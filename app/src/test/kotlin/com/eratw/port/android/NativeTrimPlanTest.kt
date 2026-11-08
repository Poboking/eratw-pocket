package com.eratw.port.android

import android.content.ComponentCallbacks2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `onTrimMemory` 的 **native 侧**收口口径（2026-11-02，heap-budget 车道）。
 *
 * ## 这一组用例钉的是什么形状
 *
 * 端点报告里三桶内存病（6 份 16/24/56 字节的 `OutOfMemoryError`、4 份 `used=507~511/512`
 * 的「Java 堆贴顶」、3 份 `native 286~361 MiB` 的「native 驻留偏高」）共同的取证缺口是：
 * `trim=runningCritical freed=0MiB` 那一行**读不出「收了什么、收没收到」** ——
 * `freed=` 只统计 Java 堆上的 `IntArray` 像素，而且 UI 线程那一发只收得了负号段
 * （线程约束见 `GameHost.releaseResourceImagePixels`）；native 那两块账面（平台三张
 * `LruCache` 与本屏四块位图）此前根本没有读数。所以本组用例一半在测**分档判式**
 * （哪一档收多深），一半在测**读数成文**（差分必须逐字出现在那一行里）。
 *
 * 为什么是纯 JVM 单测：`NativeTrimPlan` 只做整数比较与拼串，`ComponentCallbacks2.TRIM_MEMORY_*`
 * 是编译期内联常量（同 [GraphicsTrimPlanTest] 的先例）；真正的回收动作在
 * `CanvasConsoleView.releaseMemoryCaches`，那边由源码面用例
 * `CanvasMemoryReleaseAuditTest` 钉结构。
 */
class NativeTrimPlanTest {

