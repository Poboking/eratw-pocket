package com.eratw.port.android.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 滚动面的**边缘渐隐**（2026-11-26 第十七批）：列表顶/底各压一条背景色渐变，
 * 让「还有内容没露出来」读起来是淡出，而不是一刀切在卡片中间。
 *
 * ## 为什么需要它
 *
 * 设置页与 Mod 卡都是可滚长列表，内容滚到顶栏/底边时会被硬裁断：卡片上沿在半高状态下
 * 直接消失，视线读成「被切了一刀」。原生桌面/移动端长列表通常都有这道渐隐 —— 它不是装饰，
 * 而是把「滚动边界」这件空间事实讲清楚（M3 动效口径：只解释状态与空间关系）。
 *
 * ## 怎么用
 *
 * 调用方把「还能往上/往下滚」传进来（`gridState.canScrollBackward` / `canScrollForward`），
 * 本件只负责画与淡入淡出：翻到顶就慢慢撤掉上面那条，翻到底就撤掉下面那条。
 * 参数收 `top` / `bottom` 两个布尔而不是滚动状态类型 —— 这样 LazyColumn、LazyRow、
 * 瀑布网格、普通 Box 都能挂，设计件不必认识任何一种 state。
 */
@Composable
fun BoxScope.ShellEdgeFades(
    top: Boolean,
    bottom: Boolean,
    color: Color = EratwInk.background(),
    height: Dp = FADE_HEIGHT,
) {
    EdgeVeil(top = true, shown = top, color = color, height = height)
    EdgeVeil(top = false, shown = bottom, color = color, height = height)
}

/** 单条渐变：顶上那条从实色往下变透明，底下那条反过来。 */
@Composable
private fun BoxScope.EdgeVeil(top: Boolean, shown: Boolean, color: Color, height: Dp) {
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = ShellMotion.quick(),
        label = "shell-edge-fade",
    )
    Box(
        modifier = Modifier
            .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
            .fillMaxWidth()
            .height(height)
            .alpha(alpha)
            .background(fadeBrush(top = top, color = color)),
    )
}

/**
 * 横向滚动条的**两端渐隐**（第二十批，给市场筛选带用）：一排档位超宽时，右端不会「正好切在
 * 半枚片中间」——片被裁一半读起来像坏图，淡出才读得出「还有档位在右边」。
 *
 * 与竖向那条同一套纪律：调用方传「还能往左右滚」，颜色取所在面的底色（在弹窗卡上就是卡面色，
 * 页面黑底会糊出亮带），本件只画与淡入淡出。窄屏上筛选带溢出、宽屏上全放下，两条各自消失。
 */
@Composable
fun BoxScope.ShellSideFades(
    start: Boolean,
    end: Boolean,
    color: Color = EratwInk.background(),
    width: Dp = SIDE_FADE_WIDTH,
) {
    SideVeil(start = true, shown = start, color = color, width = width)
    SideVeil(start = false, shown = end, color = color, width = width)
}

/** 单条侧边渐变：起端那张从实色往右变透明，末端反过来。 */
@Composable
private fun BoxScope.SideVeil(start: Boolean, shown: Boolean, color: Color, width: Dp) {
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = ShellMotion.quick(),
        label = "shell-side-fade",
    )
    Box(
        modifier = Modifier
            .align(if (start) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            .width(width)
            .alpha(alpha)
            .background(sideBrush(start = start, color = color)),
    )
}

/** 渐变方向：顶上实色在极顶、底下实色在极底，中间不留过渡缝。 */
private fun fadeBrush(top: Boolean, color: Color): Brush =
    if (top) {
        Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))
    } else {
        Brush.verticalGradient(listOf(color.copy(alpha = 0f), color))
    }

/** 侧边渐变方向：起端实色在极左，末端实色在极右。 */
private fun sideBrush(start: Boolean, color: Color): Brush =
    if (start) {
        Brush.horizontalGradient(listOf(color, color.copy(alpha = 0f)))
    } else {
        Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color))
    }

/** 渐隐带高度：比一行正文略高，短了看不出过渡、长了会吃掉首张卡的标题。 */
private val FADE_HEIGHT = 20.dp

/** 侧边渐隐宽度：只够「半个片」的宽度，再宽会连档位的字一起吃掉。 */
private val SIDE_FADE_WIDTH = 16.dp
