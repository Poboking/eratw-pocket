package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COMPAT-3P-MOD：第三方引擎档包强制旁路 eraTW Mod 装配的接线钉 + 逻辑判据。
 *
 * 背景（2026-11-19 真机实测）：配置模式出的 eratohoReverse 第三方包（印章 Present）缺省装配
 * 6 个 eraTW 业务 mod + global sidecar，SYSTEM_TITLE 行4 `SAVEGLOBAL` 走侧车配对事务抛
 * `CodeEE(ErrorSavingGlobalData)` → 开局即"游戏出错"。关掉总闸则标题正常、可收输入。
 * 修复 = 会话有效总闸 `modActive = modRuntime && engineStamp !is EngineStampRead.Present`。
 *
 * 本测试钉两件事：
 * 1. 源码接线：GameHost 的 `modActive` 定义、5 处消费点、构造参数原文、MainActivity 传参原文
 *    （后两者是 ModRuntimeGateTest / ModRuntimeSwitchWiringTest 的既有钉，这里再确认不被本修复破坏）。
 * 2. 逻辑判据：4 态真值表（含第三方 Present 强制关、eraTW Missing 保持玩家开关），
 *    用与生产同式的布尔表达式直测 —— 表达式源码一致性由第 1 条源码钉保证。
 */
class ThirdPartyModBypassTest {

    private fun sourceOf(name: String): String {
        val roots = listOf(
            "app/src/main/kotlin/com/eratw/port/android/$name",
            "src/main/kotlin/com/eratw/port/android/$name",
        )
        for (rel in roots) {
            val f = File(rel)
            if (f.isFile) return f.readText(Charsets.UTF_8)
        }
        error("找不到源文件 $name（cwd=${File(".").absolutePath}）")
    }

    /** 生产同式：modActive = modRuntime && stamp != Present。 */
    private fun effective(modRuntime: Boolean, stampPresent: Boolean): Boolean =
        modRuntime && !stampPresent

    @Test
    fun `会话有效总闸定义存在且表达式正确`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "必须定义 modActive = modRuntime && engineStamp !is EngineStampRead.Present",
            host.contains(
                "private val modActive: Boolean = modRuntime && " +
                    "engineStamp !is EngineStampRead.Present",
            ),
        )
    }

    @Test
    fun `五处消费点全部改吃modActive而非原总闸`() {
        val host = sourceOf("GameHost.kt")
        // 5 处生产消费：音声覆盖 / csvContext / 链装配 enabled / attach enabled / attach modProviders / assetOverlay。
        // 逐一钉住 modActive 关键上下文，防止回退到 modRuntime。
        assertTrue("音声覆盖消费点", host.contains("val soundOverlay = if (modActive)"))
        assertTrue("CSV 装载上下文消费点", host.contains("val csvContext = if (modActive)"))
        assertTrue(
            "链装配 enabled 消费点",
            host.contains("enabled = modActive,"),
        )
        assertTrue(
            "attach modProviders 消费点",
            host.contains("modProviders = if (modActive)"),
        )
        assertTrue("资源覆盖消费点", host.contains("val assetOverlay = if (modActive)"))
        // 反向：消费点不得再直接吃原总闸（构造参数定义那行除外）。
        assertFalse(
            "消费点不许回退用 modRuntime 作 if 条件",
            host.contains("if (modRuntime)"),
        )
        assertFalse(
            "装配入口 enabled 不许回退用 modRuntime",
            host.contains("enabled = modRuntime,"),
        )
    }

    @Test
    fun `构造参数与MainActivity传参原文保持不变`() {
        // 两个既有守门测试（ModRuntimeGateTest / ModRuntimeSwitchWiringTest）钉的原文，
        // 本修复必须保持 —— 否则说明改了构造参数流，会牵连玩家面开关。
        val host = sourceOf("GameHost.kt")
        val main = sourceOf("MainActivity.kt")
        assertTrue(
            "GameHost 构造参数原文不变",
            host.contains("private val modRuntime: Boolean = false,"),
        )
        assertTrue(
            "MainActivity 单路传参原文不变",
            main.contains("modRuntime = UiPrefs.modRuntime(this),"),
        )
    }

    @Test
    fun `四态真值表第三方Present强制关且eraTW保持玩家开关`() {
        // 第三方档（Present）+ 玩家开 → 旁路（修复目标）
        assertFalse("第三方档必须旁路 mod", effective(modRuntime = true, stampPresent = true))
        // 第三方档 + 玩家关 → 旁路（本就关）
        assertFalse(effective(modRuntime = false, stampPresent = true))
        // eraTW 档（无 Present）+ 玩家开 → 装配（eraTW 行为逐字不变）
        assertTrue("eraTW 档玩家开必须装配 mod", effective(modRuntime = true, stampPresent = false))
        // eraTW 档 + 玩家关 → 旁路（关态既有口径）
        assertFalse(effective(modRuntime = false, stampPresent = false))

        // 真值表恰好 1 个 true（只有 eraTW+开），证明旁路不是"全关"。
        val truth = listOf(
            effective(true, true), effective(false, true),
            effective(true, false), effective(false, false),
        )
        assertEquals(listOf(false, false, true, false), truth)
    }
}
