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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwDialogCard
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.EratwSubhead
import com.eratw.port.android.ui.EratwText
import com.eratw.port.android.ui.EratwTextTone
import com.eratw.port.android.ui.design.ShellRuleLabel
import com.eratw.port.android.ui.design.ShellShape
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.core.loader.ModPatchLoader
import java.io.File

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

    /**
     * 本次选中文件的只读预览（[ModImportPreview.compute] 的结果，见 [ModImportEvent.PreviewRefreshed]）。
     * null = 还没算过（打开页 / 刚关掉），覆盖层不显示预览块；[Preview.Unavailable] 也照样显示
     * 一行「预览不可用」——预览失败**不阻断导入**（强校验仍在导入事务里）。
     */
    val preview: Preview?

    /** 初始 / 上次结果已被覆盖的状态。 */
    data class Idle(
        override val report: ModPatchReportSnapshot?,
        override val preview: Preview? = null,
    ) : ModImportUiState

    /** 导入进行中（编译 + 落盘在后台线程，UI 只显示这条文案）。 */
    data class Importing(
        val sourceCount: Int,
        val stageText: String,
        override val report: ModPatchReportSnapshot?,
        override val preview: Preview? = null,
    ) : ModImportUiState

    /** 导入成功：补丁产物已落 mods（重启游戏后生效）。 */
    data class Succeeded(
        val detail: ImportedPatchDetail,
        override val report: ModPatchReportSnapshot?,
        override val preview: Preview? = null,
    ) : ModImportUiState

    /** 导入失败（报错页：[message] 全文原样展示，编译错误带函数名 + 文件:行）。 */
    data class Failed(
        val kind: ModImportFailureKind,
        val message: String,
        override val report: ModPatchReportSnapshot?,
        override val preview: Preview? = null,
    ) : ModImportUiState

    /**
     * 批量导入跑完（task-178）：逐项结果 + 批级失败都在 [outcome] 里，UI 逐包分行
     * （好包一行成功、坏包一行归类标题 + message 全文），最后是「N 成功 / M 失败」汇总。
     * 与单包 [Succeeded] / [Failed] 并列而不是替代：单包调用点语义不变，批量只是多了这一态。
     */
    data class BatchFinished(
        val outcome: ModImportBatchOutcome,
        override val report: ModPatchReportSnapshot?,
        override val preview: Preview? = null,
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

    /**
     * 批量导入跑完（task-178）：[outcome] 已含逐项结果与可能的批级失败，壳层只负责
     * 「后台跑完 importBatch → 发这一发」，不在这里二次解释成败（判定归控制器）。
     */
    data class BatchImportFinished(
        val outcome: ModImportBatchOutcome,
        val report: ModPatchReportSnapshot?,
    ) : ModImportEvent

    /** 报告刷新（打开覆盖层 / 导入完成后都会来一发）。 */
    data class ReportRefreshed(val report: ModPatchReportSnapshot?) : ModImportEvent

    /**
     * 预览算好了（SAF 选完文件后由宿主在后台线程算 [ModImportPreview.compute] 再发这里）。
     * 只换预览读数，不动三态本体与报告快照；[Preview.Unavailable] 同一条路进来（显示预告行）。
     * [preview] = null 表示**清预览**（重选 / 取消 / 关页时把上一批读数下屏，不留旧盘面）。
     */
    data class PreviewRefreshed(val preview: Preview?) : ModImportEvent
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
        is ModImportEvent.ImportStarted -> ModImportUiState.Importing(
            event.sourceCount,
            copy.importingStageText,
            state.report,
            state.preview,
        )
        is ModImportEvent.ImportSucceeded ->
            ModImportUiState.Succeeded(event.detail, event.report ?: state.report, state.preview)
        is ModImportEvent.ImportFailed ->
            ModImportUiState.Failed(event.kind, event.message, event.report ?: state.report, state.preview)
        is ModImportEvent.BatchImportFinished ->
            ModImportUiState.BatchFinished(event.outcome, event.report ?: state.report, state.preview)
        is ModImportEvent.ReportRefreshed -> state.withReport(event.report)
        is ModImportEvent.PreviewRefreshed -> state.withPreview(event.preview)
    }

    /** 报告刷新不覆盖进行中 / 结果态的内容，只换报告快照。 */
    private fun ModImportUiState.withReport(report: ModPatchReportSnapshot?): ModImportUiState = when (this) {
        is ModImportUiState.Idle -> copy(report = report)
        is ModImportUiState.Importing -> copy(report = report)
        is ModImportUiState.Succeeded -> copy(report = report)
        is ModImportUiState.Failed -> copy(report = report)
        is ModImportUiState.BatchFinished -> copy(report = report)
    }

    /** 预览刷新只换预览读数（预览是选中文件的读数，与三态本体互不覆盖）；null = 下屏。 */
    private fun ModImportUiState.withPreview(preview: Preview?): ModImportUiState = when (this) {
        is ModImportUiState.Idle -> copy(preview = preview)
        is ModImportUiState.Importing -> copy(preview = preview)
        is ModImportUiState.Succeeded -> copy(preview = preview)
        is ModImportUiState.Failed -> copy(preview = preview)
        is ModImportUiState.BatchFinished -> copy(preview = preview)
    }
}

