package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 测试域共用的假校验器与夹具构造器（本波 pipe 路的测试注入缝，CONTRACT §1）。
 *
 * parseIndex / parsePackManifest 用**测试专用行式格式**（域路 MarketVerifierImpl 才是
 * JSON 权威实现；本假实现只服务管线/服务层的注入，不承担 JSON 语法覆盖）。
 * versionCompare / minAppVersionSatisfied / contentDigest / isSha256Hex 按
 * market.ts 与 digest.ts 的口径给真实现（纯函数，几行而已）。
 */
internal class FakeVerifier : MarketVerifier {

    /** index 行格式：`entry|kind|id|version|name|author|license|redistribute| */
    /** minAppVersion|zipUrl|zipSha256|zipBytes|fileCount|unpackedBytes`（一根竖线分 14 段）。 */
    override fun parseIndex(json: String, fetchedAtMs: Long): MarketParseResult<MarketIndex> = when {
        !json.startsWith("#mkm-index") -> MarketParseResult.Invalid("不认识的索引格式")
        else -> when (val problem = indexProblem(json)) {
            null -> MarketParseResult.Ok(buildIndex(json, fetchedAtMs))
            else -> MarketParseResult.Invalid(problem)
        }
    }

    /** 行扫：遇到 INVALID| 行返回其人话（null = 全部行可收）。 */
    private fun indexProblem(json: String): String? =
        json.lineSequence().firstOrNull { it.startsWith("INVALID|") }?.let { it.removePrefix("INVALID|") }

    private fun buildIndex(json: String, fetchedAtMs: Long): MarketIndex = MarketIndex(
        schema = 1,
        generatedAt = null,
        packs = json.lineSequence().filter { it.startsWith("entry|") }.map(::parseIndexEntry).toList(),
        dropped = 0,
        coverIgnored = 0,
        fetchedAtMs = fetchedAtMs,
    )

    /** pack.json 行格式：`key=value` 逐行 + `file=path,bytes,sha256`。 */
    override fun parsePackManifest(json: String): MarketParseResult<PackManifest> = when {
        !json.startsWith("#mkm-pack") -> MarketParseResult.Invalid("不认识的清单格式")
        else -> when (val problem = manifestProblem(json)) {
            null -> MarketParseResult.Ok(readManifestFields(json))
            else -> MarketParseResult.Invalid(problem)
        }
    }

    /** 行扫：遇到 INVALID= 行返回其人话（null = 全部行可收）。 */
    private fun manifestProblem(json: String): String? =
        json.lineSequence().firstOrNull { it.startsWith("INVALID=") }?.let { it.removePrefix("INVALID=") }

    private fun readManifestFields(json: String): PackManifest {
        val fields = HashMap<String, String>()
        val files = ArrayList<PackManifestFile>()
        for (line in json.lineSequence()) {
            when {
                line.startsWith("file=") -> {
                    val (path, bytes, sha) = line.removePrefix("file=").split(',').map { it.trim() }
                    files += PackManifestFile(path, bytes.toLong(), sha)
                }
                else -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { fields[it[0]] = it[1] }
            }
        }
        return PackManifest(
            schema = fields.getValue("schema").toInt(),
            kind = fields.getValue("kind"),
            id = fields.getValue("id"),
            version = fields.getValue("version"),
            name = fields.getValue("name"),
            author = fields.getValue("author"),
            license = fields.getValue("license"),
            redistribute = fields.getValue("redistribute").toBoolean(),
            minAppVersion = fields.getValue("minAppVersion"),
            summary = fields["summary"],
            sourceUrl = fields["sourceUrl"],
            gameId = fields["gameId"],
            files = files,
        )
    }

    private fun parseIndexEntry(line: String): MarketIndexEntry {
        val f = line.split('|')
        return MarketIndexEntry(
            kind = f[1],
            id = f[2],
            version = f[3],
            name = f[4],
            author = f[5],
            license = f[6],
            redistribute = f[7].toBoolean(),
            minAppVersion = f[8],
            summary = null,
            sourceUrl = null,
            gameId = null,
            zipUrl = f[9],
            zipSha256 = f[10],
            zipBytes = f[11].toLong(),
            fileCount = f[12].toInt(),
            unpackedBytes = f[13].toLong(),
            coverUrl = null,
        )
    }

