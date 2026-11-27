package com.eratw.port.android

import com.eratw.port.android.market.JsonNumber
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson
import com.eratw.port.android.market.PACK_ID_RE
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.modapi.ModApi
import java.io.File

/**
 * 本次导入相对盘上同包旧产物的版本关系（BU：升级 / 重装 / 新增三态）。
 *
 * ## 为什么只做字符串相等比较
 *
 * `ModuleDescriptor.version` 在 core 侧是**纯字符串**（没有强校验，见 ManifestCodec 的段语义），
 * 项目也没有版本号排序规则。这里只回答「同不同」，不发明 semver / 字典序：降级（旧 2.0 → 新 1.0）
 * 与升级同样落 [Changed]，读数原样显示，绝不拦导入——拦旧包会挡住作者回滚这一正常动作。
 *
 * ## 旧版本从哪来
 *
 * 导入前读 `mods/rt2/<packageId>.manifest` 的 version（[installedManifestOf]）。文件不在、读不出、
 * 解码失败一律返回 null ⇒ 按**首次安装**处理、导入照常进行（旧 manifest 坏了不能反过来把新包拒收，
 * ⑤ 落盘本来就会把它原地替换掉）。**同一份旧 descriptor 也用来回读 min-api**：重编路径不重新
 * 获取作者声明，按盘上旧 manifest 的 `minApi` 保留（与 version 回落同口径，读不到 = 0）。
 *
 * ## 版本号从哪来（真链路）
 *
 * 真源 = 作者包里的**包级元数据条目** [PACK_META_ENTRY_NAME]（`pack-mod` 从模块 `@ModCarrier`
 * 的 `modDeclarations(..., version = …)` 抽出、写进 zip 根；见 tools/modgen 的 `PackModMeta`）。
 * 导入端用 [packMetaOf] 读它，经 `PatchImportTranscoder.transcode(..., version = …)` 落到产物
 * manifest，于是同一包 v1 → v2 重导时 [versionChangeOf] 能给 [ImportVersionChange.Changed]。
 *
 * 同一份元数据的可选 `minApi` 键是**作者声明的引擎下限**（`pack-mod --min-api <N>`）：导入端只把它
 * 搬运进产物 manifest 的 `/6` `min-api` 段（`ModuleDescriptor.minApi`），**不在导入时判**——真正的
 * 闸在装配期（`ModChainAssembly`：任一包 minApi > `ModApi.VERSION` ⇒ 整链拒载并逐包归因）。
 * 缺失 / 0 = 不设闸（老包行为逐字节不变）。
 *
 * 缺失 / 坏档 / schema 不认识 / 版本号形态非法一律回落 [PACK_META_FALLBACK_VERSION]（`"1"`，
 * = core 历史硬编码值）⇒ 老包（没有元数据条目）行为与改造前逐字相同，读数仍是 v1 → v1「重装」。
 */
sealed interface ImportVersionChange {

    /** 盘上没有同包旧 manifest（或旧 manifest 读数坏掉）：按新增处理，不额外报一行。 */
    data object FirstInstall : ImportVersionChange

    /** 盘上有旧包，且新版本号与旧版本号相等（或新版本号缺失、无从断言变化）：重装同一版本。 */
    data class Reinstall(val version: String) : ImportVersionChange

    /** 新旧版本号不等：只做字符串比较，降级同样是这一态、原样显示「旧 → 新」。 */
    data class Changed(val oldVersion: String, val newVersion: String) : ImportVersionChange
}

/**
 * 三态判据（纯函数，单测直接点名）。
 *
 * 规则顺序即语义：
 * 1. 旧版本读不到（null）⇒ [ImportVersionChange.FirstInstall]；
 * 2. 旧或新版本号缺失（空串）⇒ [ImportVersionChange.Reinstall]（没有可比的版本号，不当升级）；
 * 3. 新旧相等 ⇒ [ImportVersionChange.Reinstall]；
 * 4. 其余（含降级）⇒ [ImportVersionChange.Changed]，读数原样、不拦。
 */
