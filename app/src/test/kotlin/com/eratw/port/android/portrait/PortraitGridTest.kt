package com.eratw.port.android.portrait

import androidx.compose.ui.unit.dp
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.OptionOrigin
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 竖屏断点/形态/立绘宽度纯函数的口径钉（设计稿 §5.1 断点表、§3.5 判据代码块、§3.6.3 立绘常量表）。
 * 全部纯 JVM（PortraitGrid 不 import 任何 android.*；Dp 是 Compose 的纯 Kotlin 值类）。
 */
class PortraitGridTest {

    // ------------------------------------------------------------------
    // §5.1 断点表：全档 + 边界值
    // ------------------------------------------------------------------

    @Test
    fun `断点全档按表逐格成立`() {
        // 表：s(<380) → HUD 3/行、盘 2 列、Palam 2 列；m(380-519) → 3/3/3；
        //     l(520-639) → 6/3/4；xl(≥640) → 6/4/4（设计稿 §5.1 三个数值列）。
        val cases = listOf(
            360f to (SizeClass.S to Triple(3, 2, 2)),
            390f to (SizeClass.M to Triple(3, 3, 3)),
            412f to (SizeClass.M to Triple(3, 3, 3)),
            600f to (SizeClass.L to Triple(6, 3, 4)),
            800f to (SizeClass.XL to Triple(6, 4, 4)),
        )
        for ((width, expected) in cases) {
            val (size, cols) = expected
            assertEquals("width=$width 档", size, sizeClass(width))
            assertEquals("width=$width hudCols", cols.first, hudCols(size))
            assertEquals("width=$width cmdCols", cols.second, cmdCols(size))
            assertEquals("width=$width palamCols", cols.third, palamCols(size))
        }
    }

    @Test
    fun `断点边界值逐格不漂`() {
        // 379→s、380→m、519→m、520→l、639→l、640→xl（§5.1 表的格点）。
        assertEquals(SizeClass.S, sizeClass(379f))
        assertEquals(SizeClass.M, sizeClass(380f))
        assertEquals(SizeClass.M, sizeClass(519f))
        assertEquals(SizeClass.L, sizeClass(520f))
        assertEquals(SizeClass.L, sizeClass(639f))
        assertEquals(SizeClass.XL, sizeClass(640f))
    }

    // ------------------------------------------------------------------
    // §3.5 trayFormOf 七用例（3xx 判定优先于 size 判定）
    // ------------------------------------------------------------------

    private fun option(label: String, value: Any = 0L): ConsoleOption =
        ConsoleOption(value = value, label = label, origin = OptionOrigin.EXPLICIT)

    private fun numberedOptions(from: Int, count: Int): List<ConsoleOption> =
        (from until from + count).map { option("菜单项[$it]", it.toLong()) }

    @Test
    fun `G0 起床菜单页 27 钮无 3xx 归 MENU`() {
        // 主菜单实测 27 钮（§3.5 表 G0 行）；编号取 100 起的非 3xx。
        val options = numberedOptions(from = 100, count = 27)
        assertEquals(TrayForm.MENU, trayFormOf(options, charCount = 0))
    }

    @Test
    fun `G1 探索页 8 钮无 3xx 归 EXPLORE`() {
        val options = numberedOptions(from = 400, count = 8)
        assertEquals(TrayForm.EXPLORE, trayFormOf(options, charCount = 0))
    }

    @Test
    fun `G2 含 3xx 且 1 个角色块归 INTERACT`() {
        // 标签两种印法都认：尾缀 摸屁股[310] 与前缀 [300]（前缀形态见 UiPrefs.leanOptions 的口径注）。
        val options = listOf(
            option("摸屁股[310]", 310L),
            option("[300] 睡觉", 300L),
            option("系统菜单"),
        )
        assertEquals(TrayForm.INTERACT, trayFormOf(options, charCount = 1))
    }

    @Test
    fun `G3 含 3xx 且 2 个角色块归 MULTI`() {
        val options = listOf(option("摸屁股[310]", 310L), option("系统菜单"))
        assertEquals(TrayForm.MULTI, trayFormOf(options, charCount = 2))
    }

    @Test
    fun `G3 兜底 含 802 且角色块解析失败也归 MULTI`() {
        // 802 = 原版多角色的变更对象按钮，只作存在性判据（§3.5 括注、§10-13 禁拼提交值）。
        val options = listOf(
            option("摸屁股[310]", 310L),
            option("变更对象[802]", 802L),
        )
        assertEquals(TrayForm.MULTI, trayFormOf(options, charCount = 0))
    }

    @Test
    fun `G3 优先于 G0 20 钮含 312 也归 MULTI`() {
        // 顺序先判 3xx（§3.5）：20 钮 ≥16 本该落 MENU，含 3xx 永远优先。
        val options = numberedOptions(from = 100, count = 19) + option("爱抚[312]", 312L)
        assertEquals(20, options.size)
        assertEquals(TrayForm.MULTI, trayFormOf(options, charCount = 3))
    }

    @Test
    fun `空候选列表归 EXPLORE`() {
        assertEquals(TrayForm.EXPLORE, trayFormOf(emptyList(), charCount = 0))
    }

    @Test
    fun `编号剥取 只认最后的纯数字方括号尾缀`() {
        assertEquals(400, optionNumberOf("移动[400]"))
        assertEquals(100, optionNumberOf("[100] - 睁开眼睛"))
        assertEquals(802, optionNumberOf("变更对象[802]"))
        assertEquals(310, optionNumberOf("前缀[999] 干扰 摸屁股[310]"))
        assertEquals(null, optionNumberOf("系统菜单"))
        assertEquals(null, optionNumberOf("[abc] 不是编号"))
        assertEquals(null, optionNumberOf("残[310"))
        // 超出 Int 的数字串剥不出 → null（不抛）
        assertEquals(null, optionNumberOf("[99999999999]"))
    }

    // ------------------------------------------------------------------
    // §3.6.3 立绘宽度常量表：六档
    // ------------------------------------------------------------------

    @Test
    fun `立绘宽度按人数六档取常量`() {
        // 表：1→190dp（调用侧再与 60% 屏宽取小）、2→138、3→108、4→92、5~6→84、7~10→76。
        assertEquals(190.dp, spriteWidthFor(1))
        assertEquals(138.dp, spriteWidthFor(2))
        assertEquals(108.dp, spriteWidthFor(3))
        assertEquals(92.dp, spriteWidthFor(4))
        assertEquals(84.dp, spriteWidthFor(5))
        assertEquals(84.dp, spriteWidthFor(6))
        assertEquals(76.dp, spriteWidthFor(7))
        assertEquals(76.dp, spriteWidthFor(10))
    }

    @Test
    fun `立绘宽度 无立绘与超上限的边界`() {
        // 0 人 = 舞台整条不显示；>10 理论不发生（原版上限 10），仍按 76dp 横滑不隐藏（§0-13）。
        assertEquals(0.dp, spriteWidthFor(0))
        assertEquals(76.dp, spriteWidthFor(11))
    }
}
