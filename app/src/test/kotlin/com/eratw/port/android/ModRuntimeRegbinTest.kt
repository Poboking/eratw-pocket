package com.eratw.port.android

import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.platform.storage.DeltaDeletionSource
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameAssetTree
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 regbin 车道（CONTRACT-RT2-REGBIN §1）五条判据的定向用例：
 * 全局 rt2_pkg_registry.bin 与磁盘全部 .sav.rt2 快照的装载并集、孤儿短 ID 锁定、
 * fail-closed 降级、会话收口原子写 + 关态零接触、installer 差量删除负控。
 * 全部直打生产缝 [ModRuntimeAssembly.attach] 与 [ModRuntimeSession.shutdown]（不注入 newHost）。
 * 造档走 [PackageShortIdRegistry.encode] 同格式手工字节与 [SidecarSaveBundle.encode] 容器，
 * 不改任何 core 生产代码。
 */
class ModRuntimeRegbinTest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 装置：临时游戏根 / manifest / 生产缝 attach / 造档
    // ------------------------------------------------------------------

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-regbin-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path) {
        val descriptor = ModDeclarationAdapter.toDescriptor(
            ModManifest(
                packageId = "mod.regbin",
                archetypes = listOf(ArchetypeDecl("mod.regbin.trait")),
            ),
        )
        put(root, "mods/rt2/mod_regbin.manifest", ManifestCodec.encode(descriptor))
    }

    private fun attach(
        root: Path,
        enabled: Boolean = true,
        providers: List<Pair<String, EraMod>> = emptyList(),
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = VariableStore(),
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = enabled,
        logSink = { line -> logs += line },
        modProviders = providers,
    )

    private fun put(root: Path, rel: String, bytes: ByteArray) {
        val target = root.resolve(rel)
        target.parent?.let { Files.createDirectories(it) }
        Files.write(target, bytes)
    }

    /** 一条注册表条目：包名、短 ID、是否锁定（孤儿载荷锁定位）。 */
    private fun core(): Triple<String, Int, Boolean> =
        Triple(PackageShortIdRegistry.CORE_PACKAGE_ID, EntityIdCodec.CORE_PKG_SHORT_ID, false)

    /**
     * [PackageShortIdRegistry.encode] 同格式字节（大端：魔数 + 版本 + nextId + 条目数 + 条目）。
     * 手工写是为精确控制短 ID 与 nextId 水位：allocate 只能自 2 单调分配，造不出跳号档。
     * 每条必含 [core] 条目（decode 侧强制校验 __core__=1 与 nextId 大于已注册最大 ID）。
     */
    private fun registryBytes(entries: List<Triple<String, Int, Boolean>>, nextId: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { out ->
            out.writeInt(PackageShortIdRegistry.MAGIC)
            out.writeShort(PackageShortIdRegistry.FORMAT_VERSION)
            out.writeInt(nextId)
            out.writeInt(entries.size)
            for ((name, id, locked) in entries) {
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                out.writeInt(id)
                out.writeByte(if (locked) 1 else 0)
                out.writeInt(nameBytes.size)
                out.write(nameBytes)
            }
        }
        return buf.toByteArray()
    }

    /** 把注册表字节包进合法 [SidecarSaveBundle] 容器（decode 取 registrySnapshot 段）。 */
    private fun sidecarBytes(registrySnapshot: ByteArray): ByteArray =
        SidecarSaveBundle(
            binding = SidecarBinding(1L, 1L, 0),
            registrySnapshot = registrySnapshot,
            entityGraphPayload = ByteArray(0),
            chunks = emptyList(),
        ).encode()

    private fun binAt(root: Path): File = root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME).toFile()

    /** 无行为 mod：只为让 host.install 在会话内经 shortIdOf 分配一次短 ID。 */
    private class NoopMod : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    // ------------------------------------------------------------------
    // 判据 1：并集装载端到端（全局 bin ∪ sav 配对槽 ∪ gameRoot 根孤儿片）
    // ------------------------------------------------------------------

    @Test
    fun `判据1 并集装载含全局bin配对槽与孤儿片三映射`() {
        val root = tempGameRoot()
        writeManifest(root)
        // ① 全局 bin：X->7，水位 8
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, registryBytes(listOf(core(), Triple("pkg.X", 7, false)), 8))
        // ② sav 目录配对槽：save01.sav + save01.sav.rt2，快照含 X->7 / Y->8，水位 9
        put(root, "sav/save01.sav", ByteArray(0))
        put(
            root,
            "sav/save01.sav.rt2",
            sidecarBytes(registryBytes(listOf(core(), Triple("pkg.X", 7, false), Triple("pkg.Y", 8, false)), 9)),
        )
        // ③ gameRoot 根孤儿片：save02.sav.rt2（本体 save02.sav 缺失），快照含 Z->9 且锁定位，水位 10
        put(root, "save02.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Z", 9, true)), 10)))
        assertFalse("前置：孤儿片本体 .sav 确实缺失", Files.exists(root.resolve("save02.sav")))

        val binBefore = Files.readAllBytes(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME))
        val session = attach(root)
        assertNotNull("开态必须产出会话", session)

        assertEquals("全局 bin 的 X 并入", 7, session!!.shortIdOf("pkg.X"))
        assertEquals("sav 配对 sidecar 的 Y 并入", 8, session.shortIdOf("pkg.Y"))
        assertEquals("gameRoot 根孤儿片的 Z 并入（本体缺失也并入）", 9, session.shortIdOf("pkg.Z"))

        // 装载顺序 = 全局 bin 先、磁盘快照后（era-arch-v61u.md:160）
        val binLog = logs.indexOfFirst { it.contains("src=${ModRuntimeAssembly.REGISTRY_BIN_NAME}") }
        val sidecarLog = logs.indexOfFirst { it.contains("src=save01.sav.rt2") }
        assertTrue("全局 bin 与磁盘快照都成功 merge", binLog >= 0 && sidecarLog >= 0)
        assertTrue("装载顺序：全局 bin 先于磁盘快照", binLog < sidecarLog)

        // 装配期只读：不写盘、不删任何东西（契约裁定 5）
        val afterAttach = Files.readAllBytes(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME))
        assertArrayEquals("装配期不写盘：全局 bin 字节未被改写", binBefore, afterAttach)
        assertTrue("装配期不删：配对 sidecar 仍在", Files.isRegularFile(root.resolve("sav/save01.sav.rt2")))
        assertTrue("装配期不删：孤儿片仍在", Files.isRegularFile(root.resolve("save02.sav.rt2")))
        session.shutdown()
    }

    @Test
    fun `判据1 伴生件形状单源 - 残件与journal不当容器扫`() {
        val root = tempGameRoot()
        writeManifest(root)
        // 真容器：Z->9（证明扫盘本身在工作）
        put(root, "save02.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Z", 9, false)), 10)))
        // 三种残件：内容都是**合法容器**，唯独文件名不是逻辑存档单元 —— 单源后缀表
        // （SidecarSaveInventory：长后缀先命中，`.rt2.part`/`.rt2.bak` 先于容器后缀；
        // `.journal` 是伴生件但本体形状不成立）必须把它们全部挡在扫描外。
        val decoy = sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Q", 5, false)), 6))
        put(root, "save03.sav.rt2.part", decoy)
        put(root, "save04.sav.rt2.bak", decoy)
        put(root, "save05.sav.journal", decoy)

        val session = attach(root)!!
        assertEquals("真容器照并入", 9, session.shortIdOf("pkg.Z"))
        assertNull("`.rt2.part` 残件不得当容器扫", session.shortIdOf("pkg.Q"))
        assertTrue(
            "残件不得出现在 merge 日志里",
            logs.none {
                it.contains("src=save03.sav.rt2.part") ||
                    it.contains("src=save04.sav.rt2.bak") ||
                    it.contains("src=save05.sav.journal")
            },
        )
        assertTrue(
            "残件都原地保留（只读装载）",
            listOf("save03.sav.rt2.part", "save04.sav.rt2.bak", "save05.sav.journal")
                .all { Files.isRegularFile(root.resolve(it)) },
        )
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 2：孤儿短 ID 锁定，会话内新投射不撞号（蓝图 :160）
    // ------------------------------------------------------------------

    @Test
    fun `判据2 孤儿短ID锁定会话内新投射不撞号`() {
        val root = tempGameRoot()
        writeManifest(root)
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, registryBytes(listOf(core(), Triple("pkg.X", 7, false)), 8))
        // 孤儿片：Z->9 锁定位，把会话水位抬到 10
        put(root, "save02.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Z", 9, true)), 10)))

        val session = attach(root, providers = listOf("mod.regbin" to NoopMod()))
        assertNotNull("开态产出会话", session)
        assertEquals("孤儿 Z 的 9 号已并入会话注册表", 9, session!!.shortIdOf("pkg.Z"))
        val newId = session.shortIdOf("mod.regbin")
        assertNotNull("会话内 install 期新投射必须登记到同一注册表", newId)
        assertTrue("会话内新投射不得撞孤儿 9 号：实际 $newId", newId != 9)
        assertTrue("会话内新投射必须越过并集水位 10：实际 $newId", newId!! >= 10)

        session.shutdown()
        val reg = PackageShortIdRegistry.decode(Files.readAllBytes(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME)))
        assertTrue("收口 bin 里孤儿 9 号仍锁定（蓝图 :160 孤儿锁定语义）", reg.isLocked(9))
        assertEquals("收口 bin 里 Z 仍映射 9", 9, reg.shortIdOf("pkg.Z"))
        assertTrue("收口后再次新投射仍不撞 9", reg.allocate("pkg.brand.new") != 9)
    }

    // ------------------------------------------------------------------
    // 判据 3：损坏降级（坏档 / merge 冲突 / 全坏）
    // ------------------------------------------------------------------

    @Test
    fun `判据3 坏magic全局bin上日志面跳源其余照并不拒载`() {
        val root = tempGameRoot()
        writeManifest(root)
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, ByteArray(16) { 0x42 })
        put(root, "sav/save01.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Y", 8, false)), 9)))

        val session = attach(root)
        assertNotNull("坏 bin 不得拒载游戏、不得抛穿装配", session)
        assertEquals("好 sidecar 源照并（不受坏 bin 牵连）", 8, session!!.shortIdOf("pkg.Y"))
        assertTrue(
            "坏源上日志面并被跳过",
            logs.any { it.contains("跳过损坏源") && it.contains(ModRuntimeAssembly.REGISTRY_BIN_NAME) },
        )
        session.shutdown()
    }

    @Test
    fun `判据3 merge冲突快照上日志面跳该源不抛穿装配`() {
        val root = tempGameRoot()
        writeManifest(root)
        // bin：X->7；sav 快照：X->8（同包异 ID = 冲突，一律拒绝后到源）
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, registryBytes(listOf(core(), Triple("pkg.X", 7, false)), 8))
        put(root, "sav/save01.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.X", 8, false)), 9)))

        val session = attach(root)
        assertNotNull("冲突不得拒载游戏、不得抛穿装配", session)
        assertEquals("冲突一律拒绝后到源：先到的 bin 源 X->7 保留", 7, session!!.shortIdOf("pkg.X"))
        assertTrue(
            "冲突源上日志面并被跳过",
            logs.any { it.contains("跳过损坏源") && it.contains("save01.sav.rt2") },
        )
        session.shutdown()
    }

    @Test
    fun `判据3 全部源损坏空注册表会话照常`() {
        val root = tempGameRoot()
        writeManifest(root)
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, ByteArray(16) { 0x42 })
        put(root, "sav/save01.sav.rt2", "not a sidecar".toByteArray(Charsets.UTF_8))

        val session = attach(root)
        assertNotNull("全坏不得拒载、不得抛穿装配", session)
        assertNull("全坏 = 空注册表（除内核外无任何映射）", session!!.shortIdOf("pkg.X"))
        assertTrue("每个坏源都上日志面", logs.count { it.contains("跳过损坏源") } >= 2)

        session.shutdown()
        val reg = PackageShortIdRegistry.decode(Files.readAllBytes(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME)))
        assertEquals("空注册表会话收口 bin 只含内核一条", 1, reg.count)
    }

    // ------------------------------------------------------------------
    // 判据 4：收口原子写（往返等价 / 再启读回 / 空会话也写 / 关态零接触）
    // ------------------------------------------------------------------

    @Test
    fun `判据4 收口原子写decode往返逐字节等价再启读回`() {
        val root = tempGameRoot()
        writeManifest(root)
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, registryBytes(listOf(core(), Triple("pkg.X", 7, false)), 8))
        put(root, "sav/save01.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Y", 8, false)), 9)))
        put(root, "save02.sav.rt2", sidecarBytes(registryBytes(listOf(core(), Triple("pkg.Z", 9, false)), 10)))

        val s1 = attach(root)!!
        s1.shutdown()

        val written = Files.readAllBytes(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME))
        val reg = PackageShortIdRegistry.decode(written)
        assertEquals("收口 bin 含 X", 7, reg.shortIdOf("pkg.X"))
        assertEquals("收口 bin 含 Y", 8, reg.shortIdOf("pkg.Y"))
        assertEquals("收口 bin 含 Z", 9, reg.shortIdOf("pkg.Z"))
        assertArrayEquals("decode 往返 encode 逐字节等价", written, reg.encode())
        val tmpLeft = root.toFile().listFiles()?.filter { it.name.startsWith("rt2reg-") } ?: emptyList()
        assertTrue("原子写不留 tmp 残件", tmpLeft.isEmpty())

        // 再启会话：并集装载读回
        val s2 = attach(root)!!
        assertEquals("再启读回 X", 7, s2.shortIdOf("pkg.X"))
        assertEquals("再启读回 Y", 8, s2.shortIdOf("pkg.Y"))
        assertEquals("再启读回 Z", 9, s2.shortIdOf("pkg.Z"))
        s2.shutdown()
    }

    @Test
    fun `判据4 开态短会话空注册表也原子写`() {
        val root = tempGameRoot()
        writeManifest(root)
        assertFalse("前置：root 下无 bin", Files.exists(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME)))

        val s = attach(root)!!
        s.shutdown()

        val bin = root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME)
        assertTrue("空注册表会话收口也写 bin（幂等）", Files.isRegularFile(bin))
        val reg = PackageShortIdRegistry.decode(Files.readAllBytes(bin))
        assertEquals("空 bin 只含内核一条", 1, reg.count)
        assertTrue("空 bin 收口写留痕", logs.any { it.contains("收口写 bin ok") })
    }

    @Test
    fun `判据4 关态零接触bin不读不写不touch`() {
        val root = tempGameRoot()
        writeManifest(root)
        val binBytes = registryBytes(listOf(core(), Triple("pkg.X", 7, false)), 8)
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, binBytes)
        val bin = binAt(root)
        val rootFile = root.toFile()
        val mtimeBefore = bin.lastModified()
        val before = rootFile.listFiles()!!.map { it.name }.sorted()

        val session = attach(root, enabled = false)
        assertNull("关态返回 null", session)
        val after = Files.readAllBytes(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME))
        assertArrayEquals("关态不得写 bin（字节逐字不变）", binBytes, after)
        assertEquals("关态不得 touch bin（mtime 不变）", mtimeBefore, bin.lastModified())
        val afterNames = rootFile.listFiles()!!.map { it.name }.sorted()
        assertEquals("关态不得在 gameRoot 新增任何文件", before, afterNames)
        assertTrue("关态不得读 bin（日志面无任何 mod.registry 行）", logs.none { it.contains("mod.registry") })
    }

    // ------------------------------------------------------------------
    // 判据 5：installer 负控（裁定 1 取证：差量删除面不波及 gameRoot 根散文件）
    // ------------------------------------------------------------------

    /** 差量档资产树：空资产（无任何落盘条目）+ 一张删除表；纯 JVM fake，不建 zip。 */
    private class RootScatterDeltaTree(
        private val deletions: List<String>,
    ) : GameAssetTree, DeltaDeletionSource {
        override val isDeltaPackage: Boolean = true
        override fun deltaDeletions(): List<String> = deletions
        override fun openFile(path: String): InputStream? = null
        override fun listDir(path: String): List<String> = emptyList()
        override fun fileSize(path: String): Long? = null
    }

    @Test
    fun `判据5 installer负控差量删除表列gameRoot根散文件计受阻不删`() {
        val root = Files.createTempDirectory("mod-regbin-installer")
        val binBytes = registryBytes(listOf(core(), Triple("pkg.X", 7, false)), 8)
        put(root, ModRuntimeAssembly.REGISTRY_BIN_NAME, binBytes)
        put(root, "CSV/stale.csv", "旧语料".toByteArray(Charsets.UTF_8))

        // 删除表同时列一根散文件（越出资产宇宙）与一条范围内合法删除（对照）。
        val tree = RootScatterDeltaTree(listOf(ModRuntimeAssembly.REGISTRY_BIN_NAME, "CSV/stale.csv"))
        val result = GameAssetInstaller(tree, root.toFile()).install()

        assertEquals("范围内那条照删", 1, result.deletedFiles)
        assertEquals("越出资产宇宙的根散文件计受阻", 1, result.blockedDeletions)
        assertFalse("范围内旧语料已删", Files.exists(root.resolve("CSV/stale.csv")))
        val binPath = root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME)
        assertTrue("gameRoot 根散文件 bin 绝不被差量删除波及", Files.isRegularFile(binPath))
        assertArrayEquals("bin 字节逐字未变", binBytes, Files.readAllBytes(binPath))
    }
}
