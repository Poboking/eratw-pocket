package com.eratw.port.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.EratwText
import com.eratw.port.android.ui.EratwTextTone

/**
 * 批量导入结果面（task-178）：行模型 + 纯函数行序 + 渲染块。
 *
 * 单独成文件是为了不把 [ModImportUi] 那份状态机 / 渲染文件的顶层函数数顶过 detekt 的
 * 文件级上限（该文件的既有函数已按签名冻结在 baseline，禁放宽闸门）；本文件只依赖
 * [ModImportBatchOutcome] 与既有文案表，不碰单包导入链。
 *
 * task-183 起再加一层「上屏投影」：整批 30+ 项时卡片不再逐项全列，而是成功明细最多 5 条、
 * 失败明细最多 8 条（批级失败本就至多 1 条），超出的折成一行可点按展开的摘要；失败 message
 * 压成单行并截到 200 字符保尾部省略号。折叠态只活在本文件的 Composable `remember` 里，
 * **不进 reducer**：换一批结果（[outcome] 变）自动回到首屏折叠态。
 */

/** 成功明细上屏上限：超出部分折成一行摘要（task-183）。 */
private const val BATCH_RESULT_MAX_SUCCEEDED = 5

/** 失败明细上屏上限：超出部分折成一行摘要（task-183）。 */
private const val BATCH_RESULT_MAX_FAILED = 8

/** 失败 message 上屏上限：单行、总量 200 字符（含尾部省略号）。 */
private const val BATCH_RESULT_FAILED_MESSAGE_MAX = 200

/** 明细滚动区高度上限：超出只滚明细区，重启键留在卡内固定位置（task-183）。 */
private val BATCH_RESULT_CONTENT_MAX_HEIGHT = 240.dp

/** 被折叠的是哪一类明细；两类各自记住展开态（互不牵连）。 */
internal enum class ModImportBatchOverflowKind { SUCCEEDED, FAILED }

/**
 * 批量导入结果面的一行（[BatchResultBlock] 只按这个顺序渲染，判定与归类不在 Composable 里）：
 * 汇总行固定在首；逐项行严格按 [ModImportBatchOutcome.items] 的顺序（= 选中序）；批级失败垫尾。
 */
internal sealed interface ModImportBatchResultLine {
    /** 总体计数行「N 成功 / M 失败」。 */
    data class Summary(val succeeded: Int, val failed: Int) : ModImportBatchResultLine

    /** 一个好包。 */
    data class Succeeded(val zipName: String) : ModImportBatchResultLine

    /** 一个坏包：归类标题 + 该项自己的 message 全文（不截断）。 */
    data class Failed(val zipName: String, val title: String, val message: String) : ModImportBatchResultLine

    /** 批级失败（空选择 / 循环本身异常；已完成项的行仍在它前面）。 */
    data class BatchFailed(val title: String, val message: String) : ModImportBatchResultLine

    /**
     * 某一类明细超上限时的折叠 / 收起行（task-183）：点按切本类展开态。
     * 折叠态 [hiddenCount] = 未显示的项数；展开态 [hiddenCount] = 0，文案换成「收起」。
     */
    data class Overflow(
        val kind: ModImportBatchOverflowKind,
        val hiddenCount: Int,
        val expanded: Boolean,
    ) : ModImportBatchResultLine
}

/**
 * 批量结果 → 行序列（纯函数、顺序稳定）：汇总行 + 逐项行（选中序）+ 批级失败行。
 * 逐项标题用 [modImportFailureTitle]（与单包报错页同一个归类口径，不另造分类）。
 *
 * **不设上限**：这是 reducer 语义面的完整行序，既有对拍与单包路径都靠它；上屏折叠见
 * [modImportBatchResultDisplayLines]。
 */
internal fun modImportBatchResultLines(
    outcome: ModImportBatchOutcome,
    copy: ModImportCopy = ModImportCopy.ZH,
): List<ModImportBatchResultLine> {
    val lines = ArrayList<ModImportBatchResultLine>(outcome.items.size + 2)
    lines.add(ModImportBatchResultLine.Summary(outcome.succeededCount, outcome.failedCount))
    for (item in outcome.items) {
        when (val itemOutcome = item.outcome) {
            is ModImportOutcome.Imported -> lines.add(ModImportBatchResultLine.Succeeded(item.zipName))
            is ModImportOutcome.Failed -> lines.add(
                ModImportBatchResultLine.Failed(
                    zipName = item.zipName,
                    title = modImportFailureTitle(itemOutcome.kind, copy),
                    message = itemOutcome.message,
                ),
            )
        }
    }
    outcome.batchFailure?.let {
        lines.add(ModImportBatchResultLine.BatchFailed(modImportFailureTitle(it.kind, copy), it.message))
    }
    return lines
}

