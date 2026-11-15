package com.eratw.port.render.canvas

import com.eratw.port.render.view.ScreenLayout
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PATCH-PAGE-MISALIGN`（2026-11-12 玩家反馈）的回归闸：**会话期版面几何与生效度量必须同代**。
 *
 * ## 病（真机读数，`wm size` 改窗口宽、`hits` 命令）
 *
 * 2272px 基线：`cols=136 clientCols=136 halfCell=16.64px font=32`；
 * 改窄到 1704px（未修）：`cols=102 clientCols=136 halfCell=16.64px`。
 * [CanvasConsoleView.rebuildScreen] 的 attached 分支经 [CanvasConsoleView.resolveFor]
 * 把 `resolvedLayoutWidthPx` 刷成新宽，却把 `halfCellPx` 留在会话构造期那一代 ⇒
 * 绘制右沿 / 命中上界按 102 列裁，文字与按钮仍按 136 列折行，右侧 34 列被硬裁，
 * 就是玩家截图里「补丁页面 UI 错位」的形态。
 *
 * [ScreenLayout.resolve] 的列数守护本来就会在 1704px 把字号压到 24（`halfCell=12.48`），
 * 让 136 列仍铺得下；那一代度量过去在会话期被丢掉了。修法是在 attached 分支
 * `resolveFor` 之后补一次 `applyMetrics(layout.metrics)` —— **只换渲染度量，不重排文字**：
 * `ConsoleScreen.clientWidth` / sink 折行宽 / 日志 / revision 一个字不动。
 *
 * ## 为什么既要数值闸又要源码闸
 *
 * `CanvasConsoleView` 是 Android `View`，本模块没有 Robolectric，实例化不了：
 * - 数值闸用纯 JVM 的 [ScreenLayout.resolve] 复刻设备两档读数，证明「错代必分叉、
 *   同代必收敛」，把因果而不是某个魔数钉住；
 * - 源码闸钉住那一行换代确实挂在 attached 分支、且在 LIVE-REFLOW 判据**之前**
 *   （关态也必须同代，不能只在开态补）。
 *
 * 设备复验（最小语料 debug 包 + `emulator-5554`）：修复后同一序列读
 * `cols=136 clientCols=136 halfCell=12.48px viewW=1704 font=24`，`logLines=47` 与
 * 29 个按钮一个不少，`[31]`–`[60]` 全部对齐可见；`wm size reset` 回 `font=32` 可逆。
 */
class CanvasViewportMetricsParityTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 跳过 `//` 行注释体，返回行尾换行符的下标（换行由外层统一落串）。 */
    private fun lineCommentEnd(raw: String, start: Int): Int {
        var i = start
        while (i < raw.length && raw[i] != '\n') i++
        return i
    }

    /** 跳过 C 风格块注释体（slash-star 起始的那一种），把其中的换行原样落到 [out]，返回注释结束后的下标。 */
    private fun blockCommentEnd(raw: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
            if (raw[i] == '\n') out.append('\n')
            i++
        }
        return i + 2
    }

    /** 与同目录既有 AuditTest 同一把尺：剥注释但保留换行（换行数不变，行号锚点不受影响）。 */
    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' && i + 1 < raw.length && raw[i + 1] == '/') {
                i = lineCommentEnd(raw, i)
            } else if (c == '/' && i + 1 < raw.length && raw[i + 1] == '*') {
                i = blockCommentEnd(raw, i, sb)
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private val code: String by lazy { stripComments(src) }

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

    /**
     * [rebuildScreen] 函数体（剥注释、按大括号配平）。
     *
     * **刻意不用 `substringBefore("\n        …")` 切片**：源文件在 Windows 上是 CRLF，
     * 含 `\n` 的多行分隔串会静默匹配失败，`substringBefore` 退回整个函数体 ⇒
     * 「attached 分支里有没有某行」会被非 attached 分支的同名行喂成假绿（本波
     * 第一次反向证明只红 1 例就是这样）。改用**单行锚点的 indexOf 区间**，与换行符无关；
     * 既有 `CanvasLiveReflowTest.attachedBranch` 的同类弱点另登记，不在本波改面。
     */
    private val rebuildBody: String by lazy { bodyOf("private fun rebuildScreen(w: Int, h: Int)") }

    private val attachedStart: Int get() = rebuildBody.indexOf("if (attached != null)")
    private val attachedLayoutAt: Int get() =
        rebuildBody.indexOf("val layout = resolveFor(w, h)", attachedStart.coerceAtLeast(0))
    /** 换代行全文第一次出现（必须落在 attached 的 resolveFor 之后、回调分支之前）。 */
    private val applyAt: Int get() =
        rebuildBody.indexOf("applyMetrics(layout.metrics)", attachedLayoutAt.coerceAtLeast(0))
    private val liveReflowAt: Int get() =
        rebuildBody.indexOf("if (liveReflow)", attachedLayoutAt.coerceAtLeast(0))
    private val detachedLayoutAt: Int get() =
        rebuildBody.indexOf("val layout = resolveFor(w, h)", liveReflowAt.coerceAtLeast(0))
    /** attached 分支区间（截止到非 attached 路径的版面解算）。 */
    private val attachedBranch: String get() = rebuildBody.substring(
        attachedStart.coerceAtLeast(0),
        if (detachedLayoutAt > 0) detachedLayoutAt else rebuildBody.length,
    )

    // ==================================================================
    // 1) 源码面：换代必须挂在 attached 分支，且关态也要走
    // ==================================================================

    @Test
    fun `attached 分支解算版面后必须同步换渲染度量`() {
        assertTrue(attachedStart > 0, "rebuildScreen 必须仍有 feed 已挂分支")
        assertTrue(attachedLayoutAt > attachedStart, "attached 分支仍须重解版面几何")
        assertTrue(applyAt > attachedLayoutAt, "resolveFor 之后必须把 layout.metrics 换进生效度量")
        assertTrue(
            applyAt < liveReflowAt,
            "换代必须落在 attached 分支内（早于回调分支）；只找到非 attached 路径那一次说明 attached 的换代被摘了",
        )
    }

    @Test
    fun `换代在 LIVE-REFLOW 判据之前_关态也同源`() {
        val judge = rebuildBody.indexOf("viewportNeedsReflow(", attachedLayoutAt.coerceAtLeast(0))
        assertTrue(attachedBranch.contains("if (liveReflow)"), "开态回调分支必须还在")
        assertTrue(judge > 0, "触发判据必须还在")
        assertTrue(
            applyAt < liveReflowAt && applyAt < judge,
            "换代不能塞进 if (liveReflow) 里：两套宽出现在默认关态，关态也必须同源",
        )
        assertTrue(
            rebuildBody.indexOf("applyMetrics(layout.metrics)", applyAt + 1) > detachedLayoutAt,
            "非 attached 路径原有的换代必须原样保留（本次只补 attached 那一处）",
        )
    }

    @Test
    fun `换代不许顺手改会话折行宽或配置字号`() {
        val branch = attachedBranch
        assertTrue(
            branch.contains("resizeFeedScreen(attached.screen, rows)"),
            "rows-only 路径原样保留：换度量不重排文字",
        )
        val forbidden = listOf(
            "applySourceFontMetrics(",
            "applySourceWindowMetrics(",
            "swapScreen(",
            "resizeConsole(",
        )
        for (call in forbidden) {
            assertTrue(
                !branch.contains(call),
                "attached 分支不许出现 `$call`：本次只解决几何/度量同代，不是重开会话或重排",
            )
        }
    }

    // ==================================================================
    // 2) 数值面：按 ScreenLayout.resolve 原样复刻真机 2272 → 1704 两档
    // ==================================================================

    /**
     * 照 [CanvasConsoleView.resolveFor] 的 shift ⇄ font 不动点迭代解一档。
     * 设备 `WINDOW WIDTH:1800` 接线后 `windowWidthPx` 两档都大于物理宽，
     * 被 `min(窗口宽, 物理内容宽)` 夹成物理宽 ⇒ 这里按未接线（0）算同值。
     */
    private fun layoutFor(contentWidthPx: Int, heightPx: Int = 1080): ScreenLayout {
        var inset = 2
        var layout = resolve(contentWidthPx, heightPx, inset)
        repeat(5) {
            val nextInset = (layout.fontPixelSize / 6).coerceAtLeast(2)
            layout = resolve(contentWidthPx, heightPx, nextInset)
            if (nextInset == inset) return layout
            inset = nextInset
        }
        return layout
    }

    private fun resolve(contentWidthPx: Int, heightPx: Int, inset: Int): ScreenLayout =
        ScreenLayout.resolve(
            widthPx = (contentWidthPx - inset).coerceAtLeast(0),
            heightPx = heightPx,
            densityDpi = 420,
            configFontPx = 18,
            configLineHeightPx = 18,
            minColumns = ScreenLayout.DEFAULT_COLUMNS,
            contentWidthPx = contentWidthPx,
        )

    private fun windowColumns(layoutWidthPx: Int, halfCellPx: Float): Int =
        floor(layoutWidthPx / halfCellPx).toInt().coerceAtLeast(1)

    @Test
    fun `真机两档字号与半角格宽可复现`() {
        val wide = layoutFor(2272)
        val narrow = layoutFor(1704)
        assertEquals(32, wide.fontPixelSize, "2272px 基线：列数守护把字号压到 32")
        assertEquals(24, narrow.fontPixelSize, "1704px 窄屏：列数守护把字号压到 24")
        assertTrue(
            kotlin.math.abs(wide.metrics.cellPixelWidth - 16.64f) < 0.01f,
            "2272px 基线半角格宽应为 16.64px，实为 ${wide.metrics.cellPixelWidth}",
        )
        assertTrue(
            kotlin.math.abs(narrow.metrics.cellPixelWidth - 12.48f) < 0.01f,
            "1704px 窄屏半角格宽应为 12.48px，实为 ${narrow.metrics.cellPixelWidth}",
        )
        assertEquals(136, wide.clientWidth, "基线折行列数 = 会话构造期的 clientWidth")
    }

    @Test
    fun `只刷几何不刷度量必然分叉_同代则收敛`() {
        val wide = layoutFor(2272)
        val narrow = layoutFor(1704)
        val frozenHalfCell = wide.metrics.cellPixelWidth

        // 未修：新版面宽 ÷ 会话构造期冻结的 halfCell ⇒ 102 列，屏仍是 136 列。
        val stale = windowColumns(narrow.layoutWidthPx, frozenHalfCell)
        // 已修：新版面宽 ÷ 同一代度量 ⇒ 回到 136 列。
        val fresh = windowColumns(narrow.layoutWidthPx, narrow.metrics.cellPixelWidth)
        assertEquals(102, stale, "错代分叉必须与真机 before 读数一致")
        assertEquals(136, fresh, "同代后绘制/命中列数必须回到折行列数")
        assertEquals(wide.clientWidth, fresh, "单一宽源：windowColumns == ConsoleScreen.clientWidth")
        assertEquals(34, wide.clientWidth - stale, "被硬裁掉的正是右侧 34 列")
    }

    @Test
    fun `纯高度变化不换字号_输入栏键盘显隐零副作用`() {
        val tall = layoutFor(2272, heightPx = 1080)
        val short = layoutFor(2272, heightPx = 947)
        assertEquals(tall.fontPixelSize, short.fontPixelSize, "字号只由可绘制宽反推，高度不参与")
        assertEquals(tall.metrics.cellPixelWidth, short.metrics.cellPixelWidth)
        assertTrue(short.rows < tall.rows, "可见行数仍按高度走（原有 rows-only 语义不变）")
    }
}
