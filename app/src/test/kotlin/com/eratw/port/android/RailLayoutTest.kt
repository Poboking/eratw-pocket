package com.eratw.port.android

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.OptionOrigin
import com.eratw.port.core.host.OptionVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右栏三档布局纯函数（[railLayoutOf]）的契约（2026-11-11 QQ 群反馈波）。
 *
 * 三档共用：按游戏显示行分组、调色板 run 切 8 列密集行。差异只在普通段：
 * - ROW_SCROLL（缺省）：一条游戏行 = 一行，超过每行枚数横滑；
 * - COLUMN 竖列：按每行枚数折行，行内均分（stretch），不横滑；
 * - EXPANDED 完全展开：同样折行，格子定宽（stretch=false），不横滑。
 *
 * 兼容垫片 `railLayoutOf(chips)`（竖屏指令盘在走）必须与「ROW_SCROLL + 3」逐字节同形：
 * 老玩家的缺省观感与竖屏指令盘的分行口径都不许漂。
 */
class RailLayoutTest {

    private fun chip(value: Long, serial: Long = 10L): ConsoleOption = ConsoleOption(
        value = value,
        label = "选项$value",
        origin = OptionOrigin.EXPLICIT,
        lineSerial = serial,
        startCol = value.toInt() * 2,
        endCol = value.toInt() * 2 + 2,
    )

    private fun swatch(value: Long, serial: Long = 10L): ConsoleOption = ConsoleOption(
        value = value,
        label = "■",
        origin = OptionOrigin.EXPLICIT,
        lineSerial = serial,
        startCol = 0,
        endCol = 2,
        visual = OptionVisual.COLOR_SWATCH,
    )

    private fun line(vararg values: Long, serial: Long = 10L): List<ConsoleOption> =
        values.map { chip(it, serial) }

    // ------------------------------------------------------------------
    // 兼容垫片
    // ------------------------------------------------------------------

    @Test
    fun `兼容垫片与ROW_SCROLL加三逐字节同形`() {
        val chips = line(1, 2, 3, 4, 5, 6, 7) + line(100, 101, serial = 20L)
        assertEquals(
            railLayoutOf(chips, UiPrefs.RailLayoutMode.ROW_SCROLL, 3),
            railLayoutOf(chips),
        )
    }

    // ------------------------------------------------------------------
    // ROW_SCROLL（缺省）
    // ------------------------------------------------------------------

    @Test
    fun `行内横滑 一条游戏行一行 超枚数才横滑`() {
        val chips = line(1, 2, 3, 4, 5, 6, 7)
        val rows = railLayoutOf(chips, UiPrefs.RailLayoutMode.ROW_SCROLL, 3)
        assertEquals(1, rows.size)
        assertEquals(7, rows.single().chips.size)
        assertTrue("超过 3 枚要横滑", rows.single().scrollable)
        assertTrue(rows.single().stretch)

        val three = railLayoutOf(line(1, 2, 3), UiPrefs.RailLayoutMode.ROW_SCROLL, 3)
        assertFalse(three.single().scrollable)
    }

    @Test
    fun `行内横滑 每行枚数旋钮调到 5 五枚不滑第六枚滑`() {
        val five = railLayoutOf(line(1, 2, 3, 4, 5), UiPrefs.RailLayoutMode.ROW_SCROLL, 5)
        assertFalse(five.single().scrollable)
        val six = railLayoutOf(line(1, 2, 3, 4, 5, 6), UiPrefs.RailLayoutMode.ROW_SCROLL, 5)
        assertTrue(six.single().scrollable)
    }

    // ------------------------------------------------------------------
    // COLUMN 竖列：折行不横滑、行内均分
    // ------------------------------------------------------------------

    @Test
    fun `竖列 同一游戏行按每行枚数折行 均分不横滑`() {
        val rows = railLayoutOf(line(1, 2, 3, 4, 5, 6, 7), UiPrefs.RailLayoutMode.COLUMN, 3)
        assertEquals(listOf(3, 3, 1), rows.map { it.chips.size })
        assertTrue(rows.all { it.stretch })
        assertTrue(rows.all { !it.scrollable })
        assertEquals((1L..7L).toList(), rows.flatMap { it.chips }.map { it.value })
    }

