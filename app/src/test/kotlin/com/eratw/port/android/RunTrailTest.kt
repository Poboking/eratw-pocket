package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [RunTrail] 是 2026-10-30 玩家反馈「死机了」「这次没有报错，直接闪退了，安卓16」的采集契约。
 *
 * ## 为什么这些判式必须单独锁
 *
 * 这一类死不给任何 Java 异常：系统按内存压力杀进程（lmkd / OEM 后台管控）不经过
 * `Thread.setDefaultUncaughtExceptionHandler`，OOM 大到报告自己也分配不出来时同样留不下东西。
 * 唯一的取证办法是冷启动时读上一轮的收尾标记 —— 于是判式写错的两种后果都只能装机才发现：
 *
 * - **误报**：正常退出被认成脏退出 ⇒ 每次冷启多一份「上次异常退出」，台账被自造噪声灌满。
 * - **漏报**：真被杀的那次没认出来 ⇒ 玩家反馈的那一类死永远零读数，修复无从谈起。
 *
 * ## 判据（逐条锁死）
 *
 * ①脏退出只认状态首词 `running`；空/读不到/`stopped`/`consumed`/`background` 一律不算
 *   （宁缺勿误报）。`background` = 界面已不存在的普通态，之后被系统回收是正常管控 ——
 *   这条判据守的就是「玩家按 Home 离开后每次打开都弹一次」这个误报。
 * ②认账后的那一行**自身不能再判脏** —— 它把上一轮原文带在 `prior=` 里，字符串含 `running`。
 * ③落盘节流只作用于盘，不作用于内存采样：首行必写、2 秒内不重复写、`force` 无条件写。
 * ④报告里的三列口径与装载埋点 [LoadTrace] 逐字相同（`used` / `max` / `native`），否则
 *   装载期与运行期的读数并排看会得出假结论。
 * ⑤写文件要**自己建目录**：[RunWatch] 全程 `runCatching`，`writeText` 撞 ENOENT 会被静默
 *   吞掉 —— 2026-10-30 在 API 36 模拟器上就是这样整套采集一声不响地没落盘（`files/eratw-run`
 *   根本不存在）。这条只能靠装机/模拟器发现，所以判式与写入器一起锁。
 *
 * 为何是 JVM 单测而非 instrumented：判式本身无 Android 运行期依赖（同
 * `ShellUiFailureActionsTest` 的先例）；真正读写 `filesDir` 的那半在 [RunWatch]，全程
 * `runCatching`，出错只 Log。
 */
class RunTrailTest {

    @get:Rule
    val root = TemporaryFolder()

    // ---- ① 脏退出判据 ----

    @Test
    fun `只有 running 首词算脏退出`() {
        assertTrue(RunTrail.isDirtyExit(RunTrail.runningState("20261030-a")))
        assertTrue("行尾还有构建戳，判据只看首词", RunTrail.isDirtyExit("running build=x\n"))
        assertTrue("首词后的空格可有可无", RunTrail.isDirtyExit("  running"))
    }

    @Test
    fun `正常收尾与读不到都不算脏退出`() {
        assertFalse("走到 onDestroy 就是干净退出", RunTrail.isDirtyExit(RunTrail.stoppedState("onDestroy")))
        assertFalse(RunTrail.isDirtyExit(RunTrail.stoppedState("exitButton")))
        assertFalse(RunTrail.isDirtyExit(RunTrail.stoppedState("crash:OutOfMemoryError")))
        assertFalse("文件不存在（首装）不该报上一次死过", RunTrail.isDirtyExit(null))
        assertFalse(RunTrail.isDirtyExit(""))
        assertFalse(RunTrail.isDirtyExit("   "))
        assertFalse("旧版本留下的别的格式一律不猜", RunTrail.isDirtyExit("unknown garbage"))
    }

    // ---- ①b 后台被回收不算异常退出（2026-10-30 装机误报）----

    @Test
    fun `切后台后状态不再是 running`() {
        val bg = RunTrail.backgroundState(nowMs = 1_730_000_000_000L)
        assertTrue("首词必须是 background", bg.startsWith(RunTrail.MARK_BACKGROUND))
        assertFalse(
            "这条判据守的就是「按 Home 离开 → 被系统清 → 下次打开弹卡」：后台态不能算脏退出",
            RunTrail.isDirtyExit(bg),
        )
        assertTrue(RunTrail.isBackgroundDeath(bg))
        assertTrue("墙钟带在行里，日志里能看出在后台待了多久", bg.contains("since=1730000000000"))
    }

    @Test
    fun `后台回收判据只认 background 首词`() {
        assertFalse("前台正在跑不是后台回收", RunTrail.isBackgroundDeath(RunTrail.runningState("x")))
        assertFalse(RunTrail.isBackgroundDeath(RunTrail.stoppedState("onDestroy")))
        assertFalse(RunTrail.isBackgroundDeath(null))
        assertFalse(RunTrail.isBackgroundDeath(""))
        assertFalse(
            "认账行把上一轮原文带在 prior= 里（可能含 background），首词仍是 consumed",
            RunTrail.isBackgroundDeath(RunTrail.consumedState(RunTrail.backgroundState(1L))),
        )
        assertTrue("首词后的空格可有可无", RunTrail.isBackgroundDeath("  background since=1"))
    }

