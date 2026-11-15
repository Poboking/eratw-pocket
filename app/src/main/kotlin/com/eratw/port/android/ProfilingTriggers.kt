package com.eratw.port.android

import android.content.Context
import android.os.Build
import android.os.ProfilingManager
import android.os.ProfilingResult
import android.os.ProfilingTrigger
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.function.Consumer

/**
 * Android 17（API 37）的**自动堆转储触发器**（OOM-C 波，2026-11-09）。
 *
 * ## 它补的是哪条欠账
 *
 * Android 17 起 MemoryLimiter 按进程总量杀应用，死法是**无栈终止**：没有 Java 异常、
 * 没有崩溃栈，玩家只看到「玩着玩着回桌面」。项目已接 `ApplicationExitInfo` 的
 * `MemoryLimiter:AnonSwap` 识别（OOM-A 波），但那只能说「死于内存」，说不出「谁占着」。
 * 官方在 Android 17 给 `ProfilingManager` 加了两个事件驱动触发器：
 * - `TRIGGER_TYPE_OOM`：`OutOfMemoryError` 发生的瞬间自动采 Java 堆转储；
 * - `TRIGGER_TYPE_ANOMALY`：突破 OS 内存阈值时、**系统终止应用之前**给一份堆转储。
 * 两者都在应用下次启动并注册 `registerForAllProfilingResults` 回调时投递上次的结果。
 *
 * ## 为什么用反射取常量，而不是写死数值
 *
 * 本仓 `compileSdk = 36`。本机用 `javap` 实测 `android-36/android.jar`：
 * `ProfilingManager` / `ProfilingTrigger.Builder(int)` / `ProfilingResult` 都在 SDK 36 stub 内，
 * 但 `ProfilingTrigger` 只有 `TRIGGER_TYPE_ANR` / `TRIGGER_TYPE_APP_FULLY_DRAWN` /
 * `TRIGGER_TYPE_NONE` —— 上述两个常量是 **API 37** 才加入的。写死整数等于把未经核对的
 * 平台内部值当契约（换一个平台版本就可能注册到一个完全不同的触发器）。所以：
 * `SDK_INT >= 37` 守卫 + 运行时 `Class.forName` 读公开静态字段取值，取不到任一即整条功能
 * 关闭；Builder 与注册 API 仍用 SDK 36 的编译期类型，不反射调用方法。
 *
 * ## 边界
 *
 * - 全线 `runCatching`：无服务 / 低版本 / ROM 阉割 / SecurityException 一律静默关闭，
 *   **绝不因取证拖垮启动**（同 `EratwApplication.onCreate` 里其余装配的纪律）。
 * - 只把结果的元数据（错误码、触发类型、文件路径、大小）落 `filesDir` 台账并进错误报告，
 *   **不上传堆转储本体**：端点与出包归主人节奏，本波不碰。真机取证时按台账里的路径
 *   `adb pull` 即可，或将来由上报链决定要不要带。
 * - 台账是错误报告正文的取证面，不是玩家可见 UI 文案（AGENTS §6 例外），不读
 *   SharedPreferences、不自判语言。
 *
 * 官方出处：developer.android.com「Android 17 features and APIs」的 New ProfilingManager
 * triggers 节；「Trigger-based profiling」文档的 API level 表（OOM / ANOMALY = API 37，
 * `registerForAllProfilingResults` = API 36）。
 */
internal object ProfilingTriggers {

    /** 两个新触发器的起始 API；compileSdk 36 没有对应的 VERSION_CODES 常量，故写字面量。 */
    const val MIN_SDK_INT: Int = 37

    const val OOM_FIELD: String = "TRIGGER_TYPE_OOM"
    const val ANOMALY_FIELD: String = "TRIGGER_TYPE_ANOMALY"

    private const val TAG: String = "EratwProfiling"
    private const val DIR_NAME: String = "eratw-profiling"
    private const val LEDGER_NAME: String = "ledger.jsonl"
    private const val MAX_LEDGER_BYTES: Long = 64L * 1024L
    private const val RATE_LIMIT_HOURS: Int = 24
    private const val REPORT_LINE_MAX: Int = 320

    /**
     * 解析本次启动该注册哪两个触发器；返回 null = 整条功能关闭。
     *
     * 纯 JVM 判式（单测注入 [constantOf] 假体，不碰 Android 运行时）：低版本、缺任一常量、
     * 常量取值抛掷，三种形状都必须关掉，而不是退化成「只注册一个」或猜一个号。
     */
    fun triggerTypes(sdkInt: Int, constantOf: (String) -> Int?): IntArray? {
        if (sdkInt < MIN_SDK_INT) return null
        val oom = runCatching { constantOf(OOM_FIELD) }.getOrNull() ?: return null
        val anomaly = runCatching { constantOf(ANOMALY_FIELD) }.getOrNull() ?: return null
        return intArrayOf(oom, anomaly)
    }

