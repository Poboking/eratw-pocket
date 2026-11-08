package com.eratw.port.android

/**
 * 素材包（背景图 / BGM）导入域的玩家面文案表（三语，本波新增）。
 *
 * ## 为什么单独一张表
 *
 * 照 [ModImportCopy] / [UpdateCopy] 的同款理由：素材导入是一条链路（选包 → 逐包投放 →
 * 汇总成功 / 部分成功 / 全失败 → 跳过与结构体检明细 → 重启按钮），判读时整组一起动；
 * 并进配置页那张一千多行的大表只会让两边都更难读。入口卡的标题、说明、按钮仍在
 * Settings 的既有表里，本表不重复收（口径唯一，避免两处权威）。
 *
 * ## 三条硬规矩（与 [ModImportCopy] 同口径）
 *
 * 1. **取语言只有一条路**：[of] 只吃 [SettingsLanguage]；壳层取好一次往里传，导入线程在
 *    后台跑，语言随调用进线程——本表不碰 SharedPreferences、不判系统 Locale。
 * 2. **玩家面不内联中文**：壳层拼句一律 `copy.fill(模板, 实参)`；本表之外不许再出现
 *    素材导入的玩家句字面量。
 * 3. **结构体检只映射 kind**：`ResourcePackStore` 的 `StructureNote` 带的是路径原话
 *    （取证面），玩家句子按 kind 映射到本表的 [structureBgmOutsideFormat] /
 *    [structureImagesNoIndexFormat] / [structureEmptyCsvFormat] /
 *    [structureUnrecognizedText]，取证原话只作为 `{0}` 实参灌进去。
 *
 * ## 句式里的 `{0}`..`{3}`
 *
 * 带读数的句子存成模板，由 [fill] 一次替换（与 [ModImportCopy.fill] 同口径：花括号走
 * 字符类而不是反斜杠转义，Android 的 ICU 正则会把转义写法直接抛掉，真机一点就崩）。
 * 三档的槽位**序列**必须一致（`fill` 按下标灌数），语序可以按各语言调整实参位置之外
 * 的措辞，但 `{n}` 的先后不许挪。
 *
 * @property lang 这一组对应的设置页语言
 */
