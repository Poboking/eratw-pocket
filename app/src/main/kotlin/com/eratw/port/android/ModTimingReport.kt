package com.eratw.port.android

import com.eratw.port.core.mod.ModRuntimeHost

/** 纳秒 → 微秒（归因日志的显示口径；账本内部仍是纳秒）。 */
private const val NANOS_PER_MICRO: Long = 1_000L

/**
 * 单包**整会话累计**耗时预算（纳秒，1 秒）——宿主 shutdown 上屏时注入 [reportModTiming]。
 *
 * 与 [com.eratw.port.core.mod.ModHandlerBudget.DEFAULT_THRESHOLD_NANOS]（单次调用 16ms）口径
 * 不同：那是每次处理器调用的慢调用阈值，这是整局里某个包所有 scope 累计耗时之和的上限。
 * 取 1 秒量级是为了只标记「确实吃掉整局可观时间」的包，避免把正常短会话刷成满屏告警。
 * 宿主可自行改传别的预算值；本常量只是当前装配的默认值，不是引擎契约。
 */
internal const val DEFAULT_PACKAGE_CUMULATIVE_BUDGET_NANOS: Long = 1_000_000_000L

/**
 * 把本会话账本按 mod 逐条上日志面（`mod.timing …`）。
 *
 * 用 `pkg=` 前缀而不是复用 `mod.install` / `mod.shutdown 完成` 的子串：那两类行有整行/计数
 * 断言（`ModRuntimeAssemblyWireTest`），归因行必须能被独立筛出来。纳秒转微秒（整数除法）
 * 只为读数好读，原始精度仍在账本里（作者侧 `context.timing`）。
 *
 * 放在 [ModRuntimeAssembly] 之外的文件里：那个文件的函数数已到 detekt `TooManyFunctions`
 * 的阈值，而「归因上屏」与「会话状态机」无关，是纯投影（一行一个 `(pkg, scope)`）。
 *
 * 装配侧不建账本（总闸关）时 [ModRuntimeHost.timing] 为 null ⇒ 本函数零输出，
 * 与「关态零日志」判据一致。
 *
 * ## 可选预算告警
 *
 * [budgetNanos] 是单包累计耗时上限（纳秒），由宿主注入；传 null 或 ≤ 0 表示不启用告警，
 * 逐条 `mod.timing` 行的格式与顺序逐字不变。启用后，累计耗时严格超预算的包在自身逐条行
 * 之后多打一行 `mod.timing.budget [WARN] …`（独立前缀，可被单独筛出；既有行不掺字段、
 * 不改文案）。预算与累计耗时都按微秒显示（与本文件既有显示口径一致），预算判定仍由
 * 账本在纳秒域完成（`ModTimingLedger.overBudgetPackages`）。
 */
internal fun reportModTiming(
    host: ModRuntimeHost,
    packageIds: List<String>,
    logSink: (String) -> Unit,
    budgetNanos: Long? = null,
) {
    val ledger = host.timing ?: return
    val overBudget = ledger.overBudgetPackages(budgetNanos)
    for (packageId in packageIds) {
        val packageSamples = ledger.forPackage(packageId)
        for (sample in packageSamples) {
            logSink(
                "mod.timing pkg=$packageId scope=${sample.scope} count=${sample.count} " +
                    "totalUs=${sample.totalNanos / NANOS_PER_MICRO} " +
                    "maxUs=${sample.maxNanos / NANOS_PER_MICRO} " +
                    "lastUs=${sample.lastNanos / NANOS_PER_MICRO}",
            )
        }
        if (packageId in overBudget && budgetNanos != null) {
            val totalNanos = packageSamples.sumOf { it.totalNanos }
            logSink(
                "mod.timing.budget [WARN] pkg=$packageId " +
                    "totalUs=${totalNanos / NANOS_PER_MICRO} " +
                    "budgetUs=${budgetNanos / NANOS_PER_MICRO} 累计耗时超预算",
            )
        }
    }
}
