package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 主线程冻结哨兵（EP1 波 · L4 stall-stack）的判式、线程面与装配面。
 *
 * ## 为什么这些判式必须钉死
 *
 * 端点那批「上次异常退出（无异常对象）」的报告只知道主线程**多久没动**、不知道它**在哪一行**。
 * 本路补的正是后一半，而它的两种失效形态都只能装机才发现：
 *
 * - **误抓**：装载/解包期间、切后台、脚本线程已退出这三种「泵本来就不转」的窗口被当成冻结
 *   ⇒ 每次装机都钉出一屏 `nativePollOnce`，把 64 行滚动轨迹里真正临死前那几行挤掉
 *   （钉法与后果同 [HeartbeatTest] 里「读屏态不钉」那一条）。
 * - **哑抓**：判式或武装位接错，哨兵永远不动手 ⇒ 这条取证线白装，报告里还是只有时刻没有位置。
 *   本文件最后一条用例（[短路成永不抓栈时必红]）与本文件的钉发计数用例就是防这一种，
 *   也是验收口径要求的破坏性验证的落点。
 *
 * ## 判据出处（逐条对回实现）
 *
 * ①尺子复用：动手下限取 [HeartbeatJudgment.thresholdOf] 的 UI 档与 [StallJudgment.CAPTURE_MIN_UI_AGE_MS]
 *   的较大者（只更严不更松）；冷却直接**等于** [HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS]。
 * ②未采到（[Heartbeat.NEVER]）恒不动手（[HeartbeatJudgment.isStale] 对 null 恒 false 的同一条纪律）。
 * ③武装位（[StallSentinel.setUiLoopExpected]）缺省 false ⇒ 没接好之前整路零行为。
 * ④体积：一次冻结 ≤ [StallJudgment.MAX_FRAMES_TOTAL] 条帧文本、轨迹只添**一行**且
 *   ≤ [StallJudgment.MAX_TRAIL_PHASE_CHARS] 字符（4 KB 增量预算的两个来源）。
 * ⑤谁该被钉：主线程恒钉、脚本线程带（取不到写 [StallJudgment.NOT_CAPTURED]，不拿主线程的栈冒充）。
 * ⑥钉发口出事不外抛、且照样进冷却（否则一次落盘失败 = 后面每拍重试抓栈 = 新的卡顿源）。
 * ⑦线程面：daemon、`eratw-stall`、`stop()` 后线程收口；只用 `Thread.getStackTrace()`，
 *   不 suspend、不 interrupt 别人（唯一的 interrupt 对象是它自己）。
 * ⑧装配面（源码面，先例 [StrictModeGuardTest] 的 `codeLines`）：起停随生命周期、
 *   `onDestroy` 必达且排在 `super.onDestroy()` 之前、读数口是 internal、不 import core。
 *
 * 为何是 JVM 单测而非 instrumented：判式与 `tick` 都不碰 Android 运行期（时刻由调用方传，
 * 线程由 lambda 交），真机读数（ART 能不能从卡在 native 解码的主线程拿到 Java 帧）
 * 本机 `adb devices` 为空 ⇒ 写在交付报告 TODO(verify)，不在这里编。
 */
class StallSentinelTest {

    private val now = 200_000L

    /**
     * 造一份「主线程已经 N 毫秒没动」的心跳；`uiAgeMs == null` = 这一路从未打点。
     *
     * 另外两路**照打**（publish 50 ms / vm 30 ms 都在阈值内）：那样 [HeartbeatJudgment.verdict]
     * 给的才是 `ui-stalled`（三路并读的形状，见它那张决策表），与本哨兵要抓的现场一致。
     * 只打 UI 那一路会让判读退化成 `unread`（有一路没接线），验不到我要验的词。
     */
    private fun heartbeat(
        uiAgeMs: Long?,
        vmAgeMs: Long? = 30L,
        publishAgeMs: Long? = 50L,
    ): Pair<Heartbeat, Long> {
        val hb = Heartbeat()
        if (uiAgeMs != null) hb.markUiFrame(now - uiAgeMs, "四段全成")
        if (vmAgeMs != null) hb.markVm(now - vmAgeMs, "turn 2ms")
        if (publishAgeMs != null) hb.markRenderPublish(now - publishAgeMs, "rev=42 frames=99")
        return hb to now
    }

