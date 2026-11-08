package com.eratw.port.android

/**
 * 三路心跳记账（OBS 观察性波 · obs-heartbeat 车道）。
 *
 * ## 要补的读数缺口
 *
 * 2026-11-01 收件箱那份 OPPO PLD110 / 0.2.34 的轨迹是 `turn 2ms` → `2342ms` → `15192ms` →
 * `58515ms`（同期 `used=510~511MiB max=512MiB`）—— 那是**脚本侧**的形状，盘上读得到。
 * 玩家说的「卡死 / 点屏幕没反应」还有另外两种死法：死的是 **UI 帧循环**（`MainActivity.pump`
 * 那条 Choreographer 回路）或**渲染发布**（新快照没到屏）。游戏逻辑严格单线程（ADR-003，
 * 脚本跑在 `eratw-vm` 线程，UI 走 Choreographer，两条线程分开），所以「谁先停」是一个
 * **需要三处各留一个时刻戳**才答得出的问题，不是主线程执行脚本那种典型安卓病灶。
 *
 * ## 本文件给什么
 *
 * 1. [Heartbeat]：**实例**（不是 object 单例）的三路末次动作记账 —— 每路存
 *    「末次成功动作的时刻（`SystemClock.elapsedRealtime` 口径，由调用方传入）」+ 累计次数 +
 *    末次动作名。可变状态全在实例字段上，挂宿主实例（`MainActivity` 的成员；`GameHost`
 *    侧的引用由组长接线交出），符合 AGENTS 铁律 4「禁静态可变全局」。
 * 2. [HeartbeatJudgment]：**纯**判读 —— 阈值、超期与否、报告小节文本、轨迹行尾的 `hb=` 段。
 *    不碰时钟、不碰 Android API ⇒ `:app` 的 JVM 单测直接钉死（同 [RunTrail] 的抽法）。
 *
 * 时刻一律由调用方传 `nowMs`（`elapsedRealtime` 口径）：本文件不读时钟，
 * 于是同一份判式在单测里可复现，装机上读的是单调时钟（不受墙钟改时间影响）。
 */

/**
 * 心跳的三路通道。
 *
 * @property key 轨迹行与报告里用的短名（`hb=ui:120/publish:58000/vm:2`），给端点侧脚本 grep 用
 * @property cnName 报告小节里的人话名
 */
enum class HeartbeatChannel(val key: String, val cnName: String) {

    /** UI 帧循环：`MainActivity.pump` 这一 Runnable 又跑完了一整轮。 */
    UI_FRAME("ui", "UI 帧循环"),

    /** 渲染发布：一趟真的有新快照到达并被绘制（判据见 [HeartbeatJudgment] 的类头）。 */
    RENDER_PUBLISH("publish", "渲染发布"),

    /** 脚本线程：回合边界（页交付）或页中检查点（指令循环里两条语句之间）。 */
    VM("vm", "脚本线程"),
}

/**
 * 一路心跳的一次读数（纯值，从 [Heartbeat.snapshot] 拷出来交给判读）。
 *
 * @property lastAtMs 末次动作时刻（`elapsedRealtime` 口径）；[Heartbeat.NEVER] = 这一路从没跳过
 * @property count 累计成功次数（只增；溢出无所谓，读数只看形状）
 * @property lastAction 末次动作名（打点方给的一句话，如 `turn 58515ms` / `四段全成`）
 */
data class HeartbeatReading(
    val channel: HeartbeatChannel,
    val lastAtMs: Long,
    val count: Long,
    val lastAction: String,
)

/** 三路读数的不可变快照；判读只吃这个，不吃活动中的 [Heartbeat]（避免判式与状态互相引用）。 */
data class HeartbeatSnapshot(val readings: List<HeartbeatReading>) {

    /** 某一路的读数；这一路根本没被登记过（打点未接线）时 null。 */
    fun reading(channel: HeartbeatChannel): HeartbeatReading? = readings.firstOrNull { it.channel == channel }

    /** 某一路距末次动作多少毫秒；从未跳过 = null（判读不许把「没数据」当成「冻结」）。 */
    fun ageMs(channel: HeartbeatChannel, nowMs: Long): Long? =
        HeartbeatJudgment.ageMs(reading(channel)?.lastAtMs ?: Heartbeat.NEVER, nowMs)
}

