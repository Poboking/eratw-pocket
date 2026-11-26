package com.eratw.port.android

import com.eratw.port.modapi.ModApi
import java.io.File

/**
 * 「导出 Mod 诊断」快照的**纯拼装器**（task-172）。
 *
 * 输入全是既有只读数据类（[ModManageScan] / [ModManageDiagnostics] 的快照表 /
 * [ModRuntimeData] 的两张逐包读数表 / [ModCodeOverview]），本文件不扫盘、不读会话、
 * 不打日志、不联网：同一份输入永远拼出同一份文本，所以能在 JVM 单测里逐行点名。
 * Android 侧只有壳层负责取数与弹窗（[SettingsActivity] 的导出卡），拼装语义全在这里。
 *
 * ## 约束（契约硬要求）
 *
 * 1. **不含值本体**：报告只带字节数 / 键数 / 已改项数这类计量，绝不拼设置值或存储值。
 *    输入模型里根本没有这些字段——这是结构性保证，不是过滤。
 * 2. **缺失照「—」**：显示名 / 版本 / 存储读数 / 已改项数 / 代码包读数缺失一律写
 *    [DIAGNOSTIC_DASH]，不写 `0` / `null` / 空串（玩家会把它误读成真实读数）。
 * 3. **两态分开**：代码包装载总闸关时写「未开启」，不写「没装」/空表——那是两件事。
 */
internal data class ModDiagnosticReportInput(
    /** 引擎（APK）版本，壳层传 `BuildConfig.VERSION_NAME`。 */
    val engineVersion: String,
    /** Mod 运行时总闸的**实际值**（[UiPrefs.modRuntime]）。 */
    val runtimeGate: Boolean,
    /** 代码包装载总闸的**实际值**（[UiPrefs.modDexLoaderEnabled]）。 */
    val dexLoaderGate: Boolean,
    /** 设置页同一次扫描的已装清单（含 skipped 明细）。 */
    val scan: ModManageScan,
    /** [ModManageDiagnostics.snapshot] 的装载失败表（packageId → 摘要）。 */
    val failures: Map<String, String> = emptyMap(),
    /** [ModRuntimeData.storageByPackage] 的存储段读数（null = 本包没有存储段）。 */
    val storageByPackage: Map<String, ModStorageReadout?> = emptyMap(),
    /** [ModRuntimeData.settingsChangedByPackage] 的已改设置项数（null = 不在本局账本）。 */
    val settingsChangedByPackage: Map<String, Int?> = emptyMap(),
    /** 代码包只读概览（null = 未取数；总闸关时仍要有对象，壳层照旧 `buildModCodeOverview` 取）。 */
    val codeOverview: ModCodeOverview? = null,
    /**
     * 代码包装载面读数（null = 未取数，快照不出本节；壳层接线时由 [readModCodeFace] 取）。
     *
     * 与 [codeOverview] 的分工：后者是设置页同口径的逐包汇总；本节补「运行期身份 / 两套
     * 身份的信任口径 / 盘面裁决 / 缺失的运行期装载结论」，纯取证，不参与任何裁决。
     */
    val codeFace: ModCodeFace? = null,
    /** 总闸缺省值，跟着 [UiPrefs] 常量走，报告里显式标「（默认开/关）」。 */
    val runtimeGateDefault: Boolean = UiPrefs.DEFAULT_MOD_RUNTIME,
    /** 代码包装载总闸缺省值（ADR-013 D5：默认关）。 */
    val dexLoaderGateDefault: Boolean = UiPrefs.DEFAULT_MOD_DEX_LOADER,
    /** 内核 Mod API 版本（[ModApi.VERSION]）。 */
    val modApiVersion: Int = ModApi.VERSION,
    /** 引擎侧 API 兼容表版本（[MOD_ENGINE_API_VERSION]）。 */
    val compatApiVersion: Int = MOD_ENGINE_API_VERSION,
)

