package com.eratw.port.android

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.eratw.port.android.ui.EratwShellDialog
import com.eratw.port.core.update.UpdatePolicy
import com.eratw.port.core.update.UpdateTelemetry
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 自动更新的**触发与上屏**层（国内镜像仓通道，2026-10-29；U-E 收口波 2026-11-01）。
 *
 * ## 为什么单独一层
 *
 * [UpdateManager] 只会「问」和「下」；什么时候问、问完给玩家看什么、点了按钮之后跑哪一条
 * 全在本文件。选「启动检查 + 弹窗确认」是三条判据的合题：
 *
 * - **不静默下载**：一包几百 MiB，走蜂窝就是玩家的钱包；也不能假设玩家愿意自己的游戏数据
 *   在自己看不见的时候被换掉；
 * - **不指望玩家自己去点**：侧载的玩家不会翻设置页，而这条通道真正的价值就是
 *   「主人发了修复，老包自己爬得上来」，所以自动查、自动弹；
 * - **不在装载期抢资源**：检查延后到首屏画完之后（[STARTUP_DELAY_MILLIS]），与首启解包、
 *   装载抢 CPU/IO/带宽是启动耗时读数里最难看的一种回归。
 *
 * ## 两条通道只下一条
 *
 * 整包 APK 是数据包的同源超集（同一份产物 + 同一份语料，见 `app/build.gradle.kts` 的随包口径段），
 * 两个都下就是白跑几百 MiB。所以**有新版可装就只装新版**，只有数据包可更才走数据通道。
 * 数据包下的是**全量还是差量**由 core 判好（[UpdateManager.UpdateOffer.dataArtifact]），
 * 本层只搬这个结果，不重算（契约 §1.2）。数据包的「应用」不在这里做 —— 它落在下一次启动的
 * 装载前拍，理由见 [UpdateManager.applyStagedBundleIfPresent]。
 *
 * ## 一次只跑一路检查（EP4 U2-②）
 *
 * 自动那一发与设置页那颗按钮共用一把单飞闸（[checking]）：一次检查最坏要跑几十秒
 * （每条地址 8 秒取清单、逐条回退），玩家连点三次就是三条线程同时打同一个源。
 * 谁拿到闸谁负责放闸（[runCheck] 的成功与失败路径都放），按钮的进行中态由调用方按 [checkNowInteractive] 的返回值置位。
 *
 * 弹窗与通知的**文字判读**全部收在 [UpdateUiText] 与 [UpdateProgressText]（纯函数、有定向单测），
 * 设置页那条通道现状收在 [UpdateChannelReadout]；本对象只做 Android 侧的取数与编排。全程
 * `runCatching`：更新面把游戏搞崩是本末倒置，任何通知/服务异常只落 logcat
 * （与 [com.eratw.port.core.update.UpdatePolicy] 的「判错代价」表同源纪律）。
 */
object UpdateUi {

    private const val TAG = "EratwUpdate"

    /** 首屏画完之后隔多久才自动检查（毫秒）：给装载让路。 */
    const val STARTUP_DELAY_MILLIS = 12_000L

    /** [Activity.requestPermissions] 的请求码：通知授权回调里认它（只有这一处用运行时权限）。 */
    const val PERMISSION_REQUEST_NOTIFICATIONS = 0x5703

    private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * 等通知授权回执的上限（毫秒，P1-2 兜底）：回执因为「Activity 没转发」或「界面被回收」丢了时，
     * 这一单不许永远挂着 —— 到点就按「授权与否都不影响下载」直接用应用上下文起服务。
     *
     * 取 90 秒：够玩家读完系统弹窗再作答，又短到不会把「点了下载没反应」变成常态。
     */
    private const val PENDING_START_TIMEOUT_MILLIS = 90_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 更新面取文案的**唯一**入口（契约 EP4 U3 §1 硬规矩 1）：语言与设置页同源，
     * 全仓 `UpdateCopy.of(` 只在这里出现一次，实参只有 [UiPrefs.settingsLanguage]。
     *
     * 本层不判系统 Locale、不为更新另立一套语言开关；设置页切了语言，弹窗与通道现状
     * 下一次上屏就跟着切（冷启自动那一发也一样，它读的就是同一份偏好）。
     */
    fun copyFor(context: Context): UpdateCopy = UpdateCopy.of(UiPrefs.settingsLanguage(context))

    /** 本进程的自动检查只做一次（回前台、Activity 重建都不重复弹）。 */
    @Volatile
    private var startupCheckDone = false

    /** 冷启首帧的 onResume 不算「回前台」：先让首屏画完、冷启那一发拿到机会。 */
    @Volatile
    private var firstResumeSeen = false

    /** 检查的单飞闸（口径见类头）：与 [UpdateManager.tryBeginDownload] 同形态，只做互斥不做判读。 */
    private val checking = AtomicBoolean(false)


