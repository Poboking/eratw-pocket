package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TurnPercentiles] 的取值口径钉死用例（obs-turnpct 波）。
 *
 * 断言的就是类 KDoc 里那四条口径：①窗口定长不增长（喂 1000 条只留容量条）；
 * ②最近秩分位对已知数据的手算对拍；③n=0/n=1 与「n<20 时 p95 即 max」的边界行为；
 * ④摘要行的字面格式。全部用整数算式手算期望值，任何人改实现都能按同样算式复核。
 */
class TurnPercentilesTest {

    @Test
    fun `窗口定长 喂 1000 条只留容量条`() {
        val tp = TurnPercentiles()
        // 喂 1000 条单调递增读数 1..1000（模拟长跑翻页）。
        for (ms in 1L..1000L) tp.record(ms)
        assertEquals("喂 1000 条后窗口必须封顶在默认容量，不无限增长（OOM 账的来路）",
            TurnPercentiles.CAPACITY, tp.count())
        // 窗口 = 最后 512 条 = 489..1000；被顶掉的 1..488 不许再进任何读数。
        assertEquals("max 只看窗口内：1000 在窗口内", 1000L, tp.max())
        assertEquals("p50 = 第 ceil(50%×512)=256 小 = 489+256-1 = 744", 744L, tp.p50())
        assertEquals("p95 = 第 ceil(95%×512)=487 小 = 489+487-1 = 975", 975L, tp.p95())
        assertEquals("窗口最老一条是 489（1..488 已被环形顶掉）", 489L, tp.recent(512).first())
        assertEquals("recent 旧→新给最近三条", listOf(998L, 999L, 1000L), tp.recent(3))
    }

    @Test
    fun `最近秩对拍 n=20 已知数据 手算 p50 与 p95 与 max`() {
        val tp = TurnPercentiles(capacity = 64)
        // 1..20 乱序喂入——读数不得依赖到达顺序（乱子由排序吸收）。
        val shuffled = listOf(13, 2, 20, 7, 1, 19, 4, 11, 16, 3, 10, 5, 18, 9, 12, 6, 15, 8, 17, 14)
        shuffled.forEach { tp.record(it.toLong()) }
        assertEquals(20, tp.count())
        // 最近秩、偶数取偏低一半：p50 = 第 (50×20+99)/100 = 10 小 = 10。
        assertEquals("p50 取下中位数第 10 小", 10L, tp.p50())
        // p95 = 第 (95×20+99)/100 = 19 小 = 19（不是 19.05 插值，也不是 max）。
        assertEquals("n=20 起 p95 才区别于 max：第 19 小 = 19", 19L, tp.p95())
        assertEquals("max = percentile(100) = 第 20 小", 20L, tp.max())
        // 取证那组真实读数的口径：OPPO PLD110 连续四页 2 / 2342 / 15192 / 58515ms。
        val oppo = TurnPercentiles(capacity = 64)
        listOf(2L, 2342L, 15192L, 58515L).forEach { oppo.record(it) }
        assertEquals("n=4 时 p95 秩 = ceil(0.95×4)=4 ⇒ 恰为 max（窗口未满 20 的既定行为）",
            58515L, oppo.p95())
        assertEquals("n=4 时 p50 秩 = ceil(0.5×4)=2 ⇒ 第 2 小 = 2342", 2342L, oppo.p50())
        assertEquals(58515L, oppo.max())
    }

    @Test
    fun `边界 n=0 给 0 不抛不 NaN 摘要给无读数文案`() {
        val tp = TurnPercentiles(capacity = 64)
        assertEquals(0, tp.count())
        // Long 返回值本就没有 NaN；口径 = 返回 0 且不得抛异常。
        assertEquals("n=0 时 p50 必须是 0（不许 NaN/异常）", 0L, tp.p50())
        assertEquals("n=0 时 p95 必须是 0", 0L, tp.p95())
        assertEquals("n=0 时 max 必须是 0", 0L, tp.max())
        assertEquals("n=0 时 percentile 同样返回 0", 0L, tp.percentile(99))
        assertEquals(emptyList<Long>(), tp.recent(5))
        assertEquals("n=0 无翻页读数", tp.summaryLine())
    }

    @Test
    fun `边界 n=1 三个分位全等于该读数`() {
        val tp = TurnPercentiles(capacity = 64)
        tp.record(42L)
        assertEquals(1, tp.count())
        assertEquals(42L, tp.p50())
        assertEquals("n=1 < 20 ⇒ p95 即 max 即该值", 42L, tp.p95())
        assertEquals(42L, tp.max())
        assertEquals(listOf(42L), tp.recent(10))
        assertEquals("n=1 p50=42ms p95=42ms max=42ms", tp.summaryLine())
    }

    @Test
    fun `环形顶旧 满窗后最老读数退出 max 与 recent`() {
        val tp = TurnPercentiles(capacity = 4)
        listOf(1L, 2L, 3L, 4L, 100L).forEach { tp.record(it) }
        assertEquals("满窗封顶", 4, tp.count())
        assertEquals("1 已被 100 顶出窗口", listOf(2L, 3L, 4L, 100L), tp.recent(4))
        // 窗口 = 2,3,4,100：p50 秩 = (50×4+99)/100 = 2 ⇒ 第 2 小 = 3。
        assertEquals(3L, tp.p50())
        assertEquals(100L, tp.max())
    }

    @Test
    fun `摘要行格式 与报告口径逐字对齐`() {
        val tp = TurnPercentiles(capacity = 64)
        // 复刻取证场景的形状：41 页，其中一页 58515ms 劣化尾巴。
        repeat(40) { tp.record(8L) }
        tp.record(58515L)
        // n=41：p50 秩 = ceil(20.5)=21 ⇒ 第 21 小 = 8；p95 秩 = ceil(38.95)=39 ⇒ 第 39 小 = 8；
        // 尾巴读数把 max 拉爆——这正是「看 P95/max 而不是平均值」要暴露的东西。
        assertEquals("n=41 p50=8ms p95=8ms max=58515ms", tp.summaryLine())
        assertEquals(58515L, tp.max())
        // recent 的 n 非正给空。
        assertEquals(emptyList<Long>(), tp.recent(0))
        assertEquals(emptyList<Long>(), tp.recent(-3))
        // 负读数折到 0（防御时钟回摆），不得污染 max。
        tp.record(-7L)
        assertEquals(58515L, tp.max())
        assertTrue("分位参数越界要当场拦下，不许悄悄给第 0 小", runCatching { tp.percentile(0) }.isFailure)
        assertTrue("times100 上限 100", runCatching { tp.percentile(101) }.isFailure)
        assertTrue("capacity 至少 1", runCatching { TurnPercentiles(capacity = 0) }.isFailure)
    }
}
