package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 世代闸**归层**的源码哨兵（0.2.45 `ABLNAME:900/1150` 簇收口）。
 *
 * `GameHost` 挂在 Android 运行时上（app 的 JVM 单测面没有 Robolectric，实例化不了），
 * 与 `GameHostFrameWireTest` 同族用源码面钉接线形状；行为判据在
 * `core-vm` 的 `FrameOptionsGenerationTest`。
 *
 * 钉住三件事，任一环断掉，真机就回到「上一屏按钮值串进下一屏 INPUT」：
 * 1. 壳层只把命中值转交给控制台的世代判据，不在 `GameHost` 里另算可点集合；
 * 2. 壳层、控制台两处都**没有**「当前页没按钮就放行」的 fail-open；
 * 3. 控制台的 `lastButtonGeneration` 只有一个写口，且该写口同步置脏帧选项快照
 *    —— 否则 `publishFrame` 先清脏、`noteInputWait` 后前移世代，快照里留旧按钮。
 */
class GameHostGenerationGateWireTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/$relative"),
            // 公开分仓：core-vm 在同级引擎仓 erak-engine（原单体布局作兼容回落）。
            File("../../erak-engine/core-vm/src/main/kotlin/com/eratw/port/$relative"),
            File("../core-vm/src/main/kotlin/com/eratw/port/$relative"),
        )
        return candidates.firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8)
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
    }

    private fun hostSource(): String = sourceOf("android/GameHost.kt")

    private fun sinkSource(): String = sourceOf("core/host/LineBufferConsoleSink.kt")

    @Test
    fun `壳层世代闸只转交控制台判据_会话缺失时 fail closed`() {
        val src = hostSource()
        assertTrue(
            "GameHost.isCurrentGeneration 必须委托 console.isCurrentGeneration（判据归引擎持有者）",
            src.contains("session?.console?.isCurrentGeneration(value) ?: false"),
        )
        assertFalse(
            "壳层不得再留空表放行的 fail-open（原 options.isEmpty() || ...）",
            src.contains("options.isEmpty()"),
        )
    }

    @Test
    fun `控制台世代判据不设空表放行`() {
        val src = sinkSource()
        assertTrue(
            "判据必须是当前世代按钮集合的逐值反查",
            src.contains("publishedOptions().any { it.submitValue == value }"),
        )
        val body = src.substringAfter("fun isCurrentGeneration(value: String): Boolean")
            .substringBefore("/**")
        assertFalse("判据里不得出现 isEmpty() 短路放行，实际=$body", body.contains("isEmpty()"))
    }

    @Test
    fun `按钮世代只有一个写口且同步置脏帧选项快照`() {
        val src = sinkSource()
        assertTrue(
            "唯一写口必须在世代前移时置脏 optionsDirty",
            src.contains("lastButtonGeneration = to") && src.contains("optionsDirty = true"),
        )
        assertEquals(
            "声明处必须仍然存在",
            1,
            Regex("""private var lastButtonGeneration\s*=""").findAll(src).count(),
        )
        // KDoc 里会引用 C# 的 `lastButtonGeneration = newButtonGeneration` 原句，
        // 所以只扫非注释代码行；写口之外不允许再有直写。
        val codeLines = src.lines().filterNot {
            val t = it.trim()
            t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*/")
        }
        val directAssignments = codeLines.filter {
            Regex("""^\s*lastButtonGeneration\s*=(?!=)""").containsMatchIn(it)
        }
        assertEquals(
            "只允许 moveLastButtonGeneration 一处直写；实际=$directAssignments",
            1,
            directAssignments.size,
        )
        assertTrue(
            "newGeneration / stampButtonGeneration 都应走统一写口",
            codeLines.count { it.contains("moveLastButtonGeneration(newButtonGeneration)") } >= 3 &&
                codeLines.any { it.contains("moveLastButtonGeneration(generation)") },
        )
    }
}
