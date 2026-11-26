package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-013 D2 闸 2：compat 表的**读侧 + 注入面**（表本身只是拒绝文案的信息面）。
 *
 * 判据真源（本类逐条钉住，改坏任何一条会红）：
 * - 读侧：[ModCompatTable.load] 缺失（source 返回 null）或读失败（source 抛异常）⇒
 *   [ModDexGate.SurfaceCompat.EMPTY] 且不抛（ModCompatTable.kt:44-48）；坏行由
 *   [ModDexGate.loadCompatTable] 计数丢弃、不抛（ModDexGate.kt:125-143）。
 * - 注入面：[ModCodeCarrierProvider] 构造参数 `abi`（[ModAbiReadout]，内含 `compat`）单路传到
 *   [ModDexLoader.load] 的 `compat`（ModCodeCarrierProvider.kt:149-155 → ModDexLoader.kt:211-221）；
 *   表只改拒绝文案后缀
 *   （ModDexGate.kt:169-173），判定仍是 `compiledSurface <= engineApiVersion` 一条
 *   （ModDexGate.kt:102-116）——表把 surface 标到更高也不许改变放行结论。
 * - 资产内容：`surface<TAB>minEngineApi` 恒等覆盖 1..[MOD_ENGINE_API_VERSION]（证据 = 资产表头注释
 *   引用的 ModApi.kt 版本表 KDoc；本仓没有「surface N 在更早引擎可用」的已发布证据，故不补更低值）。
 */
class ModCompatTableTest {

    // ---- 读侧：真资产与资产形状 ----

    @Test
    fun `真实资产可解析 且恒等覆盖 1 到当前引擎 API`() {
        val text = assetFile().readText(Charsets.UTF_8)
        val table = ModCompatTable.load { text }
        assertEquals("真资产不许有坏行（坏行说明表被写歪）", 0, table.malformedLineCount)
        assertFalse("真资产不许是空表（空表 = 生产等价于没接线）", table.isEmpty)
        for (surface in 1..MOD_ENGINE_API_VERSION) {
            assertEquals(
                "surface v$surface 必须登记，且最低引擎 API = 版本号自身（ModApi.kt:35-153 的恒等证据）",
                surface,
                table.minEngineApiFor(surface),
            )
        }
        assertNull("surface 0 刻意不登记（无 v0 证据，且 compiled=0 永不可查）", table.minEngineApiFor(0))
        assertNull(
            "超出当前引擎 API 的 surface 没有已发布证据可引，不许编",
            table.minEngineApiFor(MOD_ENGINE_API_VERSION + 1),
        )
        val dataLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        assertEquals(
            "表行数必须恰好是 1..MOD_ENGINE_API_VERSION 每版一行：漏补（bump VERSION 没补表）或多写都红",
            MOD_ENGINE_API_VERSION,
            dataLines.size,
        )
    }

    @Test
    fun `资产缺失时回退空表且不抛`() {
        val table = ModCompatTable.load { null }
        assertTrue("资产缺失必须退化成空表（行为 = 接线前的 EMPTY）", table.isEmpty)
        assertEquals(0, table.malformedLineCount)
    }

    @Test
    fun `读资产抛异常时回退空表且不抛`() {
        val table = ModCompatTable.load { throw FileNotFoundException("assets/mod-api-abi-compat.tsv 不存在") }
        assertTrue("读失败必须退化成空表、不许把 dex 装载链带死", table.isEmpty)
        assertEquals(0, table.malformedLineCount)
    }

    @Test
    fun `坏行只计数不抛 合法行照常进表`() {
        val text = listOf("# 注释行", "18\t19", "broken", "20\tx", "21\t-1", "22", "", "23\t24")
            .joinToString(separator = "\n")
        val table = ModCompatTable.load { text }
        assertEquals("四行坏行（缺列/非数字/负值/单列）必须逐行计数", 4, table.malformedLineCount)
        assertEquals("坏行不许连累同行之外的合法行", 19, table.minEngineApiFor(18))
        assertEquals(24, table.minEngineApiFor(23))
        assertNull("负值行被丢弃，不许留残值", table.minEngineApiFor(21))
    }

