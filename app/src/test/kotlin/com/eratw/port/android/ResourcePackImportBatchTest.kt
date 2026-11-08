package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResourcePackImportBatch] 的三态判定钉子（纯 JVM）：成功判据是「真有文件落盘」，
 * 不是「install 没抛」。
 *
 * 三个最容易误报成功的形态各钉一条：坏 zip（error + 0 文件）、全跳过 zip（0 文件、
 * 无 error）、非 zip / 读失败（还没走到 install）。1 好 1 坏必须落 PARTIAL 而不是
 * 成功或失败；全好才是 SUCCEEDED。
 */
class ResourcePackImportBatchTest {

    private val copy = ResourcePackCopy.ZH

    private fun item(
        name: String,
        installed: Int,
        skipped: Int = 0,
        overwritten: Int = 0,
        error: String? = null,
    ) = ResourcePackBatchItem(
        name = name,
        installedFiles = installed,
        skippedEntries = skipped,
        overwrittenFiles = overwritten,
        error = error,
    )

    @Test
    fun `全部包都有文件落盘才判 SUCCEEDED 且汇总句走模板`() {
        val verdict = ResourcePackImportBatch.judge(
            copy,
            listOf(
                item("a.zip", installed = 3, skipped = 1),
                item("b.zip", installed = 2, skipped = 0, overwritten = 1),
            ),
        )

        assertEquals(ResourcePackBatchOutcome.SUCCEEDED, verdict.outcome)
        assertEquals(5, verdict.files)
        assertEquals(2, verdict.packs)
        assertEquals(0, verdict.failedPacks)
        assertEquals(1, verdict.overwritten)
        assertEquals(1, verdict.skipped)
        assertTrue(verdict.hasLandedFiles)
        assertEquals(copy.fill(copy.importedSummaryFormat, 5, 2, 1, 1), verdict.message)
        assertTrue(verdict.failedLines.isEmpty())
    }

    @Test
    fun `坏 zip 零文件判 FAILED 且 error 原话进明细`() {
        val verdict = ResourcePackImportBatch.judge(
            copy,
            listOf(item("bad.zip", installed = 0, error = "打不开压缩包（zip END header not found）")),
        )

        assertEquals(ResourcePackBatchOutcome.FAILED, verdict.outcome)
        assertEquals(0, verdict.files)
        assertFalse(verdict.hasLandedFiles)
        assertEquals(1, verdict.failedPacks)
        assertEquals(
            copy.fill(copy.importedNothingFormat, verdict.failedLines.joinToString("\n")),
            verdict.message,
        )
        val line = verdict.failedLines.single()
        assertTrue(line, line.contains("bad.zip"))
        assertTrue(line, line.contains("zip END header"))
    }

    @Test
    fun `全跳过 zip 无 error 也判 FAILED 并点出没有可用条目`() {
        val verdict = ResourcePackImportBatch.judge(
            copy,
            listOf(item("skip.zip", installed = 0, skipped = 7)),
        )

        assertEquals(ResourcePackBatchOutcome.FAILED, verdict.outcome)
        assertEquals(0, verdict.files)
        assertEquals(7, verdict.skipped)
        assertFalse(verdict.hasLandedFiles)
        assertEquals(
            copy.fill(copy.failedNoUsableEntriesFormat, "skip.zip"),
            verdict.failedLines.single(),
        )
    }

    @Test
    fun `一个成功一个失败判 PARTIAL 且成功包数与失败包数分开`() {
        val verdict = ResourcePackImportBatch.judge(
            copy,
            listOf(
                item("good.zip", installed = 4, skipped = 2, overwritten = 1),
                item("bad.zip", installed = 0, error = "坏包原话"),
            ),
        )

        assertEquals(ResourcePackBatchOutcome.PARTIAL, verdict.outcome)
        assertEquals(4, verdict.files)
        assertEquals(1, verdict.packs)
        assertEquals(1, verdict.failedPacks)
        assertEquals(1, verdict.overwritten)
        assertEquals(2, verdict.skipped)
        assertTrue(verdict.hasLandedFiles)
        assertEquals(copy.fill(copy.importedPartialFormat, 4, 1, 1), verdict.message)
        assertEquals(1, verdict.failedLines.size)
    }

    @Test
    fun `非 zip 与读失败按预检失败计入失败包数`() {
        val preflight = listOf(
            copy.fill(copy.failedNotZipFormat, "note.txt"),
            copy.fill(copy.failedReadFormat, "huge.zip", 1024),
        )
        val verdict = ResourcePackImportBatch.judge(
            copy,
            listOf(item("ok.zip", installed = 1)),
            preflight,
        )

        assertEquals(ResourcePackBatchOutcome.PARTIAL, verdict.outcome)
        assertEquals(1, verdict.packs)
        assertEquals(2, verdict.failedPacks)
        assertEquals(copy.fill(copy.importedPartialFormat, 1, 1, 2), verdict.message)
        assertEquals(preflight, verdict.failedLines)
    }

    @Test
    fun `空批判 FAILED 且消息非空`() {
        val verdict = ResourcePackImportBatch.judge(copy, emptyList())

        assertEquals(ResourcePackBatchOutcome.FAILED, verdict.outcome)
        assertEquals(0, verdict.files)
        assertFalse(verdict.hasLandedFiles)
        assertTrue(verdict.message.isNotEmpty())
    }
}