internal fun versionChangeOf(installedVersion: String?, importedVersion: String): ImportVersionChange =
    when {
        installedVersion == null -> ImportVersionChange.FirstInstall
        installedVersion.isEmpty() || importedVersion.isEmpty() ->
            ImportVersionChange.Reinstall(importedVersion)
        installedVersion == importedVersion -> ImportVersionChange.Reinstall(importedVersion)
        else -> ImportVersionChange.Changed(installedVersion, importedVersion)
    }

/**
 * 导入前读盘上同包旧 manifest 的 descriptor；缺失 / 读失败 / 解码失败一律 null（不抛）。
 *
 * 版本读数与 min-api 回落共用这一个读点（[compileToModule] 只在调用方没给作者声明时读它）：
 * 旧 manifest 坏了不能反过来把新包拒收，⑤ 落盘本来就会把它原地替换掉。
 *
 * @param rt2Dir `mods/rt2`（manifest 与四件套同目录）
 * @param packageId 本次导入的包 ID（与产物同名，比较的正是这一包）
 */
internal fun installedManifestOf(rt2Dir: File, packageId: String): ModuleDescriptor? {
    val manifest = File(rt2Dir, packageId + ModPackageInventory.MANIFEST_EXTENSION)
    if (!manifest.isFile) return null
    return runCatching { ManifestCodec.decode(manifest.readBytes()) }.getOrNull()
}

/**
 * 调用方对产物 manifest 的**作者声明面**（[ModImportController.compileToModule] 的入参）。
 *
 * 三个字段各有「没说」的语义与回落口径 —— 缺省 `ModuleDeclarations()` 就是三样都没说，
 * 正是重编路径（[ModRecompiler]）的形态：盘上产物已定型，重编不再获取新的作者声明。
 *
 * - [overrides]：包清单的可选收窄声明（[import] 的同名参数）。`null` = 未声明 ⇒ 同名 ∩
 *   base 自动推导；这是收窄，**不是**白名单，null 与空表都要走推导（语义见 `OverrideInputs`）。
 * - [version]：产物 manifest 的版本号。`null` = 作者没自述版本 ⇒ 回落到**盘上旧产物的
 *   version**，再回落到 [PACK_META_FALLBACK_VERSION]。这条回落是「重编不该把已导入的 v2
 *   降级成 v1」（否则下一次导入会误报「v2 → v1」）。导入路径恒传非空值（`packMetaOf` 的
 *   结果，缺元数据时本身就是回落值），老包行为与改造前逐字相同。
 * - [minApi]：`min-api` 段（作者声明的引擎下限；0 = 不设闸，见 `ModuleDescriptor.minApi`）。
 *   `null` = 没有作者声明 ⇒ 回落到**盘上旧 manifest 的 `minApi`**——同样是不许重编把已声明
 *   的下限悄悄抹掉（作者改源重编后包会突然在旧引擎上通过装配）。`/6` 段只在非 0 时写
 *   （见 `ManifestCodec`）。
 */
internal data class ModuleDeclarations(
    val overrides: List<String>? = null,
    val version: String? = null,
    val minApi: Int? = null,
)

/**
 * 成功页的版本读数行（纯函数，判据与 [ImportedPatchDetail.resourceSummaryLine] 同款）。
 *
 * - 首次安装 ⇒ null（整行不出；成功详情行已说「导入成功」）；
 * - 重装 / 版本变化 ⇒ 一行读数，并明说「设置与数据保留」（该行文案内嵌，见 [ModImportCopy]）；
 * - 版本号缺失时走无版本号的短格式（`v` 前缀写在文案表格式串里，不在代码里拼字面量）。
 */
internal fun ImportedPatchDetail.versionChangeSummaryLine(copy: ModImportCopy): String? =
    when (val change = versionChange) {
        ImportVersionChange.FirstInstall -> null
        is ImportVersionChange.Reinstall ->
            if (change.version.isEmpty()) {
                copy.reinstallNoVersionSummaryFormat
            } else {
                copy.fill(copy.reinstallSummaryFormat, change.version)
            }
        is ImportVersionChange.Changed -> copy.fill(
            copy.versionChangedSummaryFormat,
            change.oldVersion,
            change.newVersion,
        )
    }

