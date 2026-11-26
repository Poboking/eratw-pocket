package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页 mod 贡献项入口（task-14）的**接线源码钉**。
 *
 * 与 `ModInteractWireSentinelTest` 同族：只钉调用点/形态存在与**顺序**，行为面在
 * `ModUiSurfaceTest` / `ModInteractionEndToEndTest`。三条关键负控：
 *
 *  1. 设置页**不许读 `SessionVault`** —— 那是 Activity 销毁时的交接槽不是常驻注册表
 *     （`takeoverIfAlive` 取出即清槽，SessionVault.kt:63-68），拿它当缝 = 平时永远不显示；
 *  2. 设置页**不许新增页边界动作**（`refresh` / `drainPending` / `consumeOnBoundary`）——
 *     drain 只许 VM 线程（`VmBoundaryScheduler` fail-closed），兑现唯一在
 *     `ModRuntimeAssembly.kt:1357-1360`；
 *  3. `SettingsScreen` **不许加形参**（语言复用既有 `language: SettingsLanguage`）。
 */
class ModUiSettingsEntryWireTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString()}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** [SettingsScreen] 的函数体源码（从函数头到下一个顶层 `@Composable private fun` 定义前）。 */
    private fun settingsScreenBody(source: String): String {
        val start = source.indexOf("private fun SettingsScreen(")
        assertTrue("必须找得到 SettingsScreen 定义", start >= 0)
        val end = source.indexOf("private fun MaterialPickRow(")
        assertTrue("必须找得到 SettingsScreen 之后的下一个顶层定义", end > start)
        return source.substring(start, end)
    }

    @Test
    fun `浮层渲染在设置页内容根末尾且不加形参`() {
        val source = sourceOf("SettingsActivity.kt")
        val body = settingsScreenBody(source)
        val overlay = body.indexOf("ModUiSettingsOverlay(language = language)")
        val rootOpen = body.indexOf("BoxWithConstraints(")
        val column = body.indexOf("Column(modifier = Modifier.fillMaxSize())")
        val afterRoot = body.indexOf("editing?.let { request ->")
        assertTrue("浮层必须在根容器内渲染", overlay > rootOpen)
        assertTrue("浮层必须排在内容 Column 之后（内容根末尾）", overlay > column)
        assertTrue("浮层必须留在根容器内（根闭合之前）", afterRoot > overlay)
        // 形参口径：语言直接用既有形参，浮层不吃任何新形参、也不直接碰 UI 表面类型。
        assertTrue(body.contains("language: SettingsLanguage,"))
        assertFalse("SettingsScreen 不许新增 ModUiSurface 形参", body.contains("ModUiSurface"))
    }

    @Test
    fun `设置页不读交接槽也不新增页边界动作`() {
        val source = sourceOf("SettingsActivity.kt")
        assertFalse("设置页不许读 SessionVault（交接槽平时为空）", source.contains("SessionVault"))
        assertFalse("设置页不许自己刷 UI 快照", source.contains("uiSurface.refresh"))
        assertFalse("设置页不许自己消费页边界", source.contains("consumeOnBoundary"))
        assertFalse("设置页不许自己 drain 提交队列", source.contains("drainPending"))
        assertFalse("设置页不许直接碰 GameHost", source.contains("modUiSurface"))
    }

    @Test
    fun `读数口形态与认主卸载`() {
        val entry = sourceOf("ModUiSettingsEntry.kt")
        assertTrue(entry.contains("object ModUiSettingsEntry"))
        assertTrue(entry.contains("fun install(owner: Any, provider: (() -> ModUiSurface?)?)"))
        assertTrue(entry.contains("fun uninstall(owner: Any)"))
        assertTrue("卸载必须认主：旧壳无权撅新壳的接线", entry.contains("if (wiredOwner === owner)"))
        assertTrue(entry.contains("fun read(): ModUiSurface? = liveSurface?.invoke()"))
        assertTrue("跨 Activity 读数口必须 @Volatile", entry.contains("@Volatile"))
    }

    @Test
    fun `浮层复用既有三语文案表不新增文案键`() {
        val entry = sourceOf("ModUiSettingsEntry.kt")
        assertTrue(entry.contains("surfaceProvider = { ModUiSettingsEntry.read() }"))
        assertTrue(entry.contains("copy = ModUiCopy.of(language)"))
        assertFalse("不许在本件另造文案表", entry.contains("data class ModUiCopy"))
        assertFalse("不许内联玩家可见文案", entry.contains("Text(\""))
    }

    @Test
    fun `页边界兑现点仍留在 VM 线程侧`() {
        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(assembly.contains("fun onTurnBoundary()"))
        assertTrue(assembly.contains("seams.interactionWire.consumeOnBoundary()"))
        assertTrue(assembly.contains("seams.uiSurface.refresh()"))
    }

    @Test
    fun `MainActivity 既有入口未被破坏`() {
        val main = sourceOf("MainActivity.kt")
        assertTrue("游戏内浮层入口不许被这条 lane 挪走", main.contains("ModUiOverlay("))
        assertTrue(main.contains("surfaceProvider = { host?.modUiSurface() }"))
        assertTrue(
            "游戏内入口仍取壳侧语言",
            main.contains("copy = ModUiCopy.of(UiPrefs.settingsLanguage(this@MainActivity))"),
        )
    }
}
