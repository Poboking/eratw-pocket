package com.eratw.port.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 「常用置顶」卡的全部写操作；收成一件，避免 Composable 参数表随动作数膨胀。 */
internal data class RailPinnedActions(
    val onUnpin: (String) -> Unit,
    val onPin: (String) -> Unit,
    val onClearPinned: () -> Unit,
)

/**
 * 「常用置顶」功能卡（2026-11-11 QQ 群反馈波，2026-11-12 改紧凑芯片流）：
 * 已置顶列表（逐条取消/清空）+ 历史候选勾选新增 + 底部提示（显示规则 + 长按入口）。
 *
 * 历史候选由壳层在换屏时自动并入（`UiPrefs.recordRailHistory`：只收带编号指令、
 * 名字取最新），设置页只读它 —— 玩家不用背编号也能把常用指令加进常用区。
 * 在这里新增的置顶项同样遵守显示硬规则：**当前画面里出现同值按钮才显示**。
 *
 * 旧版把「已置顶」和「历史候选」都逐条竖排，历史候选上限 32 条 ⇒ 一张卡能拉到
 * 1600dp 以上，下面所有功能卡都要滑很久。现在两条名单都收成芯片流：
 * 已置顶默认露 [SettingsListPreview.PINNED] 枚、历史候选默认露
 * [SettingsListPreview.HISTORY] 枚，其余点「展开全部」；点芯片即取消置顶 / 新增。
 */
@Composable
internal fun RailPinnedCard(
    text: SettingsStrings,
    pinned: List<String>,
    history: List<RailOptionRules.RailHistoryEntry>,
    pinnedCap: Int,
    actions: RailPinnedActions,
) {
    // 名字优先取历史（最近一次见到的实时文案），找不到才兜「编号 {0}」。
    val labelOf: (String) -> String = { value ->
        history.firstOrNull { it.value == value }?.label?.takeIf { it.isNotBlank() }
            ?: text.railPinnedUnknown(value)
    }
    val candidates = history.filter { it.value !in pinned }
    SettingsCard {
        CardHeading(
            title = text.railPinnedTitle,
            badge = "${pinned.size}/$pinnedCap",
            actionText = text.railPinnedClear.takeIf { pinned.isNotEmpty() },
            onAction = actions.onClearPinned.takeIf { pinned.isNotEmpty() },
            actionColor = UiColors.TextMuted,
        )
        PinnedChipSection(text = text, pinned = pinned, labelOf = labelOf, onUnpin = actions.onUnpin)
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                PrefCaption(text.railPinnedHistoryHeader)
            }
            if (candidates.isNotEmpty()) {
                CountBadge(text = candidates.size.toString())
            }
        }
        HistoryChipSection(text = text, candidates = candidates, onPin = actions.onPin)
        Text(
            text = text.railPinnedHint,
            color = UiColors.TextMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun PinnedChipSection(
    text: SettingsStrings,
    pinned: List<String>,
    labelOf: (String) -> String,
    onUnpin: (String) -> Unit,
) {
    if (pinned.isEmpty()) {
        EmptyHint(text = text.railPinnedEmpty)
        return
    }
    CollapsibleChipFlow(
        items = pinned,
        previewCount = SettingsListPreview.PINNED,
        labels = CollapseLabels(
            showAll = { total -> text.commonShowAll(total) },
            collapse = text.commonCollapse,
        ),
    ) { value ->
        SettingChip(
            label = labelOf(value),
            decoration = ChipDecoration(trailing = "×"),
            onClick = { onUnpin(value) },
        )
    }
}

@Composable
private fun HistoryChipSection(
    text: SettingsStrings,
    candidates: List<RailOptionRules.RailHistoryEntry>,
    onPin: (String) -> Unit,
) {
    if (candidates.isEmpty()) {
        EmptyHint(text = text.railPinnedHistoryEmpty)
        return
    }
    CollapsibleChipFlow(
        items = candidates,
        previewCount = SettingsListPreview.HISTORY,
        labels = CollapseLabels(
            showAll = { total -> text.commonShowAll(total) },
            collapse = text.commonCollapse,
        ),
    ) { entry ->
        SettingChip(
            label = entry.label,
            decoration = ChipDecoration(leading = "＋", detail = entry.value),
            onClick = { onPin(entry.value) },
        )
    }
}
