package com.eratw.port.android

import com.eratw.port.core.host.HostEventCodec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 结构化事件日志（R3）的定向锁：未 begin 只记内存环、落盘行可回读、超限轮转、载荷截断。
 *
 * 判据来自 CONTRACT-MODERN-T3 §3.2/§5.2：埋点绝不抛、未绑定目录不写盘、两代文件有界。
 *
 * STAB-C3 起写路径异步化（单写线程 + 有界队列）：断言落盘前必须 [EventJournal.flushForTest]
 * 排空队列 —— 行内容与行序的判据逐字不变，只是「谁写」从调用线程换成了写线程。
 */
class EventJournalTest {

    private lateinit var folder: TemporaryFolder

    @Before
    fun setUp() {
        folder = TemporaryFolder()
        folder.create()
        EventJournal.resetForTest()
    }

    @After
    fun tearDown() {
        EventJournal.resetForTest()
        folder.delete()
    }

    @Test
    fun `未 begin 时只记内存环且不写盘`() {
        EventJournal.record("turn.end", mapOf("ms" to "1"))
        assertEquals(1, EventJournal.events().size)
        assertNull(EventJournal.file())
        assertTrue(EventJournal.lines().single().contains("\"type\":\"turn.end\""))
    }

    @Test
    fun `begin 后落盘行与内存环逐字一致且可解码`() {
        EventJournal.begin(folder.root)
        EventJournal.record("turn.end", linkedMapOf("ms" to "42"))
        EventJournal.record("oom", mapOf("level" to "80"))
        EventJournal.flushForTest()
        val file = EventJournal.file()!!
        val disk = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, disk.size)
        assertEquals(EventJournal.lines(), disk)
        assertEquals("turn.end", HostEventCodec.decode(disk[0]).type)
        assertEquals("80", HostEventCodec.decode(disk[1]).payload["level"])
    }

    @Test
    fun `当前文件达到上限时整代轮转且新文件重新计`() {
        EventJournal.begin(folder.root)
        val current = EventJournal.file()!!
        // 预置一个刚好到上限的旧文件：下一次写入必须先轮转，而不是继续追加。
        current.writeBytes(ByteArray(EventFilePolicy.MAX_FILE_BYTES.toInt()) { 'x'.code.toByte() })
        EventJournal.record("turn.end", mapOf("ms" to "1"))
        EventJournal.flushForTest()
        val rotated = java.io.File(folder.root, EventFilePolicy.ROTATED_NAME)
        assertTrue("旧文件必须轮转为 events.1.jsonl", rotated.isFile)
        assertEquals(EventFilePolicy.MAX_FILE_BYTES, rotated.length())
        assertTrue("新文件只含轮转后的新事件", current.length() < EventFilePolicy.MAX_FILE_BYTES)
        assertEquals(1, current.readLines(Charsets.UTF_8).count { it.isNotBlank() })
    }

    @Test
    fun `payload 超八键截断并标注`() {
        EventJournal.begin(folder.root)
        val big = LinkedHashMap<String, String>()
        repeat(10) { big["k$it"] = "v" }
        EventJournal.record("error", big)
        val payload = EventJournal.events().single().payload
        assertEquals(EventJournal.MAX_PAYLOAD_KEYS + 1, payload.size)
        assertEquals("1", payload["_truncated"])
        assertFalse(payload.containsKey("k9"))
    }

    @Test
    fun `reset 后序号重新从 1 起且统计清零`() {
        EventJournal.record("a")
        EventJournal.record("b")
        EventJournal.resetForTest()
        EventJournal.record("c")
        assertEquals(1L, EventJournal.events().single().seq)
        assertTrue(EventJournal.stats(), EventJournal.stats().startsWith("emitted=1 dropped=0 ring=1"))
    }

    @Test
    fun `入队后立即退出 flushForExit 后尾行仍在文件里`() {
        EventJournal.begin(folder.root)
        // 壳层退出路径（onDestroy / 结束应用）的形态：record 只入队就「退出」，尾行必须
        // 由 flushForExit 抢出来 —— dirty_exit 一族的取证语义全靠这一步。
        EventJournal.record("turn.end", mapOf("ms" to "7"))
        EventJournal.record("dirty_exit", mapOf("state" to "dirty"))
        assertTrue("退出 drain 必须在有界等待内排空", EventJournal.flushForExit())
        val disk = EventJournal.file()!!.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, disk.size)
        assertEquals("dirty_exit", HostEventCodec.decode(disk.last()).type)
        assertEquals("dirty", HostEventCodec.decode(disk.last()).payload["state"])
    }

    @Test
    fun `写线程卡死时 flushForExit 有界返回不挂死`() {
        EventJournal.begin(folder.root)
        EventJournal.slowWriteHookForTest = { _, _ -> Thread.sleep(1_000) }
        EventJournal.record("turn.end", mapOf("ms" to "1"))
        EventJournal.record("turn.end", mapOf("ms" to "2"))
        val beganAt = System.nanoTime()
        // 超时路径：写线程被钉死在写闸里，flushForExit 必须有界返回（宁丢行不挂进程）。
        // 返回值不断言 —— 慢写缝把同步刷剩余那段也拖慢，true/false 随调度而定，钉它反而脆。
        EventJournal.flushForExit(200)
        val elapsedMs = (System.nanoTime() - beganAt) / 1_000_000L
        assertTrue("flushForExit 必须有界返回（实测 ${elapsedMs}ms），不得挂死退出流程", elapsedMs < 5_000)
        EventJournal.slowWriteHookForTest = null
        assertTrue("缝拆除后必须能排空", EventJournal.flushForExit(10_000))
        val disk = EventJournal.file()!!.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertEquals(2, disk.size)
    }

    @Test
    fun `写线程被拖慢时 record 只入队不阻塞调用方且满则丢行进读数`() {
        EventJournal.begin(folder.root)
        EventJournal.slowWriteHookForTest = { _, _ -> Thread.sleep(50) }
        try {
            val beganAt = System.nanoTime()
            repeat(600) { EventJournal.record("input.submit", mapOf("len" to it.toString())) }
            val elapsedMs = (System.nanoTime() - beganAt) / 1_000_000L
            // 写线程每行被缝拖 50ms：600 条若走同步落盘要 ~30s。主线程高频路径（input.submit /
            // input.tapRefused 一族）只入队 —— 上界钉 5s，谁把 record 挪回同步写这里必红。
            assertTrue("record 必须只入队（实测 ${elapsedMs}ms），不得阻塞调用方做文件 IO", elapsedMs < 5_000)
            // 队列容量 256：写线程被钉住时最多再消费 1 条 ⇒ 丢行必在 [300, 344]；
            // 丢行必须进读数面（stats 的 writeDropped），否则因果链断了一段没人知道。
            val readout = EventJournal.stats()
            val dropped = Regex("writeDropped=(\\d+)").find(readout)!!
                .groupValues[1].toLong()
            assertTrue("丢行读数必须进 stats（实测 writeDropped=$dropped）且在预期区间", dropped in 300..344)
            // 拆缝排空对账：落盘行数 = 600 − 丢行，一条不多一条不少。
            EventJournal.slowWriteHookForTest = null
            assertTrue(EventJournal.flushForTest())
            val disk = EventJournal.file()!!.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            assertEquals(600 - dropped.toInt(), disk.size)
        } finally {
            EventJournal.slowWriteHookForTest = null
        }
    }

    @Test
    fun `轮转判式边界`() {
        assertFalse(EventFilePolicy.shouldRotate(EventFilePolicy.MAX_FILE_BYTES - 1))
        assertTrue(EventFilePolicy.shouldRotate(EventFilePolicy.MAX_FILE_BYTES))
        assertTrue(EventFilePolicy.shouldRotate(EventFilePolicy.MAX_FILE_BYTES + 1))
    }
}
