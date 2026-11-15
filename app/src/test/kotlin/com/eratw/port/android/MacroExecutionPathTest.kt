package com.eratw.port.android

import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.proc.QueueInputSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷宏**端到端执行路径**闸（2026-11-11 复查后补，评审明确要求的那一条）。
 *
 * 只测 `macroContent()` 的字符串形状是不够的 —— 真正的产品行为是「引擎按玩家配置执行」。
 * 这里一路走到 `QueueInputSink.submit`（真实展开 + 真实切段），断言**引擎实际收到的执行序列**。
 *
 * 评审给的例子：A×2、B×3、整组重复 2 ⇒ `A A B B B A A B B B`。
 */
class MacroExecutionPathTest {

    private fun strRequest(): InputRequest = InputRequest(type = InputRequest.InputType.STR)

    private fun step(value: String, times: Int = 1) = RailOptionRules.MacroStep(value, "", times)

    /** 把整条宏提交进 sink，按顺序取出所有段（含末尾可能的空段）。 */
    private fun drain(content: String): List<String> {
        val sink = QueueInputSink()
        assertTrue("宏串应能一次入队：$content", sink.submit(content))
        val out = ArrayList<String>()
        while (sink.pendingCount > 0) {
            out += sink.waitInput(strRequest()).strValue
            sink.consumeMesSkip()
        }
        return out
    }

    @Test
    fun `评审例子 A乘2 B乘3 整组重复2 引擎按序执行`() {
        val macro = RailOptionRules.QuickMacro(
            steps = listOf(step("A", 2), step("B", 3)),
            repeat = 2,
        )
        val seq = drain(RailOptionRules.macroContent(macro))
        // 尾段：`\e` 自带分段 ⇒ 展开结果以换行结尾，切段器按 None 语义多出一个空段
        // （原版同口径，core-vm 的 `(0\e\n)*3` 等价式用例已钉住）。它不是玩家配置的一部分。
        assertEquals(
            listOf("A", "A", "B", "B", "B", "A", "A", "B", "B", "B", ""),
            seq,
        )
        assertEquals("玩家配置的 10 段必须全中", 10, seq.count { it.isNotBlank() })
    }

    @Test
    fun `单步单轮 只提交一次且没有多余空回车`() {
        val macro = RailOptionRules.QuickMacro(listOf(step("31")), repeat = 1)
        assertEquals(listOf("31"), drain(RailOptionRules.macroContent(macro)))
    }

    @Test
    fun `多步单轮 无括号形态 段数精确且无尾空段`() {
        val macro = RailOptionRules.QuickMacro(listOf(step("31"), step("32")), repeat = 1)
        assertEquals(listOf("31", "32"), drain(RailOptionRules.macroContent(macro)))
    }

    @Test
    fun `提交值里的括号与星号原样送达引擎`() {
        // 值 a(b)*c 必须作为一个完整段送达，不能被指令集语法拆开。
        //
        // 注意尾空段：值里含 `(` 时转义后仍含 `(`（`\(`），引擎因此会走 `parseInput` 路径，
        // 而 `\e` 在解析路径里会产出真换行 ⇒ 切段后多一个空段（与任何带括号的宏同口径）。
        // 值里不含括号时不走解析、没有这个空段 —— 这条差异是引擎协议本身的性质，
        // 记在这里是为了将来若「值里带括号」在语料里真出现，有据可查。
        val macro = RailOptionRules.QuickMacro(listOf(step("a(b)*c")), repeat = 1)
        assertEquals(listOf("a(b)*c", ""), drain(RailOptionRules.macroContent(macro)))
        // 多步 + 重复形态同样不能被值里的括号改变分组。
        val repeated = RailOptionRules.QuickMacro(listOf(step("a(b)", 2), step("c")), repeat = 1)
        assertEquals(
            listOf("a(b)", "a(b)", "c"),
            drain(RailOptionRules.macroContent(repeated)).filter { it.isNotBlank() },
        )
    }

    @Test
    fun `连发次数与整组重复都进真实执行序列`() {
        val macro = RailOptionRules.QuickMacro(listOf(step("X", 3)), repeat = 2)
        val seq = drain(RailOptionRules.macroContent(macro))
        assertEquals(6, seq.count { it.isNotBlank() })
        assertEquals(listOf("X", "X", "X", "X", "X", "X"), seq.filter { it.isNotBlank() })
    }

    @Test
    fun `每段都带跳过标记 连发才能自动跑下去`() {
        val sink = QueueInputSink()
        sink.submit(RailOptionRules.macroContent(RailOptionRules.QuickMacro(listOf(step("A", 2)), repeat = 1)))
        assertEquals("A", sink.waitInput(strRequest()).strValue)
        assertTrue("第一段带跳过标记", sink.consumeMesSkip())
        assertEquals("A", sink.waitInput(strRequest()).strValue)
        assertTrue("第二段也带跳过标记", sink.consumeMesSkip())
    }

    @Test
    fun `草稿校验通过的宏一定能编译并执行`() {
        // 契约串联：MacroDraft.build() 的 Valid 必须能过 macroContent（不抛）且段数对得上。
        val draft = MacroDraft(
            steps = listOf(RailOptionRules.MacroStep("A", "", 2), RailOptionRules.MacroStep("B", "", 1)),
            pendingValue = "C",
            pendingTimes = "2",
            repeat = "2",
        )
        val result = draft.build()
        assertTrue("草稿应合法：$result", result is MacroDraftResult.Valid)
        val macro = (result as MacroDraftResult.Valid).macro
        val content = RailOptionRules.macroContent(macro)
        val seq = drain(content)
        assertEquals(
            "build() 报的段数必须与引擎实际执行的非空段数一致",
            RailOptionRules.macroSegmentCount(macro),
            seq.count { it.isNotBlank() },
        )
        assertEquals(listOf("A", "A", "B", "C", "C", "A", "A", "B", "C", "C"), seq.filter { it.isNotBlank() })
    }
}
