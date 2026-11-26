package com.eratw.port.android

import java.io.File

/**
 * 一次导入请求（UI 线程读好的快照集合，纯数据）。
 *
 * 为什么单独成型：多选（批导入）与单选（单包导入）是**两条入口、同一份事务实现**
 * （批导入逐项复用 [ModImportController.import]）。把「哪条入口 + 拼什么事件」这段
 * 判定搬出 Activity，一是让它可被纯 JVM 单测覆盖（Activity 侧只能靠源码哨兵），
 * 二是把 `runModImport` 压回可读长度（detekt `LongMethod` 60 行门槛）。
 *
 * [modRuntimeEnabled] / [codeGateEnabled] 都是**调用方在 UI 线程读好的快照**：
 * 后台导入线程不碰 SharedPreferences（AGENTS §6：语言/设置只经壳层单路取）。
 */
internal data class ModImportRequest(
    val picked: List<PickedModFile>,
    val readErrors: List<String>,
    val copy: ModImportCopy,
    val modRuntimeEnabled: Boolean,
    val codeGateEnabled: Boolean,
)

/**
 * 把一次导入请求翻译成一条 [ModImportEvent]（纯函数，唯一裁决点）。
 *
 * 三条分支与既有语义逐字对应：
 * 1. `picked.size > 1` ⇒ 批导入，事件固定 [ModImportEvent.BatchImportFinished]；
 * 2. `picked` 为空 ⇒ 没东西可导（预览期全部源读失败），固定 `Failed(STORAGE, allReadsFailed)`；
 * 3. 否则 ⇒ 单包导入（[ModImportEvent.ImportSucceeded] / [ModImportEvent.ImportFailed]）。
 *
 * 读失败兜底规则（承重）：预览期读不出来的源不在 `picked` 里，不能静默丢 ——
 * 批路径上「已有批级失败则原样优先」，单包路径上追加到失败文案尾部；
 * 成功事件不带读失败（成功页只说成功，读失败由预览阶段已上屏）。
 */
internal fun modImportEventFor(
    request: ModImportRequest,
    controller: ModImportController,
    report: ModPatchReportSnapshot?,
): ModImportEvent {
    val copy = request.copy
    if (request.picked.size > 1) {
        val batch = controller.importBatch(
            request.picked,
            copy,
            modRuntimeEnabled = request.modRuntimeEnabled,
        )
        val readFailure = if (request.readErrors.isEmpty()) {
            null
        } else {
            ModImportOutcome.Failed(ModImportFailureKind.STORAGE, request.readErrors.joinToString("\n"))
        }
        val keepBatch = readFailure == null || batch.batchFailure != null
        val merged = if (keepBatch) batch else batch.copy(batchFailure = readFailure)
        return ModImportEvent.BatchImportFinished(merged, report)
    }
    val outcome = if (request.picked.isEmpty()) {
        ModImportOutcome.Failed(
            ModImportFailureKind.STORAGE,
            copy.fill(copy.allReadsFailedFormat, request.readErrors.joinToString("\n")),
        )
    } else {
        controller.import(request.picked, copy, modRuntimeEnabled = request.modRuntimeEnabled)
    }
    return when (outcome) {
        is ModImportOutcome.Imported -> ModImportEvent.ImportSucceeded(outcome.detail, report)
        is ModImportOutcome.Failed -> ModImportEvent.ImportFailed(
            outcome.kind,
            if (request.readErrors.isEmpty()) outcome.message
            else outcome.message + "\n" + request.readErrors.joinToString("\n"),
            report,
        )
    }
}

/**
 * 存量 `.patch` 迁移段（相位 ⑤′，L5 / CONTRACT-RT2-M2LEGACY）。
 *
 * 从 [ModImportController.import] 的类体内搬出来（该类已贴 detekt `LargeClass` 600 行门槛），
 * 语义逐字保留：旧并集补丁 `kojo-imports.patch` 与旧 per-import `.patch` 只在「迁移成功 +
 * 三件套读回校验通过」后删；迁移器枚举盘上留档源（真相源，绝不读 `.patch` 内容），经
 * [importInner] 回调二次进入整条编译→转码→落盘链（调用方传 `legacyMigrationEnabled = false`
 * 防递归），产出独立 packageId 的同形三件套 —— 零第二份编译/转码实现。
 *
 * 无存量 `.patch` ⇒ `NoLegacy`，本函数零副作用（逐字节零变化）。失败告警返回给调用方，
 * 并入 `parseWarnings`，不拦截本次导入。
 */
internal fun migrateLegacyPatches(
    modsDir: File,
    importDiskNames: Set<String>,
    copy: ModImportCopy,
    importInner: (List<PickedModFile>) -> ModImportOutcome,
): List<String> {
    val migration = LegacyPatchMigrator.migrate(
        modsDir = modsDir,
        importDiskNames = importDiskNames,
        copy = copy,
        writePackage = { legacySources ->
            val picked = legacySources.map { PickedModFile(it.diskName, it.bytes) }
            when (val inner = importInner(picked)) {
                is ModImportOutcome.Imported -> MigrationWriteOutcome.Ok(
                    inner.detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION),
                )
                is ModImportOutcome.Failed -> MigrationWriteOutcome.Err(inner.message)
            }
        },
    )
    return when (migration) {
        is MigrationResult.Migrated -> {
            for (stale in migration.stalePatches) stale.delete()
            emptyList()
        }
        is MigrationResult.Kept -> migration.warnings
        MigrationResult.NoLegacy -> emptyList()
    }
}
