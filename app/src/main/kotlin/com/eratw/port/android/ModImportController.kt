package com.eratw.port.android

import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.frontend.sizeConsts
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchBaseSnapshot
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.patch.PatchIdentityMismatchException
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParseConfig
import com.eratw.port.core.vars.DimParser
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.frontend.ErbPreprocessor
import com.eratw.port.core.frontend.RenameDictionary
import com.eratw.port.platform.storage.ModStore
import com.eratw.port.platform.storage.ResourcePackStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 一份 SAF 选中的口上源文件（R7-C5 导入链的输入）。
 *
 * [bytes] 是 SAF 流的完整字节（`ModStore.MAX_MOD_BYTES` 之内的口上文件量级远小于它，
 * 整份进堆可接受；更大的文件在 [com.eratw.port.android.UriNames.readBounded] 的读取口
 * 就被拦下）。zip 整包也以本形态进链：[ModImportController.import] 里按扩展名展开成
 * 条目源（[ModZip.unpack]），[displayName] 随之换成 zip 内条目的相对路径。
 */
class PickedModFile(
    /** SAF 的 DISPLAY_NAME（导入链用它过 [ModStore] 的文件名校验与排序）。 */
    val displayName: String,
    /** 文件字节（文本按 [text] 解码；口上 ERB/ERH 是文本文件）。 */
    val bytes: ByteArray,
    /** 是否来自 zip 整包展开（[ModImportController.import] 内部置位；直选文件恒 false）。 */
    val fromArchive: Boolean = false,
) {
    /**
     * 源文本（只在导入链里解码一次）：走 [ModSourceCodec]——严格 UTF-8 试解、解不动
     * 回落 CP932（社区口上文件几乎全是 Shift-JIS 编码，无条件 UTF-8 会整份乱码）。
     */
    val text: String by lazy { ModSourceCodec.decode(bytes) }

    /**
     * 落盘留档名：zip 条目把相对路径切出主名（`sub/foo.ERB` → `foo.ERB`，`/` 与 `\`
     * 双支持——个别 Windows 打包器用反斜杠写条目路径），直选文件名无分隔符原样返回。
     * [ModStore] 的文件名合同禁分隔符，路径串只能活到这一步；切主名顺带消解 zip 条目
     * 名里的 `../` 穿越（穿越段在最后一个分隔符之前）。直选文件不切：SAF 的
     * DISPLAY_NAME 理论上不含分隔符，真出现就该让 [ModStore.validateName] 的防呆闸
     * 报红，而不是静默把 `bad/name.ERB` 改名成 `name.ERB`。
     */
    internal val diskName: String
        get() = if (fromArchive) displayName.substringAfterLast('/').substringAfterLast('\\') else displayName
}

/**
 * base 产物的编译上下文（[PatchCompiler] 的全部输入，R7-C1 定义的符号面）。
 *
 * 字段与 [PatchCompiler] 的构造参数一一对应：侧车 [metadata] + base 基线计数
 * [baseCounts] + base `program.erbc` 的 SHA-256（[baseSha256]，补丁头 `base-sha256`
 * 字段与 I3 身份校验同源）+ CSV 根（[csvDir]，灌解析期符号表；null = 没有 CSV 可灌，
 * 解析期只吃侧车与内建符号）。
 */
class BaseCompileContext(
    val metadata: BootMetadata,
    val baseCounts: PatchCompiler.BaseCounts,
    val baseSha256: String,
    val csvDir: File?,
)

/**
 * base 编译上下文的读取口。抽象成接口只为单测：端到端单测注入手工构造的
 * [BaseCompileContext]（mini 侧车 + 合成计数），不需要真的准备 328 MB 的产物。
 *
 * **ADR-011 边界**：本接口的生产实现 [ProductionBaseCompileContextSource] 只被
 * [ModImportController.import]（用户导入动作的响应链）调用；启动路径
 * （[com.eratw.port.android.MainActivity] 的 onCreate / startGameOrDemo）与
 * 游戏循环不得触碰——那条纪律见 `docs/eratw-架构决策记录.md` 的 ADR-011。
 * R7-D1 任务①起导入动作的宿主是 [SettingsActivity]（设置页入口卡）。
 */
fun interface BaseCompileContextSource {
    /** 从游戏根读出 base 编译上下文。任何失败都由调用方折成导入失败上屏。 */
    fun read(gameRoot: File): BaseCompileContext
}

/**
 * 生产实现：`erbc.v1` 的 `program.erbc`（mmap 装载取六个基线计数）+ `program.meta`
 * （侧车符号上下文）+ 流式 SHA-256。
 *
 * mmap 装载出的 [com.eratw.port.core.code.Program] 是方法局部量：六个计数读完即弃，
 * 映射与惰性语句池的文件句柄交给 GC 回收（真机 POSIX 无碍；Windows 单测里临时目录
 * 随测试结束删除，句柄在 GC 前挂着不影响断言）。
 *
 * 侧车与产物**配对性**由调用方约束：导入链读的是游戏根里的现成产物，若玩家中途换包，
 * 侧车与产物错位会在编译期以明确的错误暴露（补丁引用的函数名/常量对不上号），
 * 不做静默补偿。
 */
object ProductionBaseCompileContextSource : BaseCompileContextSource {

    override fun read(gameRoot: File): BaseCompileContext {
        val dir = File(gameRoot, GameHost.ARTIFACT_DIR)
        val programFile = File(dir, GameHost.PROGRAM_FILE)
        val metaFile = File(dir, GameHost.META_FILE)
        if (!programFile.isFile) {
            throw IOException("游戏产物缺失：${programFile.absolutePath}（先完成随包解包才能导入补丁）")
        }
        if (!metaFile.isFile) {
            throw IOException("侧车缺失：${metaFile.absolutePath}（program.meta 必须与 program.erbc 配对存在）")
        }
        // 流式摘要：program.erbc 量级 328 MB，绝不整份读进堆再 digest。
        val sha = fileSha256Hex(programFile)
        val program = ProgramLoader.loadMapped(programFile.toPath())
        val counts = PatchCompiler.BaseCounts(
            instructionCount = program.instructionCount,
            functionCount = program.functions.size,
            statementCount = program.statementCount,
            stringCount = program.stringCount,
            longCount = program.constantCount,
            exprCount = program.exprCount,
        )
        val metadata = BootMetadata.read(metaFile.toPath())
        return BaseCompileContext(metadata, counts, sha, File(gameRoot, "CSV"))
    }

    /**
     * 文件 SHA-256（64 位小写十六进制，[PatchArtifacts.sha256Hex] 的流式变体）：
     * 1 MiB 缓冲逐块喂，328 MB 的产物不整份进堆。
     */
    fun fileSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file.toPath()).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sb = StringBuilder(64)
        for (b in digest.digest()) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}

/** 导入失败的类别（UI 报错页按它选标题；[UNEXPECTED] 是三态之外的内部兜底，禁静默）。 */
enum class ModImportFailureKind {
    /** 编译期拒绝（I6 四闸 / 解析失败 / 词法错误）。 */
    COMPILE,

    /** 补丁与 base 身份不符（I3；含落盘读回的头字段比对失败）。 */
    IDENTITY,

