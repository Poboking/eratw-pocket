package com.eratw.port.android

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopTexts
import com.eratw.port.core.code.LoopGuardCause
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.lexer.LangVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [UiFaultBreaker] / [UiFaultLog] 的口径：主线程「这一帧出错」既不能杀进程，也不能
 * 变成每帧重试把 CPU 烧光的死循环。
 *
 * 这些断言锁的是 2026-10-31 稳定性复查补的两条判据：
 * 1. **日志限流**：第一次必须拿到全栈（那是唯一的取证面），之后按固定节奏打计数，
 *    否则 60 fps 下一帧一崩会把 logcat 刷满、把真正的第一条栈冲掉；
 * 2. **连续失败熔断**：连续 [UiFaultBreaker.DEFAULT_THRESHOLD] 次才冷却（偶发一次不该
 *    停循环），冷却时长逐轮翻倍并封顶，成功一次完全复位（自愈后不留旧账）。
 *
 * 为什么是纯 JVM 单测：本类不读时钟（时间基准由调用方给），判据可以逐毫秒钉死；
 * 真正的「吞异常后继续画」由 [CanvasConsoleView] 的绘制兜底与 `MainActivity.pump`
 * 的分段兜底承担，那两条在真机 logcat 上验。
 */
class UiFaultTest {

    // ------------------------------------------------------------------
    // 日志限流
    // ------------------------------------------------------------------

    /** 第一次永远是全栈：崩溃现场只有这一份，不能被限流吞掉。 */
    @Test
    fun `首次故障打完整栈`() {
        assertEquals(UiFaultLogMode.FULL, UiFaultLog.mode(1L))
    }

    /** 第 2 次起静默，直到第 120 次才打一行计数。 */
    @Test
    fun `限流区间内静默`() {
        assertEquals(UiFaultLogMode.SILENT, UiFaultLog.mode(2L))
        assertEquals(UiFaultLogMode.SILENT, UiFaultLog.mode(119L))
    }

    /** 每 120 次一行汇总，且 120 的整数倍都命中（不是只命中第一次）。 */
    @Test
    fun `每百二十次打一行计数`() {
        assertEquals(UiFaultLogMode.SHORT, UiFaultLog.mode(120L))
        assertEquals(UiFaultLogMode.SHORT, UiFaultLog.mode(240L))
        assertEquals(UiFaultLogMode.SHORT, UiFaultLog.mode(1200L))
    }

    /** 节奏常量本身钉死：改成别的数就算行为变化，必须有人重新看一眼。 */
    @Test
    fun `限流节奏是 120`() {
        assertEquals(120L, UiFaultLog.EVERY)
        // 119 / 121 都不该命中，否则「每 120 次」就成了「每 120±1 次」。
        assertEquals(UiFaultLogMode.SILENT, UiFaultLog.mode(121L))
        assertEquals(UiFaultLogMode.SILENT, UiFaultLog.mode(239L))
    }

    // ------------------------------------------------------------------
    // 连续失败熔断
    // ------------------------------------------------------------------

    /** 阈值前一次都不熔断：偶发一帧出错不该停掉重绘循环。 */
    @Test
    fun `阈值前不熔断`() {
        val breaker = UiFaultBreaker(threshold = 8)
        repeat(7) { i ->
            assertFalse("第 ${i + 1} 次不该熔断", breaker.recordFailure(i.toLong()))
            assertTrue(breaker.shouldRun(i.toLong()))
        }
        assertEquals(7L, breaker.failures)
        assertEquals(7, breaker.consecutive)
    }

    /** 第 8 次熔断：冷却期内 shouldRun 为假，到点自动放行（自愈靠这一下）。 */
    @Test
    fun `第八次熔断并按点放行`() {
        val breaker = UiFaultBreaker(threshold = 8, baseCooldownMs = 2_000L, maxCooldownMs = 8_000L)
        val now = 10_000L
        repeat(7) { breaker.recordFailure(now) }
        assertTrue(breaker.recordFailure(now))
        assertEquals(now + 2_000L, breaker.cooldownUntilMs)
        assertFalse(breaker.shouldRun(now))
        assertFalse(breaker.shouldRun(now + 1_999L))
        assertTrue("冷却到点必须放行探针帧", breaker.shouldRun(now + 2_000L))
    }

