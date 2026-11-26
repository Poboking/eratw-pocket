package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRandomApi
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.GlobalSidecarSaveWiring
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RandomApi
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W1-DSTATE / W2-SEED 的 **global 对齐面** e2e：global 存档的决策种子段与槽档同口径
 * （task-107）。
 *
 * 硬闸口径（全部走生产缝，不自造引擎/存档实现）：
 * 1. store 取自 `GameSession.store`（生产 `GameHost` 也是拿 `fresh.store` 去 attach）；
 * 2. 装配走 `ModRuntimeAssembly.attach`（生产构造点，缺省 `newHost` / 缺省决策管线）；
 * 3. 存档走 `FileSystemOps.saveGlobal/loadGlobal` + `SaveConfig.ERATW` 真落盘
 *    （`sav/global.sav` + 配对 `sav/global.sav.rt2`），不手搓 bundle（旧档兼容用例例外，
 *    它要做的正是「按增补前字节形状重写侧车」）；
 * 4. 决策种子从生产管线读：`session.decisionState!!.pipeline.snapshotState().decisionSeed`；
 * 5. mod 随机面从生产宿主读：`session.host.contextOf(packageId)!!.rng`。
 *
 * 被测链路：`FileSystemOps.saveGlobal` → `saveGlobalPair`
 * （FileSystemOps.kt:847）→ `port.captureGlobalRt2Bytes`
 * （ModRuntimeAssembly.kt 内，与槽档 `captureRt2Bytes` 共用 `stateSegmentsForSave`）
 * → `LOADGLOBAL` 末段 `restoreGlobalSidecarIfWired`（FileSystemOps.kt:966-984）
 * → `port.restoreGlobalRt2`（采纳序：`DecisionStateCodec.decode` →
 * `ModDecisionJobState.adoptSeedFromSave` → `ModRuntimeHost.adoptRandomSeed`
 * → 每 mod `ModContextImpl.adoptRandomSeed`，即换种子后 `ModRandomApi` 从头重放）。
 *
 * 向后兼容（增补前旧档）：增补前的 global.rt2 没有状态尾段，`SidecarSaveBundle.decode`
 * 回落 `SidecarStateSegments.EMPTY`；`restoreGlobalRt2` 对空决策段不采纳、不抛、不拒载，
 * 种子保持装配期值继续运行 —— 第 03 / 07 例把这条口径钉成真字节断言。
 *
 * 线格式约束（本车硬闸）：加段只发生在 global 档，槽档 `saveNN.sav.rt2` 的段顺序与字节
 * 不受影响 —— `captureRt2Bytes` 只是把原表达式抽进 `stateSegmentsForSave`，第 05 例是槽档
 * 回归，第 04 例断言两侧段载荷**逐字节同源**、global 的实体 / 虚拟 NO 段仍恒空。
 */
