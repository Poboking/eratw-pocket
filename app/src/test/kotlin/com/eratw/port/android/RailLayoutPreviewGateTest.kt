package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页「右栏布局」预览的**分叉钉子**（2026-11-11 玩家反馈波）。
 *
 * 玩家问「三档到底接进去没有」。接线本身有 `QolGateWireTest` 钉；这一类钉的是另一半：
 * **三档在预览里必须画得出差别**。三档只在某一行的枚数超过每行枚数时才分叉 —— 枚数不超时
 * 横滑不触发、竖列与横滑同形，行满时定宽又与 `weight(1f)` 等分同宽（[railButtonWidth]
 * 的定义就是那个等分结果）。示例候选一旦排得太少，设置页就会出现「选了看不出变化」，
 * 那正是这次被问的形状。所以把示例的枚数排布与三档的行形状一起钉死。
 */
class RailLayoutPreviewGateTest {

    private val samples = listOf(
        "会话" to 31,
        "移动到广场" to 400,
        "购买[酒]" to 10000,
        "攻击" to 7,
        // 裸数字按钮（标签为空，只剩编号）：与设置页实际喂进来的那两枚同形。
        "" to 208,
        "" to 310,
    )

    /** 行形状 = 每行的（枚数，是否横滑，是否拉伸）；三档比的是这个，不是文案。 */
    private fun shape(mode: UiPrefs.RailLayoutMode, perRow: Int): List<Triple<Int, Boolean, Boolean>> =
        railLayoutOf(railPreviewChips(samples), mode, perRow)
            .map { row -> Triple(row.chips.size, row.scrollable, row.stretch) }

    @Test
    fun `示例候选排成四加二两行 第一行必须超过缺省每行枚数`() {
        val chips = railPreviewChips(samples)
        val firstLine = chips.takeWhile { it.lineSerial == chips.first().lineSerial }
        assertEquals("第一条显示行 4 枚", 4, firstLine.size)
        assertEquals("第二条显示行 2 枚", 2, chips.size - firstLine.size)
        assertTrue(
            "示例第一行必须超过缺省每行枚数，否则三档同形（见本类 KDoc）",
            firstLine.size > UiPrefs.DEFAULT_RAIL_CHIPS_PER_ROW,
        )
    }

    @Test
    fun `缺省每行三枚下三档两两不同形`() {
        val perRow = UiPrefs.DEFAULT_RAIL_CHIPS_PER_ROW
        val modes = UiPrefs.RailLayoutMode.entries
        val shapes = modes.associateWith { shape(it, perRow) }
        modes.forEachIndexed { i, a ->
            modes.drop(i + 1).forEach { b ->
                assertNotEquals(
                    "$a 与 $b 的预览同形，玩家选完看不出变化",
                    shapes[a],
                    shapes[b],
                )
            }
        }
    }

    @Test
    fun `三档各自的行形状逐字钉住`() {
        val perRow = UiPrefs.DEFAULT_RAIL_CHIPS_PER_ROW
        // 横滑：一条游戏行一行，4 枚那行超枚数 ⇒ 走横滑（预览里能看见"能滑"这件事本身）。
        assertEquals(
            listOf(Triple(4, true, true), Triple(2, false, true)),
            shape(UiPrefs.RailLayoutMode.ROW_SCROLL, perRow),
        )
        // 竖列：4 枚那行折成 3+1，全程不横滑、行内均分。
        assertEquals(
            listOf(Triple(3, false, true), Triple(1, false, true), Triple(2, false, true)),
            shape(UiPrefs.RailLayoutMode.COLUMN, perRow),
        )
        // 完全展开：与竖列同样折行，但格子定宽不拉伸。
        assertEquals(
            listOf(Triple(3, false, false), Triple(1, false, false), Triple(2, false, false)),
            shape(UiPrefs.RailLayoutMode.EXPANDED, perRow),
        )
    }

    /**
     * 反向证明：把每行枚数抬到示例最长行之上，横滑与竖列必然同形 —— 这就是
     * 「玩家觉得三档没生效」的几何来源。这条用例存在的意义是**不许有人**把示例
     * 候选改成"每行都不超过所选枚数"然后还声称预览有效。
     */
    @Test
    fun `每行枚数大于示例最长行时横滑与竖列同形 这条已知重合必须记在案`() {
        val perRow = samples.size
        assertEquals(
            "枚数不超时，横滑档与竖列档的行形状一模一样",
            shape(UiPrefs.RailLayoutMode.ROW_SCROLL, perRow),
            shape(UiPrefs.RailLayoutMode.COLUMN, perRow),
        )
    }
}