    /** 一次钉发的记录（轨迹那一行 + 留在内存里的现场）。 */
    private class Pin(val phase: String, val scene: StallScene)

    private fun sentinel(
        uiAgeMs: Long?,
        mainThread: () -> Thread? = { Thread.currentThread() },
        vmThread: () -> Thread? = { null },
        pins: MutableList<Pin>,
        throwOnPin: Boolean = false,
        armed: Boolean = true,
    ): StallSentinel {
        val (hb, _) = heartbeat(uiAgeMs)
        return StallSentinel(
            heartbeat = hb,
            clockMs = { now },
            mainThread = mainThread,
            vmThread = vmThread,
            pin = { phase, scene ->
                if (throwOnPin) error("落盘失败（测试用）")
                pins += Pin(phase, scene)
            },
        ).apply { setUiLoopExpected(armed) }
    }

    // ---- ① 尺子与心跳同一把 ----

    @Test
    fun `动手下限不松于心跳的 UI 档且冷却直接复用心跳那一档`() {
        val ui = HeartbeatJudgment.DEFAULT_THRESHOLDS.uiMs
        val t = StallJudgment.captureThresholdMs()
        assertTrue("哨兵只能更严（$t >= $ui）：抓栈比钉一行心跳贵得多", t >= ui)
        assertEquals("缺省取任务书给的 1 500 ms 一档", 1_500L, t)
        val cooldown = HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS
        assertEquals("冷却不是新造的数：与心跳的 freeze 钉发同一档（30 s）", 30_000L, cooldown)
        assertTrue("还没钉过（负数）⇒ 首钉必走", StallJudgment.shouldPin(100_000L, -1L))
        assertFalse("差一毫秒不重复钉", StallJudgment.shouldPin(100_000L, 100_000L - cooldown + 1L))
        assertTrue("冷却到点就放行", StallJudgment.shouldPin(100_000L, 100_000L - cooldown))
        // 心跳把 UI 档抬到 2 s 时哨兵自动跟着收紧（maxOf 的存在理由）。
        assertEquals(
            2_000L,
            StallJudgment.captureThresholdMs(
                HeartbeatJudgment.Thresholds(uiMs = 2_000L, publishMs = 5_000L, vmMs = 5_000L),
            ),
        )
    }

    // ---- ②③ 什么时候动手 ----

    @Test
    fun `边界：一千四百九十九不动手一千五动手`() {
        assertFalse(StallJudgment.shouldCapture(1_499L))
        assertTrue(StallJudgment.shouldCapture(1_500L))
        assertTrue("三万秒级冻结当然动手", StallJudgment.shouldCapture(30_000L))
    }

    @Test
    fun `未采到与未武装都不动手`() {
        assertFalse("没数据不许谎报成冻结（同心跳那一条纪律）", StallJudgment.shouldCapture(null))
        val pins = mutableListOf<Pin>()
        assertNull("未武装（泵本来就不转的三种窗口）整拍作废", sentinel(30_000L, pins = pins, armed = false).tick(now))
        assertTrue(pins.isEmpty())
        val never = sentinel(null, pins = pins)
        assertNull("UI 那一路从未打点 ⇒ 无从判超期", never.tick(now))
        assertTrue(pins.isEmpty())
        val fresh = sentinel(1_000L, pins = pins)
        assertNull("抖动一秒不算冻结", fresh.tick(now))
        assertTrue(pins.isEmpty())
    }

    // ---- 抓栈与钉发 ----

