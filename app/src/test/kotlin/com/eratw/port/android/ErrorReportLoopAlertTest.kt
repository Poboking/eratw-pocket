package com.eratw.port.android

import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.proc.ProcessRuntime
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `--- 死循环告警 ---` 小节的形状与字段口径（loopreport，2026-11-12）。
 *
 * ## 钉的是什么
 *
 * 1. 一次告警都没有 ⇒ **整节不出现**，报告与改前**逐字节一致**（不是「没抛异常」就算过）；
 * 2. 有告警 ⇒ 快照字段逐项进报告（src / line / elapsed / lines / thr / nth / bg / 退避），
 *    块落在 `--- java ---` 之后（即 `[ERB 调用栈与 VM 现场]` 小节的尾部）；
 * 3. 两次告警只留**最新**一份快照，`nth` 随之递增、`退避` 如实翻面；
 * 4. 告警块占的行数从整节帧预算里扣掉，`--- erb ---` 标题不会被渲染层的
 *    `takeLast(STACK_MAX_LINES)` 挤出头部。
 *
 * ## 为什么接线用源码哨兵
 *
 * 宿主要 Android Context 才能构造，而 app 模块的单测只有 JUnit4（没有 Robolectric /
 * Mockito）⇒「快照字段是不是 @Volatile 单槽」「赋值点是不是唯一且在 loopalert 轨迹钉点旁」
 * 这两件只能在源码上钉。纪律与 ErrorReportErbStackTest 同源：桌面闸认证不了设备时序，
 * 就静态补位。
 */
class ErrorReportLoopAlertTest {

    // ---- 夹具 ----------------------------------------------------------

    private fun alert(
        source: String? = "ERB/SUB/LOOP.ERB",
        function: String? = "LOOPFUNC",
        lineNo: Long = 123L,
        lines: Long = 10_001L,
        elapsedMs: Long = 180_012L,
        alertTimeMs: Long = 180_000L,
        attempt: Int = 1,
        backgroundMs: Long = 0L,
    ): InfiniteLoopAlert = InfiniteLoopAlert(
        sourceName = source,
        functionName = function,
        lineNo = lineNo,
        linesExecuted = lines,
        elapsedMs = elapsedMs,
        alertTimeMs = alertTimeMs,
        attempt = attempt,
        backgroundMs = backgroundMs,
    )

    private fun frame(
        function: String?,
        line: Int,
        pc: Int,
        caller: String?,
        callLine: Int,
        callPc: Int,
        index: Int = 0,
    ): ProcessRuntime.ErbFrame = ProcessRuntime.ErbFrame(
        functionIndex = index,
        function = function,
        line = line,
        pc = pc,
        callSiteFunction = caller,
        callSiteLine = callLine,
        callSitePc = callPc,
    )

    private val javaTop = listOf("  at com.eratw.port.android.MainActivity.run:1")

    /** 走与生产同一条渲染路径：`[ERB 调用栈与 VM 现场]` 的整节就是 vmStackLines 那张表。 */
    private fun report(vmStack: List<String>): String = ErrorReport(
        kind = "脚本运行报错",
        timestampText = "2026-11-12 00:00:00",
        errorType = "com.eratw.port.core.err.StepLimitExceeded",
        errorMessage = "疑似无限循环（玩家选择退出）",
        vmStackLines = vmStack,
    ).render()

    // ---- 判据① 无告警：整节不出现，字节不变 ------------------------------

