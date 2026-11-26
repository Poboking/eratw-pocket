package com.eratw.port.android

import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_PACKAGE_ID: String = "eratw.dexsrc"
private const val OTHER_PACKAGE_ID: String = "eratw.dexsrc2"
private const val ENTRYPOINT_NAME: String = "com.eratw.port.android.DexSourceFakeMod"
private const val ENGINE_API_VERSION: Int = ModApi.VERSION

/** 失败归因行载荷绝不能出现的内部细节：实现类名、异常类名、包路径片段（照 ModDexLoaderTest 口径）。 */
private val FORBIDDEN_REASON_TOKENS: List<String> =
    listOf("Exception", "java.", "kotlin.", "ModCodeStore", "DexLoader", "IllegalState", ".kt")

/**
 * ADR-013 S5/S6/S7：dex 载体供给核心（[ModDexCarrierSource]）的定向用例。
 *
 * 三族判据：
 * 1. **闸与过滤**（S5/S6）：总闸关态零扫盘零构造（反向证明 1）、`.off` 停用连 ClassLoader 都
 *    不建（D4 玩家面第一条）、信任 / ABI 闸照走闸链全序；
 * 2. **入口解析与实例化**（S5）：包内 manifest.json 解析、身份自洽、入口可指派性判在构造之前；
 * 3. **失败收口与归因**（S7）：单包失败收敛成 canonical 归因行（`ModManageDiagnostics` 可解析）、
 *    不向上抛、不互相拖垮、不泄漏内部细节。
 */
class ModDx4DexCarrierSourceTest {

    // ------------------------------------------------------------------
    // 总闸（D5 关态 fail-closed）
    // ------------------------------------------------------------------

