package com.eratw.port.android.market

/**
 * 口上市场/Mod市场（MKM 系列）共享骨架 —— 波次契约 CONTRACT-MKM-W1 §0.5 由组长入册。
 *
 * 形态：**无服务器索引 + ZIP 容器**。注册表 = GitHub 仓 kojo-market 的 `index.json`
 * （gh-pages 权威 + Worker 镜像回退）；容器 = ZIP；权威校验锚 = `pack.json` 逐文件
 * sha256 + 索引条目的整包 zip sha256 双锚。语义真源 = kojo-editor
 * `packages/plugin-api/src/market.ts`（契约层）与
 * `packages/plugins/kojo-market/src/service.ts`（安装流水线），本文件是其 Kotlin 侧
 * 的共享面：只放**数据类型 + 契约常量 + 两条接口缝**，零业务逻辑。
 *
 * 模块纪律：本包（`com.eratw.port.android.market`）的域/管线文件禁 Android API，
 * 定向单测必须纯 JVM 可跑（app 单测源集为 JVM 口径）。
 */

/** 索引条目（`index.json` 的 `packs[]` 元素，schema v1）。字段与 market.ts `MarketIndexEntry` 同名对齐。 */
data class MarketIndexEntry(
    val kind: String,
    val id: String,
    val version: String,
    val name: String,
    val author: String,
    val license: String,
    /** 注册表 CI 同款硬闸：false 一律拒分发；索引侧不该出现 false，出现即进 dropped。 */
    val redistribute: Boolean,
    val minAppVersion: String,
    val summary: String?,
    val sourceUrl: String?,
    /** 由消费者解释，市场契约零游戏名词；本 App 只作展示徽章。 */
    val gameId: String?,
    val zipUrl: String,
    val zipSha256: String,
    val zipBytes: Long,
    val fileCount: Int,
    val unpackedBytes: Long,
    val coverUrl: String?,
)

/** 解析后的完整索引。`dropped` = 因 id-kind 冲突等被判弃的条数；`coverIgnored` = 封面闸不过被忽略条数。 */
data class MarketIndex(
    val schema: Int,
    val generatedAt: String?,
    val packs: List<MarketIndexEntry>,
    val dropped: Int,
    val coverIgnored: Int,
    /** 本机取数时刻（epoch 毫秒），由取数侧盖章，非索引原文。 */
    val fetchedAtMs: Long,
)

/** 索引三态：缺席（从未成功取过）/ 非法（回源成功但校验拒，盘上旧列表原样保留）/ 正常。 */
sealed interface MarketIndexState {
    data object Absent : MarketIndexState
    data class Invalid(val problem: String) : MarketIndexState
    data class Ok(val index: MarketIndex) : MarketIndexState
}

/**
 * 一次「从注册表刷新索引」的回执三态（CONTRACT-MKM-W6 §0.1 冻结形状；语义锚 = kojo-editor
 * market.ts `MarketIndexRefresh` 的 refreshed/cached/failed 三分支——失败也是一等态，不许
 * 「加载中」常驻，也不许把没拉过装作拉过）。调用方要本机现列表一律走 [MarketService.indexState]，
 * 回执只回答「这次刷新发生了什么」。
 */
sealed interface MarketRefreshOutcome {
    /** 本次真回源且校验过（refreshed）。 */
    data class Fresh(val index: MarketIndex) : MarketRefreshOutcome

    /** TTL 内/非 force 直接回缓存（cached；不是失败，也不该装作拉过）。 */
    data class Cached(val index: MarketIndex) : MarketRefreshOutcome

    /** 回源失败，盘上旧列表原样保留（indexState() 仍 Ok 旧值）。 */
    data class Stale(val problem: String) : MarketRefreshOutcome
}

/** 安装容器内 `pack.json` 的逐文件锚（path 为 posix 相对、恒以 `files/` 开头）。 */
data class PackManifestFile(val path: String, val bytes: Long, val sha256: String)