/** 包级元数据在 zip 里的固定条目名（zip 根、大小写敏感）。 */
internal const val PACK_META_ENTRY_NAME = "eratw-pack.json"

/** 元数据 schema 版本：认不出的 schema 一律按「没有元数据」处理（不猜未来格式）。 */
internal const val PACK_META_SCHEMA = 1

/** 元数据条目字节上限：真实元数据 < 200 字节，超限条目按「没有元数据」处理（不读进堆）。 */
internal const val PACK_META_MAX_ENTRY_BYTES = 4096

/** 元数据缺失/不可信时的版本读数：与 core 转码器历史硬编码逐字相同。 */
internal const val PACK_META_FALLBACK_VERSION = "1"

/** 版本号上限：超过即判形态非法（合法版本号是作者手写的短串，长串只会是坏档）。 */
private const val PACK_META_MAX_VERSION_LENGTH = 32

/** 元数据里可选的 min-api 键（作者声明的最低 mod-api 版本；缺失 = 不设闸）。 */
private const val PACK_META_MIN_API_KEY = "minApi"

/** 元数据里可选的旁挂资源目录名键（ADR-014 D2；缺失 = 不带旁挂资源，老包常态）。 */
private const val PACK_META_RESOURCES_KEY = "resources"

/** 旁挂资源目录名长度上限：作者手写的短目录名，超出只可能是坏档（ADR-014 D4）。 */
private const val PACK_META_MAX_RESOURCES_NAME_LENGTH = 64

/** 元数据里可选的编译期 mod-api surface 键（ADR-013 D2 闸 2；缺失 = 未声明，老包常态）。 */
private const val PACK_META_COMPILED_SURFACE_KEY = "compiledSurface"

/**
 * `compiledSurface` 接纳上限（含）：作者声明的是「编译期 mod-api surface 序号」，只会是小整数；
 * 超过它只可能来自坏档/误写。**上限本身不是 ABI 判据**（真判据是它 ≤ 当前引擎 API），
 * 清掉坏值只是为了不让坏档把读数写进诊断。与 modgen 侧 `PackModMeta` 同值（跨模块无共享常量）。
 */
internal const val PACK_META_MAX_COMPILED_SURFACE: Int = 1_000_000

/** C0 控制字符与空格的分界（版本号里出现任何 < 它的字符即非法）。 */
private const val CONTROL_LIMIT = 0x20

/** DEL（唯一一个不在 C0 区间内、但仍不可打印的 ASCII 控制字符）。 */
private const val DEL_CODE = 0x7F

/** 包内资源命名空间中缀（`<prefix>.assets/<相对路径>`），用于自述包名的形态校验。 */
private const val ASSET_MARKER = ".assets/"

/** 元数据未被采信的原因（诊断/单测用；不上玩家面，见 [PackMeta]）。 */
internal enum class PackMetaIgnore {
    /** zip 里没有这个条目（老包常态）。 */
    MISSING,

    /** 条目在但字节超上限，或不是 UTF-8 文本，或不是合法 JSON 对象。 */
    MALFORMED,

    /** `schema` 字段缺失/不是整数/不是 [PACK_META_SCHEMA]。 */
    SCHEMA,

    /** `version` 字段缺失、类型不对，或形态非法（空 / 超长 / 含控制字符）。 */
    VERSION,

    /**
     * `minApi` 字段出现但不是非负整数：作者声明的引擎下限无法采信 ⇒ 整份元数据按坏档处理
     * （不猜作者想要哪个引擎；version 一并回落，导入照常，闸由装配期按「不设闸」处理）。
     */
    MIN_API,