    @Test
    fun `无告警时整节不出现且报告与改前逐字节一致`() {
        val frames = (1..70).map { frame("F$it", 0, it, null, -1, -1, index = it) }
        // 改前：帧预算只扣两个段标题与 java 段。改后：再扣死循环告警块（无告警 ⇒ 0 行）。
        val beforeSection = ErbStack.sectionLines(
            ErbStack.frameLines(frames, maxOf(1, ErrorReport.STACK_MAX_LINES - 2 - javaTop.size)),
            javaTop,
        )
        val afterSection = ErbStack.sectionLines(
            ErbStack.frameLines(frames, ErrorReportCollector.vmSectionFrameBudget(javaTop.size, LoopAlertReport.lines(null).size)),
            javaTop,
        ) + LoopAlertReport.lines(null)

        assertEquals("无告警 ⇒ 整节逐字节等于改前", beforeSection, afterSection)
        val before = report(beforeSection)
        val after = report(afterSection)
        assertEquals("报告必须逐字节一致（不是「没抛异常」就算过）", before, after)
        assertFalse("一次告警都没有 ⇒ 整节不出现（连标题也不许留）", after.contains(LoopAlertReport.MARK))
        assertTrue("既有 erb 段必须原样还在", after.contains(ErbStack.ERB_MARK))
        assertTrue("既有 java 段必须原样还在", after.contains(ErbStack.JAVA_MARK))
        assertTrue("报告必须完整收尾", after.contains("报告结束"))
        assertTrue("告警块自身必须是空表", LoopAlertReport.lines(null).isEmpty())
    }

    // ---- 判据② 一次告警：字段齐全且位置在 java 段之后 ---------------------

    @Test
    fun `一次告警时字段齐全且块排在java段之后`() {
        val loop = LoopAlertReport.lines(alert())
        assertEquals("首行必须是固定标题", LoopAlertReport.MARK, loop.first())
        assertEquals(
            "正文必须八行（src/line/elapsed/lines/thr/nth/bg/退避）",
            LoopAlertReport.FIELD_LINES + 1,
            loop.size,
        )
        assertEquals(
            "整块形状（字段名与次序就是报告契约，漂移即返工）",
            listOf(
                "--- 死循环告警 ---",
                "src=ERB/SUB/LOOP.ERB",
                "line=行122（物理行123）",
                "elapsed=180012ms",
                "lines=10001",
                "thr=180000ms",
                "nth=1",
                "bg=0ms",
                "退避=未翻倍",
            ),
            loop,
        )
        assertTrue("文件名必须原样带出：$loop", loop.contains("src=ERB/SUB/LOOP.ERB"))
        assertTrue(
            "行号要同时给 0 基与物理行（快照是原版正文口径的 1 基 123）",
            loop.contains("line=行122（物理行123）"),
        )
        assertTrue(loop.contains("elapsed=180012ms"))
        assertTrue(loop.contains("lines=10001"))
        assertTrue(loop.contains("thr=180000ms"))
        assertTrue(loop.contains("nth=1"))
        assertTrue(loop.contains("bg=0ms"))
        assertTrue(loop.contains("退避=" + LoopAlertReport.NOT_ESCALATED))

        val section = ErbStack.sectionLines(
            ErbStack.frameLines(
                listOf(frame("INNER", 1, 42, null, -1, -1)),
                ErrorReportCollector.vmSectionFrameBudget(javaTop.size, loop.size),
            ),
            javaTop,
        ) + loop
        val rendered = report(section).lines()
        val java = rendered.indexOf(ErbStack.JAVA_MARK)
        val mark = rendered.indexOf(LoopAlertReport.MARK)
        assertTrue("告警块必须排在 --- java --- 之后：$rendered", java >= 0 && mark > java)
        assertFalse(
            "块之后不许再出现 erb 段（位置口径 = 本节最尾）",
            rendered.drop(mark).any { it == ErbStack.ERB_MARK },
        )
        assertTrue("字段要原样落进整份报告：$rendered", rendered.contains("src=ERB/SUB/LOOP.ERB"))
        assertEquals("块内每一行都要原样落进报告（不许被 clip 截断）", loop, rendered.subList(mark, mark + loop.size))
    }

    // ---- 判据③ 两次告警：只留最新、nth 递增 ------------------------------