    @Test
    fun `帧计数前进时不动手（墙钟读数落后的那一形）`() {
        // 纯判式三形：没有基线不判前进；计数变大 = 在动；计数不动 = 没在动（不等于冻结，交给距今判）。
        assertFalse(StallJudgment.hasUiProgress(-1L, 5L))
        assertTrue(StallJudgment.hasUiProgress(5L, 6L))
        assertFalse(StallJudgment.hasUiProgress(5L, 5L))
        // 线程面：深睡唤醒那一形 —— 距今早就超阈值、冷却也过了，但循环确实又跳过了一帧。
        var clock = 100_000L
        val hb = Heartbeat()
        hb.markUiFrame(clock - 30_000L, "四段全成")
        hb.markVm(clock - 20L, "turn 2ms")
        hb.markRenderPublish(clock - 40L, "rev=42 frames=99")
        val pins = mutableListOf<Pin>()
        val s = StallSentinel(
            heartbeat = hb,
            clockMs = { clock },
            mainThread = { Thread.currentThread() },
            vmThread = { null },
            pin = { phase, scene -> pins += Pin(phase, scene) },
        ).apply { setUiLoopExpected(true) }
        assertNotNull("首拍没有计数基线 ⇒ 按距今抓栈", s.tick(clock))
        // 同一末次时刻（距今仍 30 秒以上）、计数前进一格、时钟再往前走 31 秒（冷却已过）：
        // 这一拍必须作废，否则就是把「刚画完一帧」的正常位置钉成冻结现场。
        clock += 31_000L
        hb.markUiFrame(100_000L - 30_000L, "四段全成")
        assertNull("计数在前进 ⇒ 不是冻结（这条判式只收紧不放松）", s.tick(clock))
        assertEquals("作废的那一拍不许钉发", 1, pins.size)
        // 对照：计数不再前进（真冻住），冷却也已过 ⇒ 必须再抓一次。
        clock += 1_000L
        assertNotNull("计数停住且冷却已过 ⇒ 该抓就得抓", s.tick(clock))
        assertEquals(2, pins.size)
    }

    @Test
    fun `判定超期就抓主线程栈并钉一行轨迹`() {
        val pins = mutableListOf<Pin>()
        val scene = requireNotNull(sentinel(3_000L, pins = pins).tick(now)) { "判式成立却不抓 = 这条取证线白装" }
        assertEquals("一次冻结只钉一行", 1, pins.size)
        assertTrue("轨迹那一行以阶段名开头：${pins[0].phase}", pins[0].phase.startsWith(StallJudgment.TRAIL_PHASE))
        assertTrue("要把判读带上：${pins[0].phase}", pins[0].phase.contains(HeartbeatJudgment.Verdict.UI_LOOP_STALLED.key))
        assertTrue("要把冻结时长带上：${pins[0].phase}", pins[0].phase.contains("uiFrozen=3000ms"))
        assertTrue("栈顶帧要写进轨迹行：${pins[0].phase}", pins[0].phase.contains("ui@"))
        assertTrue("主线程的栈确实拿到了（本用例的当前线程）", scene.uiFrames.isNotEmpty())
        assertEquals("脚本线程没起来 ⇒ 帧表为空而不是编一帧", emptyList<String>(), scene.vmFrames)
        assertEquals("距今读数原样带出", 3_000L, scene.uiAgeMs)
        val lines = scene.lines()
        assertEquals("节名在第一行", StallJudgment.SCENE_HEADER, lines[0])
        assertTrue("取不到的那一路要如实写未取到", lines.contains(StallJudgment.NOT_CAPTURED))
        assertTrue("小节不许超预算（${lines.size} > ${StallJudgment.SCENE_MAX_LINES}）", lines.size <= StallJudgment.SCENE_MAX_LINES)
    }

    @Test
    fun `现场留在内存里供报告读取`() {
        val s = sentinel(2_000L, pins = mutableListOf())
        assertNull("还没动手之前没有现场（不是空表冒充）", s.lastScene())
        assertTrue(s.sceneLines().isEmpty())
        val scene = s.tick(now)
        assertNotNull(scene)
        assertEquals("读数口拿到的就是那一份", scene, s.lastScene())
        assertEquals("正文行与现场的 render 逐字相同", scene?.lines(), s.sceneLines())
    }

