package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunDiagnostics] 是「闪退但没有任何报告」那一类的判读契约。
 *
 * 这些判式没有第二次机会：玩家把轨迹交上来一次，判成哪一桶就把修复方向带到哪。
 * 阈值写错的两种后果 —— 把 native 死判成 Java 堆不够、把映射访问判成内存不够 ——
 * 都只能等下一批玩家反馈才发现，所以边界值（`used/max` 的 85%、native 的 150 MiB）
 * 与判读优先级一起锁在这里。
 */
class RunDiagnosticsTest {

    private fun line(
        used: Long,
        max: Long = 512L,
        native: Long = 20L,
        phase: String = "turn 1ms",
        elapsed: Long = 1_000L,
        sys: Long = -1L,
        lmk: Long = -1L,
    ): String = RunTrail.formatLine(
        elapsed, used, max, native, phase,
        sysAvailMiB = sys, lmkThresholdMiB = lmk,
    )

    // ---- 解析 ----

    @Test
    fun `轨迹行能原样解析回来`() {
        val sample = RunDiagnostics.parseSample(line(508L, native = 251L, elapsed = 20_845L))!!
        assertEquals(20_845L, sample.elapsedMs)
        assertEquals(508L, sample.usedMiB)
        assertEquals(512L, sample.maxMiB)
        assertEquals(251L, sample.nativeMiB)
        assertEquals("turn 1ms", sample.phase)
        assertNull("普通回合行不是内存压力事件", sample.trimFreedMiB)
    }

    @Test
    fun `trim 行解析出这一刀收回多少`() {
        val zero = RunDiagnostics.parseSample(
            RunTrail.formatLine(268_716L, 130L, 512L, 44L, "trim=uiHidden freed=0MiB"),
        )!!
        assertEquals(0L, zero.trimFreedMiB)
        val freed = RunDiagnostics.parseSample(
            RunTrail.formatLine(300_000L, 410L, 512L, 90L, "turn.trim.unused n=3 freed=42MiB used=410MiB/512"),
        )!!
        assertEquals(42L, freed.trimFreedMiB)
    }

    @Test
    fun `不是轨迹行一律跳过`() {
        assertNull(RunDiagnostics.parseSample(""))
        assertNull(RunDiagnostics.parseSample("unknown garbage"))
        assertNull("缺列（旧格式/手改）不猜", RunDiagnostics.parseSample("t=1ms used=2MiB max=512MiB"))
        assertTrue("手改过前缀的照样能读（前后空白容忍）",
            RunDiagnostics.parseSample("  ${line(100L)}  ") != null)
    }

    // ---- 汇总 ----

    @Test
    fun `汇总取峰值而不是末行`() {
        val summary = RunDiagnostics.summarize(
            listOf(
                line(300L, native = 20L, elapsed = 1_000L),
                line(508L, native = 251L, phase = "trim=uiHidden freed=0MiB", elapsed = 2_000L),
                line(200L, native = 100L, phase = "pause", elapsed = 3_000L),
            ),
        )
        assertEquals(3, summary.samples.size)
        assertEquals(508L, summary.peakUsedMiB)
        assertEquals(512L, summary.peakMaxMiB)
        assertEquals(99L, summary.peakUsedPercent)
        assertEquals(251L, summary.peakNativeMiB)
        assertEquals("末行单独留着：死前那一发往往与峰值不是同一行", 3_000L, summary.last!!.elapsedMs)
        assertEquals(1, summary.trimEvents)
        assertEquals(0L, summary.trimFreedMiB)
    }

    @Test
    fun `读不懂的行不影响汇总`() {
        val summary = RunDiagnostics.summarize(listOf("垃圾行", line(100L), ""))
        assertEquals(1, summary.samples.size)
        assertEquals(100L, summary.peakUsedMiB)
    }

    // ---- 判读：三桶 + 无数据 ----

    @Test
    fun `峰值贴顶判 Java 堆`() {
        val summary = RunDiagnostics.summarize(listOf(line(508L, native = 251L)))
        assertEquals(DeathMode.JAVA_HEAP, RunDiagnostics.classify(summary))
    }

    @Test
    fun `Java 堆与 native 双高时 Java 堆优先`() {
        // 两桶都成立时取更充分的解释：堆贴顶不需要再拿 native 解释死因。
        val summary = RunDiagnostics.summarize(listOf(line(508L, native = 304L)))
        assertEquals(DeathMode.JAVA_HEAP, RunDiagnostics.classify(summary))
    }

