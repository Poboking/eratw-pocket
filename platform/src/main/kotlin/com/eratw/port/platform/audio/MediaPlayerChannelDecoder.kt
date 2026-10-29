package com.eratw.port.platform.audio

import android.annotation.TargetApi
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import com.eratw.port.core.audio.ChannelDecoder
import com.eratw.port.core.audio.LoopMode
import com.eratw.port.core.audio.LoopPlan
import com.eratw.port.core.audio.SoundChannel
import java.io.File

/**
 * Android 侧的真实解码流：一条 [MediaPlayer] 包成一个 [ChannelDecoder]。
 *
 * 映射关系（原版把同样的事直接做在 NAudio 采样链上）：
 * - 时长 / 位置：`GetTotalTime` / `GetCurrentTime` 在原版是「字节位置 ÷ 采样率×声道×4」
 *   （Sound.cs:31-57），除数为 0 或流为 null 时返回 0.0（:38-41、:52-55）。MediaPlayer 直接给
 *   毫秒，本类换算成秒，并复刻同一守卫：未 prepare / 已 release / 平台报错 / 非正值一律 0.0。
 * - 定位：原版把秒换算成字节并 Clamp 到 0..Length（Sound.cs:208-221）；本类换算成毫秒并
 *   Clamp 到 0..时长（调用方 [SoundChannel] 已按接口约定夹过一次，这里再夹一次防御）。
 * - 播放钩子：`onStarted` / `onPaused` / `onResumed` / `onStopped` 对应
 *   [MediaPlayer.start] / [MediaPlayer.pause] / [MediaPlayer.start] / [MediaPlayer.stop]；
 *   `onVolume` 对应 `volumeProvider.Volume = volume`（Sound.cs:183-186，线性 0..1）；
 *   `onSpeed` 对应 `varispeedProvider.PlaybackRate`（Sound.cs:194-201，已夹 0.1..10）；
 *   `onPreservePitch` 对应 `SetSoundTouchProfile`（Sound.cs:242-248）；
 *   `onRepeat` 对应 LoopStream / RepeatStream 的包装（Sound.cs:97-105，PLAYBGM 固定 -1，
 *   FunctionIdentifier.cs:2696）。
 *
 * ## 变速
 * API 23+ 用 [PlaybackParams]：`speed = rate`，音高保留时 pitch 固定 1.0（平台的 Sonic 做
 * 时域伸缩，等价 SoundTouch），关闭音高保留时把 pitch 也设成 rate（等价重采样变调）。
 * API 21-22 没有 PlaybackParams，`onSpeed` 只记字段、不下发（no-op）。
 * TODO(verify)：Android 21-22 的变速降级口径（静音变速还是保持原速）；卡点＝本机没有
 * 21-22 设备，且平台没有等价 API。真机播放、音频焦点（AudioFocus 申请/丢失）、
 * 格式覆盖（语料 `sound/` 只有 mp3，MediaPlayer 对 mp3/ogg/wav 的支持随 ROM 而异）、
 * 以及 `setPlaybackParams` 在各 ROM 上的行为都未验证 —— 全部登记为 TODO(verify)。
 *
 * ## 状态机的自我保护
 * MediaPlayer 的方法在非法状态会抛 IllegalStateException（例如 stop 后再 start）。本类把
 * 这些平台状态异常吞掉（[SoundChannel] 的可见状态仍按原版模型走），只让构造期的
 * setDataSource / prepare 失败上抛 —— 那对应原版开流失败（Sound.cs:83-96）→
 * FunctionIdentifier.cs:2647-2650 / :2699-2702 的裸 catch → CodeEE。
 *
 * ## 自然播完回灌
 * 非循环流读完最后一遍后，完成监听器经 [setNaturalEndListener] 通知 [SoundChannel]，
 * 由后者交给 `AudioMixer.onInputEnded`（SoundMixer.cs:31、:39-42）。循环流不回调；
 * `repeat > 1` 只有最后一遍回调；同一条流最多回调一次，[release] 后不再回调。
 * 音频焦点**不经本类**：宿主把 `AudioFocusSession` 的事件送进 `AudioMixer.onFocusEvent`，
 * 动作以 [ChannelDecoder.onPaused] / [ChannelDecoder.onResumed] 的形式到达（GameHost 的
 * 焦点接线，M6-AUDIO-FOCUS）；真机上的焦点行为验证仍登记在类头 TODO(verify)。
 *
 * ## 线程模型
 * 命令侧（play/stop/pause/seek/release/onRepeat 等）由 [SoundChannel] 在 **VM 线程**调用；
 * 而 MediaPlayer 在 VM 线程（无 Looper）创建后，AOSP 会把事件回调投递到 **主 looper**——
 * 即 [onCompleted] 在 UI 线程执行。两个线程无同步地交叉读写同一组状态字段会产生竞态
 * （典型：VM 线程 `release()` 与 UI 线程重播逻辑并发，导致对正在释放的播放器 start）。
 * 因此：状态字段一律 `@Volatile`（保证单次读写可见性）；所有「读-判断-动作」复合操作
 * 统一持有 [stateLock] 串行化。铁律：**锁内绝不调用任何外部回调/监听器**（监听器可能
 * 回调回本对象或 SoundChannel，锁内调用有死锁风险）——先在锁内取出引用，移到锁外调用。
 * MediaPlayer 系统调用（start/seekTo/pause 等）保持在锁内：文档未禁止，且这本来就是
 * 跨线程乱序的调用面，锁内串行化恰好修复了命令乱序；声道级粒度可接受。
 */
