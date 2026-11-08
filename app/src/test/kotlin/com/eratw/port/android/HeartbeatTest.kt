package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三路心跳（obs-heartbeat 车道）的判式与记账。
 *
 * ## 为什么这些判式必须钉死
 *
 * 玩家报「玩着玩着卡死 / 点屏幕没反应」，2026-11-01 从端点取到的决定性读数只有脚本侧形状
 * （`turn 2ms` → `2342ms` → `15192ms` → `58515ms`，同期 `used=510~511MiB max=512MiB`）。
 * 本波补的是**另外两路**（UI 帧循环 / 渲染发布）的读数，好回答「谁先停」。判式写错的两种后果
 * 都只能装机才发现：
 *
 * - **误报**：玩家正常读屏（脚本停在等待点、屏面无新快照）被判成冻结 ⇒ 轨迹与报告被噪声灌满，
 *   真正临死前那几行被 64 行的滚动窗口挤掉。
 * - **漏报 / 谎报**：读不到却写成一个读数 ⇒ 下一轮排查按假形状改错地方；所以
 *   「未采到」必须有独立文本、且**不参与超期判定**（[HeartbeatJudgment.isStale] 对 null 恒 false）。
 *
 * ## 判据出处（逐条对回实现）
 *
 * ①三路各自独立记账，未打点 = [Heartbeat.NEVER]，快照按 ui/publish/vm 固定序（Heartbeat.kt）。
 * ②阈值边界含等号：`age >= threshold` 判超期；差一毫秒不判。
 * ③缺省阈值的取舍与主车同源：渲染发布与脚本侧都取 [RunTrail.LONG_TURN_MS]（5 s，
 *   `RunWatch.kt` 里「玩家嘴里的卡死」下沿），UI 帧 1 s（Choreographer 16 ms 一跳，
 *   一秒跳不到一次就是画面冻住；不取 500 ms 是为了不把一次大帧/GC 暂停报成冻结）。
 * ④「谁先停」是**三路并读**的组合判读，不是单路超期（[HeartbeatJudgment.verdict] 的决策表）。
 * ⑤钉轨迹只在「绝不是正常态」的形状发生，且带冷却（[HeartbeatJudgment.shouldPinFreeze]）；
 *   读屏态（`VM_NOT_IN_LOOP`）**永不钉**。
 * ⑥报告小节在 `snapshot == null` 时也要成节，写「未采到」，不许留空、不许给读数。
 *
 * 为何是 JVM 单测而非 instrumented：判式与记账都不碰 Android 运行期（时刻一律由调用方传
 * `elapsedRealtime` 口径的 `nowMs`），同 `RunTrailTest` / `ShellUiFailureActionsTest` 的先例。
 */
class HeartbeatTest {

    private val now = 100_000L

    /** 造一份三路快照：`age == null` 表示这一路从未打点（[Heartbeat.NEVER]）。 */
    private fun snap(uiAge: Long?, publishAge: Long?, vmAge: Long?): HeartbeatSnapshot {
        fun reading(channel: HeartbeatChannel, age: Long?) = HeartbeatReading(
            channel,
            lastAtMs = if (age == null) Heartbeat.NEVER else now - age,
            count = if (age == null) 0L else 1L,
            lastAction = if (age == null) "" else "${channel.key}-act",
        )
        return HeartbeatSnapshot(
            listOf(
                reading(HeartbeatChannel.UI_FRAME, uiAge),
                reading(HeartbeatChannel.RENDER_PUBLISH, publishAge),
                reading(HeartbeatChannel.VM, vmAge),
            ),
        )
    }

    // ---- ① 记账实例 ----

    @Test
    fun `三路各自独立记账`() {
        val hb = Heartbeat()
        hb.markUiFrame(1_000L, "四段全成")
        hb.markRenderPublish(1_500L, "rev=42 frames=99")
        hb.markVm(2_000L, "turn 58515ms")
        val s = hb.snapshot()
        val ui = requireNotNull(s.reading(HeartbeatChannel.UI_FRAME))
        assertEquals("UI 帧的末次时刻原样存", 1_000L, ui.lastAtMs)
        assertEquals("末次动作名原样存", "四段全成", ui.lastAction)
        assertEquals(
            "渲染发布读数",
            HeartbeatReading(HeartbeatChannel.RENDER_PUBLISH, 1_500L, 1L, "rev=42 frames=99"),
            s.reading(HeartbeatChannel.RENDER_PUBLISH),
        )
        assertEquals("脚本侧读数", 2_000L, requireNotNull(s.reading(HeartbeatChannel.VM)).lastAtMs)
        assertEquals("快照固定按 ui/publish/vm 排", 3, s.readings.size)
        assertEquals("通道顺序", HeartbeatChannel.VM.key, s.readings[2].channel.key)
    }

