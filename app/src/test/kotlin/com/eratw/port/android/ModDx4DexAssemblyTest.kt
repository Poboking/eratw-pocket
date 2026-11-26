package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ASM_PACKAGE_ID: String = "eratw.dexasm"
private const val ASM_MANIFEST_FILE: String = "eratw.dexasm.manifest"

/**
 * ADR-013 S5：dex 载体接进装配链（[ModRuntimeAssembly.attach] dex 装配段）的定向用例。
 *
 * 三族判据：
 * 1. **缝缺省 null = 未接线零接触**（反向证明 1 的装配面）：盘上夹具齐备，attach 不传缝 ⇒
 *    dex 包一个对象都不建、一行日志都不出；
 * 2. **缝注入 = 与内置 Kotlin 载体并装**：dex provider 进同一批 `host.install`（manifest
 *    快照在册 ⇒ active=true；不在册 ⇒ 逐 mod fail-closed，与 ModCarrierRegistry 未登记同症状）；
 * 3. **总闸关态全链 fail-closed（反向证明 1）**：生产形状的缝（[ModDexCarrierSource] 关闸）
 *    注入后，盘面夹具齐备也不扫 code 目录、不建类加载器、install 报告无 dex 包。
 *
 * 真机边界：`DexClassLoader` 真构造与真装载 JVM 测不了（假工厂注入），真机验证项
 * 见 AndroidDexLoaderFactory / ModDexCarrierSource KDoc 的 📱 挂账。
 */