    /**
     * `resources` 字段出现但不是合法的单段目录名（非字符串 / 空 / 多段 / 含 `..` / 控制字符 /
     * `:` / 超长）：目录名本身就是旁挂资源落点的安全判据（ADR-014 D4），读不准就不猜 ⇒ 整份
     * 元数据按坏档处理，导入照常（本次不 stage 旁挂资源，老包行为逐位不变）。
     */
    RESOURCES,

    /** `packageId` 自述包名与包内可观察的资源前缀对不上 ⇒ 整份元数据不可信。 */
    PACKAGE_ID_PREFIX,
}

/**
 * [PackMeta] 的可选旁证读数：旁挂目录名只出现在整份元数据被采信时，前缀证据只出现在包名与
 * 包内资源前缀冲突时——都不在每个包上出现，收进一个包让「主读数 vs 旁证」的界线显式，也避免
 * 主构造参数数量撞 detekt `LongParameterList` 阈值（编译期 surface 同属旁证，故一并收在这里）。
 */
internal class PackMetaEvidence(
    /** 包内可观察的资源目录前缀（`<prefix>.assets/` 里的 `prefix`，已排序）。 */
    val assetPrefixes: List<String> = emptyList(),
    /** 作者声明的旁挂资源目录名（已过单段目录名闸与 64 字符上限）。 */
    val resources: String? = null,
    /** 作者声明的编译期 mod-api surface（已过 JSON 数字 + 0..上限 闸）。 */
    val compiledSurface: Int? = null,
)

/**
 * 包元数据读取结果。
 *
 * @property packageId 作者自述包名（缺 / 形态非法 ⇒ null）。安装身份（磁盘命名空间）恒为内容
 *   hash [ModImportController.packageIdFor] 派生的 `kojo-<8hex>`；但**代码包的运行期身份**
 *   （dex 里看到的包名、[ModCodeIdentity] 侧车的值）以它为准（缺失时回退内层
 *   `manifest.json` 的 `packageId`，见 [ModImportCodeArtifacts.stage]）。
 * @property declaredVersion 元数据里写的版本原文（缺 / 不可信 ⇒ null）
 * @property version 实际采信并写进产物的版本号（不可信 ⇒ [PACK_META_FALLBACK_VERSION]）
 * @property minApi 采信的引擎下限（`pack-mod --min-api` 写的可选键；缺失 / 不可信 ⇒ 0 = 不设闸）。
 *   **只搬运、不判**：写进产物 manifest 的 `/6` `min-api` 段，闸在装配期
 *   （`ModChainAssembly` 判 minApi > `ModApi.VERSION` 整链拒载）。
 * @property ignored 未采信原因；null = 采信
 * @property compiledSurface 作者声明「本包编译于哪个 mod-api surface」（`pack-mod --surface` 写的可选键；
 *   缺失 / 坏值 / 整份元数据未采信 ⇒ null = 未声明）。**只解析、不消费**：真闸在
 *   [ModDexGate.verdict]（任何 mod 代码执行之前判）。坏值口径刻意与 `minApi`/`resources` 不同——
 *   只忽略本字段、不连坐整份元数据：它是后加的可选键，老 app 不认它，作者工具把数写歪一个
 *   不该连累 version/min-api 的读数（ADR-013 D2）。
 */
