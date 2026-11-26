package com.eratw.port.android

import com.eratw.port.android.DiagnosticEventLog.DiagnosticEvent
import com.eratw.port.android.DiagnosticEventLog.DiagnosticKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 诊断事件聚合器的定向单测（上报覆盖补齐波的出口读数）。
 *
 * 钉四件事：① 同签名首 [DiagnosticEventLog.PER_KIND_FIRST_MAX] 次放行、其后只计数；
 * ② 抹数字归一（同处换读数算一型）；③ 不同 where / 不同 kind 不合并；④ reset 清账 + 汇总形状。
 */
class DiagnosticEventLogTest {

    @Before
    fun setUp() {
        DiagnosticEventLog.reset()
    }

    private fun ui(where: String, msg: String) =
        DiagnosticEvent(DiagnosticKind.UI_FAULT, where, msg, 0L)

    private fun warn(where: String, msg: String) =
        DiagnosticEvent(DiagnosticKind.LOAD_WARNING, where, msg, 0L)

    @Test
    fun `同签名首两次落代表第三次起只计数`() {
        assertTrue(DiagnosticEventLog.record(ui("提交", "NullPointerException: 按钮空")).firstOfKind)
        assertTrue("第二次仍放行（上限 2）", DiagnosticEventLog.record(ui("提交", "NullPointerException: 按钮空")).firstOfKind)
        val third = DiagnosticEventLog.record(ui("提交", "NullPointerException: 按钮空"))
        assertFalse("第三次起只计数", third.firstOfKind)
        assertEquals(3, third.occurrences)
    }

    @Test
    fun `同处换读数按抹数字归一合并`() {
        DiagnosticEventLog.record(warn("CALLSHARP", "第 0 行 常量未定义"))
        DiagnosticEventLog.record(warn("CALLSHARP", "第 +7 行 常量未定义"))
        // 若两次没归一合并，第三次会是「新一型」而放行；合并了则第三次 occurrences=3、只计数。
        val third = DiagnosticEventLog.record(warn("CALLSHARP", "第 -3 行 常量未定义"))
        assertFalse("数字是数据不是身份：第0/+7/-3行算一型", third.firstOfKind)
        assertEquals("归一后应是同一型累计 3 次", 3, third.occurrences)
    }

    @Test
    fun `不同where与不同kind都不合并`() {
        DiagnosticEventLog.record(ui("提交", "按钮空"))
        assertTrue("不同 where 是新一型", DiagnosticEventLog.record(ui("返回", "按钮空")).firstOfKind)
        assertTrue("不同 kind 是新一型", DiagnosticEventLog.record(warn("CALLSHARP", "按钮空")).firstOfKind)
    }