/**
 * 三路心跳的记账本体：**实例**持有全部可变状态。
 *
 * 线程口径：每路**单写者** —— [markUiFrame] / [markRenderPublish] 只在 UI 线程（同一个
 * `pump` Runnable），[markVm] 只在 `eratw-vm` 线程。于是 `count += 1` 这种非原子自增是安全的
 * （没有第二个写者），而 [snapshot] 是三读拼接 —— 跨通道不保证同一瞬间。判读要的正是
 * 「哪一路陈旧了多少毫秒」这种量级差（阈值最小 1 s），几十毫秒的拼接误差不改变结论；
 * 反过来若为了拼接原子去加锁，就是让 VM 线程在指令循环里等 UI 线程，本末倒置。
 */
class Heartbeat {

    private class Slot {
        @Volatile var lastAtMs: Long = NEVER
        @Volatile var count: Long = 0L
        @Volatile var lastAction: String = ""
    }

    private val ui = Slot()
    private val publish = Slot()
    private val vm = Slot()

    /**
     * 记一次 UI 帧循环：`pump` 这一轮的四段跑完之后（含熔断冷却帧 —— 那种帧没跑四段，
     * 但循环确实还在转，判成「UI 死了」是假读数）。
     *
     * @param action 这一轮做了什么（`四段全成` / `四段有失败:快照差分` / `熔断冷却中`）
     */
    fun markUiFrame(nowMs: Long, action: String) = mark(ui, nowMs, action)

    /** 记一次渲染发布：判据（为什么是「水位前进 + 真的画了一帧」）见 [HeartbeatJudgment] 类头。 */
    fun markRenderPublish(nowMs: Long, action: String) = mark(publish, nowMs, action)

    /**
     * 记一次脚本侧动作：页尾回合边界（`onTurnReady`）或页中检查点（`midPageCheckpoint`）。
     *
     * 打点口在 `GameHost`（obs-turnpct 车道独占的文件），由组长接线时各加一行，故这里
     * 只给口、不在本车调用。
     */
    fun markVm(nowMs: Long, action: String) = mark(vm, nowMs, action)

    /** 当前三路读数的快照（旧值容忍：见类头的线程口径）。 */
    fun snapshot(): HeartbeatSnapshot = HeartbeatSnapshot(
        listOf(
            readingOf(HeartbeatChannel.UI_FRAME, ui),
            readingOf(HeartbeatChannel.RENDER_PUBLISH, publish),
            readingOf(HeartbeatChannel.VM, vm),
        ),
    )

    /** 这一路跳过没有（轨迹与报告用它决定写读数还是写「未采到」）。 */
    fun hasMeasurement(channel: HeartbeatChannel): Boolean = slotOf(channel).lastAtMs != NEVER

    private fun slotOf(channel: HeartbeatChannel): Slot = when (channel) {
        HeartbeatChannel.UI_FRAME -> ui
        HeartbeatChannel.RENDER_PUBLISH -> publish
        HeartbeatChannel.VM -> vm
    }

    private fun mark(slot: Slot, nowMs: Long, action: String) {
        slot.lastAtMs = nowMs
        slot.count += 1L
        slot.lastAction = action
    }

    private fun readingOf(channel: HeartbeatChannel, slot: Slot): HeartbeatReading =
        HeartbeatReading(channel, slot.lastAtMs, slot.count, slot.lastAction)

    companion object {
        /** 这一路还没有任何动作（未接线 / 还没跑到那个点）。 */
        const val NEVER: Long = -1L
    }
}

