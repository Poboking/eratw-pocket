package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * C2 修复批（stab-c）：close 路径的两条时序判据。
 *
 * 1. **有界 join**：`GameHost.close()` 在 interrupt 后对 VM 线程做 [GameHost.VM_EXIT_JOIN_MS]
 *    上限的 join（[GameHost.awaitVmExitForClose]，companion 纯函数缝 —— app 的 JVM 单测面
 *    实例化不了 `GameHost`（Android 壳），与 `consoleTailOf` 同款口径）。点查场景 VM 在
 *    上限内走到 65536 步检查点收口（`Vm.kt` 关闭闸），join 成功 = UI close 时 VM 已死，
 *    SQL 并发面消灭；长链场景不越过上限（不回归「无界等 130s」，GameHost.kt 关闭协议
 *    既有裁定）。假 VM 线程模拟两型：检查点响应中断（收尾有毫秒级退出尾巴）与
 *    「纯计算不吃中断」的合法长链。
 *
 * 2. **flush 后台化**：close 路径的 `flushDiagnosticSummary` 改「读数面同步、写面异步」
 *    （快照在调用线程拍好，落盘交一次性非 daemon 线程）。装配面哨兵（源码形状 grep，
 *    `DiagnosticEventLogTest` 四黑洞同款口径）：同步直呼落盘的旧形状必须消失。
 *
 * 反向证明：把 join 段拆掉（不 join）⇒ [点查场景_VM线程在上限内收口_close确认退出] 红
 * （收尾尾巴未跑完线程还活着，join 结果翻转）；把上限拆掉（join 无界）⇒
 * [长链场景_不越过上限_超时撒手] 红（等到链尾，耗时越过界断言）；把 flush 拉回同步
 * 直呼 ⇒ 接线哨兵红。
 */
class GameHostCloseTimingTest {

    @Test
    fun `点查场景_VM线程在上限内收口_close确认退出`() {
        val vm = startFakeVm(exitsOnCheckpoint = true)
        vm.start()
        Thread.sleep(WARMUP_MS)
        // close 顺序前半：interrupt（GameHost.close 同款），随后 join 确认上限内退出。
        vm.interrupt()
        val startedAt = System.nanoTime()
        val exited = GameHost.awaitVmExitForClose(vm, GameHost.VM_EXIT_JOIN_MS)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("点查场景应在上限内确认 VM 收口", exited)
        assertTrue(
            "join 等待应显著小于上限（收尾尾巴 ${VM_EXIT_TAIL_MS}ms）",
            elapsedMs < GameHost.VM_EXIT_JOIN_MS,
        )
        vm.join(JOIN_BOUND_MS)
        assertFalse(vm.isAlive)
    }

    @Test
    fun `长链场景_不越过上限_超时撒手`() {
        val vm = startFakeVm(exitsOnCheckpoint = false)
        vm.start()
        vm.interrupt()
        val startedAt = System.nanoTime()
        val exited = GameHost.awaitVmExitForClose(vm, GameHost.VM_EXIT_JOIN_MS)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertFalse("合法长链（纯计算不吃中断）不该在几百 ms 内确认收口", exited)
        assertTrue("close 应仍在等 VM 线程（超时撒手 ≠ 杀线程）", vm.isAlive)
        assertTrue(
            "join 必须有界：不得等到长链尾（${LONG_CHAIN_MS}ms）才返回",
            elapsedMs < LONG_CHAIN_MS,
        )
        vm.join(JOIN_BOUND_MS)
        assertFalse(vm.isAlive)
    }

    @Test
    fun `无VM线程_立即确认_selfjoin不成立`() {
        assertTrue("null 线程 = 无可等，立即确认", GameHost.awaitVmExitForClose(null, GameHost.VM_EXIT_JOIN_MS))
        // close 只许 UI 线程调：self-join 形状防御（等自己永远不会收口）。
        assertFalse(
            "self-join 必须立刻判 false，不得空转一个上限",
            GameHost.awaitVmExitForClose(Thread.currentThread(), GameHost.VM_EXIT_JOIN_MS),
        )
    }

