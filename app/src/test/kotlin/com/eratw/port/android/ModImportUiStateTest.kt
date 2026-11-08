package com.eratw.port.android

import com.eratw.port.core.loader.ModPatchLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R7-C5 导入页状态机的三态钉子（CONTRACT-R7-C §2/C5 出口判据①：编译失败 / 身份不符 /
 * 存储拒绝各有 UI 状态；②：删除补丁后的回退路径展示）。
 *
 * 全部纯函数（[ModImportUiReducer.reduce]），不碰 Compose：UI 只渲染状态，判定不进 UI 层。
 */
class ModImportUiStateTest {

    private val report = ModPatchReportSnapshot.of(
        loadedPatches = listOf(ModPatchLoader.LoadedPatch("KOJO.patch", 3, 42, listOf("B_HELLO"))),
        failures = emptyList(),
        warnings = emptyList(),
        rolledBack = false,
        storedPatchFiles = listOf("KOJO.patch"),
    )

    private val rollbackReport = ModPatchReportSnapshot.of(
        loadedPatches = emptyList(),
        failures = listOf("KOJO.patch: 补丁与 base 身份不符（拒绝装载，请用当前 base 重编补丁）"),
        warnings = emptyList(),
        rolledBack = true,
        storedPatchFiles = listOf("KOJO.patch"),
    )

    // ------------------------------------------------------------------
    // 成功路径
    // ------------------------------------------------------------------

    @Test
    fun `导入开始进入进行态并保留旧报告`() {
        val s0 = ModImportUiState.Idle(report)
        val s1 = ModImportUiReducer.reduce(s0, ModImportEvent.ImportStarted(2))
        assertTrue(s1 is ModImportUiState.Importing)
        assertEquals(2, (s1 as ModImportUiState.Importing).sourceCount)
        assertEquals("进行中不丢报告快照", report, s1.report)
    }

    @Test
    fun `导入成功进入成功态并刷新报告`() {
        val detail = ImportedPatchDetail(
            patchFileName = "KOJO.patch",
            functionCount = 1,
            instructionCount = 7,
            entryFunction = "B_HELLO",
            sourceFileNames = listOf("KOJO.ERB"),
            parseWarnings = emptyList(),
        )
        val s1 = ModImportUiReducer.reduce(
            ModImportUiState.Importing(1, "编译中", report),
            ModImportEvent.ImportSucceeded(detail, report),
        )
        assertTrue(s1 is ModImportUiState.Succeeded)
        assertEquals(detail, (s1 as ModImportUiState.Succeeded).detail)
    }

    // ------------------------------------------------------------------
    // 失败三态（编译 / 身份 / 存储 + 内部兜底）
    // ------------------------------------------------------------------

    @Test
    fun `导入失败进入失败态且报错文案原样保留`() {
        val message = "补丁不能声明全局 #DIM（…）：KOJO_BAD.ERB:1 #DIM GLOBAL_COUNT = 5"
        val s1 = ModImportUiReducer.reduce(
            ModImportUiState.Importing(1, "编译中", report),
            ModImportEvent.ImportFailed(ModImportFailureKind.COMPILE, message, report),
        )
        val failed = s1 as ModImportUiState.Failed
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertEquals("编译错误全文上屏（禁静默截断）", message, failed.message)
    }

    @Test
    fun `四类失败都有各自的报错页标题`() {
        assertEquals("补丁编译失败（未落盘）", modImportFailureTitle(ModImportFailureKind.COMPILE))
        assertEquals("补丁与游戏本体不符", modImportFailureTitle(ModImportFailureKind.IDENTITY))
        assertEquals("存储失败", modImportFailureTitle(ModImportFailureKind.STORAGE))
        assertEquals("内部错误", modImportFailureTitle(ModImportFailureKind.UNEXPECTED))
    }

    // ------------------------------------------------------------------
    // 回退展示（I5 全或无的 UI 面）
    // ------------------------------------------------------------------

