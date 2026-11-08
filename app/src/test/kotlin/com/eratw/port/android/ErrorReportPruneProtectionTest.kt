package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReportUpload
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * FEEL1/L2：报告目录剪枝的**两桶两道闸**——结「未发件被自己的滚动剪枝删掉」的病。
 *
 * 旧写法 `KEEP_REPORTS` 不看「发过没有」，按 mtime 删最旧；断网玩家那几份**还没发出去**的
 * 报告往往正是最旧的，于是被永久丢掉。本波把预算拆成独立两桶：
 * - 第一道闸（[ErrorReportCollector.KEEP_REPORTS]）滚动删除**只碰已发件**（名字在 `.uploaded`
 *   索引里），未发件一条都不删；
 * - 第二道闸（[ErrorReportCollector.MAX_PENDING_REPORTS]）给未发件一个硬顶，超出才删最旧。
 *
 * 全部判式在 JVM 用临时目录就能钉红/钉绿——不需要真机、不碰网络。可证伪见下面两条用例：
 * 把第一道闸退回「总数滚动（不分已发/未发）」，[剪枝跳过未发件] 立刻红（未发件被删）；
 * 把第二道闸摘掉，[未发件超上限才删最旧] 立刻红（积压不收敛）。
 */
class ErrorReportPruneProtectionTest {

    @get:Rule
    val root = TemporaryFolder()

    /** 建一份报告文件；mtime 用 [order] 递增，保证「越靠后越新」。 */
    private fun report(dir: File, tag: String, order: Int): File =
        File(dir, "eratw-report-20261103-$tag.txt").apply {
            writeText("r")
            setLastModified(1_700_000_000_000L + order * 10_000L)
        }

    private fun writeIndex(dir: File, names: List<String>) {
        File(dir, ErrorReportUpload.INDEX_FILE_NAME).writeText(names.joinToString("\n"), Charsets.UTF_8)
    }

    @Test
    fun `剪枝跳过未发件_已发尽才按 keep 滚动`() {
        val dir = root.newFolder("protect-pending")
        // 5 份，最旧→最新：a、b 未发；c、d、e 已发（在索引里）。
        val a = report(dir, "a", 0)
        val b = report(dir, "b", 1)
        val c = report(dir, "c", 2)
        val d = report(dir, "d", 3)
        val e = report(dir, "e", 4)
        writeIndex(dir, listOf(c.name, d.name, e.name))

        // keep=2：已发件 {c,d,e} 超过 2 → 删最旧的已发件 c；未发件 {a,b} 不受第一道闸碰。
        // maxPending=10：未发件只有 2 ≤ 10 → 一条都不删。
        ErrorReportCollector.prune(dir, keep = 2, maxPending = 10)

        assertTrue("未发件 a 不许被滚动删除（这是本波要结的病）", a.isFile)
        assertTrue("未发件 b 不许被滚动删除", b.isFile)
        assertFalse("已发件里最旧的 c 应按 keep 滚动删除", c.exists())
        assertTrue("已发件 d 保留", d.isFile)
        assertTrue("已发件 e 保留", e.isFile)
        // 存活 = a,b,d,e 四份报告 + 索引文件本身（.uploaded 不参与统计）。
        val reports = dir.listFiles { f -> f.isFile && f.name.startsWith("eratw-report-") }!!
        assertEquals(4, reports.size)
    }

    @Test
    fun `未发件超上限才删最旧_离线硬顶`() {
        val dir = root.newFolder("pending-cap")
        // 无索引 ⇒ 4 份全判成未发件；maxPending=2 ⇒ 删最旧两份、留最新两份。
        val p1 = report(dir, "p1", 0)
        val p2 = report(dir, "p2", 1)
        val p3 = report(dir, "p3", 2)
        val p4 = report(dir, "p4", 3)

        ErrorReportCollector.prune(dir, keep = 10, maxPending = 2)

        assertFalse("未发件积压超上限时删最旧 p1", p1.exists())
        assertFalse("未发件积压超上限时删最旧 p2", p2.exists())
        assertTrue("较新的 p3 留到下轮补发", p3.isFile)
        assertTrue("较新的 p4 留到下轮补发", p4.isFile)
        val reports = dir.listFiles { f -> f.isFile && f.name.startsWith("eratw-report-") }!!
        assertEquals("未发件收到硬顶 2 份", 2, reports.size)
    }

    @Test
    fun `已发件从未发满上限时一条都不动_保护语义不误伤`() {
        val dir = root.newFolder("no-op")
        // 3 份全已发、都在 keep=10 与 maxPending=10 之内 ⇒ 什么都不删。
        val x = report(dir, "x", 0)
        val y = report(dir, "y", 1)
        val z = report(dir, "z", 2)
        writeIndex(dir, listOf(x.name, y.name, z.name))

        ErrorReportCollector.prune(dir)

        assertTrue(x.isFile); assertTrue(y.isFile); assertTrue(z.isFile)
        assertEquals(3, dir.listFiles { f -> f.isFile && f.name.startsWith("eratw-report-") }!!.size)
    }

    @Test
    fun `索引读不出时全判未发件_仍按硬顶收口不误删`() {
        val dir = root.newFolder("bad-index")
        // 写一个坏索引（读侧仍会拿到某集合，但这里干脆不放索引文件：readIndex 回空集）。
        val files = (0 until 6).map { report(dir, "f$it", it) }

        // keep=10（本场景 0 已发件）；maxPending=3 ⇒ 未发件 6 → 删最旧 3、留最新 3。
        ErrorReportCollector.prune(dir, keep = 10, maxPending = 3)

        files.take(3).forEach { assertFalse("无索引时未发件也受硬顶：最旧三份删掉", it.exists()) }
        files.takeLast(3).forEach { assertTrue("最新三份留给补发", it.isFile) }
    }
}
