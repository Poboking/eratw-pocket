package com.eratw.port.android

import android.os.Build
import android.os.Debug
import android.util.Log
import com.eratw.port.core.host.MemoryTier

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

    /**
     * 段采样监听（R3 结构化事件）：宿主把它接到 `EventJournal.record("load.phase", ...)`。
     *
     * 默认 null = 与引入前逐字等价（不取时间、不拼串）。异常就地吞掉：埋点坏了不许拖死装载。
     */
    var onSample: ((Sample) -> Unit)? = null

    // ---- 段内 GC / 分配增量（2026-10-24）----
    // `art.gc.gc-time` / `art.gc.bytes-allocated` 是 ART 的**进程累计**读数，本类只记增量：
    // 每次 sample() 与上一次的读数相减，就得到「这一段里 GC 停了多久、分配了多少」。
    // 读数拿不到（API < 23 或 ROM 阉割）一律留 -1，不影响装载。
    private var lastGcMs: Long = gcStat("art.gc.gc-time")
    private var lastGcCount: Long = gcStat("art.gc.gc-count")
    private var lastAllocBytes: Long = gcStat("art.gc.bytes-allocated")

    /**
     * 采样表的加锁快照：**读侧统一入口**（2026-11-03 真机 CME 的修复点）。
     *
     * `samples` 的写侧（[mark]）本来就挂在本对象的 monitor 里，但读侧原先裸奔 ——
     * 真机启动失败报告（0.2.39 debug 包，2026-11-03 17:05，vivo V2199A）实锤：
     * VM 线程在 `logPeak` 的 `joinTo` 里迭代采样表，UI 线程同时经
     * `view.post { bootTrace?.mark("boot.首帧后") }` 往同一张表追加，
     * `ArrayList$Itr.checkForComodification` 当场炸掉；异常被装载 catch 接住，
     * 本该正常进标题页的一次启动被判成「启动失败」。
     * 快照是几十个引用的浅拷贝，读侧要么走它、要么自己挂同一把 monitor。
     */
    @Synchronized
    private fun snapshot(): List<Sample> = samples.toList()

    /** 堆占用最高的那一段（`load.peak` 的 `at`，也是失败屏峰值的来源）。 */
    fun peakHeap(): Sample? = snapshot().maxByOrNull { it.usedMiB }

    /** native 分配最高的那一段。 */
    fun peakNative(): Sample? = snapshot().maxByOrNull { it.nativeMiB }

    /** 采一段并落一行日志（release 只采样、不打日志）。 */
    @Synchronized
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
        onSample?.let { listener -> runCatching { listener(sample) } }
        return sample
    }

    /**
     * 在下一段大分配之前收一次垃圾，再把收完的读数落成 `load.gc.<stage>`。
     *
     * **只用于解码期那几处会重复走的交界**：收不收由 [LoadGcPolicy.collectIntraDecode] 判，
     * 阈值由 [gcTriggerPercent] 现取本档的那一条（保守档 [GC_TRIGGER_PERCENT] = 55%，其来历是
     * 真机 2026-10-24 实测的两处**背靠背** hint
     * （`meta` 前 used=342 收掉 169 MiB 垃圾、`session` 前 used=173 什么都收不到却白付
     * 443 ms）；放开档 [GC_TRIGGER_PERCENT_GENEROUS] = 65%，判式见 `core/host/MemoryTier.kt`）。
     * 装载链一次装载只走一次的交界（`meta` / `session`）不归本函数，走
     * [forceGc] —— 那里的读数恰恰常年压在阈值下面，见 [LoadGcPolicy.collectAtSeam]。
     */
    fun gcHint(stage: String) {
        if (LoadGcPolicy.collectIntraDecode(usedMiB(), maxMiB())) Runtime.getRuntime().gc()
        mark("gc.$stage")
    }

    /**
     * 装载链**交界**专用：不问阈值、逢交界必收（2026-10-28 引入，2026-10-30 起也是缺省档的口径）。
     *
     * 两个调用族：
     *
     * 1. **兼容装载档**（2026-10-28）：逢段必收。兼容档的任务就是**用装载时间换堆余量** ——
     *    同一份产物的高水位读数在纯 GC 时机噪声下能从 342 摆到 725 MiB
     *    （`ProgramLoader.estimatedHeapBytes` 标定表），512 MiB 挡位本来就贴边，
     *    任何一次「该收没收」都可能是 OOM 的临门一脚。
     * 2. **缺省档的装载链交界**（`meta` 前、`session` 前，2026-10-30 从 [gcHint] 换过来）：
     *    一次装载只走这两次，付得起；而它俩后面紧跟的是本链最大的两笔分配（49 MiB 侧车解析
     *    ≈170 MiB 分配、会话装配 ≈130 MiB 分配）。判据见 [LoadGcPolicy.collectAtSeam]。
     *
     * 收完照常落 `gc.<stage>`，失败屏/日志口径不变。
     *
     * 收不收仍走判式（[LoadGcPolicy.collectAtSeam] 恒真）—— 不是为了分支，是为了让
     * 「谁把阈值重新套回交界」这件事在单测里当场红，而不是等下一批玩家屏。
     */
    fun forceGc(stage: String) {
        if (LoadGcPolicy.collectAtSeam(usedMiB(), maxMiB())) Runtime.getRuntime().gc()
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
     *
     * 整个方法挂在本对象的 monitor 下：迭代采样表期间不许 [mark] 并发追加 ——
     * 2026-11-03 真机就是在这里被 UI 线程的「boot.首帧后」采样撞出
     * `ConcurrentModificationException`、把一次正常启动判成启动失败的（见 [snapshot]）。
     */
    @Synchronized
    fun logPeak() {
        if (!logEnabled) return
        val snap = snapshot()
        val heap = snap.maxByOrNull { it.usedMiB } ?: return
        val native = snap.maxByOrNull { it.nativeMiB }?.nativeMiB ?: 0L
        val series = snap.joinToString(",") { "${it.stage}:${it.usedMiB}" }
        // `elapsed` 是各段 `t=` 的**最后一段**（也就是装载链走完的累计毫秒），
        // 与 `series` 并列：一条 logcat 同时给出「花在哪段（内存）」与「一共花了多久（时间）」。
        val elapsed = snap.lastOrNull()?.atMs ?: 0L
        val times = snap.joinToString(",") { "${it.stage}:${it.atMs}" }
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
     * 「峰值是多少、峰值段在哪、堆上限多少」。
     *
     * 2026-10-30 追加「OOM 时」读数：[peakHeap] 的阶段是**采样点上**堆最高的那一段，
     * 而采样点只落在段边界 —— 2026-10-30 真机失败屏显示「峰值 167 MiB / 上限 512 MiB /
     * 卡在 program 引用范围校验 2/8」却报了堆不足，峰值读数与分类自相矛盾：真正的 OOM
     * 落在最后一次采样**之后**的盲区（INTERN / meta / session 装配），峰值行停在了解码
     * 中段的旧读数上。宿主在 catch 里先打一枚 [FAIL_STAGE] 采样（见 [mark]），
     * 这里把它追加到句尾 —— 「OOM 时已用」远低于上限就说明不是 Java 堆容量问题
     * （native / 线程 / 直接缓冲），贴近上限才是真装载峰值超限。
     */
    fun screenSummary(): String {
        val snap = snapshot()
        val heap = snap.maxByOrNull { it.usedMiB } ?: return "未采到峰值（装载在第一次采样前中止）"
        val fail = snap.lastOrNull { it.stage == FAIL_STAGE }
        val failPart = if (fail != null) {
            " / OOM时已用 ${fail.usedMiB} MiB（native ${fail.nativeMiB}）"
        } else {
            ""
        }
        return "峰值 ${heap.usedMiB} MiB / native ${snap.maxByOrNull { it.nativeMiB }?.nativeMiB ?: 0} MiB / " +
            "堆上限 ${heap.maxMiB} MiB / 峰值段 ${heap.stage}$failPart"
    }

    /**
     * DevTools `loadtrace` 用：逐段一行（段名 + 已用堆 + 距起点毫秒）。
     *
     * 真机 vivo（Origin OS）会把应用 logcat 整条丢掉，`load.*` 那批 `Log.i` 拿不到；
     * 但采样表始终在内存里，所以调试通道能拿到与 logcat 完全同口径的装载分段读数。
     */
    @Synchronized
    fun summaryLines(): List<String> = samples.toList().map { s ->
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

    companion object {
        private const val MIB = 1024L * 1024L

        /** 预分配的采样槽：start / gc.preflight / preflight / program / gc.meta / meta / gc.session / session / ready。 */
        private const val EXPECTED_STAGES = 12

        /**
         * 失败点采样段名（2026-10-30）：宿主 catch 到装载异常时**第一件事**打一枚
         * `mark(FAIL_STAGE)`，把 OOM 当口的堆/native 读数钉在采样表里 —— 它是
         * [screenSummary] 的「OOM时已用」读数来源，也是区分「Java 堆容量不足」与
         * 「native 侧分配失败被归并成堆不足」的决定性读数。
         */
        internal const val FAIL_STAGE = "fail"

        /** 低于这个堆占用就不值得显式 GC（刚进装载函数时的常态是几 MiB）。 */
        const val GC_MIN_USED_MIB = 24L

        /**
         * `gcHint` 的触发比例（已用堆 / 堆上限）。2026-10-24 起取代「24 MiB 就收」：
         * 512 MiB 上限 ⇒ 281 MiB。只跳掉「收不到东西的背靠背 GC」，不是取消交界回收。
         *
         * 这是**保守档**的读数；放开档（Android 8+ 且堆 >= 512 MiB，判式见
         * `core/host/MemoryTier.kt`）读 [GC_TRIGGER_PERCENT_GENEROUS]。两者都由
         * [gcTriggerPercent] 现取，调用侧不许各自写死一个百分比。
         */
        const val GC_TRIGGER_PERCENT = 55L

        /**
         * 放开档的 `gcHint` 触发比例：**65%**（512 MiB 挡 ⇒ 332 MiB，比保守档晚 51 MiB 才收）。
         *
         * 为什么这一族机型可以晚点收：55% 那条阈值的全部代价是**白付一次全量 GC**
         * （真机 2026-10-24 实测「173 MiB 什么都收不到却付 443 ms」），收益是别让垃圾堆到
         * 顶穿挡位；而放开档的两条前提是这台机给到 >= 512 MiB 的堆**且** API >= 26
         * （两条各钉什么、版本线上尚未一手取证的那一点，见 `core/host/MemoryTier.kt` 的类头）。
         * 装载链交界那两次（`meta` / `session`）
         * 照旧**恒收**、不看这条线（[LoadGcPolicy.collectAtSeam]），所以本刀动的只有解码期那
         * 十几处重复交界。
         *
         * 运行期的两档回收水位（80% / 92% / 95% 恐慌档，见 RunWatch.kt 的 RunTrail）**一条都没抬**：
         * 那是「已经不贴顶就活不下去」那一族判据，抬它等于抬闪退概率。
         */
        const val GC_TRIGGER_PERCENT_GENEROUS = 65L

        /**
         * 本档的 `gcHint` 阈值：放开档读 [GC_TRIGGER_PERCENT_GENEROUS]，其余读
         * [GC_TRIGGER_PERCENT]（没落性质的桌面装置与单测 ⇒ 一律保守档，与加本刀前逐字相同）。
         *
         * `maxMiB` 必须就是调用侧拿来算阈值的那同一个堆读数（缺省 = 本进程现取），
         * 否则会出现「按注入的挡位判不足、按真实挡位判放开」两张皮；显式传它只为定向单测
         * 在不自控测试 JVM 挡位的前提下复刻两档，生产不传。
         */
        fun gcTriggerPercent(maxMiB: Long = Runtime.getRuntime().maxMemory() / MIB): Long =
            if (MemoryTier.isGenerous(maxMiB * MIB)) {
                GC_TRIGGER_PERCENT_GENEROUS
            } else {
                GC_TRIGGER_PERCENT
            }
    }
}

/**
 * 装载链上「这一刻该不该付一次全量 GC」的判据（纯函数，JVM 单测直接钉）。
 *
 * ## 为什么要拆出来
 *
 * 原写法把判据埋在 [LoadTrace.gcHint] 里，而它管的两类交界**根本不是一回事**：
 * 解码期那几处一次装载会走十几遍（收错一次就白付十几遍），装载链的 `meta` / `session`
 * 交界一次装载只走两遍。同一个 55% 阈值套在两者身上，2026-10-30 的玩家失败屏就撞出来了：
 *
 * - 两张屏的实测读数分别是 **267 MiB** 与 **265 MiB**（堆上限 512 MiB），
 *   而 512 MiB 挡的阈值 = `512 × 55%` = **281 MiB** —— 差 14 MiB，`meta` 前与 `session`
 *   前那两次交界回收**一次都没付**。
 * - 同一份产物在桌面同挡位实测（`:core-vm:deviceTierProbe`，`-Xmx512m`、设备口径
 *   `flatStatements = false`、meta 并行）：`session` 交界无条件收一次把 146 MiB 收到
 *   **73 MiB**（白捡 73 MiB 余量），而这两次交界后面紧跟的是本链最大的两笔分配
 *   —— 侧车解析 ≈170 MiB、会话装配 ≈128 MiB。
 * - 也就是说：**贴边的机型恰恰是「读数还没到阈值」的那一批**，阈值把它们全部放过去，
 *   然后让它们在 meta/session 的分配里顶穿 512 MiB。屏上留下的最后一枚采样正是
 *   「池重灌与下标保真」这一句。
 *
 * ## 两条规则
 *
 * - [collectIntraDecode]：解码期（重复走）—— 保留 55% 阈值，2026-10-24 那笔「173 MiB
 *   什么都收不到却付 443 ms」的教训仍然成立。
 * - [collectAtSeam]：装载链交界（一次装载只走一两次）—— **恒收**。这里的成本上限是
 *   两次全量 GC（真机实测各 ≈0.1–0.6 s），换来的是本链最大两笔分配之前的全部余量。
 *   兼容装载档早就是这条口径（[LoadTrace.forceGc] 逢段必收），本次只是把**交界**这两处
 *   提到缺省档。
 *
 * 两个入参都收进来（含恒收那条现在用不上的 [collectAtSeam]），是为了让单测能把
 * 「阈值被谁重新套回交界」这一种改法当场判红。
 */
internal object LoadGcPolicy {

    /** 解码期重复交界：已用堆达到阈值才收（阈值口径见 [LoadTrace.gcTriggerPercent]，两档读数见 [LoadTrace.GC_TRIGGER_PERCENT] 与 [LoadTrace.GC_TRIGGER_PERCENT_GENEROUS]）。档位判式用的就是传进来的这同一个 `maxMiB`，不另读一次堆。 */
    fun collectIntraDecode(usedMiB: Long, maxMiB: Long): Boolean =
        usedMiB >= maxOf(LoadTrace.GC_MIN_USED_MIB, maxMiB * LoadTrace.gcTriggerPercent(maxMiB) / 100L)

    /** 装载链交界（`meta` 前 / `session` 前）：不问读数恒收 —— 一次装载只付两次。 */
    fun collectAtSeam(usedMiB: Long, maxMiB: Long): Boolean = true
}
