package com.eratw.port.platform

import com.eratw.port.core.audio.AudioFocusEvent
import com.eratw.port.core.host.InputRequest
import com.eratw.port.platform.audio.AudioFocusChange
import com.eratw.port.platform.audio.AudioFocusMapping
import com.eratw.port.platform.audio.AudioFocusRaw
import com.eratw.port.platform.audio.AudioFocusRequester
import com.eratw.port.platform.audio.AudioFocusSession
import com.eratw.port.platform.audio.AudioFocusTarget
import com.eratw.port.platform.input.InputBarState
import com.eratw.port.platform.input.KeyMacroController
import com.eratw.port.platform.input.TouchGestureController
import com.eratw.port.render.input.InputSession
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.input.MacroKeys
import com.eratw.port.render.input.Submission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入条 / 双击跳过 / 音频焦点的平台契约（纯 JVM，不碰 Android API）。
 *
 * ## 期望值出处
 * - 输入框属性：`MaxLength = 32767`、`Multiline = false`
 *   （`MainWindow.cs:1922-1923`，设计器里写死）。
 * - 空文本去向：整数默认值/丢弃（`EmueraConsole.cs:1119-1128`）、字符串默认值/空串
 *   （`:1190-1203`）、WAIT 系忽略文本（`:1443-1448`）。
 * - 四条提交入口的实参：回车 `mesSkip=false, inputsByMouse=false`（`MainWindow.cs:1407-1414`）、
 *   鼠标左键/触屏提交 `false, true`（`:818-820`）、`Esc` 取消 `true, false`（`:1415-1422`）、
 *   右键/双击跳过 `true, true`（`:814-816`）。守卫统一在 `:922-936`，空文本无守卫。
 * - 双击跳过只在 WAIT 系生效：`MainWindow.cs:806` 的 `console.IsWaitingEnterKey`，
 *   该属性只对 `EnterKey` / `AnyKey` 为真（`EmueraConsole.cs:324-343`）。
 * - 多行提交切分：鼠标那一路整条（`EmueraConsole.cs:1406-1409`），键盘那一路按
 *   `spliter` 四元素切（`:1429`，表在 `:146`，其中含字面量反斜杠加 n）。
 * - 宏只预填不提交：`MainWindow.cs:507-512`；换组标签 200ms × 11 拍
 *   （`:1590-1611`，判定 `labelTimerCount > 10`）。
 * - 音频焦点没有 C# 语义（原版无焦点概念，见 `core/audio/AudioFocus.kt` 的取证）；
 *   原始常量取自 `android-35/android.jar` 的 javap 读数，映射规则见 `AudioFocusRaw`。
 * - 双击/长按/滑动的阈值 300ms / 500ms 属移植侧口径，C# 无行号（见
 *   `TouchGestureController` 类文档）。
 */
class InputAudioContractTest {

    private val scheme = KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE

    private fun macroController(fkey: Int = 0, group: Int = 0, value: String): KeyMacroController {
        val table = KeyMacroTable(scheme)
        table.loadLines(listOf(table.nameOf(fkey, group) + value))
        return KeyMacroController(InputSession(macroTable = table))
    }

    // ------------------------------------------------------------------ 输入条

    @Test
    fun `输入条_整数与字符串等待的空提交去向`() {
        val intDefault = InputBarState.of(
            hasHost = true,
            running = true,
            pending = InputRequest(InputRequest.InputType.INT, hasDef = true),
            awaitingAnyKey = false,
        )
        assertEquals("整数等待切数字键盘", InputBarState.Keyboard.SIGNED_NUMBER, intDefault.keyboard)
        assertEquals(
            "空行 + 有默认值 ⇒ 取 DefIntValue（EmueraConsole.cs:1120-1124）",
            InputBarState.EmptyText.DEFAULT_VALUE,
            intDefault.emptyText,
        )
        assertTrue("INPUT 解释文本", intDefault.textConsumed)

        val intNoDef = InputBarState.of(
            hasHost = true,
            running = true,
            pending = InputRequest(InputRequest.InputType.INT),
            awaitingAnyKey = false,
        )
        assertEquals(
            "空行 + 无默认值 ⇒ long.TryParse 失败、停在等待（:1125-1128）",
            InputBarState.EmptyText.DISCARDED,
            intNoDef.emptyText,
        )

        val strDefault = InputBarState.of(
            hasHost = true,
            running = true,
            pending = InputRequest(InputRequest.InputType.STR, hasDef = true),
            awaitingAnyKey = false,
        )
        assertEquals(
            "字符串空行 + 默认值 ⇒ DefStrValue（:1191-1194）",
            InputBarState.EmptyText.DEFAULT_VALUE,
            strDefault.emptyText,
        )
        assertEquals(InputBarState.Keyboard.TEXT, strDefault.keyboard)

        val strNoDef = InputBarState.of(
            hasHost = true,
            running = true,
            pending = InputRequest(InputRequest.InputType.STR),
            awaitingAnyKey = false,
        )
        assertEquals(
            "字符串空行无默认值 ⇒ 提交空串（:1195-1203）",
            InputBarState.EmptyText.LITERAL,
            strNoDef.emptyText,
        )
    }

