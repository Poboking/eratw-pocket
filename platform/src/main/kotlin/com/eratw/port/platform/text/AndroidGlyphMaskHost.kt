package com.eratw.port.platform.text

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import com.eratw.port.core.host.GlyphMask
import com.eratw.port.core.host.GlyphMaskHost
import com.eratw.port.core.host.GlyphRequest
import com.eratw.port.render.font.BundledFontFamilies
import com.eratw.port.render.font.BundledFontFamily

/**
 * Android 字形掩码宿主（CONTRACT-M11-E §2「m11e-glyph-platform」路、§4 钉死接口、§5 判据①-⑤）。
 *
 * 职责：把 `Paint + Bitmap + Canvas.drawText + Bitmap.getPixels`（readPixels）栅格出来的
 * 抗锯齿灰度，转成契约约定的 `GlyphMask`（行主序、size = width*height、0..255 覆盖度，
 * bearing 原点系**以核心侧 `core/host/GlyphMaskHost.kt:41-59` 为权威**：掩码左上角
 * = `(⌊penX⌋ + bearingX, lineTop + bearingY)`，`lineTop` 是行盒顶、`bearingY` 正数向下；
 * 本类的换算落地见 [GlyphMaskMath] 对象 KDoc「bearing 原点系」一节，两端同口径）。
 *
 * 本类是**薄封装**（判据④）：Android API 调用全部集中在 [rasterize] 与
 * [loadBundledTypeface] 两处；换算/裁切/取墨的数学都在 [GlyphMaskMath] 纯函数里，
 * 已由 platform 的纯 JVM 单测钉死。契约接口 core/host/GlyphMaskHost.kt 由并行的
 * glyph-core 路新建，本文件按 §4 签名直接 import，合流前 platform main 编译不过属预期，
 * 由组长合流统一处理。
 *
 * 消费链（只读参考，本路不接线，判据⑤「不碰 app/」）：
 * GDRAWTEXT 段 `(c.host as? GlyphMaskHostProvider)?.glyphMaskHost`
 * （spec: 本仓 GraphicsMethods.kt:454-517 的绘制体 + CONTRACT §4 消费点行）。
 * 接线时由组长把本宿主实例经 hostwire 路的 `GameSession(glyphMaskHost = ...)` 形参注入。
 *
 * 线程与状态：实例级 `synchronized` 缓存，无静态可变全局（AGENTS.md 铁律 4）；
 * [glyphMask] 对同一入参幂等（禁时间禁随机，对齐 GlyphRaster 契约 3）。
 */
class AndroidGlyphMaskHost(context: Context) : GlyphMaskHost {

    private val appContext: Context = context.applicationContext

    /**
     * 族名 → Typeface 的实例缓存。多族（GFX-12）：默认正文族（当前 Saitamaar）
     * 与设置页的备选族。`createFromAsset` 每次都要解一遍 ttf，必须缓存。
     */
    private val typefaces = HashMap<String, Typeface>(5)

    private val cache = HashMap<String, GlyphMask?>()

    /**
     * 取字形掩码；**任何**失败（非法码位、非正字号、无墨、位图分配失败、内部异常）都回 null，
     * 调用方回落推进盒 —— 「不许抛」契约照抄 FontFactory.GetFont 的 try/catch → null
     * （spec: FontFactory.cs:52-54，本仓登记在 render/glyph/GlyphRaster.kt:33-34）。
     */
    override fun glyphMask(cp: Int, req: GlyphRequest): GlyphMask? {
        val key = GlyphMaskMath.cacheKey(cp, req.fontName, req.sizePx, req.styleMask)
        synchronized(cache) {
            // containsKey 而非 getOrPut：「无此字形」的 null 结论也要命中缓存，
            // 否则每次空格/缺字都重走一遍位图栅格。
            if (cache.containsKey(key)) return cache[key]
            if (cache.size >= CACHE_MAX) cache.clear()
        }
        val mask = runCatching { rasterize(cp, req) }.getOrNull()
        synchronized(cache) {
            if (cache.size < CACHE_MAX) cache[key] = mask
        }
        return mask
    }

    // ---- Android 依赖面（薄封装第 1 处）----

