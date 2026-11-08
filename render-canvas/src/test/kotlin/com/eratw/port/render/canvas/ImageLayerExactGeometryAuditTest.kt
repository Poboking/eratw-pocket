package com.eratw.port.render.canvas

import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.ImageLayerGeometry
import com.eratw.port.render.view.ScreenRows
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `SETIMAGELAYERL` 图层改用**精确式**的接线闸（IMG-A4 I4）。
 *
 * ## 为什么一半是源码面
 *
 * [CanvasConsoleView] 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric），
 * `drawImageLayers` 实例化不了。口径同 [CanvasTapOutcomeAuditTest]：源码面只钉「几何从哪来」，
 * 数值对不对由 `:render` 的 `ImageLayerGeometryTest` 与下面的视口读数用例钉。
 *
 * 源码面钉四件事：
 * 1. 几何走 [ImageLayerGeometry.layerBoxPxExact]（不再是 `layerBoxPx` 的 tops 近似）；
 * 2. `linesBelowAnchor` 的 `num2` 取自**视口最后可见行**（`Viewport.bottomRingIndex`，
 *    `EmueraConsole.cs:1786-1795`），不是 `layout.tops.size - 1` 那种「保留列表末行」硬凑；
 * 3. 求差结果原样喂给精确式（恒置 0 或换回顶层近似都会红）；
 * 4. KDoc 不再自称近似（同一件事的散文面不许与代码分叉）。
 *
 * ## 为什么还要一条视口读数用例
 *
 * 「最后可见行」与 `RowLayout.Plan.tops` 的坐标系是同一把尺（保留列表行下标）这件事，
 * 只有拿真的 [ConsoleScreen] 走一遍「提交长历史 + 上翻」才判得出 —— 尤其要钉住
 * **滚动后** `visibleRows()` 的末行仍就是 `bottomRingIndex()` 那一行（不是列表末行恰好等于它）。
 *
 * 真机视觉（立绘行改高、滚动中）无设备 ⇒ TODO(verify)，本夹具不冒充。
 */
class ImageLayerExactGeometryAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与 CanvasTapOutcomeAuditTest 同一把尺）。 */
    private fun bodyOf(signature: String): String {
        fun strip(body: String): String = body.lineSequence().joinToString("\n") { line ->
            val c = line.indexOf("//")
            if (c >= 0) line.substring(0, c) else line
        }
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
                    if (depth == 0) return strip(src.substring(open + 1, i))
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }

    /** `drawImageLayers` 的 KDoc 正文（签名前最近一个块注释）。 */
    private fun kdocOf(signature: String): String {
        val start = src.indexOf(signature)
        assertTrue(start >= 0, "源码里找不到 $signature")
        val open = src.lastIndexOf("/**", start)
        assertTrue(open >= 0, "$signature 前没有 KDoc")
        val close = src.indexOf("*/", open)
        assertTrue(close in open until start, "$signature 的 KDoc 没闭合")
        return src.substring(open, close)
    }

    private val layersBody: String by lazy { bodyOf("fun drawImageLayers(") }

    /** 几何走精确式：`layerBoxPxExact` 是唯一落位调用，旧 tops 近似调用点已移除。 */
    @Test
    fun `立绘图层走层框精确式且旧近似调用点已移除`() {
        assertTrue(
            layersBody.contains("ImageLayerGeometry.layerBoxPxExact("),
            "drawImageLayers 必须调 layerBoxPxExact（精确式：y 不含锚行行顶）",
        )
        assertFalse(
            layersBody.contains("ImageLayerGeometry.layerBoxPx("),
            "旧 layerBoxPx（tops 近似）不得再被调用；函数本体保留给既有单测，但生产路径只剩精确式",
        )
        assertFalse(
            src.contains("ImageLayerGeometry.layerBoxPx("),
            "整个 CanvasConsoleView 都不该再调 layerBoxPx（旧调用点应清零）",
        )
        assertFalse(
            layersBody.contains("rowTopPx ="),
            "精确式不吃行顶：再传 rowTopPx = layout.tops[...] 就是把近似面接了回来",
        )
    }

    /**
     * `num2` 取自视口最后可见行（`Viewport.bottomRingIndex`），锚行抬回保留区坐标后求差；
     * 不许用 `tops.size - 1 - row` 这种「保留列表末行」硬凑。
     */
    @Test
    fun `行距取自视口最后可见行而不是保留列表末行`() {
        assertTrue(
            layersBody.contains("console.viewport.bottomRingIndex()"),
            "最后可见行必须读 Viewport.bottomRingIndex（原版 num2 = ScrollBar.Value - 1 夹 Count - 1）",
        )
        assertTrue(
            layersBody.contains("console.visibleRowBase"),
            "锚行要先换算到保留区行下标（visibleRowBase + 绘制行号），否则两个坐标系不能相减",
        )
        assertFalse(
            layersBody.contains("tops.size - 1") || layersBody.contains("tops.size-1"),
            "linesBelowAnchor 不许用 layout.tops.size - 1 - row 硬凑（那是保留列表末行，不是 num2）",
        )
        assertTrue(
            Regex(
                """linesBelowAnchor\s*=\s*ImageLayerGeometry\.linesBelowAnchor\(""" +
                    """\s*lastVisibleRing\s*,\s*anchorRing\s*,?\s*\)""",
            ).containsMatchIn(layersBody),
            "求差结果必须原样喂给精确式（恒置 0 / 换成别的行号都会红）",
        )
    }

    /** 调用点把 console 交给绘制方法（视口读口就在它身上）。 */
    @Test
    fun `绘制调用点把 ConsoleScreen 交给立绘图层方法`() {
        assertTrue(
            src.contains("drawImageLayers(target, contentLeft, layout, console)"),
            "drawScreen 的调用点必须传 console，否则拿不到视口最后可见行",
        )
    }

    /** KDoc 不再自称近似：散文面与代码同一结论（旧口径「tops 充当 GetLinePointY」已翻案）。 */
    @Test
    fun `立绘图层 KDoc 不再自称近似`() {
        val kdoc = kdocOf("fun drawImageLayers(")
        assertFalse(kdoc.contains("充当"), "KDoc 不得再说「用行顶表充当 GetLinePointY」")
        assertFalse(kdoc.contains("近似"), "KDoc 不得自称近似（精确式已接上生产调用点）")
        assertTrue(
            kdoc.contains("layerBoxPxExact") || kdoc.contains("精确式"),
            "KDoc 要写明走的是精确式",
        )
        assertTrue(
            kdoc.contains("bottomRingIndex"),
            "KDoc 要写明 num2 的读口（Viewport.bottomRingIndex）",
        )
    }

    // ------------------------------------------------------------------
    // 视口读数：最后可见行与 RowLayout.tops 同一坐标系（滚动前后）
    // ------------------------------------------------------------------

    private fun screenWith(lines: Int, heightLines: Int = 6): ConsoleScreen {
        val screen = ConsoleScreen(clientWidth = 20, maxLog = 500, heightLines = heightLines)
        screen.submit((0 until lines).map { ScreenRows.textLine("L$it") })
        return screen
    }

    /**
     * 贴底：绘制窗口末行 = `num2`，且 `num2 - (visibleRowBase + row)` 与
     * `list.rowCount - 1 - row` 读数相同（两条口径在「列表 = 窗口的一段后缀」时恒等）。
     */
    @Test
    fun `贴底时最后可见行就是绘制窗口末行`() {
        val screen = screenWith(lines = 40)
        assertTrue(screen.viewport.atBottom, "提交后应贴底")
        assertEquals(
            screen.viewport.bottomRingIndex(),
            screen.visibleRowBase + screen.visibleRows().size - 1,
            "visibleRows() 的末行必须就是 num2（bottomRingIndex）",
        )
        assertEquals(
            screen.visibleRows().size,
            screen.list.rowCount,
            "今天绘制列表行数 = 绘制窗口行数（[rowLayout] 的既有前提）",
        )
        for (row in 0 until screen.list.rowCount) {
            assertEquals(
                screen.list.rowCount - 1 - row,
                ImageLayerGeometry.linesBelowAnchor(
                    screen.viewport.bottomRingIndex(),
                    screen.visibleRowBase + row,
                ),
                "row=$row：视口求差与「列表末行 - row」必须同值",
            )
        }
    }

    /**
     * 上翻 9 行（看历史）：窗口整体前移，但两条口径仍逐行同值，且末行仍是 `num2`
     * —— 这就是「滚动时行距跟着视口走」的证据（`followScroll` 的代数面）。
     */
    @Test
    fun `上翻历史后最后可见行仍与绘制窗口同坐标系`() {
        val screen = screenWith(lines = 40)
        val before = screen.viewport.bottomRingIndex()
        val topBefore = screen.visibleRowBase
        screen.viewport.scrollUp(9)
        screen.refresh()
        assertFalse(screen.viewport.atBottom, "上翻后不该还贴底")
        val after = screen.viewport.bottomRingIndex()
        assertEquals(before - 9, after, "num2 随滚动整体上移 9 行（ScrollBar.Value -= 9）")
        assertEquals(
            after,
            screen.visibleRowBase + screen.visibleRows().size - 1,
            "滚动后 visibleRows() 的末行仍是 num2",
        )
        for (row in 0 until screen.list.rowCount) {
            assertEquals(
                screen.list.rowCount - 1 - row,
                ImageLayerGeometry.linesBelowAnchor(after, screen.visibleRowBase + row),
                "滚动后 row=$row 的两条口径仍同值",
            )
        }
        // followScroll 的代数面：num2 上翻 9 ⇒ 同一保留区锚行到 num2 的行距**减** 9
        // （`ScrollBar.Value` 每减 1，`num2 - lineNo` 减 1）⇒ 精确式里 y 少 9 × LineHeight，
        // 图层随内容一起往上走。锚行取「滚动后仍在窗口内」的那一行：上翻 9 行 > 窗口 6 行，
        // 前后两窗无交集，拿上翻前可见的行当锚会落到 num2 之下（走下面那条夹取）。
        val anchorRing = after - 2
        assertEquals(
            ImageLayerGeometry.linesBelowAnchor(before, anchorRing) - 9,
            ImageLayerGeometry.linesBelowAnchor(after, anchorRing),
            "同一锚行在上翻 9 行后行距 -9（num2 减 9、lineNo 不变）",
        )
        // 锚行落到 num2 之下（上翻前靠底那一行已滚出视口下方）⇒ 夹到 0，不出现负行距。
        // 生产不可达：`drawImageLayers` 只画 `row < layout.tops.size` 的层，锚行恒 ≤ num2；
        // 原版此时 `GetLinePointY` 给出大于底行行顶的 y，图层整块落在可视区下方被裁掉。
        assertEquals(
            0,
            ImageLayerGeometry.linesBelowAnchor(after, topBefore + 3),
            "滚到 num2 之下的锚行夹到 0（负行距不许出现）",
        )
    }

    /**
     * 内容不足一屏：窗口顶夹到 0，`visibleRows()` 只有实际内容那几行，
     * 末行仍是 `num2`（`bottomRingIndex` = 行数 - 1）。
     */
    @Test
    fun `内容不足一屏时末行仍然对齐 num2`() {
        val screen = screenWith(lines = 4)
        assertEquals(4, screen.visibleRows().size, "4 行内容 ⇒ 4 行窗口（顶行夹 0）")
        assertEquals(3, screen.viewport.bottomRingIndex())
        assertEquals(
            3,
            screen.visibleRowBase + screen.visibleRows().size - 1,
            "短内容下 visibleRows() 的末行同样就是 num2",
        )
        assertEquals(
            0,
            ImageLayerGeometry.linesBelowAnchor(
                screen.viewport.bottomRingIndex(),
                screen.visibleRowBase + 3,
            ),
            "锚在末行：行距 0",
        )
    }
}
