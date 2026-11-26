package com.eratw.port.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.unit.dp
import com.eratw.port.android.R
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.android.ui.design.ViewShellStyle

/**
 * 平台 `android.app.AlertDialog` 的统一外观件（View 侧）。
 *
 * ## 为什么要自己画
 * 存档迁移询问、`UPDATECHECK` 询问、更新推荐、蜂窝二次确认、通知授权说明这五类弹窗必须留在
 * View 侧：弹它们代码路径压根不在 Compose 里（[android.app.AlertDialog] 由宿主线程直接投到
 * UI 线程），而且返回键语义要交回框架（原版只有 `DialogResult.Yes` 算 true）。
 * 但框架的 `setTitle` / `setMessage` 把字号、行距、按钮全大写、面板方角全权交给系统主题，
 * 换 Android 版本换 OEM 就换长相 —— 玩家看到的「安卓原生弹窗」就是这么来的。
 *
 * ## 收口后的用法（三行，处处同形）
 * ```
 * val dialog = EratwShellDialog.builder(activity)
 *     .setView(EratwShellDialog.content(activity, title, lines))
 *     .setPositiveButton(label) { _, _ -> ... }
 *     .create()
 * EratwShellDialog.dress(dialog)   // create() 之后、show() 之前
 * dialog.show()
 * ```
 *
 * ## 与壳层设计系统的关系（2026-11-26 第三轮：View 面收进同一套语义 token）
 *
 * 字号 / 圆角 / 内距 / 行距一律读 `ui/design` 的 token（经 [ViewShellStyle] 换算），本文件不再出现
 * 裸值。于是弹窗与设置页卡片是同一副字阶：弹窗标题 = `titleSmall`、正文 = `bodyMedium`、
 * 注脚 = `bodySmall`，玩家从卡片读进弹窗不会感到字号跳变；圆角取 [ShellShape.SheetRadius]，
 * 与底部弹层同档。
 *
 * 三处**有意偏离** Material 3 官方弹窗体量，理由在产品侧而不在规范侧：
 * 1. 圆角 24dp 而非官方 28dp —— 壳层最大圆角就在这一档，弹窗不该比底部弹层更圆，否则层级读不出来；
 * 2. 遮罩浓度 0.62（官方更淡）—— 弹窗后面是立绘与场景，压到「看得出发生了什么、但读不清细节」
 *    才不抢正文；
 * 3. 面板底比正文卡片更暗一档（`panelAlt`）—— 浮在上层的容器越暗越沉，视线才落回文字。
 *
 * 颜色一律取 [EratwInk]，本文件不出现第二个 `0xFF`。
 */
internal object EratwShellDialog {

    /** 正文一行的三档视觉，与 Compose 侧 [EratwTextTone] 同一套层级，只是 View 面少一档注脚。 */
    enum class Tone { HEADLINE, BODY, NOTE }

    /** 一行正文：[Tone.HEADLINE] 是「玩家要先看到的那一行」，[Tone.NOTE] 是下一步的预告。 */
    data class Line(val text: String, val tone: Tone = Tone.BODY)

    /** 统一入口的构造器；除 `setView` 与按钮之外不暴露任何样式参数。 */
    fun builder(activity: Activity): AlertDialog.Builder = AlertDialog.Builder(activity)

    /** 只有一行正文的常见形态（`setMessage` 的替身）。 */
    fun content(activity: Activity, title: String, body: String): LinearLayout =
        content(activity, title, listOf(Line(body)))

