package com.eratw.port.platform.storage

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * 一次「清理已导入素材」的读数（只描述文件系统事实，不写玩家句子）。
 *
 * 七个字段各说一件事：删掉几个文件、删掉多少字节（按删除前的长度累计）、有几个被玩家导入
 * 覆盖过的随包文件按 assets 原字节还原了、有几个随包文件原样留着、顺手剪掉几个空目录、
 * 逐条失败取证、以及「整批没做」的原因。
 *
 * [failures] 的行格式与 `ResourcePackReport.skippedSamples` 同口径：
 * 「相对游戏根的路径 —— 原因原话」，路径一律 `/` 分隔，方便玩家把这行原文贴过来取证。
 * [error] 与 [failures] 的分工：前者是「整批一个字节都没动」（基准清单读不到），
 * 后者是「跑过了，但这一条没做成」—— 两者不许混用。
 */
data class ResourcePackCleanupReport(
    val deletedFiles: Int,
    val deletedBytes: Long,
    val restoredBaseFiles: Int,
    val keptBaseFiles: Int,
    val prunedDirs: Int,
    /** 逐条失败取证（格式 `<相对路径> —— <原因>`，与 ResourcePackReport.skippedSamples 同口径）。 */
    val failures: List<String>,
    /** 整批没做：原因原话（基准清单读不到等）；null = 正常跑过。 */
    val error: String? = null,
)

/**
 * 设置页 Mod 档「导入资源」卡上的**清除已导入素材**（BGM / 背景图）在存储层的那一半。
 *
 * ## 为什么不能整片删目录
 *
 * 随包基线素材就长在这两棵树里（`app/build.gradle.kts` 实测 `resources/` 约 328 MB、
 * 3,399 个文件，`sound/` 约 6.7 MB、52 个文件）。`deleteRecursively` 会连游戏自带素材一起
 * 抹掉，属于不可恢复的破坏面，所以这里**逐文件**判「这份文件是玩家导入的，还是随包就有的」。
 *
 * ## 判基准的两个来源（取并集，越并越保守）
 *
 * 1. 盘上文件级摘要 [GameAssetInstaller.readDataDigest]（随包解包器写的
 *    `update/data-digest.txt`）里的相对路径表；
 * 2. APK assets 自身这棵只读树 [GameAssetTree]：`openFile(rel) != null` 即「随包有这条路径」。
 *
 * 两个来源判「是基准」的并集才算基准 —— 任何一边说随包有，就**不删**。判据的实现见
 * [isBase]：拿不准（例如读 assets 抛异常）时一律当基准。
 *
 * ## 基准源存活闸
 *
 * 摘要读不到（null）且 assets 树也给不出 `CSV` 目录清单时，判「基准清单读不到」，
 * **一个字节都不删**，只回 [ResourcePackCleanupReport.error]。理由是开发包（跳过资产解包）
 * 与手工拷来的游戏根都可能没有摘要：此时把「不在基准里」误判成「玩家导入」会清掉整棵语料。
 * `CSV` 是随包必有的探针目录（随包四类目录 CSV/resources/sound/plugins 之一）。
 *
 * ## 清理范围
 *
 * - [MaterialTarget.BACKGROUND] → `<gameRoot>/resources/背景图` 子树；
 * - [MaterialTarget.BGM] → `<gameRoot>/sound` **整个子树**。不止 `sound/游戏bgm`：标准形态的包
 *   会把音频投到 `sound/<地点>/`，那正是 `StructureNoteKind.BGM_OUTSIDE_GAME_BGM` 点出的
 *   「导了也不响」形态；`sound` 是纯音频域，非基准文件删掉是安全的。
 *
 * 范围目录按**段**大小写不敏感定位（与 `ResourcePackStore.scanStatus` 同一手法）；
 * 范围目录不存在 ⇒ 计数全 0、[ResourcePackCleanupReport.error] 为 null（无事可做，不是失败）。
 *
 * ## 安全闸
 *
 * 1. **canonical 边界闸**：每个候选文件的 canonical 路径必须以范围根 canonical 路径 +
 *    分隔符开头，否则只记 [ResourcePackCleanupReport.failures] 并跳过（不删、不还原）；
 * 2. 只处理 `isFile` 的真文件（目录不删；符号链接跟随后的越界由第 1 条兜底）；
 * 3. 候选文件先收集再按相对路径字典序排序，报告与用例可复现；
 * 4. 剪空目录只从**范围根以内**自最深层往上做，范围根本身永不删；
 * 5. 两趟遍历都带 canonical 去重：链接成环（子目录指回祖先）不会把一次清理拖成死循环。
 *
 * TODO(verify)：摘要里的相对路径是解包器按 APK 条目原样大小写写的，而盘上目录名若被玩家
 * 改成别的大小写（`resources` → `Resources`），[isBase] 的摘要匹配会落空，此时只能靠
 * assets 那一支兜底。卡点：Android 的 `AssetManager` 也是大小写敏感的，真机上没有可靠的
 * 「同一条路径按大小写折叠」对照物；本层不猜，改由 assets 分支保守兜底。
 */
