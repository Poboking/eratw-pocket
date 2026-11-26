package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRandomApi
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RandomApi
import com.eratw.port.modapi.RuntimeScheduler
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RT2 RNG 车次（task-105）：`context.rng` 真装配 + 真槽档往返的 app 生产缝 e2e。
 *
 * 硬闸口径（全部走生产缝，不自造引擎/存档实现）：
 * 1. store 取自 `GameSession.store`（生产宿主 `GameHost` 也是拿 `fresh.store` 去 attach）；
 * 2. 装配走 `ModRuntimeAssembly.attach`（生产构造点，缺省 `newHost`/缺省决策管线）；
 * 3. 存档走 `FileSystemOps.saveData/loadData` + `SaveConfig.ERATW` 真落盘（`sav/save01.sav`
 *    + 配对侧车 `sav/save01.sav.rt2`），不直打 `SidecarSaveWiring` 端口、不手搓 bundle；
 * 4. 决策种子从生产管线读：`session.decisionState!!.pipeline.snapshotState().decisionSeed`；
 * 5. mod 随机面从生产宿主读：`session.host.contextOf(packageId)!!.rng`。
 *
 * 种子随档的端到端链路（证据见交付报告）：
 * `ModRuntimeAssembly.attach` → `SidecarSaveWiring.install`（ModRuntimeAssembly.kt:604-616）
 * → `FileSystemOps.saveData` 配对写 `.sav`+`.sav.rt2`（FileSystemOps.kt:318-328、:426-512）
 * → 槽档 `.rt2` 内含 `SidecarStateSegments(decisionStatePayload, jobStatePayload)`
 * （ModRuntimeAssembly.kt:1755-1777）→ `FileSystemOps.loadData` 末段
 * `restoreSidecarIfWired`（FileSystemOps.kt:520-552）→ `port.restoreRt2`
 * （ModRuntimeAssembly.kt:1779-1833）→ `ModDecisionJobState.adoptSeedFromSave`
 * （ModRuntimeAssembly.kt:1017-1025）→ `ModRuntimeHost.adoptRandomSeed`
 * （ModRuntimeHost.kt:586-588、:681-684）→ 每 mod `ModContextImpl.adoptRandomSeed`
 * （ModContextImpl.kt:383-385，换种子 `ModRandomApi` 从头重放）。
 *
 * 引擎主流隔离（判据 4）：`context.rng` 是每 mod 按「决策种子 + packageId」派生的独立
 * SFMT19937（ModRandomApi.kt:3-28、:89-100），与引擎 `RAND` 那条 `store.randProvider`
 * 不是同一条流（VariableStore.kt:3716；HostMethods.kt:34-37）。
 *
 * 计时口径：全部断言写「期望 vs 实际」。
 */
class ModRandomSaveRoundTripE2ETest {

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