/** 安装期解压后读回的 `pack.json` 全量形状（市场契约的字段全集，契约外键在解析期拒）。 */
data class PackManifest(
    val schema: Int,
    val kind: String,
    val id: String,
    val version: String,
    val name: String,
    val author: String,
    val license: String,
    val redistribute: Boolean,
    val minAppVersion: String,
    val summary: String?,
    val sourceUrl: String?,
    val gameId: String?,
    val files: List<PackManifestFile>,
    /**
     * 可选的**显式收窄声明**（R7-① 次要能力；`overrideFunctions` 字段）。
     *
     * 不是覆盖名单的来源——名单真源恒为「模块函数名 ∩ base 函数名（事件名除外）」在导入期
     * 自动推导，存量包不写本字段即按推导集装载。写了 = 作者钉住覆盖面：声明必须与推导集
     * 逐一相等，多列（声明了覆盖不了的目标）或漏列（补丁含同名函数却没声明）都在导入期
     * 硬拒；`emptyList()` = 显式声明「本包不覆盖任何函数」。用途是防后续改源时意外多覆盖。
     */
    val overrideFunctions: List<String>? = null,
)

/** 已装包记账条目。路径不落账（由 [MarketContract] 派生函数重建），丢失时按在位自愈剔除。 */
data class InstalledPack(
    val kind: String,
    val id: String,
    val version: String,
    val name: String,
    val author: String,
    val license: String,
    val installedAtMs: Long,
    /** 容器（zip）整包 sha256；目录源安装没有容器事实 = null，不拿 contentDigest 顶替。 */
    val containerSha256: String?,
    /** 逐文件内容摘要（path+sha256 规范串再哈希）。 */
    val contentDigest: String?,
    val fileCount: Int,
    val bytes: Long,
)

/** 安装结果三态。`Rejected` 的 `code` 取值封闭在 [MarketRejectCodes]，`detail` 供日志/取证。 */
sealed interface InstallOutcome {
    data class Ok(val pack: InstalledPack) : InstallOutcome
    data class Rejected(val code: String, val detail: String) : InstallOutcome
    data class Failed(val problem: String) : InstallOutcome
}

/** 卸载结果。 */
sealed interface UninstallOutcome {
    data object Ok : UninstallOutcome
    data object NotFound : UninstallOutcome
    data class Failed(val problem: String) : UninstallOutcome
}

/**
 * 显式清扫库内被超越版本的回执读数（CONTRACT-MKM-W5-GC §0.2 余项：手动入口的读数面）。
 * 放进骨架文件的裁定：裸 Int 只带得了计数、带不了失败原因，手动入口要把「清掉 N 个 /
 * 没得清 / 失败」折成 UI 状态，必须是一个封闭判别联——形状随 [UninstallOutcome] 的 outcome
 * 密封族口径（失败也是一等态，不静默）。
 */
sealed interface SweepOutcome {
    /** 清扫跑完：removed = 删除的版本目录数（0 = 库内没有可清的旧版，幂等 no-op 同形）。 */
    data class Swept(val removed: Int) : SweepOutcome

    /**
     * 清扫没跑成：problem = 未预期异常原话（不静默）。逐目录删除失败**不在本态**——store 侧
     * 失败语义是「单目录删除失败只少计不报、不重试，下次触发自然重试」（[MarketStore.sweepSuperseded]
     * KDoc），服务缝如实透传已删计数；本态只兜「清扫根本没跑成」的未预期异常面，让手动入口
     * 的互斥闸必有回执可清（回执不落屏 = 按钮永久禁用）。
     */
    data class Failed(val problem: String) : SweepOutcome
}

/** 更新检测四态（照 market.ts `packUpdateCheck` :1025-1063）。未安装 = 接口返回 null，不是 Failed。 */
sealed interface UpdateCheckState {
    data class Updatable(val indexVersion: String) : UpdateCheckState

    /**
     * 同版本已装。sameContainer = 本机 `containerSha256` 与索引条目 zip 锚的对平结果
     * （market.ts `PackUpdateCheck.sameContainer` :887-896）：相同 true、不同 false、
     * 本机无容器事实（目录源装的）null——**不拿 contentDigest 顶替**（内容锚折叠摘要与
     * 容器整包摘要不是一个东西，比出来的"相等"没有意义）。
     */
    data class Latest(val sameContainer: Boolean?) : UpdateCheckState

