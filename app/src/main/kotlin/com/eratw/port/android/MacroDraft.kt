package com.eratw.port.android

/**
 * 快捷宏编辑草稿的**纯 Kotlin 模型**（2026-11-11 复查后抽离）。
 *
 * ## 为什么要单独一层
 *
 * 之前草稿状态与校验都长在 Composable 里（`appendStep` / `buildDraft` / `draftTimesOf`），
 * 带来两个后果：
 * 1. **校验失败会静默放行**：`buildDraft()` 写成 `sanitizeMacro(raw) ?: raw` —— 规整函数
 *    返回 null（不可表示/无步骤）时，反而把未经规整的原始数据继续交给上层。负责合法性的
 *    函数不能有这样的后门。
 * 2. **不能脱离 Android 测**：次数解析、容量、合并这些是业务规则，不该只能靠跑界面验证。
 *
 * 现在：Composable 只负责把 [MacroDraft] 画出来、把用户动作翻译成 [MacroDraft] 的纯函数调用；
 * 「这条草稿能不能存、不能存是哪种问题」由 [build] 给出**显式结果**，UI 只负责展示。
 *
 * ## 契约
 *
 * - 不做**静默修正**：次数/重复次数非法就返回 [MacroDraftIssue.TimesOutOfRange] /
 *   [MacroDraftIssue.RepeatOutOfRange]，不会悄悄改成 1 或上限值；
 * - 不做**静默截断**：步数满时 [appendStep] 是 no-op，UI 用 [stepQuotaFull] 置灰并提示；
 * - 提交值含换行/反斜杠时返回 [MacroDraftIssue.ValueNotSubmittable]（指令集串无法表达，见
 *   [RailOptionRules.isMacroValueSubmittable]）；
 * - 段数超 [RailOptionRules.MACRO_SEGMENT_CAP] 时返回 [MacroDraftIssue.SegmentsOverCap]，
 *   而不是偷偷把整组重复夹小（夹小是存储侧对旧数据的兜底，不是交互侧的默认行为）。
 */
enum class MacroDraftIssue {
    /** 一步都没有（含「输入框里也没填」）。 */
    NoSteps,

    /** 步数已满，但输入框里还留着一步 —— 收下它就得丢掉别的步骤。 */
    StepQuotaOverflow,

    /** 某个提交值含换行或反斜杠，指令集串无法表达。 */
    ValueNotSubmittable,

    /** 单步连发次数不是 1..[RailOptionRules.MACRO_TIMES_MAX] 的整数。 */
    TimesOutOfRange,

    /** 整组重复次数不是 1..[RailOptionRules.MACRO_REPEAT_MAX] 的整数。 */
    RepeatOutOfRange,

    /** 总段数超过 [RailOptionRules.MACRO_SEGMENT_CAP]。 */
    SegmentsOverCap,
}

/** [MacroDraft.build] 的结果：要么是一条可直接保存的宏，要么是一个明确的拒绝原因。 */
sealed interface MacroDraftResult {
    data class Valid(val macro: RailOptionRules.QuickMacro) : MacroDraftResult

    /** [detail] 是给人看/给测试断言的补充信息（越界的次数、出问题的值、实际段数）。 */
    data class Invalid(val issue: MacroDraftIssue, val detail: String = "") : MacroDraftResult
}

/**
 * 一条正在编辑的宏：已加入的步骤 + 输入框里还没加的那一步 + 整组重复的原始文本。
 *
 * 所有字段都保持**用户原样输入**（`pendingTimes`/`repeat` 是字符串），这样界面能显示
 * 用户真正敲的东西，校验失败时也能说清是哪一项不合法。
 */
