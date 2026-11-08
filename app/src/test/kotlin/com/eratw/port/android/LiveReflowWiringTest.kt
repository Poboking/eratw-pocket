package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LIVE-REFLOW 的**壳层接线**钉子（契约 CONTRACT-LIVE-REFLOW §6 L6）。
 *
 * ## 被测形状为什么是「纯函数 + 源码面」两半
 *
 * `MainActivity` / `GameHost` 都挂在 Android 运行时上（`ComponentActivity` / `View`），
 * app 的 JVM 单测面没有 Robolectric，实例化不了；本仓对此的既有先例是
 * [OrientationLockTest]（companion 纯函数直测）+ [HotConfigChannelPinningTest]
 * （与生产闭包**逐字同形**的装置）+ [ErrorReportThrottleTest]（源码面钉子）。
 * 这里三样都用：
 * - 三段链的判据本体 [ViewportReflowChain] 直测（关态/无会话/`resizeConsole` 返回 false
 *   时**绝不**换屏、返回 true 时才换、参数同源）；
 * - 装配同源、开关守卫、摘除时机这些**只存在于接线里**的不变量走源码面；
 * - 生产那枚闭包的形状用同形装置跑一遍（[wireLikeProduction]）。
 *
 * ## 钉住的四件事（逐条对应派单判据）
 *   1. 关态：`liveReflow=false` ⇒ `reflowConsole=false`，且宿主**不注册**回调 ⇒ 重排链一步不跑；
 *   2. 开态：回调里先 `resizeConsole`，**只有它返回 true** 才 `reflowAttachedFeed(cols, rows)`，
 *      两用的列数/行数同源；
 *   3. `closed` 之后回调不做事，且关闭路径把它置空；
 *   4. 设置回传的 `displayChanged` 比较包含这颗开关，与字号档同款判据。
 *
 * 另钉一条派单点名的坑：两个开关**必须同源**（视口读一个值、核心读另一个值 ⇒
 * 「视口一直问、核心一直拒」的空转回调）。
 */
class LiveReflowWiringTest {

    // ==================================================================
    // 1. 三段链判据本体（直测）
    // ==================================================================

    @Test
    fun `关态 resizeConsole 恒 false 时绝不换屏`() {
        // 关态的真值形状：`GameSession.resizeConsole` 第一行 `if (!reflowConsole) return false`
        // （core-vm/.../GameSession.kt:335）⇒ 链子在第二步就停，`reflowAttachedFeed` 不被求值。
        var swapCalls = 0
        val outcome = ViewportReflowChain.run(
            closed = false,
            session = FakeSession(resizeResult = false),
            resize = { s -> s.resizeConsole(40, 1080L, 600L) },
            swap = { swapCalls++; true },
        )
        assertEquals(ViewportReflowChain.Outcome.RESIZE_REFUSED, outcome)
        assertEquals("resizeConsole 返回 false 时换屏一次都不许调", 0, swapCalls)
    }

    @Test
    fun `换屏抛异常那条坑 一旦误调必炸`() {
        // 为什么上面那条值得单独钉：`reflowAttachedFeed` 在「核心没先 resize」时不是返回 false，
        // 而是**抛 IllegalStateException**（CanvasConsoleView.kt:688 `WidthMismatch` 支，
        // 判据 `LiveReflowPlan.swapStep`）。它由 UI 线程的布局回调触发 ⇒ 抛出去就是
        // 「玩家转一下屏，应用没了」。这里把误调的后果显式钉成一条用例。
        val view = FakeView(sinkWidth = 70, attached = true)
        var thrown: Throwable? = null
        try {
            // 故意跳过 resizeConsole（模拟「false 也照样换屏」的错接法）：列数 40 vs sink 旧宽 70。
            view.reflowAttachedFeed(columns = 40, rows = 30)
        } catch (t: IllegalStateException) {
            thrown = t
        }
        assertTrue("误调必须炸得出 IllegalStateException，否则上面那条钉子没有代价", thrown != null)
    }

