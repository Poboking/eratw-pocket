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
 * 稳定性波车道 L17 的定向单测：报告栈深口径、剪枝排序、空报告/崩溃补发次序，
 * 以及调试通道不许在调试线程上解 `.erbc` 惰性函数表。
 *
 * 每一条都钉一个「桌面全绿、真机才炸」的判式：桩数据在 JVM 上就能判红。
 * 源码哨兵与 [ErrorReportVmStackTest] 同族（静态补位，读的是代码行，不读 KDoc）。
 */
class StabilityL17ReportAndDebugTest {

    @get:Rule
    val root = TemporaryFolder()

    private val maxBytes: Long = ErrorReportUpload.MAX_BODY_BYTES.toLong()

    private fun resolver(names: Map<Int, String>): (Int) -> String? = { names[it] }

    // ---- ① VmStack：depth 是栈顶帧下标，不是帧数 ----

    @Test
    fun `depth 是栈顶下标_只有入口帧时也要报出一帧`() {
        val names = mapOf(10 to "OUTER", 20 to "MIDDLE", 30 to "INNER")
        // Vm.run 起跑就是 depth = 0 + funcStack[0] = 入口；此时崩溃必须还能看到入口函数。
        assertEquals(
            listOf("#0 OUTER"),
            VmStack.frameLines(intArrayOf(10, 20, 30), depth = 0, maxFrames = 25, resolve = resolver(names)),
        )
        assertEquals(
            listOf("#0 MIDDLE", "#1 OUTER"),
            VmStack.frameLines(intArrayOf(10, 20, 30), depth = 1, maxFrames = 25, resolve = resolver(names)),
        )
        assertEquals(
            listOf("#0 INNER", "#1 MIDDLE", "#2 OUTER"),
            VmStack.frameLines(intArrayOf(10, 20, 30), depth = 2, maxFrames = 25, resolve = resolver(names)),
        )
    }

    @Test
    fun `截断说明里的总帧数是 depth 加一`() {
        val names = (0 until 10).associateWith { "F$it" }
        val lines = VmStack.frameLines(IntArray(10) { it }, depth = 9, maxFrames = 3, resolve = resolver(names))
        assertEquals(
            listOf("#0 F9", "#1 F8", "#2 F7", "…（共 10 帧，余下省略）"),
            lines,
        )
    }

    @Test
    fun `栈顶下标越过表长仍按表长收口`() {
        val lines = VmStack.frameLines(
            intArrayOf(7, 8),
            depth = 5,
            maxFrames = 25,
            resolve = resolver(mapOf(7 to "A", 8 to "B")),
        )
        assertEquals(listOf("#0 B", "#1 A"), lines)
    }

    // ---- ② 剪枝：按 mtime，不按文件名 ----

    @Test
    fun `prune 只删最旧_同秒避让的 epoch 名不会被误删`() {
        val dir = root.newFolder("prune")
        val base = 1_700_000_000_000L
        val dated = (0 until 11).map { i ->
            File(dir, "eratw-report-20261031-" + String.format("%06d", i) + ".txt").apply {
                writeText("r")
                setLastModified(base + i * 10_000L)
            }
        }
        // 同秒第二份的避让名：字典序最小（"1..." < "2..."），但它是**最新**写出的那份。
        val newest = File(dir, "eratw-report-" + (base + 999_999L) + ".txt").apply {
            writeText("r")
            setLastModified(base + 11 * 10_000L)
        }

        ErrorReportCollector.prune(dir, keep = 10)

        assertTrue("文件名排序会把这份当最旧删掉", newest.isFile)
        assertFalse("mtime 最旧的两份应被删", dated[0].exists() || dated[1].exists())
        assertTrue(dated.drop(2).all { it.isFile })
        assertEquals("保留份数按 keep 收口", 10, dir.listFiles { f -> f.isFile }!!.size)
    }

    // ---- ③ 补发：空报告不发；崩溃档只赌最新一份 ----

