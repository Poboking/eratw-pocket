package com.eratw.port.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.ShellAlertDialog
import com.eratw.port.android.ui.design.ShellDialogActions
import com.eratw.port.android.ui.design.ShellField
import com.eratw.port.android.ui.design.ShellRowHairline
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellStepper
import com.eratw.port.android.ui.design.ShellType

/** 编辑对话框最多显示多少枚最近指令（再多的历史候选不进主视图，避免又变成长名单）。 */
private const val MACRO_CANDIDATE_LIMIT = 12

/** 编辑正文的最大高度：横屏可用高只有 300 多 dp，正文不滚会把按钮挤出视区。 */
private val MACRO_EDITOR_MAX_HEIGHT = 460.dp

/**
 * 「快捷宏」功能卡（2026-11-11 重做，同日按复查意见再改一轮）：玩家**拼**出来的连发宏。
 *
 * 一条宏 = 若干步 + 整组重复次数：
 * - 每步 = 一个提交值 + 连发次数（「会话 ×5」）；
 * - 多步 = 按顺序连发（「先 a 后 b」）；
 * - 整组重复 = 「a、b 这一轮跑 N 遍」。
 *
 * 编译成 era 原生指令集串交给引擎（[RailOptionRules.macroContent] → `QueueInputSink.submit`），
 * 分段与剥离在引擎侧；按钮文案由 [RailOptionRules.macroLabel] 自动生成，玩家不起名字。
 *
 * ## 复查那轮改了什么
 *
 * 1. **草稿模型外移**：状态与校验搬进纯 Kotlin 的 [MacroDraft]（可脱离 Android 单测），
 *    本文件只剩「画出来 + 把动作翻译成纯函数调用」。
 * 2. **校验不再静默放行/静默修正**：以前 `sanitizeMacro(raw) ?: raw` 会在规整失败时
 *    把原始数据继续交出去；现在只认 [MacroDraftResult.Valid]，非法输入显示具体原因并禁用保存。
 *    次数/重复次数非法不再被悄悄改成 1 或上限值。
 * 3. **草稿跨配置变化存活**：全部状态走 `rememberSaveable`（[MacroDraftSaver] 负责步骤表），
 *    横竖屏切换、Activity 重建都不会把玩家拼到一半的宏丢掉。
 * 4. **陈旧下标守卫**：编辑期间宏列表被删/改时，用打开编辑那一刻的快照比对，拒绝把改动
 *    写回错误的宏（列表按内容而非下标确认，避免「保存到别人身上」）。
 * 5. **清空二次确认**。
 *
 * ## 壳层设计系统那一轮（2026-11-26 第三轮）改了什么
 *
 * - 每条宏 / 每一步不再各自套一块 8dp 圆角浮底（卡内套卡，密度反而更差），改成
 *   **发丝分隔的一行**，与口上包管理卡同一种扫读节奏；
 * - 数字步进器换成共享件 [ShellStepper]：视觉仍是紧凑小方块，但热区撑到 48dp、边界两侧
 *   独立灰化（原先那版是 6dp 圆角 + 2dp 内距，小屏上点不中）；
 * - 两个确认框换共享件 [ShellAlertDialog]：标题字阶、面板色、圆角与全壳同拍，不再各写一份；
 * - 全部字阶改取 [ShellType]，不再出现 `13.sp` / `10.sp` 这类裸值。
 */
