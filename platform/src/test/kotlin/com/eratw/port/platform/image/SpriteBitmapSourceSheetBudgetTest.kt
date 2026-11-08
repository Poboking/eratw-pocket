package com.eratw.port.platform.image

import com.eratw.port.core.host.MemoryTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作表位图预算的钉值用例（2026-10-30 稳定性审计 #3(c)）。
 *
 * 旧口径 `Runtime.maxMemory()/2` 是 **Java 堆**数字，却用来预算 **native** 位图：
 * 512 MiB 堆的机器放行 256 MiB native，堆遥测全程好看，PSS 却悄悄爬到 0.4–0.7 GB
 * 被系统静默杀掉。新口径改为与 Java 堆无关的文档化常数
 * [SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES]。
 *
 * 纯 JVM 用例：只读 companion 常数，不构造 [SpriteBitmapSource] 实例
 * （其构造触碰 android.util.LruCache 桩，同 SpriteBitmapSourcePackTest 的口径）。
 * LruCache 行为面（trimCaches 收收到 sheetCache、cacheBytes 计入该表）在桩下无法驱动，
 * 归真机验收 —— 见交付报告 TODO(verify)。
 */
class SpriteBitmapSourceSheetBudgetTest {

    /** 语料最大单张工作表：8000x4000 的 webp 解成 ARGB_8888（native）的字节数。 */
    private val largestSheetBytes: Long = 8000L * 4000L * 4L

    @Test
    fun `预算必须容得下语料最大单张否则逐出即每帧重解`() {
        // LruCache 放不下的条目会被当场逐出 ⇒ 每帧重解（旧 sheetBytes KDoc 记过的退化，
        // 「在大内存机器上反而更慢」）；下调预算不得低于单张最大工作表。
        assertTrue(
            "预算 ${SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES} 必须 >= 最大单张 $largestSheetBytes",
            SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES.toLong() >= largestSheetBytes,
        )
    }

    @Test
    fun `预算为文档化常数严格低于旧口径在512MiB堆上的最坏放行`() {
        // 192 MiB：容得下最大单张（122 MiB）+ 头部余量，且 < 旧口径 maxMemory/2 在
        // 512 MiB 挡放行的 256 MiB —— native 侧只可能比修复前更省。
        assertEquals(192L * 1024L * 1024L, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES.toLong())
        assertTrue(SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES.toLong() < 256L * 1024L * 1024L)
    }

