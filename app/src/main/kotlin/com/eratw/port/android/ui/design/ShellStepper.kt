package com.eratw.port.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 数字步进器 `− [N] +`（2026-11-26 第三轮，从快捷宏卡提级）。
 *
 * 为什么不让玩家敲键盘：宏编辑器里「连发次数 / 整组重复」是**高频微调**，弹一次软键盘就把
 * 正文顶掉一半；步进器两下点完，且在边界处自己变灰 —— 玩家不用记上限是多少。
 *
 * ## 触控口径
 * 视觉块只有 32dp（同屏要塞下若干步），但**触摸区**至少 48dp：外层用
 * `minimumInteractiveComponentSize` 撑开热区、内层画小方块。M3 允许视觉小、热区大，
 * 但不允许热区跟着视觉一起缩 —— 小屏上点不中是最真实的可用性事故。
 *
 * ## 状态
 * 三档都给：可点（正文色）、到边界（灰化 + 不可点）、按压（系统 ripple）。左右两侧独立判定，
 * `−` 到下限时只有它灰，`+` 仍然可点。
 */
@Composable
fun ShellStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(STEPPER_GAP),
    ) {
        StepperKey(symbol = "−", enabled = value > min, onClick = {
            onChange((value - 1).coerceAtLeast(min))
        })
        Text(
            text = value.toString(),
            color = EratwInk.text(),
            style = ShellType.Tab,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = STEPPER_VALUE_WIDTH),
        )
        StepperKey(symbol = "+", enabled = value < max, onClick = {
            onChange((value + 1).coerceAtMost(max))
        })
    }
}

/** 单颗 `−` / `+`：48dp 热区 + 32dp 视觉块；按压在**方块内**压一档（不是整块 48dp 都染色）。 */
@Composable
private fun StepperKey(symbol: String, enabled: Boolean, onClick: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(ShellShape.Chip)
            .clickable(
                enabled = enabled,
                interactionSource = pressSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = ShellShape.Chip,
            color = EratwInk.panelAlt(),
            border = BorderStroke(1.dp, EratwInk.border()),
        ) {
            Text(
                text = symbol,
                // 到边界的那一侧压到 35%：不是隐掉（隐掉会让人以为按钮坏了），是「按了也不会变」。
                color = if (enabled) EratwInk.text() else EratwInk.textMuted().copy(alpha = 0.35f),
                style = ShellType.Tab,
                modifier = Modifier
                    .background(
                        if (enabled) ShellPress.tint(pressSource, EratwInk.text()) else Color.Transparent,
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** 步进键之间的缝（两颗避免贴在一起误触）。 */
private val STEPPER_GAP = 4.dp

/** 读数格最小宽度：两位数也不跳位。 */
private val STEPPER_VALUE_WIDTH = 24.dp
