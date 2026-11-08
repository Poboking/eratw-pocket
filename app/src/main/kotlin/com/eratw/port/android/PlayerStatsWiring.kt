package com.eratw.port.android

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.Log

/**
 * 匿名玩家心跳的**接线层**：把 [PlayerStatsReporter] 的纯判式接到偏好、端点与后台线程上。
 *
 * ## 三条纪律
 *
 * 1. **一次冷启 = 最多一次心跳，且绝不在主线程**：[reportAsync] 起一条 daemon 线程，
 *    [reportOnce] 另有一道主线程闸。读 SharedPreferences 会命中磁盘（首读 XML），
 *    生成编号要取随机数，POST 要走网络 —— 三样都不许落在玩家点开游戏的那条线程上。
 *    调用点在 `GameHost.ensureAssetsInstalled` 的装载前那一拍（与漏斗冲刷同位置），
 *    但这里**仍然自己起线程**：那一拍的返回值是「游戏能不能开始装载」，
 *    同步等一趟 HTTP 就是把首屏交给端点的快慢。
 * 2. **关闸 = 一个字节不出网，也不生成编号**：玩家在设置页关掉统计、或出包没配端点时，
 *    [pingOnce] 在 [PlayerStatsReporter.shouldPing] 第一行短路，连 `player_stats_id` 都不写。
 *    端点判定与错误报告、漏斗遥测共用同一个 `R.string.eratw_report_endpoint`。
 * 3. **失败留到下次冷启**：[PlayerStatsReporter.flush] 只有服务端 2xx 才返回 true，
 *     true 才把 UTC 日写进偏好。R2 写入不可用时服务端回 503，客户端不即时重试、
 *    不记日期，等下一次冷启再试 —— 既不刷爆额度，也不把「没数上」误记成「已数过」。
 *    统计失败只落一行 logcat，绝不外逃异常、绝不碰更新面与装载返回值。
 *
 * ## 为什么复用 `UiPrefs` 的那份 SharedPreferences
 *
 * 设置页在主线程读开关，新开一份 XML 就会多一次没登记进 `StrictModeGuard` 豁免表的
 * UI 线程冷读。复用 `eratw_ui` 那份（[UiPrefs.playerStatsPrefs]）让豁免窗口与既有
 * 「首选项首读」完全是同一发，不多开一个口子。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
object PlayerStatsWiring {

    private const val TAG = "EratwPlayerStats"

    /** 时钟与主线程探针可注入：JVM 单测直测 [pingOnce]，不碰 Android 运行时。 */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    internal var mainThreadProbe: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }

    /**
     * 冷启动那一拍的入口：起 daemon 线程发一次心跳，调用线程一次网络、一次磁盘 IO 都不等。
     * 通道关 / 端点未配置时这一发起来也只是白跑一趟判断，出站字节恒为零。
     */
    fun reportAsync(context: Context) {
        val app = runCatching { context.applicationContext }.getOrNull() ?: return
        Thread({
            runCatching { reportOnce(app) }
                .onFailure { Log.w(TAG, "匿名玩家心跳没跑起来（不影响游戏与更新）", it) }
        }, "eratw-player-stats").apply { isDaemon = true }.start()
    }

    /** Context 胶水：主线程不许跑；读端点、令牌与偏好，剩下的全交给 [pingOnce]。 */
    private fun reportOnce(context: Context) {
        if (runCatching { mainThreadProbe() }.getOrDefault(true)) return
        val app = context.applicationContext ?: context
        val endpoint = runCatching { app.getString(R.string.eratw_report_endpoint) }.getOrDefault("")
        val token = runCatching { app.getString(R.string.eratw_report_token) }.getOrDefault("")
        val sp = runCatching { UiPrefs.playerStatsPrefs(app) }.getOrNull() ?: return
        pingOnce(sp, endpoint, token, PlayerStatsReporter.defaultTransport(), clock())
    }

    /**
     * 纯 JVM 可注入的一次心跳：以 [SharedPreferences] 为状态，[transport] 为出网口。
     *
     * 顺序即纪律：先过闸 → 再取（或生成）编号 → 再发 → 只有 2xx 才记当日日期。
     * 关闸时连 [UiPrefs.readPlayerStatsId] 都不必调，编号只在真要出网时才存在。
     *
     * @return true = 这一拍发出去且被收下；false = 被闸挡住或没成功（下次冷启再试）
     */
    internal fun pingOnce(
        sp: SharedPreferences,
        endpoint: String,
        token: String,
        transport: UpdateTelemetrySink.Transport,
        nowMillis: Long,
        random: (Int) -> ByteArray = PlayerStatsReporter::secureBytes,
    ): Boolean {
        val today = PlayerStatsReporter.utcDay(nowMillis)
        if (!PlayerStatsReporter.shouldPing(
                enabled = UiPrefs.readPlayerStatsEnabled(sp),
                endpoint = endpoint,
                lastPingDay = UiPrefs.readPlayerStatsLastDay(sp),
                today = today,
            )
        ) return false

        val id: String
        val stored = UiPrefs.readPlayerStatsId(sp)
        if (stored != null && PlayerStatsReporter.isValidId(stored)) {
            id = stored
        } else {
            val fresh = PlayerStatsReporter.freshId(random)
            if (!PlayerStatsReporter.isValidId(fresh)) return false
            UiPrefs.writePlayerStatsId(sp, fresh)
            id = fresh
        }
        // flush 内部按同一枚 id 手写 body；这里不再重复编码，也不做无意义的二次比对。
        if (!PlayerStatsReporter.flush(endpoint, token, id, transport)) return false
        UiPrefs.writePlayerStatsLastDay(sp, today)
        return true
    }
}
