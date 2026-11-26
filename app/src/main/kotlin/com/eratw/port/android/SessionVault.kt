package com.eratw.port.android

import android.util.Log

/**
 * 进程级会话槽（2026-11-13「会话升进程级」收尾）：把交接完成的活会话从 [MainActivity]
 * 的实例字段升到进程单例，Activity 被系统销毁（「不保留活动」/ OEM 后台清理 / SAF 全屏
 * 界面）而进程仍活着时，下一个 MainActivity 实例从这里接回同一局，玩家进度不丢。
 *
 * ## 为什么需要它
 *
 * era VM 会话不可序列化——进程死了什么都救不回（那条路是物理限制，回标题是唯一归宿）；
 * 但「进程活、Activity 死」的形态（BOARD 2026-10-31 三星 SM-G9860 双壳实形）以前被
 * `onDestroy → host.close()` 一刀杀掉，玩家从设置页返回只能重新装载回标题。
 * 设置页自 2026-11-14 起恢复为**不透明的全屏 Activity**（半透明窗口会把设置页钉死在
 * 游戏的方向锁里，见 manifest 的 SettingsActivity 节点）：它盖上来时 MainActivity 正常进
 * stopped，系统销毁它也由本槽接回。会话归进程所有，Activity 只是视图适配器 ——
 * 设置页再进 SAF 全屏界面、任何 Activity 被单独销毁的场景同理，进度损失为零。
 *
 * ## 生命周期与线程口径
 *
 * * [install] —— [MainActivity] 的 `unpackAndStart` 交接成功处（UI 线程）；交接即入槽，
 *   从此槽是权威（Activity 字段只是本实例的便利引用）。
 * * [takeoverIfAlive] —— 新实例 `onCreate` 装配处（UI 线程）：槽里是活会话就取走并清槽
 *   （接回实例成为新主人）；槽里是死会话（VM 线程已退：失败终局 / QUIT 收口）就地
 *   [GameHost.close] 后按无会话处理 —— 失败屏不接回，重试语义归重开（边界，见类头尾）。
 * * [install]（第二次调用）—— `onDestroy` 的非 finishing 分支在冻结后**重新入槽**：
 *   takeover 清过槽，接回壳自己再被系统销毁时必须把会话交还进程，否则下一实例问槽扑空、
 *   冷启回标题，挂起的存档检查点也永远兑现不了（2026-11-17 玩家 bug）。归属判据用
 *   [GameHost.isBoundToView]（视图仍是本壳才动），不用槽归属——槽在壳持有期间本就为空。
 * * [discard] —— [GameHost.close] 尾部**认主**调用：只有槽里正是这只宿主才清槽
 *   （`restartGame` 拆旧会话 / 玩家退出都经 close，一处收口不漏）；旧壳的 close 不会把
 *   新壳刚接回的槽撅空（口径同 `stallSceneOwner` 认主摘除）。
 *
 * 全部读写在 UI 线程：install（交接回调）、takeoverIfAlive（onCreate）、discard（close）
 * 的每一个调用点都在主线程（GameHost.close 的三条路——onDestroy / restartGame /
 * onStartupFault——全部经 `runOnUiThread` 或 UI 线程生命周期）。VM 线程不碰槽：它的
 * 收口走 `shutdownForExit`（不 close 宿主），槽里的宿主仍由 UI 线程处置。
 *
 * ## 边界（如实记账）
 *
 * * 进程被 LMK 整杀：槽随进程走，无解（所有 app 同命）；冷启回标题。
 * * 失败屏 / QUIT 终局不接回：`takeoverIfAlive` 判 VM 线程已死就地 close，玩家重试 =
 *   重开一局。失败屏的「重试 / 兼容档重试」按钮在新实例上不可达（失败状态在旧壳），
 *   这是收口简化换来的损失，如实留痕。
 */
object SessionVault {

    private const val TAG = "SessionVault"

    private val backing = VaultCore<GameHost>()

    /** 交接成功即入槽（UI 线程）。返回被顶出的旧宿主（正常为 null；调用方不负责关闭它——能被顶出说明它已被 close 清过槽）。 */
    fun install(host: GameHost) {
        backing.install(host)
        Log.i(TAG, "会话入槽：$host")
    }

    /**
     * 新实例装配处取活会话（UI 线程）：槽里是活的（VM 线程在跑且宿主未关）就取走清槽；
     * 死会话就地 close 清槽返回 null（见类头「失败屏不接回」）。
     */
    fun takeoverIfAlive(): GameHost? = backing.takeover(
        alive = { !it.isClosedForTakeover && it.isRunning() },
        kill = { runCatching { it.close() } },
    ).also {
        if (it != null) Log.i(TAG, "会话出槽接回：$it")
    }

    /** [GameHost.close] 尾部认主清槽：槽里正是这只宿主才清（旧壳不撅新壳的槽）。 */
    fun discard(host: GameHost) {
        backing.discard(host)
    }

    /** 诊断读数（dumpsys 取证 / 源码哨兵可读）；不改变槽状态。 */
    fun peek(): GameHost? = backing.peek()
}

/**
 * 槽内核（泛型，纯 JVM 可测）：install / takeover(alive, kill) / discard 三操作的
 * 认主与清理语义。生产由 [SessionVault] 持 `VaultCore<GameHost>`；单测用假类型直测。
 */
internal class VaultCore<T : Any> {

    @Volatile
    private var slot: T? = null

    /** 入槽（覆盖旧值，返回旧值——正常序列下调用方已先 close 它，槽里不会有旧值）。 */
    fun install(value: T): T? {
        val old = slot
        slot = value
        return old
    }

    /**
     * 取会话：槽里 [alive] 成立 → 取出清槽返回；不成立 → [kill] 收场后清槽返回 null。
     * 「取出」与「收场」互斥且各自只发生一次。
     */
    fun takeover(alive: (T) -> Boolean, kill: (T) -> Unit): T? {
        val current = slot ?: return null
        slot = null
        return if (alive(current)) current else {
            kill(current)
            null
        }
    }

    /** 认主清槽：只有槽里正是 [value] 才清（旧壳的 close 不得撅空新壳刚接回的槽）。 */
    fun discard(value: T) {
        if (slot === value) slot = null
    }

    /** 诊断读数。 */
    fun peek(): T? = slot
}
