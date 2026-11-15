package com.eratw.port.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.android.ui.EratwInk

/** 编辑对话框最多显示多少枚最近指令（再多的历史候选不进主视图，避免又变成长名单）。 */
private const val MACRO_CANDIDATE_LIMIT = 12

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
 * ## 本轮（复查后）改了什么
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
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                shown.forEachIndexed { index, macro ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = UiColors.CardAlt,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = RailOptionRules.macroLabel(macro),
                                    color = UiColors.Text,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    text = text.railQuickMacroSegments(
                                        RailOptionRules.macroSegmentCount(macro),
                                    ),
                                    color = UiColors.TextMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                )
                            }
                            InlineAction(text = text.railQuickMacroEdit, onClick = { openFor(index) })
                            InlineAction(
                                text = text.railQuickMacroDelete,
                                color = UiColors.TextMuted,
                                onClick = {
                                    // 删掉一条之后「已满」提示必须立刻灭：否则会出现
                                    // 「提示说满了、可「新增」又点得动」的自相矛盾。
                                    showFullHint = false
                                    onDelete(index)
                                },
                            )
                        }
                    }
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
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
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
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
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
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = EratwInk.raised(),
            tonalElevation = 0.dp,
            title = { Text(text.railQuickMacroClear) },
            text = { Text(text.railQuickMacroClearConfirm) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    showFullHint = false
                    onClear()
                }) {
                    Text(text.railQuickMacroClear, color = UiColors.Warn)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(text.railQuickMacroCancel, color = UiColors.TextMuted)
                }
            },
        )
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            shape = RoundedCornerShape(14.dp),
            containerColor = EratwInk.raised(),
            tonalElevation = 0.dp,
            title = { Text(if (editIndex >= 0) text.railQuickMacroEdit else text.railQuickMacroAdd) },
            text = {
                // 正文必须可滚：步骤 + 候选芯片 + 手动输入 + 整组重复 + 预览，
                // 横屏可用高只有 300 多 dp，不滚会把「保存/取消」挤出视区。
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
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
            },
            confirmButton = {
                TextButton(
                    enabled = result is MacroDraftResult.Valid && !draft.pendingOverQuota,
                    onClick = {
                        val macro = (result as? MacroDraftResult.Valid)?.macro ?: return@TextButton
                        if (editIndex >= 0) {
                            // 陈旧下标守卫：列表在编辑期间被删/改时不写回错误的宏。
                            if (MacroEditGuard.isStale(macros, editIndex, editSnapshot)) {
                                staleEdit = true
                                return@TextButton
                            }
                            onUpdate(editIndex, macro)
                            dialogOpen = false
                        } else if (onAdd(macro)) {
                            dialogOpen = false
                        } else {
                            showFullHint = true
                            dialogOpen = false
                        }
                    },
                ) {
                    Text(text.railQuickMacroSave)
                }
            },
            dismissButton = {
                TextButton(onClick = { dialogOpen = false }) {
                    Text(text.railQuickMacroCancel, color = UiColors.TextMuted)
                }
            },
        )
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
        modifier = Modifier.padding(top = 8.dp),
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
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (state.staleEdit) {
        Text(
            text = text.railQuickMacroEditStale,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        draft.steps.forEachIndexed { index, step ->
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
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
    if (draft.stepQuotaFull) {
        Text(
            text = text.railQuickMacroStepQuotaFull(RailOptionRules.MACRO_STEP_CAP),
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        return
    }
    val candidates = recent.take(MACRO_CANDIDATE_LIMIT)
    if (candidates.isEmpty()) {
        EmptyHint(text = text.railQuickMacroNoRecent)
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
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
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
    TextButton(
        enabled = draft.pendingValue.isNotBlank() && !draft.stepQuotaFull,
        onClick = { onDraftChange(draft.appendStep()) },
    ) {
        Text(text.railQuickMacroAddStep)
    }
}

@Composable
private fun MacroRepeatRow(
    text: SettingsStrings,
    draft: MacroDraft,
    onDraftChange: (MacroDraft) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.railQuickMacroRepeatLabel,
            color = UiColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        IntStepper(
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = UiColors.CardAlt,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = text.railQuickMacroPreview(RailOptionRules.macroLabel(preview)),
                color = UiColors.Text,
                fontSize = 13.sp,
            )
            Text(
                text = text.railQuickMacroSegments(draft.previewSegmentCount()),
                color = UiColors.TextMuted,
                fontSize = 10.sp,
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = UiColors.CardAlt,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 9.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${index + 1}",
                color = UiColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.width(18.dp),
            )
            Text(
                text = step.label.ifBlank { step.value },
                color = UiColors.Text,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IntStepper(
                value = step.times,
                min = 1,
                max = RailOptionRules.MACRO_TIMES_MAX,
                onChange = onTimes,
            )
            InlineAction(
                text = "×",
                color = UiColors.TextMuted,
                onClick = onRemove,
            )
        }
    }
}

/** 数字步进器：`− [N] +`，不要求玩家打开键盘；边界处自动禁用对应一侧。 */
@Composable
private fun IntStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        StepperButton(label = "−", enabled = value > min) {
            onChange((value - 1).coerceAtLeast(min))
        }
        Text(
            text = value.toString(),
            color = UiColors.Text,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp),
        )
        StepperButton(label = "+", enabled = value < max) {
            onChange((value + 1).coerceAtMost(max))
        }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = UiColors.Card,
    ) {
        Text(
            text = label,
            color = if (enabled) UiColors.TextDim else UiColors.TextMuted.copy(alpha = 0.35f),
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 2.dp),
        )
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