    @Test
    fun `未打点的一路是未采到而不是零时刻`() {
        val s = Heartbeat().snapshot()
        assertEquals(
            "从未跳过 = NEVER 哨兵",
            Heartbeat.NEVER,
            requireNotNull(s.reading(HeartbeatChannel.VM)).lastAtMs,
        )
        assertFalse("hasMeasurement 如实说没有", Heartbeat().hasMeasurement(HeartbeatChannel.UI_FRAME))
        assertNull(
            "距今无从算起：判读要拿到 null 而不是 100000ms",
            s.ageMs(HeartbeatChannel.UI_FRAME, now),
        )
    }

    @Test
    fun `累计次数只增且各路互不污染`() {
        val hb = Heartbeat()
        hb.markUiFrame(10L, "a")
        hb.markUiFrame(20L, "b")
        hb.markUiFrame(30L, "c")
        hb.markVm(40L, "turn 2ms")
        val s = hb.snapshot()
        assertEquals("UI 帧累计三次", 3L, requireNotNull(s.reading(HeartbeatChannel.UI_FRAME)).count)
        assertEquals("末次动作名取最后一次", "c", requireNotNull(s.reading(HeartbeatChannel.UI_FRAME)).lastAction)
        assertEquals("脚本侧只有一发", 1L, requireNotNull(s.reading(HeartbeatChannel.VM)).count)
        assertEquals("渲染发布一次都没有", 0L, requireNotNull(s.reading(HeartbeatChannel.RENDER_PUBLISH)).count)
    }

    // ---- ② 超期边界 ----

    @Test
    fun `阈值边界含等号`() {
        assertTrue("age == 阈值判超期", HeartbeatJudgment.isStale(1_000L, 1_000L))
        assertFalse("差一毫秒不算超期", HeartbeatJudgment.isStale(999L, 1_000L))
        assertTrue(HeartbeatJudgment.isStale(58_515L, 5_000L))
    }

    @Test
    fun `从未采到不判超期`() {
        assertNull("NEVER 算不出距今", HeartbeatJudgment.ageMs(Heartbeat.NEVER, now))
        assertFalse("没数据不许谎报成冻结", HeartbeatJudgment.isStale(null, 1_000L))
    }

    @Test
    fun `读数时刻晚于当前时刻时不出现负距今`() {
        // 跨线程拼接快照时 UI 侧读到的时刻可能比调用方的 nowMs 还新（各路各读各的 volatile）；
        // 距今必须夹到 0，否则报告里出现 age=-37ms 这种读不出也解释不了的东西。
        assertEquals(
            "距今夹到 0",
            0L,
            requireNotNull(HeartbeatJudgment.ageMs(now + 37L, now)),
        )
    }

    // ---- ③ 阈值取值与主车同源 ----

    @Test
    fun `缺省阈值钉住与既有判式同值的取舍`() {
        val t = HeartbeatJudgment.DEFAULT_THRESHOLDS
        assertEquals("渲染发布沿用主车的「玩家口中的卡死」下沿", RunTrail.LONG_TURN_MS, t.publishMs)
        assertEquals("脚本侧同一把尺", RunTrail.LONG_TURN_MS, t.vmMs)
        assertEquals("UI 帧循环取 1 秒（Choreographer 16ms 一跳）", 1_000L, t.uiMs)
        assertEquals("钉发冷却与回收冷却同值", RunTrail.RELEASE_COOLDOWN_MS, HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS)
    }

    // ---- ④ 谁先停的组合判读 ----

    @Test
    fun `UI 帧循环超期而脚本在动时判成 UI 先停`() {
        val v = HeartbeatJudgment.verdict(snap(uiAge = 30_000L, publishAge = 100L, vmAge = 50L), now)
        assertEquals(HeartbeatJudgment.Verdict.UI_LOOP_STALLED, v)
    }

    @Test
    fun `三路全超期判成整进程被冻或已死`() {
        val v = HeartbeatJudgment.verdict(snap(uiAge = 30_000L, publishAge = 30_000L, vmAge = 30_000L), now)
        assertEquals(HeartbeatJudgment.Verdict.PROCESS_FROZEN, v)
    }

    @Test
    fun `脚本侧超期而 UI 在动判成不在指令循环里`() {
        // 玩家读屏时回合边界与页中检查点都不来 —— 这一路**不是**「卡死」的结论，
        // 必须与轨迹里的 turn/longturn 并读，所以判式给的措辞是「不在指令循环里」。
        val v = HeartbeatJudgment.verdict(snap(uiAge = 50L, publishAge = 60_000L, vmAge = 60_000L), now)
        assertEquals(HeartbeatJudgment.Verdict.VM_NOT_IN_LOOP, v)
    }

