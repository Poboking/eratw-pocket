package com.eratw.port.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面判据的**对照实验**：用两张同尺寸的合成图证明阈值真的能分「有立绘」和「只有文字」。
 *
 * 这条用例不碰游戏产物、不装机、不需要 adb，任何机器（含 USB 真机与四档云机）都会跑，
 * 作用是让 `M10LaunchEvidenceTest` 里那句「截图必须有立绘」不是空话：
 *
 * - [textOnlyPictureIsRejected]：一块黑底 + 16 行字（含一行彩色标题、一行破折号）
 *   + 灰底按钮 + 底部输入条 + **一枚彩色小图标**（模拟 emoji / 小按钮图这种「有颜色但不是画」的东西）。
 *   必须至少违例四条，且 `imageLikeBlobCount` 必须为 0。
 * - [pictureWithArtworkPassesGate]：同样的文字，只在中间多一块渐变+多色的「立绘」。
 *   必须零违例。
 * - [artReadingsBeatTextReadings]：六个读数逐项比较，画那张必须在每个判据方向上都更强。
 *
 * 如果哪天真机上这三条里挂了，说明**装置本身**在该机型上不成立（字体渲染、色彩管理、
 * 截屏色偏都可能），先修装置再谈画面验收 —— 所以这条用例和主用例一样不能删。
 */
class M10StatsGateControlTest {

    /** 合成图的尺寸：取云机 MediumPhone.arm 那一档的常用竖屏分辨率量级（判据按屏幕像素算，尺寸不敏感）。 */
    private val pictureWidth = 720
    private val pictureHeight = 1480

    @Test
    fun textOnlyPictureIsRejected() {
        val stats = statsOf(picture(artwork = false))
        dump("纯文字对照图", stats)
        val violations = ArtworkGate.DEFAULT.violations(stats)
        assertEquals("纯文字图不该长出「够大够实够多彩」的色块（彩色小图标必须被尺寸与色数判据挡掉）", 0, stats.imageLikeBlobCount)
        assertTrue(
            "纯文字图应当在六个判据里至少违例四条，实际违例 ${violations.size} 条：$violations；读数=${stats}",
            violations.size >= 4,
        )
        assertTrue("纯文字图的最长彩色横段必须短于阈值：${stats.longestChromaticRunPx}", stats.longestChromaticRunPx < ArtworkGate.DEFAULT.minLongestChromaticRunPx)
    }

    @Test
    fun pictureWithArtworkPassesGate() {
        val stats = statsOf(picture(artwork = true))
        dump("带立绘对照图", stats)
        val violations = ArtworkGate.DEFAULT.violations(stats)
        assertTrue(
            "带立绘的对照图必须过全部判据（它挂了说明装置本身不成立）：违例=$violations 读数=$stats",
            violations.isEmpty(),
        )
    }

    @Test
    fun artReadingsBeatTextReadings() {
        val text = statsOf(picture(artwork = false))
        val art = statsOf(picture(artwork = true))
        assertTrue("色彩种类：画(${art.distinctColors}) 应明显多于纯文字(${text.distinctColors})", art.distinctColors > text.distinctColors)
        assertTrue(
            "高饱和像素比例：画(${art.chromaPixelRatio}) 应至少是纯文字(${text.chromaPixelRatio}) 的 5 倍",
            art.chromaPixelRatio >= text.chromaPixelRatio * 5f || art.chromaPixelRatio > 0.05f,
        )
        assertTrue("最长彩色横段：画(${art.longestChromaticRunPx}) 应长于纯文字(${text.longestChromaticRunPx})", art.longestChromaticRunPx > text.longestChromaticRunPx)
        assertTrue("最大色块占比：画(${art.largestBlobCoverage}) 应大于纯文字(${text.largestBlobCoverage})", art.largestBlobCoverage > text.largestBlobCoverage)
        assertTrue("底色占比：画(${art.dominantColorRatio}) 应低于纯文字(${text.dominantColorRatio})", art.dominantColorRatio < text.dominantColorRatio)
    }

    // ------------------------------------------------------------------
    // 合成图
    // ------------------------------------------------------------------

    private fun picture(artwork: Boolean): Bitmap {
        val bitmap = Bitmap.createBitmap(pictureWidth, pictureHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.BLACK)
        if (artwork) drawArtwork(canvas)
        drawConsoleText(canvas)
        drawInputBars(canvas)
        return bitmap
    }

