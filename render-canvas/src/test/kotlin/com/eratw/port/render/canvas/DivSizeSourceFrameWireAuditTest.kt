package com.eratw.port.render.canvas

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * MODERN-R12-WIRE 的源码形状闸：[CanvasConsoleView.divSizeSource] 必须是**双口转发的真对象**
 * （destSize + frameAvailable 都现读转发 [CanvasConsoleView] 挂着的 frameSource），
 * 不是只转发 destSize 的单口 SAM。
 *
 * ## 为什么是源码形状闸
 *
 * `CanvasConsoleView` 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric），实例化不了；
 * 而「divSizeSource 覆写了 frameAvailable」「frameAvailable 未挂档答 true」都是**代码形状**
 * 问题。口径照同目录既有 `DivImageAltFallbackAuditTest`（剥注释后按声明体断言）。
 *
 * 钉住的原版语义（`ConsoleDivPart.cs:198-201` div 子行与正文同一条缺帧量宽路）：
 * div 子行 `<img>` 尺寸命中而帧拿不到 ⇒ 展平落 AltText 文本尺（判据唯一名下 = core
 * `CellWidth.imgWidthPxOrNull`）。SAM 形态（frameAvailable 吃接口缺省 true）= 未接线旧读数，
 * 子行展平判不了缺帧面 —— 反向证明 C（契约 §4）：回退 SAM 本闸红。
 */
class DivSizeSourceFrameWireAuditTest {

    private val code: String by lazy {
        val candidates = listOf(
            Path.of("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt"),
            Path.of("../render-canvas/src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt"),
        )
        val path = candidates.firstOrNull { Files.isRegularFile(it) }
            ?: error("找不到 CanvasConsoleView.kt（试了 ${candidates.joinToString { it.toString() }}）")
        stripComments(path.readText())
    }

    /** divSizeSource 属性声明体（从声明行到初始化表达式大括号配平）。 */
    private val divSizeSourceBody: String by lazy {
        val marker = "private val divSizeSource: HtmlRunFlatten.ImageSizeSource ="
        val start = code.indexOf(marker)
        assertTrue(start >= 0, "代码里找不到 $marker")
        val objKw = code.indexOf("object", start)
        assertTrue(objKw >= 0, "divSizeSource 必须是匿名对象（双口转发），找到的是别的形态")
        val open = code.indexOf('{', objKw)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return@lazy code.substring(start, i + 1)
                }
            }
            i++
        }
        error("divSizeSource 声明体没有闭合")
    }

    @Test
    fun `divSizeSource 是匿名对象双口转发 不是单口 SAM`() {
        assertTrue(
            divSizeSourceBody.contains("= object : HtmlRunFlatten.ImageSizeSource"),
            "divSizeSource 必须用 object 表达式（单口 SAM 判不了缺帧面）",
        )
        assertTrue(
            !divSizeSourceBody.contains("= HtmlRunFlatten.ImageSizeSource {"),
            "SAM 构造形态（= HtmlRunFlatten.ImageSizeSource 加单表达式体）必须退场",
        )
    }

    @Test
    fun `frameAvailable 口现读转发 frameSource 且未挂档答 true`() {
        assertTrue(
            divSizeSourceBody.contains(
                "(frameSource as? HtmlRunFlatten.ImageSizeSource)?.frameAvailable(resource) ?: true",
            ),
            "必须覆写 frameAvailable 并现读转发 frameSource（缺帧面与正文与 core 折行同尺）",
        )
        assertTrue(
            divSizeSourceBody.contains(
                "(frameSource as? HtmlRunFlatten.ImageSizeSource)?.destSize(resource)",
            ),
            "destSize 口必须保留原转发（缺 width 的纵横比推宽支不回退）",
        )
        assertTrue(
            divSizeSourceBody.contains("override fun frameAvailable"),
            "覆写必须是 override（漏了 override 就接不上接口口，frameAvailable 恒吃缺省 true）",
        )
    }

    @Test
    fun `frameAvailable 转发挂到展平器`() {
        // divFlattener 构造必须仍吃 divSizeSource（换掉注入点 = 闸落在没人消费的口上）。
        assertTrue(
            code.contains("HtmlRunFlatten(metrics, sizeSource = divSizeSource"),
            "divFlattener 必须吃 divSizeSource（缺帧判定才进得了子行展平）",
        )
    }

    /** 剥掉行注释与块注释（与 DivImageAltFallbackAuditTest 同一把尺）。 */
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
}
