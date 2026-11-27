package com.eratw.port.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.EratwText
import com.eratw.port.android.ui.EratwTextTone
import com.eratw.port.android.ui.design.ShellProgress
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellTag

/*
 * 覆盖层的四个**分区组件**（头部 / 状态签 / 进度区 / 动作行），从 ModImportChrome 拆出。
 *
 * ## 为什么单独一个文件
 * 不是为了好看，是为了「取消键点不到」那次事故不再复发：外壳把头部、进度、正文、动作
 * 四段平铺在同一个 Column 里时，中间那段会跟底部按钮抢高度约束。拆成独立组件后，每一段
 * 自己管自己的 modifier，外壳只剩下装配 —— 谁能撑多高这件事在结构上就有了单一归属。
 *
 * ## 可见性
 * 一律 internal（本包内复用，不出模块）。文案全部取自 ModImportCopy，本文件不写
 * 玩家面字面量；颜色全部走 EratwInk 语义色，不写字面色值。
 */

/** 头部：标题 + 说明句 + 右侧状态签（固定区，不随正文滚动）。 */
@Composable
internal fun ImportChromeHeader(
    state: ModImportUiState,
    text: ModImportChromeText,
    copy: ModImportCopy,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EratwText(text = copy.cardTitle, tone = EratwTextTone.HEADLINE)
            EratwText(
                text = text.intro,
                tone = EratwTextTone.NOTE,
                modifier = Modifier.padding(top = NOTE_TOP_GAP),
            )
        }
        // 状态签钉在右上：玩家一眼看到「这屏现在是哪种局面」，不必先去读正文。
        ImportStateTag(state, copy)
    }
}

/**
 * 头部右侧的状态签：一句话说清「这一屏现在是哪种局面」。
 *
 * 只做语义色，不做图标（本壳层没有图标体系，凭空加一枚反而多一套语言）：
 * 进行中 = 分区铜金、成功 = 主绿、失败 = 危险红。文字全取三语表。
 */
@Composable
internal fun ImportStateTag(state: ModImportUiState, copy: ModImportCopy) {
    val (label, tone) = when (state) {
        is ModImportUiState.Importing -> copy.stateTagImporting to EratwInk.zoneMod()
        is ModImportUiState.Succeeded -> copy.stateTagSucceeded to EratwInk.accent()
        is ModImportUiState.Failed -> copy.stateTagFailed to EratwInk.danger()
        is ModImportUiState.BatchFinished -> if (state.outcome.batchFailure != null || state.outcome.failedCount > 0) {
            // 批里只要有一个包没进来，整批的结论就不是「已完成」——签上不许粉饰。
            copy.stateTagFailed to EratwInk.danger()
        } else {
            copy.stateTagSucceeded to EratwInk.accent()
        }
        is ModImportUiState.Idle -> return
    }
    ShellTag(text = label, tone = tone)
}

/**
 * 进度区：一句「在做什么（N 个文件）」+ 右侧百分比 + 一条细带 + 可选的分项读数。
 *
 * - 读数为 null（刚起跑那一帧）：走不确定态扫描带，**不显示假百分比**（宁可不说，不撒谎）；
 * - 可细分阶段（解包 / 留档）：下方补一行「done/total」；
 * - 百分比与细带都来自 [ModImportProgress] 的同一份快照，不在渲染层另算一遍。
 */
@Composable
internal fun ModImportProgressLine(state: ModImportUiState.Importing, copy: ModImportCopy) {
    val progress = state.progress
    val label = progress?.let { copy.stageText(it.stage.display) } ?: state.stageText
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        EratwText(
            text = copy.fill(copy.importingFilesFormat, label, state.sourceCount),
            tone = EratwTextTone.NOTE,
            modifier = Modifier.weight(1f),
        )
        if (progress != null) {
            EratwText(text = "${progress.percent}%", tone = EratwTextTone.NOTE)
        }
    }
    Spacer(modifier = Modifier.height(PROGRESS_TOP_GAP))
    ShellProgress(
        accent = EratwInk.zoneMod(),
        progress = progress?.overall,
        modifier = Modifier.fillMaxWidth(),
    )
    if (progress != null && progress.hasItems) {
        EratwText(
            text = "${progress.done}/${progress.total}",
            tone = EratwTextTone.MUTED,
            modifier = Modifier.padding(top = PROGRESS_TOP_GAP),
        )
    }
}