    /** 本机版本比索引还新（目录源装的高版本等）；localVersion = 本机同 (kind,id) 最高版本那条。 */
    data class Ahead(val localVersion: String) : UpdateCheckState
    data class Failed(val problem: String) : UpdateCheckState
}

/** 安装三阶段（CONTRACT-MKM-W6 §0.3 冻结；阶段回调由 ServiceImpl 编排层围绕三段外发，安装器内部零改动）。 */
enum class MarketInstallPhase { DOWNLOAD, EXTRACT, COMMIT }

/** 端点编辑四态（照 market-source.ts `planEndpointEdit`：keep/reset/set/invalid）。 */
sealed interface EndpointEditResult {
    data object Kept : EndpointEditResult
    data object Reset : EndpointEditResult
    data class Set(val url: String) : EndpointEditResult
    data class Invalid(val problem: String) : EndpointEditResult
}

/**
 * 拒因机器码封闭集。UI 层把每个 code 映射成三语人话（穷尽映射，未知 code 给兜底文案），
 * 码面改动 = 契约变更，须经组长接线；`detail` 一律不进玩家文案。
 */
object MarketRejectCodes {
    const val SCHEMA_VERSION = "schema-version"
    const val KIND_INVALID = "kind-invalid"
    const val ID_INVALID = "id-invalid"
    const val VERSION_INVALID = "version-invalid"
    const val REDISTRIBUTE_FALSE = "redistribute-false"
    const val PATH_TRAVERSAL = "path-traversal"
    const val PATH_INVALID = "path-invalid"
    const val TOO_MANY_ENTRIES = "too-many-entries"
    const val UNPACKED_TOO_BIG = "unpacked-too-big"
    const val ZIP_TOO_BIG = "zip-too-big"
    const val SHA_MISMATCH = "sha-mismatch"
    const val MANIFEST_MISMATCH = "manifest-mismatch"
    const val ID_KIND_CONFLICT = "id-kind-conflict"
    const val APP_VERSION_UNSUPPORTED = "app-version-unsupported"
    const val ENDPOINT_FORBIDDEN = "endpoint-forbidden"
    const val DOWNLOAD_FAILED = "download-failed"
    const val EXTRACT_FAILED = "extract-failed"
    const val STAGING_FAILED = "staging-failed"
    /** 封闭集全表，供 UI 穷尽映射与测试断言。 */
    val ALL: List<String> = listOf(
        SCHEMA_VERSION, KIND_INVALID, ID_INVALID, VERSION_INVALID, REDISTRIBUTE_FALSE,
        PATH_TRAVERSAL, PATH_INVALID, TOO_MANY_ENTRIES, UNPACKED_TOO_BIG, ZIP_TOO_BIG,
        SHA_MISMATCH, MANIFEST_MISMATCH, ID_KIND_CONFLICT, APP_VERSION_UNSUPPORTED,
        ENDPOINT_FORBIDDEN, DOWNLOAD_FAILED, EXTRACT_FAILED, STAGING_FAILED,
    )
}

/** 解析结果密封族（解析成功但内容校验拒 = Invalid，不是异常）。 */
sealed interface MarketParseResult<T> {
    data class Ok<T>(val value: T) : MarketParseResult<T>
    data class Invalid<T>(val problem: String) : MarketParseResult<T>
}

/**
 * 市场契约常量与派生名（**唯一真源**，别处不许拼第二次 —— 照 market.ts 派生函数纪律）。
 * 数值口径全部对齐 kojo-editor market.ts 与 kojo-market-worker 的校验判据。
 */
object MarketContract {
    /** 当前（也是唯一在用的）清单/索引 schema 版本。 */
    const val SCHEMA_VERSION = 1

    /** 权威索引端点（gh-pages）。 */
    const val DEFAULT_INDEX_URL = "https://poboking.github.io/kojo-market/index.json"

