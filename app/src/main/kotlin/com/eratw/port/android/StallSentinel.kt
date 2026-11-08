package com.eratw.port.android

import android.os.Looper
import android.os.SystemClock

/**
 * 主线程冻结哨兵（EP1 波 · L4 stall-stack）——「主线程冻住的那几秒，它到底在哪一行」。
 *
 * ## 要补的读数缺口
 *
 * 端点 2026-11-02 那批「上次异常退出（无异常对象，见补充现场）」的报告（收件箱当前口径 8 份，
 * 同一形状；主人直传那份是 OPPO PERM00 / 0.2.34 / Java 堆 511–512 MiB /
 * `trim=runningCritical freed=0MiB`）里，关于主线程只有**时刻**没有**位置**：
 * [RunWatch] 的轨迹能给出「`t=…ms` 之后没有新行」「`hb=ui:30000` = UI 帧循环 30 秒没动」，
 * [HeartbeatJudgment] 能给出「谁先停」，但两者都答不出**那 30 秒主线程停在哪一行**。
 * 而「死在屏前」的报告是**下一次冷启动**落的（`RunWatch.beginProcess`，RunWatch.kt:123），
 * 那一刻活着的栈早没了 ⇒ 只能在冻结**正在发生**的时候由**另一条线程**把栈抓下来。
 *
 * ## 本文件给什么
 *
 * 1. [StallJudgment]：**纯**判式 —— 动手下限、冷却、谁该被钉、帧文本的形状与体积上限。
 *    不碰时钟、不碰 Android API、不起线程 ⇒ `:app` 的 JVM 单测直接钉死（同
 *    [HeartbeatJudgment] / [RunTrail] 的抽法与理由）。
 * 2. [StallScene]：一次冻结的现场（不可值对象），抓栈那刻建好，之后只读。
 * 3. [StallSentinel]：**一条独立线程**（既不是 UI 也不是 VM）按 [StallJudgment.SAMPLE_INTERVAL_MS]
 *    的节拍读 [Heartbeat] 的 UI 帧读数，判定主线程超期时抓**主线程 + `eratw-vm`** 的栈，
 *    钉一行轨迹并把现场留在实例里供报告侧读（[StallSentinel.lastScene]）。
 *
 * ## 为什么不另造一把尺
 *
 * 「多久没动」全部走 [Heartbeat] 的三路读数与 [HeartbeatJudgment] 的阈值口径：
 * - 判超期用 [HeartbeatJudgment.isStale]（未采到 = null ⇒ 恒不超期，绝不把「没数据」当冻结）；
 * - 阈值下限取 [HeartbeatJudgment.thresholdOf] 的 UI 那一档（1 000 ms），再抬到本哨兵自己的
 *   [StallJudgment.CAPTURE_MIN_UI_AGE_MS]（1 500 ms）：**只更严、不更松**——抓栈是给「已经确定
 *   冻住」那一段留位置，1 000–1 500 ms 之间的那次抖动只够钉一行心跳轨迹（`freeze` 行由
 *   `MainActivity.markHeartbeats` 负责），不值得为它跑一次线程栈转储；
 * - 冷却直接**复用** [HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS]（30 s，RunWatch 里
 *   `RELEASE_COOLDOWN_MS` 同值那一档），不在本文件里再写一个 30 000。
 *
 * ## 硬约束（逐条怎么落地）
 *
 * - **不 suspend、不打断、不抛**：抓栈只用 `Thread.getStackTrace()`（JVM/ART 自己的安全点转储，
 *   不改目标线程状态）。本线程唯一的 `interrupt()` 对象是**它自己**（[StallSentinel.stop] 唤醒
 *   睡眠），线程体整段被 `runCatching` 包住，任何一次判读/抓栈/落盘出事都只是「这一拍什么都没做」。
 * - **不读 `.erbc` 映射页、不碰 core**：本文件没有任何 `core` 侧 import；抓栈路径只读
 *   [Heartbeat] 的三个 volatile 与两条线程的栈。函数名解析那一路（`MappedFunctionTable` 就地解码会
 *   踩坏 VM 线程正在用的复用缓冲，见 `ErrorReportCollector.kt:362-382`）**不**在这里重演。
 * - **不拿锁**：状态是一个 `@Volatile` 的现场引用 + 两个 `@Volatile` 的标志，没有 monitor；
 *   于是「UI 线程正卡在锁上」时哨兵不会跟着卡。
 * - **落盘只走既有写侧闸**：钉发只调 `RunWatch.forceSample`，磁盘写在
 *   `TrailWriteGate`（RunWatch.kt:360-383）的写侧 monitor 里串行、按环形表追加序号跳旧快照，
 *   所以本哨兵作为**第三个写者**不会把取证文件的先后讲乱（那条纪律正是 `fix-trailtear` 的产物）。
 * - **体积**：一次冻结 ≤ [StallJudgment.MAX_FRAMES_TOTAL]（24）帧文本，轨迹只添
 *   **一行**（[StallJudgment.trailPhase]，≤ [StallJudgment.MAX_TRAIL_PHASE_CHARS] 字符）⇒
 *   滚动轨迹 64 行、单行约 60 B 的既有基线（RunWatch.kt:399）之上多出不到 0.3 KB，
 *   远低于 4 KB 的增量预算，也不会把临死前那几行挤掉。
 * - **默认关**：没有 [StallSentinel.start] 就没有线程；起停随 Activity 生命周期（装配点见
 *   `MainActivity` 的 onResume/onPause/onDestroy），且只有泵**自认为还在转**时才动手
 *   （[StallSentinel.setUiLoopExpected]，见该方法 KDoc）。
 *
 * ## 未取证（本机 `adb devices` 为空）
 *
 * `TODO(verify)`：①真机上 ART 对**卡在 native 解码**的主线程取 `getStackTrace()` 实际给到几帧
 * （JVM 侧只会看到 `native` 那一层，Java 帧是否还在要看真机）；②500 ms 节拍 + 1 500 ms 下限
 * 能否在「ANR 对话框出现之前」抓到有效栈；③一次抓栈给主线程加多少停顿（预期微秒级，未实测）。
 * 这三条都不许拿单测读数冒充装机读数。
 */

