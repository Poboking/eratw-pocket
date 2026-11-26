package com.eratw.port.android

import android.content.Context
import android.util.Log
import com.eratw.port.core.save.SaveDiagnostics

/**
 * 「存档失败」的可上报化接线（2026-11-20，可上报化波）。
 *
 * ## 为什么需要它
 *
 * 玩家报的「SAVEDATA命令在存档时发生了意料之外的错误」只是
 * `StatementExecutor` 在 `ops(ctx).saveData(...)` 返回 false 时打的一句屏上文案
 * （`core-vm/src/main/kotlin/com/eratw/port/core/sem/StatementExecutor.kt:1240-1242`）：
 * 失败不抛异常、不触发任何报告采集，`SaveDiagnostics` 的环形缓冲又只随「下一次别的报告」
 * 才出网。于是 release 包玩家存档失败时，端点一条现场都收不到 —— 这就是要补的盲区。
 *
 * 本类是 [SaveDiagnostics.onFailure] 的**宿主侧安装器**：core 只负责在失败留痕后通知，
 * 「要不要上报、怎么限流」全在这里。
 *
 * ## 为什么复用 [ErrorReportCollector.recordDiagnostic]
 *
 * 那条通道（2026-11-17 覆盖补齐波）本来就是为「非崩溃但需要上报」建的：聚合判重、
 * 轻量报告（不做 binder 重的取证）、`DiagnosticEventLog` 每局每型封顶、落盘后经
 * `ErrorReportUploader.uploadPendingAsync` 走既有端点/令牌配置。存档失败正好落在它的
 * 形状里，没有任何理由另起一条 HTTP 或落盘通道。
 *
 * ## 线程姿态
 *
 * 观察者由 core 在**存档失败的那条线程**（通常是 VM 线程）上同步调用。本类只在这里做
 * 两件极轻的事：判限流、拍下环形缓冲快照；真正的报告采集（读 assets 指纹、写文件、起
 * 上传线程）交给一次性 daemon 线程 —— 存档已经失败了，不许再拿「写报告」把游戏线程按在
 * 磁盘上。报告生成失败只记一行 log：上报是附带动作，绝不许冒泡回存档主路径
 * （core 侧另有独立 try/catch 兜底，见 `SaveDiagnostics.notifyFailure`）。
 */
internal object SaveFailureReporter {

    private const val TAG = "EratwSaveAlert"

    /** 本报告关心的失败语句。其余 op（`WRITE` / `SIDECAR_GC` / `SAVECHARA` …）不在此列。 */
    private val REPORT_OPS: Set<String> = setOf("SAVEDATA", "SAVEGLOBAL")

    /**
     * 在进程入口装一次观察者（`EratwApplication.onCreate`，`:crash` 进程由调用方跳过）。
     *
     * 幂等：重复调用只是把同一个 lambda 再赋一次；[context] 只取 applicationContext，
     * 不持有 Activity。安装本身绝不抛（调用方另有 runCatching，这里再兜一层）。
     */
    fun install(context: Context) {
        runCatching {
            val appContext = context.applicationContext ?: context
            SaveDiagnostics.onFailure = { failure ->
                // 观察者里再兜一层：core 已 try/catch，但宿主侧自己的判式（读字符串、判集合）
                // 也不许把异常送回去，double safety 比信任调用约定便宜。
                runCatching { handle(appContext, failure) }
                    .onFailure { Log.w(TAG, "存档失败上报接线异常（已忽略，不影响存档主路径）", it) }
            }
            Log.i(
                TAG,
                "存档失败观察者已安装（op=${REPORT_OPS.joinToString("/")}，" +
                    "同 op 冷却 ${SaveDiagnostics.ALERT_COOLDOWN_MINUTES} 分钟）",
            )
        }.onFailure { Log.w(TAG, "存档失败观察者安装失败（存档失败将只有 logcat 留痕）", it) }
    }

    /**
     * 一次失败的处置：限流闸 → 被挡只留 [SaveDiagnostics.note] 一行；放行则拍现场并异步落报告。
     *
     * @return true = 已交给后台线程生成报告；false = 冷却中或不在关心的 op 集合里。
     */
    internal fun handle(context: Context, failure: SaveDiagnostics.Failure): Boolean = when {
        failure.op !in REPORT_OPS -> false
        !SaveDiagnostics.claimFailureAlert(failure.op) -> {
            // 冷却中的那些次不是静默丢弃：note 一行进环形缓冲，随冷却后的下一份报告出网。
            SaveDiagnostics.note(
                failure.op,
                failure.path,
                "上报限流（同一 op ${SaveDiagnostics.ALERT_COOLDOWN_MINUTES} 分钟内只报一次）" +
                    " stage=${failure.stage}",
            )
            false
        }
        else -> {
            // 现场快照必须在失败线程上拍：环形缓冲会被后续失败覆盖，而报告在另一条线程上拼装。
            val extraLines = buildList {
                addAll(ErrorReportCollector.saveDiagLines())
                add("存档失败异常：${failure.errorClass}：${failure.message ?: "（无消息）"}")
            }
            // where 进 DiagnosticEventLog 的归一签名：同 op/阶段/相对路径的连续失败归成一条账。
            val where = "存档失败 ${failure.op} stage=${failure.stage} path=${failure.path}"
            Thread({
                runCatching {
                    ErrorReportCollector.recordDiagnostic(
                        context,
                        DiagnosticEventLog.DiagnosticKind.SAVE_FAILURE,
                        null,
                        where,
                        extraLines,
                    )
                }.onFailure { Log.w(TAG, "存档失败报告生成失败（不影响游戏）", it) }
            }, "eratw-save-alert").apply {
                isDaemon = true
                start()
            }
            true
        }
    }
}
