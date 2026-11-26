package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 夹具常量（hermetic，全部为测试内字符串常量；真实样本抄录，禁测试内联网、禁运行时读共享根）
private const val SHA_OK = "d88a4a2422cc47ff3df18068b0c93b209418ccd6c10a5867a27e905448232ae5"
private const val SHA_ERA = "7c7c606b7c5d3d9f69e10d135a69522592c18b9f1f24ffbbd2c9ee5aac3e0c41"
private const val SHA_DICT = "94b7d7a2f2161877084a43f07ed487e6365f874926fc0aef4fe122af8922f684"
private const val ZIP_URL_DICT =
    "https://github.com/Poboking/kojo-market/releases/download/demo.dict-v1.0.0/demo.dict-1.0.0.zip"
private const val ZIP_URL_ERA =
    "https://github.com/Poboking/kojo-market/releases/download/demo.era-tw-v1.0.3/demo.era-tw-1.0.3.zip"

/** 线上 demo.dict 条目（紧凑单行，键序与值同线上；供逐字段负例替换）。 */
private const val ENTRY_DICT =
    "{\"kind\":\"dict-pack\",\"id\":\"demo.dict\",\"version\":\"1.0.0\"," +
        "\"name\":\"演示名称表（合成）\",\"author\":\"kojo-market CI 冒烟\"," +
        "\"license\":\"CC0-1.0（合成演示内容，可自由再配布）\"," +
        "\"summary\":\"market v1 端到端冒烟用合成名称表，无真实游戏文本\"," +
        "\"minAppVersion\":\"0.4.0\",\"gameId\":\"eraTW\"," +
        "\"sourceUrl\":\"https://github.com/Poboking/kojo-market\"," +
        "\"zip\":{\"url\":\"$ZIP_URL_DICT\",\"sha256\":\"$SHA_DICT\",\"bytes\":709}," +
        "\"fileCount\":1,\"unpackedBytes\":77}"

/** 线上 demo.era-tw 条目（紧凑单行，键序与值同线上）。 */
private const val ENTRY_ERA =
    "{\"kind\":\"kojo-pack\",\"id\":\"demo.era-tw\",\"version\":\"1.0.3\"," +
        "\"name\":\"演示口上包（合成）\",\"author\":\"kojo-market CI 冒烟\"," +
        "\"license\":\"CC0-1.0（合成演示内容，可自由再配布）\"," +
        "\"summary\":\"market v1 全链路测试（webhook 闭环验证）用合成口上包，无真实游戏文本\"," +
        "\"minAppVersion\":\"0.4.0\",\"gameId\":\"eraTW\"," +
        "\"sourceUrl\":\"https://github.com/Poboking/kojo-market\"," +
        "\"zip\":{\"url\":\"$ZIP_URL_ERA\",\"sha256\":\"$SHA_ERA\",\"bytes\":1084}," +
        "\"fileCount\":1,\"unpackedBytes\":471}"

/**
 * [MarketVerifierImpl] 的判据面定向单测（契约 §2.2-§2.5）。
 *
 * 夹具 hermetic：真实样本逐字抄自 `<work-root>/packs/kojo-pack/demo.era-tw/1.0.3/pack.json`
 * 与线上 `index.json`（2026-10-19 快照缩编为 2 条目，键序与值保持原样），全部为测试内
 * 字符串常量——禁测试内联网、禁运行时读共享根。每条判据的 KDoc 带真源（market.ts /
 * manifest.ts / digest.ts）行号。
 */
class MarketVerifierImplTest {

    private val verifier = MarketVerifierImpl()

    private val now = 1_729_000_000_000L

    private fun packOk(json: String): PackManifest {
        val r = verifier.parsePackManifest(json)
        assertTrue("应合法：${problem(r)}", r is MarketParseResult.Ok)
        return (r as MarketParseResult.Ok).value
    }

    /** Invalid 给 problem 人话；Ok 给空串（负例循环靠 isNotEmpty 才能发现「该拒没拒」）。 */
    private fun problem(r: MarketParseResult<*>): String =
        (r as? MarketParseResult.Invalid)?.problem ?: ""

    private fun packProblem(json: String): String = problem(verifier.parsePackManifest(json))

    private fun indexOk(json: String, at: Long = now): MarketIndex {
        val r = verifier.parseIndex(json, at)
        assertTrue("应合法：${problem(r)}", r is MarketParseResult.Ok)
        return (r as MarketParseResult.Ok).value
    }

    private fun indexProblem(json: String): String =
        problem(verifier.parseIndex(json, now))

    // ------------------------------------------------------------------
    // 夹具（hermetic 字符串常量；真实样本抄录——常量本体在文件顶层，见下）
    // ------------------------------------------------------------------