    @Test
    fun `CRLF 与首尾空行不影响解析`() {
        val table = ModCompatTable.load { "\r\n18\t19\r\n\r\n23\t24\r\n" }
        assertEquals("Windows 换行不算坏行", 0, table.malformedLineCount)
        assertEquals(19, table.minEngineApiFor(18))
        assertEquals(24, table.minEngineApiFor(23))
    }

    // ---- 注入面：表只改文案，不改判定 ----

    @Test
    fun `注入 compat 表后 ABI 超前的拒绝文案带上自引擎版本`() {
        val fixture = fixture(compiledSurface = 21)
        val provider = providerOf(
            fixture,
            engineApiVersion = 20,
            compat = ModDexGate.loadCompatTable("21\t22\n"),
        )
        val collection = provider.collect()
        assertEquals(
            "判定仍是 compiled > engine ⇒ 拒载",
            CodeCarrierOutcome.ENGINE_TOO_OLD,
            collection.records.single().outcome,
        )
        assertTrue("拒载不许出载体", collection.carriers.isEmpty())
        assertTrue(
            "拒绝文案 = base 句 + compat 后缀（ModDexGate.kt:169-173）",
            fixture.logs.any { line ->
                line.contains("本包编译于 mod-api surface v21，当前引擎 API v20") &&
                    line.contains("（compat 表登记该 surface 自引擎 v22 起提供）")
            },
        )
    }

    @Test
    fun `空表对照 同样的拒绝文案没有 compat 后缀`() {
        val fixture = fixture(compiledSurface = 21)
        val provider = providerOf(fixture, engineApiVersion = 20)
        val collection = provider.collect()
        assertEquals(CodeCarrierOutcome.ENGINE_TOO_OLD, collection.records.single().outcome)
        assertTrue(
            "空表时 base 句照旧上日志",
            fixture.logs.any { it.contains("本包编译于 mod-api surface v21，当前引擎 API v20") },
        )
        assertTrue(
            "空表不许凭空补兼容后缀（表只是信息面，缺信息就不说话）",
            fixture.logs.none { it.contains("compat 表登记") },
        )
    }

    @Test
    fun `forGate 传入的 compat 表到达逐包装载点`() {
        val ahead = MOD_ENGINE_API_VERSION + 1
        val fixture = fixture(compiledSurface = ahead)
        val provider = ModCodeCarrierProvider.forGate(
            gameRoot = fixture.gameRoot,
            enabled = true,
            factory = FakeFactory(javaClass.classLoader),
            compat = ModDexGate.loadCompatTable("$ahead\t${ahead + 1}\n"),
            logSink = { line -> fixture.logs += line },
        )
        val collection = provider.collect()
        assertEquals(CodeCarrierOutcome.ENGINE_TOO_OLD, collection.records.single().outcome)
        assertTrue(
            "forGate 的 compat 形参必须一路传到 ModDexLoader.load，文案后缀点名表里的版本",
            fixture.logs.any { it.contains("（compat 表登记该 surface 自引擎 v${ahead + 1} 起提供）") },
        )
    }

    @Test
    fun `判定不变式 表把 surface 标到更高也不改变判定`() {
        // 表说「surface 5 要 v99 才有」：若有人让表参与判定，这条会变成 Rejected。
        val table = ModDexGate.loadCompatTable("5\t99\n")
        val verdict = ModDexGate.verdict(metaOf(compiledSurface = 5), engineApiVersion = 20, compat = table)
        assertEquals(
            "compiled <= engine 恒 Allowed，与表无关（表不是第二真源）",
            ModDexGate.Verdict.Allowed,
            verdict,
        )
        val undeclared = ModDexGate.verdict(metaOf(compiledSurface = null), engineApiVersion = 20, compat = table)
        assertEquals(ModDexGate.Verdict.AllowedUndeclared, undeclared)
    }

    @Test
    fun `判定不变式 表标到更高时装载级仍走通`() {
        val fixture = fixture(compiledSurface = 5)
        val provider = providerOf(fixture, compat = ModDexGate.loadCompatTable("5\t99\n"))
        val collection = provider.collect()
        assertEquals(
            "表只是信息面：compiled 5 <= 引擎 21 ⇒ 装出载体，表怎么说都不影响",
            CodeCarrierOutcome.LOADED,
            collection.records.single().outcome,
        )
        assertEquals(1, collection.carriers.size)
    }

