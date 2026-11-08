package com.eratw.port.android

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.OptionOrigin
import com.eratw.port.core.host.OptionVisual
import com.eratw.port.core.host.isColorBlockLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右栏颜色呈现与密集调色板布局的纯 JVM 契约。
 *
 * 数据面（引擎是否把颜色带出来）由 core-vm 的 `LineBufferConsoleOptionColorTest` 钉；
 * 本类只钉壳层：
 * 1. 色块填充/描边精确；
 * 2. 连续 8 枚以上的 `■` 才切成 8×N 密集色格；
 * 3. 普通彩色按钮只染底色，不产生左侧色块，也不得进入调色板识别。
 */
class ShellUiRailColorTest {

    @Test
    fun swatchKeepsTheExactScriptRgbAndForcesOpaqueAlpha() {
        assertEquals(0xFFFF0000.toInt(), railSwatchArgb(0xFF0000))
        assertEquals(0xFF000000.toInt(), railSwatchArgb(0x000000))
        assertEquals(0xFFFFFFFF.toInt(), railSwatchArgb(0xFFFFFF))
    }

    @Test
    fun swatchBorderFlipsForLightAndDarkColors() {
        assertTrue("黑块的描边应偏白", (railSwatchBorderArgb(0x000000) and 0xFFFFFF) > 0x808080)
        assertTrue("白块的描边应偏黑", (railSwatchBorderArgb(0xFFFFFF) and 0xFFFFFF) < 0x808080)
    }

    @Test
    fun plainButtonsKeepThePreColorRailPalette() {
        assertEquals(0xE62A3A2A.toInt(), railContainerArgb(null))
        assertEquals(0xFFEDEDED.toInt(), railContentArgb(null))
    }

    @Test
    fun ordinaryColoredButtonsTintTheContainerWithoutBecomingSwatches() {
        val red = railContainerArgb(0xFF0000)
        assertEquals("普通按钮底仍用原半透明档", 0xE6, (red ushr 24) and 0xFF)
        assertTrue("红色按钮的红通道应高于无颜色按钮", ((red shr 16) and 0xFF) > 0x2A)
        assertEquals(0xFFEDEDED.toInt(), railContentArgb(0x000000))
        assertEquals(0xFF101010.toInt(), railContentArgb(0xFFFF80))
    }

    @Test
    fun colorBlockLabelDetectionIsStrictEnoughForOrdinaryTitles() {
        assertTrue(isColorBlockLabel("■"))
        assertTrue(isColorBlockLabel(" ● "))
        assertFalse(isColorBlockLabel("红色"))
        assertFalse(isColorBlockLabel("■ 说明"))
        assertFalse(isColorBlockLabel(""))
    }

