package com.eratw.port.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 小状态签（2026-11-26，竖屏功能卡的「实验性」标注）：10sp 小字 + 淡色底 + 同色字。
 *
 * 只做**一句话状态**：不抢标题、不占行、不点不跳。默认取警示橙 —— 「实验性」在语义上
 * 是「能用但还没定型」的提醒，不是错误红，也不是主操作绿。文案由调用方从三语表取，
 * 本件不内联任何玩家可见字面量（AGENTS 文案表约定）。
 */
@Composable
fun ShellTag(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = EratwInk.warn(),
) {
    Text(
        text = text,
        color = tone,
        style = ShellType.Overline,
        modifier = modifier
            .clip(ShellShape.Tag)
            .background(tone.copy(alpha = TAG_FILL_ALPHA))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 标签底的实体不透明度：够看出「这是一枚签」，又不至于在小字号旁炸开。 */
private const val TAG_FILL_ALPHA = 0.12f