    @Test
    fun `回退报告快照——装载失败清单与回退标志逐项保留`() {
        assertTrue(rollbackReport.rolledBack)
        assertEquals("回退时已装载清单全空（I5 的 UI 呈现）", 0, rollbackReport.loadedPatches.size)
        assertEquals(1, rollbackReport.failures.size)
        assertTrue("失败原因可定位（I7）", rollbackReport.failures[0].contains("身份不符"))
        assertEquals("静态清单不受装载回退影响", listOf("KOJO.patch"), rollbackReport.storedPatchFiles)
    }

    @Test
    fun `报告快照构造——I7 读数投影逐项一致`() {
        val snapshot = ModPatchReportSnapshot.of(
            loadedPatches = listOf(ModPatchLoader.LoadedPatch("A.patch", 2, 10, emptyList())),
            failures = listOf("B.patch: 头解析失败"),
            warnings = listOf("A.patch: 形参名缺失"),
            rolledBack = false,
            storedPatchFiles = listOf("A.patch", "B.patch"),
        )
        assertEquals(1, snapshot.loadedPatches.size)
        assertEquals("A.patch", snapshot.loadedPatches[0].name)
        assertEquals(2, snapshot.loadedPatches[0].functionCount)
        assertEquals(10, snapshot.loadedPatches[0].instructionCount)
        assertEquals(listOf("B.patch: 头解析失败"), snapshot.failures)
        assertFalse(snapshot.rolledBack)
    }

    @Test
    fun `读取失败快照——错误原样上屏不静默`() {
        val snapshot = ModPatchReportSnapshot.unreadable("mods 路径不是目录：/tmp/x")
        assertEquals(0, snapshot.loadedPatches.size)
        assertTrue(snapshot.readError!!.contains("不是目录"))
    }

    // ------------------------------------------------------------------
    // 刷新语义（不覆盖进行中 / 结果态）
    // ------------------------------------------------------------------

    @Test
    fun `报告刷新只换快照不覆盖失败内容`() {
        val s0 = ModImportUiState.Failed(ModImportFailureKind.STORAGE, "落盘失败", null)
        val s1 = ModImportUiReducer.reduce(s0, ModImportEvent.ReportRefreshed(report))
        assertTrue(s1 is ModImportUiState.Failed)
        assertEquals("落盘失败", (s1 as ModImportUiState.Failed).message)
        assertEquals(report, s1.report)
    }

    @Test
    fun `打开覆盖层只刷报告保持结果态`() {
        val detail = ImportedPatchDetail("A.patch", 1, 2, "B_HELLO", listOf("A.ERB"), emptyList())
        val s0 = ModImportUiState.Succeeded(detail, null)
        val s1 = ModImportUiReducer.reduce(s0, ModImportEvent.OverlayOpened(report))
        assertTrue(s1 is ModImportUiState.Succeeded)
        assertEquals(report, (s1 as ModImportUiState.Succeeded).report)
        assertEquals(detail, (s1 as ModImportUiState.Succeeded).detail)
        val idle = ModImportUiReducer.reduce(ModImportUiState.Idle(null), ModImportEvent.OverlayOpened(null))
        assertNull((idle as ModImportUiState.Idle).report)
    }

    // ------------------------------------------------------------------
    // 设置页入口卡摘要（R7-D1 任务①）
    // ------------------------------------------------------------------

    @Test
    fun `入口卡摘要——未导入为空其余各态一行`() {
        assertNull("未导入过 = 无摘要行", modImportCardSummary(ModImportUiState.Idle(null)))
        assertEquals(
            "进行中带源文件数（与覆盖层的同一括注口径）",
            "正在编译并落盘补丁…（2 个文件）",
            modImportCardSummary(ModImportUiState.Importing(2, "正在编译并落盘补丁…", null)),
        )
        assertEquals(
            "上次导入成功：KOJO.patch（重启游戏后生效）",
            modImportCardSummary(
                ModImportUiState.Succeeded(
                    ImportedPatchDetail("KOJO.patch", 1, 7, "B_HELLO", listOf("KOJO.ERB"), emptyList()),
                    null,
                ),
            ),
        )
        assertEquals(
            "上次导入失败：补丁编译失败（未落盘）",
            modImportCardSummary(ModImportUiState.Failed(ModImportFailureKind.COMPILE, "全文见报错页", null)),
        )
    }
}