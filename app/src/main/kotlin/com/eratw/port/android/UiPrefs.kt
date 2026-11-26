package com.eratw.port.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.lexer.LangVariant
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.MirrorHealth
import com.eratw.port.core.update.UpdatePolicy
import java.security.SecureRandom

/**
 * 一个「显示大小」档位：同时给出**字号缩放倍率**与**列数守护下限**。
 *
 * 两者由同一列数反推（[UiPrefs.fontScale] = [UiPrefs.DEFAULT_FONT_COLUMNS] / columns）：
 * 列数越少，字号放大越多、守护下限也越低 —— 「大」档因此真的会放大字号，
 * 而不是像纯下限那样在宽屏上变成无效档。
 *
 * @property columns 守护下限列数（存进 SharedPreferences 的就是它；也是缩放倍率基准）
 * @property label 档位名（设置页上的单选项文字）
 * @property hint 辅助说明（设置页上跟在档位名后面的小字）
 */
data class FontColumnsOption(
    val columns: Int,
    val label: String,
    val hint: String,
)

/**
 * app 侧 UI 偏好的读写（`SharedPreferences("eratw_ui")`），与游戏读写的 `emuera.config` 完全解耦。
 *
 * ## 为什么另开一份存储
 * 这些是**移植壳**自己的显示偏好：
 *   * 列数守护 —— 原版没有「屏宽 → 列数」这回事（PC 基准固定 `FontSize=18` + 1800px 宽），
 *     手机屏宽差一个数量级，128 格地图行需要一条宽度下限；
 *   * 显示大小档 —— 由同一个列数反推出字号倍率（[fontScale]），原版没有对应条目。
 *
 * 它们没有对应的原版配置条目；而 `emuera.config` 的写回只遍历 `configArray`
 * （spec: `ConfigData.cs:430`），壳层偏好塞进去会写脏随包那份配置，所以单独一条 XML。
 *
 * ## 读取方（由装配层接线）
 *   * [fontColumns] → `CanvasConsoleView(minColumnsOverride = …)` 的**列数守护下限**
 *     （不是目标列数；字号来自 `emuera.config` 的 `FONT SIZE`，再乘 [fontScale]）；
 *   * [fontScale] → `CanvasConsoleView(fontScale = …)` 的**字号缩放倍率**
 *     （「显示大小」档，1 = 跟随 `FONT SIZE`）；
 *   * [screenFit] → `CanvasConsoleView(columnGuardEnabled = …)` 的列数守护开关。
 *     一屏一帧（`frameGateEnabled`）仍是装配参数，但不是壳层偏好：固定用生产缺省
 *     （见 `LineBufferConsoleSink` 的构造参数）；
 *   * [contentMarginFraction] → `CanvasConsoleView(contentMarginFraction = …)` 的
 *     **画面左右留白居中档位**（圆屏适配；解算收口在 `ScreenLayout.resolve` 的
 *     `contentMarginPx`，守护看去留白宽继续保列数）。
 *
 * ## 口径
 *   * 不缓存：每次现读 SharedPreferences（一次进程内 XML 快照，微秒量级），
 *     于是设置页改完立刻被下一次装配读到；
 *   * 写入一律 `apply()`（异步落盘，不阻塞 UI 线程）；
 *   * 读到不认识的值（老版本写坏、手工改 XML）一律回落缺省：最少 136 列、列数守护开；
 *   * 磁盘 IO 只有**每进程一次**的首读（那份 XML 的加载），它已进 UI 线程磁盘 IO 豁免表并带一次性
 *     耗时读数 —— 判据、删表条件与为什么不能挪都写在 [prefs] 里。
 */
object UiPrefs {

    /** SharedPreferences 文件名。与 `emuera.config` 是两套东西，互不覆盖。 */
    const val PREFS_NAME = "eratw_ui"

    /**
     * 「显示大小」档位。顺序即设置页上的展示顺序：标准 / 大 / 特大 / 小。
     *
     * 倍率 = 136 / 列数（由 [fontScale] 统一反推）；列数同时是列数守护下限。
     */
    val FONT_COLUMN_OPTIONS: List<FontColumnsOption> = listOf(
        FontColumnsOption(136, "标准", "字号 ×1.00（跟随 FONT SIZE）；每行至少 136 列（缺省；128 格地图完整）"),
        FontColumnsOption(90, "大", "字号 ×1.51（约 90 列）；宽地图可能折行"),
        FontColumnsOption(75, "特大", "字号 ×1.81（约 75 列）；宽地图会折行"),
        FontColumnsOption(150, "小", "字号 ×0.91（约 150 列）；字更小，地图更稳"),
    )

    /**
     * 列数守护下限（标准档）= **136**（半角格）。
     *
     * 为什么不是 110（2026-11-01 收「红魔馆一层地图残缺」玩家反馈）：语料里最宽的地图行是
     * **128 半角格**（`ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:10` 的 `#DIMS COLORCOLUMNS,128`，
     * 每格一个全角字 ⇒ 64 字 = 128 格；`COLOREDMAP_03.ERB` 的 `@COLOREDMAP_KOUMAKAN_1` 即此宽）。
     * 110 列档下这种行会被 `LineFolder` 按宽折成「主行 + 18 格尾巴行」，玩家看到的就是残缺地图。
     * 原版 PC 是 1800px 窗口 + 字号 18 ⇒ 约 199 格（`emuera.config:12,22`），128 格从来不会被折。
     *
     * 128 之上留 8 格余量：地图行可能带行首标记/边框；且守护反推现在用 floor + 逐列复核
     * （见 [com.eratw.port.render.view.ScreenLayout.resolve]），不再有「roundToInt 吃掉 0–5 格」
     * 的问题，136 在 1000–3000px 屏宽下必定铺满 ≥136 格（`ScreenLayoutTest` 钉住）。
     */
    const val DEFAULT_FONT_COLUMNS: Int = 136

    /**
     * 「列数守护」开关缺省**开**：窄屏压小字号保 [DEFAULT_FONT_COLUMNS] 列（128 格地图不折行），
     * 关 = 字号优先、地图可能折行（取舍见 [screenFit] 的 KDoc）。
     */
    const val DEFAULT_SCREEN_FIT: Boolean = true

    /** 右侧选项栏缺省可见（用户可随时用螺旋图标收起，状态记忆）。 */
    const val DEFAULT_OPTION_RAIL: Boolean = true

    /**
     * 「键入回显复刻」缺省**关**：关 = 点选项后玩家看不到那行所选编号
     * （玩家反馈「选口上剧本后最新一行冒数字、整屏跟着抖」）；开 = 原版
     * `EmueraConsole.cs:1280-1282` 的 `Print(str)+PrintFlush` 原样上屏。
     *
     * 只切**可见性**：两档都把回显行打进逻辑行表（LINECOUNT / CLEARLINE / deleteLine
     * 逐字不变），关态只把它从上屏帧剔除。这是移植侧的显示偏好，不是原版配置项，
     * 所以只放壳层偏好、不进 `emuera.config`。
     */
    const val DEFAULT_ECHO_INPUT: Boolean = false

    /**
     * 「指令后等一次按键」缺省**开** = 原版口径：指令打出来的口上停在屏上，点一下才回指令菜单
     * （`Process.cs:2413-2422` 无条件置位、`:2449-2455` 消费 `setWait()`；`RESULT == 0` 走
     * `endCallComXX` 另一支，不经 `SOURCE_CHECK`，故不等待）。
     *
     * 2026-11-02 曾收窄成「只清掃被拒」（移动每走一步 `RETURN 1`，等待落在每次移动之后）；
     * 2026-11-22 玩家反馈「口上根本看不见、还得往上翻阅」—— 那行提示被下个回合的整帧重绘
     * 刷掉 —— 于是全量恢复原版，收窄降级成本开关的一档。移植侧策略开关，原版没有对应配置项，
     * 故只放壳层偏好、不进 `emuera.config`。
     */
    const val DEFAULT_COMMAND_END_WAIT: Boolean = true

    /**
     * 自动更新缺省**开**。真正的开关不在这里：出包没带 `-PeratwUpdateMirrors` 时包里
     * 一条源都没有，通道整体不跑（见 `UpdateManager.channelEnabled`），所以这一项缺省开
     * 不会让开发包、CI 包多出任何一次网络请求。
     */
    const val DEFAULT_AUTO_UPDATE: Boolean = true

    /**
     * 匿名玩家数统计缺省**开**。与 [DEFAULT_AUTO_UPDATE] 同一条硬闸：出包没带
     * `-PeratwReportEndpoint` 时端点为空，`PlayerStatsReporter.isEnabled` 直接短路，
     * 开发包 / CI 包零网络行为。设置页可随时关掉，关掉后连匿名编号都不生成。
     *
     * 默认开的理由：这个数只用来在公开 README 上显示「有多少人来过」，默认关会让徽章
     * 长期停在 0；代价与口径在设置页文案与 README 三语隐私说明里写给玩家看。
     */
    const val DEFAULT_PLAYER_STATS_ENABLED: Boolean = true

    /**
     * 标题彩蛋（`@BADAPPLE`）屏蔽开关：缺省**开**（= 屏蔽）。
     *
     * 为什么缺省开：那是 6573 帧 `AWAIT 16`（≈ 105 秒）+ 一个 MP3 的纯装饰动画，
     * 对「能不能玩」零贡献。语料里那一行 `CALL BADAPPLE` 在 4.980 版已被语料作者注释掉，
     * 但语料不进仓、别人的 clone 用的是上游那份（可能是活的），所以屏蔽落在**引擎**
     * （`EngineFunctionPolicy`），这里的开关只是让想看的玩家能把它放回来。
     */
    const val DEFAULT_DISABLE_BAD_APPLE: Boolean = true

    /**
     * 长页忙碌提示（「少女祈祷中」横带）缺省**开**。
     *
     * 只影响**观感**：一页脚本算满 1 秒后，内容区最下面一行显示一条居中呼吸的
     * 「少女祈祷中…」；页一交付/进入等待就消失。它不打印行、不改行缓冲，关掉也
     * 只少一层像素（内容与脚本语义逐字不变）。阈值与形态见
     * `com.eratw.port.core.host.BusyIndicator.MIN_ELAPSED_MS` 与 `BusyIndicatorBand`。
     */
    const val DEFAULT_BUSY_INDICATOR: Boolean = true

    /**
     * RT2 Mod 运行时总闸缺省：**开**（2026-11-17 主人裁定「让 Mod 真正可用」）。
     *
     * 开态装配链会读 `mods/rt2/` 的 manifest 与 `.erbm` 容器、装载随包内置 Mod，并在存档时
     * 写同名 `.rt2` 侧车；对没有安装任何 Mod 的玩家，装配链在「无容器」旁路处原样返回，
     * 游戏内容逐字节不变。关态是完整的旁路开关（`ModRuntimeHost` 构造次数 0、磁盘不碰），
     * 设置页「操作与显示」卡里的 `modRuntime` 行可随时关回。
     */
    const val DEFAULT_MOD_RUNTIME: Boolean = true

    /**
     * 路 B dex 装载总闸缺省：**关**（ADR-013 D5 组长裁定；与 [DEFAULT_MOD_RUNTIME] 刻意不同）。
     *
     * 与 `.erbm` 容器不同，dex 是**可执行的第三方机器码**：开态才会扫 `mods/rt2/<pkg>/code/` 并
     * 构造 DexClassLoader，一开就是直接执行任意代码的高风险面；缺省关 = 老玩家与没装 dex 包的
     * 玩家完全不进这条链（`code/` 不扫、DexClassLoader 构造 0 次、磁盘不碰），游戏内容逐字节不变。
     *
     * 本片（S2）**不接 UI**：只提供判定函数与偏好键（无设置行、无文案表条目），设置行/文案由 S8 收口。
     */
    const val DEFAULT_MOD_DEX_LOADER: Boolean = false

    /**
     * 判词 9（PDIAG1）「玩家授权诊断通道」的缺省：**关**。
     *
     * 与 DEFAULT_MOD_DEX_LOADER 同族的高风险面口径：这条通道一旦被外部够到，等于把本机运行
     * 现场的只读快照交给「能往 files/eratw/dev/cmd.txt 写一行字」的那只手。缺省关 = release 包
     * 连轮询线程都不建；只有玩家在设置页「Mod 与素材」档显式二次确认之后才放开，且只放开
     * 只读/观测命令，写侧与改档命令仍要 debug 包（判据在 DevToolsHost 的命令分类闸）。
     */
    const val DEFAULT_PLAYER_DIAGNOSTICS: Boolean = false

    /**
     * 画面左右留白居中缺省：**关**（0 = 满宽，全部读数与本功能加入前逐值相同）。
     * 解算语义见 `ScreenLayout.resolve` 的 `contentMarginPx`；档位见 [CONTENT_MARGIN_OPTIONS]。
     */
    const val DEFAULT_CONTENT_MARGIN_FRACTION: Float = 0f

    /** 留白档位：关（= [DEFAULT_CONTENT_MARGIN_FRACTION]，满宽）。 */
    const val CONTENT_MARGIN_OFF: Float = 0f
    /** 留白档位：小（每侧 4%，字号 ≈ ×0.92）。 */
    const val CONTENT_MARGIN_SMALL: Float = 0.04f
    /** 留白档位：中（每侧 8%，字号 ≈ ×0.84）。 */
    const val CONTENT_MARGIN_MEDIUM: Float = 0.08f
    /** 留白档位：大（每侧 12%，字号 ≈ ×0.76）。上限依据：守护看去留白宽仍保 136 列且可读。 */
    const val CONTENT_MARGIN_LARGE: Float = 0.12f

