package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.ProcessRuntime
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `[ERB 调用栈与 VM 现场]` 的 `--- erb ---` 段形状与降级口径。
 *
 * ## 钉的是什么（2026-11-09 上报收口 lane bug-erbstack）
 *
 * 1. 有 ERB 帧时：`--- erb ---` 与 `--- java ---` 之间**一帧一行**（行数 = 帧数），
 *    最内帧 `#0` 在前；
 * 2. 每帧同时给 **0 基行**与**物理行**（`行N（物理行N+1）`）—— 上报口径是 0 基，
 *    不标注会被当物理行误读；
 * 3. 无帧时打一行 `（无：非 VM 线程或已回卷）`，报告仍能完整渲染（绝不吞小节）；
 * 4. 只有 CodeEE（含因果链）才允许回落抛点镜像；普通 RuntimeException 必须判空；
 * 5. 采集器只走 `runtime.erbFrameStack()`（运行期帧表），不许在崩溃线程碰
 *    `program.functions` 惰性解码面（同 `ErrorReportVmStackTest` 的源码哨兵）。
 */
class ErrorReportErbStackTest {

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

    // ---- 形状：一帧一行 ----

    @Test
    fun `有帧时erb段行数等于帧数且最内帧在前`() {
        val frames = listOf(
            frame("CALLEE", 1, 42, "CALLER", 1, 7, index = 11),
            frame("CALLER", 1, 7, null, -1, -1, index = 10),
        )
        val lines = ErbStack.frameLines(frames)
        assertEquals("一帧一行，实际：$lines", 2, lines.size)
        assertTrue("最内帧在 #0，实际：${lines[0]}", lines[0].startsWith("#0 CALLEE "))
        assertTrue("外层帧在 #1，实际：${lines[1]}", lines[1].startsWith("#1 CALLER "))

        val section = ErbStack.sectionLines(lines, listOf("  at Thread.run:1"))
        val erb = section.indexOf(ErbStack.ERB_MARK)
        val java = section.indexOf(ErbStack.JAVA_MARK)
        assertTrue("必须有 --- erb --- 固定标题", erb >= 0)
        assertTrue("必须有 --- java --- 固定标题", java > erb)
        assertEquals("--- erb --- 与 --- java --- 之间的行数 = 帧数", 2, java - erb - 1)

        val reportLines = ErrorReport(
            kind = "脚本运行报错",
            timestampText = "2026-11-09 00:00:00",
            errorType = "com.eratw.port.core.err.CodeEE",
            errorMessage = "RAND 的参数必须为正数（实际 0）",
            vmStackLines = section,
        ).render().lines()
        val reportErb = reportLines.indexOf(ErbStack.ERB_MARK)
        val reportJava = reportLines.indexOf(ErbStack.JAVA_MARK)
        assertTrue("整份报告里也要有 --- erb ---", reportErb >= 0)
        assertEquals("整份报告里 erb 段行数 = 帧数", 2, reportJava - reportErb - 1)
    }

    @Test
    fun `每帧同时标注零基行与物理行且带pc与调用点`() {
        val line = ErbStack.frameLines(
            listOf(frame("CALLEE", 1, 42, "CALLER", 1, 7)),
        ).single()
        assertTrue("0 基行与物理行必须同时出现：$line", line.contains("行1（物理行2）"))
        assertTrue("必须带抛错指令 pc：$line", line.contains("pc=42"))
        assertTrue("必须带调用方函数名：$line", line.contains("调用点 CALLER"))
        assertTrue("调用点行也双标注：$line", line.contains("调用点 CALLER 行1（物理行2） pc=7"))
    }

    @Test
    fun `根帧没有调用点时如实写根帧而不是伪造行号`() {
        val line = ErbStack.frameLines(listOf(frame("ENTRY", 0, 3, null, -1, -1))).single()
        assertTrue(line, line.contains("调用点 根帧"))
        assertFalse(line, line.contains("调用点 根帧 pc="))
    }

