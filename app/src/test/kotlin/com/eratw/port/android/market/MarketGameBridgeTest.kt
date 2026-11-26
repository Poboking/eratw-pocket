package com.eratw.port.android.market

import com.eratw.port.android.BaseCompileContext
import com.eratw.port.android.ModImportController
import com.eratw.port.android.ModImportFailureKind
import com.eratw.port.android.ModPackageInventory
import com.eratw.port.android.ModRuntimeAssembly
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 「市场已装包 → 装进游戏」接线桥的定向单测（CONTRACT-MKM-W2 §2.2-§2.6 判据）。
 *
 * 真链口径照 KojoTranscodeChainEndToEndTest 判据 3-2 的直接 new 驱动（:437-457）：假
 * `baseSource`（lambda 缝 + mini 侧车与合成计数，ModImportControllerTest 的 mini face
 * 同源夹具）+ 真 PatchCompiler / PatchImportTranscoder / ErbModuleContainer.encode /
 * ManifestCodec.encode；**hermetic，零语料环境变量依赖**（三门用例才消费真 4.981 产物，
 * 本波夹具不消费它们）。
 *
 * 反向证明锚：摘掉 MarketGameBridge ⑤ 段的旧版三件套删除分支 ⇒ `判据4 更新重导` 用例红；
 * 摘掉台账 upsert 调用 ⇒ `判据2 装进游戏真链` 的台账断言红。
 */
class MarketGameBridgeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var marketRoot: File
    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        marketRoot = tmp.newFolder("market")
        gameRoot = tmp.newFolder("game")
    }

    // ------------------------------------------------------------------
    // 夹具：mini 侧车 + 合成计数 + 真链桥（ModImportControllerTest.kt:61-99 同源夹具）
    // ------------------------------------------------------------------

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
    private fun miniBaseCounts(): com.eratw.port.core.compile.PatchCompiler.BaseCounts =
        com.eratw.port.core.compile.PatchCompiler.BaseCounts(
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

    /** 与 patchSource 同函数集、不同正文（print 文本变化 ⇒ 容器字节必变）。 */
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

    /** 真链桥：控制器照 SettingsActivity:1170-1174 的随用随建形态在工厂里现造。 */
    private fun newBridge(): MarketGameBridge = MarketGameBridge(
        marketRoot = marketRoot,
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

    private fun ledger(): MarketImportsLedger =
        MarketImportsLedger(File(marketRoot, MarketImportsLedger.FILE_NAME))

    private fun rt2Dir(): File = ModRuntimeAssembly.manifestDirFor(gameRoot)

    /**
     * 市场换包/卸载联动判据的「容器三件套」：`.erbm` / `.manifest` / `.sources` 是有语义定义的
     * 固定集合（换包必须整组撤旧、卸载必须整组清）。`.sources.sha256` / `.assetmap` / `.codeid` /
     * `.settings` / `.trusted` 属附加侧车，不参与本判据，也不许拿
     * [ModPackageInventory.memberNames] 顶替（它是「全部包成员」名单，会随侧车增减漂移）。
     */
    private fun containerTrio(packageId: String): List<String> = listOf(
        packageId + ModPackageInventory.SOURCES_EXTENSION,
        packageId + ModPackageInventory.MANIFEST_EXTENSION,
        packageId + ModPackageInventory.MODULE_EXTENSION,
    )

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** 断言失败信息里的结果形态（不内插密封对象本体）。 */
    private fun outcomeName(outcome: MarketBridgeOutcome): String = when (outcome) {
        is MarketBridgeOutcome.Ok -> "Ok(${outcome.packageId})"
        MarketBridgeOutcome.NotFound -> "NotFound"
        MarketBridgeOutcome.EmptyPack -> "EmptyPack"
        is MarketBridgeOutcome.ImportFailed -> "ImportFailed(${outcome.failure.kind}: ${outcome.failure.message})"
        is MarketBridgeOutcome.LedgerFailed -> "LedgerFailed(${outcome.problem})"
    }

    /** 成功出口统一断言：不是 Ok 就 AssertionError（失败详情进消息；测试函数只留一个 throw 点）。 */
    private fun okOrThrow(outcome: MarketBridgeOutcome): MarketBridgeOutcome.Ok =
        outcome as? MarketBridgeOutcome.Ok
            ?: throw AssertionError("导入应成功，实为 ${outcomeName(outcome)}")

    /**
     * 在市场库里装一个包：pack.json + files 树真实落盘（逐文件 sha256 按真实字节算，
     * 清单要过真 MarketVerifierImpl 的解析闸），返回记账条目。
     */
    private fun installPack(
        kind: String,
        id: String,
        version: String,
        files: List<Pair<String, ByteArray>>,
    ): InstalledPack {
        val dir = File(File(File(File(marketRoot, MarketContract.PACKS_DIR), kind), id), version)
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
            "\"name\":\"测试包\",\"author\":\"tester\",\"license\":\"MIT\"," +
            "\"redistribute\":true,\"minAppVersion\":\"0.0.1\",\"files\":[" +
            entries.joinToString(",") + "]}"
        File(dir, MarketContract.MANIFEST_FILE).writeText(packJson, Charsets.UTF_8)
        return InstalledPack(
            kind = kind,
            id = id,
            version = version,
            name = "测试包",
            author = "tester",
            license = "MIT",
            installedAtMs = System.currentTimeMillis(),
            containerSha256 = null,
            contentDigest = null,
            fileCount = files.size,
            bytes = total,
        )
    }

    /** 64 位小写 hex 的 SHA-256（KojoTranscodeChainEndToEndTest:223-234 同款）。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { b -> "%02x".format(b.toInt() and BYTE_MASK) }

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private val BASE_SHA = "a".repeat(64)

        private const val BYTE_MASK = 0xFF
    }

    // ------------------------------------------------------------------
    // 判据 2：装进游戏 happy path（真链）
    // ------------------------------------------------------------------

    @Test
    fun `判据2 装进游戏真链 三件套落盘且台账记账`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val ok = okOrThrow(bridge.importInstalled(pack, modRuntimeEnabled = true))
        val packageId = ok.packageId
        assertTrue(
            "packageId 形态 = kojo- + 8 位 hex：$packageId",
            Regex("kojo-[0-9a-f]{8}").matches(packageId),
        )

        val rt2 = rt2Dir()
        val erbmFile = File(rt2, packageId + ModPackageInventory.MODULE_EXTENSION)
        val manifestFile = File(rt2, packageId + ModPackageInventory.MANIFEST_EXTENSION)
        val sourcesFile = File(rt2, packageId + ModPackageInventory.SOURCES_EXTENSION)
        assertTrue("容器三件套在场", erbmFile.isFile && manifestFile.isFile && sourcesFile.isFile)
        assertEquals("provenance = 留档源名（末尾换行）", "KOJO.ERB\n", sourcesFile.readText(Charsets.UTF_8))
        val module = ErbModuleContainer.decode(erbmFile.readBytes())
        assertEquals("容器读回 = 同一包身份", packageId, module.packageId)
        assertEquals("补丁函数编译进容器", listOf("B_HELLO"), module.functions.map { it.key })
        assertEquals(
            "manifest 读回 = 同一包身份",
            packageId,
            ManifestCodec.decode(manifestFile.readBytes()).packageId,
        )
        assertNull("总闸开着 ⇒ 无关态提示", ok.detail.modRuntimeOffHint)

        val record = ledger().lookup("kojo-pack", "demo") ?: throw AssertionError("台账应记下本次导入")
        assertEquals("1.0.0", record.version)
        assertEquals(packageId, record.packageId)
        assertTrue(record.importedAtMs > 0)
    }

    // ------------------------------------------------------------------
    // 判据 3：过滤 + 空包
    // ------------------------------------------------------------------

    @Test
    fun `判据3 过滤 只有 ERB 与 ERH 进导入链 其余静默跳过`() {
        val bridge = newBridge()
        val pack = installPack(
            "kojo-pack",
            "demo",
            "1.0.0",
            listOf(
                "KOJO.ERB" to utf8(patchSource),
                "sub/HEADER.ERH" to utf8("; 头文件当普通源送编译\n"),
                "readme.txt" to utf8("说明不是源"),
                "data.csv" to utf8("a,b"),
                "docs/notes.md" to utf8("笔记"),
            ),
        )
        val ok = okOrThrow(bridge.importInstalled(pack, true))
        assertEquals(
            "只有两个源条目（文件名序）",
            listOf("KOJO.ERB", "sub/HEADER.ERH"),
            ok.detail.sourceFileNames,
        )
        val mods = ModStore.modsDirFor(gameRoot)
        assertTrue("ERB 留档在 mods", File(mods, "KOJO.ERB").isFile)
        assertTrue("ERH 留档在 mods（切主名）", File(mods, "HEADER.ERH").isFile)
        assertFalse("readme.txt 不落 mods", File(mods, "readme.txt").exists())
        assertFalse("data.csv 不落 mods", File(mods, "data.csv").exists())
        assertFalse("notes.md 不落 mods", File(mods, "notes.md").exists())
        val sources = File(rt2Dir(), ok.packageId + ModPackageInventory.SOURCES_EXTENSION)
            .readText(Charsets.UTF_8)
        assertEquals("provenance 只记源条目", "HEADER.ERH\nKOJO.ERB\n", sources)
    }

    @Test
    fun `判据3 零可选条目 EmptyPack 且导入链未触达零落盘`() {
        val bridge = newBridge()
        val pack = installPack(
            "kojo-pack",
            "demo",
            "1.0.0",
            listOf("readme.txt" to utf8("x"), "data.csv" to utf8("y")),
        )
        val outcome = bridge.importInstalled(pack, true)
        assertTrue("零 ERB/ERH = EmptyPack，实为 ${outcomeName(outcome)}", outcome is MarketBridgeOutcome.EmptyPack)
        assertFalse("mods 目录不得被创建（先过滤后调用）", ModStore.modsDirFor(gameRoot).exists())
        assertNull("台账不得写入", ledger().lookup("kojo-pack", "demo"))
    }

    @Test
    fun `已装目录缺席返回 NotFound 且零写入`() {
        val bridge = newBridge()
        val pack = InstalledPack(
            kind = "kojo-pack", id = "ghost", version = "1.0.0", name = "x", author = "a",
            license = "MIT", installedAtMs = 1L, containerSha256 = null, contentDigest = null,
            fileCount = 0, bytes = 0L,
        )
        val outcome = bridge.importInstalled(pack, true)
        assertTrue("实为 ${outcomeName(outcome)}", outcome is MarketBridgeOutcome.NotFound)
        assertFalse("零写入", ModStore.modsDirFor(gameRoot).exists())
    }

    // ------------------------------------------------------------------
    // 判据 4：更新重导（旧版清理）与原地替换
    // ------------------------------------------------------------------

    @Test
    fun `判据4 更新重导 旧三件套被删新三件套在场台账改指新包`() {
        val bridge = newBridge()
        val v1 = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val first = okOrThrow(bridge.importInstalled(v1, true))
        val idA = first.packageId
        assertTrue(File(rt2Dir(), idA + ModPackageInventory.MODULE_EXTENSION).isFile)

        // 换版本 + 换文件集（KOJO.ERB → KOJO_V2.ERB）⇒ 派生 packageId 必变。
        val v2 = installPack("kojo-pack", "demo", "1.1.0", listOf("KOJO_V2.ERB" to utf8(patchSourceV2)))
        val second = okOrThrow(bridge.importInstalled(v2, true))
        val idB = second.packageId
        assertTrue("文件集变了 ⇒ 派生身份必须变（$idA vs $idB）", idA != idB)

        for (name in containerTrio(idA)) {
            assertFalse("旧三件套必须被删：$name", File(rt2Dir(), name).exists())
        }
        for (name in containerTrio(idB)) {
            assertTrue("新三件套在场：$name", File(rt2Dir(), name).isFile)
        }
        val record = ledger().lookup("kojo-pack", "demo") ?: throw AssertionError("台账应指向新包")
        assertEquals("1.1.0", record.version)
        assertEquals(idB, record.packageId)
        assertTrue(
            "旧版留档源本波不删（契约 §0.3 取舍）",
            File(ModStore.modsDirFor(gameRoot), "KOJO.ERB").isFile,
        )
    }

    @Test
    fun `判据4 packageId 相同重导 原地替换无删除`() {
        val bridge = newBridge()
        val v1 = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val first = okOrThrow(bridge.importInstalled(v1, true))
        val id = first.packageId
        val erbm = File(rt2Dir(), id + ModPackageInventory.MODULE_EXTENSION)
        val oldBytes = erbm.readBytes()

        // 换版本但同文件集（文件名不变、正文变）⇒ packageId 不变 = 原地替换。
        val v2 = installPack("kojo-pack", "demo", "1.1.0", listOf("KOJO.ERB" to utf8(patchSourceUpdated)))
        val second = okOrThrow(bridge.importInstalled(v2, true))
        assertEquals("同文件集 ⇒ 同 packageId", id, second.packageId)
        assertTrue("三件套原地保留（同名未删）", erbm.isFile)
        assertFalse("容器内容被新版替换", oldBytes.contentEquals(erbm.readBytes()))
        val record = ledger().lookup("kojo-pack", "demo") ?: throw AssertionError("台账应更新版本")
        assertEquals("1.1.0", record.version)
        assertEquals(id, record.packageId)
    }

    // ------------------------------------------------------------------
    // 判据 5：gcOrphans 互证
    // ------------------------------------------------------------------

    @Test
    fun `判据5 市场导入产物过 gcOrphans 不删`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val ok = okOrThrow(bridge.importInstalled(pack, true))

        ModPackageInventory.gcOrphans(rt2Dir(), ModStore.modsDirFor(gameRoot))

        for (name in containerTrio(ok.packageId)) {
            assertTrue("留档源在场 ⇒ GC 保守不删：$name", File(rt2Dir(), name).isFile)
        }
    }

    // ------------------------------------------------------------------
    // 判据 6：失败映射
    // ------------------------------------------------------------------

    @Test
    fun `判据6 导入链编译失败折 ImportFailed 且台账不写零产物`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8("; 只有注释\n")))
        val outcome = bridge.importInstalled(pack, true)
        val failed = outcome as? MarketBridgeOutcome.ImportFailed
            ?: throw AssertionError("无入口补丁必须失败，实为 ${outcomeName(outcome)}")
        assertEquals(ModImportFailureKind.COMPILE, failed.failure.kind)
        assertTrue("失败消息原样透出", failed.failure.message.isNotEmpty())
        assertNull("失败不写台账", ledger().lookup("kojo-pack", "demo"))
        assertFalse("编译失败零产物（rt2 目录都不该有）", rt2Dir().exists())
    }

    @Test
    fun `manifest 缺席与解析拒折成 STORAGE 面失败且台账不写`() {
        val bridge = newBridge()
        // ① 目录在、pack.json 缺席。
        val naked = InstalledPack(
            kind = "kojo-pack", id = "naked", version = "1.0.0", name = "x", author = "a",
            license = "MIT", installedAtMs = 1L, containerSha256 = null, contentDigest = null,
            fileCount = 0, bytes = 0L,
        )
        File(File(File(File(marketRoot, MarketContract.PACKS_DIR), "kojo-pack"), "naked"), "1.0.0").mkdirs()
        val missingOutcome = bridge.importInstalled(naked, true)
        val missing = missingOutcome as? MarketBridgeOutcome.ImportFailed
            ?: throw AssertionError("pack.json 缺席必须失败，实为 ${outcomeName(missingOutcome)}")
        assertEquals(ModImportFailureKind.STORAGE, missing.failure.kind)

        // ② pack.json 在但解析拒（坏 JSON）。
        val broken = InstalledPack(
            kind = "kojo-pack", id = "broken", version = "1.0.0", name = "x", author = "a",
            license = "MIT", installedAtMs = 1L, containerSha256 = null, contentDigest = null,
            fileCount = 0, bytes = 0L,
        )
        val brokenDir = File(File(File(File(marketRoot, MarketContract.PACKS_DIR), "kojo-pack"), "broken"), "1.0.0")
        brokenDir.mkdirs()
        File(brokenDir, MarketContract.MANIFEST_FILE).writeText("{ 坏", Charsets.UTF_8)
        val rejectedOutcome = bridge.importInstalled(broken, true)
        val rejected = rejectedOutcome as? MarketBridgeOutcome.ImportFailed
            ?: throw AssertionError("坏清单必须失败，实为 ${outcomeName(rejectedOutcome)}")
        assertEquals(ModImportFailureKind.STORAGE, rejected.failure.kind)
        assertNull(ledger().lookup("kojo-pack", "naked"))
        assertNull(ledger().lookup("kojo-pack", "broken"))
    }

    @Test
    fun `台账写不进去折 LedgerFailed 且导入本体已落盘`() {
        // 占住台账文件名：imports.jsonl 变成目录 ⇒ append 必炸（簿记失败的最小可复现形）。
        File(marketRoot, MarketImportsLedger.FILE_NAME).mkdirs()
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))

        val outcome = bridge.importInstalled(pack, true)
        val failed = outcome as? MarketBridgeOutcome.LedgerFailed
            ?: throw AssertionError("台账写失败必须折 LedgerFailed，实为 ${outcomeName(outcome)}")
        assertTrue(
            "人话必须交代导入本体已落盘：${failed.problem}",
            failed.problem.contains("导入已落盘"),
        )
        val erbmFiles = rt2Dir().listFiles()!!
            .filter { it.name.endsWith(ModPackageInventory.MODULE_EXTENSION) }
        assertEquals("导入本体不受簿记失败影响（容器已落盘）", 1, erbmFiles.size)
    }

    // ------------------------------------------------------------------
    // MKM-W4-UNLINK 判据 1-3/5：末版本卸载联动（真链装进游戏 → 卸库 → syncUninstalled）
    // ------------------------------------------------------------------

    @Test
    fun `判据1 末版本卸载联动 三件套消失台账清 其它包零触碰`() {
        val bridge = newBridge()
        val packA = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val a = okOrThrow(bridge.importInstalled(packA, true))
        val packB = installPack("kojo-pack", "other", "2.0.0", listOf("OTHER.ERB" to utf8(patchSourceV2)))
        val b = okOrThrow(bridge.importInstalled(packB, true))
        assertTrue(
            "负证前提：两包三件套都在场",
            containerTrio(a.packageId).all { File(rt2Dir(), it).isFile } &&
                containerTrio(b.packageId).all { File(rt2Dir(), it).isFile },
        )

        val outcome = bridge.syncUninstalled("kojo-pack", "demo", 0)
        assertEquals(
            "末版本联动 = Cleaned（包身份取自台账）",
            BridgeSyncOutcome.Cleaned(a.packageId),
            outcome,
        )
        for (name in containerTrio(a.packageId)) {
            assertFalse("demo 三件套必须消失：$name", File(rt2Dir(), name).exists())
        }
        for (name in containerTrio(b.packageId)) {
            assertTrue("负证：other 包三件套零触碰：$name", File(rt2Dir(), name).isFile)
        }
        assertNull("台账 demo 记录已清", ledger().lookup("kojo-pack", "demo"))
        assertEquals(
            "负证：other 包台账记录原样",
            b.packageId,
            ledger().lookup("kojo-pack", "other")?.packageId,
        )
    }

    @Test
    fun `判据2 多版本残留 Kept 且三件套与台账原样`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val ok = okOrThrow(bridge.importInstalled(pack, true))
        val erbm = File(rt2Dir(), ok.packageId + ModPackageInventory.MODULE_EXTENSION)
        val containerBefore = erbm.readBytes()

        val outcome = bridge.syncUninstalled("kojo-pack", "demo", 1)
        assertEquals(
            "库内还有版本 = Kept(VersionsRemaining)",
            BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.VersionsRemaining),
            outcome,
        )
        for (name in containerTrio(ok.packageId)) {
            assertTrue("三件套原样：$name", File(rt2Dir(), name).isFile)
        }
        val record = ledger().lookup("kojo-pack", "demo") ?: throw AssertionError("台账应原样")
        assertEquals("1.0.0", record.version)
        assertEquals(ok.packageId, record.packageId)
        assertTrue("容器字节未动", containerBefore.contentEquals(erbm.readBytes()))
    }

    @Test
    fun `判据3 台账无记录 Kept LedgerAbsent 零删零写`() {
        val bridge = newBridge()
        val outcome = bridge.syncUninstalled("kojo-pack", "ghost", 0)
        assertEquals(
            "从未装进游戏 = Kept(LedgerAbsent)",
            BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.LedgerAbsent),
            outcome,
        )
        assertFalse("零删动作：rt2 目录不得被创建", rt2Dir().exists())
        assertTrue("零写动作：台账文件不得被创建", !File(marketRoot, MarketImportsLedger.FILE_NAME).exists())
    }

    @Test
    fun `判据3 产物已不在 gc 过 Kept GamesAbsent 台账行保留`() {
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val ok = okOrThrow(bridge.importInstalled(pack, true))
        // 模拟 gc 已清：三件套整组摘除。worker 裁定（契约 §2.3 二选一）：零文件可删归
        // Kept(GamesAbsent)，一个字节不动比 Cleaned 更保守，台账残留行照 §0.3 等装回覆盖。
        for (name in containerTrio(ok.packageId)) {
            assertTrue(File(rt2Dir(), name).delete())
        }

        val outcome = bridge.syncUninstalled("kojo-pack", "demo", 0)
        assertEquals(
            "零文件可删 = Kept(GamesAbsent)",
            BridgeSyncOutcome.Kept(BridgeSyncOutcome.Cause.GamesAbsent),
            outcome,
        )
        val record = ledger().lookup("kojo-pack", "demo") ?: throw AssertionError("台账行应保留（安全态）")
        assertEquals(ok.packageId, record.packageId)
    }

    @Test
    fun `判据5 删三件套 IO 失败折 Failed 台账不清且重试可续清`() {
        // 逼败手段 = 句柄锁：FileOutputStream 打开的文件没有 FILE_SHARE_DELETE，Windows
        // 上 File.delete() 必败（JDK 17.0.20 / Windows 11 实测；只读属性拦不住 JDK 17 的
        // delete）。Unix 上删除不受打开句柄影响，此逼败法不成立 —— assumeTrue 显式跳过，
        // 不假绿（照 KojoTranscodeChainEndToEndTest 的环境门口径）。
        assumeTrue(
            "句柄锁逼败只在 Windows 成立",
            System.getProperty("os.name")?.contains("windows", ignoreCase = true) == true,
        )
        val bridge = newBridge()
        val pack = installPack("kojo-pack", "demo", "1.0.0", listOf("KOJO.ERB" to utf8(patchSource)))
        val ok = okOrThrow(bridge.importInstalled(pack, true))
        val container = File(rt2Dir(), ok.packageId + ModPackageInventory.MODULE_EXTENSION)

        // 锁容器（名单第三件）：前两件实删、容器删不掉 = 删除相位中途失败。
        val lock = FileOutputStream(container)
        try {
            val outcome = bridge.syncUninstalled("kojo-pack", "demo", 0)
            val failed = outcome as? BridgeSyncOutcome.Failed
                ?: throw AssertionError("删除失败必须折 Failed，实为 $outcome")
            assertTrue("人话点名删不掉的产物", failed.problem.isNotEmpty())
            assertNotNull("台账不清（保重试面）", ledger().lookup("kojo-pack", "demo"))
        } finally {
            lock.close()
        }

        // 锁放掉后重试 = 续清完成（fail-soft 的重试面实断）。
        assertEquals(
            "重试 = Cleaned",
            BridgeSyncOutcome.Cleaned(ok.packageId),
            bridge.syncUninstalled("kojo-pack", "demo", 0),
        )
        for (name in containerTrio(ok.packageId)) {
            assertFalse("重试后三件套清完：$name", File(rt2Dir(), name).exists())
        }
        assertNull("重试后台账清空", ledger().lookup("kojo-pack", "demo"))
    }
}
