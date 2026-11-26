package com.eratw.port.android.market

import com.eratw.port.android.SettingsLanguage

/**
 * 市场域（MKM 系列）的玩家面三语文案表（CONTRACT-MKM-W1 §2 mkm-ui 判据 1）。
 *
 * ## 为什么单独一张表
 * 照 [com.eratw.port.android.ModImportCopy] / [com.eratw.port.android.UpdateCopy] 的同款理由：
 * 市场是一条链路（入口卡 → 覆盖层 → 行卡片 → 已装区 → 端点行 → 拒因表），改判读时整组一起动。
 *
 * ## 语义锚（作者面纪律）
 * 拒因人话表锚 kojo-editor `packages/plugins/ui-market/src/market-view.ts` 的 REJECT_TEXT
 * （:77-114）与 REJECT_PREFIX（:147）：**机器词不上主行**——`kind` / `id` / `manifest` /
 * `sha256` / `schema` 这类词在本表里只说「类型 / 编号 / 说明 / 核对码 / 清单格式」的人话形态，
 * 机器原话只进日志与取证面。穷尽映射的判定在 [MarketViewModel]（rejectPhrase 的 when 全臂），
 * 本表只放词。更新提示照 updatePhrase（market-view.ts:451-468）的口径「只说事实不催动作」。
 *
 * ## 三条硬规矩（与既有域表同口径）
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]；壳层用 `MarketCopy.of(language)`
 *    一次取好往里传（市场取数在后台线程跑，语言随调用进线程；本域纯层不碰
 *    SharedPreferences、不判系统 Locale，照 AGENTS 文案表约定 6.3）。
 * 2. **中文底稿**：[ZH] 是中文底稿；EN/JA 措辞另写，不逐字复述中文。
 * 3. **带读数的句子存模板**，由 [fill] 一次替换（与既有域表同一写法：花括号走字符类，
 *    不用反斜杠转义——Android 的 ICU 正则会把转义写法直接抛掉）。
 *
 * 值里不许出现 `=`（[com.eratw.port.android.CopyShapeGate.assertKeyCount] 靠 `toString`
 * 的 `=` 计数对账）与 `{`/`}`（只允许 `{0}`..`{2}` 槽位）。
 *
 * @property lang 这一组对应的设置页语言
 */