    /** 存储面失败（ModStore 拒收 / 目录建不出 / 落盘失败 / SAF 读取失败）。 */
    STORAGE,

    /** 未预期异常（防御兜底；文案带异常类名，绝不静默吞）。 */
    UNEXPECTED,
}

/** 导入成功详情（UI 成功态与测试断言面）。 */
data class ImportedPatchDetail(
    /** 落盘的补丁产物名（mods 目录里，含 `.patch` 扩展名）。 */
    val patchFileName: String,
    /** 补丁新增函数数。 */
    val functionCount: Int,
    /** 补丁段新增指令数（绝对口径 − base）。 */
    val instructionCount: Int,
    /** 补丁入口函数名（补丁头 `entry-function` 字段，大写归一）。 */
    val entryFunction: String,
    /** 本次导入保存的源文件名（mods 里的 `.ERB`/`.ERH`；zip 条目如实列出 zip 内相对路径，按文件名序）。 */
    val sourceFileNames: List<String>,
    /** 编译期非致命告警（C1 的 level 1 语义；不影响成败）。 */
    val parseWarnings: List<String>,
)

/** 导入结果（[ModImportController.import] 的唯一出口；失败不抛，折成 [Failed] 上屏）。 */
sealed interface ModImportOutcome {

    /** 导入成功：补丁产物已落 `mods/`。 */
    data class Imported(val detail: ImportedPatchDetail) : ModImportOutcome

    /** 导入失败（UI 报错页原样展示 [message]，含函数名 + 文件:行的编译错误不截断）。 */
    data class Failed(val kind: ModImportFailureKind, val message: String) : ModImportOutcome
}

/**
 * R7-C5 导入链（CONTRACT-R7-C §2/C5）：SAF 选 `.ERB`/`.ERH`/口上 zip 包 → zip 解包成
 * 条目源（[ModZip]）→ [ModStore.save] 源留档 → [PatchCompiler] 设备端编译 →
 * 补丁产物落 `mods/` 的 `.patch`（与 [ModPatchLoader.PATCH_EXTENSION] 的装载扫描口径对齐）。
 *
 * 源文本解码（[ModSourceCodec]）：严格 UTF-8 试解、解不动回落 CP932——社区口上文件
 * 几乎全是 Shift-JIS 编码（原版 `EncodingHandler.cs:37-40` 同口径）。
 *
 * ## ADR-011：编译只发生在导入动作里
 *
 * 本类是**全仓唯一的 PatchCompiler 调用点**：编译、base 产物装载（mmap）、侧车解析
 * 全部发生在 [import]（用户点击导入后的后台线程动作）里。[SettingsActivity] 的导入动作
 * （R7-D1 任务①起的宿主）与 [com.eratw.port.android.GameHost] 的装载链之外，任何启动
 * 路径（[com.eratw.port.android.MainActivity] 的 onCreate / startGameOrDemo）不得引用
 * 本类与 [PatchCompiler]——游戏装载仍然只读既有产物（`erbc.v1` 与 `mods`），
 * 补丁经 [com.eratw.port.core.loader.ModPatchLoader] 在会话装配期拼接（零编译）。
 *
 * ## 全或无（不变量 I5）
 *
 * 补丁产物的落盘是最后一步：编译失败 / 身份预检失败都不落 `.patch`（源 `.ERB`
 * 照契约顺序先落盘留档，那是编译输入，不是产物）。已存在的同名旧产物不会被半途
 * 覆盖——落盘失败时临时文件已清理，旧产物原样保留（下次装载回退到旧补丁，
 * 行为可预期）。
 *
 * ## 三态失败（契约 C5 出口判据①）
 *
 * 每一步的异常**分步接住**并折成 [ModImportOutcome.Failed]：
 * 1. 源文件落盘（[ModStore.save]）→ STORAGE；
 * 2. base 上下文读取（[BaseCompileContextSource.read]）→ STORAGE；
 * 3. 编译（[PatchCompiler.compile] 的 [PatchCompiler.PatchCompileException] / require）→ COMPILE；
 * 4. 身份预检（[com.eratw.port.core.patch.PatchArtifacts.checkAgainst]，I3）→ IDENTITY；
 * 5. 产物落盘 + 写读往返校验（I8）→ STORAGE（往返不一致同样归 STORAGE：那是落盘损坏）。
 *
 * 未预料的异常走 [ModImportFailureKind.UNEXPECTED]（带异常类名，禁静默）。
 *
 * @param modStore 源 `.ERB` 的存储面（platform 已建好的 [ModStore]，只调用不修改）。
 * @param baseSource base 编译上下文读取口（生产 = [ProductionBaseCompileContextSource]）。
 * @param gameRoot 游戏根（[ModStore.modsDirFor] 的同源口径；base 上下文也从这里读）。
 */
