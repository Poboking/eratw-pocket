package com.eratw.port.android.market

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.reflect.KProperty1

/**
 * 市场覆盖层的**纯视图逻辑**（CONTRACT-MKM-W1 §2 mkm-ui 判据 2）：零 Android API、零
 * Compose import，纯 JVM 可跑定向单测——UI 不写域逻辑，校验/版本判断只走骨架接口与
 * 本文件的纯函数。
 *
 * 语义权威 = kojo-editor `packages/plugins/ui-market/src/`（只读参考）：
 * - `market-view.ts`：分组/行动作/拒因表/更新话术/读数（逐函数带行号锚）；
 * - `actions.ts`：快照装配与乐观更新（:73-93/:130-154）；
 * - `market-panel.ts`：两段式刷新与单键互斥的**壳层口径**（:207-223/:244-307，转移实现在
 *   [MarketViewModel.reduce] 的事件机里，由宿主 SettingsActivity 驱动）；
 * - `market-source.ts`：端点行四态投影（:30-37/:161-182）。
 *
 * 分层与 [com.eratw.port.android.ModImportUiReducer] 同款：本文件只放状态形状 + 纯转移 +
 * 话术函数；Compose 层（MarketUi.kt）只渲染，后台线程与 runOnUiThread 接线在宿主。
 */
object MarketViewModel {

    /**
     * 手动清扫占用的互斥闸键（[MarketUiState.installingKey] 的哨兵值）：不是行键——行键恒为
     * kind/id@version 形，撞不上「sweep」这个字面，行级渲染与进度查找自然不受影响。
     */
    internal const val SWEEP_GATE_KEY = "sweep"

    // ---------------------------------------------------------------- 行键与显式态

    /** 已装行的稳定键 `kind/id@version`（market-view.ts installedKey :583-585）。 */
    fun installedKey(kind: String, id: String, version: String): String = "$kind/$id@$version"

    /** 索引行的稳定键（market-view.ts indexRowKey :593-595；与「哪一行正在装」共用同形）。 */
    fun indexRowKey(entry: MarketIndexEntry): String = installedKey(entry.kind, entry.id, entry.version)

    /** 索引显式态四值（market-view.ts resolveStatus :267-276：只吃服务的三态判别联）。 */
    enum class MarketViewStatus { LIST_MISSING, LIST_BROKEN, NO_PACKS, READY }

    fun resolveStatus(index: MarketIndexState): MarketViewStatus = when (index) {
        MarketIndexState.Absent -> MarketViewStatus.LIST_MISSING
        is MarketIndexState.Invalid -> MarketViewStatus.LIST_BROKEN
        is MarketIndexState.Ok -> if (index.index.packs.isEmpty()) MarketViewStatus.NO_PACKS else MarketViewStatus.READY
    }

    // ---------------------------------------------------------------- 行动作

    /** 索引行上的动作（market-view.ts PackRowAction :398；`none` = 不放按钮）。 */
    enum class MarketRowAction { INSTALL, UPDATE, NONE }

    /**
     * 这一行该不该给按钮（market-view.ts rowAction :471-476 逐行移植）：
     * 版本要求不满足 ⇒ 不给；本机没装 ⇒ install；可更新 ⇒ update；
     * **Failed 态也不给**——比较判据本身读不出时，「安装」等于拿一份没人核对过的版本关系去覆盖本机。
     */
    fun rowAction(appOk: Boolean, update: UpdateCheckState?): MarketRowAction = when {
        !appOk -> MarketRowAction.NONE
        update == null -> MarketRowAction.INSTALL
        update is UpdateCheckState.Updatable -> MarketRowAction.UPDATE
        else -> MarketRowAction.NONE
    }

    /** 索引分组里的一行（market-view.ts PackRow :385-395 同形）。 */
    data class MarketRow(
        val entry: MarketIndexEntry,
        /** 本机版本是否够装（够 = true；market-view.ts :615）。 */
        val appOk: Boolean,
        /** 同 (kind,id,version) 是否已装（= latest 态；market-view.ts :620 的等价形态）。 */
        val installed: Boolean,
        /** KM-10 三态；null = 本机没装过这个包（「没装」不是一个态，是另一个动作）。 */
        val update: UpdateCheckState?,
        val action: MarketRowAction,
    )

    /** 按种类分组的一组（market-view.ts PackGroup :478-482；空组不出现，:626）。 */
    data class MarketGroup(val kind: String, val label: String, val rows: List<MarketRow>)

    /**
     * 索引 → 按种类分组（market-view.ts buildGroups :604-629）：种类顺序 = 契约的
     * [MarketContract.KINDS]，组内按包名**码元序**（market-view.ts byCodePoint :33 的纪律：
     * 不用 localeCompare，中文顺序不随宿主 ICU 漂；Kotlin 的 String 比较同为码元序）再按 id。
     * [updates] 是后台段按服务 [MarketService.updateCheck] 逐条算好的三态表（键 = [indexRowKey]）：
     * 版本比较不在这里重算第二遍，那是第二份口径。
     * [favorites] / [favoritesOnly]（CONTRACT-MKM-STATS-UI §2）：收藏过滤档在档时各组内只留
     * 已收藏条目（键 = [MarketContract.favoriteKey]；组无内容不出现，与空组口径一致）——
     * 收藏 chip 与 kind chips 可叠加，两者是独立维度。
     */
    fun buildGroups(
        copy: MarketCopy,
        packs: List<MarketIndexEntry>,
        appVersion: String,
        updates: Map<String, UpdateCheckState?>,
        filterKind: String?,
        favorites: Set<String> = emptySet(),
        favoritesOnly: Boolean = false,
    ): List<MarketGroup> {
        val kinds = if (filterKind == null) MarketContract.KINDS else listOf(filterKind)
        val out = mutableListOf<MarketGroup>()
        for (kind in kinds) {
            val rows = packs.asSequence()
                .filter { it.kind == kind }
                .filter { if (favoritesOnly) MarketContract.favoriteKey(it.kind, it.id) in favorites else true }
                .map { entry ->
                    val appOk = MarketVersion.minAppVersionSatisfied(appVersion, entry.minAppVersion)
                    val update = updates[indexRowKey(entry)]
                    MarketRow(
                        entry = entry,
                        appOk = appOk,
                        installed = update is UpdateCheckState.Latest,
                        update = update,
                        action = rowAction(appOk, update),
                    )
                }
                .sortedWith(compareBy({ it.entry.name }, { it.entry.id }))
                .toList()
            if (rows.isNotEmpty()) {
                out.add(MarketGroup(kind = kind, label = MarketOptimistic.kindLabel(copy, kind), rows = rows))
            }
        }
        return out
    }