    /** 镜像回退端点（Worker R2 分发面，miss 时自回源 gh-pages）。 */
    const val MIRROR_INDEX_URL = "https://market.362573581.xyz/index.json"

    /** GitHub Releases API（下载量统计取数面，CONTRACT-MKM-STATS-UI §1 D1；zip.url 的宿主仓同源）。 */
    const val RELEASES_API_URL = "https://api.github.com/repos/Poboking/kojo-market/releases?per_page=100"

    // 取数候选序现由 MarketServiceImpl.endpointOrderWithHealth 按健康度现算
    // （CONTRACT-MKM-W7-HEALTH：覆盖端点恒第一 → lastSuccess 降序 → 无记录默认序垫后），
    // 旧固定序 indexEndpointOrder 已无调用点，随 W7 删除（组长裁定）。

    const val KIND_KOJO = "kojo-pack"
    const val KIND_DICT = "dict-pack"
    const val KIND_TEMPLATE = "template-pack"
    /**
     * mod 包（ERAFIN-C3）：modgen 作者轨的**编译产物态**包——已装包的 `files/` 树带
     * `<pkg>.manifest` + `<pkg>.erbm` 两件套，装进游戏走成品包导入合同
     * （[com.eratw.port.android.ModFinC3PackagedImport]，跳过编译链直接落 `mods/rt2`）。
     * 与 `template-pack`（角色模板数据）语义不撞：template 是 Chara CSV 数据面，mod-pack
     * 是可运行的零基线模块容器。上游注册表（kojo-market 仓）README 现仍限三 kind
     * （RECON 必答 6，只读结论）：本 kind 在 app 侧放行后，官方索引在仓侧放开前不会出现
     * mod-pack 条目；自建端点（GitHub 系）不受影响。
     */
    const val KIND_MOD = "mod-pack"
    val KINDS: List<String> = listOf(KIND_KOJO, KIND_DICT, KIND_TEMPLATE, KIND_MOD)

    /** 市场在应用数据区的根目录名：`<filesDir>/market`。 */
    const val DIR_NAME = "market"

    /** 索引镜像文件名（`<filesDir>/market/index.json`）。 */
    const val INDEX_FILE = "index.json"

    /** 下载量统计缓存文件名（`<filesDir>/market/stats.json`，CONTRACT-MKM-STATS-UI §1 D1）。 */
    const val STATS_FILE = "stats.json"

    /** 统计缓存 schema（独立于索引/清单 schema；形状变更才递增）。 */
    const val STATS_SCHEMA = 1

    /** 本机收藏文件名（`<filesDir>/market/favorites.json`，CONTRACT-MKM-STATS-UI §1 D2）。 */
    const val FAVORITES_FILE = "favorites.json"

    /** 收藏文件 schema。 */
    const val FAVORITES_SCHEMA = 1

    /**
     * 收藏键派生：`kind/id`（收藏按包不按版本，CONTRACT-MKM-STATS-UI §1 D2）。键形唯一真源
     * ——收藏存储落盘、ViewModel 过滤与 UI 星标态判定全部走这一处，不许拼第二次。
     */
    fun favoriteKey(kind: String, id: String): String = "$kind/$id"

    /** 已装包目录段名：`<filesDir>/market/packs/<kind>/<id>/<version>`。 */
    const val PACKS_DIR = "packs"

    /** 暂存目录段名（装完/失败都必须清空；崩溃残壳由下次安装清扫）。 */
    const val STAGING_DIR = ".staging"

    /** zip 根 / 已装目录根部唯一的元数据文件名。 */
    const val MANIFEST_FILE = "pack.json"

    /** 包内容一律住在这个子目录下（清单里的 path 恒以它开头）。 */
    const val CONTENT_DIR = "files"

    const val ZIP_SUFFIX = ".zip"
    const val UNPACKED_SUFFIX = ".unpacked"
    const val STALE_SUFFIX = ".__previous__"

    /** 索引 TTL：6 小时。 */
    const val INDEX_TTL_MS: Long = 6 * 60 * 60 * 1000L

    /** 索引取数超时（毫秒）。 */
    const val INDEX_TIMEOUT_MS: Long = 15_000L

