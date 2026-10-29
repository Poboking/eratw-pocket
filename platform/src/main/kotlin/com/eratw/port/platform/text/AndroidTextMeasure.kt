package com.eratw.port.platform.text

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import com.eratw.port.render.glyph.CellMetrics

/**
 * FN-04 platform：Android 侧**分段**文本量宽（CONTRACT-Q2 §2.4、CONTRACT-W3 w3-l09）。
 *
 * 原版量宽问的是真字体：`ConsoleStyledString.SetWidth` → `StringMeasure.GetDisplayLength`
 * （StringMeasure.cs:42-72），字体按 **字体名 + 字号 + 样式位** 三件套从 `FontFactory` 取
 * （FontFactory.cs:9-50），GDI+ 的度量结果本身就随粗体/斜体/face 变化。core 侧旧的
 * `BuiltinHost.measureText` 只有纯文本回调（Builtins.kt:149），所以 HTML 段样式在
 * `HTML_STRINGLEN` 一族里被抹平 —— 本文件就是补这条「带样式」的量宽通道。
 *
 * ## 分工：纯换算在 [TextMeasureMath]，Android 调用只在本类
 *
 * 可测的约定（样式位 → 粗/斜/删/下、字体名 → 族键、字号守卫、charSpace 折算、
 * 像素 → 半角格）全部放在 [TextMeasureMath]，由 `AndroidTextMeasureTest` 在纯 JVM 上钉死；
 * 本类只做 `Paint` / `Typeface` 调用与逐字推进（薄层口径同 `AndroidGlyphMaskHost`）。
 *
 * ## 单位（读之前必看，契约措辞与本仓 core 口径有出入）
 *
 * - core 的量宽回调返回**像素**：`BuiltinHost.measureText` 的 KDoc 写的是「文本显示宽度测量
 *   （像素）」（Builtins.kt:145-149），`HtmlMethods.approxTextWidth` 也把全角折成
 *   `fontSize` 像素、半角折成 `fontSize/2` 像素（HtmlMethods.kt:464-506），基准
 *   `HTML_STRINGLEN("<i>AB</i>CD") = 36` 正是 4 个半角 × 18/2（HtmlMethods.kt:41）。
 * - CONTRACT-Q2 §2.4 说「返回**半角格数**」，若把它当回调返回值，`HTML_STRINGLEN` 会从 36
 *   缩成 4。故本类主 API 是 [measurePixels]（接线用这个），格数是同一读数的第二种表达，
 *   由 [measureCells] / [TextMeasureMath.pixelsToCells] 提供，口径 = `StringMeasure` 的
 *   量化式（StringMeasure.cs:65）与 `CellMetrics.pixelsToCells`（CellMetrics.kt:83-88）。
 *
 * ## 字体族
 *
 * 原版按名字取族：空名先归一成 `Config.FontName`（FontFactory.cs:13-17，StringStyle.cs:26-31），
 * 族名比较是**序号相等**（`fontFamily.Name == text`，FontFactory.cs:40，大小写敏感）；
 * 查不到族时 `new Font` 走 GDI+ 的缺省族、异常则整体回 null（FontFactory.cs:33-55）。
 * 本类把族表做成注入式 `Map<String, Typeface>`：命中原名用对应族，空名/未知族/大小写不一致
 * 一律回落 [defaultTypeface]（[TextMeasureMath.resolveFamily]）。随包字体名到真族名的多族映射
 * 与 `AndroidGlyphMaskHost` 一样仍是 TODO(verify)，卡点是随包 ttf 只有单一字面。
 *
 * ## charSpace
 *
 * 契约要求「每字加 charSpace 像素」：`charSpace != 0` 时逐**码位**量前进量再加
 * `码位数 × charSpace`（[TextMeasureMath.totalPixels]），整串只在最后量化一次。
 * TODO(verify)：C# 侧 grep `charSpace` / `CharSpacing` 零命中（`reference/emuera-cs` 全树），
 * 该字段是 FN-04 core 契约新加的，原版没有对应语句；本实现按契约字面执行，待与 w3-l07
 * 的 `TextMeasureRequest` 对账。
 *
 * ## 样式位
 *
 * 1=粗体、2=斜体、4=删除线、8=下划线（Process.cs:1508-1527 的 FONTSTYLE 逐位判、
 * FunctionMethodCreator.cs:6361-6381 的 GSETFONT 第 4 参逐位判；本仓同口径登记在
 * LineBufferConsoleSink.kt:134）。**不要把这个数值当 GDI+/Android 的 FontStyle 枚举**：
 * GDI+ 的 Underline/Strikeout 恰好是 4/8 互换（GlyphMaskMath 的 KDoc 已登记）。
 * 删除线/下划线不改前进量（GDI+ 装饰位不参与 MeasureCharacterRanges，StringMeasure.cs:63-65），
 * Android 侧同样只作溯源、不参与量宽。
 *
 * ## 线程与状态
 *
 * 实例级 `Paint` + 实例锁，无静态可变全局（AGENTS.md 铁律 4）；同一入参读数幂等。
 * 失败（空 text 之外的非法字号）回 null，由 core 落回旧 `measureText` 再落等宽近似。
 *
 * 生产接线（`GameSession` / `ConsoleBuiltinHost`）不归本文件，由组长在 w3-l07 的
 * `TextMeasureRequest` 合流后把五个字段逐个传进 [measurePixels]。
 */