    /** 冷却时长逐轮翻倍并封顶：2 s → 4 s → 8 s → 8 s。 */
    @Test
    fun `冷却逐轮翻倍并封顶`() {
        val breaker = UiFaultBreaker(threshold = 2, baseCooldownMs = 2_000L, maxCooldownMs = 8_000L)

        // 第一轮：2 s，下一轮档位抬到 4 s。
        breaker.recordFailure(0L)
        assertTrue(breaker.recordFailure(10L))
        assertEquals(2_010L, breaker.cooldownUntilMs)
        assertEquals(4_000L, breaker.cooldownMs)

        // 第二轮：4 s（截止 = 2020 + 4000），下一轮档位抬到 8 s。
        breaker.recordFailure(2_010L)
        assertTrue(breaker.recordFailure(2_020L))
        assertEquals(6_020L, breaker.cooldownUntilMs)
        assertEquals(8_000L, breaker.cooldownMs)

        // 第三轮：8 s，档位封顶不再涨（不能无限翻倍成「永久停摆」）。
        breaker.recordFailure(6_020L)
        assertTrue(breaker.recordFailure(6_030L))
        assertEquals(14_030L, breaker.cooldownUntilMs)
        assertEquals(8_000L, breaker.cooldownMs)
    }

    /** 成功一次 = 完全复位：连续计数、冷却截止与档位全部回到初值。 */
    @Test
    fun `成功一次完全复位`() {
        val breaker = UiFaultBreaker(threshold = 2, baseCooldownMs = 2_000L, maxCooldownMs = 8_000L)
        breaker.recordFailure(0L)
        breaker.recordFailure(1L)
        breaker.recordFailure(2L)
        breaker.recordFailure(3L)
        assertEquals(8_000L, breaker.cooldownMs)

        breaker.recordSuccess()
        assertEquals(0, breaker.consecutive)
        assertEquals(0L, breaker.cooldownUntilMs)
        assertEquals(2_000L, breaker.cooldownMs)
        assertTrue(breaker.shouldRun(0L))
        // 累计次数是诊断读数，只增不减 —— 复位的是「连续」，不是历史。
        assertEquals(4L, breaker.failures)
    }

    /** 熔断后重新计数：冷却放行再连续失败，要重新攒满阈值才再熔断一次。 */
    @Test
    fun `熔断后重新攒阈值`() {
        val breaker = UiFaultBreaker(threshold = 3)
        breaker.recordFailure(0L)
        breaker.recordFailure(0L)
        assertTrue(breaker.recordFailure(0L))
        assertEquals(0, breaker.consecutive)
        assertFalse(breaker.recordFailure(0L))
        assertFalse(breaker.recordFailure(0L))
        assertTrue(breaker.recordFailure(0L))
    }

    // ------------------------------------------------------------------
    // 启动异常的三条出口
    // ------------------------------------------------------------------

    /**
     * 会话已经被界面接管时必须走「重排循环」。
     *
     * 反例的代价是玩家最不能接受的那一种：`unpackAndStart` 已经把 `host` 交出去、
     * `start()` 也成功了，只是后面排重绘循环那一步抛了 —— 这时按「启动失败」去 close，
     * 等于把一局好游戏打死，还配一张文案相反的屏。
     */
    @Test
    fun `会话已接管时只重排循环不报失败`() {
        assertEquals(
            StartupFaultAction.RESUME_PUMP,
            startupFaultAction(adopted = true, destroyed = false),
        )
        // 接管优先：即使 Activity 同时被判销毁，也不该反过来去 close 一局活的会话
        // （onDestroy 会自己收口；这里再 close 是重复动作，且会打断它正在走的清场）。
        assertEquals(
            StartupFaultAction.RESUME_PUMP,
            startupFaultAction(adopted = true, destroyed = true),
        )
    }

    /** 没接管 + Activity 还活着：关宿主、上失败屏（否则屏上永远停在「正在检查…」）。 */
    @Test
    fun `未接管且界面还在时关宿主并上失败屏`() {
        assertEquals(
            StartupFaultAction.CLOSE_AND_FAIL,
            startupFaultAction(adopted = false, destroyed = false),
        )
    }

