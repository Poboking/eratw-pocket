package com.eratw.port.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 壳层**分节卡外壳**（2026-11-26 从 `SettingsCard` 的绘制逻辑提级到 ui/design）：
 * 圆角面板 + 1dp 发丝边 + 分区色洗色 + 左侧 3dp 色带。
 *
 * 设置页、Mod 卡、市场卡此前各写一份「Card + 描边 + 手绘色带」，圆角半径与内距各是各的；
 * 收到这里之后三者同一副骨架，观感随 token 改而一起改。**只做骨架，不放标题**：标题行
 * 由 [ShellSectionTitle] / [ShellDisclosureHeader] 这类件组合进来，页面仍决定自己的信息顺序。
 *
 * 着色遵循 Material 3 的 tonal surface 口径：不是把分区色刷成整块底，而是在面板底上叠
 * 4.5% 的同色洗色 —— 扫一眼知道「这块属于哪个分区」，但正文对比度不受影响。
 */
@Composable
fun ShellSurfaceCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = ShellShape.Card,
            colors = CardDefaults.cardColors(containerColor = EratwInk.panel()),
            border = BorderStroke(1.dp, EratwInk.border()),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ShellTint.wash(accent))
                    .padding(
                        start = ShellSpace.CardStart,
                        end = ShellSpace.CardEnd,
                        top = ShellSpace.CardVertical,
                        bottom = ShellSpace.CardVertical,
                    ),
                content = content,
            )
        }
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = ShellSpace.AccentBar.toPx()
            val radius = ShellShape.CardRadius.toPx()
            val path = Path().apply {
                addRoundRect(RoundRect(0f, 0f, width, size.height, radius, radius))
            }
            drawPath(path, accent.copy(alpha = STRIPE_ALPHA))
        }
    }
}

/** 卡标题：分区色、titleSmall 字阶，最多两行（长标题不再被截成一个字）。 */
@Composable
fun ShellSectionTitle(text: String, accent: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = accent,
        style = ShellType.Section,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 说明句（bodySmall / onSurfaceVariant 档）：标题之下、正文之前的那一句口径。 */
@Composable
fun ShellCaption(text: String, modifier: Modifier = Modifier, color: Color = EratwInk.textDim()) {
    Text(
        text = text,
        color = color,
        style = ShellType.Caption,
        modifier = modifier.padding(top = 2.dp, bottom = 2.dp),
    )
}

/** 分区色带的实体不透明度（75% 是旧口径：够亮但不抢标题）。 */
private const val STRIPE_ALPHA = 0.75f