    // ---- ② 认账不重复报 ----

    @Test
    fun `认账后的状态行自身不再判脏`() {
        val consumed = RunTrail.consumedState(RunTrail.runningState("20261030-a"))
        assertTrue("原文要带在行里，报告才有得读", consumed.contains("running"))
        assertTrue("但首词必须是 consumed", consumed.startsWith(RunTrail.MARK_CONSUMED))
        assertFalse(
            "这是「每次冷启重报一份」的守门判据：上一行脏退出已认账，第二次读它不能再算脏",
            RunTrail.isDirtyExit(consumed),
        )
    }

    @Test
    fun `状态原文缺省时写占位而不是空`() {
        assertEquals("consumed prior=-", RunTrail.consumedState(null))
        assertTrue(RunTrail.consumedState("a\nb").contains("a b"))
    }

    // ---- ②b 崩溃认领的那一轮：收尾标记要能把报告文件名交给下一次冷启动 ----

    /**
     * 崩溃兜底写完报告后落的那条标记（`stopped crash=… report=…`）。
     *
     * 真实读数取自 2026-11-03 端点那三份退化报告的异常类名
     * （`java.lang.OutOfMemoryError` / `java.lang.NoClassDefFoundError`）与
     * [ErrorReportCollector] 的两条命名路（常规名 `eratw-report-<yyyyMMdd-HHmmss>.txt`
     * 与同秒避让名 `eratw-report-<毫秒>.txt`）——两种名都要认得出，避让名恰恰是
     * 「一秒内崩了两次」那种最该被认领的形状。
     */
    @Test
    fun `崩溃认领的标记交回报告文件名`() {
        val reason = RunTrail.crashStopReason("OutOfMemoryError", "eratw-report-20261103-130512.txt")
        val state = RunTrail.stoppedState(reason)
        assertEquals("stopped crash=OutOfMemoryError report=eratw-report-20261103-130512.txt", state)
        assertFalse("崩过的那一轮不能再被首词 running 判成脏退出", RunTrail.isDirtyExit(state))
        assertEquals(
            "常规名要认得（下一次冷启靠它把那份现成报告递给玩家）",
            "eratw-report-20261103-130512.txt",
            RunTrail.crashReportName(state),
        )
        assertEquals(
            "同秒避让名同样是本类的产物，一样要认",
            "eratw-report-1793682312684.txt",
            RunTrail.crashReportName(RunTrail.stoppedState(RunTrail.crashStopReason("NoClassDefFoundError", "eratw-report-1793682312684.txt"))),
        )
    }

    /**
     * 只认「崩溃兜底写了报告并认领了」那一形；其余收尾一律不沾这张卡。
     *
     * 漏这一条的代价是**每次正常退出都弹一张「上次异常退出」**：`onDestroy` / `exitButton`
     * 这些收尾走的是同一支 [RunTrail.stoppedState]，只有 reason 前缀分得出是谁认的账。
     */
    @Test
    fun `非崩溃的收尾与畸形文件名都不认领`() {
        assertNull("正常收尾（onDestroy）不给卡", RunTrail.crashReportName(RunTrail.stoppedState("onDestroy")))
        assertNull("玩家点结束应用不给卡", RunTrail.crashReportName(RunTrail.stoppedState("exitButton")))
        assertNull("重启失败屏不给卡（它自己写过报告）", RunTrail.crashReportName(RunTrail.stoppedState("restartAfterFailure")))
        assertNull("崩了但报告没落成：没有 report= 就没有东西可递", RunTrail.crashReportName("stopped crash=OutOfMemoryError"))
        assertNull("首词不是 stopped 的一律不认", RunTrail.crashReportName("running crash=X report=eratw-report-a.txt"))
        assertNull("空文件名的标记不认", RunTrail.crashReportName("stopped crash=X report="))
        assertNull("不相干的文件名前缀不认", RunTrail.crashReportName("stopped crash=X report=notes.txt"))
        assertNull(
            "状态文件在私有目录里，但拿盘上的字符串直接拼 File 不省这道闸",
            RunTrail.crashReportName("stopped crash=X report=eratw-report-../../../deleteme.txt"),
        )
        assertNull("反斜杠同样挡掉", RunTrail.crashReportName("stopped crash=X report=eratw-report-..\\a.txt"))
        assertNull("读不到状态就更不认", RunTrail.crashReportName(null))
    }

