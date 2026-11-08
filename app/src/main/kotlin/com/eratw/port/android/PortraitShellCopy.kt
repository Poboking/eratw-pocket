package com.eratw.port.android

/**
 * 壳层内联 UI（竖屏 HUD / 指令盘 / 立绘舞台 + 横屏壳与两版加载页、失败屏）的玩家面文案表
 * （三语，照 [UpdateCopy] 的表形态与三条硬规矩）。
 *
 * ## 收口范围
 *
 * 只收「壳层自己画出来的短句」：HUD 徽章与按钮、指令盘上下文行与开合钮、舞台标题条、
 * 加载页、脏退出报告卡、失败屏的按钮与说明行。**不收**三类（同 [UpdateNotifyCopyTest]
 * 对「匹配上游原话的锚点」的豁免口径）：
 *
 * 1. **游戏原文标签**：HUD 数值条/抽屉 chips 的标签（体力、好感度、今日运势……）是
 *    `HudLineParser` 解析屏行的**匹配键**，同时是 [PortraitHud] 条色判据（`barColor`），
 *    搬进三语表翻译就会解析失配 + 配色失配，原样留在数据层；
 * 2. **规格占位字样**：名字解析不出时显示的「era」（竖屏设计稿 §3 数据来源一览明文），
 *    是品牌式占位不是语言文案；
 * 3. **失败屏标题与说明**（`failureScreenTitle` / `failureScreenNote`，UiFault.kt）：
 *    所有权不在本表车道，且 [UiFaultTest] 逐字钉死中文原文。
 *
 * ## 三条硬规矩（照 [UpdateCopy] 的 KDoc，此处不重复展开）
 *
 * 1. 取语言只有一条路：`PortraitShellCopy.of(UiPrefs.settingsLanguage(context))`；
 * 2. 中文逐字搬运：[ZH] 的每一个值与收口前的内联字面量逐字相同（含全角括号、全角空格、
 *    省略号字符、`▾`/`▸`/`▲`/`▼` 箭头与「执行中:」的半角冒号）；
 * 3. 无状态 / 非 Composable 的取用走**中文兼容口**：[REPEATED_FAILURE_NOTE] 在 ShellUi.kt
 *    仍以原名导出（[ShellUiFailureActionsTest] 按名引用），值代 [ZH] 档——照
 *    [UpdateNotifyCopyTest]「兼容口走的必须是中文那一档的表」的口径。
 *
 * ## 句式里的 `{0}`
 *
 * 带读数的句子存成模板，由 [fill] 一次替换（与 [UpdateCopy.fill] / `SettingsStrings.fill`
 * 同口径：花括号走字符类而不是反斜杠转义）。判读结构留在壳层，本表只放词。
 *
 * @property lang 这一组对应的设置页语言
 */
