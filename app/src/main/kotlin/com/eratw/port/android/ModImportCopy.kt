package com.eratw.port.android

/**
 * 补丁（MOD）导入域的玩家面文案表（三语，A-8/T-2c 收口）。
 *
 * ## 为什么单独一张表而不并进 SettingsStrings
 *
 * 照 [UpdateCopy] 的同款理由：导入是一条链路（入口卡 → 覆盖层 → 成功 / 报错 / 报告页 →
 * 控制器的失败消息原话），改判读时整组一起动；并进配置页那张 1100+ 行的大表两边都越长越难读。
 * 说明句与选文件按钮这两条早在 [SettingsStrings.modImportIntro] / [SettingsStrings.modImportPickButton]
 * 里（2026-11 接的三语），本表**不重复收**（口径唯一，避免两处权威）。
 *
 * ## 三条硬规矩（照 UpdateCopy，同口径）
 *
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]；壳层用 `ModImportCopy.of(language)`
 *    一次取好往里传（本域的判读多在后台线程跑，语言随调用进线程；纯层不碰
 *    SharedPreferences、不判系统 Locale）。
 * 2. **中文逐字搬运**：[ZH] 的每一个值与收口前的硬编码**逐字相同**（含 `…`、全角括号/冒号、
 *     `——` 两侧空格）。既有定向用例（ModImportUiStateTest / ModImportControllerTest）钉中文
 *     原文，中文动一个字就是把别人的用例打红。
 * 3. **缺省参数只为所有权外的既有单测留**（对拍 ZH 档的兼容口）；生产调用点必须显式传本表。
 *
 * ## 句式里的 `{0}`..`{3}`
 *
 * 带读数的句子存成模板，由 [fill] 一次替换（与 `SettingsStrings.fill` 同口径：花括号走字符类
 * 而不是反斜杠转义，Android 的 ICU 正则会把转义写法直接抛掉，真机一点就崩）。
 * 判读结构（哪个状态出哪句、读数怎么摆）留在纯层函数与 Compose 里，本表只放词。
 *
 * ## 留在表外的共用面（取证面与玩家面同吃一句的，不收）
 *
 * 控制器往失败消息里拼的异常原话（`e.message ?: 类名`）与底层抛出的异常 detailMessage
 * （产物缺失、mods 目录建不出、落盘读回不符等）同时服务报错页上屏与取证定位，保持原样
 * 不进表（收了就把取证面翻掉，I7 可定位纪律作废）；侧车灌装告警同理（带 文件:行 + 原文）。
 *
 * @property lang 这一组对应的设置页语言
 */