object ResourcePackCleanup {

    /** 一次清理的入口：跑完就回读数，任何失败都折进 [ResourcePackCleanupReport]。 */
    fun clear(gameRoot: File, target: MaterialTarget, base: GameAssetTree?): ResourcePackCleanupReport {
        val scope = scopeOf(gameRoot, target) ?: return emptyReport()

        val digestPaths = GameAssetInstaller.readDataDigest(gameRoot)?.entries?.keys
        if (digestPaths == null && !baseIsAlive(base)) {
            return emptyReport(BASE_UNREADABLE)
        }

        var deletedFiles = 0
        var deletedBytes = 0L
        var restoredBaseFiles = 0
        var keptBaseFiles = 0
        val failures = ArrayList<String>()

        val candidates = ArrayList<Pair<File, String>>()
        val visited = HashSet<String>()
        runCatching {
            scope.root.walkTopDown()
                .onEnter { dir -> visited.add(canonicalPath(dir)) }
                .forEach { file ->
                    if (file.isFile) candidates += file to relOf(scope, file)
                }
        }

        for ((file, rel) in candidates.sortedBy { it.second }) {
            if (!isWithinScope(scope, file)) {
                failures += "$rel —— $OUT_OF_SCOPE"
                continue
            }
            if (!isBase(rel, digestPaths, base)) {
                val size = file.length()
                val outcome = runCatching { file.delete() }
                if (outcome.getOrDefault(false)) {
                    deletedFiles++
                    deletedBytes += size
                } else {
                    failures += "$rel —— " + deleteFailureReason(outcome.exceptionOrNull())
                }
                continue
            }
            if (base == null) {
                // 有摘要没 assets：知道它是随包的，但拿不到原字节 ⇒ 不猜、不删。
                keptBaseFiles++
                continue
            }
            // 先比字节数（assets 给得出 STORED 长度时），再逐块比内容；两步都相等才算「没被覆盖」。
            val declared = runCatching { base.fileSize(rel) }.getOrNull()
            val sameLength = declared == null || declared == file.length()
            val unchanged = sameLength && runCatching { assetEqualsDisk(base, rel, file) }.getOrDefault(false)
            if (unchanged) {
                keptBaseFiles++
                continue
            }
            val restore = runCatching { restoreFromAsset(base, rel, file) }
            if (restore.getOrDefault(false)) {
                restoredBaseFiles++
            } else {
                keptBaseFiles++
                val reason = restore.exceptionOrNull()?.let { restoreFailureReason(it) } ?: ASSET_BYTES_MISSING
                failures += "$rel —— $reason"
            }
        }

        val prunedDirs = pruneEmptyDirs(scope)

        return ResourcePackCleanupReport(
            deletedFiles = deletedFiles,
            deletedBytes = deletedBytes,
            restoredBaseFiles = restoredBaseFiles,
            keptBaseFiles = keptBaseFiles,
            prunedDirs = prunedDirs,
            failures = failures,
        )
    }

    // ------------------------------------------------------------------
    // 范围根：逐段大小写不敏感定位
    // ------------------------------------------------------------------

    /** 清理范围：根目录 + 相对游戏根的前缀 + 用于边界闸的 canonical 前缀。 */
    private class Scope(val root: File, val relPrefix: String, val canonicalPrefix: String)

