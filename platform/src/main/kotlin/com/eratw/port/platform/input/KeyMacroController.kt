package com.eratw.port.platform.input

import com.eratw.port.render.input.InputSession
import com.eratw.port.render.input.MacroKeys
import com.eratw.port.render.input.Submission

/**
 * 键盘宏平台控制器（INPUT-BIND）—— 把「功能键 / 换组键」接到既有的渲染层输入模型上。
 *
 * 本类**不重写**任何宏规则：按键分派全部走 [InputSession.onMacroKey]
 * （它内部调 `MacroKeys.dispatch` 并查 [com.eratw.port.render.input.KeyMacroTable]），
 * 组切换与记录语义原样沿用 [MacroKeys] 的 KDoc 与其引用的 C# 行号。本类只做两件事：
 * 1. 把 [MacroKeys.Decision] 连同绑定层需要的「组名标签」与「是否吞键」收成一个 [Outcome]；
 * 2. 提供**唯一**的显式提交口 [submit]，钉住「宏只预填、提交必须手按」这条判据。
 *
 * ## F1-F12 只预填，不自动回车
 * 原版无修饰 F1-F12 的整段只有两句：`richTextBox1.Text = KeyMacro.GetMacro(...)`
 * 与 `SelectionStart = Text.Length`（`MainWindow.cs:507-512`），全流程没有 `PressEnterKey`；
 * 板上同口径（`docs/golden-runbook.md:106`「不会自动回车提交」、`:125`「回车仍需手按」）。
 * 对照：`HOTKEY.ERB` 命中那一路是**会**自动提交的（`MainWindow.cs:1362-1374`），
 * 两种「预填」的差别只在最后一句，故本类不把 [submit] 混进 [onKey]。
 *
 * ## 按键口径（全部沿用 MacroKeys / InputSession）
 * - 等待原始键（`console.IsWaitingPrimitive`）时整段不响应（`MainWindow.cs:385-388`）；
 * - `Config.UseKeyMacro` 关掉时不响应（`MainWindow.cs:488-491`，默认开见 `ConfigData.cs:53`）；
 * - Shift+F1-F12 记录框内文本，空框不覆盖旧宏（`MainWindow.cs:499-506`）；
 * - 无修饰 F1-F12 预填，宏为空即清框（`MainWindow.cs:507-512`）；
 * - Ctrl+主键盘/小键盘数字换组（`MainWindow.cs:514-528`），该键**不吞**（`:527` 之后走 `break`）；
 * - Alt、Ctrl+Shift 等组合三个旗标都不成立，什么都不做（`MainWindow.cs:493-495`、`:530`）。
 *
 * [onKey] 接收的是 **WinForms 虚拟键码**（[MacroKeys.VK_F1] 等）。Android 的 `KeyEvent`
 * 键码与 WinForms 不重合（例如 Android 的 `KEYCODE_CTRL_LEFT` 数值落在 112..123，若原样透传
 * 会被当成 F2），换算在 [AndroidInputAdapter] 完成，规则层不掺 Android 常量。
 *
 * ## 四条提交路径（实参各不相同，别合并成一个 `submit`）
 * 平台层必须把原版四处 `PressEnterKey` 调用的实参组合区分开，因为 `inputsByMouse`
 * 决定提交文本是否按 `spliter` 切成多条（`EmueraConsole.cs:1406-1409` 对 `:1429`）：
 * - [submit]：触屏「提交」按钮 = 鼠标左键点消息区，`mesSkip = false, inputsByMouse = true`
 *   （`MainWindow.cs:818-820`；本波契约口径，因为它是「用指针把文本交出去」）；
 *   [requestSkip] 之后这一次升级为 `mesSkip = true`（跳过标志搭车，见下一节）；
 * - [submitEnterKey]：输入法回车 / `Keys.Return`，`mesSkip = false, inputsByMouse = false`
 *   （`:1407-1414`）。与 [submit] 的唯一差别就是 `inputsByMouse`：多行粘贴文本在这里会
 *   被切成多条，在触屏按钮那一路保持整条；
 * - [submitCancel]：`Esc` 取消，先 `console.KillMacro = true`（`:1418`）再
 *   `mesSkip = true, inputsByMouse = false`（`:1421`）。**取消也是提交**，不是清空输入框；
 *   原版没有「只清空不提交」的键位；
 * - [submitSkip]：双击跳过 = 鼠标右键，`mesSkip = true, inputsByMouse = true`（`:814-816`）。
 *   与 `GameHost.submitSkip(text)` 同语义（文本入队 + `keySkip`），区别只在入口：
 *   `GameHost` 直接推 `QueueInputSink`，这里走 [InputSession]，因此多出原版的
 *   「入历史 + 清框」两步副作用（`MainWindow.cs:926-934`）。
 *
 * ## 跳过手势与下一次显式提交的装配（M10-PLAY 收口 M7 钉桩①）
 * 原版右键那一路是**当场提交**：`PressEnterKey(mesSkip: true, ...)`（`MainWindow.cs:814-816`），
 * `EmueraConsole.PressEnterKey` 入口先 `MesSkip = keySkip`（`EmueraConsole.cs:1371-1373`），
 * 跳过态随本次回车进入输入循环，并在下一个取值输入前结束（`:1451-1456` 的 while 之后 `MesSkip = false`）。
 * 本移植的双击手势按契约**不提交**（见 `TouchGestureController` 类文档「触屏不提交」），
 * 所以标志必须**搭上下一次显式提交**：绑定层收到 [TouchGestureController.Action.Skip] 后调
 * [requestSkip]，随后的 [submit] / [submitEnterKey] 自动带 `mesSkip = true`（一次性，送出即清）。
 * [submitSkip] / [submitCancel] 本身就带 `mesSkip = true` 提交，调用时把待标记**顺带清掉**
 * （同一条消息不能被两个跳过标志重复解释）。等待结束后绑定层要调 [clearSkipRequest]
 * 作废未消费的标记 —— 原版没有「悬置到下下次回车」的形态（右键当场就交了），留着
 * 只会把用户后来想读的那条也跳掉。
 *
 * 跳过的生效范围由 [InputBarState.canSkip] 判（只在 WAIT 系等待，`MainWindow.cs:806` /
 * `EmueraConsole.cs:324-343`）；本类不重复这一闸，绑定层看状态决定调不调 [submitSkip] /
 * [requestSkip]。守卫（`console != null`、`!IsInProcess`）全在 [InputSession.pressEnterKey] 内；
 * 提交被守卫挡掉（返回 null）时待标记**不消费**，留给下一次提交。
 *
 * ## 组名标签
 * 换组时原版会显示组名并起一个 200ms × 11 拍的计时器（`MainWindow.cs:1590-1611`）。
 * [onKey] 除了照旧把标签文本放进 [Outcome.groupLabel]，还会把它交给 [groupLabel]，
 * 绑定层每 200ms 调一次 [MacroGroupLabel.onTick] 即可，不必自己数拍。
 *
 * 宏文件读写、`Config.UseKeyMacro` 的配置源都属绑定层/宿主。
 *
 * @param session 渲染层的输入会话（文本框 + 历史 + 宏表 + 提交流水）。默认新建一份，
 *   单测各建各的，互不串味（AGENTS.md 铁律 4：禁静态可变全局）。
 */
