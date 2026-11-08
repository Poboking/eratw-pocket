package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `CanvasConsoleView.windowWidthScriptPx()` 读数语义的**源码面钉子**（与 [CanvasLiveReflowTest]
 * 同族：本模块纯 JVM 无 Robolectric，`View` 实例化不了，代码形状骗不了 grep）。
 *
 * ## 钉的是哪一次语义翻转（0.2.43 同源收口）
 *
 * 旧实现「未接线（`sourceWindowWidthScriptPx` ≤ 0）⇒ 原样返回 0」：回填层把 0 当
 * 「未接线」跳过 ⇒ 旧盘 `WINDOW WIDTH` 读数（裸缺省 760）压住真机满宽版面 —— core 的
 * `WindowX` 停在 760 ⇒ `drawableWidth` ≈ 42 格而屏上 139 列，`MAXWIDTH()`（= 折行宽上的
 * `HTML_STRINGLEN(DRAWLINESTR)`）掉进除零区（语料 `DEBUG.ERB` `CFLAG_DEBUG` 行582
 * 「除数为0的除法运算」），HTML_PRINT 折行尺与屏上分叉成两把尺。
 *
 * 新实现统一为「**实际版面宽** ÷ 「真机字号 / 脚本字号」」：无论配置宽是否接线，
 * core 的 `WindowX` 都以屏上实际版面为准（原版 `MainWindow.cs:726` 的回填语义）。
 * 未布局时才回落 `sourceWindowWidthScriptPx`（未接线 = 0，回填层对 ≤ 0 不写）。
 */
class CanvasWindowSeedReadTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个声明的函数体（按大括号配平，先剥行注释；与既有 AuditTest 同一把尺）。 */
    private fun bodyOf(signature: String): String {
        val start = src.indexOf(signature)
        assertTrue(start >= 0, "源码里找不到 $signature")
        val open = src.indexOf('{', start)
        assertTrue(open >= 0, "$signature 后没有函数体")
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return stripComments(src.substring(open + 1, i))
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    private fun stripComments(body: String): String =
        body.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }

    @Test
    fun `读数来自实际版面宽而不是配置请求值`() {
        val body = bodyOf("fun windowWidthScriptPx()")
        assertTrue(
            body.contains("resolvedLayoutWidthPx"),
            "必须读 resolveFor 解算出的实际版面宽（resolvedLayoutWidthPx）——" +
                "core 的 WindowX 以屏上实际版面为准（MainWindow.cs:726 回填语义）",
        )
        assertTrue(
            body.contains("layoutWidth.toFloat() / ratio"),
            "逆折算必须用同一把尺：实际版面宽 ÷ （真机字号 / 脚本字号），与 CellMetrics.scriptPixels 同式",
        )
    }

    @Test
    fun `未接线不再原样返回零`() {
        val body = bodyOf("fun windowWidthScriptPx()")
        assertFalse(
            body.contains("return requested"),
            "「未接线原样返回 0」会让回填层跳过写入，旧盘 WINDOW WIDTH 读数（裸缺省 760）" +
                "压住真机满宽版面 —— MAXWIDTH() 掉进除零区（CFLAG_DEBUG 行582）",
        )
    }

    @Test
    fun `未布局时回落配置值而不拿零冒充版面宽`() {
        val body = bodyOf("fun windowWidthScriptPx()")
        assertTrue(
            body.contains("return sourceWindowWidthScriptPx"),
            "未布局（版面宽还没解算）⇒ 回落 sourceWindowWidthScriptPx（未接线 = 0，" +
                "回填层对 ≤ 0 一律不写）：不拿 0 冒充版面宽",
        )
    }
}
