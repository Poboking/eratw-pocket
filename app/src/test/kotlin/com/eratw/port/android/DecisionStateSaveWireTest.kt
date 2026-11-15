package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.DecisionState
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.core.mod.ActionExecutionOutcome
import com.eratw.port.core.mod.JobSchedulerStateCodec
import com.eratw.port.core.mod.JobState
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ReservationKey
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W1-DSTATE 的 app 侧接线判据（CONTRACT-RT2-DSTATE 判据 1–6 的定向面）：
 * 开态真决策管线注入（判据 2）、决策态/任务态 `.rt2` 尾段往返（判据 1/3/5）、
 * 随机流独立性负控（判据 4）、旧档无段回落不拒载与关态/旧形字节不变（判据 6）、
 * 坏段 fail-closed 与陈旧预留过滤（判据 1/3）。
 *
 * 行为面走**真装配链**：`ModRuntimeAssembly.attach` 默认宿主构造点（决策管线随
 * `AssemblyInputs.decisionPipeline` 进宿主），抓取/恢复走装进 store 的
 * `SidecarSaveWiring` 端口（SAVEGAME/LOADGAME 的容器物化缝，生产同一条路）。
 */
class DecisionStateSaveWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-dstate-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = com.eratw.port.core.patch.ManifestCodec.encode(
            ModDeclarationAdapter.toDescriptor(manifest),
        )
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** 带一个静态任务槽 + 一个动态任务模板声明的 manifest（判据 5 的载体）。 */
    private fun writeJobManifest(root: Path) {
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(
                packageId = "mod.a",
                archetypes = listOf(com.eratw.port.modapi.ArchetypeDecl("mod.a.trait")),
                jobs = listOf(JobDecl(jobId = "daily", everyDays = 1L, firstDay = 0L)),
                jobTemplates = listOf(JobTemplateDecl(templateKey = "evt")),
            ),
        )
    }

    /**
     * 决策 + 任务双面 mod：onLoad 注册一个 EVENT 候选（带冷却/限额/预留声明）并绑处理器，
     * 排一个 atDay=5 的动态实例，绑静态槽处理器。
     */
    private class DecisionJobMod : EraMod {
        var context: ModContext? = null
        var jobFired: Int = 0
        var staticFired: Int = 0
        var actionExecuted: Int = 0

        override fun onLoad(context: ModContext) {
            this.context = context
            context.actions.on("act.handler") { actionExecuted++ }
            context.actions.provide("EVENT") { _ ->
                listOf(
                    ActionCandidate(
                        providerPackage = "mod.a",
                        candidateKey = "act.k1",
                        actor = EntityId(1L),
                        weight = 1,
                        cooldownKey = "act.ck",
                        cooldownDays = 3,
                        perDay = 2,
                        handlerKey = "act.handler",
                    ),
                )
            }
            context.jobs.bindTemplate("evt") { _ -> jobFired++ }
            context.jobs.bind("daily") { _ -> staticFired++ }
            context.jobs.scheduleOnce("evt", 5L)
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}
    }

    /**
     * W2-SEED：[entropyTail] 非 null = 注入确定尾巴（同熵同种子的定向面）；null = 走生产
     * 同款真熵尾巴（每次 attach 独立取样 ⇒ 会话间种子互异，往返用例据此走采纳序）。
     */
    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
        enabled: Boolean = true,
        entropyTail: ByteArray? = null,
    ): ModRuntimeSession? =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = enabled,
            logSink = { line -> logs += line },
            modProviders = providers,
            decisionEntropyTail = entropyTail?.let { tail -> { tail } }
                ?: { ByteArray(16).also(java.security.SecureRandom()::nextBytes) },
        )

    private fun bindingOf(): SidecarBinding = SidecarBinding(11L, 22L, 33)

    // ------------------------------------------------------------------
    // 判据 2：开态 actions 脱 fail-closed 桩；关态维持 null（关态零接触由
    // ModSidecarWireTest「关态不安装侧车接线」+ WireTest 关态用例继续锁定）
    // ------------------------------------------------------------------

    @Test
    fun `开态actions是真管线facade且候选被真收集`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val mod = DecisionJobMod()
        val store = VariableStore()
        val session = attach(root, store, listOf("mod.a" to mod))!!
        val context = session.host.contextOf("mod.a") as ModContextImpl
        assertNotNull(context)
        // 反向证明③的对照面：fail-closed 桩的 provide 会抛 UnsupportedOperationException
        // （ModContextActionQueryWireTest 同款判据）；真 facade 再注册一次不抛即装配为真，
        // 且 onLoad 注册的候选经真管线收集、处理器真实执行。
        context.actions.provide("EVENT") { emptyList() }
        val pipeline = session.decisionState!!.pipeline
        assertNotNull(pipeline)
        val report = pipeline.run(ActionSelectionContext(domain = "EVENT", time = GameTime(1, "")))
        assertEquals("onLoad 注册的候选被真管线收集（测试的空 provider 不产候选）", 1, report.evaluated)
        assertEquals(
            "处理器真实执行（actions 脱 fail-closed 桩的行为面）",
            ActionExecutionOutcome.COMPLETED,
            report.outcome,
        )
        assertEquals(1, mod.actionExecuted)
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 1/3/5：容器尾段抓取 → 回灌 → 往返无损 → 重启后 job 实例按期触发
    // ------------------------------------------------------------------

    @Test
    fun `决策态任务态往返无损且重启后job实例按期触发`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val modA = DecisionJobMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))!!

        // 造非平凡决策态：一轮仲裁（COMPLETED ⇒ 冷却 + 每日计数 + 序号推进）。
        val pipelineA = sessionA.decisionState!!.pipeline
        assertNotNull(pipelineA)
        val report = pipelineA.run(ActionSelectionContext(domain = "EVENT", time = GameTime(3, "")))
        assertEquals("候选唯一且执行完成", ActionExecutionOutcome.COMPLETED, report.outcome)
        assertEquals(1, modA.actionExecuted)
        val snapshotA = pipelineA.snapshotState()
        assertEquals("生产路径序号自持推进", 1, snapshotA.nextOrdinal)
        assertTrue("每日计数已记账", snapshotA.dayCounts.isNotEmpty())
        assertTrue("冷却已记账", snapshotA.cooldownUntil.isNotEmpty())

        // 抓取：容器尾段两段都在（判据 1：开态存档必带决策段与任务段）。
        val wiringA = SidecarSaveWiring.of(storeA)!!
        val binding = bindingOf()
        val groups = wiringA.port.captureChunks()
        val bytes = wiringA.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)
        val decoded = SidecarSaveBundle.decode(bytes)
        assertTrue("决策态段必须在容器里", decoded.decisionStatePayload.isNotEmpty())
        assertTrue("任务态段必须在容器里", decoded.jobStatePayload.isNotEmpty())
        assertEquals(
            "决策段载荷 = DecisionStateCodec v2 输出（容器层不解释内容）",
            DecisionStateCodec.encode(snapshotA).toList(),
            decoded.decisionStatePayload.toList(),
        )
        val jobSnapA = JobSchedulerStateCodec.decode(decoded.jobStatePayload)
        assertEquals("动态实例随任务态段入容器", 1, jobSnapA.instances.size)
        assertEquals("mod.a::evt#0", jobSnapA.instances.single().instanceKey)

        // 恢复：新会话（真熵 ⇒ 装配种子 ≠ 档内种子，先经 W2-SEED 采纳序把档内种子供给进
        // 管线，restoreState 种子相等闸自然放行）。
        val modB = DecisionJobMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))!!
        val applied = SidecarSaveWiring.of(storeB)!!.port.restoreRt2(bytes, binding).applied
        assertTrue("绑定吻合且分段可应用", applied)

        // 判据 5：决策态四件无损。
        val pipelineB = sessionB.decisionState!!.pipeline
        assertNotNull(pipelineB)
        assertEquals(snapshotA, pipelineB.snapshotState())
        // 判据 5：任务态回灌 + 重启后 job 实例仍按期触发（DAY=5 ⇒ atDay=5 实例 + 静态槽都触发）。
        storeB.writeInt("DAY", longArrayOf(0L), 5L)
        sessionB.onTurnBoundary()
        assertEquals("动态实例跨存读档后仍按期触发", 1, modB.jobFired)
        assertEquals("静态槽照常按期触发", 1, modB.staticFired)
        val jobSnapB = (sessionB.host.contextOf("mod.a") as ModContextImpl).jobScheduler.snapshot()
        assertEquals("实例触发后 DONE（不复活）", 1, jobSnapB.instances.count { it.state == JobState.DONE })
        sessionA.shutdown()
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 4：随机流独立性负控
    // ------------------------------------------------------------------

    @Test
    fun `仲裁前后原版RAND流零消耗且同seed重放逐位一致`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val draws = ArrayList<Long>()
        // W2-SEED：同 seed 重放面改为显式注入同一条真熵尾巴（生产缺省每局新种子，
        // 不再有「同根 ⇒ 同种子」；判据 2 的同档重放另在种子采纳序上锁）。
        val sharedTail = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val modA = DecisionJobMod()
        val storeA = VariableStore(randProvider = { bound -> draws += bound; 0L })
        val sessionA = attach(root, storeA, listOf("mod.a" to modA), entropyTail = sharedTail)!!
        val pipelineA = sessionA.decisionState!!.pipeline
        assertNotNull(pipelineA)
        val reportA = pipelineA.run(ActionSelectionContext(domain = "EVENT", time = GameTime(3, "")))
        val chosenA = reportA.chosen
        assertNotNull(chosenA)
        val winnerA = chosenA!!.candidateKey
        assertTrue(
            "判据 4a：仲裁不得消费原版 RAND 流（store.randProvider 零抽取）",
            draws.isEmpty(),
        )

        // 判据 4b：同 seed（同注入熵）同候选集重放 → 同一胜者。
        val modB = DecisionJobMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB), entropyTail = sharedTail)!!
        val pipelineB = sessionB.decisionState!!.pipeline
        assertNotNull(pipelineB)
        assertEquals(
            "同注入熵 ⇒ 同种子（熵纪律：同熵同种子）",
            pipelineA.snapshotState().decisionSeed,
            pipelineB.snapshotState().decisionSeed,
        )
        val reportB = pipelineB.run(ActionSelectionContext(domain = "EVENT", time = GameTime(3, "")))
        val chosenB = reportB.chosen
        assertNotNull(chosenB)
        assertEquals(winnerA, chosenB!!.candidateKey)
        sessionA.shutdown()
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 1：旧档无段回落不拒载；坏段 fail-closed；陈旧预留过滤（判据 3）
    // ------------------------------------------------------------------

    @Test
    fun `旧档无段回落不拒载且决策任务态回落空`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val modA = DecisionJobMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))!!
        assertNotNull(sessionA.decisionState!!.pipeline.snapshotState())
        val wiringA = SidecarSaveWiring.of(storeA)!!
        val binding = bindingOf()
        val groups = wiringA.port.captureChunks()
        val fresh = SidecarSaveBundle.decode(
            wiringA.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks),
        )
        // 增补前的旧代码只会写出五段构造（无状态尾段）——这正是旧档字节形状。
        val legacy = SidecarSaveBundle(
            binding = fresh.binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
        ).encode()
        assertNull("旧形容器解码无决策段", SidecarSaveBundle.decode(legacy).decisionStatePayload.takeIf { it.isNotEmpty() })
        assertNull("旧形容器解码无任务段", SidecarSaveBundle.decode(legacy).jobStatePayload.takeIf { it.isNotEmpty() })

        val modB = DecisionJobMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))!!
        val applied = SidecarSaveWiring.of(storeB)!!.port.restoreRt2(legacy, binding).applied
        assertTrue("旧档无段必须照常 Applied（不拒载）", applied)
        val pipelineB = sessionB.decisionState!!.pipeline
        assertNotNull(pipelineB)
        assertEquals(
            "决策态回落「未初始化 + 无动态实例」：计数/冷却/预留全空",
            0,
            pipelineB.snapshotState().dayCounts.size +
                pipelineB.snapshotState().cooldownUntil.size +
                pipelineB.snapshotState().heldReservations.size,
        )
        // 任务态回落「无实例」= 没有任何**恢复**进来的实例；调度器里只有 onLoad 自己排的
        // seq=0 那一条（mod 在本会话 onLoad scheduleOnce 的产物，与容器段无关）。
        val jobSnap = (sessionB.host.contextOf("mod.a") as ModContextImpl).jobScheduler.snapshot()
        assertEquals(
            "任务态回落：无恢复实例（仅本会话 onLoad 自排的一条 seq=0）",
            listOf(0L),
            jobSnap.instances.map { it.seq },
        )
        sessionA.shutdown()
        sessionB.shutdown()
    }

    @Test
    fun `坏决策段fail-closed原样抛不吞`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val modA = DecisionJobMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))!!
        val wiringA = SidecarSaveWiring.of(storeA)!!
        val binding = bindingOf()
        val groups = wiringA.port.captureChunks()
        val fresh = SidecarSaveBundle.decode(
            wiringA.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks),
        )
        // 决策段换垃圾字节（非 codec 魔数）：解码 fail-closed，读档缝既有 catch 的抛出形状。
        val corrupt = SidecarSaveBundle(
            binding = fresh.binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
            stateSegments = com.eratw.port.core.save.SidecarStateSegments(
                decisionState = "not-a-decision-state".toByteArray(Charsets.UTF_8),
                jobState = fresh.jobStatePayload,
            ),
        ).encode()
        val modB = DecisionJobMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))!!
        try {
            SidecarSaveWiring.of(storeB)!!.port.restoreRt2(corrupt, binding)
            fail("坏决策段必须 fail-closed 抛出，不静默丢段")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("决策态"))
        }
        sessionA.shutdown()
        sessionB.shutdown()
    }

    @Test
    fun `陈旧chara预留按内核存活过滤并报告`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val modA = DecisionJobMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))!!
        val seed = sessionA.decisionState!!.pipeline.snapshotState().decisionSeed
        val wiringA = SidecarSaveWiring.of(storeA)!!
        val binding = bindingOf()
        val groups = wiringA.port.captureChunks()
        val fresh = SidecarSaveBundle.decode(
            wiringA.port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks),
        )
        // 手工造带 held 的决策态：chara 实体 999999 不存在（内核 exists=false ⇒ 陈旧回收），
        // 非 chara kind 宿主无判定依据照原样保留。
        val state = DecisionState(
            decisionSeed = seed,
            nextOrdinal = 7,
            dayCounts = emptyList(),
            actorCounts = emptyList(),
            cooldownUntil = emptyList(),
            heldReservations = listOf(
                ReservationKey("chara", "999999"),
                ReservationKey("custom.lock", "k1"),
            ),
        )
        val withHeld = SidecarSaveBundle(
            binding = fresh.binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
            stateSegments = com.eratw.port.core.save.SidecarStateSegments(
                decisionState = DecisionStateCodec.encode(state),
                jobState = fresh.jobStatePayload,
            ),
        ).encode()
        val modB = DecisionJobMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))!!
        assertTrue(SidecarSaveWiring.of(storeB)!!.port.restoreRt2(withHeld, binding).applied)
        val pipelineB = sessionB.decisionState!!.pipeline
        assertNotNull(pipelineB)
        assertEquals("陈旧 chara 预留被回收并计数", 1, sessionB.decisionState!!.lastStaleReservationDrops)
        assertEquals(
            "存活（宿主无判定依据）的预留键原样保留",
            setOf(ReservationKey("custom.lock", "k1")),
            pipelineB.heldReservations(),
        )
        assertEquals("序号回灌无损", 7, pipelineB.snapshotState().nextOrdinal)
        assertTrue(
            "回收必须上日志面留痕（增补 B 口径，不静默）",
            logs.any { it.contains("陈旧预留回收") },
        )
        sessionA.shutdown()
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 6：两段全空 = 增补前逐字节同形（字节布局锁）
    // ------------------------------------------------------------------

    @Test
    fun `两段全空编码与增补前字节同形`() {
        val registry = ByteArray(16) { it.toByte() }
        val graph = ByteArray(8) { (it + 1).toByte() }
        val chunks = listOf(com.eratw.port.core.save.PackageChunk("mod.a", byteArrayOf(1, 2, 3)))
        val binding = SidecarBinding(1L, 2L, 3)
        val bytes = SidecarSaveBundle(
            binding = binding,
            registrySnapshot = registry,
            entityGraphPayload = graph,
            chunks = chunks,
            virtualNoSnapshot = ByteArray(0),
        ).encode()
        // 手算增补前布局：头(4+2+8+8+4) + 注册表(4+16) + 虚拟NO(4+0) + 实体图(4+8)
        // + 分片数(4) + 分片(4+5+4+3) + CRC(4)。
        val expected = 26 + 20 + 4 + 12 + 4 + 16 + 4
        assertEquals("全空状态段 = 增补前字节形状（判据 6 的字节锁）", expected, bytes.size)
        val decoded = SidecarSaveBundle.decode(bytes)
        assertFalse(decoded.decisionStatePayload.isNotEmpty())
        assertFalse(decoded.jobStatePayload.isNotEmpty())
        // 有段形态：任一非空 ⇒ 两段都写（决策段在前）。
        val withState = SidecarSaveBundle(
            binding = binding,
            registrySnapshot = registry,
            entityGraphPayload = graph,
            chunks = chunks,
            virtualNoSnapshot = ByteArray(0),
            stateSegments = com.eratw.port.core.save.SidecarStateSegments(
                decisionState = byteArrayOf(9),
                jobState = ByteArray(0),
            ),
        ).encode()
        assertEquals(expected + 4 + 1 + 4 + 0, withState.size)
        val decodedState = SidecarSaveBundle.decode(withState)
        assertEquals(byteArrayOf(9).toList(), decodedState.decisionStatePayload.toList())
        assertTrue(decodedState.jobStatePayload.isEmpty())
    }

    // ------------------------------------------------------------------
    // 关态零变化（判据 6）：总闸关 ⇒ 不装侧车接线 ⇒ .rt2 一个都不写。
    // ------------------------------------------------------------------

    @Test
    fun `关态不装侧车接线不产出会话`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val store = VariableStore()
        val session = attach(root, store, listOf("mod.a" to DecisionJobMod()), enabled = false)
        assertNull("关态不产出会话", session)
        assertNull("关态不装侧车接线（.rt2 逐字节不变的机制保证：根本不写）", SidecarSaveWiring.of(store))
        assertTrue("关态零日志（零接触）", logs.isEmpty())
    }
}
