package com.eratw.port.android

import java.util.Arrays

/**
 * 翻页耗时的定长窗口分位累加器（纯 JVM，无 Android 依赖；obs-turnpct 观察性波）。
 *
 * ## 为什么要有它
 *
 * `turn Nms` 读数（口径 = 提交输入 → 脚本到达下一个等待点的墙钟毫秒）此前只有逐页原始值，
 * 散在 [GameHost] 的 turnLog、`turn.next ms=` 日志与 `turn.latency ms=` 三处。
 * 逐条 ms 回答不了「这一局整体变慢了没有」——取证页里 `2ms → 2342ms → 15192ms → 58515ms`
 * 这种尾部劣化，平均值会被大量快页稀释，**要看的是 P95/P99 与 max**。本类把「最近一段」
 * 的翻页耗时收进定长环形窗口，读取时排序取位，给出一条可直接上屏的分位摘要。
 *
 * ## 窗口容量：为什么是 512、为什么必须定长
 *
 * 本仓的 OOM 账就是从「没人记账的常驻累加」开始的（见 510MiB/512MiB 贴顶那批取证）。
 * 所以这里**不存全量历史**：报告与 DevTools 都只看「最近一段」，长跑里累加器的堆占用
 * 恒定不增长。容量取 512：
 * - turnLog 的 64 条是「逐页给人看最近几页」的口；分位要回答的是「这一局慢没慢」，
 *   64 条太短——最朴素的分位判据 n≥20 才让 p95 有别于 max，再给约 25 倍余量让窗口
 *   跨过一段完整的「快页/慢页混合」；
 * - 上限成本 = 512 × 8 字节 = 4 KiB 常驻（`LongArray`，无装箱、无节点对象），
 *   读取期临时排序数组同样 ≤ 4 KiB 且即用即弃——对 512 MiB 的堆账可以忽略。
 *
 * ## 取值口径（钉死，对拍用）
 *
 * - **方法 = 最近秩（nearest-rank）**：把窗口内 n 个值升序排，q 分位取第
 *   `ceil(q × n / 100)` 小（1 起算；整数算式 `(q100 × n + 99) / 100`，不用浮点乘法，
 *   避免 `0.95 × 20` 这类浮点毛刺把秩算进下一档）。这是**窗口内精确分位**，不是
 *   t-digest 之类的估计算法，因此没有估计误差；唯一的误差来源是窗口截断本身
 *   ——更老的页不进读数，这正是定长的设计意图。也**不引入任何第三方依赖**。
 * - **偶数不插值**：n 为偶数时 p50 取偏低的那一半（下中位数），如 n=20 → 第 10 小；
 *   不做「取中间两数平均」，读数可逐值手算复核。
 * - **n < 20 时的 p95**：`ceil(0.95n) = n`（n ≤ 19），即 **p95 恒等于 max**。
 *   这是最近秩的定义使然，不是 bug；窗口没攒到 20 条时别把 p95 读成「95% 的页都比它快」。
 * - **n = 0**：[p50]/[p95]/[max]/[percentile] 一律返回 `0L`（Long 语义下本就没有 NaN，
 *   也不抛异常）；[summaryLine] 输出显式的「无读数」文案。
 * - **n = 1**：三个分位全等于那一条读数本身。
 * - 负数读数（理论不该出现，`QueueInputSink` 只在 `ms ≥ 0` 时交付；防御时钟回摆）折到 0。
 *
 * ## 线程
 *
 * [record] 由 VM 线程在 `onTurnReady` 回调里调；读口由 DevTools daemon 线程调
 * （口径同 [GameHost] 的 turnLog 锁）。全部公开方法同步在同一把锁上，回调侧成本
 * 只是一次数组写入——本回调跑在 VM 线程上，不许塞任何 IO。
 */
class TurnPercentiles(val capacity: Int = CAPACITY) {

    init {
        require(capacity >= 1) { "capacity 必须 ≥ 1，实际 $capacity" }
    }

