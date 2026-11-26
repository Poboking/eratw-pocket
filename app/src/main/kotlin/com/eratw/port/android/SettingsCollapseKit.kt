package com.eratw.port.android

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.eratw.port.android.ui.design.DisclosureStyle
import com.eratw.port.android.ui.design.ShellChevron
import com.eratw.port.android.ui.design.ShellDisclosureHeader
import com.eratw.port.android.ui.design.ShellDisclosureSection

/**
 * 设置页的**折叠件适配层**（2026-11-26 二次收口）。
 *
 * 第一次收口（同日早些时候）把折叠标题行与可折叠卡写在本文件里，实现和动效常量也跟着落在
 * 设置页包内；结果 Mod 卡、市场卡想复用只能跨文件抄 `AnimatedVisibility` + 4 个毫秒常量。
 * 现在真正的实现与节奏已上提到 `ui/design`（[ShellDisclosureHeader] /
 * [ShellDisclosureSection] / [ShellChevron] / `ShellMotion`），本文件只剩三行转接：
 *
 * - 调用点（`SettingsActivity`、`KojoManageCard`）**零改动**，不用为了换实现去动 8000 行页；
 * - 分区色仍由设置页的 `LocalZoneAccent` 注入，样式与旧版逐像素同源（圆角/内距/色带
 *   都取自 `ShellShape` / `ShellSpace`）；
 * - 想换节奏改 `ShellMotion` 一处，设置页与其它壳层同拍，不再各调各的毫秒数。
 */
@Composable
internal fun CollapsibleHeading(
    title: String,
    badge: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    ShellDisclosureHeader(
        title = title,
        expanded = expanded,
        onToggle = onToggle,
        accent = LocalZoneAccent.current,
        badge = badge,
    )
}

/** 折叠箭头（设置页口径：次级文字色）。 */
@Composable
internal fun ExpandChevron(expanded: Boolean, modifier: Modifier = Modifier) {
    ShellChevron(expanded = expanded, color = UiColors.TextDim, modifier = modifier)
}

/**
 * 可折叠功能卡：标题行 + 常驻说明句 + 可折叠正文，默认展开、折叠态存活转屏。
 *
 * 形参表与语义与旧实现一致（`title` / `hint` / 末尾正文 lambda），实现见 [ShellDisclosureSection]。
 */
@Composable
internal fun CollapsibleSettingsCard(
    title: String,
    hint: String?,
    content: @Composable ColumnScope.() -> Unit,
) {
    ShellDisclosureSection(
        title = title,
        style = DisclosureStyle(accent = LocalZoneAccent.current, hint = hint),
        content = content,
    )
}
