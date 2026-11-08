package com.eratw.port.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.eratw.port.core.update.UpdatePolicy
import com.eratw.port.core.update.UpdateTelemetry
import java.io.File

/**
 * PackageInstaller 会话回执的三分类（CONTRACT-UPD2 §2 U7，本类纯 JVM、可定向单测）：
 *
 * - **成功**（[Success]）：`STATUS_SUCCESS`。注意装的是本应用自己，成功那一拍进程多半已被
 *   系统杀掉，这条回执**大概率收不到**——调用方要按「尽力面」对待，别拿它当唯一完成信号；
 * - **用户取消**（[UserCancelled]）：`STATUS_FAILURE_ABORTED`。玩家在系统确认框里点了退
 *   （或系统侧中止了会话）：更新包还留在下载目录，重试入口原样可用，不计入失败；
 * - **失败 + 原因摘要**（[Failed]）：其余 `STATUS_FAILURE_*` 与一切未知状态码。每类给一句
 *   玩家能照着做的摘要（[summaryOf]），系统原话（`EXTRA_STATUS_MESSAGE`）原样附带不翻译；
 *   原始状态码随 [Failed.status] 一起透出（漏斗那一拍要的是码，不是那句长话）。
 *
 * [of] 只吃两个原始读数（状态码 + 系统原话）加一档语言表，不做任何 Android 调用——分类判定
 * 下沉到这儿就是为了「Android 面不许崩游戏」之外再要一层「判定面不许藏进接收器里没法测」。
 */
sealed class InstallSessionOutcome {

    /** 会话装成了（`STATUS_SUCCESS` = 0）。 */
    object Success : InstallSessionOutcome()

    /** 用户取消 / 会话被中止（`STATUS_FAILURE_ABORTED` = 3，android-36 SDK 实测；勿照旧文档写成 -2）。 */
    data class UserCancelled(val statusMessage: String?) : InstallSessionOutcome()

    /**
     * 失败 + 原因摘要（其余状态码；摘要含系统原话）。
     *
     * [status] 是系统回执的**原始状态码**（`PackageInstaller.EXTRA_STATUS`），U6b 把它透出给
     * 漏斗遥测那一拍用（`install_result` 的 `status` 维只收这种短读数）。它**不参与** [summary]
     * 的生成，也不上屏：玩家看到的句子还是 [summaryOf] 那一句，一个字都没变。
     * 会话自身开不起来 / 路径不合法这类「系统还没回过话」的失败没有状态码，是 null。
     */
    data class Failed(val summary: String, val status: Int? = null) : InstallSessionOutcome()

    companion object {

        /**
         * 回执分类的**唯一入口**：状态码与系统原话进，三分类出；摘要句按 [copy] 所在的
         * 语言档取（玩家面句子全部在 [UpdateCopy] 三语表，U7b 起不再内联中文字面量）。
         *
         * `STATUS_PENDING_USER_ACTION`（-1）**不进本函数**——它是中间态不是终态（该拉起系统
         * 确认框、继续等最终回执），在接收器里单列；传进来会按未知码落失败，那不是预期用法。
         */
        fun of(status: Int, statusMessage: String?, copy: UpdateCopy): InstallSessionOutcome = when (status) {
            PackageInstaller.STATUS_SUCCESS -> Success
            PackageInstaller.STATUS_FAILURE_ABORTED -> UserCancelled(statusMessage)
            else -> Failed(summaryOf(status, statusMessage, copy), status)
        }

        /**
         * 各失败状态码的玩家面摘要：每句都带「下一步该做什么」，系统原话有就附带。
         *
         * 句子全部来自 [UpdateCopy] 三语表（U7b 把 U7 波次契约豁免的内联中文收进表；
         * 中文档与当时的内联原句逐字相同）。
         */
        internal fun summaryOf(status: Int, statusMessage: String?, copy: UpdateCopy): String {
            val base = when (status) {
                PackageInstaller.STATUS_FAILURE_BLOCKED -> copy.installBlockedLine
                PackageInstaller.STATUS_FAILURE_CONFLICT -> copy.installConflictLine
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> copy.installIncompatibleLine
                PackageInstaller.STATUS_FAILURE_INVALID -> copy.installInvalidLine
                PackageInstaller.STATUS_FAILURE_STORAGE -> copy.installStorageLine
                PackageInstaller.STATUS_FAILURE_TIMEOUT -> copy.installTimeoutLine
                else -> copy.fill(copy.installGenericFailedFormat, status)
            }
            return if (statusMessage.isNullOrBlank()) base else copy.fill(copy.installSystemMessageFormat, base, statusMessage)
        }
    }
}