    private val lock = Any()
    private val ring = LongArray(capacity)
    /** 写游标：下一条 record 落在 `ring[nextWrite]`（满窗时顶掉最老一条）。 */
    private var nextWrite = 0
    /** 窗口内有效条数（封顶 capacity）。 */
    private var filled = 0

    /** 记一条翻页耗时（毫秒，非负；负值折到 0）。 */
    fun record(ms: Long) {
        val v = if (ms < 0L) 0L else ms
        synchronized(lock) {
            ring[nextWrite] = v
            nextWrite = (nextWrite + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** 窗口内当前条数（≤ capacity；不是历史总条数——历史总数无意义且没人记账）。 */
    fun count(): Int = synchronized(lock) { filled }

    /** 最近 n 条原始读数，旧→新顺序；不足 n 给全部，n ≤ 0 给空。 */
    fun recent(n: Int): List<Long> = synchronized(lock) {
        val k = minOf(n, filled)
        if (k <= 0) {
            emptyList()
        } else {
            // 最新一条在 (nextWrite - 1) mod capacity；旧→新即从 (nextWrite - k) 起顺着读。
            (0 until k).map { i ->
                val idx = ((nextWrite - k + i) % capacity + capacity) % capacity
                ring[idx]
            }
        }
    }

    /**
     * q 分位读数（最近秩法）。[times100] = 百分位 × 100 的整数口径：p50 传 50、p95 传 95、
     * p99 传 99、max 传 100。取值范围 1..100，越界直接 [require] 拦下——传 0 会让秩退化成
     * 「第 0 小」这种不存在的位置。窗口为空返回 0（见类 KDoc 的 n=0 口径）。
     */
    fun percentile(times100: Int): Long {
        require(times100 in 1..100) { "times100 必须在 1..100，实际 $times100" }
        return synchronized(lock) {
            if (filled == 0) {
                0L
            } else {
                val sorted = snapshotSorted()
                // 最近秩：rank = ceil(times100 × n / 100) = (times100 × n + 99) / 100（整除下取整）。
                val rank = (times100.toLong() * filled + 99L) / 100L
                sorted[(rank - 1L).toInt()]
            }
        }
    }

    fun p50(): Long = percentile(50)

    fun p95(): Long = percentile(95)

    /** 窗口内最大值 = percentile(100)（最近秩下第 n 小即最大值）。 */
    fun max(): Long = percentile(100)

    /**
     * 一行分位摘要，直接可上屏/进 DevTools 输出。
     *
     * 非空：`n=41 p50=8ms p95=15192ms max=58515ms`；
     * 空窗口：`n=0 无翻页读数`（不输出 0ms 假读数，避免被读成「全是快页」）。
     */
    fun summaryLine(): String = synchronized(lock) {
        if (filled == 0) {
            "n=0 无翻页读数"
        } else {
            val sorted = snapshotSorted()
            val n = filled
            val rank50 = ((50L * n + 99L) / 100L - 1L).toInt()
            val rank95 = ((95L * n + 99L) / 100L - 1L).toInt()
            "n=$n p50=${sorted[rank50]}ms p95=${sorted[rank95]}ms max=${sorted[n - 1]}ms"
        }
    }

    /** 窗口内升序快照（调用方须持锁）。仅在读口调用：512 条排序是微秒级，翻页路径零成本。 */
    private fun snapshotSorted(): LongArray {
        val copy = if (filled < capacity) {
            // 未满窗：有效区恰好是 ring[0..filled)。
            ring.copyOf(filled)
        } else {
            // 满窗：从最老一条（下标 nextWrite）起环形抄一圈。
            LongArray(filled) { i -> ring[(nextWrite + i) % capacity] }
        }
        Arrays.sort(copy)
        return copy
    }

    companion object {
        /**
         * 默认窗口容量 = 512 条。取值理由（含「必须定长」的 OOM 教训）见类 KDoc；
         * 常驻上限 4 KiB，长跑不增长。
         */
        const val CAPACITY = 512
    }
}