    /** Paint/Bitmap/Canvas/getPixels 的全部调用都只在这一段；数值口径先经 [GlyphMaskMath] 算好。 */
    private fun rasterize(cp: Int, req: GlyphRequest): GlyphMask? {
        val text = GlyphMaskMath.codePointToString(cp) ?: return null
        val plan = GlyphMaskMath.paintPlanFor(req.sizePx, req.styleMask) ?: return null
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = typefaceFor(req)
            textSize = plan.textSizePx
            isFakeBoldText = plan.syntheticBold
            // 斜体走合成斜切（随包字体只有常规字面）；斜切量 -0.25 是 Android 合成惯例，
            // 原版那边是 GDI+ FontStyle.Italic 的真轮廓（spec: FunctionMethodCreator.cs:6369-6372），
            // 两边墨迹形状不逐像素等 —— 登记 TODO(verify)，归真机目测 + 组长对账。
            textSkewX = if (plan.syntheticItalic) ITALIC_SKEW else 0f
            // 覆盖度只取 alpha 通道 ⇒ 颜色任意；给不透明黑保证 RGB 侧无惊喜。
            color = 0xFF000000.toInt()
            style = Paint.Style.FILL
        }
        val advance = paint.measureText(text)
        val fm = paint.fontMetrics
        val pad = GlyphMaskMath.padPxFor(plan.textSizePx)
        // 竖盒取 fm.top/fm.bottom（比 ascent/descent 保守）：Android 文档口径
        // 「top/bottom = 该 Typeface 实际最大升降」，够装 CJK 满高与降部；
        // GDI+ 侧对应 emHeight/lineSpacing 那一档缺口见本仓 GraphicsMethods.kt:529-530 的 TODO(verify)。
        // 行盒顶：[GlyphMaskMath.layoutFor] 把内容区顶行（=pad 那一行）声明为核心侧的 lineTop，
        // 于是下面 bearingY = bounds.top - lineTopY 即权威「正数向下」口径（推导见 GlyphMaskMath KDoc；
        // 核心侧没有基线概念，baselineY 只在本类离屏盒内部使用）。
        val layout = GlyphMaskMath.layoutFor(
            advancePx = advance,
            aboveBaselinePx = -fm.top,
            belowBaselinePx = fm.bottom,
            padPx = pad,
        ) ?: return null
        val bitmap = Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawText(text, layout.drawX.toFloat(), layout.baselineY.toFloat(), paint)
            val pixels = IntArray(layout.width * layout.height)
            bitmap.getPixels(pixels, 0, layout.width, 0, 0, layout.width, layout.height)
            val alpha = GlyphMaskMath.extractAlpha(pixels, layout.width, layout.height)
            val bounds = GlyphMaskMath.inkBounds(alpha, layout.width, layout.height) ?: return null
            return GlyphMask(
                width = bounds.width,
                height = bounds.height,
                bearingX = GlyphMaskMath.bearingX(bounds, layout),
                bearingY = GlyphMaskMath.bearingY(bounds, layout),
                alpha = GlyphMaskMath.cropAlpha(alpha, layout.width, bounds),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 字体名 → Typeface 的最小映射（CONTRACT §5 判据②）：**一律用随包字体**，
     * `req.fontName` **参与选族**（GFX-12 换字体波）：族名经 [BundledFontFamilies.resolve]
     * 折成登记族（`ＭＳ ゴシック` / `黑体` / 空串 / 未知名 → 默认族 Saitamaar，
     * `Saitamaar` 同族），再按族取 Typeface。
     * 与 `CanvasConsoleView.typefaceFor` 同一张表、同一条回落链 —— 两处口径必须一致，
     * 否则 `GDRAWTEXT` 的掩码与屏上正文会是两种字形。
     * 族缺失（裁剪包/坏包）时回落 [Typeface.DEFAULT]（判据②钉的就是 DEFAULT）。
     */
    private fun typefaceFor(req: GlyphRequest): Typeface {
        val entry = BundledFontFamilies.resolve(req.fontName)
        synchronized(typefaces) {
            typefaces[entry.family]?.let { return it }
            val loaded = loadTypeface(appContext, entry) ?: Typeface.DEFAULT
            typefaces[entry.family] = loaded
            return loaded
        }
    }

    // ---- Android 依赖面（薄封装第 2 处）----

    companion object {

        /**
         * 默认族的 assets 路径。物理源文件由 [BundledFontFamilies.default] 给出
         * （`render/src/main/resources/fonts/` 下的 TTF，由 Gradle 同步进 assets、
         * 不复制进 git）；这里不写死字面量 —— 换默认族只改注册表一处。
         */
        val FONT_ASSET: String = BundledFontFamilies.default.asset

        /** 合成斜体斜切量（Android 惯例值，非契约数值，见 [rasterize] 内 TODO(verify)）。 */
        private const val ITALIC_SKEW: Float = -0.25f

        /** 实例缓存上限：超限即整表清空（掩码可重算，清表只损性能不损正确性）。 */
        private const val CACHE_MAX: Int = 4096

        /**
         * 按登记族加载随包字体：`Typeface.createFromAsset` 缺包/坏包抛 RuntimeException
         * （android.graphics 契约），照抄 render-canvas 的 try/catch 形态，差别只在
         * 缺省回 null 让调用侧落 [Typeface.DEFAULT]。
         */
        fun loadTypeface(context: Context, family: BundledFontFamily): Typeface? =
            try {
                Typeface.createFromAsset(context.assets, family.asset)
            } catch (_: RuntimeException) {
                null
            }

        /** 兼容入口：加载**默认族**（旧调用点语义）。 */
        fun loadBundledTypeface(context: Context): Typeface? =
            loadTypeface(context, BundledFontFamilies.default)
    }
}
