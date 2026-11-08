package com.eratw.port.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 右上角壳层图标按钮的共用配色（键盘、螺旋、方向锁三枚同源）。
 *
 * 抽出来之前这三处各写一份硬编码色值（2026-10-25 高亮那次只改了键盘、
 * 漏了螺旋的先例），现在一处定义、改色一处生效。
 * 状态只落在图标线条颜色上（2026-11-03 负责人定调：按钮空白处不画任何底色）。
 */
internal object ShellIconTone {
    /** 静息描边/图标色。 */
    val idle = Color(0xFF8A8A8A)

    /** 激活（对应功能展开/锁定）时的浅绿，与游戏黑底同调的高亮。 */
    val active = Color(0xFFB9D3B9)
}

/**
 * 图标按钮的「咔哒抖一下」点击动效：-10 度 → +10 度 → -6 度 → 回正。
 *
 * 从键盘图标抽出（原来这段 Animatable + 协程内联在 [InputToggle] 里），
 * 键盘、方向锁这类「状态切换 + 瞬时反馈」的按钮共用；螺旋的 0↔180 度状态旋转
 * 是持续态动画、不走这里。点击瞬间重放一轮，动画期间连点只重置进度不叠加。
 */
@Stable
internal class ClickShakeState(
    private val shake: Animatable<Float, AnimationVector1D>,
    private val scope: CoroutineScope,
) {
    /** 当前抖动角度（度），挂到图标的 `rotate(...)` 上。 */
    val degrees: Float get() = shake.value

    /** 播放一轮抖动并原样转发 [onClick]（先启动动画再回调，回调即时生效）。 */
    fun click(onClick: () -> Unit) {
        scope.launch {
            shake.animateTo(-10f, tween(60))
            shake.animateTo(10f, tween(80))
            shake.animateTo(-6f, tween(60))
            shake.animateTo(0f, tween(60))
        }
        onClick()
    }
}

/** [ClickShakeState] 的 Compose 记忆入口：一次组合只建一份动画状态。 */
@Composable
internal fun rememberClickShake(): ClickShakeState {
    val shake = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    return remember(shake, scope) { ClickShakeState(shake, scope) }
}

/**
 * 右上角壳层图标按钮的共用容器：40dp 命中区、读屏标签、点击回调。
 *
 * 抽出来之前键盘与螺旋各写一个几乎一样的 Box，方向锁是第三份 —— 收口到这一处，
 * 图标本体（Canvas 手绘）仍由调用方提供。容器只管命中区与无障碍，**不画任何底色**
 * （2026-11-03 负责人定调：空白处透明、激活反馈只靠图标线条变绿）；命中区本身
 * 依旧整块可点，点击手感与 40dp 目标尺寸不受去底色影响。
 */
@Composable
internal fun ShellIconButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}