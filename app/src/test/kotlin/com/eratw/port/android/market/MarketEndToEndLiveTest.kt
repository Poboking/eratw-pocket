package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 口上市场「真端点全链贯通探针」（CONTRACT-MKM-W2-E2E 判据 2-1 / 2-3 / 2-4）。
 *
 * ## env 门（契约 §2.5，JUnit 动态用例纪律）
 *
 * `ERATW_MARKET_E2E=1` 才生成用例：行集 [rows] 在变量缺失时返回空集合，Parameterized
 * 对空行集生成 **0 个用例**（不进 XML、不计 skipped，与 Assume 假绿划清界限）。跑带变量轮
 * 前必须 `./gradlew --stop`（守护进程快照式继承环境变量，旧守护进程吃不到新变量）并
 * `cleanTest`（test 任务 inputs 不含环境变量，不 fresh 会 UP-TO-DATE 假绿）。
 * 主端点可用 `ERATW_MARKET_E2E_URL` 注入（缺省 [MarketContract.DEFAULT_INDEX_URL]）：
 * 注入值经 [MarketServiceImpl.setEndpoint] 生产写入口落盘——形态不过闸 = 用例红
 * （App 本来就打不开这种端点）；注入主机不在 [MarketFetcher] 白名单时主端点取数失败、
 * 镜像兜底接住（生产回退语义，本探针照实走过）。
 *
 * ## 内容合规（契约 §0.2）
 *
 * 安装面只动 `demo.era-tw`（CC0-1.0 合成内容，注册表 README 明示可自由再配布）；
 * 索引里的真实第三方口上条目一个字节都不许下载。索引其余条目只作 §2.1 的计数与存在性断言。
 *
 * ## 网络口径（契约 §2.6）
 *
 * 探针取数用 `HttpURLConnection` 走系统默认栈（跟跳交给平台默认实现）；生产闸口的
 * 形态/白名单面用 [MarketFetcher.urlProblem] 出结构锚（探针不做假实现旁路）。本机直连
 * 不通时给 Gradle 测试 JVM 传 `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`
 * （AGENTS §1.1 的 Clash 口，生效哪种自证后写进交付）。
 *
 * ## 断言锚
 *
 * 「结构 + 存在性」锚，不硬编码全量 JSON（索引会演进）：schema=1、packs ≥5、demo 条目
 * zip.url/sha256/bytes 非空可过闸、落库记账 = 索引声明、落库逐文件 sha256 = 清单锚 =
 * zip 内字节（三向零漂移）。线上实测快照（2026-11-17，直连 curl）：schema=1、packs=6、
 * `demo.era-tw` v1.0.3 zip 1084B、包内 pack.json + files 1 个 ERB（471B）。
 */
