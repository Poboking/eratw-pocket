package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ActionExecutionOutcome
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.DecisionElimination
import com.eratw.port.core.mod.DecisionPipeline
import com.eratw.port.core.mod.DecisionReasonCodes
import com.eratw.port.core.mod.DecisionState
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ReservationKey
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERAFIN2-A2：`chara:` 预留键的**生产 claimant 端到端**（`ModRuntimeAssembly
 * .CHARA_RESERVATION_KIND` KDoc 原 TODO(verify) 的销账面）。
 *
 * 锚：mod-api `ActionApi.kt` ReservationKey「`chara:<entityId>` 预留即占槽」+ 宿主存活谓词
 * `charaReservationAlive`（kind=chara 按内核 `exists` 判活）。本文件钉四件事：
 *
 * 1. **格式**：claim key = [EntityId]`raw` 十进制串——作者用 `context.entities.create` 建
 *    的真实体（内核 `EntityKernel.create` 编码 pkg/generation/slot）取 `raw.toString()`，
 *    宿主谓词把它解析回同一 Long 且 `exists` 判活（恢复存活侧用例是格式回路的反向证）；
 * 2. **占槽（写侧）**：候选被仲裁选中后 handler 执行期间 claim 在 `heldReservations` 里，
 *    执行结束（finally）即释放；
 * 3. **冲突**：占槽期间的嵌套仲裁对同 `chara` 预留判 `RESERVATION_CONFLICT` 淘汰（点名
 *    held 键）；
 * 4. **恢复**：活实体的 `chara` 预留跨存档恢复存活；坏格式键（非十进制串 / NULL id）按
 *    「宁可误杀不可留悬空锁」判死回收并留痕。
 *
 * 陈旧回收的既有反向面（死实体 → 回收）由 `DecisionStateSaveWireTest.陈旧chara预留按内核
 * 存活过滤并报告` 继续锁定，本文件不重复。
 */
