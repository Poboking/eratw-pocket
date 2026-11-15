package com.eratw.port.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 快捷宏的**唯一前端实现**（2026-11-11）：一枚宏按钮长什么样、一排怎么摆，只此一份。
 *
 * ## 为什么单独成文件（解耦口径）
 *
 * 快捷宏要同时出现在两套壳里：横屏右栏的「常用区」和竖屏底部的「指令盘」。两套壳的
 * 尺寸体系完全不同（横屏走 [RailMetrics] 档位、竖屏走设计稿 §11 的 token 表），但
 * **按钮的语义与交互必须一模一样** —— 点一下 = 提交玩家写的指令集串。如果两边各写一份
 * Box/Text，配色、省略号、禁用态、点击语义迟早漂移；所以这里收成三个可复用件：
 *
 * 1. [QuickMacroChipStyle] —— 视觉参数（颜色/字号/高度/圆角），两套壳各给一个预设；
 * 2. [QuickMacroChip] —— 单枚按钮（唯一点击通路，`onClick` 注入，不碰任何业务）；
 * 3. [QuickMacroSection] —— 小标题 + 网格 + 末行补位（列数 1 = 横屏整宽列、N = 竖屏网格）。
 *
 * 数据模型与代数在 [RailOptionRules.QuickMacro]（纯 JVM 可测），提交路径在宿主
 * （`GameHost.submit`，键盘路）。本文件**不读偏好、不判语言、不做提交**：文案由调用侧
 * 从三语表取好传进来（`PortraitShellCopy.railQuickMacroHeader`）。
 */
internal data class QuickMacroChipStyle(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color?,
    val fontSize: TextUnit,
    val cornerRadius: Dp,
    /** 定高（横屏按钮走档位高度）。null = 用 [minHeight] 由内容撑（竖屏设计稿口径）。 */
    val fixedHeight: Dp?,
    val minHeight: Dp,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val headerColor: Color,
    val headerStyle: TextStyle,
    /** 网格间距（横屏沿用右栏行距，竖屏沿用设计稿 5dp）。 */
    val spacing: Dp,
)

/**
 * 横屏右栏的宏按钮样式：与普通候选按钮**同一套**几何（档位高度/圆角/配色），但没有
 * 序号角标 —— 宏不是引擎给编号的指令。
 *
 * 参数刻意只收**基本量**（字号/高/两个颜色）而不是 `RailMetrics`：本文件要能被
 * `tools/uipreview` 的壳层预览口原样编译（纯 JVM + Compose Desktop，拿不到 Android 模块里的
 * `RailMetrics`）。真机侧的适配在 `ShellUi.kt` 的 `railQuickMacroStyle(metrics)` 里做。
 */
internal fun railQuickMacroStyle(
    labelTextSize: TextUnit,
    buttonHeight: Dp,
    containerColor: Color,
    contentColor: Color,
): QuickMacroChipStyle = QuickMacroChipStyle(
    containerColor = containerColor,
    contentColor = contentColor,
    borderColor = null,
    fontSize = labelTextSize,
    cornerRadius = 7.dp,
    fixedHeight = buttonHeight,
    minHeight = buttonHeight,
    horizontalPadding = 6.dp,
    verticalPadding = 2.dp,
    headerColor = Color(0xFF9A9A9A),
    headerStyle = TextStyle(fontSize = labelTextSize),
    spacing = 4.dp,
)

/**
 * 竖屏指令盘的宏按钮样式：设计稿 §11 token 表的**命令钮**口径（宏提交的就是指令串），
 * 高度 34dp 与指令盘按钮同档（§4.3「min-height 34dp」）。
 */
internal fun portraitQuickMacroStyle(): QuickMacroChipStyle = QuickMacroChipStyle(
    containerColor = Color(0xFF24382A),
    contentColor = Color(0xFFD8ECD8),
    borderColor = Color(0x8C7FAF7F),
    fontSize = 12.sp,
    cornerRadius = 8.dp,
    fixedHeight = null,
    minHeight = 34.dp,
    horizontalPadding = 4.dp,
    verticalPadding = 4.dp,
    headerColor = Color(0xFF7FAF7F),
    headerStyle = TextStyle(fontSize = 10.sp),
    spacing = 5.dp,
)

/** 单枚快捷宏按钮：单行标签（超出省略号），禁用态压暗；点击语义由调用侧注入。 */
@Composable
internal fun QuickMacroChip(
    label: String,
    enabled: Boolean,
    style: QuickMacroChipStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(style.cornerRadius)
    val container = if (enabled) style.containerColor else style.containerColor.copy(alpha = 0.4f)
    val content = if (enabled) style.contentColor else style.contentColor.copy(alpha = 0.6f)
    var boxModifier = modifier
        .background(container, shape)
    style.borderColor?.let { boxModifier = boxModifier.border(1.dp, it, shape) }
    boxModifier = if (style.fixedHeight != null) {
        boxModifier.height(style.fixedHeight)
    } else {
        boxModifier.heightIn(min = style.minHeight)
    }
    Box(
        modifier = boxModifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = style.horizontalPadding, vertical = style.verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = content,
            fontSize = style.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 快捷宏小节（小标题 + 网格）：**横竖屏共用**。`columns = 1` 就是横屏的整宽单列；
 * 竖屏传指令盘同一档列数，末行用等宽空位补满（与 [com.eratw.port.android.portrait]
 * 的指令网格同一口径）。[macros] 为空时整节不渲染，不占高度。
 */
@Composable
internal fun QuickMacroSection(
    header: String,
    macros: List<RailOptionRules.QuickMacro>,
    columns: Int,
    enabled: Boolean,
    style: QuickMacroChipStyle,
    onQuickMacro: (RailOptionRules.QuickMacro) -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
) {
    if (macros.isEmpty()) return
    val cols = columns.coerceAtLeast(1)
    Column(
        modifier = modifier.fillMaxWidth().padding(top = topPadding),
        verticalArrangement = Arrangement.spacedBy(style.spacing),
    ) {
        Text(
            text = header,
            color = style.headerColor,
            style = style.headerStyle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp),
        )
        macros.chunked(cols).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(style.spacing),
            ) {
                chunk.forEach { macro ->
                    QuickMacroChip(
                        label = RailOptionRules.macroLabel(macro),
                        enabled = enabled,
                        style = style,
                        modifier = Modifier.weight(1f),
                        onClick = { onQuickMacro(macro) },
                    )
                }
                // 末行补位：保持与满行同宽的等分网格（跨行对齐，不随枚数抖动）。
                repeat(cols - chunk.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
