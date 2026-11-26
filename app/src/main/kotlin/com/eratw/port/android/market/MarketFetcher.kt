package com.eratw.port.android.market

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicInteger

/** 拒绝点统一出口：取数层的所有 RejectException 只此一处 throw（detekt ThrowsCount 口径）。 */
private fun reject(code: String, problem: String, cause: Throwable? = null): Nothing =
    if (cause == null) throw MarketFetcher.RejectException(code, problem)
    else throw MarketFetcher.RejectException(code, problem, cause)

/**
 * 市场取数口（文本 + 容器下载，CONTRACT-MKM-W1 §2 mkm-pipe 判据 1）。
 *
 * 语义权威 = kojo-editor `packages/app/src-tauri/src/market.rs`：主机白名单
 * （`MARKET_HOST_ALLOWLIST` :51-62 + `host_in_allowlist` :157-167）、URL 闸
 * （`url_problem_in` :548-569：https、有主机、无 userinfo、无非默认端口）、
 * 重定向逐跳重核 + 终态 URL 重核 + 跳数 ≤5（:186-193、:572-580）、`Content-Length`
 * 预核（:199-205）、进度节流 ≥64KiB 或 ≥100ms（:216-233）、同目录临时文件 + rename
 * 原子写（:364-408）、逐块累计体积闸（:690-696）。白名单常量**只住这个文件**
 * （CONTRACT §0.4：平台侧单源，别处不许抄第二份）。
 *
 * 与 Rust 版的差异仅一处实现细节：下载主机组白名单在本仓多一族 `market.362573581.xyz`
 * （CONTRACT §0.3 的索引镜像端点 = 我们的下载源；Rust 把它归发布闸，我们只有下载闸）。
 *
 * 出网唯一用 `HttpURLConnection`（不引网络库）；重定向**手动跟跳**（JVM 的自动跟随
 * 不做逐跳重核，等于白名单没设）。线程口径：阻塞调用线程，不建线程池不引协程。
 *
 * 测试缝：构造参数 [openConnection] 让定向单测把任意 URL 的连接指到本地
 * `com.sun.net.httpserver.HttpServer`——闸照旧核真实 URL 字面（https + 白名单主机），
 * 线路层落到本地假栈；`fetchText`/`downloadToStaging` 为 open，服务层测试直接覆写。
 */
