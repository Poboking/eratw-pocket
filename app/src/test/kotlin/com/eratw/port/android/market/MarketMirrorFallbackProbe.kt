package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 市场镜像端点「可达性 + 双源对拍」探针（CONTRACT-MKM-W2-E2E 判据 2-2）。
 *
 * 镜像（[MarketContract.MIRROR_INDEX_URL]，Worker R2 分发面，miss 时自回源 gh-pages）
 * 与权威端点（[MarketContract.DEFAULT_INDEX_URL]）在 `schema` / `generatedAt` /
 * `packs[].(id,version,zip.sha256)` 三个维度逐条一致 —— 镜像兜底的前提是双源同一份
 * 索引，这是镜像回退面第一次被实证。两边原文都先过真实 [MarketVerifierImpl.parseIndex]
 * 闸再对拍：镜像一旦给出过不了校验的内容，探针先报解析问题、不进对拍。
 *
 * 对拍恒对权威端点（不认 `ERATW_MARKET_E2E_URL` 注入）：镜像一致性是「镜像 vs 权威」
 * 的不变量，主端点注入是探针另一个维度的旋钮（见 [MarketEndToEndLiveTest]），两者不混。
 *
 * ## env 门（契约 §2.5，同 [MarketEndToEndLiveTest]）
 *
 * `ERATW_MARKET_E2E=1` 才生成用例：行集在变量缺失时返回空集合，Parameterized 对空行集
 * 生成 **0 个用例**（不进 XML、不计 skipped）。跑带变量轮前 `./gradlew --stop` +
 * `cleanTest`（守护进程环境变量快照 + test 任务 inputs 不含环境变量，两条纪律同上）。
 *
 * ## 网络口径（契约 §2.6）
 *
 * `HttpURLConnection` 走系统默认栈；直连不通时给 Gradle 测试 JVM 传
 * `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`（AGENTS §1.1 的 Clash 口）。
 */
@RunWith(Parameterized::class)
class MarketMirrorFallbackProbe {

    private val verifier = MarketVerifierImpl()

    // ------------------------------------------------------------------
    // 判据 2-2（可达性半边）：镜像原文可取且过真实解析闸
    // ------------------------------------------------------------------

    @Test
    fun `判据2-2a_镜像端点可达_index原文过真实parseIndex_Ok且schema1`() {
        val (status, body) = httpGetBytes(MarketContract.MIRROR_INDEX_URL)
        assertEquals("镜像端点必须 200：${MarketContract.MIRROR_INDEX_URL}", HTTP_OK, status)
        val parsed = verifier.parseIndex(String(body, Charsets.UTF_8), System.currentTimeMillis())
        val ix = when (parsed) {
            is MarketParseResult.Ok -> parsed.value
            is MarketParseResult.Invalid ->
                throw AssertionError("镜像 index.json 过不了真实解析闸：${parsed.problem}")
        }
        assertEquals("镜像索引 schema 钉 1", MarketContract.SCHEMA_VERSION, ix.schema)
        assertTrue("镜像索引 packs 非空：${ix.packs.size}", ix.packs.isNotEmpty())
        assertTrue("镜像 generatedAt 必须非空", !ix.generatedAt.isNullOrBlank())
    }

    // ------------------------------------------------------------------
    // 判据 2-2（对拍半边）：双源在 schema/generatedAt/packs 三元组维度逐条一致
    // ------------------------------------------------------------------

    @Test
    fun `判据2-2b_镜像与权威对拍_schema_generatedAt_packs三元组逐条一致`() {
        val authority = parseOrFail(MarketContract.DEFAULT_INDEX_URL, "权威")
        val mirror = parseOrFail(MarketContract.MIRROR_INDEX_URL, "镜像")
        assertEquals("schema 一致", authority.schema, mirror.schema)
        assertEquals(
            "generatedAt 一致（双源同一份索引；镜像自回源落后的窗口期不该被玩家碰到）",
            authority.generatedAt,
            mirror.generatedAt,
        )
        assertEquals("packs 条数一致", authority.packs.size, mirror.packs.size)
        val order = compareBy<MarketIndexEntry>({ it.id }, { it.version })
        assertEquals(
            "packs[].(id,version,zip.sha256) 逐条一致（双源一致性首次实证）",
            authority.packs.sortedWith(order).map { Triple(it.id, it.version, it.zipSha256) },
            mirror.packs.sortedWith(order).map { Triple(it.id, it.version, it.zipSha256) },
        )
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 取端点原文并过真实解析闸（失败给人话，探针不打哑巴）。 */
    private fun parseOrFail(url: String, what: String): MarketIndex =
        when (val parsed = verifier.parseIndex(httpText(url), System.currentTimeMillis())) {
            is MarketParseResult.Ok -> parsed.value
            is MarketParseResult.Invalid ->
                throw AssertionError("$what 端点 index 过不了真实解析闸：${parsed.problem}")
        }

    /** 探针侧独立 GET + 200 闸，回原文（系统默认栈，跟跳交给平台默认实现）。 */
    private fun httpText(url: String): String {
        val (status, body) = httpGetBytes(url)
        assertEquals("端点必须 200：$url", HTTP_OK, status)
        return String(body, Charsets.UTF_8)
    }

    /** 探针 GET 装置：状态码 + 响应体字节；非 2xx 读错误流（对拍报文要点全靠它）。 */
    private fun httpGetBytes(url: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = PROBE_TIMEOUT_MS
        conn.readTimeout = PROBE_TIMEOUT_MS
        try {
            val status = conn.responseCode
            val input = if (status == HTTP_OK) {
                conn.inputStream
            } else {
                conn.errorStream ?: conn.inputStream
            }
            return status to input.use { readBody(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** 读尽流（探针读缓冲；流关闭由调用方的 use 收口）。 */
    private fun readBody(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUF_BYTES)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {

        /** env 总闸变量名与放行值（契约 §2.5）。 */
        private const val GATE_ENV = "ERATW_MARKET_E2E"
        private const val GATE_ON = "1"

        private const val HTTP_OK = 200

        /** 探针超时（毫秒）：索引 4KB 级，20s 富余。 */
        private const val PROBE_TIMEOUT_MS = 20_000

        /** 探针读缓冲（64 KiB）。 */
        private const val BUF_BYTES = 64 * 1024

        /**
         * Parameterized 行集：env 门开 = 一行（全部用例各跑一次）；门关 = 空行集
         * = **0 用例生成**（不进 XML、不计 skipped，契约 §2.5 的动态用例纪律）。
         */
        @JvmStatic
        @Parameterized.Parameters
        fun rows(): Collection<Array<Any>> =
            if (gateOn()) listOf(arrayOf<Any>()) else emptyList()

        private fun gateOn(): Boolean = System.getenv(GATE_ENV)?.trim() == GATE_ON
    }
}
