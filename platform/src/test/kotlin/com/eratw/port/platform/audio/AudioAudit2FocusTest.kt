package com.eratw.port.platform.audio

import com.eratw.port.core.audio.AudioFocusEvent
import com.eratw.port.core.audio.AudioFocusScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计二（lane L01 音频）—— 焦点会话的 **LOSS 收尾必须交回平台注册**。
 *
 * 该层的正确性判据不是 C#（原版没有任何焦点概念：`MainWindow.cs:2118-2119` 是全部事件挂载点，
 * 只挂 `FormClosing` / `Shown`，:1219-1234 的 `MainWindow_FormClosing` 不动音频，全文无
 * `Sound` / `Bgm` 引用），而是**移植侧自己钉下的两条**：
 * - `core/audio/AudioFocus.kt` 对 [AudioFocusEvent.LOSS] 的口径：「被长期抢走……
 *   **必须重新申请**才可能再拿到 `GAIN`」（对应 [AudioFocusScope.PERMANENT] 的收尾规则）；
 * - [AudioFocusRequester.abandon] 的契约：幂等，且实现方靠它摘掉监听器
 *   （真机侧 `AndroidAudioFocusRequester` 的 `listener` / `modernRequest` 两个字段只记最新一条）。
 *
 * 旧实现在 `LOSS` 分支只把 `holdsFocus` 置 false、没有 abandon，于是与
 * [AudioFocusSession.onHostPaused]（同样以「不再持有焦点」收尾、但会 abandon）**不对称**：
 * 旧监听器还挂在 requester 上时下一次 [AudioFocusSession.start] 会再申请一次，
 * 那两个字段被静默覆盖，旧注册再也摘不掉（部分 ROM 上同一次平台变化还会投递两遍）。
 * 本文件钉的就是「不持有焦点 ⇔ 没有活着的注册」这条不变量。
 *
 * 音频停止/释放的原始口径（用来确认 abandon 不该动声道）：`Sound.stop()`（Sound.cs:151-163）
 * 与 `Sound.close()`（:165-169）只有脚本 `STOPSOUND` / `STOPBGM`（FunctionIdentifier.cs:2664-2674、
 * :2716）与启动清场（Program.cs:97-104）会走到，焦点层一条都不许碰。
 */
class AudioAudit2FocusTest {

    @Test
    fun `永久失焦交回注册 瞬态失焦不交回`() {
        val f = Fixture()

        assertTrue(f.session.start())
        assertEquals(1, f.requester.liveRegistrations)

        f.deliver(AudioFocusChange.LOSS)
        assertFalse("LOSS 之后不再自认持有焦点", f.session.holdsFocus)
        assertEquals("永久失焦必须把注册交回", 0, f.requester.liveRegistrations)
        assertEquals(1, f.requester.abandonCount)
        assertEquals(listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS), f.target.received)

