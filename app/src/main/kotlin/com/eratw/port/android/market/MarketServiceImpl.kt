package com.eratw.port.android.market

import java.io.File
import java.io.IOException

/**
 * 市场服务门面的实现（CONTRACT-MKM-W1 §2 mkm-pipe 判据 6）。
 *
 * 语义权威 = kojo-editor `packages/plugins/kojo-market/src/service.ts` 的刷新三态
 * （`refreshIndex` :466-527：TTL 内不回源、校验通过**才**覆盖镜像、失败的刷新永远不许
 * 把好用的旧列表换成坏文件）、端点（`effectiveEndpoint` :460-464 / `setEndpoint` :1228-1242）、
 * 记账核验（`verifiedRecords` :287-307）、更新检测（market.ts `packUpdateCheck` :1025-1063，
 * 本机读数照 `PackUpdateCheck.sameContainer` :887-896）。
 * 端点编辑四态的判定序照 `market-source.ts` 的 `planEndpointEdit`（:40-49：keep → reset →
 * invalid → set，形态闸只核 https/主机/userinfo，白名单在取数时核）。
 * 三缝回执形状（refreshIndex 三态 / UpdateCheckState 本机读数 / install 阶段回调）=
 * CONTRACT-MKM-W6 §0 冻结设计。
 *
 * 盘上镜像读写是**本文件私有最小实现**（4 个小文件：`index.json` 原文镜像、
 * `fetched-at.txt` 取数时刻、`endpoint.txt` 覆盖端点、`endpoint-health.txt` 端点健康度
 * ——CONTRACT-MKM-W7 §0.1）——domain 路的 `MarketIndexStore`
 * 不 import（CONTRACT §1 所有权互斥），两者只共享「校验通过才覆盖、坏文件不写回」的口径。
 *
 * 索引端点序由本层算（[endpointOrderWithHealth]，CONTRACT-MKM-W7 §0.2：覆盖恒第一 →
 * 健康度降序 → 无记录默认序；先例 = core-vm `UpdatePolicy.mirrorOrderWithHealth`，只借
 * 语义不 import），取数器只收有序列表。install 的容器下载主机不参与健康度（只管 index 端点）。
 *
 * 线程口径：所有方法阻塞调用线程；install/uninstall 走同一把监视器锁单飞互斥
 * （CONTRACT §2.6），refresh 的镜像写用临时文件 + rename 原子落盘。
 */
