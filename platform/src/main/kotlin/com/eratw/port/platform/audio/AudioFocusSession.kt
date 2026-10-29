package com.eratw.port.platform.audio

import com.eratw.port.core.audio.AudioFocusEvent

/**
 * 平台原始焦点变化 —— `AudioManager.OnAudioFocusChangeListener` 的入参四值。
 *
 * 单独一层枚举的目的：**纯 JVM 可测**。本文件不 import 任何 `android.*`，
 * `AudioManager` 常量到本枚举的数值翻译在下面的 [AudioFocusRaw] 里完成（同样不碰
 * Android API，单测直接读），真机侧 [AndroidAudioFocusRequester] 只负责把监听器回调
 * 送进来。单测因此不需要 Robolectric、也不会撞上 android.jar 的 "not mocked"。
 */
enum class AudioFocusChange {
    /** `AUDIOFOCUS_GAIN`。 */
    GAIN,

    /** `AUDIOFOCUS_LOSS`（长期失焦，对方不会自动还回来）。 */
    LOSS,

    /** `AUDIOFOCUS_LOSS_TRANSIENT`。 */
    LOSS_TRANSIENT,

    /** `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`（允许闪避，本移植侧按瞬态暂停处理）。 */
    LOSS_TRANSIENT_CAN_DUCK,
}

/**
 * 平台原始常量 → 本包枚举的**唯一**翻译表，放在纯 JVM 侧是为了可单测：
 * `android.media.AudioManager` 的常量在 `:platform` 单测里既不能读（android.jar 是
 * "not mocked" 桩）也不该读，所以数值以常量形式在这里复刻一份。
 *
 * 数值取自 `android-35/android.jar` 的 `javap -constants android.media.AudioManager`：
 * `AUDIOFOCUS_GAIN = 1`、`AUDIOFOCUS_LOSS = -1`、`AUDIOFOCUS_LOSS_TRANSIENT = -2`、
 * `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3`、`AUDIOFOCUS_NONE = 0`。
 * 请求侧的 `GAIN_TRANSIENT(2)` / `GAIN_TRANSIENT_MAY_DUCK(3)` / `GAIN_TRANSIENT_EXCLUSIVE(4)`
 * 不是回调值，正常 ROM 不会送进监听器。
 *
 * ## 未知常量的兜底（本波改掉旧的「一律当瞬态」）
 * 旧实现把任何不认识的数都映射成 [AudioFocusChange.LOSS_TRANSIENT]。问题是它掩盖了
 * 两种方向相反的错：把厂商的「gain 类」新常量当失焦会永久暂停，把「loss 类」当失焦
 * 反而恰好安全。现在按 AOSP 的符号约定分流：
 * - 未知**负数** ⇒ [AudioFocusChange.LOSS_TRANSIENT]。AOSP 的三个失焦常量全是负数，
 *   未知负数只可能是失焦类；按瞬态挂起（不升级成永久）是保守且可恢复的选择 ——
 *   下一次 `GAIN` 或 `onResume` 都能把声道拉回来；
 * - 未知**零/正数** ⇒ null（忽略）。`AUDIOFOCUS_NONE` 是 0，gain 类都是正数；
 *   在不知道对方语义时**绝不能**合成 `GAIN`（那会把后台的声道拉起来出声），也不该
 *   暂停（可能只是一条无关通知）。
 * 调用方（[AndroidAudioFocusRequester]）收到 null 只记一条日志，不投递事件。
 */
object AudioFocusRaw {

    /** `AUDIOFOCUS_GAIN`。 */
    const val GAIN: Int = 1

    /** `AUDIOFOCUS_LOSS`。 */
    const val LOSS: Int = -1

    /** `AUDIOFOCUS_LOSS_TRANSIENT`。 */
    const val LOSS_TRANSIENT: Int = -2

    /** `AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK`。 */
    const val LOSS_TRANSIENT_CAN_DUCK: Int = -3

    /** `AUDIOFOCUS_NONE`（不会作为回调值出现，列出来只是为了注释完整）。 */
    const val NONE: Int = 0

