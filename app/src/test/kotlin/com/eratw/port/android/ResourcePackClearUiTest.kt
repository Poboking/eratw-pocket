package com.eratw.port.android

import com.eratw.port.platform.storage.MaterialTarget
import com.eratw.port.platform.storage.ResourcePackCleanupReport
import com.eratw.port.platform.storage.ResourcePackStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清理（「清除已导入素材」）两态 + 三事件的钉子，以及确认框正文的取词口。
 *
 * 1. 推进：ClearStarted → Clearing；ClearSucceeded → Cleared（带 summary）；
 *    ClearFailed → **既有** Failed 态（message 原样），不新造失败态。
 * 2. 两态都带现状读数：清理只影响结果区，不许把「已导入什么」的读数一起清掉。
 * 3. StatusRefreshed 对全部状态（导入四态 + 清理两态 + Idle）生效；刷新后既有结果原样保留。
 * 4. [clearConfirmBody] 按 [MaterialTarget] 从 [ResourcePackCopy] 取词，壳层不内联字面量。
 */
class ResourcePackClearUiTest {

    private val status = ResourcePackStatus(
        backgroundImages = 12,
        backgroundIndexRows = 300,
        bgmTracks = 7,
        bgmFolders = 3,
    )

    private val copy = ResourcePackCopy.ZH

    private val summary = ResourcePackCleanupSummary(
        deletedFiles = 5,
        deletedBytes = 1_048_576L,
        restoredBaseFiles = 1,
        prunedDirs = 2,
        failedDeletions = 0,
        message = copy.fill(copy.clearSucceededFormat, 5, 2),
        warningLines = listOf(
            copy.fill(copy.noteLineFormat, copy.fill(copy.clearRestoredFormat, 1)),
        ),
    )

    @Test
    fun `清理开始落 Clearing 且保留现状读数`() {
        val before = ResourcePackUiState.Idle(status)
        val state = ResourcePackUiReducer.reduce(before, ResourcePackEvent.ClearStarted)
        assertTrue(state is ResourcePackUiState.Clearing)
        assertEquals(status, state.status)
    }

    @Test
    fun `清理结束落 Cleared 且带 summary`() {
        val state = ResourcePackUiReducer.reduce(
            ResourcePackUiState.Clearing(status),
            ResourcePackEvent.ClearSucceeded(summary, status),
        )
        assertTrue(state is ResourcePackUiState.Cleared)
        val cleared = state as ResourcePackUiState.Cleared
        assertEquals(summary, cleared.summary)
        assertEquals(status, cleared.status)
        // 汇总句来自表，不是壳层字面量
        assertEquals(copy.fill(copy.clearSucceededFormat, 5, 2), cleared.summary.message)
        assertEquals(5, cleared.summary.deletedFiles)
        assertEquals(1_048_576L, cleared.summary.deletedBytes)
        assertEquals(1, cleared.summary.restoredBaseFiles)
        assertEquals(2, cleared.summary.prunedDirs)
        assertEquals(0, cleared.summary.failedDeletions)
    }

