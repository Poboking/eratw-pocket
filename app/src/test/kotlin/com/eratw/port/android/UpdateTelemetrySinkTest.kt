package com.eratw.port.android

import com.eratw.port.core.update.UpdateTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateTelemetrySink] 定向单测（更新漏斗遥测基座，P1-4 出口判据）。
 *
 * 只钉两件在纯 JVM 上判得起、判错又最贵的事：
 * - 「通道关 = 零出网」：端点空 / 明文 http / 带空白畸形时，[FakeTransport] 一次都不被调；
 * - 「编码形状」：body 带 kind 标签、events 数组里逐行就是 core `UpdateTelemetry.encode`
 *   的产出，sink 不重新编码、不加工内容。
 *
 * HTTP 管线（[UpdateTelemetrySink.defaultTransport]）不在此测，与
 * `ErrorReportUploader` 的 HTTP 一样归集成/真机面；这里只测经 [FakeTransport] 注入的
 * 判定与装配。
 */
class UpdateTelemetrySinkTest {

    /** 记录每次 post 的三元组，并可按用例设定「发送成功/失败」。 */
    private class FakeTransport(var result: Boolean = true) : UpdateTelemetrySink.Transport {
        val calls: MutableList<Triple<String, String, String>> = mutableListOf()
        override fun post(endpoint: String, token: String, body: String): Boolean {
            calls.add(Triple(endpoint, token, body))
            return result
        }
    }

    private val endpoint = "https://eratw.example/report"
    private val token = "sekret"

    /** 借 core 的编码器产一行，避免测试自己手搓 JSON 与 core 漂移。 */
    private fun coreLine(): String {
        val core = UpdateTelemetry(
            object : UpdateTelemetry.Storage {
                override fun load(): List<String> = emptyList()
                override fun save(lines: List<String>) = Unit
            },
            { 1_700_000_000_000L },
            { true },
        )
        return core.encode(UpdateTelemetry.Event("check_ok", 1_700_000_000_000L, version = 15L))
    }

    // ------------------------------------------------------------------
    // 通道关 = 零出网
    // ------------------------------------------------------------------

    @Test
    fun `通道未配置时transport零调用_返回true`() {
        val badEndpoints = listOf(
            "",                                   // 出包没带参数（缺省空串）
            "   ",                                // 纯空白
            "http://eratw.example/report",        // 明文 http
            "https://eratw.example/a b",          // 带空白的畸形端点
            "ftp://eratw.example/report",         // 非 https 协议
        )
        for (bad in badEndpoints) {
            val transport = FakeTransport()
            val ok = UpdateTelemetrySink.flush(bad, token, listOf(coreLine()), transport)
            assertTrue("端点 $bad 应判为已消费", ok)
            assertEquals("端点 $bad 不该触碰传输口", 0, transport.calls.size)
        }
    }

    @Test
    fun `sendCallback_通道关时零出网`() {
        val transport = FakeTransport()
        val send = UpdateTelemetrySink.sendCallback("", token, transport)
        assertTrue(send(listOf(coreLine(), coreLine())))
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `空批即便通道开也不触发transport`() {
        val transport = FakeTransport()
        assertTrue(UpdateTelemetrySink.flush(endpoint, token, emptyList(), transport))
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun `isEnabled_复用core的https_only判定_没跑偏`() {
        assertTrue(UpdateTelemetrySink.isEnabled("https://eratw.example/report"))
        assertFalse(UpdateTelemetrySink.isEnabled(""))
        assertFalse(UpdateTelemetrySink.isEnabled("   "))
        assertFalse(UpdateTelemetrySink.isEnabled("http://eratw.example/report"))
        assertFalse(UpdateTelemetrySink.isEnabled("https://eratw.example/a b"))
    }

    // ------------------------------------------------------------------
    // 编码形状
    // ------------------------------------------------------------------

    @Test
    fun `编码形状_kind标签与core事件行`() {
        val line = coreLine()
        assertEquals(
            """{"kind":"update-funnel","events":[{"event":"check_ok","at":1700000000000,"version":15}]}""",
            UpdateTelemetrySink.encodeBody(listOf(line)),
        )
    }

    @Test
    fun `编码形状_多事件逗号分隔且顺序不变`() {
        val a = coreLine()
        val core = UpdateTelemetry(
            object : UpdateTelemetry.Storage {
                override fun load(): List<String> = emptyList()
                override fun save(lines: List<String>) = Unit
            },
            { 1_700_000_000_000L },
            { true },
        )
        val b = core.encode(UpdateTelemetry.Event("download_failed", 1_700_000_000_001L, version = 15L, kind = "apk", sizeBytes = 9L, failure = "network"))
        assertEquals(
            """{"kind":"update-funnel","events":[$a,$b]}""",
            UpdateTelemetrySink.encodeBody(listOf(a, b)),
        )
    }

    // ------------------------------------------------------------------
    // https 开：整批送到位 / 失败保留
    // ------------------------------------------------------------------

    @Test
    fun `https开时整批送到transport_端点令牌body都正确`() {
        val transport = FakeTransport(result = true)
        val lines = listOf(coreLine(), coreLine())
        assertTrue(UpdateTelemetrySink.flush(endpoint, token, lines, transport))
        assertEquals(1, transport.calls.size)
        val (gotEndpoint, gotToken, gotBody) = transport.calls[0]
        assertEquals(endpoint, gotEndpoint)
        assertEquals(token, gotToken)
        assertEquals(UpdateTelemetrySink.encodeBody(lines), gotBody)
    }

    @Test
    fun `发送失败返回false_整批留给core重试`() {
        val transport = FakeTransport(result = false)
        assertFalse(UpdateTelemetrySink.flush(endpoint, token, listOf(coreLine()), transport))
        assertEquals(1, transport.calls.size) // 确实试过一次
    }
}
