package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 预览喂入接线的出口判据（纯 JVM：状态机 [ModImportFeedReducer] + 预览后台体
 * [computeModImportPreviewSafely] + 宿主线迹哨兵）。
 *
 * 钉的语义：
 * - **顺序**：选完先进 [ModImportFeedState.Reading]（没预览、不可确认），预览算好才
 *   [ModImportFeedState.AwaitingConfirm]，确认后才产生导入批——「预览先于导入」的唯一放行口
 *   是 [ModImportFeedReducer.importBatchOf]（不是 UI 按钮可见性）；
 * - **取消不留盘面**：取消后回 [ModImportFeedState.Idle]，批与预览都没了；
 * - **降级不阻断**：预览 [Preview.Unavailable] 照样可确认导入（强校验仍在导入事务里）；
 * - **重选清空**：旧预览立即作废，上一批迟到的结果按 selectionId 丢弃，不串批；
 * - **后台不阻塞**：后台体只读、绝不抛（异常折成 Unavailable 供宿主降级显示），判定全在
 *   纯函数里，宿主只做线程编排；
 * - **无全局单例**：两路状态互不影响；
 * - **喂入点存在**：SAF 回调必须先落预览、导入只从确认动作接线（源码哨兵，防回退）。
 */
class ModImportPreviewFeedWireTest {

    @Test
    fun `选完先算预览——Reading 阶段没有预览也不可确认`() {
        val reading = ModImportFeedReducer.reduce(ModImportFeedState.Idle, ModImportFeedEvent.SelectionStarted(1))
        assertEquals(ModImportFeedState.Reading(1), reading)
        assertNull("预览还没算好，不该有可上屏预览", ModImportFeedReducer.previewOf(reading))
        assertNull("预览还没上屏，不许有任何可导入批", ModImportFeedReducer.importBatchOf(reading))
        assertEquals(ModImportPreviewPhase.READING, modImportPreviewPhaseOf(reading))
        // 后台还没算完就点「确定」= 无效事件：状态与批都不动（不抢先导入）。
        val stillReading = ModImportFeedReducer.reduce(reading, ModImportFeedEvent.Confirmed)
        assertEquals(reading, stillReading)
        assertNull(ModImportFeedReducer.importBatchOf(stillReading))
    }

    @Test
    fun `确认才放行——预览上屏后点确定才产生导入批`() {
        val tmp = Files.createTempDirectory("eratw-feed-wire").toFile()
        val preview = FeedFixture.readyPreview(tmp)
        val awaiting = FeedFixture.awaiting(7, listOf(FeedFixture.picked("口上.ERB")), preview)
        assertEquals(ModImportPreviewPhase.AWAITING_CONFIRM, modImportPreviewPhaseOf(awaiting))
        assertEquals(preview, ModImportFeedReducer.previewOf(awaiting))
        assertNull("尚未确认，导入批必须是 null", ModImportFeedReducer.importBatchOf(awaiting))
        val running = ModImportFeedReducer.reduce(awaiting, ModImportFeedEvent.Confirmed)
        val batch = ModImportFeedReducer.importBatchOf(running)
        assertNotNull("确认后必须交出待导入批", batch)
        assertEquals(listOf("口上.ERB"), batch!!.map { it.displayName })
        assertEquals(ModImportPreviewPhase.IDLE, modImportPreviewPhaseOf(running))
    }

    @Test
    fun `取消不留盘面——取消后批与预览都清掉`() {
        val tmp = Files.createTempDirectory("eratw-feed-wire").toFile()
        val awaiting = FeedFixture.awaiting(3, listOf(FeedFixture.picked("旧.ERB")), FeedFixture.readyPreview(tmp))
        val cancelled = ModImportFeedReducer.reduce(awaiting, ModImportFeedEvent.Cancelled)
        assertEquals(ModImportFeedState.Idle, cancelled)
        assertNull("取消后不许还能导入", ModImportFeedReducer.importBatchOf(cancelled))
        assertNull("取消后预览读数必须下屏", ModImportFeedReducer.previewOf(cancelled))
        // Reading 阶段取消同样回 Idle（读线程的迟到结果会被序号丢掉）。
        val reading = ModImportFeedReducer.reduce(ModImportFeedState.Idle, ModImportFeedEvent.SelectionStarted(4))
        assertEquals(ModImportFeedState.Idle, ModImportFeedReducer.reduce(reading, ModImportFeedEvent.Cancelled))
    }

