package com.eratw.port.android.market

import com.eratw.port.android.BaseCompileContext
import com.eratw.port.android.ModImportController
import com.eratw.port.android.ModImportFailureKind
import com.eratw.port.android.ModPackageInventory
import com.eratw.port.android.ModRuntimeAssembly
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.security.MessageDigest

/**
 * 口上市场「装进游戏 / 卸载联动」真端点全链探针（CONTRACT-MKM-E2E2 判据 1-3）。
 *
 * ## env 门（契约 §0.1，JUnit 动态用例纪律，同 [MarketEndToEndLiveTest]）

 * `ERATW_MARKET_E2E=1` 才生成用例：行集 [rows] 在变量缺失时返回空集合，Parameterized
 * 对空行集生成 **0 个用例**（不进 XML、不计 skipped，与 Assume 假绿划清界限）。跑带变量轮
 * 前必须 `./gradlew --stop`（守护进程快照式继承环境变量，旧守护进程吃不到新变量）并
 * `cleanTest`（test 任务 inputs 不含环境变量，不 fresh 会 UP-TO-DATE 假绿）。
 * 主端点可用 `ERATW_MARKET_E2E_URL` 注入（缺省 [MarketContract.DEFAULT_INDEX_URL]），
 * 经 [MarketServiceImpl.setEndpoint] 生产写入口落盘——形态不过闸 = 用例红。
 *
 * ## 网络口径（契约 §0.1，同 W2-E2E 先例）

 * 服务取数走 JVM 默认栈（生产 [MarketFetcher]）；本机直连不通时给 Gradle 测试 JVM 传
 * `JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
 * -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890"`（AGENTS §1.1 的 Clash 口；W2-E2E
 * 自证笔记已验证 gradle -D 不生效、JAVA_TOOL_OPTIONS 生效）。
 *
 * ## 内容合规（契约 §0.2）

 * 真端点安装面只动 `demo.era-tw`（CC0-1.0 合成内容）；索引里真实第三方口上条目一个字节
 * 都不下载。「装进游戏成功」半边与判据 2 的第二版本按契约判据 2 Bless 的**手摆夹具法**
 * （照 [MarketGameBridgeTest] 的 installPack 形态）在同一市场库现场构造：pack.json +
 * files 真实落盘、清单过真 [MarketVerifierImpl] 解析闸，内容是本测试自带的合成源，
 * 不触线上任何第三方条目。
 *
 * ## 判据 1 的两半裁定（契约 §2.1）

 * 线上 `demo.era-tw` v1.0.3 的 ERB（471B，2026-11-17 快照）通篇是 `#M_KOJO_K900_DEMO` /
 * `#SLOT_n` 标记行与裸文本行，**没有任何 `@` 函数定义行**；导入链入口判定只认 `@` 行
 * （ModImportController.kt:364-368 取不到入口折 noEntryFunctionFormat；:592 预扫只匹配
 * `@` 前缀；PatchCompiler.kt:914 的 `#` 行只当元数据不产出函数头）⇒ 真包过真桥必折
 * COMPILE「无入口」。这条失败映射本身是「装进游戏全链」的真实一环（真包 → 真清单解析 →
 * 真过滤 → 真编译器），判据 1 上半把它如实钉住；「三件套在场 + 台账记录」的成功半边用
 * 同 (kind,id) 手摆版本（带 `@` 入口的合成源）落上半条链，编译/转码/落盘/台账全真链。
 * 两半合钉判据 1：桥对真内容不作假、对手摆内容走通全链。
 *
 * ## 判据 2 的选择声明（契约 §2.2）

 * 双 zip 造第二版本不可行（换版要换内容、`demo.era-tw` 条目字节不许动）⇒ 选**双包同 id
 * 现场构造**：同一市场库手摆第二版本目录 + 附测同文件集重导（同 packageId 原地替换 +
 * 台账稳定），两种形态都落，覆盖「同包重导」与「换版清理」两个面。
 *
 * ## 反向证明锚（契约 §2.4）

 * 摘掉判据 3 末段的 `bridge.syncUninstalled(kind, id, 0)` 调用（断言 Cleaned 照留）⇒
 * 判据 3 红（产物与台账残留）；恢复调用复绿。
 *
 * ## 断言锚

 * 线上快照（2026-11-17，Clash 代理 curl）：schema=1、packs=6、`demo.era-tw` kind=kojo-pack
 * v1.0.3 zip 1084B、包内 pack.json + files 1 个 ERB（M_KOJO_K900_DEMO.ERB，471B）。
 */