data class MarketCopy(
    val lang: SettingsLanguage,

    // ---------------------------------------------------------------- 入口卡与覆盖层骨架
    /** 入口卡标题与覆盖层头部共用的那一句（单源：两处不许各写各的）。 */
    val cardTitle: String,
    /** 入口卡的打开按钮。 */
    val cardOpenButton: String,
    /** 入口卡说明行（覆盖层副题同用这一条）。 */
    val subtitle: String,
    /** 覆盖层右下角的关闭按钮。 */
    val closeButton: String,
    /** 工具条上的回源按钮（照 market-view.ts「拉取列表」那条既有说法）。 */
    val pullButton: String,
    /** 服务未装配时覆盖层的诚实一句（照 market-source.ts NO_SERVICE_VIEW :144-149 口径）。 */
    val serviceMissing: String,

    // ---------------------------------------------------------------- kind 徽章与过滤 chips
    /** kojo-pack 的人读名（market-view.ts KIND_LABEL :36-40）。 */
    val kindKojo: String,
    /** dict-pack 的人读名。 */
    val kindDict: String,
    /** template-pack 的人读名。 */
    val kindTemplate: String,
    /** mod-pack 的人读名（ERAFIN-C3：modgen 作者轨的编译产物 Mod 包）。 */
    val kindMod: String,
    /** 过滤 chips 的「全部」档（filterKind = null）。 */
    val filterAll: String,
    /** 过滤 chips 的「收藏」档（收藏过滤 toggle，CONTRACT-MKM-STATS-UI §1 D2）。 */
    val filterFavorites: String,

    // ---------------------------------------------------------------- 下载量统计与本机收藏（CONTRACT-MKM-STATS-UI）
    /** 行卡统计行：下载量（{0} 真实计数；无数据显示「—」由 MarketReadouts 折，不进本表）。 */
    val downloadCountFormat: String,
    /** 行卡收藏按钮的未收藏态（toggle 到收藏）。 */
    val favoriteAdd: String,
    /** 行卡收藏按钮的已收藏态（toggle 到取消收藏）。 */
    val favoriteAdded: String,
    /** 入口卡概览行：本机收藏数（{0} 计数；这是本机收藏，不是全网数字）。 */
    val cardFavoritesFormat: String,
    /** 入口卡概览行：从未同步过注册表（索引缺席态）。 */
    val cardNotSynced: String,
    /** 入口卡概览统计格的包数标签（与 installedTitle/filterFavorites 同行三格）。 */
    val cardPacksLabel: String,

    // ---------------------------------------------------------------- 行卡片与详情展开
    /** 行卡片元信息行：{0} 作者、{1} 版本、{2} 体积读数。 */
    val rowMetaFormat: String,
    /** 详情展开区的小标题。 */
    val detailTitle: String,
    /** 详情行：{0} 许可。 */
    val detailLicenseFormat: String,
    /** 详情行：{0} 简介（summary 缺省时整行不出现）。 */
    val detailSummaryFormat: String,
    /** 详情行：{0} 来源主页。 */
    val detailSourceFormat: String,
    /** 详情行：{0} 文件数、{1} 解包后体积读数。 */
    val detailFilesFormat: String,
    /** 版本闸不过时行动作位置的那一句（market-view.ts appVersionRequirement :381-383，「编辑器」改「应用」）。 */
    val minAppVersionFormat: String,

    // ---------------------------------------------------------------- 行动作与更新提示
    /** 行动作按钮（market-view.ts ACTION_LABEL :401-405）。 */
    val actionInstall: String,
    /** 行动作按钮（更新 = 覆盖装成新内容）。 */
    val actionUpdate: String,
    /** 更新提示（只说事实不催动作）：{0} 索引版本。 */
    val updateUpdatableFormat: String,
    /** 更新提示：本机比索引还新，{0} 本机版本（market-view.ts :458 `u.local.version`；CONTRACT-MKM-W6 缝 2）。 */
    val updateAheadFormat: String,
    /** 更新提示：同版本已装。 */
    val updateLatest: String,
    /** 更新提示：同版本已装但容器核对码与注册表不同，重装会覆盖（market-view.ts :460；CONTRACT-MKM-W6 缝 2）。 */
    val updateLatestDiverged: String,
    /** 更新检查失败的兜底句（market-view.ts :466 那一句）。 */
    val updateFailedFallback: String,

    // ---------------------------------------------------------------- 安装进度（进度只在这一处显示）
    /** 安装进行中那一句：{0} 包名（market-view.ts installBusyNote :414-416）。 */
    val installBusyFormat: String,
    /** 更新进行中那一句：{0} 包名。 */
    val updateBusyFormat: String,
    /** 下载中（总数已知）：{0} 进行中那句、{1} 百分比（market-view.ts installProgressNote :423-434）。 */
    val progressPercentFormat: String,
    /** 下载中（总数未知，不编假百分比）：{0} 进行中那句、{1} 已收体积读数。 */
    val progressBytesFormat: String,
    /** 解压与核对段（CONTRACT-MKM-W6 缝 3：服务阶段回调 EXTRACT 到场时的那句；总量未知 = 不定态进度条）。 */
    val progressExtract: String,
    /** 搬运落盘段（CONTRACT-MKM-W6 缝 3：服务阶段回调 COMMIT 到场时的那句）。 */
    val progressCommit: String,
    /** 装成：{0} 包名、{1} 版本（market-view.ts installNote :440-442，版本取记账那条）。 */
    val installDoneFormat: String,
    /** 更新成（一键更新链的「只装库」完成形态）：{0} 包名、{1} 版本。 */
    val updateDoneFormat: String,
    /** 更新并装进游戏（一键更新链的「装库+装游戏」完成形态，CONTRACT-MKM-W4-UI3 裁定 1/5）：{0} 包名、{1} 版本。 */
    val updateChainDoneFormat: String,

    // ------------------------------------------------------------ 拒因人话表（穷尽骨架 18 码封闭集，market-view.ts :77-114）
    /** schema-version。 */
    val rejectSchemaVersion: String,
    /** kind-invalid。 */
    val rejectKindInvalid: String,
    /** id-invalid。 */
    val rejectIdInvalid: String,
    /** version-invalid。 */
    val rejectVersionInvalid: String,
    /** redistribute-false。 */
    val rejectRedistributeFalse: String,
    /** path-traversal。 */
    val rejectPathTraversal: String,
    /** path-invalid。 */
    val rejectPathInvalid: String,
    /** too-many-entries。 */
    val rejectTooManyEntries: String,
    /** unpacked-too-big。 */
    val rejectUnpackedTooBig: String,
    /** zip-too-big。 */
    val rejectZipTooBig: String,
    /** sha-mismatch。 */
    val rejectShaMismatch: String,
    /** manifest-mismatch。 */
    val rejectManifestMismatch: String,
    /** id-kind-conflict。 */
    val rejectIdKindConflict: String,
    /** app-version-unsupported。 */
    val rejectAppVersionUnsupported: String,
    /** endpoint-forbidden。 */
    val rejectEndpointForbidden: String,
    /** download-failed。 */
    val rejectDownloadFailed: String,
    /** extract-failed。 */
    val rejectExtractFailed: String,
    /** staging-failed。 */
    val rejectStagingFailed: String,
    /** 未知码兜底：{0} 机器码原样给出（不静默；market-view.ts rejectNote :163 的原话出口）。 */
    val rejectUnknownFormat: String,
    /** 拒因上屏行：{0} 包名、{1} 拒因人话（market-view.ts REJECT_PREFIX :147 那一处前缀）。 */
    val rejectNoteFormat: String,

    // ---------------------------------------------------------------- 已装管理
    /** 卸载确认框标题：{0} 包名（actions.ts runUninstall :237-244 的一次确认门）。 */
    val uninstallConfirmTitleFormat: String,
    /** 卸载确认框正文。 */
    val uninstallConfirmBody: String,
    /** 卸载确认框的确认键。 */
    val uninstallConfirmOk: String,
    /** 卸载确认框的取消键。 */
    val uninstallConfirmCancel: String,
    /** 卸载进行中：{0} 包名（market-panel.ts:290）。 */
    val uninstallRunningFormat: String,
    /** 卸成：{0} 包名（actions.ts :247）。 */
    val uninstalledFormat: String,
    /** 本机没有这个包（NotFound 态；market-view.ts REJECT_TEXT installed-missing 同口径）。 */
    val uninstallNotFound: String,
    /** 卸载失败：{0} 原话（actions.ts :252 不静默）。 */
    val uninstallFailedFormat: String,
    /** 卸载联动失败句（CONTRACT-MKM-W4-UNLINK 组长接线）：库已清但游戏产物/台账清理失败，缀在卸载成功句后；Cleaned/Kept 静默。 */
    val uninstallSyncFailedFormat: String,

    // ---------------------------------------------------------------- 已装区手动清扫（CONTRACT-MKM-W5-GC 余项 D3）
    /** 已装区的「清理旧版本」按钮（清掉库内被超越版本目录；只动市场库 packs 子树，mods 与导入台账零触碰）。 */
    val sweepButton: String,
    /** 清扫进行中那一句。 */
    val sweepBusy: String,
    /** 清扫回执：清掉 {0} 个旧版本（计数取 Swept 回执）。 */
    val sweepDoneFormat: String,
    /** 清扫回执：库内没有要清的旧版（实话不是失败，不给失败色）。 */
    val sweepNothing: String,
    /** 清扫失败：{0} 失败原话（不静默）。 */
    val sweepFailedFormat: String,

    // ---------------------------------------------------------------- 已装区
    /** 已装区小标题。 */
    val installedTitle: String,
    /** 已装区计数：{0} 条数、{1} 占用体积读数（market-view.ts installedCountPhrase :504-507）。 */
    val installedCountFormat: String,
    /** 已装区空态主句（market-view.ts INSTALLED_EMPTY :546-549）。 */
    val installedEmptyTitle: String,
    /** 已装区空态副句。 */
    val installedEmptyHint: String,
    /** 已装来源（有容器摘要 = 压缩包；market-view.ts :660）。 */
    val installedSourceContainer: String,
    /** 已装来源（目录源 = 本机文件夹）。 */
    val installedSourceFolder: String,
    /** 内容核对码前 8 位（机器读数只进展开层；market-view.ts digestPrefix :633）。 */
    val installedDigestFormat: String,

    // ---------------------------------------------------------------- 索引三态与回执
    /** 覆盖层刚开、本地第一段还没回来（market-panel.ts:207-214 的本地段 busy）。 */
    val indexLoading: String,
    /** 索引缺席（从未成功取过）：一句人话 + 一条退路（market-view.ts STATUS_TEXT list-missing :51）。 */
    val statusAbsent: String,
    /** 索引非法：{0} 校验拒因（回源成功但校验拒，盘上旧列表原样保留）。 */
    val statusInvalidFormat: String,
    /** 索引正常但零条目（market-view.ts STATUS_TEXT no-packs :54）。 */
    val statusNoPacks: String,
    /** 回源回执（正常）：{0} 条数（market-view.ts refreshNote refreshed+ok :289-291）。 */
    val pullOkFormat: String,
    /** 回源回执（TTL 内没回源，用本机最近拉取的那份；market.ts cached「不该装作拉过」；CONTRACT-MKM-W6 缝 1 新键）。 */
    val pullCachedNote: String,
    /** 回源回执（本机这份读不出内容；refreshNote :291-292）。 */
    val pullInvalidNote: String,
    /** 被剔除条目出声：{0} 条数（market-view.ts droppedNotice :306-314；骨架只给计数，不给名单）。 */
    val droppedNoticeFormat: String,
    /** 封面不合规 ≠ 包不合规：{0} 条数（market-view.ts coverIgnoredNotice :321-328）。 */
    val coverIgnoredFormat: String,
    /** 数据时间短读数：{0} 时间（market-view.ts dataTimeSummary :365-369）。 */
    val syncTimeFormat: String,
    /** 还没有数据时间。 */
    val noDataTime: String,
    /** 服务读数本身失败（异常面原话照给，不静默；actions.ts failedSnapshot :99-113）。 */
    val readFailedFormat: String,

    // ---------------------------------------------------------------- 端点行（market-source.ts）
    /** 端点行小标题（market-source.ts「注册表来源」一条）。 */
    val endpointTitle: String,
    /** 端点行说明（无代理输入框的裁定口径：market-source.ts:9-13）。 */
    val endpointIntro: String,
    /** 保存按钮。 */
    val endpointSave: String,
    /** 恢复默认按钮（覆盖态才出现；sourceRowUi showReset :129）。 */
    val endpointReset: String,
    /** 状态主句：默认地址（sourceRowUi :130）。 */
    val endpointStatusDefault: String,
    /** 状态主句：自建地址。 */
    val endpointStatusOverridden: String,
    /** 与生效值一致 ⇒ 不写存储（market-source.ts planEndpointEdit :30-37 的 keep）。 */
    val endpointKept: String,
    /** 恢复默认的回执（market-source.ts :177-179）。 */
    val endpointResetDone: String,
    /** 换自建地址的回执（market-source.ts :178-180）。 */
    val endpointSetDone: String,
    /** 形态闸不过：{0} 拒因（market-source.ts :165）。 */
    val endpointInvalidFormat: String,

    // ---------------------------------------------------------------- 组与分组读数
    /** 组标题：{0} 组名、{1} 计数短语（market-view.ts groupCountPhrase :495-497）。 */
    val groupHeaderFormat: String,
    /** 组内计数：{0} 条数。 */
    val groupCountFormat: String,

    // ---------------------------------------------------------------- 装进游戏（桥五态，CONTRACT-MKM-W3-UI2）
    // 五态的形状权威 = MarketBridgeOutcome 的 KDoc（封闭密封族「五态不许加」）；本组只放词，
    // 判定半边在 MarketBridgeUi.bridgeNote 的 when 穷尽（由 MarketViewModelTest 钉死）。
    /** 已装行上的「装进游戏」按钮（装无确认门：W3-UI2 裁定 4）。 */
    val bridgeInstallButton: String,
    /** 装进游戏进行中：{0} 包名（桥无进度回调缝，一句进行中顶到底）。 */
    val bridgeBusyFormat: String,
    /** 装成：{0} 新产物 packageId 短码；只说已入 mods/rt2 与重启生效（裁定 5：总闸口径不进文案）。 */
    val bridgeOkFormat: String,
    /** 空包指路：包里没有可导入的 .ERB/.ERH 条目（语义对齐 ModImportCopy.zipNoEntriesFormat :201 的空包口径）。 */
    val bridgeEmptyPack: String,
    /** 已装包不在位（多半刚被卸载）：没有任何写入发生（Outcome NotFound 的「零写入」要如实）。 */
    val bridgeNotFound: String,
    /** 导入链失败：{0} 包名、{1} 失败类别人话（复用既有导入失败句式 modImportFailureTitle；failure 原文不上屏，契约 §2.4）。 */
    val bridgeFailedFormat: String,
    /** 台账没记上：导入本体已落盘、只有记账没成（Outcome KDoc 要求人话交代这一点）。 */
    val bridgeLedgerFailed: String,
) {
    /**
     * 句式占位符 `{0}`..`{2}`：单趟替换，参数本身带 `{n}`（例如异常原话）不会被二次展开。
     *
     * **花括号必须走字符类而不是反斜杠转义**：Android 的正则是 ICU 引擎，转义写法直接抛
     * `PatternSyntaxException`（OpenJDK 却容忍）——照既有域表的同一写法，真机实测过的坑不踩第二遍。
     */
    fun fill(template: String, vararg args: Any): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言：不许在市场侧再造一套语言判定。 */
        fun of(lang: SettingsLanguage): MarketCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：市场域的中文底稿（对拍右轴）。 */
        val ZH: MarketCopy = MarketCopy(
            lang = SettingsLanguage.ZH,
            cardTitle = "市场",
            cardOpenButton = "打开市场",
            subtitle = "浏览注册表上的口上包 / 字典包 / 模板包 / Mod 包，下载并装进本机市场库",
            closeButton = "关闭",
            pullButton = "拉取列表",
            serviceMissing = "市场服务还没装配：入口先挂在这里，等取数管线接好再用",
            kindKojo = "口上包",
            kindDict = "字典包",
            kindTemplate = "模板包",
            kindMod = "Mod 包",
            filterAll = "全部",
            filterFavorites = "收藏",
            downloadCountFormat = "下载 {0}",
            favoriteAdd = "收藏",
            favoriteAdded = "已收藏",
            cardFavoritesFormat = "收藏 {0} 个",
            cardNotSynced = "尚未同步注册表",
            cardPacksLabel = "包",
            rowMetaFormat = "作者 {0} · 版本 {1} · {2}",
            detailTitle = "详情",
            detailLicenseFormat = "许可：{0}",
            detailSummaryFormat = "简介：{0}",
            detailSourceFormat = "来源主页：{0}",
            detailFilesFormat = "内容 {0} 个文件，共 {1}",
            minAppVersionFormat = "这个包要求应用不低于版本 {0}",
            actionInstall = "安装",
            actionUpdate = "更新",
            updateUpdatableFormat = "可更新到 {0}",
            updateAheadFormat = "本机已是 {0}（比注册表那条新）",
            updateLatest = "已安装",
            updateLatestDiverged = "已安装（注册表那份与本机这份内容不同，重装会覆盖）",
            updateFailedFallback = "这一条的更新检查读不出（本机那份没被动过）",
            installBusyFormat = "正在安装「{0}」…",
            updateBusyFormat = "正在更新「{0}」…",
            progressPercentFormat = "{0}（{1}%）",
            progressBytesFormat = "{0} 已收 {1}",
            progressExtract = "包已取回，正在解压并核对内容…",
            progressCommit = "正在搬进本机市场库…",
            installDoneFormat = "已装上「{0}」（版本 {1}）",
            updateDoneFormat = "已把「{0}」更新到版本 {1}",
            updateChainDoneFormat = "已把「{0}」更新到版本 {1}，并装进了游戏（重启游戏生效）",
            rejectSchemaVersion = "这个包用的清单格式比本机新，先升级应用",
            rejectKindInvalid = "这个包的类型本机不认识",
            rejectIdInvalid = "这个包的编号写法不合规",
            rejectVersionInvalid = "这个包的版本号写法不合规",
            rejectRedistributeFalse = "作者没有许可二次分发，这个包不给装",
            rejectPathTraversal = "这个包里有条内容想往上跳目录，本机没动",
            rejectPathInvalid = "这个包里有条内容的存放位置不合规范，本机没动",
            rejectTooManyEntries = "这个包里的文件数量超过上限，本机没动",
            rejectUnpackedTooBig = "这个包解开的总体积超过上限，本机没动",
            rejectZipTooBig = "这个压缩包的体积超过上限，本机没动",
            rejectShaMismatch = "取回来的包和注册表钉的不是同一份，本机没动",
            rejectManifestMismatch = "这个包的内容和它的说明对不上，本机没动",
            rejectIdKindConflict = "同一个编号在本机已被另一种类型的包占用，装下去会互相盖掉",
            rejectAppVersionUnsupported = "这个包要求更新版本的应用",
            rejectEndpointForbidden = "这个地址不在放行列表里（只放行 GitHub 系主机），本机没动",
            rejectDownloadFailed = "这个包没能从注册表取回来（网络或地址的问题），本机没动",
            rejectExtractFailed = "这个包打不开（里面的路径或体积不合格），本机没动",
            rejectStagingFailed = "最后一步搬进本机市场库没成功，本机没留半成品",
            rejectUnknownFormat = "出了个没认出来的错误（{0}）：本机没动过",
            rejectNoteFormat = "「{0}」没装成：{1}",
            uninstallConfirmTitleFormat = "要卸掉「{0}」吗？",
            uninstallConfirmBody = "会把这个包从本机市场库删掉。没点「卸载」之前一个字节都不会动。",
            uninstallConfirmOk = "卸载",
            uninstallConfirmCancel = "取消",
            uninstallRunningFormat = "正在卸掉「{0}」…",
            uninstalledFormat = "已卸掉「{0}」（已装列表与市场目录都已清除）",
            uninstallNotFound = "本机没有这个包，不用卸",
            uninstallFailedFormat = "卸载没有成功：{0}",
            uninstallSyncFailedFormat = "包已从市场库移除，但游戏侧的旧内容清理没有成功；重新卸载一次或重新安装该包即可补上",
            sweepButton = "清理旧版本",
            sweepBusy = "正在清理库内旧版本…",
            sweepDoneFormat = "已清掉 {0} 个旧版本",
            sweepNothing = "库内没有要清的旧版本（每个包都已停在最高版）",
            sweepFailedFormat = "清扫没有成功：{0}",
            installedTitle = "已安装",
            installedCountFormat = "{0} 个 · 占用 {1}",
            installedEmptyTitle = "本机还没有装好的包",
            installedEmptyHint = "在上方挑一条点「安装」，装好的会排在这里",
            installedSourceContainer = "压缩包",
            installedSourceFolder = "本机文件夹",
            installedDigestFormat = "内容核对码前 8 位：{0}",
            indexLoading = "正在读取本机列表…",
            statusAbsent = "本机还没有包列表：点上面的「拉取列表」从注册表同步",
            statusInvalidFormat = "包列表读不出内容（{0}）：点「拉取列表」用注册表那份覆盖它",
            statusNoPacks = "包列表里暂时没有可展示的包",
            pullOkFormat = "已拉取注册表列表（{0} 个包）",
            pullCachedNote = "列表还是本机最近拉取的那份（在自动刷新的有效期内，这次没有重新联网）",
            pullInvalidNote = "注册表列表现在读不出：本机这份也不能用，点「拉取清单」用注册表副本覆盖",
            droppedNoticeFormat = "包列表里有 {0} 条破损或互相冲突的条目，没有列出、也不许安装（这是注册表那边的问题，不是你的包）",
            coverIgnoredFormat = "有 {0} 个包的封面链接不合格，已按无封面列出（包本身不受影响）",
            syncTimeFormat = "上次同步 {0}",
            noDataTime = "还没有数据时间",
            readFailedFormat = "读取列表失败：{0}",
            endpointTitle = "索引来源",
            endpointIntro = "默认用官方索引地址；可换成自建地址（只放行 GitHub 系主机）。清空后保存就是恢复默认。",
            endpointSave = "保存",
            endpointReset = "恢复默认",
            endpointStatusDefault = "当前用的是默认地址",
            endpointStatusOverridden = "当前用的是自建地址",
            endpointKept = "没有改动",
            endpointResetDone = "已恢复默认索引地址（下次刷新列表生效）",
            endpointSetDone = "已改用自建索引地址（只放行 GitHub 系主机，下次刷新列表生效）",
            endpointInvalidFormat = "没有保存：{0}",
            groupHeaderFormat = "{0}（{1}）",
            groupCountFormat = "{0} 个",
            bridgeInstallButton = "装进游戏",
            bridgeBusyFormat = "正在把「{0}」装进游戏…",
            bridgeOkFormat = "已装进游戏（{0}）：内容已入 mods/rt2，重启游戏生效",
            bridgeEmptyPack =
                "这个包里没有可导入的 .ERB/.ERH 条目，本机没动：" +
                "口上源要放在包的 files/ 树里（素材类内容不用装进游戏）",
            bridgeNotFound = "本机市场库里已经没有这个包了（多半刚被卸载），什么都没动",
            bridgeFailedFormat = "「{0}」没装进游戏：{1}",
            bridgeLedgerFailed =
                "已装进游戏，但导入台账没记上：新内容已在 mods/rt2，重启游戏生效" +
                "（下次重导同一个包会按首次导入处理）",
        )

        /** English：结构与 [ZH] 逐条同形（同样几行、同样几个读数），措辞另写，不抄中文。 */
        val EN: MarketCopy = MarketCopy(
            lang = SettingsLanguage.EN,
            cardTitle = "Market",
            cardOpenButton = "Open market",
            subtitle = "Browse kojo / dict / template / mod packs from the registry, download and install them locally",
            closeButton = "Close",
            pullButton = "Fetch list",
            serviceMissing =
                "The market service is not wired up yet: this entry is parked " +
                "here until the fetch pipeline lands",
            kindKojo = "Kojo pack",
            kindDict = "Dict pack",
            kindTemplate = "Template pack",
            kindMod = "Mod pack",
            filterAll = "All",
            filterFavorites = "Favorites",
            downloadCountFormat = "Downloads: {0}",
            favoriteAdd = "Favorite",
            favoriteAdded = "Favorited",
            cardFavoritesFormat = "{0} favorites",
            cardNotSynced = "Not synced yet",
            cardPacksLabel = "Packs",
            rowMetaFormat = "By {0} - version {1} - {2}",
            detailTitle = "Details",
            detailLicenseFormat = "License: {0}",
            detailSummaryFormat = "Summary: {0}",
            detailSourceFormat = "Home page: {0}",
            detailFilesFormat = "{0} content files, {1} unpacked",
            minAppVersionFormat = "This pack needs app version {0} or newer",
            actionInstall = "Install",
            actionUpdate = "Update",
            updateUpdatableFormat = "Update available: {0}",
            updateAheadFormat = "Local copy is {0} (newer than the registry entry)",
            updateLatest = "Installed",
            updateLatestDiverged =
                "Installed (the registry copy differs from this local one; reinstalling will overwrite it)",
            updateFailedFallback = "The update check for this entry could not be read (the local copy was not touched)",
            installBusyFormat = "Installing \"{0}\"...",
            updateBusyFormat = "Updating \"{0}\"...",
            progressPercentFormat = "{0} ({1}%)",
            progressBytesFormat = "{0} received {1}",
            progressExtract = "Package fetched, extracting and verifying...",
            progressCommit = "Moving into the local market library...",
            installDoneFormat = "Installed \"{0}\" (version {1})",
            updateDoneFormat = "Updated \"{0}\" to version {1}",
            updateChainDoneFormat =
                "Updated \"{0}\" to version {1} and installed it into the game " +
                "(takes effect after restarting the game)",
            rejectSchemaVersion = "This pack uses a newer manifest format; update the app first",
            rejectKindInvalid = "This pack has a type this app does not know",
            rejectIdInvalid = "This pack's identifier is not well-formed",
            rejectVersionInvalid = "This pack's version number is not well-formed",
            rejectRedistributeFalse = "The author did not allow redistribution, so this pack cannot be installed",
            rejectPathTraversal = "One file in this pack tries to escape its folder, nothing was touched",
            rejectPathInvalid = "One file in this pack sits in a location that breaks the rules, nothing was touched",
            rejectTooManyEntries = "This pack contains too many files, nothing was touched",
            rejectUnpackedTooBig = "This pack unpacks to more than the size limit, nothing was touched",
            rejectZipTooBig = "This archive is over the size limit, nothing was touched",
            rejectShaMismatch = "The downloaded pack is not the one the registry pinned, nothing was touched",
            rejectManifestMismatch = "The pack contents do not match its manifest, nothing was touched",
            rejectIdKindConflict =
                "Another pack of a different type already occupies this identifier; " +
                "installing would overwrite it",
            rejectAppVersionUnsupported = "This pack requires a newer app version",
            rejectEndpointForbidden = "This address is not on the allow list (GitHub hosts only), nothing was touched",
            rejectDownloadFailed =
                "This pack could not be fetched from the registry (network or address problem), " +
                "nothing was touched",
            rejectExtractFailed = "This pack could not be opened (paths or sizes fail the rules), nothing was touched",
            rejectStagingFailed =
                "The final move into the local library failed, " +
                "no half-written files were left behind",
            rejectUnknownFormat = "An unrecognized error occurred ({0}): nothing was touched",
            rejectNoteFormat = "\"{0}\" was not installed: {1}",
            uninstallConfirmTitleFormat = "Uninstall \"{0}\"?",
            uninstallConfirmBody =
                "This removes the pack from the local market library. " +
                "Nothing changes until you tap Uninstall.",
            uninstallConfirmOk = "Uninstall",
            uninstallConfirmCancel = "Cancel",
            uninstallRunningFormat = "Uninstalling \"{0}\"...",
            uninstalledFormat = "Uninstalled \"{0}\" (removed from the installed list and the market library)",
            uninstallNotFound = "This pack is not installed, nothing to remove",
            uninstallFailedFormat = "Uninstalling failed: {0}",
            uninstallSyncFailedFormat =
                "The pack was removed from the market library, but cleaning up its game-side " +
                "copy failed; uninstall once more or reinstall the pack to finish the cleanup",
            sweepButton = "Clean up old versions",
            sweepBusy = "Cleaning up old versions in the library...",
            sweepDoneFormat = "Cleaned up {0} old versions",
            sweepNothing = "No old versions to clean up (every pack is already at its newest version)",
            sweepFailedFormat = "Cleanup failed: {0}",
            installedTitle = "Installed",
            installedCountFormat = "{0} packs, {1} in use",
            installedEmptyTitle = "No packs installed yet",
            installedEmptyHint = "Pick one above and tap Install; installed packs will appear here",
            installedSourceContainer = "archive",
            installedSourceFolder = "local folder",
            installedDigestFormat = "Content digest (first 8): {0}",
            indexLoading = "Reading the local list...",
            statusAbsent = "No local pack list yet: tap Fetch list above to sync from the registry",
            statusInvalidFormat =
                "The local pack list could not be read ({0}): tap Fetch list " +
                "to overwrite it with the registry copy",
            statusNoPacks = "The pack list has nothing to show right now",
            pullOkFormat = "Fetched the registry list ({0} packs)",
            pullCachedNote =
                "Showing the most recently fetched local list (still within its auto-refresh window; " +
                "no network request was made)",
            pullInvalidNote =
                "The registry list cannot be read right now and the local copy is unusable " +
                "too; tap Fetch list to overwrite it with the registry copy",
            droppedNoticeFormat =
                "{0} broken or conflicting entries in the list are not shown and cannot be installed " +
                "(a registry-side problem, not yours)",
            coverIgnoredFormat =
                "{0} packs have an invalid cover link and are listed without one " +
                "(the packs themselves are fine)",
            syncTimeFormat = "Last synced {0}",
            noDataTime = "No sync time yet",
            readFailedFormat = "Reading the list failed: {0}",
            endpointTitle = "List source",
            endpointIntro =
                "The official registry address is used by default; you can point it to your own " +
                "(GitHub hosts only). Save with an empty box to restore the default.",
            endpointSave = "Save",
            endpointReset = "Restore default",
            endpointStatusDefault = "Using the default address",
            endpointStatusOverridden = "Using a custom address",
            endpointKept = "No change",
            endpointResetDone = "Restored the default list address (takes effect on the next refresh)",
            endpointSetDone = "Switched to a custom list address (GitHub hosts only, takes effect on the next refresh)",
            endpointInvalidFormat = "Not saved: {0}",
            groupHeaderFormat = "{0} ({1})",
            groupCountFormat = "{0} packs",
            bridgeInstallButton = "Install to game",
            bridgeBusyFormat = "Installing \"{0}\" into the game...",
            bridgeOkFormat =
                "Installed into the game ({0}): the content is in mods/rt2 and " +
                "takes effect after restarting the game",
            bridgeEmptyPack =
                "This pack has no importable .ERB/.ERH entries, nothing was touched: " +
                "kojo sources sit under the pack's files/ tree (asset content does not go into the game)",
            bridgeNotFound =
                "This pack is no longer in the local market library (probably just " +
                "uninstalled), nothing was touched",
            bridgeFailedFormat = "\"{0}\" was not installed into the game: {1}",
            bridgeLedgerFailed =
                "Installed into the game, but the import ledger was not updated: the new " +
                "content is in mods/rt2 and takes effect after restarting the game " +
                "(re-importing the same pack later will run as a first-time import)",
        )

        /** 日本語：结构与 [ZH] 逐条同形。 */
        val JA: MarketCopy = MarketCopy(
            lang = SettingsLanguage.JA,
            cardTitle = "マーケット",
            cardOpenButton = "マーケットを開く",
            subtitle = "登録にある口上パック / 辞書パック / テンプレートパック / Mod パックを見て、端末のマーケット庫に入れます",
            closeButton = "閉じる",
            pullButton = "一覧を取得",
            serviceMissing = "マーケットサービスが未接続です：取得経路がつながるまで入口だけ置いておきます",
            kindKojo = "口上パック",
            kindDict = "辞書パック",
            kindTemplate = "テンプレートパック",
            kindMod = "Mod パック",
            filterAll = "全部",
            filterFavorites = "お気に入り",
            downloadCountFormat = "ダウンロード {0}",
            favoriteAdd = "お気に入り",
            favoriteAdded = "登録済み",
            cardFavoritesFormat = "お気に入り {0} 件",
            cardNotSynced = "まだ同期していません",
            cardPacksLabel = "パック",
            rowMetaFormat = "作者 {0} · バージョン {1} · {2}",
            detailTitle = "詳細",
            detailLicenseFormat = "ライセンス：{0}",
            detailSummaryFormat = "概要：{0}",
            detailSourceFormat = "配布元：{0}",
            detailFilesFormat = "内容 {0} ファイル、合計 {1}",
            minAppVersionFormat = "このパックにはアプリ バージョン {0} 以上が必要です",
            actionInstall = "インストール",
            actionUpdate = "更新",
            updateUpdatableFormat = "{0} に更新できます",
            updateAheadFormat = "端末は {0}（登録のものより新しいです）",
            updateLatest = "インストール済み",
            updateLatestDiverged = "インストール済み（登録のものと端末の中身が異なります、再インストールすると上書きされます）",
            updateFailedFallback = "この項目の更新確認が読み出せません（端末側はそのままです）",
            installBusyFormat = "「{0}」をインストールしています…",
            updateBusyFormat = "「{0}」を更新しています…",
            progressPercentFormat = "{0}（{1}%）",
            progressBytesFormat = "{0} {1} を受信済み",
            progressExtract = "パック取得済み、展開と照合をしています…",
            progressCommit = "マーケット庫へ移しています…",
            installDoneFormat = "「{0}」をインストールしました（バージョン {1}）",
            updateDoneFormat = "「{0}」をバージョン {1} に更新しました",
            updateChainDoneFormat = "「{0}」をバージョン {1} に更新し、ゲームへも導入しました（ゲーム再起動で有効）",
            rejectSchemaVersion = "このパックの説明書式は端末より新しいため、先にアプリを更新してください",
            rejectKindInvalid = "このパックの種類を端末は認識できません",
            rejectIdInvalid = "このパックの識別子の書き方が規則に合っていません",
            rejectVersionInvalid = "このパックのバージョン番号の書き方が規則に合っていません",
            rejectRedistributeFalse = "作者が再配布を許可していないため、このパックは入れられません",
            rejectPathTraversal = "このパック内のあるファイルが上位フォルダへ脱出しようとしています、端末側はそのままです",
            rejectPathInvalid = "このパック内のあるファイルの置き場所が規則に合っていません、端末側はそのままです",
            rejectTooManyEntries = "このパックのファイル数が上限を超えています、端末側はそのままです",
            rejectUnpackedTooBig = "このパックの展開後の合計が上限を超えています、端末側はそのままです",
            rejectZipTooBig = "このアーカイブの容量が上限を超えています、端末側はそのままです",
            rejectShaMismatch = "取得したパックが登録の釘付けと一致しません、端末側はそのままです",
            rejectManifestMismatch = "パックの中身と説明が一致しません、端末側はそのままです",
            rejectIdKindConflict = "同じ識別子が別種類のパックで使用済みのため、入れると上書きされてしまいます",
            rejectAppVersionUnsupported = "このパックにはより新しいアプリ バージョンが必要です",
            rejectEndpointForbidden = "このアドレスは許可リストにありません（GitHub 系のみ）、端末側はそのままです",
            rejectDownloadFailed = "このパックを登録から取得できませんでした（ネットワークかアドレスの問題）、端末側はそのままです",
            rejectExtractFailed = "このパックを開けませんでした（経路か容量が規則に合いません）、端末側はそのままです",
            rejectStagingFailed = "最後のマーケット庫への移動に失敗しました、中途半端な成果物は残っていません",
            rejectUnknownFormat = "認識できないエラーが発生しました（{0}）：端末側はそのままです",
            rejectNoteFormat = "「{0}」をインストールできませんでした：{1}",
            uninstallConfirmTitleFormat = "「{0}」をアンインストールしますか？",
            uninstallConfirmBody = "このパックをマーケット庫から削除します。「アンインストール」を押すまで何も変わりません。",
            uninstallConfirmOk = "アンインストール",
            uninstallConfirmCancel = "キャンセル",
            uninstallRunningFormat = "「{0}」をアンインストールしています…",
            uninstalledFormat = "「{0}」をアンインストールしました（インストール済み一覧とマーケット庫から削除）",
            uninstallNotFound = "このパックは端末に入っていないため、削除の必要はありません",
            uninstallFailedFormat = "アンインストールに失敗しました：{0}",
            uninstallSyncFailedFormat = "パックはマーケット庫から削除しましたが、ゲーム側の旧内容の掃除に失敗しました；もう一度アンインストールするか、パックを入れ直せば完了します",
            sweepButton = "旧バージョンを削除",
            sweepBusy = "ライブラリの旧バージョンを削除しています…",
            sweepDoneFormat = "旧バージョンを {0} 件削除しました",
            sweepNothing = "削除する旧バージョンはありません（どのパックも最新版のままです）",
            sweepFailedFormat = "削除に失敗しました：{0}",
            installedTitle = "インストール済み",
            installedCountFormat = "{0} 件 · 使用 {1}",
            installedEmptyTitle = "インストール済みのパックはまだありません",
            installedEmptyHint = "上で一つ選んで「インストール」を押すと、ここに並びます",
            installedSourceContainer = "アーカイブ",
            installedSourceFolder = "端末フォルダ",
            installedDigestFormat = "内容照合コード上位 8 桁：{0}",
            indexLoading = "端末の一覧を読み込んでいます…",
            statusAbsent = "端末にパック一覧がまだありません：上の「一覧を取得」で登録と同期してください",
            statusInvalidFormat = "パック一覧を読み出せません（{0}）：「一覧を取得」で登録のものに置き換えてください",
            statusNoPacks = "パック一覧に表示できるものが今はありません",
            pullOkFormat = "登録の一覧を取得しました（{0} パック）",
            pullCachedNote = "一覧は端末に最近取得したもののままです（自動更新の有効期内で、今回は通信していません）",
            pullInvalidNote = "登録の一覧は今読み出せません：端末のこの一覧も使えないので、「一覧を取得」で登録のコピーで上書きしてください",
            droppedNoticeFormat = "一覧に {0} 件の破損または競合する項目があり、表示もインストールもしていません（登録側の問題で、あなたのパックではありません）",
            coverIgnoredFormat = "{0} 個のパックのカバー リンクが不正のため、カバーなしで表示しています（パック自体は問題ありません）",
            syncTimeFormat = "前回同期 {0}",
            noDataTime = "まだデータ時刻がありません",
            readFailedFormat = "一覧の読み取りに失敗しました：{0}",
            endpointTitle = "一覧の取得元",
            endpointIntro = "既定では公式の取得元アドレスを使います。自前のアドレスにも変えられます（GitHub 系のみ）。空で保存すると既定に戻ります。",
            endpointSave = "保存する",
            endpointReset = "既定に戻す",
            endpointStatusDefault = "既定のアドレスを使用中",
            endpointStatusOverridden = "自前のアドレスを使用中",
            endpointKept = "変更なし",
            endpointResetDone = "既定の取得元アドレスに戻しました（次の一覧更新から有効）",
            endpointSetDone = "自前の取得元アドレスに変更しました（GitHub 系のみ、次の一覧更新から有効）",
            endpointInvalidFormat = "保存できませんでした：{0}",
            groupHeaderFormat = "{0}（{1}）",
            groupCountFormat = "{0} 件",
            bridgeInstallButton = "ゲームへ導入",
            bridgeBusyFormat = "「{0}」をゲームへ導入しています…",
            bridgeOkFormat = "ゲームへ導入しました（{0}）：内容は mods/rt2 に入っています、ゲーム再起動で有効になります",
            bridgeEmptyPack =
                "このパックには導入できる .ERB/.ERH エントリがありません、端末側はそのままです：" +
                "口上ソースはパックの files/ ツリーの下に置いてください（素材類はゲームへ導入しません）",
            bridgeNotFound = "このパックはもう端末のマーケット庫にありません（アンインストールされた可能性が高いです）、何も変わっていません",
            bridgeFailedFormat = "「{0}」をゲームへ導入できませんでした：{1}",
            bridgeLedgerFailed =
                "ゲームへは導入しましたが、導入台帳に記録できませんでした：新しい内容は mods/rt2 にあり、" +
                "ゲーム再起動で有効になります（同じパックを再導入するときは初回導入として扱われます）",
        )
    }
}
