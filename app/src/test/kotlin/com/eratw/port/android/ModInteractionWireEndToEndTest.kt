package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.InteractionApi
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交互生产触发点 → [InteractionPipeline] 派发 → 结果回写（日志 / 诊断）的**生产缝 e2e**
 * （直打 `ModRuntimeAssembly.attach`，不手搓装配）。
 *
 * 生产触发点两条（ModDX-4 M4 起并存）：
 * 1. **输入驱动**（RT2 P0-LAST）：玩家输入证据 → `notePlayerInput` 累积 → 页边界
 *    `consumeOnBoundary` 消费（id = `host.input`）；
 * 2. **回合级**（ModDX-4 M4 判据 2）：页边界**每拍**一次 `runTurnFrame`，witness frame 随
 *    metadata 透传（id = `host.turn`）；无注册 handler 先闸后建零成本旁路。
 *
 * 判据面：
 * 1. 采集侧照 `GameHost` 的真实三形态（mapped-color / cbg-button / 命令行）喂 `notePlayerInput`；
 * 2. 派发后五相位顺序 = `InteractionPipeline.BEFORE_MAIN` + MAIN + `AFTER_MAIN`，metadata
 *    透传 `source` / `text` / `count`（输入）与 witness 五键（回合级）；
 * 3. 两触发在同一页边界可并存且以 interactionId 可分；输入证据只消费一次、不跨拍重放；
 * 4. INTERRUPT / REPLACE / 深度 32 / handler 抛异常四条分支的结果回写（日志行原样断言）；
 * 5. VM 线程闸与 shutdown 收口：旁路成日志、输入不被吞、边界不再派发。
 */
class ModInteractionWireEndToEndTest {

    private val logs = mutableListOf<String>()

