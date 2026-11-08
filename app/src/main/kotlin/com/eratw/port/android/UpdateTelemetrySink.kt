package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReportUpload
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新漏斗遥测的 app 侧发送基座（P1-4，本波只落基座、不接线）。
 *
 * [com.eratw.port.core.update.UpdateTelemetry] 把事件攒成一批，在冲刷点交出一个
 * `List<String>`（每行是 core 手写编码好的匿名事件 JSON 对象）。本对象负责把这一批
 * 包成带 `kind: "update-funnel"` 的 body，POST 到报错上报的**同一个端点**，让
 * `tools/report-endpoint/worker.js` 与错误报告分流后按「事件名 + UTC 日」聚合计数。
 *
 * ## 为什么是纯 JVM 形状（对齐 UpdateDownloadPlan 的分层）
 *
 * 本文件**零 Android API 依赖**：端点、令牌、时钟、传输全部由调用方注入。凡「判错了
 * 就可能把玩家数据发出去」的决定都收在 [flush] / [isEnabled] / [encodeBody] 这些
 * 纯函数里，`testDebugUnitTest` 直接钉死；读 resValue、起线程、拿 Context 这类胶水
 * 属于下一波 U6 接线（见交付的接线清单），本波一律不做 —— 契约 §1 禁接线
 * UpdateUi / UpdateManager / UpdateDownloadService / Settings。
 *
 * ## 纪律（照抄 ErrorReportUploader 的类头与实现）
 *
 * 1. **通道未配置 = 一个字节不出网**：[flush] 与 [sendCallback] 产出的回调都在
 *    第一行用 [isEnabled] 短路；端点为空串、空白或明文 http 时直接返回，[Transport]
 *    一次都不会被调用。端点与令牌由出包参数注入 `R.string.eratw_report_endpoint` /
 *    `R.string.eratw_report_token`（app/build.gradle.kts 的 resValue，缺省空串），
 *    不带参数出包 = 整条漏斗通道静默关闭，与错误上报链同一套口径；
 * 2. **https-only**：[isEnabled] 直接复用 core 的 [ErrorReportUpload.isHttpsEndpoint]，
 *    明文 http 与带空白的畸形端点一律拒，不另起一套判定；
 * 3. **超时 / 重试口径**：默认 [HttpTransport] 照抄 `ErrorReportUploader.uploadOne`
 *    —— `connectTimeout` / `readTimeout` 取 [ErrorReportUpload.TIMEOUT_MS]、令牌走
 *    [ErrorReportUpload.TOKEN_HEADER]、2xx 才算成功、finally 里 disconnect。重试**不在
 *    本类**：[flush] 返回 false 时 core 的 `UpdateTelemetry` 会整批保留并持久化，
 *    下次写入或 flushNow 再试（与错误报告「漏发可容忍、重发无妨」同口径）。
 *
 * ## 短路时返回 true 的理由
 *
 * 通道未配置是**编译期决定的持久状态**：重开通道要重新出包，旧缓冲留着既不会被补发、
 * 又会一路堆到撑爆存储。所以 [flush] 在「通道关 / 空批」时返回 true（= 已消费），
 * 让 core 清掉缓冲；只有真正经 [Transport] 发出去且非 2xx 才返回 false 保留重试。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
object UpdateTelemetrySink {

    /** body 的 kind 标签；worker.js 用它把漏斗事件与纯文本错误报告分流。 */
    const val KIND = "update-funnel"

    /**
     * 可注入的传输口：把整批 body POST 出去，返回是否成功（2xx）。
     * 单测塞假实现观察「发没发、发到哪、发了什么」；线上是 [HttpTransport]。
     */
    fun interface Transport {
        fun post(endpoint: String, token: String, body: String): Boolean
    }

    /** 端点是否已配置且合法（https-only、缺省关闭）；复用 core 判定，不另写一套。 */
    fun isEnabled(endpoint: String): Boolean = ErrorReportUpload.isHttpsEndpoint(endpoint)

    /**
     * 把 core 编码好的事件行包成一次 POST 的 body，手写 JSON、零依赖：
     * `{"kind":"update-funnel","events":[行1,行2,...]}`。
     *
     * [lines] 来自 `UpdateTelemetry.encode`，每行已是一个合法的单行 JSON 对象（字段与
     * 转义都由 core 把过关），这里只做数组装配，不再解析、不重新编码、不加工内容 ——
     * 事件全程零设备标识，聚合侧只数「事件名 + UTC 日」。
     */
    fun encodeBody(lines: List<String>): String = buildString {
        append("{\"kind\":\"").append(KIND).append("\",\"events\":[")
        lines.forEachIndexed { index, line ->
            if (index > 0) append(',')
            append(line)
        }
        append("]}")
    }

    /**
     * 冲刷一批事件：通道未配置时**第一行就返回**，一个字节不出网。
     *
     * @return true = 已消费（通道关 / 空批 / 发送成功，core 可清缓冲）；
     *         false = 发送失败（非 2xx / 异常），整批留给 core 下次再试
     */
    fun flush(endpoint: String, token: String, lines: List<String>, transport: Transport): Boolean {
        if (!isEnabled(endpoint)) return true
        if (lines.isEmpty()) return true
        return transport.post(endpoint, token, encodeBody(lines))
    }

    /** 线上默认传输口：java.net 的 HttpURLConnection，口径见类头第 3 条。 */
    fun defaultTransport(): Transport = HttpTransport()

    /**
     * 产出 `UpdateTelemetry` 构造所需的 `send: (List<String>) -> Boolean` 回调。
     * U6 接线时从 resValue 读出端点与令牌传进来即可；通道未配置时该回调恒返回 true
     * 且从不触碰 [Transport]（零出网）。签名见交付的接线清单。
     */
    fun sendCallback(
        endpoint: String,
        token: String,
        transport: Transport = defaultTransport(),
    ): (List<String>) -> Boolean = { lines -> flush(endpoint, token, lines, transport) }

    /** 默认 HTTP 实现：逐项对齐 `ErrorReportUploader.uploadOne`，唯 Content-Type 改 JSON。 */
    private class HttpTransport : Transport {
        override fun post(endpoint: String, token: String, body: String): Boolean {
            val conn = URL(endpoint).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = ErrorReportUpload.TIMEOUT_MS
                conn.readTimeout = ErrorReportUpload.TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty(ErrorReportUpload.TOKEN_HEADER, token)
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
                return conn.responseCode in 200..299
            } finally {
                conn.disconnect()
            }
        }
    }
}
