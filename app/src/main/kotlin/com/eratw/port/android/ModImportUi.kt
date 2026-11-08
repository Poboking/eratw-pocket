package com.eratw.port.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwDialogCard
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.EratwSubhead
import com.eratw.port.android.ui.EratwText
import com.eratw.port.android.ui.EratwTextTone
import com.eratw.port.core.loader.ModPatchLoader

/**
 * R7-C5 的 UI 状态机（纯 Kotlin，可单测）：SAF 选文件入口、报错页（I6/I3 的人话展示，
 * 保留函数名 + 文件:行，禁静默吞错）与已装载补丁报告页（I7 读数口的玩家可见面）。
 *
 * Compose 层（[ModImportChrome]）只渲染这份状态，不做任何判定：全部状态转移走
 * [ModImportUiReducer.reduce] 纯函数（app 侧单测覆盖成功 / 失败 / 回退三态）。
 * R7-D1 任务①起导入宿主是 [SettingsActivity]（入口卡片在设置页）。
 */

// ---------------------------------------------------------------------------
// 报告快照（I7 读数口的 UI 投影）
// ---------------------------------------------------------------------------

/** 报告页里一个已装载补丁的读数（I7 的最小条目：补丁名 + 函数数 + 补丁段指令数）。 */
data class ModPatchReportItem(
    val name: String,
    val functionCount: Int,
    val instructionCount: Int,
)

/**
 * 报告页快照：会话侧的 I7 读数（已装载 / 失败 / 告警 / 回退）+ mods 目录的静态清单。
 *
 * [readError] 非空 = 状态本身读不出来（mods 目录列不出等存储事故）——同样上屏，禁静默。
 */
data class ModPatchReportSnapshot(
    val loadedPatches: List<ModPatchReportItem>,
    val failures: List<String>,
    val warnings: List<String>,
    val rolledBack: Boolean,
    val storedPatchFiles: List<String>,
    val readError: String? = null,
) {
    companion object {
        /**
         * 从 I7 读数口取快照（[com.eratw.port.core.proc.GameSession] 的四个公开读数的
         * 原始类型直入，不收 GameSession：单测不需要先造会话）。
         */
        fun of(
            loadedPatches: List<ModPatchLoader.LoadedPatch>,
            failures: List<String>,
            warnings: List<String>,
            rolledBack: Boolean,
            storedPatchFiles: List<String>,
        ): ModPatchReportSnapshot = ModPatchReportSnapshot(
            loadedPatches = loadedPatches.map { ModPatchReportItem(it.name, it.functionCount, it.patchInstructionCount) },
            failures = failures.toList(),
            warnings = warnings.toList(),
            rolledBack = rolledBack,
            storedPatchFiles = storedPatchFiles.toList(),
        )

        /** 状态读取失败的快照（禁静默：读不出也如实上屏）。 */
        fun unreadable(message: String): ModPatchReportSnapshot =
            ModPatchReportSnapshot(emptyList(), emptyList(), emptyList(), rolledBack = false, storedPatchFiles = emptyList(), readError = message)
    }
}

// ---------------------------------------------------------------------------
// 状态与事件
// ---------------------------------------------------------------------------

/** 导入页内容状态（覆盖层的组合时机与状态解耦：可见性归宿主 [SettingsActivity] 的私有字段）。 */
sealed interface ModImportUiState {
    /** 最近一次刷新的报告快照；null = 尚未刷新过（UI 显示提示行）。 */
    val report: ModPatchReportSnapshot?

    /** 初始 / 上次结果已被覆盖的状态。 */
    data class Idle(override val report: ModPatchReportSnapshot?) : ModImportUiState

    /** 导入进行中（编译 + 落盘在后台线程，UI 只显示这条文案）。 */
    data class Importing(
        val sourceCount: Int,
        val stageText: String,
        override val report: ModPatchReportSnapshot?,
    ) : ModImportUiState

    /** 导入成功：补丁产物已落 mods（重启游戏后生效）。 */
    data class Succeeded(
        val detail: ImportedPatchDetail,
        override val report: ModPatchReportSnapshot?,
    ) : ModImportUiState

