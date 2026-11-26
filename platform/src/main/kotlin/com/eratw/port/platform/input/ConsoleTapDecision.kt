package com.eratw.port.platform.input

/**
 * 控制台里一次**单击**的判据：这一击该收起输入栏、该交按钮值、该补回车，还是该被拒。
 *
 * ## 为什么把这段判据抽出来
 *
 * 它原来整段长在 `MainActivity.handleConsoleTap`（app 模块，无 Robolectric、零 JVM 覆盖）。
 * 2026-11 的「点 `+` 弹出的子选单里点选项没反应」前后修了三轮都没打中，验收口径本身是
 * 主要责任人：那一段的四条分支里任何一条判假都表现为**静默吞点击**，屏上什么也不会说，
 * 而它一次自动化断言都没有。抽成纯函数之后每个分支都能钉一条用例。
 *
 * ## 四条分支的原版依据
 *
 * 1. **输入栏展开时第一击只收栏**：移植壳层自有行为（对齐原 Compose 遮罩），不落值；
 * 2. **没命中任何按钮** ⇒ 走 [BlankTapPolicy]（`MainWindow.cs:806-820`）；
 * 3. **命中的值不在当前世代** ⇒ 丢弃。原版判据是按钮对象的
 *    `Generation == lastButtonGeneration`（`EmueraConsole.cs:2439`、`:2456`、`:2724`、
 *    `:2740`），上一页没滚出屏幕的按钮点不动；
 * 4. **当前不可提交**（宿主停转 / 非等待抢跑，[InputBarState.canSubmit]）⇒ 丢弃；
 *    原版 `PressButton` 在非等待态直接不落值（`MainWindow.cs:824-849` 与
 *    `EmueraConsole.cs:1188`/`:1246` 的世代反查同序）。
 *
 * 顺序照原实现原样保留：**先收栏，再判宿主，再命中，再世代，最后可提交**。
 * 会改这一点的只有原版行为变化，不是本地口味 —— 顺序变了，「输入栏展开时点中按钮」
 * 这类组合的分诊结果就变了。
 */
object ConsoleTapDecision {

    /** 这一击被丢掉的原因；写进事件日志后，真机一份日志就能定位是哪一道闸。 */
    enum class Refusal(val code: String) {
        /** 宿主还没挂上（装载期 / 停转后），这一击没人收。 */
        NO_HOST("no-host"),

        /** 命中了一颗按钮，但它的世代不是当前世代（上一页没滚出屏幕的旧按钮）。 */
        NOT_CURRENT_GENERATION("stale-generation"),

        /** 命中的是当前世代的按钮，但此刻不接受提交（[InputBarState.canSubmit] 为 false）。 */
        NOT_SUBMITTABLE("not-submittable"),
        ;

        companion object {
            fun fromCode(code: String): Refusal? = entries.firstOrNull { it.code == code }
        }
    }

    /** 一次单击的去向。[hitButton] 供 `TouchGestureController.noteTapExecuted` 的双击判定用。 */
    sealed interface Outcome {
        /** 输栏展开：收起它，本轮不落值。 */
        data object CollapseInputBar : Outcome

        /** 没命中按钮：交给 [BlankTapPolicy]。 */
        data object BlankTap : Outcome

        /** 命中当前世代按钮且可提交：把这个值交出去。 */
        data class Submit(val value: String) : Outcome

        /** 丢掉这一击。[value] 是命中的值（没命中时为 null），只用于日志。 */
        data class Refused(val reason: Refusal, val value: String?) : Outcome
    }

    /**
     * @param inputBarVisible 输入栏此刻是否展开（壳层 `shell.inputVisible`）
     * @param hasHost 宿主是否就绪（`GameHost` 已挂上）
     * @param hit 渲染侧命中得到的提交值；未命中为 null
     * @param currentGeneration [hit] 是否为当前世代按钮的值
     * @param canSubmit 此刻能否提交（[InputBarState.canSubmit]）
     */
    fun decide(
        inputBarVisible: Boolean,
        hasHost: Boolean,
        hit: String?,
        currentGeneration: Boolean,
        canSubmit: Boolean,
    ): Outcome = when {
        inputBarVisible -> Outcome.CollapseInputBar
        !hasHost -> Outcome.Refused(Refusal.NO_HOST, hit)
        hit == null -> Outcome.BlankTap
        !currentGeneration -> Outcome.Refused(Refusal.NOT_CURRENT_GENERATION, hit)
        !canSubmit -> Outcome.Refused(Refusal.NOT_SUBMITTABLE, hit)
        else -> Outcome.Submit(hit)
    }

    /** 这一击算不算「命中按钮」：与旧实现 `TapResult.hitButton = submitted != null` 同口径。 */
    fun hitButton(outcome: Outcome): Boolean = when (outcome) {
        is Outcome.Submit -> true
        is Outcome.Refused -> outcome.value != null
        Outcome.BlankTap, Outcome.CollapseInputBar -> false
    }
}