    @Test
    fun `竖列与展开都保留游戏行分组 新行从新的一行起`() {
        val chips = line(1, 2, 3, 4, serial = 10L) + line(5, 6, serial = 20L)
        listOf(UiPrefs.RailLayoutMode.COLUMN, UiPrefs.RailLayoutMode.EXPANDED).forEach { mode ->
            val rows = railLayoutOf(chips, mode, 3)
            assertEquals("$mode 档：4+2 在每行 3 下折成 3/1/2 三行", listOf(3, 1, 2), rows.map { it.chips.size })
            assertEquals(listOf(10L, 10L, 20L), rows.map { it.chips.first().lineSerial })
        }
    }

    @Test
    fun `竖列每行一枚就是全宽一列`() {
        val rows = railLayoutOf(line(1, 2, 3), UiPrefs.RailLayoutMode.COLUMN, 1)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.chips.size == 1 && it.stretch && !it.scrollable })
    }

    // ------------------------------------------------------------------
    // EXPANDED 完全展开：折行 + 定宽格子
    // ------------------------------------------------------------------

    @Test
    fun `完全展开 同竖列折行但格子定宽`() {
        val rows = railLayoutOf(line(1, 2, 3, 4, 5, 6, 7), UiPrefs.RailLayoutMode.EXPANDED, 3)
        assertEquals(listOf(3, 3, 1), rows.map { it.chips.size })
        assertTrue(rows.all { !it.stretch })
        assertTrue(rows.all { !it.scrollable })
        assertEquals((1L..7L).toList(), rows.flatMap { it.chips }.map { it.value })
    }

    @Test
    fun `完全展开 每行枚数等于 1 到 6 全部成立`() {
        val chips = line(1, 2, 3, 4, 5, 6, 7)
        for (perRow in UiPrefs.RAIL_CHIPS_PER_ROW_MIN..UiPrefs.RAIL_CHIPS_PER_ROW_MAX) {
            val rows = railLayoutOf(chips, UiPrefs.RailLayoutMode.EXPANDED, perRow)
            assertEquals(
                "每行 $perRow 枚：7 枚应折成 ⌈7/perRow⌉ 行",
                (7 + perRow - 1) / perRow,
                rows.size,
            )
            assertTrue(rows.all { !it.stretch && !it.scrollable })
            assertEquals((1L..7L).toList(), rows.flatMap { it.chips }.map { it.value })
        }
    }

    // ------------------------------------------------------------------
    // 调色板：三档都一样
    // ------------------------------------------------------------------

    @Test
    fun `调色板密集行三档都不变`() {
        val chips = line(1, 2) + (0 until 12).map { swatch(80000L + it) } + line(3)
        listOf(
            UiPrefs.RailLayoutMode.ROW_SCROLL,
            UiPrefs.RailLayoutMode.COLUMN,
            UiPrefs.RailLayoutMode.EXPANDED,
        ).forEach { mode ->
            val rows = railLayoutOf(chips, mode, 3)
            assertEquals(
                "$mode 档：普通段与调色板 run 的交错形状不该变",
                listOf(null, 8, 8, null),
                rows.map { it.paletteColumns },
            )
            assertEquals(listOf(2, 8, 4, 1), rows.map { it.chips.size })
        }
    }

    @Test
    fun `空输入三档都返回空表`() {
        listOf(
            UiPrefs.RailLayoutMode.ROW_SCROLL,
            UiPrefs.RailLayoutMode.COLUMN,
            UiPrefs.RailLayoutMode.EXPANDED,
        ).forEach { mode ->
            assertEquals(emptyList<RailLayoutRow>(), railLayoutOf(emptyList(), mode, 3))
        }
    }

    @Test
    fun `每行枚数越界夹到 1`() {
        val rows = railLayoutOf(line(1, 2, 3), UiPrefs.RailLayoutMode.COLUMN, 0)
        assertEquals(3, rows.size)
    }
}
