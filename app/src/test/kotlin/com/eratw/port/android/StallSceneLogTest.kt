package com.eratw.port.android

import com.eratw.port.render.canvas.CanvasConsoleView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SlowUiGate] 的纯判式测试（JVM 单测，不碰线程与时钟；口径同 [StallSentinelTest]）。
 *
 * 阈值语义是「**超过**才钉」：300 ms 的段/帧不钉，301 ms 才钉 —— 边界行为在这里钉死，
 * 防止将来有人把比较符改回 `>=` 后把贴边抖动灌满 64 行轨迹。
 */
class SlowUiGateTest {

    @Test
    fun `段阈值 - 超过才钉`() {
        assertFalse(SlowUiGate.isSlowStage(1L))
        assertFalse(SlowUiGate.isSlowStage(SlowUiGate.STAGE_SLOW_MS))
        assertTrue(SlowUiGate.isSlowStage(SlowUiGate.STAGE_SLOW_MS + 1L))
    }

    @Test
    fun `帧阈值 - 超过才钉`() {
        assertFalse(SlowUiGate.isSlowFrame(16L))
        assertFalse(SlowUiGate.isSlowFrame(SlowUiGate.FRAME_SLOW_MS))
        assertTrue(SlowUiGate.isSlowFrame(SlowUiGate.FRAME_SLOW_MS + 1L))
    }

    @Test
    fun `冷却 - 首钉必走 - 间隔不足不钉 - 到点再钉`() {
        assertTrue(SlowUiGate.shouldPin(nowMs = 10_000L, lastPinAtMs = -1L))
        assertFalse(SlowUiGate.shouldPin(10_000L, lastPinAtMs = 9_500L))
        assertTrue(SlowUiGate.shouldPin(10_000L, lastPinAtMs = 9_000L))
    }

    @Test
    fun `阶段文本 - 两路都带 slowui 前缀与毫秒读数`() {
        val stage = SlowUiGate.stagePhase("选项刷新", 4_521L)
        val frame = SlowUiGate.framePhase(4_521L)
        assertTrue(stage.startsWith(SlowUiGate.TRAIL_PHASE))
        assertTrue(stage.contains("stage=选项刷新"))
        assertTrue(stage.contains("ms=4521"))
        assertTrue(frame.startsWith(SlowUiGate.TRAIL_PHASE))
        assertTrue(frame.contains("onDraw"))
        assertNotEquals(stage, frame)
    }

    /** 帧阈值与 render-canvas 的常量必须是同一来源（单一来源纪律）。 */
    @Test
    fun `帧阈值与 CanvasConsoleView 同一常量源`() {
        assertEquals(CanvasConsoleView.SLOW_FRAME_NOTIFY_MS, SlowUiGate.FRAME_SLOW_MS)
    }
}

/**
 * [StallSceneLog] 的落盘/认主/轮换测试（JVM 临时目录；pid 与墙钟全部注入）。
 *
 * 覆盖三条报告侧判据：
 * 1. 写 → 读回正文逐字一致（[StallScene.lines] 的形状不许被落盘破坏）；
 * 2. **pid 认主**：本进程自己写的不算「上一进程遗留」（局中手动分享报告的形状）；
 * 3. **轮换**：别的进程写的新现场被本进程哨兵覆盖之前，先进 prev（两连报告不重复贴同一份）。
 */
class StallSceneLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 一份现场：两条栈各 2 帧，正文行数可控。 */
    private fun scene(uiAge: Long = 1618L): StallScene = StallScene(
        atMs = 34_630L,
        uiAgeMs = uiAge,
        vmAgeMs = 4_583L,
        verdictKey = "ui-stalled",
        verdictText = "UI 帧循环超期",
        uiThreadName = "main",
        vmThreadName = "eratw-vm",
        uiFrames = listOf("StringFactory.newStringFromBytes:-2", "Foo.bar:42"),
        vmFrames = listOf("GameHost.run:88", "Looper.loop:1"),
    )

    @Test
    fun `写读回 - 正文与判读逐字一致`() {
        val dir = tmp.newFolder()
        assertTrue(StallSceneLog.write(dir, scene(), wallClockMs = 1_700_000_000_000L, pid = 100))

        val previous = StallSceneLog.readPrevious(dir, currentPid = 200)
        assertTrue(previous != null)
        assertEquals(100, previous!!.pid)
        assertEquals(1_700_000_000_000L, previous.wallClockMs)
        // 正文 = 哨兵现场的 lines() 原样（首行 pid/wall 是日志头，不进正文）。
        assertEquals(scene().lines(), previous.reportLines)
        // 报告侧拿到的是「标题 + 正文」整节。
        val lines = StallSceneLog.reportLines(dir, currentPid = 200)
        assertEquals(StallSceneLog.SCENE_FROM_DISK_HEADER, lines.first())
        assertEquals(scene().lines(), lines.drop(1))
    }

    @Test
    fun `pid 认主 - 本进程写的不算遗留`() {
        val dir = tmp.newFolder()
        assertTrue(StallSceneLog.write(dir, scene(), pid = 100))
        // 同一进程来读：跳过（局中手动分享报告时，自己抓的现场走进程内槽位，不走这一节）。
        assertTrue(StallSceneLog.readPrevious(dir, currentPid = 100) == null)
        assertTrue(StallSceneLog.reportLines(dir, currentPid = 100).single() == StallSceneLog.NO_SNAPSHOT)
    }

    @Test
    fun `轮换 - 别人的现场进 prev 后不被本进程覆盖丢掉 - 读侧最新优先`() {
        val dir = tmp.newFolder()
        assertTrue(StallSceneLog.write(dir, scene(), wallClockMs = 1L, pid = 100))
        // 进程 200 读到进程 100 的遗留（最新现场优先），读侧顺手把它轮换进 prev。
        val first = StallSceneLog.readPrevious(dir, currentPid = 200)
        assertTrue(first != null)
        assertEquals(100, first!!.pid)
        assertEquals(1L, first.wallClockMs)
        // 轮换生效：current 已空，进程 200 自己写新现场不会把 100 的那份盖掉。
        assertTrue(StallSceneLog.write(dir, scene(uiAge = 2_000L), wallClockMs = 2L, pid = 200))
        // 进程 300 来读：当前文件（200，最新）优先 —— 报告贴的是**最近一次**冻结现场。
        val latest = StallSceneLog.readPrevious(dir, currentPid = 300)
        assertTrue(latest != null)
        assertEquals(200, latest!!.pid)
        assertEquals(2L, latest.wallClockMs)
    }

    @Test
    fun `空目录与坏文件 - 如实回到无留痕`() {
        val dir = tmp.newFolder()
        assertTrue(StallSceneLog.reportLines(dir, currentPid = 1).single() == StallSceneLog.NO_SNAPSHOT)
        // 坏文件（首行没有 pid=）也按无留痕处理，不抛。
        dir.resolve(StallSceneLog.FILE_NAME).writeText("garbage\nlines")
        assertTrue(StallSceneLog.reportLines(dir, currentPid = 1).single() == StallSceneLog.NO_SNAPSHOT)
        // 坏文件不轮换、不被读侧消费：下一次写入仍能正常落盘。
        assertTrue(StallSceneLog.write(dir, scene(), pid = 9))
        assertTrue(StallSceneLog.readPrevious(dir, currentPid = 1) != null)
    }
}
