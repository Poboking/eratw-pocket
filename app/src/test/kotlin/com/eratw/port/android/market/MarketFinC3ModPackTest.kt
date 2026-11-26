package com.eratw.port.android.market

import com.eratw.port.android.BaseCompileContext
import com.eratw.port.android.ModImportController
import com.eratw.port.android.ModPackageInventory
import com.eratw.port.android.ModRuntimeAssembly
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ERAFIN-C3 市场链 `mod-pack` kind 的定向单测：
 *
 * 1. **kind 闸放行**：verifier（pack.json 解析 + index 解析）与 fields（indexEntryProblem）
 *    对 `mod-pack` 收下；未知 kind 仍拒（反向）；旧三 kind 零回归由既有 Market* 用例全绿兜底。
 * 2. **桥装进游戏**：mod-pack 已装包（`files/` 树带编译产物两件套）→
 *    [MarketGameBridge.importInstalled] 走成品包合同 → `mods/rt2` 三件套按 manifest 自述身份
 *    落盘 + 台账记账；两件套缺失 = ImportFailed 且零落盘零台账（fail-closed 反向）。
 *
 * 两件套由真 [ErbModuleContainer] / [ManifestCodec] 编码（真编译链产容器后重编身份），
 * 不伪造格式；hermetic 零语料依赖（夹具形态照 MarketGameBridgeTest 的 installPack）。
 */
