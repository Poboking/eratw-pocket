package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [CanvasConsoleView.tap] 返回 [TapOutcome] 的**源码面**回归闸（C4 / HOST-06 收尾）。
 *
 * ## 为什么是源码面
 *
 * [CanvasConsoleView] 是 Android `View`：本模块只有纯 JVM 单测（无 Robolectric，
 * `render-canvas/build.gradle.kts` 只引 `kotlin("test")` + `junit-jupiter`），**实例化不了**
 * [TapOutcome] 的两半也得先有 `ConsoleScreen` / `Viewport` 才能算出来。同
 * [CanvasMemoryReleaseAuditTest] / [SessionReleaseFieldAuditTest] 的口径：本夹具**不冒充**
 * 真机手势读数，只钉三件可判的事 ——
 *
 * 1. 返回类型带状态位（退化成裸 `String?` 的那一版，调用方**拿不到**「刚才是否未贴底」，
 *    滚动看历史时点空白会多补一次回车，`MainWindow.cs:799-820`）；
 * 2. **顺序**：命中按贴底前的视图算（`:800` 在 `:803` 之前），且贴底无论如何都发生；
 * 3. 无屏 early return 里状态位是 false（没有视口就没有「刚才未贴底」这件事）。
 *
 * 真机上的点击行为仍是 TODO(verify)：本机 `adb devices` 为空。
 */
class CanvasTapOutcomeAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；与 CanvasMemoryReleaseAuditTest 同一把尺）。 */
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

    private val tapBody: String by lazy { bodyOf("fun tap(") }

    /** 状态位必须真的回传：`tap` 的返回类型就是 [TapOutcome]，不是裸 `String?`。 */
    @Test
    fun `tap 返回带状态位的 TapOutcome`() {
        assertTrue(
            Regex("""fun tap\([^)]*\)\s*:\s*TapOutcome\s*\{""").containsMatchIn(src),
            "tap 必须返回 TapOutcome（命中文本 + 本击是否回贴底），否则调用方复刻不了 MainWindow.cs:808 的 if (!flag)",
        )
        assertFalse(
            Regex("""fun tap\([^)]*\)\s*:\s*String\?""").containsMatchIn(src),
            "tap 不得退回裸 String?：那样 snappedToBottom 就丢了（C4 的老账）",
        )
        assertTrue(
            Regex("""data class TapOutcome\(val hit: String\?, val snappedToBottom: Boolean\)""")
                .containsMatchIn(src),
            "TapOutcome 的字段就是 hit + snappedToBottom，别换成别的判据",
        )
    }

    /** 顺序：命中先按贴底前视图取，再回贴底（`MainWindow.cs:800` 在 `:801-805` 之前）。 */
    @Test
    fun `命中在贴底之前算`() {
        val hit = tapBody.indexOf("hitOnCurrentView")
        val snap = tapBody.indexOf("snapToBottomForTap")
        assertTrue(hit >= 0, "tap 里没有命中断（hitOnCurrentView）")
        assertTrue(snap >= 0, "tap 里没有贴底半段（snapToBottomForTap）")
        assertTrue(hit < snap, "命中必须按**贴底前**的视图算：MainWindow.cs:800 先于 :803")
    }

    /** 贴底状态位取的是 `snapToBottomForTap` 的返回值，并原样进 [TapOutcome]。 */
    @Test
    fun `贴底状态位来自 snapToBottomForTap 的返回值`() {
        assertTrue(
            Regex("""val snapped = if \(noFocus\) false else console\.viewport\.snapToBottomForTap\(\)""")
                .containsMatchIn(tapBody),
            "贴底结果要落到具名状态位；NF 等待必须整段跳过贴底" +
                "（MainWindow.cs:949，否则上翻看历史会被一下拽回底部）",
        )
        val refreshed = tapBody.substringAfter("if (snapped)")
        assertTrue(
            refreshed.contains("refresh()") && refreshed.contains("requestRedraw()"),
            "真从非底部弹回底部时必须 refresh + 置脏（滚动位置 revision 看不见）",
        )
        assertTrue(
            tapBody.contains("TapOutcome(hit = submitted, snappedToBottom = snapped)"),
            "两个字段都要原样回传：命中文本来自贴底前视图，状态位来自 snapToBottomForTap",
        )
    }

    /**
     * 无屏 early return：`snappedToBottom = false`。
     *
     * 没有 `feed`/`screen` 时连视口都没有，谈不上「刚才未贴底」；给 true 会让调用方
     * 误以为这一击只是回贴底、于是连正常回车都不补（装配层在首帧前恰好吃这一下）。
     */
    @Test
    fun `无屏 early return 的状态位为 false`() {
        val early = tapBody.substringBefore("hitOnCurrentView")
        assertTrue(
            early.contains("TapOutcome(hit = null, snappedToBottom = false)"),
            "无屏 early return 必须是 hit=null / snappedToBottom=false",
        )
    }

    /**
     * 4.981 NF 族（`TINPUTNF` / `TINPUTSNF`）在两处强制贴底入口都要被放过。
     *
     * C# 侧 `WaitInputNoFocus` 置 `ConsoleState.WaitInputNoFocus`
     * （`EmueraConsole.cs:818`），宿主用 `IsWaitInputNoFocusState`（`:357`）在
     * 点击（`MainWindow.cs:949`）与按键（`:1591`）两处跳过 `Value = Maximum`。
     * 本夹具是源码面闸（本模块无法实例化 Android View），只钉「两条入口都认
     * 这个位、缺省仍是基线行为」，真机观测属 TODO(verify)。
     */
    @Test
    fun `NF 等待跳过点击与提交两处强制贴底`() {
        assertTrue(
            Regex("""fun tap\([^)]*noFocus: Boolean = false[^)]*\)""").containsMatchIn(src),
            "tap 必须接受 noFocus，缺省 false = 1.824 基线路径逐字不变",
        )
        assertTrue(
            tapBody.contains("if (noFocus) false else"),
            "点击半边必须由 noFocus 抑制贴底（MainWindow.cs:949）",
        )
        val submit = bodyOf("fun snapToBottomForSubmit(")
        assertTrue(
            submit.contains("!noFocus && console.viewport.snapToBottomForTap()"),
            "键入/选项提交半边同样由 noFocus 短路掉贴底动作（MainWindow.cs:1591）",
        )
        assertTrue(
            Regex("""fun snapToBottomForSubmit\(noFocus: Boolean = false\)""").containsMatchIn(src),
            "提交入口的 noFocus 也必须缺省 false，既有调用方零行为变化",
        )
    }
}
