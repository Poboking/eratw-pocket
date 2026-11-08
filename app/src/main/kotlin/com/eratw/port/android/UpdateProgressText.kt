package com.eratw.port.android

import com.eratw.port.core.update.UpdatePolicy

/**
 * 下载**过程与结局**的文案判读层（EP4 U0 从 UpdateUi.kt 的段 D 拆出，归 U1 车道）。
 *
 * 装什么：进度通知的正文（已下/总量/百分比/还剩，加 EP4 U1 补的**速度**与「约 X 后下完」、
 * 以及在等待/换地址时的**状态行**）、开跑话术（授权/拒绝两种）、整包与数据包完成、取消、
 * 失败（EP4 U1 起**按失败类别各说各话**）—— 全部是与 Android 无关的纯函数：
 * 判定写在这里，壳层只取数（契约 §0.2）。
 * 为什么单独成文件：下载可见性与检查态是两条并行车道各自的改动面（契约 §1 所有权表），
 * 合成一个 object 就切不开文件所有权；[UpdateDownloadService] 的通知正文与 [UpdateUi] 的开跑 Toast 都取这里。
 *
 * ## 三条「算不出就别说」的纪律（EP4 U1）
 *
 * - **速度与预报**只吃 [UpdateManager.download] 已经给出的累计字节回调与一个毫秒时钟，
 *   窗口不够宽、总量没声明、速度算不出正数 ⇒ 一律退回原有形态，绝不编一个数（契约 §0.3）；
 * - **状态行**（「正在等一等接着下」「正在换另一条更新地址试」）只能由壳层看得见的信号判：
 *   字节数多久没动 + 整段下载已经跑了多久。**慢源真的在下时必须闭嘴**——字节每有新内容进来
 *   就不许提「在重试」，所以判据的主语是「多久没动」而不是「百分比没涨够」；
 * - **失败分类**只读 [UpdateDownloadPlan.FailureKind] 做文案映射：本层不改判据、不新增类别，
 *   分类权在 [UpdateManager] 与 [UpdateDownloadPlan]（契约 §0.3）。
 *
 * 玩家面文案零基础可读（契约 §0.1）：本文件的句子不许出现「镜像 / 清单 / 摘要 / sha / Range /
 * versionCode」这类术语——用例把这条钉住了（见 UpdateProgressTextTest）。
 *
 * ## EP4 U3b：句子全部来自 [UpdateCopy]
 *
 * 与 U3a 在 [UpdateUiText] 定下的形状同构（契约 §1 硬规矩 1）：
 * - 需要玩家面词句的函数**第一个参数收 `copy: UpdateCopy`**，其余参数与判据一律不动；
 * - 分支、行数、顺序、` · ` 与换行这些**结构**留在本层，表里只放词（带读数的句子是 `{0}` 模板）；
 * - 语言由壳层一次取好传进来（下载服务走 `UpdateUi.copyFor(applicationContext)`），本层不判语言、
 *   不读系统 Locale；
 * - **下载通知里那一件的名字在渲染期按 `copy` 现取**（[labelFor]），不再吃成单那一刻存下来的旧标签：
 *   否则英文里点下载、回中文看通知就会拼出「英文标签 + 中文句子」的混排。
 *
 * 文件末尾有一组**兼容口**（不带 `copy` 的旧签名）：它们只把调用方原样转给 [UpdateCopy.ZH]，
 * 存在的唯一理由是那几个调用点在本车道的所有权之外（`UpdateUi.kt` 的开跑 Toast、`SettingsActivity.kt`
 * 的「接着下」按钮，以及钉中文原文的既有用例）。**组长接线传 copy 之后就该删掉**，接线清单在 U3b 的交付报告里；
 * 用例把「兼容口的数量」钉住了，接线完成后删一个就要在这里报一次。
 * 判定与结构一处都没因为兼容口而变化。
 */
object UpdateProgressText {

