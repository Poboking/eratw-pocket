package com.eratw.port.render.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Looper
import android.util.AttributeSet
import android.util.LongSparseArray
import android.view.MotionEvent
import android.view.View
import com.eratw.port.render.GlyphKey
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.adapter.DivChildDraw
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.DivOverlayRow
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.ButtonLayer
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.font.BundledFontFamilies
import com.eratw.port.render.font.BundledFontFamily
import com.eratw.port.render.font.GlyphFallback
import com.eratw.port.render.font.ResolvedFontFamilyCache
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.AnimeRepaintPlan
import com.eratw.port.render.sprite.planAnimeRepaint
import com.eratw.port.core.host.CbgLayerSnapshot
import com.eratw.port.core.host.FontDisplayWidth
import com.eratw.port.render.view.CbgDrawOrder
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenLayout
import com.eratw.port.render.view.BackgroundBakeGuard
import com.eratw.port.render.view.GraphicGeometry
import com.eratw.port.render.view.GraphicTapAnchor
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.hitRowForCell
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import com.eratw.port.render.view.ImageLayerGeometry
import com.eratw.port.render.view.OptionButtonScanner
import com.eratw.port.render.view.RowRasterCache
import com.eratw.port.render.view.RunChrome
import com.eratw.port.render.view.RowLayout
import com.eratw.port.render.view.ScreenSurfacePlanner
import com.eratw.port.render.glyph.FontWidthRuler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 一次点击（按下-抬起）的完整结果 —— [CanvasConsoleView.tap] 的返回类型。
 *
 * 两半的原版顺序是**先取命中、后回贴底**（`MainWindow.cs:799-805`）：`flag = Value != Maximum`
 * （`:799`）与 `selectedString = console.SelectedString`（`:800`）都在未贴底时那两行
 * `Value = Maximum` / `RefreshStrings`（`:801-805`）**之前**算好，所以命中判据用的是贴底前
 * 的视图，而「这一击是否刚把视口弹回底部」这件事在命中分支之外单独成立。
 *
 * 拆成两个字段是因为调用方（`MainActivity.handleConsoleTap`）要复刻 `:806` 那个块的
 * `if (!flag)`：贴底发生时**这一击只回贴底、不再提交**，无论有没有命中。
 *
 * @property hit 贴底前视图上的按钮提交文本（[CanvasConsoleView.tap] 的命中半段）；
 *   未命中（空白 / 余量 / 窗口外）为 null
 * @property snappedToBottom 本击之前视口**未贴底**、且已被弹回底部（`MainWindow.cs:799` 的
 *   `flag` 为真 ⇒ `:803` 的 `Value = Maximum` 已执行）；无屏可动时恒 false
 */
data class TapOutcome(val hit: String?, val snappedToBottom: Boolean)

/**
 * `ConsoleScreen` 的 Android 自绘 View（M4-SKEL）：只消费 [ConsoleScreen.list] 的
 * [DrawCmd]，逐字形铺到等宽半角格上，不重算任何语义（行号、折行、对齐、命中都在 :render）。
 *
 * ## 坐标口径
 *
 * - 半角格宽 = [CellMetrics.cellPixelWidth]（`字号 / 2 * 1.04`，与 `StringMeasure.cs:37` 的
 *   `fontDisplaySize` 同式）；行高 = [CellMetrics.lineHeight]。
 * - [DrawCmd.row] 是**绘制口径**的保留列表下标；窗口装满时列表比可见行多
 *   `ConsoleScreen.EXTRA_ABOVE_ROWS` 行。纵向落位唯一来源是 [RowLayout] 的行顶表
 *   （[rowLayout] 交给它的窗口行数 = `ConsoleScreen.visibleRows().size`，**零命令的空行照样占一个行高**）：
 *   整块内容**贴住内容区下沿**（最后一行恰好落在 contentBottom 上），多出来的那一排屏外
 *   溢出行落在表顶、由 [drawScreen] 的 clipRect 裁掉。出处是原版从底往上画的绘制循环：
 *   最后一行顶边 = `MainPicBox.Height - Config.LineHeight`（spec: `EmueraConsole.cs:1805`），
 *   首行再按「底部行号 - 顶行号」回推（`:1812` 的 `num -= (num2 - num3) * LineHeight`），
 *   之后循环逐行下移（`:1852-1856`，与该行有没有内容无关）。早先本类从 contentTop 自顶向下画，视口高不是行高
 *   整数倍时最后一行被裁掉一截（真机标题页实测），改成底对齐后最后一行永远完整。
 * - 绘制、命中与取色**共用同一张行顶表**：[drawScreen] / [cellAt]（`tap` / `setPointer` /
 *   `tooltipAt`）与 [mappingColor] 都查 [rowLayout]，保证「画在哪 = 点哪里 = 取哪里的色」。
 *   等距形态的原点 `contentOriginY` 已随 W6 取色刀摘除（纯图行被压缩时取色与绘制分叉
 *   一整排，BOARD GFX-14；横向同理共用 [MappingColorGeometry.graphicOriginLeftPx]，
 *   取色不再与绘制差一个 shift，见 [shapeShiftPx] 的 KDoc）。
 * - 字形前进量取 [fontRuler]（随包 sidecar 的真实 advance；等宽族吸附到半角 1/全角 2、
 *   增补平面与缺表回落仍是「每码元 2 格」的静态网格口径），与 [DrawCmd.colEnd] 的算法
 *   （[ConsoleScreen.fontWidth] 同一把尺）同源；不读 `Paint` 的实际前进量来排版，
 *   只把单字横向缩放到该前进量内 —— `SETFONT` 换字形即换尺（2026-11 AA 画/老虎机修复）。
 *
 * ## 字体与列数（2026-11-03 单源重构）
 *
 * 字号/行距是**输入**：来自 `emuera.config` 的 `FONT SIZE` / `LINE HEIGHT`，由装配层在
 * 会话开始前经 [applySourceFontMetrics] 灌入，换算与夹取**只**发生在 [ScreenLayout.resolve]。
 * 列数与行数是**输出**：`columns = 可绘制宽 / 半角格宽`、`rows = 可见高 / 行高`，
 * 页面内容/图片展平结果**不参与** —— 旧实现按「当前页行数」反推字号，与页行数互为函数，
 * 形成自激环（翻页、输入栏显隐、地图重印都会跳字号），是「修一处坏两处」的根因。
 *
 * 唯一宽度约束是构造参数 `minColumnsOverride`（列数守护下限，缺省 136）：屏宽铺不下
 * 该列数时压小字号，守住 128 格地图行（`ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:10`）。
 * 这不是「目标列数」—— 屏宽足够时字号优先，列数可以多于此值。构造参数 `fontScale`
 * 是壳层「显示大小」倍率（1 = 跟随 `FONT SIZE`），把玩家选的大/小档乘进基准字号后
 * 再走同一条守护，**不引入第二条推导**。
 *
 * 随包字体走 [BundledFontFamilies] 的**多族**注册表（GFX-12 换字体波）：默认族
 * `Sarasa Mono SC`（原版黑体的合法替身，思源黑体同源、严格 1:2），另备霞鹜文楷等宽、
 * Maple Mono CN、Saitamaar、美咲ゴシック、Fusion/Ark Pixel 12px 与 Unifont 16px
 * （兜底族），`SETFONT "Saitamaar"` 仍落字符画族。字体文件由 Gradle 从 `:render` 的
 * resources 同步进 APK assets（不复制进 git）；某族缺包/坏包时该族退 [Typeface.MONOSPACE]，
 * **任何情况下不抛**（同 `FontFactory.cs:52-54` 的 try/catch → null）。
 * 逐字形缺字按 [effectiveFamily] 的随包链换族（API 23+ 用 `Paint.hasGlyph` 判），
 * 不再依赖系统字体。选族口径与判据见 [BundledFontFamilies] 的 KDoc。
 *
 * 排障读数：[fontPixelSize]（生效字号像素）、[columns]（列数守护下限）、
 * [consoleColumns]（当前实际列数）；配置原始值见 `debugMetrics()` 的 `configFont/configLineH`。
 *
 * ## 两种喂法
 *
 * - 生产路径 [attachFeed]：`ConsoleFeed` 把核心层 sink 的快照桥进一个外部 `ConsoleScreen`，
 *   [onDraw] 先 `feed.pump()`（revision 未变时零开销）再画 `feed.screen.list`；
 * - 演示路径 [submitSnapshot]：本 View 按自己的尺寸自建 `ConsoleScreen` 并接收整屏快照。
 * 未挂 feed 时 [onDraw] 的行为与 M4-SKEL 逐项相同。
 *
 * ## 行内按钮的按下态
 *
 * [setPointer] 把触摸按下/抬起换算成**命中口径**后交给 `ButtonLayer.setPointer` —— 原版同构，
 * 焦点/按下只是绘制标记（spec: `EmueraConsole.cs:830-838` 的 `ButtonIsSelected` /
 * `ButtonIsPointing` 只做引用比较）。触摸事件的观测点在 [dispatchTouchEvent]：装配层的
 * `setOnTouchListener` 只把手势喂给 `TouchGestureController`（点/滑分界、双击窗口与邻域），
 * 按下态没有第二处写点，见该方法的 KDoc。
 * [drawScreen] 每帧只查一次按下目标（绘制循环里零分配），[drawCmd] 对覆盖到的
 * `RunKind.BUTTON` 段先铺半透明高亮底色、并按原版焦点态把字色换成
 * [DrawCmd.focusFore]（spec: `ConsoleStyledString.cs:105-111` 与 `:147-150` 的
 * `ButtonColor` 分支）。命中几何一个字都不动：点击回环仍走 [tap] → `ButtonLayer.hit`。
 *
 * 本类不处理触摸滚动 / 按钮命中 / 输入法，分别归 M5-TOUCH 与 M5-CONFIG；
 * [dispatchTouchEvent] 只观测按下/抬起做绘制标记（照原样返回 `super` 的结果，不消费事件），
 * 不产任何输入。
 */
class CanvasConsoleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    /**
     * 列数守护（原「适应屏幕」开关）：true = 屏宽铺不下 [columns] 列时压小字号保列数；
     * false = 字号优先，列数随字号走（可能折行，见 [ScreenLayout.resolve] 第 3 步）。
     */
    private val columnGuardEnabled: Boolean = true,

    /**
     * 列数守护**下限**（半角格）：null = 缺省 [ScreenLayout.DEFAULT_COLUMNS] = 136。
     *
     * 只在 [columnGuardEnabled] = true 时生效。注意语义已从「目标列数」改为「下限」：
     * 屏宽足够时字号（`emuera.config` 的 `FONT SIZE`）优先，只有会跌破本值才压小字号 ——
     * 这样字号设置是真设置，同时守住 128 格地图行不被折成「主行 + 尾巴行」。
     */
    minColumnsOverride: Int? = null,

    /**
     * 壳层「显示大小」倍率（1 = 跟随 `emuera.config` 的 `FONT SIZE`）。
     *
     * 与 [minColumnsOverride] 配对：档位越「大」倍率越高、守护下限列数越低，
     * 于是大字号档会真的变大（必要时让宽地图折行），而不是变成无效档。
     * 非正 / 非有限值按 1 处理（非法偏好不许把字号算成 0）。
     */
    private val fontScale: Float = 1f,

    /**
     * LIVE-REFLOW 开关：false（缺省）= 现有「会话期宽度冻结、只改行数」路径逐字不变；
     * true = attached 后窗口宽度变化时回调 [onViewportChange]，由宿主先重排会话宽度，
     * 再调 [reflowAttachedFeed] 换新 [ConsoleScreen]。
     */
    private val liveReflow: Boolean = false,
) : View(context, attrs, defStyleAttr) {

    /**
     * 列数守护下限（半角格）：构造参数覆盖，缺省 [ScreenLayout.DEFAULT_COLUMNS] = 136。
     *
     * 为什么不照 PC 基准的 199：199 列反推出来的是「一行铺满」的字号，
     * 2400px 长边上只有约 23px ≈ 7.7dp，屏上看不清。136 列约 34px ≈ 11dp，
     * 是正常手机阅读尺寸，且放得下语料最宽的 128 格地图行（理由见
     * [ScreenLayout.DEFAULT_COLUMNS]）。
     * 装配层可用本属性 / [fontPixelSize] / [consoleColumns] 三个读数排障。
     */
    val columns: Int = (minColumnsOverride ?: ScreenLayout.DEFAULT_COLUMNS).coerceAtLeast(1)

    /**
     * 字号/行距的**输入**：emuera.config 的生效值（脚本逻辑像素，缺省 18/19）。
     *
     * 旧实现把字号当输出（按当前页行数反推），而页行数又由字号+折行+图片展平决定，
     * 两个方程互为输入 ⇒ 修一处坏两处。重构后字号是输入、行数是输出，
     * 推导唯一收口在 [ScreenLayout.resolve]；页面内容只决定滚动位置。
     */
    private var sourceFontPixelSize: Int = CellMetrics.DEFAULT_FONT_PIXEL_SIZE

    /** 见 [sourceFontPixelSize]；缺省 19/18（`ConfigData.cs:66-67`）。 */
    private var sourceLineHeight: Int = CellMetrics.DEFAULT_LINE_HEIGHT

    /**
     * 脚本 px 的窗口宽（`Config.WindowX`，原版主窗口客户区宽，spec: `MainWindow.cs:687-688`）。
     *
     * 0/负数 = **未接线** ⇒ 满宽，行为与本次改动前逐像素相同。由 [applySourceWindowMetrics]
     * 在会话构造前灌入，经 [resolveFor] 按 [CellMetrics.scriptPixels] 折算成设备 px。
     */
    private var sourceWindowWidthScriptPx: Int = 0

    /**
     * 脚本 px 的窗口高（`Config.WindowY`，原版客户区高；客户区高 = `WindowY - LineHeight`，
     * spec: `MainWindow.cs:726`）。0/负数 = 未接线 ⇒ 整高（纵向观感不变）。
     */
    private var sourceWindowHeightScriptPx: Int = 0

    /** 最近一次 [resolveFor] 的客户区宽（设备 px，[ScreenLayout.layoutWidthPx] 的存储）。 */
    private var resolvedLayoutWidthPx: Int = 0

    /** 最近一次 [resolveFor] 的水平居中偏移（设备 px，[ScreenLayout.contentInsetLeftPx] 的存储）。 */
    private var resolvedContentInsetLeftPx: Int = 0

    /** 最近一次解算的客户区高（设备 px；未接线时不用它，[clientHeightPx] 回落到物理内容高）。 */
    private var resolvedClientHeightPx: Int = 0

    /**
     * 把 emuera.config 的生效字号/行高灌进 View，并按**当前可见区**重解一次版面。
     *
     * 必须在会话构造前调用：装配层会用 `view.consoleScreen().clientWidth` 作为会话
     * `consoleWidth`，也会用 `view.cellMetrics()` 构造 [ConsoleFeed]；若字号/列数在会话
     * 构造后才变，控制台折行宽与展平器 metrics 会和 View 错位。运行期改配置走「下次会话」。
     */
    fun applySourceFontMetrics(fontSizePx: Int, lineHeightPx: Int) {
        val font = fontSizePx.coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE)
        val line = lineHeightPx.coerceAtLeast(font)
        if (font == sourceFontPixelSize && line == sourceLineHeight) return
        sourceFontPixelSize = font
        sourceLineHeight = line
        if (width > 0 && height > 0) {
            rebuildScreen(width, height)
        } else {
            applyMetrics(resolveFor(0, 0).metrics)
        }
    }

    /**
     * 把 `Config.WindowX` / `Config.WindowY`（脚本逻辑 px）灌进 View，并按**当前可见区**重解版面。
     *
     * 时序与会话装配：必须在 `GameSession` 构造前调用 —— 装配层用 [layoutWidthPx] /
     * [clientWidthPx] 定会话宽度、用 [windowWidthScriptPx] 播种 `WindowX`；晚于会话构造会让
     * 核心折行宽与屏上列数分成两把尺（R5.2）。运行期改值只在下一次会话生效（`WindowX/WindowY`
     * 已移出热更键表，理由见 `core/config/SettingsModel.kt` 的 `HOT_KEYS` KDoc）。
     *
     * 原版语义：客户区宽 = `Config.WindowX`、客户区高 = `WindowY - LineHeight`
     * （spec: `MainWindow.cs:687-688` / `:726`）；宽窄于物理内容区时在屏上水平居中
     * （偏移由 [ScreenLayout.contentInsetLeftPx] 出，余 1 px 归左）。
     *
     * 早退模式同 [applySourceFontMetrics]：同值直接返回；未布局（`width <= 0`）只存脚本值，
     * 等下一次 [onSizeChanged] 解算 —— 不拿 0 尺寸的版面盖掉已有几何。
     *
     * @param windowWidthScriptPx 客户区宽（脚本 px；0/负数 = 未接线 ⇒ 满宽）
     * @param windowHeightScriptPx 客户区高（脚本 px；0/负数 = 未接线 ⇒ 整高）
     */
    fun applySourceWindowMetrics(windowWidthScriptPx: Int, windowHeightScriptPx: Int) {
        if (windowWidthScriptPx == sourceWindowWidthScriptPx &&
            windowHeightScriptPx == sourceWindowHeightScriptPx
        ) {
            return
        }
        sourceWindowWidthScriptPx = windowWidthScriptPx
        sourceWindowHeightScriptPx = windowHeightScriptPx
        if (width > 0 && height > 0) {
            rebuildScreen(width, height)
        }
    }

    /**
     * 交给 core 回填用的脚本 px 版面宽（同尺逆折算：实际版面宽 ÷ 「真机字号 / 脚本字号」）。
     *
     * 消费链：`GameHost` 取 [windowWidthScriptPx] 存进 `ScriptPixelMetrics` → `GameSession`
     * → `ConfigMethods.backfillWindowWidth` 的 `WindowX` **链后回填**。折算比
     * `k = fontPixelSize / sourceFontPixelSize` 与 [CellMetrics.scriptPixels] 同一把尺；
     * 回填后核心 `drawableWidth` 折算到设备像素应与屏上可绘制宽差不到一格（R5.1）。
     *
     * 读数是**实际版面宽**的逆折算，与窗口宽是否接线无关：接线（`sourceWindowWidthScriptPx > 0`）
     * 时版面被 `min(配置宽×k, 物理宽)` 收窄，逆折算回配置窄化语义；未接线（≤ 0，生产常态）
     * 时版面是物理满宽，逆折算给出满宽脚本 px —— 无论哪种，core 的 `WindowX` 都以屏上
     * **实际**版面为准（原版 `MainWindow.cs:726` 的回填语义），而不是配置文件的期望值。
     * 0.2.43 曾在这里「未接线原样返回 0」，那会让回填层把 0 当「未接线」跳过 —— 旧盘的
     * `WINDOW WIDTH` 读数（裸缺省 760）压住真机满宽版面，`MAXWIDTH()` 掉进除零区
     * （`DEBUG.ERB` `CFLAG_DEBUG` 行582），HTML_PRINT 折行尺与屏上分叉成两把尺。
     *
     * 未布局（客户区宽尚未解算）⇒ 回落 `sourceWindowWidthScriptPx`（未接线 = 0）：不拿 0
     * 冒充版面宽 —— 回填层对 ≤ 0 一律不写，等价于「这一次没有读数」。
     */
    fun windowWidthScriptPx(): Int {
        val layoutWidth = resolvedLayoutWidthPx
        if (layoutWidth <= 0 || sourceFontPixelSize <= 0) return sourceWindowWidthScriptPx
        val ratio = metrics.fontPixelSize.toFloat() / sourceFontPixelSize
        return (layoutWidth.toFloat() / ratio).roundToInt()
    }

    /** 当前生效度量（唯一解算见 [resolveFor]）；绘制/折行/展平一律读它。 */
    private var metrics: CellMetrics = run {
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        ScreenLayout.resolve(
            widthPx = w,
            heightPx = h,
            densityDpi = dm.densityDpi,
            configFontPx = sourceFontPixelSize,
            configLineHeightPx = sourceLineHeight,
            minColumns = if (columnGuardEnabled) columns else 0,
            fontScale = fontScale,
        ).metrics
    }

    /** 生效字号（像素）。随可见区变化，绘制/折行一律读它。 */
    var fontPixelSize: Float = metrics.fontPixelSize.toFloat()
        private set

    private var halfCellPx: Float = metrics.cellPixelWidth
    private var lineHeightPx: Float = metrics.lineHeight.toFloat()
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * **字体感知排版尺**（AA 画 / 老虎机字符画错位修复）：读 Android assets 的 sidecar 表，
     * 折行 / 列区间 / 逐字形推进与它同一把尺。装配细则与退化纪律见
     * [AndroidFontWidthRuler]；`SETFONT` 的宽度语义证据见 [FontWidthRuler] 的类文档
     * （`ConsoleStyledString.cs:25,82` + `ConsoleDisplayLine.cs:66`）。
     *
     * 换字号必须经 [applyMetrics] 同批把 [metrics] 写进 [fontRuler.cellMetrics]
     * （尺按字号清缓存）——漏了这步，尺的推进量会停在旧字号上。
     */
    private val fontRuler: FontWidthRuler = AndroidFontWidthRuler.create(context, metrics)

    /**
     * [fontRuler] 的 `FontDisplayWidth` 投影，**全链共用这一只实例**：
     * 核心层折行（`GameSession` → `LineBufferConsoleSink.fontWidth`）、渲染层折行与列区间
     * （[ConsoleScreen.fontWidth]）与装配桥（`ConsoleFeed`）都吃它。
     * 稳定实例而非每次投影：`LineBufferConsoleSink` 等消费方把它当普通字段持有，
     * 实例身份不稳定会让缓存/判等语义漂移。
     */
    val fontDisplayWidth: FontDisplayWidth = fontRuler.asDisplayWidth()


    /**
     * div 子行展平器的**图元尺寸源**：现读当前挂着的 [frameSource]。
     *
     * 子行里的 `<img>` 与正文同一套几何（`HtmlRunFlatten.emitImage` 缺 width 时按基图
     * 纵横比推宽，`ConsoleImagePart.cs:66-70`），但展平器此前拿不到尺寸源
     * （`sizeSource = null`）⇒ 缺宽 img 按高见方占位（真机与 headless 不一致）。
     * 实际对象 `SpriteBitmapSource` 同时实现 [ImageFrameSource] 与
     * [HtmlRunFlatten.ImageSizeSource]（platform 侧声明），故直接转发。
     *
     * 用 lambda 现读而不是构造期抓一份：图形通路是后挂的（[attachGraphics]），
     * 子行展平发生在规划期（[divPlanner] 的子行折行）⇒ 现读才能拿到最新挂载；
     * 未挂/实现对不上时返回 null，回落见方占位（旧行为）。
     */
    private val divSizeSource: HtmlRunFlatten.ImageSizeSource = HtmlRunFlatten.ImageSizeSource { resource ->
        (frameSource as? HtmlRunFlatten.ImageSizeSource)?.destSize(resource)
    }

    /**
     * div 子行展平器（W2；自持实例 —— `HtmlRunFlatten` 有逐串状态，与 feed 的正文展平器
     * 分开持有让两处调用序互不干扰）。图形段尺寸源见 [divSizeSource]（与正文同一把尺）。
     */
    private var divFlattener: HtmlRunFlatten =
        HtmlRunFlatten(metrics, sizeSource = divSizeSource, scriptFontPixelSize = sourceFontPixelSize)

    /**
     * div 覆盖绘制规划器（CONTRACT-DIV-WIRE W2）：脚本 px 的折算基尺喂**配置字号**
     * （[sourceFontPixelSize] —— 与 `ConsoleFeed.scriptFontPixelSize` 同源，都是会话生效
     * `Config.FontSize`），`CellMetrics.scriptPixels` 据此把块几何折到设备像素，与行文字
     * 同一把尺；子行折行（`DivChildDraw.wrap`）在规划期由注入的 [divFlattener] 统一做，
     * 绘制/命中两侧只消费 `ChildRow` 成品。随 [applyMetrics] 换代（字号/行高变了块的
     * 矩形全体重折）。
     */
    private var divPlanner: DivOverlayPlanner = DivOverlayPlanner(metrics, sourceFontPixelSize, divFlattener)

    /** 上一帧是否画过覆盖块：退场帧（块没了但行签名没变）也要整幅一次清掉旧像素。 */
    private var lastFrameHadOverlay: Boolean = false

    /**
     * 族名 → Typeface 的**实例级**缓存（每族只 `createFromAsset` 一次）。
     *
     * 为什么不是构造期一个字段：正文与 `SETFONT` 段可以同屏出现不同族（语料 14 处
     * `SETFONT "Saitamaar"` 画字符画），选族键是 `GlyphKey.fontName`，逐段不同。
     * 键取**归一后的族名**（[BundledFontFamilies.resolve] 的结果），保证
     * `ＭＳ ゴシック` / 空串 / 未知名都共享同一个默认族实例，不会重复解码。
     */
    private val typefaces = HashMap<String, Typeface>(4)

    /**
     * 族名 → 基线偏移的缓存。基线与字体度量绑定（`ascent`/`descent` 各族不同），
     * 用别的族的基线画本族字形会整体上下错位，所以按族各算一份。
     * 缺省族那份就是 `baselineOffsetOf(BundledFontFamilies.DEFAULT_FAMILY)`。
     *
     * [applyMetrics] 改字号/行高时必须整表清空 —— 基线与 `paint.textSize` 绑定。
     */
    private val baselines = HashMap<String, Float>(4)

    /**
     * 请求字体名 → 登记族的实例缓存（[ResolvedFontFamilyCache]，纯 JVM 有单测）。
     *
     * 逐字形热路径此前每个字形要调两次 [BundledFontFamilies.resolve]（一次选 Typeface、
     * 一次算基线），而它每次都先 `normalize` 建新字符串、未命中再对五个登记族各归一
     * 一遍 —— 全屏两千字形 × 60 帧 = 每秒数十万个临时字符串。这里按请求名原样记住结果，
     * 命中返回同一个登记实例，落到 [typefaces] / [baselines] 的族名键语义完全不变
     * （键仍是登记族的 `family`，不是请求名）。
     *
     * 只在本类持有、只在 UI 线程（onDraw / 命中路径）访问，无锁。
     */
    private val fontFamilies = ResolvedFontFamilyCache()

    /** 最近一次取用的族与 Typeface（`metricOf` 的快路径，避免每字形查两次 map）。 */
    private var lastTypefaceFamily: String? = null
    private var lastTypeface: Typeface? = null

    /**
     * 单源解算：把「可见区像素 + 配置字号 + 显示大小倍率 + 列数守护 + 窗口宽/高」交给
     * [ScreenLayout.resolve]。
     *
     * 唯一调用方是 [rebuildScreen] / [applySourceFontMetrics] / [reflowAttachedFeed]；
     * **任何页面内容、图片展平结果都不参与**。`widthPx` 用可绘制宽（已扣形状原点 shift），
     * 与绘制/命中同一口径；`contentWidthPx` 传**物理内容区宽**（含 shift），让解算点能算出
     * 右端预留与居中偏移。shift、字号、窗口宽/高三者互为函数（`WindowX` 从脚本 px 折到设备 px
     * 的比例就是「真机字号 / 脚本字号」），故先对它们做**同一个不动点迭代**再落定（最多 5 轮）。
     *
     * 解出的客户区几何（[ScreenLayout.layoutWidthPx] / [ScreenLayout.contentInsetLeftPx]）在这里
     * 写进 [resolvedLayoutWidthPx] / [resolvedContentInsetLeftPx] —— [contentLeftPx] 与
     * [layoutWidthPx] 的单一权威读数，绘制/命中/取色只许从这里取，不再另算一份。
     *
     * @param w 可见宽（像素，0 = 未布局）
     * @param h 可见高（像素，0 = 未布局）
     */
    private fun resolveFor(w: Int, h: Int): ScreenLayout {
        val contentWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        val densityDpi = resources.displayMetrics.densityDpi
        val minColumns = if (columnGuardEnabled) columns else 0
        if (contentWidth <= 0) {
            val layout = ScreenLayout.resolve(
                widthPx = 0,
                heightPx = contentHeight,
                densityDpi = densityDpi,
                configFontPx = sourceFontPixelSize,
                configLineHeightPx = sourceLineHeight,
                minColumns = minColumns,
                fontScale = fontScale,
            )
            publishLayoutGeometry(layout, contentHeight)
            return layout
        }
        // shift 的底料是**生效字号**（`Config.cs:461-466` 的 `FontSize/6`），而字号本身又由
        // 「扣掉 shift 的可绘制宽」反推 —— 直接拿旧字号的 shift 会算错一档：
        // 字号变大时 shift 变大，少扣会把列数算多；字号变小时 shift 变小，多扣会把列数算少。
        // 窗口宽/高同理：脚本 px → 设备 px 的折算比就是本轮的「真机字号 / 脚本字号」，
        // 候选字号没定就折不出最终客户区宽。三者一起迭代（最多 5 轮 = 既有 4 轮上限 + 1 轮余量，
        // 收敛判据取 (居中偏移, 列数, 行数, 版面宽, 字号) 元组相等）。
        var inset = shapeShiftPx
        var layout = resolveWithInset(contentWidth, contentHeight, densityDpi, minColumns, inset)
        var rounds = 0
        while (rounds++ < 5) {
            val nextInset = ShapePositionShift.of(layout.fontPixelSize)
            val windowWidthPx = windowWidthPxFor(layout.metrics)
            val windowHeightPx = windowHeightPxFor(layout.metrics)
            val next = resolveWithInset(
                contentWidth, contentHeight, densityDpi, minColumns, nextInset,
                windowWidthPx, windowHeightPx,
            )
            val converged = nextInset == inset &&
                next.fontPixelSize == layout.fontPixelSize &&
                next.columns == layout.columns &&
                next.rows == layout.rows &&
                next.layoutWidthPx == layout.layoutWidthPx &&
                next.contentInsetLeftPx == layout.contentInsetLeftPx
            inset = nextInset
            layout = next
            if (converged) break
        }
        publishLayoutGeometry(layout, contentHeight)
        return layout
    }

    /**
     * 把本轮 [ScreenLayout] 的客户区几何落成单一权威读数（[contentLeftPx] / [layoutWidthPx] /
     * [clientHeightPx] 的底料）。
     *
     * 未接线时 `layoutWidthPx` 就是物理内容宽、偏移恒 0 —— 与改动前逐像素相同；`clientHeight`
     * 只有在 `WindowY` 接线后才有独立读数，未接线时 [clientHeightPx] 仍按物理内容高现算。
     */
    private fun publishLayoutGeometry(layout: ScreenLayout, contentHeightPx: Int) {
        resolvedLayoutWidthPx = layout.layoutWidthPx
        resolvedContentInsetLeftPx = layout.contentInsetLeftPx
        resolvedClientHeightPx = if (sourceWindowHeightScriptPx > 0) {
            windowHeightPxFor(layout.metrics).coerceAtMost(contentHeightPx)
        } else {
            contentHeightPx
        }
    }

    /** 当轮候选度量下 `WindowX`（脚本 px）→ 设备 px；未接线 ⇒ 0（[ScreenLayout.resolve] 视作满宽）。 */
    private fun windowWidthPxFor(candidate: CellMetrics): Int =
        if (sourceWindowWidthScriptPx > 0) {
            candidate.scriptPixels(sourceWindowWidthScriptPx, sourceFontPixelSize).coerceAtLeast(0)
        } else {
            0
        }

    /** 当轮候选度量下客户区高（`WindowY - LineHeight` 脚本 px → 设备 px）；未接线 ⇒ 0。 */
    private fun windowHeightPxFor(candidate: CellMetrics): Int =
        if (sourceWindowHeightScriptPx > 0) {
            candidate.scriptPixels(
                (sourceWindowHeightScriptPx - sourceLineHeight).coerceAtLeast(0),
                sourceFontPixelSize,
            ).coerceAtLeast(0)
        } else {
            0
        }

    /**
     * @param contentWidthPx 物理内容区宽（含右端预留）
     * @param insetPx 右端形状预留（`Math.Max(2, FontSize / 6)` 的本轮候选读数）
     */
    private fun resolveWithInset(
        contentWidthPx: Int,
        contentHeightPx: Int,
        densityDpi: Int,
        minColumns: Int,
        insetPx: Int,
        windowWidthPx: Int = 0,
        windowHeightPx: Int = 0,
    ): ScreenLayout = ScreenLayout.resolve(
        widthPx = (contentWidthPx - insetPx).coerceAtLeast(0),
        heightPx = contentHeightPx,
        densityDpi = densityDpi,
        configFontPx = sourceFontPixelSize,
        configLineHeightPx = sourceLineHeight,
        minColumns = minColumns,
        fontScale = fontScale,
        contentWidthPx = contentWidthPx,
        windowWidthPx = windowWidthPx,
        windowHeightPx = windowHeightPx,
    )

    /** 应用新度量；字号/行高变了就整链作废缓存（原因见下）。 */
    private fun applyMetrics(next: CellMetrics) {
        if (next.fontPixelSize == metrics.fontPixelSize && next.lineHeight == metrics.lineHeight) return
        metrics = next
        fontPixelSize = next.fontPixelSize.toFloat()
        halfCellPx = next.cellPixelWidth
        lineHeightPx = next.lineHeight.toFloat()
        // W6-H6 换代刀：shift 的算式底料是**生效字号**（Config.cs:461-466 的 FontSize/6），
        // 必须与 halfCellPx/lineHeightPx 同批改写 —— 否则换字号后绘制/取色的形状原点
        // 停在旧档（真机 36px 档停在 18px 档的 3px），且可绘制宽预留（drawableWidthPx）失真。
        shapeShiftPx = ShapePositionShift.of(next.fontPixelSize)
        // 字号/行高变了 ⇒ 按族缓存的基线全部失效（基线与 paint.textSize 绑定）
        baselines.clear()
        // 字形实测宽缓存同样与 paint.textSize 绑定：GlyphMetric.measured 是当时字号下的
        // paint.measureText 原生读数，绘制期再由 textScaleX = 格数x半角格宽 / measured
        // 反推缩放。DisplayList 建 GlyphKey 时一律不传 size（默认 0，见 DisplayList.kt:656,659），
        // packGlyphKey 的字号段恒为 0，打包键无法区分字号。若字号在装配期换代
        // （配置 FONT SIZE / 显示大小档 / 列数守护压小，真机 36px->30px）后不清此表，
        // measured 仍是旧字号读数，每字形前进量按「新字号/旧字号」比例偏窄
        //（36->30 约 -17%），整行从左向右压缩、后段字形叠到前段上。必须与 baselines 一起整表作废。
        metricsByFont.clear()
        lastFontName = null
        lastFontBucket = null
        // 排版尺与绘制必须同一把尺：推进量按「真实 advance ÷ 半格像素宽」算，分母就是
        // halfCellPx。换字号后不清尺缓存，尺仍按旧字号折算（真机 36px->30px 约 +20%），
        // 且 FontWidthRuler.cellMetrics 的 setter 自带整表清缓存，这里直接赋值即可。
        fontRuler.cellMetrics = next
        // div 覆盖面（CONTRACT-DIV-WIRE W2）：规划器的折算尺与子行展平器的图形字号都绑
        // 生效度量，随本方法换代（与上面 shapeShiftPx 同一批改写的道理）。展平器先换代、
        // 规划器接新的那一份 —— 子行折行发生在规划期，规划器必须拿到新度量的展平器。
        divFlattener = HtmlRunFlatten(next, sizeSource = divSizeSource, scriptFontPixelSize = sourceFontPixelSize)
        divPlanner = DivOverlayPlanner(next, sourceFontPixelSize, divFlattener)
        // 同一次度量换代还要作废**像素侧**的两份缓存：行位图是按旧字号烤死的、保留面里也是
        // 旧字号的像素。行高没变而字号变了时，行位图的几何判据（行高/内容宽）挡不住这种
        // 陈旧 —— 不清就会把旧字号的字原样贴回来（ADR-009 决策 4）。
        rowCache.clear()
        planner.notifySurfaceLost()
    }

    private var screen: ConsoleScreen? = null
    private var pending: List<RenderLine> = emptyList()
    private var feed: ConsoleFeed? = null

    /**
     * 一次性「首屏有内容」回调（UI 线程）：生产桥发布出第一屏**非空**内容并完成落笔后调用。
     *
     * 装载覆盖层的收尾点曾经挂在「程序装载完成、脚本即将开始执行」（`GameHost.onConsoleReady`），
     * 前提是 4.980 脚本紧接着自己画「少女祈祷中」进度条。4.981 删掉了那段脚本进度条，
     * 覆盖层照旧提前收就露出空屏。改为等**真正有内容的一帧落笔之后**再收：
     * 4.980 那一刻正是脚本进度条的首帧（彩条照常可见），4.981 则是标题页首帧 ——
     * 两个版本的「加载页 → 可见画面」之间都不再有空屏。
     *
     * 只认挂了 [ConsoleFeed] 的生产路径：`submitSnapshot` 的演示/失败屏不该被当成游戏首帧。
     */
    var onFirstContentFrame: (() -> Unit)? = null

    /** 宿主图形层（`SETBGIMAGE` 那几条语句的落点）；null = 图形通路没接线。 */
    private var graphicsLayer: GraphicsLayer? = null

    /** 资源名 → 位图的取图口（`:platform` 的 `SpriteBitmapSource`）。 */
    private var frameSource: ImageFrameSource? = null

    /**
     * CBG 动画层的**帧时钟**（哪一帧是「当前帧」）。
     *
     * core 的 CBG 快照只在 `CBG*` 语句执行时推送（无逐帧重推通道），因此「动画 CBG 会动」
     * 完全押在绘制侧喂时钟：本类每帧用 [CbgDrawOrder.CbgAnimeClock] 算已播放毫秒，
     * 交给 [CbgDrawOrder.frameGeometry] 取帧。与 headless `ScreenComposer` 同一份函数，
     * 两边不会「预览对、真机错」。只在 UI 线程访问（与 [ScreenComposer] 同一口径）。
     */
    private val cbgClock = CbgDrawOrder.CbgAnimeClock()

    /**
     * CBG 按钮的**按下态取色值**（`cbgButtonMap` 命中值的 `& 0xFFFFFF`，原版
     * `EmueraConsole.cs:1840-1843` 的 `selectingCBGButtonInt`）；null = 没有按住 CBG 按钮。
     *
     * 绘制侧据此把 `isButton && buttonValue == cbgPressedValue` 的层改画
     * [CbgLayerSnapshot.spriteNameB]；命中侧由 [pointerCellRow] 上游的 [setPointer] 写入。
     */
    private var cbgPressedValue: Int? = null

    /**
     * CBG 按钮命中回调（UI 线程）：参数 = 取色图命中值（`0xRRGGBB`）。
     *
     * 非 null 时 [tap] 在派发前先试 CBG 取色图；命中即调用本回调并**不再**走文本通道
     * （原版 `InputMouseKey` 走 `InputResult5`，与文本输入是两条投喂口）。
     * 装配侧接 `GameHost.submitCbgButtonValue` → `QueueInputSink.submitCbgButtonValue`。
     */
    var onCbgButtonValue: ((Int) -> Unit)? = null

    /**
     * CBG 按钮**是否在等待期**（`IsWaitingPrimitive`，`EmueraConsole.cs:1300-1305`）。
     *
     * 原版 `MouseDown` 一进门就是 `if (!IsWaitingPrimitive) return;` —— 不是 INPUTMOUSEKEY
     * 等待期时**连取色都不发生**，CBG 按钮区域该落到的文本/div 命中照常走。所以取色图命中
     * 与按下换图都必须先过这道闸，否则普通 INPUT 屏上点 CBG 区域会把点击吞掉。
     *
     * 装配侧接 `host.pendingRequest()?.form == PRIMITIVE_MOUSE_KEY`；null = 未接线 ⇒ 不取色
     * （回落旧路径，行为与接线前一致）。
     */
    var cbgButtonArmed: (() -> Boolean)? = null

    /**
     * 绘制异常兜底的两个计数（只在 UI 线程读写，不需要 `@Volatile`）。
     *
     * 见 [onDraw] 的 catch 段：`drawFailureCount` 是全量计数（诊断读数），
     * `drawFailureReported` 决定「首个异常是否已经打过全栈」。
     */
    private var drawFailureCount = 0L
    private var drawFailureReported = false

    /**
     * 指针按下态（纯绘制标记，不参与命中）：行是**命中口径**的行下标，列是屏幕半角格；
     * -1 = 无指针。真正的状态源是 `ConsoleScreen.buttons` 的 `ButtonLayer`，这里只留一份
     * 坐标用于在绘制侧反查「当前被按住的命中区」（`ButtonLayer` 只给 `isPressed(target)`，
     * 不给指针坐标；每帧调 `all()` 会分配一张列表，违反帧零分配约定）。
     */
    private var pointerHitRow = -1
    private var pointerHitCol = -1
    private var pointerDown = false
    private var pointerAcceptance = ButtonAcceptance.ANY

    /** 复用的裁剪/目标矩形：`onDraw` 每帧都要用到，不能每帧 new（帧零分配约定）。 */
    private val srcRect = Rect()
    private val dstRect = RectF()

    /**
     * 字形前进量缓存：键就是显示列表建好的那个 [GlyphKey] 实例（`fontName` + `styleMask` +
     * `codePoint`，提交期只建一次并被干净行复用，所以这里不新增键对象），值 = 该字形的
     * `Paint.measureText` 原始宽度（未缩放的像素）。
     *
     * 原版只在 `ConsoleButtonString.CalcWidth` 对宽度未算过的节点测一次、之后一直复用
     * （spec: `ConsoleButtonString.cs:257-270`）；本 View 早先每帧对每个字形重测一次，
     * 一屏就是数千次原生调用。字体固定，但字号可随配置或屏宽变化在运行期换代（[applyMetrics]
     * 改字号时整表作废），`textScaleX` 测量前复位成 1，
     * 且 `styleMask` 的粗体/斜体已经进键 —— 同一键的测量结果恒定，只有未命中才测。
     */
    /**
     * 字形绘制度量缓存（2026-10-24 抖动收口）。
     *
     * 原来每画一个字形都要 `String(Character.toChars(cp))`（全屏每帧几千次分配）+ `Grid.cells`
     * + 一次独立的 `canvas.drawText`；`dumpsys gfxinfo` 实测整屏重绘 p99 77 ms、13.5% 掉帧，
     * 分配量又喂出周期性 GC —— 玩家侧表现为「每十几秒整屏抖一下」。
     * 这里把「字符串 + 实测宽 + 格宽」按 [GlyphKey] 缓存，绘制期零分配。
     */
    private class GlyphMetric(
        val text: String,
        val measured: Float,
        /**
         * 该字形的**真实前进量**（浮点半角格，来自 [fontRuler]）。
         * 等宽族落在整格吸附档（半角 1 / 全角 2），比例字体保留亚格读数
         * （Saitamaar 发丝空格 0.12 格）—— 字符画的像素级微调就靠它。
         * 接线前这里存的是 `Grid.cells` 的静态 CP932 格数，`SETFONT` 换字形不换尺，
         * AA 画因此在屏上散架（修复证据见 [FontWidthRuler] 类文档）。
         */
        val advanceCells: Float,
        /** 实际用来画这个字形的族（缺字时可能不是请求族，见 [effectiveFamily]）。 */
        val family: BundledFontFamily,
        /** 该族在当前字号下的基线偏移（行盒顶 → 基线）。 */
        val baseline: Float,
    )

    /**
     * 字形度量缓存：**按字体名分桶 + 打包 Long 键**（2026-10-24 再提速）。
     *
     * 用 `HashMap<GlyphKey, …>` 时每个字形都要算一次键的 `hashCode`（含 `String.hashCode`
     * 与 data class 的字段混合）再做 `equals`（比 String）——全屏 3000 字形 × 每次重绘。
     * 这里把「字体」提到外层（同一行几乎总是同一个字体，命中 [lastFontBucket] 快路径时
     * 连字符串比较都省掉），内层键打包成一个 Long：codePoint(32) | styleMask(8) | size(24)。
     *
     * 内层用 [LongSparseArray] 而不是 `HashMap<Long, …>`：后者每次查表都要把 long 装箱成
     * `Long`（16 B/次，命中缓存也照分配）。2026-11-07 真机报告 `5783b90` 的崩溃栈顶正是
     * `metricOf` → `Long.valueOf` —— 堆已贴顶时，这一下就是主线程倒下的最后一环。
     * [LongSparseArray] 的键是原生 `long[]`（二分查找、不装箱），换掉后**命中路径**零分配。
     */
    private val metricsByFont = HashMap<String, LongSparseArray<GlyphMetric>>(4)

    /** 上一次命中的字体桶（同一行/相邻行通常连续命中，避免每字形一次外层查表）。 */
    private var lastFontName: String? = null
    private var lastFontBucket: LongSparseArray<GlyphMetric>? = null

    /** 每行命令分组（每帧复用，避免新建 Map/List）。 */
    private val rowCmds = HashMap<Int, ArrayList<DrawCmd>>(64)

    // ---- 保留面（2026-10-26 真机黑屏的修复主体）------------------------------------
    //
    // 旧实现直接把「这一行的内容签名没变」当成「这一行不用画」，前提是屏幕上还留着
    // 上一帧的像素。安卓不认这个前提：本类所有失效点都是无参 [invalidate]，硬件加速下
    // 每帧重录整个 RenderNode，传进 [onDraw] 的画布从空白开始 —— 没落笔的地方透出
    // `setBackgroundColor(Color.BLACK)`（`app/.../MainActivity.kt:197`）⇒ 黑屏。
    // 症状 1（点选项后黑一秒多）与症状 2（色付き地图只剩会动的字符）同源于此。
    //
    // 现在改由**我们自己持有那块像素**：`screenSurface` 是一张与 View 等大的位图，
    // 增量的只是「哪些行要重新栅格化进这张图」，而 [onDraw] **每帧整幅贴图**，
    // 所以画面永不再依赖平台留像素；原版是即时全量重绘（spec: `EmueraConsole.cs:1816`
    // 的 `graph.Clear(bgColor)` 与 `:1852-1856` 的整视口逐行重画），本方案与它同形，
    // 只是把重活（栅格化）留在增量侧。判定口径与触发条件全在 [ScreenSurfacePlanner]（纯 JVM 可测）。

    /** 每帧刷新判定（含上一帧的行签名表与版面指纹）。 */
    private val planner = ScreenSurfacePlanner()

    /** 本帧可见行的内容签名缓冲（复用；有效长度 = [RowLayout.Plan.tops] 的 size）。 */
    private var rowSigBuf = IntArray(64)

    /** 保留面：与 View 等大的屏幕位图。null = 还没建（首帧建）。 */
    private var screenSurface: Bitmap? = null

    /** [screenSurface] 的画布包装（同生命周期，避免每帧 new Canvas）。 */
    private var screenSurfaceCanvas: Canvas? = null

    /** 保留面的尺寸快照；与 View 尺寸不一致即重建（重建 = [ScreenSurfacePlanner.notifySurfaceLost]）。 */
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** 贴图专用画笔：1:1 拷贝，不要滤波也不要抖动（滤波会把边缘算成半透明）。 */
    private val blitPaint = Paint().apply { isFilterBitmap = false; isDither = false }

    // ---- 行栅格位图缓存（修复③：原版 bitmapCacheArray 的对应物）--------------------

    /**
     * 行位图缓存：整幅帧（滚动、背景层、内联图形进出、显式置脏）要把全部可见行重栅格化，
     * 内容没变的行在这里直接贴回烤好的位图，不再逐段重放。对齐原版
     * `ConsoleButtonString.DrawTo` 的行位图（spec: `ConsoleButtonString.cs:317-366`）
     * 与 256 槽轮替（`EmueraConsole.cs:70-76`）；槽位数照抄，**真机另由字节预算先约束**
     * （一块 2400×44 的 ARGB_8888 行位图 = 422 KB，256 槽能吃掉 105 MB）。
     *
     * 与原版一致的口径：它是性能项，命中与否都不许改变像素；原版由脚本
     * `BITMAP_CACHE_ENABLE` 逐行显式开（`FunctionMethodCreator.cs:8674`/`:9305`，
     * 语料实证 `ERB/COLOREDMAPS/DRAW_COLOREDMAP.ERB:31` 与 `:145`），本仓做成自动 ——
     * 差别只在速度，不在画面。烤图**只含本行自身内容**（透明底），底色与背景仍按帧重铺，
     * 所以命中路径与直画路径逐像素同形。
     */
    private val rowCache = RowRasterCache<Bitmap>(
        maxBytes = ROW_CACHE_MAX_BYTES,
        costOf = { b -> if (b.isRecycled) 0L else b.allocationByteCount.toLong() },
        onEvict = { b -> poolOrDropRowBitmap(b) },
    )

    /** 行位图回收池（按同尺寸复用，避免地图那种「每 tick 全部行重烤」的分配抖动）。 */
    private val rowBitmapPool = ArrayList<Bitmap>(ROW_POOL_MAX)

    /** 池内位图占的字节（与张数双上限，见 [poolOrDropRowBitmap]）。 */
    private var rowPoolBytes = 0L

    /** 行位图的临时画布（复用；每次烤行前 `setBitmap`，烤完置 null 不牵住位图）。 */
    private val rowCanvas = Canvas()

    /**
     * 本帧已经有一行**烤不出位图**（`Bitmap.createBitmap` 抛 `OutOfMemoryError`）的记号。
     *
     * 有它之后：本帧余下的行一律走逐段直画（画面一字不差，只是不进缓存），
     * 每帧最多一次失败尝试、一行 logcat、一次 [releaseMemoryCaches]；帧首在
     * [drawScreen] 取到保留面之后清零，下一帧重新给一次机会。
     * UI 线程单线程读写（口径同 [lastFrameFull] 的绘制侧字段，铁律 4 无静态可变）。
     */
    private var rowBakeBlocked = false

    /** 取证读数：本帧贴图是否走了整幅（与 [planner] 的 full 同值，供 debugStats 直读）。 */
    @Volatile
    private var lastFrameFull = false


    /**
     * 形状（内联图 / 色块）的横向位移量（`Config.DrawingParam_ShapePositionShift`）。
     *
     * 原版的**可绘制宽度不是窗口客户区宽**：
     * `shift = Math.Max(2, FontSize / 6)`，`DrawableWidth = WindowX - shift`
     * （spec: `Config.cs:461-466`）。PC 基准 `WindowX = 1800`、`FontSize = 18`
     * ⇒ shift = 3、DrawableWidth = 1797px，除以实测半角步进 9px ⇒ **199 格**
     * （钉在 `fixtures/golden/baseline/pc-golden-root.txt:253` 的 199 个 `=` 与
     * `render/.../glyph/CellMetrics` 的 9px 换算式上）。
     * 折行宽、`<p align>` 的居中基准、DRAWLINE 的填充宽都读 DrawableWidth，
     * 所以本 View 交出去的列数必须是「(客户区宽 - shift) ÷ 半角步进」而不是「客户区宽 ÷ 步进」。
     *
     * ## 原版把 shift 加在**绘制侧**，取色侧再减回去（W6-H6 横向刀的裁决）
     *
     * 图片绘制 `rectangle.X = destRect.X + PointX + shift`（spec: `ConsoleImagePart.cs:171`）、
     * 色块测量期烘焙 `rect.X += shift`（spec: `ConsoleRectangleShapePart.cs:57`）、
     * 取色 `num = pointX - PointX - mask.PointX - shift`（spec: `ConsoleButtonString.cs:147`）。
     * 「取色减 shift 成立的前提是绘制加 shift」—— 本 View 早先**绘制不加、取色照抄原版去减**，
     * 两把尺差一个 shift，长按取色横偏约字号/6 像素。修法裁决取「贴原版」一支：
     * 图形段绘制原点统一走 [MappingColorGeometry.graphicOriginLeftPx]（列游标 + shift），
     * [mappingColor] 用同一函数换算；**文本与缺帧回落的 altText 不沾 shift**
     * （spec: `ConsoleImagePart.cs:177,181` 用裸 `PointX`，见 [drawImageAltText]），
     * 命中列换算也不加 shift（原版命中判式同样只比裸 `PointX` 域）。
     *
     * ## 换代（W6-H6 第二刀）
     *
     * shift 跟着**生效字号**走：构造期按 [metrics] 初值算，[applyMetrics] 换字号时与
     * `halfCellPx`/`lineHeightPx` 同批重算（算式单源 [ShapePositionShift.of]）。
     * 此前它是构造期固化的 val，旧 fit 模式 36px 档下 shift 停在旧字号读数，
     * 绘制与取色一起偏、且 [drawableWidthPx] 的预留也跟着失真。
     */
    private var shapeShiftPx: Int = ShapePositionShift.of(metrics.fontPixelSize)

    /** 当前可绘制宽（像素）：版面客户区宽减掉 [shapeShiftPx]。 */
    private fun drawableWidthPx(): Int = (layoutWidthPx() - shapeShiftPx).coerceAtLeast(0)

    /**
     * 版面客户区宽（设备 px）：[ScreenLayout.layoutWidthPx] 的**唯一权威读口**。
     *
     * = `min(窗口宽, 物理内容宽)`（未接线 ⇒ 物理内容宽）；窗口宽由 [applySourceWindowMetrics]
     * 灌入、经 [resolveFor] 夹取后存在 [resolvedLayoutWidthPx]。绘制右沿、命中上界、
     * `CLIENTWIDTH()` 与 [drawableWidthPx] 都只读它 —— 不许再从
     * `width - paddingLeft - paddingRight` 另算一份（那会漏掉窗口宽与居中偏移）。
     */
    fun layoutWidthPx(): Int = resolvedLayoutWidthPx

    /**
     * 客户区左沿（View 坐标）＝ `paddingLeft + 居中偏移`：全部绘制/命中/取色的**唯一左沿来源**。
     *
     * 窗口宽窄于物理内容区时客户区在屏上水平居中（[ScreenLayout.contentInsetLeftPx]，
     * 左右均分、余 1 px 归左）；文本、图形段原点、背景/图层、div 覆盖、命中列换算与取色
     * 全读这一处，不得再出现 `paddingLeft.toFloat()` 充当内容左沿的独立算例（R4.2 单一权威）。
     */
    private fun contentLeftPx(): Float = (paddingLeft + resolvedContentInsetLeftPx).toFloat()

    /**
     * 本 View 的像素 ↔ 半角格换算器。装配侧（`GameHost`）必须拿**这一个实例**去建
     * [ConsoleFeed] 的展平器，否则图形段的占宽与实际绘制的列位置会分叉。
     */
    fun cellMetrics(): CellMetrics = metrics

    /** 内容区可用高（像素）：竖带合并的块高上限与装配层读数共用这一处口径。 */
    fun contentHeightPx(): Int = (height - paddingTop - paddingBottom).coerceAtLeast(0)

    /**
     * 竖带图块（首页标题图）合并的整块高上限（像素）。
     *
     * 取内容高的 [BAND_MAX_HEIGHT_SHARE]：标题图下面还有十来行页脚文字，不能让整块图把
     * 一屏吃满。真机 1080 高 ⇒ 648px 图 + 12 行 31px 文字 ≈ 33 行，正好落进 34 行视口
     * （2026-10-25 标题页实测：47 行 / 28 行视口 ⇒ 顶部 181px 黑带）。
     */
    fun bandMaxHeightPx(): Int = (contentHeightPx() * BAND_MAX_HEIGHT_SHARE).toInt()

    /**
     * 内联图形（`<img>` / `<shape>`）的基准字号像素：喂给 `ConsoleFeed(artFontPixel = …)`。
     *
     * 取 `min(PC 基准, 内容高/36)`：
     * - **PC 基准** = 让一行铺满 [PC_REFERENCE_COLUMNS] 列的字号（2400px 屏约 23px）。eratw
     *   的标题图是 35 条 1041×16 横带逐行 `<img>` 打印（`ResourceManager.ERB:8-10` +
     *   `TITLE.ERB:62`），按 PC 基准画才能保住它的横向版面（否则 110 列档下宽 ≈2733px
     *   超出 2400 屏宽、右侧被裁）；
     * - **内容高/36** = 「35 行素材 + 1 行余量」的上限，小屏上避免图形比可见区还高。
     *
     * 注意：本值只影响图形段的尺寸/占宽，不改文字行高（行高仍由用户字号档位决定）。
     *
     * **本函数至今无人调用（死钩子），而且它跟已经落地的「脚本 px 尺」不是一把尺**：
     * 后者是 `ConsoleFeed(scriptFontPixelSize = …)` / `AndroidGraphicsSink(scriptFontPixelSize = …)`
     * 里那个**引擎生效 `Config.FontSize`**（真机 ≈18，折算系数 = 真机字号 ÷ 它，见
     * `glyph/CellMetrics.scriptPixels`）；本函数按 199 列反推出来的 ≈23px 是「PC 窗口的字号」，
     * 两者差 1.3 倍。接线前必须先定夺要用哪一把，别照这句 KDoc 的老措辞直接塞进去
     * （2026-10-27 立绘空档修复时登记）。
     */
    fun artBaselineFontPx(): Float {
        val dm = resources.displayMetrics
        val landscapeWidthPx = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
        val pc = landscapeWidthPx /
            (PC_REFERENCE_COLUMNS * CellMetrics.CELL_PIXEL_FACTOR / 2f + 1f / 6f + 0.03f)
        val contentHeight = (height - paddingTop - paddingBottom).toFloat()
        val byHeight = if (contentHeight > 0f) contentHeight / ART_ROWS_WITH_MARGIN else pc
        return minOf(pc, byHeight).coerceAtLeast(CellMetrics.MIN_FONT_PIXEL_SIZE.toFloat())
    }

    /** 窗口宽对应的列数（半角格，**不**减 [shapeShiftPx]）：命中域的上界用它。 */
    fun windowColumns(): Int = floor(layoutWidthPx() / halfCellPx).toInt().coerceAtLeast(1)

    /**
     * 客户区像素宽（GFX-06 / R4.4）：`CLIENTWIDTH()` 的真机注入源（**设备像素**）。     *
     * 原版 = `MainPicBox.Width`（spec: `EmueraConsole.cs:232`），即窗口客户区整宽 =
     * `Config.WindowX` 折到设备 px（不减 shift；shift 只进 DrawableWidth，见 [shapeShiftPx]）。
     * 未接线时就是物理内容区宽（与改动前逐值相同）。布局未发生时为 0 —— 调用方
     * （GameHost.start）只在 [consoleScreen] 就位后才读，与列数同一时点。
     * 核心收到后按 [cellMetrics] 的字号折成脚本 px 再交给脚本（见 `ScriptScreenState`）。
     */
    fun clientWidthPx(): Int = layoutWidthPx()

    /**
     * 客户区像素高（GFX-06 / R4.4）：`CLIENTHEIGHT()` 的真机注入源（**设备像素**；折脚本 px 同
     * [clientWidthPx]）。
     * 原版 = `MainPicBox.Height` = `WindowY - LineHeight`（spec: `EmueraConsole.cs:234`）；
     * 未接线（`WindowY` = 0）⇒ 物理内容高，纵向观感与改动前逐像素相同。
     */
    fun clientHeightPx(): Int =
        if (sourceWindowHeightScriptPx > 0) resolvedClientHeightPx else contentHeightPx()

    /**
     * 提交整屏快照（生产喂法同 [ConsoleScreen.submitSnapshot]）。布局尚未发生时先缓存，
     * [onSizeChanged] 建出 `ConsoleScreen` 后立即补交。
     */
    fun submitSnapshot(lines: List<RenderLine>) {
        pending = lines
        screen?.submitSnapshot(lines)
        invalidate()
    }

    /**
     * 挂上核心层 sink 的整屏快照桥（CONSOLE-FEED）：此后 [onDraw] 先 `feed.pump()`
     * （revision 未变时零开销），再画 `feed.screen.list.render()`。
     *
     * 原 [submitSnapshot] 演示路径保留：未挂 feed 时本 View 的行为与之前逐项相同。
     * 宽度口径是 [ConsoleFeed] 构造时校验过的 `sink.clientWidth == screen.clientWidth`；
     * 本 View 只按自己的字号把半角格铺到像素，不参与语义。
     */
    fun attachFeed(feed: ConsoleFeed) {
        this.feed = feed
        invalidate()
    }

    /**
     * LIVE-REFLOW：视口宽度变化回调（UI 线程）。参数 = 新列数 / 新可见行数 /
     * 内容区像素宽 / 像素高 / 本次版面解算出的**设备字号**。null（缺省）= 不启用重排，
     * 行为与旧路径相同。
     *
     * 最后那格是给核心折回脚本 px 用的（`CLIENTWIDTH()` 要的是脚本坐标系读数，见
     * `ScriptScreenState` 的类文档）。**必须传本次 `resolveFor` 解出的字号**，不能读
     * [cellMetrics]：换屏动作（`reflowAttachedFeed`）在回调返回之后才 `applyMetrics`，
     * 回调执行期 [cellMetrics] 还是旧字号，转屏后会拿旧比例折新宽度。
     */
    var onViewportChange: ((columns: Int, rows: Int, widthPx: Int, heightPx: Int, fontPx: Int) -> Unit)? = null

    /**
     * LIVE-REFLOW（L4）：核心侧已完成 `sink.resize(columns)` 后，用新列数/行数重建
     * [ConsoleScreen] 并换给 [ConsoleFeed]，返回是否真的换了屏。
     *
     * ## 顺序（写死，别调换）
     *
     * 1. [applyMetrics]：先按**当前可见区**重解一次度量。宽变了「列数守护」的判据也变
     *    （[ScreenLayout.resolve] 是先压字号保列数、再算列数），新屏的折行宽、字形与基线
     *    必须按换代后那一套走 —— 换代连带的缓存作废见 [applyMetrics] 的 KDoc；
     * 2. 新建**空** [ConsoleScreen]（`maxLog` 仍取 [Grid.MAX_LOG_DEFAULT]，MAXLOG 语义不动）：
     *    `ConsoleFeed.swapScreen` 的前提是空屏 —— 带着旧内容的屏会被当成「快照变长」再追加一遍；
     * 3. `feed.swapScreen(fresh)`：换屏 + 清适配缓存 / PRINTN 认领尾巴 / 发布水位 / 内联锚点；
     * 4. [screen] 指到新屏 —— [consoleScreen] / 命中 / 排障读数从此读新屏；
     * 5. [syncFeedLayout]：把新度量推给展平器（参数变了它内部会 force 一次）；
     * 6. `feed.force()`：兜底重放这一版快照（新屏日志是空的 ⇒ 整屏标脏）；
     * 7. [invalidate]：下一帧重画。
     *
     * **不自动 force 的替代物就是这里的第 6 步**：`swapScreen` 只做「换 + 清」
     * （见 [ConsoleFeed.swapScreen] 的 KDoc），重放责任在调用方。
     *
     * ## 宽度是前置条件，不是请求
     *
     * `columns` 必须**已经**被核心 `sink.resize(columns)` 接受。本类的绘制、折行、命中三套
     * 坐标系都建立在「sink 宽 == screen 宽」这条不变量上（见 [ConsoleFeed] 的类文档）；
     * 两套宽度并存时列号必漂，所以不一致一律抛 [IllegalStateException] —— 那是调用方漏了
     * resize 的编程错误，静默降级（例如只换一半）只会把错位留到画面上。
     *
     * ## 重入
     *
     * 换屏后 `screen.clientWidth == columns == sink.clientWidth`，下一次 [rebuildScreen] 的
     * 触发判据（[LiveReflowPlan.viewportNeedsReflow]）自然落空 ⇒ 回到既有的 rows-only
     * （[resizeFeedScreen]）路径，不会自激回调。
     *
     * ## 线程
     *
     * **只在 UI / 渲染线程调用**（[onDraw] / [onSizeChanged] / 宿主的视口回调同一线程）：
     * 本方法写 [screen]，并动 [ConsoleFeed] 的发布水位与内联锚点表，后者都不是跨线程结构。
     *
     * ## 返回 false 的两种情形（都是零状态变化，契约 §1 对 false 的定义）
     *
     * - 开关关闭（[liveReflow] = false）：本方法既不换屏也不碰任何字段；
     * - 没挂 feed（演示路径 [submitSnapshot]）：没有可换的桥。
     *
     * @param columns 目标列数（半角格），必须等于核心 sink **当前**的 `clientWidth`
     * @param rows 目标可见行数（= 触发那一次 [rebuildScreen] 解出的 `layout.rows`）
     * @return 换了屏返回 true
     * @throws IllegalStateException `columns` 与 `sink.clientWidth` 不一致（核心还没 resize）
     */
    fun reflowAttachedFeed(columns: Int, rows: Int): Boolean {
        val attached = feed
        // 判据本体在纯 JVM 的 [LiveReflowPlan.swapStep]（本模块没有 Robolectric，View 实例化不了，
        // 分支语义只能抽到那里单测）。三支的含义见该对象 KDoc。
        //
        // 「核心是否已按新列数重排」只能读 [ConsoleFeed.sinkClientWidth]。契约字面写的是
        // `feed.sink.clientWidth`，而 `:render-canvas` 的编译面看不见 `:core-vm` 的
        // `LineBufferConsoleSink`（`:render` 对 core-vm 是 implementation 依赖），所以
        // `:render` 为此单独开了这个纯 `Int` 只读口。
        //
        // **不能**退回 `feed.screen.clientWidth`：宿主链是 `session.resizeConsole(新列)`
        // （核心已经新宽）→ 本方法（屏还挂旧宽），交接窗口里那两支**必然不等**——读屏宽会把
        // 「已经重排」判成「宽度不符」，真机转屏/分屏当场抛 IllegalStateException（2026-11-04
        // 组长验收抓到，判据由 ConsoleFeedReflowTest 的交接窗口用例钉住）。
        val sinkWidth = attached?.sinkClientWidth
        when (LiveReflowPlan.swapStep(liveReflow, sinkWidth, columns)) {
            LiveReflowPlan.Step.Skip -> return false
            LiveReflowPlan.Step.WidthMismatch -> throw IllegalStateException(
                "reflowAttachedFeed 要求核心先 resize：columns=$columns，" +
                    "sink.clientWidth=$sinkWidth",
            )
            LiveReflowPlan.Step.Swap -> Unit
        }
        val target = attached ?: return false
        applyMetrics(resolveFor(width, height).metrics)
        // 换屏必须带走同一只尺：折行/列区间与屏上推进同源，否则 SETFONT 段重新错位。
        val fresh = ConsoleScreen(
            columns,
            Grid.MAX_LOG_DEFAULT,
            rows,
            fontWidth = fontDisplayWidth,
        )
        // fresh 是刚 new 出来的实例 ⇒ swapScreen 恒返回 true；仍按返回值收口，
        // 免得将来 swapScreen 的语义变成「同一只屏早退」时这里把旧屏当成新屏写回 [screen]。
        if (!target.swapScreen(fresh)) return false
        screen = fresh
        syncFeedLayout()
        target.force()
        invalidate()
        return true
    }

    /** 摘掉生产桥（装配失败或退回演示路径时用），之后重新走 [submitSnapshot]。 */
    fun detachFeed() {
        feed = null
        // 动画重绘表随会话一起停：会话没了还按 SETANIMETIMER 的间隔空转重画，
        // 等于失败屏/演示屏上白烧一颗核。重新装配时 attachConsoleFeed 会再挂上。
        attachAnimeRepaint(null)
        invalidate()
    }

    /** 当前装配体（尺寸变化时会整只重建；调用方不要在外部改动它的视口）。 */
    fun consoleScreen(): ConsoleScreen? = screen

    /**
     * 当前逻辑列数（半角格）：有装配体时与它同源，否则按**可绘制**宽与字号现算。
     *
     * 装配体一旦存在，`GameSession.consoleWidth` 必须取这个值，保证 `ConsoleFeed`
     * 的「sink 宽 == screen 宽」前置条件成立。
     *
     * 「可绘制」而不是「客户区」：见 [shapeShiftPx] 的 `Config.cs:461-466` 推导 ——
     * M10-GFX 之前这里直接除客户区宽，PC 基准 199 格在真机上会多算出 1 格
     * （钉桩的第三条读数，`core/host/HostContractAuditTest` 的控制台尺寸用例）。
     */
    fun consoleColumns(): Int {
        screen?.let { return it.clientWidth }
        return floor(drawableWidthPx() / halfCellPx).toInt().coerceAtLeast(1)
    }

    /**
     * 排障读数（调试通道 `view` 命令）：当前生效的度量与页行数。
     *
     * 覆盖版面的全部判据：可见行数、列数、生效字号/行高、配置字号、列数守护。
     */
    fun debugMetrics(): String {
        val console = feed?.screen ?: screen
        // 读**实时**视口行数：`ConsoleScreen.heightLines` 是构造期值，`resizeFeedScreen`
        // 改的是 `viewport.heightLines`；排障时要看后者。
        val rows = console?.viewport?.heightLines ?: 0
        val logLines = console?.log?.size ?: 0
        val band = feed?.bandDebug()?.let { " $it" } ?: ""
        return "rows=$rows cols=${console?.clientWidth ?: consoleColumns()} " +
            "font=${fontPixelSize.toInt()} lineH=${lineHeightPx.toInt()} " +
            "configFont=$sourceFontPixelSize configLineH=$sourceLineHeight " +
            "guardCols=${if (columnGuardEnabled) columns else 0} logLines=$logLines " +
            "viewW=$width viewH=$height" + band
    }

    /**
     * 排障读数（调试通道 `hits`）：把**本帧的行/列几何**逐行导出，与截图对拍用。
     *
     * 为什么单开一条命令：`chips` 只能证明「这一屏有这些按钮」，证明不了
     * 「画在第几行」与「点第几行」是同一行 —— 而真机「按钮点不动」的账恰好全在这条缝里
     * （2026-10-26 那次的根因就是行换算多减了一排，`CellHitRow.kt` 有完整记录）。
     * 本命令每行给出：命中行号、快照行号、行顶 y（与 `tap x,y` 同一坐标系）、行高，
     * 以及落在该行的按钮列区间与值；没有按钮的行也照样列出来，一眼能看出「这一行是空的」。
     *
     * 行号一律走 [hitRowForCell]（唯一换算口径，见 `render/view/CellHitRow.kt`），
     * 本命令**不另算一份** —— 否则排障工具自己就会成为第二份口径。
     */
    fun debugHits(): String {
        val console = feed?.screen ?: screen ?: return "（未装载）"
        val layout = rowLayout(console, console.list.render())
        val extraRows = extraRowsAbove(console)
        val targets = console.buttons.all()
        val sb = StringBuilder()
        sb.append("cols=").append(windowColumns())
            .append(" clientCols=").append(console.clientWidth)
            .append(" halfCell=").append(halfCellPx).append("px")
            .append(" viewW=").append(width).append(" viewH=").append(height)
            .append(" padL=").append(paddingLeft).append(" padT=").append(paddingTop)
            .append(" hitRowBase=").append(console.hitRowBase)
            .append(" visibleRowBase=").append(console.visibleRowBase)
            .append(" shift=").append(console.hitRowBase - console.visibleRowBase)
            .append(" hitRows=").append(console.hitRows().size)
            .append(" visibleRows=").append(console.visibleRows().size)
            // 行表恒按窗口行数建行 ⇒ 没有 minRow 这一项了：windowRows 就是表长，
            // snapRow（= 绘制口径行号 = cmd.row）恒等于表的行下标。
            .append(" windowRows=").append(layout.tops.size)
            .append(" extraRows=").append(extraRows)
            .append(" targets=").append(targets.size)
        var i = 0
        while (i < layout.tops.size) {
            val hitRow = hitRowForCell(0, i, extraRows)
            sb.append("\nrow=").append(hitRow)
                .append(" snapRow=").append(i)
                .append(" y=").append(layout.tops[i].toInt())
                .append(" h=").append(layout.heights[i].toInt())
            var any = false
            for (t in targets) {
                if (t.row != hitRow) continue
                any = true
                sb.append("\n    col=[").append(t.colStart).append(',').append(t.colEnd)
                    .append(") value=").append(t.value)
            }
            if (!any) sb.append(" （无按钮）")
            i++
        }
        return sb.toString()
    }

    /** 行高（像素）：触摸坐标 → 行号的换算单位。 */
    fun lineHeightPx(): Float = lineHeightPx

    /**
     * 半角格宽（像素）：触摸横坐标 → 列号的换算单位。
     *
     * 与 [tap] / [setPointer] 内部用的 `halfCellPx` 是同一个字段（同源于
     * `CellMetrics.cellPixelWidth`），装配层算「双击位置邻域」的列号必须走这里，
     * 否则会与命中侧差一个格宽（见 `TouchGestureController.doubleTapColumnSlop`）。
     */
    fun cellWidthPx(): Float = halfCellPx

    /**
     * 绘制窗口比命中窗口多取的最上面一排数（**行换算的唯一额外余量**）。
     *
     * 口径不变：`ConsoleScreen.hitRowBase - visibleRowBase` 夹进
     * `[0, ConsoleScreen.EXTRA_ABOVE_ROWS]`（`DRAW_EXTRA_ABOVE` vs `HIT_EXTRA_ABOVE`，
     * `adapter/ConsoleAdapter.kt:272-285`）。行表改成按窗口建行后，「快照行 = minRow + idx」
     * 那层平移消失（minRow 恒 0），只剩这一排要减，换算入口仍是 [hitRowForCell]。
     */
    private fun extraRowsAbove(console: ConsoleScreen): Int =
        (console.hitRowBase - console.visibleRowBase).coerceIn(0, ConsoleScreen.EXTRA_ABOVE_ROWS)

    /**
     * 逐行纵布局（**标题图按屏高缩放** 的唯一落点）：算式本体在纯 JVM 的 [RowLayout]，
     * 本方法只负责把 View 的度量与内容区交给它。
     *
     * 为什么行表**恒按窗口行数**（`console.visibleRows().size`）而不是按「有没有绘制命令」：
     * 原版逐行 `num += Config.LineHeight`（`EmueraConsole.cs:1852-1856`）与该行有无内容无关，
     * 而按命令极值建行会把脚本预留的纯 `<br>` 空行整排丢掉 ⇒ 立绘/动图被底对齐贴死在
     * `contentBottom`、主体压在可视区下方被裁（玩家报的「动图落在屏的底边以下」）。
     * 因果链与逐条行号见 `render/view/RowLayout.kt` 的类文档；同一件事在纯 JVM 合成侧的
     * 算式是 [com.eratw.port.render.view.ScreenComposer.rowTopPx]，无压缩时两张表逐值相同（钉在 `RowLayoutTest`）。
     *
     * 命令源由调用方给（**帧路复用同一份展平结果**，命中路自己展平一次）：旧实现每帧要展平
     * 两遍 —— 旧 `rowMetrics` 里一次 `console.list.render()`、[drawScreen] 里再一次
     * `renderInto(frameCmds)`，用的是同一个 `flattenInto`，产出逐项相同。本方法与
     * [drawScreen] 的绘制循环吃同一份 [frameCmds]；它只读 `cmds` 的 `row` / `graphic` /
     * `graphic.heightPx`，与命令顺序无关（逐行属性按行下标归并），换成同一份列表内容 ⇒ 结果逐值相同。
     *
     * 命中路（[hitOnCurrentView] / [setPointer] / [debugHits]）是低频事件、不占帧预算，
     * 各自 `render()` 一次，行为（含分配）与旧实现一致。
     *
     * 文字行多到放不下时（普通游戏画面）total > 可用高度，起点为负 ⇒ 顶部按 [drawScreen]
     * 的 clipRect 裁掉，与底对齐的原版逐像素一致。命中同样吃这份表：行→y 只有这一个来源，
     * 「画在哪 = 点哪里」。
     */
    private fun rowLayout(console: ConsoleScreen, cmds: List<DrawCmd>): RowLayout.Plan =
        RowLayout.plan(
            cmds = cmds,
            // 行数取**保留列表的行数**而不是 `visibleRows().size`：`DrawCmd.row` 是保留列表
            // 的下标（[DisplayList.renderInto] 的口径），只有两者恒等时按可见行数建表才安全。
            // 今天恒等（`resizeFeedScreen` 先 `ensureCapacity(rows + EXTRA_ABOVE_ROWS)` 再设
            // `viewport.heightLines`），但 `DisplayList.submit` 的 `retainTail` 会在超容量时
            // **从头部丢行**、使清单整体前移 —— 那时按可见行数建表会让每一行错位一整段。
            // 按保留行数建表则在任何情况下都与命令下标同坐标系（丢头行时画面只画得下保留的那段，
            // 与旧实现「只给有命令的行排位」同象）。
            windowRows = console.list.rowCount,
            contentTop = paddingTop.toFloat(),
            contentBottom = (height - paddingBottom).toFloat(),
            lineHeightPx = lineHeightPx,
            minArtRowHeightPx = MIN_ART_ROW_HEIGHT,
        )

    /**
     * 本条命令的图形段是否**越出自己那一行** —— 溢出部件最后铺墨的判式（缺陷 B）。
     *
     * 算式本体在纯 JVM 的 [GraphicGeometry.escapesLine]（原版
     * `ConsoleButtonString.cs:165-185` 的 `FilterEscaped`，判式 `:171`，几何出处见该函数
     * KDoc 的三支行号），与 uipreview 的 `InkPainter` 第 3 趟共用**同一份**判式。
     *
     * 文本段恒 false：原版文本件的纵盒是 `Top = 0`、`Bottom = Config.FontSize`
     * （`AConsoleDisplayNode.cs:29,31`），正常配置（`FontSize <= LineHeight`）下永不溢出 ⇒
     * 第一趟照旧把它们按行升序铺完。
     */
    private fun escapesLine(cmd: DrawCmd): Boolean {
        val g = cmd.graphic ?: return false
        return GraphicGeometry.escapesLine(g, fontPixelSize.toInt(), lineHeightPx)
    }

    /**
     * 屏幕坐标 → 按钮命中，返回喂给 VM 的提交文本（未命中返回 null）。
     *
     * 绘制口径与命中口径差一排（`ConsoleAdapter.DRAW_EXTRA_ABOVE` vs `HIT_EXTRA_ABOVE`）：
     * [rowLayout] 的行表**恒按窗口行数**建行（[ConsoleScreen.visibleRows] 的下标），
     * 所以「快照行 = idx」（旧实现的 `minRow + idx` 那层平移随缺陷 A 的修法一起消失）；
     * 再按 `hitRowBase - visibleRowBase` **只减一次**（[hitRowForCell]，第一参恒传 0）。
     * 旧实现减了两次，点击整体落到手指上一行，
     * 上一行无按钮时表现为完全没反应（2026-10-26 真机回归）。
     *
     * 纵向换算的起点是内容区顶（[paddingTop]，与 [drawScreen] 的底对齐行表同源）：
     * 行表里可能有一排被裁在最上沿、只露出一部分的溢出行，它落在命中窗口之外
     * （换算结果是负行号），点击那里不命中任何按钮。
     *
     * 列号**夹到窗口宽**（M10-GFX 对钉桩③的处理）：命中区间的右沿可以越过
     * `clientWidth`（= 原版 `DrawableWidth`，`ConsoleButtonString.cs:307-315` 只加 `PointX`
     * 不夹宽度），但**点得到**的范围受窗口客户区宽限制 —— 原版的 `col` 来自
     * `MouseEventArgs` 的像素坐标，天然不可能超出 `WindowX`（spec: `EmueraConsole.cs:2328-2331`
     * 与 `:2358-2361` 全程用控件内坐标，没有任何「越界命中」入口）。所以这里夹的是
     * **窗口宽**（[windowColumns]）而不是可绘制宽：差的那 `shift` 像素（PC 上 3px）
     * 仍然可点，与原版逐值一致；而 `render/button/ButtonLayer` 的对外契约一个字都没动
     * （它仍按原始区间判命中，纯 JVM 侧的读数不变）。
     *
     * **图段的「列↔像素」映射与绘制分发一致性**：本方法把 `x` 折成屏幕列 `col` 后直接交给
     * `ButtonLayer.hit`，而 `ButtonLayer` 对图段取的宽度是 `graphic.widthCells`（GFX-02 命中侧，
     * `ConsoleImagePart.cs:41` 的字符跨度 0 ≠ 像素盒宽）——与本类的绘制落点 drawCmd →
     * drawGraphic 用的 `cmd.colStart * halfCellPx` 同源：二者都建立在
     * `displaylist/DisplayList.prepare`（`:496` 对图段同样读 `graphic.widthCells`）铺出的列游标上。
     * 于是「`<button>` 里的图落在哪几列」与「点这几列算不算命中该按钮」逐格相等，
     * 不需要在绘制侧另开一份宽度或第二个纵原点（[rowLayout] 的行顶表是唯一的
     * 底对齐纵源，绘制与命中都查它）。

     * ## 贴底半边（M11-B DEV-01）
     *
     * 原版 MouseDown 在命中判定之前先取 `flag = Value != Maximum`（`MainWindow.cs:799`）
     * 与命中目标 `selectedString`（`:800`），随后未贴底且左/右键就 `Value = Maximum`
     * 并强刷（`:801-805`）—— **未命中（含点空白/余量）也回贴底**，且命中用的是
     * 贴底前的视图。本方法按同一顺序：先在当前视图上算命中（[hitOnCurrentView]），
     * 再调 `Viewport.snapToBottomForTap`（未贴底才动）；真动了就 `ConsoleScreen.refresh()`
     * 重算取行窗口与命中表，并 [requestRedraw]（滚动位置是类内状态变化，revision
     * 看不见，必须显式置脏）。回车提交归输入路径，本方法不碰。
     * 原版的 `IsWaitingPrimitive` 分支（`MainWindow.cs:790-797`）不贴底、`IsInProcess`
     * 整个不响应：这两个状态闸都在装配层（本 View 不读输入状态），装配层若要在
     * 原语等待期保留原版口径，应把那一路走 `mappingColor` 而不是本方法。
     *
     * ## 返回值（M11-B DEV-01 收尾，C4）
     *
     * 返回 [TapOutcome] 而不是裸文本：调用方要复刻 `MainWindow.cs:806` 块外的 `!flag`
     * 门（`:808`），而「这一击是否刚回贴底」只有本方法知道（`Viewport.snapToBottomForTap`
     * 的返回值在内部被消费掉了）。贴底无条件发生 —— 命中与否都一样，`MainWindow.cs:801-805`
     * 在命中判定之前、也不受任何 early return 影响。
     */
    fun tap(x: Float, y: Float, acceptance: ButtonAcceptance = ButtonAcceptance.ANY): TapOutcome {
        val console = feed?.screen ?: screen ?: return TapOutcome(hit = null, snappedToBottom = false)
        // 一次点击（抬起）就是按下态的终点：先清掉高亮，未命中也一样（高亮不残留）。
        // 清除走 [clearPointer] 的置脏口；点击回环本身仍由调用方拿返回值去提交。
        if (pointerDown) clearPointer()
        // 原版顺序（MainWindow.cs:800 在 :803 之前）：命中目标先按贴底前的视图取好。
        val submitted = hitOnCurrentView(console, x, y, acceptance)
        // MainWindow.cs:799-805 的贴底半边：未贴底时任何点击（含未命中）都回最新行。
        val snapped = console.viewport.snapToBottomForTap()
        if (snapped) {
            console.refresh()
            requestRedraw()
        }
        return TapOutcome(hit = submitted, snappedToBottom = snapped)
    }

    /**
     * 键入提交路的贴底半边：PC 上任何非原语/宏/翻页键在提交前都无条件
     * `Value = Maximum` 并强刷（`MainWindow.cs:1402-1406`，回车提交在 `:1407-1414`）。
     * 手机的「输入条提交按钮 / 输入法回车」没有 WinForms 的 KeyDown 可挂，
     * `InputSession.onTextChanged` 又按波次契约未接线（`InputBarState` 类文档
     * 「不自动提交」），所以这条半边由提交入口（`MainActivity.submitInput`）补，
     * 选项按钮提交路（原版 `:799-805` 的点击语义）同走这里。
     * 未贴底时归零、重算窗口并重绘；已贴底或无屏返回 false（与 [snapToBottomForTap] 同构）。
     */
    fun snapToBottomForSubmit(): Boolean {
        val console = feed?.screen ?: screen ?: return false
        if (!console.viewport.snapToBottomForTap()) {
            return false
        }
        console.refresh()
        requestRedraw()
        return true
    }

    /**
     * tap 的命中半段（**贴底前的视图**）：屏幕坐标 → 行列 → `ConsoleScreen.click`。
     * 与 [tap] 拆开是为了让贴底无论命中与否都发生（原版 `MainWindow.cs:801-805`
     * 在命中判定之前执行、不因 early return 被跳过）；点在余量/空白/窗口外返回 null。
     * 纵横换算的口径见 [tap] 的 KDoc，与 [drawScreen] 同源。
     *
     * **div 像素命中优先**（CONTRACT-DIV-HIT2）：div 子行按钮不在行列表里，原版走
     * `ConsoleDivPart.TestChildHitbox`（CDP :137-172）的像素矩形；[ConsoleScreen.divHitAt]
     * 已把原版扫描序（负 depth → 正文行 → depth 0 → 正 depth，`EmueraConsole.cs:2343-2434`）
     * 编码好，**非空即 div 赢**，按原点击派发口径走 `selectedString`（与格路径同一个
     * 类型闸：INT 等待期字符串按钮不给值）。返回 null 才落回既有的「像素 → 格 → [ConsoleScreen.click]」
     * 老路（正文行按钮由 [ConsoleScreen.divHitAt] 的优先级判为赢家，与格路径同目标）。
     */
    private fun hitOnCurrentView(console: ConsoleScreen, x: Float, y: Float, acceptance: ButtonAcceptance): String? {
        // CBG 按钮（取色图）优先：原版 `MouseDown` 在 `IsWaitingPrimitive` 时先取
        // `cbgButtonMap` 的颜色（`EmueraConsole.cs:1309-1320`），值走 `InputResult5` 的独立
        // 投喂口，与文本/div 命中不是同一条通道。回调未接线时回落旧路径（不改变既有行为）。
        val onCbg = onCbgButtonValue
        if (onCbg != null) {
            cbgButtonHitAt(x, y)?.let { hit ->
                onCbg(hit.value)
                return null
            }
        }
        val div = console.divHitAt(clientX(x), clientY(y))
        if (div != null) {
            // 命中层的 DivHit 没有「行下标×列」这组坐标（像素条目），行号塞 -1 只作占位；
            // `selectedString` 只用 value + acceptance，不读几何（见其实现）。
            return console.buttons.selectedString(
                HitTarget(-1, div.colStart, div.colEnd, div.value, div.label),
                acceptance,
            )
        }
        val hit = cellHitAt(console, x, y) ?: return null
        // 直接命中走行内矩形口径（纵门 + 压缩行 k）；溢出图形的锚行回退保留旧口径 ——
        // 那一路的 tap 点不在锚行带内，纵门由 GraphicTapAnchor 在解析锚行时已经判过。
        return if (hit.gated) {
            console.clickInline(
                hit.row, hit.col, hit.yInRowPx, fontPixelSize.toInt(), acceptance,
                hit.rowHeightPx, lineHeightPx, hit.pureArtRow,
            )
        } else {
            console.click(hit.row, hit.col, acceptance)
        }
    }

    /**
     * 屏幕坐标 → 命中口径的 `(行, 列)`；点在余量/窗口外返回 null。
     *
     * [hitOnCurrentView] 与 [tooltipAt] 共用这一份换算，保证「点得中」与
     * 「长按查得到」永远落在同一格。
     */
    private fun cellAt(console: ConsoleScreen, x: Float, y: Float): Pair<Int, Int>? =
        cellHitAt(console, x, y)?.let { it.row to it.col }

    /**
     * 客户区视图坐标 → **CBG 按钮命中**（取色图取色），null = 没有 CBG 按钮 / 未取到。
     *
     * 纯几何在 [CbgDrawOrder.hitCbgButton]（与 headless 同一份函数）；本方法只负责
     * 「把图纸取出来、按像素读一次色」：取色图像素由 `ImageFrameSource.frameByGid`
     * 从 core 的 `GraphicsPool` 现读（platform 只查不建，`Bitmap` 走既有 LRU/预算）。
     *
     * 坐标口径：取色图与画布**底对齐**（`sampleY = py - 画布高 + 图高`），由
     * [CbgDrawOrder.buttonMapSamplePoint] 逐式对应原版 `EmueraConsole.cs:1306-1313`。
     */
    private fun cbgButtonHitAt(x: Float, y: Float): CbgDrawOrder.CbgButtonHit? {
        // 原版 `MouseDown` 的 `IsWaitingPrimitive` early-return：非 INPUTMOUSEKEY 等待期
        // 连取色都不发生（`EmueraConsole.cs:1300-1305`）。不给这道闸，普通 INPUT 屏上
        // 点到 CBG 按钮区域就会吞掉本该给文本/div 的点击。
        if (cbgButtonArmed?.invoke() != true) return null
        val layers = graphicsLayer?.cbgLayers ?: return null
        if (layers.isEmpty()) return null
        // 取色图 gid 是控制台级单值，core 把它重复写进每条图层（见 DTO 的 KDoc）。
        val mapGid = layers[0].buttonMapGid
        if (mapGid < 0) return null
        val source = frameSource ?: return null
        val mapFrame = source.frameByGid(mapGid) ?: return null
        val bmp = mapFrame.handle as? Bitmap ?: return null
        if (bmp.width <= 0 || bmp.height <= 0) return null
        return CbgDrawOrder.hitCbgButton(
            px = clientX(x),
            py = clientY(y),
            canvasHeightPx = clientHeightPx(),
            mapWidthPx = bmp.width,
            mapHeightPx = bmp.height,
            colorAt = { cx, cy -> bmp.getPixel(cx, cy) },
            layers = layers,
        )
    }

    /**
     * 一次命中换算的**全部产物**：命中口径行、列，以及该行内纵坐标与行几何（按行内
     * 矩形口径判命中时要用）。[gated] = 该行是**直接命中**（没走溢出图形的锚行回退）——
     * 只有直接命中才叠行内纵门：锚行回退的 tap 点本来就不在锚行带里，硬套纵门会把
     * 跨行立绘的点击判死（那一路的纵向几何由 [GraphicTapAnchor] 自己判过）。
     */
    private data class CellHit(
        val row: Int,
        val col: Int,
        val yInRowPx: Float,
        val rowHeightPx: Float,
        val pureArtRow: Boolean,
        val gated: Boolean,
    )

    /**
     * 屏幕坐标 → 命中产物（[CellHit]）。[cellAt] / [hitOnCurrentView] / [setPointer] 共用
     * 这一份换算，保证「点得中」「长按查得到」「按下高亮在哪」永远一致。
     *
     * 行内纵门（[CellHit.gated] + [CellHit.yInRowPx]）是 IMG-A4 / B1 的落地口：渲染层
     * `ButtonLayer.hitInline` 已有纵盒判式与压缩行 k，但此前**没有生产调用点**（只活在
     * 单测里）；点击派发改走 `ConsoleScreen.clickInline` 后，图上方的行内空白才真的
     * 点不中（原版 `EmueraConsole.cs:2368` 的 `num4 < Top || num4 > Bottom`）。
     */
    private fun cellHitAt(console: ConsoleScreen, x: Float, y: Float): CellHit? {
        val contentLeft = contentLeftPx()
        val contentTop = paddingTop.toFloat()
        if (x < contentLeft || y < contentTop) return null
        // 逐行纵布局与 drawScreen 同源（见 [rowLayout]）：图片行被压缩后，行号不能再用
        // 「(y - originY) / lineHeight」估，必须查同一张行顶表。
        val layout = rowLayout(console, console.list.render())
        val idx = layout.rowIndex(y)
        if (idx < 0) return null
        val col = floor((x - contentLeft) / halfCellPx).toInt()
        if (col >= windowColumns()) return null
        // 只做一次换算：idx 就是快照行（行表按窗口建行，不再有 minRow 平移），
        // 减去绘制窗口多取的 extraRows 就是命中行；直接命中为空时走溢出图形的
        // 锚行回退（与 setPointer 共用 [pointerCellRow]，CellHitRow 的教训：换算只许一份）。
        val hitRow = pointerCellRow(console, layout, idx, x, y)
        val direct = hitRowForCell(0, idx, extraRowsAbove(console))
        return CellHit(
            row = hitRow,
            col = col,
            yInRowPx = y - (layout.tops.getOrNull(idx) ?: contentTop),
            rowHeightPx = layout.heights.getOrNull(idx) ?: 0f,
            pureArtRow = layout.artOnly.getOrNull(idx) ?: true,
            gated = hitRow == direct,
        )
    }

    /**
     * 长按取按钮提示：该点的 `<button title='…'>` / `<nonbutton title='…'>` 原文
     * （null = 该点没有提示）。
     *
     * 原版 PC 用鼠标悬停取 `ConsoleButtonString.Title` 做 ToolTip
     * （spec: `EmueraConsole.cs:1891-1917`，`:1909` 把 `<br>` 换成换行）。安卓没有
     * hover，装配层在 `onLongPress` 里调本方法，把原文交给壳层显示。
     *
     * **像素优先**：先按内容区像素查 [ConsoleScreen.divTooltipAt]（div 子行的提示是像素
     * 矩形命中，`ConsoleDivPart.cs:137-172` 的 TestChildHitbox；`:159` 用节点的
     * `PointX/Top/Bottom` 判），取不到才回落 [cellAt] 的格路径 —— 格路径只用格中心当
     * 代表点（`ButtonLayer.tooltip` 的 KDoc），div 子行带与屏幕格线不对齐时（qol 卡
     * 28px 高 vs 行高 18px）整段会查空。两条路的坐标换算是同一套内容区原点
     * （[clientX]/[clientY] 就是减 padding），不会出现「点得中却查不到」。
     */
    fun tooltipAt(x: Float, y: Float): String? {
        val console = feed?.screen ?: screen ?: return null
        // 像素入口：命中层的像素矩形表（div 子行条目 + 正文行按钮/提示的同一优先级链，
        // ButtonLayer.tooltipAt 的 KDoc）。参数是**客户区像素**（内容区坐标）。
        val pixel = console.divTooltipAt(clientX(x), clientY(y))
        if (pixel != null) {
            return pixel
        }
        // 回落：像素入口取不到时走既有格路径，非 div 命中的老行为不由本次接线改变。
        val hit = cellHitAt(console, x, y) ?: return null
        // 与点击同一条行内纵判据：点不中的行内空白不该弹 title（原版 `:2368` 的 y 判式
        // 同样作用于提示扫描）。锚行回退行不叠此门（tap 点不在锚行带内）。
        if (hit.gated && inlineHitOrNull(console, hit) == null) return null
        return console.tooltip(hit.row, hit.col)
    }

    /**
     * [CellHit] → 行内矩形命中的 `HitTarget`（`null` = 该行内坐标上没有可命中节点）。
     *
     * 点击、按下高亮、长按提示、`MOUSEB()` 指向态**共用这一个判据**：四条路的几何必须
     * 完全同源，否则会出现「点不中却弹提示 / 高亮却点不响」这类无法解释的错位。
     */
    private fun inlineHitOrNull(console: ConsoleScreen, hit: CellHit): HitTarget? =
        console.hitInline(
            row = hit.row,
            col = hit.col,
            yInRowPx = hit.yInRowPx,
            fontSizePx = fontPixelSize.toInt(),
            rowHeightPx = hit.rowHeightPx,
            lineHeightPx = lineHeightPx,
            pureArtRow = hit.pureArtRow,
        )

    /**
     * 客户区（窗口内容区）的像素尺寸；对应原版 `MainPicBox.ClientSize` = (WindowX,
     * WindowY - LineHeight)（`MouseState` 的 `clientWidth/clientHeight` 与
     * `isPointerInClientArea()` 都用它，spec: `EmueraConsole.cs:2328-2331`、`:2358-2361`）。
     *
     * 宽取 [layoutWidthPx]（窗口宽窄于物理屏时**不是**物理内容宽），否则居中留白那段会被
     * `isPointerInClientArea()` 的半开区间 `0 until clientWidth` 当成客户区。未接线 ⇒
     * 物理内容宽（与改动前逐值相同）。
     */
    fun clientSizePx(): Pair<Int, Int> = layoutWidthPx() to clientHeightPx()

    /**
     * 视图坐标 → 客户区坐标（相对内容区左上角，可为负/越界，边界判定归 `MouseState`）。
     *
     * 横原点必须是 [contentLeftPx]（含居中偏移），与 div 覆盖/命中/绘制同一左沿；
     * 纵向仍只减 [paddingTop]（垂直顶对齐，D1 不引入纵向偏移）。
     */
    fun clientX(viewX: Float): Int = (viewX - contentLeftPx()).roundToInt()

    /** 视图坐标 → 客户区坐标（相对内容区左上角，纵向由 `MouseState.mouseY()` 再换算）。 */
    fun clientY(viewY: Float): Int = (viewY - paddingTop).roundToInt()

    /**
     * 客户区坐标 → 该格按钮（**不做** INT/STR 过滤）。
     *
     * 供 `MOUSEB()` 的指向态使用：原版 `MoveMouse` 取的是光标下的
     * `ConsoleButtonString`，与当时在等什么输入无关（spec: `EmueraConsole.cs:2328-2331`）。
     * 只在 UI 线程调用——[cellAt] 会读 `DisplayList`/`ButtonLayer`，它们不是跨线程结构。
     */
    fun buttonAtClient(clientX: Int, clientY: Int): HitTarget? {
        val console = feed?.screen ?: screen ?: return null
        val hit = cellHitAt(
            console,
            clientX + contentLeftPx(),
            clientY + paddingTop.toFloat(),
        ) ?: return null
        // MOUSEB() 与点击同判据：行内空白（图形节点盒以外）不指向该按钮。
        if (hit.gated) return inlineHitOrNull(console, hit)
        return console.hit(hit.row, hit.col)
    }

    /**
     * 触摸按下/抬起 → 行内按钮的按下态高亮（只记绘制标记，不消费事件、不产输入）。
     *
     * 为什么放在这里而不是等装配层接线：main@2d94f78e 的 `MainActivity.attachGestures`
     * 只把手势喂给 `TouchGestureController`（`tap` / 滚动 / 双击判定都在那里），**没有**调用
     * `ButtonLayer.setPointer`，所以按下态在 View 之外没有任何写点。本类在分发入口观测
     * `ACTION_DOWN` / `ACTION_UP` / `ACTION_CANCEL`，把它们换算后交给 [setPointer]；
     * 事件本身照原样交给 `super`（`setOnTouchListener` 的分发优先级不变，滚动/点击仍归装配层）。
     *
     * 只处理三支动作、**不跟 ACTION_MOVE**：按下态是「按下即亮、抬起即灭」的反馈，
     * 移动跟随会按每个触摸事件触发一次重绘（滚动/拖动时白烧帧）。装配层若将来想改成
     * 鼠标式「指着就高亮」，可以改调 [setPointer] 显式接线（与本入口二选一即可，
     * 同时开也只是同值重复置脏，不产生语义差）。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPointer(event.x, event.y, pressed = true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                setPointer(event.x, event.y, pressed = false)
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * 记录触摸按下/抬起（行内按钮的按下态高亮，纯绘制标记，不产输入）。
     *
     * 坐标口径与 [tap] 完全一致：横走半角格、纵走 [rowLayout] 给出的底对齐行顶表；
     * 换算出的行列是**命中口径**（`ButtonLayer` 的行下标 / 屏幕列），交给 `ButtonLayer`
     * 存起来 —— 原版同构，`ButtonIsSelected` / `ButtonIsPointing` 只做引用比较
     * （spec: `EmueraConsole.cs:830-838`）。
     *
     * **装配层接线点**：`MainActivity` 的触摸回调在 `ACTION_DOWN` 调
     * `setPointer(x, y, pressed = true, acceptance)`，在 `ACTION_UP` / `ACTION_CANCEL` 调
     * `setPointer(x, y, pressed = false, ...)` 或 [clearPointer]。当前 main 的
     * 装配侧已接线（2026-11-06 收口）：`MainActivity` 的触摸段在 `ACTION_DOWN` 调 [setPointer]`(pressed = true)`、`ACTION_UP`/`ACTION_CANCEL` 调 [clearPointer]`()`，按下/焦点高亮因此真的上屏。
     * 本方法总会 [invalidate]，按下/抬起立刻上屏 —— 但**不走** [requestRedraw]
     *（强制整幅，PERF 2026-11-03）：按下态只改按下行内 BUTTON 段的像素，
     * [rowSignature] 对它的判定与 [drawCmd] 逐字同源，普通文本帧只重栅格化那一行；
     * 含图形/图层的帧本来就恒整幅，两边都不需要整张签名表作废。
     */
    fun setPointer(x: Float, y: Float, pressed: Boolean, acceptance: ButtonAcceptance = ButtonAcceptance.ANY) {
        val console = feed?.screen ?: screen
        if (console == null) {
            resetPointerState(null)
            return
        }
        val contentLeft = contentLeftPx()
        val layout = rowLayout(console, console.list.render())
        val col = floor((x - contentLeft) / halfCellPx).toInt()
        val idx = if (x < contentLeft || col < 0 || col >= windowColumns()) -1 else layout.rowIndex(y)
        if (idx < 0) {
            // 指在空白/余量/窗口外：清掉旧按下态（否则上一次的高亮永远留着）。
            resetPointerState(console)
            invalidate()
            return
        }
        val row = pointerCellRow(console, layout, idx, x, y)
        // 按下高亮与点击必须同一判据：直接命中行上，行内纵门没盖住就不该亮（图上方的
        // 行内空白点亮却点不响是最难解释的那种不一致）。锚行回退行不做此门（同上）。
        if (row == hitRowForCell(0, idx, extraRowsAbove(console))) {
            val inlineHit = console.buttons.hitInline(
                row = row,
                col = col,
                yInRowPx = y - (layout.tops.getOrNull(idx) ?: paddingTop.toFloat()),
                fontSizePx = fontPixelSize.toInt(),
                rowHeightPx = layout.heights.getOrNull(idx) ?: 0f,
                lineHeightPx = lineHeightPx,
                pureArtRow = layout.artOnly.getOrNull(idx) ?: true,
            )
            if (inlineHit == null) {
                resetPointerState(console)
                invalidate()
                return
            }
        }
        pointerHitRow = row
        pointerHitCol = col
        pointerDown = pressed
        pointerAcceptance = acceptance
        // CBG 按钮按下态：绘制侧据此把 `buttonValue == 该值` 的层改画 `spriteNameB`
        // （`EmueraConsole.cs:1840-1843`）。取值口径与 [hitOnCurrentView] 同一条取色图换算。
        cbgPressedValue = if (pressed) cbgButtonHitAt(x, y)?.value else null
        console.buttons.setPointer(row, col, pressed, acceptance)
        // div 子行按钮的**像素**指针（CONTRACT-DIV-HIT2）：div 子行不在行列表里，
        // 没有「行下标 × 列」这组坐标，命中/焦点/按下走独立入口（客户区像素，
        // 与 [clientX]/[clientY] 同一套原点；即 drawScreen 的内容区坐标）。
        console.setDivPointer(clientX(x), clientY(y), pressed)
        invalidate()
    }

    /**
     * 指针/点击的公共换算尾段：命中口径行号 + 溢出图形的锚行回退。
     *
     * [cellAt]（tap / tooltip / MOUSEB 取色）与 [setPointer]（按下高亮）必须落在同一格，
     * 所以回退也只此一份：直接命中（`ButtonLayer.hit`，ANY 口径）为空时，按
     * [GraphicTapAnchor.resolveAnchorRow] 就近找盖住该点的溢出图形按钮、解析回锚行
     * （点击立绘无反应的修法；几何与 drawScreen 两趟铺墨同源，出处见该类文档）。
     * 直接命中存在时不回退 —— 类型筛选（acceptance）仍由 `ConsoleScreen.click` 收口，
     * 与原版「先类型闸后 escaped 支」的顺序等价。
     *
     * @param idx 点击点的绘制口径行（`RowLayout.Plan.rowIndex` 的产物，>= 0）
     */
    private fun pointerCellRow(
        console: ConsoleScreen,
        layout: RowLayout.Plan,
        idx: Int,
        x: Float,
        y: Float,
    ): Int {
        val col = floor((x - contentLeftPx()) / halfCellPx).toInt()
        val direct = hitRowForCell(0, idx, extraRowsAbove(console))
        if (console.buttons.hit(direct, col) != null) {
            return direct
        }
        val anchor = GraphicTapAnchor.resolveAnchorRow(
            rows = console.visibleRows(),
            tops = layout.tops,
            tapRow = idx,
            tapCol = col,
            tapY = y,
            fontSizePx = fontPixelSize.toInt(),
            // B1 行几何：锚行的 k 必须与 drawImage 用同一个 rowHeight/lineHeight/artOnly，
            // 否则「画出来的图」与「锚行回退判的图」在压缩行上会差一截。
            heights = layout.heights,
            artOnly = layout.artOnly,
            lineHeightPx = lineHeightPx,
        )
        return if (anchor >= 0) hitRowForCell(0, anchor, extraRowsAbove(console)) else direct
    }

    /** 清掉按下/焦点态（触摸取消、滚出按钮、会话重建时用）；无指针时也幂等。 */
    fun clearPointer() {
        resetPointerState(feed?.screen ?: screen)
        // 与 [setPointer] 同口径：按下态的像素面只覆盖按下行，invalidate 即可
        //（强制整幅的理由已在 [layoutFingerprint] 的 KDoc 说明）。
        invalidate()
    }

    /**
     * 泵一次生产侧（有新快照才失效）：重绘循环的每帧入口。
     *
     * `pump()` 是**发布动作**（发布后 revision 前移、脏区信息随之丢失），循环只该在
     * 真有未发布内容时才 invalidate —— 见 `ConsoleFeed.hasUnpublishedContent` 的 KDoc
     * （2026-10-22 真机收口：每帧 pump 把「发布」与「询问」混在一起）。
     */
    fun pumpAndInvalidateIfNeeded() {
        if (feed?.hasUnpublishedContent() == true) invalidate()
        // 字号不再随页面内容重算：版面只在尺寸变化 / 配置字号灌入时解算（[rebuildScreen]）。
    }

    /**
     * 置脏 + 失效：下一帧重画（烘焙后备图路径的唯一单线程失效口）。
     *
     * 类内状态变化（按下态、滚动、图形段变化）都走它；UI 线程以外的入口用
     * [notifyGraphicsChanged] 的跨线程版。
     */
    fun requestRedraw() {
        dbgRequestRedrawCount++
        forceRedraw.set(true)
        invalidate()
    }

    /**
     * 双份指针状态的复位：本类留的行列坐标 + `ButtonLayer` 里的标记一起清。
     * [console] 为 null（布局前）时只清本类那份。
     *
     * 两份命中表各清各的：格坐标指针（[ConsoleScreen.buttons]）与 div 的**像素**指针
     * （CONTRACT-DIV-HIT2 的 `setDivPointer`/`clearDivPointer`）互不相干。
     */
    private fun resetPointerState(console: ConsoleScreen?) {
        pointerHitRow = -1
        pointerHitCol = -1
        pointerDown = false
        pointerAcceptance = ButtonAcceptance.ANY
        cbgPressedValue = null
        if (console != null) {
            console.buttons.clearPointer()
            console.clearDivPointer()
        }
    }

    /**
     * 悬浮选项栏用的一枚候选。
     *
     * @property ordinal 数值按钮的序号（`[3]` 里的 3）；**字符串按钮（`exit` 等）为 null**
     * @property label 按钮上印的字（引擎侧 `HitTarget.label`，图形按钮为空串）
     * @property value 提交值：`Long`（数值）或 `String`（字符串按钮），与 `TextRun.buttonValue` 同源
     */
    data class OptionChip(val ordinal: Int?, val label: String, val value: Any) {
        /** 点这枚候选要提交给输入队列的文本：数值 = 十进制串，字符串 = 原串。 */
        val submitValue: String get() = value.toString()
    }

    /**
     * **旧路径（2026-10-25 起不再用于右侧栏）**：从可见行/文本快照里认选项。
     *
     * 右侧栏已改为消费引擎的 `LineBufferConsoleSink.currentOptions()` 快照
     * （`ConsoleSink.noteInputWait` 在输入等待入口冻结，只含当前页、按等待类型过滤）。
     * 本方法保留给历史调试/回归；新代码不要再用它 —— 它会把正文里的 `[N]` 和旧页
     * 按钮一起列出来，正是误识别的来源。
     *
     * 从**当前可见行**里认出脚本列出的可点选项（手机端「大按钮」的来源）。
     *
     * eratw 的选项分两种形态，都要认：
     * 1. **裸文本 + `INPUT`**：`PRINTFORML [0] 开始游戏` —— `ButtonLayer` 里可能有
     *    自动按钮（`[123]` 族），但这种行在手机上点不准；
     * 2. **HTML 按钮**：`HTML_PRINT "<button value='0'>[  0] START</button>"` ——
     *    标题页/模式选择页/存档页大量使用，且**渲染层当前不给它们建命中区**
     *    （真机实测：这类行 `DisplayLine.buttons` 为空，点行内文字无反应），
     *    所以选项栏是它们唯一的可点入口。
     *
     * 因此扫描顺序：先认 `<button value=N>…</button>`（去标签取标题），再兜
     * `[N]` / `[ N ]` / `[  N]`（`[  0]` 是 eratw 的补位写法）。序号只收 1~2 位数字，
     * 超过 99 的多半是正文里的年份/计数。只扫**最后 [scanRows] 行**：选项总在交互区
     * 末尾，扫全屏会把正文里引用旧选项的 `[1]` 一起抓进来。
     *
     * @param maxCount 最多交给界面的候选数（右侧竖栏一屏能放下的量级）
     */
    fun optionChips(maxCount: Int = 32, scanRows: Int = 40, maxLabelChars: Int = 24): List<OptionChip> {
        val console = feed?.screen ?: screen ?: return emptyList()
        // **引擎级枚举**（2026-10-24）：直接读按钮命中层（`ButtonLayer.all()`），
        // Long/String 两种值通吃 —— 本作大量非数字按钮（`exit`、`戻る` 等）靠旧的
        // 「文本正则认 `[N]`」永远认不出来，而且「只认最新一页」只能靠启发式。
        // 命中层的输入就是当前窗口的行（`ConsoleScreen.hitRows()`），天然只有当前页。
        val engine = OptionButtonScanner.scan(console, maxCount, maxLabelChars)
        if (engine.isNotEmpty()) {
            return engine.map { OptionChip(it.ordinal, it.label, it.value) }
        }
        // 兜底：命中层为空（首次刷新前 / 脚本把选项印成纯文本没有按钮语义）时，
        // 退回「自底向上扫最后 scanRows 行」的文本启发式。
        return textScanChips(console, maxCount, scanRows, maxLabelChars)
    }

    /** 兜底路径：文本快照启发式（仅在引擎命中层为空时使用）。 */
    private fun textScanChips(
        console: ConsoleScreen,
        maxCount: Int,
        scanRows: Int,
        maxLabelChars: Int,
    ): List<OptionChip> {
        val lines = console.snapshotText()
        if (lines.isEmpty()) return emptyList()
        val found = LinkedHashMap<Int, OptionChip>()
        var idx = lines.size - 1
        var scanned = 0
        while (idx >= 0 && scanned < scanRows && found.size < maxCount) {
            val line = lines[idx]
            if (found.isNotEmpty() && isOptionPageBoundary(line)) break
            parseOptionLine(line, maxLabelChars, found)
            idx--
            scanned++
        }
        return found.values.sortedBy { it.ordinal ?: Int.MAX_VALUE }.take(maxCount)
    }

    /** 选项页边界：整行只有分隔符（`━ ─ ═ = - _ ~ * ・ … ※`）或空白。 */
    private fun isOptionPageBoundary(raw: String): Boolean {
        val line = raw.trim()
        if (line.isEmpty()) return true
        if (line.length < 3) return false
        var symbols = 0
        for (ch in line) {
            if (ch in OPTION_BOUNDARY_CHARS) symbols++ else if (ch != ' ') return false
        }
        return symbols >= 3
    }


    /** 抽一行里的全部选项（HTML `<button>` 优先，再兜裸文本 `[N]`）。一行可放多个。 */
    private fun parseOptionLine(rawLine: String, maxLabelChars: Int, into: MutableMap<Int, OptionChip>) {
        // 全角归一：脚本里 `［３］`、`【３】`、`（３）` 和全角数字都出现过（负责人反馈「部分选项
        // 识别不到」），统一折成 ASCII 再走同一套扫描。
        val line = normalizeOptionLine(rawLine)
        for (m in HTML_BUTTON_RE.findAll(line)) {
            val ordinal = m.groupValues[1].toIntOrNull() ?: continue
            // 自底向上扫 ⇒ 后写入的是更靠下的（最新页）选项，直接覆盖旧页同名序号。
            into[ordinal] = OptionChip(ordinal, cleanOptionLabel(m.groupValues[2], maxLabelChars), ordinal.toLong())
        }

        var i = 0
        while (i < line.length) {
            val open = line.indexOf('[', i)
            if (open < 0) break
            val close = line.indexOf(']', open + 1)
            if (close < 0) break
            val digits = line.substring(open + 1, close).trim()
            if (digits.isEmpty() || digits.length > 2 || !digits.all { it in '0'..'9' }) {
                i = close + 1
                continue
            }
            val ordinal = digits.toInt()
            run {
                val nextOpen = line.indexOf('[', close + 1)
                val tail = line.substring(close + 1, if (nextOpen > 0) nextOpen else line.length)
                into[ordinal] = OptionChip(ordinal, cleanOptionLabel(tail, maxLabelChars), ordinal.toLong())
            }
            i = close + 1
        }
    }

    /** 去 HTML 标签/常见实体、去掉行首 `[N]` 前缀，留可读标题（选项栏按钮文案）。 */
    /** 全角 → 半角归一：括号、数字、冒号。选项行里这几种写法在各版脚本里都出现过。 */
    private fun normalizeOptionLine(raw: String): String {
        if (raw.isEmpty()) return raw
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '［', '【', '〔', '（' -> sb.append('[')
                '］', '】', '〕', '）' -> sb.append(']')
                in '０'..'９' -> sb.append('0' + (ch - '０'))
                '：' -> sb.append(':')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
    private fun cleanOptionLabel(raw: String, maxLabelChars: Int): String =
        raw.replace(HTML_TAG_RE, " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(ORDINAL_PREFIX_RE, "")
            .replace(WHITESPACE_RE, " ")
            .trim()
            .take(maxLabelChars)

    /**
     * 挂上图形通路（M10-GFX）：[layer] 是宿主图形 sink 写入的那份状态，
     * [source] 把显示列表里的资源名换成位图。两者都在 [onDraw] 里读，
     * 装配侧必须与 [ConsoleFeed] 用的 [GraphicsLayer] 是同一个实例。
     */
    fun attachGraphics(layer: GraphicsLayer, source: ImageFrameSource) {
        graphicsLayer = layer
        frameSource = source
        // 新会话/新图形通路：CBG 动画时钟与按下态都从零开始（旧图层的起点不能带过来，
        // 否则同名同 depth 的层会接着上一次会话的时间轴继续播）。
        cbgClock.reset()
        cbgPressedValue = null
        invalidate()
    }

    /** 摘掉图形通路（会话关闭 / 读档重建时用）。 */
    fun detachGraphics() {
        graphicsLayer = null
        frameSource = null
        invalidate()
    }

    /**
     * 解码就绪入口（本波 FEEL1-L1「资源就绪回调」的上屏半段；装配侧已接：`GameHost` 的 `setDecodeReadyListener(view::onGraphicResourceReady)`）。
     *
     * 调用方口径：唯一订阅口是 `SpriteBitmapSource.setDecodeReadyListener`（platform 模块），
     * 该通知在源侧**一律经主线程 Handler 投回 UI 线程**后才调本口（源类 KDoc 的投递纪律，
     * 并由 platform 侧源码面钉子钉住）—— 也就是说本口运行在 UI 线程，与 [onDraw] 同线程，
     * 读 [frameCmds] / [graphicsLayer] 没有竞态。
     *
     * ## 先闸后失效：为什么
     *
     * 源解完一张图只知道**资源名**，不知道它落在哪一行；本口用 [GraphicReadyGate] 问一句
     * 「这名字还在不在上一帧画过的内联图段 / 背景层 / 图层里」，这是契约那句「只重画受影响的
     * 那一条」在本 View 既有失效体系下唯一可交付的含义：
     * - 不在屏（已滚出 / 已换代 / 会话已摘图形通路）⇒ **零失效**。含图形的屏上一次普通
     *   [invalidate] 就是一次整幅栅格化（planner「含图形/图层帧恒整幅」的既有口径，见
     *   [drawScreen] 扫描段 KDoc），让离屏的名字白烧一帧是净亏；
     * - 在屏 ⇒ 恰好一次普通 [invalidate]，与按下态同口径（[setPointer] 的 KDoc 记着为什么
     *   不用置脏），行级收敛仍由 planner 的行签名判据做。
     *
     * ## 明确不走的路
     *
     * [requestRedraw] / `forceRedraw` / `planner.notifySurfaceLost` / `bakeGuard.invalidate`：
     * 这四个都会把整张行签名表作废，把本轮刚收口的「按下态只重栅格化一行」白烧回去；就绪事件
     * 只新增一个事实「这个名字现在有像素了」。背景层 null→位图的翻转由
     * [BackgroundBakeGuard] 的帧签名判据（missing 位翻转）自行接住 —— 含背景的帧像素面本就恒整幅。
     *
     * 空名 / 图形通路未接或已摘（[frameSource] 为 null）⇒ 直接返回，不失效。
     */
    fun onGraphicResourceReady(resource: String) {
        if (resource.isEmpty() || frameSource == null) return
        val layer = graphicsLayer
        val onScreen = GraphicReadyGate.inDrawnCmds(frameCmds, resource) ||
            (
                layer != null &&
                    (
                        GraphicReadyGate.inBackgrounds(layer.background, resource) ||
                            GraphicReadyGate.inImageLayers(layer.imageLayers, resource)
                        )
                )
        if (!onScreen) return
        invalidate()
    }

    /**
     * 会话侧引用的**最终释放口**：只在 `Activity.onDestroy` 调，此后这一屏再也不会画。
     *
     * ## 与 [detachGraphics] 的分工（别合并）
     *
     * [detachGraphics] 是「换会话 / 退回失败屏 / 退出到标题」用的 —— 之后这一屏**还要继续画**
     * （失败屏、控制台尾部、重试按钮都长在上面），所以只摘 `graphicsLayer` 与 `frameSource`，
     * `feed` / `screen` / 离屏表面都留着。
     *
     * 本函数是「这一屏**再也不会画**」的那一次：把剩下三条重引用一起放掉。它们的分量不是
     * 同一个量级 —— `feed`（`ConsoleFeed` → `ConsoleScreen` → 全量行缓冲 + HTML 浮岛）
     * 与 `screenSurface`（整屏离屏 `Bitmap`）是**整局会话里最大的两块常驻**，而
     * `graphicsLayer` 那条 lambda 还强持 `GameSession`（`backgroundAdmissible`）。
     *
     * ## 为什么非要有这一步（2026-10-31 真机读数）
     *
     * 真机上「按返回键退出 → 再点图标进来」会让同一进程里出现**第二个** `MainActivity`：
     * `dumpsys meminfo -a` 读到 `Activities: 2 / Views: 26`（干净进程是 `1 / 15`），
     * 下一次装载起点 `load.baseline.high used=215MiB`（干净进程 15 MiB，阈值 150 MiB），
     * 于是 `GameHost` 的「上一局没让出堆」兜底闸把玩家拦在「启动失败：请结束本应用进程后
     * 重开」屏上 —— 而且**每次重试都还是 215/216 MiB**，不杀进程就永远进不去。
     *
     * 旧 Activity 实例为什么没被回收，是另一条线（未定性，见 BOARD）。但「旧壳还活着」
     * 不该等于「整局会话还活着」：这里把重引用切断之后，即便壳被钉住，钉住的也只是一棵
     * 空视图树（几十 KB），不是 215 MiB。
     *
     * 必须在 UI 线程调（[onDraw] 同线程，避免把正在画的 `screenSurface` 抽走）。
     *
     * ## 逐字段判性（W12 · AUD-h7-release 残余四字段，2026-11-01）
     *
     * **随会话释放**：`pending` —— 见函数体内注释（不只是滞留，还是跨会话陈旧快照）。
     *
     * **有意常驻（进程级字体度量缓存，与具体会话无关）**：
     * - [typefaces]（≤4 项）：族名 → `Typeface`，`createFromAsset` 解码一次即复用。清掉只是
     *   下次重建，收益 <10 KB 级；而它是**唯一**持有已解码 Typeface 的地方，清空会让下一局
     *   首帧重新解码字体（首屏可感）。
     * - [baselines]（≤4 项）：族名 → 基线偏移，与 `paint.textSize` 绑定。[applyMetrics]
     *   改字号时已整表作废，**不需要**会话销毁再清一遍。
     * - [metricsByFont]（≤4 桶）：字形度量，键含字号；同样由 [applyMetrics] 整表作废。
     *   清空等于下一局把每个字形重新量一遍（全屏数千字形），是明确的性能倒退。
     *
     * 三者键都是**字体族名/字号**，不含任何会话状态；留着不会钉住 `GameSession`、
     * `ConsoleScreen` 或任何位图。残余量级：4 + 4 + 4 个空表头（`metricsByFont` 的桶
     * 上限由字号变化次数决定，但每次 [applyMetrics] 都整表丢弃，稳态 ≤ 当次字号的桶）。
     */
    fun releaseSession() {
        // 动画表先停：若还有一拍在路上的 tick，它到点会 postInvalidateOnAnimation，
        // 之后这一屏按 SETANIMETIMER 的间隔（动态图 40 ms）被反复重画 —— 而且是**永远不会再画**
        // 的那一屏。provider 这条 lambda 还强持 SpriteBitmapSource（整份位图缓存），
        // 死会话不该把它钉在 View 上。
        attachAnimeRepaint(null)
        feed = null
        screen = null
        // 最后一次 submitSnapshot 的行列表：**必须清**（W12 · AUD-h7-release 残余）。
        // 两重后果 —— ① 滞留：这批 RenderLine 连同其字形/颜色引用被死 View 钉住；
        // ② 正确性：rebuildScreen 会用 `pending` 给**新建的** ConsoleScreen 补交首帧快照
        //    （见 :1445 `fresh.submitSnapshot(pending)`），旧会话的行会被画进新会话的屏，
        //    直到新会话自己再 submitSnapshot 覆盖为止。二者都不该跨会话存在。
        pending = emptyList()
        graphicsLayer = null
        frameSource = null
        // 首帧收尾回调强持壳层（Compose 状态）：会话销毁后不该再由死 View 攥着。
        onFirstContentFrame = null
        screenSurface = null
        screenSurfaceCanvas = null
        surfaceWidth = 0
        surfaceHeight = 0
        // 行位图缓存（预算 24 MB）与行位图池（预算 6 MB）同样是「这一屏的像素」：
        // 旧实现只放了 surface，这两块仍钉在死 View 上（本函数正是按「切断重引用」立的）。
        planner.notifySurfaceLost()
        rowCache.clear()
        rowBitmapPool.clear()
        rowPoolBytes = 0L
        // 背景烘焙位图（内容区大小）+ 各层帧句柄：与 [onDetachedFromWindow] 同一条配对纪律，
        // 丢位图必须同时作废脏判定，否则再附着时 needsRebake 判 false、背景永久不贴。
        backing = null
        backingCanvas = null
        bakeGuard.invalidate()
    }

    // ------------------------------------------------------------------
    // 内存压力收口（heap-budget 车道：本屏自有的 native 位图此前没有任何 trim 入口）
    // ------------------------------------------------------------------

    /**
     * 本屏持有的**位图账面**（字节）：整屏保留面 + 背景烘焙面 + 行栅格缓存 + 行位图池。
     *
     * ## 为什么要单列这一枚读数
     *
     * 端点报告里「native 驻留偏高」那一桶（峰值 286~361 MiB）只有平台侧三张 `LruCache`
     * 的读数（`SpriteBitmapSource.cacheBytes()`，预算 32 + 16 + 工作表缓存 192 MiB / 放开档 384 MiB）在册，
     * 本屏这四块从来没进过任何账面 —— 而它们的量级不是零头：
     * 整屏保留面与背景烘焙面各是 `宽 x 高 x 4`（真机 1080x2400 ⇒ 每张约 9.9 MiB），
     * 行栅格缓存预算 [ROW_CACHE_MAX_BYTES]（24 MiB）加行位图池 [ROW_POOL_MAX_BYTES]（6 MiB）：
     * 缓存侧合计 30 MiB，再加两张整屏面 ⇒ 稳态约 50 MiB 的 native 在这一枚读数之前**无人记账**。
     * 少了这枚读数，`onTrimMemory` 收完之后 `cacheBytes()` 归零、native 侧却纹丝不动，
     * 报告里就永远分不出「没收」与「收了但没落到 native 堆」。
     *
     * 字节口径与缓存自身一致：`Bitmap.allocationByteCount`（像素分配量）与
     * [RowRasterCache.bytes]（同一把尺的 `costOf`）。只读诊断，允许与绘制线程并发读到中间态。
     */
    fun viewBitmapCacheBytes(): Long {
        var total = rowCache.bytes + rowPoolBytes
        screenSurface?.let { if (!it.isRecycled) total += it.allocationByteCount.toLong() }
        backing?.let { if (!it.isRecycled) total += it.allocationByteCount.toLong() }
        return total
    }

    /**
     * 内存压力（`MainActivity.onTrimMemory` → `GameHost.trimGraphicsCaches`）时把本屏的
     * 可重建位图交出去，返回**交出去的字节数**（0 = 本来就没持有 / 不在 UI 线程）。
     *
     * ## 两档
     *
     * - `deep = false`（前台 RUNNING 系：玩家正看着这一屏）：只收行栅格缓存与行位图池。
     *   两者都是**性能面**——行烤图的内容只由该行的 `DrawCmd` 序列决定，重烤必得同一张图，
     *   而 [screenSurface] 上的像素一个字节都没动，所以这一档**不改画面、不触发整幅重绘**
     *   （失效判定看的是行内容签名，不是缓存命中，见 [rasterTextRow]）。
     * - `deep = true`（后台系 `UI_HIDDEN` / `BACKGROUND` / `MODERATE` / `COMPLETE`：这一屏
     *   不会立刻再画）：连整屏保留面与背景烘焙面一起交还，配方与 [onDetachedFromWindow]
     *   逐字相同 —— 丢位图必须同批 `planner.notifySurfaceLost()` 与 [bakeGuard.invalidate]，
     *   否则回前台那一帧既没有 surface 又判定「不用重烘」，屏上就是黑的。
     *   代价是回前台整幅重画一次（一帧），换来的是后台不再顶着约 20 MiB 的 native 被
     *   vivo 这类 `kill_heaviest_task=true` 的 ROM 当成最重任务先收掉。
     *
     * ## 只丢引用、绝不 `recycle()`
     *
     * 口径与 [ensureScreenSurface] 的注释同一条：贴图是异步上屏的，硬件渲染器可能还持有
     * 这一帧的引用，UI 线程当场 `recycle()` 会撞 `Canvas: trying to use a recycled bitmap`。
     * API 26 起位图像素在 native 堆上、由 `NativeAllocationRegistry` 随 Java 侧引用清空归还，
     * 所以这一刀的真实效果要等一次 GC 才落到 native 读数上 —— 因此调用侧把
     * `Debug.getNativeHeapAllocatedSize()` 一并钉进轨迹，收没收由读数说话，不由本函数自证。
     *
     * ## 线程
     *
     * **只在 UI 线程生效**（与 [onDraw] 同线程）：`Looper` 判定不过直接返回 0，不做跨线程摘引用
     * —— 那正是「正在烤的那一行被抽走位图」的形状。`onTrimMemory` 本来就在 UI 线程。
     */
    fun releaseMemoryCaches(deep: Boolean): Long {
        if (Looper.myLooper() != Looper.getMainLooper()) return 0L
        val before = viewBitmapCacheBytes()
        // 顺序有讲究：rowCache.clear() 会逐条走 onEvict ⇒ 至多 ROW_POOL_MAX_BYTES 落进池子，
        // 所以紧接着把池一起清掉，否则「收缓存」只把字节从缓存挪进了池子。
        rowCache.clear()
        rowBitmapPool.clear()
        rowPoolBytes = 0L
        if (deep) {
            planner.notifySurfaceLost()
            screenSurfaceCanvas = null
            screenSurface = null
            surfaceWidth = 0
            surfaceHeight = 0
            backing = null
            backingCanvas = null
            bakeGuard.invalidate()
        }
        val freed = before - viewBitmapCacheBytes()
        return if (freed > 0L) freed else 0L
    }

    /**
     * 滚动 [rows] 行：正数 = 看更早的行（上滚），负数 = 回到底部方向（下滚）。
     *
     * 绑定层交来的步数与**手指位移同号**（手指下移 = 正 = 内容跟着手指往下走，回看更早），
     * 换算只有 `platform/input/ScrollLineAccumulator` 类文档「口径」那一个来源，
     * 调用处不再各自取负（2026-11-03 方向回归的成因）。
     */
    fun scrollRows(rows: Int) {
        val console = feed?.screen ?: screen ?: return
        when {
            rows > 0 -> console.viewport.scrollUp(rows)
            rows < 0 -> console.viewport.scrollDown(-rows)
            else -> return
        }
        console.refresh()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildScreen(w, h)
    }

    // ------------------------------------------------------------------
    // 动画周期重绘（GFX-07 的宿主侧接线）
    // ------------------------------------------------------------------

    /**
     * 重绘间隔提供者（毫秒，`0` = 不排）。装配侧接
     * `SpriteBitmapSource.animeRepaintIntervalMs`，即 `SETANIMETIMER` 的池级读数。
     *
     * 为什么由宿主排而不是帧推进里排：原版 `setRedrawTimer`（`EmueraConsole.cs:967-980`）
     * 只决定「多久 `window.Refresh` 一次」，帧号是 `GetCurrentFrame` 每次现算的
     * （`SpriteAnime.cs:90-119`）—— 两者解耦。所以这里只需要「按时戳把屏再画一遍」，
     * 帧选谁由取图口按当前时钟自己算。
     */
    private var animeIntervalMs: (() -> Int)? = null

    /** 已排上但还没跑的一次 tick（防重复排；UI 线程读写）。 */
    private var animeTickPending = false

    /**
     * 排着的那一拍用的间隔。与 [animeTickPending] 一起构成「在路上的回调是不是当前间隔」
     * 的判据 —— 见 [planAnimeRepaint]：间隔变了必须撤旧重排，否则旧的长间隔会把新的短间隔
     * 挡到它到期（原版 `setRedrawTimer` 是当场改 `Timer.Interval`，EmueraConsole.cs:978-979）。
     */
    private var animeTickScheduledMs = 0

    private val animeTick = object : Runnable {
        override fun run() {
            animeTickPending = false
            // 不可见（切后台 / 被覆盖层挡住）时不空转：等下一次内容变化或重新可见再排。
            if (!isShown) return
            postInvalidateOnAnimation()
            scheduleAnimeTick()
        }
    }

    /**
     * 接上/摘掉动画重绘间隔提供者（`null` = 摘掉并停表）。
     *
     * 与 [attachGraphics] 分开是刻意的：图形通路可以在没有动画（`SETANIMETIMER 0`）的
     * 场景下独立工作，而这条线只在真的设了定时器时才有意义。
     */
    fun attachAnimeRepaint(provider: (() -> Int)?) {
        animeIntervalMs = provider
        if (provider == null) {
            removeCallbacks(animeTick)
            animeTickPending = false
            animeTickScheduledMs = 0
        } else {
            scheduleAnimeTick()
        }
    }

    /**
     * 排下一次重画：间隔 `<= 0` 就是关表（原版 `EmueraConsole.cs:971-975` 的
     * `<= 0 ⇒ timer 停`；`< 10 ⇒ 抬到 10` 由 [SpriteAnimeTimeline] 的纯函数做，
     * 这里只认它吐出来的结果）。
     *
     * 间隔**变了**要撤掉在路上的回调重排（[planAnimeRepaint]）：原版改值是当场
     * `Timer.Interval = tickcount; Enabled = true`（`EmueraConsole.cs:978-979`），而 EraTW
     * 平时压在 32627 ms 的「关」哨兵上、只在展示动态图时改 40 —— 不重排的话那条 32.6 秒的
     * 旧回调会把动态图整段展示窗口挡成一张静态图。
     */
    private fun scheduleAnimeTick() {
        val ms = animeIntervalMs?.invoke() ?: 0
        when (planAnimeRepaint(ms, animeTickPending, animeTickScheduledMs)) {
            AnimeRepaintPlan.Keep -> return
            AnimeRepaintPlan.Cancel -> {
                removeCallbacks(animeTick)
                animeTickPending = false
                animeTickScheduledMs = 0
            }
            AnimeRepaintPlan.Rearm -> {
                // 未排时 remove 是空操作；已排时撤的正是「间隔过期」的那一拍。
                removeCallbacks(animeTick)
                animeTickPending = true
                animeTickScheduledMs = ms
                postDelayed(animeTick, ms.toLong())
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animeTick)
        animeTickPending = false
        animeTickScheduledMs = 0
        // 保留面与行位图一起交还：离开窗口期间不该按住一整屏像素不放。
        // 再附着时 [ensureScreenSurface] 会重建，并因 notifySurfaceLost 走一帧整幅 ⇒ 画面自洽。
        planner.notifySurfaceLost()
        rowCache.clear()
        rowBitmapPool.clear()
        rowPoolBytes = 0L
        screenSurfaceCanvas = null
        screenSurface = null
        surfaceWidth = 0
        surfaceHeight = 0
        // 背景烘焙位图与 surface 同一分量级（内容区 w×h），上面那句「不该按住一整屏像素」
        // 对它同样成立；[bakeGuard] 的帧签名还强持各层背景位图的句柄。两者必须成对释放：
        // 只丢位图不作废 ⇒ 再附着时 needsRebake 判 false，[drawBackgrounds] 取不到位图就一直不贴。
        backing = null
        backingCanvas = null
        bakeGuard.invalidate()
        super.onDetachedFromWindow()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        // 系统栏 insets 在首次布局之后才到，尺寸可能不变而可绘制区变了；
        // halfCellPx 尚未初始化时（View 构造期若走到这里）直接跳过。
        if (halfCellPx > 0f && width > 0 && height > 0) {
            rebuildScreen(width, height)
        }
    }

    private fun rebuildScreen(w: Int, h: Int) {
        // 唯一解算：字号/行距是输入，列数/行数是输出；页面内容不参与（见 ScreenLayout.resolve）。
        val contentHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        val attached = feed
        if (attached != null) {
            // **纯几何刷新**（不碰字号/列宽）：`MainActivity` 声明了
            // `configChanges="orientation|screenSize|…"` ⇒ 旋转/分屏**不重建 Activity**，
            // 只经 onSizeChanged 走到本分支；而 feed 已挂时下面只改行数就 return。若不在这里
            // 重解一次版面几何，[resolvedLayoutWidthPx] / [resolvedContentInsetLeftPx] 会停在
            // 旧屏宽 —— drawScreen 的内容右沿已改成 `contentLeftPx() + layoutWidthPx()`（不再是
            // 活读 `width - paddingRight`），窗口调窄过的玩家一转屏就会按旧屏宽的偏移画。
            // [resolveFor] 只做纯几何解算（[publishLayoutGeometry] 更新三个读数），**不**调
            // [applyMetrics]：会话期字号/列宽冻结的既有纪律不变（见下方 rows-only 路径）。
            val layout = resolveFor(w, h)
            // LIVE-REFLOW（契约 §5 L4）：宽度变了就**不许**再走下面的 rows-only 路径 ——
            // 旧路径的前提是「会话期宽度冻结」，还按旧宽折行会让 sink 折行、本类绘制、
            // 命中几何三套坐标系一起错位（列号漂、按钮点不中）。这里只负责把新列数/新行数
            // **问出去**，真正的重排是宿主的链：`session.resizeConsole(...)`（先让核心
            // `sink.resize`）→ [reflowAttachedFeed]（再换屏）。
            //
            // 关态（liveReflow=false）永不回调：「关态永不回调」的判据本体仍带开关
            // （[LiveReflowPlan.viewportNeedsReflow]），这样那条不变量本身可以在纯 JVM 单测里
            // 直接钉住。上面的 [resolveFor] 只是几何刷新，不产生任何宿主回调 / 换屏。
            //
            // 宽度比对读 `feed.screen.clientWidth`（当前**显示中**的那只屏）：本判据问的是
            // 「画面配不配得上新版面」，换屏没补做时旧屏宽照样不等于新解算列数 ⇒ 下次继续问。
            // 而 [reflowAttachedFeed] 那一支（宿主→视口）读的是 `feed.sinkClientWidth`——
            // 两支读数在交接窗口里必然不同，口径与理由见 [LiveReflowPlan] 两个判据的 KDoc。
            if (liveReflow) {
                val notify = onViewportChange
                if (notify != null &&
                    LiveReflowPlan.viewportNeedsReflow(liveReflow, attached.screen.clientWidth, layout.columns)
                ) {
                    // 像素口径 = **版面容区**宽高（与 [resolveFor] 的入参、[applyMetrics] 的那一套
                    // 读数同源），不是 View 全高（含系统栏/状态栏），也不是 sink 的逻辑格宽。
                    // 宽读 [ScreenLayout.layoutWidthPx]（窗口宽窄于物理屏时它才是客户区宽，与
                    // [clientWidthPx] / MouseState 的 clientWidth 同一读数）；未布局解不出宽时
                    // 回落本次的物理内容宽。字号取本次 layout 的解算值（此刻 [cellMetrics]
                    // 尚未 applyMetrics，是旧档）。
                    val physicalContentWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
                    val contentWidth = layout.layoutWidthPx.takeIf { it > 0 } ?: physicalContentWidth
                    notify(
                        layout.columns,
                        layout.rows,
                        contentWidth,
                        contentHeight,
                        layout.metrics.fontPixelSize,
                    )
                    return
                }
            }
            // 会话期字号与列宽**冻结**：ConsoleFeed 的 sink 宽、展平 metrics、命中几何都是
            // 会话构造期定死的，中途换字号会让三套坐标系错位（绘制用新字号、折行/命中用旧字号）。
            // 可见行数必须跟着高度走（输入栏/系统栏显隐就是这个维度）；宽度变化需先经
            // [reflowAttachedFeed] 换屏（开关打开时），否则只能重开会话。
            val rows = if (contentHeight > 0) {
                (contentHeight / lineHeightPx).toInt().coerceAtLeast(1)
            } else {
                1
            }
            resizeFeedScreen(attached.screen, rows)
            syncFeedLayout()
            return
        }
        val layout = resolveFor(w, h)
        applyMetrics(layout.metrics)
        val cells = layout.columns
        // 视口行数 = **屏上真实能画下的行数**（2026-10-24 修：原来这里多要 40 行图片行，
        // 导致视口比可见区高 40 行）。
        // 后果是「一页内容被截断且上滑够不着」：内容贴底对齐后，窗口顶部那 40 行**永远
        // 画在画布上方**，而 `Viewport.maxOffset = retained - heightLines` 也补不回它们
        // —— 玩家看到的就是「原版一页的内容，手机端上面被切掉，还滚不上去」。
        // 图片溢出的压缩交给 RowLayout.plan（按可用高度缩放），不再靠撑高视口。
        val rows = layout.rows

        android.util.Log.i("EratwView", "rebuildScreen rows=$rows cols=$cells lineH=$lineHeightPx font=$fontPixelSize contentH=$contentHeight")
        val current = screen
        if (current != null && current.clientWidth == cells && current.heightLines == rows) {
            return
        }
        // 保留行数取缺省 5000（`ConfigData.cs:62` 的 `MAXLOG` 缺省）而**有意不接** emuera.config 的
        // MAXLOG 键（2026-11-01 裁定，收 OOM 排障时记下的「配置未接进壳层」悬案）：语料 ERB 全库
        // 零消费 MAXLOG（GETCONFIG 无此键的调用点），它在这台壳上只管滚动保留量；而值域上限
        // 1,000,000 行在安卓 512 MiB 堆上是直接的 OOM 放大器 —— 原版桌面语义照抄反而是伤害。
        // 玩家真要更多回滚，走设置页的壳层偏好而不是让脚本配置直通内存分配。
        // 喂同一只尺：本路径是演示/重建屏（未挂 feed 的首屏），与生产屏同一宽度口径。
        val fresh = ConsoleScreen(cells, Grid.MAX_LOG_DEFAULT, rows, fontWidth = fontDisplayWidth)
        fresh.submitSnapshot(pending)
        screen = fresh
        invalidate()
    }

    /**
     * 只改 [ConsoleScreen] 的可见行数并回贴底（宽度不动，理由见 [rebuildScreen]）。
     *
     * 上界取 `list.maxRows - EXTRA_ABOVE_ROWS`：显示列表的行容量是**构造期**定的
     * （`displaylist/DisplayList.kt:117-122`、`view/ConsoleScreen.kt:144`），超过它
     * `DisplayList.submit` 会按尾部保留丢掉窗口顶行，所以宁可夹行数也不能把窗口撑破。
     * 放大到超过初次布局的容量时（分屏/折叠屏让可见高变大）读数会被夹住，画面仍是
     * 「多画到容量为止」，但不再像早先那样直接 return、尺寸永久冻结。
     */
    private fun resizeFeedScreen(console: ConsoleScreen, rows: Int) {
        // 显示列表的容量是构造期按**首帧行高**定的（`ConsoleScreen.kt:159`）。窗口变高
        // （系统栏/输入栏收起）后可见行数会变多（真机 28 行 → 34 行），不放容量就会在
        // `retainTail` 里丢掉窗口顶行：标题页只画得下 28 行、上方留一条黑带。
        // 先抬容量再把行数夹到新容量，容量只升不降。
        console.list.ensureCapacity(rows + ConsoleScreen.EXTRA_ABOVE_ROWS)
        val capacity = (console.list.maxRows - ConsoleScreen.EXTRA_ABOVE_ROWS).coerceAtLeast(1)
        val target = rows.coerceIn(1, capacity)
        if (console.viewport.heightLines == target) {
            return
        }
        console.viewport.heightLines = target
        // 窗口高度变了**不许把读者扔回底部**（2026-10-29 真机反馈「向上滑就被拖拽回来」的第二处，
        // 第一处是 Viewport.sync 的缩短分支）：可见高变 → 可见行数变 → 这里若归零，玩家正看着的
        // 那一屏直接跳到底。现在只由 heightLines 的设值分支重夹到 maxOffset（变矮时夹、变高时守恒），
        // 回底线只保留点击与按键两条原版入口。
        console.refresh()
        invalidate()
    }

    /**
     * 把当前字号/可见区推给 [ConsoleFeed]，让内联图形按新度量重展平一次。
     *
     * 展平器是 `ConsoleFeed` 构造期按当时的 [metrics] 建的：`<img>` 缺省高、占宽格数、
     * 竖带合并的行高都取自它。会话期字号/列宽是冻结的（见 [rebuildScreen]），这里真正会变的
     * 只有可见高（系统栏/输入栏收起）与由此派生的 [bandMaxHeightPx]；图片带宽仍按构造期
     * 的度量展平。若误把「字号在会话中变了」写回这里，会重新引入会话期重排。
     *
     * 可用宽取**固定窗口列数** × 当前半角格宽：`sink.clientWidth` 构造期定死，字号变了
     * 也不能按 `contentWidth / 当前格宽` 重算（那会把 123 列算成 148 列，图形占宽越界）。
     */
    private fun syncFeedLayout() {
        val f = feed ?: return
        // 可用宽取**版面客户区宽**（[layoutWidthPx]，与绘制/命中同一读数），不是物理内容宽：
        // 窗口调窄后竖带图块不该按屏宽合块（那会越出客户区被裁）。
        val layoutWidth = layoutWidthPx()
        val contentHeight = contentHeightPx()
        if (layoutWidth <= 0 || contentHeight <= 0) return
        // 竖带合并的可用宽：单张 <img> 的宽度不再按窗口夹（见 HtmlRunFlatten.emitImage 的 KDoc），
        // 但「把整块横带合成一张、等比缩进屏」这个动作需要一个可用宽，用的还是这个数。
        val maxImageWidth = minOf(layoutWidth, (f.screen.clientWidth * metrics.cellPixelWidth).toInt())
        if (f.updateLayout(
                metrics = metrics,
                bandAvailWidthPx = maxImageWidth,
                bandMaxHeightPx = bandMaxHeightPx(),
            )
        ) {
            // 重展平会改行数（竖带合并把 35 行收成 1 图 + 承载空行），本帧之后的
            // rowLayout / 命中表都按新列表算；字号不在会话期重算（见类头「字体与列数」）。
            requestRedraw()
        }
    }

    /**
     * 图形层跨线程失效口（[GameHost](app) 的 `AndroidGraphicsSink` 回调用它）：
     * VM 线程任何时候都可能 `SETBGIMAGE` / 内联段入队 —— 置脏并按动画帧失效。
     * `postInvalidateOnAnimation` 保证跨线程调用安全。
     */
    fun notifyGraphicsChanged() {
        dbgNotifyCount++
        forceRedraw.set(true)
        postInvalidateOnAnimation()
    }

    /**
     * 背景烘焙位图（C# bakedBackground 的对应物）：把全部背景层按 cover 几何重采样成
     * 一张与内容区等大的图（ValidateBackground 建图 + BakeBackground 烘焙，spec:
     * `EmueraConsole.cs:923-936` 与 `:938-957`），之后每帧只做 1:1 整幅贴图
     * （OnPaint 同款，`:1816-1820`），帧间不重采样。null = 尺寸未就绪 / 尚未画过背景层。
     */
    private var backing: Bitmap? = null

    /** 复用的离屏画布（与 [backing] 同生命周期，避免每次重烘 new Canvas）。 */
    private var backingCanvas: Canvas? = null

    /**
     * 背景烘焙的脏判定（纯逻辑在 [BackgroundBakeGuard]）：内容变化（层换代 / 帧换代）
     * 或尺寸变化才重烘，对齐 C# 的三条重烘触发（Add/Clear/Remove 后各一次 BakeBackground，
     * `EmueraConsole.cs:907`/`:914`/`:921`）加每帧的 ValidateBackground 尺寸闸（`:923-936`）。
     */
    private val bakeGuard = BackgroundBakeGuard()

    /**
     * 类内状态变化置脏标志（[requestRedraw] / [notifyGraphicsChanged] / 尺寸变化都在写它）：
     * 与「有未发布内容」是两个独立的重画理由 —— 后者由 feed 的 revision 对比给出，
     * 前者（滚动位置、命中态、位图替换）revision 看不见，必须显式置脏。
     * 消费口归帧路（onDraw）所有。背景烘焙**不**读本标志：按下态/滚动不是背景内容变化，
     * 背景的换代由 [bakeGuard] 按内容判定，重烘它们违反「帧间不重采样」。
     *
     * 用 [AtomicBoolean] 而不是「读布尔再写 false」：写方在 VM 线程（[notifyGraphicsChanged]），
     * 读到清零之间的那几纳秒里落下的置脏会被旧写法丢掉 —— 图形像素已经原地换了、签名却没有
     * 任何变化时，那一帧就会继续贴旧图。`getAndSet` 把这扇窗关掉。
     */
    private val forceRedraw = AtomicBoolean(false)

    // ---- 实时调试计数（DevTools 通道读取；写只在 UI 线程，跨线程读容忍旧值） ----
    @Volatile var dbgOnDrawCount: Long = 0L; private set
    @Volatile var dbgRepaintCount: Long = 0L; private set
    @Volatile var dbgRequestRedrawCount: Long = 0L; private set
    @Volatile var dbgNotifyCount: Long = 0L; private set

    // ---- 帧内耗时分解（任务 perf-render2；写只在 UI 线程 onDraw，跨线程读容忍旧值） ----
    // 口径：**自上一次 debugStats() 读取以来**的累加，debugStats 读完即清零。
    // 单位：`Ns` = 墙钟纳秒累加；`Calls` / `Rows` / `Frames` = 次数。
    // 阶段是**嵌套**关系：f.drawNs ⊇ f.scanNs + f.bakeNs + f.textNs + 其余（save/clip/drawColor/restore）。
    @Volatile var dbgFrameCount: Long = 0L; private set
    @Volatile var dbgFrameDrawNanos: Long = 0L; private set
    @Volatile var dbgFrameScanNanos: Long = 0L; private set
    @Volatile var dbgFrameBakeNanos: Long = 0L; private set
    @Volatile var dbgFrameTextNanos: Long = 0L; private set
    @Volatile var dbgFrameTextCalls: Long = 0L; private set
    @Volatile var dbgFrameCmdCalls: Long = 0L; private set
    @Volatile var dbgFrameRows: Long = 0L; private set

    /**
     * DevTools `stats` 命令：一行读数量化「谁在驱动重绘」「一帧花在哪」，并带上字号/列数排障读数。
     *
     * 末尾 `f.*` 是**自上次读取以来**的帧内分解（读完立即清零，下次读到的又是新一段）：
     *
     * - `f.frames` — 这一段里的 `onDraw` 帧数（含提前 return 的空帧）
     * - `f.drawNs` / `f.drawUsPerFrame` — 整个 `onDraw` 的墙钟累计 / 每帧均值（微秒）
     * - `f.scanNs` — 「展平命令表 + 逐行纵布局 + 按行分组 + 无图形判定 + 逐行内容签名」
     *   那一趟的累计（**不含**任何落笔）
     * - `f.bakeNs` — `drawBackgrounds` 的累计（重烘帧含 `repaintBacking` 的重采样；
     *   有背景时**每重画一行都会调一次**，所以这个数会按重画行数放大）
     * - `f.textNs` / `f.textCalls` / `f.textUsPerFrame` — `canvas.drawText` 的累计耗时 / 次数 / 每帧均值
     * - `f.cmds` — `drawCmd` 实际执行的次数（可见性闸之后，不含行外早退）
     * - `f.rows` — 真正重画的行数累计（整屏图形路径按行数计）
     *
     * 读与 UI 线程并发时 [debugStats] 在 DevTools 线程清零，正在写的那一帧可能被吞掉
     * （丢 1 帧，不影响量级）；各阶段是**嵌套**的，别把它们相加当总耗时。
     */
    fun debugStats(): String {
        val frames = dbgFrameCount
        val drawNs = dbgFrameDrawNanos
        val scanNs = dbgFrameScanNanos
        val bakeNs = dbgFrameBakeNanos
        val textNs = dbgFrameTextNanos
        val textCalls = dbgFrameTextCalls
        val cmdCalls = dbgFrameCmdCalls
        val redrawnRows = dbgFrameRows
        // 判定器的读数要在清零**之前**取走（否则永远报 0）；行缓存的计数不清零（是总量）。
        val framesFull = planner.fullFrames
        val framesIncremental = planner.incrementalFrames
        val lastRows = planner.lastRasteredRows
        val tracked = planner.trackedRows()
        dbgFrameCount = 0L
        dbgFrameDrawNanos = 0L
        dbgFrameScanNanos = 0L
        dbgFrameBakeNanos = 0L
        dbgFrameTextNanos = 0L
        dbgFrameTextCalls = 0L
        dbgFrameCmdCalls = 0L
        dbgFrameRows = 0L
        planner.resetStatistics()
        val drawUsPerFrame = if (frames > 0L) drawNs / frames / 1000L else 0L
        val textUsPerFrame = if (frames > 0L) textNs / frames / 1000L else 0L
        return "onDraw=$dbgOnDrawCount repaint=$dbgRepaintCount reqRedraw=$dbgRequestRedrawCount " +
            "notify=$dbgNotifyCount forceRedraw=${forceRedraw.get()} backing=${backing?.width}x${backing?.height} " +
            "surface=${surfaceWidth}x${surfaceHeight} lastFull=$lastFrameFull " +
            "rowsFull=$framesFull rowsInc=$framesIncremental " +
            "lastRows=$lastRows tracked=$tracked " +
            "rowCache=${rowCache.size}条/${rowCache.bytes}B hit=${rowCache.hits} miss=${rowCache.misses} " +
            "evict=${rowCache.evictions} refused=${rowCache.refusedOversize} " +
            "pool=${rowBitmapPool.size}/${rowPoolBytes}B " +
            "unpublished=${feed?.hasUnpublishedContent()} columns=$columns " +
            "fontPx=${fontPixelSize.toInt()} consoleColumns=${consoleColumns()} " +
            "f.window=sinceLastRead f.frames=$frames f.drawNs=$drawNs f.drawUsPerFrame=$drawUsPerFrame " +
            "f.scanNs=$scanNs f.bakeNs=$bakeNs f.textNs=$textNs f.textCalls=$textCalls " +
            "f.textUsPerFrame=$textUsPerFrame f.cmds=$cmdCalls f.rows=$redrawnRows"
    }

    /** DevTools `img` 命令：按资源名试取一帧，返回宽高（null = 取不到）。 */
    fun debugFrame(resource: String): Pair<Int, Int>? {
        val f = frameSource?.frame(resource) ?: return null
        return f.srcW to f.srcH
    }

    /** DevTools `bg` 命令：当前背景层列表。 */
    fun debugBackgrounds(): String {
        val layers = graphicsLayer?.background ?: return "（无图形层）"
        return if (layers.isEmpty()) "（无背景层）" else layers.joinToString { "${it.resource}@${it.depth}" }
    }

    /**
     * 重烘背景进 [backing]（C# BakeBackground 的对应物，spec: `EmueraConsole.cs:938-957`）：
     * 先透清成透明（`:941` 的 `Clear(Color.Transparent)`，这里 `eraseColor(0)` 同义），
     * 再按 depth 降序逐层以「等比适配 + 水平居中 + 顶对齐」的 cover 几何
     * （`:947-952`，纯算式在 [GraphicGeometry.coverBox]）带不透明度重采样进位图
     * （`:953-955` 的 ColorMatrix 在此用 [Paint.alpha] 等价：GDI+ 是逐通道乘 alpha）。
     * 位图缺失或宽高不匹配时先重建（`:929-934` 的 ValidateBackground 分支）。
     *
     * 调用点唯一：[drawBackgrounds] 的脏判定 —— 对应原版「层变更后各调一次 BakeBackground
     * （Add `:907` / Clear `:914` / Remove `:921`）+ 每帧 ValidateBackground 尺寸闸
     * （`:923-936`）」在绘制侧的合并形态。烘焙坐标以内容区左上为原点，
     * 贴图时统一加回 content 原点，与逐层直画的落点逐像素同位。
     */
    private fun repaintBacking(width: Int, height: Int, layers: List<GraphicsLayer.BgPlacement>, source: ImageFrameSource) {
        dbgRepaintCount++
        var bmp = backing
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (t: OutOfMemoryError) {
                // 烘焙面分配不下：这一帧按「没有 bakedBackground」处理，背景不贴（口径同
                // `EmueraConsole.cs:924-927` 的 null 分支 —— 下一帧 [drawBackgrounds] 的
                // `backing == null` 自愈条件会再试一次，见其 KDoc）。
                // 同 [ensureScreenSurface]：native 面不做 Java 头寸预检，只兜住 + 先收自有缓存。
                // 兜不住的话这是一条 `SETBGIMAGE` 就能崩掉 UI 线的路（Error 不被 onDraw 的
                // `catch (t: RuntimeException)` 拦下）。
                android.util.Log.w(TAG, "背景烘焙面 ${width}x$height native 分配不下：这一帧不铺背景", t)
                backing = null
                backingCanvas = null
                releaseMemoryCaches(deep = false)
                return
            }
            backing = bmp
            backingCanvas = Canvas(bmp)
        }
        val bc = backingCanvas ?: return
        // 0 = Color.TRANSPARENT：透清对齐 `:941` 的 Clear(Color.Transparent)
        bmp.eraseColor(0)
        for (placement in layers) {
            val frame = source.frame(placement.resource) ?: continue
            val bitmap = frame.handle as? Bitmap ?: continue
            val bw = (if (frame.srcW > 0) frame.srcW else bitmap.width).toFloat()
            val bh = (if (frame.srcH > 0) frame.srcH else bitmap.height).toFloat()
            if (bw <= 0f || bh <= 0f) continue
            // 摆位与源矩形都是纯算式（判据与 C# 行号见 GraphicGeometry）
            val box = GraphicGeometry.coverBox(width.toFloat(), height.toFloat(), bw, bh)
            // 上屏必须用夹过的源矩形：`Canvas.drawBitmap` 的 src 越界/空会抛 IllegalArgumentException
            // （GDI+ 只是看不见），一条坏 SPRITECREATE 就会把整帧绘制打死。夹空 = 这一层没像素可画。
            val src = GraphicGeometry.sourceRectClipped(
                frame.srcX, frame.srcY, frame.srcW, frame.srcH, bitmap.width, bitmap.height,
            ) ?: continue
            paint.alpha = placement.opacity.coerceIn(0, 255)
            srcRect.set(src[0], src[1], src[2], src[3])
            val x = box[0]
            dstRect.set(x, 0f, x + box[2], box[3])
            bc.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
        paint.alpha = 255
    }

    override fun onDraw(canvas: Canvas) {
        // 任务 perf-render2：整帧墙钟（f.drawNs）。try/finally 不分配对象，
        // 提前 return 的空帧也计入（f.frames 与 f.drawNs 的分母一致）。
        val frameStartNs = System.nanoTime()
        // 「原地重写 ⇒ 位图重建」的那一帧可能画的是合成半成品（UI 线程与 VM 线程并发，
        // 见 `ImageFrameSource.cacheRebuilds` 的 KDoc）：前后各读一次计数，变了就再排一帧。
        // 行位图缓存不用清 —— [rowSignature] 已把位图身份算进签名，下一帧签名变了自然重烤。
        val rebuildsBefore = frameSource?.cacheRebuilds ?: 0L
        try {
            // 动画重绘：`SETANIMETIMER` 非 0 时，画这一帧的同时把下一帧排上（原版定时器
            // 到点只做 window.Refresh，EmueraConsole.cs:959-965）。0 = 关，退回既有的
            // 「内容变了才重画」—— 这一行在关表时只是一次 `?.invoke()` + 一次比较。
            scheduleAnimeTick()
            val attached = feed
            if (attached != null) {
                // 生产喂法：revision 未变时 pump 为零开销；变化时补交快照后再画同一次绘制里
                // 已更新的 DisplayList（folding/入列/脏区都在 :render 内完成）。
                attached.pump()
                drawScreen(canvas, attached.screen)
                notifyFirstContentFrame(attached.screen)
                return
            }
            drawScreen(canvas, screen ?: return)
        } catch (t: RuntimeException) {
            onDrawFailed(t)
        } finally {
            dbgFrameDrawNanos += System.nanoTime() - frameStartNs
            dbgFrameCount += 1L
            // 本帧重建过位图 ⇒ 这一帧的行可能画的是合成半成品：再排一帧。
            // 收敛性：下一帧取到的是按新写代数重建的源位图、签名随之改变，计数不再变 ⇒ 不自激。
            if ((frameSource?.cacheRebuilds ?: 0L) != rebuildsBefore) postInvalidateOnAnimation()
            // 有重建被「写空闲稳定期」推迟 ⇒ 继续排帧，等这波图形写停下来再重建（否则画面停在旧图）。
            if (frameSource?.hasPendingRebuild == true) postInvalidateOnAnimation()
        }
    }

    /**
     * 首屏非空内容落笔后的收尾：只认挂了 [ConsoleFeed] 的生产帧（调用点就在 `onDraw` 的
     * feed 分支里），空屏（还没打印任何行）不算。回调挪出 `onDraw` 用 `post` 执行，
     * 避免在绘制遍历里写 Compose 状态。
     */
    private fun notifyFirstContentFrame(console: ConsoleScreen) {
        val cb = onFirstContentFrame ?: return
        if (console.list.rowCount == 0) return
        onFirstContentFrame = null
        post { cb() }
    }

    /**
     * 绘制路径的**最后一帧兜底**：一帧画不出来 ≠ 游戏退出。
     *
     * ## 为什么必须有这一层
     *
     * Android 上主线程抛出的任何未捕获异常都直接结束进程 —— 而 [onDraw] 里跑的是
     * `pump()`（快照差分 + 折行 + HTML 展平）与整屏绘制，是全应用**执行频率最高**的
     * 一段代码：60 fps 下每秒 60 次，且每次都要摸一遍 VM 线程刚发布的数据结构。
     * 其中任何一处抛 `RuntimeException`（最典型的是 `Canvas: trying to use a recycled
     * bitmap` 的 `IllegalStateException`、行/列下标越界、并发容器在极端时序下的
     * `ConcurrentModificationException`），玩家看到的就是**没有失败屏、没有提示、
     * 直接回到桌面** —— 正是「在外面跑来跑去莫名其妙就退出来了」那种形状。
     *
     * 兜住之后：本帧放弃（`Canvas` 上保留上一帧的像素），下一帧照常重画。屏上最多
     * 「顿一下」，进程还在，玩家能继续玩、也能把日志发回来。
     *
     * ## 为什么不 catch `Error`
     *
     * `OutOfMemoryError` / `StackOverflowError` 刻意放行：它们要走 `MainActivity`
     * 的崩溃处理器拿到真实栈并落错误报告（那条链是玩家反馈的唯一取证面）；而 OOM
     * 被逐帧吞掉只会变成「每帧都分配失败」的死循环，比退出更糟。只兜 `RuntimeException`
     * 这一类「这一帧的数据不自洽」的错。
     *
     * ## 不静默
     *
     * 首次打完整栈，之后按 [DRAW_FAILURE_LOG_EVERY] 限流打一行计数 —— 一帧一崩时
     * logcat 不该被同一段栈刷满，但也绝不能一声不响。
     *
     * ## 必须作废增量状态（否则「兜住异常」会变成「永久残屏」）
     *
     * [drawScreen] 是**增量**的：它按行签名决定哪几行重新栅格化进 [screenSurface]，
     * 并把本帧签名记进 [planner]。一旦中途抛出去，就出现了「签名已提交、像素没落笔」
     * 的组合 —— 下一帧按签名判定「这行没变」，于是**那一行永远是残的**，而且不再有任何
     * 东西会把它改回来（这正是旧实现「黑屏一秒多」的同型陷阱，见保留面那一段注释）。
     * 所以这里把 planner 整个作废（[ScreenSurfacePlanner.notifySurfaceLost]）：
     * 下一帧退化成整屏重画，画面自愈。代价只在「正在连续失败」时多付几次全量重绘，
     * 而那种情形下画面本来就是坏的。
     */
    private fun onDrawFailed(t: RuntimeException) {
        drawFailureCount += 1L
        // 先作废增量判定，再打日志：日志本身不该影响恢复动作的时序。
        planner.notifySurfaceLost()
        if (!drawFailureReported) {
            drawFailureReported = true
            android.util.Log.e(
                TAG,
                "绘制异常：本帧放弃（屏上保留上一帧），后续同类只计数",
                t,
            )
        } else if (drawFailureCount % DRAW_FAILURE_LOG_EVERY == 0L) {
            android.util.Log.w(
                TAG,
                "绘制异常累计 ${drawFailureCount} 次：${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    /**
     * 整帧版面指纹：行顶/行高与内容区尺寸。任一变化都必须整屏重画 ——
     * 因为「同一行」在屏上的位置变了，跳过重画会留下错位残影。
     *
     * 行表改成按窗口建行后**没有 `minRow` 项**了（[RowLayout.Plan] 的下标恒从 0 起），
     * 行数取 `tops.size` —— 窗口行数变了（滚动夹边、字号换代）照样进指纹。
     *
     * **按下态不进指纹**（PERF 2026-11-03，地图/长页点按钮卡顿收口）：按下只改
     * 按下那一行的像素（[rowSignature] 对 BUTTON 行按下态的判定与 [drawCmd] 逐字
     * 同源），改行内像素不需要作废整张行签名表 —— 旧口径把按下态同时放进指纹，
     * 一次按下/抬起就把全部行的签名连同整幅判定一起作废，非图形帧也被迫整屏重画。
     * 按下态的置脏面由此收敛到单行（[setPointer] 也不再走强制整幅）。
     */
    private fun layoutFingerprint(
        layout: RowLayout.Plan,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        textBgHash: Int,
    ): Int {
        var h = layout.tops.size
        for (i in layout.tops.indices) {
            // 行顶/行高按**原始位**入指纹：图上按行带落笔，半像素的位移也是位移。
            // 取 toInt() 会把「立绘压缩把后面的行推了 0.4px」抹平（整数部分没变），
            // 判定就此停在旧位置；文本行本来就是整像素，原始位不产生额外抖动。
            h = h * 31 + layout.tops[i].toRawBits()
            h = h * 31 + layout.heights[i].toRawBits()
        }
        h = h * 31 + left.toInt()
        h = h * 31 + top.toInt()
        h = h * 31 + right.toInt()
        h = h * 31 + bottom.toInt()
        // 文字衬底（TEXT_BGC_ON/OFF）是**全局**状态：变了必须整屏重画，否则「脚本改了衬底
        // 但该行签名不变 ⇒ 不重画」的假绿（WIRE-4 自检项）。衬底在任何文字段之下，
        // 逐行增量无法只补新行，故随指纹整屏。
        h = h * 31 + textBgHash
        return h
    }

    /**
     * 单行内容签名：把这一行所有绘制命令里**影响像素**的字段揉成一个 Int。
     *
     * 包含字形的完整 [GlyphKey] 列表哈希（文字内容一变签名就变，不会出现旧字残留），
     * 以及按下态（按下会改底色/字色）。
     */
    private fun rowSignature(
        cmds: List<DrawCmd>?,
        pressedRow: Int,
        pressedLo: Int,
        pressedHi: Int,
    ): Int {
        if (cmds == null || cmds.isEmpty()) return 0
        var h = 17
        for (c in cmds) {
            h = h * 31 + c.colStart
            h = h * 31 + c.colEnd
            h = h * 31 + c.fore
            h = h * 31 + (c.back ?: 0)
            h = h * 31 + c.styleMask
            h = h * 31 + c.kind.ordinal
            h = h * 31 + c.focusFore
            // 按下态口径必须与 drawCmd 的判定**逐字一致**（含 `kind == BUTTON`）：
            // 签名宽于像素变化只会多烤一份同形位图，宽窄反过来就是「像素变了签名没变」。
            if (c.kind == RunKind.BUTTON && c.row == pressedRow &&
                c.colStart < pressedHi && pressedLo < c.colEnd
            ) {
                h = h * 31 + 1
            }
            val g = c.graphic
            // 图片段的签名必须**含当前位图身份**（2026-11-06 真机布娃娃页）：
            // 命令还是那条 `<img src=…>`，但图片内容会因为脚本**原地重画合成画布**而变
            // （`GCREATE` 一次 + 每帧 `GDRAWSPRITE`；像素数组引用不变，位图靠
            // `GraphicsBuffer.pixelRevision` 重建）。签名只算命令哈希的话，行位图缓存
            // 会把上一张半成品图原样贴回来 —— 现象就是「预览永远是第一帧的碎片」。
            // `frame()` 是缓存命中路径（常态零解码），每帧每图片段一次哈希表查找的代价。
            if (g is InlineGraphic.Image) {
                val handle = frameSource?.frame(g.drawResource(false))?.handle
                h = h * 31 + if (handle == null) 0 else System.identityHashCode(handle)
            }
            h = h * 31 + (g?.hashCode() ?: 0)
            h = h * 31 + c.glyphs.hashCode()
        }
        return h
    }

    /**
     * 本帧的绘制命令缓冲（复用，稳态帧零分配）：[drawScreen] 每帧经
     * `DisplayList.renderInto` 先清后填，不再像旧路径那样每帧 `render()` 新建一个
     * 全量 `ArrayList`。只在绘制帧路（UI 线程 [onDraw]）上读写；[repaintBacking]
     * 烘的是背景层、不碰这份缓冲，两处没有共享。
     */
    private val frameCmds = ArrayList<DrawCmd>()

    private fun drawScreen(canvas: Canvas, console: ConsoleScreen) {
        val contentLeft = contentLeftPx()
        val contentTop = paddingTop.toFloat()
        // 内容右沿 = 内容左沿 + 客户区宽（唯一派生；不再用 width - paddingRight，那会漏掉居中偏移）。
        val contentRight = contentLeft + layoutWidthPx()
        val contentBottom = (height - paddingBottom).toFloat()
        if (contentRight <= contentLeft || contentBottom <= contentTop) {
            return
        }
        // ---- 扫描段（读数 f.scanNs）：展平 + 逐行纵布局 + 按行分组 + 无图形判定 + 逐行签名 ----
        // 计时包住「所有不落笔的准备活」，好把一帧里剩下的 ~25 ms 拆开看（上一波真机读数：
        // 行签名扫描 0.19 ms/帧、drawText 2.27 ms/帧、bake 0.004 ms/帧 ⇒ 大头不在这三处）。
        val scanStartNs = System.nanoTime()
        // **本帧唯一一次展平**（任务 perf-render2 的唯一优化）。命令先落进复用缓冲
        // [frameCmds]，[rowLayout] 与下面的绘制循环吃同一份 —— 不再像旧实现那样
        // 「旧 `rowMetrics` 里 `render()` 展一遍 + 这里 `renderInto` 再展一遍」。
        console.list.renderInto(frameCmds)
        // 纵布局与 tap / setPointer 同一个来源（逐行行顶表，见 [rowLayout]）。
        // 行表**恒按窗口行数**建行 ⇒ `cmd.row` 直接就是表的下标，无平移。
        val layout = rowLayout(console, frameCmds)
        // div 覆盖块帧（CONTRACT-DIV-WIRE W2）：窗口 + 一屏 margin 扫块，按行顶表规划。
        // 在场（或上一帧在场这一帧退场）都走整幅 —— 块的像素横跨行带，逐行栅格化会把
        // 块擦出洞；div 页面（列表/栏式布局）低频，整幅代价可接受（与含图形帧同一口径）。
        val divOverlay = divOverlayFrame(console, layout)
        val hasDivOverlay = !divOverlay.isEmpty
        // 命中层吃**同一帧**的覆盖规划（CONTRACT-DIV-HIT2）：`blockIndex` 就是本帧
        // behind+front 拼接序的下标，`focusedDiv`/`pressedDiv`/`divTargets` 全由它出。
        // 空帧也要喂一次（命中表随之清空）；画布高与 [divOverlayFrame] 喂规划器的同值。
        console.updateDivOverlay(divOverlay, metrics, contentHeightPx())
        // div 子行按钮的焦点/按下态（HTMLDIV-B2）：每帧只查一次，绘制循环里零分配。
        // 身份 = 命中层的 div 像素条目（`ConsoleDivPart.cs:137-172` 的 TestChildHitbox）；
        // [ButtonLayer.DivFocus.blockIndex] 是**本帧 `DivOverlayFrame.behind + front` 的
        // 拼接序全局下标**（= `DivOverlayPlanner.Plan.all` 的 KDoc 口径，DivOverlay.kt:107），
        // 所以画 behind 时基址 0、画 front 时基址 `behind.size`（顺序与 [divOverlayFrame] 同一份）。
        val focusedDiv = console.focusedDiv()
        val pressedDiv = console.pressedDiv()
        // 按下态命中区：每帧只查一次（绘制循环里零分配）。行列换算是
        // 「命中口径 → 绘制口径」，与 tap 用同一套基准（ConsoleScreen.hitRowToSnapshotRow）。
        var pressedRow = -1
        var pressedLo = 0
        var pressedHi = 0
        if (pointerDown) {
            val layer = console.buttons
            val target = layer.hit(pointerHitRow, pointerHitCol, pointerAcceptance)
            if (target != null && layer.isPressed(target)) {
                val row = console.hitRowToSnapshotRow(target.row)
                if (row != null) {
                    pressedRow = row
                    pressedLo = target.colStart
                    pressedHi = target.colEnd
                }
            }
        }
        // ---- 按行签名跳过重画（2026-10-24 抖动收口）--------------------------------
        // 全屏重绘 p99 仍有 ~48 ms（~3000 次 drawText），而一次输出通常只动 1-2 行。
        // 给每个可见行算「内容签名」，签名没变就**跳过**（像素保留上一帧）；版面几何
        // （滚动/尺寸/行高）、背景层、按下态任一变化 ⇒ 整屏重画并作废全部签名。
        // 不走 render 层的脏矩形：那套是行区间，与绘制口径换算容易错位（实测出重影）。
        // 命令表已在上面展平过一次（[frameCmds]），这里只按行分组，不再重复 flatten。
        // 命令仍是 DisplayList 行缓存里的稳定实例，与旧路径 `render()` 逐项相同。
        for (list in rowCmds.values) list.clear()
        // 命令按行升序展平（DisplayList 逐行展平），缓存「上一个桶」就能把每条命令一次装箱
        // 压成每行一次（`HashMap<Int, …>` 的键是装箱对象，行号几乎都大于 128，不吃 Integer 缓存）。
        // 顺序假设不成立时只是退回逐条查表，不影响正确性。
        var lastRowKey = Int.MIN_VALUE
        var lastRowList: ArrayList<DrawCmd>? = null
        for (cmd in frameCmds) {
            var list = if (cmd.row == lastRowKey) lastRowList else null
            if (list == null) {
                list = rowCmds.getOrPut(cmd.row) { ArrayList(4) }
                lastRowKey = cmd.row
                lastRowList = list
            }
            list.add(cmd)
        }
        val hasBackground = !graphicsLayer?.background.isNullOrEmpty()
        // SETIMAGELAYERL 图层在**行带之外**且画在文字之上 ⇒ 在场即整幅（与图形的口径一致）：
        // 逐行重画只会清掉/擦掉图层像素，必须每次整屏铺。
        val hasImageLayer = !graphicsLayer?.imageLayers.isNullOrEmpty()
        // CBG 图层（CBGSETG / CBGSETSPRITE）：整屏层、depth 降序、不随滚动。与 SETIMAGELAYERL
        // 同理：它画在行带之外（depth<0 还压正文），逐行重绘会擦出陈迹 ⇒ 在场即走整屏路。
        val hasCbg = !graphicsLayer?.cbgLayers.isNullOrEmpty()
        // 含内联图形的行不能逐行裁剪：图片/色块的高度可以超过行高、并有意溢出到相邻行
        // （标题图 16px 横带按 40px 行高铺就是这样），逐行 clipRect 会把它切出带状断层
        // （2026-10-24 真机实证：标题图出现错位横带）。有图形 ⇒ 回整屏绘制路径。
        // 只看**可见窗口**：窗口外的行这两条路都不会落笔（行索引闸加可见性闸），它却会让
        // 之后每一帧都退回整幅 —— 滚动离开一张立绘后，增量路径就再也接不上。
        var hasGraphic = false
        for (idx in layout.tops.indices) {
            val list = rowCmds[idx] ?: continue
            for (cmd in list) {
                if (cmd.graphic != null) {
                    hasGraphic = true
                    break
                }
            }
            if (hasGraphic) break
        }
        // ---- 落笔一律进**保留面**，出 [onDraw] 前整幅贴图（修复①②的核心）----
        // 判定可以少栅格化行，但画面是否上屏不再取决于判定 —— 每一帧都把整张 surface 贴出去。
        // 「不贴」就是黑屏的定义，所以这里没有任何跳过贴图的分支。
        val target = ensureScreenSurface(console.backgroundArgb) ?: return
        // 上一帧的「烤行位图分配不下」不带到这一帧：每帧给一次尝试机会（见 [rowBakeBlocked]）。
        rowBakeBlocked = false
        val fingerprint = layoutFingerprint(
            layout, contentLeft, contentTop, contentRight, contentBottom,
            graphicsLayer?.textBackground?.hashCode() ?: 0,
        )
        val rowCount = layout.tops.size
        if (rowSigBuf.size < rowCount) rowSigBuf = IntArray(rowCount.coerceAtLeast(16))
        for (idx in 0 until rowCount) {
            rowSigBuf[idx] = rowSignature(
                rowCmds[idx], pressedRow, pressedLo, pressedHi,
            )
        }
        // 显式置脏在这里被原子地消费（旧实现只写不读，「置脏即整屏重画」是空头承诺）。
        // 覆盖块退场帧（上一帧有块、这一帧没了而行签名没变）同样整幅一次，清掉旧像素。
        val forced = forceRedraw.getAndSet(false) || (lastFrameHadOverlay && !hasDivOverlay)
        planner.plan(
            // 行表按窗口建行 ⇒ 表下标就是绘制口径行号，起点恒 0（planner 本身不改）。
            // 副作用（复查确认过、不是漏网）：planner 的 `windowChanged` 从此只看行数变化 ——
            // 旧的 `minRow` 是「首个**有命令**的行的快照下标」，滚动时只在「窗口首行有没有字」
            // 翻转时才偶尔变一次，本来就不是可靠的滚动信号。滚动后的像素正确性由两条既有判据
            // 兜住：每行内容变 ⇒ 该行 [rowSignature] 变 ⇒ 该行重栅格化；行顶/行高或内容区变 ⇒
            // [layoutFingerprint] 变 ⇒ 整幅。含图形/背景的帧本来就一律整幅（planner 的
            // graphicPresent / backgroundPresent 分支）。
            minRow = 0,
            sigs = rowSigBuf,
            rowCount = rowCount,
            fingerprint = fingerprint,
            graphicPresent = hasGraphic || hasImageLayer || hasDivOverlay || hasCbg,
            backgroundPresent = hasBackground,
            backgroundArgb = console.backgroundArgb,
            forced = forced,
        )
        lastFrameFull = planner.full
        lastFrameHadOverlay = hasDivOverlay
        dbgFrameRows += planner.lastRasteredRows.toLong()
        dbgFrameScanNanos += System.nanoTime() - scanStartNs

        if (hasGraphic || hasImageLayer || hasCbg) {
            // 整屏路（planner 对含图形的帧一律判整幅）：图片/色块可以溢出到相邻行，
            // 逐行 clip 会切出断层（2026-10-24 真机实证），所以这里不逐行、不查缓存。
            // 扫描段到此为止（下面全是落笔）：命令表就是上面展平的那份 [frameCmds]。
            // 整屏底色先于 clip 铺满整张保留面——原版 `graph.Clear(bgColor)` 清的是整张
            // 画布（EmueraConsole.cs:1816），填色若 clip 在内容区，换底色后内边距带会
            // 残留上一版底色（时停变色首帧的形状）。
            target.drawColor(console.backgroundArgb)
            target.save()
            target.clipRect(contentLeft, contentTop, contentRight, contentBottom)
            drawBackgroundsMeasured(target, contentLeft, contentTop, contentRight, contentBottom)
            // CBG / div / 正文：**单趟 depth 归并**（与 headless `ScreenComposer` 同一份
            // [CbgDrawOrder.merge]）。旧实现是「CBG(>=0) → div behind → 正文 → div front →
            // CBG(<0)」两段式：CBG depth=1 + div depth=2 这类交叉形态会画反（原版
            // `EmueraConsole.cs:1834-1872` 三侧在同一张 depth 序上归并）。这里只把归并结果
            // 按 [CbgDrawOrder.Step.Text] 切成「正文前/正文后」两段落笔 —— 正文行本身仍是
            // 原版的两趟铺墨（不越行的先画、越行的溢出面后补），逻辑一字未动。
            val cbgLayers = graphicsLayer?.cbgLayers ?: emptyList()
            cbgClock.retainOnly(cbgLayers)
            val cbgNowMs = System.nanoTime() / 1_000_000L
            val steps = CbgDrawOrder.merge(
                cbgLayers = cbgLayers,
                divBehind = divOverlay.behind,
                divFront = divOverlay.front,
            )
            var textDrawn = false
            for (step in steps) {
                when (step) {
                    is CbgDrawOrder.Step.Cbg ->
                        drawCbgLayer(target, step.layer, contentLeft, contentTop, cbgNowMs)
                    is CbgDrawOrder.Step.Div -> drawDivBlock(
                        target, step.block, contentLeft, contentTop, step.blockIndex,
                        focusedDiv = focusedDiv, pressedDiv = pressedDiv,
                    )
                    // 立绘本类不画（端口的立绘走 `GraphicsLayer`/宿主侧既有通路）；归并里
                    // 出现时跳过，不改变像素（本类的 `sprites` 入参恒空）。
                    is CbgDrawOrder.Step.Sprite -> Unit
                    is CbgDrawOrder.Step.Text -> if (!textDrawn) {
                        textDrawn = true
                        drawTextPasses(target, frameCmds, layout, contentLeft, pressedRow, pressedLo, pressedHi)
                    }
                }
            }
            if (!textDrawn) {
                // 归并恒产出一条 Text 步；这里只是防御（空步骤表时也要把正文画出来）。
                drawTextPasses(target, frameCmds, layout, contentLeft, pressedRow, pressedLo, pressedHi)
            }
            // 图层画在**文字之上**（overlay）：先两趟铺文字/图形，再铺 SETIMAGELAYERL 图层。
            // TODO(verify)：原版图层与文字/图形的 z 序无 PC 截图可比，本侧按「图层最上层」收口。
            drawImageLayers(target, contentLeft, layout, console)
            target.restore()
            blitSurface(canvas)
            // 动画 CBG 要逐帧推进：快照本身不会再推送，绘制侧每帧自算帧号 ⇒ 只要还有
            // 带帧表的层就再排一帧（与 `SETIMAGELAYERL` 动画同一类做法）。
            if (cbgLayers.any { it.frames.isNotEmpty() }) postInvalidateOnAnimation()
            return
        }
        if (planner.full) {
            // 整幅帧先把保留面铺成底色：跳过重画的前提是「那块像素已经是最终结果」，
            // 刚清空的面上不成立。背景层同样在这里一次性铺（逐行再铺会盖掉上一行的溢出）。
            // 底色是 SETBGCOLOR 的活值（原版 graph.Clear(bgColor) 整张清，EmueraConsole.cs:1816）。
            target.drawColor(console.backgroundArgb)
            if (hasBackground) drawBackgroundsMeasured(target, contentLeft, contentTop, contentRight, contentBottom)
            // div 覆盖块 behind 层（W2）：整幅帧先落 depth > 0 的块，正文行铺在它们上面。
            drawDivBlocks(
                target, divOverlay.behind, contentLeft, contentTop,
                blockIndexBase = 0, focusedDiv = focusedDiv, pressedDiv = pressedDiv,
            )
        }
        // 逐行栅格化：只动 planner 判脏的行。行序与旧实现一致（升序、同一 index 空间）。
        for (idx in 0 until rowCount) {
            if (!planner.needsRaster(idx)) continue
            rasterTextRow(
                target = target,
                cmds = rowCmds[idx],
                sig = rowSigBuf[idx],
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                rowTopPx = layout.tops[idx],
                rowHeightPx = layout.heights[idx],
                surfacePreCleared = planner.full,
                bgArgb = console.backgroundArgb,
                pressedRow = pressedRow,
                pressedLo = pressedLo,
                pressedHi = pressedHi,
            )
        }
        if (hasDivOverlay) {
            // div 覆盖块 front 层（W2）：depth < 0 的块压在正文上（planner 对含覆盖块的帧
            // 恒判整幅，这里不用担心增量帧漏画）。blockIndex 基址同拼接序（见上）。
            drawDivBlocks(
                target, divOverlay.front, contentLeft, contentTop,
                blockIndexBase = divOverlay.behind.size,
                focusedDiv = focusedDiv, pressedDiv = pressedDiv,
            )
        }
        blitSurface(canvas)
    }

    /**
     * 本帧的 div 覆盖帧（CONTRACT-DIV-WIRE W2）：[ConsoleScreen.divOverlayScan] 扫
     * 「窗口 + 一屏 margin」的块行，行条顶用 [RowLayout.Plan.tops] 折算 ——
     * 行表是 View 绝对坐标（起点 `contentBottom - total`），块几何统一走**内容区坐标**
     * （锚行顶减 [paddingTop]；absolute 块的画布高喂 [clientHeightPx]，ConsoleDivPart.cs:178
     * 的 MainPicBox.Height 就是内容区高），绘制时统一加回 [contentLeft]/[contentTop]。
     * 窗口外的 margin 行按行高线性外推（可越出窗口上沿为负，原版落点公式
     * `num6 + (LineNo - num3) * LineHeight` 同一条直线，EmueraConsole.cs:1870）。
     */
    private fun divOverlayFrame(console: ConsoleScreen, layout: RowLayout.Plan): DivOverlayFrame {
        if (layout.tops.isEmpty()) return DivOverlayFrame.EMPTY
        val contentHeightPx = clientHeightPx()
        if (contentHeightPx <= 0 || lineHeightPx <= 0f) return DivOverlayFrame.EMPTY
        val scan = console.divOverlayScan((contentHeightPx / lineHeightPx).toInt() + 1)
        if (scan.isEmpty()) return DivOverlayFrame.EMPTY
        val base = console.visibleRowBase
        val firstIndex = console.log.firstIndex
        val rowCount = layout.tops.size
        val rows = ArrayList<DivOverlayRow>(scan.size)
        for (r in scan) {
            val idx = r.retainedIndex - base
            // 窗口内用行表原值；窗口外按最近一排 + 行高步进外推（行表有纯图行压缩时
            // 窗口内仍是原值，块锚行都是文本行，不受压缩影响）。
            val clamped = idx.coerceIn(0, rowCount - 1)
            val topContent = (layout.tops[clamped] + (idx - clamped) * lineHeightPx - paddingTop).roundToInt()
            rows.add(DivOverlayRow(r.blocks, topContent, r.lineNo))
        }
        return divPlanner.planFrame(
            rows = rows,
            canvasHeightPx = contentHeightPx,
            visibleTopLine = firstIndex + base,
            visibleBottomLine = firstIndex + base + console.visibleRows().size - 1,
        )
    }

    /**
     * 画一层覆盖块（[DivOverlayFrame.behind] / [front] 的已排序清单，原样按序铺）。
     *
     * 焦点/按下态参与子行换色（HTMLDIV-B2）：原版 div 子行与普通行走**同一条**绘制路径
     * （`ConsoleDivPart.cs:198-203` 的 `children[i].DrawTo`），子行里的按钮照旧由
     * `ConsoleButtonString.DrawTo` 自己算 `isFocus` / `isSelecting`
     * （`ConsoleButtonString.cs:317-320`，按按钮对象身份比、与节点在不在 div 内无关）；
     * 本端口的身份由命中层 `ConsoleScreen.focusedDiv()` / `pressedDiv()` 给出，
     * 逐 run 做「块号 + 子行号 + 格区间相交」判定（见 [divFocusCovers]）。
     *
     * @param blockIndexBase 本层首块的全局块号：`Plan.all = behindText + inFrontOfText`
     *   的拼接序（`DivOverlay.kt:107`）—— behind 层基址 0、front 层基址 `behind.size`，
     *   与命中层 [ButtonLayer.DivFocus.blockIndex] 同一套编号（组长裁定 2026-11-06）。
     */
    private fun drawDivBlocks(
        canvas: Canvas,
        blocks: List<DivOverlayPlanner.Block>,
        contentLeft: Float,
        contentTop: Float,
        blockIndexBase: Int,
        focusedDiv: ButtonLayer.DivFocus?,
        pressedDiv: ButtonLayer.DivFocus?,
    ) {
        for (i in blocks.indices) {
            drawDivBlock(
                canvas, blocks[i], contentLeft, contentTop,
                blockIndexBase + i, focusedDiv, pressedDiv,
            )
        }
    }

    /**
     * 一颗覆盖块：背景（圆角）→ 四边框条带 → 内容盒内逐子行。块内几何全部出自
     * [DivOverlayPlanner.plan]（margin 收缩的 borderRect、三段收缩的内容盒、子行逐行落点），
     * 本方法只做 Canvas 铺装。
     *
     * 与原版 `DrawBorder`（Shape.cs）的形状差异：边框条带按直角矩形铺，不复刻原版
     * 「两端斜切的梯形 + 圆角路径」；背景在 radius > 0 时用 [android.graphics.Path.addRoundRect]
     * 的逐角半径（语料 TITLE.ERB:79 的 `radius='4px'`、神签.ERB:80 的 `radius='12px'`），
     * 子行的内容盒 clip 同样按直角矩形 —— 圆角处的边框斜切与像素级抗锯齿不复刻，
     * 登记在 verify-notes §33 的消费端条目（TODO(verify)：真机截图对拍）。
     * `bcolor` 没写（borderColorArgb 空表）的边框回落 `Config.ForeColor` 出厂值
     * （DrawBorder :94/:111 的 colors == null 支；真机配置 ForeColor 改动后的回退差同条登记）。
     */
    private fun drawDivBlock(
        canvas: Canvas,
        block: DivOverlayPlanner.Block,
        contentLeft: Float,
        contentTop: Float,
        blockIndex: Int,
        focusedDiv: ButtonLayer.DivFocus?,
        pressedDiv: ButtonLayer.DivFocus?,
    ) {
        val rect = block.borderRectPx
        if (rect.width > 0 && rect.height > 0) {
            val bg = block.backgroundArgb
            if (bg != null) {
                val left = contentLeft + rect.x
                val top = contentTop + rect.y
                val right = left + rect.width
                val bottom = top + rect.height
                paint.style = Paint.Style.FILL
                paint.color = bg
                val radii = cornerRadii(block.radiusPx, rect)
                if (radii != null) {
                    roundRectPath.reset()
                    roundRectPath.addRoundRect(
                        RectF(left, top, right, bottom),
                        radii,
                        Path.Direction.CW,
                    )
                    canvas.drawPath(roundRectPath, paint)
                } else {
                    canvas.drawRect(left, top, right, bottom, paint)
                }
            }
            drawDivBorder(canvas, block, contentLeft, contentTop)
        }
        val box = block.contentBoxPx
        if (box.width <= 0 || box.height <= 0) return
        canvas.save()
        canvas.clipRect(
            contentLeft + box.x,
            contentTop + box.y,
            contentLeft + box.x + box.width,
            contentTop + box.y + box.height,
        )
        // 子行序号就是 [ButtonLayer.DivFocus.childRow]（0 起，`ConsoleDivPart.cs:197-203` 的
        // children 序——折行后按**可视行**编号）；截断后仍按原序号，不因早退重编号。
        for ((childRow, child) in block.children.withIndex()) {
            // 内容盒底以下不画（原版 SetClip 到内容盒，ConsoleDivPart.cs:195；y 升序可断）。
            if (child.yPx >= box.y + box.height) break
            // 行对齐偏移（SetAlignment 的 customWidth 口径）折像素加到行左沿；
            // 原版把偏移写进 PointX（ConsoleDisplayLine.cs:80-90），命中侧同式。
            val originX = contentLeft + child.xPx + child.alignOffsetCells * halfCellPx
            for (cmd in child.cmds) {
                drawDivCmd(
                    canvas, cmd,
                    originX,
                    contentTop + child.yPx,
                    lineHeightPx,
                    blockIndex, childRow,
                    focusedDiv, pressedDiv,
                )
            }
        }
        canvas.restore()
    }

    /**
     * 画 div 子行的一段命令（HTMLDIV-B2）：底色 → 按下叠色 → 文字或图形。
     *
     * 原版 div 子行与普通行共用 `ConsoleDisplayLine.DrawTo`（`ConsoleDivPart.cs:198-203`
     * 对 children 逐行 `DrawTo`），子行里的每个按钮串自己算 `isFocus` / `isSelecting`
     * （`ConsoleButtonString.cs:317-320`，按按钮对象身份比、与节点在不在 div 内无关），
     * 再逐节点透传（`:363`）。四支消费点：
     * - 文字换色：`ConsoleStyledString.cs:105-112` 的 isFocus 支（`ButtonColor`）；
     * - 位图换 `srcb`：`ConsoleImagePart.cs:163-167` 的 `(isSelecting || isFocus) && cImageB != null`；
     * - 色块换 `ButtonColor`：`ConsoleRectangleShapePart.cs:45` 的 `isSelecting ? ButtonColor : Color`；
     * - 按下观感：本端口沿用 [drawCmd] 的既有口径，叠 [RunChrome.PRESSED_BACK] 半透明白。
     *
     * 本端口把命中层的两个身份合成一个高亮位：[ButtonLayer.DivFocus] 的 `focused`
     * （指针所在，对应原版 isSelecting/isFocus 的合并通道）与 `pressed`（按下，另叠底色）。
     * 几何判定见 [divFocusCovers]。
     *
     * @param originX 子行左沿（`contentLeft + child.xPx`，内容区坐标）
     * @param rowTop 子行行条上沿（`contentTop + child.yPx`）
     * @param rowHeight 行高（div 子行恒 = `Config.LineHeight`，`ConsoleDivPart.cs:202`）
     * @param blockIndex 本块的全局块号（`Plan.all` 拼接序，见 [drawDivBlocks]）
     * @param childRow 本子行的序号（0 起，`ConsoleDivPart.cs:197-203` 的 children 序）
     */
    private fun drawDivCmd(
        canvas: Canvas,
        cmd: DrawCmd,
        originX: Float,
        rowTop: Float,
        rowHeight: Float,
        blockIndex: Int,
        childRow: Int,
        focusedDiv: ButtonLayer.DivFocus?,
        pressedDiv: ButtonLayer.DivFocus?,
    ) {
        val focused = divFocusCovers(focusedDiv, blockIndex, childRow, cmd.colStart, cmd.colEnd)
        val pressed = divFocusCovers(pressedDiv, blockIndex, childRow, cmd.colStart, cmd.colEnd)
        val highlighted = focused || pressed
        val graphic = cmd.graphic
        if (graphic != null) {
            // 图形段绘制原点 = 列游标 + ShapePositionShift（`ConsoleImagePart.cs:171`），
            // 与正文 [drawCmd] 的图形段同一个算式（取色侧同式，W6-H6 的「一把尺」）。
            val originLeft = MappingColorGeometry.graphicOriginLeftPx(
                originX, cmd.colStart, halfCellPx, shapeShiftPx,
            )
            drawDivGraphic(canvas, graphic, originLeft, rowTop, highlighted, cmd.focusFore)
            return
        }
        val left = originX + cmd.colStart * halfCellPx
        val right = originX + cmd.colEnd * halfCellPx
        paint.style = Paint.Style.FILL
        // 子行底色：HtmlRunFlatten 产出的 [DrawCmd.back] 就是样式底色（不是正文 run 的
        // ButtonColor 槽 —— 那条约定只在 ConsoleAdapter.runOf 上），照 run 直接铺。
        val back = cmd.back
        if (back != null) {
            paint.color = opaque(back)
            canvas.drawRect(left, rowTop, right, rowTop + rowHeight, paint)
        }
        if (pressed) {
            // 按下高亮叠在底色之上（半透明白，与正文 [drawCmd] 同一常量、同一层序）。
            paint.color = RunChrome.PRESSED_BACK
            canvas.drawRect(left, rowTop, right, rowTop + rowHeight, paint)
        }
        drawCmdText(canvas, cmd, originX, rowTop, if (highlighted) cmd.focusFore else cmd.fore)
    }

    /**
     * 命中层的 div 焦点/按下条目是否覆盖本 run：块号 + 子行号相等、格区间相交
     * （左闭右开，口径同 [drawCmd] 的按下判定）。
     *
     * @param focus 命中层条目 [ButtonLayer.DivFocus]；null = 本帧没有焦点/按下按钮
     * @param blockIndex 本块在 `Plan.all` 拼接序里的全局块号（与命中层同一套编号，
     *   组长裁定 2026-11-06；见 [drawDivBlocks]）
     * @param childRow 子行序号（0 起）
     * @param colStart / colEnd 本 run 在子行内的格区间（[DrawCmd.colStart] / [DrawCmd.colEnd]）
     */
    private fun divFocusCovers(
        focus: ButtonLayer.DivFocus?,
        blockIndex: Int,
        childRow: Int,
        colStart: Int,
        colEnd: Int,
    ): Boolean = focus != null && focus.blockIndex == blockIndex &&
        focus.childRow == childRow && colStart < focus.colEnd && focus.colStart < colEnd

    /**
     * 画 div 子行里的一段内联图形（位图 / 色块 / 空白）。
     *
     * 原版 div 子行与普通行走**同一条** `ConsoleDisplayLine.DrawTo`，所以落位照
     * `ConsoleImagePart.DrawTo`：`rectangle.X = destRect.X + PointX +
     * Config.DrawingParam_ShapePositionShift`、`rectangle.Y = destRect.Y + pointY`
     * （`ConsoleImagePart.cs:170-173`）；色块照 `ConsoleRectangleShapePart.DrawTo`
     * （`ConsoleRectangleShapePart.cs:40-47`：`X += PointX`、`Y += pointY`，
     * 按下时填充色换 `ButtonColor`，`:45`）。
     *
     * 与正文 [drawGraphic] 的差异只有两处，都是 div 路径的口径：
     * - 色块高亮换色（正文路径只画原色；这里是本波交付项，`:45`）；
     * - 查不到资源时**静默跳过**（div 子行不画 altText 回落 —— 原版 `ConsoleImagePart.cs:175-182`
     *   的回落是正文路径既有行为，div 侧不接，登记 TODO(verify)）。
     * 裁剪已在调用方 [drawDivBlock] 的 save/clipRect（内容盒，`ConsoleDivPart.cs:196`），
     * 负 x/y 由裁剪静默吃掉 —— **不夹到 0**（`xpos` 允许为负，语料
     * `TEMP_CHARA_LIST_BY_DT.ERB:641` 就是负 xpos）。
     *
     * @param originLeft 图形段绘制框左沿（列游标 + ShapePositionShift 的成品，见 [drawDivCmd]）
     * @param highlighted 本段是否处于焦点/按下态（位图换 srcb、色块换 ButtonColor）
     * @param buttonColor 该段按钮的 `ButtonColor`（[DrawCmd.focusFore]）
     */
    private fun drawDivGraphic(
        canvas: Canvas,
        graphic: InlineGraphic,
        originLeft: Float,
        rowTop: Float,
        highlighted: Boolean,
        buttonColor: Int,
    ) {
        when (graphic) {
            is InlineGraphic.Space -> Unit
            is InlineGraphic.Rect -> {
                // 段自带 xOffsetPx 是 4 参形态的脚本偏移（`ConsoleShapePart.cs:63`）；绘制期
                // 的 PointX 与测量期烘焙的 shift 都已在 [originLeft] 里（`ConsoleRectangleShapePart.cs:43,57`）。
                val x0 = originLeft + graphic.xOffsetPx
                val top = rowTop + graphic.yOffsetPx
                val right = x0 + graphic.widthPx
                val bottom = top + graphic.heightPx
                if (right <= x0 || bottom <= top) return
                paint.style = Paint.Style.FILL
                val stops = graphic.gradientArgb
                // 高亮时**不**画渐变：原版 ConsoleRectangleShapePart 没有渐变形态（渐变是本端口
                // 对逐块 `<shape>` 的合并近似，见 InlineGraphic.Rect 的 KDoc），高亮只换填充色
                // ButtonColor（`ConsoleRectangleShapePart.cs:45`）。
                if (!highlighted && stops != null && stops.size >= 2 && graphic.widthPx > 0) {
                    paint.shader = android.graphics.LinearGradient(
                        x0, top, right, top, stops, null, android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    paint.shader = null
                    // 高亮取 ButtonColor（原版 `isSelecting ? base.ButtonColor : base.Color`）；
                    // 非高亮取段自带色（HtmlRunFlatten 的 colorOf 已补不透明位）。
                    paint.color = if (highlighted) opaque(buttonColor) else graphic.colorArgb
                }
                canvas.drawRect(x0, top, right, bottom, paint)
                paint.shader = null
            }
            is InlineGraphic.Image -> drawDivImage(canvas, graphic, originLeft, rowTop, highlighted)
        }
    }

    /**
     * 画 div 子行里的一张内联位图（`ConsoleImagePart.DrawTo` 的位图分支）。
     *
     * 与正文 [drawImage] 共用同一套几何原语（[ImageFrameSource.frame] →
     * [GraphicGeometry.inlineSize] → [GraphicGeometry.sourceRectClipped] →
     * [GraphicGeometry.spriteDestRect]），但不走纯图行压缩：div 子行行距恒为
     * `Config.LineHeight`（`ConsoleDivPart.cs:202`），图照 `destRect` 原尺寸画、向下溢出，
     * 由调用方内容盒裁剪（`ConsoleDivPart.cs:196`）。换图判据照
     * `ConsoleImagePart.cs:163-167` 的 `(isSelecting || isFocus) && cImageB != null`
     * （取图入口 [InlineGraphic.Image.drawResource]；按下图查不到时退主图，
     * `GetSprite` 对未注册名返回 null ⇒ `cImageB == null` 的判式落空，`AppContents.cs:46-48`）。
     *
     * @param highlighted 焦点或按下（两者都换 `srcb` 图，取不到退主图）
     */
    private fun drawDivImage(
        canvas: Canvas,
        image: InlineGraphic.Image,
        originLeft: Float,
        rowTop: Float,
        highlighted: Boolean,
    ) {
        // 竖带块（ImageBandMerge 产物）：div 子行理论上不出现（合并在正文绘制路径开），
        // 但同一份 Image 形态可能带着 bandResources，按既有原语堆叠，行为可预期。
        image.bandResources?.let {
            drawBandStack(canvas, image, it, originLeft, rowTop)
            return
        }
        val source = frameSource ?: return
        val requested = image.drawResource(highlighted)
        var frame = source.frame(requested)
        if (frame == null && requested != image.resource) {
            // 按下图缺失退主图（同 drawImage：GetSprite 未注册名返回 null）。
            frame = source.frame(image.resource)
        }
        val found = frame ?: return
        val bitmap = found.handle as? Bitmap ?: return
        val w = GraphicGeometry.inlineSize(image.widthPx, found.width)
        val h = GraphicGeometry.inlineSize(image.heightPx, found.height)
        if (w <= 0 || h <= 0) return
        val src = GraphicGeometry.sourceRectClipped(
            found.srcX, found.srcY, found.srcW, found.srcH, bitmap.width, bitmap.height,
        ) ?: return
        srcRect.set(src[0], src[1], src[2], src[3])
        val top = rowTop + image.yOffsetPx
        // 落位框（ASpriteSingle.cs:79-85 / SpriteAnime.cs:153-156），div 子行不缩（缩放系数恒 1）。
        val place = GraphicGeometry.spriteDestRect(
            w, h, found.destW, found.destH,
            found.basePosX, found.basePosY, found.offsetX, found.offsetY,
            found.srcW, found.srcH, found.anime,
        )
        val left = originLeft + place[0]
        val drawTop = top + place[1]
        dstRect.set(left, drawTop, left + place[2], drawTop + place[3])
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        paint.isFilterBitmap = false
    }

    /** [drawDivBlock] 复用的圆角路径（每块重建内容，实例字段省分配）。 */
    private val roundRectPath = Path()

    /**
     * 逐角圆角半径（`Path.addRoundRect` 的 8 浮点序：LT/RT/RB/LB 各一对 rx, ry）。
     * [radiusPx] 是 CSS 序四角（CDP 的 radius 表；空表/全 0 返回 null 走直角）。
     */
    private fun cornerRadii(radiusPx: List<Int>, rect: DivOverlayPlanner.Rect): FloatArray? {
        if (radiusPx.size < 4) return null
        val maxR = maxOf(radiusPx[0], radiusPx[1], radiusPx[2], radiusPx[3])
        if (maxR <= 0) return null
        // 半径压到半短边内（GDI 的 GraphicsPath 对超半径圆角同样收敛到椭圆内切）。
        val limit = minOf(rect.width, rect.height) / 2f
        fun r(i: Int): Float = radiusPx[i].toFloat().coerceAtMost(limit)
        return floatArrayOf(
            r(0), r(0),
            r(1), r(1),
            r(2), r(2),
            r(3), r(3),
        )
    }

    /**
     * 真机的四边框条带：宽度 0 不画（DrawBorder :92/:109）、bcolor 没写回落
     * `Config.ForeColor` 出厂值（:94/:111；与 headless 的 ScreenComposer 同一条回落口径）。
     * 直角矩形近似见 [drawDivBlock] 的类文档。
     */
    private fun drawDivBorder(
        canvas: Canvas,
        block: DivOverlayPlanner.Block,
        contentLeft: Float,
        contentTop: Float,
    ) {
        val widths = block.borderWidthsPx
        if (widths.size < 4) return
        val rect = block.borderRectPx
        val colors = block.borderColorArgb
        paint.style = Paint.Style.FILL
        for (edge in 0 until 4) {
            val w = widths[edge]
            if (w <= 0) continue
            paint.color = if (edge < colors.size) colors[edge] else
                (0xFF shl 24 or (DIV_BORDER_FALLBACK_RGB and 0xFFFFFF))
            when (edge) {
                0 -> canvas.drawRect(
                    contentLeft + rect.x, contentTop + rect.y,
                    contentLeft + rect.x + rect.width, contentTop + rect.y + w, paint,
                )
                1 -> canvas.drawRect(
                    contentLeft + rect.x + rect.width - w, contentTop + rect.y,
                    contentLeft + rect.x + rect.width, contentTop + rect.y + rect.height, paint,
                )
                2 -> canvas.drawRect(
                    contentLeft + rect.x, contentTop + rect.y + rect.height - w,
                    contentLeft + rect.x + rect.width, contentTop + rect.y + rect.height, paint,
                )
                else -> canvas.drawRect(
                    contentLeft + rect.x, contentTop + rect.y,
                    contentLeft + rect.x + w, contentTop + rect.y + rect.height, paint,
                )
            }
        }
    }

    /**
     * 取（或重建）与 View 等大的保留面。尺寸没变就是同一个对象，稳态帧零分配。
     *
     * 重建时做三件事：[ScreenSurfacePlanner.notifySurfaceLost]（下一帧必然整幅）、
     * [rowCache] 全清（烤图是按旧行高/内容宽算的，换了版面不许再贴）、底色铺满。
     *
     * 旧位图**只丢引用不 recycle**：贴图是异步上屏的，硬件渲染器可能还持有这一帧的引用，
     * 在 UI 线程 recycle 会撞「Canvas: trying to use a recycled bitmap」。API 26 起位图像素
     * 在 native 堆上、由 GC 可靠回收，丢掉引用即可。
     */
    private fun ensureScreenSurface(bgArgb: Int): Canvas? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        var bmp = screenSurface
        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
            bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (t: OutOfMemoryError) {
                // 保留面是 native 位图，**不做 Java 堆头寸预检**（Android 不暴露单进程 native 上限，
                // 没有可对的分母，理由逐字见 `platform/.../SpriteBitmapSource.kt` 的
                // `SHEET_CACHE_BUDGET_BYTES` 与 `app/.../GraphicsTrimPlan.kt` 的「水位判据只看 Java 堆」）。
                // 能做的只有两件：先把本屏自有、可重建的那两块 native（行栅格缓存 + 行位图池，
                // 最多 30 MiB）交出去，再按「这一帧没有保留面」的既有出口返回 null
                // —— 调用方 `drawScreen` 的 `?: return` 就是这条降级支，下一帧自然重试。
                // 不兜这一支的话 OOM 是 Error，`onDraw` 那道 `catch (t: RuntimeException)` 拦不住，
                // 一帧画不出来会变成整局没了。
                android.util.Log.w(TAG, "整屏保留面 ${w}x$h native 分配不下：先收行缓存与行池，这一帧不画", t)
                releaseMemoryCaches(deep = false)
                return null
            }
            screenSurface = bmp
            screenSurfaceCanvas = Canvas(bmp)
            surfaceWidth = w
            surfaceHeight = h
            // 初铺就是当前整屏底色（SETBGCOLOR 活值；surface 重建时 planner 已 notifySurfaceLost
            // ⇒ 下一帧整幅，不会读到这个色的陈旧帧）。
            bmp.eraseColor(bgArgb)
            planner.notifySurfaceLost()
            rowCache.clear()
        }
        return screenSurfaceCanvas
    }

    /** 把保留面整幅贴到 [onDraw] 传入的画布上 —— 每帧必做，无条件。 */
    private fun blitSurface(canvas: Canvas) {
        val bmp = screenSurface ?: return
        if (bmp.isRecycled) return
        canvas.drawBitmap(bmp, 0f, 0f, blitPaint)
    }

    /**
     * 把一个纯文本行栅格化进保留面 [target]。
     *
     * 口径与旧实现的逐行绘制逐项相同：`save → clipRect(行区间) →（增量帧才）drawColor(底色)
     * →（增量帧且真有背景层才）铺背景 → 逐段 drawCmd → restore`。整幅帧由帧首统一铺底与背景，
     * 行带不再清 —— 再清一次就会把背景像素擦掉。新增的只有一层：多于一段的行先查
     * [rowCache]，命中就贴烤好的行位图，未命中则烤一张再贴 —— 烤图**只含本行自身内容**
     * （透明底），底色与背景仍然按帧重铺，所以命中与直画两条路逐像素同形；同形的前提是
     * 行顶落在整像素栅格上，带小数的行一律不进缓存（见下方 cacheable 条件）。
     *
     * 走到这里必然没有内联图形（有图形的帧在 [drawScreen] 上半段就走了整屏路），
     * 因此烤图的坐标可以是「行内相对坐标」：x 以 [contentLeft] 为 0、y 以行顶为 0。
     */
    private fun rasterTextRow(
        target: Canvas,
        cmds: List<DrawCmd>?,
        sig: Int,
        contentLeft: Float,
        contentTop: Float,
        contentRight: Float,
        contentBottom: Float,
        rowTopPx: Float,
        rowHeightPx: Float,
        /**
         * 本帧整幅帧已把 surface 铺成「底色加背景、无任何旧文字」：行带**不许再清**。
         * 整幅帧再逐行 `drawColor` 会把这一行的背景层像素擦成纯底色 —— 那是 2026-10-26
         * 复审抓到的回归（背景层只在帧首铺一次，行带一清就露出黑底）。
         * 只有增量帧（行带里还留着上一帧的旧文字）才需要清。
         */
        surfacePreCleared: Boolean,
        /** 本帧整屏底色（`ConsoleScreen.backgroundArgb`，SETBGCOLOR 活值）；增量帧行带清回这个色。 */
        bgArgb: Int,
        pressedRow: Int,
        pressedLo: Int,
        pressedHi: Int,
    ) {
        val clipTop = rowTopPx.coerceAtLeast(contentTop)
        val clipBottom = (rowTopPx + rowHeightPx).coerceAtMost(contentBottom)
        if (clipBottom <= clipTop) return
        target.save()
        target.clipRect(contentLeft, clipTop, contentRight, clipBottom)
        if (!surfacePreCleared) {
            // 增量帧只重画这一行：先把行带擦回底色，再（若真有背景层）在行带内补回来。
            // 当前判定器对含背景的帧一律整幅，这条背景分支是防御性的 —— 万一哪天放开了
            // 增量，也不至于把背景擦掉。
            target.drawColor(bgArgb)
            if (graphicsLayer?.background?.isNotEmpty() == true) {
                drawBackgroundsMeasured(target, contentLeft, contentTop, contentRight, contentBottom)
            }
        }
        if (cmds == null || cmds.isEmpty()) {
            target.restore()
            return
        }
        // 只烤「多于一段」的行 —— 原版同条件（ConsoleButtonString.cs:322 判 parts>1）。
        // 单段行重放一次 drawText 比「一次分配 加 一次贴图」还便宜，烤了反而慢。
        // 再加一条整数栅格条件：行顶必须是整像素。行位图里的字形是按「行顶 = 0」
        // 烤死的，贴图时只能整体平移；行顶带小数（立绘行把后面的行推到半像素上）时，
        // 「烤图 加 小数平移」与「直接在该小数位置画字」的纵向抗锯齿相位不同，
        // 同屏会出现粗细不均的字。宁可这一行不进缓存，也不让像素形态随缓存命中而变。
        // 横向由 contentLeft（内容左沿 [contentLeftPx]，整数像素）与列量化保证，无需额外条件。
        val cacheable = cmds.size > 1 && rowTopPx % 1f == 0f
        val wPx = (contentRight - contentLeft).roundToInt()
        val hPx = ceil(rowHeightPx.toDouble()).toInt()
        // `rowBakeBlocked`：本帧已经有一行烤不出位图（native 分配不下）⇒ 后面那些行不再各试一次，
        // 一律走下面的逐段直画支。少这一枚闸，一屏 34 行就是 34 次失败的分配 + 34 行 logcat +
        // 34 次 releaseMemoryCaches，而画面结果完全一样（都是直画）。
        if (cacheable && !rowBakeBlocked && wPx > 0 && hPx > 0) {
            val hit = rowCache.find(sig, hPx, wPx, cmds.size)
            if (hit != null) {
                target.drawBitmap(hit.value, contentLeft, rowTopPx, blitPaint)
                target.restore()
                return
            }
            val bmp = obtainRowBitmap(wPx, hPx)
            if (bmp != null) {
                val rc = rowCanvas
                rc.setBitmap(bmp)
                bmp.eraseColor(0) // 0 = 透明：烤图只留本行自己的像素
                rc.save()
                for (cmd in cmds) {
                    // 画布 y=0 是行顶（不是 View 顶）⇒ 关掉按 View 绝对坐标的可见性早退，
                    // 否则内边距高过一行高时整行会被静默画空并被缓存下来。
                    drawCmd(
                        rc, cmd, 0f, 0f, rowHeightPx, false,
                        pressedRow, pressedLo, pressedHi, viewportGated = false,
                    )
                }
                rc.restore()
                rc.setBitmap(null)
                // 先贴后收：put 可能因单行超预算直接拒收（拒收会把位图送回池），
                // 但那一帧的画面必须上屏。
                target.drawBitmap(bmp, contentLeft, rowTopPx, blitPaint)
                rowCache.put(sig, bmp, hPx, wPx, cmds.size)
                target.restore()
                return
            }
            // 行位图拿不到（native 分配不下）：这一行不进缓存，落到下面的逐段直画支。
        }
        for (cmd in cmds) {
            drawCmd(
                target, cmd, contentLeft, rowTopPx, rowHeightPx,
                false, pressedRow, pressedLo, pressedHi,
            )
        }
        target.restore()
    }

    /**
     * 取一张 [w]x[h] 的行位图：同尺寸的回收池优先，没有再新建。
     *
     * 新建拿不到时返回 **null**（native 分配不下）—— 行烤图是**性能面**，不是画面的唯一来源：
     * 调用方 [rasterTextRow] 拿到 null 就落回它本来就有的「逐段直画进保留面」那一支，
     * 一行少一张烤图、一个字都不少。这一支必须存在，否则 `Bitmap.createBitmap` 的
     * `OutOfMemoryError` 会从 `onDraw` 里穿过（那道 `catch (t: RuntimeException)` 拦不住 Error），
     * 烤一行行图这种纯优化动作就能把整局带走。
     */
    private fun obtainRowBitmap(w: Int, h: Int): Bitmap? {
        val pool = rowBitmapPool
        for (i in pool.indices) {
            val b = pool[i]
            if (!b.isRecycled && b.width == w && b.height == h) {
                pool.removeAt(i)
                rowPoolBytes -= b.allocationByteCount.toLong()
                return b
            }
        }
        return try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (t: OutOfMemoryError) {
            android.util.Log.w(TAG, "行位图 ${w}x$h native 分配不下：这一行起本帧都不再烤，改逐段直画", t)
            rowBakeBlocked = true
            releaseMemoryCaches(deep = false)
            null
        }
    }

    /**
     * 行位图的去处：池子未满就留着复用，否则丢掉引用（同 [ensureScreenSurface] 的理由，
     * 不主动 recycle —— 异步渲染器可能还引用着这一帧贴出去的位图）。
     *
     * 池子按**张数与字节双上限**收：一张 2400×44 的行位图就是 422 KB，只看 48 张的上限
     * 会让池子单独占掉 20 MB（还没算 12 MB 的缓存与整屏保留面）。
     */
    private fun poolOrDropRowBitmap(bmp: Bitmap) {
        if (bmp.isRecycled) return
        val cost = bmp.allocationByteCount.toLong()
        if (rowBitmapPool.size >= ROW_POOL_MAX) return
        if (rowPoolBytes + cost > ROW_POOL_MAX_BYTES) return
        rowBitmapPool.add(bmp)
        rowPoolBytes += cost
    }

    /**
     * 画整屏背景：稳态只做 1:1 贴 [backing]，脏了才经 [repaintBacking] 重采样。
     *
     * 原版把背景层烘进一张 bakedBackground，在 `graph.Clear(bgColor)` 之后、文本之前
     * 整幅贴上去（spec: `EmueraConsole.cs:1816-1820`）；重烘只在背景内容变化
     * （Add/Clear/Remove 后各调一次 BakeBackground，`:907`/`:914`/`:921`）或
     * 首次/尺寸变化（OnPaint 每帧的 ValidateBackground，`:923-936`）发生，
     * 帧间只是 `DrawImage(bakedBackground, 0, 0)`，绝不重采样。单条层的摆位是
     * **等比适配 + 水平居中 + 顶对齐**（cover 而非 contain，`:949-951`），顺序按
     * depth 降序（`:904` 的比较器），每层乘一个不透明度（`:953-955`，本侧用
     * [Paint.alpha]，GDI+ 的 ColorMatrix 是逐通道乘 alpha，视觉等价）。
     * 本类早先把这套几何**每帧**逐层重画一遍（有背景时中低端机掉帧发热），
     * 现在几何只在重烘帧算一次；脏判定的逐条口径见 [BackgroundBakeGuard]。
     *
     * 与逐层直画的可见结果一致性：alpha 合成满足结合律 —— 先逐层叠进透明位图、
     * 再一次性叠到背景色，与逐层直接叠到背景色同值；烘焙坐标以内容区左上为原点、
     * 贴图时加回同一 content 原点（均为整数），落点逐像素同位。中间位图的预乘
     * 量化最多带来半透明边缘 ±1/255 的差（原版烘焙路径同构，非逐位同值）。
     */
    private fun drawBackgrounds(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val layer = graphicsLayer ?: return
        val source = frameSource ?: return
        val backgrounds = layer.background
        if (backgrounds.isEmpty()) return
        val areaW = right - left
        val areaH = bottom - top
        // 内容区宽高都是整数像素，取整无损；对齐 ValidateBackground 的 (int)clipBounds 口径
        val w = areaW.roundToInt().coerceAtLeast(1)
        val h = areaH.roundToInt().coerceAtLeast(1)
        // 自愈：位图不在（例如刚被 onDetachedFromWindow / releaseSession 交还给 GC，而判定
        // 还没来得及作废）时不能只信 [bakeGuard] —— 原版 ValidateBackground 的 null 分支
        // （spec: `EmueraConsole.cs:924-927`）就是「没有 bakedBackground 就重建重烘」。
        // 少了这一条，脏判定一旦与位图生命周期脱节，背景就永久不贴（取不到位图就直接 return）。
        if (backing == null ||
            bakeGuard.needsRebake(w, h, backgrounds) { resource -> source.frame(resource) }
        ) {
            repaintBacking(w, h, backgrounds, source)
            bakeGuard.markBaked(w, h, backgrounds) { resource -> source.frame(resource) }
        }
        val bmp = backing ?: return
        // 1:1 整幅贴图（对齐 `:1816-1820` 的 DrawImage(bakedBackground, 0, 0)）
        canvas.drawBitmap(bmp, left, top, paint)
    }

    /**
     * [drawBackgrounds] 的计时包装（读数 `f.bakeNs`）：唯一调用口径。
     * 稳态帧就是一次 1:1 贴图；重烘帧把 [repaintBacking] 的重采样也算进来。
     */
    private fun drawBackgroundsMeasured(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val startNs = System.nanoTime()
        drawBackgrounds(canvas, left, top, right, bottom)
        dbgFrameBakeNanos += System.nanoTime() - startNs
    }

    /**
     * 画 **一条 CBG 图层**（`CBGSETG` / `CBGSETSPRITE` / `CBGSETBUTTONSPRITE`）。
     *
     * ## 语义（原版对照）
     *
     * - 层序由 [CbgDrawOrder.merge] 在调用侧给出（与 headless `ScreenComposer` 同一次归并，
     *   `EmueraConsole.cs:1834-1872` 的三侧单趟 depth 序），本方法只管**一条**。
     * - 落位：`x` 直取客户区 x；`y` 是**下沿锚定偏移** ⇒
     *   `destY = 客户区顶 + y + MainPicBox.Height - 锚点高`（`EmueraConsole.cs:1846`）；
     *   锚点高 = `DestBaseSize.Height`（[CbgLayerSnapshot.baseHeightPx]，动画层**不是**帧高），
     *   0 时退落位高。
     * - 尺寸：动画层用**当前帧** `SrcRectangle.Size`（`SpriteAnime.cs:137-146`）+ `Offset`
     *   额外平移；静态层用快照 `widthPx/heightPx`，为 0 退图口帧尺寸。都是脚本像素，
     *   经 [CellMetrics.scriptPixels] 折设备像素（只折一次）。
     * - `frameIndex == NO_FRAME` ⇒ **整层跳过**（`SpriteAnime.GetCurrentFrame` 返回 null，
     *   `SpriteAnime.cs:92-95` + `:139-145`）；动画层帧号越界同样跳过。
     * - 按下态换图：层是按钮且取色值 == [cbgPressedValue] 时改画
     *   [CbgLayerSnapshot.spriteNameB]（`EmueraConsole.cs:1840-1843`）；无第二张图照画主图。
     * - 未登记/未创建：静默跳过（`EmueraConsole.cs:1844`）。
     * - **gid 路**：core 只给 gid，像素由 `frameByGid` 从 `GraphicsPool` 现读
     *   （`ImageFrameSource.frameByGid`，platform 只查不建）。
     * - CBG **不随滚动**：它是整屏层，不是行锚定层（行锚定那条是 `SETIMAGELAYERL`）。
     */
    private fun drawCbgLayer(
        canvas: Canvas,
        layer: CbgLayerSnapshot,
        contentLeft: Float,
        contentTop: Float,
        nowMs: Long,
    ) {
        if (layer.frameIndex == CbgLayerSnapshot.NO_FRAME) return
        val pressed = cbgPressedValue != null && layer.isButton && layer.buttonValue == cbgPressedValue
        val name = if (pressed && layer.spriteNameB != null) layer.spriteNameB else layer.spriteName
        // 帧几何：动画层走共享时钟取帧；静态层（frames 空）返回 null。
        val frame = CbgDrawOrder.frameGeometry(layer, if (layer.frames.isEmpty()) null else nowMs)
        if (frame == null && layer.frames.isNotEmpty()) return
        val source = frameSource ?: return
        val imageFrame = if (name != null) source.frame(name) else source.frameByGid(layer.gid)
        if (imageFrame == null) return
        val bmp = imageFrame.handle as? Bitmap ?: return
        // 动画层落位尺寸 = 当前帧 `SrcRectangle.Size`（`SpriteAnime.cs:144`）。**帧矩形被裁空
        // （srcW/srcH = 0）时原版 DrawImage 一个像素都不画** —— 不能回落到整张位图。
        val destWScript: Int
        val destHScript: Int
        if (frame != null) {
            if (frame.widthPx <= 0 || frame.heightPx <= 0) return
            destWScript = frame.widthPx
            destHScript = frame.heightPx
        } else {
            destWScript = layer.widthPx.takeIf { it > 0 } ?: imageFrame.width
            destHScript = layer.heightPx.takeIf { it > 0 } ?: imageFrame.height
        }
        if (destWScript <= 0 || destHScript <= 0) return
        // 下沿锚点高：动画层 = DestBaseSize.Height（不是帧高），否则用落位高。
        val anchorHScript = layer.baseHeightPx.takeIf { it > 0 }
            ?: layer.heightPx.takeIf { it > 0 }
            ?: destHScript
        val w = metrics.scriptPixels(destWScript, sourceFontPixelSize).toFloat()
        val h = metrics.scriptPixels(destHScript, sourceFontPixelSize).toFloat()
        val anchorH = metrics.scriptPixels(anchorHScript, sourceFontPixelSize).toFloat()
        val src = GraphicGeometry.sourceRectClipped(
            imageFrame.srcX, imageFrame.srcY, imageFrame.srcW, imageFrame.srcH, bmp.width, bmp.height,
        ) ?: return
        srcRect.set(src[0], src[1], src[2], src[3])
        val dx = contentLeft + metrics.scriptPixels(layer.x + (frame?.offsetX ?: 0), sourceFontPixelSize)
        val dy = contentTop + metrics.scriptPixels(layer.y + (frame?.offsetY ?: 0), sourceFontPixelSize) +
            clientHeightPx() - anchorH
        dstRect.set(dx, dy, dx + w, dy + h)
        paint.isFilterBitmap = true
        canvas.drawBitmap(bmp, srcRect, dstRect, paint)
        paint.isFilterBitmap = false
    }

    /**
     * 正文的**两趟铺墨**（缺陷 B 的原样搬入，逻辑一字未改）：第一趟铺「不出自己那一行」的
     * 命令，第二趟单独铺越出的图形段 —— 与原版同构（`EmueraConsole.cs:1850-1857` 先按行画，
     * `:1858-1872` 再补 `FilterEscaped` 的溢出部件，`:1870`）。单趟会让后面的字盖住图上
     * （玩家报的「历史里的动图被文本覆盖」）。
     *
     * 抽成方法只是为了给 [CbgDrawOrder.merge] 的 [CbgDrawOrder.Step.Text] 一个落笔点，
     * 遍历序、行闸、`escapesLine` 判据全部与抽出前逐字节相同。
     */
    private fun drawTextPasses(
        target: Canvas,
        frameCmds: List<DrawCmd>,
        layout: RowLayout.Plan,
        contentLeft: Float,
        pressedRow: Int,
        pressedLo: Int,
        pressedHi: Int,
    ) {
        for (cmd in frameCmds) {
            if (escapesLine(cmd)) continue
            val row = cmd.row
            if (row < 0 || row >= layout.tops.size) continue
            drawCmd(
                target, cmd, contentLeft, layout.tops[row], layout.heights[row],
                layout.artOnly[row], pressedRow, pressedLo, pressedHi,
            )
        }
        for (cmd in frameCmds) {
            if (!escapesLine(cmd)) continue
            val row = cmd.row
            if (row < 0 || row >= layout.tops.size) continue
            drawCmd(
                target, cmd, contentLeft, layout.tops[row], layout.heights[row],
                layout.artOnly[row], pressedRow, pressedLo, pressedHi,
            )
        }
    }

    /**
     * 画 `SETIMAGELAYERL` 图层（WIRE-3）：锚定**显示行**、随滚动跟随，画在文字之上。
     *
     * 几何与尺寸落位全在纯 JVM 的 [ImageLayerGeometry]（OD3 的下沉面），本方法只取帧、
     * 落 `drawBitmap`。公式出处 `specs/isa.yaml:2632-2643`（`x = ShapePositionShift + xpos`、
     * `y = GetLinePointY(lineNo) + LineHeight - ClientHeight + (imageHeight - LineHeight) + ypos`）；
     * **IMG-A4 I4 起走精确式** [ImageLayerGeometry.layerBoxPxExact]：把本地可证的
     * `GetLinePointY`（`EmueraConsole.cs:1786-1795`，`num2 = ScrollBar.Value - 1` 夹到
     * `Count - 1` = 最后一条可见逻辑行）展开代入后 `+LineHeight - ClientHeight` 与
     * `(ClientHeight - LineHeight)` 严格相消 ⇒ `y = imageHeight - LineHeight -
     * (num2 - lineNo)·LineHeight + ypos`，**不再引用锚行行顶的绝对坐标**（也就没有
     * 「tops 是内容区坐标、GetLinePointY 是窗口坐标」那一个内容区原点的差）。
     *
     * ## `(num2 - lineNo)` 的读口（坐标系必须同一把尺）
     *
     * - `num2` 的唯一权威 = [ConsoleScreen.viewport] 的
     *   `Viewport.bottomRingIndex()`（保留区行下标；空视口 -1）；
     * - 锚行下标 = `ConsoleScreen.visibleRowBase` + 本方法吃的绘制行号 [RowLayout.Plan.tops]
     *   的下标；`DisplayList` 超容量丢头行（`retainTail`）时清单整体前移，故再补
     *   `visibleRows().size - layout.tops.size` 抬回保留区坐标（今天恒 0，见 [rowLayout]）；
     * - 两者都换算到**保留区行下标**后求差（[ImageLayerGeometry.linesBelowAnchor]）。
     *
     * 注：`layout.tops` 恒按 `console.list.rowCount` 建行、其末行就是 `num2` 那一行，
     * 所以「`rowCount - 1 - 绘制行号`」与上面这条链在今天的每一种视口形态下读数相同；
     * 走视口读口是为了让口径挂在原版 `num2` 上，而不是挂在「保留列表末行恰好等于
     * 最后可见行」这个派生事实上（两者一旦分叉，前者仍是定义）。
     *
     * - `lineNo` 越界的层不画（口径同 [drawScreen] 的行可见性闸）；
     * - 尺寸：`width`/`height` 给正数就用脚本值，否则退图口帧尺寸（[GraphicGeometry.inlineSize]）；
     * - `height <= 0` 的回落已由 core 算好（`StatementExecutor.execSetImageLayerL`）；
     * - `opacity` 原样 0..255 ⇒ `Paint.alpha`（`FunctionIdentifier.cs:1539` 的 `/255f` 折算等价）；
     * - z 序 = [GraphicsLayer.imageLayers] 列表序（写侧 `LinkedHashMap` 插入序，同 depth 覆盖）。
     *   TODO(verify)：多层同 depth 遮挡次序无 PC 截图可比，暂按「后写者在上」。
     *
     * TODO(verify)：① 公式本体出自 fork 源（`Instraction.Child.cs:4199-4205`，本机 reference
     * 快照无该文件），以 `specs/isa.yaml` 的 SETIMAGELAYERL 条目为 pinned 依据；
     * ② `snap.lineNo` 是 **core 侧的显示行计数**（`StatementExecutor.execSetImageLayerL` 取
     * `displayedLines.size`，即原版 `Console.GetLineNo`，`EmueraConsole.cs:507`），**不是**保留
     * 列表下标 —— 只有「本页显示行数 < 窗口行数」时它才恰好落进 `layout.tops.indices`；历史
     * 攒过一屏后它大于表长，被上面那道越界闸静默丢弃（既有口径，本波按契约逐字保留）。
     * 换算面在 core/adapter（`displayedLines` 与 `LogBuffer.firstIndex` 的差、以及折行前后
     * 行数不同），需组长接线；③ 立绘行改高 / 滚动中的真机视觉读数，卡点＝无设备。
     */
    private fun drawImageLayers(
        canvas: Canvas,
        contentLeft: Float,
        layout: RowLayout.Plan,
        console: ConsoleScreen,
    ) {
        val source = frameSource ?: return
        val layers = graphicsLayer?.imageLayers ?: return
        if (layers.isEmpty()) return
        // 原版 num2（最后一条可见逻辑行）的权威读口，与 layout.tops / DrawCmd.row 同一坐标系
        // （保留区行下标）：Viewport.bottomRingIndex 就是 `ScrollBar.Value - 1` 夹到 `Count - 1`
        // （EmueraConsole.cs:1786-1795）。
        val lastVisibleRing = console.viewport.bottomRingIndex()
        val windowTopRing = console.visibleRowBase
        val droppedHeadRows = (console.visibleRows().size - layout.tops.size).coerceAtLeast(0)
        for (snap in layers) {
            val row = snap.lineNo
            if (row < 0 || row >= layout.tops.size) continue
            val frame = source.frame(snap.spriteName) ?: continue
            val bitmap = frame.handle as? Bitmap ?: continue
            val targetW = GraphicGeometry.inlineSize(snap.width, frame.width).toFloat()
            val targetH = GraphicGeometry.inlineSize(snap.height, frame.height).toFloat()
            if (targetW <= 0f || targetH <= 0f) continue
            val src = GraphicGeometry.sourceRectClipped(
                frame.srcX, frame.srcY, frame.srcW, frame.srcH, bitmap.width, bitmap.height,
            ) ?: continue
            val anchorRing = windowTopRing + droppedHeadRows + row
            val box = ImageLayerGeometry.layerBoxPxExact(
                contentLeftPx = contentLeft,
                imageHeightPx = targetH,
                lineHeightPx = lineHeightPx,
                linesBelowAnchor = ImageLayerGeometry.linesBelowAnchor(lastVisibleRing, anchorRing),
                shapeShiftPx = shapeShiftPx,
                xpos = snap.xpos,
                ypos = snap.ypos,
                targetWidthPx = targetW,
                targetHeightPx = targetH,
            )
            srcRect.set(src[0], src[1], src[2], src[3])
            dstRect.set(box[0], box[1], box[2], box[3])
            val prevAlpha = paint.alpha
            paint.isFilterBitmap = true
            paint.alpha = snap.opacity.coerceIn(0, 255)
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            paint.alpha = prevAlpha
            paint.isFilterBitmap = false
        }
    }

    /**
     * 画一段 [DrawCmd]。纵行顶 [rowTop] 来自 [RowLayout] 的行顶表（底对齐唯一纵源，见
     * [rowLayout]），绘制 / 命中 / 取色三路查同一张表。横向：文本段走裸列游标
     * （原版 `PointX` 域），**图形段走 [MappingColorGeometry.graphicOriginLeftPx]**
     * —— 列游标之上再加 [shapeShiftPx]（原版绘制口径加 shift：spec: `ConsoleImagePart.cs:171`、
     * `ConsoleRectangleShapePart.cs:57`，W6-H6 横向刀补齐，取色侧同式已对齐）。
     *
     * [pressedRow] / [pressedLo] / [pressedHi] 是**当前按下按钮**在绘制口径下的行与列区间
     * （-1 / 0 / 0 = 无按下），由 [drawScreen] 每帧查一次；覆盖到的 `RunKind.BUTTON` 段
     * 先铺半透明高亮底色，字色按原版焦点态换成 [DrawCmd.focusFore]（spec:
     * `ConsoleStyledString.cs:105-111` 的 `isFocus` 分支与 `:147-150` 的 `isSelecting` 分支，
     * 两处都把字色换成 `ButtonColor` = 默认 `Config.FocusColor` `0xFFFF00`）。
     * 未按下时保持原样（行底色 + 白色描边）。
     */
    private fun drawCmd(
        canvas: Canvas,
        cmd: DrawCmd,
        originX: Float,
        rowTop: Float,
        rowHeight: Float,
        pureArtRow: Boolean,
        pressedRow: Int,
        pressedLo: Int,
        pressedHi: Int,
        /**
         * 是否按「View 的绝对坐标」做可见性早退。烤行位图时传 false：那张画布的 y=0
         * 是**行顶**而不是 View 顶，拿 `paddingTop` 去比会把整行静默画空
         * （内边距一旦高过一行高就会全部落空，命中的还是这张空图）。
         */
        viewportGated: Boolean = true,
    ) {
        val rowBottom = rowTop + rowHeight
        if (viewportGated &&
            (rowBottom <= paddingTop.toFloat() || rowTop >= (height - paddingBottom).toFloat())
        ) {
            return
        }
        // 任务 perf-render2：本帧实际执行的绘制命令数（f.cmds）。放在可见性闸之后 = 只数真要画的。
        dbgFrameCmdCalls += 1L
        val graphic = cmd.graphic
        if (graphic != null) {
            // GFX-09：图形段与文本段共享同一按下态通道 —— 原版按钮里的 img 节点与文本片
            // 同住一条 ConsoleButtonString，绘制时拿到同一份 isSelecting/isFocus
            // （spec: ConsoleButtonString.cs:319-320,363）；命中层把按钮内图片列并进同一
            // 命中区间，这里只做几何相交、不限 kind（判式见 InlineGraphic.graphicPressed）。
            val graphicPressed = InlineGraphic.graphicPressed(cmd, pressedRow, pressedLo, pressedHi)
            // W6-H6 横向刀：图形段绘制原点 = 列游标 + ShapePositionShift（原版绘制口径
            // 加 shift：spec: ConsoleImagePart.cs:171、ConsoleRectangleShapePart.cs:57）。
            // 取色侧 mappingColor 走**同一个**函数，两把尺从此一把。
            drawGraphic(
                canvas, graphic,
                MappingColorGeometry.graphicOriginLeftPx(originX, cmd.colStart, halfCellPx, shapeShiftPx),
                rowTop, rowHeight, pureArtRow, cmd, graphicPressed,
            )
            return
        }
        val left = originX + cmd.colStart * halfCellPx
        val right = originX + cmd.colEnd * halfCellPx
        // 按下判定：行相等 + 列区间相交（半开区间，口径同 ButtonLayer 的命中区间）。
        val pressed = cmd.kind == RunKind.BUTTON && cmd.row == pressedRow &&
            cmd.colStart < pressedHi && pressedLo < cmd.colEnd
        paint.style = Paint.Style.FILL
        // 底色判定统一走 [RunChrome.backgroundFill]（纯 JVM 单一实现 + 单测钉死）：
        // 按钮段的 `back` 槽装的是 `ButtonColor`（焦点字色），不是背景色，非按下态不铺；
        // 非按钮段才是 `SETBGCOLOR` 的行背景色。
        val drawBack = RunChrome.backgroundFill(cmd, pressed)
        if (drawBack != null) {
            paint.color = drawBack
            canvas.drawRect(left, rowTop, right, rowBottom, paint)
        }
        // TEXT_BGC_ON 的文字衬底（WIRE-4）：铺在**本段文字之下、行背景之上**。
        // 全局快照 [GraphicsLayer.textBackground]；`rgb < 0` 已在 Bridge 折成 null（清除）。
        // 矩形/alpha 折算全在纯 JVM 的 [ImageLayerGeometry]（alpha = opacity% × 255 / 100）。
        val textBg = graphicsLayer?.textBackground
        if (textBg != null) {
            val bgRect = ImageLayerGeometry.textBackgroundRect(left, rowTop, right, rowBottom)
            paint.style = Paint.Style.FILL
            paint.color = ImageLayerGeometry.textBackgroundArgb(textBg.rgb, textBg.opacityPercent)
            canvas.drawRect(bgRect[0], bgRect[1], bgRect[2], bgRect[3], paint)
        }


        // 行内按钮**不画白描边**（2026-10-23 用户实机反馈「白描看起来很脏」）：
        // 可点性由按下高亮（[BUTTON_PRESSED_BACK]）与焦点字色（[cmd.focusFore]）表达，
        // 静态描边去掉后正文与选项的底色一致，视觉更干净。命中几何不受影响。

        drawCmdText(canvas, cmd, originX, rowTop, if (pressed) cmd.focusFore else cmd.fore)
    }

    /**
     * 逐字形铺一条命令的文字（[drawCmd] 与 div 子行 [drawDivCmd] 共用的唯一字形路径）。
     *
     * 抽出来的原因：div 子行要按焦点/按下态换字色（`ConsoleStyledString.cs:105-112` 的
     * isFocus 支把字色换成 `ButtonColor`），而这条路径里含字体族/基线缓存、`textScaleX`
     * 归位与 perf-render2 的计时读数 —— 复制一份必然漂移。调用方只决定用
     * [DrawCmd.fore] 还是 [DrawCmd.focusFore]。
     *
     * @param originX 列游标的原点（正文段 = 内容区左沿；div 子行 = 子行左沿）
     * @param rowTop 行条上沿（正文 = 行顶表；div 子行 = 内容盒顶 + 行序 × 行高）
     * @param foreArgb 本段字色（`0xRRGGBB`，内部补不透明位）
     */
    private fun drawCmdText(
        canvas: Canvas,
        cmd: DrawCmd,
        originX: Float,
        rowTop: Float,
        foreArgb: Int,
    ) {
        paint.color = opaque(foreArgb)
        paint.textSize = fontPixelSize
        paint.isFakeBoldText = cmd.styleMask and Grid.STYLE_BOLD != 0
        paint.textSkewX = if (cmd.styleMask and Grid.STYLE_ITALIC != 0) ITALIC_SKEW else 0f
        paint.isUnderlineText = cmd.styleMask and Grid.STYLE_UNDERLINE != 0
        paint.isStrikeThruText = cmd.styleMask and Grid.STYLE_STRIKE != 0
        // 列游标是**浮点半角格**：等宽族每步 1/2 格（与旧口径逐值相同），比例字体的
        // 亚格 advance（发丝空格 0.12 格）逐字累加 —— 这是觉 AA 画像素级对齐的全部依据。
        // 接线前 col 是 Int，任何非整格 advance 都会被截掉，比例字符画必然散架。
        var col = cmd.colStart.toFloat()
        // 任务 perf-render2：drawText 的耗时要逐字形量（一条命令里字形数不等），但 volatile
        // 写不能逐字形做 —— 先攒局部变量，一条命令画完才落一次字段（计数口径不变，
        // 写次数从每字形 2 次降到每命令 2 次）。
        var glyphTextNs = 0L
        var glyphTextCalls = 0L
        // 逐字形绘制（保持原有像素口径不变），但**度量走缓存**：原来每个字形都要
        // `String(Character.toChars(cp))`（全屏每帧几千次分配）+ `Grid.cells`，是周期性
        // GC 停顿的主要垃圾源（gfxinfo 实测 13.5% 掉帧、p99 77 ms）。
        // 注意：**不能**把连续字形合并成一次 drawText —— 实测跨字形串接会触发另一套整形/
        // 字体回退，标题页正文出现重影与方块字（2026-10-24 真机截图实证），已放弃该路线。
        //
        // 字体与基线**按字形取**（GFX-12）：一段 run 里的字形可能来自不同族
        // （正文 + `SETFONT "Saitamaar"` 字符画同段），而 `GlyphKey.fontName` 就是逐字形的。
        // 缺字回落（[effectiveFamily]）会让**同一请求族**的相邻字形落在不同实际族上，
        // 所以比较的是 [GlyphMetric.family] 而不是请求名；实际族没变时连 paint.typeface
        // 都不重设，热路径上零查表。当前族与基线放局部变量。
        // [baselineOffsetOf] 读 fontMetrics 时会暂时改写 paint 的 typeface/textSize 并复原，
        // 所以族变化时先算基线、再落 paint.typeface，顺序反了会让基线读回旧族的度量。
        var currentFamilyId: String? = null
        var currentBaseline = 0f
        for (glyph in cmd.glyphs) {
            if (col >= cmd.colEnd) {
                break
            }
            // 「未解析过」由 currentFamilyId 的 null 初值表达：族 id 永不为 null，
            // 首字形必进这一支（不能只看请求名 —— 首字形的字体名常是 null，比不出来）。
            // **必须先归位 textScaleX 再测量**：`Paint.measureText` 会被当前 textScaleX 缩放，
            // 而 metricOf 的缓存只在首次见到该字形时量一次 —— 若带着上一字形的缩放去量，
            // 缓存里存的就是错宽，之后所有缩放都算歪（真机：标题页正文重叠、方块字）。
            paint.textScaleX = 1f
            val metric = metricOf(glyph)
            val family = metric.family
            if (family.id != currentFamilyId) {
                paint.typeface = typefaceFor(family)
                currentBaseline = metric.baseline
                currentFamilyId = family.id
            }
            val advance = metric.advanceCells
            if (advance <= 0f) continue
            // 缩放口径同旧（把字形横向填满它的前进量），但填的是**尺的 advance**：
            // 等宽族 advance 吸附成 1/2 格 ⇒ textScaleX ≈ 1.04，观感与接线前一致；
            // 比例字体 advance = 真实像素 ÷ halfCellPx ⇒ textScaleX ≈ 1，字形不再被
            // 拉伸/压缩塞进静态格子 —— 这一步就是「AA 画微调空格」在屏上生效的落点。
            paint.textScaleX = if (metric.measured > 0f) advance * halfCellPx / metric.measured else 1f
            // 任务 perf-render2：单次 drawText 的墙钟与次数（f.textNs / f.textCalls）。
            // 计时区间只包住 drawText 调用本身（基线已在族变化时算好，实参求值不查表）。
            val textStartNs = System.nanoTime()
            canvas.drawText(
                metric.text,
                originX + col * halfCellPx,
                rowTop + currentBaseline,
                paint,
            )
            glyphTextNs += System.nanoTime() - textStartNs
            glyphTextCalls += 1L
            col += advance
        }
        dbgFrameTextNanos += glyphTextNs
        dbgFrameTextCalls += glyphTextCalls
        paint.textScaleX = 1f
        paint.isFakeBoldText = false
        paint.textSkewX = 0f
        paint.isUnderlineText = false
        paint.isStrikeThruText = false
    }

    /**
     * 单字形宽度（带缓存，键值口径见 [metricsByFont]）：只有首次见到该键时才调
     * `Paint.measureText`。调用点保证测量时 `textScaleX == 1`，否则量到的会是缩放后的宽度。
     */
    private fun glyphWidth(glyph: GlyphKey): Float = metricOf(glyph).measured

    /**
     * 取（并缓存）字形度量：字符串只在这里建一次，绘制期不再分配。
     *
     * 命中缓存时**不重新选族**（缺字判据 `Paint.hasGlyph` 已在首次测量时做过），
     * 但会把 `paint.typeface` 落回该度量记录的实际族 —— 同一请求族的相邻字形可能
     * 分别落在选中族与兜底族上，绘制方必须按 [GlyphMetric.family] 设字体。
     */
    private fun metricOf(glyph: GlyphKey): GlyphMetric {
        val bucket = bucketFor(glyph.fontName)
        val key = packGlyphKey(glyph)
        val cached = bucket.get(key)
        if (cached != null) {
            paint.typeface = typefaceFor(cached.family)
            return cached
        }
        val family = effectiveFamily(fontFamilies.resolve(glyph.fontName), glyph.codePoint)
        paint.typeface = typefaceFor(family)
        val text = String(Character.toChars(glyph.codePoint))
        // 前进量问排版尺（与折行/列区间同一把尺），不再用 Grid.cells 的静态 CP932 表：
        // `SETFONT "Saitamaar"` 的比例 advance（发丝空格 1.125px）只有尺读得出；
        // `SETFONT "MS Gothic"` 的块元素 ▂/▉ 静态表按 2 格算、随包等宽字体实际半角 1 格。
        // 尺里没有的码位尺自己会回落等宽网格，所以这里不再有第二条退化路径。
        val metric = GlyphMetric(
            text,
            paint.measureText(text),
            fontRuler.advanceCells(glyph.codePoint, glyph.fontName),
            family,
            baselineOffsetOf(family),
        )
        bucket.put(key, metric)
        return metric
    }

    /**
     * 逐字形缺字回落：请求族没有该码点的字形时，按 [GlyphFallback.resolve] 走随包回落链
     * （链的成员与顺序由 `GlyphFallback.chainFor` 定义，与排版尺同源），全都没有才落回请求族
     * （屏上表现为 `.notdef` 方框）。
     *
     * 原版靠 Windows GDI 的 font linking 挂系统字体，结果随机型/ROM 变化；这里只用随包族，
     * 同一份 APK 在任何机器上的缺字表现一致。`Paint.hasGlyph` 需要 API 23，minSdk 21
     * 的设备直接跳过（等价于旧行为：缺字交给 Paint 的系统回退）。
     *
     * 只在该字形**首次**进度量缓存时调用一次，不在每帧热路径上。
     */
    private fun effectiveFamily(requested: BundledFontFamily, codePoint: Int): BundledFontFamily {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return requested
        val text = String(Character.toChars(codePoint))
        return GlyphFallback.resolve(requested) { hasGlyph(it, text) }
    }

    /** 该族在当前字号下是否有 [text] 的字形（`Paint.hasGlyph`，API 23+ 调用方保证）。 */
    private fun hasGlyph(family: BundledFontFamily, text: String): Boolean {
        val previousTypeface = paint.typeface
        val previousSize = paint.textSize
        paint.typeface = typefaceFor(family)
        paint.textSize = fontPixelSize
        val has = try {
            paint.hasGlyph(text)
        } catch (_: RuntimeException) {
            true
        } finally {
            paint.typeface = previousTypeface
            paint.textSize = previousSize
        }
        return has
    }

    /** 取（必要时建）该字体的度量桶，并把 [metricOf] 的快路径指过去。 */
    private fun bucketFor(fontName: String): LongSparseArray<GlyphMetric> {
        val cached = lastFontBucket
        if (cached != null && lastFontName == fontName) return cached
        val bucket = metricsByFont.getOrPut(fontName) { LongSparseArray(1024) }
        lastFontName = fontName
        lastFontBucket = bucket
        return bucket
    }

    /** 打包字形键：codePoint | styleMask | size（同一字体桶内唯一）。 */
    private fun packGlyphKey(glyph: GlyphKey): Long =
        (glyph.codePoint.toLong() shl 32) or
            ((glyph.styleMask.toLong() and 0xFFL) shl 24) or
            (glyph.size.toLong() and 0xFFFFFFL)

    private fun opaque(rgb: Int): Int = 0xFF000000.toInt() or (rgb and 0xFFFFFF)

    /**
     * 画一段内联图形（位图 / 色块 / 空白）。
     *
     * 落位口径逐条照原版：横向起点是**列游标 + ShapePositionShift**（[originLeft] 由调用方
     * 按 [MappingColorGeometry.graphicOriginLeftPx] 算好传入 —— 列游标是文本同一个 `PointX`，
     * shift 是图形专属：spec: `ConsoleImagePart.cs:171` 的 `rectangle.X = destRect.X + PointX + shift`、
     * `ConsoleRectangleShapePart.cs:57` 的 `rect.X += shift`），
     * 竖向是 `行顶 + ypos`（`:172` 的 `rectangle.Y = destRect.Y + pointY`，
     * `destRect.Y` 就是构造期算好的 `top` = ypos，`:82`）；
     * 尺寸取段自带的像素宽高（`:66-80`），越出可绘制右沿由 [Canvas.clipRect] 裁掉
     * （与 GDI 裁画布外像素同效，spec: `ConsoleStyledString.cs:119,129-138` 那四条分支）。
     *
     * 空白段（[InlineGraphic.Space]）本来就什么都不画，只占列 —— 原版
     * `ConsoleSpacePart.DrawTo` 也是空实现（spec: `ConsoleShapePart.cs:50-55` 的分支）。
     */
    private fun drawGraphic(
        canvas: Canvas,
        graphic: InlineGraphic,
        originLeft: Float,
        rowTop: Float,
        rowHeight: Float,
        pureArtRow: Boolean,
        cmd: DrawCmd,
        pressed: Boolean,
    ) {
        when (graphic) {
            is InlineGraphic.Space -> Unit
            is InlineGraphic.Rect -> {
                val x0 = originLeft + graphic.xOffsetPx
                val top = rowTop + graphic.yOffsetPx
                val right = x0 + graphic.widthPx
                val bottom = top + graphic.heightPx
                paint.style = Paint.Style.FILL
                val stops = graphic.gradientArgb
                if (stops != null && stops.size >= 2 && graphic.widthPx > 0) {
                    // 渐变条带：停点等间距铺满段宽（展平器按位置降采样，见 InlineGraphic.Rect）
                    paint.shader = android.graphics.LinearGradient(
                        x0, top, right, top, stops, null, android.graphics.Shader.TileMode.CLAMP,
                    )
                } else {
                    paint.shader = null
                    paint.color = graphic.colorArgb
                }
                canvas.drawRect(x0, top, right, bottom, paint)
                paint.shader = null
            }
            is InlineGraphic.Image -> drawImage(
                canvas, graphic, originLeft, rowTop, rowHeight, pureArtRow, cmd, pressed,
            )
        }
    }

    /**
     * 画一张内联图（原版 `ConsoleImagePart.DrawTo` 的位图分支 + 缺帧回落，
     * spec: `ConsoleImagePart.cs:159-184`）。
     *
     * ## GFX-09 按下态换图
     *
     * 原版换图判据是 `(isSelecting || isFocus) && cImageB != null`（`:163-167`）：
     * [pressed] 时改取 [InlineGraphic.Image.buttonResource]（选图入口
     * [InlineGraphic.Image.drawResource]）。按下图查不到时退主图 —— 原版 GetSprite
     * 对未注册名返回 null ⇒ `cImageB == null`，判式落空仍画主图
     * （spec: `AppContents.cs:46-48`）。已知差异：移植侧 `frame()` 返回 null 分不清
     * 「未注册」与「已注册但未创建」（后者原版会落到 altText，`:164,168`），
     * 统一按未注册折叠，见交付报告 TODO(verify)。
     *
     * ## GFX-10 缺帧回落
     *
     * 选中的图解析不出位图（帧缺失或句柄不可用）时改画重建的 img 标签文本
     * （spec: `ConsoleImagePart.cs:175-182`），此前这里是静默 return（留白 bug）。
     */
    private fun drawImage(
        canvas: Canvas,
        image: InlineGraphic.Image,
        originLeft: Float,
        rowTop: Float,
        rowHeight: Float,
        pureArtRow: Boolean,
        cmd: DrawCmd,
        pressed: Boolean,
    ) {
        // 竖带块（标题图 35 张切片合成的一块）：逐片堆叠，单看 resource 只会出最上面一条。
        image.bandResources?.let {
            drawBandStack(canvas, image, it, originLeft, rowTop)
            return
        }
        val source = frameSource
        val requested = image.drawResource(pressed)
        var frame = source?.frame(requested)
        if (frame == null && requested != image.resource) {
            // 按下图缺失退主图（ConsoleImagePart.cs:164 的 cImageB == null 判式，
            // GetSprite 未注册名返回 null：AppContents.cs:46-48）
            frame = source?.frame(image.resource)
        }
        val bitmap = frame?.handle as? Bitmap
        if (bitmap == null) {
            // GFX-10：缺帧回落（ConsoleImagePart.cs:175-182）
            // 探针（2026-10-24 图片闪烁排查）：缺帧回落 = 屏幕上这张图这一帧没了，玩家看到就是
            // 「图片闪一下」。正常游玩不该出现；一旦出现，logcat 会打出资源名与请求名。
            if (DEBUG_IMAGE_MISS) {
                android.util.Log.w(
                    "EratwRender",
                    "缺帧回落: requested=$requested resource=${image.resource} " +
                        "w=${image.widthPx} h=${image.heightPx}",
                )
            }
            // 原版 altText 回落用**裸 PointX**（spec: ConsoleImagePart.cs:177,181 的
            // `new Point(PointX, pointY)`，不带 shift）；绘制链的 [originLeft] 已含 shift，
            // 这里退回去 —— 缺帧文本的落点与 shift 修正前逐像素一致。
            drawImageAltText(canvas, image, originLeft - shapeShiftPx, rowTop, cmd)
            return
        }

        val w = GraphicGeometry.inlineSize(image.widthPx, frame.width)
        val h = GraphicGeometry.inlineSize(image.heightPx, frame.height)
        if (w <= 0 || h <= 0) return
        val src = GraphicGeometry.sourceRectClipped(
            frame.srcX, frame.srcY, frame.srcW, frame.srcH, bitmap.width, bitmap.height,
        ) ?: return
        srcRect.set(src[0], src[1], src[2], src[3])
        val top = rowTop + image.yOffsetPx
        // 只在「行高被 RowLayout.plan 压过、且图本身不高于一行」时才缩（标题横带那支）。
        // 行高未被压、或图高于一行 ⇒ 按脚本尺寸画、向下溢出到后续空白行，与原版同构
        // （EmueraConsole.cs:1855 行距恒为 Config.LineHeight；ConsoleImagePart.cs:159-174
        // 直接按 destRect 画，高只看 raw_height），四条守卫与出处见 [GraphicGeometry.artRowScale]。
        // 2026-10-26 真机 Look 页：180px 立绘原先被无条件压成 21px（行高）＝16×21px 屏上像素，
        // 而 ERB 已按 180px 预留了整块空行 —— 属于「立绘被误伤」，不是素材或版面问题。
        val k = if (pureArtRow) {
            GraphicGeometry.artRowScale(h.toFloat(), rowHeight, lineHeightPx, image.rowSpan)
        } else {
            1f
        }
        // 框内落位（ASpriteSingle.cs:79-85 / SpriteAnime.cs:153-156）：sprite 的
        // `DestBasePosition` 与动画帧的 `Offset` 在这里进矩形，`SrcRectangle` 相对
        // `DestBaseSize` 的占比也在这里缩。少了这一步，语料里 6,851 个带 `xpos/ypos` 的
        // 静态资源会被拉满整框（拼接件全部错位放大）、`l13/留琴/*` 的动画每帧偏 15~80px。
        val place = GraphicGeometry.spriteDestRect(
            w, h, frame.destW, frame.destH,
            frame.basePosX, frame.basePosY, frame.offsetX, frame.offsetY,
            frame.srcW, frame.srcH, frame.anime,
        )
        val left = originLeft + place[0] * k
        val drawTop = top + place[1] * k
        dstRect.set(left, drawTop, left + place[2] * k, drawTop + place[3] * k)
        paint.isFilterBitmap = true
        if (image.flipX || image.flipY) {
            // 负尺寸 = 原版的**镜像**形态（`ConsoleImagePart.cs:83-92`）：GDI+ 把负宽/负高矩形
            // 镜像画进 `[0,|W|]` / `[top, top+|H|]`（纵盒不变）。布局侧已由展平器取绝对值
            // （[HtmlRunFlatten.emitImage]），这里补绘制方向：绕目标矩形中心 `scale(±1)`。
            // 语料 `画像反転フラグ` 全库无一处传非 0 ⇒ 该分支当前不可达，属 ADR-005 的
            // 语义补齐（字形/位图都按同一变换，不做逐像素翻转）。
            canvas.save()
            canvas.scale(
                if (image.flipX) -1f else 1f,
                if (image.flipY) -1f else 1f,
                dstRect.left + dstRect.width() / 2f,
                dstRect.top + dstRect.height() / 2f,
            )
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        }
        paint.isFilterBitmap = false
    }

    /**
     * 画「竖带块」：把 [InlineGraphic.Image.bandResources] 里的切片**自上而下逐片堆叠**
     * 到 [Image.widthPx]×[Image.heightPx] 的目标矩形里。
     *
     * eratw 标题图是 `SPRITECREATE "TW_titleNNN", RM_TITLE, 0, N*16, 1041, 16` 切出的
     * 35 张独立 sprite：单画首张只有最上面 16px 一条（2026-10-25 真机把整张图合成一张
     * 资源后实测只剩一条绿色碎片）。这里按每片帧的 `srcH` 占比分配目标高度，逐片
     * `drawBitmap`，拼回来才是完整画面；宽度统一拉伸到目标宽（原图各片等宽）。
     */
    private fun drawBandStack(
        canvas: Canvas,
        image: InlineGraphic.Image,
        bands: List<String>,
        originLeft: Float,
        rowTop: Float,
    ) {
        val source = frameSource ?: return
        if (bands.isEmpty()) return
        val frames = bands.map { source.frame(it) }
        var totalSrcH = 0
        for (f in frames) if (f != null) totalSrcH += f.srcH.coerceAtLeast(1)
        if (totalSrcH <= 0 || image.widthPx <= 0 || image.heightPx <= 0) return
        val top = rowTop + image.yOffsetPx
        val destW = image.widthPx.toFloat()
        val destH = image.heightPx.toFloat()
        paint.isFilterBitmap = true
        var y = top
        for (f in frames) {
            val bmp = f?.handle as? Bitmap ?: continue
            val sh = f.srcH.coerceAtLeast(1)
            val dh = destH * sh / totalSrcH
            val src = GraphicGeometry.sourceRectClipped(
                f.srcX, f.srcY, f.srcW, f.srcH, bmp.width, bmp.height,
            ) ?: continue
            srcRect.set(src[0], src[1], src[2], src[3])
            dstRect.set(originLeft, y, originLeft + destW, y + dh)
            canvas.drawBitmap(bmp, srcRect, dstRect, paint)
            y += dh
        }
        paint.isFilterBitmap = false
    }

    /**
     * 缺帧回落：把重建的 img 标签当文本画（spec: `ConsoleImagePart.cs:175-182`）。
     * GFX-10 接线后由 [drawImage] 在选中图解析不出位图时真调用（此前是死代码，
     * 缺图场景留白）。mode 分支（`:175-182` 的 GRAPHICS DrawString 与 TextRenderer
     * 两态）在本端口只有一条文本路径，统一走 `drawText`。
     * 颜色用原版那句的 `Config.ForeColor` —— 本端口最接近的现存量是段的
     * [DrawCmd.fore]（快照行色，出厂同值 192,192,192）；落点 (PointX, pointY) 不带
     * ShapePositionShift（`:177,181` 用的是裸 `PointX`，[drawImage] 的缺帧支已把
     * 图形链的 shift 还原）。altText 为空（生产侧没给）时保持静默不画。
     *
     * 与基准的已知差异：原版缺帧时宽度改按**文本**测量（`SetWidth` 的
     * `cImage == null` 分支，spec: `ConsoleImagePart.cs:110-114`），本端列宽在生产侧已按
     * 图宽入表、这里不动列表 —— 缺帧行的后续列会整体偏右 |图宽-文本宽|，
     * TODO(verify) 需真机截图判据决定是否让展平侧按帧存在性出文本段（归组长接线）。
     */
    private fun drawImageAltText(
        canvas: Canvas,
        image: InlineGraphic.Image,
        originLeft: Float,
        rowTop: Float,
        cmd: DrawCmd,
    ) {
        val alt = image.altText ?: return
        if (alt.isEmpty()) return
        // 缺帧回落的 altText 是**整串**一次 drawText，只能选一个族：取该段第一个字形的族
        // （`GlyphKey.fontName`），段内没有字形（纯图段）时落默认族。不逐字形拆画 ——
        // 拆了就不是原版 `DrawString(altText)` 的一次调用语义（`ConsoleImagePart.cs:177,181`）。
        val altFont = cmd.glyphs.firstOrNull()?.fontName
        paint.color = opaque(cmd.fore)
        paint.typeface = typefaceFor(altFont)
        paint.textSize = fontPixelSize
        paint.textScaleX = 1f
        // 裸列游标落点（ConsoleImagePart.cs:177,181 的裸 PointX）：W6-H6 之后绘制链的
        // originLeft **已含 ShapePositionShift**（图形段起点走 graphicOriginLeftPx），
        // 本函数的调用方（drawImage 缺帧支）已把 shift 退回去，这里拿到的就是裸值。
        // 任务 perf-render2：缺帧回落的文本同样计入 f.textNs / f.textCalls（整串一次 drawText）。
        val textStartNs = System.nanoTime()
        canvas.drawText(alt, originLeft, rowTop + baselineOffsetOf(altFont), paint)
        dbgFrameTextNanos += System.nanoTime() - textStartNs
        dbgFrameTextCalls += 1L
    }

    /**
     * 屏幕坐标 → 遮罩取色（原版按钮命中后附加的 `GetMappedColor`，
     * spec: `ConsoleButtonString.cs:138-152` + `ConsoleImagePart.cs:135-157` +
     * `ASpriteSingle.cs:50-64`）。PC 上鼠标按下时把
     * `INPUT(6, color)`（等待原始输入态，`MainWindow.cs:795`）或
     * `INPUT(3, color)`（带鼠标的输入等待，`MainWindow.cs:828`）灌进 VM 输入队列，
     * 脚本据此知道「图片按钮的哪一块被点了」—— eraTW 的贴图菜单/地图交互就吃这个值。
     *
     * ## 坐标口径：与绘制**逐式一致**的两把尺合成一把（W6 横竖两刀）
     *
     * - **横向**：取色本地 x 从 [MappingColorGeometry.graphicOriginLeftPx] 起算
     *   —— 列游标 + [shapeShiftPx]，与 [drawCmd] 图形段的绘制原点**同一个函数**。
     *   原版绘制本来就加 shift（spec: `ConsoleImagePart.cs:171`、
     *   `ConsoleRectangleShapePart.cs:57`），取色 `num = pointX - ... - shift`
     *   （spec: `ConsoleButtonString.cs:147`）的前提就是这一条；移植侧曾「绘制不加、
     *   取色照抄原版去减」，横偏一个 shift（约字号/6 像素）。文本与 altText 不沾 shift
     *   （spec: `ConsoleImagePart.cs:177,181` 用裸 `PointX`）。
     * - **纵向**：行顶查 [rowLayout] 的行顶表（`tops[cmd.row]`，与 [drawScreen] 的
     *   `layout.tops[row]` 逐式一致），**不再用等距式** `contentOriginY + (row - extraRows)
     *   * lineHeightPx` —— 纯图行被压缩（标题页那种 `artAdvance < 行高`）时等距式整体
     *   偏一排（BOARD GFX-14，883f7bf6 收了绘制/命中两路，取色是最后一路）。
     *
     * 逐段扫描、范围判与基图定位的算式在 [MappingColorScan.scan]（纯 JVM，同坐标对拍
     * 用例钉在 `render-canvas/src/test`）；本方法只剩三步 View 侧专属：行顶表、位图句柄
     * 到 `Bitmap` 的解释、基图越界回落（`ASpriteSingle.cs:55-60` 的 Transparent ⇒ RGB 0）。
     *
     * 返回 0 = 原版 `return 0L` 的各条分支（无遮罩段命中 / 遮罩缺失 / 未创建 /
     * 点不在图内 / 基图越界，spec: `ConsoleButtonString.cs:151`、
     * `ConsoleImagePart.cs:137,148,156`）。要区分「未命中」与「真取到黑像素 0」
     * 用 [mappingColorOrNull]。
     *
     * **消费点（INPUT(6/3,color) 入队，2026-11-04 W6 接线）**：长按手势（MainActivity
     * 长按段）先走 [mappingColorOrNull] 判命中，命中才投喂输入通道——现走
     * `GameHost.submit` 的文本通道（INPUT 系等待的端到端读数）；core 面的专用投喂口
     * `QueueInputSink.submitMappedColor`（INPUTMOUSEKEY 的色条目直付交付面）已落地，
     * 装配侧已切换（2026-11-06 收口）：`MainActivity.pickMappedColorAt` → `GameHost.submitMappedColor` → `QueueInputSink.submitMappedColor`，色条目直付、不走文本通道。
     */
    fun mappingColor(x: Float, y: Float): Long = mappingColorOrNull(x, y) ?: 0L

    /**
     * [mappingColor] 的命中判定版：命中遮罩段 ⇒ 取色值（**黑像素 0L 也是命中**），
     * 未命中 ⇒ null。
     *
     * 原版 `GetMappedColor`（spec: `ConsoleButtonString.cs:138-152`）对「遮罩缺失 /
     * 未创建 / 点不在图内 / 基图越界」与「真取到黑像素」都返回 0L，本身不分；分辨靠
     * 调用侧闸门（PC 是「悬停按钮才写值」，`MainWindow.cs:793` 的 `SelectingButton != null`
     * 与 `:824` 的 `selectedString != null`）。本方法的 null 就是给调用侧的那道闸门：
     * 长按手势只在命中时投喂，黑像素 0L 照原版口径照样交付（`MainWindow.cs:795`/`:828`
     * 对命中按钮的取色值不做 0 剔除）。
     *
     * 逐式换算与各无命中分支的证据同 [mappingColor] 的 KDoc；扫描半段在
     * [MappingColorScan.scan]（纯 JVM）。
     */
    fun mappingColorOrNull(x: Float, y: Float): Long? {
        val console = feed?.screen ?: screen ?: return null
        val source = frameSource ?: return null
        val contentLeft = contentLeftPx()
        // 快速闸同 [cellAt]：点在内容区左上之外恒不命中（绘制被 clipRect 裁掉的区域）。
        if (x < contentLeft || y < paddingTop.toFloat()) return null
        val cmds = console.list.render()
        // 行顶表与 drawScreen / cellAt 同源（见 [rowLayout]）—— GFX-14 的纵向刀。
        val layout = rowLayout(console, cmds)
        val hit = MappingColorScan.scan(
            x = x,
            y = y,
            cmds = cmds,
            rowTops = layout.tops,
            contentLeftPx = contentLeft,
            halfCellPx = halfCellPx,
            shapeShiftPx = shapeShiftPx,
            frameOf = { resource -> source.frame(resource) },
        ) ?: return null
        val maskBitmap = hit.mask?.handle as? Bitmap ?: return null
        if (hit.baseX !in 0 until maskBitmap.width || hit.baseY !in 0 until maskBitmap.height) {
            return null // ASpriteSingle.cs:55-60：基图外返回 Transparent ⇒ RGB 0
        }
        return (maskBitmap.getPixel(hit.baseX, hit.baseY) and 0xFFFFFF).toLong()
    }

    /**
     * 配置里的字体名 → 该族的 Typeface（带缓存，缺包回落见 [loadTypeface]）。
     *
     * 选族走 [fontFamilies]（请求名 → 登记族的实例缓存），不再逐字形调
     * [BundledFontFamilies.resolve] 做两轮归一；命中 [lastTypefaceFamily] 时零 map 查表。
     * 逐字形热路径用下面那个**收已解析族**的重载，连请求名查表都省掉。
     */
    private fun typefaceFor(fontName: String?): Typeface =
        typefaceFor(fontFamilies.resolve(fontName))

    /** 已解析族 → Typeface。缓存键仍是登记族的族名（[typefaces] 的键语义不变）。 */
    private fun typefaceFor(family: BundledFontFamily): Typeface {
        val key = family.family
        if (key == lastTypefaceFamily) return lastTypeface ?: Typeface.MONOSPACE
        val tf = typefaces.getOrPut(key) { loadTypeface(family) ?: Typeface.MONOSPACE }
        lastTypefaceFamily = key
        lastTypeface = tf
        return tf
    }

    /**
     * 该族在**当前字号**下的基线偏移（行盒顶 → 基线）。
     *
     * 公式与旧版逐字相同：`(行高 - (descent - ascent)) / 2 - ascent`，只是把「字体」从
     * 构造期固定的单一 Typeface 换成按族查。各族 `ascent/descent` 不同（12px 点阵与
     * 16px 点阵的升降部不一样），共用一份会让异族行整体上下错位。
     *
     * 键仍是登记族的族名（[baselines] 的键语义不变）；[applyMetrics] 改字号时整表清空。
     */
    private fun baselineOffsetOf(fontName: String?): Float =
        baselineOffsetOf(fontFamilies.resolve(fontName))

    private fun baselineOffsetOf(family: BundledFontFamily): Float {
        val key = family.family
        baselines[key]?.let { return it }
        val previousTypeface = paint.typeface
        val previousSize = paint.textSize
        paint.typeface = typefaceFor(family)
        paint.textSize = fontPixelSize
        val fm = paint.fontMetrics
        paint.typeface = previousTypeface
        paint.textSize = previousSize
        val offset = (lineHeightPx - (fm.descent - fm.ascent)) / 2f - fm.ascent
        baselines[key] = offset
        return offset
    }

    /**
     * 按登记表加载一个随包族；`assets` 缺该文件（裁剪包/坏包）时回 null，
     * 由调用侧退 [Typeface.MONOSPACE]。`Typeface.createFromAsset` 对缺包/坏包抛
     * RuntimeException（android.graphics 契约），这里一律吞成 null —— 渲染层
     * **不许**因为字体缺失把整帧打挂（同 `FontFactory.cs:52-54`）。
     */
    private fun loadTypeface(family: BundledFontFamily): Typeface? =
        try {
            Typeface.createFromAsset(context.assets, family.asset)
        } catch (_: RuntimeException) {
            null
        }

    companion object {

        /** 绘制/取图的 logcat 标签（与既有 `EratwRender` 那批诊断行同源）。 */
        private const val TAG = "EratwRender"

        /**
         * div 边框色的回落值（`bcolor` 没写时原版取 `Config.ForeColor`，DrawBorder :94/:111
         * 的 colors == null 支）。`:render-canvas` 看不见 `:core-vm`（implementation 依赖），
         * 出厂值 192,192,192（ConfigData.cs:68）在此**独立成常量**，与 headless 的
         * ScreenComposer 用的核心层常量同值 —— 两头分叉时以核心层为准，真机配置改过
         * ForeColor 后的回退差登记 TODO(verify)（语料带 border 的 div 全部写了 bcolor）。
         */
        private const val DIV_BORDER_FALLBACK_RGB = 0xC0C0C0

        /**
         * 绘制异常日志的限流步长：首帧打全栈，之后每这么多次打一行计数。
         *
         * 120 ≈ 60 fps 下的两秒 —— 一帧一崩时两秒一行，既看得出「还在崩」，又不至于
         * 把 logcat 冲掉（玩家发回来的日志才是下一次定位的依据）。
         */
        private const val DRAW_FAILURE_LOG_EVERY = 120L

        /** 选项页边界字符（整行都是这些字符 ⇒ 视为分页分隔线）。 */
        private val OPTION_BOUNDARY_CHARS = "━─═=-_~*・…※―─".toSet()

        /** 图片缺帧诊断日志（2026-10-24 图片闪烁排查）：默认开，定位完可关。 */

        private const val DEBUG_IMAGE_MISS = true

        /**
         * 行位图缓存的字节预算。槽位数沿用原版 256（`EmueraConsole.cs:70-76`），但真机
         * 必须先看字节：一块 2400×44 的 ARGB_8888 行位图 422 KB，256 槽 = 105 MB，
         * 而开局常驻已经 532 MiB（`BOARD.md:639`）。
         *
         * 取值不能小于**一屏行位图的总量**，否则全幅帧会把开头几行挤出去、下一帧再烤回来
         * —— 2400 宽 × 43 行高 × 42 行 ≈ 17.3 MB，所以取 24 MB（滚动回看与地图整屏重印
         * 才真能命中）。这只是上限，窄屏用多少占多少。
         */
        private const val ROW_CACHE_MAX_BYTES = 24L * 1024L * 1024L

        /** 行位图回收池上限（避免色付き地图那种「每 tick 整屏重烤」的分配抖动）。 */
        private const val ROW_POOL_MAX = 48

        /**
         * 回收池的字节上限。张数上限单独不够：一张 2400×44 的 ARGB_8888 行位图 422 KB，
         * 48 张就是 20 MB，而缓存本身只给了 12 MB。6 MB 够放一屏常见宽度的行（1080×44 ≈
         * 190 KB × 30 出头），又不至于让池子变成第二个缓存。
         */
        private const val ROW_POOL_MAX_BYTES = 6L * 1024L * 1024L

        /** 缺省目标列数改由 [ScreenLayout.DEFAULT_COLUMNS] 单一持有（136 ⇒ 2400px 长边下字号约 34px）。
         *
         * PC 基准是 199 列（`Config.cs:461-466`：WindowX 1800 - shift 3 = 1797px ÷ 9px
         * 步进，佐证在 `fixtures/golden/baseline/pc-golden-root.txt:253` 的 199 个 `=`）：
         * 按 199 列反推，手机上的字号只有约 23px ≈ 7.7dp，看不清 —— 所以缺省取手机可读档，
         * 要逐像素对齐 PC 版面的排障场景用构造参数 `minColumnsOverride` 传 199。
         */

        /** PC 基准列数（原版 `FontSize=18` + 1800px 窗口 ⇒ 199 列）：图形素材的版面基准。 */
        private const val PC_REFERENCE_COLUMNS = 199

        /**
         * 图片行压缩后的最小行高（像素）：防止极端情况下压成 0 导致除零/闪烁。
         */
        private const val MIN_ART_ROW_HEIGHT = 6f

        /**
         * 竖带图块整块高上限占内容高的比例。
         *
         * 标题图（35 条带）之后还有页脚文字：留 40% 给文字，图占 60%。成块后行数 =
         * ceil(图高/行高) + 页脚行数，真机 1080×2318 / 字号 30 下是 33 行，正好进 34 行视口。
         */
        private const val BAND_MAX_HEIGHT_SHARE = 0.6f

        /** 标题素材总行数（35 条横带，`ResourceManager.ERB:8-10`）+ 1 行余量，作图形纵向上限。 */
        private const val ART_ROWS_WITH_MARGIN = 36f

        /** 按下态按钮的高亮底色（半透明白，叠在行底色上 = 灰亮块）。 */
        private const val BUTTON_PRESSED_BACK = 0x66FFFFFF

        /** `<button value=N>标签</button>`（value 可带引号，1~2 位数字；不跨标签嵌套）。 */
        private val HTML_BUTTON_RE = Regex(
            "<button[^>]*\\bvalue\\s*=\\s*['\"]?(\\d{1,2})['\"]?[^>]*>(.*?)</button>",
            RegexOption.IGNORE_CASE,
        )

        /** 任意 HTML 标签（取按钮标题时剥掉）。 */
        private val HTML_TAG_RE = Regex("<[^>]+>")

        /** 行首的 `[N]` / `[  N]` 前缀（标题里不重复显示序号）。 */
        private val ORDINAL_PREFIX_RE = Regex("^\\[\\s*\\d{1,2}\\s*\\]\\s*")

        /** 连续空白压成一个空格。 */
        private val WHITESPACE_RE = Regex("\\s+")

        /**
         * 合成斜体斜切量（Android 惯例值，非契约数值）。随包三族都只有常规字面，
         * `STYLE_ITALIC` 只能靠 `Paint.textSkewX` 合成；原版是 GDI+ `FontStyle.Italic`
         * 的真轮廓（spec: `FunctionMethodCreator.cs:6369-6372`），墨迹形状不逐像素等。
         */
        private const val ITALIC_SKEW = -0.25f
    }
}

/**
 * 「这个资源名此刻还在屏上要像素吗」判口（FEEL1-L1 解码就绪事件的行权闸；
 * 纯 JVM，不碰任何 Android API，可在桌面单测直接驱动；唯一调用方是
 * `CanvasConsoleView.onGraphicResourceReady`）。
 *
 * 三个问句对应绘制侧三个像素在场面（都是既有通道，不新增任何状态）：
 * - [inDrawnCmds]：内联图段。命令缓冲取 `CanvasConsoleView.frameCmds` —— 上一帧画过的
 *   展平命令（零分配复用缓冲，只在 UI 线程读写），它的 Contents 就是上一帧真正落过笔的
 *   命令集合，不需要再维护第二份台账；
 * - [inBackgrounds]：`SETBGIMAGE` 的层表（`GraphicsLayer.background`，volatile 不可变列表）；
 * - [inImageLayers]：`SETIMAGELAYERL` 的图层表（`GraphicsLayer.imageLayers`，同上）。
 *
 * 判式匹配一段的**全部可取图名**：主图名、按下换图名（`InlineGraphic.Image.buttonResource`，
 * 按下态下 `drawResource(pressed)` 的真实取图名）、竖带切片名（`bandResources`，标题图
 * 35 片逐片取名）。**不含**遮罩名（`mappingResource`）：遮罩不参与绘制、只被命中路径读像素
 * （出处：`render/src/main/kotlin/com/eratw/port/render/displaylist/DrawCmd.kt:75-80` 的
 * mappingResource KDoc 与 `ConsoleButtonString.cs:138-152` 的取色链），它就绪不改像素，
 * 不该引起重画。`InlineGraphic.Rect` / `InlineGraphic.Space` 不消费位图，恒不匹配。
 */
internal object GraphicReadyGate {

    /** 该资源名是否被这一段内联图形引用（匹配全部可取图名，口径见类 KDoc）。 */
    fun references(graphic: InlineGraphic?, resource: String): Boolean {
        val image = graphic as? InlineGraphic.Image ?: return false
        if (image.resource == resource || image.buttonResource == resource) return true
        val bands = image.bandResources
        if (bands != null) {
            for (band in bands) {
                if (band == resource) return true
            }
        }
        return false
    }

    /** 该资源名是否被上一帧画过的某条命令的内联图段引用。 */
    fun inDrawnCmds(cmds: List<DrawCmd>, resource: String): Boolean {
        if (resource.isEmpty()) return false
        for (cmd in cmds) {
            if (references(cmd.graphic, resource)) return true
        }
        return false
    }

    /** 该资源名是否还在背景层表里（`SETBGIMAGE`）。 */
    fun inBackgrounds(backgrounds: List<GraphicsLayer.BgPlacement>, resource: String): Boolean {
        if (resource.isEmpty()) return false
        for (placement in backgrounds) {
            if (placement.resource == resource) return true
        }
        return false
    }

    /** 该资源名是否还在图层表里（`SETIMAGELAYERL` 的 sprite 名）。 */
    fun inImageLayers(layers: List<GraphicsLayer.ImageLayerSnapshot>, resource: String): Boolean {
        if (resource.isEmpty()) return false
        for (layer in layers) {
            if (layer.spriteName == resource) return true
        }
        return false
    }
}

/**
 * LIVE-REFLOW 的两条触发判据（L4）：纯 JVM、不碰任何 Android API，供桌面单测直接驱动。
 *
 * ## 为什么判据能抽出来、动作抽不出来
 *
 * [CanvasConsoleView] 是 Android `View`，本模块没有 Robolectric（`render-canvas` 的测试面
 * 只有 `kotlin("test")` + JUnit5），**实例化不了**；而换屏动作里写的是 `View` 的私有状态
 * （`screen` / `metrics` / 保留面失效）。所以口径同 [GraphicReadyGate]：判据抽成这里直测，
 * 动作顺序由源码面钉子钉住（`render-canvas/src/test` 里的 LIVE-REFLOW 用例）。
 *
 * ## 两条判据的写入方向是相反的，别合并
 *
 * - [viewportNeedsReflow]：**视口 → 宿主**。宽度变了（列数解算结果与显示中屏宽不一致）
 *   才回调；一致时连回调都不发（同尺寸重复布局必须零副作用，契约 §0）。
 * - [swapStep]：**宿主 → 视口**。回调之后宿主必须先把核心 resize 到同一列数，本类才换屏；
 *   `WidthMismatch` 不是「等一下」，而是调用方漏了 resize 的编程错误（两套宽度并存
 *   = 列号必漂），所以 [CanvasConsoleView.reflowAttachedFeed] 对它抛 `IllegalStateException`。
 */
internal object LiveReflowPlan {

    /**
     * [CanvasConsoleView.rebuildScreen] 的 attached 分支判据：true = 该问宿主重排，
     * **不再**走旧的 rows-only（`resizeFeedScreen`）路径。
     *
     * @param liveReflow 开关；false 时恒 false（关态逐字等价的红线）
     * @param attachedWidth 当前**显示中**那只屏的列宽（`feed.screen.clientWidth`）。这里读屏宽是
     *   有意的：本判据问的是「画面上这只屏还配不配得上新版面」，而换屏没成功时（宿主 resize 了、
     *   屏还没换）旧屏宽仍旧不等于 `layoutColumns` ⇒ 下一次 [rebuildScreen] 照样再问一次，
     *   形成天然的补做路径。判据若改读核心宽，那一格会被判成「已对齐」而把旧屏永久留在画上。
     *   （反例见 [swapStep]：宿主→视口那一支必须读核心宽。）
     * @param layoutColumns 本次可见区解算出的列数（`ScreenLayout.resolve.columns`）
     */
    fun viewportNeedsReflow(liveReflow: Boolean, attachedWidth: Int, layoutColumns: Int): Boolean =
        liveReflow && layoutColumns != attachedWidth

    /**
     * [CanvasConsoleView.reflowAttachedFeed] 的分支判定。
     *
     * @param sinkWidth 核心 sink 的当前折行宽（[ConsoleFeed.sinkClientWidth]）；
     *   null = 没挂 feed（演示路径）。**两支判据读的不是同一个数**，别顺手合并：
     *   [viewportNeedsReflow] 问「显示中的屏对不对得上新版面」⇒ 读 `screen.clientWidth`；
     *   这里问「核心是否已经按新列数重排」⇒ 只能读核心宽，交接窗口里屏还是旧宽。
     * @param columns 调用方要求的新列数
     */
    fun swapStep(liveReflow: Boolean, sinkWidth: Int?, columns: Int): Step = when {
        // 开关关闭：不换屏、不碰任何字段（契约 §1 对 false 的定义）
        !liveReflow -> Step.Skip
        // 没挂 feed：没有可换的桥
        sinkWidth == null -> Step.Skip
        // 核心还没按新宽 resize：调用方顺序错了
        columns != sinkWidth -> Step.WidthMismatch
        else -> Step.Swap
    }

    /** [swapStep] 的三支：跳过 / 调用方漏了 resize / 可以换屏。 */
    enum class Step {
        /** 开关关闭或没挂 feed：返回 false，零状态变化。 */
        Skip,

        /** `columns != sink.clientWidth`：核心还没 resize，抛 `IllegalStateException`。 */
        WidthMismatch,

        /** 宽度已对齐：新建空屏并换给 feed。 */
        Swap,
    }
}

