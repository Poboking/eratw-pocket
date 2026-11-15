package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.DecisionPipeline
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.DecisionSeed
import com.eratw.port.core.mod.DecisionState
import com.eratw.port.core.mod.DecisionStateCodec
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.save.SidecarStateSegments
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ActionSelectionContext
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * W2-SEED 的 app 装配面判据（CONTRACT-RT2-SEED 判据 1/2/3/4 的定向面）：
 * 每局新种子（判据 1，真熵尾巴 + 生产缺省 SecureRandom 面）、种子随档（判据 2，
 * 采纳序 + 重放逐位一致 + 重复重载不漂移）、旧档无段回落（判据 3）、已消费/一次性
 * fail-closed 点名（判据 4 的保持面）。
 *
 * 行为面走真装配链：`ModRuntimeAssembly.attach` 生产构造点 + `SidecarSaveWiring` 端口
 * （SAVEGAME/LOADGAME 的容器物化缝，生产同一条路），与 DecisionStateSaveWireTest 同款。
 */
class DecisionSeedFollowsSaveWireTest {

    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-seed-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = com.eratw.port.core.patch.ManifestCodec.encode(
            ModDeclarationAdapter.toDescriptor(manifest),
        )
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private fun writeJobManifest(root: Path) {
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(
                packageId = "mod.a",
                archetypes = listOf(com.eratw.port.modapi.ArchetypeDecl("mod.a.trait")),
            ),
        )
    }

    /**
     * 双权重候选 mod：胜者取决于仲裁抽取（同 seed + 同键 ⇒ 同胜者；异 seed ⇒ 流不同），
     * 判据 1/2 的重放面载体。
     */
    private class TwoCandidateMod : EraMod {
        var executed: MutableList<String> = mutableListOf()

        override fun onLoad(context: ModContext) {
            context.actions.provide("EVENT") { _ ->
                listOf(
                    ActionCandidate(
                        providerPackage = "mod.a",
                        candidateKey = "act.k1",
                        actor = EntityId(1L),
                        weight = 1,
                        handlerKey = "act.h1",
                    ),
                    ActionCandidate(
                        providerPackage = "mod.a",
                        candidateKey = "act.k2",
                        actor = EntityId(1L),
                        weight = 3,
                        handlerKey = "act.h2",
                    ),
                )
            }
            context.actions.on("act.h1") { executed += "h1" }
            context.actions.on("act.h2") { executed += "h2" }
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
        entropyTail: ByteArray? = null,
    ): ModRuntimeSession =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = providers,
            // 与 DecisionStateSaveWireTest.attach 同款：null = 生产真熵尾巴（每 attach 独立
            // 取样）；非 null = 注入确定字节做同熵/异熵定向。
            decisionEntropyTail = entropyTail?.let { tail -> { tail } }
                ?: { ByteArray(16).also(java.security.SecureRandom()::nextBytes) },
        )!!

    private fun bindingOf(): SidecarBinding = SidecarBinding(11L, 22L, 33)

    private fun drawOnce(pipeline: DecisionPipeline, day: Long = 3L): String {
        val report = pipeline.run(ActionSelectionContext(domain = "EVENT", time = GameTime(day, "")))
        assertNotNull("双权重候选必抽出一胜者", report.chosen)
        return report.chosen!!.candidateKey
    }

    // ------------------------------------------------------------------
    // 判据 1：每局新种子（真熵）
    // ------------------------------------------------------------------

    @Test
    fun `01 同一安装连续两局无历史档种子不同`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        // 生产缺省面（SecureRandom 尾巴）：两次装配各自独立取样 ⇒ 种子互异。
        // 碰撞上界 2^-64（SHA-256 前 8 字节全同），工程上视为不可能。
        val modA = TwoCandidateMod()
        val sessionA = attach(root, VariableStore(), listOf("mod.a" to modA))
        val modB = TwoCandidateMod()
        val sessionB = attach(root, VariableStore(), listOf("mod.a" to modB))
        val seedA = sessionA.decisionState!!.pipeline.snapshotState().decisionSeed
        val seedB = sessionB.decisionState!!.pipeline.snapshotState().decisionSeed
        assertNotEquals("判据 1：每局新种子（生产真熵尾巴）", seedA, seedB)
        // 异注入熵 ⇒ 异种子：确定性变体（不依赖随机碰撞率）。
        val tailX = ByteArray(16) { (it + 1).toByte() }
        val tailY = ByteArray(16) { (it + 17).toByte() }
        val sessionX = attach(root, VariableStore(), listOf("mod.a" to TwoCandidateMod()), tailX)
        val sessionY = attach(root, VariableStore(), listOf("mod.a" to TwoCandidateMod()), tailY)
        assertNotEquals(
            "判据 1：异注入熵 ⇒ 异种子",
            sessionX.decisionState!!.pipeline.snapshotState().decisionSeed,
            sessionY.decisionState!!.pipeline.snapshotState().decisionSeed,
        )
        // 同注入熵 ⇒ 同种子：熵纪律（同熵同种子）经装配链成立。
        val sessionX2 = attach(root, VariableStore(), listOf("mod.a" to TwoCandidateMod()), tailX)
        assertEquals(
            "同注入熵 ⇒ 同种子（DecisionSeed.derive 确定性经装配链保持）",
            sessionX.decisionState!!.pipeline.snapshotState().decisionSeed,
            sessionX2.decisionState!!.pipeline.snapshotState().decisionSeed,
        )
        sessionA.shutdown()
        sessionB.shutdown()
        sessionX.shutdown()
        sessionY.shutdown()
        sessionX2.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 2：种子随档（采纳序 + 重放逐位一致 + 重复重载不漂移）
    // ------------------------------------------------------------------

    @Test
    fun `02 存档重载种子随档且重放逐位一致且重复重载不漂移`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val binding = bindingOf()
        // 局 A：装配后先抓档（种子 SA、零抽取、零计数），再抽一次定胜者 W
        //（抽取键 = day3/EVENT/nth1 + 种子 SA）。
        val modA = TwoCandidateMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))
        val pipelineA = sessionA.decisionState!!.pipeline
        val seedA = pipelineA.snapshotState().decisionSeed
        val groups = SidecarSaveWiring.of(storeA)!!.port.captureChunks()
        val bytes = SidecarSaveWiring.of(storeA)!!
            .port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)
        val winnerA = drawOnce(pipelineA)
        assertEquals("抽取不改变种子", seedA, pipelineA.snapshotState().decisionSeed)

        // 局 B：真熵 ⇒ 装配种子 SB ≠ SA；重载 A 的档 ⇒ 采纳序把管线种子供给为 SA，
        // 相等闸自然放行；同候选集 + 同日 + 同相位（抽取键同 A 的首次抽取）⇒ 同胜者。
        val modB = TwoCandidateMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))
        val pipelineB = sessionB.decisionState!!.pipeline
        val seedB = pipelineB.snapshotState().decisionSeed
        assertNotEquals("新局装配种子与档内种子不同（判据 1 的前提面）", seedA, seedB)
        assertTrue("种子随档重载可应用", SidecarSaveWiring.of(storeB)!!.port.restoreRt2(bytes, binding).applied)
        assertEquals(
            "重载后管线种子 = 档内 decisionSeed（判据 2）",
            seedA,
            pipelineB.snapshotState().decisionSeed,
        )
        assertTrue(
            "采纳序上日志面留痕（不静默）",
            logs.any { it.contains("决策种子随档采纳") },
        )
        assertEquals("重放逐位一致（同 seed + 同键 ⇒ 同胜者）", winnerA, drawOnce(pipelineB))

        // 重复重载不漂移：B 的管线种子已是 SA ⇒ 档内种子相等走原闸放行（不供给），
        // 重载后再抽取与首局仍逐位一致；再抓档种子仍为 SA。
        assertTrue(SidecarSaveWiring.of(storeB)!!.port.restoreRt2(bytes, binding).applied)
        assertEquals("重复重载种子不漂移", seedA, pipelineB.snapshotState().decisionSeed)
        assertEquals("重放仍逐位一致", winnerA, drawOnce(pipelineB))
        val groupsB = SidecarSaveWiring.of(storeB)!!.port.captureChunks()
        val bytesB = SidecarSaveWiring.of(storeB)!!
            .port.captureRt2Bytes(binding, groupsB.activeChunks, groupsB.orphanChunks)
        assertEquals(
            "重载会话再存档：档内种子仍 = 原档 decisionSeed（无漂移）",
            DecisionStateCodec.decode(SidecarSaveBundle.decode(bytes).decisionStatePayload).decisionSeed,
            DecisionStateCodec.decode(SidecarSaveBundle.decode(bytesB).decisionStatePayload).decisionSeed,
        )
        sessionA.shutdown()
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 3：旧档无段回落（管线保持装配期种子照常载入）+ 首次存档起入段
    // ------------------------------------------------------------------

    @Test
    fun `03 旧档无段回落不拒载且首次存档起种子入段`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val binding = bindingOf()
        val modA = TwoCandidateMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))
        val groups = SidecarSaveWiring.of(storeA)!!.port.captureChunks()
        val fresh = SidecarSaveBundle.decode(
            SidecarSaveWiring.of(storeA)!!
                .port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks),
        )
        // 增补前的旧代码只会写出五段构造（无状态尾段）——这正是旧档字节形状。
        val legacy = SidecarSaveBundle(
            binding = fresh.binding,
            registrySnapshot = fresh.registrySnapshot,
            entityGraphPayload = fresh.entityGraphPayload,
            chunks = fresh.chunks,
            virtualNoSnapshot = fresh.virtualNoSnapshot,
        ).encode()

        // 旧档载入新会话：无采纳步（无段 ⇒ 无种子可恢复），管线保持装配期种子，照常 Applied。
        val modB = TwoCandidateMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))
        val pipelineB = sessionB.decisionState!!.pipeline
        val seedB = pipelineB.snapshotState().decisionSeed
        assertTrue("旧档无段必须照常 Applied（不拒载）", SidecarSaveWiring.of(storeB)!!.port.restoreRt2(legacy, binding).applied)
        assertEquals(
            "无段回落：管线种子保持装配期值（不被改写）",
            seedB,
            pipelineB.snapshotState().decisionSeed,
        )
        assertTrue(
            "无段回落不写采纳日志",
            logs.none { it.contains("决策种子随档采纳") },
        )
        // 该局首次存档起种子入段：抓出的容器决策段种子 = 本局装配种子。
        val groupsB = SidecarSaveWiring.of(storeB)!!.port.captureChunks()
        val bytesB = SidecarSaveWiring.of(storeB)!!
            .port.captureRt2Bytes(binding, groupsB.activeChunks, groupsB.orphanChunks)
        val savedSeed = DecisionStateCodec.decode(SidecarSaveBundle.decode(bytesB).decisionStatePayload)
            .decisionSeed
        assertEquals("首次存档起种子入段", seedB, savedSeed)
        sessionA.shutdown()
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 4：已消费会话重载异种子档 = 点名拒
    // ------------------------------------------------------------------

    @Test
    fun `04 已消费会话重载异种子档点名拒`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val binding = bindingOf()
        val modA = TwoCandidateMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))
        val groups = SidecarSaveWiring.of(storeA)!!.port.captureChunks()
        val bytesA = SidecarSaveWiring.of(storeA)!!
            .port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)
        val seedA = DecisionStateCodec.decode(SidecarSaveBundle.decode(bytesA).decisionStatePayload)
            .decisionSeed

        // 已消费会话（本局已抽取）重载异种子档：supplySeed 已消费闸 fail-closed，
        // restoreRt2 原样抛（读档缝既有 catch 收警告，点名不吞）。
        val modB = TwoCandidateMod()
        val storeB = VariableStore()
        val sessionB = attach(root, storeB, listOf("mod.a" to modB))
        val pipelineB = sessionB.decisionState!!.pipeline
        val seedB = pipelineB.snapshotState().decisionSeed
        assertNotEquals(seedA, seedB)
        drawOnce(pipelineB)
        try {
            SidecarSaveWiring.of(storeB)!!.port.restoreRt2(bytesA, binding)
            fail("已消费会话重载异种子档必须 fail-closed 抛出（判据 4）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "点名已消费闸：" + expected.message,
                expected.message!!.contains("已消费"),
            )
        }
        assertEquals("拒后管线种子保持装配值", seedB, pipelineB.snapshotState().decisionSeed)
        sessionA.shutdown()
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 4：一次性闸——一次采纳后再供给异种子 = 点名拒
    // ------------------------------------------------------------------

    @Test
    fun `05 一次采纳后二次供给异种子一次性拒`() {
        val root = tempGameRoot()
        writeJobManifest(root)
        val binding = bindingOf()
        val modA = TwoCandidateMod()
        val storeA = VariableStore()
        val sessionA = attach(root, storeA, listOf("mod.a" to modA))
        val groups = SidecarSaveWiring.of(storeA)!!.port.captureChunks()
        val bytesA = SidecarSaveWiring.of(storeA)!!
            .port.captureRt2Bytes(binding, groups.activeChunks, groups.orphanChunks)
        val seedA = DecisionStateCodec.decode(SidecarSaveBundle.decode(bytesA).decisionStatePayload)
            .decisionSeed

        // 未消费会话先采纳一次（放行）；再造一段异种子档（种子 SD、空态）再重载：
        // supplySeed 一次性闸 fail-closed 点名拒，种子保持首次采纳值。
        val modC = TwoCandidateMod()
        val storeC = VariableStore()
        val sessionC = attach(root, storeC, listOf("mod.a" to modC))
        assertTrue(SidecarSaveWiring.of(storeC)!!.port.restoreRt2(bytesA, binding).applied)
        val secondSeed = DecisionSeed.derive("crafted-second".toByteArray())
        assertNotEquals(secondSeed, seedA)
        val freshC = SidecarSaveBundle.decode(bytesA)
        val secondSave = SidecarSaveBundle(
            binding = freshC.binding,
            registrySnapshot = freshC.registrySnapshot,
            entityGraphPayload = freshC.entityGraphPayload,
            chunks = freshC.chunks,
            virtualNoSnapshot = freshC.virtualNoSnapshot,
            stateSegments = SidecarStateSegments(
                decisionState = DecisionStateCodec.encode(
                    DecisionState(
                        decisionSeed = secondSeed,
                        nextOrdinal = 0,
                        dayCounts = emptyList(),
                        actorCounts = emptyList(),
                        cooldownUntil = emptyList(),
                        heldReservations = emptyList(),
                    ),
                ),
                jobState = freshC.jobStatePayload,
            ),
        ).encode()
        try {
            SidecarSaveWiring.of(storeC)!!.port.restoreRt2(secondSave, binding)
            fail("一次采纳后再次供给异种子必须 fail-closed 抛出（一次性）")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "点名一次性闸：" + expected.message,
                expected.message!!.contains("一次性"),
            )
        }
        assertEquals(
            "拒后种子仍为首次采纳值",
            seedA,
            sessionC.decisionState!!.pipeline.snapshotState().decisionSeed,
        )
        sessionA.shutdown()
        sessionC.shutdown()
    }
}