/**
 * 动作行：钉在卡片底部（[EratwHairline] 之下），任何时候都完整落在卡内、都点得着。
 *
 * 两颗键按语义分工：左侧是「这一步的主出口」（预览确认 / 选文件），右侧恒为「关闭」。
 * 主出口在 [ModImportUiState.Importing] 或预览读取中禁用 —— 导入不可重入，玩家不必靠
 * 「点了没反应」去猜。
 */
@Composable
internal fun ImportChromeActionRow(
    state: ModImportUiState,
    actions: ModImportChromeActions,
    text: ModImportChromeText,
    previewPhase: ModImportPreviewPhase,
    copy: ModImportCopy,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ACTION_BUTTON_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (previewPhase == ModImportPreviewPhase.AWAITING_CONFIRM) {
            // 预览确认面：确认才进导入，取消只清盘面（导入事务的放行口在
            // ModImportFeedReducer.importBatchOf，渲染层不判定）。
            EratwFilledButton(
                text = text.confirmButton,
                onClick = actions.onConfirmImport,
                modifier = Modifier.heightIn(min = ShellSpace.TouchMin),
            )
            EratwGhostButton(
                text = text.cancelButton,
                onClick = actions.onCancelPreview,
                modifier = Modifier.heightIn(min = ShellSpace.TouchMin),
            )
        } else {
            EratwFilledButton(
                text = text.pickButton,
                onClick = actions.onPickFiles,
                enabled = state !is ModImportUiState.Importing && previewPhase != ModImportPreviewPhase.READING,
                modifier = Modifier.heightIn(min = ShellSpace.TouchMin),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        EratwGhostButton(
            text = copy.closeButton,
            onClick = actions.onDismiss,
            modifier = Modifier.heightIn(min = ShellSpace.TouchMin),
        )
    }
}

// ---------------------------------------------------------------------------
// 覆盖层几何与节奏（只服务导入覆盖层的排版；不另起设计 token）
// ---------------------------------------------------------------------------

/**
 * 卡片高度上限（屏高充裕时的档）。
 *
 * 620dp 是旧值，只对竖屏平板成立 —— 本作是横屏游戏，横屏手机可用高 320~420dp，
 * 于是实际生效的必须是 `min(this, 屏高 × 0.92)`（见 [ModImportChrome]）。
 */
internal val MAX_CARD_HEIGHT = 620.dp

/** 卡片占屏高比例：留出上下 4% 的呼吸，避免贴边。 */
internal const val CARD_HEIGHT_FRACTION = 0.92f

/** 段间距（标题 → 进度 → 正文 → 动作区）：一档到底，不在同屏出现 8/10/12 三种。 */
internal val SECTION_GAP = 10.dp

/** 标题与说明句之间的贴行距。 */
internal val NOTE_TOP_GAP = 4.dp

/** 进度文案与细带之间的距。 */
internal val PROGRESS_TOP_GAP = 6.dp

/** 发丝线与动作按钮之间的距。 */
internal val ACTION_TOP_GAP = 10.dp

/** 动作行内两颗按钮之间（M3 动作组的标准间隔档）。 */
internal val ACTION_BUTTON_GAP = 8.dp

/** 结论块左侧竖条宽度（与设置页分区色带同档）。 */
internal val FAILURE_BAR_WIDTH = 3.dp

/** 成功块底色透明度：读得出「这块是结论」，又不至于把正文压暗。 */
internal const val SUCCESS_FILL_ALPHA = 0.10f