/**
 * 一次冻结的现场（抓栈那一刻建好，之后**只读**）。
 *
 * 帧序与 [ErrorReportCollector] 的 Java 栈一致：**最内帧在前**（`Thread.getStackTrace` 的原序）。
 * 体积由 [StallJudgment] 的常量在构造时就裁好，本类不再做二次裁剪。
 *
 * @property atMs 抓取时刻（`SystemClock.elapsedRealtime` 口径，与心跳同一把单调钟）
 * @property uiAgeMs 抓取那一刻主线程距末次 UI 帧多少毫秒（判式成立，故恒 >= 0）
 * @property vmAgeMs 脚本线程距末次打点多少毫秒；null = 这一路未采到（不编读数）
 * @property verdictKey [HeartbeatJudgment.Verdict.key]：与心跳那一节用同一个词，端点侧可并读
 * @property uiThreadName / vmThreadName 抓到的线程名；VM 线程没起来时后者为 null
 * @property uiFrames / vmFrames 已裁剪的帧文本；空表 = 没取到栈（[StallJudgment.NOT_CAPTURED]）
 */
data class StallScene(
    val atMs: Long,
    val uiAgeMs: Long,
    val vmAgeMs: Long?,
    val verdictKey: String,
    val verdictText: String,
    val uiThreadName: String?,
    val vmThreadName: String?,
    val uiFrames: List<String>,
    val vmFrames: List<String>,
) {

    /** 现场正文（报告用）：节名 + 两行读数 + 两条栈；行数上限见 [StallJudgment.SCENE_MAX_LINES]。 */
    fun lines(): List<String> = StallJudgment.sceneLines(this)

    /**
     * 这次一共钉出几条**帧文本**（不含「余下省略」那一行标记）：上限 [StallJudgment.MAX_FRAMES_TOTAL]。
     *
     * 为什么按帧数而不是按行数计预算：契约给的是「一次冻结钉的文本 <= 24 **帧**」，
     * 省略标记是说明文字、不是栈帧；把它算进额度会让实际帧数悄悄少掉两帧。
     */
    fun frameCount(): Int = StallJudgment.realFrames(uiFrames) + StallJudgment.realFrames(vmFrames)
}

/**
 * 哨兵的纯判式：什么时候动手、抓谁、钉出来的文本长什么样。
 *
 * 抽成 object 的唯一理由是**可测**：[StallSentinel] 绑死 `SystemClock` / `Looper` / 真线程，
 * `:app` 的 JVM 单测进不去（该模块没有 `isReturnDefaultValues`，桩方法直接抛），
 * 而这里每一条都是纯算术 + 纯格式化，同 [RunTrail] / [HeartbeatJudgment] 的先例。
 */
object StallJudgment {

    /**
     * 哨兵线程的节拍（毫秒）。
     *
     * 取 500 ms 的理由：它必须显著小于 [HeartbeatJudgment.DEFAULT_THRESHOLDS] 的 UI 阈值
     * （1 000 ms，Heartbeat.kt:195）才能在该判超期后**尽快**看见；而取到 100 ms 以下就是
     * 让一条线程以 10 Hz 做永远为假的判断，常态路径多花钱（本波共同口径「默认关、命中才动」）。
     * 500 ms 的代价是「判定超期到抓栈之间最多晚半秒」——栈是几十秒级冻结的位置读数，值。
     */
    const val SAMPLE_INTERVAL_MS = 500L

