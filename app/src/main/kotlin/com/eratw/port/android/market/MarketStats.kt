package com.eratw.port.android.market

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 市场包的**下载量统计**（CONTRACT-MKM-STATS-UI §1 D1）。
 *
 * 数据裁定：索引 schema v1 无下载量字段（上游 kojo-market 仓与 gh-pages index.json 实测
 * 确认），本波不改索引契约——但市场全部容器 zip 都挂在 GitHub Releases 上（zip.url 形态
 * `https://github.com/<owner>/<repo>/releases/download/<tag>/<asset>`），Releases API 的
 * `assets[].download_count` 就是**全网真实累计下载次数**，一次全量请求即可覆盖索引全部条目。
 *
 * 诚实纪律（与 MarketRefreshOutcome 三态同一口径）：
 * - API 失败 / 超时 / 形状畸形 ⇒ 用盘上旧缓存（[MarketStatsStore.read]）；
 * - 旧缓存也没有 ⇒ 统计位显示「—」，**不显示假数、不阻塞市场列表**（独立异步段，
 *   宿主在市场覆盖层打开时后台拉一次，UI 等事件落屏）。
 *
 * 健壮性：GitHub 匿名限额 60 req/h ⇒ 取数带 TTL（TTL 内直接回缓存不联网，TTL 常量单源
 * [MarketContract.INDEX_TTL_MS]）；解析逐 asset 防御（缺键/形状不对跳该 asset，非整批拒）。
 *
 * 模块纪律：本文件纯 JVM 零 Android API，定向单测可跑（app 单测源集为 JVM 口径）。
 */

/** 一次统计快照：取数时刻 + zipUrl → 累计下载次数（键 = Releases 的 `browser_download_url`）。 */
data class MarketStatsSnapshot(
    val fetchedAtMs: Long,
    val downloads: Map<String, Long>,
)

/** 统计缓存三态（与 [MarketIndexState] 同形口径：缺席 / 正常；统计缓存无「非法」态——损坏按缺席重建）。 */
sealed interface MarketStatsState {
    data object Absent : MarketStatsState
    data class Ok(val snapshot: MarketStatsSnapshot) : MarketStatsState
}

/** Releases JSON 解析结果：Ok = 全量映射；Invalid = 整篇形状不对（非数组等），一个映射都不给。 */
sealed interface MarketStatsParseResult {
    data class Ok(val downloads: Map<String, Long>) : MarketStatsParseResult
    data class Invalid(val problem: String) : MarketStatsParseResult
}

/**
 * Releases 响应解析（纯函数零 IO）。
 *
 * 期望形状 = GitHub API `GET /repos/{owner}/{repo}/releases` 的数组：
 * `[{ "assets": [ { "browser_download_url": "...", "download_count": 12 }, ... ] }, ...]`。
 * 逐 asset 防御：条目非对象 / assets 缺席或非数组 / asset 非对象 / URL 缺席或空 /
 * download_count 非负整数 ⇒ **跳过该 asset**（真实响应里个别 release 没有资产属正常形态，
 * 跳过不拒整批）；只有**整篇不是数组**才判 Invalid（那意味着端点被劫持或 API 形态变更，
 * 全量映射没有可信度）。重复 URL 后值胜（与 [MarketJson] 重复键口径一致）。
 */
object MarketStatsParser {

    /** 解析 Releases JSON 全文；语法不合法或整篇形状不对返回 [MarketStatsParseResult.Invalid]。 */
    fun parse(json: String): MarketStatsParseResult {
        val root = MarketJson.parseOrNull(json) ?: return invalidRoot("Releases 响应不是合法 JSON")
        if (root !is JsonArray) return invalidRoot("Releases 响应不是数组")
        val out = LinkedHashMap<String, Long>()
        for (release in root.items) {
            val assets = (release as? JsonObject)?.opt("assets") as? JsonArray ?: continue
            for (asset in assets.items) {
                downloadCountOf(asset)?.let { (url, count) -> out[url] = count }
            }
        }
        return MarketStatsParseResult.Ok(out)
    }

