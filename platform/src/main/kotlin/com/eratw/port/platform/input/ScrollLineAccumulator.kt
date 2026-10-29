package com.eratw.port.platform.input

import kotlin.math.roundToInt

/**
 * 把拖动的**逐段增量**折成整行数（纯 JVM，可单测）。
 *
 * ## 为什么要攒零头
 *
 * Android `GestureDetector.onScroll` 交来的是**相邻两个 MOVE 事件之间**的位移，不是一共拖了多少：
 * 一次缓慢的半屏拖动会被切成几十段，每段只有几个像素。逐段 `roundToInt` 的话，
 * 每段都不到半行高 ⇒ 每段都算 0 行 ⇒ 手指推了半屏、画面一格都不动
 * （2026-10-29 真机反馈「无法向上滑」的一半成因；另一半是重印把视口拽回底部，见
 * `render/scroll/Viewport` 的「缩短不再拽回」）。
 *
 * 本类把没付出去的零头留着：攒够一行就付一行，快拖慢拖同速、同一段总位移得到同一个行数。
 *
 * ## 口径
 *
 *   * 符号沿用调用方给的方向：`distancePx > 0`（手指上移）= 看更后面的行，与
 *     [com.eratw.port.render.scroll.Viewport.scrollDown] 同向；换算成滚动动作由调用方取负。
 *   * 每次都用**当帧的行高**换算（`pixelsPerLine` 是入参而不是构造期定死）：「适应屏幕」会在
 *     拖动过程中换字号，行高跟着变，零头按当时的行高结账即可，不需要重算历史。
 *   * 四舍五入到最近整行（与 `TouchGestureController` 的滑动换算同口径），所以单向一次
 *     最多留下半行的零头；不会出现「攒了三行才动一下」的顿挫。
 */
class ScrollLineAccumulator {

    private var carryPx = 0f

    /** 一次按下开始时清账：上一拖的零头不许带到下一拖。 */
    fun reset() {
        carryPx = 0f
    }

    /** 还没付出去的零头（像素），仅供调试读数。 */
    fun carry(): Float = carryPx

    /**
     * 交入本段位移（像素），返回应当滚动的整行数（与 [distancePx] 同号，不足一行返回 0 并把零头留着）。
     *
     * @param pixelsPerLine 一行的高度；必须为正的有限值
     */
    fun add(distancePx: Float, pixelsPerLine: Float): Int {
        require(pixelsPerLine > 0f && pixelsPerLine.isFinite()) {
            "pixelsPerLine 必须是正的有限值，实到 $pixelsPerLine"
        }
        carryPx += distancePx
        val lines = (carryPx / pixelsPerLine).roundToInt()
        if (lines != 0) carryPx -= lines * pixelsPerLine
        return lines
    }
}