    @Test
    fun `汇总按类型聚合次数且reset清账`() {
        repeat(12) { DiagnosticEventLog.record(ui("提交", "按钮空")) }
        repeat(3) { DiagnosticEventLog.record(warn("CALLSHARP", "常量未定义")) }
        val lines = DiagnosticEventLog.summaryLines()
        // 第 1 行总账 + 每型一行 TOP 明细（DBG-TOOLING 波），行数 = 1 + 有账的类型数。
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("共 15 条"))
        assertTrue(lines[0].contains("UI线程异常 ×12"))
        assertTrue(lines[0].contains("装载警告 ×3"))
        assertTrue("UI 型明细行", lines.any { it.startsWith("UI线程异常 TOP") && it.contains("提交｜按钮空 ×12") })
        assertTrue("装载型明细行", lines.any { it.startsWith("装载警告 TOP") && it.contains("CALLSHARP｜常量未定义 ×3") })
        DiagnosticEventLog.reset()
        assertTrue("reset 后为空", DiagnosticEventLog.summaryLines().isEmpty())
    }

    @Test
    fun `空局汇总为空且首条必放行`() {
        assertTrue(DiagnosticEventLog.summaryLines().isEmpty())
        assertTrue(DiagnosticEventLog.record(ui("x", "y")).firstOfKind)
    }

    @Test
    fun `每型落盘上限封顶清单型来源`() {
        // 清单型（侧车告警可达上百条互不相同签名）：per-signature 放行不封顶会刷屏，
        // 每型落盘上限 PER_KIND_REPORT_MAX 是最终判据（mayReport）。
        // 消息不含数字：normalize 会抹数字归一，带数字的 8 条会并成同一签名、测不出类型闸。
        val labels = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛")
        repeat(DiagnosticEventLog.PER_KIND_REPORT_MAX) { i ->
            val d = DiagnosticEventLog.record(warn("侧车", "告警 ${labels[i]}"))
            assertTrue("前 ${DiagnosticEventLog.PER_KIND_REPORT_MAX} 条每条新签名都该放行", d.mayReport)
            DiagnosticEventLog.markReported(DiagnosticKind.LOAD_WARNING)
        }
        val over = DiagnosticEventLog.record(warn("侧车", "告警 超限"))
        assertTrue("签名闸仍放行（新签名）", over.firstOfKind)
        assertFalse("但类型闸封顶：mayReport=false", over.mayReport)
        // 换型不受影响：另一型的首条照常放行
        assertTrue("类型闸按型独立", DiagnosticEventLog.record(ui("x", "y")).mayReport)
    }

    @Test
    fun `markReported失败不回填时宁多报`() {
        // 落盘计数只在调用方回填（markReported）后增长：record 本身不抬 mayReport 计数。
        val d1 = DiagnosticEventLog.record(ui("solo", "e1"))
        assertTrue(d1.mayReport)
        val d2 = DiagnosticEventLog.record(ui("solo", "e1"))
        assertTrue("没 markReported 之前计数不涨，仍放行（宁多报）", d2.mayReport)
        DiagnosticEventLog.markReported(DiagnosticKind.UI_FAULT)
        val d3 = DiagnosticEventLog.record(ui("solo", "e1"))
        assertFalse("回填后同签名第三次过首份上限即停", d3.firstOfKind)
        assertFalse(d3.mayReport)
    }

    @Test
    fun `四类reportKind中文名对齐报告字面量`() {
        assertEquals("UI线程异常", DiagnosticKind.UI_FAULT.reportKind)
        assertEquals("启动流程异常", DiagnosticKind.STARTUP_FAULT.reportKind)
        assertEquals("装载警告", DiagnosticKind.LOAD_WARNING.reportKind)
        assertEquals("诊断汇总", DiagnosticKind.DIAGNOSTIC_SUMMARY.reportKind)
    }

    @Test
    fun `现场原文只留最近一条且不归一`() {
        DiagnosticEventLog.record(ui("提交", "NullPointerException: 按钮 0 空"))
        assertEquals("提交｜NullPointerException: 按钮 0 空", DiagnosticEventLog.lastDisplay())
    }

    @Test
    fun `四类黑洞都有接线到聚合上报出口`() {
        // 装配面哨兵：桌面跑不了 Activity/装载，但接线代码形状骗不了 grep。
        // 任何一处接线被删/改名，这道用例就红，防止「聚合器绿着但没人调用」。
        val main = File("src/main/kotlin/com/eratw/port/android/MainActivity.kt").readText()
        val host = File("src/main/kotlin/com/eratw/port/android/GameHost.kt").readText()
        assertTrue("logUiFault 接线", main.contains("DiagnosticEventLog.DiagnosticKind.UI_FAULT"))
        assertTrue("onStartupFault 接线", main.contains("DiagnosticEventLog.DiagnosticKind.STARTUP_FAULT"))
        assertTrue("次启孤儿账并入口", main.contains("reportOrphanCheckpoint"))
        assertTrue("装载警告接线", host.contains("DiagnosticEventLog.DiagnosticKind.LOAD_WARNING"))
        assertTrue("侧车告警接线（GameHost 捕获 loadWarningsOrEmpty）", host.contains("loadWarningsOrEmpty"))
        assertTrue("会话结束 flush 汇总", host.contains("flushDiagnosticSummary"))
        assertTrue("collector 有统一入口", File("src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt")
            .readText().contains("fun recordDiagnostic"))
        assertTrue("collector 有次启并入口", File("src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt")
            .readText().contains("fun reportOrphanCheckpoint"))
    }
}