    @Test
    fun `总闸关闭时不扫码目录不建类加载器`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        val factory = RecordingFactory()
        val logs = mutableListOf<String>()
        val carriers = sourceOf(gameRoot, factory = factory, enabled = false, logSink = { logs += it }).carriers()
        assertTrue("总闸关必须是空表，实际：$carriers", carriers.isEmpty())
        assertEquals("总闸关时类加载器构造次数必须为 0", 0, factory.createdFor.size)
        assertTrue("总闸关时不得产生任何日志（链一次都没进）", logs.isEmpty())
    }

    @Test
    fun `总闸关闭的反证_同一盘面开闸即产出载体`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        val carriers = sourceOf(gameRoot).carriers()
        assertEquals("开闸应产出恰好一个载体", 1, carriers.size)
        assertEquals(TEST_PACKAGE_ID, carriers.single().first)
        assertTrue("载体实例必须是 EraMod", carriers.single().second is EraMod)
    }

    @Test
    fun `总闸关闭时连不存在的盘面也不碰`() {
        val gameRoot = tempGameRoot()
        // gameRoot 只有 mods/rt2 骨架、没有 code 目录：关态空表且 code 目录不被现造
        //（ModCodeStore 的只读查询不建目录；关态连 list 都不调，磁盘零接触）。
        assertFalse(File(rt2DirOf(gameRoot), MOD_CODE_DIR_NAME).exists())
        val carriers = sourceOf(gameRoot, enabled = false).carriers()
        assertTrue(carriers.isEmpty())
        assertFalse("关态不得现造 code 目录", File(rt2DirOf(gameRoot), MOD_CODE_DIR_NAME).exists())
    }

    // ------------------------------------------------------------------
    // .off 停用（ADR-013 D4：连 ClassLoader 都不建）
    // ------------------------------------------------------------------

    @Test
    fun `停用标记让包连类加载器都不建且不归因`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        assertTrue(ModEnablement.setEnabled(rt2, TEST_PACKAGE_ID, enabled = false))
        val factory = RecordingFactory()
        val logs = mutableListOf<String>()
        val carriers = sourceOf(gameRoot, factory = factory, logSink = { logs += it }).carriers()
        assertTrue("停用包必须被静默摘除，实际：$carriers", carriers.isEmpty())
        assertEquals("停用包不得构造类加载器", 0, factory.createdFor.size)
        assertTrue("停用过滤与 Kotlin 载体同口径（静默，不归因）", logs.isEmpty())
    }

    @Test
    fun `停用过滤只摘被停用的包_未停用的包照常产出`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        installArtifact(rt2, manifestJson = manifestJson(packageId = OTHER_PACKAGE_ID), packageId = OTHER_PACKAGE_ID)
        trust(rt2, OTHER_PACKAGE_ID)
        assertTrue(ModEnablement.setEnabled(rt2, TEST_PACKAGE_ID, enabled = false))
        val carriers = sourceOf(gameRoot).carriers()
        assertEquals("只该产出未停用的那一个包", listOf(OTHER_PACKAGE_ID), carriers.map { it.first })
    }

    // ------------------------------------------------------------------
    // 闸链（ABI 闸 / 信任）
    // ------------------------------------------------------------------

    @Test
    fun `ABI 闸拒绝超前编译面且不建类加载器`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson(compiledSurface = ENGINE_API_VERSION + 1))
        trust(rt2)
        val factory = RecordingFactory()
        val logs = mutableListOf<String>()
        val carriers = sourceOf(gameRoot, factory = factory, logSink = { logs += it }).carriers()
        assertTrue("超前编译面必须拒载，实际：$carriers", carriers.isEmpty())
        assertEquals("ABI 闸拒绝时类加载器构造次数必须为 0", 0, factory.createdFor.size)
        assertTrue(
            "拒载必须走归因行（mod.install 形状）",
            installRows(logs).single().contains("mod.install pkg=$TEST_PACKAGE_ID active=false failure="),
        )
    }

    @Test
    fun `没有信任标记时不产载体且归因行可被设置页解析`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        val logs = mutableListOf<String>()
        val carriers = sourceOf(gameRoot, logSink = { logs += it }).carriers()
        assertTrue(carriers.isEmpty())
        val row = installRows(logs).single()
        assertTrue("归因行必须是 canonical 形状：$row", row.startsWith("mod.install pkg=$TEST_PACKAGE_ID active=false failure="))
        // 跨源对账：本行形状必须能被 ModManageDiagnostics（设置页失败小节的唯一消费者）解析入账。
        ModManageDiagnostics.reset()
        ModManageDiagnostics.record(row)
        assertTrue(
            "归因行必须能被 ModManageDiagnostics 入账",
            ModManageDiagnostics.snapshot().containsKey(TEST_PACKAGE_ID),
        )
        ModManageDiagnostics.forget(TEST_PACKAGE_ID)
    }

    // ------------------------------------------------------------------
    // 包内元数据（ADR-013 D1 manifest.json）
    // ------------------------------------------------------------------

    @Test
    fun `包内没有元数据条目时fail-closed且不建类加载器`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = null)
        trust(rt2)
        val factory = RecordingFactory()
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertEquals(0, factory.createdFor.size)
        assertAttributed(attributed, "元数据")
    }

    @Test
    fun `自述包名与落地目录不符时fail-closed`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson(packageId = OTHER_PACKAGE_ID))
        trust(rt2)
        val factory = RecordingFactory()
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertEquals(0, factory.createdFor.size)
        assertAttributed(attributed, "自述包名")
    }

    @Test
    fun `元数据未声明入口类时fail-closed`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson(entrypoint = null))
        trust(rt2)
        val factory = RecordingFactory()
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertEquals(0, factory.createdFor.size)
        assertAttributed(attributed, "入口类")
    }

    // ------------------------------------------------------------------
    // 入口解析与实例化（S5）
    // ------------------------------------------------------------------

    @Test
    fun `入口类加载失败时收敛成归因行且不向上抛`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson(entrypoint = "com.example.Missing"))
        trust(rt2)
        val factory = RecordingFactory(resolve = { throw ClassNotFoundException(it) })
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertAttributed(attributed, "入口类")
    }

    @Test
    fun `入口类不是EraMod时拒装且不实例化`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        // 可指派性判在构造之前：返回任意非 EraMod 类（String）时连构造都不发生。
        val factory = RecordingFactory(resolve = { String::class.java })
        val before = DexSourceFakeMod.instantiations.get()
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertEquals("入口不是 EraMod 时不得实例化", before, DexSourceFakeMod.instantiations.get())
        assertAttributed(attributed, "不是合法的 Mod 入口")
    }

    @Test
    fun `入口类无法实例化时收敛成归因行`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        // 抽象类可指派 EraMod 但没有无参构造可实例化：newInstance 抛 InstantiationException。
        val factory = RecordingFactory(resolve = { AbstractDexSourceMod::class.java })
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertAttributed(attributed, "无法创建实例")
    }

    @Test
    fun `每次供给都实例化新载体_DZ重装语义`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        val first = sourceOf(gameRoot).carriers().single()
        val second = sourceOf(gameRoot).carriers().single()
        assertFalse(
            "两次供给必须是不同的 mod 实例（每包一个新 loader、新实例）",
            first.second === second.second,
        )
        assertTrue("两次供给各建一个实例", DexSourceFakeMod.instantiations.get() >= 2)
    }

    // ------------------------------------------------------------------
    // 失败收口与归因（S7）
    // ------------------------------------------------------------------

    @Test
    fun `单包失败不拖垮其余包`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson(entrypoint = null), packageId = TEST_PACKAGE_ID)
        trust(rt2, TEST_PACKAGE_ID)
        installArtifact(rt2, manifestJson = manifestJson(packageId = OTHER_PACKAGE_ID), packageId = OTHER_PACKAGE_ID)
        trust(rt2, OTHER_PACKAGE_ID)
        val carriers = sourceOf(gameRoot).carriers()
        assertEquals("坏包只回滚它自己，好包照常产出", listOf(OTHER_PACKAGE_ID), carriers.map { it.first })
    }

    @Test
    fun `多版本产物拒猜且不建类加载器`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        installArtifact(rt2, manifestJson = manifestJson(), payloadMark = "v2")
        trust(rt2)
        val factory = RecordingFactory()
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue(carriers.isEmpty())
        assertEquals(0, factory.createdFor.size)
        assertAttributed(attributed, "多个版本")
    }

    @Test
    fun `产物内层炸弹条目在清单扫描处过闸拒绝且收敛成归因行`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson(), bombFirst = true)
        trust(rt2)
        val factory = RecordingFactory()
        val (carriers, attributed) = sourceWithAttribution(gameRoot, factory = factory)
        assertTrue("内层炸弹不许产出载体：$carriers", carriers.isEmpty())
        assertEquals("闸拒绝发生在建类加载器之前", 0, factory.createdFor.size)
        assertAttributed(attributed, "解压量越限")
    }

    @Test
    fun `归因行载荷不含内部类名与异常名`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = null)
        trust(rt2)
        val logs = mutableListOf<String>()
        sourceOf(gameRoot, logSink = { logs += it }).carriers()
        val payload = logs.single().substringAfter("failure=")
        for (token in FORBIDDEN_REASON_TOKENS) {
            assertFalse("归因载荷泄漏内部细节「$token」：$payload", payload.contains(token))
        }
        assertTrue("归因载荷必须非空", payload.isNotBlank())
    }

    @Test
    fun `logSink与取证回调抛异常都不影响载体供给`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        val carriers = sourceOf(
            gameRoot,
            logSink = { throw IllegalStateException("log sink 崩了") },
            onAttribution = { _, _ -> throw IllegalStateException("取证崩了") },
        ).carriers()
        assertEquals("诊断面异常不得改写供给结果", 1, carriers.size)
    }

    @Test
    fun `取证回调按包收到失败原因`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = null)
        trust(rt2)
        val attributed = mutableMapOf<String, String>()
        val carriers = sourceOf(
            gameRoot,
            onAttribution = { packageId, reason -> attributed[packageId] = reason },
        ).carriers()
        assertTrue(carriers.isEmpty())
        assertEquals(TEST_PACKAGE_ID, attributed.keys.single())
        assertTrue(attributed.getValue(TEST_PACKAGE_ID).isNotBlank())
    }

    // ------------------------------------------------------------------
    // Lane DZ（ERAFIN2）：单包取件缝 carrierForPackage——页边界重装路径的供给源
    // ------------------------------------------------------------------

    @Test
    fun `单包取件缝_多包盘面只取点名包且缺包归因返null`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        installArtifact(rt2, manifestJson = manifestJson(packageId = OTHER_PACKAGE_ID), packageId = OTHER_PACKAGE_ID)
        trust(rt2, OTHER_PACKAGE_ID)
        val logs = mutableListOf<String>()
        val source = sourceOf(gameRoot, logSink = { logs += it })
        val single = source.carrierForPackage(TEST_PACKAGE_ID)
        assertNotNull("点名包必须可取（与 carriers 同一条闸链现造新实例）", single)
        assertEquals(TEST_PACKAGE_ID, single!!.first)
        assertTrue(single.second is EraMod)
        val before = DexSourceFakeMod.instantiations.get()
        val missing = source.carrierForPackage("eratw.nosuch")
        assertNull("不在 code 目录的包必须 null（读产物失败归因）", missing)
        assertEquals("缺包必须走归因行（mod.install 形状）", 1, installRows(logs).size)
        assertTrue(
            "归因行点名缺包",
            installRows(logs).single().contains("mod.install pkg=eratw.nosuch active=false failure="),
        )
        assertEquals("缺包不得构造类加载器", before, DexSourceFakeMod.instantiations.get())
    }

    @Test
    fun `单包取件缝_关态与停用与carriers同口径旁路`() {
        val gameRoot = tempGameRoot()
        val rt2 = rt2DirOf(gameRoot)
        installArtifact(rt2, manifestJson = manifestJson())
        trust(rt2)
        val factory = RecordingFactory()
        val logs = mutableListOf<String>()
        val off = sourceOf(gameRoot, factory = factory, enabled = false, logSink = { logs += it })
        assertNull("总闸关 = 单包也整段旁路", off.carrierForPackage(TEST_PACKAGE_ID))
        assertEquals("关态零构造", 0, factory.createdFor.size)
        assertTrue("关态零日志（链一次都没进）", installRows(logs).isEmpty())
        assertTrue(ModEnablement.setEnabled(rt2, TEST_PACKAGE_ID, enabled = false))
        val disabled = sourceOf(gameRoot, factory = RecordingFactory(), logSink = { logs += it })
        assertNull("停用包单包取件也静默 null（D4：连 ClassLoader 都不建）", disabled.carrierForPackage(TEST_PACKAGE_ID))
    }

    @Test
    fun `缝类型缺省单包取件fail-closed返回null`() {
        // lambda 形态的 DexCarrierProvision（Lane DZ 之前的装配面）没有覆写 carrierFor：
        // 缺省实现恒 null = 未接线单包缝的供给端 fail-closed（重装请求静默作废，不整表实例化）。
        val provision = DexCarrierProvision { _, _ -> emptyList() }
        assertNull(
            "缺省实现必须 fail-closed",
            provision.carrierFor(tempGameRoot(), TEST_PACKAGE_ID) { },
        )
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 记录构造次数与解析结果的假工厂；resolve 可注入各种入口解析病态。 */
    private class RecordingFactory(
        val resolve: (String) -> Class<*> = { DexSourceFakeMod::class.java },
    ) : DexLoaderFactory {
        val createdFor = mutableListOf<String>()

        override fun create(packageId: String, artifactFile: File): ClassLoader =
            FakeClassLoader(resolve).also { createdFor += packageId }
    }

    /** 只会按注入函数「加载」入口类的假 ClassLoader（真机 DexClassLoader 行为不在此验证面）。 */
    private class FakeClassLoader(private val resolve: (String) -> Class<*>) : ClassLoader(null) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> = resolve(name)
    }

    private fun tempGameRoot(): File = Files.createTempDirectory("mod-dex-carrier").toFile()

    private fun rt2DirOf(gameRoot: File): File = File(File(gameRoot, "mods"), "rt2")

    private fun manifestJson(
        packageId: String = TEST_PACKAGE_ID,
        compiledSurface: Int? = null,
        entrypoint: String? = ENTRYPOINT_NAME,
    ): String = buildString {
        append("{")
        append("\"packageId\":\"").append(packageId).append("\",")
        append("\"version\":\"1.0\",")
        if (compiledSurface != null) append("\"compiledSurface\":").append(compiledSurface).append(",")
        append("\"entrypoint\":")
        if (entrypoint == null) append("null") else append("\"").append(entrypoint).append("\"")
        append("}")
    }

    /** 用真实 ModCodeStore.install 落一个内容寻址产物（zip 体：manifest.json + 占位 dex；[bombFirst] 在最前面加 256 MiB 全零炸弹条目）。 */
    private fun installArtifact(
        rt2: File,
        manifestJson: String?,
        packageId: String = TEST_PACKAGE_ID,
        payloadMark: String = "dex",
        bombFirst: Boolean = false,
    ): File {
        val entries = buildMap {
            if (bombFirst) {
                // 炸弹条目排最前：readMeta 取 manifest.json 前必须先把它读尽（closeEntry 丢弃语义）。
                put("pack-res.bin", ByteArray(256 * 1024 * 1024))
            }
            put("classes.dex", (payloadMark + "-classes").toByteArray(Charsets.UTF_8))
            if (manifestJson != null) put(DEX_MANIFEST_ENTRY_NAME, manifestJson.toByteArray(Charsets.UTF_8))
        }
        return ModCodeStore(rt2).install(packageId, zipBytesOf(entries)).file
    }

    private fun trust(rt2: File, packageId: String = TEST_PACKAGE_ID) {
        assertTrue("夹具信任标记必须写成功", ModTrustStore.trust(rt2, packageId))
    }

    private fun sourceOf(
        gameRoot: File,
        factory: DexLoaderFactory = RecordingFactory(),
        enabled: Boolean = true,
        logSink: (String) -> Unit = {},
        onAttribution: ((String, String) -> Unit)? = null,
    ): ModDexCarrierSource = ModDexCarrierSource(
        gameRoot = gameRoot,
        factory = factory,
        dexLoaderEnabled = enabled,
        logSink = logSink,
        engineApiVersion = ENGINE_API_VERSION,
        onAttribution = onAttribution,
    )

    /** 供给一次并捕获逐包归因（失败收口用例共用；归因在主运行里捕获，不重跑夹具）。 */
    private fun sourceWithAttribution(
        gameRoot: File,
        factory: DexLoaderFactory = RecordingFactory(),
        enabled: Boolean = true,
    ): Pair<List<Pair<String, EraMod>>, Map<String, String>> {
        val attributed = mutableMapOf<String, String>()
        val carriers = sourceOf(
            gameRoot,
            factory = factory,
            enabled = enabled,
            onAttribution = { packageId, reason -> attributed[packageId] = reason },
        ).carriers()
        return carriers to attributed
    }

    /** 断言恰好一条单包归因，原因含 [reasonToken] 且不泄漏内部细节。 */
    private fun assertAttributed(attributed: Map<String, String>, reasonToken: String) {
        val reason = attributed.values.singleOrNull()
        assertNotNull("应有一条单包归因：$attributed", reason)
        assertTrue("归因原因应含「$reasonToken」：$reason", reason!!.contains(reasonToken))
        for (token in FORBIDDEN_REASON_TOKENS) {
            assertFalse("归因原因泄漏内部细节「$token」：$reason", reason.contains(token))
        }
    }

    /** 归因行（mod.install 形状）过滤：logSink 上还混着 ModDexLoader 自身的 mod.dex trace 行。 */
    private fun installRows(logs: List<String>): List<String> = logs.filter { it.startsWith("mod.install pkg=") }

    private fun zipBytesOf(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}

/** 供 ModDx4DexCarrierSourceTest 当「dex 里的入口类」用的假 mod（构造计数校验新实例语义）。 */
class DexSourceFakeMod : EraMod {
    override fun onLoad(context: com.eratw.port.modapi.ModContext) = Unit
    override fun onEnable() = Unit
    override fun onDisable() = Unit
    override fun onUnload() = Unit

    companion object {
        val instantiations: AtomicInteger = AtomicInteger(0)
    }

    init {
        instantiations.incrementAndGet()
    }
}

/** 抽象入口类：可指派 EraMod 但 `newInstance` 必抛（实例化失败收口的载体）。 */
abstract class AbstractDexSourceMod : EraMod
