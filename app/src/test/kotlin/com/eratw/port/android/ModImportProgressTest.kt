package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 进度模型的**纯函数闸**（不碰 UI、不碰 IO）。
 *
 * 为什么值得单独钉：进度条是玩家判断「还要等多久」的唯一依据，错了就是把人骗在那儿干等
 * （走得太快 → 卡在 99%；走得太慢 → 以为卡死）。这里把三件事锁死：
 * ① 权重表必须合计 100（加阶段忘改分母 ⇒ 永远走不满）；
 * ② 整链推进的 overall 必须**单调不减**（相位切换不许回退）；
 * ③ 收尾必须正好 100%（[ModImportProgressTracker.complete]）。
 */
class ModImportProgressTest {

    @Test
    fun `阶段权重合计为 100`() {
        val sum = ModImportStage.entries.sumOf { it.weight.toDouble() }.toFloat()
        assertEquals(
            "权重合计必须等于 ModImportStage.TOTAL_WEIGHT：加阶段要同步分母，否则进度条走不满",
            ModImportStage.TOTAL_WEIGHT,
            sum,
            0.001f,
        )
    }

    @Test
    fun `整链推进单调不减且收尾正好 100`() {
        val seen = ArrayList<Float>()
        val tracker = ModImportProgressTracker { seen += it.overall }
        tracker.enter(ModImportStage.UNPACK, items = 4)
        repeat(4) { tracker.advance() }
        tracker.enter(ModImportStage.SAVE_SOURCES, items = 3)
        repeat(3) { tracker.advance() }
        tracker.enter(ModImportStage.BASE_CONTEXT)
        tracker.enter(ModImportStage.SYMBOL_TABLE)
        tracker.enter(ModImportStage.COMPILE, items = 2)
        tracker.advanceTo(1)
        tracker.finishStage()
        tracker.enter(ModImportStage.ENCODE)
        tracker.complete()

        assertTrue("至少要发几发读数", seen.size >= 6)
        for (i in 1 until seen.size) {
            assertTrue(
                "overall 不许回退：第 $i 发 ${seen[i]} < 上一发 ${seen[i - 1]}",
                seen[i] >= seen[i - 1],
            )
        }
        assertEquals("收尾必须正好 100%（否则玩家永远看不到满格）", 1f, seen.last(), 0.0001f)
        for (p in seen) {
            assertTrue("overall 越界：$p", p in 0f..1f)
        }
    }

    @Test
    fun `同一阶段同一进度不重复发射`() {
        var count = 0
        val tracker = ModImportProgressTracker { count++ }
        tracker.enter(ModImportStage.COMPILE, items = 10) // 1 发（enter 强制）
        assertEquals(1, count)
        tracker.advanceTo(0) // 同一读数：不重复
        assertEquals("同阶段同进度不许再发（主线程不该被无意义唤醒）", 1, count)
        tracker.advanceTo(5)
        assertEquals(2, count)
        tracker.advanceTo(5)
        assertEquals(2, count)
    }

    @Test
    fun `不可细分阶段直接按本阶段权重走完`() {
        val tracker = ModImportProgressTracker(null) // sink 为 null：整条链零开销，不许抛
        tracker.enter(ModImportStage.BASE_CONTEXT)
        tracker.advance()
        tracker.finishStage()
        tracker.complete()
    }

    @Test
    fun `四段玩家文案三语都非空`() {
        for (copy in listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)) {
            for (display in ModImportStageDisplay.entries) {
                val text = copy.stageText(display)
                assertTrue("${copy.lang} 的阶段文案为空：$display", text.isNotBlank())
            }
        }
    }

    @Test
    fun `百分比取整不越界`() {
        val low = ModImportProgress(ModImportStage.UNPACK, overall = 0f)
        val high = ModImportProgress(ModImportStage.MIGRATE, overall = 1f)
        assertEquals(0, low.percent)
        assertEquals(100, high.percent)
        assertEquals(100, ModImportProgress(ModImportStage.MIGRATE, overall = 1.4f).percent)
        assertEquals(0, ModImportProgress(ModImportStage.UNPACK, overall = -0.2f).percent)
    }

    @Test
    fun `进度事件只在进行中生效`() {
        val progress = ModImportProgress(ModImportStage.COMPILE, overall = 0.4f, done = 3, total = 10)
        val idle = ModImportUiState.Idle(null)
        assertTrue(
            "待机态不该被进度事件改写",
            ModImportUiReducer.reduce(idle, ModImportEvent.ImportProgressChanged(progress)) === idle,
        )
        val importing = ModImportUiState.Importing(sourceCount = 2, stageText = "x", report = null)
        val next = ModImportUiReducer.reduce(importing, ModImportEvent.ImportProgressChanged(progress))
        assertEquals(progress, (next as ModImportUiState.Importing).progress)
        assertEquals("换进度不许丢掉进行中的其它读数", 2, next.sourceCount)
    }
}
