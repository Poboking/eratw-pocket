package com.eratw.port.android

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModResourceStore
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.RuntimeScheduler
import com.eratw.port.modapi.modUiImage
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 图片元素**完整竖切**的介质层行为面（元素来源缝 → 快照 → VM 线程读字节 → 加载器 → 降级）：
 *
 *  1. [ModUiSurface.images] 把来源缝给的元素拍成不可变快照并排序；**缺省来源缝 = 空表**
 *     （既有装配行为零变化）；
 *  2. [ModUiSurface.requestImageBytes] 主线程只入队、不就地读盘（`ModResourceApi` 的线程
 *     契约是「只在 VM 线程调用」），VM 线程兑现后回调字节；
 *  3. 缺资源 / 非法名（资源面抛 IAE）/ 包不在会话 / 资源面未装配 / 投递队列失效 —— 全部
 *     收敛成 null + WARN，绝不把异常抛回 Compose；
 *  4. 来源缝自身抛错（装配侧实现有 bug）只降级为空快照，不影响文本贡献项。
 */
class ModUiImageSurfaceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val logs = mutableListOf<String>()

    @Test
    fun `缺省来源缝零行为变化且元素快照按命名排序`() {
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val (plain, _) = newSurface(host)
        assertTrue("缺省图片来源缝 = 空表", plain.images().isEmpty())
        val (surface, _) = newSurface(host) { listOf(modUiImage("images/b.png"), modUiImage("images/a.png")) }
        surface.refresh()
        assertEquals(
            listOf("images/a.png", "images/b.png"),
            surface.images().map { it.element.resourceName },
        )
        assertTrue("文本贡献项不受图片元素影响", surface.contributions().isEmpty())
        host.shutdown()
    }

    @Test
    fun `读取只在 VM 线程兑现并端到端解码成功`() {
        val bytes = pngBytes()
        writeResource("images/bg01.png", bytes)
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val (surface, queued) = newSurface(host) { listOf(IMAGE) }
        surface.refresh()
        var delivered: ByteArray? = null
        surface.requestImageBytes(PKG, "images/bg01.png") { delivered = it }
        assertEquals(1, queued.size)
        assertNull("主线程不许就地读资源", delivered)
        queued.removeAt(0).invoke()
        assertArrayEquals("字节应与包资源目录里的文件逐位一致", bytes, delivered)
        val loader = ModUiImageLoader(decoder = ModUiImageDecoder { _, _ -> "bitmap" }, logSink = { logs += it })
        val outcome = loader.decode(IMAGE, delivered)
        assertTrue("读到合法 PNG 应解码成功，实际：$outcome", outcome is ModUiImageOutcome.Ready)
        host.shutdown()
    }

    @Test
    fun `缺资源与非法名降级为 null 且记 WARN`() {
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val (surface, queued) = newSurface(host)
        var missing: ByteArray? = byteArrayOf(1)
        surface.requestImageBytes(PKG, "images/missing.png") { missing = it }
        queued.removeAt(0).invoke()
        assertNull("缺文件必须降级为 null", missing)
        assertTrue("缺字节应留 WARN 痕迹", logs.any { it.contains("图片缺字节已降级") })
        var illegal: ByteArray? = byteArrayOf(1)
        surface.requestImageBytes(PKG, "../escape.png") { illegal = it }
        queued.removeAt(0).invoke()
        assertNull("非法资源名（资源面抛 IAE）必须被兜住", illegal)
        assertTrue("非法名应留 WARN 痕迹", logs.any { it.contains("图片读取失败已降级") })
        host.shutdown()
    }

    @Test
    fun `包不在会话与资源面未装配都只降级不抛`() {
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val (surface, queued) = newSurface(host)
        var ghost: ByteArray? = byteArrayOf(1)
        surface.requestImageBytes("ghost.pkg", "images/bg01.png") { ghost = it }
        queued.removeAt(0).invoke()
        assertNull("包不在会话里必须降级为 null", ghost)
        // 资源面未装配（host.resources 为 null）⇒ FailClosedResourceApi 抛 UOE，同样兜住。
        val bareHost = newBareHost()
        bareHost.install(listOf(PKG to NoopMod))
        val (bareSurface, bareQueued) = newSurface(bareHost)
        var bare: ByteArray? = byteArrayOf(1)
        bareSurface.requestImageBytes(PKG, "images/bg01.png") { bare = it }
        bareQueued.removeAt(0).invoke()
        assertNull("资源面未装配必须降级为 null", bare)
        assertTrue(logs.any { it.contains("资源面未装配") || it.contains("UnsupportedOperationException") })
        host.shutdown()
        bareHost.shutdown()
    }

    @Test
    fun `投递队列失效时同步回调 null 且不外抛`() {
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val surface = ModUiSurface(
            host = host,
            submit = { throw IllegalStateException("会话队列已失效") },
            logSink = { logs += it },
        )
        var calls = 0
        var delivered: ByteArray? = byteArrayOf(1)
        surface.requestImageBytes(PKG, "images/bg01.png") {
            calls += 1
            delivered = it
        }
        assertEquals("投递失败也必须回调一次（渲染侧要能停止等待）", 1, calls)
        assertNull(delivered)
        assertTrue(logs.any { it.contains("图片读取投递失败已降级") })
        host.shutdown()
    }

    @Test
    fun `元素来源缝抛错只降级为空快照`() {
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val (surface, _) = newSurface(host) { error("装配侧来源缝有 bug") }
        surface.refresh()
        assertTrue("来源缝抛错不许把快照弄挂", surface.images().isEmpty())
        assertTrue(logs.any { it.contains("图片元素快照失败已降级为空表") })
        host.shutdown()
    }

    @Test
    fun `元素与包身份随快照携带`() {
        val host = newHost()
        host.install(listOf(PKG to NoopMod))
        val (surface, _) = newSurface(host) { listOf(IMAGE) }
        surface.refresh()
        val contribution = surface.images().single()
        assertEquals(PKG, contribution.packageId)
        assertEquals(IMAGE, contribution.element)
        assertNotNull(contribution.element.altText)
        host.shutdown()
    }

    private fun newHost(): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(PKG),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    ).apply {
        resources = ModResourceStore(rt2Dir = temp.root, logSink = { logs += it })
    }

    private fun newBareHost(): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(PKG),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = { logs += it },
        runtime = FakeRuntime(),
    )

    private fun newSurface(
        host: ModRuntimeHost,
        imageElements: (String) -> List<ModUiElement.Image> = { emptyList() },
    ): Pair<ModUiSurface, MutableList<() -> Unit>> {
        val queued = mutableListOf<() -> Unit>()
        val surface = ModUiSurface(
            host = host,
            submit = { action -> queued += action },
            logSink = { logs += it },
            imageElements = imageElements,
        )
        surface.refresh()
        return surface to queued
    }

    private fun writeResource(name: String, bytes: ByteArray) {
        val file = File(temp.root, "$PKG.assets/$name")
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

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
        registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
        return registry.freeze()
    }

    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24)

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) {
            action()
        }
    }

    private object NoopMod : EraMod {
        override fun onLoad(context: ModContext) = Unit

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private companion object {
        const val PKG: String = "mod.img"

        val IMAGE: ModUiElement.Image = modUiImage("images/bg01.png", altText = "替代文本", maxSidePx = 256)
    }
}
