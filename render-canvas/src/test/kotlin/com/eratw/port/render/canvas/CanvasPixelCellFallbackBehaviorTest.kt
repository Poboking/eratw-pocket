package com.eratw.port.render.canvas

import com.eratw.port.core.host.DisplayDivBlock
import com.eratw.port.core.host.DisplayDivChildLine
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.TextRun
import com.eratw.port.render.adapter.DivOverlayFrame
import com.eratw.port.render.adapter.DivOverlayPlanner
import com.eratw.port.render.adapter.HtmlRunFlatten
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.RowLayout
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 像素 -> 格兜底链的**行为**闸（CANVASTAP1；判词 7「优先更好更优雅的一方」的余波）。
 *
 * ## 为什么需要这一份
 *
 * [CanvasConsoleView.tap] 的兜底支此前只被 [CanvasTapOutcomeAuditTest] 用**源码文本**看着：
 * 比的是 indexOf 顺序与 substringBefore 区间里的字样。把
 * `cellHitAt(console, x, y) ?: return null` 改成「算不出来也当一次空命中」，或把 gated 支的
 * `clickInline` 与 `click` 反接，文本闸一个字都不会红 —— 判词 7 点的正是这种面。
 *
 * 换算体搬到 [PixelTapMetrics] 的扩展函数（[tapFallback] / [cellHitAt] / [pointerCellRow]，
 * 体逐字节搬自原方法）之后，本夹具把它拉进 JVM 直测：接收者是假度量源（带 rowLayout 计数口），
 * 屏是真正的 [ConsoleScreen]，于是 div 键表、格表、行内纵门、锚行回退四件事全是真实读数。
 * 可驱动性口径同 [CanvasLiveReflowTest] 与文件末尾 [LiveReflowPlan] 的先例。
 *
 * ## 钉住的判据
 *
 * 1. 早闸先于行表换算：div 命中路径 `rowLayout` 次数 == 0；走兜底 == 1（既不许提前算，也不许重复算）。
 * 2. 键全负：div 键表只有负 depth 时，块外的像素必须让回格层（不跟原版 1.824
 *    `EmueraConsole.cs:2350` 的 flag3 / 蛇版 4.981 `:2612` 的 zeroTested「整趟跳过正文行扫描」bug；
 *    与 render 侧 `ButtonLayerDivHitTest.键全负的一帧里正文行按钮仍可点` 同一条定案，这里补的是兜底链那半段）。
 * 3. 混合键：正键与负键同帧、且都没把该像素判成命中时，取格层的值 —— 不得因为表里有条键就伪造命中。
 * 4. 兜底失败：`cellHitAt` 给不出格（早闸外 / 行表落不到行 / 列超窗）或该格没有可点件，
 *    返回值必须是 null（断言 null，不是断言「没崩」）。
 * 5. gated 走 `clickInline`、锚行回退走 `click`：直接命中叠行内纵门（原版 `EmueraConsole.cs:2368`
 *    的 num4 < Top || num4 > Bottom —— 图上方的行内空白不该触发）；跨行立绘的 tap 点本来就不在锚行带里，
 *    再叠一次纵门会把立绘点击判死，那一路必须留整行口径。
 *
 * 真机手势面（onTouchEvent -> tap）仍是 TODO(verify)：本机 adb devices 为空，本模块无 Robolectric。
 */
class CanvasPixelCellFallbackBehaviorTest {

    private val metrics = CellMetrics(FONT_PX, FONT_PX)
    private val planner = DivOverlayPlanner(metrics, 0, HtmlRunFlatten(metrics))
    private val lineHeightPx = metrics.lineHeight.toFloat()
    private val halfCellPx = metrics.cellPixelWidth

    // ---- 夹具 ----

