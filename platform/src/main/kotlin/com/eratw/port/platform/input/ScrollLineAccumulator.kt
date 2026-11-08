package com.eratw.port.platform.input

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 把拖动的**逐段增量**折成整行数（纯 JVM，可单测）。
 *
 * ## 为什么要攒零头
 *
 * 拖动交来的是**相邻两个 MOVE 事件之间**的位移，不是一共拖了多少：
 * 一次缓慢的半屏拖动会被切成几十段，每段只有几个像素。逐段 `roundToInt` 的话，
 * 每段都不到半行高 ⇒ 每段都算 0 行 ⇒ 手指推了半屏、画面一格都不动
 * （2026-10-29 真机反馈「无法向上滑」的一半成因；另一半是重印把视口拽回底部，见
 * `render/scroll/Viewport` 的「缩短不再拽回」）。
 *
 * 本类把没付出去的零头留着：攒够一行就付一行，快拖慢拖同速、同一段总位移得到同一个行数。
 *
 * ## 口径
 *
 *   * **入参和读数都是「手指口径」，中间不换手**：[fingerDeltaPx] 是手指的纵向位移
 *     （安卓坐标，向下为正），读数是同号的整行数，**原样**交给
 *     `CanvasConsoleView.scrollRows` 就是「内容跟着手指走」
 *     （那一路 `rows > 0` = [com.eratw.port.render.scroll.Viewport.scrollUp] = 回看更早的行）。
 *     调用方**不要再取负**：一次取负是历史包袱，两次取负叠起来会把整条方向翻反 ——
 *     2026-11-02 把接线从 `GestureDetector.onScroll` 收回裸 MOVE 时，喂进来的量从
 *     `distanceY`（AOSP 算的是 `lastPosY - posY`，与手指位移反号）换成了裸位移，
 *     而调用处那个照抄下来的取负没跟着摘，玩家于是「手指往下滑反而往更后面翻」。
 *     判据钉在 `ScrollDirectionTest`：同一次手势里本类的读数与抬手快扫兜底
 *     （[TouchGestureController.Action.Scroll]，它自己的口径是「正 = 看更晚」）换算到
 *     `scrollRows` 后必须同号。
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
     * 交入本段**手指位移**（像素，安卓坐标向下为正），返回同号的整行数
     * （不足一行返回 0 并把零头留着，可直接传 `CanvasConsoleView.scrollRows`，见类文档「口径」）。
     *
     * 半行临界与 [TouchGestureController.onUp] 同一口径：**先取绝对值再判方向**
     * （`TouchGestureController` 类文档「行数换算」）。直接对带符号比值 `roundToInt` 的话，
     * `Math.round` 的「半数向 +∞ 收」会让恰好 -0.5 行的零头收成 0、+0.5 行收成 1 ——
     * 同一个半行临界，快扫兜底（抬手按总位移独立判）付 1 行、慢拖攒零头这一路却付 0 行，
     * `ScrollDirectionTest` 钉的「同手势两条路同向同量」恰好在 ±半行破口。带符号比值取
     * 绝对值收成整行数再还原符号，两个方向的临界读数就都收敛到 1 行。
     *
     * 非有限位移（驱动偶发的 NaN / ±Inf 坐标）整段丢弃：不滚动、零头不动。这不是「宽容」，
     * 是防状态污染 —— 零头是先加后算的，一旦写进 NaN，`Float.roundToInt()` 对 NaN 抛
     * `IllegalArgumentException`，这一拖之后的每一段都抛（按帧吞进 UI 线程的兜底里，滚不动）；
     * 写进 +Inf 则 `Math.round` 饱和成 `Int.MAX_VALUE`，之后每一次换算都付 `Int.MAX_VALUE` 行，
     * 要等下一次按下时的 [reset] 才恢复。有限位移的读数逐值不变（半行临界除外，见上）。
     *
     * @param pixelsPerLine 一行的高度；必须为正的有限值
     */
    fun add(fingerDeltaPx: Float, pixelsPerLine: Float): Int {
        require(pixelsPerLine > 0f && pixelsPerLine.isFinite()) {
            "pixelsPerLine 必须是正的有限值，实到 $pixelsPerLine"
        }
        if (!fingerDeltaPx.isFinite()) {
            return 0
        }
        carryPx += fingerDeltaPx
        if (!carryPx.isFinite()) {
            // 同号两段 Float.MAX 相加才会溢到这里；宁可把这一拖的零头清零，也不留 Inf。
            carryPx = 0f
            return 0
        }
        val ratio = carryPx / pixelsPerLine
        val steps = abs(ratio).roundToInt()
        val lines = if (ratio < 0f) -steps else steps
        if (lines != 0) carryPx -= lines * pixelsPerLine
        return lines
    }
}
