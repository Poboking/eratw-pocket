package com.eratw.port.android

import com.eratw.port.render.canvas.CanvasConsoleView

/**
 * UI 慢帧归因的**纯**判式（2026-11-20 ANR 取证补口）。
 *
 * ## 要补的读数缺口
 *
 * vivo 2026-11-20 的 ANR（点击「调教时间」必现）：轨迹里哨兵钉了
 * `stallstack … uiFrozen=1618ms vm=4583ms frames=12 ui@StringFactory.newStringFromBytes:-2`，
 * 随后 UI 一直没动到系统按 `Input dispatching timed out` 收走。堆只有 10%（排除 GC/OOM），
 * 但报告里只有栈顶 1 帧 —— 只知道 UI 线程停在 native String 构造，不知道那 5 秒花在
 * pump 的四段（快照差分/选项刷新/失败屏/内存采样）里的哪一段，还是在 `onDraw` 的
 * 内容交帧（publish/adapt/flatten）里。本判式给「那一帧到底慢在哪一段」一个**分段坐标**：
 * pump 段慢 ⇒ 钉 `slowui stage=<段名>`；onDraw 帧慢 ⇒ 钉 `slowui onDraw`。
 * 下一次报告把两者并读，就能把 5 秒归到「VM 交来的页面太大（交帧路）」还是
 * 「选项/失败屏/内存采样里的某一段（pump 路）」。
 *
 * 阈值 300 ms 的口径：pump 四段正常各 < 5 ms、`onDraw` 正常 < 16 ms（60 Hz），
 * 300 ms = 「玩家已可感知的一帧」，再低就会把设备瞬间的调度抖动当慢帧灌满 64 行轨迹。
 *
 * 钉发自身冷却 1 s：慢段可能连续多帧出现（比如交帧路一卡就是十几帧），不冷却会把
 * 轨迹整段换成同一个读数、把真正的 `turn`/`freeze` 行挤掉（64 行环形表的体积纪律）。
 *
 * 抽成 object 的理由与 [StallJudgment] / [HeartbeatJudgment] 相同：判式是纯算术，
 * `:app` 的 JVM 单测直接钉死，不碰真线程与时钟。
 */
object SlowUiGate {

    /** pump 四段的单段阈值（毫秒）：正常 < 5 ms，300 ms = 玩家可感知的异常段。 */
    const val STAGE_SLOW_MS = 300L

    /** onDraw 单帧墙钟阈值（毫秒）：与 [CanvasConsoleView.SLOW_FRAME_NOTIFY_MS] 同一常量源。 */
    const val FRAME_SLOW_MS = CanvasConsoleView.SLOW_FRAME_NOTIFY_MS

    /** 慢帧钉发的自身冷却（毫秒）：与哨兵/心跳的钉发频率错开，1 s 一发封顶。 */
    const val PIN_MIN_INTERVAL_MS = 1_000L

    /** 轨迹阶段名前缀（与 `turn` / `freeze` / `stallstack` 并列；端点分桶按它认）。 */
    const val TRAIL_PHASE = "slowui"

    /** 纳秒 → 毫秒的换算（`System.nanoTime` 口径，pump 段计时用）。 */
    const val NS_PER_MS = 1_000_000L

    /** pump 段要不要钉：**超过**才钉（等于阈值不钉——边界值留给测试，线上不重复贴边）。 */
    fun isSlowStage(ms: Long): Boolean = ms > STAGE_SLOW_MS

    /** onDraw 帧要不要钉：同上，超阈值才钉。 */
    fun isSlowFrame(ms: Long): Boolean = ms > FRAME_SLOW_MS

    /**
     * 慢帧钉发的冷却到了没有。
     * `lastPinAtMs < 0` = 本进程还没钉过 ⇒ 首钉必走（形状对齐 [StallJudgment.shouldPin]）。
     */
    fun shouldPin(nowMs: Long, lastPinAtMs: Long): Boolean =
        lastPinAtMs < 0L || nowMs - lastPinAtMs >= PIN_MIN_INTERVAL_MS

    /** pump 段的轨迹阶段文本：`slowui stage=选项刷新 ms=4521`。 */
    fun stagePhase(stage: String, ms: Long): String = "$TRAIL_PHASE stage=$stage ms=$ms"

    /** onDraw 帧的轨迹阶段文本：`slowui onDraw ms=4521`。 */
    fun framePhase(ms: Long): String = "$TRAIL_PHASE onDraw ms=$ms"
}