    /**
     * 「等通知授权回调再开下载」的一次性续跑登记：玩家点了「下载」、系统在问授权，
     * 回调来了才把这一单跑完。只存一个（弹窗本身是单发的），Activity 只持弱引用 ——
     * 授权对话框挂着时 Activity 被回收是常态，不许把死的 Activity 钉在进程里；
     * 但**应用上下文与单子本身**要留下来：界面没了也要把这一单跑完（P1-2，不许静默丢单）。
     *
     * @param enqueuedAtMillis 入队时刻：超过 [PENDING_START_TIMEOUT_MILLIS] 还没回执就按「回执丢了」处理。
     */
    private class PendingStart(
        val activity: WeakReference<Activity>,
        val job: DownloadJob,
        val enqueuedAtMillis: Long,
        val appContext: Context,
    )

    private var pendingStart: PendingStart? = null

    /**
     * 等授权那一单的超时兜底（P1-2）：回执丢在「Activity 没转发 / 界面被回收」上时，
     * 这一单不许永远挂着——到点直接用应用上下文起服务。
     *
     * 为什么必须有一条：旧实现只在 `onRequestPermissionsResult` 里清 pendingStart，
     * 设置页没转发回调时它永远不空，之后每一次「下载」都被当成「授权还没回来」静默吞掉，
     * 直到进程重启。
     */
    private val pendingStartTimeout = Runnable {
        val pending = pendingStart ?: return@Runnable
        pendingStart = null
        Log.w(TAG, "等通知授权回执超时（回调没到？），直接用应用上下文开始这一单")
        startWithContext(pending.appContext, pending.job)
    }

    /**
     * 同屏只亮一条 offer 弹窗（P2 重复弹窗）：新弹窗出来前，把同一 Activity 上还亮着的旧弹窗收掉。
     *
     * 为什么会有两条：冷启自动检查的 12 秒定时器与手动检查各发一条，结果都可能成立；
     * 叠两层弹窗时玩家关掉上面那条，底下那条还是旧读数。
     */
    private var shownOfferDialog: WeakReference<AlertDialog>? = null
    private var shownOfferActivity: WeakReference<Activity>? = null

    // ------------------------------------------------------------------
    // 自动检查（冷启动一次）
    // ------------------------------------------------------------------

    /**
     * 挂一次冷启动自动检查：通道关、或距上次检查没到 [UpdatePolicy.CHECK_INTERVAL_MILLIS]
     * 就直接 return —— 一个字节都不出网。
     *
     * 检查失败**不弹**：所有镜像都取不到清单大概率是玩家在网络死角，弹一条「检查失败」只会
     * 让人以为游戏坏了。但「不弹」不等于「不记」：结果的形状与原话走 [runCheck] 落进偏好，
     * 设置页那条通道现状读得到（EP4 U2-③，旧行为是只进 logcat 就丢弃）。
     *
     * 「以后都不」的账在这里兑现：把 `UiPrefs.updateSkippedVersionCode` 传给
     * `UpdateManager.checkNow`（抑制判定在 core，只对同一个 versionCode 生效，契约 §1.5）。
     */
    fun scheduleStartupCheck(activity: Activity, gameRoot: File) {
        if (startupCheckDone) return
        if (!UpdateManager.channelEnabled(activity)) return
        if (!UpdatePolicy.isCheckDue(UpdateManager.lastCheckMillis(activity), System.currentTimeMillis())) {
            Log.i(TAG, "未到检查间隔，本次启动不查")
            return
        }
        startupCheckDone = true
        val app = activity.applicationContext
        mainHandler.postDelayed({
            if (!alive(activity)) {
                // P2：12 秒窗口里 Activity 被销毁/重建（显示大小档 recreate、系统回收……）：
                // 把「本进程已排过」退回去，让回来后的回前台补查能接手这一发 —— 否则本进程
                // 再也不会有自动检查（旧行为就是这个死结）。
                startupCheckDone = false
                Log.i(TAG, "冷启自动检查到点时界面已不在，交回回前台补查")
                return@postDelayed
            }
            // P2：手动检查可能已经在这个 12 秒窗口里查过（并弹过窗）——到点必须重新过 due 闸，
            // 不然一次启动会白出两次网，还可能把第二个 offer 弹窗叠在第一个上面。
            if (!UpdatePolicy.isCheckDue(UpdateManager.lastCheckMillis(app), System.currentTimeMillis())) {
                Log.i(TAG, "12 秒窗口内已经查过（未到间隔），本次冷启自动检查不再出网")
                return@postDelayed
            }
            val skipped = runCatching { UiPrefs.updateSkippedVersionCode(app) }.getOrDefault(0L)
            val addressCount = runCatching { UpdateManager.mirrorCandidates(app).size }.getOrDefault(0)
            // 手动检查正在跑就不重复出网：同一趟联网，结果与形状记数已经由那一发写好了。
            runCheck(
                app = app,
                gameRoot = gameRoot,
                skippedVersionCode = skipped,
                addressCount = addressCount,
                onBusy = { Log.i(TAG, "已有一次检查在跑，本次冷启自动检查不重复出网") },
                onCrashed = { error -> Log.w(TAG, "自动检查抛错", error) },
            ) { result ->
                if (result.fetchProblems.isNotEmpty()) {
                    Log.i(TAG, "自动检查没取到可用清单：" + result.fetchProblems.joinToString("；"))
                }
                if (result.warnings.isNotEmpty()) {
                    Log.i(TAG, "自动检查的清单提醒：" + result.warnings.joinToString("；"))
                }
                val offer = result.offer ?: return@runCheck
                if (alive(activity)) showOfferDialog(activity, offer, result.warnings)
            }
        }, STARTUP_DELAY_MILLIS)
    }

