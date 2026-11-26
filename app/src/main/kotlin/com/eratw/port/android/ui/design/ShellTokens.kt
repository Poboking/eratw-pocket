package com.eratw.port.android.ui.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 壳层的**形状 / 间距 / 字阶 / 着色**语义 token（2026-11-26）。
 *
 * 口径取自 Material 3 的三层结构：原始值 → 语义角色 → 组件参数。壳层此前是「组件参数直接写
 * 数字」（12dp 圆角、15sp 标题、12sp 说明各写各的），改一次观感要在几十个调用点里找；
 * 收到这里之后，调一处就全壳同拍，也让新写的卡天然与老卡对齐。
 *
 * 全部是 `val`（Compose 侧参数），不要在这里塞平台色值 —— 颜色仍只从
 * `com.eratw.port.android.ui.EratwInk` 取，本文件只做**强度与层级**的组合。
 */
object ShellShape {
    /** 分节卡圆角半径（色带绘制与 Shape 共用这一个数，别各写一份）。 */
    val CardRadius = 14.dp
    val InsetRadius = 10.dp
    val ChipRadius = 9.dp
    val BarRadius = 2.dp
    val TagRadius = 4.dp
    val SheetRadius = 24.dp

    /** 分节卡（设置页、Mod、市场共用一档圆角）。 */
    val Card = RoundedCornerShape(CardRadius)

    /** 卡内嵌块（空态提示、次级面板）。 */
    val Inset = RoundedCornerShape(InsetRadius)

    /** 芯片 / 行内小按钮。 */
    val Chip = RoundedCornerShape(ChipRadius)

    /** 细色条、分组首列的小竖条。 */
    val Bar = RoundedCornerShape(BarRadius)

    /** 小标签（「实验性」这类状态签）。 */
    val Tag = RoundedCornerShape(TagRadius)

    /** 底部弹层（对话框、抽屉）。 */
    val Sheet = RoundedCornerShape(SheetRadius)
}

object ShellSpace {
    /** 卡片左内距：3dp 色带 + 13dp 正文。 */
    val CardStart = 16.dp

    /** 卡片右内距。 */
    val CardEnd = 14.dp

    /** 卡片上下内距。 */
    val CardVertical = 12.dp

    /** 左侧分区色带宽度。 */
    val AccentBar = 3.dp

    /** 卡与卡之间。 */
    val CardGap = 8.dp

    /** 分组头与上一组之间的留白。 */
    val GroupGap = 10.dp

    /** 可点整行的最小高度（Material 触控下限之上留一点余量）。 */
    val RowMinHeight = 44.dp

    /** 单枚图标的触控下限（Material 3 的 48dp）：视觉可以是 28dp 的方块，热区必须给满。 */
    val TouchMin = 48.dp

    /** 列表行内距（比章节卡更紧：同屏要能塞下十几条记录）。 */
    val RowPadding = 12.dp

    /** 遮罩层级的内距（对话框、抽屉正文）：一级浮层比卡片松一档，读起来不局促。 */
    val DialogPadding = 24.dp

    /** 浮层标题上方留白（比正文内距略收，标题贴顶会显得被挤）。 */
    val DialogTop = 20.dp

    /** 浮层底部留白（按钮自己带内距，这里只补一线呼吸）。 */
    val DialogBottom = 8.dp

    /** 浮层里段落之间（标题与正文、正文各行之间）。 */
    val DialogRowGap = 12.dp

    /** 浮层文字按钮的左右内距。 */
    val DialogButtonPadding = 14.dp

    /** 发丝线厚度（1dp 是能上屏的最小实线，再细在低密度屏会消失）。 */
    val Hairline = 1.dp
}

/**
 * 字阶：Material 3 的 role 名照搬，但字号按本项目实际密度收了一档
 * （设置页是密集表单，不是内容页，标题不需要 22sp）。
 */
object ShellType {
    /** 卡标题（titleMedium）。 */
    val Title = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    )

    /** 小节标题（titleSmall）。 */
    val Section = TextStyle(
        fontSize = 15.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    )

    /** 正文（bodyMedium）。 */
    val Body = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

    /** 说明句（bodySmall，行高放宽到 18sp 便于两行折读）。 */
    val Caption = TextStyle(fontSize = 12.sp, lineHeight = 18.sp)

    /** 注脚 / 标签（labelSmall）。 */
    val Label = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)

    /** 分段标签条的单档（比 labelSmall 大一档：这一档要能被拇指点中并读清）。 */
    val Tab = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp)

    /** 分组头上的小号全大写位（overline），只用于极短的分区名。 */
    val Overline = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.0.sp,
    )

    /**
     * 读数数字（统计格主值、进度百分比、计数大数）：比正文大一档、半粗。
     *
     * 为什么不复用 [Title]：标题与数字的**职责不同** —— 标题是「这一块叫什么」（字距、字重都为阅读
     * 服务），数字是「读数是几」（要一眼抓取）。同屏既有标题又有统计格时，两者共用一个 token 会让
     * 「数值」看起来只是「又一个标题」。需要更大一档时用 `Figure.copy(fontSize = …)` 派生，别直写字号。
     */
    val Figure = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/**
 * 着色强度：Material 3 的 tonal surface 做法 —— 分区色不是刷满整块，而是
 * 在面板底上叠一层 4~5% 的同色洗色，读起来「这块属于这个分区」，但不抢正文。
 */
object ShellTint {

    private const val WASH_ALPHA = 0.045f
    private const val HAIRLINE_ALPHA = 0.35f
    private const val PRESS_ALPHA = 0.12f

    /** 卡片底的极淡分区洗色。 */
    fun wash(accent: Color): Color = accent.copy(alpha = WASH_ALPHA)

    /** 分区色描边（比正文描边更弱，只做「归属」提示）。 */
    fun hairline(accent: Color): Color = accent.copy(alpha = HAIRLINE_ALPHA)

    /** 按压态叠色（自定义可点块用，系统 ripple 之外的备用口径）。 */
    fun press(accent: Color): Color = accent.copy(alpha = PRESS_ALPHA)
}