internal class PackMeta(
    val packageId: String?,
    val declaredVersion: String?,
    val version: String,
    val ignored: PackMetaIgnore?,
    private val evidence: PackMetaEvidence? = null,
    val minApi: Int = 0,
) {
    /**
     * 包内可观察的资源目录前缀（`<prefix>.assets/` 里的 `prefix`，已排序）；
     * **只在 [PackMetaIgnore.PACKAGE_ID_PREFIX] 命中时非空**——玩家 WARN 要点名「资源目录实际是什么」
     * （见 [packageIdPrefixWarning]）；其余情况为空表，不为告警之外的路径多扫一遍 zip。
     */
    val assetPrefixes: List<String> get() = evidence?.assetPrefixes ?: emptyList()

    /**
     * 作者声明的旁挂资源目录名（`eratw-pack.json` 的可选 `resources` 键，ADR-014 D2）。
     * 缺失 / 形态非法 / 整份元数据未采信 ⇒ null。名字已过单段目录名闸与 64 字符上限；本端只解析
     * **不消费**（资源 stage 是后续切片的接线面），null 等价于「不带旁挂资源」——老包逐位不变。
     */
    val resources: String? get() = evidence?.resources

    /**
     * 作者声明的编译期 mod-api surface（`eratw-pack.json` 的可选 `compiledSurface` 键，ADR-013 D2）。
     * 缺失 / 坏值 / 整份元数据未采信 ⇒ null = 未声明（老包常态）。**只解析、不判**：真闸在
     * [ModDexGate.verdict]，且必须在任何 mod 代码执行之前跑完。
     */
    val compiledSurface: Int? get() = evidence?.compiledSurface

    /** 版本号是否来自元数据（false = 回落值，读数与改造前一致）。 */
    val trusted: Boolean get() = ignored == null

    /**
     * 导入成功页的「引擎 API 下限」告警（DC）：作者声明的 min-api 高于当前引擎 API 时上屏一行。
     *
     * 判据 = [minApi] > [engineApi]（缺省 [ModApi.VERSION] = 本机内核承诺的作者面版本，也是
     * 装配期 `ModChainAssembly` 整链拒载的同一条判据）。句子必须让玩家自己判断三件事：
     * 这个包要求的最低引擎 API 版本、当前引擎 API 版本、装上后装配期会被拒载。
     *
     * - `minApi == 0`（老包 / 作者不设闸）或 `minApi <= 当前引擎` ⇒ null，**一条都不出**
     *   （不给每个正常包加一行噪声）；
     * - 元数据未采信（缺失 / 坏档 / schema 不认识 / 版本或 min-api 形态非法 / 包名自相矛盾）⇒
     *   [minApi] 已归 0 ⇒ 同样 null，不重复报（既有 `packageIdPrefixWarning` 之外的作者侧
     *   问题保持静默）。
     *
     * **只做读数、不改导入成败**：本包照常落盘并写进产物 manifest 的 `/6` `min-api` 段，
     * 真正的闸仍在装配期（见类 KDoc）；本函数不给成功页判「可用」之外加任何状态。
     */
    fun engineApiGateWarning(
        copy: ModImportCopy,
        engineApi: Int = ModApi.VERSION,
    ): String? =
        if (trusted && minApi > engineApi) {
            copy.fill(copy.engineApiGateWarningFormat, minApi, engineApi)
        } else {
            null
        }
}

/**
 * 从原始 zip 字节里读包元数据：`archiveZips` 里**第一个**带 [PACK_META_ENTRY_NAME] 条目的包胜。
 *
 * 自述包名的一致性口径（为什么不是「与内容 hash 比」）：安装身份是内容 hash 派生的 `kojo-…`，
 * 与作者声明的包名（`eratw.xxx` 之类）命名空间结构性不同、永不相等——拿两者比只会 100% 误报。
 * 真正可观察的自证来源是包内资源目录前缀（`<prefix>.assets/`）：自述包名不在其中 ⇒ 元数据
 * 与这个包的资源面自相矛盾（例如误塞了别包的元数据），此时**不采信版本**（回落），但**不改写
 * 身份、不拦导入**。包内没有资源条目时无从自证，按采信处理（元数据本身合法即可）。
 * 前缀冲突时 `minApi` 一并归 0、`resources` 一并归 null（整份元数据已判不可信，不拿它声明的
 * 引擎下限或旁挂目录去接线）。
 */
