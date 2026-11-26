package com.eratw.port.android.ui.design

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically

/**
 * 壳层**唯一动效源**（2026-11-26 从 SettingsCollapseKit 提级）。
 *
 * 提出前折叠 / 行级展开的 4 个时长常量散在设置页与 Mod 卡里，另有 `tween(220)`、`tween(200)`、
 * `tween(190)` 这类裸字面量直接写在各调用点；同一处「展开一张卡」在不同文件里能出现三套节奏。
 * 这里把**时长**与**缓动**各收成一份：调用方只表达「这是揭示 / 这是行级展开 / 这是切换指示」，
 * 不再自己挑毫秒数。新加动效先看有没有现成的语义位，没有再加常量，不要就地写 `tween(250)`。
 *
 * 缓动选定遵循 Material 动效的两条主线：进入类（展开、揭示）走**减速**曲线，元素「落到位」；
 * 退出类（收起、淡出）走**标准**曲线，离场要快，别让玩家等一个已经决定离开的区块。
 */
object ShellMotion {

    /** 最短反馈（拖拽跟手、点击回弹一类）。 */
    const val QUICK_MILLIS = 120

    /** 最短反馈的离场档：比进场更短，别让要消失的东西多留 20ms。 */
    const val QUICK_OUT_MILLIS = 100

    /** 通用内容切换（指示块滑动、状态色过渡）。 */
    const val CONTENT_MILLIS = 200

    /** 分节卡展开：略长于内容切换，让整块高度变化读起来是一次「揭示」。 */
    const val DISCLOSURE_MILLIS = 240
    const val DISCLOSURE_FADE_IN_MILLIS = 160
    const val DISCLOSURE_SHRINK_MILLIS = 200
    const val DISCLOSURE_FADE_OUT_MILLIS = 120

    /** 卡内行级展开（Mod 条目详情一类，比整卡揭示更快，避免连点卡顿）。 */
    const val ROW_EXPAND_MILLIS = 220
    const val ROW_FADE_IN_MILLIS = 140
    const val ROW_SHRINK_MILLIS = 180
    const val ROW_FADE_OUT_MILLIS = 100

    /** 折叠箭头旋转：与揭示同拍，略慢于内容淡入。 */
    const val CHEVRON_MILLIS = 200

    /** 分段选择器的指示块滑动。 */
    const val SEGMENT_MILLIS = 190

    /** 持续态切换（螺旋展开、齿轮转身这类「转到另一边就不回来」的 180° 旋转）。 */
    const val TURN_MILLIS = 420

    /** 未知总量的扫描带（启动屏与下载条来回扫同一档，两处不许各挑一个速度）。 */
    const val SWEEP_MILLIS = 1500

    /** 呼吸/闪烁提示（▼「还有下文」、像素屏脉冲）。 */
    const val PULSE_MILLIS = 620

    /** 浮条进出（输入条从底部上滑、收起）：进比出略长，离场不拖。 */
    const val PANEL_IN_MILLIS = 140
    const val PANEL_OUT_MILLIS = 120

    /** 整页翻页（教程前后章）：进场稍长、退场更快，翻过去就不再回头。 */
    const val PAGE_IN_MILLIS = 220
    const val PAGE_OUT_MILLIS = 140

    /** 翻页横移的除数：页宽的 1/12 就够读出「往哪边翻」，整屏滑会晕。 */
    private const val PAGE_SLIDE_DIVISOR = 12

    /** 点击抖动（图标钮的「咔哒」）：四段幅度与时长成对给，振幅不留调用点自己定。 */
    private const val SHAKE_LEFT = -10f
    private const val SHAKE_RIGHT = 10f
    private const val SHAKE_RECOIL = -6f
    private const val SHAKE_REST = 0f
    private const val SHAKE_STEP_MILLIS = 60
    private const val SHAKE_BACK_MILLIS = 80

    /** 抖动的四个角度（与 [TAP_SHAKE_MILLIS] 逐位对应）。 */
    val TAP_SHAKE_DEGREES: List<Float> = listOf(SHAKE_LEFT, SHAKE_RIGHT, SHAKE_RECOIL, SHAKE_REST)

    /** 抖动的四段时长。 */
    val TAP_SHAKE_MILLIS: List<Int> =
        listOf(SHAKE_STEP_MILLIS, SHAKE_BACK_MILLIS, SHAKE_STEP_MILLIS, SHAKE_STEP_MILLIS)

    // 减速曲线的四个控制点（Material 的 emphasized decelerate 简化档）：
    // 起步快、尾巴长，元素读起来是「被放到位置上」，不是在匀速平移。
    private const val DECELERATE_X1 = 0.05f
    private const val DECELERATE_Y1 = 0.7f
    private const val DECELERATE_X2 = 0.1f
    private const val DECELERATE_Y2 = 1f

    /** 进入类缓动（展开 / 揭示）。 */
    val Emphasized: Easing = CubicBezierEasing(DECELERATE_X1, DECELERATE_Y1, DECELERATE_X2, DECELERATE_Y2)

