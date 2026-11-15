package com.eratw.port.render.canvas

/**
 * Android 字形光栅器的纯几何层（CONTRACT-MODERN-R11-T4B S2）。
 *
 * 把 advance 到目标框、ascent/descent 到基线、脚本样式位到绘制标志、空 advance 到零长位图
 * 这四组算式收成纯函数：不引 Android API、不依赖本模块其它类，JVM 单测可直接跑。
 *
 * 公式与桌面基线 tools/uipreview 的 AwtGlyphRaster.kt:141-189 是同一套：
 * 位图框宽取 advance 非正则给零、框高取行高；基线夹进零到行高的盒、descent 是行高减基线；
 * 粗斜两位进字型、删除线与下划线手画线；advance 非正时给零长位图而不是 null。
 *
 * 脚本样式位与 GDI FontStyle 位不等价（那边 4 是下划线、8 是删除线），
 * 见 render/glyph/GlyphRaster.kt:71-75；这里只输出抽象标志，由 AndroidGlyphRaster 映射到 Paint。
 * 线位公式是确定性选择，真机像素对账前记 TODO(verify)。
 */
object AndroidGlyphMath {

    /** 脚本样式位（与 Grid.STYLE_* 同口径）：1 粗 2 斜 4 删除线 8 下划线。 */
    const val STYLE_BOLD = 1
    const val STYLE_ITALIC = 2
    const val STYLE_STRIKE = 4
    const val STYLE_UNDERLINE = 8

    /** 抽象字型风格：0 常规 1 粗 2 斜 3 粗斜（与 AWT Font 的前四档同序，不是 Android 常量）。 */
    const val TYPEFACE_NORMAL = 0
    const val TYPEFACE_BOLD = 1
    const val TYPEFACE_ITALIC = 2
    const val TYPEFACE_BOLD_ITALIC = 3

    /** advance 到目标框：宽取 advance（非正给零），高取行高（AwtGlyphRaster.kt:97,110-118）。 */
    fun targetBox(advancePixels: Int, lineHeightPx: Int): GlyphBox =
        GlyphBox(
            widthPx = if (advancePixels > 0) advancePixels else 0,
            heightPx = lineHeightPx,
        )

    /** 空位图规则：框为空时给零长数组，不是 null，也不是一像素占位框。 */
    fun bitmapFor(advancePixels: Int, lineHeightPx: Int): IntArray =
        IntArray(targetBox(advancePixels, lineHeightPx).pixelCount)

    /** 基线夹进行高盒：像素字体 ascent 可能超行高，夹住保证位图内不越界（AwtGlyphRaster.kt:110）。 */
    fun clampBaseline(ascentPx: Int, lineHeightPx: Int): Int = ascentPx.coerceIn(0, lineHeightPx)

    /** 基线以下高度；与 clampBaseline 相加恒等于行高（GlyphRasterConformance 第 5 条同口径）。 */
    fun descentPx(baselinePx: Int, lineHeightPx: Int): Int = lineHeightPx - baselinePx

    /** 手画线线宽：至少一像素，随行高按十六分之一给（AwtGlyphRaster.kt:189）。 */
    fun lineThickness(lineHeightPx: Int): Int = maxOf(1, lineHeightPx / 16)

    /**
     * 样式位映射：粗斜合成抽象字型风格，删除线与下划线给手画线的 y 位。
     * 线位照 AwtGlyphRaster.kt:164-172：删除线在基线减四分之一行高处、
     * 下划线在基线加一个线宽处，两者都夹进零到行高减线宽的盒。
     */
    fun styleFlags(styleMask: Int, baselinePx: Int, lineHeightPx: Int): GlyphStyle {
        var typefaceStyle = TYPEFACE_NORMAL
        if (styleMask and STYLE_BOLD != 0) typefaceStyle = typefaceStyle or TYPEFACE_BOLD
        if (styleMask and STYLE_ITALIC != 0) typefaceStyle = typefaceStyle or TYPEFACE_ITALIC
        val thickness = lineThickness(lineHeightPx)
        val yLimit = (lineHeightPx - thickness).coerceAtLeast(0)
        return GlyphStyle(
            typefaceStyle = typefaceStyle,
            strike = styleMask and STYLE_STRIKE != 0,
            underline = styleMask and STYLE_UNDERLINE != 0,
            strikeY = (baselinePx - lineHeightPx / 4).coerceIn(0, yLimit),
            underlineY = (baselinePx + thickness).coerceIn(0, yLimit),
            thickness = thickness,
        )
    }
}

/** 目标框（像素）。空框一律配零长位图。 */
data class GlyphBox(val widthPx: Int, val heightPx: Int) {
    /** 宽或高非正即空框。 */
    val isEmpty: Boolean get() = widthPx <= 0 || heightPx <= 0

    /** 像素数；空框为零，保证 bitmapWidth 乘 bitmapHeight 恒等于位图长度。 */
    val pixelCount: Int get() = if (isEmpty) 0 else widthPx * heightPx
}

/** 样式位映射的产物：抽象字型风格加两条手画线的几何。 */
data class GlyphStyle(
    val typefaceStyle: Int,
    val strike: Boolean,
    val underline: Boolean,
    val strikeY: Int,
    val underlineY: Int,
    val thickness: Int,
)