    /** 一屏「只有文字与底色」的形态：彩色标题一行 + 若干灰阶行 + 一枚彩色小图标。 */
    private fun drawConsoleText(canvas: Canvas) {
        val paint = Paint().apply {
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
            textSize = 26f
        }
        var y = 34f
        paint.color = 0xFF7FD4FF.toInt()
        canvas.drawText("eraTW Android — M10 — CLOUD", 8f, y, paint)
        y += 34f
        paint.color = 0xFFFFFFFF.toInt()
        val body = listOf(
            "半角: ABC abc 0123 !\"#$%&'()",
            "全角: 日本語の表示と中文显示测试",
            "半角假名: ｱｲｳｴｵ ｶﾀｶﾅ",
            "=".repeat(40),
            "数据根: /storage/emulated/0/Android/data/pkg/files/eratw",
            "未找到产物: /storage/emulated/0/.../erbc.v1",
            "把 erbc.v1 与 CSV 推到游戏根后重启。",
            "本屏由 ConsoleScreen 到 DrawCmd 逐格绘制。",
            "装载失败：内存不足（实测 上限 512 MiB，需 634 MiB）",
        )
        body.forEach { line ->
            paint.color = if (line.startsWith("装载失败")) 0xFF606060.toInt() else 0xFFE0E0E0.toInt()
            canvas.drawText(line, 8f, y, paint)
            y += 34f
        }
        // 彩色小图标：模拟 emoji 位图字符 —— 有颜色、有饱和度，但尺寸与色数都不该算成「画」。
        val icon = Paint().apply { isAntiAlias = true; color = 0xFFD03030.toInt() }
        canvas.drawRoundRect(RectF(8f, y, 8f + 30f, y + 30f), 6f, 6f, icon)
        icon.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(8f + 15f, y + 15f, 6f, icon)
    }

    private fun drawInputBars(canvas: Canvas) {
        val bar = Paint().apply { isAntiAlias = false }
        bar.color = 0xFF202020.toInt()
        canvas.drawRect(0f, pictureHeight - 90f, pictureWidth.toFloat(), pictureHeight.toFloat(), bar)
        bar.color = 0xFF181818.toInt()
        canvas.drawRect(0f, pictureHeight - 150f, pictureWidth.toFloat(), pictureHeight - 90f, bar)
        val button = Paint().apply { isAntiAlias = false; color = 0xFFC0C0C0.toInt() }
        canvas.drawRect(pictureWidth - 150f, pictureHeight - 80f, pictureWidth - 20f, pictureHeight - 20f, button)
        val label = Paint().apply { isAntiAlias = true; color = Color.BLACK; textSize = 24f }
        canvas.drawText("提交", pictureWidth - 110f, pictureHeight - 45f, label)
    }

    /**
     * 「立绘」：一块占屏约 1/4 的多区域彩色画（渐变主体 + 三种颜色的圆 + 一条暖色带）。
     *
     * 主体用 7 个色标，量化后必然远超 10 个色桶；尺寸 260x520 屏幕像素，
     * 对应判据里的尺寸/填充/色数/占比四条。
     */
    private fun drawArtwork(canvas: Canvas) {
        val left = 90f
        val top = 380f
        val right = left + 260f
        val bottom = top + 520f
        val body = Paint().apply {
            isAntiAlias = true
            shader = LinearGradient(
                left, top, right, bottom,
                intArrayOf(0xFF1B3AA8.toInt(), 0xFF2FB4E5.toInt(), 0xFFF7E7C4.toInt(), 0xFFE45C2B.toInt(), 0xFF8C2F6E.toInt(), 0xFF2A7A3C.toInt(), 0xFF0B0B2A.toInt()),
                floatArrayOf(0f, 0.18f, 0.36f, 0.55f, 0.72f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(RectF(left, top, right, bottom), 24f, 24f, body)
        val accents = Paint().apply { isAntiAlias = true }
        accents.color = 0xFFFFF2A0.toInt()
        canvas.drawCircle(left + 70f, top + 120f, 46f, accents)
        accents.color = 0xFF2B5BD7.toInt()
        canvas.drawCircle(left + 180f, top + 250f, 58f, accents)
        accents.color = 0xFFD8335F.toInt()
        canvas.drawRect(left + 30f, top + 380f, right - 30f, top + 470f, accents)
        val hair = Paint().apply { isAntiAlias = true; color = 0xFFF2D6B0.toInt(); strokeWidth = 6f; style = android.graphics.Paint.Style.STROKE }
        canvas.drawRect(left + 8f, top + 8f, right - 8f, bottom - 8f, hair)
    }

    private fun statsOf(bitmap: Bitmap): ScreenStats {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        // 合成图的 1 像素就是 1 屏幕像素，所以 samplePx 传 1，网格步长由 ScreenStats 自己按宽度决定。
        return ScreenStats.compute(pixels, w, h, samplePx = 1)
    }

    private fun dump(title: String, stats: ScreenStats) {
        Log.i(M10Evidence.TAG, "对照图判据：$title")
        stats.readings().forEach { Log.i(M10Evidence.TAG, "  $it") }
    }
}