// ---------------------------------------------------------------------------
// 预览喂入状态机（SAF 选完 → 只读预览上屏 → 玩家确认 → 才进导入事务）
// ---------------------------------------------------------------------------

/** 预览喂入的覆盖层相位（[ModImportFeedState] 的渲染投影；渲染层不做任何判定）。 */
enum class ModImportPreviewPhase {
    /** 没有待确认批：首次打开 / 尚未选文件 / 已取消 / 导入已接管。 */
    IDLE,

    /** 后台正在读字节 + 算预览：重选入口先禁掉（同一时刻只放一批 IO）。 */
    READING,

    /** 预览已上屏、等玩家确认：底部出「确定 / 取消」。 */
    AWAITING_CONFIRM,
}

/**
 * 预览喂入状态（纯 Kotlin，可单测；转移只走 [ModImportFeedReducer.reduce]）。
 *
 * 为什么单独一台状态机：只读预览（[ModImportPreview]）与导入事务
 * （[ModImportController.import]）之间必须隔一道「玩家确认」——预览先上屏、
 * 算不出来也不拦，但**导入只认确认后的那一批**（[ModImportFeedReducer.importBatchOf]
 * 是唯一放行口）。宿主（[SettingsActivity]）只做线程编排与事件投递，判定全在这里。
 *
 * [selectionId] 是选区序号：重选 / 取消后，上一批迟到的「预览算好了」按序号丢弃，不串批。
 */
internal sealed interface ModImportFeedState {
    /** 没有待确认批。 */
    data object Idle : ModImportFeedState

    /** 已选完 [selectionId] 批，后台正在读字节 + 算预览：没有可上屏的预览、不可确认。 */
    data class Reading(val selectionId: Long) : ModImportFeedState

    /**
     * 预览已上屏，等玩家确认。[preview] 可能是 [Preview.Unavailable]（读不出 / 算不出）——
     * 预览是**只提示不拦截**的一屏预告，确认照常放行（强校验仍在导入事务里）。
     */
    data class AwaitingConfirm(
        val selectionId: Long,
        val picked: List<PickedModFile>,
        val readErrors: List<String>,
        val preview: Preview,
    ) : ModImportFeedState

    /** 已确认，导入线程接管了 [picked]（此后不再接受预览 / 取消）。 */
    data class Running(
        val selectionId: Long,
        val picked: List<PickedModFile>,
        val readErrors: List<String>,
    ) : ModImportFeedState
}

/** 喂入事件（SAF 回调 / 预览线程 / 覆盖层确认按钮三条来源）。 */
internal sealed interface ModImportFeedEvent {
    /** 新一批选区开始（重选 / 首次选择）：旧预览与旧待确认批立刻作废。 */
    data class SelectionStarted(val selectionId: Long) : ModImportFeedEvent