    /** store 只从真会话拿（硬闸）：与 `GameHost` 的 `fresh.store` 同一构造口径。 */
    private fun newSession(root: Path): GameSession = GameSession(
        gameRoot = root,
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
    )

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-random-e2e")
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
            // null 语义 = 生产真熵（SecureRandom）；这里恒注入字节做确定性定向。
            decisionEntropyTail = { entropyTail },
        )
        assertNotNull("开态装配必须返回会话（enabled=true）", attached)
        return attached!!
    }

    /** 熵尾巴：同 offset ⇒ 同字节 ⇒ 同决策种子；异 offset ⇒ 异种子。 */
    private fun tail(offset: Int): ByteArray = ByteArray(TAIL_BYTES) { (it + offset).toByte() }

    /** eratw 实机二进制槽档口径的生产消费点（真落盘 `.sav` + `.sav.rt2`）。 */
    private fun slotOps(store: VariableStore, root: Path): FileSystemOps =
        FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

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

    /**
     * 被测 mod 载体：在 `onLoad` 里立刻从 `context.rng` 抽数。
     *
     * 生产装载序：`ModRuntimeHost` 先 `context.adoptRandomSeed(...)`
     * （ModRuntimeHost.kt:337）再 `mod.onLoad(...)`（:362）——本载体把这条序变成可观测断言。
     */
    private class DrawingMod(private val onLoadDraws: Int = DRAW_COUNT) : EraMod {
        var context: ModContext? = null
        var drawnOnLoad: List<Int> = emptyList()

        override fun onLoad(context: ModContext) {
            this.context = context
            drawnOnLoad = List(onLoadDraws) { context.rng.nextInt(DRAW_BOUND) }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    // ------------------------------------------------------------------
    // 01-04：真装配 / 装配序 / 确定性 / 多 mod 独立派生流
    // ------------------------------------------------------------------

    @Test
    fun `01 装配后 context rng 是生产 ModRandomApi 且序列由档内决策种子决定`() {
        val root = tempGameRoot()
        val session = newSession(root)
        val attached = attach(session, root, listOf("mod.a" to DrawingMod(0)), tail(1))
        val seed = seedOf(attached)
        assertNotEquals("注入熵尾巴必须真的进种子（不落到 0 缺省）", 0L, seed)
        val actual = draws(rngOf(attached, "mod.a"), CONCRETE_DRAWS)
        val expected = draws(ModRandomApi(seed, "mod.a"), CONCRETE_DRAWS)
        assertEquals("context.rng 必须等于 ModRandomApi(档内决策种子, packageId) 的生产派生流", expected, actual)
        attached.shutdown()
    }

    @Test
    fun `02 onLoad 时 context rng 已按决策种子装配（adopt 序在 onLoad 之前）`() {
        val root = tempGameRoot()
        val session = newSession(root)
        val mod = DrawingMod(DRAW_COUNT)
        val attached = attach(session, root, listOf("mod.a" to mod), tail(2))
        assertEquals("onLoad 必须恰好抽 $DRAW_COUNT 次", DRAW_COUNT, mod.drawnOnLoad.size)
        val expected = draws(ModRandomApi(seedOf(attached), "mod.a"), DRAW_COUNT)
        assertEquals("onLoad 阶段拿到的必须是已装配的生产流（不是 fail-closed 桩）", expected, mod.drawnOnLoad)
        attached.shutdown()
    }

    @Test
    fun `03 同安装同注入熵两局种子相同且同 mod 抽数逐位一致`() {
        // 熵串 = 安装级熵基座（gameCode + gameRoot 规范路径，见 DecisionEntropySupport.decisionEntropy）
        // + 注入尾巴；故「同安装（同 root）+ 同尾巴」才同种子，跨 root 会因基座不同而换种子。
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val sessionB = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod(0)), tail(3))
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod(0)), tail(3))
        assertEquals("同安装 + 同熵尾巴 ⇒ 同决策种子（DecisionSeed 派生确定性）", seedOf(attachedA), seedOf(attachedB))
        assertEquals(
            "同种子 + 同 packageId ⇒ 同 mod 流（跨会话逐位一致）",
            draws(rngOf(attachedA, "mod.a"), CONCRETE_DRAWS),
            draws(rngOf(attachedB, "mod.a"), CONCRETE_DRAWS),
        )
        attachedA.shutdown()
        attachedB.shutdown()
    }

    @Test
    fun `04 同决策种子下不同 mod 的派生流互不影响`() {
        val root = tempGameRoot()
        val session = newSession(root)
        val modA = DrawingMod(0)
        val modB = DrawingMod(0)
        val attached = attach(session, root, listOf("mod.a" to modA, "mod.b" to modB), tail(4))
        val seed = seedOf(attached)
        val rngA = rngOf(attached, "mod.a")
        val rngB = rngOf(attached, "mod.b")
        val actualA = mutableListOf<Int>()
        val actualB = mutableListOf<Int>()
        repeat(CONCRETE_DRAWS) {
            actualA += rngA.nextInt(DRAW_BOUND)
            actualB += rngB.nextInt(DRAW_BOUND)
        }
        assertEquals(
            "mod.a 流 = ModRandomApi(seed, mod.a)，与 mod.b 的存在/抽数无关",
            draws(ModRandomApi(seed, "mod.a"), CONCRETE_DRAWS),
            actualA,
        )
        assertEquals(
            "mod.b 流 = ModRandomApi(seed, mod.b)，与 mod.a 的抽数交错无关",
            draws(ModRandomApi(seed, "mod.b"), CONCRETE_DRAWS),
            actualB,
        )
        assertNotEquals("不同 packageId 必须派生不同流（否则 mod 之间会串流）", actualA, actualB)
        attached.shutdown()
    }

    // ------------------------------------------------------------------
    // 05-06：真槽档往返（真磁盘 .sav + .sav.rt2）读档重放
    // ------------------------------------------------------------------

    @Test
    fun `05 槽档往返后 mod 随机序列按档内决策种子从头重放`() {
        val root = tempGameRoot()
        // 局 A：真装配 → 抽数 → 真存档（写 sav/save01.sav + 配对 .rt2）→ 关会话。
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod(0)), tail(5))
        val seedA = seedOf(attachedA)
        val drawnA = draws(rngOf(attachedA, "mod.a"), REPLAY_DRAWS)
        val opsA = slotOps(sessionA.store, root)
        assertTrue("真存档必须成功（FileSystemOps.saveData 生产路）", opsA.saveData(SLOT_NO, SAVE_TEXT))
        val savRelative = SaveConfig.ERATW.normalPath(SLOT_NO)
        val savPath = root.resolve(savRelative)
        val rt2Path = root.resolve("$savRelative.rt2")
        assertTrue("槽档必须真落盘：$savPath", Files.isRegularFile(savPath))
        assertTrue("配对侧车必须真落盘：$rt2Path", Files.isRegularFile(rt2Path))
        assertTrue("侧车必须非空（内嵌决策种子段）", Files.size(rt2Path) > 0L)
        attachedA.shutdown()

        // 局 B：换熵装配（种子与 A 必不同）→ 真读档 → 期望种子/序列回到 A。
        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod(0)), tail(6))
        val seedB = seedOf(attachedB)
        assertNotEquals("前置：B 的局内种子必须与档内种子不同（否则本用例无鉴别力）", seedA, seedB)
        slotOps(sessionB.store, root).loadData(SLOT_NO)
        assertEquals("读档后决策管线必须采纳档内种子", seedA, seedOf(attachedB))
        assertTrue(
            "采纳序必须上日志（ModRuntimeAssembly.adoptSeedFromSave 的生产接线）" +
                "：期望含「决策种子随档采纳 seed=$seedA」，实际=${logs.filter { "决策种子随档采纳" in it }}",
            logs.any { it.contains("决策种子随档采纳") && it.contains("seed=$seedA") },
        )
        val replayed = draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS)
        assertEquals("读档后 mod 随机序列必须按档内种子从头重放（不续接局内 B 的流位置）", drawnA, replayed)
        attachedB.shutdown()
    }

    @Test
    fun `06 同档重复读档走同种子 no-op 快速通道（种子不漂移、流续接不重播种）`() {
        val root = tempGameRoot()
        val sessionA = newSession(root)
        val attachedA = attach(sessionA, root, listOf("mod.a" to DrawingMod(0)), tail(7))
        val seedA = seedOf(attachedA)
        val drawnA = draws(rngOf(attachedA, "mod.a"), REPLAY_DRAWS)
        assertTrue("真存档必须成功", slotOps(sessionA.store, root).saveData(SLOT_NO, SAVE_TEXT))
        attachedA.shutdown()

        val sessionB = newSession(root)
        val attachedB = attach(sessionB, root, listOf("mod.a" to DrawingMod(0)), tail(8))
        assertNotEquals("前置：B 局内种子必须与档内不同（保证首读档真的采纳）", seedA, seedOf(attachedB))
        val opsB = slotOps(sessionB.store, root)
        opsB.loadData(SLOT_NO)
        assertEquals("首读档必须采纳档内种子", seedA, seedOf(attachedB))
        assertEquals("首读档后 mod 随机序列必须从头重放", drawnA, draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS))
        assertEquals(
            "首读档必须恰好打一条采纳日志，实际=${logs.filter { "决策种子随档采纳" in it }}",
            1,
            logs.count { "决策种子随档采纳" in it },
        )

        // 第二读档：档内种子 == 当前种子 ⇒ adoptSeedFromSave 走 no-op 快速通道（防重复播种），
        // 流位置不重置而续接。生产语义：重放锚点是「换种子的读档 / 新会话」，不是重复读同档。
        opsB.loadData(SLOT_NO)
        assertEquals("第二读档不得让决策种子漂移", seedA, seedOf(attachedB))
        val continuation = draws(ModRandomApi(seedA, "mod.a"), REPLAY_DRAWS * 2)
            .subList(REPLAY_DRAWS, REPLAY_DRAWS * 2)
        val afterSecondLoad = draws(rngOf(attachedB, "mod.a"), REPLAY_DRAWS)
        assertEquals(
            "第二读档后必须续接（no-op 不重播种）：期望档内流的 [12..24)，实际=$afterSecondLoad",
            continuation,
            afterSecondLoad,
        )
        assertEquals(
            "第二读档走 no-op 不得新增采纳日志（生产防重复播种），实际=${logs.filter { "决策种子随档采纳" in it }}",
            1,
            logs.count { "决策种子随档采纳" in it },
        )
        attachedB.shutdown()
    }

    // ------------------------------------------------------------------
    // 07：缺省种子与 adoptRandomSeed 运行时换种子
    // ------------------------------------------------------------------

    @Test
    fun `07 生产缺省熵尾巴每局独立取样且 adoptRandomSeed 对已装载 mod 生效`() {
        // 生产缺省面：不传 decisionEntropyTail（= freshDecisionEntropyTail，SecureRandom）。
        val rootA = tempGameRoot()
        val rootB = tempGameRoot()
        val sessionA = newSession(rootA)
        val sessionB = newSession(rootB)
        writeManifest(rootA, "mod.a")
        writeManifest(rootB, "mod.a")
        val attachedA = ModRuntimeAssembly.attach(
            gameRoot = rootA.toFile(),
            store = sessionA.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = listOf("mod.a" to DrawingMod(0)),
        )!!
        val attachedB = ModRuntimeAssembly.attach(
            gameRoot = rootB.toFile(),
            store = sessionB.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = listOf("mod.a" to DrawingMod(0)),
        )!!
        val seedA = seedOf(attachedA)
        val seedB = seedOf(attachedB)
        assertNotEquals("生产缺省熵 ⇒ 每局独立取样，种子必须不同", seedA, seedB)

        // adoptRandomSeed：宿主对已装载 mod 换种子即「从头重放」。
        val newSeed = seedA + SEED_STEP
        attachedA.host.adoptRandomSeed(newSeed)
        assertEquals(
            "adoptRandomSeed 后 mod 流必须等于 ModRandomApi(newSeed, packageId) 的头 N 个",
            draws(ModRandomApi(newSeed, "mod.a"), CONCRETE_DRAWS),
            draws(rngOf(attachedA, "mod.a"), CONCRETE_DRAWS),
        )
        attachedA.shutdown()
        attachedB.shutdown()
    }

    // ------------------------------------------------------------------
    // 08-09：引擎 RAND 主流隔离（不消耗 / 未注入 fail-closed）
    // ------------------------------------------------------------------

    @Test
    fun `08 引擎 RAND 主流不被 mod 抽数消耗且引擎抽数不影响 mod 流`() {
        val root = tempGameRoot()
        val session = newSession(root)
        // 引擎主流按生产口径注入（GameSession 已给它装 Approx/Sfmt 一类 provider，这里换成已知种子的参照实现）。
        session.store.randProvider = Sfmt19937(ENGINE_SEED)
        val attached = attach(session, root, listOf("mod.a" to DrawingMod(0)), tail(9))
        val referenceEngine = Sfmt19937(ENGINE_SEED)
        val expectedEngine = List(ENGINE_DRAWS) { referenceEngine.next(ENGINE_BOUND) }
        val referenceMod = ModRandomApi(seedOf(attached), "mod.a")

        val actualEngine = mutableListOf<Long>()
        repeat(ENGINE_BEFORE) { actualEngine += rand(session.store, ENGINE_BOUND) }
        // 判据 4 的 state() 快照面：mod 抽数前后引擎主流 state 必须逐元素相等。
        val engineProvider = session.store.randProvider!!
        val stateBeforeModDraws = engineProvider.state()
        // 中间穿插大量 mod 抽数：若共用同一条流，引擎读数会漂移。
        val actualMod = draws(rngOf(attached, "mod.a"), MOD_STEAL_DRAWS)
        assertArrayEquals(
            "mod 抽 $MOD_STEAL_DRAWS 次不得推进引擎主流 state（state() 逐元素相等）",
            stateBeforeModDraws,
            engineProvider.state(),
        )
        repeat(ENGINE_DRAWS - ENGINE_BEFORE) { actualEngine += rand(session.store, ENGINE_BOUND) }
        assertFalse(
            "对照：真 RAND 语句确实推进引擎主流 state（防「没真跑所以不变」的假绿）",
            stateBeforeModDraws.contentEquals(engineProvider.state()),
        )

        assertEquals("引擎 RAND 序列必须与「只做引擎抽数」的参照逐位一致（mod 抽数不得消耗引擎流）", expectedEngine, actualEngine)
        assertEquals("mod 流必须与参照逐位一致（引擎抽数不得消耗 mod 流）", draws(referenceMod, MOD_STEAL_DRAWS), actualMod)
        attached.shutdown()
    }

    @Test
    fun `09 未注入引擎 RandProvider 时 RAND 显式 fail-closed 且不被 mod rng 顶替`() {
        val root = tempGameRoot()
        val session = newSession(root)
        // GameSession 生产装配会给引擎主流注入 RandProvider；本用例主动清空造「宿主未注入」形态，
        // 验证 VariableStore 生产读路仍显式 fail-closed（不静默兜底、也不借用 mod 派生流）。
        session.store.randProvider = null
        assertTrue("前置：本用例已主动清空引擎主流 randProvider", session.store.randProvider == null)
        val attached = attach(session, root, listOf("mod.a" to DrawingMod(0)), tail(10))
        // mod 随便抽，引擎 RAND 仍必须 fail-closed（两条流不共享）。
        draws(rngOf(attached, "mod.a"), DRAW_COUNT)
        try {
            session.store.readInt("RAND", longArrayOf(MOD_STEAL_DRAWS.toLong()))
            fail("期望：未注入 RandProvider 时引擎 RAND 抛 CodeEE（不得被 mod rng 顶替），实际未抛")
        } catch (expected: CodeEE) {
            val message = expected.message ?: ""
            assertTrue("期望错误文案点名 RandProvider，实际=$message", "RandProvider" in message)
        }
        attached.shutdown()
    }

    // ------------------------------------------------------------------
    // 10：未装配随机面的 ModContext.rng 显式 fail-closed（生产桩直接可构造）
    // ------------------------------------------------------------------

    @Test
    fun `10 未装配随机面的 ModContext rng 显式 fail-closed 不返回静默兜底`() {
        val context = unassembledContext(UNASSEMBLED_PKG)
        try {
            context.rng.nextInt(DRAW_BOUND)
            fail("期望：未装配随机面时 context.rng 抛 UnsupportedOperationException，实际未抛")
        } catch (expected: UnsupportedOperationException) {
            val message = expected.message ?: ""
            assertTrue("期望错误文案点名未装配包与调用，实际=$message", "随机面未装配：$UNASSEMBLED_PKG" in message)
            assertTrue("期望错误文案点名 rng.nextInt 调用，实际=$message", "rng.nextInt($DRAW_BOUND)" in message)
        }
    }

    /** 未装配形态的 ModContext（生产 class，直接构造；不参与任何装配链）。 */
    private fun unassembledContext(packageId: String): ModContextImpl = ModContextImpl(
        packageId = packageId,
        snapshot = snapshotOf(packageId),
        entityKernel = kernel(),
        pkgShortId = 1,
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = ImmediateRuntime(),
    )

    private fun snapshotOf(packageId: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
        return registry.freeze()
    }

    private fun kernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private class ImmediateRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    /** 真引擎 `RAND` 求值入口：`VariableStore` 的 `RAND` 伪变量生产读路（VariableStore.kt:3711-3733）。 */
    private fun rand(store: VariableStore, bound: Long): Long =
        store.readInt("RAND", longArrayOf(bound))

    private companion object {
        private const val TAIL_BYTES = 16
        private const val DRAW_BOUND = 1000
        private const val DRAW_COUNT = 4
        private const val CONCRETE_DRAWS = 8
        private const val REPLAY_DRAWS = 12
        private const val SLOT_NO = 1
        private const val SAVE_TEXT = "RT2-RNG-E2E"
        private const val SEED_STEP = 0x5DEECE66DL
        private const val ENGINE_SEED = 20261118L
        private const val ENGINE_BOUND = 100L
        private const val ENGINE_DRAWS = 20
        private const val ENGINE_BEFORE = 5
        private const val MOD_STEAL_DRAWS = 50
        private const val UNASSEMBLED_PKG = "mod.unassembled"
    }
}