    /**
     * 留白档位表（每侧占物理内容宽的比例）：关 / 小 / 中 / 大。
     * 档位值是白名单（读取不认识回落缺省），新增档 = 改这一处 + 设置页文案。
     */
    val CONTENT_MARGIN_OPTIONS: List<Float> = listOf(
        CONTENT_MARGIN_OFF,
        CONTENT_MARGIN_SMALL,
        CONTENT_MARGIN_MEDIUM,
        CONTENT_MARGIN_LARGE,
    )

    private const val KEY_FONT_COLUMNS = "font_columns"
    private const val KEY_OPTION_RAIL = "option_rail"
    private const val KEY_DISABLE_BAD_APPLE = "disable_bad_apple"
    /** 长页忙碌提示开关：开 = 长页最下面一行画「少女祈祷中」，关 = 不画。 */
    private const val KEY_BUSY_INDICATOR = "busy_indicator"
    /** RT2 Mod 运行时总闸（玩家面，缺省开）：开 = 会话装配走完整 mod 链；关 = 完全不进。 */
    private const val KEY_MOD_RUNTIME = "mod_runtime"
    /**
     * 路 B dex 装载总闸（玩家面，缺省 [DEFAULT_MOD_DEX_LOADER] = 关）：开 = 会话装配链允许扫
     * `code/` 并装载 dex；关 = 完全不进。键名独立于 `mod_runtime`（两个闸分开拨）。
     */
    const val KEY_MOD_DEX_LOADER: String = "mod.dex.loader"
    /** 列数守护开关：开 = 屏宽铺不下选定列数时压小字号保地图；关 = 字号优先、地图可能折行。 */
    private const val KEY_SCREEN_FIT = "screen_fit"
    private const val KEY_LIVE_REFLOW = "live_reflow"
    /** 键入回显复刻（ECHO-INPUT）开关：玩家侧缺省 [DEFAULT_ECHO_INPUT]（关）。 */
    private const val KEY_ECHO_INPUT = "echo_input"
    /** 指令收尾按键等待（COMEND-WAIT）开关：缺省 [DEFAULT_COMMAND_END_WAIT]（开 = 原版）。 */
    private const val KEY_COMMAND_END_WAIT = "command_end_wait"
    private const val KEY_LOADING_STYLE = "loading_style"
    private const val KEY_INPUT_KNOB = "input_knob"

    /**
     * 逐帧诊断记录开关（玩家侧取证通道，缺省关）。
     *
     * 开 = 游戏画面每帧往环形缓冲记一条版面事实（`FrameTrace`），玩家点「分享诊断」
     * 时整段进报告；关 = `onDraw` 只多一次 volatile 读，不建对象、不写盘。
     * 与 DevTools 的 `frames on` 共用同一个环形缓冲与同一个开关。
     */
    private const val KEY_FRAME_TRACE = "frame_trace"

    /**
     * 玩家授权诊断通道开关（判词 9 PDIAG1）：开 = release 包也允许起 DevTools 轮询线程
     * （只读命令档）。与 KEY_FRAME_TRACE 并列：两条都是玩家侧取证通道，缺省都关。
     */
    private const val KEY_PLAYER_DIAGNOSTICS = "player_diagnostics"

    /**
     * 拨动授权开关的时刻（System.currentTimeMillis）：判词 9 §2.4 的取证面，开与关都刷新。
     *
     * 只落这一枚整数 —— 不存身份、不在 SharedPreferences 之外再起常驻状态。日志与本机诊断
     * 报告各出一行「谁在什么时候打开过」，读数永不自动上传。
     */
    private const val KEY_PLAYER_DIAG_AUTHORED_AT = "player_diag_authored_at"

    /**
     * 画面左右留白居中档位（每侧留白 = 物理内容宽 × 比例，0 = 关）。
     * 移植壳的显示适配，不进 `emuera.config`（与 `WINDOW WIDTH` 零交集）。
     */
    private const val KEY_CONTENT_MARGIN_FRACTION = "content_margin_fraction"

    /** 右侧选项栏按钮大小档（小/标准/大/特大）。 */
    private const val KEY_RAIL_BUTTON_SIZE = "rail_button_size"

    /** 右侧选项栏布局模式（行内横滑/竖列折行/完全展开）。 */
    private const val KEY_RAIL_LAYOUT = "rail_layout"

    /** 每行最多按钮数（三模式通用：横滑=超过才滚，竖列/展开=按它折行/定宽）。 */
    private const val KEY_RAIL_CHIPS_PER_ROW = "rail_chips_per_row"

    /** 自定义过滤的「隐藏无编号按钮」规则开关（缺省关；黑名单独立于它生效）。 */
    private const val KEY_RAIL_HIDE_UNNUMBERED = "rail_hide_unnumbered"

    /** 自定义过滤：从右栏隐藏的按钮标签黑名单（StringSet，写入侧吃 [RAIL_HIDDEN_LABELS_CAP] 上限）。 */
    private const val KEY_RAIL_HIDDEN_LABELS = "rail_hidden_labels"

    /** 常用置顶的提交值有序列表（编码串，写入侧吃 [RAIL_PINNED_CAP] 上限、LRU 淘汰）。 */
    private const val KEY_RAIL_PINNED = "rail_pinned"

    /** 常用置顶的历史候选（值+名字，编码串，写入侧吃 [RAIL_HISTORY_CAP] 上限）。 */
    private const val KEY_RAIL_HISTORY = "rail_history"

    /**
     * 快捷宏（名字+指令集串，编码串，写入侧吃 [RailOptionRules.QUICK_MACRO_CAP] 上限）。
     * 与 [KEY_RAIL_PINNED]/[KEY_RAIL_HISTORY] 同族：一行编码串，读端坏行跳过不炸。
     */
    private const val KEY_QUICK_MACROS = "quick_macros"

    /** 自动更新总开关（缺省开；真关在包里没源，见 [DEFAULT_AUTO_UPDATE]）。 */
    private const val KEY_AUTO_UPDATE = "auto_update"

    /**
     * 「以后都不」跳过的整包版本（CONTRACT-UPDATE §1.5）。存的是玩家跳过的那个
     * `apk-version-code`；**没写过 = 0**（[UpdatePolicy.isVersionSuppressed] 把非正数一律判「不抑制」）。
     */
    private const val KEY_UPDATE_SKIPPED_VERSION_CODE = "update_skipped_version_code"

    /** 设置页显示语言（中/英/日）；只作用于设置页，与游戏界面无关。 */
    private const val KEY_SETTINGS_LANGUAGE = "settings_language"

    /** 灰度分桶 bucket（0..99）：首次生成后持久化，同一设备对所有版本稳定。 */
    private const val KEY_ROLLOUT_BUCKET = "rollout_bucket"

    /** 「仅 Wi-Fi 下载」偏好（U5 P2-10）：**缺省 false** = 保持现状（蜂窝仍走二次确认）。 */
    private const val KEY_WIFI_ONLY_DOWNLOAD = "update_wifi_only"

    /** 匿名玩家数统计开关（设置键，恢复默认时清掉）。 */
    private const val KEY_PLAYER_STATS_ENABLED = "player_stats_enabled"

    /** 本机匿名编号（64 位小写 hex）：设备身份，不是设置，恢复默认时**不清**，否则会虚增玩家数。 */
    private const val KEY_PLAYER_STATS_ID = "player_stats_id"

    /** 上一次心跳成功落下的 UTC 日：节流状态，不是设置。 */
    private const val KEY_PLAYER_STATS_LAST_DAY = "player_stats_last_day"

    /**
     * 首启教程（复古像素指南）是否已展示过：缺省 false = 首次安装首次进入时弹一次。
     * 属于引导完成态而非玩家设置，恢复默认时**不清**（否则老玩家每次全部重置都被教程拦一次）。
     */
    private const val KEY_TUTORIAL_SHOWN = "tutorial_shown"

    /** 镜像健康表：每行「归一地址 / 连续失败数 / 最后成功时刻」，见 [encodeMirrorHealth]。 */
    private const val KEY_MIRROR_HEALTH = "mirror_health"

    /** bucket 还没生成过的读数（读不到或坏值回落它，由 [rolloutBucket] 兜底生成）。 */
    const val NO_ROLLOUT_BUCKET: Int = -1

    /** 单源连续失败数上限：失败只累加到这个数，避免偏好表里的值无限增长。 */
    const val MIRROR_FAILURE_CAP: Int = 99

    /**
     * 启动画面样式：**像素风**（缺省，2000 年代文字游戏的方块进度条 + 硬边框 + 扫描线）
     * 与**现代风**（2026-10-23 加的那版深色渐变页）。
     */
    enum class LoadingStyle(val stored: String, val label: String, val hint: String) {
        PIXEL("pixel", "像素风", "方块进度条，复古文字游戏味"),
        MODERN("modern", "现代风", "深色渐变页，简洁"),
        ;

        companion object {
            fun fromStored(value: String?): LoadingStyle =
                entries.firstOrNull { it.stored == value } ?: DEFAULT_LOADING_STYLE
        }
    }

    /** 启动画面样式缺省值：像素风（与游戏本身的画风一致）。 */
    val DEFAULT_LOADING_STYLE: LoadingStyle = LoadingStyle.PIXEL

    /**
     * 右侧选项栏按钮的大小档 —— 一档同时定栏宽、按钮高度与标题字号
     * （换算表在 `ShellUi.railMetricsOf`，设置页与右栏读的是同一档）。
     *
     * @property stored 落盘用的稳定字符串（改档名不会让已存偏好失效）
     * @property label 档位名（设置页上的单选项文字）
     * @property hint 辅助说明（设置页上跟在档位名后面的小字）
     */
    enum class RailButtonSize(val stored: String, val label: String, val hint: String) {
        SMALL("small", "小", "右栏最窄，被它盖住的画面最少；标题长的选项会被截断"),
        NORMAL("normal", "标准", "缺省大小"),
        LARGE("large", "大", "按钮更高、标题更大；右栏占的画面更多"),
        XLARGE("xlarge", "特大", "标题最大最好点；右栏几乎盖住半屏，可用右上螺旋图标收起"),
        ;

        companion object {
            fun fromStored(value: String?): RailButtonSize =
                entries.firstOrNull { it.stored == value } ?: DEFAULT_RAIL_BUTTON_SIZE
        }
    }

    /** 选项按钮大小缺省档：标准（＝ 2026-10-29 收口的那版几何，老玩家看到的是同一个界面）。 */
    val DEFAULT_RAIL_BUTTON_SIZE: RailButtonSize = RailButtonSize.NORMAL

    /** 选项按钮大小档：缺省 [DEFAULT_RAIL_BUTTON_SIZE]；存的值不认识时回落缺省。 */
    fun railButtonSize(context: Context): RailButtonSize =
        RailButtonSize.fromStored(prefs(context).getString(KEY_RAIL_BUTTON_SIZE, null))

    /** 写选项按钮大小档（随改随存：纯壳层几何，不需要重开会话）。 */
    fun setRailButtonSize(context: Context, size: RailButtonSize) {
        prefs(context).edit().putString(KEY_RAIL_BUTTON_SIZE, size.stored).apply()
    }

    /**
     * 右侧选项栏布局模式 —— 三档都按游戏显示行分组、都吃「每行最多按钮数」（[railChipsPerRow]）：
     * - [RailLayoutMode.ROW_SCROLL] 行内横滑（缺省）：一条游戏行一行，≤N 枚均分宽度，>N 枚横向滚动；
     * - [RailLayoutMode.COLUMN] 竖列：同一游戏行超过 N 枚就折到下一行，行内仍均分宽度，不横滑；
     * - [RailLayoutMode.EXPANDED] 完全展开：游戏行分组保留，格子按 N 枚定宽、左起排不拉伸、装不下折行。
     *
     * 档名/说明文案在设置页三语表（SettingsStrings.railLayoutLabel 一族），这里只存
     * [stored] 稳定串 —— 与 [RailButtonSize] 的「stored 落盘、文案分离」同一纪律。
     */
    enum class RailLayoutMode(val stored: String) {
        ROW_SCROLL("row_scroll"),
        COLUMN("column"),
        EXPANDED("expanded"),
        ;

        companion object {
            fun fromStored(value: String?): RailLayoutMode =
                entries.firstOrNull { it.stored == value } ?: DEFAULT_RAIL_LAYOUT
        }
    }

    /** 布局模式缺省档：行内横滑（＝本设置项加入前唯一一档，老玩家零观感变化）。 */
    val DEFAULT_RAIL_LAYOUT: RailLayoutMode = RailLayoutMode.ROW_SCROLL

    /** 每行最多按钮数的取值范围（闭区间）。 */
    const val RAIL_CHIPS_PER_ROW_MIN: Int = 1
    const val RAIL_CHIPS_PER_ROW_MAX: Int = 6

    /** 每行最多按钮数缺省：3（＝右栏「一行三枚」的既有几何）。 */
    const val DEFAULT_RAIL_CHIPS_PER_ROW: Int = 3

    /** 常用置顶上限：再置顶就 LRU 淘汰最早的一条。 */
    const val RAIL_PINNED_CAP: Int = 8

    /** 置顶历史候选上限：只记最近见过的带编号指令。 */
    const val RAIL_HISTORY_CAP: Int = 32

    /** 隐藏标签黑名单上限：满了之后新隐藏不再入表（提示玩家先恢复一些）。 */
    const val RAIL_HIDDEN_LABELS_CAP: Int = 64

    /** 读右栏布局模式（缺省 [DEFAULT_RAIL_LAYOUT]；存的值不认识时回落缺省）。 */
    fun railLayout(context: Context): RailLayoutMode =
        RailLayoutMode.fromStored(prefs(context).getString(KEY_RAIL_LAYOUT, null))

