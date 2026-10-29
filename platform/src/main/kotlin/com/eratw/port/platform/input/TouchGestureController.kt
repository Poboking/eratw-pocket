package com.eratw.port.platform.input

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 触屏手势控制器（INPUT-BIND）—— 把「按下的列/纵坐标、抬起的时间戳与纵坐标」折成平台无关的动作。
 *
 * 纯 Kotlin：无 Android import、不读系统时钟（时间戳由调用方传入）、无静态可变状态。
 * 事件搬运与像素换算在 [AndroidInputAdapter]，判定全部留在这里，便于在桌面 JVM 上单测。
 *
 * ## 动作语义
 * - [Action.Tap]：一次单击，携带**按下时**的列号，供后续按钮命中用。列号是半角格（与
 *   [com.eratw.port.render.button.ButtonLayer] 的命中坐标同一空间，见该文件 KDoc 的「输入与坐标」）。
 *   原版在 `mainPicBox_MouseDown` 就处理点击（`MainWindow.cs:784`），触屏必须等抬手才能分辨
 *   滑动；按钮命中列仍按按下点算，免得抬手时的横向漂移改写目标。
 * - [Action.Skip]：跳过消息。等价于原版右键/`Esc` 提交时的 `mesSkip = true`
 *   （`MainWindow.cs:814-816`、`MainWindow.cs:1415-1422`）：`PressEnterKey` 先把 `MesSkip`
 *   置真（`EmueraConsole.cs:1371-1373`），输入循环随后空转程序直到需要取值
 *   （`EmueraConsole.cs:1451-1456`）。
 * - [Action.Scroll]：纵向滚动。`lines > 0` 表示看更晚的行（视图内容上移），`lines < 0`
 *   表示回看更早的行。口径与 [com.eratw.port.render.scroll.Viewport.scrollDown] 一致，
 *   也等于滚轮 `delta < 0` 的那一侧：原式 `-Math.Sign(e.Delta) * SmallChange * ScrollHeight`
 *   （`MainWindow.cs:1262`）。
 * - [Action.LongPress]：按住超过 [longPressMs] 且没有形成滑动。原版**没有**长按语义
 *   （全仓无 `MouseLongClick` 一类入口），这是安卓指针模型自带的区分：`GestureDetector`
 *   触发 `onLongPress` 之后不会再回调 `onSingleTapUp`，所以长按不能被当作「第一击」，
 *   也不能与上一次单击凑成双击。本类照此把它从 tap 序列里摘出来，动作本身交绑定层
 *   （当前绑定层可以什么都不做）。
 * - [Action.None]：没有可产出的动作（没按下就收到抬起、或手势已被取消）。原版没有对应概念，
 *   只为绑定层的分支穷尽而存在。
 *
 * ## 触屏不提交
 * 本控制器**没有**提交动作：原版 F1-F12 预填的整段没有 `PressEnterKey`
 * （`MainWindow.cs:507-512`），板上同口径（`docs/golden-runbook.md:106`、`:125`）。
 * 触屏提交必须由绑定层显式调用 [KeyMacroController.submit]；[Action.Tap] 只是按钮命中输入。
 *
 * ## 双击 = 跳过（本波口径，不是 C# 直译）
 * 原版没有「双击窗口」：`mainPicBox_MouseDoubleClickCBCheck` 只把双击交给剪贴板处理器
 * （`MainWindow.cs:776-782`）；跳过消息靠右键（:814-816）、`Esc`（:1415-1422）或 `SKIPLOG`
 * （`Process.cs:1830-1833`）。安卓没有右键，本波按 BOARD 的 M5-TOUCH 行与
 * `docs/eratw-项目实施方案.md:165` 的口径，把「300ms 内的第二次单击」映射成 [Action.Skip]。
 * 因此 **300ms 与「第一击是否延迟」都没有 C# 行号可引**。本类选即时模型：
 * 第一击立刻产出 [Action.Tap]，窗口内的第二击产出 [Action.Skip]；窗口外两击各产出一次
 * [Action.Tap]。窗口按两次单击的**抬手时间**量，闭区间；按下时先清一次过期候选。
 * TODO(verify)：若绑定层要采用 Android 单击确认（GestureDetector 式「延迟 300ms 再算单击」），
 * 需要给本类加 pending-tap 状态与超时冲刷口；卡点＝契约只钉了 300ms，没定义第一击的回收策略，
 * 本波不擅自扩 API。
 *
 * ## 跳过的三种边界
 * - **无文本**：[Action.Skip] 只说明「这一下是跳过」，**不携带文本**。绑定层按
 *   `GameHost.submitSkip` 的语义取框内当前值提交（`PressEnterKey(mesSkip: true, ...)`，
 *   `MainWindow.cs:814-816`）。框内为空同样是有效提交：原版 `PressEnterKey` 只守
 *   `console != null && !IsInProcess`（`:922-936`），而 WAIT 系本来就忽略文本
 *   （`EmueraConsole.cs:1443-1448`、`QueueInputSink` KDoc 第 5 条）。
 * - **长按**：按住超过 [longPressMs] 给出 [Action.LongPress]，不计单击，并清掉上一下的
 *   配对候选（长按既不能当双击的第一击，也不会在抬手时补一次跳过）；与 Android
 *   `GestureDetector` 抑制 `onSingleTapUp` 的行为同向。
 * - **滑动**：纵向位移够半行就先判 [Action.Scroll]，并清掉双击候选；滑动不从属于点击，
 *   也不会在抬手时补一次跳过。横向位移仍不参与判定。
 * 三种边界都只是本类的**手势**结论；「这一次等待到底能不能跳过」由
 * [InputBarState.canSkip] 判（原版右键的 `console.IsWaitingEnterKey` 闸，`MainWindow.cs:806`）。
 *
 * ## 行数换算
 * 行高默认 19px（`ConfigData.cs:67` 的 `LineHeight` 默认值 19）；`Config.ScrollHeight`
 * 默认 1 行（`ConfigData.cs:74`），滚轮一格 = `SmallChange * ScrollHeight` 行
 * （`MainWindow.cs:1262`）。触屏按「纵向位移 / [pixelsPerLine] 四舍五入到最近整行」换算；
 * 不足半行（约 `pixelsPerLine / 2`）不产生滚动、按单击处理，这同时充当 tap 与 swipe 的分界，
 * 不再引入契约外的第二个人为阈值。恰好半个行高（四舍五入的临界点）**上下两个方向都算 1 行** ——
 * 先取绝对值再判方向，避免 `roundToInt` 的「半数向 +∞ 收」让同一下滑距离出现方向不对称。
 * PageUp/PageDown 的固定 10 行（`MainWindow.cs:1375-1399`）不是滑动语义，本类不建模。
 *
 * 下滑（手指向下、`dy > 0`）得到负行数，上滑（`dy < 0`）得到正行数。Android 的 y 轴向下为正，
 * 与 WinForms 的 `MouseEventArgs.Y` 同向。
 *
 * ## 边界
 * - 只建模纵向滑动；横向位移不参与判定（本波没有横向手势需求）。
 * - 多指不建模；[AndroidInputAdapter] 遇到第二根手指会调用 [onCancel]。
 * - 时间戳必须单调（Android 的 `MotionEvent.getEventTime()` 是开机以来的毫秒数）；时间戳倒退时
 *   不与上一击配对，也不做长按判定（按窗口外/普通点击处理）。
 * - 双击除时间窗外还要求**位置邻域**（M10-PLAY 收口 M7 钉桩②③）：原版没有双击跳过
 *   （`MainWindow.cs:776-782` 的双击只喂剪贴板处理器；跳过靠右键 `:814-816`、`Esc` `:1415-1422`），
 *   因此邻域阈值没有 C# 行号可引，纯按「真机快速点两个不同按钮不得被误判成跳过」这条
 *   产品判据来定：第二击与第一击的按下列相距不超过 [doubleTapColumnSlop]（半角格）、
 *   纵坐标相距不超过一行（[pixelsPerLine] 像素）。窗外或邻域外的第二击按**新的单击**产出
 *   [Action.Tap] 并重新起算候选。
 *   TODO(verify)：[DEFAULT_DOUBLE_TAP_COLUMN_SLOP] 的 6 格按「同一按钮内落点抖动 ≤ 数格、
 *   相邻按钮列距 ≥ 10 格」的经验取中，未真机标定；实测误触率仍高就调小，双击跳不动就调大。
 *
 * @param pixelsPerLine 一行对应的像素高度。真机传字形度量的行高（`CanvasConsoleView` 的
 *   `CellMetrics.lineHeight`），默认 19（`ConfigData.cs:67`）。
 * @param doubleTapWindowMs 双击窗口（毫秒，闭区间）；默认 300 是本波契约口径，见类文档。
 * @param longPressMs 长按阈值（毫秒）；默认 500 是 Android `ViewConfiguration` 的
 *   `getLongPressTimeout()` 缺省值，原版无对应概念（见 [Action.LongPress]）。
 * @param doubleTapColumnSlop 双击位置邻域的列阈值（半角格，闭区间）；判据与口径见类文档
 *   「边界」，真机标定前不要凭感觉改。
 */
