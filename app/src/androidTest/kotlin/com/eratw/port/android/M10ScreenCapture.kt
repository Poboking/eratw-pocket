package com.eratw.port.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

/**
 * 抓屏装置：两条路按可靠性顺序尝试，全部落到同一张 PNG 文件上。
 *
 * 1. [captureByUiDevice] —— 整屏（含系统栏），云真机上最有证据价值的形态。
 *    `UiDevice.takeScreenshot` 在 API 30+ 走 AccessibilityService 的截屏、以下走兼容路径，
 *    本波四档（26/30/33/35）都在覆盖内。
 * 2. [captureByViewDraw] —— 应用窗口内容的软件渲染（`View.draw`），不需要任何权限；
 *    UiDevice 失败（云机上偶发的截屏超时）时兜底。它只有窗口、没有系统栏，
 *    所以取证文本会写明这一张是哪条路来的。
 * 3. 两条都失败 → 返回 null，用例把它当成**判据失败**报红：拿不到屏证就等于没取证，不能算过。
 *
 * 解码一律带 `inSampleSize`（2 的幂，解码器只认这个），目标解码宽度不超过
 * [MAX_DECODED_WIDTH]。被测应用正背着 400~600 MiB 的装载峰值，取证装置不该再申请整屏
 * ARGB（1080x2400 = 10.4 MiB）；[Shot.samplePx] 用「原宽 / 解码宽」实测，判据读数因此
 * 仍然换算成屏幕像素，与抽样方向无关。
 */
