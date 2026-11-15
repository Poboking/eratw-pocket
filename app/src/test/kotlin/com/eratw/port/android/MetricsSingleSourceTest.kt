package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 度量单源化的源码面哨兵（2026-11-13，R5.2 收口）。
 *
 * 首轮架构审查定位的病灶族：脚本 px / 设备 px / 格宽三套坐标换算散在多处，每次版面改动
 * 需同改多点 —— WindowX / CLIENTWIDTH / MOUSEX 三族玩家可见 bug 全是它。历次修复波已把
 * 图形侧（[CellMetrics.scriptPixels]）、回填链（ScriptPixelMetrics → BuiltinHost.windowWidthScriptPx）
 * 收编成单源；本波收掉最后两块（反折 ÷k 入口、手势列号），并用哨兵钉死「不许再长出第二把尺」：
 *
 * * **×k 与 ÷k 同尺**：反折只许走 `CellMetrics.scriptPixelsInverse`，手写 ratio 算式禁入；
 * * **列号同源**：手势列号与 tap 命中共用 `contentLeftPx` / `halfCellPx` 权威读口；
 * * **播种单源**：GameSession 的四参播种只许从 View 权威读口取数。
 */
class MetricsSingleSourceTest {

    private fun sourceOf(vararg candidates: String): String {
        val file = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: error("找不到源文件（试了 ${candidates.joinToString()}）")
        // CRLF 工作树归一：多行锚点必须先归一，否则「明明在」也 0 命中（2026-11-08 实测）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun cellMetrics(): String = sourceOf(
        // 公开分仓：render 在同级引擎仓 erak-engine（原单体布局作兼容回落）。
        "../../erak-engine/render/src/main/kotlin/com/eratw/port/render/glyph/CellMetrics.kt",
        "../render/src/main/kotlin/com/eratw/port/render/glyph/CellMetrics.kt",
        "render/src/main/kotlin/com/eratw/port/render/glyph/CellMetrics.kt",
    )

    private fun canvasView(): String = sourceOf(
        "../render-canvas/src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt",
        "render-canvas/src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt",
    )

    private fun host(): String = sourceOf(
        "../app/src/main/kotlin/com/eratw/port/android/GameHost.kt",
        "app/src/main/kotlin/com/eratw/port/android/GameHost.kt",
    )

    private fun mainActivity(): String = sourceOf(
        "../app/src/main/kotlin/com/eratw/port/android/MainActivity.kt",
        "app/src/main/kotlin/com/eratw/port/android/MainActivity.kt",
    )

    @Test
    fun `反折只许走单源入口 手写ratio禁入`() {
        val metrics = cellMetrics()
        assertTrue(
            "反折入口必须存在（scriptPixels 的同尺逆算）",
            metrics.contains("fun scriptPixelsInverse(devicePx: Int, scriptFontPixelSize: Int): Int"),
        )
        assertTrue(
            "折算比定义必须活在 CellMetrics（两向共用一处）",
            metrics.contains("val ratio = fontPixelSize.toFloat() / scriptFontPixelSize"),
        )
        val body = canvasView()
            .substringAfter("fun windowWidthScriptPx(): Int")
            .substringBefore("    }")
        assertTrue(
            "windowWidthScriptPx 必须委托单源入口",
            body.contains("metrics.scriptPixelsInverse(layoutWidth, sourceFontPixelSize)"),
        )
        assertFalse(
            "手写反折算式禁入（第二把尺回潮闸）",
            body.contains("/ ratio") || body.contains("toFloat() / sourceFontPixelSize"),
        )
    }

    @Test
    fun `手势列号与命中同源 不许自算除法`() {
        val view = canvasView()
        assertTrue(
            "View 必须公开 columnAt 读口（contentLeftPx + halfCellPx 单一权威）",
            view.contains("fun columnAt(x: Float): Int") &&
                view.contains("= kotlin.math.floor((x - contentLeftPx()) / halfCellPx).toInt()"),
        )
        val activity = mainActivity()
        assertTrue(
            "手势列号必须委托 View 权威读口",
            activity.contains("touch.onDown(consoleView.columnAt(event.x), event.y, event.eventTime)"),
        )
        assertFalse(
            "自算列号除法禁入（漏算 contentInset 的两把尺回潮闸）",
            activity.contains("floor((x - consoleView.paddingLeft)"),
        )
    }

    @Test
    fun `GameSession 播种只许从 View 权威读口取数`() {
        val host = host()
        assertTrue(
            "播种必须经 ScriptPixelMetrics 值对象",
            host.contains("val metrics = ScriptPixelMetrics("),
        )
        assertTrue(
            "四个播种位都必须取自 View 权威读口（clientWidth/clientHeight/cellMetrics/windowWidthScriptPx）",
            host.contains("clientWidthPx = view.clientWidthPx().toLong(),") &&
                host.contains("clientHeightPx = view.clientHeightPx().toLong(),") &&
                host.contains("deviceFontPixelSize = view.cellMetrics().fontPixelSize,") &&
                host.contains("windowWidthScriptPx = view.windowWidthScriptPx().toLong(),"),
        )
        assertFalse(
            "播种位不许出现自算宽度（两把尺回潮闸）",
            host.contains("clientWidthPx = ") && host.contains("resolvedLayoutWidthPx"),
        )
    }
}
