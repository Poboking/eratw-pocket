package com.eratw.port.platform.image

import com.eratw.port.core.host.GraphicsSink
import com.eratw.port.render.glyph.CellMetrics
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.render.view.GraphicsSinkBridge

/**
 * `GraphicsSink` 的 Android 壳（UP1-WIRE-1 之后只剩装配差异）。
 *
 * 六条图形语句的参数换算、守卫与 C# 出处全部在共享桥
 * [com.eratw.port.render.view.GraphicsSinkBridge] 里；本类只负责 Android 特有的
 * 一件事：把「屏上要变了」经 [onInvalidate] 投回 UI 线程（生产接线是
 * `GameHost.installGraphics` 的 `view.notifyGraphicsChanged()`）。
 *
 * 共享实现放 `render` 的依据：`render` 已依赖 `core-vm`（拿 `GraphicsSink`），
 * `platform` 与 `tools/uipreview` 又都依赖 `render`；放 `core-vm` 会反向依赖
 * `GraphicsLayer`/`InlineGraphic` 成环。
 *
 * ## 线程
 *
 * 本类在 **VM 线程**被语句层调用（`StatementExecutor` 的执行线程）。
 * [GraphicsLayer] 的写入是无锁安全的；[onInvalidate] 必须实现成
 * `View.postInvalidateOnAnimation()` 一类跨线程入口。
 *
 * @param layer 图形层状态（与 `ConsoleFeed` / `CanvasConsoleView` 必须同一个实例）
 * @param metrics 像素 ↔ 半角格换算器（与绘制侧不同源就会错位）
 * @param scriptFontPixelSize 脚本 `px` 的基准字号（= 引擎生效 `Config.FontSize`），
 *                与 HTML 侧同尺的判据见 `render/glyph/CellMetrics.scriptPixels`；0 = 不折算
 * @param onInvalidate 屏上要变了的通知（null = 不通知，测试用）
 */
class AndroidGraphicsSink(
    layer: GraphicsLayer,
    metrics: CellMetrics,
    scriptFontPixelSize: Int = 0,
    onInvalidate: (() -> Unit)? = null,
) : GraphicsSink by GraphicsSinkBridge(
    layer,
    metrics,
    scriptFontPixelSize = scriptFontPixelSize,
    onInvalidate = onInvalidate,
) {

    companion object {
        /** 语义与 [GraphicsSinkBridge.DEFAULT_RECT_COLOR] 同一个；保留旧引用面。 */
        const val DEFAULT_RECT_COLOR = GraphicsSinkBridge.DEFAULT_RECT_COLOR
    }
}
