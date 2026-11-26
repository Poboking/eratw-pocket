package com.eratw.port.android

import android.util.Log
import com.eratw.port.core.save.SaveDiagnostics
import com.eratw.port.platform.storage.ModStore
import java.io.File

/**
 * 旁挂媒体资源面（ADR-014 S4 写入 / S5 回收）的**删除侧唯一实现**：`<packageId>.media` 清单
 * 与两棵树（`resources/mod/<dir>/…`、`sound/mod/<dir>/…`）里的清单内文件。
 *
 * ## 与写侧的分界（[ModImportMediaAssets]）
 *
 * 写侧只写清单内条目、只按旧清单删旧文件；本对象负责「显式卸载删净」与「孤儿 GC 回收」两个
 * 删除时机。清单校验逐条对齐写侧 `listedPathOrNull`：路径必须落在两棵树之一、`<dir>` 是单段
 * 合法名、相对名合法，且路径里的 `<dir>` 必须等于首行声明的 `<dir>`。盘上清单不认识的文件
 * 一律不碰（同目录里可能有玩家自己放的东西）。
 *
 * ## 三条安全阀
 *
 * 1. **只删清单内路径**：不枚举两棵树做「整目录清空」，绝不 `deleteRecursively()` 整棵 `<dir>`。
 * 2. **清单损坏 fail-closed**：版本行不认识、`<dir>` 不合法、任一行路径不合法 / 与 `<dir>`
 *    不一致 / 逃出 gameRoot、读盘失败 ⇒ 不删任何文件（含清单本身），只 WARN + 一条取证行；
 *    等下次写侧覆盖出合法清单再谈回收（不拿坏清单当删除依据）。
 * 3. **删空目录止于两树根之前**：自底向上删空目录，但 `resources/mod/` 与 `sound/mod/` 是游戏
 *    本体与所有包共用的骨架，本对象永不删这两级（也永删 gameRoot 本身）。
 *
 * ## 两个调用时机
 *
 * - **显式卸载**（[uninstall]，由 [ModManageUninstall.uninstall] 调用）：玩家删除意图的延伸，
 *   删净清单内文件 + 空目录 + 清单；个别文件删不掉只 WARN 并保留清单（下轮 GC 重试），
 *   **不**让卸载报失败——`ModManageUninstall.uninstall` 的返回值语义仍是 `mods/rt2/` 成员删净。
 * - **孤儿 GC**（[gcOrphans]，由 [ModPackageInventory.gcOrphans] 在包级判据之后调用）：只在
 *   「确定无主」时回收——该 packageId 在 `mods/rt2/` 已无任何包文件 / 资源目录 / 设置侧车
 *   （完整包一律保留；半成品由包级判据先清）；同 `<dir>` 仍被别的包清单声明 ⇒ 保留（卸载后
 *   允许别的包复用该目录，不误删新包文件）；清单在但列出的文件有缺失（半成品台账）⇒ 只留
 *   取证行，不拿不完整台账当删除依据。
 *
 * 非标准布局（[gameRootOf] 返回 null）一律 fail-closed 跳过：相对路径没有可采信的落点坐标。
 */
internal object ModMediaReclaim {

    /** 清单后缀与写侧同源（[ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX]），本对象不复制字面量。 */
    const val MANIFEST_SUFFIX: String = ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX

    private const val TAG: String = "ModMediaReclaim"

    /** 取证面 tag：与包级孤儿 GC 同通道（存档诊断 `[eratw][save]`），玩家零屏上提示。 */
    private const val DIAG_TAG: String = "MOD_PKG_GC"

    /** 清单首行前缀 `RT2MEDIA/1 dir=`（版本行与写侧同源拼装，不另写字面量）。 */
    private const val HEADER_PREFIX: String = ModImportMediaAssets.MANIFEST_VERSION + " " +
        ModImportMediaAssets.MANIFEST_DIR_KEY

    /** 相对路径分隔符（清单线格式固定 `/`，与写侧一致）。 */
    private const val SEGMENT_SEPARATOR: Char = '/'

    /** 两树根（gameRoot 相对、带结尾 `/`）：既是路径白名单，也是删空目录的边界。 */
    private val TREE_ROOTS: List<String> = listOf(
        ModImportMediaAssets.RESOURCE_ROOT,
        ModImportMediaAssets.SOUND_ROOT,
    )

