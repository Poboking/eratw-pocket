package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.host.ResourceContents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * 源码哨兵的锚法（2026-11-20 A2 重锚 + 2026-11-25 HYGIENE1 复核）：不逐字匹配调用表达式
 * （旧哨兵把 `host?.memoryCensusReportLine(),` 整串钉死，上报波 e37823cb8 把堆分项挪进独立
 * 度量小节、改成 lineSequence 展开写法后就对不上 ⇒ 那是**写法**漂、不是接线断，假红），
 * 改锚「具名实参绑定 + 块内拓扑」——`metricSectionLines` / `graphicsLines` 是 core-vm
 * ErrorReport 与 MemoryForensics 的具名形参，对端改名直接编译红，锚不可能静默漂移；
 * 供给函数与次序断言对换行、改拆分、加 orEmpty 等写法变化鲁棒，但对「接线被移动/被删除」
 * 必须红（2026-11-25 突变探针实证：把 ErrorReportCollector.kt:908 那次调用摘掉，
 * 「CSV 资源表读数不在图形侧采集块里」立刻红，见交付）；读数换供给函数也会红——
 * 锚①点名要 memoryCensusReportLine 出现在 metricSectionLines 绑定里。
 *
 * 接线现在真正落在这三处（行号 = 2026-11-25 在本工作树核对，日后漂了以锚①②的判据为准）：
 * 1. app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:734 —— 度量小节的供给
 *    `metricSectionLines = host?.memoryCensusReportLine()?.lineSequence()?.toList().orEmpty(),`；
 * 2. 同文件 :897 起的 `graphicsLines = listOfNotNull(` 采集块，CSV 读数在 :908
 *    `host?.resourceCensusReportLine(),` 且是块内最尾一条（满档剪行走 takeLast 尾窗）；
 * 3. app/src/main/kotlin/com/eratw/port/android/GameHost.kt —— :1575 唯一装配点
 *    `resourceCensusLine = ResourceContents.censusLine(contents)`（字段声明 :477），
 *    :2749-2750 读出函数 `resourceCensusReportLine()` 在未装载时回 censusUnloadedLine()。
 *    另有 CrashForensics.kt:333 的崩溃侧宿主也调同一条读出，那是第二读者不是接线，本文件不锚它。
 *
 * C# 锚说明：崩溃报告接线是宿主自研诊断面，反编译参考树里没有对应物；
 * 本文件的锚 = 生产接线拓扑本身 + 每笔重锚的反向实证（摘掉接线必须转红），
 * 读数行文案「CSV 资源表：」另有渲染面用例直钉。
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
    fun `分项账面进度量小节且CSV读数垫在图形侧最尾`() {
        val collector = codeLines("ErrorReportCollector.kt")
        // 锚定原理（2026-11-20 重锚，A2）：旧哨兵逐字匹配 `host?.memoryCensusReportLine(),`，
        // 上报波把堆分项挪进独立度量小节（加 lineSequence 展开写法）后字面量对不上、假红；
        // 新锚只锚**具名实参绑定 + 块内拓扑**：`metricSectionLines` / `graphicsLines` 是
        // ErrorReport / MemoryForensics 的具名形参，对端改名直接编译红，锚不可能静默漂移；
        // 绑定右边只要求供给函数出现在同一绑定表达式里，对换行、改拆分、加 orEmpty 等写法鲁棒。
        // 锚①：度量小节 ← Java 堆分项读数的供给路径（多行展开是写法细节，不锚）。
        val metricBind = Regex("metricSectionLines\\s*=\\s*[^,]*memoryCensusReportLine\\(\\)")
        assertTrue(
            "度量小节必须由 memoryCensusReportLine() 供给（绑定被摘 = 堆分项账面整节空转）",
            metricBind.containsMatchIn(collector),
        )
        // 锚②：CSV 资源表读数在图形侧采集块（具名实参 graphicsLines 的实参括号内），
        // 且垫在块内所有读数之后——满档剪行走 takeLast 尾窗，只有最尾的活得下来。
        val block = graphicsLinesBlock(collector)
        assertTrue("graphicsLines 采集块找不到（memoryLines 的采集写法换了 idiom？人工核一眼）", block.isNotEmpty())
        assertFalse(
            "堆分项读数不许回流图形侧尾窗（会被 takeLast 挤掉，度量小节才是它的家）",
            block.contains("memoryCensusReportLine"),
        )
        val reportLineCall = Regex("[A-Za-z]+ReportLine\\(\\)")
        val calls = reportLineCall.findAll(block).toList()
        val census = calls.filter { it.value == "resourceCensusReportLine()" }
        assertEquals("CSV 资源表读数不在图形侧采集块里 = 只落 logcat、没进报告", 1, census.size)
        val censusAt = census.single().range.first
        val later = calls.filter { it.value != "resourceCensusReportLine()" && it.range.first > censusAt }
        assertTrue("图形侧读数必须都排在 CSV 读数之前（CSV 垫最尾）：${later.map { it.value }}", later.isEmpty())
        assertTrue("采集块只剩 CSV 一条 = 图形侧读数被掏空", calls.size > 1)
        // 全文件只许这一处调用（块内那次）：多出来的多半是只落 logcat 的旁路接线。
        assertEquals(
            "resourceCensusReportLine() 全文件只许接进报告一处",
            1,
            collector.countOccurrences("resourceCensusReportLine()"),
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

    /**
     * 抽出 `graphicsLines = listOfNotNull(` 的**实参括号内**文本（括号配平，嵌套调用不算出界）。
     * 只取实参内容、不带绑定头：块内的次序/成员断言就不受实参列表写法影响。
     * 找不到开标记（采集函数换了 idiom 或被搬走）返回空串，由用例显式红掉让人来核。
     */
    private fun graphicsLinesBlock(code: String): String {
        val marker = "graphicsLines = listOfNotNull("
        val start = code.indexOf(marker)
        if (start < 0) return ""
        var depth = 0
        for (i in start + marker.length - 1 until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return code.substring(start + marker.length, i)
                }
            }
        }
        return ""
    }

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