data class PortraitShellCopy(
    val lang: SettingsLanguage,

    // ---------------------------------------------------------------- 指令盘（CommandTray）
    /** 空候选占位行（原 CommandTray.TRAY_EMPTY_TEXT）。 */
    val trayEmptyLine: String,
    /** 收起态上下文行：{0} 执行中指令原文。 */
    val trayExecutingFormat: String,
    /** 收起态上下文行：{0} 上回指令原文（尖括号是原样外壳）。 */
    val trayLastCommandFormat: String,
    /** 收起态整行开关。 */
    val trayExpandCommand: String,
    /** 展开态整行开关。 */
    val trayCollapseCommand: String,

    // ---------------------------------------------------------------- HUD（PortraitHud）
    /** 身份行三枚图标钮：输入开关。 */
    val hudKeyButton: String,
    /** 身份行三枚图标钮：指令盘开关。 */
    val hudTrayButton: String,
    /** 身份行三枚图标钮：设置入口。 */
    val hudSettingsButton: String,
    /** 名字行所持金徽章：{0} 千分位读数（格式化在调用侧）。 */
    val hudMoneyFormat: String,
    /** 抽屉入口：展开态。 */
    val hudDetailOpen: String,
    /** 抽屉入口：收起态。 */
    val hudDetailClosed: String,
    /** 抽屉标题（主角侧）：{0} 当前主体名。 */
    val drawerProtagonistTitleFormat: String,
    /** 抽屉标题（角色侧）：{0} 当前主体名。 */
    val drawerCharacterTitleFormat: String,
    /** 抽屉里主角专属详情的备注行。 */
    val drawerM2Note: String,
    /** 抽屉锚点按钮：翻到正文 Status/Palam 区。 */
    val drawerScrollToSections: String,
    /** 抽屉锚点按钮：回到最新。 */
    val drawerScrollToLatest: String,

    // ---------------------------------------------------------------- 立绘舞台（SpriteStage）
    /** 舞台标题（有张数）：{0} 张数。 */
    val stageTitleFormat: String,
    /** 舞台标题（拿不到张数的退化形态，不编数字）。 */
    val stageTitleNoCount: String,
    /** 舞台折叠开关：展开。 */
    val stageExpand: String,
    /** 舞台折叠开关：收起。 */
    val stageCollapse: String,
    /** 展开态的一行位置提示。 */
    val stageHint: String,

    // ---------------------------------------------------------------- 输入与右栏（ShellUi 横屏壳）
    /** ShellState 初始输入提示（运行时按等待类型被 InputHint 覆盖，缺省档代 ZH）。 */
    val inputHintDefault: String,
    /** 失败屏「重试已经试过一回还是失败」那句说明的原话（中文兼容口导出）。 */
    val repeatedFailureNote: String,
    /** WAIT 系页面栏底的「继续」脚钮。 */
    val continueButton: String,
    /** 「继续」脚钮的读屏描述。 */
    val continueButtonA11y: String,
    /** 横屏右上角的设置入口。 */
    val settingsButton: String,
    /** 右栏候选计数：{0} 枚数。 */
    val railCountFormat: String,
    /** 宏条组名缺省字。 */
    val macroGroupFallback: String,
    /** 长按提示卡底部的关闭提示。 */
    val tooltipDismissHint: String,
    /** 输入开关的读屏标签：展开态。 */
    val inputToggleExpanded: String,
    /** 输入开关的读屏标签：收起态。 */
    val inputToggleCollapsed: String,
    /** 底边抓手的读屏描述。 */
    val inputKnobA11y: String,
    /** 输入条的提交按钮。 */
    val submitButton: String,
    /** 选项栏开关的读屏标签：展开态。 */
    val railToggleExpanded: String,
    /** 选项栏开关的读屏标签：收起态。 */
    val railToggleCollapsed: String,
    /** 方向锁开关的读屏标签：锁定态。 */
    val orientationLocked: String,
    /** 方向锁开关的读屏标签：未锁定态。 */
    val orientationUnlocked: String,

    // ---------------------------------------------------------------- 通用对话框按钮（Yes/No）
    /** 通用「是」：死循环告警的强制结束（原版 `Dialog.ShowPrompt` 的 Yes）。 */
    val dialogYes: String,
    /** 通用「否」：死循环告警的继续（原版 `Dialog.ShowPrompt` 的 No）。 */
    val dialogNo: String,

    // ---------------------------------------------------------------- 报告卡（脏退出 + 失败屏共用词）
    /** 脏退出报告卡标题。 */
    val dirtyExitTitle: String,
    /** 脏退出报告卡正文第一行。 */
    val dirtyExitBody1: String,
    /** 脏退出报告卡正文第二行。 */
    val dirtyExitBody2: String,
    /** 报告分享按钮（脏退出卡与失败屏共用一个词）。 */
    val shareReport: String,
    /** 报告复制按钮（同上）。 */
    val copyReport: String,
    /** 报告卡关闭按钮。 */
    val ackButton: String,

    // ---------------------------------------------------------------- 加载页（两版共用）
    /** 加载页底部的免费声明（两版样式共用一句，原 FREE_NOTICE）。 */
    val freeNotice: String,
    /** 像素风加载页的字距标题。 */
    val loadingPixelHeadline: String,
    /** 现代风加载页的标题。 */
    val loadingModernHeadline: String,
    /** 加载页缺省进度说明（loadingTitle 为空时）。 */
    val loadingPreparingData: String,

    // ---------------------------------------------------------------- 失败屏（FailureOverlay）
    /** 「少占内存再试」按钮旁的代价说明。 */
    val retryCompatNote: String,
    /** 「重启应用」出口旁的进程说明。 */
    val exitIsRestartNote: String,
    /** 失败屏出口：回到标题。 */
    val backToTitle: String,
    /** 失败屏出口：少占内存再试。 */
    val retryCompatButton: String,
    /** 失败屏出口：重试。 */
    val retryButton: String,
    /** 失败屏出口（重启形态）。 */
    val exitAppRestart: String,
    /** 失败屏出口（结束形态）。 */
    val exitAppQuit: String,
) {

    /**
     * 句式占位符 `{0}` 的单趟替换，与 [UpdateCopy.fill] 同一套实现口径（花括号走字符类，
     * Android 的 ICU 正则会把反斜杠转义写法直接抛掉）。
     */
    fun fill(template: String, vararg args: Any): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言：不许在壳层再造一套语言判定。 */
        fun of(lang: SettingsLanguage): PortraitShellCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：每个值都与收口前的内联字面量逐字相同（中文零漂移的对拍右轴）。 */
        val ZH: PortraitShellCopy = PortraitShellCopy(
            lang = SettingsLanguage.ZH,
            trayEmptyLine = "（本屏没有可点选项）",
            trayExecutingFormat = "执行中: {0}",
            trayLastCommandFormat = "<上回指令:{0}>",
            trayExpandCommand = "更多指令 ▾",
            trayCollapseCommand = "收起到常用指令 ▴",
            hudKeyButton = "键",
            hudTrayButton = "盘",
            hudSettingsButton = "设",
            hudMoneyFormat = "所持金 {0}",
            hudDetailOpen = "▲ 收起状态详情",
            hudDetailClosed = "▼ 状态详情",
            drawerProtagonistTitleFormat = "▼ {0}　主角状态",
            drawerCharacterTitleFormat = "▼ {0}　好感度 / Palam",
            drawerM2Note = "主角专属详情（素质・经验・装备）属 M2",
            drawerScrollToSections = "▲ 翻到 [Status]/[Palam] 区",
            drawerScrollToLatest = "▼ 回到最新",
            stageTitleFormat = "立绘 {0}/10",
            stageTitleNoCount = "立绘",
            stageExpand = "展开 ▸",
            stageCollapse = "收起 ▾",
            stageHint = "立绘在正文流内，可在画面里滚动查看",
            inputHintDefault = "输入后回车提交",
            repeatedFailureNote = "重试已经试过一回，还是失败：再点一次走的还是同一条路。",
            continueButton = "继续 ▶",
            continueButtonA11y = "继续，翻到下一屏",
            settingsButton = "设置",
            railCountFormat = "{0} 项可点",
            macroGroupFallback = "宏",
            tooltipDismissHint = "点任意处关闭",
            inputToggleExpanded = "键盘(已展开)",
            inputToggleCollapsed = "键盘(已收起)",
            inputKnobA11y = "底边抓手",
            submitButton = "提交",
            railToggleExpanded = "选项栏(已展开)",
            railToggleCollapsed = "选项栏(已收起)",
            orientationLocked = "屏幕方向(已锁定)",
            orientationUnlocked = "屏幕方向(未锁定)",
            dirtyExitTitle = "上次异常退出",
            dirtyExitBody1 = "上一次游戏在运行中异常结束（闪退或被系统强制结束）。",
            dirtyExitBody2 = "已生成一份错误报告存在手机里。自动上报没能送出时，用下面的按钮把它发给开发者。",
            shareReport = "分享报告",
            copyReport = "复制报告",
            ackButton = "知道了",
            dialogYes = "是",
            dialogNo = "否",
            freeNotice =
                "本游戏完全免费，仅供学习使用；任何收费售卖均与本项目无关，" +
                    "如遇收费请勿付款，保留凭证并向发布渠道举报。",
            loadingPixelHeadline = "― 正 在 进 入 这 个 世 界 ―",
            loadingModernHeadline = "正在进入这个世界",
            loadingPreparingData = "正在准备游戏数据…",
            retryCompatNote =
                "「少占内存再试」装得更慢、占用更少（本次运行会一直沿用），但也可能还是装不下。",
            exitIsRestartNote =
                "「重启应用」会结束本进程再由系统立刻拉回来：上一局占着的内存只有换一个" +
                    "干净进程才还得回去，本进程里重试多少次都是同一张屏。存档在盘上，不会丢。",
            backToTitle = "回到标题",
            retryCompatButton = "少占内存再试",
            retryButton = "重试",
            exitAppRestart = "重启应用",
            exitAppQuit = "结束应用",
        )

        /** English：结构与 [ZH] 逐条同形，措辞另写，不抄中文。 */
        val EN: PortraitShellCopy = PortraitShellCopy(
            lang = SettingsLanguage.EN,
            trayEmptyLine = "(Nothing to tap on this screen)",
            trayExecutingFormat = "Running: {0}",
            trayLastCommandFormat = "<Last command:{0}>",
            trayExpandCommand = "More commands ▾",
            trayCollapseCommand = "Collapse to favorites ▴",
            hudKeyButton = "Kb",
            hudTrayButton = "Cmd",
            hudSettingsButton = "Set",
            hudMoneyFormat = "Money {0}",
            hudDetailOpen = "▲ Hide status details",
            hudDetailClosed = "▼ Status details",
            drawerProtagonistTitleFormat = "▼ {0}  Protagonist status",
            drawerCharacterTitleFormat = "▼ {0}  Favor / Palam",
            drawerM2Note = "Protagonist-only details (traits, experience, equipment) not shown yet",
            drawerScrollToSections = "▲ Jump to [Status]/[Palam]",
            drawerScrollToLatest = "▼ Back to latest",
            stageTitleFormat = "Sprites {0}/10",
            stageTitleNoCount = "Sprites",
            stageExpand = "Expand ▸",
            stageCollapse = "Collapse ▾",
            stageHint = "Sprites render in the text flow; scroll the screen to see them",
            inputHintDefault = "Type and press Enter to submit",
            repeatedFailureNote = "A retry has already been tried once and failed: trying again takes the same path.",
            continueButton = "Continue ▶",
            continueButtonA11y = "Continue, go to the next screen",
            settingsButton = "Settings",
            railCountFormat = "{0} tappable",
            macroGroupFallback = "Macros",
            tooltipDismissHint = "Tap anywhere to close",
            inputToggleExpanded = "Keyboard (shown)",
            inputToggleCollapsed = "Keyboard (hidden)",
            inputKnobA11y = "Bottom handle",
            submitButton = "Submit",
            railToggleExpanded = "Option bar (shown)",
            railToggleCollapsed = "Option bar (hidden)",
            orientationLocked = "Orientation (locked)",
            orientationUnlocked = "Orientation (unlocked)",
            dirtyExitTitle = "Abnormal exit last time",
            dirtyExitBody1 = "The game ended abnormally last time (a crash, or killed by the system).",
            dirtyExitBody2 = "An error report has been saved on this phone. If the automatic upload did not get " +
                "through, use the buttons below to send it to the developers.",
            shareReport = "Share report",
            copyReport = "Copy report",
            ackButton = "Got it",
            dialogYes = "Yes",
            dialogNo = "No",
            freeNotice =
                "This game is completely free for study use only; selling it has nothing to do with this project. " +
                    "If you are asked to pay, do not pay, keep the evidence and report it to the channel you got it from.",
            loadingPixelHeadline = "― E N T E R I N G   T H E   W O R L D ―",
            loadingModernHeadline = "Entering the world",
            loadingPreparingData = "Preparing game data...",
            retryCompatNote =
                "\"Retry with less memory\" loads slower and uses less memory (kept for the rest of this run), " +
                    "but it may still fail to fit.",
            exitIsRestartNote =
                "\"Restart app\" ends this process and the system brings it right back: the memory the previous " +
                    "session holds can only be returned by switching to a clean process - retrying inside this " +
                    "process always lands on the same screen. Saves are on disk and will not be lost.",
            backToTitle = "Back to title",
            retryCompatButton = "Retry with less memory",
            retryButton = "Retry",
            exitAppRestart = "Restart app",
            exitAppQuit = "Exit app",
        )

        /** 日本語：结构与 [ZH] 逐条同形；术语面同样克制。 */
        val JA: PortraitShellCopy = PortraitShellCopy(
            lang = SettingsLanguage.JA,
            trayEmptyLine = "（この画面に選べる項目はありません）",
            trayExecutingFormat = "実行中: {0}",
            trayLastCommandFormat = "<前回の指令:{0}>",
            trayExpandCommand = "指令をさらに表示 ▾",
            trayCollapseCommand = "よく使う指令に戻す ▴",
            hudKeyButton = "キ",
            hudTrayButton = "コ",
            hudSettingsButton = "設",
            hudMoneyFormat = "所持額 {0}",
            hudDetailOpen = "▲ 状態詳細を閉じる",
            hudDetailClosed = "▼ 状態詳細",
            drawerProtagonistTitleFormat = "▼ {0}　主人公ステータス",
            drawerCharacterTitleFormat = "▼ {0}　好感 / Palam",
            drawerM2Note = "主人公専用の詳細（素質・経験・装備）は未表示",
            drawerScrollToSections = "▲ [Status]/[Palam] へ移動",
            drawerScrollToLatest = "▼ 最新へ戻る",
            stageTitleFormat = "立ち絵 {0}/10",
            stageTitleNoCount = "立ち絵",
            stageExpand = "展開 ▸",
            stageCollapse = "折りたたむ ▾",
            stageHint = "立ち絵は本文ストリーム内にあります。画面をスクロールしてご覧ください",
            inputHintDefault = "入力して Enter で送信",
            repeatedFailureNote = "再試行はすでに一度行われ、失敗しています：もう一度押しても同じ道を通ります。",
            continueButton = "続ける ▶",
            continueButtonA11y = "続ける、次の画面へ",
            settingsButton = "設定",
            railCountFormat = "{0} 個選択可能",
            macroGroupFallback = "マクロ",
            tooltipDismissHint = "タップで閉じます",
            inputToggleExpanded = "キーボード（表示中）",
            inputToggleCollapsed = "キーボード（非表示）",
            inputKnobA11y = "下部のつまみ",
            submitButton = "送信",
            railToggleExpanded = "選択肢バー（表示中）",
            railToggleCollapsed = "選択肢バー（非表示）",
            orientationLocked = "画面の向き（ロック中）",
            orientationUnlocked = "画面の向き（ロック解除）",
            dirtyExitTitle = "前回の異常終了",
            dirtyExitBody1 = "前回、ゲームが実行中に異常終了しました（クラッシュまたはシステムによる強制終了）。",
            dirtyExitBody2 = "エラーレポートを端末に保存しました。自動送信できなかった場合は、下のボタンで開発者へ送ってください。",
            shareReport = "レポートを共有",
            copyReport = "レポートをコピー",
            ackButton = "わかりました",
            dialogYes = "はい",
            dialogNo = "いいえ",
            freeNotice =
                "このゲームは完全無料で、学習用途限定です。有料での販売は本プロジェクトとは無関係です。" +
                    "課金を求められたら支払わず、証拠を残して配布元に通報してください。",
            loadingPixelHeadline = "― 世 界 へ 入 る ―",
            loadingModernHeadline = "世界へ入っています",
            loadingPreparingData = "ゲームデータを準備中…",
            retryCompatNote =
                "「軽量モードで再試行」は読み込みが遅く、メモリ使用量も少ない（今回の起動中はずっと使われます）が、" +
                    "それでも収まらない場合があります。",
            exitIsRestartNote =
                "「アプリを再起動」はこのプロセスを終了させ、システムがすぐに再起動します：前回のセッションが" +
                    "占めているメモリは、クリーンなプロセスに切り替えてしか返ってきません。このプロセス内で何度" +
                    "再試行しても同じ画面になります。セーブデータはディスク上にあり、失われません。",
            backToTitle = "タイトルへ戻る",
            retryCompatButton = "軽量モードで再試行",
            retryButton = "再試行",
            exitAppRestart = "アプリを再起動",
            exitAppQuit = "アプリを終了",
        )
    }
}