    /**
     * 速度用的定长滑动窗口宽度（毫秒）。
     *
     * 进度回调是**每读着一批就一次**（UpdateManager.kt:607-617 的读循环，1 MiB 缓冲、实际一次返回多少
     * 由网络给多少决定），而一次读阻塞最多 30 秒就抛（连接/读超时 15/30 秒，UpdateManager.kt:122-123、
     * :904）⇒ 正常在下的连接里回调是连续的，15 秒窗口足够采到两点以上。
     * 窗口太短会被单次读阻塞抖成「算不出」，太长则把「刚刚换了一条地址」摊平成旧速度。
     * 窗口外的点由 [withSampleAdded] 裁掉。
     */
    const val SPEED_WINDOW_MILLIS = 15_000L

    /** 窗口里最早一点与最新一点至少要隔这么久才谈得上速度：两点隔半秒的差值是抖动，不是速度。 */
    const val SPEED_MIN_SPAN_MILLIS = 3_000L

    /** 最多留多少个采样点（内存闸，与窗口宽度配合；16 点足够覆盖 15 秒的回调密度）。 */
    const val SPEED_MAX_SAMPLES = 16

    /** 预计还要多久的可信上限：超过一天就不报——这种预报几乎全是窗口噪声，报出来等于编数。 */
    const val ETA_MAX_MILLIS: Long = 24L * 60L * 60L * 1000L

    /**
     * 「暂时没有新内容进来」的下限（毫秒）。
     *
     * 证据：一次读阻塞超过 30 秒必抛（读超时 30 秒，UpdateManager.kt:123/:904），抛出后本条 attempt 结束、
     * 进第一档退避 1 秒（UpdatePolicy.kt:50 的 1/2/5/15/30 秒）⇒ **一条真在吐字节的连接不可能静默 45 秒**，
     * 静默到这个量只剩三种解释：在退避等待、在换下一条地址、或在本机重算已下前缀的摘要
     * （续传时 digestPrefix 要把盘上那几百 MiB 读一遍，UpdateManager.kt:596-605）。
     * 句子因此三种都点一下、并冠「多半」，绝不写成断言。
     * 取 45 秒 = 一个整回合（连接 15 + 读 30）再加一段等待 ⇒ 单次慢读块不会被说成在重试。
     */
    const val SILENT_WAIT_MILLIS = 45_000L

    /**
     * 「多半在换另一条更新地址」的静默下限（毫秒）。
     *
     * 证据：一条源把退避档位走完要 1+2+5+15+30 = 53 秒（UpdateManager.kt:455 的 KDoc 与
     * UpdatePolicy.kt:50），5 档之间各自还要吃一次连接/读超时。120 秒零字节 ⇒ 这条源的档位
     * 早已用完（[UpdateManager.download] 的循环只能推进到下一条）。
     */
    const val SILENT_SWITCH_MILLIS = 120_000L

    /**
     * 播报「在换地址」额外要求的整段下载已耗时（毫秒）。
     *
     * 为什么要有这一条：刚开跑的**第一回合**本身就能静默 15+30+53 约 100 秒（建连 + 读超时 + 满档退避），
     * 这时「换地址」可能只是「第一条地址还没下成一行字节」。要求整段已跑 3 分钟，
     * 才允许说推进到下一条——把「刚出发」和「真卡住」分开。
     */
    const val SWITCH_MIN_ELAPSED_MILLIS = 180_000L

    /**
     * 磁盘不足这类「玩家自己能动手」的失败，在 problem 原话里的固定起手（UpdateManager.kt:490-496 造的句子）。
     *
     * **它不是玩家面文案**：这是拿去**匹配**上游原话的锚点（原话由 UpdateManager 造，本波不扩它的语言面），
     * 搬进三语表反而会让匹配失效——所以它留在这里，U3b 的防扩散守卫把它单独点名放行并钉住「只许这一个」。
     */
    private const val SPACE_PROBLEM_HINT = "空间不够"

    /** 速度窗口里的一个采样点：累计字节 + 采样时刻（只用差值，绝对时钟值从不参与判定）。 */
    class ProgressSample(val doneBytes: Long, val atMillis: Long)