    /** 一份可采信清单：首行声明的 `<dir>` + 逐行相对路径。 */
    private data class Ledger(val dir: String, val paths: List<String>)

    /** 单行相对路径校验（与写侧 `listedPathOrNull` 同规则；`<dir>` 还必须等于首行声明）。 */
    private val listedPathOf: (String, String) -> String? = { line, dirName ->
        val root = TREE_ROOTS.firstOrNull { line.startsWith(it) }.orEmpty()
        val rest = line.substring(root.length)
        val slash = rest.indexOf(SEGMENT_SEPARATOR)
        val dir = if (slash > 0) rest.substring(0, slash) else ""
        val relative = if (slash > 0) rest.substring(slash + 1) else ""
        line.takeIf {
            root.isNotEmpty() && dir == dirName &&
                ModImportAssets.isLegalSingleSegmentName(dir) &&
                ModImportAssets.isValidResourceName(relative)
        }
    }

    /** 只读首行 `RT2MEDIA/1 dir=<dir>`（跨包同目录认领判据；坏清单只要首行合法也照认，保守）。 */
    private val declaredDirOf: (File) -> String? = { manifest ->
        runCatching { manifest.readLines(Charsets.UTF_8).firstOrNull() }.getOrNull()
            ?.takeIf { it.startsWith(HEADER_PREFIX) }
            ?.removePrefix(HEADER_PREFIX)
            ?.trim()
            ?.takeIf { ModImportAssets.isLegalSingleSegmentName(it) }
    }

    /** 清单文件名 → packageId；非 `kojo-` 前缀 / 形状不合法 ⇒ null（内置与手放件永不碰）。 */
    private val packageIdOf: (File) -> String? = { manifest ->
        manifest.name.removeSuffix(MANIFEST_SUFFIX).takeIf { candidate ->
            candidate.length > ModPackageInventory.PACKAGE_ID_PREFIX.length &&
                candidate.startsWith(ModPackageInventory.PACKAGE_ID_PREFIX) &&
                ModEnablement.isValidPackageId(candidate)
        }
    }

    /** [target] 是否落在 [root] 内部（canonical，防 `..` 与符号链接逃逸）。 */
    private val isInside: (File, File) -> Boolean = { root, target ->
        target.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }

    /** 空目录可否自底向上删：必须是 [treeRoot] 的后代（不含 treeRoot 自己）且当前为空。 */
    private val isPrunable: (File, File?) -> Boolean = { treeRoot, dir ->
        dir != null && dir != treeRoot &&
            dir.canonicalPath.startsWith(treeRoot.canonicalPath + File.separator) &&
            dir.isDirectory && dir.listFiles().isNullOrEmpty()
    }

    /** 清单文件（只读；在盘与否由调用方用 `isFile` 判）。 */
    fun manifestFile(rt2Dir: File, packageId: String): File = File(rt2Dir, packageId + MANIFEST_SUFFIX)

    /**
     * 标准布局反推 gameRoot：`<gameRoot>/mods/rt2` ⇒ `<gameRoot>`；非标准布局（临时目录等）⇒ null。
     *
     * 调用方据此 fail-closed 跳过媒体面：清单里是 gameRoot 相对路径，没有可采信的 gameRoot 就
     * 不能拿它当删除坐标（宁可留文件，也不在错误根下乱删）。
     */
    fun gameRootOf(rt2Dir: File): File? {
        val modsDir = rt2Dir.parentFile
            ?.takeIf { rt2Dir.name == ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME }
            ?: return null
        return modsDir.takeIf { it.name == ModStore.MODS_DIR_NAME }?.parentFile
    }

    /**
     * 显式卸载的媒体面：删清单内文件 + 自底向上删空目录（止于两树根之前）+ 删清单本身。
     *
     * @return 盘上已无该包清单（含本来就不存在）时为 `true`；清单损坏 / 布局非标准 / 有文件删不掉
     *   时为 `false` 并保留清单（fail-closed）。调用方 [ModManageUninstall] 只把它当 WARN 面，
     *   不改卸载结局。
     */
    fun uninstall(rt2Dir: File, packageId: String): Boolean {
        val manifest = manifestFile(rt2Dir, packageId)
        return !manifest.isFile || removeLedger(manifest, rt2Dir, packageId)
    }

