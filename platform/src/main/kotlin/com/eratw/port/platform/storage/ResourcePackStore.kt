package com.eratw.port.platform.storage

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.ArrayDeque
import java.util.Locale
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * 素材包的整体结构画像：安装前先枚举一遍条目名判出来，决定逐条往哪个游戏根投放。
 *
 * - [STANDARD]：包内自带 `resources` / `sound` 段（官方补丁形态、GitHub 下载目录包裹层都算），
 *   逐条走 [ResourcePackStore.normalizeEntry] 的既有规则。
 * - [BACKGROUND_IMAGES]：官方背景图包形态——剥掉可选包裹层后根级有 `BGI.csv` 或 `背景图` 目录，
 *   条目整体改投到 `resources/背景图/`。
 * - [BGM]：裸音乐包形态——根级有 `游戏bgm` 目录，条目整体改投到 `sound/` 下。
 * - [MIXED]：保留给「标准布局与裸结构混装」的包；当前决策树穷尽在其余四支，本版不产生它。
 * - [UNRECOGNIZED]：认不出布局，全部跳过并给 [StructureNoteKind.UNRECOGNIZED_LAYOUT] 告警。
 */
enum class PackProfile { STANDARD, BACKGROUND_IMAGES, BGM, MIXED, UNRECOGNIZED, FOLDER }

/**
 * 直接导入（选文件 / 选文件夹）的投放目标。
 *
 * 与压缩包导入不同，手机玩家手里往往就是一堆图片或一个音乐文件夹；这条路径不接受
 * 压缩包，也不要求玩家先把素材重新打包。两个目标各自有一套落点与命名规则：
 * 背景图进 `resources/背景图/`（必要时自动生成 CSV 索引），BGM 进 `sound/游戏bgm/<地点>/`
 * （必要时把散落文件编号成 `0-9`）。
 */
enum class MaterialTarget { BACKGROUND, BGM }

/**
 * 直接导入的目录判定结论（只按相对路径判，不读文件内容）。
 *
 * - [BACKGROUND_TREE]：源里带 `resources/` 或 `背景图/` 层，投放时剥到图片目录；
 * - [BACKGROUND_FLAT]：源根本身就是图片 / CSV 目录；
 * - [BGM_TREE]：源里带 `sound/`、`游戏bgm/`，或首段就是地点目录（集合目录）；
 * - [BGM_LOCATION]：源根是单个地点目录（根级音频，或只有 上午/下午/晚上/战斗BGM 子目录）；
 * - [UNSUPPORTED_FILES]：有文件，但没有该目标能用的扩展名（例如 BGM 卡里选了图片）；
 * - [EMPTY]：源是空的。
 */
enum class MaterialLayout {
    BACKGROUND_TREE,
    BACKGROUND_FLAT,
    BGM_TREE,
    BGM_LOCATION,
    UNSUPPORTED_FILES,
    EMPTY,
}

/**
 * 结构化体检结论的类别。
 *
 * 玩家可见句子由 app 侧 copy 表按 kind 映射（三语同加），platform 层只描述文件系统事实；
 * [StructureNote.detail] 放路径原话供取证，不写玩家句子。
 */
enum class StructureNoteKind {
    /** 标准包里写进 `sound/` 但不在 `sound/游戏bgm/` 下的音频：游戏按地点取曲，这种文件不会被播。 */
    BGM_OUTSIDE_GAME_BGM,

    /** 本次往 `resources/背景图/` 投放了图片，但同批没有 CSV 索引：资源表里登记不出这些图。 */
    IMAGES_WITHOUT_CSV_INDEX,

    /** 导入了 0 字节的 CSV：索引表为空，等同于没有索引。 */
    EMPTY_CSV_INDEX,

    /** 整包认不出布局，所有条目被跳过。 */
    UNRECOGNIZED_LAYOUT,

    /** BGM 没有地点目录名，已放进兜底目录：游戏按地点取曲，不改名不会播放。 */
    BGM_FALLBACK_LOCATION,

    /** 同一地点目录的 0-9 曲目槽位已满，多出的音频被跳过。 */
    BGM_TRACKS_OVERFLOW,

    /** 选中的目录里没有该目标可用的文件（扩展名全不支持）。 */
    MATERIAL_NO_USABLE_FILES,

    /** 图片已落盘，但文件名含逗号，CSV 索引无法表达这一行。 */
    BACKGROUND_INDEX_ROW_SKIPPED,

    /**
     * 来源里不是该目标素材的文件（说明 txt、0 字节目录占位、封面图等）。
     *
     * 这类文件本来就不会被导入，逐条列进「跳过」会让玩家把一份正常合集误读成失败
     * （官方 BGM 包 102 首曲目带 127 个占位/说明）；折成一条读数，[StructureNote.detail]
     * 存文件个数（十进制字符串），玩家面句子由 app 侧 copy 表按 kind 映射。
     */
    MATERIAL_IGNORED_FILES,
}

/** 一条结构体检结论；[detail] 是取证用的路径原话（不是玩家句子）。 */
data class StructureNote(val kind: StructureNoteKind, val detail: String)

/** 包级画像的判定结果：profile 决定投放规则，wrapper 是可选的一层公共顶层目录名。 */
private data class PackLayout(val profile: PackProfile, val wrapper: String?)

/**
 * 一次资源包导入的读数（与游戏语义无关，只描述文件系统事实）。
 *
 * [skippedSamples] 是**可直接上屏**的跳过原因（最多 [SKIP_SAMPLES_MAX] 条）：
 * 压缩包夹带 readme、`__MACOSX` 目录、越界路径都是常态，页面要把「跳了什么、为什么」
 * 原样告知，而不是静默只报一个总数。
 *
 * [profile] 与 [structureNotes] 是本次导入的结构体检面：前者说明按哪种包形态投放，
 * 后者把「图片没索引」「BGM 放错目录」这类**只会静默不生效**的问题点出来。
 */
data class ResourcePackReport(
    val installedFiles: Int,
    val installedBytes: Long,
    val overwrittenFiles: Int,
    val skippedEntries: Int,
    val roots: List<String>,
    val skippedSamples: List<String>,
    val error: String? = null,
    /** 本次导入的包级画像；默认值让既有构造点（不区分布局的老调用）保持源码兼容。 */
    val profile: PackProfile = PackProfile.STANDARD,
    /** 结构体检结论；空列表 = 本次导入没发现结构问题。 */
    val structureNotes: List<StructureNote> = emptyList(),
    /** 直接导入的目录判定结论；压缩包导入为 null。 */
    val layout: MaterialLayout? = null,
    /** 本次自动生成的背景图索引（相对游戏根路径）；没生成 = null。 */
    val generatedIndex: String? = null,
    /** 本次因为文件名不是 `0-9` 而被自动编号的 BGM 文件数。 */
    val autoRenamed: Int = 0,
)

/** 游戏根里当前可用的资源面读数（设置页 Mod 档的现状行）。 */
data class ResourcePackStatus(
    /** `resources/背景图` 下的图片数（png/jpg/webp/bmp/gif）。 */
    val backgroundImages: Int,
    /** `resources/背景图` 下 CSV 的非空、非注释行数（背景图靠 CSV 注册进资源表）。 */
    val backgroundIndexRows: Int,
    /** `sound/游戏bgm` 下可播放音频文件数（mp3/wav/ogg/flac/m4a/aac/opus）。 */
    val bgmTracks: Int,
    /** 上述音频分布在多少个地点子目录里。 */
    val bgmFolders: Int,
) {
    val backgroundReady: Boolean get() = backgroundImages > 0 && backgroundIndexRows > 0
    val bgmReady: Boolean get() = bgmTracks > 0
}