    @Test
    fun `Java 堆门槛是八十五成`() {
        assertEquals(84L, RunDiagnostics.parseSample(line(435L))!!.usedPercent)
        assertEquals(DeathMode.NATIVE,
            RunDiagnostics.classify(RunDiagnostics.summarize(listOf(line(435L, native = 200L)))))
        assertEquals(85L, RunDiagnostics.parseSample(line(436L))!!.usedPercent)
        assertEquals(DeathMode.JAVA_HEAP,
            RunDiagnostics.classify(RunDiagnostics.summarize(listOf(line(436L, native = 200L)))))
    }

    @Test
    fun `堆不高而 native 高判 native`() {
        val summary = RunDiagnostics.summarize(listOf(line(210L, native = 304L)))
        assertEquals(DeathMode.NATIVE, RunDiagnostics.classify(summary))
    }

    @Test
    fun `native 门槛是一百五十 MiB`() {
        assertEquals(DeathMode.NATIVE,
            RunDiagnostics.classify(RunDiagnostics.summarize(listOf(line(210L, native = 150L)))))
        assertEquals(DeathMode.NEITHER,
            RunDiagnostics.classify(RunDiagnostics.summarize(listOf(line(210L, native = 149L)))))
    }

    // ---- 系统内存（LMK）：2026-10-31 从排除法里拆出来的一桶 ----

    @Test
    fun `轨迹行能读回系统可用内存与低内存线`() {
        val sample = RunDiagnostics.parseSample(line(300L, sys = 820L, lmk = 180L))!!
        assertEquals(820L, sample.sysAvailMiB)
        assertEquals(180L, sample.lmkThresholdMiB)
        assertNull("旧轨迹行没有这两列 ⇒ 读成 null，不是 0", RunDiagnostics.parseSample(line(300L))!!.sysAvailMiB)
        assertNull(RunDiagnostics.parseSample(line(300L))!!.lmkThresholdMiB)
    }

    @Test
    fun `死前系统跌破本机低内存线判系统内存不足`() {
        val summary = RunDiagnostics.summarize(
            listOf(
                line(120L, native = 40L, sys = 620L, lmk = 180L, elapsed = 1_000L),
                line(150L, native = 55L, sys = 172L, lmk = 180L, elapsed = 9_000L),
            ),
        )
        assertEquals("整机内存被别的应用榨干、lmkd 收进程 —— 不是我们的堆不够",
            DeathMode.SYSTEM_LOW_MEM, RunDiagnostics.classify(summary))
        assertEquals(172L, summary.minSysAvailMiB)
        assertTrue("名字要能直接对到 lmkd", RunDiagnostics.modeTitle(DeathMode.SYSTEM_LOW_MEM).contains("LMK"))
    }

    @Test
    fun `恰好等于低内存线不算跌破`() {
        // Android 的定义是 availMem < threshold；相等时系统自己还不算 lowMemory，这里照抄。
        val summary = RunDiagnostics.summarize(listOf(line(120L, sys = 180L, lmk = 180L)))
        assertEquals(DeathMode.NEITHER, RunDiagnostics.classify(summary))
        assertTrue("差一 MiB 就翻桶", RunDiagnostics.isSystemLowMemory(
            RunDiagnostics.summarize(listOf(line(120L, sys = 179L, lmk = 180L)))))
    }

    @Test
    fun `缺 sys 或 lmk 时不往系统桶里推`() {
        // 旧轨迹没有 sys 列：宁可不判，也不拿拍出来的门槛把映射访问的锅推给系统。
        assertEquals(DeathMode.NEITHER,
            RunDiagnostics.classify(RunDiagnostics.summarize(listOf(line(120L, native = 30L)))))
        assertEquals("只有 sys 没有 lmk 同样不判", DeathMode.NEITHER,
            RunDiagnostics.classify(RunDiagnostics.summarize(listOf(line(120L, sys = 50L)))))
    }

    @Test
    fun `Java 堆贴顶时系统压力不抢判`() {
        // 两桶都成立：堆贴顶是充分解释（收堆有用），系统那条只是旁证。
        val summary = RunDiagnostics.summarize(listOf(line(500L, native = 60L, sys = 90L, lmk = 180L)))
        assertEquals(DeathMode.JAVA_HEAP, RunDiagnostics.classify(summary))
    }

    @Test
    fun `读数行带上系统最低可用与那条线`() {
        val summary = RunDiagnostics.summarize(
            listOf(line(150L, native = 55L, sys = 620L, lmk = 180L), line(160L, native = 60L, sys = 172L, lmk = 180L)),
        )
        val text = RunDiagnostics.headline(summary)
        assertTrue("卡片与报告都要能一眼看见死前系统还剩多少", text.contains("系统最低可用 172 MiB"))
        assertTrue(text.contains("低内存线 180 MiB"))
        assertTrue("旧轨迹（没有 sys 列）不出现这一段",
            !RunDiagnostics.headline(RunDiagnostics.summarize(listOf(line(150L)))).contains("系统最低可用"))
    }

