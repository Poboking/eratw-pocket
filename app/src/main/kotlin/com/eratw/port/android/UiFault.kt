package com.eratw.port.android

import com.eratw.port.core.code.LoopGuardCause
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VmException
import com.eratw.port.core.err.CodeEE

/**
 * 启动流程抛出之后的处置判据（`MainActivity.onStartupFault` 的唯一分支来源，纯逻辑可 JVM 单测）。
 *
 * 三种情形必须分开，混在一起会出两种事故：
 * - 已经交给界面的那一局（`adopted`）被 close ⇒ 玩家刚进游戏又被踢出来，而且失败屏文案
 *   （「启动失败」）与事实相反；
 * - Activity 已销毁还去上屏 ⇒ 往一条没有窗口的视图写，白费一次分配，还可能再抛一次；
 * - 还没接管却只记日志 ⇒ 屏上永远停在「正在检查 APK 随包数据…」，玩家以为卡死。
 */
internal enum class StartupFaultAction {
    /** 会话已经交给界面：只把重绘循环重排上，不打失败屏。 */
    RESUME_PUMP,

    /** 没接管 + Activity 还活着：关掉半装配宿主，上失败屏。 */
    CLOSE_AND_FAIL,

    /** Activity 已销毁：关掉宿主，什么也不上屏。 */
    CLOSE_SILENTLY,
}

/**
 * 决定启动异常该怎么收场。
 *
 * @param adopted 这一局的宿主是否已经被界面接管（`host === candidate`）
 * @param destroyed Activity 是否已经销毁（含正在 finish）
 */
internal fun startupFaultAction(adopted: Boolean, destroyed: Boolean): StartupFaultAction = when {
    // 接管优先：已经能画的一局不该因为「交接之后那一步」失败被拆掉。
    adopted -> StartupFaultAction.RESUME_PUMP
    destroyed -> StartupFaultAction.CLOSE_SILENTLY
    else -> StartupFaultAction.CLOSE_AND_FAIL
}

/**
 * 从系统状态机那一泵里逃出来的异常，该按**可恢复的脚本错误终局**收（留会话、能回标题），
 * 还是照旧交回装载失败的收口（关会话、报「启动失败」）。
 *
 * 判据刻意在 `CodeEE` 内按成因收窄一层：`StepLimitExceeded` 这一个类型装了两种语义，
 * 不能一刀切按 [VmException] 排除。
 * - `CodeEE` = 原版 `MinorShift.Emuera` 的**脚本级**运行错误。原版对它的处置写死在
 *   官方 reference `Process.cs:438-478`：DoScript 顶层 `catch (Exception exc)`（`:462-478`）
 *   → `handleException`（`:595-661`）打屏 → **正常返回**，进程与会话都留着，复活通道是
 *   `GotoTitle`（`EmueraConsole.cs:2569-2581`）。所以这一类穿破泵、上「启动失败」屏是移植侧
 *   自己加的升级，不是官方行为。
 * - [LoopGuardCause.PlayerChoseExit]：墙钟告警框里玩家按「是」抛的那条 `CodeEE`
 *   （skia `Process.cs:482-484`；官方 reference `Process.cs:505-507`，方法体 `:493-512`；
 *   文案 = `Lang.Error.SelectExitInfiniteLoopMB`）。它**就是普通脚本错误**，
 *   必须走可恢复终局；ENGFIX-3/4 把它复刻进 [StepLimitExceeded] 时已经要求宿主按
 *   `guardCause` 分流（`Errors.kt:249-253`），本判据是那一环的落点。
 *   旧代码一律按 `VmException` 排除 ⇒ 玩家读档后日始长链越过 `InfiniteLoopAlertTime`
 *   弹出告警框、按「是」退出，却被关掉会话、上「启动失败」屏并落一份「启动失败」报告，
 *   而「重试」只会重新装载再撞同一个框（0.2.42+18 / HONOR LGN-NX1，`BASE自然変動`）。
 * - [LoopGuardCause.DeviceBudget]：移植层 10 亿步极端兜底（ADR-005 登记项），不是官方语义，
 *   现场转储（`deadloop-site.txt` 的语句级转储）挂在装载失败那条收口上，照旧不可恢复。
 *   `CallStackOverflow` 与其它 [VmException] 同理。
 * - 非 `CodeEE`（OOM、`RuntimeException`、装载层自己的抛错）**不动**：原版的
 *   `catch (Exception)` 兜它们，但移植侧这些形态的现场在失败屏与崩溃兜底那头，
 *   把它们一起折成「可恢复终局」只会把真 bug 藏成一屏「游戏出错」。
 */