    /** 后台读字节 + 算预览完成；[selectionId] 与当前批不符 = 迟到结果，丢弃。 */
    data class PreviewComputed(
        val selectionId: Long,
        val picked: List<PickedModFile>,
        val readErrors: List<String>,
        val preview: Preview,
    ) : ModImportFeedEvent

    /** 玩家确认：只有 [ModImportFeedState.AwaitingConfirm] 放行——「预览先于导入」的判据。 */
    data object Confirmed : ModImportFeedEvent

    /** 取消 / 关页：清掉待确认批，**不产生任何导入**。 */
    data object Cancelled : ModImportFeedEvent
}

/** 喂入状态机唯一转移函数（纯函数；不做 IO、不判语言、不碰任何全局）。 */
internal object ModImportFeedReducer {

    fun reduce(state: ModImportFeedState, event: ModImportFeedEvent): ModImportFeedState = when (event) {
        is ModImportFeedEvent.SelectionStarted -> ModImportFeedState.Reading(event.selectionId)
        is ModImportFeedEvent.PreviewComputed ->
            if (state is ModImportFeedState.Reading && state.selectionId == event.selectionId) {
                ModImportFeedState.AwaitingConfirm(event.selectionId, event.picked, event.readErrors, event.preview)
            } else {
                state
            }
        ModImportFeedEvent.Confirmed -> if (state is ModImportFeedState.AwaitingConfirm) {
            ModImportFeedState.Running(state.selectionId, state.picked, state.readErrors)
        } else {
            state
        }
        ModImportFeedEvent.Cancelled -> when (state) {
            is ModImportFeedState.Reading, is ModImportFeedState.AwaitingConfirm -> ModImportFeedState.Idle
            // 导入已接管（Running）时取消是 no-op：线程已经起跑，撤不回，也不该假装撤了。
            is ModImportFeedState.Running, ModImportFeedState.Idle -> state
        }
    }

    /** 待确认的预览；null = 不该显示预览块（正在算 / 已取消 / 已接管）。 */
    fun previewOf(state: ModImportFeedState): Preview? = (state as? ModImportFeedState.AwaitingConfirm)?.preview

    /** 确认后交给导入线程的那一批；null = 还没被确认过（导入的唯一放行判据）。 */
    fun importBatchOf(state: ModImportFeedState): List<PickedModFile>? =
        (state as? ModImportFeedState.Running)?.picked

    /** 确认时随批带走的读失败清单（附在导入结果后面一起上屏）。 */
    fun readErrorsOf(state: ModImportFeedState): List<String> =
        (state as? ModImportFeedState.Running)?.readErrors.orEmpty()
}

/** [ModImportFeedState] → 覆盖层相位（唯一投影口）。 */
internal fun modImportPreviewPhaseOf(state: ModImportFeedState): ModImportPreviewPhase = when (state) {
    ModImportFeedState.Idle -> ModImportPreviewPhase.IDLE
    is ModImportFeedState.Reading -> ModImportPreviewPhase.READING
    is ModImportFeedState.AwaitingConfirm -> ModImportPreviewPhase.AWAITING_CONFIRM
    // 已确认 = 导入页接管，预览确认面撤掉（预览块本身仍在 UI 状态里留着给结果页）。
    is ModImportFeedState.Running -> ModImportPreviewPhase.IDLE
}

/**
 * 预览后台体的唯一出口（宿主在后台线程调）：只读、不落盘、绝不抛。
 *
 * [ModImportPreview.compute] 自己已把可预期失败折成 [Preview.Unavailable]，这里再兜一层
 * （读字节阶段全失败 / 任何未预期异常）——预览算不出来**只降级显示、不阻断确认**。
 * [rt2Dir] 与 [copy] 由宿主在主线程取好随调用进线程（后台不碰 SharedPreferences、不判 Locale）。
 */
