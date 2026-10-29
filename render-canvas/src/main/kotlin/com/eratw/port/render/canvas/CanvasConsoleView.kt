package com.eratw.port.render.canvas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.eratw.port.render.GlyphKey
import com.eratw.port.render.Grid
import com.eratw.port.render.HitTarget
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.font.BundledFontFamilies
import com.eratw.port.render.font.BundledFontFamily
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.sprite.AnimeRepaintPlan
import com.eratw.port.render.sprite.planAnimeRepaint
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ScreenLayout
import com.eratw.port.render.view.BackgroundBakeGuard
import com.eratw.port.render.view.GraphicGeometry
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.hitRowForCell
import com.eratw.port.render.view.ImageFrame
import com.eratw.port.render.view.ImageFrameSource
import com.eratw.port.render.view.OptionButtonScanner
import com.eratw.port.render.view.PageFitGate
import com.eratw.port.render.view.PageFitter
import com.eratw.port.render.view.RowRasterCache
import com.eratw.port.render.view.RunChrome
import com.eratw.port.render.view.RowLayout
import com.eratw.port.render.view.ScreenSurfacePlanner
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

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
 * - 绘制与命中**共用同一张行顶表**：[drawScreen] / [cellAt]（`tap` / `setPointer` / `tooltipAt`）
 *   都查它，保证「画在哪 = 点哪里」。等距形态的原点 [contentOriginY] 现在只剩 [mappingColor]
 *   在用（纯图行被压缩时两者会分叉，那条取色路尚未跟表 —— 已按报红登记 BOARD，未在本刀改）。
 * - 字形前进量取 [Grid.cells]（半角 1 / 全角 2，增补平面按已登记的「每码元 2 格」口径），
 *   与 [DrawCmd.colEnd] 的算法同源；不读 `Paint` 的实际前进量来排版，
 *   只把单字横向缩放到该格宽内，保证列位置与原版网格一致。
 *
 * ## 字体与列数
 *
 * 字号由「屏宽 ÷ 目标列数」反推：目标列数缺省 110，手机横屏（2400px 长边）下约 42px
 * ≈ 15dp，是正常阅读尺寸；构造参数 `fontColumnsOverride` 可显式覆盖（排障想对齐 PC 基准
 * 就传 199）。原版是固定 `FontSize=18` + 固定窗口 1800px（⇒ 199 列，spec:
 * `Config.cs:461-466`）：手机上照抄固定字号列数会随屏宽漂；照抄 199 列则只剩
 * 约 7.7dp 字号（真机 2400px 宽 ≈ 23px），屏上根本看不清。
 * 反推而不是缩放画布：列数定了之后，行高、图高、折行宽按同一比例放大，版面结构不变。
 * 屏宽取**长短边的长边**，横竖屏都按横屏算（App 已锁横屏，见 AndroidManifest 的说明）。
 * 拿不到真实屏幕尺寸（mock context）时退回设计基准 18dp；反推出小于
 * [CellMetrics.MIN_FONT_PIXEL_SIZE] 时同样走这条兜底。
 *
 * 随包字体走 [BundledFontFamilies] 的**多族**注册表（GFX-12 换字体波）：默认族
 * `Saitamaar`（公有领域字符画字体，比例 advance 由 1:2 网格兜底），另备美咲ゴシック、
 * Fusion Pixel 12px、Ark Pixel 12px 与 Unifont 16px，`SETFONT "Saitamaar"` 同族。字体文件由
 * Gradle 从 `:render` 的 resources 同步进 APK assets（不复制进 git）；某族缺包/坏包时
 * 该族退 [Typeface.MONOSPACE]，**任何情况下不抛**
 * （同 `FontFactory.cs:52-54` 的 try/catch → null）。选族口径与判据见
 * [BundledFontFamilies] 的 KDoc。
 *
 * 排障读数：[columns]（反推用的目标列数）、[fontPixelSize]（生效字号像素）、
 * [consoleColumns]（当前实际列数）。
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
 * `ButtonIsPointing` 只做引用比较）。触摸事件的观测点在 [dispatchTouchEvent]（main 的
 * `setOnTouchListener` 只喂 `GestureDetector`，没有 `setPointer` 写点，见该方法的 KDoc）。
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
    /** 「适应屏幕」（设置页开关）：true = 按可见区反推一页铺满；false = 只按屏宽定字号。 */
    private val screenFitEnabled: Boolean = true,

    /**
     * 反推字号用的**目标列数**（半角格）：null = 缺省 110（手机可读档）。
     * 装配层想显式锁列数（例如排障时对齐 PC 基准 199）就传具体值；
     * 只影响本 View 的字号/行高/半角格宽，不改变 [consoleColumns] 的语义。
     */
    fontColumnsOverride: Int? = null,
) : View(context, attrs, defStyleAttr) {

    /**
     * 反推字号用的目标列数（半角格）：构造参数覆盖，缺省 110。
     *
     * 为什么是 110 而不是 PC 基准的 199：199 列反推出来的是「一行铺满」的字号，
     * 2400px 长边上只有约 23px ≈ 7.7dp，屏上看不清。110 列约 42px ≈ 15dp，
     * 是正常手机阅读尺寸；代价是正文折行比 PC 密，见类文档「字体与列数」。
     * 装配层可用本属性 / [fontPixelSize] / [consoleColumns] 三个读数排障。
     */
    val columns: Int = (fontColumnsOverride ?: DEFAULT_FONT_COLUMNS).coerceAtLeast(1)

    /**
     * 「每屏行数」偏好（0 = 自动，只按屏宽反推字号）。
     *
     * 原版一页 = 窗口高 480 ÷ 行高 19 = **25 行**（`ConfigData.cs:57,67`），列宽 84
     * （760px 宽、字号 18）。手机屏幕比 4:3 更宽，屏宽反推出的字号会让一页只占十几行、
     * 屏底留白；把行数也纳入反推（行高 = 屏高 ÷ 行数，字号随行高缩放）就能让**一页正好
     * 铺满一屏**，代价是列数变多/字变小 —— 这正是「一屏一帧」想要的取舍。
     */


    /**
     * 生效字号（像素，未取整）：由**屏宽**反推，使一行正好铺 [columns] 个半角格。
     *
     * 原版是固定 `FontSize=18` + 固定窗口 1800px（⇒ 199 列）。手机上照抄固定字号，
     * 列数会随屏宽漂（1080 竖屏只有约 60 列），版面和素材尺寸全部与原版不一致 ——
     * 标题图那 35 条 1041×16 的横带尤其明显：缺省高度按 `FontSize` 拉伸后
     * 逐带高度 = 行高，35 行加起来远高于屏高，且每带被压进屏宽 ⇒ 纵向拉长、
     * 字形横向压扁。
     *
     * 反推而不是缩放画布：列数定了之后，行高、图高、折行宽按同一比例放大，
     * 版面结构不变。屏宽取**长短边的长边**，横竖屏都按横屏算，
     * 避免依赖 onCreate 时的当前朝向（App 已锁横屏）。
     *
     * 两支兜底：反推值小于 [CellMetrics.MIN_FONT_PIXEL_SIZE]（拿不到真实屏幕尺寸的
     * mock context 就是 0）时退回设计基准 18dp；其余情况用反推值。
     */
    /**
     * 一页要放几行：壳层偏好的「每屏行数」，缺省按原版一页 [PageFitter.DEFAULT_PAGE_ROWS] = 25
     * （窗口 480 ÷ 行高 19，`ConfigData.cs:57,67`）。
     *
     * 自动适配（2026-10-24 主人要求「一页必须正好铺满一屏」）：字号/行高/列数一律由
     * [PageFitter] 按**当前可见区像素**反推，见 [applyPageFit]。旧实现只按**整屏宽**反推字号，
     * 输入栏一展开可见高从 1080 掉到 966，可见行数就从 25 掉到 22 ⇒ 脚本那页 25 行被顶掉 3 行，
     * 玩家得往上滑才看得全（存档界面实测如此）。
     */
    /**
     * 一页行数：引擎缺省
     * [PageFitter.DEFAULT_PAGE_ROWS] = 36。
     *
     * 为什么不用「日志行数」自动推：日志保留的是**历史**（真机读档页实测 103 行），
     * 不是「当前页行数」——按它反推会把字号压到 15px、标题页又撑到 85px（2026-10-24 实测翻车）。
     * 页面边界脚本并没有标记，所以把「一屏几行」交给壳层偏好，由玩家/组长按画面需要定。
     */
    /**
     * 当前这一页的行数：**从控制台内容量出来**（`ConsoleScreen.pageExtentRows`），
     * 拿不到（屏幕还没接线 / 日志为空）时退回 [PageFitter.FALLBACK_PAGE_ROWS]。
     */
    private fun currentPageRows(): Int =
        consoleForFit()?.pageExtentRows()?.takeIf { it > 0 } ?: PageFitter.FALLBACK_PAGE_ROWS

    /** 当前真正在用的控制台：生产路径挂的是 [feed]，`screen` 字段只在无 feed 时赋值。 */
    private fun consoleForFit(): ConsoleScreen? = feed?.screen ?: screen

    /**
     * 「一页行数 → 字号」的防抖闸（2026-10-29 玩家反馈「外出界面会出现闪动」）。
     *
     * 为什么不能「量到几行就立刻换字号」：QoL 地图/外出屏每 `ANIMATERECOLOREDMAPS`
     * 毫秒整屏 `CLEARLINE` + 重印（`qol_MAP.ERB:34-44`），`pageExtentRows()` 的读数
     * 会在 N 与 N±1 之间摆；而字号是行数的函数 ⇒ 整页在两种字号间来回跳 = 闪动。
     * 口径与自激成因见 [PageFitGate] 的类文档。
     */
    private val fitGate = PageFitGate()

    /**
     * 一页行数变了就重算度量（**「适应屏幕」的驱动点**）。
     *
     * 为什么必须每帧比对：页面是游戏随时画出来的，`pageExtentRows()` 量到 5 行还是 58 行
     * 直接决定字号 —— 只在 `onSizeChanged` 算一次的话，翻页后字号永远是上一页的。
     * 代价：只有「本帧发布过新内容」时量一次（`hasUnpublishedContent` 已由调用方保证）。
     */
    private var lastMeasuredLogSize: Int = -1

    private fun refitIfPageChanged() {
        if (!screenFitEnabled) return
        val logSize = consoleForFit()?.log?.size ?: -1
        // 待确认期间**不许早退**：日志行数可能恰好不再变化（页面静止），而确认还差一帧；
        // 早退会让字号永远停在旧档（[PageFitGate.hasPending] 的 KDoc 同口径）。
        if (!fitGate.hasPending && logSize == lastMeasuredLogSize && fitGate.appliedRows > 0) return
        lastMeasuredLogSize = logSize
        // 行数要连续 [PageFitGate.DEFAULT_STABLE_FRAMES] 帧一致才生效（抖动不过闸）。
        if (!fitGate.accept(currentPageRows())) return
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        if (contentWidth <= 0 || contentHeight <= 0) return
        // 用闸已确认的行数，不再重测：同一帧里两次 pageExtentRows 若撞上生产侧写入，
        // 会得到两个不同的行数 ⇒ 字号与闸的记录不一致，下一帧又判「变了」。
        applyPageFit(contentWidth, contentHeight, fitGate.appliedRows)
        val feedScreen = feed?.screen
        if (feedScreen != null) {
            val rowsOnScreen = (contentHeight / lineHeightPx).toInt().coerceAtLeast(1)
            resizeFeedScreen(feedScreen, rowsOnScreen)
            // 字号变了：展平器的 `metrics` 还是构造期快照，图片会按旧字号排版（带宽/占宽错位）。
            // 重展平后页面行数可能变化，下一帧 refit 会再收敛一次。
            syncFeedLayout()
        }
        requestRedraw()
    }

    /** 当前生效度量（[applyPageFit] 随可见区重算）；推导已下沉 [ScreenLayout]（与本 View 共享同一公式）。 */
    private var metrics: CellMetrics = run {
        val dm = resources.displayMetrics
        val w = maxOf(dm.widthPixels, dm.heightPixels)
        val h = minOf(dm.widthPixels, dm.heightPixels)
        if (screenFitEnabled) {
            // 构造期兜底：屏参按横屏长边 × 短边（App 锁 sensorLandscape），页行数未知落 25 行档
            ScreenLayout.fit(w, h, PageFitter.DEFAULT_PAGE_ROWS).metrics
        } else {
            // 宽传 0 = 拿不到真实屏宽（mock context / 未布局）：ScreenLayout 里走 18dp × density 兜底
            ScreenLayout.fixedColumns(0, dm.densityDpi, columns).metrics
        }
    }

    /** 生效字号（像素）。随可见区变化，绘制/折行一律读它。 */
    var fontPixelSize: Float = metrics.fontPixelSize.toFloat()
        private set

    private var halfCellPx: Float = metrics.cellPixelWidth
    private var lineHeightPx: Float = metrics.lineHeight.toFloat()
    private val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

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
     * [applyPageFit] 改字号/行高时必须整表清空 —— 基线与 `paint.textSize` 绑定。
     */
    private val baselines = HashMap<String, Float>(4)

    /** 最近一次取用的族与 Typeface（`metricOf` 的快路径，避免每字形查两次 map）。 */
    private var lastTypefaceFamily: String? = null
    private var lastTypeface: Typeface? = null

    /**
     * 「适应屏幕 = 关」时的旧口径：只按**可绘制宽**反推字号（目标列数固定），
     * 行高 = 字号 × 19/18，与页面行数无关 ⇒ 长页面会超出屏幕（需要上滑）。
     * 公式已下沉 [ScreenLayout.fixedColumns]（UP3），本方法只是调用点。
     */
    private fun widthOnlyMetrics(contentWidth: Int): CellMetrics =
        ScreenLayout.fixedColumns(contentWidth, resources.displayMetrics.densityDpi, columns).metrics

    /**
     * 按**当前可见区**重算度量：一页行数 × 行高 = 可见高，列数铺满可见宽。
     *
     * 拿不到真实尺寸（mock context / 尺寸为 0）时不改度量，保持构造期的缺省值。
     */
    private fun applyPageFit(w: Int, h: Int, pageRows: Int = -1) {
        val contentWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        if (contentWidth <= 0 || contentHeight <= 0) return
        val next = if (screenFitEnabled) {
            // 适应屏幕：一页行数 × 行高 = 可见高；宽度上限用**实际列数**（clientWidth 构造期定死，
            // 改宽要换 sink，不安全），否则字号抬上去后字形会横向溢出。
            // 推导已下沉 ScreenLayout.fit（UP3），公式与 PageFitter 单一实现。
            val fixedColumns = (consoleForFit()?.clientWidth ?: PageFitter.DEFAULT_MIN_COLUMNS)
                .coerceAtLeast(1)
            ScreenLayout.fit(
                contentWidth,
                contentHeight,
                if (pageRows > 0) pageRows else currentPageRows(),
                minColumns = fixedColumns,
            ).metrics
        } else {
            widthOnlyMetrics(contentWidth)
        }
        if (next.fontPixelSize == metrics.fontPixelSize && next.lineHeight == metrics.lineHeight) return
        metrics = next
        fontPixelSize = next.fontPixelSize.toFloat()
        halfCellPx = next.cellPixelWidth
        lineHeightPx = next.lineHeight.toFloat()
        // 字号/行高变了 ⇒ 按族缓存的基线全部失效（基线与 paint.textSize 绑定）
        baselines.clear()
        // 字形实测宽缓存同样与 paint.textSize 绑定：GlyphMetric.measured 是当时字号下的
        // paint.measureText 原生读数，绘制期再由 textScaleX = 格数x半角格宽 / measured
        // 反推缩放。DisplayList 建 GlyphKey 时一律不传 size（默认 0，见 DisplayList.kt:656,659），
        // packGlyphKey 的字号段恒为 0，打包键无法区分字号。若「适应屏幕」改字号（真机 36px->30px）
        // 后不清此表，measured 仍是旧字号读数，每字形前进量按「新字号/旧字号」比例偏窄
        //（36->30 约 -17%），整行从左向右压缩、后段字形叠到前段上。必须与 baselines 一起整表作废。
        metricsByFont.clear()
        lastFontName = null
        lastFontBucket = null
        // 同一次度量换代还要作废**像素侧**的两份缓存：行位图是按旧字号烤死的、保留面里也是
        // 旧字号的像素。行高没变而字号变了时，行位图的几何判据（行高/内容宽）挡不住这种
        // 陈旧 —— 不清就会把旧字号的字原样贴回来（ADR-009 决策 4）。
        rowCache.clear()
        planner.notifySurfaceLost()
    }

    private var screen: ConsoleScreen? = null
    private var pending: List<RenderLine> = emptyList()
    private var feed: ConsoleFeed? = null

    /** 宿主图形层（`SETBGIMAGE` 那几条语句的落点）；null = 图形通路没接线。 */
    private var graphicsLayer: GraphicsLayer? = null

    /** 资源名 → 位图的取图口（`:platform` 的 `SpriteBitmapSource`）。 */
    private var frameSource: ImageFrameSource? = null

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
     * 一屏就是数千次原生调用。字体固定，但字号可随「适应屏幕」在运行期变化（[applyPageFit]
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
    private class GlyphMetric(val text: String, val measured: Float, val cells: Int)

    /**
     * 字形度量缓存：**按字体名分桶 + 打包 Long 键**（2026-10-24 再提速）。
     *
     * 用 `HashMap<GlyphKey, …>` 时每个字形都要算一次键的 `hashCode`（含 `String.hashCode`
     * 与 data class 的字段混合）再做 `equals`（比 String）——全屏 3000 字形 × 每次重绘。
     * 这里把「字体」提到外层（同一行几乎总是同一个字体，命中 [lastFontBucket] 快路径时
     * 连字符串比较都省掉），内层键打包成一个 Long：codePoint(32) | styleMask(8) | size(24)。
     */
    private val metricsByFont = HashMap<String, HashMap<Long, GlyphMetric>>(4)

    /** 上一次命中的字体桶（同一行/相邻行通常连续命中，避免每字形一次外层查表）。 */
    private var lastFontName: String? = null
    private var lastFontBucket: HashMap<Long, GlyphMetric>? = null

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

    /** 取证读数：本帧贴图是否走了整幅（与 [planner] 的 full 同值，供 debugStats 直读）。 */
    @Volatile
    private var lastFrameFull = false


    /**
     * 形状/文本的横向位移量（`Config.DrawingParam_ShapePositionShift`）。
     *
     * 原版的**可绘制宽度不是窗口客户区宽**：
     * `shift = Math.Max(2, FontSize / 6)`，`DrawableWidth = WindowX - shift`
     * （spec: `Config.cs:461-466`）。PC 基准 `WindowX = 1800`、`FontSize = 18`
     * ⇒ shift = 3、DrawableWidth = 1797px，除以实测半角步进 9px ⇒ **199 格**
     * （钉在 `fixtures/golden/baseline/pc-golden-root.txt:253` 的 199 个 `=` 与
     * `render/.../glyph/CellMetrics` 的 9px 换算式上）。
     * 折行宽、`<p align>` 的居中基准、DRAWLINE 的填充宽都读 DrawableWidth，
     * 所以本 View 交出去的列数必须是「(客户区宽 - shift) ÷ 半角步进」而不是「客户区宽 ÷ 步进」。
     */
    private val shapeShiftPx: Int = (metrics.fontPixelSize / 6).coerceAtLeast(2)

    /** 当前可绘制宽（像素）：客户区宽减掉 [shapeShiftPx]。 */
    private fun drawableWidthPx(contentWidthPx: Int): Int = (contentWidthPx - shapeShiftPx).coerceAtLeast(0)

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
    fun windowColumns(): Int {
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        return floor(contentWidth / halfCellPx).toInt().coerceAtLeast(1)
    }

    /**
     * 内容区像素宽（GFX-06）：`CLIENTWIDTH()` 的真机注入源。
     *
     * 原版 = MainPicBox.Width（EmueraConsole.cs:232），即控制台客户区整宽（不减 shift；
     * shift 只进 DrawableWidth，见 [shapeShiftPx]）。布局未发生时为 0 —— 调用方
     * （GameHost.start）只在 [consoleScreen] 就位后才读，与列数同一时点。
     */
    fun clientWidthPx(): Int = (width - paddingLeft - paddingRight).coerceAtLeast(0)

    /**
     * 内容区像素高（GFX-06）：`CLIENTHEIGHT()` 的真机注入源。
     * 原版 = MainPicBox.Height = WindowY - LineHeight（EmueraConsole.cs:234）。
     */
    fun clientHeightPx(): Int = (height - paddingTop - paddingBottom).coerceAtLeast(0)

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
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        return floor(drawableWidthPx(contentWidth) / halfCellPx).toInt().coerceAtLeast(1)
    }

    /**
     * 排障读数（调试通道 `view` 命令）：当前生效的度量与页行数。
     *
     * 覆盖「一页是否正好铺满」的全部判据：可见行数、列数、字号、行高、一页行数、日志行数。
     */
    fun debugMetrics(): String {
        val console = feed?.screen ?: screen
        // 读**实时**视口行数：`ConsoleScreen.heightLines` 是构造期值，`resizeFeedScreen`
        // 改的是 `viewport.heightLines`；排障时要看后者，否则字号调小后读数永远停在 28。
        val rows = console?.viewport?.heightLines ?: 0
        val logLines = console?.log?.size ?: 0
        val fit = PageFitter.fit(width, height, currentPageRows())
        val band = feed?.bandDebug()?.let { " $it" } ?: ""
        return "rows=$rows cols=${console?.clientWidth ?: consoleColumns()} " +
            "font=${fontPixelSize.toInt()} lineH=${lineHeightPx.toInt()} " +
            "pageRows=${currentPageRows()} logLines=$logLines " +
            "viewW=$width viewH=$height fitRows=${fit.rows} fitFont=${fit.fontPixelSize}" + band
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
     * 绘制与命中**共用的纵向原点**（内容区坐标系，像素）。
     *
     * 原版是底对齐的：最后一行顶边 = `MainPicBox.Height - Config.LineHeight`
     * （spec: `EmueraConsole.cs:1805`），首行再按「底部行号 - 顶行号」回推
     * （`:1812` 的 `num -= (num2 - num3) * LineHeight`），循环里逐行下移（`:1852-1856`）。
     * 换成这里的量就是 `contentTop + (contentHeight - heightLines * lineHeightPx)`：
     * [ConsoleScreen] 的 `heightLines` 行正好贴住 `contentBottom`，比它多出来的那排
     * 屏外溢出行落在原点**上方**（由 [drawScreen] 的 clipRect 裁掉），
     * 所以最后一行永远是完整的。
     *
     * 绘制（[drawScreen]）与命中（[tap] / [setPointer] / [mappingColor]）必须取同一个原点，
     * 否则「画在哪」与「点哪里」会差一个余量。早先从 contentTop 自顶向下画时，
     * 视口高不是行高整数倍的最后一行被裁掉一截（真机标题页实测
     * `【欢落五子棋】` 只剩上半截）。
     *
     * 内容区尚未布局（高或宽为 0）时返回 contentTop —— 各调用方都先拦了非正尺寸。
     */
    private fun contentOriginY(console: ConsoleScreen): Float {
        val contentTop = paddingTop.toFloat()
        val contentHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        return contentTop + (contentHeight - console.viewport.heightLines * lineHeightPx)
    }

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
     */
    fun tap(x: Float, y: Float, acceptance: ButtonAcceptance = ButtonAcceptance.ANY): String? {
        val console = feed?.screen ?: screen ?: return null
        // 一次点击（抬起）就是按下态的终点：先清掉高亮，未命中也一样（高亮不残留）。
        // 清除走 [clearPointer] 的置脏口；点击回环本身仍由调用方拿返回值去提交。
        if (pointerDown) clearPointer()
        // 原版顺序（MainWindow.cs:800 在 :803 之前）：命中目标先按贴底前的视图取好。
        val submitted = hitOnCurrentView(console, x, y, acceptance)
        // MainWindow.cs:799-805 的贴底半边：未贴底时任何点击（含未命中）都回最新行。
        if (console.viewport.snapToBottomForTap()) {
            console.refresh()
            requestRedraw()
        }
        return submitted
    }

    /**
     * tap 的命中半段（**贴底前的视图**）：屏幕坐标 → 行列 → `ConsoleScreen.click`。
     * 与 [tap] 拆开是为了让贴底无论命中与否都发生（原版 `MainWindow.cs:801-805`
     * 在命中判定之前执行、不因 early return 被跳过）；点在余量/空白/窗口外返回 null。
     * 纵横换算的口径见 [tap] 的 KDoc，与 [drawScreen] 同源。
     */
    private fun hitOnCurrentView(console: ConsoleScreen, x: Float, y: Float, acceptance: ButtonAcceptance): String? {
        val (hitRow, col) = cellAt(console, x, y) ?: return null
        return console.click(hitRow, col, acceptance)
    }

    /**
     * 屏幕坐标 → 命中口径的 `(行, 列)`；点在余量/窗口外返回 null。
     *
     * [hitOnCurrentView] 与 [tooltipAt] 共用这一份换算，保证「点得中」与
     * 「长按查得到」永远落在同一格。
     */
    private fun cellAt(console: ConsoleScreen, x: Float, y: Float): Pair<Int, Int>? {
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        if (x < contentLeft || y < contentTop) return null
        // 逐行纵布局与 drawScreen 同源（见 [rowLayout]）：图片行被压缩后，行号不能再用
        // 「(y - originY) / lineHeight」估，必须查同一张行顶表。
        val layout = rowLayout(console, console.list.render())
        val idx = layout.rowIndex(y)
        if (idx < 0) return null
        // 只做一次换算：idx 就是快照行（行表按窗口建行，不再有 minRow 平移），
        // 减去绘制窗口多取的 extraRows 就是命中行。
        val hitRow = hitRowForCell(0, idx, extraRowsAbove(console))
        val col = floor((x - contentLeft) / halfCellPx).toInt()
        if (col >= windowColumns()) return null
        return hitRow to col
    }

    /**
     * 长按取按钮提示：该格的 `<button title='…'>` 原文（null = 该格没有按钮/没有提示）。
     *
     * 原版 PC 用鼠标悬停取 `ConsoleButtonString.Title` 做 ToolTip
     * （spec: `EmueraConsole.cs:1891-1917`，`:1909` 把 `<br>` 换成换行）。安卓没有
     * hover，装配层在 `onLongPress` 里调本方法，把原文交给壳层显示。
     * 坐标口径与 [tap] 完全一致（[cellAt]），不会出现「点得中却查不到」。
     */
    fun tooltipAt(x: Float, y: Float): String? {
        val console = feed?.screen ?: screen ?: return null
        val (row, col) = cellAt(console, x, y) ?: return null
        return console.tooltip(row, col)
    }

    /**
     * 客户区（内容区，padding 以内）的像素尺寸；对应原版 `MainPicBox.ClientSize`
     * （`MouseState` 的 `clientWidth/clientHeight` 与 `isPointerInClientArea()` 都用它，
     * spec: `EmueraConsole.cs:2328-2331`、`:2358-2361`）。
     */
    fun clientSizePx(): Pair<Int, Int> {
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val h = (height - paddingTop - paddingBottom).coerceAtLeast(0)
        return w to h
    }

    /** 视图坐标 → 客户区坐标（相对内容区左上角，可为负/越界，边界判定归 `MouseState`）。 */
    fun clientX(viewX: Float): Int = (viewX - paddingLeft).roundToInt()

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
        val (row, col) = cellAt(
            console,
            clientX + paddingLeft.toFloat(),
            clientY + paddingTop.toFloat(),
        ) ?: return null
        return console.hit(row, col)
    }

    /**
     * 触摸按下/抬起 → 行内按钮的按下态高亮（只记绘制标记，不消费事件、不产输入）。
     *
     * 为什么放在这里而不是等装配层接线：main@2d94f78e 的 `MainActivity.attachGestures`
     * 只把触摸喂给 `GestureDetector`（`tap` / 滚动 / 双击），**没有**调用
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
     * 坐标口径与 [tap] 完全一致：横走半角格、纵走 [contentOriginY] 给出的底对齐行窗口；
     * 换算出的行列是**命中口径**（`ButtonLayer` 的行下标 / 屏幕列），交给 `ButtonLayer`
     * 存起来 —— 原版同构，`ButtonIsSelected` / `ButtonIsPointing` 只做引用比较
     * （spec: `EmueraConsole.cs:830-838`）。
     *
     * **装配层接线点**：`MainActivity` 的触摸回调在 `ACTION_DOWN` 调
     * `setPointer(x, y, pressed = true, acceptance)`，在 `ACTION_UP` / `ACTION_CANCEL` 调
     * `setPointer(x, y, pressed = false, ...)` 或 [clearPointer]。当前 main 的
     * `setOnTouchListener` 只喂了 [tap]，所以没有按下反馈（见交付「需组长接线」）。
     * 本方法总会 [requestRedraw]（烘焙后备图置脏），按下/抬起立刻上屏。
     */
    fun setPointer(x: Float, y: Float, pressed: Boolean, acceptance: ButtonAcceptance = ButtonAcceptance.ANY) {
        val console = feed?.screen ?: screen
        if (console == null) {
            resetPointerState(null)
            return
        }
        val contentLeft = paddingLeft.toFloat()
        val layout = rowLayout(console, console.list.render())
        val col = floor((x - contentLeft) / halfCellPx).toInt()
        val idx = if (x < contentLeft || col < 0 || col >= windowColumns()) -1 else layout.rowIndex(y)
        if (idx < 0) {
            // 指在空白/余量/窗口外：清掉旧按下态（否则上一次的高亮永远留着）。
            resetPointerState(console)
            requestRedraw()
            return
        }
        val row = hitRowForCell(0, idx, extraRowsAbove(console))
        pointerHitRow = row
        pointerHitCol = col
        pointerDown = pressed
        pointerAcceptance = acceptance
        console.buttons.setPointer(row, col, pressed, acceptance)
        requestRedraw()
    }

    /** 清掉按下/焦点态（触摸取消、滚出按钮、会话重建时用）；无指针时也幂等。 */
    fun clearPointer() {
        resetPointerState(feed?.screen ?: screen)
        requestRedraw()
    }

    /**
     * 泵一次生产侧（有新快照才失效）：重绘循环的每帧入口。
     *
     * `pump()` 是**发布动作**（发布后 revision 前移、脏区信息随之丢失），循环只该在
     * 真有未发布内容时才 invalidate —— 见 `ConsoleFeed.hasUnpublishedContent` 的 KDoc
     * （2026-10-22 真机收口：每帧 pump 把「发布」与「询问」混在一起）。
     */
    fun pumpAndInvalidateIfNeeded() {
        val dirty = feed?.hasUnpublishedContent() == true
        if (dirty) invalidate()
        // 每帧都问一次「这一页还是不是上次那一页」：翻页/读档/换屏都可能不改 revision 计数口径，
        // 只在 dirty 时问会漏（2026-10-25 真机：读档后字号没重算，一直停在上一个页面的 36px）。
        refitIfPageChanged()
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
     */
    private fun resetPointerState(console: ConsoleScreen?) {
        pointerHitRow = -1
        pointerHitCol = -1
        pointerDown = false
        pointerAcceptance = ButtonAcceptance.ANY
        if (console != null) console.buttons.clearPointer()
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
        invalidate()
    }

    /** 摘掉图形通路（会话关闭 / 读档重建时用）。 */
    fun detachGraphics() {
        graphicsLayer = null
        frameSource = null
        invalidate()
    }

    /** 滚动 [rows] 行：正数 = 看更早的行（上滚），负数 = 回到底部方向（下滚）。 */
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
        // 先按可见区重算字号/行高（一页铺满），再算列数与行数 —— 顺序不能反。
        applyPageFit(w, h)
        val contentWidth = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = (h - paddingTop - paddingBottom).coerceAtLeast(0)
        val cells = floor(drawableWidthPx(contentWidth) / halfCellPx).toInt().coerceAtLeast(1)
        // 视口行数 = **屏上真实能画下的行数**（2026-10-24 修：原来这里多要 40 行图片行，
        // 导致视口比可见区高 40 行）。
        // 后果是「一页内容被截断且上滑够不着」：内容贴底对齐后，窗口顶部那 40 行**永远
        // 画在画布上方**，而 `Viewport.maxOffset = retained - heightLines` 也补不回它们
        // —— 玩家看到的就是「原版一页的内容，手机端上面被切掉，还滚不上去」。
        // 图片溢出的压缩交给 RowLayout.plan（按可用高度缩放），不再靠撑高视口。
        val rows = floor(contentHeight / lineHeightPx).toInt().coerceAtLeast(1)

        android.util.Log.i("EratwView", "rebuildScreen rows=$rows cols=$cells lineH=$lineHeightPx font=$fontPixelSize contentH=$contentHeight")
        val attached = feed
        if (attached != null) {
            // 生产桥挂着时不能换屏幕（`ConsoleFeed` 的 sink 宽 == screen 宽是构造期校验过的
            // 前置条件，换宽会让折行列号漂），但**行数可以安全同步**：[ConsoleScreen] 的
            // `viewport.heightLines` 在 `scroll/Viewport.kt:65-69` 的 KDoc 里就是「窗口 resize
            // 时由绑定层改写」的入口；原版 resize 也只改 `MainWindow` 的客户区高，滚动条
            // 由 `verticalScrollBarUpdate` 重新对齐（spec: `EmueraConsole.cs:2543-2567`）。
            // 宽度仍留给装配层（重建 feed 需要新 sink，归组长接线）。
            resizeFeedScreen(attached.screen, rows)
            syncFeedLayout()
            return
        }
        val current = screen
        if (current != null && current.clientWidth == cells && current.heightLines == rows) {
            return
        }
        val fresh = ConsoleScreen(cells, Grid.MAX_LOG_DEFAULT, rows)
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
     * 放大到超过初次布局的容量时（旋转成横屏）读数会被夹住，画面仍是「多画到容量为止」，
     * 但不再像早先那样直接 return、尺寸永久冻结。
     */
    private fun resizeFeedScreen(console: ConsoleScreen, rows: Int) {
        // 显示列表的容量是构造期按**首帧行高**定的（`ConsoleScreen.kt:159`）。「适应屏幕」
        // 把字号调小后可见行数会变多（真机 36px/28 行 → 30px/34 行），不放容量就会在
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
        // 第一处是 Viewport.sync 的缩短分支）：字号换代 → 可见行数变 → 这里若归零，玩家正看着的
        // 那一屏直接跳到底。现在只由 heightLines 的设值分支重夹到 maxOffset（变矮时夹、变高时守恒），
        // 回底线只保留点击与按键两条原版入口。
        console.refresh()
        invalidate()
    }

    /**
     * 把当前字号/可见区推给 [ConsoleFeed]，让内联图形按新度量重展平一次。
     *
     * 展平器是 `ConsoleFeed` 构造期按当时的 [metrics] 建的：`<img>` 缺省高、占宽格数、
     * 竖带合并的行高都取自它。字号从 36 掉到 30 后不更新，图片仍然按 36 排（带宽 36 vs
     * 行高 31，逐带压到下一行），标题图的比例也会比实际行高偏大。
     *
     * 可用宽取**固定窗口列数** × 当前半角格宽：`sink.clientWidth` 构造期定死，字号变了
     * 也不能按 `contentWidth / 当前格宽` 重算（那会把 123 列算成 148 列，图形占宽越界）。
     */
    private fun syncFeedLayout() {
        val f = feed ?: return
        val contentWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val contentHeight = contentHeightPx()
        if (contentWidth <= 0 || contentHeight <= 0) return
        // 竖带合并的可用宽：单张 <img> 的宽度不再按窗口夹（见 HtmlRunFlatten.emitImage 的 KDoc），
        // 但「把整块横带合成一张、等比缩进屏」这个动作需要一个可用宽，用的还是这个数。
        val maxImageWidth = minOf(contentWidth, (f.screen.clientWidth * metrics.cellPixelWidth).toInt())
        if (f.updateLayout(
                metrics = metrics,
                bandAvailWidthPx = maxImageWidth,
                bandMaxHeightPx = bandMaxHeightPx(),
            )
        ) {
            // 重展平会改行数（竖带合并把 35 行收成 1 图 + 承载空行），本帧之后的
            // rowLayout / 命中表都按新列表算；下一帧 refit 再收敛字号。
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
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
            val src = GraphicGeometry.sourceRect(
                frame.srcX, frame.srcY, frame.srcW, frame.srcH, bitmap.width, bitmap.height,
            )
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
                return
            }
            drawScreen(canvas, screen ?: return)
        } finally {
            dbgFrameDrawNanos += System.nanoTime() - frameStartNs
            dbgFrameCount += 1L
        }
    }

    /**
     * 整帧版面指纹：行顶/行高/内容区尺寸，以及按下态。任一变化都必须整屏重画 ——
     * 因为「同一行」在屏上的位置变了，跳过重画会留下错位残影。
     *
     * 行表改成按窗口建行后**没有 `minRow` 项**了（[RowLayout.Plan] 的下标恒从 0 起），
     * 行数取 `tops.size` —— 窗口行数变了（滚动夹边、字号换代）照样进指纹。
     */
    private fun layoutFingerprint(
        layout: RowLayout.Plan,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        pointerHot: Boolean,
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
        if (pointerHot) h = h * 31 + 12345
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
            h = h * 31 + c.back
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
        val contentLeft = paddingLeft.toFloat()
        val contentTop = paddingTop.toFloat()
        val contentRight = (width - paddingRight).toFloat()
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
        val target = ensureScreenSurface() ?: return
        val fingerprint = layoutFingerprint(
            layout, contentLeft, contentTop, contentRight, contentBottom, pointerDown,
        )
        val rowCount = layout.tops.size
        if (rowSigBuf.size < rowCount) rowSigBuf = IntArray(rowCount.coerceAtLeast(16))
        for (idx in 0 until rowCount) {
            rowSigBuf[idx] = rowSignature(
                rowCmds[idx], pressedRow, pressedLo, pressedHi,
            )
        }
        // 显式置脏在这里被原子地消费（旧实现只写不读，「置脏即整屏重画」是空头承诺）。
        val forced = forceRedraw.getAndSet(false)
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
            graphicPresent = hasGraphic,
            backgroundPresent = hasBackground,
            forced = forced,
        )
        lastFrameFull = planner.full
        dbgFrameRows += planner.lastRasteredRows.toLong()
        dbgFrameScanNanos += System.nanoTime() - scanStartNs

        if (hasGraphic) {
            // 整屏路（planner 对含图形的帧一律判整幅）：图片/色块可以溢出到相邻行，
            // 逐行 clip 会切出断层（2026-10-24 真机实证），所以这里不逐行、不查缓存。
            // 扫描段到此为止（下面全是落笔）：命令表就是上面展平的那份 [frameCmds]。
            target.save()
            target.clipRect(contentLeft, contentTop, contentRight, contentBottom)
            target.drawColor(BACKGROUND)
            drawBackgroundsMeasured(target, contentLeft, contentTop, contentRight, contentBottom)
            // **两趟铺墨**（缺陷 B）：第一趟铺「不出自己那一行」的命令，第二趟单独铺
            // 越出的图形段 —— 与原版同构：`EmueraConsole.cs:1850-1857` 先把行自上而下画完，
            // `:1858-1872` 才把 `FilterEscaped`（`ConsoleButtonString.cs:165-185`，判式在 `:171`）
            // 收进的溢出部件按 `num6 + (LineNo - num3) * LineHeight`（`:1870`）补铺在上面。
            // 端口以前只有单趟 ⇒ 立绘/动图后面的字盖在图上（玩家报的「历史里的动图被文本覆盖」）。
            // 同一个 frameCmds 走两遍：不新建列表、每帧零分配；趟内保持原有行升序 ⇒
            // 同行内列序不变（原版 `ConsoleButtonString.DrawTo` `:317-363` 对 strArray
            // 全量画、不排除 escaped 部件，所以原版是「原位画两遍 + 溢出的再铺一遍」，
            // 净效果就是溢出部件盖在文字之上）。
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
            target.restore()
            blitSurface(canvas)
            return
        }
        if (planner.full) {
            // 整幅帧先把保留面铺成底色：跳过重画的前提是「那块像素已经是最终结果」，
            // 刚清空的面上不成立。背景层同样在这里一次性铺（逐行再铺会盖掉上一行的溢出）。
            target.drawColor(BACKGROUND)
            if (hasBackground) drawBackgroundsMeasured(target, contentLeft, contentTop, contentRight, contentBottom)
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
                pressedRow = pressedRow,
                pressedLo = pressedLo,
                pressedHi = pressedHi,
            )
        }
        blitSurface(canvas)
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
    private fun ensureScreenSurface(): Canvas? {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return null
        var bmp = screenSurface
        if (bmp == null || bmp.width != w || bmp.height != h || bmp.isRecycled) {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            screenSurface = bmp
            screenSurfaceCanvas = Canvas(bmp)
            surfaceWidth = w
            surfaceHeight = h
            bmp.eraseColor(BACKGROUND)
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
     * 口径与旧实现的逐行绘制逐项相同：`save → clipRect(行区间) →（增量帧才）drawColor(BACKGROUND)
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
            target.drawColor(BACKGROUND)
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
        // 横向由 contentLeft = paddingLeft（整数像素）与列量化保证，无需额外条件。
        val cacheable = cmds.size > 1 && rowTopPx % 1f == 0f
        val wPx = (contentRight - contentLeft).roundToInt()
        val hPx = ceil(rowHeightPx.toDouble()).toInt()
        if (cacheable && wPx > 0 && hPx > 0) {
            val hit = rowCache.find(sig, hPx, wPx, cmds.size)
            if (hit != null) {
                target.drawBitmap(hit.value, contentLeft, rowTopPx, blitPaint)
                target.restore()
                return
            }
            val bmp = obtainRowBitmap(wPx, hPx)
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
        for (cmd in cmds) {
            drawCmd(
                target, cmd, contentLeft, rowTopPx, rowHeightPx,
                false, pressedRow, pressedLo, pressedHi,
            )
        }
        target.restore()
    }

    /** 取一张 [w]x[h] 的行位图：同尺寸的回收池优先，没有再新建。 */
    private fun obtainRowBitmap(w: Int, h: Int): Bitmap {
        val pool = rowBitmapPool
        for (i in pool.indices) {
            val b = pool[i]
            if (!b.isRecycled && b.width == w && b.height == h) {
                pool.removeAt(i)
                rowPoolBytes -= b.allocationByteCount.toLong()
                return b
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        if (bakeGuard.needsRebake(w, h, backgrounds) { resource -> source.frame(resource) }) {
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
     * 画一段 [DrawCmd]。纵原点 [originY] 由 [contentOriginY] 给（底对齐），命中侧同源。
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
            drawGraphic(
                canvas, graphic, originX + cmd.colStart * halfCellPx,
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


        // 行内按钮**不画白描边**（2026-10-23 用户实机反馈「白描看起来很脏」）：
        // 可点性由按下高亮（[BUTTON_PRESSED_BACK]）与焦点字色（[cmd.focusFore]）表达，
        // 静态描边去掉后正文与选项的底色一致，视觉更干净。命中几何不受影响。

        paint.color = opaque(if (pressed) cmd.focusFore else cmd.fore)
        paint.textSize = fontPixelSize
        paint.isFakeBoldText = cmd.styleMask and Grid.STYLE_BOLD != 0
        paint.textSkewX = if (cmd.styleMask and Grid.STYLE_ITALIC != 0) ITALIC_SKEW else 0f
        paint.isUnderlineText = cmd.styleMask and Grid.STYLE_UNDERLINE != 0
        paint.isStrikeThruText = cmd.styleMask and Grid.STYLE_STRIKE != 0
        var col = cmd.colStart
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
        // [typefaceFor] / [baselineOffsetOf] 都带「上一个族」快路径，同族连续字形零查表。
        for (glyph in cmd.glyphs) {
            if (col >= cmd.colEnd) {
                break
            }
            paint.typeface = typefaceFor(glyph.fontName)
            // **必须先归位 textScaleX 再测量**：`Paint.measureText` 会被当前 textScaleX 缩放，
            // 而 metricOf 的缓存只在首次见到该字形时量一次 —— 若带着上一字形的缩放去量，
            // 缓存里存的就是错宽，之后所有缩放都算歪（真机：标题页正文重叠、方块字）。
            paint.textScaleX = 1f
            val metric = metricOf(glyph)
            val glyphCells = metric.cells
            if (glyphCells <= 0) continue
            paint.textScaleX = if (metric.measured > 0f) glyphCells * halfCellPx / metric.measured else 1f
            // 任务 perf-render2：单次 drawText 的墙钟与次数（f.textNs / f.textCalls）。
            // 计时区间含实参求值里的 [baselineOffsetOf]（同族快路径，一次 null 比较），
            // 口径与上一波一致：**包住整条 drawText 调用**，不含 paint 赋值与度量查表。
            val textStartNs = System.nanoTime()
            canvas.drawText(
                metric.text,
                originX + col * halfCellPx,
                rowTop + baselineOffsetOf(glyph.fontName),
                paint,
            )
            glyphTextNs += System.nanoTime() - textStartNs
            glyphTextCalls += 1L
            col += glyphCells
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

    /** 取（并缓存）字形度量：字符串只在这里建一次，绘制期不再分配。 */
    private fun metricOf(glyph: GlyphKey): GlyphMetric {
        val bucket = bucketFor(glyph.fontName)
        val key = packGlyphKey(glyph)
        bucket[key]?.let { return it }
        val text = String(Character.toChars(glyph.codePoint))
        val metric = GlyphMetric(text, paint.measureText(text), Grid.cells(text))
        bucket[key] = metric
        return metric
    }

    /** 取（必要时建）该字体的度量桶，并把 [metricOf] 的快路径指过去。 */
    private fun bucketFor(fontName: String): HashMap<Long, GlyphMetric> {
        val cached = lastFontBucket
        if (cached != null && lastFontName == fontName) return cached
        val bucket = metricsByFont.getOrPut(fontName) { HashMap(1024) }
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
     * 落位口径逐条照原版：横向起点是**列游标**（与文本同一个 `PointX`，
     * spec: `ConsoleImagePart.cs:170-172` 的 `rectangle.X = destRect.X + PointX + shift`），
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
            drawImageAltText(canvas, image, originLeft, rowTop, cmd)
            return
        }

        val w = GraphicGeometry.inlineSize(image.widthPx, frame.width)
        val h = GraphicGeometry.inlineSize(image.heightPx, frame.height)
        if (w <= 0 || h <= 0) return
        val src = GraphicGeometry.sourceRect(
            frame.srcX, frame.srcY, frame.srcW, frame.srcH, bitmap.width, bitmap.height,
        )
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
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
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
            val src = GraphicGeometry.sourceRect(
                f.srcX, f.srcY, f.srcW, f.srcH, bmp.width, bmp.height,
            )
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
     * ShapePositionShift（`:177,181` 用的是裸 `PointX`）。altText 为空（生产侧没给）时
     * 保持静默不画。
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
        // 裸列游标落点（ConsoleImagePart.cs:177,181 的裸 PointX）：绘制链传进来的
        // originLeft 本就是「paddingLeft + colStart * 半角步进」，不含 ShapePositionShift
        // （移植侧把 shift 折进可绘制宽的预留，drawableWidthPx 里减掉），不需要还原。
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
     * 返回 0 = 原版 `return 0L` 的三条分支（遮罩缺失 / 未创建 / 点不在图内，
     * spec: `ConsoleImagePart.cs:137,148,156`）。位图像素在基图上的定位照
     * `SpriteGetColor`：取色点 + 子矩形左上角，出基图界返回 0（Transparent 的 RGB）。
     *
     * **消费点归装配层**：本方法只给数；把它按 INPUT(6/3) 语义喂进输入队列需要在
     * app/platform 的触摸路径接一次（本路禁改那两个模块，见交付「需组长接线」）。
     */
    fun mappingColor(x: Float, y: Float): Long {
        val console = feed?.screen ?: screen ?: return 0L
        val source = frameSource ?: return 0L
        val contentLeft = paddingLeft.toFloat()
        // 纵原点与 [drawScreen]/[drawImage] 同源（底对齐，见 [contentOriginY]）：
        // 取色点的换算必须与绘制落位逐式一致，否则点在图上的位置对不上。
        val originY = contentOriginY(console)
        if (x < contentLeft || y < originY) return 0L
        // 溢出排数符号同 [drawScreen]/[tap]：`hitRowBase - visibleRowBase`（装满时 +1）。
        // 这里必须与 drawImage 的 rowTop 逐式一致，否则取色点会整体偏一排（点在图上的位置对不上）。
        val extraRows = (console.hitRowBase - console.visibleRowBase)
            .coerceIn(0, ConsoleScreen.EXTRA_ABOVE_ROWS)
        var result = 0L
        for (cmd in console.list.render()) {
            val image = cmd.graphic as? InlineGraphic.Image ?: continue
            val maskName = image.mappingResource?.takeIf { it.isNotEmpty() } ?: continue
            // 图本体缺帧 ⇒ 没有有效绘制框：原版构造期查不到 sprite 时 destRect 保持全 0
            // （spec: ConsoleImagePart.cs:60-64 提前 return，:82 没执行），
            // `GetMappedColor` 的范围判 `num < mask.Width`（Width 也停在 0，`:104-115`）必不成立 ⇒ 0。
            val imgFrame = if (image.resource.isEmpty()) null else source.frame(image.resource)
            if (imgFrame == null) continue
            val rowTop = originY + (cmd.row - extraRows) * lineHeightPx
            // 绘制框与 drawImage 逐式一致：同一个翻正入口、同一个 inlineSize 兜底。
            val xFix = InlineGraphic.fixNegativeExtent(0, image.widthPx)
            val yFix = InlineGraphic.fixNegativeExtent(image.yOffsetPx, image.heightPx)
            val destW = GraphicGeometry.inlineSize(xFix[1], imgFrame.width)
            val destH = GraphicGeometry.inlineSize(yFix[1], imgFrame.height)
            val localX = (x - (contentLeft + cmd.colStart * halfCellPx + shapeShiftPx + xFix[0])).toInt()
            val localY = (y - (rowTop + yFix[0])).toInt()
            val mask = source.frame(maskName)
            val maskBitmap = mask?.handle as? Bitmap
            val sample = InlineGraphic.maskSamplePoint(
                localX, localY, destW, destH,
                mask?.width ?: 0, mask?.height ?: 0,
            )
            // 点在图内 ⇒ 本段就是候选；带遮罩段叠放时以**后段**为准
            // （getLastImg 从数组尾部取第一个 ConsoleImagePart，spec: ConsoleButtonString.cs:60-68）。
            if (sample != null) {
                result = if (mask != null && maskBitmap != null) {
                    val px = mask.srcX + sample.first
                    val py = mask.srcY + sample.second
                    if (px in 0 until maskBitmap.width && py in 0 until maskBitmap.height) {
                        (maskBitmap.getPixel(px, py) and 0xFFFFFF).toLong()
                    } else {
                        0L // ASpriteSingle.cs:55-60：基图外返回 Transparent ⇒ RGB 0
                    }
                } else {
                    0L // 遮罩缺失 / 未创建（ConsoleImagePart.cs:137,148,156 的 return 0L）
                }
            }
        }
        return result
    }

    /**
     * 配置里的字体名 → 该族的 Typeface（带缓存，缺包回落见 [loadTypeface]）。
     *
     * 选族本身是纯逻辑（[BundledFontFamilies.resolve]，纯 JVM 有单测），这里只负责
     * 「族 → Android 对象」的落地与缓存。命中 [lastTypefaceFamily] 时零 map 查表 ——
     * 逐字形调用，热路径不能每次两次哈希。
     */
    private fun typefaceFor(fontName: String?): Typeface {
        val entry = BundledFontFamilies.resolve(fontName)
        val key = entry.family
        if (key == lastTypefaceFamily) return lastTypeface ?: Typeface.MONOSPACE
        val tf = typefaces.getOrPut(key) { loadTypeface(entry) ?: Typeface.MONOSPACE }
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
     */
    private fun baselineOffsetOf(fontName: String?): Float {
        val family = BundledFontFamilies.resolve(fontName).family
        baselines[family]?.let { return it }
        val previousTypeface = paint.typeface
        val previousSize = paint.textSize
        paint.typeface = typefaceFor(fontName)
        paint.textSize = fontPixelSize
        val fm = paint.fontMetrics
        paint.typeface = previousTypeface
        paint.textSize = previousSize
        val offset = (lineHeightPx - (fm.descent - fm.ascent)) / 2f - fm.ascent
        baselines[family] = offset
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

        /** 缺省目标列数（半角格）：110 ⇒ 2400px 长边下字号约 42px ≈ 15dp，手机可读。
         *
         * PC 基准是 199 列（`Config.cs:461-466`：WindowX 1800 - shift 3 = 1797px ÷ 9px
         * 步进，佐证在 `fixtures/golden/baseline/pc-golden-root.txt:253` 的 199 个 `=`）：
         * 按 199 列反推，手机上的字号只有约 23px ≈ 7.7dp，看不清 —— 所以缺省取 110，
         * 要逐像素对齐 PC 版面的排障场景用构造参数 `fontColumnsOverride` 传 199。
         */
        private const val DEFAULT_FONT_COLUMNS = 110

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
        private val BACKGROUND = 0xFF000000.toInt()
    }
}