    /**
     * 通知正文的一次快照：壳层只负责把这几个数取来，**判定全在本文件**。
     *
     * @param doneBytes 最新一次回调的累计字节（含续传认领的盘上前缀，与 totalBytes 同基准）
     * @param totalBytes 声明总量；小于等于 0 = 拿不到声明值
     * @param samples 速度窗口内的采样点（[withSampleAdded] 维护，时间升序；顺序错了也会先排）
     * @param nowMillis 本次渲染的时刻（与 samples 同一时钟源）
     * @param peakDoneBytes 本单见过的最大累计字节：最新值比它小 = 前缀作废、正从头重下（铁证）
     * @param silentMillis 距离上一次「有新字节进来」过了多久
     * @param elapsedMillis 本单从开跑到现在过了多久
     */
    class ProgressSnapshot(
        val doneBytes: Long,
        val totalBytes: Long,
        val samples: List<ProgressSample>,
        val nowMillis: Long,
        val peakDoneBytes: Long,
        val silentMillis: Long,
        val elapsedMillis: Long,
    )

    // ------------------------------------------------------------------
    // 进度通知正文
    // ------------------------------------------------------------------

    /**
     * 那一件在句子开头的名字：**渲染期按 [copy] 现取**（EP4 U3b 接掉混排）。
     *
     * 为什么不再吃 [DownloadJob.label]：那是**成单那一刻**的玩家语言，会随单子穿过意图与暂存；
     * 玩家中途换语言（或 U3a 之前留下的单子）就会拼出「英文标签 + 中文句子」这种两头不靠的一行。
     * 通知这一路只剩 `kind` 这一个稳定读数，标签由本函数现推（契约 U3b 硬事 2）。
     */
    fun labelFor(copy: UpdateCopy, kind: UpdateDownloadKind): String =
        if (kind == UpdateDownloadKind.Apk) copy.apkLabel else copy.assetsLabel

    /**
     * 进度通知的一行正文：已下/总量/百分比/还剩多少。
     *
     * `totalBytes ≤ 0` 只可能出现在调用方拿不到声明值的路（管线本身拒绝无名无数的产物），
     * 这时退化成「已下 X」而不是除零。
     */
    fun progressText(copy: UpdateCopy, label: String, doneBytes: Long, totalBytes: Long): String {
        if (totalBytes <= 0L) {
            return copy.fill(copy.progressNoTotalFormat, label, UpdatePolicy.sizeLabel(doneBytes.coerceAtLeast(0L)))
        }
        val percent = ((doneBytes.coerceAtLeast(0L) * 100L) / totalBytes).coerceAtMost(100L)
        return copy.fill(
            copy.progressLineFormat,
            label,
            percent,
            UpdatePolicy.sizeLabel(doneBytes),
            UpdatePolicy.sizeLabel(totalBytes),
            UpdatePolicy.sizeLabel((totalBytes - doneBytes).coerceAtLeast(0L)),
        )
    }

    /** 同上，但名字按 [kind] 与 [copy] 现推（下载通知走这一支）。 */
    fun progressText(copy: UpdateCopy, kind: UpdateDownloadKind, doneBytes: Long, totalBytes: Long): String =
        progressText(copy, labelFor(copy, kind), doneBytes, totalBytes)

    /**
     * 带速度/预报/状态行的进度正文（EP4 U1-①②）。
     *
     * 拼法：原有的那一行**一字不动**，后面按需追加「约 X/秒」「约 X 后下完」，再用换行补状态行
     * （通知走 BigTextStyle，换行看得见）。算不出的那一句就是**没有那一句**：
     * 快照全空时本方法的返回与三参版完全相同。
     */
    fun progressText(copy: UpdateCopy, label: String, snapshot: ProgressSnapshot): String {
        val body = progressText(copy, label, snapshot.doneBytes, snapshot.totalBytes)
        val speed = speedBytesPerSecond(snapshot.samples, snapshot.nowMillis)
        val clauses = ArrayList<String>(2)
        if (speed != null) clauses.add(speedClause(copy, speed))
        etaMillis(snapshot.totalBytes, snapshot.doneBytes, speed)?.let {
            clauses.add(copy.fill(copy.etaClauseFormat, durationLabel(copy, it)))
        }
        val withClauses = if (clauses.isEmpty()) body else "$body · ${clauses.joinToString(" · ")}"
        val stall = stallLine(
            copy,
            snapshot.peakDoneBytes,
            snapshot.doneBytes,
            snapshot.silentMillis,
            snapshot.elapsedMillis,
        )
        return if (stall == null) withClauses else "$withClauses\n$stall"
    }

