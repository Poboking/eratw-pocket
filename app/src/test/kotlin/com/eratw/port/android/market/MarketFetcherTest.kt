package com.eratw.port.android.market

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * 市场取数口的定向单测（CONTRACT §2.1 判据逐条）。
 *
 * 假栈选择：本地 [TinyHttpServer]（裸 `ServerSocket` 手写 HTTP/1.1 子集）。契约 §5 允许
 * `com.sun.net.httpserver` 或注 URLStreamHandler 二选一——本环境实测 `com.sun.*` 不在
 * app 单测编译面（android 单测按 android.jar 解析 java 类，`com.sun` 不在其中），
 * URLStreamHandler 又要 JVM 全局注册工厂、跨测试类污染，故取第三条更窄的路：
 * 只用 android.jar 里就有的 `java.net.ServerSocket` 现打一个每连接一响应的小服务器。
 *
 * 取数层测试缝：测试把「任意 URL 的连接」映射到 `http://127.0.0.1:<port><同一 path>`，
 * 闸照旧核真实 URL 字面（https + 白名单主机），线路层落到本地假栈。
 *
 * 反向证明锚：`redirect to non allowlisted host` 摘掉逐跳重核（只在首跳核闸）即转红；
 * `content length beyond cap` 系列摘掉 Content-Length 预核/逐块累计即转红。
 * 有序端点面（CONTRACT-MKM-W7）：`ordered fetch stops at first success and skips the rest`
 * 摘掉「首个成功即收口」即转红；`ordered fetch all dead reports every endpoint in call
 * order` 摘掉逐端点失败原话收集即转红。
 */
class MarketFetcherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: TinyHttpServer

    /** 1 MiB 进度测试体。 */
    private val bigBody = ByteArray(1024 * 1024) { (it % 251).toByte() }

    @Before
    fun startServer() {
        server = TinyHttpServer { path -> route(path) }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop()
    }

    /** 按 path 分发的本地假栈路由。 */
    private fun route(path: String): TinyHttpServer.Response = when (path) {
        "/text" -> TinyHttpServer.body(200, "hello index".toByteArray())
        "/alive-first", "/alive-second" -> TinyHttpServer.body(200, "hello index".toByteArray())
        "/big" -> TinyHttpServer.body(200, bigBody)
        "/over" -> TinyHttpServer.body(200, ByteArray(200))
        "/chunked-over" -> TinyHttpServer.body(200, ByteArray(200), chunked = true)
        "/notfound" -> TinyHttpServer.body(404, "nope".toByteArray())
        "/dead", "/dead-a", "/dead-b" -> TinyHttpServer.body(404, "nope".toByteArray())
        "/slow" -> run {
            Thread.sleep(2_000)
            TinyHttpServer.body(200, "late".toByteArray())
        }
        "/redir" -> TinyHttpServer.redirect("https://raw.githubusercontent.com/text")
        "/redir-evil" -> TinyHttpServer.redirect("https://evil.example.com/x")
        "/redir-loop" -> TinyHttpServer.redirect("https://github.com/redir-loop")
        else -> TinyHttpServer.body(404, "unknown path".toByteArray())
    }

    /** 白名单内 URL → 本地假栈（闸照核真实 URL 字面）。 */
    private fun fetcher(): MarketFetcher = MarketFetcher { url ->
        URL("http://127.0.0.1:${server.port}${url.path}").openConnection() as HttpURLConnection
    }

    private fun gateway(path: String) = "https://github.com$path"

    private fun rejects(code: String, block: () -> Unit): MarketFetcher.RejectException = try {
        block()
        fail("期望拒绝 $code，实际成功")
        error("unreachable")
    } catch (e: MarketFetcher.RejectException) {
        assertEquals(code, e.code)
        e
    }

    /* ---------------- 闸的纯函数面 ---------------- */

    @Test
    fun `allowlist hosts pass and suffix accepts one level only`() {
        val f = fetcher()
        assertTrue(f.hostInAllowlist("github.com"))
        assertTrue(f.hostInAllowlist("raw.githubusercontent.com"))
        assertTrue(f.hostInAllowlist("objects.githubusercontent.com"))
        assertTrue(f.hostInAllowlist("release-assets.githubusercontent.com"))
        assertTrue(f.hostInAllowlist("market.362573581.xyz"))
        assertTrue(f.hostInAllowlist("poboking.github.io"))
        assertTrue(f.hostInAllowlist("POBOKING.github.io."))
        assertTrue(f.urlProblem("https://poboking.github.io/kojo-market/index.json") == null)
        // 一级子域闸：a.b.github.io 与裸 github.io 都不放行
        assertFalse(f.hostInAllowlist("a.b.github.io"))
        assertFalse(f.hostInAllowlist(".github.io"))
        assertFalse(f.hostInAllowlist("github.io"))
        assertFalse(f.hostInAllowlist("evil.example.com"))
    }

    @Test
    fun `url gate rejects http userinfo port and garbage`() {
        val f = fetcher()
        assertTrue(f.urlProblem("http://github.com/x")?.contains("https") == true)
        assertTrue(f.urlProblem("https://user:pass@github.com/x")?.contains("userinfo") == true)
        assertTrue(f.urlProblem("https://github.com:8443/x")?.contains("8443") == true)
        assertTrue(f.urlProblem("https://evil.example.com/x")?.contains("evil.example.com") == true)
        assertTrue(f.urlProblem("not a url at all") != null)
    }

    @Test
    fun `progress due follows byte and time thresholds`() {
        val f = fetcher()
        // 首块直接发（一次都没发过）
        assertTrue(f.progressDue(1L, null))
        assertTrue(f.progressDue(0L, null))
        // 字节段：≥64KiB 即发
        assertTrue(f.progressDue(64L * 1024L, 0L))
        // 未到字节段且未到时间段：不发
        assertFalse(f.progressDue(1L, 50L))
        // 未到字节段但到了时间段（≥100ms）：发
        assertTrue(f.progressDue(1L, 100L))
        // 新增为 0（收尾重复）：不发
        assertFalse(f.progressDue(0L, 500L))
    }

    /* ---------------- 线路面 ---------------- */

    @Test
    fun `fetch text through the gate returns body and facts`() {
        val result = fetcher().fetchText(gateway("/text"), 15_000L, 512 * 1024L)
        assertEquals("hello index", result.text)
        assertEquals(200, result.status)
        assertEquals(11L, result.bytes)
        assertTrue(result.finalUrl.endsWith("/text"))
    }

    @Test
    fun `non allowlisted host rejected before any socket`() {
        rejects(MarketRejectCodes.ENDPOINT_FORBIDDEN) {
            fetcher().fetchText("https://evil.example.com/text", 15_000L, 512 * 1024L)
        }
    }

    @Test
    fun `plain http scheme rejected before any socket`() {
        rejects(MarketRejectCodes.ENDPOINT_FORBIDDEN) {
            fetcher().fetchText("http://github.com/text", 15_000L, 512 * 1024L)
        }
    }

    @Test
    fun `redirect is followed with per hop recheck and final url recheck`() {
        val result = fetcher().fetchText(gateway("/redir"), 15_000L, 512 * 1024L)
        assertEquals("hello index", result.text)
        assertEquals("https://raw.githubusercontent.com/text", result.finalUrl)
    }

    @Test
    fun `redirect to non allowlisted host rejected`() {
        rejects(MarketRejectCodes.ENDPOINT_FORBIDDEN) {
            fetcher().fetchText(gateway("/redir-evil"), 15_000L, 512 * 1024L)
        }
    }

    @Test
    fun `redirect beyond five hops rejected`() {
        rejects(MarketRejectCodes.ENDPOINT_FORBIDDEN) {
            fetcher().fetchText(gateway("/redir-loop"), 15_000L, 512 * 1024L)
        }
    }

    @Test
    fun `non 2xx status rejected`() {
        rejects(MarketRejectCodes.DOWNLOAD_FAILED) {
            fetcher().fetchText(gateway("/notfound"), 15_000L, 512 * 1024L)
        }
    }

    @Test
    fun `content length beyond cap rejected before reading a byte`() {
        rejects(MarketRejectCodes.DOWNLOAD_FAILED) {
            fetcher().fetchText(gateway("/over"), 15_000L, 100L)
        }
        // 下载路同一把尺，码归 ZIP_TOO_BIG
        val staging = tmp.newFolder("staging")
        val over = MarketFetcher.ContainerDownload(staging, maxBytes = 100L, timeoutMs = 15_000L)
        rejects(MarketRejectCodes.ZIP_TOO_BIG) {
            fetcher().downloadToStaging(over, gateway("/over"), "c.zip", null)
        }
        assertFalse(File(staging, "c.zip").exists())
    }

    @Test
    fun `cumulative body beyond cap rejected and half file removed`() {
        // 无 Content-Length 可预核 → 逐块累计闸在流中途拒（半截文件删干净）
        val staging = tmp.newFolder("staging")
        val over = MarketFetcher.ContainerDownload(staging, maxBytes = 100L, timeoutMs = 15_000L)
        rejects(MarketRejectCodes.ZIP_TOO_BIG) {
            fetcher().downloadToStaging(over, gateway("/chunked-over"), "c.zip", null)
        }
        assertFalse(File(staging, "c.zip").exists())
        assertTrue(staging.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `download lands atomically with progress reports`() {
        val staging = tmp.newFolder("staging")
        val reports = ArrayList<Pair<Long, Long>>()
        val request = MarketFetcher.ContainerDownload(staging, maxBytes = 4L * 1024 * 1024, timeoutMs = 60_000L)
        val result = fetcher().downloadToStaging(request, gateway("/big"), "c.zip") { done, total ->
            reports += done to total
        }
        assertEquals(bigBody.size.toLong(), result.bytes)
        assertEquals(bigBody.size.toLong(), File(staging, "c.zip").length())
        // 原子写：无临时文件残留
        val leftovers = staging.listFiles()?.filter { it.name.endsWith(".tmp") } ?: emptyList()
        assertTrue(leftovers.isEmpty())
        // 进度：至少两条、单调不减、total 恒 = Content-Length。
        // 终值不钉 == 总量：收尾那一次报告也过节流（market.rs:402-404 同口径），尾差
        // 不足 64KiB 且 <100ms 时最后一条停在倒数第二块——这是实现语义，不是丢进度。
        assertTrue(reports.size >= 2)
        assertTrue(reports.last().first <= bigBody.size.toLong())
        assertEquals(bigBody.size.toLong(), reports.last().second)
        assertTrue(reports.zipWithNext().all { (a, b) -> a.first <= b.first })
    }

    @Test
    fun `read timeout aborts early instead of hanging to the server schedule`() {
        val started = System.currentTimeMillis()
        rejects(MarketRejectCodes.DOWNLOAD_FAILED) {
            fetcher().fetchText(gateway("/slow"), 300L, 512 * 1024L)
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("应在超时点中止而非等满服务端 $elapsed ms", elapsed < 1_500L)
    }

    /* ---------------- 有序端点面（CONTRACT-MKM-W7：取数器只收调用方算好的序） ---------------- */

    @Test
    fun `ordered fetch stops at first success and skips the rest`() {
        val alive = gateway("/alive-first")
        val result = fetcher().fetchTextFromEndpoints(listOf(alive, gateway("/dead")), 15_000L, 512 * 1024L)

        assertEquals(alive, result.requestedUrl)
        assertEquals("hello index", result.fetched?.text)
        // 首个成功即收口：后面那个死端点没被试（试了必留失败原话，problems 不会是空表）
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `ordered fetch falls through failures and records problems in order`() {
        val deadA = gateway("/dead-a")
        val alive = gateway("/alive-second")
        val result = fetcher().fetchTextFromEndpoints(listOf(deadA, alive), 15_000L, 512 * 1024L)

        assertEquals(alive, result.requestedUrl)
        assertEquals("hello index", result.fetched?.text)
        assertEquals(1, result.problems.size)
        assertTrue("失败原话以请求端点起头：${result.problems[0]}", result.problems[0].startsWith("$deadA："))
    }

    @Test
    fun `ordered fetch all dead reports every endpoint in call order`() {
        val deadA = gateway("/dead-a")
        val deadB = gateway("/dead-b")
        val result = fetcher().fetchTextFromEndpoints(listOf(deadA, deadB), 15_000L, 512 * 1024L)

        assertNull(result.requestedUrl)
        assertNull(result.fetched)
        assertEquals(2, result.problems.size)
        assertTrue(result.problems[0].startsWith("$deadA："))
        assertTrue(result.problems[1].startsWith("$deadB："))
    }
}

/**
 * 测试专用 HTTP 假栈（裸 ServerSocket，只回 HTTP/1.1 最小子集）。
 * 每连接一响应 + `Connection: close`：无 Content-Length 的响应体以连接关闭为界
 * （HttpURLConnection 原生支持，等价 chunked 的「长度未知」形态）。
 * 只活在测试源集；请求只处理 GET（本波取数层只发 GET）。
 */
internal class TinyHttpServer(private val router: (String) -> Response) {

    /** 一条响应：状态码 + 附加头 + 体。 */
    class Response(val status: Int, val headers: Map<String, String> = emptyMap(), val body: ByteArray = ByteArray(0)) {
        /** true = 不带 Content-Length（体以连接关闭为界）。 */
        val chunked: Boolean = headers.containsKey(CHUNKED_MARK)
    }

    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = true

    val port: Int
        get() = serverSocket?.localPort ?: error("server not started")

    fun start() {
        val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        worker = Thread { serve(socket) }.apply {
            isDaemon = true
            name = "tiny-http-${socket.localPort}"
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        worker = null
    }

    private fun serve(socket: ServerSocket) {
        while (running) {
            val client = acceptQuietly(socket) ?: return
            handle(client)
        }
    }

    /** 最近一次被吞掉的连接级异常（取证位：单连接失败不杀服务器，测试断言自己会看见）。 */
    @Volatile
    private var lastFailure: Throwable? = null

    /** accept 失败（stop() 关闸属正常退出）记进取证位并收工。 */
    private fun acceptQuietly(socket: ServerSocket): Socket? = try {
        socket.accept()
    } catch (e: Exception) {
        lastFailure = e
        null
    }

    private fun handle(client: Socket) {
        val failure = runCatching { respondOn(client) }.exceptionOrNull()
        if (failure != null) lastFailure = failure
    }

    /** 单连接：读请求 → 路由 → 写响应（一响应即断，`Connection: close`）。 */
    private fun respondOn(client: Socket) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            skipHeaders(reader)
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val response = router(path)
            val out = sock.getOutputStream()
            out.write(headOf(response).toByteArray(Charsets.ISO_8859_1))
            if (response.body.isNotEmpty()) out.write(response.body)
            out.flush()
        }
    }

    /** 读完请求头（到空行/流尾为止）。 */
    private fun skipHeaders(reader: BufferedReader) {
        var line = reader.readLine()
        while (!line.isNullOrEmpty()) line = reader.readLine()
    }

    /** 响应头串（无 Content-Length 的形态以连接关闭为界）。 */
    private fun headOf(response: Response): String = buildString {
        append("HTTP/1.1 ${response.status} ${reasonOf(response.status)}\r\n")
        response.headers.forEach { (name, value) -> append("$name: $value\r\n") }
        if (!response.chunked && response.body.isNotEmpty()) {
            append("Content-Length: ${response.body.size}\r\n")
        }
        append("Connection: close\r\n\r\n")
    }

    private fun reasonOf(status: Int): String = when (status) {
        200 -> "OK"
        302 -> "Found"
        404 -> "Not Found"
        else -> "Status"
    }

    companion object {
        /** 响应头里「长度未知」的标记键（不会真发出去）。 */
        private const val CHUNKED_MARK = "x-tiny-chunked"

        fun body(status: Int, body: ByteArray, chunked: Boolean = false): Response =
            Response(status, if (chunked) mapOf(CHUNKED_MARK to "1") else emptyMap(), body)

        fun redirect(location: String): Response = Response(302, mapOf("Location" to location))
    }
}
