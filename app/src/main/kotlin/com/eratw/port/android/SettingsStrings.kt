package com.eratw.port.android

import com.eratw.port.core.config.ConfigClampLanguage
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigPageLabels
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.SettingsEntry
import com.eratw.port.core.config.SettingsRejectReason

/**
 * 设置页的显示语言（**只作用于设置页**，游戏画面与其它壳层界面不跟随）。
 *
 * 三档对应三种可取到的真实文本：
 *   * [ZH] —— 原版 `lang.emuera-zhs.xml` 的简中译文 + app 侧兜底表；
 *   * [EN] —— 原版没有 `lang.emuera-eng.xml`（语料 `lang/` 下只有 zhs），条目名取
 *     `emuera.config` 的**英文键名**（`ConfigSpec.enLabel`），壳层文案用本表英文；
 *   * [JA] —— 原版日语基准：设置页标签取 `Lang.cs` 的 `UI.ConfigDialog.*`（`pageJaLabel`），
 *     没有页面标签的条目回落配置键原文（`ConfigSpec.jpLabel`）。
 *
 * 切语言只换显示，不改 `emuera.config`、不重开游戏、不碰系统 Locale。
 *
 * @property stored SharedPreferences 里的稳定值（改显示名不能让已存偏好失效）
 * @property nativeName 语言名本身（语言卡片上永远用母语显示：简体中文 / English / 日本語）
 */
enum class SettingsLanguage(val stored: String, val nativeName: String) {
    ZH("zh", "简体中文"),
    EN("en", "English"),
    JA("ja", "日本語"),
    ;

    /** 夹取提示（原版 `Lang.MessageBox.*`）的同语种列。 */
    val clampLanguage: ConfigClampLanguage
        get() = when (this) {
            ZH -> ConfigClampLanguage.CHINESE_SIMPLIFIED
            EN -> ConfigClampLanguage.ENGLISH
            JA -> ConfigClampLanguage.JAPANESE
        }

