package com.eratw.port.render.canvas

import com.eratw.port.render.Grid
import com.eratw.port.render.adapter.ConsoleAdapter
import com.eratw.port.render.view.ConsoleScreen
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LIVE-REFLOW L4（`CanvasConsoleView` 的换屏半段）的判据钉子。
 *
 * ## 为什么是「纯函数 + 源码面」两层
 *
 * [CanvasConsoleView] 是 Android `View`，本模块只有纯 JVM 单测（无 Robolectric，依赖面见
 * `render-canvas/build.gradle.kts`），**实例化不了**；而 `render-canvas` 的编译面也看不见
 * `:core-vm` 的 `LineBufferConsoleSink`（`:render` 对 core-vm 是 implementation 依赖），
 * 于是连 `ConsoleFeed` 都装配不出来。口径沿用本目录既有先例（[CanvasMemoryReleaseAuditTest]、
 * [GraphicReadyGateTest]）：
 *
 * 1. **判据抽成纯对象** [LiveReflowPlan]，在桌面直测「什么情况才允许回调 / 才允许换屏」；
 * 2. **动作顺序**用大括号配平的源码面钉子钉住 —— 顺序错（先换屏后 applyMetrics、漏 force）
 *    的代价是列号漂移/新旧度量混一帧，而这两件事没有第二条可观测通道。
 *
 * ## 判的是哪几条不变量（契约 §5 L4 + §0）
 *
 * 1. `liveReflow = false` **永不回调**、永不换屏（关态逐字等价的红线）；
 * 2. `liveReflow = true` 且列数与屏宽**一致**时不回调（同尺寸重复布局零副作用）；
 * 3. 列数变了才回调，且回调后**直接 return**、不许落到旧的 rows-only（`resizeFeedScreen`）路径；
 * 4. `reflowAttachedFeed` 的三支：没挂 feed ⇒ false；宽度不符 ⇒ `IllegalStateException`
 *    （核心必须先 resize）；宽度对齐 ⇒ 新建空屏并按固定顺序换给 feed；
 * 5. 换屏用的新屏口径：列数 = 入参、行数 = 入参、`maxLog` 仍取 `Grid.MAX_LOG_DEFAULT`
 *    （MAXLOG 语义不动）、内容为空。
 */
class CanvasLiveReflowTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个声明的函数体（按大括号配平，先剥行注释；与既有 AuditTest 同一把尺）。 */
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

    // ==================================================================
    // 1) 触发判据（纯 JVM）：什么时候才许问宿主重排
    // ==================================================================

    /** 关态红线：开关关闭时，**任何**宽度组合都不许触发（横屏/竖屏旧行为逐字不变）。 */
    @Test
    fun `开关关闭永不触发重排`() {
        assertFalse(
            LiveReflowPlan.viewportNeedsReflow(liveReflow = false, attachedWidth = 40, layoutColumns = 30),
            "liveReflow=false 时宽度变了也不许回调 —— 关态必须逐字走旧的 rows-only 路径",
        )
        assertFalse(
            LiveReflowPlan.viewportNeedsReflow(liveReflow = false, attachedWidth = 40, layoutColumns = 40),
            "liveReflow=false 时同宽更不该触发",
        )
    }

    /** 同宽 no-op：开关打开但列数没变（系统栏显隐只改行数）时，回调一次都不许发。 */
    @Test
    fun `开关打开但列数未变不触发`() {
        assertFalse(
            LiveReflowPlan.viewportNeedsReflow(liveReflow = true, attachedWidth = 40, layoutColumns = 40),
            "宽度没变 ⇒ 只需改行数（resizeFeedScreen），不许惊动核心重排",
        )
    }

    /** 开关打开 + 列数变了（两个方向）才触发 —— 横屏塞进竖屏、竖屏拉成横屏都算。 */
    @Test
    fun `开关打开且列数变化才触发`() {
        assertTrue(
            LiveReflowPlan.viewportNeedsReflow(liveReflow = true, attachedWidth = 40, layoutColumns = 30),
            "变窄（横→竖）必须触发重排",
        )
        assertTrue(
            LiveReflowPlan.viewportNeedsReflow(liveReflow = true, attachedWidth = 30, layoutColumns = 40),
            "变宽（竖→横）必须触发重排",
        )
    }

    // ==================================================================
    // 2) 换屏判据（纯 JVM）：reflowAttachedFeed 的三支
    // ==================================================================

    /** 关态：本方法安全返回 false，且不换屏（判据在最前面，连宽度校验都不做）。 */
    @Test
    fun `换屏_关态一律跳过`() {
        assertEquals(
            LiveReflowPlan.Step.Skip,
            LiveReflowPlan.swapStep(liveReflow = false, sinkWidth = 40, columns = 30),
            "liveReflow=false 时即便宽度不符也只许返回 false，不许抛错、不许换屏",
        )
        assertEquals(
            LiveReflowPlan.Step.Skip,
            LiveReflowPlan.swapStep(liveReflow = false, sinkWidth = 30, columns = 30),
            "liveReflow=false 时同宽也不换屏",
        )
    }

    /** 没挂 feed（演示路径）：返回 false，零状态变化。 */
    @Test
    fun `换屏_没挂 feed 一律跳过`() {
        assertEquals(
            LiveReflowPlan.Step.Skip,
            LiveReflowPlan.swapStep(liveReflow = true, sinkWidth = null, columns = 30),
            "feed == null ⇒ false（契约 §1 对 false 的定义：无状态变化）",
        )
    }

    /** 宽度不符 = 调用方漏了 `sink.resize`：必须报错，不许换半张屏。 */
    @Test
    fun `换屏_宽度不符要求核心先 resize`() {
        assertEquals(
            LiveReflowPlan.Step.WidthMismatch,
            LiveReflowPlan.swapStep(liveReflow = true, sinkWidth = 40, columns = 30),
            "columns 与当前屏宽不一致 ⇒ IllegalStateException（两套坐标系并存必漂）",
        )
    }

    /** 宽度对齐：唯一的换屏支。 */
    @Test
    fun `换屏_宽度对齐才换`() {
        assertEquals(
            LiveReflowPlan.Step.Swap,
            LiveReflowPlan.swapStep(liveReflow = true, sinkWidth = 30, columns = 30),
            "核心已 resize 到同宽 ⇒ 可以建新屏并换给 feed",
        )
    }

    // ==================================================================
    // 3) 新屏口径（真 ConsoleScreen，不碰 View）：空屏 + 列数/行数入参 + MAXLOG 不动
    // ==================================================================

    /**
     * 生产装配式：`ConsoleScreen(columns, Grid.MAX_LOG_DEFAULT, rows)`。
     *
     * 三条都要成立才配当「换屏目标」：宽度是核心里 **已生效** 的那一列数、行数是本次解出的
     * 可见行数、保留列表容量刚好覆盖 `rows + DRAW_EXTRA_ABOVE`（绘制窗口比命中窗口多一排，
     * 见 [ConsoleAdapter.DRAW_EXTRA_ABOVE]）；且必须是**空屏** —— `swapScreen` 的前提
     * （带旧内容会被当成「快照变长」再追加一遍）。
     */
    @Test
    fun `新屏口径_空屏且列数行数按入参`() {
        val screen = ConsoleScreen(30, Grid.MAX_LOG_DEFAULT, 44)
        assertEquals(30, screen.clientWidth, "新屏宽度必须等于入参列数（= 核心已 resize 到的宽）")
        assertEquals(44, screen.heightLines, "新屏可见行数必须等于本次解出的 layout.rows")
        assertEquals(Grid.MAX_LOG_DEFAULT, screen.maxLog, "MAXLOG 语义不动：仍取 Grid.MAX_LOG_DEFAULT")
        assertEquals(
            44 + ConsoleAdapter.DRAW_EXTRA_ABOVE,
            screen.list.maxRows,
            "绘制列表容量 = 可见行数 + 多取的那一排",
        )
        assertEquals(emptyList<String>(), screen.snapshotText(), "换屏目标必须是空屏（swapScreen 的前提）")
        assertEquals(0, screen.log.size, "新屏日志必须为空")
    }

    // ==================================================================
    // 4) 源码面：rebuildScreen 的 attached 分支（触发 → 回调 → return，且关态不进）
    // ==================================================================

    private val attachedBranch: String by lazy {
        val body = bodyOf("private fun rebuildScreen(w: Int, h: Int)")
        // 只取 `feed != null` 那一段：到非 attached 路径的版面解算（8 空格缩进那一处）之前。
        // 用缩进区分：新分支里也有一句 `val layout = resolveFor(w, h)`，但它缩进更深。
        body.substringAfter("if (attached != null)")
            .substringBefore("\n        val layout = resolveFor(w, h)\n        applyMetrics(layout.metrics)")
    }

    /** 新分支必须整体落在 `if (liveReflow)` 里：关态连一次版面解算都不许多做。 */
    @Test
    fun `rebuildScreen_新分支被开关守住`() {
        val branch = attachedBranch
        assertTrue(branch.contains("if (liveReflow)"), "attached 分支里必须先用 switch 守住新路径")
        assertTrue(
            branch.contains("LiveReflowPlan.viewportNeedsReflow("),
            "触发判据必须走 [LiveReflowPlan]（那是唯一可单测的那一份）",
        )
        assertTrue(
            branch.indexOf("if (liveReflow)") < branch.indexOf("viewportNeedsReflow("),
            "开关必须在外层先判，判据里的开关是二重保险而不是唯一一道",
        )
    }

    /**
     * **读数口径的另一半**（与 [reflowAttachedFeed_宽度判据读核心宽不读屏宽] 相反）：
     * 视口→宿主这一支比的是**显示中**那只屏的宽，不是核心宽。
     *
     * 换屏没补做时（宿主 resize 了、屏还挂旧宽）旧屏宽仍不等于新解算列数 ⇒ 下一次
     * `rebuildScreen` 继续再问一次，形成天然补做；若这里也读核心宽，那一格会被判成
     * 「已对齐」而退回 rows-only，旧宽的行会永久留在画面上。
     */
    @Test
    fun `rebuildScreen_触发判据读显示屏宽而不是核心宽`() {
        val branch = attachedBranch
        assertTrue(
            branch.contains("attached.screen.clientWidth"),
            "触发判据必须比当前屏宽（画面上生效的那一代），实到：$branch",
        )
        assertFalse(
            branch.contains("sinkClientWidth"),
            "触发判据不许读核心宽：会把「还挂着旧屏」那一格判成已对齐",
        )
    }

    /** 回调参数口径：列/行/字号取本次解析出的 layout，像素取内容区（与 applyMetrics 同一套读数）。 */
    @Test
    fun `rebuildScreen_回调参数是 layout 列行加内容区像素`() {
        val branch = attachedBranch
        assertTrue(
            branch.contains("notify(") &&
                branch.contains("layout.columns") &&
                branch.contains("layout.rows") &&
                branch.contains("contentWidth") &&
                branch.contains("contentHeight"),
            "回调必须传 layout.columns / layout.rows / contentWidth / contentHeight，实到：$branch",
        )
        assertTrue(
            branch.contains("layout.metrics.fontPixelSize"),
            "回调必须带本次解算出的设备字号（转屏换字号档后核心的折算分母要跟着换），实到：$branch",
        )
        assertTrue(
            branch.contains("(w - paddingLeft - paddingRight)"),
            "内容区宽必须从本次的 w 扣 padding 得到，不许混入 View 全宽",
        )
        // 内容高来自函数入口的 (h - paddingTop - paddingBottom)，不是 `height` 字段（后者是 View 全高）。
        val rebuild = bodyOf("private fun rebuildScreen(w: Int, h: Int)")
        assertTrue(
            rebuild.contains("val contentHeight = (h - paddingTop - paddingBottom)"),
            "内容高必须按传入的 h 解，别读 height 字段（含系统栏的全高）",
        )
    }

    /** 触发后直接 return：不许再落到旧的 rows-only（`resizeFeedScreen`）路径。 */
    @Test
    fun `rebuildScreen_触发后不再走 rows-only 路径`() {
        val branch = attachedBranch
        val notify = branch.indexOf("notify(")
        val resize = branch.indexOf("resizeFeedScreen(attached.screen, rows)")
        assertTrue(notify >= 0, "attached 分支里找不到回调调用")
        assertTrue(resize > notify, "旧路径必须仍在新分支之后（触发时先 return，到不了它）")
        val between = branch.substring(notify, resize)
        assertTrue(Regex("""\breturn\b""").containsMatchIn(between), "回调之后必须 return，否则两层都跑")
    }

    /** 关态旧路径逐字保留：行数仍按内容高算、仍走 `resizeFeedScreen` + `syncFeedLayout`。 */
    @Test
    fun `rebuildScreen_关态旧路径原样保留`() {
        val branch = attachedBranch
        assertTrue(branch.contains("resizeFeedScreen(attached.screen, rows)"), "rows-only 路径必须还在")
        assertTrue(branch.contains("syncFeedLayout()"), "旧路径的展平同步必须还在")
        assertTrue(
            branch.contains("val rows = if (contentHeight > 0)"),
            "行数仍按内容高 / 行高算（宽度不变时唯一会变的维度）",
        )
    }

    // ==================================================================
    // 5) 源码面：reflowAttachedFeed 的动作顺序与三支出口
    // ==================================================================

    private val reflowBody: String by lazy { bodyOf("fun reflowAttachedFeed(columns: Int, rows: Int)") }

    /** 换屏顺序写死（契约 §5 L4）：applyMetrics → 新屏 → swapScreen → screen → sync → force → invalidate。 */
    @Test
    fun `reflowAttachedFeed_换屏顺序写死`() {
        val body = reflowBody
        val seq = listOf(
            "applyMetrics(resolveFor(width, height).metrics)",
            "val fresh = ConsoleScreen(columns, Grid.MAX_LOG_DEFAULT, rows)",
            "swapScreen(fresh)",
            "screen = fresh",
            "syncFeedLayout()",
            "target.force()",
            "invalidate()",
            "return true",
        )
        var last = -1
        for (step in seq) {
            val at = body.indexOf(step)
            assertTrue(at > last, "换屏顺序错位：`$step` 不在预期位置（其实到第 ${body.indexOf(step)} 字）")
            last = at
        }
    }

    /** 宽度不符支：必须抛 `IllegalStateException`（不许静默降级）。 */
    @Test
    fun `reflowAttachedFeed_宽度不符抛错`() {
        val body = reflowBody
        assertTrue(body.contains("Step.WidthMismatch -> throw IllegalStateException"), "宽度不符必须抛 IllegalStateException")
        assertTrue(body.contains("LiveReflowPlan.swapStep("), "三支判定必须走可单测的 [LiveReflowPlan.swapStep]")
    }

    /**
     * **读数口径**（组长验收钉）：宿主→视口这一支的宽度只能读核心的 `sinkClientWidth`。
     *
     * 为什么只有源码面钉得住：[LiveReflowPlan.swapStep] 是纯函数，宽度由调用方喂进去 ——
     * 喂成旧屏宽时纯函数用例照样全绿，而真机链 `session.resizeConsole(新列)` →
     * `reflowAttachedFeed(新列)` 会当场抛 `IllegalStateException`（交接窗口里核心已是新宽、
     * `feed.screen` 还挂上一代宽）。2026-11-04 L4 验收就是这样一颗，等价用例见
     * `:render` 的 `ConsoleFeedReflowTest.resize 后换屏前核心宽与屏宽差一代且判据只认核心宽`。
     */
    @Test
    fun `reflowAttachedFeed_宽度判据读核心宽不读屏宽`() {
        val body = reflowBody
        assertTrue(
            body.contains("attached?.sinkClientWidth"),
            "换屏前置条件必须读核心折行宽（[ConsoleFeed.sinkClientWidth]），实到：$body",
        )
        assertFalse(body.contains("screen?.clientWidth"), "不许读旧屏宽当核心宽：交接窗口那一格必不等")
        assertFalse(body.contains("screen.clientWidth"), "同上：换屏成功之前屏宽永远是上一代")
    }

    /** 跳过支：返回 false 在**任何**状态修改之前（关态 / 没挂 feed 零副作用）。 */
    @Test
    fun `reflowAttachedFeed_跳过支先于一切状态修改`() {
        val body = reflowBody
        val skip = body.indexOf("Step.Skip -> return false")
        assertTrue(skip >= 0, "缺跳过支（Step.Skip -> return false）")
        for (forbidden in listOf("applyMetrics(", "swapScreen(fresh)", "screen = fresh", "force()")) {
            val at = body.indexOf(forbidden)
            assertTrue(at > skip, "`$forbidden` 必须在跳过支之后，否则关态也会动状态")
        }
    }

    /** 换屏支不许退回 rows-only，也不许顺手改字号/列数策略（那是 [ScreenLayout.resolve] 的地盘）。 */
    @Test
    fun `reflowAttachedFeed_不碰旧路径与字号策略`() {
        val body = reflowBody
        assertFalse(body.contains("resizeFeedScreen("), "换屏支不许调 rows-only 路径（那是同宽时的路径）")
        assertFalse(
            body.contains("applySourceFontMetrics("),
            "不许在换屏里改配置字号：字号换代只经 applyMetrics 的既有口径",
        )
        assertFalse(body.contains("minColumns"), "不许在换屏里改列数守护下限（那是 ScreenLayout.resolve 的地盘）")
        assertFalse(body.contains("this." + "columns ="), "不许在换屏里改列数守护下限")
    }
}
