package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页 Mod UI 浮层的**宿主接线**哨兵（组长接线面，2026-11-17）。
 *
 * 为什么单独一份：`ModUiSettingsEntryWireTest` 钉的是设置页侧（渲染点、零新增形参、不读交接槽）；
 * 本用例钉的是壳层侧的三行认主装挂 —— 少了它们，设置页浮层会**静默恒不显示**（`read()` 返回
 * null，`ModUiOverlay` 整块 return，不崩也不报），只有文本哨兵能在 CI 里提前抓住。
 *
 * 三条接线（双路径 + 一条卸载）：
 * - 接回路径：`host` 从交接槽取回后装挂（否则从后台接回后再进设置页看不到浮层）；
 * - 冷启路径：装载完成后装挂（正常启动）；
 * - 销毁：`onDestroy` 认主卸载（双壳交接窗口下旧壳不得撅空新壳的接线）。
 */
class ModUiSettingsEntryHostWireTest {
    /** 以「MainActivity.kt 真实文本」为准做形状断言（与 ModInteractWireSentinelTest 同口径）。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: error("未找到源码 $name（cwd=${File(".").absolutePath}）")
    }

    /** 目标串在源码里的全部起始下标（`String.indexOf` 只给首个，形状闸需要逐个对照上下文）。 */
    private fun String.indicesOf(needle: String): List<Int> {
        val result = mutableListOf<Int>()
        var cursor = 0
        while (true) {
            val next = indexOf(needle, cursor)
            if (next < 0) return result
            result += next
            cursor = next + needle.length
        }
    }

    @Test
    fun `两条路径各装一次且都挂在会话通道之后`() {
        val source = sourceOf("MainActivity.kt")
        val installLine = "ModUiSettingsEntry.install(this) { host?.modUiSurface() }"
        val indices = source.indicesOf(installLine)
        assertEquals("装挂点应恰好 2 处（接回 + 冷启）", 2, indices.size)
        indices.forEach { index ->
            val window = source.substring((index - 600).coerceAtLeast(0), index)
            assertTrue(
                "装挂点前面必须紧跟会话通道（同一「活引用」口径）",
                window.contains("ModImportSessionChannel.install(this)"),
            )
        }
        // 浮层读的是活会话的不可变快照口，不许改读别的东西（例如交接槽或某单例）。
        assertTrue("装挂的读口必须是 host?.modUiSurface()", source.contains("host?.modUiSurface()"))
    }

    @Test
    fun `销毁时认主卸载且与既有通道同段`() {
        val source = sourceOf("MainActivity.kt")
        val uninstall = source.indexOf("ModUiSettingsEntry.uninstall(this)")
        assertTrue("onDestroy 必须卸载设置页浮层读口（否则旧壳会持有死会话）", uninstall > 0)
        val channel = source.indexOf("ModImportSessionChannel.uninstall(this)")
        assertTrue("卸载点应排在会话通道卸载之后（同寿命令）", channel in 0 until uninstall)
    }

    @Test
    fun `设置页渲染与壳层装挂成对存在`() {
        val settings = sourceOf("SettingsActivity.kt")
        val main = sourceOf("MainActivity.kt")
        assertTrue("设置页必须在内容根渲染浮层", settings.contains("ModUiSettingsOverlay(language = language)"))
        assertTrue("壳层必须装挂读口", main.contains("ModUiSettingsEntry.install(this)"))
        assertTrue("壳层必须卸下读口", main.contains("ModUiSettingsEntry.uninstall(this)"))
    }
}
