package com.eratw.port.android

import com.eratw.port.core.mod.ModHandlerBudget
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModSettingsSnapshot
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.mod.footprint
import com.eratw.port.core.mod.RegisteredFaces
import com.eratw.port.core.mod.registeredFacesOf
import com.eratw.port.modapi.TimingSample

/** 纳秒 → 毫秒的换算基数（账本与阈值内部都是纳秒；屏显口径 = 毫秒）。 */
private const val NANOS_PER_MILLI: Long = 1_000_000L

/** 每个 mod 最多展示的耗时作用域条数（最慢的 top 3）。 */
private const val TOP_SCOPE_LIMIT: Int = 3

/** 1 KB 的字节数（屏显口径用二进制 KB/MB，与 Android 设置页/文件管理器一致）。 */
private const val BYTES_PER_KIB: Long = 1024L

/** 1 MB 的字节数。 */
private const val BYTES_PER_MIB: Long = BYTES_PER_KIB * 1024L

/** 一个「整单位」含多少个十分位（小数一位显示用整数算，避免 Locale 相关格式化）。 */
private const val TENTHS_PER_UNIT: Long = 10L

/**
 * 纳秒 → 毫秒，四舍五入。
 *
 * 账本与预算阈值都是纳秒（`TimingSample.totalNanos` / `ModHandlerBudget.thresholdNanos`），
 * 设置页只显示毫秒：截断会把「0.6ms」显示成「0」，四舍五入与玩家的 `16 ms` 阈值直觉一致。
 * 负值（时钟异常）按 0 处理，绝不让屏上出现负耗时。
 */
internal fun nanosToMillisRounded(nanos: Long): Long =
    if (nanos <= 0L) 0L else (nanos + NANOS_PER_MILLI / 2L) / NANOS_PER_MILLI

/**
 * 一个包的存储段读数：**序列化字节数 + 段内条目数**（含墓碑位）+ **两条阈值越限位**。
 *
 * 字节数/键数/越限位全部取自 core 公开的只读足迹 [com.eratw.port.core.mod.ModStorageStore.footprint]
 * ——判据单源在 core（阈值常量与「严格大于」口径都只有那一份），app 侧不重算、不改判。
 * 该足迹只编码单段，不触全仓 `ModStorageStore.encode()`（O(全量)，只在存档路径用）。
 *
 * @property bytes 单段编码后字节数（与真实落盘字节同源）
 * @property keys 段内条目数（含墓碑位）
 * @property overKeysLimit 键数超 core 软阈值（core 的判定，不是展示阈值）
 * @property overBytesLimit 编码字节数超 core 软阈值
 */
internal data class ModStorageReadout(
    val bytes: Long,
    val keys: Int,
    val overKeysLimit: Boolean = false,
    val overBytesLimit: Boolean = false,
) {

    /** 任一口径越限即成「超限行」；渲染与单测都读这同一处，免得两条 over 位各判一套。 */
    val overLimit: Boolean get() = overKeysLimit || overBytesLimit
}

/**
 * 字节数 → 屏显文本：`512 B` / `1.5 KB` / `2.0 MB`。
 *
 * B/KB/MB 是三种语言共用的技术单位记号（不翻译、不进文案表）；小数一位用整数十分位算，
 * 不依赖 `String.format` 的 Locale，单测可逐字点名。
 */
internal fun formatStorageBytes(bytes: Long): String = when {
    bytes < BYTES_PER_KIB -> "$bytes B"
    bytes < BYTES_PER_MIB -> formatTenths(bytes, BYTES_PER_KIB, "KB")
    else -> formatTenths(bytes, BYTES_PER_MIB, "MB")
}

/** 十分位四舍五入后拼成 `x.y UNIT`（[bytes] 非负由调用方的 when 保证）。 */
private fun formatTenths(bytes: Long, unit: Long, suffix: String): String {
    val tenths = (bytes * TENTHS_PER_UNIT + unit / 2L) / unit
    return "${tenths / TENTHS_PER_UNIT}.${tenths % TENTHS_PER_UNIT} $suffix"
}

/** 一行「作用域读数」：稳定作用域名 + 调用次数 + 累计毫秒。 */
internal data class ModRuntimeScopeRow(
    val scope: String,
    val count: Long,
    val totalMillis: Long,
)

