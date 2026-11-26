package com.eratw.port.android

import com.eratw.port.android.market.JsonNumber
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson

/**
 * 包元数据（`eratw-pack.json`）与代码产物内层清单（`code.mod` 里的 `manifest.json`）的
 * **双源漂移检测**（ADR-013 D1，2026-11）。
 *
 * ## 两个源各管什么
 *
 * 同一个导入包里，关于「这份代码需要什么引擎、入口在哪」存在两份作者声明：
 *
 * - 包根 `eratw-pack.json`（[PackMeta]）：装载闸 [ModDexGate] 读它的 `compiledSurface`、
 *   装配期读它的 `minApi` —— 这是**闸唯一认的**一份；
 * - `code.mod` 内层 `manifest.json`（ADR-013 D1）：描述**真跑的那份 dex**，但导入链只校验它的
 *   `packageId`，其余字段不进任何闸。
 *
 * 两份可能各说各话（作者重打包只更新一份 / 手改清单 / 工具链版本不一致）。届时闸对着「另一份
 * 元数据」放行或拒载：代码实际按 `compiledSurface=20` 编译、包元数据却写 18，装载闸就会认为它
 * 兼容；反过来则过度拒载。本文件只做**检测 + canonical 日志取证**，不做任何裁决策略：
 *
 * - 不拒收、不回落、不覆盖作者声明：导入结局与引入本检测前逐字节相同；
 * - 不决定「该信哪一份」——那要改闸的判据来源，属架构决策，不在本波（记 TODO(verify)，见
 *   [innerManifestFieldsOf] 的 KDoc）；
 * - 玩家侧不可见：只写 logcat WARN（[ModImportCodeArtifacts] 的默认 sink），排障时 grep。
 *
 * ## 对账字段与分级（先定表、再落码）
 *
 * 级别分两维：**CONFLICT**（两侧都声明且不等）按字段本身定级；**INNER_ONLY**（只有内层清单
 * 声明、包根没写该键）对「包根写侧本代不输出」的字段降为 INFO —— 那是预期形态，不是缺陷，
 * 但仍留一行事实，说明本次以包内清单为准。
 *
 * | 字段 | CONFLICT | INNER_ONLY | 为什么 |
 * |---|---|---|---|
 * | `compiledSurface` | ERROR | ERROR | 装载闸 [ModDexGate] 的 ABI 判据；两边各说各话 = 静默错判 |
 * | `minApi` | ERROR | ERROR | 装配期的引擎下限；判错直接把包挡在门外（装不起来） |
 * | `entrypoint` | ERROR | INFO | 装配期拿它 `loadClass` 找入口；值不同 = 装载器找不到类。包根写侧本代还不输出该键（见 [packRootFieldsOf]），内层单源属预期形态 |
 * | `version` | WARN | WARN | 只影响版本读数与上屏，不进任何闸 |
 * | `apiVersion` | WARN | INFO | 作者声明的契约版本；值不同多半只是作者声明滞后，不影响装载判定。包根写侧本代不输出该键 |
 * | `packageId` | 跳过 | 跳过 | 身份已由 [ModCodeIdentity] 归一后再判，见下节「为什么不报 packageId」 |
 *
 * `entrypoint` / `apiVersion` 在 [PackMeta] 侧没有采信面（`ModImportVersion.kt:363-400` 只读
 * schema / packageId / version / minApi / resources / compiledSurface 六项），故本文件按
 * [rawPackMetaPackageId] 的同一种取件口径（同一组常量、同一个条目名 [PACK_META_ENTRY_NAME]、
 * 同一份 schema 闸）另读一份 [PackRootFields] 补齐「包侧」值。**为什么必须独立读一次**：
 * [PackMeta] 的定义与解析都在 `ModImportVersion.kt`，该文件不在本波授权面（改它要和装载闸的
 * 判据来源一起动，属架构决策）；而 [rawPackMetaPackageId] 已有「同一份 zip 双扫」的既有先例
 * （`ModCodeIdentity.kt:151-169`），代价只发生在带 `code.mod` 的包上、条目 ≤
 * [PACK_META_MAX_ENTRY_BYTES]，故沿同一写法。别把它当重复实现删掉——删掉就只剩内层单源。
 *
 * ## 为什么不报 `packageId`
 *
 * 包级清单的 `packageId` 是**作者声明的运行期身份**，导入期经 [ModCodeIdentity] 归一
 * （`ModCodeIdentity.kt:34-42` 定义、`:92` 写入侧车）：[ModImportCodeArtifacts.stage] 先判
 * 「声明了就必须形态合法」（`ModImportCodeArtifacts.kt:261-268`），再判「内层 `packageId` 必须
 * 等于归一后的运行期身份」（`:316-324`），不一致直接 IDENTITY 拒收。检测点（[reportMetaDrift]
 * 的接线在身份闸之后，`:282`）到这里必然一致，再报一遍只是噪声。
 *
 * 结构上也锁死：内层对账面 [InnerManifestFields] **不含** `packageId`，[DRIFT_FIELD_SPECS] 里
 * 也没有该字段，想报都报不出来——单测 `packageId 不参与对账` 锁住这个口径。
 *
 * ## 为什么不报「仅包元数据声明」
 *
 * 内层清单没写某个字段**不构成矛盾**：闸读的是包侧声明，代码侧沉默不等于自相矛盾。若也上报，
 * 所有老格式 `code.mod` / 精简清单都会刷一行噪声，把真正有用的漂移埋掉。故只报两种确定形态：
 * 两侧都有值且不等、只有内层声明（闸读不到代码自述的 ABI 证据）——见 [PackMetaDriftKind]。
 *
 * ## 严重度由「字段类 × 形态」两维决定
 *
 * CONFLICT（两侧都声明且不等）按字段类定级：ABI / 入口类字段
 * （`compiledSurface` / `minApi` / `entrypoint`）漂移 = [PackMetaDriftSeverity.ERROR]——闸/装配
 * 拿到的判据与真跑代码各说各话，属静默错判或装不起来的风险；信息类字段（`version` /
 * `apiVersion`）漂移 = WARN——只影响读数与上屏，不影响装载判定。
 *
 * INNER_ONLY（只有内层声明）对包根写侧本代**不输出**的两个键（`entrypoint` / `apiVersion`）
 * 降为 [PackMetaDriftSeverity.INFO]：写侧不写是预期形态，不是缺陷，但仍留一行事实说明「本次以
 * 包内清单为准」；其余字段的 INNER_ONLY 维持其字段级（见 [DriftFieldSpec.innerOnlySeverity]）。
 */