@Composable
internal fun RailQuickMacroCard(
    text: SettingsStrings,
    macros: List<RailOptionRules.QuickMacro>,
    recent: List<RailOptionRules.RailHistoryEntry>,
    onAdd: (RailOptionRules.QuickMacro) -> Boolean,
    onUpdate: (Int, RailOptionRules.QuickMacro) -> Unit,
    onDelete: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var dialogOpen by rememberSaveable { mutableStateOf(false) }
    // -1 = 新增；>= 0 = 改第 index 条。
    var editIndex by rememberSaveable { mutableStateOf(-1) }
    // 打开编辑那一刻的宏快照（编码串）：保存时用它确认「第 index 条还是原来那条」。
    var editSnapshot by rememberSaveable { mutableStateOf("") }
    var draft by rememberSaveable(stateSaver = MacroDraftSaver) { mutableStateOf(MacroDraft()) }
    var showFullHint by rememberSaveable { mutableStateOf(false) }
    // 宏条目默认只露前几条；展开状态随横竖屏切换存活。
    var listExpanded by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    // 保存时发现「列表已变、这条宏不在了」⇒ 留在对话框里说明，不写回错误的宏。
    var staleEdit by rememberSaveable { mutableStateOf(false) }

    fun openFor(index: Int) {
        editIndex = index
        val existing = if (index >= 0) macros.getOrNull(index) else null
        draft = existing?.let { MacroDraft.of(it) } ?: MacroDraft()
        editSnapshot = existing?.let(MacroEditGuard::snapshot).orEmpty()
        showFullHint = false
        staleEdit = false
        dialogOpen = true
    }

    // 校验结果：唯一决定「保存」能不能点、以及显示哪句原因。
    val result = draft.build()
    val issueText: String? = when (result) {
        is MacroDraftResult.Valid -> null
        is MacroDraftResult.Invalid -> when (result.issue) {
            // 「还没有步骤」不必喊一句：保存本来就灰着，步骤区已有空态说明。
            MacroDraftIssue.NoSteps -> null
            MacroDraftIssue.StepQuotaOverflow -> text.railQuickMacroStepQuotaFull(RailOptionRules.MACRO_STEP_CAP)
            MacroDraftIssue.ValueNotSubmittable -> text.railQuickMacroIssueValue
            MacroDraftIssue.TimesOutOfRange -> text.railQuickMacroIssueTimes(result.detail)
            MacroDraftIssue.RepeatOutOfRange -> text.railQuickMacroIssueRepeat(result.detail)
            MacroDraftIssue.SegmentsOverCap -> text.railQuickMacroIssueSegments(
                result.detail.toIntOrNull() ?: 0,
                RailOptionRules.MACRO_SEGMENT_CAP,
            )
        }
    }

    SettingsCard {
        CardHeading(
            title = text.railQuickMacroTitle,
            badge = "${macros.size}/${RailOptionRules.QUICK_MACRO_CAP}",
        )
        if (macros.isEmpty()) {
            EmptyHint(text = text.railQuickMacroEmpty)
        } else {
            val shown = previewOf(macros, expanded = listExpanded, limit = SettingsListPreview.MACRO)
            Column(modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap)) {
                shown.forEachIndexed { index, macro ->
                    // 行间只摆一条发丝线：一条宏是一行记录，框起来会让卡里再长出一层卡。
                    if (index > 0) ShellRowHairline()
                    MacroSummaryRow(
                        macro = macro,
                        text = text,
                        onEdit = { openFor(index) },
                        onDelete = {
                            // 删掉一条之后「已满」提示必须立刻灭：否则会出现
                            // 「提示说满了、可「新增」又点得动」的自相矛盾。
                            showFullHint = false
                            onDelete(index)
                        },
                    )
                }
                if (macros.size > SettingsListPreview.MACRO) {
                    InlineAction(
                        text = if (listExpanded) {
                            text.commonCollapse
                        } else {
                            text.commonShowAll(macros.size)
                        },
                        onClick = { listExpanded = !listExpanded },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingChip(
                label = text.railQuickMacroAdd,
                decoration = ChipDecoration(leading = "＋"),
                onClick = {
                    if (macros.size >= RailOptionRules.QUICK_MACRO_CAP) {
                        showFullHint = true
                    } else {
                        openFor(-1)
                    }
                },
            )
            if (showFullHint) {
                Text(
                    text = text.railQuickMacroFull(RailOptionRules.QUICK_MACRO_CAP),
                    color = UiColors.TextMuted,
                    style = ShellType.Label,
                    modifier = Modifier.padding(start = ShellSpace.CardGap),
                )
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }
            if (macros.isNotEmpty() && !showFullHint) {
                InlineAction(
                    text = text.railQuickMacroClear,
                    color = UiColors.TextMuted,
                    onClick = { confirmClear = true },
                )
            }
        }
        Text(
            text = text.railQuickMacroHint,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = ShellSpace.CardGap),
        )
    }
    if (confirmClear) {
        ShellAlertDialog(
            title = text.railQuickMacroClear,
            actions = ShellDialogActions(
                confirmLabel = text.railQuickMacroClear,
                confirmTone = EratwInk.warn(),
                onConfirm = {
                    confirmClear = false
                    showFullHint = false
                    onClear()
                },
                dismissLabel = text.railQuickMacroCancel,
            ),
            onDismissRequest = { confirmClear = false },
        ) {
            Text(
                text = text.railQuickMacroClearConfirm,
                color = EratwInk.textDim(),
                style = ShellType.Body,
            )
        }
    }

    if (dialogOpen) {
        ShellAlertDialog(
            title = if (editIndex >= 0) text.railQuickMacroEdit else text.railQuickMacroAdd,
            actions = ShellDialogActions(
                confirmLabel = text.railQuickMacroSave,
                confirmEnabled = result is MacroDraftResult.Valid && !draft.pendingOverQuota,
                onConfirm = {
                    val macro = (result as? MacroDraftResult.Valid)?.macro
                    if (macro != null && editIndex >= 0) {
                        // 陈旧下标守卫：列表在编辑期间被删/改时不写回错误的宏。
                        if (MacroEditGuard.isStale(macros, editIndex, editSnapshot)) {
                            staleEdit = true
                        } else {
                            onUpdate(editIndex, macro)
                            dialogOpen = false
                        }
                    } else if (macro != null) {
                        if (onAdd(macro)) {
                            dialogOpen = false
                        } else {
                            showFullHint = true
                            dialogOpen = false
                        }
                    }
                    // macro == null：保存键本来就灰着，这里只是兜一层，不写回任何东西。
                },
                dismissLabel = text.railQuickMacroCancel,
                onDismiss = { dialogOpen = false },
            ),
            onDismissRequest = { dialogOpen = false },
        ) {
            // 正文必须可滚：步骤 + 候选芯片 + 手动输入 + 整组重复 + 预览，
            // 横屏可用高只有 300 多 dp，不滚会把「保存/取消」挤出视区。
            Column(
                modifier = Modifier
                    .heightIn(max = MACRO_EDITOR_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                MacroEditorBody(
                    text = text,
                    state = MacroEditorViewState(
                        draft = draft,
                        issueText = issueText,
                        staleEdit = staleEdit,
                    ),
                    recent = recent,
                    onDraftChange = { draft = it },
                )
            }
        }
    }
}

/** 一条宏的汇总行：名称 + 段数 + 编辑 / 删除（行内动作，删完立刻从列表消失）。 */
@Composable
private fun MacroSummaryRow(
    macro: RailOptionRules.QuickMacro,
    text: SettingsStrings,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = RailOptionRules.macroLabel(macro),
                color = UiColors.Text,
                style = ShellType.Tab,
                maxLines = 1,
            )
            Text(
                text = text.railQuickMacroSegments(RailOptionRules.macroSegmentCount(macro)),
                color = UiColors.TextMuted,
                style = ShellType.Overline,
                maxLines = 1,
            )
        }
        InlineAction(text = text.railQuickMacroEdit, onClick = onEdit)
        InlineAction(text = text.railQuickMacroDelete, color = UiColors.TextMuted, onClick = onDelete)
    }
}