    @Test
    fun `开态 先重排再换屏 两步顺序写死`() {
        val steps = mutableListOf<String>()
        val session = FakeSession(resizeResult = true)
        val view = FakeView(sinkWidth = 70, attached = true)
        val outcome = ViewportReflowChain.run(
            closed = false,
            session = session,
            resize = { s ->
                steps += "resize"
                s.resizeConsole(80, 1920L, 600L).also { if (it) view.noteSinkWidth(80) }
            },
            swap = {
                steps += "swap"
                view.reflowAttachedFeed(80, 30)
            },
        )
        assertEquals(ViewportReflowChain.Outcome.SWAP_DONE, outcome)
        assertEquals("顺序必须是核心先重排、视口再换屏", listOf("resize", "swap"), steps)
    }

    @Test
    fun `closed 之后两步都不做`() {
        var resizeCalls = 0
        var swapCalls = 0
        val outcome = ViewportReflowChain.run(
            closed = true,
            session = FakeSession(resizeResult = true),
            resize = { s -> resizeCalls++; s.resizeConsole(40, 1L, 1L) },
            swap = { swapCalls++; true },
        )
        assertEquals(ViewportReflowChain.Outcome.CLOSED, outcome)
        assertEquals(0, resizeCalls)
        assertEquals("关闭路径之后回调不做事：换屏一次都不许调", 0, swapCalls)
    }

    @Test
    fun `会话不在时两步都不做`() {
        var swapCalls = 0
        var resizeCalls = 0
        // 实参类型点名：`session = null` 时推导不出 S（`Nothing?` 上没有 resizeConsole）。
        val outcome = ViewportReflowChain.run<FakeSession>(
            closed = false,
            session = null,
            resize = { s -> resizeCalls++; s.resizeConsole(40, 1L, 1L) },
            swap = { swapCalls++; true },
        )
        assertEquals(ViewportReflowChain.Outcome.NO_SESSION, outcome)
        assertEquals(0, resizeCalls)
        assertEquals(0, swapCalls)
    }

    @Test
    fun `重排成功但没挂 feed 时记早退`() {
        // `reflowAttachedFeed` 返回 false 的合法含义（契约 §1）：没挂 feed ⇒ 零状态变化，
        // 不许把它当成异常路径，也不许因此重排第二遍。
        val outcome = ViewportReflowChain.run(
            closed = false,
            session = FakeSession(resizeResult = true),
            resize = { s -> s.resizeConsole(40, 1L, 1L) },
            swap = { false },
        )
        assertEquals(ViewportReflowChain.Outcome.SWAP_NO_FEED, outcome)
    }

    // ==================================================================
    // 2. 生产那枚闭包的形状：同形装置跑一遍参数口径
    // ==================================================================

    @Test
    fun `开态回调参数口径一致 像素只进核心 列数行数同源`() {
        val session = FakeSession(resizeResult = true)
        val view = FakeView(sinkWidth = 70, attached = true)
        val callback = wireLikeProduction(
            closedProvider = { false },
            sessionProvider = { session },
            view = view,
        )
        callback(80, 30, 1920, 600, 33)

        assertEquals("核心收到一次重排请求", 1, session.resizeCalls.size)
        assertEquals("列数口径 = 视口解算出的新列数", 80, session.resizeCalls[0].columns)
        assertEquals(
            "像素宽高只交给核心（Int → Long，GFX-06 同一把尺）",
            1920L to 600L,
            session.resizeCalls[0].widthPx to session.resizeCalls[0].heightPx,
        )
        assertEquals(
            "本次版面的设备字号随像素一起交给核心（CLIENTWIDTH 的折算分母）",
            33,
            session.resizeCalls[0].deviceFontPixelSize,
        )
        assertEquals("视口换屏只拿列数/行数", listOf(80 to 30), view.swapCalls)
    }

    @Test
    fun `开态同宽的第二发不再换屏`() {
        // 验收判据「同尺寸重复布局不触发」的链上形状：宽度未变时 `resizeConsole` 返回 false
        // （`LineBufferConsoleSink.resize` 的 `newWidth == clientWidth` 支），于是换屏不被调。
        val session = FakeSession(resizeResult = false)
        val view = FakeView(sinkWidth = 80, attached = true)
        val callback = wireLikeProduction({ false }, { session }, view)
        // 位置实参：函数**类型**上的参数名只给阅读用，Kotlin 不许在调用函数值时用具名实参。
        callback(80, 30, 1920, 640, 33)
        assertEquals(1, session.resizeCalls.size)
        assertTrue("同宽那一发（resizeConsole 返回 false）不许换屏", view.swapCalls.isEmpty())
    }

