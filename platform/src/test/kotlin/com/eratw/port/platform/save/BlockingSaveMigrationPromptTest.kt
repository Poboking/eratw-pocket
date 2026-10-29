package com.eratw.port.platform.save

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M11-A-WIRE-2：同步询问桥的行为钉。
 *
 * 判据来自原版 `Dialog.ShowPrompt`（spec: `Dialog.cs:21-28`）：只有明确「是」才搬；
 * 关框/投递失败/线程被中断一律按「否」收敛，且 VM 线程不得被永久挂住。
 */
class BlockingSaveMigrationPromptTest {

    @Test
    fun `玩家答是 返回 true 且 title message 原样送达`() {
        val seen = AtomicReference<Pair<String, String>?>(null)
        val prompt = BlockingSaveMigrationPrompt { title, message, answer ->
            seen.set(title to message)
            answer(true)
        }
        assertTrue(prompt("数据移动", "将现在的数据移动到sav文件夹中吗？"))
        assertEquals("数据移动" to "将现在的数据移动到sav文件夹中吗？", seen.get())
    }

    @Test
    fun `玩家答否 返回 false 文件不搬`() {
        val prompt = BlockingSaveMigrationPrompt { _, _, answer -> answer(false) }
        assertFalse(prompt("数据移动", "msg"))
    }

    @Test
    fun `UI 未作答时调用线程阻塞 作答后放行`() {
        val answerRef = AtomicReference<((Boolean) -> Unit)?>(null)
        val prompt = BlockingSaveMigrationPrompt { _, _, answer -> answerRef.set(answer) }
        var result: Boolean? = null
        val worker = Thread { result = prompt("t", "m") }.apply { start() }
        val deadline = System.currentTimeMillis() + 2_000
        while (answerRef.get() == null && System.currentTimeMillis() < deadline) Thread.sleep(1)
        assertNull("UI 作答前不得返回", result)
        answerRef.get()!!.invoke(true)
        worker.join(2_000)
        assertEquals(true, result)
    }

    @Test
    fun `重复作答只认第一次`() {
        val prompt = BlockingSaveMigrationPrompt { _, _, answer ->
            answer(true)
            answer(false)
        }
        assertTrue("先到的「是」生效，后到的「否」丢弃", prompt("t", "m"))
    }

    @Test
    fun `UI 投递抛异常按否收敛 不挂住装载线程`() {
        val prompt = BlockingSaveMigrationPrompt { _, _, _ -> throw IllegalStateException("Activity 已销毁") }
        assertFalse(prompt("t", "m"))
    }

    @Test
    fun `等待被中断按否收敛并恢复中断位`() {
        val shown = AtomicReference<Boolean>(false)
        val prompt = BlockingSaveMigrationPrompt { _, _, _ -> shown.set(true) }
        var result: Boolean? = null
        val worker = Thread { result = prompt("t", "m") }.apply { start() }
        val deadline = System.currentTimeMillis() + 2_000
        while (!shown.get() && System.currentTimeMillis() < deadline) Thread.sleep(1)
        worker.interrupt()
        worker.join(2_000)
        assertEquals(false, result)
        assertTrue("中断后必须退出等待", worker.state == Thread.State.TERMINATED)
    }
}
