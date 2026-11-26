package com.eratw.port.android.ui.design

import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import com.eratw.port.android.ui.EratwInk

/**
 * 壳层的**输入框配色**（单一源）。
 *
 * 输入框此前散在两处各写一份 `OutlinedTextFieldDefaults.colors(…)`：主界面输入栏写对了，
 * 快捷宏编辑器里的手输框却一件没给 —— 缺省那套取 `MaterialTheme` 的 primary/onSurface，
 * 而壳层从来没装配自己的 M3 主题，实机上就落成**默认紫底板 + 默认紫下划线**，
 * 与暖绿卡片像两块贴错的东西。
 *
 * 这里把「静息 / 聚焦 / 禁用 / 错误」四挡一次定死：底色与卡片面板同族、聚焦描边取主按钮绿、
 * 错误描边取警示橙；字色只分正文与次级两档（禁用不降字号，只降字色）。
 * 形状由调用点给（输入栏用 [ShellShape.Chip]，多行正文用 [ShellShape.Inset]）。
 */
object ShellField {

    /** 描边输入框的配色（四挡齐备，调用点不必再自写色值）。 */
    @Composable
    fun outlined(): TextFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = EratwInk.text(),
        unfocusedTextColor = EratwInk.text(),
        disabledTextColor = EratwInk.textMuted(),
        focusedContainerColor = EratwInk.panelAlt(),
        unfocusedContainerColor = EratwInk.panelAlt(),
        disabledContainerColor = EratwInk.panel(),
        errorContainerColor = EratwInk.panelAlt(),
        focusedBorderColor = EratwInk.primaryButton(),
        unfocusedBorderColor = EratwInk.raised(),
        disabledBorderColor = EratwInk.hairline(),
        errorBorderColor = EratwInk.danger(),
        focusedLabelColor = EratwInk.textDim(),
        unfocusedLabelColor = EratwInk.textMuted(),
        disabledLabelColor = EratwInk.textMuted(),
        errorLabelColor = EratwInk.danger(),
        cursorColor = EratwInk.accent(),
    )
}