    @Test
    fun `收口之后回调不做事`() {
        val session = FakeSession(resizeResult = true)
        val view = FakeView(sinkWidth = 70, attached = true)
        var closed = false
        val callback = wireLikeProduction({ closed }, { session }, view)
        callback(80, 30, 1920, 600, 33)
        assertEquals(1, view.swapCalls.size)

        closed = true
        callback(90, 31, 2000, 700, 30)
        assertEquals("closed 之后一条都不许多做", listOf(80 to 30), view.swapCalls)
        assertEquals(1, session.resizeCalls.size)
    }

    // ==================================================================
    // 3. 源码面：同源 · 关态零新增 · 摘除
    // ==================================================================

    @Test
    fun `装配期只读一次 UiPrefs 且两处喂同一个值`() {
        val shell = codeLines("MainActivity.kt")
        assertEquals(
            "appliedLiveReflow 只在装配期赋值一次（多处读 = 设置页能在两次读之间改值）",
            1,
            shell.countOccurrences("appliedLiveReflow = UiPrefs.liveReflow(this)"),
        )
        assertEquals(
            "视口（CanvasConsoleView）与核心（GameHost）必须拿同一个 appliedLiveReflow",
            2,
            shell.countOccurrences("liveReflow = appliedLiveReflow"),
        )
        assertEquals(
            "不许把 UiPrefs 的现读值直接塞进构造函数（那就绕过了同源）",
            0,
            shell.countOccurrences("liveReflow = UiPrefs.liveReflow(this)"),
        )
    }

    @Test
    fun `核心侧不自己摸偏好 reflowConsole 取本宿主读数`() {
        val host = codeLines("GameHost.kt")
        assertEquals(
            "GameHost 不许自己读 UiPrefs.liveReflow：与视口分叉就是「视口问、核心拒」的空转回调",
            0,
            host.countOccurrences("UiPrefs.liveReflow"),
        )
        assertTrue(
            "GameSession 的 reflowConsole 必须由构造参数 liveReflow 供给",
            host.contains("reflowConsole = liveReflow,"),
        )
        assertTrue(
            "GameHost 必须有这颗开关的构造参数（缺省关 = 既有路径逐字不变）",
            host.contains("private val liveReflow: Boolean = false,"),
        )
    }

    @Test
    fun `关态宿主不注册回调`() {
        val host = codeLines("GameHost.kt")
        val lines = host.lines()
        val arm = lines.indexOfFirst {
            it.trim() == "view.onViewportChange = { cols, rows, widthPx, heightPx, fontPx ->"
        }
        assertTrue("生产接线必须存在（找不到那枚闭包 = 接线被删）", arm >= 0)
        // 守卫必须紧贴注册点：往上找最近的非空代码行就是 `if (liveReflow) {`。
        var i = arm - 1
        while (i >= 0 && lines[i].isBlank()) i--
        assertEquals("关态连闭包都不分配：注册点必须包在 if (liveReflow) 里", "if (liveReflow) {", lines[i].trim())
    }

    @Test
    fun `关闭路径把回调置空`() {
        val host = codeLines("GameHost.kt")
        val disarm = host.indexOf("view.onViewportChange = null")
        assertTrue("退出清场必须摘掉视口回调", disarm >= 0)
        val graphics = host.indexOf("view.detachGraphics()", disarm)
        assertTrue(
            "摘回调与摘渲染桥同一发 UI 线程清场，且摘在换屏动作之前",
            graphics > disarm,
        )
        assertTrue(
            "整发清场仍按线程分派（VM 线程走到这里时不许直接摸 View）",
            host.contains("if (Looper.myLooper() == Looper.getMainLooper()) clear.run() else view.post(clear)"),
        )
    }

    @Test
    fun `重排链只由那枚回调驱动`() {
        val host = codeLines("GameHost.kt")
        assertEquals(
            "reflowAttachedFeed 只许出现在回调的 swap 那一步（别处调 = 绕过了「先重排」这条闸）",
            1,
            host.countOccurrences("view.reflowAttachedFeed("),
        )
        assertEquals(
            "resizeConsole 只许出现在回调的 resize 那一步（尤其不许从 VM 步进线程调）",
            1,
            host.countOccurrences("s.resizeConsole("),
        )
        assertTrue(
            "两步都挂在 ViewportReflowChain.run 上（判据本体只有一个）",
            host.contains("ViewportReflowChain.run("),
        )
    }

