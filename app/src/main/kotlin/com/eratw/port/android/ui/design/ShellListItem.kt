package com.eratw.port.android.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 壳层的**列表行套件**（2026-11-26 第二轮）：市场包行、已装行、导入读数块共用一副「行」骨架。
 *
 * 为什么不复用 `ShellSurfaceCard`：分节卡表达「一组同类设置」，带分区色带与洗色，是**页面章节**；
 * 列表行表达「一条可比较的记录」，同屏十几条、要能扫读、要能整行点开。两者的圆角、内距、描边与
 * 按压反馈都不同，硬套会让市场页变成长满色带的几百 dp 章节卡。
 *
 * 与 Material 3 的对齐点：行容器取 `surfaceContainer` 级面板底 + `outlineVariant` 发丝边、不投影；
 * 整行可点（`onClick` 非空时），按压走 [ShellPress] 的整块叠色而不是系统涟漪 —— 涟漪是圆形水波，
 * 在这种描边方块行上会溢出一圈，像把边框咬掉一块；叠色只在行内压一档，整行范围不变。
 */
@Composable
fun ShellListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    val clickModifier = if (onClick != null) {
        Modifier
            .background(ShellPress.tint(pressSource, EratwInk.text()))
            .clickable(interactionSource = pressSource, indication = null, onClick = onClick)
    } else {
        Modifier
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(ShellShape.Inset)
            .background(EratwInk.panel())
            .border(BorderStroke(1.dp, EratwInk.border()), ShellShape.Inset)
            .then(clickModifier)
            .padding(ShellSpace.RowPadding),
        content = content,
    )
}

/**
 * 行内小徽章（kind / gameId / 版本这类**静态标签**）：只有文字与一圈发丝边，不当按钮用。
 *
 * 与 [ShellTag] 的分工：`ShellTag` 是「状态提醒」（实验性、待处理），会着色抢眼；
 * 本件是「分类读数」，永远中性，同屏出现多枚也不吵。
 */
@Composable
fun ShellListBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = EratwInk.textMuted(),
) {
    Box(
        modifier = modifier
            .clip(ShellShape.Tag)
            .border(BorderStroke(1.dp, EratwInk.border()), ShellShape.Tag)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, color = tone, style = ShellType.Overline)
    }
}

/**
 * 覆盖层 / 对话框里的**小节标签**（替代各处手写的 subhead）：一行小字 + 一条同色细分隔线。
 *
 * 与 [ShellGroupHeader] 的分工：分组头是页面级的（分区色竖条 + 说明句），本件是紧凑面
 * （市场覆盖层、导入弹窗）里「这一段是什么」的最小标记，只占一行。
 */
@Composable
fun ShellRuleLabel(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = EratwInk.textDim(),
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        Text(text = text, color = tone, style = ShellType.Label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .height(1.dp)
                .background(tone.copy(alpha = RULE_ALPHA)),
        )
    }
}

/**
 * 过滤 chip（市场「全部 / 收藏 / 四 kind」这类**筛选项**）：选中态亮底 + 同色描边，未选中发丝边。
 *
 * 选中不只靠底色：描边同色、文字转正文色，读屏靠 `selected` 语义；chip 只表达「筛选/选择」，
 * 不表达「执行」（Material 3 组件语义）。
 *
 * **视觉 32dp / 热区 44dp**：原来片体自身就撑到 `RowMinHeight`，一排四五个方片连起来像一堵砖
 * 墙——片是「一串可扫的档位」，该比按钮矮一档。现在外层仍是 44dp 的可点区（`clickable` 在
 * 外层，`RowMinHeight` 兜底），里面那层视觉片收到 [CHIP_VISUAL_HEIGHT]；按压叠色按外层给的
 * interaction source 画在内层，反馈仍落在片体上而不是整条 44dp 空地。
 */
@Composable
fun ShellFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor by animateColorAsState(
        targetValue = if (selected) EratwInk.accent() else EratwInk.border(),
        animationSpec = ShellMotion.content(),
        label = "shell-chip-border",
    )
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = ShellSpace.RowMinHeight)
            .clickable(interactionSource = pressSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = CHIP_VISUAL_HEIGHT)
                .clip(ShellShape.Chip)
                .background(if (selected) EratwInk.panelAlt() else EratwInk.panel())
                .border(BorderStroke(1.dp, borderColor), ShellShape.Chip)
                .background(ShellPress.tint(pressSource, EratwInk.text())),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (selected) EratwInk.text() else EratwInk.textMuted(),
                style = ShellType.Label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 行内缩进分隔线（列表里两条记录之间，比整块描边弱一档）。 */
@Composable
fun ShellRowHairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 6.dp),
        thickness = 1.dp,
        color = EratwInk.border(),
    )
}

// 小节标签分隔线的实体不透明度：只交代「这一段从哪开始」，不做装饰。
private const val RULE_ALPHA = 0.45f

/** 筛选片的片体高度（视觉层）；外层可点区仍取 [ShellSpace.RowMinHeight]。 */
private val CHIP_VISUAL_HEIGHT = 32.dp
