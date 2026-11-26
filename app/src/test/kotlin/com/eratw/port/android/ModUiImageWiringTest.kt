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
import com.eratw.port.core.mod.uiElementsOf
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.Registration
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.modUiImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 P1 资源注入第二刀（作者声明 → 宿主快照 → 浮层表面）的装配层行为面：
 *
 * 1. `UiApi.registerImage` 的声明经宿主 `ModRuntimeHost` 的只读查询（[uiElementsOf]）可见、
 *    字段保真、跨包隔离；
 * 2. 撤销 / onLoad 失败回滚 / shutdown 之后查询立即为空（与生产登记表同一份 Registration，
 *    没有第二种真相）；
 * 3. 非法声明在**声明期**（onLoad/onEnable 内）就被 [ModUiElement.Image] 构造校验拒掉，
 *    该 mod 进失败报告、查询为空；
 * 4. 真装配探针：[ModUiSurface] 接上 `host.uiElementsOf` 之后 `images()` 真拿到元素；缺省
 *    （未接线）表面拿不到 —— 钉住「元素不是从环境里某处漏出来的」。
 */
class ModUiImageWiringTest {

    private val logs = mutableListOf<String>()

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

    private fun newSnapshot(vararg packageIds: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (packageId in packageIds) {
            registry.addPackage(
                ModDeclarationAdapter.toDescriptor(
                    ModManifest(packageId = packageId),
                ),
            )
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    /** 作者 mod：onLoad 里执行声明体（声明失败 = onLoad 失败，走宿主错误围栏）。 */
    private class DeclaringMod(private val body: (ModContext) -> Unit) : EraMod {
        override fun onLoad(context: ModContext) = body(context)

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun newHost(vararg packageIds: String): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(*packageIds),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    )

    @Test
    fun `声明图片后宿主只读查询可见且字段保真`() {
        val host = newHost(PKG)
        val report = host.install(listOf(PKG to DeclaringMod { context -> declareImage(context, IMAGE) }))
        assertTrue(report.single().active)
        assertEquals(listOf(IMAGE), host.uiElementsOf(PKG))
        host.shutdown()
    }

    @Test
    fun `跨包隔离且互不可见`() {
        val host = newHost(PKG, PEER)
        val other = modUiImage("images/peer.png", altText = "对面包的图")
        host.install(
            listOf(
                PKG to DeclaringMod { context -> declareImage(context, IMAGE) },
                PEER to DeclaringMod { context -> declareImage(context, other) },
            ),
        )
        assertEquals(listOf(IMAGE), host.uiElementsOf(PKG))
        assertEquals(listOf(other), host.uiElementsOf(PEER))
        host.shutdown()
    }

    @Test
    fun `非法资源名在声明期当场拒绝且该 mod 不启用`() {
        val host = newHost(PKG)
        val report = host.install(
            listOf(PKG to DeclaringMod { context -> declareImage(context, "../escape.png") }),
        )
        val state = report.single()
        assertFalse(state.active)
        assertTrue(state.failure is IllegalArgumentException)
        assertTrue(host.uiElementsOf(PKG).isEmpty())
        host.shutdown()
    }

    @Test
    fun `越界最大边长在声明期当场拒绝`() {
        val host = newHost(PKG)
        val report = host.install(
            listOf(PKG to DeclaringMod { context -> declareImage(context, imageWithMaxSide(0)) }),
        )
        assertFalse(report.single().active)
        assertTrue(report.single().failure is IllegalArgumentException)
        assertTrue(host.uiElementsOf(PKG).isEmpty())
        host.shutdown()
    }

    @Test
    fun `撤销注册后查询立即为空`() {
        val registrations = mutableListOf<Registration>()
        val host = newHost(PKG)
        host.install(
            listOf(
                PKG to DeclaringMod { context ->
                    registrations += context.ui.registerImage(
                        resourceName = IMAGE.resourceName,
                        altText = IMAGE.altText,
                    )
                },
            ),
        )
        assertEquals(listOf(IMAGE.resourceName), host.uiElementsOf(PKG).map { it.resourceName })
        registrations.single().unregister()
        assertTrue(host.uiElementsOf(PKG).isEmpty())
        host.shutdown()
    }

    @Test
    fun `onLoad 失败回滚清空已声明图片`() {
        val host = newHost(PKG)
        val report = host.install(
            listOf(
                PKG to DeclaringMod { context ->
                    declareImage(context, IMAGE)
                    error("声明后故意让 onLoad 失败，验证注册项回滚")
                },
            ),
        )
        val state = report.single()
        assertFalse(state.active)
        assertNotNull(state.failure)
        assertTrue(host.uiElementsOf(PKG).isEmpty())
        assertTrue(logs.any { it.contains("仅回滚本 mod 已注册项") })
        host.shutdown()
    }

    @Test
    fun `未安装包查询空表且不抛错不制造 context`() {
        val host = newHost(PKG)
        assertTrue(host.uiElementsOf(GHOST).isEmpty())
        assertTrue(host.uiElementsOf(PKG).isEmpty())
        assertTrue("查询面不得凭空制造 context", host.installedContexts.isEmpty())
    }

    @Test
    fun `shutdown 撤销注册后查询为空`() {
        val host = newHost(PKG)
        host.install(listOf(PKG to DeclaringMod { context -> declareImage(context, IMAGE) }))
        assertEquals(1, host.uiElementsOf(PKG).size)
        host.shutdown()
        assertTrue(host.uiElementsOf(PKG).isEmpty())
    }

    @Test
    fun `同资源重复声明后者覆盖且旧撤销不误删新元素`() {
        val first = mutableListOf<Registration>()
        val second = mutableListOf<Registration>()
        val host = newHost(PKG)
        host.install(
            listOf(
                PKG to DeclaringMod { context ->
                    first += context.ui.registerImage(resourceName = IMAGE.resourceName, altText = "旧")
                    second += context.ui.registerImage(resourceName = IMAGE.resourceName, altText = "新")
                },
            ),
        )
        assertEquals(listOf("新"), host.uiElementsOf(PKG).map { it.altText })
        first.single().unregister()
        assertEquals("旧句柄撤销不得误删后声明的新元素", listOf("新"), host.uiElementsOf(PKG).map { it.altText })
        second.single().unregister()
        assertTrue(host.uiElementsOf(PKG).isEmpty())
        host.shutdown()
    }

    @Test
    fun `真装配探针 接线后 images 拿到元素而缺省表面拿不到`() {
        val host = newHost(PKG)
        host.install(listOf(PKG to DeclaringMod { context -> declareImage(context, IMAGE) }))
        val unwired = ModUiSurface(host = host, submit = { it() }, logSink = { logs += it })
        unwired.refresh()
        assertTrue("未接线时表面不得凭空看到元素", unwired.images().isEmpty())
        val wired = ModUiSurface(
            host = host,
            submit = { it() },
            logSink = { logs += it },
            imageElements = imageElementsFrom(host),
        )
        wired.refresh()
        assertEquals(listOf(ModUiImageContribution(PKG, IMAGE)), wired.images())
        assertTrue(wired.contributions().isEmpty())
        host.shutdown()
    }

    private fun declareImage(context: ModContext, element: ModUiElement.Image) {
        context.ui.registerImage(
            resourceName = element.resourceName,
            altText = element.altText,
            maxSidePx = element.maxSidePx,
        )
    }

    private fun declareImage(context: ModContext, rawName: String) {
        context.ui.registerImage(rawName)
    }

    private fun imageWithMaxSide(maxSidePx: Int): ModUiElement.Image =
        modUiImage(IMAGE.resourceName, altText = IMAGE.altText, maxSidePx = maxSidePx)

    private companion object {
        const val PKG: String = "mod.ui"
        const val PEER: String = "mod.peer"
        const val GHOST: String = "mod.ghost"
        const val ALT_TEXT: String = "替代文本"
        val IMAGE: ModUiElement.Image = modUiImage("images/bg01.png", altText = ALT_TEXT, maxSidePx = 256)
    }
}