class KeyMacroController(val session: InputSession = InputSession()) {

    /** 换组标签的显隐状态（`setNewMacroGroup` 的计时器，`MainWindow.cs:1590-1611`）。 */
    val groupLabel: MacroGroupLabel = MacroGroupLabel()

    /**
     * 跳过手势的待消费标志（装配判据见类文档「跳过手势与下一次显式提交的装配」）。
     * 只在本类的显式提交口消费；非线程安全约定与 [session] 相同（绑定层单线程访问）。
     */
    private var skipRequested = false

    /** 是否有待搭上下一次显式提交的跳过标志（绑定层画「跳过待生效」提示用）。 */
    val hasSkipRequest: Boolean
        get() = skipRequested

    /**
     * 记录一次跳过手势（[TouchGestureController.Action.Skip]）：下一次 [submit] /
     * [submitEnterKey] 带 `mesSkip = true`（`EmueraConsole.cs:1371-1373` 的 `keySkip` 实参，
     * 经本移植的「手势不提交、标志搭车」装配模型交到核心层）。重复调用幂等。
     */
    fun requestSkip() {
        skipRequested = true
    }

    /** 作废未消费的跳过标志（等待结束、手势撤销时由绑定层调用）。 */
    fun clearSkipRequest() {
        skipRequested = false
    }