class MarketFinC3ModPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var marketRoot: java.io.File
    private lateinit var gameRoot: java.io.File

    @org.junit.Before
    fun setUp() {
        marketRoot = tmp.newFolder("market")
        gameRoot = tmp.newFolder("game")
    }

    /** modgen 风格的成品包自述身份（非 kojo- 前缀）。 */
    private val modgenPackageId = "eratw.finc3.mktdemo"

    // ------------------------------------------------------------------
    // 夹具：真编译产物两件套（与 ModFinC3PackagedImportTest 同源口径，文件间不共享 private）
    // ------------------------------------------------------------------

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

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0
    """.trimIndent() + "\n"

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private val BASE_SHA = "a".repeat(64)
    }

    /** 真编译链产容器 → 按 packageId 重编身份；manifest 以真 descriptor 保真拷贝 + 钉摘要。 */
    private fun packagedPairBytes(packageId: String): Pair<ByteArray, ByteArray> {
        val buildDir = tmp.newFolder("finc3-build-" + System.nanoTime())
        val controller = ModImportController(
            modStore = ModStore(ModStore.modsDirFor(buildDir)),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = buildDir,
        )
        val outcome = controller.import(
            listOf(com.eratw.port.android.PickedModFile("KOJO_SRC.ERB", patchSource.toByteArray(Charsets.UTF_8))),
        )
        val detail = (outcome as? com.eratw.port.android.ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("真编译链应成功，实为 $outcome")
        val rt2 = ModRuntimeAssembly.manifestDirFor(buildDir)
        val builtId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        val module = ErbModuleContainer.decode(java.io.File(rt2, "$builtId.erbm").readBytes())
        val rebuilt = ErbModuleContainer.encode(
            ErbModuleContainer.ErbModule(
                packageId,
                module.allowSuperCalls,
                module.program,
                module.functions,
                module.signatures,
            ),
        )
        val descriptor = ManifestCodec.decode(java.io.File(rt2, "$builtId.manifest").readBytes())
            .copy(packageId = packageId, version = "1.0.0", artifactSha256 = sha256Hex(rebuilt))
        return rebuilt to ManifestCodec.encode(descriptor)
    }

    /** 市场已装包落盘（夹具形态照 MarketGameBridgeTest.installPack：pack.json + files 树）。 */
    private fun installPack(
        kind: String,
        id: String,
        version: String,
        files: List<Pair<String, ByteArray>>,
    ): InstalledPack {
        val dir =
            java.io.File(
                java.io.File(java.io.File(java.io.File(marketRoot, MarketContract.PACKS_DIR), kind), id),
                version,
            )
        val entries = ArrayList<String>(files.size)
        var total = 0L
        for ((rel, bytes) in files) {
            val target = java.io.File(dir, MarketContract.CONTENT_DIR + "/" + rel)
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
        java.io.File(dir, MarketContract.MANIFEST_FILE).writeText(packJson, Charsets.UTF_8)
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

    private fun rt2Dir(): java.io.File = ModRuntimeAssembly.manifestDirFor(gameRoot)

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { b -> "%02x".format(b.toInt() and 0xFF)

            }

    // ------------------------------------------------------------------
    // kind 闸放行
    // ------------------------------------------------------------------

    @Test
    fun `kind放行——verifier与fields收mod-pack且未知kind仍拒`() {
        val verifier = MarketVerifierImpl()
        val packJson = "{\"schema\":1,\"kind\":\"mod-pack\",\"id\":\"m.demo\",\"version\":\"1.0.0\"," +
            "\"name\":\"n\",\"author\":\"a\",\"license\":\"MIT\",\"redistribute\":true," +
            "\"minAppVersion\":\"0.0.1\",\"files\":[]}"
        val parsed = verifier.parsePackManifest(packJson)
        assertTrue("mod-pack 的 pack.json 应过 verifier：$parsed", parsed is MarketParseResult.Ok)
        assertEquals("mod-pack", (parsed as MarketParseResult.Ok).value.kind)

        val entryJson = "{\"kind\":\"mod-pack\",\"id\":\"m.demo\",\"version\":\"1.0.0\",\"name\":\"n\"," +
            "\"author\":\"a\",\"license\":\"MIT\",\"redistribute\":true,\"minAppVersion\":\"0.0.1\"," +
            "\"zip\":{\"url\":\"https://github.com/x/y/releases/download/v/p.zip\"," +
            "\"sha256\":\"" + "a".repeat(64) + "\",\"bytes\":10},\"fileCount\":0,\"unpackedBytes\":0}"
        val index = verifier.parseIndex(
            "{\"schema\":1,\"generatedAt\":\"2026-11-19\",\"packs\":[$entryJson]}",
            fetchedAtMs = 0L,
        )
        assertTrue("mod-pack 的索引条目应保留：$index", index is MarketParseResult.Ok)
        assertEquals(1, (index as MarketParseResult.Ok).value.packs.size)

        // 反向：未知 kind 两道闸都仍拒（白名单封闭性不受新 kind 影响）。
        val badIndex = verifier.parseIndex(
            "{\"schema\":1,\"generatedAt\":\"2026-11-19\",\"packs\":[" +
                entryJson.replace("\"kind\":\"mod-pack\"", "\"kind\":\"funny-pack\"") + "]}",
            fetchedAtMs = 0L,
        )
        assertTrue(badIndex is MarketParseResult.Ok)
        assertEquals("未知 kind 条目应判弃", 1, (badIndex as MarketParseResult.Ok).value.dropped)
        val badPack = verifier.parsePackManifest(packJson.replace("\"kind\":\"mod-pack\"", "\"kind\":\"funny-pack\""))
        assertTrue("未知 kind 的 pack.json 应拒", badPack is MarketParseResult.Invalid)

        // fields 层：mod-pack 条目闸收下、未知 kind 报「kind 不认识」。
        val entry = parseIndexEntry(entryJson)
        assertEquals(null, indexEntryProblem(entry))
        val badEntry = parseIndexEntry(entryJson.replace("\"kind\":\"mod-pack\"", "\"kind\":\"funny-pack\""))
        assertEquals("kind 不认识", indexEntryProblem(badEntry))
    }

    /** parseIndex 只回整索引；本夹具需要单条目对象 → 走 MarketJson 直解。 */
    private fun parseIndexEntry(entryJson: String): JsonObject = MarketJson.parse(entryJson) as JsonObject

    // ------------------------------------------------------------------
    // 桥：mod-pack 装进游戏走成品包合同
    // ------------------------------------------------------------------

    @Test
    fun `桥——mod-pack已装包装进游戏三件套落盘且台账记账`() {
        val (erbm, manifest) = packagedPairBytes(modgenPackageId)
        val bridge = newBridge()
        val pack = installPack(
            "mod-pack",
            "modgen.demo",
            "1.0.0",
            listOf(
                "$modgenPackageId.erbm" to erbm,
                "$modgenPackageId.manifest" to manifest,
            ),
        )
        val outcome = bridge.importInstalled(pack, modRuntimeEnabled = true)
        val ok = outcome as? MarketBridgeOutcome.Ok
            ?: throw AssertionError("mod-pack 应装进游戏，实为 $outcome")
        assertEquals("packageId = 成品包 manifest 自述身份", modgenPackageId, ok.packageId)
        assertTrue("成功详情应标记成品包形态", ok.detail.packagedModuleOnly)

        // 三件套按 manifest 自述身份落盘（provenance 空名单 + manifest + 容器）。
        val sources = java.io.File(rt2Dir(), modgenPackageId + ModPackageInventory.SOURCES_EXTENSION)
        val manifestFile = java.io.File(rt2Dir(), modgenPackageId + ModPackageInventory.MANIFEST_EXTENSION)
        val erbmFile = java.io.File(rt2Dir(), modgenPackageId + ModPackageInventory.MODULE_EXTENSION)
        assertTrue("provenance 应落盘", sources.isFile)
        assertEquals("\n", sources.readText(Charsets.UTF_8))
        assertTrue("manifest 应落盘", manifestFile.isFile)
        assertTrue("容器应落盘", erbmFile.isFile)
        assertEquals(modgenPackageId, ManifestCodec.decode(manifestFile.readBytes()).packageId)

        // 台账记账（换包/卸载联动的重试面）。
        val record = MarketImportsLedger(java.io.File(marketRoot, MarketImportsLedger.FILE_NAME))
            .lookup("mod-pack", "modgen.demo")
        assertEquals("台账应指向成品包自述身份", modgenPackageId, record?.packageId)
    }

    @Test
    fun `桥——mod-pack同id换版本原地替换台账改指`() {
        val bridge = newBridge()
        fun pairFor(version: String): Pair<ByteArray, ByteArray> {
            val (erbm, manifest) = packagedPairBytes(modgenPackageId)
            val descriptor = ManifestCodec.decode(manifest).copy(version = version)
            return erbm to ManifestCodec.encode(descriptor)
        }
        val v1 = pairFor("1.0.0")
        val pack1 = installPack(
            "mod-pack", "modgen.demo", "1.0.0",
            listOf("$modgenPackageId.erbm" to v1.first, "$modgenPackageId.manifest" to v1.second),
        )
        assertTrue(bridge.importInstalled(pack1, modRuntimeEnabled = true) is MarketBridgeOutcome.Ok)
        val v2 = pairFor("2.0.0")
        val pack2 = installPack(
            "mod-pack", "modgen.demo", "2.0.0",
            listOf("$modgenPackageId.erbm" to v2.first, "$modgenPackageId.manifest" to v2.second),
        )
        val ok2 = bridge.importInstalled(pack2, modRuntimeEnabled = true)
        assertTrue("同 id 换版本应成功：$ok2", ok2 is MarketBridgeOutcome.Ok)
        val manifestFile = java.io.File(rt2Dir(), modgenPackageId + ModPackageInventory.MANIFEST_EXTENSION)
        assertEquals("落盘 manifest 版本应已是 v2", "2.0.0", ManifestCodec.decode(manifestFile.readBytes()).version)
        val record = MarketImportsLedger(java.io.File(marketRoot, MarketImportsLedger.FILE_NAME))
            .lookup("mod-pack", "modgen.demo")
        assertEquals("台账应指向新版本", "2.0.0", record?.version)
    }

    @Test
    fun `桥——mod-pack缺两件套ImportFailed且零落盘零台账`() {
        val bridge = newBridge()
        val pack = installPack("mod-pack", "modgen.demo", "1.0.0", listOf("README.txt" to utf8("no modules")))
        val outcome = bridge.importInstalled(pack, modRuntimeEnabled = true)
        val failed = outcome as? MarketBridgeOutcome.ImportFailed
            ?: throw AssertionError("缺两件套必须失败，实为 $outcome")
        assertEquals(com.eratw.port.android.ModImportFailureKind.STORAGE, failed.failure.kind)
        assertFalse("mods/rt2 不得落任何件", java.io.File(rt2Dir(), modgenPackageId).exists())
        assertFalse(rt2Dir().isDirectory)
        assertNull(
            "台账不得记账",
            MarketImportsLedger(java.io.File(marketRoot, MarketImportsLedger.FILE_NAME))
                .lookup("mod-pack", "modgen.demo"),
        )
    }

}
