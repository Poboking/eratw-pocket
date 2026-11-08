package com.eratw.port.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.UpdatePolicy
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 更新下载的前台服务（U-E）：常驻进度通知 + 取消按钮 + 完成/失败通知。
 *
 * 为什么单独成文件（EP4 U0）：前台服务与通知构造是「下载过程可见性」这一路（U1）的独占改动面，
 * 与弹窗/检查态那一趟（U2）并行；拆开后两路才不必共用一个文件。文案取 [UpdateProgressText]，
 * 成单形状见 [DownloadJob]，触发与编排见 [UpdateUi]。
 *
 * ## 下载改走前台服务 + 常驻通知（U-E 的存在理由）
 *
 * 旧版只有 Toast 百分比：几百 MiB 的下要几十分钟，Toast 既看不全、玩家一离开界面就什么都没剩。
 * 现在下载由 [UpdateDownloadService] 承载：通知栏常驻一条进度（已下/总量/百分比/还剩多少），
 * 带「取消下载」按钮（断点保留，再点会从断点继续）；下载完成后点通知拉起系统安装器（整包）
 * 或告知「下次启动自动应用」（数据包）。API 33+ 的 `POST_NOTIFICATIONS` **缺授权不许静默失败**：
 * 下载前先弹一次说清「进度在通知栏」，玩家拒绝也能继续下载，但 Toast 明说「通知栏里看不到进度」。
 *
 * ## 形态
 *
 * - 单飞闸仍由 [UpdateManager.tryBeginDownload] 定（进程内一次只下一条）；本服务**先抢闸
 *   再动任何本单状态**（2026-11-04 复查收口：旧序先换状态再抢闸，「已有下载在跑」的早退路
 *   会踩掉在飞单的进度渲染与通知；2026-11-03 I 簇的 FGS 闸由抢闸失败路的前台重申与
 *   [enterForegroundThenStop] 过闸退场共同保住），抢不到就报「已经有一个下载在跑」；
 * - 通知与 Toast 的文案全部取 [UpdateProgressText]，句子按 [UpdateCopy] 三语取（EP4 U3b；玩家面中文
 *   不再是硬编码）；本服务取语言只有一条路 —— [UpdateUi.copyFor]，见 [notifyCopy] 的说明。
 *   下载与续传、退避、清理的管线一个字都不在本服务里重算，[UpdateManager.download] 照旧是唯一执行体；
 * - 「取消下载」= 置标志 + interrupt 工作线程：在途的块读操作最迟在下一次进度回调处退出，
 *   `UpdateManager` 的中断分支返回「断点已保留」，`.part` 原样留着（契约 §1.4 的续传口径）；
 * - 整包下载完成后**不自动弹安装器**（后台拉起 Activity 在 Android 10+ 本来就不可靠），
 *   改为完成通知点击 → ACTION_INSTALL → [UpdateManager.launchInstaller]（它自带 handoff 登记）；
 * - 数据包下载完成后 [UpdateManager.stageAssetsBundle] 登记指针，应用仍在下次启动装载前那拍。
 *
 * ## EP4 U1 补的「看得见」（本车道）
 *
 * - **速度与预报**（病 ①）：本服务把 [UpdateManager.download] 已有的累计字节回调按时钟存成定长滑动窗口
 *   （[UpdateProgressText.SPEED_WINDOW_MILLIS]），正文多出「约 X/秒」与「约 X 后下完」；窗口不足、
 *   总量没声明、速度算不出正数 ⇒ 少那一句，绝不编一个数（判定全在 [UpdateProgressText]，这里只搬数）。
 * - **等待与换地址不再静默**（病 ②）：退避等待与换下一条地址期间**根本没有进度回调**
 *   （UpdateManager.kt:499-519 只在读着字节时才回调），所以正文靠 [PROGRESS_HEARTBEAT_MILLIS] 的
 *   **主线程心跳**刷出来（不是第二条线程）；「像不像卡在等待」由 [UpdateProgressText.stallLine] 按
 *   「多久没有新内容进来 + 整段跑了多久」判，慢源真的在吐字节时它返回 null。
 * - **七类失败各说各话**（病 ③）：结果通知读 [DownloadOutcome.kind]（现成的，本服务不为此改管线），
 *   处置句取 [UpdateProgressText.failureAction]。
 * - **不给通知权限也有补救面**（病 ④）：[notificationsVisible] 的读数决定正文里能不能说「点这条通知」，
 *   失败时**无条件**再补一条 Toast（不依赖通知权限），并把原单记进 [lastFailedJob] 供同屏重放
 *   （[retryLastFailed]）。
 *
 * ## 异常面
 *
 * 服务整体在 `runCatching` 里跑：前台通知起不来就明说「本次没有开始下载」并放闸（**不静默**），
 * 其余异常只 logcat —— 更新面不许把游戏搞崩。
 */
class UpdateDownloadService : Service() {

    companion object {

        private const val TAG = "EratwUpdate"

        const val ACTION_DOWNLOAD = "com.eratw.port.android.action.UPDATE_DOWNLOAD"
        const val ACTION_CANCEL = "com.eratw.port.android.action.UPDATE_CANCEL"
        const val ACTION_INSTALL = "com.eratw.port.android.action.UPDATE_INSTALL"

        const val EXTRA_FILE_NAME = "update_file_name"
        const val EXTRA_SIZE_BYTES = "update_size_bytes"
        const val EXTRA_SHA256 = "update_sha256"
        const val EXTRA_KIND = "update_kind"
        const val EXTRA_ASSETS_VERSION = "update_assets_version"
        const val EXTRA_APK_PATH = "update_apk_path"

        const val CHANNEL_PROGRESS = "eratw_update_progress"
        const val CHANNEL_RESULT = "eratw_update_result"

        const val NOTIFICATION_PROGRESS = 0x5701
        const val NOTIFICATION_RESULT = 0x5702

        /** 通知刷新节流（毫秒）：notify 是跨进程调用，每个 8 KiB 块刷一次会把系统刷吐。 */
        private const val NOTIFY_MIN_INTERVAL_MILLIS = 1_000L

        /**
         * 进度心跳（毫秒，EP4 U1-②）：**跑在主线程的 Handler 上，不是第二条线程**。
         *
         * 为什么非要有它：进度回调只在**有新字节落盘时**才发生，而 [UpdateManager.download] 的退避等待
         * 与换地址整段都在静默里跑（UpdateManager.kt:499-519 的循环里只有 Thread.sleep 与下一条候选）。
         * 只靠回调的话，「正在等一等接着下」这句话永远只能在等待**结束**时才刷出来 —— 玩家要的恰恰是
         * 等待期间的答复。3 秒一档：既比 [UpdateProgressText] 的 45 秒判据细得多，又不会把通知刷出问题。
         */
        private const val PROGRESS_HEARTBEAT_MILLIS = 3_000L

        /** 开一条下载（[UpdateUi] 确认后调用）。API 26+ 走 startForegroundService，先亮通知再干活。 */
        fun start(context: Context, job: DownloadJob) {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_FILE_NAME, job.artifact.fileName)
                putExtra(EXTRA_SIZE_BYTES, job.artifact.sizeBytes)
                putExtra(EXTRA_SHA256, job.artifact.sha256Hex)
                putExtra(EXTRA_KIND, job.kind.name)
                putExtra(EXTRA_ASSETS_VERSION, job.assetsVersion)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        private fun cancelIntent(context: Context): Intent =
            Intent(context, UpdateDownloadService::class.java).setAction(ACTION_CANCEL)

        private fun installIntent(context: Context, apkPath: String): Intent =
            Intent(context, UpdateDownloadService::class.java)
                .setAction(ACTION_INSTALL)
                .putExtra(EXTRA_APK_PATH, apkPath)

        private fun retryIntent(context: Context, job: DownloadJob): Intent {
            val intent = Intent(context, UpdateDownloadService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_FILE_NAME, job.artifact.fileName)
                putExtra(EXTRA_SIZE_BYTES, job.artifact.sizeBytes)
                putExtra(EXTRA_SHA256, job.artifact.sha256Hex)
                putExtra(EXTRA_KIND, job.kind.name)
                putExtra(EXTRA_ASSETS_VERSION, job.assetsVersion)
            }
            return intent
        }