    /** 真实 pack.json 逐字抄录（kojo-market demo.era-tw 1.0.3）。 */
    private val realPackJson = """
        {
          "schema": 1,
          "kind": "kojo-pack",
          "id": "demo.era-tw",
          "version": "1.0.3",
          "name": "演示口上包（合成）",
          "author": "kojo-market CI 冒烟",
          "license": "CC0-1.0（合成演示内容，可自由再配布）",
          "redistribute": true,
          "sourceUrl": "https://github.com/Poboking/kojo-market",
          "summary": "market v1 全链路测试（webhook 闭环验证）用合成口上包，无真实游戏文本",
          "minAppVersion": "0.4.0",
          "gameId": "eraTW",
          "files": [
            {
              "path": "files/M_KOJO_K900_DEMO.ERB",
              "bytes": 471,
              "sha256": "$SHA_ERA"
            }
          ]
        }
    """.trimIndent()

    /** 线上 index.json 缩编（demo.dict + demo.era-tw，值为 2026-10-19 线上快照原样）。 */
    private val realIndexJson = indexJson(ENTRY_DICT, ENTRY_ERA)

    private fun indexJson(vararg entries: String): String =
        "{\n  \"schema\": 1,\n  \"generatedAt\": \"2026-10-19T08:40:41.000Z\",\n  \"packs\": [\n    " +
            entries.joinToString(",\n    ") + "\n  ]\n}"

    private fun coverJson(url: String, bytes: String): String =
        "{\"url\": \"$url\", \"bytes\": $bytes}"

    private fun filesJson(path: String, bytes: String, sha: String): String =
        "[{\"path\": \"$path\", \"bytes\": $bytes, \"sha256\": \"$sha\"}]"

    /** 组装一份 pack.json：默认九键齐全；overrides 按键改值，`omit` 整键省略（测必填缺失）。 */
    private fun manifestJson(
        vararg overrides: Pair<String, String>,
        omit: Set<String> = emptySet(),
    ): String {
        val fields = mapOf(
            "schema" to "1",
            "kind" to "\"kojo-pack\"",
            "id" to "\"demo.era-tw\"",
            "version" to "\"1.0.3\"",
            "name" to "\"演示口上包（合成）\"",
            "author" to "\"kojo-market CI 冒烟\"",
            "license" to "\"CC0-1.0\"",
            "redistribute" to "true",
            "minAppVersion" to "\"0.4.0\"",
            "files" to filesJson("files/a.txt", "1", SHA_OK),
        ).toMutableMap()
        for ((key, value) in overrides) fields[key] = value
        val fragments = fields.entries
            .filter { it.key !in omit }
            .map { "\"${it.key}\": ${it.value}" }
        return "{\n  " + fragments.joinToString(",\n  ") + "\n}"
    }

    // ------------------------------------------------------------------
    // pack.json（契约 §2.2）
    // ------------------------------------------------------------------

    @Test
    fun `pack_真实样本全字段通过`() {
        val m = packOk(realPackJson)
        assertEquals(1, m.schema)
        assertEquals("kojo-pack", m.kind)
        assertEquals("demo.era-tw", m.id)
        assertEquals("1.0.3", m.version)
        assertEquals("演示口上包（合成）", m.name)
        assertEquals("kojo-market CI 冒烟", m.author)
        assertEquals("CC0-1.0（合成演示内容，可自由再配布）", m.license)
        assertEquals(true, m.redistribute)
        assertEquals("0.4.0", m.minAppVersion)
        assertEquals("https://github.com/Poboking/kojo-market", m.sourceUrl)
        assertEquals("market v1 全链路测试（webhook 闭环验证）用合成口上包，无真实游戏文本", m.summary)
        assertEquals("eraTW", m.gameId)
        assertEquals(1, m.files.size)
        assertEquals("files/M_KOJO_K900_DEMO.ERB", m.files[0].path)
        assertEquals(471L, m.files[0].bytes)
        assertEquals(SHA_ERA, m.files[0].sha256)
    }

    @Test
    fun `pack_必填九键缺失各一负例`() {
        // 必填九键 = kind,id,version,name,author,license,redistribute,minAppVersion,files
        // （schema 单列一条，见下一用例；判据 = 契约 §2.2 + manifest.ts:62-149 的必填面）
        val required = listOf(
            "kind", "id", "version", "name", "author", "license",
            "redistribute", "minAppVersion", "files",
        )
        for (key in required) {
            val p = packProblem(manifestJson(omit = setOf(key)))
            assertTrue("缺 $key 应拒（实得：$p）", p.isNotEmpty())
        }
    }

    @Test
    fun `pack_schema不为1拒`() {
        // manifest.ts:64-70：schema 严格等于 1；字符串与布尔都不等价于数字 1
        for (bad in listOf("0", "2", "\"1\"", "true", "null", "1.5")) {
            assertTrue("schema=$bad 应拒", packProblem(manifestJson("schema" to bad)).contains("schema"))
        }
        assertTrue(packProblem(manifestJson(omit = setOf("schema"))).contains("schema"))
    }

