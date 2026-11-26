package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「指令后等一次按键」（COMEND-WAIT）的**接线哨兵**（2026-11-22）。
 *
 * 语义最终落在生产宿主 `ConsoleBeginHost.shouldWaitAfterCommand`（core 的
 * `CommandEndWaitPolicyTest` 钉读数），但从设置页到那一行要跨五个文件：
 * `UiPrefs`（键 + 缺省）→ `SettingsActivity`（开关行）→ `MainActivity`（UI 线程读数）→
 * `GameHost`（构造参数）→ `GameSession`（会话参数）→ `ConsoleBeginHost`（宿主总闸）。
 * 中间任何一环漏接，开关都会变成「点了没反应」的哑键 —— 这几个类多数挂在 Android 运行时上，
 * app 的 JVM 单测面实例化不了，故照 `BusyIndicatorWiringTest` / `QolGateWireTest` 的源码面
 * 口径钉形状。
 *
 * 反向哨兵同理重要：2026-11-02 那版「只清掃被拒」的收窄（`commandEndWaitComCodes`）必须
 * **彻底消失**，否则「开」这一档仍会被引擎侧二次过滤，玩家看到的还不是原版。
 */
class CommandEndWaitWiringTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(File("src/main/kotlin/com/eratw/port/android/$relative"))
        return candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)?.replace("\r\n", "\n")
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
    }

    private fun moduleSourceOf(relative: String): String {
        val candidates = listOf(
            File(relative),
            File("../$relative"),
            File("../../$relative"),
            // 公开分仓后引擎源码在同级引擎仓 Kotera（composite build 的 ../Kotera）。
            File("../../Kotera/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `偏好层键与缺省开`() {
        val prefs = sourceOf("UiPrefs.kt")
        assertTrue(
            "缺省必须是开（原版口径）",
            prefs.contains("const val DEFAULT_COMMAND_END_WAIT: Boolean = true"),
        )
        assertTrue("键名逐字", prefs.contains("private const val KEY_COMMAND_END_WAIT = \"command_end_wait\""))
        assertTrue("读侧公开入口", prefs.contains("fun commandEndWait(context: Context): Boolean"))
        assertTrue("写侧公开入口", prefs.contains("fun setCommandEndWait(context: Context, enabled: Boolean)"))
        assertTrue(
            "internal 读层缺省取常量，不许内联字面量",
            prefs.contains("sp.getBoolean(KEY_COMMAND_END_WAIT, DEFAULT_COMMAND_END_WAIT)"),
        )
    }

    @Test
    fun `设置页开关行与文案接线`() {
        val activity = sourceOf("SettingsActivity.kt")
        assertTrue("读数来自 UiPrefs", activity.contains("mutableStateOf(UiPrefs.commandEndWait(context))"))
        assertTrue("写回 UiPrefs", activity.contains("UiPrefs.setCommandEndWait(context, enabled)"))
        assertTrue("面板传值", activity.contains("commandEndWait = commandEndWait,"))
        assertTrue("开关行标题来自文案表", activity.contains("title = text.commandEndWaitTitle"))
        assertTrue("开关行提示来自文案表", activity.contains("hint = text.commandEndWaitHint"))

        val copy = sourceOf("SettingsStrings.kt")
        assertTrue("标题三语条目", copy.contains("val commandEndWaitTitle: String = tr("))
        assertTrue("提示三语条目", copy.contains("val commandEndWaitHint: String = tr("))
    }

    @Test
    fun `UI 线程读数到会话宿主一路直通`() {
        val main = sourceOf("MainActivity.kt")
        assertTrue(
            "UI 线程读好再交出去（装配线程不许摸 SharedPreferences）",
            main.contains("commandEndWait = UiPrefs.commandEndWait(this),"),
        )

        val host = sourceOf("GameHost.kt")
        assertTrue("宿主构造参数缺省开", host.contains("private val commandEndWait: Boolean = true,"))
        assertTrue("传给 GameSession", host.contains("waitAfterCommand = commandEndWait,"))

        val session = moduleSourceOf("core-vm/src/main/kotlin/com/eratw/port/core/proc/GameSession.kt")
        assertTrue("会话参数缺省开", session.contains("val waitAfterCommand: Boolean = true,"))
        assertTrue("传给生产宿主", session.contains("waitAfterCommand = waitAfterCommand,"))

        val beginHost = moduleSourceOf("core-vm/src/main/kotlin/com/eratw/port/core/proc/ConsoleBeginHost.kt")
        assertTrue("宿主总闸缺省开", beginHost.contains("private val waitAfterCommand: Boolean = true,"))
        val gateLine = "override fun shouldWaitAfterCommand(comCode: Int, result: Long): Boolean = " +
            "waitAfterCommand"
        assertTrue("判定只看总闸：不看指令码、不看 RESULT", beginHost.contains(gateLine))
    }

    @Test
    fun `旧收窄必须彻底消失`() {
        val roots = listOf(
            "src/main/kotlin",
            "src/test/kotlin",
            // 公开分仓后 core-vm 在同级引擎仓 Kotera（composite build 的 ../Kotera）。
            "../../Kotera/core-vm/src/main/kotlin",
            "../../Kotera/core-vm/src/test/kotlin",
        ).map { File(it) }.filter { it.isDirectory }
        assertEquals("四个源码根都要能找到", 4, roots.size)

        val offenders = roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                // 本文件自己的 KDoc 与断言里就写着这个旧名，排除它自己。
                .filter { it.name != "CommandEndWaitWiringTest.kt" }
                .filter { file -> file.readText(Charsets.UTF_8).contains("commandEndWaitComCodes") }
                .map { it.path }
                .toList()
        }
        assertTrue("旧收窄（只清掃被拒）仍在：$offenders", offenders.isEmpty())
    }
}
