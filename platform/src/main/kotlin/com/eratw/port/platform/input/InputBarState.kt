package com.eratw.port.platform.input

import com.eratw.port.core.host.InputRequest

/**
 * 底部输入条的渲染状态 —— 纯逻辑，不碰 Android API，由 `MainActivity` 只做像素映射。
 *
 * ## 为什么输入条恒在
 * 原版文本框是 MainWindow 的常驻子控件：字段声明见 `MainWindow.cs:112`，对外经 `TextBox`
 * 属性暴露（`MainWindow.cs:210`），每次重算窗口尺寸都把它钉在客户区最下一行
 * （`MainWindow.cs:727-728`）；全仓没有一处 `Visible` / `Enabled` 开关。所以 [visible]
 * 恒为 true：「只在等待输入时露出输入条」是移植侧的偏离，不是原版行为。
 *
 * ## 控件属性（单行 + 长度上限）
 * 原版文本框在设计器里写死两个属性：`MaxLength = 32767`（`MainWindow.cs:1922`）与
 * `Multiline = false`（`MainWindow.cs:1923`）。所以：
 * - 框**不是**多行控件，换行只能在提交文本里出现（例如从剪贴板粘进来），
 *   不能由用户在框内敲出来；[MULTILINE] 因此恒为 false；
 * - 超过 [MAX_TEXT_LENGTH] 的输入被控件截断。安卓侧 `EditText` 默认没有这个上限，
 *   绑定层要自己补 `InputFilter.LengthFilter`（见交付报告的接线项）。
 *
 * 注意：`render` 层 `EmueraTextBox` 的 KDoc 里有一句「原版 RichTextBox 是像素域多行控件
 * （默认 Multiline=true）」，与 `MainWindow.cs:1923` 的实测相反；本层按设计器读数走，
 * 那句属 render 侧待修文档（本波文件面不含 render）。
 *
 * ## 键盘类型
 * 等待整数时切数字键盘：输入最终由 `QueueInputSink` 按 `long.TryParse` 解析
 * （`EmueraConsole.cs:1120-1124`），非数字提交会被丢弃；其余等待（字符串 / 任意键）与未等待
 * 一律文本键盘。原版没有这层映射（WinForms 的输入法由系统给），这是触摸端
 * 为了少切一次输入法的移植增量。
 *
 * ## 是否可抢跑
 * 原版 `PressEnterKey` 的守卫只有 `console != null && !console.IsInProcess`
 * （`MainWindow.cs:922-936`），**不看是否在等待**；没在等待时文本仍交给
 * `console.PressEnterKey`（`:934`），由输入队列先存着（本移植的对应实现见
 * `QueueInputSink` 的 KDoc 第 1 条）。因此非等待期的一次「提交」记为 [Submit.PRERUN]，
 * 照样入队、不丢；等待期为 [Submit.NORMAL]。空文本同样有效：原版空行取默认值
 * （`EmueraConsole.cs:1111-1115`、`:1187-1190`），`QueueInputSink` 也照此处理。
 *
 * ## 空文本的去向（[emptyText]）
 * 只有 `INPUT` 系（[textConsumed] 为真）会解释文本，分三种：
 * - 整数等待 + 有默认值 + 计时器没在跑 ⇒ 取默认值（`EmueraConsole.cs:1119-1124`）；
 * - 整数等待 + 无默认值 ⇒ `long.TryParse("")` 失败、整条提交被丢弃、**仍停在等待**
 *   （`:1125-1128`，控制台不离开 WaitInput）；
 * - 字符串等待 + 有默认值 ⇒ 取 `DefStrValue`（`:1190-1194`）；无默认值 ⇒ 提交空串
 *   （`:1195-1203`，`str == null` 补成 `""` 后照常 `InputString`）。
 * WAIT 系（EnterKey / AnyKey）只消费一条提交、不解释文本（`:1443-1448` 的 Void 支与
 * `QueueInputSink` KDoc 第 5 条），故记 [EmptyText.IGNORED]。
 *
 * ## 双击跳过只在 WAIT 系生效（[canSkip]）
 * 原版「点消息区提交 / 右键跳过」整段挂在 `console.IsWaitingEnterKey` 上
 * （`MainWindow.cs:806`），而 `IsWaitingEnterKey` 只对 `EnterKey` / `AnyKey` 两种等待为真
 * （`EmueraConsole.cs:324-343`）。`INPUT` / `INPUTS` 的取值等待**不在**其中 —— 在等整数或
 * 字符串时双击不排队、也不吞输入。这是 [canSkip] 的唯一判据；`Esc`（取消）不受此限，
 * 它走 `MainWindow.cs:1415-1422` 的独立分支（见 [planFor]）。
 *
 * ## 不自动提交
 * 宏预填只有 `Text =` 与 `SelectionStart =` 两句（`MainWindow.cs:507-512`），没有
 * `PressEnterKey`；`docs/golden-runbook.md:106`、`:125` 同口径。故 [autoSubmit] 恒为
 * false，提交只由用户按「提交」或输入法回车触发。原版 `richTextBox1_TextChanged` 在
 * ONEINPUT / WAIT 系上会直接提交（`MainWindow.cs:1313-1332`），本移植的输入框没有接
 * 这条自动路径，见 TODO(verify)。
 *
 * TODO(verify)：`InputSession.onTextChanged`（`render/input/InputSession.kt:225-247`）
 * 已建模单键/任意键的「打字即提交」，但 `MainActivity` 直接走 `GameHost`，未把
 * `EditText` 的文本变化接到它上面；本波契约明确「不自动提交」，故暂不接线。
 */