        /**
         * 最近一次**没下成**的那一单（EP4 U1-④）：内存一份 + 盘一份（U2 病②，见 [rememberFailedJob]）。
         *
         * 为什么要壳层自己记着：结果通知上的「点一下重试」是**唯一**挂着重开的入口，而没给通知权限的玩家
         * 根本看不见那条通知（[UpdateProgressText.startedToast] 的 false 分支明说「通知栏里看不到进度」）。
         * 这一单本身（文件名/字节数/摘要/通道）是重试需要的全部信息，记下来就不必重新走一遍检查与弹窗。
         * 盘上那份让入口在进程死了之后还在：内存 miss 再读盘还原（[lastFailedDownload]），
         * 成功一单两处一起清（[clearFailedJob]），开始下载不清（仍是失败态）。
         */
        @Volatile
        private var lastFailedJob: DownloadJob? = null

        /**
         * 记一张失败单：内存 + 盘（U2 病②）。
         *
         * 内存保「本进程内的即时重放」，盘（[UiPrefs.writeFailedDownloadRecord]）保「进程被杀后
         * 入口还在」—— 病根就是旧版只有 @Volatile 内存一份。落盘失败只影响下次冷启的重放，
         * 本进程内照旧：logcat 记一笔不抛（更新面不许把游戏搞崩）。
         */
        private fun rememberFailedJob(context: Context, job: DownloadJob) {
            lastFailedJob = job
            runCatching {
                UiPrefs.writeFailedDownloadRecord(
                    context,
                    FailedDownloadRecord(
                        fileName = job.artifact.fileName,
                        sizeBytes = job.artifact.sizeBytes,
                        sha256Hex = job.artifact.sha256Hex,
                        kind = job.kind,
                        assetsVersion = job.assetsVersion,
                    ),
                )
            }.onFailure { Log.w(TAG, "失败单落盘没写成（只影响进程重启后的重试入口）", it) }
        }

        /** 成功一单清空（盘 + 内存）。开始下载不清：失败态要到「真的下成」才解除。 */
        private fun clearFailedJob(context: Context) {
            lastFailedJob = null
            runCatching { UiPrefs.clearFailedDownloadRecord(context) }
                .onFailure { Log.w(TAG, "失败单清盘没写成（只影响下次冷启的入口显隐）", it) }
        }

        /** 有没有可重放的失败单（内存那一眼；不带 Context 的旧读数，调用方接线带 Context 的重载后可删）。 */
        fun lastFailedDownload(): DownloadJob? = lastFailedJob

        /**
         * 最近一次**没下成**的那一单：先读内存、miss 再读盘还原成 [DownloadJob]（U2 病②）。
         *
         * 还原口径：五元组校验全在 [UiPrefs.readFailedDownload]（缺一视为无效、坏文件名整单作废，
         * 重过 UpdatePolicy.isArtifactFileName 等成单同款校验，不绕）；玩家面标签按当前语言现推
         * （与通知标签同口径，成单那一刻的语言不落盘）。
         */
        fun lastFailedDownload(context: Context): DownloadJob? {
            lastFailedJob?.let { return it }
            val record = runCatching { UiPrefs.failedDownloadRecord(context) }.getOrNull() ?: return null
            // 还原期**不推语言**：label 只是给「读 label 的旧调用方」留的读数，本链路（retryLastFailed
            // → start → intent）只带 artifact/kind/assetsVersion，label 无人消费；通知正文按 kind
            // + 渲染期语言现推（renderProgressLocked 口径）。静态函数拿不到 applicationContext，
            // 也不该为一句没人读的标签再开一处取语言入口。
            return DownloadJob(
                Artifact(record.fileName, record.sizeBytes, record.sha256Hex),
                record.kind,
                "",
                record.assetsVersion,
            )
        }

        /**
         * 重放最近一次失败的下载：不重新查清单、不重新弹窗，直接把原单再交给本服务。
         *
         * 返回 false = 内存与盘上都没有可重放的单（进程死了且盘上没有，或压根没失败过）⇒
         * 调用方该回落到正常的检查/弹窗流程。单飞闸仍由 [UpdateManager.tryBeginDownload] 定，
         * 重复调用不会开第二条下载。
         */
        fun retryLastFailed(context: Context): Boolean {
            val job = lastFailedDownload(context) ?: return false
            start(context, job)
            return true
        }

        // ------------------------------------------------------------------
        // 进度读数暴露区（U5 P2-12）：只加不改。
        // 设置页要的是「此刻这一单下了多少」的只读快照，不参与通知渲染、不碰节流、
        // 不开第二条下载线程 —— 所以这里读的是下载线程本来就在维护的活字段，
        // 在 progressLock 内取一份纯数据出去，别的路径一个字都不动。
        // ------------------------------------------------------------------

        /** 当前活着的服务实例（[onCreate] 挂、[onDestroy] 摘）；没有实例 = 没有下载在跑。 */
        @Volatile
        private var exposedService: UpdateDownloadService? = null

