package com.eratw.port.platform.audio

import com.eratw.port.core.audio.AudioFocusEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点会话状态机（纯 JVM）：Activity 生命周期与 `AudioManager` 回调的归一、去重、幂等。
 *
 * 该层存在的理由：原版没有任何焦点/失活音频行为（`MainWindow.cs:2118-2119` 只挂
 * `FormClosing` / `Shown`，:1219-1234 不动音频，全文无 `Sound` / `Bgm` 引用），
 * 所以「切后台该不该停声」完全由移植侧自定；既然自定，就必须只有**一个**决策点，
 * 否则 `onPause` 与系统回调各停一次、`onResume` 与 `GAIN` 各恢复一次，恢复语义就不可判定。
 *
 * 本文件不碰 Android：`AudioFocusRequester` 用 [FakeRequester]，`AudioManager` 常量到
 * [AudioFocusChange] 的翻译在 `AndroidAudioFocusRequester`（真机项，见回执 TODO）。
 *
 * DEV-06（本波）：永久 LOSS 之后的阶段语义由「一个布尔」升级为 [FocusPhase] 四相 ——
 * 前台被永久抢占（DEFEATED）后迟到的 `GAIN` 按可用恢复处理（自动重申请），宿主切后台
 * （HOST_SUSPENDED）后迟到的 `GAIN` 仍然丢弃。原版没有焦点概念（平台最优，取舍见
 * [AudioFocusSession] 类 KDoc），本文件改钉的两条用例在注释里写明依据。
 */
class AudioFocusSessionTest {

    @Test
    fun `GAIN 处置策略按阶段分流`() {
        // 纯函数直钉：HOLDING 转发、DEFEATED 重申请、IDLE 与 HOST_SUSPENDED 丢弃。
        assertEquals(GainPolicy.FORWARD, gainPolicyOnGain(FocusPhase.HOLDING))
        assertEquals(GainPolicy.REACQUIRE, gainPolicyOnGain(FocusPhase.DEFEATED))
        assertEquals(GainPolicy.DROP, gainPolicyOnGain(FocusPhase.IDLE))
        assertEquals(GainPolicy.DROP, gainPolicyOnGain(FocusPhase.HOST_SUSPENDED))
    }

    @Test
    fun `四种平台变化归一成三种事件`() {
        // CAN_DUCK 按瞬态处理：原版没有闪避音量（唯一写入口 FunctionIdentifier.cs:2731-2738、:2753），
        // 临时改音量会污染 getVolume() 读数（Sound.cs:189-192）。
        assertEquals(AudioFocusEvent.GAIN, AudioFocusMapping.toEvent(AudioFocusChange.GAIN))
        assertEquals(AudioFocusEvent.LOSS, AudioFocusMapping.toEvent(AudioFocusChange.LOSS))
        assertEquals(
            AudioFocusEvent.LOSS_TRANSIENT,
            AudioFocusMapping.toEvent(AudioFocusChange.LOSS_TRANSIENT),
        )
        assertEquals(
            AudioFocusEvent.LOSS_TRANSIENT,
            AudioFocusMapping.toEvent(AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK),
        )
    }

    @Test
    fun `申请成功后合成一条 GAIN 且重复 start 不重复申请`() {
        val f = Fixture(granted = true)

        assertTrue(f.session.start())
        assertTrue(f.session.holdsFocus)
        assertEquals(1, f.requester.acquireCount)
        assertEquals(listOf(AudioFocusEvent.GAIN), f.target.received)

        // 系统 granted 时又真回调了一条 GAIN：会话必须去重，不能让下游恢复两次。
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals(listOf(AudioFocusEvent.GAIN), f.target.received)

        assertTrue("已持有焦点 ⇒ 幂等返回 true", f.session.start())
        assertEquals("不重复 requestAudioFocus", 1, f.requester.acquireCount)
    }

    @Test
    fun `申请被拒时不投递事件且不算持有焦点`() {
        val f = Fixture(granted = false)

        assertFalse(f.session.start())
        assertFalse(f.session.holdsFocus)
        assertEquals(1, f.requester.acquireCount)
        assertTrue("没拿到焦点就没有 GAIN", f.target.received.isEmpty())
        assertNull(f.session.lastEvent)
    }