internal fun isRecoverableScriptError(t: Throwable): Boolean = when {
    t !is CodeEE -> false
    t is StepLimitExceeded -> t.guardCause == LoopGuardCause.PlayerChoseExit
    t is VmException -> false
    else -> true
}

/**
 * 「回到标题」那条复活线程抛出来之后：按下不表，还是照旧上失败屏并落一份报告。
 *
 * 判据只有一条，而且与既有的两道闸同源 —— 宿主此刻是不是已经 `close()`：
 * - [SUPPRESS_BY_CLOSE]：复活途中宿主被关（`onDestroy`、换局）。`gotoTitle()` 撞上 VM 主循环的
 *   中断检查点时抛的是「执行被中断（宿主正在关闭）」（`Vm.kt:732-746`，消息逐字保留就是给
 *   这种按消息识别的收口用的）；会话已关时任何等待抛 `InputSinkClosedException`
 *   （`QueueInputSink.kt:11`，`IllegalStateException` 的子类，所以 `catch (t: Throwable)` 捞得到）。
 *   两者都是**玩家正在拆这一局**的正常退出路径。照旧上屏就把失败屏换成「回到标题失败」，
 *   照旧落报告就是一份「回到标题失败」的缺陷单 —— 收件箱 2026-11-03 一天 6 份「关闭竞态」
 *   正是同一形状。`GameHost` 的 `drive`（`GameHost.kt:3350-3353`）与 `pumpUntilTerminal`
 *   （`:3482` 与 `:3495`）已经按这条闸静默收口，独漏 `backToTitle` 的工作线程那一条。
 * - [FAIL_AND_REPORT]：宿主还活着却失败 = 真事故，屏与报告都要留（既有形状一字不动）。
 *
 * 判据放本文件（纯逻辑、无 Android 依赖）就是为了两枚都能在 JVM 单测里钉死；接线侧「闸必须排在
 * `writeErrorReport` 之前」由 `UiFaultTest` 的源码哨兵钉 —— 顺序写反了这两枚判据全绿但行为照旧。
 */
internal enum class BackToTitleFaultAction {
    /** 宿主正在关闭：只打一行日志即收，不动失败屏、不落报告。 */
    SUPPRESS_BY_CLOSE,

    /** 宿主还活着：按既有形状上「回到标题失败」屏并落报告。 */
    FAIL_AND_REPORT,
}

/**
 * 决定「回到标题」工作线程那发 `catch (t: Throwable)` 怎么收场。
 *
 * @param closed 宿主是否已收口（生产读 `GameHost.closed`，`@Volatile`；口径同
 * [ViewportReflowChain.run] 的 `closed` 参数）
 */
internal fun backToTitleFaultAction(closed: Boolean): BackToTitleFaultAction =
    if (closed) BackToTitleFaultAction.SUPPRESS_BY_CLOSE else BackToTitleFaultAction.FAIL_AND_REPORT

/**
 * 失败屏该按哪一族讲（2026-11-01 一位玩家的截图带出来的这条）。
 *
 * 三族的话术此前共用一套：失败屏无条件写「启动失败」，控制台里再钉三行
 * 「产物根 / 随包 APK 正常不该走到这里（磁盘满、assets 坏、program 与 meta 不配对）/
 * adb 推数据」，覆盖层那句说明也以「若反复失败请检查产物目录」收尾。可这同一张屏
 * 同样承接**游戏已经跑起来之后**的终局（状态机停在某态、回到标题失败）—— 那位玩家
 * 玩到第 8 日、指令菜单都画完了，中止之后读到的是「你的包坏了」，方向整个是错的：
 * 真凶是 512 MiB 挡位下一次被 `runCatching` 吞掉的分配失败。
 */
internal enum class FailureScreenKind {
    /** 随包产物没解出来、容器或映射坏了：只有这一族该提产物那一串。 */
    ARTIFACT,

    /** 装载没起来但不是产物的锅（堆不足、上一局内存没让出、装载期其它抛错）：只报路径。 */
    LOAD,

    /** 游戏已经跑起来之后中止的：不提产物、不提 adb，也不说「启动失败」。 */
    IN_GAME,
}

