package com.eratw.port.platform.text

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * M11-E glyph-platform 路的**纯函数助手**（CONTRACT-M11-E §2/§4，判据③「JVM 单测可跑」）。
 *
 * 这里只放与 Android API 无关的换算：码位→串、sizePx/styleMask→绘制计划、离屏盒布局、
 * ARGB→alpha 提取、墨迹包围盒与裁剪、bearing 换算、缓存键。
 * [AndroidGlyphMaskHost] 把 Paint/Bitmap/Canvas 的调用集中成一层薄封装，
 * 数值口径全部先在这里算好再喂给 Android API —— 这样换算约定可以在纯 JVM 单测里钉死，
 * 组长在有 SDK 的闸上只需要验「薄封装有没有把数喂错」，不需要重推数学。
 *
 * ## 掩码语义（与契约 §4 逐字对齐）
 *
 * `GlyphMask.alpha` 是**行主序**、`size = width*height`、值域 `0..255` 的覆盖度
 * （CONTRACT-M11-E §4 字段注释原文「行主序，size = width*height，0..255 覆盖度」）；
 * 抗锯齿的灰度覆盖率直接取 ARGB 像素的 alpha 通道
 * （GDI+ 侧 `SmoothingMode.AntiAlias` 同口径，spec: GraphicsImage.cs:171）。
 *
 * ## bearing 原点系（权威 = 核心侧 KDoc，本节与之逐字同口径）
 *
 * 权威引行：`core/host/GlyphMaskHost.kt:41-59`（glyph-core 路已落地；
 * 本仓 worktree 参考副本 `eratw-wt-m11e-glyph-core/eratw-android/core-vm/src/main/kotlin/`
 * 下同路径。核心侧没有基线概念，参照系是**行盒顶**）。
 *
 * 掩码左上角落位（核心侧 GDRAWTEXT 消费点 `fn/GraphicsMethods.kt` 的
 * `compositeGlyphMask(⌊penX⌋ + bearingX, lineTop + bearingY)`，spec: 同仓 :524-529）：
 *
 * ```text
 * 掩码左上角 = ( ⌊penX⌋ + bearingX , lineTop + bearingY )
 * ```
 *
 * - `lineTop` = **行盒顶**（首行 = GDRAWTEXT 的 y 原点，逐行 +sizePx；
 *   `AddString` 的 origin 是 em 盒左上角而非基线，spec: 本仓 GraphicsMethods.kt:421-425）；
 * - `bearingX = bounds.left - drawX`：掩码左缘相对笔位的水平偏移，**正数向右**
 *   （`drawX` 对应离屏盒里的笔位 x；可负，左超/斜体悬出）；
 * - `bearingY = bounds.top - lineTopY`：**掩码顶缘相对行盒顶的垂直偏移，正数向下**。
 *   这**不是** FreeType 的 top bearing（那是「自基线向上」的正向）；Y 在 Android 位图里
 *   本就向下，越靠下越大，故「正数向下」与位图坐标同向，`bounds.top` 越大越向下偏移。
 * - 两个 bearing 都可以为负（上伸部悬出行盒顶、左移/斜体悬出笔位）；掩码贴到画布上时
 *   越界部分由核心侧 `compositeGlyphMask` 按 Long 裁到画布内、**不报错**，
 *   宿主侧不做裁剪、也不因越界报错
 *   （spec: 权威 KDoc :58-59，契约 §5「glyph-core」判据④同一口径）。
 *
 * ### 基线系 → 行盒顶系的换算（本路的离屏盒布局）
 *
 * 权威 KDoc :55-57 给的换算是 `bearingY = ascentFromBoxTop - topBearing`，其中
 * 「行盒顶到基线的距离由宿主自己按字体竖度量给出」。本路的取法是**声明内容区顶行即行盒顶**：
 * 离屏盒布局为 `drawX = pad`、`baselineY = pad + ceil(aboveBaselinePx)`，
 * 去掉外扩 pad 后的第一行 `lineTopY = pad` 就取作 `lineTop`（于是
 * `ascentFromBoxTop = baselineY - lineTopY = ceil(aboveBaselinePx)`，由宿主竖度量
 * `-fm.top` 给出，核心侧不需要知道它）。代入权威式，与基线相关的项相消：
 *
 * ```text
 * bearingY = ascentFromBoxTop - topBearing
 *          = (baselineY - lineTopY) - (baselineY - bounds.top)
 *          = bounds.top - lineTopY
 * ```
 *
 * ⇒ 数值管道（墨迹包围盒 → 裁剪 → alpha）与取墨口径完全不变，只有 `bearingY` 一行
 * （以及 `OffscreenLayout` 要把 `lineTopY` 显式带出来，不能拿 `drawX` 隐式凑 ——
 * 当前 `layoutFor` 的四边 pad 同源，`lineTopY` 与 `drawX` 数值相等，但两者语义不同：
 * `drawX` 是笔位、`lineTopY` 是行盒顶，将来上下 pad 分家时 `lineTopY` 必须独立变化）。
 */
