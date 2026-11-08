package com.eratw.port.android

import com.eratw.port.core.update.DataArtifactKind

/**
 * 下载进度的**进程内只读读数**（U5 P2-12）：已下 / 总量 / 百分比 / 是否在跑。
 *
 * 纯数据类，不含 Android 依赖，也不自带状态：进程里那一份由
 * [UpdateDownloadService.progress] 暴露（活字段现读），设置页与 [UpdateUi.channelReadout]
 * 取的都是它。百分比的判定与 [UpdateProgressText.progressText] 同口径（见
 * [UpdateChannelReadout.progressPercent]）；那一行的正文直接复用
 * [UpdateProgressText.progressText]，不在本层另拼一套「已下 / 总量 / 还剩」。
 *
 * @param kind 在下的那一条通道，渲染行按它与当前语言现取名字；不在跑时为 null。
 */
data class DownloadProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val percent: Long,
    val running: Boolean,
    val kind: UpdateDownloadKind? = null,
) {
    companion object {
        /** 没有下载在跑时的读数：设置页据此不显示那一行。 */
        val IDLE: DownloadProgress = DownloadProgress(0L, 0L, 0L, false, null)
    }
}

/**
 * 上一次检查在数据通道上**选出的档位与判据摘要**（U11 P2-13 可观测性）。
 *
 * 差量静默失效不报错：玩家每次都拿到全量包、每次都成功，只有流量账单知道「这次为什么下 300 MiB
 * 而不是 12 MiB」。这份读数把那趟选件从只进 logcat 抬到设置页展开层，判据一律留在 core
 * （[DataArtifactKind] 由 `UpdatePolicy.dataArtifactKind` 判出），app 侧只做**存取与读出**。
 *
 * @param kind 上一次检查走的那一档（core 的枚举，app 不自行判档）
 * @param diskTrusted 那次判出的「盘上数据可信不可信」：不可信 ⇒ 差量一律不选、直接走全量
 * @param hasBlockDelta / @param hasFileDelta / @param hasFull 发布那边这次发了哪几件数据包
 *   （「清单发了哪几件」的展开层读数，与 kind 一起才讲得清差量为什么没接上）
 */
data class DataArtifactReadout(
    val kind: DataArtifactKind,
    val diskTrusted: Boolean,
    val hasBlockDelta: Boolean,
    val hasFileDelta: Boolean,
    val hasFull: Boolean,
) {
    /**
     * 玩家面句子（词全在 [UpdateCopy]，本函数只挑档、不判任何事）：
     * 档位标签按 kind 取，盘可信按 [diskTrusted] 取两词之一，「发布给的包」把三格里在的逐件点名、
     * 一件都不在落 [UpdateCopy.dataItemsNone]。
     */
    fun line(copy: UpdateCopy): String {
        val kindLabel = when (kind) {
            DataArtifactKind.BLOCK_DELTA -> copy.dataKindBlockDelta
            DataArtifactKind.FILE_DELTA -> copy.dataKindFileDelta
            DataArtifactKind.FULL -> copy.dataKindFull
            DataArtifactKind.NONE -> copy.dataKindNone
        }
        val trustLabel = if (diskTrusted) copy.dataDiskTrusted else copy.dataDiskUntrusted
        val offered = buildList {
            if (hasBlockDelta) add(copy.dataKindBlockDelta)
            if (hasFileDelta) add(copy.dataKindFileDelta)
            if (hasFull) add(copy.dataKindFull)
        }
        val offeredLabel = offered.joinToString(copy.problemSeparator).ifEmpty { copy.dataItemsNone }
        return copy.fill(copy.techDataArtifactLineFormat, kindLabel, trustLabel, offeredLabel)
    }
}

/**
 * 坏基线拒装账（U13 upd-baccount）：**上一次整包数据被拒装**时落的那条持久记录。
 *
 * 记账点唯一：只在 [UpdateManager.applyStagedBundleIfPresent] 的 REJECTED 出口写（crashed/gave-up
 * 的暂存还在盘上会重试，不写这条账）。检查决策读它做**同键挡板**：「这次要选的 档位+目标版本」
 * 与账上完全一致 ⇒ 不再走同一条下载路，单步回落下一档 —— 不记账的话同一个坏件每轮重下重拒。
 *
 * @param version 被拒的目标数据版本（暂存包声明的版本，即清单 assets 那一版）
 * @param kind 被拒那一趟的档位（包内实况，与检查决策的选件档位同键比对）
 * @param atMillis 拒装时刻（毫秒原值；只做取证读数，不参与判定）
 */
