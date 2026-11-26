package com.eratw.port.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 逐帧诊断包（[FrameTrace]）的纯函数闸 —— 2026-11-23「地图疯狂跳动」专项的真机兜底面。
 *
 * 断言三件事（每件都对应一条真机现场会用到的判据）：
 * 1. **环形缓冲**：容量满后覆盖最旧、`samples(n)` 尾部对齐取最近 n 帧；
 * 2. **判定口径**：版面量（行高/视口行数/可见基准/行表总高）相邻帧有变 ⇒ 判「视图层在动」，
 *    全稳 ⇒ 判「canvas 贴图/硬件加速层」——这两句话是现场唯一的区分手段，
 *    口径被改错就会把真机现场引到错的那一层，所以这里钉死；
 * 3. **默认关的形态**：空表读出来必须是「空」而不是抛，也不许伪造样本。
 *
 * 反向证明：把 [FrameTrace.verdict] 里任一版面量从比对里删掉，
 * [jitterIsJudgedAsViewLayer] 立刻红（那一帧只改 `lineHeight`）。
 */
class FrameTraceTest {

    /**
     * 造一帧：只让调用方指定的那几个量变化，其余固定。
     *
     * `planHeight` 不开放成参数（恒 = `heightLines * lineHeight` 的形状）：它在真机上总是
     * 行高×行数的派生量，放开反而让用例写出「行高没变、总高变了」这种真机上不可能的组合。
     */
    private fun frame(
        no: Long,
        lineHeight: Float = 40f,
        heightLines: Int = 24,
        visibleRowBase: Int = 100,
        offsetFromEnd: Int = 0,
    ): FrameSample = FrameSample(
        frameNo = no,
        revision = no * 2,
        rows = 24,
        visibleRowBase = visibleRowBase,
        hitRowBase = visibleRowBase,
        offsetFromEnd = offsetFromEnd,
        lineHeight = lineHeight,
        viewHeight = 960,
        rowsVisible = 24,
        rebaked = false,
        awaitingAnyKey = false,
        heightLines = heightLines,
        fontPx = 32f,
        rowCount = 24,
        planHeight = heightLines * lineHeight,
        artAdvance = lineHeight,
        artRows = 0,
    )

    @Test
    fun ringKeepsLastFrames() {
        val trace = FrameTrace(capacity = 4)
        for (i in 1L..6L) trace.record(frame(i))
        assertEquals(4, trace.size, "容量 4 装 6 帧后应只剩最近 4 帧")
        val kept = trace.samples(4)
        assertEquals(listOf(3L, 4L, 5L, 6L), kept.map { it.frameNo }, "最旧的两帧应被覆盖")
        // limit 尾部对齐：只要最近 2 帧
        assertEquals(listOf(5L, 6L), trace.samples(2).map { it.frameNo })
    }

    @Test
    fun clearDropsEverything() {
        val trace = FrameTrace(capacity = 4)
        trace.record(frame(1))
        trace.clear()
        assertEquals(0, trace.size)
        assertTrue(trace.dump().startsWith("frameTrace: 空"), "清空后 dump 必须说「空」，不许伪造样本")
    }

    @Test
    fun jitterIsJudgedAsViewLayer() {
        val trace = FrameTrace(capacity = 8)
        trace.record(frame(1, lineHeight = 40f))
        trace.record(frame(2, lineHeight = 41f))
        val text = trace.dump()
        assertTrue(text.contains("版面量在跳"), "行高相邻帧变了却没判成「视图层在动」：\n$text")
        assertTrue(text.contains("视图层在动"), "判定句子必须落到「视图层」，否则现场会被引到 canvas 层")
    }

    @Test
    fun heightLinesJitterAlsoCounts() {
        val trace = FrameTrace(capacity = 8)
        trace.record(frame(1, heightLines = 24))
        trace.record(frame(2, heightLines = 23))
        assertTrue(trace.dump().contains("版面量在跳"), "视口行数 24→23 是 lineHeight 抖的另一面，必须算版面抖动")
    }