internal fun packMetaOf(archiveZips: List<ByteArray>): PackMeta {
    val entry = archiveZips.firstNotNullOfOrNull { zipBytes ->
        ModZip.fixedEntryBytes(zipBytes, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES)
    }
    val parsed = parsePackMeta(entry)
    // 只有「已采信 + 元数据自述了包名」才需要与包内资源前缀对账；其余情况原样返回。
    val declared = parsed.packageId.takeIf { parsed.trusted }
    val prefixes = assetPrefixesOf(archiveZips)
    val prefixConflict = declared != null && prefixes.isNotEmpty() && declared !in prefixes
    return if (prefixConflict) {
        PackMeta(
            parsed.packageId,
            parsed.declaredVersion,
            PACK_META_FALLBACK_VERSION,
            PackMetaIgnore.PACKAGE_ID_PREFIX,
            evidence = PackMetaEvidence(assetPrefixes = prefixes.sorted()),
        )
    } else {
        parsed
    }
}

/**
 * 未采信的元数据原因 → 玩家可见 WARN（null = 静默）。
 *
 * 只有 [PackMetaIgnore.PACKAGE_ID_PREFIX] 上屏：自述包名与包内资源目录前缀对不上是**玩家可观测的
 * 包内事实**（包自述的包名和它自己的资源目录不是同一个），玩家能自己判断并去问作者；其余原因
 * （缺失 / 坏档 / schema 不认识 / 版本形态非法）是作者侧打包问题、玩家无可行动，保持内部诊断。
 *
 * 文案口径 = 三件事实都写进句子：声明了什么包名、资源目录实际是什么、本次怎么处置（仍按内容
 * 安装、版本读数回落到 [version]）。内部枚举名不上玩家面。
 */
internal fun PackMeta.packageIdPrefixWarning(copy: ModImportCopy): String? {
    val declared = packageId
    if (ignored != PackMetaIgnore.PACKAGE_ID_PREFIX || declared == null) return null
    val dirs = assetPrefixes.joinToString(copy.nameListSeparator) { prefix -> prefix + ASSET_MARKER }
    return copy.fill(copy.packMetaPackageIdMismatchFormat, declared, dirs, version)
}

/**
 * 解析元数据条目字节（纯函数，单测直接点名；[entry] = null 表示条目缺失或超上限）。
 *
 * 只认自己写出的字段（`schema` / `packageId` / `version` / 可选 `minApi` / 可选 `resources` /
 * 可选 `compiledSurface`），多余
 * 字段忽略——第三方工具加注解不该让导入端拒收；认不出的 `schema` 才整体回落（未来格式不许被老端
 * 猜着读）。
 */
internal fun parsePackMeta(entry: ByteArray?): PackMeta {
    val obj = entry
        ?.let { bytes -> runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() }
        ?.let { text -> MarketJson.parseOrNull(text) as? JsonObject }
    return when {
        entry == null -> untrusted(PackMetaIgnore.MISSING)
        obj == null -> untrusted(PackMetaIgnore.MALFORMED)
        (obj.opt("schema") as? JsonNumber)?.intValueOrNull() != PACK_META_SCHEMA ->
            untrusted(PackMetaIgnore.SCHEMA)
        else -> metaOfObject(obj)
    }
}

/**
 * 已通过 `schema` 闸的 JSON 对象 → [PackMeta]（字段缺失/形态非法各自按对应口径回落）。
 *
 * `minApi` 缺席 = 0（不设闸，老包常态）；**出现就必须是非负整数**，否则整份元数据按
 * [PackMetaIgnore.MIN_API] 不采信——作者声明的引擎下限读不准时不猜，宁可当没声明。
 *
 * `resources` 缺席 = null（老包常态）；**出现就必须是合法单段目录名**，否则整份元数据按
 * [PackMetaIgnore.RESOURCES] 不采信（目录名即落点安全判据：单段判据与导入资源面同源
 * [ModImportAssets.isLegalSingleSegmentName]，另加 64 字符上限与整名不含 `..`）。
 *
 * `compiledSurface` 缺席 / 坏值（非数字、负数、超 [PACK_META_MAX_COMPILED_SURFACE]）⇒
 * [PackMeta.compiledSurface] = null，**只忽略该字段**、其余字段照常采信（口径与 minApi/resources
 * 相反，理由见 [PackMeta.compiledSurface] 的 KDoc）。
 */