    /** 类名里带换行要把行压平：状态文件按首词判状态，多出一行整套判据失声（口径同 consumedState）。 */
    @Test
    fun `崩溃标记里的类名压平成一行`() {
        val state = RunTrail.stoppedState(RunTrail.crashStopReason("a\nb", "eratw-report-c.txt"))
        assertEquals(1, state.lines().size)
        assertTrue(state, state.contains("crash=a b report=eratw-report-c.txt"))
        assertEquals("eratw-report-c.txt", RunTrail.crashReportName(state))
    }

    // ---- ②c 同进程重入：Activity 重建不许把还活着的这一轮判成脏退出 ----

    /**
     * 冷启动取证的同进程一次性闸门。
     *
     * 真实形状取自 2026-11-04 那台三星 SM-G9860（API 28）：一次装机里 5 份「上次异常退出」，
     * 其中一份的运行轨迹是 `t=78ms boot` → `t=18068ms pause` → `t=18828ms boot` —— 同一个
     * `originMs` 基准里两发 `boot`，就是同进程第二趟 `onCreate`。而配置变更那一跳
     * `onStop` 故意不写 `background`（见 `MainActivity.onStop` 的注释），盘上那行 `running`
     * 是本进程第一趟自己写的 ⇒ 旧口径把**还活着的这一轮**判成「上次异常退出」并弹卡。
     * 口径说清楚：那 5 份报告正文的「本轮 Java 堆」全是 5MiB ⇒ 它们都是**新进程**写的，
     * 这一形在端点里还没有误报样本；闸门钉的是代码上确定的那条路（同 [ColdStartOnceGate]
     * 的出处段）。
     */
    @Test
    fun `冷启动取证每个进程只判一次`() {
        val gate = ColdStartOnceGate()
        assertFalse("第一趟之前闸门没落下", gate.hasEntered())
        assertTrue("第一趟才是真冷启动", gate.enter())
        assertTrue("闸门落下", gate.hasEntered())
        assertFalse("第二趟（Activity 重建）不再判", gate.enter())
        assertFalse("第三趟也不再判", gate.enter())
    }

    // ---- ③ 落盘节流 ----

    @Test
    fun `首行必写即使间隔未到`() {
        assertTrue(RunTrail.shouldWrite(elapsedMs = 0L, lastWriteMs = -1L, force = false))
        assertTrue(RunTrail.shouldWrite(elapsedMs = 800L, lastWriteMs = -1L, force = false))
    }

    @Test
    fun `两秒内不重复落盘`() {
        assertTrue(RunTrail.shouldWrite(2_000L, 0L, force = false))
        assertFalse(
            "回合可以每几十毫秒一次，逢回合就写盘会把私有分区刷爆",
            RunTrail.shouldWrite(RunTrail.WRITE_MIN_INTERVAL_MS - 1, 0L, force = false),
        )
        assertFalse(RunTrail.shouldWrite(1_000L, 900L, force = false))
    }

    @Test
    fun `内存压力无条件落盘`() {
        assertTrue("onTrimMemory 那一发往往就是死前最后一行，节流不能拦它",
            RunTrail.shouldWrite(10L, 9L, force = true))
        assertTrue(RunTrail.shouldWrite(0L, 0L, force = true))
    }

    // ---- ④ 轨迹行与报告行 ----

    @Test
    fun `轨迹行三列口径同装载埋点`() {
        assertEquals(
            "t=1500ms used=301MiB max=512MiB native=88MiB turn 42ms",
            RunTrail.formatLine(1_500L, 301L, 512L, 88L, "turn 42ms"),
        )
        assertEquals("阶段为空不留尾巴空格",
            "t=0ms used=0MiB max=0MiB native=0MiB",
            RunTrail.formatLine(0L, 0L, 0L, 0L, ""))
    }

    @Test
    fun `轨迹为空时说明死在装载期`() {
        val lines = RunTrail.dirtyReportLines("running build=x", emptyList())
        assertTrue(
            "一条都没有是有效读数（死在第一次回合边界之前），不能留白让玩家以为没数据",
            lines.any { it.contains("装载期") },
        )
        assertTrue(lines.any { it.contains("running build=x") })
    }

    @Test
    fun `报告只带尾部若干行且旧在前`() {
        val trail = (1..80).map { "t=${it}ms used=${it}MiB max=512MiB native=1MiB p$it" }
        val lines = RunTrail.dirtyReportLines("running build=x", trail)
        val carried = lines.filter { it.startsWith("t=") }
        assertEquals("报告体积不能被轨迹灌大，只取尾段（= 轨迹上限 64 行）", 64, carried.size)
        assertEquals("t=17ms used=17MiB max=512MiB native=1MiB p17", carried.first())
        assertEquals("t=80ms used=80MiB max=512MiB native=1MiB p80", carried.last())
    }