    /** 没接管 + Activity 已销毁（解包几十秒里按了返回）：只关宿主，不上屏。 */
    @Test
    fun `未接管且界面已销毁时静默关宿主`() {
        assertEquals(
            StartupFaultAction.CLOSE_SILENTLY,
            startupFaultAction(adopted = false, destroyed = true),
        )
    }

    // ------------------------------------------------------------------
    // 失败屏按族讲（2026-11-01 玩家截图：中局中止被说成「包坏了」）
    // ------------------------------------------------------------------

    /**
     * 真机读数（收件箱 `eratw/2026-11-01/1793510851994-…-132730.txt`）：那位玩家玩到
     * 春之月 8 日、指令菜单都画完了，状态机终局之后屏上是
     * 「启动失败」+「随包 APK 正常不该走到这里：这一句就是原因（磁盘满 / assets 坏 /
     * program 与 meta 不配对）」+「若反复失败请检查产物目录」。三句全部指向随包数据，
     * 而真凶是装载完好的一次分配失败 —— 表此刻还是好的（同一份报告的屏上原文里有
     * `＜上回指令:散步＞`，那句出自语料直接读 `TRAINNAME:604` 的 `@TRAINNAME_TR`）。
     */
    @Test
    fun `中局终局不叫启动失败也不提产物`() {
        assertEquals("游戏出错", failureScreenTitle(FailureScreenKind.IN_GAME))
        assertEquals(emptyList<String>(), failureScreenArtifactLines(FailureScreenKind.IN_GAME, "/files/erbc.v1"))
        val note = failureScreenNote(FailureScreenKind.IN_GAME, canBackToTitle = false)
        assertFalse("不许把玩家支去翻产物目录", note.contains("产物"))
        assertTrue(note.contains("存档在盘上"))
    }

    /** 装载两族的标题不变（这一局确实没起来，「启动失败」是实话）。 */
    @Test
    fun `装载两族仍叫启动失败`() {
        assertEquals("启动失败", failureScreenTitle(FailureScreenKind.ARTIFACT))
        assertEquals("启动失败", failureScreenTitle(FailureScreenKind.LOAD))
    }

    /**
     * 「磁盘满 / assets 坏 / program 与 meta 不配对」这一句是**产物族的成因清单**，
     * 只在产物族出现；堆不足那张屏有它自己的兼容档出口，提产物同样是误导。
     * 产物族那三行原文逐字不动（在册取证与玩家群里的对照串靠它）。
     */
    @Test
    fun `产物三行只在产物族给`() {
        val artifact = failureScreenArtifactLines(FailureScreenKind.ARTIFACT, "/root/erbc.v1")
        assertEquals(3, artifact.size)
        assertEquals("产物根: /root/erbc.v1", artifact[0])
        assertEquals(
            "随包 APK 正常不该走到这里：这一句就是原因（磁盘满 / assets 坏 / program 与 meta 不配对）。",
            artifact[1],
        )
        assertEquals(
            "开发调试通道才需要 adb：tools/apkpack/push_game_data.ps1 -Dev 会把数据直推到上面那个目录。",
            artifact[2],
        )
        // 装载族只留路径一行，不带那句归因。
        assertEquals(listOf("产物根: /root/erbc.v1"), failureScreenArtifactLines(FailureScreenKind.LOAD, "/root/erbc.v1"))
    }

    /** 能回标题时那一句三族同形（两族此前已在册，此处不许因为分族而改动一字）。 */
    @Test
    fun `可回标题时说明句三族同形`() {
        val expected =
            "「回到标题」在原会话上复位后重进标题（不清存档、不重新装载）；「重试」关闭当前会话并重新装载。"
        for (kind in FailureScreenKind.values()) {
            assertEquals(kind.name, expected, failureScreenNote(kind, canBackToTitle = true))
        }
    }

