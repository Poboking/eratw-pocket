package com.eratw.port.android.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 壳层按钮的**唯一配色源**（2026-11-26 第十四批）。
 *
 * ## 为什么需要它
 *
 * 设置页与底栏里有二十来处 `Button` / `OutlinedButton`，此前每处各自写一遍
 * `ButtonDefaults.buttonColors(containerColor = PrimaryButton, contentColor = Text)`，
 * 描边有的给 `BorderStroke(1.dp, Border)`、有的干脆不给（吃主题缺省），高度在 36dp / 40dp /
 * 「不写」三种之间摆动。看上去「差不多」，实际是三套口径：换墨色得改十几处，禁用态更是
 * 各显神通 —— 有的是 Material 默认的四成透明度，有的根本没定义。
 *
 * 这里把三件事收成一处：
 * 1. [filled]：实心主行动（黄底墨字，禁用压暗而非变透明）；
 * 2. [outlined]：描边次行动（透明底 + 墨字，禁用转 muted）；
 * 3. [border]：描边色（默认分隔线色，警示类行动传 warn）。
 *
 * 热区不在这里定：调用点统一 `heightIn(min = ShellSpace.RowMinHeight)`，
 * 尺寸是布局责任，配色是样式责任，两者分开才不会又长回「一处一样式」。
 */
object ShellButton {

    /** 禁用态描边的透明度：描边压淡、但不至于消失（与禁用内容色同一口气）。 */
    private const val DISABLED_BORDER_ALPHA = 0.35f

    /** 描边次行动的内容缩进：小字按钮只留横向留白，纵向交给最小高度。 */
    val CompactPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

    /**
     * 实心主行动配色：黄底 + 墨字；[container] 留给「分享」一类的特殊语义色。
     *
     * 禁用态**压暗**而不是靠 alpha：`PrimaryButton` 本身已经是压住的黄，再乘透明度会
     * 掉到和卡片底分不开的程度；这里直接换成面板色 + muted 字，一眼能看出「现在不能按」。
     */
    @Composable
    fun filled(container: Color = EratwInk.primaryButton()): ButtonColors = ButtonDefaults.buttonColors(
        containerColor = container,
        contentColor = EratwInk.text(),
        disabledContainerColor = EratwInk.panelAlt(),
        disabledContentColor = EratwInk.textMuted(),
    )

    /**
     * 描边次行动配色：透明底 + [content]（缺省正文色），禁用转 muted。
     *
     * 强调类行动（如「前往更新」）传 `EratwInk.accent()`；警示类传 `EratwInk.warn()`。
     */
    @Composable
    fun outlined(content: Color? = null): ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color.Transparent,
        contentColor = content ?: EratwInk.text(),
        disabledContentColor = EratwInk.textMuted(),
    )

    /**
     * 描边：缺省分隔线色；警示类行动传 `EratwInk.warn()`。
     *
     * [enabled] = false 时描边同内容色一起压淡（只压字不压框会让禁用钮仍像「可以按」）。
     */
    fun border(tone: Color? = null, enabled: Boolean = true): BorderStroke {
        val color = tone ?: EratwInk.border()
        return BorderStroke(ShellSpace.Hairline, if (enabled) color else color.copy(alpha = DISABLED_BORDER_ALPHA))
    }

    /**
     * 正文按钮（`TextButton`）配色：只有内容色有意义。
     *
     * 保留这个入口是为了让「强调 / 警示 / 次行」三档也走同一处，
     * 而不是每处各写一遍 `ButtonDefaults.textButtonColors(contentColor = …)`。
     */
    @Composable
    fun text(content: Color? = null): ButtonColors = ButtonDefaults.textButtonColors(
        contentColor = content ?: EratwInk.text(),
        disabledContentColor = EratwInk.textMuted(),
    )
}
