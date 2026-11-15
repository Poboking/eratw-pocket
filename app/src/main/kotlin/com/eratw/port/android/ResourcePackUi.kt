package com.eratw.port.android

import com.eratw.port.platform.storage.MaterialTarget
import com.eratw.port.platform.storage.ResourcePackCleanupReport
import com.eratw.port.platform.storage.ResourcePackStatus
import com.eratw.port.platform.storage.StructureNote
import com.eratw.port.platform.storage.StructureNoteKind

/**
 * 素材包导入的 UI 状态机（纯 Kotlin，可单测）。
 *
 * 与口上补丁导入（[ModImportUiState]）刻意分开：素材包没有编译、没有身份校验，
 * 只有「投放了多少文件 / 覆盖了多少 / 跳过了什么」三件事。状态转移全在
 * [ResourcePackUiReducer] 里，Compose 层只渲染，不做判定。
 *
 * 结果态分两段：**导入四态**（成功 / 部分成功 / 失败 / 进行中）与**清理两态**（清理中 /
 * 清理结束）。导入的「成功」由 [ResourcePackImportBatch] 判定（单包成功 iff 真有文件落盘），
 * 不是「install 没抛」；清理跑完（删没删到东西都算跑完）落 [ResourcePackUiState.Cleared]，
 * 整批没做成才复用 [ResourcePackUiState.Failed]。两段都带一份现状读数：「页面上已导入什么」
 * 只由现状读数负责，结果态一律不许把它清空。
 */

/** 一次导入的汇总（多选 zip 时逐包累加；[message] 已按当前语言拼好）。 */
data class ResourcePackImportSummary(
    /** 成功包数（每个成功包至少 1 个文件落盘）。 */
    val packs: Int,
    /** 失败包数（坏包 / 全跳过 / 非 zip / 读不出）。 */
    val failedPacks: Int,
    /** 落盘文件总数。 */
    val files: Int,
    val overwritten: Int,
    val skipped: Int,
    /** 逐条跳过样本（取证原话，展示时套 [ResourcePackCopy.skippedLineFormat]）。 */
    val skippedSamples: List<String>,
    /** 结构体检警示行（已按 [ResourcePackCopy] 拼好的玩家句）。 */
    val warningLines: List<String>,
    /** 汇总句（已按 [ResourcePackCopy] 拼好，成功态与部分成功态共用这条展示）。 */
    val message: String,
) {
    /** 真有文件落盘：重启按钮只在这一档出现。 */
    val hasLandedFiles: Boolean get() = files > 0
}

/**
 * 一次「清理已导入素材」的汇总（[message] 与 [warningLines] 已按当前语言拼好）。
 *
 * 与 [ResourcePackImportSummary] 分开收：清理的读数维度不同（删了多少 / 还原了多少 /
 * 剪了多少空目录 / 几条失败），并进导入汇总会让两边的字段互相将就。[failedDeletions]
 * 只报真实总数，逐条取证留在 [warningLines] 里（展示时按条数截断）。
 */
data class ResourcePackCleanupSummary(
    /** 删掉的玩家导入文件数。 */
    val deletedFiles: Int,
    /** 删掉的字节数（按删除前的原字节数累加）。 */
    val deletedBytes: Long,
    /** 被导入覆盖、已按随包原字节还原的文件数。 */
    val restoredBaseFiles: Int,
    /** 顺手剪掉的空目录数（清理范围的根目录本身不剪）。 */
    val prunedDirs: Int,
    /** 删除失败条数（真实总数，不随展示截断）。 */
    val failedDeletions: Int,
    /** 汇总句（已按 [ResourcePackCopy] 拼好）。 */
    val message: String,
    /** 提示 / 取证行（已按 [ResourcePackCopy] 拼好的玩家句，可截断）。 */
    val warningLines: List<String>,
)

/** 状态里始终带一份现状读数：[status] 为 null = 读不出来（页面按「未知」呈现，不假装全零）。 */
sealed interface ResourcePackUiState {
    val status: ResourcePackStatus?

    data class Idle(override val status: ResourcePackStatus?) : ResourcePackUiState

    data class Importing(
        val sourceCount: Int,
        override val status: ResourcePackStatus?,
    ) : ResourcePackUiState

    data class Succeeded(
        val summary: ResourcePackImportSummary,
        override val status: ResourcePackStatus?,
    ) : ResourcePackUiState

    /**
     * 部分成功：有包真的落盘、也有包一个文件都没进来（坏包 / 全跳过 / 读不出）。
     * 状态仍要带 [summary]（含落盘文件数）与 [failedPacks]，页面才会把「成功的那部分」
     * 和「失败的那部分」分开说，而不是笼统报失败或成功。
     */
    data class Partial(
        val summary: ResourcePackImportSummary,
        val failedPacks: Int,
        override val status: ResourcePackStatus?,
    ) : ResourcePackUiState

