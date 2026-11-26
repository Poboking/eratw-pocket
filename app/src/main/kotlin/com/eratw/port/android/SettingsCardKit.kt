package com.eratw.port.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.ShellCaption
import com.eratw.port.android.ui.design.ShellPress
import com.eratw.port.android.ui.design.ShellSectionTitle
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellSurfaceCard
import com.eratw.port.android.ui.design.ShellTag
import com.eratw.port.android.ui.design.ShellType

/**
 * 设置页的**卡片外壳套件**（2026-11-11 从 SettingsActivity 抽出）：分区配色、
 * 卡片容器、标题、小节标题，以及「当前分区主题色」这条 CompositionLocal。
 *
 * 2026-11-12 补三件长名单卡共用的紧凑件：计数徽标、行内文字按钮、可折叠芯片流、
 * 空态提示。右栏过滤 / 常用置顶 / 快捷宏的条目数会随玩家使用增长，继续逐行竖排
 * 会把一张卡拉到上千 dp；这里把它们收成「芯片流 + 默认预览 + 展开全部」，让设置页
 * 的其它功能卡始终在两三屏内可达。
 *
 * ## 为什么要单独成文件（解耦口径）
 *
 * 抽出前它们和 `SettingsActivity` 里 5000 多行的页面代码焊在一起，任何一张功能卡都
 * 没法被单独渲染/预览/复用。抽出后：
 * - 设置页照旧用（同包、同名，调用点零改动）；
 * - 卡片套件与页面代码分家后，任何一张功能卡都能被单独复用/单测（`internal` 可见性）；
 *   注意 `tools/uipreview` 的壳层预览口当前**没有**拷这些文件（它只拷
 *   `RailOptionRules.kt` / `QuickMacroUi.kt`），要把它也纳入预览需另加拷贝清单，别照注释臆断。
 *
 * 颜色值一律来自 [EratwInk]（壳层唯一墨色源），这里不写任何字面色值。
 */
internal object UiColors {
    val Background: Color = EratwInk.background()
    val Card: Color = EratwInk.panel()
    val CardAlt: Color = EratwInk.panelAlt()
    val Border: Color = EratwInk.border()
    val Text: Color = EratwInk.text()
    val TextDim: Color = EratwInk.textDim()
    val TextMuted: Color = EratwInk.textMuted()
    val Accent: Color = EratwInk.accent()
    val Warn: Color = EratwInk.warn()
    val PrimaryButton: Color = EratwInk.primaryButton()

    // 四个分区各自一色：卡片左侧细条、卡片标题、分区带都用它，扫一眼就知道自己在哪一档。
    // 移植版沿用原来的浅蓝（不动老玩家的既视感）；原版引擎取紫，和警告橙、按钮绿都不撞；
    // Mod 取低饱和铜金（暖调、但比警告橙暗）；关于/反馈取主题主绿的亮档。
    val ZonePort: Color = EratwInk.zonePort()
    val ZoneEngine: Color = EratwInk.zoneEngine()
    val ZoneMod: Color = EratwInk.zoneMod()
    val ZoneAbout: Color = EratwInk.zoneAbout()
}

internal val LocalZoneAccent = compositionLocalOf { UiColors.ZonePort }


@Composable
internal fun PrefCaption(text: String) {
    ShellCaption(text)
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    ShellSurfaceCard(accent = LocalZoneAccent.current, content = content)
}

@Composable
internal fun SectionTitle(text: String) {
    ShellSectionTitle(text = text, accent = LocalZoneAccent.current)
}

/**
 * 功能卡标题行：标题靠左，右侧可挂一枚计数徽标与一个行内动作。
 *
 * 计数徽标把「8/8」这类读数从竖排正文提到标题同一行，省掉一行高度；行内动作
 * （如「清空常用」）也放在这里，避免在长名单底部再长出一行按钮。
 */
@Composable
internal fun CardHeading(
    title: String,
    badge: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    actionColor: Color = LocalZoneAccent.current,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            SectionTitle(title)
        }
        if (badge != null) {
            CountBadge(badge)
        }
        if (actionText != null && onAction != null) {
            Box(modifier = Modifier.padding(start = 6.dp)) {
                InlineAction(text = actionText, onClick = onAction, color = actionColor)
            }
        }
    }
}