    @Test
    fun `行号未知时留问号不猜`() {
        val line = ErbStack.frameLines(listOf(frame("X", -1, -1, null, -1, -1))).single()
        assertTrue(line, line.contains("行?（物理行?）"))
        assertFalse(line, line.contains("pc=-1"))
    }

    @Test
    fun `超限时省略说明也算进帧预算且标题不被挤出`() {
        val frames = (1..100).map { frame("F$it", 0, it, null, -1, -1, index = it) }
        val lines = ErbStack.frameLines(frames, maxFrames = 5)
        assertEquals("截断后整表行数不许超过预算", 5, lines.size)
        assertTrue("最后一行必须是省略说明：${lines.last()}", lines.last().contains("余下省略"))
        val section = ErbStack.sectionLines(lines, listOf("  at Thread.run:1"))
        assertEquals(
            "省略说明也算帧位，--- erb --- 标题必须仍在头部",
            5,
            section.indexOf(ErbStack.JAVA_MARK) - section.indexOf(ErbStack.ERB_MARK) - 1,
        )
    }

    // ---- 空段：非 VM 线程 / 已回卷 ----

    @Test
    fun `无帧时打一行说明且报告仍能写完`() {
        val section = ErbStack.sectionLines(emptyList(), listOf("  at Thread.run:1"))
        assertEquals(listOf(ErbStack.ERB_MARK, ErbStack.NO_FRAMES, ErbStack.JAVA_MARK, "  at Thread.run:1"), section)

        val report = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-11-09 00:00:00",
            errorType = "java.lang.RuntimeException",
            errorMessage = "boom",
            vmStackLines = section,
        ).render()
        assertTrue("小节标题必须保留", report.contains("[ERB 调用栈与 VM 现场]"))
        assertTrue("空段说明必须出现", report.contains(ErbStack.NO_FRAMES))
        assertTrue("java 段必须保留", report.contains(ErbStack.JAVA_MARK))
        assertTrue("报告必须完整收尾", report.contains("报告结束"))
        val reportLines = report.lines()
        val erb = reportLines.indexOf(ErbStack.ERB_MARK)
        val java = reportLines.indexOf(ErbStack.JAVA_MARK)
        assertTrue("整份报告里也要有 --- erb ---", erb >= 0)
        assertEquals("无帧时 erb 段必须恰好一行说明", 1, java - erb - 1)
        assertEquals(ErbStack.NO_FRAMES, reportLines[erb + 1])
    }

    // ---- 只对 CodeEE 回落镜像 ----

    @Test
    fun `普通RuntimeException不触发抛点镜像而CodeEE及其因果链触发`() {
        assertFalse("非 VM 线程的普通异常不许读 VM 抛点镜像", ErbStack.hasCodeEE(RuntimeException("boom")))
        assertFalse(ErbStack.hasCodeEE(null))
        assertTrue(ErbStack.hasCodeEE(CodeEE("脚本错")))
        val wrapped = RuntimeException("host", CodeEE("脚本错"))
        assertTrue("因果链里的 CodeEE 也算本次现场", ErbStack.hasCodeEE(wrapped))
    }

    // ---- 源码哨兵：采集器接线 ----

    @Test
    fun 采集器接入运行期帧栈导出并保留java段() {
        val code = collectorSource().lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
        }.joinToString("\n")
        assertTrue("必须走 ProcessRuntime 的只读导出口", code.contains("erbFrameStack()"))
        assertTrue("必须有固定的 erb/java 段标题", code.contains("ErbStack.sectionLines"))
        assertTrue("空段说明由 ErbStack 统一给", code.contains("NO_FRAMES"))
        assertFalse("崩溃线程仍不许读惰性函数表", code.contains("program.functions"))
    }

    private fun collectorSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt"),
            File("../app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 ErrorReportCollector.kt（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }
}