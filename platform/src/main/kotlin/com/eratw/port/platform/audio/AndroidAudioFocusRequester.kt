package com.eratw.port.platform.audio

import android.annotation.TargetApi
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 真机焦点申请方：`AudioManager` 的两条 API 路径（21 兼容 + 26 新 API）。
 *
 * ## 两条路径
 *
 * - **API 26+**：`AudioFocusRequest.Builder(AUDIOFOCUS_GAIN)`
 *   + `setAudioAttributes(USAGE_GAME / CONTENT_TYPE_MUSIC)` + 变化监听器，
 *   再 `requestAudioFocus(request)` / `abandonAudioFocusRequest(request)`。
 * - **API 21-25**：`requestAudioFocus(listener, STREAM_MUSIC, AUDIOFOCUS_GAIN)`
 *   / `abandonAudioFocus(listener)`（26 起标 deprecated，但 21-25 只有这一条）。
 *
 * `USAGE_GAME` 与 `CONTENT_TYPE_MUSIC` 都是 API 21 起可用，故两路共用同一份属性构造。
 *
 * ## 与语料的关系
 *
 * 原版没有任何焦点概念（`MainWindow.cs:2118-2119` 只挂 `FormClosing` / `Shown`，全文无
 * `Sound` / `Bgm` 引用；音频只由脚本 `STOPSOUND` / `STOPBGM` 与 `Program.cs:97-104`
 * 的启动清场停止）。本类只负责「向系统提出/撤回申请」，失焦后的动作全部落在
 * [AudioFocusSession] → [com.eratw.port.core.audio.AudioMixer.onFocusEvent]。
 *
 * ## 线程
 *
 * `AudioManager` 的焦点回调在主 looper 送达；[AudioFocusSession] 已把公开方法加锁，
 * 本类只在 [acquire] / [abandon] 里持有监听器引用，不加自己的锁（调用点已在会话锁内）。
 *
 * ## 为什么 LOSS 之后也必须 abandon（B2 泄漏修复）
 *
 * 平台下发的永久 `LOSS`（来电、导航抢占）不走 `onHostPaused` 路径，[AudioFocusSession]
 * 现在会在 LOSS 分支主动调 [abandon]。旧实现的问题：acquire 无条件覆盖
 * `modernRequest` / `listener` 引用，而 LOSS 分支只置标志不 abandon —— 每次
 * 「来电 → 回到游戏（resume → acquire）」都会把上一份 `AudioFocusRequest` + listener
 * 的引用覆盖掉，但它们在 `AudioManager` 侧的登记仍在，等于永久泄漏一份申请对象，
 * 旧 listener 还会持续收到焦点回调。
 *
 * 因此本类保证两件事：
 * 1. [abandon] 幂等且**清空** `modernRequest` / `listener` 引用（丢失引用 = 泄漏源，
 *    置 null 后重复 abandon 全是 no-op）；
 * 2. [acquire] 覆盖前若发现旧引用残留（防御式兜底，正常路径上 LOSS 分支已清理），
 *    先 [abandon] 旧请求再登记新 listener。
 *
 * 安全性：`abandonAudioFocusRequest` 是同步 binder 调用，不会在同一线程上同步回调
 * listener（回调走主 looper 的 Handler 派发），所以在会话锁内调用不构成重入死锁面；
 * 且 abandon 本身就在摘除 listener，之后不可能再有回调进来。
 *
 * ## 未知常量
 * 翻译只在纯 JVM 的 [AudioFocusRaw] 做：未知负数按瞬态失焦挂起、未知零/正数忽略
 * （规则与理由见该类 KDoc）。本类只负责记一条日志，绝不把未知常量猜成 `GAIN`。
 *
 * TODO(verify)：真机上 `USAGE_GAME` 的焦点组在「其他应用播放导航」时是否给出
 * `LOSS_TRANSIENT` 还是直接 `LOSS`，以及部分 ROM 是否忽略 `setWillPauseWhenDucked` 的缺省。
 * 卡点＝无真机音频焦点事件注入装置（本机模拟器无音频）。当前按标准 AOSP 行为实现。
 */
class AndroidAudioFocusRequester(context: Context) : AudioFocusRequester {

    private val audioManager: AudioManager? =
        context.applicationContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** API 26+ 的申请对象；21-25 恒 null。 */
    private var modernRequest: AudioFocusRequest? = null

    /** 平台回调监听器（两路共用一份引用，供 [abandon] 摘除）。 */
    private var listener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * 申请焦点。
     *
     * @return true = `AUDIOFOCUS_REQUEST_GRANTED`；无 `AudioManager`（理论上不会发生）或
     *         系统拒绝时 false。
     */
    override fun acquire(onChange: (AudioFocusChange) -> Unit): Boolean {
        val manager = audioManager ?: return false
        // 防御式清理：正常路径下（onHostPaused / 平台 LOSS / release）旧请求已被 abandon
        // 且引用清空；这里兜底防异常路径残留 —— 旧引用一旦被下面的赋值覆盖，AudioManager
        // 侧的登记就再也摘不掉（B2 泄漏源）。abandon 幂等，残留时重复调用安全。
        if (modernRequest != null || listener != null) {
            abandon()
        }
        val focusListener = AudioManager.OnAudioFocusChangeListener { raw ->
            val change = AudioFocusMapping.fromRawChange(raw)
            if (change == null) {
                // 未知零/正数：不猜成 GAIN，也不暂停（规则见 AudioFocusRaw）。
                Log.w(TAG, "忽略未知音频焦点常量 $raw")
            } else {
                onChange(change)
            }
        }
        listener = focusListener
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = buildRequest(focusListener)
            modernRequest = request
            manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    /**
     * 放弃焦点；幂等（重复调用只会在第一次真的通知平台，第二次全是 null 判空）。
     * 无论走哪条路径都必须**清空** [modernRequest] / [listener] 引用：引用一旦残留
     * 又被 [acquire] 覆盖，AudioManager 侧的登记就永久摘不掉（见类 KDoc「B2」）。
     */
    override fun abandon() {
        val manager = audioManager
        if (manager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                modernRequest?.let {
                    // abandonAudioFocusRequest 返回 int（GRANTED / 未授予码）：平台已先回收
                    // 焦点（如 LOSS 后再 abandon）时会返回非 GRANTED，属幂等清理，只记日志。
                    val result = manager.abandonAudioFocusRequest(it)
                    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        Log.w(TAG, "abandonAudioFocusRequest 返回 $result（焦点可能已被系统回收，按幂等清理处理）")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                listener?.let {
                    // 21-25 老路径的 abandonAudioFocus 同样返回 int，语义同上。
                    val result = manager.abandonAudioFocus(it)
                    if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        Log.w(TAG, "abandonAudioFocus 返回 $result（焦点可能已被系统回收，按幂等清理处理）")
                    }
                }
            }
        }
        modernRequest = null
        listener = null
    }

    /** API 26+ 的申请对象：游戏用途 + 音乐内容类型，不吃 flash / sonification 的焦点组。 */
    @TargetApi(Build.VERSION_CODES.O)
    private fun buildRequest(focusListener: AudioManager.OnAudioFocusChangeListener): AudioFocusRequest {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
    }

    companion object {
        /** logcat 过滤用（BOARD M6-AUDIO-DEVICE 行口径：`GameHost:` 前缀）。 */
        private const val TAG: String = "GameHost:AudioFocus"
    }
}
