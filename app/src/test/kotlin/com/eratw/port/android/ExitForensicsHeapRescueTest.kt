package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台堆顶自救的判式与「恰好一次」（lane `bug-silent` 第三件；检查点口径由
 * lane `bug-autosave` 在 2026-11-10 更新为真通道）。
 *
 * ## 钉的是什么
 *
 * 既有的两档水位自救（`RunTrail.RELEASE_AT_PERCENT` 80% / `RELEASE_UNUSED_AT_PERCENT` 92%）
 * 全挂在**前台**回合边界与页中检查点上；而后台脚本线程冻结在安全点 ⇒ 那两条路再也不来，
 * 贴顶的堆在后台一次都不会被人管（端点 10 份「无签名」里 Java 堆贴顶占 5 份）。
 *
 * 本类钉住补上这一档之后的四条可判据面：
 *
 * 1. **90% 水位 + 后台才动手**：注入 90% / 89% / 前台三种读数，只有第一种调动作；
 * 2. **恰好一次**：一次命中里「逐出」与「检查点」各调一次（不是两次、也不是零次）；
 * 3. **顺序**：逐出先于检查点登记（存档那一笔要一次性分出对象图 + payload + 压缩副本，
 *    先收后存才分得出来）；
 * 4. **检查点结局如实上账面**：`ok` / `fail` / `deferred` / `未接线` 四档不串味，
 *    且动作自己抛异常不许把游戏带崩。
 *
 * 真通道全部是 `GameHost` 既有 API（`releaseResourceImagePixels` /
 * `releaseUnusedGraphics(usePixelBudget = true)` / `trimGraphicsCaches(deep = true)`），
 * 本类只钉判式与调用次数，不重造第二套逐出实现；检查点的实写路径由
 * [SaveCheckpointRunnerTest] 与 `SaveCheckpointWiringTest` 各自钉一半。
 *
 * 纯 JVM（无 Android import），断言签名随 app 模块用 JUnit4（消息在前）。
 */
class ExitForensicsHeapRescueTest {

    /** 计数器 + 可注入的返回值/异常；三种动作都记「被调了几次」，另记调用顺序。 */
    private class Probe {
        val order = ArrayList<String>()
        var evictCalls = 0
        var bufferCalls = 0
        var trimCalls = 0
        var checkpointCalls = 0
        var evictResult = 12L
        var evictThrows = false
        var checkpointSignal: CheckpointSignal = CheckpointSignal.SAVED
    }

    private fun runner(probe: Probe, cooldownMs: Long = LastBreath.STALL_COOLDOWN_MS) =
        HeapRescueRunner(
            evict = {
                probe.evictCalls += 1
                probe.order += "evict"
                if (probe.evictThrows) throw OutOfMemoryError("回收闸自己炸了")
                probe.evictResult
            },
            recycleBuffers = { probe.bufferCalls += 1; 3 to (9L * 1024L * 1024L) },
            trimNative = { probe.trimCalls += 1; probe.order += "trim" },
            checkpoint = {
                probe.checkpointCalls += 1
                probe.order += "checkpoint"
                probe.checkpointSignal
            },
            cooldownMs = cooldownMs,
        )

    @Test
    fun `触发线是90百分比且89不触发`() {
        assertEquals("触发线写死 90%（出处见 HeapRescueRunner 类头）", 90L, HeapRescueRunner.RESCUE_AT_PERCENT)
        assertFalse("89% 不触发", HeapRescueRunner.shouldRescue(455L, 512L, background = true, sinceLastMs = -1L))
        assertTrue("90% 触发（461/512 = 90.03%）", HeapRescueRunner.shouldRescue(461L, 512L, background = true, sinceLastMs = -1L))
    }

    @Test
    fun `前台不触发`() {
        assertFalse("前台有回合边界那两档兜着，本档不管", HeapRescueRunner.shouldRescue(512L, 512L, background = false, sinceLastMs = -1L))
    }

    @Test
    fun `堆读不到或上限非法一律不触发`() {
        assertFalse("上限 0 不判", HeapRescueRunner.shouldRescue(100L, 0L, background = true, sinceLastMs = -1L))
        assertFalse("已用读不到", HeapRescueRunner.shouldRescue(-1L, 512L, background = true, sinceLastMs = -1L))
    }