data class RejectedBundleAccount(
    val version: Long,
    val kind: DataArtifactKind,
    val atMillis: Long,
)

/**
 * 挡板生效读数（U13）：上一次检查**确实因为同键被拒过而改了路**，落这一条给上屏。
 *
 * 由检查决策在挡板生效那一刻写（被拒版本 + 回落后的档位）；挡板没生效就清掉，不让旧说明
 * 残留到新版本。上屏走 [UpdateChannelReadout.Readout.techLines] 展开层，玩家面三行不动。
 *
 * @param rejectedVersion 被拒的目标数据版本（与 [RejectedBundleAccount.version] 同源）
 * @param fallbackKind 这次实际改走的档位；[DataArtifactKind.NONE] = 没有下一档（全量也被拒过），
 *   这次暂不取数据包
 */
data class RejectedBundleFallback(
    val rejectedVersion: Long,
    val fallbackKind: DataArtifactKind,
)

/**
 * 设置页「这条更新通道现在到底怎么了」的**判读层**（契约 EP4 U2-①③④）。
 *
 * ## 为什么单独一个 object
 *
 * 壳层（[UpdateUi] + SettingsActivity 的「启动」卡）只能做一件事：把 [UpdateManager] 的读数搬进来。
 * 「怎么写成人话、哪种失败算哪一种、什么时候不许说已生效」全是判读，必须落在纯 JVM 里才钉得住单测
 * （壳层碰一下 `Looper.getMainLooper()` 就炸，本仓的纯 JVM 单测进不去）。
 *
 * ## 三行玩家面 + 一行技术细节（U2-①）
 *
 * 旧摘要长这样：「更新源 2 条，首选 https://eratw-android.asia/updates/ ／ 上次检查在 137 分钟前；
 * 本机整包 12，盘上数据 12 ／ 没有待应用的数据包」——术语、裸 URL、无解释的裸数字，玩家读完不知道
 * 该做什么。现在按**玩家关心的三件事**分行写：
 *
 * 1. 这条通道能不能查（查不了的时候处理人是出包的人，不是玩家）；
 * 2. 上次查到了什么（成功 / 一条都没连上 / 没配地址 / 检查自己出了意外）；
 * 3. 有没有东西在等着应用（下好的数据包只能说「下次启动时会自动装上」，不能说「已生效」）。
 *
 * 更新地址原文、本机版本号、毫秒读数、发布那边的原话这些**取证有用的数字**收进 [Readout.techLines]，
 * 由屏上的「看技术细节」展开层显示；[Readout.playerLines] 里一个都不许出现。
 *
 * ## 词与结构分开（EP4 U3a）
 *
 * 本 object 只留**结构**：哪一种形状写哪几行、什么时候不许说「已生效」。句子一律来自
 * [UpdateCopy]（三语表），需要句子的函数第一个参数收 `copy`，判据与分行一条不动 ——
 * 中文那一组与搬动前逐字相同，英/日同结构。语言由壳层一次取好传进来
 * （[UpdateUi.copyFor] → `UpdateCopy.of(UiPrefs.settingsLanguage(...))`），本层不判语言。
 * 下面的 KDoc 与日志面留中文是给改代码的人看的，不算玩家面文案。
 *
 * ## 结果的形状（U2-③）
 *
 * 自动检查失败以前只进 logcat，屏上看不出来。这里定四个互斥的结果标记（[OUTCOME_OK] 等），
 * 由 [outcomeOf] 一次性判出、壳层原样存进 UpdateManager 的追加键，摘要读它。
 * 注意与 `UpdateManager.KEY_LAST_CHECK` 的分工：那条**只**表示「上次查成功的时间」，语义不许动；
 * 失败的形状与「那次尝试发生在什么时候」走新键 [OUTCOME_UNREACHABLE] 一族。
 */
object UpdateChannelReadout {

    /** prefs 里还没记过任何一次检查（老存档升级上来 / 装完就没网）。 */
    const val OUTCOME_UNKNOWN: String = ""

    /** 那次检查连上了、也读到了能读懂的内容。 */
    const val OUTCOME_OK: String = "ok"