    @Test
    fun sixteenSwatchesOnOneGameLineBecomeTwoDenseRowsOfEight() {
        val chips = (0 until 16).map { i ->
            swatch(value = 80000L + i, color = (i * 0x0F0F0F + 0x102030) and 0xFFFFFF, serial = 77L)
        }
        val rows = railLayoutOf(chips)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.paletteColumns == PALETTE_CHIPS_PER_ROW })
        assertEquals(listOf(8, 8), rows.map { it.chips.size })
        assertEquals((0 until 16).map { 80000L + it }, rows.flatMap { it.chips }.map { it.value })
        assertEquals(chips.map { it.colorRgb }, rows.flatMap { it.chips }.map { it.colorRgb })
    }

    @Test
    fun dollPalette128ColorsBecomeSixteenDenseRowsWithoutLosingOrderOrValue() {
        // MOB_SYSTEM.ERB:274-285：8 行 × 16 列，提交值 80000..80127；右栏按 8 列切成 16 行。
        val chips = (0 until 128).map { i ->
            swatch(value = 80000L + i, color = 0x010203 * i and 0xFFFFFF, serial = 123L)
        }
        val rows = railLayoutOf(chips)
        assertEquals(16, rows.size)
        assertTrue(rows.all { it.paletteColumns == PALETTE_CHIPS_PER_ROW })
        assertEquals(List(16) { 8 }, rows.map { it.chips.size })
        assertEquals((0 until 128).map { 80000L + it }, rows.flatMap { it.chips }.map { it.value })
        assertEquals(chips.map { it.colorRgb }, rows.flatMap { it.chips }.map { it.colorRgb })
    }

    @Test
    fun singleSwatchIsNeverPromotedToAPalette() {
        val one = listOf(swatch(1L, 0x112233, 10L))
        val rows = railLayoutOf(one)
        assertEquals(1, rows.size)
        assertNull(rows.single().paletteColumns)
    }

    @Test
    fun paletteRunInsideANormalLineOnlySplitsTheRun() {
        val chips = listOf(normal(1L, "页签", 10L, 0x123456)) +
            (0 until 8).map { swatch(100L + it, 0x102030, 10L) } +
            listOf(normal(200L, "结束", 10L, 0x654321))
        val rows = railLayoutOf(chips)
        assertEquals(listOf(null, PALETTE_CHIPS_PER_ROW, null), rows.map { it.paletteColumns })
        assertEquals(listOf(1, 8, 1), rows.map { it.chips.size })
        assertEquals(
            listOf(1L) + (100L until 108L).toList() + listOf(200L),
            rows.flatMap { it.chips }.map { it.value },
        )
    }

    @Test
    fun twoPaletteRunsOnOneLineStaySeparate() {
        val chips = (0 until 8).map { swatch(it.toLong(), 0x112233, 33L) } +
            listOf(normal(50L, "间隔", 33L, 0x445566)) +
            (0 until 8).map { swatch(100L + it, 0x778899, 33L) }
        val rows = railLayoutOf(chips)
        assertEquals(listOf(PALETTE_CHIPS_PER_ROW, null, PALETTE_CHIPS_PER_ROW), rows.map { it.paletteColumns })
        assertEquals(listOf(8, 1, 8), rows.map { it.chips.size })
    }

    @Test
    fun denseModeNeedsEnoughContiguousSwatchesAndNeverBleedsAcrossGameLines() {
        // 七枚不够进入密集模式，仍是同一条普通行；超过三枚由 UI 横向滚动。
        val seven = (0 until 7).map { swatch(1L + it, 0x112233, 10L) }
        assertTrue(railLayoutOf(seven).all { it.paletteColumns == null })
        assertEquals(listOf(7), railRowsOf(seven).map { it.size })

        // 两个游戏行各 8 枚：各自切成一行 8 列，不跨行合并。
        val twoLines = (0 until 8).map { swatch(it.toLong(), 0x112233, 10L) } +
            (0 until 8).map { swatch(100L + it, 0x445566, 20L) }
        val rows = railLayoutOf(twoLines)
        assertEquals(2, rows.size)
        assertEquals(listOf(10L, 20L), rows.map { it.chips.first().lineSerial })
    }

    @Test
    fun coloredLabelsAreNotSilentlyTurnedIntoAPalette() {
        val labeled = (0 until 8).map { i ->
            ConsoleOption(
                value = i.toLong(),
                label = "部件$i",
                origin = OptionOrigin.EXPLICIT,
                lineSerial = 5L,
                startCol = i * 2,
                endCol = i * 2 + 2,
                colorRgb = 0x123456,
            )
        }
        val rows = railLayoutOf(labeled)
        assertTrue(rows.all { it.paletteColumns == null })
        assertEquals(listOf(8), rows.map { it.chips.size })
    }

    @Test
    fun paletteRowsMayEndWithAShortRowThatKeepsEightColumnSlots() {
        val chips = (0 until 12).map { swatch(1L + it, 0x102030, 99L) }
        val rows = railLayoutOf(chips)
        assertEquals(listOf(8, 4), rows.map { it.chips.size })
        assertTrue(rows.all { it.paletteColumns == PALETTE_CHIPS_PER_ROW })
    }

    private fun normal(value: Long, label: String, serial: Long, color: Int? = null): ConsoleOption =
        ConsoleOption(
            value = value,
            label = label,
            origin = OptionOrigin.EXPLICIT,
            lineSerial = serial,
            startCol = value.toInt() * 2,
            endCol = value.toInt() * 2 + 2,
            colorRgb = color,
        )

    private fun swatch(value: Long, color: Int, serial: Long): ConsoleOption = ConsoleOption(
        value = value,
        label = "■",
        origin = OptionOrigin.EXPLICIT,
        lineSerial = serial,
        startCol = value.toInt() * 2,
        endCol = value.toInt() * 2 + 2,
        colorRgb = color,
        visual = OptionVisual.COLOR_SWATCH,
    )
    @Test
    fun paletteHitTestingUsesTheSameGeometryAsCanvasDrawing() {
        val width = 232f
        val gap = 4f
        val columns = 8
        val count = 8
        val cell = paletteCellWidth(width, columns, gap)
        assertTrue(cell > 0f)
        for (i in 0 until count) {
            val centerX = i * (cell + gap) + cell / 2f
            assertEquals(i, paletteIndexAt(centerX, width, columns, count, gap) ?: -1)
        }
        assertNull("列间空隙不触发相邻色块", paletteIndexAt(cell + gap / 2f, width, columns, count, gap))
        assertNull(paletteIndexAt(-1f, width, columns, count, gap))
        assertNull(paletteIndexAt(width + 1f, width, columns, count, gap))
        assertNull("末行不足 8 枚时空白位不可点", paletteIndexAt(cell * 5f, width, columns, 4, gap))
    }

    @Test
    fun paletteCellWidthNeverCollapsesToZeroOnNarrowRail() {
        assertTrue(paletteCellWidth(1f, 8, 4f) >= 1f)
        assertTrue(paletteCellWidth(0f, 8, 4f) >= 1f)
    }

}