    @Test
    fun `pack_id正则闸`() {
        // market.ts:161-162 PACK_ID_RE：小写、首字符字母数字、可含 . _ -、总长不超过 64
        val ok = listOf(
            "a", "0", "demo.era-tw", "a-b_c.d",
            "a" + "9".repeat(63), // 恰 64 字符
        )
        for (v in ok) assertEquals("id=$v 应过", v, packOk(manifestJson("id" to "\"$v\"")).id)
        val bad = listOf(
            "A", "-a", ".a", "_a", "", "a b", "的", "a/B", "a:b",
            "aa" + "9".repeat(63), // 65 字符
        )
        for (v in bad) assertTrue("id=$v 应拒", packProblem(manifestJson("id" to "\"$v\"")).contains("id"))
    }

    @Test
    fun `pack_version三段与段长闸`() {
        // market.ts:164-165 严格三段数字 + 契约 §2.2 段不超过 9 位；前导零合法（TS 同款）
        for (v in listOf("0.0.0", "999.999.999", "01.02.03", "123456789.0.0")) {
            assertEquals("version=$v 应过", v, packOk(manifestJson("version" to "\"$v\"")).version)
        }
        for (v in listOf("1.0", "1.0.0.0", "1..0", "a.b.c", "1.0.0-rc1", "", "1.0.x", "1234567890.0.0")) {
            assertTrue("version=$v 应拒", packProblem(manifestJson("version" to "\"$v\"")).contains("版本"))
        }
    }

    @Test
    fun `pack_sha256形态闸`() {
        // market.ts:170-171 SHA256_HEX_RE（64 位小写 hex；契约 §2.2 冻结大写直接拒）
        packOk(manifestJson("files" to filesJson("files/a.txt", "1", SHA_OK)))
        val bad = listOf("AB".repeat(32), SHA_OK.dropLast(1), SHA_OK + "a", SHA_OK.replace("0", "g"))
        for (sha in bad) {
            assertTrue(
                "sha=[${sha.take(12)}...] 应拒",
                packProblem(manifestJson("files" to filesJson("files/a.txt", "1", sha))).contains("sha256"),
            )
        }
        assertTrue(
            packProblem(manifestJson("files" to filesJson("files/a.txt", "1", ""))).contains("sha256"),
        )
    }

    @Test
    fun `pack_redistribute布尔硬闸`() {
        // manifest.ts:87-97：必须是布尔（缺省即不合法）；false = 未许可二次分发，拒
        assertEquals(true, packOk(manifestJson("redistribute" to "true")).redistribute)
        val p = packProblem(manifestJson("redistribute" to "false"))
        assertTrue(p.contains("二次分发") || p.contains("分发"))
        assertTrue(packProblem(manifestJson(omit = setOf("redistribute"))).contains("布尔"))
        assertTrue(packProblem(manifestJson("redistribute" to "\"true\"")).contains("布尔"))
        assertTrue(packProblem(manifestJson("redistribute" to "1")).contains("布尔"))
    }

    @Test
    fun `pack_契约外键拒`() {
        // 契约 §2.2「契约外键拒（pack.json 顶层）」；market.ts 无此闸（组长裁定，骨架
        // PackManifest KDoc「契约外键在解析期拒」）
        val withExtra = manifestJson().replace("{\n  ", "{\n  \"extra\": 1,\n  ")
        assertTrue(packProblem(withExtra).contains("契约外"))
        val withTypo = manifestJson().replace("\"redistribute\"", "\"redistriute\"")
        assertTrue(packProblem(withTypo).contains("契约外"))
    }

    @Test
    fun `pack_路径闸负例逐条`() {
        // market.ts:672-689 packEntryPathProblem 的规则面逐条：空段、盘符与冒号、反斜杠、
        // 绝对路径、点段、上跳、段尾点或空格、files 前缀、裸 files、深度；末条 = 契约 §2.2
        // 的 512 字节闸
        val bad = listOf(
            "",
            "files//a.txt",
            "C:/x/a.txt",
            "files/a:b.txt",
            "files\\\\a.txt",
            "/files/a.txt",
            "files/./a.txt",
            "files/../a.txt",
            "files/a.txt.",
            "files/a.txt ",
            "other/a.txt",
            "files",
            "files/a/a/a/a/a/a/a/a/a/a/a/a/a.txt",
            "files/" + "x".repeat(507),
        )
        for (path in bad) {
            val p = packProblem(manifestJson("files" to filesJson(path, "1", SHA_OK)))
            assertTrue("path 应拒（实得：$p）：$path", p.isNotEmpty())
        }
    }

