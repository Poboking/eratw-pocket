package com.eratw.port.android

import android.content.Context
import android.content.SharedPreferences

/**
 * 一个字号档位：把「每行多少列」映射到玩家看得懂的中文档名。
 *
 * 列数与字号是**反比**：列数越少，每个半角格越宽、字就越大。
 *
 * @property columns 目标列数（存进 SharedPreferences 的就是它）
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
 * 这两项是**移植壳**自己的显示偏好：
 *   * 字号档位 —— 原版没有「按屏宽反推列数」这回事（PC 基准固定 `FontSize=18` + 1800px 宽），
 *     手机屏宽差一个数量级，列数必须可调；
 *   * 一屏一帧 —— 原版是 WinForms 桌面窗口，没有手机上的刷新抖动问题。
 *
 * 它们没有对应的原版配置条目；而 `emuera.config` 的写回只遍历 `configArray`
 * （spec: `ConfigData.cs:430`），壳层偏好塞进去会写脏随包那份配置，所以单独一条 XML。
 *
 * ## 读取方（由装配层接线）
 *   * [fontColumns] → `CanvasConsoleView(fontColumnsOverride = …)` 的目标列数；
 *   * [frameGate] → `GameHost(frameGateEnabled = …)` 的一屏一帧开关
 *     （true = 每屏只刷一帧、减少刷新抖动；false = 更实时）。
 *
 * ## 口径
 *   * 不缓存：每次现读 SharedPreferences（一次进程内 XML 快照，微秒量级），
 *     于是设置页改完立刻被下一次装配读到；
 *   * 写入一律 `apply()`（异步落盘，不阻塞 UI 线程）；
 *   * 读到不认识的值（老版本写坏、手工改 XML）一律回落缺省：字号 110 列、一屏一帧开。
 */
object UiPrefs {

    /** SharedPreferences 文件名。与 `emuera.config` 是两套东西，互不覆盖。 */
    const val PREFS_NAME = "eratw_ui"

    /** 字号档位（列数）。顺序即设置页上的展示顺序：标准 / 大 / 特大 / 小。 */
    val FONT_COLUMN_OPTIONS: List<FontColumnsOption> = listOf(
        FontColumnsOption(110, "标准", "每行 110 列（缺省）"),
        FontColumnsOption(90, "大", "每行 90 列"),
        FontColumnsOption(75, "特大", "每行 75 列"),
        FontColumnsOption(150, "小", "每行 150 列"),
    )

    /** 字号缺省列数（标准档）。 */
    const val DEFAULT_FONT_COLUMNS: Int = 110

    /** 一屏一帧缺省值（开）。 */

    /** 右侧选项栏缺省可见（用户可随时用螺旋图标收起，状态记忆）。 */
    const val DEFAULT_OPTION_RAIL: Boolean = true

    /** 「更精简的游戏选项」缺省**关**：关 = 右侧栏照原样显示全部识别结果。 */
    const val DEFAULT_LEAN_OPTIONS: Boolean = false

    /**
     * 标题彩蛋（`@BADAPPLE`）屏蔽开关：缺省**开**（= 屏蔽）。
     *
     * 为什么缺省开：那是 6573 帧 `AWAIT 16`（≈ 105 秒）+ 一个 MP3 的纯装饰动画，
     * 对「能不能玩」零贡献。语料里那一行 `CALL BADAPPLE` 在 4.980 版已被语料作者注释掉，
     * 但语料不进仓、别人的 clone 用的是上游那份（可能是活的），所以屏蔽落在**引擎**
     * （`EngineFunctionPolicy`），这里的开关只是让想看的玩家能把它放回来。
     */
    const val DEFAULT_DISABLE_BAD_APPLE: Boolean = true

    private const val KEY_FONT_COLUMNS = "font_columns"
    private const val KEY_OPTION_RAIL = "option_rail"
    private const val KEY_DISABLE_BAD_APPLE = "disable_bad_apple"
    private const val KEY_LEAN_OPTIONS = "lean_options"

    /** 「适应屏幕」：开 = 按屏幕自动缩放让一页铺满；关 = 只按屏宽定字号。 */
    private const val KEY_SCREEN_FIT = "screen_fit"
    private const val KEY_LOADING_STYLE = "loading_style"
    private const val KEY_INPUT_KNOB = "input_knob"

    /** 右侧选项栏按钮大小档（小/标准/大/特大）。 */
    private const val KEY_RAIL_BUTTON_SIZE = "rail_button_size"

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
     * 字号档位（列数）：缺省 [DEFAULT_FONT_COLUMNS]。
     *
     * @return 一定是 [FONT_COLUMN_OPTIONS] 里的某个 `columns`；存放值不认识时回落缺省
     */
    fun fontColumns(context: Context): Int {
        val stored = prefs(context).getInt(KEY_FONT_COLUMNS, DEFAULT_FONT_COLUMNS)
        return if (FONT_COLUMN_OPTIONS.any { it.columns == stored }) stored else DEFAULT_FONT_COLUMNS
    }