    /**
     * 回前台补查（U5 P1-6）：长驻进程冷启那一发若因「没到冷却 / 通道关」被跳过，
     * 本进程就再也没有自动检查了 —— 回到前台且冷却到了，这里补上那一发。
     *
     * 四闸全开才补，判据在纯函数 [UpdateUiText.shouldCheckOnResume]（有单测矩阵）：
     * 通道开 + 冷却已到 + 单飞闸空 + 本进程不到点（`startupCheckDone` 仍为 false）。
     * 补之前先把 `startupCheckDone` 置真：冷启那一发的口径一字不动，回前台也不会重复弹窗、重复出网。
     * 冷启首帧的 onResume 由 [firstResumeSeen] 先挡掉，不抢在首屏画完之前。
     */
    fun maybeCheckOnResume(activity: Activity, gameRoot: File) {
        if (!firstResumeSeen) {
            firstResumeSeen = true
            return
        }
        val due = runCatching {
            UpdatePolicy.isCheckDue(UpdateManager.lastCheckMillis(activity), System.currentTimeMillis())
        }.getOrDefault(false)
        val enabled = runCatching { UpdateManager.channelEnabled(activity) }.getOrDefault(false)
        if (!UpdateUiText.shouldCheckOnResume(enabled, due, checking.get(), startupCheckDone)) return
        startupCheckDone = true
        val app = activity.applicationContext
        val skipped = runCatching { UiPrefs.updateSkippedVersionCode(app) }.getOrDefault(0L)
        val addressCount = runCatching { UpdateManager.mirrorCandidates(app).size }.getOrDefault(0)
        runCheck(
            app = app,
            gameRoot = gameRoot,
            skippedVersionCode = skipped,
            addressCount = addressCount,
            onBusy = { Log.i(TAG, "已有一次检查在跑，回前台补查不重复出网") },
            onCrashed = { error -> Log.w(TAG, "回前台补查抛错", error) },
        ) { result ->
            val offer = result.offer ?: return@runCheck
            if (alive(activity)) showOfferDialog(activity, offer, result.warnings)
        }
    }

    // ------------------------------------------------------------------
    // 手动检查（设置页按钮）
    // ------------------------------------------------------------------

    /**
     * 设置页「立即检查更新」：无视冷却、**无视跳过抑制**（`checkNow` 的 `skippedVersionCode`
     * 传缺省 0 —— 玩家主动按的就是「这次必须给我说实话」），结果一律上屏（玩家主动问的，
     * 失败也要说，而且要说清是「包里没配源」还是「配了但连不上」——这两件事的处理人不同）。
     *
     * EP4 U2-② 的两条新增：
     * - **单飞闸**：拿不到闸就只上一句「已经在查了」，不再起第二条线程；
     * - **进行中态**：返回 `true` 表示「这一发真的起了」，调用方据此把按钮置灰并写「正在检查更新…」，
     *   结果回来后通过 [onSettled] 随改随消（每个上屏分支都调，一次不多一次不少）。
     *
     * @return 是否由本次调用发起了检查（`false` = 包里没配地址，或已经有一路在跑）
     */
    fun checkNowInteractive(activity: Activity, gameRoot: File, onSettled: () -> Unit = {}): Boolean {
        val copy = copyFor(activity)
        val builtIn = runCatching { UpdateManager.builtInMirrors(activity) }.getOrDefault(emptyList())
        if (builtIn.isEmpty()) {
            toast(activity, UpdateUiText.noBuiltInMirrorReport(copy))
            return false
        }
        // P2：玩家主动按只是授权**这一次**联网（`UpdateManager.checkNow` 要过 channelEnabled
        // 那道闸），所以这里临时打开、查完立刻恢复原值 —— 旧行为会把玩家关掉的自动检查
        // **永久**打开（设置页第一行尾巴跟着变），连遥测通道也一起开。
        val autoWasEnabled = runCatching { UiPrefs.autoUpdateEnabled(activity) }.getOrDefault(true)
        if (!autoWasEnabled) UiPrefs.setAutoUpdateEnabled(activity, true)
        val app = activity.applicationContext
        val restoreAutoUpdate = {
            if (!autoWasEnabled) runCatching { UiPrefs.setAutoUpdateEnabled(app, false) }
        }
        val addressCount = runCatching { UpdateManager.mirrorCandidates(app).size }.getOrDefault(builtIn.size)
        val started = runCheck(
            app = app,
            gameRoot = gameRoot,
            skippedVersionCode = 0L,
            addressCount = addressCount,
            onBusy = { toast(activity, UpdateUiText.checkBusyReport(copy)) },
            onCrashed = { error ->
                onSettled()
                restoreAutoUpdate()
                toast(activity, UpdateUiText.checkCrashedReport(copy, error))
            },
        ) { result ->
            onSettled()
            restoreAutoUpdate()
            if (!alive(activity)) return@runCheck
            val offer = result.offer
            when {
                offer != null -> showOfferDialog(activity, offer, result.warnings)
                result.fetchProblems.isNotEmpty() ->
                    toast(activity, UpdateUiText.checkFailedReport(copy, addressCount, result.fetchProblems, result.warnings.size))
                else -> {
                    val current = runCatching { UpdateManager.currentVersionCode(activity) }.getOrDefault(-1L)
                    val data = runCatching { UpdateManager.installedDataVersion(gameRoot) }.getOrDefault(0L)
                    toast(activity, UpdateUiText.upToDateReport(copy, current, data, result.warnings.size))
                }
            }
        }
        // 没起成（闸被占住）也要把临时打开的开关恢复：这一发根本没跑，不该留下任何副作用。
        if (!started) restoreAutoUpdate()
        if (started) toast(activity, copy.checkingLabel)
        return started
    }