    @Test
    fun `壳层方向分支不被这颗开关影响`() {
        val shell = codeLines("MainActivity.kt")
        for (line in shell.lines().filter { it.contains("requestedOrientation =") }) {
            assertFalse("竖屏/横屏壳的分支选择只认方向偏好，不许掺重排开关：$line", line.contains("LiveReflow"))
        }
        assertTrue("isPortrait 仍只由 portraitNow 决定", shell.contains("isPortrait = portraitNow,"))
        assertFalse(
            "isPortrait 不许再与任何装配参数合流",
            shell.contains("isPortrait = portraitNow &&"),
        )
    }

    @Test
    fun `重挂渲染桥的换屏兜底只在开态且宽度不等时进`() {
        // `ConsoleFeed` 的构造是 `require(sink.clientWidth == screen.clientWidth)`
        // （render/.../ConsoleFeed.kt:131），非等即抛 —— 这条兜底就是把「旧屏 + 新核心宽」
        // 那一跳挡住，所以判据本身要钉死。
        assertFalse(
            "关态：核心折行宽在会话期冻结，永不分叉 ⇒ 分支根本不进（既有路径逐字不变）",
            ViewportReflowChain.needsScreenAlignOnReattach(
                liveReflow = false, screenColumns = 70, sinkColumns = 80,
            ),
        )
        assertFalse(
            "开态同宽：无需换屏",
            ViewportReflowChain.needsScreenAlignOnReattach(
                liveReflow = true, screenColumns = 80, sinkColumns = 80,
            ),
        )
        assertTrue(
            "开态分叉：按核心宽换空屏",
            ViewportReflowChain.needsScreenAlignOnReattach(
                liveReflow = true, screenColumns = 70, sinkColumns = 80,
            ),
        )
        assertFalse(
            "sink 处于「不折行」形态（<= 0）时不许换：ConsoleScreen 自己 require(clientWidth >= 1)",
            ViewportReflowChain.needsScreenAlignOnReattach(
                liveReflow = true, screenColumns = 70, sinkColumns = 0,
            ),
        )
    }

    @Test
    fun `换屏成功后 lastScreen 与重挂桥都跟上新宽`() {
        val host = codeLines("GameHost.kt")
        assertTrue(
            "换屏成功这一支必须把 lastScreen 跟到视图当前的屏（backToTitle 拿它重挂桥）",
            host.contains("lastScreen = view.consoleScreen() ?: lastScreen"),
        )
        assertTrue(
            "重挂渲染桥走兜底闸",
            host.contains("ViewportReflowChain.needsScreenAlignOnReattach("),
        )
        assertTrue(
            "兜底换出来的屏交给 ConsoleFeed（不是原来那份旧宽的）",
            host.contains("feedScreen,"),
        )
    }

    // ==================================================================
    // 4. displayChanged：与字号档同款判据
    // ==================================================================

