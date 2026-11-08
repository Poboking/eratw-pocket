package com.eratw.port.android

/**
 * 失败屏 / 崩溃面 / 启动加载域的玩家面文案表（三语，T-2a 收口）。
 *
 * ## 为什么 MainActivity 失败域、UiFault 失败屏与 GameHost 失败面同表
 *
 * 这三处是**同一条「开局 → 失败屏」链路**上的同一批句子：`FAILURE_PREFIX` 早已是跨两文件的
 * 共享常量，`FailureScreenKind` 的分族话术写在 UiFault、由 MainActivity 上屏、而分族的置位
 * 在 GameHost。拆两张表的唯一候选切法是「线程模型不同」（GameHost 的 `failure` 由 VM/装载
 * 线程写、MainActivity 各点在 UI 线程现取），但取用差异只是**调用点写法**（见下节），
 * 表本体没有第二条语义需要分开管 —— 拆开反而把「启动失败」一族的措辞权威劈成两处。
 *
 * ## 取语言的两种口径（照 UpdateCopy + ModImportController 先例）
 *
 * - **UI 线程点位**（MainActivity 全部收口点、UiFault 三函数的生产调用点）：渲染期现取
 *   `ShellFailureCopy.of(UiPrefs.settingsLanguage(context))` —— 与 ShellUi 的
 *   `PortraitShellCopy.of(...)` 同口径，标签渲染期现取不吃旧值；
 * - **VM/装载线程点位**（GameHost 的 `failure` 写点）：[GameHost] 构造期（UI 线程）取好
 *   一次存进实例，VM 线程只用这份快照 —— 与 ModImportController「起线程前一次取好进线程、
 *   纯层不吃 context、不判 Locale」同口径。会话生命周期内语言冻结是接受代价（同一会话的
 *   失败屏与装载进度本就同批产生）。
 *
 * ## 三条硬规矩（照 UpdateCopy / ModImportCopy，同口径）
 *
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]；壳层不许再判系统 Locale。
 * 2. **中文逐字搬运**：[ZH] 的每一个值与收口前的硬编码**逐字相同**（含 `…`、全角括号/冒号、
 *     `——` 与 `: ` 的写法）。既有定向用例（UiFaultTest / HeapFailureCopyTest）逐字钉中文
 *     原文，中文动一个字就是把别人的用例打红。
 * 3. **缺省参数只为所有权外的既有单测留**（对拍 ZH 档的兼容口）：`UiFault.kt` 三函数与
 *     `heapPreflightStoppedText` / `heapOomText` 的 `copy` 形参缺省 [ZH]；生产调用点必须显式传本表。
 *
 * ## 留在表外的共用面（不收）
 *
 * 异常原话（`t.message`、`blocker.message`、解包器 label）是取证面与玩家面同吃一句的：
 * 只当 `fill` 实参进模板，不进表、不翻（照 ModImportCopy 的「原话不翻」规矩）。
 * `Log.x` 各行、`writeErrorReport` 的报告标题与补充现场行（取证面）、`renderBootstrapFailure`
 * （在 CrashForensics.kt，物理归另一文件）本波都不收。
 *
 * @property lang 这一组对应的设置页语言
 */
