package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [RestartWaiterGate] 的定向单测 —— 换局 waiter 的单飞闸（挖虫 W5-misc 第 1 条）。
 *
 * 钉的是屏上看不见的形状：`MainActivity.restartGame` 连点两次 ⇒ 两条 `eratw-restart`
 * 等同一个旧会话 ⇒ 两条都判定「它退了」⇒ `afterStop()` 跑两遍 ⇒ 两份 `GameHost` 同住一个堆，
 * 而 `host` 字段只留最后一份，前一份脱离字段没人 `close()`。
 *
 * 判据（基准 = 现树 [MainActivity.restartGame] 的两道闸调用点）：
 * 1. 同一个 `old` 第二次认领必须失败（这就是被忽略的那一次）；
 * 2. 不同的 `old` 各自可认领（两局本就各有一条推进线程，不能互相挡）；
 * 3. 放开后同一 `old` 可再认领，重复放开无害（`onDestroy` 与异常分支都可能再放一次）；
 * 4. 多线程同时抢同一个 `old`，恰有一个成功（真机触发来自按钮点击与 Activity 结果回调，
 *    不保证只落在同一次消息循环里）；
 * 5. [RestartWaiterGate.hasInFlight] 在「名额已认领、交付还没放开」的窗口里为 true ——
 *    这一条对应 restartGame 里 `old == null` 的那道闸：旧会话已停、投递还在主线程队列里。
 *
 * 为什么是 JVM 单测：本类不碰 Android 运行期（一把锁 + 一个身份集合），与
 * `SessionHandoffTest`、`ShellUiFailureActionsTest` 同一先例；Activity 侧的接线（认领点、
 * 放开点）由 `:app:compileDebugKotlin` 编译期钉类型，行为形状由上面五条钉。
 */
class RestartWaiterGateTest {

    /** 只用于占名额的假「旧会话」：不覆写 equals，身份即相等语义（与 GameHost 同口径）。 */
    private class FakeHost

    /** 覆写了 equals/hashCode 的一对：用来验闸门**不**按值合并。 */
    private class TwinHost(val id: Int) {
        override fun equals(other: Any?): Boolean = other is TwinHost && other.id == id
        override fun hashCode(): Int = id
    }

    @Test
    fun `同一个旧会话只有第一条 waiter 能认领`() {
        val gate = RestartWaiterGate()
        val old = FakeHost()
        assertTrue("第一条 waiter 必须认领成功", gate.begin(old))
        assertFalse("第二条撞同一个 old 必须被挡（否则双 afterStop ⇒ 双宿主）", gate.begin(old))
        assertTrue(gate.isInFlight(old))
        assertEquals(1, gate.inFlightCount())
    }

    @Test
    fun `不同旧会话各自认领互不挡`() {
        val gate = RestartWaiterGate()
        val first = FakeHost()
        val second = FakeHost()
        assertTrue(gate.begin(first))
        assertTrue("两局是两条推进线程，第二条不该被第一条挡死", gate.begin(second))
        assertEquals(2, gate.inFlightCount())
    }

    @Test
    fun `按身份不按值相等合并`() {
        val gate = RestartWaiterGate()
        val a = TwinHost(1)
        val b = TwinHost(1)
        assertEquals(a, b)
        assertTrue(gate.begin(a))
        assertTrue("值相等但不同实例 = 两个宿主 ⇒ 各留一个名额，闸门只认引用身份", gate.begin(b))
        assertFalse(gate.begin(a))
    }

    @Test
    fun `放开后可再认领且重复放开无害`() {
        val gate = RestartWaiterGate()
        val old = FakeHost()
        assertTrue(gate.begin(old))
        gate.release(old)
        assertFalse(gate.isInFlight(old))
        assertFalse(gate.hasInFlight())
        gate.release(old)
        assertTrue("交付过后同一形状的重启是合法的一路（玩家按重试）", gate.begin(old))
    }

    @Test
    fun `并发抢同一个 old 恰有一个成功`() {
        val gate = RestartWaiterGate()
        val old = FakeHost()
        val threads = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        val winners = AtomicInteger(0)
        repeat(threads) {
            Thread {
                try {
                    start.await()
                    if (gate.begin(old)) winners.incrementAndGet()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue("并发认领没跑完", done.await(10, TimeUnit.SECONDS))
        assertEquals("双 waiter 的根因就是认领不是单飞", 1, winners.get())
        assertEquals(1, gate.inFlightCount())
    }

    @Test
    fun `交付还在队列里时在途标记为真`() {
        val gate = RestartWaiterGate()
        val old = FakeHost()
        assertFalse("首启（没有旧会话）时这道闸必须放行", gate.hasInFlight())
        assertTrue(gate.begin(old))
        // waiter 已把旧会话等到退出、投递也进了主线程队列，但投递体还没跑 ⇒ 名额仍被占住。
        // restartGame 的 `old == null` 分支靠这一条拒绝第二路（否则与随后的投递叠成双宿主）。
        assertTrue(gate.hasInFlight())
        gate.release(old)
        assertFalse(gate.hasInFlight())
    }
}