    @Test
    fun `注入90水位时逐出与检查点各恰好一次`() {
        val probe = Probe()
        val outcome = runner(probe).maybeRescue(usedMiB = 504L, maxMiB = 512L, background = true, nowMs = 1_000L)
        assertNotNull("90% + 后台必须动手", outcome)
        assertEquals("逐出必须恰好一次", 1, probe.evictCalls)
        assertEquals("预算档必须恰好一次", 1, probe.bufferCalls)
        assertEquals("native 位图交还必须恰好一次", 1, probe.trimCalls)
        assertEquals("存档检查点必须恰好一次", 1, probe.checkpointCalls)
        val body = requireNotNull(outcome)
        assertEquals(12L, body.freedImageMiB)
        assertEquals(3, body.freedBuffers)
        assertEquals(9L, body.freedBufferMiB)
        assertEquals("检查点成功要如实记", CheckpointSignal.SAVED, body.checkpoint)
        assertTrue(body.checkpointWired)
        assertTrue("轨迹行必须带水位与三段账面：${body.trailLine()}", body.trailLine().startsWith("bg.heaptop used=504MiB/512MiB"))
        assertTrue(body.trailLine(), body.trailLine().contains("checkpoint=ok"))
    }

    @Test
    fun `逐出先于检查点登记`() {
        val probe = Probe()
        runner(probe).maybeRescue(usedMiB = 504L, maxMiB = 512L, background = true, nowMs = 1_000L)
        assertEquals("顺序必须是 逐出 → native → 检查点登记", listOf("evict", "trim", "checkpoint"), probe.order)
    }

    @Test
    fun `登记为deferred时轨迹行如实写deferred`() {
        val probe = Probe()
        probe.checkpointSignal = CheckpointSignal.DEFERRED_WAITING
        val outcome = requireNotNull(
            runner(probe).maybeRescue(504L, 512L, background = true, nowMs = 1_000L),
        )
        assertEquals("登记结局原样上账面", CheckpointSignal.DEFERRED_WAITING, outcome.checkpoint)
        assertTrue("接上真通道了就不许记未接线", outcome.checkpointWired)
        assertTrue(outcome.trailLine(), outcome.trailLine().contains("checkpoint=deferred(wait)"))
        assertFalse("deferred 不是成功", outcome.checkpoint == CheckpointSignal.SAVED)
    }

    @Test
    fun `低于90水位一次都不调`() {
        val probe = Probe()
        assertNull(runner(probe).maybeRescue(usedMiB = 455L, maxMiB = 512L, background = true, nowMs = 1_000L))
        assertEquals(0, probe.evictCalls)
        assertEquals(0, probe.bufferCalls)
        assertEquals(0, probe.trimCalls)
        assertEquals(0, probe.checkpointCalls)
    }

    @Test
    fun `同一冷却窗口内只做一次`() {
        val probe = Probe()
        val runner = runner(probe, cooldownMs = 30_000L)
        assertNotNull(runner.maybeRescue(504L, 512L, background = true, nowMs = 1_000L))
        assertNull("冷却内第二次不许再动", runner.maybeRescue(506L, 512L, background = true, nowMs = 5_000L))
        assertEquals("逐出仍然恰好一次", 1, probe.evictCalls)
        assertEquals(1, probe.checkpointCalls)
        assertNotNull("冷却过后可再来一次", runner.maybeRescue(508L, 512L, background = true, nowMs = 31_500L))
        assertEquals(2, probe.evictCalls)
    }

    @Test
    fun `检查点未接线时如实记未接线且逐出照做`() {
        val probe = Probe()
        probe.checkpointSignal = CheckpointSignal.NOT_WIRED
        val outcome = requireNotNull(
            runner(probe).maybeRescue(504L, 512L, background = true, nowMs = 1_000L),
        )
        assertEquals("未接线不许记成功", CheckpointSignal.NOT_WIRED, outcome.checkpoint)
        assertFalse("未接线必须能被报告读出来", outcome.checkpointWired)
        assertEquals("逐出不受检查点有没有接线影响", 1, probe.evictCalls)
        assertTrue(outcome.trailLine(), outcome.trailLine().contains("checkpoint=未接线"))
    }

    @Test
    fun `逐出自己抛异常时不传播且仍给出账面`() {
        val probe = Probe()
        probe.evictThrows = true
        val outcome = requireNotNull(
            runner(probe).maybeRescue(510L, 512L, background = true, nowMs = 1_000L),
        )
        assertEquals("炸掉的那一刀按 0 计，不编数", 0L, outcome.freedImageMiB)
        assertEquals("后面的动作照做（逐出炸不等于整条自救放弃）", 1, probe.bufferCalls)
        assertEquals(1, probe.trimCalls)
    }

    /** 只为一处可读性：把 Outcome 直接返回（JUnit 的 assertEquals 对 Long 重载更明确）。 */
    private fun body(outcome: HeapRescueRunner.Outcome): HeapRescueRunner.Outcome = outcome
}