data class ResourcePackCopy(
    val lang: SettingsLanguage,

    // ---- 直投卡（背景图 / BGM）：玩家直接选文件或文件夹，不必先打包 ----

    /** 背景图直投卡标题。 */
    val backgroundTitle: String,
    /** 背景图直投卡说明：投放根、自动索引、重启生效。 */
    val backgroundIntro: String,
    /** 背景图卡「选图片」（多选）。 */
    val backgroundPickFiles: String,
    /** 背景图卡「选文件夹」。 */
    val backgroundPickFolder: String,
    /** BGM 直投卡标题。 */
    val bgmTitle: String,
    /** BGM 直投卡说明：投放根、自动编号、何时生效。 */
    val bgmIntro: String,
    /** BGM 卡「选音频」（多选）。 */
    val bgmPickFiles: String,
    /** BGM 卡「选文件夹」。 */
    val bgmPickFolder: String,
    /** BGM 卡地点名输入框的标签。 */
    val bgmLocationLabel: String,
    /** BGM 卡地点名输入框的说明（何时用、留空去哪）。 */
    val bgmLocationHint: String,

    /** 导入线程跑起来后的进行态那句（选完包、还没出结果）。 */
    val importingStageText: String,
    /** 全部包成功的汇总：{0}=已装文件数 {1}=包数 {2}=覆盖数 {3}=跳过数。 */
    val importedSummaryFormat: String,
    /** 部分包成功的汇总：{0}=已装文件数 {1}=成功包数 {2}=失败包数。 */
    val importedPartialFormat: String,
    /** 一个包都没成功：{0}=失败/跳过明细（已按换行连好）。 */
    val importedNothingFormat: String,
    /** 选中的不是 zip / 7z：{0}=文件名。 */
    val failedNotZipFormat: String,
    /** 缓存压缩包读不出来或超过体积上限：{0}=文件名 {1}=上限 MiB。 */
    val failedReadFormat: String,
    /** 解包失败（加密 / 损坏 / 打不开）：{0}=文件名 {1}=异常原话。 */
    val failedUnzipFormat: String,
    /** 包里没有可投放的素材条目：{0}=文件名。 */
    val failedNoUsableEntriesFormat: String,
    /** 导入线程兜底异常：{0}=异常类名 {1}=异常原话。 */
    val unexpectedFormat: String,
    /** 跳过明细样本行：{0}=明细样本。 */
    val skippedLineFormat: String,
    /** 提示明细行（结构体检 / 说明）：{0}=明细。 */
    val noteLineFormat: String,
    /** 成功且有文件落盘时的重启按钮。 */
    val restartButton: String,
    /** 文件名清单的分隔符（EN 用逗号加空格，中日用顿号）。 */
    val nameListSeparator: String,
    /** 存储读数完全取不到且异常连类名都没有时的最后一档。 */
    val unknownStorageError: String,
    /** 结构体检：BGM 不在 游戏bgm 下：{0}=路径原话。 */
    val structureBgmOutsideFormat: String,
    /** 结构体检：图片目录没有 CSV 索引：{0}=目录路径原话。 */
    val structureImagesNoIndexFormat: String,
    /** 结构体检：CSV 是空文件：{0}=路径原话。 */
    val structureEmptyCsvFormat: String,
    /** 结构体检：整包结构认不出（无槽位）。 */
    val structureUnrecognizedText: String,
    /** 直投背景图自动生成了索引：{0}=索引路径。 */
    val autoIndexGeneratedFormat: String,
    /** 直投 BGM 自动把非 0-9 文件名编号：{0}=编号个数。 */
    val autoRenamedFormat: String,
    /** 直投 BGM 没有地点名，落进兜底目录：{0}=兜底目录名。 */
    val bgmFallbackLocationFormat: String,
    /** 某地点目录 0-9 已满，多出的音频被跳过：{0}=目录路径。 */
    val bgmTracksOverflowFormat: String,
    /** 选中的来源里没有可投放的文件：{0}=来源名。 */
    val materialNoUsableFilesFormat: String,
    /** 背景图文件名带逗号等无法进索引，已跳过该行：{0}=相对路径。 */
    val backgroundIndexRowSkippedFormat: String,
    /**
     * 来源里不是该目标素材的文件（说明 txt、0 字节目录占位、封面图等）折成一条读数：
     * {0}=文件个数。这类文件本来就不该投放，逐条算「跳过」会让正常导入看着像失败。
     */
    val materialIgnoredFilesFormat: String,
    /** 直投（文件 / 文件夹）失败：{0}=来源名 {1}=原因。 */
    val materialImportFailedFormat: String,
    /** 多选文件时没有来源目录名，结果行用它当来源名（无槽位）。 */
    val materialSelectionLabel: String,
    /** 玩家把口上包选进素材卡时的指路句（无槽位）。 */
    val hintLooksLikeKojoPack: String,
    /** 背景图已导入后，游戏内还要开的 DLC 开关（无槽位）。 */
    val backgroundEnabledHint: String,
    /** BGM 已导入后，游戏内还要开的 DLC 开关与文件夹模式（无槽位）。 */
    val bgmEnabledHint: String,
    /** 结果区「还差一步」小标题：有文件落盘时在重启按钮上方再顶一次启用步骤（无槽位）。 */
    val enableStepsTitle: String,
) {

    /**
     * 句式占位符 `{0}`..`{3}`：单趟替换，参数本身带 `{n}`（例如异常原话）不会被二次展开。
     *
     * 花括号必须走字符类而不是反斜杠转义：Android 的正则是 ICU 引擎，转义写法直接抛
     * PatternSyntaxException（OpenJDK 却容忍）—— 与 [ModImportCopy.fill] 同一写法。
     */
    fun fill(template: String, vararg args: Any?): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    companion object {
        private val SLOT = Regex("[{](\\d+)[}]")

        /** 与设置页同源取语言：不许在素材导入侧再造一套语言判定。 */
        fun of(lang: SettingsLanguage): ResourcePackCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** 简体中文：玩家面底稿。 */
        val ZH: ResourcePackCopy = ResourcePackCopy(
            lang = SettingsLanguage.ZH,
            backgroundTitle = "导入背景图片",
            backgroundIntro = "直接选图片（可多选）或整个文件夹，不用打包。图片会放进 resources/背景图/；没有现成 CSV 索引时按文件名自动生成 自动索引.csv。官方背景图包请选整个包或 zip：只选子文件夹会丢掉 BGI.csv，自动索引的文件名和游戏要的「地点_季节_时间」对不上，图片就不会显示。选到带 resources/背景图/ 层的文件夹会保留子目录。图片重启游戏后生效。",
            backgroundPickFiles = "选择图片",
            backgroundPickFolder = "选择文件夹",
            bgmTitle = "导入 BGM",
            bgmIntro = "直接选 .mp3（可多选）或整个文件夹，不用打包。会放进 sound/游戏bgm/<地点>/，名字不是 0-9 的自动编号；选地点文件夹用文件夹名当地点，选合集时第一层目录名当地点。BGM 下次播放时生效。",
            bgmPickFiles = "选择音频",
            bgmPickFolder = "选择文件夹",
            bgmLocationLabel = "地点名",
            bgmLocationHint = "直接选音频时用这个地点名，留空放进「自定义」",
            importingStageText = "正在导入素材…",
            importedSummaryFormat = "已导入 {0} 个文件（来源 {1} 个）；覆盖 {2} 个，跳过 {3} 项。",
            importedPartialFormat = "已导入 {0} 个文件（成功 {1} 个来源，{2} 个来源失败）。",
            importedNothingFormat = "没有导入任何素材。\n{0}",
            failedNotZipFormat = "{0}：不是 zip / 7z 文件（素材包只收 .zip / .7z）",
            failedReadFormat = "{0}：读不出来，或超过 {1} MiB 上限",
            failedUnzipFormat = "{0}：素材包解包失败 —— {1}",
            failedNoUsableEntriesFormat = "{0}：没有可用的素材条目",
            unexpectedFormat = "素材导入发生未预期异常：{0}: {1}",
            skippedLineFormat = "跳过：{0}",
            noteLineFormat = "提示：{0}",
            restartButton = "重启游戏生效",
            nameListSeparator = "、",
            unknownStorageError = "未知存储错误",
            structureBgmOutsideFormat = "BGM 不在 游戏bgm 目录下：{0}（游戏不会把它当作地点曲目播放）",
            structureImagesNoIndexFormat = "图片目录没有 CSV 索引：{0}（只放图片不会生效）",
            structureEmptyCsvFormat = "CSV 是空文件：{0}（索引表为空，等同于没有索引）",
            structureUnrecognizedText =
                "这个压缩包的结构认不出来，没有导入任何素材。请确认包里有 resources/、sound/、" +
                    "背景图/ 或 游戏bgm/ 目录。",
            autoIndexGeneratedFormat = "已按文件名写入索引：{0}。如果这是官方背景图包，请选整个包或 zip 重新导入：只选子文件夹会丢掉 BGI.csv，自动索引的文件名也和游戏要的「地点_季节_时间」对不上。",
            autoRenamedFormat = "{0} 个文件名不是 0-9，已自动编号",
            bgmFallbackLocationFormat = "这些曲目没有地点文件夹，已放进「{0}」",
            bgmTracksOverflowFormat = "这个地点已有 0-9 共 10 首，多出的没有导入：{0}",
            materialNoUsableFilesFormat = "{0}：没有可用的图片 / mp3，未导入任何文件",
            backgroundIndexRowSkippedFormat = "文件名不能进索引（含逗号），已跳过：{0}",
            materialIgnoredFilesFormat = "另有 {0} 个非素材文件（说明 / 占位）没有导入",
            materialImportFailedFormat = "{0}：导入失败 —— {1}",
            materialSelectionLabel = "选中的文件",
            hintLooksLikeKojoPack =
                "这个包看起来是口上包，不是素材包。请到「补丁（MOD）导入」卡里导入。",
            backgroundEnabledHint =
                "要在游戏里看到背景图，还需在 DLC 设置开启「背景图补丁开关」，然后重启游戏。",
            bgmEnabledHint =
                "要在游戏里听到 BGM，还需在 DLC 设置开启「音乐补丁开关」；曲目只按 上午/下午/晚上 " +
                    "分目录时，再到音乐设置里点「更改音乐目录模式」切到差分模式。",
            enableStepsTitle = "还差一步：在游戏里启用",
        )

        /** English：结构与 [ZH] 逐条同形（同样几个读数），措辞另写，不抄中文。 */
        val EN: ResourcePackCopy = ResourcePackCopy(
            lang = SettingsLanguage.EN,
            backgroundTitle = "Import background images",
            backgroundIntro = "Pick images (multiple selection) or a whole folder - no packing needed. Images go into resources/背景图/; when there is no CSV index yet, 自动索引.csv is generated from the file names. For official background packs, pick the whole pack or the zip: picking only a subfolder drops BGI.csv, and the auto index names will not match the 地点_季节_时间 keys the game queries, so nothing shows. Folders that already contain resources/背景图/ keep their subfolders. Images apply after a restart.",
            backgroundPickFiles = "Choose images",
            backgroundPickFolder = "Choose folder",
            bgmTitle = "Import BGM",
            bgmIntro = "Pick .mp3 files (multiple selection) or a whole folder - no packing needed. Tracks go into sound/游戏bgm/<location>/; names other than 0-9 are renumbered automatically. A selected location folder supplies the location name; for a collection folder the first-level folder name is used. BGM applies on the next playback.",
            bgmPickFiles = "Choose audio",
            bgmPickFolder = "Choose folder",
            bgmLocationLabel = "Location",
            bgmLocationHint = "Used as the location when picking audio files directly; empty means 自定义",
            importingStageText = "Importing assets...",
            importedSummaryFormat = "Imported {0} files from {1} source(s); {2} overwritten, {3} skipped.",
            importedPartialFormat = "Imported {0} files; {1} source(s) succeeded, {2} failed.",
            importedNothingFormat = "Nothing was imported.\n{0}",
            failedNotZipFormat = "{0}: not a zip/7z file (asset packs must be .zip or .7z)",
            failedReadFormat = "{0}: could not be read, or it exceeds the {1} MiB limit",
            failedUnzipFormat = "{0}: failed to unpack the asset pack - {1}",
            failedNoUsableEntriesFormat = "{0}: no usable asset entries",
            unexpectedFormat = "Unexpected error during asset import: {0}: {1}",
            skippedLineFormat = "Skipped: {0}",
            noteLineFormat = "Note: {0}",
            restartButton = "Restart to apply",
            nameListSeparator = ", ",
            unknownStorageError = "Unknown storage error",
            structureBgmOutsideFormat =
                "BGM is outside the game BGM folder: {0} (the game will not play it as a place track)",
            structureImagesNoIndexFormat =
                "The images folder has no CSV index: {0} (images alone will not show up)",
            structureEmptyCsvFormat =
                "The CSV file is empty: {0} (the index table is empty, same as no index)",
            structureUnrecognizedText =
                "The pack layout is not recognized, so nothing was imported. Make sure the pack has " +
                    "a resources/, sound/, background-image/, or game-BGM/ folder.",
            autoIndexGeneratedFormat = "Wrote the index from file names: {0}. If this is an official background pack, re-import the whole pack or zip: picking only a subfolder drops BGI.csv, and the auto index names may not match the 地点_季节_时间 keys the game queries.",
            autoRenamedFormat = "{0} file name(s) were not 0-9 and were renumbered",
            bgmFallbackLocationFormat = "These tracks have no location folder, so they went into \"{0}\"",
            bgmTracksOverflowFormat = "This location already has 0-9 (10 tracks); the rest were skipped: {0}",
            materialNoUsableFilesFormat = "{0}: no usable images / mp3 files, nothing was imported",
            backgroundIndexRowSkippedFormat = "The file name cannot go into the index (comma), skipped: {0}",
            materialIgnoredFilesFormat = "{0} non-asset file(s) (readme / placeholder) were not imported",
            materialImportFailedFormat = "{0}: import failed - {1}",
            materialSelectionLabel = "selected files",
            hintLooksLikeKojoPack =
                "This looks like a kojo (dialogue) pack, not an asset pack. " +
                    "Please import it from the Patch (MOD) import card.",
            backgroundEnabledHint =
                "To see backgrounds in game, enable the background-image patch in DLC settings, " +
                    "then restart the game.",
            bgmEnabledHint =
                "To hear the imported BGM, enable the music patch in DLC settings; if tracks are only " +
                    "under morning/afternoon/evening folders, switch the music folder mode to differential.",
            enableStepsTitle = "One more step: enable it in game",
        )

        /** 日本語：结构与 [ZH] 逐条同形。 */
        val JA: ResourcePackCopy = ResourcePackCopy(
            lang = SettingsLanguage.JA,
            backgroundTitle = "背景画像を導入",
            backgroundIntro = "画像（複数選択可）またはフォルダをそのまま選べます。固める必要はありません。画像は resources/背景图/ に配置し、CSV 索引が無ければファイル名から 自动索引.csv を自動生成します。公式の背景パックはパック全体または zip を選んでください。サブフォルダだけを選ぶと BGI.csv が欠け、自動索引の名前がゲームの問い合わせる「地点_季節_時間」と一致せず、画像が表示されません。resources/背景图/ を含むフォルダを選ぶと階層を保ったまま配置します。画像は再起動後に反映されます。",
            backgroundPickFiles = "画像を選択",
            backgroundPickFolder = "フォルダを選択",
            bgmTitle = "BGM を導入",
            bgmIntro = ".mp3（複数選択可）またはフォルダをそのまま選べます。固める必要はありません。sound/游戏bgm/＜地点＞/ に配置し、0-9 以外の名前は自動で番号を振ります。地点フォルダを選ぶとフォルダ名を地点名に、まとめフォルダでは第 1 階層のフォルダ名を地点名にします。BGM は次回の再生時に反映されます。",
            bgmPickFiles = "音声を選択",
            bgmPickFolder = "フォルダを選択",
            bgmLocationLabel = "地点名",
            bgmLocationHint = "音声を直接選ぶときの地点名。空欄なら「自定义」に入ります",
            importingStageText = "素材を導入しています…",
            importedSummaryFormat = "導入済み：{0} ファイル（{1} 個のソース、上書き {2}、スキップ {3}）。",
            importedPartialFormat = "{0} ファイルを導入（成功 {1} ソース、失敗 {2} ソース）。",
            importedNothingFormat = "素材は1つも導入されませんでした。\n{0}",
            failedNotZipFormat = "{0}：zip / 7z ファイルではありません（素材パックは .zip / .7z のみ）",
            failedReadFormat = "{0}：読み取れないか、上限 {1} MiB を超えています",
            failedUnzipFormat = "{0}：素材パックの展開に失敗しました —— {1}",
            failedNoUsableEntriesFormat = "{0}：使用できる素材エントリがありません",
            unexpectedFormat = "素材の導入中に予期しない例外が発生：{0}: {1}",
            skippedLineFormat = "スキップ：{0}",
            noteLineFormat = "補足：{0}",
            restartButton = "再起動で反映",
            nameListSeparator = "、",
            unknownStorageError = "不明な保存エラー",
            structureBgmOutsideFormat = "BGM が ゲームBGM ディレクトリの外にあります：{0}（地点曲として再生されません）",
            structureImagesNoIndexFormat = "画像ディレクトリに CSV 索引がありません：{0}（画像だけでは反映されません）",
            structureEmptyCsvFormat = "CSV ファイルが空です：{0}（索引が空のため、索引なしと同じです）",
            structureUnrecognizedText =
                "パックの構成を判別できないため、素材は1つも導入されませんでした。パックに " +
                    "resources/、sound/、背景図/、ゲームBGM/ のいずれかが入っているか確認してください。",
            autoIndexGeneratedFormat = "ファイル名から索引に書き込みました：{0}。公式の背景パックの場合は、パック全体または zip を選び直してください。サブフォルダだけを選ぶと BGI.csv が欠け、自動索引のファイル名もゲームの問い合わせる「地点_季節_時間」と一致しないことがあります。",
            autoRenamedFormat = "0-9 以外のファイル名 {0} 件に自動で番号を振りました",
            bgmFallbackLocationFormat = "これらの曲には地点フォルダが無いため「{0}」に入れました",
            bgmTracksOverflowFormat = "この地点は 0-9 の 10 曲が揃っているため、残りは導入しませんでした：{0}",
            materialNoUsableFilesFormat = "{0}：使える画像 / mp3 が無く、1 ファイルも導入しませんでした",
            backgroundIndexRowSkippedFormat = "索引に入れられないファイル名（カンマ入り）のためスキップ：{0}",
            materialIgnoredFilesFormat = "素材以外のファイル（説明・プレースホルダ）{0} 件は導入しませんでした",
            materialImportFailedFormat = "{0}：導入に失敗しました —— {1}",
            materialSelectionLabel = "選択したファイル",
            hintLooksLikeKojoPack =
                "これは口上パックのようです。素材パックではありません。" +
                    "「パッチ（MOD）導入」から導入してください。",
            backgroundEnabledHint =
                "ゲーム内で背景画像を表示するには、DLC 設定で「背景図パッチ」を有効にして再起動してください。",
            bgmEnabledHint =
                "BGM を再生するには、DLC 設定で「音楽パッチ」を有効にしてください。曲が 午前/午後/夜 " +
                    "のフォルダだけにある場合は、音楽設定で「音楽ディレクトリモード」を差分モードに切り替えてください。",
            enableStepsTitle = "あと 1 ステップ：ゲーム内で有効化",
        )
    }
}