    // ---------------------------------------------------------------- 状态机（照 ModImportUiReducer 的转移形态）

    /** 下载进度读数（totalBytes 不大于 0 = 总数未知，不编假百分比）。 */
    data class MarketProgress(val doneBytes: Long, val totalBytes: Long)

    /**
     * 装包进度阶段。CONTRACT-MKM-W6 缝 3 落地后信号源 = 服务 [MarketService.install] 的
     * [MarketInstallPhase] 阶段回调（编排层围绕三段外发），[MarketUiEvent.InstallProgress]
     * 随事件带阶段进状态机；W1 的「下载尾推断 EXTRACT」停顿口径已由显式阶段替代。
     */
    enum class InstallPhase { DOWNLOAD, EXTRACT, COMMIT }

    /** 快照（actions.ts MarketSnapshot :46-71 的最小面：UI 要的全部事实都在服务读数里）。 */
    data class MarketSnapshot(
        val index: MarketIndexState,
        val packs: List<MarketIndexEntry>,
        /** 逐行更新三态（键 = [indexRowKey]；值 null = 本机没装）。 */
        val updates: Map<String, UpdateCheckState?>,
        val installed: List<InstalledPack>,
        val installedBytes: Long,
        val endpoint: String,
    )

    /** 已装区的一行（market-view.ts InstalledRow :631-644 的 Kotlin 面）。 */
    data class MarketInstalledRow(
        val rec: InstalledPack,
        val key: String,
        /** 逐文件锚摘要前 8 位（只进展开层）；目录源没有 = null。 */
        val digestPrefix: String?,
        val kindLabel: String,
        /** 来源的人话：有容器摘要 = 压缩包，没有 = 本机文件夹（market-view.ts :660）。 */
        val sourceLabel: String,
    )

    /**
     * 覆盖层的全部界面状态（宿主用一个 `mutableStateOf` 持有，转移只走 [reduce]）。
     * [snapshot] = null 表示本地第一段还没回来（market-panel.ts:207-214 的 busy 段）。
     */
    data class MarketUiState(
        val appVersion: String,
        val snapshot: MarketSnapshot? = null,
        val groups: List<MarketGroup> = emptyList(),
        /** kind 过滤 chips 的现档；null = 「全部」。gameId 只作徽章展示、不参与过滤（契约 §0 裁定 7）。 */
        val filterKind: String? = null,
        val installedRows: List<MarketInstalledRow> = emptyList(),
        /** 内联状态行那一句（空串 = 不占行；一切正常不该有废话，market-panel.ts:220-222）。 */
        val note: String = "",
        /** 状态行是否失败色（拒因 / 失败回执 = true）。 */
        val noteWarn: Boolean = false,
        /** 本地读/回源进行中（回源按钮禁用）。 */
        val busy: Boolean = false,
        /**
         * 正在装/更新/卸的那一行键（**单键互斥**：装与卸共用一把闸，market-panel.ts:244-307）。
         * 手动清扫（CONTRACT-MKM-W5-GC 余项 D2）占同一把闸：哨兵 [MarketViewModel.SWEEP_GATE_KEY]
         * 不是行键（行键恒为 kind/id@version 形，撞不上这个字面），全部 mutation 按钮（含宿主
         * 入口的第二道挡）随既有 `installingKey == null` 判定一并禁用——服务端清扫本来就与
         * 装卸共用同一把 mutationLock，闸面与锁面单源。
         */
        val installingKey: String? = null,
        val progress: MarketProgress? = null,
        val phase: InstallPhase = InstallPhase.DOWNLOAD,
        /** 卸载确认目标（null = 不弹框；同一时刻只有一条处于待确认态，market-view.ts pendingKey :666-668）。 */
        val pendingUninstall: MarketInstalledRow? = null,
        /** 端点输入框草稿（玩家输入原样回填，归一化在保存时做）。 */
        val endpointDraft: String = "",
        /**
         * 下载量统计缓存态（CONTRACT-MKM-STATS-UI §1 D1）：null = 独立异步段还没回来；
         * Absent = 回来了但没有可用数据（取数失败且无旧缓存）；Ok = 有映射（查询走
         * [MarketStatsUi.downloadsFor]，未命中 zipUrl 折「—」不编 0）。
         */
        val stats: MarketStatsState? = null,
        /** 本机收藏集（键 = [MarketContract.favoriteKey]；落盘真源在宿主，这里只持显示面）。 */
        val favorites: Set<String> = emptySet(),
        /** 收藏过滤档（与 kind chips 可叠加的独立维度；true = 各组只留已收藏条目）。 */
        val favoritesOnly: Boolean = false,
    )

    fun initial(appVersion: String): MarketUiState = MarketUiState(appVersion = appVersion)

    /** 状态机事件（宿主的后台线程 / Compose 回调都发到这里；转移唯一入口 = [reduce]）。 */
    sealed interface MarketUiEvent {
        /** 覆盖层打开 / 点「拉取列表」：busy 占用（market-panel.ts:209/:228）。 */
        data object ReloadStarted : MarketUiEvent

        /** 本地第一段读出（零网络，秒上屏）：busy 归还，端点草稿回填现值。 */
        data class LocalLoaded(val snapshot: MarketSnapshot) : MarketUiEvent

        /** 回源段完成：快照 + 回执一起落屏（note 空串 = cached 一切正常，不废话）。 */
        data class PullDone(val snapshot: MarketSnapshot, val note: String) : MarketUiEvent

        /** 装卸失败后的主动重读（零网络；note 原样保留，market-panel.ts:272-275/:300-303）。 */
        data class InstalledRevalidated(val snapshot: MarketSnapshot) : MarketUiEvent

        /** 过滤 chips 换档（null = 全部）。 */
        data class FilterChanged(val kind: String?) : MarketUiEvent

        /** 装/更新开始：占用互斥闸 + 状态行出声（market-panel.ts:249-250）。 */
        data class InstallStarted(val key: String, val note: String) : MarketUiEvent

        /** 安装进度（CONTRACT-MKM-W6 缝 3：阶段随事件进状态机；节流在服务侧，壳层不重复闸）。 */
        data class InstallProgress(
            val phase: MarketInstallPhase,
            val doneBytes: Long,
            val totalBytes: Long,
        ) : MarketUiEvent

        /**
         * 装完（无论成败）：[installedRec] = 记账回来的那条（Ok 时非空；文案的版本一律取它，
         * 不取索引那一行，actions.ts:271-273），失败 = null 并由宿主主动重读。
         */
        data class InstallFinished(val key: String, val note: String, val installedRec: InstalledPack?) : MarketUiEvent

        /** 卸载确认框开/关（null = 关；确认门前一个字节都不动，actions.ts:242-244）。 */
        data class UninstallConfirm(val row: MarketInstalledRow?) : MarketUiEvent