        /**
         * 进程内只读进度读数：设置页与 [UpdateUi.channelReadout] 都从这一个口取。
         *
         * 判据（是否在跑）只读 `activeJob`：它在抢到单飞闸之后赋值、在 [runDownload] 的 finally 清空，
         * 与闸同寿命，不拿它当放闸依据、只当「这一刻有没有单」的读数。读不到实例就报 [DownloadProgress.IDLE]。
         */
        fun progress(): DownloadProgress {
            val s = exposedService ?: return DownloadProgress.IDLE
            return synchronized(s.progressLock) {
                val job = s.activeJob
                val total = if (s.latestTotalBytes > 0L) s.latestTotalBytes else job?.artifact?.sizeBytes ?: 0L
                DownloadProgress(
                    doneBytes = s.latestDoneBytes,
                    totalBytes = total,
                    percent = UpdateChannelReadout.progressPercent(s.latestDoneBytes, total),
                    running = job != null,
                    kind = job?.kind,
                )
            }
        }

        /**
         * 设置页「停止下载」（P2）：通知看不见时取消动作只挂在通知 action 上，玩家没有别的停法。
         *
         * 只在**真有一单在跑**时投递取消意图：服务不在/已收尾时起一个 ACTION_CANCEL 的普通服务
         * 只是白留一个没人停的组件；取消本身仍在服务里走既有分支（置标志 + interrupt，断点保留）。
         */
        fun cancel(context: Context) {
            if (!progress().running) return
            runCatching { context.startService(cancelIntent(context)) }
                .onFailure { Log.w(TAG, "停止下载的意图没投出去（这一单会自己跑完）", it) }
        }
    }

    private val cancelFlag = AtomicBoolean(false)

    /**
     * 系统时限到点（U2 病①）：[onTimeout] 置位，工作线程的收尾按它分流——
     * 没有它，interrupt 之后 runDownload 会落进「玩家取消」那一支，把系统的锅扣到玩家头上。
     * 与 [cancelFlag] 一样每单开跑时复位。
     */
    private val systemTimedOut = AtomicBoolean(false)

    /**
     * 本服务实例是否持有 [UpdateManager] 的单飞闸（放闸的唯一判据）。
     *
     * 只在 [beginDownload] 真抢到闸之后置位、[releaseGate] / [runDownload] 收尾时清掉；
     * 「activeJob 非空」**不能**当判据 —— 2026-11-04 复查前它先于抢闸被赋值，异常路径按它
     * 放闸会把在飞下载的闸一起放掉（下一单得以与在飞下载并发跑，单飞闸名存实亡）。
     */
    @Volatile
    private var gateHeld = false

    /** 放自己抢到的单飞闸；没持有就是 no-op（防误放别人的闸）。 */
    private fun releaseGate() {
        if (gateHeld) {
            gateHeld = false
            UpdateManager.releaseDownload()
        }
    }

    /**
     * 「已有下载在跑」那条早退路的前台合规重申：对在飞那条进度通知**同 id** 重画一次
     * （顺带把 startForeground 重申一遍，FGS 闸在任何解释下都算过），不撤通知、不下前台、
     * 不碰在飞单的任何状态。文案走 [renderProgressLocked] 的现渲染；被节流掉说明屏上那条
     * 就是当前内容，按上次刷出去的原文原样重申。只动 logcat 取证面，不新增玩家面句子与
     * 取语言/渲染点（形状闸的计数口径不动，见 [UpdateNotifyCopyTest]）。
     */
    private fun reassertForegroundWhileBusy() {
        val now = System.currentTimeMillis()
        val fallbackCopy = copy()
        val (text, percent) = synchronized(progressLock) {
            renderProgressLocked(now)
                ?.takeIf { it.first.isNotEmpty() }
                ?: (lastNotifiedText.ifEmpty { fallbackCopy.progressNotifyTitle } to lastNotifiedPercent.toLong())
        }
        runCatching { startForegroundCompat(NOTIFICATION_PROGRESS, buildProgressNotification(text, percent)) }
            .onFailure { Log.w(TAG, "在飞通知的前台重申失败（在飞下载照跑）", it) }
    }

    @Volatile
    private var worker: Thread? = null

    /** 本单在飞的下载：失败通知的「点一下重试」要按原单重开，不能凭空造。 */
    @Volatile
    private var activeJob: DownloadJob? = null

    /**
     * 本单渲染用的三语文案（EP4 U3b）：一条下载在飞的期间只取一次。
     *
     * 为什么钉在开跑那一刻：通知正文每 3 秒由心跳重画一次，中途换词会让同一单前后两行不搭；
     * 玩家真换了语言，影响的是**下一条**单，与这条通道其余「按成单时刻定形态」的口径一致。
     * 取语言只有一条路（[UpdateUi.copyFor]，其内部就是 `UpdateCopy.of(UiPrefs.settingsLanguage(...))`）——
     * 本服务不许自己判系统 Locale，也不许再写一处 `UpdateCopy.of(`。
     */
    @Volatile
    private var notifyCopy: UpdateCopy? = null

    /** 取本单文案：没开跑过就现取一份并记住（心跳与下载线程都读这一个，避免反复读偏好）。 */
    private fun copy(): UpdateCopy =
        notifyCopy ?: UpdateUi.copyFor(applicationContext).also { notifyCopy = it }

    /** 心跳只在一条下载真的在跑时挂；stop 走 [runDownload] 的 finally，绝不漏。 */
    private val heartbeatActive = AtomicBoolean(false)

    /**
     * 进度取数与渲染的共享锁（EP4 U1-①②）：写方是下载线程的进度回调，读方是主线程心跳。
     *
     * 主线程 Handler 不是「第二条线程」（契约 §0 不许在服务里加线程），用的就是服务本来就有的主线程；
     * 所有读数只在锁内动，通知刷新留在锁外（notify 是跨进程调用，不许抱锁做）。
     */
    private val progressLock = Any()

    private val mainHandler = Handler(Looper.getMainLooper())

    private var speedSamples: List<UpdateProgressText.ProgressSample> = emptyList()
    private var latestDoneBytes = 0L
    private var latestTotalBytes = 0L
    private var peakDoneBytes = 0L
    private var lastByteAtMillis = 0L
    private var downloadStartedAtMillis = 0L
    private var lastNotifyAtMillis = 0L
    private var lastNotifiedText = ""
    private var lastNotifiedPercent = -1

    /** 主线程心跳：静默期间（退避等待、换地址、本机重读前缀）进度回调根本不发生，靠它把状态行刷出来。 */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!heartbeatActive.get()) return
            refreshProgress(System.currentTimeMillis())
            mainHandler.postDelayed(this, PROGRESS_HEARTBEAT_MILLIS)
        }
    }

    /** U5 进度读数暴露区：把本实例登记给进程内只读口，设置页才读得到活字段。 */
    override fun onCreate() {
        super.onCreate()
        exposedService = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // U5 进度读数暴露区：实例没了就摘登记，外面读到 IDLE（没有下载在跑）。
        exposedService = null
        // 心跳只挂主线程 Handler：服务没了就必须摘，否则一条没人接的 Runnable 会在主线程上一直排下去。
        heartbeatActive.set(false)
        mainHandler.removeCallbacks(heartbeat)
        // U2 病① 兜底：系统可能不经 onTimeout 直接销毁服务（内存回收、卸载、时限后的强杀）。
        // 判定矩阵见 UpdateDownloadPlan.onDestroyFallbackSteps：闸还挂着 = 正常收尾没走完，必须兜 ——
        // worker 活着就置标志 + interrupt（让线程自己的取消收尾跑完，.part 保留），按矩阵放闸
        // （releaseGate 自判 gateHeld，幂等），原单记进失败单（内存 + 盘），补发「已暂停」通知。
        // L4-11：系统时限路（onTimeout 已置 systemTimedOut）矩阵**不含 ReleaseGate** ——
        // onTimeout 的 stopSelf 之后本回调紧跟着来，worker 还在 interrupt 生效前的读阻塞里，
        // 提前放闸会让下一单与旧 worker 同写一个 .part；闸归 worker 的取消收尾放。
        // 补发整段 runCatching 包死：服务都进了 onDestroy，任何抛出都会把销毁流程本身炸掉。
        // 成功/取消的正常收尾不进这支：runDownload 的 finally 已先放闸，这里 gateHeld 必为 false。
        val fallbackSteps = UpdateDownloadPlan.onDestroyFallbackSteps(
            gateHeld,
            worker?.isAlive == true,
            systemTimedOut.get(),
        )
        if (fallbackSteps.isNotEmpty()) {
            if (UpdateDownloadPlan.FgsTimeoutStep.CancelWorker in fallbackSteps) {
                systemTimedOut.set(true)
                cancelFlag.set(true)
                worker?.interrupt()
            }
            if (UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate in fallbackSteps) releaseGate()
            activeJob?.let { rememberFailedJob(applicationContext, it) }
            runCatching {
                val copy = copy()
                postResultNotification(
                    copy.systemPausedNotifyTitle,
                    copy.systemPausedLine,
                    activeJob?.let { retryIntent(applicationContext, it) },
                )
            }.onFailure { Log.w(TAG, "销毁兜底的暂停通知发不出去", it) }
        }
        super.onDestroy()
    }

    /**
     * Android 15+（API 35）dataSync 型前台服务的系统时限到点回调（U2 病①）。
     *
     * 官方口径（TODO(verify) 已核）：回调签名是**双参** `Service.onTimeout(int startId, int fgsType)`，
     * Android 15 引入 —— API diff 34→35 只给 Service 新增这一个方法
     * （developer.android.google.cn/sdk/api_diff/35/changes/android.app.Service）；行为变更页
     * （developer.android.google.cn/about/versions/15/behavior-changes-15「数据同步前台服务超时行为」）
     * 明说：应用的全部 dataSync 服务在 24 小时内共享 6 小时预算（用户把应用调到前台会重置），
     * 到点系统回调本方法，服务只有几秒钟调 stopSelf 的窗口，不停止则 RemoteServiceException。
     * 时钟归系统：回调没被调到，就当预算还在（本服务不自设计时器）。
     *
     * 行为 = 「系统请求的取消」，按 [UpdateDownloadPlan.fgsTimeoutSteps] 的判定矩阵执行：
     * 置取消标志 + interrupt 工作线程，复用 runDownload 的取消收尾（`.part` 原样保留，
     * UpdateManager 的中断分支返回「断点已保留」UpdateManager.kt:560-562；闸随 worker 的
     * finally 放，这里提前放会把收尾竞态窗口放大到一次读阻塞的量级）；结果通知与 Toast 用
     * 三语表新句（[UpdateCopy.systemPausedLine]，与玩家自己的取消分开说）；原单记进失败单
     * （内存 + 盘）供重放；然后 stopSelf —— 系统的时限闸以「服务停了」为准。
     * 通知、记账整段 runCatching 包死：回调之后系统随时可能撤前台/杀服务，收尾不许抛。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT < 35) return
        Log.i(TAG, "系统时限到点（dataSync 前台服务 onTimeout），按系统请求的取消收尾")
        val steps = UpdateDownloadPlan.fgsTimeoutSteps(Build.VERSION.SDK_INT, worker?.isAlive == true)
        for (step in steps) {
            when (step) {
                UpdateDownloadPlan.FgsTimeoutStep.CancelWorker -> {
                    systemTimedOut.set(true)
                    cancelFlag.set(true)
                    worker?.interrupt()
                }
                // 时限路不放闸（见 KDoc）：这一格只对 onDestroy 兜底路有意义。
                UpdateDownloadPlan.FgsTimeoutStep.ReleaseGate -> Unit
                UpdateDownloadPlan.FgsTimeoutStep.StopSelf -> stopSelf(startId)
            }
        }
        val job = activeJob
        if (job != null) rememberFailedJob(applicationContext, job)
        val copy = copy()
        runCatching {
            postResultNotification(
                copy.systemPausedNotifyTitle,
                copy.systemPausedLine,
                job?.let { retryIntent(applicationContext, it) },
            )
        }.onFailure { Log.w(TAG, "系统时限暂停通知发不出去", it) }
        honestToast(copy.systemPausedLine)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val handled = runCatching {
            when (intent?.action) {
                ACTION_CANCEL -> {
                    cancelFlag.set(true)
                    worker?.interrupt()
                    true
                }

                ACTION_INSTALL -> {
                    val path = intent.getStringExtra(EXTRA_APK_PATH)
                    if (path != null) openInstaller(path)
                    // L4-7：安装入口是「一次性意图」——处理完就退场，不留下常驻空服务
                    // （旧实现只有异常支会 stopSelf，点一次安装就留一个已启动服务到进程死）。
                    // 只在**没有在飞下载**时停：下载的收尾归 worker 的 finally，误停会触发
                    // onDestroy 兜底把在飞下载取消掉。
                    if (!gateHeld) stopSelf(startId)
                    true
                }

                else -> beginDownloadSafe(intent, startId)
            }
        }
        if (handled.isFailure) {
            Log.w(TAG, "更新服务处理意图异常", handled.exceptionOrNull())
            // FGS 闸（I 簇）：异常收场同样要先过 startForeground 再退 —— 抛点可能在
            // startForeground 之前，也可能已经过了（重复调一次无害，只是更新通知）。
            enterForegroundThenStop(startId)
            // 单飞闸**不在这里放**：能走到这条外层兜底的只有 CANCEL / INSTALL 两支，它们的
            // 异常与闸无关；闸在飞时误放一次，就是把在飞下载暴露成双开。下载支路的闸
            // 由 [beginDownloadSafe] 自己收（只放 [gateHeld] 记账的那一把）。
            stopSelf(startId)
        }
        // 死了不自动重开：续传靠玩家再点一次 + 盘上 .part，不需要系统在背后反复拉起。
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------
    // 下载主线
    // ------------------------------------------------------------------

    /**
     * 下载支路的意图入口：残缺意图走 FGS 过闸退场，正常单子交 [beginDownload]。
     *
     * 异常在本地收口（不再抛给 [onStartCommand] 的外层兜底）：单飞闸只放 [gateHeld]
     * 记账的那一把 —— CANCEL / INSTALL 支路的异常与闸无关，走外层兜底时不许碰它。
     */
    private fun beginDownloadSafe(intent: Intent?, startId: Int): Boolean {
        val job = intent?.let { jobFrom(it) }
        if (job == null) {
            Log.w(TAG, "下载服务收到残缺的启动意图，忽略")
            // FGS 闸（收件箱 2026-11-03 I 簇）：startForegroundService 进来的意图
            // 哪怕残缺，也必须先调一次 startForeground 再退场，否则系统按
            // ForegroundServiceDidNotStartInTimeException 杀整进程。
            enterForegroundThenStop(startId)
            return false
        }
        return try {
            beginDownload(job, startId)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "下载服务处理意图异常", t)
            // FGS 闸与单飞闸都在这条过闸退场里收：[gateHeld] 只在 beginDownload 真抢到闸
            // 之后才置位，这里放闸不会误伤别人的在飞下载；activeJob 同理 —— 抢闸失败路
            // （busy）的异常若也从这过，activeJob 是在飞下载的，一个字都不许动。
            enterForegroundThenStop(startId)
            if (gateHeld) {
                releaseGate()
                activeJob = null
            }
            false
        }
    }

    private fun beginDownload(job: DownloadJob, startId: Int) {
        // FGS 闸（收件箱 2026-11-03 I 簇）：startForegroundService 起来的服务，任何 stopSelf
        // 之前都必须调过一次 startForeground，否则系统按 ForegroundServiceDidNotStartInTimeException
        // 直接杀整进程（OnePlus PYB110 / API 37 实录，点「下载」就崩）。
        //
        // 单飞闸挪到**一切本单状态改动之前**（2026-11-04 复查收口）：旧序先把 activeJob、
        // 文案、取消标志与进度追踪全换成新单再去抢闸，「已有下载在跑」的早退路把这些改动
        // 原样留在在飞下载身上 —— renderProgressLocked 读不到 activeJob 就不再刷进度，
        // stopForegroundCompat 还会把在飞那条进度通知连同前台态一起撤掉（下载变成无通知
        // 无前台的裸线程）。先抢闸：抢不到只答一句「已经有一个下载在跑」，在飞单零扰动；
        // 服务此刻必然已在前台（闸在飞 ⇒ 本实例已 startForeground 过进度通知），
        // 对同一条通知 id 重申一次前台只是合规重申，不换单不撤通知。
        if (!UpdateManager.tryBeginDownload()) {
            // 在飞单零扰动：文案用 copy() 的缓存（在飞单开跑那一刻定的语言），前台对在飞那条
            // 进度通知做一次同 id 重申（FGS 合规），只答一句「已经有一个下载在跑」。
            reassertForegroundWhileBusy()
            honestToast(copy().downloadBusyToast)
            return
        }
        gateHeld = true
        activeJob = job
        // 一单一定文案：抢闸之后才取，玩家在上一个下载之后换过语言也按新语言报。
        notifyCopy = UpdateUi.copyFor(applicationContext)
        val copy = copy()
        cancelFlag.set(false)
        systemTimedOut.set(false)
        ensureChannels()
        val now = System.currentTimeMillis()
        resetProgressTracking(job.artifact.sizeBytes, now)
        val first = buildProgressNotification(
            UpdateProgressText.progressText(copy, job.kind, 0L, job.artifact.sizeBytes),
            0L,
        )
        val foregrounded = runCatching { startForegroundCompat(NOTIFICATION_PROGRESS, first) }
        if (foregrounded.isFailure) {
            Log.w(TAG, "前台通知起不来，本次不开下载", foregrounded.exceptionOrNull())
            releaseGate()
            activeJob = null
            honestToast(
                copy.fill(
                    copy.noNotifyBarStartFailedFormat,
                    foregrounded.exceptionOrNull()?.javaClass?.simpleName ?: "null",
                ),
            )
            stopSelf(startId)
            return
        }
        // 漏斗遥测拍 4 起（U6）：抢到闸、前台合规过了 —— 这张单是真的要开始下了。
        // 「抢闸失败（已有下载在跑）」与「前台通知起不来」两支都不发：那两种形状下没有下载在跑。
        UpdateTelemetryWiring.downloadStarted(applicationContext, job.kind, job.assetsVersion)
        val thread = Thread {
            runCatching { runDownload(job) }
                .onFailure {
                    Log.w(TAG, "下载线程异常收场", it)
                    // 线程直接抛了 = 一条下载都没跑起来：类别未知（null），但补救面照给（Toast 不靠通知权限）。
                    val visible = notificationsVisible()
                    rememberFailedJob(applicationContext, job)
                    postResultNotification(
                        copy.notStartedNotifyTitle,
                        UpdateProgressText.failedText(copy, "", null, visible),
                        retryIntent(applicationContext, job),
                    )
                    honestToast(UpdateProgressText.failureToast(copy, null))
                    // 同一拍的遥测：管线没给出类别（failureKind 为 null），异常名交进去当类别标签，
                    // 免得这一类失败在漏斗里和「地址全试完」混成同一条。
                    UpdateTelemetryWiring.downloadFailed(
                        applicationContext,
                        job.kind,
                        progress().doneBytes,
                        null,
                        job.assetsVersion,
                        it.javaClass.simpleName,
                    )
                }
            stopForegroundCompat()
            stopSelf()
            // 冲刷点之二（U6，P2：收尾顺序）：放在退场**之后**且走异步 —— 旧写法在收尾里同步
            // 走两档超时（最坏 ~10s），期间那条 ongoing 进度通知还挂在通知栏、服务还占着前台预算。
            UpdateTelemetryWiring.flushPendingAsync(applicationContext)
        }.apply { isDaemon = true; name = "eratw-update-download" }
        worker = thread
        thread.start()
    }

    private fun runDownload(job: DownloadJob) {
        val copy = copy()
        heartbeatActive.set(true)
        mainHandler.post(heartbeat)
        try {
            val outcome = UpdateManager.download(applicationContext, job.artifact) { done, total ->
                if (cancelFlag.get()) throw InterruptedException("玩家取消了下载")
                val now = System.currentTimeMillis()
                recordProgress(done, total, now)
                refreshProgress(now)
            }
            val notifyVisible = notificationsVisible()
            val file = outcome.file
            // 文件已核对落盘就按完成报：取消只取消「还没下完的等待」，不许把已到手的产物吞掉。
            when {
                file != null -> {
                    // 成功一单清空（盘 + 内存）：失败单的使命到「真的下成」为止；开始下载不清（U2 病②）。
                    clearFailedJob(applicationContext)
                    // 漏斗遥测拍 4 成（U6）：字节到位且 sha 核对过。数据包的「登记待应用」随后可能
                    // 单独失败（下面那一支），但那是应用侧的事，「下成了」这一格已经成立。
                    UpdateTelemetryWiring.downloadFinished(
                        applicationContext,
                        job.kind,
                        job.artifact.sizeBytes,
                        job.assetsVersion,
                    )
                    when (job.kind) {
                        UpdateDownloadKind.Apk -> {
                            if (notifyVisible) {
                                val done = UpdateProgressText.apkDoneText(copy, true)
                                postResultNotification(
                                    copy.apkReadyTitle,
                                    done,
                                    installIntent(applicationContext, file.absolutePath),
                                )
                            } else {
                                // P1：通知看不见就别指望「点通知安装」——同一拍直接把已核对的包
                                // 交给系统安装器（这是拒绝通知权限的玩家唯一的同屏可达入口）。
                                val problem = installJustDownloaded(file)
                                honestToast(problem ?: UpdateProgressText.apkDoneText(copy, false))
                            }
                        }

                        UpdateDownloadKind.Assets -> {
                            val staged = runCatching {
                                UpdateManager.stageAssetsBundle(applicationContext, file, job.assetsVersion, job.artifact, UpdateManager.installedDataVersionOf(applicationContext))
                            }
                            if (staged.isFailure) {
                                Log.w(TAG, "数据包暂存登记失败", staged.exceptionOrNull())
                                reportFailure(
                                    job,
                                    copy.assetsRegisterFailedTitle,
                                    // 原话直取：这一支不是「下不下来」，是「下好了没登记成待应用」，
                                    // 重放会走「文件已核对过就直接用」那条路（UpdateManager.kt:541-545）。
                                    UpdateProgressText.failedText(
                                        copy,
                                        copy.assetsRegisterProblemLine,
                                        null,
                                        notifyVisible,
                                    ),
                                    retryIntent(applicationContext, job),
                                    notifyVisible,
                                    null,
                                )
                            } else {
                                // 只说「下次启动时自动应用」：此刻还没生效，不许提前报喜（契约 §0.4）。
                                val done = UpdateProgressText.assetsDoneText(copy)
                                postResultNotification(copy.assetsReadyTitle, done, null)
                                if (!notifyVisible) honestToast(done)
                            }
                        }
                    }
                }

                // 系统时限到点（U2 病①）：onTimeout 已置标志并发过 Toast；这里兜底重画同一条
                // 通知（同 id 同内容，幂等），挡住「cancelFlag 也是真」把系统暂停说成玩家取消。
                systemTimedOut.get() -> {
                    // 不报漏斗：系统时限到点既不是玩家取消也不是下载失败（core 的取消事件专指玩家
                    // 按「取消下载」，失败事件专指管线判出的失败类别），把它记进任一格都是编读数。
                    postResultNotification(
                        copy.systemPausedNotifyTitle,
                        copy.systemPausedLine,
                        retryIntent(applicationContext, job),
                    )
                }

                cancelFlag.get() -> {
                    val cancelled = UpdateProgressText.cancelledText(copy, notifyVisible)
                    postResultNotification(copy.cancelledNotifyTitle, cancelled, null)
                    if (!notifyVisible) honestToast(cancelled)
                    // 漏斗遥测拍 4 取消（U6）：玩家按了「取消下载」（断点留在盘上，续传口径不变）。
                    UpdateTelemetryWiring.downloadCancelled(applicationContext, job.kind, job.assetsVersion)
                }

                else -> {
                    // 漏斗遥测拍 4 败（U6）：类别交现成的 FailureKind（null = 地址全试完，
                    // 装配层落 exhausted）；sizeBytes 交已落盘字节，它是「下到哪里断的」唯一读数。
                    UpdateTelemetryWiring.downloadFailed(
                        applicationContext,
                        job.kind,
                        progress().doneBytes,
                        outcome.kind,
                        job.assetsVersion,
                    )
                    reportFailure(
                        job,
                        copy.failedNotifyTitle,
                        UpdateProgressText.failedText(copy, outcome.problem, outcome.kind, notifyVisible),
                        retryIntent(applicationContext, job),
                        notifyVisible,
                        // 读现成的：UpdateManager 在「所有候选地址都试完」那一支不带类别（UpdateManager.kt:519），
                        // 那时 kind 为 null，本层的映射自有一句话，不许在这儿另造第二套分类。
                        outcome.kind,
                    )
                }
            }
        } finally {
            heartbeatActive.set(false)
            mainHandler.removeCallbacks(heartbeat)
            // 单飞闸必须在这里放：download 的契约是「调用方负责 release」，异常路径也不许留死闸。
            releaseGate()
            worker = null
            activeJob = null
        }
    }

    /**
     * 通知看不见时的**同屏安装入口**（P1）：整包下完就在这一拍直接把已核对的包交给系统安装器。
     *
     * 为什么不等玩家点通知：没给通知权限（Android 13+ 新装默认关）或进度渠道被单独关掉时，
     * 完成通知根本不出现，等不到那一下点击 —— 旧行为是发一条看不见的通知 + 一句「再点一次下载
     * 就会交给安装器」，而再次下载只会命中「已核对文件直接返回」快路径、又回到同一个死循环。
     *
     * 与 [openInstaller] 的分工：那个是通知点击（ACTION_INSTALL）的入口，校验口径相同
     * （只出更新目录、必须是文件）；这里只服务「刚下完、就在本服务里」这一拍，所以用同一套
     * 校验但独立成函数 —— ACTION_INSTALL 面的接线归另一条车道，两边互不牵连。
     *
     * @return 需要上屏的 problem 原话；null = 已经交给系统（会话已提交 / 旧路已拉起）。
     */
    private fun installJustDownloaded(file: File): String? {
        val copy = copy()
        val inDownloadDir = runCatching {
            val dir = UpdateManager.downloadDir(applicationContext)
            file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!inDownloadDir || !file.isFile) return copy.apkMissingToast
        val result = runCatching { UpdateManager.launchInstaller(applicationContext, file) }
            .getOrElse { copy.fill(copy.installerOpenFailedFormat, it.javaClass.simpleName) }
        if (result != null && result != UpdateManager.SESSION_SUBMITTED_SIGNAL) return result
        // 只有「真的交给了系统」才算一次安装请求（与 openInstaller 的遥测口径同源）。
        UpdateTelemetryWiring.installRequested(
            applicationContext,
            runCatching { UpdateManager.currentVersionCode(applicationContext) }.getOrDefault(0L),
        )
        Log.i(TAG, "通知不可见：整包下完直接交给系统安装器（不等通知点击）")
        return null
    }

    /**
     * 失败的上屏（EP4 U1-③④）：通知按能不能看见写正文，**Toast 无条件补一条**。
     *
     * Toast 不依赖通知权限，是「不给通知权限的玩家也能看到该怎么办 + 去哪儿重试」的最后一条同屏面；
     * 它只有一行的余地，所以送 [UpdateProgressText.failureToast]（类别化的处置句，不带原话与术语）。
     */
    private fun reportFailure(
        job: DownloadJob,
        title: String,
        text: String,
        contentIntent: Intent?,
        notifyVisible: Boolean,
        kind: UpdateDownloadPlan.FailureKind?,
    ) {
        rememberFailedJob(applicationContext, job)
        postResultNotification(title, text, contentIntent)
        honestToast(UpdateProgressText.failureToast(copy(), kind))
    }

    // ------------------------------------------------------------------
    // 进度取数（判定全在 UpdateProgressText，这里只搬字节与时钟）
    // ------------------------------------------------------------------

    private fun resetProgressTracking(totalBytes: Long, nowMillis: Long) {
        synchronized(progressLock) {
            // 窗口**不**塞 0 字节的起点：续传时第一个回调带来的就是盘上前缀的字节数，
            // 凭空补一个 0 点会把「上次会话下完的那几百 MiB」算成本次的速度 —— 那是编数。
            speedSamples = emptyList()
            latestDoneBytes = 0L
            latestTotalBytes = totalBytes
            peakDoneBytes = 0L
            lastByteAtMillis = nowMillis
            downloadStartedAtMillis = nowMillis
            lastNotifyAtMillis = 0L
            lastNotifiedText = ""
            lastNotifiedPercent = -1
        }
    }

    /** 下载线程每读着一批就调一次：刷新最新读数、峰值与窗口，并标下「这一次真的有新内容进来」的时刻。 */
    private fun recordProgress(doneBytes: Long, totalBytes: Long, nowMillis: Long) {
        synchronized(progressLock) {
            if (doneBytes != latestDoneBytes) lastByteAtMillis = nowMillis
            if (doneBytes > peakDoneBytes) peakDoneBytes = doneBytes
            if (totalBytes > 0L) latestTotalBytes = totalBytes
            latestDoneBytes = doneBytes
            speedSamples = UpdateProgressText.withSampleAdded(
                speedSamples,
                UpdateProgressText.ProgressSample(doneBytes, nowMillis),
                nowMillis,
            )
        }
    }

    /**
     * 渲染并刷新进度通知（下载线程与主线程心跳共用）。
     *
     * 节流两条：距上次刷新不足 [NOTIFY_MIN_INTERVAL_MILLIS] 且还没下完就跳过；正文与百分比都没变也跳过
     * —— 静默期间心跳每 [PROGRESS_HEARTBEAT_MILLIS] 毫秒跑到这里，绝大多数时候一个字都不该动。
     * 判定只在 [UpdateProgressText.progressText] 快照版里做一次，这里连「该不该说在等待」都不参与。
     */
    private fun refreshProgress(nowMillis: Long) {
        val render = synchronized(progressLock) { renderProgressLocked(nowMillis) } ?: return
        showProgress(render.first, render.second)
    }

    /** 锁内的渲染 + 记账：返回 null = 这次不该刷通知。percent 与正文一起算，保证两者永远同源。 */
    private fun renderProgressLocked(nowMillis: Long): Pair<String, Long>? {
        val job = activeJob ?: return null
        val total = if (latestTotalBytes > 0L) latestTotalBytes else job.artifact.sizeBytes
        val done = latestDoneBytes
        val snapshot = UpdateProgressText.ProgressSnapshot(
            doneBytes = done,
            totalBytes = total,
            samples = speedSamples,
            nowMillis = nowMillis,
            peakDoneBytes = peakDoneBytes,
            silentMillis = (nowMillis - lastByteAtMillis).coerceAtLeast(0L),
            elapsedMillis = (nowMillis - downloadStartedAtMillis).coerceAtLeast(0L),
        )
        val text = UpdateProgressText.progressText(copy(), job.kind, snapshot)
        val percent = if (total > 0L) ((done * 100L) / total).coerceAtMost(100L) else 0L
        val due = nowMillis - lastNotifyAtMillis >= NOTIFY_MIN_INTERVAL_MILLIS || (total > 0L && done >= total)
        if (!due || (text == lastNotifiedText && percent == lastNotifiedPercent.toLong())) return null
        lastNotifyAtMillis = nowMillis
        lastNotifiedText = text
        lastNotifiedPercent = percent.toInt()
        return text to percent
    }

    /**
     * 通知到底看不看得见（EP4 U1-④ 的判据；P2-10 起加**渠道级**）。
     *
     * 判据统一收在 [UpdateUi.notificationsVisible]：应用级开关 + 进度渠道的 importance 两档，
     * 本服务与设置页共用同一份读数（同一件事不许两处各判一次，判歪了就是两套话术）。
     * 读数取不到时那边按「看得见」处理 —— 宁可少发一条 Toast，也不许对玩家说瞎话。
     */
    private fun notificationsVisible(): Boolean = UpdateUi.notificationsVisible(applicationContext)

    /** 系统通知服务：API 23 起才有 Class 形态的 getSystemService，21/22 走字符串形态。 */
    private fun notificationManager(): NotificationManager? = runCatching {
        if (Build.VERSION.SDK_INT >= 23) {
            getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        }
    }.getOrNull()

    private fun showProgress(text: String, percent: Long) {
        runCatching {
            notificationManager()?.notify(NOTIFICATION_PROGRESS, buildProgressNotification(text, percent))
        }.onFailure { Log.w(TAG, "进度通知刷新失败（下载继续跑）", it) }
    }

    /** 完成/失败通知：contentIntent 为空就只是「看一眼」，非空即「点一下有下一步」。 */
    private fun postResultNotification(title: String, text: String, contentIntent: Intent?) {
        runCatching {
            val manager = notificationManager() ?: return
            var builder = builderFor(CHANNEL_RESULT, android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
            if (contentIntent != null) {
                builder = builder.setContentIntent(
                    PendingIntent.getService(applicationContext, 0, contentIntent, pendingFlags()),
                )
            }
            manager.notify(NOTIFICATION_RESULT, builder.build())
        }.onFailure { Log.w(TAG, "结果通知发不出去（只影响提醒，不影响下载产物）", it) }
    }

    /**
     * 通知点击 → 把已核对的 APK 交给系统安装器（handoff 登记在 UpdateManager 里）。
     *
     * 返回值分档（CONTRACT-UPD2 §2 U7）：API 29+ 走 PackageInstaller 会话，
     * [UpdateManager.launchInstaller] 返回 [UpdateManager.SESSION_SUBMITTED_SIGNAL]——
     * **对这个信号不上屏**：会话是异步的，成功/用户取消/失败三类回执由会话侧
     * （[PackageInstallerSession]）经广播送达并自己落结果通知与 Toast，这里提前说话就是谎报。
     * 29 以下旧 ACTION_VIEW 路的 problem 原话照旧 Toast（原话直取的既有口径不扩语言面）。
     */
    private fun openInstaller(path: String) {
        val copy = copy()
        val file = File(path)
        val inDownloadDir = runCatching {
            val dir = UpdateManager.downloadDir(applicationContext)
            file.canonicalPath.startsWith(dir.canonicalPath + File.separator)
        }.getOrDefault(false)
        if (!inDownloadDir || !file.isFile) {
            honestToast(copy.apkMissingToast)
            return
        }
        // 另一支（launchInstaller 自己返回的 problem 原话）出自 UpdateManager，本波不扩它的语言面：
        // 那是「原话直取」的既有口径，写进 U3b 交付报告的接线清单。
        val result = runCatching { UpdateManager.launchInstaller(applicationContext, file) }
            .getOrElse {
                copy.fill(
                    copy.installerOpenFailedFormat,
                    it.javaClass.simpleName,
                )
            }
        // 漏斗遥测拍 4 安装请求（U6）：只有「真的交给了系统」才算 ——
        // null = 29 以下的 ACTION_VIEW 已起，SESSION_SUBMITTED_SIGNAL = 29+ 会话已提交；
        // 其余都是 problem 原话（权限没放行 / 打不开），一次安装请求都没发生，不报。
        // 装成/装废的最终回执接不了：core 的事件白名单里没有 install_result（见装配层类头与交付）。
        if (result == null || result == UpdateManager.SESSION_SUBMITTED_SIGNAL) {
            UpdateTelemetryWiring.installRequested(
                applicationContext,
                runCatching { UpdateManager.currentVersionCode(applicationContext) }.getOrDefault(0L),
            )
        }
        if (result != null && result != UpdateManager.SESSION_SUBMITTED_SIGNAL) honestToast(result)
    }

    // ------------------------------------------------------------------
    // 通知构造（minSdk 21 分档：API 26 起才有渠道）
    // ------------------------------------------------------------------

    /**
     * 从启动意图还原那张单。
     *
     * EP4 U3b：单子给玩家看的名字**不再硬写中文**，按当前语言的三语表现推
     * （[UpdateProgressText.labelFor]）。它只是给「读 `label` 的旧调用方」留着的读数 ——
     * 通知正文**不取它**（渲染期自己按 `kind` + `copy` 推），否则就是「成单那一刻的语言」
     * 与「看通知这一刻的语言」混在一行里。
     */
    private fun jobFrom(intent: Intent): DownloadJob? = runCatching {
        val name = intent.getStringExtra(EXTRA_FILE_NAME) ?: return null
        // L8 接线：意图里的摘要是「跨进程/跨冷启」带回来的字符串，先过 core 的归一/形状校验
        // 再成单 —— 坏摘要整单作废，不让它带着垃圾 sha 下到末尾才由 DigestMismatch 收场。
        val sha = UpdatePolicy.normalizeSha(intent.getStringExtra(EXTRA_SHA256) ?: return null) ?: return null
        val size = intent.getLongExtra(EXTRA_SIZE_BYTES, -1L)
        if (!UpdatePolicy.isArtifactFileName(name) || size <= 0L) return null
        val kind = runCatching { UpdateDownloadKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "") }
            .getOrNull() ?: return null
        // L8 接线：与 UpdateFileProvider 的 allowlist 同口径 —— 交给安装器的只可能是 `.apk`。
        // kind=Apk 却在意图里带个 zip/tmp（历史形状或伪造 intent）就在这里作废，
        // 否则下载完几百 MiB 才在 Provider 的 SecurityException 上失败（玩家白跑流量）。
        if (kind == UpdateDownloadKind.Apk && !name.endsWith(UpdateDownloadPlan.APK_SUFFIX)) return null
        DownloadJob(
            Artifact(name, size, sha),
            kind,
            UpdateProgressText.labelFor(copy(), kind),
            intent.getLongExtra(EXTRA_ASSETS_VERSION, 0L),
        )
    }.getOrNull()

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val copy = copy()
        runCatching {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, copy.channelProgressName, NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RESULT, copy.channelResultName, NotificationManager.IMPORTANCE_DEFAULT),
            )
        }.onFailure { Log.w(TAG, "通知渠道建不出来", it) }
    }

    private fun buildProgressNotification(text: String, percent: Long): Notification {
        val copy = copy()
        var builder = builderFor(CHANNEL_PROGRESS, android.R.drawable.stat_sys_download)
            .setContentTitle(copy.progressNotifyTitle)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0L, 100L).toInt(), false)
            .setContentIntent(
                PendingIntent.getActivity(
                    applicationContext,
                    0,
                    Intent(applicationContext, MainActivity::class.java),
                    pendingFlags(),
                ),
            )
        builder = builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            copy.cancelNotifyAction,
            PendingIntent.getService(applicationContext, 1, cancelIntent(applicationContext), pendingFlags()),
        )
        return builder.build()
    }

    /** 两条分支都要带 smallIcon：缺它某些 ROM 直接抛「Invalid notification (no valid small icon)」。 */
    private fun builderFor(channelId: String, smallIcon: Int): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder.setSmallIcon(smallIcon)
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)

    private fun startForegroundCompat(id: Int, notification: Notification) {
        startForeground(id, notification)
    }

    private fun stopForegroundCompat() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
    }

    /**
     * FGS 闸的过闸退路（收件箱 2026-11-03 I 簇）：startForegroundService 进来的意图走到
     * 残缺/异常这类没法正常开下载的分支时，也要先调一次 startForeground 再退场 ——
     * 否则系统按 ForegroundServiceDidNotStartInTimeException 杀整进程（OnePlus PYB110 /
     * API 37 实录）。亮一条标题通知、随即撤下，玩家感知不到这条通知存在过。
     */
    private fun enterForegroundThenStop(startId: Int) {
        runCatching {
            ensureChannels()
            startForegroundCompat(
                NOTIFICATION_PROGRESS,
                buildProgressNotification(copy().progressNotifyTitle, 0L),
            )
            stopForegroundCompat()
        }.onFailure { Log.w(TAG, "过闸通知起不来（照常退场）", it) }
        stopSelf(startId)
    }

    /**
     * 一句话提醒（EP4 U1-④ 的同屏补救面就靠它）。
     *
     * 必须回主线程发：下载跑在 daemon 线程上，而 Toast 内部要建 Handler，
     * 在没有 Looper 的线程里直接 show 会在部分系统上抛异常 —— 那条 Toast 恰好是「没给通知权限的玩家
     * 唯一看得见的失败处置」，发不出去就等于病 ④ 没结。
     */
    private fun honestToast(text: String) {
        Log.i(TAG, text)
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { showToast(text) }
        } else {
            showToast(text)
        }
    }

    private fun showToast(text: String) {
        runCatching { Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show() }
            .onFailure { Log.w(TAG, "Toast 都发不出去", it) }
    }
}