internal fun computeModImportPreviewSafely(
    picked: List<PickedModFile>,
    readErrors: List<String>,
    rt2Dir: File,
    copy: ModImportCopy,
): Preview = if (picked.isEmpty()) {
    Preview.Unavailable(copy.fill(copy.allReadsFailedFormat, readErrors.joinToString("\n")))
} else {
    runCatching { ModImportPreview.compute(picked, rt2Dir, copy) }.getOrElse { t ->
        Preview.Unavailable(t.message ?: t::class.simpleName ?: copy.unknownStorageError)
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
    // 批级失败（空选择 / 循环中断）优先报失败；否则报本批「N 成功 / M 失败」计数。
    is ModImportUiState.BatchFinished -> state.outcome.batchFailure?.let {
        copy.fill(copy.cardFailedFormat, modImportFailureTitle(it.kind, copy))
    } ?: copy.fill(copy.batchCardSummaryFormat, state.outcome.succeededCount, state.outcome.failedCount)
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
     * 当前接线归属（2026-11-13 认主）：与 [HotConfigChannel.uninstall] 同一防撅语义。
     * 会话升进程级后双壳交接窗口（旧壳 onDestroy 跑在新壳接回/交接之后，2026-10-31
     * 真机实形）里，旧壳的卸载不得把新壳刚装好的活引用撅空——否则设置页读会话侧
     * 四读数静默变空，导入报告退回「mods 静态清单」形态且无任何痕迹。
     */
    @Volatile
    private var owner: Any? = null

    /**
     * 壳接线：装上 [provider] 并记 [owner]（通常为本 Activity）。幂等：重开一局会再次装，
     * 重复 install 无害（后装者覆盖，owner 同步换）。
     */
    fun install(owner: Any?, provider: (() -> com.eratw.port.core.proc.GameSession?)?) {
        this.owner = owner
        liveSession = provider
    }

    /** 认主卸载：只有归属正是 [owner] 才清（旧壳无权撅新壳刚装的接线）。 */
    fun uninstall(owner: Any) {
        if (this.owner === owner) {
            this.owner = null
            liveSession = null
        }
    }

    /** 现取现用：未接线或会话未装配时为 null（调用方按「无会话」呈现）。 */
    fun read(): com.eratw.port.core.proc.GameSession? = liveSession?.invoke()
}

// ---------------------------------------------------------------------------
// Compose：全屏覆盖层（导入 + 报错 + 报告三页共用一个 Column）
// ---------------------------------------------------------------------------

/**
 * 覆盖层底部的动作组（形参归组：本组件原有形参已达 LongParameterList 阈值，预览确认面
 * 再要三个动作只能成组进——照军规「超参函数不再平铺加参」的口径）。
 */
data class ModImportChromeActions(
    val onDismiss: () -> Unit,
    val onPickFiles: () -> Unit,
    val onConfirmImport: () -> Unit,
    val onCancelPreview: () -> Unit,
    val onRestartGame: () -> Unit,

    /**
     * 一键复制（KI-4）：报错页的错误报告 / 成功页的告警清单都经这一个口写剪贴板。
     * 参数 = 已拼好的全文（拼装在纯函数 [modImportErrorReportText] / 调用点，
     * 写剪贴板与 Toast 归宿主）。
     */
    val onCopyText: (String) -> Unit,
)

/**
 * 覆盖层里仍由壳层从 [SettingsStrings] 传词的几条键（其余玩家面句子一律从 [copy] 取）。
 * [confirmButton] / [cancelButton] 是预览确认面的两颗键：复用设置页既有的确定 / 取消，
 * 预览面文案表不另开新键（要开就得同步三语形状闸，越本波授权面）。
 */
data class ModImportChromeText(
    val intro: String,
    val pickButton: String,
    val confirmButton: String,
    val cancelButton: String,
)

/**
 * 补丁导入的覆盖层（R7-D1 任务①：入口悬浮钮退役，入口改在设置页的
 * [SettingsActivity.ModImportCard] 卡片；本组件只保留覆盖层形态——导入页 /
 * 报错页 / 报告页三页的渲染逐字未动）。
 *
 * 文案口径（A-8/T-2c）：除 [ModImportChromeText] 里那几条仍由壳层从 [SettingsStrings]
 * 传入的键（那几条早就在设置页表里），其余玩家面句子统一从 [copy]（[ModImportCopy]）取，
 * 本组件不写字面量。
 *
 * [previewPhase] 决定底部按钮组：[ModImportPreviewPhase.AWAITING_CONFIRM] 时出
 * 「确定 / 取消」——预览先上屏、确认才导入；其余相位仍是「选文件 / 关闭」。
 * 预览块（[PreviewBlock]）照旧只读 [state] 的预览读数，不参与判定。
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
    actions: ModImportChromeActions,
    text: ModImportChromeText,
    previewPhase: ModImportPreviewPhase,
    copy: ModImportCopy,
) {
    EratwDialogCard(
        onDismissRequest = actions.onDismiss,
        dismissOnClickOutside = false,
        maxWidth = 680.dp,
        maxHeight = 620.dp,
        scrollable = false,
        // 宿主是设置页（系统栏可见，那是页面语义的一部分）：只做几何装配，不藏栏。
        immersive = false,
    ) {
        EratwText(text = copy.cardTitle, tone = EratwTextTone.HEADLINE)
        Spacer(modifier = Modifier.height(4.dp))
        EratwText(text = text.intro, tone = EratwTextTone.NOTE)
        Spacer(modifier = Modifier.height(8.dp))

        // 预览区：选中文件的只读读数（只读、只提示；null = 还没算过，整块不出）。
        val preview = state.preview
        if (preview != null) {
            PreviewBlock(preview = preview, copy = copy)
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 状态区：导入中 / 成功 / 失败 三态（契约 C5 出口判据①）。
        when (val s = state) {
            is ModImportUiState.Importing -> EratwText(text = copy.fill(copy.importingFilesFormat, s.stageText, s.sourceCount))
            is ModImportUiState.Succeeded -> SuccessBlock(
                detail = s.detail,
                onRestartGame = actions.onRestartGame,
                onCopyText = actions.onCopyText,
                copy = copy,
            )
            is ModImportUiState.Failed -> FailureBlock(
                kind = s.kind,
                message = s.message,
                sourceNames = (state.preview as? Preview.Ready)?.packages?.map { it.displayName }.orEmpty(),
                onCopyText = actions.onCopyText,
                copy = copy,
            )
            is ModImportUiState.BatchFinished -> BatchResultBlock(
                outcome = s.outcome,
                onRestartGame = actions.onRestartGame,
                copy = copy,
            )
            is ModImportUiState.Idle -> Unit
        }
        Spacer(modifier = Modifier.height(8.dp))

        // 报告区：I7 读数口 + mods 静态清单（可滚动，长错误全文可读）。
        ReportBlock(state.report, copy)
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (previewPhase == ModImportPreviewPhase.AWAITING_CONFIRM) {
                // 预览确认面：确认才进导入，取消只清盘面（导入事务的放行口在
                // ModImportFeedReducer.importBatchOf，渲染层不判定）。
                EratwFilledButton(text = text.confirmButton, onClick = actions.onConfirmImport)
                EratwGhostButton(text = text.cancelButton, onClick = actions.onCancelPreview)
            } else {
                EratwFilledButton(
                    text = text.pickButton,
                    onClick = actions.onPickFiles,
                    enabled = state !is ModImportUiState.Importing && previewPhase != ModImportPreviewPhase.READING,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            EratwGhostButton(text = copy.closeButton, onClick = actions.onDismiss)
        }
    }
}

/**
 * 导入预览块（[ModImportPreview.previewLines] 的只读读数 + 每条预警一行）。
 *
 * 预览失败不占成功 / 失败三态的位置：这里只多一行「预览不可用」，导入按钮照常可点——
 * 预览是**只提示不拦截**的一屏预告，强校验仍在导入事务里（KDoc 分工见 [ModImportPreview]）。
 */
@Composable
private fun PreviewBlock(preview: Preview, copy: ModImportCopy) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ShellRuleLabel(text = copy.previewTitle)
        for (line in ModImportPreview.previewLines(preview, copy)) {
            EratwText(text = line, tone = EratwTextTone.NOTE)
        }
    }
}

