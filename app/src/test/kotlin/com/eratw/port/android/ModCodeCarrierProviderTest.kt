package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DISK_ID: String = "kojo-0badc0de"
private const val SECOND_DISK_ID: String = "kojo-0badc0df"
private const val THIRD_DISK_ID: String = "kojo-0badc0e0"
private const val DECLARED_ID: String = "author.codemod"
private const val OTHER_DECLARED_ID: String = "author.other"
private const val SURFACE_AHEAD: Int = MOD_ENGINE_API_VERSION + 1
private val PAYLOAD: ByteArray = "fake-dex-payload".toByteArray(Charsets.UTF_8)
private val OTHER_PAYLOAD: ByteArray = "other-fake-dex-payload".toByteArray(Charsets.UTF_8)

/**
 * ADR-013 S6b：`ModCodeCarrierProvider`（已信任代码包 → 载体名单）的定向用例。
 *
 * 覆盖面：总闸关的**零扫描零构造**（反证：同一盘面开闸即出载体）、工厂缺席、逐包隔离
 * （未信任 / ABI 超前 / 侧车坏 / 清单坏 / 入口类找不到 / 多份产物）、运行期身份（`Loaded.runtimePackageId`）
 * 与按运行期身份查信任、内置重名跳过（反证：不传内置 id 即出载体）、同运行期身份去重、
 * 日志形态、logSink 抛异常不影响隔离、ABI 拒载的「引擎太旧」细分与进程内装载结论快照
 * （[ModCodeLoadReports]：按磁盘包名 join、关闸发布空结论），以及 [ModCarrierRegistry.providers] 合并重载。
 *
 * 真机 ART 实装载不在本刀：类加载器由 [FakeFactory] 注入，父加载器 = 测试 classpath 加载器，
 * 让入口类能被真装载成 [EraMod]；`DexClassLoader` 真读 dex 的端到端留 TODO(verify)。
 */
class ModCodeCarrierProviderTest {

    // ------------------------------------------------------------------
    // 总闸：零扫描零构造 + 反证
    // ------------------------------------------------------------------