class ModImportController(
    private val modStore: ModStore,
    private val baseSource: BaseCompileContextSource,
    private val gameRoot: File,
) {

    /**
     * 执行一次导入：排序 → 源落盘 → 读 base 上下文 → 编译 → 身份预检 → 产物落盘。
     * 任何失败都折成 [ModImportOutcome.Failed]（不向上抛；调用方是 Android 线程，
     * 拿到结果直接渲染）。
     *
     * [copy] 是壳层在起线程前取好的当前语言文案表（照 `UpdateCopy` 的口径：纯层不碰
     * SharedPreferences，语言随调用进线程）；缺省只为所有权外的既有单测留（对拍
     * [ModImportCopy.ZH]，消息逐字同形）。
     */
    fun import(files: List<PickedModFile>, copy: ModImportCopy = ModImportCopy.ZH): ModImportOutcome {
        if (files.isEmpty()) {
            return ModImportOutcome.Failed(ModImportFailureKind.STORAGE, copy.noFilesPicked)
        }
        // ---- ⓪ zip 整包展开（2026-11：SAF 现在也收口上 zip 包，解出 .ERB/.ERH 条目当普通源）----
        // 条目 displayName 保留 zip 内相对路径（排序与编译报错定位都用它），落盘留档时才切主名。
        val flat = ArrayList<PickedModFile>(files.size)
        for (f in files) {
            if (!ModZip.isZipName(f.displayName)) {
                flat.add(f)
                continue
            }
            val unpacked = try {
                ModZip.unpack(f.bytes)
            } catch (e: Exception) {
                return failed(
                    ModImportFailureKind.STORAGE,
                    copy.fill(copy.zipUnpackFailedFormat, f.displayName, detailText(e)),
                    e,
                )
            }
            if (unpacked.isEmpty()) {
                // 跨卡指路（2026-11）：素材包（resources/ 与 sound/ 投放根、背景图包、4.981 音乐
                // 补丁的裸目录）被玩家选进补丁导入卡时，只说「没有 .ERB/.ERH 条目」等于让玩家
                // 自己猜该去哪张卡。判定为素材包就点名「素材导入」卡；判定不了（真·空口上包）
                // 维持既有 zipNoEntriesFormat。判定失败一律 false，绝不把失败面改写成另一样。
                return if (ModZip.looksLikeAssetPack(f.bytes)) {
                    ModImportOutcome.Failed(
                        ModImportFailureKind.STORAGE,
                        copy.fill(copy.zipLooksLikeAssetPackFormat, f.displayName),
                    )
                } else {
                    ModImportOutcome.Failed(
                        ModImportFailureKind.STORAGE,
                        copy.fill(copy.zipNoEntriesFormat, f.displayName),
                    )
                }
            }
            for (entry in unpacked) flat.add(PickedModFile(entry.name, entry.bytes, fromArchive = true))
        }
        // 多文件的装载序 = 文件名序（C1 TODO-⑥：调用方按文件名排序喂入）；
        // 与 ModStore.compareNames 同源，保证「同一目录两张面同序」的三方口径。
        // compareNames 对路径串同样适用：zip 条目的 `/` 参与逐字符比较，同目录条目
        // 仍按文件名聚在一处（装载序只要求确定性，路径段不破坏这一点）。
        val sorted = sortSourceFiles(flat)

        // ---- ⓪′ 落盘名去重（同名取先 + 告警）----
        // zip 条目名可能带目录层级（`sub/foo.ERB`），落盘留档只留主名（[ModStore] 的
        // 文件名合同禁分隔符）。两份条目主名相同时取**先**到的一份（装载序在前），
        // 后到者跳过并出告警——同一份导入里哪份是「对的」无法判定，静默覆盖更糟。
        val dedupWarnings = ArrayList<String>()
        val deduped = ArrayList<PickedModFile>(sorted.size)
        val seenDiskNames = HashSet<String>()
        for (f in sorted) {
            val diskName = f.diskName
            if (!seenDiskNames.add(diskName)) {
                dedupWarnings += copy.fill(copy.dedupSkippedWarningFormat, f.displayName, diskName)
                continue
            }
            deduped.add(f)
        }

        // ---- ① 源 .ERB/.ERH 留档（契约顺序：save 在 compile 前；扩展名/大小校验在 ModStore）----
        try {
            for (f in deduped) modStore.save(f.diskName, f.bytes)
        } catch (e: Exception) {
            return failed(ModImportFailureKind.STORAGE, copy.fill(copy.saveSourceFailedFormat, detailText(e)), e)
        }

        // ---- ② base 编译上下文（ADR-011：只在这条用户动作链里碰产物）----
        val base = try {
            baseSource.read(gameRoot)
        } catch (e: Exception) {
            return failed(ModImportFailureKind.STORAGE, copy.fill(copy.readBaseFailedFormat, detailText(e)), e)
        }

        // ---- ③ 编译（I6 四闸在这里抛 PatchCompileException，消息带函数名 + 文件:行）----
        // 并集重编（2026-11 多包导入实测）：补丁窗口恒锚定裸 base 产物（I3），而装载器把
        // 第二份窗口同样锚裸 base 的 .patch 叠到第一份之后时，窗口对齐闸必炸——「导了 A
        // 再导 B」整轮回退。设备端唯一正确的形态是 **mods 里全部留档源 + 本次新源一次性
        // 编成一张补丁**：留档 .ERB 是真相（源在，随时可重编），产物只此一份 UNION_PATCH。
        // 装载序 = 文件名序（与 ModStore.compareNames 同源）；本次新源同盘名覆盖旧留档
        // （重导=更新），旧 per-import 产物在落新产物前清理。
        val archiveNames = modStore.modsDir.listFiles()?.map { it.name }.orEmpty()
            // 并集把留档的 .ERB 与 .ERH 都算进来：留档才是真相，产物必须能从留档源整体重算。
            .filter { ModStore.hasErbExtension(it) || ModStore.hasErhExtension(it) }
            .toHashSet()
        val unionNames = LinkedHashSet<String>()
        for (f in deduped) unionNames.add(f.diskName)
        unionNames.addAll(archiveNames)
        val archived = HashMap<String, PickedModFile>()
        for (name in unionNames) {
            if (deduped.any { it.diskName == name }) continue
            val bytes = try {
                Files.readAllBytes(File(modStore.modsDir, name).toPath())
            } catch (e: Exception) {
                return failed(
                    ModImportFailureKind.STORAGE,
                    copy.fill(copy.readArchivedFailedFormat, name, detailText(e)),
                    e,
                )
            }
            archived[name] = PickedModFile(name, bytes)
        }
        val unionSources = sortSourceFiles(unionNames.map { f ->
            deduped.firstOrNull { it.diskName == f } ?: archived.getValue(f)
        })
        // entry 跨源回退（B4，2026-11 整包导入实测）：排序第一的文件可能整文件在
        // [SKIPSTART] 块内（群友整合包的 M_KOJO_K16_5_IMAGE.ERB 排最前、全文件被跳过，
        // PatchCompiler 同样不编译它）——按装载序找第一个能提供 @ 函数头的源；全部为空
        // 才报 COMPILE。旧实现只看第一个文件，整包在「入口函数不存在」上死。
        val entry = unionSources.firstNotNullOfOrNull { firstFunctionNameOf(it.text) }
            ?: return ModImportOutcome.Failed(
                ModImportFailureKind.COMPILE,
                copy.fill(copy.noEntryFunctionFormat, unionSources.size),
            )
        // 侧车灌装告警（R7-D3 P0：global-dims 容忍模式的失败条目明细）在 try 内赋值、
        // 块外并入 parseWarnings——I7 口径：告警不拦截导入，但必须可上屏可定位。
        var symbolWarnings: List<String> = emptyList()
        var renameWarnings: List<String> = emptyList()
        val result = try {
            val symbolBuild = buildPatchSymbolTable(base.metadata, base.csvDir)
            symbolWarnings = symbolBuild.warnings
            val sources = unionSources.map { PatchCompiler.SourceFile(relPath = it.displayName, text = it.text) }
            // `[[替换名]]` 字典与全量编译同源（CSV/_Rename.csv）：口上包大量写
            // `CASE [[比基尼套装]]`，不喂字典则 `[[` 漏到词法器撞 LexicalAnalyzer.cs:809-813
            // 的 RenameDic==null 分支，整包解析失败（2026-11 蕾米莉亚包实测）。
            // CONFIG-WIRE U7：装载按 `useRenameFile` 门控（spec: Process.cs:261-274 只在
            // 开关开时 `LoadEraExRenameFile`；ErbLoader.cs:370 逐文件再按
            // `UseRenameFile && RenameDic != null` 决定应用）——开关关时喂字典反而是
            // 偏离原版。开关开而文件缺席保持 null 并留痕（原版 PrintError MissingRename，
            // Process.cs:270-273；后续 `[[` 撞词法器与原版同形）。
            val store = com.eratw.port.core.config.ConfigStore()
            com.eratw.port.core.config.EmueraConfigParser.loadChainFromDisk(gameRoot.toPath(), store)
            val useRename = com.eratw.port.core.config.EffectiveConfig.of(store).useRenameFile
            val renameDic = if (useRename) RenameDictionary.load(base.csvDir?.toPath()) else null
            if (useRename && renameDic == null && base.csvDir != null) {
                renameWarnings = listOf(
                    "USE _RENAME.CSV FILE:YES 但 CSV/_Rename.csv 不存在，[[置換名]] 不展开" +
                        "（原版 PrintError MissingRename，spec: Process.cs:270-273）",
                )
            }
            PatchCompiler(base.metadata, base.baseCounts, symbolBuild.table, renameDic = renameDic)
                .compile(sources, entry)
        } catch (e: PatchCompiler.PatchCompileException) {
            return failed(ModImportFailureKind.COMPILE, e.message ?: copy.compileFailedFallback, e)
        } catch (e: IllegalArgumentException) {
            // PatchCompiler.compile 的 require（补丁源为空 / entry 为空）同属编译面。
            return failed(ModImportFailureKind.COMPILE, e.message ?: copy.compileFailedFallback, e)
        } catch (e: Exception) {
            return failed(
                ModImportFailureKind.UNEXPECTED,
                copy.fill(copy.compileUnexpectedFormat, e::class.simpleName ?: "null", e.message ?: "null"),
                e,
            )
        }

        // ---- ④ 补丁产物（头 + 内嵌 .erbc）+ I3 身份预检 ----
        // 写头走 PatchCompiler.headerOf（E1 权威口径）：privateDims 随 result 透传进
        // 可选段 private-dims——C5 导入链从此产出带私有 #DIM 载体的补丁（R7-E1 收官）。
        val program = result.program
        val baseCounts = base.baseCounts
        // 入口取**编译实际生效**的那一个（PatchCompiler 的入口回退面）：头字段要与产物一致。
        val header = PatchCompiler.headerOf(result, baseCounts, base.baseSha256, result.entryFunction)
        val artifact = try {
            // 补丁体 = 一份完整 .erbc（窗口编码，ModPatchLoader 的 decode 口径）；I8 的
            // 「写完立刻读回」左移到构造期：of 内部先做容器级校验（ErbcReader.probe）。
            PatchArtifacts.of(header, ErbcWriter.encode(program))
        } catch (e: Exception) {
            return failed(ModImportFailureKind.COMPILE, copy.fill(copy.artifactEncodeFailedFormat, detailText(e)), e)
        }
        try {
            artifact.checkAgainst(PatchBaseSnapshot(baseCounts.instructionCount, baseCounts.functionCount,
                baseCounts.statementCount, baseCounts.stringCount, baseCounts.longCount, baseCounts.exprCount,
                base.baseSha256))
        } catch (e: PatchIdentityMismatchException) {
            return failed(ModImportFailureKind.IDENTITY, e.message ?: copy.identityFailedFallback, e)
        }

        // ---- ⑤ 产物落盘（.patch，与 ModPatchLoader 的扫描扩展名逐字对齐）+ I8 读回校验 ----
        // 并集产物名恒定（UNION_PATCH_NAME）：一张 mods 只养一张设备端补丁，重导=整张替换。
        // 与本链留档源同名的旧 per-import 产物（`<盘名>.patch`，上一版按「首个源名」各落
        // 一张）在这里清掉——它们与并集产物窗口重叠，并存必撞装载器窗口对齐闸（整轮回退）。
        // 判定只认「盘名逐字等于某个留档 .ERB 的产物」；玩家手工放进 mods 的其它 .patch
        // 一概不删（装载侧由 I3/窗口闸裁决，回退清单会点名，玩家能自行处置）。
        val patchName = UNION_PATCH_NAME
        try {
            val unionDiskNames = unionSources.map { it.diskName }.toHashSet()
            for (stale in modStore.modsDir.listFiles().orEmpty()) {
                if (!stale.isFile || stale.name == patchName ||
                    !ModPatchLoader.hasPatchExtension(stale.name)
                ) continue
                val sourceName = stale.name.removeSuffix(ModPatchLoader.PATCH_EXTENSION) + ModStore.ERB_EXTENSION
                if (unionDiskNames.contains(sourceName)) stale.delete()
            }
            savePatchArtifact(modStore.modsDir, patchName, artifact.write())
        } catch (e: Exception) {
            return failed(ModImportFailureKind.STORAGE, copy.fill(copy.artifactSaveFailedFormat, detailText(e)), e)
        }
        try {
            verifyWrittenArtifact(File(modStore.modsDir, patchName), header)
        } catch (e: Exception) {
            return failed(ModImportFailureKind.IDENTITY, copy.fill(copy.verifyWrittenFailedFormat, detailText(e)), e)
        }

        return ModImportOutcome.Imported(
            ImportedPatchDetail(
                patchFileName = patchName,
                functionCount = program.functions.size,
                instructionCount = program.instructionCount - baseCounts.instructionCount,
                entryFunction = header.entryFunction,
                sourceFileNames = deduped.map { it.displayName },
                parseWarnings = result.parseWarnings + symbolWarnings + dedupWarnings + renameWarnings,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 纯函数辅助（internal：单测直接点名）
    // ------------------------------------------------------------------

    /**
     * 导入源的装载序：文件名序，[ModStore.compareNames]（ASCII 折叠、locale 无关）——
     * 与 ModStore / ModPatchLoader 三方同源（C1 TODO-⑥ 的调用方义务落在这里）。
     */
    internal fun sortSourceFiles(files: List<PickedModFile>): List<PickedModFile> =
        files.sortedWith { a, b -> ModStore.compareNames(a.displayName, b.displayName) }

    /**
     * 首个**可用** `@` 函数头的名字（补丁头 `entry-function` 字段的来源）。
     *
     * B4（2026-11 整包导入实测）：群友整合包排序第一的 M_KOJO_K16_5_IMAGE.ERB 全文件
     * 在 `[SKIPSTART]` 块内——旧实现不认 SKIPSTART，把跳过块里的 @ 名当 entry，
     * I3 校验「入口函数不存在」整包死。本预扫改用 core-vm 的 [ErbPreprocessor]
     * 驱动同一条状态机（PatchCompiler 预扫与 LogicalLineReader 的共用口径，
     * spec: ErbLoader.cs:25-181），与其零漂移：
     *
     *  - `[SKIPSTART]`..`[SKIPEND]` 块内的行不产出函数头。SKIPSTART **不嵌套**：
     *    重复使用原版 Warn level 1 后忽略、不叠栈（spec: ErbLoader.cs:43-60
     *    DuplicateSkipstart）；块内嵌套的 `[IF 宏]` 无法解锁区间——每条指令处理后
     *    无条件 `if (skip) disabled = true`（spec: ErbLoader.cs:163-166 强制位）。
     *  - 未闭合（SKIPSTART 无 SKIPEND）：状态持续到文件尾（原版 fileEnd 只 Warn
     *    level 1 不复位区间，spec: ErbLoader.cs:169-180 + :513 调用点）——本预扫
     *    丢弃告警面，行为等价「余行全禁用」。
     *  - `[SKIPEND]` 无配对：原版 UnexpectedSkipend level 1 忽略、不改状态
     *    （spec: ErbLoader.cs:127-141）。
     *  - `;` 注释行、指令行（`[` 起头整行消费）不可能是函数头。
     *  - 大小写不敏感（`[skipstart]` 同效，kw 按 Locale.ROOT 归一）与前导空白容忍
     *    （content 先 trim 再进状态机，process 的合同）。
     *
     * 判定与 [PatchCompiler] 的函数名口径逐字同源（`@` 之后到首个空白、`(`、`,` 的段）。
     * `entry-function` 在装载期没有运行语义（入口恒为 base 的，铁律），它只是补丁的
     * 定位信息（I7）；预扫取「装载序第一个可用函数头」稳定标识一份补丁。
     *
     * TODO(verify)：宏表按空集跑——`[IF 已定义宏]` 启用分支里的 @ 行会被本预扫判禁用、
     * 编译器判启用，取到错位 entry（伤 I7 定位信息，I3 仍兜底）；真实口上包无此形态
     * （K16 系只用 SKIPSTART），卡点 = 取证含宏分支的口上包。
     */
    internal fun firstFunctionNameOf(text: String): String? {
        val preprocessor = ErbPreprocessor(
            debugMode = false,
            macroNames = emptySet(),
            file = "ModImportController.firstFunctionNameOf",
        )
        for ((lineIndex, rawLine) in text.lineSequence().withIndex()) {
            // 首行 BOM（\uFEFF）必须剥掉：SAF 读出的 UTF-8 文件常带 BOM，不剥则首函数头
            // 匹配不到、entry 取名偏移到第二个函数（仅伤 I7 定位信息；SystemVariableBootstrap
            // 同款先例）。BOM 剥掉后再全行 trim（[ErbPreprocessor.process] 的合同：content
            // 需已 trim；尾部空白同样裁掉，指令判定与 @ 判定都不受影响）。
            val content = rawLine.trimStart('\uFEFF', ' ', '\t').trimEnd()
            if (content.isEmpty()) continue
            if (preprocessor.process(content, lineIndex + 1)) continue
            if (preprocessor.disabled) continue
            if (content.startsWith(";")) continue
            if (!content.startsWith("@")) continue
            val body = content.substring(1)
            var i = 0
            while (i < body.length && body[i] !in " \t(,") i++
            val name = body.substring(0, i).trim()
            if (name.isNotEmpty()) return name
        }
        return null
    }

    /**
     * 编译期符号表（[PatchCompiler] 的 `symbolTable` 参数）：
     *
     * 1. **CSV 全集**（[GameDataLoader]）：运行期注册表里真实存在的系统/CSV 变量逐条
     *    声明（[com.eratw.port.core.vars.VarDef.toVarInfo] 同源）——补丁解析期可见的变量集
     *    与运行期一致，不多不少；目录缺失时 [GameDataLoader.load] 返回 null，跳过
     *    （内建符号兜底仍在）。
     * 2. **base 全局 `#DIM`**（侧车 `global-dims` 段原文重解析，[DimParser] 同一口径）：
     *    base 的 ERH/ERB 全局声明（含 `#DIM CONST` 常量名）进表，补丁引用它们不再
     *    「未定义标识符」。声明失败 = 侧车与补丁编译器口径脱节，明确失败不静默跳过。
     * 3. **base 方法三分**（侧车 `method-types` 的 I/S 桶，[BootMetadata.knownMethodTypes]）：
     *    表达式位调用 base 方法需要返回类型；U 桶不声明（保守明确失败，波 0 缺口②的口径）。
     *
     * TODO(verify)（导入链残余差异，全部明确失败方向、无静默错值）：
     * 1. base `#DIM CONST` 常量在补丁里按变量引用解析（运行期查表），而 PC 全量编译
     *    可能把它折值进指令——同源差异待真机口上实测核对（C1 类尾 TODO 的宏展开同族）。
     * 2. 变量下标里的名字键（`CFLAG:ARG:口上セレクタ`）解析期走 [com.eratw.port.core.expr.Parser]
     *    的 indexOwner 保守放行路径，与全量编译「CSV 名字表命中与否」的严格判定有差：
     *    打错名字在补丁侧要到运行期才报错（不静默错值，方向安全）。
     */
    /**
     * 侧车 global-dims 灌装的容忍模式（R7-D3 冒烟 P0）：真产物侧车对 global-dims
     * **原文保真**，其中本就存在「含函数调用的常量表达式 / 行内注释」等非严格解析
     * 形态（实测 80 条；原版 C# 对非 SingleLongTerm 同样不折叠进编译期常量，运行期处理）。
     * 这些条目跳过 = 补丁里引用它们得到「未定义变量」的**明确失败**（方向安全，与
     * 静默错值相反）；失败条目逐条进告警（I7：文件:行 + 原文），不再一条失败整链死。
     */
    internal fun buildPatchSymbolTable(metadata: BootMetadata, csvDir: File?): SymbolTableBuild {
        val sym = SymbolTable()
        // ERH `#DIM CONST` 的**名字**先进符号表（侧车 const / str-const 两段就是它们折叠后的
        // 值表）：原版解析期靠 nameDic 认出「这是个常量」，口上里 `CASE 桜花道的鳥居`、
        // `MAX_DIARY_PAGE` 这类裸常量名就是这么命中的。只灌值表不灌名字 ⇒ 解析期
        // 「未定义的标识符」整包死（2026-11 蕾米莉亚色情特化 v1.3 实测第一抛）。
        // 声明在最前：CSV 与 global-dims 两段随后若出现同名条目，会以更精确的 spec
        // （真实维数/初值）覆盖这里的标量保守形态（declareVariable 后写覆盖前写）。
        val dimCfg = metadata.dimParseConfig
        for (name in dimCfg.intConstants.keys) {
            sym.declareVariable(name, OperandType.INT, 0, isCharacterData = false, isConst = true)
        }
        for (name in dimCfg.strConstants.keys) {
            sym.declareVariable(name, OperandType.STR, 0, isCharacterData = false, isConst = true)
        }
        if (csvDir != null) {
            val data = GameDataLoader.load(csvDir.toPath())
            if (data != null) {
                for (def in data.registry.all()) {
                    val info = def.toVarInfo()
                    sym.declareVariable(info.name, info.operandType, info.dimension, info.isCharacterData, info.isConst)
                }
            }
        }
        val consts = sizeConsts(dimCfg.macros, dimCfg.intConstants)
        // 只重算尺寸常量折叠表，其余解析口径**原样继承侧车 dim-config**——尤其是
        // systemSaveInBinary：丢掉它会退成 false，把 base 里合法的
        // `#DIM CHARADATA SAVEDATA X, n` 判成「非二进制存档下 SAVEDATA 不能是 CHARADATA」
        // 而整条跳过 ⇒ 补丁引用 CHILDNAME/REQUEST_DISHNAME 就是「未定义的标识符」
        // （2026-11 蕾米莉亚色情特化 v1.3 实测：78 条假跳过里 73 条即此因）。
        val cfg = dimCfg.copy(intConstants = consts)
        val warnings = ArrayList<String>()
        var skippedBadDims = 0
        for (raw in metadata.globalDims) {
            try {
                val keyword = raw.raw.substringBefore(' ', raw.raw).uppercase()
                val spec = DimParser.parse(keyword, raw.raw.substringAfter(' ', "").trim(), isPrivate = false, config = cfg)
                sym.declareVariable(
                    spec.name,
                    // 三态判序与全量预扫描同源（CorpusScan.kt:242）：`#DIMF`（4.981 浮点声明）
                    // 若折成 INT，补丁里对它的读写会落整型列 ⇒ 静默错值。isFloat 必须先判
                    // （DimSpec 的两个布尔位「都 false 不代表整数」，UserVariables.kt:72）。
                    when {
                        spec.isFloat -> OperandType.FLOAT
                        spec.isStr -> OperandType.STR
                        else -> OperandType.INT
                    },
                    spec.dimension,
                    // CHARADATA 位原样带给符号表（与 erbcc 全量预扫 CorpusScan 的
                    // global.declareVariable 同口径）：口上里 `CHILDNAME:16:ARG` 这类
                    // 「下标+角色」二维取用要靠它才解析得过。
                    isCharacterData = spec.isCharaData,
                    isConst = spec.isConst,
                )
            } catch (e: Exception) {
                skippedBadDims++
                warnings += "侧车 global-dims 跳过（${raw.file}:${raw.line}）${raw.raw.take(80)} —— ${e.message ?: e::class.simpleName}"
            }
        }
        if (skippedBadDims > 0) {
            warnings += "侧车 global-dims 共跳过 $skippedBadDims 条非严格解析条目（原版运行期处理形态，见告警明细）"
        }
        for ((name, type) in metadata.knownMethodTypes()) {
            when (type) {
                OperandType.INT -> sym.declareFunction(name, OperandType.INT)
                OperandType.STR -> sym.declareFunction(name, OperandType.STR)
                else -> Unit // UNKNOWN：不声明 = 表达式位调用保守明确失败（禁静默降级）
            }
        }
        // `#FUNCTIONF`（4.981 第三种 MethodType）在侧车 `method-types` 段只有 UNKNOWN，
        // 但 `function` 段逐条带 `returnsFloat` —— 名字类型按它补声明成 FLOAT。不补的话
        // 补丁里 `F()` 解析成整型调用：全量侧同一声明是 FLOAT（CorpusScan.kt:180
        // `FUNCTIONF -> OperandType.FLOAT`），裸浮点调用在整型落点会明确失败；补丁侧则
        // 保持整型节点、运行期按整型读浮点方法的返回（恒 0）= 静默错值。
        for (m in metadata.functions) {
            if (m.isMethod && m.returnsFloat) sym.declareFunction(m.key, OperandType.FLOAT)
        }
        // base 普通函数（非方法、非事件）补进表达式位存在性表（2026-11 蕾米莉亚包实测）：
        // LineParser 在 SET/IF 表达式位调用 `@IS_HERE(TARGET)` 时经 resolveFunction 判存在，
        // 不声明 = 「未定义的标识符」整包死。类型钉 INT：ExprCallTable 把它们归
        // nonMethodLabels（不降级、保持 FuncCall 节点交运行期重入通道），解析期类型位
        // 不参与编译产物——与 RealCorpusPatchSmokeTest 装置复刻面同步（改一处须同步另一处）。
        val methodKeys = metadata.knownMethodTypes().keys
        for (m in metadata.functions) {
            if (m.isEvent || m.isMethod || m.key in methodKeys) continue
            sym.declareFunction(m.key, OperandType.INT)
        }
        return SymbolTableBuild(sym, warnings)
    }

    /** [buildPatchSymbolTable] 的出口：符号表 + 侧车灌装告警（并入导入结果 `parseWarnings`）。 */
    internal data class SymbolTableBuild(val table: SymbolTable, val warnings: List<String>)

    /**
     * mods 目录里的补丁产物名（静态清单，报告页用；文件名序，[ModStore.compareNames]）。
     * 扩展名判定只引用 [ModPatchLoader.hasPatchExtension]（R7-D1 任务③：判定口径的
     * 唯一权威在 core-vm 装载器侧，app 面不再自持复制；列目录与排序的口径不变）。
     */
    internal fun patchFileNamesIn(modsDir: File): List<String> {
        if (!modsDir.isDirectory) return emptyList()
        val files = modsDir.listFiles { f -> f.isFile && ModPatchLoader.hasPatchExtension(f.name) } ?: return emptyList()
        return files.map { it.name }.sortedWith { a, b -> ModStore.compareNames(a, b) }
    }

    // ------------------------------------------------------------------
    // 落盘（.patch 不能走 ModStore.save：那边只收 .ERB 源文件面）
    // ------------------------------------------------------------------

    /**
     * 原子落盘一份补丁产物：同目录 `.tmp` → fsync → rename（镜像 [ModStore] 的原子性
     * 口径）。目标已存在（重导同名源）= 整份替换；任何失败都清理临时文件，`mods/` 里
     * 不会出现半截产物。
     */
    private fun savePatchArtifact(modsDir: File, name: String, bytes: ByteArray) {
        if (!modsDir.isDirectory && !modsDir.mkdirs() && !modsDir.isDirectory) {
            throw IOException("无法创建 mods 目录：${modsDir.absolutePath}")
        }
        val target = File(modsDir, name)
        val tmp = File(modsDir, "$name.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                if (target.exists() && !target.delete()) {
                    throw IOException("无法替换既有补丁产物：${target.name}")
                }
                if (!tmp.renameTo(target)) throw IOException("无法落盘补丁产物：${target.name}")
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw e
        }
    }

    /**
     * 写读往返校验（不变量 I8 的落盘侧）：读回刚写的字节，头字段逐项比对刚写入的
     * [header]（六计数 + sha + compiler + 补丁五计数 + entry）。读不出 / 解不开 / 字段
     * 不符都在这里抛（调用方折成 IDENTITY 失败上屏）——写坏了却没人读是最隐蔽的坑。
     */
    private fun verifyWrittenArtifact(target: File, header: PatchHeader) {
        val bytes = try {
            Files.readAllBytes(target.toPath())
        } catch (e: IOException) {
            throw IOException("补丁产物落盘后读不回来：${target.name}（${e.message ?: e::class.simpleName}）", e)
        }
        val readBack = try {
            PatchArtifacts.read(bytes)
        } catch (e: Exception) {
            throw IllegalStateException("补丁产物读回解析失败：${target.name}（${e.message ?: e::class.simpleName}）", e)
        }
        val h = readBack.header
        fun mismatch(field: String, expected: Any, actual: Any): Boolean = expected != actual
        val problems = buildList {
            if (mismatch("base-instructions", header.baseInstructionCount, h.baseInstructionCount)) add("base-instructions")
            if (mismatch("base-functions", header.baseFunctionCount, h.baseFunctionCount)) add("base-functions")
            if (mismatch("base-statements", header.baseStatementCount, h.baseStatementCount)) add("base-statements")
            if (mismatch("base-strings", header.baseStringCount, h.baseStringCount)) add("base-strings")
            if (mismatch("base-longs", header.baseLongCount, h.baseLongCount)) add("base-longs")
            if (mismatch("base-exprs", header.baseExprCount, h.baseExprCount)) add("base-exprs")
            if (mismatch("base-sha256", header.baseSha256, h.baseSha256)) add("base-sha256")
            if (mismatch("compiler", header.compiler, h.compiler)) add("compiler")
            if (mismatch("patch-functions", header.patchFunctionCount, h.patchFunctionCount)) add("patch-functions")
            if (mismatch("patch-statements", header.patchStatementCount, h.patchStatementCount)) add("patch-statements")
            if (mismatch("patch-strings", header.patchStringCount, h.patchStringCount)) add("patch-strings")
            if (mismatch("patch-longs", header.patchLongCount, h.patchLongCount)) add("patch-longs")
            if (mismatch("patch-exprs", header.patchExprCount, h.patchExprCount)) add("patch-exprs")
            if (mismatch("entry-function", header.entryFunction, h.entryFunction)) add("entry-function")
        }
        if (problems.isNotEmpty()) {
            throw IllegalStateException("补丁产物落盘内容与预期不符（字段 ${problems.joinToString("、")} 期望/实际不一致）：${target.name}")
        }
    }

    /**
     * 异常原话段（`message ?: 类名`，控制器失败消息统一取法）：异常没有消息也没有类名的
     * 罕见形态按原字符串模板的渲染结果补成 "null"，保证收口前后逐字同形。原话本身是
     * 取证面（core 抛出的 detailMessage 保持原样不进文案表），只有外层的句子模板走
     * [ModImportCopy]。
     */
    private fun detailText(e: Throwable): String = e.message ?: e::class.simpleName ?: "null"

    /** 失败的统一收口：消息上屏（I7 可定位），异常只进日志面（由调用方决定是否记 logcat）。 */
    private fun failed(kind: ModImportFailureKind, message: String, cause: Throwable): ModImportOutcome.Failed {
        cause.printStackTrace()
        return ModImportOutcome.Failed(kind, message)
    }

    companion object {
        /**
         * 设备端并集产物的固定文件名（mods 根，[ModPatchLoader.PATCH_EXTENSION] 口径）。
         *
         * 存在理由：补丁窗口恒锚定裸 base 产物（I3），装载器按文件名序逐张叠加时，第二张
         * 同样锚裸 base 的窗口必撞 [com.eratw.port.core.code.ProgramOverlay] 的窗口对齐闸
         * ——多张设备端补丁**结构性不能并存**。所以导入链的产物只此一张：mods 里全部
         * 留档 .ERB + 本次新源一次性并集重编（= 原版 PC「全部口上一起装载」的设备端等价形）。
         */
        const val UNION_PATCH_NAME: String = "kojo-imports" + ModPatchLoader.PATCH_EXTENSION
    }
}

/**
 * 口上源文本的解码口：严格 UTF-8 试解，解不动回落 CP932。
 *
 * 与 core-vm 主装载链 `LogicalLineReader.decode` 同口径（严格 UTF-8 用
 * CharsetDecoder REPORT 模式，失败回落 windows-31j）；语义权威是原版
 * `EncodingHandler.cs:37-40`（无 BOM 时严格 UTF-8 探测、失败回退 CP932）。
 * core-vm 那份实现是 private 且 core 车道不归本路所有，故此处按同口径持有
 * 一份小实现（KDoc 对账，禁两边口径漂移——改解码规则须同步两处）。
 *
 * 社区口上文件几乎全是 Shift-JIS（CP932）编码：无条件按 UTF-8 解会把整份源变成
 * 乱码、编译全线爆炸（「导入口上包没有一个能成功」的主因）。
 *
 * CP932 用 `windows-31j`（JDK 的 CP932 等价名），缺字符集时退 `Shift_JIS` 近似——
 * 与 platform 层 MacroFile 的 charset 先例同款（platform/.../input/MacroFile.kt:60-62）。
 * JDK 裸 `Shift_JIS` 缺 NEC/IBM 扩展区（0x8740 ⇒ U+FFFD、0x815C ⇒ U+2014），
 * windows-31j 才是 CP932（0x8740 ⇒ U+2460、0x815C ⇒ U+2015）。
 *
 * 不做 BOM 分支：UTF-8 BOM 解出的首字符 U+FEFF 由 [ModImportController.firstFunctionNameOf]
 * 的 trimStart 剥掉（主装载链 readAllLines 的 BOM 分支同效）；UTF-16/32 BOM 源
 * TODO(verify)：原版 EncodingHandler.cs:15-41 支持，社区口上包无此形态，卡点=真机取证样本。
 */
internal object ModSourceCodec {

    /** 日文回落编码 = CP932（`windows-31j`）；JVM 缺这个字符集时退回 `Shift_JIS` 等价近似。 */
    internal val CP932: Charset =
        runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }

    /** 严格 UTF-8 解不动才回落 CP932（宽松 REPLACE——原版回落档同样可产出替换符，见 core decode 的 TODO(verify)）。 */
    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        String(bytes, CP932)
    }
}