/** 缺失读数的统一占位（与设置页既有「—」口径同一字符）。 */
internal const val DIAGNOSTIC_DASH: String = "—"

/**
 * 拼出多行中文诊断快照（取证面文本，含行尾换行规范：行间 `\n`、结尾不带空白行）。
 *
 * 版式固定为「头 → 每个已装 Mod 一段 → 尾」：头是引擎侧两把总闸与版本读数；每段一行包名
 * 加 8 行逐包读数；尾是读不出的 manifest 明细 + 会话内装载失败记录条数。块之间空一行，
 * 便于作者整段贴进反馈。
 */
internal fun buildModDiagnosticReport(input: ModDiagnosticReportInput): String {
    val lines = ArrayList<String>()
    lines += headerLines(input)
    for ((index, mod) in input.scan.mods.withIndex()) {
        lines += ""
        lines += modLines(index, mod, input)
    }
    input.codeFace?.let { face ->
        lines += ""
        lines += ModCodeFaceReadout.sectionLines(face, input.dexLoaderGateDefault)
    }
    lines += ""
    lines += tailLines(input)
    return lines.joinToString("\n")
}

/** 头：版本三行 + 两把总闸（实际值 + 缺省值）+ 已装计数（总数 / 启用 / 停用三值同现）。 */
private fun headerLines(input: ModDiagnosticReportInput): List<String> {
    val mods = input.scan.mods
    val enabled = mods.count { it.enabled }
    return listOf(
        "eratw Mod 诊断快照（只读）",
        "引擎版本：${input.engineVersion.ifBlank { DIAGNOSTIC_DASH }}",
        "Mod API 版本：v${input.modApiVersion}",
        "引擎 API 兼容版本：v${input.compatApiVersion}",
        "Mod 运行时总闸：${gateText(input.runtimeGate, input.runtimeGateDefault, offText = "关")}",
        "代码包装载总闸：${gateText(input.dexLoaderGate, input.dexLoaderGateDefault, offText = "未开启")}",
        "已安装 Mod：${mods.size} 个（启用 $enabled 个，停用 ${mods.size - enabled} 个）",
    )
}

/** 单段：包名标题 + 逐包读数（缺失照「—」，状态两态分开）。 */
private fun modLines(
    index: Int,
    mod: InstalledMod,
    input: ModDiagnosticReportInput,
): List<String> = listOf(
    "[${index + 1}] ${mod.packageId}",
    "${INDENT}名称：${mod.displayName.ifBlank { DIAGNOSTIC_DASH }}",
    "${INDENT}版本：${mod.version.ifBlank { DIAGNOSTIC_DASH }}",
    "${INDENT}来源：${sourceLabel(mod.source)}",
    "${INDENT}状态：${if (mod.enabled) "启用" else "已停用"}",
    "${INDENT}装载失败：${input.failures[mod.packageId] ?: DIAGNOSTIC_DASH}",
    "${INDENT}存储：${storageText(input.storageByPackage[mod.packageId])}",
    "${INDENT}已改设置：${settingsChangedText(input.settingsChangedByPackage[mod.packageId])}",
    "${INDENT}代码包：${codePackText(input.codeOverview, mod.packageId)}",
)

/** 尾：读不出的 manifest 明细（逐条原样）+ 会话内装载失败记录条数（诊断计数口径）。 */
private fun tailLines(input: ModDiagnosticReportInput): List<String> {
    val lines = ArrayList<String>()
    val skipped = input.scan.skipped
    lines += "无法读取清单：${skipped.size} 个"
    for (entry in skipped) {
        lines += "$INDENT- $entry"
    }
    lines += "装载失败记录：${input.failures.size} 条"
    return lines
}

/** 两态总闸读数：`开（默认开）` / `未开启（默认关）`——实际值与缺省值必须同时可见。 */
private fun gateText(actual: Boolean, default: Boolean, offText: String): String {
    val defaultText = if (default) "开" else "关"
    return "${if (actual) "开" else offText}（默认$defaultText）"
}