    /** 导入失败（报错页：[message] 全文原样展示，编译错误带函数名 + 文件:行）。 */
    data class Failed(
        val kind: ModImportFailureKind,
        val message: String,
        override val report: ModPatchReportSnapshot?,
    ) : ModImportUiState
}

/** 状态机事件（[SettingsActivity] 的 SAF 回调 / 导入线程 / 报告刷新都发到这里）。 */
sealed interface ModImportEvent {
    /** 打开覆盖层时顺带刷新报告。 */
    data class OverlayOpened(val report: ModPatchReportSnapshot?) : ModImportEvent

    /** 开始导入（N 个源文件）。 */
    data class ImportStarted(val sourceCount: Int) : ModImportEvent

    /** 导入成功。 */
    data class ImportSucceeded(val detail: ImportedPatchDetail, val report: ModPatchReportSnapshot?) : ModImportEvent

    /** 导入失败（三态 + 内部兜底见 [ModImportFailureKind]）。 */
    data class ImportFailed(val kind: ModImportFailureKind, val message: String, val report: ModPatchReportSnapshot?) : ModImportEvent

    /** 报告刷新（打开覆盖层 / 导入完成后都会来一发）。 */
    data class ReportRefreshed(val report: ModPatchReportSnapshot?) : ModImportEvent
}

/** 状态机唯一转移函数（纯函数；UI 层不做任何判定）。 */
object ModImportUiReducer {

    /**
     * [copy] 缺省只为所有权外的既有单测留（对拍 [ModImportCopy.ZH]）；生产调用点
     * （设置页壳层）必须显式传当前语言的文案表。
     */
    fun reduce(
        state: ModImportUiState,
        event: ModImportEvent,
        copy: ModImportCopy = ModImportCopy.ZH,
    ): ModImportUiState = when (event) {
        is ModImportEvent.OverlayOpened -> state.withReport(event.report)
        is ModImportEvent.ImportStarted -> ModImportUiState.Importing(event.sourceCount, copy.importingStageText, state.report)
        is ModImportEvent.ImportSucceeded -> ModImportUiState.Succeeded(event.detail, event.report ?: state.report)
        is ModImportEvent.ImportFailed -> ModImportUiState.Failed(event.kind, event.message, event.report ?: state.report)
        is ModImportEvent.ReportRefreshed -> state.withReport(event.report)
    }

    /** 报告刷新不覆盖进行中 / 结果态的内容，只换报告快照。 */
    private fun ModImportUiState.withReport(report: ModPatchReportSnapshot?): ModImportUiState = when (this) {
        is ModImportUiState.Idle -> copy(report = report)
        is ModImportUiState.Importing -> copy(report = report)
        is ModImportUiState.Succeeded -> copy(report = report)
        is ModImportUiState.Failed -> copy(report = report)
    }
}

/**
 * 失败类别的报错页标题（I6/I3/存储的人话分类；message 全文另起展示，不截断）。
 * [copy] 缺省只为所有权外的既有单测留；生产调用点显式传当前语言的文案表。
 */
internal fun modImportFailureTitle(kind: ModImportFailureKind, copy: ModImportCopy = ModImportCopy.ZH): String = when (kind) {
    ModImportFailureKind.COMPILE -> copy.failureTitleCompile
    ModImportFailureKind.IDENTITY -> copy.failureTitleIdentity
    ModImportFailureKind.STORAGE -> copy.failureTitleStorage
    ModImportFailureKind.UNEXPECTED -> copy.failureTitleUnexpected
}

/**
 * 设置页入口卡的一行摘要（R7-D1 任务①）：最近一次导入结果的短文案；null = 尚未导入过。
 * 摘要只是入口卡的提示行，完整三态（成功详情 / 报错全文 / 报告页）在 [ModImportChrome]
 * 覆盖层里原样保留。词源在 [ModImportCopy]；[copy] 缺省只为所有权外的既有单测留。
 */
internal fun modImportCardSummary(state: ModImportUiState, copy: ModImportCopy = ModImportCopy.ZH): String? = when (state) {
    is ModImportUiState.Idle -> null
    is ModImportUiState.Importing -> copy.fill(copy.importingFilesFormat, state.stageText, state.sourceCount)
    is ModImportUiState.Succeeded -> copy.fill(copy.cardSucceededFormat, state.detail.patchFileName)
    is ModImportUiState.Failed -> copy.fill(copy.cardFailedFormat, modImportFailureTitle(state.kind, copy))
}