/**
 * 口上 zip 整包的解包口（纯 JVM `java.util.zip`，不碰 Android API；zip 字节在堆里，
 * 量级由 `ModStore.MAX_MOD_BYTES` 的读取闸兜住）。
 *
 * ## 条目筛选
 *
 * 只解出 `.ERB`/`.ERH` 条目（[ModStore.hasErbExtension] / [ModStore.hasErhExtension]
 * 的大小写不敏感口径）；txt/csv/图片等其余条目跳过**不报错**——口上包夹带 readme 与
 * 素材是常态。目录条目（名字以 `/` 或 `\` 结尾）与空名条目同样跳过。
 *
 * ## 条目名编码探测
 *
 * 社区口上包主流是 Windows 打包（条目名 CP932），跨平台打包用 UTF-8；`java.util.zip`
 * 不做启发，选错 charset 时日文条目名变乱码（留档名不可读）。整包字节已在堆里，
 * 按两种 charset 各扫一遍条目名（零内容读取），「乱码名」（含 U+FFFD——两种 decoder
 * 对非法序列都 REPLACE 出它）少者胜，平手取 UTF-8。文本**内容**不受此影响：
 * 条目字节统一走 [ModSourceCodec] 的 UTF-8/CP932 解码口。
 *
 * ## 失败面（调用方折成 STORAGE）
 *
 * 加密条目（zip 标准加密位）在内容读取时抛 [ZipException]；坏 zip / 非 zip 字节在
 * [nextEntry][ZipInputStream.getNextEntry] 即抛。两者都从 [unpack] 冒泡，由
 * [ModImportController.import] 捕获折成 STORAGE 明确报错，禁静默产出乱字节。
 */