/** 存储段读数：`1.5 KB / 3 键`；没有存储段照「—」（不写 `0 B / 0 键`）。 */
private fun storageText(readout: ModStorageReadout?): String =
    readout?.let { "${formatStorageBytes(it.bytes)} / ${it.keys} 键" } ?: DIAGNOSTIC_DASH

/** 已改设置项数：`2 项`；不在本局账本照「—」。 */
private fun settingsChangedText(count: Int?): String = count?.let { "$it 项" } ?: DIAGNOSTIC_DASH

/**
 * 代码包读数：总闸关 → 「未开启」（不是「没装」，关态压根没扫 `code/`）；总闸开但本包没有
 * 产物行 → 「—」；有行 → `2 个版本 / 4.5 KB / 已信任`。
 */
private fun codePackText(overview: ModCodeOverview?, packageId: String): String = when {
    overview == null -> DIAGNOSTIC_DASH
    overview.gateOff -> "未开启"
    else -> overview.rows.firstOrNull { it.packageId == packageId }?.let { row ->
        "${row.versions} 个版本 / ${formatStorageBytes(row.bytes)} / ${if (row.trusted) "已信任" else "未信任"}"
    } ?: DIAGNOSTIC_DASH
}

/** 来源三值中文化（只影响这段取证文本，不参与装配裁决）。 */
private fun sourceLabel(source: ModSource): String = when (source) {
    ModSource.BUILTIN -> "内置"
    ModSource.DATA_PACKAGE -> "数据包"
    ModSource.IMPORTED -> "导入"
}

/** 逐包读数的缩进前缀（段内两格，固定不随语言变）。 */
private const val INDENT: String = "  "

// ---------------------------------------------------------------------------
// 代码包装载面（task-193）：只读取证，不是装载判据
// ---------------------------------------------------------------------------

/**
 * 代码包装载面的一行读数（**取证面，不是装载判据**）。
 *
 * 只复述 `code/` 目录、身份侧车与信任标记的现状；装载端有自己完整的闸序
 * （ABI → 总闸 → 磁盘身份 → 身份侧车 → 运行期身份信任 → 产物），本节故意不重放它，
 * 只把玩家可能踩到的盘面差异摆出来。运行时到底装载成功没有，由 [ModCodeFace.loadReport]
 * 传入的**本会话**装载结论复述；拿不到结论时照 [DIAGNOSTIC_DASH]，**不猜**。
 */
internal data class ModCodeFaceRow(
    /** 磁盘身份：`code/` 下的目录名（由产物内容派生，卸载/重导可能换代）。 */
    val diskPackageId: String,
    /** 身份侧车解析结果（缺侧车 = 装载回退磁盘身份；坏档 = 装载会拒）。 */
    val identity: CodeIdSidecar,
    /** 合法 `.mod` 产物个数（大于 1 = 多版本残留，装载会拒）。 */
    val versions: Int,
    /** 上述产物字节数合计。 */
    val bytes: Long,
    /** 信任标记与两套身份的对应关系（装载端按运行期身份查，设置页按磁盘身份写）。 */
    val trust: ModCodeTrustState,
    /** 盘面裁决：按装载端同源判据会卡在哪一步；null = 无阻挡。 */
    val hint: ModCodeGateHint?,
)

/** 信任标记与身份的口径对应（诊断面专用，不参与裁决）。 */
internal enum class ModCodeTrustState {
    /** 运行期身份有信任标记：装载端的信任闸会放行。 */
    TRUSTED,

    /** 只有磁盘身份有信任标记：运行期身份没标记，装载端仍判未信任。 */
    TRUSTED_ON_DISK_ID,

    /** 两套身份都没有信任标记。 */
    UNTRUSTED,

    /** 身份侧车坏了，运行期身份无法确定，信任无法判定。 */
    CORRUPT_IDENTITY,
}

