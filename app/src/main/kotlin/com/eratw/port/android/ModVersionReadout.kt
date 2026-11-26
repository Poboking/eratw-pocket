package com.eratw.port.android

import com.eratw.port.modapi.ModApi

/**
 * Mod 版本读数（VS-1）：把「作者面 API 版本 / 引擎版本 / 数据版本缺口」翻成设置页的几行小字。
 *
 * 背景：版本不兼容原先只在日志里出现 `mod.install pkg=… active=false failure=…`，玩家在设置页
 * 看不到任何原因；本文件只做**纯计算**（不做 IO、不碰会话对象、不造 mod 实例），三样读数各有各
 * 的真源：
 *
 * 1. **引擎版本**：[MOD_ENGINE_API_VERSION] 直接取 [ModApi.VERSION]（编译进 APK 的内核版本，
 *    与 `core-vm` 的 `ModRuntimeHost.enforceVersionGate` 判据同源）。
 * 2. **作者面 API 版本**：来自 build-time 载体声明
 *    （[ModCarrierRegistry.versionDeclarations]，即 `ModCarrierDeclaration.apiVersion`）。
 *    数据包 / 导入包没有 Kotlin 声明，读不到就**不显示**（不猜、不填默认值）。
 * 3. **数据版本缺口**：只有装载门真的判过才留得下两侧版本号——`ModVersionGate` 的三个异常构造
 *    参数都不是 `val`（消息本身就是玩家/作者可读的失败原因），app 侧拿不到字段，所以这里解析
 *    [ModManageDiagnostics] 通道里的两条 canonical 句式。**成功迁移后侧车记录已被落成新声明值**
 *    （`ModRuntimeHost.migrateForInstall`），缺口在读数时刻本来就不可观测，因此读数只可能来自
 *    拒载 / 迁移失败。解析不到或两侧相等 ⇒ 不显示（宁可少显示一行，不显示 0）。
 */
internal const val MOD_ENGINE_API_VERSION: Int = ModApi.VERSION

/** 数据版本缺口：存档侧车记录 [stored] vs mod 声明 [declared]（只会由迁移 / 拒载两种口径产生）。 */
internal data class ModDataVersionGap(val stored: Int, val declared: Int)

/**
 * 单个 Mod 的版本读数。
 *
 * [declaredApiVersion] 为 null = 这个包没有作者面声明（数据包 / 导入包 / 未登记的包）。
 */
internal data class ModVersionRow(
    val packageId: String,
    val declaredApiVersion: Int?,
    val engineApiVersion: Int,
    val dataVersionGap: ModDataVersionGap?,
) {
    /** 声明版本高于引擎 ⇒ 装配期必被版本门拒载（与 `enforceVersionGate` 的判据逐字一致）。 */
    val incompatible: Boolean
        get() = declaredApiVersion != null && declaredApiVersion > engineApiVersion
}

/**
 * 把「已装列表 + 载体声明 + 装载失败读数」拼成逐包版本行。
 *
 * [failures] 与 [ModManageDiagnostics.snapshot] 同一份（`pkg` → `简单类名: 消息`）。
 */
internal fun buildModVersionRows(
    packageIds: List<String>,
    declaredApiVersions: Map<String, Int>,
    engineApiVersion: Int,
    failures: Map<String, String>,
): List<ModVersionRow> = packageIds.map { packageId ->
    ModVersionRow(
        packageId = packageId,
        declaredApiVersion = declaredApiVersions[packageId],
        engineApiVersion = engineApiVersion,
        dataVersionGap = dataVersionGapOf(failures[packageId]),
    )
}

/** 拒载口径：`存档记录 v3，mod 只声明到 v1`（`ModDataVersionDowngradeException`）。 */
private val DOWNGRADE_GAP = Regex("""存档记录 v(-?\d+)，mod 只声明到 v(-?\d+)""")

/** 迁移失败口径：`数据迁移 v0→v2 失败`（`ModDataMigrationException`）。 */
private val MIGRATION_GAP = Regex("""数据迁移 v(-?\d+)→v(-?\d+) 失败""")

/**
 * 从失败原因里取数据版本缺口；拿不到、或两侧相等（不构成迁移 / 拒载）⇒ null。
 *
 * [reason] 是 `ModManageDiagnostics` 里那条 `简单类名: 消息` 的摘要（已被截到 160 字符）；
 * 两段数字都在摘要窗口内，截断掉后半句也不影响解析。
 */
internal fun dataVersionGapOf(reason: String?): ModDataVersionGap? {
    val groups = reason?.let { DOWNGRADE_GAP.find(it) ?: MIGRATION_GAP.find(it) }
    val stored = groups?.groupValues?.getOrNull(1)?.toIntOrNull()
    val declared = groups?.groupValues?.getOrNull(2)?.toIntOrNull()
    return if (stored == null || declared == null || stored == declared) {
        null
    } else {
        ModDataVersionGap(stored = stored, declared = declared)
    }
}

/**
 * 版本读数行：`API 版本 v13[ · 数据版本 v2→v0]`；没有可读数 ⇒ null。
 *
 * 不兼容时返回 null：那一行的警告文案自带「需要哪版 + 引擎到哪版」两个数字，再叠一行同数字
 * 的读数只是噪音。
 */
internal fun modVersionLine(text: SettingsStrings, row: ModVersionRow): String? {
    if (row.incompatible) return null
    val parts = mutableListOf<String>()
    row.declaredApiVersion?.let { parts += text.modVersionApi(it) }
    row.dataVersionGap?.let { parts += text.modVersionData(it.stored, it.declared) }
    return if (parts.isEmpty()) null else parts.joinToString(" · ")
}

/** 不兼容警告行：`不兼容（需要 API v14，引擎 v13）`；兼容 / 无声明 ⇒ null。 */
internal fun modVersionIncompatibleLine(text: SettingsStrings, row: ModVersionRow): String? {
    val declared = row.declaredApiVersion
    return if (declared != null && row.incompatible) {
        text.modVersionIncompatible(declared, row.engineApiVersion)
    } else {
        null
    }
}

/**
 * 想启用 [packageId] 时被版本门挡住的读数；兼容 / 无声明 / 不在表里 ⇒ null。
 *
 * 判据与不兼容行同源，界面据此在**启用方向**给明确提示、不写 marker（停用方向照旧——
 * `mods/rt2/<packageId>.off` 仍是唯一真源，玩家随时能把不兼容的关掉）。
 */
internal fun modVersionBlocksEnable(rows: List<ModVersionRow>, packageId: String): ModVersionRow? =
    rows.firstOrNull { it.packageId == packageId && it.incompatible }