    @Test
    fun `UI 与脚本都在动但没有新快照判成发布超期`() {
        // 58 秒那一页的形状：检查点每 250ms 一发（vm 新鲜）、帧循环在转（ui 新鲜），
        // 水位要到页尾才前进 ⇒ publish 超期。
        val v = HeartbeatJudgment.verdict(snap(uiAge = 50L, publishAge = 20_000L, vmAge = 200L), now)
        assertEquals(HeartbeatJudgment.Verdict.SNAPSHOT_NOT_PUBLISHED, v)
    }

    @Test
    fun `三路都在动判成未见冻结`() {
        assertEquals(
            HeartbeatJudgment.Verdict.HEALTHY,
            HeartbeatJudgment.verdict(snap(uiAge = 16L, publishAge = 1_000L, vmAge = 900L), now),
        )
    }

    @Test
    fun `全无读数与部分缺数各判各的`() {
        assertEquals(
            "三路都没打过点（本进程刚起或整条没接）",
            HeartbeatJudgment.Verdict.NO_DATA,
            HeartbeatJudgment.verdict(snap(null, null, null), now),
        )
        assertEquals(
            "脚本侧还没接线时不许给结论：没接线与接了但停在等待点是两件事",
            HeartbeatJudgment.Verdict.UNREAD,
            HeartbeatJudgment.verdict(snap(uiAge = 16L, publishAge = 20L, vmAge = null), now),
        )
    }

    @Test
    fun `阈值可调时同一读数给出不同判读`() {
        val s = snap(uiAge = 800L, publishAge = 800L, vmAge = 800L)
        assertEquals(
            "缺省阈值下 UI 未超期",
            HeartbeatJudgment.Verdict.HEALTHY,
            HeartbeatJudgment.verdict(s, now),
        )
        assertEquals(
            "把 UI 阈值压到 500ms 就成 UI 先停",
            HeartbeatJudgment.Verdict.UI_LOOP_STALLED,
            HeartbeatJudgment.verdict(s, now, HeartbeatJudgment.Thresholds(uiMs = 500L, publishMs = 5_000L, vmMs = 5_000L)),
        )
    }

    // ---- ⑤ 冻结钉发的节流 ----

    @Test
    fun `冷却未到不钉轨迹`() {
        val abnormal = snap(uiAge = 30_000L, publishAge = 30_000L, vmAge = 50L)
        assertFalse(
            "距上一次才 29999ms：同一次冻结不重复钉（64 行窗口装不下）",
            HeartbeatJudgment.shouldPinFreeze(now, HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS - 1L, abnormal),
        )
        assertTrue(
            "冷却到点就钉",
            HeartbeatJudgment.shouldPinFreeze(now, HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS, abnormal),
        )
    }

    @Test
    fun `首钉不等冷却`() {
        assertTrue(
            "还没钉过（负数）时第一发必走",
            HeartbeatJudgment.shouldPinFreeze(now, -1L, snap(uiAge = 30_000L, publishAge = 30_000L, vmAge = 50L)),
        )
    }

    @Test
    fun `读屏态与正常态都不钉`() {
        assertFalse(
            "玩家在读屏：脚本停在等待点、屏面无新快照 —— 钉一发就是每天几百行噪声",
            HeartbeatJudgment.shouldPinFreeze(now, -1L, snap(uiAge = 50L, publishAge = 120_000L, vmAge = 120_000L)),
        )
        assertFalse(
            "三路都在动不钉",
            HeartbeatJudgment.shouldPinFreeze(now, -1L, snap(uiAge = 16L, publishAge = 40L, vmAge = 30L)),
        )
        assertFalse(
            "没有实例（读不到）不钉，也不谎报",
            HeartbeatJudgment.shouldPinFreeze(now, -1L, null),
        )
    }

    @Test
    fun `长页那一分钟与 UI 先停两种形状都钉`() {
        assertTrue(
            "UI 与脚本都在动、新快照没到屏（58 秒那一页）",
            HeartbeatJudgment.shouldPinFreeze(now, -1L, snap(uiAge = 50L, publishAge = 20_000L, vmAge = 200L)),
        )
        assertTrue(
            "UI 帧循环先停",
            HeartbeatJudgment.shouldPinFreeze(now, -1L, snap(uiAge = 30_000L, publishAge = 100L, vmAge = 50L)),
        )
    }

    // ---- ⑥ 报告小节与轨迹行尾段 ----

