package com.eratw.port.android

import android.os.Build
import android.os.Debug
import android.util.Log

/**
 * 装载链内存埋点（M8-APPMEM）。
 *
 * ## 读数口径
 *
 * 每段一行 `load.<阶段> used=XMiB max=YMiB native=NMiB t=Nms`，收尾一行 `load.peak`：
 *
 * - `t` = 距装载链起点（本类构造）的**累计毫秒**，相邻两段的差就是该段耗时。
 *   2026-10-22 之前只有内存读数、没有时间读数，于是「首启两分钟」只能靠猜；
 *   现在一条 `times=` 就能把「时间花在哪段」与「内存花在哪段」并排列出来。
 *
 * - `used` = `Runtime.totalMemory() - Runtime.freeMemory()`：当前 Java 堆（已提交区）里的占用。
 *   它**含尚未回收的垃圾**，所以阶段之间要插 [gcHint] 才有可比性；真机 512 MB 堆机型的
 *   `used` 一旦摸到 `max`，下一次大分配就 OOM。
 * - `max` = `Runtime.maxMemory()`：ART 上是 `dalvik.vm.heapgrowthlimit`（manifest 开 `largeHeap`
 *   时是 `heapmaxsize`）换算成字节；与 `ProgramLoader.estimateHeap` 的判据是同一个读数，
 *   两处口径因此不会互相打架。
 * - `native` = `Debug.getNativeHeapAllocatedSize()`：映射窗口之外的 native 分配（渲染位图、
 *   MediaPlayer 缓冲等）。个别 ART 实现上不可用，回落 0 而不是抛。
 *
 * ## 开关
 *
 * 只关**日志**（release 静默）：采样三列始终进行，因为失败屏要显示峰值读数；三次系统调用
 * 的成本远低于一次段解码。开关由宿主按 `ApplicationInfo.FLAG_DEBUGGABLE` 决定。
 *
 * ## 为什么不多占内存
 *
 * 本类只存每段四个标量（阶段名 + 三个 MiB 读数），不持有 Program、不持有任何 ByteArray，
 * 埋点本身不会推高被它测量的那个峰值。
 */
