package com.eratw.port.platform.input

/**
 * HOST-06：点**空白 / 无按钮区**要不要补一条空回车 —— 纯判定，不碰 Android API。
 *
 * ## 原版口径
 * 原版左键按下走 `MainWindow.mainPicBox_MouseDown`（`reference/emuera-cs` 下
 * `MinorShift.Emuera.Forms/MainWindow.cs:784-822`）：
 * - 先按贴底前的视图取命中目标 `selectedString = console.SelectedString`（`:800`），
 *   未贴底且左/右键时先回贴底并强刷（`:801-805`）；
 * - 命中判定块的条件是 `console.IsWaitingEnterKey && selectedString == null`（`:806`）——
 *   即**正等回车/任意键**且**没点到按钮**；块内左键执行
 *   `PressEnterKey(mesSkip: false, inputsByMouse: true)`（`:818-820`），右键才是
 *   `mesSkip: true`（`:814-816`，本移植映射成双击跳过）；
 * - `IsWaitingEnterKey` 只对 `EnterKey` / `AnyKey` 两种等待为真（`EmueraConsole.cs:324-343`），
 *   `INPUT` / `INPUTS` 的整数、字符串取值等待**不在**其中 —— 原版在等数值时点空白也不提交。
 *
 * ## 移植侧判据（组长取保守口径，见 `.workbuddy/CONTRACT-Q2.md` §2.8）
 * 只有「宿主就绪 + 无覆盖层 + 正等回车/任意键 + 未命中按钮」四件同时成立才补。
 * 三点取舍：
 * - **输入等待不补**：`INPUT` / `INPUTS` 的数值与字符串等待不补空串，避免误提交空值
 *   （原版同样不补，见上）。这一侧不需要单独的「等待类型」入参：[InputBarState.canSkip]
 *   的定义就是「可提交 且 `awaitingAnyKey`」，而 `awaitingAnyKey` 只来自 WAIT 系
 *   （`InputBarState` 类文档「双击跳过只在 WAIT 系生效」，`EmueraConsole.cs:324-343`）⇒
 *   数值/字符串等待、非等待抢跑（PRERUN）、宿主停转（DISABLED）三种情形下 [canSkip] 均为
 *   false，天然被本策略挡掉。
 * - **覆盖层与加载期不补**：加载覆盖层与失败覆盖层盖住控制台时点击不该穿透（DEV-03 同源
 *   问题），由调用方把两者合成 [shouldSubmitEnter] 的 `overlayVisible`；装载期 VM 还没起，
 *   [InputBarState.canSkip] 也会因 `Submit.DISABLED` 为 false，两闸互为兜底。
 * - **命中按钮不补**：命中路径原样把按钮文本交给 `GameHost.submit`，本策略不参与，
 *   也不改变按钮命中几何（`CanvasConsoleView.tap` 的返回值不变）。
 *
 * 双击跳过**不经过本策略**：它由装配层的 `onDoubleTap` 按同一个 [InputBarState.canSkip]
 * 闸门独立决定，本对象没有手势入参，也不产 skip。
 *
 * TODO(verify)：原版在「未贴底」时只回贴底、这一击**不**回车（`:801-805` 先置
 * `flag`，`:806` 块内的三个分支都在 `if (!flag)` 里）。本移植的 `CanvasConsoleView.tap`
 * 在内部贴底后只返回命中文本，调用方**拿不到**「刚才是否未贴底」；差的是滚动着看历史时
 * 点空白会多补一次回车。修法需要 `tap` 侧多返回一个状态位，属 `render-canvas` 文件面
 * （不在本路），见交付回执。
 */
object BlankTapPolicy {

    /**
     * 一次未命中按钮的点击是否要补一条空回车（`mesSkip = false`，等价原版
     * `PressEnterKey(mesSkip: false, inputsByMouse: true)`，`MainWindow.cs:818-820`）。
     *
     * @param hasHost 宿主存在（`GameHost` 已挂上且 VM 线程在跑）；false 时不补
     * @param overlayVisible 壳层覆盖层可见（加载中或启动失败）；true 时不补
     * @param canSkip 当前是否在等回车/任意键（[InputBarState.canSkip]）；false 时不补 ——
     *   数值/字符串取值等待、非等待抢跑、宿主停转都落在这里
     * @param hitButton 本次点击是否命中了行内按钮；true 时走既有按钮提交路径，不补
     * @return true 表示调用方应提交一条空文本、`mesSkip` 为 false 的输入
     */
    fun shouldSubmitEnter(
        hasHost: Boolean,
        overlayVisible: Boolean,
        canSkip: Boolean,
        hitButton: Boolean,
    ): Boolean = hasHost && !overlayVisible && canSkip && !hitButton
}
