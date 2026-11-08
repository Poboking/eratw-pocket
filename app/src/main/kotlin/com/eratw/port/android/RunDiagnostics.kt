package com.eratw.port.android

/**
 * 运行轨迹的死因判读与可导出诊断文本（「玩着玩着没了、什么报告都没有」的那一类）。
 *
 * ## 为什么要有这一层
 *
 * [RunWatch] 每回合边界采一行 `used / max / native`，被杀时盘上留下 `eratw-run/trail.txt`。
 * 可这份读数只有玩家自己看得见：玩家报「闪退」时，我们手上通常只有两种报告 —— 抛异常的
 * 那份（有栈）与脏退出的那份（只带 64 行尾巴）。剩下的一大类（lmkd / OEM 直接收进程、
 * native 侧崩）连报告都不存在，只能请玩家把轨迹交上来。
 *
 * 于是这里做两件事，纯 JVM、可单测：
 * 1. [classify]：把轨迹读成四种死法 —— Java 堆贴顶 / native 驻留高 / 系统内存不足 / 两者都不高；
 * 2. [reportText]：把轨迹 + 收尾标记 + 设备头拼成一份与错误报告同款的可分享文本
 *    （设置页「运行诊断」那张卡消费，见 `SettingsActivity`）。
 *
 * ## 判据与取舍
 *
 * - **Java 堆贴顶**：任一行 `used / max ≥ 85%`（512 MiB 机型约 435 MiB）。门槛取 85% 而不是
 *   100%：ART 的 OOM 是「分配这一下失败」，死前最后一行往往还没写满；日志里 free 只剩几百
 *   KB 的那几次，轨迹末行停在 90% 附近。门槛再高就会把最典型的死法漏掉。
 * - **native 高**：峰值 `native ≥ 150 MiB`。位图与画布在 Java 堆外，这项高而 Java 堆不高
 *   ⇒ 内存被图形缓冲吃掉，收 Java 堆没有用。
 * - **系统内存不足**：死前系统可用内存跌破**本机**低内存线（轨迹里的 `sys=` / `lmk=`，
 *   判据见 [isSystemLowMemory]）。这一桶 2026-10-31 才拆出来：拆之前它藏在下面那一桶里，
 *   于是「整机内存被别的东西吃光、lmkd 无差别收进程」与「我们自己的映射崩了」混在一起数。
 * - **两者都不高**：其余全算这一类（文件映射访问 SIGBUS、系统按总内存或后台管控收进程、
 *   或旧轨迹根本没有 `sys=` 列）。这一桶是**排除法，不是证明** —— 文本里如实写，不冒充结论。
 *
 * 阈值判错的后果只能装机才看见（会把玩家与修复方向一起带偏），所以判据与常量都锁进
 * `RunDiagnosticsTest`。
 */
internal data class TrailSample(
    val elapsedMs: Long,
    val usedMiB: Long,
    val maxMiB: Long,
    val nativeMiB: Long,
    val phase: String,
    /** `freed=` 那一刀收回的 MiB；null = 这一行不是内存压力事件。 */
    val trimFreedMiB: Long?,
    /** 这一行记下的**系统**可用内存（MiB）；null = 旧轨迹行（这一列是 2026-10-31 才加的）。 */
    val sysAvailMiB: Long? = null,
    /** 这一行记下的本机低内存线（`MemoryInfo.threshold`，MiB）；null = 没取到。 */
    val lmkThresholdMiB: Long? = null,
) {

    /** 已用 / 上限（百分比）；[maxMiB] 不可用时为 0（不参与峰值判定）。 */
    val usedPercent: Long get() = if (maxMiB > 0L) usedMiB * 100L / maxMiB else 0L
}

/** 一份轨迹的汇总 —— [RunDiagnostics.classify] 的唯一输入。 */
internal data class TrailSummary(
    val samples: List<TrailSample>,
    val peakUsedMiB: Long,
    val peakMaxMiB: Long,
    val peakUsedPercent: Long,
    val peakNativeMiB: Long,
    val last: TrailSample?,
    val trimEvents: Int,
    val trimFreedMiB: Long,
    /** 整份轨迹里系统可用内存的**最低**值（MiB）；null = 全是旧格式行，没有这一列。 */
    val minSysAvailMiB: Long? = null,
    /** 这份轨迹里最后一次记下的本机低内存线（MiB）；null = 没取到。 */
    val lmkThresholdMiB: Long? = null,
)