/**
 * API 29+ 的整包安装会话（CONTRACT-UPD2 §2 U7，P2-11「系统安装器拉起后没有安装结果回执」）：
 *
 * 用 `PackageManager.packageInstaller` 开 `MODE_FULL_INSTALL` 会话，把**已核对**的 APK 写进
 * 会话（同一份走 [UpdateFileProvider] allowlist 口径的文件），commit 后经广播接收器收
 * `EXTRA_STATUS` 回执，按 [InstallSessionOutcome] 三分类落**结果通知 + Toast**——
 * 通知复用 [UpdateDownloadService.CHANNEL_RESULT] 渠道与 [UpdateDownloadService.NOTIFICATION_RESULT]
 * 这条 id，形状与 `UpdateDownloadService.postResultNotification` 同一版式。
 *
 * ## 为什么是广播接收器而不是 SessionCallback
 *
 * `SessionCallback` 只报会话进度与结束，**不带 commit 结果**；commit 结果只能经
 * `IntentSender` 送（这里用 `PendingIntent.getBroadcast` + 动态接收器，官方样本同款）。
 * 接收器在 commit **之前**注册（防回执竞态），终态到达即注销；用户取消（确认框里按退）
 * 会以 `STATUS_FAILURE_ABORTED` 再送一条广播，所以「拉起确认框」之后接收器必须活着等。
 *
 * ## 生命周期与防泄漏
 *
 * - 会话写入跑在自开的 daemon 线程（几百 MiB 的阻塞拷贝，不吃调用方的主线程）；
 * - 写完用 try/finally `close`：`close` 只关本进程侧的流，commit 过的会话由系统安装器继续持有；
 * - commit 之前任何一步失败 → `abandon` 会话（防会话泄漏）+ 注销接收器 + 落一条失败回执；
 * - 每次开新会话前先按 uid 扫一遍本包遗留会话（[reclaimStaleSessions] + [shouldReclaimSelfSession] 的
 *   超时判定），回收上次进程被杀留下的孤儿；`sessionId` **有意不落盘**——uid 级枚举比记单个 id 更稳，
 *   记 id 会漏掉「杀在写登记之前」的遗留、且只认最后一条；
 * - 本对象**永不抛**：入口 [installApk] 与接收器内的一切都在 `runCatching` 里，更新面不许把游戏搞崩。
 *
 * ## PendingIntent 的可变性
 *
 * 回执 PendingIntent 必须可变（API 31 起 `FLAG_MUTABLE`）：系统安装器要往这条广播里**填**
 * `EXTRA_STATUS` / `EXTRA_STATUS_MESSAGE` / 确认框 intent，建死（IMMUTABLE）它就填不进去了。
 * 反方向的通知 PendingIntent（点击重装）照旧 immutable，那是我们自己发起的。
 *
 * ## 权限与回退
 *
 * `REQUEST_INSTALL_PACKAGES` 的检查留在 [UpdateManager.launchInstaller]（两档共用的第一道闸）；
 * 本对象只接「已放行」的调用。API 29 以下根本不该走到这儿（路由在 [UpdateManager.installRouteFor]）。
 */
object PackageInstallerSession {

    private const val TAG = "EratwUpdate"

    /** 回执广播的自定义 action（基座 Intent 上 setPackage 钉死只投给本进程的接收器）。 */
    private const val ACTION_COMMIT_STATUS = "com.eratw.port.android.action.UPDATE_INSTALL_STATUS"

    /**
     * Session API 路的「已提交会话」信号：[UpdateManager.launchInstaller] 在 29+ 提交会话后
     * 返回它，调用方（服务的 ACTION_INSTALL 支路）据此**不做任何上屏**——回执是异步的，
     * 任何「装好了/没装成」的句子都只能由回执面说，这里提前说话就是谎报。
     */
    const val SESSION_SUBMITTED_SIGNAL = "session-submitted"