    @Test
    fun `两次告警只留最新一份且nth递增退避翻面`() {
        // 与宿主那份快照同一条纪律：单槽覆盖写，第二份顶掉第一份。
        // 单槽本身（@Volatile + 唯一赋值点）由本文件末尾的接线哨兵钉住。
        var slot: InfiniteLoopAlert? = null
        slot = alert(source = "ERB/FIRST.ERB", attempt = 1, alertTimeMs = 180_000L)
        slot = alert(
            source = "ERB/SECOND.ERB",
            attempt = 2,
            alertTimeMs = 360_000L,
            lines = 20_002L,
            elapsedMs = 360_020L,
        )
        val loop = LoopAlertReport.lines(slot)
        assertTrue("只留最新一份的文件名：$loop", loop.contains("src=ERB/SECOND.ERB"))
        assertFalse("上一问的现场不许残留", loop.any { it.contains("FIRST") })
        assertTrue("nth 必须递增到第二问", loop.contains("nth=2"))
        assertTrue("第二问的生效阈值是翻倍后的 360000ms", loop.contains("thr=360000ms"))
        assertTrue(loop.contains("lines=20002"))
        assertTrue("nth>1 ⇒ 退避翻面", loop.contains("退避=" + LoopAlertReport.ESCALATED))
        assertEquals("行数不随告警次数变化", LoopAlertReport.FIELD_LINES + 1, loop.size)
    }

    // ---- 判据④ 后台暂停期间触发：bg>0 如实出现 ---------------------------

    @Test
    fun `后台暂停期间触发时bg大于零如实出现`() {
        val loop = LoopAlertReport.lines(alert(backgroundMs = 4_500L))
        assertTrue("bg 是已从段落墙钟里扣掉的后台毫秒，原值直出：$loop", loop.contains("bg=4500ms"))
        assertFalse("不许换算成秒", loop.any { it.contains("bg=4.5") || it.contains("bg=4500s") })
        assertTrue(
            "0 也是读数，该行不许省",
            LoopAlertReport.lines(alert(backgroundMs = 0L)).contains("bg=0ms"),
        )
        assertTrue("同一份现场里 elapsed 仍是段落墙钟，两者可交叉读", loop.contains("elapsed=180012ms"))
    }

    // ---- 取不到的字段：整行不写 ------------------------------------------

    @Test
    fun `源文件名与函数名都取不到时只有src那一行不写`() {
        val loop = LoopAlertReport.lines(alert(source = null, function = null))
        assertFalse("src 取不到就不写该行，不拿占位符顶：$loop", loop.any { it.startsWith("src=") })
        assertEquals("其余七行照旧", LoopAlertReport.FIELD_LINES, loop.size)
        assertEquals(LoopAlertReport.MARK, loop.first())
    }

    // ---- 形状：告警块占的行从帧预算里扣掉 --------------------------------

    @Test
    fun `告警块从整节帧预算里扣掉且erb标题不被挤出`() {
        val loop = LoopAlertReport.lines(alert())
        val top = (1..8).map { "  at F$it.run:$it" }
        val budget = ErrorReportCollector.vmSectionFrameBudget(top.size, loop.size)
        assertEquals(
            "预算 = 上限 − 两个段标题 − java 段 − 告警块",
            ErrorReport.STACK_MAX_LINES - 2 - top.size - loop.size,
            budget,
        )
        val frames = (1..200).map { frame("F$it", 0, it, null, -1, -1, index = it) }
        val section = ErbStack.sectionLines(ErbStack.frameLines(frames, budget), top) + loop
        assertEquals("整节必须正好卡在渲染层 takeLast 上限上", ErrorReport.STACK_MAX_LINES, section.size)

        val rendered = report(section).lines()
        val erb = rendered.indexOf(ErbStack.ERB_MARK)
        assertTrue("--- erb --- 标题必须留在头部：$rendered", erb > 0)
        assertTrue("告警块不许把 java 段挤掉", rendered.indexOf(ErbStack.JAVA_MARK) > erb)
        assertTrue("告警块要在本节最尾", rendered.indexOf(LoopAlertReport.MARK) > rendered.indexOf(ErbStack.JAVA_MARK))
        assertTrue("帧被截断时要留省略说明", rendered.any { it.contains("余下省略") })
        // 无告警时不多扣：预算回到改前那一条式子
        assertEquals(maxOf(1, ErrorReport.STACK_MAX_LINES - 2 - top.size), ErrorReportCollector.vmSectionFrameBudget(top.size, 0))
    }

    // ---- 接线哨兵 --------------------------------------------------------