    /** 同上，但名字按 [kind] 与 [copy] 现推。 */
    fun progressText(copy: UpdateCopy, kind: UpdateDownloadKind, snapshot: ProgressSnapshot): String =
        progressText(copy, labelFor(copy, kind), snapshot)

    /**
     * 追加一个采样点并裁窗口（壳层每次进度回调调一次；纯函数，返回新列表）。
     *
     * 裁剪口径两条：只留窗口宽度之内、最多 [SPEED_MAX_SAMPLES] 点。留不住点意味着窗口跨度不足，
     * [speedBytesPerSecond] 就会返回 null —— 那是「宁可不报速度」的代价，不是缺陷。
     */
    fun withSampleAdded(samples: List<ProgressSample>, sample: ProgressSample, nowMillis: Long): List<ProgressSample> {
        val windowStart = nowMillis - SPEED_WINDOW_MILLIS
        val kept = samples.filter { it.atMillis >= windowStart && it.atMillis <= nowMillis }.toMutableList()
        kept.add(sample)
        kept.sortBy { it.atMillis }
        return if (kept.size > SPEED_MAX_SAMPLES) kept.subList(kept.size - SPEED_MAX_SAMPLES, kept.size) else kept
    }

    /**
     * 窗口速度（字节/秒）；**算不出就返回 null**（不足两点 / 跨度不足 / 差值非正 / 全是旧点）。
     *
     * 差值非正包含两种真实情况：静默期间字节没动（此时应退回不报速度，而不是报 0 让 ETA 变成无穷）、
     * 以及前缀作废后从头重下（累计值倒退）——后者由 [stallLine] 用「正在重新下一遍」另行说清。
     */
    fun speedBytesPerSecond(samples: List<ProgressSample>, nowMillis: Long): Long? {
        val windowStart = nowMillis - SPEED_WINDOW_MILLIS
        val points = samples.filter { it.atMillis >= windowStart && it.atMillis <= nowMillis }.sortedBy { it.atMillis }
        if (points.size < 2) return null
        val first = points.first()
        val last = points.last()
        val span = last.atMillis - first.atMillis
        if (span < SPEED_MIN_SPAN_MILLIS) return null
        val delta = last.doneBytes - first.doneBytes
        if (delta <= 0L) return null
        return delta * 1000L / span
    }

    /** 还要多久下完（毫秒）；总量未声明 / 速度缺失 / 已经下完 / 超出可信上限 ⇒ null（不编数）。 */
    fun etaMillis(totalBytes: Long, doneBytes: Long, speedBytesPerSecond: Long?): Long? {
        if (speedBytesPerSecond == null || speedBytesPerSecond <= 0L) return null
        if (totalBytes <= 0L) return null
        val remaining = totalBytes - doneBytes
        if (remaining <= 0L) return null
        val eta = remaining * 1000L / speedBytesPerSecond
        return if (eta > ETA_MAX_MILLIS) null else eta
    }

    /** 「约 X/秒」：体积读数直接用 core 的 [UpdatePolicy.sizeLabel]，与本层其余读数同一口径。 */
    fun speedClause(copy: UpdateCopy, speedBytesPerSecond: Long): String =
        copy.fill(copy.speedClauseFormat, UpdatePolicy.sizeLabel(speedBytesPerSecond.coerceAtLeast(0L)))

