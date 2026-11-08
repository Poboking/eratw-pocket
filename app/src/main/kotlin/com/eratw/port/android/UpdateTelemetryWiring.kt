package com.eratw.port.android

import android.content.Context
import android.os.Looper
import android.util.Log
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.UpdateTelemetry
import java.io.File

/**
 * 漏斗遥测的**维度装配**（U6 接线面，P1-4 收尾；纯 JVM、零 Android 依赖，定向单测直接钉）。
 *
 * ## 为什么单独一层
 *
 * [UpdateTelemetry] 只裁定「这条事件合不合法」，[UpdateTelemetrySink] 只管「这一批怎么送走」；
 * 中间那一格 —— 「此刻手上这些读数该拼成哪条事件的哪些维度」—— 原本散在六个调用点里，
 * 拼错一次就是聚合表上一整列脏数据，而它在屏上没有任何表现。本对象把这六拍的装配收在一处，
 * 每个调用点只剩「把已有的读数交进来」。
 *
 * ## 三条口径（写在这里，调用点不再各判一遍）
 *
 * 1. **读不出来就不带那个维，而不是把整条事件丢掉**：`version` 在 core 那侧只收正数
 *    （`UpdateTelemetry.validate`），而本机的 `currentVersionCode` 读失败时是 -1、
 *    `assetsVersion` 缺省是 0 —— 这些「没有读数」的形状全部归一成 null（不带维），
 *    事件照样记，因为「这一拍发生了」本身就是漏斗要数的东西。
 * 2. **失败类别是短标签，不是自由文本**：core 限非空白且不超过 [UpdateTelemetry.MAX_DIM_CHARS]。
 *    这里的输入有两种：[UpdateChannelReadout] 的结果标记（ok / unreachable / no-mirror / crashed，
 *    本就 ≤40）与异常类名（`NetworkOnMainThreadException` 这类长名字）。超长就截到上限、
 *    空白回落 [FAILURE_UNKNOWN] —— 宁可类别粗一档，也不能让整条事件被 core 拒掉。
 * 3. **零设备标识**：本层能拿到的只有版本号、字节数、类别标签、布尔档位。
 *    没有设备号 / IMEI / Android ID / 序列号，也没有存档内容与游戏目录路径 ——
 *    [checkFailed] 只搬 [UpdateChannelReadout] 的**结果标记**，不搬任何一条原话（原话里可能
 *    出现镜像地址与文件名，那是取证面，不进漏斗）。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
internal object UpdateTelemetryEvents {

    /** 失败类别读不出来时的回落标签（core 不许空白）。 */
    const val FAILURE_UNKNOWN: String = "unknown"

    /** 下载把全部候选地址试完仍失败：`DownloadOutcome.kind` 此时为 null（UpdateManager.kt:519 那一支）。 */
    const val FAILURE_EXHAUSTED: String = "exhausted"

    /** 见类头第 1 条：非正的版本读数不是「版本 0」，是「没读数」，不带维。 */
    fun versionOrNone(value: Long?): Long? = if (value != null && value > 0L) value else null

    /**
     * 见类头第 2 条：把任意来源的失败类别归一成 core 收得下的短标签。
     *
     * [raw] 为 null / 空白时用 [fallback]（连 fallback 都空白就落 [FAILURE_UNKNOWN]，
     * 保证出口恒非空白 —— `check_failed` 与 `download_failed` 少了 failure 会被整条拒掉）。
     */
    fun failureLabel(raw: String?, fallback: String = FAILURE_UNKNOWN): String {
        val source = raw?.takeIf { it.isNotBlank() } ?: fallback
        val clipped = source.trim().take(UpdateTelemetry.MAX_DIM_CHARS)
        return clipped.takeIf { it.isNotBlank() } ?: FAILURE_UNKNOWN
    }

    /** 两条通道的标签：与 core 的 `KIND_APK` / `KIND_ASSETS` 同一套词，不另造。 */
    fun artifactKind(kind: UpdateDownloadKind): String =
        if (kind == UpdateDownloadKind.Apk) UpdateTelemetry.KIND_APK else UpdateTelemetry.KIND_ASSETS

    /**
     * 弹窗这一拍要报的产物类别：**照抄 [UpdateUiText.jobFor] 的超集规则**（能装整包就只算整包，
     * 否则才算数据包），这样「弹窗展示的到底是哪件」与「点下载会下哪件」恒同一判据。
     *
     * `jobFor` 返回 null（章不合把整包一票否决、又没有数据可下）时按 offer 的形状回落，
     * 保证 `offer_shown` 必带合法 kind（core 对这个事件是**必填**）。
     */
    fun offerKind(job: DownloadJob?, apkNeeded: Boolean, assetsNeeded: Boolean): String = when {
        job != null -> artifactKind(job.kind)
        apkNeeded -> UpdateTelemetry.KIND_APK
        assetsNeeded -> UpdateTelemetry.KIND_ASSETS
        else -> UpdateTelemetry.KIND_ASSETS
    }

    /** 弹窗正文里那件产物的版本号：整包取 versionCode，数据包取 data-version；都没有就不带维。 */
    fun offerVersion(kind: String, apkVersionCode: Long?, assetsVersion: Long?): Long? =
        versionOrNone(if (kind == UpdateTelemetry.KIND_APK) apkVersionCode else assetsVersion)

    fun checkStarted(currentVersionCode: Long): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_CHECK_STARTED,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(currentVersionCode),
    )

    fun checkOk(currentVersionCode: Long): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_CHECK_OK,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(currentVersionCode),
    )

    /** 检查失败：`failure` 只搬结果标记（unreachable / no-mirror / crashed），不搬任何原话。 */
    fun checkFailed(outcome: String): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_CHECK_FAILED,
        atMillis = System.currentTimeMillis(),
        failure = failureLabel(outcome),
    )

    fun offerShown(kind: String, version: Long?, force: Boolean): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_OFFER_SHOWN,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(version),
        kind = kind,
        force = force,
    )

    /** 玩家动作：download / skip / decline 三选一（core 的 `KIND_ACTION` 白名单）。 */
    fun offerAction(action: String, version: Long?): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_OFFER_ACTION,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(version),
        kind = action,
    )

    fun downloadStarted(kind: UpdateDownloadKind, assetsVersion: Long): UpdateTelemetry.Event =
        UpdateTelemetry.Event(
            name = UpdateTelemetry.EVENT_DOWNLOAD_STARTED,
            atMillis = System.currentTimeMillis(),
            version = versionOrNone(assetsVersion),
            kind = artifactKind(kind),
        )

    /** 下成：`sizeBytes` 交的是**声明体积**（sha 已核对，实收必等长，报声明值不与报实收冲突）。 */
    fun downloadFinished(
        kind: UpdateDownloadKind,
        sizeBytes: Long,
        assetsVersion: Long,
    ): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_DOWNLOAD_FINISHED,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(assetsVersion),
        kind = artifactKind(kind),
        sizeBytes = if (sizeBytes >= 0L) sizeBytes else null,
    )

    fun downloadCancelled(kind: UpdateDownloadKind, assetsVersion: Long): UpdateTelemetry.Event =
        UpdateTelemetry.Event(
            name = UpdateTelemetry.EVENT_DOWNLOAD_CANCELLED,
            atMillis = System.currentTimeMillis(),
            version = versionOrNone(assetsVersion),
            kind = artifactKind(kind),
        )

    /**
     * 下败：`failure` 交 [UpdateDownloadPlan.FailureKind] 的枚举名（短标签，与「换不换镜像」的
     * 判据同源）；`kind` 为 null 的那一支默认是「所有候选地址都试完」，落 [FAILURE_EXHAUSTED]，
     * 下载线程自己抛出来的那一类异常由调用点给 [failureFallback]（异常类名，超长由
     * [failureLabel] 截到上限）。
     * `sizeBytes` 交的是**已落盘的字节**（0 起收），它是「下到哪里断的」这一格唯一可用的读数。
     */
    fun downloadFailed(
        kind: UpdateDownloadKind,
        doneBytes: Long,
        failureKind: UpdateDownloadPlan.FailureKind?,
        assetsVersion: Long,
        failureFallback: String = FAILURE_EXHAUSTED,
    ): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_DOWNLOAD_FAILED,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(assetsVersion),
        kind = artifactKind(kind),
        sizeBytes = if (doneBytes >= 0L) doneBytes else null,
        failure = failureLabel(failureKind?.name, failureFallback),
    )

    /** 把已核对的 APK 交给系统安装器（或提交安装会话）这一拍：只带本机版本维。 */
    fun installRequested(currentVersionCode: Long): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_INSTALL_REQUESTED,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(currentVersionCode),
    )

    /**
     * 安装终态（第 6 拍）：PackageInstaller 会话的回执落到成功 / 取消 / 失败那一刻。
     *
     * 三档标签直接取 core 的 `OUTCOME_*`（不另造词，聚合表与 core 的白名单同一套）。
     * [status] 是系统回执的**原始状态码**（`PackageInstaller.EXTRA_STATUS`），只有失败档交得进来：
     * 它在这里只被 `toString` 一下（core 的 `status` 只收字母数字与 `._-`、且限长），
     * 摘要长句与系统原话**一个字都不进这一格** —— 那两样是玩家面与取证面，不是漏斗维度。
     * 非失败档误传了状态码时**丢掉那一格而不是丢掉整条事件**（类头第 1 条：读不出来就不带那个维；
     * 「这一刻安装终态发生了」才是漏斗要数的东西）。
     */
    fun installResult(outcome: String, status: Int?, currentVersionCode: Long): UpdateTelemetry.Event =
        UpdateTelemetry.Event(
            name = UpdateTelemetry.EVENT_INSTALL_RESULT,
            atMillis = System.currentTimeMillis(),
            version = versionOrNone(currentVersionCode),
            outcome = outcome,
            status = if (outcome == UpdateTelemetry.OUTCOME_FAILED) status?.toString() else null,
        )

    /**
     * 数据包应用终态（第 7 拍，U10）：冷启装载前把暂存包应用到游戏根的那一拍，成与败两格都发。
     *
     * [outcome] 只收 core 的 `OUTCOME_SUCCESS` / `OUTCOME_FAILED` 两档（应用跑在冷启解包线程上，
     * 没有可取消的形态；「版本不需要」根本不应用，走的是「没有这一拍」而不是 failed）。
     * [kind] 交 core 的 [DataArtifactKind]，经 [UpdateTelemetry.dataArtifactKindLabel] 映射成
     * 漏斗标签 —— 唯一映射在 core，app 侧不许 `name.lowercase()` 自成一派；成功档必带合法档位
     * （core 对 success 缺 kind 整条拒，[DataArtifactKind.NONE] 没有标签，「NONE 不发这一拍」
     * 就挡在那一格）。[failure] 只在失败档交失败类别短标签
     * （[UpdateManager.ApplyOutcome.FailureCategory] 的 label；经 [failureLabel] 归一，
     * null 回落 unknown 保证失败拍不被 core 拒），`problem` 原话可能带路径与文件名，
     * 灌进来会被 core 的 isStatusTag 整条拒掉；成功档误传时丢掉那一维而不是丢掉整条事件
     * （第 6 拍同一口径）。[stagedVersion] 是这次生效的 data-version，失败拍没有「生效」可言，
     * 交 null。
     */
    fun assetsApplied(
        stagedVersion: Long?,
        outcome: String,
        kind: DataArtifactKind?,
        failure: String?,
    ): UpdateTelemetry.Event = UpdateTelemetry.Event(
        name = UpdateTelemetry.EVENT_ASSETS_APPLIED,
        atMillis = System.currentTimeMillis(),
        version = versionOrNone(stagedVersion),
        kind = UpdateTelemetry.dataArtifactKindLabel(kind),
        failure = if (outcome == UpdateTelemetry.OUTCOME_FAILED) failureLabel(failure) else null,
        outcome = outcome,
    )
}