    /** 装载族「检查产物目录」那句保持原样 —— 分族只收窄，不改动说得对的那一支。 */
    @Test
    fun `装载族不能回标题时仍提示产物目录`() {
        val expected = "重试会关闭当前会话并重新装载；若反复失败请检查产物目录。"
        assertEquals(expected, failureScreenNote(FailureScreenKind.ARTIFACT, canBackToTitle = false))
        assertEquals(expected, failureScreenNote(FailureScreenKind.LOAD, canBackToTitle = false))
    }

    // ------------------------------------------------------------------
    // 逃出来的异常：哪一类按「可恢复的脚本错误终局」收
    // ------------------------------------------------------------------

    /**
     * 语句级脚本错误按可恢复终局收。
     *
     * 钉的是 2026-11-02 端点收件箱里最大的一簇：`RAND不能指定0以下的值(0)`
     * `@ QOL_GATHERING_DRAWING 行69`（0.2.34 一台 vivo 连报 28 份）、`除数为 0`、
     * `变量 EXPLV 的第 1 维下标 5002 越界`、`XML_GET函数:""解析错误:缺少根元素` ——
     * 全是 `CodeEE`，原版由 DoScript 顶层 catch 打屏后**正常返回**
     * （`Process.cs:438-478` + `:595-661`），会话留着走 `GotoTitle` 复活；
     * 移植侧此前把它们升成「启动失败」并关了会话（复活按钮随之熄灭）。
     */
    @Test
    fun `语句级脚本错误是可恢复终局`() {
        assertTrue(isRecoverableScriptError(CodeEE("RAND不能指定0以下的值(0)", "QOL_Gathering_Drawing", 70)))
        assertTrue(isRecoverableScriptError(CodeEE("除数为 0")))
    }

    /**
     * 玩家在死循环告警框里按「是」：原版抛的就是一条普通 `CodeEE`
     * （skia `Process.cs:482-484` / 官方 reference `Process.cs:505-507`，方法体 `:493-512`），
     * 由 DoScript 顶层 catch 打屏后正常返回（官方 `Process.cs:462-478` → `:595-661`），
     * 会话留着、能回标题。0.2.42+18 / HONOR LGN-NX1 的报告原文是「报告类型：启动失败」+
     * 类型 `StepLimitExceeded` + 信息「可能进入了无限循环因此选择强制终止」+
     * 脚本位置 `BASE自然変動`，异常栈落在 `Vm.infiniteLoopExitAt`。
     *
     * 反向证明：同一个异常类型只把成因换回移植层兜底 ⇒ 仍按启动失败收口（下面最后一断言）。
     */
    @Test
    fun `死循环告警里玩家选退出是可恢复终局`() {
        val exit = StepLimitExceeded(
            steps = 12_000_000L,
            line = 4719,
            pc = 404,
            funcName = "BASE自然変動",
            elapsedMs = 5_000L,
            guardCause = LoopGuardCause.PlayerChoseExit,
        )
        assertTrue(
            "玩家主动退出按官方脚本错误收口：留会话、能回标题，不许上启动失败屏",
            isRecoverableScriptError(exit),
        )
        assertEquals(
            "没带告警现场时文案回落使用者面简中（Errors.kt:310-312）",
            InfiniteLoopTexts.EXIT_ZHS,
            exit.message,
        )
        val budget = StepLimitExceeded(
            steps = 1_000_000_001L,
            line = 4719,
            pc = 404,
            funcName = "BASE自然変動",
            guardCause = LoopGuardCause.DeviceBudget,
        )
        assertFalse(
            "10 亿步兜底不是官方语义，现场转储仍挂装载失败收口",
            isRecoverableScriptError(budget),
        )
    }

