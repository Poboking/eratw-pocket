package com.eratw.port.android

import com.eratw.codee2e.CodeE2eMod
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真 pack-mod 代码产物 → 真导入链 → 信任 → `ModCodeCarrierProvider` 装配的跨段 e2e。
 *
 * 与 [ModImportRealCodeProductEndToEndTest] 的分工：那一段钉「真产物落成内容寻址只读 `.mod`」，
 * 本段从这里接着钉「同一份盘面过 `ModCodeCarrierProvider.collect` 后载体名单/结局读数对不对」。
 * 覆盖任务书五条：总闸关零扫描、总闸关载体名单只有内置、未信任 `needs-trust` 不装配、
 * 信任后用**真产物**装配出 `EraMod`、升级重导清旧产物后仍可装载且非 `ambiguous-artifact`；外加
 * `.codeid` 侧车损坏的 `corrupt-identity`、清单缺入口的 `invalid-manifest` 与「总闸开但工厂缺席」
 * 三个拒绝面。
 *
 * 真夹具（[FIXTURE_BASE64]）是 `pack-mod --code --entrypoint` 真产物：内层 `manifest.json` 带
 * `entrypoint` = [EXPECTED_ENTRYPOINT]（= app 测试源集里的真类 `CodeE2eMod`），所以未信任/信任/
 * 侧车损坏/真产物装载各用例**直接导入真产物**，不再替换内层 code.mod。只有两类面保留合成内层
 * 产物并逐条说明理由：升级重导需要「第 2 版产物」（冻结夹具只有一版），清单缺入口失败面在真产物
 * 上不可能出现（写侧 `--entrypoint` 强制）。
 *
 * 真机 ART `DexClassLoader` 实装载不在本刀：工厂注入 JVM fake（父加载器 = 测试 classpath），
 * 与 [ModCodeCarrierProviderTest] 同口径；`TODO(verify)`：真机 dex2oat 装载留后续波次。
 */
class ModCodeCarrierAssemblyEndToEndTest {

    // ------------------------------------------------------------------
    // 1. 总闸关：零扫描零构造 + 载体名单只有内置
    // ------------------------------------------------------------------