open class MarketServiceImpl(
    private val marketRoot: File,
    private val verifier: MarketVerifier,
    private val fetcher: MarketFetcher = MarketFetcher(),
    private val store: MarketStore = MarketStore(marketRoot),
    private val installer: MarketInstaller = MarketInstaller(store, verifier, fetcher),
    /** 时钟缝（可测路径不直呼 System.currentTimeMillis）：缺省挂系统钟，定向单测注入假钟。 */
    private val clock: () -> Long = { System.currentTimeMillis() },
) : MarketService {

    /** 装卸单飞锁（双线程并发 install/uninstall 必须串行化，测试断言）。 */
    private val mutationLock = Object()

    /** 盘上四件套读写层（镜像 / 取数时刻 / 端点覆盖 / 端点健康度）。 */
    private val disk = Persistence(marketRoot, verifier)

    /** 最近一次索引三态的内存缓存（读盘惰性种子；刷新成功/校验拒后原地更新）。 */
    @Volatile
    private var stateCache: MarketIndexState? = null

    override fun indexState(): MarketIndexState = stateCache ?: disk.readMirrorState().also { stateCache = it }

    override fun refreshIndex(force: Boolean): MarketRefreshOutcome {
        val fetchedAt = disk.readFetchedAtMs()
        val now = clock()
        // TTL 窗口内不回源（service.ts:477-481：开一次面板打一次 1.6 KB 也是打，但每次点开都打没必要）
        if (!force && fetchedAt != null && now - fetchedAt < MarketContract.INDEX_TTL_MS) {
            val cached = indexState()
            if (cached is MarketIndexState.Ok) return MarketRefreshOutcome.Cached(cached.index)
            // 退化边：TTL 命中但盘上状态不是 Ok（镜像在写盘后被外部打坏/删除）——没有可回的
            // 缓存对象，不回 Cached 假装缓存还在，照非 force 续走一次回源校准。
        }
        // 回源：端点序 = 覆盖恒第一 + 健康度降序 + 无记录默认序（CONTRACT-MKM-W7 §0.2）；
        // 校验通过才覆盖镜像（service.ts:508-518）；全败/校验拒 = Stale，盘上旧列表原样保留
        val health = disk.readEndpointHealth()
        val order = endpointOrderWithHealth(disk.readEndpointOverride(), health)
        val result = fetchAndParse(fetcher, verifier, disk, order, now)
        // 健康度只记成功（§0.3）：successUrl 非空 = 该端点取数 200 且过 parseIndex 校验，
        // 盖 lastSuccessMs 章；失败路径根本不碰健康文件（不记负分、不清旧记录）。写盘失败
        // 吞掉不外抛——健康度是辅助排序因子，丢一章只是下轮排序退回，不能把已写好镜像的
        // 成功刷新翻成异常。
        result.successUrl?.let { url ->
            runCatching { disk.writeEndpointHealth(health + (url to now)) }
        }
        result.state?.let { stateCache = it }
        return when (val next = result.state) {
            is MarketIndexState.Ok -> MarketRefreshOutcome.Fresh(next.index)
            is MarketIndexState.Invalid -> MarketRefreshOutcome.Stale(next.problem)
            // null = 全端点取数失败（保留旧三态）；Absent 形态 fetchAndParse 不产出（镜像只在
            // Ok 时写），防御归并进 Stale——没有可用的新列表就没有 Fresh
            MarketIndexState.Absent, null -> MarketRefreshOutcome.Stale(
                if (result.problems.isEmpty()) "取数端点都连不上" else "取数端点都连不上：" + result.problems.joinToString("；"),
            )
        }
    }

    override fun installed(): List<InstalledPack> = store.verifiedRecords()

    override fun installedBytes(): Long = store.installedBytes()

    /** 更新检测（market.ts `packUpdateCheck` :1025-1063）：本机取同 (kind,id) 最高版本比索引。 */
    override fun updateCheck(entry: MarketIndexEntry): UpdateCheckState? {
        val local = store.verifiedRecords().filter { it.kind == entry.kind && it.id == entry.id }
        // 本机一条都没有 ⇒ null（"没装"不是态，是另一个动作）
        val best = local.reduceOrNull { acc, next ->
            // compareVersions 解不动 = 不换 best（照 service.ts 同款 `c !== null && c > 0` 判序）
            if ((runCatching { verifier.versionCompare(next.version, acc.version) }.getOrNull() ?: 0) > 0) next else acc
        } ?: return null
        val relation = runCatching { verifier.versionCompare(entry.version, best.version) }.getOrNull()
        return when {
            relation == null -> UpdateCheckState.Failed(
                "版本号写法本机读不出，无法判断该不该更新（注册表 ${entry.version} / 本机 ${best.version}）",
            )
            relation > 0 -> UpdateCheckState.Updatable(entry.version)
            // sameContainer 读数照 market.ts :1058-1061：latest 态且本机有容器事实才比，
            // 目录源（containerSha256 缺省）= null，不拿 contentDigest 顶替
            relation == 0 -> UpdateCheckState.Latest(best.containerSha256?.let { it == entry.zipSha256 })
            else -> UpdateCheckState.Ahead(best.version)
        }
    }

    /**
     * 安装：单飞互斥；id-kind 查重面 = 已装集 + 索引原样（service.ts:533-538 同一组合并）。
     * 阶段回调（CONTRACT-MKM-W6 §0.3）：编排层围绕三段外发同一回调——DOWNLOAD 把安装器的
     * 下载字节回调逐笔直通（字节口径照旧，顺序不打乱），下载流收口点（done ≥ total 首次
     * 出现）补一声 EXTRACT（总量/起点 0，不编假进度；取数层节流可能吞掉收口那笔，此时
     * EXTRACT 不出声、UI 停在最后一条下载读数 = W1 同款如实停顿）；安装链 Ok 收口处补一声
     * COMMIT（清单累计字节 = 记账条 bytes，总量 0）。MarketInstaller/MarketFetcher 内部
     * 零改动——安装器对编排层是单次阻塞调用，EXTRACT/COMMIT 只能由本层在可观测点上合成。
     */
    override fun install(
        entry: MarketIndexEntry,
        onProgress: (phase: MarketInstallPhase, doneBytes: Long, totalBytes: Long) -> Unit,
    ): InstallOutcome = synchronized(mutationLock) {
        val refs = ArrayList<MarketPackRef>()
        refs.addAll(store.verifiedRecords().map { MarketPackRef(it.kind, it.id) })
        val state = indexState()
        if (state is MarketIndexState.Ok) {
            refs.addAll(state.index.packs.map { MarketPackRef(it.kind, it.id) })
        }
        var extractAnnounced = false
        val outcome = installer.installFromIndex(
            entry,
            { done, total ->
                // 下载流逐笔直通（字节口径照旧）；收口那笔之后补 EXTRACT 起点（顺序：先
                // DOWNLOAD 后 EXTRACT，不让阶段声明插队进下载流中间）
                onProgress(MarketInstallPhase.DOWNLOAD, done, total)
                if (!extractAnnounced && total > 0 && done >= total) {
                    extractAnnounced = true
                    onProgress(MarketInstallPhase.EXTRACT, 0L, 0L)
                }
            },
            refs,
        )
        // 成功提交后锁内挂库内被超越版本清扫（CONTRACT-MKM-W5-GC §0.2：新版本入库即清
        // 同 id 旧版，更新流的自然收尾）；失败/拒绝不动库。清扫内部失败静默不重试
        // （store 侧 KDoc），不改安装回执。
        if (outcome is InstallOutcome.Ok) {
            // COMMIT 段读数：搬运已成 = 累计搬运清单累计字节（记账条 bytes），总量 0
            // （总量未知口径，不编假百分比）；拒绝/失败路径不发 COMMIT。
            onProgress(MarketInstallPhase.COMMIT, outcome.pack.bytes, 0L)
            store.sweepSuperseded(verifier)
        }
        outcome
    }

    override fun uninstall(kind: String, id: String, version: String): UninstallOutcome = synchronized(mutationLock) {
        store.uninstall(kind, id, version)
    }

    /**
     * 显式清扫库内被超越版本（CONTRACT-MKM-W5-GC §0.2：透传 [MarketStore.sweepSuperseded]；
     * 本波补读数面）：返回 [SweepOutcome]——删除的版本目录数 / 未预期失败原话。实现类公开
     * 方法——骨架 [MarketService] 接口不加方法；启动触发（SettingsActivity 开设置页后台扫一次）
     * 与市场覆盖层手动入口共用本方法；锁内跑，与 install/uninstall 同一把单飞锁。
     * 异常折成 [SweepOutcome.Failed] 而不是外抛：手动入口的互斥闸靠回执清（回执不落屏 =
     * 按钮永久禁用），回执必须总能落屏。
     */
    fun sweepSuperseded(): SweepOutcome = synchronized(mutationLock) {
        try {
            SweepOutcome.Swept(store.sweepSuperseded(verifier))
        } catch (e: Exception) {
            SweepOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** 生效端点：用户覆盖优先，否则契约默认值（默认值只住契约常量）。 */
    override fun endpoint(): String =
        disk.readEndpointOverride()?.takeIf { it.isNotBlank() } ?: MarketContract.DEFAULT_INDEX_URL

    /** 端点编辑唯一写入口（planEndpointEdit :40-49 同序：keep → reset → invalid → set）。 */
    override fun setEndpoint(url: String): EndpointEditResult {
        val trimmed = url.trim()
        return when {
            trimmed == endpoint() -> EndpointEditResult.Kept
            // 空串 = 撤销覆盖 = 回到契约默认值（默认值只有契约常量一份，这里不复制）
            trimmed.isEmpty() -> EndpointEditResult.Reset.also { disk.clearEndpointOverride() }
            else -> when (val problem = endpointFormProblem(trimmed)) {
                null -> EndpointEditResult.Set(trimmed.also { disk.writeEndpointOverride(it) })
                else -> EndpointEditResult.Invalid(problem)
            }
        }
    }
}

/**
 * 端点**形态**闸（market.ts:748-758 `endpointProblem` 同判定，纯函数零网络）：
 * 必须 https、必须有主机名、不许带 userinfo。**这不是白名单**——可信主机的唯一真源
 * 在 [MarketFetcher] 的放行表（每次取数/每跳重定向重核），本闸只拦"一看就打不开"的输入。
 */
private fun endpointFormProblem(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull()
    return when {
        uri == null -> "不是一个合法 URL：$url"
        uri.scheme != "https" -> "协议必须是 https（收到 ${uri.scheme ?: "无"}）"
        uri.host.isNullOrBlank() -> "URL 没有主机名"
        !uri.userInfo.isNullOrEmpty() -> "URL 不许带账号信息"
        else -> null
    }
}

/**
 * 一次回源的收口（三缝 1 的编排内面）：state = 收口三态；problems = 收口前的逐端点失败原话；
 * successUrl = 过了 parseIndex 校验的那个端点（健康度记账键；null = 全败或校验拒，§0.3 只记成功）。
 */
private class FetchOutcome(
    val state: MarketIndexState?,
    val problems: List<String>,
    val successUrl: String?,
)

/**
 * 逐端点回退取数 + 解析（文件级私有件，收口形状见 [FetchOutcome]）：端点序已由调用方算好
 * （[endpointOrderWithHealth]），回退循环住取数层 [MarketFetcher.fetchTextFromEndpoints]；
 * 首个取数成功的端点按其解析结果收口，校验通过才覆盖镜像（写原文一字不改，service.ts:508-518）；
 * 全挂 = state null。校验拒时镜像不动、fetched-at 不动、健康度不盖章（三态语义，skeleton 注释同款）。
 */
private fun fetchAndParse(
    fetcher: MarketFetcher,
    verifier: MarketVerifier,
    disk: Persistence,
    order: List<String>,
    now: Long,
): FetchOutcome {
    // 白名单/逐跳重核/体积/超时全在取数层；取数失败换下一个端点（照 update 通道镜像先例）
    val fetched = fetcher.fetchTextFromEndpoints(order, MarketContract.INDEX_TIMEOUT_MS, MarketContract.INDEX_MAX_BYTES)
    val hit = fetched.fetched ?: return FetchOutcome(null, fetched.problems, null)
    return when (val parsed = verifier.parseIndex(hit.text, now)) {
        is MarketParseResult.Ok -> {
            disk.writeMirror(hit.text)
            disk.writeFetchedAtMs(now)
            FetchOutcome(MarketIndexState.Ok(parsed.value), fetched.problems, fetched.requestedUrl)
        }
        // 回源成功但校验拒：镜像不动、fetched-at 不动、健康度不盖成功章
        is MarketParseResult.Invalid ->
            FetchOutcome(MarketIndexState.Invalid(parsed.problem), fetched.problems, null)
    }
}

/**
 * 索引端点候选序（CONTRACT-MKM-W7 §0.2；先例 = core-vm `UpdatePolicy.mirrorOrderWithHealth`
 * :707-732，只借语义不 import）：
 *
 * 1. **覆盖端点恒第一**（[override] 非空且非默认值——用户显式意志优先于健康度，即使镜像
 *    有新章也压不过覆盖值）；
 * 2. 其余端点**有健康度记录的按 lastSuccessMs 降序在前**；
 * 3. **无记录的保持默认序**（DEFAULT 先于 MIRROR）垫后。
 *
 * 候选全集 = 覆盖值 + DEFAULT + MIRROR 按此序去重。与旧固定序（W7 已从骨架删除）的差别
 * 只有一处且有意为之：覆盖值不再顶掉 DEFAULT 位——「覆盖恒第一」要靠本函数钉，
 * 而健康度排序要求 DEFAULT/MIRROR 都在候选里才有排序可言（覆盖 = MIRROR 时自然退化为
 * MIRROR、DEFAULT，也顺带不复现旧值的重复表项）。排序是纯内存判序：无熔断、无淘汰；
 * 健康文件里的旧键（换过覆盖端点的残留）不参与本序也不清理（裁定 4：Reset 不删健康度）。
 * lastSuccessMs 打平时 [sortedByDescending] 的稳定序保持 DEFAULT 在前（默认序兜底）。
 */
private fun endpointOrderWithHealth(override: String?, health: Map<String, Long>): List<String> {
    val covered = override?.trim()?.takeIf { it.isNotEmpty() && it != MarketContract.DEFAULT_INDEX_URL }
    val rest = LinkedHashSet(listOf(MarketContract.DEFAULT_INDEX_URL, MarketContract.MIRROR_INDEX_URL))
    rest.remove(covered)
    val recorded = rest.filter { health.containsKey(it) }.sortedByDescending { health.getValue(it) }
    val unrecorded = rest.filter { !health.containsKey(it) }
    return listOfNotNull(covered) + recorded + unrecorded
}

/** 健康行解析：`url<TAB>毫秒`；缺 TAB、url 空、毫秒读不出 = null（跳过该行）。 */
private fun parseHealthLine(line: String): Pair<String, Long>? {
    val sep = line.indexOf('\t').takeIf { it > 0 }
    val url = sep?.let { line.substring(0, it).trim() }
    val ms = sep?.let { line.substring(it + 1).trim().toLongOrNull() }
    return if (url.isNullOrEmpty() || ms == null) null else url to ms
}

/** 临时文件 + 原子挪写盘（market.rs:364-408 同形态；Windows 覆盖语义走 MarketAtomicMove）。 */
private fun writeTextAtomic(file: File, text: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, ".${file.name}.tmp")
    try {
        tmp.writeText(text, Charsets.UTF_8)
        MarketAtomicMove.move(tmp, file)
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

/** 盘上四件套（镜像 / 取数时刻 / 端点覆盖 / 端点健康度）的最小读写层，全部原子落盘。 */
private class Persistence(
    marketRoot: File,
    private val verifier: MarketVerifier,
) {

    private val mirrorFile = File(marketRoot, MarketContract.INDEX_FILE)
    private val fetchedAtFile = File(marketRoot, FETCHED_AT_FILE)
    private val endpointFile = File(marketRoot, ENDPOINT_FILE)
    private val healthFile = File(marketRoot, ENDPOINT_HEALTH_FILE)

    /** 最近一次读盘失败的取证位（读不出 = 视同缺席，失败原因不静默丢）。 */
    private var lastReadProblem: String? = null

    /** 读镜像 + 解析 → 三态（文件缺席 = Absent；解析拒 = Invalid，盘上原样保留）。 */
    fun readMirrorState(): MarketIndexState {
        val text = readText(mirrorFile) ?: return MarketIndexState.Absent
        val fetchedAt = readFetchedAtMs() ?: 0L
        return when (val parsed = verifier.parseIndex(text, fetchedAt)) {
            is MarketParseResult.Ok -> MarketIndexState.Ok(parsed.value)
            is MarketParseResult.Invalid -> MarketIndexState.Invalid(parsed.problem)
        }
    }

    fun writeMirror(text: String) = writeTextAtomic(mirrorFile, text)

    fun readFetchedAtMs(): Long? = readText(fetchedAtFile)?.trim()?.toLongOrNull()

    fun writeFetchedAtMs(ms: Long) = writeTextAtomic(fetchedAtFile, ms.toString())

    fun readEndpointOverride(): String? = readText(endpointFile)?.trim()?.takeIf { it.isNotEmpty() }

    fun writeEndpointOverride(url: String) = writeTextAtomic(endpointFile, url)

    fun clearEndpointOverride() {
        runCatching { endpointFile.delete() }
    }

    /**
     * 端点健康度（CONTRACT-MKM-W7 §0.1）：`endpoint-health.txt` 每行
     * `<url>\t<lastSuccessMs>`（TAB 分隔——端点值都过 https 形态闸，不含原始 TAB；
     * epoch 毫秒）。文件缺席/整行读不出 = 空表（fail-open：排序退回默认序）；坏行静默
     * 跳过不拒读。只记成功时刻：写入口只在端点取数成功且过 parseIndex 校验后被调用
     * （§0.3），失败路径不写文件——负分与「清旧记录」两个形状都不存在。
     */
    fun readEndpointHealth(): Map<String, Long> =
        readText(healthFile)?.lineSequence()?.mapNotNull(::parseHealthLine)?.toMap() ?: emptyMap()

    /** 全量重写健康文件（键序 = 传入 map 迭代序：旧键在前、新章键追加在后，行数个位数）。 */
    fun writeEndpointHealth(health: Map<String, Long>) = writeTextAtomic(
        healthFile,
        health.entries.joinToString("\n") { "${it.key}\t${it.value}" },
    )

    /** 读文本；读不出（IO 异常）= 视同文件缺席，原因记进 [lastReadProblem] 不静默丢。 */
    private fun readText(file: File): String? = try {
        file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    } catch (e: IOException) {
        lastReadProblem = e.message
        null
    }

    private companion object {
        private const val FETCHED_AT_FILE = "fetched-at.txt"
        private const val ENDPOINT_FILE = "endpoint.txt"

        /** 端点健康度文件名（市场根下，与镜像三件套同层；格式见 [readEndpointHealth]）。 */
        private const val ENDPOINT_HEALTH_FILE = "endpoint-health.txt"
    }
}