/**
 * 一批待发事件的**发送口**（U6 接线，纯函数好钉）：主线程一律不发。
 *
 * 为什么不是「照发」：Android 主线程禁网，直接 POST 得到 `NetworkOnMainThreadException`，
 * 而 core 的冲刷点就挂在 `record()` 里面（缓冲满 [UpdateTelemetry.MAX_BUFFER] 或最旧一条满 48 小时）
 * —— 弹窗那一拍跑在主线程，照发就是「玩家点了个按钮，收一条异常、事件一条没出去」。
 * 返回 false 的语义是「这批没消费，整批留在缓冲并保持持久化」（`UpdateTelemetrySink` 类头第 3 条），
 * 所以这一档**不丢数据**：下一次在后台线程上的记录或 [UpdateTelemetryWiring.flushPending] 会把它带走。
 */
internal fun telemetrySendPort(
    endpoint: String,
    token: String,
    transport: UpdateTelemetrySink.Transport,
    isMainThread: () -> Boolean,
): (List<String>) -> Boolean = { lines ->
    if (isMainThread()) false
    else runCatching { UpdateTelemetrySink.flush(endpoint, token, lines, transport) }
        .getOrDefault(false)
}

/**
 * 更新漏斗遥测的**接线层**（U6，P1-4 收尾：把 U3 落好的基座接进六拍链路）。
 *
 * ## 这一层只管三件事
 *
 * 1. **进程级那一只缓冲**：[UpdateTelemetry] 是「事件校验 + 批量 + 持久化协调」，它要有状态，
 *    所以本对象持一只懒建的实例（app 壳层允许进程级单例，与 [UpdateManager] / [ErrorReportUploader] 同形态；
 *    core 层的「禁静态可变全局」铁律管不到这里）。首次建要读盘（还原上次没发出去的待发行），
 *    所以**只在后台线程首建** —— 主线程首建会在玩家点按钮的那一刻同步读文件，那是
 *    [StrictModeGuard] 天天抓的那个形状，而本仓的豁免表不打算为遥测加一条。
 * 2. **通道闸**：所有记录与冲刷都过 [UpdateManager.channelEnabled]，判据与更新通道同一条
 *    （出包没带镜像、或玩家在设置页关掉自动更新 = 整条漏斗**一个字节都不出网、也不落盘**）。
 *    出包侧还有一道同口径的闸：上报端点没配时 [UpdateTelemetrySink.flush] 恒返回「已消费」且
 *    一次都不触碰传输口 —— 两道闸任一道关着都没有出站字节。
 * 3. **每拍 `runCatching`**：本层每个入口都兜成「最多不记」，异常只落一行 logcat。
 *    返回值一律是 `Unit`、不改任何既有返回值与上屏文案（AGENTS 铁律：遥测不许把游戏搞崩）。
 *
 * ## 冲刷点（core 不起线程，年龄检查只搭写入/手动冲刷的便车）
 *
 * - [UpdateUi.runCheck] 的后台线程里、**排在放闸那一发之后**（置灰时长不许被端点快慢拉长）；
 * - [UpdateDownloadService.runDownload] 的 `finally`（下载线程，本来就在后台）；
 * - [GameHost] 装载前那拍（每次冷启必到，走 [flushPendingAsync] —— 那一拍的返回值是
 *   「游戏能不能开始装载」，同步等一趟 HTTP 就是把首屏交给端点）。
 *
 * ## 第 6 拍（安装终态）：core 开口之后才接上的
 *
 * U6 落五拍时这一拍是**报红未接**的：core 的事件名白名单没有 `install_result`，维度里也没有
 * `outcome` / `status` 两格，接上去的表现是 `record()` 恒返回 false 的静默无效。U6b 在 core 侧
 * 把事件名、两格维度、校验与编码一并跟平（聚合侧 `tools/report-endpoint/worker.js` 的白名单同步），
 * 这一拍才由 [installResult] 接上：结局三档取 `InstallSessionOutcome` 的三分类，失败档另带
 * 系统的原始状态码；**摘要长句与系统原话都不进漏斗**，它们只属于玩家面与取证面。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（见 AGENTS.md 铁律 5）。
 */
