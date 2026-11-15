package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷宏**编译成指令集串**时的转义/拒绝契约（2026-11-11 复查后补）。
 *
 * 评审点出的核心风险：提交值是**普通文本**，其中的 `(` `)` `*` 会被指令集语法重新解释，
 * 反斜杠与换行则完全无法表达。这一层必须钉死，否则「值里带个括号」就会把整条宏的
 * 分组结构搞乱（甚至多执行几条指令）。
 */
class MacroProtocolTest {

    private fun step(value: String, times: Int = 1) = RailOptionRules.MacroStep(value, "", times)

    @Test
    fun `语法字符被转义`() {
        assertEquals("a\\(b", RailOptionRules.escapeMacroValue("a(b"))
        assertEquals("a\\)b", RailOptionRules.escapeMacroValue("a)b"))
        assertEquals("a\\*b", RailOptionRules.escapeMacroValue("a*b"))
        assertEquals("a(b)*c", RailOptionRules.escapeMacroValue("a(b)*c").replace("\\", ""))
        assertEquals("普通文本", RailOptionRules.escapeMacroValue("普通文本"))
    }

    @Test
    fun `换行与反斜杠不可表示`() {
        assertFalse(RailOptionRules.isMacroValueSubmittable("a\nb"))
        assertFalse(RailOptionRules.isMacroValueSubmittable("a\rb"))
        assertFalse(RailOptionRules.isMacroValueSubmittable("a\\b"))
        // 半角括号/星号是「需要转义但可表示」。
        assertTrue(RailOptionRules.isMacroValueSubmittable("a(b)*c"))
        assertTrue(RailOptionRules.isMacroValueSubmittable("31"))
    }

    @Test
    fun `编译时对语法字符转义`() {
        // 单步单轮（无括号形态）：值里的括号也必须转义，否则 submit 会误走 parseInput 分组。
        val simple = RailOptionRules.macroContent(
            RailOptionRules.QuickMacro(listOf(step("a(b")), repeat = 1),
        )
        assertTrue("值里的 '(' 必须被转义：$simple", simple.contains("a\\(b"))
        // 多步形态：值里的 '*' 不许变成重复次数。
        val multi = RailOptionRules.macroContent(
            RailOptionRules.QuickMacro(listOf(step("a*b"), step("c")), repeat = 1),
        )
        assertTrue("值里的 '*' 必须被转义：$multi", multi.contains("a\\*b"))
    }

    @Test
    fun `不可表示的提交值编译时直接拒绝`() {
        val bad = RailOptionRules.QuickMacro(listOf(step("a\nb")), repeat = 1)
        val thrown = runCatching { RailOptionRules.macroContent(bad) }.exceptionOrNull()
        assertTrue("应抛 IllegalArgumentException，实际 $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun `规整函数拒绝不可表示的值`() {
        assertEquals(null, RailOptionRules.sanitizeMacro(RailOptionRules.QuickMacro(listOf(step("a\\b")))))
        assertEquals(null, RailOptionRules.sanitizeMacro(RailOptionRules.QuickMacro(listOf(step("a\nb")))))
        // 可表示的值照常规整。
        val ok = RailOptionRules.sanitizeMacro(RailOptionRules.QuickMacro(listOf(step("a(b)*c"))))
        assertEquals("a(b)*c", ok?.steps?.single()?.value)
    }
}