class M10ScreenCapture(
    private val device: UiDevice?,
    private val dir: File,
) {

    /** 一张已落盘的截图 + 解码像素（统计与哈希都用它）。 */
    class Shot(
        val file: File,
        val via: String,
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        /** 解码像素 1 个 = [samplePx] 个屏幕像素（`inSampleSize` 实测值）。 */
        val samplePx: Int,
        val sourceWidthPx: Int,
        val sourceHeightPx: Int,
    ) {
        /** 抽样哈希：只用于「这一屏画完了没有」的稳定性判断，不进判据。 */
        val hash: Long by lazy {
            var h = 1125899906842597L // 一个大质数作哈希初值，只做稳定性比较，不追求散列质量
            val step = max(1, pixels.size / 4096)
            var i = 0
            while (i < pixels.size) {
                h = (h xor pixels[i].toLong()) * 1099511628211L
                i += step
            }
            h
        }

        /** 网格单位 = 解码抽样 x 统计步长，步长由 [ScreenStats.compute] 自己按宽度决定。 */
        fun stats(): ScreenStats = ScreenStats.compute(pixels, width, height, samplePx = samplePx)

        fun note(): String = "${file.name}（$via，解码 ${width}x$height，1 格=${samplePx}px 起）"
    }

    /** 阶段截图：文件名带序号、阶段名、API 与时刻。失败返回 null。 */
    fun capture(index: Int, stage: String, activityProvider: (() -> Activity?)?): Shot? {
        val base = "m10-%02d-%s-api%d-%d".format(index, M10Evidence.sanitize(stage), Build.VERSION.SDK_INT, System.currentTimeMillis())
        val png = File(dir, "$base.png")
        return writeTo(png, stage, activityProvider)
    }

    /**
     * 轮询用的轻量抽样：固定文件名反复覆盖，不留一堆中间文件。
     *
     * @return 屏幕哈希；抓不到屏时返回一个每轮都不同的负数，使「画面稳定」这条出口判据不会误触发。
     */
    fun pollHash(activityProvider: (() -> Activity?)?): Long {
        val png = File(dir, "m10-poll.png")
        val shot = writeTo(png, "poll", activityProvider) ?: return -(System.nanoTime() % 1_000_003L) - 1L
        return shot.hash
    }

    private fun writeTo(png: File, stage: String, activityProvider: (() -> Activity?)?): Shot? {
        dir.mkdirs()
        var via: String? = null
        if (device != null) {
            via = try {
                captureByUiDevice(device, png)
                "uiautomator整屏"
            } catch (t: Throwable) {
                Log.w(M10Evidence.TAG, "UiDevice 抓屏失败（$stage）：${t.javaClass.simpleName} ${t.message}")
                null
            }
        }
        if (via == null) {
            val activity = try {
                activityProvider?.invoke()
            } catch (t: Throwable) {
                Log.w(M10Evidence.TAG, "取 Activity 失败（$stage）：${t.javaClass.simpleName} ${t.message}")
                null
            }
            via = if (activity == null) {
                null
            } else {
                try {
                    captureByViewDraw(activity, png)
                    "窗口软件渲染"
                } catch (t: Throwable) {
                    Log.w(M10Evidence.TAG, "View.draw 抓屏失败（$stage）：${t.javaClass.simpleName} ${t.message}")
                    null
                }
            }
        }
        if (via == null || !png.isFile || png.length() <= 0L) {
            Log.w(M10Evidence.TAG, "抓屏彻底失败（$stage）：两条路都没出图")
            return null
        }
        return decode(png, via)
    }

    private fun decode(png: File, via: String): Shot? = try {
        val bounds = boundsOf(png)
        val requested = pow2SampleAtLeast(bounds.first)
        val options = BitmapFactory.Options().apply {
            inSampleSize = requested
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(png.absolutePath, options)
        if (bitmap == null) {
            Log.w(M10Evidence.TAG, "截图解码失败：${png.name}")
            null
        } else {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            bitmap.recycle()
            Shot(
                file = png,
                via = via,
                pixels = pixels,
                width = w,
                height = h,
                samplePx = max(1, bounds.first / max(1, w)),
                sourceWidthPx = bounds.first,
                sourceHeightPx = bounds.second,
            )
        }
    } catch (t: Throwable) {
        Log.w(M10Evidence.TAG, "截图处理异常（$via）：${t.javaClass.simpleName} ${t.message}")
        null
    }

    companion object {
        /** 解码后的目标宽度上限：统计精度够用，内存开销压在 1 MiB 量级。 */
        const val MAX_DECODED_WIDTH = 720

        /** 取「至少把宽度压到 [MAX_DECODED_WIDTH] 以内」的最小 2 的幂。 */
        fun pow2SampleAtLeast(sourceWidth: Int): Int {
            var sample = 1
            while (sourceWidth / sample > MAX_DECODED_WIDTH) sample *= 2
            return sample
        }

        /** UiDevice 抓整屏（PNG，无损）。失败抛 IOException。 */
        @Suppress("DEPRECATION")
        fun captureByUiDevice(device: UiDevice, target: File) {
            target.parentFile?.mkdirs()
            device.takeScreenshot(target)
            if (!target.isFile || target.length() <= 0L) throw IOException("takeScreenshot 没有产出文件：${target.absolutePath}")
        }

        /**
         * 窗口内容的软件渲染兜底：把 `decorView` 画进 Bitmap 再压成 PNG。
         *
         * 只画应用窗口（没有系统栏与输入法），对本判据足够 —— 立绘/背景都在控制台 View 里。
         */
        fun captureByViewDraw(activity: Activity, target: File) {
            val root: View = activity.window.decorView
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) throw IOException("窗口尚未布局：${w}x$h")
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.BLACK)
                root.draw(canvas)
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
            if (!target.isFile || target.length() <= 0L) throw IOException("View.draw 没有产出文件：${target.absolutePath}")
        }

        /** 只读图片尺寸（`inJustDecodeBounds`），不占像素内存。 */
        private fun boundsOf(png: File): Pair<Int, Int> {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(png.absolutePath, options)
            return max(1, options.outWidth) to max(1, options.outHeight)
        }
    }
}

/**
 * 从 [ActivityScenario] 里安全取当前 Activity（场景没起来 / 已被关就返回 null，不抛）。
 *
 * `onActivity` 在 UI 线程同步执行，返回值经这个 holder 带出来；轮询线程不直接碰 UI。
 */
class M10ActivityHolder {
    @Volatile
    private var current: Activity? = null

    fun refresh(scenario: ActivityScenario<MainActivity>?): Activity? {
        val s = scenario ?: return current
        return try {
            s.onActivity { current = it }
            current
        } catch (t: Throwable) {
            Log.w(M10Evidence.TAG, "取 Activity 失败：${t.javaClass.simpleName} ${t.message}")
            current
        }
    }

    fun current(): Activity? = current
}
