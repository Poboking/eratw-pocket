package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import com.eratw.port.platform.storage.ModStore
import java.io.File

/**
 * `mods/rt2/` 导入产物（容器 + manifest + provenance）的**唯一形状与孤儿 GC 来源**
 * （RT2 MODGC 裁定 1/3/4）。
 *
 * ## 为什么需要 provenance（`.sources`）
 *
 * 导入链的包身份 = `kojo-` + sha256(排序后留档源名) 前 8 位（[ModImportController.packageIdFor]），
 * 容器与 manifest 里都**不带**源文件名。没有第三件侧车就无法回答「这个包的源还在不在」，
 * 玩家删掉留档源后产物会永远生效（`era-arch-v61u.md` §23.4 的孤儿包余量）。
 * `<packageId>.sources` 逐行记下当次导入的留档源名（排序 = `packageIdFor` 的同序），
 * 只做 provenance，不是装载面文件（`manifestFilesIn` / `collectErbModules` 都不认它）。
 *
 * ## 孤儿 GC 判据（组长裁定 3，冻结）
 *
 * 1. 只处理包名以 [PACKAGE_ID_PREFIX] 开头的导入产物；内置资产（`eratw.*`）与玩家手放件
 *    （非该前缀）永不删除；
 * 2. `.erbm` / `.manifest` 任一缺失 = 半成品（导入两次写之间崩溃的残留）。半成品会让
 *    `ModChainAssembly.assembleChain` 的单向配对整包 fail-closed ⇒ 启动砖化，故必须在
 *    容器被读取之前清掉；
 * 3. `.sources` 存在且名单非空、但列出的留档源**一个都不在** `mods/` = 源已失联，整套删除；
 *    `.sources` 缺失 / 空名单 / 读不出 = 保守不删（老包与手放件没有 provenance）；
 * 4. 只读 provenance、只删本包成员：GC 不枚举 `mods/` 的源文件，也不碰其它包。
 */
object ModPackageInventory {

    /** 导入链产物包名前缀（[ModImportController.packageIdFor] 的唯一常量来源）。 */
    const val PACKAGE_ID_PREFIX: String = "kojo-"

    /** ERB 模块容器扩展名（erbcc `compile-module` 产物，装载收集面同源）。 */
    const val MODULE_EXTENSION: String = ".erbm"

    /** RT2 manifest 扩展名（与容器同 packageId 单向配对）。 */
    const val MANIFEST_EXTENSION: String = ".manifest"

    /** provenance 侧车扩展名（RT2 MODGC 新增，只记留档源名）。 */
    const val SOURCES_EXTENSION: String = ".sources"

    /** 一个包在 `mods/rt2/` 下的全部成员（顺序 = 写入序：provenance → manifest → 容器）。 */
    fun memberNames(packageId: String): List<String> = listOf(
        packageId + SOURCES_EXTENSION,
        packageId + MANIFEST_EXTENSION,
        packageId + MODULE_EXTENSION,
    )

    /**
     * provenance 字节：排序后的留档源名逐行 UTF-8 + 末尾换行。
     *
     * 排序口径与 [ModImportController.packageIdFor] 同源（`ModStore.compareNames`），
     * 让「包身份」「写盘名单」与「GC 判定」三处看到的顺序逐字一致。
     */
    fun sourcesBytes(diskNames: List<String>): ByteArray =
        (diskNames.sortedWith { a, b -> ModStore.compareNames(a, b) }
            .joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)

    /**
     * 读 provenance 名单；文件缺失 / 读失败 / 空名单 ⇒ null（= GC 保守不删）。
     *
     * 含路径分隔符或普通文件名不合法的行一律丢弃：provenance 只可能由本导入链写出，
     * 出现越界名说明文件被改过，宁可当「读不出」保守保留，也不拿它做删除依据。
     */
    fun storedSourceNames(rt2Dir: File, packageId: String): List<String>? {
        val file = File(rt2Dir, packageId + SOURCES_EXTENSION)
        return if (!file.isFile) {
            null
        } else {
            runCatching { file.readLines(Charsets.UTF_8) }.getOrNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.contains('/') && !it.contains('\\') }
                ?.ifEmpty { null }
        }
    }

    /**
     * `mods/rt2/` 孤儿包 GC：在容器被收集/读取**之前**调用（生产 = [ModChainAssembly.assemble]
     * 开态入口）。关态调用方不得调用本函数（零接触纪律在调用点）。
     *
     * 全程 runCatching，不抛、不改装配结局；每个删除动作经 [SaveDiagnostics] 留痕
     * （`MOD_PKG_GC`，与存档侧 `SIDECAR_GC` 同一取证面）。
     */
    fun gcOrphans(rt2Dir: File, modsDir: File) {
        if (!rt2Dir.isDirectory) return
        val members = rt2Dir.listFiles().orEmpty().filter { it.isFile }
        val byPackage = members
            .mapNotNull { file -> packageIdOf(file.name)?.let { it to file } }
            .groupBy({ it.first }, { it.second })
        for ((packageId, files) in byPackage.entries.sortedBy { it.key }) {
            val names = files.mapTo(HashSet()) { it.name }
            val reason = orphanishReason(packageId, names, rt2Dir, modsDir) ?: continue
            for (file in files) {
                val removed = runCatching { file.delete() }.getOrDefault(false)
                SaveDiagnostics.note(
                    "MOD_PKG_GC",
                    file.name,
                    if (removed) "孤儿包成员已删除（$reason）" else "孤儿包成员删除失败，原地保留（$reason）",
                )
            }
        }
    }

    /**
     * 孤儿判定：返回 null = 保留；返回字符串 = 删除理由（进取证面）。
     *
     * 顺序 = 裁定 3 的 2/3 两条：先判半成品（启动砖化的直接来源），再判源失联。
     */
    private fun orphanishReason(
        packageId: String,
        names: Set<String>,
        rt2Dir: File,
        modsDir: File,
    ): String? {
        val hasModule = (packageId + MODULE_EXTENSION) in names
        val hasManifest = (packageId + MANIFEST_EXTENSION) in names
        return if (!hasModule || !hasManifest) {
            "半成品：缺" + when {
                !hasModule && !hasManifest -> "容器与 manifest"
                !hasModule -> "容器"
                else -> "manifest"
            }
        } else {
            lostSourceReason(packageId, rt2Dir, modsDir)
        }
    }

    /** 源失联判定：provenance 缺失 / 空名单 / 任一名单源在场 ⇒ null（保守保留）。 */
    private fun lostSourceReason(packageId: String, rt2Dir: File, modsDir: File): String? {
        val sources = storedSourceNames(rt2Dir, packageId) ?: return null
        return if (sources.any { File(modsDir, it).isFile }) {
            null
        } else {
            "源失联：${sources.size} 个留档源均不在 mods/"
        }
    }

    /**
     * 文件名 → 所属 packageId；不是本对象认识的导入产物形状 ⇒ null。
     *
     * 只有三种成员后缀、且包名以 [PACKAGE_ID_PREFIX] 开头才算：`.erbm` / `.manifest` /
     * `.sources` 之外的任何文件（含玩家手放件与内置 `eratw.*` 资产）一律不参与 GC。
     */
    private fun packageIdOf(name: String): String? {
        for (extension in listOf(MODULE_EXTENSION, MANIFEST_EXTENSION, SOURCES_EXTENSION)) {
            if (!name.endsWith(extension)) continue
            val packageId = name.removeSuffix(extension)
            if (packageId.length > PACKAGE_ID_PREFIX.length && packageId.startsWith(PACKAGE_ID_PREFIX)) {
                return packageId
            }
        }
        return null
    }
}
