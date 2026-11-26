package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 P0-LAST 两条死路的**接线源码钉**（防「函数写好了但没人调用」的假接线）。
 *
 * 与 `ModLifecycleWireSentinelTest` 同族：只钉调用点存在与**顺序**，行为面在
 * `ModInteractionWireTest` / `ModUiSurfaceTest` / `ModInteractionEndToEndTest`。
 */
class ModInteractWireSentinelTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString()}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `GameHost 在输入漏斗采集证据且只在 attach 之后接线`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(host.contains("fun modUiSurface(): ModUiSurface?"))
        assertTrue(host.contains("hostSeams?.interactionWire?.notePlayerInput("))
        val attach = host.indexOf("modRuntimeSession = ModRuntimeAssembly.attach(")
        val note = host.indexOf("hostSeams?.interactionWire?.notePlayerInput(")
        assertTrue("attach 调用点必须在源码里", attach >= 0)
        assertTrue("输入采集点必须排在 attach 之后（会话未建时无 seam 可写）", note > attach)
    }

    @Test
    fun `会话页边界只消费一次输入并重拍 UI 快照`() {
        val session = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(session.contains("internal var hostSeams: ModHostSeams? = null"))
        assertTrue(session.contains("seams.interactionWire.consumeOnBoundary()"))
        assertTrue(session.contains("seams.uiSurface.refresh()"))
        assertTrue(
            "attach 必须在会话装配期把交互缝接上",
            session.contains("bindHostSeams(session, host, scheduler, logSink, diagnostics)"),
        )
        val wiring = sourceOf("ModHostSeamWiring.kt")
        assertTrue("UI 派发必须经会话调度器入队", wiring.contains("scheduler.submit(action)"))
        assertTrue(wiring.contains("session.hostSeams = ModHostSeams("))
        assertTrue(wiring.contains("ModInteractionWire("))
        assertTrue(wiring.contains("ModUiSurface("))
    }

    @Test
    fun `UiApi 贡献项在独立浮层里渲染且点击经 submit 派发`() {
        val surface = sourceOf("ModUiSurface.kt")
        assertTrue(surface.contains("fun click(contribution: ModUiContribution): Boolean"))
        assertTrue(surface.contains("submit { dispatch(contribution) }"))
        assertTrue(surface.contains("host.installedContexts"))
        val main = sourceOf("MainActivity.kt")
        assertTrue(main.contains("ModUiOverlay("))
        assertTrue(main.contains("host?.modUiSurface()"))
    }

    @Test
    fun `文案表取语言唯一路且不内联播放器文案`() {
        val copy = sourceOf("ModUiCopy.kt")
        assertTrue(copy.contains("fun of(lang: SettingsLanguage): ModUiCopy"))
        assertEquals("语言分支必须覆盖三档", 3, Regex("SettingsLanguage\\.(ZH|EN|JA) ->").findAll(copy).count())
    }
}
