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
    fun `轮转判式边界`() {
        assertFalse(EventFilePolicy.shouldRotate(EventFilePolicy.MAX_FILE_BYTES - 1))
        assertTrue(EventFilePolicy.shouldRotate(EventFilePolicy.MAX_FILE_BYTES))
        assertTrue(EventFilePolicy.shouldRotate(EventFilePolicy.MAX_FILE_BYTES + 1))
    }
}