    @Test
    fun `pack_路径闸边界合法`() {
        // 恰 12 段（market.ts:686-687 的深度边界）与恰 512 字节（契约 §2.2 worker 口径）
        val depthOk = "files/" + "a/".repeat(10) + "a.txt" // files + 10 段 + 文件名 = 12 段
        val bytesOk = "files/" + "x".repeat(506)
        packOk(manifestJson("files" to filesJson(depthOk, "1", SHA_OK)))
        packOk(manifestJson("files" to filesJson(bytesOk, "1", SHA_OK)))
    }

    @Test
    fun `pack_bytes与条目数与总量闸`() {
        // market.ts:705 bytes 非负整数、market.ts:695 条目数上限、market.ts:708 累计上限
        packOk(manifestJson("files" to filesJson("files/a.txt", "0", SHA_OK)))
        assertTrue(packProblem(manifestJson("files" to filesJson("files/a.txt", "-1", SHA_OK))).contains("bytes"))
        assertTrue(packProblem(manifestJson("files" to filesJson("files/a.txt", "1.5", SHA_OK))).contains("bytes"))
        val half = 67108864L
        val two = "[{\"path\": \"files/a.bin\", \"bytes\": $half, \"sha256\": \"$SHA_OK\"}," +
            "{\"path\": \"files/b.bin\", \"bytes\": $half, \"sha256\": \"$SHA_OK\"}]"
        packOk(manifestJson("files" to two)) // 恰 128 MiB 不过闸
        val over = two.replace("files/b.bin\", \"bytes\": $half", "files/b.bin\", \"bytes\": ${half + 1}")
        assertTrue(packProblem(manifestJson("files" to over)).contains("总量"))
        val many = (0 until MarketContract.MAX_FILE_ENTRIES)
            .joinToString(",", prefix = "[", postfix = "]") {
                "{\"path\": \"files/f$it.txt\", \"bytes\": 1, \"sha256\": \"$SHA_OK\"}"
            }
        packOk(manifestJson("files" to many)) // 恰 5000 条不过闸
        val tooMany = many.dropLast(1) + ",{\"path\": \"files/extra.txt\", \"bytes\": 1, \"sha256\": \"$SHA_OK\"}]"
        assertTrue(packProblem(manifestJson("files" to tooMany)).contains("条目数"))
    }

    @Test
    fun `pack_重复路径拒`() {
        // market.ts:703-704 同一路径出现两次即拒
        val dup = "[{\"path\": \"files/a.txt\", \"bytes\": 1, \"sha256\": \"$SHA_OK\"}," +
            "{\"path\": \"files/a.txt\", \"bytes\": 2, \"sha256\": \"$SHA_OK\"}]"
        assertTrue(packProblem(manifestJson("files" to dup)).contains("两次"))
    }

    @Test
    fun `pack_首个问题即拒`() {
        // 契约 §2.2：不做「收集全部」——files[0] 的 sha 问题先报，files[1] 的路径问题不出现
        val two = "[{\"path\": \"files/a.txt\", \"bytes\": 1, \"sha256\": \"${"AB".repeat(32)}\"}," +
            "{\"path\": \"files/../evil.txt\", \"bytes\": 1, \"sha256\": \"$SHA_OK\"}]"
        val p = packProblem(manifestJson("files" to two))
        assertTrue(p.contains("files/a.txt"))
        assertTrue(!p.contains("evil"))
    }

    @Test
    fun `pack_可选字段形状`() {
        // manifest.ts:35-42 + 130-132：缺席 = null；在册必须是字符串（JSON null 也算在册）
        val m = packOk(manifestJson())
        assertEquals(null, m.summary)
        assertEquals(null, m.sourceUrl)
        assertEquals(null, m.gameId)
        // 空串是合法字符串（TS optString 只核类型不核非空）
        val emptySummary = manifestJson().replace("{\n  ", "{\n  \"summary\": \"\",\n  ")
        assertEquals("", packOk(emptySummary).summary)
        // summary 给数字与 null 都拒（存在时必须是字符串）
        val numSummary = manifestJson().replace("{\n  ", "{\n  \"summary\": 42,\n  ")
        assertTrue(packProblem(numSummary).contains("字符串"))
        val nullSummary = manifestJson().replace("{\n  ", "{\n  \"summary\": null,\n  ")
        assertTrue(packProblem(nullSummary).contains("字符串"))
    }

    @Test
    fun `pack_files形状面`() {
        // manifest.ts:111-124：files 必须是数组、条目必须是对象、path 非空、bytes 是数字、sha 非空
        assertTrue(packProblem(manifestJson("files" to "{}")).contains("数组"))
        assertTrue(packProblem(manifestJson("files" to "[\"x\"]")).contains("files[0]"))
        assertTrue(
            packProblem(manifestJson("files" to "[{\"bytes\": 1, \"sha256\": \"$SHA_OK\"}]")).contains("path"),
        )
        val bytesNotNumber =
            manifestJson("files" to "[{\"path\": \"files/a.txt\", \"bytes\": \"1\", \"sha256\": \"$SHA_OK\"}]")
        assertTrue(packProblem(bytesNotNumber).contains("bytes"))
    }