class MediaPlayerChannelDecoder(file: File) : ChannelDecoder {

    private val player = MediaPlayer()

    // 以下状态字段跨线程共享（命令在 VM 线程、完成回调在 UI 线程）：
    // @Volatile 保证可见性；「读-判断-动作」复合操作必须持有 [stateLock]。

    /** 复合状态操作的串行化锁；锁内禁止调用外部回调/监听器（防死锁）。 */
    private val stateLock = Any()

    /** prepare 成功；构造失败会直接抛，不会留下半初始化的实例。 */
    @Volatile
    private var prepared = false

    /** [release] 之后所有 API 一律 no-op，时间读数回 0.0。 */
    @Volatile
    private var released = false

    /** 「平台侧正在播放」——只在播放态下发变速参数（非播放态 setPlaybackParams 会拉起重播）。 */
    @Volatile
    private var started = false

    /** repeat == -1（Sound.cs:98-101）。 */
    @Volatile
    private var looping = false

    /** repeat > 1 时剩余的重播次数（含当前这一遍）。UI/VM 双线程读写，须进锁防次数错乱。 */
    @Volatile
    private var repeatsRemaining = 1

    /** 变速参数：VM 线程写，UI 线程（onCompleted → startPlayback → applyTransport）读。 */
    @Volatile
    private var preservePitch = true

    @Volatile
    private var rate = 1f

    /** 自然播完反向通道；[setNaturalEndListener] 挂/摘，[onCompleted] 一次性消费。 */
    @Volatile
    private var naturalEndListener: (() -> Unit)? = null

