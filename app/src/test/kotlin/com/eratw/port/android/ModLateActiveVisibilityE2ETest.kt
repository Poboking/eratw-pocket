package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.JobSchedulerStateCodec
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modlivingworld.CharacterDeclarations
import com.eratw.port.modlivingworld.CharacterMod
import java.io.File
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
 * task-131：本会话「回滚态重装成功」（[ModRuntimeSession.reinstallRolledBack]）的包，必须被
 * 装配期快照之外的三处运行面看见 —— ① 决策/任务态（`ModDecisionJobState` 的遍历面）、
 * ② 仲裁外轮 provider 名单（[ModArbitrationRound] 的 driver 注入面）、③ 存档侧车
 * `knownPackages`（[ModRuntimeAssembly.ModSidecarStoragePort] 的活集闭包）。
 *
 * 全部用例走生产缝：装配 = [ModRuntimeAssembly.attach] 缺省宿主；存档 =
 * `FileSystemOps.saveData/loadData` 真落盘 `.sav` + 配对 `.sav.rt2`（与
 * [ModSaveStateIntegrityE2ETest] 同款往返口径）。
 *
 * 硬判据（组长口径，两条都要能红）：
 * - 「同会话读档」：装配期 `activePackageIds` 快照不含重装包 ⇒ `.sav.rt2` 里的该包分段被
 *   `ModStorageStore.load(bytes, knownPackages)`（ModStorageStore.kt:156-164）判成孤儿，
 *   同会话 `loadData` 后 `context.storage` fail-closed（BoundStorage 未激活闸）⇒ 用例 2 红。
 * - 「重装包动态任务实例进并集」：旧遍历面只走装配期集合 ⇒ 用例 3 红（改前
 *   `jobStatePayload()` 里没有重装包的 `JobInstance`）。
 * - 「仲裁 driver 补注入」：旧装配只对装配期 provider 注入 ⇒ 用例 4 红（改前
 *   `character.decisionDriver == null`）。
 * - 用例 1 是组长硬要求的「真存档 → 新会话读档 → 值仍在」闭环（改前后都应绿，属证据不是红例）。
 *
 * 反证锚点（交付报告给红例名 + 改坏哪一行）：把 `attach` 里
 * `activePackages = { session.activePackages }` 与侧车端口同名字段临时换回
 * `{ activePackageIds }`、并摘掉 `reinstallRolledBack` 成功分支的
 * `arbitrationRound?.let { injectDecisionDriver(it, mod) }`，用例 2/3/4 分别红。
 */
