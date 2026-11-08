package com.eratw.port.render.canvas

import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.view.GraphicGeometry
import com.eratw.port.render.view.ImageFrame

/**
 * `Config.DrawingParam_ShapePositionShift` 的**唯一算式**（W6-H6 换代刀的落点）。
 *
 * 原版：`shift = Math.Max(2, FontSize / 6)`（spec: `Config.cs:461-466`；WINAPI 分支置 0，
 * 移植侧只有 TEXTRENDERER 语义，不搬那一支）。**字号换代它必须跟着换代**：
 * `CanvasConsoleView.applyMetrics` 换 `halfCellPx` 的同批就要重算它，
 * 否则字号（配置的 `FONT SIZE` 或被列数守护压小）换代后 shift 停留在旧字号
 * （真机 36px 档停留在 18px 档的 3px，绘制/取色同时偏 3px）。
 */
object ShapePositionShift {

    /** 生效字号（像素）→ 形状位移量。`CanvasConsoleView.shapeShiftPx` 的构造期与换代都走这里。 */
    fun of(fontPixelSize: Int): Int = (fontPixelSize / 6).coerceAtLeast(2)
}

/**
 * 图形段（内联图 / 色块）的**横原点和取色本地坐标**算式，绘制侧与取色侧共用（W6-H6 横向刀）。
 *
 * 原版口径：**绘制加 shift、采样也减 shift**，两把尺是同一把 ——
 * 图片绘制 `rectangle.X = destRect.X + PointX + Config.DrawingParam_ShapePositionShift`
 * （spec: `ConsoleImagePart.cs:171`），色块测量期烘焙 `rect.X += shift`
 * （spec: `ConsoleRectangleShapePart.cs:57`，`DrawTo` 再 `+ PointX`），
 * 取色 `num = pointX - PointX - mask.PointX - shift`（spec: `ConsoleButtonString.cs:147`）。
 * 文本侧不受影响：`ConsoleStyledString`/altText 用**裸** `PointX`
 * （spec: `ConsoleImagePart.cs:177,181`），所以 [CanvasConsoleView.drawImageAltText]
 * 的落点要把 shift 退回去。
 *
 * 本对象只算「列游标 → 图形框左沿」这一小步；框内偏移（`xpos`/负宽翻正）在
 * [localX] 的 [xFix0] 参数里带上，调用两侧各自 supply 同一个值（绘制侧走
 * `InlineGraphic.fixNegativeExtent` 的产物、取色侧显式再算一次，算式同源）。
 */
object MappingColorGeometry {

    /**
     * 图形段绘制框的左沿（View 横坐标）：`contentLeft + colStart * halfCellPx + shift`。
     *
     * [contentLeftPx] = 内容区左沿（`paddingLeft`），[colStart] = [DrawCmd.colStart]
     * （像素侧唯一换算 `halfCellPx` 与绘制文本段同源），[shapeShiftPx] =
     * [ShapePositionShift.of] 在当前字号档的读数。
     */
    fun graphicOriginLeftPx(
        contentLeftPx: Float,
        colStart: Int,
        halfCellPx: Float,
        shapeShiftPx: Int,
    ): Float = contentLeftPx + colStart * halfCellPx + shapeShiftPx

    /** 屏幕 x → 图内本地 x（减去绘制框左沿与翻正起点；整型截断同原版 `int` 域）。 */
    fun localX(x: Float, graphicOriginLeftPx: Float, xFix0: Int): Int =
        (x - (graphicOriginLeftPx + xFix0)).toInt()

    /** 屏幕 y → 图内本地 y（[rowTopPx] 必须是 [com.eratw.port.render.view.RowLayout] 行顶表读数）。 */
    fun localY(y: Float, rowTopPx: Float, yFix0: Int): Int =
        (y - (rowTopPx + yFix0)).toInt()
}

/**
 * `mappingColor` 的**逐命令扫描半段**（GFX-14 纵向刀 + H6 横向刀的最小可测单元）。
 *
 * 吃 [CanvasConsoleView] 交来的纯数据：命令表、行顶表（**唯一来源是 RowLayout 的行表**，
 * 与 [CanvasConsoleView.drawScreen] 的 `layout.tops[cmd.row]` 逐式一致；旧实现按
 * `contentOriginY + (cmd.row - extraRows) * lineHeightPx` 的等距式换算，纯图行被压缩时
 * 取色点整体偏一排）、绘制侧的取图口。位图像素读取（`Bitmap.getPixel`）留在 View 侧 ——
 * 那是本函数唯一不能下 JVM 的一步。
 *
 * 与原版 `GetMappedColor`（spec: `ConsoleButtonString.cs:138-152` +
 * `ConsoleImagePart.cs:135-157`）的对应：
 * - 范围判 + 缩放 = [InlineGraphic.maskSamplePoint]（左/上沿严格大于 0、右/下沿严格小于宽）；
 * - 基图定位 = `SpriteGetColor` 的「取色点 + 子矩形左上角」（spec: `ASpriteSingle.cs:50-64`），
 *   这里折进 [Hit.baseX]/[Hit.baseY]，基图越界回落语义（返回 Transparent ⇒ RGB 0）由 View 侧做；
 * - 叠放取**最后一段**（spec: `ConsoleButtonString.cs:60-68` 的 `getLastImg` 从数组尾部
 *   取第一个带遮罩段）：循环不提前退出，后段命中覆盖前段 —— 遮罩缺失的命中同样覆盖
 *   （对应 View 侧把 [Hit.mask] 为 null 折成 `0L`，口径与改造前逐分支一致）。
 */