    /** 配了更新地址，但一条都没连上：处理人是玩家的网络与发版那边。 */
    const val OUTCOME_UNREACHABLE: String = "unreachable"

    /** 包里没带更新地址：处理人是出包的人，跟玩家的网络无关。 */
    const val OUTCOME_NO_MIRROR: String = "no-mirror"

    /** 检查自己抛了未预期的错：不影响游戏，下次照常查。 */
    const val OUTCOME_CRASHED: String = "crashed"

    /** 存进偏好里的原话条数上限（超出只在屏上少列几条，数量按真实值报）。 */
    const val MAX_STORED_NOTES: Int = 20

    /** 单条原话的截断长度：防止一条坏镜像地址把整块摘要撑爆。 */
    const val MAX_NOTE_CHARS: Int = 200

    /**
     * 摘要的输入：全部是 [UpdateManager] / [UiPrefs] 已有的读数，壳层只负责搬，不在这里判任何事。
     *
     * @param lastSuccessMillis `KEY_LAST_CHECK` 的语义 —— **只在查成功时写过**，不许拿它当「上次尝试」
     * @param lastAttemptMillis 新键：上一次检查（成功或失败）发生的时间
     * @param outcome [outcomeOf] 判出的结果标记
     * @param notes 那次留下的原话（成功 = 发布那边的提醒，连不上 = 每条地址的失败原因）
     * @param noteCount 原话的真实条数（可能大于 [notes] 的长度，因为存的时候截过）
     * @param stagedDataVersion 已下载、等下次启动应用的数据包版本；小于等于 0 = 没有
     */
    class Snapshot(
        val mirrorCount: Int = 0,
        val preferredMirror: String = "",
        val lastSuccessMillis: Long = 0L,
        val lastAttemptMillis: Long = 0L,
        val outcome: String = OUTCOME_UNKNOWN,
        val notes: List<String> = emptyList(),
        val noteCount: Int = 0,
        val currentVersionCode: Long = -1L,
        val installedDataVersion: Long = 0L,
        val stagedDataVersion: Long = 0L,
        val skippedVersionCode: Long = UpdateUiText.NO_SKIPPED_VERSION,
        val autoChannelWanted: Boolean = true,
        val nowMillis: Long = 0L,
        /** U5 P2-12：进程内只读的下载进度读数；null 或不在跑 = 不显示「正在下载」那一行。 */
        val progress: DownloadProgress? = null,
        /** U11 P2-13：上一次检查选出的数据通道档位摘要；null = 没检查过或检查没跑到选件那一步。 */
        val dataArtifact: DataArtifactReadout? = null,
        /**
         * U13 upd-baccount：挡板生效读数（上一次检查因为同「版本+档位」被拒过而改了路）。
         * null = 挡板没生效（没账、账异键）或没查过 —— 展开层就一个字都不加，不凭空多一行。
         */
        val rejectedFallback: RejectedBundleFallback? = null,
    )

    /**
     * 判读结果。
     *
     * @param playerLines 玩家面分行现状（零基础措辞，无地址、无裸版本号）
     * @param techLines 展开层：地址原文、本机版本读数、毫秒读数、发布那边的原话
     */
    class Readout(
        val playerLines: List<String>,
        val techLines: List<String>,
    ) {
        /** 展开入口要不要出现：没有技术细节就不给一个点了没动静的入口。 */
        val hasTechDetail: Boolean get() = techLines.isNotEmpty()
    }

    /**
     * 一次检查的结果形状判读（**唯一一处**，手动与自动两条路都走它，不许在壳层再判一遍）。
     *
     * 三个输入都是 [UpdateManager.CheckResult] 已经给出的读数：可用地址条数、失败原因条数、
     * 有没有抛错。这里只做「这三者组合成哪一种标记」，不碰任何版本比较与间隔判定。
     */
    fun outcomeOf(mirrorCount: Int, problemCount: Int, crashed: Boolean): String = when {
        crashed -> OUTCOME_CRASHED
        mirrorCount <= 0 -> OUTCOME_NO_MIRROR
        problemCount > 0 -> OUTCOME_UNREACHABLE
        else -> OUTCOME_OK
    }