class ModGlobalSeedRoundTripE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 夹具：最小真会话 / manifest / 真存档点
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

    /** store 只从真会话拿（硬闸）：与 `GameHost` 的 `fresh.store` 同一构造口径。 */
    private fun newSession(root: Path): GameSession = GameSession(
        gameRoot = root,
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
    )

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-global-seed-e2e")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, packageId: String) {
        val manifest = ModManifest(
            packageId = packageId,
            archetypes = listOf(ArchetypeDecl("$packageId.trait")),
        )
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        val fileName = packageId.replace('.', '_') + ".manifest"
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** 生产装配入口（缺省 newHost = 宿主编排；决策熵尾巴按需注入确定字节）。 */
    private fun attach(
        session: GameSession,
        root: Path,
        providers: List<Pair<String, EraMod>>,
        entropyTail: ByteArray,
    ): ModRuntimeSession {
        for ((packageId, _) in providers) writeManifest(root, packageId)
        val attached = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = session.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
            decisionEntropyTail = { entropyTail },
        )
        assertNotNull("开态装配必须返回会话（enabled=true）", attached)
        return attached!!
    }

    /** 熵尾巴：同 offset ⇒ 同字节 ⇒ 同决策种子；异 offset ⇒ 异种子。 */
    private fun tail(offset: Int): ByteArray = ByteArray(TAIL_BYTES) { (it + offset).toByte() }

    /** eratw 实机二进制存档口径的生产消费点（全局与槽档同一 `FileSystemOps`）。 */
    private fun opsOf(store: VariableStore, root: Path): FileSystemOps =
        FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

    private fun globalSavOf(root: Path): Path = root.resolve(SaveConfig.ERATW.globalPath())

    private fun globalRt2Of(root: Path): Path = Path.of(globalSavOf(root).toString() + ".rt2")

    private fun seedOf(session: ModRuntimeSession): Long {
        val state = session.decisionState
        assertNotNull("开态装配必须带决策管线（种子随档的载体）", state)
        return state!!.pipeline.snapshotState().decisionSeed
    }

    private fun rngOf(session: ModRuntimeSession, packageId: String): RandomApi {
        val context = session.host.contextOf(packageId)
        assertNotNull("宿主必须持有 $packageId 的 ModContext", context)
        return context!!.rng
    }

    private fun draws(rng: RandomApi, count: Int, bound: Int = DRAW_BOUND): List<Int> =
        List(count) { rng.nextInt(bound) }

    private fun adoptLogs(): List<String> = logs.filter { "决策种子随档采纳" in it }

    /** 被测 mod 载体：`onLoad` 立刻可抽 `context.rng`（本车默认 0 抽，抽数由用例显式驱动）。 */
    private class DrawingMod(private val onLoadDraws: Int = 0) : EraMod {
        override fun onLoad(context: ModContext) {
            repeat(onLoadDraws) { context.rng.nextInt(DRAW_BOUND) }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /** 真存一次全局档：生产 `SAVEGLOBAL` 路，断言配对两件真落盘。 */
    private fun saveGlobalExactlyOnce(session: GameSession, root: Path) {
        opsOf(session.store, root).saveGlobal()
        assertTrue("全局档必须真落盘：${globalSavOf(root)}", Files.isRegularFile(globalSavOf(root)))
        assertTrue(
            "配对 global.rt2 必须真落盘：${globalRt2Of(root)}",
            Files.isRegularFile(globalRt2Of(root)),
        )
        assertTrue("global.rt2 必须非空（内嵌决策种子段）", Files.size(globalRt2Of(root)) > 0L)
    }

    // ------------------------------------------------------------------
    // 01-02：global 真往返回放 / 重复读档 no-op 续接
    // ------------------------------------------------------------------

    @Test
    fun `01 global 往返后 mod 随机序列按档内决策种子从头重放`() {
        val root = tempGameRoot()

        // 局 A：真装配 → 抽数 → 真 SAVEGLOBAL（写 sav/global.sav + .rt2）→ 关会话。
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod()), tail(1))
        val seedA = seedOf(attachedA)
        val drawnA = draws(rngOf(attachedA, "mod.a"), REPLAY_DRAWS)
        saveGlobalExactlyOnce(sessionA, root)
        assertTrue(
            "global.rt2 决策段必须带种子（增补前的 global 容器没有这一段）",
            SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root))).decisionStatePayload.isNotEmpty(),
        )
        attachedA.shutdown()

        // 局 B：换熵装配（种子与 A 必不同）→ 真 LOADGLOBAL → 期望种子 / 序列回到 A。
        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod()), tail(2))
        val seedB = seedOf(attachedB)
        assertNotEquals("前置：B 的局内种子必须与档内种子不同（否则本用例无鉴别力）", seedA, seedB)
        assertTrue("LOADGLOBAL 必须成功（真生产路）", opsOf(sessionB.store, root).loadGlobal())
        assertEquals("读全局档后决策管线必须采纳档内种子", seedA, seedOf(attachedB))
        assertTrue(
            "采纳序必须上日志（复用 adoptSeedFromSave 的生产接线）：期望含「决策种子随档采纳 seed=$seedA」，" +
                "实际=${adoptLogs()}",
            adoptLogs().any { it.contains("seed=$seedA") },
        )
        assertEquals(
            "读全局档后 mod 随机序列必须按档内种子从头重放（不续接局内 B 的流位置）",
            drawnA,
            draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS),
        )
        attachedB.shutdown()
    }

    @Test
    fun `02 同全局档重复 LOADGLOBAL 走同种子 no-op 快速通道（流续接不重播种）`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod()), tail(3))
        val seedA = seedOf(attachedA)
        val drawnA = draws(rngOf(attachedA, "mod.a"), REPLAY_DRAWS)
        saveGlobalExactlyOnce(sessionA, root)
        attachedA.shutdown()

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod()), tail(4))
        assertNotEquals("前置：B 局内种子必须与档内不同（保证首读档真的采纳）", seedA, seedOf(attachedB))
        val opsB = opsOf(sessionB.store, root)
        assertTrue("首读全局档必须成功", opsB.loadGlobal())
        assertEquals("首读档必须采纳档内种子", seedA, seedOf(attachedB))
        assertEquals("首读档后 mod 随机序列必须从头重放", drawnA, draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS))
        assertEquals("首读档必须恰好打一条采纳日志，实际=${adoptLogs()}", 1, adoptLogs().size)

        // 第二读档：档内种子 == 当前种子 ⇒ adoptSeedFromSave 的「相等 = no-op」短路
        // （防重复播种），流位置不重置而续接。
        assertTrue("重复 LOADGLOBAL 必须成功", opsB.loadGlobal())
        assertEquals("第二读档不得让决策种子漂移", seedA, seedOf(attachedB))
        val continuation = draws(ModRandomApi(seedA, "mod.a"), REPLAY_DRAWS * 2)
            .subList(REPLAY_DRAWS, REPLAY_DRAWS * 2)
        val afterSecondLoad = draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS)
        assertEquals(
            "第二读档后必须续接（no-op 不重播种）：期望档内流的 [12..24)，实际=$afterSecondLoad",
            continuation,
            afterSecondLoad,
        )
        assertEquals("第二读档走 no-op 不得新增采纳日志，实际=${adoptLogs()}", 1, adoptLogs().size)
        attachedB.shutdown()
    }

    // ------------------------------------------------------------------
    // 03 / 07：旧档（增补前字节形状）向后兼容
    // ------------------------------------------------------------------

    @Test
    fun `03 旧格式 global 无种子段读入不拒载且种子保持装配值`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod()), tail(5))
        val seedA = seedOf(attachedA)
        saveGlobalExactlyOnce(sessionA, root)
        attachedA.shutdown()

        // 把侧车按增补前的字节形状重写：五段容器，无状态尾段（决策段 / 任务段都不写）。
        val fresh = SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root)))
        assertTrue("前置：新格式 global.rt2 必须带决策段", fresh.decisionStatePayload.isNotEmpty())
        val legacy = SidecarSaveBundle(
            binding = fresh.binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
        ).encode()
        Files.write(globalRt2Of(root), legacy)
        assertTrue(
            "前置：旧档字节里确实没有决策段",
            SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root))).decisionStatePayload.isEmpty(),
        )

        // 旧档载入新会话：不抛、不拒载；无种子可采纳 ⇒ 种子保持装配期值，随机流不动。
        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod()), tail(6))
        val seedB = seedOf(attachedB)
        assertNotEquals("前置：A/B 种子不同（否则无法断言「未被改写」）", seedA, seedB)
        assertTrue("旧档无段必须照常 LOADGLOBAL（不拒载）", opsOf(sessionB.store, root).loadGlobal())
        assertEquals("旧档无段：种子必须保持装配期值（不被改写）", seedB, seedOf(attachedB))
        assertEquals("旧档无段：不得出现采纳日志，实际=${adoptLogs()}", 0, adoptLogs().size)
        assertEquals(
            "旧档无段：mod 随机流必须与装配期同源（B 种子派生流头 N 个）",
            draws(ModRandomApi(seedB, "mod.a"), REPLAY_DRAWS),
            draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS),
        )
        attachedB.shutdown()
    }

    @Test
    fun `07 旧格式 global 读入后首次 SAVEGLOBAL 起种子入段`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod()), tail(7))
        saveGlobalExactlyOnce(sessionA, root)
        attachedA.shutdown()

        val fresh = SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root)))
        val legacy = SidecarSaveBundle(
            binding = fresh.binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
        ).encode()
        Files.write(globalRt2Of(root), legacy)

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod()), tail(8))
        val seedB = seedOf(attachedB)
        assertTrue("旧档必须能读入", opsOf(sessionB.store, root).loadGlobal())
        saveGlobalExactlyOnce(sessionB, root)
        val rewritten = SidecarSaveBundle.decode(Files.readAllBytes(globalRt2Of(root)))
        assertEquals(
            "旧档读入后首次存档必须起入决策段（会话当前种子 = 装配期种子，无漂移）",
            seedB,
            DecisionStateCodec.decode(rewritten.decisionStatePayload).decisionSeed,
        )
        assertTrue("任务态段也随同一段结构起入", rewritten.jobStatePayload.isNotEmpty())
        attachedB.shutdown()
    }

    // ------------------------------------------------------------------
    // 04：两侧段同源同构（槽档字节形状不变、global 容器形状不变）
    // ------------------------------------------------------------------

    @Test
    fun `04 global 段与槽档段同源同构且 global 容器形状不变`() {
        val root = tempGameRoot()
        val session = newSession(root)
        val attached = attach(session, root, listOf("mod.a" to DrawingMod()), tail(9))
        val store = session.store
        val slotPort = SidecarSaveWiring.of(store)!!.port
        val globalPort = GlobalSidecarSaveWiring.of(store)!!.port
        val binding = SidecarBinding(11L, 22L, 33)

        val slotGroups = slotPort.captureChunks()
        val slotBytes = slotPort.captureRt2Bytes(binding, slotGroups.activeChunks, slotGroups.orphanChunks)
        val globalGroups = globalPort.captureGlobalChunks()
        val globalBytes = globalPort.captureGlobalRt2Bytes(
            binding,
            globalGroups.activeChunks,
            globalGroups.orphanChunks,
        )
        val slot = SidecarSaveBundle.decode(slotBytes)
        val global = SidecarSaveBundle.decode(globalBytes)

        // 槽档增补形状未变：决策段 + 任务段都在（判据 1/3 的槽档面）。
        assertTrue("槽档决策段必须仍非空", slot.decisionStatePayload.isNotEmpty())
        assertTrue("槽档任务段必须仍非空", slot.jobStatePayload.isNotEmpty())
        // global 容器形状未变：实体图段与虚拟 NO 段仍恒空（SaveService.captureGlobalBundle 口径）。
        assertTrue("global 实体图段必须保持空", global.entityGraphPayload.isEmpty())
        assertTrue("global 虚拟 NO 段必须保持空", global.virtualNoSnapshot.isEmpty())
        // 同源同构：同一会话同一时刻两侧段载荷逐字节一致（同一 stateSegmentsForSave 入口）。
        assertTrue("global 决策段必须非空（与槽档同源起入）", global.decisionStatePayload.isNotEmpty())
        assertTrue(
            "global 决策段必须与槽档决策段逐字节同源（同一 SidecarStateSegments 口径）",
            slot.decisionStatePayload.contentEquals(global.decisionStatePayload),
        )
        assertTrue(
            "global 任务段必须与槽档任务段逐字节同源（不为 global 另开一套段格式）",
            slot.jobStatePayload.contentEquals(global.jobStatePayload),
        )
        assertEquals("两侧绑定必须一致", slot.binding, global.binding)
        assertEquals("段内种子必须同一", DecisionStateCodec.decode(slot.decisionStatePayload).decisionSeed, seedOf(attached))
        attached.shutdown()
    }

    // ------------------------------------------------------------------
    // 05：槽档回归（W2-SEED 口径不变，禁止被 global 改动静默漂移）
    // ------------------------------------------------------------------

    @Test
    fun `05 槽档往返回归仍按档内决策种子从头重放`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod()), tail(10))
        val seedA = seedOf(attachedA)
        val drawnA = draws(rngOf(attachedA, "mod.a"), REPLAY_DRAWS)
        val opsA = opsOf(sessionA.store, root)
        assertTrue("真槽档存档必须成功", opsA.saveData(SLOT_NO, SAVE_TEXT))
        val savRelative = SaveConfig.ERATW.normalPath(SLOT_NO)
        val rt2Path = root.resolve("$savRelative.rt2")
        assertTrue("槽档侧车必须真落盘：$rt2Path", Files.isRegularFile(rt2Path))
        val slotSegments = SidecarSaveBundle.decode(Files.readAllBytes(rt2Path))
        assertTrue("槽档侧车决策段必须仍非空（增补前形状由 stateSegmentsForSave 保持）", slotSegments.decisionStatePayload.isNotEmpty())
        assertTrue("槽档侧车任务段必须仍非空", slotSegments.jobStatePayload.isNotEmpty())
        assertEquals(
            "槽档内决策种子必须 = 会话决策种子（槽档口径未漂移）",
            seedA,
            DecisionStateCodec.decode(slotSegments.decisionStatePayload).decisionSeed,
        )
        attachedA.shutdown()

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod()), tail(11))
        assertNotEquals("前置：B 局内种子必须与档内不同", seedA, seedOf(attachedB))
        opsOf(sessionB.store, root).loadData(SLOT_NO)
        assertEquals("槽档读档后必须采纳档内种子", seedA, seedOf(attachedB))
        assertEquals(
            "槽档读档后 mod 随机序列必须从头重放（槽档回归，同 DG 波口径）",
            drawnA,
            draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS),
        )
        attachedB.shutdown()
    }

    // ------------------------------------------------------------------
    // 06：绑定失配（global 档不该在别人的存档上播种）
    // ------------------------------------------------------------------

    @Test
    fun `06 global 绑定失配返回 false 且不采纳种子`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod()), tail(12))
        val seedA = seedOf(attachedA)
        saveGlobalExactlyOnce(sessionA, root)
        val bytes = Files.readAllBytes(globalRt2Of(root))
        val actualBinding = SidecarSaveBundle.decode(bytes).binding
        attachedA.shutdown()

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod()), tail(13))
        val seedB = seedOf(attachedB)
        assertNotEquals("前置：B 局内种子必须与档内不同", seedA, seedB)
        val wrong = actualBinding.copy(saveTextHash = actualBinding.saveTextHash + 1)
        val applied = GlobalSidecarSaveWiring.of(sessionB.store)!!.port.restoreGlobalRt2(bytes, wrong)
        assertFalse("绑定失配必须返回 false", applied)
        assertEquals("绑定失配不得采纳档内种子", seedB, seedOf(attachedB))
        assertEquals("绑定失配不得出现采纳日志，实际=${adoptLogs()}", 0, adoptLogs().size)
        assertTrue("绑定失配必须上日志面（生产 warn 口径）", logs.any { "绑定失配" in it })
        attachedB.shutdown()
    }

    // ------------------------------------------------------------------
    // 08：多 mod 各自派生流（种子按 packageId 派生，不串流）
    // ------------------------------------------------------------------

    @Test
    fun `08 global 往返后多 mod 各自派生流逐位重放`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(
            sessionA,
            root,
            listOf("mod.a" to DrawingMod(), "mod.b" to DrawingMod()),
            tail(14),
        )
        val seedA = seedOf(attachedA)
        val rngA = rngOf(attachedA, "mod.a")
        val rngB = rngOf(attachedA, "mod.b")
        val drawnA = mutableListOf<Int>()
        val drawnB = mutableListOf<Int>()
        repeat(REPLAY_DRAWS) {
            drawnA += rngA.nextInt(DRAW_BOUND)
            drawnB += rngB.nextInt(DRAW_BOUND)
        }
        assertNotEquals("前置：不同 packageId 的派生流必须不同（否则本用例无鉴别力）", drawnA, drawnB)
        saveGlobalExactlyOnce(sessionA, root)
        attachedA.shutdown()

        val sessionB = newSession(root)
        val attachedB = attach(
            sessionB,
            root,
            listOf("mod.a" to DrawingMod(), "mod.b" to DrawingMod()),
            tail(15),
        )
        assertNotEquals("前置：B 局内种子必须与档内不同", seedA, seedOf(attachedB))
        assertTrue("LOADGLOBAL 必须成功", opsOf(sessionB.store, root).loadGlobal())
        assertEquals("mod.a 流必须按档内种子从头重放", drawnA, draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS))
        assertEquals("mod.b 流必须按档内种子从头重放", drawnB, draws(rngOf(attachedB, "mod.b"), REPLAY_DRAWS))
        attachedB.shutdown()
    }

    private companion object {
        const val TAIL_BYTES = 16
        const val DRAW_BOUND = 1000
        const val REPLAY_DRAWS = 12
        const val SLOT_NO = 1
        const val SAVE_TEXT = "RT2-GLOBAL-SEED-E2E"
    }
}