object GlyphMaskMath {

    /** 离屏盒单边像素上限：超过即放弃取掩码（宿主回 null，调用方落回推进盒），与「不许抛」契约同口径。 */
    const val MAX_SIDE_PX: Int = 4096

    /**
     * 契约约定的覆盖度值域上限（alpha 通道满值）。
     * spec: CONTRACT-M11-E §4「0..255 覆盖度」。
     */
    const val MAX_COVERAGE: Int = 255

    /**
     * 码位 → 单字形串。非法码位（非正、超 U+10FFFF、落在代理区 D800..DFFF 的码元）返回 null，
     * 由宿主整体回 null（无此字形），**不抛** —— 对齐 [com.eratw.port.render.glyph.GlyphRaster]
     * 契约 1「字体不可用、码位缺字、内部异常一律返回 null」（spec: FontFactory.cs:52-54）。
     *
     * 增补面口径：本仓字符串按 UTF-16 码元计（GDRAWTEXT 逐码位取用
     * `line.codePointAt(i)` + `Character.charCount`，spec: 本仓 GraphicsMethods.kt:484-485），
     * 所以辅助平面码位在这里还原成**代理对两码元**的串去喂 measure/drawText。
     */
    fun codePointToString(cp: Int): String? {
        if (cp <= 0) return null
        if (cp > 0x10FFFF) return null
        if (cp in 0xD800..0xDFFF) return null
        return StringBuilder().appendCodePoint(cp).toString()
    }

    /**
     * sizePx/styleMask → 绘制计划（纯数据，Android 无关）。
     *
     * @property textSizePx 字号（像素）；Pixel 单位下 emSize 即字号
     *   （GDI+ 侧 `GraphicsUnit.Pixel`，spec: FunctionMethodCreator.cs:6394,6400）
     * @property syntheticBold 脚本位 1：GDI+ `FontStyle.Bold` 由本位驱动
     *   （spec: FunctionMethodCreator.cs:6365-6368），平台侧走 Paint.setFakeBoldText
     * @property syntheticItalic 脚本位 2：GDI+ `FontStyle.Italic`（spec: :6369-6372），
     *   平台侧走 Paint.setTextSkewX（斜切量非本契约范围，见宿主 KDoc TODO(verify)）
     * @property underline 脚本位 8：GDI+ `FontStyle.Underline`（spec: :6377-6380）。
     *   **不影响掩码墨迹**：`GraphicsPath.AddString` 只按 Bold/Italic 选轮廓，
     *   Underline/Strikeout 装饰位在 GDI+ 文本路径上不产墨（本仓登记同判：
     *   GraphicsMethods.kt:450-451「样式位…对替代字形没有影响」）；Android drawText 同样不画。
     *   字段保留仅作溯源。
     * @property strikeout 脚本位 4：GDI+ `FontStyle.Strikeout`（spec: :6373-6376），同上仅作溯源
     */
    data class GlyphPaintPlan(
        val textSizePx: Float,
        val syntheticBold: Boolean,
        val syntheticItalic: Boolean,
        val underline: Boolean,
        val strikeout: Boolean,
    )

