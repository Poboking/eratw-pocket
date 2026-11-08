package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「内存不够」两族的**屏上文案**契约（[heapPreflightStoppedText] / [heapOomText]）。
 *
 * ## 为什么要单独钉文案
 *
 * 2026-11 真机（vivo V2199A，堆上限 512 MiB）那次的核心不是文案不好看，是**玩家什么都没看到**：
 * 预检拒载后自动降档 → 装载 OOM → 进程被杀。第一刀修判据（[HeapFailureStage] /
 * [shouldAutoRetryCompat]），这一刀修「屏上得说话」：
 *
 * - 甲族（预检拒载）的屏必须给出**这台机的上限 / 这一版要多少 / 还差多少** —— 玩家看到
 *   「还差 28 MB」才知道该换机还是该再试一次；只说「内存不足」等于什么都没说；
 * - 甲族的屏必须明说「换档也可能还是装不下」：那颗按钮在两族都亮（取舍见
 *   `GameHost.canRetryCompat`），按下去仍有被系统杀进程的风险，这一次尝试必须是知情的；
 * - 措辞零基础中文：**不许出现**「堆」「预检」「兼容」「largeHeap」「挡位」这类术语
 *   （2026-11 玩家面口径）。core 的固定句 `BootFailures.HEAP_SHORTAGE_TEXT` 仍按原样留在
 *   核心层（它自己的测试逐字锁定），只是不再上屏。
 */
class HeapFailureCopyTest {

    /** 屏上不许出现的词：读屏的人只有一部手机，没有 logcat 也没有编译原理。 */
    private val forbidden = listOf("堆", "预检", "兼容", "largeHeap", "挡位", "档")

    private fun assertNoJargon(text: String) {
        forbidden.forEach { word ->
            assertFalse("玩家面文案不许出现「$word」：$text", text.contains(word))
        }
    }

    @Test
    fun `甲族屏给出上限需求与还差多少`() {
        // 真机那组数：上限 512、需要 540.2 ⇒ 差 28.2，四舍五入到 MiB = 28。
        val text = heapPreflightStoppedText(maxMiB = 512L, requiredMiB = 540L, usedMiB = 15L)
        assertTrue("这句就是玩家要懂的那句：$text", text.startsWith("${FAILURE_PREFIX}内存不够，装不进这一版"))
        assertTrue("上限要上屏（换不换机就看这个数）", text.contains("最多给游戏 512 MB"))
        assertTrue("需求要上屏", text.contains("这一版要 540 MB"))
        assertTrue("差多少要替玩家算好", text.contains("还差 28 MB"))
        assertTrue("装载前占用要上屏（区分「机型不够」与「已被挤满」）", text.contains("已用 15 MB"))
        assertNoJargon(text)
    }

    @Test
    fun `甲族屏说清换档也可能装不下`() {
        val text = heapPreflightStoppedText(maxMiB = 512L, requiredMiB = 540L, usedMiB = 15L)
        assertTrue("保留那颗按钮就必须交代它的代价：$text", text.contains("也可能还是装不下"))
        assertTrue("按钮叫什么，文案就得叫什么叫（少占内存再试）", text.contains("「少占内存再试」"))
        assertTrue("给一条真出口：换机", text.contains("换一台内存更大的手机"))
    }

    @Test
    fun `差值不为负（需求低于上限时不许说还差 -1 MB）`() {
        val text = heapPreflightStoppedText(maxMiB = 512L, requiredMiB = 512L, usedMiB = 40L)
        assertTrue("刚刚好也是「还差 0 MB」：$text", text.contains("还差 0 MB"))
        assertFalse("负差值是读屏事故", text.contains("还差 -"))
    }

    @Test
    fun `乙族屏给出峰值上限停留阶段与 OOM 原话`() {
        val text = heapOomText(
            peakMiB = 267L,
            maxMiB = 512L,
            stage = "meta",
            oomDetail = "；OOM 原话：OutOfMemoryError: Failed to allocate a 4665616 byte allocation",
        )
        assertTrue("这一族是「装到一半断了」：$text", text.contains("装到一半就断了"))
        assertTrue("峰值要上屏", text.contains("最高用到 267 MB"))
        assertTrue("上限要上屏", text.contains("最多给游戏 512 MB"))
        assertTrue("停在哪一步要上屏（排障与玩家复述都靠它）", text.contains("停在「meta」这一步"))
        assertTrue("OOM 原话照旧保留（区分 Java 堆与 native 分配失败）", text.contains("OOM 原话"))
        assertTrue("出口仍是那两条", text.contains("「少占内存再试」"))
        assertNoJargon(text)
    }

    @Test
    fun `乙族没有阶段读数时不连累那句话`() {
        // 防御分支（meta 闸状态异常）拿不到可信的阶段读数：不许印出「停在「null」」这种。
        val text = heapOomText(peakMiB = 120L, maxMiB = 512L, stage = null, oomDetail = "")
        assertFalse("没有阶段就没有那个从句：$text", text.contains("停在"))
        assertTrue("峰值仍要上屏", text.contains("最高用到 120 MB"))
        assertTrue("上限仍要上屏", text.contains("最多给游戏 512 MB）"))
        assertNoJargon(text)
    }

    @Test
    fun `两族共用同一个标题前缀`() {
        // 失败屏的标题行与覆盖层第一行必须是同一句（旧版这里分叉过：一句「堆不足」、一句「启动失败」）。
        assertEquals(
            FAILURE_PREFIX,
            heapPreflightStoppedText(512L, 540L, 15L).substringBefore("内存不够"),
        )
        assertEquals(
            FAILURE_PREFIX,
            heapOomText(267L, 512L, "meta", "").substringBefore("内存不够"),
        )
    }
}
