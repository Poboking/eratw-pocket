package com.eratw.port.platform.input

import com.eratw.port.render.input.InputSession
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.input.MacroKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KeyMacroController] 的判据：F1-F12 只预填、显式提交才产出 [com.eratw.port.render.input.Submission]、
 * 组切换沿用宏表语义、无宏时不提交。
 *
 * ## 期望值出处
 * - 无修饰 F1-F12 预填且不回车：`MainWindow.cs:507-512`（只有 `Text=` 与 `SelectionStart=` 两句，
 *   全段没有 `PressEnterKey`）；板上口径 `docs/golden-runbook.md:106`、`:125`。
 * - Shift+F1-F12 记录、空框不覆盖：`MainWindow.cs:499-506`。
 * - Ctrl+数字换组、该键不吞：`MainWindow.cs:514-528`，`:527` 之后走 `:530` 的 `break`。
 * - 等待原始键不响应：`MainWindow.cs:385-388`；`Config.UseKeyMacro` 关闭不响应：`:488-491`。
 * - 提交口：`MainWindow.cs:922-936`（`PressEnterKey` 本体，守卫 `:924`）；
 *   键盘回车的实参是 `mesSkip = false, inputsByMouse = false`（`:1407-1414`），
 *   本波触屏提交按鼠标左键那一路取 `inputsByMouse = true`（`:818-820`）。
 * - 组名标签：`setNewMacroGroup`（`MainWindow.cs:1601-1611`），文本源
 *   [KeyMacroTable.groupName]。
 *
 * 基准 `pc-golden-root.txt` 不含宏/提交行，故本文件只钉上述 C# 行。
 */
class KeyMacroControllerTest {

    private val scheme = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE

    /** 建一个会话，宏按 `macro.txt` 的读盘形态（`KeyMacroTable.loadLines`）预置。 */
    private fun controllerWithMacro(
        value: String,
        fkey: Int = 0,
        group: Int = 0,
    ): KeyMacroController {
        val table = KeyMacroTable(scheme)
        table.loadLines(listOf(table.nameOf(fkey, group) + value))
        return KeyMacroController(InputSession(macroTable = table))
    }

    @Test
    fun `F1只预填不提交`() {
        val controller = controllerWithMacro("123")
        val outcome = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)