    /**
     * 动手（抓栈）的 UI 帧距今下限（毫秒）。
     *
     * 与心跳的 1 000 ms 是**同一把尺的更严一档**（见文件头「为什么不另造一把尺」）：
     * 1 000 ms 那一档由心跳钉 `freeze` 行（零成本，只读三个 volatile）；抓栈是一次线程栈转储，
     * 只在「玩家已经会把这一秒多叫作卡死」的位置才做。不取 5 000 ms（ANR 下沿）：
     * 收件箱那批报告要的正是「冻住的**那几秒**在哪一行」，等 ANR 阈值就只剩一条死链。
     */
    const val CAPTURE_MIN_UI_AGE_MS = 1_500L

    /**
     * 单条线程最多列几帧。
     *
     * 主线程与 `eratw-vm` 各 12 帧 = [MAX_FRAMES_TOTAL]：栈顶 12 帧够穿过 2–3 层壳层
     * （`CanvasConsoleView.onDraw` → `SpriteBitmapSource.frame` → `BitmapFactory.decodeFile`
     * 这类链是 4–6 帧），再深的帧对一个已经冻住的线程没有分辨力。
     */
    const val MAX_FRAMES_PER_THREAD = 12

    /** 一次冻结钉出的帧文本总上限（条）：两条线程各 [MAX_FRAMES_PER_THREAD]。 */
    const val MAX_FRAMES_TOTAL = 24

    /** 单帧文本的字符上限：类名.方法名:行号 一般 30–60 字符，超了就是被截的对象名。 */
    const val MAX_FRAME_TEXT_CHARS = 96

    /** 钉进轨迹那一行的字符上限（一行约 220 B ⇒ 远小于 4 KB 的增量预算）。 */
    const val MAX_TRAIL_PHASE_CHARS = 220

    /** [StallScene.lines] 的行数上限：节名 1 + 读数 2 + 栏名 2 + 帧 [MAX_FRAMES_TOTAL] + 省略标记 2。 */
    const val SCENE_MAX_LINES = MAX_FRAMES_TOTAL + 7

    /** 轨迹行与端点分桶用的阶段名（与 `turn` / `longturn` / `trim` / `freeze` 并列）。 */
    const val TRAIL_PHASE = "stallstack"

    /** 报告小节名：这一节是**哨兵抓的栈**，与 `[异常栈]`（抛点）和 `[ERB 调用栈与 VM 现场]` 都不同源。 */
    const val SCENE_HEADER = "[主线程冻结现场（哨兵抓栈，最内帧在前）]"

    /** 没取到栈时写死的那句：与 [HeartbeatJudgment.NOT_MEASURED] 同一纪律，不许留空、不许编。 */
    const val NOT_CAPTURED = "未取到"

    /**
     * 脚本线程名。
     *
     * 唯一来源是 `GameHost` 建线程时给的名字（GameHost.kt:3274 `VM_THREAD_NAME = "eratw-vm"`，
     * 两处建线程点 :611/:2856 都传它）。本文件**不**引用 `GameHost` 的实例口取线程，
     * 理由见 [findLiveThreadByName]。
     */
    const val VM_THREAD_NAME = "eratw-vm"

    /** 省略尾帧那一行的固定前缀（[StallScene.frameCount] 与轨迹行都靠它认出这是标记行）。 */
    private const val OMIT_PREFIX = "…"

    /**
     * 动手下限：本哨兵的 UI 阈值 = 心跳那把尺的 UI 档与 [CAPTURE_MIN_UI_AGE_MS] 的**较大者**。
     *
     * 写成 `maxOf` 而不是常数 1 500 是有意的：将来心跳把 UI 档调到 2 s（真机抖动取证之后），
     * 哨兵自动跟着收紧，不会出现「心跳判超期而哨兵不抓」之外的第二种方向。
     */
    fun captureThresholdMs(
        thresholds: HeartbeatJudgment.Thresholds = HeartbeatJudgment.DEFAULT_THRESHOLDS,
    ): Long = maxOf(
        CAPTURE_MIN_UI_AGE_MS,
        HeartbeatJudgment.thresholdOf(thresholds, HeartbeatChannel.UI_FRAME),
    )

    /**
     * 要不要为这一刻抓栈：UI 帧距今**已超期**（按 [captureThresholdMs]）。
     *
     * `uiAgeMs == null`（本路未打点）一律 false —— 未接线不是冻结，把它当冻结就是假现场。
     */
    fun shouldCapture(
        uiAgeMs: Long?,
        thresholds: HeartbeatJudgment.Thresholds = HeartbeatJudgment.DEFAULT_THRESHOLDS,
    ): Boolean = HeartbeatJudgment.isStale(uiAgeMs, captureThresholdMs(thresholds))