internal enum class PackMetaDriftSeverity {
    /** ABI/入口类字段漂移：装载闸/装配闸的判据与内层代码清单自述不一致（静默错判风险）。 */
    ERROR,

    /** 信息类字段漂移：只影响版本读数/排障，不影响装载判定。 */
    WARN,

    /** 预期形态的取证记录：包根写侧本代不输出该键，内层单源属正常，仅记录本次采信哪一侧。 */
    INFO,
}

/** 漂移形态（只列「确定矛盾」的两种；仅包侧声明的形态刻意不报，理由见文件 KDoc）。 */
internal enum class PackMetaDriftKind {
    /** 两侧都声明了该字段，且值不同。 */
    CONFLICT,

    /** 只有内层代码清单声明；包元数据侧缺失或未采信（即闸读不到这份证据）。 */
    INNER_ONLY,
}

/**
 * 一条双源漂移读数（纯数据，不含任何处置）。
 *
 * @property field 字段名，与 `eratw-pack.json` / 内层 `manifest.json` 的同名键一致
 * @property severity 字段类决定的严重度（见 [PackMetaDriftSeverity]）
 * @property kind 漂移形态（见 [PackMetaDriftKind]）
 * @property packMetaValue 包元数据侧**采信值**（[PackMeta] / [PackRootFields] 语义）；
ull` = 未声明/未采信
 * @property innerValue 内层代码清单侧声明值（字符串化，仅用于取证与日志）
 */
internal data class PackMetaDrift(
    val field: String,
    val severity: PackMetaDriftSeverity,
    val kind: PackMetaDriftKind,
    val packMetaValue: String?,
    val innerValue: String,
)

/**
 * 内层 `manifest.json` 里参与对账的字段（ADR-013 D1 字段表的子集）。
 *
 * 只收「双源都有承载面」的五个键：`version` / `compiledSurface` / `minApi` / `entrypoint` /
 * `apiVersion`。`requires` / `displayName` / `permissions` 不在 [PackMeta] 侧、也不在包根
 * [PackRootFields] 侧，单源无法对账（强行按「内层独有 ⇒ 漂移」上报会在每个正常代码包上刷噪声）。
 * `packageId` 不参与：身份一致性已由 [ModCodeIdentity] 归一 + [ModImportCodeArtifacts] 的
 * IDENTITY 分档单独判（文件 KDoc「为什么不报 packageId」）。
 *
 * @property version 内层声明的版本号；空串按未声明处理
 * @property compiledSurface 内层声明的 ABI surface；非整数/缺失 = 
ull`
 * @property minApi 内层声明的引擎下限；`0`/负数按未声明处理（与 [PackMeta.minApi] 的 0 口径一致）
 * @property entrypoint 内层声明的入口类（装配期 `loadClass` 的唯一来源，
 *   `ModCodeCarrierProvider.kt:368-378`）；空串按未声明处理
 * @property apiVersion 内层声明的契约版本；`0`/负数按未声明处理
 */
