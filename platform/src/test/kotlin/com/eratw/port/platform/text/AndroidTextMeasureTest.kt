package com.eratw.port.platform.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextMeasureMath] 的纯 JVM 单测（CONTRACT-W3 w3-l09 / CONTRACT-Q2 §2.4）。
 *
 * 只钉**换算约定**，不碰 Android runtime（platform 单测侧 android.jar 是桩，
 * build.gradle.kts 的 `isReturnDefaultValues`）：样式位映射、字体名 → 族键、字号守卫、
 * charSpace 逐字折算、像素 → 半角格。
 *
 * 断言面逐条对 C#：
 * - 样式位 1=粗体 / 2=斜体 / 4=删除线 / 8=下划线（Process.cs:1508-1527、
 *   FunctionMethodCreator.cs:6361-6381），高位忽略；
 * - 空字体名归一缺省、族名序号比较大小写敏感（FontFactory.cs:13-17/40）；
 * - 空串宽度 0（StringMeasure.cs:53-56）；
 * - 像素 → 格用 `StringMeasure.cs:65` 的量化式，字号 18 时 36px = 4 格，与基准
 *   `HTML_STRINGLEN("<i>AB</i>CD") = 36`（`pc-golden-root.txt:297`、HtmlMethods.kt:41）
 *   同一口径；
 * - charSpace 是本轮 FN-04 契约新增字段（C# 全树零命中），按「每码位加 charSpace 像素」
 *   的字面口径钉住，见 [AndroidTextMeasure] 类 KDoc 的 TODO(verify)。
 *
 * Android 薄层（Paint/Typeface 调用本体）不在此覆盖，真机读数归组长在有 SDK 的闸上验。
 */
class AndroidTextMeasureTest {

    // ---- 样式位 ----

    @Test
    fun `样式位按脚本约定映射 4是删除线 8是下划线`() {
        // C# 逐位判：bit0 Bold、bit1 Italic、bit2 Strikeout、bit3 Underline
        // （Process.cs:1512-1527、FunctionMethodCreator.cs:6365-6380）。
        // 注意 GDI+ FontStyle 枚举里 4=Underline、8=Strikeout，与脚本位恰好互换 ——
        // 本用例钉的就是「映射显式做，不许原样透传数值」（GlyphMaskMath KDoc 同警告）。
        val p0 = TextMeasureMath.stylePlan(0)
        assertFalse(p0.bold)
        assertFalse(p0.italic)
        assertFalse(p0.strikeout)
        assertFalse(p0.underline)

        val p1 = TextMeasureMath.stylePlan(1)
        assertTrue(p1.bold)
        assertFalse(p1.italic)

        val p2 = TextMeasureMath.stylePlan(2)
        assertFalse(p2.bold)
        assertTrue(p2.italic)

        val p4 = TextMeasureMath.stylePlan(4)
        assertTrue("4 = 删除线，不是下划线", p4.strikeout)
        assertFalse(p4.underline)

        val p8 = TextMeasureMath.stylePlan(8)
        assertTrue("8 = 下划线，不是删除线", p8.underline)
        assertFalse(p8.strikeout)

        val p15 = TextMeasureMath.stylePlan(0xF)
        assertTrue(p15.bold && p15.italic && p15.strikeout && p15.underline)
    }

    @Test
    fun `样式位高位一律忽略`() {
        // 脚本侧只判 bit0..bit3（FunctionMethodCreator.cs:6361-6381 四个 if；
        // GSETFONT 另叠 and 0xF 截断，GraphicsMethods.kt:382）
        for (mask in intArrayOf(0x10, 0x20, 0x100, -0x100)) {
            val plan = TextMeasureMath.stylePlan(mask)
            assertFalse(plan.bold)
            assertFalse(plan.italic)
            assertFalse(plan.strikeout)
            assertFalse(plan.underline)
        }
    }

    // ---- 字体名 → 族键 ----

    @Test
    fun `空字体名归一缺省族`() {
        // StringStyle 构造：空字体名 → Config.FontName（StringStyle.cs:26-31）；
        // FontFactory.GetFont 开头的 IsNullOrEmpty 分支同判（FontFactory.cs:13-17）
        assertNull(TextMeasureMath.effectiveFontName(null))
        assertNull(TextMeasureMath.effectiveFontName(""))
        assertEquals("Saitamaar", TextMeasureMath.effectiveFontName("Saitamaar"))
        // 空白不是空：原版只判 IsNullOrEmpty，不去 trim
        assertEquals(" ", TextMeasureMath.effectiveFontName(" "))
    }

    @Test
    fun `未知族与大小写不一致回落默认`() {
        val known = setOf("Saitamaar", "黑体")
        assertEquals("命中原名", "Saitamaar", TextMeasureMath.resolveFamily("Saitamaar", known))
        assertEquals("CJK 族名同样按原文命中", "黑体", TextMeasureMath.resolveFamily("黑体", known))
        // 族名比较是序号相等（FontFactory.cs:40）：大小写不同就是另一个族
        assertNull(TextMeasureMath.resolveFamily("saitamaar", known))
        assertNull("未知族回落默认", TextMeasureMath.resolveFamily("ＭＳ ゴシック", known))
        assertNull("空名走缺省族", TextMeasureMath.resolveFamily(null, known))
        assertNull(TextMeasureMath.resolveFamily("", known))
        assertNull("空族表里全部落缺省", TextMeasureMath.resolveFamily("Saitamaar", emptySet()))
    }

    // ---- 字号守卫 ----