    @Test
    fun `close 段接线哨兵_有界join与flush后台化在位`() {
        // 装配面哨兵：桌面跑不了 Activity，但接线代码形状骗不了 grep。
        val host = File("src/main/kotlin/com/eratw/port/android/GameHost.kt").readText()
        val collector = File("src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt").readText()

        assertTrue("close 段有界 join 接线", host.contains("awaitVmExitForClose(thread, VM_EXIT_JOIN_MS)"))
        assertTrue("interrupt 先于 join（检查点协议前半）", host.contains("thread?.interrupt()"))
        assertTrue("flush 读数面同步：快照在调用线程拍好", host.contains("DiagnosticEventLog.summaryLines()"))
        assertTrue(
            "flush 写面异步：走后台化入口（applicationContext 姿态）",
            host.contains("ErrorReportCollector.flushDiagnosticSummaryAsync(view.context.applicationContext"),
        )
        assertFalse(
            "close 路径不许同步落盘（C2 判据：旧同步直呼形状必须消失）",
            host.contains("ErrorReportCollector.flushDiagnosticSummary(view.context"),
        )
        assertTrue(
            "后台线程非 daemon 姿态（不随 JVM 早退被杀；进程退出由 checkpoint 兜）",
            collector.contains("isDaemon = false"),
        )
    }

    // ------------------------------------------------------------------
    // 假 VM 与常量
    // ------------------------------------------------------------------

    /**
     * 假 VM 线程：
     * - `exitsOnCheckpoint = true`：纯计算循环 + 检查点（每 [CHECKPOINT_EVERY_STEPS] 步），
     *   检查点命中中断即收口——与 `Vm.kt` 关闭闸同形；收口带 [VM_EXIT_TAIL_MS] 退出尾巴
     *   （模拟 CodeEE 上浮 + closed 首查 + 线程拆除），保证「不 join 就判退出」必然红。
     * - `exitsOnCheckpoint = false`：合法长链（纯计算不吃中断，[LONG_CHAIN_MS]ms 后自然
     *   链尾），模拟 2.9 亿指令 ≈130s 形态（测试里等比缩小）。
     */
    private fun startFakeVm(exitsOnCheckpoint: Boolean): Thread {
        return Thread({
            var steps = 0L
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LONG_CHAIN_MS)
            while (System.nanoTime() < deadline) {
                steps += 1
                if (steps % CHECKPOINT_EVERY_STEPS == 0L && Thread.currentThread().isInterrupted) {
                    if (exitsOnCheckpoint) {
                        // 收尾尾巴用 nanoTime 旋转（不可被中断位缩短）：检查点用的
                        // isInterrupted 不清位，紧跟的 Thread.sleep 会立刻抛
                        // InterruptedException，尾巴就形同虚设（反向证明失效）。
                        val tailDeadline = System.nanoTime() +
                            TimeUnit.MILLISECONDS.toNanos(VM_EXIT_TAIL_MS)
                        while (System.nanoTime() < tailDeadline) {
                            Thread.yield()
                        }
                        return@Thread
                    }
                    // 长链形态：中断位不吃（纯计算段无让出边界），继续算到链尾。
                }
                try {
                    Thread.sleep(FAKE_STEP_SLICE_MS)
                } catch (e: InterruptedException) {
                    // 阻塞点被叫醒只提前醒一步；中断位要回填（InterruptedException 会清位），
                    // 下一个检查点才看得见 —— 与 ProcessRuntime 阻塞点回填中断位同形。
                    Thread.currentThread().interrupt()
                }
            }
        }, "eratw-vm-fake").apply { isDaemon = true }
    }

    private companion object {
        /** 线程 join 与判活观察的共同上界（毫秒）。 */
        const val JOIN_BOUND_MS = 5_000L

        /** 假 VM 起跑预热（毫秒）。 */
        const val WARMUP_MS = 50L

        /** 检查点命中的退出尾巴（毫秒）：CodeEE 收尾 + 线程拆除的最小可观察时长。 */
        const val VM_EXIT_TAIL_MS = 120L

        /** 假 VM 每步让出时间片（毫秒），让循环可被调度又不烧核。 */
        const val FAKE_STEP_SLICE_MS = 1L

        /** 假 VM 检查点频率（步）。 */
        const val CHECKPOINT_EVERY_STEPS = 4L

        /** 合法长链总时长（毫秒），远大于 [GameHost.VM_EXIT_JOIN_MS]。 */
        const val LONG_CHAIN_MS = 1_500L
    }
}
