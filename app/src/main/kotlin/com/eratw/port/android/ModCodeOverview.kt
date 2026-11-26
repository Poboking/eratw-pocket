package com.eratw.port.android

import java.io.File

/**
 * 设置页「第三方代码包概览」的纯读数面（ADR-013 路 B 玩家可见面 / task-162）。
 *
 * ## 是什么
 *
 * 玩家可见的只读读数：本机 `mods/rt2/code/` 下有哪些第三方代码包、各有多少个已落地版本、
 * 共占多少字节、是否已被信任，以及 dex 装载总闸的调用方快照。
 *
 * ## 只读与总闸（D5 同口径）
 *
 * 本文件只读盘面：不装载、不写信任标记、不删任何产物、不写偏好。总闸关时直接返回「未开启」
 * 快照（[ModCodeOverview.rows] 为空）且**不扫 `code/` 目录**——与 dex 装载器 D5 的关态判据
 * 同源（`ModDexLoader.kt:86`：总闸关就不碰产物）。空表因此只在「总闸开且没有包」时出现，
 * 由 [ModCodeOverview.empty] 显式区分，避免玩家把「未开启」误读成「没装」。
 *
 * ## 口径不另造
 *
 * 包清单 = [ModTrustStore.codePackages]（只认有合法 16 位小写 hex 名前缀 `.mod` 产物的包目录）；
 * 版本数与体积 = [ModCodeStore.list] 逐件累加；信任态 = 同一身份桥 [trustReadoutOf]（磁盘身份经
 * `.codeid` 解析成运行期身份再实读 `*.trusted`，与装载闸的读取键同源；身份坏档的行显式照
 * 「身份信息损坏」口径，不许显示成「未信任」）；体积格式 = [formatStorageBytes]
 * （与「Mod 运行时概览」卡同一口径）。
 */

/** 「第三方代码包概览」的一行：包名、版本数、体积、信任态、所属快照的总闸态、最近一次装载结论。 */
internal data class ModCodeRow(
    val packageId: String,
    val versions: Int,
    val bytes: Long,
    val trusted: Boolean,
    val dexLoaderEnabled: Boolean,
    /** 最近一次装载结论（[ModCodeLoadReport] 的投影）；null = 本次启动还没有该包的结论（显式「—」）。 */
    val loadConclusion: ModCodeLoadConclusion? = null,
    /**
     * 身份坏档原因（[TrustMarkerBlockReason]）；非 null = 信任态不可判，[trusted] 恒 false，
     * 行状态照「身份信息损坏」显示，不显示成「未信任」。
     */
    val blockedReason: TrustMarkerBlockReason? = null,
)

/**
 * 一行「最近一次装载结论」的展示投影（[ModCodeRow.loadConclusion]）。
 *
 * 只承载展示要用的两件事：结局码与「引擎太旧」的版本缺口；原始原因句留在
 * [CodeCarrierRecord.rejectReason]（诊断导出用），本投影不重复携带。
 */
internal data class ModCodeLoadConclusion(
    val outcome: CodeCarrierOutcome,
    /** 仅 [CodeCarrierOutcome.ENGINE_TOO_OLD]：版本缺口读数。 */
    val engineGap: EngineSurfaceGap? = null,
)

/**
 * 一次概览快照（只读）。
 *
 * @property dexLoaderEnabled dex 装载总闸的调用方快照（[UiPrefs.modDexLoaderEnabled]）。
 * @property rows 每个 code 包一行，按包名升序；总闸关或读盘失败时为空。
 * @property readFailed 读盘失败（rt2 目录不可解析 / 扫描抛异常）：体积与信任态都不可信，
 *   由卡片照「缺数据显式 —」口径提示，不许静默显示成「0 个包」。
 */
internal data class ModCodeOverview(
    val dexLoaderEnabled: Boolean,
    val rows: List<ModCodeRow>,
    val readFailed: Boolean = false,
) {
    /** 总闸关：整块只显式说明「未开启」，不渲染空表也不渲染行。 */
    val gateOff: Boolean get() = !dexLoaderEnabled

    /** 总闸开但本机没有任何 code 包：显式空态（不是隐藏整块）。 */
    val empty: Boolean get() = dexLoaderEnabled && !readFailed && rows.isEmpty()
}

/**
 * 扫 [rt2Dir] 下的 `code/` 与信任标记，产出概览快照。
 *
 * 全程不抛：总闸关时早退且不扫 `code/`（D5 同口径）；[rt2Dir] 为 null（游戏根不可解析）
 * 或扫描抛异常时返回 [ModCodeOverview.readFailed] 快照，由 UI 显式提示「—」。
 *
 * @param loadReport 装配期发布的最近一次装载结论（[ModCodeLoadReports.last]）；null = 本进程
 *   还没装配过，行上的装载结论照「—」口径，不猜。
 */