    @Test
    fun `三项都没改时不必重开`() {
        assertFalse(
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = true, appliedScreenFit = true,
                nowLiveReflow = false, appliedLiveReflow = false,
            ),
        )
    }

    @Test
    fun `只改 LIVE-REFLOW 也算显示设置变更`() {
        assertTrue(
            "开：玩家从设置页打开重排开关回来，必须走征求重开那一条路",
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = true, appliedScreenFit = true,
                nowLiveReflow = true, appliedLiveReflow = false,
            ),
        )
        assertTrue(
            "关：反向同样要问（否则这一局永远带着旧开关跑）",
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = true, appliedScreenFit = true,
                nowLiveReflow = false, appliedLiveReflow = true,
            ),
        )
    }

    @Test
    fun `字号档与守护开关的既有判据不变`() {
        assertTrue(
            MainActivity.displaySettingChanged(
                nowFontColumns = 110, appliedFontColumns = 136,
                nowScreenFit = true, appliedScreenFit = true,
                nowLiveReflow = false, appliedLiveReflow = false,
            ),
        )
        assertTrue(
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = false, appliedScreenFit = true,
                nowLiveReflow = false, appliedLiveReflow = false,
            ),
        )
    }

    @Test
    fun `进设置页的快照也带上这颗开关`() {
        val shell = codeLines("MainActivity.kt")
        assertEquals(
            "openSettings 必须记 liveReflow 的进页快照（否则选了「下次生效」的玩家每次返回都被拦）",
            1,
            shell.countOccurrences("settingsEntryLiveReflow = UiPrefs.liveReflow(this)"),
        )
        assertEquals(
            "displayChanged 与 displayTouched 两处比较都带 liveReflow（同一口径）",
            2,
            shell.countOccurrences("nowLiveReflow = UiPrefs.liveReflow(this)"),
        )
    }

    // ==================================================================
    // 装置
    // ==================================================================

    /**
     * 与 `GameHost.attachConsoleFeed` 里那枚闭包**逐字同形**的装置。
     *
     * 口径同 [HotConfigChannelPinningTest]：AGP 桩类造不出真 `CanvasConsoleView`/`GameSession`，
     * 于是把生产 lambda 体原样搬过来（`closed` / `session` 改成效能等价的提供器，
     * `resizeConsole` 与 `reflowAttachedFeed` 换成记账假件），逐字形状再由
     * 「重排链只由那枚回调驱动」那条源码面钉子钉住。
     */
    private fun wireLikeProduction(
        closedProvider: () -> Boolean,
        sessionProvider: () -> FakeSession?,
        view: FakeView,
    ): (columns: Int, rows: Int, widthPx: Int, heightPx: Int, fontPx: Int) -> Unit =
        { cols, rows, widthPx, heightPx, fontPx ->
        ViewportReflowChain.run(
            closed = closedProvider(),
            session = sessionProvider(),
            resize = { s ->
                val reflowed = s.resizeConsole(cols, widthPx.toLong(), heightPx.toLong(), fontPx)
                // 复刻「核心 sink 已按新列数折行」这件事：真实链路里 `LineBufferConsoleSink.clientWidth`
                // 在 `resize` 里被改掉，紧随其后的 `reflowAttachedFeed` 才比得上（比不上就是那条
                // IllegalStateException）。假件不把这条边接上，换屏那一支就成了永远抛的假红。
                if (reflowed) view.noteSinkWidth(cols)
                reflowed
            },
            swap = { view.reflowAttachedFeed(cols, rows) },
        )
    }

    /** 假会话：只记「被问了什么」并按脚本返回重排结果（对应 `GameSession.resizeConsole`）。 */
    private class FakeSession(private val resizeResult: Boolean) {
        data class ResizeCall(
            val columns: Int,
            val widthPx: Long,
            val heightPx: Long,
            val deviceFontPixelSize: Int,
        )

        val resizeCalls = mutableListOf<ResizeCall>()

        fun resizeConsole(
            columns: Int,
            widthPx: Long,
            heightPx: Long,
            deviceFontPixelSize: Int = 0,
        ): Boolean {
            resizeCalls += ResizeCall(columns, widthPx, heightPx, deviceFontPixelSize)
            return resizeResult
        }
    }

    /**
     * 假视口：复刻 `CanvasConsoleView.reflowAttachedFeed` 的**判据**（列数与 sink 当前宽不符就抛），
     * 好让「false 也换屏」这类错接法在测试里有真实代价，而不是只靠人眼 review。
     */
    private class FakeView(var sinkWidth: Int, private val attached: Boolean) {
        val swapCalls = mutableListOf<Pair<Int, Int>>()

        /** 核心重排成功之后 sink 的折行宽就是新列数（真实链路由 `LineBufferConsoleSink.resize` 做的）。 */
        fun noteSinkWidth(columns: Int) {
            sinkWidth = columns
        }

        fun reflowAttachedFeed(columns: Int, rows: Int): Boolean {
            check(columns == sinkWidth) {
                "reflowAttachedFeed 要求核心先 resize：columns=$columns，sink.clientWidth=$sinkWidth"
            }
            swapCalls += columns to rows
            return attached
        }
    }

    private fun String.countOccurrences(needle: String): Int {
        var index = 0
        var count = 0
        while (true) {
            val at = indexOf(needle, index)
            if (at < 0) return count
            count++
            index = at + needle.length
        }
    }

    // ------------------------------------------------------------------
    // 取源码：工作目录随 gradle 入口变（口径同 StrictModeGuardTest / StallSentinelTest）
    // ------------------------------------------------------------------

    private val moduleRootCandidates = listOf(File("."), File("../app"), File("app"))

    private fun moduleRoot(): File =
        moduleRootCandidates.firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin（试了 ${moduleRootCandidates.joinToString { it.path }}）")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：源码面钉子判的是接线，不是文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")
}
