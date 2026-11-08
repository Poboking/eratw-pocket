package com.eratw.port.platform.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作表位图「逐出 → 等宽限期 → 回收」的队列（`SpriteBitmapSource.sheetCache` 的配套）。
 *
 * ## 为什么这条队列值得单测
 *
 * 它不是「延迟一下更优雅」，而是**防崩**：`LruCache` 逐出条目时那张 `Bitmap` 很可能正被
 * 本帧的 `Canvas` 引用（硬件加速的 RenderNode 活到帧提交为止），当场 `recycle()` 就是下一帧
 * `Canvas: trying to use a recycled bitmap`。所以判据（等够 `graceMs`、且只在 UI 线程收）
 * 必须被钉死 —— 任何一次「顺手改成当场回收」都要在这里红。
 *
 * 本类是纯逻辑（不认识 `Bitmap`），因此能在纯 JVM 下跑；`Bitmap.recycle()` 的真实调用
 * 留在 `SpriteBitmapSource.drainSheetRecycle`。
 */
class DeferredRecycleTest {

    @Test
    fun `没等够宽限期的一条都不给`() {
        val q = DeferredRecycle<String>(graceMs = 1_000)
        q.add("a", nowMs = 0)
        assertTrue("刚入队就回收 = 赌一帧内画完，赌输就崩", q.drain(nowMs = 0).isEmpty())
        assertTrue("差 1 ms 也不行", q.drain(nowMs = 999).isEmpty())
        assertEquals("没等够的必须留在队列里", 1, q.size)
    }

    @Test
    fun `等够就交出去，且只交一次`() {
        val q = DeferredRecycle<String>(graceMs = 1_000)
        q.add("a", nowMs = 0)
        assertEquals("恰好到期即可回收", listOf("a"), q.drain(nowMs = 1_000))
        assertEquals("交出后从队列摘掉", 0, q.size)
        assertTrue("不重复交（重复 recycle 会崩）", q.drain(nowMs = 5_000).isEmpty())
    }

    @Test
    fun `多条按各自入队时刻分别到期`() {
        val q = DeferredRecycle<String>(graceMs = 1_000)
        q.add("old", nowMs = 0)
        q.add("new", nowMs = 900)
        assertEquals("只有先入队的到期", listOf("old"), q.drain(nowMs = 1_000))
        assertEquals(listOf("new"), q.drain(nowMs = 1_900))
        assertEquals(0, q.size)
    }

    @Test
    fun `drainAll 不论到期与否全部交出`() {
        val q = DeferredRecycle<String>(graceMs = 1_000)
        q.add("a", nowMs = 0)
        q.add("b", nowMs = 999)
        assertEquals("换代/退出时用", listOf("a", "b"), q.drainAll())
        assertEquals(0, q.size)
        assertTrue(q.drainAll().isEmpty())
    }

    @Test
    fun `空队列是常态，不能有额外分配或异常`() {
        val q = DeferredRecycle<String>(graceMs = 1_000)
        assertEquals(0, q.size)
        assertTrue(q.drain(nowMs = 1).isEmpty())
        assertTrue(q.drainAll().isEmpty())
        assertEquals(0, q.drainDueAndRecycle(nowMs = 1) { throw AssertionError("不该被调用") })
        assertEquals(0, q.drainAllAndRecycle { throw AssertionError("不该被调用") })
    }

    /**
     * 审计 #4（2026-10-30）：`drainAll` 此前没有生产调用方，`SpriteBitmapSource.close()`
     * 不排干队列 ⇒ 会话一换，逐出的 122 MiB 级位图被队列钉到进程结束。
     * 两个 `*AndRecycle` 是 close/drainSheetRecycle 现在实际走的收口路径，本组用例钉其纪律。
     */
    @Test
    fun `drainDueAndRecycle 只把等够宽限期的交给 recycler 且每条恰好一次`() {
        val recycled = ArrayList<String>()
        val q = DeferredRecycle<String>(graceMs = 1_000)
        q.add("a", nowMs = 0)
        q.add("b", nowMs = 900)
        assertEquals(1, q.drainDueAndRecycle(nowMs = 1_000) { recycled += it })
        assertEquals(listOf("a"), recycled)
        assertEquals("没等够的留在队列里，等下一次主线程取图时再收", 1, q.size)
        assertEquals(1, q.drainDueAndRecycle(nowMs = 1_900) { recycled += it })
        assertEquals(listOf("a", "b"), recycled)
        assertEquals(0, q.drainDueAndRecycle(nowMs = 9_000) { recycled += it })
        assertEquals("每条只能被交出去一次（重复 recycle 会崩）", 2, recycled.size)
    }

    @Test
    fun `drainAllAndRecycle 整段收干并清空 - close 收口的延后一拍路径`() {
        val recycled = ArrayList<String>()
        val q = DeferredRecycle<String>(graceMs = 1_000)
        q.add("a", nowMs = 0)
        q.add("b", nowMs = 999)
        // 延后回调发生在 RECYCLE_GRACE_MS 之后：这里模拟「不论到期与否」的收口语义
        assertEquals(2, q.drainAllAndRecycle { recycled += it })
        assertEquals("close 后队列必须清空，不然位图被队列钉死（审计 #4）", 0, q.size)
        assertTrue(recycled.containsAll(listOf("a", "b")))
        assertEquals(0, q.drainAllAndRecycle { recycled += it })
        assertEquals("不重复交", 2, recycled.size)
    }

    @Test
    fun `并发入队与抽取不丢不重`() {
        // 淘汰发生在谁 put 就谁身上（UI 线程 / VM 线程都可能是），抽取在 UI 线程 ⇒ 必须同步。
        val q = DeferredRecycle<Int>(graceMs = 0)
        val threads = (0 until 4).map { t ->
            Thread {
                for (i in 0 until 500) q.add(t * 1_000 + i, nowMs = 0)
            }
        }
        threads.forEach { it.start() }
        val got = ArrayList<Int>()
        var idle = 0
        while (idle < 50) {
            val batch = q.drain(nowMs = 1)
            if (batch.isEmpty()) {
                idle++
            } else {
                got += batch
                idle = 0
            }
        }
        threads.forEach { it.join() }
        got += q.drain(nowMs = 1)
        assertEquals("一条都不能丢", 2_000, got.size)
        assertEquals("也不能重复交（重复 recycle 会崩）", got.size, got.toSet().size)
    }
}