@Composable
private fun SuccessBlock(
    detail: ImportedPatchDetail,
    onRestartGame: () -> Unit,
    onCopyText: (String) -> Unit,
    copy: ModImportCopy,
) {    Column {
        EratwText(text = detail.successDetailText(copy))
        if (detail.overrideCount > 0) {
            EratwText(text = copy.fill(copy.overrideSummaryFormat, detail.overrideCount))
        }
        // Lane BH：资源 N 条（0 条整行不出；判据在 ImportedPatchDetail.resourceSummaryLine）。
        detail.resourceSummaryLine(copy)?.let { EratwText(text = it, tone = EratwTextTone.NOTE) }
        // BU：重装 / 版本变化读数（首次安装整行不出；判据在 versionChangeSummaryLine，含「设置与数据保留」）。
        detail.versionChangeSummaryLine(copy)?.let { EratwText(text = it, tone = EratwTextTone.NOTE) }
        for (w in detail.parseWarnings) {
            EratwText(text = copy.fill(copy.warningLineFormat, w), tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
        }
        // 本 lane：包编译期要求的引擎面比本机新 ⇒ 导入成功但装载期会被整包拒载，成功页当场说清
        // 「要求 vX / 本机 vY / 不会装载」。普通包与兼容包整行不出（判据在 engineTooOldLine，
        // 与装载侧 ModDexGate.verdict 同口径）。
        detail.engineTooOldLine(copy)?.let {
            EratwText(text = it, tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
        }
        detail.modRuntimeOffHint?.let {
            EratwText(text = it, tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
        }
        // task-179：本包带了第三方代码、但下次装载不会运行它时再报一行原因（总闸关 / 未信任）；
        // 普通口上包与已生效的代码包整行不出（判据在 modCodeGateHintText）。
        modCodeGateHintText(copy, detail.codeGateHint)?.let {
            EratwText(text = it, tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
        }
        // 「源文件已保存」行：纯资源包（MODDX3-A3）与成品包（ERAFIN-C3，无留档源）整行不出。
        if (!detail.assetPackOnly && !detail.packagedModuleOnly) {
            EratwText(
                text = copy.fill(copy.savedSourcesFormat, detail.sourceFileNames.joinToString(copy.nameListSeparator)),
                tone = EratwTextTone.MUTED,
            )
        }
        // KI-4 ④：隔离 / 降级 / 告警清单非空时给一键复制（玩家转给口上作者用，
        // 与报错页同一 [onCopyText] 口）；清单本身照旧逐行上屏，可回看。
        if (detail.parseWarnings.isNotEmpty()) {
            EratwGhostButton(
                text = copy.copyWarningsButton,
                onClick = { onCopyText(detail.parseWarnings.joinToString("\n")) },
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        EratwFilledButton(text = copy.restartButton, onClick = onRestartGame)
    }
}

@Composable
private fun FailureBlock(
    kind: ModImportFailureKind,
    message: String,
    sourceNames: List<String>,
    onCopyText: (String) -> Unit,
    copy: ModImportCopy,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ShellShape.Inset)
            .background(EratwInk.dangerFill())
            .padding(ShellSpace.RowPadding),
    ) {
        EratwText(
            text = modImportFailureTitle(kind, copy),
            colorOverride = EratwInk.danger(),
        )        // message 全文原样展示（禁静默吞错；编译错误自带函数名 + 文件:行）。
        EratwText(
            text = message,
            tone = EratwTextTone.NOTE,
            modifier = Modifier.verticalScroll(rememberScrollState()),
        )
        Spacer(modifier = Modifier.height(6.dp))
        // KI-4 ②：免责/导向行（错误归属告诉玩家该找谁，不背口上包自身的错）。
        EratwText(text = modImportFailureGuidance(kind, copy), tone = EratwTextTone.NOTE)
        Spacer(modifier = Modifier.height(4.dp))
        // KI-4 ③：一键复制报告（包名/类别/时间/全文四段，见 modImportErrorReportText）。
        EratwGhostButton(
            text = copy.copyErrorReportButton,
            onClick = {
                onCopyText(modImportErrorReportText(kind, message, copy, System.currentTimeMillis(), sourceNames))
            },
        )
    }
}

@Composable
private fun ReportBlock(report: ModPatchReportSnapshot?, copy: ModImportCopy) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        ShellRuleLabel(text = copy.reportTitle)
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