    @Test
    fun `字号非正一律拒绝`() {
        // 原版 new Font(name, emSize, ...) 对 emSize ≤ 0 抛 ArgumentException
        // （GraphicsMethods.kt:539-543 同判）；移植侧回 null 让 core 回落旧 measureText
        assertNull(TextMeasureMath.fontSizePx(0L))
        assertNull(TextMeasureMath.fontSizePx(-18L))
        assertEquals(18f, TextMeasureMath.fontSizePx(18L)!!, 0f)
        assertEquals(9f, TextMeasureMath.fontSizePx(9L)!!, 0f)
        // 格数换算同样以字号为守卫
        assertNull(TextMeasureMath.pixelsToCells(36L, 0L))
        assertNull(TextMeasureMath.pixelsToCells(36L, -18L))
    }

    // ---- 码位数 ----

    @Test
    fun `charSpace 按码位计 增补平面算一个字`() {
        assertEquals(0, TextMeasureMath.charCount(""))
        assertEquals(2, TextMeasureMath.charCount("AB"))
        assertEquals(1, TextMeasureMath.charCount("あ"))
        // 增补平面（U+20000）是一个码位、两个 UTF-16 码元：charSpace 只加一次
        assertEquals(1, TextMeasureMath.charCount("\uD840\uDC00"))
        assertEquals(3, TextMeasureMath.charCount("A\uD840\uDC00B"))
    }

    // ---- charSpace 折算 ----

    @Test
    fun `charSpace 逐字累加后整串量化一次`() {
        // base 36px = 4 个半角 × 18/2（基准 HTML_STRINGLEN("<i>AB</i>CD") = 36，
        // pc-golden-root.txt:297；HtmlMethods.kt:41）
        assertEquals(36L, TextMeasureMath.totalPixels(36.0, 4, 0L)!!)
        assertEquals("4 字 × 3px = +12", 48L, TextMeasureMath.totalPixels(36.0, 4, 3L)!!)
        assertEquals("负 charSpace 允许收紧字距", 28L, TextMeasureMath.totalPixels(36.0, 4, -2L)!!)
        assertEquals("只有 charSpace、没有前景宽", 20L, TextMeasureMath.totalPixels(0.0, 4, 5L)!!)
        assertEquals("没有字就不加 charSpace", 36L, TextMeasureMath.totalPixels(36.0, 0, 5L)!!)
        assertEquals(0L, TextMeasureMath.totalPixels(0.0, 0, 0L)!!)
        // 合计被负 charSpace 拉到负数时按 0（不许返回负宽）
        assertEquals(0L, TextMeasureMath.totalPixels(2.0, 4, -10L)!!)
    }

    @Test
    fun `整串只在最后四舍五入一次`() {
        // 原版 GetDisplayLength 返回 int（StringMeasure.cs:42-72）；本仓 TextMetrics 也是
        // 整串一次截断，不许逐字各自取整后相加
        assertEquals(9L, TextMeasureMath.totalPixels(9.4, 1, 0L)!!)
        assertEquals(10L, TextMeasureMath.totalPixels(9.5, 1, 0L)!!)
        // 9.4px 若按「每个字形各自四舍五入」会得 5 + 5 = 10；整串一次只有 9
        assertEquals(9L, TextMeasureMath.totalPixels(9.4, 2, 0L)!!)
        // charSpace 先并入合计再量化：3.49 + 2 × 2 = 7.49 ⇒ 7
        assertEquals(7L, TextMeasureMath.totalPixels(3.49, 2, 2L)!!)
    }

    @Test
    fun `坏入参回null`() {
        assertNull(TextMeasureMath.totalPixels(Double.NaN, 1, 0L))
        assertNull(TextMeasureMath.totalPixels(Double.POSITIVE_INFINITY, 1, 0L))
        assertNull("负的像素宽不是合法读数", TextMeasureMath.totalPixels(-1.0, 1, 0L))
        assertNull(TextMeasureMath.totalPixels(10.0, -1, 0L))
    }

    // ---- 像素 → 半角格 ----

    @Test
    fun `像素折半角格 字号18时36px是4格`() {
        // 量化式 = StringMeasure.cs:65，实现复用 CellMetrics.pixelsToCells（CellMetrics.kt:83-88）。
        // 基准：4 个半角字符宽 36px ⇒ 4 格（pc-golden-root.txt:297、HtmlMethods.kt:41）。
        assertEquals(0, TextMeasureMath.pixelsToCells(0L, 18L)!!)
        assertEquals(1, TextMeasureMath.pixelsToCells(9L, 18L)!!)
        assertEquals(2, TextMeasureMath.pixelsToCells(18L, 18L)!!)
        assertEquals(4, TextMeasureMath.pixelsToCells(36L, 18L)!!)
        assertEquals("不足半格量化到 0", 0, TextMeasureMath.pixelsToCells(1L, 18L)!!)
    }

    @Test
    fun `像素与格数同源 一站到底`() {
        // 逐字加 charSpace 的合计与「先折格」是同一读数的两种表达，故意只差量化这一步：
        // 4 字 × 9px = 36px ⇒ 4 格；再加 4 × 2px = 44px ⇒ (43/9.36+0.95)=5.54 ⇒ 5 格
        val plain = TextMeasureMath.totalPixels(36.0, 4, 0L)!!
        val spaced = TextMeasureMath.totalPixels(36.0, 4, 2L)!!
        assertEquals(4, TextMeasureMath.pixelsToCells(plain, 18L)!!)
        assertEquals(5, TextMeasureMath.pixelsToCells(spaced, 18L)!!)
    }
}