    /**
     * 导入失败（0 个包成功）：整包打不开 / 读不出 SAF 流 / 中途超出上限。[skippedSamples]
     * 保留逐条跳过原因——页面要照实说，不能报成「什么都没发生」。
     */
    data class Failed(
        val message: String,
        val skippedSamples: List<String>,
        override val status: ResourcePackStatus?,
    ) : ResourcePackUiState

    /**
     * 清理进行态：与 [Importing] 同一纪律，保留旧读数——清理只影响结果区，不许把页面上
     * 「已导入什么」一起清空。
     */
    data class Clearing(override val status: ResourcePackStatus?) : ResourcePackUiState

    /**
     * 清理结束（删到东西 / 没删到东西都落这里）：[summary] 带读数与已拼好的玩家句。
     * 整批没做成（基准清单读不到等）不在这里造新的失败态，走既有 [Failed]。
     */
    data class Cleared(val summary: ResourcePackCleanupSummary, override val status: ResourcePackStatus?) : ResourcePackUiState
}

sealed interface ResourcePackEvent {
    data class ImportStarted(val sourceCount: Int) : ResourcePackEvent
    data class ImportSucceeded(val summary: ResourcePackImportSummary, val status: ResourcePackStatus?) : ResourcePackEvent
    data class ImportPartial(
        val summary: ResourcePackImportSummary,
        val failedPacks: Int,
        val status: ResourcePackStatus?,
    ) : ResourcePackEvent
    data class ImportFailed(
        val message: String,
        val skippedSamples: List<String>,
        val status: ResourcePackStatus?,
    ) : ResourcePackEvent
    /** 清理开始（确认框点「清除」之后、线程还没出结果）。 */
    data object ClearStarted : ResourcePackEvent
    /** 清理跑完：删没删到东西都算跑完，读数在 [summary] 里。 */
    data class ClearSucceeded(val summary: ResourcePackCleanupSummary, val status: ResourcePackStatus?) : ResourcePackEvent
    /** 整批没做成（例如基准清单读不到）：[message] 是已拼好的玩家句。 */
    data class ClearFailed(val message: String, val status: ResourcePackStatus?) : ResourcePackEvent
    data class StatusRefreshed(val status: ResourcePackStatus?) : ResourcePackEvent
}

object ResourcePackUiReducer {

    fun reduce(state: ResourcePackUiState, event: ResourcePackEvent): ResourcePackUiState = when (event) {
        is ResourcePackEvent.ImportStarted -> ResourcePackUiState.Importing(event.sourceCount, state.status)
        is ResourcePackEvent.ImportSucceeded -> ResourcePackUiState.Succeeded(event.summary, event.status)
        is ResourcePackEvent.ImportPartial ->
            ResourcePackUiState.Partial(event.summary, event.failedPacks, event.status)
        is ResourcePackEvent.ImportFailed -> ResourcePackUiState.Failed(event.message, event.skippedSamples, event.status)
        is ResourcePackEvent.ClearStarted -> ResourcePackUiState.Clearing(state.status)
        is ResourcePackEvent.ClearSucceeded -> ResourcePackUiState.Cleared(event.summary, event.status)
        // 整批没做成复用既有失败态：不新造「清理失败」态，页面只学一套失败渲染。
        is ResourcePackEvent.ClearFailed -> ResourcePackUiState.Failed(event.message, emptyList(), event.status)
        is ResourcePackEvent.StatusRefreshed -> when (state) {
            is ResourcePackUiState.Idle -> state.copy(status = event.status)
            is ResourcePackUiState.Importing -> state.copy(status = event.status)
            is ResourcePackUiState.Succeeded -> state.copy(status = event.status)
            is ResourcePackUiState.Partial -> state.copy(status = event.status)
            is ResourcePackUiState.Failed -> state.copy(status = event.status)
            is ResourcePackUiState.Clearing -> state.copy(status = event.status)
            is ResourcePackUiState.Cleared -> state.copy(status = event.status)
        }
    }
}

/**
 * 清理确认框正文（按清理范围出词）。
 *
 * 句子全部来自 [ResourcePackCopy]：壳层只按玩家点的是哪张卡（[MaterialTarget]）挑模板，
 * 不自己拼字。两条正文各自点名盘上真实投放根（背景图是 resources/背景图/ 与 自动索引.csv，
 * BGM 是 sound/），玩家才能对上自己当初在哪张卡导入的。
 *
 * 平台层清理读数 -> 玩家事件那一步（`cleanupEvent`）不在这里：它吃平台层的清理报告对象，
 * 与本文件的纯状态机解耦，由接线侧在平台侧清理落地后补上。
 */