    /**
     * 放开档（`core/host/MemoryTier.kt`：API >= 26 **且** Java 堆 >= 512 MiB）读到 384 MiB，
     * 保守档与「没落性质的桌面装置」仍读 192 MiB（2026-11-03 分档刀）。
     *
     * 走 [SpriteBitmapSource.defaultSheetCacheBytes] 这个**生产入口**并注入 512 MiB 的堆读数，
     * 而不是直读两个常数：把函数里的档位查询摘掉，这条必须红（本仓最怕「闸改了调用口没接」）。
     * 断言不挂测试 JVM 自己的挡位。
     */
    @Test
    fun `放开档读384MiB而保守档与无性质一律读192MiB`() {
        val mib = 1024L * 1024L
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            assertEquals(192L * mib, SpriteBitmapSource.defaultSheetCacheBytes(512L * mib).toLong())
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            assertEquals(384L * mib, SpriteBitmapSource.defaultSheetCacheBytes(512L * mib).toLong())
            // 版本线够但挡位不够 ⇒ 仍旧 192 MiB（判式第二条线，与像素预算那条同口径）。
            assertEquals(192L * mib, SpriteBitmapSource.defaultSheetCacheBytes(256L * mib).toLong())
            // Android 7.1.2 那一族：落了性质也不放开。
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "25")
            assertEquals(192L * mib, SpriteBitmapSource.defaultSheetCacheBytes(512L * mib).toLong())
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
        assertEquals(192L * mib, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES.toLong())
        assertEquals(384L * mib, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES_GENEROUS.toLong())
        // 两档之间只做**选取**：384 MiB 仍是文档化常数、仍是正 Int，不做任何运行期除法
        //（旧 maxMemory/2 的 Long 溢出教训不得复发，见上一条用例）。
        assertTrue(SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES_GENEROUS > 0)
        assertEquals(
            SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES * 2,
            SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES_GENEROUS,
        )
    }

    /**
     * 报告那行的 native 预算必须与档位现取同源（2026-11-04 报告 004311 的教训）：
     * 头部「工作表缓存=384MiB」与池读数「预算 240 MiB」自相矛盾，会把 309 MiB
     * （实际 < 432）误报成「超预算」，把排查往错方向带。四张表之和只许这一份算式。
     *
     * 2026-11-08 起算式含降级位图缓存（[SpriteBitmapSource.defaultDemotedCacheBytes]）：
     * 512 MiB 堆 = 48（bufferCache+fileCache）+ 192/384（工作表）+ 128（降级位图，池像素
     * 预算同分母 maxMemory/4）= 368 / 560 MiB。写死任何一份快照都会重演 004311。
     */
    @Test
    fun `报告 native 预算随档位现取而不是写死 240`() {
        val mib = 1024L * 1024L
        try {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
            assertEquals(368L * mib, SpriteBitmapSource.defaultNativeCacheBudgetBytes(512L * mib))
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
            assertEquals(560L * mib, SpriteBitmapSource.defaultNativeCacheBudgetBytes(512L * mib))
        } finally {
            System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        }
    }

    /**
     * 降级位图缓存预算与池像素预算**同分母**（2026-11-08）：512 MiB 堆 = 128 MiB、
     * 256 MiB 堆 = 64 MiB。判据是「降级位图全集 ≤ 池里被降级那批的像素字节量，而池自己
     * 就拿 maxMemory/4 当闸」—— 平台侧不该比 core 侧更宽，也不该另拍常数。
     * 堆不可知（maxMemory ≤ 0）时预算散成无界，但那条路降级档永不触发 ⇒ 缓存恒空，
     * 自限（见 defaultDemotedCacheBytes 的 KDoc），这里只钉正数闸。
     */
    @Test
    fun `降级位图缓存预算随池像素预算同分母`() {
        val mib = 1024L * 1024L
        assertEquals(128L * mib, SpriteBitmapSource.defaultDemotedCacheBytes(512L * mib).toLong())
        assertEquals(64L * mib, SpriteBitmapSource.defaultDemotedCacheBytes(256L * mib).toLong())
        assertTrue(SpriteBitmapSource.defaultDemotedCacheBytes() > 0)
    }

    @Test
    fun `预算必须是正 Int - 旧 maxMemory 半值的 Long 溢出教训不得复发`() {
        // 旧实现做过 `(maxMemory()/2).coerceIn(64..384MiB).toInt()`：堆 >= 4 GiB 时 /2
        // 越过 Int.MAX_VALUE，toInt 溢出成负数、静默落 64 MiB 挡。常数是纯字面量，
        // 但任何后续「按运行期读数算预算」的改动都必须先过这道正数闸。
        assertTrue(SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES > 0)
    }

    @Test
    fun `宽限期常数保持1秒 - close 延后一拍依赖它`() {
        // SpriteBitmapSource.drainSheetRecycleOnClose 的 postDelayed(RECYCLE_GRACE_MS)
        // 依赖「到点必已等够」这条；改小宽限期要先复核 close 路径与逐出路径两处。
        assertEquals(1_000L, SpriteBitmapSource.RECYCLE_GRACE_MS)
    }

    /**
     * fileCache 预算路由的判据（feat-memory-image-cache）：`LruCache.put` 对单条大于
     * maxSize 的条目是「先入表再当场逐出」⇒ 每帧重解，所以登记前必须先判、超预算的
     * 按名大图改挂 sheetCache。判据与 fitsSheetCache 同形：0 不入表、等预算放行、超一
     * 字节拒。语料参考点：29 张 ARGB 16–64 MiB 的图装不进 16 MiB 的 fileCache 预算，
     * 必须全部被这把闸拦去 sheetCache（192 MiB）。
     */
    @Test
    fun `fileCache 路由判据 - 零不进 - 等预算放行 - 超预算拒`() {
        val budget = 16L * 1024L * 1024L
        assertFalse("0 字节不进表（桩位图/异常位图）", SpriteBitmapSource.fitsFileCache(0L, budget.toInt()))
        assertTrue("等于预算放行（LruCache 裁剪条件是 size > maxSize）",
            SpriteBitmapSource.fitsFileCache(budget, budget.toInt()))
        assertFalse("超一字节就拒 —— 这正是要拦的「先入表再当场逐出」形态",
            SpriteBitmapSource.fitsFileCache(budget + 1L, budget.toInt()))
        // 语料参考点：4096x4096x4B = 64 MiB（按名通道被 maxSide=4096 闸住的最大单张）
        assertFalse(SpriteBitmapSource.fitsFileCache(64L * 1024L * 1024L, budget.toInt()))
        assertTrue(SpriteBitmapSource.fitsFileCache(64L * 1024L * 1024L,
            SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES))
    }

    /** 慢解码取证阈值（BOARD ⑦）：钉 200ms，改值要先复核 ANR 分桶口径（见常量 KDoc）。 */
    @Test
    fun `慢解码取证阈值为200毫秒`() {
        assertEquals(200L, SpriteBitmapSource.SLOW_DECODE_LOG_MS)
    }
}
