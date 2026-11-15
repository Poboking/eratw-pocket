package com.eratw.port.android

import com.eratw.port.core.config.ConfigClampLanguage
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigPageLabels
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.SettingsEntry
import com.eratw.port.core.config.SettingsRejectReason
import com.eratw.port.platform.save.SaveImport

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
 * ## 为什么用类体属性 + [tr] 辅助函数而不是巨型主构造器或分散的 `when (lang)`
 * 1. **编译期三语强约束**：[tr] 的三个形参（`zh` / `en` / `ja`）均无默认值，**漏一种语言编译就不过**；
 * 2. **突破 JVM 255 参数槽位上限**：主构造器仅保留 [lang] 一个参数，每个字段作为独立属性在实例初始化时
 *    求值一次并存入 backing field，运行时零额外开销，也不会触发 `ClassFormatError: Too many arguments`；
 * 3. **三语就近对齐**：同一词条的简中、英文、日文紧挨在一起，修改文案或核对 `{0}` / `{1}` 占位符一目了然；
 * 4. **参数化句式统一**：带参文案统一用 `{0}` / `{1}` 占位，由 [fill] 一次替换。
 *
 * ## 条目标签的来源（不编造）
 * [configLabel] 的三条路各自有出处：zhs 页面译文（缺译回落日文）、日语页面标签、
 * 英文配置键名。原版没有英文页面译文文件，所以英文模式显示配置键本身，而不是自造译文。
 */
