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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RT2 多语言文案的**生产装配探针**：浮层标签优先显示 mod 自带文字，没声明才回落宿主技术标识。
 *
 * 关键口径（为什么不自己写一份解析逻辑来测）：用例直接调生产装配的同一个函数
 * [modUiStringResolver]（`bindHostSeams` 造 [ModUiSurface] 时注入的就是它）+ 同一个
 * `ModRuntimeHost` 会话表，所以「台架绿 = 真机绿」不是靠复述实现换来的。
 *
 * 另外用源码哨兵钉住**渲染点真的走了这条缝**（否则解析器再对，屏幕上还是技术标识）。
 */
class ModUiStringResolverTest {

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
        registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = packageId)))
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true
        override fun submit(action: () -> Unit) = action()
    }

    /** 在 onLoad 里声明自带三语文案 + 注册一个交互项的测试 mod。 */
    private class StringsMod(private val hits: MutableList<String>) : EraMod {
        override fun onLoad(context: ModContext) {
            context.strings.declare(
                key = "btn.hello",
                zh = "打个招呼",
                en = "Say hello",
                ja = "あいさつする",
            )
            context.ui.registerInteraction("btn.hello") { hits += "btn.hello" }
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private fun newHost(): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot(PKG),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = {},
        runtime = FakeRuntime(),
    )

    private fun newSurface(host: ModRuntimeHost): ModUiSurface {
        val surface = ModUiSurface(
            host = host,
            submit = { action -> action() },
            logSink = {},
            stringResolver = modUiStringResolver(host),
        )
        surface.refresh()
        return surface
    }

    @Test
    fun `浮层标签优先显示mod自带文案`() {
        val host = newHost()
        host.install(listOf(PKG to StringsMod(uiHits)))
        val surface = newSurface(host)
        assertEquals("前置：作者注册了一个贡献项", 1, surface.contributionCount())
        assertEquals("打个招呼", surface.labelOf(PKG, "btn.hello", "zh"))
        assertEquals("Say hello", surface.labelOf(PKG, "btn.hello", "en"))
        assertEquals("あいさつする", surface.labelOf(PKG, "btn.hello", "ja"))
        host.shutdown()
    }

    @Test
    fun `未声明文案的键回落宿主技术标识`() {
        val host = newHost()
        host.install(listOf(PKG to StringsMod(uiHits)))
        val surface = newSurface(host)
        assertEquals("$PKG / btn.ghost", surface.labelOf(PKG, "btn.ghost", "zh"))
        assertTrue("回落仍可见键名", surface.labelOf(PKG, "btn.ghost", "zh").contains("btn.ghost"))
        host.shutdown()
    }

    @Test
    fun `缺省解析缝保持既有呈现逐位不变`() {
        val host = newHost()
        host.install(listOf(PKG to StringsMod(uiHits)))
        // 不传 stringResolver = 加这一刀之前的所有装配点（既有测试 / 旧宿主路径）。
        val legacy = ModUiSurface(host = host, submit = { action -> action() }, logSink = {})
        legacy.refresh()
        assertEquals("$PKG / btn.hello", legacy.labelOf(PKG, "btn.hello", "zh"))
        host.shutdown()
    }

    @Test
    fun `浮层渲染点走的是这条解析缝`() {
        val source = sourceOf("ModUiSurface.kt")
        assertTrue(
            "贡献项按钮标签必须经 labelOf 解析（否则解析器再对、屏上仍是技术标识）",
            source.contains("label = surface.labelOf(contribution.packageId, contribution.labelKey, copy.lang.stored)"),
        )
        assertTrue(
            "回显行同样经 labelOf（作者文案对成功/失败/未执行三态一致）",
            source.contains("val item = surface.labelOf(last.packageId, last.labelKey, copy.lang.stored)"),
        )
        assertTrue(
            "语言码取自 ModUiCopy.lang.stored（壳层唯一读取单路），不在渲染件里自判 Locale",
            source.contains("copy.lang.stored"),
        )
    }

    /** 读主源码文本（口径照 `ModUiSettingsEntryWireTest.sourceOf`）。 */
    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        for (candidate in candidates) {
            if (candidate.isFile) return candidate.readText().replace("\r\n", "\n")
        }
        throw AssertionError("找不到源码文件：$relative（候选：${candidates.joinToString { it.path }}）")
    }

    private companion object {
        const val PKG: String = "mod.strings"
    }
}