    /** 受约束的标准缓动（退出 / 指示滑动），与 Compose 默认观感一致。 */
    val Standard: Easing = FastOutSlowInEasing

    /** 分节卡展开：纵向撑开 + 淡入。 */
    fun disclosureEnter(): EnterTransition = expandVertically(
        animationSpec = tween(durationMillis = DISCLOSURE_MILLIS, easing = Emphasized),
    ) + fadeIn(animationSpec = tween(durationMillis = DISCLOSURE_FADE_IN_MILLIS, easing = Standard))

    /** 分节卡收起：纵向压缩 + 淡出（比展开快一档）。 */
    fun disclosureExit(): ExitTransition = shrinkVertically(
        animationSpec = tween(durationMillis = DISCLOSURE_SHRINK_MILLIS, easing = Standard),
    ) + fadeOut(animationSpec = tween(durationMillis = DISCLOSURE_FADE_OUT_MILLIS))

    /** 行级展开。 */
    fun rowEnter(): EnterTransition = expandVertically(
        animationSpec = tween(durationMillis = ROW_EXPAND_MILLIS, easing = Emphasized),
    ) + fadeIn(animationSpec = tween(durationMillis = ROW_FADE_IN_MILLIS, easing = Standard))

    /** 行级收起。 */
    fun rowExit(): ExitTransition = shrinkVertically(
        animationSpec = tween(durationMillis = ROW_SHRINK_MILLIS, easing = Standard),
    ) + fadeOut(animationSpec = tween(durationMillis = ROW_FADE_OUT_MILLIS))

    /**
     * 浮条进（输入条从底部上滑）：位移与淡入同拍，读起来是一整块被托上来。
     *
     * 与上面四个 `xxxEnter()` 不同，这里是 `val`：它们不依赖参数，只表达一种已定的语义，
     * 当成 token 用比当成工厂函数用更贴近事实（也让本对象别长成一个万能动画工厂）。
     */
    val PanelEnter: EnterTransition = slideInVertically(
        animationSpec = tween(durationMillis = PANEL_IN_MILLIS, easing = Emphasized),
    ) { it } + fadeIn(animationSpec = tween(durationMillis = PANEL_IN_MILLIS))

    /** 浮条出（收起）：同样的动作反向，但更快 —— 已经决定离开就别再多留。 */
    val PanelExit: ExitTransition = slideOutVertically(
        animationSpec = tween(durationMillis = PANEL_OUT_MILLIS, easing = Standard),
    ) { it } + fadeOut(animationSpec = tween(durationMillis = PANEL_OUT_MILLIS))

    /** 整页进（教程翻页）：淡入 + 短距离横移，位移只有页宽的 1/12，不做整屏滑动。 */
    val PageEnter: EnterTransition = fadeIn(
        animationSpec = tween(durationMillis = PAGE_IN_MILLIS, easing = Emphasized),
    ) + slideInHorizontally(
        animationSpec = tween(durationMillis = PAGE_IN_MILLIS, easing = Emphasized),
    ) { it / PAGE_SLIDE_DIVISOR }

    /** 整页出：与 [PageEnter] 反向，位移同样只走页宽的 1/12。 */
    val PageExit: ExitTransition = fadeOut(
        animationSpec = tween(durationMillis = PAGE_OUT_MILLIS, easing = Standard),
    ) + slideOutHorizontally(
        animationSpec = tween(durationMillis = PAGE_OUT_MILLIS, easing = Standard),
    ) { -it / PAGE_SLIDE_DIVISOR }

    /** 通用内容规格（指示块、色块过渡）。 */
    fun <T> content(): TweenSpec<T> = tween(durationMillis = CONTENT_MILLIS, easing = Emphasized)

    /** 最短反馈规格。 */
    fun <T> quick(): TweenSpec<T> = tween(durationMillis = QUICK_MILLIS)

    /** 最短反馈的离场规格。 */
    fun <T> quickOut(): TweenSpec<T> = tween(durationMillis = QUICK_OUT_MILLIS)

    /** 折叠箭头旋转规格。 */
    fun <T> chevron(): TweenSpec<T> = tween(durationMillis = CHEVRON_MILLIS, easing = Standard)

    /** 分段选择器指示块规格。 */
    fun <T> segment(): TweenSpec<T> = tween(durationMillis = SEGMENT_MILLIS, easing = Standard)

    /** 持续态 180° 旋转规格（螺旋展开、齿轮转身）。 */
    val Turn: TweenSpec<Float> = tween(durationMillis = TURN_MILLIS, easing = Emphasized)

    /** 未知总量扫描带的单程规格（线性，匀速扫才读得出「在等」）。 */
    val Sweep: TweenSpec<Float> = tween(durationMillis = SWEEP_MILLIS, easing = LinearEasing)

    /** 呼吸/闪烁规格（线性回环，不做缓动起伏）。 */
    val Pulse: TweenSpec<Float> = tween(durationMillis = PULSE_MILLIS, easing = LinearEasing)
}