    /**
     * 「主线程还在推进」的**第二道证据**：UI 帧计数比上一拍大了就不动手。
     *
     * 任务书要的是「复用 [Heartbeat] 的 UI 帧计数，别再造一把尺」——距今（[shouldCapture]）与
     * 计数正是同一份读数的两半：距今答「多久没动」，计数答「这期间动过没有」。要防的形态是
     * 墙钟读数跟不上真在转的循环（深睡唤醒 / `elapsedRealtime` 大步前进后第一拍），
     * 那种时候抓到的栈是「刚跑完一帧」的正常位置，钉出去就是假现场。
     *
     * 方向上**只收紧不放松**：这条判式只能让本该抓的那次**少**抓一次（下一拍计数不再前进时
     * 照样抓），绝不会让本该判健康的变成冻结。
     *
     * @param lastSeenCount 上一拍看到的 UI 帧计数；负数 = 还没有基线（首拍不判前进，直接看距今）
     * @param count 这一拍的 UI 帧计数
     */
    fun hasUiProgress(lastSeenCount: Long, count: Long): Boolean =
        lastSeenCount >= 0L && count > lastSeenCount

    /**
     * 冷却到了没有（同一次冻结只钉一发）。
     *
     * `lastPinAtMs < 0` = 本进程还没钉过 ⇒ 首钉必走。间隔直接复用
     * [HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS]（30 s）：与心跳的 `freeze` 行同一档，
     * 64 行的滚动轨迹不会被同一次冻结灌满（钉的是不同内容，冷却必须同步才不会各钉各的）。
     */
    fun shouldPin(nowMs: Long, lastPinAtMs: Long): Boolean =
        lastPinAtMs < 0L || nowMs - lastPinAtMs >= HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS

    /**
     * 一条线程的栈 → 帧文本（最内帧在前，裁到 [MAX_FRAMES_PER_THREAD] 再加一行省略标记）。
     *
     * 帧文本形状与 `ErrorReportCollector.kt:412` 完全一致（`简单类名.方法名:行号`）：
     * 同一份报告里两种 Java 栈读起来必须是一个形状，端点侧的解析也只写一套。
     * 空栈返回空表（调用方按 [NOT_CAPTURED] 处理），越界的 `maxFrames` 收敛到
     * [MAX_FRAMES_PER_THREAD]，不放开成「调用方想钉多少钉多少」。
     */
    fun framesOf(trace: Array<StackTraceElement>, maxFrames: Int = MAX_FRAMES_PER_THREAD): List<String> {
        if (trace.isEmpty() || maxFrames <= 0) return emptyList()
        val limit = minOf(maxFrames, MAX_FRAMES_PER_THREAD)
        val n = minOf(limit, trace.size)
        val out = ArrayList<String>(n + 1)
        for (i in 0 until n) out += frameLine(trace[i])
        if (trace.size > n) out += "$OMIT_PREFIX（共 ${trace.size} 帧，余下省略）"
        return out
    }

    /** 一帧文本：`简单类名.方法名:行号`，裁到 [MAX_FRAME_TEXT_CHARS]。 */
    internal fun frameLine(element: StackTraceElement): String =
        clip(
            "${element.className.substringAfterLast('.')}.${element.methodName}:${element.lineNumber}",
            MAX_FRAME_TEXT_CHARS,
        )

