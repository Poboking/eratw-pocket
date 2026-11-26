package com.eratw.port.platform.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ConsoleTapDecision] 的分诊钉子：五道判据（输入栏 → 宿主 → 命中 → 世代 → 可提交）
 * 每一道判假都必须落到一个**可区分的**去向，而不是都表现为「点了没反应」。
 *
 * 这组用例的来历：真机「点 `+` 弹出的子选单里点选项没反应」在 2026-11 复发三轮，
 * 而它所在的 `MainActivity.handleConsoleTap` 在 app 模块，无 Robolectric、零 JVM 覆盖
 * —— 每次修完只能靠真机拍脑袋验收。判据抽到这里之后，任何一道闸被改坏都有用例报红。
 */
class ConsoleTapDecisionTest {

    private fun decide(
        inputBarVisible: Boolean = false,
        hasHost: Boolean = true,
        hit: String? = "1",
        currentGeneration: Boolean = true,
        canSubmit: Boolean = true,
    ) = ConsoleTapDecision.decide(inputBarVisible, hasHost, hit, currentGeneration, canSubmit)

    @Test
    fun `命中当前世代按钮且可提交时交出这个值`() {
        assertEquals(ConsoleTapDecision.Outcome.Submit("1"), decide())
        assertTrue("提交算命中", ConsoleTapDecision.hitButton(decide()))
    }

    @Test
    fun `输入栏展开时第一击只收栏_即使命中按钮也不落值`() {
        val outcome = decide(inputBarVisible = true)
        assertEquals(ConsoleTapDecision.Outcome.CollapseInputBar, outcome)
        assertFalse("收栏那一击不算命中，否则双击判定会串", ConsoleTapDecision.hitButton(outcome))
    }

    @Test
    fun `宿主不在时丢弃并记 no-host`() {
        val outcome = decide(hasHost = false)
        assertEquals(
            ConsoleTapDecision.Outcome.Refused(ConsoleTapDecision.Refusal.NO_HOST, "1"),
            outcome,
        )
    }

    @Test
    fun `没命中按钮时落给空白点击策略`() {
        assertEquals(ConsoleTapDecision.Outcome.BlankTap, decide(hit = null))
        assertFalse(
            "空白点击不算命中",
            ConsoleTapDecision.hitButton(ConsoleTapDecision.Outcome.BlankTap),
        )
    }

    @Test
    fun `命中的是上一页按钮时丢弃并记 stale-generation`() {
        val outcome = decide(currentGeneration = false)
        assertEquals(
            ConsoleTapDecision.Outcome.Refused(
                ConsoleTapDecision.Refusal.NOT_CURRENT_GENERATION,
                "1",
            ),
            outcome,
        )
        assertTrue(
            "命中了真实按钮但被世代闸拒：仍算命中，双击判定不能把它当空白",
            ConsoleTapDecision.hitButton(outcome),
        )
    }

    @Test
    fun `命中的是当前世代按钮但此刻不可提交时丢弃并记 not-submittable`() {
        val outcome = decide(canSubmit = false)
        assertEquals(
            ConsoleTapDecision.Outcome.Refused(ConsoleTapDecision.Refusal.NOT_SUBMITTABLE, "1"),
            outcome,
        )
        assertTrue("命中的按钮算命中", ConsoleTapDecision.hitButton(outcome))
    }

    @Test
    fun `判据顺序与旧实现一致_收栏先于宿主与命中`() {
        assertEquals(
            "收栏是第一道",
            ConsoleTapDecision.Outcome.CollapseInputBar,
            decide(inputBarVisible = true, hasHost = false, hit = null),
        )
        assertEquals(
            "宿主不在时先记 no-host，再去谈空白点击",
            ConsoleTapDecision.Outcome.Refused(ConsoleTapDecision.Refusal.NO_HOST, null),
            decide(hasHost = false, hit = null),
        )
        assertEquals(
            "没命中就不该被世代闸或可提交闸拦成 refused",
            ConsoleTapDecision.Outcome.BlankTap,
            decide(hit = null, currentGeneration = false, canSubmit = false),
        )
        assertEquals(
            "世代先于可提交：旧按钮不该被记成 not-submittable",
            ConsoleTapDecision.Outcome.Refused(
                ConsoleTapDecision.Refusal.NOT_CURRENT_GENERATION,
                "1",
            ),
            decide(currentGeneration = false, canSubmit = false),
        )
    }

    @Test
    fun `拒绝原因码可反查_日志里读回来要能还原成枚举`() {
        ConsoleTapDecision.Refusal.entries.forEach { r ->
            assertEquals(r.code, r, ConsoleTapDecision.Refusal.fromCode(r.code))
        }
    }
}