    @Test
    fun `输入条_WAIT系不解释文本且只有WAIT可跳过`() {
        val wait = InputBarState.of(
            hasHost = true,
            running = true,
            pending = null,
            awaitingAnyKey = true,
        )
        assertEquals(InputBarState.Submit.NORMAL, wait.submit)
        assertFalse("WAIT 系只消费一条提交，不解释文本", wait.textConsumed)
        assertEquals(InputBarState.EmptyText.IGNORED, wait.emptyText)
        assertTrue(
            "IsWaitingEnterKey 对 EnterKey/AnyKey 为真（MainWindow.cs:806、EmueraConsole.cs:324-343）",
            wait.canSkip,
        )

        val input = InputBarState.of(
            hasHost = true,
            running = true,
            pending = InputRequest(InputRequest.InputType.STR),
            awaitingAnyKey = false,
        )
        assertTrue("INPUT 仍可正常提交", input.canSubmit)
        assertFalse("INPUT/INPUTS 的取值等待不在 IsWaitingEnterKey 里 ⇒ 双击不跳过", input.canSkip)

        val idle = InputBarState.of(hasHost = true, running = true, pending = null, awaitingAnyKey = false)
        assertEquals("非等待期抢跑", InputBarState.Submit.PRERUN, idle.submit)
        assertFalse("原版非等待期右键不跳过（MainWindow.cs:806 的闸）", idle.canSkip)
        assertEquals(
            "抢跑文本由之后取走它的等待决定，本层不预判",
            InputBarState.EmptyText.UNDETERMINED,
            idle.emptyText,
        )

        val demo = InputBarState.of(hasHost = false, running = false, pending = null, awaitingAnyKey = true)
        assertEquals(InputBarState.Submit.DISABLED, demo.submit)
        assertFalse("宿主不在时跳过同样没有去处（:924 同闸）", demo.canSkip)
    }

    @Test
    fun `输入条_单行与长度上限与四条提交实参`() {
        assertFalse("原版 Multiline=false（MainWindow.cs:1923）", InputBarState.MULTILINE)
        assertEquals("原版 MaxLength=32767（MainWindow.cs:1922）", 32767, InputBarState.MAX_TEXT_LENGTH)

        val enter = InputBarState.planFor(InputBarState.SubmitSource.ENTER_KEY)
        assertFalse("回车 mesSkip=false（:1412）", enter.mesSkip)
        assertFalse("回车 inputsByMouse=false（:1412）", enter.inputsByMouse)
        assertTrue("键盘那一路按 spliter 切（EmueraConsole.cs:1429）", enter.splitsLines)

        val touch = InputBarState.planFor(InputBarState.SubmitSource.TOUCH_SUBMIT)
        assertFalse("触屏提交=鼠标左键（:820）", touch.mesSkip)
        assertTrue("鼠标那一路 inputsByMouse=true（:820）", touch.inputsByMouse)
        assertFalse("鼠标那一路整条不切（:1406-1409）", touch.splitsLines)

        val cancel = InputBarState.planFor(InputBarState.SubmitSource.CANCEL_ESCAPE)
        assertTrue("Esc 取消带 mesSkip（:1421）", cancel.mesSkip)
        assertFalse("Esc 是键盘路径（:1421）", cancel.inputsByMouse)
        assertTrue(cancel.splitsLines)

        val skip = InputBarState.planFor(InputBarState.SubmitSource.DOUBLE_TAP_SKIP)
        assertTrue("右键/双击跳过带 mesSkip（:816）", skip.mesSkip)
        assertTrue("跳过是指针手势（:816）", skip.inputsByMouse)
        assertFalse(skip.splitsLines)
    }