    /**
     * 组装现场：把两条线程的帧表裁进 [MAX_FRAMES_TOTAL] 并取好判读文本。
     *
     * 「谁该被钉」在这里定形（纯函数，可单测）：
     * - **主线程恒钉** —— 它就是当事人，[shouldCapture] 成立的唯一证据就是它不前进；
     * - **脚本线程一并钉** —— 与主线程并排才分得出「UI 循环先停（脚本还在出页）」与
     *   「整进程被冻（两条形同静止）」这两种完全不同的病（判读表见
     *   [HeartbeatJudgment.verdict]）；取不到就写 [NOT_CAPTURED]，不拿主线程的栈冒充；
     * - 第三条及以后的线程**不钉**：本波要答的是这两条线程的位置，全进程转储既不必要也会
     *   把 4 KB 预算和 24 帧上限一起吃光。
     */
    fun buildScene(
        nowMs: Long,
        snapshot: HeartbeatSnapshot?,
        uiAgeMs: Long,
        uiThreadName: String?,
        uiTrace: Array<StackTraceElement>?,
        vmThreadName: String?,
        vmTrace: Array<StackTraceElement>?,
        thresholds: HeartbeatJudgment.Thresholds = HeartbeatJudgment.DEFAULT_THRESHOLDS,
    ): StallScene {
        val verdict = snapshot?.let { HeartbeatJudgment.verdict(it, nowMs, thresholds) }
            ?: HeartbeatJudgment.Verdict.NO_DATA
        // 两条表各自先按 12 帧裁，再把**合计帧数**压进 [MAX_FRAMES_TOTAL]（省略标记不占额度：
        // 它是说明文字不是栈帧，算进去只会让实际帧悄悄少两帧，见 [StallScene.frameCount]）。
        val uiFrames = framesOf(uiTrace ?: emptyArray())
        val room = MAX_FRAMES_TOTAL - realFrames(uiFrames)
        val vmFrames = capTotal(framesOf(vmTrace ?: emptyArray()), room)
        return StallScene(
            atMs = nowMs,
            uiAgeMs = uiAgeMs,
            vmAgeMs = snapshot?.ageMs(HeartbeatChannel.VM, nowMs),
            verdictKey = verdict.key,
            verdictText = verdict.text,
            uiThreadName = uiThreadName,
            vmThreadName = vmThreadName,
            uiFrames = uiFrames,
            vmFrames = vmFrames,
        )
    }

    /**
     * 现场正文。
     *
     * 顺序固定：节名 → 两行距今读数 → 主线程栈 → 脚本线程栈。**行尾不给心跳段**——
     * 同一份报告里 `[线程心跳]` 那一节已经有了（`HeartbeatJudgment.freezeLines`），
     * 这里只给位置；两处都写会把 `EXTRA_MAX_LINES`（core/diag/ErrorReport.kt:191 = 40 行）
     * 的窗口吃掉，而那一节是保**尾**的，所以接线时请把本小节放在心跳节**之后**（见交付报告）。
     */
    fun sceneLines(scene: StallScene): List<String> {
        val out = ArrayList<String>(SCENE_MAX_LINES)
        out += SCENE_HEADER
        out += "主线程距今=${scene.uiAgeMs}ms（阈值${captureThresholdMs()}ms）判读=${scene.verdictKey}"
        out += "脚本线程距今=${scene.vmAgeMs?.let { "${it}ms" } ?: HeartbeatJudgment.NOT_MEASURED}"
        out += "主线程 ${scene.uiThreadName ?: "?"}："
        out += if (scene.uiFrames.isEmpty()) listOf(NOT_CAPTURED) else scene.uiFrames
        out += "脚本 ${scene.vmThreadName ?: "?"}："
        out += if (scene.vmFrames.isEmpty()) listOf(NOT_CAPTURED) else scene.vmFrames
        return out
    }

    /**
     * 钉进 [RunWatch] 轨迹的那一行阶段文本（**只一行**）。
     *
     * 形状：`stallstack ui-stalled uiFrozen=3200ms vm=120ms frames=24 ui@Canvas.draw:1 vm@Vm.run:88`
     * 只带**两条栈的栈顶帧**：完整现场在内存里（[StallSentinel.lastScene]），
     * 而轨迹行还要在下一次冷启动被 [RunTrail.dirtyReportLines] 原样带上盘 ⇒
     * 24 帧灌进一行会把整份 64 行轨迹的重量压到报告里，违背「增量 <= 4 KB」。
     * 行尾的 `hb=` 段由 `RunWatch.forceSample` 自己拼（RunWatch.kt:228-232），这里不重复。
     */
    fun trailPhase(scene: StallScene): String {
        val vm = scene.vmAgeMs?.let { "${it}ms" } ?: HeartbeatJudgment.NOT_MEASURED
        val line = StringBuilder(160)
            .append(TRAIL_PHASE).append(' ').append(scene.verdictKey)
            .append(" uiFrozen=").append(scene.uiAgeMs).append("ms")
            .append(" vm=").append(vm)
            .append(" frames=").append(scene.frameCount())
            .append(" ui@").append(topFrame(scene.uiFrames))
        if (scene.vmThreadName != null) line.append(" vm@").append(topFrame(scene.vmFrames))
        return clip(line.toString(), MAX_TRAIL_PHASE_CHARS)
    }

    /** 栈顶帧（跳过省略标记）；一帧都没有时给 [NOT_CAPTURED]，不写空串。 */
    private fun topFrame(frames: List<String>): String =
        frames.firstOrNull { !it.startsWith(OMIT_PREFIX) } ?: NOT_CAPTURED

    /** 省略标记以外的**真帧**条数（预算与读数都按它计）。 */
    internal fun realFrames(lines: List<String>): Int = lines.count { !it.startsWith(OMIT_PREFIX) }