/**
 * 心跳的纯判读：阈值、超期、报告小节、轨迹行尾的 `hb=` 段。
 *
 * ## 渲染发布这一路为什么不是「pumpAndInvalidateIfNeeded 的返回值」
 *
 * 任务书要的是「真的拿到了新快照并置脏才算一次发布」。实读代码：
 * `CanvasConsoleView.pumpAndInvalidateIfNeeded()`（render-canvas :1013-1019）**返回 Unit**，
 * 内部只做 `feed?.hasUnpublishedContent()` 的布尔询问 + 置脏 `invalidate()` —— 它既不把「有没有新快照」交回调用方，也不是发布动作本身。
 * 真正的发布在 `onDraw` 里（:1682 `attached.pump()`；`ConsoleFeed.publish` 走完
 * `screen.submitSnapshot` 才把 `publishedRevision` 推上去，render :284-286），而
 * `ConsoleFeed.publishedRevision` 是 **private**（:156），app 侧读不到。
 * ⇒ 退到任务书给的第二条判据并再加一道「真的画了帧」的证据：
 * ① 水位前进 = `GameHost.debugRevision()`（app/GameHost.kt:1203 → `session.console.revision`，
 *   与 `ConsoleFeed.hasUnpublishedContent()` 比的是同一个数，render :241-244）；
 * ② 本帧之后 `CanvasConsoleView.dbgFrameCount` 变了（:1544 声明、:1691 在 `onDraw` 的
 *   finally 里自增，含提前 return 的空帧）⇒ 那一趟 `onDraw` 里的 `pump()` 把新快照发了出去。
 * 两条件同时成立才记一次发布：**只有水位前进、没人画帧**（invalidate 不生效 / 绘制被饿）
 * 不记 —— 那正是「UI 在转但屏上不更新」这一路要读出来的形状。
 * `debugStats()`（:1585）会把 `dbgFrameCount` 清零，所以调用方用**不等**而不是**增大**判，
 * 调试通道读一次 stats 不会把这一路永久打死。
 *
 * ## 阈值的取值依据
 *
 * - **UI 帧循环 1 000 ms**：这条回路是 Choreographer 帧节拍（`postOnAnimation` 续排，
 *   `MainActivity.pump` :339-372），正常 16 ms 一跳；一秒跳不到一次就是玩家眼里的「画面冻住」。
 *   不取 500 ms：真机大帧/GC 暂停能到几百毫秒（2026-10-22 的抖动收口就踩在那条上，
 *   :347-350 的注释），把一次抖动报成冻结会灌噪声。不取 ANR 的 5 s：那已经比玩家报的
 *   「点没反应」晚太多，读不到先后。绝对抖动上界未取证 ⇒ `TODO(verify)`。
 * - **渲染发布 5 000 ms**：与 [RunTrail.LONG_TURN_MS]（app/RunWatch.kt:419）同值同理由 ——
 *   收件箱里同一类页面平时 `turn 2ms`、贴顶时 `58515ms`，5 秒就是玩家嘴里「卡死」的下沿。
 *   注意静止屏（玩家在读一页文字、没提交输入）**本就该**长期不发布，所以这一路的超期
 *   必须与脚本侧并读才有意义（见 [verdict]）。
 * - **脚本线程 5 000 ms**：同一个 5 s 下沿；但口径要说清 —— 回合边界与页中检查点都是
 *   「脚本在指令循环里」的证据，玩家在读屏时两条都不来（线程 parked 在输入队列），
 *   所以这一路超期的含义是**不在指令循环里**（parked / 卡在语句外 / 未接线），不是「卡死」。
 * - **钉轨迹的冷却 30 000 ms**：与 [RunTrail.RELEASE_COOLDOWN_MS]（:311）同值 —— 页中
 *   检查点本身就是 250 ms 一档，判读若每帧都钉，64 行滚动轨迹会被同一次冻结灌满。
 */
object HeartbeatJudgment {

    /** 报告小节的第一行（端点侧与人工都按这一行找读数）。 */
    const val SECTION_HEADER = "[线程心跳（末次动作距今）]"

    /** 读不到 / 未接线时写死的那句：不许拿「未采到」冒充读数，也不许留空行。 */
    const val NOT_MEASURED = "未采到"

    /** 各路超期阈值（毫秒）。 */
    data class Thresholds(val uiMs: Long, val publishMs: Long, val vmMs: Long)

    /** 缺省阈值：见类头「阈值的取值依据」。 */
    val DEFAULT_THRESHOLDS = Thresholds(uiMs = 1_000L, publishMs = 5_000L, vmMs = 5_000L)

    /** [trailSuffix] 里未采到那一路的占位（短，轨迹行要为体积让路）。 */
    private const val TRAIL_NEVER = "-"

    /** 超期判据：从未采到（[Heartbeat.NEVER]）**不判超期** —— 没数据不许谎报成冻结。 */
    fun ageMs(lastAtMs: Long, nowMs: Long): Long? =
        if (lastAtMs == Heartbeat.NEVER) null else (nowMs - lastAtMs).coerceAtLeast(0L)

    /** 这一路超期了没有。`age == null`（未采到）一律 false。 */
    fun isStale(ageMs: Long?, thresholdMs: Long): Boolean = ageMs != null && ageMs >= thresholdMs

    /** 某一路的阈值。 */
    fun thresholdOf(thresholds: Thresholds, channel: HeartbeatChannel): Long = when (channel) {
        HeartbeatChannel.UI_FRAME -> thresholds.uiMs
        HeartbeatChannel.RENDER_PUBLISH -> thresholds.publishMs
        HeartbeatChannel.VM -> thresholds.vmMs
    }

