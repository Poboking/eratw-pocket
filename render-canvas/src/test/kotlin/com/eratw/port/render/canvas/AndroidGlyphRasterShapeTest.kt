package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * AndroidGlyphRaster 与 CanvasConsoleView 的源码形状闸（CONTRACT-MODERN-R11-T4B S3/S4）。
 *
 * 这两个类都吃 Android 平台 API，本模块只有纯 JVM 单测、实例化不了，所以钉代码形状：
 * 后端族链只经 GlyphFallback、开关构造期读一次且缺省关、关态分支先于缓存路径。
 * 反向证明 B 手拼回落链、C 把开关缺省改 true 都要让这里红。
 * 真机上屏与位图像素记 TODO(verify)。
 */
class AndroidGlyphRasterShapeTest {

    private fun readSource(name: String): String =
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/$name")
            .readText()

    /**
     * 剥掉行注释与块注释、保留换行（行号与源文件一一对应）。写法照同目录
     * FontFallbackChainSingleSourceTest 复制一份：本模块测试源集没有共享 helper。
     */
    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '/') {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        if (raw[i] == '\n') sb.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val rasterCode: String by lazy { stripComments(readSource("AndroidGlyphRaster.kt")) }
    private val viewCode: String by lazy { stripComments(readSource("CanvasConsoleView.kt")) }

    @Test
    fun `AndroidGlyphRaster 的族链只经 GlyphFallback 不手拼第二条链`() {
        assertTrue(
            rasterCode.contains("GlyphFallback.chainFor("),
            "默认族必须取 GlyphFallback.chainFor 的链首，族级回落链只此一个权威（反向证明 B）",
        )
        assertTrue(
            !rasterCode.contains("BundledFontFamilies.fallbacksFor("),
            "AndroidGlyphRaster 不许直接读 fallbacksFor 手拼第二条链",
        )
    }

    @Test
    fun `AndroidGlyphRaster 是 Typeface 加 Paint 后端 整条路径吞异常不抛且字号入键`() {
        assertTrue(rasterCode.contains("Typeface.create("), "真机后端必须走 Typeface.create 加 Paint")
        assertTrue(rasterCode.contains("Paint("), "真机后端必须走 Typeface.create 加 Paint")
        assertTrue(rasterCode.contains("catch (ignored: Throwable)"), "不许把字体栈异常抛给调用方")
        assertTrue(rasterCode.contains("CellMetrics(sizePx)"), "字号必须进入度量换算，不许被吞掉")
    }

    @Test
    fun `开关构造期读一次且缺省关`() {
        assertTrue(
            viewCode.contains("System.getProperty(\"eratw.render.glyphCache\") == \"1\""),
            "开关必须是精确等于 1 才开，缺省 false（反向证明 C）",
        )
        assertTrue(
            viewCode.contains("private val glyphCacheEnabled: Boolean"),
            "开关在 View 构造期读一次并固化为 val，不在热路径上反复读",
        )
    }

    @Test
    fun `关态分支先于缓存路径且关态既有语句仍在`() {
        val guardIdx = viewCode.indexOf("if (glyphCacheEnabled)")
        val getIdx = viewCode.indexOf("glyphCache().get(")
        assertTrue(guardIdx >= 0, "drawCmdText 与 metricOf 必须各有一个关态 guard")
        assertTrue(getIdx >= 0, "开态的 GlyphCache 取数路径必须存在")
        assertTrue(
            guardIdx < getIdx,
            "关态分支必须先于 GlyphCache 的取数路径（缺省关时缓存路径根本不进）",
        )
        assertTrue(
            viewCode.contains("paint.measureText(text)"),
            "关态 metricOf 既有测量语句必须原样保留（关态逐字节等价）",
        )
        assertTrue(
            viewCode.contains("canvas.drawText("),
            "关态 drawCmdText 既有绘制语句必须原样保留",
        )
        assertTrue(
            !viewCode.contains("BundledFontFamilies.fallbacksFor("),
            "CanvasConsoleView 全文件不许手拼回落链",
        )
    }
}
