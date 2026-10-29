package com.eratw.port.platform.input

import com.eratw.port.core.host.InputRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InputBarState] 的判据：输入条恒在、等待期按类型切键盘、非等待期可抢跑、宏预填不自动提交。
 *
 * ## 期望值出处
 * - 输入框常驻：`MainWindow.cs:112`（字段）、`:210`（`TextBox` 属性）、`:727-728`
 *   （每次重算尺寸都钉在客户区最下一行），全仓无 `Visible` / `Enabled` 开关。
 * - 提交不看是否在等待：`MainWindow.cs:922-936` 的守卫只有 `console != null &&
 *   !console.IsInProcess`，`:934` 把文本原样交给核心层；未等待的提交由 `QueueInputSink`
 *   先入队（该类 KDoc 第 1 条，`QueueInputSink.kt:26-31`）。
 * - 宏只预填：`MainWindow.cs:507-512` 只有 `Text =` 与 `SelectionStart =` 两句；
 *   `docs/golden-runbook.md:106`「不会自动回车提交」、`:125`「回车仍需手按」。
 * - 清空后回到常驻：`updateInputs` 末尾 `richTextBox1.Text = ""`（`MainWindow.cs:942`、`:965`），
 *   提交前后输入条的可见性/键盘口径不变。
 * - 数字键盘：整数等待的解析失败会丢弃该次提交（`EmueraConsole.cs:1120-1124`；
 *   `QueueInputSink.kt:38-40` 同口径），其余等待一律文本。
 * - 负号开关（DEV-07）：整数取值由 `long.TryParse` 解析、接受负数（`EmueraConsole.cs:1125`），
 *   而 Android 数字键盘没有负号键 ⇒ 整数等待时由壳层补一枚「±」，文本变换是纯函数
 *   [InputBarState.toggleSign]；非整数等待既不显示开关、也不改文本。
 *
 * 本文件只断言纯逻辑读数，不涉及 Android `InputType` 常量（映射在 `MainActivity`）。
 */
class InputBarStateTest {

    private fun state(
        pending: InputRequest? = null,
        awaitingAnyKey: Boolean = false,
        hasHost: Boolean = true,
        running: Boolean = true,
    ): InputBarState = InputBarState.of(
        hasHost = hasHost,
        running = running,
        pending = pending,
        awaitingAnyKey = awaitingAnyKey,
    )

    private fun intState(): InputBarState = state(pending = InputRequest(InputRequest.InputType.INT))

    @Test
    fun `等待 INT 时数字键盘且为普通提交`() {
        val s = state(pending = InputRequest(InputRequest.InputType.INT))
        assertTrue("输入条恒在（MainWindow.cs:112）", s.visible)
        assertEquals("整数等待切数字键盘", InputBarState.Keyboard.SIGNED_NUMBER, s.keyboard)
        assertEquals("在等待里提交是正常取值", InputBarState.Submit.NORMAL, s.submit)
        assertTrue(s.canSubmit)
        assertFalse("等待期不是抢跑", s.isPrerun)
        assertFalse("不自动提交", s.autoSubmit)
    }

    @Test
    fun `等待 STR 时文本键盘且为普通提交`() {
        val s = state(pending = InputRequest(InputRequest.InputType.STR))
        assertTrue(s.visible)
        assertEquals(InputBarState.Keyboard.TEXT, s.keyboard)
        assertEquals(InputBarState.Submit.NORMAL, s.submit)
        assertTrue(s.canSubmit)
        assertFalse(s.isPrerun)
    }

    @Test
    fun `等待任意键时也是普通提交且文本键盘`() {
        val s = state(awaitingAnyKey = true)
        assertTrue(s.visible)
        assertEquals("WAIT 系不解释文本，键盘保持文本", InputBarState.Keyboard.TEXT, s.keyboard)
        assertEquals(InputBarState.Submit.NORMAL, s.submit)
        assertFalse(s.isPrerun)
    }

    @Test
    fun `非等待期仍可提交且标记为抢跑`() {
        val s = state()
        assertTrue("原版文本框不因未等待而隐藏", s.visible)
        assertEquals(InputBarState.Keyboard.TEXT, s.keyboard)
        assertEquals("未等待仍入队（QueueInputSink KDoc 第 1 条）", InputBarState.Submit.PRERUN, s.submit)
        assertTrue(s.canSubmit)
        assertTrue(s.isPrerun)
    }

    @Test
    fun `无宿主或线程已停时提交禁用`() {
        val demo = state(hasHost = false)
        assertEquals("演示屏没有 VM，提交无处可去", InputBarState.Submit.DISABLED, demo.submit)
        assertFalse(demo.canSubmit)
        assertTrue("但输入框仍然常驻", demo.visible)

        val stopped = state(running = false)
        assertEquals(InputBarState.Submit.DISABLED, stopped.submit)
        assertFalse(stopped.canSubmit)
    }

    @Test
    fun `宏预填后仍是常驻可抢跑且不自动提交`() {
        // 预填只改框内文本（MainWindow.cs:507-512），不产生任何提交；
        // 预填发生的时刻若核心层没在等，状态就是非等待态。
        val s = state()
        assertTrue("宏预填不会把输入条藏起来", s.visible)
        assertEquals(InputBarState.Submit.PRERUN, s.submit)
        assertTrue(s.canSubmit)
        assertFalse("宏这一路没有 PressEnterKey", s.autoSubmit)
    }

