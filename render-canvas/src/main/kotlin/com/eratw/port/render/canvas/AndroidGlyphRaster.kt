package com.eratw.port.render.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.eratw.port.render.font.BundledFontFamilies
import com.eratw.port.render.font.GlyphFallback
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.glyph.FallbackWidthClass
import com.eratw.port.render.glyph.FontCoverage
import com.eratw.port.render.glyph.GlyphRaster
import com.eratw.port.render.glyph.RasterizedGlyph

/**
 * GlyphRaster 的 Android 实现（CONTRACT-MODERN-R11-T4B S3）：Typeface.create 加 Paint 逐字形出图。
 *
 * 四契约：
 * 不抛 —— 整条路径包 try，平台字体栈不可用一律折成这一跳没有字形（FontFactory.cs:52-54）；
 * 族名大小写敏感 —— 覆盖表按精确字符串键查（序号口径，GlyphRaster 契约 2）；
 * 确定性 —— 同入参不读时间、不读随机、不回写共享字体状态；
 * 字号入键 —— CellMetrics 与 textSize 都吃 sizePx，不把字号吞掉。
 *
 * 缺字判定与 StaticGlyphRaster 同源：只看 FontCoverage.covers 的判序（显式缺字集优先于全覆盖）。
 * 族级回落链不在这里手写：链成员与顺序只由 GlyphFallback.chainFor 回答，默认族取链首族；
 * 逐族试画由 GlyphCache 的跳序负责，本类只回答单族命中。
 *
 * advance 走 CellMetrics 的网格退化口径（与 StaticGlyphRaster 缺度量行那条同式）；
 * 真机接 sidecar 表、抗锯齿覆盖率、字色合成、Paint.hasGlyph 谓词与 sidecar 有行的差异
 * 全部记 TODO(verify)：本机 adb devices 为空，无真机截图读数。
 */
class AndroidGlyphRaster(
    fonts: List<FontCoverage> = BundledFontFamilies.ALL.map { FontCoverage(it.family, coversAllCodePoints = true) },
) : GlyphRaster {

    /** 默认族 = 回落链首族（链只经 GlyphFallback，不手写第二条链）。 */
    override val defaultFontName: String =
        GlyphFallback.chainFor(BundledFontFamilies.default).first().family

    private val coverages: Map<String, FontCoverage> = fonts.associateBy { it.name }

    override fun rasterize(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph? =
        try {
            rasterizeUnsafe(fontName, styleMask, codePoint, sizePx)
        } catch (ignored: Throwable) {
            null
        }

    private fun rasterizeUnsafe(fontName: String, styleMask: Int, codePoint: Int, sizePx: Int): RasterizedGlyph? {
        // 精确键查表：大小写不同的名字是另一族，未登记一律在出图前就返回 null（不抛）。
        val coverage = coverages[fontName] ?: return null
        if (!coverage.covers(codePoint)) return null

        val converter = CellMetrics(sizePx)
        // 零宽档（组合符/变体选择符）给 0 步进配零长位图，其余按布局格数换算：
        // 与 StaticGlyphRaster.missingGlyphAdvancePixels 的退化口径同式，不另起第二条分档。
        val advance = if (converter.fallbackClassOf(codePoint) == FallbackWidthClass.ZERO) {
            0
        } else {
            converter.cellsToPixels(converter.cellsOf(codePoint))
        }
        val box = AndroidGlyphMath.targetBox(advance, converter.lineHeight)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val faceStyle = AndroidGlyphMath.styleFlags(styleMask, 0, box.heightPx)
        paint.typeface = Typeface.create(fontName, androidTypefaceStyle(faceStyle.typefaceStyle))
        paint.textSize = converter.fontPixelSize.toFloat()
        paint.color = INK_COLOR
        val baseline = AndroidGlyphMath.clampBaseline(
            (-paint.fontMetrics.ascent).toInt(),
            box.heightPx,
        )
        val style = AndroidGlyphMath.styleFlags(styleMask, baseline, box.heightPx)

        val bitmap = if (box.isEmpty) {
            IntArray(0)
        } else {
            drawToBitmap(paint, codePoint, baseline, box, style)
        }
        return RasterizedGlyph(
            advancePixels = advance,
            lsbPixels = 0,
            ascentPixels = baseline,
            descentPixels = AndroidGlyphMath.descentPx(baseline, box.heightPx),
            bitmapWidth = box.widthPx,
            bitmapHeight = box.heightPx,
            bitmap = bitmap,
        )
    }

    private fun drawToBitmap(
        paint: Paint,
        codePoint: Int,
        baseline: Int,
        box: GlyphBox,
        style: GlyphStyle,
    ): IntArray {
        val bmp = Bitmap.createBitmap(box.widthPx, box.heightPx, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bmp)
            canvas.drawText(String(Character.toChars(codePoint)), 0f, baseline.toFloat(), paint)
            if (style.strike) {
                canvas.drawRect(
                    0f, style.strikeY.toFloat(), box.widthPx.toFloat(),
                    (style.strikeY + style.thickness).toFloat(), paint,
                )
            }
            if (style.underline) {
                canvas.drawRect(
                    0f, style.underlineY.toFloat(), box.widthPx.toFloat(),
                    (style.underlineY + style.thickness).toFloat(), paint,
                )
            }
            val out = IntArray(box.pixelCount)
            bmp.getPixels(out, 0, box.widthPx, 0, 0, box.widthPx, box.heightPx)
            return out
        } finally {
            bmp.recycle()
        }
    }

    /** 抽象字型风格到 Android Typeface 常量：粗斜位显式映射，不把脚本掩码直传。 */
    private fun androidTypefaceStyle(abstractStyle: Int): Int = when (abstractStyle) {
        AndroidGlyphMath.TYPEFACE_BOLD -> Typeface.BOLD
        AndroidGlyphMath.TYPEFACE_ITALIC -> Typeface.ITALIC
        AndroidGlyphMath.TYPEFACE_BOLD_ITALIC -> Typeface.BOLD_ITALIC
        else -> Typeface.NORMAL
    }

    private companion object {
        /** 覆盖率掩码的墨色：ARGB 全不透明白，着色在绘制侧。 */
        const val INK_COLOR: Int = -1
    }
}
