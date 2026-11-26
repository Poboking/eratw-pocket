package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 下载量统计面的定向单测（CONTRACT-MKM-STATS-UI §3 判据 1/4；hermetic，零网络）。
 *
 * 覆盖：Releases JSON 解析（真实形状 + 逐 asset 防御负例 + 整篇形状拒）、盘上缓存往返
 * 与损坏回 Absent、Loader 的 TTL 命中 / 过期取数 / 失败降级（Stale 带旧快照）、
 * 纯查询 [MarketStatsUi.downloadsFor] 的未命中不编 0。
 */
class MarketStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------- 夹具

    /** 真实 Releases 形状（两 release、三 asset，其中一个 asset 缺字段验证逐条跳过）。 */
    private fun releasesJson(vararg assets: Triple<String, Long, Boolean>): String {
        val assetJson = assets.joinToString(",") { (url, count, ok) ->
            if (ok) {
                """{"name":"a.zip","browser_download_url":"$url","download_count":$count}"""
            } else {
                // 不合格 asset：缺 browser_download_url
                """{"name":"a.zip","download_count":$count}"""
            }
        }
        return """[{"tag_name":"v1","assets":[$assetJson]},{"tag_name":"v2","assets":[]}]"""
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `解析_真实形状_按browser_download_url建映射`() {
        val json = releasesJson(
            Triple(
                "https://github.com/Poboking/kojo-market/releases/download/demo.dict-v1.0.0/demo.dict-1.0.0.zip",
                7L,
                true,
            ),
            Triple(
                "https://github.com/Poboking/kojo-market/releases/download/demo.era-tw-v1.0.3/demo.era-tw-1.0.3.zip",
                12L,
                true,
            ),
        )
        val r = MarketStatsParser.parse(json)
        assertTrue(r is MarketStatsParseResult.Ok)
        r as MarketStatsParseResult.Ok
        val dictUrl = "https://github.com/Poboking/kojo-market/releases/download/demo.dict-v1.0.0/demo.dict-1.0.0.zip"
        val eraUrl =
            "https://github.com/Poboking/kojo-market/releases/download/demo.era-tw-v1.0.3/demo.era-tw-1.0.3.zip"
        assertEquals(7L, r.downloads[dictUrl])
        assertEquals(12L, r.downloads[eraUrl])
        assertEquals(2, r.downloads.size)
    }

    @Test
    fun `解析_缺browser_download_url的asset跳过不拒整批`() {
        val json = releasesJson(
            Triple("https://example.invalid/good.zip", 5L, true),
            Triple("", 9L, false),
        )
        val r = MarketStatsParser.parse(json)
        assertTrue(r is MarketStatsParseResult.Ok)
        r as MarketStatsParseResult.Ok
        assertEquals(mapOf("https://example.invalid/good.zip" to 5L), r.downloads)
    }

    @Test
    fun `解析_download_count非负整数或负数跳过`() {
        val json = """
            [{"assets":[
                {"browser_download_url":"https://example.invalid/a.zip","download_count":"many"},
                {"browser_download_url":"https://example.invalid/b.zip","download_count":-1},
                {"browser_download_url":"https://example.invalid/c.zip","download_count":0}
            ]}]
        """.trimIndent()
        val r = MarketStatsParser.parse(json)
        assertTrue(r is MarketStatsParseResult.Ok)
        r as MarketStatsParseResult.Ok
        assertEquals(mapOf("https://example.invalid/c.zip" to 0L), r.downloads)
    }

    @Test
    fun `解析_重复url后值胜`() {
        val json = """
            [{"assets":[{"browser_download_url":"https://example.invalid/a.zip","download_count":1}]},
             {"assets":[{"browser_download_url":"https://example.invalid/a.zip","download_count":2}]}]
        """.trimIndent()
        val r = MarketStatsParser.parse(json)
        assertTrue(r is MarketStatsParseResult.Ok)
        r as MarketStatsParseResult.Ok
        assertEquals(2L, r.downloads["https://example.invalid/a.zip"])
    }

    @Test
    fun `解析_整篇非数组或非法JSON判Invalid`() {
        assertTrue(MarketStatsParser.parse("{}") is MarketStatsParseResult.Invalid)
        assertTrue(MarketStatsParser.parse("not json") is MarketStatsParseResult.Invalid)
    }

    @Test
    fun `解析_空数组给空映射不判Invalid`() {
        val r = MarketStatsParser.parse("[]")
        assertTrue(r is MarketStatsParseResult.Ok)
        r as MarketStatsParseResult.Ok
        assertTrue(r.downloads.isEmpty())
    }

    // ---------------------------------------------------------------- 缓存

    @Test
    fun `缓存_写读往返_映射与时刻保真`() {
        val store = MarketStatsStore(File(tmp.root, "stats.json"))
        store.write(MarketStatsSnapshot(fetchedAtMs = 123_456L, downloads = mapOf("u1" to 1L, "u2" to 999L)))
        val state = store.read()
        assertTrue(state is MarketStatsState.Ok)
        state as MarketStatsState.Ok
        assertEquals(123_456L, state.snapshot.fetchedAtMs)
        assertEquals(mapOf("u1" to 1L, "u2" to 999L), state.snapshot.downloads)
    }

    @Test
    fun `缓存_文件缺席或损坏或形状不对一律Absent`() {
        val root = tmp.root
        assertNull((MarketStatsStore(File(root, "missing.json")).read() as? MarketStatsState.Ok))
        val broken = File(root, "broken.json")
        broken.writeText("{ not json")
        assertNull((MarketStatsStore(broken).read() as? MarketStatsState.Ok))
        val wrongShape = File(root, "shape.json")
        wrongShape.writeText("""{"schema":1,"fetchedAtMs":"not-a-number"}""")
        assertNull((MarketStatsStore(wrongShape).read() as? MarketStatsState.Ok))
        val negTime = File(root, "neg.json")
        negTime.writeText("""{"schema":1,"fetchedAtMs":-5,"downloads":{}}""")
        assertNull((MarketStatsStore(negTime).read() as? MarketStatsState.Ok))
    }

    // ---------------------------------------------------------------- Loader

    @Test
    fun `loader_TTL内回缓存不联网`() {
        var fetchCalls = 0
        val store = MarketStatsStore(File(tmp.root, "stats.json"))
        val loader = testLoader(store, { fetchCalls += 1; "[]" }, { 10_000L })
        store.write(MarketStatsSnapshot(fetchedAtMs = 1_000L, downloads = mapOf("u" to 3L)))
        val r = loader.load(force = false)
        assertTrue(r is MarketStatsLoader.Outcome.Ok)
        r as MarketStatsLoader.Outcome.Ok
        assertEquals(false, r.fromNetwork)
        assertEquals(0, fetchCalls)
        assertEquals(3L, r.snapshot.downloads["u"])
    }

    @Test
    fun `loader_过期取数成功覆盖缓存`() {
        var fetchCalls = 0
        val store = MarketStatsStore(File(tmp.root, "stats.json"))
        val loader = testLoader(store, {
            fetchCalls += 1
            """[{"assets":[{"browser_download_url":"https://example.invalid/new.zip","download_count":42}]}]"""
        }, { 100_000_000L }) // 远超 6h TTL（7e6 ms 只有 1.9h，仍在 TTL 内——首版踩过这坑）
        store.write(MarketStatsSnapshot(fetchedAtMs = 1_000L, downloads = mapOf("old" to 1L)))
        val r = loader.load(force = false)
        assertTrue(r is MarketStatsLoader.Outcome.Ok)
        r as MarketStatsLoader.Outcome.Ok
        assertTrue(r.fromNetwork)
        assertEquals(1, fetchCalls)
        assertEquals(42L, r.snapshot.downloads["https://example.invalid/new.zip"])
        // 盘上已被覆盖为新快照
        val reread = store.read() as MarketStatsState.Ok
        assertEquals(42L, reread.snapshot.downloads["https://example.invalid/new.zip"])
    }

    @Test
    fun `loader_取数失败降级Stale带旧快照`() {
        val store = MarketStatsStore(File(tmp.root, "stats.json"))
        val loader = testLoader(store, { throw java.io.IOException("boom") }, { 100_000_000L })
        store.write(MarketStatsSnapshot(fetchedAtMs = 1_000L, downloads = mapOf("old" to 1L)))
        val r = loader.load(force = false)
        assertTrue(r is MarketStatsLoader.Outcome.Stale)
        r as MarketStatsLoader.Outcome.Stale
        assertEquals(1L, r.previous?.downloads?.get("old"))
    }

    @Test
    fun `loader_失败且无旧缓存_Stale的previous为null`() {
        val loader = testLoader(
            MarketStatsStore(File(tmp.root, "empty.json")),
            { throw java.io.IOException("boom") },
            { 1L },
        )
        val r = loader.load(force = true)
        assertTrue(r is MarketStatsLoader.Outcome.Stale)
        assertNull((r as MarketStatsLoader.Outcome.Stale).previous)
    }

    @Test
    fun `loader_force无视TTL硬回源`() {
        var fetchCalls = 0
        val store = MarketStatsStore(File(tmp.root, "stats.json"))
        val loader = testLoader(store, { fetchCalls += 1; "[]" }, { 1_000L })
        store.write(MarketStatsSnapshot(fetchedAtMs = 999L, downloads = emptyMap()))
        val r = loader.load(force = true)
        assertTrue((r as MarketStatsLoader.Outcome.Ok).fromNetwork)
        assertEquals(1, fetchCalls)
    }

    // ---------------------------------------------------------------- 纯查询

    @Test
    fun `查询_命中给真值_未命中与缺席给null不编0`() {
        val ok = MarketStatsState.Ok(MarketStatsSnapshot(1L, mapOf("https://example.invalid/a.zip" to 5L)))
        assertEquals(5L, MarketStatsUi.downloadsFor(ok, "https://example.invalid/a.zip"))
        assertNull(MarketStatsUi.downloadsFor(ok, "https://example.invalid/missing.zip"))
        assertNull(MarketStatsUi.downloadsFor(MarketStatsState.Absent, "https://example.invalid/a.zip"))
        assertNull(MarketStatsUi.downloadsFor(null, "https://example.invalid/a.zip"))
    }
}

/** fetcher lambda 形态的接口适配（测试用）。 */
private fun testLoader(
    store: MarketStatsStore,
    fetcher: () -> String,
    clock: () -> Long,
): MarketStatsLoader = MarketStatsLoader(store, object : MarketStatsFetcher {
    override fun fetchReleasesJson(): String = fetcher()
}, clock)