/** 失败屏大标题 —— 措辞只由「这一局有没有真的开始过」决定。 */
internal fun failureScreenTitle(
    kind: FailureScreenKind,
    copy: ShellFailureCopy = ShellFailureCopy.ZH,
): String =
    if (kind == FailureScreenKind.IN_GAME) copy.failureTitleInGame else copy.failureTitleStartup

/**
 * 失败屏控制台快照里「产物」那几行（标题与正文之外的可变部分）。
 *
 * 逐族收窄：[FailureScreenKind.ARTIFACT] 给全三行（那一句就是它的成因清单）；
 * [FailureScreenKind.LOAD] 只给路径一行 ——「磁盘满 / assets 坏」对堆不足是假归因；
 * [FailureScreenKind.IN_GAME] 一行都不给 —— 产物此刻毫无嫌疑，留个路径只会把玩家支去翻目录。
 */
internal fun failureScreenArtifactLines(
    kind: FailureScreenKind,
    artifactDirPath: String?,
    copy: ShellFailureCopy = ShellFailureCopy.ZH,
): List<String> =
    when (kind) {
        FailureScreenKind.IN_GAME -> emptyList()
        FailureScreenKind.LOAD -> listOf(copy.fill(copy.artifactRootLineFormat, artifactDirPath))
        FailureScreenKind.ARTIFACT -> listOf(
            copy.fill(copy.artifactRootLineFormat, artifactDirPath),
            copy.artifactCauseLine,
            copy.artifactAdbLine,
        )
    }

/**
 * 失败屏覆盖层那句说明。
 *
 * 「若反复失败请检查产物目录」只对装载族成立；中局终局说这一句是把人往盘上引，
 * 换成「存档在盘上，不会丢」—— 那才是此刻玩家真正担心的事。
 * 能回标题时那一句两族逐字相同、一字未动。
 */
internal fun failureScreenNote(
    kind: FailureScreenKind,
    canBackToTitle: Boolean,
    copy: ShellFailureCopy = ShellFailureCopy.ZH,
): String = when {
    canBackToTitle -> copy.noteBackToTitle
    kind == FailureScreenKind.IN_GAME -> copy.noteInGame
    else -> copy.noteLoad
}

/**
 * 主线程「这一帧 / 这一次输入出错」的兜底判据（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * ## 为什么需要这一层
 *
 * Android 上主线程抛出的任何未捕获异常都直接结束进程：没有失败屏、没有提示、直接回桌面
 * —— 正是玩家报的「在外面跑来跑去莫名其妙就退出来了」那种形状（2026-10-31 收件箱里
 * 11 份 `OutOfMemoryError` 之外，还有一整类「无异常对象」的静默死）。
 *
 * 绘制侧（`CanvasConsoleView.onDraw`）2026-10-31 已补了 `RuntimeException` 兜底，但
 * **同频率的另外三段没有**：`MainActivity` 的重绘循环（`pump`，每帧跑快照差分 + 选项刷新
 * + 失败屏同步）、触摸回调、按键分发。这三段每帧 / 每次输入都要摸一遍 VM 线程刚发布的数据，
 * 任何一处 `RuntimeException`（`ConcurrentModificationException`、行/列越界、
 * `trying to use a recycled bitmap` 的 `IllegalStateException`）都会把整局带走。
 *
 * 兜住的语义是**这一帧作废、进程继续**，不是「把错误藏起来」：
 * - 首次失败打完整栈（取证面不能丢），之后按 [UiFaultLog.EVERY] 限流打一行计数；
 * - 连续失败到 [UiFaultBreaker.DEFAULT_THRESHOLD] 次说明这一帧的数据已经持续不自洽，
 *   熔断冷却一小段（2 s 起、逐次翻倍、封顶 8 s），避免 60 fps 下每秒 60 次异常把 CPU
 *   烧光；冷却到点会自动放行一次探针帧，恢复了就照常跑。
 *
 * ## 为什么不 catch `Error`
 *
 * 与 `CanvasConsoleView.onDrawFailed` 同一条口径：`OutOfMemoryError` / `StackOverflowError`
 * 要走 `MainActivity` 的崩溃处理器拿真实栈并落错误报告（那是玩家反馈唯一的取证面），
 * 而 OOM 被逐帧吞掉只会变成「每帧都分配失败」的死循环，比退出更糟。
 */