/**
 * 一个已装 mod 的运行时读数行。
 *
 * @property active 本次会话是否装载成功且处于启用态（真源 = 装配期 `report` 的 active 集）
 * @property failure 装载失败原因；null = 没失败（屏上显示「无」而不是留白）
 * @property totalCount 全部作用域累计调用次数（timing 账本聚合）
 * @property totalMillis 全部作用域累计毫秒（timing 账本聚合，四舍五入）
 * @property topScopes 最慢的至多三条作用域（按累计耗时降序）
 * @property slowCount 越阈值的慢处理器次数（0 = 不显示慢栏）
 * @property slowThresholdMillis 慢处理器判定阈值（毫秒）
 * @property faces 注册面读数（hook/事件/交互/服务/文案/设置/图片各几条；全零也照渲染）
 * @property storage 该包存储段读数（字节 + 键数）；null = 会话里根本没有这个包的存储段（屏显
 *   「存储 —」，不是「0 B」——没段与空段是两件事）
 * @property settingsChanged 相对声明默认已改的设置项数；null = 会话快照里没有该包设置面
 *   （屏显「设置已改 —」），0 = 有声明且一项都没改
 */
internal data class ModRuntimeRow(
    val packageId: String,
    val active: Boolean,
    val failure: String?,
    val totalCount: Long,
    val totalMillis: Long,
    val topScopes: List<ModRuntimeScopeRow>,
    val slowCount: Long,
    val slowThresholdMillis: Long,
    val faces: RegisteredFaces = RegisteredFaces.EMPTY,
    val storage: ModStorageReadout? = null,
    val settingsChanged: Int? = null,
)

/** 设置页「Mod 运行时概览」的不可变读数快照（纯数据，可单测）。 */
internal data class ModRuntimeOverview(
    val rows: List<ModRuntimeRow>,
)

/**
 * 失败栏文案：有原因给「装载失败：<原因>」，没有给「无」——空栏也要有字，不能让玩家把
 * 「没显示」误读成「没失败」；无失败时不挂「装载失败：」前缀，免得读成「失败了：无」。
 */
internal fun failureLine(text: SettingsStrings, row: ModRuntimeRow): String {
    val reason = row.failure ?: return text.modRuntimeOverviewNone
    return text.modRuntimeOverviewFailure(reason)
}

/** 慢栏是否出现：>0 才显示（0 = 不挂「无」噪音）；判定与渲染分离，便于单测钉口径。 */
internal fun showsSlowLine(row: ModRuntimeRow): Boolean = row.slowCount > 0L

/**
 * 存储超限标记是否出现：以 [ModStorageReadout.overLimit]（= core 足迹的两条 over 位）为唯一判据。
 *
 * 缺段（storage = null）不标 —— 「没有段」与「段偏大」是两件事，前者屏上照旧显式「存储 —」。
 * 判定与渲染分离（只读派生属性，无副作用），卡片只读这一个布尔。
 */
internal val ModRuntimeRow.showsStorageWarning: Boolean
    get() = storage?.overLimit == true

/**
 * 会话侧只读快照：装配期 active 集 + 逐包 timing 读数 + 逐包慢计数 + 阈值。
 *
 * 只由 `ModRuntimeSession` 在设置页取数时拍一次（同一线程内现读账本），不落盘、不改状态。
 */
internal data class ModRuntimeData(
    val activeIds: Set<String>,
    val timingByPackage: Map<String, List<TimingSample>>,
    val slowCountByPackage: Map<String, Long>,
    val thresholdNanos: Long,
    /** 逐包注册面读数（现读活注册表的小快照；缺包 = 调用方回落到全零 [RegisteredFaces.EMPTY]）。 */
    val facesByPackage: Map<String, RegisteredFaces> = emptyMap(),
    /** 逐包存储段读数；缺包 = 该包没有存储段（卡片显式「—」）。 */
    val storageByPackage: Map<String, ModStorageReadout?> = emptyMap(),
    /** 逐包「相对声明默认已改几项」；缺包 = 会话快照没有该包设置面（卡片显式「—」）。 */
    val settingsChangedByPackage: Map<String, Int?> = emptyMap(),
)