object UpdateTelemetryWiring {

    private const val TAG = "EratwUpdate"

    /** 待发事件的落盘目录与文件名（应用私有目录，与错误报告同一块地，不进云备份的那份路径由系统管）。 */
    internal const val DIR_NAME = "update-telemetry"
    internal const val FILE_NAME = "pending.jsonl"

    /** 进程级那一只缓冲；只由 [bridgeOf] 懒建，建不出来（任何异常）就是「本次不记」。 */
    @Volatile
    private var bridge: UpdateTelemetryBridge? = null

    /** 「当前这条线程是不是主线程」的口；单测不碰 Android 运行时，线下面两个闸都把它当参数收。 */
    internal var mainThreadProbe: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }

    /**
     * 「当前这条线程上能不能真的出站」的闸（主线程禁网）。
     * 首建缓冲要读盘，读的也是同一条闸：见 [bridgeOf]。
     */
    internal fun networkingAllowed(isMainThread: Boolean): Boolean = !isMainThread

    /** 玩家可见文案一个都不新增：本层唯一的上屏面是 logcat 取证行（AGENTS §6.1 的例外口径）。 */
    private fun note(message: String, error: Throwable?) {
        if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
    }

    /**
     * 取（必要时建）进程级那一只缓冲；主线程不首建，关闸时也不建。
     *
     * 首建走锁：检查线程、下载线程与装载前那拍可能同时摸到第一拍，各建一只就是两只缓冲共用
     * 同一份待发行 —— 同一批事件被发两遍（计数面最讨厌的形状：漏斗每一格都虚高）。
     */
    private fun bridgeOf(context: Context): UpdateTelemetryBridge? {
        bridge?.let { return it }
        if (!networkingAllowed(runCatching { mainThreadProbe() }.getOrDefault(true))) return null
        return synchronized(this) {
            bridge ?: runCatching { create(context) }
                .onFailure { note("更新漏斗遥测缓冲建不起来（不影响更新面）", it) }
                .getOrNull()?.also { bridge = it }
        }
    }

    /** 首建：读端点与令牌、接上报同一只传输口、把上次的待发行交回 core 还原。 */
    private fun create(context: Context): UpdateTelemetryBridge {
        val app = context.applicationContext
        val endpoint = runCatching { app.getString(R.string.eratw_report_endpoint) }.getOrDefault("")
        val token = runCatching { app.getString(R.string.eratw_report_token) }.getOrDefault("")
        val storage = FileStorage(File(File(app.filesDir, DIR_NAME), FILE_NAME))
        val telemetry = UpdateTelemetry(
            storage,
            { System.currentTimeMillis() },
            telemetrySendPort(endpoint, token, UpdateTelemetrySink.defaultTransport()) {
                runCatching { mainThreadProbe() }.getOrDefault(true)
            },
        )
        return UpdateTelemetryBridge(
            telemetry,
            { UpdateManager.channelEnabled(app) },
            { runCatching { mainThreadProbe() }.getOrDefault(true) },
            ::note,
        )
    }

    /** 六拍共用的落笔口：拿不到缓冲 / 通道关 / core 拒了这条事件，都只是「这一拍没记上」。 */
    private fun record(context: Context, event: UpdateTelemetry.Event) {
        runCatching { bridgeOf(context)?.record(event) }
            .onFailure { note("更新漏斗遥测记录失败（不影响更新面）", it) }
    }

    // ------------------------------------------------------------------
    // 六拍入口（调用点只负责把已有读数交进来）
    // ------------------------------------------------------------------

    /** 拍 1 起：一次检查真的起跑（[UpdateUi.runCheck] 抢到单飞闸、后台线程开跑那一刻）。 */
    fun checkStarted(context: Context, currentVersionCode: Long) =
        record(context, UpdateTelemetryEvents.checkStarted(currentVersionCode))

    /** 拍 3：清单取到并读懂（找到可更与「已经最新」都算成功，区别由紧随的 offer_shown 分出）。 */
    fun checkOk(context: Context, currentVersionCode: Long) =
        record(context, UpdateTelemetryEvents.checkOk(currentVersionCode))

    /** 拍 1 止：这次检查没成（连不上 / 包里没配源 / 自己抛错），failure = 既有结果标记，不含原话。 */
    fun checkFailed(context: Context, outcome: String) =
        record(context, UpdateTelemetryEvents.checkFailed(outcome))

    /** 拍 2 展示：弹窗真的亮起来了（kind 与「点下载会下哪件」同判据，force 是强制档位）。 */
    fun offerShown(context: Context, kind: String, version: Long?, force: Boolean) =
        record(context, UpdateTelemetryEvents.offerShown(kind, version, force))

    /** 拍 2 动作：download / skip / decline 三选一；没点按钮（直接关弹窗）不报。 */
    fun offerAction(context: Context, action: String, version: Long?) =
        record(context, UpdateTelemetryEvents.offerAction(action, version))

    /** 拍 4 起：前台服务抢到单飞闸、进度通知已就位，这张单真的要开始下了。 */
    fun downloadStarted(context: Context, kind: UpdateDownloadKind, assetsVersion: Long) =
        record(context, UpdateTelemetryEvents.downloadStarted(kind, assetsVersion))

    /** 拍 4 成：字节到位且 sha 核对通过（数据包「登记待应用」另说，这一拍只报「下成了」）。 */
    fun downloadFinished(context: Context, kind: UpdateDownloadKind, sizeBytes: Long, assetsVersion: Long) =
        record(context, UpdateTelemetryEvents.downloadFinished(kind, sizeBytes, assetsVersion))

    /** 拍 4 取消：玩家按了通知上的「取消下载」（系统时限到点那一支不报这里，那是系统的锅不是玩家的）。 */
    fun downloadCancelled(context: Context, kind: UpdateDownloadKind, assetsVersion: Long) =
        record(context, UpdateTelemetryEvents.downloadCancelled(kind, assetsVersion))

    /** 拍 4 败：类别交 [UpdateDownloadPlan.FailureKind]，null 是「候选地址全试完」那一支；
     *  下载线程自己抛出来的那一类由调用点把异常名交给 [failureFallback]。 */
    fun downloadFailed(
        context: Context,
        kind: UpdateDownloadKind,
        doneBytes: Long,
        failureKind: UpdateDownloadPlan.FailureKind?,
        assetsVersion: Long,
        failureFallback: String = UpdateTelemetryEvents.FAILURE_EXHAUSTED,
    ) = record(
        context,
        UpdateTelemetryEvents.downloadFailed(kind, doneBytes, failureKind, assetsVersion, failureFallback),
    )

    /** 拍 4 安装请求：已核对的 APK 真的交给了系统（旧路 ACTION_VIEW 成功，或 29+ 会话已提交）。 */
    fun installRequested(context: Context, currentVersionCode: Long) =
        record(context, UpdateTelemetryEvents.installRequested(currentVersionCode))

    /**
     * 拍 6 安装终态：会话回执落到某一档结局那一刻（[InstallSessionOutcome] 的三分类是唯一来源）。
     *
     * [outcome] 取 core 的 `OUTCOME_*` 三值之一，[status] 只在失败档交原始状态码；
     * 通知与 Toast 说的话由回执面自己说，这一拍只数数、不碰文案。
     */
    fun installResult(context: Context, outcome: String, status: Int?, currentVersionCode: Long) =
        record(context, UpdateTelemetryEvents.installResult(outcome, status, currentVersionCode))

    /**
     * 拍 7（U10）：数据包应用终态，成功与失败两格都发（Failed 那一格不再是静默的）。
     *
     * 成功档 [kind] 必交实际档位（[UpdateManager.ApplyOutcome.Done.kind]，经 core 的唯一
     * 映射落标签）；失败档 [failure] 交 [UpdateManager.ApplyOutcome.FailureCategory] 的
     * label（短标签），`problem` 原话一个字都不进漏斗。
     */
    fun assetsApplied(
        context: Context,
        stagedVersion: Long?,
        outcome: String,
        kind: DataArtifactKind?,
        failure: String?,
    ) = record(context, UpdateTelemetryEvents.assetsApplied(stagedVersion, outcome, kind, failure))

    /**
     * 冲刷点：**只能在后台线程调**（主线程禁网，闸在 [telemetrySendPort] 与 [bridgeOf] 两处，
     * 误调只会得到「这批留下次」）。
     *
     * 它是同步的，最坏 = [com.eratw.port.core.diag.ErrorReportUpload.TIMEOUT_MS] 连接 + 同量读
     * （端点慢的时候十秒级），所以**只能挂在不等它的线程上**：检查线程里那一发排在
     * `mainHandler.post` 之后（单飞闸的释放不因为它延后），装载前那一拍走 [flushPendingAsync]。
     */
    fun flushPending(context: Context) {
        runCatching { bridgeOf(context)?.flush() }
            .onFailure { note("更新漏斗遥测冲刷失败（不影响更新面）", it) }
    }

    /**
     * 异步冲刷（冷启动那一拍专用，形状照 [ErrorReportUploader.uploadPendingAsync]）：
     * 起一条 daemon 线程去发，调用线程一次网络都不等。
     *
     * 为什么必须有这一档：`GameHost.ensureAssetsInstalled` 的返回值是「游戏能不能开始装载」，
     * 在它后面同步发一趟 HTTP，等于让端点的快慢决定玩家看到首屏的时间 —— 那是本末倒置。
     * 通道关 / 端点未配置时这一发起来也只是白跑一趟判断，出站字节恒为零。
     */
    fun flushPendingAsync(context: Context) {
        val app = runCatching { context.applicationContext }.getOrNull() ?: return
        Thread({
            runCatching { flushPending(app) }
                .onFailure { note("更新漏斗遥测的后台冲刷没跑起来（不影响更新面）", it) }
        }, "eratw-update-telemetry").apply { isDaemon = true }.start()
    }
}