/** 编辑对话框正文的只读快照：草稿 + 当前校验原因 + 陈旧下标提示。 */
internal data class MacroEditorViewState(
    val draft: MacroDraft,
    val issueText: String?,
    val staleEdit: Boolean,
)

/**
 * 快捷宏编辑器的**傻瓜式主路径**（2026-11-11 重做）：
 *
 * 1. 先从「最近见过的指令」点一枚 → 自动成为第一步；
 * 2. 再点一枚 → 自动接在后面，变成「先 A 后 B」；
 * 3. 每一步用 `− / +` 调连发次数，不用手敲数字；
 * 4. 整组重复同样用 `− / +`；
 * 5. 底部实时显示「将这样跑」和段数，保存键只在合法时亮。
 *
 * 手动输入被收进折叠项，给高级玩家保留，但不再是必经路径。
 */
@Composable
internal fun MacroEditorBody(
    text: SettingsStrings,
    state: MacroEditorViewState,
    recent: List<RailOptionRules.RailHistoryEntry>,
    onDraftChange: (MacroDraft) -> Unit,
) {
    val draft = state.draft
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    MacroStepsEditor(text = text, draft = draft, onDraftChange = onDraftChange)
    MacroCandidatePicker(text = text, draft = draft, recent = recent, onDraftChange = onDraftChange)
    InlineAction(
        text = if (manualOpen) text.commonCollapse else text.railQuickMacroManualToggle,
        onClick = { manualOpen = !manualOpen },
        modifier = Modifier.padding(top = ShellSpace.CardGap),
    )
    if (manualOpen) {
        MacroManualInput(text = text, draft = draft, onDraftChange = onDraftChange)
    }
    MacroRepeatRow(text = text, draft = draft, onDraftChange = onDraftChange)
    MacroPreviewBlock(text = text, draft = draft)
    state.issueText?.let {
        Text(
            text = it,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = ShellSpace.CardGap),
        )
    }
    if (state.staleEdit) {
        Text(
            text = text.railQuickMacroEditStale,
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = ShellSpace.CardGap),
        )
    }
}