    /** 写右栏布局模式（随改随存：纯壳层摆法，不需要重开会话）。 */
    fun setRailLayout(context: Context, mode: RailLayoutMode) {
        prefs(context).edit().putString(KEY_RAIL_LAYOUT, mode.stored).apply()
    }

    /** 读每行最多按钮数（夹在 [RAIL_CHIPS_PER_ROW_MIN]..[RAIL_CHIPS_PER_ROW_MAX]，坏值回落缺省）。 */
    fun railChipsPerRow(context: Context): Int =
        prefs(context).getInt(KEY_RAIL_CHIPS_PER_ROW, DEFAULT_RAIL_CHIPS_PER_ROW)
            .coerceIn(RAIL_CHIPS_PER_ROW_MIN, RAIL_CHIPS_PER_ROW_MAX)

    /** 写每行最多按钮数（随改随存：纯壳层几何，不需要重开会话）。 */
    fun setRailChipsPerRow(context: Context, perRow: Int) {
        prefs(context).edit()
            .putInt(KEY_RAIL_CHIPS_PER_ROW, perRow.coerceIn(RAIL_CHIPS_PER_ROW_MIN, RAIL_CHIPS_PER_ROW_MAX))
            .apply()
    }

    /**
     * 「右栏过滤」的规则开关：隐藏**无编号**按钮（立绘开关、【＋】、[CONFIG] 这类屏上不印
     * 自己编号的小开关）。缺省关；判据是
     * [com.eratw.port.render.view.LeanOptionFilter.isCommonCommand]（屏上自印编号的
     * `移动[400]` 与 `[100] - 睁开眼睛` 两形都算），与标签黑名单（[railHiddenLabels]）叠加生效，互不依赖。
     */
    fun railHideUnnumbered(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RAIL_HIDE_UNNUMBERED, false)

