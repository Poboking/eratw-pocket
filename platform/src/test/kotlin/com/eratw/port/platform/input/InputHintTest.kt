package com.eratw.port.platform.input

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [InputHint] 的四档提示语 —— 2026-10-29 玩家反馈「没提示」的回归锁。
 *
 * ## 期望值出处
 * - WAIT 系那一档必须点出**两条**推进通道（点画面空白 / 「继续」键），对应原版
 *   `MainWindow.cs:806`（`IsWaitingEnterKey && selectedString == null` ⇒ 左键
 *   `PressEnterKey`，`:818-820`）与 `EmueraConsole.cs:324-343`（只有 `EnterKey` /
 *   `AnyKey` 算 WAIT 系）。这一档原先**没有分支**，玩家停在 `PRINTFORMW` 页上看到的
 *   仍是「输入后回车提交」，于是只能自己去手输 —— 正是反馈里那句「也没提示」。
 * - 其余三档与 `InputBarState` 的 `Submit` / `awaitingAnyKey` 字段一一对应。
 */
class InputHintTest {

    private fun state(
        hasHost: Boolean = true,
        running: Boolean = true,
        awaitingAnyKey: Boolean = false,
        pendingInt: Boolean = false,
    ): InputBarState = InputBarState.of(
        hasHost = hasHost,
        running = running,
        pending = if (pendingInt) {
            com.eratw.port.core.host.InputRequest(
                form = com.eratw.port.core.host.InputRequest.RequestForm.VALUE,
                type = com.eratw.port.core.host.InputRequest.InputType.INT,
            )
        } else {
            null
        },
        awaitingAnyKey = awaitingAnyKey,
    )

    @Test
    fun `任意键等待页提示点画面或按继续`() {
        assertEquals(
            "PRINTW / PRINTFORMW 这类页面屏上没有 [N]，必须把「点空白」和「继续」两条通道都写出来",
            InputHint.CONTINUE,
            InputHint.of(state(awaitingAnyKey = true)),
        )
    }

    @Test
    fun `常规取值等待提示输入后回车`() {
        assertEquals(InputHint.NORMAL, InputHint.of(state(pendingInt = true)))
    }

    @Test
    fun `非等待期提交提示抢跑口径`() {
        assertEquals(InputHint.PRERUN, InputHint.of(state()))
    }

    @Test
    fun `宿主停转提示游戏未运行`() {
        assertEquals(InputHint.NOT_RUNNING, InputHint.of(state(hasHost = false)))
        assertEquals(
            "VM 线程已停与宿主缺失同档：两者都提交不了",
            InputHint.NOT_RUNNING,
            InputHint.of(state(running = false)),
        )
    }

    @Test
    fun `WAIT 系优先于抢跑且不被禁用档盖掉之外的分支污染`() {
        // awaitingAnyKey=true 时 submit 必为 NORMAL（InputBarState.of），四档互斥：
        // 任一组合下提示语都必须恰好落在四档之一，不允许出现空串或未覆盖的落空值。
        val allowed = setOf(InputHint.NOT_RUNNING, InputHint.CONTINUE, InputHint.PRERUN, InputHint.NORMAL)
        for (hasHost in listOf(true, false)) {
            for (running in listOf(true, false)) {
                for (awaiting in listOf(true, false)) {
                    for (pendingInt in listOf(true, false)) {
                        val text = InputHint.of(state(hasHost, running, awaiting, pendingInt))
                        assertEquals(
                            "hasHost=$hasHost running=$running awaiting=$awaiting pendingInt=$pendingInt",
                            true,
                            text in allowed,
                        )
                    }
                }
            }
        }
    }
}
