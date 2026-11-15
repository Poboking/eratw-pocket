package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoadPhaseProgress] 的定向用例（CONTRACT-BOOTPHASE）。
 *
 * 钉的是「进度必须来自真实阶段名序列」这件事本身：
 *
 * - 交界采样（`gc.` / `boot.`）与未知阶段不许凭空报进度、也不许把已到的档位拨回去；
 * - 解码细段逐段前进但有上限，不许在细段还没走完时越过 `program` 完成点；
 * - 里程碑按序推进、比例全程单调；
 * - [LoadTrace.currentProgress] 直接用真采样表折算，不是另起一套估算。
 *
 * 本用例不测耗时、不测真机百分比：比例只表达相对推进（TODO(verify) 见
 * `LoadPhaseProgress.kt` KDoc），无 C# 锚（移植侧自研安卓装载壳）。
 */
class LoadPhaseProgressTest {

    @Test
    fun `空阶段序列不虚报进度`() {
        assertNull(LoadPhaseProgress.of(emptyList()))
    }

    @Test
    fun `只有交界采样时不虚报进度`() {
        assertNull(LoadPhaseProgress.of(listOf("gc.preflight", "boot.首帧后", "GameSession.某内部段")))
    }

    @Test
    fun `start 立刻落到 PROGRAM 与起始比例`() {
        val p = LoadPhaseProgress.of(listOf("start"))!!
        assertEquals(LoadPhase.PROGRAM, p.phase)
        assertEquals(0.02f, p.fraction, 0.0001f)
    }

    @Test
    fun `解码细段逐段递增且压在 program 完成点之下`() {
        val stages = (1..30).map { "program.段$it" }
        val first = LoadPhaseProgress.of(listOf("start", stages.first()))!!.fraction
        val all = LoadPhaseProgress.of(listOf("start") + stages)!!
        assertEquals(LoadPhase.PROGRAM, all.phase)
        assertTrue("细段应让比例前进：first=$first all=${all.fraction}", all.fraction > first)
        assertTrue("细段比例不得超过解码上限 0.55：${all.fraction}", all.fraction <= 0.55f)
    }

    @Test
    fun `里程碑按序推进且比例全程单调`() {
        val stages = listOf("start", "program.读容器头", "program", "meta", "session", "ready")
        val p = LoadPhaseProgress.of(stages)!!
        assertEquals(LoadPhase.STARTING, p.phase)
        assertEquals(0.94f, p.fraction, 0.0001f)
        var last = 0f
        stages.forEachIndexed { i, _ ->
            val cur = LoadPhaseProgress.of(stages.take(i + 1))!!.fraction
            assertTrue("前缀 ${i + 1} 比例倒退：last=$last cur=$cur", cur >= last)
            last = cur
        }
    }

    @Test
    fun `交界采样与未知阶段不倒退档位与比例`() {
        val p = LoadPhaseProgress.of(
            listOf("start", "program.读容器头", "program", "meta", "gc.session", "GameSession.某内部段", "boot.首帧后"),
        )!!
        assertEquals(LoadPhase.META, p.phase)
        assertEquals(0.72f, p.fraction, 0.0001f)
    }

    @Test
    fun `阶段乱序也不把档位与进度拨回去`() {
        val p = LoadPhaseProgress.of(listOf("start", "meta", "session", "program.迟到的细段"))!!
        assertEquals(LoadPhase.SESSION, p.phase)
        assertTrue("迟到的细段不该把 0.86 拉回解码段：${p.fraction}", p.fraction >= 0.86f)
    }

    @Test
    fun `program 完成点严格高于细段上限`() {
        val decode = LoadPhaseProgress.of(listOf("start") + (1..60).map { "program.段$it" })!!
        val done = LoadPhaseProgress.of(listOf("start") + (1..60).map { "program.段$it" } + listOf("program"))!!
        assertTrue("细段应先停在 0.55：${decode.fraction}", decode.fraction < 0.58f)
        assertTrue("program 完成点必须继续前进：${done.fraction}", done.fraction > decode.fraction)
    }

    @Test
    fun `LoadTrace 直接折算真采样表`() {
        val trace = LoadTrace(tag = "LoadPhaseProgressTest", logEnabled = false)
        assertNull("构造后未采样不应有进度", trace.currentProgress())
        trace.mark("start")
        assertEquals(LoadPhase.PROGRAM, trace.currentProgress()!!.phase)
        trace.mark("gc.decode")
        trace.mark("program.读容器头")
        assertTrue(trace.currentProgress()!!.fraction > 0.02f)
        trace.mark("program")
        trace.mark("gc.meta")
        trace.mark("meta")
        assertEquals(LoadPhase.META, trace.currentProgress()!!.phase)
        trace.mark("session")
        trace.mark("ready")
        assertEquals(LoadPhase.STARTING, trace.currentProgress()!!.phase)
    }
}
