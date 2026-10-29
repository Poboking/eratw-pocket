package com.eratw.port.platform.audio

import com.eratw.port.core.audio.AudioFocusEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点相位的**边界**（m11e-audio-format ④）：DEV-06 的四相状态机已定，本文件只补抖动与
 * 「后台 × 永久抢占」这两种相位会互相覆盖的路径。
 *
 * 为什么这些边界存在：`FocusPhase` 只有四个值，而「宿主在后台」与「被平台永久抢占」是
 * **正交**的两件事。后台期间平台下发 `LOSS` 会把相位从 HOST_SUSPENDED 改写成 DEFEATED，
 * 于是「后台」只剩 `AudioFocusSession.hostInBackground` 这一个记录点；少了它，随后迟到的
 * `GAIN` 会走 REACQUIRE、在后台重新申请并投递 `GAIN` ⇒ 后台出声。
 *
 * 原版没有焦点概念（`MainWindow.cs:2118-2119` 是全部事件挂载点，全文无 `Sound` 引用），
 * 所以这里没有 C# 口径可对齐 —— 判据只有一条：**后台绝不出声，前台尽量不停**。
 */
class M11FocusBoundaryTest {

    @Test
    fun `后台中的 DEFEATED 也是 DROP`() {
        // 纯函数直钉：同一个 DEFEATED，前台 ⇒ REACQUIRE（DEV-06），后台 ⇒ DROP。
        assertEquals(GainPolicy.REACQUIRE, gainPolicyOnGain(FocusPhase.DEFEATED, hostInBackground = false))
        assertEquals(GainPolicy.DROP, gainPolicyOnGain(FocusPhase.DEFEATED, hostInBackground = true))
        // 其余三相不受后台标志影响
        assertEquals(GainPolicy.FORWARD, gainPolicyOnGain(FocusPhase.HOLDING, hostInBackground = true))
        assertEquals(GainPolicy.DROP, gainPolicyOnGain(FocusPhase.IDLE, hostInBackground = false))
        assertEquals(GainPolicy.DROP, gainPolicyOnGain(FocusPhase.HOST_SUSPENDED, hostInBackground = false))
    }

    @Test
    fun `后台期间平台 LOSS 之后迟到的 GAIN 不重申请`() {
        val f = Fixture()
        f.session.start()
        f.session.onHostPaused() // HOST_SUSPENDED + abandon

        // 后台期间被别的播放器永久抢走：相位被改写成 DEFEATED，但「在后台」这条事实仍在。
        f.requester.deliver(AudioFocusChange.LOSS)
        assertEquals(2, f.requester.abandonCount)
        assertFalse(f.session.holdsFocus)

        f.requester.deliver(AudioFocusChange.GAIN)

        assertEquals("后台不得因迟到 GAIN 重新申请", 1, f.requester.acquireCount)
        assertFalse(f.session.holdsFocus)
        assertEquals("后台不得投递 GAIN", AudioFocusEvent.LOSS, f.target.received.last())
        assertEquals(listOf("acquire", "abandon", "abandon"), f.requester.log)
    }

    @Test
    fun `后台 LOSS 之后回前台仍能恢复`() {
        // 上一条的收尾：后台标志只由 start / onHostPaused 改写，回前台必须能重新申请。
        val f = Fixture()
        f.session.start()
        f.session.onHostPaused()
        f.requester.deliver(AudioFocusChange.LOSS)
        f.requester.deliver(AudioFocusChange.GAIN) // 被丢弃

        assertTrue(f.session.onHostResumed())

        assertEquals(2, f.requester.acquireCount)
        assertTrue(f.session.holdsFocus)
        assertEquals(AudioFocusEvent.GAIN, f.target.received.last())
    }

    @Test
    fun `连续 LOSS GAIN 抖动 活注册数不叠加`() {
        val f = Fixture()
        f.session.start()

        repeat(3) {
            f.requester.deliver(AudioFocusChange.LOSS)
            f.requester.deliver(AudioFocusChange.GAIN)
        }

        assertEquals("1 次首申请 + 3 次 REACQUIRE", 4, f.requester.acquireCount)
        assertEquals("每条 LOSS 都交回注册", 3, f.requester.abandonCount)
        assertEquals("任何时刻最多一条活注册（B2 不变量）", 1, f.requester.maxLive)
        assertTrue(f.session.holdsFocus)
        assertEquals(
            "抖动期间每条事件都不同 ⇒ 去重闸不该吞掉任何一条",
            listOf(
                AudioFocusEvent.GAIN,
                AudioFocusEvent.LOSS, AudioFocusEvent.GAIN,
                AudioFocusEvent.LOSS, AudioFocusEvent.GAIN,
                AudioFocusEvent.LOSS, AudioFocusEvent.GAIN,
            ),
            f.target.received,
        )
    }