/**
 * 直接导入的一个源条目：[name] 是相对源根的路径（恒 `/` 分隔），[open] 打开时才能读内容。
 *
 * 不把整份字节先读进堆：背景图包动辄几百 MB，SAF 目录也不该先复制到缓存再导；
 * [ResourcePackStore.installSource] 逐条流式写入游戏根。
 */
data class MaterialEntry(
    val name: String,
    /** 声明大小（未知 = -1）；实际写入量始终以 [ResourcePackStore] 的预算闸为准。 */
    val size: Long = -1L,
    val open: () -> InputStream,
)

/**
 * 直接导入的源。
 *
 * platform 侧提供本机目录实现 [FolderMaterialSource]；app 侧用 SAF 的目录树实现同一接口，
 * 于是「选文件夹」不再需要先把整棵树复制进缓存。
 *
 * [label] 对 BGM 有语义：单文件 / 根级音频没有地点目录时，就用它当地点名（文件夹名，
 * 或玩家在卡里填的地点名）。
 */
interface MaterialSource {
    val label: String
    fun entries(): List<MaterialEntry>
}

/** 本机目录源：递归列出文件（跳过隐藏目录与 `__MACOSX`），按相对路径排序保证编号可复现。 */
class FolderMaterialSource(private val folder: File) : MaterialSource {

    override val label: String get() = folder.name

    override fun entries(): List<MaterialEntry> {
        if (!folder.isDirectory) return emptyList()
        val out = ArrayList<MaterialEntry>()
        val stack = ArrayDeque<File>()
        val visited = HashSet<String>()
        stack.addLast(folder)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
            if (!visited.add(canonical)) continue
            val children = dir.listFiles() ?: continue
            for (child in children) {
                when {
                    child.isDirectory -> {
                        if (!child.name.startsWith(".") &&
                            !child.name.equals("__MACOSX", ignoreCase = true)
                        ) {
                            stack.addLast(child)
                        }
                    }
                    child.isFile -> {
                        val relative = child.relativeTo(folder).path.replace('\\', '/')
                        out += MaterialEntry(relative, child.length()) { FileInputStream(child) }
                    }
                }
            }
        }
        out.sortBy { it.name }
        return out
    }
}

/**
 * 素材类 Mod（背景图、BGM）的导入存储层。
 *
 * ## 为什么与口上导入分开
 *
 * 口上包是**源文本**：要按 base 产物编译成 `.patch`（[com.eratw.port.platform.storage.ModStore]
 * 只收 `.ERB`/`.ERH`）。素材包是**纯文件投放**：图片、音频、CSV 索引，没有编译、没有游戏语义，
 * 只需安全落盘。两条链共用 SAF 选择器与「重启生效」出口，但存储/校验完全不同，所以各自独立。
 *
 * ## 目录合同（逐条对应 4.981 语料的读取口径）
 *
 * - `resources/`：`AppContents` 递归扫该子树下的 CSV（`AppContents.cs:115`）；背景图补丁的资源
 *   放在 `resources/背景图`，靠 CSV 把图片名注册进资源表（`GETINFODLC.ERB:1259` 的
 *   `SPRITECREATED("通用浴室_0_0")` 检测的就是这条登记）。
 * - `sound/`：`PLAYSOUND` / `PLAYBGM` 以 `.<gameRoot>/sound/` 为根（`FunctionIdentifier.cs:2622`、
 *   `:2691`）。4.981 音乐补丁按 `sound/游戏bgm/<地点>/<0-9>.MP3` 取曲
 *   （`魔改版更新记录文档/添加音乐方法.txt`、`ERB/DLC/音乐补丁/音乐播放.ERB:1-22`），
 *   还支持 `<地点>/上午|下午|晚上/` 与 `<地点>/战斗BGM/` 子目录。
 *
 * 标准包（[PackProfile.STANDARD]）只接收这两个顶层根下的条目，其它顶层目录一律跳过并在
 * 报告里点名。玩家下载的素材还常见两种**裸结构**，由包级画像（`profile`）整体改投：
 * 官方 `BGI_Resource` 背景图包（根级 `BGI.csv` + 编号子目录）投到 `resources/背景图/`，
 * 裸 `游戏bgm/` 音乐目录投到 `sound/`。认不出布局的整包跳过并给结构告警。
 *
 * ## 路径安全
 *
 * 条目名先归一（`\\`→`/`、丢空段与 `.`），`..`、盘符冒号、绝对路径、隐藏段、`__MACOSX`
 * 一律拒收；STANDARD 再从**左起第一个** `resources` / `sound` 段开始取相对路径——这样
 * GitHub 下载的 zip（外面包一层 `eratw-...-master/`）、直接压 `resources/` 的包，以及
 * 官方 BGM 的 .7z（LZMA2 solid）都能导入；
 * 而不需要玩家重新打包。其余画像先剥掉可选包裹层，剥完的路径同样过全部安全闸（含隐藏段
 * 末级校验），再拼到目标根下。最终目标仍做一次 canonical 前缀校验，双重保证不越出游戏根。
 *
 * ## 大小与原子性
 *
 * 逐文件「同目录 `.import-tmp` → fsync → rename」：任何一步失败只删临时文件，已有文件
 * 要么是旧完整版、要么是新完整版。单文件上限 [MAX_ENTRY_BYTES]、单批总量上限
 * [MAX_TOTAL_BYTES]：背景图包在数百 MB 量级，超过上限按失败上屏（已落盘的文件保留，
 * 报告 `error` 说明从哪一条开始停）。
 *
 * 纯文件系统逻辑（不碰 Android API），`ResourcePackStoreTest` 在纯 JVM 下覆盖。
 */
class ResourcePackStore(private val gameRoot: File) {