    @Test
    fun 宿主快照是volatile单槽且唯一赋值点紧挨loopalert轨迹钉点() {
        val code = stripComments(source("GameHost.kt"))
        assertTrue(
            "快照必须是非空可写的 @Volatile 单槽（UI 线程写、报告线程读）",
            Regex("@Volatile\\s+internal var lastInfiniteLoopAlert\\s*:\\s*InfiniteLoopAlert\\?\\s*=\\s*null")
                .containsMatchIn(code),
        )
        val lines = code.lines()
        val assignments = lines.withIndex().filter { it.value.contains("lastInfiniteLoopAlert =") }
        assertEquals("快照只能有一个赋值点（第二处就是第二套写入纪律）", 1, assignments.size)
        val assignAt = assignments.single().index
        val fnAt = lines.indexOfFirst { it.contains("onInfiniteLoopSeen = { activity, alert ->") }
        val nailAt = lines.indexOfFirst { it.contains("LoopAlertReport.trailLine(alert)") }
        assertTrue("赋值点必须落在死循环弹窗接线钩子里（UiPromptCoordinator 回调）", fnAt in 0 until assignAt)
        assertTrue("赋值点必须在 loopalert 轨迹钉点旁（同一次告警、同一现场）", abs(nailAt - assignAt) <= 8)
        assertTrue(
            "赋值必须是单槽覆盖写：" + lines[assignAt],
            lines[assignAt].contains("lastInfiniteLoopAlert = alert"),
        )
    }

    @Test
    fun loopalert轨迹行格式一字未动() {
        // 钉的是**输出字节面**而不是实现语法：宿主侧无论用拼接还是插值，真机取证
        // 对读的都是这一行的输出。字段名与次序一个字都不许动（loopguard3 反向证明）。
        val alert = InfiniteLoopAlert(
            sourceName = "ERB/DEBUG.ERB",
            functionName = null,
            lineNo = 582L,
            linesExecuted = 56789L,
            elapsedMs = 123456L,
            alertTimeMs = 180000L,
            attempt = 2,
            backgroundMs = 4000L,
        )
        val line = LoopAlertReport.trailLine(alert)
        assertEquals(
            "轨迹行已被 loopguard3 的反向证明钉住：字段名与次序一个字都不许动",
            "loopalert src=ERB/DEBUG.ERB line=582 elapsed=123456ms lines=56789 thr=180000 nth=2 bg=4000ms",
            line,
        )
        assertFalse("轨迹行不许改走结构化小节", line.contains("已迁到报告小节"))
    }

    @Test
    fun 采集器从宿主单槽快照接线且预算扣掉告警块() {
        val code = stripComments(source("ErrorReportCollector.kt"))
        assertTrue("必须读宿主那份单槽快照", code.contains("LoopAlertReport.lines(host?.lastInfiniteLoopAlert)"))
        assertTrue(
            "帧预算要扣掉告警块，否则 takeLast 会把 --- erb --- 标题挤掉",
            Regex("vmSectionFrameBudget\\(javaTop\\.size, loopAlert\\.size\\)").containsMatchIn(code),
        )
        assertTrue(
            "告警块必须拼在 erb/java 两段之后",
            Regex("return ErbStack\\.sectionLines\\(frames, javaTop\\) \\+ loopAlert").containsMatchIn(code),
        )
        assertTrue("宿主为 null 时交回告警块（空表）而不是抛", code.contains("if (host == null) return loopAlert"))
        assertTrue("告警块的字段口径只有一处实现", code.contains("object LoopAlertReport"))
    }

    // ---- 源码读取 --------------------------------------------------------

    /** 只看代码行：KDoc 里当然要写轨迹行的口径，那不是违例。 */
    private fun stripComments(text: String): String = text.lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }.joinToString("\n")

    /** 取模块源码（工作目录按 AGP 单测惯例是本模块目录）。 */
    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/" + name),
            File("../app/src/main/kotlin/com/eratw/port/android/" + name),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 " + name + "（试了 " + candidates.joinToString { it.path } + "）")
        return file.readText(Charsets.UTF_8)
    }
}
