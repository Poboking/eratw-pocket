package com.eratw.port.render.canvas

import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W12 · AUD-h7-release 残余 —— `CanvasConsoleView.releaseSession` 的**逐字段判性**回归闸。
 *
 * ## 为什么是源码面断言，而不是驻留读数
 *
 * 该行的出口判据写的是「会话销毁前后驻留读数对比（DevTools 驻留口或桌面等效量测）」，
 * 但 `CanvasConsoleView` 是 Android `View`，本模块只有纯 JVM 单测（无 Robolectric，
 * `render-canvas/build.gradle.kts` 只引 `kotlin("test")` + `junit-jupiter`），**实例化不了**；
 * 本机 `adb devices` 为空 ⇒ 真机堆转储也取不到。故本夹具**不冒充**驻留读数，只钉两件可判的事：
 *
 * 1. `releaseSession` 必须切断的字段一个都不能漏（含本波补的 `pending`）；
 * 2. 判为「有意常驻」的三个字体度量缓存**不得**在这里被清（清掉是明确的性能倒退）。
 *
 * ## 逐字段判性（2026-11-01，W12）
 *
 * | 字段 | 判性 | 依据 |
 * |---|---|---|
 * | `pending` | **随会话释放** | 最后一次 `submitSnapshot` 的 `List<RenderLine>`。滞留之外还有正确性问题：`rebuildScreen` 会拿它给**新建的** `ConsoleScreen` 补交首帧（`CanvasConsoleView.kt:1445`），旧会话的行会画进新会话 |
 * | `typefaces` | 有意常驻 | 族名 → `Typeface`，`createFromAsset` 解码一次即复用；进程级、不含会话状态 |
 * | `baselines` | 有意常驻 | 族名 → 基线偏移，与 `paint.textSize` 绑定；`applyMetrics` 改字号时已整表作废 |
 * | `metricsByFont` | 有意常驻 | 字形度量（键含字号）；同样由 `applyMetrics` 整表作废；清空 = 下一局每字形重新量一遍 |
 *
 * 三者键都是字体族名/字号，不含 `GameSession` / `ConsoleScreen` / 任何位图引用 ⇒ 留着不钉会话。
 */
class SessionReleaseFieldAuditTest {

    private val src: String by lazy {
        Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/render/canvas/CanvasConsoleView.kt")
            .readText()
    }

    /** 取某个 `fun xxx(` 的函数体（按大括号配平；注释里没有裸大括号，配平可靠）。 */
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

    /**
     * 剥掉行注释再断言 —— 否则「把赋值注释掉」会让闸误判为通过（本夹具的负向对拍就是这么暴露的：
     * `// pending = emptyList()` 仍被 `\bpending\s*=` 命中）。本文件函数体里没有含 `//` 的字符串字面量。
     */
    private fun stripComments(body: String): String =
        body.lineSequence().joinToString("\n") { line ->
            val i = line.indexOf("//")
            if (i >= 0) line.substring(0, i) else line
        }

    /** 本函数**必须**切断的引用（L13 稳定性波已落的 13 项 + `pending` + 首帧收尾回调）。 */
    private val mustRelease = listOf(
        "feed", "screen", "pending", "graphicsLayer", "frameSource",
        "onFirstContentFrame",
        "screenSurface", "screenSurfaceCanvas", "surfaceWidth", "surfaceHeight",
        "backing", "backingCanvas",
    )

    /** 判为「有意常驻」的进程级字体度量缓存 —— 在这里清掉是性能倒退，必须**不**出现。 */
    private val intentionalResident = listOf("typefaces", "baselines", "metricsByFont")

    @Test
    fun `releaseSession 切断全部会话引用（含 pending）`() {
        val body = bodyOf("fun releaseSession()")
        val missing = mustRelease.filter { !Regex("""\b${Regex.escape(it)}\s*=""").containsMatchIn(body) }
        assertEquals(
            emptyList(), missing,
            "releaseSession 漏切断：$missing —— 每一处漏掉都是把死会话的对象钉在 View 上",
        )
    }

    @Test
    fun `releaseSession 必须清 pending（本波 AUD-h7-release 残余）`() {
        val body = bodyOf("fun releaseSession()")
        assertTrue(
            Regex("""\bpending\s*=\s*emptyList\(\)""").containsMatchIn(body),
            "pending 是最后一次快照的 List<RenderLine>：既滞留，又会被 rebuildScreen 回放进新会话",
        )
    }

    @Test
    fun `releaseSession 不清进程级字体度量缓存`() {
        val body = bodyOf("fun releaseSession()")
        val cleared = intentionalResident.filter {
            Regex("""\b${Regex.escape(it)}\s*(=|\.clear\()""").containsMatchIn(body)
        }
        assertEquals(
            emptyList(), cleared,
            "$cleared 是进程级字体度量缓存（键为族名/字号、不含会话状态），" +
                "在会话销毁时清掉只会让下一局重新解码字体/重新量每个字形 —— 判性见本文件类头与 releaseSession 的 KDoc",
        )
    }

    @Test
    fun `三个常驻字段的判性写在 releaseSession 的 KDoc 里`() {
        // 判性必须留在代码旁，不能只留在 BOARD：改判的人会先读到这段。
        val kdocStart = src.lastIndexOf("/**", src.indexOf("fun releaseSession()"))
        assertTrue(kdocStart >= 0, "releaseSession 缺 KDoc")
        val kdoc = src.substring(kdocStart, src.indexOf("fun releaseSession()"))
        for (f in intentionalResident) {
            assertTrue(kdoc.contains(f), "releaseSession 的 KDoc 必须点名 $f 的「有意常驻」判性")
        }
        assertTrue(kdoc.contains("有意常驻"), "KDoc 必须写明「有意常驻」这一判性档")
    }

    @Test
    fun `onDetachedFromWindow 不清 pending（再附着要靠它回放）`() {
        // detach 是**暂时**的（旋转 / 切后台），rebuildScreen 要拿 pending 给新尺寸的
        // ConsoleScreen 补交快照；把它和「会话销毁」混为一谈会让旋转后白屏。
        val body = bodyOf("override fun onDetachedFromWindow()")
        assertTrue(
            !Regex("""\bpending\s*=""").containsMatchIn(body),
            "onDetachedFromWindow 不得清 pending —— 只有 releaseSession（会话销毁）才清",
        )
    }
}