open class MarketFetcher(
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {

    /** 取文本结果（照 market.rs `MarketFetchTextResultDto` 四件套；索引不落盘）。 */
    class FetchTextResult(val text: String, val bytes: Long, val status: Int, val finalUrl: String)

    /** 下载结果（照 market.rs `MarketDownloadResultDto`）。 */
    class DownloadResult(val bytes: Long, val status: Int, val finalUrl: String)

    /** 取数拒绝（带 [MarketRejectCodes] 封闭码）。IOException 子类。 */
    class RejectException(val code: String, message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /** 主机白名单精确表（market.rs:51-62 四族 + CONTRACT §0.3/§0.4 的索引镜像主机一族）。 */
    private val allowlistHosts = listOf(
        HOST_GITHUB,
        HOST_RAW_GITHUB_USER_CONTENT,
        HOST_OBJECTS_GITHUB_USER_CONTENT,
        HOST_RELEASE_ASSETS_GITHUB_USER_CONTENT,
        HOST_MARKET_MIRROR,
    )

    /** 白名单里唯一的后缀通配：GitHub Pages 的 `*.github.io`（只准一级子域，market.rs:57-58）。 */
    private val githubPagesSuffix = SUFFIX_GITHUB_PAGES

    /**
     * 主机是否在白名单内（market.rs:157-167 同判定：小写、去尾点；`*.github.io`
     * 前缀必须是**单个非空 label**——`a.b.github.io` 与裸 `.github.io` 都不放行）。
     */
    fun hostInAllowlist(host: String): Boolean {
        val lowered = host.trimEnd('.').lowercase()
        return lowered in allowlistHosts ||
            (lowered.endsWith(githubPagesSuffix) && lowered.removeSuffix(githubPagesSuffix).let { label ->
                label.isNotEmpty() && !label.contains('.')
            })
    }

    /**
     * 一个 URL 能不能作为取数目标（market.rs:548-569 `url_problem_in` 同判定）。
     * 返回 null = 放行；否则给人话拒因。每次取数与每一跳重定向都过它。
     */
    fun urlProblem(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull()
        return when {
            uri == null -> "URL 解析失败：$url"
            uri.scheme != "https" -> "协议不是 https：$url"
            uri.host == null -> "URL 没有主机名：$url"
            !uri.userInfo.isNullOrEmpty() -> "URL 带 userinfo：$url"
            uri.port != -1 && uri.port != HTTPS_DEFAULT_PORT -> "URL 带非默认端口 ${uri.port}：$url"
            !hostInAllowlist(uri.host) -> "主机 ${uri.host} 不在平台放行列表（GitHub 系主机、*.github.io 一级子域、市场镜像）"
            else -> null
        }
    }

    /**
     * 该不该发这条进度（market.rs:225-233 `progress_due` 纯函数同判定）。
     * [deltaTimeMs] = null 表示一次都没发过（首块直接发）；字节段与时间段满足其一即发；
     * 新增为 0（收尾重复调用）不发。
     */
    fun progressDue(deltaBytes: Long, deltaTimeMs: Long?): Boolean =
        deltaBytes >= PROGRESS_MIN_DELTA_BYTES ||
            deltaTimeMs == null ||
            (deltaBytes > 0 && deltaTimeMs >= PROGRESS_MIN_INTERVAL_MS)

    /**
     * 取一段远程文本（索引）：不落盘，闸与下载同一份 [send]（market.rs:417-427 `fetch_text`）。
     * 响应体必须 UTF-8（market.rs:209-212），超限/非 2xx/白名单外一律 [RejectException]。
     */
    open fun fetchText(url: String, timeoutMs: Long, maxBytes: Long): FetchTextResult {
        val sent = send(url, timeoutMs, maxBytes, MarketRejectCodes.DOWNLOAD_FAILED)
        try {
            val body = readCapped(sent.conn.inputStream, maxBytes)
            return FetchTextResult(strictUtf8(body), body.size.toLong(), sent.status, sent.finalUrl)
        } finally {
            sent.conn.disconnect()
        }
    }

    /**
     * 按 [order] 顺序逐端点取文本，**首个成功即收口**（CONTRACT-MKM-W7：取数器只接受
     * 调用方算好的有序端点列表——健康度与端点序都不住本层，单一职责）。每个端点过
     * [fetchText] 同一套闸；失败（拒绝/超时/非 2xx）记原话换下一个；全败回
     * [FallbackTextResult.fetched] = null + 逐端点失败原话（顺序 = 调用序）。
     */
    fun fetchTextFromEndpoints(
        order: List<String>,
        timeoutMs: Long,
        maxBytes: Long,
    ): FallbackTextResult {
        val problems = ArrayList<String>()
        for (url in order) {
            val hit = runCatching { fetchText(url, timeoutMs, maxBytes) }.getOrElse { e ->
                problems += "$url：${e.message ?: e::class.simpleName ?: "无回音"}"
                null
            } ?: continue
            return FallbackTextResult(url, hit, problems)
        }
        return FallbackTextResult(null, null, problems)
    }

    /**
     * [fetchTextFromEndpoints] 的收口面：[fetched] = 首个取数成功端点的结果（全败 = null）；
     * [requestedUrl] = 那一跳的**请求端点**（候选序里的那个 URL；重定向终态在
     * [FetchTextResult.finalUrl]，健康度按候选端点记账、不按重定向落点）；[problems] =
     * 收口前每个失败端点的原话（顺序 = 调用序，与 Stale 收口的逐端点文案同源）。
     */
    class FallbackTextResult(
        val requestedUrl: String?,
        val fetched: FetchTextResult?,
        val problems: List<String>,
    )

    /**
     * 下载响应体到 `<request.stagingDir>/<stagingName>`：同目录临时文件 + 原子挪写
     * （market.rs:364-408 同形态，临时名带序号隔开并发同名互踩）；体积双闸走 [send]
     * 预核与逐块累计（超限中止并删掉半截）；进度按 [progressDue] 节流、在写入之后报。
     * 总数取终态响应的 `Content-Length`，缺失如实回 -1（不拿累计上限冒充总数）。
     * 注意：stagingName 由调用方从 [MarketContract.packContainerName] 派生（上游已过
     * 条目名合同），本层不重核命名——与 Rust `container_name_problem` 的判位不同源，
     * 那层闸在容器名自拼的平台，我们容器名出自契约派生函数。
     */
    open fun downloadToStaging(
        request: ContainerDownload,
        url: String,
        stagingName: String,
        onProgress: ((doneBytes: Long, totalBytes: Long) -> Unit)?,
    ): DownloadResult {
        request.stagingDir.mkdirs()
        val sent = send(url, request.timeoutMs, request.maxBytes, MarketRejectCodes.ZIP_TOO_BIG)
        val tmp = File(request.stagingDir, ".$stagingName.${tmpSeq.incrementAndGet()}.tmp")
        val target = File(request.stagingDir, stagingName)
        try {
            // 逐块落盘 + 逐块累计闸 + 节流进度（节流状态收在块级拷贝里）
            val received = sent.conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    copyCapped(input, out, request.maxBytes, sent.contentLength, onProgress)
                }
            }
            // 同目录临时文件 + 原子挪（Windows 的 File.renameTo 不覆盖已存在目标，统一走 nio）
            MarketAtomicMove.move(tmp, target)
            return DownloadResult(received, sent.status, sent.finalUrl)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } finally {
            sent.conn.disconnect()
        }
    }

    /** 下载落地参数（暂存根 + 体积上限 + 超时；合并成一个参数面省参数表）。 */
    class ContainerDownload(val stagingDir: File, val maxBytes: Long, val timeoutMs: Long)

    /** 块级拷贝：累计体积闸 + 写入后报进度（收尾那一次也过节流，照 market.rs:402-404）。 */
    private fun copyCapped(
        input: java.io.InputStream,
        out: FileOutputStream,
        maxBytes: Long,
        total: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): Long {
        val buf = ByteArray(BODY_BUF)
        var received = 0L
        var lastSentBytes = 0L
        var lastSentAtMs: Long? = null
        while (true) {
            val n = input.read(buf)
            if (n < 0) return received
            received += n
            if (received > maxBytes) {
                reject(
                    MarketRejectCodes.ZIP_TOO_BIG,
                    "响应体累计 $received 字节超过上限 $maxBytes，已中止（半截文件删除）",
                )
            }
            out.write(buf, 0, n)
            if (progressDue(received - lastSentBytes, lastSentAtMs?.let { System.currentTimeMillis() - it })) {
                onProgress?.invoke(received, total)
                lastSentBytes = received
                lastSentAtMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * **本类唯一一条出网路径**（market.rs:254-285 `guarded_send`）：URL 闸 → 连接
     * （手动跟跳、每跳重闸）→ 状态码 → 终态 URL 重核 → `Content-Length` 预核。
     * [capCode] = 体积预核超限时的封闭码（下载给 ZIP_TOO_BIG，取文本给 DOWNLOAD_FAILED）。
     * 终态 URL = 跳表里实际服务响应的那一跳（跟跳由本层手动做，连接上的 `url()` 与它同源）。
     */
    private fun send(url: String, timeoutMs: Long, maxBytes: Long, capCode: String): Sent {
        var current = url
        var hops = 0
        while (true) {
            urlProblem(current)?.let { reject(MarketRejectCodes.ENDPOINT_FORBIDDEN, it) }
            val conn = openConnection(URL(current))
            conn.connectTimeout = timeoutMs.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            conn.readTimeout = conn.connectTimeout
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val status = try {
                conn.responseCode
            } catch (e: IOException) {
                reject(MarketRejectCodes.DOWNLOAD_FAILED, "请求失败 $current（${e.message}）", e)
            }
            if (status in REDIRECT_STATUSES) {
                hops++
                if (hops > MarketContract.MAX_REDIRECTS) {
                    reject(
                        MarketRejectCodes.ENDPOINT_FORBIDDEN,
                        "重定向跳数超过上限 ${MarketContract.MAX_REDIRECTS}：$current",
                    )
                }
                val location = conn.getHeaderField("Location")
                    ?: reject(MarketRejectCodes.DOWNLOAD_FAILED, "重定向缺 Location：$current")
                val next = URL(URL(current), location)
                conn.disconnect()
                current = next.toString()
                continue
            }
            if (status < HTTP_OK_MIN || status > HTTP_OK_MAX) {
                conn.disconnect()
                reject(MarketRejectCodes.DOWNLOAD_FAILED, "HTTP 状态非 2xx：$status（$current）")
            }
            // 终态 URL 再核一次白名单（market.rs:275-278：策略闸管"跳"，这一闸管"落点"）。
            urlProblem(current)?.let {
                conn.disconnect()
                reject(MarketRejectCodes.ENDPOINT_FORBIDDEN, "最终 URL 拒绝：$it")
            }
            val len = conn.contentLengthLong
            if (len >= 0 && len > maxBytes) {
                conn.disconnect()
                reject(capCode, "Content-Length $len 超过上限 $maxBytes，已拒绝（一个字节都不收）")
            }
            return Sent(conn, status, current, len)
        }
    }

    /** 逐块累计读进内存（取文本专用；超限即拒，照 market.rs `stream_capped` :699）。 */
    private fun readCapped(input: InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BODY_BUF)
        var total = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                total += n
                if (total > maxBytes) {
                    reject(MarketRejectCodes.DOWNLOAD_FAILED, "响应体累计 $total 字节超过上限 $maxBytes")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** 严格 UTF-8 解码（"看起来像 JSON 的别的编码"不进本层，market.rs:209-212）。 */
    private fun strictUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        reject(MarketRejectCodes.DOWNLOAD_FAILED, "响应体不是 UTF-8 文本（${bytes.size} 字节）", e)
    }

    /** 发送完成后的响应面（连接 + 状态 + 终态 URL 字面 + 声明体积）。 */
    private class Sent(val conn: HttpURLConnection, val status: Int, val finalUrl: String, val contentLength: Long)

    private companion object {

        /** 白名单精确主机（market.rs:51-55 同字面 + 市场镜像）。 */
        private const val HOST_GITHUB = "github.com"
        private const val HOST_RAW_GITHUB_USER_CONTENT = "raw.githubusercontent.com"
        private const val HOST_OBJECTS_GITHUB_USER_CONTENT = "objects.githubusercontent.com"
        private const val HOST_RELEASE_ASSETS_GITHUB_USER_CONTENT = "release-assets.githubusercontent.com"
        private const val HOST_MARKET_MIRROR = "market.362573581.xyz"

        /** 后缀通配（market.rs:58）。 */
        private const val SUFFIX_GITHUB_PAGES = ".github.io"

        /** https 默认端口之外的端口一律拒（market.rs:559-561）。 */
        private const val HTTPS_DEFAULT_PORT = 443

        /** 请求 UA（market.rs:68 `MARKET_USER_AGENT` 同字面）。 */
        private const val USER_AGENT = "kojo-editor-market"

        /** 响应体读缓冲（64 KiB）。 */
        private const val BODY_BUF = 64 * 1024

        /** 进度节流的字节段阈值：累计新增 ≥64 KiB 才发一条（market.rs:218）。 */
        private const val PROGRESS_MIN_DELTA_BYTES = 64L * 1024L

        /** 进度节流的时间段阈值：距上次 ≥100ms 也发（market.rs:220）。 */
        private const val PROGRESS_MIN_INTERVAL_MS = 100L

        /** 2xx 成功段的上下界。 */
        private const val HTTP_OK_MIN = 200
        private const val HTTP_OK_MAX = 299

        /** 重定向状态集（301/302/303/307/308；303 换 GET 对我们无感——本来只发 GET）。 */
        private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
    }

    /** 临时文件序号（同进程内自增；照 market.rs tmp_unique_id 隔开并发同名互踩）。 */
    private val tmpSeq = AtomicInteger(0)
}
