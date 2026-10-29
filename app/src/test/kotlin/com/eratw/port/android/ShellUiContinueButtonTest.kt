package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [showContinueButton] 是 2026-10-29 玩家反馈
 * 「点进钓鱼列表，最下面没有退出这个键，要自己手输（也没提示）」的修复契约。
 *
 * ## 现象与根因
 * 钓鱼列表页（`SHOW_FISHING_LIST` / `SHOW_FISHING_LIST_SELECT`）末尾是 `PRINTFORMW`，
 * 即 **WAIT 系**等待：原版这类页面**屏上不印任何 `[N]`**，推进方式是「点一下画面空白」
 * 或敲任意键（`reference/emuera-cs` 下 `MinorShift.Emuera.Forms/MainWindow.cs:806`
 * 的 `console.IsWaitingEnterKey && selectedString == null` ⇒ `:818-820` 的
 * `PressEnterKey(mesSkip: false, inputsByMouse: true)`；`IsWaitingEnterKey` 只对
 * `EnterKey` / `AnyKey` 两种等待为真，`EmueraConsole.cs:324-343`）。
 * 触摸端既没有回车键，「点空白也能继续」也没有视觉暗示 ⇒ 玩家看到的是一页
 * 没有任何可点物的正文，只能去底部输入条手输。
 *
 * ## 判据
 * 脚钮**只**在 WAIT 系出现：[showContinueButton] 与 [com.eratw.port.platform.input.InputBarState.canSkip]
 * 同源（`awaitingAnyKey`）。`INPUT` / `INPUTS` 的取值等待上不能出现这枚按钮 ——
 * 那时提交空文本会被 `long.TryParse` 丢弃、页面反而更让人困惑。
 *
 * 为何是 JVM 单测而非 instrumented：判据本身无 Android 运行期依赖（同
 * `ShellUiRailReservationTest` 的先例）；Compose 侧的摆放（栏底常驻、不随
 * `LazyColumn` 滚走）由布局本身保证。
 */
class ShellUiContinueButtonTest {

    @Test
    fun waitSystem_showsContinueButton() {
        assertTrue(
            "PRINTW / PRINTFORMW / WAIT 页屏上没有 [N]，必须给一枚显式推进键",
            showContinueButton(awaitingAnyKey = true),
        )
    }

    @Test
    fun normalInputWait_hidesContinueButton() {
        assertFalse(
            "INPUT / INPUTS 的取值等待不出现：空提交会被丢弃，玩家仍停在原地",
            showContinueButton(awaitingAnyKey = false),
        )
    }
}