    /**
     * 生产抛点（`Vm.kt:917-927`）带的告警现场与语言档都不参与成因分流：日文框里按「是」
     * 时 message 是日文原文，仍必须按可恢复终局收 —— 判据只读 [LoopGuardCause]，
     * 不许退化成按文案串匹配语言。
     */
    @Test
    fun `成因分流与告警现场及语言档无关`() {
        val ja = StepLimitExceeded(
            steps = 12_000_000L,
            line = 4719,
            pc = 404,
            funcName = "BASE自然変動",
            callChain = "EVENTTRAIN → CHARA_MOVEMENT → MOVEMENT共通処理 → BASE自然変動",
            frameSnapshot = listOf("LOCAL:0 = 0"),
            elapsedMs = 5_000L,
            guardCause = LoopGuardCause.PlayerChoseExit,
            alert = InfiniteLoopAlert(
                sourceName = "ERB/MOVEMENTS/BASE自然変動.ERB",
                functionName = "BASE自然変動",
                lineNo = 4720L,
                linesExecuted = 10_000L,
                elapsedMs = 5_000L,
                alertTimeMs = 5_000L,
                textVariant = LangVariant.JA,
            ),
        )
        assertEquals("日文告警框答「是」⇒ 屏上那句是日文原文", InfiniteLoopTexts.EXIT_JA, ja.message)
        assertTrue(
            "语言档与现场字段都不改变成因：仍按玩家主动退出收口",
            isRecoverableScriptError(ja),
        )
    }

    /** 装置预算与爆栈闸不走这条路：死循环现场转储挂在装载失败那道收口上。 */
    @Test
    fun `装置预算与爆栈闸仍按启动失败收口`() {
        assertFalse(
            "步数闸（疑似死循环）不许折成可恢复终局",
            isRecoverableScriptError(StepLimitExceeded(steps = 1_000_000_000L, line = 21, pc = 404, funcName = "SYSTEM_TITLE")),
        )
        assertFalse(
            "调用栈闸同上",
            isRecoverableScriptError(CallStackOverflow(depth = 256, line = 12, pc = 88, funcName = "再帰関数")),
        )
    }

    /** 非脚本错误不动既有收口：把 OOM 与宿主 bug 藏成一屏「游戏出错」比杀掉更糟。 */
    @Test
    fun `非脚本错误一律不折`() {
        assertFalse(isRecoverableScriptError(OutOfMemoryError("Failed to allocate a 1000016 byte allocation")))
        assertFalse(isRecoverableScriptError(IllegalStateException("trying to use a recycled bitmap")))
    }

    // ------------------------------------------------------------------
    // 「回到标题」那条复活线程的关闭闸（2026-11-05 lane errterminal，G1）
    // ------------------------------------------------------------------

    /**
     * 宿主已 close() ⇒ 抑制。
     *
     * 复活途中被关时 `gotoTitle()` 撞 VM 中断检查点抛「执行被中断（宿主正在关闭）」
     * （`Vm.kt:732-746`），会话已关时等待抛 `InputSinkClosedException`（`QueueInputSink.kt:11`），
     * 两条都是玩家正常退出的路径 —— 按既有形状就会换屏 + 落一份「回到标题失败」报告，
     * 把退出折成缺陷上报（收件箱 2026-11-03 一天 6 份「关闭竞态」的同形）。
     */
    @Test
    fun `复活途中宿主已关闭时抑制屏与报告`() {
        assertEquals(BackToTitleFaultAction.SUPPRESS_BY_CLOSE, backToTitleFaultAction(closed = true))
    }

    /** 宿主仍活着的失败是真事故：屏与报告一条都不许多、一条都不许少。 */
    @Test
    fun `复活途中宿主仍活时照上屏照落报告`() {
        assertEquals(BackToTitleFaultAction.FAIL_AND_REPORT, backToTitleFaultAction(closed = false))
    }