data class ShellFailureCopy(
    val lang: SettingsLanguage,

    // ---------------------------------------------------------------- 失败屏（UiFault 三函数 + GameHost 前缀）
    /** 装载两族（产物/装载）的失败屏大标题。 */
    val failureTitleStartup: String,
    /** 中局终局（游戏跑起来之后中止）的失败屏大标题。 */
    val failureTitleInGame: String,
    /** 装载/运行期失败的既有标题前缀（GameHost `FAILURE_PREFIX` 的同源位，含尾冒号）。 */
    val failurePrefix: String,
    /** 产物根行：{0} 产物目录路径。 */
    val artifactRootLineFormat: String,
    /** 产物族成因清单行（那一整句就是成因，不许再拆）。 */
    val artifactCauseLine: String,
    /** 产物族的 adb 提示行（只对产物族讲）。 */
    val artifactAdbLine: String,
    /** 能回标题时三族同形的说明句。 */
    val noteBackToTitle: String,
    /** 中局终局且不能回标题的说明句。 */
    val noteInGame: String,
    /** 装载两族且不能回标题的说明句。 */
    val noteLoad: String,

    // ---------------------------------------------------------------- 「内存不够」两族（HeapFailureStage 甲/乙）
    /** 甲族（预检拒载）：{0} 上限、{1} 需求、{2} 还差、{3} 装载前已用。 */
    val heapPreflightFormat: String,
    /** 乙族有阶段读数时的从句：{0} 阶段名。 */
    val heapOomWhereFormat: String,
    /** 乙族（装载中真 OOM）：{0} 峰值、{1} 上限、{2} 阶段从句或空、{3} OOM 原话从句或空。 */
    val heapOomFormat: String,
    /** 乙族 OOM 异常原话从句：{0} 异常类名与首行原话（原话不翻）。 */
    val oomDetailPrefixFormat: String,

    // ---------------------------------------------------------------- GameHost 装载/运行失败面
    /** 控制台还没布局（列宽解不出）。 */
    val consoleNotLaidOut: String,
    /** 首启解包失败：{0} 异常原话（前缀 [failurePrefix] 由调用点拼）。 */
    val unpackFailedFormat: String,
    /** 脚本错误终局（会话保留）：{0} 脚本原话。 */
    val scriptAbortFormat: String,
    /** 状态机终局：{0} 停在哪个态、{1} 阻塞原话、{2} 证据原话。 */
    val stateMachineHaltFormat: String,
    /** 状态机终局阻塞原话缺席时的占位词。 */
    val stateMachineUnknownBlocker: String,
    /** 状态机终局证据缺席时的占位词。 */
    val stateMachineNoEvidence: String,
    /** 回到标题失败：{0} 异常原话。 */
    val backToTitleFailedFormat: String,
    /** 脚本位置行号：{0} 行号。 */
    val scriptLocationLineFormat: String,
    /** 脚本位置包装：{0} 函数名与行号的并列串。 */
    val scriptLocationWrapFormat: String,
    /** 上一局内存未释放：{0} 已用、{1} 上限。 */
    val staleSessionHeapFormat: String,
    /** 缺产物：{0} 候选目录并列串、{1} program 文件名、{2} meta 文件名。 */
    val missingArtifactsFormat: String,
    /** 候选目录并列时的连接词。 */
    val artifactDirsSeparator: String,

    // ---------------------------------------------------------------- 重启与收尾等待（MainActivity）
    /** 按新配置重启时同步给覆盖层与快照的那句。 */
    val restartingLine: String,
    /** 重启失败：{0} 异常类名、{1} 明细从句（[detailSeparator] + 原话）或空串。 */
    val restartFailedFormat: String,
    /** 异常明细接在类名后的分隔符（无明细时调用点不拼它）。 */
    val detailSeparator: String,
    /** 旧会话还没退出的等待行。 */
    val oldSessionWaiting: String,
    /** 重启停止：{0} 硬等待秒数。 */
    val restartStuckFormat: String,
    /** 重启停止时提交给控制台快照的标签行。 */
    val restartStuckLabel: String,

    // ---------------------------------------------------------------- 解包与装载进度（MainActivity）
    /** 快照首行：检查随包数据（清点出数之前）。 */
    val checkingApkLine: String,
    /** 覆盖层：检查游戏数据。 */
    val checkingDataLine: String,
    /** 快照首行：解包进行中。 */
    val unpackingApkLine: String,
    /** 覆盖层：解包进行中。 */
    val unpackingDataLine: String,
    /** 覆盖层：装载进行中。 */
    val loadingGameLine: String,
    /** 解包进度行：{0} 已解包条数、{1} 总条数、{2} 解包器 label。 */
    val progressUnpackedFormat: String,
    /** 落盘读数行：{0} 汇总串。 */
    val progressLandedFormat: String,
    /** 清点还没出数时的占位行。 */
    val progressCounting: String,
    /** 清点读数：一条都没有。 */
    val inventoryNone: String,
    /** 清点读数：{0} 条目数、{1} MiB 数。 */
    val inventorySummaryFormat: String,
    /** 解包器说成功但落盘追不上：{0} 落盘汇总、{1} 随包汇总。 */
    val finalTitleLagFormat: String,
    /** 命中已解包目录：{0} 落盘汇总。 */
    val finalTitleCachedFormat: String,
    /** 本次真解包：{0} 落盘汇总。 */
    val finalTitleUnpackedFormat: String,

    // ---------------------------------------------------------------- 空间不足与启动失败消息
    /** 解包前空间预检不过：{0} 还需写入的 MiB。 */
    val spaceShortFormat: String,
    /** 启动期异常的失败屏消息：{0} 异常类名、{1} 明细从句或空串。 */
    val startupFaultFormat: String,

    // ---------------------------------------------------------------- 输入条与确认框
    /** 宿主不在/VM 已停时输入条的 hint。 */
    val hintNotRunning: String,
    /** 显示大小重开确认框标题。 */
    val displaySizeDialogTitle: String,
    /** 显示大小重开确认框正文。 */
    val displaySizeDialogBody: String,
    /** 方向重开确认框标题。 */
    val orientationDialogTitle: String,
    /** 方向确认框正文里的「竖屏」。 */
    val orientationPortrait: String,
    /** 方向确认框正文里的「横屏」。 */
    val orientationLandscape: String,
    /** 方向重开确认框正文：{0} 切到的方向。 */
    val orientationDialogBodyFormat: String,
    /** 恢复默认设置后「重开生效」确认框标题。 */
    val restoreDefaultsDialogTitle: String,
    /** 恢复默认设置后「重开生效」确认框正文。 */
    val restoreDefaultsDialogBody: String,
    /** 三枚确认框共用的「现在重开」。 */
    val dialogRestartNow: String,
    /** 三枚确认框共用的「下次启动生效」。 */
    val dialogApplyNextLaunch: String,

    // ---------------------------------------------------------------- 报告复制 / 分享 Toast 组
    /** 报告文件不在（复制与分享共用）。 */
    val toastNoReportFile: String,
    /** 读失败或剪贴板写入失败。 */
    val toastCopyFailed: String,
    /** 复制成功。 */
    val toastCopyOk: String,
    /** 分享面板没能启动。 */
    val toastSharePanelFailed: String,
    /** 没有可用的分享入口。 */
    val toastNoShareEntry: String,
    /** 系统分享面板的标题。 */
    val shareChooserTitle: String,

    // ---- 建壳失败最后一屏（CrashForensics.renderBootstrapFailure，接线②入表）----

    /** 建壳失败最后一屏：标题行（含尾随空行，换行进键值保证跨档对齐）。 */
    val bootstrapTitleLine: String,
    /** 建壳失败最后一屏：异常消息缺席时的兜底词。 */
    val bootstrapNoMessage: String,
    /** 建壳失败最后一屏：存档安抚句（含尾随空行）。 */
    val bootstrapReassure: String,
    /** 建壳失败最后一屏：操作指引句。 */
    val bootstrapInstruction: String,
    /** 建壳失败最后一屏：「重启应用」按钮。 */
    val bootstrapRestartButton: String,
) {

    /**
     * 句式占位符 `{0}`..`{3}`：单趟替换，参数本身带 `{n}`（例如异常原话）不会被二次展开。
     *
     * **花括号必须走字符类而不是反斜杠转义**：Android 的正则是 ICU 引擎，转义写法直接抛
     * `PatternSyntaxException`（OpenJDK 却容忍）—— 本表照抄 `SettingsStrings` / `UpdateCopy` /
     * `ModImportCopy` 的同一写法。
     */
    fun fill(template: String, vararg args: Any?): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言：不许在失败面再造一套语言判定。 */
        fun of(lang: SettingsLanguage): ShellFailureCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：每个值都与收口前的硬编码逐字相同（中文零漂移的对拍右轴）。 */
        val ZH: ShellFailureCopy = ShellFailureCopy(
            lang = SettingsLanguage.ZH,
            failureTitleStartup = "启动失败",
            failureTitleInGame = "游戏出错",
            failurePrefix = "启动失败：",
            artifactRootLineFormat = "产物根: {0}",
            artifactCauseLine =
                "随包 APK 正常不该走到这里：这一句就是原因（磁盘满 / assets 坏 / program 与 meta 不配对）。",
            artifactAdbLine =
                "开发调试通道才需要 adb：tools/apkpack/push_game_data.ps1 -Dev 会把数据直推到上面那个目录。",
            noteBackToTitle =
                "「回到标题」在原会话上复位后重进标题（不清存档、不重新装载）；「重试」关闭当前会话并重新装载。",
            noteInGame = "重试会关闭当前会话并重新装载；存档在盘上，不会丢。",
            noteLoad = "重试会关闭当前会话并重新装载；若反复失败请检查产物目录。",
            heapPreflightFormat =
                "启动失败：内存不够，装不进这一版" +
                    "（这台手机最多给游戏 {0} MB，这一版要 {1} MB，还差 {2} MB；" +
                    "这次装载开始前已用 {3} MB）。" +
                    "下面的「少占内存再试」会装得更慢、占得少一些，但这一版的需求比上限还大，" +
                    "所以也可能还是装不下；最稳的是换一台内存更大的手机。",
            heapOomWhereFormat = "，停在「{0}」这一步",
            oomDetailPrefixFormat = "；OOM 原话：{0}",
            heapOomFormat =
                "启动失败：内存不够，装到一半就断了" +
                    "（最高用到 {0} MB / 这台手机最多给游戏 {1} MB{2}{3}）。" +
                    "换一台内存更大的手机，或者点下面的「少占内存再试」再试一次 —— 它装得更慢、占得更少。",
            consoleNotLaidOut = "控制台尚未布局，无法确定列宽",
            unpackFailedFormat = "首启解包失败：{0}",
            scriptAbortFormat = "脚本错误中止本回合：{0}",
            stateMachineHaltFormat = "系统状态机停在 {0}：{1}（{2}）",
            stateMachineUnknownBlocker = "未知阻塞",
            stateMachineNoEvidence = "无证据",
            backToTitleFailedFormat = "回到标题失败：{0}",
            scriptLocationLineFormat = "行{0}",
            scriptLocationWrapFormat = "（{0}）",
            staleSessionHeapFormat =
                "启动失败：上一局的内存还没释放（已用 {0} MiB / 上限 {1} MiB）。" +
                    "点下面的「重启应用」—— 它会结束本进程并由系统立刻拉回来，存档在盘上，不会丢。",
            missingArtifactsFormat = "缺少 {0} 下的 {1} / {2}",
            artifactDirsSeparator = " 或 ",
            restartingLine = "正在按新配置重启游戏…",
            restartFailedFormat = "重启失败：{0}{1}。存档在盘上，不会丢失；可先重试，若仍然失败请结束应用后重开。",
            detailSeparator = "：",
            oldSessionWaiting = "上一局还在收尾（脚本正跑一条长链，等它到输入点）…",
            restartStuckFormat =
                "上一局在 {0} 秒内没跑到输入点，为避免两份程序同时占堆已停止重启（存档已在盘上，不会丢）。",
            restartStuckLabel = "重启停止",
            checkingApkLine = "正在检查 APK 随包数据…",
            checkingDataLine = "正在检查游戏数据…",
            unpackingApkLine = "正在解包 APK 随包资源（立绘与音效一起）…",
            unpackingDataLine = "正在解包游戏资源…",
            loadingGameLine = "正在装载游戏内容…",
            progressUnpackedFormat = "已解包 {0}/{1} 条（{2}）",
            progressLandedFormat = "已落盘：{0}",
            progressCounting = "正在盘点随包数据…",
            inventoryNone = "(没有随包条目)",
            inventorySummaryFormat = "{0} 个条目 / {1} MiB",
            finalTitleLagFormat = "落盘 {0} 追不上 APK 随包 {1}：随包缺件或条目被压缩",
            finalTitleCachedFormat = "随包数据已就位（本次启动命中已解包目录，{0}），正在装载 erbc.v1…",
            finalTitleUnpackedFormat = "解包完成 {0}，正在装载 erbc.v1（program.erbc + program.meta）…",
            spaceShortFormat = "启动失败：存储空间不足，还需写入 {0} MiB，请清理存储后重试。",
            startupFaultFormat = "启动失败：{0}{1}。存档在盘上，不会丢失；可先重试，若仍然失败请把这句话反馈给开发者。",
            hintNotRunning = "游戏未运行，无法提交",
            displaySizeDialogTitle = "显示大小已更改",
            displaySizeDialogBody =
                "字号、列数与「窗口宽窄变化时重新排版」都要重开一局才生效。现在重开吗？未存档的进度会丢失。",
            orientationDialogTitle = "屏幕方向已更改",
            orientationPortrait = "竖屏",
            orientationLandscape = "横屏",
            orientationDialogBodyFormat = "已切到{0}。方向要重开游戏才生效，现在重开吗？未存档的进度会丢失。",
            restoreDefaultsDialogTitle = "设置已恢复默认",
            restoreDefaultsDialogBody =
                "恢复的设置已写入磁盘，其中字号、显示档位等要重开一局才生效。现在重开吗？未存档的进度会丢失。",
            dialogRestartNow = "现在重开",
            dialogApplyNextLaunch = "下次启动生效",
            toastNoReportFile = "没有可用的报告文件",
            toastCopyFailed = "复制失败，请改用「分享报告」",
            toastCopyOk = "报错报告已复制，粘贴发给开发者即可",
            toastSharePanelFailed = "分享面板启动失败，请改用「复制报告」",
            toastNoShareEntry = "没有可用的分享入口，请改用「复制报告」",
            shareChooserTitle = "分享 eratw 错误报告",
            bootstrapTitleLine = "启动失败：游戏界面无法初始化。\n\n",
            bootstrapNoMessage = "（无消息）",
            bootstrapReassure = "存档都在存储卡上，不会丢失。\n\n",
            bootstrapInstruction = "请先点下面的「重启应用」；若仍然停在这一屏，把这段文字截图发给开发者即可定位。",
            bootstrapRestartButton = "重启应用",
        )

        /** English：结构与 [ZH] 逐条同形（槽位序列一致），措辞另写，不抄中文。 */
        val EN: ShellFailureCopy = ShellFailureCopy(
            lang = SettingsLanguage.EN,
            failureTitleStartup = "Startup failed",
            failureTitleInGame = "Game error",
            failurePrefix = "Startup failed: ",
            artifactRootLineFormat = "Artifact root: {0}",
            artifactCauseLine =
                "The bundled APK should never reach this point: this sentence is the cause " +
                    "(disk full / broken assets / program and meta not paired).",
            artifactAdbLine =
                "adb is only needed on the development channel: tools/apkpack/push_game_data.ps1 -Dev " +
                    "pushes the data straight into the directory above.",
            noteBackToTitle =
                "\"Back to title\" resets on the original session and re-enters the title " +
                    "(saves are kept, nothing is reloaded); \"Retry\" closes the current session and loads again.",
            noteInGame = "Retry closes the current session and loads again; saves are on disk and are not lost.",
            noteLoad = "Retry closes the current session and loads again; if it keeps failing, " +
                "check the artifact directory.",
            heapPreflightFormat =
                "Startup failed: not enough memory to load this version " +
                    "(this phone allows the game at most {0} MB, this version needs {1} MB, " +
                    "still short {2} MB; {3} MB was already in use before this load started). " +
                    "\"Use less memory and retry\" below loads slower and uses less, but this version needs " +
                    "more than the limit, so it may still fail; the surest fix is a phone with more memory.",
            heapOomWhereFormat = ", stopped at the \"{0}\" step",
            oomDetailPrefixFormat = "; OOM said: {0}",
            heapOomFormat =
                "Startup failed: not enough memory, it broke partway through loading " +
                    "(peak {0} MB / this phone allows the game at most {1} MB{2}{3}). " +
                    "Switch to a phone with more memory, or tap \"Use less memory and retry\" below to try once " +
                    "more - it loads slower and uses less.",
            consoleNotLaidOut = "The console is not laid out yet; column width cannot be determined",
            unpackFailedFormat = "first-launch unpacking failed: {0}",
            scriptAbortFormat = "A script error ended this turn: {0}",
            stateMachineHaltFormat = "The system state machine stopped at {0}: {1} ({2})",
            stateMachineUnknownBlocker = "unknown blocker",
            stateMachineNoEvidence = "no evidence",
            backToTitleFailedFormat = "Returning to the title failed: {0}",
            scriptLocationLineFormat = "line {0}",
            scriptLocationWrapFormat = "({0})",
            staleSessionHeapFormat =
                "Startup failed: the previous session's memory has not been released " +
                    "({0} MiB used / {1} MiB limit). Tap \"Restart app\" below - it ends this process and " +
                    "the system brings it right back; saves are on disk and are not lost.",
            missingArtifactsFormat = "Under {0}, missing {1} / {2}",
            artifactDirsSeparator = " or ",
            restartingLine = "Restarting the game with the new settings...",
            restartFailedFormat = "Restart failed: {0}{1}. Saves are on disk and are not lost; try again first, " +
                "and if it still fails, close the app and reopen it.",
            detailSeparator = ": ",
            oldSessionWaiting =
                "The previous session is still winding down (a script is running a long chain; " +
                    "waiting for it to reach an input point)...",
            restartStuckFormat =
                "The previous session did not reach an input point within {0} seconds; the restart was stopped " +
                    "to keep two programs from occupying memory at once (saves are already on disk, " +
                    "nothing is lost).",
            restartStuckLabel = "Restart stopped",
            checkingApkLine = "Checking bundled APK data...",
            checkingDataLine = "Checking game data...",
            unpackingApkLine = "Unpacking bundled APK resources (art and sound together)...",
            unpackingDataLine = "Unpacking game resources...",
            loadingGameLine = "Loading game content...",
            progressUnpackedFormat = "Unpacked {0}/{1} entries ({2})",
            progressLandedFormat = "On disk: {0}",
            progressCounting = "Counting bundled data...",
            inventoryNone = "(no bundled entries)",
            inventorySummaryFormat = "{0} entries / {1} MiB",
            finalTitleLagFormat = "On disk {0} lags the bundled APK {1}: entries are missing or compressed",
            finalTitleCachedFormat =
                "Bundled data is in place (this launch hit the already-unpacked directory, {0}); " +
                    "loading erbc.v1...",
            finalTitleUnpackedFormat = "Unpacking finished {0}; loading erbc.v1 (program.erbc + program.meta)...",
            spaceShortFormat = "Startup failed: not enough storage, {0} MiB more to write; free up space and retry.",
            startupFaultFormat = "Startup failed: {0}{1}. Saves are on disk and are not lost; try again first, " +
                "and if it still fails, please report this sentence to the developer.",
            hintNotRunning = "The game is not running; cannot submit",
            displaySizeDialogTitle = "Display size changed",
            displaySizeDialogBody =
                "Font size, column count and \"reflow when the window resizes\" only take effect after " +
                    "restarting the game. Restart now? Unsaved progress will be lost.",
            orientationDialogTitle = "Screen orientation changed",
            orientationPortrait = "portrait",
            orientationLandscape = "landscape",
            orientationDialogBodyFormat =
                "Switched to {0}. Orientation only takes effect after restarting the game. " +
                    "Restart now? Unsaved progress will be lost.",
            restoreDefaultsDialogTitle = "Defaults restored",
            restoreDefaultsDialogBody =
                "The restored settings are on disk. Some of them (font size, display options and more) " +
                    "only take effect after restarting the game. Restart now? Unsaved progress will be lost.",
            dialogRestartNow = "Restart now",
            dialogApplyNextLaunch = "Next launch",
            toastNoReportFile = "No report file available",
            toastCopyFailed = "Copy failed; use \"Share report\" instead",
            toastCopyOk = "Error report copied; paste it to the developer",
            toastSharePanelFailed = "The share panel failed to start; use \"Copy report\" instead",
            toastNoShareEntry = "No share entry available; use \"Copy report\" instead",
            shareChooserTitle = "Share eratw error report",
            bootstrapTitleLine = "Startup failed: the game UI could not initialize.\n\n",
            bootstrapNoMessage = "(no message)",
            bootstrapReassure = "Your saves are all on the storage card and cannot be lost.\n\n",
            bootstrapInstruction = "Tap \"Restart app\" below first; if it still stops on this screen, " +
                "screenshot this text and send it to the developer to pinpoint the cause.",
            bootstrapRestartButton = "Restart app",
        )

        /** 日本語：结构与 [ZH] 逐条同形。 */
        val JA: ShellFailureCopy = ShellFailureCopy(
            lang = SettingsLanguage.JA,
            failureTitleStartup = "起動に失敗しました",
            failureTitleInGame = "ゲームエラー",
            failurePrefix = "起動に失敗しました：",
            artifactRootLineFormat = "成果物ルート: {0}",
            artifactCauseLine =
                "同梱 APK でここに来るはずはありません：この一文が原因です" +
                    "（ディスク満杯 / assets 破損 / program と meta の不整合）。",
            artifactAdbLine =
                "adb が必要なのは開発経路だけです：tools/apkpack/push_game_data.ps1 -Dev で" +
                    "上のディレクトリへ直接データを送れます。",
            noteBackToTitle =
                "「タイトルへ戻る」は元のセッションのまま復位してタイトルへ戻ります（セーブは消えず、" +
                    "再読み込みもしません）；「再試行」は現在のセッションを閉じて再読み込みします。",
            noteInGame = "再試行すると現在のセッションを閉じて再読み込みします；セーブはディスク上にあり、消えません。",
            noteLoad = "再試行すると現在のセッションを閉じて再読み込みします；繰り返し失敗する場合は" +
                "成果物ディレクトリを確認してください。",
            heapPreflightFormat =
                "起動に失敗しました：メモリが足りず、この版を読み込めません" +
                    "（この端末はゲームに最大 {0} MB、この版は {1} MB 必要で、あと {2} MB 足りません；" +
                    "読み込み開始前にすでに {3} MB 使用）。" +
                    "下の「省メモリで再試行」はより遅く、より少ないメモリで読み込みますが、この版の要求は" +
                    "上限を超えているため、それでも読み込めないことがあります；最も確実なのは、" +
                    "より大容量メモリの端末に変えることです。",
            heapOomWhereFormat = "、「{0}」の段階で停止",
            oomDetailPrefixFormat = "；OOM の原話：{0}",
            heapOomFormat =
                "起動に失敗しました：メモリが足りず、読み込みの途中で切れました" +
                    "（最高 {0} MB / この端末はゲームに最大 {1} MB{2}{3}）。" +
                    "より大容量メモリの端末に変えるか、下の「省メモリで再試行」でもう一度お試しください —— " +
                    "より遅く、より少ないメモリで読み込みます。",
            consoleNotLaidOut = "コンソールがまだレイアウトされておらず、列幅を確定できません",
            unpackFailedFormat = "初回展開に失敗しました：{0}",
            scriptAbortFormat = "スクリプトエラーでこのターンを中断しました：{0}",
            stateMachineHaltFormat = "システム状態機械が {0} で停止しました：{1}（{2}）",
            stateMachineUnknownBlocker = "未知のブロッカー",
            stateMachineNoEvidence = "証拠なし",
            backToTitleFailedFormat = "タイトルへの復帰に失敗しました：{0}",
            scriptLocationLineFormat = "{0} 行目",
            scriptLocationWrapFormat = "（{0}）",
            staleSessionHeapFormat =
                "起動に失敗しました：前のセッションのメモリがまだ解放されていません" +
                    "（使用 {0} MiB / 上限 {1} MiB）。" +
                    "下の「アプリを再起動」を押してください —— このプロセスを終了し、システムがすぐに" +
                    "立ち上げ直します。セーブはディスク上にあり、消えません。",
            missingArtifactsFormat = "{0} の下に {1} / {2} がありません",
            artifactDirsSeparator = " または ",
            restartingLine = "新しい設定でゲームを再起動しています…",
            restartFailedFormat =
                "再起動に失敗しました：{0}{1}。セーブはディスク上にあり、消えません。まずは再試行し、" +
                    "それでも失敗する場合はアプリを終了して開き直してください。",
            detailSeparator = "：",
            oldSessionWaiting =
                "前のセッションがまだ収尾中です（スクリプトが長い連鎖を実行中。" +
                    "入力地点に届くのを待っています）…",
            restartStuckFormat =
                "前のセッションが {0} 秒以内に入力地点に届きませんでした。2つのプログラムが同時に" +
                    "メモリを占めるのを避けるため再起動を停止しました（セーブはディスク上にあり、消えません）。",
            restartStuckLabel = "再起動を停止",
            checkingApkLine = "APK 同梱データを確認しています…",
            checkingDataLine = "ゲームデータを確認しています…",
            unpackingApkLine = "APK 同梱リソースを展開しています（立ち絵と効果音も一緒に）…",
            unpackingDataLine = "ゲームリソースを展開しています…",
            loadingGameLine = "ゲーム内容を読み込んでいます…",
            progressUnpackedFormat = "展開済み {0}/{1} 件（{2}）",
            progressLandedFormat = "書き込み済み：{0}",
            progressCounting = "同梱データを集計しています…",
            inventoryNone = "（同梱エントリなし）",
            inventorySummaryFormat = "{0} 件 / {1} MiB",
            finalTitleLagFormat = "書き込み {0} が APK 同梱 {1} に追いつきません：同梱欠けか圧縮エントリです",
            finalTitleCachedFormat =
                "同梱データは既に揃っています（今回の起動は展開済みディレクトリを命中、{0}）。" +
                    "erbc.v1 を読み込んでいます…",
            finalTitleUnpackedFormat = "展開完了 {0}。erbc.v1（program.erbc + program.meta）を読み込んでいます…",
            spaceShortFormat =
                "起動に失敗しました：ストレージ空き容量が足りず、あと {0} MiB 書き込みが必要です。" +
                    "ストレージを整理してから再試行してください。",
            startupFaultFormat =
                "起動に失敗しました：{0}{1}。セーブはディスク上にあり、消えません。まずは再試行し、" +
                    "それでも失敗する場合はこの文を開発者へお知らせください。",
            hintNotRunning = "ゲームが起動していないため、送信できません",
            displaySizeDialogTitle = "表示サイズが変更されました",
            displaySizeDialogBody =
                "文字サイズ・列数・「ウィンドウ幅変化時に再整形」はゲームを再起動しないと反映されません。" +
                    "今すぐ再起動しますか？未保存の進行状況は失われます。",
            orientationDialogTitle = "画面の向きが変更されました",
            orientationPortrait = "縦画面",
            orientationLandscape = "横画面",
            orientationDialogBodyFormat =
                "{0} に切り替えました。向きはゲームの再起動後に反映されます。今すぐ再起動しますか？" +
                    "未保存の進行状況は失われます。",
            restoreDefaultsDialogTitle = "既定値に戻しました",
            restoreDefaultsDialogBody =
                "復元した設定は保存済みです。文字サイズや表示設定などはゲームの再起動後に反映されます。" +
                    "今すぐ再起動しますか？未保存の進行状況は失われます。",
            dialogRestartNow = "今すぐ再起動",
            dialogApplyNextLaunch = "次回起動時に反映",
            toastNoReportFile = "利用できる報告ファイルがありません",
            toastCopyFailed = "コピーに失敗しました。「報告を共有」をご利用ください",
            toastCopyOk = "エラー報告をコピーしました。開発者に貼り付けてお送りください",
            toastSharePanelFailed = "共有パネルを開始できませんでした。「報告をコピー」をご利用ください",
            toastNoShareEntry = "共有できる入り口がありません。「報告をコピー」をご利用ください",
            shareChooserTitle = "eratw エラー報告を共有",
            bootstrapTitleLine = "起動に失敗しました：ゲーム画面を初期化できません。\n\n",
            bootstrapNoMessage = "（メッセージなし）",
            bootstrapReassure = "セーブデータはすべてストレージカード上にあり、失われることはありません。\n\n",
            bootstrapInstruction = "まず下の「アプリを再起動」をタップしてください。それでもこの画面のままなら、" +
                "この文章をスクリーンショットして開発者に送れば原因を特定できます。",
            bootstrapRestartButton = "アプリを再起動",
        )
    }
}
