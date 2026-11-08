package com.eratw.port.buildlogic

import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * cb001 接线②（D8 缺口闭环）：产物侧 formatAppliedCorpusPatches 的定向单测。
 *
 * 本测试钉住「buildSrc 写进 package.report 的列值」与「出包脚本 format_applied_corpus_patches
 * 逐字一致」：黄金串与出包脚本定向用例里的 CB001 常量同源，两端各自拿同一条留痕行比对
 * 同一个期望值。target 含空格、多项分隔、空形状、坏形状判红都覆盖。
 */
class AppliedCorpusPatchesColumnTest {

    // 与出包脚本定向用例里的 CB001 常量逐字同源（唯一权威）。
    private val cb001Id = "CB-001"
    private val cb001Target =
        "口上・メッセージ関連/個人口上/051 Nitori [にとり]/Nitori/M_KOJO_K51_everyday_commmands.ERB"
    private val cb001Bad = "3482ab97bbdea05f7c33d6b4b69de43abfaea2576ce9bbef20e859ac81c586a8"
    private val cb001Fixed = "5a3a8c7b62d58860d900843c722925e222c1aaf952d10a2d31f1c9d99240db71"

    // Python 侧 format_applied_corpus_patches 对上面这条留痕的规范值（单项，无分隔符）。
    private val cb001Golden = "$cb001Id $cb001Target $cb001Bad $cb001Fixed"

    private fun manifestWithCb001(): String =
        "manifest-format 2\n" +
            "source A.ERB 8 ${"0".repeat(64)}\n" +
            "corpus-patch $cb001Id $cb001Target $cb001Bad $cb001Fixed\n"

    @Test
    fun `单条留痕 target 含空格 与脚本侧黄金串逐字一致`() {
        val column = formatAppliedCorpusPatches(manifestWithCb001())
        assertEquals(cb001Golden, column)
        // 头 id + 尾两个 64hex sha + 中间全当 target：拆回后仍是同一条记录。
        val parts = column.split(" ")
        assertEquals(cb001Id, parts.first())
        assertEquals(cb001Bad, parts[parts.size - 2])
        assertEquals(cb001Fixed, parts.last())
        assertEquals(cb001Target, parts.subList(1, parts.size - 2).joinToString(" "))
    }

    @Test
    fun `多条留痕以分隔符拼接且保序`() {
        val secondBad = "a".repeat(64)
        val secondFixed = "b".repeat(64)
        val manifest = manifestWithCb001() +
            "corpus-patch CB-002 DLC/含 空格 的 路径.ERB $secondBad $secondFixed\n"
        val expected = "$cb001Golden | CB-002 DLC/含 空格 的 路径.ERB $secondBad $secondFixed"
        assertEquals(expected, formatAppliedCorpusPatches(manifest))
    }

    @Test
    fun `无留痕行取空形状 非留痕行不混入`() {
        val manifest = "manifest-format 2\nsource A.ERB 8 ${"0".repeat(64)}\n"
        assertEquals("-", formatAppliedCorpusPatches(manifest))
        assertEquals("-", formatAppliedCorpusPatches(""))
    }

    @Test
    fun `字段不足四段即判红`() {
        assertRejects("corpus-patch CB-001 short ${"0".repeat(64)}\n")
    }

    @Test
    fun `病灶 sha 非 64 位小写 hex 即判红`() {
        assertRejects("corpus-patch CB-001 T.ERB NOTSHA $cb001Fixed\n")
    }

    @Test
    fun `修后 sha 大写 hex 即判红 与脚本侧窄 schema 一致`() {
        assertRejects("corpus-patch CB-001 T.ERB $cb001Bad ${cb001Fixed.uppercase()}\n")
    }

    private fun assertRejects(manifest: String) {
        try {
            formatAppliedCorpusPatches(manifest)
            fail("坏形状应当抛 GradleException")
        } catch (e: GradleException) {
            assertTrue("异常应带文案", e.message!!.isNotEmpty())
        }
    }
}
