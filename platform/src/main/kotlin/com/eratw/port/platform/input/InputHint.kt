package com.eratw.port.platform.input

/**
 * 输入条提示语的**唯一来源** —— 纯逻辑，不碰 Android API。
 *
 * ## 为什么单独抽一层
 * 2026-10-29 玩家反馈：「点进了钓鱼列表，发现最下面没有退出这个键，要自己手输（**也没提示**）」。
 * 前半句是「WAIT 系页面屏上不印任何 `[N]`」（见 [InputBarState.canSkip] 与壳层
 * `ContinueButton`），后半句就是本类：提示语原先散在 `MainActivity.refreshShell` 的
 * `when` 里，`awaitingAnyKey` 这一档根本没有分支 —— 玩家停在「按任意键继续」页时，
 * 输入条上写的仍是「输入后回车提交」，等于**告诉玩家去手输**。
 *
 * ## 四档口径（与原版一一对得上）
 * - **游戏未运行**：`Submit.DISABLED`（宿主没起 / VM 线程已停）。原版没有这一档
 *   （WinForms 窗口恒在、控制台状态机自己扛），属移植增量文案。
 * - **点画面空白处或按「继续」翻页**：WAIT 系等待。原版的推进方式是**点一下画面**
 *   或敲任意键 —— `MainWindow.cs:806` 的条件 `console.IsWaitingEnterKey &&
 *   selectedString == null`，块内左键走 `:818-820` 的 `PressEnterKey(mesSkip: false,
 *   inputsByMouse: true)`；`IsWaitingEnterKey` 只对 `EnterKey` / `AnyKey` 两种等待为真
 *   （`EmueraConsole.cs:324-343`）。触摸端没有回车键，「点画面」也没有视觉暗示，
 *   所以提示语必须把这两条通道都写出来。
 * - **当前未等待输入**：`Submit.PRERUN`（脚本在执行期轮询 `GETTEXTBOX` / `AWAIT_UI`）。
 *   原版此时提交不落输入队列、只作为「文本框 + 回车沿变」交给脚本
 *   （`MainWindow.cs:922-936` 的 `IsInProcess` 早退），文案要跟这条口径一致。
 * - **输入后回车提交**：`INPUT` / `INPUTS` 的常规取值等待。
 *
 * 判定顺序与 [InputBarState] 的字段优先级一致：禁用 > WAIT 系 > 抢跑 > 常规。
 * WAIT 系排在抢跑之前是因为两者互斥（`awaitingAnyKey` 为真时 `submit` 必为
 * `NORMAL`，见 `InputBarState.of`），顺序只影响可读性、不影响取值。
 */
object InputHint {

    /** 游戏没跑起来（演示屏 / 产物缺失 / VM 已停）。 */
    const val NOT_RUNNING: String = "游戏未运行"

    /** WAIT 系（`PRINTW` / `PRINTFORMW` / `WAIT` / `WAITANYKEY`）等待推进。 */
    const val CONTINUE: String = "点画面空白处或按「继续」翻页"

    /** 非等待期的提交（抢跑，交给脚本轮询）。 */
    const val PRERUN: String = "当前未等待输入（提交按文本+回车事件）"

    /** 常规取值等待。 */
    const val NORMAL: String = "输入后回车提交"

    /**
     * 由输入条状态推出提示语。
     *
     * @param state 当前输入条状态（`InputBarState.of` 的产物）
     * @return 显示在输入条上的一句话，四档之一
     */
    fun of(state: InputBarState): String = when {
        !state.canSubmit -> NOT_RUNNING
        state.awaitingAnyKey -> CONTINUE
        state.isPrerun -> PRERUN
        else -> NORMAL
    }
}