// ---------------------------------------------------------------------------
// 会话读数口的跨 Activity 通道（R7-D1 任务①）
// ---------------------------------------------------------------------------

/**
 * 会话侧 I7 四读数（[com.eratw.port.core.proc.GameSession] 的 loadedPatches /
 * patchLoadFailures / patchLoadWarnings / patchRolledBack）的进程级提供者。
 *
 * 为什么需要一个口：报告页的「已装载 / 装载失败 / 告警 / 回退」行来自运行中会话，
 * 而会话挂在 [MainActivity] 的 [GameHost] 上；导入宿主迁到 [SettingsActivity] 后
 * （R7-D1 任务①），设置页拿不到那个 Activity 实例的字段。照 [HotConfigChannel]
 * 的同款形态做显式装卸的通道：MainActivity 在宿主就绪处装活引用（`host?.debugSession()`，
 * host 变 null 自动空）、onDestroy 卸载；设置页报告刷新时现取现用。
 * 不装也不崩：read 为 null，报告按「会话没起」形态呈现（只显示 mods 静态清单）。
 */
object ModImportSessionChannel {

    /**
     * 运行中会话的提供者；null = 未接线（壳还没起 / 已卸载）。读取端可能在 UI 线程
     * （打开覆盖层）与导入线程（导入完成后刷报告）两端，写入端在 MainActivity 生命周期，
     * 与 [HotConfigChannel.liveStore] 同口径标 `@Volatile`。
     */
    @Volatile
    private var liveSession: (() -> com.eratw.port.core.proc.GameSession?)? = null

    /**
     * 壳接线：装上 [provider]；传 null 即卸载（MainActivity 销毁清场侧）。幂等：
     * 重开一局会再次装，重复 install 无害。
     */
    fun install(provider: (() -> com.eratw.port.core.proc.GameSession?)?) {
        liveSession = provider
    }

    /** 现取现用：未接线或会话未装配时为 null（调用方按「无会话」呈现）。 */
    fun read(): com.eratw.port.core.proc.GameSession? = liveSession?.invoke()
}

// ---------------------------------------------------------------------------
// Compose：全屏覆盖层（导入 + 报错 + 报告三页共用一个 Column）
// ---------------------------------------------------------------------------

/**
 * 补丁导入的覆盖层（R7-D1 任务①：入口悬浮钮退役，入口改在设置页的
 * [SettingsActivity.ModImportCard] 卡片；本组件只保留覆盖层形态——导入页 /
 * 报错页 / 报告页三页的渲染逐字未动）。
 *
 * 文案口径（A-8/T-2c）：除 [intro] / [pickButton] 仍由壳层从 [SettingsStrings] 传入
 * （那两条键早就在设置页表里），其余玩家面句子统一从 [copy]（[ModImportCopy]）取，
 * 本组件不写字面量。
 *
 * 外壳走 [EratwDialogCard]，但 `scrollable = false`：卡内的报告区自己滚（长编译错误全文要
 * 读得完），外层再滚会和它抢高度约束。`dismissOnClickOutside = false` 是有意的 —— 这一页
 * 有正在跑的导入，点卡外不该把它关掉，收场只认「关闭」那颗与返回键。
 *
 * 真机观感（共用壳样式下的覆盖层）留组长真机验收调整（TODO(verify)）。
 */
