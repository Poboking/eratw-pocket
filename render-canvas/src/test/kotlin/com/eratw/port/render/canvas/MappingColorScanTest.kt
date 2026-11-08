package com.eratw.port.render.canvas

import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.view.RowLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W6-mappingcolor：`mappingColor` 取色口径的**同坐标对拍**（H6 横向刀 + GFX-14 纵向刀 + shift 换代）。
 *
 * 装置全在 JVM：命令表直接手搭 [DrawCmd]（列游标口径同 `DisplayList.prepare`），
 * 行顶表走真 [RowLayout.plan]（与 `CanvasConsoleView.rowLayout` 传参逐式一致），
 * 「位图」是 [ImageFrame] 的几何读数（`handle` 只当身份标签）—— 不造 `android.graphics.Bitmap`、
 * 不走真机。被钉的判据全部带 C# 行号（本地 1.824 实测）：
 * - 绘制加 shift：spec `ConsoleImagePart.cs:171`、`ConsoleRectangleShapePart.cs:57`；
 *   取色减 shift 的前提就是这一条：spec `ConsoleButtonString.cs:147`；
 * - 文本/altText 用裸 PointX：spec `ConsoleImagePart.cs:177,181`（对拍用例反证「图形链含 shift」
 *   只作用于图形段）；
 * - 行顶查表不查等距式：BOARD GFX-14（883f7bf6 收了绘制/命中，取色是最后一路）；
 * - shift 随字号换代：spec `Config.cs:461-466` 的 `Math.Max(2, FontSize / 6)`。
 */
class MappingColorScanTest {

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun imageCmd(
        row: Int,
        colStart: Int,
        widthPx: Int,
        heightPx: Int,
        yOffsetPx: Int = 0,
        resource: String = "art",
        mask: String? = "map",
    ) = DrawCmd(
        row = row, colStart = colStart, colEnd = colStart + 10, fore = 0xFFFFFF, back = 0x000000,
        styleMask = 0, kind = RunKind.IMAGE, glyphs = emptyList(),
        graphic = InlineGraphic.Image(
            resource = resource, widthPx = widthPx, heightPx = heightPx,
            yOffsetPx = yOffsetPx, widthCells = 10, mappingResource = mask,
        ),
    )

    /** 帧：dest 尺寸不给（width/height 走 srcW/srcH 兜底），srcX/srcY 当基图子矩形左上角。 */
    private fun frame(tag: String, w: Int, h: Int, srcX: Int = 0, srcY: Int = 0) =
        com.eratw.port.render.view.ImageFrame(handle = tag, srcX = srcX, srcY = srcY, srcW = w, srcH = h)

    private fun sourceOf(vararg frames: Pair<String, com.eratw.port.render.view.ImageFrame?>) =
        frames.toMap().let { table -> { resource: String -> table[resource] } }

    // ------------------------------------------------------------------
    // 1) shift 算式（换代刀的单源）
    // ------------------------------------------------------------------

    /** spec: Config.cs:464 `Math.Max(2, FontSize / 6)`（整除截断），1.824 实测。 */
    @Test
    fun `shift 按生效字号取 二十六制且不低于 2`() {
        assertEquals(3, ShapePositionShift.of(18), "PC 基准档 18 ÷ 6")
        assertEquals(6, ShapePositionShift.of(36), "真机 page-fit 大字号档")
        assertEquals(5, ShapePositionShift.of(30))
        assertEquals(2, ShapePositionShift.of(12), "整除恰为 2")
        assertEquals(2, ShapePositionShift.of(11), "11÷6=1 要夹到下限 2")
        assertEquals(2, ShapePositionShift.of(0))
        assertEquals(20, ShapePositionShift.of(120))
    }

    // ------------------------------------------------------------------
    // 2) 横向：采样与绘制同一把尺（H6）
    // ------------------------------------------------------------------

