package com.eratw.port.android

import android.content.Context
import android.util.Log
import com.eratw.port.core.diag.ErrorReportUpload
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 错误报告上报器（方案③「Serverless 上报端点」，2026-10-27）。
 *
 * 把方案①落盘的报告包**原样**（text/plain 全文）POST 到部署好的 Cloudflare Worker，
 * Worker 写 KV；部署 runbook 见 `tools/report-endpoint/worker.js` 头注。
 *
 * ## 启用条件（缺省零行为）
 *
 * 端点与令牌由出包参数注入（app/build.gradle.kts 的 resValue，缺省空串）：
 *
 * ```text
 * gradlew assembleRelease -PeratwReportEndpoint=https://... -PeratwReportToken=...
 * ```
 *
 * 不带这两个参数出包 = 本对象所有入口在第一行就返回，不发任何网络请求——
 * 未部署端点的日常开发与调试包保持完全离线。
 *
 * ## 触发时机（三路，全都 best-effort、绝不抛）
 *
 * 1. **失败路径后台发**：`GameHost.writeErrorReport` 落盘后拉 daemon 线程（VM 线程
 *    不等网络）；
 * 2. **崩溃同步发**：崩溃兜底处理器里在进程死前同步试一次（3 s 超时上限，
 *    见 [ErrorReportUpload.CRASH_TIMEOUT_MS]）；
 * 3. **冷启动补发**：Activity onCreate 拉 daemon 线程扫一遍待发——上一轮失败/断网的
 *    报告在这里自然重试（`.uploaded` 索引去重，成功不重发）。
 *
 * 三路**共用一道进程级单飞闸门**（[ReportUploadQueue.tryBegin]）：1 与 3 在同一次冷启动里
 * 会各起一条线程扫同一个目录，索引原先又只在整轮扫完后才落笔，于是同一份报告被 POST 两次
 * （2026-10-31 玩家上报里能看到成对的同名报告）。拿不到闸门的一方**立刻返回**、不排队 ——
 * 补发是 best-effort，下一轮冷启动还会再扫，重复发才是真不可接受的那个。
 *
 * ## 判定逻辑
 *
 * 待发名单/索引合并/端点与体积判定全部下沉 core（[ErrorReportUpload]，有定向单测）；
 * 本类只做 HTTP 管线与文件读写。
 */
object ErrorReportUploader {

    private const val TAG = "ErrorReport"

    /**
     * 崩溃兜底等补发线程的上限（毫秒）：单份 3s 超时（[ErrorReportUpload.CRASH_TIMEOUT_MS]）
     * 加一份余量 —— 再长就是在拖崩溃对话框。到点没发完的，下次冷启动 [uploadPendingAsync] 补。
     */
    private const val CRASH_SYNC_JOIN_MS = 7_000L

    /** 端点是否已配置且合法（未配置 = 整体关闭，第一行就返回）。 */
    fun endpointConfigured(context: Context): Boolean =
        ErrorReportUpload.isHttpsEndpoint(endpoint(context))

    private fun endpoint(context: Context): String =
        context.getString(R.string.eratw_report_endpoint)

    private fun token(context: Context): String =
        context.getString(R.string.eratw_report_token)

