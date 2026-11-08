package com.eratw.port.android.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
 * 标题与正文由 [content] 自绘（字号、行距、强调档在这里定死），面板底 / 圆角 / 发丝描边 /
 * 遮罩浓度 / 按钮字色与大小写由 [dress] 一次打上。按钮要 `setOnShowListener` 才取得到，
 * 所以 [dress] 里挂的是监听而不是当场改。
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
     * 行距按段隔 10dp；标题与正文之间那条线只有一像素，作用是「把标题从正文里摘出来」，
     * 不引入第二层底色。
     */
    fun content(activity: Activity, title: String, lines: List<Line>): LinearLayout {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(activity, 22), dip(activity, 18), dip(activity, 22), dip(activity, 4))
        }
        box.addView(
            textView(activity, title, 15f, EratwInk.textArgb(), Typeface.create("sans-serif-medium", Typeface.NORMAL)),
            layoutParams(activity, topDip = 0),
        )
        if (lines.isNotEmpty()) {
            box.addView(
                View(activity).apply {
                    setBackgroundColor(EratwInk.borderArgb())
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dip(activity, 1))
                    .apply { topMargin = dip(activity, 12) },
            )
        }
        lines.forEachIndexed { index, line ->
            val (size, color, face) = when (line.tone) {
                Tone.HEADLINE -> Triple(16f, EratwInk.textArgb(), Typeface.DEFAULT_BOLD)
                Tone.BODY -> Triple(15f, EratwInk.textArgb(), Typeface.DEFAULT)
                Tone.NOTE -> Triple(13f, EratwInk.textDimArgb(), Typeface.DEFAULT)
            }
            box.addView(textView(activity, line.text, size, color, face), layoutParams(activity, topDip = if (index == 0) 14 else 10))
        }
        return box
    }

    /**
     * 就地展开的一行（更新弹窗的「看说明」那一类）：点一下换成整段说明，再点收回。
     *
     * 展开层限高（[maxScreenFraction] 比例的屏高）且可滚 —— 这是为了让「正文 + 展开 + 按钮」
     * 在小屏上也把主按钮留在可视区内。
     */
    fun expandable(
        activity: Activity,
        collapsedLabel: String,
        expandedText: String,
        maxScreenFraction: Float = 0.30f,
    ): View {
        val label = TextView(activity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(EratwInk.textDimArgb())
            text = collapsedLabel
            isClickable = true
            setPadding(dip(activity, 12), dip(activity, 8), dip(activity, 12), dip(activity, 8))
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
     */
    fun dress(dialog: AlertDialog) {
        val context = dialog.context
        dialog.window?.apply {
            setBackgroundDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dip(context, 12).toFloat()
                    setColor(EratwInk.panelAltArgb())
                    setStroke(dip(context, 1), EratwInk.borderArgb())
                },
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
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
                    letterSpacing = 0.02f
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(
                        when (which) {
                            AlertDialog.BUTTON_POSITIVE -> EratwInk.accentArgb()
                            AlertDialog.BUTTON_NEGATIVE -> EratwInk.textArgb()
                            else -> EratwInk.textDimArgb()
                        },
                    )
                    setPadding(dip(context, 14), 0, dip(context, 14), 0)
                    minHeight = dip(context, 44)
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

    private fun textView(
        context: Context,
        text: String,
        sizeSp: Float,
        color: Int,
        face: Typeface,
    ): TextView = TextView(context).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setTypeface(face)
        // 中英混排的正文：行距比系统默认松一点，长句才不糊在一起。
        setLineSpacing(0f, 1.12f)
    }

    private fun layoutParams(context: Context, topDip: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dip(context, topDip) }

    private fun dip(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