    @Test
    fun visibleRowBaseJitterAlsoCounts() {
        val trace = FrameTrace(capacity = 8)
        trace.record(frame(1, visibleRowBase = 100))
        trace.record(frame(2, visibleRowBase = 104))
        assertTrue(trace.dump().contains("版面量在跳"), "可见基准 100→104 = 玩家眼里整屏上移，必须算版面抖动")
    }

    @Test
    fun stableLayoutPointsAtCanvasLayer() {
        val trace = FrameTrace(capacity = 8)
        // 内容每帧都换（revision / rows 变），但版面量一律不动 —— 正是「文字逐字相同、屏在动」的对照组。
        trace.record(frame(1).copy(rebaked = true))
        trace.record(frame(2).copy(rebaked = true))
        val text = trace.dump()
        assertTrue(text.contains("版面量全稳"), "版面量没变却判成抖动：\n$text")
        assertTrue(text.contains("canvas 贴图/硬件加速层"), "全稳时应把现场指向 canvas 层")
        assertTrue(text.contains("rebakeFrames=2/2"), "重烤计数是 canvas 层的判据，必须出现在判定行：\n$text")
    }

    @Test
    fun scrollIsNotCallerAsJitter() {
        val trace = FrameTrace(capacity = 8)
        trace.record(frame(1, offsetFromEnd = 0))
        trace.record(frame(2, offsetFromEnd = 3))
        val text = trace.dump()
        assertTrue(text.contains("offsetFromEnd跳动=Y"), "滚动必须与「跳」区分开：\n$text")
        assertTrue(text.contains("版面量全稳"), "只是滚了 3 行，版面量没变，不该判成视图层抖动")
    }

    @Test
    fun singleFrameCannotJudge() {
        val trace = FrameTrace(capacity = 4)
        trace.record(frame(1))
        assertTrue(trace.dump().contains("帧数不足"), "只有 1 帧时不许给判定结论")
    }

    @Test
    fun summaryIsBoundedButStillCarriesVerdict() {
        val trace = FrameTrace(capacity = 240)
        for (i in 1L..240L) trace.record(frame(i, lineHeight = if (i % 2 == 0L) 41f else 40f))
        val lines = trace.summaryLines(FrameTrace.REPORT_FRAME_ROWS)
        // 上界：摘要 1 + 表头 1 + 明细 N + 判定 1。报告是「补充现场」共享尾窗（80 行），
        // 摘要必须远小于它，否则会把内存读数/心跳/冻结现场挤出去。
        assertTrue(
            lines.size <= FrameTrace.SUMMARY_FIXED_LINES + FrameTrace.REPORT_FRAME_ROWS,
            "报告形态不能超过 SUMMARY_FIXED_LINES+REPORT_FRAME_ROWS 行，实得 ${lines.size} 行",
        )
        assertTrue(lines.first().contains("frames=240"), "首行必须是全环摘要（含帧数）：${lines.first()}")
        assertTrue(lines.first().contains("lineH="), "摘要要带各版面量的取值集合：${lines.first()}")
        assertTrue(lines.first().contains("40.0|41.0"), "行高两档必须都列出来：${lines.first()}")
        assertTrue(lines.last().startsWith("verdict:"), "末行必须是判定行：${lines.last()}")
        assertTrue(lines.last().contains("版面量在跳"), "40↔41 交替必须判成版面抖动：${lines.last()}")
    }

    @Test
    fun summaryOfEmptyRingSaysEmpty() {
        val trace = FrameTrace(capacity = 4)
        assertEquals(listOf(FrameTrace.EMPTY_TEXT), trace.summaryLines(20))
    }

    @Test
    fun dumpListsEveryColumn() {
        val trace = FrameTrace(capacity = 4)
        trace.record(frame(1))
        val text = trace.dump()
        for (col in listOf(
            "frameNo", "rev", "rows", "vBase", "hBase", "offEnd", "lineH", "viewH",
            "rowsVis", "hLines", "font", "rowCount", "planH", "artAdv", "artRows",
            "rebake", "anyKey",
        )) {
            assertTrue(text.contains(col), "报告缺列 $col：\n$text")
        }
        assertTrue(text.contains(FrameTrace.VERDICT_HINT), "判据摘要必须随表头带出，现场才不用另翻代码")
    }
}
