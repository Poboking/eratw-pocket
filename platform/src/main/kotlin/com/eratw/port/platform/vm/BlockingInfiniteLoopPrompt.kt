package com.eratw.port.platform.vm

import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 死循环告警对话框的 UI 最小面（ENGFIX-4）。
 *
 * 实现方必须把 [show] 投到 **UI 线程**，并在玩家作答（或框被关掉、Activity 不可用）时
 * 调用一次 [answer]。语言档由宿主在画框时定（原版文案留在 core 的
 * [com.eratw.port.core.code.InfiniteLoopTexts]）；[answer] 带回去的
 * [InfiniteLoopAnswer.Exit] 必须等值携带同一个语言档，退出时那条 `CodeEE` 才会同语。
 */
fun interface InfiniteLoopPromptUi {
    fun show(alert: InfiniteLoopAlert, answer: (InfiniteLoopAnswer) -> Unit)
}

/**
 * 把 core 的同步告警通道（`VmHost.askInfiniteLoop`）接到 Android UI 上。
 *
 * 原版在 `Process.cs:487-512` 于**语句边界**同步弹框：视图可用时
 * `Dialog.ShowPrompt`（Yes = 强制结束，No = 继续），不可用时什么都不做
 * （skia `Process.cs:478-488` 三条分支 ↦ [InfiniteLoopAnswer]）。
 * 本类只做「阻塞 VM 线程直到 UI 作答」这一件事，**不碰 Android API**：
 * UI 线程投递与 Activity 生命周期守卫都在宿主侧（`GameHost`）。
 *
 * 作答是**一次性**的：重复调用、关框回调与关闭取消同时到达时只认第一次；
 * UI 投递失败（Activity 已销毁等）按 [InfiniteLoopAnswer.NoUi] 收敛
 * （= 原版「没有可用 UI」⇒ 既不中止也不归零），绝不把 VM 线程永久挂住。
 * 线程被中断（会话 close）时同样回落 [InfiniteLoopAnswer.NoUi] 并恢复中断位，
 * 让外层既有的中断收口照常生效。
 */
class BlockingInfiniteLoopPrompt(
    private val ui: InfiniteLoopPromptUi,
) : (InfiniteLoopAlert) -> InfiniteLoopAnswer {

    override fun invoke(alert: InfiniteLoopAlert): InfiniteLoopAnswer {
        val latch = CountDownLatch(1)
        val result = AtomicReference<InfiniteLoopAnswer>(InfiniteLoopAnswer.NoUi)
        val answered = AtomicBoolean(false)
        val answer: (InfiniteLoopAnswer) -> Unit = { value ->
            if (answered.compareAndSet(false, true)) {
                result.set(value)
                latch.countDown()
            }
        }
        // UI 投递失败 = 框弹不出来；按原版「view.Enabled == false」收敛。
        runCatching { ui.show(alert, answer) }.onFailure { answer(InfiniteLoopAnswer.NoUi) }
        try {
            latch.await()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return InfiniteLoopAnswer.NoUi
        }
        return result.get()
    }
}