    /**
     * 异步补发（触发时机 1/3）：有端点才起线程，daemon、不阻塞调用方。
     * 在 VM 线程与崩溃兜底之外的任何地方都可以调。
     */
    fun uploadPendingAsync(context: Context) {
        if (!endpointConfigured(context)) return
        val appContext = context.applicationContext
        Thread({
            // 这条线程跑在冷启第一屏：任何未捕获异常都会在**玩家还没看到界面**时把进程
            // 带走（上报是纯附带动作，绝不该有这种权力）。`uploadPending` 内部只有闸门与
            // 目录扫描，但它调的 `endpoint(context)` / 磁盘遍历都不在我们的契约里，
            // 所以整段兜成「最多不报」，落一行日志即可。
            runCatching { uploadPending(appContext) }
                .onFailure { Log.w(TAG, "冷启动补发失败（不影响游戏）", it) }
        }, "eratw-report-upload").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 同步补发（触发时机 2，崩溃兜底专用）：调用线程上直接发，单份超时
     * [ErrorReportUpload.CRASH_TIMEOUT_MS]，进程死前给一次机会。
     *
     * **网络不在调用线程上做**：本函数跑在 `Thread.setDefaultUncaughtExceptionHandler` 里，
     * 而崩溃十有八九在主线程 —— Android 主线程禁网，直接 POST 得到
     * `NetworkOnMainThreadException`，一份也发不出去（2026-10-28 真机实测抓到）。
     * 所以起一条 daemon 线程去发、本线程 join：进程要等我们把控制权交回默认处理器之后才死，
     * 那条线程有机会跑完。join 有上限（[CRASH_SYNC_JOIN_MS]），到点就撒手 ——
     * 没发出去的下次冷启动由 [uploadPendingAsync] 补。
     *
     * @return 成功上报份数（-1 = 端点未配置 / 崩了但不必区分）
     */
    fun uploadPendingSyncForCrash(context: Context): Int {
        if (!endpointConfigured(context)) return -1
        val app = context.applicationContext
        var sent = -1
        val worker = Thread({
            sent = runCatching { uploadPending(app, crashMode = true) }.getOrElse {
                Log.w(TAG, "崩溃路上补发失败（不再重试，进程即将退出）", it)
                -1
            }
        }, "eratw-report-crash")
        worker.isDaemon = true
        worker.start()
        runCatching { worker.join(CRASH_SYNC_JOIN_MS) }
        if (worker.isAlive) Log.w(TAG, "崩溃补发未在 ${CRASH_SYNC_JOIN_MS}ms 内跑完，留给下次冷启动")
        return sent
    }

    /**
     * 扫报告目录补发全部待发报告；返回成功份数。任何异常只 Log。
     *
     * 闸门（[ReportUploadQueue.runGuarded]）拿不到就直接返回 0：另有一路正在扫同一个目录，
     * 再开一路就是「同一份报告发两次」的来源本身。目录与索引读写都在
     * [ReportUploadQueue.scanAndUpload]（纯 JVM，有定向单测）。
     */
    private fun uploadPending(context: Context, crashMode: Boolean = false): Int {
        val endpoint = endpoint(context)
        if (!ErrorReportUpload.isHttpsEndpoint(endpoint)) return 0
        var sent = 0
        val dir = ErrorReportCollector.reportDir(context)
        val began = ReportUploadQueue.runGuarded(lockFile = File(dir, ReportUploadQueue.LOCK_FILE_NAME)) {
            sent = ReportUploadQueue.scanAndUpload(
                dir = dir,
                indexFile = File(dir, ErrorReportUpload.INDEX_FILE_NAME),
                maxBodyBytes = ErrorReportUpload.MAX_BODY_BYTES.toLong(),
                crashMode = crashMode,
                log = { message, warn -> if (warn) Log.w(TAG, message) else Log.i(TAG, message) },
                send = { file ->
                    runCatching { uploadOne(endpoint, token(context), file, crashMode) }
                        .getOrElse {
                            Log.w(TAG, "报告上报失败：${file.name}（下次冷启动补发）", it)
                            false
                        }
                },
            )
        }
        if (!began) Log.i(TAG, "已有补发在跑，本次跳过（进程级单飞闸门）")
        return sent
    }

    /** 单份上传：POST 报告全文，2xx = 成功。超时按 crashMode 取档。 */
    private fun uploadOne(endpoint: String, token: String, file: File, crashMode: Boolean): Boolean {
        val timeout = if (crashMode) ErrorReportUpload.CRASH_TIMEOUT_MS else ErrorReportUpload.TIMEOUT_MS
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeout
            conn.readTimeout = timeout
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            conn.setRequestProperty(ErrorReportUpload.TOKEN_HEADER, token)
            conn.setRequestProperty(ErrorReportUpload.NAME_HEADER, file.name)
            conn.setFixedLengthStreamingMode(file.length().toInt())
            file.inputStream().use { input ->
                conn.outputStream.use { output -> input.copyTo(output) }
            }
            val code = conn.responseCode
            return code in 200..299
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * 补发的「单飞闸门 + 目录扫描 + 索引落笔」——纯 JVM，无 Android 依赖，`:app` 单测直接锁
 * （见 `ErrorReportUploadQueueTest`）。抽出来的理由与 [RunTrail] 同口径：这一段判的是
 * 「同一份报告到底发几次」，写错只能从端点那侧的重复条目发现，等玩家反馈太晚。
 *
 * ## 三个修复点（2026-10-31 两处 + 2026-11-04 跨进程一处，都冲着「成对同名报告」）
 *
 * 1. **单飞闸门**（[tryBegin] / [finish]）：一次冷启动里 `RunWatch.beginProcess` 与
 *    `MainActivity.onCreate` 各调一次 `uploadPendingAsync`，两条 daemon 线程同时扫同一目录，
 *    而索引要等整轮扫完才落笔 ⇒ 两边读到的都是空索引 ⇒ 同一份报告 POST 两次。
 *    闸门是 `AtomicBoolean` 的 compare-and-set：只有一路拿得到，另一路立刻返回。
 * 2. **索引逐份落笔**（[scanAndUpload] 每成功一份就写一次 `.uploaded`）：即便闸门被绕过
 *    （未来加了第二个入口），一份报告发成功后它的名字**当场**就在盘上了，另一轮扫描要么
 *    拿不到闸门、要么在索引里见到它。整轮结束才写一次的那种形态，等于把「发出去了」这件事
 *    只记在内存里 —— 中途进程被杀（崩溃补发的常态）就全部重发。
 * 3. **跨进程文件锁**（[LOCK_FILE_NAME]）：`AtomicBoolean` 只挡同进程的两条线程。启动失败
 *    自重启 / 崩溃后下一次冷启时，**旧进程**可能还在发同一份报告，新进程照样会在旧进程
 *    落索引之前再发一次 —— 2026-11-04 端点仍有成对条目（13:34:32 / 15:07:37 两份各两次，
 *    间隔约 2 秒）。[runGuarded] 再拿一把 OS 文件锁，拿不到就当场放弃。
 */
internal object ReportUploadQueue {

    /**
     * 跨进程单飞锁的文件名（reportDir 内）。
     *
     * 点前缀 + 非 `eratw-report-` 形状 ⇒ [scanAndUpload] 不会把它当报告；文件本身只当锁
     * 载体，不写内容。进程死亡时 OS 自动释放 [FileLock]，不会留下需要人工清理的僵尸锁。
     */
    internal const val LOCK_FILE_NAME = ".upload.lock"

    /** 进程级单飞闸门（CAS，不是普通布尔：两路同时抢时只有一路成功）。 */
    private val inFlight = AtomicBoolean(false)

    /** 拿到闸门返回 true；已被占用返回 false（调用方立刻放弃，不排队、不等待）。 */
    fun tryBegin(): Boolean = inFlight.compareAndSet(false, true)

    /** 放开闸门；必与 [tryBegin] 成对（[runGuarded] 已在 finally 里放开）。 */
    fun finish() {
        inFlight.set(false)
    }

    /**
     * 拿到进程闸门（以及给了 [lockFile] 时的跨进程文件锁）才跑 [body]，抛出也必定放开。
     *
     * 文件锁用 [RandomAccessFile] 的 channel（minSdk 21 可用；`FileChannel.open` 走
     * java.nio.file，API 26 才有）。同一 JVM 内重复加锁会抛
     * [OverlappingFileLockException]，与「另一进程持有」同判为拿不到。
     *
     * @param lockFile null = 只做进程内单飞（单测沿用）；线上传 `<reportDir>/.upload.lock`
     * @return true = 本次真的跑了；false = 已有别的路（线程或进程）在跑，本次跳过
     */
    fun runGuarded(lockFile: File? = null, body: () -> Unit): Boolean {
        if (!tryBegin()) return false
        var raf: RandomAccessFile? = null
        var lock: FileLock? = null
        try {
            if (lockFile != null) {
                val parent = lockFile.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
                val opened = runCatching { RandomAccessFile(lockFile, "rw") }.getOrNull()
                if (opened != null) {
                    raf = opened
                    var otherProcessHolds = false
                    val acquired = try {
                        opened.channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        otherProcessHolds = true
                        null
                    } catch (_: IOException) {
                        // 文件系统不支持锁（或瞬时 IO 错）：退回旧的「进程内单飞」，
                        // 不能因为锁不可用就把整条上报通道永久关掉。
                        null
                    }
                    if (acquired == null && otherProcessHolds) return false
                    lock = acquired
                }
            }
            body()
            return true
        } finally {
            val held = lock
            if (held != null) runCatching { held.release() }
            val opened = raf
            if (opened != null) runCatching { opened.close() }
            finish()
        }
    }

    /** 索引读成集合；读不出来按空集处理（顶多多发一次，不能因为索引坏掉就不发）。 */
    fun readIndex(indexFile: File): Set<String> = runCatching {
        if (indexFile.isFile) indexFile.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.toSet()
        else emptySet()
    }.getOrDefault(emptySet())

    /**
     * 把 [name] 记进已上报索引并**当场**落盘（滚动上限走 core 的 `mergeIndex`）。
     *
     * @return false = 写不成（目录建不出 / 磁盘满）；调用方只 Log，下次可能重发，可接受。
     */
    fun recordUploaded(indexFile: File, name: String, existing: Set<String>): Boolean = runCatching {
        val parent = indexFile.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
        indexFile.writeText(
            ErrorReportUpload.mergeIndex(existing, listOf(name)).joinToString("\n"),
            Charsets.UTF_8,
        )
        true
    }.getOrDefault(false)

    /**
     * 扫目录补发：返回成功份数（超限/空报告本地拒发不计入成功，按规则记进索引）。
     *
     * @param maxBodyBytes 单份体积闸；超限本地拒发并记入索引，免得每次补发白跑一趟
     * @param crashMode true = 崩溃路上：**最新一份优先**，第一份发不出去就撒手（进程要死了）。
     *   原先按名字升序（最旧先发），而崩溃当场刚落的报告恰好排在最后 —— 3 秒预算里它最可能
     *   发不出去。名字序在「同秒避让」的 `eratw-report-<epochMillis>.txt` 上也已不等于时间序，
     *   这里直接用 mtime 降序。
     * @param log 纯回调（第二参 = 是否按告警级处理），让单测能观察次序而不需要 Android `Log`
     * @param send 传输口（单份 → 是否成功）；单测给假实现，线上是 HTTP
     */
    fun scanAndUpload(
        dir: File,
        indexFile: File,
        maxBodyBytes: Long,
        crashMode: Boolean,
        log: (message: String, warn: Boolean) -> Unit = { _, _ -> },
        send: (File) -> Boolean,
    ): Int {
        if (!dir.isDirectory) return 0
        val scanStartedAt = System.currentTimeMillis()
        val diskNames = dir.listFiles { f -> f.isFile }?.map { it.name }.orEmpty()
        var uploaded = readIndex(indexFile)
        val pending = ErrorReportUpload.pendingNames(diskNames, uploaded)
        if (pending.isEmpty()) return 0
        val files = pending.map { File(dir, it) }
        // 非崩溃档沿用名字升序（最旧先发，快点把积压清掉）；崩溃档只赌一份，挑最新的。
        val order = if (crashMode) files.sortedByDescending { it.lastModified() } else files
        var ok = 0
        for (file in order) {
            val name = file.name
            val length = file.length()
            if (length == 0L) {
                // 空体 JSON 不会丢，但空报告 POST 出去只会在端点留一条空气条目，再被记成
                // 「已上报」永久跳过。刚写出的空文件可能是**正在写**的那份（报告与补发跨线程），
                // 所以只在冷却期外才认作坏件记档；冷却期内留给下一轮扫描。
                log("空报告跳过：$name", true)
                if (scanStartedAt - file.lastModified() >= EMPTY_GRACE_MS &&
                    record(indexFile, name, uploaded, log)
                ) uploaded += name
                continue
            }
            if (length > maxBodyBytes) {
                log("报告超限拒发：$name（$length B）", true)
                if (record(indexFile, name, uploaded, log)) uploaded += name
                continue
            }
            if (send(file)) {
                ok++
                log("报告已上报：$name", false)
                // 逐份落笔：这一行的存在就是「不重发」的全部依据。
                if (record(indexFile, name, uploaded, log)) uploaded += name
            } else if (crashMode) {
                break
            }
        }
        return ok
    }

    /**
     * 空报告从「可能是正在写」变为「确认坏件」的冷却期（毫秒）。
     *
     * **全仓「报错链路上的一节 10 秒」唯一真源**：[ErrorReportThrottle] 的钉发冷却
     * ([ErrorReportCollector.PIN_COOLDOWN_MS]) 直接引用它，两边共用一把尺（EP1-L6）。
     * 之所以必须同源而不是各写一个 10_000：这两条闸管的是同一段时间窗里的同一件事
     * ——「一份报错在 10 秒内不该再惊动盘与端点第二次」。各写一份迟早会漂成两个数，
     * 漂开的那一刻起，节流侧的钉发就会开始撞上补发侧的「正在写」窗口（要么白钉、
     * 要么把还在写的那份认成坏件永久跳过）。
     */
    internal const val EMPTY_GRACE_MS = 10_000L

    /** 索引落笔；写不成只按告警级报出来（下次可能重发，这是可接受的降级）。 */
    private fun record(indexFile: File, name: String, existing: Set<String>, log: (String, Boolean) -> Unit): Boolean {
        val wrote = recordUploaded(indexFile, name, existing)
        if (!wrote) log("已上报索引写回失败：$name（下次可能重发）", true)
        return wrote
    }
}