/** 代码包装载面整体读数：总闸实际值 + 逐包行 + 目录读失败标记 + 本会话装载结论（读不到不猜）。 */
internal data class ModCodeFace(
    val dexLoaderEnabled: Boolean,
    val rows: List<ModCodeFaceRow> = emptyList(),
    val readFailed: Boolean = false,
    /**
     * 装载端发布的本会话结论（[ModCodeLoadReports.last]）；null = 本次快照没拿到
     * （导出早于装配完成 / 装载端未跑），逐包行照「—」口径写，不猜。
     */
    val loadReport: ModCodeLoadReport? = null,
)

/**
 * 取「代码包装载面」的盘面读数：**只读**、不抛、不建类加载器、不写盘。
 *
 * 总闸关时与 [buildModCodeOverview] 同口径早退，不碰 `code/`；[rt2Dir] 为 null 或
 * 目录整体不可读 → `readFailed = true`，正文照「—」，不猜一个「没有代码包」。
 *
 * @param loadReport 装载端已发布的本会话结论（生产壳层传 [ModCodeLoadReports.last]）；
 *   null 表示拿不到，逐包行的「最近一次装载结论」照「—（本会话未尝试装载，不猜）」。
 */
internal fun readModCodeFace(
    rt2Dir: File?,
    dexLoaderEnabled: Boolean,
    loadReport: ModCodeLoadReport? = null,
): ModCodeFace = ModCodeFaceReadout.read(rt2Dir, dexLoaderEnabled, loadReport)

/**
 * [readModCodeFace] 的读数实现（私有）。数据面只用既有只读入口——
 * [ModCodeStore.list]（产物）、[ModTrustStore.trustedPackages] /
 * [ModTrustStore.codePackages]（信任标记与 `code/` 目录）、[ModCodeIdentity.read]
 * （身份侧车）与 [modCodeGateHint]（盘面裁决，判据与装载端同源）。
 *
 * 收在私有 object 里而不是散成顶层函数：读侧与拼装侧分开，顶层函数数也不再膨胀。
 */
private object ModCodeFaceReadout {

    /** 关闸早退；rt2 未知/读坏 → 读失败两态，不猜「没有代码包」。 */
    fun read(
        rt2Dir: File?,
        dexLoaderEnabled: Boolean,
        loadReport: ModCodeLoadReport?,
    ): ModCodeFace = when {
        !dexLoaderEnabled -> ModCodeFace(dexLoaderEnabled = false)
        rt2Dir == null -> ModCodeFace(dexLoaderEnabled = true, readFailed = true)
        else -> runCatching { scan(rt2Dir, loadReport) }
            .getOrElse { ModCodeFace(dexLoaderEnabled = true, readFailed = true) }
    }

    /** 扫一遍 `code/` 目录：只认带合法产物的包，按包名升序（`codePackages` 已排序）。 */
    private fun scan(rt2Dir: File, loadReport: ModCodeLoadReport?): ModCodeFace {
        val store = ModCodeStore(rt2Dir)
        val trusted = ModTrustStore.trustedPackages(rt2Dir).toSet()
        val rows = ModTrustStore.codePackages(store.codeDir())
            .mapNotNull { pack -> row(rt2Dir, store, trusted, pack.packageId) }
        return ModCodeFace(dexLoaderEnabled = true, rows = rows, loadReport = loadReport)
    }