    @Test
    fun `abandon 之后立刻 re-request 只有一条活注册`() {
        // 平台永久 LOSS 会 abandon（防 AudioManager 侧登记泄漏），随后马上回前台重申请。
        val f = Fixture()
        f.session.start()
        f.requester.deliver(AudioFocusChange.LOSS)

        assertEquals("LOSS 之后平台侧没有活注册", 0, f.requester.live)
        assertTrue(f.session.start())

        assertEquals(listOf("acquire", "abandon", "acquire"), f.requester.log)
        assertEquals(1, f.requester.live)
        assertEquals(1, f.requester.maxLive)
        assertTrue(f.session.holdsFocus)
    }

    @Test
    fun `重复 LOSS 幂等 不重复投递也不叠加注册`() {
        val f = Fixture()
        f.session.start()

        repeat(3) { f.requester.deliver(AudioFocusChange.LOSS) }

        assertEquals(1, f.target.received.count { it == AudioFocusEvent.LOSS })
        assertEquals("重复 LOSS 的 abandon 幂等（requester 侧不会叠两份）", 0, f.requester.live)
        assertFalse(f.session.holdsFocus)
    }

    @Test
    fun `后台期间的瞬态失焦不改相位 也不叠加 abandon`() {
        // 瞬态路径口径：不动阶段、不 abandon（系统会自己送回 GAIN，登记必须原样保留）。
        // 后台期间来一条平台瞬态 LOSS，与 onHostPaused 合成的那条重复（去重闸挡掉）——
        // 相位必须留在 HOST_SUSPENDED，随后迟到的 GAIN 仍然丢弃，回前台仍能恢复。
        val f = Fixture()
        f.session.start()
        f.session.onHostPaused()

        f.requester.deliver(AudioFocusChange.LOSS_TRANSIENT)

        assertEquals("瞬态不失焦也不交回注册", 1, f.requester.abandonCount)
        assertEquals("onHostPaused 已交回；瞬态不再动注册", 0, f.requester.live)
        assertFalse(f.session.holdsFocus)
        assertEquals("重复的瞬态被去重闸挡掉", AudioFocusEvent.LOSS_TRANSIENT, f.target.received.last())

        f.requester.deliver(AudioFocusChange.GAIN)

        assertEquals("后台迟到的 GAIN 仍丢弃，不重申请", 1, f.requester.acquireCount)
        assertEquals(AudioFocusEvent.LOSS_TRANSIENT, f.target.received.last())
        assertTrue(f.session.onHostResumed())
        assertEquals("回前台重新申请", 2, f.requester.acquireCount)
        assertEquals(AudioFocusEvent.GAIN, f.target.received.last())
        assertTrue(f.session.holdsFocus)
    }

    private class Fixture {
        val requester = TrackingRequester()
        val target = Target()
        val session = AudioFocusSession(requester, target)
    }

    /**
     * 记「活注册数」的假申请方：`acquire` 加一、`abandon` 减一（不为负）。
     *
     * 真机 `AudioManager` 用同一个 `AudioFocusRequest` 重复申请是替换而非叠加，所以只要会话
     * 遵守「先 abandon 再 acquire」，活注册数恒 ≤ 1；出现 2 就说明有泄漏路径。
     * abandon 后**保留**回调引用：部分 ROM / 已排队的主 looper 回调仍会补投递（与
     * AudioFocusSessionTest.FakeRequester 同口径），否则测不到迟到事件的 DROP 分流。
     */
    private class TrackingRequester : AudioFocusRequester {
        val log: MutableList<String> = mutableListOf()

        var acquireCount: Int = 0
            private set
        var abandonCount: Int = 0
            private set

        /** 当前活注册数。 */
        var live: Int = 0
            private set

        /** 历史最大活注册数。 */
        var maxLive: Int = 0
            private set

        private var onChange: ((AudioFocusChange) -> Unit)? = null

        override fun acquire(onChange: (AudioFocusChange) -> Unit): Boolean {
            acquireCount++
            log += "acquire"
            live++
            maxLive = maxOf(maxLive, live)
            this.onChange = onChange
            return true
        }

        override fun abandon() {
            abandonCount++
            log += "abandon"
            if (live > 0) live--
        }

        fun deliver(change: AudioFocusChange) {
            onChange?.invoke(change)
        }
    }

    private class Target : AudioFocusTarget {
        val received: MutableList<AudioFocusEvent> = mutableListOf()

        override fun onFocusEvent(event: AudioFocusEvent): Int {
            received += event
            return 0
        }
    }
}
