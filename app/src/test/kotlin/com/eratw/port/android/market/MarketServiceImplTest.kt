package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * MarketServiceImpl 的定向单测（CONTRACT §2.6 判据 + CONTRACT-MKM-W6 三缝）。
 *
 * 取数用 [StubFetcher] 覆写 fetchText（零网络），安装用 [RecordingInstaller] 覆写
 * installFromIndex（可控时长，专测单飞互斥）；清扫接线用例走 [CommittingInstaller]
 * （真实 commitPack 全链落盘）；阶段回执用例走 [PhasedInstaller]（下载流 + Ok 收口）。
 * 反向证明锚：`two dead endpoints keep old state` 摘掉 refreshIndex 的「全挂保留旧三态」
 * 分支即转红；`concurrent installs serialize` 摘掉 mutationLock 即转红；
 * `install success sweeps superseded old versions under the lock` 摘掉 install 成功段
 * 挂清扫即转红；`sweep waits for in-flight install to release the mutation lock` 摘掉
 * sweepSuperseded 的锁内执行即转红（CONTRACT-MKM-W5-GC §2.4）；
 * `install emits download extract commit phases` 摘掉编排层 COMMIT 段回调即转红
 * （CONTRACT-MKM-W6 §2.4）。
 * 健康度排序（CONTRACT-MKM-W7 §2.4）：`mirror success ranks mirror first on the next refresh`
 * 与 `primary recovery reclaims the first position by newest success` 摘掉
 * endpointOrderWithHealth 的健康度降序段（回到固定默认序）即转红；
 * `override endpoint stays first regardless of health records` 摘掉「覆盖恒第一」段
 * （覆盖值只当普通候选进健康度排序）即转红；`endpoint reset keeps the health file and
 * its ordering effect` 摘掉 Reset 不删健康文件即转红；`failed refresh keeps the health
 * file intact` / `validation rejected fetch writes no health record` 摘掉「成功才写」闸
 * （失败也盖章）即转红。
 */

