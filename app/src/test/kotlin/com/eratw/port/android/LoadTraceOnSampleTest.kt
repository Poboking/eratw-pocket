package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoadTrace.onSample]（R3 结构化事件的装载相位入口）的接线契约。
 *
 * 判据三条：
 * 1. 缺省 null ⇒ 与引入前逐字等价（`mark` 照常返回、采样表照常增长）；
 * 2. 装上监听 ⇒ 每次 `mark` 恰好回调一次，段名/读数与采样表里那一条相同；
 * 3. 监听体抛异常 ⇒ 就地吞掉，`mark` 不抛、采样仍落表 —— 埋点坏了不许拖死装载。
 */
class LoadTraceOnSampleTest {

    @Test
    fun `缺省没有监听时 mark 行为不变`() {
        val trace = LoadTrace(tag = "LoadTraceOnSampleTest", logEnabled = false)
        val a = trace.mark("start")
        val b = trace.mark("preflight")
        assertEquals("start", a.stage)
        assertEquals("preflight", b.stage)
        assertEquals("两段都在采样表里", 2, trace.summaryLines().size)
    }

    @Test
    fun `装上监听后每次 mark 回调一次且读数一致`() {
        val trace = LoadTrace(tag = "LoadTraceOnSampleTest", logEnabled = false)
        val seen = ArrayList<LoadTrace.Sample>()
        trace.onSample = { seen += it }
        val a = trace.mark("start")
        val b = trace.mark("meta")
        assertEquals("回调次数 = mark 次数", 2, seen.size)
        assertEquals(a, seen[0])
        assertEquals(b, seen[1])
        assertEquals("start", seen[0].stage)
        assertEquals("meta", seen[1].stage)
    }

    @Test
    fun `监听体抛异常不传染给 mark`() {
        val trace = LoadTrace(tag = "LoadTraceOnSampleTest", logEnabled = false)
        var calls = 0
        trace.onSample = {
            calls++
            error("模拟埋点故障")
        }
        val sample = trace.mark("start")
        assertEquals("start", sample.stage)
        assertEquals("监听确实被调过", 1, calls)
        assertTrue("采样仍落表", trace.summaryLines().size == 1)
    }
}