    /**
     * 人话时长：秒 / 分（秒） / 小时（分），一律向上取整，绝不出现「0 秒后下完」。
     *
     * 选哪一档是**结构**（留在本层），每档的句式才是词（在表里）：三种语言都走同一棵档位树，
     * 所以同一毫秒数在三语下都只有那一句，不多一行也不少一行。
     */
    fun durationLabel(copy: UpdateCopy, millis: Long): String {
        val totalSeconds = ((millis + 999L) / 1000L).coerceAtLeast(1L)
        if (totalSeconds < 60L) return copy.fill(copy.durationSecondsFormat, totalSeconds)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        if (minutes < 60L) {
            return if (seconds == 0L || minutes >= 10L) copy.fill(copy.durationMinutesFormat, minutes)
            else copy.fill(copy.durationMinutesSecondsFormat, minutes, seconds)
        }
        val hours = minutes / 60L
        val restMinutes = minutes % 60L
        return if (restMinutes == 0L) copy.fill(copy.durationHoursFormat, hours)
        else copy.fill(copy.durationHoursMinutesFormat, hours, restMinutes)
    }

    /**
     * 卡住时的状态行；不像卡住就返回 null（EP4 U1-②）。
     *
     * 三条判据按可信度从高到低：
     * 1. **累计字节比峰值小** = 盘上前缀作废、这一轮从头重写（[UpdateManager] 的两条铁证路径：
     *    服务端不认续传请求时截断重写 UpdateManager.kt:593-595，摘要/超长类失败删掉 `.part`
     *    换地址 UpdateManager.kt:612/630/638）⇒ 这是看得见的实事，敢说；
     * 2. 静默满 [SILENT_SWITCH_MILLIS] 且整段已跑满 [SWITCH_MIN_ELAPSED_MILLIS] ⇒ 「多半在换另一条更新地址」；
     * 3. 静默满 [SILENT_WAIT_MILLIS] ⇒ 「多半在等一等接着下」；一个字节都还没收到时改口「还没收到内容」
     *    （不许在零字节时宣称「已经下了一些」）。
     *
     * 反例（必须闭嘴）：慢源每秒都吐字节 ⇒ 1、2、3 全不成立 ⇒ null；
     * 单个慢读块（静默 30 秒）⇒ 三条都不成立 ⇒ null。
     */
    fun stallLine(
        copy: UpdateCopy,
        peakDoneBytes: Long,
        doneBytes: Long,
        silentMillis: Long,
        elapsedMillis: Long,
    ): String? = when {
        doneBytes < peakDoneBytes -> copy.stallRestartLine
        silentMillis >= SILENT_SWITCH_MILLIS && elapsedMillis >= SWITCH_MIN_ELAPSED_MILLIS -> copy.stallSwitchLine
        silentMillis >= SILENT_WAIT_MILLIS ->
            if (peakDoneBytes > 0L) copy.stallWaitLine else copy.stallNoByteLine
        else -> null
    }

    // ------------------------------------------------------------------
    // 开跑 / 完成 / 取消
    // ------------------------------------------------------------------

    /** 下载起不来/看不看得见通知的两种开跑话术（不许把「看不见」说成「没有」）。 */
    fun startedToast(copy: UpdateCopy, notifyVisible: Boolean): String =
        if (notifyVisible) copy.startedNotifyLine else copy.startedNoNotifyLine

    /**
     * 整包下完：通知正文（点击拉起安装器）。
     *
     * 只有一句「点这条通知」是**通知栏可见**才成立的话，所以 [notifyVisible] 为假时换成
     * 同屏可达的补救说法：再点一次「下载」会立刻把已经核对好的文件交给安装器
     * （downloadOnce 开头那段「已有核对通过的就直接用」，UpdateManager.kt:541-545）。
     */
    fun apkDoneText(copy: UpdateCopy): String = copy.apkDoneLine

    /** 整包下完（按能不能看见通知分两种说法，EP4 U1-④）。 */
    fun apkDoneText(copy: UpdateCopy, notifyVisible: Boolean): String =
        if (notifyVisible) copy.apkDoneLine else copy.apkDoneNoNotifyLine

    /** 数据包下完：应用发生在下一次启动装载前，这里不许谎报「已生效」。 */
    fun assetsDoneText(copy: UpdateCopy): String = copy.assetsDoneLine