    @Test
    fun `预览不可用不阻断——降级预览照样可确认导入`() {
        val unavailable = Preview.Unavailable("zip 坏了")
        val awaiting = FeedFixture.awaiting(5, listOf(FeedFixture.picked("坏.zip")), unavailable)
        assertTrue(ModImportFeedReducer.previewOf(awaiting) is Preview.Unavailable)
        assertEquals(ModImportPreviewPhase.AWAITING_CONFIRM, modImportPreviewPhaseOf(awaiting))
        val confirmed = ModImportFeedReducer.reduce(awaiting, ModImportFeedEvent.Confirmed)
        val batch = ModImportFeedReducer.importBatchOf(confirmed)
        assertNotNull("预览算不出来也不许阻断导入（强校验在导入事务里）", batch)
    }

    @Test
    fun `重选清空——旧预览下屏且上一批迟到结果被丢弃`() {
        val tmp = Files.createTempDirectory("eratw-feed-wire").toFile()
        val oldPreview = FeedFixture.readyPreview(tmp)
        val awaitingOld = FeedFixture.awaiting(1, listOf(FeedFixture.picked("旧.ERB")), oldPreview)
        val reselected = ModImportFeedReducer.reduce(awaitingOld, ModImportFeedEvent.SelectionStarted(2))
        assertEquals(ModImportFeedState.Reading(2), reselected)
        assertNull("重选后旧预览必须先作废", ModImportFeedReducer.previewOf(reselected))
        assertNull(ModImportFeedReducer.importBatchOf(reselected))
        // 旧批（selectionId=1）的结果迟到：按序号丢弃，状态原地不动。
        val stale = ModImportFeedReducer.reduce(
            reselected,
            ModImportFeedEvent.PreviewComputed(1, listOf(FeedFixture.picked("旧.ERB")), emptyList(), oldPreview),
        )
        assertEquals(reselected, stale)
        assertNull(ModImportFeedReducer.previewOf(stale))
        // 新批结果到达才推进到待确认。
        val fresh = ModImportFeedReducer.reduce(
            reselected,
            ModImportFeedEvent.PreviewComputed(2, listOf(FeedFixture.picked("新.ERB")), emptyList(), oldPreview),
        )
        assertEquals(ModImportPreviewPhase.AWAITING_CONFIRM, modImportPreviewPhaseOf(fresh))
    }

    @Test
    fun `预览后台体——空选择与坏 zip 只降级成 Unavailable 不抛`() {
        val tmp = Files.createTempDirectory("eratw-feed-wire").toFile()
        val rt2Dir = FeedFixture.rt2DirOf(tmp)
        val zh = ModImportCopy.ZH
        val readError = zh.fill(zh.sourceReadFailedFormat, "坏.ERB")
        val empty = computeModImportPreviewSafely(emptyList(), listOf(readError), rt2Dir, zh)
        assertTrue("一个都读不出来必须是 Unavailable，实为 $empty", empty is Preview.Unavailable)
        // 上屏形态：一行「预览不可用：<原因>（不影响导入…）」，绝不抛、绝不吞。
        val lines = ModImportPreview.previewLines(empty, zh)
        assertEquals(1, lines.size)
        assertTrue("必须点名预览不可用：$lines", lines[0].contains("预览不可用"))
        assertTrue("必须声明不阻断导入：$lines", lines[0].contains("不影响导入"))
        // 坏 zip：compute 内部已折 Unavailable，安全体照样不抛。
        val broken = computeModImportPreviewSafely(
            listOf(PickedModFile("坏.zip", ByteArray(8) { 7 })),
            emptyList(),
            rt2Dir,
            zh,
        )
        assertTrue("坏 zip 必须只降级不抛，实为 $broken", broken is Preview.Unavailable)
    }