    /**
     * 单个 asset → (browser_download_url, download_count)；任一形状不合规给 null
     * （跳过不拒整批，真实响应里个别 release 没有资产属正常形态）。
     */
    private fun downloadCountOf(asset: JsonNode): Pair<String, Long>? {
        val a = asset as? JsonObject ?: return null
        val url = (a.opt("browser_download_url") as? JsonString)?.value
        if (url.isNullOrEmpty()) return null
        val count = (a.opt("download_count") as? JsonNumber)?.longValueOrNull()
        if (count == null || count < 0) return null
        return Pair(url, count)
    }

    private fun invalidRoot(problem: String): MarketStatsParseResult.Invalid =
        MarketStatsParseResult.Invalid(problem)
}

/**
 * 盘上统计缓存（`<filesDir>/market/stats.json`）。
 *
 * 纯缓存语义（与 [MarketIndexStore] 的「权威镜像」不同级）：读失败 / 损坏一律回
 * [MarketStatsState.Absent]（下次取数成功自然重建，盘上一个字节不动——读路径不写纪律同源）；
 * 写失败静默（统计是锦上添花，不值得为它报错打扰玩家）。落盘 = 临时文件写满 fsync 后
 * rename 覆盖（原子替换，失败不留半截文件）。
 */
class MarketStatsStore(private val file: File) {