    @Test
    fun `轨迹行带上系统可用内存与本机低内存线`() {
        assertEquals(
            "t=1500ms used=301MiB max=512MiB native=88MiB turn 42ms sys=820MiB lmk=180MiB",
            RunTrail.formatLine(
                1_500L, 301L, 512L, 88L, "turn 42ms",
                sysAvailMiB = 820L, lmkThresholdMiB = 180L,
            ),
        )
        assertEquals(
            "读不到系统内存时整段消失（绝不写假读数）",
            "t=0ms used=0MiB max=0MiB native=0MiB",
            RunTrail.formatLine(0L, 0L, 0L, 0L, "", sysAvailMiB = -1L, lmkThresholdMiB = -1L),
        )
        assertEquals(
            "只有 sys 没有 lmk（旧机取不到阈值）时只写 sys",
            "t=1ms used=1MiB max=2MiB native=3MiB sys=900MiB",
            RunTrail.formatLine(1L, 1L, 2L, 3L, "", sysAvailMiB = 900L, lmkThresholdMiB = -1L),
        )
    }

    @Test
    fun `meminfo 里取系统可用内存（MiB）`() {
        val meminfo = """
            MemTotal:        7645284 kB
            MemFree:          145216 kB
            MemAvailable:    1832960 kB
            Buffers:           12004 kB
        """.trimIndent()
        assertEquals("1832960 kB = 1790 MiB（向下取整）", 1_790L, RunTrail.parseMemAvailableMiB(meminfo))
        assertNull("没有这一行就不猜", RunTrail.parseMemAvailableMiB("MemFree: 100 kB"))
        assertNull("值不是数字也不猜", RunTrail.parseMemAvailableMiB("MemAvailable: lots kB"))
        assertNull("空文件", RunTrail.parseMemAvailableMiB(""))
    }

    @Test
    fun `保留行数与节流常量是埋点自述的口径`() {
        assertEquals(64, RunTrail.MAX_LINES)
        assertEquals(2_000L, RunTrail.WRITE_MIN_INTERVAL_MS)
        assertEquals("state.txt", RunTrail.STATE_FILE)
        assertEquals("trail.txt", RunTrail.TRAIL_FILE)
        assertEquals("stopped onDestroy", RunTrail.stoppedState("onDestroy"))
        // 崩溃兜底那一形的**生产原文**（`crashStopReason` 拼出来的东西就长这样，见上面的用例）：
        // stoppedState 只认「首词 + 空格 + reason」，reason 里再塞换行就毁掉整套首词判据。
        assertEquals(
            "stopped crash=OutOfMemoryError report=eratw-report-20261103-130512.txt",
            RunTrail.stoppedState("crash=OutOfMemoryError report=eratw-report-20261103-130512.txt"),
        )
    }

    // ---- ⑥ 主动回收的水位判据（ROM 不一定发 onTrimMemory）----

    @Test
    fun `堆贴到八成才主动收`() {
        assertTrue("512 的八成 = 409.6 ⇒ 410 是第一个该动手的整数",
            RunTrail.shouldReleaseImagePixels(usedMiB = 410, maxMiB = 512, sinceLastAttemptMs = 60_000))
        assertFalse("差一 MiB 就不动", RunTrail.shouldReleaseImagePixels(409, 512, 60_000))
        assertFalse("七成就动手等于每几页白走一遍资源表",
            RunTrail.shouldReleaseImagePixels(400, 512, 60_000))
        assertFalse("读数不可用（max=0）时一律不收", RunTrail.shouldReleaseImagePixels(0, 0, 60_000))
    }

    @Test
    fun `回收尝试有冷却且首次不受冷却限制`() {
        // 用 472（九成二）而不是 500：500/512 = 97.6% 已经落进 panic 档（九五以上无视冷却），
        // 那条判据由下面的 `贴到九五以上无视冷却` 单独钉。
        assertFalse(RunTrail.shouldReleaseImagePixels(472, 512, RunTrail.RELEASE_COOLDOWN_MS - 1))
        assertTrue(RunTrail.shouldReleaseImagePixels(472, 512, RunTrail.RELEASE_COOLDOWN_MS))
        assertTrue("还没试过（调用方给一个足够大的间隔）⇒ 立刻可以试",
            RunTrail.shouldReleaseImagePixels(472, 512, Long.MAX_VALUE / 2))
    }

    // ---- ⑥b 第二档水位：连「画过的」合成缓冲也释放（G_ID 池只增不减的收口）----

    @Test
    fun `九成二才收画过的缓冲`() {
        // 512 MiB 的 92% = 471.04 ⇒ 472 是第一个该动手的整数。
        assertTrue(RunTrail.shouldReleaseUnusedGraphics(472, 512, 61_000))
        assertFalse("九成二不到绝不动脚本画上去的东西（可能一帧空白，赌不起频繁赌）",
            RunTrail.shouldReleaseUnusedGraphics(471, 512, 61_000))
        assertFalse("八成档（shouldReleaseImagePixels）该动手时这一档还按兵不动",
            RunTrail.shouldReleaseUnusedGraphics(410, 512, 61_000))
        assertFalse("读数不可用（max=0）时一律不收", RunTrail.shouldReleaseUnusedGraphics(0, 0, 61_000))
    }

