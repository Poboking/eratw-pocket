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
    fun `方向随手指走且符号一致`() {
        val acc = ScrollLineAccumulator()
        // 手指下移（distanceY < 0）= 看更早的行。12px = 0.63 行 ⇒ 付 -1 行。
        assertEquals(-1, acc.add(-12f, lineH))
        // 付完欠下 +7px 零头，再来 -2px 仍不到半行 ⇒ 这一帧不动。
        assertEquals(0, acc.add(-2f, lineH))
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
    fun `非法行高直接拒绝`() {
        val acc = ScrollLineAccumulator()
        assertThrows(IllegalArgumentException::class.java) { acc.add(5f, 0f) }
        assertThrows(IllegalArgumentException::class.java) { acc.add(5f, Float.NaN) }
    }
}