    /** 写「隐藏无编号按钮」规则开关（随改随存，下一次右栏刷新即生效）。 */
    fun setRailHideUnnumbered(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RAIL_HIDE_UNNUMBERED, enabled).apply()
    }

    /** 读隐藏标签黑名单（缺省空集）。 */
    fun railHiddenLabels(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_RAIL_HIDDEN_LABELS, emptySet()) ?: emptySet()

    /**
     * 把一枚按钮的标签加进隐藏黑名单（上限 [RAIL_HIDDEN_LABELS_CAP]，满了不追加 ——
     * 玩家先在设置页恢复几条再试）。返回是否真的写进去了。
     */
    fun addRailHiddenLabel(context: Context, label: String): Boolean {
        if (label.isEmpty()) return false
        val current = railHiddenLabels(context)
        if (label in current || current.size >= RAIL_HIDDEN_LABELS_CAP) return false
        prefs(context).edit().putStringSet(KEY_RAIL_HIDDEN_LABELS, current + label).apply()
        return true
    }

    /** 从隐藏黑名单恢复一枚按钮（标签不在表里时是空操作）。 */
    fun removeRailHiddenLabel(context: Context, label: String) {
        val current = railHiddenLabels(context)
        if (label in current) {
            prefs(context).edit().putStringSet(KEY_RAIL_HIDDEN_LABELS, current - label).apply()
        }
    }

    /** 清空隐藏标签黑名单。 */
    fun clearRailHiddenLabels(context: Context) {
        prefs(context).edit().remove(KEY_RAIL_HIDDEN_LABELS).apply()
    }

    /** 读常用置顶的提交值有序列表（越靠后越新；缺省空表）。 */
    fun railPinned(context: Context): List<String> =
        RailOptionRules.decodeStringList(prefs(context).getString(KEY_RAIL_PINNED, null).orEmpty())

    /** 写常用置顶有序列表（调用方负责先过 [RailOptionRules.pinTo] 的上限与去重）。 */
    fun setRailPinned(context: Context, values: List<String>) {
        prefs(context).edit().putString(KEY_RAIL_PINNED, RailOptionRules.encodeStringList(values)).apply()
    }

    /** 置顶一枚按钮（LRU：已存在则挪到最新，超 [RAIL_PINNED_CAP] 淘汰最早一条）。 */
    fun pinRailOption(context: Context, submitValue: String) {
        setRailPinned(context, RailOptionRules.pinTo(railPinned(context), submitValue, RAIL_PINNED_CAP))
    }

    /** 取消置顶一枚按钮（不在表里时是空操作）。 */
    fun unpinRailOption(context: Context, submitValue: String) {
        setRailPinned(context, RailOptionRules.unpinFrom(railPinned(context), submitValue))
    }

    /** 清空常用置顶。 */
    fun clearRailPinned(context: Context) {
        prefs(context).edit().remove(KEY_RAIL_PINNED).apply()
    }

    /** 读置顶历史候选（越靠后越新）。 */
    fun railHistory(context: Context): List<RailOptionRules.RailHistoryEntry> =
        RailOptionRules.decodeHistory(prefs(context).getString(KEY_RAIL_HISTORY, null).orEmpty())

    /**
     * 把这一屏识别出的按钮并入历史候选：只记**带编号指令**（[RailOptionRules.pinnableMaskOf]，
     * 这是历史候选的收录面，不是右栏长按菜单的置顶判据 —— 菜单收全部非色块），按提交值
     * 去重、名字取最新。调用点在选项指纹翻转时（换屏才合并一次，不是每 250ms）。
     */
    fun recordRailHistory(context: Context, options: List<ConsoleOption>) {
        val merged = RailOptionRules.mergeHistory(
            railHistory(context),
            options,
            RAIL_HISTORY_CAP,
        )
        prefs(context).edit().putString(KEY_RAIL_HISTORY, RailOptionRules.encodeHistory(merged)).apply()
    }

    /**
     * 补记**一条**历史候选（长按菜单置顶时调用）：菜单允许钉无编号按钮，这类值不在
     * [recordRailHistory] 的收录面里，不补记的话设置页已置顶列表只能显示兜底
     * 「编号 {0}」。空名不记（裸数字按钮的标签可能是空串，记了也只是空芯片）。
     */
    fun recordRailHistoryEntry(context: Context, submitValue: String, label: String) {
        if (submitValue.isEmpty() || label.isEmpty()) return
        val merged = RailOptionRules.mergeHistoryEntry(
            railHistory(context),
            RailOptionRules.RailHistoryEntry(submitValue, label),
            RAIL_HISTORY_CAP,
        )
        prefs(context).edit().putString(KEY_RAIL_HISTORY, RailOptionRules.encodeHistory(merged)).apply()
    }

    /** 清空置顶历史候选。 */
    fun clearRailHistory(context: Context) {
        prefs(context).edit().remove(KEY_RAIL_HISTORY).apply()
    }

    /**
     * 快捷宏列表（玩家自定义的 era 指令集串，右栏常用区里的按钮，上限
     * [RailOptionRules.QUICK_MACRO_CAP]）。纯代数在 [RailOptionRules]，这里只做薄存取。
     */
    fun quickMacros(context: Context): List<RailOptionRules.QuickMacro> =
        RailOptionRules.decodeQuickMacros(prefs(context).getString(KEY_QUICK_MACROS, null).orEmpty())

    /** 整表写回（调用方已按上限裁好；写盘口径与置顶/历史同一条 apply）。 */
    fun setQuickMacros(context: Context, macros: List<RailOptionRules.QuickMacro>) {
        prefs(context).edit().putString(KEY_QUICK_MACROS, RailOptionRules.encodeQuickMacros(macros)).apply()
    }

    /**
     * 新增一条快捷宏。
     *
     * @return false = 已满 [RailOptionRules.QUICK_MACRO_CAP] 条或内容为空（**不静默淘汰**
     *   已有宏：宏是带内容的配置，丢一条玩家会真丢东西），调用方据此提示玩家先删一条。
     */
    fun addQuickMacro(context: Context, macro: RailOptionRules.QuickMacro): Boolean {
        val next = RailOptionRules.addQuickMacro(quickMacros(context), macro) ?: return false
        setQuickMacros(context, next)
        return true
    }

    /** 改第 [index] 条（顺序不变）。 */
    fun updateQuickMacro(context: Context, index: Int, macro: RailOptionRules.QuickMacro) {
        setQuickMacros(context, RailOptionRules.updateQuickMacro(quickMacros(context), index, macro))
    }

    /** 删第 [index] 条。 */
    fun removeQuickMacro(context: Context, index: Int) {
        setQuickMacros(context, RailOptionRules.removeQuickMacro(quickMacros(context), index))
    }

    /** 清空全部快捷宏。 */
    fun clearQuickMacros(context: Context) {
        prefs(context).edit().remove(KEY_QUICK_MACROS).apply()
    }

    /** 输入栏小抓手在底边的水平位置（0..1，0 = 最左、1 = 最右）；缺省贴右。 */
    fun inputKnobFraction(context: Context): Float =
        prefs(context).getFloat(KEY_INPUT_KNOB, DEFAULT_INPUT_KNOB).coerceIn(0.02f, 0.98f)

    /** 写输入栏小抓手的水平位置。 */
    fun setInputKnobFraction(context: Context, fraction: Float) {
        prefs(context).edit().putFloat(KEY_INPUT_KNOB, fraction.coerceIn(0.02f, 0.98f)).apply()
    }

    /** 输入栏小抓手缺省位置：贴右（0.97 ≈ 距右沿一小段）。 */
    const val DEFAULT_INPUT_KNOB: Float = 0.97f
    /** 启动画面样式：缺省 [DEFAULT_LOADING_STYLE]。 */
    fun loadingStyle(context: Context): LoadingStyle =
        LoadingStyle.fromStored(prefs(context).getString(KEY_LOADING_STYLE, null))

    /** 写启动画面样式。 */
    fun setLoadingStyle(context: Context, style: LoadingStyle) {
        prefs(context).edit().putString(KEY_LOADING_STYLE, style.stored).apply()
    }

    /**
     * 「显示大小」档位（存的是守护下限列数）：缺省 [DEFAULT_FONT_COLUMNS]。
     *
     * @return 一定是 [FONT_COLUMN_OPTIONS] 里的某个 `columns`；存放值不认识时回落缺省
     */
    fun fontColumns(context: Context): Int {
        val stored = prefs(context).getInt(KEY_FONT_COLUMNS, DEFAULT_FONT_COLUMNS)
        return if (FONT_COLUMN_OPTIONS.any { it.columns == stored }) stored else DEFAULT_FONT_COLUMNS
    }

    /**
     * 字号缩放倍率：`[DEFAULT_FONT_COLUMNS] / [fontColumns]`（标准 1.00、大 1.51、特大 1.81、小 0.91）。
     *
     * 交给 `CanvasConsoleView(fontScale = …)`：先乘在 `emuera.config` 的 `FONT SIZE` 上，
     * 再走列数守护。于是「显示大小」档既真实放大字号，也不覆盖玩家在字体页改的 `FONT SIZE`。
     */
    fun fontScale(context: Context): Float = DEFAULT_FONT_COLUMNS.toFloat() / fontColumns(context)

    /**
     * 写字号档位。不认识的列数按缺省 136 落盘（不抛异常：设置页只可能传枚举内的值，
     * 兜底只为防将来调用方传错）。
     */
    fun setFontColumns(context: Context, columns: Int) {
        val normalized = if (FONT_COLUMN_OPTIONS.any { it.columns == columns }) columns else DEFAULT_FONT_COLUMNS
        prefs(context).edit().putInt(KEY_FONT_COLUMNS, normalized).apply()
    }

    /**
     * 「列数守护」开关：缺省**开**。
     *
     * 开：字号 = `FONT SIZE` × [fontScale]；若屏宽仍铺不下 [fontColumns] 列，就压小字号到
     *     「刚好铺下」—— 128 格地图行不折行，代价是窄屏字更小。
     * 关：字号优先（`FONT SIZE` × [fontScale]），列数随字号走，宽地图行可能折行。
     *
     * 已不再是「一页正好铺满」：一页行数由可见高 ÷ 行高直接得出（内容超出就滚动，
     * 见 `ScreenLayout.resolve`）。
     */
    fun screenFit(context: Context): Boolean = readScreenFit(prefs(context))

    /** 写「列数守护」开关（下一次装配生效，与列数下限档位同口径）。 */
    fun setScreenFit(context: Context, enabled: Boolean) = writeScreenFit(prefs(context), enabled)

    // 与 LIVE-REFLOW 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    internal fun readScreenFit(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_SCREEN_FIT, DEFAULT_SCREEN_FIT)

    internal fun writeScreenFit(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_SCREEN_FIT, enabled).apply()
    }

    /**
     * 「窗口尺寸变化时自动重排」开关（LIVE-REFLOW）：缺省 **false**。
     *
     * false = 现有横屏/竖屏路径完全不动（宽度在会话期冻结，只改可见行数）；
     * true  = 窗口宽度变化时由引擎重排历史行，横屏分屏/键盘/折叠屏同样生效。
     */
    fun liveReflow(context: Context): Boolean = readLiveReflow(prefs(context))

    /** 写 LIVE-REFLOW 开关（下一次会话装配生效；设置页随改随存，与字号档同口径需重开一局）。 */
    fun setLiveReflow(context: Context, enabled: Boolean) =
        writeLiveReflow(prefs(context), enabled)

    // 与竖屏四键同款的 internal read/write 层（以 SharedPreferences 为参、不走 prefs()）：
    // 纯 JVM 可注入测试桩，公开函数只做 prefs(context) 接线。
    internal fun readLiveReflow(sp: SharedPreferences): Boolean = sp.getBoolean(KEY_LIVE_REFLOW, false)

    internal fun writeLiveReflow(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_LIVE_REFLOW, enabled).apply()
    }

    /**
     * 逐帧诊断记录开关（缺省关）。玩家侧取证通道：开了之后「分享诊断」里多一段逐帧版面事实。
     *
     * 只影响诊断记录，不参与任何游戏语义 —— 关态与未加本功能时逐值相同。
     */
    fun frameTraceEnabled(context: Context): Boolean = readFrameTrace(prefs(context))

    /** 写逐帧诊断开关（设置页随改随存；生效点见 `FrameTraceChannel` 的 attach/detach）。 */
    fun setFrameTraceEnabled(context: Context, enabled: Boolean) =
        writeFrameTrace(prefs(context), enabled)

    internal fun readFrameTrace(sp: SharedPreferences): Boolean = sp.getBoolean(KEY_FRAME_TRACE, false)

    internal fun writeFrameTrace(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_FRAME_TRACE, enabled).apply()
    }

    /**
     * 画面左右留白居中档位（每侧留白 = 物理内容宽 × 比例）：缺省 [DEFAULT_CONTENT_MARGIN_FRACTION]。
     *
     * 圆屏/圆角屏的壳层显示适配：两侧留黑边、内容居中，列数守护在去留白宽上继续保列数
     * （字号相应让步）。与 `WINDOW WIDTH`（emuera.config）无关——移植侧显示偏好，
     * 不进游戏配置链。存放值必须在 [CONTENT_MARGIN_OPTIONS] 里，不认识回落缺省。
     *
     * @return 一定是 [CONTENT_MARGIN_OPTIONS] 里的某个值
     */
    fun contentMarginFraction(context: Context): Float =
        readContentMarginFraction(prefs(context))

    /** 写留白档位（重开一局生效；设置页随改随存，与字号档同口径）。 */
    fun setContentMarginFraction(context: Context, fraction: Float) =
        writeContentMarginFraction(prefs(context), fraction)

    // 与 liveReflow 同款的 internal read/write 层（以 SharedPreferences 为参、不走 prefs()）：
    // 纯 JVM 可注入测试桩，公开函数只做 prefs(context) 接线。
    internal fun readContentMarginFraction(sp: SharedPreferences): Float {
        val stored = sp.getFloat(KEY_CONTENT_MARGIN_FRACTION, DEFAULT_CONTENT_MARGIN_FRACTION)
        return if (CONTENT_MARGIN_OPTIONS.any { it == stored }) stored else DEFAULT_CONTENT_MARGIN_FRACTION
    }

    internal fun writeContentMarginFraction(sp: SharedPreferences, fraction: Float) {
        val normalized =
            if (CONTENT_MARGIN_OPTIONS.any { it == fraction }) fraction else DEFAULT_CONTENT_MARGIN_FRACTION
        sp.edit().putFloat(KEY_CONTENT_MARGIN_FRACTION, normalized).apply()
    }

    /**
     * 「键入回显复刻」（ECHO-INPUT）开关，缺省 [DEFAULT_ECHO_INPUT]（关）。
     *
     * false = 回显行仍打进逻辑行表、只从上屏帧剔除（玩家看不到编号，脚本侧零偏离）；
     * true = 原版 `EmueraConsole.cs:1280-1282` 行为（编号可见）。两档都接
     * `holdFrame` 先于打印，动态地图那笔帧闸修复恒生效。
     * 装配期读数：与 LIVE-REFLOW 同口径，**下一次会话装配**才吃到（设置页随改随存）。
     */
    fun echoInput(context: Context): Boolean = readEchoInput(prefs(context))

    /** 写 ECHO-INPUT 开关（下一次会话装配生效）。 */
    fun setEchoInput(context: Context, enabled: Boolean) =
        writeEchoInput(prefs(context), enabled)

    // 与 LIVE-REFLOW 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    internal fun readEchoInput(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_ECHO_INPUT, DEFAULT_ECHO_INPUT)

    internal fun writeEchoInput(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_ECHO_INPUT, enabled).apply()
    }

    /**
     * 「指令后等一次按键」（COMEND-WAIT）开关，缺省 [DEFAULT_COMMAND_END_WAIT]（开 = 原版）。
     *
     * true = 每条返回非 0 的指令回菜单前等一次回车（`Process.cs:2417` / `:2449-2455`）；
     * false = 整条链都不等（2026-11-02 那版「只清掃被拒」的动机已由这一档整体表达）。
     * 装配期读数：与 ECHO-INPUT 同口径，**下一次会话装配**才吃到（设置页随改随存）。
     */
    fun commandEndWait(context: Context): Boolean = readCommandEndWait(prefs(context))

    /** 写 COMEND-WAIT 开关（下一次会话装配生效）。 */
    fun setCommandEndWait(context: Context, enabled: Boolean) =
        writeCommandEndWait(prefs(context), enabled)

    // 与 ECHO-INPUT 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    internal fun readCommandEndWait(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_COMMAND_END_WAIT, DEFAULT_COMMAND_END_WAIT)

    internal fun writeCommandEndWait(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_COMMAND_END_WAIT, enabled).apply()
    }

    /**
     * 右侧选项栏是否展开（螺旋图标控制）；缺省 [DEFAULT_OPTION_RAIL]（开）。
     *
     * 存的是「上次退出时的可见状态」：真机上一旦关掉，下次进游戏不会被自动弹回。
     */
    fun optionRailVisible(context: Context): Boolean = readOptionRail(prefs(context))

    /** 写右侧选项栏可见状态。 */
    fun setOptionRailVisible(context: Context, visible: Boolean) =
        writeOptionRail(prefs(context), visible)

    // 与 LIVE-REFLOW 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    internal fun readOptionRail(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_OPTION_RAIL, DEFAULT_OPTION_RAIL)

    internal fun writeOptionRail(sp: SharedPreferences, visible: Boolean) {
        sp.edit().putBoolean(KEY_OPTION_RAIL, visible).apply()
    }

    /**
     * 标题彩蛋（`@BADAPPLE`）是否**屏蔽**：缺省 [DEFAULT_DISABLE_BAD_APPLE]（开 = 屏蔽）。
     *
     * 读点只有一处：`MainActivity` 在 **UI 线程**取好，经 `GameHost` 的构造参数传给
     * `GameSession(disabledFunctions = …)`（装配期在 VM 线程上，那里不许摸偏好表）；
     * 下一次进游戏生效（会话构造期定死，与字号档位同口径）。
     */
    fun disableBadApple(context: Context): Boolean = readDisableBadApple(prefs(context))

    /** 写标题彩蛋屏蔽开关（下一次装配生效）。 */
    fun setDisableBadApple(context: Context, disabled: Boolean) =
        writeDisableBadApple(prefs(context), disabled)

    // 与 LIVE-REFLOW 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    // 生产读点只有一处：`MainActivity` 在 UI 线程读好、经 `GameHost(disableBadApple = …)` 传入
    // （装配期在 VM 线程上，那里不许直接摸偏好表，见 GameHost 构造参数的 KDoc）。
    internal fun readDisableBadApple(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_DISABLE_BAD_APPLE, DEFAULT_DISABLE_BAD_APPLE)

    internal fun writeDisableBadApple(sp: SharedPreferences, disabled: Boolean) {
        sp.edit().putBoolean(KEY_DISABLE_BAD_APPLE, disabled).apply()
    }

    /**
     * 长页忙碌提示开关：缺省 [DEFAULT_BUSY_INDICATOR]（开）。
     *
     * 与 [disableBadApple] 同一条线程纪律：读点只有一处（`MainActivity` 在 **UI 线程**
     * 取好、经 `GameHost(busyIndicator = …)` 构造参数传给页中检查点），`GameHost` 内部
     * 不许再摸偏好表。改完下一局生效（宿主构造期定死，与 liveReflow 同口径）。
     */
    fun busyIndicator(context: Context): Boolean = readBusyIndicator(prefs(context))

    /** 写长页忙碌提示开关（下一次装配生效）。 */
    fun setBusyIndicator(context: Context, enabled: Boolean) =
        writeBusyIndicator(prefs(context), enabled)

    // 与 LIVE-REFLOW 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    internal fun readBusyIndicator(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_BUSY_INDICATOR, DEFAULT_BUSY_INDICATOR)

    internal fun writeBusyIndicator(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_BUSY_INDICATOR, enabled).apply()
    }

    /**
     * RT2 Mod 运行时装配总闸（`wave/mod-b1` 装配链的唯一开关）：缺省 [DEFAULT_MOD_RUNTIME]（开）。
     *
     * 与 [disableBadApple] 同一条线程纪律：读点只有一处（`MainActivity` 在 **UI 线程**
     * 取好，经 `GameHost(modRuntime = ...)` 构造参数传入；装配期在 VM 线程上，那里不许摸偏好表）。
     * 关态装配链一次都不进（`ModRuntimeHost` 构造次数 == 0），磁盘都不碰。
     *
     * **写面归属**：玩家面开关（2026-11-12 接线：`SettingsActivity` 的 `modRuntimeTitle/Hint`
     * 行 → [setModRuntime]）。缺省值自 2026-11-17 起为开（主人裁定「让 Mod 真正可用」）：
     * 新装/从未改过的玩家开箱即走完整装配链，关掉即回到与未接 Mod 运行时逐字节等价的旁路。
     * 走 [resetAll] 的 `clear()` 一并恢复缺省。
     */
    fun modRuntime(context: Context): Boolean = readModRuntime(prefs(context))

    /** 写 Mod 运行时总闸（随改随存，下一次会话装配生效）。 */
    fun setModRuntime(context: Context, enabled: Boolean) = writeModRuntime(prefs(context), enabled)

    // 与 LIVE-REFLOW 同款的 internal read/write 层：纯 JVM 可注入测试桩，公开函数只做接线。
    internal fun readModRuntime(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_MOD_RUNTIME, DEFAULT_MOD_RUNTIME)

    internal fun writeModRuntime(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_MOD_RUNTIME, enabled).apply()
    }

    /**
     * 路 B dex 装载总闸（ADR-013 D5）：缺省 [DEFAULT_MOD_DEX_LOADER]（**关**，与 [modRuntime] 相反）。
     *
     * 线程纪律照 [modRuntime]：读点只有 UI 线程一处，经构造参数单路传入；关态装载链一次都不进。
     * 纯 JVM 可注入层是 [readModDexLoader]（与 [readModRuntime] 同款）：测试直测它，公开函数只做接线。
     * 写面（设置页总闸开关 + 二次确认）与玩家可见文案见 S8（task-191）。
     */
    fun modDexLoaderEnabled(context: Context): Boolean = readModDexLoader(prefs(context))

    /**
     * 路 B dex 装载总闸的写面（S8 / task-191）：设置页二次确认后落盘，返回**读回校验**结果。
     *
     * 为什么不是 `Unit`：`SharedPreferences.apply()` 没有返回值，而全仓写盘点一律 apply
     * （UI 线程不许 `commit()` 探盘，会撞 StrictMode 的 UI 线程磁盘写）。所以「有没有真写进盘」
     * 只能靠写完立刻读回比对：false 时设置页给玩家红字，不静默当成功。既有
     * [writeModDexLoader] 的 apply 语义不动，本函数只加一层读回。
     */
    fun setModDexLoader(context: Context, enabled: Boolean): Boolean =
        persistModDexLoader(prefs(context), enabled)

    // 与 [readModRuntime] 同款的 internal read/write 层，测试直测（写面 = S8 已接线）。
    internal fun readModDexLoader(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_MOD_DEX_LOADER, DEFAULT_MOD_DEX_LOADER)

    internal fun writeModDexLoader(sp: SharedPreferences, enabled: Boolean) {
        sp.edit().putBoolean(KEY_MOD_DEX_LOADER, enabled).apply()
    }

    internal fun persistModDexLoader(sp: SharedPreferences, enabled: Boolean): Boolean {
        writeModDexLoader(sp, enabled)
        return readModDexLoader(sp) == enabled
    }

    /**
     * 玩家授权诊断通道（判词 9 PDIAG1）：缺省 DEFAULT_PLAYER_DIAGNOSTICS = 关 ⇒ release 包
     * 连 DevTools 轮询线程都不建（不是「起了不读」）。
     *
     * 读点纪律同 modRuntime：只在 **UI 线程** 读。唯一的装载期读点在 DevToolsHost.startOnce
     * 里，而那条函数被 VM 线程的装载尾段调用（GameHost.kt:1050），所以非 debuggable 时它先 hop
     * 回主线程再读这里，VM 线程本身不摸偏好表（BusyIndicatorWiringTest 钉的同一口径）。
     * 纯 JVM 可注入层是 readPlayerDiagnostics，公开函数只做接线。
     */
    fun playerDiagnosticsEnabled(context: Context): Boolean = readPlayerDiagnostics(prefs(context))

    /**
     * 写授权开关（设置页二次确认后落盘），返回**读回校验**结果；理由与形状逐字照 setModDexLoader
     * （apply() 没有返回值，UI 线程又不许 commit 探盘）。开与关都刷新 KEY_PLAYER_DIAG_AUTHORED_AT，
     * 供日志与诊断报告写「谁在什么时候打开过」。
     *
     * @return false = 没真写进盘，调用方必须给玩家报错，不许静默当成功
     */
    fun setPlayerDiagnostics(context: Context, enabled: Boolean): Boolean =
        persistPlayerDiagnostics(prefs(context), enabled)

    internal fun readPlayerDiagnostics(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_PLAYER_DIAGNOSTICS, DEFAULT_PLAYER_DIAGNOSTICS)

    /** 写开关 + 同一次编辑里刷新取证时刻：一次 apply，不留「开了却没记时刻」的半程状态。 */
    internal fun writePlayerDiagnostics(sp: SharedPreferences, enabled: Boolean) {
        sp.edit()
            .putBoolean(KEY_PLAYER_DIAGNOSTICS, enabled)
            .putLong(KEY_PLAYER_DIAG_AUTHORED_AT, System.currentTimeMillis())
            .apply()
    }

    internal fun persistPlayerDiagnostics(sp: SharedPreferences, enabled: Boolean): Boolean {
        writePlayerDiagnostics(sp, enabled)
        return readPlayerDiagnostics(sp) == enabled
    }

    /** 最近一次拨动授权开关的时刻（毫秒）；从未拨过 = 0 ⇒ 取证行写「从未授权」。 */
    fun playerDiagnosticsAuthoredAt(context: Context): Long =
        readPlayerDiagnosticsAuthoredAt(prefs(context))

    internal fun readPlayerDiagnosticsAuthoredAt(sp: SharedPreferences): Long =
        sp.getLong(KEY_PLAYER_DIAG_AUTHORED_AT, 0L)

    // ---- RT2 玩家排序配置（L2，2026-11-14）：公开读点 / 纯转换 / 设置页读写 ----
    // 完整实现与归一化语义在文件顶层 `ModPackageOrderStore`：UiPrefs 类体已逼近 detekt
    // LargeClass 阈值，整块逻辑留在外面才不把闸门顶红（不改阈值 / 不加 @Suppress / 不改 baseline）。
    // 只补壳层「配置来源」：一行核心排序语义都不改（语义权威 = LinkOrder KDoc 与
    // CONTRACT-RT2-M2-3.md §3.1）。读点纪律照 [modRuntime]：偏好只在 UI 线程读。

    /** 逗号分隔串 → 归一化 packageId 列表（实现见 `ModPackageOrderStore.parse`）。 */
    fun parsePreferredPackageIds(raw: String): List<String> = ModPackageOrderStore.parse(raw)

    /** 纯 JVM 可注入层：读原始串（缺键回落空串 = 缺省）；与 [readModRuntime] 同款。 */
    internal fun readPreferredPackageIds(sp: SharedPreferences): String = ModPackageOrderStore.read(sp)

    /** 纯 JVM 可注入层：写原始串（随改随存，apply）。 */
    internal fun writePreferredPackageIds(sp: SharedPreferences, raw: String) = ModPackageOrderStore.write(sp, raw)

    /**
     * 构造期读一次（**UI 线程**）并转成 [com.eratw.port.core.patch.LinkOrder] 的输入：
     * 归一化后的 preferredPackageIds 列表。
     *
     * 与 [modRuntime] 同一条线程纪律：读点只有一处 —— `MainActivity` 在 UI 线程取好，
     * 经 `GameHost` 构造参数单路传入（`linkOrder = LinkOrder(preferredPackageIds(this))`），
     * 装配期在 VM 线程上不许摸偏好表。**需组长接线**：`GameHost` 构造参数 +
     * `ModChainAssembly.assemble` 的 `linkOrder =` 实参（worker 不碰 GameHost）。
     */
    fun preferredPackageIds(context: Context): List<String> = ModPackageOrderStore.list(prefs(context))

    /** 设置页读原始串（UI 线程）：玩家输入原样回填输入框，归一化留到 [preferredPackageIds]。 */
    fun preferredPackageIdsRaw(context: Context): String = ModPackageOrderStore.read(prefs(context))

    /** 设置页写原始串（UI 线程，随改随存；下一次会话装配生效）。 */
    fun setPreferredPackageIds(context: Context, raw: String) = ModPackageOrderStore.write(prefs(context), raw)

    /**
     * 是否允许自动更新通道在冷启动时联网检查（国内镜像仓通道，2026-10-29）。
     *
     * 缺省**开**，但这不等于「会出网」：整条通道还压着一条更硬的闸 —— 出包时没带
     * `-PeratwUpdateMirrors` 就没有内置源，[UpdateManager.channelEnabled] 第一行就返回。
     * 所以「开发包 / CI 包 / `--offline` 包照旧零网络行为」这条不变，本开关只服务
     * 一个真实场景：主人已经发了带源的包，玩家不想让它开机联网问一次。
     *
     * 关掉之后设置页的「立即检查更新」仍可用（那是玩家主动按的按钮，
     * 见 [UpdateUi.checkNowInteractive] 里会把它临时打开）。
     */
    fun autoUpdateEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_UPDATE, DEFAULT_AUTO_UPDATE)

    /** 写自动更新开关（随改随存，下一次检查生效）。 */
    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply()
    }

    /**
     * 「仅 Wi-Fi 下载」开关（U5 P2-10，缺省关 = 保持现状）。
     *
     * 打开后蜂窝网络点下载**不给绕行**（判定在 `UpdateUiText.wifiOnlyBlocksCellular`，
     * 话术在 `UpdateCopy.wifiOnlyCellular*`）；关态与既有「蜂窝二次确认」路径逐字等价。
     * 走 [resetAll] 的 `clear()` 一并恢复缺省。
     */
    fun wifiOnlyDownload(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WIFI_ONLY_DOWNLOAD, false)

    /** 写「仅 Wi-Fi 下载」开关（随改随存，与 [setAutoUpdateEnabled] 同口径）。 */
    fun setWifiOnlyDownload(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY_DOWNLOAD, enabled).apply()
    }

    // ---- 匿名玩家数统计（2026-11-08 波）------------------------------------
    // 复用 eratw_ui 这一份 SharedPreferences：不新开 XML，主线程首读的豁免窗口仍是同一发。
    // 编号与上次心跳日属于运行态（加入 KEEP_ON_RESET），开关属于设置（恢复默认时清掉）。

    /** 设置页读开关；缺键回落到 [DEFAULT_PLAYER_STATS_ENABLED]。 */
    fun playerStatsEnabled(context: Context): Boolean = readPlayerStatsEnabled(prefs(context))

    /** 写统计开关（随改随存；关掉后当天就不再发心跳，下次冷启生效）。 */
    fun setPlayerStatsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAYER_STATS_ENABLED, enabled).apply()
    }

    /**
     * 冷启接线层取偏好表的入口。与其余 getter 共用同一份实例与同一次首读，
     * 由 `PlayerStatsWiring` 在 daemon 线程上调用，不给 UI 线程添磁盘 IO。
     */
    fun playerStatsPrefs(context: Context): SharedPreferences = prefs(context)

    internal fun readPlayerStatsEnabled(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_PLAYER_STATS_ENABLED, DEFAULT_PLAYER_STATS_ENABLED)

    /** 上次成功心跳的 UTC 日；没发过 = null（接线层据此判「今天还没发」）。 */
    internal fun readPlayerStatsLastDay(sp: SharedPreferences): String? =
        sp.getString(KEY_PLAYER_STATS_LAST_DAY, null)

    /**
     * 本机匿名编号；没生成过 = null。形状不在这里判：`PlayerStatsReporter.isValidId`
     * 是唯一一道闸，坏值一律重新生成，绝不顺着 body 出网。
     */
    internal fun readPlayerStatsId(sp: SharedPreferences): String? =
        sp.getString(KEY_PLAYER_STATS_ID, null)

    internal fun writePlayerStatsId(sp: SharedPreferences, id: String) {
        sp.edit().putString(KEY_PLAYER_STATS_ID, id).apply()
    }

    /** 只在服务端 2xx 之后由接线层调用：写进去就等于「今天已数过」。 */
    internal fun writePlayerStatsLastDay(sp: SharedPreferences, day: String) {
        sp.edit().putString(KEY_PLAYER_STATS_LAST_DAY, day).apply()
    }

    // ---- 首启教程（2026-11-09）--------------------------------------------

    /** 首启教程是否已展示过；缺键 = 没展示过（首次安装首次进入时弹）。 */
    fun tutorialShown(context: Context): Boolean = readTutorialShown(prefs(context))

    /** 教程关闭时落「已读」：此后冷启不再弹，关于页仍可重新查阅。 */
    fun markTutorialShown(context: Context) {
        writeTutorialShown(prefs(context))
    }

    internal fun readTutorialShown(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_TUTORIAL_SHOWN, false)

    internal fun writeTutorialShown(sp: SharedPreferences) {
        sp.edit().putBoolean(KEY_TUTORIAL_SHOWN, true).apply()
    }

    /**
     * 玩家「以后都不」跳过的整包 versionCode（契约 §1.5，U-E）。
     *
     * 只对**这一个版本**抑制弹窗提醒（判定在 core 的 `UpdatePolicy.isVersionSuppressed`，
     * 消费点在 `UpdateManager.checkNow` 的 `skippedVersionCode` 形参）：修了致命 bug 的
     * 新版本照样提醒 —— 侧载分发没有商店强更兜底，「跳过了一个版本 = 从此与所有更新绝缘」
     * 是最不能犯的方向性错误。负数读数按 0 处理（老版本写坏 / 手工改 XML 的兜底）。
     */
    fun updateSkippedVersionCode(context: Context): Long =
        prefs(context).getLong(KEY_UPDATE_SKIPPED_VERSION_CODE, 0L).coerceAtLeast(0L)

    /**
     * 写「跳过此版本」。传非正数等于恢复提醒（与 [clearUpdateSkippedVersionCode] 同口径），
     * 这样调用方不需要在 UI 层分叉「写 0」与「删键」两种形态。
     */
    fun setUpdateSkippedVersionCode(context: Context, versionCode: Long) {
        val editor = prefs(context).edit()
        if (versionCode > 0L) editor.putLong(KEY_UPDATE_SKIPPED_VERSION_CODE, versionCode)
        else editor.remove(KEY_UPDATE_SKIPPED_VERSION_CODE)
        editor.apply()
    }

    /** 列数 → 档位名；不认识的值按缺省档显示（与 [fontColumns] 的回落一致）。 */
    fun fontColumnsLabel(columns: Int): String =
        FONT_COLUMN_OPTIONS.firstOrNull { it.columns == columns }?.label
            ?: FONT_COLUMN_OPTIONS.first { it.columns == DEFAULT_FONT_COLUMNS }.label

    /**
     * 设置页显示语言：缺省 [SettingsLanguage.DEFAULT]（简体中文）。
     *
     * 存的值不认识（老版本 / 手工改 XML）时回落缺省，不抛。设置页切换时立即落盘，
     * 只影响设置页自己的文案，不碰系统 Locale，也不重开游戏。
     */
    fun settingsLanguage(context: Context): SettingsLanguage =
        SettingsLanguage.fromStored(prefs(context).getString(KEY_SETTINGS_LANGUAGE, null))

    /** 写设置页显示语言（随改随存）。 */
    fun setSettingsLanguage(context: Context, language: SettingsLanguage) {
        prefs(context).edit().putString(KEY_SETTINGS_LANGUAGE, language.stored).apply()
    }

    // ==================================================================
    // U4 upd-rollout：灰度 bucket 与镜像健康（P1-5 / P1-7）
    // 读写口径与其余键一致：现读现用、apply() 落盘、坏值回落缺省。
    // ==================================================================

    /**
     * 灰度分桶 bucket（0..99）：**首次生成后持久化**，同一设备对所有版本稳定。
     *
     * 为什么只取一次：bucket 是设备身份，不是「这次检查」的属性。每检查一次就重新随机会让
     * 同一台设备对同一版本有时被选中、有时被挡（弹窗闪烁，发布者也没法解释灰度人数），
     * 而固定 bucket 让「把百分比从 10 调到 50」变成纯扩量、原来那 10% 一定还在名单里。
     * core 层禁随机数，所以 SecureRandom 只在这里（app 壳）出现一次，随后只读 prefs。
     * 代价是灰度用户群固定；若将来要「不同版本不同人群」，可改成对 versionCode 再做一次
     * 稳定哈希，但本波按契约取一次固定。「恢复默认设置」会清掉这个键，下次检查重新取一次。
     */
    fun rolloutBucket(context: Context): Int = rolloutBucket(prefs(context))

    /** 纯 JVM 可注入层：读不到合法 bucket 就生成一次并落盘，读得到就原样返回（稳定性在这里钉）。 */
    internal fun rolloutBucket(sp: SharedPreferences): Int {
        val stored = readRolloutBucket(sp)
        if (stored != NO_ROLLOUT_BUCKET) return stored
        val fresh = freshRolloutBucket()
        writeRolloutBucket(sp, fresh)
        return fresh
    }

    /** 读 bucket：缺席或不在 0..99 的坏值一律回落 [NO_ROLLOUT_BUCKET]（由生成口兜底）。 */
    internal fun readRolloutBucket(sp: SharedPreferences): Int {
        val stored = sp.getInt(KEY_ROLLOUT_BUCKET, NO_ROLLOUT_BUCKET)
        return if (stored in 0 until UpdatePolicy.ROLLOUT_BUCKETS) stored else NO_ROLLOUT_BUCKET
    }

    /** 唯一一次取随机数：java.security.SecureRandom，返回 0..99。core 不碰随机数。 */
    internal fun freshRolloutBucket(): Int = SecureRandom().nextInt(UpdatePolicy.ROLLOUT_BUCKETS)

    /** 写 bucket：坏值兜底成一次新生成，绝不把越界值落进偏好表。 */
    internal fun writeRolloutBucket(sp: SharedPreferences, bucket: Int) {
        val normalized =
            if (bucket in 0 until UpdatePolicy.ROLLOUT_BUCKETS) bucket else freshRolloutBucket()
        sp.edit().putInt(KEY_ROLLOUT_BUCKET, normalized).apply()
    }

    /** 镜像健康表（归一地址 → 连续失败数/最后成功时刻），供 UpdatePolicy.mirrorOrderWithHealth 消费。 */
    fun mirrorHealth(context: Context): Map<String, MirrorHealth> = readMirrorHealth(prefs(context))

    /**
     * 记一次镜像结果：成功清零失败数并记成功时刻；失败把失败数 +1（封顶 [MIRROR_FAILURE_CAP]）。
     *
     * 只记**归一后**的地址（与 core 侧的键同形）。表条目数封顶到 [UpdatePolicy.MAX_MIRRORS]：
     * 表满且来的是新地址时直接不收（内置源最多就这么多，正常路径永远不触发；这条防线挡的是
     * 将来有人把清单补充源也塞进来把偏好表撑大）。
     */
    fun noteMirrorResult(context: Context, mirrorBase: String, success: Boolean, nowMillis: Long) =
        noteMirrorResult(prefs(context), mirrorBase, success, nowMillis)

    /** 纯 JVM 可注入层：解码整表 → 更新一条 → 编码写回（表本身就是全部状态，不带跨进程缓存）。 */
    internal fun noteMirrorResult(
        sp: SharedPreferences,
        mirrorBase: String,
        success: Boolean,
        nowMillis: Long,
    ) {
        val base = UpdatePolicy.normalizeMirror(mirrorBase) ?: return
        val rows = LinkedHashMap(decodeMirrorHealth(sp.getString(KEY_MIRROR_HEALTH, "").orEmpty()))
        if (!rows.containsKey(base) && rows.size >= UpdatePolicy.MAX_MIRRORS) return
        val old = rows[base]
        rows[base] = if (success) {
            MirrorHealth(0, nowMillis.coerceAtLeast(0L))
        } else {
            MirrorHealth(
                ((old?.consecutiveFailures ?: 0) + 1).coerceAtMost(MIRROR_FAILURE_CAP),
                old?.lastSuccessMillis ?: 0L,
            )
        }
        sp.edit().putString(KEY_MIRROR_HEALTH, encodeMirrorHealth(rows)).apply()
    }

    /** 读健康表：坏行直接丢（老版本写坏 / 手工改 XML），不抛。 */
    internal fun readMirrorHealth(sp: SharedPreferences): Map<String, MirrorHealth> =
        decodeMirrorHealth(sp.getString(KEY_MIRROR_HEALTH, "").orEmpty())

    /**
     * 健康表编码：每行「base 制表符 失败数 制表符 最后成功时刻」。
     *
     * 用制表符与换行做分隔符是安全的：base 已过 [UpdatePolicy.normalizeMirror]，那里明确拒掉
     * 一切空白与控制字符（含制表符与换行），所以地址本身不会撞分隔符。
     */
    internal fun encodeMirrorHealth(rows: Map<String, MirrorHealth>): String =
        rows.entries.joinToString("\n") { (base, h) ->
            "$base\t${h.consecutiveFailures}\t${h.lastSuccessMillis}"
        }

    /** 解码：形状不对的行整行丢；失败数与时刻夹紧到非负；重复 base 取第一条。 */
    internal fun decodeMirrorHealth(raw: String): Map<String, MirrorHealth> {
        val out = LinkedHashMap<String, MirrorHealth>()
        for (line in raw.split('\n')) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size != 3) continue
            val base = parts[0]
            if (base.isEmpty() || base.any { it.isWhitespace() }) continue
            val failures = parts[1].toIntOrNull() ?: continue
            val lastOk = parts[2].toLongOrNull() ?: continue
            if (!out.containsKey(base)) {
                out[base] = MirrorHealth(
                    failures.coerceIn(0, MIRROR_FAILURE_CAP),
                    lastOk.coerceAtLeast(0L),
                )
            }
        }
        return out
    }

    // ==================================================================
    // 竖屏三区壳（契约 CONTRACT-PORTRAIT §2 PORTRAIT-P1；设计稿 docs/archive/
    // 竖屏布局设计稿.md §7 持久化键表 + §5.2 档位表。**只新增**，上面任何既有键不动。）
    // 读写口径与既有键完全一致：现读现用、apply() 落盘、坏值回落缺省。
    // ==================================================================

    /**
     * 竖屏字号档位表（设计稿 §5.2：50 特大 / 62 大·缺省 / 75 标准 / 90 小）。
     *
     * 竖屏档是**独立的逻辑列宽**（按方向在装配期选档，改档走 recreate，与横屏档互不读写），
     * 与横屏 [FONT_COLUMN_OPTIONS] 的「136 反推倍率」语义不同，故不复用那张表。
     */
    val FONT_COLUMN_OPTIONS_PORTRAIT: List<FontColumnsOption> = listOf(
        FontColumnsOption(50, "特大", "约 50 列；字最大，折行最早"),
        FontColumnsOption(62, "大", "约 62 列；竖屏缺省档"),
        FontColumnsOption(75, "标准", "约 75 列"),
        FontColumnsOption(90, "小", "约 90 列；字最小，单屏信息最多"),
    )

    /** 竖屏字号档缺省：**62** 列（设计稿 §5.2）。 */
    const val DEFAULT_FONT_COLUMNS_PORTRAIT: Int = 62

    /**
     * 屏幕方向：**只有横屏与竖屏两态**，竖屏是玩家在设置页显式打开的开关。
     *
     * 这里刻意**没有**「跟随机转」一档：那等于把方向决定权交回系统自动旋转偏好，
     * 手机竖着拿启动就会整个壳换成竖屏三区版面（2026-11-04 玩家实测的意外竖屏）。
     * 老版本存过的 `follow` 按坏值处理 ⇒ 回落 [ORIENTATION_LANDSCAPE]，不会复活那一档。
     */
    const val ORIENTATION_LANDSCAPE: String = "landscape"

    /** 屏幕方向取值：锁竖屏（设置页「竖屏模式」打开）。 */
    const val ORIENTATION_PORTRAIT: String = "portrait"

    /** 屏幕方向缺省：锁横屏（与 0.2.39 的 manifest `sensorLandscape` 行为一致）。 */
    const val DEFAULT_ORIENTATION: String = ORIENTATION_LANDSCAPE

    private const val KEY_ORIENTATION = "orientation"
    private const val KEY_FONT_COLUMNS_PORTRAIT = "font_columns_portrait"
    private const val KEY_COMMAND_TRAY_OPEN = "command_tray_open"
    private const val KEY_SPRITE_STAGE_COLLAPSED = "sprite_stage_collapsed"

    /**
     * 方向值归一：只认 `portrait`，其余（含 null、老版本的 `follow`、写坏、手改 XML）
     * 一律回落横屏 —— 竖屏只可能由玩家显式打开。
     */
    internal fun normalizeOrientation(stored: String?): String =
        if (stored == ORIENTATION_PORTRAIT) ORIENTATION_PORTRAIT else DEFAULT_ORIENTATION

    /** 竖屏列数归一：只认 50/62/75/90（[fontColumns] 同款防线），坏值回落 62。 */
    internal fun normalizeFontColumnsPortrait(stored: Int): Int =
        if (FONT_COLUMN_OPTIONS_PORTRAIT.any { it.columns == stored }) stored else DEFAULT_FONT_COLUMNS_PORTRAIT

    // 以下 read/write 内联函数以 SharedPreferences 为参（不走 [prefs]）：纯 JVM 可注入测试桩，
    // 公开函数只做 prefs(context) 接线，与既有键同形。

    internal fun readOrientation(sp: SharedPreferences): String =
        normalizeOrientation(sp.getString(KEY_ORIENTATION, null))

    internal fun writeOrientation(sp: SharedPreferences, value: String) {
        sp.edit().putString(KEY_ORIENTATION, normalizeOrientation(value)).apply()
    }

    internal fun readFontColumnsPortrait(sp: SharedPreferences): Int =
        normalizeFontColumnsPortrait(sp.getInt(KEY_FONT_COLUMNS_PORTRAIT, DEFAULT_FONT_COLUMNS_PORTRAIT))

    internal fun writeFontColumnsPortrait(sp: SharedPreferences, columns: Int) {
        sp.edit().putInt(KEY_FONT_COLUMNS_PORTRAIT, normalizeFontColumnsPortrait(columns)).apply()
    }

    internal fun readCommandTrayOpen(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_COMMAND_TRAY_OPEN, DEFAULT_COMMAND_TRAY_OPEN)

    internal fun writeCommandTrayOpen(sp: SharedPreferences, open: Boolean) {
        sp.edit().putBoolean(KEY_COMMAND_TRAY_OPEN, open).apply()
    }

    internal fun readSpriteStageCollapsed(sp: SharedPreferences): Boolean =
        sp.getBoolean(KEY_SPRITE_STAGE_COLLAPSED, DEFAULT_SPRITE_STAGE_COLLAPSED)

    internal fun writeSpriteStageCollapsed(sp: SharedPreferences, collapsed: Boolean) {
        sp.edit().putBoolean(KEY_SPRITE_STAGE_COLLAPSED, collapsed).apply()
    }

    /** 指令盘展开态记忆：缺省**收**（false）。仅记 G1/G2/G3 的用户手动开合；G0 自动展开不受它影响（设计稿 §3.5）。 */
    fun commandTrayOpen(context: Context): Boolean = readCommandTrayOpen(prefs(context))

    /** 写指令盘展开态（随改随存）。 */
    fun setCommandTrayOpen(context: Context, open: Boolean) = writeCommandTrayOpen(prefs(context), open)

    /** 指令盘展开态缺省：收起（peek），正文优先。 */
    const val DEFAULT_COMMAND_TRAY_OPEN: Boolean = false

    /**
     * 立绘舞台折叠状态：缺省**不折**（false）。跨屏记忆（设计稿 §3.6.3：玩家既然想看文字，
     * 就别每屏弹回来——与 [commandTrayOpen] 的「只记手动偏好」不同，这里跨屏生效）。
     */
    fun spriteStageCollapsed(context: Context): Boolean = readSpriteStageCollapsed(prefs(context))

    /** 写立绘舞台折叠状态（随改随存）。 */
    fun setSpriteStageCollapsed(context: Context, collapsed: Boolean) =
        writeSpriteStageCollapsed(prefs(context), collapsed)

    /** 立绘舞台折叠缺省：展开。 */
    const val DEFAULT_SPRITE_STAGE_COLLAPSED: Boolean = false

    /** 屏幕方向：缺省 [DEFAULT_ORIENTATION]（横屏）；存的值不认识时回落缺省。 */
    fun orientation(context: Context): String = readOrientation(prefs(context))

    /** 写屏幕方向（除了 `portrait` 一律落横屏，与 [setFontColumns] 同款防线）。 */
    fun setOrientation(context: Context, value: String) = writeOrientation(prefs(context), value)

    /** 「竖屏模式」开关：true = 锁竖屏，false = 锁横屏（缺省）。 */
    fun portraitMode(context: Context): Boolean =
        readOrientation(prefs(context)) == ORIENTATION_PORTRAIT

    /** 写「竖屏模式」开关（设置页随改随存；生效要重开 Activity/游戏）。 */
    fun setPortraitMode(context: Context, enabled: Boolean) =
        writeOrientation(
            prefs(context),
            if (enabled) ORIENTATION_PORTRAIT else ORIENTATION_LANDSCAPE,
        )

    /**
     * 竖屏字号档位（存的是列数）：缺省 [DEFAULT_FONT_COLUMNS_PORTRAIT]。
     *
     * @return 一定是 [FONT_COLUMN_OPTIONS_PORTRAIT] 里的某个 `columns`；存放值不认识时回落缺省
     */
    fun fontColumnsPortrait(context: Context): Int = readFontColumnsPortrait(prefs(context))

    /** 写竖屏字号档位。不认识的列数按缺省 62 落盘（不抛异常，防线同 [setFontColumns]）。 */
    fun setFontColumnsPortrait(context: Context, columns: Int) =
        writeFontColumnsPortrait(prefs(context), columns)

    // ==================================================================
    // 更新下载失败单持久化（U2 病②，CONTRACT-UPD1-3LANE §2）。
    // **只新增**，上面任何既有键不动。读写口径与既有键一致：现读现用、apply() 落盘、坏值整单作废。
    //
    // 病根：旧版「最近一次没下成的单」只活在 [UpdateDownloadService] 的一个 @Volatile 内存位上，
    // 进程一死「点一下重试」的入口就丢。这里落成五个独立键（fileName/sizeBytes/sha256/kind/
    // assetsVersion），**缺一视为无效** —— 读侧整单返回 null，绝不还原出半张残单。
    // 还原必须重过成单同款校验（UpdatePolicy.isArtifactFileName 等，jobFrom 的口径）：
    // 盘是「老版本写坏、手工改 XML」都可能脏的地方，宁可丢一次重试入口，不许把残单喂回下载。
    // ==================================================================

    private const val KEY_UPDATE_FAILED_FILE_NAME = "update_failed_file_name"
    private const val KEY_UPDATE_FAILED_SIZE_BYTES = "update_failed_size_bytes"
    private const val KEY_UPDATE_FAILED_SHA256 = "update_failed_sha256"
    private const val KEY_UPDATE_FAILED_KIND = "update_failed_kind"
    private const val KEY_UPDATE_FAILED_ASSETS_VERSION = "update_failed_assets_version"

    /**
     * 读失败单（以 SharedPreferences 为参、不走 [prefs]：纯 JVM 可注入测试桩，与竖屏键同形）。
     *
     * 五键缺一、字节数非正、摘要/文件名空白、通道名不认识、文件名过不了
     * [UpdatePolicy.isArtifactFileName] —— 任何一条不满足都整单作废返回 null。
     *
     * L4-10：摘要还要过 [UpdatePolicy.normalizeSha]（64 位十六进制）并**归一成小写**后还原。
     * 旧实现只判 `isBlank()`：盘上写坏/大小写混入的摘要会让下载把整包下完、digest 在最后一
     * 字节才不符 ⇒ 每条镜像都白下一次几百 MiB，恰好是 UpdateDownloadPlan 类头点名要避免的
     * 「每次失败在最末尾」。坏形状整单作废，宁可丢一次重试入口。
     */
    internal fun readFailedDownload(sp: SharedPreferences): FailedDownloadRecord? {
        val name = sp.getString(KEY_UPDATE_FAILED_FILE_NAME, null) ?: return null
        val sha = UpdatePolicy.normalizeSha(sp.getString(KEY_UPDATE_FAILED_SHA256, null)) ?: return null
        val kindStored = sp.getString(KEY_UPDATE_FAILED_KIND, null) ?: return null
        if (!sp.contains(KEY_UPDATE_FAILED_SIZE_BYTES) || !sp.contains(KEY_UPDATE_FAILED_ASSETS_VERSION)) return null
        val size = sp.getLong(KEY_UPDATE_FAILED_SIZE_BYTES, -1L)
        val assetsVersion = sp.getLong(KEY_UPDATE_FAILED_ASSETS_VERSION, 0L)
        val kind = UpdateDownloadKind.entries.firstOrNull { it.name == kindStored } ?: return null
        if (name.isBlank() || size <= 0L) return null
        if (!UpdatePolicy.isArtifactFileName(name)) return null
        return FailedDownloadRecord(name, size, sha, kind, assetsVersion)
    }

    /** 写失败单（五个键一次成批落盘，不留半张）。 */
    internal fun writeFailedDownload(sp: SharedPreferences, record: FailedDownloadRecord) {
        sp.edit()
            .putString(KEY_UPDATE_FAILED_FILE_NAME, record.fileName)
            .putLong(KEY_UPDATE_FAILED_SIZE_BYTES, record.sizeBytes)
            .putString(KEY_UPDATE_FAILED_SHA256, record.sha256Hex)
            .putString(KEY_UPDATE_FAILED_KIND, record.kind.name)
            .putLong(KEY_UPDATE_FAILED_ASSETS_VERSION, record.assetsVersion)
            .apply()
    }

    /** 清失败单（五个键一起摘；读侧对空表返回 null，即「没有可重放的失败单」）。 */
    internal fun clearFailedDownload(sp: SharedPreferences) {
        sp.edit()
            .remove(KEY_UPDATE_FAILED_FILE_NAME)
            .remove(KEY_UPDATE_FAILED_SIZE_BYTES)
            .remove(KEY_UPDATE_FAILED_SHA256)
            .remove(KEY_UPDATE_FAILED_KIND)
            .remove(KEY_UPDATE_FAILED_ASSETS_VERSION)
            .apply()
    }

    /** 读失败单：见 [readFailedDownload] 的校验口径；坏单/无单返回 null。 */
    fun failedDownloadRecord(context: Context): FailedDownloadRecord? =
        readFailedDownload(prefs(context))

    /** 写失败单（随记随存；调用方是 [UpdateDownloadService] 的失败收尾路）。 */
    fun writeFailedDownloadRecord(context: Context, record: FailedDownloadRecord) =
        writeFailedDownload(prefs(context), record)

    /** 清失败单（成功一单清空盘 + 内存；内存那份归 [UpdateDownloadService] 自己管）。 */
    fun clearFailedDownloadRecord(context: Context) =
        clearFailedDownload(prefs(context))

    // ==================================================================
    // 数据通道选件读数持久化（U11 P2-13，CONTRACT-UPD3-3LANE §2 U11）。
    // **只新增**，上面任何既有键不动。落盘在 UiPrefs 这一份（eratw_ui），语义 =「上一次检查选出的档位」：
    // 检查跑到选件那一步才写、跑不到就不写（读侧回落 null），「恢复默认设置」的 resetAll 用 edit().clear()
    // 天然把它清掉（它不在 [KEEP_ON_RESET]），不需要单写删除逻辑。判据一律留 core，app 只存取与读出。
    // 编码用竖线连接五个读数（kind 枚举名 / 盘可信 / 块级件 / 文件级件 / 全量件），逐格校验、
    // 任一格坏（枚举名不认识、布尔位不是 0/1、字段数不对）整条作废返回 null，绝不还原半条残读数。
    // ==================================================================

    private const val KEY_DATA_ARTIFACT_READOUT = "last_data_artifact_readout"

    /** 读上一次检查选出的数据通道档位摘要；没写过或坏值返回 null（不猜、不残留）。 */
    fun dataArtifactReadout(context: Context): DataArtifactReadout? =
        readDataArtifactReadout(prefs(context))

    /** 写上一次检查选出的档位摘要（选件那一刻调；随记随存，与 [setWifiOnlyDownload] 同口径 apply()）。 */
    fun setDataArtifactReadout(context: Context, readout: DataArtifactReadout) =
        writeDataArtifactReadout(prefs(context), readout)

    /** 纯 JVM 可注入层：读，见 [dataArtifactReadout] 的校验口径；坏值/无值返回 null。 */
    internal fun readDataArtifactReadout(sp: SharedPreferences): DataArtifactReadout? {
        val raw = sp.getString(KEY_DATA_ARTIFACT_READOUT, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 5) return null
        val kind = DataArtifactKind.entries.firstOrNull { it.name == parts[0] } ?: return null
        val flags = parts.drop(1).map { it.toIntOrNull() }.takeIf { list -> list.all { it == 0 || it == 1 } }
            ?: return null
        return DataArtifactReadout(
            kind = kind,
            diskTrusted = flags[0] == 1,
            hasBlockDelta = flags[1] == 1,
            hasFileDelta = flags[2] == 1,
            hasFull = flags[3] == 1,
        )
    }

    /** 纯 JVM 可注入层：写（五格一次成批落盘，布尔归一 0/1）。 */
    internal fun writeDataArtifactReadout(sp: SharedPreferences, readout: DataArtifactReadout) {
        fun bit(v: Boolean) = if (v) "1" else "0"
        val raw = "${readout.kind.name}|${bit(readout.diskTrusted)}|" +
            "${bit(readout.hasBlockDelta)}|${bit(readout.hasFileDelta)}|${bit(readout.hasFull)}"
        sp.edit().putString(KEY_DATA_ARTIFACT_READOUT, raw).apply()
    }

    // ==================================================================
    // 坏基线拒装账与挡板读数（U13 upd-baccount）。**只新增**，上面任何既有键不动。
    // 落盘口径与 [KEY_DATA_ARTIFACT_READOUT] 一族同形：单键竖线编码、逐格校验、
    // 任一格坏（枚举名不认识、版本非正）整条作废返回 null，绝不还原半条残账。
    //
    // 病根：整包数据被拒装（[UpdateManager.applyStagedBundleIfPresent] 的 REJECTED 出口）后
    // 暂存删了、prefs 指针清了，盘上不留任何「这个版本的这个包是坏件」的痕迹 —— 下一轮检查
    // 决策面不知道上一次拒过，同一个坏件每 12 小时重下重拒，流量白烧。这里落一条账
    // （被拒的目标数据版本 + 档位 + 时刻），检查决策读它把同键挡到下一档。
    // 写侧点唯一（只在 REJECTED 档写）；清侧两处（应用成功、换版本暂存成功）走
    // [clearRejectedBundleState] 一次把账与挡板读数一起摘。「恢复默认设置」的 clear() 天然清掉，
    // 不进 [KEEP_ON_RESET] —— 账被清的最坏后果是重下一遍坏包再重新记账，方向安全。
    // ==================================================================

    /** 上一次拒装的账（单键：被拒的目标数据版本 竖线 档位 竖线 时刻）。 */
    private const val KEY_UPDATE_REJECTED_BUNDLE = "update_rejected_bundle"

    /** 挡板生效读数（单键：被拒的目标数据版本 竖线 这次改走的档位；NONE = 暂不取数据包）。 */
    private const val KEY_UPDATE_REJECTED_FALLBACK = "update_rejected_fallback"

    /** 读拒装账：没写过或坏值返回 null（不猜、不残留）。 */
    fun rejectedBundleAccount(context: Context): RejectedBundleAccount? =
        readRejectedBundleAccount(prefs(context))

    /** 写拒装账（拒装出口调；随记随存，与 [setDataArtifactReadout] 同口径 apply()）。 */
    fun writeRejectedBundleAccount(context: Context, account: RejectedBundleAccount) =
        writeRejectedBundleAccount(prefs(context), account)

    /** 清拒装状态（账与挡板读数两条键一起摘）：应用成功、换版本暂存成功两个出口调。 */
    fun clearRejectedBundleState(context: Context) = clearRejectedBundleState(prefs(context))

    /** 读挡板生效读数：见 [readRejectedBundleFallback] 的校验口径；没生效/坏值返回 null。 */
    fun rejectedBundleFallback(context: Context): RejectedBundleFallback? =
        readRejectedBundleFallback(prefs(context))

    /** 只摘挡板读数（检查决策挡板没生效时调，不让旧说明残留到新版本）。 */
    fun clearRejectedBundleFallback(context: Context) = clearRejectedBundleFallback(prefs(context))

    /** 纯 JVM 可注入层：读账，坏值（枚举名不认识、版本非正、时刻为负）整条作废。 */
    internal fun readRejectedBundleAccount(sp: SharedPreferences): RejectedBundleAccount? {
        val raw = sp.getString(KEY_UPDATE_REJECTED_BUNDLE, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val version = parts[0].toLongOrNull() ?: return null
        val kind = DataArtifactKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        if (version <= 0L || at < 0L) return null
        return RejectedBundleAccount(version, kind, at)
    }

    /** 纯 JVM 可注入层：写账（三格一次成批落盘）。 */
    internal fun writeRejectedBundleAccount(sp: SharedPreferences, account: RejectedBundleAccount) {
        sp.edit()
            .putString(
                KEY_UPDATE_REJECTED_BUNDLE,
                "${account.version}|${account.kind.name}|${account.atMillis.coerceAtLeast(0L)}",
            )
            .apply()
    }

    /** 纯 JVM 可注入层：读挡板读数，坏值整条作废；NONE 是合法值（全量被拒 = 暂不取）。 */
    internal fun readRejectedBundleFallback(sp: SharedPreferences): RejectedBundleFallback? {
        val raw = sp.getString(KEY_UPDATE_REJECTED_FALLBACK, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 2) return null
        val version = parts[0].toLongOrNull() ?: return null
        val kind = DataArtifactKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        if (version <= 0L) return null
        return RejectedBundleFallback(version, kind)
    }

    /** 纯 JVM 可注入层：写挡板读数（检查决策挡板生效时调；没生效由调用方清）。 */
    internal fun writeRejectedBundleFallback(sp: SharedPreferences, fallback: RejectedBundleFallback) {
        sp.edit()
            .putString(KEY_UPDATE_REJECTED_FALLBACK, "${fallback.rejectedVersion}|${fallback.fallbackKind.name}")
            .apply()
    }

    /** 纯 JVM 可注入层：只摘挡板读数（检查决策挡板没生效时调，不让旧说明残留到新版本）。 */
    internal fun clearRejectedBundleFallback(sp: SharedPreferences) {
        sp.edit().remove(KEY_UPDATE_REJECTED_FALLBACK).apply()
    }

    /** 纯 JVM 可注入层：账与挡板读数一起摘（应用成功 / 换版本暂存成功的清账口）。 */
    internal fun clearRejectedBundleState(sp: SharedPreferences) {
        sp.edit()
            .remove(KEY_UPDATE_REJECTED_BUNDLE)
            .remove(KEY_UPDATE_REJECTED_FALLBACK)
            .apply()
    }

    /** 取 SharedPreferences。优先 `applicationContext`（短命 Context 不会把整份 XML 绑到 Activity 上），
     * mock Context（测试）没有 applicationContext 时退回传入的 Context。
     *
     * ## UI 线程磁盘 IO 的裁定（obs-uiio 车道）：**甲**（「首选项首读落盘」）
     *
     * 判据与删表条件写在 `StrictModeGuard.uiDiskIoExemptions` 的那一条里，摘要：
     * - 全进程**只有第一次**取实例真的命中磁盘（读那份 XML）；之后 Android 缓存实例，
     *   每一个 getter 都只是查内存表 ⇒ 豁免窗口只开在这一发上，别处一次都不开；
     * - 首读又必须在**第一帧之前**给出结果：字号档位（[fontColumns]）是 `CanvasConsoleView`
     *   的构造期参数，`MainActivity.onCreate` 当场就要值 ⇒ 「挪到后台线程」在这里没有可挪的时机；
     * - 「Application 里 preload 后删表」的原案经 fix-uiowiring 车道判为**删不掉**（理由与
     *   TODO(verify) 都在表的那一条里）；一次性耗时读数就在下面那行日志里，读盘过 2 s
     *   （[StrictModeGuard.SLOW_UI_DISK_IO_MS] 那把尺子）豁免窗口自己还会再留一行带标签的慢样本。
     *
     * 线程：首读可能由任意线程先发起（设置页 / 更新通道 / 壳层都会走到）。主线程闸收在
     * [EratwStrictMode.allowUiDiskIo] **本体**（fix-uiowiring 车道：非主线程调用 = 不开窗口、
     * 走本体那条日志口径）⇒ 这里无条件调用即可；下面的 `onMain` 只服务于那一行读数文案，
     * 不再决定开不开窗口（StrictMode 的线程策略按线程装，非主线程上本来就没有侦测位）。
     */
    /**
     * 恢复默认时**不清**的运行态键：这些装的不是玩家设置，而是更新通道的状态与设备身份。
     * 整表 clear 会把灰度分桶重抽、镜像健康清零、失败下载的重试凭证抹掉
     * （「重试失败下载」入口会因此消失），所以先读出、clear 之后原样回填。
     *
     * 匿名编号同理：清掉会换一枚新 id，服务端按 id 去重就会把同一个玩家数成两个人，
     * 徽章凭空虚增；上次心跳日也留着，免得恢复默认后同一天又补发一拍。
     * 「是否参与统计」那个开关**不在此列**，恢复默认就该回到出厂缺省。
     * 首启教程的「已读」标记也留：它是引导完成态，清掉等于让老玩家每次重置再读一遍教程。
     */
    private val KEEP_ON_RESET = setOf(
        KEY_ROLLOUT_BUCKET,
        KEY_MIRROR_HEALTH,
        KEY_UPDATE_FAILED_FILE_NAME,
        KEY_UPDATE_FAILED_SIZE_BYTES,
        KEY_UPDATE_FAILED_SHA256,
        KEY_UPDATE_FAILED_KIND,
        KEY_UPDATE_FAILED_ASSETS_VERSION,
        KEY_PLAYER_STATS_ID,
        KEY_PLAYER_STATS_LAST_DAY,
        KEY_TUTORIAL_SHOWN,
        // ModDX-4 A+：mod 变更标记同样不是设置（mod 的启停 / 导入落盘在 mods/rt2/，resetAll
        // 不碰盘面），「全部重置」抹掉它会让刚改过的 mod 面回到「静悄悄不生效」——正是本波
        // 要清掉的摩擦。恢复默认也不改 mod 盘面，标记照旧有提示义务。
        KEY_MOD_RESTART_PENDING,
    )

    /** 「全部重置」后「返回游戏时问一句」的一次性标记（由 MainActivity 消费）。 */
    private const val KEY_RESTORE_RESTART_PENDING = "restore_restart_pending"

    /**
     * 设置页改过 mod 面（per-mod 启停 / 导入成功）之后的一次性「重开生效」标记（ModDX-4 A+）。
     *
     * ## 存储形态裁定（判据 1）：UiPrefs 新布尔键，不走 EXTRA_RESTART_GAME 通道
     *
     * 既有「保存并重启」走的是 intent extra（SettingsActivity 的 `EXTRA_RESTART_GAME`，
     * 随 setResult 当场带回）；mod 生命周期动作是**异步**的（启停随时点、导入在后台线程
     * 跑完才落盘），没有「当场带回」的那一拍，所以走与 [setRestoreRestartPending] 同形的
     * 落盘一次性标记（SettingsActivity「全部重置」置位 [setRestoreRestartPending] 的先例）：
     * 置位方只管置，消费方（MainActivity `onActivityResult`）读一次即清。
     * 生效口径 = ModEnablement.kt:13-14「装配在会话构造期一次性完成，改完要重开一局」。
     */
    private const val KEY_MOD_RESTART_PENDING = "mod_restart_pending"

    /**
     * 顶栏「全部重置」：清掉整份壳层偏好（[PREFS_NAME] 这一份 XML）。
     *
     * 用 clear 而不是逐键写回缺省的理由：本对象每个 getter 都是「读不到键 ⇒ 回落缺省」
     * （`getXxx(KEY, DEFAULT)` 或 `fromStored(null)`），清键与逐键写缺省**取值逐字等价**，
     * 而且将来新增偏好键时不会漏掉。只动这一份 XML：不碰 emuera.config、存档、
     * 已导入的补丁与素材包。
     *
     * 唯一例外是 [KEEP_ON_RESET] 那几个运行态键：它们不是设置，清掉会破坏更新通道，
     * 所以 clear 之后原样回填。「恢复默认」只恢复设置，不该顺手重置设备身份与下载状态。
     *
     * 写盘沿用 apply()（内存表立即生效、磁盘异步落），与其余写入同口径；调用方负责
     * 在之后重建界面，让 Compose 里 remember 住的那些偏好快照重新读一次。
     */
    internal fun resetAll(sp: SharedPreferences) {
        val kept = KEEP_ON_RESET.mapNotNull { key -> sp.all[key]?.let { key to it } }
        val editor = sp.edit().clear()
        kept.forEach { (key, value) -> putBack(editor, key, value) }
        editor.apply()
    }

    /** clear 之后把运行态键原样回填；SharedPreferences 的值域就这六种形状。 */
    private fun putBack(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, (value as Set<String>).toMutableSet())
            }
        }
    }

    /** [resetAll] 的 Context 入口：顶栏「全部重置」清偏好时调用。 */
    fun resetAll(context: Context) = resetAll(prefs(context))

    /** 「全部重置」清完偏好之后置位；返回游戏时由 MainActivity 消费一次，弹「重开生效」询问。 */
    internal fun setRestoreRestartPending(context: Context) {
        setRestoreRestartPending(prefs(context))
    }

    /** [setRestoreRestartPending] 的注入版：JVM 单测直测，不依赖 Android Context。 */
    internal fun setRestoreRestartPending(sp: SharedPreferences) {
        sp.edit().putBoolean(KEY_RESTORE_RESTART_PENDING, true).apply()
    }

    /** 读并清一次性标记；没置位返回 false。 */
    internal fun consumeRestoreRestartPending(context: Context): Boolean =
        consumeRestoreRestartPending(prefs(context))

    /** [consumeRestoreRestartPending] 的注入版：读一次即清，防止重复弹询问。 */
    internal fun consumeRestoreRestartPending(sp: SharedPreferences): Boolean {
        if (!sp.getBoolean(KEY_RESTORE_RESTART_PENDING, false)) return false
        sp.edit().remove(KEY_RESTORE_RESTART_PENDING).apply()
        return true
    }

    /**
     * mod 面变更（启停成功 / 导入成功）后置位；返回游戏时由 MainActivity 消费一次，
     * 弹「mod 已变更，重开生效」询问（ModDX-4 A+）。置位即幂等：同一轮设置页里连续
     * 启停多个 mod / 导入多个包都只置同一个布尔，返回时合并成一句提示。
     */
    internal fun setModRestartPending(context: Context) {
        setModRestartPending(prefs(context))
    }

    /** [setModRestartPending] 的注入版：JVM 单测直测，不依赖 Android Context。 */
    internal fun setModRestartPending(sp: SharedPreferences) {
        sp.edit().putBoolean(KEY_MOD_RESTART_PENDING, true).apply()
    }

    /**
     * 读并清一次性标记；没置位返回 false。
     *
     * **取消路径裁定（判据 2，二选一）**：消费发生在读这一拍（提示弹出与否不再回头改标记），
     * 玩家点「下次启动生效」拒绝重开 = 标记已清、不再追问 —— 与 [consumeRestoreRestartPending]
     * 同一口径。理由：变更已在盘上（`.off` / `.erbm`），下次冷启必生效；「保留至生效」要
     * 跟踪「标记 ↔ 会话代际」的对应关系，是一台小状态机，本波不做。不追问的代价是玩家
     * 拒绝后看不到第二次提醒，换来的是不骚扰（按钮文案「下次启动生效」本身已说明后果）。
     */
    internal fun consumeModRestartPending(context: Context): Boolean =
        consumeModRestartPending(prefs(context))

    /** [consumeModRestartPending] 的注入版：读一次即清，防止重复弹询问。 */
    internal fun consumeModRestartPending(sp: SharedPreferences): Boolean {
        if (!sp.getBoolean(KEY_MOD_RESTART_PENDING, false)) return false
        sp.edit().remove(KEY_MOD_RESTART_PENDING).apply()
        return true
    }

    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext ?: context
        if (coldReadDone) return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 先置「已读」再起这一发：并发首读最坏是多算一次耗时读数，绝不会出现第二条线程也来开窗口
        //（豁免窗口的重入位只在主线程被踩到，见 EratwStrictMode 的 allowUiDiskIo 走向 3）。
        coldReadDone = true
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val startedAt = SystemClock.uptimeMillis()
        val prefs = EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_PREFS_COLD_READ) {
            app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
        val tookMs = SystemClock.uptimeMillis() - startedAt
        runCatching {
            Log.i(
                LOG_TAG,
                "首选项首读（每进程只此一次）：${tookMs}ms ｜主线程=$onMain ｜" +
                    "豁免窗口=${if (onMain) "已开（表内）" else "未开（非主线程，哨本来不管）"}",
            )
        }
        return prefs
    }

    /** 首读是否已经发生（只服务于上面那一次性的窗口与读数；主线程/后台线程都可能翻它）。 */
    @Volatile
    private var coldReadDone = false

    /** 首读读数的 logcat tag（与 [StrictModeGuard.LOG_TAG] 分开：两条路各自可 grep）。 */
    private const val LOG_TAG = "EratwUiPrefs"
}

