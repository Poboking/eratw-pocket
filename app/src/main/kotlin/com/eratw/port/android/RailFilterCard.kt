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

/**
 * 「右栏过滤」功能卡（2026-11-11 QQ 群反馈波，2026-11-12 改紧凑芯片流）：
 * 规则开关 + 隐藏名单管理。
 *
 * 两种判据叠在一起：规则开关按判据整族收编（只留屏上自印编号的常用指令），黑名单
 * 则玩家逐枚点名 —— 长按右栏按钮「从右栏隐藏」进黑名单（按标签精确匹配，横竖屏
 * 共用同一条数据流），本卡负责恢复/清空。两者互不依赖，叠加生效。
 *
 * 隐藏名单没有硬上限：旧版每条占一整行 + 一颗「恢复」按钮，藏几十枚就能把卡片
 * 拉到上千 dp，下面的「常用置顶 / 快捷宏」要滑很久才看得到。现在收成芯片流，
 * 默认只露 [SettingsListPreview.CHIP] 枚，其余点「展开全部」再看；点芯片即恢复。
 */
@Composable
internal fun RailFilterCard(
    text: SettingsStrings,
    hideUnnumbered: Boolean,
    actions: RailFilterActions,
    hiddenLabels: Set<String>,
) {
    SettingsCard {
        CardHeading(
            title = text.railFilterTitle,
            badge = hiddenLabels.size.toString(),
        )
        SwitchRow(
            title = text.railFilterRuleLabel,
            hint = text.railFilterRuleHint,
            checked = hideUnnumbered,
            onCheckedChange = actions.onHideUnnumbered,
        )
        HorizontalDivider(color = UiColors.Border, modifier = Modifier.padding(vertical = 8.dp))
        if (hiddenLabels.isEmpty()) {
            Text(
                text = text.railFilterHiddenHeader,
                color = UiColors.TextDim,
                fontSize = 12.sp,
            )
            EmptyHint(text = text.railFilterEmpty)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    PrefCaption(text.railFilterHiddenHeader)
                }
                InlineAction(
                    text = text.railFilterClear,
                    color = UiColors.TextMuted,
                    onClick = actions.onClearHidden,
                )
            }
            // 黑名单是 StringSet（无序），排序后渲染；否则每次重组顺序都可能变、看着像在抖。
            CollapsibleChipFlow(
                items = hiddenLabels.sorted(),
                previewCount = SettingsListPreview.CHIP,
                labels = CollapseLabels(
                    showAll = { total -> text.commonShowAll(total) },
                    collapse = text.commonCollapse,
                ),
            ) { label ->
                SettingChip(
                    label = label,
                    decoration = ChipDecoration(trailing = "×"),
                    onClick = { actions.onRestore(label) },
                )
            }
        }
    }
}







/** 「右栏过滤」卡的全部写操作；收成一件，避免 Composable 参数表随动作数膨胀。 */
internal data class RailFilterActions(
    val onHideUnnumbered: (Boolean) -> Unit,
    val onRestore: (String) -> Unit,
    val onClearHidden: () -> Unit,
)
