package com.eratw.port.android

import com.eratw.port.core.config.ConfigClampLanguage
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigPageLabels
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.SettingsEntry
import com.eratw.port.core.config.SettingsRejectReason
import com.eratw.port.core.mod.RegisteredFaces
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

    // ---- 应用偏好内的三个子分组（2026-11-26 信息架构收口：12 张卡一次列出要翻两三屏）----
    val displayGroupTitle: String = tr(
        zh = "字号与画面",
        en = "Text and display",
        ja = "文字と表示",
    )
    val displayGroupSubtitle: String = tr(
        zh = "字号档、竖屏字号、留白与按钮几何 · 随改随存",
        en = "Text size, portrait size, margins and button geometry · saved immediately",
        ja = "文字サイズ・縦画面サイズ・余白・ボタン寸法 · すぐ保存",
    )
    val railGroupTitle: String = tr(
        zh = "右栏与快捷",
        en = "Rail and shortcuts",
        ja = "右バーとショートカット",
    )
    val railGroupSubtitle: String = tr(
        zh = "选项栏显示规则、常用置顶与快捷宏 · 随改随存",
        en = "Rail display rules, pinned options and quick macros · saved immediately",
        ja = "右バーの表示規則・よく使う項目の固定・クイックマクロ · すぐ保存",
    )
    val launchGroupTitle: String = tr(
        zh = "启动与维护",
        en = "Launch and upkeep",
        ja = "起動とメンテナンス",
    )
    val launchGroupSubtitle: String = tr(
        zh = "启动画面、自动更新与诊断导出 · 随改随存",
        en = "Loading screen, auto update and diagnostics export · saved immediately",
        ja = "起動画面・自動更新・診断出力 · すぐ保存",
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
     * 「实验性」小签（2026-11-26）：竖屏相关的功能卡（竖屏模式开关 / 竖屏字号）在标题旁挂这一枚。
     *
     * 语义是「能用但还没定型、随时可能改」，不是错误，所以挂警示橙而不是危险红；文案本身
     * 进三语表，不在页面里内联（AGENTS §6.1）。
     */
    val experimentalTag: String = tr(
        zh = "实验性",
        en = "Experimental",
        ja = "試験的",
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
     * 当前竖屏档的取舍说明（分段条只装得下档名，这一句跟在分段条下面）。
     * 与档名同源：键取自 [UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT]，值顺序与四个档名一一对齐。
     */
    val portraitColExtraLargeHint: String = tr(
        zh = "约 50 列：字最大、折行最早，单手大字阅读最稳",
        en = "~50 columns: largest text and earliest wrapping; easiest one-handed reading",
        ja = "約 50 列：文字最大・折り返しが早い。片手での大きな文字の読みに最適",
    )
    val portraitColLargeHint: String = tr(
        zh = "约 62 列：竖屏默认档，字号与一屏信息量平衡",
        en = "~62 columns: the portrait default, balancing text size and on-screen information",
        ja = "約 62 列：縦画面の既定。文字サイズと 1 画面の情報量のバランスが良い",
    )
    val portraitColStandardHint: String = tr(
        zh = "约 75 列：字略小，一屏能多看几行",
        en = "~75 columns: slightly smaller text, a few more lines per screen",
        ja = "約 75 列：文字はやや小さめで 1 画面に数行多く入る",
    )
    val portraitColSmallHint: String = tr(
        zh = "约 90 列：字最小、单屏信息最多，适合小屏手机",
        en = "~90 columns: smallest text and most information per screen; good for small phones",
        ja = "約 90 列：文字最小・1 画面の情報量最大。小型端末向け",
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

    /** 竖屏档说明映射：与档名同一套键（[UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT] 的四个 columns）。 */
    private val portraitFontColumnHints: Map<Int, String> =
        UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { it.columns }
            .zip(
                listOf(
                    portraitColExtraLargeHint,
                    portraitColLargeHint,
                    portraitColStandardHint,
                    portraitColSmallHint,
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

    /**
     * 导出卡的 mod 例外提示（MODDX4-D3 诚实文案）：`.sav` 本体 PC Emuera 照常可读；装了补丁
     * 的存档附带的 `.rt2` 扩展数据 PC 不识别，扩展部分 PC 不可玩——照实说，不许夸大成
     * 「存档会坏」。规约锚 = specs 下的 save-format.md 第 11 节 + era-arch-v61u.md:223-231
     * 兼容性矩阵（「100% 互通」表述已作废）。上屏落位由组长接线（导出卡说明行邻域）。
     */
    val saveModExportHint: String = tr(
        zh = "没装补丁（Mod）时，导出的存档电脑版照常能读；装了补丁的存档会附带一份扩展数据文件，电脑版读不到扩展部分。",
        en = "With no patches (Mods) installed, exported saves read normally on PC; patched saves " +
            "carry a companion extension data file whose contents the PC version cannot read.",
        ja = "パッチ（Mod）を入れていなければ、書き出したセーブはパソコン版でそのまま読めます。パッチを入れたセーブには拡張データファイルが付随し、パソコン版は拡張部分を読めません。",
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

    /**
     * 导入卡的 mod 例外提示（MODDX4-D3 诚实文案）：装了补丁后扩展部分随 `.rt2` 走，本体与
     * 扩展数据成对导入；PC 玩过同一槽传回或扩展数据缺失/失配时上警告（扩展状态可能滞后或
     * 丢失），不是静默应用。规约锚同 [saveModExportHint]。
     * 上屏落位由组长接线（导入卡标题说明行邻域）。
     */
    val saveModImportHint: String = tr(
        zh = "没装补丁（Mod）时，存档与电脑版完全互通；装了补丁后，扩展部分单独成文件，本体和扩展数据要成对导入，缺了扩展数据应用会提醒。",
        en = "With no patches (Mods) installed, saves interop fully with PC; with patches, the extension " +
            "part travels in a separate companion file — import the body and extension data as a pair, " +
            "and the app will warn when the extension data is missing.",
        ja = "パッチ（Mod）を入れていなければ、セーブはパソコン版と完全にやり取りできます。" +
            "パッチを入れた場合は拡張部分が別ファイル側にあり、本体と拡張データをペアで読み込んでください。" +
            "拡張データが足りないときはアプリが知らせます。",
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
        zh = "已装 Mod 的启停、卸载与诊断，以及素材包、口上补丁与 Mod 市场的入口",
        en = "Enable, uninstall and diagnose installed mods, plus entries for asset packs, " +
            "script patches and the mod market",
        ja = "導入済み Mod の有効化・削除・診断と、素材パック・口上パッチ・Mod マーケットの入口",
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

    /** 逐帧诊断记录开关的标题（诊断卡内，默认关）。 */
    val frameTraceTitle: String = tr(
        zh = "记录逐帧诊断（排查画面跳动用）",
        en = "Record per-frame trace (for screen-jitter reports)",
        ja = "フレーム単位の診断を記録（画面の揺れ調査用）",
    )

    /** 逐帧诊断记录开关的说明：讲清「复现 → 分享」这个动作序，别让玩家以为开着就会自动上报。 */
    val frameTraceHint: String = tr(
        zh = "开着玩到画面开始跳，再回到这里点「导出并分享诊断」；只在本机记录，不会自动发送",
        en = "Play until the screen jumps, then come back and tap Share; recorded on this device only, " +
            "never sent automatically",
        ja = "画面が揺れ始めるまでプレイしてから、ここに戻って「書き出して共有」を押してください。記録は端末内のみで、自動送信はしません",
    )

    /**
     * 玩家授权诊断通道开关的标题（判词 9 / PDIAG1，Mod 档，缺省关）。
     *
     * 与 [frameTraceTitle] 的分工要分清：那一格只开逐帧环，本格开的是整条 DevTools 的
     * **只读**命令通道（正式包里锁等待读数只有这一条路）。
     */
    val playerDiagTitle: String = tr(
        zh = "开启只读诊断通道（排查卡死用）",
        en = "Enable read-only diagnostics (for freeze reports)",
        ja = "読み取り専用診断を有効化（固まり調査用）",
    )

    /**
     * 开关说明：三件事缺一不可 —— 只放读侧、只落本机不自动发送、开启要重开一局（关闭当场停）。
     * 「重开一局生效」是装配面的真实代价（启动点在装载尾段 GameHost.kt:1050，本波不动它），不许写成即时。
     */
    val playerDiagHint: String = tr(
        zh = "开启后只放读侧读数（锁等待、变量、调用栈这类）；改存档、注入按键这类写侧动作仍需要 debug 包装机。" +
            "读数只写在应用自己的目录，不会自动发送。开启要重开一局才生效，关闭当场就停。",
        en = "Unlocks read-side readouts only (lock waits, variables, call stacks); write-side actions such as " +
            "editing saves or injecting key presses still need a debuggable build. Readouts stay inside the app " +
            "folder and are never sent automatically. Enabling takes effect after a restart; disabling stops now.",
        ja = "読み取り側だけの数値（ロック待ち・変数・コールスタック）。セーブ改変やキー注入など書き込み側は " +
            "debug 版端末が必要です。数値はアプリ自身のフォルダに書き、自動送信しません。有効化は再起動後、無効化は即時停止。",
    )

    /** 开启前的二次确认标题（照 modCodeLoaderConfirmTitle 的形状）。 */
    val playerDiagConfirmTitle: String = tr(
        zh = "开启只读诊断通道？",
        en = "Enable read-only diagnostics?",
        ja = "読み取り専用診断を有効にしますか？",
    )

    /**
     * 二次确认正文：三件事逐语都要在 —— 只放读侧（写侧继续拒）、只落本机不自动发送、
     * 开启下次进入游戏才生效而关闭当场停。少说一件就是让玩家在不知情下打开一条通道。
     */
    val playerDiagConfirmBody: String = tr(
        zh = "这一格只放开只读观测：锁等待、变量、调用栈等读数会写进应用自己的目录，不会自动发送给任何人。" +
            "改存档、注入按键、改运行时行为这类写侧动作依旧拒绝，需要 debug 包装机。" +
            "开启后要重开一局才生效，关闭时当场停通道。",
        en = "This unlocks read-side observation only: lock waits, variables and call stacks are written inside " +
            "the app folder and never sent to anyone. Write-side actions — editing saves, injecting key presses, " +
            "changing runtime behaviour — stay refused and need a debuggable build. It takes effect the next time " +
            "the game starts; switching it off stops the channel right away.",
        ja = "この項目は読み取り専用の観測だけを解放します：ロック待ち・変数・コールスタックの数値はアプリ自身の" +
            "フォルダに保存され、誰にも自動送信されません。セーブ改変・キー注入・実行時動作の変更など書き込み側は" +
            "引き続き拒否され、debug 版端末が必要です。有効化は次回ゲーム起動時に反映され、無効化はその場で停止します。",
    )

    /** 确认按钮：明示「我知道我在开什么」，不用「确定」这种零信息词。 */
    val playerDiagConfirmOk: String = tr(
        zh = "了解，开启",
        en = "I understand, enable",
        ja = "わかった、有効にする",
    )

    /** 写盘失败红字（读回校验为假时给，不静默当成功）。 */
    val playerDiagWriteFailed: String = tr(
        zh = "写入失败：授权位没有落盘，请再试一次",
        en = "Write failed: the grant was not stored, try again",
        ja = "書き込み失敗：許可状態が保存されませんでした。もう一度お試しください",
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

    // ---- 分区标签页（移植版 / 原版引擎 / Mod 与素材 / 关于） ----
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
    // COMEND-WAIT 开关文案（2026-11-22）
    // -----------------------------------------------------------------

    /** 「指令后等一次按键」开关（默认开 = 原版）的标题。 */
    val commandEndWaitTitle: String = tr(
        zh = "指令后等一次按键",
        en = "Wait for a tap after each command",
        ja = "コマンド後に一度タップを待つ",
    )

    /** 开 = 原版：指令打出的文本留在屏上、点一下才继续；关 = 不等（旧版行为）。 */
    val commandEndWaitHint: String = tr(
        zh = "默认开（原版行为）：执行一个指令、把文本打完之后停下，你点一下才会回到指令菜单，" +
            "打出来的内容不会被下一回合直接刷掉。关掉就不再等这一下，指令连着执行更快，" +
            "但较长的口上容易被刷走、需要往上翻阅。改这一项后要重开一局才生效。",
        en = "On by default (original behavior): after a command prints its text, it waits for " +
            "one tap before returning to the command menu, so the printed text is not wiped by " +
            "the next turn. Turn it off to skip that tap and run commands straight through; " +
            "longer passages may scroll away and need scrolling back through. Takes effect " +
            "after the game restarts.",
        ja = "既定はオン（原本の動作）：コマンドの文章を表示した後で一度タップを待ち、" +
            "それからコマンドメニューに戻ります。表示された内容が次のターンで流されません。" +
            "オフにするとこの待ちがなくなり、コマンドを続けて実行できますが、" +
            "長い口上は流れて読み返す必要があります。変更後はゲームの再起動が必要です。",
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

    /** 「Mod 运行时」总闸（RT2，默认开；会话构造期参数，改完重开一局生效）。 */
    val modRuntimeTitle: String = tr(
        zh = "Mod 运行时",
        en = "Mod runtime",
        ja = "Mod ランタイム",
    )

    /**
     * 只说玩家能观察到的后果：开 = 随包内置 Mod 会被装载、活动 Mod 数据随存档写成同名
     * Mod 数据文件；没装 Mod 时游戏照常运行、游戏内容不变；关 = 与未接 Mod 运行时的
     * 版本逐字节相同。
     */
    val modRuntimeHint: String = tr(
        zh = "默认开。开启后，随包内置的 Mod 会在开局时装载；启用中的 Mod 数据会随存档保存成同名的 Mod 数据文件（和存档放在一起）。" +
            "没有安装任何 Mod 时，游戏照常运行、内容不变。关掉后回到完全不用 Mod 运行时的状态。" +
            "改这一项后要重开一局才生效。",
        en = "On by default. When on, Mods shipped with the app are loaded at game start; data from active Mods " +
            "is saved next to your saves as a same-name Mod data file. With no Mods installed the game runs as " +
            "usual and game content stays unchanged. Turn it off to go back to a build with no Mod runtime at " +
            "all. Takes effect after the game restarts.",
        ja = "既定はオン。オンにすると、同梱の Mod は起動時に読み込まれ、有効な Mod のデータはセーブと同名の " +
            "Mod データファイルとして保存されます（セーブの隣）。Mod が入っていない場合もゲームは通常どおり動作し、" +
            "内容は変わりません。オフにすると Mod ランタイムを一切使わない状態に戻ります。" +
            "変更後はゲームの再起動が必要です。",
    )

    // -----------------------------------------------------------------
    // Mod 运行时诊断（RT2-GATE-OPEN，2026-11-17）：release 也能看的只读摘要
    // 真源与 debug 通道 `DevToolsHost.modgate` 同口径（总闸 / 本局会话 / 装载数 / 侧车数）；
    // 玩家可复制后贴进反馈。这里只读，没有任何写入面。
    // -----------------------------------------------------------------

    /** 诊断摘要卡标题。 */
    val modDiagnosticsTitle: String = tr(
        zh = "Mod 运行时诊断",
        en = "Mod runtime diagnostics",
        ja = "Mod ランタイム診断",
    )

    /** 诊断卡说明：只读、可复制、不改设置。 */
    val modDiagnosticsHint: String = tr(
        zh = "只读摘要，反馈问题时可以复制出来。这里不会改动任何设置。",
        en = "A read-only summary you can copy when reporting a problem. Nothing here changes your settings.",
        ja = "読み取り専用の要約です。不具合報告の際にコピーしてください。ここでは設定は変更されません。",
    )

    /** 诊断卡复制按钮。 */
    val modDiagnosticsCopy: String = tr(
        zh = "复制诊断信息",
        en = "Copy diagnostics",
        ja = "診断情報をコピー",
    )

    /** 复制成功提示。 */
    val modDiagnosticsCopied: String = tr(
        zh = "诊断信息已复制",
        en = "Diagnostics copied",
        ja = "診断情報をコピーしました",
    )

    /** 摘要行：总闸开。 */
    val modDiagnosticsGateOn: String = tr(
        zh = "总闸：开",
        en = "Runtime: on",
        ja = "ランタイム：オン",
    )

    /** 摘要行：总闸关。 */
    val modDiagnosticsGateOff: String = tr(
        zh = "总闸：关",
        en = "Runtime: off",
        ja = "ランタイム：オフ",
    )

    /** 摘要行：本局引擎实例里 Mod 运行时已生效（接缝槽命中）。 */
    val modDiagnosticsSessionOn: String = tr(
        zh = "本局 Mod 运行时：已生效",
        en = "Mod runtime in this session: active",
        ja = "今回の Mod ランタイム：有効",
    )

    /** 摘要行：本局未装配（总闸关，或设置刚改还没重开）。 */
    val modDiagnosticsSessionOff: String = tr(
        zh = "本局 Mod 运行时：未生效（重开一局后按总闸生效）",
        en = "Mod runtime in this session: not active (restart the game to apply the switch)",
        ja = "今回の Mod ランタイム：無効（ゲームを再起動すると反映されます）",
    )

    /** 摘要行：Mod 计数（安装 / 启用 / manifest 读不出）。 */
    private val modDiagnosticsModsFormat: String = tr(
        zh = "Mod：安装 {0} 个 · 启用 {1} 个 · 读不出 {2} 个",
        en = "Mods: {0} installed · {1} enabled · {2} unreadable",
        ja = "Mod：インストール {0} 件・有効 {1} 件・読み込み不可 {2} 件",
    )

    /** 摘要行：Mod 数据文件计数与总字节。 */
    private val modDiagnosticsSidecarsFormat: String = tr(
        zh = "Mod 数据文件：{0} 个 · {1} 字节",
        en = "Mod data files: {0} files · {1} bytes",
        ja = "Mod データファイル：{0} 件・{1} バイト",
    )

    fun modDiagnosticsMods(installed: Int, enabled: Int, unreadable: Int): String =
        fill(modDiagnosticsModsFormat, installed, enabled, unreadable)

    fun modDiagnosticsSidecars(count: Int, bytes: Long): String =
        fill(modDiagnosticsSidecarsFormat, count, bytes)

    // -----------------------------------------------------------------
    // Mod 诊断快照导出（task-172）：把「装了啥 / 哪一步失败 / 两把总闸」拼成一段
    // 只读纯文本带走。正文由 ModDiagnosticReport 的纯拼装器生成（取证面固定中文标签），
    // 这里只放壳层文案（按钮 / 弹窗标题 / 说明 / 复制 / 关闭）。
    // -----------------------------------------------------------------

    /** 导出诊断按钮（Mod 管理小节）。 */
    val modDiagnosticExport: String = tr(
        zh = "导出诊断",
        en = "Export diagnostics",
        ja = "診断をエクスポート",
    )

    /** 导出卡说明：只读、离线、可整段贴给作者，且明说不含值本体。 */
    val modDiagnosticExportHint: String = tr(
        zh = "生成一份只读的本机 Mod 诊断快照（不联网、不改设置）。反馈问题时整段复制给作者：" +
            "装了什么、哪一步失败、两把总闸是什么状态都在里面。快照不含设置值与存储值。",
        en = "Builds a read-only, offline Mod diagnostic snapshot (no network, no setting changes). " +
            "Copy the whole thing when reporting a problem: what is installed, where loading failed, " +
            "and the state of both gates. It contains no setting or storage values.",
        ja = "読み取り専用の Mod 診断スナップショットを生成します（オフライン・設定変更なし）。" +
            "不具合報告の際に全文コピーしてください：何がインストールされ、どこで失敗したか、" +
            "2 つのゲートの状態が分かります。設定値と保存値は含まれません。",
    )

    /** 快照弹窗标题。 */
    val modDiagnosticDialogTitle: String = tr(
        zh = "Mod 诊断快照",
        en = "Mod diagnostic snapshot",
        ja = "Mod 診断スナップショット",
    )

    /** 快照弹窗说明：等宽只读 + 复制口径，且明说不含值本体。 */
    val modDiagnosticDialogHint: String = tr(
        zh = "只读文本，可全选或点「复制全部」贴给作者。不含设置值与存储值。",
        en = "Read-only text. Select it or tap \"Copy all\" to share with the author. " +
            "No setting or storage values.",
        ja = "読み取り専用のテキストです。全選択するか「すべてコピー」で作者に共有してください。" +
            "設定値と保存値は含まれません。",
    )

    /** 快照弹窗「复制全部」按钮。 */
    val modDiagnosticDialogCopy: String = tr(
        zh = "复制全部",
        en = "Copy all",
        ja = "すべてコピー",
    )

    // -----------------------------------------------------------------
    // Mod 运行时概览（Lane AW，2026-11-17）：只读诊断卡下面的「每 mod 一行」读数
    // 真源 = 已装清单（同一次 ModManageScan）+ 装载失败表（ModManageDiagnostics）+
    // 活会话的 timing 账本 / 慢处理器预算（ModRuntimeOverviewEntry）。没有任何写入面。
    // -----------------------------------------------------------------

    /** 运行时概览卡标题。 */
    val modRuntimeOverviewTitle: String = tr(
        zh = "Mod 运行时概览",
        en = "Mod runtime overview",
        ja = "Mod ランタイム概要",
    )

    /** 概览卡说明：只读、关态隐藏、不改设置。 */
    val modRuntimeOverviewHint: String = tr(
        zh = "只读：本局每个 Mod 的装载结果与耗时明细。Mod 运行时总闸关掉时这里不显示。",
        en = "Read-only: load results and time breakdown for each mod. Hidden while the mod runtime is off.",
        ja = "読み取り専用：今回の各 Mod の読み込み結果と所要時間の内訳です。Mod ランタイムがオフのときは表示されません。",
    )

    /** 单行状态：本次会话运行中。 */
    val modRuntimeOverviewActive: String = tr(
        zh = "运行中",
        en = "Active",
        ja = "稼働中",
    )

    /** 单行状态：本次会话未运行（停用或装载失败）。 */
    val modRuntimeOverviewInactive: String = tr(
        zh = "未运行",
        en = "Not active",
        ja = "停止中",
    )

    /** 空栏占位：失败原因 / 读数缺失时显示「无」，不留白。 */
    val modRuntimeOverviewNone: String = tr(
        zh = "无",
        en = "None",
        ja = "なし",
    )

    /** 空态：一个已装 Mod 都没有。 */
    val modRuntimeOverviewEmpty: String = tr(
        zh = "没有已安装的 Mod。",
        en = "No mods installed.",
        ja = "インストール済み Mod はありません。",
    )

    /** 失败栏格式：`装载失败：<原因>`。 */
    private val modRuntimeOverviewFailureFormat: String = tr(
        zh = "装载失败：{0}",
        en = "Load failure: {0}",
        ja = "読み込み失敗：{0}",
    )

    /** 累计耗时格式：调用次数 + 总毫秒。 */
    private val modRuntimeOverviewTotalsFormat: String = tr(
        zh = "累计 {0} 次 · {1} ms",
        en = "{0} calls · {1} ms",
        ja = "累計 {0} 回・{1} ms",
    )

    /** 单个作用域格式：作用域名 + 调用次数 + 累计毫秒。 */
    private val modRuntimeOverviewScopeFormat: String = tr(
        zh = "{0} · {1} 次 · {2} ms",
        en = "{0} · {1} calls · {2} ms",
        ja = "{0}・{1} 回・{2} ms",
    )

    /** 慢处理器格式：次数 + 判定阈值毫秒。 */
    private val modRuntimeOverviewSlowFormat: String = tr(
        zh = "慢处理器：{0} 次（单次超过 {1} ms）",
        en = "Slow handlers: {0} (over {1} ms each)",
        ja = "低速ハンドラ：{0} 件（1 回 {1} ms 超）",
    )

    fun modRuntimeOverviewFailure(reason: String): String = fill(modRuntimeOverviewFailureFormat, reason)

    fun modRuntimeOverviewTotals(count: Long, millis: Long): String =
        fill(modRuntimeOverviewTotalsFormat, count, millis)

    fun modRuntimeOverviewScope(scope: String, count: Long, millis: Long): String =
        fill(modRuntimeOverviewScopeFormat, scope, count, millis)

    fun modRuntimeOverviewSlow(count: Long, thresholdMillis: Long): String =
        fill(modRuntimeOverviewSlowFormat, count, thresholdMillis)

    /**
     * 注册面读数格式：hooks（含已退槽）+ 事件四桶 + 交互 / 服务 / 文案 / 设置 / 图片。
     *
     * 字段名保持 ASCII 便于作者对日志：`hooks=` / `events=PRI/NORMAL/LATER/ONLY=` / `interactions=` /
     * `services=` / `strings=` / `settings=` / `images=`；只有前缀按语言翻译。
     */
    private val modRuntimeOverviewFacesFormat: String = tr(
        zh = "注册 hooks={0} 退槽={1} events=PRI/NORMAL/LATER/ONLY={2}/{3}/{4}/{5} " +
            "interactions={6} services={7} strings={8} settings={9} images={10}",
        en = "Registered hooks={0} retired={1} events=PRI/NORMAL/LATER/ONLY={2}/{3}/{4}/{5} " +
            "interactions={6} services={7} strings={8} settings={9} images={10}",
        ja = "登録 hooks={0} 解除={1} events=PRI/NORMAL/LATER/ONLY={2}/{3}/{4}/{5} " +
            "interactions={6} services={7} strings={8} settings={9} images={10}",
    )

    /**
     * 一行注册面读数（**全零也照渲染**：0 本身就是「没注册上」这一最有用的信息，不留白/不隐藏）。
     *
     * 只读 [faces] 快照，不查任何注册表；缺桶键按 0 兜底（宿主侧降级快照也照常可渲染）。
     */
    fun modRuntimeOverviewFaces(faces: RegisteredFaces): String {
        val pri = faces.events["PRI"] ?: 0
        val normal = faces.events["NORMAL"] ?: 0
        val later = faces.events["LATER"] ?: 0
        val only = faces.events["ONLY"] ?: 0
        return fill(
            modRuntimeOverviewFacesFormat,
            faces.hooks,
            faces.retiredHooks,
            pri,
            normal,
            later,
            only,
            faces.interactions,
            faces.services,
            faces.strings,
            faces.settings,
            faces.images,
        )
    }

    /**
     * 存储段读数格式：`存储 <大小文本> · <键数> 键`。
     *
     * `{0}` 是已经由壳层格式化好的大小文本（[formatStorageBytes]，B/KB/MB 与语言无关），
     * 本表只管串词与占位符，不做数值换算 —— 换算规则留在可单测的纯函数里。
     */
    private val modRuntimeOverviewStorageFormat: String = tr(
        zh = "存储 {0} · {1} 键",
        en = "Storage {0} · {1} keys",
        ja = "ストレージ {0}・{1} キー",
    )

    /** 该包没有存储段时的显式缺数据行：`—` 不是 `0`（0 键是真读到空段，语义不同）。 */
    val modRuntimeOverviewStorageMissing: String = tr(
        zh = "存储 —",
        en = "Storage —",
        ja = "ストレージ —",
    )

    /** 存储段读数（[bytesText] 为壳层格式化好的大小，[keys] 为段内条目数含墓碑位）。 */
    fun modRuntimeOverviewStorage(bytesText: String, keys: Int): String =
        fill(modRuntimeOverviewStorageFormat, bytesText, keys)

    /**
     * 存储数据量偏大时的中性提示行（[ModStorageReadout.overLimit] 为真才出现）。
     *
     * 故意**不写**具体阈值、键数、编码细节：core 的软阈值是可调诊断参数，写死数字会被玩家
     * 读成「硬上限」或「引擎在报错」；这里只讲玩家能感知的后果（存档可能变大）+ 一条可行动建议。
     * 也不出现包标识/存储格式等内部术语 —— 这是玩家可见文案。
     */
    val modRuntimeOverviewStorageOver: String = tr(
        zh = "⚠ 存储数据量偏大，可能让存档变大；建议作者检查写入的数据量。",
        en = "⚠ This mod stores a lot of data, which may make saves larger. " +
            "Consider asking the author to check how much it writes.",
        ja = "⚠ 保存データが多いため、セーブが大きくなる可能性があります。" +
            "作者に書き込み量の確認をおすすめします。",
    )

    /** 设置项读数格式：`设置已改 <项数> 项`（相对作者声明的默认值）。 */
    private val modRuntimeOverviewSettingsChangedFormat: String = tr(
        zh = "设置已改 {0} 项",
        en = "Settings changed: {0}",
        ja = "設定変更 {0} 件",
    )

    /** 会话快照里没有该包设置面时的显式缺数据行：`—` 不是 `0`。 */
    val modRuntimeOverviewSettingsChangedMissing: String = tr(
        zh = "设置已改 —",
        en = "Settings changed: —",
        ja = "設定変更 —",
    )

    /** 设置项读数（[changed] = 相对声明默认已改的项数）。 */
    fun modRuntimeOverviewSettingsChanged(changed: Int): String =
        fill(modRuntimeOverviewSettingsChangedFormat, changed)

    // -----------------------------------------------------------------
    // 已安装的 Mod 列表（批2-3，2026-11-17）：逐个启停
    // 真源 = `mods/rt2/<packageId>.off` 标记文件（ModEnablement）；装配在会话构造期
    // 一次性完成，所以文案必须写清「改完重开一局」，与总闸 / 装载优先级同口径。
    // -----------------------------------------------------------------

    /** 「已安装的 Mod」列表卡标题。 */
    val modManageTitle: String = tr(
        zh = "已安装的 Mod",
        en = "Installed mods",
        ja = "インストール済み Mod",
    )

    /** 列表说明：逐个开关的语义 + 重开一局生效。 */
    val modManageHint: String = tr(
        zh = "逐个开关：停用 = 下次启动不装载这个 Mod，存档里的 Mod 数据保留，随时能再启用。" +
            "点条目展开详情，导入包可以在这里卸载。改完要重开一局才生效。",
        en = "Toggle each mod: off means it is not loaded on the next launch. Its Mod data " +
            "stays in your saves, so you can turn it back on any time. Tap an entry for " +
            "details; imported packs can be uninstalled here. Restart the game to apply.",
        ja = "Mod ごとにオン・オフできます。オフにすると次回起動時に読み込まれません。" +
            "セーブの Mod データは残るので、いつでも戻せます。" +
            "項目をタップすると詳細が開き、インポートしたパックはここで削除できます。" +
            "変更はゲーム再起動後に反映されます。",
    )

    /**
     * 一次性生效时机说明（卡顶，**不逐行重复**）：三种操作三种时机一次说清。
     *
     * 口径与实现证据逐条对齐（改文案先核这三处，别写嘴顺的「立即生效」）：
     * - 启停 / 卸载 = 下次进入游戏生效：装配期才读停用标记（`ModEnablement.enabledOnly`，
     *   `ModRuntimeAssembly.kt:802`）与载体名单（`ModCarrierRegistry.providers`，:46），
     *   本局已装配的会话不变；
     * - 重编 = 编完后重开一局生效：重编只做磁盘原子替换（`ModRecompiler.recompile`，
     *   `ModRecompile.kt:87`），本会话已 attach 的容器不重载；
     * - Mod 设置 = 保存后本局内下一拍生效：写盘成功后入队 `ModSettingsNotifyChannel`，VM
     *   线程页边界 drain 到 `ModRuntimeAssembly.notifySettingsChanged`（:1525）——是页边界
     *   热通知，不是立即生效（Lane CS 用例 `ModSettingsHotTakeEffectEndToEndTest` 钉住）。
     */
    val modManageTakeEffectHint: String = tr(
        zh = "生效时机（一次说清）：逐个开关、卸载 → 下次进入游戏时生效，本局不变；" +
            "改过源文件后点「重新编译」→ 编完后重开一局生效；" +
            "Mod 设置 → 保存后回到游戏、下一次回合推进时生效，不用重开。",
        en = "When changes take effect: toggling a mod or uninstalling → next time you enter the " +
            "game (this session keeps running as-is); tapping Recompile after editing sources → " +
            "restart the game once it finishes; mod settings → apply at the next turn boundary " +
            "after you return to the game, no restart needed.",
        ja = "反映タイミング：Mod のオン・オフやアンインストール → 次回ゲームを開始したとき" +
            "（今回のプレイ中は変わりません）。ソースを編集して「再コンパイル」→ 完了後に" +
            "ゲームを再起動。Mod 設定 → 保存後にゲームへ戻り、次のターン進行時に反映" +
            "（再起動不要）。",
    )

    /** 列表为空时的提示。 */
    val modManageEmpty: String = tr(
        zh = "还没有安装任何 Mod。",
        en = "No mods installed yet.",
        ja = "まだ Mod がインストールされていません。",
    )

    // -----------------------------------------------------------------
    // 已装列表的搜索 + 来源筛选（Lane modsearch）：纯视图过滤，不改 marker、
    // 不写盘、不影响启停/卸载/重试。空结果两态必须分开说（「一个都没装」vs
    // 「筛选后没有匹配」），计数行与列表读同一次 [ModManageScanner.scan]。
    // -----------------------------------------------------------------

    /** 搜索框标签：按显示名 / 包名 / 作者做大小写不敏感包含匹配。 */
    val modManageSearchLabel: String = tr(
        zh = "搜索（名称 / 包名 / 作者）",
        en = "Search (name / package / author)",
        ja = "検索（名前 / パッケージ / 作者）",
    )

    /** 来源筛选的第一档：不筛（全部）。 */
    val modManageFilterAll: String = tr(
        zh = "全部",
        en = "All",
        ja = "すべて",
    )

    /** 筛选后没有任何匹配时的说明（与 [modManageEmpty] 的「一个都没装」分开）。 */
    val modManageFilterEmpty: String = tr(
        zh = "没有匹配的 Mod。换个关键词，或把来源筛选切回「全部」。",
        en = "No matching mods. Try another keyword, or switch the source filter back to All.",
        ja = "一致する Mod がありません。キーワードを変えるか、入手元フィルタを「すべて」に戻してください。",
    )

    /** 计数行模板：筛选结果 N / 共 M（N、M 都来自同一次扫描的已装清单）。 */
    private val modManageFilterCountFormat: String = tr(
        zh = "筛选结果 {0} / 共 {1}",
        en = "Showing {0} of {1}",
        ja = "表示 {0} / 全 {1}",
    )

    /** 计数行：当前可见条数 / 已装总条数。 */
    fun modManageFilterCount(shown: Int, total: Int): String =
        fill(modManageFilterCountFormat, shown, total)

    /** 有包读不出来时的小字前缀（后面由界面拼文件名列表）。 */
    val modManageBrokenPrefix: String = tr(
        zh = "读不出来的包（可重新导入覆盖修复）：",
        en = "Unreadable packs (re-import to fix): ",
        ja = "読み込めないパック（再インポートで修復できます）：",
    )

    /** 来源标签：随包内置的 Kotlin 载体。 */
    val modManageSourceBuiltin: String = tr(
        zh = "内置",
        en = "built-in",
        ja = "内蔵",
    )

    /** 来源标签：编译好的数据包（.erbm + manifest）。 */
    val modManageSourceData: String = tr(
        zh = "数据包",
        en = "data pack",
        ja = "データパック",
    )

    /** 来源标签：玩家从口上导入的包。 */
    val modManageSourceImported: String = tr(
        zh = "导入",
        en = "imported",
        ja = "インポート",
    )

    /** 列表项被停用时的前缀（拼在详情行前面）。 */
    val modManageDisabledTag: String = tr(
        zh = "已停用",
        en = "disabled",
        ja = "無効",
    )

    /** 写启停状态失败（只读存储 / 目录不可写）时的提示。 */
    val modManageToggleFailed: String = tr(
        zh = "没法保存这个 Mod 的启停状态，请检查存储空间或应用存储权限。",
        en = "Could not save this mod's on/off state. Check free space or app storage permission.",
        ja = "この Mod の有効・無効を保存できません。空き容量か保存権限を確認してください。",
    )

    // -----------------------------------------------------------------
    // 第三方代码包信任确认（ADR-013 D3 / task-153 S4b）：只信任 code 下有 .mod 产物的包；
    // 二次确认必须如实说明同进程、无沙箱、等同自行安装的应用内代码，不许淡化风险。
    // -----------------------------------------------------------------

    /** 设置页小节标题。 */
    val modTrustTitle: String = tr(
        zh = "第三方代码包",
        en = "Third-party code packs",
        ja = "サードパーティ製コードパック",
    )

    /** 小节说明：列出什么、什么时候生效、撤销的边界（本局不中途卸载）。 */
    val modTrustHint: String = tr(
        zh = "列出带有已编译代码产物的包。信任后，游戏下次装载时才会运行它的代码；" +
            "未信任的包不装载。撤销只删信任记录，本局已经跑起来的代码不会中途卸载。",
        en = "Lists packs with compiled code artifacts (.mod under code/). A pack's code runs " +
            "only after you trust it and the game loads it next time; untrusted packs are not " +
            "loaded. Revoking trust only deletes the trust record — code already running in this " +
            "session is not unloaded.",
        ja = "コンパイル済みコード成果物（code/ 下の .mod）を持つパックの一覧です。信頼すると" +
            "次回の読み込み時にコードが実行されます。未信頼のパックは読み込まれません。" +
            "信頼を取り消しても信頼記録を削除するだけで、今回のプレイ中のコードは途中で破棄されません。",
    )

    /** 诚实风险说明：同一进程、没有沙箱、等同自行安装的应用内代码；只信任了解来源的包。 */
    val modTrustHonestNote: String = tr(
        zh = "请注意：这些代码与游戏在同一个进程里运行，没有沙箱隔离，会继承应用已有的全部权限" +
            "（读写存储、网络等）；风险等同你自行安装的应用内代码，崩溃会带走整局游戏。" +
            "只信任你了解来源的包。",
        en = "Warning: this code runs in the same process as the game, with no sandbox. It " +
            "inherits every permission the app already has (storage, network, and so on). The " +
            "risk is the same as installing in-app code yourself, and a crash can take down the " +
            "whole session. Trust only packs whose source you understand.",
        ja = "注意：このコードはゲームと同じプロセスで動作し、サンドボックスはありません。" +
            "アプリが持つ権限（ストレージやネットワークなど）をすべて継承します。" +
            "リスクは自分でアプリ内コードを導入するのと同じで、クラッシュするとプレイ全体が落ちる" +
            "可能性があります。出所を理解しているパックだけを信頼してください。",
    )

    /** 没有任何 code 产物包时的空态。 */
    val modTrustEmpty: String = tr(
        zh = "没有发现第三方代码包。",
        en = "No third-party code packs found.",
        ja = "サードパーティ製コードパックは見つかりませんでした。",
    )

    /** 信任态：已信任。 */
    val modTrustStateTrusted: String = tr(
        zh = "已信任",
        en = "Trusted",
        ja = "信頼済み",
    )

    /** 信任态：未信任（下次装载不运行）。 */
    val modTrustStateUntrusted: String = tr(
        zh = "未信任",
        en = "Untrusted",
        ja = "未信頼",
    )

    /** 行内按钮：请求信任（点了先弹诚实二次确认，不直接写标记）。 */
    val modTrustGrantButton: String = tr(
        zh = "信任此包…",
        en = "Trust this pack…",
        ja = "このパックを信頼…",
    )

    /** 行内按钮：撤销信任（即时删标记，下次装载重新要求确认）。 */
    val modTrustRevokeButton: String = tr(
        zh = "撤销信任",
        en = "Revoke trust",
        ja = "信頼を取り消す",
    )

    /** 二次确认弹窗标题。 */
    val modTrustConfirmTitle: String = tr(
        zh = "确认信任这个代码包？",
        en = "Trust this code pack?",
        ja = "このコードパックを信頼しますか？",
    )

    /** 二次确认弹窗确认按钮。 */
    val modTrustConfirmOk: String = tr(
        zh = "确认信任",
        en = "Trust",
        ja = "信頼する",
    )

    /** 信任标记写 / 删失败时的提示。 */
    val modTrustWriteFailed: String = tr(
        zh = "没能更新信任记录，请检查存储空间或应用存储权限。",
        en = "Could not update the trust record. Check free space or app storage permission.",
        ja = "信頼記録を更新できませんでした。空き容量か保存権限を確認してください。",
    )

    /** 行读数：包名 + 产物个数。 */
    private val modTrustRowReadoutFormat: String = tr(
        zh = "「{0}」· 代码产物 {1} 个",
        en = "\"{0}\" · {1} code artifact(s)",
        ja = "「{0}」・コード成果物 {1} 件",
    )

    /** 行读数（[packageId] 为包名，[artifactCount] 为合法 .mod 产物数）。 */
    fun modTrustRowReadout(packageId: String, artifactCount: Int): String =
        fill(modTrustRowReadoutFormat, packageId, artifactCount)

    /** 二次确认正文：说明风险来源（同进程 / 无沙箱 / 等同自行安装 / 崩溃带走整局）。 */
    private val modTrustConfirmBodyFormat: String = tr(
        zh = "「{0}」是别人编译好的代码。信任后，它会在下次进入游戏时与游戏在同一个进程里运行：" +
            "没有沙箱隔离、继承应用已有的权限，风险等同你自行安装的应用内代码，崩溃会带走整局。" +
            "只在你了解它的来源时继续。",
        en = "\"{0}\" is code compiled by someone else. Once trusted, it runs alongside the " +
            "game on the next launch: same process, no sandbox, inheriting the app's permissions. " +
            "The risk is the same as installing in-app code yourself, and a crash can take down " +
            "the whole session. Continue only if you know where it came from.",
        ja = "「{0}」は他人がコンパイルしたコードです。信頼すると次回の起動時にゲームと同じプロセスで" +
            "実行されます。サンドボックスはなく、アプリの権限を継承します。" +
            "リスクは自分でアプリ内コードを導入するのと同じで、クラッシュするとプレイ全体が落ちます。" +
            "出所を理解している場合だけ続行してください。",
    )

    /** 二次确认正文（[packageId] 为待信任包名）。 */
    fun modTrustConfirmBody(packageId: String): String = fill(modTrustConfirmBodyFormat, packageId)

    /** 撤销信任的二次确认标题（撤销也是安全面动作，点一下按钮不能直接删标记）。 */
    val modTrustRevokeConfirmTitle: String = tr(
        zh = "撤销这个代码包的信任？",
        en = "Revoke trust for this code pack?",
        ja = "このコードパックの信頼を取り消しますか？",
    )

    /** 撤销信任的二次确认按钮。 */
    val modTrustRevokeConfirmOk: String = tr(
        zh = "确认撤销",
        en = "Revoke",
        ja = "取り消す",
    )

    /** 撤销确认正文：删标记 + 下次装载回待确认 + 本局已装载的代码不中途卸载。 */
    private val modTrustRevokeConfirmBodyFormat: String = tr(
        zh = "「{0}」的信任记录会被删除，下次进入游戏时它回到「未信任」，必须重新确认才会运行。" +
            "本局已经装载的代码不会中途卸载。",
        en = "\"{0}\" will lose its trust record. The next time you enter the game it goes back " +
            "to untrusted and must be confirmed again before it runs. Code already loaded in " +
            "this session is not unloaded.",
        ja = "「{0}」の信頼記録を削除します。次回ゲーム開始時は「未信頼」に戻り、再確認するまで" +
            "実行されません。今回のプレイで読み込み済みのコードは途中で破棄されません。",
    )

    /** 撤销确认正文（[packageId] 为待撤销包名）。 */
    fun modTrustRevokeConfirmBody(packageId: String): String =
        fill(modTrustRevokeConfirmBodyFormat, packageId)

    /** 确认信任成功的 toast。 */
    private val modTrustGrantedFormat: String = tr(
        zh = "已信任「{0}」，下次进入游戏时生效。",
        en = "Trusted \"{0}\". It will run the next time you enter the game.",
        ja = "「{0}」を信頼しました。次回ゲーム開始時に反映されます。",
    )

    /** 确认信任成功提示（[packageId] 为包名）。 */
    fun modTrustGranted(packageId: String): String = fill(modTrustGrantedFormat, packageId)

    /** 撤销信任成功的 toast：标记已删，下次装载会重新要求确认。 */
    private val modTrustRevokedFormat: String = tr(
        zh = "已撤销「{0}」的信任，信任记录已删除，下次装载会重新要求确认。",
        en = "Revoked trust for \"{0}\". The trust record is deleted; the next load will ask again.",
        ja = "「{0}」の信頼を取り消しました。信頼記録を削除したので、次回読み込み時に再確認します。",
    )

    /** 撤销信任成功提示（[packageId] 为包名）。 */
    fun modTrustRevoked(packageId: String): String = fill(modTrustRevokedFormat, packageId)

    // -----------------------------------------------------------------
    // 第三方代码包概览（ADR-013 路 B 玩家可见面 / task-162，task-191 补总闸开关）：
    // 总闸开关 + 每包读数（版本数 / 体积 / 信任态）。本段除总闸开关的确认文案外不含动作；
    // 开着总闸跑第三方机器码这件事必须在二次确认里讲清后果，不能只写「打开开关」。
    // 总闸关必须整块明说「未开启」，否则空表会被误读成「本机没装」。
    // -----------------------------------------------------------------

    /** 「第三方代码包概览」小节标题。 */
    val modCodeOverviewTitle: String = tr(
        zh = "第三方代码包概览",
        en = "Third-party code pack overview",
        ja = "サードパーティ製コードパック概要",
    )

    /** 小节说明：本卡读数 + 本卡总闸开关；信任变更在下方「第三方代码包」卡操作。 */
    val modCodeOverviewHint: String = tr(
        zh = "读数：列出本机已落地的代码产物与信任状态；装载总开关就在本卡，信任变更在下方卡片操作。",
        en = "Readout: lists the code artifacts already on this device and their trust state. " +
            "The master switch is on this card; trust changes are handled in the card below.",
        ja = "読み取り：端末に配置済みのコード成果物と信頼状態を一覧します。" +
            "マスタースイッチはこのカードにあり、信頼の変更は下のカードで行います。",
    )

    /** 总闸开：已信任的包会在下次装载时运行其代码。 */
    val modCodeOverviewGateOn: String = tr(
        zh = "总开关：已开启 — 已信任的代码包会在下次装载时运行。",
        en = "Master switch: on — trusted packs will run on the next load.",
        ja = "マスタースイッチ：オン — 信頼済みのコードパックは次回の読み込み時に実行されます。",
    )

    /** 总闸关：必须明说不会装载任何第三方代码（防「空表 = 没装」误读）。 */
    val modCodeOverviewGateOff: String = tr(
        zh = "总开关：未开启 — 不会装载任何第三方代码，本机的代码包都不会被运行。",
        en = "Master switch: off — no third-party code is loaded, so none of the packs on this " +
            "device run.",
        ja = "マスタースイッチ：オフ — サードパーティ製コードは一切読み込まれません。" +
            "端末内のコードパックも実行されません。",
    )

    /** 总闸开但本机没有任何代码包时的显式空态。 */
    val modCodeOverviewEmpty: String = tr(
        zh = "本机还没有第三方代码包。",
        en = "No third-party code packs on this device yet.",
        ja = "この端末にはまだサードパーティ製コードパックがありません。",
    )

    /** 读盘失败：照「缺数据显式 —」口径，不静默显示成 0 个包。 */
    val modCodeOverviewUnreadable: String = tr(
        zh = "没能读取代码产物目录：体积与信任状态显示为「—」。请检查存储空间或应用存储权限。",
        en = "Could not read the code artifact directory. Size and trust state are shown as " +
            "\"—\". Check free space or app storage permission.",
        ja = "コード成果物ディレクトリを読み取れませんでした。サイズと信頼状態は「—」と表示されます。" +
            "空き容量か保存権限を確認してください。",
    )

    /** 行读数：包名 + 版本数 + 体积（体积口径 = 既有 formatStorageBytes）。 */
    private val modCodeOverviewRowFormat: String = tr(
        zh = "「{0}」· {1} 个版本 · {2}",
        en = "\"{0}\" · {1} version(s) · {2}",
        ja = "「{0}」・{1} バージョン・{2}",
    )

    /** 行读数（[packageId] 包名，[versions] 已落地版本数，[size] 已格式化体积如 "1.5 KB"）。 */
    fun modCodeOverviewRow(packageId: String, versions: Int, size: String): String =
        fill(modCodeOverviewRowFormat, packageId, versions, size)

    // -----------------------------------------------------------------
    // 第三方代码包装载总闸的开关与二次确认（task-191）：开启 = 允许本机第三方机器码入库执行。
    // 确认正文必须三件事都说清：① 同进程同权限、无沙箱，出错崩溃会带走整局；② 只在了解来源时开启；
    // ③ 下次进入游戏才生效（本局不受影响、已加载的代码包也不会被卸载）。
    // -----------------------------------------------------------------

    /** 总闸开关行标题（开关本体在「第三方代码包概览」卡内）。 */
    val modCodeLoaderSwitchTitle: String = tr(
        zh = "启用第三方代码包装载",
        en = "Enable third-party code packs",
        ja = "サードパーティ製コードパックの読み込みを有効にする",
    )

    /** 总闸开关行说明：要二次确认、下次进入游戏生效、关掉后本机一个包都不跑。 */
    val modCodeLoaderSwitchHint: String = tr(
        zh = "开启需要二次确认；下次进入游戏才生效，关闭后本机代码包都不会运行。",
        en = "Turning it on needs a second confirmation. It takes effect the next time the game " +
            "starts; when off, no code pack on this device runs.",
        ja = "オンにするにはもう一度確認が必要です。次回ゲーム起動時から有効になり、" +
            "オフにすると端末内のコードパックは実行されません。",
    )

    /** 总闸开启前的二次确认标题。 */
    val modCodeLoaderConfirmTitle: String = tr(
        zh = "确定开启第三方代码包装载吗？",
        en = "Turn on third-party code pack loading?",
        ja = "サードパーティ製コードパックの読み込みを有効にしますか？",
    )

    /** 二次确认正文：同进程同权限无沙箱 / 只在了解来源时开 / 下次进入游戏才生效。 */
    val modCodeLoaderConfirmBody: String = tr(
        zh = "第三方代码包会以与本应用相同的权限、在同一进程内运行，没有沙箱隔离；" +
            "一旦它出错，崩溃会带走整局游戏。请只在了解代码来源时开启。" +
            "开关要下次进入游戏时才生效，本局不受影响，已加载的代码包也不会被卸载。",
        en = "Third-party code packs run in this app's own process with the same permissions and " +
            "no sandbox. If one misbehaves, the crash can take down the whole game session. " +
            "Only turn this on if you know where the code comes from. The switch takes effect the " +
            "next time the game starts: the current session is unaffected, and code packs already " +
            "loaded stay loaded.",
        ja = "サードパーティ製コードパックは本アプリと同じ権限・同じプロセスで動作し、" +
            "サンドボックスはありません。不具合があるとクラッシュしてゲーム全体が失われることがあります。" +
            "コードの入手元が分かる場合にのみ有効にしてください。" +
            "このスイッチは次回ゲーム起動時から有効で、現在のプレイには影響せず、" +
            "読み込み済みのコードパックもアンロードされません。",
    )

    /** 二次确认的确认按钮（Warn 色，防手滑）。 */
    val modCodeLoaderConfirmOk: String = tr(
        zh = "开启",
        en = "Turn on",
        ja = "有効にする",
    )

    /** 总闸没写进盘时的红字：明说设置没生效，不静默当成功。 */
    val modCodeLoaderWriteFailed: String = tr(
        zh = "开关没能保存成功，设置没有生效。请稍后再试。",
        en = "Could not save the switch, so the setting did not change. Please try again.",
        ja = "スイッチを保存できず、設定は反映されませんでした。もう一度お試しください。",
    )

    // -----------------------------------------------------------------
    // 第三方代码包的代码态提示（task-179，task-187 补多版本残留态）：「装了但没生效」要在
    // **装了它的那一行**上明说。五态（多版本残留 > 身份损坏 > 总闸关 > 未信任 > 已启用）
    // 与 ModCodeGateHint 的裁决同序（多版本与身份损坏同属「只能重导」，见该文件 KDoc 的理由）；
    // 句式复用 modManageDetailLine，本段只加状态词、不加句式，本表不参与裁决。
    // -----------------------------------------------------------------

    /** Mod 管理行的读数标签：这个包的第三方代码现在是什么状态。 */
    val modManageCodeLabel: String = tr(
        zh = "代码",
        en = "code",
        ja = "コード",
    )

    /** 代码态：总闸开着、已信任、身份完好 ⇒ 下次装载就运行。 */
    val modCodeStateEnabled: String = tr(
        zh = "已启用（下次装载时运行）",
        en = "enabled (runs on the next load)",
        ja = "有効（次回の読み込み時に実行されます）",
    )

    /** 代码态：运行总闸关着 ⇒ 本机任何第三方代码都不会跑，不是这个包单独的问题。 */
    val modCodeStateGateOff: String = tr(
        zh = "未开启（运行总开关关着，不会运行）",
        en = "off (the runtime master switch is off, so nothing runs)",
        ja = "オフ（実行のマスタースイッチが切れているため実行されません）",
    )

    /** 代码态：盘上有代码产物但玩家还没信任 ⇒ 信任后下次装载才跑。 */
    val modCodeStateUntrusted: String = tr(
        zh = "待信任（信任后下次装载才运行）",
        en = "waiting for trust (runs after you trust it, on the next load)",
        ja = "信頼待ち（信頼すると次回の読み込み時に実行されます）",
    )

    /** 代码态：代码身份信息坏了 ⇒ 改总闸 / 补信任都救不回来，只能重新导入这个包。 */
    val modCodeStateCorrupt: String = tr(
        zh = "身份信息损坏（改开关或补信任都没用，需要重新导入这个包）",
        en = "identity data damaged (toggling settings or trusting it will not help; import this pack again)",
        ja = "身元情報が壊れています（スイッチや信頼では直りません。このパックを再インポートしてください）",
    )

    /** 代码态：盘上还留着多份代码版本 ⇒ 装载端拒绝猜版本，重新导入会自动清成只留最新的一份。 */
    val modCodeStateMultipleVersions: String = tr(
        zh = "多版本残留（装载时不会运行，重新导入这个包会自动只留最新的一份）",
        en = "multiple versions left over (it will not run when loaded; import this pack again " +
            "to keep only the newest)",
        ja = "複数バージョンが残存（読み込み時は実行されません。このパックを再インポートすると" +
            "最新の1つだけになります）",
    )

    /** 代码态：包要求的引擎比本机新 ⇒ 装载闸拒载，改开关 / 补信任都救不回来，只能更新应用。 */
    val modCodeStateEngineTooOld: String = tr(
        zh = "需要更新引擎（这个包要求的引擎比本机新，更新应用后再试）",
        en = "engine update needed (this pack requires a newer engine; update the app and retry)",
        ja = "エンジンの更新が必要（このパックは本機より新しいエンジンを要求しています。" +
            "アプリを更新してから再試行してください）",
    )

    // -----------------------------------------------------------------
    // 第三方代码包概览的「最近一次装载结论」（task-195）：每包行在体积 / 信任读数后追加一行
    // 「装载结论：…」。结论只在本次进程内有效（装配期发布、设置页只读，不落盘），拿不到记录
    // 就显式「—」，不许把「没记录」写成「已装载」或「失败」。
    // -----------------------------------------------------------------

    /** 装载结论行标签（与 [modCodeLoadLabel] 组合成「标签：值」句式）。 */
    val modCodeLoadLabel: String = tr(zh = "装载结论", en = "load result", ja = "読み込み結果")

    /** 缺数据：本次启动还没有任何装载结论（总闸关 / 设置页在装配前打开 / 本次没跑到该包）。 */
    val modCodeLoadUnknown: String = tr(
        zh = "—（本次启动还没有装载记录）",
        en = "— (no load recorded this launch)",
        ja = "—（今回の起動では読み込み記録がありません）",
    )

    /** 本次装载已生效（载体已进运行名单）。 */
    val modCodeLoadLoaded: String = tr(zh = "已装载", en = "loaded", ja = "読み込み済み")

    /** 本次装载未生效：本行只说结论，具体原因见诊断导出（不在这里猜原因）。 */
    val modCodeLoadSkipped: String = tr(
        zh = "本次未装载（原因见诊断导出）",
        en = "not loaded this launch (see the diagnostics export for why)",
        ja = "今回の読み込みは未反映（理由は診断エクスポートを参照）",
    )

    /** 引擎太旧结论的版本缺口句式：包要求的引擎版本 + 本机版本（版本号不算内部术语）。 */
    private val modCodeLoadEngineTooOldFormat: String = tr(
        zh = "该包要求引擎 v{0}，本机 v{1}，请更新应用后再试",
        en = "this pack requires engine v{0}; this build is v{1} — update the app and retry",
        ja = "このパックはエンジン v{0} が必要です（本機は v{1}）。アプリを更新してから再試行してください",
    )

    /** 引擎太旧结论（[required] 包要求的引擎版本，[current] 本机引擎版本）。 */
    fun modCodeLoadEngineTooOld(required: Int, current: Int): String =
        fill(modCodeLoadEngineTooOldFormat, required, current)

    // -----------------------------------------------------------------
    // 家督继任（task-160 / Lane HB）：mod-family 手动继任面的玩家入口。
    // 数据面 = ModHeirRequests（设置页 → VM 线程页边界）+ ModHeirFeed（会话 → 设置页快照）；
    // 本段只放文案。规则名 / 候选 / 已指定三行都是「真值显示」，读不到就显示破折号，不猜。
    // -----------------------------------------------------------------

    /** 「家督继任」小节标题（嵌在 Mod 管理卡尾，只在 family 载体启用时渲染）。 */
    val modHeirSectionTitle: String = tr(zh = "家督继任", en = "Family succession", ja = "家督の後継")

    /** 小节说明：点了不会立刻变——回到游戏的下一个回合边界才兑现（与设置热通知同拍）。 */
    val modHeirHint: String = tr(
        zh = "手动指定仅对本局有效：回到游戏后，下一个回合边界生效。",
        en = "Manual picks apply to this session only; they take effect at the next turn " +
            "boundary after you return to the game.",
        ja = "手動指定はこのセッション内でのみ有効です。ゲームに戻って次のターン境界で反映されます。",
    )

    /** 规则行标签（与 [modHeirModeLine] 组合成一行）。 */
    val modHeirModeLabel: String = tr(zh = "继承规则", en = "Rule", ja = "継承規則")

    /** 规则行格式（标签：值）。 */
    private val modHeirModeLineFormat: String = tr(zh = "{0}：{1}", en = "{0}: {1}", ja = "{0}：{1}")

    /** 规则行（[label] 为标签、[mode] 为规则名标签）。 */
    fun modHeirModeLine(label: String, mode: String): String = fill(modHeirModeLineFormat, label, mode)

    /** 规则标签：手动指定（只有这一档允许玩家点选候选）。 */
    val modHeirModeManual: String = tr(zh = "手动指定", en = "Manual", ja = "手動指定")

    /** 规则标签：长嗣优先（自动，玩家只能看不能改）。 */
    val modHeirModeEldest: String = tr(zh = "长嗣优先（自动）", en = "Eldest first (auto)", ja = "長子優先（自動）")

    /** 规则标签：幼嗣优先（自动，玩家只能看不能改）。 */
    val modHeirModeYoungest: String = tr(zh = "幼嗣优先（自动）", en = "Youngest first (auto)", ja = "末子優先（自動）")

    /** 规则标签缺省（未知规则名 / 本局还没读到真值）：破折号，不猜。 */
    val modHeirModeUnknown: String = tr(zh = "—", en = "—", ja = "—")

    /** 候选区标签。 */
    val modHeirCandidatesLabel: String = tr(zh = "候选继任者", en = "Candidates", ja = "後継候補")

    /** 候选行格式（作者面暂不出显示名，用稳定身份原文）。 */
    private val modHeirCandidateFormat: String = tr(zh = "#{0}", en = "#{0}", ja = "#{0}")

    /** 候选行（[raw] 为 EntityId.raw）。 */
    fun modHeirCandidate(raw: Long): String = fill(modHeirCandidateFormat, raw)

    /**
     * 带名字的候选行格式（task-171）：名字在前、稳定身份紧跟，玩家既认得出是谁也能对上日志里的 raw。
     * 名字缺省时调用方仍走 [modHeirCandidate]（只显示 `#raw`），不出现「（#id）」空壳。
     */
    private val modHeirCandidateNamedFormat: String =
        tr(zh = "{0}（#{1}）", en = "{0} (#{1})", ja = "{0}（#{1}）")

    /** 带名字的候选行（[displayName] = 既有 `title` 的只读投影，[raw] 为 EntityId.raw）。 */
    fun modHeirCandidateNamed(displayName: String, raw: Long): String =
        fill(modHeirCandidateNamedFormat, displayName, raw)

    /**
     * 候选行文本（task-171）：「有名字 ⇒ 名字（#id），缺名 ⇒ 只显示 #id」的二选一口径只此一份。
     * 设置页候选列表与「已指定」行共用；空串 / 纯空白名字视为缺名，不出现空壳「（#id）」。
     */
    fun modHeirCandidateLabel(displayName: String?, raw: Long): String {
        val name = displayName?.takeIf { it.isNotBlank() } ?: return modHeirCandidate(raw)
        return modHeirCandidateNamed(name, raw)
    }

    /** 「未指定」态（手动规则下还没有指定继任者）。 */
    val modHeirPendingNone: String = tr(zh = "未指定", en = "Not chosen", ja = "未指定")

    /** 已指定行格式。 */
    private val modHeirPendingFormat: String = tr(zh = "已指定：{0}", en = "Chosen: {0}", ja = "指定済み：{0}")

    /** 已指定行（[candidate] 为候选行文本）。 */
    fun modHeirPending(candidate: String): String = fill(modHeirPendingFormat, candidate)

    /** 无值占位（无候选 / 无快照 / 规则未知）：破折号，明确「没有数据」而不是留白。 */
    val modHeirNoneValue: String = tr(zh = "—", en = "—", ja = "—")

    /** 无候选说明（本局没有被作者面认可的候选）。 */
    val modHeirNoCandidates: String = tr(
        zh = "本局没有可选的继任者。",
        en = "No selectable heirs in this session.",
        ja = "このセッションに選べる後継者がいません。",
    )

    /** 指定按钮（点候选行即提交，不弹确认：非破坏性，下一拍快照回显真值）。 */
    val modHeirChooseButton: String = tr(zh = "指定为继任者", en = "Choose as successor", ja = "後継者に指定")

    /** 清除指定按钮（会把手动指定交回当前规则：破坏性，二次确认）。 */
    val modHeirClearButton: String = tr(zh = "清除指定", en = "Clear choice", ja = "指定を解除")

    /** 指定提交回执（本拍只入队，页边界才调作者面）。 */
    val modHeirChooseQueued: String = tr(
        zh = "已提交：回到游戏后下一个回合边界生效。",
        en = "Submitted: takes effect at the next turn boundary after you return to the game.",
        ja = "送信しました。ゲームに戻って次のターン境界で反映されます。",
    )

    /** 清除提交回执。 */
    val modHeirClearQueued: String = tr(
        zh = "已提交清除：回到游戏后下一个回合边界生效。",
        en = "Clear submitted: takes effect at the next turn boundary after you return to the game.",
        ja = "解除を送信しました。ゲームに戻って次のターン境界で反映されます。",
    )

    /** 清除二次确认标题。 */
    val modHeirClearConfirmTitle: String = tr(
        zh = "清除指定的继任者？",
        en = "Clear the chosen successor?",
        ja = "指定した後継者を解除しますか？",
    )

    /** 清除二次确认正文：说明清除 = 回到自动规则，而不是「这局没有继任者」。 */
    val modHeirClearConfirmBody: String = tr(
        zh = "清除后本局回到当前继承规则自动选择；回到游戏后下一个回合边界生效。",
        en = "After clearing, this session falls back to the current rule. It takes effect at " +
            "the next turn boundary after you return to the game.",
        ja = "解除すると、このセッションは現在の継承規則による自動選択に戻ります。" +
            "ゲームに戻って次のターン境界で反映されます。",
    )

    /** 清除确认按钮。 */
    val modHeirClearConfirmOk: String = tr(zh = "确认清除", en = "Clear", ja = "解除する")

    /** 规则非 manual 时的说明：不是「你没选」，而是当前规则不让选。 */
    val modHeirManualOnlyHint: String = tr(
        zh = "当前继承规则不是「手动指定」，先在本 Mod 的设置里改成手动才能指定。",
        en = "The current rule is not \"Manual\". Switch it in this mod's settings before choosing.",
        ja = "現在の継承規則は「手動指定」ではありません。この Mod の設定で手動に切り替えてから指定してください。",
    )

    /** 快照未就绪说明（本局还没有页边界回写过真值：刚进游戏 / 刚打开设置）。 */
    val modHeirUnavailable: String = tr(
        zh = "尚未就绪：回到游戏推进一回合后再看。",
        en = "Not ready yet: advance one turn in the game and check again.",
        ja = "まだ準備できていません。ゲームで1ターン進めてから確認してください。",
    )

    // -----------------------------------------------------------------
    // Mod 版本读数（VS-1，2026-11-18）：作者面 API 版本 / 引擎版本 / 数据版本缺口。
    // 引擎读数只在段落首行显示一次；每行只显示该包自己声明的那一档（读不到就不显示）。
    // -----------------------------------------------------------------

    /** 引擎读数（段落首行一次）：本体内核支持的 mod API 版本。 */
    private val modVersionEngineFormat: String = tr(
        zh = "引擎 mod API v{0}",
        en = "Engine mod API v{0}",
        ja = "エンジン mod API v{0}",
    )

    /** 引擎读数行（[engineApiVersion] 为本体内核的 `ModApi.VERSION`）。 */
    fun modVersionEngine(engineApiVersion: Int): String = fill(modVersionEngineFormat, engineApiVersion)

    /** 每行作者面声明：`API 版本 v13`。 */
    private val modVersionApiFormat: String = tr(
        zh = "API 版本 v{0}",
        en = "Mod API version v{0}",
        ja = "API バージョン v{0}",
    )

    /** 作者面 API 版本读数行（[apiVersion] 为 mod 声明的 `EraMod.apiVersion`）。 */
    fun modVersionApi(apiVersion: Int): String = fill(modVersionApiFormat, apiVersion)

    /** 不兼容警告：声明版本高于引擎，装配期会被版本门拒载。 */
    private val modVersionIncompatibleFormat: String = tr(
        zh = "不兼容（需要 API v{0}，引擎 v{1}）",
        en = "Incompatible (needs API v{0}, engine v{1})",
        ja = "非対応（API v{0} が必要、エンジンは v{1} まで）",
    )

    /** 不兼容警告行（[requiredApiVersion] 为 mod 声明，[engineApiVersion] 为引擎）。 */
    fun modVersionIncompatible(requiredApiVersion: Int, engineApiVersion: Int): String =
        fill(modVersionIncompatibleFormat, requiredApiVersion, engineApiVersion)

    /** 数据版本缺口：`数据版本 v1→v2`（存档侧车记录 → mod 声明）。 */
    private val modVersionDataFormat: String = tr(
        zh = "数据版本 v{0}→v{1}",
        en = "Data version v{0}→v{1}",
        ja = "データ版 v{0}→v{1}",
    )

    /** 数据版本缺口行（[storedDataVersion] 侧车记录、[declaredDataVersion] mod 声明）。 */
    fun modVersionData(storedDataVersion: Int, declaredDataVersion: Int): String =
        fill(modVersionDataFormat, storedDataVersion, declaredDataVersion)

    /** 启用被版本门挡住时的提示：不写 marker、直接说清要哪一版。 */
    private val modVersionEnableBlockedFormat: String = tr(
        zh = "无法启用：这个 Mod 需要 API v{0}，引擎只到 v{1}。",
        en = "Can't enable: this mod needs API v{0}, but the engine only provides v{1}.",
        ja = "有効にできません：この Mod は API v{0} を必要としますが、エンジンは v{1} までです。",
    )

    /** 启用被挡提示行（[requiredApiVersion] 为 mod 声明，[engineApiVersion] 为引擎）。 */
    fun modVersionEnableBlocked(requiredApiVersion: Int, engineApiVersion: Int): String =
        fill(modVersionEnableBlockedFormat, requiredApiVersion, engineApiVersion)

    /**
     * 装载失败小节标题（内容是本会话装配链上报的失败，不是盘面扫描结果）。
     *
     * 口径随诊断面按会话归零（`ModManageDiagnostics.reset()` 在 `GameHost.assembleSession`
     * 顶部调用）：行只代表**本次会话**，上一局修好或禁用过的包不会再出现在这里；所以标题不能
     * 写「上次启动」——那会让玩家以为这些行还反映当前盘面状态。
     */
    val modManageFailureHeading: String = tr(
        zh = "本会话装载失败：",
        en = "Failed to load in this session: ",
        ja = "今回のセッションの読み込み失敗：",
    )

    /** 装载失败单行：显示名（或包名）+ 失败摘要；界面逐条 fill。 */
    private val modManageFailureLineFormat: String = tr(
        zh = "{0}：{1}",
        en = "{0}: {1}",
        ja = "{0}：{1}",
    )

    /** 装载失败单行（[modName] 为标题，[reason] 为异常摘要）。 */
    fun modManageFailureLine(modName: String, reason: String): String =
        fill(modManageFailureLineFormat, modName, reason)

    /** 详情：包名标签。 */
    val modManageDetailPackageId: String = tr(zh = "包名", en = "Package name", ja = "パッケージ名")

    /** 详情：版本标签。 */
    val modManageDetailVersion: String = tr(zh = "版本", en = "Version", ja = "バージョン")

    /** 详情：作者标签。 */
    val modManageDetailAuthor: String = tr(zh = "作者", en = "Author", ja = "製作者")

    /** 详情：来源标签。 */
    val modManageDetailSource: String = tr(zh = "来源", en = "Source", ja = "入手元")

    /** 详情：说明标签。 */
    val modManageDetailDescription: String = tr(zh = "说明", en = "Description", ja = "説明")

    /** 详情单行：标签 + 值（界面逐条 fill，避免把分隔符内联进组合代码）。 */
    private val modManageDetailLineFormat: String = tr(
        zh = "{0}：{1}",
        en = "{0}: {1}",
        ja = "{0}：{1}",
    )

    /** 详情单行（[label] 标签、[value] 值）。 */
    fun modManageDetailLine(label: String, value: String): String =
        fill(modManageDetailLineFormat, label, value)

    /** 详情：说明为空的占位。 */
    val modManageDescriptionEmpty: String = tr(
        zh = "（没有填写说明）",
        en = "(no description)",
        ja = "（説明なし）",
    )

    /**
     * 详情：数据包已配对（扩展包文件在场）。
     *
     * 与「装载失败」是**两种不同读数**：这里只说盘面配对状态，不推断运行期能不能装载。
     */
    val modManageModulePaired: String = tr(
        zh = "扩展包文件已就位（数据包已配对）",
        en = "Pack file is in place (data pack paired)",
        ja = "拡張パックファイルあり（データパックは揃っています）",
    )

    /**
     * 详情：导入包缺扩展包文件（半成品，装配期不会被当作模块装载）。
     *
     * 与「装载失败」分开报：这是导入产物配对问题，重新导入即可修复，不是 mod 代码/声明出错。
     */
    val modManageModuleMissing: String = tr(
        zh = "缺少扩展包文件（只有包信息，装载不会生效；重新导入可修复）",
        en = "Pack file is missing (pack info only, so it will not load; re-import to fix)",
        ja = "拡張パックファイルがありません（パック情報のみのため読み込まれません。再インポートで修復できます）",
    )

    /** 详情：内置载体没有独立扩展包文件的说明。 */
    val modManageModuleBuiltin: String = tr(
        zh = "随游戏本体提供，没有单独的扩展包文件",
        en = "Ships with the game; has no standalone pack file",
        ja = "本体に含まれるため、独立した拡張パックファイルはありません",
    )

    /**
     * 条目状态：缺依赖体检（ModDX2-C，对标 LOOT/SMAPI 交互警告）——`requires` 声明的前置
     * 包既不在其余已装 manifest 里、也不是随包内置载体。
     *
     * {0} = 缺失包名清单（扫描声明序，界面用 [listSeparator] 拼好再灌）；盘面静态事实，
     * 不随启停变，也不含版本区间裁决（那是链接期的活）。
     */
    private val modMissingRequiresFormat: String = tr(
        zh = "缺前置包：{0}（装上后重启生效）",
        en = "Missing dependency: {0} (install it and restart)",
        ja = "前置パッケージが未導入：{0}（インストール後に再起動で有効）",
    )

    /** 缺依赖行（[deps] 为缺失包名清单，扫描声明序、[listSeparator] 拼接）。 */
    fun modMissingRequires(deps: String): String = fill(modMissingRequiresFormat, deps)

    /** 条目展开/收起提示。 */
    val modManageExpandHint: String = tr(
        zh = "点条目展开详情",
        en = "Tap an entry for details",
        ja = "項目をタップすると詳細が開きます",
    )

    /** 卸载按钮文字。 */
    val modManageUninstall: String = tr(zh = "卸载", en = "Uninstall", ja = "アンインストール")

    /** 卸载二次确认标题（防误删导入包）。 */
    val modManageUninstallConfirmTitle: String = tr(
        zh = "确认卸载",
        en = "Confirm uninstall",
        ja = "アンインストールの確認",
    )

    /** 卸载二次确认正文（点名要删哪些文件、能否恢复、原始文件不动）。 */
    private val modManageUninstallConfirmBodyFormat: String = tr(
        zh = "要卸载「{0}」吗？会删除这个包的扩展包文件、包信息、源文件与停用状态，" +
            "之后只能重新导入恢复；mods 目录里的原始文件不会删。",
        en = "Uninstall \"{0}\"? This deletes its pack file, pack info, source files and the disabled state; " +
            "the only way back is re-importing. The original files in the mods folder are kept.",
        ja = "「{0}」をアンインストールしますか？拡張パックファイル、パック情報、ソースファイルと無効状態を削除します。" +
            "元に戻すには再インポートが必要です。mods フォルダーの元ファイルは残ります。",
    )

    /** 卸载二次确认正文（[modName] 为标题）。 */
    fun modManageUninstallConfirmBody(modName: String): String =
        fill(modManageUninstallConfirmBodyFormat, modName)

    /** 卸载失败提示（文件被占用 / 权限不足）。 */
    private val modManageUninstallFailedFormat: String = tr(
        zh = "卸载「{0}」失败，有文件删不掉，请检查存储权限。",
        en = "Could not uninstall \"{0}\"; some files could not be deleted. Check storage permission.",
        ja = "「{0}」の削除に失敗しました。削除できないファイルがあります。保存権限を確認してください。",
    )

    /** 卸载失败提示（[modName] 为标题）。 */
    fun modManageUninstallFailed(modName: String): String =
        fill(modManageUninstallFailedFormat, modName)

    /** 卸载成功提示。 */
    private val modManageUninstallDoneFormat: String = tr(
        zh = "已卸载「{0}」。",
        en = "Uninstalled \"{0}\".",
        ja = "「{0}」を削除しました。",
    )

    /** 卸载成功提示（[modName] 为标题）。 */
    fun modManageUninstallDone(modName: String): String = fill(modManageUninstallDoneFormat, modName)

    /** 内置 Mod 不提供卸载入口的说明。 */
    val modManageUninstallBuiltin: String = tr(
        zh = "内置 Mod 随游戏本体提供，不能在设置页卸载。",
        en = "Built-in mods ship with the game and cannot be uninstalled here.",
        ja = "内蔵 Mod は本体に含まれるため、ここでは削除できません。",
    )

    /** 卸载确认对话框里的影响预览小标题。 */
    val modUninstallImpactHeading: String = tr(
        zh = "卸载会一并删除：",
        en = "Uninstalling also deletes:",
        ja = "アンインストールで以下も削除されます：",
    )

    /** 影响预览：代码行类别标签。 */
    val modUninstallImpactCode: String = tr(zh = "代码", en = "Code", ja = "コード")

    /** 影响预览：包设置行类别标签。 */
    val modUninstallImpactSettings: String = tr(zh = "包设置", en = "Package settings", ja = "パッケージ設定")

    /** 影响预览：存档侧车行类别标签。 */
    val modUninstallImpactSave: String = tr(zh = "存档数据", en = "Save data", ja = "セーブデータ")

    /** 影响预览：媒体清单行类别标签。 */
    val modUninstallImpactMedia: String = tr(zh = "媒体文件", en = "Media files", ja = "メディアファイル")

    /** 影响预览：资源目录行类别标签。 */
    val modUninstallImpactAssets: String = tr(zh = "资源文件", en = "Asset files", ja = "素材ファイル")

    /** 影响预览：角色模板目录行类别标签。 */
    val modUninstallImpactChara: String = tr(
        zh = "角色模板",
        en = "Character templates",
        ja = "キャラクターテンプレート",
    )

    /** 影响预览：包成员文件行类别标签。 */
    val modUninstallImpactMembers: String = tr(zh = "包文件", en = "Package files", ja = "パッケージファイル")

    /** 影响预览读数「确实没有」：数量为 0 时写这个，不写 0。 */
    val modUninstallImpactNone: String = tr(zh = "无", en = "none", ja = "なし")

    /** 影响预览读数「取不到」：与 0 分开，免得玩家以为卸载什么都不删。 */
    val modUninstallImpactMissing: String = tr(
        zh = "—（无读数）",
        en = "— (no reading)",
        ja = "—（読取なし）",
    )

    /** 影响预览：代码行 `版本数 / 体积`。 */
    private val modUninstallImpactCodeLineFormat: String = tr(
        zh = "{0} 个版本 / {1}",
        en = "{0} version(s) / {1}",
        ja = "{0} バージョン / {1}",
    )

    /** 影响预览：代码行（[versions] 版本数、[size] 屏显体积）。 */
    fun modUninstallImpactCodeLine(versions: Int, size: String): String =
        fill(modUninstallImpactCodeLineFormat, versions, size)

    /** 影响预览：存档侧车行 `体积 / 条目数`。 */
    private val modUninstallImpactSaveLineFormat: String = tr(
        zh = "{0} / {1} 项",
        en = "{0} / {1} item(s)",
        ja = "{0} / {1} 件",
    )

    /** 影响预览：存档侧车行（[size] 屏显体积、[keys] 条目数）。 */
    fun modUninstallImpactSaveLine(size: String, keys: Int): String =
        fill(modUninstallImpactSaveLineFormat, size, keys)

    /** 影响预览：资源目录行 `条目数 / 体积`。 */
    private val modUninstallImpactAssetsLineFormat: String = tr(
        zh = "{0} 个 / {1}",
        en = "{0} file(s) / {1}",
        ja = "{0} 個 / {1}",
    )

    /** 影响预览：资源目录行（[entries] 条目数、[size] 屏显体积）。 */
    fun modUninstallImpactAssetsLine(entries: Int, size: String): String =
        fill(modUninstallImpactAssetsLineFormat, entries, size)

    /** 影响预览：纯计数行（包设置 / 媒体 / 角色模板 / 包成员）。 */
    private val modUninstallImpactCountLineFormat: String = tr(
        zh = "{0} 项",
        en = "{0} item(s)",
        ja = "{0} 件",
    )

    /** 影响预览：纯计数行（[count] 条目数）。 */
    fun modUninstallImpactCountLine(count: Int): String = fill(modUninstallImpactCountLineFormat, count)

    /**
     * 装载失败条目旁的「重试装载」按钮（Lane DZ）。
     *
     * 只对**可重试形态**亮：内置载体 + 本会话曾因 onLoad/onEnable 失败回滚。点击不回显
     * 同步结果（会话在游戏壳里、设置页拿不到引用），而由失败读数的既有轮询刷新。
     */
    val modManageRetryInstall: String = tr(
        zh = "重试装载",
        en = "Retry loading",
        ja = "読み込みを再試行",
    )

    /** 点击「重试装载」后的等待提示（请求在下个回合边界由会话执行）。 */
    val modManageRetryQueued: String = tr(
        zh = "已请求重试装载，下一回合边界生效；结果会刷新在这一行。",
        en = "Retry requested. It runs at the next turn boundary and the result updates on this line.",
        ja = "再読み込みを要求しました。次のターン境界で実行され、結果はこの行に反映されます。",
    )

    // -----------------------------------------------------------------
    // 口上包管理卡（KI-6，2026-11-23）：只列玩家导入的口上包（`kojo-` 前缀），
    // 与「已安装的 Mod」列表（全来源）分立——主人指令「和 mod 导入功能区分开来，
    // 单独设置一个口上导入功能」。覆盖关系展示 = manifest `override-functions` 段。
    // -----------------------------------------------------------------

    /** 口上包管理卡标题。 */
    val kojoManageTitle: String = tr(
        zh = "口上包管理",
        en = "Installed kojo packs",
        ja = "口上パック管理",
    )

    /** 卡顶说明：这卡只管导入的口上包，覆盖与启停的语义、生效时机一次说清。 */
    val kojoManageHint: String = tr(
        zh = "这里只列从「口上导入」或口上市场装进来的口上包（与 Mod 列表分立）。" +
            "开关 = 停用/启用该口上；卸载后该口上对本体的覆盖恢复为原版。改完要重开一局才生效。",
        en = "Only kojo packs imported from Kojo Import or the market are listed here (separate from " +
            "the mod list). Toggle = disable/enable a pack; uninstalling restores the original lines " +
            "it overrode. Restart the game to apply.",
        ja = "ここには「口上インポート」やマーケットから入れた口上パックだけを表示します" +
            "（Mod 一覧とは別）。スイッチ＝パックの無効/有効。アンインストールすると" +
            "上書きしていた本体の口上は元に戻ります。反映にはゲーム再起動が必要です。",
    )

    /** 列表为空时的提示（导引到导入入口）。 */
    val kojoManageEmpty: String = tr(
        zh = "还没有导入任何口上包。用上方「口上导入」或口上市场添加。",
        en = "No kojo packs imported yet. Use Kojo Import above or the kojo market.",
        ja = "口上パックはまだありません。上の「口上インポート」か口上マーケットから追加できます。",
    )

    /** 计数行：{0} 已导入的口上包数。 */
    val kojoManageCountFormat: String = tr(
        zh = "已导入 {0} 个口上包",
        en = "{0} kojo pack(s) imported",
        ja = "口上パック {0} 件",
    )

    /** 导入时间行：{0} 已格式化的时间（ISO 形）。 */
    val kojoManageImportedAtFormat: String = tr(
        zh = "导入于 {0}",
        en = "imported at {0}",
        ja = "導入日時：{0}",
    )

    /** 覆盖计数行：{0} 声明覆盖的本体函数数。 */
    val kojoManageOverrideCountFormat: String = tr(
        zh = "覆盖本体函数 {0} 个",
        en = "{0} original function(s) overridden",
        ja = "本体関数 {0} 件を上書き",
    )

    /** 展开面标题：被覆盖函数清单（无覆盖声明则整块不出）。 */
    val kojoManageOverrideListTitle: String = tr(
        zh = "被覆盖的本体函数（装载时后装者生效）",
        en = "Overridden original functions (later-installed packs win)",
        ja = "上書きされた本体関数（後から入れたものが有効）",
    )

    /** 无覆盖声明的展开说明（整块替代覆盖清单）。 */
    val kojoManageOverrideNone: String = tr(
        zh = "本包没有声明覆盖本体函数。",
        en = "This pack does not override any original functions.",
        ja = "このパックは本体関数を上書きしていません。",
    )

    /** 卸载确认框标题。 */
    val kojoManageUninstallConfirmTitle: String = tr(
        zh = "卸载口上包",
        en = "Uninstall kojo pack",
        ja = "口上パックを削除",
    )

    /** 卸载确认正文：{0} 口上显示名。覆盖恢复原版 + 设置一并删除要说明。 */
    val kojoManageUninstallConfirmFormat: String = tr(
        zh = "卸载「{0}」？该口上对本体的覆盖将恢复为原版，本包设置与数据一并删除。",
        en = "Uninstall \"{0}\"? The original lines it overrode will be restored, and this pack's " +
            "settings and data will be removed.",
        ja = "「{0}」を削除しますか？上書きしていた本体の口上は元に戻り、このパックの設定とデータも削除されます。",
    )

    /** 卸载确认框的确认按钮。 */
    val kojoManageUninstallConfirmOk: String = tr(
        zh = "卸载",
        en = "Uninstall",
        ja = "削除",
    )

    /** 卸载确认框的取消按钮。 */
    val kojoManageUninstallConfirmCancel: String = tr(
        zh = "取消",
        en = "Cancel",
        ja = "キャンセル",
    )

    /** 计数行包装（卡内只出词，不判语言）。 */
    fun kojoManageCount(count: Int): String = fill(kojoManageCountFormat, count)

    /** 导入时间行包装（时间戳格式化走 [formatImportTimestamp] 的 ISO 形）。 */
    fun kojoManageImportedAt(millis: Long): String = fill(kojoManageImportedAtFormat, formatImportTimestamp(millis))

    /** 覆盖计数行包装。 */
    fun kojoManageOverrideCount(count: Int): String = fill(kojoManageOverrideCountFormat, count)

    /** 卸载确认正文包装。 */
    fun kojoManageUninstallConfirm(name: String): String = fill(kojoManageUninstallConfirmFormat, name)

    // -----------------------------------------------------------------
    // 重新编译（已导入 ERB 数据包的源变更后原位重编，2026-11-17 lane-hotrecompile）
    // -----------------------------------------------------------------

    /** 「重新编译」按钮文字。 */
    val modManageRecompile: String = tr(zh = "重新编译", en = "Recompile", ja = "再コンパイル")

    /** 重编按钮下的说明（何时用、源没变会跳过）。 */
    val modManageRecompileHint: String = tr(
        zh = "改过这个包的源文件后点这里重编；源没变会自动跳过。",
        en = "Use this after editing this pack's source files; unchanged sources are skipped automatically.",
        ja = "このパックのソースを変更したらここで再コンパイルします。変更がなければ自動でスキップされます。",
    )

    /** 重编进行中的按钮文字（后台编译期显示）。 */
    val modManageRecompileBusy: String = tr(
        zh = "正在重新编译…",
        en = "Recompiling…",
        ja = "再コンパイル中…",
    )

    /** 重编结果：源未变更，未动手。 */
    val modManageRecompileUpToDate: String = tr(
        zh = "源文件没有变化，无需重新编译。",
        en = "Sources are unchanged; no recompile needed.",
        ja = "ソースは変更されていないため、再コンパイルは不要です。",
    )

    /** 重编成功提示格式（{0} = Mod 标题；重开一局才生效）。 */
    private val modManageRecompileDoneFormat: String = tr(
        zh = "已重新编译「{0}」，重开一局生效。",
        en = "Recompiled \"{0}\". Restart the game to apply.",
        ja = "「{0}」を再コンパイルしました。ゲームを再起動すると反映されます。",
    )

    /** 重编成功提示（[modName] 为标题）。 */
    fun modManageRecompileDone(modName: String): String = fill(modManageRecompileDoneFormat, modName)

    /** 重编失败提示格式（{0} = Mod 标题，{1} = 失败原因；旧版本已保留）。 */
    private val modManageRecompileFailedFormat: String = tr(
        zh = "「{0}」重新编译失败（已保留旧版本）：{1}",
        en = "Could not recompile \"{0}\" (the previous version is kept): {1}",
        ja = "「{0}」の再コンパイルに失敗しました（以前の版は残っています）：{1}",
    )

    /** 重编失败提示（[modName] 为标题，[reason] 为取证面原因）。 */
    fun modManageRecompileFailed(modName: String, reason: String): String =
        fill(modManageRecompileFailedFormat, modName, reason)

    // -----------------------------------------------------------------
    // per-mod 设置项（RT2 per-mod 配置，2026-11-17）：作者声明一次 → 设置页自动出控件
    // -----------------------------------------------------------------
    // 口径：设置项标题 / 说明正文来自 manifest 的 settings 段（作者填），本表只收「壳层
    // 自己的字」：小节标题、说明、单行格式、布尔开合、保存反馈、整数校验与 clamp 提示。

    /** Mod 设置小节标题（展开某条 Mod 时出现在详情里）。 */
    val modSettingSectionTitle: String = tr(
        zh = "Mod 设置",
        en = "Mod settings",
        ja = "Mod 設定",
    )

    /**
     * 说明：设置项来源（作者声明）、保存落点（按包保存在本机）与生效时机。
     *
     * 生效时机 = **本局内下一拍**（Lane CS 热通知）：写盘成功后经 `ModSettingsNotifyChannel`
     * 入队，VM 线程页边界 drain 到 `ModRuntimeAssembly.notifySettingsChanged`。旧口径
     * 「下次重开一局生效」在热通知落地后已失真，这里与 [modManageTakeEffectHint] 同口径。
     */
    val modSettingHint: String = tr(
        zh = "这些设置项由 Mod 作者声明，按包保存在本机（不写进存档）。保存后回到游戏，" +
            "下一次回合推进时生效，不用重开。",
        en = "These settings are declared by the Mod author and saved per package on this device " +
            "(not inside save files). They apply at the next turn boundary after you return to " +
            "the game; no restart needed.",
        ja = "これらの設定は Mod 作者が宣言したもので、パッケージごとに端末へ保存されます" +
            "（セーブデータには入りません）。保存後にゲームへ戻ると、次のターン進行時に" +
            "反映されます（再起動不要）。",
    )

    /** 单行展示格式：设置项标题：当前值。 */
    private val modSettingLineFormat: String = tr(
        zh = "{0}：{1}",
        en = "{0}: {1}",
        ja = "{0}：{1}",
    )

    /** 设置项单行（[label] 标题 / [value] 当前值文本）。 */
    fun modSettingLine(label: String, value: String): String = fill(modSettingLineFormat, label, value)

    /** 布尔设置项「开」的显示文本。 */
    val modSettingBoolOn: String = tr(zh = "开", en = "On", ja = "オン")

    /** 布尔设置项「关」的显示文本。 */
    val modSettingBoolOff: String = tr(zh = "关", en = "Off", ja = "オフ")

    /** 保存成功提示（写盘成功；生效时机同说明 = 回到游戏后的下一拍，不承诺重开）。 */
    val modSettingSaved: String = tr(
        zh = "设置已保存，回到游戏后下一回合生效。",
        en = "Setting saved. It applies on the next turn after you return to the game.",
        ja = "設定を保存しました。ゲームに戻ると次のターンで反映されます。",
    )

    /** 保存失败提示（写盘失败；盘上原值保持不变）。 */
    val modSettingSaveFailed: String = tr(
        zh = "设置保存失败，请检查存储空间后重试。",
        en = "Failed to save the setting. Check storage space and try again.",
        ja = "設定の保存に失敗しました。空き容量を確認して再試行してください。",
    )

    /** 整数输入非法提示（不是整数，取消不改盘）。 */
    val modSettingInvalidInt: String = tr(
        zh = "请输入整数。",
        en = "Please enter a whole number.",
        ja = "整数を入力してください。",
    )

    /** 超出声明范围被 clamp 的提示（[value] 为实际落盘值文本）。 */
    private val modSettingClampedFormat: String = tr(
        zh = "超出允许范围，已修正为 {0}。",
        en = "Out of range; adjusted to {0}.",
        ja = "許容範囲外のため {0} に修正しました。",
    )

    /** clamp 提示（[value] 为实际落盘值文本）。 */
    fun modSettingClamped(value: String): String = fill(modSettingClampedFormat, value)

    /** 「恢复默认」按钮（本小节底部；删除本机为该包保存的全部设置值）。 */
    val modSettingResetButton: String = tr(
        zh = "恢复默认",
        en = "Restore defaults",
        ja = "既定値に戻す",
    )

    /** 二次确认弹窗标题。 */
    val modSettingResetConfirmTitle: String = tr(
        zh = "恢复这个 Mod 的默认设置？",
        en = "Restore this Mod's default settings?",
        ja = "この Mod の既定の設定に戻しますか？",
    )

    /** 二次确认正文：删除范围（本机该包侧车）+ 不碰存档/Mod 数据 + 生效时机。 */
    val modSettingResetConfirmBody: String = tr(
        zh = "将删除本机为这个包保存的全部设置值，回到作者声明的默认值（不影响存档与 Mod 数据）。" +
            "回到游戏后下一回合生效。",
        en = "This deletes every saved value for this package on this device and falls back to the " +
            "defaults declared by the author (save files and Mod data are untouched). It applies on " +
            "the next turn after you return to the game.",
        ja = "このパッケージの端末保存設定をすべて削除し、作者が宣言した既定値に戻します" +
            "（セーブデータと Mod のデータには影響しません）。ゲームに戻ると次のターンで反映されます。",
    )

    /** 二次确认弹窗的确认按钮（与编辑弹窗的通用「确定」区分开）。 */
    val modSettingResetConfirmOk: String = tr(
        zh = "确认恢复",
        en = "Restore",
        ja = "戻す",
    )

    /** 恢复默认成功提示（删除成功；生效时机同保存 = 回到游戏后的下一拍）。 */
    val modSettingResetDone: String = tr(
        zh = "已恢复默认，回到游戏后下一回合生效。",
        en = "Defaults restored. They apply on the next turn after you return to the game.",
        ja = "既定値に戻しました。ゲームに戻ると次のターンで反映されます。",
    )

    /** 恢复默认失败提示（侧车删除失败；盘上原值保持不变）。 */
    val modSettingResetFailed: String = tr(
        zh = "恢复默认失败，请检查存储空间后重试。",
        en = "Failed to restore defaults. Check storage space and try again.",
        ja = "既定値への復元に失敗しました。空き容量を確認して再試行してください。",
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

    /** 说明：勾选 + 上下移即优先级；不是完整顺序；依赖会改变实际装载序。 */
    val modPackageOrderHint: String = tr(
        zh = "勾选要优先的 Mod 并上下移调整顺序，靠前的在「同时就绪」时优先装载。" +
            "这是优先级，不是最终的完整顺序：依赖关系会改变实际装载序，" +
            "没勾选的 Mod 按包名缺省序排，已卸载的旧包名会被忽略。" +
            "全部取消勾选 = 恢复缺省，改完要重开一局生效。",
        en = "Tick the Mods you want to load first and move them up or down; earlier ones load " +
            "first when both are ready. This is a priority, not the final full order: dependencies " +
            "can change the actual load order, unticked Mods fall back to package name order, and " +
            "stale package names are ignored. Untick everything for the default; restart the game to apply.",
        ja = "優先したい Mod にチェックを入れ、上下に移動して順序を決めます。同時に読み込み可能な場合は" +
            "先のものが優先されます。これは優先度であり最終的な完全な順序ではありません。" +
            "依存関係により実際の読み込み順は変わり、チェックしていない Mod はパッケージ名順、" +
            "削除済みのパッケージ名は無視されます。すべて解除すると既定値に戻ります。変更はゲーム再起動後に反映されます。",
    )

    /** 输入框空态提示：留空 = 缺省（按包名顺序）。 */
    val modPackageOrderEmpty: String = tr(
        zh = "留空 = 缺省（按包名顺序）",
        en = "Empty = default (by package name)",
        ja = "空欄 = 既定（パッケージ名順）",
    )

    /** 排序卡：已勾选数量行。 */
    private val modPackageOrderChosenCountFormat: String = tr(
        zh = "已勾选 {0} 个，按上面列表顺序优先装载；没勾的按缺省序。",
        en = "Selected {0}; they load first in the order above. Unticked Mods use the default order.",
        ja = "{0} 件を選択中。上のリスト順に優先して読み込まれます。未選択は既定順です。",
    )

    /** 排序卡：已勾选数量行（[count] 为勾选数）。 */
    fun modPackageOrderChosenCount(count: Int): String =
        fill(modPackageOrderChosenCountFormat, count)

    /** 排序卡：勾选行上移按钮。 */
    val modPackageOrderMoveUp: String = tr(zh = "上移", en = "Move up", ja = "上へ")

    /** 排序卡：勾选行下移按钮。 */
    val modPackageOrderMoveDown: String = tr(zh = "下移", en = "Move down", ja = "下へ")

    /** 排序卡：一键恢复缺省顺序。 */
    val modPackageOrderReset: String = tr(
        zh = "恢复缺省",
        en = "Reset to default",
        ja = "既定に戻す",
    )

    /** 排序卡：手动编辑兜底输入框的标签。 */
    val modPackageOrderManualLabel: String = tr(
        zh = "手动编辑（兜底）",
        en = "Manual edit (fallback)",
        ja = "手動編集（代替手段）",
    )

    /** 排序卡：列表空态（没勾选任何包、也没有未知包残留）——点明此刻的装载行为。 */
    val modPackageOrderListEmpty: String = tr(
        zh = "还没有勾选任何 Mod：全部按包名缺省序装载。勾选后可用上移 / 下移排优先级。",
        en = "No Mods ticked yet: everything loads in default package-name order. " +
            "Tick some, then use Move up / Move down to set priority.",
        ja = "チェックされた Mod はまだありません：すべて既定のパッケージ名順で読み込まれます。" +
            "チェック後に上へ / 下へで優先度を設定できます。",
    )

    /** 排序卡：未知包残留行的标签（偏好串里有、已装列表里没有的旧 ID）。 */
    val modPackageOrderUnknownTag: String = tr(
        zh = "未知包（不在已装列表）",
        en = "Unknown package (not installed)",
        ja = "不明なパッケージ（未インストール）",
    )

    /** 排序卡：未知包残留行的移除按钮。 */
    val modPackageOrderUnknownRemove: String = tr(
        zh = "移除",
        en = "Remove",
        ja = "削除",
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

    /**
     * 竖屏档说明：键与档名同源（[portraitFontColumnLabels] 那张表），坏值回落缺省 62 档的说明。
     */
    fun portraitFontColumnHint(columns: Int): String =
        portraitFontColumnHints[columns] ?: portraitColLargeHint

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
