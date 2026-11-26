package com.eratw.port.android.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 确认框的两个动作（2026-11-26 第三轮：把散布在全壳的 `AlertDialog` 原样调用收成一处）。
 *
 * 为什么收：柜面此前每个确认框都自己写一遍 `shape = RoundedCornerShape(14.dp)`、
 * `containerColor = EratwInk.raised()`、`tonalElevation = 0.dp`，标题与按钮字阶各写各的；
 * 十六处调用就有十六份「差不多但不一样」的确认框。收成数据类之后，调用侧只描述**语义**
 * （谁是出路、谁危险、能不能点），长相归 [ShellAlertDialog]。
 */
data class ShellDialogActions(
    /** 确认键文案（「保存」「清除」「知道」）。 */
    val confirmLabel: String,
    val onConfirm: () -> Unit,
    /** 确认键是否可用（校验不过时灰着，不靠点下去再报错）。 */
    val confirmEnabled: Boolean = true,
    /** 确认键的语义色：危险动作传 `EratwInk.danger()` / `warn()`，常规留 null 走主题 accent。 */
    val confirmTone: Color? = null,
    /** 取消键文案；null = 不画取消键（单动作知会框）。 */
    val dismissLabel: String? = null,
    /** 取消键动作；null 时退回 [ShellAlertDialog] 的 onDismissRequest。 */
    val onDismiss: (() -> Unit)? = null,
)

/**
 * 壳层确认框（Material 3 `AlertDialog` + 壳层语义 token）。
 *
 * 与平台侧 `EratwShellDialog` 的分工：那边是**宿主线程弹的 View 弹窗**（存档询问、更新推荐，
 * 代码路径不在 Compose 里），这边是 Compose 世界里的一切确认框。两面共用同一套
 * `ShellShape` / `ShellType` / `EratwInk`，所以玩家看到的是同一副长相。
 *
 * 三条口径：
 * 1. 圆角取 [ShellShape.Sheet]（与底部弹层同档），面板底取 `raised`（比正文卡片亮一档：
 *    Compose 弹窗浮在设置页之上，亮一档才读得出「这层在上面」）；
 * 2. 标题 `titleSmall`、按钮 `labelLarge` 级（[ShellType.Tab]）—— 与壳层其它按钮同拍，不随
 *    Material 默认字阶跑；
 * 3. 危险动作**只靠颜色还不够**：调用侧应给出动词本身（「清除」「卸载」），颜色是第二重提示。
 */
@Composable
fun ShellAlertDialog(
    title: String,
    actions: ShellDialogActions,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = ShellShape.Sheet,
        containerColor = EratwInk.raised(),
        tonalElevation = 0.dp,
        titleContentColor = EratwInk.text(),
        textContentColor = EratwInk.textDim(),
        title = {
            Text(text = title, color = EratwInk.text(), style = ShellType.Section)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ShellSpace.DialogRowGap)) {
                body()
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirm, enabled = actions.confirmEnabled) {
                Text(
                    text = actions.confirmLabel,
                    // 禁用态不能只靠「点了没反应」：颜色一起压下去，玩家一眼知道还差什么。
                    color = if (actions.confirmEnabled) {
                        actions.confirmTone ?: EratwInk.accent()
                    } else {
                        EratwInk.textMuted()
                    },
                    style = ShellType.Tab,
                )
            }
        },
        dismissButton = actions.dismissLabel?.let { label ->
            {
                TextButton(onClick = { (actions.onDismiss ?: onDismissRequest)() }) {
                    Text(text = label, color = EratwInk.textMuted(), style = ShellType.Tab)
                }
            }
        },
    )
}