    /** 严格三段数字版本比较（段各 ≤9 位；形态非法直接抛，由调用方折 Failed）。 */
    override fun versionCompare(a: String, b: String): Int {
        val pa = splitVersion(a)
        val pb = splitVersion(b)
        for (i in 0..2) {
            val c = pa[i].compareTo(pb[i])
            if (c != 0) return c
        }
        return 0
    }

    /** minAppVersion 闸：剥预发布段（`-` 之后）后三段比较。 */
    override fun minAppVersionSatisfied(minAppVersion: String, appVersion: String): Boolean {
        val strip = { v: String -> v.substringBefore('-') }
        return versionCompare(strip(appVersion), strip(minAppVersion)) >= 0
    }

    /** digest.ts 口径：按 path 排序，`${path}NUL${sha256}` 以单元分隔符连接后整体 SHA-256。 */
    override fun contentDigest(entries: List<Pair<String, String>>): String {
        val canonical = entries.sortedBy { it.first }
            .joinToString(separator = "\u0001") { "${it.first}\u0000${it.second}" }
        return sha256HexOf(canonical.toByteArray(Charsets.UTF_8))
    }

    override fun isSha256Hex(value: String): Boolean = SHA256_RE.matches(value)

    private fun splitVersion(v: String): List<Long> {
        val parts = v.split('.')
        require(parts.size == 3) { "版本号写法读不出：$v" }
        require(parts.all { it.isNotEmpty() && it.length <= 9 && it.all { c -> c.isDigit() } }) { "版本号写法读不出：$v" }
        return parts.map { it.toLong() }
    }

    private companion object {
        private val SHA256_RE = Regex("^[0-9a-f]{64}$")
    }
}