internal class InnerManifestFields(
    val version: String?,
    val compiledSurface: Int?,
    val minApi: Int?,
    val entrypoint: String? = null,
    val apiVersion: Int? = null,
)

/**
 * 包根 `eratw-pack.json` 里参与对账、但 [PackMeta] 没有采信面的字段。
 *
 * 只收 `entrypoint` / `apiVersion` 两个键；
ull` = 该侧未声明（键缺失/类型不符/整份未采信）。
 * 取件与 [rawPackMetaPackageId] 同口径：`archiveZips` 里第一个带元数据条目的包胜，`schema` 必须
 * 等于 [PACK_META_SCHEMA]，否则整份按「没有元数据」处理（不猜未来格式）。
 */
internal class PackRootFields(
    val entrypoint: String? = null,
    val apiVersion: Int? = null,
)

/**
 * 内层 `manifest.json` → 参与对账的字段。
 *
 * 解析口径与 [ModImportCodeArtifacts] 校验 `packageId` 时完全一致（同一份 [MarketJson]、同一
 * UTF-8 解码）：坏 JSON / 顶层非对象 ⇒ 
ull`（该情形早已在身份校验阶段判 STORAGE，到这里只是
 * 防御）；键缺失或类型不符 ⇒ 对应字段 
ull`（视为该侧未声明，不猜值）。
 *
 * TODO(verify)：`requires` / `displayName` / `permissions` 在包侧没有承载面，暂不可对账；将来
 * 任一侧扩出这些键时，把对应键补进 [InnerManifestFields] 与 [DRIFT_FIELD_SPECS] 才能覆盖。
 */
internal fun innerManifestFieldsOf(manifestBytes: ByteArray?): InnerManifestFields? {
    val node = manifestBytes
        ?.let { bytes -> MarketJson.parseOrNull(String(bytes, Charsets.UTF_8)) as? JsonObject }
        ?: return null
    return innerFieldsOfObject(node)
}

/** 已确认是 JSON 对象的清单 → 字段集合（键缺失/类型不符各自落 
ull`，不上抛）。 */
private fun innerFieldsOfObject(node: JsonObject): InnerManifestFields = InnerManifestFields(
    version = (node.opt(KEY_VERSION) as? JsonString)?.value?.takeIf { it.isNotEmpty() },
    compiledSurface = (node.opt(KEY_COMPILED_SURFACE) as? JsonNumber)?.intValueOrNull(),
    minApi = (node.opt(KEY_MIN_API) as? JsonNumber)?.intValueOrNull()?.takeIf { it > 0 },
    entrypoint = (node.opt(KEY_ENTRYPOINT) as? JsonString)?.value?.trim()?.takeIf { it.isNotEmpty() },
    apiVersion = (node.opt(KEY_API_VERSION) as? JsonNumber)?.intValueOrNull()?.takeIf { it > 0 },
)

/**
 * 包根元数据 → [PackRootFields]（只读取、不上抛；缺条目/坏档/不认识的 schema 一律当未声明）。
 *
 * 与 [packMetaOf] 各扫一遍 zip（同 [rawPackMetaPackageId] 的既有口径：只在带 `code.mod` 的包上
 * 调用，条目 ≤ [PACK_META_MAX_ENTRY_BYTES]，代价可忽略）。为什么不并进 [PackMeta]：其定义文件
 * 不在本波授权面，且扩「采信面」要动装载闸判据来源，属架构决策，不在本波。
 *
 * TODO(verify)：包根写侧（`tools/modgen/.../PackModMeta.kt:85-91`）本代只输出 schema /
 * packageId / version / minApi / resources / compiledSurface，**不产出** `entrypoint` /
 * `apiVersion`；写侧接线之前现网包这两个键恒为「包侧未声明」，内层有值时会以内层单源形态上报
 * （见 [InnerManifestFields] 与 [DRIFT_FIELD_SPECS]）。写侧接线不在本波授权面，需组长另接线。
 */