/** 标题旁的中性计数徽标（如 `4/8`、`12`）：只做轻量读数，不抢主标题。 */
@Composable
internal fun CountBadge(text: String) {
    Text(
        text = text,
        color = UiColors.TextMuted,
        style = ShellType.Label,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * 行内文字动作：比 Material `TextButton` 少掉 48dp 最小触控高度，用于卡片标题右侧
 * 或紧凑列表尾部。默认使用次级文字色，危险操作由调用侧单独点色。
 *
 * 关掉系统涟漪、改用 [ShellPress] 的按下叠色：涟漪是圆形水波，在像素风卡片上会盖掉
 * 字；叠色只在字这一小块上压暗一档，点中了照样看得见。禁用态靠字色降级（不叠色）。
 */
@Composable
internal fun InlineAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = UiColors.TextDim,
    enabled: Boolean = true,
) {
    val pressSource = remember { MutableInteractionSource() }
    Text(
        text = text,
        color = if (enabled) color else UiColors.TextMuted,
        style = ShellType.Caption,
        modifier = modifier
            .clip(ShellShape.Chip)
            .background(if (enabled) ShellPress.tint(pressSource, color) else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = pressSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** 芯片的前导符号 / 细节读数 / 尾部符号；空 = 不画对应位置。 */
internal data class ChipDecoration(
    val leading: String? = null,
    val detail: String? = null,
    val trailing: String? = null,
)

/**
 * 紧凑芯片：标签 + 可选前导符号 / 细节读数 / 尾部符号。
 *
 * 视觉上刻意压低存在感：无描边、单层中性底、符号与读数只用次级文字色。芯片流比
 * 逐行列表省一半以上高度，也让长标签能自然折行（单枚最多 260dp，超长省略）。
 */
@Composable
internal fun SettingChip(
    label: String,
    onClick: () -> Unit,
    decoration: ChipDecoration = ChipDecoration(),
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 260.dp),
        shape = ShellShape.Inset,
        color = UiColors.CardAlt,
    ) {
        val pressSource = remember { MutableInteractionSource() }
        Row(
            modifier = Modifier
                .background(ShellPress.tint(pressSource, UiColors.Text))
                .clickable(
                    interactionSource = pressSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (decoration.leading != null) {
                Text(
                    text = decoration.leading,
                    color = UiColors.TextMuted,
                    style = ShellType.Caption,
                )
            }
            Text(
                text = label,
                color = UiColors.Text,
                style = ShellType.Caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            if (decoration.detail != null) {
                Text(
                    text = decoration.detail,
                    color = UiColors.TextMuted,
                    style = ShellType.Overline,
                    maxLines = 1,
                )
            }
            if (decoration.trailing != null) {
                Text(
                    text = decoration.trailing,
                    color = UiColors.TextMuted,
                    style = ShellType.Caption,
                )
            }
        }
    }
}

/** 可折叠芯片流的两个按钮文案：展开 = 拿完整条数拼「展开全部（N）」，收起 = 固定文案。 */
internal data class CollapseLabels(
    val showAll: (Int) -> String,
    val collapse: String,
)

/**
 * 可折叠芯片流：默认只画 [previewCount] 枚，余下的收进「展开全部（N）」。
 *
 * 折叠状态用 `rememberSaveable` 存活横竖屏切换；条数不超过预览上限时**不画切换按钮**，
 * 避免短名单也出现一条多余的行。真正的条数截断走 [previewOf]，由定向单测钉住。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> CollapsibleChipFlow(
    items: List<T>,
    previewCount: Int,
    labels: CollapseLabels,
    modifier: Modifier = Modifier,
    chip: @Composable (T) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val visible = previewOf(items, expanded = expanded, limit = previewCount)
    Column(modifier = modifier.fillMaxWidth()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            visible.forEach { item -> chip(item) }
        }
        if (items.size > previewCount) {
            InlineAction(
                text = if (expanded) labels.collapse else labels.showAll(items.size),
                onClick = { expanded = !expanded },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 空态提示：淡底 + 描边的一块说明文字，比裸 Text 更容易和卡片正文区分。 */
@Composable
internal fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        shape = ShellShape.Inset,
        color = UiColors.CardAlt,
        border = BorderStroke(1.dp, UiColors.Border),
    ) {
        Text(
            text = text,
            color = UiColors.TextMuted,
            style = ShellType.Label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * 标题 + 说明 + 右侧开关的一整行：整行可点（点空白也能切），不是只点 Switch 小圆点。
 *
 * 2026-11-12 从 SettingsActivity 移入卡片套件：右栏过滤卡独立成文件后也要用这一行，
 * 而它原本是 Activity 文件里的 private 件。
 */
@Composable
internal fun SwitchRow(
    title: String,
    hint: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String? = null,
) {
    val pressSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ShellSpace.RowMinHeight)
            .clip(ShellShape.Chip)
            .background(ShellPress.tint(pressSource, UiColors.Text))
            .toggleable(
                value = checked,
                interactionSource = pressSource,
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = UiColors.Text, style = ShellType.Body)
                if (tag != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ShellTag(text = tag)
                }
            }
            if (hint != null) {
                Text(hint, color = UiColors.TextMuted, style = ShellType.Label)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = UiColors.Text,
                checkedTrackColor = LocalZoneAccent.current.copy(alpha = 0.55f),
                checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = UiColors.TextMuted,
                uncheckedTrackColor = UiColors.CardAlt,
                uncheckedBorderColor = UiColors.Border,
            ),
        )
    }
}