internal enum class UiFaultLogMode {
    /** 打完整栈（每个故障点的第一次）。 */
    FULL,

    /** 只打一行计数（第 [UiFaultLog.EVERY] 次的整数倍）。 */
    SHORT,

    /** 本次不打（限流区间内）。 */
    SILENT,
}

/**
 * 故障日志的限流判据。
 *
 * 只按**累计**次数判，不按时间窗：一帧一崩时按时间窗会把同一段栈刷满 logcat，
 * 而累计计数既能让第一次拿到全栈、也能让「崩了 1,200 次」这种读数一眼可见。
 */
internal object UiFaultLog {

    /** 每隔多少次打一行汇总（120 次 ≈ 60 fps 下 2 秒一行，够密也够省）。 */
    const val EVERY: Long = 120L

    /**
     * 第 [count] 次故障该打哪种日志。
     *
     * @param count 从 1 起的累计次数（调用方先自增再问）
     */
    fun mode(count: Long): UiFaultLogMode = when {
        count <= 1L -> UiFaultLogMode.FULL
        count % EVERY == 0L -> UiFaultLogMode.SHORT
        else -> UiFaultLogMode.SILENT
    }
}

/**
 * 连续失败熔断器：同一帧路上连续出错到阈值就冷却一小段，到点自动放行探针帧。
 *
 * 存在的理由不是「省日志」，而是**省 CPU**：`pump` 走 `postOnAnimation`，一帧一崩时
 * 每秒仍会重试 60 次；每次失败都要走一遍快照差分的前半段，玩家看到的是「卡成幻灯片」。
 * 熔断把重试频率从 60 Hz 压到「每 2~8 秒一次」，同时保留自愈能力（数据一旦恢复自洽，
 * 探针帧成功即完全复位）。
 *
 * 时间基准由调用方给（生产 = `SystemClock.elapsedRealtime`），本类不读时钟 —— 这样
 * 判据可以在 JVM 单测里逐毫秒钉死。
 */
internal class UiFaultBreaker(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val baseCooldownMs: Long = DEFAULT_BASE_COOLDOWN_MS,
    private val maxCooldownMs: Long = DEFAULT_MAX_COOLDOWN_MS,
) {

    /** 累计失败次数（诊断读数，只增不减；日志限流也读它）。 */
    var failures: Long = 0L
        private set

    /** 当前这一轮连续失败次数（成功一次即清零）。 */
    var consecutive: Int = 0
        private set

    /** 冷却截止时刻（调用方时间基准）；0 = 不在冷却。 */
    var cooldownUntilMs: Long = 0L
        private set

    /** 下一次熔断的冷却时长（每熔断一次翻倍，封顶 [maxCooldownMs]）。 */
    var cooldownMs: Long = baseCooldownMs
        private set

    /** 这一帧/这次输入是否放行（冷却期内为 false）。 */
    fun shouldRun(nowMs: Long): Boolean = nowMs >= cooldownUntilMs

    /** 成功一次：连续计数与冷却全部复位（自愈后不该留着上一轮的账）。 */
    fun recordSuccess() {
        consecutive = 0
        cooldownUntilMs = 0L
        cooldownMs = baseCooldownMs
    }

    /**
     * 记一次失败。
     *
     * @return true = 本次触发了熔断（调用方据此决定是否额外打一行「暂停 N ms」）
     */
    fun recordFailure(nowMs: Long): Boolean {
        failures += 1L
        consecutive += 1
        if (consecutive < threshold) return false
        // 先按当前档位定冷却截止，再翻倍：第一轮 2 s、第二轮 4 s、第三轮起 8 s。
        cooldownUntilMs = nowMs + cooldownMs
        cooldownMs = if (cooldownMs > maxCooldownMs / 2L) maxCooldownMs else cooldownMs * 2L
        consecutive = 0
        return true
    }

    companion object {
        /** 连续失败多少次开始熔断：8 次 ≈ 60 fps 下 0.13 秒，肉眼还看不出卡，够判「持续不自洽」。 */
        const val DEFAULT_THRESHOLD: Int = 8

        /** 首轮冷却时长（毫秒）。 */
        const val DEFAULT_BASE_COOLDOWN_MS: Long = 2_000L

        /** 冷却上限（毫秒）：再久就会让「偶发一次自愈」也显得像死机。 */
        const val DEFAULT_MAX_COOLDOWN_MS: Long = 8_000L
    }
}