    // ------------------------------------------------------------------ 提交路径

    @Test
    fun `提交_回车与取消走键盘实参且取消带跳过`() {
        val enter = KeyMacroController()
        enter.session.box.assign("第一行\r\n第二行")
        val submission = enter.submitEnterKey()
        assertNotNull("回车是有效提交", submission)
        assertEquals(":926 读框内全文，多行原样交给核心层", "第一行\r\n第二行", submission!!.text)
        assertFalse("回车不跳过（:1412）", submission.mesSkip)
        assertFalse("回车不是鼠标路径（:1412）", submission.inputsByMouse)
        assertEquals("updateInputs 清框（:942/:965）", "", enter.session.box.text)

        val cancel = KeyMacroController()
        cancel.session.box.assign("取消也提交")
        val cancelled = cancel.submitCancel()
        assertNotNull("原版 Esc 也是提交，不是清空输入框", cancelled)
        assertEquals("框内文本照样交给核心层", "取消也提交", cancelled!!.text)
        assertTrue("Esc 带 mesSkip（:1421）", cancelled.mesSkip)
        assertFalse("Esc 是键盘路径（:1421）", cancelled.inputsByMouse)

        val blocked = KeyMacroController()
        blocked.session.inProcess = true
        assertNull("console.IsInProcess 时提交被挡（:924）", blocked.submitEnterKey())
        assertNull(blocked.submitCancel())
        assertTrue(blocked.session.submissions.isEmpty())
    }

    @Test
    fun `提交_触屏与双击跳过按指针实参且空框也有效`() {
        val touch = KeyMacroController()
        touch.session.box.assign("点按")
        val submitted = touch.submit()
        assertNotNull(submitted)
        assertFalse(submitted!!.mesSkip)
        assertTrue("触屏提交按鼠标左键那一路（:818-820）", submitted.inputsByMouse)

        val skip = KeyMacroController()
        skip.session.box.assign("跳过文本")
        val skipped = skip.submitSkip()
        assertNotNull(skipped)
        assertEquals("跳过携带框内文本（与 GameHost.submitSkip 同语义）", "跳过文本", skipped!!.text)
        assertTrue("跳过 mesSkip=true", skipped.mesSkip)
        assertTrue("双击是手指指针，按右键那一路（:816）", skipped.inputsByMouse)
        assertEquals("提交后清框", "", skip.session.box.text)

        val empty = KeyMacroController().submitSkip()
        assertNotNull("空框跳过同样有效：:922-936 没有空值守卫", empty)
        assertEquals("提交的是空串", "", empty!!.text)
        assertTrue(empty.mesSkip)
    }

    @Test
    fun `多行_键盘提交按四元素切分而指针提交整条`() {
        val session = InputSession()
        val keyboard = session.splitPieces(Submission("a\r\nb\nc", mesSkip = false, inputsByMouse = false))
        assertEquals(
            "键盘那一路按 spliter 切成多条（EmueraConsole.cs:1429）",
            listOf("a", "b", "c"),
            keyboard.map { it.text },
        )
        val pointer = session.splitPieces(Submission("a\r\nb\nc", mesSkip = false, inputsByMouse = true))
        assertEquals(
            "鼠标那一路只留一整条（:1406-1409）",
            listOf("a\r\nb\nc"),
            pointer.map { it.text },
        )
        val literal = session.splitPieces(Submission("a\\nb", mesSkip = false, inputsByMouse = false))
        assertEquals(
            "切分符表含字面量反斜杠加 n（:146），所以它也会断开 —— 官方行为",
            listOf("a", "b"),
            literal.map { it.text },
        )
    }

    // ------------------------------------------------------------------ 手势