    /**
     * 原始常量 → 四值枚举；null = 未知且非负，必须忽略（理由见本对象 KDoc）。
     * 未知负数一律归 [AudioFocusChange.LOSS_TRANSIENT]。
     */
    fun toChange(raw: Int): AudioFocusChange? = when (raw) {
        GAIN -> AudioFocusChange.GAIN
        LOSS -> AudioFocusChange.LOSS
        LOSS_TRANSIENT -> AudioFocusChange.LOSS_TRANSIENT
        LOSS_TRANSIENT_CAN_DUCK -> AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK
        else -> if (raw < 0) AudioFocusChange.LOSS_TRANSIENT else null
    }
}

/**
 * 原始四值 → 核心三值的**唯一映射表**。
 *
 * `..._CAN_DUCK` 归到 [AudioFocusEvent.LOSS_TRANSIENT]：原版音量只有脚本写入口
 * （`SETSOUNDVOLUME` / `SETBGMVOLUME`，FunctionIdentifier.cs:2731-2738、:2753），
 * 临时压低会污染 `getVolume()` 读数（Sound.cs:189-192），且 `GAIN` 时还要恢复原值；
 * 直接暂停既不改读数，也和「没有音频焦点」的最终态一致。
 */
object AudioFocusMapping {
    /** 四值归一；没有 null 分支 —— 未知平台常量在 [AudioFocusRaw] 就已经被分流掉。 */
    fun toEvent(change: AudioFocusChange): AudioFocusEvent = when (change) {
        AudioFocusChange.GAIN -> AudioFocusEvent.GAIN
        AudioFocusChange.LOSS -> AudioFocusEvent.LOSS
        AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK ->
            AudioFocusEvent.LOSS_TRANSIENT
    }

    /**
     * 平台原始常量一步到核心事件；null = 未知且非负、应当忽略
     * （分流规则与理由见 [AudioFocusRaw]）。
     */
    fun fromRaw(raw: Int): AudioFocusEvent? =
        AudioFocusRaw.toChange(raw)?.let(::toEvent)

    /** 平台原始常量到四值枚举；null 同 [fromRaw]。给 [AndroidAudioFocusRequester] 用。 */
    fun fromRawChange(raw: Int): AudioFocusChange? = AudioFocusRaw.toChange(raw)
}

/**
 * 焦点申请方 —— 对 `AudioManager` 的最小抽象（唯一实现是 [AndroidAudioFocusRequester]）。
 *
 * 抽出接口是为了让 [AudioFocusSession] 的生命周期/去重逻辑能在纯 JVM 单测里跑：
 * 假实现只记「acquire 几次、abandon 几次」，并手动投递 [AudioFocusChange]。
 */
interface AudioFocusRequester {
    /**
     * 申请音频焦点。实现方必须在拿到焦点后**保留** [onChange] 并持续投递平台回调，
     * 直到 [abandon]。
     *
     * @return true = `AUDIOFOCUS_REQUEST_GRANTED`。
     */
    fun acquire(onChange: (AudioFocusChange) -> Unit): Boolean

    /** 放弃焦点；幂等（重复调用不应崩、不应重复通知平台）。 */
    fun abandon()
}

/**
 * 焦点事件的消费端 —— 生产接线是 [com.eratw.port.core.audio.AudioMixer.onFocusEvent]。
 *
 * 返回「本次真正改变传输状态的声道数」，只用于日志与测试；0 = 幂等空转。
 */
fun interface AudioFocusTarget {
    fun onFocusEvent(event: AudioFocusEvent): Int
}

/**
 * 焦点会话的**阶段**。比单个 `holdsFocus` 布尔多记一件事：「没持有焦点」是因为
 * **宿主主动切后台**（[HOST_SUSPENDED]，迟到的 `GAIN` 必须吞掉），还是**前台被其他
 * 播放器永久抢走**（[DEFEATED]，迟到的 `GAIN` 是可用的恢复信号）。旧实现只有布尔时
 * 这两种处境无法区分，DEFEATED 之后唯一的恢复点只剩 `onResume` —— 用户不离开应用
 * BGM 就永久静音（DEV-06 的缺陷本体）。
 */
internal enum class FocusPhase {
    /** 初始 / 已 [AudioFocusSession.release]：从未申请或会话作废。 */
    IDLE,

    /** 持有焦点（或系统 granted 后自认为持有）。 */
    HOLDING,

