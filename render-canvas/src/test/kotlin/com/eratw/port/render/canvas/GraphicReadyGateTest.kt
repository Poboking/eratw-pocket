package com.eratw.port.render.canvas

import com.eratw.port.render.RunKind
import com.eratw.port.render.displaylist.DrawCmd
import com.eratw.port.render.displaylist.InlineGraphic
import com.eratw.port.render.view.GraphicsLayer
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FEEL1-L1「解码就绪 → 只重画受影响处」的 View 侧钉子（[GraphicReadyGate] 判式 +
 * `onGraphicResourceReady` 失效口径的源码面）。
 *
 * ## 为什么判式在纯 JVM、失效动作走源码面
 *
 * [CanvasConsoleView] 是 Android View，本模块没有 Robolectric（类文档见
 * [CanvasMemoryReleaseAuditTest] 同一口径的先例），实例化不了；所以把「在不在屏上」
 * 这半段闸抽成纯对象 [GraphicReadyGate] 直测，「只普通失效、绝不置脏」那半段用
 * 大括号配平的源码面钉子钉死 —— 后者正是本波刚收口的按下态行级纪律（`setPointer`
 * 的 KDoc：普通 invalidate，不走 requestRedraw），就绪回调若烧回整幅就是回归。
 *
 * ## 判式匹配的是「像素在场面」，不是「名字表」
 *
 * 三个问句对应绘制侧三个既有通道（上一帧画过的内联图段 / 背景层 / 图层，出处见
 * [GraphicReadyGate] 类 KDoc）；**遮罩名不在其列** —— 它不参与绘制，只被命中路径
 * 读像素（`drawResource(pressed)` 换图名与竖带切片名都在），就绪不该为它重画。
 */
class GraphicReadyGateTest {

    private fun imageCmd(
        row: Int,
        graphic: InlineGraphic?,
    ) = DrawCmd(
        row = row, colStart = 0, colEnd = 10, fore = 0xFFFFFF, back = 0x000000,
        styleMask = 0, kind = RunKind.IMAGE, glyphs = emptyList(), graphic = graphic,
    )

    private fun image(
        resource: String,
        button: String? = null,
        mask: String? = null,
        bands: List<String>? = null,
    ) = InlineGraphic.Image(
        resource = resource, widthPx = 100, heightPx = 50, yOffsetPx = 0,
        widthCells = 10, buttonResource = button, mappingResource = mask,
        bandResources = bands,
    )

    // ------------------------------------------------------------------
    // 1) references：一段内联图形的全部「可取图名」
    // ------------------------------------------------------------------

    @Test
    fun `主图 按下图 竖带切片名都算在场面`() {
        val g = image("art", button = "art_b", mask = "art_m", bands = listOf("t1", "t2"))
        assertTrue(GraphicReadyGate.references(g, "art"), "主图名（drawImage 每帧取图）")
        assertTrue(GraphicReadyGate.references(g, "art_b"), "按下态换图名（drawResource(pressed) 的真实取图名）")
        assertTrue(GraphicReadyGate.references(g, "t2"), "竖带切片名（drawBandStack 逐片取名）")
    }

    @Test
    fun `遮罩名与非本段的名字都不算`() {
        val g = image("art", button = "art_b", mask = "art_m", bands = listOf("t1"))
        assertFalse(GraphicReadyGate.references(g, "art_m"), "遮罩不参与绘制、只被命中路径读像素 —— 就绪不该重画")
        assertFalse(GraphicReadyGate.references(g, "other"))
        assertFalse(GraphicReadyGate.references(null, "art"))
        assertFalse(GraphicReadyGate.references(InlineGraphic.Rect(0, 0, 10, 10, 0xFF0000, 2), "art"), "色块不消费位图")
        assertFalse(GraphicReadyGate.references(InlineGraphic.Space(4), "art"), "空白推进段不消费位图")
    }

    // ------------------------------------------------------------------
    // 2) 三个在场面：命令表 / 背景层 / 图层
    // ------------------------------------------------------------------