@Composable
fun ModImportChrome(
    state: ModImportUiState,
    onDismiss: () -> Unit,
    onPickFiles: () -> Unit,
    onRestartGame: () -> Unit,
    intro: String,
    pickButton: String,
    copy: ModImportCopy,
) {
    EratwDialogCard(
        onDismissRequest = onDismiss,
        dismissOnClickOutside = false,
        maxWidth = 680.dp,
        maxHeight = 620.dp,
        scrollable = false,
    ) {
        EratwText(text = copy.cardTitle, tone = EratwTextTone.HEADLINE)
        Spacer(modifier = Modifier.height(4.dp))
        EratwText(text = intro, tone = EratwTextTone.NOTE)
        Spacer(modifier = Modifier.height(8.dp))

        // 状态区：导入中 / 成功 / 失败 三态（契约 C5 出口判据①）。
        when (val s = state) {
            is ModImportUiState.Importing -> EratwText(text = copy.fill(copy.importingFilesFormat, s.stageText, s.sourceCount))
            is ModImportUiState.Succeeded -> SuccessBlock(detail = s.detail, onRestartGame = onRestartGame, copy = copy)
            is ModImportUiState.Failed -> FailureBlock(kind = s.kind, message = s.message, copy = copy)
            is ModImportUiState.Idle -> Unit
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 报告区：I7 读数口 + mods 静态清单（可滚动，长错误全文可读）。
        ReportBlock(state.report, copy)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EratwFilledButton(
                text = pickButton,
                onClick = onPickFiles,
                enabled = state !is ModImportUiState.Importing,
            )
            Spacer(modifier = Modifier.weight(1f))
            EratwGhostButton(text = copy.closeButton, onClick = onDismiss)
        }
    }
}

@Composable
private fun SuccessBlock(detail: ImportedPatchDetail, onRestartGame: () -> Unit, copy: ModImportCopy) {
    Column {
        EratwText(
            text = copy.fill(
                copy.succeededDetailFormat,
                detail.patchFileName,
                detail.functionCount,
                detail.instructionCount,
                detail.entryFunction,
            ),
        )
        for (w in detail.parseWarnings) {
            EratwText(text = copy.fill(copy.warningLineFormat, w), tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
        }
        EratwText(
            text = copy.fill(copy.savedSourcesFormat, detail.sourceFileNames.joinToString(copy.nameListSeparator)),
            tone = EratwTextTone.MUTED,
        )
        Spacer(modifier = Modifier.height(6.dp))
        EratwFilledButton(text = copy.restartButton, onClick = onRestartGame)
    }
}

@Composable
private fun FailureBlock(kind: ModImportFailureKind, message: String, copy: ModImportCopy) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(EratwInk.dangerFill())
            .padding(10.dp),
    ) {
        EratwText(
            text = modImportFailureTitle(kind, copy),
            colorOverride = EratwInk.danger(),
        )
        // message 全文原样展示（禁静默吞错；编译错误自带函数名 + 文件:行）。
        EratwText(
            text = message,
            tone = EratwTextTone.NOTE,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun ReportBlock(report: ModPatchReportSnapshot?, copy: ModImportCopy) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        EratwSubhead(text = copy.reportTitle)
        if (report == null) {
            EratwText(text = copy.reportNotRefreshed, tone = EratwTextTone.NOTE)
            return
        }
        report.readError?.let {
            EratwText(text = copy.fill(copy.reportReadFailedFormat, it), tone = EratwTextTone.NOTE, colorOverride = EratwInk.danger())
            return
        }
        if (report.rolledBack) {
            EratwText(text = copy.reportRolledBackLine, tone = EratwTextTone.NOTE, colorOverride = EratwInk.danger())
        }
        if (report.failures.isEmpty() && report.loadedPatches.isEmpty() && !report.rolledBack) {
            EratwText(text = copy.reportNoneLoaded, tone = EratwTextTone.NOTE)
        }
        for (p in report.loadedPatches) {
            EratwText(text = copy.fill(copy.reportLoadedFormat, p.name, p.functionCount, p.instructionCount), tone = EratwTextTone.NOTE)
        }
        for (f in report.failures) {
            EratwText(text = copy.fill(copy.reportLoadFailedFormat, f), tone = EratwTextTone.NOTE, colorOverride = EratwInk.danger())
        }
        for (w in report.warnings) {
            EratwText(text = copy.fill(copy.reportLoadWarningFormat, w), tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
        }
        EratwText(
            text = if (report.storedPatchFiles.isEmpty()) {
                copy.reportNoStoredFiles
            } else {
                copy.fill(copy.reportStoredFilesFormat, report.storedPatchFiles.joinToString(copy.nameListSeparator))
            },
            tone = EratwTextTone.MUTED,
        )
    }
}