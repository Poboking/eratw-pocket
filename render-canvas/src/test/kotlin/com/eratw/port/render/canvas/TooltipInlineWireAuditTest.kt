package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 长按提示纵判**落在渲染层**的源码面回归闸（素质 / 种族提示丢失的 0.2.44 回归）。
 *
 * ## 为什么是源码面
 *
 * `CanvasConsoleView` 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric，
 * `render-canvas/build.gradle.kts` 只引 `kotlin("test")` + `junit-jupiter`），实例化不了。
 * 同 [CanvasTapOutcomeAuditTest] / [CanvasMemoryReleaseAuditTest] 的口径：本夹具不冒充真机
 * 读数，只钉一件可判的事 —— 壳体**不许**再用「该点有没有**可点**目标」当提示的纵门。
 *
 * ## 判据出处
 *
 * 原版 ToolTip 读光标候选串的 `Title`（`EmueraConsole.cs:1891-1895`），而光标候选在节点纵判
 * 通过后**非按钮也记**（`:2368` 的 `Top/Bottom` 判 + `:2373-2379` 的
 * `if (!flag) consoleButtonString2 = consoleButtonString3;`）⇒ 提示的纵门只跟**节点**走，
 * 与「节点可不可点」无关。素质屏是 `<nonbutton title='..'>` 纯文本行，行内没有任何可点
 * 目标，拿可点性当门就整行判空 —— 可点按钮的提示照旧正常，玩家感知即「只有素质看不了」。
 *
 * 正确接线：`tooltipAt` 调 `ConsoleScreen.tooltipInline`（渲染层的节点盒判据），
 * 且要按 `CellHit` 的**同一份行几何**（行内像素 + 行高 / 行高约束 / 纯图行）传进去，
 * 否则压缩行上的图元提示盒会与 `hitInline` 分叉。
 *
 * 真机读数仍是 TODO(verify)：本机 `adb devices` 为空。
 */
class TooltipInlineWireAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与 [CanvasTapOutcomeAuditTest] 同一把尺）。 */
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

    private val tooltipBody: String by lazy { bodyOf("fun tooltipAt(") }

    /** 提示的纵判必须走渲染层单一入口 `ConsoleScreen.tooltipInline`。 */
    @Test
    fun `长按提示改走渲染层的 tooltipInline`() {
        assertTrue(
            tooltipBody.contains("console.tooltipInline("),
            "tooltipAt 必须调 ConsoleScreen.tooltipInline：纵判归渲染层（EmueraConsole.cs:2368 按节点自己的 Top/Bottom）",
        )
    }

    /**
     * 壳体不许再自己造「可点性」纵门：`inlineHitOrNull` 只认识可点目标，用在提示路径上
     * 会把 `<nonbutton title='..'>` 整行判空（这正是 0.2.44 素质提示丢失的判据）。
     */
    @Test
    fun `长按提示不再拿可点性当纵门`() {
        assertFalse(
            tooltipBody.contains("inlineHitOrNull"),
            "tooltipAt 里不许出现 inlineHitOrNull：它是点击/高亮面的判据，只认识可点目标，不能当提示纵门",
        )
        assertFalse(
            tooltipBody.contains("hit.gated &&"),
            "不许保留「gated 且没有可点目标就 return null」这条复合判据（0.2.44 回归原形）",
        )
    }

    /** 行几何必须与 `CellHit` 同源传下去（像素 + 行高 / 执行行高 / 纯图行）。 */
    @Test
    fun `行几何按 CellHit 同一份传入`() {
        listOf("hit.yInRowPx", "hit.rowHeightPx", "pureArtRow = hit.pureArtRow").forEach { arg ->
            assertTrue(tooltipBody.contains(arg), "tooltipInline 的入参缺 $arg：压缩行上提示盒会与点击判据分叉")
        }
        assertTrue(
            tooltipBody.contains("lineHeightPx = lineHeightPx"),
            "执行行高必须一起给：GraphicGeometry.nodeRowScale 的 k 只靠 rowHeightPx + lineHeightPx 才能算",
        )
    }
}
