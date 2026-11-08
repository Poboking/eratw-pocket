package com.eratw.port.android

import java.util.Collections
import java.util.IdentityHashMap

/**
 * 「按新配置重启游戏」的在办 waiter 单飞闸（挖虫 W5-misc 第 1 条）。
 *
 * ## 修的是哪一种形状
 *
 * [MainActivity.restartGame] 每次进入都会新起一条名为 `eratw-restart` 的线程去等旧会话退出，
 * 而**没有任何地方登记「这一份旧会话已经有人在等了」**。于是两次快速触发（设置页「保存并重启」
 * 连点、或重启屏上再按「重试」）会拿到同一个 `old`，各起一条 waiter：
 *
 * 1. 两条 waiter 都从 `waitUntilStopped(old)` 回来，都判定 `old.isRunning() == false`；
 * 2. 于是 `afterStop()` 跑两遍（双宿主：`startGameOrDemo` 起两份 `GameHost`，或 `recreate()`
 *    与新装载叠在一起）；
 * 3. 第二份宿主把 `host` 字段覆盖掉，**第一份就此脱离 `host` 字段、没有人 close 它** ——
 *    它的 VM 线程还挂在 [SessionHandoff] 的登记槽里，整份 `Program` 压在堆上。
 *
 * 这正是 2026-10-27 那次真机 OOM 的同类形状（两份 Program 同住 512 MiB 堆），只是触发面从
 * 「等不到旧会话」换成「等得太好」：等待逻辑越成功，双 waiter 越是必然双起。
 *
 * ## 两道闸
 *
 * - [begin]：同一个 `old` 只允许一条 waiter。第二次直接忽略（调用方原样返回，不重置 UI、
 *   不再起线程）—— 在办的那条本来就会推进到同一个终点。
 * - [hasInFlight]：旧会话**已经停了**但它的交付还排在主线程队列里的那个窗口（`afterStop()`
 *   经 `runOnUiThread` 投递，名额在投递体第一行才放开）。这一刻 `host` 与 `retiring` 都认不出
 *   在办的那条，若放第二路走「无旧会话」的直起分支，随后那条投递会**再**起一局 ⇒ 又是双宿主。
 *
 * ## 只认身份
 *
 * 与 [SessionHandoff] 同一口径：按引用身份比较（[IdentityHashMap]），不用 `equals`。
 * 两个「看起来一样」但确实不同的 `GameHost` 是两局，各有各的 waiter 才是对的。
 *
 * ## 线程
 *
 * 认领在 UI 线程（[MainActivity.restartGame]），放开在主线程投递体或 waiter 线程的异常分支，
 * 所以本类必须线程安全 —— 一把短临界区即可（三个操作都是集合增删判空，无等待、无嵌套锁）。
 *
 * 本类不碰 Android 运行期，可纯 JVM 单测（见 `RestartWaiterGateTest`）。
 */
internal class RestartWaiterGate {

    private val lock = Any()

    /** 正在被某条 `eratw-restart` 等的旧会话集合（按身份）。 */
    private val inFlight: MutableSet<Any> =
        Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    /**
     * 认领对 [old] 的 waiter 名额。
     *
     * @return true = 本路可以起 waiter；false = 已有 waiter 在等**同一个**旧会话，调用方必须
     *         原样返回（不起第二条线程），否则就是双 `afterStop()`。
     */
    fun begin(old: Any): Boolean = synchronized(lock) { inFlight.add(old) }

    /** 放开对 [old] 的名额（交付体第一行、或 waiter 没走到投递就异常收场时）。重复放开无害。 */
    fun release(old: Any) {
        synchronized(lock) { inFlight.remove(old) }
    }

    /** [old] 是否已有在办 waiter（只读判据，认领请用 [begin]）。 */
    fun isInFlight(old: Any): Boolean = synchronized(lock) { inFlight.contains(old) }

    /** 有没有任何在办 waiter：挡「旧会话已停、交付还在主线程队列里」的那段窗口。 */
    fun hasInFlight(): Boolean = synchronized(lock) { inFlight.isNotEmpty() }

    /** 在办名额数（只用于单测断言与日志，不参与判定）。 */
    fun inFlightCount(): Int = synchronized(lock) { inFlight.size }
}