    /**
     * 按 [target] 定位范围根；`resources` 与 `背景图` 逐段各找一次（大小写不敏感）。
     *
     * 任一段不存在就返回 null —— 调用方按「无事可做」处理（不是失败）。
     */
    private fun scopeOf(gameRoot: File, target: MaterialTarget): Scope? {
        val root = when (target) {
            MaterialTarget.BACKGROUND -> {
                val resources = findChild(gameRoot, ResourcePackStore.RESOURCES_DIR) ?: return null
                findChild(resources, ResourcePackStore.BACKGROUND_DIR) ?: return null
            }
            MaterialTarget.BGM -> findChild(gameRoot, ResourcePackStore.SOUND_DIR) ?: return null
        }
        return Scope(root, relOfGameRoot(gameRoot, root), canonicalPath(root).trimEnd(File.separatorChar) + File.separatorChar)
    }

    /** 目录下按段名找子目录（大小写不敏感）；不存在/不是目录/读不了都返回 null。 */
    private fun findChild(parent: File, name: String): File? =
        runCatching {
            parent.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }
        }.getOrNull()

    // ------------------------------------------------------------------
    // 判基准
    // ------------------------------------------------------------------

    /**
     * 基准源存活闸：摘要读不到时，还认不认得出「随包有哪些目录」。
     *
     * 判据 = assets 树能列出 `CSV` 的非空子项表（随包必有 CSV，其余三条是 resources / sound /
     * plugins）。拿不到就返回 false ⇒ 调用方整批不动。
     */
    private fun baseIsAlive(base: GameAssetTree?): Boolean {
        if (base == null) return false
        val listing = runCatching { base.listDir(CSV_PROBE_DIR) }.getOrNull() ?: return false
        return listing.isNotEmpty()
    }

    /**
     * 这条相对路径是不是随包基准：两个权威来源取并集（任一说是，就是）。
     *
     * assets 那一支的流必须关（`use`）。读 assets 抛异常时**保守当基准**，宁可少删一个文件，
     * 也不赌「读不到 ⇒ 不是随包的」。
     */
    private fun isBase(rel: String, digestPaths: Set<String>?, base: GameAssetTree?): Boolean {
        if (digestPaths != null && digestPaths.contains(rel)) return true
        if (base == null) return false
        val outcome = runCatching { base.openFile(rel)?.use { true } ?: false }
        return outcome.getOrDefault(true)
    }

    // ------------------------------------------------------------------
    // 还原被覆盖的基准文件：同目录临时文件 + 改名
    // ------------------------------------------------------------------

    /** assets 流与盘上文件内容是否逐字节相同（分块比，不把整份素材读进内存）。 */
    private fun assetEqualsDisk(base: GameAssetTree, rel: String, disk: File): Boolean {
        val asset = base.openFile(rel) ?: return false
        asset.use { source ->
            FileInputStream(disk).use { onDisk ->
                val assetChunk = ByteArray(COPY_BUFFER)
                val diskChunk = ByteArray(COPY_BUFFER)
                while (true) {
                    val a = readChunk(source, assetChunk)
                    val d = readChunk(onDisk, diskChunk)
                    if (a != d) return false
                    if (a == 0) return true
                    for (i in 0 until a) {
                        if (assetChunk[i] != diskChunk[i]) return false
                    }
                }
            }
        }
    }

    /** 读满一块或读到 EOF；返回本次读到的字节数（0 = 已到末尾）。 */
    private fun readChunk(input: InputStream, buffer: ByteArray): Int {
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        return read
    }

    /**
     * 用 assets 的原字节把盘上文件覆盖写回：同目录临时文件 → 写满 → fsync → 改名。
     *
     * 与解包器 `ResourcePackStore.extractEntry` 同纪律（临时名带纳秒后缀，盘上合法存在
     * `x.clear-tmp` 这种名字时不会被抹掉）。assets 里根本没有这条路径时返回 false（不抛）；
     * 写失败抛 [IOException]，由调用方折成一条失败取证。
     */
    private fun restoreFromAsset(base: GameAssetTree, rel: String, target: File): Boolean {
        val source = base.openFile(rel) ?: return false
        val parent = target.parentFile ?: throw IOException("无法定位父目录：${target.name}")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建目录：${parent.path}")
        }
        val tmp = File(parent, "${target.name}.${System.nanoTime()}.$CLEAR_TMP_SUFFIX")
        try {
            source.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        out.write(buffer, 0, n)
                    }
                    out.flush()
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(target)) {
                // Windows 上目标已存在时先删再改名（与 ResourcePackStore.replace 同口径）。
                target.delete()
                if (!tmp.renameTo(target)) throw IOException("无法落盘：${target.name}")
            }
            return true
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    // ------------------------------------------------------------------
    // 剪空目录：范围根以内自最深层往上，范围根本身不删
    // ------------------------------------------------------------------

    /** 删除完成后剪空目录；只碰范围根以内、且 canonical 也在范围根以内的目录。 */
    private fun pruneEmptyDirs(scope: Scope): Int {
        val dirs = ArrayList<File>()
        val visited = HashSet<String>()
        runCatching {
            scope.root.walkTopDown()
                .onEnter { dir -> visited.add(canonicalPath(dir)) }
                .forEach { file ->
                    if (file.isDirectory && file.path != scope.root.path) dirs += file
                }
        }
        var pruned = 0
        for (dir in dirs.sortedByDescending { it.path.length }) {
            if (!isWithinScope(scope, dir)) continue
            val children = runCatching { dir.list() }.getOrNull()
            if (children == null || children.isNotEmpty()) continue
            if (runCatching { dir.delete() }.getOrDefault(false)) pruned++
        }
        return pruned
    }

    // ------------------------------------------------------------------
    // canonical 边界闸与路径口径
    // ------------------------------------------------------------------

    /** canonical 边界闸：路径解析（含符号链接）后必须仍在范围根之内。 */
    private fun isWithinScope(scope: Scope, file: File): Boolean =
        canonicalPath(file).startsWith(scope.canonicalPrefix)

    /** canonical 路径；解析失败（权限/坏链接）退回绝对路径，免得整批抛出去。 */
    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }

    /** 相对游戏根的路径，分隔符一律 `/`（摘要与失败取证都用这一种口径）。 */
    private fun relOfGameRoot(gameRoot: File, file: File): String =
        runCatching { file.relativeTo(gameRoot).path.replace('\\', '/') }.getOrElse { file.name }

    /** 范围根之内的文件 → 相对游戏根的路径（`resources/背景图/00/a.webp` 这种）。 */
    private fun relOf(scope: Scope, file: File): String {
        val inner = runCatching { file.relativeTo(scope.root).path.replace('\\', '/') }.getOrElse { file.name }
        return if (scope.relPrefix.isEmpty()) inner else "${scope.relPrefix}/$inner"
    }

    // ------------------------------------------------------------------
    // 取证原话（不是玩家可见文案：上屏由 app 层 copy 表按槽位包一层）
    // ------------------------------------------------------------------

    private fun deleteFailureReason(cause: Throwable?): String =
        if (cause == null) {
            "删除失败（文件系统返回 false）"
        } else {
            "删除失败（${cause.javaClass.simpleName}: ${cause.message}）"
        }

    private fun restoreFailureReason(cause: Throwable): String =
        "还原失败（${cause.javaClass.simpleName}: ${cause.message}）"

    private fun emptyReport(error: String? = null): ResourcePackCleanupReport = ResourcePackCleanupReport(
        deletedFiles = 0,
        deletedBytes = 0L,
        restoredBaseFiles = 0,
        keptBaseFiles = 0,
        prunedDirs = 0,
        failures = emptyList(),
        error = error,
    )

    /** 拷块大小：与解包器 `ResourcePackStore` 的 COPY_BUFFER 同量级（64 KiB）。 */
    private const val COPY_BUFFER = 64 * 1024

    /** 还原用的同目录临时文件后缀。 */
    private const val CLEAR_TMP_SUFFIX = "clear-tmp"

    /** 基准源存活闸的探针目录名（随包必有 CSV）。 */
    private const val CSV_PROBE_DIR = "CSV"

    /** 整批没做的原话：摘要与 assets 两条来源都读不到。 */
    private const val BASE_UNREADABLE =
        "基准清单读不到：既没有 update/data-digest.txt，APK 随包 assets 里也找不到 CSV 目录"

    private const val ASSET_BYTES_MISSING = "随包 assets 里读不到基准字节，未还原"

    private const val OUT_OF_SCOPE = "路径解析后越出清理范围（canonical 边界闸），已跳过"
}