/** 无报告那一类死的可判读形态（[NO_DATA] = 盘上根本没有轨迹）。 */
internal enum class DeathMode { JAVA_HEAP, NATIVE, SYSTEM_LOW_MEM, NEITHER, NO_DATA }

internal object RunDiagnostics {

    /** Java 堆「贴顶」门槛（已用 / 上限，百分比）；理由见文件头。 */
    const val HEAP_CEILING_PERCENT = 85L

    /** native「偏高」门槛（MiB）；理由见文件头。 */
    const val NATIVE_HIGH_MIB = 150L

    /** [RunTrail.formatLine] 的逆；阶段名允许为空。 */
    private val TRAIL_LINE =
        Regex("""t=(\d+)ms used=(\d+)MiB max=(\d+)MiB native=(\d+)MiB\s*(.*)""")

    /** 阶段名里的 `freed=<n>MiB`（trim / 主动回收那一发才有）。 */
    private val TRIMMED_MIB = Regex("""freed=(\d+)MiB""")

    /** 阶段尾部的 `sys=<n>MiB`（系统可用内存，[RunTrail.formatLine] 追加）。 */
    private val SYS_AVAIL_MIB = Regex("""sys=(\d+)MiB""")

    /** 阶段尾部的 `lmk=<n>MiB`（本机低内存线，同上）。 */
    private val LMK_THRESHOLD_MIB = Regex("""lmk=(\d+)MiB""")

    /** 解析一行轨迹；不是轨迹行（旧格式、手改过的文件）返回 null，调用方一律跳过。 */
    fun parseSample(line: String): TrailSample? {
        val match = TRAIL_LINE.matchEntire(line.trim()) ?: return null
        val values = LongArray(4)
        for (i in 0 until 4) {
            values[i] = match.groupValues[i + 1].toLongOrNull() ?: return null
        }
        val phase = match.groupValues[5].trim()
        return TrailSample(
            elapsedMs = values[0],
            usedMiB = values[1],
            maxMiB = values[2],
            nativeMiB = values[3],
            phase = phase,
            trimFreedMiB = TRIMMED_MIB.find(phase)?.groupValues?.get(1)?.toLongOrNull(),
            sysAvailMiB = SYS_AVAIL_MIB.find(phase)?.groupValues?.get(1)?.toLongOrNull(),
            lmkThresholdMiB = LMK_THRESHOLD_MIB.find(phase)?.groupValues?.get(1)?.toLongOrNull(),
        )
    }

    /** 汇总轨迹（峰值按行取最大；百分比只统计 `max > 0` 的行）。 */
    fun summarize(lines: List<String>): TrailSummary {
        val samples = lines.mapNotNull(::parseSample)
        val trims = samples.filter { it.trimFreedMiB != null }
        return TrailSummary(
            samples = samples,
            peakUsedMiB = samples.maxOfOrNull { it.usedMiB } ?: 0L,
            peakMaxMiB = samples.maxOfOrNull { it.maxMiB } ?: 0L,
            peakUsedPercent = samples.filter { it.maxMiB > 0L }.maxOfOrNull { it.usedPercent } ?: 0L,
            peakNativeMiB = samples.maxOfOrNull { it.nativeMiB } ?: 0L,
            last = samples.lastOrNull(),
            trimEvents = trims.size,
            trimFreedMiB = trims.sumOf { it.trimFreedMiB ?: 0L },
            minSysAvailMiB = samples.mapNotNull { it.sysAvailMiB }.minOrNull(),
            lmkThresholdMiB = samples.mapNotNull { it.lmkThresholdMiB }.lastOrNull(),
        )
    }

