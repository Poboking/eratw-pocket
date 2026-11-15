package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 快捷宏**草稿模型**的纯 JVM 闸（2026-11-11 复查后补）。
 *
 * 覆盖评审点名的场景：最后一步直接保存、步数上限、删后恢复容量、次数 0/空/超上限、
 * 重复上限、编辑既有宏、历史候选、协议分隔符、陈旧下标。
 * 这些规则全在 [MacroDraft] / [MacroEditGuard] 里，不需要 Android 环境。
 */
class MacroDraftTest {

    private fun step(value: String, times: Int = 1, label: String = "") =
        RailOptionRules.MacroStep(value, label, times)

    private fun validMacro(draft: MacroDraft): RailOptionRules.QuickMacro {
        val r = draft.build()
        assertTrue("期望草稿合法，实际 $r", r is MacroDraftResult.Valid)
        return (r as MacroDraftResult.Valid).macro
    }

    private fun issueOf(draft: MacroDraft): MacroDraftIssue? =
        (draft.build() as? MacroDraftResult.Invalid)?.issue

    // ---------------------------------------------------------------- 保存时的待提交步骤

    @Test
    fun `手动添加最后一步后直接保存 最后一步不会丢`() {
        // 玩家常见顺序：填完最后一步直接点保存，没有先按「加一步」。
        val draft = MacroDraft(steps = listOf(step("31")), pendingValue = "32", pendingTimes = "1")
        val macro = validMacro(draft)
        assertEquals(listOf("31", "32"), macro.steps.map { it.value })
    }

    @Test
    fun `输入框里那一步按当前次数收下`() {
        val draft = MacroDraft(pendingValue = "31", pendingTimes = "7")
        assertEquals(7, validMacro(draft).steps.single().times)
    }

    // ---------------------------------------------------------------- 步数上限

    @Test
    fun `步数达上限后继续输入 不允许静默截断`() {
        val full = (1..RailOptionRules.MACRO_STEP_CAP).map { step("$it") }
        val draft = MacroDraft(steps = full, pendingValue = "extra", pendingTimes = "1")
        assertTrue("步数已满", draft.stepQuotaFull)
        assertTrue("输入框那步无处安放", draft.pendingOverQuota)
        assertEquals(
            "必须明确拒绝（而不是 take(4) 静默丢掉）",
            MacroDraftIssue.StepQuotaOverflow,
            issueOf(draft),
        )
        // appendStep 也必须是 no-op（不静默截断已有步骤）。
        assertEquals(full, draft.appendStep().steps)
    }

    @Test
    fun `删除一个步骤后容量正确恢复`() {
        val full = (1..RailOptionRules.MACRO_STEP_CAP).map { step("$it") }
        val draft = MacroDraft(steps = full)
        assertTrue("满时加不进去", draft.appendStep().steps == full)
        val afterDelete = draft.removeStep(0)
        assertFalse("删一步后不再满", afterDelete.stepQuotaFull)
        val appended = afterDelete.copy(pendingValue = "99").appendStep()
        assertEquals(RailOptionRules.MACRO_STEP_CAP, appended.steps.size)
        assertEquals("99", appended.steps.last().value)
    }

    // ---------------------------------------------------------------- 次数 / 重复校验

    @Test
    fun `次数为 0 或空或超上限都明确报错 不静默改成 1`() {
        for (raw in listOf("0", "", "abc", (RailOptionRules.MACRO_TIMES_MAX + 1).toString())) {
            val draft = MacroDraft(pendingValue = "31", pendingTimes = raw)
            assertEquals("次数「$raw」应报越界", MacroDraftIssue.TimesOutOfRange, issueOf(draft))
            assertEquals("非法次数不许被加入步骤", emptyList<Any>(), draft.appendStep().steps)
        }
        // 合法边界值照收。
        assertEquals(
            RailOptionRules.MACRO_TIMES_MAX,
            validMacro(MacroDraft(pendingValue = "31", pendingTimes = RailOptionRules.MACRO_TIMES_MAX.toString()))
                .steps.single().times,
        )
    }

    @Test
    fun `整组重复为 0 或超上限明确报错`() {
        for (raw in listOf("0", "", "x", (RailOptionRules.MACRO_REPEAT_MAX + 1).toString())) {
            val draft = MacroDraft(steps = listOf(step("31")), repeat = raw)
            assertEquals("重复「$raw」应报越界", MacroDraftIssue.RepeatOutOfRange, issueOf(draft))
        }
    }