    @Test
    fun `报告小节在无实例时逐路写未采到`() {
        val lines = HeartbeatJudgment.freezeLines(now, null)
        assertEquals("表头在第一行", HeartbeatJudgment.SECTION_HEADER, lines[0])
        assertEquals("表头 + 三路 + 判读", 5, lines.size)
        for (channel in HeartbeatJudgment.CHANNELS) {
            val line = lines.first { it.startsWith("${channel.key} ") }
            assertTrue("$channel 那一行要写未采到：$line", line.contains(HeartbeatJudgment.NOT_MEASURED))
            assertFalse("不许给未采到的路编一个 age：$line", line.contains("age="))
        }
        assertTrue("判读行是 no-data", lines[4].startsWith(HeartbeatJudgment.Verdict.NO_DATA.key))
    }

    @Test
    fun `报告小节逐行给出时刻次数与超期标记`() {
        val hb = Heartbeat()
        hb.markUiFrame(now - 120L, "四段全成")
        hb.markRenderPublish(now - 20_000L, "rev=42 frames=99")
        hb.markVm(now - 200L, "turn 58515ms")
        val lines = HeartbeatJudgment.freezeLines(now, hb.snapshot())
        assertEquals(
            "ui 一行含距今/次数/末次动作/阈值判语",
            "ui age=120ms count=1 last=[四段全成] 未超期(阈值1000ms)",
            lines[1],
        )
        assertEquals(
            "publish 超期要把阈值一起写出来",
            "publish age=20000ms count=1 last=[rev=42 frames=99] 超期(阈值5000ms)",
            lines[2],
        )
        assertEquals(
            "vm 未超期",
            "vm age=200ms count=1 last=[turn 58515ms] 未超期(阈值5000ms)",
            lines[3],
        )
        assertEquals(
            "这一份的形状就是收件箱那一分钟：发布超期、两路新鲜",
            "${HeartbeatJudgment.Verdict.SNAPSHOT_NOT_PUBLISHED.key} ${HeartbeatJudgment.Verdict.SNAPSHOT_NOT_PUBLISHED.text}",
            lines[4],
        )
    }

    @Test
    fun `报告里未打点的那一路不编读数`() {
        val lines = HeartbeatJudgment.freezeLines(now, snap(uiAge = 16L, publishAge = 20L, vmAge = null))
        assertTrue("vm 没接线就如实写：${lines[3]}", lines[3].contains(HeartbeatJudgment.NOT_MEASURED))
        assertTrue("判读不做结论：${lines[4]}", lines[4].startsWith(HeartbeatJudgment.Verdict.UNREAD.key))
    }

    @Test
    fun `轨迹行尾段把三路距今压成一段`() {
        assertEquals(
            "未采到的那一路写 -，不写假读数",
            " hb=ui:12000/publish:-/vm:-",
            HeartbeatJudgment.trailSuffix(now, snap(uiAge = 12_000L, publishAge = null, vmAge = null)),
        )
        assertEquals(
            "三路齐全时逐项给出",
            " hb=ui:16/publish:20000/vm:200",
            HeartbeatJudgment.trailSuffix(now, snap(uiAge = 16L, publishAge = 20_000L, vmAge = 200L)),
        )
        assertEquals("没有实例时不往轨迹上添噪声", "", HeartbeatJudgment.trailSuffix(now, null))
    }

    @Test
    fun `心跳段进了轨迹行也不会破坏既有列格式`() {
        // 端点侧脚本按前三列 + 阶段名读轨迹行，hb 段挂在行尾：列序不能被动过。
        val line = RunTrail.formatLine(
            elapsedMs = 9_000L,
            usedMiB = 510L,
            maxMiB = 512L,
            nativeMiB = 88L,
            phase = HeartbeatJudgment.FREEZE_PHASE + " ui-stalled" +
                HeartbeatJudgment.trailSuffix(now, snap(uiAge = 30_000L, publishAge = 100L, vmAge = 50L)),
        )
        assertTrue(
            "整行形状：$line",
            line == "t=9000ms used=510MiB max=512MiB native=88MiB freeze ui-stalled hb=ui:30000/publish:100/vm:50",
        )
    }

    @Test
    fun `通道短名固定不许漂`() {
        // 端点侧的 grep 与这一路轨迹都按 key 找读数；改名等于把历史读数作废。
        assertEquals("ui", HeartbeatChannel.UI_FRAME.key)
        assertEquals("publish", HeartbeatChannel.RENDER_PUBLISH.key)
        assertEquals("vm", HeartbeatChannel.VM.key)
        assertEquals(
            "报告小节的节名是任务书指定的那一行",
            "[线程心跳（末次动作距今）]",
            HeartbeatJudgment.SECTION_HEADER,
        )
    }
}