        /** 卸载开始（占用同一把互斥闸，market-panel.ts:284-285）。 */
        data class UninstallStarted(val key: String, val note: String) : MarketUiEvent

        /**
         * 按包卸载完成（CONTRACT-MKM-W4-UI3 裁定 3：已装行按包去重后卸载一律按包）：宿主对该
         * `(kind,id)` 的**全部已装版本**逐个卸完后一次性落屏，乐观摘除按包整体走
         * （[MarketOptimistic.afterPackageUninstalled]）。[removed] = 该包去重行上的记账条
         * （全部版本都卸成才非空；任一失败 = null 并由宿主主动重读）。
         */
        data class PackageUninstallFinished(
            val key: String,
            val note: String,
            val removed: InstalledPack?,
        ) : MarketUiEvent

        /**
         * 手动清扫开始（已装区「清理旧版本」，CONTRACT-MKM-W5-GC 余项 D2）：占用同一把互斥闸
         * （[MarketViewModel.SWEEP_GATE_KEY]）；服务端与 install/uninstall 同一把 mutationLock。
         */
        data object SweepStarted : MarketUiEvent

        /**
         * 手动清扫回执（[SweepOutcome] 判别联落屏）：Swept(0) = 没有要清的旧版（实话不是失败）、
         * Swept(N) = 清掉 N 个、Failed = 失败原话上屏（失败色，不静默）。回执先落不等待——
         * 宿主随后零网络重读一次（actions.ts :272-275 的主动重读同款），让占用读数收敛到
         * 清扫后的盘面。
         */
        data class SweepFinished(val outcome: SweepOutcome) : MarketUiEvent

        /** 端点草稿变化（玩家输入原样回填）。 */
        data class EndpointDraftChanged(val draft: String) : MarketUiEvent

        /** 端点保存回执（market-source.ts applyEndpointEdit :161-182 的一半，形态闸在服务）。 */
        data class EndpointSaved(val endpoint: String, val note: String) : MarketUiEvent

        /**
         * 下载量统计落屏（CONTRACT-MKM-STATS-UI §1 D1 的独立异步段）：宿主在覆盖层打开时
         * 后台跑 [MarketStatsLoader.load]，成与败都以缓存态落屏——UI 按有无数据折「—」，
         * 失败不进状态行（统计是锦上添花，不值得挤掉安装回执那句话）。
         */
        data class StatsLoaded(val state: MarketStatsState) : MarketUiEvent

        /** 本机收藏集落屏（覆盖层打开时宿主读一次盘真源）。 */
        data class FavoritesLoaded(val favorites: Set<String>) : MarketUiEvent

        /** 收藏 toggle 落屏（宿主已落盘；事件带 toggle 后的新全集，转移只换显示面）。 */
        data class FavoriteToggled(val favorites: Set<String>) : MarketUiEvent

        /** 收藏过滤档切换（分组随过滤重建，不回源——同 FilterChanged 口径）。 */
        data class FavoritesOnlyChanged(val only: Boolean) : MarketUiEvent
    }

    /** 唯一转移函数（纯函数；UI 层不做任何判定）。分派只按事件类型走，落屏细节在文件尾的私有扩展。 */
    fun reduce(state: MarketUiState, event: MarketUiEvent, copy: MarketCopy): MarketUiState = when (event) {
        is MarketUiEvent.ReloadStarted -> state.copy(busy = true)
        is MarketUiEvent.LocalLoaded -> state.onLocalLoaded(event.snapshot, copy)
        is MarketUiEvent.PullDone -> state.onPullDone(event.snapshot, event.note, copy)
        is MarketUiEvent.InstalledRevalidated -> state.withSnapshot(event.snapshot, copy)
        is MarketUiEvent.FilterChanged -> state.onFilterChanged(event.kind, copy)
        is MarketUiEvent.InstallStarted -> state.onInstallStarted(event.key, event.note)
        is MarketUiEvent.InstallProgress -> state.onInstallProgress(event, copy)
        is MarketUiEvent.InstallFinished -> state.afterRecordChanged(
            rec = event.installedRec,
            optimistic = MarketOptimistic::afterInstalled,
            note = event.note,
            warn = event.installedRec == null,
            copy = copy,
        )
        is MarketUiEvent.UninstallConfirm -> state.copy(pendingUninstall = event.row)
        is MarketUiEvent.UninstallStarted -> state.onUninstallStarted(event.key, event.note)
        is MarketUiEvent.PackageUninstallFinished -> state.afterRecordChanged(
            rec = event.removed,
            optimistic = MarketOptimistic::afterPackageUninstalled,
            note = event.note,
            warn = event.removed == null,
            copy = copy,
        ).copy(pendingUninstall = null)
        is MarketUiEvent.SweepStarted -> state.copy(
            installingKey = SWEEP_GATE_KEY,
            note = copy.sweepBusy,
            noteWarn = false,
        )
        is MarketUiEvent.SweepFinished -> state.copy(
            installingKey = null,
            note = MarketPhrases.sweepDonePhrase(copy, event.outcome),
            noteWarn = event.outcome is SweepOutcome.Failed,
        )
        is MarketUiEvent.EndpointDraftChanged -> state.copy(endpointDraft = event.draft)
        is MarketUiEvent.EndpointSaved -> state.onEndpointSaved(event.endpoint, event.note)
        is MarketUiEvent.StatsLoaded -> state.copy(stats = event.state)
        is MarketUiEvent.FavoritesLoaded -> state.onFavoritesChanged(event.favorites, copy)
        is MarketUiEvent.FavoriteToggled -> state.onFavoritesChanged(event.favorites, copy)
        is MarketUiEvent.FavoritesOnlyChanged -> state.onFavoritesOnlyChanged(event.only, copy)
    }
}

// ---------------------------------------------------------------------------
// 状态机转移的分派细节（文件级私有扩展；reduce 本体只做事件分派，复杂度不在这里堆）
// ---------------------------------------------------------------------------

/** 快照整包替换（本地段 / 回源段 / 权威重读共用）：分组与已装行随快照重建。 */
private fun MarketViewModel.MarketUiState.withSnapshot(
    snap: MarketViewModel.MarketSnapshot,
    copy: MarketCopy,
): MarketViewModel.MarketUiState = this.copy(
    snapshot = snap,
    groups = MarketViewModel.buildGroups(
        copy = copy,
        packs = snap.packs,
        appVersion = appVersion,
        updates = snap.updates,
        filterKind = filterKind,
        favorites = favorites,
        favoritesOnly = favoritesOnly,
    ),
    installedRows = MarketOptimistic.buildInstalledRows(copy, snap.installed),
)