/**
 * 通道闸 + 缓冲 + **主线程中继**的组合口（U6；实例类，不是又一根静态可变全局 —— 单例在
 * [UpdateTelemetryWiring] 那一格，本类由测试直接造出真件跑真判定）。
 *
 * ## 钉死的是三条「判错就是事故」的闸
 *
 * 1. **通道关 = 一个字节都不出网，也不落盘**：[record] 与 [flush] 都在第一行问
 *    [channelEnabled]，关着的时候连 core 的缓冲都不进 —— 事件不落盘就不会在下次开闸时
 *    被一起带走，「关了还攒」等于把玩家关通道之前与之后的行为混成一坨。
 *    出包侧的第二道闸（端点未配置）在 [UpdateTelemetrySink.flush] 里，本类不重复判；
 * 2. **主线程一次磁盘 IO 都不做**：core 的 `record` 每记一条就整批覆盖写一次待发行，
 *    而弹窗那一拍（展示 + 玩家点按钮）跑在主线程上 —— 直接记就是「落在一次点击里的磁盘写」，
 *    正是 [StrictModeGuard] 类头点名的那个缺陷形状（本仓真机出过两次的那一类）。
 *    所以主线程的事件先进 [relay] 这一格**内存**（不落盘、不出网、不读盘），由下一个后台拍
 *    （检查线程 / 下载线程 / 装载前那拍）排空后交给 core。代价如实写明：进程在排空之前死掉，
 *    中继里那几条就没了 —— 与基座「计数漏发可容忍、重发无妨」同口径，换来的是玩家点按钮
 *    那一刻一次磁盘写都不做；
 * 3. **任何异常都不外逃**：core 的 `record` 会写文件、`flush` 会走网络，两边抛什么都只
 *    得到 `false` + 一行日志（连日志本身都抛也不逃）。调用点（六拍）因此不需要为
 *    「遥测把下载/游戏搞崩」留神。
 */