/** 阶段回执夹具：按序发下载流两笔、返回 Ok（bytes=50 模拟清单累计字节）。 */
private class PhasedInstaller(store: MarketStore) : MarketInstaller(store, FakeVerifier(), MarketFetcher()) {
    override fun installFromIndex(
        entry: MarketIndexEntry,
        onProgress: (Long, Long) -> Unit,
        knownRefs: List<MarketPackRef>,
    ): InstallOutcome {
        onProgress(0L, 100L)
        onProgress(100L, 100L)
        return InstallOutcome.Ok(
            InstalledPack("kojo-pack", entry.id, entry.version, "Demo", "author", "MIT", 0L, null, null, 0, 50),
        )
    }
}
class MarketServiceImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = FakeVerifier()
    private val primaryUrl = MarketContract.DEFAULT_INDEX_URL
    private val mirrorUrl = MarketContract.MIRROR_INDEX_URL

    /** 记调用序的假取数器：按 URL 字面回罐头文本或失败（闸不参与，服务层只看结果）。 */
    private class StubFetcher : MarketFetcher() {
        val calls = ArrayList<String>()
        val responses = HashMap<String, String>()
        val failures = HashSet<String>()

        override fun fetchText(url: String, timeoutMs: Long, maxBytes: Long): FetchTextResult {
            calls += url
            if (url in failures) throw RejectException(MarketRejectCodes.DOWNLOAD_FAILED, "stub down：$url")
            return FetchTextResult(responses.getValue(url), 10L, 200, url)
        }
    }

    /** 记参 + 可控时长的假安装器（互斥测试用；锁在 service 层，注入双线程必串行）。 */
    private class RecordingInstaller(store: MarketStore) : MarketInstaller(store, FakeVerifier(), MarketFetcher()) {
        val seenRefs = ArrayList<List<MarketPackRef>>()
        val maxInside = AtomicInteger(0)
        private val inside = AtomicInteger(0)

        override fun installFromIndex(
            entry: MarketIndexEntry,
            onProgress: (Long, Long) -> Unit,
            knownRefs: List<MarketPackRef>,
        ): InstallOutcome {
            seenRefs += knownRefs
            val current = inside.incrementAndGet()
            maxInside.updateAndGet { old -> maxOf(old, current) }
            Thread.sleep(200)
            inside.decrementAndGet()
            return InstallOutcome.Ok(
                InstalledPack(entry.kind, entry.id, entry.version, entry.name, "author", "MIT", 0L, null, null, 0, 0),
            )
        }
    }

    /**
     * 走真实 commitPack 全链的安装器：installFromIndex 按条目现造源目录后整链落盘
     * （清扫接线用例要真目录真账——install 成功段的清扫对象是盘上的旧版目录）。
     */
    private class CommittingInstaller(
        store: MarketStore,
        private val fixtureVerifier: FakeVerifier,
        private val root: File,
    ) : MarketInstaller(store, fixtureVerifier, MarketFetcher()) {

        override fun installFromIndex(
            entry: MarketIndexEntry,
            onProgress: (Long, Long) -> Unit,
            knownRefs: List<MarketPackRef>,
        ): InstallOutcome {
            val body = "content-${entry.version}".toByteArray()
            val text = buildString {
                append("#mkm-pack\n")
                append("schema=1\n")
                append("kind=${entry.kind}\n")
                append("id=${entry.id}\n")
                append("version=${entry.version}\n")
                append("name=Demo\n")
                append("author=author\n")
                append("license=MIT\n")
                append("redistribute=true\n")
                append("minAppVersion=0.0.1\n")
                append("file=files/a.txt,${body.size},${sha256HexOf(body)}\n")
            }
            val manifest = (fixtureVerifier.parsePackManifest(text) as MarketParseResult.Ok).value
            val source = File(root, "source-${entry.id}-${entry.version}")
            source.mkdirs()
            File(source, MarketContract.MANIFEST_FILE).writeText(text, Charsets.UTF_8)
            File(source, "files").mkdirs()
            File(source, "files/a.txt").writeBytes(body)
            return commitPack(source, manifest, null)
        }
    }

    private fun indexText(vararg lines: String): String = "#mkm-index\n" + lines.joinToString("\n")

    private fun indexLine(id: String = "demo", version: String = "1.0.0", kind: String = "kojo-pack"): String =
        "entry|$kind|$id|$version|Demo|author|MIT|true|0.0.1|https://github.com/demo/d.zip|" +
            "a".repeat(64) + "|10|1|10"

    /** 造一个在位落点并记账（updateCheck / install 查重面的夹具）。 */
    private fun seedInstalled(id: String = "demo", version: String = "1.0.0"): MarketStore {
        val store = MarketStore(tmp.root)
        val dir = store.dirOf("kojo-pack", id, version)
        dir.mkdirs()
        File(dir, "files").mkdirs()
        File(dir, "files/a.txt").writeText("alpha")
        store.upsertRecord(
            InstalledPack("kojo-pack", id, version, "Demo", "author", "MIT", 0L, "c".repeat(64), null, 1, 5),
        )
        return store
    }

    @Test
    fun `fresh service with no mirror reads Absent`() {
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher())
        assertEquals(MarketIndexState.Absent, service.indexState())
    }

    @Test
    fun `refresh writes mirror verbatim and stamps fetched at`() {
        val fetcher = StubFetcher().apply { responses[mirrorUrl] = indexText(indexLine()) }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)

        val outcome = service.refreshIndex(force = true)

        // 真回源且校验过 = Fresh，回执带解析后的索引本体（CONTRACT-MKM-W6 缝 1）
        val fresh = outcome as MarketRefreshOutcome.Fresh
        assertEquals(1, fresh.index.packs.size)
        assertEquals("demo", fresh.index.packs[0].id)
        // 镜像 = 原文一字不改
        assertEquals(indexText(indexLine()), File(tmp.root, MarketContract.INDEX_FILE).readText())
        assertEquals(fresh.index.fetchedAtMs, File(tmp.root, "fetched-at.txt").readText().trim().toLong())
    }

    @Test
    fun `within ttl no refetch happens unless forced`() {
        val text = indexText(indexLine())
        val fetcher = StubFetcher().apply {
            responses[primaryUrl] = text
            responses[mirrorUrl] = text
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)
        service.refreshIndex(force = true)
        assertEquals(1, fetcher.calls.size)

        // TTL 内非 force = Cached（不是失败，也不该装作拉过）
        val cached = service.refreshIndex(force = false) as MarketRefreshOutcome.Cached
        assertEquals(1, cached.index.packs.size)
        assertEquals(1, fetcher.calls.size)

        service.refreshIndex(force = true)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `primary endpoint failure falls back to mirror`() {
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine(id = "from-mirror"))
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)

        val fresh = service.refreshIndex(force = true) as MarketRefreshOutcome.Fresh

        assertEquals("from-mirror", fresh.index.packs[0].id)
        // 取到镜像：主端点先打、镜像后打
        assertEquals(listOf(primaryUrl, mirrorUrl), fetcher.calls)
    }

    @Test
    fun `user override endpoint is tried before the mirror`() {
        val custom = "https://poboking.github.io/my-mirror/index.json"
        val fetcher = StubFetcher().apply { responses[custom] = indexText(indexLine()) }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)
        assertEquals(EndpointEditResult.Set(custom), service.setEndpoint(custom))

        val outcome = service.refreshIndex(force = true)
        assertTrue(outcome is MarketRefreshOutcome.Fresh)
        // 首个端点取数成功即按其解析结果收口（回退只在取数失败时发生）
        assertEquals(listOf(custom), fetcher.calls)
    }

    @Test
    fun `two dead endpoints keep old state and mirror untouched`() {
        val firstText = indexText(indexLine(id = "first"))
        val fetcher = StubFetcher().apply { responses[mirrorUrl] = firstText }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)
        val seeded = service.refreshIndex(force = true)
        assertTrue(seeded is MarketRefreshOutcome.Fresh)

        fetcher.failures += primaryUrl
        fetcher.failures += mirrorUrl
        val again = service.refreshIndex(force = true)

        // 回源失败 = Stale 回执 + 盘上旧列表原样保留（indexState() 仍 Ok 旧值）
        val stale = again as MarketRefreshOutcome.Stale
        assertTrue("Stale 带逐端点失败原话", stale.problem.contains(primaryUrl) && stale.problem.contains(mirrorUrl))
        val ok = service.indexState() as MarketIndexState.Ok
        assertEquals("first", ok.index.packs[0].id)
        assertEquals(firstText, File(tmp.root, MarketContract.INDEX_FILE).readText())
    }

    @Test
    fun `all endpoints dead without a mirror yield Stale and keep Absent`() {
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            failures += mirrorUrl
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)

        val stale = service.refreshIndex(force = true) as MarketRefreshOutcome.Stale

        assertTrue("无旧镜像也回 Stale（盘上无列表的事实如实出声）：${stale.problem}", stale.problem.isNotBlank())
        // indexState() 三态语义不变：盘上无镜像 = Absent
        assertEquals(MarketIndexState.Absent, service.indexState())
        assertFalse(File(tmp.root, MarketContract.INDEX_FILE).exists())
    }

    @Test
    fun `validation failure keeps old mirror and reports Invalid via index state`() {
        val firstText = indexText(indexLine(id = "first"))
        val fetcher = StubFetcher().apply { responses[mirrorUrl] = firstText }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)

        fetcher.responses[mirrorUrl] = "#mkm-index\nINVALID|坏条目形状"
        val stale = service.refreshIndex(force = true) as MarketRefreshOutcome.Stale

        assertEquals("坏条目形状", stale.problem)
        // 校验拒：三态语义不变——indexState() 走 Invalid，盘上旧列表原样保留
        val invalid = service.indexState() as MarketIndexState.Invalid
        assertEquals("坏条目形状", invalid.problem)
        assertEquals(firstText, File(tmp.root, MarketContract.INDEX_FILE).readText())
    }

    @Test
    fun `install passes known refs from ledger and index for id kind clash`() {
        val store = seedInstalled(id = "installed-pack")
        val fetcher = StubFetcher().apply { responses[mirrorUrl] = indexText(indexLine(id = "indexed")) }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, store)
        service.refreshIndex(force = true)
        val recording = RecordingInstaller(store)
        val serviceWithRecording = MarketServiceImpl(tmp.root, verifier, fetcher, store, recording)
        val entry = indexEntry(id = "new-pack")

        serviceWithRecording.install(entry, { _, _, _ -> })

        val refs = recording.seenRefs.single()
        assertTrue(refs.any { it.id == "installed-pack" })
        assertTrue(refs.any { it.id == "indexed" })
    }

    @Test
    fun `update check returns null when pack not installed`() {
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher())
        assertNull(service.updateCheck(indexEntry(version = "1.0.1")))
    }

    @Test
    fun `update check four states`() {
        val store = seedInstalled(id = "demo", version = "1.0.0")
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store)

        assertEquals(UpdateCheckState.Updatable("1.0.1"), service.updateCheck(indexEntry(version = "1.0.1")))
        // Latest 带容器对平读数：记账条 containerSha256（c 段）≠ 索引 zip 锚（a 段）= false
        assertEquals(
            UpdateCheckState.Latest(sameContainer = false),
            service.updateCheck(indexEntry(version = "1.0.0")),
        )
        // Ahead 带本机最高版本读数（CONTRACT-MKM-W6 缝 2）
        assertEquals(UpdateCheckState.Ahead("1.0.0"), service.updateCheck(indexEntry(version = "0.9.9")))
        assertTrue(service.updateCheck(indexEntry(version = "not-a-version")) is UpdateCheckState.Failed)
    }

    @Test
    fun `update check sameContainer reads true only when container digests match`() {
        // 本机容器摘要 = 索引 zip 锚（同一段十六进制）= true（market.ts :1058-1061）
        val entry = indexEntry(version = "1.0.0")
        val store = MarketStore(tmp.root)
        val dir = store.dirOf("kojo-pack", "demo", "1.0.0")
        dir.mkdirs()
        File(dir, "files").mkdirs()
        File(dir, "files/a.txt").writeText("alpha")
        store.upsertRecord(
            InstalledPack("kojo-pack", "demo", "1.0.0", "Demo", "author", "MIT", 0L, entry.zipSha256, null, 1, 5),
        )
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store)
        assertEquals(UpdateCheckState.Latest(sameContainer = true), service.updateCheck(entry))
    }

    @Test
    fun `update check sameContainer is null for directory-source installs`() {
        // 目录源记账条没有容器事实 = null（不拿 contentDigest 顶替，market.ts :887-896）
        val store = MarketStore(tmp.root)
        val dir = store.dirOf("kojo-pack", "demo", "1.0.0")
        dir.mkdirs()
        File(dir, "files").mkdirs()
        File(dir, "files/a.txt").writeText("alpha")
        store.upsertRecord(
            InstalledPack("kojo-pack", "demo", "1.0.0", "Demo", "author", "MIT", 0L, null, "d".repeat(64), 1, 5),
        )
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store)
        assertEquals(
            UpdateCheckState.Latest(sameContainer = null),
            service.updateCheck(indexEntry(version = "1.0.0")),
        )
    }

    @Test
    fun `set endpoint four states`() {
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher())
        val custom = "https://poboking.github.io/my-mirror/index.json"

        // Set：形态合法且与生效值不同
        assertEquals(EndpointEditResult.Set(custom), service.setEndpoint(custom))
        assertEquals(custom, service.endpoint())
        // Kept：与生效值相同（不写盘不发假回执）
        assertEquals(EndpointEditResult.Kept, service.setEndpoint(custom))
        // Invalid：http / userinfo / 不可解析
        val httpEndpoint = service.setEndpoint("http://x.example.com/i.json")
        assertEquals(EndpointEditResult.Invalid::class.java, httpEndpoint::class.java)
        assertEquals(
            EndpointEditResult.Invalid::class.java,
            service.setEndpoint("https://user:pw@github.com/i.json")::class.java,
        )
        assertEquals(EndpointEditResult.Invalid::class.java, service.setEndpoint("not a url")::class.java)
        // Invalid 不落盘：生效端点不动
        assertEquals(custom, service.endpoint())
        // Reset：空串撤销覆盖回默认
        assertEquals(EndpointEditResult.Reset, service.setEndpoint(""))
        assertEquals(MarketContract.DEFAULT_INDEX_URL, service.endpoint())
        assertFalse(File(tmp.root, "endpoint.txt").exists())
    }

    @Test
    fun `concurrent installs serialize through the mutation lock`() {
        val store = MarketStore(tmp.root)
        val recording = RecordingInstaller(store)
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, recording)
        val entry = indexEntry(id = "demo")

        val t1 = Thread { service.install(entry, { _, _, _ -> }) }
        val t2 = Thread { service.install(entry, { _, _, _ -> }) }
        t1.start()
        t2.start()
        t1.join()
        t2.join()

        // 双线程都进了 install，但任一时刻在飞的至多 1 个 = 串行化
        assertEquals(1, recording.maxInside.get())
    }

    @Test
    fun `uninstall waits for in-flight install to release the lock`() {
        val store = MarketStore(tmp.root)
        val latch = CountDownLatch(1)
        val installEnd = AtomicLong(0)
        val uninstallEnd = AtomicLong(0)
        val installer = object : MarketInstaller(store, verifier, MarketFetcher()) {
            override fun installFromIndex(
                entry: MarketIndexEntry,
                onProgress: (Long, Long) -> Unit,
                knownRefs: List<MarketPackRef>,
            ): InstallOutcome {
                latch.countDown()
                Thread.sleep(300)
                return InstallOutcome.Ok(
                    InstalledPack("kojo-pack", "demo", "1.0.0", "Demo", "author", "MIT", 0L, null, null, 0, 0),
                )
            }
        }
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, installer)
        val uninstaller = Thread {
            latch.await()
            service.uninstall("kojo-pack", "demo", "1.0.0")
            uninstallEnd.set(System.nanoTime())
        }
        uninstaller.start()

        service.install(indexEntry(), { _, _, _ -> })
        installEnd.set(System.nanoTime())
        uninstaller.join()

        // 卸载必然等安装释放锁之后才返回（NotFound 也要排队）
        assertTrue(uninstallEnd.get() >= installEnd.get())
    }

    @Test
    fun `install success sweeps superseded old versions under the lock`() {
        val store = seedInstalled(id = "demo", version = "1.0.0")
        val installer = CommittingInstaller(store, verifier, tmp.root)
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, installer)

        val outcome = service.install(indexEntry(id = "demo", version = "2.0.0"), { _, _, _ -> })

        assertTrue(outcome is InstallOutcome.Ok)
        // 新版入库即触发同 id 旧版清扫（更新流的自然收尾）
        assertTrue(store.dirOf("kojo-pack", "demo", "2.0.0").exists())
        assertFalse(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        // 孤儿账行由在位自愈收敛：installed() 只剩新版
        val installed = service.installed()
        assertEquals(1, installed.size)
        assertEquals("2.0.0", installed[0].version)
    }

    @Test
    fun `install failure or rejection does not sweep the library`() {
        val store = seedInstalled(id = "demo", version = "1.0.0")
        val rejecting = object : MarketInstaller(store, verifier, MarketFetcher()) {
            override fun installFromIndex(
                entry: MarketIndexEntry,
                onProgress: (Long, Long) -> Unit,
                knownRefs: List<MarketPackRef>,
            ): InstallOutcome = InstallOutcome.Rejected(MarketRejectCodes.SHA_MISMATCH, "stub 拒装")
        }
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, rejecting)
        val rejected = service.install(indexEntry(id = "demo", version = "2.0.0"), { _, _, _ -> })
        assertTrue(rejected is InstallOutcome.Rejected)
        assertTrue(store.dirOf("kojo-pack", "demo", "1.0.0").exists())

        val failing = object : MarketInstaller(store, verifier, MarketFetcher()) {
            override fun installFromIndex(
                entry: MarketIndexEntry,
                onProgress: (Long, Long) -> Unit,
                knownRefs: List<MarketPackRef>,
            ): InstallOutcome = InstallOutcome.Failed("stub IO 挂")
        }
        val serviceAfterFail = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, failing)
        val failed = serviceAfterFail.install(indexEntry(id = "demo", version = "3.0.0"), { _, _, _ -> })
        assertTrue(failed is InstallOutcome.Failed)
        assertTrue(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
    }

    /** 阶段回执收集器（两阶段用例共用，超长行折行用）。 */
    private fun collectPhases(
        service: MarketServiceImpl,
        entry: MarketIndexEntry,
        seen: MutableList<Triple<MarketInstallPhase, Long, Long>>,
    ): InstallOutcome = service.install(entry) { phase, done, total -> seen += Triple(phase, done, total) }

    @Test
    fun `install emits download extract commit phases through one callback`() {
        val store = MarketStore(tmp.root)
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, PhasedInstaller(store))
        val seen = ArrayList<Triple<MarketInstallPhase, Long, Long>>()

        val outcome = collectPhases(service, indexEntry(id = "demo"), seen)

        assertTrue(outcome is InstallOutcome.Ok)
        // 同一回调按序收三段（CONTRACT-MKM-W6 §0.3）：下载流两笔逐笔直通（字节口径照旧、
        // 顺序不打乱）→ 收口点补 EXTRACT（总量/起点 0）→ Ok 收口补 COMMIT（清单累计字节/总量 0）
        assertEquals(
            listOf(
                Triple(MarketInstallPhase.DOWNLOAD, 0L, 100L),
                Triple(MarketInstallPhase.DOWNLOAD, 100L, 100L),
                Triple(MarketInstallPhase.EXTRACT, 0L, 0L),
                Triple(MarketInstallPhase.COMMIT, 50L, 0L),
            ),
            seen,
        )
    }

    @Test
    fun `rejected install emits no commit phase callback`() {
        val store = MarketStore(tmp.root)
        val rejecting = object : MarketInstaller(store, verifier, MarketFetcher()) {
            override fun installFromIndex(
                entry: MarketIndexEntry,
                onProgress: (Long, Long) -> Unit,
                knownRefs: List<MarketPackRef>,
            ): InstallOutcome {
                onProgress(100L, 100L)
                return InstallOutcome.Rejected(MarketRejectCodes.SHA_MISMATCH, "stub 拒装")
            }
        }
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, rejecting)
        val seen = ArrayList<Triple<MarketInstallPhase, Long, Long>>()

        val outcome = collectPhases(service, indexEntry(id = "demo"), seen)

        assertTrue(outcome is InstallOutcome.Rejected)
        // 拒装路径：下载流直通 + EXTRACT 收口点照发，但搬运没成 ⇒ 不发 COMMIT（不放假读数）
        assertEquals(
            listOf(
                Triple(MarketInstallPhase.DOWNLOAD, 100L, 100L),
                Triple(MarketInstallPhase.EXTRACT, 0L, 0L),
            ),
            seen,
        )
    }

    @Test
    fun `explicit sweep passes through to the store and returns deleted count`() {
        val store = seedInstalled(id = "demo", version = "1.0.0")
        // 再摆一个无账行的旧版目录（盘是唯一真相，账没有也照清）
        val older = store.dirOf("kojo-pack", "demo", "0.5.0")
        older.mkdirs()
        File(older, "files").mkdirs()
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store)

        // 读数面（CONTRACT-MKM-W5-GC 余项 D1）：删除目录数随 Swept 回执带出
        assertEquals(1, (service.sweepSuperseded() as SweepOutcome.Swept).removed)

        assertFalse(older.exists())
        assertTrue(store.dirOf("kojo-pack", "demo", "1.0.0").exists())
        // 幂等：二次清扫 = Swept(0)（新增读数的 0 用例——没得清也是一等回执，不是失败）
        assertEquals(SweepOutcome.Swept(0), service.sweepSuperseded())
    }

    @Test
    fun `explicit sweep on a fresh library reports zero`() {
        // 库根都没建：store 侧 no-op 口径照走，回执 = Swept(0)（不是 Failed——没东西可清不是没跑成）
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher())
        assertEquals(SweepOutcome.Swept(0), service.sweepSuperseded())
    }

    @Test
    fun `sweep waits for in-flight install to release the mutation lock`() {
        val store = MarketStore(tmp.root)
        val latch = CountDownLatch(1)
        val installEnd = AtomicLong(0)
        val sweepEnd = AtomicLong(0)
        val installer = object : MarketInstaller(store, verifier, MarketFetcher()) {
            override fun installFromIndex(
                entry: MarketIndexEntry,
                onProgress: (Long, Long) -> Unit,
                knownRefs: List<MarketPackRef>,
            ): InstallOutcome {
                latch.countDown()
                Thread.sleep(300)
                return InstallOutcome.Ok(
                    InstalledPack("kojo-pack", "demo", "1.0.0", "Demo", "author", "MIT", 0L, null, null, 0, 0),
                )
            }
        }
        val service = MarketServiceImpl(tmp.root, verifier, StubFetcher(), store, installer)
        val sweeper = Thread {
            latch.await()
            service.sweepSuperseded()
            sweepEnd.set(System.nanoTime())
        }
        sweeper.start()

        service.install(indexEntry(), { _, _, _ -> })
        installEnd.set(System.nanoTime())
        sweeper.join()

        // 清扫必然等安装释放锁之后才返回（摘掉 sweepSuperseded 的锁内执行本用例即转红）
        assertTrue(sweepEnd.get() >= installEnd.get())
    }

    /* ---------------- 端点健康度排序（CONTRACT-MKM-W7） ---------------- */

    /** 健康文件（市场根下，与镜像三件套同层；格式 = 每行 `url\tlastSuccessMs`）。 */
    private fun healthFile(): File = File(tmp.root, "endpoint-health.txt")

    @Test
    fun `mirror success ranks mirror first on the next refresh`() {
        var now = 1_000L
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine(id = "from-mirror"))
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, clock = { now })

        // 第一轮：无记录 = 默认序（主端点挂）→ 镜像成功，健康文件记镜像一章
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)
        assertEquals("$mirrorUrl\t1000", healthFile().readText())

        // 第二轮（契约 §2.1 假栈两轮断言调用顺序）：镜像有章 → 排第一，首个成功即收口
        fetcher.calls.clear()
        now = 2_000L
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)
        assertEquals(mirrorUrl, fetcher.calls.single())
    }

    @Test
    fun `primary recovery reclaims the first position by newest success`() {
        var now = 1_000L
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine(id = "from-mirror"))
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, clock = { now })
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)

        // 第二轮：镜像先试（有章）但挂掉 → 回退主端点成功，盖更晚的章（失败不重盖镜像行）
        fetcher.calls.clear()
        fetcher.failures.clear()
        fetcher.failures += mirrorUrl
        fetcher.responses[primaryUrl] = indexText(indexLine(id = "from-primary"))
        now = 2_000L
        val recovered = service.refreshIndex(force = true) as MarketRefreshOutcome.Fresh
        assertEquals(listOf(mirrorUrl, primaryUrl), fetcher.calls)
        assertEquals("from-primary", recovered.index.packs[0].id)
        assertEquals("$mirrorUrl\t1000\n$primaryUrl\t2000", healthFile().readText())

        // 第三轮（契约 §2.1 后半）：主端点的章更新 → 回主序
        fetcher.calls.clear()
        fetcher.failures.clear()
        now = 3_000L
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)
        assertEquals(primaryUrl, fetcher.calls.single())
    }

    @Test
    fun `override endpoint stays first regardless of health records`() {
        var now = 1_000L
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine())
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, clock = { now })
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)

        // 覆盖端点恒第一（镜像有章也压不过用户显式意志，契约 §2.2）；覆盖与镜像都挂后落到
        // 无记录的主端点——全序 = [覆盖, 记录过的镜像, 无记录的主端点] 三条判据一次钉死
        val custom = "https://poboking.github.io/my-mirror/index.json"
        assertEquals(EndpointEditResult.Set(custom), service.setEndpoint(custom))
        fetcher.calls.clear()
        fetcher.failures.clear()
        fetcher.failures += custom
        fetcher.failures += mirrorUrl
        fetcher.responses[primaryUrl] = indexText(indexLine(id = "from-primary"))
        now = 2_000L
        val outcome = service.refreshIndex(force = true)
        assertEquals("from-primary", (outcome as MarketRefreshOutcome.Fresh).index.packs[0].id)
        assertEquals(listOf(custom, mirrorUrl, primaryUrl), fetcher.calls)
    }

    @Test
    fun `override equal to mirror dedupes to mirror first then default`() {
        // 组长验收补钉（CONTRACT-MKM-W7 接线项 2 追认）：覆盖 = MIRROR 时候选全集去重，
        // 全序 = [MIRROR（覆盖位）, DEFAULT]，不再复现旧固定序的 [MIRROR, MIRROR] 重复表项。
        // 镜像必须失败才能观察到两跳（首位成功即收口，重复表项不可见）。
        var now = 1_000L
        val fetcher = StubFetcher().apply {
            failures += mirrorUrl
            responses[primaryUrl] = indexText(indexLine())
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, clock = { now })
        assertEquals(EndpointEditResult.Set(mirrorUrl), service.setEndpoint(mirrorUrl))
        now = 2_000L
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)
        assertEquals(listOf(mirrorUrl, primaryUrl), fetcher.calls)
    }

    @Test
    fun `no health records keep the default endpoint order`() {
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine())
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)

        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)

        // 无记录 = 默认序（DEFAULT 先于 MIRROR，契约 §2.2）：主端点挂了才落到镜像
        assertEquals(listOf(primaryUrl, mirrorUrl), fetcher.calls)
    }

    @Test
    fun `endpoint reset keeps the health file and its ordering effect`() {
        var now = 1_000L
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine())
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, clock = { now })
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)

        // Reset 只清覆盖文件，健康文件一字不动（裁定 4：镜像成功史仍有效）
        assertEquals(EndpointEditResult.Reset, service.setEndpoint(""))
        assertTrue(healthFile().exists())
        assertEquals("$mirrorUrl\t1000", healthFile().readText())

        // 健康度仍参与排序：镜像有章 → 下轮仍镜像第一
        fetcher.calls.clear()
        fetcher.failures.clear()
        fetcher.responses[primaryUrl] = indexText(indexLine())
        now = 2_000L
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)
        assertEquals(mirrorUrl, fetcher.calls.single())
    }

    @Test
    fun `failed refresh keeps the health file intact`() {
        var now = 1_000L
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            responses[mirrorUrl] = indexText(indexLine())
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher, clock = { now })
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Fresh)
        val before = healthFile().readText()

        // 两端点全挂 = Stale；失败不记负分、不清旧记录（契约 §2.3）：健康文件一字不动
        fetcher.failures += mirrorUrl
        now = 2_000L
        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Stale)
        assertEquals(before, healthFile().readText())
    }

    @Test
    fun `validation rejected fetch writes no health record`() {
        // 200 但没过 parseIndex 校验 = 不算成功（§0.3）：健康文件根本不出现
        val fetcher = StubFetcher().apply { responses[primaryUrl] = "#mkm-index\nINVALID|坏条目形状" }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)

        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Stale)

        assertFalse(healthFile().exists())
    }

    @Test
    fun `transport failures write no health record`() {
        val fetcher = StubFetcher().apply {
            failures += primaryUrl
            failures += mirrorUrl
        }
        val service = MarketServiceImpl(tmp.root, verifier, fetcher)

        assertTrue(service.refreshIndex(force = true) is MarketRefreshOutcome.Stale)

        assertFalse(healthFile().exists())
    }
}