internal fun packRootFieldsOf(archiveZips: List<ByteArray>): PackRootFields {
    val entry = archiveZips.firstNotNullOfOrNull { zipBytes ->
        // 逐包防抛：坏 zip / 坏压缩流在取证面上只当「这份没有元数据」，继续看下一个包。
        runCatching {
            ModZip.fixedEntryBytes(zipBytes, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES)
        }.getOrNull()
    }
    val obj = entry?.let { bytes -> MarketJson.parseOrNull(String(bytes, Charsets.UTF_8)) as? JsonObject }
    // schema 闸与 parsePackMeta 同判据：认不得的 schema 整份按「没有元数据」处理（不猜未来格式）。
    return obj?.takeIf { node -> (node.opt(KEY_SCHEMA) as? JsonNumber)?.intValueOrNull() == PACK_META_SCHEMA }
        ?.let(::packRootFieldsOfObject)
        ?: PackRootFields()
}

/** 已确认 schema 合法的包根对象 → 增量字段（键缺失/类型不符各自落 
ull`，不上抛）。 */
private fun packRootFieldsOfObject(node: JsonObject): PackRootFields = PackRootFields(
    entrypoint = (node.opt(KEY_ENTRYPOINT) as? JsonString)?.value?.trim()?.takeIf { it.isNotEmpty() },
    apiVersion = (node.opt(KEY_API_VERSION) as? JsonNumber)?.intValueOrNull()?.takeIf { it > 0 },
)

/**
 * 单个可对账字段的取值口径。
 *
 * 两侧各自把「本侧声明值」映射成字符串（
ull` = 本侧未声明/未采信），比较即字符串相等——两个
 * 源都是 JSON 标量、没有需要归一化的语义（不做 semver 排序，与 [ImportVersionChange] 同口径）。
 * 内层侧在此再归一化一次（空串 / 0 / 负数按未声明），使「直接构造 [InnerManifestFields]」与
 * 「经 [innerManifestFieldsOf] 解析」两条入口的口径一致，不产生假漂移。
 * 用函数而非反射或 `Map<Any?, Any?>`：字段表增删时编译器逼着两行取值都写全。
 *
 * @property packValue 包侧取值：包根采信面（[PackMeta]）+ 增量键（[PackRootFields]）
 * @property innerValue 内层清单取值
 * @property innerOnlySeverity 「只有内层声明」形态下的严重度，缺省跟随 [severity]；包根写侧
 *   本代不输出的键传 [PackMetaDriftSeverity.INFO]（预期形态，不是缺陷）
 */
private class DriftFieldSpec(
    val field: String,
    val severity: PackMetaDriftSeverity,
    val packValue: (PackMeta, PackRootFields) -> String?,
    val innerValue: (InnerManifestFields) -> String?,
    val innerOnlySeverity: PackMetaDriftSeverity = severity,
)

/**
 * 对账字段表：顺序即上报顺序（ERROR 簇在前，日志里先看到错误级）。
 *
 * 严重度按字段类写死在这里（`compiledSurface` / `minApi` / `entrypoint` = ERROR，
 * `version` / `apiVersion` = WARN），与文件 KDoc 的分级表一一对应。`entrypoint` / `apiVersion`
 * 的「内层单源」形态另记 INFO：包根写侧本代不输出这两个键，属预期形态。
 */
private val DRIFT_FIELD_SPECS: List<DriftFieldSpec> = listOf(
    DriftFieldSpec(
        field = "compiledSurface",
        severity = PackMetaDriftSeverity.ERROR,
        packValue = { meta, _ -> meta.compiledSurface?.toString() },
        innerValue = { fields -> fields.compiledSurface?.toString() },
    ),
    DriftFieldSpec(
        field = "minApi",
        severity = PackMetaDriftSeverity.ERROR,
        packValue = { meta, _ -> meta.minApi.takeIf { it > 0 }?.toString() },
        innerValue = { fields -> fields.minApi?.takeIf { it > 0 }?.toString() },
    ),
    DriftFieldSpec(
        field = "entrypoint",
        severity = PackMetaDriftSeverity.ERROR,
        packValue = { _, root -> root.entrypoint?.trim()?.takeIf { it.isNotEmpty() } },
        innerValue = { fields -> fields.entrypoint?.trim()?.takeIf { it.isNotEmpty() } },
        innerOnlySeverity = PackMetaDriftSeverity.INFO,
    ),
    DriftFieldSpec(
        field = "version",
        severity = PackMetaDriftSeverity.WARN,
        packValue = { meta, _ -> meta.declaredVersion },
        innerValue = { fields -> fields.version?.takeIf { it.isNotEmpty() } },
    ),
    DriftFieldSpec(
        field = "apiVersion",
        severity = PackMetaDriftSeverity.WARN,
        packValue = { _, root -> root.apiVersion?.takeIf { it > 0 }?.toString() },
        innerValue = { fields -> fields.apiVersion?.takeIf { it > 0 }?.toString() },
        innerOnlySeverity = PackMetaDriftSeverity.INFO,
    ),
)