    /** 取消的一句话：断点保留是这条通道最要紧的承诺，必须说。 */
    fun cancelledText(copy: UpdateCopy): String = copy.cancelledLine

    /**
     * 取消的一句话（通知栏看不见时的版本，EP4 U1-④）：不许提「点这条通知」。
     *
     * 取消本身走的是通知上的按钮，所以看不见通知的玩家得知道**另一个入口**：
     * 回游戏里那个下载弹窗同样能停/能续（[UpdateUi] 的弹窗与 [UpdateDownloadService.start] 都下同一单）。
     */
    fun cancelledText(copy: UpdateCopy, notifyVisible: Boolean): String =
        if (notifyVisible) copy.cancelledLine else copy.cancelledNoNotifyLine

    // ------------------------------------------------------------------
    // 失败：七类失败各说各话（EP4 U1-③）
    // ------------------------------------------------------------------

    /** 失败：UpdateManager 的 problem 原话本就带原因，这里只加外壳层能补的上下文。 */
    fun failedText(copy: UpdateCopy, problem: String): String =
        if (problem.isBlank()) copy.failedUnknownLine else copy.fill(copy.failedLegacyFormat, problem)

    /**
     * 失败正文 = 原话 + **这一类失败的处置**（EP4 U1-③）。
     *
     * [notifyVisible] 决定「下一步去哪儿点」：看得见通知栏才许说「点这条通知」，
     * 否则只能给同屏可达的入口（[retryEntry]）。
     */
    fun failedText(copy: UpdateCopy, problem: String, kind: UpdateDownloadPlan.FailureKind?, notifyVisible: Boolean): String {
        val cause = if (problem.isBlank()) copy.causeUnknownPhrase else problem
        val entry = if (notifyVisible) copy.retryInNotifyPhrase else retryEntry(copy, false)
        return copy.fill(copy.failedBodyFormat, cause, failureAction(copy, kind, problem), entry)
    }

    /**
     * 通知栏看不见时的一行补救话（EP4 U1-④）：Toast 只有一行的余地，
     * 所以只给「你现在能做什么 + 去哪儿重试」，不带 [UpdateManager] 的 problem 原话
     * （原话长且带术语，留在通知与 logcat 里取证用）。
     */
    fun failureToast(copy: UpdateCopy, kind: UpdateDownloadPlan.FailureKind?): String =
        copy.fill(copy.failureToastFormat, failureAction(copy, kind, ""), retryEntry(copy, false))

    /** 重试入口的两种说法：只有看得见通知栏那一支才许承诺「点这条通知」。 */
    fun retryEntry(copy: UpdateCopy, notifyVisible: Boolean): String =
        if (notifyVisible) copy.retryEntryNotifyLine else copy.retryEntryGameLine

    /**
     * 设置页那颗「接着下」的正文（组长接线 EP4 U1-④ 的同屏入口落到屏上的那句）。
     *
     * 为什么要点名「哪一份」：一次失败可能是整包也可能是数据包，两者的下一步完全不同（一个要装、
     * 一个等下次启动自动应用）；只写「重试」的玩家不知道自己在等什么。断点这件事必须说 ——
     * 那是这条通道最值钱的承诺，也是玩家敢再点一次的唯一理由。
     */
    fun settingsRetryLabel(copy: UpdateCopy, artifactLabel: String): String =
        copy.fill(copy.settingsRetryLabelFormat, artifactLabel)

    /** 同上，但「哪一份」按 [kind] 与 [copy] 现推（混排修复；接线时优先用这一支）。 */
    fun settingsRetryLabel(copy: UpdateCopy, kind: UpdateDownloadKind): String =
        settingsRetryLabel(copy, labelFor(copy, kind))