    /**
     * 把一条线程的帧表裁到 `maxReal` **条真帧**（标记行原样留在末尾，它不占额度）。
     *
     * 唯一会动到 `framesOf` 结果的路径是「两条线程合计超 [MAX_FRAMES_TOTAL]」；
     * 单条本来就被 [MAX_FRAMES_PER_THREAD] 夹住，所以生产上这里最多削掉 VM 的尾帧。
     */
    private fun capTotal(lines: List<String>, maxReal: Int): List<String> {
        val marker = lines.lastOrNull()?.takeIf { it.startsWith(OMIT_PREFIX) }
        val real = ArrayList<String>(lines.size)
        for (line in lines) if (!line.startsWith(OMIT_PREFIX)) real += line
        if (real.size <= maxReal) return lines
        val kept = real.take(maxReal.coerceAtLeast(0))
        return if (marker != null) kept + marker else kept
    }

    /** 字符裁剪：超长就截断并留一个字点，避免把整段类名甩进轨迹。 */
    internal fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.substring(0, maxChars - 1) + "…"
}

/**
 * 哨兵本体：**一条独立线程**（不是 UI、不是 VM）+ 一份内存里的最近现场。
 *
 * 实例挂在 `MainActivity` 上（AGENTS 铁律 4：禁静态可变全局），起停随生命周期，
 * `onDestroy` 必达。时钟、线程来源、钉发口全部是注入的 lambda ⇒ 判式与线程面都能在
 * `:app` 的 JVM 单测里用假时钟驱动（同 [HeartbeatJudgment] 的纪律）。
 *
 * 线程纪律（本类的全部可变状态是三个 `@Volatile` 字段，没有任何 monitor）：
 * - [setUiLoopExpected] 由 UI 线程每帧写一次（volatile 写，成本与心跳打点同级）；
 * - [tick] 只在哨兵线程上跑（单写者），它写的只有 [lastScene] 与 [lastPinAtMs] 两个 volatile；
 * - [start] / [stop] 由 UI 线程调，重复调用幂等。
 *
 * @param heartbeat 三路心跳（**只读**）：判「多久没动」的那把尺就是它，见文件头
 * @param clockMs 单调时刻（生产传 `SystemClock.elapsedRealtime`，单测传假时钟）
 * @param mainThread 主线程来源（生产传 `Looper.getMainLooper().thread`）
 * @param vmThread 脚本线程来源（生产按名字找，见 [findLiveThreadByName]）
 * @param pin 钉发口（生产 = `RunWatch.forceSample`，写侧走 `TrailWriteGate`）
 * @param sampleIntervalMs 节拍（缺省 [StallJudgment.SAMPLE_INTERVAL_MS]；单测可缩短）
 * @param thresholds 心跳阈值（缺省 [HeartbeatJudgment.DEFAULT_THRESHOLDS]）
 */
