package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片扁平池开关的**预检接线**哨兵（CONTRACT-MODERN-T2 §3.4）。
 *
 * 预检（[com.eratw.port.core.loader.ProgramLoader.estimateHeap]）与装载链（`loadMapped`）必须吃
 * **同一个**开关读数：开池在 512 MiB 挡实测多吃 114 MiB（`core/code/SegmentedFlatStmt.kt:52-61`），
 * 预检少记这笔就是「按关记账、按开建池」的两张皮。旧实现只在 `assembleSession` 里读开关、
 * 预检走默认 `flatStatements = false` ⇒ 这笔预留完全漏在账外。
 *
 * `GameHost` 挂在 Android 运行时上，JVM 单测面实例化不了，照 [QolGateWireTest] /
 * [EventWiringTest] 的源码面口径钉形状。
 */
class FlatStatementsPreflightWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中（2026-11-08 实测）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `预检调用必须带上本局的 flat 开关`() {
        val src = sourceOf("GameHost.kt")
        val expected = listOf(
            "            val estimate = ProgramLoader.estimateHeap(",
            "                programFile.toPath(),",
            "                metaFile.toPath(),",
            "                flatStatements = flatStatementsOn,",
            "            )",
        ).joinToString("\n")
        assertTrue(
            "estimateHeap 必须显式吃 flatStatementsOn：不传就是默认 false，" +
                "114 MiB 的池增量完全漏在预检账外（本波修的就是这条断线）",
            src.contains(expected),
        )
    }

    @Test
    fun `开关只在预检之前读一次`() {
        val src = sourceOf("GameHost.kt")
        val reads = src.split("val flatStatementsOn = flatStatementsOverride").size - 1
        assertEquals(
            "读数必须只有一处（loadAndRun 的预检之前）：两处各读一次就可能被人" +
                " touch files/flat-statements.on 出「预检按关、装载按开」",
            1,
            reads,
        )
        // 读数必须排在预检之前：先读后判，否者预检那条调用拿不到本局的值。
        val readAt = src.indexOf("val flatStatementsOn = flatStatementsOverride")
        val estimateAt = src.indexOf("flatStatements = flatStatementsOn,")
        assertTrue("找不到预检处的开关传参", estimateAt > 0)
        assertTrue("开关读数必须排在 estimateHeap 调用之前（实际 read=$readAt, estimate=$estimateAt）", readAt < estimateAt)
    }

    @Test
    fun `装载链吃的是同一份读数`() {
        val src = sourceOf("GameHost.kt")
        val expected = listOf(
            "        val program = ProgramLoader.loadMapped(",
            "            programFile.toPath(),",
            "            compat = compatLoad,",
            "            flatStatements = flatStatementsOn,",
            "        ) { stage ->",
        ).joinToString("\n")
        assertTrue(
            "loadMapped 必须吃传进来的同一个值（不再是 assembleSession 内部现读）",
            src.contains(expected),
        )
        assertTrue(
            "读数上报面（sampleMemoryIfNeeded 的 flat= 列）仍要能拿到本局状态",
            src.contains("flatStatementsActive = flatStatementsOn"),
        )
    }
}
