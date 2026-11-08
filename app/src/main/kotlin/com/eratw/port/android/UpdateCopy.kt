package com.eratw.port.android

/**
 * 更新通道的玩家面文案表（三语，契约 EP4 U3 §1）。
 *
 * ## 为什么单独一张表而不并进 SettingsStrings
 *
 * 这张表的条目全部是「一条链路上的句子」：成单 → 弹窗正文 → 蜂窝确认 → 通知授权 → 检查报障 →
 * 设置页通道现状，改判读时整组一起动。并进配置页那张 1174 行的大表会让两边都越长越难读，
 * 而且那张表里的更新键只有六条（标题、提示、按钮、跳过行、恢复按钮、恢复完成），
 * 本波**不扩它**（口径唯一，避免两处权威）。
 *
 * ## 三条硬规矩（照契约，不许后面车道回退）
 *
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]，壳层一律用
 *    `UpdateCopy.of(UiPrefs.settingsLanguage(context))`（见 [UpdateUi.copyFor]）。
 *    更新侧不许再判系统 Locale，也不许再写一套语言判定。
 * 2. **中文逐字搬运**：[ZH] 的每一个值与搬动前的硬编码**逐字相同**（含省略号字符、全角括号与逗号、
 *     `…` 的位置）。既有定向用例大量钉中文原文，中文动一个字就是把别人的用例打红。
 * 3. **不许把术语翻进去**：玩家面三语都不许出现 mirror / manifest / digest / Range / versionCode
 *    这类词（中文侧的守卫用例「玩家面一个术语都不许出现」继续生效，英/日同口径，见 U3 的三语形状用例）。
 *
 * ## 句式里的 `{0}` / `{1}`
 *
 * 带读数的句子存成模板，由 [fill] 一次替换（与 `SettingsStrings.fill` 同口径：花括号走字符类
 * 而不是反斜杠转义，Android 的 ICU 正则会把转义写法直接抛掉，真机一点就崩）。
 * 判读结构（哪条分支、几行、顺序）留在纯层函数里，本表只放词。
 *
 * @property lang 这一组对应的设置页语言
 */
