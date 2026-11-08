package com.eratw.port.android

/**
 * 一个素材包的处理结果（[com.eratw.port.platform.storage.ResourcePackStore.install] 的读数
 * + 包级错误原话）。
 *
 * 单包成功判据只有一条：**真的有文件落盘**。自动分类上线前，「整包被跳过 / 结构认不出 /
 * 坏 zip」也会走到成功分支（旧壳层按 `install` 有没有抛来 `packs++`），页面报「已导入 0 个
 * 文件」却挂着「重启游戏生效」——玩家以为装好了，游戏里什么都没有。
 */
data class ResourcePackBatchItem(
    val name: String,
    val installedFiles: Int,
    val skippedEntries: Int,
    val overwrittenFiles: Int,
    val error: String?,
) {
    /** 成功 iff [installedFiles] > 0；坏包与全跳过包都不算成功。 */
    val succeeded: Boolean get() = installedFiles > 0
}

/** 一批素材包的总体判定。 */
enum class ResourcePackBatchOutcome {
    /** 全部包成功（每个包都至少有 1 个文件落盘）。 */
    SUCCEEDED,

    /** 有成功也有失败（失败 = 坏包 / 全跳过 / 非 zip / 读不出）。 */
    PARTIAL,

    /** 0 个包成功；0 文件绝不可能落到 SUCCEEDED。 */
    FAILED,
}

/**
 * 一批导入的汇总 + 玩家面消息。
 *
 * [message] 由 [ResourcePackCopy] 模板拼出（壳层不再内联中文）；[failedLines] 是逐包失败 /
 * 全跳过明细（也已按 copy 拼好），供卡片在部分成功或全失败时照实列出原因。
 */
data class ResourcePackBatchVerdict(
    val outcome: ResourcePackBatchOutcome,
    /** 落盘文件总数。 */
    val files: Int,
    /** 成功包数（每个成功包至少 1 个文件落盘）。 */
    val packs: Int,
    /** 失败包数（坏包 / 全跳过 / 预检失败各算 1）。 */
    val failedPacks: Int,
    val overwritten: Int,
    val skipped: Int,
    val message: String,
    val failedLines: List<String>,
) {
    /** 真有文件落盘：重启按钮只在这一档出现。 */
    val hasLandedFiles: Boolean get() = files > 0
}

/**
 * 素材包批量导入的纯逻辑（可单测）：把逐包读数折成三态与汇总句。
 *
 * 判据（与 `ResourcePackStore` 的包级画像配套）：
 * 1. 单包成功 iff `installedFiles > 0`；
 * 2. `SUCCEEDED` = 全部包成功，`PARTIAL` = 有成功也有失败，`FAILED` = 0 个包成功；
 * 3. 汇总句一律走 [ResourcePackCopy] 模板；0 文件时只会落进 `FAILED`（`importedNothingFormat`）。
 */
object ResourcePackImportBatch {

    /**
     * 汇总一批结果并判定三态。
     *
     * [preflightFailures] 是**还没走到 install 就失败**的包（不是 zip / 缓存读不出或超上限），
     * 每条已经按 copy 拼好，按「一个失败包」计入 [ResourcePackBatchVerdict.failedPacks]。
     *
     * [material] = true 时失败原因用直投口径（文件 / 文件夹没有「解包」这一步）。
     */
    fun judge(
        copy: ResourcePackCopy,
        items: List<ResourcePackBatchItem>,
        preflightFailures: List<String> = emptyList(),
        material: Boolean = false,
    ): ResourcePackBatchVerdict {
        var files = 0
        var overwritten = 0
        var skipped = 0
        var packs = 0
        val failedLines = ArrayList<String>(preflightFailures)
        for (item in items) {
            files += item.installedFiles
            overwritten += item.overwrittenFiles
            skipped += item.skippedEntries
            if (item.succeeded) {
                packs++
                continue
            }
            val error = item.error
            failedLines += when {
                error == null -> copy.fill(copy.failedNoUsableEntriesFormat, item.name)
                material -> copy.fill(copy.materialImportFailedFormat, item.name, error)
                else -> copy.fill(copy.failedUnzipFormat, item.name, error)
            }
        }
        val failedPacks = failedLines.size
        val outcome = when {
            packs > 0 && failedPacks == 0 -> ResourcePackBatchOutcome.SUCCEEDED
            packs > 0 -> ResourcePackBatchOutcome.PARTIAL
            else -> ResourcePackBatchOutcome.FAILED
        }
        val message = when (outcome) {
            ResourcePackBatchOutcome.SUCCEEDED ->
                copy.fill(copy.importedSummaryFormat, files, packs, overwritten, skipped)
            ResourcePackBatchOutcome.PARTIAL ->
                copy.fill(copy.importedPartialFormat, files, packs, failedPacks)
            ResourcePackBatchOutcome.FAILED ->
                copy.fill(copy.importedNothingFormat, failedLines.joinToString("\n"))
        }
        return ResourcePackBatchVerdict(
            outcome = outcome,
            files = files,
            packs = packs,
            failedPacks = failedPacks,
            overwritten = overwritten,
            skipped = skipped,
            message = message,
            failedLines = failedLines,
        )
    }
}
