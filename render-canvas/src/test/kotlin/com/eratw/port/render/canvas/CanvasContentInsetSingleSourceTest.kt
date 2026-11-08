package com.eratw.port.render.canvas

import com.eratw.port.render.view.ImageLayerGeometry
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `C-WINDOW-PAINT@1` / `C-CLIENT-METRICS@1` 的定向用例：`CanvasConsoleView` 的水平偏移**单源**。
 *
 * ## 为什么是源码形状闸
 *
 * `CanvasConsoleView` 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric），实例化不了；
 * 而「内容左沿有几份」是**代码形状**问题，不是某个函数能自证的。口径沿用本目录既有
 * [CanvasTapOutcomeAuditTest] / [CanvasMemoryReleaseAuditTest] 的做法：剥掉注释后按行断言，
 * 并复核一次纯函数的「绘制原点 = 取色本地原点」代数关系。
 *
 * ## 钉的五件事（R4.2 单一权威）
 *
 * 1. **内容左沿唯一**：代码里不许再把 `paddingLeft.toFloat()` 当内容左沿；所有 `paddingLeft`
 *    读数只能是内容宽算式（`w - paddingLeft - paddingRight`）或 [CanvasConsoleView.debugHits]
 *    的排障打印；
 * 2. **内容右沿唯一**：`val contentRight` 只许一处，且 = 内容左沿 + 版面宽；
 * 3. **图形段原点**：`graphicOriginLeftPx` 的首个实参只能是 `contentLeftPx()` 或内容区坐标
 *    原点 `originX`（div 子行：`contentLeft + rect.x`；烤行位图：相对行位图 0 并被整体贴到
 *    内容左沿），不许出现 padding 直算；
 * 4. **命中/取色同源**：`cellHitAt` / `setPointer` / `drawScreen` / `mappingColorOrNull` 的内容
 *    左沿都读 `contentLeftPx()`；`clientX` / `buttonAtClient` 与绘制同一左沿；
 * 5. **客户区读数**：`layoutWidthPx()` 是 `ScreenLayout.layoutWidthPx` 的唯一权威读口，
 *    `clientWidthPx()` / `windowColumns()` / `drawableWidthPx()` / `clientSizePx()` 全部从它派生。
 *
 * 真机视觉（调窄后左右对称留白、点击/取色不偏）仍是 `TODO(verify)`：本机 `adb devices` 为空，
 * 本夹具不冒充真机读数。
 */
class CanvasContentInsetSingleSourceTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /**
     * 剥掉 `//` 行注释与 `/** ... */` 块注释，**保留换行**（行号与源文件一一对应）。
     *
     * 必须剥注释：KDoc 里会提到旧写法（例如「不许再出现 paddingLeft.toFloat()」）与函数名，
     * 不剥会让闸把「散文」当成「代码」，也可能让改判的人把注释里的示例误判为通过。
     */
    private fun stripComments(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '/' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '/') {
                    while (i < raw.length && raw[i] != '\n') i++
                    continue
                }
                if (next == '*') {
                    i += 2
                    while (i + 1 < raw.length && !(raw[i] == '*' && raw[i + 1] == '/')) {
                        if (raw[i] == '\n') sb.append('\n')
                        i++
                    }
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private val code: String by lazy { stripComments(src) }
    private val codeLines: List<String> by lazy { code.lines() }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与既有 AuditTest 同一把尺）。 */
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

    // ------------------------------------------------------------------
    // 1) 内容左沿唯一
    // ------------------------------------------------------------------

    private val paddingLeftWhitelist = listOf(
        // [contentLeftPx] 本体：唯一允许把 paddingLeft 折算成内容左沿的算式。
        "paddingLeft + resolvedContentInsetLeftPx",
        // resolveFor / rebuildScreen notify 的**物理内容宽**算式（不是内容左沿）。
        "w - paddingLeft - paddingRight",
        // debugHits 的排障打印（读数面，不参与几何）。
        "append(paddingLeft)",
    )

    @Test
    fun `代码里不再出现把 paddingLeft 当内容左沿的写法`() {
        val offenders = ArrayList<String>()
        for ((index, line) in codeLines.withIndex()) {
            if (!line.contains("paddingLeft")) continue
            if (paddingLeftWhitelist.none { line.contains(it) }) {
                offenders += "第 ${index + 1} 行：${line.trim()}"
            }
        }
        assertEquals(emptyList(), offenders, "paddingLeft 只能出现在内容宽算式/contentLeftPx/debug 打印里：$offenders")
    }

    @Test
    fun `contentLeftPx 是内容左沿的唯一算式`() {
        assertTrue(
            code.contains("private fun contentLeftPx(): Float = (paddingLeft + resolvedContentInsetLeftPx).toFloat()"),
            "内容左沿的唯一来源必须是 contentLeftPx() = paddingLeft + 居中偏移",
        )
        // 左沿的四个消费面都读它（钉住「绘制/命中/取色/坐标换算同一偏移」）。
        for (signature in listOf(
            "private fun cellHitAt(",
            "fun setPointer(",
            "private fun drawScreen(",
            "fun mappingColorOrNull(",
        )) {
            assertTrue(
                bodyOf(signature).contains("val contentLeft = contentLeftPx()"),
                "$signature 里的内容左沿必须读 contentLeftPx()",
            )
        }
    }

    // ------------------------------------------------------------------
    // 2) 内容右沿唯一
    // ------------------------------------------------------------------

    @Test
    fun `内容右沿只允许一处赋值且等于左沿加版面宽`() {
        val assignments = Regex("""val contentRight\s*=""").findAll(code).count()
        assertEquals(1, assignments, "val contentRight 只许一处（多一份就是第二个右沿口径）")
        val drawScreen = bodyOf("private fun drawScreen(")
        assertTrue(
            drawScreen.contains("val contentRight = contentLeft + layoutWidthPx()"),
            "内容右沿必须 = 内容左沿 + 版面客户区宽（不许用 width - paddingRight）",
        )
        assertEquals(0, Regex("""width - paddingRight""").findAll(code).count(), "不许再用 width - paddingRight 当内容右沿")
    }

    // ------------------------------------------------------------------
    // 3) 图形段原点：只换实参来源，算式不变
    // ------------------------------------------------------------------

    @Test
    fun `图形段原点首参只能是内容左沿或内容区坐标原点`() {
        val calls = Regex("""graphicOriginLeftPx\s*\(\s*([^,\n]+?)\s*,""").findAll(code).toList()
        assertEquals(2, calls.size, "CanvasConsoleView 里应有 drawDivCmd / drawCmd 两处图形原点调用")
        val allowedFirstArgs = setOf("contentLeftPx()", "originX", "contentLeft")
        for (match in calls) {
            val firstArg = match.groupValues[1].trim()
            assertTrue(firstArg in allowedFirstArgs, "graphicOriginLeftPx 首参是 `$firstArg`，只许来自内容左沿/内容区原点")
            assertTrue(!firstArg.contains("paddingLeft"), "首参不许直算 paddingLeft：$firstArg")
        }
        // 绘制侧的 originX 链路：drawScreen → drawTextPasses/drawDivBlocks 的 contentLeft 来自 contentLeftPx()。
        assertTrue(
            bodyOf("private fun drawScreen(").contains("contentLeft = contentLeft"),
            "drawScreen 必须把内容左沿透传给行/div/图形绘制",
        )
    }

    @Test
    fun `绘制原点与取色本地坐标互为同一把尺`() {
        // 纯函数面：内容左沿整体平移 delta ⇒ 同一个屏幕点换算出的图内本地坐标不变
        // （绘制与取色共用 graphicOriginLeftPx，只换内容左沿实参来源，不许各算各的）。
        val halfCell = 10f
        val shift = 2
        val col = 3
        val contentLeft = 12f
        val delta = 50f
        val x = contentLeft + col * halfCell + 20f
        val originA = MappingColorGeometry.graphicOriginLeftPx(contentLeft, col, halfCell, shift)
        val originB = MappingColorGeometry.graphicOriginLeftPx(contentLeft + delta, col, halfCell, shift)
        assertEquals(
            MappingColorGeometry.localX(x + delta, originB, 0),
            MappingColorGeometry.localX(x, originA, 0),
            "内容左沿平移后，绘制框与取色本地坐标必须一起偏（同一把尺）",
        )
        // 图层几何也走同一条左沿（contentLeftPx 是第一参）。
        val layerA = ImageLayerGeometry.layerLeftPx(contentLeft, shift, 0)
        val layerB = ImageLayerGeometry.layerLeftPx(contentLeft + delta, shift, 0)
        assertEquals(delta, layerB - layerA, "图层左沿同样随内容左沿平移")
    }

    // ------------------------------------------------------------------
    // 4) 命中/取色/坐标换算与绘制同一左沿
    // ------------------------------------------------------------------

    @Test
    fun `clientX 与 buttonAtClient 用同一内容左沿`() {
        assertTrue(
            code.contains("fun clientX(viewX: Float): Int = (viewX - contentLeftPx()).roundToInt()"),
            "客户区坐标横原点必须是内容左沿（居中偏移后仍与绘制同源）",
        )
        assertTrue(
            code.contains("clientX + contentLeftPx()"),
            "buttonAtClient 必须把内容左沿加回去（与 clientX 成对，否则指向整体偏 inset）",
        )
        // 纵向不动（D1 顶对齐）：clientY 仍只减 paddingTop。
        assertTrue(
            code.contains("fun clientY(viewY: Float): Int = (viewY - paddingTop).roundToInt()"),
            "纵向顶点对齐不变：clientY 只减 paddingTop",
        )
    }

    // ------------------------------------------------------------------
    // 5) 客户区读数全部从 ScreenLayout 的版面宽派生
    // ------------------------------------------------------------------

    @Test
    fun `客户区宽与列数读数都从 layoutWidthPx 派生`() {
        assertTrue(
            code.contains("fun layoutWidthPx(): Int = resolvedLayoutWidthPx"),
            "layoutWidthPx 是 ScreenLayout.layoutWidthPx 的唯一权威读口",
        )
        assertTrue(
            code.contains("private fun drawableWidthPx(): Int = (layoutWidthPx() - shapeShiftPx).coerceAtLeast(0)"),
            "可绘制宽 = 版面宽 - 形状预留",
        )
        assertTrue(
            code.contains("fun windowColumns(): Int = floor(layoutWidthPx() / halfCellPx).toInt().coerceAtLeast(1)"),
            "命中域上界 windowColumns 与版面宽同源",
        )
        assertTrue(
            code.contains("fun clientWidthPx(): Int = layoutWidthPx()"),
            "CLIENTWIDTH() 的注入源 = 版面客户区宽",
        )
        assertTrue(
            code.contains("fun clientSizePx(): Pair<Int, Int> = layoutWidthPx() to clientHeightPx()"),
            "MouseState 客户区尺寸 = (版面宽, 客户区高)，居中留白不算客户区",
        )
    }

    @Test
    fun `窗口宽高灌入与脚本 px 逆折算都在`() {
        assertTrue(code.contains("fun applySourceWindowMetrics(windowWidthScriptPx: Int, windowHeightScriptPx: Int)"),
            "会话前窗口度量灌入口必须存在（与 applySourceFontMetrics 同模式）")
        assertTrue(code.contains("sourceWindowWidthScriptPx = windowWidthScriptPx"), "灌入必须存脚本 px 原值")
        assertTrue(code.contains("fun windowWidthScriptPx(): Int"), "交给 core 播种的同尺逆折算读口必须存在")
        assertTrue(
            code.contains("val ratio = metrics.fontPixelSize.toFloat() / sourceFontPixelSize"),
            "逆折算比 = 真机字号 / 脚本字号（与 CellMetrics.scriptPixels 同一把尺）",
        )
        // 窗口宽/高的脚本 px 折算必须喂给 ScreenLayout.resolve 的三个新参数。
        assertTrue(
            code.contains("windowWidthPx = windowWidthPx"),
            "窗口宽必须进 ScreenLayout.resolve（C-WINDOW-LAYOUT 的入参）",
        )
        assertTrue(
            code.contains("contentWidthPx = contentWidthPx"),
            "物理内容宽必须进 ScreenLayout.resolve（解算点在它里面算右端预留与居中偏移）",
        )
        assertTrue(
            code.contains("candidate.scriptPixels(sourceWindowWidthScriptPx, sourceFontPixelSize)"),
            "WindowX 脚本 px → 设备 px 必须走 CellMetrics.scriptPixels（同尺）",
        )
    }
}