        // 瞬态：系统会自己送回 GAIN，摘了注册就再也等不到那条回调 ⇒ 只能保留。
        assertTrue(f.session.start())
        assertEquals(2, f.requester.acquireCount)
        assertEquals("重新申请成功后合成一条 GAIN", 3, f.target.received.size)
        f.deliver(AudioFocusChange.LOSS_TRANSIENT)
        assertTrue("瞬态期间平台仍把焦点算给我们", f.session.holdsFocus)
        assertEquals("瞬态不 abandon", 1, f.requester.abandonCount)
        assertEquals(1, f.requester.liveRegistrations)
        f.deliver(AudioFocusChange.GAIN)
        assertEquals(
            listOf(
                AudioFocusEvent.GAIN,
                AudioFocusEvent.LOSS,
                AudioFocusEvent.GAIN,
                AudioFocusEvent.LOSS_TRANSIENT,
                AudioFocusEvent.GAIN,
            ),
            f.target.received,
        )
    }

    @Test
    fun `反复失焦再申请也不会同时挂着两条注册`() {
        val f = Fixture()
        f.session.start()

        // 旧实现下每一轮 LOSS → start 都会让 requester 的监听器字段被覆盖一次，
        // 平台侧的注册只增不减；修好之后活着的注册恒为 0 或 1。
        repeat(4) { round ->
            f.deliver(AudioFocusChange.LOSS)
            assertEquals("第 $round 轮 LOSS 之后没有活着的注册", 0, f.requester.liveRegistrations)
            assertTrue("回前台重新申请", f.session.start())
            assertEquals("第 $round 轮重新申请后恰好一条注册", 1, f.requester.liveRegistrations)
            assertTrue(
                "第 $round 轮恢复投递过 GAIN",
                f.target.received.contains(AudioFocusEvent.GAIN),
            )
        }
        assertEquals("任意时刻最多一条注册", 1, f.requester.maxLiveRegistrations)
        assertEquals("首次 + 四轮重申", 5, f.requester.acquireCount)
        assertEquals("每轮 LOSS 交回一次", 4, f.requester.abandonCount)
    }

    /**
     * 改钉（DEV-06）：旧钉「交回注册之后迟到的 GAIN 仍然丢弃 回前台才会续播」。旧判据
     * 防的是「后台被自动拉起来出声」，但交回注册（永久 LOSS）时宿主仍在前台 —— 吞掉迟到
     * `GAIN` 就吞掉了唯一可用的恢复信号（用户不离开应用 BGM 永久静音）。原版 Windows 无
     * 焦点概念（本文件类 KDoc 已取证：`MainWindow.cs` 全文无 `Sound` 引用），无 C# 口径
     * 可对齐，按契约 §2 L3「迟到 GAIN 不吞可用恢复」的平台最优改钉：DEFEATED 阶段迟到
     * `GAIN` ⇒ 自动重申请；「不持有焦点 ⇔ 没有活着的注册」不变量不受影响（重申请走干净
     * 的 acquire，恰好一条注册）。后台迟到的 `GAIN` 仍丢弃，见 AudioFocusSessionTest 的
     * HOST_SUSPENDED 用例。
     */
    @Test
    fun `交回注册之后迟到的 GAIN 触发重申请 回前台重申请幂等`() {
        val f = Fixture()
        f.session.start()
        f.deliver(AudioFocusChange.LOSS)

        // abandon 之后平台仍可能补一条 GAIN（ROM 行为不一）：DEFEATED ⇒ REACQUIRE。
        f.deliver(AudioFocusChange.GAIN)
        assertEquals("迟到的 GAIN 触发自动重申请", 2, f.requester.acquireCount)
        assertEquals("重申请后恰好一条活着的注册", 1, f.requester.liveRegistrations)
        assertTrue(f.session.holdsFocus)
        assertEquals(AudioFocusEvent.GAIN, f.target.received.last())

        // 回前台：已持有 ⇒ onResume 幂等，不再重复申请（重申请已由迟到 GAIN 完成）。
        assertTrue(f.session.onHostResumed())
        assertEquals(2, f.requester.acquireCount)
        assertEquals(1, f.requester.liveRegistrations)
    }

    @Test
    fun `重复 LOSS 的交回幂等 事件仍只投递一次`() {
        val f = Fixture()
        f.session.start()

        f.deliver(AudioFocusChange.LOSS)
        f.deliver(AudioFocusChange.LOSS)
        assertEquals("重复 LOSS 不再投递事件（去重在会话侧）", 2, f.target.received.size)
        assertEquals("abandon 本身幂等，多调一次不改变任何声道状态", 2, f.requester.abandonCount)
        assertEquals(0, f.requester.liveRegistrations)
        assertFalse(f.session.holdsFocus)
    }

    /** requester + target + session 的一次性装置。 */
    private class Fixture {
        val requester = TrackingRequester()
        val target = Target()
        val session = AudioFocusSession(requester, target)

        /** 模拟 `AudioManager` 的回调：abandon 之后平台仍可能补投递，所以监听器不随 abandon 清空。 */
        fun deliver(change: AudioFocusChange) = requester.deliver(change)
    }

    /** 数「活着的注册」的假申请方：acquire 加一条、abandon 减一条（幂等：没有就不减）。 */
    private class TrackingRequester : AudioFocusRequester {
        var acquireCount: Int = 0
            private set
        var abandonCount: Int = 0
            private set

        /** 当前仍挂在平台上的注册数（修复后恒 0 或 1）。 */
        var liveRegistrations: Int = 0
            private set

        /** 历史峰值，用来证明不会叠出第二条注册。 */
        var maxLiveRegistrations: Int = 0
            private set

        private var onChange: ((AudioFocusChange) -> Unit)? = null

        override fun acquire(onChange: (AudioFocusChange) -> Unit): Boolean {
            acquireCount++
            this.onChange = onChange
            liveRegistrations++
            maxLiveRegistrations = maxOf(maxLiveRegistrations, liveRegistrations)
            return true
        }

        override fun abandon() {
            abandonCount++
            if (liveRegistrations > 0) liveRegistrations--
        }

        fun deliver(change: AudioFocusChange) {
            onChange?.invoke(change)
        }
    }

    /** 记录事件的消费端。 */
    private class Target : AudioFocusTarget {
        val received: MutableList<AudioFocusEvent> = mutableListOf()

        override fun onFocusEvent(event: AudioFocusEvent): Int {
            received += event
            return 0
        }
    }
}
