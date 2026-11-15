package com.eratw.port.android

import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.GlobalSidecarSaveWiring
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 C-RES1 的 app 侧接线判据：`ModRuntimeAssembly.attach` 开态把 global 侧车
 * （`GlobalSidecarSaveWiring` install + 同一 `ModSidecarStoragePort`）装进存档面，
 * 关态一个对象都不建（`GlobalSidecarSaveWiring.of` == null）。
 *
 * 判据（CONTRACT-RT2-C-RES1 §3）：
 * - a/b 经生产路径端到端：SAVEGLOBAL 配对落盘（global.sav + global.sav.rt2 同事务）、
 *   LOADGLOBAL 读回交还，**mod 侧 `context.storage` 读档后可见**（重绑链钉住）；
 * - c 关态零接触（缺省路径零变化的机制保证）。
 */
class ModGlobalSidecarWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-cres1-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun freshStore(): VariableStore = VariableStore()

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** 记住自己 context 的 mod：mod 侧可见性判据的观测点（写值 → 读档 → 断言翻回）。 */
    private class ValueMod(private val marker: String) : EraMod {
        var context: ModContext? = null
        override fun onLoad(context: ModContext) {
            this.context = context
            context.storage.put("marker", marker)
        }

        // 本测试的 mod 无启用/停用/卸载行为；表达式体 = Unit，detekt EmptyFunctionBlock 免。
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private fun manifest(): ModManifest =
        ModManifest(packageId = "mod.a", archetypes = listOf(ArchetypeDecl("mod.a.trait")))

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

    /** eratw 实机二进制档口径的生产消费点（相对路径直拼游戏根，与 attach 的事务根同根）。 */
    private fun binaryOps(store: VariableStore, root: Path): FileSystemOps = FileSystemOps(
        store,
        RootedSaveFileIo(root),
        SaveConfig(systemSaveInBinary = true, zipSaveData = true),
    )

    @Test
    fun `关态不安装全局侧车接线`() {
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
        assertNull("关态不安装全局侧车接线（缺省路径零变化的机制保证）", GlobalSidecarSaveWiring.of(store))
    }

    @Test
    fun `开态安装全局侧车接线且事务根为游戏根`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", manifest())
        val store = freshStore()
        val session = attach(root, store)
        assertNotNull("开态产出会话", session)
        val wiring = GlobalSidecarSaveWiring.of(store)
        assertNotNull("开态必须安装全局侧车接线", wiring)
        assertEquals(
            "事务根 = 游戏根（与槽档接线同根）",
            root.toAbsolutePath().normalize(),
            wiring!!.transactionRoot.toAbsolutePath().normalize(),
        )
        session!!.shutdown()
    }

    @Test
    fun `global 端口真往返 - mod侧contextstorage读档后可见`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", manifest())
        val mod1 = ValueMod("from-first-session")
        val store1 = freshStore()
        val session1 = attach(root, store1, listOf("mod.a" to mod1))!!
        val wiring = GlobalSidecarSaveWiring.of(store1)
        assertNotNull("第一会话已装全局接线", wiring)
        val binding = SidecarBinding(11L, 22L, 33)
        val groups = wiring!!.port.captureGlobalChunks()
        val bytes = wiring.port.captureGlobalRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)
        assertTrue(
            "global 束实体载荷恒空（SAVEGLOBAL 不带实体）",
            SidecarSaveBundle.decode(bytes).entityGraphPayload.isEmpty(),
        )

        // 新会话：mod 写自己的值 → 恢复后必须翻回第一会话抓出的值（重绑链钉住 mod 侧可见）。
        val mod2 = ValueMod("from-second-session")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to mod2))!!
        val ctx2 = mod2.context
        assertNotNull("mod2 的 context 应已注入", ctx2)
        assertEquals("恢复前 mod2 看到的是自己的值", "from-second-session", ctx2!!.storage.get("marker"))
        val restoreWiring = GlobalSidecarSaveWiring.of(store2)
        assertNotNull("第二会话也要装了全局接线", restoreWiring)
        assertTrue(
            "绑定吻合时必须 Applied",
            restoreWiring!!.port.restoreGlobalRt2(bytes, binding),
        )
        assertEquals(
            "LOADGLOBAL 后 mod 侧 context.storage 可见（重绑链）",
            "from-first-session",
            ctx2.storage.get("marker"),
        )
        assertEquals(
            "会话容器也换成了重建实例",
            "from-first-session",
            session2.storage.apiFor("mod.a").get("marker"),
        )
        assertTrue("恢复成功无警告", logs.none { it.startsWith("mod.rt2 warn:") })
        session2.shutdown()
        session1.shutdown()
    }

    @Test
    fun `global 绑定失配 - 不交还且上警告不抛`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", manifest())
        val mod1 = ValueMod("from-first-session")
        val store1 = freshStore()
        val session1 = attach(root, store1, listOf("mod.a" to mod1))!!
        val wiring = GlobalSidecarSaveWiring.of(store1)
        assertNotNull("第一会话已装全局接线", wiring)
        val binding = SidecarBinding(11L, 22L, 33)
        val groups = wiring!!.port.captureGlobalChunks()
        val bytes = wiring.port.captureGlobalRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)

        val mod2 = ValueMod("from-second-session")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to mod2))!!
        val ctx2 = mod2.context
        assertNotNull("mod2 的 context 应已注入", ctx2)
        val restoreWiring = GlobalSidecarSaveWiring.of(store2)
        assertNotNull("第二会话已装全局接线", restoreWiring)
        // saveTextHash 差一位 ⇒ 失配：返回 false（不抛），mod 侧保持自己的值，宿主日志留痕。
        assertEquals(
            "绑定失配返回 false（不抛、宿主上警告）",
            false,
            restoreWiring!!.port.restoreGlobalRt2(bytes, SidecarBinding(11L, 22L, 34)),
        )
        assertEquals("失配不应用，mod 侧保持自己的值", "from-second-session", ctx2!!.storage.get("marker"))
        assertTrue("失配必须留痕（fail-closed 不静默）", logs.any { it.contains("绑定失配") })
        session2.shutdown()
        session1.shutdown()
    }

    @Test
    fun `生产路径端到端 - SAVEGLOBAL配对落盘 LOADGLOBAL交还mod侧`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", manifest())
        val mod1 = ValueMod("gold-from-saveglobal")
        val store1 = freshStore()
        val session1 = attach(root, store1, listOf("mod.a" to mod1))!!
        // 生产消费点：FileSystemOps 现读 aux 槽（晚装也能生效的口径，与真机装配序一致）。
        val fs1 = binaryOps(store1, root)
        fs1.saveGlobal()
        assertTrue("SAVEGLOBAL 写 global.sav", Files.isRegularFile(root.resolve("global.sav")))
        assertTrue("SAVEGLOBAL 配对写 global.sav.rt2", Files.isRegularFile(root.resolve("global.sav.rt2")))
        assertFalse("事务收口后无 journal 残件", Files.exists(root.resolve("global.sav.journal")))

        val mod2 = ValueMod("from-second-session")
        val store2 = freshStore()
        val session2 = attach(root, store2, listOf("mod.a" to mod2))!!
        val fs2 = binaryOps(store2, root)
        assertTrue("LOADGLOBAL 读回交还成功", fs2.loadGlobal())
        assertEquals(
            "生产路径端到端：mod 侧 context.storage 读档后可见",
            "gold-from-saveglobal",
            mod2.context!!.storage.get("marker"),
        )
        assertTrue("全程无警告", logs.none { it.startsWith("mod.rt2 warn:") })
        session2.shutdown()
        session1.shutdown()
    }
}
