package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.host.ResourceContents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CSV 资源表读数**接进崩溃报告**的定向单测。
 *
 * 判据函数本身绿不代表接线对：只在 logcat 打一行、或接进补充现场最前面被
 * `takeLast(80)` 剪掉，端点侧一样读不到。所以一头钉生产源码的接线位置
 * （照 [UiFaultTest] 的源码哨兵模式），一头把读数走完整份报告渲染。
 *
 * 纯 JVM：内存现场与报告渲染都是纯格式化，不需要 Android 框架。
 */
class ResourceCensusWiringTest {

    @Test
    fun `装载后把读数写进宿主取证槽且只写一处`() {
        val host = codeLines("GameHost.kt")
        assertEquals(
            "装配点只许一处，多处赋值会让报告读到不同代的读数",
            1,
            host.countOccurrences("resourceCensusLine = ResourceContents.censusLine(contents)"),
        )
        assertTrue(
            "报告侧只读已格式化的标量，装配前给未装载行",
            host.contains("internal fun resourceCensusReportLine(): String"),
        )
        assertTrue(host.contains("resourceCensusLine ?: ResourceContents.censusUnloadedLine()"))
    }

    @Test
    fun `读数接进内存现场图形侧且排在最尾`() {
        val collector = codeLines("ErrorReportCollector.kt")
        val at = collector.indexOf("host?.resourceCensusReportLine(),")
        assertTrue("找不到接线 = 读数只落了 logcat、没进报告", at >= 0)
        assertEquals(1, collector.countOccurrences("host?.resourceCensusReportLine(),"))
        val censusAt = collector.indexOf("host?.memoryCensusReportLine(),")
        assertTrue("Java 堆分项那行必须还在", censusAt >= 0)
        assertTrue(
            "补充现场是尾部保留，CSV 读数要垫在图形侧读数最尾才活得下来（at=$at census=$censusAt）",
            at > censusAt,
        )
    }

    @Test
    fun `完整语料读数出现在报告的补充现场里`() {
        val readout = ResourceContents.censusLine(ResourceContents.Report(266, 2795, 27, emptyList()))
        val lines = MemoryForensics.lines(
            javaUsedMiB = 412L, javaMaxMiB = 512L,
            nativeAllocatedMiB = 87L,
            dalvikPssMiB = 430L, nativePssMiB = 90L, totalPssMiB = 560L,
            graphicsLines = listOf(readout),
        )
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-11-06 00:00:00",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "堆见底",
            extraSectionLines = lines,
        ).render()
        assertTrue(text.contains("[补充现场]"))
        assertTrue("端点分桶靠这一行：$text", text.contains("CSV 资源表：文件=266 sprite=2795 警告=0"))
    }

    @Test
    fun `脚本开跑前崩溃也带得出去一行未装载`() {
        val text = ErrorReport(
            kind = "应用崩溃",
            timestampText = "2026-11-06 00:00:00",
            errorType = "java.lang.OutOfMemoryError",
            errorMessage = "堆见底",
            extraSectionLines = listOf(ResourceContents.censusUnloadedLine()),
        ).render()
        assertTrue(text.contains("[补充现场]"))
        assertTrue(text.contains("CSV 资源表：未装载（脚本开跑前）"))
    }

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：源码面钉的是接线，不是文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/" + "*")
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