    /** 宿主切后台（`onHostPaused`，含 DEFEATED 之后再切后台）：等待 `onResume`。 */
    HOST_SUSPENDED,

    /** 前台被永久抢占（平台 `LOSS`）：会话仍活着，保留可重申请状态。 */
    DEFEATED,
}

/**
 * `GAIN` 到达时的处置策略 —— 状态机里唯一有分支的决策点，抽成纯函数便于单测直钉。
 *
 * - [FORWARD]（[FocusPhase.HOLDING]）：正常转发，去重交给 [AudioFocusSession.deliver]。
 * - [REACQUIRE]（[FocusPhase.DEFEATED] 且宿主在前台）：前台被永久抢走后迟到的 `GAIN` 按
 *   **可用恢复**处理 —— 立即重新申请；拿到就把阶段推回 HOLDING 并转发 `GAIN`，拿不到维持 DEFEATED。
 * - [DROP]（[FocusPhase.IDLE] / [FocusPhase.HOST_SUSPENDED] / 后台中的 DEFEATED）：
 *   从未申请过、宿主明确切了后台、或**后台期间被平台永久抢占**——迟到的 `GAIN` 必须丢弃，
 *   否则后台会被自动拉起来出声（明确的错误）。
 *
 * 第二个入参不是冗余：`phase` 只有四个值，而「后台」与「被永久抢占」是**正交**的两件事。
 * 后台期间平台下发 `LOSS` 时 `phase` 会从 HOST_SUSPENDED 变成 DEFEATED（[AudioFocusSession]
 * 的 LOSS 分支），若只看 `phase`，随后迟到的 `GAIN` 就会被判成 REACQUIRE 并在后台把声道拉起来。
 * 所以 `hostInBackground` 必须独立记录，见 [AudioFocusSession] 类头的边界说明。
 */
internal enum class GainPolicy { FORWARD, REACQUIRE, DROP }

/** [FocusPhase] × 宿主是否在后台 → [GainPolicy] 的唯一映射（纯函数，单测直钉）。 */
internal fun gainPolicyOnGain(phase: FocusPhase, hostInBackground: Boolean = false): GainPolicy =
    when (phase) {
        FocusPhase.HOLDING -> GainPolicy.FORWARD
        FocusPhase.DEFEATED -> if (hostInBackground) GainPolicy.DROP else GainPolicy.REACQUIRE
        FocusPhase.IDLE, FocusPhase.HOST_SUSPENDED -> GainPolicy.DROP
    }