    /**
     * 数值口径自证：KDoc 写的「后台系 = 20/40/60/80、前台 RUNNING 系 = 5/10/15」必须逐个
     * 对得上 SDK 常量。这条闸不是洁癖 —— [GraphicsTrimPlanTest] 里就真实拦下过一次
     * 「把三档名字与数值配反」的错误，判式一旦建在配错的数上就是静默漏收。
     */
    @Test
    fun `档位数值与 KDoc 声明一致`() {
        assertEquals(5, ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertEquals(10, ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals(15, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        assertEquals(20, ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertEquals(40, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        assertEquals(60, ComponentCallbacks2.TRIM_MEMORY_MODERATE)
        assertEquals(80, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** 前台三档只收行缓存与行池：玩家正看着这一屏，动整屏保留面 = 当场闪一帧空白。 */
    @Test
    fun `前台 RUNNING 系不收整屏面`() {
        for (level in intArrayOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        )) {
            assertFalse("档位 $level 不该走深档", NativeTrimPlan.isDeep(level))
        }
    }

    /** 后台四档连整屏保留面 + 背景烘焙面一起交还：这一屏不会立刻再画，回前台整幅重画一帧。 */
    @Test
    fun `后台系四档走深档`() {
        for (level in intArrayOf(
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )) {
            assertTrue("档位 $level 该走深档", NativeTrimPlan.isDeep(level))
        }
    }

    /**
     * 未知档（0 / 负数 / OEM 自造值）一律**不深收**：与 [GraphicsTrimPlan] 同一口径
     * —— 宁可不做也不炸。这一条同时防「有人图省事写成 `level >= UI_HIDDEN`」：
     * 那会把两个不成序刻度混在一起比大小（教训见 [GraphicsTrimPlan] 类 KDoc）。
     */
    @Test
    fun `未知档位不深收`() {
        for (level in intArrayOf(0, 1, 4, 16, 30, -1, -80, 100)) {
            assertFalse("未知档位 $level 不该深收", NativeTrimPlan.isDeep(level))
        }
    }

    /** 深档读数尾部挂 ` deep`，浅档不挂 —— 轨迹里要能看出那一发收没收到整屏面。 */
    @Test
    fun `读数行带深浅标记`() {
        val before = NativeCacheSnapshot(bitmapCacheMiB = 120L, viewMiB = 41L, nativeAllocMiB = 300L)
        val after = NativeCacheSnapshot(bitmapCacheMiB = 0L, viewMiB = 8L, nativeAllocMiB = 300L)
        assertEquals(
            " bitmap=120->0MiB view=41->8MiB native=300->300MiB",
            NativeTrimPlan.readoutLine(before, after, deep = false),
        )
        assertEquals(
            " bitmap=120->0MiB view=41->8MiB native=300->300MiB deep",
            NativeTrimPlan.readoutLine(before, after, deep = true),
        )
    }

    /**
     * 「账面归零、native 没降」必须**读得出来**：这一对差分正是本车道给平台侧登记的那条欠账
     * （`SpriteBitmapSource.trimCaches` 逐出的工作表条目进 1 s 宽限队列，而排干只发生在
     * 主线程取图时 —— 屏上没图 / 已切后台就永远没人排）。所以读数不能只给账面。
     */
    @Test
    fun `账面降而 native 不降时读数如实并存`() {
        val before = NativeCacheSnapshot(bitmapCacheMiB = 192L, viewMiB = 40L, nativeAllocMiB = 361L)
        val after = NativeCacheSnapshot(bitmapCacheMiB = 0L, viewMiB = 0L, nativeAllocMiB = 361L)
        val line = NativeTrimPlan.readoutLine(before, after, deep = true)
        assertTrue("账面差分必须在：$line", line.contains("bitmap=192->0MiB") && line.contains("view=40->0MiB"))
        assertTrue("native 原样复述才暴露「没收」：$line", line.contains("native=361->361MiB"))
    }

    /** [NativeTrimReadout.viewFreedMiB]：正差分给数量，负差分（并发又登记回一条）按 0，不报假账。 */
    @Test
    fun `本屏交还量不为负`() {
        val shrunk = NativeTrimReadout(
            NativeCacheSnapshot(0L, 50L, 0L),
            NativeCacheSnapshot(0L, 10L, 0L),
            deep = true,
        )
        assertEquals(40L, shrunk.viewFreedMiB())
        val grew = NativeTrimReadout(
            NativeCacheSnapshot(0L, 10L, 0L),
            NativeCacheSnapshot(0L, 30L, 0L),
            deep = false,
        )
        assertEquals("账面反弹按 0，不许报成收了负数", 0L, grew.viewFreedMiB())
    }

    /** 报告行后缀：两块 native 账面各自带**堆归属**与预算，免得再被读成「挤 Java 堆」。 */
    @Test
    fun `报告后缀每段自带堆归属`() {
        val suffix = NativeTrimPlan.reportSuffix(NativeCacheSnapshot(bitmapCacheMiB = 7L, viewMiB = 41L, nativeAllocMiB = 286L))
        assertTrue("本屏位图读数必须点名: $suffix", suffix.contains("本屏位图 41 MiB"))
        assertTrue("每段要带堆归属: $suffix", suffix.contains("native"))
        assertTrue("进程 native 读数要在: $suffix", suffix.contains("286 MiB"))
        // 单行：调用方把它当一行塞进报告行表，任何换行都会破坏行对齐。
        assertFalse("报告后缀不许带换行", suffix.contains("\n"))
    }

    /** [NativeTrimPlan.miB]：负数与 0 都给 0 —— 这些读数是「收了有没有」，负数只会被误读成漏收。 */
    @Test
    fun `字节换算不给负数`() {
        assertEquals(0L, NativeTrimPlan.miB(-1L))
        assertEquals(0L, NativeTrimPlan.miB(0L))
        assertEquals(1L, NativeTrimPlan.miB(1024L * 1024L))
        assertEquals(0L, NativeTrimPlan.miB(1024L * 1024L - 1L))
    }
}