    /** 清除待消费的跳过标志（只在提交真正送出后调用；被守卫挡掉不许清）。 */
    private fun consumeSkipRequest() {
        skipRequested = false
    }

    /**
     * 一次按键的处置结果。
     *
     * @property decision 沿用 [MacroKeys] 的四态结论；[onKey] 已把它落到会话上
     * @property groupLabel 换组时给绑定层显示的组名文本（`setNewMacroGroup` 的标签，
     *   `MainWindow.cs:1601-1611`，文本取自 [com.eratw.port.render.input.KeyMacroTable.groupName]）；
     *   其余结论为 null
     * @property consumed 是否吞掉该键：预填/记录原版 `return true`（`MainWindow.cs:505`、`:511`），
     *   换组走 `break` 不吞（`:527`、`:530`），不响应也不吞
     */
    data class Outcome(
        val decision: MacroKeys.Decision,
        val groupLabel: String?,
        val consumed: Boolean,
    )

    /**
     * 处理一次功能键/换组键按下。
     *
     * 组合键判定、宏表查询与「预填/记录/换组」的落点全在 [InputSession.onMacroKey]；
     * 本函数只补上绑定层要的组名标签与吞键标志。
     *
     * @param keyCode WinForms 虚拟键码（[MacroKeys.VK_F1]..[MacroKeys.VK_F12] 或数字键）
     * @param modifier 已折成三旗标的修饰键（Android 的 metaState 折叠见 [AndroidInputAdapter]）
     * @param waitingPrimitive `console.IsWaitingPrimitive`（`MainWindow.cs:385`）
     * @param useKeyMacro `Config.UseKeyMacro`（`MainWindow.cs:488`）
     */
    fun onKey(
        keyCode: Int,
        modifier: MacroKeys.Modifier,
        waitingPrimitive: Boolean = false,
        useKeyMacro: Boolean = true,
    ): Outcome {
        val decision = session.onMacroKey(
            keyCode = keyCode,
            modifier = modifier,
            waitingPrimitive = waitingPrimitive,
            useKeyMacro = useKeyMacro,
        )
        val groupLabel = (decision as? MacroKeys.Decision.SwitchGroup)
            ?.let { session.macroTable.groupName(it.group) }
        if (groupLabel != null) {
            this.groupLabel.show(groupLabel)
        }
        val consumed = when (decision) {
            MacroKeys.Decision.Ignored -> false
            is MacroKeys.Decision.SwitchGroup -> decision.suppressKey
            else -> true
        }
        return Outcome(decision = decision, groupLabel = groupLabel, consumed = consumed)
    }

    /**
     * 显式提交：`InputSession.pressEnterKey(mesSkip, inputsByMouse = true)`
     * （`MainWindow.cs:922-936` 的提交口；实参按鼠标左键那一路，见类文档）。
     *
     * [requestSkip] 之后的一次提交自动带 `mesSkip = true`（跳过标志搭上下一次显式提交，
     * 装配判据见类文档「跳过手势与下一次显式提交的装配」）；没有待标记时维持
     * 鼠标左键那一路的 `mesSkip = false`。
     *
     * @return 交给核心层的那一条；被 `consolePresent` / `inProcess` 守卫挡掉时为 null
     *   （此时待标记**不消费**，留给下一次提交）
     */
    fun submit(): Submission? {
        val submission = session.pressEnterKey(mesSkip = skipRequested, inputsByMouse = true)
        if (submission != null) consumeSkipRequest()
        return submission
    }

