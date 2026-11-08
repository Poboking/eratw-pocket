package com.eratw.port.platform.vm

import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.lexer.LangVariant
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ENGFIX-4：死循环告警同步桥的行为钉（与 [com.eratw.port.platform.save.BlockingSaveMigrationPromptTest] 同一套判据）。
 *
 * 判据来自原版 skia `Process.cs:478-488`：有 UI 时阻塞到作答；
 * 作答 Yes = [InfiniteLoopAnswer.Exit]（带同一语言档）、No = [InfiniteLoopAnswer.Continue]；
 * 投递失败 / 线程中断 / 无 UI 一律回落 [InfiniteLoopAnswer.NoUi]，
 * 且 VM 线程不得被永久挂住、重复作答只认第一次。
 */
class BlockingInfiniteLoopPromptTest {

    private fun alert() = InfiniteLoopAlert(
        sourceName = "ERB/SAMPLE.ERB",
        functionName = "SAMPLE",
        lineNo = 42L,
        linesExecuted = 10_000L,
        elapsedMs = 5_100L,
        alertTimeMs = 5_000L,
    )

    @Test
    fun `玩家答退出 返回 Exit 且语言档原样带回`() {
        val seen = AtomicReference<InfiniteLoopAlert?>(null)
        val prompt = BlockingInfiniteLoopPrompt { a, answer ->
            seen.set(a)
            answer(InfiniteLoopAnswer.Exit(LangVariant.JA))
        }
        val result = prompt(alert())
        assertEquals(InfiniteLoopAnswer.Exit(LangVariant.JA), result)
        assertEquals("ERB/SAMPLE.ERB", seen.get()?.sourceName)
        assertEquals("可能发生了无限循环", seen.get()?.title(LangVariant.ZHS))
    }

    @Test
    fun `玩家答继续 返回 Continue`() {
        val prompt = BlockingInfiniteLoopPrompt { _, answer -> answer(InfiniteLoopAnswer.Continue) }
        assertEquals(InfiniteLoopAnswer.Continue, prompt(alert()))
    }

    @Test
    fun `UI 未作答时调用线程阻塞 作答后放行`() {
        val answerRef = AtomicReference<((InfiniteLoopAnswer) -> Unit)?>(null)
        val prompt = BlockingInfiniteLoopPrompt { _, answer -> answerRef.set(answer) }
        var result: InfiniteLoopAnswer? = null
        val worker = Thread { result = prompt(alert()) }.apply { start() }
        val deadline = System.currentTimeMillis() + 2_000
        while (answerRef.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(1)
        assertNull("UI 作答前不得返回", result)
        answerRef.get()!!.invoke(InfiniteLoopAnswer.Continue)
        worker.join(2_000)
        assertEquals(InfiniteLoopAnswer.Continue, result)
    }

    @Test
    fun `重复作答只认第一次`() {
        val prompt = BlockingInfiniteLoopPrompt { _, answer ->
            answer(InfiniteLoopAnswer.Continue)
            answer(InfiniteLoopAnswer.Exit(LangVariant.ZHS))
        }
        assertEquals("先到的「继续」生效，后到的「退出」丢弃", InfiniteLoopAnswer.Continue, prompt(alert()))
    }

    @Test
    fun `UI 投递抛异常按 NoUi 收敛 不挂住 VM 线程`() {
        val prompt = BlockingInfiniteLoopPrompt { _, _ -> throw IllegalStateException("Activity 已销毁") }
        assertEquals(InfiniteLoopAnswer.NoUi, prompt(alert()))
    }

    @Test
    fun `等待被中断按 NoUi 收敛并恢复中断位`() {
        val shown = AtomicReference<Boolean>(false)
        val prompt = BlockingInfiniteLoopPrompt { _, _ -> shown.set(true) }
        var result: InfiniteLoopAnswer? = null
        val worker = Thread { result = prompt(alert()) }.apply { start() }
        val deadline = System.currentTimeMillis() + 2_000
        while (!shown.get() && System.currentTimeMillis() < deadline) Thread.sleep(1)
        worker.interrupt()
        worker.join(2_000)
        assertEquals(InfiniteLoopAnswer.NoUi, result)
        assertTrue("中断后必须退出等待", worker.state == Thread.State.TERMINATED)
    }
}