    /**
     * 判读死法。优先级写死：**先看 Java 堆**（贴顶时它就是充分解释，native 读数只作旁证），
     * 再看 native，再看系统内存，最后才落到排除法那一桶。
     *
     * 系统内存排在 native 之后、排除法之前：前两桶都是「我们自己的堆不够」（有明确修法），
     * 而系统内存那一桶说的正是「不是我们的堆」—— 它必须**先于**排除法判出来，否则 11 份
     * 系统压力死又会掉回「映射或信号」那个什么也说明不了的名字里。
     */
    fun classify(summary: TrailSummary): DeathMode = when {
        summary.samples.isEmpty() -> DeathMode.NO_DATA
        summary.peakUsedPercent >= HEAP_CEILING_PERCENT -> DeathMode.JAVA_HEAP
        summary.peakNativeMiB >= NATIVE_HIGH_MIB -> DeathMode.NATIVE
        isSystemLowMemory(summary) -> DeathMode.SYSTEM_LOW_MEM
        else -> DeathMode.NEITHER
    }

    /**
     * 死的时候系统已经处在「低内存」状态？
     *
     * 判据用**设备自己给的那条线**（`ActivityManager.MemoryInfo.threshold`，轨迹里的 `lmk=`），
     * 不是我们拍的绝对 MiB：同一台机器的线由系统按总 RAM 与当前档位算，Android 对「低内存」
     * 的定义就是 `availMem < threshold`，这里照抄。
     *
     * 缺任何一半（旧轨迹没有 `sys=` / 没取到 `lmk=`）一律返回 false —— 宁可不判，
     * 也不拿一个拍出来的门槛把「映射访问」的锅推给系统。
     */
    fun isSystemLowMemory(summary: TrailSummary): Boolean {
        val avail = summary.minSysAvailMiB ?: return false
        val line = summary.lmkThresholdMiB ?: return false
        // 判式只有一份（[RunTrail.isSystemLow]）：轨迹里判死因与回合边界上做回收若各写一套，
        // 两处迟早会在 `<` / `<=` 这种边界上分家，而那种分家只能靠装机才看得见。
        return RunTrail.isSystemLow(avail, line)
    }

    fun modeTitle(mode: DeathMode): String = when (mode) {
        DeathMode.JAVA_HEAP -> "Java 堆贴顶"
        DeathMode.NATIVE -> "native 驻留偏高"
        DeathMode.SYSTEM_LOW_MEM -> "系统内存不足（被 LMK 收掉）"
        DeathMode.NEITHER -> "两者都不高（映射或信号）"
        DeathMode.NO_DATA -> "没有轨迹"
    }

    fun modeExplanation(mode: DeathMode): String = when (mode) {
        DeathMode.JAVA_HEAP ->
            "峰值已到堆上限的 $HEAP_CEILING_PERCENT% 以上 ⇒ 是 Java 堆不够（图形常驻 + 变量池 + 脚本对象），先降常驻占用。"
        DeathMode.NATIVE ->
            "Java 堆没有贴顶，但 native 峰值达到 $NATIVE_HIGH_MIB MiB 以上 ⇒ 内存花在位图 / 画布这类堆外驻留，或系统按总内存收掉了进程。"
        DeathMode.SYSTEM_LOW_MEM ->
            "Java 堆与 native 都不高，而死前系统可用内存跌破了本机低内存线 ⇒ 是 lmkd / OEM 按**整机**内存收掉了进程，" +
                "不是我们的堆不够。修法在「别的应用占了多少」，或在被收掉前把本进程的可重建缓存再收一轮。"
        DeathMode.NEITHER ->
            "Java 堆与 native 都不高，系统内存也没到低内存线 ⇒ 这一桶是排除法：更像文件映射访问（SIGBUS）或系统直接杀进程，不是单纯的内存不够。"
        DeathMode.NO_DATA ->
            "一条轨迹都没有 ⇒ 死在第一次回合边界之前（装载期 / 开局演出），或运行标记没落盘；这份轨迹不能判读。"
    }

