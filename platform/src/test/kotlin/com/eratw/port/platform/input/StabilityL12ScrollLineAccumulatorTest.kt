package com.eratw.port.platform.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 稳定性波 L12：`ScrollLineAccumulator` 的**坏位移**边界（NaN / ±Inf）。
 *
 * 判据（可证伪）：`carryPx += fingerDeltaPx` 先发生，之后才 `(carryPx / pixelsPerLine).roundToInt()`。
 * - `fingerDeltaPx = NaN` ⇒ 零头被写成 NaN，`Float.roundToInt()` 对 NaN **抛
 *   IllegalArgumentException** ⇒ 这一拖之后每一段 MOVE 都抛（`MainActivity` 的
 *   `catch (RuntimeException)` 每帧记一条 UI fault），整段拖动滚不动；
 * - `fingerDeltaPx = +Inf` ⇒ 零头写成 +Inf，`Math.round(+Inf)` 饱和成 `Int.MAX_VALUE` ⇒
 *   **每一次**后续换算都付 `Int.MAX_VALUE` 行（视口被反复甩到端点），直到下一次
 *   `ACTION_DOWN` 的 `reset()`。
 *
 * 修复口径：非有限位移整段丢弃（不滚、零头不动）；有限位移的读数逐值不变。
 */
class StabilityL12ScrollLineAccumulatorTest {

    private val lineH = 19f

    @Test
    fun `NaN 位移不抛也不污染零头`() {
        val acc = ScrollLineAccumulator()
        assertEquals(0, acc.add(Float.NaN, lineH))
        assertEquals("坏事件不许改零头", 0f, acc.carry())
        // 同一拖里的下一段正常位移仍照常结账（修复前这里会继续抛）。
        assertEquals(1, acc.add(12f, lineH))
        // 12px 付了 1 行（19px），零头欠下 -7px —— 与既有用例的「付完欠下 +7px」互为镜像。
        assertEquals(-7f, acc.carry())
    }

    @Test
    fun `正无穷位移不许把后续换算钉成 Int 最大值`() {
        val acc = ScrollLineAccumulator()
        assertEquals(0, acc.add(Float.POSITIVE_INFINITY, lineH))
        assertEquals("坏事件不许改零头", 0f, acc.carry())
        assertEquals("9px 不到半行 ⇒ 先欠着", 0, acc.add(9f, lineH))
        assertEquals(1, acc.add(10f, lineH))
    }

    @Test
    fun `负无穷位移同样丢弃`() {
        val acc = ScrollLineAccumulator()
        assertEquals(0, acc.add(Float.NEGATIVE_INFINITY, lineH))
        assertEquals(0f, acc.carry())
        assertEquals(-1, acc.add(-12f, lineH))
    }

    @Test
    fun `有限位移的读数与修复前逐值相同`() {
        // 与既有 ScrollLineAccumulatorTest 同一组读数：120px / 19px ⇒ 6 行、零头 6px。
        val acc = ScrollLineAccumulator()
        var total = 0
        repeat(20) { total += acc.add(6f, lineH) }
        assertEquals(6, total)
        assertEquals(120f - 6 * lineH, acc.carry())
    }
}
