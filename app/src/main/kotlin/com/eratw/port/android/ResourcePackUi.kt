package com.eratw.port.android

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
 * 结果态是**四态**：成功 / 部分成功 / 失败 / 进行中。「成功」由
 * [ResourcePackImportBatch] 判定（单包成功 iff 真有文件落盘），不是「install 没抛」。
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
    data class StatusRefreshed(val status: ResourcePackStatus?) : ResourcePackEvent
}

object ResourcePackUiReducer {

    fun reduce(state: ResourcePackUiState, event: ResourcePackEvent): ResourcePackUiState = when (event) {
        is ResourcePackEvent.ImportStarted -> ResourcePackUiState.Importing(event.sourceCount, state.status)
        is ResourcePackEvent.ImportSucceeded -> ResourcePackUiState.Succeeded(event.summary, event.status)
        is ResourcePackEvent.ImportPartial ->
            ResourcePackUiState.Partial(event.summary, event.failedPacks, event.status)
        is ResourcePackEvent.ImportFailed -> ResourcePackUiState.Failed(event.message, event.skippedSamples, event.status)
        is ResourcePackEvent.StatusRefreshed -> when (state) {
            is ResourcePackUiState.Idle -> state.copy(status = event.status)
            is ResourcePackUiState.Importing -> state.copy(status = event.status)
            is ResourcePackUiState.Succeeded -> state.copy(status = event.status)
            is ResourcePackUiState.Partial -> state.copy(status = event.status)
            is ResourcePackUiState.Failed -> state.copy(status = event.status)
        }
    }
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