    /**
     * 假度量源：十个成员按生产 CanvasConsoleView 的口径给值，`rowLayout` 装一只计数器。
     * 行表给等分行带（tops[i] = i * 行高），于是「点击点落在第几行」完全由夹具说了算。
     *
     * **两把行表尺的重合条件**（2026-11-26 首跑在此判红，别把 BLANK_LINES 改回 12）：本行表是
     * 顶对齐，而屏侧 ButtonLayer.rowTargetAt（render/src/main/kotlin/com/eratw/port/render/button/
     * ButtonLayer.kt:962-972）逆算是**底对齐** = canvasHeightPx - (rowCount - row) * lineHeight，
     * rowCount = ConsoleScreen.kt:406 的 visibleRows().size。只有 rowCount * 行高 == canvasHeightPx
     * （窗口被填满）时两者恒等；行数少于窗口行数的夹具里，探针点会落在正文行之上，
     * hitAt 的「正文行先赢」闸（ButtonLayer.kt:836）喂不到真行带，正 depth 块会假赢。
     */
    private class Stub(
        private val cellPx: Float,
        private val linePx: Float,
    ) : PixelTapMetrics {

        /** 本次点击里行表被换算的次数（判据 1 的读数口）。 */
        var rowLayoutCalls = 0

        val plan: RowLayout.Plan = RowLayout.Plan(
            tops = FloatArray(WINDOW_ROWS) { it * linePx },
            heights = FloatArray(WINDOW_ROWS) { linePx },
            artOnly = BooleanArray(WINDOW_ROWS) { false },
        )

        override fun contentLeftPx(): Float = 0f
        override val paddingTop: Int = 0
        override val halfCellPx: Float get() = cellPx
        override fun windowColumns(): Int = WINDOW_COLUMNS
        override val fontPixelSize: Float get() = linePx
        override val lineHeightPx: Float get() = linePx

        override fun rowLayout(console: ConsoleScreen, cmds: List<DrawCmd>): RowLayout.Plan {
            rowLayoutCalls += 1
            return plan
        }

        // 与生产逐字同式（CanvasConsoleView.kt:1270）：夹具不自己写死那一排。
        override fun extraRowsAbove(console: ConsoleScreen): Int =
            (console.hitRowBase - console.visibleRowBase).coerceIn(0, ConsoleScreen.EXTRA_ABOVE_ROWS)

        override fun clientX(viewX: Float): Int = floor(viewX).toInt()
        override fun clientY(viewY: Float): Int = floor(viewY).toInt()
    }

    private fun screenWith(lines: List<RenderLine>): ConsoleScreen =
        ConsoleScreen(clientWidth = WINDOW_COLUMNS, maxLog = MAX_LOG, heightLines = WINDOW_ROWS)
            .apply { submit(lines) }

    private fun blankLines(): MutableList<RenderLine> =
        (0 until BLANK_LINES).map { line(text("行" + it)) }.toMutableList()

    private fun line(vararg runs: TextRun) =
        RenderLine(runs = runs.toList(), isLogicalLine = true, isLineEnd = true)

    private fun text(value: String) = TextRun(value, FORE, null, 0, FONT)

    private fun button(value: String, id: Long) =
        TextRun(value, FORE, null, 0, FONT, kind = RunKind.BUTTON, buttonValue = id)

    /** 源按钮里的图段：buttonId 必须有效，否则 ButtonLayer.attach 的双闸（:363-373）不入表。 */
    private fun art(label: String, id: Long, heightPx: Int): TextRun = TextRun(
        text = label,
        fore = FORE,
        back = null,
        styleMask = 0,
        fontName = FONT,
        kind = RunKind.IMAGE,
        buttonValue = id,
        buttonId = ART_BUTTON_ID,
        graphic = InlineGraphic.Image(
            resource = "art.png",
            widthPx = (ART_CELLS * halfCellPx).toInt(),
            heightPx = heightPx,
            yOffsetPx = 0,
            widthCells = ART_CELLS,
        ),
    )

    private fun divBlock(label: String, value: Int, depth: Int, atX: Int = 0, atY: Int = 0) = DisplayDivBlock(
        altHeadTag = "<div>",
        anchorCol = 0,
        anchorPointXPx = 0,
        xOffsetPx = atX,
        divXOffsetPx = 0,
        pointYPx = atY,
        yOffsetPx = 0,
        widthPx = BLOCK_W,
        heightPx = BLOCK_H,
        subDivisionWidthPx = BLOCK_W,
        depth = depth,
        backgroundColor = null,
        margin = emptyList(),
        padding = emptyList(),
        border = emptyList(),
        radius = emptyList(),
        borderColors = emptyList(),
        isRelative = true,
        isEscaped = true,
        children = listOf(DisplayDivChildLine("<button value='" + value + "'>" + label + "</button>")),
    )

    private fun attachDiv(screen: ConsoleScreen, anchorTopPx: Int, vararg blocks: DisplayDivBlock) {
        val plan = planner.plan(
            blocks = blocks.toList(),
            anchorTopPx = anchorTopPx,
            anchorPointXPx = 0,
            canvasHeightPx = WINDOW_ROWS * metrics.lineHeight,
            visibleTopLine = 0,
            visibleBottomLine = VISIBLE_BOTTOM_LINE,
            anchorLineNo = 0,
        )
        screen.updateDivOverlay(
            DivOverlayFrame(plan.behindText, plan.inFrontOfText),
            metrics,
            WINDOW_ROWS * metrics.lineHeight,
        )
    }

    /** 某值所在的**绘制口径行** = 命中行 + extraRowsAbove（与 hitRowForCell 互逆，同一把尺）。 */
    private fun drawRowOfValue(screen: ConsoleScreen, stub: Stub, value: Any?): Int {
        val hit = screen.hitRows().indexOfFirst { row -> row.runs.any { it.buttonValue == value } }
        assertTrue(hit >= 0, "命中层里没有值等于 " + value + " 的按钮：夹具装配失败")
        return hit + stub.extraRowsAbove(screen)
    }

