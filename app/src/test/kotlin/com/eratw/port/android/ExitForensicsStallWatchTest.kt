package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主线程冻结看门狗的判式（lane `bug-silent` 第二件）。
 *
 * ## 钉的是什么
 *
 * `[主线程冻结现场]` 小节在端点 69 份报告里覆盖 0 —— 哨兵抓到过栈（轨迹里有
 * `stallstack all-stalled uiFrozen=1787ms`），但现场只活在进程内存里、进程一死就没了。
 * 本类钉住「谁在什么时候决定该留一次痕」这一半（另一半是 `LastBreathTest` 的落盘/成文）：
 *
 * 1. **阈值**：[LastBreath.STALL_THRESHOLD_MS]（2000 ms）是硬线，1999 不抓、2000 抓；
 * 2. **一次冻结只抓一次**：冻结恢复后连着的正常帧不再抓；
 * 3. **恢复后重新武装**：清了武装位之后的下一次冻结照抓；
 * 4. **冷却**：30 s 内不允许连发（防「一帧一卡」把心跳刷满）；
 * 5. **抓栈自己出事不许把判式带崩**（注入抛异常的 capture）。
 *
 * 时钟是注入的（`onUiFrame(nowMs)`），所以三条判据在 JVM 上完全确定 —— 与
 * `HeartbeatJudgment` / `StallJudgment` 的抽法同源。断言签名随 app 模块用 JUnit4（消息在前）。
 */
class ExitForensicsStallWatchTest {

    @Test
    fun `阈值是2000毫秒且边界只差一毫秒`() {
        assertEquals("阈值是写死的 2000ms（KDoc 里给了与 ANR 5s 的关系）", 2_000L, LastBreath.STALL_THRESHOLD_MS)
        val watch = StallWatch(capture = { listOf("ui#0 frame") })
        assertNull("第一帧只立基准", watch.onUiFrame(1_000L))
        assertNull("1999ms 不算冻结", watch.onUiFrame(2_999L))
        assertNotNull("2000ms 整就算冻结", watch.onUiFrame(4_999L))
        assertEquals(1, watch.captures)
    }

    @Test
    fun `一次冻结只抓一次且恢复后的正常帧不再抓`() {
        var calls = 0
        val watch = StallWatch(capture = { calls += 1; listOf("ui#0 stalled") })
        watch.onUiFrame(0L)
        val episode = watch.onUiFrame(3_000L)
        assertNotNull("3 秒没动 = 抓一次", episode)
        assertEquals(3_000L, requireNotNull(episode).gapMs)
        assertEquals(1, calls)

        // 恢复：紧接着的正常帧（16ms 一跳）
        assertNull(watch.onUiFrame(3_016L))
        assertNull(watch.onUiFrame(3_032L))
        assertNull(watch.onUiFrame(3_048L))
        assertEquals("一次冻结只抓一次栈", 1, calls)
        assertEquals(1, watch.captures)
    }

    @Test
    fun `恢复后重新武装下一次冻结照抓`() {
        var calls = 0
        val watch = StallWatch(capture = { calls += 1; emptyList() })
        watch.onUiFrame(0L)
        assertNotNull(watch.onUiFrame(2_500L))
        assertNull("恢复", watch.onUiFrame(2_516L))
        // 冷却（30 s）过后再一次冻结
        assertNotNull("冷却过后必须重新抓", watch.onUiFrame(40_000L))
        assertEquals(2, calls)
    }

    @Test
    fun `冷却窗口内不连发而冷却过后照抓`() {
        var calls = 0
        val watch = StallWatch(capture = { calls += 1; emptyList() })
        watch.onUiFrame(0L)
        assertNotNull("第一次冻结", watch.onUiFrame(2_500L))
        assertNull("恢复帧清武装位", watch.onUiFrame(2_516L))
        // 冷却内（距上次抓取只过了 2 520ms）又断 2.5s：一次都不许抓
        assertNull("冷却内不许连发", watch.onUiFrame(5_020L))
        assertNull("恢复帧", watch.onUiFrame(5_036L))
        assertNull("还在冷却内", watch.onUiFrame(7_540L))
        assertNull("恢复帧", watch.onUiFrame(7_556L))
        assertEquals("冷却窗口内只许一次", 1, calls)
        // 冷却边界（距上一次抓取 30 000ms）之后可再抓
        assertNotNull("冷却过后必须重新抓", watch.onUiFrame(32_560L))
        assertEquals(2, calls)
    }

    @Test
    fun `抓栈自己抛异常时判式不崩且仍记一次`() {
        val watch = StallWatch(capture = { throw IllegalStateException("getStackTrace 出事") })
        watch.onUiFrame(0L)
        val episode = watch.onUiFrame(2_400L)
        assertNotNull("抓栈失败也要留下「这一次冻结」的读数", episode)
        assertEquals(2_400L, requireNotNull(episode).gapMs)
        assertTrue("帧表可以为空（不编栈）", requireNotNull(episode).frames.isEmpty())
        assertEquals(1, watch.captures)
    }

    @Test
    fun `reset 之后重新立基准而不是立刻报冻结`() {
        val watch = StallWatch(capture = { listOf("ui#0") })
        watch.onUiFrame(1_000L)
        watch.reset()
        assertNull("reset 后的第一帧只立基准", watch.onUiFrame(90_000L))
        assertNotNull(watch.onUiFrame(92_500L))
    }
}