        assertTrue(outcome.decision is MacroKeys.Decision.Prefill)
        assertEquals(":509 取宏", "123", (outcome.decision as MacroKeys.Decision.Prefill).text)
        assertEquals(":509 写进输入框", "123", controller.session.box.text)
        assertEquals(":510 插入点放到末尾", 3, controller.session.box.selectionStart)
        assertTrue("整段没有 PressEnterKey ⇒ 流水必须为空", controller.session.submissions.isEmpty())
        assertTrue(":511 return true 吞掉功能键", outcome.consumed)
        assertNull(outcome.groupLabel)
    }

    @Test
    fun `显式提交产生Submission`() {
        val controller = controllerWithMacro("123")
        controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertTrue("预填之后仍为零产出", controller.session.submissions.isEmpty())

        val submission = controller.submit()
        assertNotNull(submission)
        assertEquals(":926 读框", "123", submission!!.text)
        assertFalse("本波契约：提交不带 mesSkip", submission.mesSkip)
        assertTrue("触屏提交按鼠标左键那一路（:818-820）传 true", submission.inputsByMouse)
        assertEquals("提交口只该产出这一条", listOf(submission), controller.session.submissions)
        assertEquals("提交后清框（history.record 的 Text = 空）", "", controller.session.box.text)
    }

    @Test
    fun `组切换后F1取对应组的宏`() {
        val table = KeyMacroTable(scheme)
        table.loadLines(
            listOf(
                table.nameOf(fkey = 0, group = 0) + "组零宏",
                table.nameOf(fkey = 0, group = 1) + "组一宏",
            ),
        )
        val controller = KeyMacroController(InputSession(macroTable = table))

        val switched = controller.onKey(
            keyCode = MacroKeys.VK_DIGIT_0 + 1,
            modifier = MacroKeys.Modifier.CONTROL,
        )
        assertTrue(switched.decision is MacroKeys.Decision.SwitchGroup)
        assertEquals(":527 setNewMacroGroup(1)", 1, controller.session.macroGroup)
        assertEquals("组名标签取 groupName（:1605）", "设置为按键宏组1", switched.groupLabel)
        assertFalse(":527 之后走 :530 的 break ⇒ 换组键不吞", switched.consumed)

        val prefilled = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertTrue(prefilled.decision is MacroKeys.Decision.Prefill)
        assertEquals(":509 取的是新组槽位", "组一宏", controller.session.box.text)
        assertTrue("换组与预填都不提交", controller.session.submissions.isEmpty())
    }

    @Test
    fun `无宏时F1清框且不提交`() {
        val controller = KeyMacroController()
        controller.session.box.assign("旧内容")

        val outcome = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertTrue(outcome.decision is MacroKeys.Decision.Prefill)
        assertEquals(
            "空宏照样赋值",
            "",
            (outcome.decision as MacroKeys.Decision.Prefill).text,
        )
        assertEquals(":509 那一路没有空值守卫 ⇒ 清框", "", controller.session.box.text)
        assertTrue("无宏也不提交", controller.session.submissions.isEmpty())
    }

    @Test
    fun `Shift加功能键记录框内文本且不提交`() {
        val controller = KeyMacroController()
        controller.session.box.assign("存进宏")

        val outcome = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.SHIFT)
        assertTrue(outcome.decision is MacroKeys.Decision.Record)
        assertEquals(":503 写表", "存进宏", controller.session.macroTable.get(fkey = 0, group = 0))
        assertEquals("记录那一路不动框", "存进宏", controller.session.box.text)
        assertTrue("记录也不提交", controller.session.submissions.isEmpty())
        assertTrue(":505 return true 吞掉 Shift+Fn", outcome.consumed)
    }

    @Test
    fun `等待原始键时宏不响应`() {
        val controller = controllerWithMacro("123")
        controller.session.box.assign("原内容")

        val outcome = controller.onKey(
            keyCode = MacroKeys.VK_F1,
            modifier = MacroKeys.Modifier.NONE,
            waitingPrimitive = true,
        )
        assertTrue(outcome.decision is MacroKeys.Decision.Ignored)
        assertFalse(":385-388 提前返回 false，键交回系统", outcome.consumed)
        assertEquals("不响应时不许动框", "原内容", controller.session.box.text)
        assertTrue(controller.session.submissions.isEmpty())
    }

    /**
     * `Config.UseKeyMacro` 的守卫在 `default:` 分支**最前面**（`MainWindow.cs:488-491`），
     * 比换组那一段（`:514-528`）更早 ⇒ 开关关掉时 Ctrl+数字**也不换组**。
     * 这一条容易漏：换组看起来与宏内容无关，但原版确实把它一起关了。
     */
    @Test
    fun `UseKeyMacro关闭时Ctrl加数字也不换组`() {
        val controller = controllerWithMacro("123")
        val outcome = controller.onKey(
            keyCode = MacroKeys.VK_DIGIT_0 + 4,
            modifier = MacroKeys.Modifier.CONTROL,
            useKeyMacro = false,
        )
        assertTrue(":488-491 直接 break", outcome.decision is MacroKeys.Decision.Ignored)
        assertFalse(outcome.consumed)
        assertEquals("组号不动", 0, controller.session.macroGroup)
        assertNull(outcome.groupLabel)
    }

    /**
     * Ctrl+F1 落在 `:496-498` 的功能键区间里，但三个修饰旗标（`:493-495`）都不成立，
     * 于是用不上任何一支、走到 `:530` 的 `break` —— **不响应也不吞键**。
     */
    @Test
    fun `Ctrl加功能键不响应也不吞键`() {
        val controller = controllerWithMacro("123")
        controller.session.box.assign("原内容")
        val outcome = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.CONTROL)

        assertTrue(outcome.decision is MacroKeys.Decision.Ignored)
        assertFalse(":530 break ⇒ 键交回控件", outcome.consumed)
        assertEquals("不动框", "原内容", controller.session.box.text)
        assertTrue(controller.session.submissions.isEmpty())
    }
}