data class InputBarState(
    /** 输入条是否占位可见：恒 true（原版文本框常驻，`MainWindow.cs:112`、`:727-728`）。 */
    val visible: Boolean,
    /** 软键盘类型；只区分「整数输入」与「其它」。 */
    val keyboard: Keyboard,
    /** 一次提交的语义：禁用 / 正常等待 / 非等待抢跑。 */
    val submit: Submit,
    /** 文本框变化是否触发提交：恒 false（`MainWindow.cs:507-512`；契约明确不自动提交）。 */
    val autoSubmit: Boolean,
    /** 是否在 WAIT 系（`EnterKey` / `AnyKey`）等待；双击跳过的闸门（`EmueraConsole.cs:324-343`）。 */
    val awaitingAnyKey: Boolean = false,
    /** 本次等待是否解释文本：`INPUT` / `INPUTS` 为 true，WAIT 系与未等待为 false。 */
    val textConsumed: Boolean = true,
    /** 空文本提交的去向，见类文档「空文本的去向」。 */
    val emptyText: EmptyText = EmptyText.LITERAL,
) {

    /** 是否接受一次提交（空文本也算，理由见类文档）。 */
    val canSubmit: Boolean
        get() = submit != Submit.DISABLED

    /** 当前提交是否属于「核心层没在等、先入队」的抢跑。 */
    val isPrerun: Boolean
        get() = submit == Submit.PRERUN

    /**
     * 双击（右键等价物）能否跳过消息：只在 WAIT 系等待时成立，理由见类文档
     * 「双击跳过只在 WAIT 系生效」。宿主不存在 / VM 已停时同样不可用（与 [canSubmit] 同闸，
     * `MainWindow.cs:924`）。
     */
    val canSkip: Boolean
        get() = submit != Submit.DISABLED && awaitingAnyKey

    /**
     * 输入条上「±」按钮是否出现：只有 [Keyboard.SIGNED_NUMBER]（整数等待）时成立。
     *
     * 原版整数取值直接交给 `long.TryParse`（`EmueraConsole.cs:1125`），负数合法；
     * 而 Android 的数字键盘（`KeyboardType.Number`）**没有负号键**，所以负号只能由壳层补一枚
     * 显式开关 —— 不把键盘降级成文本键盘（那样每敲一位数字都要多切一次输入法）。
     */
    val showsSignToggle: Boolean
        get() = keyboard == Keyboard.SIGNED_NUMBER

    /**
     * 「±」按下去之后的文本：非 [Keyboard.SIGNED_NUMBER] 时**原样返回**（字符串等待里框内
     * 已经粘进来的负号一个字都不动），否则按 [toggleSign] 切换前导负号。
     */
    fun withSignToggled(text: String): String =
        if (showsSignToggle) toggleSign(text) else text

    /**
     * 一次提交之后，输入框（= 核心层的 `Window.TextBox`，`MainWindow.cs:210`）该留下什么。
     *
     * 原版只有一条提交路 `PressEnterKey`，守卫 `!console.IsInProcess`（`MainWindow.cs:922`）：
     * - 核心层在等输入（[Submit.NORMAL]）⇒ `updateInputs(text)` 把 `richTextBox1` 清空
     *   （`:933` → `:938-967`）再交给控制台，脚本随后读 `GETTEXTBOX` 得到空串 ⇒ `""`；
     * - 核心层正在跑（[Submit.PRERUN]）⇒ 原版**提交不了**，玩家敲的字留在框里供脚本
     *   轮询 `GETTEXTBOX`（`TITLE.ERB:168` 的倒计时退出、`qol_MAP.ERB:1132` 的
     *   AWAIT_UI）⇒ 原样保留 [text]。
     *
     * `null` = 不要写框（[Submit.DISABLED] 没有提交发生；[Submit.PRERUN] 而文本为空时，
     * 原版什么都不会改 —— 框里可能是脚本刚用 `SETTEXTBOX` 写的状态串（`qol_MAP.ERB:627`
     * 的 `SETTEXTBOX MOUSEB()`），清掉就把这条通道断了）。
     */
    fun textBoxAfterSubmit(text: String): String? = when {
        submit == Submit.DISABLED -> null
        isPrerun -> text.ifEmpty { null }
        else -> ""
    }

    /** 软键盘口径（到 Android `InputType` 的映射留在绑定层）。 */
    enum class Keyboard {
        /** 文本键盘：字符串等待、任意键等待、未等待。 */
        TEXT,

        /** 数字键盘（带正负号）：`InputRequest.InputType.INT`。 */
        SIGNED_NUMBER,
    }

    /** 一次「提交」（按钮或输入法回车）的去向。 */
    enum class Submit {
        /** 宿主不存在或 VM 线程已停：按钮置灰，提交没有去处。 */
        DISABLED,

        /** 核心层正在等：正常取值。 */
        NORMAL,

        /** 核心层没在等：先入队，等下一次等待取走（`QueueInputSink` KDoc 第 1 条）。 */
        PRERUN,
    }

    /** 空文本提交的去向，逐条对应类文档「空文本的去向」。 */
    enum class EmptyText {
        /** 取默认值：`inputReq.HasDefValue && !IsRunningTimer`（`EmueraConsole.cs:1120-1124`、`:1191-1194`）。 */
        DEFAULT_VALUE,

        /** 作为空串提交：字符串等待无默认值时（`EmueraConsole.cs:1195-1203`）。 */
        LITERAL,

        /** 整条丢弃、继续等待：整数等待无默认值（`EmueraConsole.cs:1125-1128`）。 */
        DISCARDED,

        /** 文本被忽略：WAIT 系只消费一条提交（`EmueraConsole.cs:1443-1448`）。 */
        IGNORED,

        /** 未等待（抢跑）：由之后真正取走它的那次等待决定（`QueueInputSink` KDoc 第 1 条）。 */
        UNDETERMINED,
    }

    /**
     * 一次提交的来源。四值逐个对应原版的一处实参组合，见 [planFor]。
     */
    enum class SubmitSource {
        /** 输入法回车 / 键盘 `Keys.Return`（`MainWindow.cs:1407-1414`）。 */
        ENTER_KEY,

        /** 触屏「提交」按钮，按鼠标左键那一路（`MainWindow.cs:818-820`）。 */
        TOUCH_SUBMIT,

        /** `Esc` 取消（`MainWindow.cs:1415-1422`）；先 `KillMacro = true` 再带 `mesSkip` 提交。 */
        CANCEL_ESCAPE,

        /** 双击跳过，按鼠标右键那一路（`MainWindow.cs:814-816`）。 */
        DOUBLE_TAP_SKIP,
    }

    /**
     * 一次提交的实参组合（`MainWindow.cs:934` 的三个实参里与平台有关的两个）。
     *
     * @property mesSkip 是否跳过消息（`EmueraConsole.cs:1371-1373` 落到 `MesSkip`）
     * @property inputsByMouse 是否按「鼠标改过的文本」提交（`MainWindow.cs:934` 第三参）
     */
    data class SubmitPlan(
        val mesSkip: Boolean,
        val inputsByMouse: Boolean,
    ) {
        /**
         * 提交文本是否按 `spliter` 切成多条：鼠标那一路整条不切
         * （`EmueraConsole.cs:1406-1409` 只留 `array = new string[1] { input }`），
         * 键盘那一路按四个元素切（`:1429`，切分符表见 `:146`）。
         * 实现在 render 层的 `InputSession.splitPieces`，本层只给判据。
         */
        val splitsLines: Boolean
            get() = !inputsByMouse
    }

    companion object {

        /** 框是否多行：false（`MainWindow.cs:1923` 的 `Multiline = false`）。 */
        const val MULTILINE: Boolean = false

        /** 框内文本上限：32767（`MainWindow.cs:1922` 的 `MaxLength = 32767`）。 */
        const val MAX_TEXT_LENGTH: Int = 32767

        /**
         * 由宿主快照推出输入条状态。
         *
         * @param hasHost 是否已有 `GameHost`（产物缺失的演示屏为 false）
         * @param running VM 线程是否还活着（`GameHost.isRunning`）
         * @param pending 当前等待的输入请求；null = 没在等 `waitInput`
         * @param awaitingAnyKey 是否在等任意键（WAIT 系）
         */
        fun of(
            hasHost: Boolean,
            running: Boolean,
            pending: InputRequest?,
            awaitingAnyKey: Boolean,
        ): InputBarState {
            val awaiting = pending != null || awaitingAnyKey
            val submit = when {
                !hasHost || !running -> Submit.DISABLED
                awaiting -> Submit.NORMAL
                else -> Submit.PRERUN
            }
            val keyboard = if (pending?.type == InputRequest.InputType.INT) {
                Keyboard.SIGNED_NUMBER
            } else {
                Keyboard.TEXT
            }
            return InputBarState(
                visible = true,
                keyboard = keyboard,
                submit = submit,
                autoSubmit = false,
                awaitingAnyKey = awaitingAnyKey,
                textConsumed = pending != null && !awaitingAnyKey,
                emptyText = emptyTextOf(pending, awaitingAnyKey),
            )
        }

        /**
         * 一次提交来源的实参组合。四值全有原版行：
         * - [SubmitSource.ENTER_KEY]：`PressEnterKey(mesSkip: false, inputsByMouse: false)`
         *   （`MainWindow.cs:1412`，`e.SuppressKeyPress` 在 :1409）；
         * - [SubmitSource.TOUCH_SUBMIT]：鼠标左键点消息区，`mesSkip: false, inputsByMouse: true`
         *   （`MainWindow.cs:818-820`；触屏提交是「用指针把文本交出去」，按这一路）；
         * - [SubmitSource.CANCEL_ESCAPE]：`Keys.Escape`，先 `console.KillMacro = true`（:1418）
         *   再 `mesSkip: true, inputsByMouse: false`（:1421）；
         * - [SubmitSource.DOUBLE_TAP_SKIP]：鼠标右键，`mesSkip: true, inputsByMouse: true`（:816）。
         *
         * 四路都**不**检查文本是否为空（`:922-936` 只守 `console != null && !IsInProcess`），
         * 所以空框按跳过同样是一条有效提交。
         */
        fun planFor(source: SubmitSource): SubmitPlan = when (source) {
            SubmitSource.ENTER_KEY -> SubmitPlan(mesSkip = false, inputsByMouse = false)
            SubmitSource.TOUCH_SUBMIT -> SubmitPlan(mesSkip = false, inputsByMouse = true)
            SubmitSource.CANCEL_ESCAPE -> SubmitPlan(mesSkip = true, inputsByMouse = false)
            SubmitSource.DOUBLE_TAP_SKIP -> SubmitPlan(mesSkip = true, inputsByMouse = true)
        }

        /**
         * 「±」按钮的文本变换：在最前面切换/去掉一个负号；**不解析数字**、也不过滤字符。
         *
         * - 空串 ⇒ `"-"`（先落一个负号，再敲数字）；
         * - 已有前导 `-` ⇒ 去掉它（`"-0"` ⇒ `"0"`、`"-1.5"` ⇒ `"1.5"`）；
         * - 其余 ⇒ 前面补 `-`（`"20"` ⇒ `"-20"`；`"1-2"` ⇒ `"-1-2"`，内层负号一个不动）。
         *
         * 只碰前导符号的原因：原版把框内文本原样交给 `long.TryParse`
         * （`EmueraConsole.cs:1125`），`"-0"`、`"-1.5"` 这类写法要按用户敲的样子交出去、
         * 不能被归一成别的数字；剪贴板里带进来的负号（包括不在开头的那种）也一律保留 ——
         * 本函数只做前缀增删，粘进来的文本内容逐字不变。
         */
        fun toggleSign(text: String): String =
            if (text.startsWith("-")) text.substring(1) else "-$text"

        /** [emptyText] 的推导，逐条见类文档「空文本的去向」。 */
        private fun emptyTextOf(pending: InputRequest?, awaitingAnyKey: Boolean): EmptyText = when {
            pending == null && awaitingAnyKey -> EmptyText.IGNORED
            pending == null -> EmptyText.UNDETERMINED
            // `!IsRunningTimer`（EmueraConsole.cs:1120、:1191、:1206）≈ 没在限时；
            // 超时那一瞬由核心层单独处理，本层不区分。
            pending.hasDef && pending.timeLimit <= 0L -> EmptyText.DEFAULT_VALUE
            pending.type == InputRequest.InputType.INT -> EmptyText.DISCARDED
            else -> EmptyText.LITERAL
        }
    }
}