private fun metaOfObject(obj: JsonObject): PackMeta {
    val packageId = (obj.opt("packageId") as? JsonString)?.value?.takeIf { PACK_ID_RE.matches(it) }
    val declared = (obj.opt("version") as? JsonString)?.value?.takeIf { isPackVersion(it) }
    val resourcesNode = obj.opt(PACK_META_RESOURCES_KEY)
    // 旁挂目录名形态闸（ADR-014 D2/D4）：单段判据与导入资源面同源（禁止各自复制字符规则），
    // 另加两条落点相关约束——长度 ≤ PACK_META_MAX_RESOURCES_NAME_LENGTH、整名不含 `..`（名字会被
    // 拼进 `resources/mod/<dir>/` 与 `sound/mod/<dir>/` 落点，正常目录名没理由带它，读不准就不猜）。
    val resources = (resourcesNode as? JsonString)?.value?.takeIf { value ->
        value.length <= PACK_META_MAX_RESOURCES_NAME_LENGTH &&
            !value.contains("..") &&
            ModImportAssets.isLegalSingleSegmentName(value)
    }
    val minApiNode = obj.opt(PACK_META_MIN_API_KEY)
    val minApi = if (minApiNode == null) {
        0
    } else {
        (minApiNode as? JsonNumber)?.intValueOrNull()?.takeIf { it >= 0 }
    }
    // 编译期 surface（ADR-013 D2 闸 2）：坏值只丢本字段、不连坐整份元数据——本键是后加的可选键，
    // 老 app 不认它；作者工具把数写歪一个不该连累 version/min-api 的读数。真判据在 ModDexGate。
    val compiledSurfaceNode = obj.opt(PACK_META_COMPILED_SURFACE_KEY)
    val compiledSurface = (compiledSurfaceNode as? JsonNumber)?.intValueOrNull()
        ?.takeIf { it in 0..PACK_META_MAX_COMPILED_SURFACE }
    return when {
        declared == null -> PackMeta(packageId, null, PACK_META_FALLBACK_VERSION, PackMetaIgnore.VERSION)
        resourcesNode != null && resources == null ->
            PackMeta(packageId, declared, PACK_META_FALLBACK_VERSION, PackMetaIgnore.RESOURCES)
        minApi == null -> PackMeta(packageId, declared, PACK_META_FALLBACK_VERSION, PackMetaIgnore.MIN_API)
        else -> PackMeta(
            packageId,
            declared,
            declared,
            null,
            evidence = PackMetaEvidence(resources = resources, compiledSurface = compiledSurface),
            minApi = minApi,
        )
    }
}

/** 回落结果构造（版本恒为 [PACK_META_FALLBACK_VERSION]）。 */
private fun untrusted(reason: PackMetaIgnore): PackMeta =
    PackMeta(null, null, PACK_META_FALLBACK_VERSION, reason)

/**
 * 版本号形态闸：非空、≤ [PACK_META_MAX_VERSION_LENGTH]、无控制字符（含 DEL）。
 *
 * 不校验 semver 之类形状——作者写 `"beta-3"` 合法；但空串/超长/控制字符只可能来自坏档，
 * 若采信会在成功页读出乱码甚至换行注入，故一律回落。
 */
private fun isPackVersion(value: String): Boolean =
    value.isNotEmpty() && value.length <= PACK_META_MAX_VERSION_LENGTH &&
        value.all { char -> char.code >= CONTROL_LIMIT && char.code != DEL_CODE }

/** 包内可观察的资源命名空间前缀集（单段、合法资源名形态之外一律忽略）。 */
private fun assetPrefixesOf(archiveZips: List<ByteArray>): Set<String> =
    archiveZips.flatMap { zipBytes -> ModZip.entryNames(zipBytes) }
        .filter { name -> name.contains(ASSET_MARKER) }
        .map { name -> name.substringBefore(ASSET_MARKER) }
        .filter { prefix -> prefix.isNotEmpty() && !prefix.contains('/') }
        .toSet()

