package com.eratw.port.platform.image

import android.graphics.BitmapFactory
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.ResourceFileHost
import com.eratw.port.core.image.PngImage
import java.io.File

/**
 * [ResourceFileHost] 的 Android 实现：扫 `resources` 下的 CSV、按路径读字节、读图片头、解码。
 *
 * 目录口径对应原版 `Program.ContentDir` = `<gameRoot>/resources`
 * （`Program.cs` 的 `ContentDir` 绑定 + `AppContents.cs:115` 的递归扫盘）。
 * 图片解码交给已装好的 [ImageDecoderHost]（`:platform` 的 `BitmapImageDecoder`，能吃 png/webp），
 * 与 `GCREATEFROMFILE` 同一通道，避免 CSV 资源与脚本图出现两套色彩/透明度口径。
 *
 * 只读文件头（[imageSize]）用 `inJustDecodeBounds`：本语料 4 个 CSV 引用 134 张图，
 * 全量解码约 105 MiB，尺寸探测只走头部，像素留给首访问（见 `GraphicsBuffer.createDeferred`）。
 *
 * TODO(verify)：`BitmapFactory` 的两条调用是**唯一**的 Android-API 面，`android.jar` 是桩
 * （`unitTests.isReturnDefaultValues` ⇒ `decodeByteArray/decodeFile` 恒返回 null、
 * `outWidth/outHeight` 停在 0），所以 [imageSize] 在单测里**恒返回 null**（走调用方的
 * 「尺寸未知」回落）。真机验收口径：CSV 引用的立绘 XRAY 图层能正常合成 ⇒ 头部读数正确；
 * 纯逻辑面（[isCsvName] / [relativeOf]）已由 `ResourceCsvHostTest` 钉死。
 */
class ResourceCsvHost(
    private val gameRoot: File,
    private val decoder: ImageDecoderHost,
) : ResourceFileHost {

    override fun listCsvFiles(): List<String> {
        val root = File(gameRoot, RESOURCES_DIR)
        if (!root.isDirectory) return emptyList()
        // 根路径只取一次：`walkTopDown()` 每命中一个文件都要一次 `absolutePath`，
        // 原先的 `relative()` 每次都重算 `gameRoot.absolutePath`（扫盘 134 张图时纯属重复）。
        val base = gameRoot.absolutePath
        return root.walkTopDown()
            .filter { it.isFile && isCsvName(it.name) }
            .map { relativeOf(base, it.absolutePath) }
            .toList()
    }

    override fun readBytes(path: String): ByteArray? =
        runCatching { File(gameRoot, path).readBytes() }.getOrNull()

    override fun imageSize(path: String, bytes: ByteArray?): IntArray? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        if (bytes != null) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } else {
            BitmapFactory.decodeFile(File(gameRoot, path).path, options)
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return intArrayOf(options.outWidth, options.outHeight)
    }

    override fun decode(path: String, bytes: ByteArray): PngImage? = decoder.decode(path, bytes)

    /**
     * 纯逻辑伴生（`AndroidApiSurfaceGuardTest` 的登记要求 a）：本类唯一碰 Android 的地方是
     * [imageSize] 的 `BitmapFactory`，扫盘判定与相对路径换算全是纯函数，放这里由
     * `ResourceCsvHostTest` 在纯 JVM 下钉死 —— `android.jar` 是桩（`unitTests.
     * isReturnDefaultValues`），留在实例方法里就等于零覆盖。
     */
    internal companion object {
        /** 原版 `Program.ContentDir` 的目录名（`AppContents.cs:109-115`）。 */
        const val RESOURCES_DIR = "resources"

        /** 后缀是否是 CSV（大小写不敏感；口径同 `AppContents.cs:115` 只认 `.csv`）。 */
        fun isCsvName(name: String): Boolean = name.endsWith(".csv", ignoreCase = true)

        /**
         * 绝对路径 → 「相对 [baseAbs]、`/` 分隔」的路径（CSV 第二列的图片路径也按 `/` 拼）。
         *
         * **分隔符不读 `File.separatorChar`**：Android 恒 `/`，而这条逻辑的单测跑在 Windows 上
         * （`\`），读平台分隔符会让同一份逻辑在两边给出不同字符串 —— 那正是「真机对、CI 红」
         * 的经典形状。两种分隔符都认，输出恒 `/`。
         */
        fun relativeOf(baseAbs: String, fullAbs: String): String {
            val base = baseAbs.trimEnd('/', '\\')
            val rest = if (fullAbs.startsWith(base)) fullAbs.substring(base.length) else fullAbs
            return rest.trimStart('/', '\\').replace('\\', '/')
        }
    }
}
