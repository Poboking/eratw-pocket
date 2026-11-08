package com.eratw.port.platform.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.eratw.port.platform.text.GlyphMaskMath.InkBounds
import org.junit.Test

/**
 * L15 稳定性面：病态字号 / 病态盒尺寸在**纯算术层**就被拒，而不是把 NaN、负宽、
 * 几百 MB 的一次性分配交给宿主（AGENTS.md 铁律：`runCatching` 吞 OOM 只会每个字形再撞一次）。
 *
 * 断言面：
 * - [TextMeasureMath.fontSizePx]：正常档逐值不变，超 [TextMeasureMath.MAX_FONT_PX] 与
 *   `Long.MAX_VALUE` 回 null（与字号 ≤ 0 同一条回落链）；
 * - [TextMeasureMath.totalPixels]：NaN / +Inf / 折不进 Int 的合计回 null，`charCount = 0`
 *   时病态 charSpace 不参与运算；
 * - [GlyphMaskMath.layoutFor]：单边先过闸再加法，避免 `Float → Int` 饱和回绕；面积闸
 *   挡住 4096×4096 这类「单边合法但临时内存无界」的盒；
 * - [GlyphMaskMath.extractAlpha] / [GlyphMaskMath.inkBounds] / [GlyphMaskMath.cropAlpha]：
 *   数组比盒短、维度非法一律回空表/null（不许抛越界）。
 */
class StabilityL15TextGlyphGuardTest {

    @Test
    fun `字号闸只挡病态值，正常档逐值不变`() {
        assertEquals(18f, TextMeasureMath.fontSizePx(18L)!!)
        assertEquals(100f, TextMeasureMath.fontSizePx(100L)!!, 0f)
        assertEquals(4096f, TextMeasureMath.fontSizePx(TextMeasureMath.MAX_FONT_PX)!!)
        assertNull(TextMeasureMath.fontSizePx(TextMeasureMath.MAX_FONT_PX + 1))
        assertNull("脚本给 Long.MAX_VALUE 时 toFloat 会一路溢到 Inf", TextMeasureMath.fontSizePx(Long.MAX_VALUE))
        assertNull(TextMeasureMath.fontSizePx(0L))
        assertNull(TextMeasureMath.fontSizePx(-1L))
    }

    @Test
    fun `整串宽度读数折不进 Int 时回 null 而不是负宽`() {
        assertEquals(48L, TextMeasureMath.totalPixels(36.0, 4, 3L)!!)
        assertEquals("0 个码位时病态字距不参与运算", 36L, TextMeasureMath.totalPixels(36.0, 0, Long.MAX_VALUE)!!)
        assertNull(TextMeasureMath.totalPixels(Double.NaN, 4, 0L))
        assertNull(TextMeasureMath.totalPixels(Double.POSITIVE_INFINITY, 4, 0L))
        assertNull("1 字 × Long.MAX 字距：合计 = +Inf", TextMeasureMath.totalPixels(1.0, 1, Long.MAX_VALUE))
        assertNull("超过 Int 上界：下游折 Int 就是负宽", TextMeasureMath.totalPixels(3.0e9, 1, 0L))
        assertEquals(TextMeasureMath.MAX_TOTAL_PIXELS, TextMeasureMath.totalPixels(TextMeasureMath.MAX_TOTAL_PIXELS.toDouble(), 0, 0L))
    }

    @Test
    fun `离屏盒单边与面积双闸，正常字形盒不受影响`() {
        val box = GlyphMaskMath.layoutFor(advancePx = 18f, aboveBaselinePx = 20f, belowBaselinePx = 5f, padPx = 2)
        assertNotNull(box)
        assertEquals(22, box!!.width)
        assertEquals(29, box.height)
        assertEquals(2, box.drawX)
        assertEquals(22, box.baselineY)

        assertNull("Float 大值 toInt 饱和后加 pad 会回绕成负数", GlyphMaskMath.layoutFor(1e30f, 20f, 5f, 2))
        assertNull(GlyphMaskMath.layoutFor(Float.POSITIVE_INFINITY, 20f, 5f, 2))
        assertNull(GlyphMaskMath.layoutFor(Float.NaN, 20f, 5f, 2))
        assertNull(GlyphMaskMath.layoutFor(-1f, 20f, 5f, 2))
        assertNull(GlyphMaskMath.layoutFor(18f, 20f, 5f, -1))
        assertNull("两个 3e38 相加在 Float 里是 +Inf，故各自先过闸", GlyphMaskMath.layoutFor(18f, 3e38f, 3e38f, 0))
        assertNull("单边 2000 合法但面积 400 万 px ≈ 单字形峰值 36 MiB", GlyphMaskMath.layoutFor(2000f, 2000f, 0f, 0))
        assertNotNull("1000 px 级字号的 CJK 字形仍要放得下", GlyphMaskMath.layoutFor(1000f, 1000f, 0f, 0))
    }

    @Test
    fun `覆盖度三步遇到短数组与非法维度一律空表，不抛越界`() {
        val pixels = IntArray(4) { 0xFF0000FF.toInt() }
        assertEquals(4, GlyphMaskMath.extractAlpha(pixels, width = 2, height = 2).size)
        assertEquals(255, GlyphMaskMath.extractAlpha(pixels, width = 2, height = 2)[0].toInt() and 0xFF)
        assertEquals("数组比盒短：整块放弃", 0, GlyphMaskMath.extractAlpha(IntArray(3), width = 2, height = 2).size)
        assertEquals(0, GlyphMaskMath.extractAlpha(pixels, width = 0, height = 2).size)
        assertEquals(0, GlyphMaskMath.extractAlpha(pixels, width = -1, height = 2).size)
        assertEquals(
            "乘积溢出 Int 时不许 ByteArray(负数)",
            0,
            GlyphMaskMath.extractAlpha(IntArray(4), width = 65536, height = 65536).size,
        )

        val alpha = ByteArray(4) { 200.toByte() }
        assertEquals(0, GlyphMaskMath.inkBounds(alpha, width = 2, height = 2)!!.top)
        assertNull("alpha 比盒短：按无墨处理", GlyphMaskMath.inkBounds(ByteArray(3), width = 2, height = 2))
        assertNull(GlyphMaskMath.inkBounds(alpha, width = 0, height = 2))
        assertNull("全透明即无墨", GlyphMaskMath.inkBounds(ByteArray(4), width = 2, height = 2))

        val bounds = InkBounds(left = 0, top = 0, right = 2, bottom = 1)
        assertEquals(2, GlyphMaskMath.cropAlpha(alpha, width = 2, bounds = bounds).size)
        assertEquals("右缘越过盒宽：非法包围盒", 0, GlyphMaskMath.cropAlpha(alpha, width = 2, bounds = InkBounds(0, 0, 3, 1)).size)
        assertEquals("源数组覆盖不到最后一行右缘", 0, GlyphMaskMath.cropAlpha(ByteArray(3), width = 2, bounds = InkBounds(0, 1, 2, 2)).size)
        assertEquals(0, GlyphMaskMath.cropAlpha(alpha, width = 2, bounds = InkBounds(0, 0, 1, 0)).size)
        assertEquals(0, GlyphMaskMath.cropAlpha(alpha, width = 2, bounds = InkBounds(-1, 0, 1, 1)).size)
    }
}