object MappingColorScan {

    /**
     * 一次取色命中。
     *
     * @property imageLocalX 图内本地 x（调试读数用，与原版 `num` 同域）
     * @property imageLocalY 图内本地 y（同原版 `num2`）
     * @property mask 遮罩帧；null = 遮罩缺失/未创建（View 侧折成 0L，spec: `ConsoleImagePart.cs:137,148,156`）
     * @property baseX 遮罩基图取色横坐标（`srcX + 缩放点`，spec: `ASpriteSingle.cs:50-64`）
     * @property baseY 遮罩基图取色纵坐标
     */
    class Hit(
        val imageLocalX: Int,
        val imageLocalY: Int,
        val mask: ImageFrame?,
        val baseX: Int,
        val baseY: Int,
    )

    /**
     * 扫一遍 [cmds]，返回**最后一段**命中遮罩范围的取色点；无命中返回 null（原版 `return 0L`）。
     *
     * [rowTops] = `RowLayout.Plan.tops`（与绘制同表）；行下标越界的段跳过（口径同
     * [CanvasConsoleView.drawScreen] 的 `row < 0 || row >= layout.tops.size` 可见性闸）。
     * [frameOf] 是 View 的取图口（`ImageFrameSource.frame`）—— 图本体查不到帧的段
     * **没有有效绘制框**（spec: `ConsoleImagePart.cs:60-64` 提前 return、`:82` 没执行，
     * `destRect`/遮罩范围判全停在 0），直接跳过。
     */
    fun scan(
        x: Float,
        y: Float,
        cmds: List<DrawCmd>,
        rowTops: FloatArray,
        contentLeftPx: Float,
        halfCellPx: Float,
        shapeShiftPx: Int,
        frameOf: (resource: String) -> ImageFrame?,
    ): Hit? {
        var last: Hit? = null
        for (cmd in cmds) {
            val image = cmd.graphic as? InlineGraphic.Image ?: continue
            val maskName = image.mappingResource?.takeIf { it.isNotEmpty() } ?: continue
            val imgFrame = if (image.resource.isEmpty()) null else frameOf(image.resource)
            if (imgFrame == null) continue
            val row = cmd.row
            if (row < 0 || row >= rowTops.size) continue
            // 翻正入口与绘制侧同一个（InlineGraphic.fixNegativeExtent，spec: ConsoleImagePart.cs:83-92），
            // 尺寸兜底同样走 GraphicGeometry.inlineSize。
            val xFix = InlineGraphic.fixNegativeExtent(0, image.widthPx)
            val yFix = InlineGraphic.fixNegativeExtent(image.yOffsetPx, image.heightPx)
            val destW = GraphicGeometry.inlineSize(xFix[1], imgFrame.width)
            val destH = GraphicGeometry.inlineSize(yFix[1], imgFrame.height)
            val originLeft = MappingColorGeometry.graphicOriginLeftPx(
                contentLeftPx, cmd.colStart, halfCellPx, shapeShiftPx,
            )
            val localX = MappingColorGeometry.localX(x, originLeft, xFix[0])
            val localY = MappingColorGeometry.localY(y, rowTops[row], yFix[0])
            val mask = frameOf(maskName)
            // 镜像图（`InlineGraphic.Image.flipX/flipY`，2026-11 起负尺寸不再以负 widthPx
            // 形式出现在段上，改由标记表达）取色必须**镜像取样**：屏幕上第 localX 个像素
            // 画的是源图第 (destW-1-localX) 列，遮罩取色要跟着翻，否则取到的是对侧的颜色。
            // 语料 srcm 与负尺寸同时出现为 0 处，这条属语义完整（不翻会错，翻了不多花成本）。
            val sampleX = if (image.flipX) (destW - 1 - localX) else localX
            val sampleY = if (image.flipY) (destH - 1 - localY) else localY
            val sample = InlineGraphic.maskSamplePoint(
                sampleX, sampleY, destW, destH,
                mask?.width ?: 0, mask?.height ?: 0,
            )
            if (sample != null) {
                last = Hit(
                    imageLocalX = localX,
                    imageLocalY = localY,
                    mask = mask,
                    baseX = (mask?.srcX ?: 0) + sample.first,
                    baseY = (mask?.srcY ?: 0) + sample.second,
                )
            }
        }
        return last
    }
}
