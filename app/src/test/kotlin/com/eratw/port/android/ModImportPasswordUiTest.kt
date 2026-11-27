package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码补救面的判据（`ModImportPasswordUi.needsImportPassword`）：**纯函数**，逐态断言。
 *
 * 为什么值得单测：它是「这一屏要不要出密码框」的唯一裁决点，判错的两个方向都难在真机上
 * 看到——判宽了会在编译失败页挂一个无用的密码框（玩家以为要密码），判窄了会让加密包
 * 永远停在「解包失败」（本波要修的就是这个）。
 */
class ModImportPasswordUiTest {

    private val detail = ImportedPatchDetail(
        patchFileName = "kojo-test.erbm",
        functionCount = 1,
        instructionCount = 1,
        entryFunction = "EVENTTEST",
        sourceFileNames = listOf("M_KOJO_K99.ERB"),
        parseWarnings = emptyList(),
    )

    @Test
    fun `单包失败页只在 PASSWORD 类出密码框`() {
        assertTrue(needsImportPassword(failed(ModImportFailureKind.PASSWORD)))
        assertFalse(needsImportPassword(failed(ModImportFailureKind.STORAGE)))
        assertFalse(needsImportPassword(failed(ModImportFailureKind.COMPILE)))
        assertFalse(needsImportPassword(failed(ModImportFailureKind.IDENTITY)))
        assertFalse(needsImportPassword(failed(ModImportFailureKind.UNEXPECTED)))
    }

    @Test
    fun `批导入按任一项判——加密包混在多选里也给补救口`() {
        val mixed = ModImportBatchOutcome(
            items = listOf(
                ModImportBatchOutcome.Item("ok.zip", ModImportOutcome.Imported(detail)),
                ModImportBatchOutcome.Item("pwd.zip", failedOutcome(ModImportFailureKind.PASSWORD)),
            ),
        )
        assertTrue(needsImportPassword(ModImportUiState.BatchFinished(mixed, null)))
        val clean = ModImportBatchOutcome(
            items = listOf(ModImportBatchOutcome.Item("ok.zip", ModImportOutcome.Imported(detail))),
        )
        assertFalse(needsImportPassword(ModImportUiState.BatchFinished(clean, null)))
    }

    @Test
    fun `进行中与成功态永不出密码框`() {
        assertFalse(needsImportPassword(ModImportUiState.Idle(null)))
        assertFalse(needsImportPassword(ModImportUiState.Importing(1, "编译中", null)))
        assertFalse(needsImportPassword(ModImportUiState.Succeeded(detail, null)))
    }

    private fun failedOutcome(kind: ModImportFailureKind) = ModImportOutcome.Failed(kind, "文案占位")

    private fun failed(kind: ModImportFailureKind) = ModImportUiState.Failed(kind, "文案占位", null)
}