    @Test
    fun `提交清空后回到常驻`() {
        // 提交前在等待，提交被消费后回到非等待；updateInputs 已把框清空
        // （MainWindow.cs:942、:965），可见性与键盘口径必须与非等待初始态一致。
        val idle = state()
        val afterSubmit = state(pending = null, awaitingAnyKey = false)
        assertEquals(idle, afterSubmit)
        assertTrue(afterSubmit.visible)
        assertEquals(InputBarState.Keyboard.TEXT, afterSubmit.keyboard)
        assertEquals(InputBarState.Submit.PRERUN, afterSubmit.submit)
        assertFalse(afterSubmit.autoSubmit)
    }

    @Test
    fun `整数等待时出现负号开关且空串先落负号`() {
        val s = intState()
        assertTrue("数字键盘没有负号键 ⇒ 整数等待才显示 ±（DEV-07）", s.showsSignToggle)
        assertEquals("空串点 ± 先落一个负号", "-", InputBarState.toggleSign(""))
        assertEquals("-", s.withSignToggled(""))
    }

    @Test
    fun `已有前导负号时点负号开关去掉它`() {
        val s = intState()
        assertEquals("5", InputBarState.toggleSign("-5"))
        assertEquals("5", s.withSignToggled("-5"))
    }

    @Test
    fun `正数点负号开关补上负号`() {
        val s = intState()
        assertEquals("-20", InputBarState.toggleSign("20"))
        assertEquals("-20", s.withSignToggled("20"))
    }

    @Test
    fun `负零与小数往返只动符号不动数字写法`() {
        val s = intState()
        // 文本原样交给 long.TryParse（EmueraConsole.cs:1125）："-0" 不能被归一成别的数字，
        // "-1.5" 的小数点与位数也一个不动；点两次回到原样。
        assertEquals("0", InputBarState.toggleSign("-0"))
        assertEquals("-0", InputBarState.toggleSign(InputBarState.toggleSign("-0")))
        assertEquals("1.5", InputBarState.toggleSign("-1.5"))
        assertEquals("-1.5", s.withSignToggled(s.withSignToggled("-1.5")))
        assertEquals("-1.50", s.withSignToggled("1.50"))
    }

    @Test
    fun `非数字键盘不显示负号开关也不改文本`() {
        val str = state(pending = InputRequest(InputRequest.InputType.STR))
        assertFalse("字符串等待不显示 ±", str.showsSignToggle)
        assertEquals("粘贴进字符串等待的负号原样保留", "-文本", str.withSignToggled("-文本"))
        assertEquals("", str.withSignToggled(""))

        val waiting = state(awaitingAnyKey = true)
        assertFalse("任意键等待也不显示 ±", waiting.showsSignToggle)
        assertEquals("-5", waiting.withSignToggled("-5"))

        // 纯函数本身不认识键盘；闸门在 withSignToggled（壳层开关同样按 numberKeyboard 隐藏）。
        assertEquals("-5", InputBarState.toggleSign("5"))
    }

    @Test
    fun `粘贴文本里的负号保留`() {
        val s = intState()
        // 不在开头的负号：只在前缀增删，正文逐字不动。
        assertEquals("-1-2", InputBarState.toggleSign("1-2"))
        assertEquals("1-2", InputBarState.toggleSign("-1-2"))
        // 粘贴进来的带符号数字：点一次只摘掉最前面那个符号，其余原样；再点回到原样。
        assertEquals("7", s.withSignToggled("-7"))
        assertEquals("-7", s.withSignToggled(s.withSignToggled("-7")))
        // 只有孤零零一个负号时，点一次就是清掉它。
        assertEquals("", InputBarState.toggleSign("-"))
    }

    @Test
    fun `正常提交后文本框清空`() {
        val s = state(pending = InputRequest(InputRequest.InputType.STR))
        // PressEnterKey → updateInputs(text) → richTextBox1.Text = ""（MainWindow.cs:933、:965）
        assertEquals("", s.textBoxAfterSubmit("こんにちは"))
        assertEquals("", s.textBoxAfterSubmit(""))
    }

    @Test
    fun `抢跑提交后文本框保留原文`() {
        val s = state()
        assertTrue("非等待期提交是抢跑", s.isPrerun)
        // 原版此时被 !IsInProcess 挡住、一个字都不改（MainWindow.cs:922-936）；
        // 玩家敲的内容要继续留在框里，脚本靠 GETTEXTBOX() != "" 感知（TITLE.ERB:168）。
        assertEquals("0", s.textBoxAfterSubmit("0"))
        // 空文本时原版连框都不碰：里面可能是 SETTEXTBOX 刚写的状态串（qol_MAP.ERB:627）。
        assertNull(s.textBoxAfterSubmit(""))
    }

    @Test
    fun `禁用态不产生提交`() {
        val s = state(hasHost = false)
        assertEquals(InputBarState.Submit.DISABLED, s.submit)
        assertNull("没有提交就没有写框动作", s.textBoxAfterSubmit("5"))
    }
}
