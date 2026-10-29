package com.eratw.port.platform.storage

import android.content.res.AssetManager
import java.io.IOException
import java.io.InputStream

/**
 * [GameAssetTree] 的 Android 实现：包内 assets 走 [AssetManager]。
 *
 * 三处平台口径都写在方法上（都是 `AssetManager` 的老脾气）：
 * - [listDir]：`AssetManager.list` 对「不存在的目录」「空目录」「普通文件」都返回空表，
 *   所以解包器不能用「空表」反推目录存在与否，只能再看 [openFile]（见
 *   [GameAssetInstaller] 的 `walkTree`）；
 * - [fileSize]：只有 **STORED** 条目能用 `openFd` 拿到长度；压缩条目（`-PeratwErbcCompress`
 *   或非 noCompress 扩展名的 CSV/图片）必然抛 `FileNotFoundException` ⇒ 返回 null，
 *   解包器按「目标存在即视为已装」处理，不会为了比大小把整条资产 inflate 一遍；
 * - [openFile]：对目录同样抛 `FileNotFoundException`，正好当「这不是文件」的判据。
 *
 * 不持 `Context`：只吃 [AssetManager]，单测侧不会碰到 Android API。
 */
class AndroidAssetTree(private val assets: AssetManager) : GameAssetTree {

    override fun openFile(path: String): InputStream? =
        try {
            assets.open(path, AssetManager.ACCESS_STREAMING)
        } catch (_: IOException) {
            null
        }

    override fun listDir(path: String): List<String> =
        try {
            assets.list(path)?.toList().orEmpty()
        } catch (_: IOException) {
            emptyList()
        }

    override fun fileSize(path: String): Long? =
        try {
            assets.openFd(path).use { it.length.takeIf { length -> length >= 0L } }
        } catch (_: IOException) {
            null
        }
}