    private fun attach(
        phases: List<InteractionPhase> = ALL_PHASES,
        mod: RecorderMod = RecorderMod(),
        onVmThread: () -> Boolean = { true },
        store: VariableStore = VariableStore(),
        root: Path = tempGameRoot(phases),
    ): ModRuntimeSession? {
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = onVmThread,
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = listOf(PKG to mod),
        )
        assertNotNull("开态必须装配会话", session)
        assertNotNull("开态必须装配交互触发点", session!!.hostSeams)
        return session
    }

    /** 按 interactionId 分流派发事件（回合级 host.turn 与输入 host.input 在同一拍并存）。 */
    private fun RecorderMod.eventsOf(id: String): List<InteractionHookContext> =
        events.filter { it.interaction.interactionId == id }

    @Test
    fun `页边界前零派发，边界把五相位送进管线并透传元数据`() {
        val mod = RecorderMod()
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "mapped-color:5")
            assertEquals(1, wire.pendingCount())
            assertTrue("采集只入队：页边界之外零派发", mod.events.isEmpty())
            session.onTurnBoundary()
            assertEquals(
                "输入触发的五相位全到且 metadata 如实透传",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase },
            )
            val first = mod.eventsOf(ModInteractionWire.INTERACTION_ID).first()
            assertEquals(PKG, first.packageId)
            assertEquals(ModInteractionWire.INTERACTION_ID, first.interaction.interactionId)
            assertEquals(EntityId.NULL, first.interaction.actor)
            assertEquals(emptyList<EntityId>(), first.interaction.targets)
            assertEquals(
                mapOf("source" to "click", "text" to "mapped-color:5", "count" to "1"),
                first.interaction.metadata,
            )
            assertEquals("边界消费后队列必须清空", 0, wire.pendingCount())
            session.onTurnBoundary()
            assertEquals(
                "无新输入时输入触发不得重复派发",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase },
            )
            assertEquals(
                "回合级触发每拍独立派发（ModDX-4 M4 判据 2）",
                2 * ALL_PHASES.size,
                mod.eventsOf(ModInteractionWire.TURN_INTERACTION_ID).size,
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `同一页内多笔输入合并成一笔派发且只保留最后一笔文案`() {
        val mod = RecorderMod()
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "first")
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "cbg-button:7")
            assertEquals(2, wire.pendingCount())
            assertTrue("合并发生在边界：边界前仍零派发", mod.events.isEmpty())
            session.onTurnBoundary()
            assertEquals(
                "两笔输入只能派发一笔（每笔 = 五相位）",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase },
            )
            val input = mod.eventsOf(ModInteractionWire.INTERACTION_ID).first()
            assertEquals("2", input.interaction.metadata["count"])
            assertEquals("cbg-button:7", input.interaction.metadata["text"])
            assertEquals(0, wire.pendingCount())
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `无输入时输入触发零派发，回合级触发独立每拍派发`() {
        val mod = RecorderMod()
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            session.onTurnBoundary()
            assertTrue("无输入时输入触发不得派发", mod.eventsOf(ModInteractionWire.INTERACTION_ID).isEmpty())
            assertEquals(
                "回合级触发不依赖输入证据（ModDX-4 M4）",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.TURN_INTERACTION_ID).map { it.phase },
            )
            assertTrue(
                "回合级派发必须留一行读数",
                logs.any { it.contains("mod.interaction 回合级触发") },
            )
            wire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "look")
            session.onTurnBoundary()
            assertEquals(
                "命令行采集形态在下次边界兑现",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase },
            )
            assertEquals(
                "command",
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).first().interaction.metadata["source"],
            )
            assertEquals(0, wire.pendingCount())
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `回合级触发透传 witness frame metadata`() {
        val mod = RecorderMod()
        val session = attach(mod = mod)!!
        try {
            session.onTurnBoundary()
            val turn = mod.eventsOf(ModInteractionWire.TURN_INTERACTION_ID).first()
            assertEquals(ModInteractionWire.TURN_INTERACTION_ID, turn.interaction.interactionId)
            assertEquals(
                "无角色表时 witness 空清单（数字形状如实，不发明在场者）",
                "0",
                turn.interaction.metadata[ModInteractionWire.WITNESS_COUNT_KEY],
            )
            assertTrue(
                "witness 清单键必须存在（逗号分隔角色号，空 = 空串）",
                turn.interaction.metadata.containsKey(ModInteractionWire.WITNESS_KEY),
            )
            assertTrue(
                "TARGET 键必须存在（无效锚如实透传）",
                turn.interaction.metadata.containsKey(ModInteractionWire.TARGET_KEY),
            )
            assertEquals(EntityId.NULL, turn.interaction.actor)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `前置相位 INTERRUPT 立即截断后续相位并回写结果`() {
        val mod = RecorderMod()
        mod.onHook = { hook ->
            if (hook.phase == InteractionPhase.PRECONDITION) {
                InteractionResult.INTERRUPT
            } else {
                InteractionResult.CONTINUE
            }
        }
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "mapped-color:5")
            session.onTurnBoundary()
            assertEquals(
                "INTERRUPT 后 PRE/MAIN/POST/SETTLEMENT 都不得执行（回合级与输入两派发各自从 PRECONDITION 起跑并被截断）",
                listOf(InteractionPhase.PRECONDITION, InteractionPhase.PRECONDITION),
                mod.events.map { it.phase },
            )
            assertTrue(
                "回写行必须原样带 interrupted=true replaced=false",
                logs.any { it.contains("interrupted=true replaced=false") },
            )
            assertEquals(0, wire.pendingCount())
            session.onTurnBoundary()
            assertEquals(
                "回合级每拍再派发一次（每拍都从 PRECONDITION 起跑并被截断；输入不重放）",
                3,
                mod.events.size,
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `前置相位 REPLACE 跳过 MAIN 且用替换值回写结果`() {
        val mod = RecorderMod()
        mod.onHook = { hook ->
            if (hook.phase == InteractionPhase.PRE) {
                hook.replacedResult = ModValue.of(42L)
                InteractionResult.REPLACE
            } else {
                InteractionResult.CONTINUE
            }
        }
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "mapped-color:5")
            session.onTurnBoundary()
            assertEquals(
                "REPLACE 后 MAIN 必须被跳过、AFTER_MAIN 仍执行（回合级派发）",
                listOf(
                    InteractionPhase.PRECONDITION,
                    InteractionPhase.PRE,
                    InteractionPhase.POST,
                    InteractionPhase.SETTLEMENT,
                ),
                mod.eventsOf(ModInteractionWire.TURN_INTERACTION_ID).map { it.phase },
            )
            assertEquals(
                "输入触发同样被 REPLACE 影响到 MAIN 跳过（同一管线语义）",
                listOf(
                    InteractionPhase.PRECONDITION,
                    InteractionPhase.PRE,
                    InteractionPhase.POST,
                    InteractionPhase.SETTLEMENT,
                ),
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase },
            )
            assertEquals(
                ModValue.of(42L),
                mod.eventsOf(ModInteractionWire.TURN_INTERACTION_ID)[1].replacedResult,
            )
            assertTrue(
                "回写行必须原样带 interrupted=false replaced=true",
                logs.any { it.contains("interrupted=false replaced=true") },
            )
            assertEquals(0, wire.pendingCount())
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `嵌套派发触达深度 32 后隔离异常且页边界不中断`() {
        val phases = listOf(InteractionPhase.PRECONDITION)
        val mod = RecorderMod(phases)
        var nestedCalls = 0
        mod.onHook = {
            nestedCalls += 1
            mod.api!!.run(InteractionContext("nested", EntityId.NULL, emptyList())) { null }
            InteractionResult.CONTINUE
        }
        val session = attach(phases = phases, mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "deep")
            session.onTurnBoundary()
            assertEquals(
                "深度闸在 32 层拒绝继续 push（回合级派发先跑触闸，输入派发随同拍再触）",
                64,
                nestedCalls,
            )
            assertTrue(
                "回合级触发的深度异常必须由 wire 兜住并隔离上屏",
                logs.any { it.contains("mod.interaction 回合级触发 id=${ModInteractionWire.TURN_INTERACTION_ID} 异常已隔离") },
            )
            assertTrue(
                "回写行必须带深度超限原文",
                logs.any { it.contains("互动嵌套深度超限：32") },
            )
            assertEquals("异常边界返回 false 后不得残留 pending", 0, wire.pendingCount())
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "deep-again")
            session.onTurnBoundary()
            assertEquals(
                "页边界在深度异常后仍须继续兑现：下一拍回合级 32 + 输入 32 再各触闸一次",
                128,
                nestedCalls,
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `非 VM 线程的页边界整段旁路且输入不被吞`() {
        var onVmThread = false
        val mod = RecorderMod()
        val session = attach(mod = mod, onVmThread = { onVmThread })!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "mapped-color:5")
            session.onTurnBoundary()
            assertTrue("非 VM 线程边界必须整段旁路：零派发", mod.events.isEmpty())
            assertTrue(
                "旁路原因必须上屏（drainPending 的 VM 线程闸）",
                logs.any { it.contains("转移边界兑现只在 VM 线程") },
            )
            assertEquals("旁路的边界不得吞掉已采集输入", 1, wire.pendingCount())
            onVmThread = true
            session.onTurnBoundary()
            assertEquals(
                "回到 VM 线程后输入触发必须兑现",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase },
            )
            assertEquals(0, wire.pendingCount())
        } finally {
            onVmThread = true
            session.shutdown()
        }
    }

    @Test
    fun `handler 抛普通异常被管线隔离成 failures 且边界不中断`() {
        val mod = RecorderMod()
        mod.onHook = { hook ->
            if (hook.phase == InteractionPhase.PRECONDITION) {
                error("boom")
            }
            InteractionResult.CONTINUE
        }
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "mapped-color:5")
            session.onTurnBoundary()
            assertEquals(
                "单 handler 异常不得截断后续相位（回合级与输入两条派发都全相位跑完）",
                2 * ALL_PHASES.size,
                mod.events.size,
            )
            assertTrue(
                "回合级失败必须按包回写隔离日志",
                logs.any {
                    it.contains("mod.interaction 回合级触发 id=${ModInteractionWire.TURN_INTERACTION_ID} 失败已隔离 pkg=$PKG")
                },
            )
            assertTrue(
                "输入失败必须按包回写隔离日志（口径不变）",
                logs.any { it.contains("mod.interaction 页边界输入 source=click count=1 失败已隔离 pkg=$PKG") },
            )
            assertEquals(0, wire.pendingCount())
            session.onTurnBoundary()
            assertEquals(
                "失败的输入证据不得在下一边界重放（回合级每拍独立再派发）",
                3 * ALL_PHASES.size,
                mod.events.size,
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `真 GameSession 的 store 经同一页边界链派发并透传元数据`() {
        val root = tempGameRoot(ALL_PHASES)
        val gameSession = GameSession(
            gameRoot = root,
            program = baseProgram(),
            metadata = BootMetadata(functions = BASE_METAS),
        )
        val mod = RecorderMod()
        val session = attach(mod = mod, store = gameSession.store, root = root)!!
        val wire = session.hostSeams!!.interactionWire
        try {
            assertEquals(
                "真会话 store 必须被装配接受",
                listOf(PKG),
                session.host.installedContexts.map { it.packageId },
            )
            wire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "look")
            assertTrue("页边界之外零派发", mod.events.isEmpty())
            session.onTurnBoundary()
            assertEquals(ALL_PHASES, mod.eventsOf(ModInteractionWire.INTERACTION_ID).map { it.phase })
            assertEquals(
                "回合级触发与输入触发在同一拍并存且可分",
                ALL_PHASES,
                mod.eventsOf(ModInteractionWire.TURN_INTERACTION_ID).map { it.phase },
            )
            assertEquals(
                "command",
                mod.eventsOf(ModInteractionWire.INTERACTION_ID).first().interaction.metadata["source"],
            )
            assertEquals(0, wire.pendingCount())
        } finally {
            session.shutdown()
            gameSession.close()
        }
    }

    @Test
    fun `shutdown 后页边界不再消费已采集输入`() {
        val mod = RecorderMod()
        val session = attach(mod = mod)!!
        val wire = session.hostSeams!!.interactionWire
        session.shutdown()
        wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "mapped-color:5")
        session.onTurnBoundary()
        assertTrue("shutdown 后边界不得再派发", mod.events.isEmpty())
        assertEquals("shutdown 后输入保持未消费（不伪造兑现）", 1, wire.pendingCount())
        session.shutdown()
    }

    @Test
    fun `无 interaction 声明时回合级触发零成本旁路零日志`() {
        val mod = RecorderMod(emptyList())
        val session = attach(phases = emptyList(), mod = mod)!!
        try {
            session.onTurnBoundary()
            session.onTurnBoundary()
            assertTrue(
                "无声明 = 无注册 handler = 零派发（判据 4）",
                mod.events.isEmpty(),
            )
            assertTrue(
                "零日志：不构造上下文、不跑管线、无回合级读数行",
                logs.none { it.contains("mod.interaction") },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `关态零变化不装配会话与交互缝`() {
        val mod = RecorderMod()
        val session = ModRuntimeAssembly.attach(
            gameRoot = tempGameRoot(ALL_PHASES).toFile(),
            store = VariableStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = false,
            logSink = { line -> logs += line },
            modProviders = listOf(PKG to mod),
        )
        assertNull("关态不产出会话（零派发面存在的前提不存在）", session)
    }

    /** 生产接线载体：onLoad 里按 manifest 声明面注册全部相位钩子，逐次记录进 [events]。 */
    private class RecorderMod(
        private val phases: List<InteractionPhase> = ALL_PHASES,
    ) : EraMod {
        val events: MutableList<InteractionHookContext> = mutableListOf()
        var api: InteractionApi? = null
        var onHook: (InteractionHookContext) -> InteractionResult = { InteractionResult.CONTINUE }

        override fun onLoad(context: ModContext) {
            api = context.interactions
            for (phase in phases) {
                context.interactions.registerHook(phase) { hookContext ->
                    events += hookContext
                    onHook(hookContext)
                }
            }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private companion object {
        const val PKG: String = "mod.interwire"
        val ALL_PHASES: List<InteractionPhase> = InteractionPhase.entries.toList()

        val BASE_METAS: List<FuncMeta> = listOf(
            FuncMeta(index = 0, key = "ENTRY"),
            FuncMeta(index = 1, key = "X"),
        )

        fun tempGameRoot(phases: List<InteractionPhase>): Path {
            val root = Files.createTempDirectory("mod-interwire-root")
            val mods = root.resolve("mods").resolve("rt2")
            Files.createDirectories(mods)
            val manifest = ModManifest(
                packageId = PKG,
                interactionHooks = phases.map { InteractionHookDecl(it) },
            )
            Files.write(
                mods.resolve("$PKG.manifest"),
                ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest)),
            )
            return root
        }

        fun baseProgram(): Program {
            val builder = CodeBuilder()
            val entry = builder.declareFunction("ENTRY", builder.here(), arity = 0, slotCount = 2)
            builder.emit(Opcode._CALL, 1, 0, 0, 0)
            builder.emit(Opcode._RET, 0, 0, 0, 1)
            builder.declareFunction("X", builder.here(), arity = 0, slotCount = 1)
            builder.emit(Opcode._PRINT_LITERAL, builder.string("BASE-X"), 1, 0, 2)
            builder.emit(Opcode._SET_INT, 0, builder.constant(111L), 0, 3)
            builder.emit(Opcode._RET, 0, 0, 0, 4)
            return builder.build(entry)
        }
    }
}
