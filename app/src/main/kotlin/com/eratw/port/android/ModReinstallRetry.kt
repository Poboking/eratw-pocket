package com.eratw.port.android

/**
 * 设置页「重试装载」→ 运行会话的**跨 Activity 投递队列**（Lane DZ 的玩家入口）。
 *
 * ## 为什么需要这条队列
 *
 * 「回滚态替换重装」的能力住在会话侧：判据是 [com.eratw.port.core.mod.ModRuntimeHost]
 * 的 `entries[packageId].failure != null && !enabled && !shutDown`（重装槽由
 * `core-vm/.../mod/ModHostReinstall.kt` 的 `openReinstallSlot` 开），而唯一的会话引用
 * 是 [GameHost] 的私有 `modRuntimeSession`，且 `install` 必须在 VM 线程的页边界跑。
 * 点击点在设置页（独立 Activity、UI 线程）。两边**没有现成命令通道**：既有
 * [ModUiSettingsEntry] / [ModRuntimeOverviewEntry] 是「会话 → 设置」只读活引用，
 * [ModSettingsNotifyChannel] 是「设置 → 会话」的**数据**队列但语义只到
 * `onSettingsChanged`，[SessionVault] 是取出即清的交接槽（KDoc 明写不可当常驻缝）。
 *
 * 本件照 [ModSettingsNotifyChannel] 的既有形态办：**只存数据、不存引用**——积压里只有
 * packageId 字符串，写入端投递后立即返回，消费端在 VM 线程页边界
 * （`GameHost.onTurnBoundaryReached` 的 `modRuntimeSession?.onTurnBoundary()` 之后）
 * drain 并当场重装。因为不持有宿主/会话引用，双壳交接窗口里没有悬挂引用，也就不需要
 * install/uninstall 认主装卸（与 [ModSettingsNotifyChannel] 的边界口径逐条同源）。
 *
 * ## 线程与生命周期口径
 *
 * - 写入端：设置页 UI 线程（`ModManageActions.onRetryInstall`），去重插入序。
 * - 消费端：VM 线程页边界，drain 取走即清；消费失败只上日志面，不回流重投。
 * - 会话收口：`GameHost` 退出清场（`modRuntimeSession = null` 处）调 [clear] 丢弃积压，
 *   防止陈旧请求漏进下一局首边界（与 `ModSettingsNotifyChannel.clear` 同位）。
 * - 容量：一次玩家点击一条，自然有界，不做显式上限。
 *
 * 线程安全：内部锁 + 不可变取值，读侧只拿快照。
 */
object ModReinstallRequests {

    private val lock = Any()

    /** packageId 形状：与 `ModEnablement` / `ModManageDiagnostics` 的拼路径校验同口径。 */
    private val PACKAGE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** 待办 packageId（插入序去重；guard = [lock]）。 */
    private var pending: LinkedHashSet<String> = LinkedHashSet()

    /** 设置页点击「重试装载」后投递；非法 packageId 静默忽略（脏键不许进队列）。 */
    fun request(packageId: String) {
        if (!PACKAGE_ID.matches(packageId)) return
        synchronized(lock) { pending.add(packageId) }
    }

    /** 取走全部积压（取走即清），保持首投递序、去重。无积压返回空表。 */
    fun drain(): List<String> = synchronized(lock) {
        val taken = pending
        pending = LinkedHashSet()
        taken.toList()
    }

    /** 丢弃全部积压（会话收口用；口径见类 KDoc 生命周期一节）。 */
    fun clear() = synchronized(lock) { pending.clear() }

    /** 测试观察面：当前积压条数。 */
    internal fun pendingCount(): Int = synchronized(lock) { pending.size }
}

/**
 * 该条目此刻是否该给「重试装载」入口（Lane DZ 的可重试形态判据）。
 *
 * 两个条件同时成立才给：
 *
 * 1. [source] == [ModSource.BUILTIN]：只有编译进 APK 的 Kotlin 载体能在会话内取到
 *    **新实例**（[ModCarrierRegistry.providerFor] → `carrier.newMod()`）。导入包/数据包
 *    没有「重编不需要玩家参与」的实例来源，重试入口会假承诺。
 * 2. [failure] != null：本会话装载读数里有该包失败（来源 = `ModManageDiagnostics.snapshot()`，
 *    其唯一写入面是装配期 canonical 行，且 `active=true` 会清掉旧失败）。失败行存在
 *    ⇒ 会话里曾登记过回滚态条目（构造段失败不登记 entry，`failureOf` 返回 null，
 *    消费端还有第二道闸），界面据此只对可重试形态亮按钮。
 *
 * 不做「会话是否存活」的本地探测：失败行本身就是会话事实（`reset()` 只在
 * `GameHost.assembleSession` 顶部调用），会话不在时队列照收，页边界 drain 时自然丢弃。
 */
internal fun reinstallRetryable(source: ModSource, failure: String?): Boolean =
    source == ModSource.BUILTIN && failure != null