    @Test
    fun `清理失败复用既有 Failed 态且 message 原样`() {
        val message = copy.fill(copy.clearFailedFormat, "基准清单读不到")
        val state = ResourcePackUiReducer.reduce(
            ResourcePackUiState.Clearing(status),
            ResourcePackEvent.ClearFailed(message, status),
        )
        assertTrue(state is ResourcePackUiState.Failed)
        val failed = state as ResourcePackUiState.Failed
        assertEquals(message, failed.message)
        assertEquals(status, failed.status)
        // 不新造失败态：明细为空，原因已经并进 message
        assertTrue(failed.skippedSamples.isEmpty())
    }
    @Test
    fun `现状刷新对全部状态生效（含清理两态）`() {
        // 契约口径的「六态」= 导入四态（Importing / Succeeded / Partial / Failed）+ 清理两态；
        // Idle 一并覆盖（共 7 个状态实例），加态不补分支就该红。
        val empty = ResourcePackStatus(0, 0, 0, 0)
        val importSummary = ResourcePackImportSummary(
            packs = 1,
            failedPacks = 0,
            files = 1,
            overwritten = 0,
            skipped = 0,
            skippedSamples = emptyList(),
            warningLines = emptyList(),
            message = copy.fill(copy.importedSummaryFormat, 1, 1, 0, 0),
        )
        val states = listOf(
            ResourcePackUiState.Idle(null),
            ResourcePackUiState.Importing(1, null),
            ResourcePackUiState.Succeeded(importSummary, null),
            ResourcePackUiState.Partial(importSummary, 1, null),
            ResourcePackUiState.Failed("x", emptyList(), null),
            ResourcePackUiState.Clearing(null),
            ResourcePackUiState.Cleared(summary, null),
        )
        states.forEach { before ->
            val after = ResourcePackUiReducer.reduce(before, ResourcePackEvent.StatusRefreshed(empty))
            assertEquals("现状刷新没落到 $before", empty, after.status)
        }
    }

    @Test
    fun `确认框正文两个范围各自取到不同文案且来自三语表`() {
        val background = clearConfirmBody(copy, MaterialTarget.BACKGROUND)
        val bgm = clearConfirmBody(copy, MaterialTarget.BGM)
        assertEquals(copy.clearConfirmBackgroundBody, background)
        assertEquals(copy.clearConfirmBgmBody, bgm)
        assertNotEquals("背景图与 BGM 的确认正文必须是两句不同的话", background, bgm)

        // 三档各自取自己这张表：壳层只把 copy 传进来，不内联玩家可见字面量
        listOf(ResourcePackCopy.ZH, ResourcePackCopy.EN, ResourcePackCopy.JA).forEach { each ->
            assertEquals(each.clearConfirmBackgroundBody, clearConfirmBody(each, MaterialTarget.BACKGROUND))
            assertEquals(each.clearConfirmBgmBody, clearConfirmBody(each, MaterialTarget.BGM))
            // 两条正文各自点名盘上真实投放根，玩家对得上自己导入时用的那张卡
            assertTrue(
                each.clearConfirmBackgroundBody,
                each.clearConfirmBackgroundBody.contains("resources/背景图/"),
            )
            assertTrue(
                each.clearConfirmBgmBody,
                each.clearConfirmBgmBody.contains("sound/"),
            )
        }
    }

    @Test
    fun `现状刷新不许把已有清理结果清空`() {
        val emptied = ResourcePackStatus(0, 0, 0, 0)
        val before = ResourcePackUiState.Cleared(summary, status)
        val after = ResourcePackUiReducer.reduce(before, ResourcePackEvent.StatusRefreshed(emptied))
        assertTrue(after is ResourcePackUiState.Cleared)
        val cleared = after as ResourcePackUiState.Cleared
        // 只换读数：汇总、明细、玩家句一律原样保留
        assertEquals(summary, cleared.summary)
        assertEquals(before.summary.message, cleared.summary.message)
        assertEquals(before.summary.warningLines, cleared.summary.warningLines)
        assertEquals(emptied, cleared.status)
    }

    // ------------------------------------------------------------------
    // 平台读数 -> 玩家事件（cleanupEvent）
    // ------------------------------------------------------------------

    /** 整批没做成（基准清单读不到）走既有 Failed 态，绝不报成「清理成功 0 个」。 */
    @Test
    fun `清理报告带 error 时落 ClearFailed 且点名原因`() {
        val report = ResourcePackCleanupReport(
            deletedFiles = 0,
            deletedBytes = 0L,
            restoredBaseFiles = 0,
            keptBaseFiles = 3,
            prunedDirs = 0,
            failures = emptyList(),
            error = "基准清单读不到",
        )
        val message = copy.fill(copy.clearFailedFormat, "基准清单读不到")
        val event = cleanupEvent(copy, report, status)

        assertEquals(ResourcePackEvent.ClearFailed(message, status), event)
        val state = ResourcePackUiReducer.reduce(ResourcePackUiState.Clearing(status), event)
        assertTrue(state is ResourcePackUiState.Failed)
        assertEquals(message, (state as ResourcePackUiState.Failed).message)
        assertEquals(status, state.status)
    }