    companion object {
        /** 默认：简体中文（与改动前逐字一致）。 */
        val DEFAULT: SettingsLanguage = ZH

        fun fromStored(value: String?): SettingsLanguage =
            entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

/** 设置页的分页（页名与页尾说明按语言取）。 */
enum class SettingsPage {
    ENV,
    DISPLAY,
    WINDOW,
    FONT,
    SYSTEM,
    SYSTEM2,
    COMPAT,
    PARSING,
    CLIPBOARD,
    RIKAI,
    ADVANCED,
    OTHER,
}

/**
 * 设置页全部固定文案的三语表。
 *
 * ## 为什么是一个大构造器而不是若干 `when (lang)`
 * 每个字段都要在 [ZH]/[EN]/[JA] 三份实例里给值，**漏一种语言编译就不过**；
 * 参数化句式统一用 `{0}` / `{1}` 占位，由 [fill] 一次替换，避免三段几乎相同的 `when`。
 *
 * ## 条目标签的来源（不编造）
 * [configLabel] 的三条路各自有出处：zhs 页面译文（缺译回落日文）、日语页面标签、
 * 英文配置键名。原版没有英文页面译文文件，所以英文模式显示配置键本身，而不是自造译文。
 */
class SettingsStrings private constructor(
    val lang: SettingsLanguage,
    // ---- 顶栏 ----
    val back: String,
    val title: String,
    val reset: String,
    val resetAll: String,
    val save: String,
    val saveAndRestart: String,
    val filePrefix: String,
    val unsavedSuffix: String,
    // ---- 读取提示 ----
    val noticesTitle: String,
    // ---- 语言卡片 ----
    val languageTitle: String,
    val languageHint: String,
    // ---- 应用偏好分组 ----
    val appPrefsTitle: String,
    val appPrefsSubtitle: String,
    // ---- 恢复默认设置（列表底部卡片，不进顶栏）----
    val restoreDefaultsTitle: String,
    val restoreDefaultsHint: String,
    val restoreDefaultsButton: String,
    val restoreDefaultsConfirmTitle: String,
    val restoreDefaultsConfirmBody: String,
    val restoreDefaultsConfirmOk: String,
    val restoreDefaultsDone: String,
    /** 有条目被 fixed.config 钉住时拼在成功提示后面：{0} 是没改成的项目数。 */
    val restoreDefaultsPinnedFormat: String,
    val restoreDefaultsWriteFailed: String,
    // ---- 字号与字体 ----
    val fontTitle: String,
    val fontSizeLabel: String,
    val fontFamilyLabel: String,
    val coverageFormat: String,
    val fontRestartHint: String,
    val fontNoteSarasa: String,
    val fontNoteLxgw: String,
    val fontNoteMaple: String,
    val fontNoteSaitamaar: String,
    val fontNoteMisaki: String,
    val fontNoteFusion: String,
    val fontNoteArk: String,
    val fontNoteUnifont: String,
    val col136Label: String,
    val col136Hint: String,
    val col90Label: String,
    val col90Hint: String,
    val col75Label: String,
    val col75Hint: String,
    val col150Label: String,
    val col150Hint: String,
    val railSmallLabel: String,
    val railSmallHint: String,
    val railNormalLabel: String,
    val railNormalHint: String,
    val railLargeLabel: String,
    val railLargeHint: String,
    val railXlargeLabel: String,
    val railXlargeHint: String,
    val loadingPixelLabel: String,
    val loadingPixelHint: String,
    val loadingModernLabel: String,
    val loadingModernHint: String,
    /** 右栏大小预览里的三枚示例选项（文案随界面语言；序号固定）。 */
    val railSampleTalk: String,
    val railSampleMove: String,
    val railSampleBuy: String,
    // ---- 操作与显示 ----
    val controlTitle: String,
    val railSizeLabel: String,
    val railFootnote: String,
    val knobLabel: String,
    val knobLeft: String,
    val knobCenter: String,
    val knobRight: String,
    val screenFitTitle: String,
    val screenFitHint: String,
    val leanTitle: String,
    val leanHint: String,
    val portraitTitle: String,
    val portraitHint: String,
    /** 「窗口宽窄变化时重新排版」开关（LIVE-REFLOW，默认关）。 */
    val liveReflowTitle: String,
    val liveReflowHint: String,
    // ---- 启动 ----
    val launchTitle: String,
    val loadingStyleLabel: String,
    val badAppleTitle: String,
    val badAppleHint: String,
    val autoUpdateTitle: String,
    val autoUpdateHint: String,
    /** 仅 Wi-Fi 下载（U5 P2-10）：默认关 = 保持既有蜂窝二次确认；开 = 蜂窝不给下载。 */
    val wifiOnlyTitle: String,
    val wifiOnlyHint: String,
    val checkUpdateButton: String,
    // ---- 更新「跳过此版本」（U-E，契约 §1.5：只对同一个 versionCode 生效，设置页给恢复口） ----
    val updateSkippedLineFormat: String,
    val updateRestoreButton: String,
    val updateRestoreDone: String,
    // ---- 高级 ----
    val advancedTitle: String,
    val rawKeysTitle: String,
    val rawKeysHint: String,
    val prefsFootnote: String,
    // ---- 导出 / 导入卡片 ----
    /** 存档分组的组头（这两张卡不经「保存」按钮，与上面的壳层偏好分开）。 */
    val saveGroupTitle: String,
    val saveGroupSubtitle: String,
    val exportTitle: String,
    val exportHint: String,
    val exportBusy: String,
    val exportButton: String,
    val importTitle: String,
    val importBusy: String,
    val importButton: String,
    // ---- 补丁（MOD）导入卡（设置页入口卡与 ModImportChrome 覆盖层共用同一条说明；
    //      2026-11 起接三语：此前固定中文，与存档导入卡的键同机制）----
    val modImportIntro: String,
    val modImportPickButton: String,
    // ---- Mod 档（自备素材补丁的资源导入）----
    val modGroupTitle: String,
    val modGroupSubtitle: String,
    val resourcePackTitle: String,
    val resourcePackIntro: String,
    val resourcePackButton: String,
    val resourcePackBusy: String,
    val resourcePackStatusUnavailable: String,
    val resourcePackStatusBackgroundFormat: String,
    val resourcePackStatusBackgroundEmpty: String,
    val resourcePackStatusBackgroundNoIndexFormat: String,
    val resourcePackStatusBgmFormat: String,
    val resourcePackStatusBgmEmpty: String,
    val resourcePackImportedFormat: String,
    val resourcePackSkippedFormat: String,
    val resourcePackRestartButton: String,
    // ---- 运行诊断卡片（闪退但没有任何报告的一类）----
    val diagTitle: String,
    val diagHint: String,
    val diagBusy: String,
    val diagShareButton: String,
    val diagCopyButton: String,
    val diagReadyFormat: String,
    val diagFailed: String,
    val diagCopied: String,
    val diagCopyFailed: String,
    val diagShareTitle: String,
    val diagShareFailed: String,
    // ---- 分组与只读卡 ----
    val gameGroupTitle: String,
    val gameGroupSubtitleFormat: String,
    val debugReadOnlyTitle: String,
    val replaceReadOnlyTitle: String,
    val gameReadOnlyTitle: String,
    val debugReadOnlyHint: String,
    val replaceReadOnlyHint: String,
    // ---- 条目行 ----
    val pinnedByFixed: String,
    val emptyValue: String,
    val effectiveValueFormat: String,
    val currentDefaultEffectiveFormat: String,
    // ---- 底部 ----
    val bottomFootnote: String,
    /** 并列清单的分隔符（重启键清单用）。 */
    val listSeparator: String,
    // ---- 对话框 ----
    val okText: String,
    val cancelText: String,
    val overwriteTitle: String,
    val overwriteUnknownNote: String,
    val overwriteNoNote: String,
    val overwriteLineFormat: String,
    val overwriteConfirmFormat: String,
    // ---- 分页名 ----
    val pageEnv: String,
    val pageDisplay: String,
    val pageWindow: String,
    val pageFont: String,
    val pageSystem: String,
    val pageSystem2: String,
    val pageCompat: String,
    val pageParsing: String,
    val pageClipboard: String,
    val pageRikai: String,
    val pageAdvanced: String,
    val pageOther: String,
    val pageAdvancedHint: String,
    val pageRikaiHint: String,
    // ---- 拒绝原因 ----
    val rejectUnknown: String,
    val rejectType: String,
    val rejectInvalid: String,
    val rejectEmpty: String,
    val rejectRange: String,
    val rejectFixed: String,
    // ---- 拒因明细（core 给的日文原话，已知的按语言重述；未知原样保留） ----
    val detailUnknownId: String,
    val detailFixed: String,
    val detailTypeMismatch: String,
    val detailEmpty: String,
    val detailInvalidSpec: String,
    val detailNonNumeric: String,
    val detailColor: String,
    val detailFontTooSmall: String,
    val detailSaveTooFew: String,
    val detailSaveTooMany: String,
    val detailLogTooSmall: String,
    val detailRangeFormat: String,
    // ---- 读取提示句式 ----
    val loadErrorFormat: String,
    val loadAbortedFormat: String,
    val loadWarningsFormat: String,
    val loadMissing: String,
    // ---- 保存与 Toast ----
    /** 顶栏「全部重置」成功提示：{0} 是写回模型的条目数；要说明「偏好即时生效、配置未写盘」。 */
    val resetAllDoneFormat: String,
    /** 有条目被 fixed.config 钉住时拼在「全部重置」提示后面：{0} 是没改成的项目数。 */
    val resetAllDonePinnedFormat: String,
    val saveWriteFailed: String,
    val saveRestartWriteFailed: String,
    val savedHotNoRestartFormat: String,
    val summaryNoChangeFormat: String,
    val summaryHotRestartFormat: String,
    val summaryRestartFormat: String,
    val summaryHotFormat: String,
    val summaryNoSession: String,
    val moreItemsFormat: String,
    val rejectToastFormat: String,
    val titleBodyFormat: String,
    // ---- 导入结果 ----
    val configUnreadableImportFormat: String,
    val importUnreadFormat: String,
    val importRejectedFormat: String,
    val importedFormat: String,
    val importedWithNoteFormat: String,
    val importWriteFailedFormat: String,
    val importSkippedOccupiedFormat: String,
    /** 单个文件处理时抛了未预期的异常（I/O、OOM 一类）时的兜底原话。 */
    val unexpectedFailure: String,
    val selectedFileFallback: String,
    val globalSaveLabel: String,
    val slotFormat: String,
    val noNote: String,
    // ---- 导出结果 ----
    val configUnreadableExportFormat: String,
    val exportCollectFailed: String,
    val exportNothing: String,
    val exportPickerFailed: String,
    val exportDoneFormat: String,
    val exportIncludingGlobal: String,
    val exportFailedAtFormat: String,
    val exportFailedGenericFormat: String,
    val exportSkippedFormat: String,
    // ---- 存档判读理由（复述平台给出的原话） ----
    val reasonOnlySav: String,
    val reasonCorrupted: String,
    val reasonDifferentGame: String,
    val reasonDifferentVersion: String,
    val reasonSlotTooLarge: String,
    val reasonSlotsFull: String,
    val reasonTooBigFormat: String,
    val reasonEmptyFile: String,
    val reasonOversize: String,
    // ---- 分区标签页（移植版 / 原版引擎 / Mod 素材 / 关于） ----
    val zonePortTab: String,
    val zoneEngineTab: String,
    val zoneModTab: String,
    val zoneAboutTab: String,
    // ---- 关于与反馈卡（版本号 / 开发者 / 仓库 / 反馈群） ----
    val aboutGroupSubtitle: String,
    val aboutVersionFormat: String,
    val aboutBuildFormat: String,
    val aboutDeveloperCaption: String,
    val aboutDeveloperName: String,
    val aboutRepoCaption: String,
    val aboutRepoUrl: String,
    val aboutRepoButton: String,
    val aboutRepoFailed: String,
    val aboutFeedbackCaption: String,
    val aboutGroupId: String,
    val aboutCopyGroupButton: String,
    val aboutCopiedFormat: String,
    // ---- 署名声明（关于页最底下一层：本体归属 / 本项目范围 / 借物表 / 责任归属） ----
    val creditsTitle: String,
    val creditsHeadGame: String,
    val creditsGameBody: String,
    val creditsHeadScope: String,
    val creditsScopeBody: String,
    val creditsHeadThanks: String,
    val creditsItemEmuera: String,
    val creditsItemKoujo: String,
    val creditsItemCommunity: String,
    val creditsItemTouhou: String,
    val creditsLiability: String,
) {

    // -----------------------------------------------------------------
    // ECHO-INPUT 开关文案（2026-11-06）
    //
    // 主构造器的参数个数已经贴着 JVM 方法签名 255 个 slot 的上限：再加两个 String 形参，
    // 类加载期直接抛 `ClassFormatError: Too many arguments in method signature`
    // （2026-11-06 实测，SettingsStringsTest 整类 14 红）。故这两条改走类内属性。
    // 用穷尽 `when` 表达式给值：漏一种语言编译不过，与构造器参数同等的强制力；
    // `SettingsStringsTest` 反射遍历 declaredFields 时同样把它们纳入三语完整性闸。
    // -----------------------------------------------------------------

    /** 「回显所选项编号」开关（默认关）的标题。 */
    val echoInputTitle: String = when (lang) {
        SettingsLanguage.ZH -> "回显所选项编号"
        SettingsLanguage.EN -> "Echo the selected number"
        SettingsLanguage.JA -> "選択した番号を表示する"
    }

    /** 关 = 不多出一行编号、画面不抖；开 = 原版复刻；两档都要重开一局才生效。 */
    val echoInputHint: String = when (lang) {
        SettingsLanguage.ZH ->
            "默认关：点剧本、点菜单后不再显示那行所选编号，画面不会跟着跳一下。开：照原版把编号回显到最新一行。两档对脚本的行计数与清屏完全一样，只是这一行看不看得见。改这一项后要重开一局才生效。"
        SettingsLanguage.EN ->
            "Off by default: choosing a scenario or menu item no longer shows a line with the selected number, so the screen does not jump. On: reproduce the original behavior and show the number on the last line. Scripts see the same line count and clearing behavior in both modes; only the visibility changes. Takes effect after the game restarts."
        SettingsLanguage.JA ->
            "既定はオフ：口上やメニューを選んだときに選択番号の行を表示せず、画面が揺れません。オン：原本どおり選択番号を最終行に表示します。どちらもスクリプトから見た行数と行削除は同じで、表示するかどうかだけが変わります。変更後はゲームの再起動が必要です。"
    }

    // -----------------------------------------------------------------
    // 参数化句式
    // -----------------------------------------------------------------

    private fun fill(template: String, vararg args: Any): String =
        SLOT.replace(template) { match ->
            val arg = match.groupValues[1].toIntOrNull()?.let { args.getOrNull(it) }
            arg?.toString() ?: match.value
        }

    fun diagReady(name: String): String = fill(diagReadyFormat, name)

    /** 「已跳过版本 N 的提醒」一行（设置页启动卡；恢复口就在它下面）。 */
    fun updateSkippedLine(versionCode: Long): String = fill(updateSkippedLineFormat, versionCode)

    fun loadError(message: String): String = fill(loadErrorFormat, message)

    fun loadAborted(line: Int, dropped: Int): String = fill(loadAbortedFormat, line, dropped)

    fun loadWarnings(count: Int, first: String): String = fill(loadWarningsFormat, count, first)

    /** 顶栏「全部重置」成功提示：偏好已即时生效、游戏配置尚未写盘（还要玩家再点保存）。 */
    fun resetAllDone(entries: Int): String = fill(resetAllDoneFormat, entries)

    /** 「全部重置」有被 fixed.config 钉住的条目：如实带上没改成的项目数。 */
    fun resetAllDonePinned(entries: Int, pinned: Int): String =
        fill(resetAllDoneFormat, entries) + fill(resetAllDonePinnedFormat, pinned)

    /** 恢复默认成功，但 fixed.config 钉住的条目写不进去：如实带上没改成的项目数。 */
    fun restoreDefaultsDonePinned(pinned: Int): String =
        restoreDefaultsDone + fill(restoreDefaultsPinnedFormat, pinned)

    fun savedHotNoRestart(count: Int): String = fill(savedHotNoRestartFormat, count)

    fun summaryNoChange(file: String): String = fill(summaryNoChangeFormat, file)

    fun summaryHotRestart(hot: Int, restart: String): String = fill(summaryHotRestartFormat, hot, restart)

    fun summaryRestart(restart: String): String = fill(summaryRestartFormat, restart)

    fun summaryHot(hot: Int): String = fill(summaryHotFormat, hot)

    /** 重启键清单的短提示：超过 3 项折成「head 等 N 项」。 */
    fun restartHint(head: String, count: Int): String =
        if (count > 3) fill(moreItemsFormat, head, count) else head

    fun rejectToast(label: String, message: String): String = fill(rejectToastFormat, label, message)

    fun titleBody(title: String, body: String): String = fill(titleBodyFormat, title, body)

    fun gameGroupSubtitle(file: String, count: Int): String = fill(gameGroupSubtitleFormat, file, count)

    fun coverage(percent: String): String = fill(coverageFormat, percent)

    fun effectiveValue(value: String): String = fill(effectiveValueFormat, value)

    fun currentDefaultEffective(current: String, default: String, effective: String): String =
        fill(currentDefaultEffectiveFormat, current, default, effective)

    fun overwriteLine(label: String, existing: String, incoming: String, file: String): String =
        fill(overwriteLineFormat, label, existing, incoming, file)

    fun overwriteConfirm(count: Int): String = fill(overwriteConfirmFormat, count)

    fun configUnreadableImport(message: String): String = fill(configUnreadableImportFormat, message)

    fun configUnreadableExport(message: String): String = fill(configUnreadableExportFormat, message)

    fun importUnread(name: String): String = fill(importUnreadFormat, name)

    fun importRejected(name: String, reason: String): String = fill(importRejectedFormat, name, reason)

    fun imported(label: String, note: String): String =
        if (note.isEmpty()) fill(importedFormat, label) else fill(importedWithNoteFormat, label, note)

    fun importWriteFailed(label: String): String = fill(importWriteFailedFormat, label)

    fun importSkippedOccupied(label: String): String = fill(importSkippedOccupiedFormat, label)

    fun exportDone(count: Int, includingGlobal: Boolean, name: String): String =
        fill(exportDoneFormat, count, if (includingGlobal) exportIncludingGlobal else "", name)

    fun exportFailed(entry: String?, written: Int): String =
        if (entry == null) fill(exportFailedGenericFormat, written) else fill(exportFailedAtFormat, entry, written)

    fun exportSkipped(name: String, reason: String): String = fill(exportSkippedFormat, name, reason)

    /** 关于卡的版本行：`versionName` + `versionCode`（都取自 BuildConfig，不手写常量）。 */
    fun aboutVersion(versionName: String, versionCode: Int): String =
        fill(aboutVersionFormat, versionName, versionCode)

    /** 关于卡的构建行：出包时烙进去的 git sha（未传参时是 `unknown`，照实显示）。 */
    fun aboutBuild(buildStamp: String): String = fill(aboutBuildFormat, buildStamp)

    fun aboutCopied(value: String): String = fill(aboutCopiedFormat, value)

    /**
     * 背景图现状行。三态分开说：未导入 / 只有图没有索引 / 图与索引都在。
     * 「有图无索引」是玩家最常见的失败形态（只把图片丢进去，`SPRITECREATED` 仍为假），
     * 必须在读数里点名，而不是显示成看起来成功的「{0} 张」。
     */
    fun resourcePackBackground(images: Int, indexRows: Int): String = when {
        images <= 0 -> resourcePackStatusBackgroundEmpty
        indexRows <= 0 -> fill(resourcePackStatusBackgroundNoIndexFormat, images)
        else -> fill(resourcePackStatusBackgroundFormat, images, indexRows)
    }

    fun resourcePackBgm(tracks: Int, folders: Int): String =
        if (tracks <= 0) resourcePackStatusBgmEmpty else fill(resourcePackStatusBgmFormat, tracks, folders)

    fun resourcePackImported(files: Int, packs: Int, overwritten: Int, skipped: Int): String =
        fill(resourcePackImportedFormat, files, packs, overwritten, skipped)

    fun resourcePackSkipped(detail: String): String = fill(resourcePackSkippedFormat, detail)

    // -----------------------------------------------------------------
    // 标签选择
    // -----------------------------------------------------------------

    /**
     * 存档槽位的显示名。平台给出的原话是中文（「存档位 7」/「全局存档」），
     * 这里只做名字面的翻译，不改变任何判定。
     */
    fun slotLabel(raw: String): String = when {
        raw == "全局存档" -> globalSaveLabel
        raw.startsWith("存档位 ") -> fill(slotFormat, raw.removePrefix("存档位 ").trim())
        else -> raw
    }

    /**
     * 平台/引擎给出的存档判读理由。已知的几条按语言给出对应文本；未知理由原样上屏
     * （不掩盖、不改写）。简体中文模式一律原样返回，保持改动前逐字一致。
     */
    fun saveReason(raw: String): String {
        if (lang == SettingsLanguage.ZH) return raw
        return when (raw) {
            "只能导入 .sav 存档文件" -> reasonOnlySav
            "存档数据已损坏" -> reasonCorrupted
            "试图读取的存档属于另一个游戏" -> reasonDifferentGame
            "存档版本不同" -> reasonDifferentVersion
            "存档位编号太大" -> reasonSlotTooLarge
            "存档位已经满了，没有空位" -> reasonSlotsFull
            "超过体积上限" -> reasonOversize
            "是空文件" -> reasonEmptyFile
            else -> TOO_BIG.find(raw)?.let { fill(reasonTooBigFormat, it.groupValues[1]) } ?: raw
        }
    }

    /** 一个条目的控件标题；[englishKeyNames] = 「显示原始配置键名」开关。 */
    fun configLabel(entry: SettingsEntry, englishKeyNames: Boolean): String =
        configKeyLabel(entry.key, englishKeyNames, entry)

    /**
     * 按条目键取显示名（重启提示这类只有 [ConfigKey] 的地方也走这里）。
     *
     * 三条路：英文键名 / 页面标签（zhs 缺译回落日式页面标签）/ 页面标签（日语）。
     * 中文模式保留改动前的兜底顺序：页面 zhs → app 侧兜底表 → 配置键日文原文。
     */
    fun configKeyLabel(key: ConfigKey, englishKeyNames: Boolean, entry: SettingsEntry? = null): String {
        val spec = ConfigSpecTable.specOf(key)
        if (englishKeyNames || lang == SettingsLanguage.EN) {
            return entry?.enLabel ?: spec?.enLabel ?: key.name
        }
        val page = ConfigPageLabels.byKey(key)
        val jp = entry?.jpLabel ?: spec?.jpLabel ?: key.name
        return when (lang) {
            SettingsLanguage.ZH ->
                entry?.pageZhsLabel ?: page?.zhsLabel ?: ZH_CONFIG_LABELS[key] ?: jp
            SettingsLanguage.JA ->
                entry?.pageJaLabel ?: page?.jaLabel ?: jp
            SettingsLanguage.EN -> entry?.enLabel ?: spec?.enLabel ?: key.name
        }
    }

    fun rejectReason(reason: SettingsRejectReason): String = when (reason) {
        SettingsRejectReason.UNKNOWN_ID -> rejectUnknown
        SettingsRejectReason.TYPE_MISMATCH -> rejectType
        SettingsRejectReason.INVALID_TEXT -> rejectInvalid
        SettingsRejectReason.EMPTY_TEXT -> rejectEmpty
        SettingsRejectReason.OUT_OF_RANGE -> rejectRange
        SettingsRejectReason.FIXED -> rejectFixed
    }

    /**
     * 拒因明细。core 给的原文是日语（`Lang.cs` 的原文/自定文案），已知的按当前语言重述；
     * 不认识的句子原样保留，不掩盖也不编造。日语模式一律原样返回（与改动前逐字一致）。
     */
    fun rejectDetail(raw: String): String {
        if (lang == SettingsLanguage.JA) return raw
        return when (raw) {
            "設定項目が見つかりません" -> detailUnknownId
            "この項目は固定されているため変更できません" -> detailFixed
            "設定項目の型が一致しません" -> detailTypeMismatch
            "空の値は設定できません" -> detailEmpty
            "不正な指定です" -> detailInvalidSpec
            "数字でない文字が含まれています" -> detailNonNumeric
            "値をColor指定子として認識できません" -> detailColor
            "フォントサイズが小さすぎます(8が下限)" -> detailFontTooSmall
            "表示するセーブデータ数が少なすぎます(20が下限)" -> detailSaveTooFew
            "表示するセーブデータ数が多すぎます(80が上限)" -> detailSaveTooMany
            "ログ表示行数が少なすぎます(500が下限)" -> detailLogTooSmall
            else -> OUT_OF_RANGE.find(raw)?.let { fill(detailRangeFormat, it.groupValues[1]) } ?: raw
        }
    }

    fun pageTitle(page: SettingsPage): String = when (page) {
        SettingsPage.ENV -> pageEnv
        SettingsPage.DISPLAY -> pageDisplay
        SettingsPage.WINDOW -> pageWindow
        SettingsPage.FONT -> pageFont
        SettingsPage.SYSTEM -> pageSystem
        SettingsPage.SYSTEM2 -> pageSystem2
        SettingsPage.COMPAT -> pageCompat
        SettingsPage.PARSING -> pageParsing
        SettingsPage.CLIPBOARD -> pageClipboard
        SettingsPage.RIKAI -> pageRikai
        SettingsPage.ADVANCED -> pageAdvanced
        SettingsPage.OTHER -> pageOther
    }

    fun pageHint(page: SettingsPage): String? = when (page) {
        SettingsPage.ADVANCED -> pageAdvancedHint
        SettingsPage.RIKAI -> pageRikaiHint
        else -> null
    }

    fun fontColumnLabel(columns: Int): String = when (columns) {
        90 -> col90Label
        75 -> col75Label
        150 -> col150Label
        else -> col136Label
    }

    fun fontColumnHint(columns: Int): String = when (columns) {
        90 -> col90Hint
        75 -> col75Hint
        150 -> col150Hint
        else -> col136Hint
    }

    fun railLabel(size: UiPrefs.RailButtonSize): String = when (size) {
        UiPrefs.RailButtonSize.SMALL -> railSmallLabel
        UiPrefs.RailButtonSize.NORMAL -> railNormalLabel
        UiPrefs.RailButtonSize.LARGE -> railLargeLabel
        UiPrefs.RailButtonSize.XLARGE -> railXlargeLabel
    }

    fun railHint(size: UiPrefs.RailButtonSize): String = when (size) {
        UiPrefs.RailButtonSize.SMALL -> railSmallHint
        UiPrefs.RailButtonSize.NORMAL -> railNormalHint
        UiPrefs.RailButtonSize.LARGE -> railLargeHint
        UiPrefs.RailButtonSize.XLARGE -> railXlargeHint
    }

    fun loadingLabel(style: UiPrefs.LoadingStyle): String = when (style) {
        UiPrefs.LoadingStyle.PIXEL -> loadingPixelLabel
        UiPrefs.LoadingStyle.MODERN -> loadingModernLabel
    }

    fun loadingHint(style: UiPrefs.LoadingStyle): String = when (style) {
        UiPrefs.LoadingStyle.PIXEL -> loadingPixelHint
        UiPrefs.LoadingStyle.MODERN -> loadingModernHint
    }

    /** 字体备注按族 id 取三语文本；未登记的族回落渲染层给的原文。 */
    fun fontNote(id: String, fallback: String): String = when (id) {
        "sarasa" -> fontNoteSarasa
        "lxgw" -> fontNoteLxgw
        "maple" -> fontNoteMaple
        "saitamaar" -> fontNoteSaitamaar
        "misaki" -> fontNoteMisaki
        "fusion" -> fontNoteFusion
        "ark" -> fontNoteArk
        "unifont" -> fontNoteUnifont
        else -> fallback
    }

    companion object {

        fun of(language: SettingsLanguage): SettingsStrings = when (language) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }

        /** `文件超过 8 MB，不像存档` 里的字节数。 */
        private val TOO_BIG = Regex("文件超过 (\\d+) MB，不像存档")

        /**
         * 句式占位符 `{0}` / `{1}`。单趟替换：参数里本身带 `{n}`（例如玩家给存档起的
         * 文件名）不会被二次展开，也不会把别的位置顶掉。
         *
         * **花括号必须走字符类 `[{]`/`[}]` 而不是 `\{`**：Android 的正则是 ICU 引擎，
         * `\{` 直接抛 `PatternSyntaxException`（OpenJDK 却容忍）——2026-10-30 实测：
         * 本行写成 `\\{` 后整个 companion 静态初始化在真机炸，设置页一点就崩
         * （`ExceptionInInitializerError`，桌面单测全绿抓不到）。
         */
        private val SLOT = Regex("[{](\\d+)[}]")

        /** core 的区间拒因：`設定値が範囲外です(1..100)`。 */
        private val OUT_OF_RANGE = Regex("設定値が範囲外です\\((.+)\\)")

        private val ZH: SettingsStrings = SettingsStrings(
            lang = SettingsLanguage.ZH,
            back = "← 返回",
            title = "设置",
            reset = "重置",
            resetAll = "全部重置",
            save = "保存",
            saveAndRestart = "保存并重启",
            filePrefix = "文件：",
            unsavedSuffix = " · 未保存",
            noticesTitle = "读取提示",
            languageTitle = "显示语言",
            languageHint = "只影响设置页，随改随存",
            appPrefsTitle = "应用偏好",
            appPrefsSubtitle = "只影响安卓壳层 · 随改随存",
            restoreDefaultsTitle = "恢复默认设置",
            restoreDefaultsHint = "只把原版设置页那批项（emuera.config 里的游戏配置）恢复为本移植版随包出厂值并立即写盘；应用偏好（本页语言、字号档、竖屏模式、右栏、启动画面、更新开关等）、存档、已导入的补丁与素材都不受影响。此操作不可撤销。",
            restoreDefaultsButton = "恢复默认设置…",
            restoreDefaultsConfirmTitle = "恢复默认设置？",
            restoreDefaultsConfirmBody = "emuera.config 里的游戏配置会恢复成本移植版随包出厂值并立即写盘。不会改动应用偏好（本页语言、字号档、竖屏模式、右栏、启动画面、更新开关等），不会切回简体中文，也不会重开本页或返回游戏。存档和已导入的补丁不会被删除。此操作不可撤销。",
            restoreDefaultsConfirmOk = "恢复默认",
            restoreDefaultsDone = "已恢复原版游戏配置的出厂默认值并写盘",
            restoreDefaultsPinnedFormat = "；{0} 项被 fixed.config 钉住，未改动",
            restoreDefaultsWriteFailed = "写盘失败，游戏配置未改动",
            fontTitle = "字号与字体",
            fontSizeLabel = "显示大小",
            fontFamilyLabel = "正文字体",
            coverageFormat = "字数覆盖 {0}%",
            fontRestartHint = "改字体/字号/行距需重开游戏生效（写 emuera.config 的 FONT NAME / FONT SIZE / LINE HEIGHT）",
            fontNoteSarasa = "更纱黑体等宽 · 思源黑体同源 · OFL-1.1 · 原版黑体替身 · 严格 1:2",
            fontNoteLxgw = "霞鹜文楷等宽 · OFL-1.1 · 楷体观感 · 严格 1:2",
            fontNoteMaple = "Maple Mono CN · OFL-1.1 · 圆润等宽 · 严格 1:2",
            fontNoteSaitamaar = "Saitamaar · 公有领域 · 比例字体按 1:2 兜底",
            fontNoteMisaki = "美咲ゴシック（eratw 原版观感）· 严格 1:2 · 缺字走随包回落",
            fontNoteFusion = "缝合像素 12px · OFL-1.1 · 半角 600 / 全角 1200",
            fontNoteArk = "方舟像素 12px · OFL-1.1 · 点字（盲文）覆盖更全",
            fontNoteUnifont = "GNU Unifont 16px · OFL-1.1 · 只差 emoji/私用区",
            col136Label = "标准",
            col136Hint = "字号 ×1.00（跟随 FONT SIZE）；每行至少 136 列（默认；128 格地图完整）",
            col90Label = "大",
            col90Hint = "字号 ×1.51（约 90 列）；宽地图可能折行",
            col75Label = "特大",
            col75Hint = "字号 ×1.81（约 75 列）；宽地图会折行",
            col150Label = "小",
            col150Hint = "字号 ×0.91（约 150 列）；字更小，地图更稳",
            railSmallLabel = "小",
            railSmallHint = "右栏最窄，被它盖住的画面最少；标题长的选项会被截断",
            railNormalLabel = "标准",
            railNormalHint = "默认大小",
            railLargeLabel = "大",
            railLargeHint = "按钮更高、标题更大；右栏占的画面更多",
            railXlargeLabel = "特大",
            railXlargeHint = "标题最大最好点；右栏几乎盖住半屏，可用右上螺旋图标收起",
            loadingPixelLabel = "像素风",
            loadingPixelHint = "方块进度条，复古文字游戏味",
            loadingModernLabel = "现代风",
            loadingModernHint = "深色渐变页，简洁",
            railSampleTalk = "会话",
            railSampleMove = "移动到广场",
            railSampleBuy = "购买[酒]",
            controlTitle = "操作与显示",
            railSizeLabel = "选项按钮大小",
            railFootnote = "右栏随时可用右上角的螺旋图标收起；改大小不需要重开游戏",
            knobLabel = "底边抓手位置",
            knobLeft = "靠左",
            knobCenter = "居中",
            knobRight = "靠右",
            screenFitTitle = "保证地图列数（列数守护）",
            screenFitHint = "开：屏宽不足所选列数时压小字号，保证 128 格地图行不折行；关：字号优先，列数随字号走（宽地图可能折行）。真正调字号请到「字体」页改 FONT SIZE。",
            leanTitle = "更精简的游戏选项",
            leanHint = "开：右侧选项栏只保留屏上印了自己编号的常用指令（如「移动[400]」「[100] - 睁开眼睛」）；关：显示全部识别结果（默认）",
            portraitTitle = "竖屏模式",
            portraitHint = "开：游戏按竖屏版面显示（单手握持）；关：锁定横屏，与原版版面一致（默认）。切换后需重开游戏生效。",
            liveReflowTitle = "窗口宽窄变化时重新排版",
            liveReflowHint = "默认关。开：转屏、分屏、弹出键盘、折叠屏展开等让窗口变宽或变窄时，游戏里显示过的文字会按新宽度重新排版，横屏也一样有效。改这一项后要重开一局才生效。",
            launchTitle = "启动",
            loadingStyleLabel = "启动画面样式",
            badAppleTitle = "屏蔽标题彩蛋（BADAPPLE）",
            badAppleHint = "开：标题画面跳过那段约 105 秒的黑白动画（默认）；关：照原样播放",
            autoUpdateTitle = "自动检查更新",
            autoUpdateHint = "开：进游戏后自动查一次新版，有更新会先弹窗问你要不要（默认）；关：只在这个页面手动查",
            wifiOnlyTitle = "仅 Wi-Fi 下载",
            wifiOnlyHint = "默认关。开：用手机流量时点下载会直接说明「已设为仅 Wi-Fi」，不会开始下载，也不给「仍要下载」；关：走原来的二次确认（默认）",
            checkUpdateButton = "立即检查更新",
            updateSkippedLineFormat = "已跳过版本 {0} 的更新提醒；出现更新的版本时仍会提醒（「立即检查更新」也会照常报它）",
            updateRestoreButton = "恢复更新提醒",
            updateRestoreDone = "已恢复：下次检查到这个版本还会提醒",
            advancedTitle = "高级",
            rawKeysTitle = "显示原始配置键名",
            rawKeysHint = "按 emuera.config 里的英文键名显示每一项",
            prefsFootnote = "以上偏好随改随存，不写入 emuera.config",
            saveGroupTitle = "存档",
            saveGroupSubtitle = "不经「保存」按钮，直接读写存档文件",
            exportTitle = "导出存档",
            exportHint = "把全部存档连同全局存档打包成一个 zip",
            exportBusy = "正在导出…",
            exportButton = "一键导出存档",
            importTitle = "导入已有存档",
            importBusy = "正在导入…",
            importButton = "选择存档文件",
            modImportIntro = "选择口上 .ERB/.ERH 文件或整个 zip 包（可多选）：源文件保存进 mods，编译出的补丁重启游戏后生效。",
            modImportPickButton = "选择口上文件",
            modGroupTitle = "Mod 与素材",
            modGroupSubtitle = "自备素材补丁（背景图、BGM）与口上补丁的导入入口",
            resourcePackTitle = "导入素材压缩包（可选）",
            resourcePackIntro = "只有手里是现成 zip / 7z 时才用这里：包内要保留 resources/ 或 sound/ 层（官方背景图包 BGI.csv + 00..11/通用 可直接选）。平时请用上面两张卡直接选图片、音频或文件夹。",
            resourcePackButton = "选择压缩包",
            resourcePackBusy = "正在导入…",
            resourcePackStatusUnavailable = "资源现状：读取失败",
            resourcePackStatusBackgroundFormat = "背景图：{0} 张 · 索引 {1} 行",
            resourcePackStatusBackgroundEmpty = "背景图：未导入",
            resourcePackStatusBackgroundNoIndexFormat = "背景图：有 {0} 张图，但没有索引（CSV）",
            resourcePackStatusBgmFormat = "BGM：{0} 首 · {1} 个地点",
            resourcePackStatusBgmEmpty = "BGM：未导入",
            resourcePackImportedFormat = "已导入 {0} 个文件（来源 {1} 个）；覆盖 {2} 个，跳过 {3} 项。",
            resourcePackSkippedFormat = "跳过：{0}",
            resourcePackRestartButton = "重启游戏生效",
            diagTitle = "运行诊断（闪退排查）",
            diagHint = "把最近 64 行内存轨迹连同死因判读导出成一个文本；游戏突然退出又没有任何报错时，请把它发给开发者",
            diagBusy = "正在生成…",
            diagShareButton = "导出并分享诊断",
            diagCopyButton = "复制诊断",
            diagReadyFormat = "已生成 {0}",
            diagFailed = "生成失败：读不到运行记录",
            diagCopied = "诊断已复制，粘贴发给开发者即可",
            diagCopyFailed = "复制失败，请改用「导出并分享诊断」",
            diagShareTitle = "分享运行诊断",
            diagShareFailed = "分享面板启动失败，请改用「复制诊断」",
            gameGroupTitle = "游戏配置",
            gameGroupSubtitleFormat = "{0} · {1} 项 · 可编辑",
            debugReadOnlyTitle = "调试窗口（debug.config）· 只读",
            replaceReadOnlyTitle = "替换数据（_Replace.csv）· 只读",
            gameReadOnlyTitle = "游戏配置 · 只读",
            debugReadOnlyHint = "只读：原版由 debug.config 读取，本页不写回",
            replaceReadOnlyHint = "只读：原版由 _Replace.csv 读取，没有设置页，本页不写回",
            pinnedByFixed = "已被 fixed.config 钉住",
            emptyValue = "（空）",
            effectiveValueFormat = "生效值：{0}",
            currentDefaultEffectiveFormat = "当前值：{0} · 默认值：{1} · 生效值：{2}",
            bottomFootnote = "保存＝写盘（热更项立即生效，重启项下次进入游戏生效）；保存并重启＝写盘后重开（本次改动全是热更项时不重启）",
            listSeparator = "、",
            okText = "确定",
            cancelText = "取消",
            overwriteTitle = "这些位置已经有存档",
            overwriteUnknownNote = "读不出备注",
            overwriteNoNote = "无备注",
            overwriteLineFormat = "{0} 已有：{1}\n要放进来的：{2}（{3}）",
            overwriteConfirmFormat = "覆盖这 {0} 格",
            pageEnv = "环境",
            pageDisplay = "显示",
            pageWindow = "窗口",
            pageFont = "字体",
            pageSystem = "系统",
            pageSystem2 = "系统（二）",
            pageCompat = "兼容性",
            pageParsing = "解析",
            pageClipboard = "剪贴板",
            pageRikai = "弹窗翻译",
            pageAdvanced = "其他（高级）",
            pageOther = "其他",
            pageAdvancedHint = "原版设置页没有列出这些项；其中 CompatiDRAWLINE 在原版永不写出（读取时被重定向）",
            pageRikaiHint = "复用字体页的「背景颜色 / 文本颜色」译文（原版如此）",
            rejectUnknown = "未知条目",
            rejectType = "类型不符",
            rejectInvalid = "解析失败",
            rejectEmpty = "空文本",
            rejectRange = "超出范围",
            rejectFixed = "已被 fixed.config 钉住",
            detailUnknownId = "找不到这个设置项",
            detailFixed = "该项已被固定，无法修改",
            detailTypeMismatch = "设置项的类型不匹配",
            detailEmpty = "不能设置为空值",
            detailInvalidSpec = "非法的值",
            detailNonNumeric = "包含非数字的文字",
            detailColor = "无法被解析为颜色名的值",
            detailFontTooSmall = "字体大小过小(下限为8)",
            detailSaveTooFew = "显示的存档数据数量过小(下限为20)",
            detailSaveTooMany = "显示的存档数据数量过大(上限为80)",
            detailLogTooSmall = "日志显示行数过少(下限为500)",
            detailRangeFormat = "设置值超出范围（{0}）",
            loadErrorFormat = "读取失败：{0}",
            loadAbortedFormat = "第 {0} 行解析失败，之后 {1} 行未读",
            loadWarningsFormat = "解析告警 {0} 条（首条：{1}）",
            loadMissing = "未找到 emuera.config，当前显示默认值",
            resetAllDoneFormat = "已重置 {0} 项：应用偏好已即时生效，游戏配置尚未写盘（点「保存」或「保存并重启」后才写入）",
            resetAllDonePinnedFormat = "；{0} 项被 fixed.config 钉住，未改动",
            saveWriteFailed = "写盘失败，原文件未改动",
            saveRestartWriteFailed = "写盘失败，原文件未改动，未重启",
            savedHotNoRestartFormat = "已写入并热更 {0} 项，立即生效，未重启",
            summaryNoChangeFormat = "没有改动，已重写 {0}",
            summaryHotRestartFormat = "已写入：热更 {0} 项已生效；{1} 需重启",
            summaryRestartFormat = "已写入；{0} 需重启生效",
            summaryHotFormat = "已写入并热更 {0} 项，立即生效",
            summaryNoSession = "已写入；当前没有在跑的会话，下次进入游戏生效",
            moreItemsFormat = "{0} 等 {1} 项",
            rejectToastFormat = "{0}：{1}",
            titleBodyFormat = "{0}：{1}",
            configUnreadableImportFormat = "读不到存档目录的口径（{0}），未导入",
            importUnreadFormat = "未导入 {0}：读不到内容，或它超过体积上限",
            importRejectedFormat = "未导入 {0}：{1}",
            importedFormat = "已导入 {0}",
            importedWithNoteFormat = "已导入 {0}：{1}",
            importWriteFailedFormat = "{0} 没能写进去（磁盘满或目录不可写）",
            importSkippedOccupiedFormat = "未导入 {0}：那一格里已经有存档",
            unexpectedFailure = "处理时发生错误",
            selectedFileFallback = "选中的文件",
            globalSaveLabel = "全局存档",
            slotFormat = "存档位 {0}",
            noNote = "无备注",
            configUnreadableExportFormat = "读不到存档目录的口径（{0}），未导出",
            exportCollectFailed = "导出失败：读不了存档目录",
            exportNothing = "没有可导出的存档",
            exportPickerFailed = "打不开系统文件选择器",
            exportDoneFormat = "已导出 {0} 个存档{1} → {2}",
            exportIncludingGlobal = "（含全局存档）",
            exportFailedAtFormat = "导出失败：{0} 读不出来（已写 {1} 个）",
            exportFailedGenericFormat = "导出失败（已写 {0} 个）",
            exportSkippedFormat = "跳过 {0}：{1}",
            reasonOnlySav = "只能导入 .sav 存档文件",
            reasonCorrupted = "存档数据已损坏",
            reasonDifferentGame = "试图读取的存档属于另一个游戏",
            reasonDifferentVersion = "存档版本不同",
            reasonSlotTooLarge = "存档位编号太大",
            reasonSlotsFull = "存档位已经满了，没有空位",
            reasonTooBigFormat = "文件超过 {0} MB，不像存档",
            reasonEmptyFile = "是空文件",
            reasonOversize = "超过体积上限",
            zonePortTab = "移植版功能",
            zoneEngineTab = "原版引擎",
            zoneModTab = "Mod",
            zoneAboutTab = "关于",
            aboutGroupSubtitle = "版本 · 开发者 · 反馈渠道",
            aboutVersionFormat = "当前版本 {0}（build {1}）",
            aboutBuildFormat = "构建 {0}",
            aboutDeveloperCaption = "开发者",
            aboutDeveloperName = "Poboking",
            aboutRepoCaption = "项目仓库",
            aboutRepoUrl = "https://github.com/Poboking/eratw-pocket",
            aboutRepoButton = "打开仓库",
            aboutRepoFailed = "没有能打开链接的应用",
            aboutFeedbackCaption = "Bug 反馈群（QQ）",
            aboutGroupId = "1130136207",
            aboutCopyGroupButton = "复制群号",
            aboutCopiedFormat = "已复制 {0}",
            creditsTitle = "署名与声明",
            creditsHeadGame = "游戏本体",
            creditsGameBody = "本应用所运行的游戏本体（画蛇添足版 eraTW）由画蛇添足开发组创作并维护。本项目不撰写、不修改、也不解释任何游戏内容；游戏内容的著作权归画蛇添足开发组，以及各位口上、事件与数据的所有者。",
            creditsHeadScope = "本项目的范围",
            creditsScopeBody = "本项目只提供安卓原生的 era 引擎与 Android 兼容层：把 PC 版 Emuera 的运行环境搬到安卓上，不改变脚本语义。游戏行为的兼容性以 Emuera 的实际表现为参考基准。",
            creditsHeadThanks = "致谢",
            creditsItemEmuera = "MinorShift —— Emuera 的著作者。",
            creditsItemKoujo = "まだ名前は無い人 —— 以及所有为 eraTW 撰写口上、事件、数据与文档的创作者。",
            creditsItemCommunity = "era 社区的各位脚本作者、内容作者与工具维护者。",
            creditsItemTouhou = "上海アリス幻樂団（ZUN）—— 东方 Project 的原作。",
            creditsLiability = "因本移植产生的问题由本项目承担，与上海アリス幻樂団、画蛇添足开发组及各内容作者无关。",
        )

        private val EN: SettingsStrings = SettingsStrings(
            lang = SettingsLanguage.EN,
            back = "← Back",
            title = "Settings",
            reset = "Reset",
            resetAll = "Reset all",
            save = "Save",
            saveAndRestart = "Save & restart",
            filePrefix = "File: ",
            unsavedSuffix = " · Unsaved",
            noticesTitle = "Load notices",
            languageTitle = "Language",
            languageHint = "Applies to this settings page only; saved immediately",
            appPrefsTitle = "App preferences",
            appPrefsSubtitle = "Android shell only · saved immediately",
            restoreDefaultsTitle = "Restore defaults",
            restoreDefaultsHint = "Restores only the original settings-page items (the game settings in emuera.config) to the factory values shipped with this Android port and writes them to disk immediately. App preferences (page language, text size, portrait mode, option rail, loading screen, update checks and more), saves, installed patches and asset packs are untouched. This cannot be undone.",
            restoreDefaultsButton = "Restore defaults…",
            restoreDefaultsConfirmTitle = "Restore default settings?",
            restoreDefaultsConfirmBody = "The game settings in emuera.config return to the factory values shipped with this port and are written to disk immediately. App preferences (page language, text size, portrait mode, option rail, loading screen, update checks and more) are not changed, the page language does not switch back, and this page is not reopened or the game restarted. Saves and installed patches are not deleted. This cannot be undone.",
            restoreDefaultsConfirmOk = "Restore defaults",
            restoreDefaultsDone = "Factory game settings restored and written to disk",
            restoreDefaultsPinnedFormat = "; {0} items were pinned by fixed.config and left unchanged",
            restoreDefaultsWriteFailed = "Write failed; the game settings were not changed",
            fontTitle = "Text size & font",
            fontSizeLabel = "Display size",
            fontFamilyLabel = "Body font",
            coverageFormat = "text coverage {0}%",
            fontRestartHint = "Restart the game for the new font/size/line height to take effect (writes FONT NAME / FONT SIZE / LINE HEIGHT to emuera.config)",
            fontNoteSarasa = "Sarasa Mono SC · Source Han Sans based · OFL-1.1 · SimHei stand-in · strict 1:2",
            fontNoteLxgw = "LXGW WenKai Mono · OFL-1.1 · brush-style look · strict 1:2",
            fontNoteMaple = "Maple Mono CN · OFL-1.1 · rounded mono look · strict 1:2",
            fontNoteSaitamaar = "Saitamaar · public domain · proportional font fitted to the 1:2 grid",
            fontNoteMisaki = "Misaki Gothic (original eratw look) · strict 1:2 · missing glyphs use bundled fallback",
            fontNoteFusion = "Fusion Pixel 12px · OFL-1.1 · half-width 600 / full-width 1200",
            fontNoteArk = "Ark Pixel 12px · OFL-1.1 · better Braille coverage",
            fontNoteUnifont = "GNU Unifont 16px · OFL-1.1 · only emoji and private-use area missing",
            col136Label = "Standard",
            col136Hint = "Text ×1.00 (follows FONT SIZE); at least 136 columns (default; 128-cell maps fit on one line)",
            col90Label = "Large",
            col90Hint = "Text ×1.51 (about 90 columns); wide maps may wrap",
            col75Label = "Extra large",
            col75Hint = "Text ×1.81 (about 75 columns); wide maps will wrap",
            col150Label = "Small",
            col150Hint = "Text ×0.91 (about 150 columns); smaller text, maps safer",
            railSmallLabel = "Small",
            railSmallHint = "Narrowest rail, covers the least of the screen; long labels get truncated",
            railNormalLabel = "Standard",
            railNormalHint = "Default size",
            railLargeLabel = "Large",
            railLargeHint = "Taller buttons and larger labels; the rail covers more of the screen",
            railXlargeLabel = "Extra large",
            railXlargeHint = "Largest and easiest to tap; the rail covers nearly half the screen — collapse it with the spiral icon at top right",
            loadingPixelLabel = "Pixel",
            loadingPixelHint = "Blocky progress bar with a retro text-game feel",
            loadingModernLabel = "Modern",
            loadingModernHint = "Dark gradient page, clean",
            railSampleTalk = "Talk",
            railSampleMove = "Move to plaza",
            railSampleBuy = "Buy [sake]",
            controlTitle = "Controls & display",
            railSizeLabel = "Option button size",
            railFootnote = "Collapse the rail anytime with the spiral icon at top right; changing the size needs no restart",
            knobLabel = "Bottom handle position",
            knobLeft = "Left",
            knobCenter = "Center",
            knobRight = "Right",
            screenFitTitle = "Keep map columns (column guard)",
            screenFitHint = "On: shrink the font when the screen cannot fit the selected column count, so 128-cell maps never wrap; Off: the font wins and columns follow it (wide maps may wrap). To change text size, edit FONT SIZE on the Font page.",
            leanTitle = "Leaner option rail",
            leanHint = "On: show only common commands that print their own number (for example \"Move[400]\" and \"[100] - Wake up\"); Off: show every recognized option (default)",
            portraitTitle = "Portrait mode",
            portraitHint = "On: play in the portrait layout (one-handed use); Off: lock to landscape like the original (default). Takes effect after the game restarts.",
            liveReflowTitle = "Reflow text when the window resizes",
            liveReflowHint = "Off by default. On: when the window grows or shrinks (rotation, split screen, on-screen keyboard, unfolding a foldable), text already shown is laid out again to the new width; this applies in landscape too. Takes effect after the game restarts.",
            launchTitle = "Startup",
            loadingStyleLabel = "Loading screen style",
            badAppleTitle = "Skip title easter egg (BADAPPLE)",
            badAppleHint = "On: skip the ~105-second black-and-white animation on the title screen (default); Off: play it as-is",
            autoUpdateTitle = "Check for updates automatically",
            autoUpdateHint = "On: check once after launch and always ask before downloading anything (default); Off: manual checks only from this page",
            wifiOnlyTitle = "Download over Wi-Fi only",
            wifiOnlyHint = "Off by default. On: tapping Download on mobile data simply says it is set to Wi-Fi only, with no way to override it here; Off: the usual confirmation is asked (default)",
            checkUpdateButton = "Check for updates now",
            updateSkippedLineFormat = "Update reminders for version {0} are silenced; newer versions still remind you (\"Check for updates now\" reports it regardless)",
            updateRestoreButton = "Restore update reminders",
            updateRestoreDone = "Restored: this version will remind you again on the next check",
            advancedTitle = "Advanced",
            rawKeysTitle = "Show raw config keys",
            rawKeysHint = "Show each item by its English key name in emuera.config",
            prefsFootnote = "These preferences are saved immediately and are not written to emuera.config",
            saveGroupTitle = "Saves",
            saveGroupSubtitle = "Works directly on the save files, not through Save",
            exportTitle = "Export saves",
            exportHint = "Pack all saves and the global save into one zip",
            exportBusy = "Exporting…",
            exportButton = "Export all saves",
            importTitle = "Import saves",
            importBusy = "Importing…",
            importButton = "Choose save files",
            modImportIntro = "Pick .ERB/.ERH script files or a whole zip pack (multiple selection): sources are saved into mods and the compiled patch takes effect after restarting the game.",
            modImportPickButton = "Choose script files",
            modGroupTitle = "Mods & assets",
            modGroupSubtitle = "Asset packs and script patches",
            resourcePackTitle = "Import an asset archive (optional)",
            resourcePackIntro = "Only for ready-made zip / 7z archives that keep the resources/ or sound/ layer (the official background pack with BGI.csv works as is). Normally use the two cards above to pick images, audio or folders directly.",
            resourcePackButton = "Choose archive",
            resourcePackBusy = "Importing…",
            resourcePackStatusUnavailable = "Asset status: unreadable",
            resourcePackStatusBackgroundFormat = "Backgrounds: {0} images · {1} index rows",
            resourcePackStatusBackgroundEmpty = "Backgrounds: not imported",
            resourcePackStatusBackgroundNoIndexFormat = "Backgrounds: {0} images but no index (CSV)",
            resourcePackStatusBgmFormat = "BGM: {0} tracks · {1} places",
            resourcePackStatusBgmEmpty = "BGM: not imported",
            resourcePackImportedFormat = "Imported {0} files from {1} source(s); {2} overwritten, {3} skipped.",
            resourcePackSkippedFormat = "Skipped: {0}",
            resourcePackRestartButton = "Restart to apply",
            diagTitle = "Runtime diagnostics (crash triage)",
            diagHint = "Export the last 64 memory-trail lines with a death-mode verdict; send it to the developer when the game closes with no error",
            diagBusy = "Generating…",
            diagShareButton = "Export and share",
            diagCopyButton = "Copy diagnostics",
            diagReadyFormat = "Created {0}",
            diagFailed = "Failed: no runtime record found",
            diagCopied = "Diagnostics copied — paste it to the developer",
            diagCopyFailed = "Copy failed; use \"Export and share\" instead",
            diagShareTitle = "Share runtime diagnostics",
            diagShareFailed = "Share sheet failed; use \"Copy diagnostics\" instead",
            gameGroupTitle = "Game settings",
            gameGroupSubtitleFormat = "{0} · {1} items · editable",
            debugReadOnlyTitle = "Debug window (debug.config) · read-only",
            replaceReadOnlyTitle = "Replacement data (_Replace.csv) · read-only",
            gameReadOnlyTitle = "Game settings · read-only",
            debugReadOnlyHint = "Read-only: read from debug.config; this page never writes back",
            replaceReadOnlyHint = "Read-only: read from _Replace.csv; the original has no settings page, and this page never writes back",
            pinnedByFixed = "Pinned by fixed.config",
            emptyValue = "(empty)",
            effectiveValueFormat = "Effective: {0}",
            currentDefaultEffectiveFormat = "Current: {0} · Default: {1} · Effective: {2}",
            bottomFootnote = "Save = write to disk (hot items apply now; restart items apply next launch); Save & restart = write, then relaunch (skipped when every change is a hot item)",
            listSeparator = ", ",
            okText = "OK",
            cancelText = "Cancel",
            overwriteTitle = "These slots already contain saves",
            overwriteUnknownNote = "no readable note",
            overwriteNoNote = "no note",
            overwriteLineFormat = "{0} already has: {1}\nIncoming: {2} ({3})",
            overwriteConfirmFormat = "Overwrite {0} slots",
            pageEnv = "Environment",
            pageDisplay = "Display",
            pageWindow = "Window",
            pageFont = "Font",
            pageSystem = "System",
            pageSystem2 = "System (2)",
            pageCompat = "Compatibility",
            pageParsing = "Parsing",
            pageClipboard = "Clipboard",
            pageRikai = "Popup translation",
            pageAdvanced = "Other (advanced)",
            pageOther = "Other",
            pageAdvancedHint = "The original settings dialog does not list these items; CompatiDRAWLINE is never written by the original (it is redirected when read)",
            pageRikaiHint = "Reuses the Font page's \"Background color / Text color\" labels (as in the original)",
            rejectUnknown = "Unknown item",
            rejectType = "Type mismatch",
            rejectInvalid = "Parse error",
            rejectEmpty = "Empty text",
            rejectRange = "Out of range",
            rejectFixed = "Pinned by fixed.config",
            detailUnknownId = "Setting item not found",
            detailFixed = "This item is fixed and cannot be changed",
            detailTypeMismatch = "Setting type mismatch",
            detailEmpty = "An empty value cannot be set",
            detailInvalidSpec = "Invalid value",
            detailNonNumeric = "Contains non-numeric characters",
            detailColor = "Value is not a recognized color specifier",
            detailFontTooSmall = "Font size is too small (minimum 8)",
            detailSaveTooFew = "Save data count is too small (minimum 20)",
            detailSaveTooMany = "Save data count is too large (maximum 80)",
            detailLogTooSmall = "Log size is too small (minimum 500)",
            detailRangeFormat = "Value out of range ({0})",
            loadErrorFormat = "Load failed: {0}",
            loadAbortedFormat = "Parse failed at line {0}; {1} lines were not read",
            loadWarningsFormat = "{0} parse warnings (first: {1})",
            loadMissing = "emuera.config not found; showing defaults",
            resetAllDoneFormat = "Reset {0} settings: app preferences took effect immediately; the game settings are not written yet (use Save or Save & Restart to write them)",
            resetAllDonePinnedFormat = "; {0} items were pinned by fixed.config and left unchanged",
            saveWriteFailed = "Write failed; the file was not changed",
            saveRestartWriteFailed = "Write failed; the file was not changed and the game was not restarted",
            savedHotNoRestartFormat = "Saved and hot-applied {0} items; effective now, no restart",
            summaryNoChangeFormat = "No changes; rewrote {0}",
            summaryHotRestartFormat = "Saved: {0} items hot-applied; {1} need a restart",
            summaryRestartFormat = "Saved; {0} take effect after restart",
            summaryHotFormat = "Saved and hot-applied {0} items; effective now",
            summaryNoSession = "Saved; no running session — effective next launch",
            moreItemsFormat = "{0} and {1} more",
            rejectToastFormat = "{0}: {1}",
            titleBodyFormat = "{0}: {1}",
            configUnreadableImportFormat = "Could not read the save folder settings ({0}); nothing was imported",
            importUnreadFormat = "Not imported {0}: unreadable, or larger than the size limit",
            importRejectedFormat = "Not imported {0}: {1}",
            importedFormat = "Imported {0}",
            importedWithNoteFormat = "Imported {0}: {1}",
            importWriteFailedFormat = "{0} could not be written (storage full or folder not writable)",
            importSkippedOccupiedFormat = "Not imported {0}: that slot already has a save",
            unexpectedFailure = "Unexpected error",
            selectedFileFallback = "selected file",
            globalSaveLabel = "Global save",
            slotFormat = "Slot {0}",
            noNote = "no note",
            configUnreadableExportFormat = "Could not read the save folder settings ({0}); nothing was exported",
            exportCollectFailed = "Export failed: cannot read the save folder",
            exportNothing = "There are no saves to export",
            exportPickerFailed = "Could not open the system file picker",
            exportDoneFormat = "Exported {0} saves{1} → {2}",
            exportIncludingGlobal = " (including the global save)",
            exportFailedAtFormat = "Export failed: {0} could not be read ({1} written)",
            exportFailedGenericFormat = "Export failed ({0} written)",
            exportSkippedFormat = "Skipped {0}: {1}",
            reasonOnlySav = "Only .sav save files can be imported",
            reasonCorrupted = "The save data is corrupted",
            reasonDifferentGame = "The save belongs to a different game",
            reasonDifferentVersion = "The save version differs",
            reasonSlotTooLarge = "The slot number is too large",
            reasonSlotsFull = "All save slots are full",
            reasonTooBigFormat = "The file is over {0} MB and does not look like a save",
            reasonEmptyFile = "The file is empty",
            reasonOversize = "Exceeds the size limit",
            zonePortTab = "Port features",
            zoneEngineTab = "Original engine",
            zoneModTab = "Mods",
            zoneAboutTab = "About",
            aboutGroupSubtitle = "Version · developer · where to report bugs",
            aboutVersionFormat = "Version {0} (build {1})",
            aboutBuildFormat = "Build {0}",
            aboutDeveloperCaption = "Developer",
            aboutDeveloperName = "Poboking",
            aboutRepoCaption = "Repository",
            aboutRepoUrl = "https://github.com/Poboking/eratw-pocket",
            aboutRepoButton = "Open repository",
            aboutRepoFailed = "No app can open this link",
            aboutFeedbackCaption = "Bug report group (QQ)",
            aboutGroupId = "1130136207",
            aboutCopyGroupButton = "Copy group number",
            aboutCopiedFormat = "Copied {0}",
            creditsTitle = "Credits and notices",
            creditsHeadGame = "The game itself",
            creditsGameBody = "The game this app runs (the 画蛇添足 edition of eraTW) is created and maintained by 画蛇添足开发组. This project writes, edits and explains no game content; copyright in that content belongs to that team and to the authors of each scenario, event and data file.",
            creditsHeadScope = "Scope of this project",
            creditsScopeBody = "This project provides only an Android-native era engine and the Android compatibility layer: it moves the PC Emuera runtime onto Android without changing script semantics. Behavioural compatibility is measured against Emuera's own behaviour.",
            creditsHeadThanks = "Acknowledgements",
            creditsItemEmuera = "MinorShift — author of Emuera.",
            creditsItemKoujo = "まだ名前は無い人 — and everyone who wrote scenarios, events, data and documentation for eraTW.",
            creditsItemCommunity = "The era community: script authors, content authors and tool maintainers.",
            creditsItemTouhou = "Team Shanghai Alice (ZUN) — the original Touhou Project works.",
            creditsLiability = "Any problem caused by this port is this project's responsibility, not that of Team Shanghai Alice, 画蛇添足开发组 or the content authors.",
        )

        private val JA: SettingsStrings = SettingsStrings(
            lang = SettingsLanguage.JA,
            back = "← 戻る",
            title = "設定",
            reset = "リセット",
            resetAll = "すべてリセット",
            save = "保存",
            saveAndRestart = "保存して再起動",
            filePrefix = "ファイル：",
            unsavedSuffix = " · 未保存",
            noticesTitle = "読み込み通知",
            languageTitle = "表示言語",
            languageHint = "この設定画面のみに適用・すぐ保存されます",
            appPrefsTitle = "アプリ設定",
            appPrefsSubtitle = "Android シェルのみ · すぐ保存",
            restoreDefaultsTitle = "既定値に戻す",
            restoreDefaultsHint = "原版の設定画面にある項目（emuera.config のゲーム設定）だけを、この移植版に同梱された出荷時の値に戻してすぐに書き込みます。アプリ設定（この画面の言語、文字サイズ、縦画面、右バー、起動画面、更新確認など）・セーブデータ・導入済みパッチ・素材は変更しません。この操作は取り消せません。",
            restoreDefaultsButton = "既定値に戻す…",
            restoreDefaultsConfirmTitle = "既定値に戻しますか？",
            restoreDefaultsConfirmBody = "emuera.config のゲーム設定を、この移植版に同梱された出荷時の値に戻してすぐに書き込みます。アプリ設定（この画面の言語、文字サイズ、縦画面、右バー、起動画面、更新確認など）は変更せず、言語を簡体字中文に戻すこともしません。この画面の再読み込みやゲームの再起動も行いません。セーブデータと導入済みパッチは削除されません。この操作は取り消せません。",
            restoreDefaultsConfirmOk = "既定値に戻す",
            restoreDefaultsDone = "原版ゲーム設定を出荷時の既定値に戻して書き込みました",
            restoreDefaultsPinnedFormat = "；{0} 項目は fixed.config に固定されているため変更していません",
            restoreDefaultsWriteFailed = "書き込みに失敗しました。ゲーム設定は変更されていません",
            fontTitle = "文字サイズとフォント",
            fontSizeLabel = "表示サイズ",
            fontFamilyLabel = "本文フォント",
            coverageFormat = "文字カバー率 {0}%",
            fontRestartHint = "フォント/文字サイズ/行高の変更はゲーム再起動で反映（emuera.config の FONT NAME / FONT SIZE / LINE HEIGHT に書き込み）",
            fontNoteSarasa = "Sarasa Mono SC · 思源黒体系 · OFL-1.1 · 原本黒体の代替 · 厳密 1:2",
            fontNoteLxgw = "霞鶩文楷等幅 · OFL-1.1 · 楷書風 · 厳密 1:2",
            fontNoteMaple = "Maple Mono CN · OFL-1.1 · 丸みのある等幅 · 厳密 1:2",
            fontNoteSaitamaar = "Saitamaar · パブリックドメイン · プロポーショナルを 1:2 で補正",
            fontNoteMisaki = "美咲ゴシック（eratw 原本の見た目）· 厳密 1:2 · 欠字は同梱フォントで補完",
            fontNoteFusion = "Fusion Pixel 12px · OFL-1.1 · 半角 600 / 全角 1200",
            fontNoteArk = "Ark Pixel 12px · OFL-1.1 · 点字（ブライユ）のカバーが広い",
            fontNoteUnifont = "GNU Unifont 16px · OFL-1.1 · emoji/私用領域のみ欠落",
            col136Label = "標準",
            col136Hint = "文字 ×1.00（FONT SIZE に追従）、1 行あたり最低 136 列（既定、128 マスのマップが 1 行に収まる）",
            col90Label = "大",
            col90Hint = "文字 ×1.51（約 90 列）、広いマップは折り返す可能性",
            col75Label = "特大",
            col75Hint = "文字 ×1.81（約 75 列）、広いマップは折り返す",
            col150Label = "小",
            col150Hint = "文字 ×0.91（約 150 列）、文字は小さくマップはより安全",
            railSmallLabel = "小",
            railSmallHint = "最も細い右バー。画面をあまり隠さないが、長い見出しは切れる",
            railNormalLabel = "標準",
            railNormalHint = "既定の大きさ",
            railLargeLabel = "大",
            railLargeHint = "ボタンが高く見出しも大きい。右バーが画面を多く占める",
            railXlargeLabel = "特大",
            railXlargeHint = "最大で押しやすい。右バーがほぼ半画面を覆うので、右上の渦巻きアイコンで畳める",
            loadingPixelLabel = "ピクセル",
            loadingPixelHint = "ブロック型プログレスバー、レトロな味わい",
            loadingModernLabel = "モダン",
            loadingModernHint = "暗いグラデーション、シンプル",
            railSampleTalk = "会話",
            railSampleMove = "広場へ移動",
            railSampleBuy = "購入[酒]",
            controlTitle = "操作と表示",
            railSizeLabel = "選択肢ボタンの大きさ",
            railFootnote = "右バーは右上の渦巻きアイコンでいつでも畳めます。大きさの変更に再起動は不要",
            knobLabel = "下端ハンドルの位置",
            knobLeft = "左",
            knobCenter = "中央",
            knobRight = "右",
            screenFitTitle = "マップ列数を確保（列数ガード）",
            screenFitHint = "オン：画面が選択列数に満たないとき文字サイズを下げ、128 マスのマップ行を折り返さない。オフ：文字サイズ優先で列数が決まる（広いマップは折り返す）。文字サイズ自体は「フォント」ページの FONT SIZE で変更する。",
            leanTitle = "選択肢を簡略表示",
            leanHint = "オン：番号を自分で表示しているよく使うコマンドだけ出す（例「移動[400]」「[100] - 目を開ける」）。オフ：認識された全選択肢を表示（既定）",
            portraitTitle = "縦画面モード",
            portraitHint = "オン：縦画面レイアウトで表示（片手持ち向け）。オフ：横画面に固定し原本と同じ表示（既定）。切り替えはゲームの再起動後に反映される。",
            liveReflowTitle = "ウィンドウ幅の変化で文字を組み直す",
            liveReflowHint = "既定はオフ。オン：画面回転・分割画面・キーボード表示・折りたたみ展開などで幅が変わると、表示済みの文章を新しい幅で組み直します（横画面でも有効）。変更後はゲームの再起動が必要です。",
            launchTitle = "起動",
            loadingStyleLabel = "起動画面のスタイル",
            badAppleTitle = "タイトルのおまけ（BADAPPLE）を無効化",
            badAppleHint = "オン：タイトル画面の約 105 秒のモノクロ動画をスキップ（既定）。オフ：そのまま再生",
            autoUpdateTitle = "自動で更新を確認",
            autoUpdateHint = "オン：起動後に一度確認し、更新があってもダウンロード前に必ず聞きます（既定）。オフ：このページで手動確認のみ",
            wifiOnlyTitle = "Wi-Fi のみでダウンロード",
            wifiOnlyHint = "既定はオフ。オン：モバイルデータで「ダウンロード」を押すと「Wi-Fi のみに設定済み」と案内し、ここから取得を続けることはできません。オフ：従来どおり確認します（既定）",
            checkUpdateButton = "いま更新を確認",
            updateSkippedLineFormat = "バージョン {0} の更新通知をスキップ中です。新しいバージョンでは引き続き通知します（「いま更新を確認」はスキップに関係なく動作します）",
            updateRestoreButton = "更新通知を復元",
            updateRestoreDone = "復元しました。次の確認でこのバージョンを再び通知します",
            advancedTitle = "上級",
            rawKeysTitle = "設定キー名をそのまま表示",
            rawKeysHint = "emuera.config の英語キー名で各項目を表示",
            prefsFootnote = "これらの設定はすぐ保存され、emuera.config には書き込まれません",
            saveGroupTitle = "セーブデータ",
            saveGroupSubtitle = "保存ボタンを経ず、セーブファイルを直接読み書き",
            exportTitle = "セーブデータの書き出し",
            exportHint = "すべてのセーブとグローバルセーブを 1 つの zip にまとめる",
            exportBusy = "書き出し中…",
            exportButton = "すべて書き出す",
            importTitle = "セーブデータの読み込み",
            importBusy = "読み込み中…",
            importButton = "セーブファイルを選択",
            modImportIntro = "口上の .ERB/.ERH ファイルまたは zip パッケージ（複数選択可）を選択：ソースは mods に保存され、コンパイルされたパッチはゲーム再起動後に有効になります。",
            modImportPickButton = "口上ファイルを選択",
            modGroupTitle = "Mod と素材",
            modGroupSubtitle = "素材パッチと口上パッチの導入入口",
            resourcePackTitle = "素材アーカイブを導入（任意）",
            resourcePackIntro = "手元に zip / 7z が既にある場合だけ使います。中に resources/ か sound/ の階層が必要です（公式の背景パックは BGI.csv をそのまま選べます）。通常は上の 2 枚のカードから画像・音声・フォルダを直接選んでください。",
            resourcePackButton = "アーカイブを選択",
            resourcePackBusy = "導入中…",
            resourcePackStatusUnavailable = "素材の状態：読み取れません",
            resourcePackStatusBackgroundFormat = "背景画像：{0} 枚 ・ 索引 {1} 行",
            resourcePackStatusBackgroundEmpty = "背景画像：未導入",
            resourcePackStatusBackgroundNoIndexFormat = "背景画像：{0} 枚ありますが索引（CSV）がありません",
            resourcePackStatusBgmFormat = "BGM：{0} 曲 ・ {1} 地点",
            resourcePackStatusBgmEmpty = "BGM：未導入",
            resourcePackImportedFormat = "{1} 個のソースから {0} ファイルを導入（上書き {2}、スキップ {3}）。",
            resourcePackSkippedFormat = "スキップ：{0}",
            resourcePackRestartButton = "再起動で反映",
            diagTitle = "実行診断（突然終了の調査）",
            diagHint = "直近 64 行のメモリ記録と死因判定を 1 つのテキストに書き出します。エラーなしで終了したときは開発者へ送ってください",
            diagBusy = "生成中…",
            diagShareButton = "書き出して共有",
            diagCopyButton = "診断をコピー",
            diagReadyFormat = "{0} を作成しました",
            diagFailed = "生成できませんでした：実行記録が見つかりません",
            diagCopied = "診断をコピーしました。開発者へ貼り付けて送ってください",
            diagCopyFailed = "コピーできませんでした。「書き出して共有」を使ってください",
            diagShareTitle = "実行診断を共有",
            diagShareFailed = "共有シートを開けませんでした。「診断をコピー」を使ってください",
            gameGroupTitle = "ゲーム設定",
            gameGroupSubtitleFormat = "{0} ・ {1} 項目 ・ 編集可",
            debugReadOnlyTitle = "デバッグウィンドウ（debug.config）・読み取り専用",
            replaceReadOnlyTitle = "置換データ（_Replace.csv）・読み取り専用",
            gameReadOnlyTitle = "ゲーム設定 ・ 読み取り専用",
            debugReadOnlyHint = "読み取り専用：debug.config から読み込み、この画面からは書き戻しません",
            replaceReadOnlyHint = "読み取り専用：_Replace.csv から読み込み。原本に設定画面はなく、この画面からは書き戻しません",
            pinnedByFixed = "fixed.config で固定",
            emptyValue = "（空）",
            effectiveValueFormat = "有効値：{0}",
            currentDefaultEffectiveFormat = "現在値：{0} ・ 既定値：{1} ・ 有効値：{2}",
            bottomFootnote = "保存＝書き込み（即時反映項目はすぐ有効、再起動項目は次回起動時に有効）。保存して再起動＝書き込み後に再起動（変更がすべて即時反映項目なら再起動しません）",
            listSeparator = "、",
            okText = "OK",
            cancelText = "キャンセル",
            overwriteTitle = "これらの位置には既にセーブがあります",
            overwriteUnknownNote = "メモを読み取れません",
            overwriteNoNote = "メモなし",
            overwriteLineFormat = "{0} の既存：{1}\n入れるもの：{2}（{3}）",
            overwriteConfirmFormat = "{0} 件を上書き",
            pageEnv = "環境",
            pageDisplay = "表示",
            pageWindow = "ウィンドウ",
            pageFont = "フォント",
            pageSystem = "システム",
            pageSystem2 = "システム（2）",
            pageCompat = "互換性",
            pageParsing = "解析",
            pageClipboard = "クリップボード",
            pageRikai = "ポップアップ翻訳",
            pageAdvanced = "その他（上級）",
            pageOther = "その他",
            pageAdvancedHint = "原本の設定画面にはこれらの項目がありません。CompatiDRAWLINE は原本が書き出さない項目です（読み込み時にリダイレクト）",
            pageRikaiHint = "フォント画面の「背景色 / 文字色」の訳を再利用（原本どおり）",
            rejectUnknown = "不明な項目",
            rejectType = "型が一致しません",
            rejectInvalid = "解析失敗",
            rejectEmpty = "空のテキスト",
            rejectRange = "範囲外",
            rejectFixed = "fixed.config で固定",
            detailUnknownId = "設定項目が見つかりません",
            detailFixed = "この項目は固定されているため変更できません",
            detailTypeMismatch = "設定項目の型が一致しません",
            detailEmpty = "空の値は設定できません",
            detailInvalidSpec = "不正な指定です",
            detailNonNumeric = "数字でない文字が含まれています",
            detailColor = "値をColor指定子として認識できません",
            detailFontTooSmall = "フォントサイズが小さすぎます(8が下限)",
            detailSaveTooFew = "表示するセーブデータ数が少なすぎます(20が下限)",
            detailSaveTooMany = "表示するセーブデータ数が多すぎます(80が上限)",
            detailLogTooSmall = "ログ表示行数が少なすぎます(500が下限)",
            detailRangeFormat = "設定値が範囲外です（{0}）",
            loadErrorFormat = "読み込み失敗：{0}",
            loadAbortedFormat = "{0} 行目の解析に失敗。以降の {1} 行は読み込まれていません",
            loadWarningsFormat = "解析警告 {0} 件（最初：{1}）",
            loadMissing = "emuera.config が見つかりません。既定値を表示しています",
            resetAllDoneFormat = "{0} 項目をリセットしました。アプリ設定はすぐに反映され、ゲーム設定はまだ書き込まれていません（「保存」または「保存して再起動」で書き込まれます）",
            resetAllDonePinnedFormat = "；{0} 項目は fixed.config に固定されているため変更していません",
            saveWriteFailed = "書き込みに失敗しました。元のファイルは変更されていません",
            saveRestartWriteFailed = "書き込みに失敗しました。元のファイルは変更されず、再起動もしません",
            savedHotNoRestartFormat = "{0} 項目を保存して即時反映しました。再起動しません",
            summaryNoChangeFormat = "変更なし。{0} を書き直しました",
            summaryHotRestartFormat = "保存しました：{0} 項目は即時反映、{1} は再起動が必要",
            summaryRestartFormat = "保存しました。{0} は再起動後に反映",
            summaryHotFormat = "{0} 項目を保存して即時反映しました",
            summaryNoSession = "保存しました。実行中のセッションがないため、次回起動時に反映されます",
            moreItemsFormat = "{0} ほか {1} 件",
            rejectToastFormat = "{0}：{1}",
            titleBodyFormat = "{0}：{1}",
            configUnreadableImportFormat = "セーブ先の設定を読み取れません（{0}）。読み込みませんでした",
            importUnreadFormat = "{0} は読み込みません：内容を読めないか、サイズ上限を超えています",
            importRejectedFormat = "{0} は読み込みません：{1}",
            importedFormat = "{0} を読み込みました",
            importedWithNoteFormat = "{0} を読み込みました：{1}",
            importWriteFailedFormat = "{0} を書き込めませんでした（容量不足、またはフォルダに書き込めません）",
            importSkippedOccupiedFormat = "{0} は読み込みません：その位置には既にセーブがあります",
            unexpectedFailure = "処理中にエラーが発生しました",
            selectedFileFallback = "選択したファイル",
            globalSaveLabel = "グローバルセーブ",
            slotFormat = "セーブ {0}",
            noNote = "メモなし",
            configUnreadableExportFormat = "セーブ先の設定を読み取れません（{0}）。書き出しませんでした",
            exportCollectFailed = "書き出し失敗：セーブフォルダを読み取れません",
            exportNothing = "書き出せるセーブデータがありません",
            exportPickerFailed = "システムのファイル選択画面を開けません",
            exportDoneFormat = "{0} 件のセーブを書き出しました{1} → {2}",
            exportIncludingGlobal = "（グローバルセーブを含む）",
            exportFailedAtFormat = "書き出し失敗：{0} を読み取れません（{1} 件書き出し済み）",
            exportFailedGenericFormat = "書き出し失敗（{0} 件書き出し済み）",
            exportSkippedFormat = "{0} をスキップ：{1}",
            reasonOnlySav = "取り込めるのは .sav セーブファイルのみです",
            reasonCorrupted = "セーブデータが壊れています",
            reasonDifferentGame = "異なるゲームのセーブデータです",
            reasonDifferentVersion = "セーブデータのバーションが異なります",
            reasonSlotTooLarge = "セーブ番号が大きすぎます",
            reasonSlotsFull = "セーブ位置がいっぱいで空きがありません",
            reasonTooBigFormat = "ファイルが {0} MB を超えており、セーブデータとは思えません",
            reasonEmptyFile = "空のファイルです",
            reasonOversize = "サイズ上限を超えています",
            zonePortTab = "移植版機能",
            zoneEngineTab = "原版エンジン",
            zoneModTab = "Mod",
            zoneAboutTab = "情報",
            aboutGroupSubtitle = "バージョン ・ 開発者 ・ 連絡先",
            aboutVersionFormat = "現在のバージョン {0}（build {1}）",
            aboutBuildFormat = "ビルド {0}",
            aboutDeveloperCaption = "開発者",
            aboutDeveloperName = "Poboking",
            aboutRepoCaption = "リポジトリ",
            aboutRepoUrl = "https://github.com/Poboking/eratw-pocket",
            aboutRepoButton = "リポジトリを開く",
            aboutRepoFailed = "リンクを開けるアプリがありません",
            aboutFeedbackCaption = "バグ報告グループ（QQ）",
            aboutGroupId = "1130136207",
            aboutCopyGroupButton = "グループ番号をコピー",
            aboutCopiedFormat = "{0} をコピーしました",
            creditsTitle = "クレジットと注意事項",
            creditsHeadGame = "ゲーム本体",
            creditsGameBody = "本アプリが動作させるゲーム本体（画蛇添足版 eraTW）は、画蛇添足開発組が制作・維持しています。本プロジェクトはゲーム内容を一切制作・改変・解説せず、ゲーム内容の著作権は画蛇添足開発組、および各口上・イベント・データの著作者に帰属します。",
            creditsHeadScope = "本プロジェクトの範囲",
            creditsScopeBody = "本プロジェクトが提供するのは、Android ネイティブの era エンジンと Android 互換層のみです。PC 版 Emuera の実行環境を Android へ移すものであり、スクリプトの意味は変更しません。挙動の互換性は Emuera の実際の挙動を基準とします。",
            creditsHeadThanks = "謝辞",
            creditsItemEmuera = "MinorShift —— Emuera の著作者。",
            creditsItemKoujo = "まだ名前は無い人 —— および eraTW に口上・イベント・データ・ドキュメントを提供したすべての創作者。",
            creditsItemCommunity = "era コミュニティの各スクリプト作者、コンテンツ作者、ツール維持者。",
            creditsItemTouhou = "上海アリス幻樂団（ZUN）—— 東方Projectの原作。",
            creditsLiability = "本移植に起因する問題の責任は本プロジェクトが負い、上海アリス幻樂団・画蛇添足開発組および各内容作者には及びません。",
        )

        /**
         * 中文模式下页面标签拿不到 zhs 译文时的 app 侧兜底表（31 条，来自旧设置页）。
         * 只补「原版 zhs 缺译 / 条目不在任何设置页上」这两类；不改 core 的标签表。
         */
        private val ZH_CONFIG_LABELS: Map<ConfigKey, String> = mapOf(
            ConfigKey.SkipFrame to "最大跳帧数",
            ConfigKey.AllowLongInputByMouse to "允许鼠标输入长文本（ONEINPUT 系）",
            ConfigKey.SystemIgnoreStringSet to "字符串变量赋值强制使用字符串表达式",
            ConfigKey.UseLazyLoading to "延迟加载（UseLazyLoading）",
            ConfigKey.ChangeMasterNameIfDebug to "调试命令下更改 MASTER 名称",
            ConfigKey.LastKey to "最终更新代码",
            ConfigKey.CompatiDRAWLINE to "DRAWLINE 始终另起新行",
            ConfigKey.EmueraIcon to "自定义窗口图标路径",
            ConfigKey.CBUseClipboard to "将显示文本复制到剪贴板",
            ConfigKey.Ctrl_Z_Enabled to "启用 Ctrl-Z 撤销",
            ConfigKey.ReplaceContinuationBR to "行连接换行符替换字符串（用半角引号包裹）",
            ConfigKey.CheckDuplicateIdentifier to "检查 ERD 标识符与局部变量重名",
            ConfigKey.RikaiEnabled to "启用弹窗翻译词典（日语→英语）",
            ConfigKey.RikaiFilename to "词典文件路径（相对 Emuera 本体）",
            ConfigKey.RikaiUseSeparateBoxes to "高亮显示翻译中的词句",
            ConfigKey.MoneyLabel to "货币单位",
            ConfigKey.MoneyFirst to "货币单位在前",
            ConfigKey.LoadLabel to "启动时简略显示",
            ConfigKey.MaxShopItem to "贩卖道具数",
            ConfigKey.DrawLineString to "DRAWLINE 字符",
            ConfigKey.BarChar1 to "BAR 字符 1",
            ConfigKey.BarChar2 to "BAR 字符 2",
            ConfigKey.TitleMenuString0 to "系统菜单 0",
            ConfigKey.TitleMenuString1 to "系统菜单 1",
            ConfigKey.ComAbleDefault to "COM_ABLE 初始值",
            ConfigKey.StainDefault to "污渍初始值",
            ConfigKey.TimeupLabel to "时间切显示",
            ConfigKey.ExpLvDef to "EXPLV 初始值",
            ConfigKey.PalamLvDef to "PALAMLV 初始值",
            ConfigKey.pbandDef to "PBAND 初始值",
            ConfigKey.RelationDef to "RELATION 初始值",
        )
    }
}