    @Test
    fun `冷却未到不重复钉，到点再钉`() {
        val pins = mutableListOf<Pin>()
        var clock = 100_000L
        val base = clock
        val hb = Heartbeat()
        // 主线程「已经冻了 30 秒」并**一直**冻着：打点时刻相对第一次的时钟读数取，
        // 时钟往前走、距今只会越来越大 —— 否则后两拍会因为「不再超期」而挡住，验不到冷却本身。
        hb.markUiFrame(base - 30_000L, "四段全成")
        hb.markVm(base - 20L, "turn 2ms")
        hb.markRenderPublish(base - 40L, "rev=42 frames=99")
        val s = StallSentinel(
            heartbeat = hb,
            clockMs = { clock },
            mainThread = { Thread.currentThread() },
            vmThread = { null },
            pin = { phase, scene -> pins += Pin(phase, scene) },
        ).apply { setUiLoopExpected(true) }
        assertNotNull("首钉不等冷却", s.tick(clock))
        clock += HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS - 1L
        assertNull("差一毫秒不重复钉（同一次冻结只留一个位置点）", s.tick(clock))
        clock += 1L
        assertNotNull("冷却到点可以再钉（同一次冻结的斜线）", s.tick(clock))
        assertEquals(2, pins.size)
    }

    @Test
    fun `钉发口抛异常不外逃且照常进冷却`() {
        val pins = mutableListOf<Pin>()
        val s = sentinel(30_000L, pins = pins, throwOnPin = true)
        val scene = s.tick(now)
        assertNotNull("落盘失败不该把现场也吞掉", scene)
        assertEquals("落盘口抛了 ⇒ 计数里仍没有成功记录", 0, pins.size)
        // 冷却挂在「判式成立」那一刻：不这样写就会变成每 500 ms 重试一次抓栈。
        assertNull("同一秒内第二拍必须被冷却挡住", s.tick(now))
        assertNotNull(
            "冷却到点才允许再试（30 s 之后落盘可能已经恢复）",
            StallJudgment.shouldPin(now + HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS, now),
        )
    }

    @Test
    fun `取不到栈时现场不撒谎`() {
        val pins = mutableListOf<Pin>()
        val scene = requireNotNull(sentinel(5_000L, mainThread = { null }, vmThread = { null }, pins = pins).tick(now))
        assertTrue("主线程取不到 = 空表", scene.uiFrames.isEmpty())
        assertTrue("轨迹行要写未取到而不是留个空：${pins[0].phase}", pins[0].phase.contains(StallJudgment.NOT_CAPTURED))
        assertTrue(
            "小节里主线程那一栏要写未取到",
            scene.lines().dropWhile { it != "主线程 ${scene.uiThreadName ?: "?"}：" }.contains(StallJudgment.NOT_CAPTURED),
        )
    }

    // ---- ④⑤ 体积与「谁该被钉」 ----

    private fun trace(n: Int): Array<StackTraceElement> = Array(n) { i ->
        StackTraceElement("com.eratw.deep.Frame$i", "run$i", "Frame$i.kt", i + 1)
    }

    private fun snapshot(uiAgeMs: Long, vmAgeMs: Long? = 100L): HeartbeatSnapshot {
        val (hb, _) = heartbeat(uiAgeMs, vmAgeMs)
        return hb.snapshot()
    }

    @Test
    fun `一次冻结的帧文本不超二十四条且轨迹只一行`() {
        val scene = StallJudgment.buildScene(
            nowMs = now,
            snapshot = snapshot(8_000L),
            uiAgeMs = 8_000L,
            uiThreadName = "main",
            uiTrace = trace(50),
            vmThreadName = "eratw-vm",
            vmTrace = trace(50),
        )
        assertTrue("主线程一栏 <= 12 帧 + 一行省略标记：${scene.uiFrames.size}", scene.uiFrames.size <= 13)
        assertEquals("两条合计的**真帧**不超 24（省略标记是说明文字，不占额度）", 24, scene.frameCount())
        assertTrue("行数也要有闸（${scene.lines().size} > ${StallJudgment.SCENE_MAX_LINES}）", scene.lines().size <= StallJudgment.SCENE_MAX_LINES)
        assertEquals("主线程只列栈顶 12 帧", 12, scene.uiFrames.count { !it.startsWith("…") })
        assertEquals("脚本线程同样只列栈顶 12 帧", 12, scene.vmFrames.count { !it.startsWith("…") })
        assertTrue(
            "要把「其实有 50 帧」如实说出来：${scene.uiFrames.last()}",
            scene.uiFrames.last().contains("共 50 帧"),
        )
        val phase = StallJudgment.trailPhase(scene)
        assertFalse("轨迹增量只许一行：$phase", phase.contains('\n'))
        assertTrue("轨迹行体积有闸（${phase.length} > ${StallJudgment.MAX_TRAIL_PHASE_CHARS}）", phase.length <= StallJudgment.MAX_TRAIL_PHASE_CHARS)
        assertTrue("帧文本形状同 ErrorReportCollector 的 Java 栈（简单类名.方法名:行号）：${scene.uiFrames[0]}", scene.uiFrames[0] == "Frame0.run0:1")
    }

