package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 匿名玩家数统计的**纯判式**定向单测（零 Android API、零网络）。
 *
 * 钉的是三条不能退化的口径：
 * 1. 出网形状：body 只有一个 kind 与一枚 64 位小写 hex，别的东西一个字都不带；
 * 2. 通道闸：端点没配 / 不是 https / id 形状不对 —— 一个字节都不许出网；
 * 3. 日界：节流按服务端 UTC，不跟玩家本机时区漂。
 *
 * 存储面在 worker.js 走 R2（不占 KV 免费档），客户端这一层与存储选择无关，
 * 但仍按「每天最多一次」写：失败不记日期，下次冷启再试。
 */
class PlayerStatsReporterTest {

    private companion object {
        const val ENDPOINT = "https://eratw.example/report"
        const val TOKEN = "test-token"
        val ID_A = "a".repeat(64)
    }

    /** 出站面唯一判据：calls 为空 = 一个字节都没发出去。 */
    private class FakeTransport(var result: Boolean = true, var fail: Boolean = false) :
        UpdateTelemetrySink.Transport {
        val calls: MutableList<Triple<String, String, String>> = mutableListOf()
        override fun post(endpoint: String, token: String, body: String): Boolean {
            if (fail) throw IllegalStateException("桩：传输层炸了")
            calls.add(Triple(endpoint, token, body))
            return result
        }
    }

    // -----------------------------------------------------------------
    // 端点闸（与错误报告、更新漏斗同源）
    // -----------------------------------------------------------------

    @Test
    fun `只有配置过的 https 端点才开闸`() {
        assertTrue(PlayerStatsReporter.isEnabled(ENDPOINT))
        for (bad in listOf("", "  ", "http://eratw.example/report", "eratw.example/report",
            "https://eratw.example/report with space", "https://eratw.example/re port")) {
            assertFalse("端点 [$bad] 不该开闸", PlayerStatsReporter.isEnabled(bad))
        }
    }

    @Test
    fun `端点没配时 flush 不出网`() {
        val transport = FakeTransport()
        assertFalse(PlayerStatsReporter.flush("", TOKEN, ID_A, transport))
        assertTrue("一个字节都不许发", transport.calls.isEmpty())
    }

    // -----------------------------------------------------------------
    // 编号形状
    // -----------------------------------------------------------------

    @Test
    fun `只认 64 位小写十六进制`() {
        assertTrue(PlayerStatsReporter.isValidId(ID_A))
        assertTrue(PlayerStatsReporter.isValidId("0123456789abcdef".repeat(4)))
        for (bad in listOf<String?>(
            null, "", "A".repeat(64), "a".repeat(63), "a".repeat(65),
            "../evil", "zz", "a".repeat(63) + "g", "a".repeat(64) + "\n",
        )) {
            assertFalse("坏 id [$bad] 不该过闸", PlayerStatsReporter.isValidId(bad))
        }
    }

    @Test
    fun `随机字节转小写 hex 且不碰区域格式`() {
        assertEquals("00ff10", PlayerStatsReporter.toHex(byteArrayOf(0, -1, 16)))
        val random: (Int) -> ByteArray = { size -> ByteArray(size) { 0xAB.toByte() } }
        val id = PlayerStatsReporter.freshId(random)
        assertEquals("ab".repeat(32), id)
        assertTrue(PlayerStatsReporter.isValidId(id))
        assertEquals(32, PlayerStatsReporter.ID_BYTES)
    }

    @Test
    fun `随机源给错长度会被形状闸挡住`() {
        val shortId = PlayerStatsReporter.freshId { ByteArray(4) }
        assertFalse(PlayerStatsReporter.isValidId(shortId))
    }

    // -----------------------------------------------------------------
    // 日界与节流闸
    // -----------------------------------------------------------------

    @Test
    fun `日界按 UTC 且不受本机时区影响`() {
        assertEquals("1970-01-01", PlayerStatsReporter.utcDay(0L))
        assertEquals("1970-01-02", PlayerStatsReporter.utcDay(86_400_000L))
        val before = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            assertEquals("东京时区下仍按 UTC 日界", "1970-01-01", PlayerStatsReporter.utcDay(0L))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("洛杉矶时区下仍按 UTC 日界", "1970-01-02", PlayerStatsReporter.utcDay(86_400_000L))
        } finally {
            TimeZone.setDefault(before)
        }
    }

    @Test
    fun `三个短路任一命中就不发`() {
        assertTrue(PlayerStatsReporter.shouldPing(true, ENDPOINT, null, "2026-11-08"))
        assertFalse("玩家关了", PlayerStatsReporter.shouldPing(false, ENDPOINT, null, "2026-11-08"))
        assertFalse("端点没配", PlayerStatsReporter.shouldPing(true, "", null, "2026-11-08"))
        assertFalse("今天已成功发过", PlayerStatsReporter.shouldPing(true, ENDPOINT, "2026-11-08", "2026-11-08"))
        assertTrue("换了一天该再发", PlayerStatsReporter.shouldPing(true, ENDPOINT, "2026-11-07", "2026-11-08"))
    }

    // -----------------------------------------------------------------
    // body 与发送口
    // -----------------------------------------------------------------

    @Test
    fun `body 只带 kind 与 id`() {
        assertEquals(
            "{\"kind\":\"player-presence\",\"id\":\"$ID_A\"}",
            PlayerStatsReporter.encodeBody(ID_A),
        )
        val body = PlayerStatsReporter.encodeBody(ID_A)
        assertFalse(body.contains("device"))
        assertFalse(body.contains("version"))
        assertFalse(body.contains("time"))
        assertFalse(body.contains("token"))
    }

    @Test
    fun `成功一次只发一拍且带对端点令牌与 body`() {
        val transport = FakeTransport()
        assertTrue(PlayerStatsReporter.flush(ENDPOINT, TOKEN, ID_A, transport))
        assertEquals(1, transport.calls.size)
        assertEquals(ENDPOINT, transport.calls[0].first)
        assertEquals(TOKEN, transport.calls[0].second)
        assertEquals("{\"kind\":\"player-presence\",\"id\":\"$ID_A\"}", transport.calls[0].third)
    }

    @Test
    fun `坏 id 非 2xx 与传输抛错都不算成功`() {
        val bad = FakeTransport()
        assertFalse(PlayerStatsReporter.flush(ENDPOINT, TOKEN, "A".repeat(64), bad))
        assertTrue("坏 id 一个字节都不许发", bad.calls.isEmpty())

        val non2xx = FakeTransport(result = false)
        assertFalse(PlayerStatsReporter.flush(ENDPOINT, TOKEN, ID_A, non2xx))
        assertEquals("确实发过一趟，只是没被收下", 1, non2xx.calls.size)

        val broken = FakeTransport(fail = true)
        assertFalse("传输抛错不许外逃", PlayerStatsReporter.flush(ENDPOINT, TOKEN, ID_A, broken))
    }
}