/**
 * 焦点会话状态机（**纯 JVM**）：请求生命周期 + 事件去重，把 Activity 生命周期与
 * AudioManager 回调汇成同一条事件流。
 *
 * ## 为什么需要「只在一处响应」
 *
 * 失焦有两条来源，会同时到达：
 * 1. Activity `onPause`（用户切走 / 弹设置页）；
 * 2. `AudioManager` 的 `AUDIOFOCUS_LOSS_TRANSIENT`（来电、导航）。
 *
 * 两路各自暂停一次就会重复下发 `onPaused`（平台层虽然幂等，但会让「谁负责恢复」变得不可判定）。
 * 本类把两路**归一**到同一个会话：
 * - [onHostPaused] 主动放弃焦点并合成一条 [AudioFocusEvent.LOSS_TRANSIENT]（系统不一定会回调）；
 * - 平台回调经 [onFocusChange] 进来，`GAIN` 按 [gainPolicyOnGain] 分流：宿主切后台
 *   （HOST_SUSPENDED）时丢弃；前台被永久抢走（DEFEATED）时**重申请**（见下）。
 *
 * 去重规则：[lastEvent] 相同即丢弃。合成事件与平台事件重复时只投递一次；`AudioMixer`
 * 侧还有第二道闸（[com.eratw.port.core.audio.AudioMixer.focusScope]），两道都不依赖调用顺序。
 *
 * ## 为什么平台永久 LOSS 也要 abandon，却不报废会话
 *
 * `onHostPaused` 路径本来就会 abandon；平台主动下发的**永久** `LOSS`（来电、导航抢占）
 * 不走那条路，旧实现曾只置标志 —— 每次「来电 → 回到游戏（resume → acquire）」都会让
 * acquire 覆盖掉旧 `AudioFocusRequest` / listener 的引用，而它们在 `AudioManager` 侧的
 * 登记还在，等于永久泄漏一份申请（B2）。因此在 LOSS 分支投递事件后补一次
 * [requester.abandon]（幂等，且不改任何声道状态 —— 挂在混音器上的声道已由
 * [com.eratw.port.core.audio.AudioFocusScope.PERMANENT] 收尾）。
 *
 * 但 abandon 只是撤回平台注册，**会话状态机不报废**：阶段停在 [FocusPhase.DEFEATED]，
 * 保留「可重申请」状态。恢复点有两个：
 * 1. `onResume` / [start]：手动恢复（沿用既有路径，DEFEATED 下 acquire 成功即回 HOLDING）；
 * 2. 迟到的平台 `GAIN`：按 [GainPolicy.REACQUIRE] 自动重申请（DEV-06 修复本体）。
 *
 * **瞬态 LOSS（LOSS_TRANSIENT / CAN_DUCK）不 abandon**：瞬态失焦后系统送回 `GAIN`
 * 是预期路径，此时申请登记必须原样保留，系统才能把 granted 状态还回来；若 abandon 掉
 * 再等 GAIN，GAIN 会被 DROP 闸门丢弃，之后也没有重新申请的触发点，声道就永久挂起了。
 * 瞬态路径保持现状：不动阶段、不 abandon、等 GAIN。
 *
 * ## 平台最优取舍（原版没有音频焦点概念，ADR-005 不适用）
 *
 * 原版 Windows 没有任何焦点/失活音频行为（`MainWindow.cs:2118-2119` 是全部事件挂载点，
 * 只挂 `FormClosing` / `Shown`；全文无 `Sound` / `Bgm` 引用）—— PC 上 BGM 根本不会因
 * 外部抢占而停，所以「被抢焦点后如何恢复」没有 C# 口径可对齐，按移植侧平台最优自定
 * （波次契约 §2 L3 点名本条）。取舍如下：
 * - **前台**被永久抢占后，凡系统还愿意送回 `GAIN`（抢占方释放后部分 ROM 会补投；
 *   abandon 生效前已排队的主 looper 回调也可能补投 —— 真机侧 [AndroidAudioFocusRequester]
 *   的 abandon 会摘掉监听器，所以这条路径在标准 AOSP 上是窄窗口，但各 ROM 行为不一，
 *   不吞是更优选择），会话自动重申请并恢复：向「前台时尽量贴近 PC —— 音乐不因外部
 *   抢占而永久哑掉」倾斜；
 * - **后台**迟到的 `GAIN` 仍然一律丢弃：后台自动出声是明确错误，这条优先级最高；
 * - 系统始终不送回 `GAIN` 时，用户下次 `onResume` 必然重申请（[start] 对所有非 HOLDING
 *   阶段都会 acquire），最坏情况 = 静音到离开再回来，与旧实现的下界持平。
 *
 * ## 后台 × 永久抢占（本波补的相位边界）
 *
 * 四相之外还必须记一个正交事实：宿主在不在后台（[hostInBackground]）。原因是相位会被覆盖：
 * 切后台（HOST_SUSPENDED）之后平台又下发永久 `LOSS`，[onFocusChange] 的 LOSS 分支把相位改写成
 * DEFEATED，于是「后台」这条信息只剩那个正交标志。少了它，随后迟到的 `GAIN` 会走 REACQUIRE、
 * 在**后台**重新申请并投递 `GAIN` ⇒ 后台出声。三条边界因此固定为：
 * - 后台 + 平台 LOSS + 迟到 GAIN ⇒ DROP（不重申请、不投递）；
 * - 后台 + 迟到 GAIN（无 LOSS）⇒ DROP（相位是 HOST_SUSPENDED，既有行为）；
 * - 前台 + LOSS + 迟到 GAIN ⇒ REACQUIRE（DEV-06 修复本体，不因本波回退）。
 *
 * 另外两条抖动边界：连续 `LOSS`/`GAIN` 交替时，每次 acquire 前必有 abandon（LOSS 分支）或
 * 本就未持有（IDLE），所以**活注册数恒不超过 1**；`release` 之后 `start` 一律 false，
 * 不会在作废会话上重建注册。
 *
 * 锁安全：[requester.abandon] 在本会话锁内调用 `AudioManager.abandonAudioFocusRequest`，
 * 该调用是同步 binder 请求、不会在同一线程同步回调 listener（回调经主 looper Handler
 * 派发），且 abandon 摘除的正是该 listener，故无重入死锁面。
 *
 * ## 线程
 *
 * 焦点回调在主 looper 送达，而 [com.eratw.port.core.audio.AudioMixer] 的集合操作是加锁的、
 * `SoundChannel.focusSuspended` 是普通字段 —— 因此本类所有公开方法都 `@Synchronized`，
 * 把「主线程回调」与「VM 线程 PLAYSOUND」串行化到同一把锁上（原版没有这一层，
 * 见 [AudioFocusEvent] 的平台约束说明）。
 */
