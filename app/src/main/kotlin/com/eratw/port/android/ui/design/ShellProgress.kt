package com.eratw.port.android.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 壳层的**等待态**（2026-11-26 第十六批）：一条进度带 + 一撮骨架行。
 *
 * ## 为什么需要它
 *
 * 此前「正在扫/正在下」有三处各用 Material 的 `LinearProgressIndicator`（Mod 卡、口上卡、
 * 市场安装条），默认 4dp 高、默认主题色与默认圆角 —— 与壳层 2dp 细线、分区色、`ShellShape`
 * 全对不上；另有「首扫未完」处只留一条进度条，卡体上空一大片，读起来像卡住了。
 *
 * 这里给两件：
 * 1. [ShellProgress]：3dp 细带，确定总量给比例、未知总量给匀速扫描，节奏取
 *    [ShellMotion.Sweep]（与启动屏同一条带速度，不各挑一个）；
 * 2. [ShellSkeletonRow]：首屏占位用的呼吸灰条，节奏取 [ShellMotion.Pulse]，宽度按行递减，
 *    让「正在读」看上去是有东西在长出来，而不是空白等一段。
 *
 * 两件都只画，不带状态：进度真值与是否在扫由调用方给（壳层不持业务状态）。
 */
@Composable
fun ShellProgress(
    accent: Color,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    track: Color = EratwInk.panelAlt(),
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .clip(ShellShape.Bar)
            .background(track),
    ) {
        if (progress == null) {
            IndeterminateSweep(accent = accent)
        } else {
            val settled by animateFloatAsState(
                targetValue = progress.coerceIn(0f, 1f),
                animationSpec = ShellMotion.content(),
                label = "shell-progress",
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(settled)
                    .height(PROGRESS_HEIGHT)
                    .clip(ShellShape.Bar)
                    .background(accent),
            )
        }
    }
}

/**
 * 未知总量的扫描带：一段约占三成宽的亮带从左扫到右，两头都留出淡边，不做跳变式的重来。
 *
 * 用 `BoxWithConstraints` 自己算位移（而不是 `LinearProgressIndicator` 内部实现），
 * 是因为壳层要的是一段**细带**，Material 那版的高度、圆角、颜色都不可控。
 */
@Composable
private fun IndeterminateSweep(accent: Color) {
    val transition = rememberInfiniteTransition(label = "shell-sweep")
    val travel by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(ShellMotion.Sweep, RepeatMode.Restart),
        label = "shell-sweep-travel",
    )
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT)) {
        val barWidth = maxWidth * SWEEP_BAR_FRACTION
        Box(
            modifier = Modifier
                .height(PROGRESS_HEIGHT)
                .fillMaxWidth(SWEEP_BAR_FRACTION)
                // 位移从「完全在左边界外」走到「完全在右边界外」：两端都不留空档，读起来是匀速过带。
                .offset(x = (maxWidth + barWidth) * travel - barWidth)
                .clip(ShellShape.Bar)
                .background(accent.copy(alpha = SWEEP_ALPHA)),
        )
    }
}

/** 骨架行：按 [lines] 条呼吸灰条占位（宽度按 [SKELETON_WIDTHS] 轮换，避免几条等长像表格线）。 */
@Composable
fun ShellSkeletonRow(lines: Int = 2, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shell-skeleton")
    val alpha by transition.animateFloat(
        initialValue = SKELETON_MIN_ALPHA,
        targetValue = SKELETON_MAX_ALPHA,
        animationSpec = infiniteRepeatable(ShellMotion.Pulse, RepeatMode.Reverse),
        label = "shell-skeleton-alpha",
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(SKELETON_GAP)) {
        repeat(lines) { index ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(SKELETON_WIDTHS[index % SKELETON_WIDTHS.size])
                    .height(SKELETON_HEIGHT)
                    .clip(ShellShape.Tag)
                    .background(EratwInk.panelAlt().copy(alpha = alpha)),
            )
        }
    }
}

/** 进度带高度：2dp 的壳层细线在深色底上读不清，3dp 是「看得见但不抢眼」的档。 */
private val PROGRESS_HEIGHT = 3.dp

/** 扫描带占轨道的比例：约三成，再长就像确定进度了。 */
private const val SWEEP_BAR_FRACTION = 0.32f

/** 扫描带的不透明度：比实心浅一档，明示「这不是确定了多少」。 */
private const val SWEEP_ALPHA = 0.85f

/** 骨架条高度与行距：一行文字的高度，行距取卡片内距的一半。 */
private val SKELETON_HEIGHT = 10.dp
private val SKELETON_GAP = ShellSpace.CardGap

/** 骨架条的呼吸区间（线性来回，不做缓动起伏）。 */
private const val SKELETON_MIN_ALPHA = 0.35f
private const val SKELETON_MAX_ALPHA = 0.75f

/** 骨架条宽度（占卡宽比例）：逐条递减，像一段正在写出来的文字。 */
private val SKELETON_WIDTHS = listOf(SKELETON_WIDE, SKELETON_NARROW, SKELETON_MEDIUM)

private const val SKELETON_WIDE = 0.72f
private const val SKELETON_NARROW = 0.45f
private const val SKELETON_MEDIUM = 0.60f