    init {
        try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            prepared = true
            player.setOnCompletionListener { onCompleted() }
        } catch (e: Exception) {
            // 构造失败必须把平台资源还回去，再让异常穿到语句层的裸 catch（FunctionIdentifier.cs:2647）。
            released = true
            player.release()
            throw e
        }
    }

    // ------------------------------------------------------------------
    // 时间基（Sound.cs:31-57）
    // ------------------------------------------------------------------

    /** 总时长（秒）；未知 / 已释放 / 平台报错 ⇒ 0.0（Sound.cs:38-41、:52-55 的除零守卫口径）。 */
    override val totalSeconds: Double
        get() = synchronized(stateLock) {
            if (released) return@synchronized 0.0
            val millis = try {
                player.duration
            } catch (_: IllegalStateException) {
                -1
            }
            if (millis > 0) millis / MILLIS_PER_SECOND else 0.0
        }

    /** 当前位置（秒）；取不到 ⇒ 0.0。 */
    override val positionSeconds: Double
        get() = synchronized(stateLock) {
            if (released) return@synchronized 0.0
            val millis = try {
                player.currentPosition
            } catch (_: IllegalStateException) {
                -1
            }
            if (millis > 0) millis / MILLIS_PER_SECOND else 0.0
        }

    /** 定位（秒）。对应原版 Seek 的「换算 + Clamp + 赋值」（Sound.cs:208-221）。 */
    override fun seekTo(seconds: Double) {
        val total = totalSeconds
        val clamped = if (total > 0.0) seconds.coerceIn(0.0, total) else 0.0
        seekToMillis((clamped * MILLIS_PER_SECOND).toInt())
    }

    /**
     * 释放平台播放器（原版 `stream.Dispose()`，Sound.cs:160）；幂等。
     * 与 UI 线程的 [onCompleted] 竞态核心点：released 置位 → 状态清理 → `player.release()`
     * 必须整体进 [stateLock]，否则 onCompleted 可能读到过期的 released=false，
     * 对正在释放的播放器并发 seekTo+start（未定义行为 + 已 stop 的音效被错误重播）。
     */
    override fun release() {
        synchronized(stateLock) {
            if (released) return
            released = true
            started = false
            naturalEndListener = null
            player.release()
        }
    }

    /** [ChannelDecoder.setNaturalEndListener]：UI 线程读、VM 线程写，字段已 `@Volatile`；
     *  写入是单次赋值，消费端（onCompleted / release）在锁内取引用、锁外调用。 */
    override fun setNaturalEndListener(listener: (() -> Unit)?) {
        naturalEndListener = listener
    }

    // ------------------------------------------------------------------
    // 播放钩子（Sound.cs:64-169、:180-206、:242-249）
    // ------------------------------------------------------------------

    /** 原版 `SoundMixer.PlaySound`（SoundMixer.cs:73-77）在平台侧 = 开始播放。 */
    override fun onStarted() {
        startPlayback()
    }

    /** 原版 `paused = true` + 摘混音器（Sound.cs:133-134）。 */
    override fun onPaused() {
        synchronized(stateLock) {
            started = false
            if (!prepared || released) return
            try {
                player.pause()
            } catch (_: IllegalStateException) {
                // 平台已在结束/停止态：保持模型口径，不把异常甩回语句层。
            }
        }
    }

    /** 原版 `paused = false` + 挂混音器（Sound.cs:142-147）。 */
    override fun onResumed() {
        startPlayback()
    }

    /** 原版 `SoundMixer.StopSound`（SoundMixer.cs:79-83）；随后 [release] 会释放播放器。 */
    override fun onStopped() {
        synchronized(stateLock) {
            started = false
            if (!prepared || released) return
            try {
                player.stop()
            } catch (_: IllegalStateException) {
                // stop 之后必须重新 prepare 才能再 start；SoundChannel 紧接着就 release，不会复用。
            }
        }
    }

    /** 线性音量 0..1（Sound.cs:183-186）；进入前已由 [SoundChannel.setVolume] 夹过。 */
    override fun onVolume(linear: Float) {
        val level = linear.coerceIn(0f, 1f)
        synchronized(stateLock) {
            if (released) return
            try {
                player.setVolume(level, level)
            } catch (_: IllegalStateException) {
                // 播放器状态不允许时忽略；音量字段在模型侧另有存储。
            }
        }
    }

    /**
     * 变速倍率（Sound.cs:198-199）。播放中才下发；API 23 以下只记字段（见类头 TODO(verify)）。
     * 非播放态不下发：`setPlaybackParams` 在暂停/停止态会顺手把播放拉起来。
     */
    override fun onSpeed(rate: Float) {
        synchronized(stateLock) {
            // 再夹一次：Sound.cs:198 的 Clamp 是原版唯一的写入口，直接持有本类的调用方也得守它。
            this.rate = rate.coerceIn(SoundChannel.SPEED_MIN, SoundChannel.SPEED_MAX)
            applyTransport()
        }
    }

    /** 音高保留开关（Sound.cs:242-248）；同上，只在播放态下发。 */
    override fun onPreservePitch(preserve: Boolean) {
        synchronized(stateLock) {
            preservePitch = preserve
            applyTransport()
        }
    }

    /**
     * 播放次数（Sound.cs:97-105）：-1 交给平台的 `isLooping`，>1 由完成回调手动重播，
     * 其余按一遍算。三分类走 [LoopPlan.of]（与模型侧同一份判定，不在这里另写一套 `-1` 比较）。
     * repeatsRemaining 与 onCompleted（UI 线程）双线程读写，须进锁。
     */
    override fun onRepeat(repeat: Int) {
        synchronized(stateLock) {
            val plan = LoopPlan.of(repeat)
            looping = plan.looping
            // FOREVER 的 plays 是哨兵（Int.MAX_VALUE），循环由平台 isLooping 负责，不参与计数。
            repeatsRemaining = if (plan.mode == LoopMode.REPEAT) plan.plays else 1
            if (!prepared || released) return
            try {
                player.isLooping = looping
            } catch (_: IllegalStateException) {
                // 平台拒绝时保持单遍播放；循环口径已在模型侧（SoundChannel.looping）记录。
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun startPlayback() {
        synchronized(stateLock) {
            if (!prepared || released) return
            try {
                player.start()
            } catch (_: IllegalStateException) {
                return
            }
            started = true
            applyTransport()
        }
    }

    /** 只在播放态下发变速 / 音高；API 21-22 无 PlaybackParams，直接 no-op。 */
    private fun applyTransport() {
        synchronized(stateLock) {
            if (!started || !prepared || released) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            applyPlaybackParams(rate, preservePitch)
        }
    }

    /**
     * API 23+ 的变速 / 变调。音高保留时 pitch 用平台缺省的 1.0（时域伸缩），
     * 关闭时 pitch 跟 rate 走（等价原版用重采样实现的「不保留音高」）。
     */
    @TargetApi(Build.VERSION_CODES.M)
    private fun applyPlaybackParams(rate: Float, preservePitch: Boolean) {
        val params = PlaybackParams().setSpeed(rate)
        if (!preservePitch) {
            params.pitch = rate
        }
        try {
            player.playbackParams = params
        } catch (_: IllegalStateException) {
            // 非播放态或 ROM 不支持：下一次 onStarted 会重试。
        } catch (_: IllegalArgumentException) {
            // 平台拒绝的参数组合：保持原速，不让它冒泡成 CodeEE（模型侧读数不变）。
        }
    }

    /**
     * 自然播完：非循环且还有剩余遍数时从头再来（原版 RepeatStream，Sound.cs:102-104）。
     * 本方法在 **UI 线程**（主 looper）执行，与 VM 线程的 release/play 等并发：
     * 「检查 released → 决定重播 → 递减 repeatsRemaining → seekTo/start」是跨字段的
     * 复合决策，必须整体持有 [stateLock]，否则会与 release 竞态（对正在释放的播放器
     * start）或把重播次数减乱。监听器引用在锁内取出，**锁外调用**——它可能回调回
     * 本对象或 SoundChannel，锁内调用有死锁风险。
     */
    private fun onCompleted() {
        val listener = synchronized(stateLock) {
            if (released) return
            // 循环由平台 isLooping 负责；即使某些 ROM 也回调本监听器，也不能把 started 清掉。
            if (looping) return
            if (repeatsRemaining > 1) {
                repeatsRemaining--
                if (seekToMillis(0)) {
                    startPlayback()
                    return
                }
            }
            started = false
            // 一次性消费：同一条流最多一次完成通知；重复投递/迟到投递不再触发。
            val l = naturalEndListener
            naturalEndListener = null
            l
        }
        listener?.invoke()
    }

    private fun seekToMillis(positionMillis: Int): Boolean {
        synchronized(stateLock) {
            if (!prepared || released) return false
            return try {
                player.seekTo(positionMillis)
                true
            } catch (_: IllegalStateException) {
                false
            }
        }
    }

    private companion object {
        /** 秒 → 毫秒：MediaPlayer 的时间读数与定位都是毫秒（Sound.cs:208-221 的字节口径的对位量）。 */
        const val MILLIS_PER_SECOND = 1000.0
    }
}