data class MacroDraft(
    val steps: List<RailOptionRules.MacroStep> = emptyList(),
    val pendingValue: String = "",
    val pendingTimes: String = "1",
    val repeat: String = "1",
) {

    /** 步数已达 [RailOptionRules.MACRO_STEP_CAP]（候选行点击不再生效，UI 要提示）。 */
    val stepQuotaFull: Boolean get() = steps.size >= RailOptionRules.MACRO_STEP_CAP

    /** 步数已满、但输入框里还留着一步 —— 保存会丢掉它，UI 必须禁用保存。 */
    val pendingOverQuota: Boolean get() = stepQuotaFull && pendingValue.isNotBlank()

    /**
     * 把输入框里的那一步加入草稿。次数非法/为空/步数满 ⇒ **原样返回**（no-op）。
     *
     * 刻意不在这里夹次数：非法输入必须由 UI 提示，而不是被悄悄改成 1 之后让玩家
     * 以为保存的是自己敲的值。
     */
    fun appendStep(label: String = ""): MacroDraft {
        val value = pendingValue.trim()
        if (value.isEmpty() || stepQuotaFull) return this
        val times = pendingTimes.toIntOrNull() ?: return this
        if (times !in 1..RailOptionRules.MACRO_TIMES_MAX) return this
        return copy(
            steps = steps + RailOptionRules.MacroStep(value, label, times),
            pendingValue = "",
            pendingTimes = "1",
        )
    }

    /**
     * 直接追加一步（候选芯片的一键加入入口）：默认连发 1 次，步数满/值空/次数越界都 no-op。
     *
     * 与 [appendStep] 的区别：不经过输入框，不改 `pending*` 字段；候选点击与「手动输入」
     * 共用同一张步骤表，但交互路径不同。
     */
    fun addStep(value: String, label: String = "", times: Int = 1): MacroDraft {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || stepQuotaFull || times !in 1..RailOptionRules.MACRO_TIMES_MAX) return this
        return copy(steps = steps + RailOptionRules.MacroStep(trimmed, label, times))
    }

    /** 改第 [index] 步的连发次数；越界下标或非法次数原样返回。 */
    fun updateStepTimes(index: Int, times: Int): MacroDraft {
        if (index !in steps.indices || times !in 1..RailOptionRules.MACRO_TIMES_MAX) return this
        return copy(
            steps = steps.mapIndexed { i, step ->
                if (i == index) step.copy(times = times) else step
            },
        )
    }
    /** 删掉第 [index] 步（越界 no-op）。 */
    fun removeStep(index: Int): MacroDraft =
        if (index in steps.indices) {
            copy(steps = steps.filterIndexed { i, _ -> i != index })
        } else {
            this
        }

    /**
     * 校验并产出最终宏。**这是唯一入口**：UI 拿 [MacroDraftResult.Valid] 才允许保存，
     * 绝不把 [MacroDraftResult.Invalid] 的原始草稿写进存储。
     */
    fun build(): MacroDraftResult {
        val pending = pendingValue.trim()
        val steps: List<RailOptionRules.MacroStep>
        if (pending.isEmpty()) {
            steps = this.steps
        } else {
            if (stepQuotaFull) return MacroDraftResult.Invalid(MacroDraftIssue.StepQuotaOverflow)
            val times = pendingTimes.toIntOrNull()
                ?: return MacroDraftResult.Invalid(MacroDraftIssue.TimesOutOfRange, pendingTimes)
            if (times !in 1..RailOptionRules.MACRO_TIMES_MAX) {
                return MacroDraftResult.Invalid(MacroDraftIssue.TimesOutOfRange, pendingTimes)
            }
            steps = this.steps + RailOptionRules.MacroStep(pending, "", times)
        }
        if (steps.isEmpty()) return MacroDraftResult.Invalid(MacroDraftIssue.NoSteps)
        steps.firstOrNull { !RailOptionRules.isMacroValueSubmittable(it.value) }?.let {
            return MacroDraftResult.Invalid(MacroDraftIssue.ValueNotSubmittable, it.value)
        }
        if (steps.any { it.times !in 1..RailOptionRules.MACRO_TIMES_MAX }) {
            return MacroDraftResult.Invalid(MacroDraftIssue.TimesOutOfRange, pendingTimes)
        }
        val repeatCount = repeat.toIntOrNull()
            ?: return MacroDraftResult.Invalid(MacroDraftIssue.RepeatOutOfRange, repeat)
        if (repeatCount !in 1..RailOptionRules.MACRO_REPEAT_MAX) {
            return MacroDraftResult.Invalid(MacroDraftIssue.RepeatOutOfRange, repeat)
        }
        val segments = segmentsOf(steps, repeatCount)
        if (segments > RailOptionRules.MACRO_SEGMENT_CAP) {
            return MacroDraftResult.Invalid(MacroDraftIssue.SegmentsOverCap, segments.toString())
        }
        return MacroDraftResult.Valid(RailOptionRules.QuickMacro(steps, repeatCount))
    }

    /** 预览用：按当前草稿（宽松解析，非法项按 0 计）估算会提交多少段。 */
    fun previewSegmentCount(): Int {
        val pending = pendingValue.trim()
        val times = pendingTimes.toIntOrNull()?.takeIf { it in 1..RailOptionRules.MACRO_TIMES_MAX } ?: 0
        val all = if (pending.isEmpty()) {
            steps
        } else {
            steps + RailOptionRules.MacroStep(pending, "", times)
        }
        val repeatCount = repeat.toIntOrNull()?.takeIf { it in 1..RailOptionRules.MACRO_REPEAT_MAX } ?: 0
        return if (repeatCount == 0) 0 else segmentsOf(all, repeatCount)
    }

    companion object {
        /** 从一条已保存的宏进入编辑态。 */
        fun of(macro: RailOptionRules.QuickMacro): MacroDraft = MacroDraft(
            steps = macro.steps,
            pendingValue = "",
            pendingTimes = "1",
            repeat = macro.repeat.toString(),
        )

        private fun segmentsOf(steps: List<RailOptionRules.MacroStep>, repeat: Int): Int =
            steps.sumOf { step -> if (step.value.isBlank()) 0 else step.times } * repeat
    }
}

/**
 * 「编辑期间列表变了没有」的纯判据（`RailQuickMacroCard` 保存前调用）。
 *
 * 为什么不用下标直接写回：编辑框打开期间列表可能被删掉一条或整体变化，此时
 * `editIndex` 指向的已经不是玩家当初打开的那条宏 —— 直接把改动写回下标就会**改到别人的宏上**。
 * 这里用「打开那一刻的编码快照」比对内容：对不上就拒绝写回并提示重开。
 *
 * 未来若给宏加稳定 ID（评审建议），这条判据可以退化成 ID 相等，语义不变。
 */
object MacroEditGuard {

    /** 打开编辑时的快照（与持久化同源的编码串）。 */
    fun snapshot(macro: RailOptionRules.QuickMacro): String =
        RailOptionRules.encodeQuickMacros(listOf(macro))

    /** true = 下标已失效（那条宏没了或被换掉），**不许写回**。 */
    fun isStale(
        macros: List<RailOptionRules.QuickMacro>,
        index: Int,
        snapshot: String,
    ): Boolean {
        val current = macros.getOrNull(index) ?: return true
        return snapshot(current) != snapshot
    }
}