@Composable
private fun MacroStepsEditor(
    text: SettingsStrings,
    draft: MacroDraft,
    onDraftChange: (MacroDraft) -> Unit,
) {
    PrefCaption(text.railQuickMacroStepsHeader)
    if (draft.steps.isEmpty()) {
        EmptyHint(text = text.railQuickMacroStepsEmpty)
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        draft.steps.forEachIndexed { index, step ->
            if (index > 0) ShellRowHairline()
            MacroStepRow(
                index = index,
                step = step,
                onTimes = { times -> onDraftChange(draft.updateStepTimes(index, times)) },
                onRemove = { onDraftChange(draft.removeStep(index)) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MacroCandidatePicker(
    text: SettingsStrings,
    draft: MacroDraft,
    recent: List<RailOptionRules.RailHistoryEntry>,
    onDraftChange: (MacroDraft) -> Unit,
) {
    PrefCaption(text.railQuickMacroFromRecent)
    Text(
        text = text.railQuickMacroTapHint,
        color = UiColors.TextMuted,
        style = ShellType.Label,
    )
    if (draft.stepQuotaFull) {
        Text(
            text = text.railQuickMacroStepQuotaFull(RailOptionRules.MACRO_STEP_CAP),
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.padding(top = ShellSpace.CardGap),
        )
        return
    }
    val candidates = recent.take(MACRO_CANDIDATE_LIMIT)
    if (candidates.isEmpty()) {
        EmptyHint(text = text.railQuickMacroNoRecent)
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
        horizontalArrangement = Arrangement.spacedBy(ShellSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(ShellSpace.CardGap),
    ) {
        candidates.forEach { entry ->
            SettingChip(
                label = entry.label,
                decoration = ChipDecoration(leading = "＋", detail = entry.value),
                onClick = { onDraftChange(draft.addStep(entry.value, entry.label)) },
            )
        }
    }
}

@Composable
private fun MacroManualInput(
    text: SettingsStrings,
    draft: MacroDraft,
    onDraftChange: (MacroDraft) -> Unit,
) {
    OutlinedTextField(
        value = draft.pendingValue,
        onValueChange = { onDraftChange(draft.copy(pendingValue = it)) },
        label = { Text(text.railQuickMacroValueLabel) },
        singleLine = true,
        // 走壳层 token：缺省那套取的是系统主题的 primary（实机上是默认紫），在这张暖绿卡上像块贴错的东西。
        textStyle = ShellType.Body,
        colors = ShellField.outlined(),
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
    )
    TextButton(
        enabled = draft.pendingValue.isNotBlank() && !draft.stepQuotaFull,
        colors = ButtonDefaults.textButtonColors(
            contentColor = EratwInk.accent(),
            disabledContentColor = EratwInk.textMuted(),
        ),
        onClick = { onDraftChange(draft.appendStep()) },
    ) {
        Text(text = text.railQuickMacroAddStep, style = ShellType.Tab)
    }
}

@Composable
private fun MacroRepeatRow(
    text: SettingsStrings,
    draft: MacroDraft,
    onDraftChange: (MacroDraft) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.railQuickMacroRepeatLabel,
            color = UiColors.Text,
            style = ShellType.Body,
            modifier = Modifier.weight(1f),
        )
        ShellStepper(
            value = draft.repeat.toIntOrNull() ?: 1,
            min = 1,
            max = RailOptionRules.MACRO_REPEAT_MAX,
            onChange = { onDraftChange(draft.copy(repeat = it.toString())) },
        )
    }
}

@Composable
private fun MacroPreviewBlock(text: SettingsStrings, draft: MacroDraft) {
    val preview = (draft.build() as? MacroDraftResult.Valid)?.macro ?: return
    PrefCaption(text.railQuickMacroPreviewHeader)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
        shape = ShellShape.Inset,
        color = UiColors.CardAlt,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(ShellSpace.RowPadding)) {
            Text(
                text = text.railQuickMacroPreview(RailOptionRules.macroLabel(preview)),
                color = UiColors.Text,
                style = ShellType.Tab,
            )
            Text(
                text = text.railQuickMacroSegments(draft.previewSegmentCount()),
                color = UiColors.TextMuted,
                style = ShellType.Overline,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 一步的紧凑编辑行：序号 + 名称 + 连发次数步进器 + 移除。 */
@Composable
private fun MacroStepRow(
    index: Int,
    step: RailOptionRules.MacroStep,
    onTimes: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            color = UiColors.TextMuted,
            style = ShellType.Label,
            modifier = Modifier.widthIn(min = ShellSpace.DialogRowGap),
        )
        Text(
            text = step.label.ifBlank { step.value },
            color = UiColors.Text,
            style = ShellType.Tab,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ShellStepper(
            value = step.times,
            min = 1,
            max = RailOptionRules.MACRO_TIMES_MAX,
            onChange = onTimes,
        )
        InlineAction(text = "×", color = UiColors.TextMuted, onClick = onRemove)
    }
}

/**
 * 草稿的 `rememberSaveable` 编码：`[pendingValue, pendingTimes, repeat, (value,label,times)*]`。
 *
 * 用 listSaver 而不是让 Compose 反射保存：`MacroDraft` 里嵌了不可保存的列表元素，
 * 显式编码/解码同时把「横竖屏切换丢草稿」这条修掉（2026-11-11 复查项）。
 */
private val MacroDraftSaver = listSaver<MacroDraft, String>(
    save = { draft ->
        buildList {
            add(draft.pendingValue)
            add(draft.pendingTimes)
            add(draft.repeat)
            draft.steps.forEach { step ->
                add(step.value)
                add(step.label)
                add(step.times.toString())
            }
        }
    },
    restore = { flat ->
        if (flat.size < 3) {
            MacroDraft()
        } else {
            val steps = ArrayList<RailOptionRules.MacroStep>()
            var i = 3
            while (i + 2 < flat.size) {
                steps += RailOptionRules.MacroStep(
                    value = flat[i],
                    label = flat[i + 1],
                    times = flat[i + 2].toIntOrNull() ?: 1,
                )
                i += 3
            }
            MacroDraft(
                steps = steps,
                pendingValue = flat[0],
                pendingTimes = flat[1],
                repeat = flat[2],
            )
        }
    },
)