    /**
     * 把 [archive] 里的素材条目投放到游戏根。
     *
     * 两趟：第一趟只枚举条目名（不读条目内容、不整包进堆）判出包级画像 [PackProfile]；
     * 第二趟按画像逐条投放到对应游戏根。不抛压缩包解析失败：打不开 / 条目损坏都折进
     * [ResourcePackReport.error]（调用方直接上屏）。已成功落盘的文件保留——部分导入比
     * 整批丢弃更接近玩家的预期，报告里会说清停在哪一条。
     *
     * 结构体检（[ResourcePackReport.structureNotes]）只按**真正落盘成功**的条目取样：
     * 「图片没索引」「CSV 空表」「BGM 不在 游戏bgm 下」不会报错，只会让素材在游戏里静默
     * 不生效，所以要单独点名。
     *
     * BGM 画像包里的非 mp3（说明 txt / 0 字节目录占位）不投放、也不计入「跳过」，
     * 折成一条 [StructureNoteKind.MATERIAL_IGNORED_FILES] 读数——否则官方 BGM 包的
     * 127 个占位会把 102 首正常曲目淹成「失败」。
     */
    fun install(archive: File): ResourcePackReport {
        var installed = 0
        var bytes = 0L
        var overwritten = 0
        var skipped = 0
        val roots = sortedSetOf<String>()
        val samples = ArrayList<String>()
        var error: String? = null
        val notes = ArrayList<StructureNote>()
        var profile = PackProfile.UNRECOGNIZED

        fun skip(entry: String, reason: String) {
            skipped++
            if (samples.size < SKIP_SAMPLES_MAX) samples += "$entry —— $reason"
        }

        val pack = try {
            openPack(archive)
        } catch (e: Exception) {
            // 打不开的形态不止 IOException（不同 Android 版本的 zip 实现会抛别的运行时异常），
            // 全部折成报告错误；Error（OOM 等）不接，别把真正的资源事故伪装成「包坏了」。
            return ResourcePackReport(
                installedFiles = 0,
                installedBytes = 0,
                overwrittenFiles = 0,
                skippedEntries = 0,
                roots = emptyList(),
                skippedSamples = emptyList(),
                error = "打不开压缩包（${e.message ?: e::class.simpleName}）",
                profile = PackProfile.UNRECOGNIZED,
                structureNotes = listOf(
                    StructureNote(StructureNoteKind.UNRECOGNIZED_LAYOUT, archive.name),
                ),
            )
        }
        pack.use { reader ->
            val names = entryNames(reader.entries)
            val layout = detectLayout(names)
            profile = layout.profile
            if (profile == PackProfile.UNRECOGNIZED) {
                val detail = names.take(UNRECOGNIZED_DETAIL_SAMPLES).joinToString(", ")
                    .ifEmpty { archive.name }
                notes += StructureNote(StructureNoteKind.UNRECOGNIZED_LAYOUT, detail)
            }
            var backgroundImage = false
            var backgroundCsv = false
            var ignored = 0
            val bgmOutside = ArrayList<String>()
            val emptyCsv = ArrayList<String>()

            var seen = 0
            for (entry in reader.entries) {
                if (entry.isDirectory) continue
                seen++
                if (seen > MAX_ENTRIES) {
                    error = "条目数超过上限 $MAX_ENTRIES，后续条目未导入"
                    break
                }
                // BGM 画像包里只有 mp3 会被游戏播放：说明 txt / 0 字节占位不该落进 sound/，
                // 也不该逐条算「跳过」（官方 BGM 包 127 个占位会把正常导入读成失败）。
                if (!profileAccepts(layout.profile, entry.name)) {
                    ignored++
                    continue
                }
                val name = routeEntry(entry.name, layout)
                if (name == null) {
                    skip(entry.name, skipReason(entry.name, layout.profile))
                    continue
                }
                val entrySize = entry.size
                if (entrySize > MAX_ENTRY_BYTES) {
                    skip(entry.name, "单个文件超过 ${MAX_ENTRY_BYTES / MIB} MiB")
                    continue
                }
                // 额度耗尽后不再逐条试写：entry.size 可能撒谎（-1/0），只靠 extractEntry
                // 的预算会把后面每一张图都记成「跳过」并空转整本目录。
                if (bytes >= MAX_TOTAL_BYTES || bytes + maxOf(entrySize, 0) > MAX_TOTAL_BYTES) {
                    error = "解包总量超过 ${MAX_TOTAL_BYTES / MIB} MiB 上限，后续条目未导入"
                    break
                }
                val target = File(gameRoot, name)
                if (!isInsideGameRoot(target)) {
                    skip(entry.name, "目标路径越出游戏目录")
                    continue
                }
                val existed = target.isFile
                val budget = minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - bytes)
                val written = try {
                    extractEntry(entry.open(), target, budget)
                } catch (e: IOException) {
                    skip(entry.name, e.message ?: "写入失败")
                    continue
                }
                bytes += written
                installed++
                if (existed) overwritten++
                roots += name.substringBefore('/')
                // 结构体检只看落盘成功的条目：被上限挡掉的不算「已导入」。
                if (isInsideBackgroundDir(name)) {
                    when (extensionOf(name)) {
                        "csv" -> {
                            backgroundCsv = true
                            if (written == 0L) emptyCsv += name
                        }
                        in IMAGE_EXTENSIONS -> backgroundImage = true
                    }
                }
                if ((profile == PackProfile.STANDARD || profile == PackProfile.MIXED) &&
                    isBgmOutsideGameBgm(name)
                ) {
                    bgmOutside += name
                }
            }
            for (path in bgmOutside) {
                notes += StructureNote(StructureNoteKind.BGM_OUTSIDE_GAME_BGM, path)
            }
            for (path in emptyCsv) {
                notes += StructureNote(StructureNoteKind.EMPTY_CSV_INDEX, path)
            }
            if (backgroundImage && !backgroundCsv) {
                notes += StructureNote(
                    StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX,
                    "$RESOURCES_DIR/$BACKGROUND_DIR",
                )
            }
            if (ignored > 0) {
                notes += StructureNote(StructureNoteKind.MATERIAL_IGNORED_FILES, ignored.toString())
            }
        }
        return ResourcePackReport(
            installedFiles = installed,
            installedBytes = bytes,
            overwrittenFiles = overwritten,
            skippedEntries = skipped,
            roots = roots.toList(),
            skippedSamples = samples,
            error = error,
            profile = profile,
            structureNotes = notes,
        )
    }

    /**
     * 直接把一个本机目录投放到 [target]（背景图 / BGM）。
     *
     * 目录判定走 [detectMaterialLayout]：带 `resources/背景图` 层的树整棵剥到图片目录；
     * 裸图片目录按散图投放，且没有 CSV 索引时自动生成 `自动索引.csv`；BGM 目录带
     * `游戏bgm` 层就按层投放，选中的是单个地点目录就把目录名当地点，根级散落的音频
     * 自动编号成 `0-9.mp3`。
     */
    fun installFolder(folder: File, target: MaterialTarget): ResourcePackReport =
        installSource(FolderMaterialSource(folder), target)

    /**
     * 直接导入的正体：先枚举（不读内容）判目录，再逐条流式写到游戏根。
     *
     * 与 [install] 共用同一套路径安全、单文件/单批上限与原子落盘；差异只在「没有压缩包」
     * 与两个目标的投放/命名规则。单条失败只跳过该条，不让整批陪葬。
     *
     * 非目标扩展名（BGM 的说明 txt / 0 字节占位、背景目录里的 readme）不投放、也不计入
     * 「跳过」，折成一条 [StructureNoteKind.MATERIAL_IGNORED_FILES] 读数。
     */
    fun installSource(source: MaterialSource, target: MaterialTarget): ResourcePackReport {
        var installed = 0
        var bytes = 0L
        var overwritten = 0
        var skipped = 0
        var renamed = 0
        var ignored = 0
        var error: String? = null
        val roots = sortedSetOf<String>()
        val samples = ArrayList<String>()
        val notes = ArrayList<StructureNote>()

        fun skip(entry: String, reason: String) {
            skipped++
            if (samples.size < SKIP_SAMPLES_MAX) samples += "$entry —— $reason"
        }

        val entries = try {
            source.entries()
        } catch (e: Exception) {
            return ResourcePackReport(
                installedFiles = 0,
                installedBytes = 0,
                overwrittenFiles = 0,
                skippedEntries = 0,
                roots = emptyList(),
                skippedSamples = emptyList(),
                error = "读取目录失败（${e.message ?: e::class.simpleName}）",
                profile = PackProfile.FOLDER,
                structureNotes = listOf(
                    StructureNote(
                        StructureNoteKind.MATERIAL_NO_USABLE_FILES,
                        source.label.ifBlank { "-" },
                    ),
                ),
                layout = MaterialLayout.EMPTY,
            )
        }

        // 第一趟：只做安全归一（不读文件内容），够判定目录即可。
        val normalized = ArrayList<Pair<MaterialEntry, List<String>>>()
        for (entry in entries) {
            val segments = safeSegments(entry.name)
            if (segments == null || segments.any { it.startsWith(".") }) {
                skip(entry.name, "路径不安全或为隐藏文件")
                continue
            }
            normalized += entry to segments
            if (normalized.size > MAX_ENTRIES) {
                error = "条目数超过上限 $MAX_ENTRIES，后续条目未导入"
                break
            }
        }
        val limited = normalized.take(MAX_ENTRIES)
        val layout = detectMaterialLayout(limited.map { it.second.joinToString("/") }, target)
        if (layout == MaterialLayout.EMPTY || layout == MaterialLayout.UNSUPPORTED_FILES) {
            notes += StructureNote(
                StructureNoteKind.MATERIAL_NO_USABLE_FILES,
                source.label.ifBlank { "-" },
            )
        }

        val indexRows = ArrayList<Pair<String, String>>()
        var otherIndexNames: MutableSet<String>? = null
        var unindexedImages = 0
        var backgroundImage = false
        var backgroundCsv = false
        val emptyCsv = ArrayList<String>()
        val overflowDirs = LinkedHashSet<String>()
        var fallbackNoted = false
        val planned = HashSet<String>()
        val usedNumbers = HashMap<String, MutableSet<Int>>()

        for ((entry, segments) in limited) {
            val ext = extensionOf(segments.last())
            val targetPath: String
            var autoRename = false
            when (target) {
                MaterialTarget.BACKGROUND -> {
                    val isImage = ext in IMAGE_EXTENSIONS
                    val isCsv = ext == "csv"
                    if (!isImage && !isCsv) {
                        ignored++
                        continue
                    }
                    val body = backgroundBody(segments)
                    if (body == null) {
                        skip(entry.name, "不在 resources/背景图 之下")
                        continue
                    }
                    targetPath = "$RESOURCES_DIR/$BACKGROUND_DIR/${body.joinToString("/")}"
                }

                MaterialTarget.BGM -> {
                    if (ext != "mp3") {
                        ignored++
                        continue
                    }
                    val route = bgmRoute(segments)
                    if (route == null) {
                        skip(entry.name, "不在 sound/游戏bgm 之下")
                        continue
                    }
                    val label = sanitizeLocation(source.label)
                    val location = route.location ?: label ?: FALLBACK_BGM_LOCATION
                    val fallback = route.location == null &&
                        (layout == MaterialLayout.BGM_TREE || label == null)
                    if (fallback && !fallbackNoted) {
                        notes += StructureNote(StructureNoteKind.BGM_FALLBACK_LOCATION, location)
                        fallbackNoted = true
                    }
                    val dirRel = (listOf(SOUND_DIR, BGM_DIR, location) + route.dirs)
                        .joinToString("/")
                    val used = usedNumbers.getOrPut(dirRel) { existingTrackNumbers(dirRel) }
                    val stem = route.fileName.substringBeforeLast('.')
                    val track = stem.toIntOrNull()?.takeIf { it in 0..9 }
                    val fileName = if (track != null) {
                        used += track
                        route.fileName
                    } else {
                        val free = (0..9).firstOrNull { it !in used }
                        if (free == null) {
                            if (overflowDirs.add(dirRel)) {
                                notes += StructureNote(StructureNoteKind.BGM_TRACKS_OVERFLOW, dirRel)
                            }
                            skip(entry.name, "该地点已有 0-9 共 10 首，未导入")
                            continue
                        }
                        used += free
                        autoRename = true
                        "$free.$ext"
                    }
                    targetPath = "$dirRel/$fileName"
                }
            }
            if (!planned.add(targetPath)) {
                skip(entry.name, "同一批里有同名文件")
                continue
            }
            if (entry.size > MAX_ENTRY_BYTES) {
                skip(entry.name, "单个文件超过 ${MAX_ENTRY_BYTES / MIB} MiB")
                continue
            }
            if (bytes >= MAX_TOTAL_BYTES || (entry.size > 0 && bytes + entry.size > MAX_TOTAL_BYTES)) {
                error = "导入总量超过 ${MAX_TOTAL_BYTES / MIB} MiB 上限，后续条目未导入"
                break
            }
            val targetFile = File(gameRoot, targetPath)
            if (!isInsideGameRoot(targetFile)) {
                skip(entry.name, "目标路径越出游戏目录")
                continue
            }
            val existed = targetFile.isFile
            val budget = minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - bytes)
            val written = try {
                extractEntry(entry.open(), targetFile, budget)
            } catch (e: Exception) {
                // SAF 源可能抛 SecurityException / FileNotFoundException，都按单条失败处理。
                skip(entry.name, e.message ?: "写入失败")
                continue
            }
            bytes += written
            installed++
            if (existed) overwritten++
            if (autoRename) renamed++
            roots += targetPath.substringBefore('/')
            if (target == MaterialTarget.BACKGROUND) {
                val relBody = targetPath.removePrefix("$RESOURCES_DIR/$BACKGROUND_DIR/")
                when (extensionOf(relBody)) {
                    "csv" -> {
                        backgroundCsv = true
                        if (written == 0L) emptyCsv += targetPath
                    }
                    in IMAGE_EXTENSIONS -> {
                        backgroundImage = true
                        val stem = relBody.substringAfterLast('/').substringBeforeLast('.')
                        if (stem.isBlank() || stem.contains(',') || relBody.contains(',')) {
                            unindexedImages++
                            notes += StructureNote(
                                StructureNoteKind.BACKGROUND_INDEX_ROW_SKIPPED,
                                relBody,
                            )
                        } else {
                            val indexed = otherIndexNames
                                ?: existingBackgroundIndexNames()
                                    .toMutableSet()
                                    .also { otherIndexNames = it }
                            if (indexed.add(stem.uppercase(Locale.ROOT))) {
                                indexRows += stem to relBody
                            }
                        }
                    }
                }
            }
        }

        for (path in emptyCsv) {
            notes += StructureNote(StructureNoteKind.EMPTY_CSV_INDEX, path)
        }
        if (ignored > 0) {
            notes += StructureNote(StructureNoteKind.MATERIAL_IGNORED_FILES, ignored.toString())
        }

        var generatedIndex: String? = null
        if (target == MaterialTarget.BACKGROUND && backgroundImage && !backgroundCsv) {
            if (indexRows.isNotEmpty()) {
                val indexPath = "$RESOURCES_DIR/$BACKGROUND_DIR/$AUTO_INDEX_NAME"
                val targetFile = File(gameRoot, indexPath)
                val oldLines = readAutoIndexLines()
                val oldByName = HashMap<String, Int>()
                oldLines.forEachIndexed { index, line ->
                    val name = line.substringBefore(',').trim()
                    if (name.isNotEmpty()) oldByName[name.uppercase(Locale.ROOT)] = index
                }
                for ((stem, relBody) in indexRows) {
                    val line = "$stem,$relBody"
                    val existing = oldByName[stem.uppercase(Locale.ROOT)]
                    if (existing != null) oldLines[existing] = line else oldLines += line
                }
                val content = oldLines.joinToString("\n", postfix = "\n")
                val oldContent = runCatching { targetFile.readText() }.getOrNull()
                if (content != oldContent) {
                    val existed = targetFile.isFile
                    val written = try {
                        extractEntry(
                            ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
                            targetFile,
                            MAX_ENTRY_BYTES,
                        )
                    } catch (e: Exception) {
                        notes += StructureNote(
                            StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX,
                            "$RESOURCES_DIR/$BACKGROUND_DIR",
                        )
                        null
                    }
                    if (written != null) {
                        installed++
                        bytes += written
                        if (existed) overwritten++
                        roots += RESOURCES_DIR
                    }
                }
                generatedIndex = indexPath
            } else if (unindexedImages > 0) {
                notes += StructureNote(
                    StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX,
                    "$RESOURCES_DIR/$BACKGROUND_DIR",
                )
            }
        }

        return ResourcePackReport(
            installedFiles = installed,
            installedBytes = bytes,
            overwrittenFiles = overwritten,
            skippedEntries = skipped,
            roots = roots.toList(),
            skippedSamples = samples,
            error = error,
            profile = PackProfile.FOLDER,
            structureNotes = notes,
            layout = layout,
            generatedIndex = generatedIndex,
            autoRenamed = renamed,
        )
    }

    /**
     * 落一个条目：临时文件 → 写满 → fsync → rename（同目录，POSIX 原子覆盖）。
     *
     * [maxBytes] 是单文件与整批剩余额度取小：压缩包声明的大小可以撒谎，实际字节
     * 始终按写入量核对。
     */
    private fun extractEntry(input: InputStream, target: File, maxBytes: Long): Long {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建目录：${parent.absolutePath}")
        }
        // 临时名带纳秒后缀：素材包里可以有合法的 `x.import-tmp` 条目，固定临时名会在
        // 落 `x` 时先截断它、再 rename 走，把玩家包里的真实文件顺手抹掉。
        val tmp = File(parent ?: File("."), "${target.name}.${System.nanoTime()}.$IMPORT_TMP_SUFFIX")
        var written = 0L
        try {
            input.use { source ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > maxBytes) {
                            throw IOException("超出单文件或本批总量上限（${maxBytes / MIB} MiB）")
                        }
                        out.write(buffer, 0, read)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            replace(tmp, target)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw e
        }
        return written
    }

    /** 原子替换目标：POSIX 上 rename 覆盖；Windows（单测）目标已存在时先删再改名。 */
    private fun replace(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        if (target.exists() && !target.delete()) {
            throw IOException("无法替换既有文件：${target.name}")
        }
        if (!tmp.renameTo(target)) throw IOException("无法落盘：${target.name}")
    }

    /** 目标地点目录里已经被占用的 0-9 曲目槽位（只数 mp3，大小写不敏感）。 */
    private fun existingTrackNumbers(dirRel: String): MutableSet<Int> {
        val used = HashSet<Int>()
        File(gameRoot, dirRel).listFiles()?.forEach { file ->
            if (!file.isFile || extensionOf(file.name) != "mp3") return@forEach
            file.name.substringBeforeLast('.')
                .toIntOrNull()
                ?.takeIf { it in 0..9 }
                ?.let { used += it }
        }
        return used
    }

    /**
     * 现有背景图 CSV 已登记的名字（大写），自动索引据此去重。
     *
     * 自动索引自己那份不算：它是本次可以改写的表，同名行会被替换而不是跳过。
     */
    private fun existingBackgroundIndexNames(): Set<String> {
        val dir = File(File(gameRoot, RESOURCES_DIR), BACKGROUND_DIR)
        if (!dir.isDirectory) return emptySet()
        val names = HashSet<String>()
        dir.walkTopDown().forEach { file ->
            if (!file.isFile || !file.name.endsWith(".csv", ignoreCase = true)) return@forEach
            if (file.name.equals(AUTO_INDEX_NAME, ignoreCase = true)) return@forEach
            runCatching {
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty() || trimmed.startsWith(';')) continue
                        val name = trimmed.substringBefore(',').trim()
                        if (name.isNotEmpty()) names += name.uppercase(Locale.ROOT)
                    }
                }
            }
        }
        return names
    }

    /** 自动索引的现有行（丢掉空行与 `;` 注释）；读不出来按空表处理。 */
    private fun readAutoIndexLines(): MutableList<String> {
        val file = File(File(File(gameRoot, RESOURCES_DIR), BACKGROUND_DIR), AUTO_INDEX_NAME)
        if (!file.isFile) return ArrayList()
        return runCatching {
            file.bufferedReader().useLines { lines ->
                lines.map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() && !it.trimStart().startsWith(";") }
                    .toMutableList()
            }
        }.getOrElse { ArrayList() }
    }

    /**
     * 打开压缩包并选对条目名编码。
     *
     * Windows 资源管理器 / 7-Zip 默认按系统 OEM 代码页写非 ASCII 条目名、且不置 UTF-8 标志；
     * 中文 Windows 打包的 `resources/背景图/...` 在 UTF-8 下会解成一串替换符，归一后虽然
     * 还能认出 ASCII 的 `resources` 根，但子目录名已经错了，素材会落进乱码目录。
     * 这里的判定很窄：UTF-8 解出包名后只要含 U+FFFD，就改用 GBK（中文 Windows 的默认
     * 代码页）再开一次；GBK 也解不干净才退回 UTF-8 原样导入（报告里仍会点名跳过项）。
     *
     * TODO(verify)：CP932（日文 Windows）与 GBK 的字节区间高度重叠，无法靠 U+FFFD 区分；
     * 卡点 = 一份日文打包的背景图包样本。当前中文社区包优先。
     *
     * API 24 以下没有 `ZipFile(File, Charset)` 构造器：调用抛出的 `NoSuchMethodError` 会被
     * 这里的 `runCatching` 接住并回落到 UTF-8（不崩），乱码条目由 [normalizeEntry] 拒收，
     * 报告里写明「条目名编码无法识别」。
     */
    private fun openArchive(archive: File): ZipFile {
        // JDK 17+ 会直接拒开「CEN 名字不是合法 UTF-8」的包（bad entry name），所以
        // UTF-8 这一支既要接住打开失败，也要接住打开成功但名字带替换符的两种形态。
        // UTF-8 实例在 GBK 判定结束前**不关**：GBK 打不开时它就是唯一可用的回落，
        // 先关再返回会交出一只 closed ZipFile，调用方一读条目就炸。
        val utf8 = runCatching { ZipFile(archive) }
        val first = utf8.getOrNull()
        if (first != null && !hasMojibake(first)) return first
        val gbk = runCatching { Charset.forName("GBK") }.getOrNull()
        val archiveGbk = gbk?.let { runCatching { ZipFile(archive, it) }.getOrNull() }
        if (archiveGbk != null) {
            if (!hasMojibake(archiveGbk)) {
                first?.close()
                return archiveGbk
            }
            archiveGbk.close()
        }
        if (first != null) return first
        throw utf8.exceptionOrNull() ?: IOException("打不开压缩包：${archive.name}")
    }

    private fun hasMojibake(zip: ZipFile): Boolean {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            if (entries.nextElement().name.indexOf('\uFFFD') >= 0) return true
        }
        return false
    }

    /** 归一后的条目名是否仍落在游戏根之内（canonical 前缀，防符号链接/异常路径）。 */
    private fun isInsideGameRoot(target: File): Boolean {
        val root = runCatching { gameRoot.canonicalFile }.getOrElse { gameRoot.absoluteFile }
        val file = runCatching { target.canonicalFile }.getOrElse { target.absoluteFile }
        val rootPath = root.path.trimEnd(File.separatorChar) + File.separatorChar
        return file.path.startsWith(rootPath)
    }

    /** 按魔数选解包器：官方 BGM 包是 .7z（LZMA2 solid），背景图包是 .zip。 */
    private fun openPack(archive: File): PackReader =
        when (archiveFormat(archive)) {
            PackFormat.ZIP -> ZipPackReader(openArchive(archive))
            PackFormat.SEVEN_ZIP -> SevenZipPackReader(
                SevenZFile.builder().setFile(archive).get(),
            )
        }

    private fun archiveFormat(archive: File): PackFormat {
        val header = ByteArray(6)
        var read = 0
        FileInputStream(archive).use { input ->
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
        }
        if (read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()) {
            return PackFormat.ZIP
        }
        if (
            read >= 6 &&
            header[0] == 0x37.toByte() && header[1] == 0x7A.toByte() &&
            header[2] == 0xBC.toByte() && header[3] == 0xAF.toByte() &&
            header[4] == 0x27.toByte() && header[5] == 0x1C.toByte()
        ) {
            return PackFormat.SEVEN_ZIP
        }
        throw IOException("不支持的压缩包格式（素材包只收 zip / 7z）")
    }

    companion object {
        const val RESOURCES_DIR = "resources"
        const val SOUND_DIR = "sound"

        /** 背景图补丁的资源目录（`GETINFODLC.ERB:1259`）。 */
        const val BACKGROUND_DIR = "背景图"

        /** 4.981 音乐补丁的曲库目录（`音乐播放.ERB:5-22` 的 `游戏bgm/`）。 */
        const val BGM_DIR = "游戏bgm"

        /** 单文件落盘上限：背景图单张远小于它，挡住误选的视频/整盘压缩包。 */
        const val MAX_ENTRY_BYTES: Long = 256L * 1024 * 1024

        /** 单批总量上限：4.981 随包版权素材已是 743 MiB，素材包再大就不像一个补丁了。 */
        const val MAX_TOTAL_BYTES: Long = 3L * 1024 * 1024 * 1024

        /** 条目数上限：挡住深层目录轰炸式压缩包。 */
        const val MAX_ENTRIES: Int = 20_000

        /** 报告里保留的跳过原因条数（其余只计数）。 */
        const val SKIP_SAMPLES_MAX: Int = 8

        /** 官方背景图包根级的索引文件名（`BGI_Resource` 包形态，大小写不敏感）。 */
        const val BGI_CSV_NAME = "BGI.csv"

        /** 散图导入时自动生成的索引文件名（放在 `resources/背景图/` 根）。 */
        const val AUTO_INDEX_NAME = "自动索引.csv"

        /** BGM 散落文件没有地点名时的兜底目录（玩家需要在游戏里改成真实地点名）。 */
        const val FALLBACK_BGM_LOCATION = "自定义"

        /** Windows / Android 都不适合进目录名的字符，地点名里出现时替换成 `_`。 */
        private const val INVALID_LOCATION_CHARS = "\\/:*?\"<>|"

        /** 4.981 音乐补丁的时间段 / 战斗子目录（`音乐播放.ERB:1-22`）。 */
        private val BGM_TIME_DIRS = setOf("上午", "下午", "晚上", "夜", "战斗BGM")

        /** UNRECOGNIZED 告警 detail 里点名的最多条目数（取证够用即可）。 */
        private const val UNRECOGNIZED_DETAIL_SAMPLES = 3

        private const val MIB = 1024L * 1024L
        private const val COPY_BUFFER = 64 * 1024
        private const val IMPORT_TMP_SUFFIX = "import-tmp"

        private val IMAGE_EXTENSIONS = setOf("png", "webp", "jpg", "jpeg", "bmp", "gif")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac", "opus")

        /**
         * 条目名 → 相对游戏根的规范路径（`resources/...` 或 `sound/...`）；不应导入返回 null。
         *
         * `\\` 当分隔符（Windows 打包器）；从**左起第一个** `resources` / `sound` 段开始取，
         * 于是外层包一层下载目录的 zip 也能直接导入。`..`、盘符冒号、绝对路径、隐藏段、
         * `__MACOSX` 一律拒收（后者是 macOS 的资源叉副本，含同名图片但内容是元数据）。
         */
        fun normalizeEntry(raw: String): String? {
            val segments = safeSegments(raw) ?: return null
            val rootIndex = segments.indexOfFirst { isMaterialRootSegment(it) }
            if (rootIndex < 0) return null
            val root = segments[rootIndex].lowercase(Locale.ROOT)
            if (rootIndex == segments.size - 1) return null
            val tail = segments.subList(rootIndex + 1, segments.size)
            // 隐藏文件/目录（`.DS_Store`、`._foo.png`）不是素材。
            if (tail.any { it.startsWith(".") }) return null
            val joined = StringBuilder(root)
            for (part in tail) joined.append('/').append(part)
            return joined.toString()
        }

        /**
         * 直接导入的目录判定：只吃相对路径（不读内容），按 [target] 只数该目标能用的扩展名。
         *
         * 背景图：任一路径带 `resources` / `背景图` 段就是树（投放时会剥到图片目录），
         * 否则是图片目录本身。BGM：带 `游戏bgm` 段是树；只有 `sound` 却没有 `游戏bgm`
         * 判「不可用」（少一层游戏取不到曲）；全是根级 mp3 或 `上午/下午/晚上/战斗BGM`
         * 子目录是单个地点目录；其余按集合目录（首段就是地点名）处理。
         */
        fun detectMaterialLayout(names: List<String>, target: MaterialTarget): MaterialLayout {
            val parsed = names.mapNotNull { raw ->
                val segments = safeSegments(raw) ?: return@mapNotNull null
                if (segments.any { it.startsWith(".") }) null else segments
            }
            if (parsed.isEmpty()) return MaterialLayout.EMPTY
            val relevant = parsed.filter { segments ->
                val ext = extensionOf(segments.last())
                when (target) {
                    MaterialTarget.BACKGROUND -> ext in IMAGE_EXTENSIONS || ext == "csv"
                    MaterialTarget.BGM -> ext == "mp3"
                }
            }
            if (relevant.isEmpty()) return MaterialLayout.UNSUPPORTED_FILES
            return when (target) {
                MaterialTarget.BACKGROUND ->
                    if (relevant.any { segments ->
                            segments.any {
                                it.equals(BACKGROUND_DIR, ignoreCase = true) ||
                                    it.equals(RESOURCES_DIR, ignoreCase = true)
                            }
                        }
                    ) {
                        MaterialLayout.BACKGROUND_TREE
                    } else {
                        MaterialLayout.BACKGROUND_FLAT
                    }

                MaterialTarget.BGM -> when {
                    relevant.any { segments ->
                        segments.any { it.equals(BGM_DIR, ignoreCase = true) }
                    } -> MaterialLayout.BGM_TREE
                    relevant.any { segments ->
                        segments.any { it.equals(SOUND_DIR, ignoreCase = true) }
                    } -> MaterialLayout.UNSUPPORTED_FILES
                    relevant.all { it.size == 1 || isBgmTimeDir(it.first()) } ->
                        MaterialLayout.BGM_LOCATION
                    else -> MaterialLayout.BGM_TREE
                }
            }
        }

        /**
         * 背景图条目 → 相对 `resources/背景图/` 的路径；不该导入返回 null。
         *
         * 带 `背景图` 段的树（官方包 / 游戏根 / `resources` 目录）剥到该段之后；
         * 只带 `resources` 却没有 `背景图` 的路径拒收（玩家选的是整个游戏根或别的资源树，
         * 不能把角色立绘之类的资源一并搬进背景图目录）；其余按图片目录本身的相对路径。
         */
        private fun backgroundBody(segments: List<String>): List<String>? {
            val index = segments.indexOfFirst { it.equals(BACKGROUND_DIR, ignoreCase = true) }
            if (index >= 0) return segments.drop(index + 1).ifEmpty { null }
            if (segments.any { it.equals(RESOURCES_DIR, ignoreCase = true) }) return null
            return segments
        }

        /** 一条 BGM 条目的落点：[location] 为 null = 源没有地点目录，由源 label 兜底。 */
        private data class BgmRoute(
            val location: String?,
            val dirs: List<String>,
            val fileName: String,
        )

        /**
         * BGM 条目 → 地点 / 子目录 / 文件名。
         *
         * - 带 `游戏bgm` 段：剥到该段之后；根级单文件没有地点，交回调用方用 label；
         * - 带 `sound` 却没有 `游戏bgm`：返回 null（游戏按 `游戏bgm` 取曲）；
         * - 根级单文件：没有地点，交回 label；
         * - 首段是 上午/下午/晚上/战斗BGM：源根是地点目录，子目录原样保留；
         * - 其余首段就是地点名（集合目录，例如直接选了一个含多个地点目录的父目录）。
         */
        private fun bgmRoute(segments: List<String>): BgmRoute? {
            val game = segments.indexOfFirst { it.equals(BGM_DIR, ignoreCase = true) }
            if (game >= 0) {
                val body = segments.drop(game + 1)
                if (body.isEmpty()) return null
                if (body.size == 1) return BgmRoute(null, emptyList(), body.single())
                return BgmRoute(body.first(), body.subList(1, body.size - 1).toList(), body.last())
            }
            if (segments.any { it.equals(SOUND_DIR, ignoreCase = true) }) return null
            if (segments.size == 1) return BgmRoute(null, emptyList(), segments.single())
            if (isBgmTimeDir(segments.first())) {
                return BgmRoute(null, segments.subList(0, segments.size - 1).toList(), segments.last())
            }
            return BgmRoute(segments.first(), segments.subList(1, segments.size - 1).toList(), segments.last())
        }

        /** 地点名归一：去掉路径分隔符等非法字符；空 / `.` / `..` / 保留名返回 null。 */
        private fun sanitizeLocation(raw: String?): String? {
            val name = raw?.trim().orEmpty()
            if (name.isEmpty() || name == "." || name == "..") return null
            if (name.equals(BGM_DIR, ignoreCase = true) ||
                name.equals(SOUND_DIR, ignoreCase = true)
            ) {
                return null
            }
            val builder = StringBuilder(name.length)
            for (ch in name) {
                builder.append(if (ch in INVALID_LOCATION_CHARS || ch.code < 0x20) '_' else ch)
            }
            val cleaned = builder.toString().trim().trimEnd('.', ' ')
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
            return cleaned
        }

        private fun isBgmTimeDir(segment: String): Boolean =
            BGM_TIME_DIRS.any { it.equals(segment, ignoreCase = true) }

        /**
         * 条目名 → 安全切分的路径段；不该导入返回 null。
         *
         * 先把 `\\` 当分隔符归一，丢掉空段与 `.`；`..`、盘符冒号、绝对路径、隐藏资源段、
         * `__MACOSX`、U+FFFD 一律拒收。这条闸对**所有** profile 生效，包级画像只是换投放
         * 目标，不会放宽任何一条安全规则。
         */
        private fun safeSegments(raw: String): List<String>? {
            if (raw.isBlank()) return null
            // 名字里出现替换符 = 条目名编码没解对（GBK 包在旧系统上的形态）：
            // 放行只会投进乱码目录，按「不导入」处理，由上屏原因说清楚。
            if (raw.indexOf('\uFFFD') >= 0) return null
            // 目录条目（zip 里以分隔符结尾）不投放：只建文件，不替玩家造空目录。
            if (raw.endsWith('/') || raw.endsWith('\\')) return null
            // 绝对路径（`/foo`、`\foo`）不是素材包里的相对条目名，一律拒收。
            if (raw.startsWith('/') || raw.startsWith('\\')) return null
            val segments = ArrayList<String>()
            for (segment in raw.replace('\\', '/').split('/')) {
                if (segment.isEmpty() || segment == ".") continue
                if (segment == "..") return null
                if (segment.indexOf(':') >= 0) return null
                segments += segment
            }
            if (segments.isEmpty()) return null
            if (segments.any { it == "__MACOSX" }) return null
            return segments
        }

        /** 该路径段是不是素材根之一（STANDARD 的判据）。 */
        private fun isMaterialRootSegment(segment: String): Boolean =
            segment.equals(RESOURCES_DIR, ignoreCase = true) ||
                segment.equals(SOUND_DIR, ignoreCase = true)

        /** 包裹层判定时不能剥的保留顶层名：这些名字本身就是布局信号。 */
        private fun isReservedTopLevel(segment: String): Boolean =
            isMaterialRootSegment(segment) ||
                segment.equals(BACKGROUND_DIR, ignoreCase = true) ||
                segment.equals(BGM_DIR, ignoreCase = true) ||
                segment.equals(BGI_CSV_NAME, ignoreCase = true)

        /**
         * 第一趟：只收集非目录条目名（不读条目内容），供包级画像判定。
         *
         * 收集到 [MAX_ENTRIES] + 1 条为止：第二趟超过该上限本来就会停下来报错，画像不需要
         * 更长的前缀，也避免恶意超长清单把名字堆满内存。
         */
        private fun entryNames(entries: List<PackEntry>): List<String> {
            val names = ArrayList<String>()
            for (entry in entries) {
                if (names.size > MAX_ENTRIES) break
                if (!entry.isDirectory) names += entry.name
            }
            return names
        }

        /**
         * 包级画像：只用条目名判定（不读内容），返回 [PackLayout]。
         *
         * 判定顺序（与报告 `profile` 一致）：
         * 1. 任一条目带 `resources` / `sound` 段 → [PackProfile.STANDARD]（逐条走既有归一，行为不变）；
         * 2. 剥掉可选的一层公共顶层目录后，根级有 `BGI.csv` 或 `背景图/` → [PackProfile.BACKGROUND_IMAGES]；
         * 3. 剥完根级有 `游戏bgm/` → [PackProfile.BGM]；
         * 4. 都认不出 → [PackProfile.UNRECOGNIZED]（全部跳过并给结构告警）。
         *
         * 解析不了条目名（越界 / 编码坏）不参与判定：它们本来就会被逐条跳过并点名，
         * 不该把整包结构毒化成「认不出」。
         */
        private fun detectLayout(rawNames: List<String>): PackLayout {
            val parsed = ArrayList<List<String>>()
            for (raw in rawNames) {
                parsed += safeSegments(raw) ?: continue
            }
            if (parsed.any { segments -> segments.any { isMaterialRootSegment(it) } }) {
                return PackLayout(PackProfile.STANDARD, null)
            }
            val wrapper = commonWrapper(parsed)
            val bodies = parsed.map { segments -> if (wrapper == null) segments else segments.drop(1) }
            if (bodies.any { isBackgroundRootSignal(it) }) {
                return PackLayout(PackProfile.BACKGROUND_IMAGES, wrapper)
            }
            if (bodies.any { it.size >= 2 && it[0].equals(BGM_DIR, ignoreCase = true) }) {
                return PackLayout(PackProfile.BGM, wrapper)
            }
            return PackLayout(PackProfile.UNRECOGNIZED, null)
        }

        /** 根级 `BGI.csv` 文件或根级 `背景图/` 目录的存在信号（都是官方背景图包形态）。 */
        private fun isBackgroundRootSignal(segments: List<String>): Boolean =
            (segments.size == 1 && segments[0].equals(BGI_CSV_NAME, ignoreCase = true)) ||
                (segments.size >= 2 && segments[0].equals(BACKGROUND_DIR, ignoreCase = true))

        /**
         * 可选的一层公共顶层目录（GitHub 下载 zip 的 `<repo>-main/`）：
         * 全部可解析条目都至少两段、首段完全相同，且该名不是布局保留名（也不是隐藏目录）。
         * 否则返回 null（不剥）。
         */
        private fun commonWrapper(parsed: List<List<String>>): String? {
            if (parsed.isEmpty()) return null
            if (parsed.any { it.size < 2 }) return null
            val first = parsed[0][0]
            if (parsed.any { it[0] != first }) return null
            if (first.startsWith(".")) return null
            if (isReservedTopLevel(first)) return null
            return first
        }

        /**
         * 单条条目名 → 目标相对路径（按包级画像改投）；不该导入返回 null。
         *
         * STANDARD 完全沿用 [normalizeEntry]；其余画像先剥可选包裹层再按画像拼目标根，
         * 剥完的路径与 STANDARD 一样过 [safeSegments] 的全部安全闸，并拒收隐藏段。
         */
        private fun routeEntry(raw: String, layout: PackLayout): String? {
            if (layout.profile == PackProfile.STANDARD) return normalizeEntry(raw)
            if (layout.profile == PackProfile.UNRECOGNIZED) return null
            val segments = safeSegments(raw) ?: return null
            val body = if (layout.wrapper == null) {
                segments
            } else {
                if (segments[0] != layout.wrapper) return null
                segments.drop(1)
            }
            if (body.isEmpty()) return null
            if (body.any { it.startsWith(".") }) return null
            return when (layout.profile) {
                PackProfile.BACKGROUND_IMAGES -> {
                    // 官方包根级 BGI.csv 直接放到 resources/背景图/；条目自带的一层 背景图/
                    // 是玩家手工整理时多出的层级，剥掉避免落成 背景图/背景图/。
                    val relative =
                        if (body[0].equals(BACKGROUND_DIR, ignoreCase = true)) body.drop(1) else body
                    if (relative.isEmpty()) return null
                    (listOf(RESOURCES_DIR, BACKGROUND_DIR) + relative).joinToString("/")
                }
                PackProfile.BGM -> (listOf(SOUND_DIR) + body).joinToString("/")
                else -> null
            }
        }

        /**
         * BGM 画像包只认 mp3（游戏按 `0-9.MP3` 取曲，`音乐播放.ERB:323-332`）。
         *
         * 其余画像不在这里过滤：STANDARD 的拒收原因要逐条点名（是玩家装错包的主要线索），
         * 混装的 BACKGROUND_IMAGES 包保持既有行为（见 `ResourcePackStoreShapeTest` 的 TODO）。
         */
        private fun profileAccepts(profile: PackProfile, raw: String): Boolean =
            profile != PackProfile.BGM || extensionOf(raw) == "mp3"

        /** 跳过原因（上屏文案面）：编码坏优先说明，其余按画像区分。 */
        private fun skipReason(raw: String, profile: PackProfile): String = when {
            raw.indexOf('\uFFFD') >= 0 -> "条目名编码无法识别（请用 UTF-8 重新打包）"
            profile == PackProfile.UNRECOGNIZED -> "包结构无法识别（未导入）"
            profile == PackProfile.STANDARD -> "不在 resources/ 或 sound/ 之下"
            else -> "条目路径不安全或为隐藏文件"
        }

        /** 目标是否落在 `resources/背景图/` 下（结构体检的图片/CSV 取样面）。 */
        private fun isInsideBackgroundDir(path: String): Boolean =
            path.startsWith("$RESOURCES_DIR/$BACKGROUND_DIR/")

        /**
         * 落进 `sound/` 却不在 `sound/游戏bgm/` 下的音频：游戏按地点在 `游戏bgm/<地点>/`
         * 取曲，这种文件不会作为 BGM 播放（`FunctionIdentifier.cs:2622`、`:2691`）。
         */
        private fun isBgmOutsideGameBgm(path: String): Boolean {
            if (!path.startsWith("$SOUND_DIR/")) return false
            if (extensionOf(path) !in AUDIO_EXTENSIONS) return false
            val rest = path.substring(SOUND_DIR.length + 1)
            return !rest.substringBefore('/').equals(BGM_DIR, ignoreCase = true)
        }

        /**
         * 游戏根里素材面现状（设置页「已导入什么」的读数）。
         *
         * 背景图**有图无索引**单列：`SPRITECREATED` 只认 CSV 登记的名字，只丢图片文件进去
         * 游戏里什么都不会出现——这是玩家最常见的失败形态，读数要替他们说出来。
         */
        fun scanStatus(gameRoot: File): ResourcePackStatus {
            val backgroundDir = File(File(gameRoot, RESOURCES_DIR), BACKGROUND_DIR)
            var backgroundImages = 0
            var backgroundRows = 0
            if (backgroundDir.isDirectory) {
                backgroundDir.walkTopDown().forEach { file ->
                    if (!file.isFile) return@forEach
                    when {
                        extensionOf(file.name) in IMAGE_EXTENSIONS -> backgroundImages++
                        file.name.endsWith(".csv", ignoreCase = true) -> backgroundRows += countCsvRows(file)
                    }
                }
            }

            var bgmTracks = 0
            val folders = HashSet<String>()
            val soundDir = File(gameRoot, SOUND_DIR)
            val bgmDir = soundDir.listFiles()
                ?.firstOrNull { it.isDirectory && it.name.equals(BGM_DIR, ignoreCase = true) }
            if (bgmDir != null) {
                bgmDir.walkTopDown().forEach { file ->
                    if (!file.isFile) return@forEach
                    if (extensionOf(file.name) !in AUDIO_EXTENSIONS) return@forEach
                    bgmTracks++
                    val parent = file.parentFile ?: return@forEach
                    if (parent != bgmDir) {
                        // 只取第一段＝地点名：4.981 的时间段（上午/下午/晚上）与「战斗BGM」
                        // 都是地点下的子目录，按末级目录算会把一个地点数成三四个。
                        val rel = parent.relativeTo(bgmDir).path.replace('\\', '/')
                        folders += rel.substringBefore('/')
                    }
                }
            }
            return ResourcePackStatus(
                backgroundImages = backgroundImages,
                backgroundIndexRows = backgroundRows,
                bgmTracks = bgmTracks,
                bgmFolders = folders.size,
            )
        }

        /** CSV 的非空、非 `;` 注释行数；读取失败按 0 行（读数不该把设置页拖崩）。 */
        private fun countCsvRows(file: File): Int = runCatching {
            file.bufferedReader().useLines { lines ->
                lines.count { line ->
                    val trimmed = line.trim()
                    trimmed.isNotEmpty() && !trimmed.startsWith(";")
                }
            }
        }.getOrDefault(0)

        /** 小写扩展名（无扩展名返回空串；locale 无关）。 */
        private fun extensionOf(name: String): String {
            val dot = name.lastIndexOf('.')
            if (dot < 0 || dot == name.length - 1) return ""
            return name.substring(dot + 1).lowercase(Locale.ROOT)
        }
    }
}

