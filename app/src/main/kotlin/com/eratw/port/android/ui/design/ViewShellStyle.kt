package com.eratw.port.android.ui.design

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/**
 * **View 面**的 token 适配器（2026-11-26 第三轮）：把 [ShellType] / [ShellShape] / [ShellSpace]
 * 翻译成 `android.widget` 能吃的像素、字面与 drawable。
 *
 * 为什么另立一件：壳层有两张脸 —— Compose 的卡片与平台的 `AlertDialog`。两边的观感必须同拍，
 * 但 View 侧没有 `TextStyle` / `Shape` 可用，只能在每个 View 文件里自己写 `15f`、`12`、`setLineSpacing`。
 * 这类换算一旦分散，就是「弹窗字号 15sp、卡片标题 16sp」这种谁都没写错、合起来不对的差一档。
 *
 * 换算口径写在这里一次，两边引用同一份：
 * - 字号：`sp` 直接取 token 的 `fontSize.value`；
 * - 行距：Compose 的 `lineHeight` 是行盒绝对高度，TextView 的乘数乘的是系统默认行高（≈1.2 倍字号），
 *   所以乘数 = `lineHeight / (fontSize × 1.2)`；
 * - 字距：`letterSpacing` 单位是 em，TextView 的 `letterSpacing` 也是 em，故需 `letterSpacing / fontSize`；
 * - 圆角 / 内距：`Dp.value` × `density`。
 */
internal object ViewShellStyle {

    /** 系统默认行高相对字号的比例（用于把 Compose 行盒高度换算成 TextView 乘数）。 */
    private const val DEFAULT_LINE_HEIGHT_FACTOR = 1.2f

    /** dp token → 像素。 */
    fun dp(context: Context, value: Dp): Int = dp(context, value.value)

    /** 裸浮点 dp → 像素（只给 1dp 这种极薄量用，其余一律走 token）。 */
    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /**
     * 按 token 生成一段正文：字号、字面、行距、字距全部由 [TextStyle] 推出。
     *
     * [face] 只在调用方要「比 token 再重一档」时传入（例如强调行字号不变、只加粗）。
     */
    fun textView(
        context: Context,
        text: String,
        style: TextStyle,
        color: Int,
        face: Typeface? = null,
    ): TextView = TextView(context).apply {
        this.text = text
        val size = style.fontSize.value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        setTypeface(face ?: faceOf(style.fontWeight))
        setLineSpacing(0f, (style.lineHeight.value / size) / DEFAULT_LINE_HEIGHT_FACTOR)
        letterSpacing = style.letterSpacing.value / size
    }

    /** token 的字重 → 平台字面；壳层只用得到 Normal / Medium / Bold 三档。 */
    fun faceOf(weight: FontWeight?): Typeface = when {
        weight == null -> Typeface.DEFAULT
        weight.weight >= FontWeight.Bold.weight -> Typeface.create("sans-serif", Typeface.BOLD)
        weight.weight >= FontWeight.Medium.weight -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }

    /** 按压反馈：取主题里的 ripple/selector，深浅两套主题都由系统给对口底色。 */
    fun selectableBackground(context: Context): Int {
        val out = TypedValue()
        val resolved = context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return if (resolved) out.resourceId else 0
    }

    /** 浮层面板底：圆角 + 面板色 + 一像素描边（对话框、View 侧抽屉共用）。 */
    fun panelDrawable(context: Context, radius: Dp, fill: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, radius).toFloat()
            setColor(fill)
            setStroke(dp(context, ShellSpace.Hairline), strokeColor)
        }
}