    /** 一行读数（卡片与报告共用）。 */
    fun headline(summary: TrailSummary): String {
        if (summary.samples.isEmpty()) return "轨迹 0 行"
        return "峰值 Java 已用 ${summary.peakUsedMiB}/${summary.peakMaxMiB} MiB（${summary.peakUsedPercent}%）" +
            " · 峰值 native ${summary.peakNativeMiB} MiB" +
            sysHeadline(summary) +
            " · 轨迹 ${summary.samples.size} 行" +
            " · trim ${summary.trimEvents} 次（共收回 ${summary.trimFreedMiB} MiB）"
    }

    /** 读数里的系统内存一截：最低可用值与它对照的那条线（没有这一列就整段不出现）。 */
    private fun sysHeadline(summary: TrailSummary): String {
        val avail = summary.minSysAvailMiB ?: return ""
        val line = summary.lmkThresholdMiB
        return if (line != null) {
            " · 系统最低可用 ${avail} MiB（本机低内存线 ${line} MiB）"
        } else {
            " · 系统最低可用 ${avail} MiB（未取到低内存线）"
        }
    }

    /** 系统发过内存压力却一点没收回：说明这一刀对这类死法无效（或压力来自 Java 堆之外）。 */
    fun trimNote(summary: TrailSummary): String? =
        if (summary.trimEvents > 0 && summary.trimFreedMiB == 0L) {
            "注意：系统发过 ${summary.trimEvents} 次内存压力，但回收没有退回任何 MiB" +
                "（要么已无可回收的延迟图，要么压力来自 Java 堆之外）。"
        } else {
            null
        }

    /**
     * 可分享的诊断全文（中文，与错误报告同一口气：只带内存水位与阶段名，不带存档/变量）。
     *
     * @param state 收尾标记原文（`files/eratw-run/state.txt`）；null = 读不到
     * @param trail 轨迹原文（`files/eratw-run/trail.txt` 的全部行，旧在前）
     * @param headerLines 版本 / 设备 / 系统三行 + 机型档硬件读数 11 行，共 14 行（宿主给，
     *   与错误报告 `[版本与设备]` 同一套口径，见 `ErrorReportCollector.headerLines`）
     */
    fun reportText(
        state: String?,
        trail: List<String>,
        headerLines: List<String>,
        buildStamp: String,
        generatedAtText: String,
    ): String {
        val summary = summarize(trail)
        val mode = classify(summary)
        val lines = ArrayList<String>(trail.size + 24)
        lines += "================================================================"
        lines += "eraTW 安卓移植版 运行诊断（运行轨迹）"
        lines += "================================================================"
        lines += "生成时间：$generatedAtText"
        lines += "构建标识：$buildStamp"
        lines += "把本文件完整发给开发者（QQ 群 / Issue 附件）即可。"
        lines += "口径：只含内存水位与阶段名，不含存档数据与变量取值。"
        lines += "----------------------------------------------------------------"
        lines += "[版本与设备]"
        lines += headerLines
        lines += "----------------------------------------------------------------"
        lines += "[判读]"
        lines += "死因判读：${modeTitle(mode)}"
        lines += "读数：${headline(summary)}"
        lines += modeExplanation(mode)
        trimNote(summary)?.let { lines += it }
        lines += "怎么读：used/max 是 Java 堆（已用 / 上限）；native 是堆外驻留（位图、画布）；" +
            "trim= 是系统发来的内存压力，freed= 是那一刀实际收回的 MiB；" +
            "sys= 是那一发的**系统**可用内存，lmk= 是本机低内存线（sys 跌破 lmk = 系统已在低内存状态）。"
        lines += "----------------------------------------------------------------"
        lines += "[收尾标记]"
        lines += "状态文件原文：${state?.trim()?.replace('\n', ' ') ?: "（读不到）"}"
        lines += "----------------------------------------------------------------"
        lines += "[运行轨迹（旧在前，最多 ${RunTrail.MAX_LINES} 行；前三列口径同装载埋点，sys/lmk 是系统读数）]"
        if (trail.isEmpty()) {
            lines += "（一条都没有 ⇒ 上一轮死在第一次回合边界之前，即装载期或开局演出期）"
        } else {
            lines += trail
        }
        lines += "================================================================"
        lines += "诊断结束"
        return lines.joinToString("\n")
    }
}
