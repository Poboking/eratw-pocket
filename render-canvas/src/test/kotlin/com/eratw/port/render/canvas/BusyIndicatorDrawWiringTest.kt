package com.eratw.port.render.canvas

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 「画带」调用的源码面哨兵（PRAY-BUSY 反向证明的落点）。
 *
 * `CanvasConsoleView` 挂在 Android 运行时上，app 的 JVM 单测面实例化不了，所以照
 * `QolGateWireTest` 的源码面口径钉形状：把 `onDraw` 里那一行 `drawBusyIndicatorBand`
 * 撤掉，本类当场红。几何与形态本身由 [BusyIndicatorBandTest] 逐值钉。
 */
class BusyIndicatorDrawWiringTest {

    private fun source(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt"),
            File("../render-canvas/src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 CanvasConsoleView.kt（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `内容绘制之后调用横带绘制`() {
        val src = source()
        assertTrue(
            src.contains("drawScreen(canvas, attached.screen)\n                // 内容绘制之后：长页忙碌横带独占内容区最下面一行（纯显示态，不进行表）。\n                drawBusyIndicatorBand(canvas, attached)"),
            "onDraw 的 feed 分支必须在 drawScreen 之后画横带（不是在壳层外置进度条、也不是右下角角标）",
        )
    }

    @Test
    fun `横带独占一行的落笔要件都在`() {
        val src = source()
        assertTrue(src.contains("paint.color = opaque(console.screen.backgroundArgb)"), "底必须用当前控制台底色的不透明覆盖")
        assertTrue(src.contains("val foreArgb = sink.foregroundColor ?: sink.defaultForeColor"), "字必须用当前前景色（SETCOLOR 活值 / Config.ForeColor 回落）")
        assertTrue(src.contains("val measured = paint.measureText(label)"), "水平居中必须用既有 Paint.measureText 量宽")
        assertTrue(src.contains("contentBottom = (height - paddingBottom).toFloat()"), "几何必须与 contentBottom / lineHeightPx 同源")
        assertTrue(src.contains("val busyGone = attached != null && busyBandDrawn && attached.busyIndicator() == null"), "回退横带像素：清掉后要再失效一次")
    }
}