    @Test
    fun `两者都不高落到排除法那一桶`() {
        val summary = RunDiagnostics.summarize(listOf(line(60L, max = 576L, native = 18L)))
        assertEquals(DeathMode.NEITHER, RunDiagnostics.classify(summary))
        assertTrue(
            "这一桶必须自述是排除法，不能冒充结论",
            RunDiagnostics.modeExplanation(DeathMode.NEITHER).contains("排除法"),
        )
    }

    @Test
    fun `没有轨迹单独一桶`() {
        val summary = RunDiagnostics.summarize(emptyList())
        assertEquals(DeathMode.NO_DATA, RunDiagnostics.classify(summary))
        assertEquals("轨迹 0 行", RunDiagnostics.headline(summary))
        assertTrue(RunDiagnostics.modeTitle(DeathMode.NO_DATA).contains("没有轨迹"))
    }

    @Test
    fun `阈值常量就是文件头自述的口径`() {
        assertEquals(85L, RunDiagnostics.HEAP_CEILING_PERCENT)
        assertEquals(150L, RunDiagnostics.NATIVE_HIGH_MIB)
    }

    // ---- trim 回收无效的点名 ----

    @Test
    fun `压力到了却一点没收回要点名`() {
        val useless = RunDiagnostics.summarize(
            listOf(line(500L, phase = "trim=uiHidden freed=0MiB"), line(500L, phase = "trim=runningCritical freed=0MiB")),
        )
        val note = RunDiagnostics.trimNote(useless)!!
        assertTrue(note.contains("2 次"))
        assertTrue(note.contains("没有退回任何 MiB"))

        val effective = RunDiagnostics.summarize(listOf(line(400L, phase = "turn.trim.unused n=3 freed=64MiB used=400MiB/512")))
        assertNull("真收回过就不必点名", RunDiagnostics.trimNote(effective))
        assertNull("没有压力信号就没有这句话", RunDiagnostics.trimNote(RunDiagnostics.summarize(listOf(line(100L)))))
    }

    // ---- 可分享文本 ----

    @Test
    fun `报告文本带齐判读、状态与轨迹`() {
        val trail = listOf(line(508L, native = 251L, phase = "turn 42ms", elapsed = 1_500L))
        val text = RunDiagnostics.reportText(
            state = "running build=7f1afec",
            trail = trail,
            headerLines = listOf("app 版本：0.2.32 (versionCode 7)", "设备：vivo V2199A"),
            buildStamp = "7f1afec",
            generatedAtText = "2026-10-31 18:30:00",
        )
        assertTrue(text.contains("运行诊断（运行轨迹）"))
        assertTrue(text.contains("死因判读：Java 堆贴顶"))
        assertTrue(text.contains("峰值 Java 已用 508/512 MiB（99%）"))
        assertTrue(text.contains("running build=7f1afec"))
        assertTrue(text.contains("app 版本：0.2.32 (versionCode 7)"))
        assertTrue(text.contains("设备：vivo V2199A"))
        assertTrue("轨迹原文原样带出，不再二次格式化", text.contains(trail[0]))
        assertTrue("只带水位与阶段名，不带存档/变量", text.contains("不含存档数据与变量取值"))
        assertTrue(text.endsWith("诊断结束"))
    }

    @Test
    fun `系统内存不足的报告把系统读数和那条线一起上屏`() {
        val trail = listOf(
            line(180L, native = 30L, phase = "turn 12ms", elapsed = 60_000L, sys = 172L, lmk = 180L),
        )
        val text = RunDiagnostics.reportText(
            state = "running build=7f1afec",
            trail = trail,
            headerLines = listOf("设备：vivo V2199A"),
            buildStamp = "7f1afec",
            generatedAtText = "2026-10-31 18:30:00",
        )
        assertTrue(text.contains("死因判读：系统内存不足（被 LMK 收掉）"))
        assertTrue("读数要带最低可用值和它对照的本机线", text.contains("系统最低可用 172 MiB（本机低内存线 180 MiB）"))
        assertTrue("解释要指向整机内存而不是我们的堆", text.contains("lmkd"))
        assertTrue("sys/lmk 两列的口径要写在怎么读里", text.contains("sys= 是那一发的**系统**可用内存"))
    }

    @Test
    fun `无轨迹的报告写明死在装载期`() {
        val text = RunDiagnostics.reportText(
            state = null,
            trail = emptyList(),
            headerLines = listOf("设备：未知"),
            buildStamp = "?",
            generatedAtText = "2026-10-31 18:30:00",
        )
        assertTrue(text.contains("死因判读：没有轨迹"))
        assertTrue(text.contains("装载期"))
        assertTrue("读不到收尾标记也要如实说", text.contains("（读不到）"))
    }
}