/** 本地段落屏：busy 归还、端点草稿回填现值（market-panel.ts:207-214 的本地段语义）。 */
private fun MarketViewModel.MarketUiState.onLocalLoaded(
    snap: MarketViewModel.MarketSnapshot,
    copy: MarketCopy,
): MarketViewModel.MarketUiState = withSnapshot(snap, copy).copy(busy = false, endpointDraft = snap.endpoint)

/** 回源段落屏：快照 + 回执一起落（cached 的空回执 = 不废话，market-panel.ts:220-222）。 */
private fun MarketViewModel.MarketUiState.onPullDone(
    snap: MarketViewModel.MarketSnapshot,
    note: String,
    copy: MarketCopy,
): MarketViewModel.MarketUiState = withSnapshot(snap, copy).copy(busy = false, note = note, noteWarn = false)

/** chips 换档：分组随过滤重建（快照原样保留，改档不回源）。 */
private fun MarketViewModel.MarketUiState.onFilterChanged(
    kind: String?,
    copy: MarketCopy,
): MarketViewModel.MarketUiState =
    this.copy(
        filterKind = kind,
        groups = MarketViewModel.buildGroups(
            copy = copy,
            packs = snapshot?.packs ?: emptyList(),
            appVersion = appVersion,
            updates = snapshot?.updates ?: emptyMap(),
            filterKind = kind,
            favorites = favorites,
            favoritesOnly = favoritesOnly,
        ),
    )

/** 装/更新开始：占用互斥闸 + 状态行出声 + 进度清零（market-panel.ts:248-250）。 */
private fun MarketViewModel.MarketUiState.onInstallStarted(key: String, note: String): MarketViewModel.MarketUiState =
    this.copy(
        installingKey = key,
        note = note,
        noteWarn = false,
        progress = null,
        phase = MarketViewModel.InstallPhase.DOWNLOAD,
    )

/** 卸载开始：占用同一把互斥闸（market-panel.ts:284-288）。 */
private fun MarketViewModel.MarketUiState.onUninstallStarted(key: String, note: String): MarketViewModel.MarketUiState =
    this.copy(installingKey = key, note = note, noteWarn = false)

/** 端点保存回执落屏：状态行出声 + 快照的生效地址同步（market-source.ts :174-182）。 */
private fun MarketViewModel.MarketUiState.onEndpointSaved(
    endpoint: String,
    note: String,
): MarketViewModel.MarketUiState =
    this.copy(note = note, noteWarn = false, snapshot = snapshot?.copy(endpoint = endpoint))

/** 收藏集变化落屏：换显示面；收藏过滤在档时分组随新集合重建（过滤行集必须跟真值走）。 */
private fun MarketViewModel.MarketUiState.onFavoritesChanged(
    favorites: Set<String>,
    copy: MarketCopy,
): MarketViewModel.MarketUiState {
    val rebuilt = if (favoritesOnly) {
        MarketViewModel.buildGroups(
            copy = copy,
            packs = snapshot?.packs ?: emptyList(),
            appVersion = appVersion,
            updates = snapshot?.updates ?: emptyMap(),
            filterKind = filterKind,
            favorites = favorites,
            favoritesOnly = true,
        )
    } else {
        groups
    }
    return this.copy(favorites = favorites, groups = rebuilt)
}

/** 收藏过滤档切换：分组随档重建（不回源，同 FilterChanged 口径）。 */
private fun MarketViewModel.MarketUiState.onFavoritesOnlyChanged(
    only: Boolean,
    copy: MarketCopy,
): MarketViewModel.MarketUiState = this.copy(
    favoritesOnly = only,
    groups = MarketViewModel.buildGroups(
        copy = copy,
        packs = snapshot?.packs ?: emptyList(),
        appVersion = appVersion,
        updates = snapshot?.updates ?: emptyMap(),
        filterKind = filterKind,
        favorites = favorites,
        favoritesOnly = only,
    ),
)

/**
 * 安装进度落屏（CONTRACT-MKM-W6 缝 3）：阶段随服务的 [MarketInstallPhase] 回调显式进状态机，
 * 不再从下载字节流推断。DOWNLOAD 段进度句接进同一条状态行（不弹窗，market-panel.ts:254-260）；
 * EXTRACT/COMMIT 段复用既有 progressExtract/progressCommit 句（进度读数照事件原样落，
 * 总量 0 = 不定态进度条，不编假百分比）。
 */
private fun MarketViewModel.MarketUiState.onInstallProgress(
    event: MarketViewModel.MarketUiEvent.InstallProgress,
    copy: MarketCopy,
): MarketViewModel.MarketUiState {
    val installing = groups.flatMap { group -> group.rows }.firstOrNull {
        MarketViewModel.indexRowKey(it.entry) == installingKey
    }
    val phase = when (event.phase) {
        MarketInstallPhase.DOWNLOAD -> MarketViewModel.InstallPhase.DOWNLOAD
        MarketInstallPhase.EXTRACT -> MarketViewModel.InstallPhase.EXTRACT
        MarketInstallPhase.COMMIT -> MarketViewModel.InstallPhase.COMMIT
    }
    val note = when (phase) {
        MarketViewModel.InstallPhase.DOWNLOAD -> installing?.let {
            MarketPhrases.installProgressPhrase(
                copy,
                it.action == MarketViewModel.MarketRowAction.UPDATE,
                it.entry.name,
                event.doneBytes,
                event.totalBytes,
            )
        } ?: note
        MarketViewModel.InstallPhase.EXTRACT -> copy.progressExtract
        MarketViewModel.InstallPhase.COMMIT -> copy.progressCommit
    }
    return this.copy(
        progress = MarketViewModel.MarketProgress(event.doneBytes, event.totalBytes),
        phase = phase,
        note = note,
    )
}

/**
 * 装卸完成后的落屏（乐观只是**显示先行**，market-panel.ts:266-275/:294-303）：Ok 半边按
 * 记账条做乐观更新（[optimistic] = 装/卸/按包卸各事件自己选的乐观方向），失败半边状态行
 * 出声、权威数字交给宿主随后的主动重读修正。快照未装载（本地第一段还没回来）时不做乐观、
 * 只清闸出声。
 */
private fun MarketViewModel.MarketUiState.afterRecordChanged(
    rec: InstalledPack?,
    optimistic: (MarketViewModel.MarketSnapshot, InstalledPack) -> MarketViewModel.MarketSnapshot,
    note: String,
    warn: Boolean,
    copy: MarketCopy,
): MarketViewModel.MarketUiState {
    val base = snapshot
    val next = if (rec == null || base == null) base else optimistic(base, rec)
    val cleared = this.copy(
        note = note,
        noteWarn = warn,
        installingKey = null,
        progress = null,
        phase = MarketViewModel.InstallPhase.DOWNLOAD,
    )
    return if (next == null) cleared else cleared.withSnapshot(next, copy)
}