@RunWith(Parameterized::class)
class MarketEndToEndLiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = MarketVerifierImpl()

    // ------------------------------------------------------------------
    // 判据 2-1：索引全链（真端点原文 → 真实 parseIndex）
    // ------------------------------------------------------------------

    @Test
    fun `判据2-1_索引全链_线上原文过真实parseIndex_结构锚齐全`() {
        val url = primaryOverride() ?: MarketContract.DEFAULT_INDEX_URL
        val (status, body) = httpGetBytes(url)
        assertEquals("探针主端点必须 200：$url", HTTP_OK, status)
        val parsed = verifier.parseIndex(String(body, Charsets.UTF_8), System.currentTimeMillis())
        val ix = orFail(
            parsed as? MarketParseResult.Ok,
            "线上 index.json 过不了真实解析闸：$parsed",
        ).value
        assertEquals("索引 schema 钉 1", MarketContract.SCHEMA_VERSION, ix.schema)
        assertTrue(
            "packs 至少 $MIN_PACKS 条（2026-11-17 快照实测 6）：${ix.packs.size}",
            ix.packs.size >= MIN_PACKS,
        )
        assertTrue("generatedAt 必须非空", !ix.generatedAt.isNullOrBlank())
        val demo = orFail(
            ix.packs.firstOrNull { it.id == DEMO_PACK_ID },
            "线上索引必须含 $DEMO_PACK_ID 条目（探针只许动 demo 系）",
        )
        assertTrue(
            "zip.url 非空且 https：${demo.zipUrl}",
            demo.zipUrl.isNotBlank() && demo.zipUrl.startsWith("https://"),
        )
        assertTrue(
            "zip.sha256 必须 64 位小写 hex：${demo.zipSha256}",
            verifier.isSha256Hex(demo.zipSha256),
        )
        assertTrue("zip.bytes 必须为正：${demo.zipBytes}", demo.zipBytes > 0L)
        assertNull(
            "demo zip.url 必须过 App 取数白名单闸（不过闸这条链在 App 里装不动）",
            MarketFetcher().urlProblem(demo.zipUrl),
        )
    }

    // ------------------------------------------------------------------
    // 判据 2-3：安装全链（真回源 → 真下载 → 真落库记账 → 卸载全清）
    // ------------------------------------------------------------------

    @Test
    fun `判据2-3_安装全链_真回源真下载真落库_卸载全清`() {
        val service = newService()
        val outcome0 = service.refreshIndex(force = true)
        val fresh = orFail(
            outcome0 as? MarketRefreshOutcome.Fresh,
            "refreshIndex(force) 必须回 Fresh（镜像兜底后仍不行才失败）：$outcome0",
        )
        val demo = latestDemoOr(fresh.index)
        val progress = ArrayList<Triple<MarketInstallPhase, Long, Long>>()
        val outcome = service.install(demo) { phase, done, total -> progress += Triple(phase, done, total) }
        val ok = orFail(outcome as? InstallOutcome.Ok, "demo 包必须装成功：$outcome")
        assertEquals("记账版本 = 索引版本", demo.version, ok.pack.version)
        assertEquals(
            "containerSha256 = 索引声明 zip sha256",
            demo.zipSha256,
            ok.pack.containerSha256,
        )
        assertEquals("fresh root 装一个就只有一条账", 1, service.installed().size)
        assertTrue(
            "installedBytes 必须为正：${service.installedBytes()}",
            service.installedBytes() > 0L,
        )
        // 落库目录锚：路径只由 MarketStore.dirOf 派生（不手拼第二份）
        val store = MarketStore(File(tmp.root, MarketContract.DIR_NAME))
        val packDir = store.dirOf(demo.kind, demo.id, ok.pack.version)
        assertTrue(
            "落库 pack.json 在场：${packDir.path}",
            File(packDir, MarketContract.MANIFEST_FILE).isFile,
        )
        assertTrue(
            "落库 files 目录在场：${packDir.path}",
            File(packDir, MarketContract.CONTENT_DIR).isDirectory,
        )
        assertPhaseReceipt(progress, demo.zipBytes, ok.pack.bytes)
        // 卸载 → 目录与账全清（契约 §2.3 收尾）
        assertEquals(
            UninstallOutcome.Ok,
            service.uninstall(demo.kind, demo.id, ok.pack.version),
        )
        assertFalse("卸载后落库目录已删", packDir.exists())
        assertTrue("卸载后账已清", service.installed().isEmpty())
        assertEquals("卸载后 installedBytes 回零", 0L, service.installedBytes())
        val leftovers = store.stagingRoot.takeIf { it.isDirectory }?.list()?.toList() ?: emptyList()
        assertTrue("暂存残壳全清：$leftovers", leftovers.isEmpty())
    }

    // ------------------------------------------------------------------
    // 判据 2-4：对账口径（落库 pack.json 逐文件锚 与 zip 内字节 三向零漂移）
    // ------------------------------------------------------------------

    @Test
    fun `判据2-4_对账口径_落库packjson逐文件锚与zip内字节零漂移`() {
        val service = newService()
        val outcome0 = service.refreshIndex(force = true)
        val fresh = orFail(
            outcome0 as? MarketRefreshOutcome.Fresh,
            "refreshIndex(force) 必须回 Fresh：$outcome0",
        )
        val demo = latestDemoOr(fresh.index)
        // 探针侧独立取 zip：先把索引声明的整包 sha256 自证掉，再解包留档
        val (status, zipBody) = httpGetBytes(demo.zipUrl)
        assertEquals("demo zip 独立下载必须 200：${demo.zipUrl}", HTTP_OK, status)
        assertEquals(
            "独立取回的 zip 与索引声明整包 sha256 一致",
            demo.zipSha256,
            sha256Hex(zipBody),
        )
        val inZip = zipEntriesOf(zipBody)
        val progress = ArrayList<Triple<MarketInstallPhase, Long, Long>>()
        val outcome = service.install(demo) { phase, done, total -> progress += Triple(phase, done, total) }
        val ok = orFail(outcome as? InstallOutcome.Ok, "demo 包必须装成功：$outcome")
        assertTrue("安装路径的进度回调同样至少一条", progress.isNotEmpty())
        assertTrue(
            "COMMIT 段收口读数 = 记账字节（清单累计）：$progress",
            progress.last() == Triple(MarketInstallPhase.COMMIT, ok.pack.bytes, 0L),
        )
        val packDir = MarketStore(File(tmp.root, MarketContract.DIR_NAME))
            .dirOf(demo.kind, demo.id, ok.pack.version)
        val manifest = orFail(
            verifier.parsePackManifest(
                File(packDir, MarketContract.MANIFEST_FILE).readText(Charsets.UTF_8),
            ) as? MarketParseResult.Ok,
            "落库 pack.json 过不了真实解析闸",
        ).value
        assertEquals("落库清单 id 与索引一致", demo.id, manifest.id)
        assertEquals("落库清单版本与索引一致", demo.version, manifest.version)
        assertEquals("fileCount = 清单条目数", manifest.files.size, ok.pack.fileCount)
        for (f in manifest.files) {
            assertTrue("zip 内缺清单声明的 ${f.path}", inZip.containsKey(f.path))
            val onDisk = File(packDir, f.path).readBytes()
            assertEquals("${f.path} 落库字节数 = 清单声明", f.bytes, onDisk.size.toLong())
            assertEquals("${f.path} 落库 sha256 = 清单锚", f.sha256, sha256Hex(onDisk))
            val inZipBytes = inZip.getValue(f.path)
            assertEquals(
                "${f.path} 落库字节与 zip 内字节零漂移",
                sha256Hex(inZipBytes),
                sha256Hex(onDisk),
            )
        }
        assertEquals(
            UninstallOutcome.Ok,
            service.uninstall(demo.kind, demo.id, ok.pack.version),
        )
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 阶段回执判据（CONTRACT-MKM-W6 缝 3，判据 2-3/2-4 共用）：下载段逐笔直通（done 单调
     * 不降、total 均为索引声明 bytes）、EXTRACT 收口回执在场（demo 单块下载必见 done=total
     * 收口笔）、末笔 = COMMIT 段（done = 清单累计字节、total 0）。
     */
    private fun assertPhaseReceipt(
        progress: List<Triple<MarketInstallPhase, Long, Long>>,
        declaredZipBytes: Long,
        committedBytes: Long,
    ) {
        assertTrue("进度回调至少一条：$progress", progress.isNotEmpty())
        val download = progress.filter { it.first == MarketInstallPhase.DOWNLOAD }
        assertTrue(
            "done 单调不降（下载段）：$progress",
            download == download.sortedBy { it.second },
        )
        assertTrue(
            "下载段 total 均为索引声明 bytes（GitHub release 带 Content-Length）：$progress",
            download.all { it.third == declaredZipBytes },
        )
        assertTrue(
            "EXTRACT 收口回执在场：$progress",
            progress.contains(Triple(MarketInstallPhase.EXTRACT, 0L, 0L)),
        )
        assertEquals(
            "末笔 = COMMIT 段（done = 清单累计字节、total 0）：$progress",
            Triple(MarketInstallPhase.COMMIT, committedBytes, 0L),
            progress.last(),
        )
    }

    /**
     * 真实服务组装点（契约 §2.3：临时根下 market 目录 + 真校验器 + 缺省真取数/落库/安装器）。
     * `ERATW_MARKET_E2E_URL` 注入走 [MarketServiceImpl.setEndpoint] 生产写入口（形态闸照核）。
     */
    private fun newService(): MarketServiceImpl {
        val service = MarketServiceImpl(File(tmp.root, MarketContract.DIR_NAME), verifier)
        primaryOverride()?.let { injected ->
            val edit = service.setEndpoint(injected)
            assertTrue(
                "ERATW_MARKET_E2E_URL 必须过端点形态闸（https + 有主机）：$injected → $edit",
                edit is EndpointEditResult.Set || edit is EndpointEditResult.Kept,
            )
        }
        return service
    }

    /**
     * 同 id 取索引最新版（生产比较器 [MarketVerifier.versionCompare]；多版本同 id 时选最高）。
     * 缺条目就地给人话 AssertionError（探针失败不打哑巴，也把调用方的 throw 收进一处）。
     */
    private fun latestDemoOr(ix: MarketIndex): MarketIndexEntry =
        ix.packs.filter { it.id == DEMO_PACK_ID }
            .maxWithOrNull { a, b -> verifier.versionCompare(a.version, b.version) }
            ?: throw AssertionError("索引缺 $DEMO_PACK_ID 条目（探针只许动 demo 系）")

    /** 注入的主端点（未注入 = null，全链走契约缺省）。 */
    private fun primaryOverride(): String? =
        System.getenv(URL_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    /** 探针侧独立 GET（系统默认栈、自动跟跳）；返回 状态码 + 响应体字节，非 2xx 读错误流。 */
    private fun httpGetBytes(url: String): Pair<Int, ByteArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = PROBE_TIMEOUT_MS
        conn.readTimeout = PROBE_TIMEOUT_MS
        try {
            val status = conn.responseCode
            val input = if (status in HTTP_OK_MIN..HTTP_OK_MAX) {
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

    /** zip 字节 → 路径到条目字节（探针侧独立解包；目录条目不收）。 */
    private fun zipEntriesOf(body: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(body.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readAllBytes()
                zip.closeEntry()
            }
        }
        return out
    }

    /** 64 位小写 hex 的 SHA-256（口径同生产 [MarketInstaller] 的 Digests）。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {

        /** env 总闸变量名与放行值（契约 §2.5：`ERATW_MARKET_E2E=1` 才生成用例）。 */
        private const val GATE_ENV = "ERATW_MARKET_E2E"
        private const val GATE_ON = "1"

        /** 主端点注入变量（契约 §1）。 */
        private const val URL_ENV = "ERATW_MARKET_E2E_URL"

        /** demo 系合成包 id（契约 §0.2 内容合规红线：其余条目一律不下载）。 */
        private const val DEMO_PACK_ID = "demo.era-tw"

        /** packs 数量下限（契约 §2.1；2026-11-17 线上快照实测 6）。 */
        private const val MIN_PACKS = 5

        /** 探针超时（毫秒）：索引 15KB 级 + zip 1KB 级，20s 富余。 */
        private const val PROBE_TIMEOUT_MS = 20_000

        private const val HTTP_OK = 200
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299

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

/**
 * 探针失败的非空收口：空值就地折成人话 [AssertionError]（测试红的时候先看得到原因）。
 * 放文件顶层是为了让类的 throw 全部集中到这一处，测试函数体不再各开 throw 口。
 */
private fun <T> orFail(value: T?, why: String): T = value ?: throw AssertionError(why)