    /**
     * 检查的**唯一**执行体（自动与手动共用）：拿闸 → 后台线程跑 `UpdateManager.checkNow` →
     * 把结果的形状与原话落进偏好（[UpdateManager.rememberLastCheckResult]）→ 主线程放闸并回调。
     *
     * 结果标记只由 [UpdateChannelReadout.outcomeOf] 判一次（输入全是 `CheckResult` 已有的读数），
     * 壳层不另立一套判据；落盘失败（偏好写不进去）只落 logcat，不许影响上屏。
     *
     * @param onResult 检查结果（主线程）
     * @param onCrashed 检查抛错（主线程）
     * @param onBusy 单飞闸已被占住（主线程，不起线程也不落任何状态）
     * @return `true` = 本次调用真的起了这一发
     */
    private fun runCheck(
        app: Context,
        gameRoot: File,
        skippedVersionCode: Long,
        addressCount: Int,
        onBusy: () -> Unit,
        onCrashed: (Throwable) -> Unit,
        onResult: (UpdateManager.CheckResult) -> Unit,
    ): Boolean {
        if (!checking.compareAndSet(false, true)) {
            mainHandler.post(onBusy)
            return false
        }
        Thread {
            // 漏斗遥测拍 1（U6）：起跑与结局。读数全部现搬、判定一格不新立 ——
            // 结果标记就是下面喂给 rememberLastCheckResult 的那一枚（判据在 UpdateChannelReadout），
            // 「检查成功」这一条由 UpdateManager.checkNow 发（那里才知道清单的形状），这里只补
            // 它发不出的失败形状，一趟检查因此恒一条 check_* 终点事件、不重复计数。
            UpdateTelemetryWiring.checkStarted(app, runCatching { UpdateManager.currentVersionCode(app) }.getOrDefault(0L))
            val attempted = runCatching { UpdateManager.checkNow(app, gameRoot, skippedVersionCode) }
            val check = attempted.getOrNull()
            val error = attempted.exceptionOrNull()
            val mark = if (check == null) {
                UpdateChannelReadout.OUTCOME_CRASHED
            } else {
                UpdateChannelReadout.outcomeOf(addressCount, check.fetchProblems.size, crashed = false)
            }
            if (mark != UpdateChannelReadout.OUTCOME_OK) UpdateTelemetryWiring.checkFailed(app, mark)
            val notes = when (mark) {
                // 连不上：逐条地址的原话就是取证要的「哪一条、怎么死的」。
                UpdateChannelReadout.OUTCOME_UNREACHABLE -> check?.fetchProblems.orEmpty()
                // 抛错：留异常名与消息，屏上那句「出了意外」背后要有可对的东西。
                UpdateChannelReadout.OUTCOME_CRASHED -> listOfNotNull(error?.let { "${it.javaClass.simpleName}: ${it.message}" })
                // 查成功：留下的原话是发布那边的提醒（U2-④ 要消费的就是这一份）。
                else -> check?.warnings.orEmpty()
            }
            UpdateManager.rememberLastCheckResult(
                app,
                mark,
                System.currentTimeMillis(),
                notes.filter { it.isNotBlank() },
            )
            mainHandler.post {
                checking.set(false)
                if (error != null) onCrashed(error) else check?.let(onResult)
            }
            // 冲刷点之一（U6）：这条线程是后台的，可以出站；攒着的事件（含上次进程没发出去的）
            // 在这里搭便车走。**排在放闸之后**是刻意的：端点慢的时候这一发最长要等两档超时，
            // 而「正在检查更新…」那颗按钮置灰的时长归上一行管，不许被遥测拖长。
            UpdateTelemetryWiring.flushPending(app)
        }.apply { isDaemon = true }.start()
        return true
    }