    /**
     * 失败类别 → 一句「你现在能做什么」（EP4 U1-③）。
     *
     * 分类权在 [UpdateDownloadPlan.FailureKind]（UpdateDownloadPlan.kt:68-88）与本层的映射，
     * 处置人真的不同，所以句子必须不同：网络类是「等一会儿再来一遍」，源上没有文件与内容对不上是
     * 「你做什么都没用，等下次发版」，空间不够是「清手机的地方」，明文地址是「换网络或等下次发版」。
     *
     * `kind == null` 是真实存在的一支：[UpdateManager.download] 把所有候选地址都试完才收手时
     * 只带 [problem]（UpdateManager.kt:519 造的那条没有 kind），所以这里必须给得出话。
     * 磁盘不足在 [UpdateManager.kt:490-496] 归进 [UpdateDownloadPlan.FailureKind.LocalFileSystem]
     * 且原话以 [SPACE_PROBLEM_HINT] 起手，故按原话再分一支——**只分文案，不改判据**。
     *
     * 三语同一条决策对应同一句话（契约 U3b 硬事 3）：`when` 的分支一处不多一处不少，
     * 七类 + 两支无类别 = 每档九句，用例按 [UpdateCopy] 三档各跑一遍形状。
     */
    fun failureAction(copy: UpdateCopy, kind: UpdateDownloadPlan.FailureKind?, problem: String = ""): String {
        val spaceShort = problem.contains(SPACE_PROBLEM_HINT)
        return when {
            kind == null && problem.isBlank() -> copy.actionUnknownLine
            kind == null -> copy.actionAllSourcesLine
            spaceShort -> copy.actionSpaceLine
            else -> when (kind!!) {
                UpdateDownloadPlan.FailureKind.Network -> copy.actionNetworkLine
                UpdateDownloadPlan.FailureKind.HttpStatus -> copy.actionHttpStatusLine
                UpdateDownloadPlan.FailureKind.MissingOnMirror -> copy.actionMissingLine
                UpdateDownloadPlan.FailureKind.PlainHttp -> copy.actionPlainHttpLine
                UpdateDownloadPlan.FailureKind.DigestMismatch -> copy.actionDigestLine
                UpdateDownloadPlan.FailureKind.OversizeStream -> copy.actionOversizeLine
                UpdateDownloadPlan.FailureKind.LocalFileSystem -> copy.actionLocalIoLine
            }
        }
    }

    // ------------------------------------------------------------------
    // EP4 U3b 兼容口：不带 copy 的旧签名（接线后删；见文件头 KDoc 与 U3b 交付报告）
    //
    // 这些口子**不判读、不重写句子**，只是把 [UpdateCopy.ZH] 代进去，让所有权外的调用点
    // （钉中文原文的既有用例 UpdateUiTest / UpdateDownloadPlanTest）在接线之前照常编译、照常绿。
    // 设置页那颗「接着下」已改按 kind 现取（组长接线），开跑 Toast 也已补传 copy（L5 P1-2），
    // 那两个口都已删。只留**真的有外部调用点**的那几个（用例把数量钉成 6）：接线一个删一个，
    // 别攒成第二套词源。
    // ------------------------------------------------------------------

    /** 兼容口：中文正文 + 调用方给的旧标签（UpdateUiTest 钉拆前形态用）。 */
    fun progressText(label: String, doneBytes: Long, totalBytes: Long): String =
        progressText(UpdateCopy.ZH, label, doneBytes, totalBytes)

    /** 兼容口：中文整包完成（UpdateUiTest:194）。 */
    fun apkDoneText(): String = apkDoneText(UpdateCopy.ZH)

    /** 兼容口：中文数据包完成（UpdateUiTest:195）。 */
    fun assetsDoneText(): String = assetsDoneText(UpdateCopy.ZH)

    /** 兼容口：中文取消（UpdateUiTest:196）。 */
    fun cancelledText(): String = cancelledText(UpdateCopy.ZH)

    /** 兼容口：中文失败正文（拆前那一行形态，UpdateUiTest:197）。 */
    fun failedText(problem: String): String = failedText(UpdateCopy.ZH, problem)

    /** 兼容口：中文处置句（UpdateDownloadPlanTest 五处调用点）。 */
    fun failureAction(kind: UpdateDownloadPlan.FailureKind?, problem: String = ""): String =
        failureAction(UpdateCopy.ZH, kind, problem)
}