@RunWith(Parameterized::class)
class MarketBridgeLiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val verifier = MarketVerifierImpl()

    // ------------------------------------------------------------------
    // 判据 1：装进游戏全链（真端点：真包失败映射 + 手摆同 id 版本成功半边）
    // ------------------------------------------------------------------

    @Test
    fun `判据1 真端点全链 demo包经桥折COMPILE无入口 手摆同id版本三件套台账齐`() {
        val service = newService()
        val bridge = newBridge()
        val ok = refreshAndInstallDemo(service)
        assertRealDemoImportFails(bridge, ok.pack, service)
        assertHandPlacedImportSucceeds(bridge, ok.pack.kind, ok.pack.id)
    }

    /**
     * 真端点上半的第一段：refreshIndex(force) → install demo.era-tw（真回源、真下载、
     * 真落库记账），断言记账 = 索引声明。
     */
    private fun refreshAndInstallDemo(service: MarketServiceImpl): InstallOutcome.Ok {
        val outcome0 = service.refreshIndex(force = true)
        val fresh = orFail(
            outcome0 as? MarketRefreshOutcome.Fresh,
            "refreshIndex(force) 必须回 Fresh（镜像兜底后仍不行才失败）：$outcome0",
        )
        val demo = latestDemoOr(fresh.index)
        val outcome = service.install(demo) { _, _, _ -> }
        val ok = orFail(outcome as? InstallOutcome.Ok, "demo 包必须装成功：$outcome")
        assertEquals("装库记账版本 = 索引版本", demo.version, ok.pack.version)
        assertEquals(
            "containerSha256 = 索引声明 zip sha256",
            demo.zipSha256,
            ok.pack.containerSha256,
        )
        assertEquals("fresh 库装一条就一条账", 1, service.installed().size)
        return ok
    }

    /** 判据 1 上半：真 demo 包经真桥进真编译链的失败映射（无 @ 入口 → COMPILE、零产物、零台账）。 */
    private fun assertRealDemoImportFails(
        bridge: MarketGameBridge,
        pack: InstalledPack,
        service: MarketServiceImpl,
    ) {
        val importOutcome = bridge.importInstalled(pack, modRuntimeEnabled = true)
        val failed = importOutcome as? MarketBridgeOutcome.ImportFailed
            ?: throw AssertionError(
                "真 demo 包（无 @ 入口）必须折 ImportFailed，实为 ${importOutcome.name()}",
            )
        assertEquals(
            "失败面 = 编译（入口判定在编译相位前段）",
            ModImportFailureKind.COMPILE,
            failed.failure.kind,
        )
        assertTrue(
            "失败消息点名无 @ 函数定义（demo ERB 通篇无 @ 行）：${failed.failure.message}",
            failed.failure.message.contains("@函数定义"),
        )
        assertFalse("编译失败零产物：mods/rt2 不得被创建", rt2Dir(gameRoot).exists())
        assertNull("失败不写台账", ledger().lookup(pack.kind, pack.id))
        assertEquals(
            "装进游戏失败不回滚装库（卸载语义在 service，桥不碰库账）",
            1,
            service.installed().size,
        )
    }

    /** 判据 1 下半：同 (kind,id) 手摆第二版本（带 @ 入口）→ 三件套在场 + 台账记录。 */
    private fun assertHandPlacedImportSucceeds(bridge: MarketGameBridge, kind: String, id: String) {
        val e2ePack = installPack(kind, id, E2E_VERSION, listOf("KOJO_E2E.ERB" to utf8(patchSource)))
        val imported = okOrThrow(bridge.importInstalled(e2ePack, true))
        val packageId = imported.packageId
        assertTrue(
            "packageId 形态 = kojo- + 8 位 hex：$packageId",
            Regex("kojo-[0-9a-f]{8}").matches(packageId),
        )
        val rt2 = rt2Dir(gameRoot)
        for (name in ModPackageInventory.memberNames(packageId)) {
            assertTrue("三件套在场：$name", File(rt2, name).isFile)
        }
        assertEquals(
            "rt2 恰好本包三件套 3 文件（真包零产物、无多余包）",
            3,
            rt2.listFiles()!!.size,
        )
        val record = ledger().lookup(kind, id)
        assertNotNull("台账应记下成功导入", record)
        assertEquals("台账版本 = 手摆版本", E2E_VERSION, record!!.version)
        assertEquals("台账 packageId = 本次导入身份", packageId, record.packageId)
        assertTrue("packageId 非空", record.packageId.isNotBlank())
        assertTrue("importedAtMs 为正", record.importedAtMs > 0)
        assertTrue(
            "provenance 留档源名（末尾换行）",
            "KOJO_E2E.ERB\n" == File(rt2, packageId + ModPackageInventory.SOURCES_EXTENSION)
                .readText(Charsets.UTF_8),
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：重装语义（双包同 id 现场构造：同文件集重导 = 原地替换；换版 = 清理）
    // ------------------------------------------------------------------

    @Test
    fun `判据2 重装语义 同文件集重导原地替换台账稳定 换文件集第二版清理旧三件套`() {
        val bridge = newBridge()
        val v1 = installPack(KOJO_KIND, DEMO_PACK_ID, "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val first = okOrThrow(bridge.importInstalled(v1, true))
        val idA = first.packageId
        val erbm = File(rt2Dir(gameRoot), idA + ModPackageInventory.MODULE_EXTENSION)
        val oldBytes = erbm.readBytes()

        // 同文件集（文件名不变、正文变）⇒ packageId 不变 = 原地替换，台账版本更新、身份稳定。
        val v1u = installPack(KOJO_KIND, DEMO_PACK_ID, "1.0.1", listOf("KOJO.ERB" to utf8(patchSourceUpdated)))
        val second = okOrThrow(bridge.importInstalled(v1u, true))
        assertEquals("同文件集 ⇒ 同 packageId", idA, second.packageId)
        assertTrue("三件套原地保留（同名未删）", erbm.isFile)
        assertFalse("容器内容被新版替换", oldBytes.contentEquals(erbm.readBytes()))
        val reRecord = ledger().lookup(KOJO_KIND, DEMO_PACK_ID)
        assertNotNull("重导后台账仍在", reRecord)
        assertEquals("台账身份稳定", idA, reRecord!!.packageId)
        assertEquals("台账版本更新", "1.0.1", reRecord.version)

        // 换文件集第二版本（KOJO.ERB → KOJO_V2.ERB）⇒ 派生 packageId 必变 + 旧三件套清理。
        val v2 = installPack(KOJO_KIND, DEMO_PACK_ID, "2.0.0", listOf("KOJO_V2.ERB" to utf8(patchSourceV2)))
        val third = okOrThrow(bridge.importInstalled(v2, true))
        val idB = third.packageId
        assertTrue("文件集变了 ⇒ 派生身份必须变（$idA vs $idB）", idA != idB)
        for (name in ModPackageInventory.memberNames(idA)) {
            assertFalse("旧三件套必须被删：$name", File(rt2Dir(gameRoot), name).exists())
        }
        for (name in ModPackageInventory.memberNames(idB)) {
            assertTrue("新三件套在场：$name", File(rt2Dir(gameRoot), name).isFile)
        }
        val v2Record = ledger().lookup(KOJO_KIND, DEMO_PACK_ID)
        assertNotNull("换版后台账改指", v2Record)
        assertEquals(idB, v2Record!!.packageId)
        assertEquals("2.0.0", v2Record.version)
        assertEquals(
            "rt2 无多余包（恰好新三件套 3 文件）",
            3,
            rt2Dir(gameRoot).listFiles()!!.size,
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：卸载联动全链（真端点装库 + 手摆版装进游戏 → 卸库联动两段）
    // ------------------------------------------------------------------

    @Test
    fun `判据3 卸载联动全链 多版本残留Kept 卸到零Cleaned三件套台账库账全清`() {
        val service = newService()
        val outcome0 = service.refreshIndex(force = true)
        val fresh = orFail(outcome0 as? MarketRefreshOutcome.Fresh, "refreshIndex(force) 必须回 Fresh：$outcome0")
        val demo = latestDemoOr(fresh.index)
        val installOutcome = service.install(demo) { _, _, _ -> }
        val demoOk = orFail(installOutcome as? InstallOutcome.Ok, "demo 包必须装成功：$installOutcome")

        val bridge = newBridge()
        val e2ePack = installPack(
            demo.kind,
            demo.id,
            E2E_VERSION,
            listOf("KOJO_E2E.ERB" to utf8(patchSource)),
        )
        val imported = okOrThrow(bridge.importInstalled(e2ePack, true))
        val packageId = imported.packageId

        // 第一段（生产语义链）：卸手摆版、真 demo 版残留 ⇒ 桥不算库账、调用方以 installed()
        // 现算 remainingVersions 传入 = 1 ⇒ Kept(VersionsRemaining)，产物与台账原样。
        assertEquals(
            UninstallOutcome.Ok,
            service.uninstall(demo.kind, demo.id, E2E_VERSION),
        )
        val remaining = service.installed().count { it.kind == demo.kind && it.id == demo.id }
        assertEquals("库内还剩真 demo 版（手摆目录不在库账，卸它不影响账）", 1, remaining)
        assertEquals(
            BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.VersionsRemaining),
            bridge.syncUninstalled(demo.kind, demo.id, remaining),
        )
        for (name in ModPackageInventory.memberNames(packageId)) {
            assertTrue("残留 ⇒ 三件套原样：$name", File(rt2Dir(gameRoot), name).isFile)
        }
        assertNotNull("残留 ⇒ 台账保留", ledger().lookup(demo.kind, demo.id))

        // 第二段（契约判据 3 原文形态）：卸到零 ⇒ syncUninstalled(kind, id, 0) ⇒ 全清。
        assertEquals(
            UninstallOutcome.Ok,
            service.uninstall(demo.kind, demo.id, demoOk.pack.version),
        )
        assertEquals(
            "该键在库里已卸到零",
            0,
            service.installed().count { it.kind == demo.kind && it.id == demo.id },
        )
        assertEquals(
            "末版本联动 = Cleaned（包身份取自台账）",
            BridgeSyncOutcome.Cleaned(packageId),
            bridge.syncUninstalled(demo.kind, demo.id, 0),
        )
        for (name in ModPackageInventory.memberNames(packageId)) {
            assertFalse("三件套消失：$name", File(rt2Dir(gameRoot), name).exists())
        }
        assertNull("台账清", ledger().lookup(demo.kind, demo.id))
        assertTrue("installed 空", service.installed().isEmpty())
        val leftovers = ModStore.modsDirFor(gameRoot).listFiles()?.toList() ?: emptyList()
        assertTrue(
            "mods 只剩留档源（rt2 三件套已清）：$leftovers",
            leftovers.none { it.isDirectory && it.name == "rt2" && it.listFiles()!!.isNotEmpty() },
        )
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 真实服务组装点（W2-E2E 同款：临时根下 market 目录 + 真校验器 + 缺省真取数/落库/
     * 安装器）。`ERATW_MARKET_E2E_URL` 注入走 [MarketServiceImpl.setEndpoint] 生产写入口。
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

    /** 真链桥（MarketGameBridgeTest.newBridge 同款）：marketRoot 与 service 同一目录。 */
    private fun newBridge(): MarketGameBridge = MarketGameBridge(
        marketRoot = marketRoot(),
        gameRoot = gameRoot,
        controllerFactory = { root ->
            ModImportController(
                modStore = ModStore(ModStore.modsDirFor(root)),
                baseSource = {
                    BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null)
                },
                gameRoot = root,
            )
        },
        verifier = MarketVerifierImpl(),
    )

    /** 市场库根（service 与桥共用同一目录；台账与已装包都在其下）。 */
    private fun marketRoot(): File = File(tmp.root, MarketContract.DIR_NAME)

    /** 游戏根（临时目录，导入链的 mods/ 与 rt2 都从它派生）。 */
    private val gameRoot: File by lazy { tmp.newFolder("game") }

    private fun ledger(): MarketImportsLedger =
        MarketImportsLedger(File(marketRoot(), MarketImportsLedger.FILE_NAME))

    private fun rt2Dir(root: File): File = ModRuntimeAssembly.manifestDirFor(root)

    /** mini base 的侧车：A_ONE/A_TWO/A_SUM/A_DEF 函数表 + A_SUM 整型方法。 */
    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    /** 合成 base 基线计数（六个读数自洽即可）。 */
    private fun miniBaseCounts(): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
        instructionCount = 4000,
        functionCount = 4,
        statementCount = 100,
        stringCount = 10,
        longCount = 5,
        exprCount = 20,
    )

    /** 不引用 base 池条目/用户变量的补丁源（符号面只要 A_ONE 在函数表里）。 */
    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0
    """.trimIndent() + "\n"

    /** 与 patchSource 同函数集、不同正文（print 文本变化 ⇒ 容器字节必变、packageId 不变）。 */
    private val patchSourceUpdated = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH_V2
        RETURN 0
    """.trimIndent() + "\n"

    /** 换文件集用的第二份补丁源（不同函数名与不同源名 ⇒ packageId 必变）。 */
    private val patchSourceV2 = """
        @B_SECOND
        CALL A_TWO(1)
        RETURN 0
    """.trimIndent() + "\n"

    /**
     * 手摆市场库版本目录（契约判据 2 Bless 的夹具法，MarketGameBridgeTest.installPack
     * 同款）：pack.json + files 树真实落盘（逐文件 sha256 按真实字节算，清单过真
     * [MarketVerifierImpl] 的解析闸），返回对齐库账口径的记账条目。
     */
    private fun installPack(
        kind: String,
        id: String,
        version: String,
        files: List<Pair<String, ByteArray>>,
    ): InstalledPack {
        val dir = File(File(File(File(marketRoot(), MarketContract.PACKS_DIR), kind), id), version)
        val entries = ArrayList<String>(files.size)
        var total = 0L
        for ((rel, bytes) in files) {
            val target = File(dir, MarketContract.CONTENT_DIR + "/" + rel)
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            entries += "{\"path\":\"" + MarketContract.CONTENT_DIR + "/" + rel + "\",\"bytes\":" +
                bytes.size + ",\"sha256\":\"" + sha256Hex(bytes) + "\"}"
            total += bytes.size
        }
        val packJson = "{\"schema\":" + MarketContract.SCHEMA_VERSION +
            ",\"kind\":\"" + kind + "\",\"id\":\"" + id + "\",\"version\":\"" + version + "\"," +
            "\"name\":\"e2e 手摆包\",\"author\":\"tester\",\"license\":\"CC0-1.0\"," +
            "\"redistribute\":true,\"minAppVersion\":\"0.0.1\",\"files\":[" +
            entries.joinToString(",") + "]}"
        File(dir, MarketContract.MANIFEST_FILE).writeText(packJson, Charsets.UTF_8)
        return InstalledPack(
            kind = kind,
            id = id,
            version = version,
            name = "e2e 手摆包",
            author = "tester",
            license = "CC0-1.0",
            installedAtMs = System.currentTimeMillis(),
            containerSha256 = null,
            contentDigest = null,
            fileCount = files.size,
            bytes = total,
        )
    }

    /**
     * 同 id 取索引最新版（生产比较器 [MarketVerifier.versionCompare]；多版本同 id 时选
     * 最高）。缺条目就地给人话 AssertionError。
     */
    private fun latestDemoOr(ix: MarketIndex): MarketIndexEntry =
        ix.packs.filter { it.id == DEMO_PACK_ID }
            .maxWithOrNull { a, b -> verifier.versionCompare(a.version, b.version) }
            ?: throw AssertionError("索引缺 $DEMO_PACK_ID 条目（探针只许动 demo 系）")

    /** 注入的主端点（未注入 = null，全链走契约缺省）。 */
    private fun primaryOverride(): String? =
        System.getenv(URL_ENV)?.trim()?.takeIf { it.isNotEmpty() }

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** 断言失败信息里的结果形态（不内插密封对象本体）。 */
    private fun MarketBridgeOutcome.name(): String = when (this) {
        is MarketBridgeOutcome.Ok -> "Ok(packageId)"
        MarketBridgeOutcome.NotFound -> "NotFound"
        MarketBridgeOutcome.EmptyPack -> "EmptyPack"
        is MarketBridgeOutcome.ImportFailed -> "ImportFailed(${failure.kind}: ${failure.message})"
        is MarketBridgeOutcome.LedgerFailed -> "LedgerFailed($problem)"
    }

    /** 成功出口统一断言：不是 Ok 就 AssertionError（失败详情进消息；测试函数只留一个 throw 点）。 */
    private fun okOrThrow(outcome: MarketBridgeOutcome): MarketBridgeOutcome.Ok =
        outcome as? MarketBridgeOutcome.Ok
            ?: throw AssertionError("导入应成功，实为 ${outcome.name()}")

    /** 64 位小写 hex 的 SHA-256（口径同生产 [MarketInstaller] 的 Digests）。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { b -> "%02x".format(b.toInt() and BYTE_MASK) }

    private companion object {

        /** env 总闸变量名与放行值（契约 §0.1：`ERATW_MARKET_E2E=1` 才生成用例）。 */
        private const val GATE_ENV = "ERATW_MARKET_E2E"
        private const val GATE_ON = "1"

        /** 主端点注入变量（契约 §0.1）。 */
        private const val URL_ENV = "ERATW_MARKET_E2E_URL"

        /** demo 系合成包 id（契约 §0.2 内容合规红线：其余条目一律不下载）。 */
        private const val DEMO_PACK_ID = "demo.era-tw"

        /** kojo 包种类（与线上 demo 条目 kind 同面；判据 2 手摆面用）。 */
        private const val KOJO_KIND = "kojo-pack"

        /**
         * 手摆版本号（线上 demo v1.0.3 之外的目录名，永不与线上版本撞；三段数字形态，
         * 手摆清单要过 [MarketVerifierImpl] 的版本闸——MarketVerifierImpl.kt:152）。
         */
        private const val E2E_VERSION = "9.9.9"

        /** 合成 base sha（64 位小写十六进制）。 */
        private val BASE_SHA = "a".repeat(64)

        private const val BYTE_MASK = 0xFF

        /**
         * Parameterized 行集：env 门开 = 一行（全部用例各跑一次）；门关 = 空行集
         * = **0 用例生成**（不进 XML、不计 skipped，契约 §0.1 的动态用例纪律）。
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
 * 放文件顶层是为了让类的 throw 全部集中到这一处（W2-E2E 先例同款）。
 */
private fun <T> orFail(value: T?, why: String): T = value ?: throw AssertionError(why)