/**
 * 拒码 → 文案键的穷尽映射（market-view.ts REJECT_TEXT :77-114 的 Kotlin 面）。键集与
 * [MarketRejectCodes.ALL] 的封闭集逐一对账由定向单测钉死（MarketCopyShapeTest 的键数
 * 对账 + MarketViewModelTest 的穷尽用例）；查不到的码走兜底句（rejectPhrase）。
 */
internal val REJECT_PHRASE_KEYS: Map<String, KProperty1<MarketCopy, String>> = mapOf(
    MarketRejectCodes.SCHEMA_VERSION to MarketCopy::rejectSchemaVersion,
    MarketRejectCodes.KIND_INVALID to MarketCopy::rejectKindInvalid,
    MarketRejectCodes.ID_INVALID to MarketCopy::rejectIdInvalid,
    MarketRejectCodes.VERSION_INVALID to MarketCopy::rejectVersionInvalid,
    MarketRejectCodes.REDISTRIBUTE_FALSE to MarketCopy::rejectRedistributeFalse,
    MarketRejectCodes.PATH_TRAVERSAL to MarketCopy::rejectPathTraversal,
    MarketRejectCodes.PATH_INVALID to MarketCopy::rejectPathInvalid,
    MarketRejectCodes.TOO_MANY_ENTRIES to MarketCopy::rejectTooManyEntries,
    MarketRejectCodes.UNPACKED_TOO_BIG to MarketCopy::rejectUnpackedTooBig,
    MarketRejectCodes.ZIP_TOO_BIG to MarketCopy::rejectZipTooBig,
    MarketRejectCodes.SHA_MISMATCH to MarketCopy::rejectShaMismatch,
    MarketRejectCodes.MANIFEST_MISMATCH to MarketCopy::rejectManifestMismatch,
    MarketRejectCodes.ID_KIND_CONFLICT to MarketCopy::rejectIdKindConflict,
    MarketRejectCodes.APP_VERSION_UNSUPPORTED to MarketCopy::rejectAppVersionUnsupported,
    MarketRejectCodes.ENDPOINT_FORBIDDEN to MarketCopy::rejectEndpointForbidden,
    MarketRejectCodes.DOWNLOAD_FAILED to MarketCopy::rejectDownloadFailed,
    MarketRejectCodes.EXTRACT_FAILED to MarketCopy::rejectExtractFailed,
    MarketRejectCodes.STAGING_FAILED to MarketCopy::rejectStagingFailed,
)

/** 话术半边（market-view.ts 的 updatePhrase / REJECT_TEXT / installNote 系逐条移植）。 */
internal object MarketPhrases {

    /**
     * 已装条目旁的状态短语（market-view.ts updatePhrase :451-468）：**只说事实不催动作**，
     * 更新永远要玩家亲手点。四态全覆盖、无 else 分支——骨架加第五态时这里编译期就红。
     * Ahead 带本机版本读数（market-view.ts :458 `u.local.version`，CONTRACT-MKM-W6 缝 2
     * 落地）；Latest 的 sameContainer = false 分句说「重装会覆盖」（market-view.ts :460——
     * 同版本但容器核对码不同，那是玩家下一步会问的事），true/null（无容器事实）归「已安装」。
     */
    fun updatePhrase(copy: MarketCopy, row: MarketViewModel.MarketRow): String {
        val u = row.update ?: return ""
        return when (u) {
            is UpdateCheckState.Updatable -> copy.fill(copy.updateUpdatableFormat, u.indexVersion)
            is UpdateCheckState.Ahead -> copy.fill(copy.updateAheadFormat, u.localVersion)
            is UpdateCheckState.Latest -> when (u.sameContainer) {
                false -> copy.updateLatestDiverged
                else -> copy.updateLatest
            }
            is UpdateCheckState.Failed -> if (u.problem.isNotBlank()) u.problem else copy.updateFailedFallback
        }
    }

    /**
     * 拒码 → 人话（market-view.ts REJECT_TEXT :77-114 的穷尽映射纪律 + 未知码兜底）：
     * [REJECT_PHRASE_KEYS] 的键集与 [MarketRejectCodes.ALL] 18 码一一对应（结构对账由定向
     * 单测钉死），查不到的码把机器码原样带进兜底句（不静默，market-view.ts rejectNote :163
     * 的原话出口）。`detail` 一律不进玩家文案（骨架裁定）。
     */
    fun rejectPhrase(copy: MarketCopy, code: String): String =
        REJECT_PHRASE_KEYS[code]?.get(copy) ?: copy.fill(copy.rejectUnknownFormat, code)

    /** 进行中那一句（market-view.ts installBusyNote :414-416；进度只在这一处显示，不弹窗）。 */
    fun installBusyPhrase(copy: MarketCopy, isUpdate: Boolean, name: String): String =
        copy.fill(if (isUpdate) copy.updateBusyFormat else copy.installBusyFormat, name)

    /**
     * 下载进行中那一句（market-view.ts installProgressNote :423-434）：总数已知 ⇒ 百分比
     * （向下取整、封顶 100——响应头与实际流对不齐时只许少说不许超卖）；缺失 ⇒ 报已收多少，
     * 不编假百分比。
     */
    fun installProgressPhrase(
        copy: MarketCopy,
        isUpdate: Boolean,
        name: String,
        doneBytes: Long,
        totalBytes: Long,
    ): String {
        val busy = installBusyPhrase(copy, isUpdate, name)
        return if (totalBytes > 0) {
            val pct = (doneBytes * PCT_SCALE / totalBytes).toInt().coerceAtMost(PCT_CAP)
            copy.fill(copy.progressPercentFormat, busy, pct)
        } else {
            copy.fill(copy.progressBytesFormat, busy, MarketReadouts.formatBytes(doneBytes))
        }
    }

    /**
     * 装成/更新成那一句（market-view.ts installNote :440-442）：版本取**服务记账回来的那条**
     * 而不是索引那行，面板不自己比版本（那是第二份口径）。
     */
    fun installDonePhrase(copy: MarketCopy, isUpdate: Boolean, name: String, version: String): String =
        copy.fill(if (isUpdate) copy.updateDoneFormat else copy.installDoneFormat, name, version)

    /** 卸载结果那一句（actions.ts runUninstall :237-254 的人话半边；NotFound 是另一句实话）。 */
    fun uninstallPhrase(copy: MarketCopy, name: String, outcome: UninstallOutcome): String = when (outcome) {
        is UninstallOutcome.Ok -> copy.fill(copy.uninstalledFormat, name)
        is UninstallOutcome.NotFound -> copy.uninstallNotFound
        is UninstallOutcome.Failed -> copy.fill(copy.uninstallFailedFormat, outcome.problem)
    }