    @Test
    fun `脚本线程取不到时不冒充`() {
        val scene = StallJudgment.buildScene(
            nowMs = now,
            snapshot = snapshot(8_000L),
            uiAgeMs = 8_000L,
            uiThreadName = "main",
            uiTrace = trace(3),
            vmThreadName = null,
            vmTrace = null,
        )
        assertEquals(emptyList<String>(), scene.vmFrames)
        assertNull("VM 线程没起来时线程名也是 null（报告里据此写未取到）", scene.vmThreadName)
        assertFalse("轨迹行不写 vm@ 那一栏", StallJudgment.trailPhase(scene).contains("vm@"))
        assertEquals("脚本侧距今仍按心跳给（这一路有打点）", 100L, scene.vmAgeMs ?: -1L)
    }

    @Test
    fun `未采到的脚本侧不编读数`() {
        val scene = StallJudgment.buildScene(
            nowMs = now,
            snapshot = snapshot(8_000L, vmAgeMs = null),
            uiAgeMs = 8_000L,
            uiThreadName = "main",
            uiTrace = trace(2),
            vmThreadName = "eratw-vm",
            vmTrace = trace(2),
        )
        assertNull(scene.vmAgeMs)
        assertTrue("轨迹行写 - 那一档（未采到）：${StallJudgment.trailPhase(scene)}", StallJudgment.trailPhase(scene).contains(HeartbeatJudgment.NOT_MEASURED))
    }

    // ---- ⑦ 线程面（假时钟 + 真线程） ----

    @Test
    fun `线程起来会自己钉一行，stop 之后收口`() {
        val pins = mutableListOf<Pin>()
        val started = CountDownLatch(1)
        val base = System.nanoTime() / 1_000_000L
        val hb = Heartbeat()
        hb.markUiFrame(base - 10_000L, "四段全成")
        hb.markVm(base - 20L, "turn 2ms")
        // 「主线程」在测试里就是测试线程本身：它正阻塞在 latch 上，栈一定拿得到。
        val s = StallSentinel(
            heartbeat = hb,
            clockMs = { System.nanoTime() / 1_000_000L },
            mainThread = { Thread.currentThread() },
            vmThread = { null },
            pin = { phase, scene ->
                if (pins.size == 0) started.countDown()
                pins += Pin(phase, scene)
            },
            sampleIntervalMs = 50L,
        )
        s.setUiLoopExpected(true)
        s.start()
        assertTrue("哨兵线程在 5 秒内没有钉出一行（判式或线程面断了）", started.await(5, TimeUnit.SECONDS))
        assertTrue("线程名要能在真机线程表里认出它自己", findLiveThreadByName("eratw-stall") != null)
        s.stop()
        s.stop()
        assertFalse("stop 幂等后 isRunning 为假", s.isRunning())
        Thread.sleep(200L)
        assertNull("stop 之后那条线程必须已经收口（不许留在进程里空转）", findLiveThreadByName("eratw-stall"))
        assertTrue("钉发至少一行", pins.isNotEmpty())
        assertTrue("轨迹那一行是 stallstack 开头：${pins[0].phase}", pins[0].phase.startsWith(StallJudgment.TRAIL_PHASE))
    }

    @Test
    fun `stop 之后不再钉`() {
        val pins = mutableListOf<Pin>()
        val base = System.nanoTime() / 1_000_000L
        val hb = Heartbeat()
        hb.markUiFrame(base - 10_000L, "四段全成")
        val s = StallSentinel(
            heartbeat = hb,
            clockMs = { System.nanoTime() / 1_000_000L },
            mainThread = { Thread.currentThread() },
            vmThread = { null },
            pin = { phase, scene -> pins += Pin(phase, scene) },
            sampleIntervalMs = 20L,
        )
        s.setUiLoopExpected(true)
        s.start()
        s.stop()
        val afterStop = pins.size
        Thread.sleep(300L)
        assertEquals("stop 之后线程若还在钉，就是 onDestroy 的收口没做干净", afterStop, pins.size)
    }

