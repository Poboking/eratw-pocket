package com.eratw.port.android

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 进程级「上一局宿主还没让出堆」交接闸（2026-10-31 玩家反馈）。
 *
 * ## 修的是玩家面的哪一句话
 *
 * 玩家 1：「我这边跳出页面来 QQ 会答问题就这样了，同时好像还在进行区块移动，就是换地图
 * 来着」；玩家 2：「切换一直都会」。屏上原样文案：
 *
 * > 启动失败：上一局的内存还没释放（已用 422 MiB / 上限 512 MiB）。请结束本应用进程后
 * > 重开 —— 存档在盘上，不会丢。
 *
 * ## 形状（真机读数 + 读码）
 *
 * 切到 QQ 时系统把 `MainActivity` 回收（进程还在）；回前台是**同进程的新 Activity**，
 * 它会立刻开始装新一局。旧 Activity 的 `onDestroy` 虽然已 `GameHost.close()`，但
 * `close()` 的 `Thread.interrupt()` 只能叫醒阻塞点 —— 换地图那种**纯计算长链**（地图重修
 * 的 `CHARA_MOVEMENT` 实测 1.2 亿~2.9 亿条指令）在旧口径下根本不看中断位，旧 VM 线程
 * 带着整份 `Program`（真机 422 MiB）一直跑到链尾；新一局的堆基线硬闸
 * （`GameHost.STALE_SESSION_BASELINE_MIB`）当场把它判成「上一局没让出堆」。
 *
 * 两把刀各管一半：
 * 1. `VirtualMachine` 主循环现在每 65536 条指令看一次中断位（`Vm.SHUTDOWN_CHECK_MASK`），
 *    `close()` 之后旧链**毫秒级**退出；
 * 2. 本类管**交接次序**：新宿主的装载线程在堆预检之前，先等所有上一局宿主退出登记槽。
 *    这是 [MainActivity.claimUnpackSlot] 同一形状的跨 Activity 单飞，只不过对象从
 *    「解包线程」换成「整局 VM 线程」。
 *
 * ## 为什么按「登记槽」而不是「拿全局单例宿主」
 *
 * `GameHost` 仍然属于 Activity（`onDestroy` 关会话、放视图），本类只回答一个问题：
 * **同进程里还有没有上一局的 VM 线程没退**。登记在 [GameHost.start] 的线程启动之前、
 * 注销在该线程 `finally`，与「整份 Program 还压在这条线程栈上」的窗口严格同寿。
 *
 * ## 边界
 *
 * - 等待有上限（`GameHost.SESSION_HANDOFF_WAIT_MS`）：等不到就不再赌，继续走既有的堆基线
 *   硬闸 —— 由那张屏说真话，而不是把两份 Program 一起塞进 512 MiB 堆。
 * - 只认**身份**（[IdentityHashMap]）：`GameHost` 没有值相等语义，用 `equals` 会在同一
 *   形状的新旧宿主之间误判。
 * - 本类不持任何 View / Activity 引用，登记槽里的宿主由线程与 Activity 自然持有。
 */
internal object SessionHandoff {

    /** 登记槽的读写锁（`register` / `unregister` / `othersAlive` 都极短，一把即可）。 */
    private val lock = Any()

    /** 当前「VM 线程还活着」的宿主集合；按身份比较。 */
    private val live: MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    /** 登记一个宿主（[GameHost.start] 在拉起 VM 线程之前调用；重复登记是无害的）。 */
    fun register(owner: Any) {
        synchronized(lock) { live.add(owner) }
    }

    /** 注销一个宿主（VM 线程的 `finally`；重复注销无害，只认身份）。 */
    fun unregister(owner: Any) {
        synchronized(lock) { live.remove(owner) }
    }

    /** 除 [owner] 之外还有几个宿主的 VM 线程没退（0 = 可以安全开始装载）。 */
    fun othersAlive(owner: Any): Int = synchronized(lock) {
        var n = 0
        for (entry in live) if (entry !== owner) n++
        n
    }

    /**
     * 在当前线程等 [owner] 之外的宿主全部退出，最多 [timeoutMs] 毫秒。
     *
     * 返回 true = 槽已清空（或本来就只有自己）；false = 等到上限仍有人在跑，调用方按
     * 「继续走既有硬闸」处理。等待用短轮询而不是条件变量：交接的另一端是一条独立线程的
     * `finally`，没有可借用的锁对象，轮询成本（每 [pollMs] 一次 set 判空）可以忽略。
     *
     * 中断语义：调用方（装载线程）被 `GameHost.close()` 打断时立即返回当前判断结果，
     * **不吞**中断位（后续装载路径本来就会在段交界看到 `closed`）。
     */
    fun awaitOthersGone(
        owner: Any,
        timeoutMs: Long,
        pollMs: Long = DEFAULT_POLL_MS,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        val step = pollMs.coerceAtLeast(1L)
        while (true) {
            if (othersAlive(owner) == 0) return true
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(step)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return othersAlive(owner) == 0
            }
        }
    }

    /** 交接等待的轮询间隔：50 ms 对玩家不可见，对「旧链已退出」的窗口足够细。 */
    private const val DEFAULT_POLL_MS = 50L
}
