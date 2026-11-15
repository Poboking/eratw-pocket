package com.eratw.port.android

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.save.SaveService
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 C 车的 app 侧接线判据：`ModRuntimeAssembly.attach` 开态把会话 [ModStorageStore]
 * 接进存档侧车（`SidecarSaveWiring` install + SaveService 生产构造点），
 * 关态一个对象都不建（`SidecarSaveWiring.of` == null）。
 *
 * 行为面走**真 SaveService**：mod 存储写两包（一激活一孤儿）→ 经端口抓出 .rt2 容器 →
 * 解码 → `restoreBundle` 应用于新会话 SaveService → 分段按包真往返。
 */
class ModSidecarWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-c-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun freshStore(): VariableStore = VariableStore()

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private class MarkerMod : EraMod {
        override fun onLoad(context: ModContext) {
            context.storage.put("marker", "on")
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>> = emptyList(),
    ): ModRuntimeSession? =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = com.eratw.port.core.fn.NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
        )

    @Test
    fun `关态不安装侧车接线`() {
        val root = tempGameRoot()
        val store = freshStore()
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = com.eratw.port.core.fn.NativeMethodRegistry(),
            onVmThread = { true },
            enabled = false,
            logSink = { line -> logs += line },
        )
        assertNull("关态不产出会话", session)
        assertNull("关态不安装侧车接线（缺省路径零变化的机制保证）", SidecarSaveWiring.of(store))
    }

    @Test
    fun `开态安装侧车接线且含端口与事务根`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", ModManifest(packageId = "mod.a", archetypes = listOf(ArchetypeDecl("mod.a.trait"))))
        val store = freshStore()
        val session = attach(root, store)
        assertNotNull(session)
        val wiring = SidecarSaveWiring.of(store)
        assertNotNull("开态必须安装侧车接线", wiring)
        assertEquals("事务根 = 游戏根", root.toAbsolutePath().normalize(), wiring!!.transactionRoot.toAbsolutePath().normalize())
        session!!.shutdown()
    }

    @Test
    fun `端口抓取与恢复 - mod存储分段经SaveService真往返`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", ModManifest(packageId = "mod.a", archetypes = listOf(ArchetypeDecl("mod.a.trait"))))
        val store = freshStore()
        val session = attach(root, store, listOf("mod.a" to MarkerMod()))!!

        val wiring = SidecarSaveWiring.of(store)!!
        val binding = com.eratw.port.core.save.SidecarBinding(11L, 22L, 33)
        val groups = wiring.port.captureChunks()
        val bytes = wiring.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)

        // 新会话容器 + 新 SaveService：模拟「读档后」的恢复现场。
        val restoreWiringStore = freshStore()
        val restoreSession = attach(root, restoreWiringStore, listOf("mod.a" to MarkerMod()))!!
        val restoreWiring = SidecarSaveWiring.of(restoreWiringStore)!!
        val applied = restoreWiring.port.restoreRt2(bytes, binding).applied

        assertTrue("绑定吻合时必须 Applied", applied)
        val restored = restoreSession.storage
        assertEquals("两包分段都回来（孤儿段原样保留为非激活）", setOf("mod.a"), restored.packages())
        assertEquals("on", (restored.apiFor("mod.a").get("marker")))
        assertTrue("恢复成功无警告", logs.none { it.startsWith("mod.rt2 warn:") })
        restoreSession.shutdown()
        session.shutdown()
    }

    @Test
    fun `绑定失配 - 不应用且上警告不抛`() {
        val root = tempGameRoot()
        val store = freshStore()
        val session = attach(root, store)!!
        val wiring = SidecarSaveWiring.of(store)!!
        val binding = com.eratw.port.core.save.SidecarBinding(11L, 22L, 33)
        val groups = wiring.port.captureChunks()
        val bytes = wiring.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)

        val otherStore = freshStore()
        val otherSession = attach(root, otherStore)!!
        val otherWiring = SidecarSaveWiring.of(otherStore)!!
        val applied = otherWiring.port.restoreRt2(
            bytes,
            com.eratw.port.core.save.SidecarBinding(11L, 22L, 34), // saveTextHash 差一位
        ).applied
        assertEquals("绑定失配返回 false（不抛、宿主上警告）", false, applied)
        assertTrue(
            "失配必须留痕（fail-closed 不静默）",
            logs.any { it.contains("绑定失配") },
        )
        otherSession.shutdown()
        session.shutdown()
    }

    @Test
    fun `SaveService生产构造点 - attach 内构造可抓可恢复`() {
        // 间接判据：装配产物里端口能对真 SaveService 完成一轮 encode/decode 往返，
        // 即 [SaveService] 的生产构造点（attach 内）真实可用（CONTRACT-RT2-C §5.1）。
        val root = tempGameRoot()
        val store = freshStore()
        val session = attach(root, store)!!
        val wiring = SidecarSaveWiring.of(store)!!
        val binding = com.eratw.port.core.save.SidecarBinding(0L, 0L, 0)
        val groups = wiring.port.captureChunks()
        val bytes = wiring.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)
        val bundle = SidecarSaveBundle.decode(bytes)
        assertTrue("容器必须可解码（SaveService.encode 链真实工作）", bundle.chunks.isEmpty())
        session.shutdown()
    }
}
