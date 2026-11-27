package com.eratw.port.android.ui.design

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * 壳层的**可见性容器**（2026-11-26 第十六批，把「折叠正文怎么出现」收成一件）。
 *
 * ## 为什么需要它
 *
 * `AnimatedVisibility`（foundation 那版）的 content 是**叠放**布局：多个子项全部落脚同一原点。
 * 2026-11-26 设置页 Mod 管理卡就是这么叠成一坨的（说明句与每一行 Mod 压在一起），根因是
 * 手写调用点忘了在外面包 `Column`（揭示式卡自己包了，所以只有它没坏）。
 *
 * 与其在每处手写 `AnimatedVisibility + Column`（漏一次就是一次线上观感事故），不如把
 * 「正文永远是一列」写进器件本身：[ShellVisibility] 内部固定套一层 `Column`，调用点只交
 * 子项，天然不可能再叠放。
 *
 * 节奏不在这里定：默认取 [ShellMotion.disclosureEnter] / [ShellMotion.disclosureExit]，
 * 行级展开的调用点传 [ShellMotion.rowEnter] / [ShellMotion.rowExit] —— 与手写时逐字一致。
 */
@Composable
fun ShellVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = ShellMotion.disclosureEnter(),
    exit: ExitTransition = ShellMotion.disclosureExit(),
    content: @Composable ColumnScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 「换了一档内容」时的**整块淡入**：给 [key] 一次新值就从 [from] 补到全不透明。
 *
 * 设置页换档（移植版 / 原版引擎 / Mod / 关于）是同一个滚动列表换内容，瞬间替换会像闪屏；
 * 补一段 [ShellMotion.zone] 的淡入，读起来是「同一块面板换了内容」而不是「页面重开」。
 *
 * 只动 alpha（调用点交给 `graphicsLayer`），不改布局、不动滚动位置 —— 换档时玩家刚滚到哪
 * 就还在哪，不把视线拽回顶部。
 */
@Composable
fun rememberShellFade(key: Any?, from: Float = SHELL_FADE_FROM): Float {
    val anim = remember { Animatable(1f) }
    LaunchedEffect(key) {
        anim.snapTo(from)
        anim.animateTo(1f, ShellMotion.zone())
    }
    return anim.value
}

/** 淡入起点：0.35 已经够读出「换了内容」，再低就成闪烁。 */
private const val SHELL_FADE_FROM = 0.35f
