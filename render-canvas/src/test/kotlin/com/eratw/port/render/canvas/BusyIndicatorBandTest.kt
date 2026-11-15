package com.eratw.port.render.canvas

import com.eratw.port.core.host.BusyIndicator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 长页「少女祈祷中」横带的几何/形态钉子（PRAY-BUSY，纯 JVM，不起 Robolectric）。
 *
 * 主人拍死的形态在这里逐条落成判据：水平居中、单独占最下面一行（带高 = 一行）、
 * 底为不透明底色（由 View 绘制侧保证 `opaque(backgroundArgb)`，几何这里钉矩形）、
 * 只在 elapsedMs ≥ 1 s 时出现、置 null 后不画。
 */
class BusyIndicatorBandTest {

    @Test
    fun `带高恰好一行并紧贴内容区下沿`() {
        val rect = BusyIndicatorBand.bandRect(
            contentLeft = 12f,
            contentRight = 212f,
            contentBottom = 320f,
            rowHeightPx = 24f,
        )
        assertEquals(24f, rect.height, "带高必须等于一行（既有 lineHeightPx）")
        assertEquals(320f, rect.bottom, "带底必须贴内容区下沿")
        assertEquals(296f, rect.top, "带顶 = 下沿 - 一行")
        assertEquals(12f, rect.left)
        assertEquals(212f, rect.right)
    }

    @Test
    fun `水平居中左右留白差不超过一像素`() {
        val rect = BusyIndicatorBand.bandRect(10f, 210f, 120f, 20f)
        val measured = 61f
        val x = BusyIndicatorBand.textX(rect, measured)
        val leftGap = x - rect.left
        val rightGap = rect.right - (x + measured)
        assertTrue(kotlin.math.abs(leftGap - rightGap) <= 1f, "左右留白差 ${leftGap} vs ${rightGap}")
        assertEquals((rect.width - measured) / 2f, x - rect.left)
    }

    @Test
    fun `文本比带还宽时贴左沿不出界`() {
        val rect = BusyIndicatorBand.bandRect(10f, 110f, 120f, 20f)
        assertEquals(10f, BusyIndicatorBand.textX(rect, 150f), "放不下时从左沿落笔，不画到内容区外")
    }

    @Test
    fun `只在 elapsedMs 满一秒后出现 置 null 不画`() {
        assertFalse(BusyIndicatorBand.isVisible(null), "宿主置 null 后必须不画")
        assertFalse(BusyIndicatorBand.isVisible(BusyIndicator(0L)))
        assertFalse(BusyIndicatorBand.isVisible(BusyIndicator(999L)), "不足 1 s 不出现")
        assertTrue(BusyIndicatorBand.isVisible(BusyIndicator(1_000L)), "满 1 s 开始出现")
        assertTrue(BusyIndicatorBand.isVisible(BusyIndicator(130_000L)), "整段日始长链一直在")
    }

    @Test
    fun `文案是少女祈祷中加动点且约一秒一循环`() {
        val a = BusyIndicatorBand.text(BusyIndicator(1_000L))
        val b = BusyIndicatorBand.text(BusyIndicator(1_000L + BusyIndicatorBand.DOT_FRAME_MS))
        val c = BusyIndicatorBand.text(BusyIndicator(1_000L + BusyIndicatorBand.DOT_FRAME_MS * 2))
        val wrapped = BusyIndicatorBand.text(BusyIndicator(1_000L + BusyIndicatorBand.DOT_FRAME_MS * 3))
        assertEquals("少女祈祷中.", a)
        assertEquals("少女祈祷中..", b)
        assertEquals("少女祈祷中...", c)
        assertEquals(a, wrapped, "三帧走完绕回第一帧（约 1 s 一个周期）")
    }

    @Test
    fun `呼吸 alpha 在一秒内往返且不消失`() {
        assertEquals(BusyIndicatorBand.MIN_ALPHA, BusyIndicatorBand.textAlpha(BusyIndicator(1_000L)))
        assertEquals(BusyIndicatorBand.MAX_ALPHA, BusyIndicatorBand.textAlpha(BusyIndicator(1_500L)))
        assertEquals(BusyIndicatorBand.MIN_ALPHA, BusyIndicatorBand.textAlpha(BusyIndicator(2_000L)))
        for (t in 0L..2_000L step 50L) {
            val a = BusyIndicatorBand.textAlpha(BusyIndicator(t))
            assertTrue(
                a in BusyIndicatorBand.MIN_ALPHA..BusyIndicatorBand.MAX_ALPHA,
                "alpha=$a 越界（t=$t）",
            )
        }
    }

    @Test
    fun `基线在行盒内垂直居中`() {
        val rect = BusyIndicatorBand.bandRect(0f, 100f, 200f, 20f)
        val baseline = BusyIndicatorBand.baselineY(rect, ascent = -15f, descent = 5f)
        assertEquals(195f, baseline, "行盒 180..200 内居中：基线 195")
    }
}