    @Test
    fun `onPause 合成瞬态失焦并放弃焦点 重复调用只做一次`() {
        val f = Fixture(granted = true)
        f.session.start()

        assertTrue(f.session.onHostPaused())
        assertEquals(
            "切后台不合成失焦事件的话，多数 ROM 根本不会回调",
            listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS_TRANSIENT),
            f.target.received,
        )
        assertEquals(1, f.requester.abandonCount)
        assertFalse(f.session.holdsFocus)

        assertFalse("重复 onPause 空转", f.session.onHostPaused())
        assertEquals(1, f.requester.abandonCount)
        assertEquals(2, f.target.received.size)
    }

    @Test
    fun `onResume 重新申请并恢复 且放弃后迟到的 GAIN 被丢弃`() {
        val f = Fixture(granted = true)
        f.session.start()
        f.session.onHostPaused()

        // 放弃焦点之后系统迟到的 GAIN：后台恢复出声是明确的错误。
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals(
            listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS_TRANSIENT),
            f.target.received,
        )

        assertTrue(f.session.onHostResumed())
        assertEquals("回到前台必须重新申请", 2, f.requester.acquireCount)
        assertEquals(
            listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS_TRANSIENT, AudioFocusEvent.GAIN),
            f.target.received,
        )
    }

    /**
     * 改钉（DEV-06）：旧钉「长期失焦置为未持有 之后必须重新申请才会恢复」断言迟到的
     * `GAIN` 一律被吞。旧版吞掉的理由是防后台自动出声，但被永久抢占时宿主**仍在前台**
     * —— 用户没有离开，吞掉唯一可用的恢复信号就等于 BGM 静音到用户手动离开再回来
     * （DEV-06 的缺陷本体）。原版 Windows 无焦点概念（`MainWindow.cs:2118-2119` 是全部
     * 事件挂载点、全文无 `Sound` 引用），此行为无 C# 口径可对齐，按契约 §2 L3 的
     * 「迟到 GAIN 不吞可用恢复」平台最优改钉：DEFEATED 阶段迟到 `GAIN` ⇒ 自动重申请。
     */
    @Test
    fun `永久失焦后迟到的 GAIN 自动重申请并恢复`() {
        val f = Fixture(granted = true)
        f.session.start()

        f.requester.deliver(AudioFocusChange.LOSS)
        assertFalse("AUDIOFOCUS_LOSS 之后不再自认持有焦点", f.session.holdsFocus)
        assertEquals(AudioFocusEvent.LOSS, f.target.received.last())

        // 迟到的 GAIN（抢占方释放后部分 ROM 会补投）：DEFEATED ⇒ REACQUIRE。
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals("迟到的 GAIN 触发自动重申请", 2, f.requester.acquireCount)
        assertTrue("重申请成功即回到持有焦点", f.session.holdsFocus)
        assertEquals(
            listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS, AudioFocusEvent.GAIN),
            f.target.received,
        )

        // 恢复后重复的 GAIN 由去重闸挡掉，不再重申请。
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals(2, f.requester.acquireCount)
        assertEquals(3, f.target.received.size)
    }

    /**
     * 重申请被系统拒绝（acquire 返回 false）：保持 DEFEATED、不投递任何事件，
     * 下一条迟到的 `GAIN` 还会再试 —— 恢复机会不被一次拒绝消耗掉。
     */
    @Test
    fun `永久失焦后重申请被拒则维持失焦 下一条 GAIN 再试`() {
        val f = Fixture(granted = true)
        f.session.start()
        f.requester.deliver(AudioFocusChange.LOSS)

        f.requester.granted = false
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals("被拒的重申请也发生了", 2, f.requester.acquireCount)
        assertFalse(f.session.holdsFocus)
        assertEquals("拒绝时不投递事件", AudioFocusEvent.LOSS, f.target.received.last())

        f.requester.granted = true
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals(3, f.requester.acquireCount)
        assertTrue(f.session.holdsFocus)
        assertEquals(AudioFocusEvent.GAIN, f.target.received.last())
    }

    /**
     * DEFEATED 之后再切后台：`onHostPaused` 返回 false（没有事件可投、没有注册可撤），
     * 但阶段必须推进到 HOST_SUSPENDED —— 后台期间迟到的 `GAIN` 不得触发重申请
     * （后台自动出声是明确错误），回前台 `onResume` 仍能恢复。
     */
    @Test
    fun `永久失焦后切后台 迟到的 GAIN 不再触发重申请`() {
        val f = Fixture(granted = true)
        f.session.start()
        f.requester.deliver(AudioFocusChange.LOSS)

        assertFalse("DEFEATED 下 onPause 空转（LOSS 已投过、注册已交回）", f.session.onHostPaused())
        assertEquals(1, f.requester.abandonCount)

        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals("后台迟到的 GAIN 被丢弃，不重申请", 1, f.requester.acquireCount)
        assertEquals(AudioFocusEvent.LOSS, f.target.received.last())

        assertTrue(f.session.onHostResumed())
        assertEquals("回前台重新申请", 2, f.requester.acquireCount)
        assertEquals(AudioFocusEvent.GAIN, f.target.received.last())
    }

    @Test
    fun `瞬态失焦不改变持有标志 系统 GAIN 直接恢复`() {
        val f = Fixture(granted = true)
        f.session.start()

        f.requester.deliver(AudioFocusChange.LOSS_TRANSIENT)
        assertTrue("瞬态期间平台仍把焦点算给我们", f.session.holdsFocus)
        f.requester.deliver(AudioFocusChange.GAIN)
        assertEquals(
            "来电结束由系统送回 GAIN，不需要重新申请",
            listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS_TRANSIENT, AudioFocusEvent.GAIN),
            f.target.received,
        )
        assertEquals(1, f.requester.acquireCount)
    }

    @Test
    fun `release 幂等 且之后不再转发任何事件`() {
        val f = Fixture(granted = true)
        f.session.start()

        f.session.release()
        f.session.release()
        assertTrue(f.session.released)
        assertEquals("onDestroy 只放弃一次", 1, f.requester.abandonCount)
        assertFalse(f.session.holdsFocus)

        f.requester.deliver(AudioFocusChange.GAIN)
        f.requester.deliver(AudioFocusChange.LOSS_TRANSIENT)
        assertEquals("会话作废后事件全丢", 1, f.target.received.size)
        assertFalse("release 之后不再申请", f.session.start())
        assertEquals(1, f.requester.acquireCount)
    }

    /** requester + target + session 的一次性装置。 */
    private class Fixture(granted: Boolean) {
        val requester = FakeRequester(granted)
        val target = Target()
        val session = AudioFocusSession(requester, target)
    }

    /**
     * 假申请方：记次数、手动投递平台变化。abandon 后**保留**回调引用 —— 真机上 abandon
     * 摘的是 AudioManager 侧登记，但部分 ROM / 已排队的主 looper 回调仍会补投递
     * （与 AudioAudit2FocusTest 的 TrackingRequester 同一口径），这样才能真正测到
     * 「迟到 GAIN 的 DROP / REACQUIRE 分流」，而不是被假装置的空转糊弄过去。
     */
    private class FakeRequester(granted: Boolean) : AudioFocusRequester {
        /** 可变的 granted（DEV-06 用例要模拟「重申请被拒」）。 */
        var granted: Boolean = granted

        var acquireCount: Int = 0
            private set
        var abandonCount: Int = 0
            private set

        private var onChange: ((AudioFocusChange) -> Unit)? = null

        override fun acquire(onChange: (AudioFocusChange) -> Unit): Boolean {
            acquireCount++
            if (!granted) return false
            this.onChange = onChange
            return true
        }

        override fun abandon() {
            abandonCount++
            // 刻意不清 onChange：见本类 KDoc（迟到事件的补投递口径）。
        }

        /** 模拟 `AudioManager.OnAudioFocusChangeListener` 的回调。 */
        fun deliver(change: AudioFocusChange) {
            onChange?.invoke(change)
        }
    }

    /** 记录事件的 target（含重复投递，用于验证会话去重）。 */
    private class Target : AudioFocusTarget {
        val received: MutableList<AudioFocusEvent> = mutableListOf()

        override fun onFocusEvent(event: AudioFocusEvent): Int {
            received += event
            return 0
        }
    }
}