    /**
     * 手动清扫回执那一句（CONTRACT-MKM-W5-GC 余项 D2，三分支）：Swept(0) = 「没有要清的」
     * 实话（不是失败，不给失败色——由 [MarketViewModel.MarketUiEvent.SweepFinished] 的
     * noteWarn 判定负责）；Swept(N) = 计数；Failed = 失败原话上屏（照 uninstallFailedFormat
     * 的不静默口径，detail 原话只在失败态给）。
     */
    fun sweepDonePhrase(copy: MarketCopy, outcome: SweepOutcome): String = when (outcome) {
        is SweepOutcome.Swept ->
            if (outcome.removed > 0) copy.fill(copy.sweepDoneFormat, outcome.removed) else copy.sweepNothing
        is SweepOutcome.Failed -> copy.fill(copy.sweepFailedFormat, outcome.problem)
    }

    /** 被剔除条目出声（market-view.ts droppedNotice :306-314；骨架只给计数不给名单，一条没丢 ⇒ 空串）。 */
    fun droppedNotice(copy: MarketCopy, dropped: Int): String =
        if (dropped > 0) copy.fill(copy.droppedNoticeFormat, dropped) else ""

    /** 封面不合规 ≠ 包不合规（market-view.ts coverIgnoredNotice :321-328；不混进剔除那句话里）。 */
    fun coverIgnoredNotice(copy: MarketCopy, ignored: Int): String =
        if (ignored > 0) copy.fill(copy.coverIgnoredFormat, ignored) else ""

    /** 数据时间短读数（market-view.ts dataTimeSummary :365-369；缺席/非法时不许拿假话盖住）。 */
    fun syncTimePhrase(copy: MarketCopy, index: MarketIndexState): String = when (index) {
        is MarketIndexState.Ok ->
            if (index.index.fetchedAtMs > 0) {
                copy.fill(copy.syncTimeFormat, MarketReadouts.formatClock(index.index.fetchedAtMs))
            } else {
                copy.noDataTime
            }
        else -> copy.noDataTime
    }

    private const val PCT_SCALE = 100L
    private const val PCT_CAP = 100
}

/** 读数半边（体积 / 时刻 / 计数；market-view.ts formatBytes :373-378、formatClock :331-336 等）。 */
internal object MarketReadouts {

    /** 字节数 → 人读体积（阈值固定，别让面板出现 1048576 这类读数）。单位符号是度量读数，不进文案表。 */
    fun formatBytes(n: Long): String = when {
        n < 0 -> DASH
        n < BYTE_STEP -> "$n B"
        n < BYTE_STEP * BYTE_STEP -> "${oneDecimal(n.toDouble() / BYTE_STEP)} KB"
        else -> "${oneDecimal(n.toDouble() / (BYTE_STEP * BYTE_STEP))} MB"
    }

    /** epoch ms → 本地 `MM-dd HH:mm`（只给「数据时间」粗粒度，秒是噪声；不许拿 — 盖住 0 之外的值）。 */
    fun formatClock(ms: Long): String {
        if (ms <= 0) return DASH
        return SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(ms))
    }

    /** 已装组标题的计数（market-view.ts installedCountPhrase :504-507；一条没装 ⇒ 空串不占行）。 */
    fun installedCountPhrase(copy: MarketCopy, count: Int, bytes: Long): String =
        if (count == 0) "" else copy.fill(copy.installedCountFormat, count, formatBytes(bytes))

    /** 索引组标题的计数（market-view.ts groupCountPhrase :495-497）。 */
    fun groupCountPhrase(copy: MarketCopy, count: Int): String = copy.fill(copy.groupCountFormat, count)

    /**
     * 下载量读数：真实计数原样（CONTRACT-MKM-STATS-UI §1 D1；量级上来后再按语种折
     * k/万，先不编格式）。无数据（缓存缺席 / zipUrl 未命中）由话术层折「—」，这里只收真值。
     */
    fun formatDownloadCount(n: Long): String = n.toString()

    /**
     * 行卡统计行那一句：有数据 = 「下载 {0}」（话术单源 [MarketCopy.downloadCountFormat]）；
     * 无数据 = 「—」（诚实口径：不编 0，market-view.ts 无数据不拿假话盖住的同一纪律）。
     */
    fun downloadCountPhrase(copy: MarketCopy, count: Long?): String =
        count?.let { copy.fill(copy.downloadCountFormat, formatDownloadCount(it)) } ?: DASH

    private const val BYTE_STEP = 1024L
    private const val DASH = "—"

    private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)
}

/**
 * 版本比较与 minAppVersion 闸（market.ts `compareVersions` :716-729、`coreVersion` :735-737、
 * `minAppVersionSatisfied` :847-859 的 Kotlin 面）。为什么在 UI 路也放一份纯比较：
 * 更新三态的权威判定在服务（[MarketService.updateCheck]），但**乐观更新后**的行内三态要
 * 立刻重算（market.ts updateRelationOf :1011-1014），而服务缝没有「对一对这两个版本串」的
 * 纯口——这里只做比较，不做解析校验（入参一律已过 parse 闸）。
 */
internal object MarketVersion {


    /** 段面：1..9 位数字（market.ts VERSION_SEG_RE :168；9 位段在 Long 里不溢出）。 */
    private val VERSION_SEG_RE = Regex("\\d{1,9}")

    private const val VERSION_SEGMENTS = 3

    /**
     * 严格三段数字版本比较：负 = a<b，0 = 相等，正 = a>b，null = 有一边不是合法三段
     * （数值比较非字典序：1.2.3 < 1.10.0，market.ts :725-727）。
     */
    fun compareVersions(a: String, b: String): Int? {
        val x = a.split('.').map(::seg)
        val y = b.split('.').map(::seg)
        val valid = x.size == VERSION_SEGMENTS && y.size == VERSION_SEGMENTS &&
            x.none { it == null } && y.none { it == null }
        if (!valid) return null
        return x.filterNotNull().zip(y.filterNotNull())
            .firstOrNull { (p, q) -> p != q }
            ?.let { (p, q) -> if (p < q) -1 else 1 }
            ?: 0
    }

    /** 剥掉预发布段：`0.4.0-beta.1` → `0.4.0`（market.ts :735-737）。 */
    fun coreVersion(v: String): String = v.substringBefore('-')