/**
 * 把「盘上已装清单 + 装载失败表 + 会话运行时快照」拼成屏上读数。
 *
 * 纯函数（不读 Android / 不碰账本 / 不写任何东西），单测直接喂值。
 *
 * @param packageIds 已装 packageId（顺序 = 设置页列表顺序，**含装载失败的包**：它不在
 *   会话 active 集里，但玩家装过、失败原因要能看到）
 * @param failures `ModManageDiagnostics.snapshot()`（装载失败原因，key = packageId）
 * @param data 会话侧快照；**null = 账本未装配（关态 / 手工装配）⇒ 整块隐藏**
 * @return null ⇒ 调用方整块不渲染
 */
internal fun buildModRuntimeOverview(
    packageIds: List<String>,
    failures: Map<String, String>,
    data: ModRuntimeData?,
): ModRuntimeOverview? {
    if (data == null) return null
    val rows = packageIds.map { packageId ->
        val samples = data.timingByPackage[packageId].orEmpty()
        ModRuntimeRow(
            packageId = packageId,
            active = packageId in data.activeIds,
            failure = failures[packageId],
            totalCount = samples.sumOf { it.count },
            totalMillis = nanosToMillisRounded(samples.sumOf { it.totalNanos }),
            topScopes = topScopesOf(samples),
            slowCount = data.slowCountByPackage[packageId] ?: 0L,
            slowThresholdMillis = nanosToMillisRounded(data.thresholdNanos),
            faces = data.facesByPackage[packageId] ?: RegisteredFaces.EMPTY,
            storage = data.storageByPackage[packageId],
            settingsChanged = data.settingsChangedByPackage[packageId],
        )
    }
    return ModRuntimeOverview(rows)
}

/** 取累计耗时最慢的至多 [TOP_SCOPE_LIMIT] 个作用域（降序；不改入参顺序）。 */
private fun topScopesOf(samples: List<TimingSample>): List<ModRuntimeScopeRow> =
    samples
        .sortedByDescending { it.totalNanos }
        .take(TOP_SCOPE_LIMIT)
        .map { sample ->
            ModRuntimeScopeRow(
                scope = sample.scope,
                count = sample.count,
                totalMillis = nanosToMillisRounded(sample.totalNanos),
            )
        }

/**
 * 会话侧读数适配：从活宿主里现读账本 / 预算 / 注册面，给 [buildModRuntimeOverview] 用。
 *
 * **账本为 null ⇒ 返回 null**（关态总闸或手工装配的宿主没有账本），这是「整块隐藏」的唯一判据；
 * 预算缺失（老装配路径）只把慢计数降级成 0 + 缺省 [ModHandlerBudget.DEFAULT_THRESHOLD_NANOS]，
 * 不影响其余读数的可见性 —— 读数永不抛、永不拿不到就把整块吞掉。注册面读数由宿主侧
 * `registeredFacesOf` 降级兜底（查不到 = 全零），同样不影响其余读数。
 *
 * @param activeIds 装配期 active 集（`ModRuntimeSession` 的 report 快照，调用方传同一份）
 * @param packageIds 设置页当前列表的 packageId（只为按包查账本 / 注册面，缺失包 = 空读数）
 */
internal fun runtimeOverviewDataOf(
    host: ModRuntimeHost,
    activeIds: Set<String>,
    packageIds: List<String>,
): ModRuntimeData? {
    val ledger = host.timing ?: return null
    val budget = host.handlerBudget
    return ModRuntimeData(
        activeIds = activeIds.intersect(packageIds.toSet()),
        timingByPackage = packageIds.associateWith { ledger.forPackage(it) },
        slowCountByPackage = packageIds.associateWith { budget?.slowCount(it) ?: 0L },
        thresholdNanos = budget?.thresholdNanos ?: ModHandlerBudget.DEFAULT_THRESHOLD_NANOS,
        facesByPackage = packageIds.associateWith { host.registeredFacesOf(it) },
        storageByPackage = storageReadoutsOf(host.storage, packageIds),
        settingsChangedByPackage = settingsChangedCountsOf(host.settings, packageIds),
    )
}