class TouchGestureController(
    val pixelsPerLine: Float = DEFAULT_PIXELS_PER_LINE,
    val doubleTapWindowMs: Long = DEFAULT_DOUBLE_TAP_WINDOW_MS,
    val longPressMs: Long = DEFAULT_LONG_PRESS_MS,
    val doubleTapColumnSlop: Int = DEFAULT_DOUBLE_TAP_COLUMN_SLOP,
) {

    init {
        require(pixelsPerLine > 0f && pixelsPerLine.isFinite()) {
            "pixelsPerLine 必须是正的有限值，实到 $pixelsPerLine"
        }
        require(doubleTapWindowMs >= 0L) {
            "doubleTapWindowMs 不能为负，实到 $doubleTapWindowMs"
        }
        require(longPressMs > 0L) {
            "longPressMs 必须是正数，实到 $longPressMs"
        }
        require(doubleTapColumnSlop >= 0) {
            "doubleTapColumnSlop 不能为负，实到 $doubleTapColumnSlop"
        }
    }

    /** 一次手势的处置结论。 */
    sealed class Action {
        /** 单击。[col] 是按下位置对应的半角格列号。 */
        data class Tap(val col: Int) : Action()

        /**
         * 纵向滚动。[lines] > 0 = 看更晚的行（手指上滑），< 0 = 回看更早的行，
         * 与 `Viewport.scrollDown` 同向。
         *
         * **符号与 `CanvasConsoleView.scrollRows(rows)` 相反**（那一路正数 = 看更早），
         * 接线时取反后再传，别原样透传（现成对照：`app/.../MainActivity.kt:424` 的
         * `consoleView.scrollRows(-rows)`）。
         */
        data class Scroll(val lines: Int) : Action()

        /** 跳过消息：下一次显式提交必须带 `mesSkip = true`（`EmueraConsole.cs:1371-1373`）。 */
        object Skip : Action()

        /** 长按（超过 [longPressMs] 且未滑动）：不计单击，见类文档「跳过的三种边界」。 */
        object LongPress : Action()

        /** 没有动作（没按下就抬起，或手势被取消）。 */
        object None : Action()
    }

    private var downCol: Int = 0
    private var downY: Float = 0f
    private var downTimeMs: Long = 0L
    private var hasDown: Boolean = false

    /** 上一击（候选的第一击）的抬手时间；null = 无候选。 */
    private var lastTapTimeMs: Long? = null

    /** 上一击的按下列号（位置邻域判定的基准，见类文档「边界」）。 */
    private var lastTapCol: Int = 0

    /** 上一击的抬手纵坐标（像素）。 */
    private var lastTapY: Float = 0f

    /**
     * 记录按下（`MotionEvent.ACTION_DOWN`，原版对应 `mainPicBox_MouseDown` `MainWindow.cs:784`）。
     *
     * 顺带按**按下时间**清一次已过窗的候选：窗口以抬手时间为准，但按下时已经超窗的上一击
     * 不可能再与本击配对，提前清掉可让下一次判定少一次分支。时间戳倒退同样清候选。
     */
    fun onDown(col: Int, y: Float, timeMs: Long) {
        downCol = col
        downY = y
        downTimeMs = timeMs
        hasDown = true
        val last = lastTapTimeMs
        if (last != null && (timeMs < last || timeMs - last > doubleTapWindowMs)) {
            lastTapTimeMs = null
        }
    }

    /**
     * 记录抬起（`MotionEvent.ACTION_UP`）并给出动作。
     *
     * 判定顺序（顺序本身是判据，别换）：
     * 1. 没按下就抬起 ⇒ [Action.None]；
     * 2. 纵向位移四舍五入到非零行 ⇒ [Action.Scroll]，并中断双击配对（滑动不是点击）；
     * 3. 按住时长 ≥ [longPressMs] ⇒ [Action.LongPress]，同样中断双击配对
     *    （长按不是单击，见类文档「跳过的三种边界」）；
     * 4. 否则按单击处理 —— 与上一击的抬手时间相距不超过 [doubleTapWindowMs] **且落在
     *    位置邻域内**（列距 ≤ [doubleTapColumnSlop]、纵向 ≤ 一行）时是 [Action.Skip]；
     *    超窗或邻域外则是 [Action.Tap]，且本击重新起算候选（见类文档「边界」）。
     *
     * 滑动先于长按：一根手指按住不放再拖动，是「缓慢滚动」而不是长按，这与
     * Android `GestureDetector` 里 `onScroll` 与 `onLongPress` 可先后触发的实际行为同向，
     * 也保证滚动这个更常用的动作不会被 500ms 阈值吃掉。
     *
     * @param y 抬手时的纵坐标（像素，向下为正）；与 [onDown] 的 [y] 求差
     * @param timeMs 抬手时间（毫秒，单调）；双击窗口与长按阈值都按这个时间量
     */
    fun onUp(y: Float, timeMs: Long): Action {
        if (!hasDown) {
            return Action.None
        }
        hasDown = false
        val dy = y - downY
        if (!dy.isFinite()) {
            return Action.None
        }
        val steps = (abs(dy) / pixelsPerLine).roundToInt()
        val lines = if (dy > 0f) -steps else steps
        if (lines != 0) {
            lastTapTimeMs = null
            return Action.Scroll(lines)
        }
        if (timeMs >= downTimeMs && timeMs - downTimeMs >= longPressMs) {
            // 长按消费掉候选：它既不是单击，也不该成为下一次双击的第一击。
            lastTapTimeMs = null
            return Action.LongPress
        }
        val last = lastTapTimeMs
        if (last != null && timeMs >= last && timeMs - last <= doubleTapWindowMs &&
            lastTapCol - downCol <= doubleTapColumnSlop && downCol - lastTapCol <= doubleTapColumnSlop &&
            abs(y - lastTapY) <= pixelsPerLine
        ) {
            // 第二击消费掉候选，三击的第一击重新起算（Android 双击也是「双击 + 新的单击」）。
            lastTapTimeMs = null
            return Action.Skip
        }
        // 超窗、时间倒退或**邻域外**（快速点两个不同按钮）：本击按新的单击起算，
        // 候选位置换成本击 —— 邻域外不产出 Skip，按钮命中照常走 Tap。
        lastTapTimeMs = timeMs
        lastTapCol = downCol
        lastTapY = y
        return Action.Tap(downCol)
    }

    /**
     * 取消当前按下（`MotionEvent.ACTION_CANCEL`，或适配器判定多指手势不建模）。
     * 只丢按下态，不清上一击：那一击仍是有效单击，随后的单指点击仍可在窗口内与它配对。
     */
    fun onCancel() {
        hasDown = false
    }

    companion object {
        /** 行高默认值 19px（`ConfigData.cs:67`）。 */
        const val DEFAULT_PIXELS_PER_LINE: Float = 19f

        /** 双击窗口默认 300ms（本波契约口径；C# 侧无此实现，见类文档）。 */
        const val DEFAULT_DOUBLE_TAP_WINDOW_MS: Long = 300L

        /**
         * 长按阈值默认 500ms —— Android `ViewConfiguration.getLongPressTimeout()` 的缺省值。
         * 原版没有长按语义，这个数没有 C# 行号可引（见 [Action.LongPress]）。
         */
        const val DEFAULT_LONG_PRESS_MS: Long = 500L

        /**
         * 双击位置邻域的列阈值：6 个半角格（闭区间）。没有 C# 出处（原版无双击跳过，
         * 见类文档「边界」）；取「同一按钮内落点抖动 ≤ 数格、相邻按钮列距 ≥ 10 格」的
         * 经验中值，待真机标定（TODO(verify)）。
         */
        const val DEFAULT_DOUBLE_TAP_COLUMN_SLOP: Int = 6
    }
}