class StallSentinel(
    private val heartbeat: Heartbeat,
    private val clockMs: () -> Long,
    private val mainThread: () -> Thread?,
    private val vmThread: () -> Thread?,
    private val pin: (phase: String, scene: StallScene) -> Unit,
    private val sampleIntervalMs: Long = StallJudgment.SAMPLE_INTERVAL_MS,
    private val thresholds: HeartbeatJudgment.Thresholds = HeartbeatJudgment.DEFAULT_THRESHOLDS,
) {

    /** 线程是否应该在跑（[stop] 置 false 后循环立刻收口）。 */
    @Volatile
    private var running = false

    /**
     * 「UI 帧循环此刻**应该**在转吗」。
     *
     * 为什么必须有这道闸：[Heartbeat] 的 UI 读数是泵（`MainActivity.pump`）打的，而泵在三种
     * 情况下**本该**不转 —— 切后台（`onPause` 摘回调）、装载/解包期间（`host` 还是 null，
     * 泵自己不再续排）、脚本线程已退出（`isRunning()` 为假，见 MainActivity.kt:428）。
     * 这三种都不是冻结，抓栈会钉出一堆「主线程在 `nativePollOnce` 里等消息」的假现场，
     * 把 64 行轨迹灌满噪声。缺省 false ⇒ 没装好之前哨兵什么都不做（默认关）。
     */
    @Volatile
    private var uiLoopExpected = false

    /** 本哨兵自己那条线程；只在 [start] / [stop] 里赋值（UI 线程）。 */
    @Volatile
    private var worker: Thread? = null

    /** 上一次钉发的时刻（[clockMs] 口径）；-1 = 还没钉过（首钉必走）。只在哨兵线程写。 */
    @Volatile
    private var lastPinAtMs = -1L

    /** 最近一次冻结现场；null = 本进程还没抓到过。 */
    @Volatile
    private var lastScene: StallScene? = null

    /**
     * 上一拍看到的 UI 帧计数；-1 = 还没有基线。
     *
     * 只在哨兵线程（单写者）读写，测试里同样单线程直调 [tick] ⇒ 不需要 volatile；
     * 判式见 [StallJudgment.hasUiProgress]。
     */
    private var lastUiFrameCount = -1L

    /** 泵每帧写一次：`pumping && host 还活着` 时 true（见 [uiLoopExpected]）。 */
    fun setUiLoopExpected(expected: Boolean) {
        uiLoopExpected = expected
    }

    /** 线程是否在跑（诊断用；判式测试不依赖它）。 */
    fun isRunning(): Boolean = running

    /** 最近一次冻结现场；没抓到过 = null。报告侧的读数口就认这一个引用。 */
    fun lastScene(): StallScene? = lastScene

    /** 现场正文；没抓到过 = 空表（调用方据此**不写这一节**，与 [StallJudgment.NOT_CAPTURED] 不同事）。 */
    fun sceneLines(): List<String> = lastScene?.lines() ?: emptyList()

    /**
     * 起线程（幂等）。
     *
     * 线程是 daemon（不挡进程退出，也绝不让「退出游戏」被哨兵拖住）；名字 `eratw-stall` 便于
     * 真机线程表里认出它自己（proc 下 task 目录里的 comm，口径同 MainActivity 的 `eratw-assets-wa`）。
     * 起线程失败（理论上有：线程数耗尽）只意味着没有这一路取证，不影响游戏 ⇒ `runCatching`。
     */
    fun start() {
        if (running) return
        running = true
        runCatching {
            worker = Thread({ loop() }, SENTINEL_THREAD_NAME).apply {
                isDaemon = true
                // 不抢主线程的时间片：取证线程跑到最高优先级是没道理的（它一秒只醒两次）。
                priority = Thread.NORM_PRIORITY - 1
            }.also { it.start() }
        }.onFailure {
            // 起不来就整路退回「什么都没发生」，并允许下一次 onResume 再试一次。
            running = false
            worker = null
        }
    }

    /**
     * 停线程（幂等，`onDestroy` 必达）。
     *
     * `interrupt()` 只打在**自己**那条线程上（把它从 `Thread.sleep` 里叫醒）；
     * 不 join：销毁路径上任何一次等待都是在给已经够长的 onDestroy 加时长，
     * 而线程自己是 daemon + `running` 判据，醒来即收口。现场 [lastScene] **不清**：
     * 本进程若随后崩掉，同一进程里的采集器还要读它。
     */
    fun stop() {
        running = false
        uiLoopExpected = false
        val t = worker
        worker = null
        runCatching { t?.interrupt() }
    }

    /**
     * 线程体：睡一拍、判一次。**任何**异常都只让这一拍作废（最坏退化成「什么都没发生」）。
     *
     * 循环外再包一层 `runCatching`：`tick` 自己已经不抛，这里兜的是时钟 lambda 与
     * `Thread.sleep` 之外的意外；`Error`（OOM）也一样吞在本线程内——它不该把进程带下去，
     * 更不该在崩溃处理器里再生成一份「哨兵崩了」的报告。
     */
    private fun loop() {
        while (running) {
            try {
                Thread.sleep(sampleIntervalMs)
            } catch (_: InterruptedException) {
                break
            }
            if (!running) break
            val now = runCatching { clockMs() }.getOrNull() ?: continue
            runCatching { tick(now) }
        }
    }

    /**
     * 一拍的全部动作（`internal` 只为单测直调；生产只由 [loop] 调）。
     *
     * 返回这次钉出来的现场，没钉则 null。四道闸按**成本从低到高**排：
     * [uiLoopExpected] 是一次布尔读，心跳快照是三次 volatile，抓栈才是重活。
     *
     * 冷却挂在「判式成立」那一刻，不是挂在「抓栈成功」之后：钉发口（落盘）抛了也照样进冷却，
     * 否则一次落盘失败会让后面每一拍都重试抓栈 —— 那是把取证变成新的卡顿源。
     */
    internal fun tick(nowMs: Long): StallScene? {
        if (!uiLoopExpected) return null
        val snapshot = runCatching { heartbeat.snapshot() }.getOrNull() ?: return null
        // 第二道证据：计数在前进就说明循环还活着（墙钟读数可能是深睡唤醒后的旧值），
        // 这一拍作废但**基线照更新** —— 只在「要动手」那一拍读一次计数的写法会把它自己的
        // 参照系丢掉。方向上只收紧不放松（判式与理由见 StallJudgment.hasUiProgress）。
        val uiCount = snapshot.reading(HeartbeatChannel.UI_FRAME)?.count ?: -1L
        val progressed = StallJudgment.hasUiProgress(lastUiFrameCount, uiCount)
        lastUiFrameCount = uiCount
        if (progressed) return null
        val uiAge = snapshot.ageMs(HeartbeatChannel.UI_FRAME, nowMs) ?: return null
        if (!StallJudgment.shouldCapture(uiAge, thresholds)) return null
        if (!StallJudgment.shouldPin(nowMs, lastPinAtMs)) return null
        lastPinAtMs = nowMs
        val scene = capture(nowMs, snapshot, uiAge) ?: return null
        lastScene = scene
        runCatching { pin(StallJudgment.trailPhase(scene), scene) }
        return scene
    }

    /**
     * 抓两条线程的栈并组出现场；抓不到栈也要出**一行不撒谎的现场**（帧表为空 = [StallJudgment.NOT_CAPTURED]）。
     *
     * 返回 null 只有一种情况：连现场都组装不出来（lambda 本身炸）。每条线程各自
     * `runCatching`——`Thread.getStackTrace()` 在 ART 上是安全点转储，不给它加第二次机会主义假设。
     */
    private fun capture(nowMs: Long, snapshot: HeartbeatSnapshot, uiAgeMs: Long): StallScene? = runCatching {
        val ui = runCatching { mainThread() }.getOrNull()
        val vm = runCatching { vmThread() }.getOrNull()
        StallJudgment.buildScene(
            nowMs = nowMs,
            snapshot = snapshot,
            uiAgeMs = uiAgeMs,
            uiThreadName = ui?.name,
            uiTrace = ui?.let { t -> runCatching { t.stackTrace }.getOrNull() },
            vmThreadName = vm?.name,
            vmTrace = vm?.let { t -> runCatching { t.stackTrace }.getOrNull() },
            thresholds = thresholds,
        )
    }.getOrNull()

    private companion object {
        /** 哨兵线程名（真机线程表里认出它自己用）。 */
        const val SENTINEL_THREAD_NAME = "eratw-stall"
    }
}