internal fun buildModCodeOverview(
    rt2Dir: File?,
    dexLoaderEnabled: Boolean,
    loadReport: ModCodeLoadReport? = null,
): ModCodeOverview = when {
    !dexLoaderEnabled -> ModCodeOverview(dexLoaderEnabled = false, rows = emptyList())
    rt2Dir == null -> ModCodeOverview(dexLoaderEnabled = true, rows = emptyList(), readFailed = true)
    else -> runCatching { scanModCodeRows(rt2Dir, loadReport) }.fold(
        onSuccess = { ModCodeOverview(dexLoaderEnabled = true, rows = it) },
        onFailure = { ModCodeOverview(dexLoaderEnabled = true, rows = emptyList(), readFailed = true) },
    )
}

/**
 * 逐包汇总：包集合取 [ModTrustStore.codePackages]，件数与字节取 [ModCodeStore.list]，
 * 信任态取 [trustReadoutOf]（解析出运行期身份再实读标记；坏档行保留原因，不吞成未信任）。
 */
private fun scanModCodeRows(rt2Dir: File, loadReport: ModCodeLoadReport?): List<ModCodeRow> {
    val store = ModCodeStore(rt2Dir)
    return ModTrustStore.codePackages(store.codeDir()).map { pack ->
        val artifacts = runCatching { store.list(pack.packageId) }.getOrElse { emptyList() }
        val trust = trustReadoutOf(rt2Dir, pack.packageId)
        ModCodeRow(
            packageId = pack.packageId,
            versions = artifacts.size,
            bytes = artifacts.sumOf { it.sizeBytes },
            trusted = trust is TrustReadout.Known && trust.trusted,
            dexLoaderEnabled = true,
            loadConclusion = loadReport?.recordFor(pack.packageId)?.let { record ->
                ModCodeLoadConclusion(outcome = record.outcome, engineGap = record.engineTooOld)
            },
            blockedReason = (trust as? TrustReadout.Blocked)?.reason,
        )
    }
}

/** 总闸状态行：开 / 关；关态明说「不会装载任何第三方代码」。 */
internal fun modCodeGateLine(text: SettingsStrings, dexLoaderEnabled: Boolean): String =
    if (dexLoaderEnabled) text.modCodeOverviewGateOn else text.modCodeOverviewGateOff

/**
 * 行读数：「包名」· N 个版本 · 体积。
 *
 * 体积走 [formatStorageBytes]（0 B / x.y KB / x.y MB），与「Mod 运行时概览」卡逐字同口径。
 */
internal fun modCodeRowReadout(text: SettingsStrings, row: ModCodeRow): String =
    text.modCodeOverviewRow(row.packageId, row.versions, formatStorageBytes(row.bytes))

/**
 * 行信任态读数：身份坏档（[ModCodeRow.blockedReason] 非 null）照「身份信息损坏」显式不可判，
 * 不许显示成「未信任」。与信任表行同一口径（[modTrustRowStateLabel]），复用同一三语文案。
 */
internal fun modCodeRowTrustLabel(text: SettingsStrings, row: ModCodeRow): String =
    if (row.blockedReason != null) text.modCodeStateCorrupt else modTrustStateLabel(text, row.trusted)

/**
 * 「最近一次装载结论」行的值（null = 本次启动还没有结论 ⇒ 显式「—」，不编造）。
 *
 * 只投影 [ModCodeLoadConclusion.outcome]：已装载说「已装载」；引擎太旧带两侧版本号（人话，
 * 版本号不算内部术语）；其余结局一律「本次未装载（原因见诊断导出）」——本卡是只读概览，
 * 不在这里复述 [CodeCarrierRecord.rejectReason] 这类诊断原文。
 */
internal fun modCodeLoadLabel(text: SettingsStrings, conclusion: ModCodeLoadConclusion?): String = when {
    conclusion == null -> text.modCodeLoadUnknown
    conclusion.outcome == CodeCarrierOutcome.LOADED -> text.modCodeLoadLoaded
    conclusion.outcome == CodeCarrierOutcome.ENGINE_TOO_OLD ->
        conclusion.engineGap?.let { text.modCodeLoadEngineTooOld(it.required, it.current) }
            ?: text.modCodeLoadUnknown
    else -> text.modCodeLoadSkipped
}

/**
 * 「最近一次装载结论」行：复用既有「标签：值」句式（[SettingsStrings.modManageDetailLine]），
 * 与 Mod 管理行的「代码」读数行同形。
 */
internal fun modCodeLoadLine(text: SettingsStrings, row: ModCodeRow): String =
    text.modManageDetailLine(text.modCodeLoadLabel, modCodeLoadLabel(text, row.loadConclusion))
