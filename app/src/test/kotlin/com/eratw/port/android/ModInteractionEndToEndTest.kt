package com.eratw.port.android

import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 P0-LAST 两条死路的**生产缝 e2e**（直打 `ModRuntimeAssembly.attach`，不手搓装配）：
 *
 * 1. 玩家输入证据在页边界被消费一次 → 作者注册的交互阶段钩子真的执行（每边界一次，不重复）；
 * 2. `UiApi.registerInteraction` 的项经会话表面列举出来；点击只入队，页边界 `drainPending`
 *    兑现后 handler 执行、结果经 `ModUiClickOutcome` 回传；撤销后不再出现。
 *
 * 与 `ModArbitrationRoundWireTest` 同一 fixture 形状（临时游戏根 + 磁盘 manifest + 生产 attach）。
 */
class ModInteractionEndToEndTest {

    private val logs = mutableListOf<String>()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-interact-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = com.eratw.port.core.patch.ManifestCodec.encode(
            com.eratw.port.core.mod.ModDeclarationAdapter.toDescriptor(manifest),
        )
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = store,
        nativeRegistry = com.eratw.port.core.fn.NativeMethodRegistry(),
        onVmThread = { true },
        enabled = true,
        logSink = { line -> logs += line },
        modProviders = providers,
    )

    /** 生产接线载体：onLoad 里注册一个交互 PRE 钩子 + 一个 ui 交互项。 */
    private class WireMod : EraMod {
        val interactionHits: MutableList<String> = mutableListOf()
        val uiHits: MutableList<String> = mutableListOf()

        override fun onLoad(context: ModContext) {
            context.interactions.registerHook(InteractionPhase.PRE) { hookContext ->
                // 只统计输入驱动的派发（host.input）：ModDX-4 M4 起回合级触发（host.turn）
                // 每拍也派发 PRE，回合级面由 ModInteractionWireEndToEndTest 覆盖，不混账。
                if (hookContext.interaction.interactionId == ModInteractionWire.INTERACTION_ID) {
                    interactionHits += hookContext.interaction.metadata["source"] ?: "none"
                }
                InteractionResult.CONTINUE
            }
            context.ui.registerInteraction(UI_KEY) { uiHits += UI_KEY }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit

        companion object {
            const val UI_KEY: String = "BTN_A"
        }
    }

    private fun newRoot(): Path {
        val root = tempGameRoot()
        writeManifest(
            root,
            "$PKG.manifest",
            ModManifest(
                packageId = PKG,
                interactionHooks = listOf(InteractionHookDecl(InteractionPhase.PRE)),
            ),
        )
        return root
    }

    @Test
    fun `页边界消费玩家输入且同一笔证据只触发一次`() {
        val mod = WireMod()
        val session = attach(newRoot(), VariableStore(), listOf(PKG to mod))
        assertNotNull(session)
        val seams = session!!.hostSeams
        assertNotNull("开态必须装配交互触发点 + UI 表面", seams)
        seams!!.interactionWire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "look")
        session.onTurnBoundary()
        assertEquals(listOf("command"), mod.interactionHits)
        session.onTurnBoundary()
        assertEquals("无新输入时边界不得重复触发", listOf("command"), mod.interactionHits)
        session.shutdown()
    }

    @Test
    fun `UiApi 注册项真实上屏且点击经页边界兑现并回传结果`() {
        val mod = WireMod()
        val session = attach(newRoot(), VariableStore(), listOf(PKG to mod))
        assertNotNull(session)
        val surface = session!!.hostSeams!!.uiSurface
        assertEquals(listOf(ModUiContribution(PKG, WireMod.UI_KEY)), surface.contributions())
        assertTrue(surface.click(ModUiContribution(PKG, WireMod.UI_KEY)))
        assertTrue("点击只入队：页边界兑现前作者 handler 不得执行", mod.uiHits.isEmpty())
        session.onTurnBoundary()
        assertEquals(listOf(WireMod.UI_KEY), mod.uiHits)
        val outcome = surface.lastOutcome()
        assertNotNull(outcome)
        assertTrue(outcome!!.handled)
        assertFalse(outcome.failure != null)
        session.shutdown()
        surface.refresh()
        assertTrue("撤销后贡献项必须消失", surface.contributions().isEmpty())
    }

    private companion object {
        const val PKG: String = "mod.wire"
    }
}