    @Test
    fun `无全局单例——两路喂入互不影响`() {
        val a = FeedFixture.awaiting(11, listOf(FeedFixture.picked("A.ERB")), Preview.Unavailable("a"))
        val b = ModImportFeedReducer.reduce(ModImportFeedState.Idle, ModImportFeedEvent.SelectionStarted(12))
        val aRunning = ModImportFeedReducer.reduce(a, ModImportFeedEvent.Confirmed)
        assertNotNull("A 确认后放行", ModImportFeedReducer.importBatchOf(aRunning))
        assertEquals(ModImportFeedState.Reading(12), b)
        assertNull("B 不受 A 影响，仍是 Reading 无批", ModImportFeedReducer.importBatchOf(b))
        // 纯函数：同一输入两次转移结果逐字相同（没有藏在 reducer 里的可变状态）。
        assertEquals(
            ModImportFeedReducer.reduce(b, ModImportFeedEvent.Cancelled),
            ModImportFeedReducer.reduce(b, ModImportFeedEvent.Cancelled),
        )
    }

    @Test
    fun `喂入点存在——SAF 回调先进预览，导入只在确认后接线`() {
        val src = FeedFixture.settingsSource()
        assertTrue("SAF 回调必须先进预览，实为无 startModImportPreview(uris)", src.contains("startModImportPreview(uris)"))
        assertFalse("SAF 回调不许绕过预览直接进导入", src.contains("importMods(uris)"))
        assertTrue("确认按钮必须接 confirmModImport", src.contains("onConfirmImport = ::confirmModImport"))
        assertTrue("导入唯一放行口必须是 importBatchOf", src.contains("ModImportFeedReducer.importBatchOf"))
        assertTrue("重选 / 关页必须清盘面（PreviewRefreshed(null)）", src.contains("PreviewRefreshed(null)"))
    }
}

/** 喂入单测夹具（临时目录 + 一次合法源包的预览 + 宿主线迹读面；函数数压在 detekt 每对象 11 条内）。 */
private object FeedFixture {

    fun picked(name: String): PickedModFile = PickedModFile(name, "RETURN 0\n".toByteArray())

    fun rt2DirOf(tmp: File): File = File(File(tmp, "mods"), "rt2")

    /** 一次真算的预览（合法 `.ERB` 源包 ⇒ Ready；用于区分「有待确认预览」与「没有」）。 */
    fun readyPreview(tmp: File): Preview =
        computeModImportPreviewSafely(listOf(picked("口上.ERB")), emptyList(), rt2DirOf(tmp), ModImportCopy.ZH)

    fun awaiting(selectionId: Long, picked: List<PickedModFile>, preview: Preview): ModImportFeedState =
        ModImportFeedReducer.reduce(
            ModImportFeedReducer.reduce(ModImportFeedState.Idle, ModImportFeedEvent.SelectionStarted(selectionId)),
            ModImportFeedEvent.PreviewComputed(selectionId, picked, emptyList(), preview),
        )

    /** 宿主 `SettingsActivity.kt` 去掉注释后的源码行迹（找不到目录就抛，不静默跳过）。 */
    fun settingsSource(): String {
        val dir = listOf(
            File("src/main/kotlin/com/eratw/port/android"),
            File("app/src/main/kotlin/com/eratw/port/android"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）")
        return File(dir, "SettingsActivity.kt").readLines().filterNot { raw ->
            val t = raw.trim()
            t.startsWith("//") || t.startsWith("/*") || t.startsWith("*/") || t.startsWith("*")
        }.joinToString("\n")
    }
}