    @Test
    fun `手势_双击跳过_长按与滑动中断配对`() {
        val gesture = TouchGestureController()
        fun tap(col: Int, downMs: Long, upMs: Long = downMs) =
            gesture.onDown(col, 100f, downMs).let { gesture.onUp(100f, upMs) }

        assertEquals("第一击仍是单击", TouchGestureController.Action.Tap(3), tap(3, 0L))
        assertEquals("300ms 闭区间内第二击 ⇒ 跳过", TouchGestureController.Action.Skip, tap(3, 300L))

        gesture.onDown(3, 100f, 400L)
        assertEquals(
            "按住 500ms ⇒ 长按，不是单击也不是双击（GestureDetector 抑制 onSingleTapUp）",
            TouchGestureController.Action.LongPress,
            gesture.onUp(100f, 900L),
        )
        assertEquals("长按之后紧接的一击重新算单击", TouchGestureController.Action.Tap(3), tap(3, 950L))

        gesture.onDown(3, 100f, 1000L)
        assertEquals(
            "纵向够半行优先判滑动，抬手不补跳过（EmueraConsole.cs:1406-1409 不被双击污染）",
            TouchGestureController.Action.Scroll(3),
            gesture.onUp(43f, 1050L),
        )
        assertEquals("滑动清掉双击候选", TouchGestureController.Action.Tap(3), tap(3, 1100L))
    }

    // ------------------------------------------------------------------ 快捷宏

    @Test
    fun `宏_F1只预填_回车仍要手按`() {
        val controller = macroController(value = "1,2,3")
        val outcome = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE)
        assertTrue(outcome.decision is MacroKeys.Decision.Prefill)
        assertEquals(":509 写框", "1,2,3", controller.session.box.text)
        assertTrue("预填不提交（:507-512 没有 PressEnterKey）", controller.session.submissions.isEmpty())
        assertTrue(":511 吞掉功能键", outcome.consumed)