    // ------------------------------------------------------------------
    // index.json（契约 §2.3）
    // ------------------------------------------------------------------

    @Test
    fun `index_真实缩编样本全字段通过`() {
        val ix = indexOk(realIndexJson)
        assertEquals(1, ix.schema)
        assertEquals("2026-10-19T08:40:41.000Z", ix.generatedAt)
        assertEquals(now, ix.fetchedAtMs)
        assertEquals(0, ix.dropped)
        assertEquals(0, ix.coverIgnored)
        assertEquals(2, ix.packs.size)
        val d = ix.packs[0]
        assertEquals("dict-pack", d.kind)
        assertEquals("demo.dict", d.id)
        assertEquals("1.0.0", d.version)
        assertEquals("演示名称表（合成）", d.name)
        assertEquals("kojo-market CI 冒烟", d.author)
        assertEquals("CC0-1.0（合成演示内容，可自由再配布）", d.license)
        assertEquals("market v1 端到端冒烟用合成名称表，无真实游戏文本", d.summary)
        assertEquals("0.4.0", d.minAppVersion)
        assertEquals("eraTW", d.gameId)
        assertEquals("https://github.com/Poboking/kojo-market", d.sourceUrl)
        assertEquals(ZIP_URL_DICT, d.zipUrl)
        assertEquals(SHA_DICT, d.zipSha256)
        assertEquals(709L, d.zipBytes)
        assertEquals(1, d.fileCount)
        assertEquals(77L, d.unpackedBytes)
        assertEquals(null, d.coverUrl)
        // 线上索引没有 redistribute 字段：缺席 = 收下并按 true 记（骨架 MarketTypes.kt:25）
        assertEquals(true, d.redistribute)
        val e = ix.packs[1]
        assertEquals("demo.era-tw", e.id)
        assertEquals("1.0.3", e.version)
        assertEquals(ZIP_URL_ERA, e.zipUrl)
        assertEquals(1084L, e.zipBytes)
        assertEquals(471L, e.unpackedBytes)
    }

    @Test
    fun `index_整索引闸`() {
        // manifest.ts:280-284：schema / generatedAt / packs 任一不合格 = 整索引拒（不是丢条目）
        val base = indexJson(ENTRY_DICT)
        assertTrue(indexProblem(base.replace("\"schema\": 1", "\"schema\": 2")).contains("schema"))
        assertTrue(indexProblem("{\"schema\": 1, \"packs\": []}").contains("generatedAt"))
        assertTrue(indexProblem("{\"schema\": 1, \"generatedAt\": \"t\"}").contains("packs"))
        assertTrue(indexProblem("{\"schema\": 1, \"generatedAt\": \"t\", \"packs\": {}}").contains("packs"))
        assertTrue(indexProblem("[]").contains("对象"))
        assertTrue(indexProblem("\"x\"").contains("对象"))
        assertTrue(indexProblem("{\"generatedAt\": \"t\", \"packs\": []}").contains("schema"))
    }

    @Test
    fun `index_单条目字段闸各计一条dropped`() {
        // manifest.ts:226-256 indexEntryProblem：单条不合法 = 丢这一条并计数（:288-294），
        // 不让一个坏包打死整站；manifest.ts:249-252 的 fileCount/unpackedBytes 非负
        val cases = listOf(
            "\"kind\":\"dict-pack\"" to "\"kind\":\"nope\"",
            "\"id\":\"demo.dict\"" to "\"id\":\"Demo.Dict\"",
            "\"version\":\"1.0.0\"" to "\"version\":\"1\"",
            "\"minAppVersion\":\"0.4.0\"" to "\"minAppVersion\":\"0.4\"",
            "\"name\":\"演示名称表（合成）\"" to "\"name\":\"\"",
            "\"author\":\"kojo-market CI 冒烟\"" to "\"author\":\"\"",
            "\"license\":\"CC0-1.0（合成演示内容，可自由再配布）\"" to "\"license\":\"\"",
            "\"zip\":{" to "\"ziip\":{",
            ZIP_URL_DICT to "",
            "\"sha256\":\"$SHA_DICT\"" to "\"sha256\":\"${"AB".repeat(32)}\"",
            "\"bytes\":709" to "\"bytes\":0",
            "\"bytes\":709" to "\"bytes\":-1",
            "\"bytes\":709" to "\"bytes\":709.5",
            "\"fileCount\":1" to "\"fileCount\":-1",
            "\"unpackedBytes\":77" to "\"unpackedBytes\":-77",
        )
        for ((from, to) in cases) {
            val ix = indexOk(indexJson(ENTRY_DICT.replace(from, to), ENTRY_ERA))
            assertEquals("负例应恰好丢一条：$from → $to", 1, ix.dropped)
            assertEquals(1, ix.packs.size)
            assertEquals("demo.era-tw", ix.packs[0].id)
        }
    }