internal class LoadTrace(
    private val tag: String,
    /** release 的 APK 里为 false：只静默日志，采样照常。 */
    val logEnabled: Boolean,
) {

    /** 一次阶段读数（MiB + 距本轮装载开始的毫秒数）。 */
    internal data class Sample(
        val stage: String,
        val usedMiB: Long,
        val maxMiB: Long,
        val nativeMiB: Long,
        /** 距 [LoadTrace] 构造（= 装载链起点）的毫秒数：`load.<段>` 行的 `t=` 就是它。 */
        val atMs: Long,
        /** 自**上一段采样**以来 ART 的 GC 累计毫秒增量（`art.gc.gc-time`）。-1 = 读不到。 */
        val gcMs: Long = -1L,
        /** 自上一段以来的 GC 次数增量（`art.gc.gc-count`）。 */
        val gcCount: Long = -1L,
        /** 自上一段以来的分配字节增量（`art.gc.bytes-allocated`，MiB）。 */
        val allocMiB: Long = -1L,
    )

    /** 装载链起点。用 [System.nanoTime] 而不是 `SystemClock`：本类不依赖 Android，纯 JVM 也能跑。 */
    private val originNanos: Long = System.nanoTime()

    /** 距起点的毫秒数。 */
    private fun sinceOriginMs(): Long = (System.nanoTime() - originNanos) / 1_000_000L

    private val samples = ArrayList<Sample>(EXPECTED_STAGES)

    // ---- 段内 GC / 分配增量（2026-10-24）----
    // `art.gc.gc-time` / `art.gc.bytes-allocated` 是 ART 的**进程累计**读数，本类只记增量：
    // 每次 sample() 与上一次的读数相减，就得到「这一段里 GC 停了多久、分配了多少」。
    // 读数拿不到（API < 23 或 ROM 阉割）一律留 -1，不影响装载。
    private var lastGcMs: Long = gcStat("art.gc.gc-time")
    private var lastGcCount: Long = gcStat("art.gc.gc-count")
    private var lastAllocBytes: Long = gcStat("art.gc.bytes-allocated")

    /** 堆占用最高的那一段（`load.peak` 的 `at`，也是失败屏峰值的来源）。 */
    fun peakHeap(): Sample? = samples.maxByOrNull { it.usedMiB }

    /** native 分配最高的那一段。 */
    fun peakNative(): Sample? = samples.maxByOrNull { it.nativeMiB }

    /** 采一段并落一行日志（release 只采样、不打日志）。 */
    fun mark(stage: String): Sample {
        val sample = sample(stage)
        samples += sample
        if (logEnabled) {
            Log.i(
                tag,
                "load.$stage used=${sample.usedMiB}MiB max=${sample.maxMiB}MiB " +
                    "native=${sample.nativeMiB}MiB t=${sample.atMs}ms " +
                    "dgc=${sample.gcMs}ms dgcN=${sample.gcCount} dalloc=${sample.allocMiB}MiB",
            )
        }
        return sample
    }

    /**
     * 在下一段大分配之前收一次垃圾，再把收完的读数落成 `load.gc.<stage>`。
     *
     * 只在已用堆超过 [GC_MIN_USED_MIB] 时才真的调 `Runtime.gc()`：刚进装载函数时只有几 MiB，
     * 收一次纯属浪费启动时间。GC 不减少常驻，只把「待回收垃圾 + 新分配」的叠加峰削掉 ——
     * 这正是 512 MB 堆机型上 `program <-> meta <-> session` 三段交界处的余量来源。
     */
    fun gcHint(stage: String) {
        // 2026-10-24 真机实证（vivo V2199A / max=512 MiB）：装载链上有两处**背靠背**的 gcHint ——
        // `meta` 前（used=342 MiB）与 `session` 前（used=173 MiB）。第一个收掉 program 段留下的
        // 169 MiB 垃圾（342→173，值 624 ms）；第二个**几乎什么都收不到**（173→173），却照样付了
        // 一次全量 GC 的 443 ms。
        // 因此把「24 MiB 就收」改成「已用堆超过上限的 [GC_TRIGGER_PERCENT]% 才收」：
        // 512 MiB 机型上阈值 281 MiB ⇒ meta 前照收、session 前跳过；小堆机型上两处都仍会触发，
        // 「交界处余量」的原始保护不丢。
        val trigger = maxOf(GC_MIN_USED_MIB, maxMiB() * GC_TRIGGER_PERCENT / 100L)
        if (usedMiB() >= trigger) Runtime.getRuntime().gc()
        mark("gc.$stage")
    }

    /**
     * 兼容装载档专用：不问阈值、逢段必收（2026-10-28）。
     *
     * [gcHint] 的 55% 阈值是为「正常装载别白付 GC」设计的；兼容档的任务恰恰是**用装载时间
     * 换堆余量** —— 同一份产物的高水位读数在纯 GC 时机噪声下能从 342 摆到 725 MiB
     * （`ProgramLoader.estimatedHeapBytes` 标定表），512 MiB 挡位本来就贴边，任何一次
     * 「该收没收」都可能是 OOM 的临门一脚。收完照常落 `gc.<stage>`，失败屏/日志口径不变。
     */
    fun forceGc(stage: String) {
        Runtime.getRuntime().gc()
        mark("gc.$stage")
    }

    /** 只在 Debug 版落一条 warn（release 静默）；用于「余量薄但仍继续」这类不致命读数。 */
    fun warn(message: String) {
        if (logEnabled) Log.w(tag, message)
    }

    /** 当前已用堆字节（预检的「余量」判据用，不依赖上一次采样）。 */
    fun usedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    /** 当前已用堆 MiB。 */
    fun usedMiB(): Long = usedBytes() / MIB

    /** 堆上限 MiB（`Runtime.maxMemory()`；largeHeap 包在 512 MiB 机型上实测 = 512）。 */
    fun maxMiB(): Long = Runtime.getRuntime().maxMemory() / MIB

    /**
     * 汇总一行 `load.peak`：峰值堆占用、峰值 native、堆上限与峰值出现的阶段，
     * 末尾 `series` 是各阶段堆占用的串联表（一条 logcat 就能出「哪段各占多少 MiB」）。
     */
    fun logPeak() {
        if (!logEnabled) return
        val heap = peakHeap() ?: return
        val native = peakNative()?.nativeMiB ?: 0L
        val series = samples.joinToString(",") { "${it.stage}:${it.usedMiB}" }
        // `elapsed` 是各段 `t=` 的**最后一段**（也就是装载链走完的累计毫秒），
        // 与 `series` 并列：一条 logcat 同时给出「花在哪段（内存）」与「一共花了多久（时间）」。
        val elapsed = samples.lastOrNull()?.atMs ?: 0L
        val times = samples.joinToString(",") { "${it.stage}:${it.atMs}" }
        Log.i(
            tag,
            "load.peak used=${heap.usedMiB}MiB max=${heap.maxMiB}MiB native=${native}MiB " +
                "at=${heap.stage} elapsed=${elapsed}ms series=$series times=$times",
        )
    }

    /**
     * 失败屏用的峰值摘要（一行、带阶段名）。
     *
     * 与 [logPeak] 分开：release 的 APK 里日志被关掉，但装载失败时屏上仍要能读出
     * 「峰值是多少、卡在哪一段、堆上限多少」。
     */
    fun screenSummary(): String {
        val heap = peakHeap() ?: return "未采到峰值（装载在第一次采样前中止）"
        return "峰值 ${heap.usedMiB} MiB / native ${peakNative()?.nativeMiB ?: 0} MiB / " +
            "堆上限 ${heap.maxMiB} MiB / 卡在 ${heap.stage}"
    }

    /**
     * DevTools `loadtrace` 用：逐段一行（段名 + 已用堆 + 距起点毫秒）。
     *
     * 真机 vivo（Origin OS）会把应用 logcat 整条丢掉，`load.*` 那批 `Log.i` 拿不到；
     * 但采样表始终在内存里，所以调试通道能拿到与 logcat 完全同口径的装载分段读数。
     */
    fun summaryLines(): List<String> = samples.map { s ->
        val extra = if (s.gcMs >= 0 || s.allocMiB >= 0) {
            " dgc=${s.gcMs}ms dgcN=${s.gcCount} dalloc=${s.allocMiB}MiB"
        } else {
            ""
        }
        "${s.stage} used=${s.usedMiB}MiB t=${s.atMs}ms$extra"
    }

    private fun sample(stage: String): Sample {
        val runtime = Runtime.getRuntime()
        val gcMs = gcStat("art.gc.gc-time")
        val gcCount = gcStat("art.gc.gc-count")
        val alloc = gcStat("art.gc.bytes-allocated")
        val dGcMs = if (gcMs >= 0 && lastGcMs >= 0) gcMs - lastGcMs else -1L
        val dGcCount = if (gcCount >= 0 && lastGcCount >= 0) gcCount - lastGcCount else -1L
        val dAlloc = if (alloc >= 0 && lastAllocBytes >= 0) (alloc - lastAllocBytes) / MIB else -1L
        if (gcMs >= 0) lastGcMs = gcMs
        if (gcCount >= 0) lastGcCount = gcCount
        if (alloc >= 0) lastAllocBytes = alloc
        return Sample(
            stage = stage,
            usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / MIB,
            maxMiB = runtime.maxMemory() / MIB,
            nativeMiB = nativeMiB(),
            atMs = sinceOriginMs(),
            gcMs = dGcMs,
            gcCount = dGcCount,
            allocMiB = dAlloc,
        )
    }

    /** ART 进程累计读数；API < 23 或 ROM 不支持时 -1（绝不让埋点把装载搞崩）。 */
    private fun gcStat(key: String): Long = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Debug.getRuntimeStat(key)?.toLongOrNull() ?: -1L
        } else {
            -1L
        }
    } catch (_: Throwable) {
        -1L
    }

    /** `Debug.getNativeHeapAllocatedSize()` 在个别 ART 实现上会抛；埋点不能把装载搞崩。 */
    private fun nativeMiB(): Long = try {
        Debug.getNativeHeapAllocatedSize() / MIB
    } catch (_: Throwable) {
        0L
    }

    private companion object {
        const val MIB = 1024L * 1024L

        /** 预分配的采样槽：start / gc.preflight / preflight / program / gc.meta / meta / gc.session / session / ready。 */
        const val EXPECTED_STAGES = 12

        /** 低于这个堆占用就不值得显式 GC（刚进装载函数时的常态是几 MiB）。 */
        const val GC_MIN_USED_MIB = 24L

        /**
         * `gcHint` 的触发比例（已用堆 / 堆上限）。2026-10-24 起取代「24 MiB 就收」：
         * 512 MiB 上限 ⇒ 281 MiB。只跳掉「收不到东西的背靠背 GC」，不是取消交界回收。
         */
        const val GC_TRIGGER_PERCENT = 55L
    }
}