        controller.submitEnterKey()
        assertEquals("回车之后才有一条提交", 1, controller.session.submissions.size)
        assertFalse(controller.session.submissions.single().mesSkip)
        assertFalse(
            "输入法回车是键盘路径（:1412）",
            controller.session.submissions.single().inputsByMouse,
        )
    }

    @Test
    fun `宏_换组标签按200毫秒11拍隐藏`() {
        val controller = KeyMacroController()
        val outcome = controller.onKey(MacroKeys.VK_DIGIT_0 + 3, MacroKeys.Modifier.CONTROL)
        assertTrue(outcome.decision is MacroKeys.Decision.SwitchGroup)
        assertEquals(":527 setNewMacroGroup(3)", 3, controller.session.macroGroup)
        assertFalse(":530 break ⇒ 换组键不吞", outcome.consumed)
        assertEquals(":1605 标签文本取组名", controller.session.macroTable.groupName(3), outcome.groupLabel)
        assertEquals(":1601-1611 标签同一次换组就显示", outcome.groupLabel, controller.groupLabel.text)
        assertEquals("11 拍 × 200ms", 2200L, controller.groupLabel.visibleMs)

        repeat(10) { tick ->
            assertTrue("第 ${tick + 1} 拍仍可见（:1593 的 > 10 还没到）", controller.groupLabel.onTick())
        }
        assertFalse("第 11 拍隐藏", controller.groupLabel.onTick())
        assertNull(controller.groupLabel.text)

        controller.onKey(MacroKeys.VK_DIGIT_0 + 4, MacroKeys.Modifier.CONTROL)
        repeat(5) { controller.groupLabel.onTick() }
        controller.onKey(MacroKeys.VK_DIGIT_0 + 5, MacroKeys.Modifier.CONTROL)
        assertEquals("重复换组把计数归零（:1603）", 0, controller.groupLabel.ticks)
        assertEquals(5, controller.session.macroGroup)
        assertTrue(controller.groupLabel.visible)
    }

    @Test
    fun `宏_开关关闭时三路都不动作_菜单空宏也不覆盖`() {
        val controller = KeyMacroController()
        controller.session.box.assign("原内容")

        val fkey = controller.onKey(MacroKeys.VK_F1, MacroKeys.Modifier.NONE, useKeyMacro = false)
        assertTrue(":488-491 直接 break", fkey.decision is MacroKeys.Decision.Ignored)
        assertFalse("不响应的键不吞", fkey.consumed)
        assertEquals("不动框", "原内容", controller.session.box.text)

        val group = controller.onKey(
            MacroKeys.VK_DIGIT_0 + 2,
            MacroKeys.Modifier.CONTROL,
            useKeyMacro = false,
        )
        assertTrue("开关在换组判定之前（:488-491 对 :514-528）", group.decision is MacroKeys.Decision.Ignored)
        assertEquals("组号不动", 0, controller.session.macroGroup)
        assertFalse(controller.groupLabel.visible)
        assertTrue(controller.session.submissions.isEmpty())

        val menu = controller.prefillFromMenu(fkey = 0, useKeyMacro = false)
        assertTrue("右键菜单那一路同受 UseKeyMacro 夹（:1567）", menu is MacroKeys.Decision.Ignored)
        assertEquals("菜单不动作时不动框", "原内容", controller.session.box.text)

        val emptyMacro = KeyMacroController()
        emptyMacro.session.box.assign("旧")
        assertTrue(
            "菜单那一路有 if (macro.Length > 0)（:1570-1574）⇒ 空宏不覆盖",
            emptyMacro.prefillFromMenu(fkey = 0) is MacroKeys.Decision.Ignored,
        )
        assertEquals("旧", emptyMacro.session.box.text)
    }

    // ------------------------------------------------------------------ 音频焦点

    @Test
    fun `焦点_原始常量映射与未知兜底`() {
        assertEquals(AudioFocusEvent.GAIN, AudioFocusMapping.fromRaw(AudioFocusRaw.GAIN))
        assertEquals(AudioFocusEvent.LOSS, AudioFocusMapping.fromRaw(AudioFocusRaw.LOSS))
        assertEquals(AudioFocusEvent.LOSS_TRANSIENT, AudioFocusMapping.fromRaw(AudioFocusRaw.LOSS_TRANSIENT))
        assertEquals(
            "CAN_DUCK 按瞬态暂停处理（不改脚本可见音量）",
            AudioFocusEvent.LOSS_TRANSIENT,
            AudioFocusMapping.fromRaw(AudioFocusRaw.LOSS_TRANSIENT_CAN_DUCK),
        )

        assertEquals(
            "未知负数只可能是失焦类 ⇒ 按瞬态挂起，仍可被 GAIN/onResume 恢复",
            AudioFocusEvent.LOSS_TRANSIENT,
            AudioFocusMapping.fromRaw(-9),
        )
        assertNull("未知正数绝不猜成 GAIN（否则后台会被拉起来出声）", AudioFocusMapping.fromRaw(9))
        assertNull("AUDIOFOCUS_NONE(0) 不是回调事件", AudioFocusMapping.fromRaw(AudioFocusRaw.NONE))
        assertNull(AudioFocusMapping.fromRawChange(4))
        assertEquals(AudioFocusChange.LOSS, AudioFocusMapping.fromRawChange(-1))
    }

    @Test
    fun `焦点_切后台暂停_回前台续播_申请被拒不恢复`() {
        val requester = ToggleRequester()
        val events = ArrayList<AudioFocusEvent>()
        val session = AudioFocusSession(
            requester,
            AudioFocusTarget { event ->
                events += event
                0
            },
        )

        assertTrue(session.start())
        assertEquals("拿到焦点合成一条 GAIN", listOf(AudioFocusEvent.GAIN), events)
        assertTrue(session.holdsFocus)

        assertTrue("onPause 放弃焦点（GameHost.onPause）", session.onHostPaused())
        assertEquals(
            "切后台必须先暂停：系统不保证回调，合成一条瞬态",
            listOf(AudioFocusEvent.GAIN, AudioFocusEvent.LOSS_TRANSIENT),
            events,
        )
        assertFalse(session.holdsFocus)
        assertEquals(1, requester.abandonCount)

        requester.granted = false
        assertFalse("回前台但系统拒绝 ⇒ 不恢复", session.onHostResumed())
        assertEquals("被拒时不合成 GAIN（否则等于无视系统）", 2, events.size)
        assertFalse(session.holdsFocus)

        requester.granted = true
        assertTrue(session.onHostResumed())
        assertEquals("再次回前台拿到焦点 ⇒ 续播", AudioFocusEvent.GAIN, events.last())
        assertEquals(3, events.size)
        assertTrue(session.holdsFocus)
    }

    /** 可切换授予结果的假申请方；记录申请/放弃次数。 */
    private class ToggleRequester : AudioFocusRequester {
        var granted: Boolean = true
        var acquireCount: Int = 0
            private set
        var abandonCount: Int = 0
            private set

        override fun acquire(onChange: (AudioFocusChange) -> Unit): Boolean {
            acquireCount++
            return granted
        }

        override fun abandon() {
            abandonCount++
        }
    }
}