/** 素材包压缩格式：zip 走 JDK 自带 ZipFile，7z 走 commons-compress。 */
private enum class PackFormat { ZIP, SEVEN_ZIP }

/**
 * zip / 7z 的统一条目视图。
 *
 * [opener] 必须在所属 [PackReader] 还开着时调用；两趟都按同一顺序取，7z 的 solid 块
 * 才能顺序解压（`SevenZFile.getInputStream` 支持随机访问，但跨 solid 块会重解开销大）。
 */
private class PackEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    private val opener: () -> InputStream,
) {
    fun open(): InputStream = opener()
}

/**
 * 压缩包只读视图：条目清单在构造时截到 [ResourcePackStore.MAX_ENTRIES] + 1 个文件，
 * 与安装第二趟的上限一致，避免恶意超长清单把内存堆满。
 */
private interface PackReader : Closeable {
    val entries: List<PackEntry>
}

private class ZipPackReader(private val zip: ZipFile) : PackReader {
    override val entries: List<PackEntry> = buildList {
        val source = zip.entries()
        var files = 0
        while (source.hasMoreElements() && files <= ResourcePackStore.MAX_ENTRIES) {
            val entry = source.nextElement()
            if (!entry.isDirectory) files++
            add(PackEntry(entry.name, entry.isDirectory, entry.size, { zip.getInputStream(entry) }))
        }
    }

    override fun close() {
        zip.close()
    }
}

private class SevenZipPackReader(private val seven: SevenZFile) : PackReader {
    override val entries: List<PackEntry> = buildList {
        var files = 0
        for (entry in seven.entries) {
            if (files > ResourcePackStore.MAX_ENTRIES) break
            if (!entry.isDirectory) files++
            add(PackEntry(entry.name, entry.isDirectory, entry.size, { seven.getInputStream(entry) }))
        }
    }

    override fun close() {
        seven.close()
    }
}