class ModLateActiveVisibilityE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 夹具：最小真会话（生产 GameHost 同款三参形态）/ manifest / 真存档点
    // ------------------------------------------------------------------

    /** index 2 是 `EVT` 事件成员：`FunctionTable.eventCallOrder` 由此派生。 */
    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
        FuncMeta(index = 2, key = EVENT_KEY, isEvent = true, defIndex = 2),
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
        b.declareFunction(EVENT_KEY, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-EVT"), 1, 0, 5)
        b.emit(Opcode._RET, 0, 0, 0, 6)
        return b.build(entry)
    }

    private fun newSession(root: Path): GameSession = GameSession(
        gameRoot = root,
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
    )

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-late-active")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    /** 每包一份 manifest：一个静态任务槽 + 一个动态任务模板（重装探针靠模板实例留证据）。 */
    private fun manifestOf(packageId: String): ModManifest = ModManifest(
        packageId = packageId,
        jobs = listOf(JobDecl(jobId = JOB_ID, everyDays = 1L, firstDay = 0L)),
        jobTemplates = listOf(JobTemplateDecl(templateKey = TEMPLATE_KEY)),
    )

    private fun writeManifest(root: Path, packageId: String) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifestOf(packageId)))
        Files.write(root.resolve("mods").resolve("rt2").resolve("$packageId.manifest"), bytes)
    }

    /** 装配后的会话 ↔ 真 store 对照（`ModRuntimeSession.store` 是 private，只能回查源会话）。 */
    private val gamesByMod: MutableMap<ModRuntimeSession, GameSession> = LinkedHashMap()

    /** 生产装配入口（缺省 newHost = 宿主编排；决策熵尾巴按需注入确定字节）。 */
    private fun attach(
        root: Path,
        providers: List<Pair<String, EraMod>>,
        tailOffset: Int = 0,
        writeManifests: Boolean = true,
    ): ModRuntimeSession {
        val game = newSession(root)
        if (writeManifests) for ((packageId, _) in providers) writeManifest(root, packageId)
        val attached = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = game.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
            decisionEntropyTail = { tail(tailOffset) },
        )
        assertNotNull("开态装配必须返回会话（enabled=true）", attached)
        gamesByMod[attached!!] = game
        return attached
    }

    private fun storeOf(session: ModRuntimeSession): VariableStore = gamesByMod.getValue(session).store

    /** 熵尾巴：异 offset ⇒ 异装配种子（读档用例靠异种子证明种子确由档内采纳）。 */
    private fun tail(offset: Int): ByteArray = ByteArray(TAIL_BYTES) { (it + offset).toByte() }

    /** eratw 实机二进制槽档口径的生产消费点（真落盘 `.sav` + `.sav.rt2`）。 */
    private fun slotOps(store: VariableStore, root: Path): FileSystemOps =
        FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

    private fun saveSlot(store: VariableStore, root: Path) {
        assertTrue("存档必须成功（真落盘 sav/ + .sav.rt2）", slotOps(store, root).saveData(SLOT_NO, SAVE_TEXT))
        val sav = root.resolve(SaveConfig.ERATW.normalPath(SLOT_NO))
        assertTrue("槽档主文件必须在盘上：$sav", Files.isRegularFile(sav))
        assertTrue("配对侧车必须在盘上：$sav.rt2", Files.isRegularFile(root.resolve("$sav.rt2")))
    }

    private fun loadSlot(store: VariableStore, root: Path) {
        slotOps(store, root).loadData(SLOT_NO)
    }

    private fun contextOf(session: ModRuntimeSession, packageId: String): ModContext =
        session.host.contextOf(packageId) ?: error("包 $packageId 必须已装载并激活")

    /** 内置 RT2 manifest 全量写进临时游戏根（CharacterMod 的 onEnable 需要真声明面）。 */
    private fun writeBuiltinManifests(root: Path) {
        for (packageId in ModCarrierRegistry.providers(noMarkersRoot()).map { it.first }) {
            val manifest = File("src/main/assets/mods/rt2/$packageId.manifest")
            assertTrue("内置 RT2 manifest 缺席：${manifest.path}", manifest.isFile)
            Files.write(root.resolve("mods").resolve("rt2").resolve(manifest.name), manifest.readBytes())
        }
    }

    private fun noMarkersRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "eratw-lateactive-" + System.nanoTime())

    /** 首个实例在 `onEnable` 抛 ⇒ 会话内回滚态（可重装的前提）。 */
    private class RollbackMod : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() {
            error("首个实例装载炸点（制造回滚态）")
        }

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /** 重装探针：onLoad 登记动态任务实例（进任务态并集的证据载体）。 */
    private class LateStorageMod : EraMod {
        override fun onLoad(context: ModContext) {
            context.jobs.bindTemplate(TEMPLATE_KEY) { }
            context.jobs.scheduleOnce(TEMPLATE_KEY, JOB_AT_DAY)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    // ------------------------------------------------------------------
    // 判据 ③-a（组长硬要求）：真存档 → 新会话读档 → 值仍在
    // ------------------------------------------------------------------

    @Test
    fun `重装包写入值跨会话存档往返仍在`() {
        val root = tempGameRoot()
        val sessionA = attach(root, listOf(PKG to RollbackMod()))
        assertNotNull("首个实例 onEnable 失败必须登记回滚态", sessionA.host.failureOf(PKG))
        assertFalse("回滚态包不许算激活", sessionA.activePackages.contains(PKG))

        assertTrue("回滚态重装必须成功", sessionA.reinstallRolledBack(PKG, LateStorageMod()))
        assertTrue("重装成功后必须进会话活集", sessionA.activePackages.contains(PKG))
        val ctxA = contextOf(sessionA, PKG)
        ctxA.storage.put(KEY, VALUE)
        val saveSide = sessionA.storage.sectionsSnapshot().single { it.packageId == PKG }
        assertFalse("存档写出的该包分段必须是激活段（存档侧证据：orphan 位由容器 activePackages 决定）", saveSide.orphan)

        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        // 新会话：装配种子与档内不同，storage 只能靠 .rt2 恢复；角色包正常装载（无回滚）。
        val sessionB = attach(root, listOf(PKG to LateStorageMod()), 1)
        loadSlot(storeOf(sessionB), root)
        assertTrue("新会话读档后重装包分段必须激活", sessionB.storage.isActive(PKG))
        assertEquals(
            "重装包本会话写入的存储值必须随档恢复到新会话（用户可见数据不丢）",
            VALUE,
            contextOf(sessionB, PKG).storage.get(KEY),
        )
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 ③-b：同会话读档必须按活集（而非装配期快照）重建激活位
    // ------------------------------------------------------------------

    @Test
    fun `同会话读档时重装包分段必须仍按活集激活`() {
        val root = tempGameRoot()
        val session = attach(root, listOf(PKG to RollbackMod()))
        assertTrue("回滚态重装必须成功", session.reinstallRolledBack(PKG, LateStorageMod()))
        contextOf(session, PKG).storage.put(KEY, VALUE)
        saveSlot(storeOf(session), root)

        // 同会话读档：knownPackages 若仍取装配期 activePackageIds（不含 PKG），该包分段会被判孤儿。
        loadSlot(storeOf(session), root)
        assertTrue(
            "同会话读档重建容器必须把重装包视为激活包（活集而非装配期快照）",
            session.storage.isActive(PKG),
        )
        assertEquals(
            "同会话读档后重装包本会话写入值必须仍可读（不得 fail-closed）",
            VALUE,
            contextOf(session, PKG).storage.get(KEY),
        )
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 ①：决策/任务态遍历面必须看见重装包的动态任务实例
    // ------------------------------------------------------------------

    @Test
    fun `重装包动态任务实例必须进任务态并集且同会话读档后仍在`() {
        val root = tempGameRoot()
        val session = attach(root, listOf(PKG to RollbackMod()))
        assertTrue("回滚态重装必须成功", session.reinstallRolledBack(PKG, LateStorageMod()))

        val before = JobSchedulerStateCodec.decode(session.decisionState!!.jobStatePayload())
        assertTrue(
            "任务态并集必须看见重装包的动态实例（决策/任务态遍历现值活集）",
            before.instances.any { it.packageId == PKG },
        )
        saveSlot(storeOf(session), root)
        loadSlot(storeOf(session), root)

        val after = JobSchedulerStateCodec.decode(session.decisionState!!.jobStatePayload())
        assertTrue(
            "同会话读档回灌后任务态并集仍必须包含重装包实例（restoreJobState 遍历面同源）",
            after.instances.any { it.packageId == PKG },
        )
        val live = (contextOf(session, PKG) as ModContextImpl).jobScheduler.snapshot().instances
        assertTrue("同会话读档后重装包调度器仍持有该实例", live.any { it.packageId == PKG })
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 ②：仲裁外轮名单 —— 回滚态重装成功必须补注入 driver
    // ------------------------------------------------------------------

    @Test
    fun `回滚态重装成功必须补注入仲裁driver`() {
        val root = tempGameRoot()
        writeBuiltinManifests(root)
        val others = ModCarrierRegistry.providers(noMarkersRoot())
            .filterNot { it.first == CharacterDeclarations.PACKAGE_ID }
        val session = attach(
            root,
            others + (CharacterDeclarations.PACKAGE_ID to RollbackMod()),
            writeManifests = false,
        )
        assertNotNull("角色包首个实例必须失败登记回滚态", session.host.failureOf(CharacterDeclarations.PACKAGE_ID))
        assertNotNull("开态装配必须有仲裁轮（否则本判据不可辨）", session.arbitrationRound)

        val character = CharacterMod()
        assertTrue(
            "回滚态角色包必须可重装成功（openReinstallSlot 判据满足）",
            session.reinstallRolledBack(CharacterDeclarations.PACKAGE_ID, character),
        )
        assertNotNull(
            "重装成功必须补注入宿主仲裁 driver（装配期注入之外的晚期路径）",
            character.decisionDriver,
        )
        session.shutdown()
    }

    @Test
    fun `重装仍失败时绝不补注入仲裁driver`() {
        val root = tempGameRoot()
        // 该包 manifest 只声明 JOB_ID/template，没有角色包要的 DAY_TICK 任务槽 ⇒ onEnable 必炸。
        val session = attach(root, listOf(FAIL_PKG to RollbackMod()))
        assertNotNull("首个实例必须失败登记回滚态", session.host.failureOf(FAIL_PKG))

        val character = CharacterMod()
        assertFalse("缺声明的包上重装角色包必须失败", session.reinstallRolledBack(FAIL_PKG, character))
        assertNull("失败分支绝不补注入 driver（只在成功记账之后注入）", character.decisionDriver)
        assertFalse("失败包不得进会话活集", session.activePackages.contains(FAIL_PKG))
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 无重装路径不变性自证：激活集顺序 + 任务态段字节往返逐位一致
    // ------------------------------------------------------------------

    @Test
    fun `无重装路径激活集顺序与任务态段字节往返逐位不变`() {
        val root = tempGameRoot()
        val sessionA = attach(root, listOf(PA to LateStorageMod(), PB to LateStorageMod()))
        val before = sessionA.decisionState!!.jobStatePayload()
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val sessionB = attach(root, listOf(PA to LateStorageMod(), PB to LateStorageMod()), 1)
        assertEquals(
            "无重装时活集迭代序必须与装配序逐位一致（Set 迭代序契约由 LinkedHashMap 保证）",
            listOf(PA, PB),
            sessionB.activePackages.toList(),
        )
        loadSlot(storeOf(sessionB), root)
        assertArrayEquals(
            "无重装路径任务态段字节存档/读档往返逐位一致（遍历面改成活集后行为零变化）",
            before,
            sessionB.decisionState!!.jobStatePayload(),
        )
        val snapshot = JobSchedulerStateCodec.decode(sessionB.decisionState!!.jobStatePayload())
        assertEquals(
            "两包动态实例按确定字典序落段",
            listOf("$PA::$TEMPLATE_KEY#0", "$PB::$TEMPLATE_KEY#0"),
            snapshot.instances.map { it.instanceKey },
        )
        assertEquals("两包循环序与装配序一致（逐包一趟）", listOf(PA, PB), snapshot.instances.map { it.packageId })
        assertTrue("A 分段读档后激活", sessionB.storage.isActive(PA))
        assertTrue("B 分段读档后激活", sessionB.storage.isActive(PB))
        sessionB.shutdown()
    }

    private companion object {
        const val PKG: String = "mod.la.late"
        const val FAIL_PKG: String = "mod.la.charfail"
        const val PA: String = "mod.la.a"
        const val PB: String = "mod.la.b"
        const val KEY: String = "late.key"
        const val VALUE: String = "late-active-value"
        const val SLOT_NO: Int = 1
        const val SAVE_TEXT: String = "SAVE-LATEACTIVE"
        const val TAIL_BYTES: Int = 16
        const val EVENT_KEY: String = "EVT"
        const val JOB_ID: String = "tick"
        const val TEMPLATE_KEY: String = "once"
        const val JOB_AT_DAY: Long = 5L
    }
}