    /**
     * 绘制框左沿 = `contentLeft + colStart * halfCell + shift`（spec: `ConsoleImagePart.cs:171`
     * 的 `destRect.X + PointX + shift`，`drawCmd` 图形支与 `mappingColor` 共用
     * [MappingColorGeometry.graphicOriginLeftPx]）。取色点落在**绘制像素**上 ⇒ 图内本地 x 从 0 起算，
     * 不再横偏 shift（改造前采样去减 shift 而绘制不加，本地 x 恒少 6px）。
     */
    @Test
    fun `采样横坐标与绘制框左沿同坐标`() {
        val contentLeft = 8f
        val halfCell = 18.72f
        val shift = ShapePositionShift.of(36)
        assertEquals(6, shift)
        val origin = MappingColorGeometry.graphicOriginLeftPx(contentLeft, 7, halfCell, shift)
        assertEquals(8f + 7 * halfCell + 6f, origin, 1e-3f)

        val cmds = listOf(imageCmd(row = 0, colStart = 7, widthPx = 100, heightPx = 50))
        val hit = MappingColorScan.scan(
            x = origin + 10f, y = 10f,
            cmds = cmds, rowTops = floatArrayOf(0f),
            contentLeftPx = contentLeft, halfCellPx = halfCell, shapeShiftPx = shift,
            frameOf = sourceOf(
                "art" to frame("A", w = 100, h = 50),
                "map" to frame("M", w = 50, h = 25, srcX = 3, srcY = 4),
            ),
        )
        assertNotNull(hit, "绘制框内 10px 处必须取得到色")
        assertEquals(10, hit.imageLocalX)
        assertEquals(10, hit.imageLocalY)
        // 缩放 + 基图定位（spec: ConsoleImagePart.cs:152-153 整除、ASpriteSingle.cs:50-64 子矩形偏移）
        assertEquals(3 + 10 * 50 / 100, hit.baseX)
        assertEquals(4 + 10 * 25 / 50, hit.baseY)

        // 反证钉（改造前的病灶本身）：绘制若还停在**不加 shift** 的旧框左沿（origin - shift），
        // 旧框里真实本地 10px 的像素，按共用尺会算成本地 4 —— 恒少 shift。
        // 「采样去减 shift 成立的前提是绘制加 shift」（spec: ConsoleButtonString.cs:147 vs
        // ConsoleImagePart.cs:171）：两把尺必须同批挪，本用例把差值钉死。
        val stale = MappingColorGeometry.localX(origin - shift + 10f, origin, 0)
        assertEquals(4, stale, "旧绘制位 10px 在新尺下少 shift —— 改造前取色横偏的形状")

        // 文本/altText 不沾 shift 的对拍（spec: ConsoleImagePart.cs:177,181 用裸 PointX）：
        // 图形框左沿比文本列游标恰多一个 shift。
        assertEquals(
            shift.toFloat(),
            origin - (contentLeft + 7 * halfCell),
            1e-3f,
            "同一列游标，图形段比文本段恰右移 shift",
        )
    }

    // ------------------------------------------------------------------
    // 3) 纵向：行顶查 RowLayout 表，不吃等距式（GFX-14）
    // ------------------------------------------------------------------