    /** 索引体积上限（512 KiB）。 */
    const val INDEX_MAX_BYTES: Long = 512 * 1024L

    /** zip 本体上限：64 MiB（注册表 CI 同款闸）。 */
    const val MAX_ZIP_BYTES: Long = 64L * 1024 * 1024

    /** 解压后总量上限：128 MiB。 */
    const val MAX_UNPACKED_BYTES: Long = 128L * 1024 * 1024

    /** 单包文件条目上限。 */
    const val MAX_FILE_ENTRIES = 5000

    /** 包内路径深度上限（段数）。 */
    const val MAX_PATH_DEPTH = 12

    /** 下载重定向跳数上限。 */
    const val MAX_REDIRECTS = 5

    /** 一 MiB 字节数（超时公式等处用）。 */
    const val BYTES_PER_MIB: Long = 1024L * 1024L

    /** `<id>-<version>.zip`：暂存区容器名（下载与解压共用同一名字主体）。 */
    fun packContainerName(id: String, version: String): String = "$id-$version$ZIP_SUFFIX"

    /** `<id>-<version>.unpacked`：容器解出来的目录名（只当中间态，装完即删）。 */
    fun packUnpackedName(id: String, version: String): String = "$id-$version$UNPACKED_SUFFIX"

    /** `<id>-<version>`：搬运用的暂存目录名。 */
    fun packStagingName(id: String, version: String): String = "$id-$version"

    /** `<id>-<version>.__previous__`：同版本重装时被挪开的旧副本。 */
    fun packStaleName(id: String, version: String): String = "$id-$version$STALE_SUFFIX"

    /** 一个包在暂存区里可能留下的全部名字（卸载/失败清扫都从这张表来）。 */
    fun packStagingNames(id: String, version: String): List<String> = listOf(
        packContainerName(id, version),
        packUnpackedName(id, version),
        packStagingName(id, version),
        packStaleName(id, version),
    )

    /** 下载超时公式基础段：60s。 */
    const val DOWNLOAD_TIMEOUT_BASE_MS: Long = 60_000L

    /** 下载超时公式每 MiB 追加段：1s（约 0.5 MB/s 预算，照 market.ts:147-150）。 */
    const val DOWNLOAD_TIMEOUT_PER_MIB_MS: Long = 1_000L

    /** 下载超时公式：60s 基础 + 每 MiB 1s。 */
    fun downloadTimeoutMs(zipBytes: Long): Long {
        val extraSeconds = zipBytes / BYTES_PER_MIB
        return DOWNLOAD_TIMEOUT_BASE_MS + extraSeconds * DOWNLOAD_TIMEOUT_PER_MIB_MS
    }
}

/**
 * 纯校验/解析缝（零 IO、零 Android API）。实现 = mkm-domain 路交付的
 * `MarketVerifierImpl`；管线与 UI 一律只依赖本接口，测试注入假实现。
 */
interface MarketVerifier {
    /**
     * 解析并校验 `index.json` 全文。校验判据：schema 恒为 1；条目字段全检（id/版本/sha256
     * 形态、redistribute 必须 true、zip.url 形态、封面闸）；id-kind 冲突条目判弃计进 dropped
     * 出声（不整索引拒）。失败返回 [MarketParseResult.Invalid]（人话 problem）。
     */
    fun parseIndex(json: String, fetchedAtMs: Long): MarketParseResult<MarketIndex>

    /** 解析并校验 `pack.json` 全文（字段全检 + 逐文件锚形状 + 契约外键拒）。 */
    fun parsePackManifest(json: String): MarketParseResult<PackManifest>

    /** 严格三段数字版本比较（段各 ≤9 位数字）：负 = a<b，0 = 相等，正 = a>b。入参必须已过 parse 校验。 */
    fun versionCompare(a: String, b: String): Int