    /**
     * 输入法回车：`InputSession.pressEnterKey(mesSkip, inputsByMouse = false)`，无待标记时
     * 即 `PressEnterKey(mesSkip = false, inputsByMouse = false)`（`MainWindow.cs:1407-1414`）。
     *
     * 与 [submit] 的差别在 `inputsByMouse`：键盘那一路的提交文本会按 `spliter` 切成多条
     * （`EmueraConsole.cs:1429`），指针那一路不分（`:1406-1409`）。[requestSkip] 之后
     * 同样搭车带 `mesSkip = true`，送出即清。
     */
    fun submitEnterKey(): Submission? {
        val submission = session.pressEnterKey(mesSkip = skipRequested, inputsByMouse = false)
        if (submission != null) consumeSkipRequest()
        return submission
    }

    /**
     * `Esc` 取消：`InputSession.onEscapeKeyPressed()`，即
     * `PressEnterKey(mesSkip = true, inputsByMouse = false)`（`MainWindow.cs:1415-1422`）。
     *
     * 原版在同一支里还置了 `console.KillMacro = true`（`:1418`）；宏回放中止属核心层，
     * 本波不建模（`InputSession` 类文档的 TODO）。取消**照样把框内文本交出去**，
     * 并非只清空输入框。这条提交本身就带跳过，先行的 [requestSkip] 顺带清掉
     * （送出与挡掉都清：挡掉时原版 `KillMacro` 也已生效，跳过没有「下次」可搭）。
     */
    fun submitCancel(): Submission? {
        consumeSkipRequest()
        return session.onEscapeKeyPressed()
    }

    /**
     * 双击 / 右键跳过：`PressEnterKey(mesSkip = true, inputsByMouse = ...)`
     * （`MainWindow.cs:814-816`）。文本取自框内当前值 —— 与 `GameHost.submitSkip(text)`
     * 同一语义（携带框内文本 + `keySkip`），空框也是一条有效提交
     * （`:922-936` 没有空值守卫；WAIT 系只消费一条提交，见 `QueueInputSink` KDoc 第 5 条）。
     *
     * 这一路是原版的**当场提交**形态（右键即回车），与 [requestSkip] 的
     * 「搭下一次显式提交」形态二选一；调用本方法时先行的 [requestSkip] 顺带清掉
     * （同一次跳过不重复计）。
     *
     * @param inputsByMouse 指针手势传 true（右键那一路，`:816`）；若将来有键盘
     *   `Shift+Delete` 之类的跳过键位再传 false（原版没有该键位）
     */
    fun submitSkip(inputsByMouse: Boolean = true): Submission? {
        consumeSkipRequest()
        return session.pressEnterKey(mesSkip = true, inputsByMouse = inputsByMouse)
    }

    /**
     * 右键菜单里的「宏」子项（`MainWindow.cs:1565-1576`）：与功能键那一路**守卫不同** ——
     * 菜单要求 `console != null`（:1567；本层即 [InputSession.consolePresent]）、
     * `!console.IsInProcess`（:1567）且宏非空（`if (macro.Length > 0)` :1570）才赋文本；
     * 功能键那一路是无条件赋值（空宏即清框，`:509`）。判定在
     * [MacroKeys.prefillFromMenu]，本函数只负责取表与落框。
     *
     * @param fkey 0..11（菜单项 `ShortcutKeys - 112`，:1569）
     * @return 实际结论；[MacroKeys.Decision.Ignored] 表示菜单项该是灰的/没有动作
     */
    fun prefillFromMenu(fkey: Int, useKeyMacro: Boolean = true): MacroKeys.Decision {
        // 原版菜单处理器第一道就是 console != null（MainWindow.cs:1567）；没有控制台时
        // 连宏都不查、框也不动。InputSession.consolePresent 就是这一个条件（:924 的同源字段）。
        if (!session.consolePresent) {
            return MacroKeys.Decision.Ignored
        }
        val macro = session.macroTable.get(fkey, session.macroGroup)
        val decision = MacroKeys.prefillFromMenu(
            macro = macro,
            inProcess = session.inProcess,
            useKeyMacro = useKeyMacro,
        )
        session.applyMacro(decision)
        return decision
    }
}