    /**
     * 写字号档位。不认识的列数按缺省 110 落盘（不抛异常：设置页只可能传枚举内的值，
     * 兜底只为防将来调用方传错）。
     */
    fun setFontColumns(context: Context, columns: Int) {
        val normalized = if (FONT_COLUMN_OPTIONS.any { it.columns == columns }) columns else DEFAULT_FONT_COLUMNS
        prefs(context).edit().putInt(KEY_FONT_COLUMNS, normalized).apply()
    }




    /**
     * 「适应屏幕」开关：缺省**开**（主人 2026-10-24 要求在设置页暴露）。
     *
     * 开：`PageFitter` 按**当前可见区**反推字号/行高/列数 —— 一页内容正好铺满一屏，
     *     长页面（存档页 49 行）也不用上滑，代价是字更小。
     * 关：只按**屏宽**反推字号（旧口径），字更大，但长页面会超出屏幕需要上滑。
     */
    fun screenFit(context: Context): Boolean = prefs(context).getBoolean(KEY_SCREEN_FIT, true)

    /** 写「适应屏幕」开关（下一次装配生效，与字号档位同口径）。 */
    fun setScreenFit(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SCREEN_FIT, enabled).apply()
    }

    /**
     * 右侧选项栏是否展开（螺旋图标控制）；缺省 [DEFAULT_OPTION_RAIL]（开）。
     *
     * 存的是「上次退出时的可见状态」：真机上一旦关掉，下次进游戏不会被自动弹回。
     */
    fun optionRailVisible(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPTION_RAIL, DEFAULT_OPTION_RAIL)

    /** 写右侧选项栏可见状态。 */
    fun setOptionRailVisible(context: Context, visible: Boolean) {
        prefs(context).edit().putBoolean(KEY_OPTION_RAIL, visible).apply()
    }

    /**
     * 「更精简的游戏选项」：缺省 [DEFAULT_LEAN_OPTIONS]（关）。
     *
     * 开：右侧栏只显示标签末尾带数字标签的常用指令（如「会话[31]」「移动[400]」）；
     * 关：显示 [com.eratw.port.core.host.ConsoleOption] 的全部识别结果（原行为）。
     *
     * 过滤只发生在壳层显示端（[com.eratw.port.render.view.LeanOptionFilter]），
     * 不参与引擎的可点性判定，也不影响正文里的按钮命中。
     */
    fun leanOptions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LEAN_OPTIONS, DEFAULT_LEAN_OPTIONS)

    /** 写「更精简的游戏选项」开关（随改随存，下一次右侧栏刷新即生效）。 */
    fun setLeanOptions(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_LEAN_OPTIONS, enabled).apply()
    }

    /**
     * 标题彩蛋（`@BADAPPLE`）是否**屏蔽**：缺省 [DEFAULT_DISABLE_BAD_APPLE]（开 = 屏蔽）。
     *
     * 读取方是装配层（`GameHost` → `GameSession(disabledFunctions = …)`），
     * 下一次进游戏生效（会话构造期定死，与字号档位同口径）。
     */
    fun disableBadApple(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DISABLE_BAD_APPLE, DEFAULT_DISABLE_BAD_APPLE)

    /** 写标题彩蛋屏蔽开关（下一次装配生效）。 */
    fun setDisableBadApple(context: Context, disabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DISABLE_BAD_APPLE, disabled).apply()
    }

    /** 列数 → 档位名；不认识的值按缺省档显示（与 [fontColumns] 的回落一致）。 */
    fun fontColumnsLabel(columns: Int): String =
        FONT_COLUMN_OPTIONS.firstOrNull { it.columns == columns }?.label
            ?: FONT_COLUMN_OPTIONS.first { it.columns == DEFAULT_FONT_COLUMNS }.label

    /**
     * 取 SharedPreferences。优先 `applicationContext`（短命 Context 不会把整份 XML 绑到 Activity 上），
     * mock Context（测试）没有 applicationContext 时退回传入的 Context。
     */
    private fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext ?: context
        return app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/**
 * [UiPrefs.fontColumns] 的顶层同名入口 —— 装配层的调用口径就是这四个函数名。
 *
 * 实现只有一份，在 [UiPrefs] 里；这里保留顶层形态是为了让接线侧不必记「对象前缀」。
 */
fun fontColumns(context: Context): Int = UiPrefs.fontColumns(context)

/** [UiPrefs.setFontColumns] 的顶层同名入口。 */
fun setFontColumns(context: Context, columns: Int) = UiPrefs.setFontColumns(context, columns)