class SettingsStrings private constructor(
    val lang: SettingsLanguage,
) {

    /**
     * 三语选择器：在构造 [ZH] / [EN] / [JA] 单例时求值一次，存入对应属性的 backing field。
     * 三个参数均为必填，确保任何新增文案在编译期强制补齐三语。
     */
    private fun tr(zh: String, en: String, ja: String): String = when (lang) {
        SettingsLanguage.ZH -> zh
        SettingsLanguage.EN -> en
        SettingsLanguage.JA -> ja
    }

    // ---- 顶栏 ----
    val back: String = tr(
        zh = "← 返回",
        en = "← Back",
        ja = "← 戻る",
    )
    val title: String = tr(
        zh = "设置",
        en = "Settings",
        ja = "設定",
    )
    val reset: String = tr(
        zh = "重置",
        en = "Reset",
        ja = "リセット",
    )
    val resetAll: String = tr(
        zh = "全部重置",
        en = "Reset all",
        ja = "すべてリセット",
    )
    val save: String = tr(
        zh = "保存",
        en = "Save",
        ja = "保存",
    )
    val saveAndRestart: String = tr(
        zh = "保存并重启",
        en = "Save & restart",
        ja = "保存して再起動",
    )
    val filePrefix: String = tr(
        zh = "文件：",
        en = "File: ",
        ja = "ファイル：",
    )
    val unsavedSuffix: String = tr(
        zh = " · 未保存",
        en = " · Unsaved",
        ja = " · 未保存",
    )

    // ---- 读取提示 ----
    val noticesTitle: String = tr(
        zh = "读取提示",
        en = "Load notices",
        ja = "読み込み通知",
    )

    // ---- 语言卡片 ----
    val languageTitle: String = tr(
        zh = "显示语言",
        en = "Language",
        ja = "表示言語",
    )
    val languageHint: String = tr(
        zh = "只影响设置页，随改随存",
        en = "Applies to this settings page only; saved immediately",
        ja = "この設定画面のみに適用・すぐ保存されます",
    )

    // ---- 应用偏好分组 ----
    val appPrefsTitle: String = tr(
        zh = "应用偏好",
        en = "App preferences",
        ja = "アプリ設定",
    )
    val appPrefsSubtitle: String = tr(
        zh = "只影响安卓壳层 · 随改随存",
        en = "Android shell only · saved immediately",
        ja = "Android シェルのみ · すぐ保存",
    )

    // ---- 恢复默认设置（列表底部卡片，不进顶栏）----
    val restoreDefaultsTitle: String = tr(
        zh = "恢复默认设置",
        en = "Restore defaults",
        ja = "既定値に戻す",
    )
    val restoreDefaultsHint: String = tr(
        zh = "只把原版设置页那批项（emuera.config 里的游戏配置）恢复为本移植版随包出厂值并立即写盘；应用偏好（本页语言、字号档、竖屏模式、右栏、启动画面、更新开关等）、存档、已导入的补丁与素材都不受影响。此操作不可撤销。",
        en = "Restores only the original settings-page items (the game settings in emuera.config) to the factory values shipped with this Android port and writes them to disk immediately. App preferences (page language, text size, portrait mode, option rail, loading screen, update checks and more), saves, installed patches and asset packs are untouched. This cannot be undone.",
        ja = "原版の設定画面にある項目（emuera.config のゲーム設定）だけを、この移植版に同梱された出荷時の値に戻してすぐに書き込みます。アプリ設定（この画面の言語、文字サイズ、縦画面、右バー、起動画面、更新確認など）・セーブデータ・導入済みパッチ・素材は変更しません。この操作は取り消せません。",
    )
    val restoreDefaultsButton: String = tr(
        zh = "恢复默认设置…",
        en = "Restore defaults…",
        ja = "既定値に戻す…",
    )
    val restoreDefaultsConfirmTitle: String = tr(
        zh = "恢复默认设置？",
        en = "Restore default settings?",
        ja = "既定値に戻しますか？",
    )
    val restoreDefaultsConfirmBody: String = tr(
        zh = "emuera.config 里的游戏配置会恢复成本移植版随包出厂值并立即写盘。不会改动应用偏好（本页语言、字号档、竖屏模式、右栏、启动画面、更新开关等），不会切回简体中文，也不会重开本页或返回游戏。存档和已导入的补丁不会被删除。此操作不可撤销。",
        en = "The game settings in emuera.config return to the factory values shipped with this port and are written to disk immediately. App preferences (page language, text size, portrait mode, option rail, loading screen, update checks and more) are not changed, the page language does not switch back, and this page is not reopened or the game restarted. Saves and installed patches are not deleted. This cannot be undone.",
        ja = "emuera.config のゲーム設定を、この移植版に同梱された出荷時の値に戻してすぐに書き込みます。アプリ設定（この画面の言語、文字サイズ、縦画面、右バー、起動画面、更新確認など）は変更せず、言語を簡体字中文に戻すこともしません。この画面の再読み込みやゲームの再起動も行いません。セーブデータと導入済みパッチは削除されません。この操作は取り消せません。",
    )
    val restoreDefaultsConfirmOk: String = tr(
        zh = "恢复默认",
        en = "Restore defaults",
        ja = "既定値に戻す",
    )
    val restoreDefaultsDone: String = tr(
        zh = "已恢复原版游戏配置的出厂默认值并写盘",
        en = "Factory game settings restored and written to disk",
        ja = "原版ゲーム設定を出荷時の既定値に戻して書き込みました",
    )
    /** 有条目被 fixed.config 钉住时拼在成功提示后面：{0} 是没改成的项目数。 */
    val restoreDefaultsPinnedFormat: String = tr(
        zh = "；{0} 项被 fixed.config 钉住，未改动",
        en = "; {0} items were pinned by fixed.config and left unchanged",
        ja = "；{0} 項目は fixed.config に固定されているため変更していません",
    )
    val restoreDefaultsWriteFailed: String = tr(
        zh = "写盘失败，游戏配置未改动",
        en = "Write failed; the game settings were not changed",
        ja = "書き込みに失敗しました。ゲーム設定は変更されていません",
    )

    // ---- 字号与字体 ----
    val fontTitle: String = tr(
        zh = "字号与字体",
        en = "Text size & font",
        ja = "文字サイズとフォント",
    )
    val fontSizeLabel: String = tr(
        zh = "显示大小",
        en = "Display size",
        ja = "表示サイズ",
    )
    val fontFamilyLabel: String = tr(
        zh = "正文字体",
        en = "Body font",
        ja = "本文フォント",
    )
    val coverageFormat: String = tr(
        zh = "字数覆盖 {0}%",
        en = "text coverage {0}%",
        ja = "文字カバー率 {0}%",
    )
    val fontRestartHint: String = tr(
        zh = "改字体/字号/行距需重开游戏生效（写 emuera.config 的 FONT NAME / FONT SIZE / LINE HEIGHT）",
        en = "Restart the game for the new font/size/line height to take effect (writes FONT NAME / FONT SIZE / LINE HEIGHT to emuera.config)",
        ja = "フォント/文字サイズ/行高の変更はゲーム再起動で反映（emuera.config の FONT NAME / FONT SIZE / LINE HEIGHT に書き込み）",
    )
    val fontNoteSarasa: String = tr(
        zh = "更纱黑体等宽 · 思源黑体同源 · OFL-1.1 · 原版黑体替身 · 严格 1:2",
        en = "Sarasa Mono SC · Source Han Sans based · OFL-1.1 · SimHei stand-in · strict 1:2",
        ja = "Sarasa Mono SC · 思源黒体系 · OFL-1.1 · 原本黒体の代替 · 厳密 1:2",
    )
    val fontNoteLxgw: String = tr(
        zh = "霞鹜文楷等宽 · OFL-1.1 · 楷体观感 · 严格 1:2",
        en = "LXGW WenKai Mono · OFL-1.1 · brush-style look · strict 1:2",
        ja = "霞鶩文楷等幅 · OFL-1.1 · 楷書風 · 厳密 1:2",
    )
    val fontNoteMaple: String = tr(
        zh = "Maple Mono CN · OFL-1.1 · 圆润等宽 · 严格 1:2",
        en = "Maple Mono CN · OFL-1.1 · rounded mono look · strict 1:2",
        ja = "Maple Mono CN · OFL-1.1 · 丸みのある等幅 · 厳密 1:2",
    )
    val fontNoteSaitamaar: String = tr(
        zh = "Saitamaar · 公有领域 · 比例字体按 1:2 兜底",
        en = "Saitamaar · public domain · proportional font fitted to the 1:2 grid",
        ja = "Saitamaar · パブリックドメイン · プロポーショナルを 1:2 で補正",
    )
    val fontNoteMisaki: String = tr(
        zh = "美咲ゴシック（eratw 原版观感）· 严格 1:2 · 缺字走随包回落",
        en = "Misaki Gothic (original eratw look) · strict 1:2 · missing glyphs use bundled fallback",
        ja = "美咲ゴシック（eratw 原本の見た目）· 厳密 1:2 · 欠字は同梱フォントで補完",
    )
    val fontNoteFusion: String = tr(
        zh = "缝合像素 12px · OFL-1.1 · 半角 600 / 全角 1200",
        en = "Fusion Pixel 12px · OFL-1.1 · half-width 600 / full-width 1200",
        ja = "Fusion Pixel 12px · OFL-1.1 · 半角 600 / 全角 1200",
    )
    val fontNoteArk: String = tr(
        zh = "方舟像素 12px · OFL-1.1 · 点字（盲文）覆盖更全",
        en = "Ark Pixel 12px · OFL-1.1 · better Braille coverage",
        ja = "Ark Pixel 12px · OFL-1.1 · 点字（ブライユ）のカバーが広い",
    )
    val fontNoteUnifont: String = tr(
        zh = "GNU Unifont 16px · OFL-1.1 · 只差 emoji/私用区",
        en = "GNU Unifont 16px · OFL-1.1 · only emoji and private-use area missing",
        ja = "GNU Unifont 16px · OFL-1.1 · emoji/私用領域のみ欠落",
    )
    val col136Label: String = tr(
        zh = "标准",
        en = "Standard",
        ja = "標準",
    )
    val col136Hint: String = tr(
        zh = "字号 ×1.00（跟随 FONT SIZE）；每行至少 136 列（默认；128 格地图完整）",
        en = "Text ×1.00 (follows FONT SIZE); at least 136 columns (default; 128-cell maps fit on one line)",
        ja = "文字 ×1.00（FONT SIZE に追従）、1 行あたり最低 136 列（既定、128 マスのマップが 1 行に収まる）",
    )
    val col90Label: String = tr(
        zh = "大",
        en = "Large",
        ja = "大",
    )
    val col90Hint: String = tr(
        zh = "字号 ×1.51（约 90 列）；宽地图可能折行",
        en = "Text ×1.51 (about 90 columns); wide maps may wrap",
        ja = "文字 ×1.51（約 90 列）、広いマップは折り返す可能性",
    )
    val col75Label: String = tr(
        zh = "特大",
        en = "Extra large",
        ja = "特大",
    )
    val col75Hint: String = tr(
        zh = "字号 ×1.81（约 75 列）；宽地图会折行",
        en = "Text ×1.81 (about 75 columns); wide maps will wrap",
        ja = "文字 ×1.81（約 75 列）、広いマップは折り返す",
    )
    val col150Label: String = tr(
        zh = "小",
        en = "Small",
        ja = "小",
    )
    val col150Hint: String = tr(
        zh = "字号 ×0.91（约 150 列）；字更小，地图更稳",
        en = "Text ×0.91 (about 150 columns); smaller text, maps safer",
        ja = "文字 ×0.91（約 150 列）、文字は小さくマップはより安全",
    )
    val railSmallLabel: String = tr(
        zh = "小",
        en = "Small",
        ja = "小",
    )
    val railSmallHint: String = tr(
        zh = "右栏最窄，被它盖住的画面最少；标题长的选项会被截断",
        en = "Narrowest rail, covers the least of the screen; long labels get truncated",
        ja = "最も細い右バー。画面をあまり隠さないが、長い見出しは切れる",
    )
    val railNormalLabel: String = tr(
        zh = "标准",
        en = "Standard",
        ja = "標準",
    )
    val railNormalHint: String = tr(
        zh = "默认大小",
        en = "Default size",
        ja = "既定の大きさ",
    )
    val railLargeLabel: String = tr(
        zh = "大",
        en = "Large",
        ja = "大",
    )
    val railLargeHint: String = tr(
        zh = "按钮更高、标题更大；右栏占的画面更多",
        en = "Taller buttons and larger labels; the rail covers more of the screen",
        ja = "ボタンが高く見出しも大きい。右バーが画面を多く占める",
    )
    val railXlargeLabel: String = tr(
        zh = "特大",
        en = "Extra large",
        ja = "特大",
    )
    val railXlargeHint: String = tr(
        zh = "标题最大最好点；右栏几乎盖住半屏，可用右上螺旋图标收起",
        en = "Largest and easiest to tap; the rail covers nearly half the screen — collapse it with the spiral icon at top right",
        ja = "最大で押しやすい。右バーがほぼ半画面を覆うので、右上の渦巻きアイコンで畳める",
    )
    val loadingPixelLabel: String = tr(
        zh = "像素风",
        en = "Pixel",
        ja = "ピクセル",
    )
    val loadingPixelHint: String = tr(
        zh = "方块进度条，复古文字游戏味",
        en = "Blocky progress bar with a retro text-game feel",
        ja = "ブロック型プログレスバー、レトロな味わい",
    )
    val loadingModernLabel: String = tr(
        zh = "现代风",
        en = "Modern",
        ja = "モダン",
    )
    val loadingModernHint: String = tr(
        zh = "深色渐变页，简洁",
        en = "Dark gradient page, clean",
        ja = "暗いグラデーション、シンプル",
    )
    /** 右栏大小预览里的示例选项（文案随界面语言；序号固定）。 */
    val railSampleTalk: String = tr(
        zh = "会话",
        en = "Talk",
        ja = "会話",
    )
    val railSampleMove: String = tr(
        zh = "移动到广场",
        en = "Move to plaza",
        ja = "広場へ移動",
    )
    val railSampleBuy: String = tr(
        zh = "购买[酒]",
        en = "Buy [sake]",
        ja = "購入[酒]",
    )
    val railSampleAttack: String = tr(
        zh = "攻击",
        en = "Attack",
        ja = "攻撃",
    )

    // ---- 操作与显示 ----
    val controlTitle: String = tr(
        zh = "操作与显示",
        en = "Controls & display",
        ja = "操作と表示",
    )
    val railSizeLabel: String = tr(
        zh = "选项按钮大小",
        en = "Option button size",
        ja = "選択肢ボタンの大きさ",
    )
    val railFootnote: String = tr(
        zh = "右栏随时可用右上角的螺旋图标收起；改大小不需要重开游戏",
        en = "Collapse the rail anytime with the spiral icon at top right; changing the size needs no restart",
        ja = "右バーは右上の渦巻きアイコンでいつでも畳めます。大きさの変更に再起動は不要",
    )
    val knobLabel: String = tr(
        zh = "底边抓手位置",
        en = "Bottom handle position",
        ja = "下端ハンドルの位置",
    )
    val knobLeft: String = tr(
        zh = "靠左",
        en = "Left",
        ja = "左",
    )
    val knobCenter: String = tr(
        zh = "居中",
        en = "Center",
        ja = "中央",
    )
    val knobRight: String = tr(
        zh = "靠右",
        en = "Right",
        ja = "右",
    )
    val screenFitTitle: String = tr(
        zh = "保证地图列数（列数守护）",
        en = "Keep map columns (column guard)",
        ja = "マップ列数を確保（列数ガード）",
    )
    val screenFitHint: String = tr(
        zh = "开：屏宽不足所选列数时压小字号，保证 128 格地图行不折行；关：字号优先，列数随字号走（宽地图可能折行）。真正调字号请到「字体」页改 FONT SIZE。",
        en = "On: shrink the font when the screen cannot fit the selected column count, so 128-cell maps never wrap; Off: the font wins and columns follow it (wide maps may wrap). To change text size, edit FONT SIZE on the Font page.",
        ja = "オン：画面が選択列数に満たないとき文字サイズを下げ、128 マスのマップ行を折り返さない。オフ：文字サイズ優先で列数が決まる（広いマップは折り返す）。文字サイズ自体は「フォント」ページの FONT SIZE で変更する。",
    )
    val portraitTitle: String = tr(
        zh = "竖屏模式",
        en = "Portrait mode",
        ja = "縦画面モード",
    )
    val portraitHint: String = tr(
        zh = "开：游戏按竖屏版面显示（单手握持）；关：锁定横屏，与原版版面一致（默认）。切换后需重开游戏生效。",
        en = "On: play in the portrait layout (one-handed use); Off: lock to landscape like the original (default). Takes effect after the game restarts.",
        ja = "オン：縦画面レイアウトで表示（片手持ち向け）。オフ：横画面に固定し原本と同じ表示（既定）。切り替えはゲームの再起動後に反映される。",
    )

    /**
     * 竖屏字号档（竖屏三区壳设计稿 §5.2：50 特大 / 62 大·缺省 / 75 标准 / 90 小）。
     *
     * 档位值本身只在 [UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT]（唯一权威），这里只放**玩家可见**
     * 的档名；[portraitFontColumnLabels] 按档位表顺序与那张表一一对齐（形状闸逐档点名，
     * 档位表改了那里先红）。文案一律走本表三语，不在设置页内联字面量（AGENTS §6.1）。
     */
    val portraitFontTitle: String = tr(
        zh = "竖屏字号",
        en = "Portrait text size",
        ja = "縦画面の文字サイズ",
    )
    val portraitFontHint: String = tr(
        zh = "仅竖屏模式生效：决定竖屏版面一行放多少列；改这一项后要重开一局才生效。",
        en = "Only used in portrait mode: sets how many columns the portrait layout fits per " +
            "line. Takes effect after the game restarts.",
        ja = "縦画面モード時のみ有効です。縦画面レイアウトの 1 行あたりの列数を決めます。" +
            "変更後はゲームの再起動が必要です。",
    )
    val portraitColExtraLargeLabel: String = tr(
        zh = "特大 · 约 50 列",
        en = "Extra large · ~50 columns",
        ja = "特大・約 50 列",
    )
    val portraitColLargeLabel: String = tr(
        zh = "大 · 约 62 列（默认）",
        en = "Large · ~62 columns (default)",
        ja = "大・約 62 列（既定）",
    )
    val portraitColStandardLabel: String = tr(
        zh = "标准 · 约 75 列",
        en = "Standard · ~75 columns",
        ja = "標準・約 75 列",
    )
    val portraitColSmallLabel: String = tr(
        zh = "小 · 约 90 列",
        en = "Small · ~90 columns",
        ja = "小・約 90 列",
    )

    /**
     * 竖屏档名映射：键**取自** [UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT]（不另抄一份 50/62/75/90），
     * 值顺序与上面四个档名属性一致。`SettingsStringsTest` 的竖屏族用例逐档点名这张对齐关系。
     */
    private val portraitFontColumnLabels: Map<Int, String> =
        UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { it.columns }
            .zip(
                listOf(
                    portraitColExtraLargeLabel,
                    portraitColLargeLabel,
                    portraitColStandardLabel,
                    portraitColSmallLabel,
                ),
            )
            .toMap()

    /** 「窗口宽窄变化时重新排版」开关（LIVE-REFLOW，默认关）。 */
    val liveReflowTitle: String = tr(
        zh = "窗口宽窄变化时重新排版",
        en = "Reflow text when the window resizes",
        ja = "ウィンドウ幅の変化で文字を組み直す",
    )
    val liveReflowHint: String = tr(
        zh = "默认关。开：转屏、分屏、弹出键盘、折叠屏展开等让窗口变宽或变窄时，游戏里显示过的文字会按新宽度重新排版，横屏也一样有效。改这一项后要重开一局才生效。",
        en = "Off by default. On: when the window grows or shrinks (rotation, split screen, on-screen keyboard, unfolding a foldable), text already shown is laid out again to the new width; this applies in landscape too. Takes effect after the game restarts.",
        ja = "既定はオフ。オン：画面回転・分割画面・キーボード表示・折りたたみ展開などで幅が変わると、表示済みの文章を新しい幅で組み直します（横画面でも有効）。変更後はゲームの再起動が必要です。",
    )

    /** 「画面左右留白居中」档位（圆屏适配，默认关；存储见 UiPrefs.contentMarginFraction）。 */
    val contentMarginLabel: String = tr(
        zh = "画面左右留白居中",
        en = "Screen side margins (center content)",
        ja = "画面左右の余白中央寄せ",
    )
    val contentMarginHint: String = tr(
        zh = "默认关。开：画面左右各留一段黑边、游戏内容居中显示，圆屏/圆角屏也能看清边缘的文字；" +
            "留白越大字号会相应变小（列数和地图始终完整）。改这一项后要重开一局才生效。",
        en = "Off by default. On: black margins are kept on both sides and the game content is " +
            "centered, so text near the edges stays readable on round / rounded-corner screens; " +
            "larger margins make the font slightly smaller (column count and maps always stay " +
            "intact). Takes effect after the game restarts.",
        ja = "既定はオフ。オン：画面の左右に黒い余白を残して内容を中央に寄せ、" +
            "丸型・角丸画面でも端の文字が見やすくなります。余白が広いほど文字は少し小さくなります" +
            "（列数とマップは常に完全）。変更後はゲームの再起動が必要です。",
    )
    val contentMarginOff: String = tr(
        zh = "关",
        en = "Off",
        ja = "オフ",
    )
    val contentMarginSmall: String = tr(
        zh = "小",
        en = "Small",
        ja = "小",
    )
    val contentMarginMedium: String = tr(
        zh = "中",
        en = "Medium",
        ja = "中",
    )
    val contentMarginLarge: String = tr(
        zh = "大",
        en = "Large",
        ja = "大",
    )

    // ---- 启动 ----
    val launchTitle: String = tr(
        zh = "启动",
        en = "Startup",
        ja = "起動",
    )
    val loadingStyleLabel: String = tr(
        zh = "启动画面样式",
        en = "Loading screen style",
        ja = "起動画面のスタイル",
    )
    val badAppleTitle: String = tr(
        zh = "屏蔽标题彩蛋（BADAPPLE）",
        en = "Skip title easter egg (BADAPPLE)",
        ja = "タイトルのおまけ（BADAPPLE）を無効化",
    )
    val badAppleHint: String = tr(
        zh = "开：标题画面跳过那段约 105 秒的黑白动画（默认）；关：照原样播放",
        en = "On: skip the ~105-second black-and-white animation on the title screen (default); Off: play it as-is",
        ja = "オン：タイトル画面の約 105 秒のモノクロ動画をスキップ（既定）。オフ：そのまま再生",
    )
    val autoUpdateTitle: String = tr(
        zh = "自动检查更新",
        en = "Check for updates automatically",
        ja = "自動で更新を確認",
    )
    val autoUpdateHint: String = tr(
        zh = "开：进游戏后自动查一次新版，有更新会先弹窗问你要不要（默认）；关：只在这个页面手动查",
        en = "On: check once after launch and always ask before downloading anything (default); Off: manual checks only from this page",
        ja = "オン：起動後に一度確認し、更新があってもダウンロード前に必ず聞きます（既定）。オフ：このページで手動確認のみ",
    )
    /** 仅 Wi-Fi 下载（U5 P2-10）：默认关 = 保持既有蜂窝二次确认；开 = 蜂窝不给下载。 */
    val wifiOnlyTitle: String = tr(
        zh = "仅 Wi-Fi 下载",
        en = "Download over Wi-Fi only",
        ja = "Wi-Fi のみでダウンロード",
    )
    val wifiOnlyHint: String = tr(
        zh = "默认关。开：用手机流量时点下载会直接说明「已设为仅 Wi-Fi」，不会开始下载，也不给「仍要下载」；关：走原来的二次确认（默认）",
        en = "Off by default. On: tapping Download on mobile data simply says it is set to Wi-Fi only, with no way to override it here; Off: the usual confirmation is asked (default)",
        ja = "既定はオフ。オン：モバイルデータで「ダウンロード」を押すと「Wi-Fi のみに設定済み」と案内し、ここから取得を続けることはできません。オフ：従来どおり確認します（既定）",
    )
    val checkUpdateButton: String = tr(
        zh = "立即检查更新",
        en = "Check for updates now",
        ja = "いま更新を確認",
    )

    // ---- 更新「跳过此版本」（U-E，契约 §1.5：只对同一个 versionCode 生效，设置页给恢复口） ----
    val updateSkippedLineFormat: String = tr(
        zh = "已跳过版本 {0} 的更新提醒；出现更新的版本时仍会提醒（「立即检查更新」也会照常报它）",
        en = "Update reminders for version {0} are silenced; newer versions still remind you (\"Check for updates now\" reports it regardless)",
        ja = "バージョン {0} の更新通知をスキップ中です。新しいバージョンでは引き続き通知します（「いま更新を確認」はスキップに関係なく動作します）",
    )
    val updateRestoreButton: String = tr(
        zh = "恢复更新提醒",
        en = "Restore update reminders",
        ja = "更新通知を復元",
    )
    val updateRestoreDone: String = tr(
        zh = "已恢复：下次检查到这个版本还会提醒",
        en = "Restored: this version will remind you again on the next check",
        ja = "復元しました。次の確認でこのバージョンを再び通知します",
    )

    // ---- 高级 ----
    val advancedTitle: String = tr(
        zh = "高级",
        en = "Advanced",
        ja = "上級",
    )
    val rawKeysTitle: String = tr(
        zh = "显示原始配置键名",
        en = "Show raw config keys",
        ja = "設定キー名をそのまま表示",
    )
    val rawKeysHint: String = tr(
        zh = "按 emuera.config 里的英文键名显示每一项",
        en = "Show each item by its English key name in emuera.config",
        ja = "emuera.config の英語キー名で各項目を表示",
    )
    val prefsFootnote: String = tr(
        zh = "以上偏好随改随存，不写入 emuera.config",
        en = "These preferences are saved immediately and are not written to emuera.config",
        ja = "これらの設定はすぐ保存され、emuera.config には書き込まれません",
    )

    // ---- 导出 / 导入卡片 ----
    /** 存档分组的组头（这两张卡不经「保存」按钮，与上面的壳层偏好分开）。 */
    val saveGroupTitle: String = tr(
        zh = "存档",
        en = "Saves",
        ja = "セーブデータ",
    )
    val saveGroupSubtitle: String = tr(
        zh = "不经「保存」按钮，直接读写存档文件",
        en = "Works directly on the save files, not through Save",
        ja = "保存ボタンを経ず、セーブファイルを直接読み書き",
    )
    val exportTitle: String = tr(
        zh = "导出存档",
        en = "Export saves",
        ja = "セーブデータの書き出し",
    )
    val exportHint: String = tr(
        zh = "把全部存档连同全局存档打包成一个 zip",
        en = "Pack all saves and the global save into one zip",
        ja = "すべてのセーブとグローバルセーブを 1 つの zip にまとめる",
    )
    val exportBusy: String = tr(
        zh = "正在导出…",
        en = "Exporting…",
        ja = "書き出し中…",
    )
    val exportButton: String = tr(
        zh = "一键导出存档",
        en = "Export all saves",
        ja = "すべて書き出す",
    )
    val importTitle: String = tr(
        zh = "导入已有存档",
        en = "Import saves",
        ja = "セーブデータの読み込み",
    )
    val importBusy: String = tr(
        zh = "正在导入…",
        en = "Importing…",
        ja = "読み込み中…",
    )
    val importButton: String = tr(
        zh = "选择存档文件",
        en = "Choose save files",
        ja = "セーブファイルを選択",
    )

    // ---- 补丁（MOD）导入卡（设置页入口卡与 ModImportChrome 覆盖层共用同一条说明；
    //      2026-11 起接三语：此前固定中文，与存档导入卡的键同机制）----
    val modImportIntro: String = tr(
        zh = "选择口上 .ERB/.ERH 文件或整个 zip 包（可多选）：源文件保存进 mods，编译出的补丁重启游戏后生效。",
        en = "Pick .ERB/.ERH script files or a whole zip pack (multiple selection): sources are saved into mods and the compiled patch takes effect after restarting the game.",
        ja = "口上の .ERB/.ERH ファイルまたは zip パッケージ（複数選択可）を選択：ソースは mods に保存され、コンパイルされたパッチはゲーム再起動後に有効になります。",
    )
    val modImportPickButton: String = tr(
        zh = "选择口上文件",
        en = "Choose script files",
        ja = "口上ファイルを選択",
    )

    // ---- Mod 档（自备素材补丁的资源导入）----
    val modGroupTitle: String = tr(
        zh = "Mod 与素材",
        en = "Mods & assets",
        ja = "Mod と素材",
    )
    val modGroupSubtitle: String = tr(
        zh = "自备素材补丁（背景图、BGM）与口上补丁的导入入口",
        en = "Asset packs and script patches",
        ja = "素材パッチと口上パッチの導入入口",
    )
    val resourcePackTitle: String = tr(
        zh = "导入素材压缩包（可选）",
        en = "Import an asset archive (optional)",
        ja = "素材アーカイブを導入（任意）",
    )
    val resourcePackIntro: String = tr(
        zh = "只有手里是现成 zip / 7z 时才用这里：包内要保留 resources/ 或 sound/ 层（官方背景图包 BGI.csv + 00..11/通用 可直接选）。平时请用上面两张卡直接选图片、音频或文件夹。",
        en = "Only for ready-made zip / 7z archives that keep the resources/ or sound/ layer (the official background pack with BGI.csv works as is). Normally use the two cards above to pick images, audio or folders directly.",
        ja = "手元に zip / 7z が既にある場合だけ使います。中に resources/ か sound/ の階層が必要です（公式の背景パックは BGI.csv をそのまま選べます）。通常は上の 2 枚のカードから画像・音声・フォルダを直接選んでください。",
    )
    val resourcePackButton: String = tr(
        zh = "选择压缩包",
        en = "Choose archive",
        ja = "アーカイブを選択",
    )
    val resourcePackBusy: String = tr(
        zh = "正在导入…",
        en = "Importing…",
        ja = "導入中…",
    )
    val resourcePackStatusUnavailable: String = tr(
        zh = "资源现状：读取失败",
        en = "Asset status: unreadable",
        ja = "素材の状態：読み取れません",
    )
    val resourcePackStatusBackgroundFormat: String = tr(
        zh = "背景图：{0} 张 · 索引 {1} 行",
        en = "Backgrounds: {0} images · {1} index rows",
        ja = "背景画像：{0} 枚 ・ 索引 {1} 行",
    )
    val resourcePackStatusBackgroundEmpty: String = tr(
        zh = "背景图：未导入",
        en = "Backgrounds: not imported",
        ja = "背景画像：未導入",
    )
    val resourcePackStatusBackgroundNoIndexFormat: String = tr(
        zh = "背景图：有 {0} 张图，但没有索引（CSV）",
        en = "Backgrounds: {0} images but no index (CSV)",
        ja = "背景画像：{0} 枚ありますが索引（CSV）がありません",
    )
    val resourcePackStatusBgmFormat: String = tr(
        zh = "BGM：{0} 首 · {1} 个地点",
        en = "BGM: {0} tracks · {1} places",
        ja = "BGM：{0} 曲 ・ {1} 地点",
    )
    val resourcePackStatusBgmEmpty: String = tr(
        zh = "BGM：未导入",
        en = "BGM: not imported",
        ja = "BGM：未導入",
    )
    val resourcePackImportedFormat: String = tr(
        zh = "已导入 {0} 个文件（来源 {1} 个）；覆盖 {2} 个，跳过 {3} 项。",
        en = "Imported {0} files from {1} source(s); {2} overwritten, {3} skipped.",
        ja = "{1} 個のソースから {0} ファイルを導入（上書き {2}、スキップ {3}）。",
    )
    val resourcePackSkippedFormat: String = tr(
        zh = "跳过：{0}",
        en = "Skipped: {0}",
        ja = "スキップ：{0}",
    )
    val resourcePackRestartButton: String = tr(
        zh = "重启游戏生效",
        en = "Restart to apply",
        ja = "再起動で反映",
    )

    // ---- 运行诊断卡片（闪退但没有任何报告的一类）----
    val diagTitle: String = tr(
        zh = "运行诊断（闪退排查）",
        en = "Runtime diagnostics (crash triage)",
        ja = "実行診断（突然終了の調査）",
    )
    val diagHint: String = tr(
        zh = "把最近 64 行内存轨迹连同死因判读导出成一个文本；游戏突然退出又没有任何报错时，请把它发给开发者",
        en = "Export the last 64 memory-trail lines with a death-mode verdict; send it to the developer when the game closes with no error",
        ja = "直近 64 行のメモリ記録と死因判定を 1 つのテキストに書き出します。エラーなしで終了したときは開発者へ送ってください",
    )
    val diagBusy: String = tr(
        zh = "正在生成…",
        en = "Generating…",
        ja = "生成中…",
    )
    val diagShareButton: String = tr(
        zh = "导出并分享诊断",
        en = "Export and share",
        ja = "書き出して共有",
    )
    val diagCopyButton: String = tr(
        zh = "复制诊断",
        en = "Copy diagnostics",
        ja = "診断をコピー",
    )
    val diagReadyFormat: String = tr(
        zh = "已生成 {0}",
        en = "Created {0}",
        ja = "{0} を作成しました",
    )
    val diagFailed: String = tr(
        zh = "生成失败：读不到运行记录",
        en = "Failed: no runtime record found",
        ja = "生成できませんでした：実行記録が見つかりません",
    )
    val diagCopied: String = tr(
        zh = "诊断已复制，粘贴发给开发者即可",
        en = "Diagnostics copied — paste it to the developer",
        ja = "診断をコピーしました。開発者へ貼り付けて送ってください",
    )
    val diagCopyFailed: String = tr(
        zh = "复制失败，请改用「导出并分享诊断」",
        en = "Copy failed; use \"Export and share\" instead",
        ja = "コピーできませんでした。「書き出して共有」を使ってください",
    )
    val diagShareTitle: String = tr(
        zh = "分享运行诊断",
        en = "Share runtime diagnostics",
        ja = "実行診断を共有",
    )
    val diagShareFailed: String = tr(
        zh = "分享面板启动失败，请改用「复制诊断」",
        en = "Share sheet failed; use \"Copy diagnostics\" instead",
        ja = "共有シートを開けませんでした。「診断をコピー」を使ってください",
    )

    // ---- 分组与只读卡 ----
    val gameGroupTitle: String = tr(
        zh = "游戏配置",
        en = "Game settings",
        ja = "ゲーム設定",
    )
    val gameGroupSubtitleFormat: String = tr(
        zh = "{0} · {1} 项 · 可编辑",
        en = "{0} · {1} items · editable",
        ja = "{0} ・ {1} 項目 ・ 編集可",
    )
    val debugReadOnlyTitle: String = tr(
        zh = "调试窗口（debug.config）· 只读",
        en = "Debug window (debug.config) · read-only",
        ja = "デバッグウィンドウ（debug.config）・読み取り専用",
    )
    val replaceReadOnlyTitle: String = tr(
        zh = "替换数据（_Replace.csv）· 只读",
        en = "Replacement data (_Replace.csv) · read-only",
        ja = "置換データ（_Replace.csv）・読み取り専用",
    )
    val gameReadOnlyTitle: String = tr(
        zh = "游戏配置 · 只读",
        en = "Game settings · read-only",
        ja = "ゲーム設定 ・ 読み取り専用",
    )
    val debugReadOnlyHint: String = tr(
        zh = "只读：原版由 debug.config 读取，本页不写回",
        en = "Read-only: read from debug.config; this page never writes back",
        ja = "読み取り専用：debug.config から読み込み、この画面からは書き戻しません",
    )
    val replaceReadOnlyHint: String = tr(
        zh = "只读：原版由 _Replace.csv 读取，没有设置页，本页不写回",
        en = "Read-only: read from _Replace.csv; the original has no settings page, and this page never writes back",
        ja = "読み取り専用：_Replace.csv から読み込み。原本に設定画面はなく、この画面からは書き戻しません",
    )

    // ---- 条目行 ----
    val pinnedByFixed: String = tr(
        zh = "已被 fixed.config 钉住",
        en = "Pinned by fixed.config",
        ja = "fixed.config で固定",
    )
    val emptyValue: String = tr(
        zh = "（空）",
        en = "(empty)",
        ja = "（空）",
    )
    val effectiveValueFormat: String = tr(
        zh = "生效值：{0}",
        en = "Effective: {0}",
        ja = "有効値：{0}",
    )
    val currentDefaultEffectiveFormat: String = tr(
        zh = "当前值：{0} · 默认值：{1} · 生效值：{2}",
        en = "Current: {0} · Default: {1} · Effective: {2}",
        ja = "現在値：{0} ・ 既定値：{1} ・ 有効値：{2}",
    )

    // ---- 底部 ----
    val bottomFootnote: String = tr(
        zh = "保存＝写盘（热更项立即生效，重启项下次进入游戏生效）；保存并重启＝写盘后重开（本次改动全是热更项时不重启）",
        en = "Save = write to disk (hot items apply now; restart items apply next launch); Save & restart = write, then relaunch (skipped when every change is a hot item)",
        ja = "保存＝書き込み（即時反映項目はすぐ有効、再起動項目は次回起動時に有効）。保存して再起動＝書き込み後に再起動（変更がすべて即時反映項目なら再起動しません）",
    )
    /** 并列清单的分隔符（重启键清单用）。 */
    val listSeparator: String = tr(
        zh = "、",
        en = ", ",
        ja = "、",
    )

    // ---- 对话框 ----
    val okText: String = tr(
        zh = "确定",
        en = "OK",
        ja = "OK",
    )
    val cancelText: String = tr(
        zh = "取消",
        en = "Cancel",
        ja = "キャンセル",
    )
    val overwriteTitle: String = tr(
        zh = "这些位置已经有存档",
        en = "These slots already contain saves",
        ja = "これらの位置には既にセーブがあります",
    )
    val overwriteUnknownNote: String = tr(
        zh = "读不出备注",
        en = "no readable note",
        ja = "メモを読み取れません",
    )
    val overwriteNoNote: String = tr(
        zh = "无备注",
        en = "no note",
        ja = "メモなし",
    )
    val overwriteLineFormat: String = tr(
        zh = "{0} 已有：{1}\n要放进来的：{2}（{3}）",
        en = "{0} already has: {1}\nIncoming: {2} ({3})",
        ja = "{0} の既存：{1}\n入れるもの：{2}（{3}）",
    )
    val overwriteConfirmFormat: String = tr(
        zh = "覆盖这 {0} 格",
        en = "Overwrite {0} slots",
        ja = "{0} 件を上書き",
    )

    // ---- 分页名 ----
    val pageEnv: String = tr(
        zh = "环境",
        en = "Environment",
        ja = "環境",
    )
    val pageDisplay: String = tr(
        zh = "显示",
        en = "Display",
        ja = "表示",
    )
    val pageWindow: String = tr(
        zh = "窗口",
        en = "Window",
        ja = "ウィンドウ",
    )
    val pageFont: String = tr(
        zh = "字体",
        en = "Font",
        ja = "フォント",
    )
    val pageSystem: String = tr(
        zh = "系统",
        en = "System",
        ja = "システム",
    )
    val pageSystem2: String = tr(
        zh = "系统（二）",
        en = "System (2)",
        ja = "システム（2）",
    )
    val pageCompat: String = tr(
        zh = "兼容性",
        en = "Compatibility",
        ja = "互換性",
    )
    val pageParsing: String = tr(
        zh = "解析",
        en = "Parsing",
        ja = "解析",
    )
    val pageClipboard: String = tr(
        zh = "剪贴板",
        en = "Clipboard",
        ja = "クリップボード",
    )
    val pageRikai: String = tr(
        zh = "弹窗翻译",
        en = "Popup translation",
        ja = "ポップアップ翻訳",
    )
    val pageAdvanced: String = tr(
        zh = "其他（高级）",
        en = "Other (advanced)",
        ja = "その他（上級）",
    )
    val pageOther: String = tr(
        zh = "其他",
        en = "Other",
        ja = "その他",
    )
    val pageAdvancedHint: String = tr(
        zh = "原版设置页没有列出这些项；其中 CompatiDRAWLINE 在原版永不写出（读取时被重定向）",
        en = "The original settings dialog does not list these items; CompatiDRAWLINE is never written by the original (it is redirected when read)",
        ja = "原本の設定画面にはこれらの項目がありません。CompatiDRAWLINE は原本が書き出さない項目です（読み込み時にリダイレクト）",
    )
    val pageRikaiHint: String = tr(
        zh = "复用字体页的「背景颜色 / 文本颜色」译文（原版如此）",
        en = "Reuses the Font page's \"Background color / Text color\" labels (as in the original)",
        ja = "フォント画面の「背景色 / 文字色」の訳を再利用（原本どおり）",
    )

    // ---- 拒绝原因 ----
    val rejectUnknown: String = tr(
        zh = "未知条目",
        en = "Unknown item",
        ja = "不明な項目",
    )
    val rejectType: String = tr(
        zh = "类型不符",
        en = "Type mismatch",
        ja = "型が一致しません",
    )
    val rejectInvalid: String = tr(
        zh = "解析失败",
        en = "Parse error",
        ja = "解析失敗",
    )
    val rejectEmpty: String = tr(
        zh = "空文本",
        en = "Empty text",
        ja = "空のテキスト",
    )
    val rejectRange: String = tr(
        zh = "超出范围",
        en = "Out of range",
        ja = "範囲外",
    )
    val rejectFixed: String = tr(
        zh = "已被 fixed.config 钉住",
        en = "Pinned by fixed.config",
        ja = "fixed.config で固定",
    )

    // ---- 拒因明细（core 给的日文原话，已知的按语言重述；未知原样保留） ----
    val detailUnknownId: String = tr(
        zh = "找不到这个设置项",
        en = "Setting item not found",
        ja = "設定項目が見つかりません",
    )
    val detailFixed: String = tr(
        zh = "该项已被固定，无法修改",
        en = "This item is fixed and cannot be changed",
        ja = "この項目は固定されているため変更できません",
    )
    val detailTypeMismatch: String = tr(
        zh = "设置项的类型不匹配",
        en = "Setting type mismatch",
        ja = "設定項目の型が一致しません",
    )
    val detailEmpty: String = tr(
        zh = "不能设置为空值",
        en = "An empty value cannot be set",
        ja = "空の値は設定できません",
    )
    val detailInvalidSpec: String = tr(
        zh = "非法的值",
        en = "Invalid value",
        ja = "不正な指定です",
    )
    val detailNonNumeric: String = tr(
        zh = "包含非数字的文字",
        en = "Contains non-numeric characters",
        ja = "数字でない文字が含まれています",
    )
    val detailColor: String = tr(
        zh = "无法被解析为颜色名的值",
        en = "Value is not a recognized color specifier",
        ja = "値をColor指定子として認識できません",
    )
    val detailFontTooSmall: String = tr(
        zh = "字体大小过小(下限为8)",
        en = "Font size is too small (minimum 8)",
        ja = "フォントサイズが小さすぎます(8が下限)",
    )
    val detailSaveTooFew: String = tr(
        zh = "显示的存档数据数量过小(下限为20)",
        en = "Save data count is too small (minimum 20)",
        ja = "表示するセーブデータ数が少なすぎます(20が下限)",
    )
    val detailSaveTooMany: String = tr(
        zh = "显示的存档数据数量过大(上限为80)",
        en = "Save data count is too large (maximum 80)",
        ja = "表示するセーブデータ数が多すぎます(80が上限)",
    )
    val detailLogTooSmall: String = tr(
        zh = "日志显示行数过少(下限为500)",
        en = "Log size is too small (minimum 500)",
        ja = "ログ表示行数が少なすぎます(500が下限)",
    )
    val detailRangeFormat: String = tr(
        zh = "设置值超出范围（{0}）",
        en = "Value out of range ({0})",
        ja = "設定値が範囲外です（{0}）",
    )

    // ---- 读取提示句式 ----
    val loadErrorFormat: String = tr(
        zh = "读取失败：{0}",
        en = "Load failed: {0}",
        ja = "読み込み失敗：{0}",
    )
    val loadAbortedFormat: String = tr(
        zh = "第 {0} 行解析失败，之后 {1} 行未读",
        en = "Parse failed at line {0}; {1} lines were not read",
        ja = "{0} 行目の解析に失敗。以降の {1} 行は読み込まれていません",
    )
    val loadWarningsFormat: String = tr(
        zh = "解析告警 {0} 条（首条：{1}）",
        en = "{0} parse warnings (first: {1})",
        ja = "解析警告 {0} 件（最初：{1}）",
    )
    val loadMissing: String = tr(
        zh = "未找到 emuera.config，当前显示默认值",
        en = "emuera.config not found; showing defaults",
        ja = "emuera.config が見つかりません。既定値を表示しています",
    )

    // ---- 保存与 Toast ----
    /** 顶栏「全部重置」成功提示：{0} 是写回模型的条目数；要说明「偏好即时生效、配置未写盘」。 */
    val resetAllDoneFormat: String = tr(
        zh = "已重置 {0} 项：应用偏好已即时生效，游戏配置尚未写盘（点「保存」或「保存并重启」后才写入）",
        en = "Reset {0} settings: app preferences took effect immediately; the game settings are not written yet (use Save or Save & Restart to write them)",
        ja = "{0} 項目をリセットしました。アプリ設定はすぐに反映され、ゲーム設定はまだ書き込まれていません（「保存」または「保存して再起動」で書き込まれます）",
    )
    /** 有条目被 fixed.config 钉住时拼在「全部重置」提示后面：{0} 是没改成的项目数。 */
    val resetAllDonePinnedFormat: String = tr(
        zh = "；{0} 项被 fixed.config 钉住，未改动",
        en = "; {0} items were pinned by fixed.config and left unchanged",
        ja = "；{0} 項目は fixed.config に固定されているため変更していません",
    )
    val saveWriteFailed: String = tr(
        zh = "写盘失败，原文件未改动",
        en = "Write failed; the file was not changed",
        ja = "書き込みに失敗しました。元のファイルは変更されていません",
    )
    val saveRestartWriteFailed: String = tr(
        zh = "写盘失败，原文件未改动，未重启",
        en = "Write failed; the file was not changed and the game was not restarted",
        ja = "書き込みに失敗しました。元のファイルは変更されず、再起動もしません",
    )
    val savedHotNoRestartFormat: String = tr(
        zh = "已写入并热更 {0} 项，立即生效，未重启",
        en = "Saved and hot-applied {0} items; effective now, no restart",
        ja = "{0} 項目を保存して即時反映しました。再起動しません",
    )
    val summaryNoChangeFormat: String = tr(
        zh = "没有改动，已重写 {0}",
        en = "No changes; rewrote {0}",
        ja = "変更なし。{0} を書き直しました",
    )
    val summaryHotRestartFormat: String = tr(
        zh = "已写入：热更 {0} 项已生效；{1} 需重启",
        en = "Saved: {0} items hot-applied; {1} need a restart",
        ja = "保存しました：{0} 項目は即時反映、{1} は再起動が必要",
    )
    val summaryRestartFormat: String = tr(
        zh = "已写入；{0} 需重启生效",
        en = "Saved; {0} take effect after restart",
        ja = "保存しました。{0} は再起動後に反映",
    )
    val summaryHotFormat: String = tr(
        zh = "已写入并热更 {0} 项，立即生效",
        en = "Saved and hot-applied {0} items; effective now",
        ja = "{0} 項目を保存して即時反映しました",
    )
    val summaryNoSession: String = tr(
        zh = "已写入；当前没有在跑的会话，下次进入游戏生效",
        en = "Saved; no running session — effective next launch",
        ja = "保存しました。実行中のセッションがないため、次回起動時に反映されます",
    )
    val moreItemsFormat: String = tr(
        zh = "{0} 等 {1} 项",
        en = "{0} and {1} more",
        ja = "{0} ほか {1} 件",
    )
    val rejectToastFormat: String = tr(
        zh = "{0}：{1}",
        en = "{0}: {1}",
        ja = "{0}：{1}",
    )
    val titleBodyFormat: String = tr(
        zh = "{0}：{1}",
        en = "{0}: {1}",
        ja = "{0}：{1}",
    )

    // ---- 导入结果 ----
    val configUnreadableImportFormat: String = tr(
        zh = "读不到存档目录的口径（{0}），未导入",
        en = "Could not read the save folder settings ({0}); nothing was imported",
        ja = "セーブ先の設定を読み取れません（{0}）。読み込みませんでした",
    )
    val importUnreadFormat: String = tr(
        zh = "未导入 {0}：读不到内容，或它超过体积上限",
        en = "Not imported {0}: unreadable, or larger than the size limit",
        ja = "{0} は読み込みません：内容を読めないか、サイズ上限を超えています",
    )
    val importRejectedFormat: String = tr(
        zh = "未导入 {0}：{1}",
        en = "Not imported {0}: {1}",
        ja = "{0} は読み込みません：{1}",
    )
    val importedFormat: String = tr(
        zh = "已导入 {0}",
        en = "Imported {0}",
        ja = "{0} を読み込みました",
    )
    val importedWithNoteFormat: String = tr(
        zh = "已导入 {0}：{1}",
        en = "Imported {0}: {1}",
        ja = "{0} を読み込みました：{1}",
    )
    val importWriteFailedFormat: String = tr(
        zh = "{0} 没能写进去（磁盘满或目录不可写）",
        en = "{0} could not be written (storage full or folder not writable)",
        ja = "{0} を書き込めませんでした（容量不足、またはフォルダに書き込めません）",
    )
    val importSkippedOccupiedFormat: String = tr(
        zh = "未导入 {0}：那一格里已经有存档",
        en = "Not imported {0}: that slot already has a save",
        ja = "{0} は読み込みません：その位置には既にセーブがあります",
    )
    /** 单个文件处理时抛了未预期的异常（I/O、OOM 一类）时的兜底原话。 */
    val unexpectedFailure: String = tr(
        zh = "处理时发生错误",
        en = "Unexpected error",
        ja = "処理中にエラーが発生しました",
    )
    val selectedFileFallback: String = tr(
        zh = "选中的文件",
        en = "selected file",
        ja = "選択したファイル",
    )
    val globalSaveLabel: String = tr(
        zh = "全局存档",
        en = "Global save",
        ja = "グローバルセーブ",
    )
    val slotFormat: String = tr(
        zh = "存档位 {0}",
        en = "Slot {0}",
        ja = "セーブ {0}",
    )
    val noNote: String = tr(
        zh = "无备注",
        en = "no note",
        ja = "メモなし",
    )

    // ---- 导出结果 ----
    val configUnreadableExportFormat: String = tr(
        zh = "读不到存档目录的口径（{0}），未导出",
        en = "Could not read the save folder settings ({0}); nothing was exported",
        ja = "セーブ先の設定を読み取れません（{0}）。書き出しませんでした",
    )
    val exportCollectFailed: String = tr(
        zh = "导出失败：读不了存档目录",
        en = "Export failed: cannot read the save folder",
        ja = "書き出し失敗：セーブフォルダを読み取れません",
    )
    val exportNothing: String = tr(
        zh = "没有可导出的存档",
        en = "There are no saves to export",
        ja = "書き出せるセーブデータがありません",
    )
    val exportPickerFailed: String = tr(
        zh = "打不开系统文件选择器",
        en = "Could not open the system file picker",
        ja = "システムのファイル選択画面を開けません",
    )
    val exportDoneFormat: String = tr(
        zh = "已导出 {0} 个存档{1} → {2}",
        en = "Exported {0} saves{1} → {2}",
        ja = "{0} 件のセーブを書き出しました{1} → {2}",
    )
    val exportIncludingGlobal: String = tr(
        zh = "（含全局存档）",
        en = " (including the global save)",
        ja = "（グローバルセーブを含む）",
    )
    val exportFailedAtFormat: String = tr(
        zh = "导出失败：{0} 读不出来（已写 {1} 个）",
        en = "Export failed: {0} could not be read ({1} written)",
        ja = "書き出し失敗：{0} を読み取れません（{1} 件書き出し済み）",
    )
    val exportFailedGenericFormat: String = tr(
        zh = "导出失败（已写 {0} 个）",
        en = "Export failed ({0} written)",
        ja = "書き出し失敗（{0} 件書き出し済み）",
    )
    val exportSkippedFormat: String = tr(
        zh = "跳过 {0}：{1}",
        en = "Skipped {0}: {1}",
        ja = "{0} をスキップ：{1}",
    )

    // ---- 存档判读理由（复述平台给出的原话） ----
    val reasonOnlySav: String = tr(
        zh = "只能导入 .sav 存档文件",
        en = "Only .sav save files can be imported",
        ja = "取り込めるのは .sav セーブファイルのみです",
    )
    val reasonCorrupted: String = tr(
        zh = "存档数据已损坏",
        en = "The save data is corrupted",
        ja = "セーブデータが壊れています",
    )
    val reasonDifferentGame: String = tr(
        zh = "试图读取的存档属于另一个游戏",
        en = "The save belongs to a different game",
        ja = "異なるゲームのセーブデータです",
    )
    val reasonDifferentVersion: String = tr(
        zh = "存档版本不同",
        en = "The save version differs",
        ja = "セーブデータのバーションが異なります",
    )
    val reasonSlotTooLarge: String = tr(
        zh = "存档位编号太大",
        en = "The slot number is too large",
        ja = "セーブ番号が大きすぎます",
    )
    val reasonSlotsFull: String = tr(
        zh = "存档位已经满了，没有空位",
        en = "All save slots are full",
        ja = "セーブ位置がいっぱいで空きがありません",
    )
    val reasonTooBigFormat: String = tr(
        zh = "文件超过 {0} MB，不像存档",
        en = "The file is over {0} MB and does not look like a save",
        ja = "ファイルが {0} MB を超えており、セーブデータとは思えません",
    )
    val reasonEmptyFile: String = tr(
        zh = "是空文件",
        en = "The file is empty",
        ja = "空のファイルです",
    )
    val reasonOversize: String = tr(
        zh = "超过体积上限",
        en = "Exceeds the size limit",
        ja = "サイズ上限を超えています",
    )

    /** `.rt2` 侧车没有同槽 `.sav` 本体（导出跳过 / 导入拒收共用，原文见 `SaveImport.REASON_SIDECAR_WITHOUT_BODY`）。 */
    val reasonSidecarWithoutBody: String = tr(
        zh = "缺少同槽的 .sav 本体",
        en = "The matching .sav save is missing",
        ja = "同じスロットの .sav 本体がありません",
    )

    // ---- 分区标签页（移植版 / 原版引擎 / Mod 素材 / 关于） ----
    val zonePortTab: String = tr(
        zh = "移植版功能",
        en = "Port features",
        ja = "移植版機能",
    )
    val zoneEngineTab: String = tr(
        zh = "原版引擎",
        en = "Original engine",
        ja = "原版エンジン",
    )
    val zoneModTab: String = tr(
        zh = "Mod",
        en = "Mods",
        ja = "Mod",
    )
    val zoneAboutTab: String = tr(
        zh = "关于",
        en = "About",
        ja = "情報",
    )

    // ---- 关于与反馈卡（版本号 / 开发者 / 仓库 / 反馈群） ----
    val aboutGroupSubtitle: String = tr(
        zh = "版本 · 开发者 · 反馈渠道",
        en = "Version · developer · where to report bugs",
        ja = "バージョン ・ 開発者 ・ 連絡先",
    )
    val aboutVersionFormat: String = tr(
        zh = "当前版本 {0}（build {1}）",
        en = "Version {0} (build {1})",
        ja = "現在のバージョン {0}（build {1}）",
    )
    val aboutBuildFormat: String = tr(
        zh = "构建 {0}",
        en = "Build {0}",
        ja = "ビルド {0}",
    )
    val aboutDeveloperCaption: String = tr(
        zh = "开发者",
        en = "Developer",
        ja = "開発者",
    )
    val aboutDeveloperName: String = tr(
        zh = "Poboking",
        en = "Poboking",
        ja = "Poboking",
    )
    val aboutRepoCaption: String = tr(
        zh = "项目仓库",
        en = "Repository",
        ja = "リポジトリ",
    )
    val aboutRepoUrl: String = tr(
        zh = "https://github.com/Poboking/eratw-pocket",
        en = "https://github.com/Poboking/eratw-pocket",
        ja = "https://github.com/Poboking/eratw-pocket",
    )
    val aboutRepoButton: String = tr(
        zh = "打开仓库",
        en = "Open repository",
        ja = "リポジトリを開く",
    )
    val aboutRepoFailed: String = tr(
        zh = "没有能打开链接的应用",
        en = "No app can open this link",
        ja = "リンクを開けるアプリがありません",
    )
    val aboutFeedbackCaption: String = tr(
        zh = "Bug 反馈群（QQ）",
        en = "Bug report group (QQ)",
        ja = "バグ報告グループ（QQ）",
    )
    val aboutGroupId: String = tr(
        zh = "1130136207",
        en = "1130136207",
        ja = "1130136207",
    )
    val aboutCopyGroupButton: String = tr(
        zh = "复制群号",
        en = "Copy group number",
        ja = "グループ番号をコピー",
    )
    val aboutCopiedFormat: String = tr(
        zh = "已复制 {0}",
        en = "Copied {0}",
        ja = "{0} をコピーしました",
    )

    // ---- 署名声明（关于页最底下一层：本体归属 / 本项目范围 / 借物表 / 责任归属） ----
    val creditsTitle: String = tr(
        zh = "署名与声明",
        en = "Credits and notices",
        ja = "クレジットと注意事項",
    )
    val creditsHeadGame: String = tr(
        zh = "游戏本体",
        en = "The game itself",
        ja = "ゲーム本体",
    )
    val creditsGameBody: String = tr(
        zh = "本应用所运行的游戏本体（画蛇添足版 eraTW）由画蛇添足开发组创作并维护。本项目不撰写、不修改、也不解释任何游戏内容；游戏内容的著作权归画蛇添足开发组，以及各位口上、事件与数据的所有者。",
        en = "The game this app runs (the 画蛇添足 edition of eraTW) is created and maintained by 画蛇添足开发组. This project writes, edits and explains no game content; copyright in that content belongs to that team and to the authors of each scenario, event and data file.",
        ja = "本アプリが動作させるゲーム本体（画蛇添足版 eraTW）は、画蛇添足開発組が制作・維持しています。本プロジェクトはゲーム内容を一切制作・改変・解説せず、ゲーム内容の著作権は画蛇添足開発組、および各口上・イベント・データの著作者に帰属します。",
    )
    val creditsHeadScope: String = tr(
        zh = "本项目的范围",
        en = "Scope of this project",
        ja = "本プロジェクトの範囲",
    )
    val creditsScopeBody: String = tr(
        zh = "本项目只提供安卓原生的 era 引擎与 Android 兼容层：把 PC 版 Emuera 的运行环境搬到安卓上，不改变脚本语义。游戏行为的兼容性以 Emuera 的实际表现为参考基准。",
        en = "This project provides only an Android-native era engine and the Android compatibility layer: it moves the PC Emuera runtime onto Android without changing script semantics. Behavioural compatibility is measured against Emuera's own behaviour.",
        ja = "本プロジェクトが提供するのは、Android ネイティブの era エンジンと Android 互換層のみです。PC 版 Emuera の実行環境を Android へ移すものであり、スクリプトの意味は変更しません。挙動の互換性は Emuera の実際の挙動を基準とします。",
    )
    val creditsHeadThanks: String = tr(
        zh = "致谢",
        en = "Acknowledgements",
        ja = "謝辞",
    )
    val creditsItemEmuera: String = tr(
        zh = "MinorShift —— Emuera 的著作者。",
        en = "MinorShift — author of Emuera.",
        ja = "MinorShift —— Emuera の著作者。",
    )
    val creditsItemKoujo: String = tr(
        zh = "まだ名前は無い人 —— 以及所有为 eraTW 撰写口上、事件、数据与文档的创作者。",
        en = "まだ名前は無い人 — and everyone who wrote scenarios, events, data and documentation for eraTW.",
        ja = "まだ名前は無い人 —— および eraTW に口上・イベント・データ・ドキュメントを提供したすべての創作者。",
    )
    val creditsItemCommunity: String = tr(
        zh = "era 社区的各位脚本作者、内容作者与工具维护者。",
        en = "The era community: script authors, content authors and tool maintainers.",
        ja = "era コミュニティの各スクリプト作者、コンテンツ作者、ツール維持者。",
    )
    val creditsItemTouhou: String = tr(
        zh = "上海アリス幻樂団（ZUN）—— 东方 Project 的原作。",
        en = "Team Shanghai Alice (ZUN) — the original Touhou Project works.",
        ja = "上海アリス幻樂団（ZUN）—— 東方Projectの原作。",
    )
    val creditsLiability: String = tr(
        zh = "因本移植产生的问题由本项目承担，与上海アリス幻樂団、画蛇添足开发组及各内容作者无关。",
        en = "Any problem caused by this port is this project's responsibility, not that of Team Shanghai Alice, 画蛇添足开发组 or the content authors.",
        ja = "本移植に起因する問題の責任は本プロジェクトが負い、上海アリス幻樂団・画蛇添足開発組および各内容作者には及びません。",
    )

    // -----------------------------------------------------------------
    // ECHO-INPUT 开关文案（2026-11-06）
    // -----------------------------------------------------------------

    /** 「回显所选项编号」开关（默认关）的标题。 */
    val echoInputTitle: String = tr(
        zh = "回显所选项编号",
        en = "Echo the selected number",
        ja = "選択した番号を表示する",
    )

    /** 关 = 不多出一行编号、画面不抖；开 = 原版复刻；两档都要重开一局才生效。 */
    val echoInputHint: String = tr(
        zh = "默认关：点剧本、点菜单后不再显示那行所选编号，画面不会跟着跳一下。开：照原版把编号回显到最新一行。两档对脚本的行计数与清屏完全一样，只是这一行看不看得见。改这一项后要重开一局才生效。",
        en = "Off by default: choosing a scenario or menu item no longer shows a line with the selected number, so the screen does not jump. On: reproduce the original behavior and show the number on the last line. Scripts see the same line count and clearing behavior in both modes; only the visibility changes. Takes effect after the game restarts.",
        ja = "既定はオフ：口上やメニューを選んだときに選択番号の行を表示せず、画面が揺れません。オン：原本どおり選択番号を最終行に表示します。どちらもスクリプトから見た行数と行削除は同じで、表示するかどうかだけが変わります。変更後はゲームの再起動が必要です。",
    )

    // -----------------------------------------------------------------
    // 长页忙碌提示（PRAY-BUSY，2026-11）
    // -----------------------------------------------------------------

    /** 「长页忙碌提示」开关（默认开）的标题。 */
    val busyIndicatorTitle: String = tr(
        zh = "长页忙碌提示",
        en = "Long-page busy hint",
        ja = "長い処理中の表示",
    )

    /** 开 = 长页最下面一行显示呼吸的「少女祈祷中…」；关 = 只保留页面刷新。两档都不改游戏内容。 */
    val busyIndicatorHint: String = tr(
        zh = "默认开。一页脚本算满 1 秒时，内容区最下面一行会显示「少女祈祷中…」并随进度呼吸，页面一出来就消失。它只画像素，不改变游戏内容。关掉后长页仍会刷新画面，只是不再显示这行提示。",
        en = "On by default. When a single page takes more than a second, the bottom line of the content area shows the game's prayer text breathing with progress, and it disappears as soon as the page arrives. It only draws pixels and never changes game content. Turn it off to keep page refreshes without this line.",
        ja = "既定はオン。1 ページの計算に 1 秒以上かかると、内容欄の最下行に「少女祈祷中…」が呼吸するように表示され、ページが出た時点で消えます。描画だけでゲーム内容は変わりません。オフにするとページの更新だけになり、この行は表示されません。",
    )

    /** 「Mod 运行时」总闸（RT2，默认关；会话构造期参数，改完重开一局生效）。 */
    val modRuntimeTitle: String = tr(
        zh = "Mod 运行时（实验）",
        en = "Mod runtime (experimental)",
        ja = "Mod ランタイム（実験的）",
    )

    /**
     * 只说玩家能观察到的后果：开 = 随包内置 Mod 会被装载、活动 Mod 数据随存档写成同名
     * `.rt2` 侧车；关 = 与未接 Mod 运行时的版本逐字节相同。当前没有可用的第三方 Mod。
     */
    val modRuntimeHint: String = tr(
        zh = "默认关。开：启用 RT2 Kotlin Mod 运行时，随包内置的 Mod 会被装载；启用中的 Mod 数据会与存档一起保存成同名的 .rt2 侧车文件。目前没有可用的第三方 Mod，普通游玩保持关闭即可。改这一项后要重开一局才生效。",
        en = "Off by default. On: enables the RT2 Kotlin Mod runtime, so Mods bundled with the app get loaded; active Mod data is saved next to each save file as a .rt2 sidecar. No third-party Mods are available yet, so leave it off for normal play. Takes effect after the game restarts.",
        ja = "既定はオフ。オン：RT2 Kotlin Mod ランタイムを有効にし、同梱の Mod を読み込みます。有効な Mod のデータはセーブと同名の .rt2 サイドカーファイルとして保存されます。現時点で利用できる外部 Mod はないため、通常プレイではオフのままで構いません。変更後はゲームの再起動が必要です。",
    )

    // -----------------------------------------------------------------
    // 玩家排序配置（RT2 L2，2026-11-14）：Mod 装载优先级
    // 契约 §1.2：文案必须点明「优先级而非完整顺序」，并说明「依赖关系会改变实际装载序」，
    // 避免玩家把配置误读成对最终序的逐位指定。三条玩家可见文案一律进本表（AGENTS §6.1）。
    // -----------------------------------------------------------------

    /** 「Mod 装载优先级」输入卡标题。 */
    val modPackageOrderTitle: String = tr(
        zh = "Mod 装载优先级",
        en = "Mod load priority",
        ja = "Mod 読み込み優先度",
    )

    /** 说明：逗号分隔 packageId 列表是就绪模块优先级、不是完整顺序；依赖会改变实际装载序。 */
    val modPackageOrderHint: String = tr(
        zh = "用逗号分隔 Mod 包名（packageId），靠前的在「同时就绪」时优先装载。" +
            "这是优先级，不是最终的完整顺序：依赖关系会改变实际装载序，" +
            "没列出的 Mod 按包名排序，已卸载的旧 ID 会被忽略。" +
            "留空 = 恢复缺省，改完要重开一局生效。",
        en = "Separate Mod package IDs with commas; earlier ones load first when both are ready. " +
            "This is a priority, not the final full order: dependencies can change the actual " +
            "load order, unlisted Mods fall back to package ID order, and stale IDs are ignored. " +
            "Leave empty for the default; restart the game to apply.",
        ja = "Mod のパッケージ ID をカンマ区切りで入力します。同時に読み込み可能な場合は先に書いたものが優先されます。" +
            "これは優先度であり最終的な完全な順序ではありません。依存関係により実際の読み込み順は変わり、" +
            "書かれていない Mod はパッケージ ID 順、削除済みの ID は無視されます。" +
            "空欄で既定値、変更はゲーム再起動後に反映されます。",
    )

    /** 输入框空态提示：留空 = 缺省（按 packageId 规范序）。 */
    val modPackageOrderEmpty: String = tr(
        zh = "留空 = 缺省（按 packageId 规范序）",
        en = "Empty = default (package ID canonical order)",
        ja = "空欄 = 既定（パッケージ ID 順）",
    )

    // -----------------------------------------------------------------
    // 右栏布局与过滤（RAIL-OPTIONS，2026-11-11 QQ 群反馈波）
    // -----------------------------------------------------------------

    /** 「右栏布局」选择器标题。 */
    val railLayoutLabel: String = tr(
        zh = "右栏布局",
        en = "Rail layout",
        ja = "右バーの並び",
    )

    /** 行内横滑档名（缺省档）。 */
    val railLayoutRowScrollLabel: String = tr(
        zh = "行内横滑",
        en = "Row scroll",
        ja = "横スクロール",
    )

    /** 行内横滑说明。 */
    val railLayoutRowScrollHint: String = tr(
        zh = "默认：一行最多放所选枚数，装不下的往右滑",
        en = "Default: each row holds up to the chosen count; extras scroll sideways",
        ja = "既定：1 行に選択した枚数まで、あふれた分は横へスクロール",
    )

    /** 竖列档名。 */
    val railLayoutColumnLabel: String = tr(
        zh = "竖列",
        en = "Wrap",
        ja = "折り返し",
    )

    /** 竖列说明。 */
    val railLayoutColumnHint: String = tr(
        zh = "一行装不下折到下一行，不横滑；行内均分宽度",
        en = "Rows wrap to the next line instead of scrolling; buttons share row width",
        ja = "1 行に収まらない分は次の行へ折り返し、横スクロールなし。行内は均等幅",
    )

    /** 完全展开档名。 */
    val railLayoutExpandedLabel: String = tr(
        zh = "完全展开",
        en = "Expanded grid",
        ja = "全展開",
    )

    /** 完全展开说明。 */
    val railLayoutExpandedHint: String = tr(
        zh = "格子定宽铺满右栏，左起排不拉伸，不横滑",
        en = "Fixed-width cells fill the rail, left-aligned; no stretching or scrolling",
        ja = "固定幅のセルで右バーを埋め、左詰め・伸縮なし・スクロールなし",
    )

    /** 「每行最多按钮数」选择器标题（三档通用）。 */
    val railPerRowLabel: String = tr(
        zh = "每行最多按钮数",
        en = "Max buttons per row",
        ja = "1 行の最大ボタン数",
    )

    /** 「右栏过滤」功能卡标题。 */
    val railFilterTitle: String = tr(
        zh = "右栏过滤",
        en = "Rail filter",
        ja = "右バー フィルター",
    )

    /** 「隐藏无编号按钮」规则开关标题。 */
    val railFilterRuleLabel: String = tr(
        zh = "隐藏无编号按钮",
        en = "Hide unnumbered buttons",
        ja = "番号なしボタンを隠す",
    )

    /** 规则开关说明。 */
    val railFilterRuleHint: String = tr(
        zh = "开：立绘开关、【＋】、[CONFIG] 这类屏上不印编号的小开关从右栏隐藏（正文画面里仍可点，调色板不受影响）；关：显示全部识别结果（默认）",
        en = "On: small switches without their own number (portrait toggles, [＋], [CONFIG]) hide from the rail — still tappable in the text; palette unaffected. Off: show all (default)",
        ja = "オン：立ち絵スイッチ、【＋】、[CONFIG] など自分の番号を印字しない小スイッチを右バーから隠す（本文では押せる、パレットは対象外）。オフ：全部表示（既定）",
    )

    /** 隐藏名单小节标题。 */
    val railFilterHiddenHeader: String = tr(
        zh = "已隐藏的按钮",
        en = "Hidden buttons",
        ja = "隠したボタン",
    )

    /** 隐藏名单逐条「恢复」。 */
    val railFilterRestore: String = tr(
        zh = "恢复",
        en = "Restore",
        ja = "戻す",
    )

    /** 清空隐藏名单。 */
    val railFilterClear: String = tr(
        zh = "清空隐藏名单",
        en = "Clear hidden list",
        ja = "隠しリストを空に",
    )

    /** 隐藏名单空态。 */
    val railFilterEmpty: String = tr(
        zh = "没有隐藏任何按钮。长按右栏里的按钮即可把它藏起来。",
        en = "Nothing hidden yet. Long-press a rail button to hide it.",
        ja = "まだ何も隠していません。右バーのボタンを長押しすると隠せます。",
    )

    /** 「常用置顶」功能卡标题。 */
    val railPinnedTitle: String = tr(
        zh = "常用置顶",
        en = "Favorites",
        ja = "よく使う固定",
    )

    /** 已置顶计数：{0} 当前、{1} 上限。 */
    private val railPinnedCountFormat: String = tr(
        zh = "已置顶 {0}/{1}",
        en = "{0}/{1} pinned",
        ja = "{0}/{1} 固定中",
    )

    /** 置顶项逐条「取消置顶」。 */
    val railPinnedUnpin: String = tr(
        zh = "取消置顶",
        en = "Unpin",
        ja = "固定を解除",
    )

    /** 清空常用置顶。 */
    val railPinnedClear: String = tr(
        zh = "清空常用",
        en = "Clear favorites",
        ja = "よく使うを空に",
    )

    /** 置顶列表空态。 */
    val railPinnedEmpty: String = tr(
        zh = "还没有置顶项：长按右栏按钮，或在下面勾选。",
        en = "Nothing pinned yet: long-press a rail button, or pick below.",
        ja = "まだ何も固定していません：右バーのボタンを長押し、または下で選択。",
    )

    /** 历史候选小节标题。 */
    val railPinnedHistoryHeader: String = tr(
        zh = "从最近见过的指令里新增",
        en = "Add from recently seen commands",
        ja = "最近見たコマンドから追加",
    )

    /** 历史候选空态。 */
    val railPinnedHistoryEmpty: String = tr(
        zh = "暂无候选：进游戏逛一逛，带编号的指令会自动出现在这里",
        en = "No candidates yet: play a while and numbered commands appear here",
        ja = "候補なし：ゲームを進めると番号付きコマンドがここに出ます",
    )

    /** 置顶卡底部的提示（显示规则 + 长按入口）。 */
    val railPinnedHint: String = tr(
        zh = "置顶项只在当前画面里出现同值按钮时才显示；游戏右栏长按按钮也能随时置顶或取消。",
        en = "Pinned items show only while a button with the same value is on screen; long-press rail buttons to pin or unpin anytime.",
        ja = "固定項目は同じ値のボタンが画面に出ているときだけ表示。ゲーム右バーの長押しでもいつでも固定・解除できます。",
    )

    /** 置顶项名字未知时的兜底显示：{0} 提交值。 */
    private val railPinnedUnknownFormat: String = tr(
        zh = "编号 {0}",
        en = "No. {0}",
        ja = "番号 {0}",
    )

    /** 「常用置顶」卡的计数行：{0} 当前枚数、{1} 上限。 */
    fun railPinnedCountLine(current: Int, cap: Int): String = fill(railPinnedCountFormat, current, cap)

    /** 置顶项在历史里找不到名字时的兜底显示（拿提交值当编号）。 */
    fun railPinnedUnknown(value: String): String = fill(railPinnedUnknownFormat, value)

    // -----------------------------------------------------------------
    // 长名单卡的通用折叠文案（2026-11-12）
    //
    // 右栏过滤 / 常用置顶 / 快捷宏三张卡共用：「展开全部（N）」与「收起」。
    // 条目超过预览上限时才显示，短名单不出现这行按钮。
    // -----------------------------------------------------------------

    /** 通用「展开全部」：{0} 是完整条数。 */
    private val commonShowAllFormat: String = tr(
        zh = "展开全部（{0}）",
        en = "Show all ({0})",
        ja = "すべて表示（{0}）",
    )

    /** 通用「展开全部」（{0} 完整条数）。 */
    fun commonShowAll(total: Int): String = fill(commonShowAllFormat, total)

    /** 通用「收起」。 */
    val commonCollapse: String = tr(
        zh = "收起",
        en = "Collapse",
        ja = "たたむ",
    )

    // -----------------------------------------------------------------
    // 快捷宏（2026-11-11 重做：玩家自定义「步骤 + 次数 + 组合重复」）
    //
    // 宏不是让玩家写指令集串，而是让他**拼**：选哪个选项、连发几次、先 a 后 b 这种组合跑几轮。
    // 编译成 era 通用指令集串（`\n` 分段、`\e` 快进）的事由代码做，分段由引擎做
    // （`EmueraConsole.cs:146`/`:1429-1438`）。与 `macro.txt` 的 F1–F12 键盘宏是两套东西。
    // -----------------------------------------------------------------

    /** 「快捷宏」功能卡标题。 */
    val railQuickMacroTitle: String = tr(
        zh = "快捷宏",
        en = "Quick macros",
        ja = "クイックマクロ",
    )

    /** 已配置计数：{0} 当前、{1} 上限。 */
    private val railQuickMacroCountFormat: String = tr(
        zh = "已配置 {0}/{1}",
        en = "{0}/{1} configured",
        ja = "{0}/{1} 件設定済み",
    )

    /** 计数行（{0} 当前枚数、{1} 上限）。 */
    fun railQuickMacroCountLine(current: Int, cap: Int): String = fill(railQuickMacroCountFormat, current, cap)

    /** 空态说明：给出「怎么拼一条」的三个动词。 */
    val railQuickMacroEmpty: String = tr(
        zh = "还没有快捷宏。点「新增」：选一个游戏选项、设连发次数；再加一步就是「先 a 后 b」，最后设整组重复几轮。",
        en = "No macros yet. Tap Add: pick a game option, set how many times; add another step for \"a then b\", then set how many rounds.",
        ja = "まだありません。「追加」で：ゲームの選択肢を選び回数を設定。もう1歩足せば「a の後に b」、最後に全体の繰り返し回数を決めます。",
    )

    /** 新增一枚。 */
    val railQuickMacroAdd: String = tr(
        zh = "新增",
        en = "Add",
        ja = "追加",
    )

    /** 编辑一枚。 */
    val railQuickMacroEdit: String = tr(
        zh = "编辑",
        en = "Edit",
        ja = "編集",
    )

    /** 删除一枚。 */
    val railQuickMacroDelete: String = tr(
        zh = "删除",
        en = "Delete",
        ja = "削除",
    )

    /** 清空全部快捷宏。 */
    val railQuickMacroClear: String = tr(
        zh = "清空快捷宏",
        en = "Clear macros",
        ja = "すべて削除",
    )

    /** 编辑对话框：已选步骤小节标题。 */
    val railQuickMacroStepsHeader: String = tr(
        zh = "步骤（按顺序提交）",
        en = "Steps (submitted in order)",
        ja = "ステップ（順に送信）",
    )

    /** 编辑对话框：步骤值为空时的占位。 */
    val railQuickMacroStepsEmpty: String = tr(
        zh = "还没有步骤：从下面选一个指令，或直接填提交值。",
        en = "No steps yet: pick a command below, or type a value directly.",
        ja = "まだステップがありません：下からコマンドを選ぶか、値を直接入力してください。",
    )

    /** 编辑对话框：步骤输入框标签。 */
    val railQuickMacroValueLabel: String = tr(
        zh = "提交值（指令号或任意输入）",
        en = "Submit value (command number or any input)",
        ja = "送信値（コマンド番号または任意の入力）",
    )

    /** 编辑对话框：连发次数标签。 */
    val railQuickMacroTimesLabel: String = tr(
        zh = "连发次数",
        en = "Repeat times",
        ja = "連続回数",
    )

    /** 编辑对话框：加一步。 */
    val railQuickMacroAddStep: String = tr(
        zh = "加一步",
        en = "Add step",
        ja = "ステップ追加",
    )

    /** 编辑对话框：整组重复次数标签。 */
    val railQuickMacroRepeatLabel: String = tr(
        zh = "整组重复次数",
        en = "Rounds (whole sequence)",
        ja = "全体の繰り返し回数",
    )

    /** 编辑对话框：从最近见过的指令里挑。 */
    val railQuickMacroFromRecent: String = tr(
        zh = "从最近见过的指令里选",
        en = "Pick from recently seen commands",
        ja = "最近見たコマンドから選ぶ",
    )

    /** 编辑对话框：最近指令空态。 */
    val railQuickMacroNoRecent: String = tr(
        zh = "暂无记录：进游戏逛一逛，带编号的指令会自动记在这里",
        en = "Nothing recorded yet: play a while and numbered commands appear here",
        ja = "記録なし：ゲームを進めると番号付きコマンドがここに出ます",
    )

    /** 编辑对话框：候选点选提示（傻瓜式主路径）。 */
    val railQuickMacroTapHint: String = tr(
        zh = "点一下即加入；每一步都能单独调连发次数。",
        en = "Tap to add. Each step's repeat count can be adjusted separately.",
        ja = "タップで追加。各ステップの連続回数は個別に変更できます。",
    )

    /** 编辑对话框：手动输入开关。 */
    val railQuickMacroManualToggle: String = tr(
        zh = "手动输入指令号",
        en = "Enter a command manually",
        ja = "コマンド番号を手入力",
    )

    /** 编辑对话框：预览小节标题。 */
    val railQuickMacroPreviewHeader: String = tr(
        zh = "预览",
        en = "Preview",
        ja = "プレビュー",
    )

    /** 编辑对话框：整条宏怎么跑（{0} = 自动生成的步骤串）。 */
    private val railQuickMacroPreviewFormat: String = tr(
        zh = "将这样跑：{0}",
        en = "Runs as: {0}",
        ja = "このように実行：{0}",
    )

    /** 预览读数（{0} = 步骤串）。 */
    fun railQuickMacroPreview(label: String): String = fill(railQuickMacroPreviewFormat, label)

    /** 编辑对话框：将提交几段（预览读数）：{0} 段数。 */
    private val railQuickMacroSegmentsFormat: String = tr(
        zh = "这条宏会提交 {0} 段输入",
        en = "This macro submits {0} inputs",
        ja = "このマクロは {0} 回入力を送信します",
    )

    /** 预览读数（{0} 段数）。 */
    fun railQuickMacroSegments(count: Int): String = fill(railQuickMacroSegmentsFormat, count)

    /** 编辑对话框：保存。 */
    val railQuickMacroSave: String = tr(
        zh = "保存",
        en = "Save",
        ja = "保存",
    )

    /** 编辑对话框：取消。 */
    val railQuickMacroCancel: String = tr(
        zh = "取消",
        en = "Cancel",
        ja = "キャンセル",
    )

    /** 达到上限时的提示：{0} 上限。 */
    private val railQuickMacroFullFormat: String = tr(
        zh = "最多 {0} 个快捷宏，先删一个再新增。",
        en = "Up to {0} macros; delete one before adding.",
        ja = "マクロは最大 {0} 件。追加する前に1件削除してください。",
    )

    /** 上限提示（拿上限填格式）。 */
    fun railQuickMacroFull(cap: Int): String = fill(railQuickMacroFullFormat, cap)

    /** 步数已满提示：{0} 步数上限。 */
    private val railQuickMacroStepQuotaFullFormat: String = tr(
        zh = "已有 {0} 步，再加要先删掉一步。",
        en = "{0} steps already; remove one before adding more.",
        ja = "すでに {0} ステップあります。追加する前に1つ削除してください。",
    )

    /** 步数已满提示（拿步数上限填格式）。 */
    fun railQuickMacroStepQuotaFull(cap: Int): String = fill(railQuickMacroStepQuotaFullFormat, cap)

    // -----------------------------------------------------------------
    // 快捷宏草稿的校验反馈（`MacroDraft.build()` 的 Invalid 逐项翻译）
    //
    // 契约（2026-11-11 复查）：非法输入**不做静默修正**，一律显示原因并禁用保存。
    // -----------------------------------------------------------------

    /** 一步都没有。 */
    val railQuickMacroIssueNoSteps: String = tr(
        zh = "还没有任何步骤：先选一个选项或填一个提交值。",
        en = "No steps yet: pick an option or type a value first.",
        ja = "ステップがありません。まず選択肢を選ぶか値を入力してください。",
    )

    /** 提交值含换行/反斜杠（指令集串无法表达）。 */
    val railQuickMacroIssueValue: String = tr(
        zh = "提交值里不能有换行或反斜杠：指令集串无法原样表达这两类字符。",
        en = "A value cannot contain line breaks or backslashes; the macro syntax cannot express them.",
        ja = "送信値に改行やバックスラッシュは使えません。マクロ構文では表現できません。",
    )

    /** 单步连发次数非法：{0} 上限、{1} 实际输入。 */
    private val railQuickMacroIssueTimesFormat: String = tr(
        zh = "连发次数要填 1~{0} 的整数（现在是「{1}」）。",
        en = "Repeat count must be an integer 1-{0} (currently \"{1}\").",
        ja = "連続回数は 1〜{0} の整数にしてください（現在「{1}」）。",
    )

    /** 连发次数非法提示。 */
    fun railQuickMacroIssueTimes(raw: String): String =
        fill(railQuickMacroIssueTimesFormat, RailOptionRules.MACRO_TIMES_MAX, raw)

    /** 整组重复次数非法：{0} 上限、{1} 实际输入。 */
    private val railQuickMacroIssueRepeatFormat: String = tr(
        zh = "重复次数要填 1~{0} 的整数（现在是「{1}」）。",
        en = "Round count must be an integer 1-{0} (currently \"{1}\").",
        ja = "繰り返し回数は 1〜{0} の整数にしてください（現在「{1}」）。",
    )

    /** 整组重复次数非法提示。 */
    fun railQuickMacroIssueRepeat(raw: String): String =
        fill(railQuickMacroIssueRepeatFormat, RailOptionRules.MACRO_REPEAT_MAX, raw)

    /** 段数超上限：{0} 实际段数、{1} 上限。 */
    private val railQuickMacroIssueSegmentsFormat: String = tr(
        zh = "这条宏会提交 {0} 段，超过上限 {1} 段：请减少连发次数或重复次数。",
        en = "This macro would submit {0} inputs, over the {1} limit; reduce the counts.",
        ja = "このマクロは {0} 回送信され、上限 {1} を超えます。回数を減らしてください。",
    )

    /** 段数超上限提示。 */
    fun railQuickMacroIssueSegments(segments: Int, cap: Int): String =
        fill(railQuickMacroIssueSegmentsFormat, segments, cap)

    /** 编辑中列表被改动（宏被删除/重排）⇒ 拒绝用陈旧下标写回。 */
    val railQuickMacroEditStale: String = tr(
        zh = "列表已变化，这条宏不在了：请关掉重开编辑。",
        en = "The list changed and this macro is gone; reopen the editor.",
        ja = "一覧が変わり、このマクロはありません。開き直してください。",
    )

    /** 「清空全部宏」的二次确认正文。 */
    val railQuickMacroClearConfirm: String = tr(
        zh = "清空全部快捷宏？此操作不能撤销。",
        en = "Clear all quick macros? This cannot be undone.",
        ja = "クイックマクロをすべて消去しますか？元に戻せません。",
    )

    /** 卡片底部说明：宏怎么被解释（连发/组合/快进），以及和键盘宏的区别。 */
    val railQuickMacroHint: String = tr(
        zh = "一个宏 = 若干步，每步按「连发次数」提交，整组按「重复次数」跑几轮。" +
            "提交时自动拼成 era 指令集串（段间快进），不需要玩家自己写反斜杠转义。" +
            "注意：这和键盘宏（macro.txt 的 F1–F12）是两套东西。",
        en = "A macro is a list of steps: each step is submitted its own number of times, and the whole " +
            "sequence repeats for the configured rounds. The command-set string (with fast-forward) is " +
            "built for you. Note: this is separate from the keyboard macros (F1-F12 in macro.txt).",
        ja = "マクロは複数ステップの集まりです：各ステップを「連続回数」だけ送信し、全体を「繰り返し回数」だけ実行します。" +
            "命令セット文字列（早送り付き）は自動で組み立てられます。なお、キーボードマクロ（macro.txt の F1–F12）とは別物です。",
    )

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
            SaveImport.REASON_SIDECAR_WITHOUT_BODY -> reasonSidecarWithoutBody
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

    /**
     * 竖屏档名：键只认 [UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT] 里的四档（50/62/75/90），
     * 其余回落缺省 62 档的档名 —— 与 `UiPrefs.normalizeFontColumnsPortrait` 的坏值回落同口径。
     */
    fun portraitFontColumnLabel(columns: Int): String =
        portraitFontColumnLabels[columns] ?: portraitColLargeLabel

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

    fun railModeLabel(mode: UiPrefs.RailLayoutMode): String = when (mode) {
        UiPrefs.RailLayoutMode.ROW_SCROLL -> railLayoutRowScrollLabel
        UiPrefs.RailLayoutMode.COLUMN -> railLayoutColumnLabel
        UiPrefs.RailLayoutMode.EXPANDED -> railLayoutExpandedLabel
    }

    fun railModeHint(mode: UiPrefs.RailLayoutMode): String = when (mode) {
        UiPrefs.RailLayoutMode.ROW_SCROLL -> railLayoutRowScrollHint
        UiPrefs.RailLayoutMode.COLUMN -> railLayoutColumnHint
        UiPrefs.RailLayoutMode.EXPANDED -> railLayoutExpandedHint
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

        private val ZH: SettingsStrings = SettingsStrings(SettingsLanguage.ZH)
        private val EN: SettingsStrings = SettingsStrings(SettingsLanguage.EN)
        private val JA: SettingsStrings = SettingsStrings(SettingsLanguage.JA)

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
