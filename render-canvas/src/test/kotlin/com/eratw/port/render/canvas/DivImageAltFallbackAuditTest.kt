package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MODERN-R9-B 的源码审计钉：div 子行 `<img>` 缺帧必须走**正文同一份** AltText 回落，
 * 且落点是裸 `PointX` / 裸 `pointY`。
 *
 * ## 为什么是源码形状闸
 *
 * `CanvasConsoleView` 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric），实例化不了；
 * 而「三支缺帧出口都回落」「回落复用正文单源」「不加 ShapePositionShift」都是**代码形状**
 * 问题，不是某个纯函数能自证的。口径照同目录既有 [CanvasContentInsetSingleSourceTest]
 * （剥注释后按函数体断言）。
 *
 * 断言的原版语义（`reference/emuera-cs/`）：
 * - `ConsoleImagePart.cs:168` 守卫 `aSprite != null && aSprite.IsCreated` **不成立** ⇒
 *   `:177`（GRAPHICS）/ `:181`（文本模式）`graph.DrawString(AltText, Config.DefaultFont,
 *   ForeColor, new Point(PointX, pointY))`；
 * - 落点三条：**裸 `PointX`**（不加 `Config.DrawingParam_ShapePositionShift`，对比命中支
 *   `:171` 明确加了 shift）、**裸 `pointY`**（不加 `ypos` / `destRect.Y`）、色 `Config.ForeColor`；
 * - div 子行同一条绘制路径（`ConsoleDivPart.cs:198-201`），内容盒裁剪 `:183,196`。
 */
class DivImageAltFallbackAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 剥掉 `//` 行注释与块注释，**保留换行**（与 [CanvasContentInsetSingleSourceTest] 同一把尺）。 */
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

    private val code: String by lazy { stripComments(src) }

    /** 取某个 `private fun xxx(` 的函数体（按大括号配平；与既有 AuditTest 同一把尺）。 */
    private fun bodyOf(signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(start >= 0, "代码里找不到 $signature")
        val open = code.indexOf('{', start)
        assertTrue(open >= 0, "$signature 后没有函数体")
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private val divImage: String by lazy { bodyOf("private fun drawDivImage(") }
    private val divFallback: String by lazy { bodyOf("private fun drawDivImageAltText(") }
    private val bodyFallback: String by lazy { bodyOf("private fun drawImageAltText(") }

    @Test
    fun `drawDivImage 的每一条缺帧出口都回落 AltText`() {
        // 五支出口逐个钉死（原为 `>= 3`：单支退回裸 return 咬不住 —— 2026-11-09 组长反向证明
        // 实测）：① frameSource == null ② 查不到帧 ③ 帧句柄不是 Bitmap ④ inlineSize 非正
        // ⑤ sourceRectClipped 返回 null。新增出口须同步改这个计数（同文案表计数闸的纪律）。
        assertEquals(
            5,
            occurrences(divImage, "drawDivImageAltText("),
            "drawDivImage 的五支缺帧出口都要调 drawDivImageAltText（168,175-182）",
        )
        assertFalse(
            divImage.contains("?: return"),
            "缺帧不许再裸 return（旧实现 frameSource ?: return / frame ?: return 即本缺口）",
        )
    }

    @Test
    fun `div 回落复用正文 drawImageAltText 单源`() {
        assertTrue(
            divFallback.contains("drawImageAltText(canvas, image, originLeft - shapeShiftPx, rowTop, cmd)"),
            "div 回落必须走正文那份 drawImageAltText（不许造第二套画法）",
        )
        assertEquals(
            1,
            occurrences(divFallback, "drawImageAltText("),
            "回落函数只允许一处转发",
        )
    }

    @Test
    fun `div 回落用裸 PointX 不加 ShapePositionShift`() {
        // 绘制链的 originLeft 已含 ShapePositionShift（ConsoleImagePart.cs:171），回落那句是裸
        // PointX（:177,181）⇒ 必须显式减回去。误写成 originLeft 直接透传 = B 反向证明要红的那条。
        assertTrue(
            divFallback.contains("originLeft - shapeShiftPx"),
            "回落横向落点 = 裸 PointX（先减掉 shapeShiftPx）",
        )
        assertFalse(
            divFallback.contains("originLeft + ") || divFallback.contains("originLeft - 0"),
            "回落不许再叠加任何横向位移",
        )
    }

    @Test
    fun `div 回落用裸 pointY 不加 ypos`() {
        // 原版 :177 的 new Point(PointX, pointY)：pointY 是行顶，destRect.Y（= ypos）不参与。
        assertFalse(
            divFallback.contains("yOffsetPx"),
            "回落纵向落点不许沾 image.yOffsetPx（ypos 只属于命中支的 top）",
        )
        assertTrue(
            divFallback.contains("rowTop"),
            "回落纵向落点 = 子行行顶 rowTop",
        )
    }

    @Test
    fun `正文回落仍是 altText 单源与 ForeColor 口径`() {
        assertTrue(
            bodyFallback.contains("image.altText ?: return"),
            "altText 单源取自 InlineGraphic.Image.altText；null = 生产侧没给 ⇒ 静默",
        )
        assertTrue(
            bodyFallback.contains("opaque(cmd.fore)"),
            "色 = Config.ForeColor（不是焦点/按下色）",
        )
    }

    @Test
    fun `正文 drawImage 的缺帧支仍是同一份回落`() {
        // S4 连带：emitImage 开始填 altText 之后，正文缺帧回落从「死字段」变成生效 ——
        // 这条语义必须继续走同一个 drawImageAltText（单源），div 侧只是复用。
        val bodyImage = bodyOf("private fun drawImage(")
        assertTrue(
            bodyImage.contains("drawImageAltText(canvas, image, originLeft - shapeShiftPx, rowTop, cmd)"),
            "正文缺帧支 = 裸 PointX（先减 shapeShiftPx）+ 裸 pointY，交给 drawImageAltText",
        )
    }

    @Test
    fun `旧注释不再声称 div 子行不画 altText 回落`() {
        // 本路就是改掉那个登记偏差；留下旧说法会让下一个人以为仍是缺口。
        assertFalse(
            src.contains("div 子行不画 altText 回落"),
            "KDoc 里的旧缺口说明必须随实现一起改掉",
        )
    }
}
