package com.eratw.port.android

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 批导入接线哨兵（task-178 的组长接线部分）：多选必须真的走
 * [ModImportController.importBatch]，并把结果落成 [ModImportEvent.BatchImportFinished]；
 * 同时**单包入口保持原样**（既有单包调用点与单包用例零影响）。
 *
 * 为什么用源码哨兵而不是跑 UI：导入事务起在后台线程，Activity 级验证要 Robolectric/真机；
 * 而这里要钉的只是「接线没被后续重构悄悄改回单包口」，属结构约束，源码文本足够且更稳。
 *
 * 2019 重构后入口判定与事件拼装已抽到 [modImportEventFor]（`ModImportDispatcher.kt`），
 * 所以哨兵跨两个文件查：Activity 只负责线程编排 + 传快照，判定在纯函数里。
 * 断言前先剥注释、压平空白 —— 只钉「调了谁 / 发什么事件」，不被换行重排搞红。
 */
class ModImportBatchWireTest {

    private fun sourceOf(name: String): String = stripComments(
        File("src/main/kotlin/com/eratw/port/android/$name").readText(),
    ).replace(Regex("\\s+"), " ")

    private fun activity(): String = sourceOf("SettingsActivity.kt")

    private fun dispatcher(): String = sourceOf("ModImportDispatcher.kt")

    @Test
    fun `Activity 把快照交给纯函数判定且不自己拼事件`() {
        val source = activity()
        assertTrue(
            "runModImport 必须经 modImportEventFor 判定（入口分发不再是 Activity 职责）",
            source.contains("val event = modImportEventFor("),
        )
        assertTrue(
            "两条快照（mod 运行总闸 / 代码装载总闸）都必须进请求对象",
            source.contains("ModImportRequest(picked, readErrors, copy, modRuntimeOn, codeGateOn)"),
        )
        assertTrue(
            "事件只经 postModImportEvent 上屏（唯一回 UI 线程落点）",
            source.contains("postModImportEvent(event, copy)"),
        )
    }

    @Test
    fun `多选走 importBatch 并落成 BatchImportFinished`() {
        val source = dispatcher()
        assertTrue("批量判据 = 选中多于一个文件", source.contains("if (request.picked.size > 1)"))
        assertTrue(
            "批路径必须直调 importBatch（逐项复用单包 import 的唯一事务边界）",
            source.contains("controller.importBatch("),
        )
        assertTrue(
            "批量结果必须落成 BatchImportFinished（逐包结果面才有处可渲染）",
            source.contains("return ModImportEvent.BatchImportFinished(merged, report)"),
        )
    }

    @Test
    fun `单包入口保持原样`() {
        val source = dispatcher()
        assertTrue(
            "单包仍走 import(...)（既有事件语义与单包测试不受影响）",
            source.contains("controller.import(request.picked, copy, modRuntimeEnabled = request.modRuntimeEnabled)"),
        )
        assertTrue(
            "单包成功/失败事件形状不变",
            source.contains("is ModImportOutcome.Imported -> ModImportEvent.ImportSucceeded(outcome.detail, report)"),
        )
        assertTrue(
            "空选仍固定 Failed(STORAGE, allReadsFailed)（没东西可导不是静默成功）",
            source.contains("copy.fill(copy.allReadsFailedFormat, request.readErrors.joinToString(\"\\n\"))"),
        )
    }

    @Test
    fun `批级失败优先于读失败兜底`() {
        val source = dispatcher()
        // 判据顺序写死：已有批级失败时不被「读失败补一条」覆盖。
        assertTrue(
            "批级失败已存在时必须原样保留（不许被读失败兜底顶掉）",
            source.contains("val keepBatch = readFailure == null || batch.batchFailure != null"),
        )
    }

    private fun stripComments(text: String): String = text
        .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        .replace(Regex("//[^\n]*"), "")
}
