package com.eratw.port.android

import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SaveDataProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 堆顶自救的存档检查点：登记 → 安全点兑现 → 台账（lane `bug-autosave`，2026-11-10）。
 *
 * ## 钉的是哪五类判据（与波次契约逐条对应）
 *
 * 1. **恰一发落在安全点**：登记阶段一次都不写；安全点兑现恰好一笔；没有第二笔请求就不许再写。
 * 2. **无会话不写**：写入器取不到（没有会话 / `systemOps` 不是 `FileSystemOps`）时一笔都不落，
 *    只留 `save=fail:no-session`。
 * 3. **输入等待记 deferred 后兑现**：登记时 VM 停在等待点 ⇒ 台账记「输入等待登记」，
 *    下一个安全点照样兑现（一笔不丢、也不提前写）。
 * 4. **写失败留痕继续**：写入器抛异常或返回 false ⇒ 台账 `save=fail:<class>` / `save=fail:false`，
 *    异常不外溢，且下一笔请求照常可兑现。
 * 5. **既有逐出行为不变（顺序）**：逐出在前、写在后的先后关系由调用顺序钉住。
 *
 * 另钉两条纪律：**单飞**（同一时刻最多一笔在挂，不排队）与**槽位 = 官方自动存档 99**
 * （`Process.cs:136` `const AutoSaveIndex = 99`；`VariableEvaluator.cs:2023-2032` 落成
 * `save99.sav`）。
 *
 * 纯 JVM（无 Android import），断言签名随 app 模块用 JUnit4（消息在前）。
 * 真通道接线（UI 只登记、VM 线程两个安全点兑现）由 `SaveCheckpointWiringTest` 用源码面钉。
 */
class SaveCheckpointRunnerTest {

    /** 写入器替身：记次数、末次槽位与文本、调用顺序。 */
    private class Probe {
        val order = ArrayList<String>()
        var writes = 0
        var lastSlot = -1
        var lastText = ""
        var writer: SaveWriter? = { slot, text ->
            writes += 1
            lastSlot = slot
            lastText = text
            order += "write"
            true
        }
    }

    private fun runner(probe: Probe) = SaveCheckpointRunner(
        writerProvider = { probe.writer },
        textProvider = { "2026/11/10 12:00:00 " },
        beforeWrite = { probe.order += "evict" },
    )

    @Test
    fun `登记阶段不写_安全点恰好写一次`() {
        val probe = Probe()
        val runner = runner(probe)
        assertEquals(
            "VM 正在跑 ⇒ 登记为 deferred（下一个页中检查点兑现）",
            CheckpointSignal.DEFERRED_RUNNING,
            runner.request(vmWaitingInput = false),
        )
        assertEquals("登记阶段一次都不许写", 0, probe.writes)
        assertTrue("登记后必须留下挂起请求", runner.hasPendingRequest)

        val first = requireNotNull(runner.drainAtSafePoint("midpage", 1_000L)) { "有挂起请求就必须兑现" }
        assertTrue(first.line, first.ok)
        assertEquals("安全点上恰好写一笔", 1, probe.writes)
        assertTrue(first.line, first.line.contains("save=ok"))
        assertTrue(first.line, first.line.contains("slot=99"))
        assertFalse("兑现后挂起位必须清掉", runner.hasPendingRequest)

        assertNull("没有第二笔请求就不许再写", runner.drainAtSafePoint("turn", 1_100L))
        assertEquals(1, probe.writes)
    }

    @Test
    fun `无会话时不写且台账记no session`() {
        val probe = Probe()
        probe.writer = null
        val runner = runner(probe)
        runner.request(vmWaitingInput = false)
        val result = requireNotNull(runner.drainAtSafePoint("turn", 2_000L))
        assertFalse("没有会话不许记成功", result.ok)
        assertTrue(result.line, result.line.contains("save=fail:no-session"))
        assertEquals("一笔都没落", 0, probe.writes)
        assertTrue(runner.ledgerLine(), runner.ledgerLine().contains("fail 1"))
    }

    @Test
    fun `输入等待登记deferred并在下一个安全点兑现`() {
        val probe = Probe()
        val runner = runner(probe)
        assertEquals(
            "VM 停在输入等待 ⇒ 登记为 deferred(wait)",
            CheckpointSignal.DEFERRED_WAITING,
            runner.request(vmWaitingInput = true),
        )
        assertEquals("停在等待点时绝不许抢跑写", 0, probe.writes)
        assertTrue(
            "台账必须能读出「这一笔是等待点登记的」：${runner.ledgerLine()}",
            runner.ledgerLine().contains("输入等待登记 1 次"),
        )
        val result = requireNotNull(runner.drainAtSafePoint("turn", 3_000L))
        assertTrue("下一个安全点必须兑现（请求不许丢）", result.ok)
        assertEquals(1, probe.writes)
        assertTrue(runner.ledgerLine(), runner.ledgerLine().contains("save=ok"))
    }