    // ------------------------------------------------------------------
    // 玩家面句子全部来自 UpdateCopy 三语表（U7b 收表：原先这里是 U7 波次契约 §2 豁免的
    // 中文字面量，取用见 copy 参数；渠道名复用既有键 channelResultName，不再留内联兜底）
    // ------------------------------------------------------------------

    private const val COPY_BUFFER_BYTES = 256 * 1024

    /**
     * 孤儿会话回收阈值（毫秒）：早于此刻 [STALE_SESSION_MILLIS] 的**本包**会话视为遗留，开新会话
     * 前先放掉。10 分钟远大于正常 commit→回执 的时延，又足够避掉「确认框被玩家晾着」的在途会话，
     * 不会误杀并发未结束的那次安装。真机时延口径 TODO(verify)。
     */
    internal const val STALE_SESSION_MILLIS = 10 * 60_000L

    /**
     * 把已核对的 APK 交给 PackageInstaller 会话（API 29+ 才能调，路由在调用方）。
     *
     * 立即返回（内部起 daemon 线程做阻塞写入与 commit）；回执（含会话开不起来的情形）
     * 一律经结果通知 + Toast 送达，本函数与调用方之间没有同步返回值。
     *
     * 文案在**入口线程**（调用方 [UpdateManager.launchInstaller]，服务/界面的主线程）经
     * [UpdateUi.copyFor] 单路取一次再往下传（与 `UpdateDownloadService.copy()` 同源同形态）：
     * daemon 线程与回执路径不碰偏好、不自判 Locale（AGENTS.md §6.3），一次回执里语言一致。
     */
    fun installApk(context: Context, apk: File) {
        val appContext = context.applicationContext
        val copy = UpdateUi.copyFor(appContext)
        val thread = Thread {
            runCatching { installApkBlocking(appContext, apk, copy) }
                .onFailure { t ->
                    Log.w(TAG, "安装会话线程异常收场", t)
                    deliver(appContext, apk, copy, InstallSessionOutcome.Failed(
                        copy.fill(copy.installSessionOpenFailedFormat, t.javaClass.simpleName)),
                    )
                }
        }
        thread.isDaemon = true
        thread.name = "eratw-update-install"
        thread.start()
    }