    // ---- 夹具（与 ModCodeCarrierProviderTest 同款生产写端落盘，不复刻判据） ----

    private class Fixture(val gameRoot: File, val logs: MutableList<String>)

    private fun fixture(compiledSurface: Int?): Fixture {
        val gameRoot = Files.createTempDirectory("mod-compat-table").toFile()
        val rt2 = ModEnablement.rt2Dir(gameRoot)
        val artifact = zipOf(manifestJson(compiledSurface = compiledSurface))
        ModCodeStore(rt2).install(DISK_ID, artifact)
        ModCodeIdentity.write(rt2, DISK_ID, DECLARED_ID)
        File(rt2, ModDexGate.trustMarkerName(DECLARED_ID)).writeText("")
        return Fixture(gameRoot, mutableListOf())
    }

    private fun providerOf(
        fixture: Fixture,
        engineApiVersion: Int = MOD_ENGINE_API_VERSION,
        compat: ModDexGate.SurfaceCompat = ModDexGate.SurfaceCompat.EMPTY,
        factory: DexLoaderFactory? = FakeFactory(javaClass.classLoader),
    ): ModCodeCarrierProvider = ModCodeCarrierProvider(
        gameRoot = fixture.gameRoot,
        masterEnabled = true,
        loaderFactory = factory,
        abi = ModAbiReadout(engineApiVersion = engineApiVersion, compat = compat),
        logSink = { line -> fixture.logs += line },
    )

    private fun metaOf(compiledSurface: Int?): PackMeta = PackMeta(
        packageId = DECLARED_ID,
        declaredVersion = "2.0",
        version = "2.0",
        ignored = null,
        evidence = PackMetaEvidence(compiledSurface = compiledSurface),
    )

    /** 产物 = zip(classes.dex + manifest.json)，与导入链产出的内层形状一致。 */
    private fun zipOf(manifestText: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(PAYLOAD)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /** 内层清单（只为装载闸与模块装载器需要的键；其余键不在运行期读）。 */
    private fun manifestJson(compiledSurface: Int?): String {
        val fields = mutableListOf("\"packageId\":\"" + DECLARED_ID + "\"", "\"version\":\"2.0\"")
        fields += "\"entrypoint\":\"" + OK_MOD_FQN + "\""
        if (compiledSurface != null) {
            fields += "\"compiledSurface\":" + compiledSurface
        }
        return "{" + fields.joinToString(separator = ",") + "}"
    }

    /** 资产按 app 模块工作目录解析（与既有资产用例同口径，兼容从仓库根跑）。 */
    private fun assetFile(): File = listOf(
        File("src/main/assets/" + ModCompatTable.ASSET_NAME),
        File("../app/src/main/assets/" + ModCompatTable.ASSET_NAME),
        File("../../app/src/main/assets/" + ModCompatTable.ASSET_NAME),
    ).firstOrNull { it.isFile }
        ?: throw AssertionError("找不到 " + ModCompatTable.ASSET_NAME + "：表必须随 app 模块资产打包")

    /** 记录「按什么身份、拿哪个产物文件」建了 loader；父加载器 = 测试 classpath，故入口类可真装载。 */
    private class FakeFactory(private val parent: ClassLoader?) : DexLoaderFactory {
        val created = mutableListOf<Pair<String, File>>()

        override fun create(packageId: String, artifactFile: File): ClassLoader {
            created += packageId to artifactFile
            return object : ClassLoader(parent) {}
        }
    }

    /** 合法入口类（测试 classpath 可见的最小 EraMod 实现，无参构造）。 */
    class OkMod : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private companion object {
        const val DISK_ID: String = "kojo-0badc0de"
        const val DECLARED_ID: String = "author.compatmod"
        val PAYLOAD: ByteArray = "fake-dex-payload".toByteArray(Charsets.UTF_8)
        val OK_MOD_FQN: String = OkMod::class.java.name
    }
}