fun clearConfirmBody(copy: ResourcePackCopy, target: MaterialTarget): String = when (target) {
    MaterialTarget.BACKGROUND -> copy.clearConfirmBackgroundBody
    MaterialTarget.BGM -> copy.clearConfirmBgmBody
}
/** 清理失败明细最多上屏几条；真实总数留在 [ResourcePackCleanupSummary.failedDeletions]。 */
private const val CLEANUP_NOTE_LINES_MAX = 8

/**
 * 平台清理读数 -> 玩家面事件（壳层只转发，不拼字）。
 *
 * 三条判据都是「照实说」：
 * 1. [ResourcePackCleanupReport.error] 非空 = 整批一个字节都没动（例如盘上摘要读不到、assets
 *    也证明不了基准清单）⇒ 走 [ResourcePackEvent.ClearFailed]（复用既有失败态），绝不报成成功；
 * 2. 删了 0 个文件、也没还原任何文件 ⇒ [ResourcePackCopy.clearNothingFormat]，而不是「成功清理
 *    了 0 个」（玩家会以为自己点坏了）；
 * 3. 明细两类分开：被还原的随包文件（好事，说明导入覆盖过随包素材）走
 *    [ResourcePackCopy.clearRestoredFormat]，逐条失败取证走 [ResourcePackCopy.skippedLineFormat]；
 *    展示最多 [CLEANUP_NOTE_LINES_MAX] 条，[ResourcePackCleanupSummary.failedDeletions] 报真实总数。
 *
 * @param status 清理后重扫的现状读数（ResourcePackStore.scanStatus），null = 这次读不出来
 */
fun cleanupEvent(
    copy: ResourcePackCopy,
    report: ResourcePackCleanupReport,
    status: ResourcePackStatus?,
): ResourcePackEvent {
    val error = report.error
    if (error != null) {
        return ResourcePackEvent.ClearFailed(copy.fill(copy.clearFailedFormat, error), status)
    }
    val message = if (report.deletedFiles == 0 && report.restoredBaseFiles == 0) {
        copy.clearNothingFormat
    } else {
        copy.fill(copy.clearSucceededFormat, report.deletedFiles, report.prunedDirs)
    }
    val warnings = ArrayList<String>()
    if (report.restoredBaseFiles > 0) {
        warnings += copy.fill(
            copy.noteLineFormat,
            copy.fill(copy.clearRestoredFormat, report.restoredBaseFiles),
        )
    }
    for (sample in report.failures.take(CLEANUP_NOTE_LINES_MAX)) {
        warnings += copy.fill(copy.skippedLineFormat, sample)
    }
    return ResourcePackEvent.ClearSucceeded(
        summary = ResourcePackCleanupSummary(
            deletedFiles = report.deletedFiles,
            deletedBytes = report.deletedBytes,
            restoredBaseFiles = report.restoredBaseFiles,
            prunedDirs = report.prunedDirs,
            failedDeletions = report.failures.size,
            message = message,
            warningLines = warnings,
        ),
        status = status,
    )
}

/**
 * 结构体检结论 → 玩家面警示行。
 *
 * `ResourcePackStore` 的 [StructureNote] 只带 kind + 取证路径原话；句子全部来自
 * [ResourcePackCopy]，本函数只做「kind → 模板键」的映射，取证原话作为 `{0}` 实参灌入
 * （[StructureNoteKind.UNRECOGNIZED_LAYOUT] 的模板无槽位，原话只留在报告对象里）。
 * 每行再套 [ResourcePackCopy.noteLineFormat]，与其它「提示/说明」行同一视觉口径。
 */
fun structureWarningLines(copy: ResourcePackCopy, notes: List<StructureNote>): List<String> =
    notes.map { note ->
        val sentence = when (note.kind) {
            StructureNoteKind.BGM_OUTSIDE_GAME_BGM ->
                copy.fill(copy.structureBgmOutsideFormat, note.detail)
            StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX ->
                copy.fill(copy.structureImagesNoIndexFormat, note.detail)
            StructureNoteKind.EMPTY_CSV_INDEX ->
                copy.fill(copy.structureEmptyCsvFormat, note.detail)
            StructureNoteKind.BGM_FALLBACK_LOCATION ->
                copy.fill(copy.bgmFallbackLocationFormat, note.detail)
            StructureNoteKind.BGM_TRACKS_OVERFLOW ->
                copy.fill(copy.bgmTracksOverflowFormat, note.detail)
            StructureNoteKind.MATERIAL_NO_USABLE_FILES ->
                copy.fill(copy.materialNoUsableFilesFormat, note.detail)
            StructureNoteKind.BACKGROUND_INDEX_ROW_SKIPPED ->
                copy.fill(copy.backgroundIndexRowSkippedFormat, note.detail)
            StructureNoteKind.MATERIAL_IGNORED_FILES ->
                copy.fill(copy.materialIgnoredFilesFormat, note.detail)
            StructureNoteKind.UNRECOGNIZED_LAYOUT -> copy.structureUnrecognizedText
        }
        copy.fill(copy.noteLineFormat, sentence)
    }
