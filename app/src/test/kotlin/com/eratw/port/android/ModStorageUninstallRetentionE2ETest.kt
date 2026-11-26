package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModStorageSection
import com.eratw.port.core.mod.ModStorageSectionCodec
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.PackageChunk
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
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
 * 显式卸载的存档段回收 e2e（task-134；EE 审计 ee481865a 的设计缺口）。
 *
 * ## 缺口（回收前）
 *
 * 卸载只删盘上产物（`ModManageUninstall.kt:49-62`），会话容器 `ModStorageStore` 里该包的分段
 * 没人摘；存档抓取（`ModRuntimeAssembly.kt` 的 `ModSidecarStoragePort.captureChunks`）把激活
 * 段与孤儿段全表透传，`SaveService.mergeChunks` 只并集不裁剪 ⇒ 卸载后的段永久留在**后续每一次
 * 存档**里（本波之前 01–09 号用例量的就是这个读数，本文件按新语义改写）。
 *
 * ## 回收链（本文件断言的生产缝）
 *
 * 1. **持久台账**（task-134 ①）：`ModManageUninstall.uninstall` 成功后把 packageId 写进
 *    `mods/rt2/released-packages.txt`（`ModStorageReclaim.record`，原子写）。
 * 2. **抓取侧跳过**（②）：`captureChunks` 读台账的「盘上已无产物」集，那些包的段整段不写
 *    ⇒ 段与字节**减在下次存档**。既有存档文件本笔不改写（不重写历史档）；读档侧照旧把历史
 *    档里的段读进容器（用例 02/08 钉住这一点），回收只发生在下一次写档。
 * 3. **会话内回收**（③）：设置页投递 `ModStorageReclaimRequests`，VM 页边界
 *    `ModRuntimeSession.onTurnBoundary` drain 后 `storage.release(packageId)`，只摘点名的包，
 *    不 deactivate 别的包（用例 10）。
 *
 * ## 语义边界（用例 05/06/07/09 钉住）
 *
 * - 停用（`.off`）**不写台账**，段照旧保留（停用保留数据是既有语义）；
 * - 数据版本升级（同包换版本、没卸载）段照旧接回；
 * - 坏包拒载的段照旧按孤儿保留；
 * - 卸载后重装同包：旧值不回来（显式卸载 = 放弃数据），但新写入的段照常落档——台账对
 *   「盘上又有产物」的同名包惰性失效，不许静默吞掉重导后的新数据。
 *
 * ## 硬闸口径（全部走生产缝）
 *
 * 1. 装配走 `ModRuntimeAssembly.attach`（生产构造点），store 取 `GameSession.store`；
 * 2. 存档走 `FileSystemOps.saveData`/`loadData`/`saveGlobal`/`loadGlobal` + `SaveConfig.ERATW`
 *    真落盘（`sav/save00.sav` 与其配对 `sav/save00.sav.rt2`）；
 * 3. 卸载 = 生产函数 `ModManageUninstall.uninstall`，停用 = `ModEnablement.setEnabled`；
 * 4. 段数 / 字节数一律读**真文件**（`Files.readAllBytes` + `SidecarSaveBundle.decode` +
 *    `ModStorageSectionCodec.decode`），不信内存态。
 */