class AndroidTextMeasure private constructor(
    private val families: Map<String, Typeface>,
    private val defaultTypeface: Typeface,
) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * 一段文本的显示宽度（**像素**，整串一次四舍五入），口径 = `StringMeasure.GetDisplayLength`
     * （StringMeasure.cs:42-72）。
     *
     * @param text 段文本（可含增补平面；逐码位取用，同 core 的 `codePointAt` + `charCount` 口径）
     * @param fontName 段字体名；空串 / 未知族回落 [defaultTypeface]（FontFactory.cs:13-17/33-55）
     * @param fontSize 字号（**像素**，原版 `GraphicsUnit.Pixel`，ConfigData.cs:66 默认 18）
     * @param styleMask 脚本样式位（1/2/4/8，见类 KDoc）
     * @param charSpace 每码位附加像素（见类 KDoc；0 = 整串一次度量、保留字距调整）
     * @return null = 字号非正（原版 `new Font` 会抛，移植侧回 null 让 core 回落）或度量异常
     */
    fun measurePixels(
        text: String,
        fontName: String,
        fontSize: Long,
        styleMask: Int,
        charSpace: Long = 0L,
    ): Long? {
        if (text.isEmpty()) return 0L
        val sizePx = TextMeasureMath.fontSizePx(fontSize) ?: return null
        val plan = TextMeasureMath.stylePlan(styleMask)
        val advance = runCatching {
            synchronized(paint) {
                paint.typeface = typefaceFor(fontName)
                paint.textSize = sizePx
                // 随包字体只有常规字面，粗/斜都走合成（与 AndroidGlyphMaskHost 同口径）
                paint.isFakeBoldText = plan.bold
                paint.textSkewX = if (plan.italic) ITALIC_SKEW else 0f
                if (charSpace == 0L) {
                    // 整串一次：保留 kerning，贴 MeasureCharacterRanges（StringMeasure.cs:63-65）
                    paint.measureText(text).toDouble()
                } else {
                    var sum = 0.0
                    var i = 0
                    while (i < text.length) {
                        val cp = text.codePointAt(i)
                        val next = i + Character.charCount(cp)
                        sum += paint.measureText(text, i, next).toDouble()
                        i = next
                    }
                    sum
                }
            }
        }.getOrNull() ?: return null
        return TextMeasureMath.totalPixels(advance, TextMeasureMath.charCount(text), charSpace)
    }

    /**
     * 同 [measurePixels]，但折成**半角格数**（CONTRACT-Q2 §2.4 的字面产物）。
     *
     * 量化式 = `StringMeasure.cs:65`：`((宽度 - 1) / (字号/2 × 1.04) + 0.95)` 向零截断，
     * 与 `CellMetrics.pixelsToCells` 同一实现（CellMetrics.kt:83-88）。字号 < 8 时按
     * `Config.cs:431-434` 抬到 8（`CellMetrics` 构造口径）。所以它**只是** [measurePixels]
     * 的另一种表达，接线到 core 的必须是像素那条。
     */
    fun measureCells(
        text: String,
        fontName: String,
        fontSize: Long,
        styleMask: Int,
        charSpace: Long = 0L,
    ): Int? {
        val pixels = measurePixels(text, fontName, fontSize, styleMask, charSpace) ?: return null
        return TextMeasureMath.pixelsToCells(pixels, fontSize)
    }

    private fun typefaceFor(fontName: String): Typeface =
        TextMeasureMath.resolveFamily(fontName, families.keys)?.let { families[it] } ?: defaultTypeface

    companion object {

        /** 合成斜体斜切量（与 [AndroidGlyphMaskHost] 同值，非契约数值）。 */
        private const val ITALIC_SKEW: Float = -0.25f

        /**
         * 显式族表构造：`families` 的键是**原版族名原文**（大小写敏感，FontFactory.cs:40），
         * 值是平台侧 Typeface。未命中的名字一律回落 [defaultTypeface]。
         *
         * 供组长接线时传入真族映射（黑体 / ＭＳ ゴシック 等 → 随包子集或系统族）。
         */
        fun of(families: Map<String, Typeface>, defaultTypeface: Typeface): AndroidTextMeasure =
            AndroidTextMeasure(families.toMap(), defaultTypeface)

        /**
         * 缺省构造：族表为空、默认族 = 随包字体（缺包回落 [Typeface.DEFAULT]，
         * 载入路径与 `AndroidGlyphMaskHost` 同一处，不复制第二份 asset 常量）。
         *
         * TODO(verify)：多族映射未定（随包 ttf 只有单一字面），此时任何 fontName 都落随包字体；
         * 卡点与 `AndroidGlyphMaskHost.typefaceFor` 的 TODO 同源，归组长真机对账。
         */
        fun fromContext(context: Context, families: Map<String, Typeface> = emptyMap()): AndroidTextMeasure {
            val default = AndroidGlyphMaskHost.loadBundledTypeface(context.applicationContext)
                ?: Typeface.DEFAULT
            return AndroidTextMeasure(families.toMap(), default)
        }
    }
}

