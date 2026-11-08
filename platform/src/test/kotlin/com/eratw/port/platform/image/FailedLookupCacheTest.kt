package com.eratw.port.platform.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * [FailedLookupCache] 的判据守卫。
 *
 * 这个类存在的唯一理由是「解不出来的名字不要再每帧重试一次」，所以测试只钉两件事：
 * **TTL 内必须跳过、到期必须放行**，以及**并发下不能抛**（读在 UI 线程、清在 VM 线程）。
 */
class FailedLookupCacheTest {

    @Test
    fun `没记过的名字不跳过`() {
        val cache = FailedLookupCache(ttlMs = 1_000L)
        assertFalse(cache.shouldSkip("TW_title001", 0L))
        assertEquals(0, cache.size)
    }

    @Test
    fun `记过之后 TTL 内跳过`() {
        val cache = FailedLookupCache(ttlMs = 1_000L)
        cache.markFailed("missing_img", 100L)
        assertTrue(cache.shouldSkip("missing_img", 100L))
        // 边界前一刻仍然跳过（判据是 `now - failedAt >= ttl` 才算过期）
        assertTrue(cache.shouldSkip("missing_img", 1_099L))
    }

    @Test
    fun `到期放行并摘掉记录`() {
        val cache = FailedLookupCache(ttlMs = 1_000L)
        cache.markFailed("missing_img", 100L)
        assertFalse(cache.shouldSkip("missing_img", 1_100L))
        // 到期那条被顺手摘掉：否则表只会越来越大（类 KDoc 的容量取舍）
        assertEquals(0, cache.size)
        assertFalse(cache.shouldSkip("missing_img", 1_101L))
    }

    @Test
    fun `不同名字互不影响`() {
        val cache = FailedLookupCache(ttlMs = 1_000L)
        cache.markFailed("a", 0L)
        assertTrue(cache.shouldSkip("a", 10L))
        assertFalse(cache.shouldSkip("b", 10L))
    }

    @Test
    fun `clear 之后重新放行`() {
        val cache = FailedLookupCache(ttlMs = 10_000L)
        cache.markFailed("a", 0L)
        assertTrue(cache.shouldSkip("a", 1L))
        cache.clear()
        assertFalse(cache.shouldSkip("a", 2L))
        assertEquals(0, cache.size)
    }

    @Test
    fun `到容量上限时整表清空而不是无界增长`() {
        val cache = FailedLookupCache(ttlMs = 10_000L, maxEntries = 4)
        for (i in 0 until 4) cache.markFailed("k$i", 0L)
        assertEquals(4, cache.size)
        // 第 5 条触发整表清空，随后只留这一条
        cache.markFailed("k4", 0L)
        assertEquals(1, cache.size)
        assertTrue(cache.shouldSkip("k4", 1L))
        // 被清掉的那批重新变成「没记过」——代价是重试一次，不是正确性问题
        assertFalse(cache.shouldSkip("k0", 1L))
    }

    @Test
    fun `重复记同一个名字不撑大表`() {
        val cache = FailedLookupCache(ttlMs = 1_000L, maxEntries = 8)
        repeat(50) { i -> cache.markFailed("same", i.toLong()) }
        assertEquals(1, cache.size)
        // 记的是最后一次的时刻（49）：从 49 起算满 1000 ms 才到期
        assertTrue(cache.shouldSkip("same", 1_048L))
        assertFalse(cache.shouldSkip("same", 1_049L))
    }

    /**
     * 读在 UI 线程（每帧）、`clear` 在 VM 线程（`invalidate()` 换代）——两者并发时
     * 不许抛（`ConcurrentModificationException` 就是这一类容器最典型的翻车方式）。
     */
    @Test
    fun `并发标记与清理不抛异常`() {
        val cache = FailedLookupCache(ttlMs = 10L, maxEntries = 64)
        val errors = AtomicInteger()
        val start = CountDownLatch(1)
        val threads = ArrayList<Thread>()
        repeat(4) { t ->
            threads += Thread({
                start.await()
                try {
                    for (i in 0 until 20_000) {
                        val key = "k${(i + t) % 128}"
                        cache.markFailed(key, i.toLong())
                        cache.shouldSkip(key, i.toLong() + 1L)
                        if (i % 500 == 0) cache.clear()
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                }
            }, "failed-cache-$t")
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { it.join(20_000L) }
        assertEquals("并发读写不该抛异常", 0, errors.get())
    }
}