    /**
     * 「谁先停」的组合判读：单路超期不足以定论，必须并读三路。
     *
     * 形状与含义（`ui` = UI 帧循环、`publish` = 渲染发布、`vm` = 脚本线程）：
     * - `ui` 超期 + `vm` 在动 ⇒ [Verdict.UI_LOOP_STALLED]：**先停的是 UI 帧循环** —— 脚本还在
     *   出页、没人把它拉到屏上，玩家看到的就是「画面静止但游戏其实在跑」；
     * - 三路全超期 ⇒ [Verdict.PROCESS_FROZEN]：进程被系统冻结 / 已经死了 / 心跳根本没接上；
     * - `ui` 在动 + `vm` 超期 ⇒ [Verdict.VM_NOT_IN_LOOP]：脚本线程不在指令循环里
     *   （玩家在读屏属正常，配合轨迹里的 `turn`/`longturn` 才判得出卡死）；
     * - `ui` 在动 + `vm` 在动 + `publish` 超期 ⇒ [Verdict.SNAPSHOT_NOT_PUBLISHED]：
     *   **长页那一分钟的形状**（58 秒里水位不动是因为页没画完）或发布链断；
     * - 任一路从未采到 ⇒ [Verdict.UNREAD]：不硬凑结论 —— 本波开工时脚本侧那一路
     *   还在 `GameHost`（obs-turnpct 独占文件）等组长接线，报告里必须分得清
     *   「没接线」与「接了但停在等待点」。三路都没采到才是 [Verdict.NO_DATA]。
     */
    fun verdict(snapshot: HeartbeatSnapshot, nowMs: Long, thresholds: Thresholds = DEFAULT_THRESHOLDS): Verdict {
        val uiAge = snapshot.ageMs(HeartbeatChannel.UI_FRAME, nowMs)
        val publishAge = snapshot.ageMs(HeartbeatChannel.RENDER_PUBLISH, nowMs)
        val vmAge = snapshot.ageMs(HeartbeatChannel.VM, nowMs)
        if (uiAge == null && publishAge == null && vmAge == null) return Verdict.NO_DATA
        if (uiAge == null || publishAge == null || vmAge == null) return Verdict.UNREAD
        val uiStale = isStale(uiAge, thresholds.uiMs)
        val publishStale = isStale(publishAge, thresholds.publishMs)
        val vmStale = isStale(vmAge, thresholds.vmMs)
        return when {
            uiStale && vmStale && publishStale -> Verdict.PROCESS_FROZEN
            uiStale -> Verdict.UI_LOOP_STALLED
            vmStale -> Verdict.VM_NOT_IN_LOOP
            publishStale -> Verdict.SNAPSHOT_NOT_PUBLISHED
            else -> Verdict.HEALTHY
        }
    }

    /** 判读枚举：`key` 给端点侧 grep，`text` 是报告里的人话。 */
    enum class Verdict(val key: String, val text: String) {
        NO_DATA("no-data", "三路都没有读数（本进程还没跑到打点，或心跳没接上）"),
        HEALTHY("healthy", "三路都在动，未见冻结"),
        UI_LOOP_STALLED("ui-stalled", "UI 帧循环先停（脚本侧仍在动）——「画面静止但游戏在跑」这一类"),
        PROCESS_FROZEN("all-stalled", "三路全部超期（整进程被冻结 / 已死 / 打点未接）"),
        VM_NOT_IN_LOOP("vm-idle", "脚本线程不在指令循环里（等待输入属正常；要并读轨迹的 turn/longturn）"),
        SNAPSHOT_NOT_PUBLISHED("publish-stalled", "UI 与脚本都在动，但没有新快照到屏（长页期间属正常，否则是发布链断）"),
        UNREAD("unread", "读数不完整，不做结论（逐路见上）"),
    }

    /** 三路的固定顺序（报告与轨迹都按它排；用不可变表而不是每帧克隆 values()）。 */
    val CHANNELS: List<HeartbeatChannel> = listOf(
        HeartbeatChannel.UI_FRAME,
        HeartbeatChannel.RENDER_PUBLISH,
        HeartbeatChannel.VM,
    )

