package com.eratw.port.android

/**
 * 首屏装载链的分段进度（纯计算，不依赖 Android 运行时）。
 *
 * ## 为什么要有这一层
 *
 * 玩家报「开局加载长到让人以为卡了」。解包段有 ticker 报数，但装载段从
 * `MainActivity.syncLoading` 那一拍起直到首帧，覆盖层明细一直停在「已落盘：N 条」，
 * `ShellUi` 只能靠一格闪烁表示还活着，几十秒里没有任何可核对的进展。而装载链
 * 自己是有逐段埋点的：`LoadTrace` 的 `samples` 记着 `start`、`program.` 前缀的
 * 解码细段、`program`、`meta`、`session`、`ready`。
 *
 * 本对象把这份**已有的阶段名序列**折算成分段档位与单调递增的比例，供覆盖层消费。
 * 它不加速装载、不隐藏慢页、不吞错误：阶段没到就是没到，未知阶段沿用上一档，
 * 绝不用「采样数除以猜出来的总数」编一个会倒退的百分比。
 *
 * ## 口径
 *
 * - 里程碑定档：解码细段与 `program` 完成点归 PROGRAM，`meta` 归 META，
 *   `session` 归 SESSION，`ready` 归 STARTING。
 * - 细段递增：每条 `program.` 前缀的解码段让比例前进一小步，上限压在 `program`
 *   完成点之下，避免细段还没走完却显示已装载完。
 * - 单调：比例只取较大值、档位只按枚举序前进；`gc.` 与 `boot.` 交界采样、失败标记、
 *   GameSession 内部阶段一律不改档位也不倒退。
 * - 返回 null = 还没进入任何已知阶段，覆盖层保持解包段收尾时的原样。
 *
 * 无 C# 锚：移植侧自研的安卓装载壳（ADR-005 安卓适配）。TODO(verify)：真机上
 * 各细段的实际条数与耗时占比未采，比例只表达相对推进，不表达时间百分比。
 */
/**
 * 四段档位，与 [LoadProgress] 一样只在 app 模块内消费：上报口是 [GameHost.loadPhaseSink]
 * （构造后赋值的 `internal` 实例回调，不是 public 构造参数），宿主与定向单测都同模块，
 * 因此这两个值类型不对外暴露。
 */
internal enum class LoadPhase {
    PROGRAM,
    META,
    SESSION,
    STARTING,
}

/** 一次装载的当前档位与进度比例（0 到 1，单调递增）。 */
internal data class LoadProgress(
    val phase: LoadPhase,
    val fraction: Float,
)

internal object LoadPhaseProgress {
    private const val START_FRACTION = 0.02f
    private const val DECODE_FIRST = 0.10f
    private const val DECODE_STEP = 0.025f
    private const val DECODE_CAP = 0.55f
    private const val PROGRAM_DONE = 0.58f
    private const val META_DONE = 0.72f
    private const val SESSION_DONE = 0.86f
    private const val READY = 0.94f

    /** 档位只允许按枚举序前进；阶段乱序上报时不许把进度条拨回去。 */
    private fun advance(current: LoadPhase?, next: LoadPhase): LoadPhase =
        if (current == null || next.ordinal > current.ordinal) next else current

    fun of(stages: List<String>): LoadProgress? {
        var phase: LoadPhase? = null
        var fraction = 0f
        var decodeSteps = 0
        for (stage in stages) {
            when {
                stage == "start" || stage == "handoff" || stage == "preflight" -> {
                    phase = advance(phase, LoadPhase.PROGRAM)
                    fraction = maxOf(fraction, START_FRACTION)
                }
                stage.startsWith("program.") -> {
                    decodeSteps++
                    phase = advance(phase, LoadPhase.PROGRAM)
                    fraction = maxOf(
                        fraction,
                        (DECODE_FIRST + decodeSteps * DECODE_STEP).coerceAtMost(DECODE_CAP),
                    )
                }
                stage == "program" -> {
                    phase = advance(phase, LoadPhase.PROGRAM)
                    fraction = maxOf(fraction, PROGRAM_DONE)
                }
                stage == "meta" -> {
                    phase = advance(phase, LoadPhase.META)
                    fraction = maxOf(fraction, META_DONE)
                }
                stage == "session" -> {
                    phase = advance(phase, LoadPhase.SESSION)
                    fraction = maxOf(fraction, SESSION_DONE)
                }
                stage == "ready" -> {
                    phase = advance(phase, LoadPhase.STARTING)
                    fraction = maxOf(fraction, READY)
                }
                else -> Unit
            }
        }
        return phase?.let { LoadProgress(it, fraction) }
    }
}
