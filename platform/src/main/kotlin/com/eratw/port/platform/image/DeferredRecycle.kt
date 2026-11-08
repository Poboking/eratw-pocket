package com.eratw.port.platform.image

/**
 * 「过一会儿再回收」队列：位图从缓存里被逐出时先进这里，**隔一段时间**才真正 `recycle()`。
 *
 * ## 为什么不能当场回收
 *
 * `LruCache` 逐出一条目时，那张 `Bitmap` 很可能**正在被画**：`CanvasConsoleView.onDraw`
 * 拿到的 `Frame` 里就握着它，而硬件加速的 Canvas 会把位图引用挂到本帧的 RenderNode 上，
 * 直到这一帧提交为止。当场 `recycle()` 的后果是下一帧 `Canvas: trying to use a recycled
 * bitmap` 直接崩 —— 这正是 `cacheFile` 那段 KDoc 里记着的旧账（4.3 MB 的拼图每丢一次
 * 就是一次 OOM 的分子，改成「保留命中者、回收落败者」）。
 *
 * ## 判据
 *
 * 只要「从逐出到真正回收」之间隔够长（[graceMs]，默认 1 s），就不可能还有绘制引用：
 * 一帧的绘制在毫秒级完成，1 s 后连三缓冲都翻完了。**不用**引用计数 —— 那要给
 * `Frame` 加生命周期，侵入面比收益大。
 *
 * 本类是纯逻辑（不认识 `Bitmap`），因此能在纯 JVM 单测里钉死；`Bitmap.recycle()` 的
 * 实际调用留在调用方（`SpriteBitmapSource`），并且只在 **UI 线程**做（见那边的注释）。
 *
 * ## 线程
 *
 * `add` 可能在任意线程（缓存淘汰发生在谁 `put` 就谁身上），`drain` 在 UI 线程。
 * 全部收进 `this` 监视器；锁内只有 ArrayList 操作，不回调、不 `recycle`。
 *
 * @param graceMs 从入队到允许回收的最小间隔
 */
internal class DeferredRecycle<T>(private val graceMs: Long) {

    private class Entry<T>(val value: T, val atMs: Long)

    private val pending = ArrayList<Entry<T>>()

    /** 当前排队等待回收的条数（诊断/单测用）。 */
    val size: Int get() = synchronized(this) { pending.size }

    /** 入队一条待回收对象。同一条被重复入队不做去重 —— 调用方保证每条只入一次。 */
    fun add(value: T, nowMs: Long) {
        synchronized(this) { pending.add(Entry(value, nowMs)) }
    }

    /**
     * 取出**已经等够** [graceMs] 的那些，并从队列里摘掉。
     *
     * 返回的列表由调用方负责真正回收；没等够的留在队列里等下一次 `drain`。
     */
    fun drain(nowMs: Long): List<T> = synchronized(this) {
        if (pending.isEmpty()) return emptyList()
        val ready = ArrayList<T>(pending.size)
        val it = pending.iterator()
        while (it.hasNext()) {
            val e = it.next()
            // 用减法比大小而不是 `e.atMs + graceMs <= nowMs`：时间戳是毫秒计数的 Long，
            // 减法在溢出语义上更稳（虽然实际跑不到）。
            if (nowMs - e.atMs >= graceMs) {
                ready.add(e.value)
                it.remove()
            }
        }
        ready
    }

    /** 取出全部（不论等没等够）并清空。换代/退出时用，调用方自行决定要不要立刻回收。 */
    fun drainAll(): List<T> = synchronized(this) {
        if (pending.isEmpty()) return emptyList()
        val all = ArrayList<T>(pending.size)
        for (e in pending) all.add(e.value)
        pending.clear()
        all
    }

    /**
     * [drain] + 立刻把等够宽限期的逐条交给 [recycler]；返回交出去的条数。
     * 没等够的留在队列里等下一次。生产调用方：`SpriteBitmapSource.drainSheetRecycle`
     * （主线程每帧取图时）。
     *
     * [recycler] 在监视器**外**执行（先出队再回调），回收动作慢或抛异常都不占队列锁；
     * 每条恰好被交出一次（[drain] 已摘条），不会重复回收。
     */
    fun drainDueAndRecycle(nowMs: Long, recycler: (T) -> Unit): Int {
        val ready = drain(nowMs)
        for (value in ready) recycler(value)
        return ready.size
    }

    /**
     * [drainAll] + 把整段交给 [recycler]；返回交出的条数。
     *
     * 生产调用方：`SpriteBitmapSource.drainSheetRecycleOnClose` 的**延后一拍**分支 ——
     * 会话收口时刚入队、还没等够宽限期的条目，在延后 [RECYCLE_GRACE_MS] 的主线程回调里
     * 用本函数整段收干（那时距入队必已过了宽限期，回收不再可能撞到在途帧）。
     * 宽限期纪律由调用方的延后排期保证，本函数自身**不**检查时间。
     */
    fun drainAllAndRecycle(recycler: (T) -> Unit): Int {
        val all = drainAll()
        for (value in all) recycler(value)
        return all.size
    }
}