class ModStorageUninstallRetentionE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 夹具：最小真会话（生产 GameHost 同款三参形态）/ manifest / 真存档点
    // ------------------------------------------------------------------

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
    )

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-X"), 1, 0, 2)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        return b.build(entry)
    }

    /** 一次「开一局」的全部真对象：会话 + 它自己的 store（存档变量面从这里走）。 */
    private class Live(val session: ModRuntimeSession, val game: GameSession)

    private fun newSession(root: Path): GameSession = GameSession(
        gameRoot = root,
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
    )

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-uninstall-reclaim")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun rt2DirFile(root: Path) = ModEnablement.rt2Dir(root.toFile())

    private fun manifestFile(root: Path, packageId: String): Path =
        // 生产落盘名 = packageId 原串 + ".manifest"（ModImportController / ModPackageInventory
        // 成员枚举同形）——卸载链按这个名字删，测试夹具必须同形。
        root.resolve("mods").resolve("rt2").resolve(packageId + ".manifest")

    private fun writeManifest(root: Path, packageId: String) {
        val manifest = ModManifest(
            packageId = packageId,
            archetypes = listOf(ArchetypeDecl("$packageId.trait")),
        )
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(manifestFile(root, packageId), bytes)
    }

    /** 生产装配入口（缺省 newHost = 宿主编排；决策熵尾巴注入固定字节做确定性）。 */
    private fun attach(root: Path, providers: List<Pair<String, EraMod>>): Live {
        for ((packageId, _) in providers) writeManifest(root, packageId)
        val game = newSession(root)
        val attached = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = game.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
            decisionEntropyTail = { ByteArray(TAIL_BYTES) { (it + 3).toByte() } },
        )
        assertNotNull("开态装配必须返回会话（enabled=true）", attached)
        return Live(attached!!, game)
    }

    private fun slotOps(game: GameSession, root: Path): FileSystemOps =
        FileSystemOps(game.store, RootedSaveFileIo(root), SaveConfig.ERATW)

    private fun slotRt2(root: Path, no: Int = SLOT_NO): Path =
        root.resolve(SaveConfig.ERATW.normalPath(no) + ".rt2")

    private fun globalRt2(root: Path): Path = root.resolve(SaveConfig.ERATW.globalPath() + ".rt2")

    /** 真槽档存档（生产 `SAVEGAME` 同一消费点），返回配对侧车路径。 */
    private fun saveSlot(live: Live, root: Path): Path {
        assertTrue(
            "真槽档存档必须成功（FileSystemOps.saveData 生产路）",
            slotOps(live.game, root).saveData(SLOT_NO, SAVE_TEXT),
        )
        val rt2 = slotRt2(root)
        assertTrue("配对侧车必须在盘上：$rt2", Files.isRegularFile(rt2))
        return rt2
    }

    private fun loadSlot(live: Live, root: Path) {
        slotOps(live.game, root).loadData(SLOT_NO)
    }

    private fun chunksOf(rt2: Path): List<PackageChunk> =
        SidecarSaveBundle.decode(Files.readAllBytes(rt2)).chunks

    private fun chunkOf(rt2: Path, packageId: String): ByteArray? =
        chunksOf(rt2).firstOrNull { it.packageId == packageId }?.payload

    private fun sectionsOf(rt2: Path, knownPackages: Set<String>): List<ModStorageSection> =
        chunksOf(rt2).flatMap { ModStorageSectionCodec.decode(it.payload, knownPackages) }

    private fun sectionOf(rt2: Path, packageId: String, knownPackages: Set<String>): ModStorageSection? =
        sectionsOf(rt2, knownPackages).firstOrNull { it.packageId == packageId }

    /** 生产卸载：删 `mods/rt2/` 下该包全部盘上成员 + 写回收台账（不碰运行时容器）。 */
    private fun uninstallMod(root: Path, packageId: String) {
        assertTrue(
            "生产卸载路径必须成功：$packageId",
            ModManageUninstall.uninstall(rt2DirFile(root), packageId),
        )
        assertFalse("卸载后 manifest 必须已不在盘上", Files.exists(manifestFile(root, packageId)))
        assertTrue(
            "显式卸载必须落回收台账：$packageId",
            packageId in ModStorageReclaim.releasedPackageIds(rt2DirFile(root)),
        )
    }

    /** 生产停用：写 `<pkg>.off` 标记（装配期把该包从清单摘掉）。 */
    private fun disableMod(root: Path, packageId: String) {
        assertTrue(
            "生产停用标记必须写上",
            ModEnablement.setEnabled(rt2DirFile(root), packageId, false),
        )
        assertTrue("停用标记必须在盘上", ModEnablement.isDisabled(rt2DirFile(root), packageId))
    }

    private fun storageText(section: ModStorageSection, key: String): String? =
        (section.entries[key] as? ModValue.StrValue)?.value

    // ------------------------------------------------------------------
    // 被测载体
    // ------------------------------------------------------------------

    /** 在 `onLoad` 里往自己的 storage 段写初始键值的普通 mod。 */
    private class SeedingMod(
        private val entries: Map<String, String> = emptyMap(),
        private val declaredVersion: Int = 0,
    ) : EraMod {
        var loaded: Boolean = false
        var migrated: Pair<Int, Int>? = null

        override fun onLoad(context: ModContext) {
            loaded = true
            for ((key, value) in entries) context.storage.put(key, value)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit

        override val dataVersion: Int get() = declaredVersion

        override fun onMigrate(fromDataVersion: Int, toDataVersion: Int) {
            migrated = fromDataVersion to toDataVersion
        }
    }

    /** 坏包：`onLoad` 直接抛，装配侧按失败项入报告（`ModRuntimeHost` 回滚态口径）。 */
    private class ThrowingMod : EraMod {
        override fun onLoad(context: ModContext) {
            error("audit: 坏包 onLoad 抛异常")
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun contextOf(live: Live, packageId: String): ModContext =
        live.session.host.contextOf(packageId) ?: error("包 $packageId 必须已装载并持有 context")

    // ------------------------------------------------------------------
    // 判据 1：同会话（未重启）卸载后存档 —— 段减在下次存档
    // ------------------------------------------------------------------

    @Test
    fun `01 同会话卸载后存档：该包段减在下次存档`() {
        val root = tempGameRoot()
        val live = attach(root, listOf(PA to SeedingMod(mapOf("gold" to "100", "note" to "keep"))))
        assertTrue("装配后包必须激活", live.session.storage.isActive(PA))
        assertEquals("onLoad 写入必须落段", 2, live.session.storage.sizeOf(PA))

        // 生产卸载只删盘上成员 + 写台账（ModManageUninstall），容器要到页边界才摘（用例 10）。
        uninstallMod(root, PA)
        assertEquals(
            "同一拍里容器还持有段（摘容器是页边界那一步，不是卸载那一步）",
            2,
            live.session.storage.sizeOf(PA),
        )

        val rt2 = saveSlot(live, root)
        assertNull(
            "卸载后新存档不得再带该包段（减在下次存档）：${chunksOf(rt2).map { it.packageId }}",
            chunkOf(rt2, PA),
        )
        assertTrue("已释放包是唯一产物 ⇒ 新档零段", chunksOf(rt2).isEmpty())
        live.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 2：重启读档 —— 读侧不改历史档，回收落点仍是下一次写档
    // ------------------------------------------------------------------

    @Test
    fun `02 卸载后重启读档：旧档段读回容器但不再回写（减在下次存档）`() {
        val root = tempGameRoot()
        val a = attach(root, listOf(PA to SeedingMod(mapOf("gold" to "42", "heirloom" to "yes"))))
        val rt2 = saveSlot(a, root)
        assertNotNull("存前盘上必须含该段", chunkOf(rt2, PA))
        a.session.shutdown()

        uninstallMod(root, PA)
        val b = attach(root, emptyList())
        loadSlot(b, root)

        assertTrue("读档侧不做回收：历史档里的段照旧读进容器", PA in b.session.storage.packages())
        assertFalse("已卸载包必须不在激活集", b.session.storage.isActive(PA))
        assertTrue("必须列在 inactivePackages", PA in b.session.storage.inactivePackages())
        assertEquals("段内条目数原样读回", 2, b.session.storage.sizeOf(PA))

        val rt2After = saveSlot(b, root)
        assertNull("回收落点 = 下次存档：旧档段不再回写", chunkOf(rt2After, PA))
        assertEquals("新档零段", 0, chunksOf(rt2After).size)
        b.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 3：5 轮读档存档 —— 首轮即消失且不再回涨
    // ------------------------------------------------------------------

    @Test
    fun `03 连续 5 轮读档存档：已卸载包的段与字节首轮后归零且不回涨（减在下次存档）`() {
        val root = tempGameRoot()
        val a = attach(root, listOf(PA to SeedingMod(mapOf("gold" to "42", "note" to "keep"))))
        val rt2 = saveSlot(a, root)
        assertNotNull(chunkOf(rt2, PA))
        val baselineSize = Files.size(rt2)
        a.session.shutdown()
        uninstallMod(root, PA)

        val counts = mutableListOf<Int>()
        val sizes = mutableListOf<Long>()
        repeat(5) { round ->
            val live = attach(root, emptyList())
            loadSlot(live, root)
            val saved = saveSlot(live, root)
            counts += chunksOf(saved).size
            sizes += Files.size(saved)
            assertNull(
                "第 ${round + 1} 轮新档不得再带已释放包段",
                chunkOf(saved, PA),
            )
            live.session.shutdown()
        }
        assertEquals("每轮存档段数恒 0：$counts", List(5) { 0 }, counts)
        assertTrue("字节不得再回涨到回收前：$sizes（baseline=$baselineSize）", sizes.all { it <= baselineSize })
    }

    // ------------------------------------------------------------------
    // 判据 4：装/卸 10 个不同包 —— 每轮只留当前包段
    // ------------------------------------------------------------------

    @Test
    fun `04 装卸 10 个不同包：每轮存档只保留当前包段（减在下次存档）`() {
        val root = tempGameRoot()
        val names = (1..10).map { "mod.p$it" }
        val counts = mutableListOf<Int>()
        var lastRt2: Path = slotRt2(root)

        for ((i, packageId) in names.withIndex()) {
            val live = attach(root, listOf(packageId to SeedingMod()))
            if (i > 0) loadSlot(live, root)
            // 模拟玩家在游戏里写数据：走 context 的生产 storage 视图。
            contextOf(live, packageId).storage.put("slot", "v$i")
            lastRt2 = saveSlot(live, root)
            counts += chunksOf(lastRt2).size
            live.session.shutdown()
            uninstallMod(root, packageId)
        }

        assertEquals("每轮存档只剩当前激活包 1 段（永不累积）：$counts", List(10) { 1 }, counts)
        assertEquals(
            "末档盘上只该有最后装的包：${chunksOf(lastRt2).map { it.packageId }}",
            setOf(names.last()),
            chunksOf(lastRt2).map { it.packageId }.toSet(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 5：停用（.off）不是卸载 —— 不写台账、段照旧保留
    // ------------------------------------------------------------------

    @Test
    fun `05 停用不写回收台账：段作为孤儿保留且回写逐字节不变`() {
        val root = tempGameRoot()
        val a = attach(root, listOf(PA to SeedingMod(mapOf("gold" to "77"))))
        val rt2 = saveSlot(a, root)
        val before = chunkOf(rt2, PA)
        assertNotNull(before)
        a.session.shutdown()

        disableMod(root, PA)
        assertTrue("停用不删 manifest（生产停用语义）", Files.exists(manifestFile(root, PA)))
        assertFalse(
            "停用 = 保留数据，不是卸载：不许写回收台账",
            PA in ModStorageReclaim.releasedPackageIds(rt2DirFile(root)),
        )

        val b = attach(root, emptyList())
        loadSlot(b, root)
        assertFalse("停用包读档后不得激活", b.session.storage.isActive(PA))
        assertTrue("停用包段必须留在容器里", PA in b.session.storage.packages())
        val saved = saveSlot(b, root)
        assertArrayEquals("停用包孤儿段回写必须逐字节一致", before, chunkOf(saved, PA))
        b.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 6：同包换数据版本（没卸载）—— 段接回激活
    // ------------------------------------------------------------------

    @Test
    fun `06 数据版本升级不是卸载：段接回激活、旧键保留、dataVersion 更新`() {
        val root = tempGameRoot()
        val v1 = SeedingMod(mapOf("gold" to "7", "level" to "3"), declaredVersion = 1)
        val a = attach(root, listOf(PA to v1))
        val rt2 = saveSlot(a, root)
        assertEquals("首装落 declared=1", 1, sectionOf(rt2, PA, setOf(PA))!!.dataVersion)
        a.session.shutdown()

        val v2 = SeedingMod(declaredVersion = 2)
        val b = attach(root, listOf(PA to v2))
        loadSlot(b, root)
        assertEquals("迁移窗口必须触发 onMigrate(1,2)", 1 to 2, v2.migrated)
        assertTrue("段必须接回激活集", b.session.storage.isActive(PA))
        assertEquals("旧键 gold 必须回原值", "7", contextOf(b, PA).storage.get("gold"))
        assertEquals("旧键 level 必须回原值", "3", contextOf(b, PA).storage.get("level"))
        assertFalse(
            "换版本不是卸载：不许写回收台账",
            PA in ModStorageReclaim.releasedPackageIds(rt2DirFile(root)),
        )

        val rt2After = saveSlot(b, root)
        val section = sectionOf(rt2After, PA, setOf(PA))!!
        assertEquals("段尾 dataVersion 必须更新为 declared=2", 2, section.dataVersion)
        assertEquals("旧键数量必须原样", 2, section.entries.size)
        b.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 7：坏包拒载 —— 段按孤儿保留（不是显式卸载，不回收）
    // ------------------------------------------------------------------

    @Test
    fun `07 坏包拒载不写回收台账：段降为孤儿且逐字节保留`() {
        val root = tempGameRoot()
        val a = attach(root, listOf(PA to SeedingMod(mapOf("k" to "v"))))
        val rt2 = saveSlot(a, root)
        val before = chunkOf(rt2, PA)
        assertNotNull(before)
        a.session.shutdown()

        // 同 packageId 换成坏包重开：onLoad 抛 ⇒ 报告 active=false。
        val b = attach(root, listOf(PA to ThrowingMod()))
        assertFalse(
            "坏包不得进本会话激活集（装配报告 active=false 的判据面）",
            PA in b.session.activePackages,
        )
        loadSlot(b, root)
        assertFalse("坏包段读档后不得激活", b.session.storage.isActive(PA))
        assertTrue("坏包段必须保留在容器里", PA in b.session.storage.packages())
        assertFalse(
            "装配失败不是显式卸载：不许写回收台账",
            PA in ModStorageReclaim.releasedPackageIds(rt2DirFile(root)),
        )

        val rt2After = saveSlot(b, root)
        assertArrayEquals("坏包孤儿段回写必须逐字节一致", before, chunkOf(rt2After, PA))
        b.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 8：全局档（global.sav.rt2）同一回收口径
    // ------------------------------------------------------------------

    @Test
    fun `08 全局档同样回收已卸载包段（减在下次存档）`() {
        val root = tempGameRoot()
        val a = attach(root, listOf(PA to SeedingMod(mapOf("gold" to "9"))))
        slotOps(a.game, root).saveGlobal()
        val gRt2 = globalRt2(root)
        assertTrue("全局配对侧车必须在盘上：$gRt2", Files.isRegularFile(gRt2))
        assertNotNull("全局档必须含该段", chunkOf(gRt2, PA))
        a.session.shutdown()

        uninstallMod(root, PA)
        val b = attach(root, emptyList())
        assertTrue("LOADGLOBAL 必须成功（真生产路）", slotOps(b.game, root).loadGlobal())
        assertFalse("已卸载包不得因全局档读回而激活", b.session.storage.isActive(PA))
        assertTrue("读侧保留历史 global 档的段", PA in b.session.storage.packages())

        slotOps(b.game, root).saveGlobal()
        assertNull("回收落点 = 下次 SAVEGLOBAL：不再携带已释放包段", chunkOf(globalRt2(root), PA))
        b.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 9：卸载后数据不再随档；重装同包从空开始，但新段照常落档
    // ------------------------------------------------------------------

    @Test
    fun `09 卸载后重装同包：旧值不回来 新写入照常落档（减在下次存档）`() {
        val root = tempGameRoot()
        val a = attach(root, listOf(PA to SeedingMod(mapOf("gold" to "55"))))
        val rt2 = saveSlot(a, root)
        assertNotNull(chunkOf(rt2, PA))
        a.session.shutdown()

        uninstallMod(root, PA)
        // 中间再存一轮：回收落点（这一轮之后旧档段不再随档）。
        val b = attach(root, emptyList())
        loadSlot(b, root)
        val savedByB = saveSlot(b, root)
        assertNull("中间轮已回收", chunkOf(savedByB, PA))
        b.session.shutdown()

        val c = attach(root, listOf(PA to SeedingMod()))
        loadSlot(c, root)
        assertTrue("重装后段必须接回激活集", c.session.storage.isActive(PA))
        assertNull("显式卸载 = 放弃数据：旧值不许回来", contextOf(c, PA).storage.get("gold"))
        assertEquals("回收后的容器里没有旧条目", 0, c.session.storage.sizeOf(PA))

        // 台账里还有 PA，但盘上产物又在了 ⇒ 惰性失效：新数据必须照常落档。
        contextOf(c, PA).storage.put("fresh", "1")
        val rt2After = saveSlot(c, root)
        assertNotNull("重导后新写入必须照常落档（不许被旧台账静默吞掉）", chunkOf(rt2After, PA))
        val section = sectionOf(rt2After, PA, setOf(PA))!!
        assertEquals("新值必须写进盘面", "1", storageText(section, "fresh"))
        c.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 10：页边界会话内回收 —— 只摘点名的包，不动别的包
    // ------------------------------------------------------------------

    @Test
    fun `10 页边界回收内存容器：只摘点名的包 别的包数据不动（减在下次存档）`() {
        val root = tempGameRoot()
        val packageA = "mod.reclaim.a"
        val packageB = "mod.reclaim.b"
        val live = attach(
            root,
            listOf(
                packageA to SeedingMod(mapOf("k" to "a")),
                packageB to SeedingMod(mapOf("k" to "b")),
            ),
        )
        assertEquals(1, live.session.storage.sizeOf(packageA))
        assertEquals(1, live.session.storage.sizeOf(packageB))

        uninstallMod(root, packageA)
        ModStorageReclaimRequests.clear()
        ModStorageReclaimRequests.request(packageA)
        // 生产消费点 = VM 线程页边界（GameHost.onTurnBoundaryReached → onTurnBoundary）。
        live.session.onTurnBoundary()

        assertFalse(
            "页边界必须把该包段从内存容器摘掉",
            packageA in live.session.storage.packages(),
        )
        assertTrue("别的包不许被 deactivate", live.session.storage.isActive(packageB))
        assertEquals("别的包数据不许动", "b", contextOf(live, packageB).storage.get("k"))

        val rt2 = saveSlot(live, root)
        assertNull("被回收包段不再写档", chunkOf(rt2, packageA))
        assertNotNull("别的包段照常写档", chunkOf(rt2, packageB))
        assertEquals("末档只剩 B", setOf(packageB), chunksOf(rt2).map { it.packageId }.toSet())
        live.session.shutdown()
    }

    // ------------------------------------------------------------------
    // 常量
    // ------------------------------------------------------------------

    private companion object {
        const val PA = "mod.audit"
        const val SLOT_NO = 0
        const val SAVE_TEXT = "audit"
        const val TAIL_BYTES = 16
    }
}
