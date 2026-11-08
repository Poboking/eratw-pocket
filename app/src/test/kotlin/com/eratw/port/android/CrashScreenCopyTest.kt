package com.eratw.port.android

import java.io.File
import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃摘要这一族（C6，wave/eng-crash）的**纯逻辑**定向单测：
 * 三语表形状（[CopyShapeGate]）、摘要 → 屏上文字、以及「上次崩溃摘要」标记的读写。
 *
 * 纯 JVM：文案表、摘要拼装与标记读写都不碰 Android（标记只碰 `java.io`，用临时目录直测）。
 * 崩溃屏**行为层**（独立进程里真的弹起来、两颗按钮的实际跳转、系统崩溃对话框的层叠关系）
 * 没法在 JVM 跑 —— 那部分由 [CrashScreenWiringTest] 的源码面哨兵 + 交付报告的
 * TODO(verify) 认领。
 */
class CrashScreenCopyTest {

    private val copies = listOf(CrashScreenCopy.ZH, CrashScreenCopy.EN, CrashScreenCopy.JA)

    /** 全部键的显式清单（改名必须跟着改这里——形状闸只认列出来的键）。 */
    private val keys: Map<String, KProperty1<CrashScreenCopy, String>> = mapOf(
        "title" to CrashScreenCopy::title,
        "whatHappenedFormat" to CrashScreenCopy::whatHappenedFormat,
        "causeOutOfMemory" to CrashScreenCopy::causeOutOfMemory,
        "causeStackOverflow" to CrashScreenCopy::causeStackOverflow,
        "causeScript" to CrashScreenCopy::causeScript,
        "causeUnknown" to CrashScreenCopy::causeUnknown,
        "whereFormat" to CrashScreenCopy::whereFormat,
        "timeUnknown" to CrashScreenCopy::timeUnknown,
        "threadMain" to CrashScreenCopy::threadMain,
        "threadScript" to CrashScreenCopy::threadScript,
        "threadOtherFormat" to CrashScreenCopy::threadOtherFormat,
        "threadUnknown" to CrashScreenCopy::threadUnknown,
        "censusFormat" to CrashScreenCopy::censusFormat,
        "censusUnavailable" to CrashScreenCopy::censusUnavailable,
        "reportSavedFormat" to CrashScreenCopy::reportSavedFormat,
        "reportSavedHint" to CrashScreenCopy::reportSavedHint,
        "reportMissing" to CrashScreenCopy::reportMissing,
        "restartButton" to CrashScreenCopy::restartButton,
        "closeButton" to CrashScreenCopy::closeButton,
        "bannerTitle" to CrashScreenCopy::bannerTitle,
        "bannerBodyFormat" to CrashScreenCopy::bannerBodyFormat,
        "bannerAck" to CrashScreenCopy::bannerAck,
    )

