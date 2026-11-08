package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.eratw.port.core.host.GraphicsBuffer
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.image.PngImage
import com.eratw.port.render.memory.PixelPreflight

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
 * 解码位图按**非预乘**落地（[pixelArrayDecodeOptions] 的 `inPremultiplied = false`）：
 * 本类的位图从不画上 Canvas（`getPixels` 抽完数组就 `recycle`），要的是**文件里的真实
 * 直通 ARGB**——`GraphicsBuffer` 的「`Color.ToArgb()` 同构」布局
 * （spec: `FunctionMethodCreator.cs:6189,6191,6255` 的 `ToArgb() & 0xFFFFFFFF`）。
 * 缺省的 `inPremultiplied = true` 会先预乘存底、`getPixels` 再去预乘读出（AOSP
 * `Bitmap_getPixel` 的 `SkUnPreMultiply::PMColorToColor` 那一步）：一来低 alpha 像素
 * 在预乘往返里丢精度（a=2 的 R200 会被量化到 255），二来把「半透明层的 RGB」整个
 * 押在设备端 Skia 的预乘/去预乘一致性上——真机的有损 WebP 区域解码已实测过契约外
 * 行为（`ResourceCsvHost.decodeRegion` 的 KDoc：要 288x244 得 289x244），玩家报的
 * 「立绘脸红变脸黑」（半透明叠层发暗、不透明内容正常）正是预乘值被当直通值消费的
 * 特征签名。源上就解成非预乘，这一族分叉在结构上不存在，桌面（ImageIO，直通）与
 * 真机逐字节同构。alpha==0 的归零由调用方统一做，这里不重复。
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

    private val options = pixelArrayDecodeOptions()

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
            // **分配前**预检（lane oomalloc）：下面这份 `IntArray(w*h)` 是正儿八经的 Java 堆像素阵列，
            // 而 core 侧调用它的两支都只 `catch (e: Exception)`（`GraphicsMethods.kt:1756-1761`
            // 的 `readImageFile` 与 1772-1796 的 PNG/WebP 两支）—— `OutOfMemoryError` 是 Error
            // 不是 Exception，于是这一笔直接穿到 VM 线程，整局没了。
            // 尺寸族与 0.2.39 那批 512 MiB 挡报告同一条（1,440,016 / 1,000,016 / 500,016 字节、
            // free 只剩几百 KB～4 MiB）；那几份的**帧**在 core 的 `GraphicsBuffer.create` /
            // `blitFrom`（本类的这一笔是它的孪生兄弟，且此前连兜住都没有），
            // 所以本闸按同一条判式在**要之前**拒。
            // 拒绝的出口 = 既有「解不出来」那一支：返回 null ⇒ GCREATEFROMFILE / GLOAD 折成 0、
            // CSV 资源行走告警支，屏上这一块缺一块，游戏继续（不改任何返回码分支）。
            if (!pixelLandingAllowed(w, h)) {
                Log.i(tag, "解码像素预检不足 $path ${w}x$h —— 按缺图降级，${PixelPreflight.readout(pixelBytes(w, h))}")
                return null
            }
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            Log.i(tag, "解码出 $path ${w}x$h")
            PngImage(w, h, pixels)
        } catch (t: Throwable) {
            // 预检是「下限」而不是保险：空闲读数会随瞬时垃圾浮动，真顶到了还是可能 OOM。
            // 这一支把**分配本体**也兜成同一条缺图降级路（与上面 BitmapFactory 那支同口径），
            // 绝不让它穿过 core 的 `catch (e: Exception)` 冒到 VM 线程。
            Log.i(tag, "解码像素失败 $path: ${t.javaClass.simpleName}")
            return null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 只读文件头量尺寸（`inJustDecodeBounds`，一个像素都不分配）。
     *
     * 供 `GCREATEFROMFILE` 的延迟装载用：本作语料的立绘工作表单张 8000x4000
     * （ARGB 122 MiB），为了量个尺寸先整张解进 Dalvik 堆就等于把玩家直接送进 OOM。
     * 尺寸越 `GraphicsBuffer.MAX_IMAGE_SIZE` 一律当量不出来（与 [decode] 同闸）。
     */
    override fun probe(path: String, bytes: ByteArray): IntArray? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        } catch (t: Throwable) {
            return null
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w > GraphicsBuffer.MAX_IMAGE_SIZE || h > GraphicsBuffer.MAX_IMAGE_SIZE) {
            return null
        }
        return intArrayOf(w, h)
    }

    internal companion object {
        /**
         * 「抽像素数组用」的解码选项（单一来源；[ResourceCsvHost] 的区域解码/整张解码
         * 同用这一份）。`inPremultiplied = false` 的理由见类 KDoc 的像素口径段——
         * 位图只喂 [Bitmap.getPixels]、从不画上 Canvas，非预乘存底让读出值与文件
         * 直通像素逐字节一致，桌面与真机同构。
         */
        internal fun pixelArrayDecodeOptions(): BitmapFactory.Options =
            BitmapFactory.Options().apply {
                inScaled = false
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inDither = false
                inPremultiplied = false
            }

        /**
         * 像素落地的预检判式（判定面与 Android 面分开：本函数纯 JVM，
         * 定向单测用 `eratw.preflight.*` 两条性质复刻真机贴顶挡位，见 [PixelPreflight]）。
         *
         * 三条腿、「为什么 native 位图不套这条」全在 [PixelPreflight]，这里只转发。
         */
        internal fun pixelLandingAllowed(width: Int, height: Int): Boolean =
            PixelPreflight.affordsPixels(width, height)

        /** 一份 ARGB 像素阵列的字节数（转 [PixelPreflight.pixelBytes]；取证日志与单测共用一把尺）。 */
        internal fun pixelBytes(width: Int, height: Int): Long = PixelPreflight.pixelBytes(width, height)

        private const val TAG = "eratw-image"
    }
}