    @Test
    fun `总闸关闭时零扫描零构造`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        var storeCalls = 0
        val lines = mutableListOf<String>()
        val factory = FakeFactory(javaClass.classLoader)
        val collection = providerOf(
            gameRoot = gameRoot,
            enabled = false,
            factory = factory,
            logSink = { lines += it },
            storeFactory = { dir -> storeCalls += 1; ModCodeStore(dir) },
        ).collect()
        assertTrue("总闸关必须返回空载体表，实际：" + collection.carriers, collection.carriers.isEmpty())
        assertTrue("总闸关必须返回空读数表，实际：" + collection.records, collection.records.isEmpty())
        assertEquals("总闸关必须零构造 ModCodeStore（连 store 都不建）", 0, storeCalls)
        assertEquals("总闸关必须零类加载器构造", 0, factory.created.size)
        assertTrue("总闸关必须零日志（什么都没发生），实际：" + lines, lines.isEmpty())
    }

    @Test
    fun `总闸关闭的反证 同一盘面打开总闸即出载体`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val factory = FakeFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot = gameRoot, factory = factory).collect()
        assertEquals("开闸后同一盘面应出 1 个载体，实际：" + collection.records, 1, collection.carriers.size)
        assertEquals(DECLARED_ID, collection.carriers.single().first)
        assertEquals("开闸后应真建 1 次类加载器", 1, factory.created.size)
    }

    // ------------------------------------------------------------------
    // 工厂缺席（装配缺陷）
    // ------------------------------------------------------------------

    @Test
    fun `工厂缺席时零扫描并记一条 no-loader-factory`() {
        val gameRoot = gameRoot()
        install(rt2Of(gameRoot), DISK_ID, manifestJson(), declared = DECLARED_ID)
        var storeCalls = 0
        val lines = mutableListOf<String>()
        val collection = providerOf(
            gameRoot = gameRoot,
            factory = null,
            logSink = { lines += it },
            storeFactory = { dir -> storeCalls += 1; ModCodeStore(dir) },
        ).collect()
        assertTrue(collection.carriers.isEmpty())
        assertEquals("工厂缺席也不许扫盘", 0, storeCalls)
        assertEquals("工厂缺席恰好一条日志", 1, lines.size)
        assertEquals("mod.dex carrier pkg=- outcome=no-loader-factory", lines.single())
    }

    // ------------------------------------------------------------------
    // 运行期身份 + 装配成功
    // ------------------------------------------------------------------

    @Test
    fun `装载成功时返回运行期身份并按运行期身份查信任`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        val artifact = install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val factory = FakeFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot = gameRoot, factory = factory).collect()
        assertEquals("载体名单 key 必须是运行期身份", DECLARED_ID, collection.carriers.single().first)
        val record = collection.records.single()
        assertEquals("读数要能追到磁盘身份", DISK_ID, record.diskPackageId)
        assertEquals(DECLARED_ID, record.runtimePackageId)
        assertEquals(CodeCarrierOutcome.LOADED, record.outcome)
        val (createdFor, createdFile) = factory.created.single()
        assertEquals("工厂必须按运行期身份打标签", DECLARED_ID, createdFor)
        assertEquals("工厂必须拿到内容寻址产物文件", artifact.file, createdFile)
        assertNotNull("载体第二元必须是真 EraMod 实例", collection.carriers.single().second)
    }

    @Test
    fun `一份产物恰好一次类加载器构造 日志两行 loaded`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val lines = mutableListOf<String>()
        providerOf(gameRoot = gameRoot, logSink = { lines += it }).collect()
        assertEquals("装载闸 + 模块装载器各一行", 2, lines.size)
        assertTrue("装载闸行形态：" + lines[0], lines[0].contains("mod.dex disk=" + DISK_ID))
        assertTrue(lines[0].contains("outcome=loaded"))
        assertTrue("模块行形态：" + lines[1], lines[1].contains("mod.dex module pkg=" + DECLARED_ID))
        assertTrue(lines[1].contains("entrypoint=" + OK_MOD_FQN))
    }

    // ------------------------------------------------------------------
    // 逐包隔离：各种失败面只跳过该包
    // ------------------------------------------------------------------

    @Test
    fun `未信任只跳过该包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        val factory = FakeFactory(javaClass.classLoader)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot = gameRoot, factory = factory, logSink = { lines += it }).collect()
        assertTrue("未信任不许出载体", collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.NEEDS_TRUST, collection.records.single().outcome)
        assertEquals("未信任不许建类加载器", 0, factory.created.size)
        assertTrue("日志要带 needs-trust：" + lines, lines.any { it.contains("outcome=needs-trust") })
    }

    @Test
    fun `未信任的反证 同一盘面加信任标记即装载成功`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot).collect()
        assertEquals(CodeCarrierOutcome.LOADED, collection.records.single().outcome)
        assertEquals(1, collection.carriers.size)
    }

    @Test
    fun `ABI 超前只跳过该包并报出引擎太旧`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(compiledSurface = SURFACE_AHEAD), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val factory = FakeFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot = gameRoot, factory = factory).collect()
        assertTrue(collection.carriers.isEmpty())
        val record = collection.records.single()
        assertEquals(CodeCarrierOutcome.ENGINE_TOO_OLD, record.outcome)
        assertEquals(
            "版本缺口必须两侧都给：required = 包要求的编译面，current = 本机引擎面",
            EngineSurfaceGap(required = SURFACE_AHEAD, current = MOD_ENGINE_API_VERSION),
            record.engineTooOld,
        )
        val reason = record.rejectReason
        assertNotNull("ABI 拒载必须把装载闸的原因原文带出来", reason)
        assertTrue(
            "原因原文要带包要求的 surface：$reason",
            reason!!.contains(SURFACE_AHEAD.toString()),
        )
        assertTrue(
            "原因原文要带本机引擎面：$reason",
            reason.contains(MOD_ENGINE_API_VERSION.toString()),
        )
        assertEquals("ABI 拒载发生在类加载之前", 0, factory.created.size)
        // 进程内装载结论快照：设置页 / 概览卡 join 的就是它（按磁盘包名）。
        val report = ModCodeLoadReports.last
        assertNotNull("collect 必须发布装载结论快照", report)
        assertEquals(record, report!!.recordFor(DISK_ID))
        assertEquals(record.engineTooOld, report.engineGapFor(DISK_ID))
    }

    @Test
    fun `装载结论按磁盘包名逐包隔离——一包太旧不污染另一包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(compiledSurface = SURFACE_AHEAD), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        install(
            rt2,
            SECOND_DISK_ID,
            manifestJson(entrypoint = OkMod::class.java.name),
            ArtifactSpec(OTHER_DECLARED_ID, payload = OTHER_PAYLOAD),
        )
        trust(rt2, OTHER_DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot).collect()
        assertEquals("只有一包被拒载", 1, collection.carriers.size)
        val outcomes = collection.records.associate { it.diskPackageId to it.outcome }
        assertEquals(CodeCarrierOutcome.ENGINE_TOO_OLD, outcomes[DISK_ID])
        assertEquals(CodeCarrierOutcome.LOADED, outcomes[SECOND_DISK_ID])
        val report = ModCodeLoadReports.last
        assertNotNull(report)
        assertNotNull("被拒的包要有结论", report!!.recordFor(DISK_ID))
        assertNull("能装载的包不许带版本缺口", report.engineGapFor(SECOND_DISK_ID))
        assertEquals(CodeCarrierOutcome.LOADED, report.recordFor(SECOND_DISK_ID)?.outcome)
    }

    @Test
    fun `总闸关时发布空装载结论而不是留着上一次的`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(compiledSurface = SURFACE_AHEAD), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        // 先跑一次带结论的采集，再造「关闸后重新采集」：快照必须被空结论覆盖，否则设置页会
        // 拿上一轮（甚至是上一局）的结论继续报「引擎太旧」。
        providerOf(gameRoot = gameRoot).collect()
        assertNotNull(ModCodeLoadReports.last?.recordFor(DISK_ID))
        val off = providerOf(gameRoot = gameRoot, enabled = false).collect()
        assertTrue(off.carriers.isEmpty())
        val report = ModCodeLoadReports.last
        assertNotNull("总闸关也要发布快照（空结论）", report)
        assertTrue("关闸后快照必须是空的", report!!.isEmpty)
        assertNull("关闸后不许再查到上一次的结论", report.recordFor(DISK_ID))
    }

    @Test
    fun `内层清单缺省编译面直接放行`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(compiledSurface = null), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot).collect()
        val record = collection.records.single()
        assertEquals(
            "未声明编译面 = 老包放行，实际：" + collection.records,
            CodeCarrierOutcome.LOADED,
            record.outcome,
        )
        assertNull("老包没声明编译面就不该有版本缺口", record.engineTooOld)
        assertNull("装载成功没有拒载原因", record.rejectReason)
        assertNull("装载结论里也不许有缺口", ModCodeLoadReports.last?.engineGapFor(DISK_ID))
    }

    @Test
    fun `侧车损坏只跳过该包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = null)
        File(rt2, ModCodeIdentity.fileName(DISK_ID)).writeText("不是合法侧车")
        val collection = providerOf(gameRoot = gameRoot).collect()
        assertTrue(collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.CORRUPT_IDENTITY, collection.records.single().outcome)
        assertNull("身份不可信时不许猜运行期身份", collection.records.single().runtimePackageId)
    }

    @Test
    fun `内层清单缺 entrypoint 在装载前跳过`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(entrypoint = null), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val factory = FakeFactory(javaClass.classLoader)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot = gameRoot, factory = factory, logSink = { lines += it }).collect()
        assertTrue(collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.INVALID_MANIFEST, collection.records.single().outcome)
        assertEquals("清单坏要在建类加载器之前断", 0, factory.created.size)
        assertTrue("前置跳过要留 canonical 行：" + lines, lines.any { it.contains("outcome=invalid-manifest") })
        assertTrue(lines.any { it.startsWith("mod.dex carrier pkg=" + DISK_ID) })
    }

    @Test
    fun `内层清单坏 JSON 只跳过该包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        installRaw(rt2, DISK_ID, "{ 这不是 json", declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot).collect()
        assertEquals(CodeCarrierOutcome.INVALID_MANIFEST, collection.records.single().outcome)
    }

    @Test
    fun `产物内层炸弹条目在清单扫描处过闸拒绝 只跳过该包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), ArtifactSpec(DECLARED_ID, bombPrefix = true))
        trust(rt2, DECLARED_ID)
        val factory = FakeFactory(javaClass.classLoader)
        val collection = providerOf(gameRoot = gameRoot, factory = factory).collect()
        assertTrue("内层炸弹不许出载体，实际：" + collection.carriers, collection.carriers.isEmpty())
        assertEquals(
            "内层体积闸拒绝折成清单无效：该包在建类加载器之前断",
            CodeCarrierOutcome.INVALID_MANIFEST,
            collection.records.single().outcome,
        )
        assertEquals("闸拒绝必须发生在建类加载器之前", 0, factory.created.size)
    }

    @Test
    fun `入口类找不到只跳过该包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(
            rt2,
            DISK_ID,
            manifestJson(entrypoint = "com.eratw.port.android.NoSuchModClass"),
            declared = DECLARED_ID,
        )
        trust(rt2, DECLARED_ID)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot = gameRoot, logSink = { lines += it }).collect()
        assertTrue(collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.MODULE_FAILED, collection.records.single().outcome)
        assertTrue("模块失败行由模块装载器记：" + lines, lines.any { it.contains("mod.dex module pkg=" + DECLARED_ID) })
    }

    @Test
    fun `多份产物只跳过该包不猜版本`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), ArtifactSpec(DECLARED_ID, payload = PAYLOAD))
        install(rt2, DISK_ID, manifestJson(), ArtifactSpec(DECLARED_ID, payload = OTHER_PAYLOAD))
        trust(rt2, DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot).collect()
        assertTrue(collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.AMBIGUOUS_ARTIFACT, collection.records.single().outcome)
    }

    // ------------------------------------------------------------------
    // 多包混合：一坏不影响其余
    // ------------------------------------------------------------------

    @Test
    fun `三包混合 一坏一未信任仍出其余`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        install(rt2, SECOND_DISK_ID, manifestJson(), declared = OTHER_DECLARED_ID)
        install(rt2, THIRD_DISK_ID, manifestJson(), declared = null)
        File(rt2, ModCodeIdentity.fileName(THIRD_DISK_ID)).writeText("坏侧车")
        val collection = providerOf(gameRoot = gameRoot).collect()
        assertEquals("一坏不影响其余：只出 1 个载体", 1, collection.carriers.size)
        assertEquals(DECLARED_ID, collection.carriers.single().first)
        assertEquals("三个包都要有读数", 3, collection.records.size)
        assertEquals(
            listOf(CodeCarrierOutcome.LOADED, CodeCarrierOutcome.NEEDS_TRUST, CodeCarrierOutcome.CORRUPT_IDENTITY),
            collection.records.map { it.outcome },
        )
    }

    // ------------------------------------------------------------------
    // 内置优先 / 同运行期身份去重
    // ------------------------------------------------------------------

    @Test
    fun `与内置载体重名时跳过代码包`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot = gameRoot, logSink = { lines += it })
            .collect(builtinPackageIds = setOf(DECLARED_ID))
        assertTrue("内置优先：同 id 代码包不许进名单", collection.carriers.isEmpty())
        assertEquals(CodeCarrierOutcome.BUILTIN_CONFLICT, collection.records.single().outcome)
        assertTrue("冲突要记日志：" + lines, lines.any { it.contains("outcome=builtin-conflict") })
    }

    @Test
    fun `内置重名的反证 不传内置 id 即出载体`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot).collect(builtinPackageIds = emptySet())
        assertEquals(1, collection.carriers.size)
        assertEquals(CodeCarrierOutcome.LOADED, collection.records.single().outcome)
    }

    @Test
    fun `两个磁盘包声明同一运行期身份时保留首个`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        install(rt2, SECOND_DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        val lines = mutableListOf<String>()
        val collection = providerOf(gameRoot = gameRoot, logSink = { lines += it }).collect()
        assertEquals("同一运行期身份只许装一次", 1, collection.carriers.size)
        assertEquals(DECLARED_ID, collection.carriers.single().first)
        assertEquals(
            listOf(CodeCarrierOutcome.LOADED, CodeCarrierOutcome.DUPLICATE_RUNTIME_ID),
            collection.records.map { it.outcome },
        )
        assertTrue(lines.any { it.contains("outcome=duplicate-runtime-id") })
    }

    // ------------------------------------------------------------------
    // 日志面 / 宿主接线
    // ------------------------------------------------------------------

    @Test
    fun `logSink 抛异常不破坏逐包隔离`() {
        val gameRoot = gameRoot()
        val rt2 = rt2Of(gameRoot)
        install(rt2, DISK_ID, manifestJson(), declared = DECLARED_ID)
        trust(rt2, DECLARED_ID)
        install(rt2, SECOND_DISK_ID, manifestJson(), declared = OTHER_DECLARED_ID)
        val collection = providerOf(gameRoot = gameRoot, logSink = { throw IllegalStateException("sink 崩了") }).collect()
        assertEquals("日志面崩了不影响出载体", 1, collection.carriers.size)
        assertEquals(2, collection.records.size)
    }

    @Test
    fun `载体注册表合并 内置优先且重复身份去重`() {
        val gameRoot = gameRoot()
        val builtin = ModCarrierRegistry.providers(gameRoot)
        val builtinIds = builtin.map { it.first }
        val seen = mutableListOf<Set<String>>()
        val merged = ModCarrierRegistry.providers(gameRoot) { ids ->
            seen += ids
            listOf("code.carrier.test" to OkMod(), "code.carrier.test" to OkMod())
        }
        assertEquals("回调必须收到内置 id 快照", builtinIds.toSet(), seen.single())
        assertEquals("内置名单必须原样在前", builtinIds, merged.take(builtinIds.size).map { it.first })
        assertEquals("重复运行期身份只追加一次", 1, merged.size - builtinIds.size)
        assertEquals("code.carrier.test", merged.last().first)
    }

    @Test
    fun `载体注册表合并 总闸关时与单参版本逐元素相同`() {
        val gameRoot = gameRoot()
        val plain = ModCarrierRegistry.providers(gameRoot)
        val merged = ModCarrierRegistry.providers(gameRoot) { emptyList() }
        assertEquals(plain.map { it.first }, merged.map { it.first })
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 生产入口指向的生产出口（禁改 GameHost，接线由组长在宿主侧传入）：本类只验缝本身。 */
    private fun gameRoot(): File = Files.createTempDirectory("mod-code-carrier").toFile()

    private fun rt2Of(gameRoot: File): File = ModEnablement.rt2Dir(gameRoot)

    private fun trust(rt2: File, runtimeId: String) {
        File(rt2, ModDexGate.trustMarkerName(runtimeId)).writeText("")
    }

    /** 产物构造取值域：清单之外三样可变项（[install] 的参数收口，避免长参数表）。 */
    private class ArtifactSpec(
        val declared: String?,
        val payload: ByteArray = PAYLOAD,
        val bombPrefix: Boolean = false,
    )

    /** 用生产写端落产物（内容寻址 + 可选侧车），返回生产写端给的产物句柄。 */
    private fun install(rt2: File, diskId: String, manifestText: String, declared: String?): CodeArtifact =
        install(rt2, diskId, manifestText, ArtifactSpec(declared))

    /** 同 [install] 的变体入口：自定义载荷 / 前置炸弹条目。 */
    private fun install(rt2: File, diskId: String, manifestText: String, spec: ArtifactSpec): CodeArtifact {
        val bytes = zipOf(spec.payload, manifestText, spec.bombPrefix)
        val artifact = ModCodeStore(rt2).install(diskId, bytes)
        if (spec.declared != null) {
            ModCodeIdentity.write(rt2, diskId, spec.declared)
        }
        return artifact
    }

    /** 清单原始字节直接落盘（造坏 JSON）。 */
    private fun installRaw(rt2: File, diskId: String, manifestText: String, declared: String?) {
        val bytes = zipOf(PAYLOAD, manifestText)
        ModCodeStore(rt2).install(diskId, bytes)
        if (declared != null) {
            ModCodeIdentity.write(rt2, diskId, declared)
        }
    }

    /** 产物 = zip(classes.dex + manifest.json)，与导入链产出的内层形状一致；[bombPrefix] 在最前面加一个 256 MiB 全零炸弹条目。 */
    private fun zipOf(payload: ByteArray, manifestText: String, bombPrefix: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            if (bombPrefix) {
                // 炸弹条目排最前：装配期清单扫描推进条目时 getNextEntry 的 closeEntry 会把它
                // 静默全量 inflate——不挂闸就每局装配重放一次无界解压。
                zos.putNextEntry(ZipEntry("pack-res.bin"))
                val zeroChunk = ByteArray(64 * 1024)
                var left = 256 * 1024 * 1024
                while (left > 0) {
                    val count = minOf(left, zeroChunk.size)
                    zos.write(zeroChunk, 0, count)
                    left -= count
                }
                zos.closeEntry()
            }
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(payload)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifestText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    /** 内层清单（只为装载闸与模块装载器需要的键；其余键不在运行期读）。 */
    private fun manifestJson(entrypoint: String? = OK_MOD_FQN, compiledSurface: Int? = null): String {
        val fields = mutableListOf("\"packageId\":\"author.codemod\"", "\"version\":\"2.0\"")
        if (entrypoint != null) {
            fields += "\"entrypoint\":\"" + entrypoint + "\""
        }
        if (compiledSurface != null) {
            fields += "\"compiledSurface\":" + compiledSurface
        }
        return "{" + fields.joinToString(separator = ",") + "}"
    }

    private fun providerOf(
        gameRoot: File,
        enabled: Boolean = true,
        factory: DexLoaderFactory? = FakeFactory(javaClass.classLoader),
        logSink: (String) -> Unit = {},
        storeFactory: (File) -> ModCodeStore = ::ModCodeStore,
    ): ModCodeCarrierProvider = ModCodeCarrierProvider(
        gameRoot = gameRoot,
        masterEnabled = enabled,
        loaderFactory = factory,
        logSink = logSink,
        storeFactory = storeFactory,
    )

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
        val OK_MOD_FQN: String = OkMod::class.java.name
    }
}
