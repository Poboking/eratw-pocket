package com.eratw.port.android

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 P0-LAST 第二条死路（UiApi 真接线）的介质层行为面：
 *
 * 1. [ModUiSurface.refresh] 把已装 context 的 `registerInteraction` 键拍成**不可变快照**
 *    （VM 线程建、主线程只读）；
 * 2. [ModUiSurface.click] 只把动作递进 `submit`（生产上 = 会话 VmBoundaryScheduler，
 *    页边界 drain 兑现）——**不在主线程执行作者 handler**；
 * 3. 兑现后 executed/handled 与失败异常都回传到 [ModUiClickOutcome]；
 * 4. 已撤销的键拒绝派发；handler 抛异常被隔离不外泄；
 * 5. 可见性过滤（DX-F2）：DX-F1 让 `registeredKeys` 与 `uiClick` 现求值过滤，而表面恰好走
 *    这两个口 ⇒ 过滤语义对渲染与点击「自动生效」。本类把它锁成事实：不可见项不进渲染
 *    快照、不在快照则点击拒绝；快照后翻脸的项兑现期现求值不命中、回调不触发。
 */
class ModUiSurfaceTest {

    private val logs = mutableListOf<String>()
    private val uiHits = mutableListOf<String>()

    private fun newKernel(): EntityKernel = EntityKernel(
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

    private fun newSnapshot(packageId: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        registry.addPackage(
            ModDeclarationAdapter.toDescriptor(
                ModManifest(packageId = packageId),
            ),
        )
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private class UiMod(
        private val hits: MutableList<String>,
        private val keys: List<String>,
        private val throwOnClick: Boolean = false,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            for (key in keys) {
                context.ui.registerInteraction(key) {
                    hits += key
                    if (throwOnClick) error("$key 故意在 ui handler 抛异常")
                }
            }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /**
     * DX-F2 锁定用：注册「恒不可见、恒可见、缺省谓词」三档交互项，覆盖渲染清单过滤的各分支。
     * 谓词恒定（不随状态翻脸）；翻脸场景由 [TogglableUiMod] 负责。
     */
    private class VisibilityUiMod(
        private val hits: MutableList<String>,
        private val hidden: List<String>,
        private val shown: List<String>,
        private val defaulted: List<String>,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            for (key in hidden) {
                context.ui.registerInteraction(key, visible = { false }) { hits += key }
            }
            for (key in shown) {
                context.ui.registerInteraction(key, visible = { true }) { hits += key }
            }
            for (key in defaulted) {
                context.ui.registerInteraction(key) { hits += key }
            }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    /**
     * DX-F2 锁定用：单项交互的可见性随 [visibleNow] 现场翻脸——快照期可见、兑现期可切不可见，
     * 用来锁「点击兑现期现求值」这一面。
     */
    private class TogglableUiMod(
        private val hits: MutableList<String>,
        private val visibleNow: () -> Boolean,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            context.ui.registerInteraction("BTN_FLIP", visible = visibleNow) { hits += "BTN_FLIP" }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(packageId: String = PKG): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(packageId),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    )

    private fun newSurface(host: ModRuntimeHost): Pair<ModUiSurface, MutableList<() -> Unit>> {
        val queued = mutableListOf<() -> Unit>()
        val surface = ModUiSurface(
            host = host,
            submit = { action -> queued += action },
            logSink = { logs += it },
        )
        surface.refresh()
        return surface to queued
    }

    @Test
    fun `refresh 列举已注册贡献项并排序`() {
        val host = newHost()
        host.install(listOf(PKG to UiMod(uiHits, listOf("BTN_B", "BTN_A"))))
        val (surface, _) = newSurface(host)
        assertEquals(
            listOf(ModUiContribution(PKG, "BTN_A"), ModUiContribution(PKG, "BTN_B")),
            surface.contributions(),
        )
        assertEquals(2, surface.contributionCount())
        host.shutdown()
        surface.refresh()
        assertTrue(surface.contributions().isEmpty())
    }

    @Test
    fun `点击只入队不就地执行且兑现后回传 handled`() {
        val host = newHost()
        host.install(listOf(PKG to UiMod(uiHits, listOf("BTN_A"))))
        val (surface, queued) = newSurface(host)
        val contribution = surface.contributions().single()
        assertTrue(surface.click(contribution))
        assertEquals(1, queued.size)
        assertTrue("主线程不许就地执行作者 handler", uiHits.isEmpty())
        queued.removeAt(0).invoke()
        assertEquals(listOf("BTN_A"), uiHits)
        val outcome = surface.lastOutcome()
        assertNotNull(outcome)
        assertEquals(true, outcome!!.handled)
        assertNull(outcome.failure)
        host.shutdown()
    }

    @Test
    fun `未知或已撤销的贡献项拒绝派发`() {
        val host = newHost()
        host.install(listOf(PKG to UiMod(uiHits, listOf("BTN_A"))))
        val (surface, queued) = newSurface(host)
        assertFalse(surface.click(ModUiContribution(PKG, "BTN_GHOST")))
        assertTrue(queued.isEmpty())
        assertTrue(uiHits.isEmpty())
        host.shutdown()
    }

    @Test
    fun `handler 抛异常隔离并回传失败`() {
        val host = newHost()
        host.install(listOf(PKG to UiMod(uiHits, listOf("BTN_A"), throwOnClick = true)))
        val (surface, queued) = newSurface(host)
        assertTrue(surface.click(surface.contributions().single()))
        queued.removeAt(0).invoke()
        val outcome = surface.lastOutcome()
        assertNotNull(outcome)
        assertFalse(outcome!!.handled)
        assertNotNull(outcome.failure)
        assertTrue("失败摘要应说明是异常隔离", outcome.failure is IllegalStateException)
        assertTrue(logs.any { it.contains("点击失败已隔离") })
        host.shutdown()
    }

    @Test
    fun `渲染清单过滤：不可见项不进快照 可见或缺省在内`() {
        val host = newHost()
        host.install(
            listOf(
                PKG to VisibilityUiMod(
                    uiHits,
                    hidden = listOf("BTN_OFF"),
                    shown = listOf("BTN_ON"),
                    defaulted = listOf("BTN_D"),
                ),
            ),
        )
        val (surface, _) = newSurface(host)
        // 排序口径 (packageId, labelKey)：BTN_D < BTN_ON；BTN_OFF 被谓词滤掉、不进渲染快照
        assertEquals(
            listOf(ModUiContribution(PKG, "BTN_D"), ModUiContribution(PKG, "BTN_ON")),
            surface.contributions(),
        )
        assertEquals(2, surface.contributionCount())
        host.shutdown()
    }

    @Test
    fun `不可见项拒绝派发：不在快照则点击不入队不触发回调`() {
        val host = newHost()
        host.install(
            listOf(
                PKG to VisibilityUiMod(
                    uiHits,
                    hidden = listOf("BTN_OFF"),
                    shown = listOf("BTN_ON"),
                    defaulted = emptyList(),
                ),
            ),
        )
        val (surface, queued) = newSurface(host)
        // 不可见项不在渲染快照里 ⇒ 表面点击按「已撤销」同口径拒绝，不派发、回调不触发
        assertFalse(surface.click(ModUiContribution(PKG, "BTN_OFF")))
        assertTrue(queued.isEmpty())
        assertTrue(uiHits.isEmpty())
        host.shutdown()
    }

    @Test
    fun `点击兑现期现求值：快照后翻脸的项不触发回调`() {
        var visibleNow = true
        val host = newHost()
        host.install(listOf(PKG to TogglableUiMod(uiHits) { visibleNow }))
        val (surface, queued) = newSurface(host)
        val contribution = surface.contributions().single()
        // 不重拍快照直接点：兑现期 uiClick 现求值判不可见 ⇒ 不命中、回调不触发、不报失败
        visibleNow = false
        assertTrue(surface.click(contribution))
        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()
        val outcome = surface.lastOutcome()
        assertNotNull(outcome)
        assertFalse("兑现期现求值不可见 = 不命中", outcome!!.handled)
        assertNull(outcome.failure)
        assertTrue("回调不得触发", uiHits.isEmpty())
        host.shutdown()
    }

    private companion object {
        const val PKG: String = "mod.ui"
    }
}