/**
 * [UiPrefs.fontColumns] 的顶层同名入口 —— 装配层的调用口径就是这四个函数名。
 *
 * 实现只有一份，在 [UiPrefs] 里；这里保留顶层形态是为了让接线侧不必记「对象前缀」。
 */
fun fontColumns(context: Context): Int = UiPrefs.fontColumns(context)

/** [UiPrefs.setFontColumns] 的顶层同名入口。 */
fun setFontColumns(context: Context, columns: Int) = UiPrefs.setFontColumns(context, columns)

/**
 * 「最近一次**没下成**的下载单」的五元组落盘形状（U2 病②）。
 *
 * 为什么只存这五个：重放一张单子需要的全部信息就是产物名/字节数/摘要/通道/数据版本；
 * 玩家面标签在还原期按当前语言现推（与通知标签同口径，成单那一刻的语言不落盘），
 * 写坏的语言档不影响重放。**缺一视为无效**：五个键任何一个缺席或坏值，读侧整单作废。
 */
data class FailedDownloadRecord(
    val fileName: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val kind: UpdateDownloadKind,
    val assetsVersion: Long,
)

/**
 * 壳层语言设置 → core 文案语言档的**单路映射**（AGENTS 铁律 4：core 不读 Locale、不持全局语言）。
 *
 * 三语一一对应：ZH→[LangVariant.ZHS]、EN→[LangVariant.EN]、JA→[LangVariant.JA]。
 * 旧接线把 EN 折叠成 ZHS（`if (lang == JA) JA else ZHS`），英文玩家看到中文报错——
 * 2026-11-07 i18n 基础设施波起 EN 档已进 [LangVariant]，所有宿主面文案取用点一律走本函数。
 */