class ModFin2CharaClaimTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-fin2-chara")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    /** 声明 `mod.a.npc` 原型（`EntityKernel.create` 的 archetype 注册闸要求在册）。 */
    private fun writeManifest(root: Path) {
        val bytes = com.eratw.port.core.patch.ManifestCodec.encode(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(
                    packageId = "mod.a",
                    archetypes = listOf(ArchetypeDecl("mod.a.npc")),
                ),
            ),
        )
        Files.write(root.resolve("mods").resolve("rt2").resolve("mod.a.manifest"), bytes)
    }

    private fun attach(root: Path, store: VariableStore, providers: List<Pair<String, EraMod>>) =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
        )!!

    /**
     * claimant mod：onLoad 自建活实体（`EntityApi.create` = 内核 create 的唯一作者面），
     * 注册「带 chara 预留的候选」与嵌套仲裁探针。`pipelineRef` 由测试在 run 前灌入——
     * handler 执行期内读 `heldReservations`（占槽面）并起嵌套仲裁（冲突面）。
     */
    /** [createsEntity] = false 时 onLoad 不自建实体（恢复侧夹具必须留空内核——混档拒绝见 EntityKernel.restore）。 */
    private class CharaClaimMod(private val createsEntity: Boolean = true) : EraMod {
        var npcId: EntityId? = null
        var executed: Int = 0
        @Volatile var heldAtExecution: Set<ReservationKey> = emptySet()
        @Volatile var nestedEliminations: List<DecisionElimination> = emptyList()
        @Volatile var pipelineRef: DecisionPipeline? = null

        override fun onLoad(context: ModContext) {
            if (createsEntity) npcId = context.entities.create("mod.a.npc")
            context.actions.on("claim.handler") {
                executed++
                val pipeline = pipelineRef
                if (pipeline != null) {
                    heldAtExecution = pipeline.heldReservations()
                    val nested = pipeline.run(ActionSelectionContext(domain = "NESTED", time = GameTime(1, "")))
                    nestedEliminations = nested.eliminations
                }
            }
            context.actions.on("nested.handler") { }
            context.actions.provide("EVENT") { _ ->
                val npc = checkNotNull(npcId)
                listOf(
                    ActionCandidate(
                        providerPackage = "mod.a",
                        candidateKey = "claim.k1",
                        actor = npc,
                        weight = 1,
                        reservationClaims = listOf(ReservationKey("chara", npc.raw.toString())),
                        handlerKey = "claim.handler",
                    ),
                )
            }
            context.actions.provide("NESTED") { _ ->
                val npc = checkNotNull(npcId)
                listOf(
                    ActionCandidate(
                        providerPackage = "mod.a",
                        candidateKey = "nested.k2",
                        actor = npc,
                        weight = 1,
                        reservationClaims = listOf(ReservationKey("chara", npc.raw.toString())),
                        handlerKey = "nested.handler",
                    ),
                )
            }
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    // ------------------------------------------------------------------
    // 1+2：格式钉死 + 执行期占槽 / 结束释放
    // ------------------------------------------------------------------

    @Test
    fun `chara预留随候选执行占槽且结束释放`() {
        val root = tempGameRoot()
        writeManifest(root)
        val mod = CharaClaimMod()
        val session = attach(root, VariableStore(), listOf("mod.a" to mod))
        val npc = checkNotNull(mod.npcId)
        assertTrue(
            "测试前提：onLoad 自建实体是良构非 NULL id（内核 create 编码面）",
            npc.raw != 0L,
        )
        val pipeline = session.decisionState!!.pipeline
        mod.pipelineRef = pipeline
        val key = ReservationKey("chara", npc.raw.toString())

        val report = pipeline.run(ActionSelectionContext(domain = "EVENT", time = GameTime(1, "")))

        assertEquals("候选唯一且执行完成", ActionExecutionOutcome.COMPLETED, report.outcome)
        assertEquals("格式 = EntityId.raw 十进制串（key 与 raw 逐字对应）", npc.raw.toString(), key.key)
        assertEquals(
            "执行中占槽（写侧）：held 恰含本 claim",
            setOf(key),
            mod.heldAtExecution,
        )
        assertEquals("执行结束（finally）即释放", emptySet<ReservationKey>(), pipeline.heldReservations())
        assertEquals(1, mod.executed)
        assertEquals("外轮无淘汰", 0, report.eliminations.size)
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 3：占槽期间嵌套仲裁 → RESERVATION_CONFLICT
    // ------------------------------------------------------------------

    @Test
    fun `嵌套仲裁见同chara预留判冲突淘汰且点名held键`() {
        val root = tempGameRoot()
        writeManifest(root)
        val mod = CharaClaimMod()
        val session = attach(root, VariableStore(), listOf("mod.a" to mod))
        val npc = checkNotNull(mod.npcId)
        val pipeline = session.decisionState!!.pipeline
        mod.pipelineRef = pipeline

        val report = pipeline.run(ActionSelectionContext(domain = "EVENT", time = GameTime(1, "")))

        assertEquals("外层候选执行完成", ActionExecutionOutcome.COMPLETED, report.outcome)
        val nested = mod.nestedEliminations
        assertEquals("嵌套轮恰一条淘汰（同 chara 预留冲突）", 1, nested.size)
        assertEquals(
            "淘汰原因 = RESERVATION_CONFLICT",
            DecisionReasonCodes.RESERVATION_CONFLICT,
            nested.single().reasonCode,
        )
        assertEquals("淘汰归属 = 嵌套候选包", "mod.a", nested.single().packageId)
        assertEquals(
            "淘汰详情点名 held 的键（kind/key 原文）",
            "held: chara/${npc.raw}",
            nested.single().detail,
        )
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 4：恢复侧——活实体存活 / 坏格式判死
    // ------------------------------------------------------------------

    @Test
    fun `恢复侧_活实体chara预留存活且坏格式键判死回收`() {
        val root = tempGameRoot()
        writeManifest(root)
        val modB = CharaClaimMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))
        val seed = sessionB.decisionState!!.pipeline.snapshotState().decisionSeed

        val binding = SidecarBinding(11L, 22L, 33)
        // 容器段取会话 B 的真实抓取（实体图带 npcB、注册表魔数 / 分片与生产同形），唯一换掉
        // 决策段（带 held）。存活键只能指向**存档里的实体**：EntityKernel.restore 混档拒绝
        // （core-vm EntityKernel.kt:501-507，SaveService.kt:144 调用点），生产时序 = LOADDATA
        // 恢复先于 mod 自建实体的装载相位（本文件头 attach 注释同一条事实）——「活实体」由
        // 存档实体图灌入、跨会话同 id 回存活，恢复会话夹具不许 onLoad 再自建实体。
        val wiringB = SidecarSaveWiring.of(storeB)!!
        val groupsB = wiringB.port.captureChunks()
        val fresh = SidecarSaveBundle.decode(
            wiringB.port.captureRt2Bytes(binding, groupsB.activeChunks, groupsB.orphanChunks),
        )
        val modC = CharaClaimMod(createsEntity = false)
        val storeC = VariableStore()
        val sessionC = attach(root, storeC, listOf("mod.a" to modC))
        val npcB = checkNotNull(modB.npcId)
        val state = DecisionState(
            decisionSeed = seed,
            nextOrdinal = 7,
            dayCounts = emptyList(),
            actorCounts = emptyList(),
            cooldownUntil = emptyList(),
            heldReservations = listOf(
                ReservationKey("chara", npcB.raw.toString()),
                ReservationKey("chara", "not-a-number"),
                ReservationKey("chara", "0"),
            ),
        )
        val bytes = SidecarSaveBundle(
            binding = binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
            stateSegments = com.eratw.port.core.save.SidecarStateSegments(
                decisionState = DecisionStateCodec.encode(state),
                jobState = fresh.jobStatePayload,
            ),
        ).encode()

        assertTrue("绑定吻合且分段可应用", SidecarSaveWiring.of(storeC)!!.port.restoreRt2(bytes, binding).applied)
        val pipelineC = sessionC.decisionState!!.pipeline
        assertEquals(
            "坏格式两键判死回收（非十进制串 / NULL id——宁可误杀不可留悬空锁）",
            2,
            sessionC.decisionState!!.lastStaleReservationDrops,
        )
        assertEquals(
            "活实体的 chara 预留跨存档恢复存活（格式回路的正向证：谓词解析回同一 Long 且 exists 判活）",
            setOf(ReservationKey("chara", npcB.raw.toString())),
            pipelineC.heldReservations(),
        )
        assertTrue(
            "回收必须上日志面留痕（不静默）",
            logs.any { it.contains("陈旧预留回收") },
        )
        sessionB.shutdown()
        sessionC.shutdown()
    }
}
