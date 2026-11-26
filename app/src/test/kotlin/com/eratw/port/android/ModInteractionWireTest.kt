package com.eratw.port.android

import com.eratw.port.core.diag.RuntimeDiagnostics
import com.eratw.port.core.mod.InteractionApiAdapter
import com.eratw.port.core.mod.InteractionPipeline
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 P0-LAST 第一条死路（app 侧生产触发点）：玩家输入证据 → 页边界一次消费 → 交互管线。
 *
 * 判据（对应交付报告）：
 * 1. 无输入证据时边界**零成本旁路**（不触发 `pipeline.run`）；
 * 2. 一笔证据只消费一次，`metadata` 如实带 source/text/count；
 * 3. 多笔输入合并成同一拍一次评估（count = 笔数）；
 * 4. handler 抛异常按包隔离进 `failures` → 日志留痕，且**绝不把异常抛回页边界**。
 */
class ModInteractionWireTest {

    private val hits = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private var seenMetadata: Map<String, String> = emptyMap()
    private var seenInteractionId: String? = null

    private fun newPipeline(vararg packages: String): InteractionPipeline {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(
                        packageId = pkg,
                        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
                    ),
                ),
            )
        }
        return InteractionPipeline.of(registry.freeze().interactionHookDeclarations)
    }

    private fun register(
        pipeline: InteractionPipeline,
        packageId: String,
        throws: Boolean = false,
    ) {
        InteractionApiAdapter(pipeline, packageId)
            .registerHook(InteractionPhase.PRE) { context: InteractionHookContext ->
            hits += packageId
            seenMetadata = context.interaction.metadata
            seenInteractionId = context.interaction.interactionId
            if (throws) error("$packageId 故意在交互 handler 抛异常")
            InteractionResult.CONTINUE
        }
    }

    private fun newWire(pipeline: InteractionPipeline): ModInteractionWire =
        ModInteractionWire(pipeline, diagnostics = RuntimeDiagnostics(), logSink = { logs += it })

    @Test
    fun `无输入证据时页边界零成本旁路`() {
        val pipeline = newPipeline("mod.a")
        register(pipeline, "mod.a")
        val wire = newWire(pipeline)
        assertEquals(0, wire.pendingCount())
        assertFalse(wire.consumeOnBoundary())
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `一笔证据只消费一次且 metadata 带来源与文本`() {
        val pipeline = newPipeline("mod.a")
        register(pipeline, "mod.a")
        val wire = newWire(pipeline)
        wire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "look")
        assertEquals(1, wire.pendingCount())
        assertTrue(wire.consumeOnBoundary())
        assertEquals(listOf("mod.a"), hits)
        assertEquals("command", seenMetadata["source"])
        assertEquals("look", seenMetadata["text"])
        assertEquals("1", seenMetadata["count"])
        assertEquals(0, wire.pendingCount())
        assertFalse(wire.consumeOnBoundary())
        assertEquals(listOf("mod.a"), hits)
    }

    @Test
    fun `多笔输入合并成同一拍一次评估`() {
        val pipeline = newPipeline("mod.a")
        register(pipeline, "mod.a")
        val wire = newWire(pipeline)
        wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "first")
        wire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "second")
        assertTrue(wire.consumeOnBoundary())
        assertEquals(listOf("mod.a"), hits)
        assertEquals("2", seenMetadata["count"])
        assertEquals("second", seenMetadata["text"])
    }

    @Test
    fun `handler 抛异常失败隔离且不抛回页边界`() {
        val pipeline = newPipeline("mod.a")
        register(pipeline, "mod.a", throws = true)
        val wire = newWire(pipeline)
        wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "boom")
        assertTrue("失败隔离后仍算消费成功（边界继续）", wire.consumeOnBoundary())
        assertEquals(listOf("mod.a"), hits)
        assertTrue(
            "失败必须留痕：${logs.joinToString(" | ")}",
            logs.any { it.contains("失败已隔离") },
        )
    }

    @Test
    fun `空文本点击也带 metadata 且不崩`() {
        val pipeline = newPipeline("mod.a")
        register(pipeline, "mod.a")
        val wire = newWire(pipeline)
        wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "")
        assertTrue(wire.consumeOnBoundary())
        assertNotNull(seenMetadata["source"])
        assertEquals("", seenMetadata["text"])
    }

    @Test
    fun `回合级触发无注册 handler 时先闸后建零开销旁路`() {
        val pipeline = newPipeline("mod.a") // 声明了 PRE 但未 registerHook：handlers 为空
        val wire = newWire(pipeline)
        var contextBuilt = false
        val fired = wire.runTurnFrame {
            contextBuilt = true
            InteractionContext(
                interactionId = ModInteractionWire.TURN_INTERACTION_ID,
                actor = EntityId.NULL,
                targets = emptyList(),
            )
        }
        assertFalse("无注册 handler 必须零成本旁路", fired)
        assertFalse("先闸后建：provider 不得被调用（witness 读数零发生）", contextBuilt)
        assertTrue("旁路必须零日志", logs.isEmpty())
    }

    @Test
    fun `回合级触发派发已注册相位且透传 witness metadata`() {
        val pipeline = newPipeline("mod.a")
        register(pipeline, "mod.a")
        val wire = newWire(pipeline)
        val fired = wire.runTurnFrame {
            InteractionContext(
                interactionId = ModInteractionWire.TURN_INTERACTION_ID,
                actor = EntityId.NULL,
                targets = emptyList(),
                primaryTarget = null,
                metadata = mapOf(
                    ModInteractionWire.TARGET_KEY to "0",
                    ModInteractionWire.TARGET_POS_KEY to "1",
                    ModInteractionWire.WITNESS_KEY to "1",
                    ModInteractionWire.WITNESS_COUNT_KEY to "1",
                ),
            )
        }
        assertTrue("有注册 handler 必须派发", fired)
        assertEquals(listOf("mod.a"), hits)
        assertEquals(ModInteractionWire.TURN_INTERACTION_ID, seenInteractionId)
        assertEquals("0", seenMetadata[ModInteractionWire.TARGET_KEY])
        assertEquals("1", seenMetadata[ModInteractionWire.WITNESS_KEY])
        assertEquals("回合级派发必须留一行读数", true, logs.any { it.contains("回合级触发") })
    }
}