    /**
     * minAppVersion 闸：**委托 [minAppVersionSatisfiedCore]**（GATE2D 洞②③判据单源收口）。
     * 本壳唯一职责是保持 UI 侧既有参数序 (currentAppVersion, minAppVersion)——它与接口版
     * MarketVerifier.minAppVersionSatisfied 的参数序相反，这正是判词 20 洞③「两份可互接反」
     * 的死口；收口后判据只剩一处，委托处用具名实参接驳，逻辑不再各存一份。
     * 口径由 UI 的 formOk（宽三段正则）收口为与接口版同一 isPackVersion 全口径（每段 1-9 位
     * ASCII）：两口径分歧只发生在非法形态入参上，收口前后一律判 false，合法入参行为逐字不变
     * （真源 market.ts:847-859 语义保留在单源函数头上）。
     */
    fun minAppVersionSatisfied(currentAppVersion: String, minAppVersion: String): Boolean =
        minAppVersionSatisfiedCore(minAppVersion = minAppVersion, appVersion = currentAppVersion)

    private fun seg(s: String): Long? = if (VERSION_SEG_RE.matches(s)) s.toLong() else null
}

/**
 * 快照装配与乐观更新（actions.ts snapOf :73-93、snapshotWithoutInstalled :130-135、
 * snapshotWithInstalled :141-154、buildInstalledRows :647-663 的 Kotlin 面）。
 * 乐观只是**显示先行**：权威数字仍由宿主随后的零网络重读说了算（market-panel.ts:266-275）。
 */
internal object MarketOptimistic {

    /** 快照装配：非 Ok 态不给 packs（absent/invalid 的 packs 是空的，actions.ts :82）。 */
    fun snapshotOf(
        index: MarketIndexState,
        updates: Map<String, UpdateCheckState?>,
        installed: List<InstalledPack>,
        installedBytes: Long,
        endpoint: String,
    ): MarketViewModel.MarketSnapshot = MarketViewModel.MarketSnapshot(
        index = index,
        packs = (index as? MarketIndexState.Ok)?.index?.packs ?: emptyList(),
        updates = updates,
        installed = installed,
        installedBytes = installedBytes,
        endpoint = endpoint,
    )

    /**
     * 安装成功后的乐观快照：同 `(kind,id,version)` 键**替换**（重装/更新同版本）、否则**追加**；
     * 该行更新三态随本机版本重算（market.ts updateRelationOf :1011-1014），乐观行不演出旧按钮。
     */
    fun afterInstalled(snap: MarketViewModel.MarketSnapshot, rec: InstalledPack): MarketViewModel.MarketSnapshot {
        var replaced = 0L
        var found = false
        val installed = snap.installed.map { r ->
            if (r.kind == rec.kind && r.id == rec.id && r.version == rec.version) {
                found = true
                replaced += r.bytes
                rec
            } else {
                r
            }
        }.toMutableList()
        if (!found) installed.add(rec)
        val updates = snap.updates.toMutableMap()
        snap.packs.firstOrNull { it.kind == rec.kind && it.id == rec.id }?.let { entry ->
            updateStateOf(entry, rec)?.let { updates[MarketViewModel.indexRowKey(entry)] = it }
        }
        val bytes = snap.installedBytes - replaced + rec.bytes
        return snap.copy(installed = installed, installedBytes = bytes, updates = updates)
    }

    /**
     * 已安装列表（CONTRACT-MKM-W4-UI3 裁定 2：**组长裁定偏离** market-view.ts buildInstalledRows
     * :647-663——kojo-editor 全版本列出是作者工具语义，安卓玩家面按包去重：同 `(kind,id)`
     * 只保留最高版本一行，版本比较走 [MarketVersion.compareVersions] 的三段数值比较）。被
     * 隐藏的旧版本目录 = 库内惰性残留（W5 库内 GC 挂账，本波不动）。去重后排序照旧：按种类
     * 顺序 + 包名码元序 + 版本。
     */
    fun buildInstalledRows(copy: MarketCopy, list: List<InstalledPack>): List<MarketViewModel.MarketInstalledRow> =
        list.groupBy { it.kind to it.id }.values
            .map { same -> same.reduce { acc, next -> newestOf(acc, next) } }
            .sortedWith(compareBy({ kindOrder(it.kind) }, { it.name }, { it.version }))
            .map { rec ->
                MarketViewModel.MarketInstalledRow(
                    rec = rec,
                    key = MarketViewModel.installedKey(rec.kind, rec.id, rec.version),
                    digestPrefix = rec.contentDigest?.take(DIGEST_PREFIX_LEN),
                    kindLabel = kindLabel(copy, rec.kind),
                    sourceLabel = if (rec.containerSha256 == null) {
                        copy.installedSourceFolder
                    } else {
                        copy.installedSourceContainer
                    },
                )
            }

    /**
     * 同 `(kind,id)` 组里保留的那条 = 最高版本（[MarketVersion.compareVersions] 三段数值
     * 比较）。任一边不是合法三段（目录源手工版本等）时比较返回 null，退回字典序兜底——
     * 去重不许挑输入，非法版本串也要有确定序。
     */
    private fun newestOf(a: InstalledPack, b: InstalledPack): InstalledPack {
        val c = MarketVersion.compareVersions(a.version, b.version)
        return when {
            c == null -> if (b.version > a.version) b else a
            c < 0 -> b
            else -> a
        }
    }

    /** kind 的人读名（market-view.ts KIND_LABEL :36-40 的穷尽半边；未知 kind 校验层已拒，防御性回退机器值）。 */
    fun kindLabel(copy: MarketCopy, kind: String): String = when (kind) {
        MarketContract.KIND_KOJO -> copy.kindKojo
        MarketContract.KIND_DICT -> copy.kindDict
        MarketContract.KIND_TEMPLATE -> copy.kindTemplate
        MarketContract.KIND_MOD -> copy.kindMod
        else -> kind
    }

    /**
     * 按包卸载成功后的乐观快照（CONTRACT-MKM-W4-UI3 裁定 3）：该 `(kind,id)` 的**全部版本**
     * 一并摘除（按包去重后一行 = 一个包，宿主逐版本卸完发一次事件，显示面不许残留被隐藏的
     * 旧版本），占用按各版本记账条之和扣减；行内更新三态归「没装」
     * （market.ts packUpdateCheck :1023——本机一条都没有 = 三态 null）。
     */
    fun afterPackageUninstalled(
        snap: MarketViewModel.MarketSnapshot,
        rec: InstalledPack,
    ): MarketViewModel.MarketSnapshot {
        val installed = snap.installed.filterNot { it.kind == rec.kind && it.id == rec.id }
        val removedBytes = snap.installed
            .filter { it.kind == rec.kind && it.id == rec.id }
            .sumOf { it.bytes }
        val updates = snap.updates.toMutableMap()
        snap.packs.firstOrNull { it.kind == rec.kind && it.id == rec.id }?.let { entry ->
            updates[MarketViewModel.indexRowKey(entry)] = null   // 本机一条都没有 ⇒ 三态归「没装」（market.ts packUpdateCheck :1023）
        }
        return snap.copy(
            installed = installed,
            installedBytes = (snap.installedBytes - removedBytes).coerceAtLeast(0L),
            updates = updates,
        )
    }
    /**
     * 乐观行内更新三态（market.ts updateRelationOf :1011-1014 + sameContainer :1058-1061 的
     * 同款口径）：Ahead 带本机版本、Latest 的 sameContainer 用记账条的容器事实对索引 zip 锚
     * （目录源 = null，不拿 contentDigest 顶替）。
     */
    private fun updateStateOf(entry: MarketIndexEntry, rec: InstalledPack): UpdateCheckState? =
        MarketVersion.compareVersions(MarketVersion.coreVersion(rec.version), MarketVersion.coreVersion(entry.version))
            ?.let { c ->
                when {
                    c < 0 -> UpdateCheckState.Updatable(entry.version)
                    c > 0 -> UpdateCheckState.Ahead(rec.version)
                    else -> UpdateCheckState.Latest(rec.containerSha256?.let { it == entry.zipSha256 })
                }
            }

