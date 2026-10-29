package com.eratw.port.platform.input

import android.view.KeyEvent
import android.view.MotionEvent
import com.eratw.port.render.input.MacroKeys
import kotlin.math.floor

/**
 * Android 事件适配器（INPUT-BIND）—— 只做事件搬运与坐标换算，规则全在两个纯控制器里。
 *
 * - `MotionEvent` 的 DOWN/UP 时间、纵坐标 → [TouchGestureController]；横坐标按 [cellWidthPx]
 *   折成半角格列号（与 [com.eratw.port.render.button.ButtonLayer] 的命中列同一空间）。
 * - `KeyEvent` 的功能键/数字键 → WinForms 虚拟键码，修饰键 → [MacroKeys.Modifier]
 *   → [KeyMacroController]。
 *
 * 本类不含任何「要不要跳过、要不要预填、要不要提交」的判断；判定与出处全部在被调用的控制器
 * 与其 KDoc 里。动作通过构造参数的两个回调交回绑定层，本类不持有 Activity/View。
 *
 * ## 坐标换算
 * 半角格宽取 [cellWidthPx]，真机应与绘制侧同源：`CanvasConsoleView` 的 `halfCellPx`
 * 就是 `CellMetrics.cellPixelWidth`（`render-canvas` 的 `CanvasConsoleView.kt:51`，口径说明
 * 同文件 `:24`）。列号用 `floor((x - columnOriginPx) / cellWidthPx)`，**不夹到 0**：
 * [com.eratw.port.render.button.ButtonLayer] 的 KDoc 已说明居中/右对齐行的 `colStart` 可以为负，
 * 夹取反而会把视口左沿外的命中区折回可见列。`columnOriginPx` 用于减掉 View 的左内边距。
 *
 * 纵向不做换算：滑动行数由 [TouchGestureController.pixelsPerLine] 决定，构造
 * [TouchGestureController] 时应传与绘制侧一致的行高（`CellMetrics.lineHeight`）。
 *
 * ## 事件范围
 * 只处理单指 DOWN/UP：MOVE 原样吞掉但不逐帧上报（本波没有实时跟手滚动，滑动量在 UP 时一次算清）；
 * 第二根手指按下/抬起时整体取消本次手势（多指不建模），对应 [TouchGestureController.onCancel]。
 * `ACTION_CANCEL` 同样只取消，不产出动作。
 *
 * ## 按键换算（为什么不能原样透传）
 * Android 的 `KeyEvent` 键码与 WinForms 虚拟键码不重合，且**数值会撞车**：
 * `KEYCODE_CTRL_LEFT` 是 113，正好落在 `MacroKeys.VK_F1..VK_F12`（112..123）里，原样透传会被
 * 当成 F2。故只映射三组（`MainWindow.cs:496`、`:517`、`:521` 的区间）：
 * - `KEYCODE_F1..KEYCODE_F12` → [MacroKeys.VK_F1]..[MacroKeys.VK_F12]（131..142 → 112..123）；
 * - `KEYCODE_0..KEYCODE_9` → 48..57；
 * - `KEYCODE_NUMPAD_0..KEYCODE_NUMPAD_9` → 96..105。
 * 其余键返回 null、不进入宏层。修饰键按原版 `keyData & Keys.Modifiers` 的整集合相等折成
 * Shift / Control / 无修饰 / 其它（`MainWindow.cs:493-495`）：只认 Shift、Ctrl、Alt 三位，
 * CapsLock 与 Windows 键不进这三旗标（WinForms 的 `Keys.Modifiers` 只有三位）；
 * Alt、Ctrl+Shift 等落 [MacroKeys.Modifier.OTHER]，由 `MacroKeys.dispatch` 判为不响应。
 *
 * ## minSdk 21
 * 用到的都是 API 21 之前就有的成员：`getActionMasked`（API 8）、`getX`/`getY`/`getEventTime`
 * （API 1）、`getKeyCode`/`getAction`/`getMetaState`（API 1）、`META_CTRL_ON`（API 11）、
 * `KEYCODE_F1`（API 11）。没有使用 API 23 起才有的 `getActionButton` 一类方法。
 * 时间戳取 `getEventTime()`（开机以来的单调毫秒），不用墙钟。
 *
 * @param touch 手势控制器（本类只喂 DOWN/UP 与取消）
 * @param keyMacro 键盘宏控制器（本类只喂归一化后的键码与修饰键）
 * @param cellWidthPx 半角格的像素宽度（绘制侧 `CellMetrics.cellPixelWidth`）
 * @param onTouchAction 手势产出动作时的回调；DOWN/MOVE/CANCEL 不触发
 * @param onKeyOutcome 每次按键都回调一次（含不响应的 [MacroKeys.Decision.Ignored]），
 *   便于绑定层根据 `consumed` 与 `groupLabel` 更新界面
 * @param columnOriginPx 列 0 的像素起点（通常是 View 的左内边距），默认 0
 */