    @Test
    fun `画过缓冲的回收冷却比无损档长一倍`() {
        // 60 s 冷却：这一档每动手一次都真实丢内容（gid 变未创建），
        // 频率必须远低于 80% 档，否则 GET_EMPTY_G 分到的 gid 反复被抽走。
        // 472 = 九成二档刚过、还没进 panic（九五），冷却仍是唯一闸门。
        assertFalse(RunTrail.shouldReleaseUnusedGraphics(472, 512, RunTrail.RELEASE_UNUSED_COOLDOWN_MS - 1))
        assertTrue(RunTrail.shouldReleaseUnusedGraphics(472, 512, RunTrail.RELEASE_UNUSED_COOLDOWN_MS))
        assertTrue("首次不受冷却限制", RunTrail.shouldReleaseUnusedGraphics(472, 512, Long.MAX_VALUE / 2))
    }

    /**
     * 九五以上（panic 档）两档回收都无视冷却。
     *
     * 依据是 2026-10-31 端点收件箱的 11 份 `OutOfMemoryError` 崩溃（跨 6 种机型）：
     * 堆从 92% 涨到 100% 只有几十 MiB 余量，一次「打开角色列表」就填满，60 s 冷却
     * 根本等不到 —— 冷却的初衷是防卡顿，到了悬崖边卡一下远好于进程消失。
     */
    @Test
    fun `贴到九五以上无视冷却`() {
        // 512 的 95% = 486.4 ⇒ 487 是第一个 panic 档，486 还差一点。
        assertFalse("486（94.9%）还没进 panic，冷却未到就不收",
            RunTrail.shouldReleaseImagePixels(486, 512, 0))
        assertTrue("487（95.1%）已经站在悬崖边，冷却未到也必须收",
            RunTrail.shouldReleaseImagePixels(487, 512, 0))
        assertFalse("486（94.9%）已过九成二档但没进 panic，冷却未到就不收",
            RunTrail.shouldReleaseUnusedGraphics(486, 512, 0))
        assertTrue(RunTrail.shouldReleaseUnusedGraphics(487, 512, 0))
        assertFalse("读数不可用（max=0）时 panic 档也不动手",
            RunTrail.shouldReleaseUnusedGraphics(0, 0, 0))
    }

    // ---- ⑥c 系统口径：系统可用内存跌破本机低内存线（与堆水位互补）----

    /**
     * 2026-10-31 收件箱的 14 份静默退出（Java 堆 10%~79%）在堆水位判据上**一次都不会触发**，
     * 死因是整机内存被别的东西吃光。这一档的读数来自轨迹同款（`/proc/meminfo` 与
     * `MemoryInfo.threshold`），所以判式与 `RunDiagnostics` 的系统桶必须逐字同源。
     */
    @Test
    fun `系统跌破低内存线才收延迟图`() {
        assertTrue("179 < 180 就是跌破（与 MemoryInfo.lowMemory 同口径）",
            RunTrail.shouldReleaseImagePixelsForSystemPressure(179, 180, 60_000))
        assertFalse("恰好等于那条线不算跌破（系统自己也是严格小于）",
            RunTrail.shouldReleaseImagePixelsForSystemPressure(180, 180, 60_000))
        assertFalse("还在线以上就交给系统自己回收",
            RunTrail.shouldReleaseImagePixelsForSystemPressure(400, 180, 60_000))
        assertFalse("读不到系统可用内存（-1）一律不动",
            RunTrail.shouldReleaseImagePixelsForSystemPressure(-1, 180, 60_000))
        assertFalse("查不到低内存线（-1 / 0）一律不动",
            RunTrail.shouldReleaseImagePixelsForSystemPressure(100, -1, 60_000))
        assertFalse(RunTrail.shouldReleaseImagePixelsForSystemPressure(100, 0, 60_000))
    }

    @Test
    fun `系统档的回收也有冷却`() {
        assertFalse(RunTrail.shouldReleaseImagePixelsForSystemPressure(
            172, 180, RunTrail.SYS_PRESSURE_COOLDOWN_MS - 1))
        assertTrue(RunTrail.shouldReleaseImagePixelsForSystemPressure(
            172, 180, RunTrail.SYS_PRESSURE_COOLDOWN_MS))
    }

