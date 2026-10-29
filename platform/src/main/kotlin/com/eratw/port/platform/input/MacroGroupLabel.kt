package com.eratw.port.platform.input

/**
 * 换宏组标签的显隐状态 —— `setNewMacroGroup` 的计时器部分（`MainWindow.cs:1590-1611`）。
 *
 * ## 原版读数
 * 换组时 [KeyMacroController.onKey] 走 `setNewMacroGroup`：`labelTimerCount = 0`、
 * `macroGroup = group`、`labelMacroGroupChanged.Text = KeyMacro.GetGroupName(group)`、
 * `timerKeyMacroChanged.Interval = 200`、`Start()`、然后显示标签
 * （`MainWindow.cs:1601-1611`）。计时器每拍 `labelTimerCount++`，`> 10` 时停表并隐藏
 * （`:1590-1599`）。于是：
 * - 可见时长 = 11 拍 × 200ms = [visibleMs]（2,200ms）；
 * - 第 1..10 拍仍可见，第 11 拍开始隐藏（判定是 `> 10`，不是 `>= 10`）；
 * - 每次 `show` 都把计数清零并重新计时（重复换组不会累加，`:1603`）。
 *
 * ## 为什么单独建模
 * 这不是渲染层的布局问题，而是「哪一刻该把组名标签拿掉」的**状态机**：安卓侧没有
 * WinForms 计时器，绑定时由某个 `postDelayed`/帧循环每 200ms 调一次 [onTick]。
 * 把计数留在纯 JVM 层，真机只负责搬运，且可在桌面 JVM 单测里逐拍断言。
 *
 * ## 与原版的差异（刻意）
 * 原版 `timerKeyMacroChanged.Interval` 每换组都重设一次（`:1606`），本类把间隔做成构造参数；
 * 真机侧只读 [intervalMs] 去排下一拍，不再每换组重建计时器 —— 语义等价（每次换组计数归零，
 * 间隔恒为 200ms），省掉一次对象重建。
 *
 * @param intervalMs 一拍的长度，默认 200（`MainWindow.cs:1606`）
 * @param visibleTicks 可见的拍数；第 [visibleTicks] 拍就隐藏。默认 11 对应原版
 *   `labelTimerCount > 10`（`MainWindow.cs:1593`）—— 计数从 0 起算，第 11 拍越界
 */
class MacroGroupLabel(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val visibleTicks: Int = DEFAULT_VISIBLE_TICKS,
) {

    init {
        require(intervalMs > 0L) { "intervalMs 必须是正数，实到 $intervalMs" }
        require(visibleTicks > 0) { "visibleTicks 必须是正数，实到 $visibleTicks" }
    }

    /** 当前该显示的组名；null = 标签隐藏。 */
    var text: String? = null
        private set

    /** 是否可见（= [text] 非 null）。 */
    val visible: Boolean
        get() = text != null

    /** 已走过的拍数（0 = 刚显示）。重复 [show] 归零。 */
    var ticks: Int = 0
        private set

    /** 从 [show] 到隐藏的总时长（毫秒）：11 拍 × 200ms = 2,200ms（`:1590-1611`）。 */
    val visibleMs: Long
        get() = intervalMs * visibleTicks

    /**
     * 显示组名（`MainWindow.cs:1603-1610`）：写文本、计数归零、置可见。
     * 与原版一样**不**校验组名内容，空串也照样显示。
     */
    fun show(label: String) {
        text = label
        ticks = 0
    }

    /**
     * 走一拍（对应 `timerKeyMacroChanged_Tick` 的 `labelTimerCount++` 与 `> 10` 判定）。
     *
     * @return 该拍之后标签是否仍可见
     */
    fun onTick(): Boolean {
        if (text == null) {
            return false
        }
        ticks++
        if (ticks >= visibleTicks) {
            text = null
        }
        return text != null
    }

    /**
     * 立即隐藏（绑定层在别处重建界面时用；原版没有对应行，属防御性入口）。
     * 与计时器自然到期同效，但不等下一拍。
     */
    fun dismiss() {
        text = null
        ticks = 0
    }

    companion object {
        /** 一拍 200ms（`MainWindow.cs:1606`）。 */
        const val DEFAULT_INTERVAL_MS: Long = 200L

        /**
         * 可见 11 拍：原判据 `labelTimerCount > 10`（`MainWindow.cs:1593`），
         * 计数从 0 起算，第 11 拍隐藏。等价于「可见时长 2,200ms」。
         */
        const val DEFAULT_VISIBLE_TICKS: Int = 11
    }
}
