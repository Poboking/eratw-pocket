package com.eratw.port.android

import com.eratw.port.platform.storage.ResourcePackStatus
import com.eratw.port.platform.storage.StructureNote
import com.eratw.port.platform.storage.StructureNoteKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResourcePackUiReducer] 与结构体检文案映射的钉子。
 *
 * 1. 结果四态依次推进（进行中 → 成功 / 部分成功 / 失败）且都保留现状读数：现状读数是
 *    页面上「已导入什么」的唯一来源，导入结果只影响结果区；两者混在一起最常见的事故是
 *    导入失败把已有读数清空，页面变成「什么都没导入」的假象。
 * 2. 部分成功必须同时带 summary 与 failedPacks：既有文件落盘、又有包一个文件都没进来。
 * 3. [structureWarningLines] 只做 kind → copy 模板的映射，句子全部来自 [ResourcePackCopy]。
 */
class ResourcePackUiTest {

    private val status = ResourcePackStatus(
        backgroundImages = 12,
        backgroundIndexRows = 300,
        bgmTracks = 7,
        bgmFolders = 3,
    )

    private val copy = ResourcePackCopy.ZH

    private val summary = ResourcePackImportSummary(
        packs = 2,
        failedPacks = 0,
        files = 9,
        overwritten = 1,
        skipped = 4,
        skippedSamples = listOf("readme.txt —— 不在 resources/ 或 sound/ 之下"),
        warningLines = emptyList(),
        message = copy.fill(copy.importedSummaryFormat, 9, 2, 1, 4),
    )

    @Test
    fun `导入四态依次推进且保留现状读数`() {
        var state: ResourcePackUiState = ResourcePackUiState.Idle(status)

        state = ResourcePackUiReducer.reduce(state, ResourcePackEvent.ImportStarted(2))
        assertTrue(state is ResourcePackUiState.Importing)
        assertEquals(status, state.status)

        state = ResourcePackUiReducer.reduce(state, ResourcePackEvent.ImportSucceeded(summary, status))
        assertTrue(state is ResourcePackUiState.Succeeded)
        assertEquals(summary, (state as ResourcePackUiState.Succeeded).summary)
        assertEquals(status, state.status)

        val partialSummary = summary.copy(
            packs = 1,
            failedPacks = 1,
            files = 4,
            message = copy.fill(copy.importedPartialFormat, 4, 1, 1),
        )
        state = ResourcePackUiReducer.reduce(
            state,
            ResourcePackEvent.ImportPartial(partialSummary, failedPacks = 1, status = status),
        )
        assertTrue(state is ResourcePackUiState.Partial)
        val partial = state as ResourcePackUiState.Partial
        assertEquals(partialSummary, partial.summary)
        assertEquals(1, partial.failedPacks)
        assertEquals(status, partial.status)
        assertTrue(partial.summary.hasLandedFiles)

        state = ResourcePackUiReducer.reduce(
            state,
            ResourcePackEvent.ImportFailed(copy.fill(copy.importedNothingFormat, ""), emptyList(), status),
        )
        assertTrue(state is ResourcePackUiState.Failed)
        assertEquals(status, state.status)
    }

    @Test
    fun `现状刷新在任意结果态都生效`() {
        val empty = ResourcePackStatus(0, 0, 0, 0)
        val states = listOf(
            ResourcePackUiState.Idle(null),
            ResourcePackUiState.Importing(1, null),
            ResourcePackUiState.Succeeded(summary, null),
            ResourcePackUiState.Partial(summary, 1, null),
            ResourcePackUiState.Failed("x", emptyList(), null),
        )
        states.forEach { before ->
            val after = ResourcePackUiReducer.reduce(before, ResourcePackEvent.StatusRefreshed(empty))
            assertEquals(empty, after.status)
        }
    }

    @Test
    fun `零文件的汇总不许带出重启按钮判据`() {
        val zero = ResourcePackImportSummary(
            packs = 0,
            failedPacks = 1,
            files = 0,
            overwritten = 0,
            skipped = 3,
            skippedSamples = emptyList(),
            warningLines = emptyList(),
            message = copy.fill(copy.importedNothingFormat, ""),
        )

        assertFalse(zero.hasLandedFiles)
        val landed = summary.copy(files = 1)
        assertTrue(landed.hasLandedFiles)
    }

    @Test
    fun `结构体检五类 kind 映射到 copy 文案且带取证路径`() {
        val lines = structureWarningLines(
            copy,
            listOf(
                StructureNote(StructureNoteKind.BGM_OUTSIDE_GAME_BGM, "sound/某地/0.mp3"),
                StructureNote(StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX, "resources/背景图"),
                StructureNote(StructureNoteKind.EMPTY_CSV_INDEX, "resources/背景图/list.csv"),
                StructureNote(StructureNoteKind.MATERIAL_IGNORED_FILES, "127"),
                StructureNote(StructureNoteKind.UNRECOGNIZED_LAYOUT, "readme.txt"),
            ),
        )

        assertEquals(5, lines.size)
        // 每行都套 noteLineFormat 的前缀（句子本身来自 copy，不是壳层硬编码）
        val notePrefix = copy.noteLineFormat.substringBefore("{")
        lines.forEach { line -> assertTrue(line, line.startsWith(notePrefix)) }
        assertTrue(lines[0], lines[0].contains("sound/某地/0.mp3"))
        assertTrue(lines[1], lines[1].contains("resources/背景图"))
        assertTrue(lines[2], lines[2].contains("resources/背景图/list.csv"))
        // 非素材文件只剩一条读数（个数），不再逐条占行
        assertTrue(lines[3], lines[3].contains(copy.fill(copy.materialIgnoredFilesFormat, "127")))
        // 结构认不出那条模板无槽位，detail 只留在 StructureNote 里，不硬塞进玩家句
        assertTrue(lines[4], lines[4].contains(copy.structureUnrecognizedText))
    }
}