    // ------------------------------------------------------------------
    // 1. 形状闸（三语同表）
    // ------------------------------------------------------------------

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        assertEquals(22, keys.size)
        CopyShapeGate.assertKeyCount("CrashScreenCopy", copies, keys, expectedPerCopy = 23)
    }

    @Test
    fun `每条非空且三档两两不同`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, keys)
    }

    @Test
    fun `带读数的句子三档同形（槽位序列与换行数对齐）`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, keys)
    }

    @Test
    fun `灌满读数后不留任何槽位`() {
        CopyShapeGate.assertNoSlotResidue(copies, keys) { copy, template, n ->
            copy.fill(template, *Array(n) { "S$it" })
        }
    }

    @Test
    fun `语言自证（EN 无汉字、ZH 每条有汉字、JA 每条有假名或汉字）`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, keys)
    }

    @Test
    fun `of 按设置页语言三枚举各归各位`() {
        assertEquals(CrashScreenCopy.ZH, CrashScreenCopy.of(SettingsLanguage.ZH))
        assertEquals(CrashScreenCopy.EN, CrashScreenCopy.of(SettingsLanguage.EN))
        assertEquals(CrashScreenCopy.JA, CrashScreenCopy.of(SettingsLanguage.JA))
    }

    // ------------------------------------------------------------------
    // 2. 摘要 → 屏上文字
    // ------------------------------------------------------------------

    private val full = CrashSummary(
        exceptionType = "OutOfMemoryError",
        atMillis = 1_760_000_000_000L,
        threadName = "eratw-vm",
        censusLine = "CSV 资源表：文件=266 sprite=2795 警告=0",
        reportName = "eratw-report-1760000000000.txt",
    )

    @Test
    fun `完整摘要的五类句子都上屏且不含堆栈`() {
        val copy = CrashScreenCopy.ZH
        val text = CrashSummaryText.screenText(copy, full)
        assertTrue("标题：$text", text.startsWith(copy.title))
        assertTrue("异常类型那行：$text", text.contains("OutOfMemoryError"))
        assertTrue("人间话那行：$text", text.contains(copy.causeOutOfMemory))
        assertTrue("线程那行：$text", text.contains(copy.threadScript))
        assertTrue("取证那行：$text", text.contains("CSV 资源表：文件=266 sprite=2795 警告=0"))
        assertTrue("报告那行：$text", text.contains("eratw-report-1760000000000.txt"))
        assertTrue("下一步指引：$text", text.contains(copy.reportSavedHint))
        assertFalse("堆栈字样不许进摘要：$text", text.contains("\tat ") || text.contains("Exception:"))
    }

    @Test
    fun `取证与报告都取不到时说清不可用而不是留空`() {
        val copy = CrashScreenCopy.ZH
        val bare = full.copy(censusLine = null, reportName = null, atMillis = 0L)
        val text = CrashSummaryText.screenText(copy, bare)
        assertTrue("取证不可用：$text", text.contains(copy.censusUnavailable))
        assertTrue("报告没落成：$text", text.contains(copy.reportMissing))
        assertTrue("时间未知：$text", text.contains(copy.timeUnknown))
        assertFalse("没有报告就不许说保存好了：$text", text.contains(copy.reportSavedFormat.substringBefore("：")))
    }

    @Test
    fun `异常类型映射到人间话且认不出给兜底`() {
        val copy = CrashScreenCopy.ZH
        assertEquals(copy.causeOutOfMemory, CrashSummaryText.causePhrase(copy, "java.lang.OutOfMemoryError"))
        assertEquals(copy.causeStackOverflow, CrashSummaryText.causePhrase(copy, "StackOverflowError"))
        assertEquals(copy.causeScript, CrashSummaryText.causePhrase(copy, "CodeEE"))
        assertEquals(copy.causeScript, CrashSummaryText.causePhrase(copy, "VmException"))
        assertEquals(copy.causeUnknown, CrashSummaryText.causePhrase(copy, "WeirdError"))
    }

    @Test
    fun `线程名按两条已知线给人话，其余原样带上线程名`() {
        val copy = CrashScreenCopy.ZH
        assertEquals(copy.threadMain, CrashSummaryText.threadLabel(copy, "main"))
        assertEquals(copy.threadScript, CrashSummaryText.threadLabel(copy, "eratw-vm"))
        assertTrue(CrashSummaryText.threadLabel(copy, "Timer-1").contains("Timer-1"))
        assertEquals(copy.threadUnknown, CrashSummaryText.threadLabel(copy, "  "))
    }

    @Test
    fun `兜底横幅正文一句话说清类型与人话`() {
        val body = CrashSummaryText.bannerBody(CrashScreenCopy.ZH, full)
        assertTrue(body, body.contains("OutOfMemoryError"))
        assertTrue(body, body.contains(CrashScreenCopy.ZH.causeOutOfMemory))
        assertFalse("横幅不带路径：$body", body.contains("/"))
    }

    // ------------------------------------------------------------------
    // 3. 「上次崩溃摘要」标记
    // ------------------------------------------------------------------

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("eratw-crash-marker").toFile()

    @Test
    fun `标记往返不丢字段且目录不存在时自建`() {
        val dir = File(tempDir(), "eratw-reports")
        assertTrue("写标记：$dir", CrashSummaryMarker.write(dir, full))
        val back = CrashSummaryMarker.read(dir)
        assertEquals(full, back)
    }

    @Test
    fun `可选字段缺席时往返成 null 而不是空串`() {
        val dir = tempDir()
        val bare = full.copy(censusLine = null, reportName = null)
        assertTrue(CrashSummaryMarker.write(dir, bare))
        val back = CrashSummaryMarker.read(dir)
        assertEquals(bare, back)
        assertNull(back?.censusLine)
        assertNull(back?.reportName)
    }

    @Test
    fun `多行取证行折成一行不把标记切碎`() {
        val dir = tempDir()
        val multiline = full.copy(censusLine = "第一行\n第二行\r\n第三行")
        assertTrue(CrashSummaryMarker.write(dir, multiline))
        val back = CrashSummaryMarker.read(dir)
        assertEquals("第一行 第二行  第三行", back?.censusLine)
        assertEquals(full.exceptionType, back?.exceptionType)
    }

    @Test
    fun `损坏或缺失的标记读成 null 且不抛`() {
        val dir = tempDir()
        assertNull("没有文件：", CrashSummaryMarker.read(dir))
        CrashSummaryMarker.file(dir).writeText("type=OnlyType\n", Charsets.UTF_8)
        assertNull("缺 at 与 thread：", CrashSummaryMarker.read(dir))
        CrashSummaryMarker.file(dir).writeText("这不是标记\n\n乱码", Charsets.UTF_8)
        assertNull(CrashSummaryMarker.read(dir))
    }

    @Test
    fun `清标记后读取为空且重复清不成问题`() {
        val dir = tempDir()
        assertTrue(CrashSummaryMarker.write(dir, full))
        assertTrue(CrashSummaryMarker.clear(dir))
        assertNull(CrashSummaryMarker.read(dir))
        assertFalse("第二次清返回 false（文件已不存在）但绝不抛", CrashSummaryMarker.clear(dir))
    }

    @Test
    fun `写不进去只返回 false 绝不抛`() {
        // 目标同名路径是个普通文件 ⇒ mkdirs 失败 ⇒ write 只能返回 false。
        val blocker = File(tempDir(), "blocker")
        blocker.writeText("x", Charsets.UTF_8)
        assertFalse(CrashSummaryMarker.write(File(blocker, "eratw-reports"), full))
    }
}