internal object ModZip {

    /** 解出的一个源条目：[name] 保留 zip 内相对路径（排序、编译 relPath 与报错定位都用它）。 */
    class Entry(val name: String, val bytes: ByteArray)

    /** displayName 是否为口上 zip 包（`.ZIP` 尾段，大小写不敏感、必须有主名）。 */
    fun isZipName(name: String): Boolean = hasFoldedExtension(name, ".ZIP")

    /** 按条目名编码探测解出全部源条目；没有任何 `.ERB`/`.ERH` 条目时返回空表。 */
    fun unpack(zipBytes: ByteArray): List<Entry> {
        val charset = nameCharsetOf(zipBytes)
        val entries = ArrayList<Entry>()
        ZipInputStream(ByteArrayInputStream(zipBytes), charset).use { zin ->
            while (true) {
                val item = zin.nextEntry ?: break
                val name = item.name
                if (name.isEmpty() || name.endsWith('/') || name.endsWith('\\')) continue
                if (!ModStore.hasErbExtension(name) && !ModStore.hasErhExtension(name)) continue
                val out = ByteArrayOutputStream()
                zin.copyTo(out)
                entries += Entry(name, out.toByteArray())
            }
        }
        return entries
    }

    /**
     * 条目名 charset 择优：两种候选（[Charsets.UTF_8] / CP932）各扫一遍条目名，
     * 「乱码名」（含 U+FFFD——两种 decoder 对非法序列都 REPLACE 出它）少者胜，平手取 UTF-8。
     *
     * 候选池就是 [NAME_CHARSETS]：**同一对** charset 也供 [looksLikeAssetPack] 双探，
     * 严禁改一处漏一处（改编码口径须两边同步）。
     */
    private fun nameCharsetOf(zipBytes: ByteArray): Charset =
        if (mojibakeNameCount(zipBytes, Charsets.UTF_8) <= mojibakeNameCount(zipBytes, ModSourceCodec.CP932)) {
            Charsets.UTF_8
        } else {
            ModSourceCodec.CP932
        }