    private fun kindOrder(kind: String): Int {
        val at = MarketContract.KINDS.indexOf(kind)
        return if (at < 0) MarketContract.KINDS.size else at
    }

    private const val DIGEST_PREFIX_LEN = 8
}

/**
 * 一键更新链的纯逻辑缝（CONTRACT-MKM-W4-UI3 裁定 1）：「装库成功后条件链调桥」的判定、
 * 编排与完成句折算都收在这里，宿主（SettingsActivity.marketInstall）只做线程编排与事件
 * 投递。纯 JVM 零 Android 依赖，定向单测用假桥（lambda 计数）钉调用次数（MarketViewModelTest）。
 *
 * 链语义：UPDATE 行动 = 既有 [MarketService.install] 成功后，**台账里有该 `(kind,id)` 的
 * 记录**（玩家此前装过游戏）才调桥 `importInstalled`（传入新版本的记账条）；台账无记录 =
 * 只装库、不擅自改游戏状态；装库失败根本进不到桥段（既有拒因文案收口）。链路失败各自
 * 出声：桥段失败折进既有 bridgeNote 五态，装库成功段不重复播报。
 */
internal object MarketUpdateChain {

    /**
     * 桥段编排：[installOk]（装库成败）与 [ledgerHasRecord]（台账查询缝的判定结果，生产 =
     * `MarketImportsLedger.lookup(kind,id)` 非 null）都真才调一次桥；任一为假 = 返回 null
     * （桥没跑 = 只装库形态）。[importInstalled] = 桥动作注入缝（生产 = `MarketGameBridge
     * .importInstalled`；测试 = 假桥数调用次数与实参）。
     *
     * @return null = 桥段没跑；非空 = 桥五态原样（宿主与 [doneNote] 折成一句）。
     */
    fun run(
        installOk: Boolean,
        ledgerHasRecord: Boolean,
        pack: InstalledPack,
        modRuntimeEnabled: Boolean,
        importInstalled: (pack: InstalledPack, modRuntimeEnabled: Boolean) -> MarketBridgeOutcome,
    ): MarketBridgeOutcome? =
        if (installOk && ledgerHasRecord) importInstalled(pack, modRuntimeEnabled) else null

    /**
     * 链的完成句（裁定 5 两形态）：桥段没跑（[chainOutcome] = null）= 既有「更新成」句
     * （只装库形态）；桥 Ok = 「更新并装进游戏」新句（装库成功段不重复播报）；桥其余四态 =
     * 既有 [MarketBridgeUi.bridgeNote] 五态（新旧版状态如实）。
     */
    fun doneNote(
        copy: MarketCopy,
        name: String,
        version: String,
        chainOutcome: MarketBridgeOutcome?,
    ): String = when {
        chainOutcome == null -> MarketPhrases.installDonePhrase(copy, isUpdate = true, name, version)
        chainOutcome is MarketBridgeOutcome.Ok -> copy.fill(copy.updateChainDoneFormat, name, version)
        else -> MarketBridgeUi.bridgeNote(copy, name, chainOutcome)
    }
}

/**
 * 按包卸载的成败折叠（CONTRACT-MKM-W4-UI3 裁定 3 的纯逻辑半边）：宿主对 `(kind,id)` 的全部
 * 已装版本逐个调 uninstall 后，把逐版本结果折成一个 [UninstallOutcome]——任一 Failed =
 * 失败（取首个 problem 上屏，detail 纪律照旧）；一条都没卸成且无失败 = NotFound（本机没有
 * 这个包，不用卸）；否则 = Ok（端态 = 该包一个版本都不剩）。NotFound 不计失败——该版本
 * 目录已不在位时端态同样是「没了」（在位自愈的漏网补刀）。
 */
internal object MarketPackageUninstall {

    /** 折叠规则（定向单测钉死；空表 = NotFound，宿主快照滞后的兜底出口）。 */
    fun fold(results: List<UninstallOutcome>): UninstallOutcome {
        val failed = results.filterIsInstance<UninstallOutcome.Failed>()
        if (failed.isNotEmpty()) return UninstallOutcome.Failed(failed.first().problem)
        val okCount = results.count { it == UninstallOutcome.Ok }
        return if (okCount > 0) UninstallOutcome.Ok else UninstallOutcome.NotFound
    }
}

/** 端点行的投影（market-source.ts 的 sourceRowUi :126-135 与 applyEndpointEdit :161-182 回执半边）。 */
internal object MarketEndpointUi {

    /** 是否处于「自建源」状态（默认值只住契约常量，这里比字面、不复制常量；market-source.ts :40-42）。 */
    fun isOverridden(endpoint: String): Boolean = endpoint != MarketContract.DEFAULT_INDEX_URL

    /** 状态主句：现在用的是默认地址还是自建地址（market-source.ts :130）。 */
    fun endpointStatus(copy: MarketCopy, endpoint: String): String =
        if (isOverridden(endpoint)) copy.endpointStatusOverridden else copy.endpointStatusDefault

    /**
     * 保存回执（market-source.ts :161-182）：keep = 什么都不做不放假回执；reset/set 的判定
     * 与形态闸都在 [MarketService.setEndpoint]（骨架唯一写入口），这里只把四态折成一句。
     */
    fun endpointNote(copy: MarketCopy, result: EndpointEditResult): String = when (result) {
        is EndpointEditResult.Kept -> copy.endpointKept
        is EndpointEditResult.Reset -> copy.endpointResetDone
        is EndpointEditResult.Set -> copy.endpointSetDone
        is EndpointEditResult.Invalid -> copy.fill(copy.endpointInvalidFormat, result.problem)
    }
}