    /**
     * 孤儿媒体面 GC：由 [ModPackageInventory.gcOrphans] 在包级判据之后调用（包级半成品已清）。
     *
     * 只回收「确定无主」的清单；每个保留 / 删除动作都经 [SaveDiagnostics] 留一条 `MOD_PKG_GC`
     * 取证行（玩家零屏上提示）。全程不抛。
     */
    fun gcOrphans(rt2Dir: File, modsDir: File) {
        val gameRoot = modsDir.parentFile ?: return
        val standard = File(modsDir, ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME).canonicalPath ==
            rt2Dir.canonicalPath
        val manifests = rt2Dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(MANIFEST_SUFFIX) }
            .sortedBy { it.name }
        if (!standard || manifests.isEmpty()) return
        // 同 <dir> 认领表：写侧导入期拒导跨包同 <dir>，这里再核一次盘上遗留（复用/历史脏数据）。
        val dirClaims = manifests
            .mapNotNull { manifest ->
                declaredDirOf(manifest)?.let { dir ->
                    packageIdOf(manifest)?.let { pkg -> dir to pkg }
                }
            }
            .groupBy({ it.first }, { it.second })
        for (manifest in manifests) {
            reclaimOne(manifest, rt2Dir, gameRoot, dirClaims)
        }
    }

    /**
     * 单个清单的回收判定：只回收「确定无主」的清单，每个保留 / 删除动作都留一条 `MOD_PKG_GC`
     * 取证行。[gcOrphans] 只做枚举与同 `<dir>` 认领表，分支集中在这里。
     */
    private fun reclaimOne(
        manifest: File,
        rt2Dir: File,
        gameRoot: File,
        dirClaims: Map<String, List<String>>,
    ) {
        val packageId = packageIdOf(manifest) ?: return
        val ledger = readLedger(manifest, gameRoot)
        val claimedByOthers = ledger != null && dirClaims[ledger.dir].orEmpty().any { it != packageId }
        when {
            ledger == null -> SaveDiagnostics.note(
                DIAG_TAG,
                manifest.name,
                "媒体清单损坏，fail-closed 保留全部旁挂文件（不拿坏清单当删除依据）",
            )
            hasPackageFootprint(rt2Dir, packageId) -> Unit
            claimedByOthers -> SaveDiagnostics.note(
                DIAG_TAG,
                manifest.name,
                "同目录 dir=${ledger.dir} 仍被其它包媒体清单声明，保留（跨包复用不误删）",
            )
            ledger.paths.any { !File(gameRoot, it).isFile } -> SaveDiagnostics.note(
                DIAG_TAG,
                manifest.name,
                "媒体面半成品：清单 ${ledger.paths.size} 条不全在盘上，只取证不删（残缺台账不作删除依据）",
            )
            !deleteListedFiles(gameRoot, ledger.paths) -> SaveDiagnostics.note(
                DIAG_TAG,
                manifest.name,
                "孤儿媒体面文件删除失败，清单保留待下次 GC",
            )
            else -> {
                pruneEmptyDirs(gameRoot, ledger.paths)
                val removed = runCatching { manifest.delete() }.getOrDefault(false)
                SaveDiagnostics.note(
                    DIAG_TAG,
                    manifest.name,
                    if (removed) {
                        "孤儿媒体面已回收：清单 ${ledger.paths.size} 个旁挂文件（packageId 在 rt2 已无成员）"
                    } else {
                        "孤儿媒体面清单删除失败，原地保留"
                    },
                )
            }
        }
    }

    /**
     * 读清单 → 可采信 [Ledger]：首行版本行 + `<dir>` 合法，其余每行都必须是能通过 [listedPathOf]
     * 且 canonical 落在 gameRoot 内的相对路径；任何一处不合法 / 读盘失败 ⇒ null（整体 fail-closed）。
     */
    private fun readLedger(manifest: File, gameRoot: File): Ledger? {
        val lines = runCatching { manifest.readLines(Charsets.UTF_8) }.getOrNull() ?: return null
        val header = lines.firstOrNull()?.takeIf { it.startsWith(HEADER_PREFIX) }
        val dir = header?.removePrefix(HEADER_PREFIX)?.trim()
        val rootPrefix = gameRoot.canonicalPath + File.separator
        val paths = lines.drop(1).mapNotNull { line ->
            dir?.let { listedPathOf(line, it) }
                ?.takeIf { path -> File(gameRoot, path).canonicalPath.startsWith(rootPrefix) }
        }
        return dir?.takeIf { ModImportAssets.isLegalSingleSegmentName(it) }
            ?.takeIf { paths.isNotEmpty() && paths.size == lines.size - 1 }
            ?.let { Ledger(it, paths) }
    }

    /**
     * 逐条删清单内文件；返回 `false` = 有文件删不掉（调用方保留清单，下一轮再试）。
     *
     * 文件已经不在（玩家手删 / 上次删了一半）不算失败：卸载语义是「删净」，不在即已净。
     */
    private fun deleteListedFiles(gameRoot: File, paths: List<String>): Boolean {
        var failed = false
        for (path in paths) {
            val target = File(gameRoot, path)
            if (target.isFile && !runCatching { target.delete() }.getOrDefault(false)) {
                failed = true
                // Log 包 runCatching：android.jar 桩在纯 JVM 单测里对 Log 抛「not mocked」。
                runCatching { Log.w(TAG, "旁挂媒体文件删除失败 path=$path") }
            }
        }
        return !failed
    }

    /**
     * 自底向上删清单内文件的空父目录：父目录遍历**止于两树根之前**——`resources/mod/` 与
     * `sound/mod/` 是共享骨架，永不删除；非空目录（里面有玩家文件 / 未列入清单的东西）保留。
     */
    private fun pruneEmptyDirs(gameRoot: File, paths: List<String>) {
        val treeRoots = TREE_ROOTS.map { File(gameRoot, it.removeSuffix("/")) }
        for (path in paths) {
            val file = File(gameRoot, path)
            val treeRoot = treeRoots.firstOrNull { isInside(it, file) } ?: continue
            var cursor = file.parentFile
            while (isPrunable(treeRoot, cursor)) {
                val dir = cursor ?: break
                dir.delete()
                cursor = dir.parentFile
            }
        }
    }

    /**
     * 该 packageId 在 `mods/rt2/` 是否还有任何足迹（包文件 / 指纹侧车 / 设置侧车 / 资源目录）。
     *
     * 有足迹 = 媒体面仍归属这个包（完整包、manifest-only 载体、包级 GC 没删成的半成品）⇒ 不碰。
     * 只有「rt2 里一点成员都不剩」才允许回收旁挂媒体面，这是「确定无主」的唯一判据。
     */
    private fun hasPackageFootprint(rt2Dir: File, packageId: String): Boolean {
        val fileExtensions = listOf(
            ModPackageInventory.MODULE_EXTENSION,
            ModPackageInventory.MANIFEST_EXTENSION,
            ModPackageInventory.SOURCES_EXTENSION,
            ModPackageInventory.FINGERPRINT_EXTENSION,
            ModSettingsStore.SETTINGS_EXTENSION,
        )
        return fileExtensions.any { File(rt2Dir, packageId + it).isFile } ||
            File(rt2Dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX).isDirectory
    }

    /** 显式卸载的删除主体：读清单 → 删文件 → 删空目录 → 删清单。 */
    private fun removeLedger(manifest: File, rt2Dir: File, packageId: String): Boolean {
        val gameRoot = gameRootOf(rt2Dir)
        val ledger = gameRoot?.let { readLedger(manifest, it) }
        if (gameRoot == null || ledger == null || !deleteListedFiles(gameRoot, ledger.paths)) {
            runCatching { Log.w(TAG, "媒体面 fail-closed / 删除失败，清单与旁挂文件保留 pkg=$packageId") }
            SaveDiagnostics.note(
                DIAG_TAG,
                manifest.name,
                "显式卸载：媒体面未删净（布局/清单/IO），清单保留待下轮 GC",
            )
            return false
        }
        pruneEmptyDirs(gameRoot, ledger.paths)
        val removed = runCatching { manifest.delete() }.getOrDefault(false)
        SaveDiagnostics.note(
            DIAG_TAG,
            manifest.name,
            if (removed) {
                "显式卸载：媒体清单内 ${ledger.paths.size} 个旁挂文件已删净（空目录已清）"
            } else {
                "显式卸载：媒体清单删除失败，原地保留"
            },
        )
        return removed
    }
}