class ModDx4DexAssemblyTest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 缝缺省：未接线零接触
    // ------------------------------------------------------------------

    @Test
    fun `缝缺省null时盘面夹具在场也不进dex链`() {
        val root = tempGameRoot()
        writeDexFixture(root, trusted = true, withManifest = true)
        val session = attach(root = root, store = freshStore())
        assertNotNull("无 dex 缝不影响既有装配（会话照常返回）", session)
        assertFalse(
            "未接线不得出现 dex 装载日志",
            logs.any { it.contains(ASM_PACKAGE_ID) || it.contains("mod.dex") },
        )
    }

    // ------------------------------------------------------------------
    // 缝注入：与内置 Kotlin 载体并装
    // ------------------------------------------------------------------

    @Test
    fun `缝注入的载体并入install且manifest在册时装载成功`() {
        val root = tempGameRoot()
        writeDexFixture(root, trusted = true, withManifest = true)
        val session = attach(
            root = root,
            store = freshStore(),
            dexCarriers = DexCarrierProvision { _, _ -> listOf(ASM_PACKAGE_ID to DexAsmMod()) },
        )
        assertNotNull(session)
        assertTrue(
            "dex 载体必须走同一批 install 并装载成功：${logs.filter { it.contains(ASM_PACKAGE_ID) }}",
            logs.contains("mod.install pkg=$ASM_PACKAGE_ID active=true"),
        )
    }

    @Test
    fun `manifest不在册时dex载体逐mod_fail-closed不整链炸`() {
        val root = tempGameRoot()
        writeDexFixture(root, trusted = true, withManifest = false)
        val session = attach(
            root = root,
            store = freshStore(),
            dexCarriers = DexCarrierProvision { _, _ -> listOf(ASM_PACKAGE_ID to DexAsmMod()) },
        )
        assertNotNull(session)
        val row = logs.singleOrNull { it.contains("mod.install pkg=$ASM_PACKAGE_ID") }
        assertNotNull("未登记包必须有一条 install 报告行（与内置载体未登记同症状）", row)
        assertTrue("未登记包必须 fail-closed：$row", row!!.contains("active=false"))
        assertTrue("会话必须照常返回（不整链炸）", session != null)
    }

    // ------------------------------------------------------------------
    // 反向证明 1：总闸关态全链 fail-closed
    // ------------------------------------------------------------------

    @Test
    fun `总闸关态生产形状的缝在夹具齐备时零构造零日志`() {
        val root = tempGameRoot()
        writeDexFixture(root, trusted = true, withManifest = true)
        val factory = CountingFactory()
        // 生产形状的缝：DexCarrierProvision 里包 ModDexCarrierSource，总闸快照 false（D5 关态）。
        val gateOffProvision = DexCarrierProvision { gameRoot, logSink ->
            ModDexCarrierSource(
                gameRoot = gameRoot,
                factory = factory,
                dexLoaderEnabled = false,
                logSink = logSink,
            ).carriers()
        }
        val session = attach(root = root, store = freshStore(), dexCarriers = gateOffProvision)
        assertNotNull("关闸不影响既有装配（会话照常返回）", session)
        assertEquals("关闸时类加载器构造次数必须为 0", 0, factory.createdFor.size)
        assertFalse(
            "关闸不得产生 dex 装载日志（不扫 code 目录）：${logs.filter { it.contains("mod.dex") }}",
            logs.any { it.contains("mod.dex") || it.contains("mod.install pkg=$ASM_PACKAGE_ID") },
        )
    }

    // ------------------------------------------------------------------
    // fixture（照 ModRuntimeAssemblyWireTest 的行为面夹具口径）
    // ------------------------------------------------------------------

    /** 只记录构造对象的假工厂（真 DexClassLoader 行为归真机 📱）。 */
    private class CountingFactory : DexLoaderFactory {
        val createdFor = mutableListOf<String>()

        override fun create(packageId: String, artifactFile: File): ClassLoader {
            createdFor += packageId
            error("JVM 单测不得构造真类加载器（真机边界）")
        }
    }

    /** onLoad 记下 context 的最小 mod（缝注入用例的载体实例）。 */
    private class DexAsmMod : EraMod {
        var loaded: Boolean = false
            private set

        override fun onLoad(context: com.eratw.port.modapi.ModContext) {
            loaded = true
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private fun tempGameRoot(): Path = Files.createTempDirectory("mod-dex-asm")

    private fun freshStore(): VariableStore = VariableStore()

    /** dex 盘面夹具：内容寻址产物（zip 带 manifest.json）+ 信任标记 + 可选 .manifest 侧车。 */
    private fun writeDexFixture(root: Path, trusted: Boolean, withManifest: Boolean) {
        val rt2 = root.resolve("mods").resolve("rt2")
        Files.createDirectories(rt2)
        val manifestJson = "{\"packageId\":\"$ASM_PACKAGE_ID\",\"version\":\"1.0\"," +
            "\"entrypoint\":\"com.example.Ignore\"}"
        val zip = java.io.ByteArrayOutputStream().use { out ->
            java.util.zip.ZipOutputStream(out).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                zos.write("placeholder-classes".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
                zos.putNextEntry(java.util.zip.ZipEntry(DEX_MANIFEST_ENTRY_NAME))
                zos.write(manifestJson.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            out.toByteArray()
        }
        ModCodeStore(rt2.toFile()).install(ASM_PACKAGE_ID, zip)
        if (trusted) {
            assertTrue(ModTrustStore.trust(rt2.toFile(), ASM_PACKAGE_ID))
        }
        if (withManifest) {
            val descriptor = ModDeclarationAdapter.toDescriptor(ModManifest(packageId = ASM_PACKAGE_ID))
            Files.write(rt2.resolve(ASM_MANIFEST_FILE), ManifestCodec.encode(descriptor))
        }
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        dexCarriers: DexCarrierProvision? = null,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = store,
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = true,
        logSink = { line -> logs += line },
        modProviders = emptyList(),
        newHost = { inputs ->
            ModRuntimeHost(
                snapshot = inputs.snapshot,
                entityKernel = inputs.entityKernel,
                nativeMethods = inputs.nativeMethods,
                logSink = inputs.logSink,
                runtime = inputs.runtime,
                shortIdOf = inputs.shortIdOf,
                intVarView = inputs.intVarView,
                strVarView = inputs.strVarView,
                storage = inputs.storage,
            )
        },
        dexCarriers = dexCarriers,
    )
}