    /** 第 row 行带中点 x 第 col 格中点 —— 与 cellHitAt 的换算同式。 */
    private fun probe(stub: Stub, drawRow: Int, col: Int): Pair<Float, Float> =
        (col + 0.5f) * stub.halfCellPx to drawRow * stub.lineHeightPx + stub.lineHeightPx / 2f

    // ---- ① 早闸先于行表换算 ----

    @Test
    fun `div命中时早闸先于行表换算`() {
        val screen = screenWith(blankLines())
        val stub = Stub(halfCellPx, lineHeightPx)
        attachDiv(screen, anchorTopPx = 2 * metrics.lineHeight, divBlock("POP", 9, depth = -1))
        val hit = screen.divTargets().first { it.value == 9L }
        val px = hit.xPx + hit.widthPx / 2f
        val py = hit.yPx + hit.heightPx / 2f
        assertEquals("9", stub.tapFallback(screen, px, py, ButtonAcceptance.ANY), "负 depth 键盖住的像素归 div")
        assertEquals(
            0,
            stub.rowLayoutCalls,
            "早闸先于行表换算：div 命中路径一次都不许算行表（return 之前不得出现 rowLayout）",
        )
    }

    @Test
    fun `div让位给像素格兜底时行表只换算一次`() {
        val lines = blankLines()
        lines[OK_ROW] = line(button("OK", 7L))
        val screen = screenWith(lines)
        val stub = Stub(halfCellPx, lineHeightPx)
        // 块在屏幕顶端那一小条，且横向挪到窗口之外：该像素上 div 无可点键
        attachDiv(screen, anchorTopPx = 0, divBlock("POP", 9, depth = -1, atX = FAR_X))
        val row = drawRowOfValue(screen, stub, 7L)
        val (px, py) = probe(stub, row, 0)
        assertEquals("7", stub.tapFallback(screen, px, py, ButtonAcceptance.ANY), "div 让位后必须取格层的值")
        assertEquals(
            1,
            stub.rowLayoutCalls,
            "兜底路径行表换算恰好一次：多于一次是重复劳动，零次说明没换算就返回（伪造）",
        )
    }

    // ---- ② 键全负 ----

    @Test
    fun `键全负的一帧里像素格兜底仍可点`() {
        val lines = blankLines()
        lines[OK_ROW] = line(button("OK", 7L))
        val screen = screenWith(lines)
        val stub = Stub(halfCellPx, lineHeightPx)
        attachDiv(screen, anchorTopPx = 0, divBlock("NEG", 9, depth = -1))
        assertEquals(listOf(-1), screen.divTargets().map { it.depth }.distinct(), "本帧 div 键表全负")
        val row = drawRowOfValue(screen, stub, 7L)
        val (px, py) = probe(stub, row, 0)
        assertNull(screen.divHitAt(floor(px).toInt(), floor(py).toInt()), "键全负且块不盖该像素 => div 无命中")
        assertEquals("7", stub.tapFallback(screen, px, py, ButtonAcceptance.ANY), "键全负也要让回格层：表非空不等于命中")
        assertEquals(1, stub.rowLayoutCalls, "div 未命中 => 兜底必须真的去换算格")
    }

    // ---- ③ 混合键 ----

    @Test
    fun `混合正负键且都没赢时取格层的值`() {
        val lines = blankLines()
        lines[OK_ROW] = line(button("OK", 7L))
        val screen = screenWith(lines)
        val stub = Stub(halfCellPx, lineHeightPx)
        val row = drawRowOfValue(screen, stub, 7L)
        attachDiv(
            screen,
            anchorTopPx = row * metrics.lineHeight,
            divBlock("NEG", 9, depth = -1, atX = FAR_X),
            divBlock("POS", 6, depth = 1),
        )
        assertEquals(listOf(-1, 1), screen.divTargets().map { it.depth }, "键表按 depth 升序摊平（attachDiv :776）")
        val (px, py) = probe(stub, row, 0)
        assertNull(screen.divHitAt(floor(px).toInt(), floor(py).toInt()), "正文行按钮盖住正 depth 块 => hitAt 让位回 null")
        val got = stub.tapFallback(screen, px, py, ButtonAcceptance.ANY)
        assertEquals("7", got, "混合键但两键都没赢 => 取格层的值；回 9 或 6 就是伪造命中")
        assertEquals(1, stub.rowLayoutCalls, "该路径确实落到了格换算")
    }

    // ---- ④ 兜底失败路径不得伪造命中 ----