/**
 * [AndroidTextMeasure] 的**纯换算**部分（无 Android API，可在 JVM 单测里钉死）。
 *
 * 这里只回答四个问题：样式位怎么摊成粗/斜/删/下、字体名命中哪个族、字号是否可用、
 * 逐字附加 charSpace 之后整串多宽、像素折几格。Android 调用全部留在 [AndroidTextMeasure]。
 */
object TextMeasureMath {

    /** 脚本样式位：粗体（Process.cs:1512-1515）。 */
    const val STYLE_BOLD: Int = 1

    /** 脚本样式位：斜体（Process.cs:1516-1519）。 */
    const val STYLE_ITALIC: Int = 2

    /** 脚本样式位：删除线（Process.cs:1520-1523）。 */
    const val STYLE_STRIKEOUT: Int = 4

    /** 脚本样式位：下划线（Process.cs:1524-1527）。 */
    const val STYLE_UNDERLINE: Int = 8

    /**
     * 样式位摊平结果。只有 [bold] / [italic] 影响量宽；[strikeout] / [underline] 是装饰位，
     * GDI+ 与 Android 都不让它们改变前进量，保留字段只为逐位对齐与溯源。
     */
    data class StylePlan(
        val bold: Boolean,
        val italic: Boolean,
        val strikeout: Boolean,
        val underline: Boolean,
    )

    /**
     * 样式位 → 绘制度量计划。位定义见 [STYLE_BOLD] 一族；bit4 以上的高位一律忽略
     * （脚本只判 bit0..bit3，FunctionMethodCreator.cs:6361-6381；GSETFONT 侧另叠 `and 0xF`
     * 截断，GraphicsMethods.kt:382）。
     */
    fun stylePlan(styleMask: Int): StylePlan = StylePlan(
        bold = styleMask and STYLE_BOLD != 0,
        italic = styleMask and STYLE_ITALIC != 0,
        strikeout = styleMask and STYLE_STRIKEOUT != 0,
        underline = styleMask and STYLE_UNDERLINE != 0,
    )

    /**
     * 段字体名的有效形态：null / 空串归一成 null（= 用缺省族），其余**原样**返回。
     *
     * 依据：`StringStyle` 构造把空字体名归一成 `Config.FontName`（StringStyle.cs:26-31），
     * `FontFactory.GetFont` 开头的 `string.IsNullOrEmpty` 分支同判（FontFactory.cs:13-17）。
     * 不做 trim/大小写折叠 —— 原版族名比较是序号相等（FontFactory.cs:40），空白或大小写不同
     * 就是另一个族（大概率查不到，落缺省）。
     */
    fun effectiveFontName(fontName: String?): String? =
        if (fontName.isNullOrEmpty()) null else fontName