    /**
     * 判定 zip 是否「像素材包」——跨卡指路（2026-11）：素材包被选进补丁（MOD）导入卡时，
     * 报错要指向「素材导入」卡，而不是只说没有 `.ERB`/`.ERH` 条目。
     *
     * [ModImportController.import] 只在 [unpack] 解出 0 条 `.ERB`/`.ERH` 时才问本函数：
     * 判 true 换成素材包指路文案；判 false（真·空口上包）维持既有 zipNoEntriesFormat。
     * 含 `.ERB`/`.ERH` 的口上包根本走不到这里，所以「口上包夹带素材」不会被误判。
     *
     * ## 判据（与 [ResourcePackStore] 的投放合同逐条同源）
     *
     * 1. 任一条目路径含 `resources` 或 `sound` 段（素材卡的两个投放根；段名 ASCII 大小写
     *    不敏感，与 [ResourcePackStore.normalizeEntry] 的段匹配同口径）；
     * 2. 根级（允许外面包一层下载目录——GitHub 的 zip 常带 `<repo>-master/`，素材卡同样
     *    允许一层包裹）出现 `BGI.csv` 文件、`背景图` 段或 `游戏bgm` 段：前者是背景图包
     *    的索引，后两者是「只丢一棵裸目录」的两种常见包形态（与 [ResourcePackStore] 的
     *    [ResourcePackStore.BACKGROUND_DIR] / [ResourcePackStore.BGM_DIR] 同一组常量）。
     *
     * ## charset 双探（与 [unpack] 同源）
     *
     * 素材包条目名常是中文 Windows 的 GBK / 日文 Windows 的 CP932 字节：按 UTF-8 解出的
     * `背景图` 是一串替换符，只探一种编码会漏判。两种候选各扫一遍、任一命中即算——
     * 与 [nameCharsetOf] 是**同一对** charset（那边按乱码计数择优，这边只求命中）。
     *
     * ## 失败面
     *
     * 空字节 / 坏 zip 一律 false：本函数只是「指路」的加分项，任何读不出来的形态都回到
     * 调用方的既有文案，绝不允许把一种失败改写成另一种。只读条目名、不读条目内容。
     */
    fun looksLikeAssetPack(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        for (charset in NAME_CHARSETS) {
            val hit = try {
                containsAssetEntry(bytes, charset)
            } catch (e: Exception) {
                // 坏 zip 在条目名层就抛；一种 charset 抛不排除另一种能读，逐档兜住。
                false
            }
            if (hit) return true
        }
        return false
    }