    /** 「距今多久」的玩家面说法：不足一分钟说「刚刚」，不许写成「0 分钟前」。 */
    fun sinceText(copy: UpdateCopy, atMillis: Long, nowMillis: Long): String? {
        if (atMillis <= 0L) return null
        val minutes = (nowMillis - atMillis).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 1L -> copy.justNow
            minutes < 60L -> copy.fill(copy.minutesAgoFormat, minutes)
            minutes < 60L * 24L -> copy.fill(copy.hoursAgoFormat, minutes / 60L)
            else -> copy.fill(copy.daysAgoFormat, minutes / (60L * 24L))
        }
    }

    /** 原话存进偏好前的编码：逐条截断 + 换行连接（屏上只列前 [MAX_STORED_NOTES] 条）。 */
    fun encodeNotes(notes: List<String>): String =
        notes.take(MAX_STORED_NOTES)
            .joinToString("\n") { if (it.length <= MAX_NOTE_CHARS) it else it.take(MAX_NOTE_CHARS) + "…" }

    /** 读回来：换行拆分，空白行丢掉（老存档里这个键可能压根没写过）。 */
    fun decodeNotes(raw: String): List<String> =
        raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 进度百分比：与 [UpdateProgressText.progressText] 同一判定（负值归零、除零退化、封顶 100）。
     *
     * 本波不扩 [UpdateProgressText] 的所有权面，所以把这条判定收在本判读层；
     * 同一组读数在两处算出的百分比必须逐字一致，由 U5 的对拍用例钉住。
     * 「正在下载」那一行的正文仍直接复用 [UpdateProgressText.progressText]，本函数只服务读数那一格。
     */
    fun progressPercent(doneBytes: Long, totalBytes: Long): Long =
        if (totalBytes <= 0L) 0L
        else ((doneBytes.coerceAtLeast(0L) * 100L) / totalBytes).coerceAtMost(100L)

    /**
     * 可视化进度条的填充比例（UI 只取数、不自己算）：总量未声明时返回 null，让屏上改画扫描光带，
     * 而不是把「算不出」伪装成 0%。判定与 [progressPercent] 同源，负值与超量在上面那一层收口。
     */
    fun progressFraction(doneBytes: Long, totalBytes: Long): Float? =
        if (totalBytes <= 0L) null else progressPercent(doneBytes, totalBytes) / 100f

    /** 整份摘要的判读。 */
    fun of(copy: UpdateCopy, s: Snapshot): Readout {
        val player = ArrayList<String>()
        val tech = ArrayList<String>()
        player.add(canCheckLine(copy, s))
        player.add(checkLine(copy, s))
        player.add(stagedLine(copy, s))
        UpdateUiText.skippedVersionNote(copy, s.skippedVersionCode)?.let { player.add(it) }
        // U5 P2-12：下载真的在跑时补一行「正在下载」，玩家不必退出设置页去翻通知栏。
        // 不在跑（或缺读数）就一个字都不加 —— 既有三行现状的形状不许被它挤歪。
        s.progress?.takeIf { it.running }?.let { player.add(downloadingLine(copy, it)) }
        tech.add(addressLine(copy, s))
        tech.add(copy.fill(copy.techVersionsLineFormat, s.currentVersionCode, s.installedDataVersion))
        // U11 P2-13：数据通道选件读数进**展开层**（玩家面三行现状一个字都不动）：
        // 「档位 / 盘可信 / 发布给的包」三格一起讲，差量为什么没接上才看得见。没检查到选件那一步就不加这一行。
        s.dataArtifact?.let { tech.add(it.line(copy)) }
        // U13 upd-baccount：挡板生效说明也只进展开层（玩家面三行现状一个字不动）。
        // 有账且挡板生效才加行；没生效（[Snapshot.rejectedFallback] 为 null）一个字都不加。
        s.rejectedFallback?.let { tech.add(rejectedFallbackLine(copy, it)) }
        tech.addAll(timeLines(copy, s))
        if (s.noteCount > s.notes.size) tech.add(copy.fill(copy.techDroppedNotesLineFormat, s.noteCount - s.notes.size))
        tech.addAll(s.notes)
        return Readout(player, tech)
    }

    /** 第一行：这条通道现在能不能查、查了会不会自己下载。 */
    private fun canCheckLine(copy: UpdateCopy, s: Snapshot): String {
        if (s.mirrorCount <= 0) return UpdateUiText.noBuiltInMirrorReport(copy)
        val tail = if (s.autoChannelWanted) copy.canCheckAutoTail else copy.canCheckManualTail
        return copy.fill(copy.canCheckFormat, s.mirrorCount, tail)
    }

    /** 第二行：上次查到了什么（U2-③ 的新状态就是为了让这一行讲得出失败形状）。 */
    private fun checkLine(copy: UpdateCopy, s: Snapshot): String {
        val seen = if (s.lastAttemptMillis > 0L) s.lastAttemptMillis else s.lastSuccessMillis
        val since = sinceText(copy, seen, s.nowMillis) ?: return copy.neverCheckedLine
        val head = copy.fill(copy.lastCheckHeadFormat, since)
        return when (s.outcome) {
            OUTCOME_OK ->
                if (s.noteCount <= 0) copy.fill(copy.checkOkLineFormat, head)
                else copy.fill(copy.checkOkNotesLineFormat, head, UpdateUiText.warningsNote(copy, s.noteCount)!!)
            OUTCOME_UNREACHABLE -> copy.fill(copy.checkUnreachableLineFormat, head, s.mirrorCount)
            OUTCOME_NO_MIRROR -> copy.fill(copy.checkNoMirrorLineFormat, head)
            OUTCOME_CRASHED -> copy.fill(copy.checkCrashedLineFormat, head)
            else -> copy.fill(copy.checkUnknownLineFormat, head)
        }
    }

    /**
     * 第三行：有没有东西在等着应用。
     *
     * 只能说「下次启动游戏时会自动装上」——数据包真的落盘是在下一次启动的装载之前
     * （[UpdateManager.applyStagedBundleIfPresent]），在这之前说「已更新/已生效」就是谎报。
     * 三种语言同一口径（词在 [UpdateCopy]，「不许谎报」这条判据在这儿）。
     */
    private fun stagedLine(copy: UpdateCopy, s: Snapshot): String =
        if (s.stagedDataVersion > 0L) copy.fill(copy.stagedYesLineFormat, s.stagedDataVersion)
        else copy.stagedNoLine

    /** 正在下载那一行：词在 [UpdateCopy.downloadingLineFormat]，正文复用 [UpdateProgressText.progressText]。 */
    private fun downloadingLine(copy: UpdateCopy, p: DownloadProgress): String =
        copy.fill(
            copy.downloadingLineFormat,
            UpdateProgressText.progressText(
                copy,
                p.kind ?: UpdateDownloadKind.Apk,
                p.doneBytes,
                p.totalBytes,
            ),
        )

    private fun addressLine(copy: UpdateCopy, s: Snapshot): String =
        if (s.mirrorCount <= 0) copy.techAddressNoneLine
        else copy.fill(copy.techAddressLineFormat, s.mirrorCount, s.preferredMirror)

    /**
     * 挡板生效说明行（U13）：「上次同版本数据包因校验不过被拒，这次改走 X」；回落不到任何档
     * （[RejectedBundleFallback.fallbackKind] = NONE）改说「暂不取数据包」。档位词与 [DataArtifactReadout.line]
     * 同一张词表，本函数不造词。
     */
    private fun rejectedFallbackLine(copy: UpdateCopy, f: RejectedBundleFallback): String =
        when (val kindLabel = when (f.fallbackKind) {
            DataArtifactKind.BLOCK_DELTA -> copy.dataKindBlockDelta
            DataArtifactKind.FILE_DELTA -> copy.dataKindFileDelta
            DataArtifactKind.FULL -> copy.dataKindFull
            DataArtifactKind.NONE -> null
        }) {
            null -> copy.fill(copy.techRejectedSkipLineFormat, f.rejectedVersion)
            else -> copy.fill(copy.techRejectedFallbackLineFormat, f.rejectedVersion, kindLabel)
        }

    private fun timeLines(copy: UpdateCopy, s: Snapshot): List<String> {
        val lines = ArrayList<String>()
        lines.add(
            if (s.lastSuccessMillis > 0L) copy.fill(copy.techLastSuccessLineFormat, s.lastSuccessMillis)
            else copy.techNeverSucceededLine
        )
        if (s.lastAttemptMillis > 0L && s.lastAttemptMillis != s.lastSuccessMillis) {
            lines.add(copy.fill(copy.techAttemptLineFormat, s.lastAttemptMillis, s.outcome))
        } else if (s.outcome.isNotEmpty()) {
            lines.add(copy.fill(copy.techOutcomeLineFormat, s.outcome))
        }
        return lines
    }
}