    /** 没删到东西就是「没有找到需要清理的导入素材」，不写成「成功清理了 0 个」。 */
    @Test
    fun `零删除零还原时汇总句是 clearNothingFormat`() {
        val report = ResourcePackCleanupReport(
            deletedFiles = 0,
            deletedBytes = 0L,
            restoredBaseFiles = 0,
            keptBaseFiles = 12,
            prunedDirs = 4,
            failures = emptyList(),
        )
        val event = cleanupEvent(copy, report, status)

        val summary = (event as ResourcePackEvent.ClearSucceeded).summary
        assertEquals(copy.clearNothingFormat, summary.message)
        assertEquals(0, summary.deletedFiles)
        assertEquals(0, summary.failedDeletions)
        assertTrue(summary.warningLines.isEmpty())
    }

    /**
     * 有删除时汇总句报删除数与空目录数；被还原的随包文件单独一行；失败明细最多上屏 8 条，
     * 但 [ResourcePackCleanupSummary.failedDeletions] 报**真实总数**（不许被展示截断污染）。
     */
    @Test
    fun `还原行与失败明细分开拼且截断只影响展示`() {
        val failures = (1..11).map { "resources/背景图/$it.webp —— 删除失败（文件系统返回 false）" }
        val report = ResourcePackCleanupReport(
            deletedFiles = 5,
            deletedBytes = 1_048_576L,
            restoredBaseFiles = 2,
            keptBaseFiles = 30,
            prunedDirs = 3,
            failures = failures,
        )
        val event = cleanupEvent(copy, report, null)

        val summary = (event as ResourcePackEvent.ClearSucceeded).summary
        assertEquals(copy.fill(copy.clearSucceededFormat, 5, 3), summary.message)
        assertEquals(5, summary.deletedFiles)
        assertEquals(1_048_576L, summary.deletedBytes)
        assertEquals(2, summary.restoredBaseFiles)
        assertEquals(3, summary.prunedDirs)
        // 真实失败总数是 11，展示被截到 8 条明细
        assertEquals(11, summary.failedDeletions)
        assertEquals(9, summary.warningLines.size)
        val restoreLine = copy.fill(
            copy.noteLineFormat,
            copy.fill(copy.clearRestoredFormat, 2),
        )
        assertEquals(restoreLine, summary.warningLines.first())
        summary.warningLines.drop(1).forEachIndexed { index, line ->
            assertEquals(copy.fill(copy.skippedLineFormat, failures[index]), line)
        }
        assertNull(event.status)
    }

    /** 只还原、没删文件：汇总句仍走成功档（还原是「随包素材被修回来了」，不是无事可做）。 */
    @Test
    fun `只还原没删除时仍报成功且带还原行`() {
        val report = ResourcePackCleanupReport(
            deletedFiles = 0,
            deletedBytes = 0L,
            restoredBaseFiles = 1,
            keptBaseFiles = 9,
            prunedDirs = 0,
            failures = emptyList(),
        )
        val summary = (cleanupEvent(copy, report, status) as ResourcePackEvent.ClearSucceeded).summary

        assertEquals(copy.fill(copy.clearSucceededFormat, 0, 0), summary.message)
        assertEquals(1, summary.warningLines.size)
        assertEquals(
            copy.fill(copy.noteLineFormat, copy.fill(copy.clearRestoredFormat, 1)),
            summary.warningLines.single(),
        )
        assertEquals(1, summary.restoredBaseFiles)
    }
}
