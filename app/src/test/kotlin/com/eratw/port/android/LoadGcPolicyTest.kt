package com.eratw.port.android

import com.eratw.port.core.host.MemoryTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoadGcPolicy] —— 2026-10-30 两张玩家失败屏（「峰值 267 / 265 MiB，堆上限 512 MiB」）的修复契约。
 *
 * ## 现象与根因
 *
 * 装载链上的交界回收原先只有一条判据（已用堆 ≥ 上限的 55%），而这条阈值套在两类完全不同的
 * 交界上：解码期那几处一次装载会走十几遍，`meta` / `session` 交界一次装载只走两遍。
 * 512 MiB 挡的阈值 = 281 MiB，玩家两张屏的最后一枚采样是 267 与 265 MiB —— **差 14 MiB，
 * 那两次交界回收一次都没付**，紧接着本链最大的两笔分配（侧车解析、会话装配）就把堆顶穿。
 *
 * ## 桌面同挡位实测（装置 `:core-vm:deviceTierProbe`，`-Xmx512m` + 设备口径装配）
 *
 * - `session` 交界无条件收一次：146 MiB → **73 MiB**（白捡 73 MiB 余量）。
 * - 整条装载链（含并行的 `meta` 解析）段内池峰值最高 227 MiB，挡位 512 MiB 走完不 OOM
 *   ⇒ 「装载本身要 350–420 MiB」那句旧文案确实已经不成立（`BootFailures.HEAP_SHORTAGE_TEXT`
 *   同日已去掉数字）。
 *
 * ## 判据（逐条锁死）
 *
 * ① 装载链交界恒收，**与读数无关** —— 本用例的 267/512 就是玩家现场那组数，把它按阈值判
 * 一次就会漏收；② 解码期仍按 55% 阈值（2026-10-24 那笔「173 MiB 什么都收不到却付 443 ms」
 * 的教训不撤销）；③ 阈值算式本身钉死在 281 MiB，谁改百分比要连着看这条读数。
 */
class LoadGcPolicyTest {

    @Test
    fun `装载链交界恒收，玩家现场那组 267 与 265 也不例外`() {
        // 这两组数是从两张 release 失败屏上原样抄下来的（堆上限 512 MiB）。
        assertTrue(
            "267 < 阈值 281 却不该漏收：这一次装载只走两遍的交界，后面紧跟本链最大一笔分配",
            LoadGcPolicy.collectAtSeam(usedMiB = 267L, maxMiB = 512L),
        )
        assertTrue(LoadGcPolicy.collectAtSeam(usedMiB = 265L, maxMiB = 512L))
        // 恒收 = 不看读数：连「刚进装载函数只有几 MiB」也照收（真机成本一次 GC 的零头）。
        assertTrue(LoadGcPolicy.collectAtSeam(usedMiB = 8L, maxMiB = 512L))
        assertTrue(LoadGcPolicy.collectAtSeam(usedMiB = 500L, maxMiB = 512L))
    }

    @Test
    fun `交界恒收不许被重新套上阈值（断点证伪）`() {
        // 把 collectAtSeam 改回「同 gcHint 一个阈值」时，这一条必须红：
        // 用的正是玩家现场那组数（267 / 512 ⇒ 阈值 281 ⇒ 漏收）。
        val seam = LoadGcPolicy.collectAtSeam(usedMiB = 267L, maxMiB = 512L)
        val intraDecode = LoadGcPolicy.collectIntraDecode(usedMiB = 267L, maxMiB = 512L)
        assertTrue(seam)
        assertFalse("同一组读数下解码期确实不收 —— 两条规则必须能分开，否则本用例判不出东西", intraDecode)
    }

    @Test
    fun `解码期阈值算式钉在 512 挡的 281 MiB`() {
        val max = 512L
        val trigger = max * LoadTrace.GC_TRIGGER_PERCENT / 100L
        assertTrue("阈值读数本身：512 × 55% = 281", trigger == 281L)
        assertFalse(LoadGcPolicy.collectIntraDecode(usedMiB = 280L, maxMiB = max))
        assertTrue(LoadGcPolicy.collectIntraDecode(usedMiB = 281L, maxMiB = max))
    }