/**
 * 逐包存储读数：缺段 ⇒ null（与「空段 0 键」区分开），有段 ⇒ core 足迹原样转出。
 *
 * 缺段判定仍用一次 [ModStorageStore.sectionsSnapshot]（足迹对缺段会返回全 0/不越限，
 * 与「有段但空」同形，不能拿它判「有没有段」）；字节/键/两条越限位一律读 core
 * [com.eratw.port.core.mod.ModStorageStore.footprint]，不在 app 侧重算阈值。
 * 可见性放宽到 internal 只为让同模块单测能拿真实 store 钉「越限位是否照 core 读出来」。
 */
internal fun storageReadoutsOf(
    storage: ModStorageStore,
    packageIds: List<String>,
): Map<String, ModStorageReadout?> {
    val presentPackages = storage.sectionsSnapshot().mapTo(HashSet()) { it.packageId }
    return packageIds.associateWith { packageId ->
        if (packageId !in presentPackages) return@associateWith null
        val footprint = storage.footprint(packageId)
        ModStorageReadout(
            bytes = footprint.encodedBytes.toLong(),
            keys = footprint.keys,
            overKeysLimit = footprint.overKeysLimit,
            overBytesLimit = footprint.overBytesLimit,
        )
    }
}

/**
 * 逐包「设置项已改几项」：声明键里 `resolveText` 与声明默认不一致的个数。
 *
 * 只读会话快照（[ModRuntimeHost.settings]，attach/页边界由装配侧赋值），不读 SharedPreferences、
 * 不访问磁盘；无效/越界持久值经 `resolveText` 已回落默认 ⇒ 不计入「已改」（屏上读数 = 生效值
 * 语义，与玩家看到的一致）。缺包 ⇒ null（显式「—」）。
 */
private fun settingsChangedCountsOf(
    settings: Map<String, ModSettingsSnapshot>,
    packageIds: List<String>,
): Map<String, Int?> = packageIds.associateWith { packageId ->
    settings[packageId]?.let { snapshot ->
        snapshot.declaredKeys.count { key ->
            snapshot.resolveText(key) != snapshot.declarationOf(key).defaultText()
        }
    }
}

/**
 * 设置页的 Mod 运行时读数口（进程级活引用，形态照 `ModUiSettingsEntry`）。
 *
 * ## 为什么需要这条通道
 * 活 `GameHost` 只挂在 `MainActivity` 的实例字段上，设置页是另一个 Activity；
 * `GameHost.modRuntimeSession` 是私有字段，`ModRuntimeSession` 只活在游戏壳里。本件供壳
 * 在宿主就绪处装一个「按 packageId 现读账本」的 provider，设置页渲染时现取现用；不装
 * 或会话已收口时 read 为 null ⇒ 卡片整块不渲染（与「账本 null 隐藏」同一条降级口径）。
 *
 * ## 线程模型
 * 装/摘在 UI 线程（MainActivity 生命周期两端），读在 UI 线程（设置页组合）⇒ `@Volatile`。
 * 认主（[wiredOwner]）口径与 `ModUiSettingsEntry` / `HotConfigChannel` 同源：双壳交接窗口
 * 里旧壳的 onDestroy 不得撅空新壳刚装好的接线。
 */
internal object ModRuntimeOverviewEntry {

    /** 当前接线：packageId 清单 → 会话侧读数；null = 未接线（或已卸载）。 */
    @Volatile
    private var liveProvider: ((List<String>) -> ModRuntimeData?)? = null

    /** 当前接线的宿主身份（与 [liveProvider] 同生同灭）。 */
    @Volatile
    private var wiredOwner: Any? = null

    /** 壳接线：装上 provider 并以 [owner] 为身份；后装者覆盖旧接线（幂等）。 */
    fun install(owner: Any, provider: ((List<String>) -> ModRuntimeData?)?) {
        wiredOwner = owner
        liveProvider = provider
    }

    /** 认主卸载：只有归属正是 [owner] 才摘（旧壳无权撅新壳的接线）。 */
    fun uninstall(owner: Any) {
        if (wiredOwner === owner) {
            wiredOwner = null
            liveProvider = null
        }
    }

    /** 现取现用：未接线 / 会话已收口 / 账本未装配 ⇒ null（调用方整块隐藏）。 */
    fun read(packageIds: List<String>): ModRuntimeData? = liveProvider?.invoke(packageIds)

    /** 当前接线身份；null = 未接线（装/摘是否成对的判据）。 */
    internal fun wiredOwnerSnapshot(): Any? = wiredOwner
}
