package com.eratw.port.android

import com.eratw.port.core.host.LineBufferConsoleSink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏 HUD 尾窗改走窄只读口 `GameHost.consoleTail` 的**行为面 + 接线哨兵**（L31
 * PORTRAIT-CONSOLE-TAIL）。
 *
 * ## 修的是什么
 *
 * 生产 HUD 原先经 `MainActivity.portraitScreenLines()` → `host?.debugSession()?.console`
 * 直接拿整个可变 `GameSession`：这条路上 HUD 只需要「尾 N 行的 serial + 纯文本」，却握住了
 * `inputSink` / `store` / `close()` 等可变面 —— 任何未来调用方都能顺手提交指令，违反竖屏
 * 设计稿 §0-12「HUD 只显示、零指令提交」。现在 HUD 只调 `GameHost.consoleTail(limit)`，
 * 拿回不可变快照；`debugSession()` 原样保留给 DevTools / `ModImportSessionChannel`。
 *
 * ## 为什么行为判据挂在 `GameHost` companion
 *
 * `GameHost` 是 Android 壳，app 的 JVM 单测面实例化不了它（同 `PortraitDisplayChangeTest`
 * 对 `MainActivity` 的口径）。尾窗拼装抽成 companion 纯函数 [GameHost.consoleTailOf]，
 * 这里用真 `LineBufferConsoleSink` 钉住 `takeLast` / 行序 / 空会话 / limit 边界；
 * 生产方法 [GameHost.consoleTail] 只做 `session?.console` 取值后委托，源码形状闸另钉。
 *
 * ## 不测的
 *
 * 真机上的 HUD 每 250ms 轮询出数据需要设备（`PortraitShellContent` 的
 * `LaunchedEffect` 在 Compose 运行时里）；这里只钉快照语义与接线形状。
 */
class PortraitConsoleTailTest {

    /** 与 `PortraitDisplayChangeTest.sourceOf` 同款：两种工作目录都能读到大仓源码。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** 去掉注释行，避免 KDoc 里的举例串被当成接线证据（同 `MouseButtonWireGateTest.codeOf`）。 */
    private fun codeOf(name: String): String = sourceOf(name).lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }.joinToString("\n")

    /** 取某个函数的函数体（本文件的靶函数没有嵌套 `}`，按 4 空格缩进的收尾括号截断）。 */
    private fun bodyOf(source: String, signature: String): String {
        val match = Regex(signature + """\s*\{([\s\S]*?)\n    \}""").find(source)
            ?: error("找不到 $signature 的函数体")
        return match.groupValues[1]
    }

    /** 真 sink：一行一次 `printSingleLine`，行内容与 serial 都由 core 侧分配。 */
    private fun sinkWithLines(count: Int): LineBufferConsoleSink {
        val sink = LineBufferConsoleSink(clientWidth = 40)
        repeat(count) { index -> sink.printSingleLine("tail-line-${index + 1}") }
        return sink
    }

    // ==================================================================
    // 行为面：尾窗行序 / 上限 / 空表
    // ==================================================================

    @Test
    fun `consoleTail 只取尾部 limit 行并保持显示序`() {
        val sink = sinkWithLines(5)
        val expected = sink.lines().takeLast(3)
        val actual = GameHost.consoleTailOf(sink, 3)

        assertEquals("尾窗行数 = limit", 3, actual.size)
        assertEquals(
            "serial 必须是最后三行的显示序（最旧在前）",
            expected.map { it.serial },
            actual.map { it.first },
        )
        assertEquals(
            "行文本走 textOf 口径",
            listOf("tail-line-3", "tail-line-4", "tail-line-5"),
            actual.map { it.second },
        )
        assertTrue(
            "显示序必须自最旧到最新（takeLast 而不是 take）",
            actual.zipWithNext().all { (a, b) -> a.first < b.first },
        )
    }

    @Test
    fun `consoleTail 对零负 limit 与无会话返回空表`() {
        val sink = sinkWithLines(4)
        assertTrue("limit = 0 明确返回空表", GameHost.consoleTailOf(sink, 0).isEmpty())
        assertTrue(
            "limit < 0 明确返回空表（不把负数交给 takeLast 猜）",
            GameHost.consoleTailOf(sink, -7).isEmpty(),
        )
        assertTrue("无会话（null sink）返回空表", GameHost.consoleTailOf(null, 8).isEmpty())
        assertTrue(
            "空会话（尚无显示行）返回空表",
            GameHost.consoleTailOf(LineBufferConsoleSink(clientWidth = 40), 8).isEmpty(),
        )
    }

    @Test
    fun `consoleTail limit 大于现存行数时返回全部`() {
        val sink = sinkWithLines(2)
        val actual = GameHost.consoleTailOf(sink, 160)
        assertEquals("不足 limit 时取全部", 2, actual.size)
        assertEquals(listOf("tail-line-1", "tail-line-2"), actual.map { it.second })
    }

    // ==================================================================
    // 接线哨兵：HUD 只走窄口，debugSession 留给 ModImport / DevTools
    // ==================================================================

    @Test
    fun `竖屏屏行快照只调 consoleTail 不再碰 debugSession`() {
        val main = codeOf("MainActivity.kt")
        val body = bodyOf(
            main,
            """private fun portraitScreenLines\(\): List<Pair<Long, String>>""",
        )
        assertTrue(
            "portraitScreenLines 必须只调 GameHost 的窄只读口 consoleTail",
            body.contains("host?.consoleTail(PORTRAIT_HUD_TAIL_LINES) ?: emptyList()"),
        )
        assertFalse(
            "portraitScreenLines 不许再经 debugSession 拿整个可变 GameSession（HUD 零指令提交铁律）",
            body.contains("debugSession"),
        )
        assertFalse(
            "生产 HUD 尾窗路径不许再出现 debugSession()?.console",
            main.contains("debugSession()?.console"),
        )
    }

    @Test
    fun `debugSession 保留且 ModImportSessionChannel 两处装配点零改`() {
        val host = codeOf("GameHost.kt")
        assertTrue(
            "窄只读口的签名必须是 limit 入参 + serial to 文本快照",
            Regex("""fun consoleTail\(limit: Int\): List<Pair<Long, String>>""").containsMatchIn(host),
        )
        assertTrue(
            "consoleTail 必须委托 consoleTailOf，单测才和生产走同一份尾窗口径",
            host.contains("consoleTailOf(session?.console, limit)"),
        )
        assertTrue(
            "debugSession 必须原样保留（DevTools / ModImport 还要完整会话）",
            host.contains("fun debugSession(): GameSession? = session"),
        )
        val main = codeOf("MainActivity.kt")
        assertEquals(
            "ModImportSessionChannel 的两处既有装配点不许动",
            2,
            Regex("""ModImportSessionChannel\.install\(this\) \{ host\?\.debugSession\(\) \}""")
                .findAll(main).count(),
        )
    }
}
