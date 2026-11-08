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
 * ## 移植侧判据（取保守口径，内部契约未随公开仓发布）
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
 *   也不改变按钮命中几何（`CanvasConsoleView.tap` 的 `hit` 字段口径不变）。
 * - **本击回贴底则只回贴底**：原版 `:799` 先置 `flag = Value != Maximum`，`:801-805` 回贴底，
 *   而 `:806` 块内的三分支全在 `if (!flag)` 里（`:808`）—— 滚动着看历史时点空白
 *   **不**回车。移植侧这个「刚才是否未贴底」由 `CanvasConsoleView.tap` 的
 *   `TapOutcome.snappedToBottom` 带出（同文件 `tap` 的 KDoc「返回值」节）。
 *
 * ## 交什么文本：草稿，不是固定空串
 * 原版 `PressEnterKey`（`MainWindow.cs:922-936`）取的是 `string text = richTextBox1.Text;`
 * （`:926`）—— 也就是输入框里的**当前草稿**，随后 `updateInputs(text)`（`:933`）与
 * `console.PressEnterKey(mesSkip, text, inputsByMouse)`（`:934`）都用这一份。点空白与
 * 点提交按钮在原版是同一条提交路，所以本策略把「交什么」也钉在这里：[textToSubmit]。
 * （`IsWaintingOnePhrase` 的前缀剥离在 `:927-931`，那一路由 `render/input/InputSession`
 * 在提交时处理，本对象不复制。）
 *
 * 双击跳过**不经过本策略**：它由装配层的 `onDoubleTap` 按同一个 [InputBarState.canSkip]
 * 闸门独立决定，本对象没有手势入参，也不产 skip。
 */
object BlankTapPolicy {

    /**
     * 一次未命中按钮的点击是否要补一条回车（`mesSkip = false`，等价原版
     * `PressEnterKey(mesSkip: false, inputsByMouse: true)`，`MainWindow.cs:818-820`）。
     *
     * @param hasHost 宿主存在（`GameHost` 已挂上且 VM 线程在跑）；false 时不补
     * @param overlayVisible 壳层覆盖层可见（加载中或启动失败）；true 时不补
     * @param canSkip 当前是否在等回车/任意键（[InputBarState.canSkip]）；false 时不补 ——
     *   数值/字符串取值等待、非等待抢跑、宿主停转都落在这里
     * @param hitButton 本次点击是否命中了行内按钮；true 时走既有按钮提交路径，不补
     * @param snappedToBottom 本次点击之前视口未贴底、已由点击弹回底部
     *   （`MainWindow.cs:799` 的 `flag`）；true 时这一击只回贴底、不进 `:806` 块
     * @return true 表示调用方应提交 [textToSubmit] 给出的文本、`mesSkip` 为 false 的输入
     */
    fun shouldSubmitEnter(
        hasHost: Boolean,
        overlayVisible: Boolean,
        canSkip: Boolean,
        hitButton: Boolean,
        snappedToBottom: Boolean,
    ): Boolean = hasHost && !overlayVisible && canSkip && !hitButton && !snappedToBottom

    /**
     * 补回车时交付的文本：输入框草稿原样交出（`MainWindow.cs:926` 的
     * `string text = richTextBox1.Text;`，同一个 `text` 一路传到 `:934`）。
     *
     * 草稿为空时结果就是空串 —— 原版空行提交照常有效（取默认值 / 消费一条 WAIT），
     * 见 [InputBarState] 类文档「空文本的去向」。
     *
     * @param draft 输入框当前文本（壳层 `shell.input`）
     */
    fun textToSubmit(draft: String): String = draft
}