    @Test
    fun `空报告不 POST_陈旧空件记索引而新鲜空件留给下一轮`() {
        val dir = root.newFolder("empty")
        val index = File(dir, ErrorReportUpload.INDEX_FILE_NAME)
        val stale = File(dir, "eratw-report-20261031-000001.txt").apply {
            writeText("")
            setLastModified(System.currentTimeMillis() - 60_000L)
        }
        // 刚落盘还没写完的那份：报告与补发在不同线程上，不能在这里判死。
        val fresh = File(dir, "eratw-report-20261031-000002.txt").apply { writeText("") }

        val sent = ArrayList<String>()
        val logs = ArrayList<String>()
        val ok = ReportUploadQueue.scanAndUpload(
            dir = dir,
            indexFile = index,
            maxBodyBytes = maxBytes,
            crashMode = false,
            log = { message, _ -> logs += message },
        ) { file ->
            sent += file.name
            true
        }

        assertEquals("空报告不该计成功", 0, ok)
        assertTrue("空体 POST 只在端点留空气条目", sent.isEmpty())
        assertTrue(logs.joinToString("|"), logs.any { it.contains("空报告") && it.contains(stale.name) })
        val firstIndex = ReportUploadQueue.readIndex(index)
        assertTrue("冷却期外的空件是坏件，记档止噪", firstIndex.contains(stale.name))
        assertFalse("冷却期内的空件可能是正在写的，不能记成已发", firstIndex.contains(fresh.name))

        // 冷却之后仍是空：确认坏件、记索引，从此不再重试。
        fresh.setLastModified(System.currentTimeMillis() - 60_000L)
        assertEquals(0, ReportUploadQueue.scanAndUpload(dir, index, maxBytes, false) { file -> sent += file.name; true })
        assertTrue(ReportUploadQueue.readIndex(index).contains(fresh.name))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `崩溃档按 mtime 最新优先_非崩溃档仍按名字升序`() {
        val dir = root.newFolder("crash")
        val index = File(dir, ErrorReportUpload.INDEX_FILE_NAME)
        val newest = File(dir, "eratw-report-20261031-000009.txt").apply {
            writeText("new")
            setLastModified(1_700_000_100_000L)
        }
        val older = File(dir, "eratw-report-20261031-000001.txt").apply {
            writeText("old")
            setLastModified(1_700_000_000_000L)
        }

        val crashTried = ArrayList<String>()
        val crashOk = ReportUploadQueue.scanAndUpload(dir, index, maxBytes, crashMode = true) { file ->
            crashTried += file.name
            false
        }
        assertEquals(0, crashOk)
        assertEquals("崩溃 3 秒预算只赌一份，必须先试崩前刚落的报告", listOf(newest.name), crashTried)
        assertFalse("没发出去就不能记已发", ReportUploadQueue.readIndex(index).contains(newest.name))

        // 非崩溃档语义不变：照旧按名字升序把积压清完（本轮两份都成功）。
        val backlogSent = ArrayList<String>()
        val backlogOk = ReportUploadQueue.scanAndUpload(dir, index, maxBytes, crashMode = false) { file ->
            backlogSent += file.name
            true
        }
        assertEquals(2, backlogOk)
        assertEquals(listOf(older.name, newest.name), backlogSent)
    }

    // ---- ④ 源码哨兵：调试通道不再走惰性解码面 ----

    @Test
    fun `调试通道取帧名只走运行期函数表`() {
        for (name in listOf("DevToolsHost.kt", "DevState.kt")) {
            val code = codeLines(name)
            assertFalse(
                "$name 在调试线程上读 program.functions 会与 VM 线程抢未加锁的解码缓冲",
                code.contains("program.functions"),
            )
            assertTrue(
                "$name 应复用 ErrorReportCollector.functionNameResolver（只走 FunctionTable 数组读）",
                code.contains("functionNameResolver"),
            )
        }
        assertTrue(
            "callstack 的帧序/越界收口应与报告同一套判式（depth 加一，不是 depth 减一）",
            codeLines("DevToolsHost.kt").contains("VmStack.frameLines"),
        )
    }

    /** 只取代码行：KDoc 里当然要写明原来走哪条危险路，那不是违例。 */
    private fun codeLines(name: String): String = source(name).lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }.joinToString("\n")

    private fun source(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }
}
