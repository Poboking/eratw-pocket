package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 Mod 运行时总闸的**玩家面开关接线哨兵**。
 *
 * 背景：闸本体（[UiPrefs.modRuntime] 读写层 + `GameHost(modRuntime=)` + `MainActivity` 单路读数）
 * 早已落码，但打开它的唯一出口是测试/调试调用 —— 设置页没有入口，玩家无法开闸。本类钉住
 * 「开关真的接到玩家面上」这一件事，删掉任一条（开关行 / 读点 / 写点 / 传值）当场红。
 *
 * 不测行为（`SettingsActivity` 挂在 Android 运行时上，JVM 单测实例化不了），只钉源码形状；
 * 缺省值自 2026-11-17（RT2-GATE-OPEN，主人裁定）起是 **开**（开箱可用）。默认值再变动
 * 必须先改这里的断言，属于有意红。
 */
class ModRuntimeSwitchWiringTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `缺省是开`() {
        assertTrue("总闸缺省必须为开（2026-11-17 主人裁定：开箱可用）", UiPrefs.DEFAULT_MOD_RUNTIME)
    }

    @Test
    fun `设置页开关接到读写层`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页必须在 UI 线程读一次开关现状",
            settings.contains("var modRuntime by remember { mutableStateOf(UiPrefs.modRuntime(context)) }"),
        )
        assertTrue(
            "开关必须把新值写回偏好表（下一次会话装配生效）",
            settings.contains("UiPrefs.setModRuntime(context, enabled)"),
        )
        assertTrue(
            "开关行必须用真文案（不许内联字面量）",
            settings.contains("title = text.modRuntimeTitle") &&
                settings.contains("hint = text.modRuntimeHint"),
        )
        assertTrue(
            "开关行必须把它接到状态与写点",
            settings.contains("onModRuntime = { enabled ->"),
        )
    }

    @Test
    fun `单路读数与宿主透传不变`() {
        val main = sourceOf("MainActivity.kt")
        assertTrue(
            "装配期在 VM 线程，读数只在 MainActivity 取一次",
            main.contains("modRuntime = UiPrefs.modRuntime(this),"),
        )
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "宿主构造参数缺省仍为 false（关态零接触）",
            host.contains("private val modRuntime: Boolean = false,"),
        )
        assertTrue(
            "总闸必须原样透传给装配入口（COMPAT-3P-MOD：经会话有效值 modActive，第三方档旁路）",
            host.contains("enabled = modActive,"),
        )
    }

    @Test
    fun `三语文案同加且不内联`() {
        val strings = sourceOf("SettingsStrings.kt")
        for (key in listOf("modRuntimeTitle", "modRuntimeHint")) {
            assertTrue("缺 $key", strings.contains("val $key: String = tr("))
        }
        // 三语同加：每条都同时出现 zh / en / ja（形状闸 SettingsStringsTest 会再核占位符一致）。
        val block = strings.substringAfter("val modRuntimeHint: String = tr(")
            .substringBefore("\n    )")
        for (lang in listOf("zh =", "en =", "ja =")) {
            assertTrue("modRuntimeHint 缺 $lang", block.contains(lang))
        }
        // 提示文案必须说清「会话构造期参数、重开一局才生效」这一口径（三语各自都有对应说法）。
        assertTrue(
            "提示文案缺「重开一局生效」口径",
            block.contains("重开一局") && block.contains("restarts") && block.contains("再起動"),
        )
    }
}