    /**
     * 「画过的缓冲」在系统口径下要**再严一倍**（可用不足低内存线的一半），且冷却是 60 s：
     * 刚跌破线时还愿意相信系统自己能回收；丢脚本画上去的内容只在「一半都不剩」时值。
     */
    @Test
    fun `系统跌到低内存线一半才收画过的缓冲`() {
        assertFalse("90 正好是一半（90*2 = 180 不 < 180）⇒ 还不动",
            RunTrail.shouldReleaseUnusedForSystemPressure(90, 180, 61_000))
        assertTrue("89 ⇒ 一半都不剩，动",
            RunTrail.shouldReleaseUnusedForSystemPressure(89, 180, 61_000))
        assertFalse("只是越过低内存线、还没到一半 ⇒ 这一档按兵不动",
            RunTrail.shouldReleaseUnusedForSystemPressure(172, 180, 61_000))
        assertFalse("读不到读数一律不动",
            RunTrail.shouldReleaseUnusedForSystemPressure(-1, 180, 61_000))
        assertFalse(RunTrail.shouldReleaseUnusedForSystemPressure(89, -1, 61_000))
        assertFalse("冷却按 RELEASE_UNUSED_COOLDOWN_MS（60 s）计",
            RunTrail.shouldReleaseUnusedForSystemPressure(89, 180, RunTrail.RELEASE_UNUSED_COOLDOWN_MS - 1))
        assertTrue(RunTrail.shouldReleaseUnusedForSystemPressure(
            89, 180, RunTrail.RELEASE_UNUSED_COOLDOWN_MS))
    }

    @Test
    fun `系统档与死因判读共用同一条跌破判式`() {
        // 同一条线、同一组读数：回收判据说「低」，判读桶也必须说「低」（反之亦然）。
        for (avail in listOf(0L, 89L, 90L, 179L, 180L, 181L, 400L)) {
            val release = RunTrail.shouldReleaseImagePixelsForSystemPressure(avail, 180, 60_000)
            val diagnose = RunTrail.isSystemLow(avail, 180)
            assertEquals("avail=$avail 两处判式必须一致", diagnose, release)
        }
    }

    // ---- ⑤ 落盘：目录不存在也必须写成本功（2026-10-30 模拟器实测踩到的洞）----

    @Test
    fun `首启目录不存在时状态文件仍要落成`() {
        val state = File(root.root, "eratw-run/${RunTrail.STATE_FILE}")
        assertFalse("前置条件：本用例测的就是目录还没建", state.parentFile!!.exists())
        assertTrue(RunTrail.writeStateFile(state, RunTrail.runningState("api36-1")))
        assertEquals(RunTrail.runningState("api36-1"), state.readText())
    }

    @Test
    fun `轨迹覆盖写且读回是同一批行`() {
        val trail = File(root.root, "eratw-run/${RunTrail.TRAIL_FILE}")
        val first = List(RunTrail.MAX_LINES + 10) {
            RunTrail.formatLine(it * 3_000L, 300L, 512L, 88L, "turn $it")
        }.takeLast(RunTrail.MAX_LINES)
        assertTrue(RunTrail.writeTrailFile(trail, first))
        assertEquals(first, trail.readLines())
        val second = listOf(RunTrail.formatLine(0L, 12L, 512L, 4L, "pause"))
        assertTrue(RunTrail.writeTrailFile(trail, second))
        assertEquals("覆盖写，不是追加：文件里永远只有本轮的尾", second, trail.readLines())
    }

    @Test
    fun `写不成时返回 false 而不是抛出去`() {
        // 父路径是一个普通文件 ⇒ mkdirs 必然失败。调用方（RunWatch）只 Log，
        // 所以这里判的是「返回 false」这一条：抛出去会把游戏带崩，本末倒置。
        val blocker = File(root.root, "blocked")
        assertTrue(blocker.createNewFile())
        val state = File(blocker, "${RunTrail.STATE_FILE}")
        assertFalse(RunTrail.writeStateFile(state, "running"))
        assertFalse(state.exists())
    }

    @Test
    fun `写成功后再读脏退出判据能接上`() {
        val state = File(root.root, "eratw-run/${RunTrail.STATE_FILE}")
        RunTrail.writeStateFile(state, RunTrail.runningState("api36-1"))
        assertTrue("落盘原文喂回判式 = 脏退出（这一条串起 ①与⑤，缺一半都取不到证）",
            RunTrail.isDirtyExit(state.readText()))
        RunTrail.writeStateFile(state, RunTrail.stoppedState("onDestroy"))
        assertFalse(RunTrail.isDirtyExit(state.readText()))
    }

    // ------------------------------------------------------------------
    // 长页自救口（2026-11-01 收件箱 `turn 58515ms`）：页中的两个判据
    // ------------------------------------------------------------------

    @Test
    fun `检查点节流只认250毫秒一档`() {
        assertFalse("刚跑过 ⇒ 同一发指令流里不该再来一次", RunTrail.shouldMidPageCheck(0L))
        assertFalse(RunTrail.shouldMidPageCheck(RunTrail.MID_PAGE_CHECK_INTERVAL_MS - 1L))
        assertTrue(RunTrail.shouldMidPageCheck(RunTrail.MID_PAGE_CHECK_INTERVAL_MS))
        // 负数 = 还没跑过（第一发必走，否则第一页永远没有自救机会）。
        assertTrue(RunTrail.shouldMidPageCheck(-1L))
    }