data class UpdateCopy(
    val lang: SettingsLanguage,

    // ---------------------------------------------------------------- 通用读数与标签
    /** 整包那一件在句子里的名字（[UpdateUiText.jobFor] 的 `DownloadJob.label`）。 */
    val apkLabel: String,
    /** 数据包那一件的名字。 */
    val assetsLabel: String,
    /** 「距今」不足一分钟的说法（不许写成「0 分钟前」）。 */
    val justNow: String,
    /** 分钟档：`37 分钟前`。 */
    val minutesAgoFormat: String,
    /** 小时档：`2 小时前`（分钟数大于 60 就换算，玩家才数得清）。 */
    val hoursAgoFormat: String,
    /** 天档：`5 天前`。 */
    val daysAgoFormat: String,
    /** 多条失败原话连成一句时的分隔符。 */
    val problemSeparator: String,
    /** 「检查正在跑」这句话的单一来源：按钮进行中态与起线程前那句提示不许两种说法。 */
    val checkingLabel: String,
    /**
     * 「跳过了提醒，去哪里反悔」这句话的**唯一来源**（契约 EP4 U2-⑥ 的单源纪律）。
     * 真实恢复口只有一处：设置页「启动」卡里的恢复按钮。
     */
    val reminderRestorePhrase: String,

    // ---------------------------------------------------------------- 弹窗正文与成单
    /** 蜂窝二次确认的正文：{0} 要下的那一件、{1} 体积。 */
    val cellularConfirmBodyFormat: String,
    /** 整包行的头部：{0} 新版本名、{1} 本机版本名（读不出时回落 versionCode 数字）。 */
    val offerApkHeadFormat: String,
    /** 整包行（装得上）：{0} 头部、{1} 体积。 */
    val offerApkSizeLineFormat: String,
    /** 整包行（章不合，坏消息必须在正文第一行）：{0} 头部、{1} 原因原话。 */
    val offerApkBlockedLineFormat: String,
    /** 数据行里标注「仅包含变更部分」的一段。 */
    val offerDeltaMarker: String,
    /** 数据行：{0} 数据版本、{1} 本机数据读数、{2} 体积、{3} 差量标注。 */
    val offerAssetsLineFormat: String,
    /** 纯数据更新（整包已最新）时正文第一行的解释：为什么刚装完安装包还有更新；{0} 本机版本名。 */
    val offerAssetsOnlyExplainerLine: String,
    /** 正文最后一行的流量提示。 */
    val offerCellularLine: String,
    /** 展开层里那条更新说明的前缀：{0} 说明原话。 */
    val offerNoteLineFormat: String,
    /** 没有提醒原话时的展开标签。 */
    val offerExpandLabel: String,
    /** 有提醒原话时的展开标签：{0} 基础标签、{1} 提醒条数。 */
    val offerExpandCountFormat: String,
    /** 发布信息中当前版本无法解析的条目提示：{0} 条数。 */
    val warningsNoteFormat: String,
    /** 点了「忽略此版本」之后 Toast 的一句：{0} 版本码、{1} 恢复口话术。 */
    val skippedToastFormat: String,
    /** 设置页「启动」卡里的跳过小字：{0} 版本码、{1} 恢复口话术。 */
    val skippedNoteFormat: String,

    // ---------------------------------------------------------------- 检查的四种结果
    /** 单飞闸挡住重复点击时上屏的一句：{0} 就是「正在检查更新」那条。 */
    val checkBusyFormat: String,
    /** 问授权前先说清「为什么问」：{0} 体积。 */
    val notificationRationaleFormat: String,
    /** 「包里没配源」：处理人是出包的人，不是玩家的网络。 */
    val noBuiltInMirrorLine: String,
    /** 一条源都没取到、又没有逐条原话时的那句替代。 */
    val allMirrorsFailedPhrase: String,
    /** 「配了但连不上」：{0} 源条数、{1} 逐条原话。 */
    val checkFailedFormat: String,
    /** 检查自己抛错：{0} 异常名（留给取证的读数，不是术语）。 */
    val checkCrashedFormat: String,
    /** 「已经是最新版」：{0} 整包读数、{1} 数据读数。 */
    val upToDateFormat: String,
    /** 展开入口的两个标签（同一个入口的两种形状，不许两处各写各的）。 */
    val techExpandLabel: String,
    val techCollapseLabel: String,

    // ---------------------------------------------------------------- 设置页通道现状（玩家面三行）
    /** 第一行：{0} 地址条数、{1} 尾巴（自动或手动）。 */
    val canCheckFormat: String,
    /** 第一行的尾巴：自动通道开着。 */
    val canCheckAutoTail: String,
    /** 第一行的尾巴：玩家自己把自动检查关了。 */
    val canCheckManualTail: String,
    /** 第二行：尚未执行检查。 */
    val neverCheckedLine: String,
    /** 第二行的头：{0} 距今多久。 */
    val lastCheckHeadFormat: String,
    /** 第二行：检查已完成且无提醒事项。{0} 头。 */
    val checkOkLineFormat: String,
    /** 第二行：检查已完成但有提醒事项。{0} 头、{1} 提醒内容。 */
    val checkOkNotesLineFormat: String,
    /** 第二行：更新地址均无法连接。{0} 头、{1} 地址条数。 */
    val checkUnreachableLineFormat: String,
    /** 第二行：当前版本未提供可用的更新地址。{0} 头。 */
    val checkNoMirrorLineFormat: String,
    /** 第二行：检查过程出现异常。{0} 头。 */
    val checkCrashedLineFormat: String,
    /** 第二行：该次结果未记录（旧存档），仅报时间、不推断结论。{0} 头。 */
    val checkUnknownLineFormat: String,
    /** 第三行：有数据包等着下次启动应用。{0} 版本。 */
    val stagedYesLineFormat: String,
    /** 第三行：没有东西等着应用。 */
    val stagedNoLine: String,

    // ---------------------------------------------------------------- 设置页「看技术细节」展开层
    /** 包里没配地址时的那一行。 */
    val techAddressNoneLine: String,
    /** 地址行：{0} 条数、{1} 首选地址原文。 */
    val techAddressLineFormat: String,
    /** 本机版本读数行：{0} 整包、{1} 盘上数据。 */
    val techVersionsLineFormat: String,
    /** 上次查成功的时间读数：{0} 毫秒原值。 */
    val techLastSuccessLineFormat: String,
    /** 从没查成功过。 */
    val techNeverSucceededLine: String,
    /** 上次尝试（含失败）的读数行：{0} 毫秒原值、{1} 结果标记。 */
    val techAttemptLineFormat: String,
    /** 只有结果标记、没有独立尝试时间时的一行：{0} 标记。 */
    val techOutcomeLineFormat: String,
    /** 存的时候截过条数，展开层要承认少列了几条：{0} 少列的条数。 */
    val techDroppedNotesLineFormat: String,
    /**
     * 数据通道选件读数行（**只进展开层**，U11）：{0} 上次检查走的档位、{1} 盘上数据可信不可信、
     * {2} 发布那边这次给了哪几件数据包。玩家面三行现状一个字不许被它挤歪（见 [UpdateChannelReadout]）。
     */
    val techDataArtifactLineFormat: String,
    /** 数据通道档位：块级差量包（词表与 core 的 DataArtifactKind 一一对应，app 侧只做存取与读出）。 */
    val dataKindBlockDelta: String,
    /** 数据通道档位：文件级差量包。 */
    val dataKindFileDelta: String,
    /** 数据通道档位：全量数据包。 */
    val dataKindFull: String,
    /** 数据通道档位：这次没有数据包可下（只可能有整包更新）。 */
    val dataKindNone: String,
    /** 盘上数据可信（摘要在且自洽、这个基线没失败过）：差量才可能生效。 */
    val dataDiskTrusted: String,
    /** 盘上数据不可信：差量一律不选、直接走全量（「差量静默失效」就藏在这一格）。 */
    val dataDiskUntrusted: String,
    /** 发布那边一件数据包都没发（{2} 那格的空态）。 */
    val dataItemsNone: String,
    /**
     * 拒装挡板说明行（**只进展开层**，U13）：{0} 被拒的目标数据版本、{1} 这次改走的档位。
     * 挡板生效才上屏（词由档位表取），玩家面三行现状一个字不许被它挤歪。
     */
    val techRejectedFallbackLineFormat: String,
    /** 拒装挡板说明行、没有下一档可落的形态（全量也被拒过）：{0} 被拒的目标数据版本。 */
    val techRejectedSkipLineFormat: String,

    // ---------------------------------------------------------------- 弹窗标题与按钮（壳层原样搬）
    val dialogOfferTitle: String,
    /** 纯数据更新（整包已最新）时的弹窗标题：不叫「有可用的更新」，免得像又要装一遍安装包。 */
    val dialogOfferAssetsTitle: String,
    val dialogDeclineButton: String,
    val dialogNeverButton: String,
    val dialogDownloadButton: String,
    val dialogAckButton: String,
    val cellularDialogTitle: String,
    val cellularDeclineButton: String,
    val cellularProceedButton: String,
    val notificationsTitle: String,
    val notificationsDeclineButton: String,
    val notificationsGrantButton: String,
    /** 服务起不来时上屏的一句：{0} 异常名。 */
    val downloadStartFailedFormat: String,

    // ---------------------------------------------------------------- 下载进度正文与读数（EP4 U3b）
    /** 拿不到声明总量时的退化形态：{0} 那一件的名字、{1} 已下体积。 */
    val progressNoTotalFormat: String,
    /** 进度通知那一行正文：{0} 名字、{1} 百分比、{2} 已下、{3} 总量、{4} 还剩。 */
    val progressLineFormat: String,
    /** 「约 X/秒」那一格：{0} 已经按同一口径格式化好的体积读数。 */
    val speedClauseFormat: String,
    /** 「约 X 后下完」那一格：{0} 时长读数（[durationMinutesFormat] 那几档出来的串）。 */
    val etaClauseFormat: String,
    /** 时长的秒档：{0} 秒数（向上取整，绝不出现「0 秒」）。 */
    val durationSecondsFormat: String,
    /** 时长的分钟档：{0} 分钟数。 */
    val durationMinutesFormat: String,
    /** 时长「几分几秒」档：{0} 分、{1} 秒（只有不满 10 分钟才带秒）。 */
    val durationMinutesSecondsFormat: String,
    /** 时长的小时档：{0} 小时数。 */
    val durationHoursFormat: String,
    /** 时长「几小时几分」档：{0} 小时、{1} 分。 */
    val durationHoursMinutesFormat: String,

    // ---------------------------------------------------------------- 等待与换地址的状态行（EP4 U3b）
    /** 累计字节比峰值小（盘上前缀作废、从头重下）那一句。 */
    val stallRestartLine: String,
    /** 静默够久且整段跑够久：多半在换另一条更新地址。 */
    val stallSwitchLine: String,
    /** 静默够久但还没到换地址的量级：并列几种可能，不许写成断言。 */
    val stallWaitLine: String,
    /** 一个字节都没收到时的改口（不许宣称「已经下了一些」）。 */
    val stallNoByteLine: String,

    // ---------------------------------------------------------------- 开跑 / 完成 / 取消（EP4 U3b）
    /** 看得见通知栏时的开跑话术。 */
    val startedNotifyLine: String,
    /** 没给通知权限时的开跑话术：看得见的那部分照说，看不见的明说看不见。 */
    val startedNoNotifyLine: String,
    /** 整包下完（通知栏看得见）。 */
    val apkDoneLine: String,
    /**
     * 整包下完（通知栏看不见）：**已经直接把包交给系统安装器**，这里只说这一步与断点承诺，
     * 不许说「点这条通知」（那条通知在通知栏里根本看不见，说了就是把玩家往死路引）。
     */
    val apkDoneNoNotifyLine: String,
    /** 数据包下完：此刻还没生效，只许说下次启动自动应用。 */
    val assetsDoneLine: String,
    /** 取消（通知栏看得见）：断点保留必须说。 */
    val cancelledLine: String,
    /** 取消（通知栏看不见）：给游戏里那个停/续的入口。 */
    val cancelledNoNotifyLine: String,

    // ---------------------------------------------------------------- 失败与处置（EP4 U3b）
    /** 拆前旧形态：原话为空时的那一整句（点通知可重试）。 */
    val failedUnknownLine: String,
    /** 拆前旧形态：带原话时的那一整句：{0} 原话。 */
    val failedLegacyFormat: String,
    /** 原话为空时的「原因」那一格。 */
    val causeUnknownPhrase: String,
    /** 失败正文尾部那个重试入口（看得见通知栏那一支）。 */
    val retryInNotifyPhrase: String,
    /** 失败正文 = 原因 + 处置 + 入口：{0} 原因、{1} 处置句、{2} 重试入口。 */
    val failedBodyFormat: String,
    /** Toast 那一行（不带原话与术语）：{0} 处置句、{1} 重试入口。 */
    val failureToastFormat: String,
    /** 重试入口（看得见通知栏）。 */
    val retryEntryNotifyLine: String,
    /** 重试入口（看不见通知栏，只能回游戏里）。 */
    val retryEntryGameLine: String,
    /** 设置页「接着下」那颗按钮的正文：{0} 哪一份。 */
    val settingsRetryLabelFormat: String,
    /** 类别与原话都没有时的处置句。 */
    val actionUnknownLine: String,
    /** 所有候选地址都试完（没有类别）那一支的处置句。 */
    val actionAllSourcesLine: String,
    /** 磁盘不足（按原话再分出来的一支）：处理人是玩家自己。 */
    val actionSpaceLine: String,
    /** 网络类。 */
    val actionNetworkLine: String,
    /** 对面不接活类。 */
    val actionHttpStatusLine: String,
    /** 地址上找不到这个文件类。 */
    val actionMissingLine: String,
    /** 被拐到不安全路子（本层已自动避开）类。 */
    val actionPlainHttpLine: String,
    /** 下回来的与发出去的不是同一份类：玩家自己重试常常没用。 */
    val actionDigestLine: String,
    /** 内容比说好的多出一大截类：那条地址上的包是坏的。 */
    val actionOversizeLine: String,
    /** 本机读写没走通类。 */
    val actionLocalIoLine: String,

    // ---------------------------------------------------------------- 前台服务与通知（EP4 U3b）
    /** 进度通知的标题。 */
    val progressNotifyTitle: String,
    /** 取消/停止下载那颗按钮的名字：通知上的 action 与设置页「停止下载」共用同一颗（同一动作，不许两种说法）。 */
    val cancelNotifyAction: String,
    /** 通知渠道名（系统设置里玩家看得到）。 */
    val channelProgressName: String,
    /** 结果通知渠道名。 */
    val channelResultName: String,
    /** 整包就绪的结果通知标题。 */
    val apkReadyTitle: String,
    /** 数据包就位的结果通知标题。 */
    val assetsReadyTitle: String,
    /** 数据包没能登记成待应用时的标题。 */
    val assetsRegisterFailedTitle: String,
    /** 数据包没能登记时给玩家的「原因」那一格。 */
    val assetsRegisterProblemLine: String,
    /** 取消的结果通知标题。 */
    val cancelledNotifyTitle: String,
    /** 没下成的结果通知标题。 */
    val failedNotifyTitle: String,
    /** 下载线程直接抛了（一条都没开始跑）的标题。 */
    val notStartedNotifyTitle: String,
    /** 单飞闸挡住第二次下载时的 Toast。 */
    val downloadBusyToast: String,
    /** 前台通知起不来（本次没开下载）：{0} 异常名。 */
    val noNotifyBarStartFailedFormat: String,
    /** 点通知去装但文件已经不在了。 */
    val apkMissingToast: String,
    /** 系统安装器打不开：{0} 异常名。 */
    val installerOpenFailedFormat: String,

    // ---------------------------------------------------------------- 系统时限暂停（U2 病①，CONTRACT-UPD1-3LANE §2）
    // Android 15+（API 35）dataSync 型前台服务有时限；到点系统回调 onTimeout，本服务按
    // 「系统请求的取消」收尾（断点保留），通知与 Toast 用本节的新句 —— 与玩家自己的取消
    // （cancelledLine）分开说，玩家不该背系统的锅。
    /** 系统时限暂停的结果通知标题。 */
    val systemPausedNotifyTitle: String,
    /** 系统时限暂停的一句话（结果通知正文与 Toast 共用）：断点保留必须说。 */
    val systemPausedLine: String,
    // ---------------------------------------------------------------- 强制更新档（CONTRACT-UPD1-3LANE §2 U1 病②）
    /** 正文第一行（强制档命中时置顶）：发布方宣布此版本已停止支持，必须更新。 */
    val offerForceUpdateLine: String,
    // ---------------------------------------------------------------- 仅 Wi-Fi 下载 + 设置页内嵌进度（U5 P2-10 / P2-12）
    /** 「已设置为仅 Wi-Fi」弹窗的标题：蜂窝点下载时直接说明，不给「仍要下载」的绕行。 */
    val wifiOnlyCellularTitle: String,
    /** 「已设置为仅 Wi-Fi」弹窗的正文：说清不会下载、也不会再问一次，以及去哪儿改。 */
    val wifiOnlyCellularBody: String,
    /** 设置页通道现状里「正在下载」那一行：{0} 复用 [UpdateProgressText] 的进度正文。 */
    val downloadingLineFormat: String,

    // ---------------------------------------------------------------- 安装回执（U7 内联文案入表，CONTRACT-UPD2 §2 U7；U7b 2026-11-05 搬表）
    // 这 17 条原本是 PackageInstallerSession.kt 里的中文字面量（U7 波次契约 §2 因 UpdateCopy
    // 归 U5 独占而豁免内联）；U5 已合 main，本节按 AGENTS.md §6.1 收进三语表，
    // 中文档与收口前的内联原句逐字相同（U7 已定稿的玩家面话术，一个字都不动）。
    /** 会话装成功的结果通知标题（尽力面：成功那一拍进程多半已被系统杀掉）。 */
    val installDoneTitle: String,
    /** 会话装成功的一句话（结果通知正文与 Toast 共用）。 */
    val installDoneLine: String,
    /** 玩家在系统确认框里点了退的结果通知标题。 */
    val installCancelledTitle: String,
    /** 用户取消的一句话：更新包还留着 + 点通知重装的重试入口。 */
    val installCancelledLine: String,
    /** 安装失败的结果通知标题。 */
    val installFailedTitle: String,
    /** 失败结果通知正文：{0} 失败摘要（[installBlockedLine] 那族摘要句之一）。 */
    val installFailedBodyFormat: String,
    /** 会话开不起来（创建/写入/提交抛错）：{0} 异常名（留给取证的读数，不是术语）。 */
    val installSessionOpenFailedFormat: String,
    /** 系统确认框拉不起来的整句（前缀与解释合成一句，槽位只剩异常名）：{0} 异常名。 */
    val installConfirmOpenFailedFormat: String,
    /** 递给会话的文件不在更新目录里或名字不合法时的一句。 */
    val installPathInvalidLine: String,
    /** STATUS_FAILURE_BLOCKED 的摘要：被系统或设备策略拦下。 */
    val installBlockedLine: String,
    /** STATUS_FAILURE_CONFLICT 的摘要：与已装应用签名冲突，卸载会清存档。 */
    val installConflictLine: String,
    /** STATUS_FAILURE_INCOMPATIBLE 的摘要：设备不兼容或签名不一致。 */
    val installIncompatibleLine: String,
    /** STATUS_FAILURE_INVALID 的摘要：安装包无效或已损坏。 */
    val installInvalidLine: String,
    /** STATUS_FAILURE_STORAGE 的摘要：存储空间不足。 */
    val installStorageLine: String,
    /** STATUS_FAILURE_TIMEOUT 的摘要：安装超时。 */
    val installTimeoutLine: String,
    /**
     * 通用/未知失败码的摘要：{0} 状态码原值。
     *
     * 这一句**只填在 [installFailedBodyFormat] 的槽里**（通知正文 = 外层「安装失败：」+ 这句），
     * 所以它自己不许再以「安装失败」开头：U7 原拼接得到「安装失败：安装失败（错误码 1）」，
     * U6b 去掉内层重复的前缀，状态码那一格原样保留（它是排查用的唯一读数）。
     */
    val installGenericFailedFormat: String,
    /** 失败摘要带系统原话时的套壳：{0} 摘要句、{1} 系统原话（原话原样附带、不翻译）。 */
    val installSystemMessageFormat: String,
) {

    /**
     * 句式占位符 `{0}` / `{1}`：单趟替换，参数本身带 `{n}`（例如源返回的原话）不会被二次展开，
     * 也不会把别的位置顶掉。
     *
     * **花括号必须走字符类而不是反斜杠转义**：Android 的正则是 ICU 引擎，转义写法直接抛
     * `PatternSyntaxException`（OpenJDK 却容忍）—— 这个坑在 `SettingsStrings` 的同一处
     * 正则上真机实测过一次，本表照抄那个写法。
     */
    fun fill(template: String, vararg args: Any): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言：不许在更新侧再造一套语言判定（契约 §1 硬规矩 1）。 */
        fun of(lang: SettingsLanguage): UpdateCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：每个值都与搬动前的硬编码逐字相同（中文零漂移的对拍右轴）。 */
        val ZH: UpdateCopy = UpdateCopy(
            lang = SettingsLanguage.ZH,
            apkLabel = "新版本",
            assetsLabel = "数据包",
            justNow = "刚刚",
            minutesAgoFormat = "{0} 分钟前",
            hoursAgoFormat = "{0} 小时前",
            daysAgoFormat = "{0} 天前",
            problemSeparator = "；",
            checkingLabel = "正在检查更新…",
            reminderRestorePhrase = "想改主意可在设置页「启动」里恢复提醒",
            cellularConfirmBodyFormat =
                "当前为移动数据网络，非 Wi-Fi。本次「{0}」需下载 {1}，将计入移动数据流量。" +
                    "下载中断后已获取部分将保留，再次确认可从断点继续。是否立即开始下载？",
            offerApkHeadFormat = "新版本 {0}（当前版本 {1}）",
            offerApkSizeLineFormat = "{0}，需下载 {1}",
            offerApkBlockedLineFormat = "{0}，但当前设备无法安装：{1}",
            offerDeltaMarker = "（仅包含变更部分，体积显著小于完整数据包）",
            offerAssetsLineFormat = "数据包 {0}（当前数据版本 {1}），需下载 {2}{3}，将在下次启动游戏时自动应用",
            offerAssetsOnlyExplainerLine =
                "当前安装的应用已是最新版本（{0}）；本次更新内容为独立分发的游戏数据（脚本与图像资源），" +
                    "无需重新安装应用。",
            offerCellularLine = "当前未连接 Wi-Fi：继续操作将使用移动数据，并需再次确认",
            offerNoteLineFormat = "更新说明：{0}",
            offerExpandLabel = "查看说明",
            offerExpandCountFormat = "{0}（发布方提供 {1} 条提示）",
            warningsNoteFormat =
                "发布方提供的更新信息中，有 {0} 处内容当前版本无法识别；已按可识别部分继续处理，详细原因见「技术细节」。",
            skippedToastFormat = "已不再提醒版本 {0}；出了更新的版本仍会提醒。{1}。",
            skippedNoteFormat = "已跳过版本 {0} 的自动提醒；新版本照常提醒，{1}",
            checkBusyFormat = "{0}，一次只查一路；等这一条的结果出来再点，连点不会更快。",
            notificationRationaleFormat =
                "本次更新需下载 {0}，通常需要数十分钟。" +
                    "开启通知权限后，下载进度、取消入口与完成提示将在通知栏显示，可退出当前界面。" +
                    "未开启通知仍可正常下载，但通知栏不显示进度。",
            noBuiltInMirrorLine =
                "这个包没有内置更新源（出包时没带更新地址），自动更新整条通道是关的——这不是你网络的问题。",
            allMirrorsFailedPhrase = "所有更新源都连不上",
            checkFailedFormat = "已配置 {0} 条更新源，这次一条都连不上（{1}）。等网络恢复了再查一次就行。",
            checkCrashedFormat = "检查更新时出了意外（{0}），游戏不受影响，稍后再试一次",
            upToDateFormat = "已经是最新版（整包 {0}，数据 {1}）",
            techExpandLabel = "看技术细节",
            techCollapseLabel = "收起技术细节",
            canCheckFormat = "更新地址有 {0} 条，会自动帮你查新版；{1}",
            canCheckAutoTail = "查到新版会先弹窗问你，不会自己下载。",
            canCheckManualTail = "但你在设置页把自动检查关了，只有手动点「立即检查更新」才会查。",
            neverCheckedLine = "尚未执行检查：启动游戏后将自动检查一次。",
            lastCheckHeadFormat = "最近一次检查：{0}",
            checkOkLineFormat = "{0}。检查已完成，更新地址可正常访问，未发现需要处理的事项。",
            checkOkNotesLineFormat = "{0}。检查已完成，更新地址可正常访问；{1}",
            checkUnreachableLineFormat =
                "{0}。{1} 个更新地址均无法连接，网络恢复后将自动重新检查；详细原因见「技术细节」。",
            checkNoMirrorLineFormat = "{0}。当前版本未提供可用的更新地址，与本机网络状况无关。",
            checkCrashedLineFormat = "{0}。检查过程出现异常，游戏不受影响，下一次检查将照常执行。",
            checkUnknownLineFormat = "{0}。该次检查结果未记录，下一次检查完成后将自动补充。",
            stagedYesLineFormat =
                "已经下好一份数据包（版本 {0}），下次启动游戏时会自动装上；在那之前你玩到的还是现在这份数据。",
            stagedNoLine = "没有等着装的数据包。",
            techAddressNoneLine = "更新地址：包里没配",
            techAddressLineFormat = "更新地址 {0} 条，首选 {1}",
            techVersionsLineFormat = "本机整包版本 {0}，盘上数据版本 {1}",
            techLastSuccessLineFormat = "上次查成功的时间读数 {0}",
            techNeverSucceededLine = "从没查成功过",
            techAttemptLineFormat = "上次尝试（含失败）的时间读数 {0}，结果标记 {1}",
            techOutcomeLineFormat = "上次检查结果标记 {0}",
            techDroppedNotesLineFormat = "（其余 {0} 处原话这里没存下）",
            techDataArtifactLineFormat = "数据通道选件：上次检查走的是{0}，盘上数据{1}；这次发布给的包：{2}",
            dataKindBlockDelta = "块级差量",
            dataKindFileDelta = "文件级差量",
            dataKindFull = "全量数据包",
            dataKindNone = "没有数据包可下",
            dataDiskTrusted = "可信",
            dataDiskUntrusted = "不可信",
            dataItemsNone = "一件都没有",
            techRejectedFallbackLineFormat = "上一版数据包（版本{0}）校验没过被拒收，这次改取{1}",
            techRejectedSkipLineFormat = "上一版数据包（版本{0}）校验没过被拒收，这次暂不取数据包",
            dialogOfferTitle = "发现可用更新",
            dialogOfferAssetsTitle = "发现游戏数据更新",
            dialogDeclineButton = "暂不更新",
            dialogNeverButton = "忽略此版本",
            dialogDownloadButton = "下载",
            dialogAckButton = "确定",
            cellularDialogTitle = "当前为移动数据网络",
            cellularDeclineButton = "取消",
            cellularProceedButton = "确认下载",
            notificationsTitle = "下载进度通知",
            notificationsDeclineButton = "不开启通知",
            notificationsGrantButton = "开启通知",
            downloadStartFailedFormat = "下载没能启动（{0}），稍后再试一次",
            progressNoTotalFormat = "{0}下载中 · 已下 {1}",
            progressLineFormat = "{0}下载中 {1}% · 已下 {2} / 共 {3} · 还剩 {4}",
            speedClauseFormat = "约 {0}/秒",
            etaClauseFormat = "{0}后下完",
            durationSecondsFormat = "约 {0} 秒",
            durationMinutesFormat = "约 {0} 分钟",
            durationMinutesSecondsFormat = "约 {0} 分 {1} 秒",
            durationHoursFormat = "约 {0} 小时",
            durationHoursMinutesFormat = "约 {0} 小时 {1} 分",
            stallRestartLine = "刚下的那一截没能接着用，正在重新下一遍（不是你动了什么，不用重开）",
            stallSwitchLine = "这条更新地址一直没动静，多半是在换另一条更新地址接着试（已经下好的部分留着）",
            stallWaitLine =
                "暂时没有新内容进来：多半在等一等接着下，也可能在换另一条地址试，或在本机重读已经下好的部分" +
                    "（不用你重开）",
            stallNoByteLine = "一个内容都还没收到：正在连更新地址，可能还要等一小会儿才动起来（不用你重开）",
            startedNotifyLine = "开始下载了，进度在通知栏；可以离开这个游戏界面",
            startedNoNotifyLine =
                "开始下载了。没给通知权限：通知栏里看不到进度和完成提醒（下载本身照常跑，断了也留断点）；" +
                    "想看到进度的话，去系统设置里允许本应用发通知后再点一次，会从断点接着下",
            apkDoneLine = "更新包已下载好，点这条通知就能继续安装",
            apkDoneNoNotifyLine =
                "更新包已下载好。没给通知权限：结果提醒在通知栏里看不见，" +
                    "已经直接把它交给系统安装器，按屏幕上的提示完成安装即可（一个字节都不重下）",
            assetsDoneLine = "数据包已下载好，下次启动游戏时自动应用",
            cancelledLine = "下载已取消。已下载的部分留着了，再点一次「下载」会从断的地方接着下",
            cancelledNoNotifyLine =
                "下载已经停下。已下载的部分留着了，再点一次「下载」会从断的地方接着下" +
                    "（没给通知权限：这条提醒在通知栏里看不见）",
            failedUnknownLine = "更新下载失败：原因未知，点这条通知可重试",
            failedLegacyFormat = "更新下载失败：{0}（点这条通知可重试）",
            causeUnknownPhrase = "原因一时看不出来",
            retryInNotifyPhrase = "点这条通知可以再来一遍",
            failedBodyFormat = "更新下载失败：{0}\n你可以这样补救：{1}（{2}）",
            failureToastFormat = "更新没能下下来。你可以这样补救：{0}（{1}）",
            retryEntryNotifyLine = "点这条通知就能再试一次",
            retryEntryGameLine = "回游戏里再点一次「下载」，会从断的地方接着下",
            settingsRetryLabelFormat = "接着下刚才没下完的那份「{0}」（从断的地方续，不重头下）",
            actionUnknownLine = "过一会儿再试一次；每次都卡住就等下一次发版，游戏照常玩",
            actionAllSourcesLine =
                "连着几条更新地址都没能下成：过一会儿再试一次，已经下好的部分留着；还是不行就等下一次发版",
            actionSpaceLine = "这台手机的地方不够了：清掉不用的应用或照片视频，腾出空间后再试；已下的断点留着",
            actionNetworkLine = "网络晃了一下：过一两分钟再试一次就好，已经下好的部分留着不白下",
            actionHttpStatusLine = "更新那边这会儿不接活（多半在忙）：过十几分钟再试一次，不用重头下",
            actionMissingLine = "这条更新地址上暂时找不到这个文件：等一会儿换另一条再试，或者直接等下一次发版",
            actionPlainHttpLine =
                "这次指的更新地址被拐到了不安全的路子上，已经自动避开：换个网络（比如自家无线）再试",
            actionDigestLine =
                "下回来的东西和发出来的不是同一份，已经丢掉：这类你自己重试常常也没用，等下一次发版最稳",
            actionOversizeLine = "更新地址发来的内容比说好的多出一大截，已经中止：这条地址上的包是坏的，等下一次发版",
            actionLocalIoLine =
                "这台手机上的文件读写没走通：重启一次手机后再试；反复如此就用文件管理器清掉下载目录里的旧更新文件",
            progressNotifyTitle = "正在下载更新",
            cancelNotifyAction = "取消下载",
            channelProgressName = "更新下载进度",
            channelResultName = "更新下载结果",
            apkReadyTitle = "更新包已就绪",
            assetsReadyTitle = "数据包已就位",
            assetsRegisterFailedTitle = "数据包没能登记",
            assetsRegisterProblemLine = "下载完成了，但没能登记成「下次启动应用」",
            cancelledNotifyTitle = "下载已取消",
            failedNotifyTitle = "下载没成功",
            notStartedNotifyTitle = "下载没能开始",
            downloadBusyToast = "已经有一个下载在跑，请等它结束",
            noNotifyBarStartFailedFormat =
                "看不到通知栏（{0}），本次没有开始下载；请到系统设置允许本应用发通知后再点一次",
            apkMissingToast = "安装包不在了，请回游戏里重新下载",
            installerOpenFailedFormat = "系统安装器打不开（{0}），请用文件管理器手动安装",
            systemPausedNotifyTitle = "系统暂停了下载",
            systemPausedLine = "系统暂停了下载（系统时限），断点已保留，稍后可继续",
            offerForceUpdateLine = "当前版本已停止支持，必须完成更新：请下载并安装新版本。",
            wifiOnlyCellularTitle = "已设置为仅 Wi-Fi 下载",
            wifiOnlyCellularBody =
                "当前为移动数据网络，本次下载将不会开始，也不会再次提示确认。" +
                    "如需使用移动数据下载，请在设置页关闭「仅 Wi-Fi 下载」。",
            downloadingLineFormat = "正在下载：{0}",
            installDoneTitle = "更新安装完成",
            installDoneLine = "新版本已经装好了",
            installCancelledTitle = "安装已取消",
            installCancelledLine = "你取消了安装。更新包还留着，点这条通知可以再装一次",
            installFailedTitle = "更新安装没成功",
            installFailedBodyFormat = "安装失败：{0}",
            installSessionOpenFailedFormat = "安装会话没跑起来：{0}",
            installConfirmOpenFailedFormat = "安装会话没跑起来：确认框没拉起来（{0}），点这条通知可以再试",
            installPathInvalidLine = "安装包不在更新目录里或名字不合法，请回游戏重新下载",
            installBlockedLine = "安装被系统或设备策略拦下：看看是不是装了设备管理或安全软件在挡",
            installConflictLine = "与设备上已装的应用冲突（签名不同）：要先卸载旧包才能装——卸载会清掉存档，请先备份",
            installIncompatibleLine = "与这台设备不兼容，或与已装包的签名不一致：覆盖升级要同一签名，先卸载旧包会清掉存档，请先备份",
            installInvalidLine = "安装包无效或已损坏：回游戏里重新下载一次再装",
            installStorageLine = "存储空间不足：请腾出一些空间后再试",
            installTimeoutLine = "安装超时：请再试一次",
            installGenericFailedFormat = "原因不明（错误码 {0}）",
            installSystemMessageFormat = "{0}（系统原话：{1}）",
        )

        /** English：结构与 [ZH] 逐条同形（同样几行、同样几个读数），措辞另写，不抄中文。 */
        val EN: UpdateCopy = UpdateCopy(
            lang = SettingsLanguage.EN,
            apkLabel = "new version",
            assetsLabel = "data pack",
            justNow = "just now",
            minutesAgoFormat = "{0} minutes ago",
            hoursAgoFormat = "{0} hours ago",
            daysAgoFormat = "{0} days ago",
            problemSeparator = "; ",
            checkingLabel = "Checking for updates...",
            reminderRestorePhrase = "changed your mind? Turn update reminders back on in the Startup card of Settings",
            cellularConfirmBodyFormat =
                "This device is on mobile data, not Wi-Fi. The {0} download is {1} and will count " +
                    "against the mobile data allowance. If interrupted, the completed portion is retained " +
                    "and can be resumed. Proceed with the download?",
            offerApkHeadFormat = "Version {0} available (current version {1})",
            offerApkSizeLineFormat = "{0}; {1} to download",
            offerApkBlockedLineFormat = "{0}; however, installation is not possible on this device: {1}",
            offerDeltaMarker = " (contains only changed data, considerably smaller than the full data pack)",
            offerAssetsLineFormat = "Data pack {0} (current data version {1}): {2} to download{3}; " +
                "applied automatically on the next game launch",
            offerAssetsOnlyExplainerLine =
                "The installed application is already up to date ({0}). This update concerns the separately " +
                    "distributed game data (scripts and graphics); reinstallation is not required.",
            offerCellularLine = "Wi-Fi is not connected: continuing will use mobile data and requires confirmation",
            offerNoteLineFormat = "Release notes: {0}",
            offerExpandLabel = "View details",
            offerExpandCountFormat = "{0} ({1} advisory notes from the release)",
            warningsNoteFormat =
                "{0} entries in the release information could not be parsed by this build; " +
                    "processing continued with the remaining entries. See Technical details for the full report.",
            skippedToastFormat = "Version {0} will not be reported again; newer versions still will be. {1}.",
            skippedNoteFormat = "Update reminders for version {0} are silenced; newer versions still remind you, {1}",
            checkBusyFormat = "{0} - one check at a time; wait for this result before tapping again, tapping more is not faster.",
            notificationRationaleFormat =
                "This update requires a {0} download and typically takes several tens of minutes. " +
                    "With notification permission enabled, progress, the cancel action and the completion " +
                    "notice are shown in the notification bar, so this screen may be left. " +
                    "The download also proceeds without permission, but progress will not appear there.",
            noBuiltInMirrorLine =
                "This build has no update source built in (the addresses were not passed when it was packaged), " +
                    "so the whole automatic update path is off - this is not a problem with your network.",
            allMirrorsFailedPhrase = "no update source could be reached",
            checkFailedFormat = "{0} update sources are configured and none of them answered this time ({1}). " +
                "Check again once the network is back.",
            checkCrashedFormat = "The update check hit an unexpected error ({0}); the game is unaffected, try again later",
            upToDateFormat = "Already the latest version (app {0}, data {1})",
            techExpandLabel = "Show technical details",
            techCollapseLabel = "Hide technical details",
            canCheckFormat = "{0} update addresses are set up and new versions are looked for automatically; {1}",
            canCheckAutoTail = "when one turns up it asks you in a dialog first and never downloads by itself.",
            canCheckManualTail = "but you switched automatic checks off in Settings, so nothing is looked for " +
                "until you press Check for updates now on this page.",
            neverCheckedLine = "No check has been performed yet. One will run automatically when the game starts.",
            lastCheckHeadFormat = "Most recent check: {0}",
            checkOkLineFormat = "{0}. The check completed, update addresses were reachable, and no action is required.",
            checkOkNotesLineFormat = "{0}. The check completed and update addresses were reachable; {1}",
            checkUnreachableLineFormat =
                "{0}: none of the {1} update addresses could be reached. The check will run again automatically " +
                    "when the network recovers (the details are under Technical details).",
            checkNoMirrorLineFormat = "{0}: this build provides no usable update address; this is unrelated to your network.",
            checkCrashedLineFormat =
                "{0}: the check encountered an unexpected error. The game is unaffected and the next check will run as scheduled.",
            checkUnknownLineFormat = "{0}: the result of that check was not recorded; it will be filled in after the next check.",
            stagedYesLineFormat =
                "A data pack (version {0}) is already downloaded and applies itself the next time the game starts; " +
                    "until then you keep playing the data you have now.",
            stagedNoLine = "Nothing is waiting to be applied.",
            techAddressNoneLine = "Update addresses: none built in",
            techAddressLineFormat = "{0} update addresses, preferred {1}",
            techVersionsLineFormat = "On this device: app version {0}, data version {1} on disk",
            techLastSuccessLineFormat = "Last successful check reading {0}",
            techNeverSucceededLine = "No check has got through yet",
            techAttemptLineFormat = "Last attempt (failures included) reading {0}, result marker {1}",
            techOutcomeLineFormat = "Last check result marker {0}",
            techDroppedNotesLineFormat = "({0} further original lines were not stored here)",
            techDataArtifactLineFormat =
                "Data channel choice: the last check used {0}; the data on disk {1}; packages offered this time: {2}",
            dataKindBlockDelta = "the block-level update",
            dataKindFileDelta = "the file-level update",
            dataKindFull = "the full data pack",
            dataKindNone = "no data pack",
            dataDiskTrusted = "is trusted",
            dataDiskUntrusted = "is not trusted",
            dataItemsNone = "none",
            techRejectedFallbackLineFormat =
            "The version {0} data pack failed verification and was rejected; this time fetching {1} instead",
            techRejectedSkipLineFormat =
            "The version {0} data pack failed verification and was rejected; holding off on the data pack this time",
            dialogOfferTitle = "Update available",
            dialogOfferAssetsTitle = "Game data update available",
            dialogDeclineButton = "Not now",
            dialogNeverButton = "Ignore this version",
            dialogDownloadButton = "Download",
            dialogAckButton = "OK",
            cellularDialogTitle = "Mobile data connection in use",
            cellularDeclineButton = "Cancel",
            cellularProceedButton = "Confirm download",
            notificationsTitle = "Download progress notifications",
            notificationsDeclineButton = "Continue without notifications",
            notificationsGrantButton = "Enable notifications",
            downloadStartFailedFormat = "The download could not start ({0}), try again in a moment",
            progressNoTotalFormat = "{0} downloading · {1} done so far",
            progressLineFormat = "{0} downloading · {1}% · {2} of {3} done · {4} left",
            speedClauseFormat = "about {0} per second",
            etaClauseFormat = "{0} to finish",
            durationSecondsFormat = "about {0} sec",
            durationMinutesFormat = "about {0} min",
            durationMinutesSecondsFormat = "about {0} min {1} sec",
            durationHoursFormat = "about {0} hr",
            durationHoursMinutesFormat = "about {0} hr {1} min",
            stallRestartLine =
                "The part that just came down could not be stitched on, so it is being fetched a second time " +
                    "(you did not do anything, no need to reopen this)",
            stallSwitchLine =
                "This update address has been quiet for a while, so it is probably moving on to the next one " +
                    "to keep trying (what already came down is kept)",
            stallWaitLine =
                "Nothing new has come in for a moment: it is probably waiting before continuing, it may also be " +
                    "trying another address, or re-reading on this phone what already came down (no need to reopen this)",
            stallNoByteLine =
                "Nothing has arrived yet: it is still reaching the update address, it may take a little while " +
                    "before anything moves (no need to reopen this)",
            startedNotifyLine =
                "The download has started, progress shows in the notification bar; you can leave this game screen",
            startedNoNotifyLine =
                "The download has started. Notifications are off: the notification bar will not show the progress " +
                    "or the all-done alert (the download itself runs normally and a drop keeps its place); to see it, " +
                    "allow notifications for this app in system settings and tap Download once more, it picks up where it stopped",
            apkDoneLine = "The update file is downloaded - tap this notification to go on and install it",
            apkDoneNoNotifyLine =
                "The update file is downloaded. Notifications are off, so this alert stays out of the notification " +
                    "bar; the file has been handed straight to the system installer - follow the prompt on screen " +
                    "to install it (not a single byte is downloaded again)",
            assetsDoneLine = "The data pack is downloaded and applies by itself the next time the game starts",
            cancelledLine =
                "The download was cancelled. What already came down is kept - tap Download once more and it continues " +
                    "from that point",
            cancelledNoNotifyLine =
                "The download has stopped. What already came down is kept - tap Download once more and it continues " +
                    "from that point (notifications are off, so this alert stays out of the notification bar)",
            failedUnknownLine = "The update download failed: no reason found, tap this notification to retry",
            failedLegacyFormat = "The update download failed: {0} (tap this notification to retry)",
            causeUnknownPhrase = "no reason shows up right now",
            retryInNotifyPhrase = "tap this notification to try again",
            failedBodyFormat = "The update download failed: {0}\nWhat you can do now: {1} ({2})",
            failureToastFormat = "The update did not come down. What you can do now: {0} ({1})",
            retryEntryNotifyLine = "tap this notification and it tries again",
            retryEntryGameLine = "go back into the game and tap Download once more, it picks up where it stopped",
            settingsRetryLabelFormat =
                "Finish the {0} that did not come down (continues from where it stopped, nothing starts over)",
            actionUnknownLine =
                "try again in a little while; if it stalls every time, wait for the next release and keep playing meanwhile",
            actionAllSourcesLine =
                "several update addresses failed one after another: try again in a while, what already came down is " +
                    "kept; if it still fails, wait for the next release",
            actionSpaceLine =
                "this phone does not have enough room left: clear out apps or photos and videos you do not need, then " +
                    "try again; the part already downloaded stays",
            actionNetworkLine = "the connection wobbled: try again in a minute or two, what already came down is not lost",
            actionHttpStatusLine =
                "the update side is not taking work right now (probably busy): try again in ten or fifteen minutes, no " +
                    "need to start over",
            actionMissingLine =
                "that file is not on this update address at the moment: wait a bit and try another address, or just " +
                    "wait for the next release",
            actionPlainHttpLine =
                "the address this was pointed at tried to take an unsafe route and it was already dodged: switch " +
                    "networks (your home Wi-Fi, for example) and try again",
            actionDigestLine =
                "what came down was not the same file that went out, so it was thrown away: trying it yourself rarely " +
                    "helps here, waiting for the next release is the surest fix",
            actionOversizeLine =
                "the update address sent far more than the size it promised, so it was stopped: the file on that " +
                    "address is broken, wait for the next release",
            actionLocalIoLine =
                "reading or writing files on this phone did not go through: restart the phone and try again; if it " +
                    "keeps happening, use a file manager to clear the old update files out of the download folder",
            progressNotifyTitle = "Downloading the update",
            cancelNotifyAction = "Cancel download",
            channelProgressName = "Update download progress",
            channelResultName = "Update download alerts",
            apkReadyTitle = "The update file is ready",
            assetsReadyTitle = "The data pack is in place",
            assetsRegisterFailedTitle = "The data pack could not be written down for the next start",
            assetsRegisterProblemLine = "it came all the way down but did not get written down as the pack to apply at next start",
            cancelledNotifyTitle = "Download cancelled",
            failedNotifyTitle = "The download did not work",
            notStartedNotifyTitle = "The download never started",
            downloadBusyToast = "One download is already running, wait for it to finish",
            noNotifyBarStartFailedFormat =
                "The notification bar is not reachable ({0}), so nothing started this time; allow notifications for " +
                    "this app in system settings and tap once more",
            apkMissingToast = "The installer file is not there anymore, go back into the game and download it again",
            installerOpenFailedFormat = "The system installer would not open ({0}); install it yourself with a file manager",
            systemPausedNotifyTitle = "The system paused the download",
            systemPausedLine =
                "The system paused the download (system time limit); what already came down is kept, " +
                    "you can continue later",
            offerForceUpdateLine =
                "This version is no longer supported and the update is mandatory. Download and install the new version.",
            wifiOnlyCellularTitle = "Set to Wi-Fi only",
            wifiOnlyCellularBody =
                "This device is on mobile data. The download will not start and no further confirmation " +
                    "will be shown. To download over mobile data, disable Wi-Fi only in Settings.",
            downloadingLineFormat = "Downloading: {0}",
            installDoneTitle = "The update is installed",
            installDoneLine = "The new version is now on this device.",
            installCancelledTitle = "The install was cancelled",
            installCancelledLine = "You cancelled the install. The update file is kept; tap this notification to install it again.",
            installFailedTitle = "The update did not install",
            installFailedBodyFormat = "The install failed: {0}",
            installSessionOpenFailedFormat = "The install could not get started: {0}",
            installConfirmOpenFailedFormat =
                "The install could not get started: the confirmation prompt did not come up ({0}); " +
                    "tap this notification to try again",
            installPathInvalidLine =
                "The update file is not in the update folder or its name is invalid: " +
                    "go back into the game and download it again",
            installBlockedLine =
                "The system or a device policy stopped the install: check whether a device-management or security app is in the way",
            installConflictLine =
                "It clashes with the app already installed on this device (different signatures): the old package has to be " +
                    "removed first, and removing it clears your saves - back them up before you do",
            installIncompatibleLine =
                "It does not fit this device, or its signature does not match the installed app: an in-place update needs " +
                    "the same signature; removing the old package clears your saves, so back them up first",
            installInvalidLine = "The update file is invalid or damaged: go back into the game and download it again before installing",
            installStorageLine = "This device is out of storage: free up some space and try again",
            installTimeoutLine = "The install timed out: please try again",
            installGenericFailedFormat = "the system gave no reason (error code {0})",
            installSystemMessageFormat = "{0} (the system said: {1})",
        )

        /** 日本語：结构与 [ZH] 逐条同形；术语面同样不出现技术词。 */
        val JA: UpdateCopy = UpdateCopy(
            lang = SettingsLanguage.JA,
            apkLabel = "新しいアプリ",
            assetsLabel = "データパック",
            justNow = "たった今",
            minutesAgoFormat = "{0} 分前",
            hoursAgoFormat = "{0} 時間前",
            daysAgoFormat = "{0} 日前",
            problemSeparator = "；",
            checkingLabel = "更新を確認しています…",
            reminderRestorePhrase = "気が変わったら設定の「起動」で更新通知を元に戻せます",
            cellularConfirmBodyFormat =
                "現在は Wi-Fi ではなくモバイルデータ通信です。今回の「{0}」は {1} の取得が必要で、" +
                    "通信量に計上されます。取得が中断された場合、取得済みの部分は保持され、" +
                    "再開時に続きから取得します。取得を開始しますか。",
            offerApkHeadFormat = "新バージョン {0}（現在のバージョン {1}）",
            offerApkSizeLineFormat = "{0}、取得量 {1}",
            offerApkBlockedLineFormat = "{0}。ただし、この端末にはインストールできません：{1}",
            offerDeltaMarker = "（変更部分のみを含み、データ全体より大幅に小さくなります）",
            offerAssetsLineFormat = "データパック {0}（現在のデータ版 {1}）、取得量 {2}{3}、次回のゲーム起動時に自動で適用されます",
            offerAssetsOnlyExplainerLine =
                "インストール済みのアプリはすでに最新版です（{0}）。今回の更新対象は、アプリとは別に配布される" +
                    "ゲームデータ（スクリプトおよび画像）であり、アプリの再インストールは不要です。",
            offerCellularLine = "Wi-Fi に接続していません：続行するとモバイルデータ通信を使用し、追加の確認が必要です",
            offerNoteLineFormat = "更新内容：{0}",
            offerExpandLabel = "詳細を見る",
            offerExpandCountFormat = "{0}（配布側から {1} 件の通知事項）",
            warningsNoteFormat =
                "配布側の更新情報に、このビルドが解析できない形式が {0} 件ありました。" +
                    "解析可能な情報のみで処理を続行しました。詳細は「技術詳細」をご確認ください。",
            skippedToastFormat = "バージョン {0} は次回から通知しません。より新しいバージョンは引き続き通知します。{1}。",
            skippedNoteFormat = "バージョン {0} の自動通知をスキップ中です。新しいバージョンは通常どおり通知します。{1}",
            checkBusyFormat = "{0}、確認は一度に1本だけです。結果が出てから押してください。連打しても早くは終わりません。",
            notificationRationaleFormat =
                "今回の更新では {0} の取得が必要で、通常は数十分を要します。" +
                    "通知を許可すると、進捗、キャンセル操作、完了の案内が通知バーに表示され、" +
                    "この画面を離れることができます。許可しない場合も取得は可能ですが、" +
                    "通知バーには進捗が表示されません。",
            noBuiltInMirrorLine =
                "このビルドには更新先が組み込まれていません（作成時に更新先を渡さなかったため）、自動更新の経路は全区間オフです——通信環境の問題ではありません。",
            allMirrorsFailedPhrase = "すべての更新先に接続できませんでした",
            checkFailedFormat = "更新先を {0} 件設定していますが、今回は1件も接続できませんでした（{1}）。通信が回復したらもう一度確認してください。",
            checkCrashedFormat = "更新の確認で予期しないエラーがありました（{0}）。ゲームは影響を受けません。あとでもう一度お試しください",
            upToDateFormat = "すでに最新版です（アプリ {0}、データ {1}）",
            techExpandLabel = "技術詳細を見る",
            techCollapseLabel = "技術詳細を閉じる",
            canCheckFormat = "更新先が {0} 件あり、新しい版は自動で探します；{1}",
            canCheckAutoTail = "見つかってもまず確認が出るため、勝手に取得することはありません。",
            canCheckManualTail = "ただし設定ページで自動確認をオフにしているので、「いま更新を確認」を押したときだけ調べます。",
            neverCheckedLine = "確認はまだ行われていません。ゲーム起動時に自動的に1回実行します。",
            lastCheckHeadFormat = "直近の確認：{0}",
            checkOkLineFormat = "{0}。確認は完了し、更新先への接続は正常です。対応が必要な事項はありません。",
            checkOkNotesLineFormat = "{0}。確認は完了し、更新先への接続は正常です；{1}",
            checkUnreachableLineFormat =
                "{0}：更新先 {1} 件のいずれにも接続できませんでした。回線の復旧後に自動的に再確認します（詳細は技術詳細に記載されています）。",
            checkNoMirrorLineFormat = "{0}：このバージョンには利用可能な更新先がありません。通信環境とは関係ありません。",
            checkCrashedLineFormat =
                "{0}：確認の実行中に予期しないエラーが発生しました。ゲームへの影響はなく、次回の確認は予定どおり実行されます。",
            checkUnknownLineFormat = "{0}：その回の確認結果は保存されていません。次回の確認後に自動的に補われます。",
            stagedYesLineFormat =
                "データパック（バージョン {0}）の取得が済んでいます。次回のゲーム起動時に自動で適用されます；それまでは今のデータのまま遊べます。",
            stagedNoLine = "適用待ちのデータパックはありません。",
            techAddressNoneLine = "更新先：組み込まれていません",
            techAddressLineFormat = "更新先 {0} 件、第一候補 {1}",
            techVersionsLineFormat = "この端末のアプリ版 {0}、ディスク上のデータ版 {1}",
            techLastSuccessLineFormat = "前回成功時刻の生读数 {0}",
            techNeverSucceededLine = "一度も正常に確認できていません",
            techAttemptLineFormat = "前回試行（失敗を含む）の生读数 {0}、結果マーク {1}",
            techOutcomeLineFormat = "前回確認の結果マーク {0}",
            techDroppedNotesLineFormat = "（残り {0} 件の原文はここに保存されていません）",
            techDataArtifactLineFormat = "データチャンネルの選択：前回の確認では{0}を使い、ディスク上のデータは{1}；今回の配布パッケージ：{2}",
            dataKindBlockDelta = "ブロック単位の差分",
            dataKindFileDelta = "ファイル単位の差分",
            dataKindFull = "全量データパック",
            dataKindNone = "データパックがありません",
            dataDiskTrusted = "信頼できます",
            dataDiskUntrusted = "信頼できません",
            dataItemsNone = "1つもありません",
            techRejectedFallbackLineFormat =
            "バージョン{0}のデータパックは前回、検証に失敗して拒否されたため、今回は{1}を取得します",
            techRejectedSkipLineFormat =
            "バージョン{0}のデータパックは前回、検証に失敗して拒否されたため、今回はデータパックを見送ります",
            dialogOfferTitle = "利用可能な更新があります",
            dialogOfferAssetsTitle = "ゲームデータの更新があります",
            dialogDeclineButton = "今回は見送る",
            dialogNeverButton = "このバージョンを無視",
            dialogDownloadButton = "ダウンロード",
            dialogAckButton = "了解しました",
            cellularDialogTitle = "モバイルデータ通信を使用中です",
            cellularDeclineButton = "キャンセル",
            cellularProceedButton = "取得を確定",
            notificationsTitle = "ダウンロード進捗の通知",
            notificationsDeclineButton = "通知なしで続行",
            notificationsGrantButton = "通知を有効にする",
            downloadStartFailedFormat = "ダウンロードを開始できませんでした（{0}）。あとでもう一度お試しください",
            progressNoTotalFormat = "{0}を取得中 · これまで {1}",
            progressLineFormat = "{0}を取得中 {1}% · これまで {2} / 全体 {3} · 残り {4}",
            speedClauseFormat = "約 {0}/秒",
            etaClauseFormat = "{0}で完了の見込み",
            durationSecondsFormat = "約 {0} 秒",
            durationMinutesFormat = "約 {0} 分",
            durationMinutesSecondsFormat = "約 {0} 分 {1} 秒",
            durationHoursFormat = "約 {0} 時間",
            durationHoursMinutesFormat = "約 {0} 時間 {1} 分",
            stallRestartLine =
                "さきほど取得した部分を続けられなくなったため、とり直し中です（あなたが何かしたわけではありません、開き直さなくて大丈夫です）",
            stallSwitchLine =
                "この更新先からしばらく何も届いていません。別の更新先に切り替えて試しているところです（取得済み部分は残っています）",
            stallWaitLine =
                "新しい内容がしばらく届いていません：ひと休みしてから続くことが多半です。別の更新先を試している可能性、" +
                    "この端末で取得済み部分を読み直している可能性もあります（開き直さなくて大丈夫です）",
            stallNoByteLine =
                "まだ一内容も届いていません：更新先に接続中で、動き出すまで少し時間がかかることがあります（開き直さなくて大丈夫です）",
            startedNotifyLine = "ダウンロードを始めました。進み具合は通知バーに出ますので、このゲーム画面を離れて構いません",
            startedNoNotifyLine =
                "ダウンロードを始めました。通知が許可されていないため、通知バーに進み具合と完了の案内は出ません" +
                    "（取得そのものは通常どおり進み、途中で切れても続きは残ります）。進み具合を見るには、システム設定で" +
                    "このアプリの通知を許可してもう一度「ダウンロード」を押してください。切れた場所から続きを取得します",
            apkDoneLine = "更新ファイルの取得が済みました。この通知を押すとインストールを続けられます",
            apkDoneNoNotifyLine =
                "更新ファイルの取得が済みました。通知が許可されていないため、この案内は通知バーに出ません。" +
                    "システムのインストーラーにはそのまま渡してあります。画面の案内に従って" +
                    "インストールしてください（1 バイトもとり直しになりません）",
            assetsDoneLine = "データパックの取得が済みました。次回のゲーム起動時に自動で適用されます",
            cancelledLine =
                "ダウンロードをやめました。取得済み部分は残っています。もう一度「ダウンロード」を押すとその地点から続きます",
            cancelledNoNotifyLine =
                "ダウンロードを止めました。取得済み部分は残っています。もう一度「ダウンロード」を押すとその地点から続きます" +
                    "（通知が許可されていないため、この案内は通知バーに出ません）",
            failedUnknownLine = "更新のダウンロードに失敗しました：原因は分かりません。この通知で再試行できます",
            failedLegacyFormat = "更新のダウンロードに失敗しました：{0}（この通知で再試行できます）",
            causeUnknownPhrase = "原因がすぐには分かりません",
            retryInNotifyPhrase = "この通知でもう一度試せます",
            failedBodyFormat = "更新のダウンロードに失敗しました：{0}\nいまできること：{1}（{2}）",
            failureToastFormat = "更新を取得できませんでした。いまできること：{0}（{1}）",
            retryEntryNotifyLine = "この通知を押してもう一度試せます",
            retryEntryGameLine = "ゲームに戻って「ダウンロード」をもう一度押すと、切れた場所から続きます",
            settingsRetryLabelFormat = "とりかけの「{0}」を最後まで取得する（切れた場所から続き、とり直しにはなりません）",
            actionUnknownLine = "少しおいてもう一度お試しください。毎回同じところで止まる場合は次回の配布を待ち、ゲームはそのまま遊べます",
            actionAllSourcesLine =
                "複数の更新先が続けて取得できませんでした：少しおいてもう一度お試しください。取得済み部分は残ります。" +
                    "それでもだめなら次回の配布をお待ちください",
            actionSpaceLine =
                "この端末の空き容量が足りません：使わないアプリや写真・動画を整理して空きを作ってから再度お試しください。" +
                    "取得済みの続きは残っています",
            actionNetworkLine = "通信が不安定でした：1〜2 分おいてもう一度お試しください。取得済み部分は無駄になりません",
            actionHttpStatusLine = "更新側がしばらく対応していません（混雑していることが多いです）：10 数分おいてもう一度お試しください。とり直しは不要です",
            actionMissingLine = "この更新先ではいまそのファイルが見つかりません：少しおいて別の更新先で試すか、次回の配布をお待ちください",
            actionPlainHttpLine =
                "指定された更新先が安全でない経路に曲がろうとしたため、自動で避けています。通信手段を変えて（ご家庭の Wi-Fi など）もう一度お試しください",
            actionDigestLine =
                "届いたものが送り出されたものと同じでなかったため破棄しました。自分で再試行しても直ることが少ないため、次回の配布を待つのが確実です",
            actionOversizeLine = "更新先から約束より大幅に大きい内容が届いたため中止しました。その更新先のファイルは壊れています。次回の配布をお待ちください",
            actionLocalIoLine =
                "この端末でのファイルの読み書きが通りませんでした：端末を再起動してからもう一度お試しください。繰り返す場合は" +
                    "ファイルマネージャーでダウンロードフォルダ内の古い更新ファイルを消してください",
            progressNotifyTitle = "更新をダウンロード中です",
            cancelNotifyAction = "ダウンロードをやめる",
            channelProgressName = "更新ダウンロードの進み具合",
            channelResultName = "更新ダウンロードの結果",
            apkReadyTitle = "更新ファイルの準備ができました",
            assetsReadyTitle = "データパックの用意ができました",
            assetsRegisterFailedTitle = "データパックを次回適用として記録できませんでした",
            assetsRegisterProblemLine = "取得は最後まで済みました。ただし次回起動時に適用するものとして記録できませんでした",
            cancelledNotifyTitle = "ダウンロードをやめました",
            failedNotifyTitle = "ダウンロードを完了できませんでした",
            notStartedNotifyTitle = "ダウンロードを始められませんでした",
            downloadBusyToast = "すでに1件のダウンロードが走っています。終わるまでお待ちください",
            noNotifyBarStartFailedFormat =
                "通知バーが使えないため（{0}）、今回はダウンロードを開始しませんでした。システム設定でこのアプリの通知を" +
                    "許可してからもう一度押してください",
            apkMissingToast = "インストールするファイルが見当たりません。ゲームに戻ってもう一度ダウンロードしてください",
            installerOpenFailedFormat = "システムのインストール画面を開けませんでした（{0}）。ファイルマネージャーから手動でインストールしてください",
            systemPausedNotifyTitle = "システムがダウンロードを一時停止しました",
            systemPausedLine =
                "システムの時間制限によりダウンロードを一時停止しました。取得済みの部分は残っており、" +
                    "あとで続けられます",
            offerForceUpdateLine =
                "このバージョンはサポート終了のため更新が必須です。新しいバージョンをダウンロードしてインストールしてください。",
            wifiOnlyCellularTitle = "Wi-Fi のみに設定済み",
            wifiOnlyCellularBody =
                "現在はモバイルデータ通信のため、今回はダウンロードを開始せず、追加の確認も行いません。" +
                    "モバイルデータで取得する場合は、設定で「Wi-Fi のみ」を無効にしてください。",
            downloadingLineFormat = "ダウンロード中：{0}",
            installDoneTitle = "更新のインストールが完了しました",
            installDoneLine = "新しいバージョンがインストールされました",
            installCancelledTitle = "インストールを中止しました",
            installCancelledLine = "インストールをキャンセルしました。更新ファイルはそのまま残っています。この通知を押してもう一度インストールできます",
            installFailedTitle = "更新のインストールに失敗しました",
            installFailedBodyFormat = "インストールに失敗しました：{0}",
            installSessionOpenFailedFormat = "インストールを開始できませんでした：{0}",
            installConfirmOpenFailedFormat =
                "インストールを開始できませんでした：確認の画面を開けませんでした（{0}）。この通知を押すとやり直せます",
            installPathInvalidLine =
                "インストールするファイルが更新フォルダーにないか、名前が正しくありません：ゲームに戻ってダウンロードし直してください",
            installBlockedLine =
                "システムまたは端末の決まりでインストールが止められました：端末管理アプリやセキュリティアプリが妨害していないか確認してください",
            installConflictLine =
                "この端末に既に入っているアプリと衝突します（署名が違うため）：先に古いものを削除する必要がありますが、" +
                    "削除するとセーブデータが消えます。先にバックアップしてください",
            installIncompatibleLine =
                "この端末と適合しないか、既に入っているアプリと署名が一致しません：上書き更新には同じ署名が必要です。" +
                    "古いものを削除するとセーブデータが消えるため、先にバックアップしてください",
            installInvalidLine = "インストールするファイルが無効か、壊れています：ゲームに戻ってダウンロードし直してからインストールしてください",
            installStorageLine = "保存用の空き容量が足りません：容量を空けてからもう一度お試しください",
            installTimeoutLine = "インストールが時間切れになりました：もう一度お試しください",
            installGenericFailedFormat = "原因がわかりません（エラーコード {0}）",
            installSystemMessageFormat = "{0}（システムのメッセージ：{1}）",
        )
    }
}