class AudioFocusSession(
    private val requester: AudioFocusRequester,
    private val target: AudioFocusTarget,
) {

    /**
     * 会话阶段（唯一状态源）。公开方法全部在锁内读写；[holdsFocus] 的 getter 在锁外读，
     * 所以加 `@Volatile`。
     */
    @Volatile
    private var phase: FocusPhase = FocusPhase.IDLE

    /** 是否持有（或自认为持有）焦点 —— 由 [phase] 派生，HOLDING 即 true。 */
    val holdsFocus: Boolean
        get() = phase == FocusPhase.HOLDING

    /**
     * 宿主是否在后台（`onHostPaused` 置真、`start` / `onHostResumed` 置假）。
     *
     * **与 [phase] 正交**，不是冗余：后台期间平台可能下发永久 `LOSS`，那会把 [phase] 从
     * [FocusPhase.HOST_SUSPENDED] 改写成 [FocusPhase.DEFEATED]（见 [onFocusChange] 的 LOSS 分支）。
     * 若只看 `phase`，随后迟到的 `GAIN` 会走 [GainPolicy.REACQUIRE] 并在**后台**把声道拉起来
     * 出声 —— 这正是本波补的边界。标记只由 [start] / [onHostPaused] 改写，平台回调不改它。
     */
    @Volatile
    private var hostInBackground: Boolean = false

    /** 上一次真正投递给 [target] 的事件（去重用）。 */
    @Volatile
    var lastEvent: AudioFocusEvent? = null
        private set

    /** [release] 之后整条会话作废：不再申请、不再转发。 */
    @Volatile
    var released: Boolean = false
        private set

    /**
     * Activity 恢复前台 / 启动：申请焦点。幂等 —— 已持有时直接返回 true，不重复申请
     * （`requestAudioFocus` 重复调用在部分 ROM 上会额外投递一条 `GAIN`）。
     *
     * 对所有非 HOLDING 阶段（含 [FocusPhase.DEFEATED] / [FocusPhase.HOST_SUSPENDED]）
     * 都会重新 acquire：这正是「永久 LOSS 后保留可重申请状态」的手动恢复点。
     *
     * 申请成功立即合成一条 [AudioFocusEvent.GAIN]：Android 在 granted 时**不保证**再回调
     * `AUDIOFOCUS_GAIN`（各版本行为不一），合成 + 去重让「首次启动」与「从后台回来」
     * 走同一条恢复路径。
     *
     * @return true = 当前持有焦点（含本来就有）。
     */
    @Synchronized
    fun start(): Boolean {
        if (released) return false
        hostInBackground = false
        if (phase == FocusPhase.HOLDING) return true
        if (!requester.acquire { change -> onFocusChange(change) }) return false
        phase = FocusPhase.HOLDING
        deliver(AudioFocusEvent.GAIN)
        return true
    }

    /**
     * Activity `onPause`：放弃焦点并合成瞬态挂起。
     *
     * 合成而非等系统回调：多数场景（打开设置页、切到桌面）系统不会投递任何焦点事件，
     * 不合成就会在后台继续出声。返回 false = 本次调用没有做「投事件 + abandon」这套动作
     * （重复 `onPause`、本来就没持有、或已被永久抢占 —— 空转）。
     *
     * [FocusPhase.DEFEATED] 下切后台：没有事件可合成（`LOSS` 已投递过、去重闸还会挡一次）、
     * 没有注册可撤（LOSS 分支已 abandon），但**必须**把阶段推进到 [FocusPhase.HOST_SUSPENDED] ——
     * 否则后台期间迟到的 `GAIN` 会按 REACQUIRE 把后台的声道拉起来出声。
     */
    @Synchronized
    fun onHostPaused(): Boolean {
        if (released) return false
        hostInBackground = true
        return when (phase) {
            FocusPhase.HOLDING -> {
                phase = FocusPhase.HOST_SUSPENDED
                deliver(AudioFocusEvent.LOSS_TRANSIENT)
                requester.abandon()
                true
            }
            FocusPhase.DEFEATED -> {
                phase = FocusPhase.HOST_SUSPENDED
                false
            }
            FocusPhase.IDLE, FocusPhase.HOST_SUSPENDED -> false
        }
    }

    /** Activity `onResume`：重新申请（内部走 [start]，因此重复 `onResume` 不会重复申请）。 */
    @Synchronized
    fun onHostResumed(): Boolean = start()

    /**
     * 平台焦点回调。
     *
     * - `GAIN`：按 [gainPolicyOnGain] 分流。HOLDING 正常转发（重复由去重闸挡）；
     *   DEFEATED **重申请**（前台被永久抢走后迟到的 `GAIN` 是可用恢复信号，取舍见类 KDoc；
     *   重申请失败则维持 DEFEATED，不投递任何事件，下一条 `GAIN` 还会再试）；
     *   IDLE / HOST_SUSPENDED 丢弃（后台被自动拉起来出声是明确错误）。
     * - `LOSS`：推入 [FocusPhase.DEFEATED]（不报废会话，保留可重申请状态）**并 abandon**
     *   （永久失焦后系统不会自动还回焦点，必须重新申请；不 abandon 会让旧
     *   `AudioFocusRequest` / listener 在 AudioManager 里永久登记 —— 理由见类 KDoc
     *   「为什么平台永久 LOSS 也要 abandon，却不报废会话」）。与 onHostPaused 同序：
     *   先投递事件、后 abandon。[hostInBackground] **不因 LOSS 改变** —— 后台被抢占时它
     *   保持为真，正是迟到的 `GAIN` 必须被丢弃的依据（见类头「后台 × 永久抢占」）。
     * - 瞬态：不动阶段、不 abandon（系统会自己送回 `GAIN`，保留登记才能拿回 granted 状态）。
     *
     * 会话已 [release] 时一律丢弃。
     */
    @Synchronized
    fun onFocusChange(change: AudioFocusChange) {
        if (released) return
        val event = AudioFocusMapping.toEvent(change)
        when (event) {
            AudioFocusEvent.GAIN -> when (gainPolicyOnGain(phase, hostInBackground)) {
                GainPolicy.FORWARD -> deliver(event)
                GainPolicy.REACQUIRE ->
                    // 迟到 GAIN 触发的重申请：走与 start 相同的干净 acquire 路径
                    // （requester 侧对残留注册有防御式清理，B2 不变量不受影响）。
                    if (requester.acquire { c -> onFocusChange(c) }) {
                        phase = FocusPhase.HOLDING
                        deliver(AudioFocusEvent.GAIN)
                    }
                GainPolicy.DROP -> Unit
            }
            AudioFocusEvent.LOSS -> {
                phase = FocusPhase.DEFEATED
                deliver(event)
                // 平台永久 LOSS：主动撤回申请（幂等），防止旧 request/listener 泄漏（B2）。
                requester.abandon()
            }
            AudioFocusEvent.LOSS_TRANSIENT -> deliver(event)
        }
    }

    /**
     * 退出：放弃焦点、作废会话。幂等（`onDestroy` 可能被回调和 Activity 各调一次）。
     */
    @Synchronized
    fun release() {
        if (released) return
        released = true
        phase = FocusPhase.IDLE
        requester.abandon()
    }

    /** 投递并去重；返回是否真的转发。 */
    private fun deliver(event: AudioFocusEvent): Boolean {
        if (lastEvent == event) return false
        lastEvent = event
        target.onFocusEvent(event)
        return true
    }
}
