package com.eratw.port.platform.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L11 稳定性波的两条工作表判据（纯 JVM；只调 companion 的纯函数，不构造
 * [SpriteBitmapSource] 实例 —— 其构造器触碰 `android.util.LruCache` 桩，
 * 同 SpriteBitmapSourcePackTest / SpriteBitmapSourceSheetBudgetTest 的口径）。
 *
 * ## [SpriteBitmapSource.fitsSheetCache]：超预算条目禁止入表
 *
 * 缺陷路径（修复前）：`LruCache.put` 对单条大于 `maxSize` 的条目「先入表再当场逐出」，
 * `entryRemoved(newValue = null)` 把它排进 `DeferredRecycle`；调用方拿到的正是这张
 * 1 s 后会被 `recycle()` 的位图（下一帧 `trying to use a recycled bitmap`），且它没被
 * 缓存住、每帧重解。语料最大单张 8000x4000 在预算内，越界只发生在 `decodeFileByPath`
 * 不设 maxSide 档（[GameImageFiles]）解出的超大图上。
 *
 * 可证伪：把 [SpriteBitmapSource.fitsSheetCache] 改成恒 true（或改回「无条件 put」的
 * 判据），下面 192 MiB + 1 与 `Int.MAX_VALUE` 两条用例立刻红；把 Long 比较收窄成 Int，
 * `Int.MAX_VALUE + 1` 那条会因截断翻转成负数而红。
 *
 * ## [SpriteBitmapSource.clampFrameSrc]：动画帧源矩形与位图求交
 *
 * 缺陷路径（修复前）：`spriteFrame` 的动画支只判 `srcW/srcH > 0`，源矩形直接用
 * `min(f.srcW, bitmap.width - f.srcX)`。帧矩形由 `SPRITEANIMEADDFRAME` 按**入表当时**的
 * 父图尺寸校验，之后同 gid 的 `GCREATE` 换小父图，动画帧就会带着越界/负的源矩形走到
 * `Canvas.drawBitmap`（安卓对越界 srcRect 抛 IllegalArgumentException，UI 线程崩）；
 * 原版走 GDI+ 是静默裁剪。
 *
 * 可证伪：让 [SpriteBitmapSource.clampFrameSrc] 原样返回 `srcX/srcY/srcW/srcH`
 * （即修复前的口径），「右下越界」「整块在位图外」「Int 回绕」三条用例红；
 * 合法的「完全在内」「贴右沿」两条用例则钉住「合法帧逐项不变」。
 */
class StabilityL11SpriteBitmapSourceTest {

    // ---- fitsSheetCache ----

    @Test
    fun `预算边界 - 恰好等于上限允许 多一字节拒绝`() {
        val budget = SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES
        assertTrue(SpriteBitmapSource.fitsSheetCache(budget.toLong(), budget))
        assertFalse(SpriteBitmapSource.fitsSheetCache(budget.toLong() + 1L, budget))
    }

    @Test
    fun `超预算的8192x8192位图必须被拒 - 268MiB 大于 192MiB 预算`() {
        val budget = SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES
        val huge = 8192L * 8192L * 4L
        assertTrue("8192x8192 必须超出预算（否则用例本身失效）", huge > budget.toLong())
        assertFalse(SpriteBitmapSource.fitsSheetCache(huge, budget))
    }

    @Test
    fun `语料最大单张必须被接受 - 8000x4000 约122MiB`() {
        val largest = 8000L * 4000L * 4L
        assertTrue(SpriteBitmapSource.fitsSheetCache(largest, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES))
    }

    @Test
    fun `非正字节数不入表 - 桩环境与异常位图不占预算`() {
        assertFalse(SpriteBitmapSource.fitsSheetCache(0L, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES))
        assertFalse(SpriteBitmapSource.fitsSheetCache(-1L, SpriteBitmapSource.SHEET_CACHE_BUDGET_BYTES))
    }

    @Test
    fun `比较全程 Long - 越过 Int MAX 的读数不得被截断成负数`() {
        // 若实现写成 `byteCount.toInt() <= budget`，0x80000000 会翻成 Int.MIN_VALUE ⇒ 误放行。
        val over = Int.MAX_VALUE.toLong() + 1L
        assertFalse(SpriteBitmapSource.fitsSheetCache(over, Int.MAX_VALUE))
        assertTrue(SpriteBitmapSource.fitsSheetCache(Int.MAX_VALUE.toLong(), Int.MAX_VALUE))
    }

    // ---- clampFrameSrc ----

    @Test
    fun `合法帧逐项不变 - 语料参数原样穿过`() {
        // SPRITEANIMEADDFRAME 校验过的形态：源矩形完全落在父图内。
        assertArrayEquals(
            intArrayOf(210, 0, 360, 360),
            SpriteBitmapSource.clampFrameSrc(210, 0, 360, 360, 752, 752),
        )
        // 贴右/下沿（end == bitmap 尺寸）仍算在内，不裁。
        assertArrayEquals(
            intArrayOf(700, 500, 100, 100),
            SpriteBitmapSource.clampFrameSrc(700, 500, 100, 100, 800, 600),
        )
    }

    @Test
    fun `右下越界按位图裁剪 - 宽高收到交集`() {
        assertArrayEquals(
            intArrayOf(700, 0, 100, 100),
            SpriteBitmapSource.clampFrameSrc(700, 0, 400, 100, 800, 600),
        )
        assertArrayEquals(
            intArrayOf(0, 550, 100, 50),
            SpriteBitmapSource.clampFrameSrc(0, 550, 100, 100, 800, 600),
        )
    }

    @Test
    fun `左上负坐标按 GDI 口径裁掉 - 原点不动 只留交集`() {
        // 原版 GDI+：src 左上为负只画交集，目标原点不搬移（本函数只裁宽高）。
        assertArrayEquals(
            intArrayOf(0, 0, 15, 10),
            SpriteBitmapSource.clampFrameSrc(-5, 0, 20, 10, 64, 64),
        )
    }

    @Test
    fun `整块在位图外或尺寸非正返回 null - 这一帧不画且不碰 Canvas`() {
        assertNull(SpriteBitmapSource.clampFrameSrc(800, 0, 10, 10, 800, 600))
        assertNull(SpriteBitmapSource.clampFrameSrc(0, 600, 10, 10, 800, 600))
        assertNull(SpriteBitmapSource.clampFrameSrc(-100, 0, 50, 10, 800, 600))
        assertNull(SpriteBitmapSource.clampFrameSrc(0, 0, 0, 10, 800, 600))
        assertNull(SpriteBitmapSource.clampFrameSrc(0, 0, 10, -1, 800, 600))
    }

    @Test
    fun `srcX 加 srcW 用 Long - Int 回绕不得把越界帧洗成合法帧`() {
        // 修复前的 Int 写法 `bitmap.width - srcX` 在大 srcX 上是负数；而
        // `minOf(srcW, ...)` 前的 `srcX + srcW` 若在 Int 上算，2147483647 + 20 会回绕成负数。
        assertNull(SpriteBitmapSource.clampFrameSrc(Int.MAX_VALUE, 0, 20, 10, 800, 600))
        assertNull(SpriteBitmapSource.clampFrameSrc(Int.MIN_VALUE, 0, Int.MAX_VALUE, 10, 800, 600))
        // 合法小矩形不受位宽影响。
        assertEquals(4, SpriteBitmapSource.clampFrameSrc(0, 0, 4, 4, 800, 600)!!.size)
    }
}