    @Test
    fun `五秒以上的页才钉轨迹且同一页至少隔五秒`() {
        // 门槛下不钉：正常页就 2ms 量级，钉了只会把 64 行滚动轨迹灌满噪声。
        assertFalse("没有在飞的页", RunTrail.shouldPinLongTurn(0L, -1L))
        assertFalse(RunTrail.shouldPinLongTurn(RunTrail.LONG_TURN_MS - 1L, -1L))
        assertTrue("第一次跨过门槛必钉", RunTrail.shouldPinLongTurn(RunTrail.LONG_TURN_MS, -1L))
        // 冷却内不重复钉同一秒（否则 65536 步一发 × 250ms 节流 ⇒ 一秒四行同样的话）。
        assertFalse(RunTrail.shouldPinLongTurn(20_000L, RunTrail.LONG_TURN_PIN_COOLDOWN_MS - 1L))
        assertTrue(
            "还在卡就该继续给读数：钉成一条斜线而不是一个点",
            RunTrail.shouldPinLongTurn(20_000L, RunTrail.LONG_TURN_PIN_COOLDOWN_MS),
        )
        assertTrue("新一页重新起算冷却", RunTrail.shouldPinLongTurn(20_000L, -1L))
    }

    // ------------------------------------------------------------------
    // ⑦ 写侧闸门 [TrailWriteGate]（轨迹落盘撕裂窗口修复）：
    //   「取快照 + 覆盖写盘」发生在环形表 lock 之外，UI（pause/trim 的 forceSample）与
    //   VM（sampleTurn / turn.* 强制钉发）可各自带着 due 并发整份覆盖 trail.txt。
    //   撕掉的不是「少一行」，是脏退出判据本身：末行的 pause/stopped 被旧快照盖回去，
    //   RunTrail.dirtyReportLines 就分不清「死在屏前」与「后台被回收」。
    //   修法两条腿：写侧专用 monitor（串行化写者）+ 序号跳旧快照（迟到的旧盘不发）。
    // ------------------------------------------------------------------

    /** 出口判据 A：两个写者并发落盘 ⇒ 盘上末行必须是两者中较新的那份快照。 */
    @Test
    fun `并发双写者落盘后盘上留下的必是较新快照`() {
        val file = File(root.root, "trail-race.txt")
        val older = listOf(RunTrail.formatLine(1_000L, 10L, 512L, 1L, "turn 5ms"))
        val newer = listOf(RunTrail.formatLine(2_000L, 10L, 512L, 1L, "pause"))
        // sink 里故意做慢窗：新快照写得慢、旧快照写得快——没有写侧串行 + 跳旧时，
        // 快的那发完全可能后完成把 pause 尾行盖掉。30 轮把两种进入顺序都压一遍。
        repeat(30) { round ->
            val gate = TrailWriteGate { f, lines ->
                Thread.sleep(if (lines === newer) 8L else 2L)
                RunTrail.writeTrailFile(f, lines)
            }
            val barrier = java.util.concurrent.CyclicBarrier(2)
            val tOld = Thread { barrier.await(); gate.write(1L, older, file) }
            val tNew = Thread { barrier.await(); gate.write(2L, newer, file) }
            tOld.start(); tNew.start(); tOld.join(); tNew.join()
            assertEquals("第 $round 轮：盘上必须还是较新快照（旧不许盖新）", newer, file.readLines())
        }
    }

    /** 出口判据 B（正面）：已有更新快照落盘后，迟到的旧快照不再碰盘。 */
    @Test
    fun `迟到的旧快照按序号跳过 不落盘也不算失败`() {
        val file = File(root.root, "trail-skip.txt")
        var sinkCalls = 0
        val gate = TrailWriteGate { f, lines ->
            sinkCalls++
            RunTrail.writeTrailFile(f, lines)
        }
        assertTrue(gate.write(2L, listOf("新快照"), file))
        assertEquals(1L, sinkCalls.toLong())
        assertTrue("seq 1 比已落盘的 2 旧 ⇒ 跳过；跳过返回 true（不是失败，调用方不该 Log.w）",
            gate.write(1L, listOf("旧快照"), file))
        assertEquals("旧快照一次都不许进 sink", 1L, sinkCalls.toLong())
        assertEquals(listOf("新快照"), file.readLines())
        assertTrue("更新的快照照常落", gate.write(3L, listOf("更新"), file))
        assertEquals(2L, sinkCalls.toLong())
        assertEquals(listOf("更新"), file.readLines())
    }

    /** 出口判据 B（反面守门）：落盘失败不推进序号，同序号的下一发照常重试。 */
    @Test
    fun `写失败不吞掉后续同序号重发`() {
        val blocker = File(root.root, "blocked-gate")
        assertTrue(blocker.createNewFile())
        var shouldFail = true
        var attempts = 0
        val gate = TrailWriteGate { f, lines ->
            attempts++
            if (shouldFail) {
                RunTrail.writeTrailFile(File(blocker, "trail.txt"), lines) // 父路径是普通文件 ⇒ mkdirs 必败
            } else {
                RunTrail.writeTrailFile(f, lines)
            }
        }
        val file = File(root.root, "trail-retry.txt")
        assertFalse("写失败要把 false 交回调用方去 Log.w", gate.write(5L, listOf("x"), file))
        assertFalse(file.exists())
        assertEquals(1L, attempts.toLong())
        shouldFail = false
        assertTrue("失败没推进序号 ⇒ 同序号 5 照常再落", gate.write(5L, listOf("x"), file))
        assertEquals(listOf("x"), file.readLines())
    }