    /**
     * minAppVersion 满足闸：minAppVersion 必须严格三段；当前应用版本剥预发布段后三段
     * 比较，不小于即满足。判据单源 = MarketFields.minAppVersionSatisfiedCore（GATE2D 洞③），
     * 参数序钉死 **(minAppVersion, appVersion)**——UI 壳版 MarketVersion.minAppVersionSatisfied
     * 对外参数序 (currentAppVersion, minAppVersion) 相反，两壳的委托与一切接驳点必须具名
     * 实参（判词 20 的「互接反」死口）。生产调用点 = MarketInstaller.commitOrThrow 安装硬闸
     * （本接口版的零调用状态自 GATE2D 起终结）+ 管线测试注入。
     */
    fun minAppVersionSatisfied(minAppVersion: String, appVersion: String): Boolean

    /**
     * 逐文件内容摘要：条目按 path 排序，`${path}${sha256}`（两段以 NUL 分隔）用单元分隔符
     * 连接后 SHA-256，64 位小写 hex（照 kojo-editor digest.ts 口径）。
     */
    fun contentDigest(entries: List<Pair<String, String>>): String

    /** 容器/清单 sha256 形态闸（64 位小写 hex）。 */
    fun isSha256Hex(value: String): Boolean
}

/**
 * 市场服务门面。实现 = mkm-pipe 路交付的 `MarketServiceImpl`；UI 只依赖本接口。
 * 线程口径：所有方法**阻塞调用线程**（调用方自己起后台线程），实现内部对装卸互斥单飞。
 */
interface MarketService {
    /** 最近一次索引三态（可能 [MarketIndexState.Absent]）。 */
    fun indexState(): MarketIndexState

    /**
     * 刷新索引。TTL 内且非 force 直接回 [MarketRefreshOutcome.Cached]；回源按健康度序
     * （实现 = `MarketServiceImpl.endpointOrderWithHealth`：覆盖端点恒第一、lastSuccess
     * 降序、无记录默认序垫后）逐端点回退；**校验通过才覆盖盘上镜像**，
     * 回源失败回 [MarketRefreshOutcome.Stale]、盘上旧列表原样保留（坏响应不覆盖盘上文件，
     * [indexState] 三态语义不变）。本机现列表一律读 [indexState]，回执只报这次刷新发生了什么。
     */
    fun refreshIndex(force: Boolean): MarketRefreshOutcome

    /** 已装包全表（在位自愈后：丢失条目剔除并写回）。 */
    fun installed(): List<InstalledPack>

    /** 已装总字节数（同一条在位核验）。 */
    fun installedBytes(): Long

    /**
     * 更新检测：本机取同 (kind,id) 最高版本比索引条目。未安装返回 null（「没装」是另一个动作）。
     */
    fun updateCheck(entry: MarketIndexEntry): UpdateCheckState?

    /**
     * 安装一个索引条目（容器源全链）：下载 → 容器 sha256 对账 → 限额解压 → manifest 读回
     * 双向对账 → 暂存搬运 + 逐文件读回复核 → rename 原子落点（同版本重装旧副本挪
     * `.__previous__`，失败还原）→ 记账。阻塞调用线程。进度回调按 [MarketInstallPhase]
     * 三段外发（阶段由 ServiceImpl 编排层发，安装器内部零改动）：DOWNLOAD = 容器下载字节
     * 口径照旧；EXTRACT = 下载流收口点补一声（总量/起点 0，不编假进度）；COMMIT = 安装链
     * 成功收口处发累计搬运读数（清单累计字节，总量 0）。拒绝/失败路径不发 COMMIT。
     */
    fun install(
        entry: MarketIndexEntry,
        onProgress: (phase: MarketInstallPhase, doneBytes: Long, totalBytes: Long) -> Unit,
    ): InstallOutcome

    /** 卸载：删目录 + 清账 + 收空壳父目录 + 按 [MarketContract.packStagingNames] 清该包全部暂存残壳。 */
    fun uninstall(kind: String, id: String, version: String): UninstallOutcome

    /** 当前生效主端点（覆盖值或默认值）。 */
    fun endpoint(): String

    /** 端点编辑唯一写入口（形态闸；白名单在取数时核）。空串 = 恢复默认。 */
    fun setEndpoint(url: String): EndpointEditResult
}