/**
 * 上屏投影（task-183）：在 [modImportBatchResultLines] 的完整行序上做两件事——
 *
 * 1. 成功明细超过 [BATCH_RESULT_MAX_SUCCEEDED] 条的部分、失败明细超过 [BATCH_RESULT_MAX_FAILED]
 *    条的部分不逐项上屏，合成一条 [ModImportBatchResultLine.Overflow]；[showAllSucceeded] /
 *    [showAllFailed] 为 true 时该类全部上屏，摘要行变成「收起」开关。
 * 2. 失败 message（逐项与批级）走 [truncateBatchResultMessage]：单行 + 200 字符保尾省略号。
 *
 * 逐项行仍按选中序交叉保留（不按成功 / 失败分组重排）；折叠态是调用方（Composable）的
 * 临时观感，本函数纯函数、无状态。
 */
internal fun modImportBatchResultDisplayLines(
    outcome: ModImportBatchOutcome,
    copy: ModImportCopy = ModImportCopy.ZH,
    showAllSucceeded: Boolean = false,
    showAllFailed: Boolean = false,
): List<ModImportBatchResultLine> {
    val projection = projectBatchResultRows(outcome, copy, showAllSucceeded, showAllFailed)
    val lines = ArrayList<ModImportBatchResultLine>()
    lines.add(ModImportBatchResultLine.Summary(outcome.succeededCount, outcome.failedCount))
    lines.addAll(projection.rows)
    if (outcome.succeededCount > BATCH_RESULT_MAX_SUCCEEDED) {
        lines.add(
            ModImportBatchResultLine.Overflow(
                kind = ModImportBatchOverflowKind.SUCCEEDED,
                hiddenCount = projection.succeededHidden,
                expanded = showAllSucceeded,
            ),
        )
    }
    if (outcome.failedCount > BATCH_RESULT_MAX_FAILED) {
        lines.add(
            ModImportBatchResultLine.Overflow(
                kind = ModImportBatchOverflowKind.FAILED,
                hiddenCount = projection.failedHidden,
                expanded = showAllFailed,
            ),
        )
    }
    outcome.batchFailure?.let {
        lines.add(
            ModImportBatchResultLine.BatchFailed(
                title = modImportFailureTitle(it.kind, copy),
                message = truncateBatchResultMessage(it.message),
            ),
        )
    }
    return lines
}

/**
 * 逐项行的上屏投影：按选中序交叉保留，成功 / 失败各自数到本类上限后只累计隐藏计数
 * （[showAllSucceeded] / [showAllFailed] 指定的类不封顶）。
 */
private fun projectBatchResultRows(
    outcome: ModImportBatchOutcome,
    copy: ModImportCopy,
    showAllSucceeded: Boolean,
    showAllFailed: Boolean,
): BatchDisplayProjection {
    val rows = ArrayList<ModImportBatchResultLine>(outcome.items.size)
    var succeededSeen = 0
    var failedSeen = 0
    var succeededHidden = 0
    var failedHidden = 0
    for (item in outcome.items) {
        when (val itemOutcome = item.outcome) {
            is ModImportOutcome.Imported -> {
                succeededSeen++
                if (showAllSucceeded || succeededSeen <= BATCH_RESULT_MAX_SUCCEEDED) {
                    rows.add(ModImportBatchResultLine.Succeeded(item.zipName))
                } else {
                    succeededHidden++
                }
            }
            is ModImportOutcome.Failed -> {
                failedSeen++
                if (showAllFailed || failedSeen <= BATCH_RESULT_MAX_FAILED) {
                    rows.add(
                        ModImportBatchResultLine.Failed(
                            zipName = item.zipName,
                            title = modImportFailureTitle(itemOutcome.kind, copy),
                            message = truncateBatchResultMessage(itemOutcome.message),
                        ),
                    )
                } else {
                    failedHidden++
                }
            }
        }
    }
    return BatchDisplayProjection(rows, succeededHidden, failedHidden)
}

/** 逐项上屏行的投影结果 + 两类各自被折掉的条数（供 modImportBatchResultDisplayLines 拼折叠行）。 */
private data class BatchDisplayProjection(
    val rows: List<ModImportBatchResultLine>,
    val succeededHidden: Int,
    val failedHidden: Int,
)