    /**
     * 源码面：闸必须排在 `writeErrorReport` **之前**，且抑制支一个字都不许写屏。
     *
     * 判据函数本身绿不代表接线对 —— 接线写反（先落报告再问闸）行为照旧，所以钉在生产源码上。
     * 先例：`ShellUiFailureActionsTest`（同族「只由一处判」的钉子）与 `LiveReflowWiringTest`
     * 的 `codeLines("GameHost.kt")` 系列。取的是**代码行**（注释与 KDoc 已丢），措辞改动不影响。
     */
    @Test
    fun `回标题失败的关闭闸排在落报告之前`() {
        val host = codeLines("GameHost.kt")
        val gate = host.indexOf("backToTitleFaultAction(closed)")
        assertTrue("backToTitle 的工作线程 catch 必须先问关闭闸（找不到 = 闸被删）", gate >= 0)
        assertEquals("闸只在这一处接线（别处再问一次就是两处口径）", 1, host.countOccurrences("backToTitleFaultAction(closed)"))
        val report = host.indexOf("writeErrorReport(\"回到标题失败\"")
        assertTrue("抑制支之后仍要给活着的宿主留报告：找不到那发 writeErrorReport", report >= 0)
        assertTrue("顺序就是本条的全部：闸必须排在落报告之前（gate=$gate report=$report）", gate < report)
        // 抑制支（闸到 `else` 之间）只许打日志：换屏、写 failure、落报告三件事一件都不许多。
        val elseAt = host.indexOf("} else {", gate)
        assertTrue("抑制支与失败支的分界必须在闸之后", elseAt > gate)
        val suppressBranch = host.substring(gate, elseAt)
        assertFalse("抑制支不许换失败屏：$suppressBranch", suppressBranch.contains("lastFailureScreenKind"))
        assertFalse("抑制支不许写 failure：$suppressBranch", suppressBranch.contains("failure ="))
        assertFalse("抑制支不许落报告：$suppressBranch", suppressBranch.contains("writeErrorReport"))
        // 同函数另一处 catch（worker.start() 失败支）与 close 无关，刻意不加闸：它压根没起线程。
        assertEquals(
            "「回到标题失败」的报告全文只此一处",
            1,
            host.countOccurrences("writeErrorReport(\"回到标题失败\""),
        )
    }

    // ------------------------------------------------------------------
    // 报告「补充现场」的剪行次序（2026-11-05 lane errterminal，D4）
    // ------------------------------------------------------------------

    /**
     * 现场行垫到块尾，节流账面让到块头。
     *
     * core 侧那一节是**尾部保留**：`ErrorReport.kt:161-164` 的 `takeLast(EXTRA_MAX_LINES)`
     * （窗口 = 80，见 `:201` 的常量与 `:190-200` 的 KDoc 记账），而采集侧
     * （`ErrorReportCollector.collectFull` 的 `extraSectionLines`）还要在宿主交给它的这块**之后**
     * 再垫内存读数 / 约会现场 / 存档留痕 / 心跳 / 冻结现场 / 守护死因 ⇒ 交进去这块整体排在最前，
     * 满档时从它的**头部**开始剪。中止点与证据这类现场行没有第二个出口（屏会被 `gotoTitle`
     * 清掉、logcat 玩家读不到），被剪就永久没了；节流账面另有运行轨迹与 logcat 两个出口，
     * 让它先走。`writeErrorReport` 自己的 KDoc 记的就是这笔账。
     */
    @Test
    fun `现场行垫在节流账面之后`() {
        val host = codeLines("GameHost.kt")
        assertTrue(
            "交给报告的次序必须是「账面在前、现场行在末尾」",
            host.contains("suppressed + extraLines,"),
        )
        assertFalse("旧次序（现场行顶在最前 = 满档先被剪）不许回来", host.contains("extraLines + suppressed"))
        // 现场行本身一个字不改：改名会让本条与 ErrorReportThrottleTest 的签名判式一起失真。
        assertTrue("中止点行必须还在（判据原文不动）", host.contains("中止点：系统状态机"))
        assertTrue("脚本错误终局的证据行必须还在", host.contains("证据：Process.cs:438-478 / Process.cs:571-661"))
        // 剪行口径的锚点：窗口是尾部保留的 80，改成头部保留就等于本条判据作废。
        assertEquals(80, ErrorReport.EXTRA_MAX_LINES)
        // 没抑制过（首份报告）时账面是空表，`空 + 现场行` == 现场行：正文逐字不变（L6 纪律）。
        assertEquals(
            "空账面垫到前面也必须逐字等于只给现场行",
            listOf("中止点：x", "证据：y"),
            emptyList<String>() + listOf("中止点：x", "证据：y"),
        )
    }

    // ------------------------------------------------------------------
    // 取源码：工作目录随 gradle 入口变（口径同 LiveReflowWiringTest / StrictModeGuardTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：源码面钉子判的是接线，不是文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")

    private fun String.countOccurrences(needle: String): Int {
        var index = 0
        var count = 0
        while (true) {
            val at = indexOf(needle, index)
            if (at < 0) return count
            count++
            index = at + needle.length
        }
    }
}