    @Test
    fun `段数超上限明确报错 而不是偷偷夹小重复`() {
        // 1 步 × 99 连发 × 99 轮 = 9801 段 > 600。
        val draft = MacroDraft(
            steps = listOf(step("31", times = RailOptionRules.MACRO_TIMES_MAX)),
            repeat = RailOptionRules.MACRO_REPEAT_MAX.toString(),
        )
        val invalid = draft.build() as MacroDraftResult.Invalid
        assertEquals(MacroDraftIssue.SegmentsOverCap, invalid.issue)
        assertEquals("9801", invalid.detail)
        // 边界：恰好等于上限时合法（99+99+99+3 = 300 段/轮 × 2 轮 = 600）。
        val exact = MacroDraft(
            steps = listOf(step("31", times = 99), step("32", times = 99), step("33", times = 99), step("34", times = 3)),
            repeat = "2",
        )
        assertEquals(600, validMacro(exact).let { RailOptionRules.macroSegmentCount(it) })
    }

    // ---------------------------------------------------------------- 提交值协议

    @Test
    fun `提交值含换行或反斜杠 明确拒绝`() {
        for (bad in listOf("a\nb", "a\rb", "a\\nb")) {
            val draft = MacroDraft(pendingValue = bad)
            assertEquals("值「$bad」不可表示", MacroDraftIssue.ValueNotSubmittable, issueOf(draft))
        }
        // 半角括号与星号是可表示的（编译时转义），不该被拒。
        val ok = validMacro(MacroDraft(pendingValue = "a(b)*c"))
        assertEquals("a(b)*c", ok.steps.single().value)
    }

    // ---------------------------------------------------------------- 编辑既有宏

    @Test
    fun `编辑已有宏 原有步骤与重复次数保留`() {
        val original = RailOptionRules.QuickMacro(listOf(step("31", 5), step("32", 2)), repeat = 3)
        val draft = MacroDraft.of(original)
        assertEquals(original, validMacro(draft))
    }

    @Test
    fun `历史候选加入步骤 值与名字都正确`() {
        val draft = MacroDraft(pendingValue = "400", pendingTimes = "2").appendStep(label = "移动")
        val macro = validMacro(draft)
        assertEquals("400", macro.steps.single().value)
        assertEquals("移动", macro.steps.single().label)
        assertEquals(2, macro.steps.single().times)
        assertEquals("加完一步后输入框清空", "", draft.pendingValue)
        assertEquals("次数回到默认", "1", draft.pendingTimes)
    }

    @Test
    fun `候选一键加入 默认连发一次且保留名字`() {
        val draft = MacroDraft().addStep(value = "31", label = "会话")
        val macro = validMacro(draft)
        assertEquals("31", macro.steps.single().value)
        assertEquals("会话", macro.steps.single().label)
        assertEquals("候选点击默认连发一次", 1, macro.steps.single().times)
    }

    @Test
    fun `每一步都能单独改连发次数 且越界值不生效`() {
        val draft = MacroDraft().addStep("31", "会话").addStep("32", "道具")

        val changed = draft.updateStepTimes(index = 0, times = 5)
        assertEquals(5, changed.steps[0].times)
        assertEquals("只改目标步骤", 1, changed.steps[1].times)

        assertEquals("0 次非法，原样返回", changed, changed.updateStepTimes(index = 0, times = 0))
        assertEquals("下标越界，原样返回", changed, changed.updateStepTimes(index = 9, times = 2))
    }
    // ---------------------------------------------------------------- 陈旧下标

    @Test
    fun `编辑期间列表变化 陈旧下标被拒绝`() {
        val a = RailOptionRules.QuickMacro(listOf(step("31")), repeat = 1)
        val b = RailOptionRules.QuickMacro(listOf(step("32")), repeat = 1)
        val macros = listOf(a, b)
        val snapshot = MacroEditGuard.snapshot(macros[1])
        assertFalse("列表没变时不算陈旧", MacroEditGuard.isStale(macros, 1, snapshot))
        assertTrue("删掉后下标越界 ⇒ 陈旧", MacroEditGuard.isStale(listOf(a), 1, snapshot))
        assertTrue("该位置被换成别的宏 ⇒ 陈旧", MacroEditGuard.isStale(listOf(a, a), 1, snapshot))
    }

    // ---------------------------------------------------------------- 空态

    @Test
    fun `空草稿没有步骤`() {
        assertEquals(MacroDraftIssue.NoSteps, issueOf(MacroDraft()))
        assertNull("空草稿没有可预览段数", null)
        assertEquals(0, MacroDraft().previewSegmentCount())
    }
}
