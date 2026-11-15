package com.eratw.port.render.canvas

import com.eratw.port.core.host.BusyIndicator

/**
 * 长页「少女祈祷中」横带的**纯几何/形态层**（JVM 可直测，不碰 Android `Canvas`）。
 *
 * 玩家可见形态由主人拍死：**水平居中**、**单独占内容区最下面一行**（带高 = 一行）、
 * 呼吸式（周期约 1 s）、不新增外挂素材 —— 文字用游戏自己的字体与前景色画。
 *
 * 本文件只算「在哪画、画什么、多亮」，落笔在 `CanvasConsoleView.drawBusyIndicatorBand`。
 * 把几何抽出来是为了让「带高 = 一行 / 左右留白差 ≤ 1px / 1 s 才出现 / 置 null 不画」
 * 这几条能在纯 JVM 单测里逐值钉住，不必起 Robolectric。
 *
 * **不参与语义**：这里的一切都不打印行、不改 `displayLines` / `revision` / 帧快照。
 */
data class BusyBandRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object BusyIndicatorBand {

    /** 横带文案主体；动点按 [DOT_FRAME_MS] 递增追加（形态：`少女祈祷中.` … `少女祈祷中...`）。 */
    const val LABEL: String = "少女祈祷中"

    /** 动点换帧间隔：三帧合计 999 ms ≈ 主人要的「约 1 s 一个周期」。 */
    const val DOT_FRAME_MS: Long = 333L

    /** 呼吸（透明度往返）周期。 */
    const val BREATH_PERIOD_MS: Long = 1_000L

    /** 呼吸最暗/最亮两端的 alpha（最暗仍清晰可读，不做整条淡出）。 */
    const val MIN_ALPHA: Int = 140
    const val MAX_ALPHA: Int = 255

    private const val DOT_FRAMES = 3

    /**
     * 只在「有指示器且本页已跑满 [BusyIndicator.MIN_ELAPSED_MS]」时上屏。
     *
     * 阈值唯一定义在 core 的 `BusyIndicator.MIN_ELAPSED_MS`，宿主推点读的是同一个常量，
     * 这里不另立第二把尺。
     */
    fun isVisible(indicator: BusyIndicator?): Boolean =
        indicator != null && indicator.elapsedMs >= BusyIndicator.MIN_ELAPSED_MS

    /**
     * 横带矩形：紧贴内容区下沿、高度恰好一行（行高取调用方既有 `lineHeightPx`）。
     *
     * @param contentLeft 内容区左沿（`contentLeftPx()`，已含居中偏移）
     * @param contentRight 内容区右沿（`contentLeft + layoutWidthPx()`）
     * @param contentBottom 内容区下沿（`height - paddingBottom`）
     * @param rowHeightPx 既有行高（`CanvasConsoleView.lineHeightPx`），不是另算的新尺
     */
    fun bandRect(
        contentLeft: Float,
        contentRight: Float,
        contentBottom: Float,
        rowHeightPx: Float,
    ): BusyBandRect {
        require(rowHeightPx >= 0f) { "行高不能为负：$rowHeightPx" }
        return BusyBandRect(
            left = contentLeft,
            top = contentBottom - rowHeightPx,
            right = contentRight,
            bottom = contentBottom,
        )
    }

    /** 当前帧文案：`少女祈祷中` + 1..3 个点（每 [DOT_FRAME_MS] 进一帧）。 */
    fun text(indicator: BusyIndicator): String {
        val elapsed = (indicator.elapsedMs - BusyIndicator.MIN_ELAPSED_MS).coerceAtLeast(0L)
        val frame = ((elapsed / DOT_FRAME_MS) % DOT_FRAMES).toInt() + 1
        return LABEL + ".".repeat(frame)
    }

    /**
     * 呼吸 alpha（三角波，周期 [BREATH_PERIOD_MS]）：从 [MIN_ALPHA] 升到 [MAX_ALPHA]
     * 再降回，不做整条消失 —— 指示器本身在动，不需要再闪断。
     */
    fun textAlpha(indicator: BusyIndicator): Int {
        val phase = Math.floorMod(indicator.elapsedMs, BREATH_PERIOD_MS)
        val half = BREATH_PERIOD_MS / 2L
        val t = if (phase <= half) phase.toDouble() / half else (BREATH_PERIOD_MS - phase).toDouble() / half
        return (MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * t).toInt().coerceIn(MIN_ALPHA, MAX_ALPHA)
    }

    /**
     * 水平居中的落笔 x：两侧留白相等，量宽用调用方**既有文本量宽能力**
     * （`Paint.measureText`）的读数，本函数只做算术 —— 不新造一把尺。
     *
     * 文本比带还宽时按左沿落笔（不画到内容区外）。
     */
    fun textX(rect: BusyBandRect, measuredWidthPx: Float): Float =
        rect.left + ((rect.width - measuredWidthPx) / 2f).coerceAtLeast(0f)

    /** 竖直居中基线：行盒内居中，和正文 `baselineOffsetOf` 同一套 `FontMetrics` 口径。 */
    fun baselineY(rect: BusyBandRect, ascent: Float, descent: Float): Float =
        rect.top + (rect.height - (descent - ascent)) / 2f - ascent
}