    /**
     * 标题页那种「纯图行被压缩」场景（`artAdvance < 行高`）：10 行窗口、9 文字行 +
     * 第 3 行一条**可压的**横带图（图高 = 行高 38px，判据见 `RowLayout.compressible`），
     * `contentBottom = 362` ⇒ `remain = 362 - 9*38 = 20`、`artAdvance = 20`、
     * `tops[3] = 3*38 + ... = 114`（手推，与 `RowLayoutTest` 同式）。
     * 等距式（改造前的 `originY + (row - extraRows) * lineHeight`，`extraRows=0`）给
     * `362 - 10*38 + 3*38 = 96` —— 两把尺差 18px。取色必须跟**绘制用的那张表**：
     * 在 `tops[3] + 30` 落笔的点必须算出本地 y = 30（等距式会算出 48）。
     *
     * 图高取 38px（= 行高）而不是原来的 100px：高于一行的图自 2026-11 起整类不参与压缩
     * （`RowLayout.compressible`，压了会让图越出脚本预留的空行、再经第二趟铺墨盖住文字，
     * 见 `ArtRowOverflowTest`），走不到 `artAdvance` 这一支 —— 本用例要钉的是
     * 「取色读行顶表而不是等距式」，所以换成能真被压的档位，判据逐值不变。
     * 帧高同步取 38 是为了让取色换算保持 1:1（`localY = 30` 才落在图内）。
     */
    @Test
    fun `压缩行取色走行顶表不走等距式`() {
        val lineHeight = 38f
        val cmds = listOf(
            imageCmd(row = 3, colStart = 5, widthPx = 40, heightPx = 38),
        )
        val plan = RowLayout.plan(
            cmds = cmds, windowRows = 10, contentTop = 0f, contentBottom = 362f,
            lineHeightPx = lineHeight, minArtRowHeightPx = 6f,
        )
        assertEquals(114f, plan.tops[3], 1e-4f, "压缩档手推行顶（remain=20 ⇒ artAdvance=20）")
        val equidistant = (362f - 10 * lineHeight) + 3 * lineHeight
        assertEquals(96f, equidistant, 1e-4f, "旧等距式读数：与表差一整段压缩量 18px")

        val halfCell = 18.75f
        val shift = 6
        val origin = MappingColorGeometry.graphicOriginLeftPx(0f, 5, halfCell, shift)
        val hit = MappingColorScan.scan(
            x = origin + 20f, y = plan.tops[3] + 30f,
            cmds = cmds, rowTops = plan.tops,
            contentLeftPx = 0f, halfCellPx = halfCell, shapeShiftPx = shift,
            frameOf = sourceOf(
                "art" to frame("A", w = 40, h = 38),
                "map" to frame("M", w = 40, h = 38, srcX = 10, srcY = 20),
            ),
        )
        assertNotNull(hit, "画在哪张图上就该在哪张图里取色")
        assertEquals(20, hit.imageLocalX)
        assertEquals(30, hit.imageLocalY, "本地 y 以行顶表读数 114 为原点（等距式会得 48）")
        assertEquals(30, hit.baseX)
        assertEquals(50, hit.baseY)
    }

    // ------------------------------------------------------------------
    // 4) 原版 return 0L 的各分支（口径逐条保留）
    // ------------------------------------------------------------------

    @Test
    fun `无遮罩段 缺主图帧 点在外 都不命中`() {
        val half = 10f
        val tops = floatArrayOf(0f, 0f)
        val artFrames = sourceOf(
            "art" to frame("A", w = 100, h = 50),
            "map" to frame("M", w = 20, h = 20),
        )
        fun scan(cmds: List<DrawCmd>, x: Float, y: Float) = MappingColorScan.scan(
            x = x, y = y, cmds = cmds, rowTops = tops,
            contentLeftPx = 0f, halfCellPx = half, shapeShiftPx = 2, frameOf = artFrames,
        )
        val inside = MappingColorGeometry.graphicOriginLeftPx(0f, 3, half, 2)

        // 无 srcm（遮罩名 null 或空串）⇒ 该段根本不参与取色
        assertNull(scan(listOf(imageCmd(0, 3, 100, 50, mask = null)), inside + 10f, 10f))
        assertNull(scan(listOf(imageCmd(0, 3, 100, 50, mask = "")), inside + 10f, 10f))
        // 主图缺帧 ⇒ 没有有效绘制框（spec: ConsoleImagePart.cs:60-64 提前 return、:82 没执行）
        assertNull(
            scan(listOf(imageCmd(0, 3, 100, 50, resource = "missing")), inside + 10f, 10f),
        )
        // 点不在图内：右沿是**严格小于** destW（spec: ConsoleButtonString.cs:143-144）
        assertNull(scan(listOf(imageCmd(0, 3, 100, 50)), inside + 100f, 10f))
        // 行下标越出窗口表 ⇒ 绘制侧本来就不落笔（drawScreen 的行索引闸同款）
        val outOfWindow = listOf(imageCmd(row = 5, colStart = 3, widthPx = 100, heightPx = 50))
        assertNull(scan(outOfWindow, inside + 10f, 10f))
    }

