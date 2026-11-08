package com.eratw.port.platform.input

import com.eratw.port.render.scroll.Viewport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 滚动的**方向**判据（2026-11-03 玩家反馈：「往下滑反而画面往下动」= 手指下移却往更后面翻）。
 *
 * ## 钉住的是哪一条不变量
 * 屏上滚动的产出只有两条路，都在 `app/.../MainActivity.attachGestures` 的同一个触摸回调里：
 * - **慢拖逐段付账**：`ScrollLineAccumulator.add(手指位移)` → 读数原样交 `scrollRows`；
 * - **抬手快扫兜底**：`TouchGestureController.onUp` 的 [TouchGestureController.Action.Scroll]
 *   → [TouchGestureController.Action.Scroll.scrollRowsSteps] → `scrollRows`。
 *
 * 两条路的**内部**口径本来就相反（累加器按手指、`Action.Scroll.lines` 按 `Viewport.scrollDown`），
 * 所以换算是两次取反的叠加点 —— 11-02 那版手势改版就是在这里漏摘了一次取负，
 * 于是「手指下移」在第一条路上被送进了 `scrollDown`（走向更晚的内容）。本类把
 * **同一手势两条路必须同号同量**钉成用例：任何一侧再被多取反一次就红。
 *
 * ## 「更早 / 更晚」用真 [Viewport] 证，不靠注释
 * [Viewport] 是纯 JVM 的绘制侧视口，`offsetFromEnd` = 视口底部之下还压着的行数：
 * 它**增大**才叫「回看更早」，减小是「走向更晚」。本类把步数真的喂进去读这个数，
 * 而不是断言一个符号。
 *
 * 唯一没进断言的是 `scrollRows(rows)` 的两支映射（在 `:render-canvas`，是 `View` 子类，
 * 纯 JVM 起不来）：`CanvasConsoleView.kt:1427-1436` —— `rows > 0` → `viewport.scrollUp(rows)`，
 * `rows < 0` → `viewport.scrollDown(-rows)`。下面的 [applyScrollRows] 逐支照搬那份映射。
 */
class ScrollDirectionTest {

    private val lineH = 19f

    /** 复刻 `CanvasConsoleView.scrollRows(rows)`（0 行不动）。 */
    private fun applyScrollRows(viewport: Viewport, rows: Int) {
        when {
            rows > 0 -> viewport.scrollUp(rows)
            rows < 0 -> viewport.scrollDown(-rows)
            else -> Unit
        }
    }

    /** 起一个「保留 30 行、一屏 10 行、贴底」的视口（贴底 = `offsetFromEnd == 0`）。 */
    private fun bottomViewport(): Viewport {
        val viewport = Viewport(heightLines = 10)
        viewport.sync(30)
        assertEquals("起点贴底", 0, viewport.offsetFromEnd)
        return viewport
    }

    @Test
    fun `手指下移一整行回到更早的一屏`() {
        val rows = ScrollLineAccumulator().add(lineH, lineH)
        assertEquals("下移一整行 ⇒ 交给 scrollRows 的步数为 +1", 1, rows)
        val viewport = bottomViewport()
        applyScrollRows(viewport, rows)
        assertEquals("手指下移 = 内容跟着往下 = 底部之下又压住一行（回看更早）", 1, viewport.offsetFromEnd)
    }

    @Test
    fun `手指上移一整行走向更晚的内容`() {
        val viewport = bottomViewport()
        viewport.scrollUp(3)
        val rows = ScrollLineAccumulator().add(-lineH, lineH)
        assertEquals("上移一整行 ⇒ 步数为 -1", -1, rows)
        applyScrollRows(viewport, rows)
        assertEquals("手指上移 = 内容跟着往上 = 往回走一行（走向更晚）", 2, viewport.offsetFromEnd)
    }

    @Test
    fun `同一手势慢拖与快扫两条路同向同量`() {
        // 两端都按「整段位移一次给完」比较：逐段累加与一次给完的行数相同是
        // ScrollLineAccumulatorTest 已经钉过的性质，这里只查两条路的符号对不对得上。
        // ±19/2 = ±9.5 是四舍五入的临界点（上下两个方向都算 1 行）：慢拖那一路曾用
        // 带符号 roundToInt，-9.5 收成 0 而快扫付 1 行 —— 临界值进名单才守得住。
        for (dy in listOf(9.5f, 12f, 20f, 40f, 120f, -9.5f, -12f, -20f, -40f, -120f)) {
            val dragRows = ScrollLineAccumulator().add(dy, lineH)
            val controller = TouchGestureController(pixelsPerLine = lineH)
            controller.onDown(col = 0, y = 0f, timeMs = 1_000L)
            val action = controller.onUp(
                y = dy,
                timeMs = 1_120L,
                canSkip = true,
                scrolled = false,
                dx = 0f,
            )
            val swipeRows = when (action) {
                is TouchGestureController.Action.Scroll -> action.scrollRowsSteps
                else -> 0
            }
            assertEquals("dy=$dy：拖动付 $dragRows 行、快扫付 $swipeRows 行，同手势必须同向同量", dragRows, swipeRows)
        }
    }

    @Test
    fun `快扫那一路的 lines 与步数反号是刻意的`() {
        // Action.Scroll.lines 沿用 Viewport.scrollDown 口径（正 = 更晚），接线只用
        // scrollRowsSteps；这条把「别原样透传 lines」钉住（原样透传 = 方向翻反）。
        val controller = TouchGestureController(pixelsPerLine = lineH)
        controller.onDown(col = 0, y = 0f, timeMs = 1_000L)
        val scroll = controller.onUp(y = 40f, timeMs = 1_120L) as TouchGestureController.Action.Scroll
        assertEquals("手指下移在 Viewport 口径里是负行数", -2, scroll.lines)
        assertEquals("换算到 scrollRows 才是正（上翻 = 回看更早）", 2, scroll.scrollRowsSteps)
    }
}