    /**
     * 样式位映射。**位含义是脚本侧约定，不是 GDI+ FontStyle 的枚举数值**：
     * 1=粗体、2=斜体、4=删除线、8=下划线（spec: Process.cs:1508-1530 的 FONTSTYLE 逐位判、
     * FunctionMethodCreator.cs:6361-6381 的 GSETFONT 第 4 参逐位判；本仓同口径登记在
     * render/glyph/GlyphRaster.kt:59-63 与 LineBufferConsoleSink.kt:134）。
     * GDI+ `FontStyle` 的数值序是 Bold=1、Italic=2、**Underline=4、Strikeout=8**，
     * 4/8 两位与脚本位**恰好互换** —— 禁止把 styleMask 原样当 FontStyle 传下去
     * （GlyphRaster.kt:62-63 的警告，本函数就是那条「必须显式映射」的落地）。
     *
     * 忽略位：脚本只判 bit0..bit3（`FunctionMethodCreator.cs:6365-6380` 四个 if），
     * 高位连同 bit4 以上一律吞掉；GSETFONT 侧再叠一道 `mask and 0xF` 截断
     * （spec: 本仓 GraphicsMethods.kt:382）。
     * TODO(verify)：styleMask 位含义的**最终权威**在 glyph-core 路的 GDRAWTEXT 段
     * （它此刻只把 `g.fontStyle`（0xF 截断后）与控制台 `fontStyleMask` 喂进测量，
     * spec: GraphicsMethods.kt:471），字形通道的取用口径须由组长与 glyph-core 对账。
     *
     * 非正/非有限字号返回 null：原版 `new Font(name, emSize, ...)` 对 emSize ≤ 0 抛
     * ArgumentException（spec: FunctionMethodCreator.cs:6584 分支的 KDoc 引行，
     * 本仓 GraphicsMethods.kt:539-543 同判）；移植侧不抛，回 null 走无字形回落。
     */
    fun paintPlanFor(sizePx: Float, styleMask: Int): GlyphPaintPlan? {
        if (!sizePx.isFinite() || sizePx <= 0f) return null
        return GlyphPaintPlan(
            textSizePx = sizePx,
            syntheticBold = styleMask and 1 != 0,
            syntheticItalic = styleMask and 2 != 0,
            underline = styleMask and 8 != 0,
            strikeout = styleMask and 4 != 0,
        )
    }

    /**
     * 离屏位图布局：盒宽 = ceil(advance) + 2×pad（左右悬出余量），
     * 盒高 = ceil(above + below) + 2×pad，绘制基准点 = (pad, pad + ceil(above))。
     *
     * 坐标系声明（对象 KDoc「基线系 → 行盒顶系的换算」的落地）：
     * [drawX] 是**笔位**所在列、[baselineY] 是 Android drawText 的基线行，
     * [lineTopY] 是**行盒顶**所在行 —— 本路把「去掉 pad 后的内容区顶行」声明为行盒顶，
     * 于是掩码落位直接用核心侧口径 `lineTop + bearingY`（`bearingY = bounds.top - lineTopY`）。
     * 当前四边 pad 同源 ⇒ [lineTopY] 与 [drawX] 数值相等，但语义不同，故各存一个字段，
     * `bearingY` 只引用 [lineTopY]，不给 [drawX] 加隐式假设。
     *
     * @param advancePx 码位推进量（像素，paint.measureText 口径）
     * @param aboveBaselinePx 基线以上的取墨高度（≥0；宿主喂 `-fm.top`，
     *   FontMetrics.top 是「最大上升」，比 ascent 保守，够装 CJK 满高字形）
     * @param belowBaselinePx 基线以下的取墨深度（≥0；宿主喂 `fm.bottom`）
     * @param padPx 四边外扩像素（悬出/斜体用，宿主按字号比例给，见 AndroidGlyphMaskHost）
     * @return null = 入参非有限/为负/盒超 [MAX_SIDE_PX]，宿主放弃本掩码
     */
    data class OffscreenLayout(val width: Int, val height: Int, val drawX: Int, val baselineY: Int, val lineTopY: Int)

    fun layoutFor(advancePx: Float, aboveBaselinePx: Float, belowBaselinePx: Float, padPx: Int): OffscreenLayout? {
        if (!advancePx.isFinite() || !aboveBaselinePx.isFinite() || !belowBaselinePx.isFinite()) return null
        if (advancePx < 0f || aboveBaselinePx < 0f || belowBaselinePx < 0f || padPx < 0) return null
        val width = ceil(advancePx).toInt() + 2 * padPx
        val height = ceil(aboveBaselinePx + belowBaselinePx).toInt() + 2 * padPx
        if (width <= 0 || height <= 0 || width > MAX_SIDE_PX || height > MAX_SIDE_PX) return null
        return OffscreenLayout(
            width = width,
            height = height,
            drawX = padPx,
            baselineY = padPx + ceil(aboveBaselinePx).toInt(),
            // 内容区顶行 = 外扩 pad 之后的第一行，声明为核心侧的「行盒顶」
            lineTopY = padPx,
        )
    }

