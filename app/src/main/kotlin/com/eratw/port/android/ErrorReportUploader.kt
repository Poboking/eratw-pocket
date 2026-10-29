package com.eratw.port.android

import android.content.Context
import android.util.Log
import com.eratw.port.core.diag.ErrorReportUpload
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
            uploadPending(appContext)
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
     */
    private fun uploadPending(context: Context, crashMode: Boolean = false): Int {
        val endpoint = endpoint(context)
        if (!ErrorReportUpload.isHttpsEndpoint(endpoint)) return 0
        val dir = ErrorReportCollector.reportDir(context)
        if (!dir.isDirectory) return 0
        val indexFile = File(dir, ErrorReportUpload.INDEX_FILE_NAME)
        val uploaded = runCatching {
            if (indexFile.isFile) indexFile.readLines().filter { it.isNotBlank() }.toSet()
            else emptySet()
        }.getOrElse {
            Log.w(TAG, "已上报索引读不出来，按空索引处理（可能重发，可接受）", it)
            emptySet()
        }
        val diskNames = dir.listFiles { f -> f.isFile }?.map { it.name }.orEmpty()
        val pending = ErrorReportUpload.pendingNames(diskNames, uploaded)
        if (pending.isEmpty()) return 0
        var ok = 0
        val succeeded = ArrayList<String>(pending.size)
        for (name in pending) {
            val file = File(dir, name)
            // 体积闸：超限本地拒发并记入索引（不然每次补发都白跑一趟）。
            if (file.length() > ErrorReportUpload.MAX_BODY_BYTES) {
                Log.w(TAG, "报告超限拒发：$name（${file.length()} B）")
                succeeded += name
                continue
            }
            val sent = runCatching {
                uploadOne(endpoint, token(context), file, crashMode)
            }.getOrElse {
                Log.w(TAG, "报告上报失败：$name（下次冷启动补发）", it)
                false
            }
            if (sent) {
                ok++
                succeeded += name
                Log.i(TAG, "报告已上报：$name")
            } else if (crashMode) {
                // 崩溃路上第一份没发出去就别再耗时间，进程要死了。
                break
            }
        }
        if (succeeded.isNotEmpty()) {
            runCatching {
                indexFile.writeText(
                    ErrorReportUpload.mergeIndex(uploaded, succeeded).joinToString("\n"),
                    Charsets.UTF_8,
                )
            }.onFailure { Log.w(TAG, "已上报索引写回失败（下次可能重发）", it) }
        }
        return ok
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