/**
 * 失败 message 上屏口径：先把换行 / 回车压成空格（单行），再按 [maxChars] 截断并保尾部省略号。
 *
 * 总量收口在 [maxChars]（含省略号），不是「前 N 字 + 省略号」。截断点落在代理对中间时少取一个
 * char，避免半个 emoji 上屏成替换符。短消息原样返回（只做单行化）。
 */
internal fun truncateBatchResultMessage(
    message: String,
    maxChars: Int = BATCH_RESULT_FAILED_MESSAGE_MAX,
): String {
    val singleLine = message.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
    if (singleLine.length <= maxChars) return singleLine
    val ellipsis = "…"
    val cut = singleLine.take(maxChars - ellipsis.length)
    val safe = if (cut.lastOrNull()?.isHighSurrogate() == true) cut.dropLast(1) else cut
    return safe + ellipsis
}

/** 折叠行模板只看折叠的是哪一类明细（成功 / 失败各一句，三语同形）。 */
private fun overflowFormatFor(kind: ModImportBatchOverflowKind, copy: ModImportCopy): String = when (kind) {
    ModImportBatchOverflowKind.SUCCEEDED -> copy.batchResultOverflowSucceededFormat
    ModImportBatchOverflowKind.FAILED -> copy.batchResultOverflowFailedFormat
}

/**
 * 批量结果块（task-178 / task-183）：汇总行 + 逐项行（选中序，逐类封顶）+ 折叠行 + 批级失败
 * + （有成功项时）重启键。行序 / 归类 / 封顶全在纯函数里，这里只按行类型选色调。
 *
 * 卡片布局（task-183）：明细区 `heightIn(max = 240.dp)` + `verticalScroll`，几十项也只占一屏
 * 高度；重启键在滚动区之外固定，长列表不再把它挤出屏幕。折叠 / 展开态只在本 Composable 的
 * `remember` 里，不进 reducer（换一批 [outcome] 即回折叠态）。
 */
@Composable
internal fun BatchResultBlock(outcome: ModImportBatchOutcome, onRestartGame: () -> Unit, copy: ModImportCopy) {
    var showAllSucceeded by remember(outcome) { mutableStateOf(false) }
    var showAllFailed by remember(outcome) { mutableStateOf(false) }
    val lines = modImportBatchResultDisplayLines(outcome, copy, showAllSucceeded, showAllFailed)
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = BATCH_RESULT_CONTENT_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            for (line in lines) {
                BatchResultLineRow(
                    line = line,
                    copy = copy,
                    onToggleOverflow = { kind ->
                        when (kind) {
                            ModImportBatchOverflowKind.SUCCEEDED -> showAllSucceeded = !showAllSucceeded
                            ModImportBatchOverflowKind.FAILED -> showAllFailed = !showAllFailed
                        }
                    },
                )
            }
        }
        if (outcome.succeededCount > 0) {
            Spacer(modifier = Modifier.height(6.dp))
            EratwFilledButton(text = copy.restartButton, onClick = onRestartGame)
        }
    }
}

/** 一行明细的渲染（色调口径与 task-178 一致：坏包 / 批级失败整行 danger，message 不吞）。 */
@Composable
private fun BatchResultLineRow(
    line: ModImportBatchResultLine,
    copy: ModImportCopy,
    onToggleOverflow: (ModImportBatchOverflowKind) -> Unit,
) {
    when (line) {
        is ModImportBatchResultLine.Summary -> EratwText(
            text = copy.fill(copy.batchResultSummaryFormat, line.succeeded, line.failed),
        )
        is ModImportBatchResultLine.Succeeded -> EratwText(
            text = copy.fill(copy.batchResultRowSucceededFormat, line.zipName),
            tone = EratwTextTone.NOTE,
        )
        is ModImportBatchResultLine.Failed -> {
            EratwText(
                text = copy.fill(copy.batchResultRowFailedFormat, line.zipName, line.title),
                colorOverride = EratwInk.danger(),
            )
            EratwText(text = line.message, tone = EratwTextTone.NOTE, colorOverride = EratwInk.danger())
        }
        is ModImportBatchResultLine.BatchFailed -> {
            EratwText(text = line.title, colorOverride = EratwInk.danger())
            EratwText(text = line.message, tone = EratwTextTone.NOTE, colorOverride = EratwInk.danger())
        }
        is ModImportBatchResultLine.Overflow -> EratwText(
            text = if (line.expanded) {
                copy.batchResultCollapse
            } else {
                copy.fill(overflowFormatFor(line.kind, copy), line.hiddenCount)
            },
            tone = EratwTextTone.NOTE,
            modifier = Modifier.clickable { onToggleOverflow(line.kind) },
            colorOverride = EratwInk.primaryButton(),
        )
    }
}