/**
 * 计算双源漂移（纯函数：不写日志、不改任何状态，便于表驱动单测）。
 *
 * 判定口径（严格按此顺序逐字段套用）：
 *
 * 1. 内层清单未声明该字段 ⇒ **不报**（仅包侧声明不构成矛盾，见文件 KDoc）；
 * 2. 两侧都有值且相等 ⇒ 不报；
 * 3. 两侧都有值但不同 ⇒ [PackMetaDriftKind.CONFLICT]；
 * 4. 内层有值、包元数据侧缺失/未采信 ⇒ [PackMetaDriftKind.INNER_ONLY]（闸读不到内层自述的 ABI
 *    证据，属静默错判风险）。
 *
 * 严重度由「字段类 × 形态」两维决定（见 [DRIFT_FIELD_SPECS]）：CONFLICT 取字段级；
 * INNER_ONLY 取字段的 [DriftFieldSpec.innerOnlySeverity]（包根写侧不输出的键降为 INFO）。
 *
 * @param packRoot 包根增量字段（[packRootFieldsOf]）；纯单测可直接构造，缺省即「包侧未声明」
 */
internal fun packMetaDriftOf(
    meta: PackMeta,
    fields: InnerManifestFields,
    packRoot: PackRootFields,
): List<PackMetaDrift> = DRIFT_FIELD_SPECS.mapNotNull { spec -> driftOf(spec, meta, packRoot, fields) }

/** 单字段判定：
ull` = 该字段不构成矛盾（内层未声明 / 两侧相等 / 仅包侧声明）。 */
private fun driftOf(
    spec: DriftFieldSpec,
    meta: PackMeta,
    packRoot: PackRootFields,
    fields: InnerManifestFields,
): PackMetaDrift? = spec.innerValue(fields)?.let { inner ->
    val packMeta = spec.packValue(meta, packRoot)
    if (packMeta == inner) {
        null
    } else {
        val innerOnly = packMeta == null
        PackMetaDrift(
            field = spec.field,
            severity = if (innerOnly) spec.innerOnlySeverity else spec.severity,
            kind = if (innerOnly) PackMetaDriftKind.INNER_ONLY else PackMetaDriftKind.CONFLICT,
            packMetaValue = packMeta,
            innerValue = inner,
        )
    }
}

/** 双源漂移日志的稳定前缀（canonical 取证面；玩家不可见，只进 logcat）。 */
internal const val META_DRIFT_LOG_PREFIX: String = "mod.import 元数据双源漂移"

/**
 * 一条漂移的稳定日志文案：`前缀 severity=… field=… kind=… eratw-pack=… 内层manifest=…`。
 *
 * 格式是对外承诺（排障 grep / 单测断言关键子串）：字段名用清单键名原文，缺失侧固定写
 * `<未声明>`；两侧值都压成单行，防恶意清单用换行污染 logcat 行结构。不含任何玩家可见文案。
 */
internal fun packMetaDriftLogLine(drift: PackMetaDrift): String {
    val packMeta = drift.packMetaValue?.let(::oneLineLogValue) ?: MISSING_VALUE_TEXT
    val inner = oneLineLogValue(drift.innerValue)
    return "$META_DRIFT_LOG_PREFIX severity=${drift.severity.name} field=${drift.field} " +
        "kind=${drift.kind.name} eratw-pack=$packMeta 内层manifest=$inner"
}

/** 缺失侧在日志里的固定占位（测试与排障按此 grep）。 */
private const val MISSING_VALUE_TEXT: String = "<未声明>"

/** 包根元数据 `schema` 键（与 `ModImportVersion.kt` 的 [PACK_META_SCHEMA] 闸同键名）。 */
private const val KEY_SCHEMA: String = "schema"

/** 内层清单 / 包根参与对账的键名（ADR-013 D1 字段名原文；两源同名同键）。 */
private const val KEY_VERSION: String = "version"
private const val KEY_COMPILED_SURFACE: String = "compiledSurface"
private const val KEY_MIN_API: String = "minApi"
private const val KEY_ENTRYPOINT: String = "entrypoint"
private const val KEY_API_VERSION: String = "apiVersion"

/** 可打印字符下界（空白/控制字符统一压成 `?`，保证一条漂移只占一行日志）。 */
private const val FIRST_PRINTABLE_CODE: Int = 0x20

/** 声明值 → 单行日志值（控制字符换 `?`；正常值原样返回）。 */
private fun oneLineLogValue(value: String): String =
    value.map { ch -> if (ch.code < FIRST_PRINTABLE_CODE) '?' else ch }.joinToString("")