    @Test
    fun `index_zip_url形态闸`() {
        // 契约 §2.3：url 必须 https、有主机、无 userinfo（三条判据 = market.ts:748-759）
        val cases = listOf(
            "http://github.com/x.zip", // 协议
            "https:///x.zip", // 没有主机名
            "https://u:p@github.com/x.zip", // userinfo
            "ftp://github.com/x.zip", // 协议
        )
        for (url in cases) {
            val ix = indexOk(indexJson(ENTRY_DICT.replace(ZIP_URL_DICT, url)))
            assertEquals("url=$url 应丢一条", 1, ix.dropped)
            assertEquals("单条目索引丢完即空", 0, ix.packs.size)
        }
    }

    @Test
    fun `index_redistribute_false判弃计dropped`() {
        // 契约 §2.3 + 骨架 MarketTypes.kt:25：索引侧不该出现 false，出现即整条判弃；
        // 在册非布尔同样判弃；缺席 = 收（线上索引无此字段，见真实样本用例）
        val withFalse = ENTRY_DICT.replace(
            "\"kind\":\"dict-pack\"",
            "\"kind\":\"dict-pack\",\"redistribute\":false",
        )
        assertEquals(1, indexOk(indexJson(withFalse)).dropped)
        val withString = ENTRY_DICT.replace(
            "\"kind\":\"dict-pack\"",
            "\"kind\":\"dict-pack\",\"redistribute\":\"true\"",
        )
        assertEquals(1, indexOk(indexJson(withString)).dropped)
        val withTrue = ENTRY_DICT.replace(
            "\"kind\":\"dict-pack\"",
            "\"kind\":\"dict-pack\",\"redistribute\":true",
        )
        val ix = indexOk(indexJson(withTrue))
        assertEquals(0, ix.dropped)
        assertEquals(true, ix.packs[0].redistribute)
    }

    @Test
    fun `index_可选字符串字段形状`() {
        // Kotlin 字段面（String?）类型驱动：summary 为数字 = 判弃；JsonNull = 与缺席等价保留
        val num = ENTRY_DICT.replace(
            "\"summary\":\"market v1 端到端冒烟用合成名称表，无真实游戏文本\"",
            "\"summary\":42",
        )
        assertEquals(1, indexOk(indexJson(num)).dropped)
        val nullSummary = ENTRY_DICT.replace(
            "\"summary\":\"market v1 端到端冒烟用合成名称表，无真实游戏文本\"",
            "\"summary\":null",
        )
        val ix = indexOk(indexJson(nullSummary))
        assertEquals(0, ix.dropped)
        assertEquals(null, ix.packs[0].summary)
    }

    @Test
    fun `index_id_kind冲突整组判弃`() {
        // market.ts:831-842 + service.ts:410-432：同 id 异 kind 的条目全部不呈现（不许择一）
        val sameIdOtherKind = ENTRY_DICT.replace("\"kind\":\"dict-pack\"", "\"kind\":\"kojo-pack\"")
        val ix = indexOk(indexJson(ENTRY_DICT, sameIdOtherKind))
        assertEquals(2, ix.dropped)
        assertEquals(0, ix.packs.size)
        // 同 id 同 kind 重复不是冲突（A4 只管跨 kind）
        val dup = indexOk(indexJson(ENTRY_DICT, ENTRY_DICT))
        assertEquals(0, dup.dropped)
        assertEquals(2, dup.packs.size)
    }

    @Test
    fun `index_冲突条目的封面注记一并抹掉`() {
        // service.ts:428-430：整条被拦的包不再留「照常列出、只丢字段」的注记（互相矛盾）
        val badCover = ENTRY_DICT.replace(
            "\"kind\":\"dict-pack\"",
            "\"kind\":\"dict-pack\",\"cover\":{\"url\": \"http://e.com/c.png\", \"bytes\": 1}",
        )
        val sameIdOtherKind = ENTRY_DICT.replace("\"kind\":\"dict-pack\"", "\"kind\":\"kojo-pack\"")
        val ix = indexOk(indexJson(badCover, sameIdOtherKind))
        assertEquals(2, ix.dropped)
        assertEquals(0, ix.coverIgnored)
    }

    @Test
    fun `index_封面闸_合法保留`() {
        // market.ts:787-808 边界内合法：https 直链、url 不超 2048 字符、bytes 1..256KiB
        val good = ENTRY_DICT.replace(
            "\"kind\":\"dict-pack\"",
            "\"kind\":\"dict-pack\",\"cover\":${coverJson("https://e.com/c.png", "262144")}",
        )
        val ix = indexOk(indexJson(good))
        assertEquals(0, ix.coverIgnored)
        assertEquals("https://e.com/c.png", ix.packs[0].coverUrl)
    }