    /** 读盘上缓存：文件不存在 / 读不出 / JSON 语法或形状不对 / 字段畸形 ⇒ 一律 [MarketStatsState.Absent]。 */
    fun read(): MarketStatsState {
        if (!file.exists()) return MarketStatsState.Absent
        val root = try {
            MarketJson.parseOrNull(file.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            return MarketStatsState.Absent
        }
        return parseSnapshot(root)?.let { MarketStatsState.Ok(it) } ?: MarketStatsState.Absent
    }

    /**
     * 缓存 JSON → 快照（单出口）：schema 形状任一不合 ⇒ null（读侧回空不写盘，
     * 下次取数成功自然重建）。
     */
    private fun parseSnapshot(root: JsonNode?): MarketStatsSnapshot? {
        val obj = root as? JsonObject ?: return null
        val fetchedAt = (obj.opt("fetchedAtMs") as? JsonNumber)?.longValueOrNull()
        if (fetchedAt == null || fetchedAt <= 0) return null
        val downloadsNode = obj.opt("downloads") as? JsonObject ?: return null
        val downloads = downloadsNode.members.mapNotNull { (url, node) ->
            if (url.isEmpty()) return@mapNotNull null
            val count = (node as? JsonNumber)?.longValueOrNull()
            if (count == null || count < 0) null else url to count
        }
        return MarketStatsSnapshot(fetchedAtMs = fetchedAt, downloads = downloads.toMap())
    }

    /** 覆盖写缓存；IO 失败静默（返回 false，盘上旧文件原样——统计缓存不值得报错面）。 */
    fun write(snapshot: MarketStatsSnapshot): Boolean {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
        val temp = File(parent, file.name + ".tmp")
        return try {
            FileChannel.open(
                temp.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { channel ->
                channel.write(ByteBuffer.wrap(serialize(snapshot).toByteArray(Charsets.UTF_8)))
                channel.force(true)
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (_: IOException) {
            runCatching { temp.delete() }
            false
        } catch (_: SecurityException) {
            runCatching { temp.delete() }
            false
        }
    }

    /** 缓存序列化（手写最小 JSON：URL 全 ASCII 常态直通，控制字符按 RFC 8259 转义）。 */
    private fun serialize(snapshot: MarketStatsSnapshot): String = buildString {
        append("{\"schema\":")
        append(MarketContract.STATS_SCHEMA)
        append(",\"fetchedAtMs\":")
        append(snapshot.fetchedAtMs)
        append(",\"downloads\":{")
        var first = true
        for ((url, count) in snapshot.downloads) {
            if (!first) append(',')
            first = false
            append(jsonString(url))
            append(':')
            append(count)
        }
        append("}}")
    }

    /** 最小 JSON 字符串转义（URL 全 ASCII 常态直通；引号反斜杠与控制字符按 RFC 8259 转义）。 */
    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}

/** 取数接缝（生产 = GitHub Releases API；测试注入固定响应，零网络）。 */
interface MarketStatsFetcher {
    /** 拉取 Releases 全量 JSON 原文；失败抛异常（编排层折降级）。 */
    fun fetchReleasesJson(): String
}

/** GitHub Releases API 取数实现（HttpURLConnection，15s 超时同索引取数）。 */
class MarketStatsHttpFetcher(private val apiUrl: String = MarketContract.RELEASES_API_URL) : MarketStatsFetcher {

    override fun fetchReleasesJson(): String {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = MarketContract.INDEX_TIMEOUT_MS.toInt()
            conn.readTimeout = MarketContract.INDEX_TIMEOUT_MS.toInt()
            // GitHub API 无 UA 直接 403；Accept 走 vnd.github+json 拿稳定形状。
            conn.setRequestProperty("User-Agent", "eratw-android-market")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code !in HTTP_OK_MIN..HTTP_OK_MAX) {
                throw IOException("Releases API HTTP $code")
            }
            // 2xx 分支 inputStream 必在（4xx/5xx 时它是 errorStream，上面已抛）。
            val body = conn.inputStream.use { stream -> stream.readBytes() }
            return String(body, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val HTTP_OK_MIN = 200
        const val HTTP_OK_MAX = 299
    }
}

/** 统计取数编排（缓存 + TTL + 降级；宿主在覆盖层打开时后台调一次，UI 等事件落屏）。 */
class MarketStatsLoader(
    private val store: MarketStatsStore,
    private val fetcher: MarketStatsFetcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 编排结果：Ok 带快照（可能是新鲜取数也可能是 TTL 内旧缓存）；Stale = 取数失败（带旧缓存或 null）。 */
    sealed interface Outcome {
        data class Ok(val snapshot: MarketStatsSnapshot, val fromNetwork: Boolean) : Outcome
        data class Stale(val previous: MarketStatsSnapshot?) : Outcome
    }

    /**
     * TTL 内直接回盘上缓存（fromNetwork = false）；过期或缺席才联网：取数 + 解析成功 ⇒
     * 覆盖缓存；失败 ⇒ 回 [Outcome.Stale]（带盘上旧快照，可能为 null——降级面由 UI 决定显示）。
     * 取数段的异常面（IO/安全/运行期）统一折 [staleWith]——统计是锦上添花，任何失败都不上抛。
     */
    fun load(force: Boolean = false): Outcome {
        val now = clock()
        val cached = store.read()
        val freshSnapshot = (cached as? MarketStatsState.Ok)?.snapshot
        if (!force && freshSnapshot != null &&
            now - freshSnapshot.fetchedAtMs < MarketContract.INDEX_TTL_MS
        ) {
            return Outcome.Ok(freshSnapshot, fromNetwork = false)
        }
        val fresh = fetchFreshSnapshot(now) ?: return Outcome.Stale(freshSnapshot)
        store.write(fresh)
        return Outcome.Ok(fresh, fromNetwork = true)
    }

    /** 一次联网取数 + 解析（null = 任何失败形态，编排层折 Stale）。 */
    private fun fetchFreshSnapshot(now: Long): MarketStatsSnapshot? = try {
        when (val parsed = MarketStatsParser.parse(fetcher.fetchReleasesJson())) {
            is MarketStatsParseResult.Ok -> MarketStatsSnapshot(fetchedAtMs = now, downloads = parsed.downloads)
            is MarketStatsParseResult.Invalid -> null
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: RuntimeException) {
        // 泛型兜底面（解析器/网络栈未预期形态）：统计失败不上抛，这是本编排层的降级契约。
        null
    }
}

/** 统计的纯查询与显示半边（UI 与话术共用；无数据显示「—」由话术层折，这里只给真值或 null）。 */
object MarketStatsUi {

    /**
     * 查某条索引条目的下载量：统计缓存里**精确命中 zipUrl** 给计数；缓存缺席 / 未命中
     * （新包还没进 Releases、自建端点 zip 不在官方仓）给 null ⇒ UI 折「—」，不编 0。
     */
    fun downloadsFor(state: MarketStatsState?, zipUrl: String): Long? = when (state) {
        is MarketStatsState.Ok -> state.snapshot.downloads[zipUrl]
        MarketStatsState.Absent, null -> null
    }
}