    /**
     * 报告小节：一行表头 + 每路一行读数 + 一行判读。
     *
     * `snapshot == null` 也要**成节**（写「未采到」），这样端点侧每份报告都能判断这一路
     * 到底有没有接上，而不是「这节没了 = 大概是没做」。
     */
    fun freezeLines(
        nowMs: Long,
        snapshot: HeartbeatSnapshot?,
        thresholds: Thresholds = DEFAULT_THRESHOLDS,
    ): List<String> {
        val out = ArrayList<String>(5)
        out += SECTION_HEADER
        if (snapshot == null) {
            CHANNELS.forEach { out += "${it.key} $NOT_MEASURED（没有心跳实例）" }
            out += "${Verdict.NO_DATA.key} ${Verdict.NO_DATA.text}"
            return out
        }
        CHANNELS.forEach { channel ->
            out += formatReading(channel, snapshot.reading(channel), nowMs, thresholdOf(thresholds, channel))
        }
        val verdict = verdict(snapshot, nowMs, thresholds)
        out += "${verdict.key} ${verdict.text}"
        return out
    }

    /** 一路读数一行：`ui age=120ms count=8421 last=[四段全成] 未超期(阈值1000ms)`。 */
    fun formatReading(
        channel: HeartbeatChannel,
        reading: HeartbeatReading?,
        nowMs: Long,
        thresholdMs: Long,
    ): String {
        val age = ageMs(reading?.lastAtMs ?: Heartbeat.NEVER, nowMs)
            ?: return "${channel.key} $NOT_MEASURED（本路未打点或未跑到）"
        val tail = if (isStale(age, thresholdMs)) "超期(阈值${thresholdMs}ms)" else "未超期(阈值${thresholdMs}ms)"
        val action = reading?.lastAction?.takeIf { it.isNotBlank() } ?: "-"
        return "${channel.key} age=${age}ms count=${reading?.count ?: 0L} last=[$action] $tail"
    }

    /**
     * 轨迹行尾的心跳段：`hb=ui:120/publish:58000/vm:-`（单位 ms，`-` = 未采到）。
     *
     * 为什么要挤进轨迹行：「死在屏前」那一类报告是**下一次冷启动**由 [RunWatch] 落的，
     * 那时活的心跳早没了；而轨迹文件在死前就被反复落盘（[RunTrail.shouldWrite] 2 s 一档），
     * 最后一行的 `hb=` 段就是死前三路各自最后一次动作距今多久 —— 这是唯一能把
     * 「谁先停」带到报告里的东西。体积：约 30 B/行，64 行上限约 2 KB，仍在报告预算内。
     */
    fun trailSuffix(nowMs: Long, snapshot: HeartbeatSnapshot?): String {
        if (snapshot == null) return ""
        val parts = CHANNELS.map { channel ->
            val age = snapshot.ageMs(channel, nowMs)
            "${channel.key}:${age ?: TRAIL_NEVER}"
        }
        return " hb=${parts.joinToString("/")}"
    }

    /**
     * 要不要为这次冻结钉一行轨迹（只有「绝不是正常态」的形状才钉，避免静止屏灌满 64 行轨迹）。
     *
     * 钉的三种形状：[Verdict.UI_LOOP_STALLED]、[Verdict.PROCESS_FROZEN]、
     * [Verdict.SNAPSHOT_NOT_PUBLISHED]（长页那一分钟正是这一路，主车的 `longturn` 行讲的是
     * 脚本在算哪一页，这一行讲的是「算了但屏上没有新东西」——两条并排才是完整形状）。
     * **不钉** [Verdict.VM_NOT_IN_LOOP]：玩家读屏时脚本本就停在等待点，钉了就是每天几百行噪声。
     *
     * @param sinceLastPinMs 距上一次钉 `freeze` 行的毫秒；负数 = 还没钉过（首钉必走）
     */
    fun shouldPinFreeze(
        nowMs: Long,
        sinceLastPinMs: Long,
        snapshot: HeartbeatSnapshot?,
        thresholds: Thresholds = DEFAULT_THRESHOLDS,
    ): Boolean {
        if (snapshot == null) return false
        if (sinceLastPinMs >= 0L && sinceLastPinMs < FREEZE_PIN_MIN_INTERVAL_MS) return false
        val verdict = verdict(snapshot, nowMs, thresholds)
        return verdict == Verdict.UI_LOOP_STALLED ||
            verdict == Verdict.PROCESS_FROZEN ||
            verdict == Verdict.SNAPSHOT_NOT_PUBLISHED
    }

    /** [shouldPinFreeze] 的钉发冷却（毫秒），依据见类头阈值一节。 */
    const val FREEZE_PIN_MIN_INTERVAL_MS = 30_000L

    /** `freeze` 轨迹行的阶段名（与 `turn` / `longturn` / `trim` 并列，端点侧按前缀分桶）。 */
    const val FREEZE_PHASE = "freeze"
}