    @Test
    fun `index_封面闸_不合格丢封面保留条目`() {
        // 封面只是装饰字段：不合格 = coverIgnored 出声 + 条目保留无封面（market.ts:253-255，
        // 「装饰字段没有让一个包从列表里蒸发的权力」）
        val cases = listOf(
            coverJson("http://e.com/c.png", "1"), // 协议
            coverJson("", "1"), // url 空
            coverJson("https://e.com/c.png", "0"), // bytes 非正
            coverJson("https://e.com/c.png", "262145"), // bytes 超上限
            coverJson("https://u:p@e.com/c.png", "1"), // userinfo
            "null", // 在册 null 也算不合格（TS entry.cover !== undefined 口径）
            "{\"bytes\": 1}", // url 缺失
            "\"x\"", // 不是对象
            coverJson("https://" + "a".repeat(2050) + ".com/c.png", "1"), // url 超长
        )
        for (cover in cases) {
            val entry = ENTRY_DICT.replace("\"kind\":\"dict-pack\"", "\"kind\":\"dict-pack\",\"cover\":$cover")
            val ix = indexOk(indexJson(entry, ENTRY_ERA))
            assertEquals("cover=$cover 应记 coverIgnored", 1, ix.coverIgnored)
            assertEquals("条目应保留", 2, ix.packs.size)
            assertEquals(null, ix.packs[0].coverUrl)
        }
    }

    @Test
    fun `index_索引非法JSON整索引拒`() {
        assertTrue(indexProblem("{\"schema\": 1, \"generatedAt\": \"t\", \"packs\": [").isNotEmpty())
        assertTrue(indexProblem("").isNotEmpty())
    }

    // ------------------------------------------------------------------
    // 版本比较与 minAppVersion（契约 §2.4）
    // ------------------------------------------------------------------

    @Test
    fun `versionCompare_数值口径`() {
        // market.ts:716-732：逐段数值比较，非字典序；9 位段不溢出
        assertTrue(verifier.versionCompare("1.0.0", "1.0.1") < 0)
        assertTrue(verifier.versionCompare("1.2.3", "1.10.0") < 0)
        assertEquals(0, verifier.versionCompare("0.2.46", "0.2.46"))
        assertTrue(verifier.versionCompare("2.0.0", "1.9.9") > 0)
        assertTrue(verifier.versionCompare("999999999.0.0", "999999999.0.1") < 0)
        assertTrue(verifier.versionCompare("999999999.999999999.999999999", "999999999.999999999.999999998") > 0)
        assertEquals(0, verifier.versionCompare("01.0.0", "1.0.0")) // 前导零按数值
    }

    @Test
    fun `versionCompare_非法形态按编程错误抛`() {
        // 骨架前置条件「入参必须已过 parse 校验」：非法形态抛 IllegalArgumentException
        for (bad in listOf("1.0", "1.0.0.0", "1.0.0-rc1", "a.b.c", "1234567890.0.0", "")) {
            val threw = runCatching { verifier.versionCompare(bad, "1.0.0") }.isFailure
            assertTrue("versionCompare($bad) 应抛", threw)
        }
    }

    @Test
    fun `minAppVersionSatisfied_剥预发布段等价`() {
        // market.ts:853-859：minAppVersion 三段数字；当前版本剥预发布段（:734-738）后 >= min
        assertTrue(verifier.minAppVersionSatisfied("1.0.0", "1.0.0"))
        assertTrue(verifier.minAppVersionSatisfied("1.0.0", "1.0.0-rc1")) // 剥后等价
        assertTrue(verifier.minAppVersionSatisfied("0.4.0", "0.4.0-beta.1"))
        assertTrue(verifier.minAppVersionSatisfied("1.0.0", "2.0.0-beta.1"))
        assertTrue(!verifier.minAppVersionSatisfied("0.4.0", "0.3.9"))
        assertTrue(!verifier.minAppVersionSatisfied("0.4.0", "0.3.9-rc1"))
        assertTrue(!verifier.minAppVersionSatisfied("1.0", "1.0.0")) // min 形态非法
        assertTrue(!verifier.minAppVersionSatisfied("1.0.0", "x.y.z")) // 当前版本核心非法
        assertTrue(!verifier.minAppVersionSatisfied("1234567890.0.0", "999.0.0")) // 10 位段比不了 = false
        // GATE2D 门③判据单源钉子：min>app 必拒、min≤app 必放（参数序钉 (min,app)）
        assertFalse(verifier.minAppVersionSatisfied(minAppVersion = "0.5.0", appVersion = "0.4.0"))
        assertTrue(verifier.minAppVersionSatisfied(minAppVersion = "0.4.0", appVersion = "0.5.0"))
        assertTrue(verifier.minAppVersionSatisfied(minAppVersion = "0.4.0", appVersion = "0.4.0"))
    }

    // ------------------------------------------------------------------
    // contentDigest 与 sha256 形态（契约 §2.5）
    // ------------------------------------------------------------------

