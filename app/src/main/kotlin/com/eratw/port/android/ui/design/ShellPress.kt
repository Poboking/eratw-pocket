package com.eratw.port.android.ui.design

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color

/**
 * 壳层的**按压反馈**（2026-11-26）。
 *
 * ## 为什么需要它
 *
 * 像素面（教程、导轨、页签）刻意关掉了 Material 涟漪（`indication = null`）—— 水波纹和
 * 点阵字屏不是一套语言。但「关掉涟漪」不等于「不要反馈」：手指按下去什么都不发生，
 * 玩家会怀疑是不是没点中，于是再点一次。这里给出一档**与像素面同调**的反馈：按下时在
 * 元素底上叠一层极淡的面板洗色，松开即散，不动画、不位移。
 *
 * 口径与 Material 的 state layer 一致（8~12% 叠色），只是颜色取自壳层墨色表而不是主题：
 * 强调色由调用点传入，自己决定「按下去该染哪一族」。
 *
 * 用法：`interactionSource` 交给 `clickable`，同一个 source 交给 [tint] 取按下色 ——
 * 两处必须是同一个实例，否则永远读不到按下态。
 */
object ShellPress {

    /** 按下时的叠色（未按下返回全透明，直接喂给 `background`）。 */
    @Composable
    fun tint(source: MutableInteractionSource, accent: Color): Color {
        val pressed by source.collectIsPressedAsState()
        return if (pressed) ShellTint.press(accent) else Color.Transparent
    }
}