    /**
     * 设置页正文：通道现在是什么状态（能不能查、上次查到什么、有没有东西等着应用）。
     *
     * EP4 U2-①：判读与分行全部在 [UpdateChannelReadout]，这里只搬读数；返回的
     * [UpdateChannelReadout.Readout] 分两层 —— 玩家面分行现状与「看技术细节」展开层。
     * EP4 U3a：句子按玩家的语言取（[copyFor]），本层不判任何句子内容。
     */
    fun channelReadout(activity: Activity, gameRoot: File): UpdateChannelReadout.Readout =
        UpdateChannelReadout.of(copyFor(activity), snapshotOf(activity, gameRoot))

    /** 把设置页要的读数一次搬齐（每一条都是已有读点，读不出来就按「没记过」处理，不猜）。 */
    private fun snapshotOf(activity: Activity, gameRoot: File): UpdateChannelReadout.Snapshot {
        val mirrors = runCatching { UpdateManager.mirrorCandidates(activity) }.getOrDefault(emptyList())
        return UpdateChannelReadout.Snapshot(
            mirrorCount = mirrors.size,
            preferredMirror = mirrors.firstOrNull().orEmpty(),
            lastSuccessMillis = runCatching { UpdateManager.lastCheckMillis(activity) }.getOrDefault(0L),
            lastAttemptMillis = runCatching { UpdateManager.lastCheckAttemptMillis(activity) }.getOrDefault(0L),
            outcome = runCatching { UpdateManager.lastCheckOutcome(activity) }.getOrDefault(UpdateChannelReadout.OUTCOME_UNKNOWN),
            notes = runCatching { UpdateManager.lastCheckNotes(activity) }.getOrDefault(emptyList()),
            noteCount = runCatching { UpdateManager.lastCheckNoteCount(activity) }.getOrDefault(0),
            currentVersionCode = runCatching { UpdateManager.currentVersionCode(activity) }.getOrDefault(-1L),
            installedDataVersion = runCatching { UpdateManager.installedDataVersion(gameRoot) }.getOrDefault(0L),
            stagedDataVersion = runCatching { UpdateManager.stagedBundle(activity) }.getOrNull()?.version ?: 0L,
            skippedVersionCode = runCatching { UiPrefs.updateSkippedVersionCode(activity) }.getOrDefault(0L),
            autoChannelWanted = runCatching { UiPrefs.autoUpdateEnabled(activity) }.getOrDefault(true),
            nowMillis = System.currentTimeMillis(),
            // U5 P2-12：进程内只读下载进度；设置页进页读一次、前台低频刷新都从这里取。
            progress = runCatching { UpdateDownloadService.progress() }.getOrDefault(DownloadProgress.IDLE),
            // U11 P2-13：上一次检查选出的数据通道档位摘要（进展开层，玩家面三行不动）；没写过回落 null。
            dataArtifact = runCatching { UiPrefs.dataArtifactReadout(activity) }.getOrNull(),
            // U13 upd-baccount：挡板生效读数（展开层再加一行「上次同版本被拒、这次改走 X」）；
            // 没生效回落 null，展开层一个字都不加。
            rejectedFallback = runCatching { UiPrefs.rejectedBundleFallback(activity) }.getOrNull(),
        )
    }

    // ------------------------------------------------------------------
    // 弹窗
    // ------------------------------------------------------------------