    @Test
    fun `contentDigest_黄金向量与排序口径`() {
        // 排序口径钉死（digest.ts:71-77）：行 = path + NUL + sha256，按字典序排，
        // 行间以单元分隔符连接，UTF-8 后 SHA-256；黄金向量由独立实现（hashlib）预算
        val two = listOf(
            "files/alpha.txt" to "1".repeat(64),
            "files/beta.txt" to "2".repeat(64),
        )
        assertEquals(
            "f95c9e2c819d151a7e6eb1d9099d374f3148bef29aa9a238774e8bb5553ff075",
            verifier.contentDigest(two),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            verifier.contentDigest(emptyList()),
        )
        assertEquals(
            "15ca2b00b2e525e9793b1a7c6259d33001bf30f9c4f8aa7f9bee20f97b0dd84a",
            verifier.contentDigest(listOf("files/M_KOJO_K900_DEMO.ERB" to SHA_ERA)),
        )
        // 排序敏感性：乱序输入同 digest（乱序喂入与有序喂入结果一致）
        assertEquals(verifier.contentDigest(two), verifier.contentDigest(two.reversed()))
        val four = two + listOf("files/aaa.txt" to "3".repeat(64), "files/zzz.txt" to "4".repeat(64))
        assertEquals(verifier.contentDigest(four), verifier.contentDigest(four.shuffled()))
    }

    @Test
    fun `contentDigest_分隔符钉死`() {
        // 契约 §2.5：path 含分隔符的路径已被路径闸拒，此处不防御，但要钉「分隔符真实在场」
        // ——若无 NUL 分隔，("ab","c") 与 ("a","bc") 会折叠出同一串；有分隔符则必然不同
        val withSep = verifier.contentDigest(listOf("ab" to "c"))
        val withoutSepShape = verifier.contentDigest(listOf("a" to "bc"))
        assertNotEquals(withSep, withoutSepShape)
        // 同理：内容不同（哪怕只差一个 sha 位）digest 必不同
        assertNotEquals(
            verifier.contentDigest(listOf("files/a.txt" to SHA_OK)),
            verifier.contentDigest(listOf("files/a.txt" to SHA_ERA)),
        )
    }

    @Test
    fun `isSha256Hex_形态闸`() {
        assertTrue(verifier.isSha256Hex(SHA_OK))
        assertTrue(!verifier.isSha256Hex("AB".repeat(32))) // 大写拒
        assertTrue(!verifier.isSha256Hex(SHA_OK.dropLast(1))) // 63 位
        assertTrue(!verifier.isSha256Hex(SHA_OK + "0")) // 65 位
        assertTrue(!verifier.isSha256Hex("g".repeat(64)))
        assertTrue(!verifier.isSha256Hex(""))
    }

    // ------------------------------------------------------------------
    // overrideFunctions 形状闸（R7-① 可选收窄声明；语义闸在导入期转码器）
    // ------------------------------------------------------------------

    @Test
    fun `overrideFunctions_缺席与显式null都等于未声明`() {
        // 两者语义一致（都走自动推导），不该让作者以为 null 有额外含义。
        assertNull("缺席 = 未声明", packOk(manifestJson()).overrideFunctions)
        assertNull("null = 未声明", packOk(manifestJson("overrideFunctions" to "null")).overrideFunctions)
    }

    @Test
    fun `overrideFunctions_空数组合法且不等于未声明`() {
        // [] 是显式声明「本包不覆盖任何函数」，不能被折成 null——那是两句话。
        val m = packOk(manifestJson("overrideFunctions" to "[]"))
        assertEquals(emptyList<String>(), m.overrideFunctions)
    }

    @Test
    fun `overrideFunctions_合法名单原样透传_归一留给转码期`() {
        val m = packOk(manifestJson("overrideFunctions" to "[\"A_ONE\",\"b_two\"]"))
        assertEquals("解析层不改写大小写", listOf("A_ONE", "b_two"), m.overrideFunctions)
    }

    @Test
    fun `overrideFunctions_非数组拒装并给人话`() {
        val p = packProblem(manifestJson("overrideFunctions" to "\"A_ONE\""))
        assertTrue("要点名字段与数组：$p", p.contains("overrideFunctions") && p.contains("数组"))
    }

    @Test
    fun `overrideFunctions_数组含非字符串拒装并点名下标`() {
        val p = packProblem(manifestJson("overrideFunctions" to "[\"A_ONE\",7]"))
        assertTrue("要点名下标：$p", p.contains("overrideFunctions[1]"))
    }

    @Test
    fun `overrideFunctions_数组含空串拒装`() {
        val p = packProblem(manifestJson("overrideFunctions" to "[\"A_ONE\",\"\"]"))
        assertTrue("要点名非空字符串要求：$p", p.contains("必须是函数名"))
    }
}