    @Test
    fun `写失败只留痕不杀局且下一笔仍可再试`() {
        val probe = Probe()
        probe.writer = { _, _ -> throw java.io.IOException("disk full") }
        val runner = runner(probe)
        runner.request(vmWaitingInput = false)
        val failed = requireNotNull(runner.drainAtSafePoint("midpage", 4_000L)) { "失败也要有账面" }
        assertFalse(failed.ok)
        assertTrue(
            "失败必须记异常类名（不是编一个 fail）：${failed.line}",
            failed.line.contains("save=fail:java.io.IOException"),
        )

        // 不杀局：进程照常，下一笔请求仍然可兑现。
        probe.writer = { slot, text ->
            probe.writes += 1
            probe.lastSlot = slot
            probe.lastText = text
            true
        }
        runner.request(vmWaitingInput = false)
        val ok = requireNotNull(runner.drainAtSafePoint("turn", 5_000L))
        assertTrue("失败不许把通道永久关死", ok.ok)
        assertEquals(1, probe.writes)
        assertTrue(runner.ledgerLine(), runner.ledgerLine().contains("ok 1 fail 1"))
    }

    @Test
    fun `先逐出后存`() {
        val probe = Probe()
        val runner = runner(probe)
        runner.request(vmWaitingInput = false)
        requireNotNull(runner.drainAtSafePoint("midpage", 6_000L))
        assertEquals("顺序必须是 逐出 → 写", listOf("evict", "write"), probe.order)
    }

    @Test
    fun `单飞同一时刻只挂一笔`() {
        val probe = Probe()
        val runner = runner(probe)
        assertEquals(CheckpointSignal.DEFERRED_RUNNING, runner.request(vmWaitingInput = false))
        assertEquals(
            "已有一笔在挂时第二笔记 ALREADY_PENDING，不排队",
            CheckpointSignal.ALREADY_PENDING,
            runner.request(vmWaitingInput = false),
        )
        assertEquals(0, probe.writes)
        requireNotNull(runner.drainAtSafePoint("midpage", 7_000L))
        assertEquals("两笔请求只写一笔", 1, probe.writes)
        assertTrue(runner.ledgerLine(), runner.ledgerLine().contains("单飞丢弃 1 次"))
    }

    @Test
    fun `槽位是官方自动存档99且标签取当前SAVEDATA_TEXT`() {
        assertEquals(
            "必须复用官方自动存档槽（Process.cs:136 const AutoSaveIndex = 99）",
            99,
            SaveCheckpointRunner.AUTO_SAVE_SLOT,
        )
        val probe = Probe()
        val runner = runner(probe)
        runner.request(vmWaitingInput = false)
        requireNotNull(runner.drainAtSafePoint("midpage", 8_000L))
        assertEquals("写入槽位必须是 99", 99, probe.lastSlot)
        assertEquals("标签文本必须原样交给写入器", "2026/11/10 12:00:00 ", probe.lastText)
    }

    @Test
    fun `写入返回false也算失败留痕`() {
        val probe = Probe()
        probe.writer = { _, _ -> probe.writes += 1; false }
        val runner = runner(probe)
        runner.request(vmWaitingInput = false)
        val result = requireNotNull(runner.drainAtSafePoint("midpage", 9_000L))
        assertFalse(result.ok)
        assertTrue(result.line, result.line.contains("save=fail:false"))
        assertEquals("失败也是写了一次（不许假装没写）", 1, probe.writes)
    }

    @Test
    fun `槽位99经生产代码落成官方档名save99点sav`() {
        // 这条不钉我自己的常量，而是问**生产代码**：官方自动存档槽 99 到底落到哪个文件名。
        // `SaveConfig.normalPath` 就是 `VariableEvaluator.cs:2023-2032` 的移植实现。
        assertEquals("save99.sav", SaveConfig.DEFAULT.normalPath(99))
        assertEquals("eratw 实机开 SAV 文件夹 ⇒ sav/save99.sav", "sav/save99.sav", SaveConfig.ERATW.normalPath(99))
        assertEquals("save99.sav", SaveDataProbe.saveName(99L))
    }
    @Test
    fun `没有挂起请求时安全点一笔都不写`() {
        val probe = Probe()
        val runner = runner(probe)
        assertNull(runner.drainAtSafePoint("midpage", 10_000L))
        assertNull(runner.drainAtSafePoint("turn", 10_001L))
        assertEquals(0, probe.writes)
        assertTrue(runner.ledgerLine(), runner.ledgerLine().contains("请求 0 次"))
    }

    @Test
    fun `写入器取不到时台账末次与计数都不编数`() {
        val probe = Probe()
        probe.writer = null
        val runner = runner(probe)
        assertTrue("从没兑现过时末次必须是「未命中」", runner.ledgerLine().contains("末次=未命中"))
        runner.request(vmWaitingInput = false)
        requireNotNull(runner.drainAtSafePoint("midpage", 11_000L))
        assertTrue(
            "兑现过一次之后末次必须换成真实台账（不许还是未命中）：${runner.ledgerLine()}",
            runner.ledgerLine().contains("末次=save=fail:no-session"),
        )
    }
}