    /**
     * 有新版本可更时的弹窗（契约 U2-⑤）：正文只留必要三行，长更新说明与发布那边的提醒原话
     * 收进「看说明」展开层（限高、可滚），小屏上「下载」按钮不再被顶出可视区。
     *
     * 两条既有裁定一字未动：
     * - 章不合（装了不上）时坏消息必须**在正文第一行**送达，且正按钮退化成「知道了」；
     * - 「以后都不」只对同一个 versionCode 生效、纯数据更新不给这个按钮。
     *
     * 长相归 [EratwShellDialog]：三档正文（强调行 / 普通行 / 次要行）与「看说明」那一行
     * 都是共用件，本函数只决定说什么、点哪。
     */
    private fun showOfferDialog(activity: Activity, offer: UpdateManager.UpdateOffer, warnings: List<String> = emptyList()) {
        val copy = copyFor(activity)
        // P2（重复弹窗）：同一 Activity 上已经亮着一条 offer 弹窗就先收掉，宁可换成新的也不叠两层
        // （冷启自动检查与手动检查各发一条时，旧实现会把两层弹窗摞在一起）。
        val previous = shownOfferDialog?.get()
        if (previous != null && previous.isShowing && shownOfferActivity?.get() === activity) {
            runCatching { previous.dismiss() }
        }
        shownOfferDialog = null
        shownOfferActivity = null
        val onCellular = runCatching { !UpdateManager.isUnmetered(activity) }.getOrDefault(true)
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular)
        if (lines.isEmpty()) return
        val detail = UpdateUiText.offerDetailLines(copy, offer.info.note, warnings)
        // 强制档命中时这里返回 0 ⇒ 下面不给 neutral 钮：停止支持的版本没有「以后都不」的口。
        val skipCode = UpdateUiText.skipCodeForReminder(offer)
        val job = runCatching { UpdateUiText.jobFor(copy, offer) }.getOrNull()
        // 三档视觉的判读仍在 UpdateUiText（有单测），这里只把下标换成档位。
        val headlineIndex = UpdateUiText.offerHeadlineIndex(offer, lines.size)
        val footnoteIndex = if (onCellular && job != null) lines.lastIndex else -1
        // 漏斗遥测拍 2 的两个读数（U6）：kind 与「点下载会下哪件」同判据（jobFor 的超集规则），
        // version 是那件产物的版本号；都只搬已有读数，不在这里另判一次。
        val telemetryOfferKind = UpdateTelemetryEvents.offerKind(job, offer.apkNeeded, offer.assetsNeeded)
        val telemetryOfferVersion = UpdateTelemetryEvents.offerVersion(
            telemetryOfferKind,
            offer.info.apk?.versionCode,
            offer.info.assets?.version,
        )
        val body = EratwShellDialog.content(
            activity = activity,
            title = UpdateUiText.offerDialogTitle(copy, offer),
            lines = lines.mapIndexed { index, line ->
                EratwShellDialog.Line(
                    text = line,
                    tone = when (index) {
                        headlineIndex -> EratwShellDialog.Tone.HEADLINE
                        footnoteIndex -> EratwShellDialog.Tone.NOTE
                        else -> EratwShellDialog.Tone.BODY
                    },
                )
            },
        )
        if (detail.isNotEmpty()) {
            body.addView(
                EratwShellDialog.expandable(
                    activity = activity,
                    collapsedLabel = UpdateUiText.offerExpandLabel(copy, warnings.size),
                    expandedText = detail.joinToString("\n"),
                ),
            )
        }
        val dialog = EratwShellDialog.builder(activity)
            .setView(body)
            // 漏斗遥测拍 2（U6）：三个按钮各对应 core 白名单里的一个动作 ——
            // 「关闭/知道了」= decline、「以后都不」= skip（它写的就是 skippedVersionCode）、
            // 「下载」= download。监听器只做记录一件事：AlertDialog 上任何按钮都照旧自动收弹窗，
            // 把原来的 null 换成带记录的 lambda 不改变既有处置与上屏。
            .setNegativeButton(copy.dialogDeclineButton) { _, _ ->
                UpdateTelemetryWiring.offerAction(activity, UpdateTelemetry.ACTION_DECLINE, telemetryOfferVersion)
            }
            .apply {
                // 「以后都不」只抑同一个 versionCode（契约 §1.5）；纯数据更新没有可钉的整包版本，
                // 不给这个口 —— 抑制数据提醒等于让玩家永远收不到语料修复。
                if (skipCode > 0L) {
                    setNeutralButton(copy.dialogNeverButton) { _, _ ->
                        UpdateTelemetryWiring.offerAction(activity, UpdateTelemetry.ACTION_SKIP, telemetryOfferVersion)
                        val saved = runCatching { UiPrefs.setUpdateSkippedVersionCode(activity, skipCode) }
                        if (saved.isSuccess) toast(activity, UpdateUiText.skippedToast(copy, skipCode))
                        else Log.w(TAG, "跳过版本写不进去，本次不拦提醒")
                    }
                }
            }
            .apply {
                // 章不合且没有数据可更 ⇒ 没有可以点的下载，正按钮退化成「知道了」——
                // 坏消息也必须送达，但绝不给一个点了必白跑几百 MiB 的按钮。
                if (job != null) {
                    setPositiveButton(copy.dialogDownloadButton) { _, _ ->
                        UpdateTelemetryWiring.offerAction(activity, UpdateTelemetry.ACTION_DOWNLOAD, telemetryOfferVersion)
                        onDownloadClick(activity, job)
                    }
                } else {
                    setPositiveButton(copy.dialogAckButton) { _, _ ->
                        UpdateTelemetryWiring.offerAction(activity, UpdateTelemetry.ACTION_DECLINE, telemetryOfferVersion)
                    }
                }
            }
            .create()
        EratwShellDialog.dress(dialog)
        dialog.show()
        shownOfferDialog = WeakReference(dialog)
        shownOfferActivity = WeakReference(activity)
        UpdateTelemetryWiring.offerShown(activity, telemetryOfferKind, telemetryOfferVersion, offer.forceUpdate)
    }

    /** 点「下载」之后的两段确认：蜂窝二次确认（接线 `autoDownloadAllowed`）→ 通知授权 → 起服务。 */
    private fun onDownloadClick(activity: Activity, job: DownloadJob) {
        val copy = copyFor(activity)
        // 蜂窝判不了就当蜂窝（多问一次不花钱，少问一次花的是玩家的流量费）。
        val unmetered = runCatching { UpdateManager.isUnmetered(activity) }.getOrDefault(false)
        // U5 P2-10：仅 Wi-Fi 打开且当前走蜂窝 ⇒ 直接说明「已设为仅 Wi-Fi」，不给绕行按钮。
        // 这一支在既有二次确认之前、且只在开关开着时命中；关（缺省）时下面那段一个字都没动。
        if (UpdateUiText.wifiOnlyBlocksCellular(UiPrefs.wifiOnlyDownload(activity), unmetered)) {
            val blocked = EratwShellDialog.builder(activity)
                .setView(
                    EratwShellDialog.content(
                        activity = activity,
                        title = copy.wifiOnlyCellularTitle,
                        body = copy.wifiOnlyCellularBody,
                    ),
                )
                // 只有一颗「知道了」：不许把 cellularProceedButton（仍要下载）接回这条路上。
                .setPositiveButton(copy.dialogAckButton, null)
                .create()
            EratwShellDialog.dress(blocked)
            blocked.show()
            return
        }
        if (UpdateUiText.cellularConfirmRequired(unmetered)) {
            val dialog = EratwShellDialog.builder(activity)
                .setView(
                    EratwShellDialog.content(
                        activity = activity,
                        title = copy.cellularDialogTitle,
                        body = UpdateUiText.cellularConfirmBody(copy, job),
                    ),
                )
                .setNegativeButton(copy.cellularDeclineButton, null)
                .setPositiveButton(copy.cellularProceedButton) { _, _ -> askNotificationsThenStart(activity, job) }
                .create()
            EratwShellDialog.dress(dialog)
            dialog.show()
        } else {
            askNotificationsThenStart(activity, job)
        }
    }

    /**
     * API 33+：没给通知授权先把话说清再问一次系统弹窗；拒绝也放行下载。
     * 低版本/已授权直接起服务 —— 可见性不再靠「权限给没给」猜，[startOrReport] 里按
     * [notificationsVisible] 的真实读数取话术（P2-10：应用级 + 渠道级）。
     */
    private fun askNotificationsThenStart(activity: Activity, job: DownloadJob) {
        val copy = copyFor(activity)
        if (!UpdateUiText.notificationPermissionNeeded(Build.VERSION.SDK_INT)) {
            startOrReport(activity, job)
            return
        }
        val granted = runCatching {
            activity.checkSelfPermission(POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (granted) {
            startOrReport(activity, job)
            return
        }
        val waiting = pendingStart
        if (waiting != null) {
            // P1-2：等授权的单子不许把后续点击全吞掉。只有「确实还在等系统弹窗」的窗口内
            // 才把这一单并入（换单子）；超过上限 = 回执已经丢了（Activity 没转发 / 界面被回收），
            // 清掉它并重走流程 —— 旧实现这里会无限期挂着，之后每次点「下载」都被静默吞掉。
            if (System.currentTimeMillis() - waiting.enqueuedAtMillis <= PENDING_START_TIMEOUT_MILLIS) {
                pendingStart = PendingStart(
                    WeakReference(activity),
                    job,
                    waiting.enqueuedAtMillis,
                    activity.applicationContext,
                )
                Log.i(TAG, "通知授权还没回来，本次点击并入等待中的下载单")
                return
            }
            pendingStart = null
            mainHandler.removeCallbacks(pendingStartTimeout)
            Log.w(TAG, "等待通知授权已超时（回执丢了），清掉挂起的单子并重走下载流程")
        }
        val dialog = EratwShellDialog.builder(activity)
            .setView(
                EratwShellDialog.content(
                    activity = activity,
                    title = copy.notificationsTitle,
                    body = UpdateUiText.notificationRationale(copy, job),
                ),
            )
            .setNegativeButton(copy.notificationsDeclineButton) { _, _ -> startOrReport(activity, job) }
            .setPositiveButton(copy.notificationsGrantButton) { _, _ ->
                pendingStart = PendingStart(
                    WeakReference(activity),
                    job,
                    System.currentTimeMillis(),
                    activity.applicationContext,
                )
                mainHandler.removeCallbacks(pendingStartTimeout)
                mainHandler.postDelayed(pendingStartTimeout, PENDING_START_TIMEOUT_MILLIS)
                val requested = runCatching {
                    activity.requestPermissions(arrayOf(POST_NOTIFICATIONS), PERMISSION_REQUEST_NOTIFICATIONS)
                    true
                }.getOrDefault(false)
                if (!requested) {
                    pendingStart = null
                    mainHandler.removeCallbacks(pendingStartTimeout)
                    startOrReport(activity, job)
                }
            }
            .create()
        EratwShellDialog.dress(dialog)
        dialog.show()
    }

    /**
     * [MainActivity] / [SettingsActivity] 转发的权限回调：把挂起的那一单续跑完。
     *
     * 授权与否都续跑（拒绝只是看不到通知，下载照跑）；界面已经不在时**不静默丢单**，
     * 改用应用上下文起服务（P1-2 —— 服务只需要 Context，Activity 回收不该让下载消失）。
     */
    fun onPermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != PERMISSION_REQUEST_NOTIFICATIONS) return
        val pending = pendingStart ?: return
        pendingStart = null
        mainHandler.removeCallbacks(pendingStartTimeout)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        val activity = pending.activity.get()
        if (activity == null || !alive(activity)) {
            Log.i(TAG, "通知授权回来时界面已关闭（granted=$granted），改用应用上下文开始这一单")
            startWithContext(pending.appContext, pending.job)
            return
        }
        startOrReport(activity, pending.job)
    }

    /**
     * 起下载服务并报一句话。
     *
     * 三处口径：
     * - **busy 只报一句**（P2）：起服务前先读进程内单飞闸的活读数；已有一单在跑就别再
     *   先报「开始下载了」再被服务回一句「已经有一个下载在跑」——两条 Toast 互相打脸；
     * - 可见性按 [notificationsVisible] 的真实读数取话术（P2-10），不拿「权限给没给」猜；
     * - 开跑话术走带 copy 的主签名（P1-2：删掉了固定中文的兼容口，EN/JA 玩家不再看到中文）。
     */
    private fun startOrReport(activity: Activity, job: DownloadJob) {
        val copy = copyFor(activity)
        if (UpdateDownloadService.progress().running) {
            toast(activity, copy.downloadBusyToast)
            return
        }
        mainHandler.removeCallbacks(pendingStartTimeout)
        val started = runCatching { UpdateDownloadService.start(activity, job) }
        if (started.isFailure) {
            Log.w(TAG, "下载服务起不来", started.exceptionOrNull())
            // 异常名这一格与搬动前逐字同形（原来就是把可空的 simpleName 直接插进句子，
            // isFailure 保证它非空，万一为空也照旧印成 null，不在这条路上改判读）。
            val errorName = started.exceptionOrNull()?.javaClass?.simpleName ?: "null"
            toast(activity, copy.fill(copy.downloadStartFailedFormat, errorName))
            return
        }
        toast(activity, UpdateProgressText.startedToast(copy, notificationsVisible(activity)))
    }

    /**
     * 界面没了也要把这一单跑完（P1-2）：服务只需要应用上下文，别因为 Activity 回收静默丢单。
     * 没有 Activity 就没有 Toast 落点，异常只进 logcat（下载本身在服务里跑）。
     */
    private fun startWithContext(context: Context, job: DownloadJob) {
        runCatching { UpdateDownloadService.start(context, job) }
            .onFailure { Log.w(TAG, "界面不在时下载服务仍没起来（这一单没跑）", it) }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 通知到底看不看得见（P2-10 收口）：应用级开关 **加** 进度渠道级开关。
     *
     * 为什么必须看渠道：玩家可以在系统设置里只关掉「更新下载进度」这一条渠道，那时
     * `areNotificationsEnabled()` 仍是 true，而进度通知根本不会出现 —— 壳层若据此说
     * 「点这条通知」，就是把玩家往一条看不见的路上引。
     *
     * 读数取不到（旧系统没有渠道概念 / 通知服务都拿不出来）时**不许反过来谎报看不见**：
     * 与 [UpdateDownloadService] 原口径一致 —— 宁可少发一条补救 Toast，也不许对玩家说瞎话。
     */
    fun notificationsVisible(context: Context): Boolean = runCatching {
        val manager = notificationManager(context) ?: return false
        val appLevel = if (Build.VERSION.SDK_INT >= 24) manager.areNotificationsEnabled() else true
        if (!appLevel) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val progress = manager.getNotificationChannel(UpdateDownloadService.CHANNEL_PROGRESS)
            if (progress != null && progress.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        true
    }.getOrDefault(true)

    /** 系统通知服务：API 23 起才有 Class 形态的 getSystemService，21/22 走字符串形态。 */
    private fun notificationManager(context: Context): NotificationManager? = runCatching {
        if (Build.VERSION.SDK_INT >= 23) {
            context.getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }
    }.getOrNull()

    private fun toast(activity: Activity, text: String) {
        if (!alive(activity)) return
        Log.i(TAG, text)
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()
    }

    private fun alive(activity: Activity): Boolean = !activity.isFinishing && !activity.isDestroyed
}