    @Test
    fun `命令表问句只看带图形的命令`() {
        val cmds = listOf(
            imageCmd(0, null),
            imageCmd(1, InlineGraphic.Rect(0, 0, 10, 10, 0xFF0000, 2)),
            imageCmd(2, image("立绘", bands = listOf("标题-3"))),
        )
        assertTrue(GraphicReadyGate.inDrawnCmds(cmds, "立绘"))
        assertTrue(GraphicReadyGate.inDrawnCmds(cmds, "标题-3"))
        assertFalse(GraphicReadyGate.inDrawnCmds(cmds, "离屏图"))
        assertFalse(GraphicReadyGate.inDrawnCmds(cmds, ""), "空名恒不在场（与 View 入口的闸同向）")
        assertFalse(GraphicReadyGate.inDrawnCmds(emptyList(), "立绘"), "还没画过 = 不在场")
    }

    @Test
    fun `背景层与图层问句按各自的资源名`() {
        val bgs = listOf(
            GraphicsLayer.BgPlacement(resource = "bg1", depth = 0, opacity = 255),
            GraphicsLayer.BgPlacement(resource = "bg2", depth = 1, opacity = 128),
        )
        assertTrue(GraphicReadyGate.inBackgrounds(bgs, "bg2"))
        assertFalse(GraphicReadyGate.inBackgrounds(bgs, "bg3"))
        val layers = listOf(
            GraphicsLayer.ImageLayerSnapshot(
                spriteName = "lay", depth = 0L, lineNo = 3, xpos = 0, ypos = 0,
                width = 100, height = 100, opacity = 255, followScroll = false,
            ),
        )
        assertTrue(GraphicReadyGate.inImageLayers(layers, "lay"))
        assertFalse(GraphicReadyGate.inImageLayers(layers, ""))
    }

    // ------------------------------------------------------------------
    // 3) 源码面钉子：就绪口「恰好一次普通 invalidate、绝不置脏」
    // ------------------------------------------------------------------

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；口径同 [CanvasMemoryReleaseAuditTest]）。 */
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

    /**
     * 一个 ready 事件不许退化成整幅重画（契约 §2 L1-2 硬约束）：口径照按下态收口
     * （`setPointer` 的 KDoc），只做闸过之后的**恰好一次**普通 `invalidate()`；
     * 四个置脏口一个都不许出现。短路自证：把 `requestRedraw()` 塞回本口，本用例红。
     */
    @Test
    fun `就绪口只普通失效一次且不置脏`() {
        val body = bodyOf("fun onGraphicResourceReady(resource: String)")
        val plainInvalidates = Regex("""\binvalidate\(\)""").findAll(body).count()
        assertEquals(1, plainInvalidates, "就绪事件恰好一次普通 invalidate，多了就是自己造第二失效口")
        for (forbidden in listOf(
            "requestRedraw",
            "forceRedraw",
            "notifySurfaceLost",
            "bakeGuard",
            "notifyGraphicsChanged",
        )) {
            assertFalse(body.contains(forbidden), "置脏口 $forbidden 出现在就绪口：会把按下态的行级收敛烧回整幅")
        }
    }

    /** 先闸后失效：空名 / 通路未接 / 不在场都必须原路返回（离屏名字零失效）。 */
    @Test
    fun `就绪口先过闸再失效`() {
        val body = bodyOf("fun onGraphicResourceReady(resource: String)")
        assertTrue(body.contains("resource.isEmpty()"), "空名闸")
        assertTrue(body.contains("frameSource == null"), "图形通路未接 / 已摘的闸")
        assertTrue(body.contains("GraphicReadyGate.inDrawnCmds"), "内联图段在场面")
        assertTrue(body.contains("GraphicReadyGate.inBackgrounds"), "背景层在场面")
        assertTrue(body.contains("GraphicReadyGate.inImageLayers"), "图层在场面")
        assertTrue(body.contains("if (!onScreen) return"), "不在场必须直接返回")
    }

    /** 判式只认「可取图名」三族；遮罩名进了判式就是顺手扩大失效面。 */
    @Test
    fun `判式体不认遮罩名`() {
        val body = bodyOf("fun references(graphic: InlineGraphic?, resource: String)")
        assertTrue(body.contains("image.resource"))
        assertTrue(body.contains("image.buttonResource"))
        assertTrue(body.contains("bandResources"))
        assertFalse(body.contains("mappingResource"), "遮罩就绪不改像素，不许进判式")
    }
}
