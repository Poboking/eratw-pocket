package com.eratw.port.android

import com.eratw.port.core.host.DaemonFailure
import com.eratw.port.core.host.SingleDaemonSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEEL1/L2：**直接接线**的端到端判据——凡走 [SingleDaemonSlot] 的 daemon 非预期死亡，
 * 不需要任何宿主装配，就能从 core 侧留痕环（`drainCapturedFailures`）到达报告行
 * （[ErrorReportCollector.drainDaemonDeathLines]）。
 *
 * 与 `ErrorReportDaemonDeathTest`（钉 app 侧环本身）和 core-vm 的
 * `SingleDaemonSlotCaptureTest`（钉 core 侧环本身）分工不同：本类钉的是**两层之间的那根线**——
 * ① 真起一条会死的守护线程（非伪造记录），报告行里的线程名/代次/类名/消息/栈顶全部来自
 * 死那一刻的读数；② 取一次即清跨两层成立（app drain 之后 core 环与 app 环都空，第二份报告不重播）；
 * ③ core 环灌满超过 app 环容量时，转移被 app 侧 [ErrorReportCollector.MAX_DAEMON_DEATH_LINES]
 * 闸门收口（满丢最旧，§0 第 10 条：任何新表都要有写死容量）。
 *
 * 环是进程级状态：起手把两层都清一遍（app 侧 drain 会顺带走空 core 环，再显式
 * [SingleDaemonSlot.clearCapturedFailures] 兜底），避免同 JVM 内其它用例串味。
 * 「`!isRunning` 一观察到留痕必已入环」沿用 `SingleDaemonSlotDrainTest` 的同一口径
 * （JVM 在标记线程死亡之前调用 uncaughtExceptionHandler）。
 */
class ErrorReportDaemonWiringTest {

    @Before
    fun clearResidue() {
        ErrorReportCollector.drainDaemonDeathLines()
        SingleDaemonSlot.clearCapturedFailures()
    }

    private fun waitUntil(timeoutMs: Long, what: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        assertTrue("$what：${timeoutMs}ms 内未成立", cond())
    }

    @Test
    fun `槽内线程非预期死亡_不经宿主装配直接出声在报告行`() {
        val slot = SingleDaemonSlot<Any>("eratw-wire-test")
        try {
            assertTrue(slot.start({ Any() }, { throw RuntimeException("轮询崩了") }))
            waitUntil(2_000, "daemon 被 body 异常带死") { !slot.isRunning }

            val lines = ErrorReportCollector.drainDaemonDeathLines()
            assertEquals("1 行小节头 + 1 行留痕", 2, lines.size)
            assertTrue(lines[0], lines[0].startsWith("后台守护线程非预期死亡"))
            val row = lines[1]
            assertTrue(row, row.contains("[eratw-wire-test]"))
            assertTrue(row, row.contains("第1代"))
            assertTrue(row, row.contains("java.lang.RuntimeException：轮询崩了"))
            assertTrue("行尾带栈顶帧：$row", row.contains("@"))

            // 取一次即清要跨两层：core 环与 app 环都已被这一发 drain 带走。
            assertEquals(emptyList<String>(), ErrorReportCollector.drainDaemonDeathLines())
            assertEquals(emptyList<DaemonFailure>(), SingleDaemonSlot.drainCapturedFailures())
        } finally {
            slot.stop()
        }
    }

    @Test
    fun `定长记录渲染_空消息不带悬空冒号`() {
        val withMsg = DaemonFailure(
            threadName = "eratw-x",
            generation = 3,
            errorClass = "java.lang.IllegalStateException",
            message = "消息在此",
            topFrame = "com.Foo.loop(Foo.kt:42)",
        )
        assertEquals(
            "[eratw-x] 第3代 java.lang.IllegalStateException：消息在此 @ com.Foo.loop(Foo.kt:42)",
            ErrorReportCollector.daemonDeathRowText(withMsg),
        )
        val noMsg = DaemonFailure(
            threadName = "eratw-x",
            generation = 1,
            errorClass = "java.lang.IllegalStateException",
            message = "",
            topFrame = "无栈可读",
        )
        val line = ErrorReportCollector.daemonDeathRowText(noMsg)
        assertEquals("[eratw-x] 第1代 java.lang.IllegalStateException @ 无栈可读", line)
        // 类名后紧跟冒号 = 拼 null 的痕迹，读的人会以为消息真是空串。
        assertTrue(line, !line.contains("IllegalStateException："))
    }

    @Test
    fun `core环超过app环容量_转移被app闸收口`() {
        val n = ErrorReportCollector.MAX_DAEMON_DEATH_LINES + 2
        // 逐条起、逐条等死透：core 环（容量 8）里攒 n 条旧在前的记录，全部一次转移。
        (1..n).forEach { i ->
            val slot = SingleDaemonSlot<Any>("eratw-flood-$i")
            assertTrue(slot.start({ Any() }, { throw RuntimeException("洪水$i") }))
            waitUntil(2_000, "eratw-flood-$i 带死") { !slot.isRunning }
        }
        val lines = ErrorReportCollector.drainDaemonDeathLines()
        assertEquals(
            "小节头 + 至多 MAX_DAEMON_DEATH_LINES 行：app 环是第二道容量闸",
            ErrorReportCollector.MAX_DAEMON_DEATH_LINES + 1,
            lines.size,
        )
        val kept = lines.drop(1).joinToString("|")
        assertTrue("最旧的被 app 环挤出：$kept", !kept.contains("洪水1"))
        assertTrue("最新的必须出声：$kept", kept.contains("洪水$n"))
    }
}
