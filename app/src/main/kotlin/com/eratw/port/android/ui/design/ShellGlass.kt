package com.eratw.port.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * **玻璃卡与氛围光**（2026-11-26 第十九批，关于页口径）。
 *
 * ## 为什么需要它
 *
 * 设置页的分节卡是「实心面板 + 色带」，扫读快、但每一档都长得一样厚。关于页是唯一
 * 「不做事、只看一眼」的页面，厚度反而成了负担：四张实心卡叠在纯黑底上，像四块砖。
 * 这里给一套**半透明玻璃**：卡面透出背后的一层分区光晕，边缘只有一条极淡的同色发丝，
 * 顶部压一道反光 —— 读起来是「浮在深空里的一层玻璃」，而不是「黑底上的灰块」。
 *
 * ## 口径（内敛优先，不许做花）
 *
 * 1. **不做背景模糊**：`RenderEffect` 在 API 31 以下是空操作，而真正的毛玻璃还要在卡后
 *    再绘一层模糊副本，成本与「省电」冲突。玻璃感由「半透明 + 顶部光晕 + 反光边」三件事
 *    就够了 —— 这也与 M3「动效/材质只解释空间关系」的口径一致。
 * 2. **不叠第二层阴影**：深底上的阴影看不见，只会拖慢绘制。
 * 3. **不用饱和色块**：分区色只出现在 1dp 发丝、光晕与文字上，卡面本身保持无彩。
 *
 * 调用点（关于页）把分区色从 `LocalZoneAccent` 传进来；本件不认识任何业务状态。
 */
@Composable
fun ShellGlassCard(
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = ShellShape.Card,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(ShellSpace.Hairline, accent.copy(alpha = GLASS_EDGE_ALPHA)),
    ) {
        Column(modifier = Modifier.fillMaxWidth().background(glassFill())) {
            // 顶部反光线：玻璃的「边」靠这条亮线立起来，描边只负责收口。
            Box(modifier = Modifier.fillMaxWidth().height(SHEEN_HEIGHT).background(sheen()))
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
    }
}

/**
 * 页面顶部的**氛围光**：从顶端中心向下散的一团极淡分区色。
 *
 * 玻璃卡需要「后面有东西可透」才有玻璃感，光晕就是那层东西；没有它，半透明卡面只是
 * 变暗的灰块。透明度刻意压到 10% 以下 —— 抬头能感到一点冷光，低头读字不受影响。
 */
@Composable
fun ShellAmbientGlow(
    accent: Color,
    modifier: Modifier = Modifier,
    alpha: Float = GLOW_ALPHA,
) {
    Box(
        modifier = modifier.drawBehind {
            val radius = size.height * GLOW_RADIUS_RATIO
            val center = Offset(size.width / 2f, 0f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = alpha), Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        },
    )
}

/**
 * 玻璃面里的**极淡分区发丝**（替代实心分隔线）。
 *
 * 实心 `Border` 色在玻璃上会读成一道硬折痕；换成同色 16% 的发丝线后，分割还在，
 * 但整张卡仍是一块连续的面。
 */
fun glassRule(accent: Color): Color = accent.copy(alpha = GLASS_RULE_ALPHA)

/** 头像一类圆形元素的**外发光环**：只在边缘外扩一圈极淡的同色光，不做描边堆叠。 */
fun Modifier.shellHalo(accent: Color, alpha: Float = HALO_ALPHA): Modifier = drawBehind {
    val radius = size.minDimension / 2f * HALO_RADIUS_RATIO
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(accent.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** 卡面：上亮下暗的一层半透明，透出背后的氛围光。 */
private fun glassFill(): Brush = Brush.verticalGradient(
    listOf(
        EratwInk.panel().copy(alpha = GLASS_TOP_ALPHA),
        EratwInk.panelAlt().copy(alpha = GLASS_BOTTOM_ALPHA),
    ),
)

/** 顶部反光：中间亮、两头收，别做满整条边（满边会像描了第二道线）。 */
private fun sheen(): Brush = Brush.horizontalGradient(
    listOf(
        Color.Transparent,
        EratwInk.text().copy(alpha = SHEEN_ALPHA),
        Color.Transparent,
    ),
)

private const val GLASS_TOP_ALPHA = 0.70f
private const val GLASS_BOTTOM_ALPHA = 0.50f
private const val GLASS_EDGE_ALPHA = 0.22f
private const val GLASS_RULE_ALPHA = 0.16f
private const val SHEEN_ALPHA = 0.06f
private const val HALO_ALPHA = 0.12f
private const val GLOW_ALPHA = 0.09f
private const val GLOW_RADIUS_RATIO = 0.9f
private const val HALO_RADIUS_RATIO = 0.85f
private val SHEEN_HEIGHT = 1.dp