/** sha256 → 64 位小写 hex（测试侧与主实现同口径，locale 无关）。 */
internal fun sha256HexOf(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/** 索引条目夹具（字段给合法默认值，用例按需覆盖）。 */
internal fun indexEntry(
    kind: String = "kojo-pack",
    id: String = "demo",
    version: String = "1.0.0",
    zipUrl: String = "https://github.com/demo/demo-1.0.0.zip",
): MarketIndexEntry = MarketIndexEntry(
    kind = kind,
    id = id,
    version = version,
    name = "Demo",
    author = "author",
    license = "MIT",
    redistribute = true,
    minAppVersion = "0.0.1",
    summary = null,
    sourceUrl = null,
    gameId = null,
    zipUrl = zipUrl,
    zipSha256 = sha256HexOf(ByteArray(0)),
    zipBytes = 0L,
    fileCount = 1,
    unpackedBytes = 10L,
    coverUrl = null,
)

/**
 * 「注册表同款」zip 打包夹具：裸 deflate（nowrap）、声明尺寸真实、无数据描述符、
 * CRC 置 0（解压层不核 CRC，内容正确性由安装层逐文件 sha256 复核兜住）。
 * 不走 `ZipOutputStream`：JDK 的实现即便写文件也可能带数据描述符形态（本地头
 * 两段尺寸为 0、尾随描述符）——那正是解压闸按契约拒的形态（archive_guard.rs:186）。
 */
internal fun buildStandardZip(entries: List<Pair<String, ByteArray>>): ByteArray =
    FakeZipBuilder.build(entries.map { (name, payload) -> FakeZipBuilder.entry(name, payload) })

/**
 * commitPack 状态机的定向单测（CONTRACT §2.3 判据逐条）。
 * 反向证明锚：`readback sha mismatch` 用例摘掉暂存区读回复核即转红；
 * `staging left clean after failure` 系列摘掉 abort + 清暂存即转红。
 * 全链用例走本地 [TinyHttpServer]（裸 ServerSocket 假栈，见 MarketFetcherTest 的选型说明）
 * + 取数层测试缝。
 */
class MarketInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: MarketStore
    private val verifier = FakeVerifier()

    @Before
    fun setUp() {
        store = MarketStore(tmp.root)
    }

    /** 装一台「白名单内 URL → 本地 HttpServer」的取数器（闸照核真实 URL 字面）。 */
    private fun wiredFetcher(port: Int): MarketFetcher = MarketFetcher { url ->
        val local = URL("http://127.0.0.1:$port${url.path}")
        local.openConnection() as HttpURLConnection
    }

    /** 单路径 zip 假栈：命中 path 回容器字节，其余 404。 */
    private fun serveZip(path: String, wanted: String, zip: ByteArray): TinyHttpServer.Response =
        if (path == wanted) TinyHttpServer.body(200, zip) else TinyHttpServer.body(404, ByteArray(0))

    /** 清单文本（测试行式格式，FakeVerifier 解析）。 */
    private fun manifestText(
        files: List<PackManifestFile>,
        kind: String = "kojo-pack",
        id: String = "demo",
        version: String = "1.0.0",
    ): String = buildString {
        append("#mkm-pack\n")
        append("schema=1\n")
        append("kind=$kind\n")
        append("id=$id\n")
        append("version=$version\n")
        append("name=Demo\n")
        append("author=author\n")
        append("license=MIT\n")
        append("redistribute=true\n")
        append("minAppVersion=0.0.1\n")
        files.forEach { append("file=${it.path},${it.bytes},${it.sha256}\n") }
    }

    private fun parseManifest(text: String): PackManifest =
        (verifier.parsePackManifest(text) as MarketParseResult.Ok).value

    /** 造一个「解压完成」形态的源目录（pack.json + files 树）。 */
    private fun makeSourceDir(
        manifest: PackManifest,
        fileBodies: Map<String, ByteArray>,
    ): File {
        val dir = tmp.newFolder("source-${System.nanoTime()}")
        val text = manifestText(manifest.files, manifest.kind, manifest.id, manifest.version)
        File(dir, MarketContract.MANIFEST_FILE).writeText(text)
        fileBodies.forEach { (path, body) ->
            val file = File(dir, path)
            file.parentFile?.mkdirs()
            file.writeBytes(body)
        }
        return dir
    }

    private fun fileAnchor(path: String, body: ByteArray): PackManifestFile =
        PackManifestFile(path, body.size.toLong(), sha256HexOf(body))

    private fun containerFacts(
        manifest: PackManifest,
        sourceDir: File,
        entry: MarketIndexEntry,
    ): MarketInstaller.ContainerFacts {
        val meta = MarketZip.EntryFacts(MarketContract.MANIFEST_FILE, "schema=1".toByteArray().size.toLong())
        val entries = listOf(meta) +
            manifest.files.map { MarketZip.EntryFacts(it.path, File(sourceDir, it.path).length()) }
        return MarketInstaller.ContainerFacts.fromContainerBytes(ByteArray(0), entries, entry)
    }

    /** 断言「结果为拒绝且码正确」（commitPack/installFromIndex 返回三态而非抛错）。 */
    private fun rejects(code: String, block: () -> InstallOutcome): InstallOutcome.Rejected {
        val outcome = block()
        assertTrue("期望拒绝 $code，实际 $outcome", outcome is InstallOutcome.Rejected)
        val rejected = outcome as InstallOutcome.Rejected
        assertEquals(code, rejected.code)
        return rejected
    }

    @Test
    fun `new install lays out pack json plus files tree and records ledger`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        val outcome = installer.commitPack(source, manifest, null)

        val pack = (outcome as InstallOutcome.Ok).pack
        assertEquals("demo", pack.id)
        assertEquals(1, pack.fileCount)
        assertEquals(5L, pack.bytes)
        // 落点布局（W1 裁定：pack.json + files 树）
        val target = store.dirOf("kojo-pack", "demo", "1.0.0")
        assertTrue(File(target, MarketContract.MANIFEST_FILE).isFile)
        assertEquals("alpha", File(target, "files/a.txt").readText())
        // 记账 + 暂存区清空
        assertEquals(listOf(pack), store.readRecords())
        assertTrue(store.stagingRoot.listFiles()?.isEmpty() ?: true)
        // contentDigest 与假校验器同口径
        assertEquals(verifier.contentDigest(listOf("files/a.txt" to sha256HexOf(bodyA))), pack.contentDigest)
    }

    @Test
    fun `container identity mismatch rejected`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val facts = containerFacts(manifest, source, indexEntry(kind = "dict-pack"))
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        val outcome = rejects(MarketRejectCodes.MANIFEST_MISMATCH) { installer.commitPack(source, manifest, facts) }
        assertTrue(outcome.detail.contains("不是同一个包"))
    }

    @Test
    fun `manifest declared but container missing entry rejected`() {
        val bodyA = "alpha".toByteArray()
        val anchors = listOf(fileAnchor("files/a.txt", bodyA), fileAnchor("files/b.txt", "b".toByteArray()))
        val manifest = parseManifest(manifestText(anchors))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())
        val entry = indexEntry()
        val facts = MarketInstaller.ContainerFacts.fromContainerBytes(
            ByteArray(0),
            listOf(MarketZip.EntryFacts(MarketContract.MANIFEST_FILE, 8L), MarketZip.EntryFacts("files/a.txt", 5L)),
            entry,
        )

        val outcome = rejects(MarketRejectCodes.MANIFEST_MISMATCH) { installer.commitPack(source, manifest, facts) }
        assertTrue(outcome.detail.contains("files/b.txt"))
    }

    @Test
    fun `container extra undeclared entry rejected`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())
        val facts = MarketInstaller.ContainerFacts.fromContainerBytes(
            ByteArray(0),
            listOf(
                MarketZip.EntryFacts(MarketContract.MANIFEST_FILE, 8L),
                MarketZip.EntryFacts("files/a.txt", 5L),
                MarketZip.EntryFacts("files/sneak.bin", 9L),
            ),
            indexEntry(),
        )

        val outcome = rejects(MarketRejectCodes.MANIFEST_MISMATCH) { installer.commitPack(source, manifest, facts) }
        assertTrue(outcome.detail.contains("files/sneak.bin"))
    }

    @Test
    fun `container bytes mismatch rejected`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())
        val facts = MarketInstaller.ContainerFacts.fromContainerBytes(
            ByteArray(0),
            listOf(MarketZip.EntryFacts(MarketContract.MANIFEST_FILE, 8L), MarketZip.EntryFacts("files/a.txt", 9L)),
            indexEntry(),
        )

        val outcome = rejects(MarketRejectCodes.MANIFEST_MISMATCH) { installer.commitPack(source, manifest, facts) }
        assertTrue(outcome.detail.contains("9B"))
    }

    @Test
    fun `readback sha mismatch rejected and nothing lands at target`() {
        val bodyA = "alpha".toByteArray()
        // 清单谎报 sha：源树内容真实存在，但锚对不上
        val lie = PackManifestFile("files/a.txt", 5L, "e".repeat(64))
        val manifest = parseManifest(manifestText(listOf(lie)))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        val outcome = rejects(MarketRejectCodes.SHA_MISMATCH) { installer.commitPack(source, manifest, null) }
        assertTrue(outcome.detail.contains("files/a.txt"))
        // 半成品绝不留：落点不存在、暂存区清空
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        assertTrue(store.stagingRoot.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `source file missing at move time rejected and staging cleaned`() {
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/ghost.txt", "g".toByteArray()))))
        val source = makeSourceDir(manifest, emptyMap())
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        rejects(MarketRejectCodes.STAGING_FAILED) { installer.commitPack(source, manifest, null) }
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        assertTrue(store.stagingRoot.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `same version reinstall succeeds and ledger keeps single record`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        installer.commitPack(source, manifest, null)
        val again = installer.commitPack(source, manifest, null)

        assertTrue(again is InstallOutcome.Ok)
        assertEquals(1, store.readRecords().size)
        assertEquals("alpha", File(store.dirOf("kojo-pack", "demo", "1.0.0"), "files/a.txt").readText())
        // 旧副本残壳清干净
        assertFalse(File(store.stagingRoot, MarketContract.packStaleName("demo", "1.0.0")).exists())
    }

    @Test
    fun `failed reinstall keeps previous install intact`() {
        val bodyA = "alpha".toByteArray()
        val goodManifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(goodManifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())
        installer.commitPack(source, goodManifest, null)

        // 第二次装的清单锚对不上（读回复核拒）
        val lie = PackManifestFile("files/a.txt", 5L, "e".repeat(64))
        val badManifest = parseManifest(manifestText(listOf(lie)))

        val outcome = rejects(MarketRejectCodes.SHA_MISMATCH) { installer.commitPack(source, badManifest, null) }
        assertTrue(outcome.detail.isNotEmpty())
        // 旧落点原样在：内容与账都没动
        assertEquals("alpha", File(store.dirOf("kojo-pack", "demo", "1.0.0"), "files/a.txt").readText())
        assertEquals(1, store.readRecords().size)
    }

    @Test
    fun `journal begin failure cancels whole install without touching disk`() {
        // 把日志槽占成目录，begin 写文件必然失败 = 整体取消未动盘
        store.stagingRoot.mkdirs()
        File(store.stagingRoot, ".install.journal").mkdirs()
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        val outcome = installer.commitPack(source, manifest, null)
        assertTrue(outcome is InstallOutcome.Failed)
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
    }

    @Test
    fun `ledger write failure after rename surfaces Failed without rollback as source does`() {
        // service.ts 的记账在 catch 块之外（:740-757）：账写失败不回滚已落点的包，错误向上抛
        // （半趟态由消费侧 revalidate 收敛，CONTRACT §2.6）；本用例钉住这条口径。
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher())
        installer.commitPack(source, manifest, null)
        // 注入点 = 记账的临时文件槽占成目录（Files.move 的 REPLACE_EXISTING 会把空目录
        // 目标一并换掉，账本体占目录拦不住它；tmp 槽才是写路径上的硬闸）
        File(tmp.root, "installed.journal.tmp").delete()
        File(tmp.root, "installed.journal.tmp").mkdirs()

        val again = installer.commitPack(source, manifest, null)
        assertTrue(again is InstallOutcome.Failed)
        // 落点已是新树（不回滚——与 service.ts 同口径）
        assertEquals("alpha", File(store.dirOf("kojo-pack", "demo", "1.0.0"), "files/a.txt").readText())
    }

    @Test
    fun `id kind clash rejected before any disk write`() {
        // 查重在清单读回之后、落盘之前（service.ts:566-577）——用例走真实容器链（本地假栈）
        val bodyA = "alpha".toByteArray()
        val manifestContent = manifestText(listOf(fileAnchor("files/a.txt", bodyA)), kind = "dict-pack")
        val zip = buildStandardZip(
            listOf(MarketContract.MANIFEST_FILE to manifestContent.toByteArray(), "files/a.txt" to bodyA),
        )
        val server = TinyHttpServer { path -> serveZip(path, "/demo/demo.zip", zip) }
        server.start()
        try {
            val installer = MarketInstaller(store, verifier, wiredFetcher(server.port))
            val known = listOf(MarketPackRef("kojo-pack", "demo"))
            val entry = indexEntry(kind = "dict-pack", zipUrl = "https://github.com/demo/demo.zip")
                .copy(zipSha256 = sha256HexOf(zip), zipBytes = zip.size.toLong())

            val outcome = rejects(MarketRejectCodes.ID_KIND_CONFLICT) {
                installer.installFromIndex(entry, { _, _ -> }, known)
            }
            assertTrue(outcome.detail.contains("dict-pack"))
            assertFalse(store.dirOf("dict-pack", "demo", "1.0.0").exists())
            assertTrue(store.stagingRoot.listFiles()?.isEmpty() ?: true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `full container chain installs from local server`() {
        val bodyA = "alpha".toByteArray()
        val files = listOf(fileAnchor("files/a.txt", bodyA))
        val manifestContent = manifestText(files)
        val zip = buildStandardZip(
            listOf(MarketContract.MANIFEST_FILE to manifestContent.toByteArray(), "files/a.txt" to bodyA),
        )
        val zipSha = sha256HexOf(zip)
        val server = TinyHttpServer { path -> serveZip(path, "/demo/demo-1.0.0.zip", zip) }
        server.start()
        try {
            val installer = MarketInstaller(store, verifier, wiredFetcher(server.port))
            val entry = indexEntry(zipUrl = "https://github.com/demo/demo-1.0.0.zip")
                .copy(zipSha256 = zipSha, zipBytes = zip.size.toLong())
            val reports = ArrayList<Pair<Long, Long>>()

            val outcome = installer.installFromIndex(entry, { done, total -> reports += done to total }, emptyList())

            val pack = (outcome as? InstallOutcome.Ok)?.pack
                ?: error("期望 Ok，实际 $outcome")
            assertEquals("demo", pack.id)
            assertEquals(zipSha, pack.containerSha256)
            assertEquals("alpha", File(store.dirOf("kojo-pack", "demo", "1.0.0"), "files/a.txt").readText())
            assertTrue(reports.isNotEmpty())
            // 暂存区全清（容器与解压目录都不留）
            assertTrue(store.stagingRoot.listFiles()?.isEmpty() ?: true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `container sha mismatch rejected and nothing lands`() {
        val bodyA = "alpha".toByteArray()
        val zip = buildStandardZip(listOf("files/a.txt" to bodyA))
        val server = TinyHttpServer { path -> serveZip(path, "/demo/demo.zip", zip) }
        server.start()
        try {
            val installer = MarketInstaller(store, verifier, wiredFetcher(server.port))
            val entry = indexEntry(zipUrl = "https://github.com/demo/demo.zip")
                .copy(zipSha256 = "f".repeat(64), zipBytes = zip.size.toLong())

            val outcome = installer.installFromIndex(entry, { _, _ -> }, emptyList())
            val rejected = outcome as InstallOutcome.Rejected
            assertEquals(MarketRejectCodes.SHA_MISMATCH, rejected.code)
            assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
            assertTrue(store.stagingRoot.listFiles()?.isEmpty() ?: true)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `forbidden host rejected without any network`() {
        val installer = MarketInstaller(store, verifier, MarketFetcher())
        val entry = indexEntry(zipUrl = "https://evil.example.com/demo.zip")

        val outcome = installer.installFromIndex(entry, { _, _ -> }, emptyList())
        val rejected = outcome as InstallOutcome.Rejected
        assertEquals(MarketRejectCodes.ENDPOINT_FORBIDDEN, rejected.code)
    }

    // ── 洞②安装硬闸（GATE2D 门②/E4）：manifest.minAppVersion 高于本机 app 版本 ⇒ commitOrThrow 动盘前拒

    @Test
    fun `min app version above installed app rejects before anything touches disk`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
            .copy(minAppVersion = "0.5.0")
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher(), appVersion = "0.4.0")

        val outcome = rejects(MarketRejectCodes.APP_VERSION_UNSUPPORTED) {
            installer.commitPack(source, manifest, null)
        }
        assertTrue("detail 须点名两侧版本：" + outcome.detail, outcome.detail.contains("0.5.0"))
        // 拒绝发生在暂存与记账之前：落点与台账都不许存在
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        assertTrue(store.readRecords().isEmpty())
    }

    @Test
    fun `min app version at or below installed app installs normally`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
            .copy(minAppVersion = "0.4.0")
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val installer = MarketInstaller(store, verifier, MarketFetcher(), appVersion = "0.5.0")

        val outcome = installer.commitPack(source, manifest, null)

        assertTrue("期望 Ok，实际 $outcome", outcome is InstallOutcome.Ok)
        assertEquals(listOf((outcome as InstallOutcome.Ok).pack), store.readRecords())
    }

    // ── 洞④ sha 单源（GATE2D 门④/E4）：ContainerFacts 的 sha 只能由容器字节算出，手填裸串入口已封死

    @Test
    fun `container facts sha is computed from bytes not hand declared`() {
        val bytes = "container-payload".toByteArray()
        val facts = MarketInstaller.ContainerFacts.fromContainerBytes(
            bytes,
            listOf(MarketZip.EntryFacts(MarketContract.MANIFEST_FILE, 8L)),
            indexEntry(),
        )

        // 台账落账的就是这个值；谎报 f×64 的入口（公开裸构造）已私有化
        assertEquals(sha256HexOf(bytes), facts.sha256)
    }

    @Test
    fun `committed record container sha equals digest of passed container bytes`() {
        val bodyA = "alpha".toByteArray()
        val manifest = parseManifest(manifestText(listOf(fileAnchor("files/a.txt", bodyA))))
        val source = makeSourceDir(manifest, mapOf("files/a.txt" to bodyA))
        val containerBytes = "zip-bytes-under-test".toByteArray()
        val facts = MarketInstaller.ContainerFacts.fromContainerBytes(
            containerBytes,
            listOf(MarketZip.EntryFacts(MarketContract.MANIFEST_FILE, 8L), MarketZip.EntryFacts("files/a.txt", 5L)),
            indexEntry(),
        )
        val installer = MarketInstaller(store, verifier, MarketFetcher())

        val pack = (installer.commitPack(source, manifest, facts) as InstallOutcome.Ok).pack

        // 台账 sha 与传入字节的摘要逐字相等（不是 indexEntry.zipSha256，也不是任何手填串）
        assertEquals(sha256HexOf(containerBytes), pack.containerSha256)
    }
}