    /**
     * 会话主线：开参数 → createSession → openSession → openWrite 拷贝 + fsync → commit。
     *
     * 进来的 APK 只认更新包名（`UpdatePolicy.isArtifactFileName` + `.apk` 后缀，与
     * [UpdateFileProvider.resolve] 的 allowlist 同一口径）：会话通道再宽，也只写这一种文件。
     */
    private fun installApkBlocking(appContext: Context, apk: File, copy: UpdateCopy) {
        if (!apk.isFile || !UpdatePolicy.isArtifactFileName(apk.name) || !apk.name.endsWith(".apk")) {
            deliver(appContext, apk, copy, InstallSessionOutcome.Failed(copy.installPathInvalidLine))
            return
        }
        val installer = appContext.packageManager.packageInstaller
        // 先扫掉上次进程被杀遗留的本包孤儿会话（防会话泄漏）；清理自身失败不许挡住本次安装。
        runCatching { reclaimStaleSessions(installer, appContext.packageName, System.currentTimeMillis()) }
            .onFailure { Log.w(TAG, "清理历史孤儿会话失败（不影响本次安装）", it) }
        val sessionId: Int = try {
            installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        } catch (t: Throwable) {
            Log.w(TAG, "安装会话创建失败：${apk.name}", t)
            deliver(appContext, apk, copy, InstallSessionOutcome.Failed(copy.fill(copy.installSessionOpenFailedFormat, t.javaClass.simpleName)))
            return
        }
        val session: PackageInstaller.Session = try {
            installer.openSession(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "安装会话打不开（sessionId=$sessionId）", t)
            runCatching { installer.abandonSession(sessionId) }
                .onFailure { Log.w(TAG, "会话 $sessionId 放不掉（防泄漏兜底也没走通）", it) }
            deliver(appContext, apk, copy, InstallSessionOutcome.Failed(copy.fill(copy.installSessionOpenFailedFormat, t.javaClass.simpleName)))
            return
        }
        var receiver: BroadcastReceiver? = null
        try {
            session.openWrite(apk.name, 0L, apk.length()).use { out ->
                apk.inputStream().use { input -> input.copyTo(out, COPY_BUFFER_BYTES) }
                session.fsync(out)
            }
            receiver = buildStatusReceiver(appContext, apk, copy)
            registerStatusReceiver(appContext, receiver)
            // 可变性见类头：系统要往这条广播里填回执 extras，建死就永远收不到状态。
            val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val sender = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_COMMIT_STATUS).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
            ).intentSender
            session.commit(sender)
            Log.i(TAG, "安装会话已提交（sessionId=$sessionId，${apk.name}），回执经广播送达")
        } catch (t: Throwable) {
            Log.w(TAG, "安装会话写入/提交失败（sessionId=$sessionId）", t)
            receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
            runCatching { session.abandon() }
                .onFailure { Log.w(TAG, "会话 $sessionId abandon 失败（防泄漏兜底也没走通）", it) }
            deliver(appContext, apk, copy, InstallSessionOutcome.Failed(copy.fill(copy.installSessionOpenFailedFormat, t.javaClass.simpleName)))
        } finally {
            // close 不等于 abandon：commit 过的会话归安装器继续持有，这里只放本进程侧的资源。
            runCatching { session.close() }
        }
    }

    /**
     * 扫掉本包遗留的孤儿会话（防会话泄漏：上次进程在 createSession 之后、commit 之前被系统/用户
     * 杀掉，会话会一直挂在安装器里占存储，直到 AOSP 自身的超时才清）。按 uid 枚举 [PackageInstaller.mySessions]
     * 覆盖全部本包会话，**不依赖持久化 sessionId**——记住单个 id 反而更弱（杀在写登记之前就丢了、且只记得最后一条）。
     * 每条 abandon 各自 runCatching：一条放不掉不影响其余清理。
     */
    private fun reclaimStaleSessions(installer: PackageInstaller, ourPackage: String, nowMillis: Long) {
        for (info in installer.mySessions) {
            if (!shouldReclaimSelfSession(info.appPackageName, ourPackage, nowMillis, info.createdMillis)) continue
            runCatching { installer.abandonSession(info.sessionId) }
                .onSuccess { Log.i(TAG, "回收孤儿安装会话 sessionId=${info.sessionId}（距今 ${nowMillis - info.createdMillis} ms）") }
                .onFailure { Log.w(TAG, "会话 ${info.sessionId} 回收失败（交给系统超时）", it) }
        }
    }

    /**
     * 会话回收判定（纯函数，可 JVM 定向单测）：仅当「属于本包」且「创建时刻早于 [STALE_SESSION_MILLIS]」
     * 才回收。两条条件各挡一类误杀——别人的会话不动；刚提交、还可能在确认框里等的本包会话不动。
     */
    internal fun shouldReclaimSelfSession(
        sessionPackageName: String?,
        ourPackage: String,
        nowMillis: Long,
        creationTimeMillis: Long,
    ): Boolean = sessionPackageName == ourPackage && nowMillis - creationTimeMillis > STALE_SESSION_MILLIS

    /**
     * 回执接收器：`EXTRA_STATUS` 进、三分类出。
     *
     * - `STATUS_PENDING_USER_ACTION`（-1，中间态）：从 `Intent.EXTRA_INTENT` 取系统确认框
     *   intent 拉起（非 Activity 上下文补 `FLAG_ACTIVITY_NEW_TASK`），**不注销自己**——
     *   玩家在确认框上的最终选择还会送来一条终态广播；
     * - 其余状态：按 [InstallSessionOutcome.of] 分类后立刻注销并送达，一次终态一份回执。
     */
    private fun buildStatusReceiver(appContext: Context, apk: File, copy: UpdateCopy): BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirm == null) {
                            Log.w(TAG, "回执说等玩家确认，但没带来确认框 intent（status extras 缺 EXTRA_INTENT）")
                            return
                        }
                        val launch = Intent(confirm)
                        if (ctx !is android.app.Activity) launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(launch) }
                            .onFailure { t ->
                                Log.w(TAG, "系统确认框拉不起来", t)
                                deliver(
                                    appContext, apk, copy,
                                    InstallSessionOutcome.Failed(
                                        copy.fill(copy.installConfirmOpenFailedFormat, t.javaClass.simpleName),
                                    ),
                                )
                            }
                    }

                    else -> {
                        runCatching { ctx.unregisterReceiver(this) }
                        deliver(
                            appContext, apk, copy,
                            InstallSessionOutcome.of(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), copy),
                        )
                    }
                }
            }
        }

    /**
     * 注册回执接收器（commit 之前调，防竞态）。
     *
     * L4-6：API 33+ 用 **`RECEIVER_NOT_EXPORTED`**（收窄）。这条广播是**本应用创建**的
     * PendingIntent（`PendingIntent.getBroadcast`）投递的：`PendingIntent.send()` 以 creator
     * （本应用）的身份执行，动态接收器因此收得到；旧实现用 `RECEIVER_EXPORTED`，等于把
     * `ACTION_COMMIT_STATUS` 这个自定义 action 向全设备开放 —— 任意 app 发一条同名广播就能
     * 伪造「安装成功/失败」回执（假通知 + 假遥测），甚至借 `STATUS_PENDING_USER_ACTION` 的
     * `EXTRA_INTENT` 让本应用以自己身份拉起任意 Activity。33 以下的动态接收器默认不导出。
     *
     * TODO(verify)：`RECEIVER_NOT_EXPORTED` 是否在所有 API 33+ 机型/ROM 上都能收到
     * PackageInstaller 的回执（本机无 adb 装置，未真机验证）。若某 ROM 收不到，正确回退是
     * 保留 EXPORTED 但在 `onReceive` 里校验 `getSendingUid()` 属于系统安装器，而不是放开即信任。
     */
    private fun registerStatusReceiver(appContext: Context, receiver: BroadcastReceiver) {
        val filter = IntentFilter(ACTION_COMMIT_STATUS)
        val mainHandler = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, null, mainHandler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter, null, mainHandler)
        }
    }

    /** 三分类的送达面：结果通知（复用 CHANNEL_RESULT 与服务的 NOTIFICATION_RESULT）+ Toast；句子全部来自 [copy]。 */
    private fun deliver(appContext: Context, apk: File, copy: UpdateCopy, outcome: InstallSessionOutcome) {
        // 第 6 拍（漏斗遥测）：终态一确定就记一笔，排在一切上屏之前——通知发不出去也不该少这一格计数。
        recordInstallResult(appContext, outcome)
        when (outcome) {
            InstallSessionOutcome.Success -> {
                // 装的是本应用自己：成功那一拍进程多半已被杀，这条只是尽力面（真机口径 TODO(verify)）。
                postReceiptNotification(appContext, copy, copy.installDoneTitle, copy.installDoneLine, null)
                toast(appContext, copy.installDoneLine)
                Log.i(TAG, "安装回执：成功（${apk.name}）")
            }

            is InstallSessionOutcome.UserCancelled -> {
                postReceiptNotification(appContext, copy, copy.installCancelledTitle, copy.installCancelledLine, retryIntent(appContext, apk))
                toast(appContext, copy.installCancelledLine)
                Log.i(TAG, "安装回执：用户取消（${apk.name}）")
            }

            is InstallSessionOutcome.Failed -> {
                val body = copy.fill(copy.installFailedBodyFormat, outcome.summary)
                postReceiptNotification(appContext, copy, copy.installFailedTitle, body, retryIntent(appContext, apk))
                toast(appContext, body)
                Log.w(TAG, "安装回执：失败（${apk.name}）——${outcome.summary}")
            }
        }
    }

    /**
     * 三分类 → 漏斗遥测的终态标签（纯函数，可 JVM 定向单测）：与 core 的 `OUTCOME_*` 三个词
     * 逐字同一套，**不在这里造第四个词**——聚合表只数这三档，多一档就是没人看的脏维。
     */
    internal fun telemetryOutcomeOf(outcome: InstallSessionOutcome): String = when (outcome) {
        InstallSessionOutcome.Success -> UpdateTelemetry.OUTCOME_SUCCESS
        is InstallSessionOutcome.UserCancelled -> UpdateTelemetry.OUTCOME_CANCELLED
        is InstallSessionOutcome.Failed -> UpdateTelemetry.OUTCOME_FAILED
    }

    /**
     * 安装终态那一拍（`install_result`）：走 U6 已有的接线层，本文件不建缓冲、不碰发送。
     *
     * 三条口径：
     * - **维度只有三样**：终态标签、失败档的原始状态码、本机版本号。[InstallSessionOutcome.Failed.summary]
     *   那句长话（含系统原话）一个字都不交——它是玩家面与 logcat 取证面，超不过 core 的
     *   `MAX_DIM_CHARS`，也不该出现在聚合里（零路径、零存档内容）；
     * - **版本号读不出来就当没这一维**：[UpdateManager.currentVersionCode] 失败回 -1，
     *   接线层的 `versionOrNone` 归一成 null；这一发整体兜在 `runCatching` 里，
     *   遥测炸了不许挡住通知与 Toast（更不许把 daemon 安装线程带崩）；
     * - **线程不管**：接收器的回执跑在主线程 Handler、会话失败路径跑在 daemon 线程，
     *   主线程那一档由接线层的内存中继接住（下一个后台拍排空），这里不判线程也不落盘。
     */
    private fun recordInstallResult(appContext: Context, outcome: InstallSessionOutcome) {
        val status = (outcome as? InstallSessionOutcome.Failed)?.status
        val version = runCatching { UpdateManager.currentVersionCode(appContext) }.getOrDefault(-1L)
        runCatching {
            UpdateTelemetryWiring.installResult(appContext, telemetryOutcomeOf(outcome), status, version)
        }
    }

    /** 失败/取消的「点这条通知再装一次」：与服务 ACTION_INSTALL 支路同一个入口（重过目录与 allowlist 校验）。 */
    private fun retryIntent(appContext: Context, apk: File): Intent =
        Intent(appContext, UpdateDownloadService::class.java)
            .setAction(UpdateDownloadService.ACTION_INSTALL)
            .putExtra(UpdateDownloadService.EXTRA_APK_PATH, apk.absolutePath)

    /** 与 `UpdateDownloadService.postResultNotification` 同形状：BigTextStyle + autoCancel + 可选点击。 */
    private fun postReceiptNotification(appContext: Context, copy: UpdateCopy, title: String, text: String, contentIntent: Intent?) {
        runCatching {
            val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
            ensureResultChannel(appContext, manager, copy)
            var builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(appContext, UpdateDownloadService.CHANNEL_RESULT)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appContext)
            }
            builder = builder
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
            if (contentIntent != null) {
                val immutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
                builder = builder.setContentIntent(
                    PendingIntent.getService(appContext, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag),
                )
            }
            manager.notify(UpdateDownloadService.NOTIFICATION_RESULT, builder.build())
        }.onFailure { Log.w(TAG, "安装回执通知发不出去（只影响提醒）", it) }
    }

    /**
     * 结果渠道兜底：正常路径下下载服务早就建好了（createNotificationChannel 对已存在渠道
     * 是幂等的，重复建不会改名字）；这里只为「回执晚到、服务从未跑过」的少见情形兜住，
     * 不让回执因为渠道缺失而整条发不出去。渠道名取 [copy] 的 channelResultName（U7b 起
     * 表为唯一词源，原先的内联兜底名已删）。
     */
    private fun ensureResultChannel(appContext: Context, manager: NotificationManager, copy: UpdateCopy) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            if (manager.getNotificationChannel(UpdateDownloadService.CHANNEL_RESULT) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        UpdateDownloadService.CHANNEL_RESULT,
                        copy.channelResultName,
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
        }.onFailure { Log.w(TAG, "结果渠道兜底创建失败（只影响提醒）", it) }
    }

    /** 主线程 Toast（回执接收器跑在主线程 Handler 上，但守护线程的失败路径不是）。 */
    private fun toast(appContext: Context, text: String) {
        val show = Runnable {
            runCatching { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
                .onFailure { Log.w(TAG, "Toast 都发不出去", it) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run() else Handler(Looper.getMainLooper()).post(show)
    }
}
