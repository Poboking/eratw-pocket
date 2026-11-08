package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * [LoadTrace] 读侧并发 —— 2026-11-03 真机「启动失败」报告（0.2.39 debug 包，vivo V2199A）的修复契约。
 *
 * ## 现场与根因（报告原文钉死）
 *
 * 报告异常栈：`ConcurrentModificationException` at `ArrayList$Itr.checkForComodification`
 * → `CollectionsKt.joinTo` → `LoadTrace.logPeak(LoadTrace.kt:156)` → `GameHost.loadAndRun:2456`。
 * 时序：VM 线程装载走完，`mark("ready")` 之后立刻 `logPeak()` 迭代采样表；
 * UI 线程经 `view.post { bootTrace?.mark("boot.首帧后") }`（首帧挂钩，同一把
 * `trace`）同时往表里追加 —— `mark` 自己挂在 monitor 里，但 `logPeak` 原先**不挂**，
 * 迭代中途被改 ⇒ CME，被装载 catch 接住，一次本该进标题页的启动被判成「启动失败」。
 *
 * ## 修复口径（本用例锁死）
 *
 * 读侧要么整体挂同一把 monitor（`logPeak`），要么先取加锁快照再迭代
 * （`snapshot()` → `peakHeap` / `peakNative` / `screenSummary`）。写侧 `mark` 的
 * monitor 不变 —— 快照与追加互斥，读到的要么是改动前要么是改动后的完整表。
 *
 * ## 为什么用例这样摆
 *
 * - 先垫一张**长表**再开写者：CME 需要「迭代中途被改」，表越长迭代窗口越大，
 *   修复前的实现红得越快；表只有三五行时本用例碰运气也能绿，那就判不出东西。
 * - 读者反复走 `screenSummary` / `peakHeap` / `peakNative`（`logPeak` 在
 *   `logEnabled = false` 下早退、纯 JVM 单测又没有 logcat，进不去迭代体 ——
 *   但修复后它整方法挂在同一把 monitor 下，与这里锁死的是同一份纪律）。
 * - 垫表与并发追加合计约十几万次 `mark`，每次都会读 `Runtime` 与 ART 统计桩，
 *   纯 JVM 下桩读数恒 -1 / 0，不引入额外耗时。
 */
class LoadTraceConcurrentReadTest {

    @Test
    fun `并发追加与峰值读数不再撞 ConcurrentModificationException`() {
        val trace = LoadTrace(tag = "LoadTraceConcurrentReadTest", logEnabled = false)
        repeat(20_000) { trace.mark("垫表$it") }

        val writerDone = AtomicReference<Throwable?>()
        val readerBoom = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                repeat(100_000) { trace.mark("并发$it") }
            } catch (t: Throwable) {
                writerDone.set(t)
            }
        }
        writer.isDaemon = true
        val reader = Thread {
            try {
                repeat(5_000) {
                    trace.screenSummary()
                    assertNotNull(trace.peakHeap())
                    assertNotNull(trace.peakNative())
                }
            } catch (t: Throwable) {
                readerBoom.set(t)
            }
        }
        reader.isDaemon = true
        writer.start()
        reader.start()
        // 60 s 上限只为防死锁挂死 CI；正常几百 ms 就走完。
        writer.join(60_000)
        reader.join(60_000)

        assertTrue("写侧自己先炸了：${writerDone.get()}", writerDone.get() == null)
        assertTrue(
            "读侧在并发追加下炸了（修复回退 = 回到 2026-11-03 真机现场）：${readerBoom.get()}",
            readerBoom.get() == null,
        )
    }

    @Test
    fun `快照口径不改变读数语义`() {
        val trace = LoadTrace(tag = "LoadTraceConcurrentReadTest", logEnabled = false)
        trace.mark("a") // used 读数在纯 JVM 桩下彼此相同，用段名与条数锁语义
        trace.mark("b")
        trace.mark("c")
        assertEquals("峰值段 = 已用堆最大的那段（桩下并列时取首个最大值）", "a", trace.peakHeap()?.stage)
        assertTrue(
            "屏上摘要仍带峰值与堆上限两个读数位",
            trace.screenSummary().contains("峰值") && trace.screenSummary().contains("堆上限"),
        )
        assertEquals("DevTools 逐段清单仍是 3 行", 3, trace.summaryLines().size)
    }
}
