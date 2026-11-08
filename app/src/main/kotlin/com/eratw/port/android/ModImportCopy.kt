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

    // ---------------------------------------------------------------- 宿主响应链（SettingsActivity 的导入段）
    /** 单个 SAF 文件读不出：{0} 文件名或 uri 段。 */
    val sourceReadFailedFormat: String,
    /** 全部源文件都没读出来：{0} 已按换行连好的逐条原话。 */
    val allReadsFailedFormat: String,
    /** 导入线程兜底（报错页照上屏）：{0} 异常类名、{1} 异常原话。 */
    val unexpectedFormat: String,
    /** 报告快照读取失败且异常连类名都没有时的最后一档。 */
    val unknownStorageError: String,
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
            cardTitle = "补丁（MOD）导入",
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
            verifyWrittenFailedFormat = "{0}（不变量 I8）",
            sourceReadFailedFormat = "{0}：读不出文件内容（可能已被移动或删除）",
            allReadsFailedFormat = "所选文件一个都读不出来：\n{0}",
            unexpectedFormat = "导入发生未预期异常：{0}: {1}",
            unknownStorageError = "未知存储错误",
        )

        /** English：结构与 [ZH] 逐条同形（同样几行、同样几个读数），措辞另写，不抄中文。 */
        val EN: ModImportCopy = ModImportCopy(
            lang = SettingsLanguage.EN,
            cardTitle = "Patch (MOD) import",
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
            verifyWrittenFailedFormat = "{0} (invariant I8)",
            sourceReadFailedFormat = "{0}: could not read the file contents (it may have been moved or deleted)",
            allReadsFailedFormat = "None of the selected files could be read:\n{0}",
            unexpectedFormat = "Unexpected error during import: {0}: {1}",
            unknownStorageError = "Unknown storage error",
        )

        /** 日本語：结构与 [ZH] 逐条同形。 */
        val JA: ModImportCopy = ModImportCopy(
            lang = SettingsLanguage.JA,
            cardTitle = "パッチ（MOD）導入",
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
            verifyWrittenFailedFormat = "{0}（不変条件 I8）",
            sourceReadFailedFormat = "{0}：ファイルの内容を読み込めませんでした（移動または削除された可能性があります）",
            allReadsFailedFormat = "選択されたファイルを1つも読み込めませんでした：\n{0}",
            unexpectedFormat = "導入中に予期しない例外が発生：{0}: {1}",
            unknownStorageError = "不明な保存エラー",
        )
    }
}
