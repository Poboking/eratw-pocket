package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReportUpload
import java.security.SecureRandom
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 匿名玩家数统计的 app 侧**纯判式与发送口**（零 Android API，定向单测直接钉）。
 *
 * ## 只数「有多少人来过」，不数「谁来了」
 *
 * 每个安装包在首次需要上报时生成一枚 32 字节 [SecureRandom] 随机编号（64 位小写 hex），
 * 之后**稳定复用**。服务端 `tools/report-endpoint/worker.js` 只把它当 `player/<id>` 的
 * 不可变去重键：键的条数就是累计玩家数，同一天再写 `active/<UTC 日>/<id>` 就是今日活跃。
 * 编号不是设备号、不含 Android ID / IMEI / 序列号 / 广告 ID，也不与存档、账号、路径有任何关系。
 * 出网的 body 只有一个 kind 与一个 id —— 连客户端时间、版本号、语言都不带（服务端自己取 UTC 日）。
 *
 * ## 与既有上报链同一套口径
 *
 * 1. **端点与令牌**：出包时由 `-PeratwReportEndpoint` / `-PeratwReportToken` 注入
 *    `R.string.eratw_report_endpoint` / `R.string.eratw_report_token`。没配 = [isEnabled] 直接
 *    短路，[flush] 一个字节都不出网（开发包 / CI 包零网络行为，与错误报告、更新漏斗同一条闸）；
 * 2. **https-only**：[isEnabled] 复用 core 的 [ErrorReportUpload.isHttpsEndpoint]，不另起判定；
 * 3. **传输口**：直接复用 [UpdateTelemetrySink.Transport] 与 [UpdateTelemetrySink.defaultTransport]，
 *    超时、[ErrorReportUpload.TOKEN_HEADER]、2xx 才算成功、finally disconnect 全部照旧，
 *    本文件不复制第二份 HttpURLConnection；
 * 4. **每天最多一次**：[shouldPing] 用 UTC 日做节流闸，成功才由接线层记下日期。服务端
 *    把登记写进 R2（与 KV 免费档的 1000 写/天 完全无关），但「一个活跃玩家每天一次心跳」
 *    仍是最省的形状：不因玩家反复冷启刷写、不产生重试风暴。失败不记日期，
 *    下次冷启再试（配服务端 503）。
 *
 * ## 为什么通道关着时 [flush] 返回 false
 *
 * 与 [UpdateTelemetrySink.flush] 不同，这里没有需要清掉的缓冲：false 只表示「这次没被服务端收下」，
 * 接线层因此**不写当日日期**，等下次冷启再判。若通道未配置也回 true，玩家装上带端点的
 * 新包后当天就再也不会登记，徽章会莫名少一个人。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
object PlayerStatsReporter {

    /** body 的 kind 标签；worker.js 用它把登记与错误报告、更新漏斗分流。 */
    const val KIND: String = "player-presence"

    /** 匿名编号的随机字节数：32 字节 = 64 位小写 hex，与服务端 PLAYER_ID_RE 同口径。 */
    const val ID_BYTES: Int = 32

    /** 只认 64 位小写十六进制；大写、短串、路径、任意自由文本一律拒（服务端也会 400）。 */
    private val ID_RE = Regex("[0-9a-f]{64}")

    private const val HEX = "0123456789abcdef"

    /** 端点是否已配置且合法（https-only、缺省关闭）；复用 core 判定，不另写一套。 */
    fun isEnabled(endpoint: String): Boolean = ErrorReportUpload.isHttpsEndpoint(endpoint)

    /** 编号形状闸：null / 大写 / 长度不对 / 带路径都不许出网。 */
    fun isValidId(id: String?): Boolean = id != null && ID_RE.matches(id)

    /** 唯一一次取随机数的地方（core 不碰随机数）；线上是 [SecureRandom]。 */
    internal fun secureBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    /** 字节转小写 hex，手写表：不碰 Locale，也不碰格式化器在部分语言下的数字形状。 */
    internal fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xff
            append(HEX[v ushr 4])
            append(HEX[v and 0x0f])
        }
    }

    /** 生成一枚新编号；随机源可注入，线上传 [secureBytes]。坏长度会产出非法 id，由 [isValidId] 挡住。 */
    fun freshId(random: (Int) -> ByteArray = ::secureBytes): String = toHex(random(ID_BYTES))

    /**
     * UTC 日 `YYYY-MM-DD`。服务端徽章的日界是 `new Date().toISOString().slice(0, 10)`，
     * 这里刻意不看系统时区：玩家的本地「今天」与服务端「今天」错位时，节流最多多/少发一次，
     * 但当日活跃的日界仍由服务端说了算，不会出现两套真相。
     */
    fun utcDay(nowMillis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US)
        cal.timeInMillis = nowMillis
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /**
     * 今天该不该发这一拍：玩家关了、端点没配、当天已经成功发过 —— 三个短路任一命中就不发。
     * 编号不在这一格判：首次上报时它还没生成，由接线层在闸后现取，[flush] 再验一次形状。
     */
    fun shouldPing(enabled: Boolean, endpoint: String, lastPingDay: String?, today: String): Boolean =
        enabled && isEnabled(endpoint) && lastPingDay != today

    /** body 手写 JSON、零依赖：id 已过 [isValidId]，无需转义也没有第二处内容可注入。 */
    fun encodeBody(id: String): String = "{\"kind\":\"$KIND\",\"id\":\"$id\"}"

    /**
     * 发一次心跳。
     *
     * @return true = 服务端 2xx 收下了（接线层可记当日日期）；
     *         false = 通道关 / 编号不合法 / 非 2xx / 传输抛错（下次冷启再试，不记日期）
     */
    fun flush(
        endpoint: String,
        token: String,
        id: String,
        transport: UpdateTelemetrySink.Transport,
    ): Boolean {
        if (!isEnabled(endpoint)) return false
        if (!isValidId(id)) return false
        return runCatching { transport.post(endpoint, token, encodeBody(id)) }.getOrDefault(false)
    }

    /** 线上默认传输口：与更新漏斗共用同一只 HttpURLConnection 实现，口径见类头第 3 条。 */
    fun defaultTransport(): UpdateTelemetrySink.Transport = UpdateTelemetrySink.defaultTransport()
}