    /**
     * 出口判据 C：环形表锁（RunWatch.lock）的临界区里不含任何文件写。
     *
     * 取证方式选的是**源码面**（解析真实源文件），理由：`appendAndMaybeWrite` 绑死
     * SystemClock / Context，`:app` 的 JVM 单测（android.jar 桩方法直接抛 Stub!）进不去，
     * 行为面无法从外部构造「两个线程各自过了 due 再并发写」的窗口；而这条约束是**结构性**
     * 的（写盘不许挪进 lock），源码面恰好钉的是结构本身——将来谁把写盘挪回临界区，本用例
     * 立刻红。禁用词表用全称调用名（writeTrailFile / trailGate / writeText / mkdirs /
     * writeStateFile），不会把 shouldWrite 这类节流判式误计成文件写。
     */
    @Test
    fun `环形表锁的临界区内不含文件写 落盘只走写侧闸门`() {
        val source = readRunWatchSource()
        val body = extractFunBody(source, "private fun appendAndMaybeWrite(")
        val lockBlocks = findSyncBlockSpans(body, "synchronized(lock)").map { (start, end) ->
            body.substring(start, end)
        }
        for (block in lockBlocks) {
            for (forbidden in listOf(
                "writeTrailFile", "writeStateFile", "trailGate", "writeText", "mkdirs",
            )) {
                assertFalse(
                    "环形表 lock 的临界区里不许出现文件写（$forbidden）：挪进来就是让 onPause 那类 UI 钉行等 VM 的磁盘 IO",
                    block.contains(forbidden),
                )
            }
        }
        assertTrue("至少要能看到两个 lock 临界区（追加段 + 取快照段），否则本用例在空转",
            lockBlocks.size >= 2)
        assertTrue("落盘必须走 trailGate（写侧专用 monitor + 序号跳旧）", body.contains("trailGate.write("))
        // 序号与快照必须在同一次 lock 临界区里配对取出，否则「跳旧」判的是错位的号。
        assertTrue(
            "快照与序号在锁内配对",
            lockBlocks.any { it.contains("seq = appendSeq") && it.contains("snapshot = trail.toList()") },
        )
    }

    /** 常态序列不被闸门误拦：单调递增序号每一发都照落（节流口径仍归 shouldWrite 单点裁决）。 */
    @Test
    fun `写侧闸门对单调递增序号一律放行`() {
        val file = File(root.root, "trail-plain.txt")
        val gate = TrailWriteGate { f, lines -> RunTrail.writeTrailFile(f, lines) }
        for (seq in 1L..10L) {
            assertTrue("常态路径（单线程追加快照）每一发都要照落", gate.write(seq, listOf("line$seq"), file))
            assertEquals(listOf("line$seq"), file.readLines())
        }
    }

    /** 找到 RunWatch.kt 源文件：JVM 单测的 cwd 可能是模块目录或仓根，逐级都试。 */
    private fun readRunWatchSource(): String {
        val rel = "com/eratw/port/android/RunWatch.kt"
        val candidates = listOf("src/main/kotlin", "app/src/main/kotlin").flatMap { prefix ->
            listOf(File(prefix), File("..", prefix), File("../..", prefix), File("../../..", prefix))
        }.map { File(it, rel) }
        val found = candidates.firstOrNull { it.isFile }
        assertTrue("找不到 RunWatch.kt（试了 ${candidates.joinToString { it.path }}）：源码面用例需要它", found != null)
        return found!!.readText()
    }

    /** 取函数体：从签名后的第一个左花括号起做配对计数。 */
    private fun extractFunBody(source: String, signature: String): String {
        val sigAt = source.indexOf(signature)
        assertTrue("源里找不到函数 $signature", sigAt >= 0)
        val open = source.indexOf('{', sigAt + signature.length)
        assertTrue("函数 $signature 没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("函数 $signature 的花括号不配对")
    }

    /** 找每个 `marker` 出现的块跨度 [markerStart, blockEndExclusive)；只匹配精确文本，writeLock 的锁块不会混进来。 */
    private fun findSyncBlockSpans(body: String, marker: String): List<Pair<Int, Int>> {
        val spans = ArrayList<Pair<Int, Int>>()
        var search = 0
        while (true) {
            val at = body.indexOf(marker, search)
            if (at < 0) return spans
            val open = body.indexOf('{', at + marker.length)
            assertTrue("$marker 后面没有块", open >= 0)
            var depth = 0
            var i = open
            while (i < body.length) {
                when (body[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth == 0) break
                i++
            }
            spans += at to i + 1
            search = i + 1
        }
    }
}
