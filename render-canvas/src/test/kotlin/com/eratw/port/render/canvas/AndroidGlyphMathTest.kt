package com.eratw.port.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AndroidGlyphMath 的纯函数闸（CONTRACT-MODERN-R11-T4B S2）。
 *
 * 公式照 tools/uipreview 的 AwtGlyphRaster.kt:141-189：框宽取 advance 非正则给零、
 * 框高取行高；基线夹进零到行高的盒；样式位 1 粗 2 斜 4 删除线 8 下划线；
 * 空 advance 给零长位图。反向证明 D 改任一公式都要让这里红。
 * 真机像素对账记 TODO(verify)。
 */
class AndroidGlyphMathTest {

    @Test
    fun `advance 到框 宽取 advance 非正则给零 高取行高`() {
        assertEquals(18, AndroidGlyphMath.targetBox(18, 19).widthPx)
        assertEquals(19, AndroidGlyphMath.targetBox(18, 19).heightPx)
        assertEquals(0, AndroidGlyphMath.targetBox(0, 19).widthPx)
        assertEquals(0, AndroidGlyphMath.targetBox(-5, 19).widthPx)
        assertTrue(AndroidGlyphMath.targetBox(0, 19).isEmpty)
    }

    @Test
    fun `空 advance 给零长位图而不是一像素占位框`() {
        assertEquals(18 * 19, AndroidGlyphMath.bitmapFor(18, 19).size)
        assertEquals(0, AndroidGlyphMath.bitmapFor(0, 19).size)
        assertEquals(0, AndroidGlyphMath.bitmapFor(-5, 19).size)
    }

    @Test
    fun `基线夹进零到行高盒 descent 补满行高`() {
        assertEquals(12, AndroidGlyphMath.clampBaseline(12, 19))
        assertEquals(0, AndroidGlyphMath.clampBaseline(-3, 19))
        assertEquals(19, AndroidGlyphMath.clampBaseline(999, 19))
        assertEquals(7, AndroidGlyphMath.descentPx(12, 19))
        assertEquals(19, AndroidGlyphMath.clampBaseline(12, 19) + AndroidGlyphMath.descentPx(12, 19))
    }

    @Test
    fun `样式位粗斜进字型 删除线与下划线给手画线`() {
        assertEquals(1, AndroidGlyphMath.STYLE_BOLD)
        assertEquals(2, AndroidGlyphMath.STYLE_ITALIC)
        assertEquals(4, AndroidGlyphMath.STYLE_STRIKE)
        assertEquals(8, AndroidGlyphMath.STYLE_UNDERLINE)

        val boldItalic = AndroidGlyphMath.styleFlags(
            AndroidGlyphMath.STYLE_BOLD or AndroidGlyphMath.STYLE_ITALIC, 12, 19,
        )
        assertEquals(AndroidGlyphMath.TYPEFACE_BOLD_ITALIC, boldItalic.typefaceStyle)
        assertTrue(!boldItalic.strike)
        assertTrue(!boldItalic.underline)

        val lines = AndroidGlyphMath.styleFlags(
            AndroidGlyphMath.STYLE_STRIKE or AndroidGlyphMath.STYLE_UNDERLINE, 12, 19,
        )
        assertEquals(AndroidGlyphMath.TYPEFACE_NORMAL, lines.typefaceStyle)
        assertTrue(lines.strike)
        assertTrue(lines.underline)
        assertEquals(1, lines.thickness)
        assertEquals((12 - 19 / 4).coerceIn(0, 19 - 1), lines.strikeY)
        assertEquals((12 + 1).coerceIn(0, 19 - 1), lines.underlineY)
    }

    @Test
    fun `线宽至少一像素且随行高按十六分之一给`() {
        assertEquals(1, AndroidGlyphMath.lineThickness(19))
        assertEquals(1, AndroidGlyphMath.lineThickness(4))
        assertEquals(2, AndroidGlyphMath.lineThickness(32))
        assertEquals(3, AndroidGlyphMath.lineThickness(48))
    }

    @Test
    fun `基线或框公式改一处都要让断言红`() {
        // 反向证明 D 的钉子：去掉 clampBaseline 的 coerceIn、或把 targetBox 的
        // 非正则给零改掉，下面三条立即变红。
        assertEquals(19, AndroidGlyphMath.clampBaseline(40, 19))
        assertEquals(0, AndroidGlyphMath.targetBox(0, 19).widthPx)
        assertEquals(0, AndroidGlyphMath.bitmapFor(0, 19).size)
    }
}