    @Test
    fun `兜底算不出格的三条支路一律返回null`() {
        val screen = screenWith(blankLines())
        val stub = Stub(halfCellPx, lineHeightPx)
        assertNull(stub.tapFallback(screen, -1f, -1f, ButtonAcceptance.ANY), "内容区左上沿之外 => null")
        assertEquals(0, stub.rowLayoutCalls, "早闸在外侧 return，不该先换算行表")
        assertNull(
            stub.tapFallback(screen, halfCellPx / 2f, WINDOW_ROWS * lineHeightPx + 1f, ButtonAcceptance.ANY),
            "y 落不到任何行带（rowIndex 给 -1）=> null",
        )
        assertNull(
            stub.tapFallback(screen, (WINDOW_COLUMNS + 1) * halfCellPx, lineHeightPx / 2f, ButtonAcceptance.ANY),
            "列超出窗口宽度 => null",
        )
        assertEquals(2, stub.rowLayoutCalls, "落不到行与超宽窗各换算一次：换算在闸之前，闸本身不换算")
    }

    @Test
    fun `换算成功但格上没有按钮时返回null`() {
        val lines = blankLines()
        lines[OK_ROW] = line(button("OK", 7L))
        val screen = screenWith(lines)
        val stub = Stub(halfCellPx, lineHeightPx)
        val emptyHitRow = screen.hitRows().indexOfFirst { row -> row.runs.none { it.buttonValue != null } }
        assertTrue(emptyHitRow >= 0, "夹具里应当有不含按钮的空行")
        val (px, py) = probe(stub, emptyHitRow + stub.extraRowsAbove(screen), 0)
        assertNull(stub.tapFallback(screen, px, py, ButtonAcceptance.ANY), "空格必须算没点中，不许退化成空串")
        assertEquals(1, stub.rowLayoutCalls, "该路径确实走到了格换算之后")
    }

    // ---- ⑤ gated 走行内纵门 / 锚行回退走整行 ----

    @Test
    fun `直接命中的图形格按行内纵门判图元盒外的行内空白不触发`() {
        val lines = blankLines()
        lines[OK_ROW] = line(art("ART", 77, SHORT_ART_PX))
        val screen = screenWith(lines)
        val stub = Stub(halfCellPx, lineHeightPx)
        val row = drawRowOfValue(screen, stub, 77L)
        val hitRow = row - stub.extraRowsAbove(screen)
        assertEquals("77", screen.click(hitRow, 0, ButtonAcceptance.ANY), "夹具核对：同一格在整行口径下可点")
        assertNull(
            stub.tapFallback(screen, halfCellPx / 2f, row * lineHeightPx + lineHeightPx - 1f, ButtonAcceptance.ANY),
            "gated 支必须走 clickInline：点在图元盒外的行内空白不该触发（走 click 就还是命中）",
        )
        assertEquals(
            "77",
            stub.tapFallback(screen, halfCellPx / 2f, row * lineHeightPx + SHORT_ART_PX / 2f, ButtonAcceptance.ANY),
            "同一格落在图元盒内照样命中（证明上一条不是夹具坏了）",
        )
    }

    @Test
    fun `跨行立绘的锚行回退走整行口径不受纵门二次收窄`() {
        val lines = blankLines()
        lines[OK_ROW] = line(art("立绘", 88, TALL_ART_PX))
        val screen = screenWith(lines)
        val stub = Stub(halfCellPx, lineHeightPx)
        val anchorRow = drawRowOfValue(screen, stub, 88L)
        val (px, py) = probe(stub, anchorRow + 1, 0)
        assertNull(
            screen.click(anchorRow + 1 - stub.extraRowsAbove(screen), 0, ButtonAcceptance.ANY),
            "夹具核对：立绘锚行的下一行该列本无按钮",
        )
        val got = stub.tapFallback(screen, px, py, ButtonAcceptance.ANY)
        assertNotNull(got, "跨行立绘落在锚行带外也必须取值：走 clickInline 会被纵门判死")
        assertEquals("88", got, "锚行回退必须走整行口径 click")
        assertEquals(1, stub.rowLayoutCalls, "锚行回退同样只在兜底里换算一次行表")
    }

    private companion object {
        const val FONT_PX = 18
        const val FONT = "黑体"
        const val FORE = 0xC0C0C0
        const val WINDOW_ROWS = 22
        const val WINDOW_COLUMNS = 40
        const val MAX_LOG = 200
        // 必须等于 WINDOW_ROWS（见 Stub 的 KDoc）：窗口填满才让顶对齐行表与屏侧底对齐逆算重合。
        const val BLANK_LINES = WINDOW_ROWS
        const val OK_ROW = 6
        const val ART_CELLS = 2
        const val ART_BUTTON_ID = 5
        const val SHORT_ART_PX = 10
        const val TALL_ART_PX = 36
        const val BLOCK_W = 100
        const val BLOCK_H = 30
        const val FAR_X = 600
        const val VISIBLE_BOTTOM_LINE = 100L
    }
}