    /**
     * [looksLikeAssetPack] 的单档扫描：整包条目名扫完，任一命中 [isAssetEntryPath] 即真。
     *
     * 命中不提前返回：中途抛错（半截坏 zip）由调用方整档判 false——「坏 zip 一律 false」
     * 的口径要的是**整包可读**才敢指路，扫一半就下结论会把损坏包误报成素材包。
     */
    private fun containsAssetEntry(zipBytes: ByteArray, charset: Charset): Boolean {
        var hit = false
        ZipInputStream(ByteArrayInputStream(zipBytes), charset).use { zin ->
            while (true) {
                val item = zin.nextEntry ?: break
                if (isAssetEntryPath(item.name)) hit = true
            }
        }
        return hit
    }

    /**
     * 单个条目名（zip 内相对路径）是否落在素材卡的投放合同里，判据见 [looksLikeAssetPack]。
     * 反斜杠当分隔符（个别 Windows 打包器这么写条目名）；`.` 与空段丢掉后再判。
     */
    private fun isAssetEntryPath(rawName: String): Boolean {
        val segments = rawName.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return false
        if (segments.any {
                it.equals(ResourcePackStore.RESOURCES_DIR, ignoreCase = true) ||
                    it.equals(ResourcePackStore.SOUND_DIR, ignoreCase = true)
            }
        ) {
            return true
        }
        // 根级：原样一条，或剥掉一层外层包裹（下载 zip 常见 `<repo>-master/`）。
        for (root in listOf(segments, segments.drop(1))) {
            if (root.isEmpty()) continue
            if (root[0].equals(BGI_CSV, ignoreCase = true)) return true
            if (root[0].equals(ResourcePackStore.BACKGROUND_DIR, ignoreCase = true) ||
                root[0].equals(ResourcePackStore.BGM_DIR, ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    /** 乱码条目名计数：名字含 U+FFFD 替换符 = 该 charset 解错了条目名。 */
    private fun mojibakeNameCount(zipBytes: ByteArray, charset: Charset): Int {
        var bad = 0
        ZipInputStream(ByteArrayInputStream(zipBytes), charset).use { zin ->
            while (true) {
                val item = zin.nextEntry ?: break
                if (item.name.indexOf('\uFFFD') >= 0) bad++
            }
        }
        return bad
    }

    /**
     * 尾段扩展名逐字符 ASCII 折叠比较（与 ModStore.hasNamedExtension 的 foldCase 同思路，
     * locale 无关）。**两侧都折**：extension 常量写成大写、实际文件名常是小写，只折一侧
     * 会让判定永不命中（2026-11 实际踩过：`pack.zip` 判不成 zip，整包被当文本源编译）。
     */
    private fun hasFoldedExtension(name: String, extension: String): Boolean {
        if (name.length <= extension.length) return false
        val start = name.length - extension.length
        for (i in extension.indices) {
            if (foldCase(name[start + i]) != foldCase(extension[i])) return false
        }
        return true
    }

    /** ASCII 单字符折叠（`A`..`Z` → 小写；locale 无关，不用 lowercase() 的原因见 ModStore.compareNames）。 */
    private fun foldCase(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

    /** 条目名双探的 charset 候选：与 [nameCharsetOf] 的择优池是**同一对**（禁两边各写一套）。 */
    private val NAME_CHARSETS: List<Charset> = listOf(Charsets.UTF_8, ModSourceCodec.CP932)

    /**
     * 背景图包根级的索引文件名（[looksLikeAssetPack] 判据 2）：`resources/背景图` 的图片靠
     * 这份 CSV 注册进资源表，孤零零一张它也是背景图包；[ResourcePackStore] 里同名逻辑
     * 走「目录里的 csv」聚合判定，无同名常量，故在此点名。
     */
    private const val BGI_CSV = "BGI.csv"
}