    /** 单像素 ARGB `0xAARRGGBB` → 覆盖度（alpha 通道），值域 0..255。 */
    fun alphaOf(argb: Int): Int = (argb ushr 24) and 0xFF

    /**
     * 整块 ARGB → 行主序覆盖度数组（`size = width*height`）；RGB 通道丢弃。
     * 输入必须是 [layoutFor] 那档盒的 `Bitmap.getPixels` 原样数组（索引 y*width + x）。
     */
    fun extractAlpha(pixels: IntArray, width: Int, height: Int): ByteArray {
        val alpha = ByteArray(width * height)
        for (i in 0 until width * height) {
            alpha[i] = alphaOf(pixels[i]).toByte()
        }
        return alpha
    }

    /** 墨迹包围盒，半开区间：x ∈ [left, right)，y ∈ [top, bottom)。 */
    data class InkBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * 找覆盖度**严格大于** [threshold] 的像素包围盒；全盒无墨返回 null
     * （空白码位/零步进字形在 GDI+ 侧 FillPath 不出像素，spec: 本仓 GraphicsMethods.kt:447-449
     * 的「空白码位不落墨」口径 —— 宿主据 null 回 null，落回调用方的推进盒分支）。
     * 缺省阈值 0：抗锯齿淡边一律算墨（覆盖度语义里 1/255 也是墨）。
     */
    fun inkBounds(alpha: ByteArray, width: Int, height: Int, threshold: Int = 0): InkBounds? {
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((alpha[row + x].toInt() and 0xFF) > threshold) {
                    if (x < left) left = x
                    if (x >= right) right = x + 1
                    if (y < top) top = y
                    if (y >= bottom) bottom = y + 1
                }
            }
        }
        if (right < 0) return null
        return InkBounds(left, top, right, bottom)
    }

    /** 按包围盒裁覆盖度；输出仍是行主序、size = bounds.width × bounds.height。 */
    fun cropAlpha(alpha: ByteArray, width: Int, bounds: InkBounds): ByteArray {
        val out = ByteArray(bounds.width * bounds.height)
        for (y in 0 until bounds.height) {
            val srcRow = (bounds.top + y) * width + bounds.left
            System.arraycopy(alpha, srcRow, out, y * bounds.width, bounds.width)
        }
        return out
    }

    /** bearingX = 墨迹左缘 − 笔位；掩码左缘相对笔位的水平偏移，正数向右（见对象 KDoc「bearing 原点系」）。 */
    fun bearingX(bounds: InkBounds, layout: OffscreenLayout): Int = bounds.left - layout.drawX

    /**
     * bearingY = 墨迹上缘 − 行盒顶；**掩码顶缘相对行盒顶的垂直偏移，正数向下**
     * （权威：核心侧 `core/host/GlyphMaskHost.kt:54-59`；换算推导见对象 KDoc
     * 「基线系 → 行盒顶系的换算」一节）。
     *
     * 与 FreeType top bearing（基线减墨顶、自基线向上为正）方向相反、原点也不同：
     * 按权威式的换算 `bearingY = ascentFromBoxTop - topBearing`，展开后
     * `(baselineY - lineTopY) - (baselineY - bounds.top) = bounds.top - lineTopY`，
     * 基线在推导中相消，故本函数不需要 [OffscreenLayout.baselineY]。
     * 结果可为负（上伸部/悬出行盒顶）；越界裁剪归核心侧 `compositeGlyphMask`，宿主不报错。
     */
    fun bearingY(bounds: InkBounds, layout: OffscreenLayout): Int = bounds.top - layout.lineTopY

    /**
     * 宿主缓存键：同一 (码位, 字体名, 字号, 样式位) 必须同键、任一不同必不同键。
     * 对齐原版 `FontFactory` 字典键含字号的口径（spec: FontFactory.cs:9,18-25）。
     * fontName 用「空串」与 null 区分（null=未设字体走兜底名，""=显式空名），
     * 大小写**区分**（原版字体查找是序号比较，spec: FontFactory.cs:40）。
     */
    fun cacheKey(cp: Int, fontName: String?, sizePx: Float, styleMask: Int): String =
        "$cp|${fontName ?: "\u0000null"}|${sizePx.toRawBits().toString(16)}|$styleMask"

    /** 离屏外扩像素：字号的 1/4，下限 2、上限 16（悬出与斜体墨迹的经验夹逼；纯函数可测）。 */
    fun padPxFor(sizePx: Float): Int =
        max(2, min(16, ceil(sizePx / 4f).toInt()))
}