    @Test
    fun `按名字找线程：找到活的、不返回自己、找不到给 null`() {
        val name = "eratw-stall-probe"
        val gate = CountDownLatch(1)
        val t = Thread({
            gate.countDown()
            try {
                Thread.sleep(300L)
            } catch (_: InterruptedException) {
                Unit
            }
        }, name)
        t.isDaemon = true
        t.start()
        assertTrue("探针线程已就位", gate.await(3, TimeUnit.SECONDS))
        assertNotNull("同名线程活着时按名字要能找到（VM 那一路的口就靠这个）", findLiveThreadByName(name))
        assertNull("绝不返回调用方自己：哨兵若把自己钉成主线程现场就是假现场", findLiveThreadByName(Thread.currentThread().name))
        assertNull("没有这条线程时给 null，不抛", findLiveThreadByName("eratw-no-such-thread"))
        assertEquals("装配用的常量就是 GameHost 建线程时那个名", "eratw-vm", StallJudgment.VM_THREAD_NAME)
    }

    // ---- ⑧ 装配面（源码面） ----

    @Test
    fun `装配随生命周期且读数口是 internal`() {
        val code = codeLines("MainActivity.kt")
        assertTrue("onResume 起线程", code.contains("stallSentinel.start()"))
        val stops = code.lines().count { it.trim().contains("runCatching { stallSentinel.stop() }") }
        assertTrue("onPause 与 onDestroy 各停一次（切后台后泵本就不转）：读到 $stops 处", stops >= 2)
        val onDestroy = code.substringAfter("override fun onDestroy()")
        val beforeSuper = onDestroy.substringBefore("super.onDestroy()")
        assertTrue("onDestroy 必达，且排在 super.onDestroy() 之前（框架收口不许被挡）", beforeSuper.contains("stallSentinel.stop()"))
        assertTrue("泵每帧写武装位（判据 = 续排判据本身）", code.contains("stallSentinel.setUiLoopExpected("))
        assertTrue("钉发走 RunWatch 既有写侧闸并带上心跳", code.contains("RunWatch.forceSample(applicationContext, phase, heartbeat)"))
        assertTrue("读数口是 internal（L6 用，接线归组长）", code.contains("internal fun stallSceneLines(): List<String>"))
        assertTrue("进程级读数口齐备", code.contains("internal fun currentStallSceneLines(): List<String>"))
        assertTrue("读数口认主摘除（口径同 crashHostProvider）", code.contains("if (stallSceneOwner === this)"))
        // 新线程不许把自己变成 UI 卡顿源：不 suspend、不 interrupt 别人、不 join 别人。
        val sentinelCode = codeLines("StallSentinel.kt")
        assertFalse("不许出现 Thread.suspend", sentinelCode.contains(".suspend"))
        assertFalse("不许 join 别人的线程", sentinelCode.contains(".join("))
        val interrupts = sentinelCode.lines().filter { it.contains("interrupt()") }
        assertTrue("interrupt 只许打在自己那条线程上：$interrupts", interrupts.all { it.contains("t?.interrupt()") || it.contains("InterruptedException") })
        assertFalse("不许读 core（.erbc 映射页那一路禁止复发）", sentinelCode.contains("com.eratw.port.core"))
        assertFalse("不许碰 erbc", sentinelCode.contains("erbc"))
    }

    /** 破坏性验证的落点：把哨兵短路成「永不抓栈」时，本用例必须红（详见交付报告）。 */
    @Test
    fun `短路成永不抓栈时必红`() {
        val pins = mutableListOf<Pin>()
        assertNotNull(
            "判式成立却不抓栈 = 这条取证线是死代码；短路 tick 时这一条要红",
            sentinel(30_000L, pins = pins).tick(now),
        )
        assertEquals(1, pins.size)
    }

    // ------------------------------------------------------------------
    // 取源码：工作目录随 gradle 入口变（口径同 StrictModeGuardTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File = moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
        ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")
}
