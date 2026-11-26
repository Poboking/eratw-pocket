package com.eratw.port.android.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 揭示式分节的**样式参数**：分区色 + 常驻说明 + 计数 + 初始展开态。
 *
 * 收成一个对象是为了让 [ShellDisclosureSection] 的形参停在「标题 / 样式 / 正文 / 修饰」
 * 四件上（detekt 的 LongParameterList 阈下），调用侧也不必逐个补齐用不到的位。
 */
data class DisclosureStyle(
    val accent: Color,
    val hint: String? = null,
    val badge: String? = null,
    val initiallyExpanded: Boolean = true,
)

/**
 * 壳层的**揭示式分节**（2026-11-26 把设置页的 `CollapsibleSettingsCard` 提级为通用件）：
 * 标题行（标题 + 可选计数 + 折叠箭头，整行可点）+ 常驻说明句 + 可折叠正文。
 *
 * 提出来的理由和动效同源一样：设置页、Mod 档、以后的任何长卡都该是同一副骨架、同一套节奏，
 * 而不是每出现一张长卡就抄一遍 `AnimatedVisibility` + 4 个毫秒常量。折叠态用
 * `rememberSaveable`，转屏 / 换档不失；默认**展开**，折叠只是给玩家一个「先看标题」的选项。
 *
 * 与 Material 3 的对齐点：标题行最小高度 [ShellSpace.RowMinHeight]，满足触控下限；箭头旋转
 * 与正文揭示同拍（[ShellMotion.chevron] / [ShellMotion.disclosureEnter]）；折叠状态不只靠
 * 箭头方向表达 —— 正文消失、计数徽标留在标题行，读屏也读得到标题与读数。
 */
@Composable
fun ShellDisclosureSection(
    title: String,
    style: DisclosureStyle,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(style.initiallyExpanded) }
    ShellSurfaceCard(accent = style.accent, modifier = modifier) {
        ShellDisclosureHeader(
            title = title,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            accent = style.accent,
            badge = style.badge,
        )
        if (style.hint != null) ShellCaption(style.hint)
        AnimatedVisibility(
            visible = expanded,
            enter = ShellMotion.disclosureEnter(),
            exit = ShellMotion.disclosureExit(),
        ) {
            Column { content() }
        }
    }
}

/**
 * 揭示式标题行：整行可点（不是只点那枚箭头），左侧标题吃掉余宽，右侧计数与箭头固定。
 *
 * 单独导出是为了让「只换标题行、不动卡体」的场景（Mod 条目的行级展开）复用同一行，
 * 不必为了一个箭头再造一张卡。
 */
@Composable
fun ShellDisclosureHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    accent: Color,
    badge: String? = null,
) {
    val pressSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShellShape.Chip)
            .background(ShellPress.tint(pressSource, accent))
            .clickable(
                interactionSource = pressSource,
                indication = null,
                role = Role.Button,
                onClick = onToggle,
            )
            .heightIn(min = ShellSpace.RowMinHeight)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ShellSectionTitle(text = title, accent = accent)
        }
        if (badge != null) {
            Text(
                text = badge,
                color = EratwInk.textMuted(),
                style = ShellType.Label,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        ShellChevron(expanded = expanded, color = EratwInk.textDim())
    }
}

/**
 * 折叠箭头：展开朝下、收起朝右，切换时补间旋转。
 *
 * 纯图形件，不走文案表；颜色由调用方给（设置页取次级文字色，深色浮层可换更亮一档）。
 */
@Composable
fun ShellChevron(expanded: Boolean, color: Color, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) CHEVRON_EXPANDED_DEGREES else CHEVRON_COLLAPSED_DEGREES,
        animationSpec = ShellMotion.chevron(),
        label = "shell-chevron",
    )
    Canvas(
        modifier = modifier
            .size(CHEVRON_SIZE)
            .graphicsLayer { rotationZ = rotation },
    ) {
        val strokeWidth = CHEVRON_STROKE_DP.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * CHEVRON_LEFT_X, size.height * CHEVRON_TOP_Y)
            lineTo(size.width * CHEVRON_CENTER_X, size.height * CHEVRON_BOTTOM_Y)
            lineTo(size.width * CHEVRON_RIGHT_X, size.height * CHEVRON_TOP_Y)
        }
        drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
    }
}

// 箭头几何（相对 20dp 画布的比例）：三点折线，展开朝下、收起朝右。
private val CHEVRON_SIZE = 20.dp
private const val CHEVRON_STROKE_DP = 1.6f
private const val CHEVRON_LEFT_X = 0.24f
private const val CHEVRON_TOP_Y = 0.38f
private const val CHEVRON_CENTER_X = 0.50f
private const val CHEVRON_BOTTOM_Y = 0.64f
private const val CHEVRON_RIGHT_X = 0.76f
private const val CHEVRON_COLLAPSED_DEGREES = -90f
private const val CHEVRON_EXPANDED_DEGREES = 0f