    /** 单包读数：产物 → 身份侧车 → 两套身份查信任 → 盘面裁决；无产物时不进列表。 */
    private fun row(
        rt2Dir: File,
        store: ModCodeStore,
        trusted: Set<String>,
        diskPackageId: String,
    ): ModCodeFaceRow? {
        val artifacts = runCatching { store.list(diskPackageId) }.getOrDefault(emptyList())
        val identity = ModCodeIdentity.read(rt2Dir, diskPackageId)
        val runtimePackageId = when (identity) {
            is CodeIdSidecar.Bound -> identity.runtimePackageId
            CodeIdSidecar.Absent -> diskPackageId
            CodeIdSidecar.Corrupt -> null
        }
        val trust = when {
            identity is CodeIdSidecar.Corrupt -> ModCodeTrustState.CORRUPT_IDENTITY
            runtimePackageId != null && runtimePackageId in trusted -> ModCodeTrustState.TRUSTED
            diskPackageId in trusted -> ModCodeTrustState.TRUSTED_ON_DISK_ID
            else -> ModCodeTrustState.UNTRUSTED
        }
        val hint = modCodeGateHint(
            packageId = diskPackageId,
            facts = ModCodeGateFacts(
                hasArtifacts = artifacts.isNotEmpty(),
                multipleVersions = artifacts.size > 1,
                identityCorrupt = identity is CodeIdSidecar.Corrupt,
                runtimeGateEnabled = true,
                trusted = trust == ModCodeTrustState.TRUSTED,
            ),
        )
        return if (artifacts.isEmpty()) {
            null
        } else {
            ModCodeFaceRow(
                diskPackageId = diskPackageId,
                identity = identity,
                versions = artifacts.size,
                bytes = artifacts.sumOf { it.sizeBytes },
                trust = trust,
                hint = hint,
            )
        }
    }
    /** 小节：总闸 + 逐包读数 + 口径说明；关闸与读失败都是两态分开写，不混成「没装」。 */
    fun sectionLines(face: ModCodeFace, dexLoaderGateDefault: Boolean): List<String> {
        val lines = ArrayList<String>()
        lines += CODE_FACE_TITLE
        lines += "${INDENT}第三方代码包装载总开关：${
            gateText(face.dexLoaderEnabled, dexLoaderGateDefault, offText = "未开启")
        }"
        when {
            !face.dexLoaderEnabled -> lines +=
                "${INDENT}未开启：本快照不扫描代码产物目录（总开关关时本机不跑第三方代码）"
            face.readFailed -> lines +=
                "${INDENT}代码产物目录：$DIAGNOSTIC_DASH（本次读不出，不展开逐包读数，不猜）"
            else -> {
                lines += "${INDENT}有代码产物的包：${face.rows.size} 个"
                for ((index, row) in face.rows.withIndex()) {
                    lines += ""
                    lines += rowLines(index, row, face.loadReport)
                }
                lines += ""
                lines += "$INDENT$CODE_FACE_NOTE"
            }
        }
        return lines
    }

    /** 单包段：磁盘身份标题 + 6 行读数（缺失一律「—」）。 */
    private fun rowLines(
        index: Int,
        row: ModCodeFaceRow,
        loadReport: ModCodeLoadReport?,
    ): List<String> = listOf(
        "[代码 ${index + 1}] 磁盘身份：${row.diskPackageId}",
        "${INDENT}运行期身份：${runtimeIdText(row)}",
        "${INDENT}信任状态：${trustText(row.trust)}",
        "${INDENT}代码产物：${row.versions} 个版本 / ${formatStorageBytes(row.bytes)}${
            if (row.versions > 1) "（多版本残留，装载会拒）" else ""
        }",
        "${INDENT}装载闸裁决：${hintText(row.hint)}",
        "${INDENT}最近一次装载结论：${loadConclusionText(loadReport, row)}",
    )

    /**
     * 单包「最近一次装载结论」：复述装载端发布的本会话结论（[ModCodeLoadReport]），
     * 不重拼、不改判、不重算版本缺口。
     *
     * 三态分开写：**没有结论对象**（导出早于装配 / 未跑）与**有对象但该包本次没跑到**
     * 是两件事，都不猜「没装」；有结论时按结局码映射成玩家能读的一句话。
     */
    private fun loadConclusionText(loadReport: ModCodeLoadReport?, row: ModCodeFaceRow): String {
        val record = loadReport?.let { recordOf(it, row) }
        return when {
            loadReport == null -> "$DIAGNOSTIC_DASH（本会话未尝试装载，不猜）"
            record == null -> "$DIAGNOSTIC_DASH（本会话未尝试装载）"
            record.outcome == CodeCarrierOutcome.LOADED -> "装载成功"
            record.outcome == CodeCarrierOutcome.DISABLED ->
                "未尝试装载（第三方代码包装载总开关未开启）"
            record.outcome == CodeCarrierOutcome.ENGINE_TOO_OLD -> record.engineTooOld
                ?.let { "引擎太旧：要求 v${it.required}、本机 v${it.current}" }
                ?: record.rejectReason
                ?: "引擎太旧（原因句缺失）"
            else -> record.rejectReason ?: "拒载（装载端未给出原因句）"
        }
    }

    /** 结论 join：先按磁盘身份（装载端快照同键），再按侧车声明的运行期身份兜底。 */
    private fun recordOf(loadReport: ModCodeLoadReport, row: ModCodeFaceRow): CodeCarrierRecord? =
        loadReport.recordFor(row.diskPackageId)
            ?: (row.identity as? CodeIdSidecar.Bound)
                ?.runtimePackageId
                ?.let { loadReport.recordForRuntimeId(it) }

    /** 运行期身份：侧车声明 > 回退磁盘身份 > 坏档照「—」（装载端此刻会拒，不替它编一个身份）。 */
    private fun runtimeIdText(row: ModCodeFaceRow): String = when (val identity = row.identity) {
        is CodeIdSidecar.Bound -> "${identity.runtimePackageId}（身份侧车声明）"
        CodeIdSidecar.Absent -> "${row.diskPackageId}（无侧车，装载回退磁盘身份）"
        CodeIdSidecar.Corrupt -> "$DIAGNOSTIC_DASH（身份侧车损坏，装载会拒）"
    }

    /** 信任状态：按「装载端查运行期身份」这一真实口径描述，磁盘身份上的旧标记单独点名。 */
    private fun trustText(trust: ModCodeTrustState): String = when (trust) {
        ModCodeTrustState.TRUSTED -> "已信任（运行期身份有标记，装载端同一口径）"
        ModCodeTrustState.TRUSTED_ON_DISK_ID ->
            "未信任（信任标记只在磁盘身份上，运行期身份缺标记，装载端仍判未信任）"
        ModCodeTrustState.UNTRUSTED -> "未信任（两套身份都没有信任标记）"
        ModCodeTrustState.CORRUPT_IDENTITY -> "无法判定（身份侧车损坏，装载会拒）"
    }

    /** 盘面裁决文案：与装载端同源判据，但只是「会卡在哪一步」，不代表本次会话真的装过。 */
    private fun hintText(hint: ModCodeGateHint?): String = when (hint?.kind) {
        null -> "无阻挡（前置条件齐，装载端可放行）"
        ModCodeGateHintKind.MULTIPLE_VERSIONS -> "多版本残留，装载会拒（重新导入，只留最新一份）"
        ModCodeGateHintKind.CODE_ID_CORRUPT -> "身份侧车损坏，装载会拒（重新导入可重建）"
        ModCodeGateHintKind.ENGINE_TOO_OLD -> "引擎太旧，装载会拒（该包要求的引擎版本高于本机）"
        ModCodeGateHintKind.GATE_OFF -> "总开关未开启，不装载"
        ModCodeGateHintKind.UNTRUSTED -> "未信任，装载会拒（设置页信任该包后才会装载）"
    }
}

/** 小节标题（取证面文本，不进 UI 文案表）。 */
private const val CODE_FACE_TITLE: String = "代码包装载面（只读取证）"

/** 小节尾注：把「盘面现状 + 本会话事实」与「未来装载判据」的边界写死，防止玩家误读。 */
private const val CODE_FACE_NOTE: String =
    "说明：本节是盘面取证 + 本会话装载结论，不是未来装载的判据；无记录时写「$DIAGNOSTIC_DASH」。"