    @Test
    fun `总闸关时零扫描且不构造存储与类加载器工厂`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        var storeCalls = 0
        val factory = CountingFactory(javaClass.classLoader)
        val lines = mutableListOf<String>()
        val collection = providerOf(
            gameRoot = gameRoot,
            enabled = false,
            factory = factory,
            logSink = lines::add,
            storeFactory = { dir -> storeCalls += 1; ModCodeStore(dir) },
        ).collect()
        assertTrue("总闸关必须零载体，实际：${collection.carriers}", collection.carriers.isEmpty())
        assertTrue("总闸关必须零读数，实际：${collection.records}", collection.records.isEmpty())
        assertEquals("总闸关连 ModCodeStore 都不许构造", 0, storeCalls)
        assertTrue("总闸关不许建类加载器，实际：${factory.created}", factory.created.isEmpty())
        assertTrue("总闸关不许出装载日志，实际：$lines", lines.isEmpty())
    }

    @Test
    fun `总闸关时载体名单只有内置代码包不追加`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val builtin = ModCarrierRegistry.providers(gameRoot)
        assertTrue("内置载体名单不该为空（测试 classpath 应带 mod-* 模块）", builtin.isNotEmpty())
        val factory = CountingFactory(javaClass.classLoader)
        val merged = ModCarrierRegistry.providers(gameRoot) { builtinIds ->
            ModCodeCarrierProvider.forGate(gameRoot, enabled = false, factory = factory)
                .collect(builtinIds).carriers
        }
        assertEquals("总闸关：合流名单必须与内置名单逐项相同", builtin.map { it.first }, merged.map { it.first })
        assertFalse("总闸关不得追加载体：${merged.map { it.first }}", merged.any { it.first == EXPECTED_PACKAGE_ID })
        assertTrue("总闸关不许建类加载器，实际：${factory.created}", factory.created.isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. 总闸开 – 未信任 / 信任后
    // ------------------------------------------------------------------

    @Test
    fun `总闸开未信任时结局为needs-trust且不装配`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        val factory = CountingFactory(javaClass.classLoader)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot, enabled = true, factory = factory, logSink = lines::add).collect()
        val record = collection.records.single()
        assertEquals("磁盘身份", EXPECTED_PACKAGE_ID, record.diskPackageId)
        assertEquals("运行期身份取 .codeid 的 Bound（与磁盘同值）", EXPECTED_PACKAGE_ID, record.runtimePackageId)
        assertEquals(CodeCarrierOutcome.NEEDS_TRUST, record.outcome)
        assertEquals("canonical 日志 wire 形态", "needs-trust", record.outcome.wire)
        assertTrue("未信任不得装配，实际：${collection.carriers}", collection.carriers.isEmpty())
        assertTrue("未信任不得建类加载器，实际：${factory.created}", factory.created.isEmpty())
        assertTrue(
            "装载闸日志必须记 needs-trust，实际：$lines",
            lines.any { it.contains("outcome=needs-trust") },
        )
    }

    @Test
    fun `信任后真产物装配出载体且入口与运行期身份正确`() {
        val gameRoot = gameRoot()
        val detail = imported(controllerIn(gameRoot).import(listOf(PickedModFile(ZIP_NAME, fixtureZip()))))
        assertEquals(EXPECTED_PACKAGE_ID, detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION))
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val factory = CountingFactory(javaClass.classLoader)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot, enabled = true, factory = factory, logSink = lines::add).collect()
        val carrier = collection.carriers.single()
        assertEquals("载体按运行期身份登记", EXPECTED_PACKAGE_ID, carrier.first)
        assertTrue(
            "载体应是真产物 entrypoint 指向的入口类实例，实际：${carrier.second::class.java.name}",
            carrier.second is CodeE2eMod,
        )
        val record = collection.records.single()
        assertEquals(CodeCarrierOutcome.LOADED, record.outcome)
        assertEquals(EXPECTED_PACKAGE_ID, record.runtimePackageId)
        assertEquals("按运行期身份建且只建一次 loader", 1, factory.created.size)
        assertEquals(EXPECTED_PACKAGE_ID, factory.created.single().first)
        assertEquals(
            "入口 FQN 必须与真产物内层清单逐字相同",
            EXPECTED_ENTRYPOINT,
            CodeE2eMod::class.java.name,
        )
        assertTrue(
            "模块装载日志必须带真产物入口与 loaded，实际：$lines",
            lines.any { it.contains("entrypoint=$EXPECTED_ENTRYPOINT") && it.contains("outcome=loaded") },
        )
    }

    @Test
    fun `未信任到信任同一盘面由拒绝转装载`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        val before = providerOf(gameRoot, enabled = true).collect()
        assertEquals(CodeCarrierOutcome.NEEDS_TRUST, before.records.single().outcome)
        assertTrue(before.carriers.isEmpty())
        assertTrue("信任标记只在信任动作后出现", ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val after = providerOf(gameRoot, enabled = true).collect()
        assertEquals(CodeCarrierOutcome.LOADED, after.records.single().outcome)
        assertEquals(EXPECTED_PACKAGE_ID, after.carriers.single().first)
        assertTrue(
            "信任后装配的必须是真产物入口类，实际：${after.carriers.single().second::class.java.name}",
            after.carriers.single().second is CodeE2eMod,
        )
    }

    // ------------------------------------------------------------------
    // 3. 升级重导：旧产物被清 → 仍可装载且非 ambiguous
    // ------------------------------------------------------------------

    @Test
    fun `升级重导后旧版本产物被清只剩一份`() {
        val gameRoot = gameRoot()
        val store = ModCodeStore(rt2Of(gameRoot))
        importFixture(gameRoot)
        val oldFile = store.list(EXPECTED_PACKAGE_ID).single().file
        // 冻结夹具只有 v1 一版，升级面需要「同包第二版产物」 ⇒ 这里用同身份 + 同真入口契约、
        // 只换 dex 字节的合成内层产物（外层 eratw-pack.json/资源/源文件仍是真产物字节）。
        val newCodeMod = codeModWithEntrypoint(dex = OTHER_FAKE_DEX)
        val pack = packWithCodeMod(newCodeMod)
        val detail = imported(controllerIn(gameRoot).import(listOf(PickedModFile(ZIP_NAME, pack))))
        assertEquals(EXPECTED_PACKAGE_ID, detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION))
        val artifacts = store.list(EXPECTED_PACKAGE_ID)
        assertEquals("升级后同包只许一份产物，实际：$artifacts", 1, artifacts.size)
        assertFalse("旧版本产物必须被清理：${oldFile.name}", oldFile.exists())
        assertEquals(
            "留下的必须是与新 code.mod 同一 sha 前 16 位",
            sha256Hex(newCodeMod).take(SHA16_CHARS),
            artifacts.single().file.name.removeSuffix(MOD_CODE_ARTIFACT_EXTENSION),
        )
        assertEquals(EXPECTED_PACKAGE_ID, artifacts.single().packageId)
    }

    @Test
    fun `升级后载体仍装载且不是ambiguous`() {
        val gameRoot = gameRoot()
        // 同上一例：v2 产物是合成内层件（只有一版真产物），入口仍钉真产物 FQN。
        val newCodeMod = codeModWithEntrypoint(dex = OTHER_FAKE_DEX)
        importFixture(gameRoot)
        imported(controllerIn(gameRoot).import(listOf(PickedModFile(ZIP_NAME, packWithCodeMod(newCodeMod)))))
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val factory = CountingFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot, enabled = true, factory = factory).collect()
        val record = collection.records.single()
        assertEquals(CodeCarrierOutcome.LOADED, record.outcome)
        assertFalse("升级后不得再出 ambiguous-artifact", record.outcome == CodeCarrierOutcome.AMBIGUOUS_ARTIFACT)
        assertEquals(EXPECTED_PACKAGE_ID, collection.carriers.single().first)
        assertEquals(1, factory.created.size)
        val artifacts = ModCodeStore(rt2Of(gameRoot)).list(EXPECTED_PACKAGE_ID)
        assertEquals("loader 拿的必须就是盘上唯一那份新产物", artifacts.single().file, factory.created.single().second)
    }

    // ------------------------------------------------------------------
    // 4. 两个拒绝面：侧车坏 / 工厂缺席
    // ------------------------------------------------------------------

    @Test
    fun `codeid侧车损坏时结局为corrupt-identity且不装配`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val sidecar = File(rt2Of(gameRoot), ModCodeIdentity.fileName(EXPECTED_PACKAGE_ID))
        assertTrue("导入成功后应有 .codeid 侧车：$sidecar", sidecar.isFile)
        sidecar.writeText("not-a-codeid\n")
        val factory = CountingFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot, enabled = true, factory = factory).collect()
        val record = collection.records.single()
        assertEquals("侧车坏必须落到 corrupt-identity", CodeCarrierOutcome.CORRUPT_IDENTITY, record.outcome)
        assertNull("身份没解析出来时运行期身份为 null", record.runtimePackageId)
        assertEquals("磁盘身份仍可读", EXPECTED_PACKAGE_ID, record.diskPackageId)
        assertTrue("侧车坏不得装配，实际：${collection.carriers}", collection.carriers.isEmpty())
        assertTrue("侧车坏不得建类加载器，实际：${factory.created}", factory.created.isEmpty())
    }

    @Test
    fun `真产物带entrypoint时清单闸放行且身份闸可解析`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val factory = CountingFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot, enabled = true, factory = factory).collect()
        val record = collection.records.single()
        assertEquals(
            "真产物内层清单带 entrypoint ⇒ 清单闸放行，结局推进到 loaded",
            CodeCarrierOutcome.LOADED,
            record.outcome,
        )
        assertEquals("身份闸从 .codeid 解析运行期身份", EXPECTED_PACKAGE_ID, record.runtimePackageId)
        assertEquals(EXPECTED_PACKAGE_ID, collection.carriers.single().first)
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `合成缺entrypoint的内层清单仍判invalid-manifest且不装配`() {
        val gameRoot = gameRoot()
        // 真产物恒带 entrypoint（写侧 `--entrypoint` 强制），这个失败面只能合成内层产物；
        // 外层仍走同一条真导入链，只把 code.mod 换成同包身份、清单缺入口的合成件。
        val noEntryManifest = """{"packageId":"$EXPECTED_PACKAGE_ID","version":"1"}"""
        val noEntryMod = zipOf(
            "classes.dex" to FAKE_DEX,
            "manifest.json" to noEntryManifest.toByteArray(Charsets.UTF_8),
        )
        imported(controllerIn(gameRoot).import(listOf(PickedModFile(ZIP_NAME, packWithCodeMod(noEntryMod)))))
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        val factory = CountingFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot, enabled = true, factory = factory).collect()
        val record = collection.records.single()
        assertEquals(CodeCarrierOutcome.INVALID_MANIFEST, record.outcome)
        assertNull("清单坏时运行期身份尚未解析", record.runtimePackageId)
        assertTrue(collection.carriers.isEmpty())
        assertTrue("清单闸在装载前断，不得建类加载器", factory.created.isEmpty())
    }

    @Test
    fun `总闸开但工厂缺席时零扫描并记no-loader-factory`() {
        val gameRoot = gameRoot()
        importFixture(gameRoot)
        assertTrue(ModTrustStore.trust(rt2Of(gameRoot), EXPECTED_PACKAGE_ID))
        var storeCalls = 0
        val lines = mutableListOf<String>()
        val collection = ModCodeCarrierProvider(
            gameRoot = gameRoot,
            masterEnabled = true,
            loaderFactory = null,
            logSink = lines::add,
            storeFactory = { dir -> storeCalls += 1; ModCodeStore(dir) },
        ).collect()
        assertTrue(collection.carriers.isEmpty())
        assertTrue(collection.records.isEmpty())
        assertEquals("工厂缺席 = 装配缺陷：连 store 都不建", 0, storeCalls)
        assertEquals(listOf("mod.dex carrier pkg=- outcome=no-loader-factory"), lines)
    }

    // ------------------------------------------------------------------
    // 夹具：真产物 + 真导入（与 ModImportRealCodeProductEndToEndTest 同源）
    // ------------------------------------------------------------------

    /** 真产物原样导入（不信任）；返回成功后落盘的内层 code.mod 字节。 */
    private fun importFixture(gameRoot: File): ByteArray {
        val zip = fixtureZip()
        assertEquals("整包 sha256 必须与 modgen 侧哨兵同值", FIXTURE_ZIP_SHA256, sha256Hex(zip))
        val codeMod = zipEntryBytes(zip, CODE_MOD_ENTRY)
        assertEquals(
            "内层 code.mod sha256 前 16 位必须与 modgen 侧哨兵同值",
            CODE_MOD_SHA16,
            sha256Hex(codeMod).take(SHA16_CHARS),
        )
        val detail = imported(controllerIn(gameRoot).import(listOf(PickedModFile(ZIP_NAME, zip))))
        assertEquals(EXPECTED_PACKAGE_ID, detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION))
        return codeMod
    }

    /** 外层真产物 zip：只替换 `code.mod` 条目，其余条目字节原样。 */
    private fun packWithCodeMod(codeMod: ByteArray): ByteArray {
        val zip = fixtureZip()
        return zipOf(
            *zipEntryNames(zip).map { name ->
                name to if (name == CODE_MOD_ENTRY) codeMod else zipEntryBytes(zip, name)
            }.toTypedArray(),
        )
    }

    /** 内层 `.mod`：真身份（外层包声明的 [EXPECTED_PACKAGE_ID]）+ 真产物入口 FQN。 */
    private fun codeModWithEntrypoint(
        entrypoint: String = EXPECTED_ENTRYPOINT,
        dex: ByteArray = FAKE_DEX,
    ): ByteArray = zipOf(
        "classes.dex" to dex,
        "manifest.json" to (
            """{"packageId":"$EXPECTED_PACKAGE_ID","version":"1","entrypoint":"$entrypoint"}"""
            ).toByteArray(Charsets.UTF_8),
    )

    private fun providerOf(
        gameRoot: File,
        enabled: Boolean = true,
        factory: DexLoaderFactory? = CountingFactory(javaClass.classLoader),
        logSink: (String) -> Unit = {},
        storeFactory: (File) -> ModCodeStore = ::ModCodeStore,
    ): ModCodeCarrierProvider = ModCodeCarrierProvider(
        gameRoot = gameRoot,
        masterEnabled = enabled,
        loaderFactory = factory,
        logSink = logSink,
        storeFactory = storeFactory,
    )

    private fun controllerIn(tmp: File): ModImportController = ModImportController(
        modStore = ModStore(File(tmp, "mods")),
        baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
        gameRoot = tmp,
    )

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun gameRoot(): File = Files.createTempDirectory("eratw-codecarrier-e2e").toFile()

    private fun rt2Of(tmp: File): File = File(File(tmp, "mods"), "rt2")

    private fun fixtureZip(): ByteArray = Base64.getDecoder().decode(FIXTURE_BASE64)

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

    private fun miniBaseCounts(): PatchCompiler.BaseCounts = PatchCompiler.BaseCounts(
        instructionCount = 4000,
        functionCount = 4,
        statementCount = 100,
        stringCount = 10,
        longCount = 5,
        exprCount = 20,
    )

    private fun zipEntryNames(zip: ByteArray): List<String> =
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            val names = ArrayList<String>()
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
            }
            names
        }

    private fun zipEntryBytes(zip: ByteArray, name: String): ByteArray {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.name == name) return zin.readBytes()
            }
        }
        throw AssertionError("entry not found: $name")
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 记录「按什么运行期身份、拿哪个产物文件」建了 loader；父加载器 = 测试 classpath。 */
    private class CountingFactory(private val parent: ClassLoader?) : DexLoaderFactory {
        val created = mutableListOf<Pair<String, File>>()

        override fun create(packageId: String, artifactFile: File): ClassLoader {
            created += packageId to artifactFile
            return object : ClassLoader(parent) {}
        }
    }

    private companion object {
        /** 与导入端同口径（sha256 前 16 位 = 内容寻址文件名）。 */
        const val SHA16_CHARS = 16

        /** 与夹具模块自述 id 相同的磁盘/运行期身份。 */
        const val EXPECTED_PACKAGE_ID = "kojo-224af7e0"

        /** 真产物内层清单声明的入口 FQN（= 测试源集里真类 `CodeE2eMod`）。 */
        const val EXPECTED_ENTRYPOINT = "com.eratw.codee2e.CodeE2eMod"

        /** 内层 `code.mod` sha256 前 16 位（modgen 侧 `SHA16_HEX` 哨兵，逐字节同源）。 */
        const val CODE_MOD_SHA16 = "1c0db36226c68428"

        /** 整包 sha256（modgen 侧 `FIXTURE_ZIP_SHA256` 哨兵，逐字节同源）。 */
        const val FIXTURE_ZIP_SHA256 = "f4734bb9c6e3fa76b2663e8ff5dc05512b4c8a3f3612d58da1dc09e2c1da0d16"

        /** 外层 zip 根条目名（与 modgen 侧 `PackModCode.CODE_MOD_ENTRY` 同合同）。 */
        const val CODE_MOD_ENTRY = "code.mod"

        /** 导入参数里的任意文件名（真导入链按字节判身份，不看这个名）。 */
        const val ZIP_NAME = "code-e2e.ZIP"

        /** 内层假 dex（本闸不解析内容；dex ABI 闸在 ModDexGate）。 */
        val FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x35, 0x00)

        /** 升级用例的新版本 dex：与 v1 内容不同 ⇒ sha 前缀必不同。 */
        val OTHER_FAKE_DEX = byteArrayOf(0x64, 0x65, 0x78, 0x0A, 0x30, 0x33, 0x36, 0x00)

        /** 合成 base sha（64 位小写十六进制）。 */
        const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        /**
         * 真 `pack-mod --code --entrypoint` 产物（Base64），与
         * `RealCodeProductFixtureTest.REAL_PACK_BASE64`、[ModImportRealCodeProductEndToEndTest.FIXTURE_BASE64]
         * 逐字节相同；内层清单带 [EXPECTED_ENTRYPOINT]。
         * 刷新方式见后两者 KDoc（modgen 侧每次运行落 `tools/modgen/build/code-e2e-fixture.txt`）。
         */
        const val FIXTURE_BASE64 =
            "UEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAY29kZS5tb2RVVAUAAYA1zhIL8GZmEWHg4OBgYGBQZEAG3AycDMk5icXFqcV6" +
            "KakVoSGsDIwNpueEvD3XPjAyMEtlYAjwZufY+eS3IRdQNUh/AE6zeIFm5SbmZaalFpfoZRXn58FNWx2m5eFzzs/H/6tP4KagU+fO" +
            "n7puamTk6WPsbxpwKVDLW1fr3JnzzKckmCpXVqq6TmX9ObNS1aHxyZ+YvVcWHZlUtPfJnylXQhaH2KYrvM2a8mr2fKaQ2RETJNku" +
            "50Rwg9239cWNPTFAF6SB3cfIJMKAcCGy20G+RQXY/I5uArLpvCgm+AAxdh8HeLOygVQwAWEbkH4DVg8AUEsHCFtXnhHuAAAAiAEA" +
            "AFBLAwQUAAgICAAAACEAAAAAAAAAAAAAAAAADwAJAGVyYXR3LXBhY2suanNvblVUBQABgDXOEqtWKk7OSM1NVLIy1FEqSEzOTkxP" +
            "9UxRslLKzs/K1zUyMklMM081UNJRKkstKs7MzwPKGOoZKNUCAFBLBwiVgbsdOQAAADgAAABQSwMEFAAICAgAAAAhAAAAAAAAAAAA" +
            "AAAAAB8ACQBrb2pvLTIyNGFmN2UwLmFzc2V0cy9SRUFETUUudHh0VVQFAAGANc4SS85PSdVNNUpVSCwuTi3hAgBQSwcINNsf7BEA" +
            "AAAPAAAAUEsDBBQACAgIAAAAIQAAAAAAAAAAAAAAAAAIAAkAbWFpbi5FUkJVVAUAAYA1zhJzcIr3cPXx8edydvTxUXCM9/dz1TDW" +
            "5AoI8vQL8VEAS8UHOIY4e3AFuYaEBvkpGHABAFBLBwj6BXSzMgAAADMAAABQSwECFAAUAAgICAAAACEAW1eeEe4AAACIAQAACAAJ" +
            "AAAAAAAAAAAAAAAAAAAAY29kZS5tb2RVVAUAAYA1zhJQSwECFAAUAAgICAAAACEAlYG7HTkAAAA4AAAADwAJAAAAAAAAAAAAAAAt" +
            "AQAAZXJhdHctcGFjay5qc29uVVQFAAGANc4SUEsBAhQAFAAICAgAAAAhADTbH+wRAAAADwAAAB8ACQAAAAAAAAAAAAAArAEAAGtv" +
            "am8tMjI0YWY3ZTAuYXNzZXRzL1JFQURNRS50eHRVVAUAAYA1zhJQSwECFAAUAAgICAAAACEA+gV0szIAAAAzAAAACAAJAAAAAAAA" +
            "AAAAAAATAgAAbWFpbi5FUkJVVAUAAYA1zhJQSwUGAAAAAAQABAAaAQAAhAIAAAAA"
    }
}
