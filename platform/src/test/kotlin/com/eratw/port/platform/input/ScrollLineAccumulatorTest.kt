package com.eratw.port.platform.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScrollLineAccumulator] 单测 —— 真机「无法向上滑」里「慢速拖动一格不动」那一半的复现。
 *
 * `GestureDetector.onScroll` 每次只给**相邻两个 MOVE 事件之间**的增量。行高 19px
 * （`ConfigData.cs:67` 的 `LineHeight` 缺省值）下，一次 20 段的慢拖每段约 6px：
 * 逐段四舍五入全部是 0 行（旧写法就是这个结果），而总位移其实有 120px ≈ 6.3 行。
 * 本类把零头攒住，必须把这段路走满。
 */
class ScrollLineAccumulatorTest {

    private val lineH = 19f

    @Test
    fun `慢速拖动零头攒够一行就付一行`() {
        val acc = ScrollLineAccumulator()
        var total = 0
        repeat(20) { total += acc.add(6f, lineH) } // 20 段 × 6px = 120px
        assertEquals("120px / 19px 应当走完 6 行", 6, total)
    }

    @Test
    fun `同一段总位移与切分方式无关`() {
        val many = ScrollLineAccumulator()
        var slow = 0
        repeat(40) { slow += many.add(3f, lineH) } // 120px 切成 40 段
        val fast = ScrollLineAccumulator().add(120f, lineH) // 同样 120px 一次给完
        assertEquals("拖动快慢不该改变走了几行", fast, slow)
        assertEquals(6, slow)
    }

    @Test
    fun `方向随手指走且与 scrollRows 同号`() {
        val acc = ScrollLineAccumulator()
        // 手指上移 12px = 0.63 行 ⇒ 付 -1 行；`scrollRows(-1)` 走 scrollDown = 走向更晚的行。
        assertEquals(-1, acc.add(-12f, lineH))
        // 付完反向欠下 +7px 零头，再来 -2px 仍不到半行 ⇒ 这一帧不动。
        assertEquals(0, acc.add(-2f, lineH))
        // 手指下移同号 ⇒ 付 +1 行；`scrollRows(+1)` 走 scrollUp = 回看更早的行 = 内容跟着手指走。
        assertEquals(1, ScrollLineAccumulator().add(12f, lineH))
    }

    @Test
    fun `零头不越过半行`() {
        val acc = ScrollLineAccumulator()
        repeat(200) { acc.add(1f, lineH) }
        assertTrue("零头不该越攒越多，实到 ${acc.carry()}", kotlin.math.abs(acc.carry()) <= lineH / 2f + 0.5f)
    }

    @Test
    fun `重新按下清空零头`() {
        val acc = ScrollLineAccumulator()
        assertEquals("9px 不到半行 ⇒ 先欠着", 0, acc.add(9f, lineH))
        assertEquals(9f, acc.carry())
        acc.reset()
        assertEquals(0f, acc.carry())
        assertEquals("上一拖的零头不许带过来凑数", 0, acc.add(9f, lineH))
    }

    @Test
    fun `行高在拖动中变化也照常结账`() {
        // 「适应屏幕」会在拖动过程中换字号：行高 19 → 30，零头按当帧行高结算即可。
        val acc = ScrollLineAccumulator()
        assertEquals("8px 不到半行 ⇒ 先欠着", 0, acc.add(8f, 19f))
        assertEquals("欠着 8px + 本次 11px = 19px，按新行高 30 算 0.63 行 ⇒ 付 1 行", 1, acc.add(11f, 30f))
    }

    @Test
    fun `半行临界上下两个方向都算一行`() {
        // 与 TouchGestureController.onUp 同口径（先取绝对值再判方向）：快扫兜底对恰好半行
        // 的总位移上下方向都付 1 行；慢拖攒零头这一路曾是带符号 roundToInt —— Math.round
        // 半数向 +∞ 收，-0.5 行收成 0，同一个临界两条路读数破口（2026-11-04 收口）。
        assertEquals("下移恰好半行 ⇒ 付 1 行", 1, ScrollLineAccumulator().add(9.5f, lineH))
        assertEquals("上移恰好半行同样付 1 行（旧实现收 0）", -1, ScrollLineAccumulator().add(-9.5f, lineH))
        val down = ScrollLineAccumulator()
        assertEquals(1, down.add(9.5f, lineH))
        assertEquals("下移付 1 行后欠 -9.5px 零头", -9.5f, down.carry())
        val up = ScrollLineAccumulator()
        assertEquals(-1, up.add(-9.5f, lineH))
        assertEquals("上移付 1 行后欠 +9.5px 零头", 9.5f, up.carry())
    }

    @Test
    fun `半行零头跨段攒齐也照付`() {
        // 9px 欠着 + 0.5px 攒成恰好半行 ⇒ 这一段付账；与一次给 9.5px 等价（切分无关性在临界也成立）。
        val seg = ScrollLineAccumulator()
        assertEquals("9px 不到半行 ⇒ 先欠着", 0, seg.add(9f, lineH))
        assertEquals("攒到恰好半行 ⇒ 付 1 行", 1, seg.add(0.5f, lineH))
        val neg = ScrollLineAccumulator()
        assertEquals(0, neg.add(-9f, lineH))
        assertEquals(-1, neg.add(-0.5f, lineH))
    }

    @Test
    fun `非法行高直接拒绝`() {
        val acc = ScrollLineAccumulator()
        assertThrows(IllegalArgumentException::class.java) { acc.add(5f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { acc.add(5f, Float.NaN) }
    }
}