    /**
     * 弹窗的内容容器：标题 + 发丝线 + 若干行正文。
     *
     * 行距按段隔 [ShellSpace.CardGap]；标题与正文之间那条线只有一像素，作用是「把标题从
     * 正文里摘出来」，不引入第二层底色（M3 也是这个口径：弹窗内部用分隔与字阶分层，不套卡）。
     */
    fun content(activity: Activity, title: String, lines: List<Line>): LinearLayout {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                ViewShellStyle.dp(activity, ShellSpace.DialogPadding),
                ViewShellStyle.dp(activity, ShellSpace.DialogTop),
                ViewShellStyle.dp(activity, ShellSpace.DialogPadding),
                ViewShellStyle.dp(activity, ShellSpace.DialogBottom),
            )
        }
        box.addView(
            ViewShellStyle.textView(activity, title, ShellType.Section, EratwInk.textArgb()),
            layoutParams(activity, 0.dp),
        )
        if (lines.isNotEmpty()) {
            box.addView(
                View(activity).apply { setBackgroundColor(EratwInk.borderArgb()) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    ViewShellStyle.dp(activity, ShellSpace.Hairline),
                ).apply { topMargin = ViewShellStyle.dp(activity, ShellSpace.CardGap) },
            )
        }
        lines.forEachIndexed { index, line ->
            val style = when (line.tone) {
                Tone.HEADLINE -> ShellType.Title
                Tone.BODY -> ShellType.Body
                Tone.NOTE -> ShellType.Caption
            }
            val color = when (line.tone) {
                Tone.HEADLINE, Tone.BODY -> EratwInk.textArgb()
                Tone.NOTE -> EratwInk.textDimArgb()
            }
            // 强调档比标题再重一档：字号仍走 titleMedium，字面加粗只为了「先看到这一句」。
            val face = if (line.tone == Tone.HEADLINE) Typeface.DEFAULT_BOLD else null
            box.addView(
                ViewShellStyle.textView(activity, line.text, style, color, face),
                layoutParams(activity, if (index == 0) ShellSpace.DialogRowGap else ShellSpace.CardGap),
            )
        }
        return box
    }

    /**
     * 就地展开的一行（更新弹窗的「看说明」那一类）：点一下换成整段说明，再点收回。
     *
     * 展开层限高（[maxScreenFraction] 比例的屏高）且可滚 —— 这是为了让「正文 + 展开 + 按钮」
     * 在小屏上也把主按钮留在可视区内。
     *
     * 这一行是**可点控件**，所以按 M3 的两条硬要求办：触控高度取 [ShellSpace.RowMinHeight]，
     * 且必须有按压反馈（原先是一块没有状态反馈的裸文字，点下去屏幕上什么都不发生，玩家会以为
     * 没点中）。
     */
    fun expandable(
        activity: Activity,
        collapsedLabel: String,
        expandedText: String,
        maxScreenFraction: Float = 0.30f,
    ): View {
        val label = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ShellType.Tab.fontSize.value)
            setTextColor(EratwInk.textDimArgb())
            text = collapsedLabel
            isClickable = true
            minHeight = ViewShellStyle.dp(activity, ShellSpace.RowMinHeight)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                ViewShellStyle.dp(activity, ShellSpace.DialogPadding),
                ViewShellStyle.dp(activity, ShellSpace.DialogRowGap),
                ViewShellStyle.dp(activity, ShellSpace.DialogPadding),
                ViewShellStyle.dp(activity, ShellSpace.DialogRowGap),
            )
            setBackgroundResource(ViewShellStyle.selectableBackground(activity))
        }
        val maxPx = (activity.resources.displayMetrics.heightPixels * maxScreenFraction).toInt()
        val scroll = object : ScrollView(activity) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
            }
        }
        scroll.addView(label)
        label.setOnClickListener {
            val open = label.tag == null
            label.text = if (open) expandedText else collapsedLabel
            label.tag = if (open) true else null
        }
        return scroll
    }

    /**
     * 把统一外观打到已 `create()` 的对话框上：必须在 `show()` 之前调用。
     *
     * 面板底 [EratwInk.panelAltArgb] 比正文卡片更暗一档 —— 弹窗压在画面上，越暗越不抢读；
     * 遮罩 0.62 是把立绘/画面压到「看得出发生了什么，但读不清细节」的那一档。
     *
     * 开合动效交给窗口动画（`res/anim/shell_dialog_in|out`）：进场淡入 + 从 96% 放大 + 3% 上浮，
     * 出场淡出 + 收一档。动效只解释一件事 —— **这一层是从画面之上压下来的**，不是内容自己在变。
     */
    fun dress(dialog: AlertDialog) {
        val context = dialog.context
        val window = dialog.window ?: return
        window.setBackgroundDrawable(
            ViewShellStyle.panelDrawable(
                context,
                radius = ShellShape.SheetRadius,
                fill = EratwInk.panelAltArgb(),
                strokeColor = EratwInk.borderArgb(),
            ),
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(SCRIM_ALPHA)
        window.setWindowAnimations(R.style.ShellDialogAnimation)
        // 宽度按屏宽收一档：宽屏（平板/横屏）上不让弹窗拉成一整条横幅。
        window.attributes?.let { attrs ->
            attrs.width = (context.resources.displayMetrics.widthPixels * DIALOG_WIDTH_FRACTION).toInt()
            window.attributes = attrs
        }
        dialog.setOnShowListener { shown ->
            val alert = shown as? AlertDialog ?: return@setOnShowListener
            listOf(
                AlertDialog.BUTTON_POSITIVE,
                AlertDialog.BUTTON_NEGATIVE,
                AlertDialog.BUTTON_NEUTRAL,
            ).forEach { which ->
                alert.getButton(which)?.apply {
                    // 系统按钮默认全大写，中文看不出问题、英文就成了喊话；这里统一收回原样。
                    isAllCaps = false
                    letterSpacing = BUTTON_LETTER_SPACING
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, ShellType.Tab.fontSize.value)
                    setTextColor(
                        when (which) {
                            AlertDialog.BUTTON_POSITIVE -> EratwInk.accentArgb()
                            AlertDialog.BUTTON_NEGATIVE -> EratwInk.textArgb()
                            else -> EratwInk.textDimArgb()
                        },
                    )
                    setPadding(
                        ViewShellStyle.dp(context, ShellSpace.DialogButtonPadding),
                        0,
                        ViewShellStyle.dp(context, ShellSpace.DialogButtonPadding),
                        0,
                    )
                    minHeight = ViewShellStyle.dp(context, ShellSpace.RowMinHeight)
                }
            }
        }
    }

    /** 一步弹出的常见形态：标题 + 正文 + 正负两颗按钮。返回已显示的对话框（调用方不必持有）。 */
    fun show(
        activity: Activity,
        title: String,
        body: String,
        positiveLabel: String,
        onPositive: ((Boolean) -> Unit)? = null,
        negativeLabel: String? = null,
        onNegative: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ): AlertDialog? {
        val builder = builder(activity)
            .setView(content(activity, title, body))
            .setPositiveButton(positiveLabel) { _, _ -> onPositive?.invoke(true) }
        if (negativeLabel != null) {
            builder.setNegativeButton(negativeLabel) { _, _ -> onNegative?.invoke() }
        }
        if (onCancel != null) {
            builder.setOnCancelListener { onCancel.invoke() }
        }
        val dialog = builder.create()
        dress(dialog)
        return if (runCatching { dialog.show() }.isSuccess) dialog else null
    }

    private fun layoutParams(context: Context, topMargin: androidx.compose.ui.unit.Dp): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { this.topMargin = ViewShellStyle.dp(context, topMargin) }

    /** 遮罩不透明度：见类 KDoc 第 2 条偏离说明。 */
    private const val SCRIM_ALPHA = 0.62f

    /** 按钮字母间距（中文字距本来固定，英文字距靠它不散）。 */
    private const val BUTTON_LETTER_SPACING = 0.02f

    /** 浮层宽度占屏宽比例（宽屏上留出侧边，不把弹窗拉成横幅）。 */
    private const val DIALOG_WIDTH_FRACTION = 0.86f
}