    @Test
    fun `小堆机型解码期仍被最低水位兜住`() {
        // 256 MiB 挡：55% = 140 MiB，仍高于 GC_MIN_USED_MIB(24) ⇒ 阈值说话。
        assertFalse(LoadGcPolicy.collectIntraDecode(usedMiB = 100L, maxMiB = 256L))
        assertTrue(LoadGcPolicy.collectIntraDecode(usedMiB = 141L, maxMiB = 256L))
        // 上限小到阈值跌破最低水位时，按最低水位判（刚进装载函数收一次纯属浪费启动时间）。
        assertFalse(LoadGcPolicy.collectIntraDecode(usedMiB = 20L, maxMiB = 24L))
        assertTrue(LoadGcPolicy.collectIntraDecode(usedMiB = 24L, maxMiB = 24L))
    }

    /**
     * 档位分档（2026-11-03）：放开档把解码期那条线从 55% 抬到 65%，保守档一个数都不变。
     *
     * 阈值抬高的全部代价是**晚一点才收**，收益是少付几次「什么都收不到」的全量 GC
     *（判式两个条件见 `core/host/MemoryTier.kt`）。交界恒收那条（[LoadGcPolicy.collectAtSeam]）
     * 与本用例无关，它照旧不看任何读数。
     *
     * 走 [LoadGcPolicy.collectIntraDecode] 这个**生产入口**、挡位当参数传进去：把
     * `gcTriggerPercent` 里的档位查询摘掉，300/512 那一发就必须还是 true（本仓最怕
     * 「闸改了调用口没接」）。断言不挂测试 JVM 自己的挡位。
     */
    @Test
    fun `解码期阈值分两档而缺省仍读保守档`() {
        assertEquals(55L, LoadTrace.GC_TRIGGER_PERCENT)
        assertEquals(65L, LoadTrace.GC_TRIGGER_PERCENT_GENEROUS)
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            assertEquals(55L, LoadTrace.gcTriggerPercent(512L))
            // 保守档：线在 281 ⇒ 300 越线，收。
            assertTrue(LoadGcPolicy.collectIntraDecode(usedMiB = 300L, maxMiB = 512L))
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            assertEquals(65L, LoadTrace.gcTriggerPercent(512L))
            // 放开档：线抬到 332 ⇒ 同一组读数不收。**这一发就是接线探针**：把档位查询从
            // gcTriggerPercent 里摘掉，它会是 true 而不是 false。
            assertFalse(LoadGcPolicy.collectIntraDecode(usedMiB = 300L, maxMiB = 512L))
            // 贴住 332 那一发仍然收（判据是 >=，不是 >）。
            assertTrue(LoadGcPolicy.collectIntraDecode(usedMiB = 332L, maxMiB = 512L))
            // 版本线够但挡位不够（256 MiB ⇒ 线 140）⇒ 仍旧按保守档那条线走。
            assertEquals(55L, LoadTrace.gcTriggerPercent(256L))
            // Android 7.x 那一族（版本线以下）：同一台 512 MiB 的机子仍按保守档收。
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "25")
            assertTrue("Android 7.x 那一族仍按 55% 收", LoadGcPolicy.collectIntraDecode(300L, 512L))
            // 512 挡两条线的读数：281 与 332（晚 51 MiB 才收）。
            assertEquals(281L, 512L * LoadTrace.GC_TRIGGER_PERCENT / 100L)
            assertEquals(332L, 512L * LoadTrace.GC_TRIGGER_PERCENT_GENEROUS / 100L)
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
        // 缺省 = 本进程现取。单测不落性质 ⇒ 必须还是保守档那一条，
        // 上面 281/140 那些既有断言才不是靠运气绿的。
        assertEquals(LoadTrace.GC_TRIGGER_PERCENT, LoadTrace.gcTriggerPercent())
    }
}