internal class UpdateTelemetryBridge(
    private val telemetry: UpdateTelemetry,
    private val channelEnabled: () -> Boolean,
    private val isMainThread: () -> Boolean,
    private val log: (String, Throwable?) -> Unit,
) {

    /** 主线程落点的内存中继：见类头第 2 条。 */
    private val relay = ArrayDeque<UpdateTelemetry.Event>()
    private val relayLock = Any()

    /** 记一条事件；通道关、事件为 null、core 判这条不合法、任何异常 —— 一律 false，什么都不写。 */
    fun record(event: UpdateTelemetry.Event?): Boolean = try {
        when {
            event == null -> false
            !channelEnabled() -> false
            isMainThread() -> {
                holdForBackground(event)
                true
            }
            else -> {
                drainRelay()
                telemetry.record(event)
            }
        }
    } catch (t: Throwable) {
        note("更新漏斗遥测记录被异常挡住（不影响更新面）", t)
        false
    }

    /**
     * 手动冲刷；通道关时**不发**（盘上那批留着，下次后台冲刷再试），主线程时也不发 ——
     * 冲刷要先动 core 的缓冲与盘，两样都不许在主线程做。
     */
    fun flush(): Boolean = try {
        when {
            !channelEnabled() -> {
                // 关闸即弃：中继里是还没记账的计数，留着等于「关了还在攒」。
                synchronized(relayLock) { relay.clear() }
                false
            }
            isMainThread() -> false
            else -> {
                drainRelay()
                telemetry.flushNow()
            }
        }
    } catch (t: Throwable) {
        note("更新漏斗遥测冲刷被异常挡住（不影响更新面）", t)
        false
    }

    /** 观测口：待发条数（含主线程中继里还没交给 core 的那几条）。 */
    fun pendingCount(): Int = runCatching {
        telemetry.pendingCount() + synchronized(relayLock) { relay.size }
    }.getOrDefault(0)

    /** 主线程落点：只进内存，容量满丢最旧（方向与类头第 2 条的代价一致）。 */
    private fun holdForBackground(event: UpdateTelemetry.Event) {
        synchronized(relayLock) {
            if (relay.size >= RELAY_MAX) relay.removeFirst()
            relay.addLast(event)
        }
    }

    /** 后台拍顺手排空中继：摘取时持锁，交给 core 之后由 core 自己的锁管。 */
    private fun drainRelay() {
        val pending = synchronized(relayLock) {
            if (relay.isEmpty()) return
            val snapshot = relay.toList()
            relay.clear()
            snapshot
        }
        for (event in pending) telemetry.record(event)
    }

    /** 取证面自己也兜住：连记日志都抛了仍不许把异常交给更新面（这是本类唯一的「异常之异常」）。 */
    private fun note(message: String, error: Throwable?) {
        runCatching { log(message, error) }
    }

    companion object {
        /** 中继容量（条）：够两三回弹窗会话（展示 + 一次动作）；溢出丢最旧的一条。 */
        const val RELAY_MAX: Int = 16
    }
}

/**
 * 待发行落盘（[UpdateTelemetry.Storage] 的 app 侧实现，U6）：一行一条事件，格式与发送侧同一套。
 *
 * 口径：
 * - 读不出来（没有文件 / IO 错 / 权限问题）= 空表，core 的还原面对坏行本来也只跳过不抛；
 * - 写是**整批覆盖**（core 每次记录都交全量），先写临时文件再改名，进程在写一半时被杀
 *   留下的是上一版的完整文件，而不是半行 JSON 让下次还原整批跳过；
 * - 改名不动就退回「覆盖写」，两种都不许把异常抛回记录点。
 */
internal class FileStorage(private val file: File) : UpdateTelemetry.Storage {

    override fun load(): List<String> = runCatching {
        if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    override fun save(lines: List<String>) {
        runCatching {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            if (lines.isEmpty()) {
                if (file.isFile) file.delete()
                return@runCatching
            }
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }
}