    @Test
    fun `遮罩帧缺失记命中但配色落 0`() {
        // 原版分支：图在、点在图内、遮罩查不到 ⇒ GetMappingColor 的 `cImageM != null` 判式
        // 落空，return 0L（spec: ConsoleImagePart.cs:137,148,156）——扫描半段要把它记成
        // 「mask=null 的命中」，View 侧折成 0L；且它照旧**覆盖前段**（后段为准）。
        val half = 10f
        val inside = MappingColorGeometry.graphicOriginLeftPx(0f, 3, half, 2)
        val hit = MappingColorScan.scan(
            x = inside + 10f, y = 10f,
            cmds = listOf(imageCmd(0, 3, 100, 50)), rowTops = floatArrayOf(0f),
            contentLeftPx = 0f, halfCellPx = half, shapeShiftPx = 2,
            frameOf = sourceOf(
                "art" to frame("A", w = 100, h = 50),
                "map" to null,
            ),
        )
        assertNotNull(hit)
        assertNull(hit.mask, "遮罩缺失 = 命中但无帧可读，View 侧折 0L")
        assertEquals(0, hit.baseX)
        assertEquals(0, hit.baseY)
    }

    @Test
    fun `叠放取最后一段 后段覆盖前段`() {
        // spec: ConsoleButtonString.cs:60-68 的 getLastImg 从数组尾部取第一个带遮罩段。
        val half = 10f
        val inside = MappingColorGeometry.graphicOriginLeftPx(0f, 0, half, 2)
        val hit = MappingColorScan.scan(
            x = inside + 10f, y = 10f,
            cmds = listOf(
                imageCmd(0, 0, 100, 50, resource = "art1", mask = "map1"),
                imageCmd(0, 0, 100, 50, resource = "art2", mask = "map2"),
            ),
            rowTops = floatArrayOf(0f),
            contentLeftPx = 0f, halfCellPx = half, shapeShiftPx = 2,
            frameOf = sourceOf(
                "art1" to frame("A1", w = 100, h = 50),
                "map1" to frame("M1", w = 100, h = 50, srcX = 100, srcY = 0),
                "art2" to frame("A2", w = 100, h = 50),
                "map2" to frame("M2", w = 100, h = 50, srcX = 7, srcY = 0),
            ),
        )
        assertNotNull(hit)
        assertEquals("M2", hit.mask?.handle, "后段为准")
        assertEquals(7 + 10, hit.baseX)
    }

    // ------------------------------------------------------------------
    // 5) 负宽翻正入口不变（改造前后同一支：fixNegativeExtent）
    // ------------------------------------------------------------------

    @Test
    fun `负宽段取色走翻正后的框`() {
        // spec: ConsoleImagePart.cs:83-92；翻正 = 起点前移 |宽|、尺寸取绝对值。
        val half = 10f
        val shift = 2
        val origin = MappingColorGeometry.graphicOriginLeftPx(0f, 3, half, shift)
        val hit = MappingColorScan.scan(
            x = origin + 40f + 10f, y = 10f,
            cmds = listOf(imageCmd(0, 3, widthPx = -40, heightPx = 50)),
            rowTops = floatArrayOf(0f),
            contentLeftPx = 0f, halfCellPx = half, shapeShiftPx = shift,
            frameOf = sourceOf(
                "art" to frame("A", w = 20, h = 50),
                "map" to frame("M", w = 40, h = 50),
            ),
        )
        assertNotNull(hit, "翻正后本地 x=10 落在宽 40 的框内")
        assertEquals(10, hit.imageLocalX)
        // destW = inlineSize(40, 20) = 40：缩放分母用翻正后的脚本宽，不是帧宽
        assertEquals(10, hit.baseX)
        // 翻正起点之前（旧负宽未翻正的框位）取不到：本地 x = -40+10 < 0
        assertTrue(
            MappingColorScan.scan(
                x = origin + 10f, y = 10f,
                cmds = listOf(imageCmd(0, 3, widthPx = -40, heightPx = 50)),
                rowTops = floatArrayOf(0f),
                contentLeftPx = 0f, halfCellPx = half, shapeShiftPx = shift,
                frameOf = sourceOf(
                    "art" to frame("A", w = 20, h = 50),
                    "map" to frame("M", w = 40, h = 50),
                ),
            ) == null,
            "未过翻正起点的点不算命中",
        )
    }
}