data class ModImportCopy(
    val lang: SettingsLanguage,

    // ---------------------------------------------------------------- 入口卡与覆盖层骨架
    /** 入口卡标题与覆盖层头部共用的那一句（单源：两处不许各写各的）。 */
    val cardTitle: String,
    /** 入口卡的打开按钮。 */
    val cardOpenButton: String,
    /** 覆盖层右下角的关闭按钮。 */
    val closeButton: String,

    // ---------------------------------------------------------------- 进行中
    /** 导入线程跑起来后状态机写进进行态的那句（起线程前唯一的进度说法）。 */
    val importingStageText: String,
    /** 进行中的一行：{0} 进行中那句、{1} 源文件数。入口卡摘要与覆盖层同用这一条。 */
    val importingFilesFormat: String,

    // ---------------------------------------------------------------- 入口卡摘要
    /** 最近一次导入成功的摘要行：{0} 补丁产物名。 */
    val cardSucceededFormat: String,
    /** 最近一次导入失败的摘要行：{0} 报错页标题。 */
    val cardFailedFormat: String,

    // ---------------------------------------------------------------- 报错页标题（I6/I3/存储的人话分类）
    val failureTitleCompile: String,
    val failureTitleIdentity: String,
    val failureTitleStorage: String,
    val failureTitleUnexpected: String,

    // ---------------------------------------------------------------- 成功页
    /** 成功详情行：{0} 产物名、{1} 函数数、{2} 指令数、{3} 入口函数。 */
    val succeededDetailFormat: String,
    /**
     * 纯资源包成功详情行（MODDX3-A3）：{0} manifest 名、{1} 资源条目数。
     *
     * 纯资源包没有函数 / 指令 / 入口三个读数，既有 [succeededDetailFormat] 的槽位对它全是噪声；
     * [ImportedPatchDetail.successDetailText] 按 [ImportedPatchDetail.assetPackOnly] 选本模板。
     * 三语 ZH/EN/JA 同加（AGENTS §6）。
     */
    val assetPackSucceededDetailFormat: String,
    /**
     * 成品包成功详情行（ERAFIN-C3）：{0} 容器名、{1} 函数数、{2} 指令数。
     *
     * 成品包是编译产物态（zip 自带两件套，跳过编译链直接落盘）：有函数/指令读数但没有
     * 「入口函数」（入口是补丁的定位信息，成品包是零基线模块本体），
     * [ImportedPatchDetail.successDetailText] 按 [ImportedPatchDetail.packagedModuleOnly] 选本模板。
     * 三语 ZH/EN/JA 同加（AGENTS §6）。
     */
    val packagedSucceededDetailFormat: String,
    /** 编译告警行：{0} 告警原话。 */
    val warningLineFormat: String,
    /** 源文件留档行：{0} 已按 [nameListSeparator] 连好的文件名串。 */
    val savedSourcesFormat: String,
    /** 文件名清单的分隔符（EN 用逗号加空格，中日用顿号）。 */
    val nameListSeparator: String,
    /** 成功页的重启按钮。 */
    val restartButton: String,

    // ---------------------------------------------------------------- 报告页
    val reportTitle: String,
    /** 覆盖层刚开还没刷过报告的那行。 */
    val reportNotRefreshed: String,
    /** 报告状态本身读不出来（存储事故）：{0} 原话。 */
    val reportReadFailedFormat: String,
    /** 装载整体回退（I5 的 UI 面）。 */
    val reportRolledBackLine: String,
    /** 会话没装载任何补丁。 */
    val reportNoneLoaded: String,
    /** 已装载行：{0} 补丁名、{1} 函数数、{2} 指令数。 */
    val reportLoadedFormat: String,
    /** 装载失败行：{0} 失败原话（会话侧 I7 读数）。 */
    val reportLoadFailedFormat: String,
    /** 装载告警行：{0} 告警原话。 */
    val reportLoadWarningFormat: String,
    /** mods 目录里还没有补丁产物。 */
    val reportNoStoredFiles: String,
    /** mods 静态清单行：{0} 已连好的文件名串。 */
    val reportStoredFilesFormat: String,

    // ---------------------------------------------------------------- 控制器失败消息（折成 Failed 上屏的原话）
    /** 一个源文件都没选上。 */
    val noFilesPicked: String,
    /** 口上 zip 解包失败：{0} zip 名、{1} 异常原话。 */
    val zipUnpackFailedFormat: String,
    /** zip 里没有可导入条目：{0} zip 名。 */
    val zipNoEntriesFormat: String,
    /**
     * 素材包被选进补丁导入卡（跨卡指路）：{0} zip 名。
     * 判据见 [ModZip.looksLikeAssetPack]；文案必须点名「素材导入」卡，让玩家一步找对地方。
     */
    val zipLooksLikeAssetPackFormat: String,
    /**
     * 条目数越过 [ModZipGuard.MAX_ENTRIES]（疑似条目洪泛）：{0} zip 名、{1} 上限条目数。
     * 判据与常量见 [ModZipGuard]。
     */
    val zipTooManyEntriesFormat: String,
    /** 单条目解压体积越过 [ModZipGuard.MAX_ENTRY_BYTES]（疑似压缩炸弹）：{0} zip 名、{1} 条目名、{2} 上限 MiB。 */
    val zipEntryTooLargeFormat: String,
    /** 整包解压体积越过 [ModZipGuard.MAX_TOTAL_BYTES]（疑似压缩炸弹）：{0} zip 名、{1} 上限 MiB。 */
    val zipTotalTooLargeFormat: String,
    /** 压缩比越过 [ModZipGuard.MAX_COMPRESSION_RATIO]（疑似压缩炸弹）：{0} zip 名。 */
    val zipBombSuspectFormat: String,
    /** zip 内条目重名（同名覆盖语义不明确）：{0} zip 名、{1} 条目名。 */
    val zipDuplicateEntryFormat: String,
    /** 落盘名去重告警（同名取先）：{0} 条目名、{1} 落盘名。 */
    val dedupSkippedWarningFormat: String,
    /** 源留档失败：{0} 异常原话。 */
    val saveSourceFailedFormat: String,
    /** base 编译上下文读取失败：{0} 异常原话。 */
    val readBaseFailedFormat: String,
    /** 读已留档源失败：{0} 文件名、{1} 异常原话。 */
    val readArchivedFailedFormat: String,
    /** 并集里没有可用入口：{0} 源文件数。 */
    val noEntryFunctionFormat: String,
    /** 编译异常没有原话时的兜底句。 */
    val compileFailedFallback: String,
    /** 编译发生未预期异常：{0} 异常类名、{1} 异常原话。 */
    val compileUnexpectedFormat: String,
    /** 产物编码失败：{0} 异常原话。 */
    val artifactEncodeFailedFormat: String,
    /** 身份预检异常没有原话时的兜底句。 */
    val identityFailedFallback: String,
    /** 产物落盘失败：{0} 异常原话。 */
    val artifactSaveFailedFormat: String,
    /** 写读往返校验失败（I8）：{0} 异常原话（原话是取证面，尾巴的标签是给报错页的）。 */
    val verifyWrittenFailedFormat: String,
    /** 代码产物暂存校验失败：{0} 异常原话（失败面 kind 随异常自带，本句只拼原话）。 */
    val codeArtifactStageFailedFormat: String,
    /** 代码产物落盘（内容寻址目录）失败：{0} 异常原话。 */
    val codeArtifactCommitFailedFormat: String,
    /** Mod 运行总闸未开时的成功页状态说明（无占位符）。 */
    val modRuntimeOffHint: String,
    /**
     * 成功页的代码态说明：{0} 包名、{1} 原因句（见下方三个原因键）。
     *
     * 只在本次导入**带了代码产物、且下次装载不会运行它**时上屏（判据在
     * [modCodeGateHintText]）：普通口上包零提示，已生效的代码包也零提示。
     */
    val modCodeGateHintFormat: String,
    /** 代码态原因：运行总闸关着（不是这个包的问题，一开总闸就恢复）。 */
    val modCodeGateReasonGateOff: String,
    /** 代码态原因：玩家还没信任这个代码包（信任后下次装载才运行）。 */
    val modCodeGateReasonUntrusted: String,
    /** 代码态原因：代码身份信息损坏（只能重新导入，改开关 / 补信任都没用）。 */
    val modCodeGateReasonCorrupt: String,
    /** 代码态原因：盘上还留着多份代码版本（装载端拒绝猜版本，重新导入会自动清成只留最新的一份）。 */
    val modCodeGateReasonMultipleVersions: String,
    /**
     * 本包按覆盖装载的函数数（R7-①，成功页一行；0 时整行不出）。
     *
     * 存在理由：口上的本职就是改写游戏原有行为，「导入成功 966 个函数」不告诉玩家其中
     * 有多少顶掉了原函数，等于把一次行为变更报成一次纯新增。原版这条是装载期 level 1
     * 告警（spec: ErbLoader.cs:466-475），到这里升级为一句明说。{0} 个数。
     */
    val overrideSummaryFormat: String,

    // ---------------------------------------------------------------- 版本读数（BU：新增 / 重装 / 版本变化）
    /**
     * 成功页的「重装」读数行：{0} 原始 version 字符串（`v` 前缀写在本串里）。
     *
     * 只在盘上同包旧 manifest 的 version 与本次相等时上屏；行内直接
     * 明说「设置与数据保留」——替换只动四件套与资源面，`mods/rt2/<packageId>.settings` 与
     * 存档段按 packageId 各自保存，不会因重装丢失。
     */
    val reinstallSummaryFormat: String,
    /** 版本号缺失时的「重装」读数行（不带版本槽位，对应 [ImportVersionChange.Reinstall] 的空版本）。 */
    val reinstallNoVersionSummaryFormat: String,
    /**
     * 成功页的「版本变化」读数行：{0} 旧版本、{1} 新版本（`v` 前缀写在本串里）。
     *
     * 只做字符串相等比较（不发明 semver 排序）：降级同样落这一行，原样显示「旧 → 新」不拦。
     * 行内同样明说「设置与数据保留」。
     */
    val versionChangedSummaryFormat: String,
    /**
     * 包元数据自相矛盾的成功页 WARN（CH）：{0} 元数据声明的包名、{1} 包内资源目录、{2} 本次
     * 回落的版本读数。
     *
     * 触发判据 = [packMetaOf] 的 `PackMetaIgnore.PACKAGE_ID_PREFIX`（自述包名不在包内
     * `<prefix>.assets/` 前缀集里，例如误塞了别包的元数据）。此时本次**仍按内容安装、不拦导入**，
     * 只回退版本读数——玩家该知道「这个包自述的包名与它自己的资源目录对不上」。
     * 其余未采信原因（缺失 / 坏档 / schema 不认识 / 版本形态非法）是作者侧问题、玩家无可行动，
     * 不配文案、保持静默。文案不暴露内部枚举名。
     */
    val packMetaPackageIdMismatchFormat: String,
    /**
     * 成功页的「引擎 API 下限」告警（DC）：{0} 该包要求的最低引擎 API 版本、{1} 当前引擎 API 版本。
     *
     * 触发判据 = [packMetaOf] 采信了元数据且 `minApi > ModApi.VERSION`（与装配期整链拒载同一条
     * 判据）：本次**照常导入、不拦**，但要提前告诉玩家「装上后进入游戏时会被拒载、不会生效」，
     * 免得进了游戏才发现包没起作用。句子必须三件事齐全（要求版本 / 当前版本 / 会被拒载），
     * 且不暴露内部字段名（min-api、VERSION、manifest 之类），也不写「去升级引擎」这类指令。
     * min-api 缺失 / 为 0 / 低于当前引擎时不出这一行；元数据整体未采信时同样静默（见
     * [engineApiGateWarning]）。
     */
    val engineApiGateWarningFormat: String,
    /**
     * 成功页的「本包要求更新引擎」提示（本 lane）：{0} 该包编译期要求的引擎版本、{1} 本机引擎版本。
     *
     * 触发判据 = 包元数据声明的编译期面高于本机 `ModApi.VERSION`（[PackMeta.compiledSurface] >
     * [ModApi.VERSION]）——与装载期 ABI 闸 `ModDexGate.verdict` **同一条判据**，本表只做投影、
     * 不重算、不改任何判性（投影点 = `ImportedPatchDetail.engineTooOldLine`）。句子必须讲人话：
     * 这个包要求什么版本、本机是什么版本、本机会不会装载它；不暴露 ABI / surface / API 之类实现词，
     * 也不写「去升级引擎」这类指令（玩家唯一的行动是更新应用）。
     *
     * 包未声明编译期面 / 要求不高于本机时整行不出（普通口上包零噪声）。
     */
    val engineTooOldWarningFormat: String,

    // ---------------------------------------------------------------- 资源面（导入包携带 "<packageId>.assets/" 资源，Lane BH）
    // ---------------------------------------------------------------- 资源面（导入包携带 "<来源目录名>.assets/" 资源、重挂到本包，Lane BH）
    /**
     * 成功页的「资源 N 条」行：{0} 本次落盘的资源条目数。
     *
     * 只在 N > 0 时上屏（[ImportedPatchDetail.resourceSummaryLine] 的判据）：普通口上包没有
     * 资源面，给它们也加一行「资源 0 条」纯属噪声。
     */
    val resourceSummaryFormat: String,
    /** 资源条目名非法（跳过）：{0} zip 条目名。 */
    val resourceEntryInvalidNameFormat: String,
    /** 资源条目重名（跳过）：{0} zip 条目名。 */
    val resourceEntryDuplicateFormat: String,
    /** 单文件超上限、整面不导入：{0} 上限 MiB、{1} 条目名、{2} 字节数。 */
    val resourceFileTooLargeFormat: String,
    /** 资源总量超上限、整面不导入：{0} 上限 MiB、{1} 已累计字节数。 */
    val resourceTotalTooLargeFormat: String,
    /** 来源目录名被重挂到本包（仍导入）：{0} 条数、{1} 来源目录名样例串。 */
    val resourcePrefixRebasedFormat: String,
    /** 重挂后跨来源同名冲突、整包拒导：{0} 相对资源名、{1} 冲突的两个来源目录名串。 */
    val resourcePrefixConflictFormat: String,
    /** 资源面临时落盘失败（整面不导入）：{0} 异常原话（取证面）。 */
    val resourceStageFailedFormat: String,
    /** 资源面正式名替换失败（本次未生效）：{0} 异常原话（取证面）。 */
    val resourceCommitFailedFormat: String,

    // ---- 旁挂媒体资源面（ADR-014 S4）：resources/mod/<dir>/ 与 sound/mod/<dir>/ ----
    /** 跨包同名旁挂目录、整包拒导：{0} 目录名、{1} 已声明该目录的包、{2} 本次导入的包。 */
    val mediaDirConflictFormat: String,
    /** 元数据声明的旁挂目录名非法（本次不导入资源）：{0} 目录名。 */
    val mediaDirInvalidFormat: String,
    /** 媒体条目相对名非法（跳过）：{0} zip 条目名。 */
    val mediaEntryInvalidNameFormat: String,
    /** 媒体条目重名（跳过）：{0} zip 条目名。 */
    val mediaEntryDuplicateFormat: String,
    /** 扩展名不在白名单、整面不导入：{0} zip 条目名、{1} 允许的扩展名串。 */
    val mediaExtensionRejectedFormat: String,
    /** 媒体单文件超上限、整面不导入：{0} 上限 MiB、{1} zip 条目名、{2} 字节数。 */
    val mediaFileTooLargeFormat: String,
    /** 图/文树累计超上限、整面不导入：{0} 上限 MiB、{1} 已累计字节数。 */
    val mediaResourceTotalTooLargeFormat: String,
    /** 音频树累计超上限、整面不导入：{0} 上限 MiB、{1} 已累计字节数。 */
    val mediaSoundTotalTooLargeFormat: String,
    /** 两树合计超上限、整面不导入：{0} 上限 MiB、{1} 已累计字节数。 */
    val mediaCombinedTooLargeFormat: String,
    /** 媒体面临时落盘失败（整面不导入）：{0} 异常原话（取证面）。 */
    val mediaStageFailedFormat: String,
    /** 媒体面正式落盘失败（本次未生效）：{0} 异常原话（取证面）。 */
    val mediaCommitFailedFormat: String,

    // ---- 导入预览面（只读预览，见 [ModImportPreview]；限额预警只提示不拦截） ----
    /** 预览块的小标题。 */
    val previewTitle: String,
    /** 代码面源文件行：{0} 源文件数、{1} .erb 数、{2} .erh 数。 */
    val previewCodeSourcesFormat: String,
    /** 代码面已编译模块行（有 .erbm 才出）：{0} 已编译模块数。 */
    val previewCodeCompiledFormat: String,
    /** 资源面行：{0} `.assets` 条目数、{1} 累计 MiB（一位小数）。 */
    val previewAssetsFormat: String,
    /**
     * 媒体面行：{0} 声明的 `<dir>`、{1} 图/文条目数、{2} 图/文字节 MiB、{3} 音频条目数、
     * {4} 音频字节 MiB。只在包声明了 `resources` 时出。
     */
    val previewMediaFormat: String,
    /** 媒体面没有声明 `<dir>` 的那行（无槽位）。 */
    val previewMediaNoneLine: String,
    /** 媒体扩展名汇总行的外壳：{0} 已按 [nameListSeparator] 连好的「扩展名 条目数」串。 */
    val previewExtensionsFormat: String,
    /** 单个扩展名的条目数：{0} 扩展名、{1} 条目数。 */
    val previewExtensionCountFormat: String,
    /**
     * 跨包目录冲突预警：{0} 目录名、{1} 已声明该目录的包。
     * 只提示不拦截（强校验仍在 S4 的 `stage` 入口，会整包拒导）。
     */
    val previewConflictFormat: String,
    /**
     * 限额预警：{0} 超额物（条目名 / 树路径）、{1} 上限 MiB、{2} 实际 MiB。
     * **只提示不拦截**：导入时仍由 S4 的 `stage` 入口按实际字节判。
     */
    val previewLimitFormat: String,
    /** 两树合计限额预警：{0} 上限 MiB、{1} 实际 MiB。只提示不拦截（同 [previewLimitFormat]）。 */
    val previewLimitCombinedFormat: String,
    /** 预览算不出来（zip 坏 / 认不出元数据等）：{0} reason。导入仍可继续。 */
    val previewUnavailableFormat: String,
    /** 一批多个包的计数行：{0} 本批包数（≥2 才出；单包走 [previewPackageCountSingleLine]）。 */
    val previewPackageCountFormat: String,
    /** 本批只有一个包的那句措辞（无槽位；整批面行已经把它描述清楚，不再逐条摘要）。 */
    val previewPackageCountSingleLine: String,
    /** 逐包摘要行：{0} 显示名、{1} 包身份、{2} 版本、{3} 源文件数、{4} 资源条目数。 */
    val previewPackageRowFormat: String,
    /** 版本读不出来时的占位（无槽位）。 */
    val previewPackageVersionUnknown: String,
    /** 包身份算不出来时的占位（无槽位）。 */
    val previewPackageIdUnknown: String,
    /** 同批两个包声明同一资源目录的预警：{0} 先声明的包、{1} 后声明的包、{2} 目录名。 */
    val previewBatchDirConflictFormat: String,

    // ---------------------------------------------------------------- 批导入结果面（task-178：逐包结果 + 坏包隔离）
    /** 批导入总体计数行：{0} 成功项数、{1} 失败项数。 */
    val batchResultSummaryFormat: String,
    /** 批导入逐项成功行：{0} 项名（zip 文件名 / 散装源名清单）。 */
    val batchResultRowSucceededFormat: String,
    /** 批导入逐项失败行首行：{0} 项名、{1} 失败归类标题。 */
    val batchResultRowFailedFormat: String,
    /** 卡片摘要（浮层关掉后的「上次批量导入」一行）：{0} 成功项数、{1} 失败项数。 */
    val batchCardSummaryFormat: String,
    /** 成功明细超上限时的折叠行（点按展开）：{0} 未显示的成功项数。 */
    val batchResultOverflowSucceededFormat: String,
    /** 失败明细超上限时的折叠行（点按展开）：{0} 未显示的失败项数。 */
    val batchResultOverflowFailedFormat: String,
    /** 折叠行展开后的收起开关（无槽位；一个开关管本类明细整体的展开态）。 */
    val batchResultCollapse: String,

    // ---------------------------------------------------------------- 宿主响应链（SettingsActivity 的导入段）
    /** 单个 SAF 文件读不出：{0} 文件名或 uri 段。 */
    val sourceReadFailedFormat: String,
    /** 全部源文件都没读出来：{0} 已按换行连好的逐条原话。 */
    val allReadsFailedFormat: String,
    /** 导入线程兜底（报错页照上屏）：{0} 异常类名、{1} 异常原话。 */
    val unexpectedFormat: String,
    /** 报告快照读取失败且异常连类名都没有时的最后一档。 */
    val unknownStorageError: String,

    // ---------------------------------------------------------------- 报错专页（KI-4：免责导向 + 一键复制）
    /**
     * 报错页导向行（编译 / 身份两类 = 口上包自身的问题）：指明该找口上作者，移植版修不了它。
     * 无槽位。
     */
    val failureGuidanceAuthorFormat: String,
    /** 报错页导向行（存储类 = 本机存储问题）：先自查空间 / 权限，仍失败反馈移植版。无槽位。 */
    val failureGuidanceStorageFormat: String,
    /** 报错页导向行（未预期 = 内部问题）：带报告反馈移植版。无槽位。 */
    val failureGuidanceUnexpectedFormat: String,
    /** 报错页「复制错误报告」按钮（KI-4：报告拼装见 [modImportErrorReportText]）。 */
    val copyErrorReportButton: String,
    /** 成功页「复制告警清单」按钮（隔离 / 降级 / 告警清单非空才出）。 */
    val copyWarningsButton: String,
    /** 错误报告首行标题（玩家转给口上作者 / 移植版的正文头）。 */
    val errorReportTitle: String,
    /** 错误报告类别行：{0} 归类标题（与报错页同一条 [failureTitle*]，不另造分类）。 */
    val errorReportKindFormat: String,
    /** 错误报告时间行：{0} 已格式化的本地时间（ISO 形，与设备语言无关）。 */
    val errorReportTimeFormat: String,
    /** 错误报告来源行（预览态有包名才出）：{0} 已连好的包显示名串。 */
    val errorReportSourcesFormat: String,
    /** 复制成功的 Toast（剪贴板写入路径的玩家确认词）。 */
    val copyDoneToast: String,

    // ---------------------------------------------------------------- 预览替换提示（KI-8：月更重复导入）
    /**
     * 预览替换行（KI-8 ①）：{0} 已装旧版本号、{1} 旧版导入时间（旧 manifest 的 mtime）。
     * 盘上有同包旧 manifest 才出这一行；月更包重导前玩家先看到「装了会替换什么」。
     */
    val previewReplaceFormat: String,
    /** 预览替换行（旧版导入时间读不到，mtime 为 0 等）：{0} 已装旧版本号。 */
    val previewReplaceNoTimeFormat: String,
) {

    /**
     * 句式占位符 `{0}`..`{3}`：单趟替换，参数本身带 `{n}`（例如异常原话）不会被二次展开。
     *
     * **花括号必须走字符类而不是反斜杠转义**：Android 的正则是 ICU 引擎，转义写法直接抛
     * `PatternSyntaxException`（OpenJDK 却容忍）—— 本表照抄 `SettingsStrings` / `UpdateCopy`
     * 的同一写法，真机实测过的坑不踩第二遍。
     */
    fun fill(template: String, vararg args: Any): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言：不许在导入侧再造一套语言判定。 */
        fun of(lang: SettingsLanguage): ModImportCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：每个值都与收口前的硬编码逐字相同（中文零漂移的对拍右轴）。 */
        val ZH: ModImportCopy = ModImportCopy(
            lang = SettingsLanguage.ZH,
            cardTitle = "补丁（Mod）导入",
            cardOpenButton = "打开补丁导入",
            closeButton = "关闭",
            importingStageText = "正在编译并落盘补丁…",
            importingFilesFormat = "{0}（{1} 个文件）",
            cardSucceededFormat = "上次导入成功：{0}（重启游戏后生效）",
            cardFailedFormat = "上次导入失败：{0}",
            failureTitleCompile = "补丁编译失败（未落盘）",
            failureTitleIdentity = "补丁与游戏本体不符",
            failureTitleStorage = "存储失败",
            failureTitleUnexpected = "内部错误",
            succeededDetailFormat = "导入成功：{0}（{1} 个函数 / {2} 条指令，入口 {3}）",
            assetPackSucceededDetailFormat = "纯资源包导入成功：{0}（{1} 条资源）",
            packagedSucceededDetailFormat = "成品包导入成功：{0}（{1} 个函数 / {2} 条指令）",
            warningLineFormat = "告警：{0}",
            savedSourcesFormat = "源文件已保存：{0}",
            nameListSeparator = "、",
            restartButton = "重启游戏生效",
            reportTitle = "补丁状态",
            reportNotRefreshed = "尚未刷新（打开本页时自动读取）",
            reportReadFailedFormat = "补丁状态读取失败：{0}",
            reportRolledBackLine = "装载已整体回退：补丁全部未生效（无补丁模式运行）",
            reportNoneLoaded = "本局没有装载任何补丁",
            reportLoadedFormat = "已装载：{0}（{1} 个函数 / {2} 条指令）",
            reportLoadFailedFormat = "装载失败：{0}",
            reportLoadWarningFormat = "装载告警：{0}",
            reportNoStoredFiles = "mods 里暂无补丁产物",
            reportStoredFilesFormat = "mods 里的补丁产物：{0}",
            noFilesPicked = "没有选择任何文件",
            zipUnpackFailedFormat = "口上 zip 解包失败（{0}）：可能已加密或文件损坏 —— {1}",
            zipNoEntriesFormat = "口上 zip 里没有可导入的 .ERB/.ERH 条目：{0}（其余文件已跳过）",
            zipLooksLikeAssetPackFormat = "这看起来是素材包，请到素材导入卡里选择：{0}",
            zipTooManyEntriesFormat = "口上 zip 的条目数超过上限（{0}：最多 {1} 个条目），已拒绝导入 —— 这个包可能是构造出来的异常包",
            zipEntryTooLargeFormat = "口上 zip 里有条目解压后超过上限（{0}：{1} 超过 {2} MiB），已拒绝导入 —— 疑似压缩炸弹",
            zipTotalTooLargeFormat = "口上 zip 整包解压后超过上限（{0}：超过 {1} MiB），已拒绝导入 —— 疑似压缩炸弹",
            zipBombSuspectFormat = "口上 zip 的压缩比超过上限（{0}），已拒绝导入 —— 疑似压缩炸弹",
            zipDuplicateEntryFormat = "口上 zip 里出现重名条目（{0}：{1} 出现了不止一次），已拒绝导入 —— 同名覆盖的语义不明确",
            dedupSkippedWarningFormat = "同名源文件取先：{0}（落盘名 {1} 已被先前的条目占用，本条跳过）",
            saveSourceFailedFormat = "保存源文件到 mods 失败：{0}",
            readBaseFailedFormat = "读取游戏产物失败：{0}",
            readArchivedFailedFormat = "读取已留档的口上源失败（{0}）：{1}",
            noEntryFunctionFormat = "补丁里没有任何 @函数定义（{0} 个源文件都没有可用的函数头）",
            compileFailedFallback = "补丁编译失败",
            compileUnexpectedFormat = "补丁编译发生未预期异常：{0}: {1}",
            artifactEncodeFailedFormat = "补丁产物编码失败：{0}",
            identityFailedFallback = "补丁与 base 身份不符",
            artifactSaveFailedFormat = "补丁产物落盘失败：{0}",
            verifyWrittenFailedFormat = "{0}（写入校验未通过）",
            codeArtifactStageFailedFormat = "代码产物校验失败，本次导入已回滚：{0}",
            codeArtifactCommitFailedFormat = "代码产物落盘失败，本次导入已回滚：{0}",
            modRuntimeOffHint = "Mod 运行总闸当前未开，导入的内容要开启总闸并重开一局后才生效。",
            modCodeGateHintFormat =
                "第三方代码包「{0}」下次装载不会生效：{1}。到设置页的「已安装的 Mod」里处理。",
            modCodeGateReasonGateOff = "运行总开关还没开",
            modCodeGateReasonUntrusted = "这个包还没有被信任",
            modCodeGateReasonCorrupt = "代码身份信息损坏，需要重新导入",
            modCodeGateReasonMultipleVersions = "盘上还留着多份代码版本，重新导入这个包会自动只留最新的一份",
            overrideSummaryFormat = "其中 {0} 个函数与游戏原有函数同名，导入后以本包为准。",
            reinstallSummaryFormat = "重装 v{0}：设置与数据保留",
            reinstallNoVersionSummaryFormat = "重装：设置与数据保留",
            versionChangedSummaryFormat = "版本 v{0} → v{1}：设置与数据保留",
            packMetaPackageIdMismatchFormat =
                "包内元数据声明的包名 {0} 与包内资源目录 {1} 不一致；本次按内容安装，版本读数回落为 {2}。",
            engineApiGateWarningFormat =
                "此包要求引擎 API v{0} 或更高，当前引擎 API 为 v{1}：装上后进入游戏时，" +
                    "本包会因引擎版本不足被拒载（不会生效）；需要更新到支持 API v{0} 的版本。",
            engineTooOldWarningFormat =
                "此包要求引擎 v{0}，本机引擎为 v{1}：本机不会装载它；请更新应用后重新导入。",
            resourceSummaryFormat = "资源 {0} 条",
            resourceEntryInvalidNameFormat = "资源条目名非法，已跳过：{0}",
            resourceEntryDuplicateFormat = "资源条目重名，已跳过：{0}",
            resourceFileTooLargeFormat =
                "资源条目超单文件上限（{0} MiB）：{1}（{2} 字节），本次资源面整体不导入",
            resourceTotalTooLargeFormat =
                "资源总量超上限（{0} MiB）：已累计 {1} 字节，本次资源面整体不导入",
            resourcePrefixRebasedFormat =
                "导入包里有 {0} 条资源的来源目录名不是本包，已重挂到本包资源目录：{1}",
            resourcePrefixConflictFormat =
                "资源重挂到本包后出现重名，为避免互相覆盖已整体拒导：{0}（来源目录名：{1}）",
            resourceStageFailedFormat = "资源面临时落盘失败，本次不导入资源：{0}",
            resourceCommitFailedFormat = "资源目录替换失败，本次资源面未生效：{0}",
            mediaDirConflictFormat =
                "旁挂资源目录 {0} 已被包 {1} 声明，本次导入包 {2} 不能再用同一目录；为避免互相覆盖已整体拒导",
            mediaDirInvalidFormat = "包内元数据声明的旁挂资源目录名非法，本次不导入资源：{0}",
            mediaEntryInvalidNameFormat = "媒体资源条目名非法，已跳过：{0}",
            mediaEntryDuplicateFormat = "媒体资源条目重名，已跳过：{0}",
            mediaExtensionRejectedFormat = "媒体资源条目 {0} 的扩展名不在允许范围内（{1}），本次资源面整体不导入",
            mediaFileTooLargeFormat =
                "媒体资源条目超单文件上限（{0} MiB）：{1}（{2} 字节），本次资源面整体不导入",
            mediaResourceTotalTooLargeFormat =
                "图/文资源总量超上限（{0} MiB）：已累计 {1} 字节，本次资源面整体不导入",
            mediaSoundTotalTooLargeFormat =
                "音频资源总量超上限（{0} MiB）：已累计 {1} 字节，本次资源面整体不导入",
            mediaCombinedTooLargeFormat =
                "媒体资源两树合计超上限（{0} MiB）：已累计 {1} 字节，本次资源面整体不导入",
            mediaStageFailedFormat = "媒体资源临时落盘失败，本次不导入资源：{0}",
            mediaCommitFailedFormat = "媒体资源落盘失败，本次资源面未生效：{0}",
            previewTitle = "导入预览",
            previewCodeSourcesFormat = "代码面：{0} 个源文件（.erb {1} / .erh {2}）",
            previewCodeCompiledFormat = "代码面：另含 {0} 个可直接运行的编译模块",
            previewAssetsFormat = "资源面：{0} 条，共 {1} MiB",
            previewMediaFormat = "媒体面：{0} —— 图/文 {1} 条 {2} MiB、音频 {3} 条 {4} MiB",
            previewMediaNoneLine = "媒体面：没有声明旁挂目录（包内无 resources 字段）",
            previewExtensionsFormat = "媒体扩展名：{0}",
            previewExtensionCountFormat = "{0} 共 {1} 条",
            previewConflictFormat = "预警：旁挂目录 {0} 已被包 {1} 声明，导入时会因目录冲突被拒",
            previewLimitFormat = "预警：{0} 超过上限 {1} MiB（实际 {2} MiB）—— 只提示，导入时以实际校验为准",
            previewLimitCombinedFormat =
                "预警：媒体两树合计超过上限 {0} MiB（实际 {1} MiB）—— 只提示，导入时以实际校验为准",
            previewUnavailableFormat = "预览不可用：{0}（不影响导入，强校验仍在导入时执行）",
            previewPackageCountFormat = "共 {0} 个包",
            previewPackageCountSingleLine = "本批只有 1 个包",
            previewPackageRowFormat = "{0} · {1} · 版本 {2} · 源 {3} / 资源 {4}",
            previewPackageVersionUnknown = "版本未知",
            previewPackageIdUnknown = "包名未知",
            previewBatchDirConflictFormat =
                "预警：本批的 {0} 与 {1} 都声明了资源目录 {2}，同批导入会合并成一个包并共用该目录",
            batchResultSummaryFormat = "{0} 个成功 / {1} 个失败",
            batchResultRowSucceededFormat = "{0} · 成功",
            batchResultRowFailedFormat = "{0} · 失败：{1}",
            batchCardSummaryFormat = "上次批量导入：{0} 个成功 / {1} 个失败",
            batchResultOverflowSucceededFormat = "还有 {0} 个成功结果未显示（点此展开）",
            batchResultOverflowFailedFormat = "还有 {0} 个失败结果未显示（点此展开）",
            batchResultCollapse = "收起",
            sourceReadFailedFormat = "{0}：读不出文件内容（可能已被移动或删除）",
            allReadsFailedFormat = "所选文件一个都读不出来：\n{0}",
            unexpectedFormat = "导入发生未预期异常：{0}: {1}",
            unknownStorageError = "未知存储错误",
            failureGuidanceAuthorFormat = "这个错误来自口上包自身（不是移植版的问题）：请把下面的报告发给该口上的作者反馈。",
            failureGuidanceStorageFormat = "这是本机存储问题：请检查剩余空间或存储权限后重试；仍失败请反馈给移植版。",
            failureGuidanceUnexpectedFormat = "发生了未预期的内部错误：请复制下面的报告反馈给移植版。",
            copyErrorReportButton = "复制错误报告",
            copyWarningsButton = "复制告警清单",
            errorReportTitle = "口上导入错误报告",
            errorReportKindFormat = "类别：{0}",
            errorReportTimeFormat = "时间：{0}",
            errorReportSourcesFormat = "来源：{0}",
            copyDoneToast = "已复制",
            previewReplaceFormat = "将替换已装旧版 v{0}（旧版导入于 {1}）；设置与数据保留",
            previewReplaceNoTimeFormat = "将替换已装旧版 v{0}；设置与数据保留",
        )

        /** English：结构与 [ZH] 逐条同形（同样几行、同样几个读数），措辞另写，不抄中文。 */
        val EN: ModImportCopy = ModImportCopy(
            lang = SettingsLanguage.EN,
            cardTitle = "Patch (Mod) import",
            cardOpenButton = "Open patch import",
            closeButton = "Close",
            importingStageText = "Compiling and writing the patch...",
            importingFilesFormat = "{0} ({1} files)",
            cardSucceededFormat = "Last import succeeded: {0} (takes effect after the game restarts)",
            cardFailedFormat = "Last import failed: {0}",
            failureTitleCompile = "Patch compilation failed (nothing was written)",
            failureTitleIdentity = "The patch does not match this game",
            failureTitleStorage = "Storage failure",
            failureTitleUnexpected = "Internal error",
            succeededDetailFormat = "Import succeeded: {0} ({1} functions / {2} instructions, entry {3})",
            assetPackSucceededDetailFormat = "Asset-only pack imported: {0} ({1} resource files)",
            packagedSucceededDetailFormat = "Prebuilt pack imported: {0} ({1} functions / {2} instructions)",
            warningLineFormat = "Warning: {0}",
            savedSourcesFormat = "Source files saved: {0}",
            nameListSeparator = ", ",
            restartButton = "Restart the game to apply",
            reportTitle = "Patch status",
            reportNotRefreshed = "Not refreshed yet (read automatically when this page opens)",
            reportReadFailedFormat = "Reading patch status failed: {0}",
            reportRolledBackLine = "Everything was rolled back: no patch is in effect (running without patches)",
            reportNoneLoaded = "No patches were loaded in this session",
            reportLoadedFormat = "Loaded: {0} ({1} functions / {2} instructions)",
            reportLoadFailedFormat = "Failed to load: {0}",
            reportLoadWarningFormat = "Load warning: {0}",
            reportNoStoredFiles = "No patch artifacts in mods yet",
            reportStoredFilesFormat = "Patch artifacts in mods: {0}",
            noFilesPicked = "No files were selected",
            zipUnpackFailedFormat = "Failed to unpack the kojo zip ({0}): it may be encrypted or corrupted - {1}",
            zipNoEntriesFormat = "No importable .ERB/.ERH entries in the kojo zip: {0} (other files were skipped)",
            zipLooksLikeAssetPackFormat =
                "This looks like an asset pack - pick it on the asset import card instead: {0}",
            zipTooManyEntriesFormat =
                "The kojo zip has too many entries ({0}: at most {1} entries) - import rejected; " +
                    "this pack looks constructed",
            zipEntryTooLargeFormat =
                "An entry in the kojo zip is over the size limit after unpacking ({0}: {1} is over {2} MiB) - " +
                    "import rejected; suspected zip bomb",
            zipTotalTooLargeFormat =
                "The kojo zip is over the total size limit after unpacking ({0}: over {1} MiB) - " +
                    "import rejected; suspected zip bomb",
            zipBombSuspectFormat =
                "The kojo zip is over the compression ratio limit ({0}) - import rejected; suspected zip bomb",
            zipDuplicateEntryFormat =
                "The kojo zip has duplicate entries ({0}: {1} appears more than once) - import rejected; " +
                    "same-name override is ambiguous",
            dedupSkippedWarningFormat =
                "Kept the first of the same-named sources: {0} (disk name {1} was already taken by an earlier " +
                    "entry, so this one was skipped)",
            saveSourceFailedFormat = "Saving the source files into mods failed: {0}",
            readBaseFailedFormat = "Reading the game build failed: {0}",
            readArchivedFailedFormat = "Reading an archived kojo source failed ({0}): {1}",
            noEntryFunctionFormat =
                "The patch contains no @ function definitions (none of the {0} source files has a usable " +
                    "function header)",
            compileFailedFallback = "Patch compilation failed",
            compileUnexpectedFormat = "Unexpected error while compiling the patch: {0}: {1}",
            artifactEncodeFailedFormat = "Encoding the patch artifact failed: {0}",
            identityFailedFallback = "The patch does not match the base build",
            artifactSaveFailedFormat = "Writing the patch artifact failed: {0}",
            verifyWrittenFailedFormat = "{0} (write verification failed)",
            codeArtifactStageFailedFormat =
                "The packaged code artifact could not be validated; this import was rolled back: {0}",
            codeArtifactCommitFailedFormat =
                "Writing the packaged code artifact failed; this import was rolled back: {0}",
            modRuntimeOffHint =
                "The Mod runtime gate is off. The imported content takes effect only after you " +
                    "enable the gate and start a new session.",
            modCodeGateHintFormat =
                "Third-party code pack {0} will not take effect on the next load: {1}. " +
                    "Fix it in Settings, under installed mods.",
            modCodeGateReasonGateOff = "the runtime master switch is off",
            modCodeGateReasonUntrusted = "this pack has not been trusted yet",
            modCodeGateReasonCorrupt = "its code identity is damaged, so re-import this pack",
            modCodeGateReasonMultipleVersions =
                "multiple code versions are still on disk, so import this pack again and only the " +
                    "newest one will stay",
            overrideSummaryFormat =
                "{0} of these functions share a name with an existing game function; the imported " +
                    "pack now defines them.",
            reinstallSummaryFormat = "Reinstalled v{0} — settings and data preserved",
            reinstallNoVersionSummaryFormat = "Reinstalled — settings and data preserved",
            versionChangedSummaryFormat = "Version v{0} → v{1} — settings and data preserved",
            packMetaPackageIdMismatchFormat =
                "The package name {0} declared in the pack metadata does not match the package's " +
                    "resource directory {1}; this import uses the content-based identity and the " +
                    "version falls back to {2}.",
            engineApiGateWarningFormat =
                "This pack requires engine API v{0} or newer, but the current engine is API v{1}: " +
                    "after installation, loading will reject this pack because the engine is too old " +
                    "(it will not take effect). Please update to a version that supports API v{0}.",
            engineTooOldWarningFormat =
                "This pack requires engine v{0}, but this device has v{1}: it will not be loaded " +
                    "here. Update the app and import the pack again.",
            resourceSummaryFormat = "{0} resource files",
            resourceEntryInvalidNameFormat = "Skipped a resource entry with an invalid name: {0}",
            resourceEntryDuplicateFormat = "Skipped a duplicate resource entry: {0}",
            resourceFileTooLargeFormat =
                "A resource entry exceeds the per-file limit ({0} MiB): {1} ({2} bytes), so no " +
                    "resources were imported this time",
            resourceTotalTooLargeFormat =
                "The total resource size exceeds the limit ({0} MiB): accumulated {1} bytes, so no " +
                    "resources were imported this time",
            resourcePrefixRebasedFormat =
                "The import pack has {0} resource entries whose source directory is not this package; " +
                    "reattached them to this package's resource directory: {1}",
            resourcePrefixConflictFormat =
                "Resources collide after reattaching to this package, so the import was rejected to avoid " +
                    "overwriting each other: {0} (source directories: {1})",
            resourceStageFailedFormat =
                "Could not write the resource staging area, so no resources were imported: {0}",
            resourceCommitFailedFormat =
                "Replacing the resource directory failed, so this import's resources are not in effect: {0}",
            mediaDirConflictFormat =
                "The media resource directory {0} is already declared by pack {1}, so pack {2} cannot " +
                    "reuse it; the import was rejected to avoid overwriting each other",
            mediaDirInvalidFormat =
                "The pack metadata declares an invalid media resource directory, so no resources were " +
                    "imported this time: {0}",
            mediaEntryInvalidNameFormat = "Skipped a media resource entry with an invalid name: {0}",
            mediaEntryDuplicateFormat = "Skipped a duplicate media resource entry: {0}",
            mediaExtensionRejectedFormat =
                "The media resource entry {0} has an extension outside the allowed set ({1}), so no " +
                    "resources were imported this time",
            mediaFileTooLargeFormat =
                "A media resource entry exceeds the per-file limit ({0} MiB): {1} ({2} bytes), so no " +
                    "resources were imported this time",
            mediaResourceTotalTooLargeFormat =
                "The total image/text resource size exceeds the limit ({0} MiB): accumulated {1} bytes, " +
                    "so no resources were imported this time",
            mediaSoundTotalTooLargeFormat =
                "The total audio resource size exceeds the limit ({0} MiB): accumulated {1} bytes, so no " +
                    "resources were imported this time",
            mediaCombinedTooLargeFormat =
                "The combined media resource size exceeds the limit ({0} MiB): accumulated {1} bytes, so " +
                    "no resources were imported this time",
            mediaStageFailedFormat =
                "Could not write the media staging area, so no resources were imported: {0}",
            mediaCommitFailedFormat =
                "Writing the media resources failed, so this import's resources are not in effect: {0}",
            previewTitle = "Import preview",
            previewCodeSourcesFormat = "Code: {0} source files (.erb {1} / .erh {2})",
            previewCodeCompiledFormat = "Code: plus {0} compiled modules that run as-is",
            previewAssetsFormat = "Assets: {0} entries, {1} MiB in total",
            previewMediaFormat =
                "Media: {0} - {1} image/text entries ({2} MiB), {3} audio entries ({4} MiB)",
            previewMediaNoneLine = "Media: no side directory declared (the pack has no resources field)",
            previewExtensionsFormat = "Media extensions: {0}",
            previewExtensionCountFormat = "{0} x{1}",
            previewConflictFormat =
                "Warning: side directory {0} is already declared by pack {1}; the import will be " +
                    "rejected as a directory conflict",
            previewLimitFormat =
                "Warning: {0} is over the limit of {1} MiB (actual {2} MiB) - shown for " +
                    "information only; the real check runs on import",
            previewLimitCombinedFormat =
                "Warning: the two media trees together are over the limit of {0} MiB (actual " +
                    "{1} MiB) - shown for information only; the real check runs on import",
            previewUnavailableFormat =
                "Preview unavailable: {0} (import is unaffected; the strict checks still run on import)",
            previewPackageCountFormat = "{0} packages selected",
            previewPackageCountSingleLine = "Single package in this batch",
            previewPackageRowFormat = "{0} · {1} · version {2} · sources {3} / assets {4}",
            previewPackageVersionUnknown = "version unknown",
            previewPackageIdUnknown = "package name unknown",
            previewBatchDirConflictFormat =
                "Warning: {0} and {1} both declare resource dir {2} in this batch; they are merged " +
                    "into one package and share that dir",
            batchResultSummaryFormat = "{0} succeeded / {1} failed",
            batchResultRowSucceededFormat = "{0} · succeeded",
            batchResultRowFailedFormat = "{0} · failed: {1}",
            batchCardSummaryFormat = "Last batch import: {0} succeeded / {1} failed",
            batchResultOverflowSucceededFormat = "{0} more succeeded results are hidden (tap to expand)",
            batchResultOverflowFailedFormat = "{0} more failed results are hidden (tap to expand)",
            batchResultCollapse = "Collapse",
            sourceReadFailedFormat = "{0}: could not read the file contents (it may have been moved or deleted)",
            allReadsFailedFormat = "None of the selected files could be read:\n{0}",
            unexpectedFormat = "Unexpected error during import: {0}: {1}",
            unknownStorageError = "Unknown storage error",
            failureGuidanceAuthorFormat =
                "This error comes from the kojo pack itself (not a porting bug): " +
                "send the report below to the pack's author.",
            failureGuidanceStorageFormat =
                "This is a local storage problem: check free space or storage permission and retry; " +
                "if it still fails, report it to the port.",
            failureGuidanceUnexpectedFormat =
                "An unexpected internal error occurred: copy the report below and send it " +
                "to the port developers.",
            copyErrorReportButton = "Copy error report",
            copyWarningsButton = "Copy warnings",
            errorReportTitle = "Kojo import error report",
            errorReportKindFormat = "Kind: {0}",
            errorReportTimeFormat = "Time: {0}",
            errorReportSourcesFormat = "Source: {0}",
            copyDoneToast = "Copied",
            previewReplaceFormat = "Will replace installed v{0} (imported at {1}); settings and data are kept",
            previewReplaceNoTimeFormat = "Will replace installed v{0}; settings and data are kept",
        )

        /** 日本語：结构与 [ZH] 逐条同形。 */
        val JA: ModImportCopy = ModImportCopy(
            lang = SettingsLanguage.JA,
            cardTitle = "パッチ（Mod）導入",
            cardOpenButton = "パッチ導入を開く",
            closeButton = "閉じる",
            importingStageText = "パッチをコンパイルして保存しています…",
            importingFilesFormat = "{0}（{1} ファイル）",
            cardSucceededFormat = "前回の導入は成功：{0}（ゲーム再起動後に有効）",
            cardFailedFormat = "前回の導入は失敗：{0}",
            failureTitleCompile = "パッチのコンパイルに失敗しました（未保存）",
            failureTitleIdentity = "パッチがゲーム本体と一致しません",
            failureTitleStorage = "保存に失敗しました",
            failureTitleUnexpected = "内部エラー",
            succeededDetailFormat = "導入成功：{0}（{1} 個の関数 / {2} 命令、エントリ {3}）",
            assetPackSucceededDetailFormat = "リソースのみパックを導入しました：{0}（リソース {1} 件）",
            packagedSucceededDetailFormat = "ビルド済みパックを導入しました：{0}（{1} 個の関数 / {2} 命令）",
            warningLineFormat = "警告：{0}",
            savedSourcesFormat = "ソースファイルを保存しました：{0}",
            nameListSeparator = "、",
            restartButton = "ゲームを再起動して有効化",
            reportTitle = "パッチの状態",
            reportNotRefreshed = "まだ取得していません（このページを開くと自動的に読み込みます）",
            reportReadFailedFormat = "パッチ状態の読み取りに失敗：{0}",
            reportRolledBackLine = "読み込み全体を取り消しました：パッチはすべて無効です（パッチなしで動作中）",
            reportNoneLoaded = "このセッションで読み込んだパッチはありません",
            reportLoadedFormat = "読み込み済み：{0}（{1} 個の関数 / {2} 命令）",
            reportLoadFailedFormat = "読み込み失敗：{0}",
            reportLoadWarningFormat = "読み込み警告：{0}",
            reportNoStoredFiles = "mods にパッチ成果物はまだありません",
            reportStoredFilesFormat = "mods のパッチ成果物：{0}",
            noFilesPicked = "ファイルが選択されていません",
            zipUnpackFailedFormat = "口上 zip の展開に失敗しました（{0}）：暗号化または破損の可能性があります —— {1}",
            zipNoEntriesFormat = "口上 zip に導入できる .ERB/.ERH エントリがありません：{0}（その他のファイルはスキップしました）",
            zipLooksLikeAssetPackFormat =
                "これは素材パックのようです。素材の導入カードで選んでください：{0}",
            zipTooManyEntriesFormat =
                "口上 zip のエントリ数が上限を超えています（{0}：最大 {1} 個）—— 導入を拒否しました。" +
                    "人為的に作られたパックの可能性があります",
            zipEntryTooLargeFormat =
                "口上 zip 内のエントリが展開後に上限を超えています（{0}：{1} が {2} MiB 超）—— " +
                    "導入を拒否しました。圧縮爆弾の疑いがあります",
            zipTotalTooLargeFormat =
                "口上 zip 全体の展開後サイズが上限を超えています（{0}：{1} MiB 超）—— " +
                    "導入を拒否しました。圧縮爆弾の疑いがあります",
            zipBombSuspectFormat =
                "口上 zip の圧縮率が上限を超えています（{0}）—— 導入を拒否しました。圧縮爆弾の疑いがあります",
            zipDuplicateEntryFormat =
                "口上 zip に同名のエントリがあります（{0}：{1} が複数回出現）—— 導入を拒否しました。" +
                    "同名上書きの意味が不明確です",
            dedupSkippedWarningFormat =
                "同名ソースは先のものを採用：{0}（保存名 {1} は前のエントリが使用済みのため、これをスキップしました）",
            saveSourceFailedFormat = "ソースファイルの mods への保存に失敗：{0}",
            readBaseFailedFormat = "ゲーム成果物の読み取りに失敗：{0}",
            readArchivedFailedFormat = "保存済みの口上ソースの読み取りに失敗（{0}）：{1}",
            noEntryFunctionFormat =
                "パッチに @関数定義が1つもありません（{0} 個のソースファイルのどれにも使える関数ヘッダがありません）",
            compileFailedFallback = "パッチのコンパイルに失敗しました",
            compileUnexpectedFormat = "パッチのコンパイルで予期しない例外が発生：{0}: {1}",
            artifactEncodeFailedFormat = "パッチ成果物のエンコードに失敗：{0}",
            identityFailedFallback = "パッチが base と一致しません",
            artifactSaveFailedFormat = "パッチ成果物の書き込みに失敗：{0}",
            verifyWrittenFailedFormat = "{0}（書き込み検証に失敗しました）",
            codeArtifactStageFailedFormat =
                "コード成果物の検証に失敗したため、今回のインポートを取り消しました：{0}",
            codeArtifactCommitFailedFormat =
                "コード成果物の書き込みに失敗したため、今回のインポートを取り消しました：{0}",
            modRuntimeOffHint = "Mod 実行の総スイッチが切のため、有効化して新しいセッションを始めると反映されます。",
            modCodeGateHintFormat =
                "サードパーティ製コードパック {0} は次回の読み込みで有効になりません：{1}。" +
                    "設定の「インストール済み Mod」で対処してください。",
            modCodeGateReasonGateOff = "実行のマスタースイッチが切れています",
            modCodeGateReasonUntrusted = "このパックはまだ信頼されていません",
            modCodeGateReasonCorrupt =
                "コード身元情報が壊れているため、このパックの再インポートが必要です",
            modCodeGateReasonMultipleVersions =
                "古いコードが複数バージョン残っているため、このパックを再インポートすると" +
                    "最新の1つだけが残ります",
            overrideSummaryFormat = "うち{0}個の関数はゲーム既存の関数と同じ名前で、導入したパックの方が優先されます。",
            reinstallSummaryFormat = "再インストール v{0}：設定とデータは保持されます",
            reinstallNoVersionSummaryFormat = "再インストール：設定とデータは保持されます",
            versionChangedSummaryFormat = "バージョン v{0} → v{1}：設定とデータは保持されます",
            packMetaPackageIdMismatchFormat =
                "パッケージのメタデータが宣言するパッケージ名 {0} は、同梱のリソースディレクトリ {1} と" +
                    "一致しません。今回は内容ベースでインストールし、バージョン表記は {2} にフォールバックします。",
            engineApiGateWarningFormat =
                "このパックはエンジン API v{0} 以上を要求しますが、現在のエンジンは API v{1} です：" +
                    "インストール後、読み込み時にエンジンが古いため拒否され、このパックは有効になりません。" +
                    "API v{0} に対応したバージョンへ更新してください。",
            engineTooOldWarningFormat =
                "このパックはエンジン v{0} を要求しますが、本機は v{1} です：本機では読み込まれません。" +
                    "アプリを更新してから、もう一度インポートしてください。",
            resourceSummaryFormat = "リソース {0} 件",
            resourceEntryInvalidNameFormat = "リソース項目名が不正のためスキップしました：{0}",
            resourceEntryDuplicateFormat = "リソース項目が重複しているためスキップしました：{0}",
            resourceFileTooLargeFormat =
                "リソース項目が単一ファイル上限（{0} MiB）を超えています：{1}（{2} バイト）。" +
                    "今回のリソースはすべて導入しません",
            resourceTotalTooLargeFormat =
                "リソースの総量が上限（{0} MiB）を超えています：累計 {1} バイト。今回のリソースはすべて導入しません",
            resourcePrefixRebasedFormat =
                "導入パックのリソース {0} 件は元のディレクトリ名がこのパッケージと異なるため、" +
                    "このパッケージのリソースディレクトリに付け替えました：{1}",
            resourcePrefixConflictFormat =
                "リソースをこのパッケージに付け替えた結果、名前が重複したため上書きを避けて導入を拒否しました：{0}（元のディレクトリ名：{1}）",
            resourceStageFailedFormat = "リソースの一時保存に失敗したため、今回リソースは導入しません：{0}",
            resourceCommitFailedFormat = "リソースディレクトリの置き換えに失敗したため、今回のリソースは反映されていません：{0}",
            mediaDirConflictFormat =
                "メディアリソースディレクトリ {0} は既にパッケージ {1} が宣言しているため、今回の " +
                    "パッケージ {2} では同じディレクトリを使用できません。上書きを避けて導入を拒否しました",
            mediaDirInvalidFormat =
                "パッケージのメタデータが宣言するメディアリソースディレクトリ名が不正のため、今回リソースは導入しません：{0}",
            mediaEntryInvalidNameFormat = "メディアリソース項目名が不正のためスキップしました：{0}",
            mediaEntryDuplicateFormat = "メディアリソース項目が重複しているためスキップしました：{0}",
            mediaExtensionRejectedFormat =
                "メディアリソース項目 {0} の拡張子は許可範囲（{1}）外のため、今回のリソースはすべて導入しません",
            mediaFileTooLargeFormat =
                "メディアリソース項目が単一ファイル上限（{0} MiB）を超えています：{1}（{2} バイト）。" +
                    "今回のリソースはすべて導入しません",
            mediaResourceTotalTooLargeFormat =
                "画像・テキストリソースの総量が上限（{0} MiB）を超えています：累計 {1} バイト。今回のリソースはすべて導入しません",
            mediaSoundTotalTooLargeFormat =
                "音声リソースの総量が上限（{0} MiB）を超えています：累計 {1} バイト。今回のリソースはすべて導入しません",
            mediaCombinedTooLargeFormat =
                "メディアリソースの合計が上限（{0} MiB）を超えています：累計 {1} バイト。今回のリソースはすべて導入しません",
            mediaStageFailedFormat = "メディアリソースの一時保存に失敗したため、今回リソースは導入しません：{0}",
            mediaCommitFailedFormat = "メディアリソースの書き込みに失敗したため、今回のリソースは反映されていません：{0}",
            previewTitle = "導入プレビュー",
            previewCodeSourcesFormat = "コード面：ソース {0} 件（.erb {1} / .erh {2}）",
            previewCodeCompiledFormat = "コード面：そのまま実行できるコンパイル済みモジュールを {0} 件含みます",
            previewAssetsFormat = "リソース面：{0} 件、合計 {1} MiB",
            previewMediaFormat = "メディア面：{0} —— 画像/テキスト {1} 件 {2} MiB、音声 {3} 件 {4} MiB",
            previewMediaNoneLine = "メディア面：紐付けディレクトリの宣言なし（resources フィールドがありません）",
            previewExtensionsFormat = "メディアの拡張子：{0}",
            previewExtensionCountFormat = "{0} は {1} 件",
            previewConflictFormat =
                "警告：紐付けディレクトリ {0} は既にパッケージ {1} が宣言しているため、" +
                    "今回の導入はディレクトリ競合で拒否されます",
            previewLimitFormat =
                "警告：{0} が上限 {1} MiB を超えています（実際 {2} MiB）—— 表示のみで、" +
                    "導入時の実際の検証はそのまま行われます",
            previewLimitCombinedFormat =
                "警告：メディア2ツリーの合計が上限 {0} MiB を超えています（実際 {1} MiB）—— " +
                    "表示のみで、導入時の実際の検証はそのまま行われます",
            previewUnavailableFormat =
                "プレビューできません：{0}（導入には影響しません。厳格な検証は導入時に実行されます）",
            previewPackageCountFormat = "パッケージ {0} 件を選択",
            previewPackageCountSingleLine = "このバッチのパッケージは 1 件",
            previewPackageRowFormat = "{0} · {1} · バージョン {2} · ソース {3} / リソース {4}",
            previewPackageVersionUnknown = "バージョン不明",
            previewPackageIdUnknown = "パッケージ名不明",
            previewBatchDirConflictFormat =
                "警告：このバッチの {0} と {1} が同じリソースディレクトリ {2} を宣言しています。" +
                    "1 つのパッケージにまとめられ、このディレクトリを共用します",
            batchResultSummaryFormat = "{0} 件成功 / {1} 件失敗",
            batchResultRowSucceededFormat = "{0} · 成功しました",
            batchResultRowFailedFormat = "{0} · 失敗：{1}",
            batchCardSummaryFormat = "前回の一括導入：{0} 件成功 / {1} 件失敗",
            batchResultOverflowSucceededFormat = "成功 {0} 件を省略しています（タップで展開）",
            batchResultOverflowFailedFormat = "失敗 {0} 件を省略しています（タップで展開）",
            batchResultCollapse = "折りたたむ",
            sourceReadFailedFormat = "{0}：ファイルの内容を読み込めませんでした（移動または削除された可能性があります）",
            allReadsFailedFormat = "選択されたファイルを1つも読み込めませんでした：\n{0}",
            unexpectedFormat = "導入中に予期しない例外が発生：{0}: {1}",
            unknownStorageError = "不明な保存エラー",
            failureGuidanceAuthorFormat = "このエラーは口上パック自身の問題です（移植版の不具合ではありません）。下のレポートを口上の作者へお送りください。",
            failureGuidanceStorageFormat = "端末の保存領域の問題です。空き容量や保存権限を確認して再試行し、それでも失敗する場合は移植版へ報告してください。",
            failureGuidanceUnexpectedFormat = "予期しない内部エラーが発生しました。下のレポートをコピーして移植版へ報告してください。",
            copyErrorReportButton = "エラーレポートをコピー",
            copyWarningsButton = "警告一覧をコピー",
            errorReportTitle = "口上インポートエラーレポート",
            errorReportKindFormat = "種別：{0}",
            errorReportTimeFormat = "日時：{0}",
            errorReportSourcesFormat = "ソース：{0}",
            copyDoneToast = "コピーしました",
            previewReplaceFormat = "インストール済みの旧版 v{0} を置き換えます（旧版の導入日時：{1}）。設定とデータは残ります",
            previewReplaceNoTimeFormat = "インストール済みの旧版 v{0} を置き換えます。設定とデータは残ります",
        )
    }
}
