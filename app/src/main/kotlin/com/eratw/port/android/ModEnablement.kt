package com.eratw.port.android

import java.io.File

/**
 * 单个 Mod 的启用 / 停用开关（批2-3）：真源 = `mods/rt2/<packageId>.off` 标记文件。
 *
 * 为什么用磁盘标记而不是偏好表：装配链（[ModChainAssembly] / [ModRuntimeAssembly] /
 * [ModCarrierRegistry]）全部是「读盘 → 装配」，设置页写偏好再让 VM 线程读
 * SharedPreferences 会多出一条跨线程的隐式真源，而且 `attach` 的形参表已在 detekt
 * baseline 冻结（不能加参数）。一个标记文件让「盘上有什么就装什么」保持单源。
 *
 * 生效口径：装配在会话构造期一次性完成（`GameHost.assembleSession`），所以改完要
 * **重开一局**；设置页文案必须写清这一点（与总闸、装载优先级同口径）。
 *
 * 停用语义 = 该包的 manifest 与 `.erbm` **同时**从装配面摘掉：
 * `.erbm` 与 `.manifest` 是单向配对且缺一 fail-closed（见 `ModChainAssembly` 头注），
 * 只摘一边会把「停用一个包」变成「启动砖化」。
 */
object ModEnablement {
    /** 停用标记的扩展名；文件名 = `<packageId>.off`。 */
    const val DISABLED_SUFFIX: String = ".off"

    /**
     * 允许出现在包名里的字符集：标记文件名直接由 packageId 拼成，必须挡住
     * `/`、`\`、`..` 这类能逃出 `mods/rt2/` 的输入（设置页只喂盘上读到的 packageId，
     * 但这是本对象唯一写盘入口，闸放在这里最省事）。
     */
    private val PACKAGE_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** `mods/rt2/` 的解析口径与装配链同源（[ModRuntimeAssembly.manifestDirFor]）。 */
    fun rt2Dir(gameRoot: File): File = ModRuntimeAssembly.manifestDirFor(gameRoot)

    /** packageId 形状闸（拼盘上文件名之前的唯一真源；README 语义见 [PACKAGE_ID_PATTERN]）。 */
    fun isValidPackageId(packageId: String): Boolean = PACKAGE_ID_PATTERN.matches(packageId)

    /** 停用标记文件（不保证存在）。 */
    fun markerFile(rt2Dir: File, packageId: String): File {
        require(isValidPackageId(packageId)) {
            "非法 packageId：$packageId（只允许字母数字与 . _ -，且不得以符号开头）"
        }
        return File(rt2Dir, packageId + DISABLED_SUFFIX)
    }

    /** 该包当前是否被停用（标记文件在场 = 停用）。 */
    fun isDisabled(rt2Dir: File, packageId: String): Boolean = markerFile(rt2Dir, packageId).isFile

    /**
     * 写入或删除停用标记（幂等）。返回 true = 磁盘状态已按 [enabled] 落实。
     *
     * 写失败（只读存储、目录不存在等）返回 false 而不抛：调用方是设置页按钮，
     * 抛出去只会变成一次崩溃，而玩家需要的是「这一下没生效」的提示。
     */
    fun setEnabled(rt2Dir: File, packageId: String, enabled: Boolean): Boolean {
        val marker = markerFile(rt2Dir, packageId)
        return if (enabled) {
            // 恢复启用 = 删标记；本来就没有标记也算成功。
            !marker.exists() || runCatching { marker.delete() }.getOrDefault(false)
        } else {
            if (marker.isFile) {
                true
            } else {
                runCatching {
                    rt2Dir.mkdirs()
                    marker.createNewFile()
                }.getOrDefault(false)
            }
        }
    }

    /** 盘上所有被停用的 packageId（扫 `*.off`；无目录 / 读不出来 = 空集）。 */
    fun disabledPackageIds(rt2Dir: File): Set<String> {
        val files = rt2Dir.listFiles() ?: return emptySet()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(DISABLED_SUFFIX) }
            .map { it.name.removeSuffix(DISABLED_SUFFIX) }
            .toSet()
    }

    /**
     * 从目录清单里摘掉被停用的包（文件名词干 = packageId，与 `BuiltinManifestBuild`
     * 与 `ModImportController` 的落盘命名一致）。停用集为空时原样返回，零分配。
     */
    fun enabledOnly(rt2Dir: File, files: List<File>): List<File> {
        val disabled = disabledPackageIds(rt2Dir)
        if (disabled.isEmpty()) return files
        return files.filter { it.name.substringBeforeLast('.') !in disabled }
    }
}