    /**
     * 字体名 → 命中的族键；null = 用缺省族。
     *
     * 命中条件 = 非空且与 [availableFamilies] 里某个键**序号相等**（FontFactory.cs:33-49 的
     * `fontFamily.Name == text`）。未知族回落缺省而不是报错：原版查不到族时 `new Font`
     * 走 GDI+ 缺省族、抛异常则整体回 null（FontFactory.cs:44-55），两者都不是「换一个名字继续」。
     */
    fun resolveFamily(fontName: String?, availableFamilies: Set<String>): String? {
        val requested = effectiveFontName(fontName) ?: return null
        return if (requested in availableFamilies) requested else null
    }

    /**
     * 字号（Long 字段，像素）→ Paint 用的 Float；null = 非正，调用方回 null 让 core 回落。
     *
     * 原版 `new Font(name, emSize, ...)` 对 emSize ≤ 0 抛 ArgumentException
     * （GraphicsMethods.kt:539-543 同判、FunctionMethodCreator.cs:6550-6590 分支的 KDoc 引行），
     * 移植侧不抛。`Config.cs:431-434` 的「小于 8 抬到 8」是**配置层**的修正，不属于
     * 本守卫（真实配置不会给 0），只在 [pixelsToCells] 经 `CellMetrics` 时生效。
     */
    fun fontSizePx(fontSize: Long): Float? =
        if (fontSize <= 0L) null else fontSize.toFloat()

    /**
     * 码位数（charSpace 的「每字」口径）。增补平面算**一个**字
     * （`codePointAt` + `charCount`，与 core 的 `CellWidth.of` 遍历口径一致，
     * CellWidth.kt:66-75）。
     */
    fun charCount(text: String): Int = text.codePointCount(0, text.length)

    /**
     * 逐字附加 charSpace 后的整串像素宽，并在**最后**量化一次。
     *
     * `total = basePx + 码位数 × charSpace`；`basePx` 是 Paint 量到的前进量合计
     * （charSpace 为 0 时是整串一次读数，否则是逐码位读数之和）。量化用四舍五入
     * （`Math.round`，0.5 向上）：原版 `GetDisplayLength` 返回 int（StringMeasure.cs:42-72），
     * 本仓 `TextMetrics.measurePixels` 也是「整串一次截断」而不是逐字截断
     * （render/glyph/TextMetrics.kt 的测量 KDoc），两者都不许逐字各自取整后再相加。
     *
     * @return null = basePx 非有限/为负或码位数为负（调用方数据坏）；负 charSpace 允许
     *   （收紧字距），合计为负时按 0 处理
     */
    fun totalPixels(basePx: Double, charCount: Int, charSpace: Long): Long? {
        if (!basePx.isFinite() || basePx < 0.0) return null
        if (charCount < 0) return null
        val total = basePx + charCount.toDouble() * charSpace.toDouble()
        if (total <= 0.0) return 0L
        return Math.round(total)
    }

    /**
     * 像素 → 半角格（CONTRACT-Q2 §2.4 的换算，口径同 `CellWidth.of` 的格数）。
     *
     * 用 `CellMetrics.pixelsToCells`（CellMetrics.kt:83-88），它照抄 `StringMeasure.cs:65`
     * 的量化式 `((像素 - 1) / (字号/2 × 1.04) + 0.95)` 向零截断：字号 18 时 36px → 4 格、
     * 18px → 2 格、9px → 1 格，与 `HTML_STRINGLEN("<i>AB</i>CD") = 36`（4 个半角，
     * HtmlMethods.kt:41、基准 `pc-golden-root.txt:297`）一致。
     *
     * @return null = 字号非正；像素非正给 0（StringMeasure.cs:53-56 的空串口径）
     */
    fun pixelsToCells(pixels: Long, fontSize: Long): Int? {
        if (fontSize <= 0L) return null
        if (pixels <= 0L) return 0
        // CellMetrics 的字号入参是 Int；超界钳到 Int 上界（真机字号不会到这档）
        val size = fontSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val px = pixels.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return CellMetrics(requestedFontPixelSize = size).pixelsToCells(px)
    }
}