fun langVariantOf(lang: SettingsLanguage): LangVariant = when (lang) {
    SettingsLanguage.ZH -> LangVariant.ZHS
    SettingsLanguage.EN -> LangVariant.EN
    SettingsLanguage.JA -> LangVariant.JA
}

// ---- RT2 玩家排序配置（L2）：壳层配置来源实现（UiPrefs 只留薄委托）--------------------
// 核心层 LinkOrder(preferredPackageIds) 已贯通 ModuleLinkOrder.plan → ModuleLinker.link
// → ModChainAssembler.assemble → ModChainAssembly.assemble，缺的只是「配置来源」。
// 这里只补壳层的持久化与归一：一行核心排序语义都不改（语义权威 = LinkOrder KDoc 与
// CONTRACT-RT2-M2-3.md §3.1）。偏好只在 UI 线程读（读点由 UiPrefs 的公开函数收口）。
private object ModPackageOrderStore {

    /**
     * 逗号分隔的 packageId 列表原始串（沿用 UiPrefs 的 KEY_* 命名范式）。
     * 空串 = 无显式排序意图，得 LinkOrder() 缺省（packageId 规范序），与未接配置前逐位一致。
     * 属玩家设置：走 UiPrefs.resetAll 的 clear() 一并清掉。
     */
    const val KEY_MOD_PACKAGE_ORDER = "mod_package_order"