/**
 * 按线程名找一条活着的线程（本仓口径：脚本线程用名字认，见 [StallJudgment.VM_THREAD_NAME]）。
 *
 * 为什么不走 `GameHost.debugVmThread()`（GameHost.kt:386，DevTools 那条路用的就是它）：
 * 那个口挂在宿主实例上，而实例引用 `MainActivity.host` 是**普通字段**
 * （MainActivity.kt:174，注释自陈「只在 UI 线程读写」）⇒ 哨兵线程去读它是一次数据竞争，
 * 读到旧引用就会把**已退出**的那条 VM 线程的栈钉成现场（比没栈更坏）。
 * 按名字找则只碰 JDK 自己的线程表，不读任何本仓字段；找不到（线程没起 / 已退）交回 null，
 * 现场里如实写 [StallJudgment.NOT_CAPTURED]。
 *
 * 成本：一次 `ThreadGroup.enumerate`（按 `activeCount()+8` 分配一个引用数组；
 * `threadCount` 不是公开 API，只有 `activeCount` 是）。
 * 只在判式成立、冷却到点之后才调一次，常态路径不经过这里。整段 `runCatching`：
 * 线程表读不到就是「这一路没栈」，不许把哨兵打死。
 */
internal fun findLiveThreadByName(name: String): Thread? {
    val self = Thread.currentThread()
    return runCatching {
        val root = generateSequence(self.threadGroup) { it.parent }.lastOrNull() ?: return null
        val slots = arrayOfNulls<Thread>((root.activeCount() + 8).coerceAtLeast(8))
        val n = root.enumerate(slots, true)
        if (n <= 0) return@runCatching null
        (0 until n).asSequence()
            .mapNotNull { slots[it] }
            .firstOrNull { it !== self && it.name == name && it.isAlive }
    }.getOrNull()
}

/**
 * 生产装配用的主线程来源。
 *
 * `Looper.getMainLooper().thread` 在任意线程取都安全（`getMainLooper()` 只是读一个静态槽），
 * 构造期调用也没有问题；整段 `runCatching` ⇒ 取不到就是「这一路没栈」，现场里如实写未取到。
 */
internal fun stallMainThread(): Thread? = runCatching { Looper.getMainLooper().thread }.getOrNull()

/** 生产装配用的脚本线程来源：按名字认（取舍见 [findLiveThreadByName]）。 */
internal fun stallVmThread(): Thread? = findLiveThreadByName(StallJudgment.VM_THREAD_NAME)

/** 缺省时钟：与心跳同一把单调钟（`elapsedRealtime`，不受改墙钟影响）。 */
internal val stallClockMs: () -> Long = { SystemClock.elapsedRealtime() }
