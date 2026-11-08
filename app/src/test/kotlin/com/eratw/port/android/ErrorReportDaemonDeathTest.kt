package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FEEL1/L2：守护线程非预期死亡的**留痕环**（`ErrorReportCollector.noteDaemonDeath` /
 * `drainDaemonDeathLines`），结「后台线程静默死、报告里一个字都没有」的病。
 *
 * 与 [SingleDaemonSlot.drainLastFailure] 的取一次即清同一语义：报告侧每生成一份就 drain 一次，
 * 同一条死只出声一次、不逐份重播。环容量写死（[ErrorReportCollector.MAX_DAEMON_DEATH_LINES]），
 * 满则丢最旧——一条抖线反复起落不许把这节撑成无界表（§0 第 10 条）。
 *
 * 全部走 `ErrorReportCollector` 这枚进程级单例，纯 JVM 可跑（不碰 Android：note 只压字符串、
 * drain 只动 ArrayDeque）。用例开头先 drain 一次清场，避免同 JVM 内其它用例的残留串味。
 */
class ErrorReportDaemonDeathTest {

    @Before
    fun clearResidue() {
        // 环是进程级单例状态：每条用例起手排空，拿到确定的起点。
        ErrorReportCollector.drainDaemonDeathLines()
    }

    @Test
    fun `记一笔死因后_drain_出声一次并清空`() {
        val boom = RuntimeException("侧通道读崩了")
        ErrorReportCollector.noteDaemonDeath("eratw-devtools", boom)

        val first = ErrorReportCollector.drainDaemonDeathLines()
        assertEquals(2, first.size)
        assertTrue(first[0], first[0].startsWith("后台守护线程非预期死亡"))
        assertTrue(first[1], first[1].contains("[eratw-devtools]"))
        assertTrue(first[1], first[1].contains("RuntimeException：侧通道读崩了"))

        // 取一次即清：第二次再 drain 必须空表（不逐份重播）。
        assertEquals(emptyList<String>(), ErrorReportCollector.drainDaemonDeathLines())
    }

    @Test
    fun `没死过时整节不写`() {
        assertEquals(emptyList<String>(), ErrorReportCollector.drainDaemonDeathLines())
    }

    @Test
    fun `null_死因不占格`() {
        ErrorReportCollector.noteDaemonDeath("eratw-devtools", null)
        assertEquals(emptyList<String>(), ErrorReportCollector.drainDaemonDeathLines())
    }

    @Test
    fun `环满丢最旧_容量硬顶`() {
        val cap = ErrorReportCollector.MAX_DAEMON_DEATH_LINES
        // 投 cap + 3 条：环只留最新 cap 条，最旧的三条被挤出。
        (1..cap + 3).forEach { ErrorReportCollector.noteDaemonDeath("eratw-asset", RuntimeException("boom$it")) }

        val lines = ErrorReportCollector.drainDaemonDeathLines()
        assertEquals("1 行小节头 + 至多 cap 行留痕", cap + 1, lines.size)
        val kept = lines.drop(1)
        // 保留的必须是最新 cap 条（boom4..boom7），最早的 boom1..boom3 被丢。
        assertTrue(kept.joinToString("|"), kept.none { it.contains("boom1") })
        assertTrue(kept.joinToString("|"), kept.none { it.contains("boom2") })
        assertTrue(kept.joinToString("|"), kept.none { it.contains("boom3") })
        assertTrue(kept.last(), kept.last().contains("boom${cap + 3}"))
    }

    @Test
    fun `daemonDeathText_压成一行且含栈顶帧`() {
        val e = IllegalStateException("图形预取崩了")
        val line = ErrorReportCollector.daemonDeathText("eratw-graphics", e)
        assertTrue(line, line.startsWith("[eratw-graphics] IllegalStateException：图形预取崩了"))
        assertTrue(line, line.contains("栈顶"))
    }

    @Test
    fun `message_为空时不带悬空的冒号`() {
        val e = IllegalStateException()
        val line = ErrorReportCollector.daemonDeathText("eratw-x", e)
        assertTrue(line, line.startsWith("[eratw-x] IllegalStateException"))
        // 空 message 不许渲染成 "IllegalStateException："（类名后紧跟冒号 = 想拼 null 的痕迹）。
        assertTrue(line, !line.contains("IllegalStateException："))
    }
}