    /** 运行时从平台类里取公开静态 int 字段；类或字段不存在 ⇒ null。 */
    private fun runtimeConstant(name: String): Int? = runCatching {
        Class.forName("android.os.ProfilingTrigger").getField(name).getInt(null)
    }.getOrNull()

    /**
     * 装配期调一次（[EratwApplication.onCreate]）。官方要求每次启动都注册：上次崩溃留下的
     * OOM 配置就是在本次注册回调时投递的。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val types = triggerTypes(Build.VERSION.SDK_INT, ::runtimeConstant) ?: return
        runCatching {
            val manager = app.getSystemService(ProfilingManager::class.java) ?: return
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "eratw-profiling").apply { isDaemon = true }
            }
            manager.registerForAllProfilingResults(
                executor,
                Consumer<ProfilingResult> { result -> record(app, result) },
            )
            manager.addProfilingTriggers(
                types.map { type ->
                    // 堆转储动辄几十 MiB，系统自带限流之外再压一道 24 小时的冷却，
                    // 免得异常反复触发把 cacheDir 写满（那会反过来制造新的 OOM）。
                    ProfilingTrigger.Builder(type)
                        .setRateLimitingPeriodHours(RATE_LIMIT_HOURS)
                        .build()
                },
            )
            Log.i(TAG, "自动堆转储触发器已注册：OOM / ANOMALY（API ${Build.VERSION.SDK_INT}）")
        }.onFailure { Log.w(TAG, "自动堆转储触发器装配失败（游戏照常起）", it) }
    }

    /** 把一次投递的元数据追加进有界台账；任何失败都只丢这一条读数。 */
    private fun record(context: Context, result: ProfilingResult) {
        runCatching {
            val path = runCatching { result.resultFilePath }.getOrNull()
            val bytes = path?.let { runCatching { File(it).length() }.getOrDefault(-1L) } ?: -1L
            val entry = ledgerEntry(
                nowMs = System.currentTimeMillis(),
                errorCode = runCatching { result.errorCode }.getOrDefault(-1),
                triggerType = runCatching { result.triggerType }.getOrDefault(-1),
                path = path,
                bytes = bytes,
                errorMessage = runCatching { result.errorMessage }.getOrNull(),
            )
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val ledger = File(dir, LEDGER_NAME)
            if (ledger.isFile && ledger.length() > MAX_LEDGER_BYTES) {
                runCatching { ledger.copyTo(File(dir, "$LEDGER_NAME.1"), overwrite = true) }
                runCatching { ledger.writeText(entry + "\n", Charsets.UTF_8) }
            } else {
                runCatching { ledger.appendText(entry + "\n", Charsets.UTF_8) }
            }
            Log.i(TAG, "自动堆转储投递：$entry")
        }
    }

    /**
     * 台账一行（单行 JSON，字段顺序固定，便于纯 JVM 单测逐字对拍）。
     *
     * 路径与系统错误信息都来自平台，可能含引号与反斜杠 ⇒ 逐字符转义，不许拼出畸形行。
     */
    internal fun ledgerEntry(
        nowMs: Long,
        errorCode: Int,
        triggerType: Int,
        path: String?,
        bytes: Long,
        errorMessage: String?,
    ): String = buildString {
        append("{\"t\":").append(nowMs)
        append(",\"code\":").append(errorCode)
        append(",\"trigger\":").append(triggerType)
        append(",\"path\":\"").append(jsonEscape(path)).append('"')
        append(",\"bytes\":").append(bytes)
        append(",\"error\":\"").append(jsonEscape(errorMessage)).append("\"}")
    }

    private fun jsonEscape(raw: String?): String {
        if (raw == null) return ""
        return buildString {
            for (ch in raw) when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
    }

    /**
     * 错误报告正文的一行：台账末行；没有台账返回 null（调用方按缺省行处理，不写「未知」）。
     * 只读文件，不碰 Android API，报告生成线程上可直接调。
     */
    internal fun ledgerLine(filesDir: File): String? {
        val ledger = File(File(filesDir, DIR_NAME), LEDGER_NAME)
        if (!ledger.isFile) return null
        val last = runCatching { ledger.useLines { it.lastOrNull() } }.getOrNull() ?: return null
        val text = "自动堆转储台账末行：$last"
        return if (text.length <= REPORT_LINE_MAX) text else text.take(REPORT_LINE_MAX) + "…（超长截断）"
    }
}
