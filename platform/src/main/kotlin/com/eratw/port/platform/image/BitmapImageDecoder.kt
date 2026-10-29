package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage

/**
 * 系统解码器接进 core 的图形缓冲（M10-GFX 的解码侧）。
 *
 * 实现 [ImageDecoderHost]：`GCREATEFROMFILE` / `GLOAD` 取到文件字节后先问本类，
 * 解出来就按原样落进 `GraphicsBuffer` 的 ARGB 数组，core 自带的
 * `PngDecoder` / `WebpDecoder` 只在没装本通道时（纯 JVM 闸、语料对拍）生效。
 *
 * ## 为什么必须走系统解码器
 *
 * 本作语料的立绘与背景**绝大多数是 webp**（普查 2548 个），而 `WebpDecoder` 的有损位流
 * （`VP8 `）明确不做，实测只解通 37 个。原版 PC 上这些文件全部能出像素：
 * `Utils.LoadImage` 对 `.webp` 走 `WebP.Load`（spec: `Utils.cs:511-515`），
 * 其余走 `new Bitmap(path)`（spec: `Utils.cs:516`，GDI+ 吃 png/jpg/bmp/gif）。
 * 也就是说「解不出来」是我们移植侧的缺口，不是官方行为 ——
 * 本类用 `BitmapFactory`（png/jpg/bmp/gif 全支持；有损 webp API 17+、无损 webp API 18+）
 * 把这条补齐，脚本可见的返回码分支一个都没动（见 `GraphicsMethods.readImageFile`）。
 *
 * ## 像素口径
 *
 * [Bitmap.getPixels] 给的已经是 `0xAARRGGBB` 非预乘，与 `GraphicsBuffer` 的
 * 「`Color.ToArgb()` 同构」布局一致（spec: `FunctionMethodCreator.cs:6189,6191,6255`
 * 的 `ToArgb() & 0xFFFFFFFF`）。alpha==0 的归零由调用方统一做，这里不重复。
 *
 * 解码一律 `inScaled = false`（不做密度缩放：调用方要的是**文件像素**，
 * 任何按 displayMetrics 的重采样都会把 `GWIDTH` / `SPRITEWIDTH` 这类脚本可见读数改掉）、
 * `inPreferredConfig = ARGB_8888`（其它配置会丢 alpha 或改通道序）。
 *
 * @param tag logcat 标签；解码失败是常态（语料里有真损坏的 webp），只记一条 I 级
 */
class BitmapImageDecoder(
    private val tag: String = TAG,
) : ImageDecoderHost {

    private val options = BitmapFactory.Options().apply {
        inScaled = false
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inDither = false
    }

    override fun decode(path: String, bytes: ByteArray): PngImage? {
        if (bytes.isEmpty()) return null
        // 一次装载一行的量级（真机开局要问几十张），但缺了它就分不清「没读到字节」与
        // 「解出来却没登记」——2026-10-21 真机 `RM_タイトル画像用意` 死循环就是这么定位的。
        Log.i(tag, "解码入 $path ${bytes.size}B")
        val bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (t: Throwable) {
            // BitmapFactory 对畸形字节可能抛（VerifyError / OutOfMemoryError），
            // 原版对应位置是 Utils.cs:521-523 的空 catch ⇒ 折成 null ⇒ 函数返回 0。
            Log.i(tag, "解码失败 $path: ${t.javaClass.simpleName}")
            return null
        }
        if (bitmap == null) {
            Log.i(tag, "解码返回空 $path")
            return null
        }
        return try {
            val w = bitmap.width
            val h = bitmap.height
            if (w <= 0 || h <= 0 || w > GraphicsBuffer.MAX_IMAGE_SIZE ||
                h > GraphicsBuffer.MAX_IMAGE_SIZE
            ) {
                // 原版的 8192 上限（spec: `AbstractImage.cs:8`、`FunctionMethodCreator.cs:6905`）
                // 与 GraphicsBuffer.MAX_IMAGE_SIZE 同值，越界一律按解不出来处理。
                Log.i(tag, "尺寸越界 $path ${w}x$h")
                return null
            }
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            Log.i(tag, "解码出 $path ${w}x$h")
            PngImage(w, h, pixels)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val TAG = "eratw-image"
    }
}