    /**
     * 逗号分隔串 → 归一化 packageId 列表：去重、去空、保序、首尾空白剔除，
     * 段内仍含空白的整段丢弃（packageId 是连续非空白 token）。
     *
     * 纯函数：不摸 UiPrefs.prefs、不看线程，装配期与纯 JVM 单测都可直接调。
     * 空串 ⇒ 空表 ⇒ LinkOrder() 缺省行为（packageId 规范序）。
     *
     * 去重是**硬要求**：核心 ModuleLinkOrder.plan 的 preferredRanks 对配置内重复 ID
     * 整组抛 LinkOrderException（不静默去重），玩家输入里的重复必须由本函数挡在装配之前。
     * 这是「就绪模块优先级」的比较键，不是对完整排列做最小调整 —— UI 文案点明此语义。
     */
    fun parse(raw: String): List<String> =
        raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.all { c -> !c.isWhitespace() } }
            .distinct()

    /** 纯 JVM 可注入层：读原始串（缺键回落空串 = 缺省）。 */
    fun read(sp: SharedPreferences): String =
        sp.getString(KEY_MOD_PACKAGE_ORDER, "").orEmpty()

    /** 纯 JVM 可注入层：写原始串（随改随存，apply）。 */
    fun write(sp: SharedPreferences, raw: String) {
        sp.edit().putString(KEY_MOD_PACKAGE_ORDER, raw).apply()
    }

    /** 构造期用：读原始串 → 归一列表（prefs 由 UiPrefs 在 UI 线程取好传入）。 */
    fun list(sp: SharedPreferences): List<String> = parse(read(sp))
}