class AndroidInputAdapter(
    private val touch: TouchGestureController,
    private val keyMacro: KeyMacroController,
    private val cellWidthPx: Float,
    private val onTouchAction: (TouchGestureController.Action) -> Unit,
    private val onKeyOutcome: (KeyMacroController.Outcome) -> Unit,
    private val columnOriginPx: Float = 0f,
) {

    init {
        require(cellWidthPx > 0f && cellWidthPx.isFinite()) {
            "cellWidthPx 必须是正的有限值，实到 $cellWidthPx"
        }
    }

    private var tracking: Boolean = false

    /**
     * 喂一个触摸事件；返回该事件是否被消费（直接作为 View 的 `onTouchEvent` 返回值）。
     * 手势判定在 [TouchGestureController]，本函数只搬运时间与坐标。
     */
    fun handleTouchEvent(event: MotionEvent): Boolean = when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            tracking = true
            touch.onDown(columnOf(event.getX()), event.getY(), event.eventTime)
            true
        }
        MotionEvent.ACTION_MOVE -> true
        MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
            // 多指不建模：整体取消，避免把两指中任意一指的位置当成单击/滑动终点。
            if (tracking) {
                touch.onCancel()
                tracking = false
            }
            true
        }
        MotionEvent.ACTION_UP -> {
            if (tracking) {
                val action = touch.onUp(event.getY(), event.eventTime)
                tracking = false
                if (action !== TouchGestureController.Action.None) {
                    onTouchAction(action)
                }
            }
            true
        }
        MotionEvent.ACTION_CANCEL -> {
            if (tracking) {
                touch.onCancel()
                tracking = false
            }
            true
        }
        else -> false
    }

    /**
     * 喂一个按键事件；返回是否消费该键（直接作为 `onKeyDown` 的返回值）。
     * 只处理 `ACTION_DOWN`（按住产生的重复 down 也照原版逐次处理）；
     * `ACTION_UP` 与非宏键返回 false，交回系统。
     *
     * @param waitingPrimitive `console.IsWaitingPrimitive`（`MainWindow.cs:385`），默认 false
     * @param useKeyMacro `Config.UseKeyMacro`（`MainWindow.cs:488`），默认 true（`ConfigData.cs:53`）
     */
    fun handleKeyEvent(
        event: KeyEvent,
        waitingPrimitive: Boolean = false,
        useKeyMacro: Boolean = true,
    ): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        val virtualKey = virtualKeyOf(event.keyCode) ?: return false
        val outcome = keyMacro.onKey(
            keyCode = virtualKey,
            modifier = modifierOf(event.metaState),
            waitingPrimitive = waitingPrimitive,
            useKeyMacro = useKeyMacro,
        )
        onKeyOutcome(outcome)
        return outcome.consumed
    }

    /** 像素横坐标 → 半角格列号（不夹取，理由见类文档）。 */
    private fun columnOf(x: Float): Int = floor((x - columnOriginPx) / cellWidthPx).toInt()

    /** Android 键码 → WinForms 虚拟键码；只映射宏层认的三组，其余 null。 */
    private fun virtualKeyOf(androidKeyCode: Int): Int? = when (androidKeyCode) {
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
            MacroKeys.VK_F1 + (androidKeyCode - KeyEvent.KEYCODE_F1)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
            MacroKeys.VK_DIGIT_0 + (androidKeyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
            MacroKeys.VK_NUMPAD_0 + (androidKeyCode - KeyEvent.KEYCODE_NUMPAD_0)
        else -> null
    }

    /** Android metaState → [MacroKeys.Modifier] 的四态（整集合口径，见类文档）。 */
    private fun modifierOf(metaState: Int): MacroKeys.Modifier {
        val shift = (metaState and KeyEvent.META_SHIFT_ON) != 0
        val control = (metaState and KeyEvent.META_CTRL_ON) != 0
        val alt = (metaState and KeyEvent.META_ALT_ON) != 0
        return when {
            shift && !control && !alt -> MacroKeys.Modifier.SHIFT
            control && !shift && !alt -> MacroKeys.Modifier.CONTROL
            !shift && !control && !alt -> MacroKeys.Modifier.NONE
            else -> MacroKeys.Modifier.OTHER
        }
    }
}
