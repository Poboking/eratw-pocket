package com.eratw.port.platform.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BlankTapPolicy] 的判据：只有「宿主就绪 + 无覆盖层 + 正等回车/任意键 + 未命中按钮 +
 * 本击没有回贴底」才补一条回车，且补的是输入框草稿。
 *
 * ## 期望值出处
 * - 原版左键在非按钮区提交回车：`reference/emuera-cs` 下
 *   `MinorShift.Emuera.Forms/MainWindow.cs:806` 的条件
 *   （`console.IsWaitingEnterKey && selectedString == null`）与 `:818-820` 的
 *   `PressEnterKey(mesSkip: false, inputsByMouse: true)`；
 * - 「本击回贴底则只回贴底、不回车」：`:799` 的 `flag = vScrollBar.Value != vScrollBar.Maximum`
 *   与 `:808` 的 `if (!flag)` —— `:810-821` 的三个分支全在这个门里；
 * - 「交草稿而不是空串」：`:926` 的 `string text = richTextBox1.Text;` 与 `:934` 的
 *   `console.PressEnterKey(mesSkip, text, inputsByMouse)`；
 * - 「等回车/任意键」的判据是 `EmueraConsole.cs:324-343`，`INPUT` / `INPUTS` 的
 *   数值与字符串等待不在其中 —— 所以 [InputBarState.canSkip] 为 false 时不补；
 * - 保守口径（覆盖层 / 加载期 / 无宿主不补）来自内部契约（未随公开仓发布），
 *   不是 C# 直译：原版没有 Compose 覆盖层，加载期也没有可点的控制台。
 *
 * 基准 `pc-golden-root.txt` 没有触摸手势行，故本文件只钉上述 C# 行与契约口径。
 * 真机屏上行为仍是 TODO(verify)（本机无 adb）。
 */
class BlankTapPolicyTest {

    private fun policy(
        hasHost: Boolean = true,
        overlayVisible: Boolean = false,
        canSkip: Boolean = true,
        hitButton: Boolean = false,
        snappedToBottom: Boolean = false,
    ): Boolean = BlankTapPolicy.shouldSubmitEnter(
        hasHost = hasHost,
        overlayVisible = overlayVisible,
        canSkip = canSkip,
        hitButton = hitButton,
        snappedToBottom = snappedToBottom,
    )

    @Test
    fun `等待回车或任意键且点空白时补一条回车`() {
        assertTrue("宿主在跑、无覆盖层、WAIT 系等待、未命中、未滚动 ⇒ 原版会提交一次回车", policy())
    }

    @Test
    fun `本击回贴底时只回贴底不回车`() {
        // MainWindow.cs:799 的 flag 为真 ⇒ :801-805 只把 Value 拉回 Maximum，
        // :806 块内的 :810-821 全在 `if (!flag)`（:808）里 ⇒ 这一击不提交。
        assertFalse("滚动着看历史时点空白：只回贴底，不补回车", policy(snappedToBottom = true))
    }

    @Test
    fun `已贴底且点空白时交的是输入框草稿`() {
        // MainWindow.cs:926 的 `string text = richTextBox1.Text;` 就是输入框草稿，
        // 一路传到 :934 的 console.PressEnterKey；点空白与点提交键同一条路。
        assertEquals("已贴底 ⇒ 允许这一击回车", true, policy(snappedToBottom = false))
        assertEquals("交的是框里的草稿，不是一个固定空串", "小町", BlankTapPolicy.textToSubmit("小町"))
    }

    @Test
    fun `草稿为空时交空串`() {
        // 原版空行提交照常有效（取默认值 / 消费一条 WAIT），见 InputBarState 类文档
        // 「空文本的去向」：草稿为空不是「不提交」，是提交一个空串。
        assertEquals("空草稿 ⇒ 空串（不是 null、也不是跳过提交）", "", BlankTapPolicy.textToSubmit(""))
        assertTrue("空草稿同样走回车路", policy())
    }

    @Test
    fun `草稿原样交出不被改写`() {
        // PressEnterKey 只做 IsWaintingOnePhrase 的前缀剥离（:927-931，那一路在
        // render/input/InputSession 里），本层不得再裁剪/trim/换行化。
        val drafts = listOf("", " ", " 0123 ", "@宏", "a\nb", "ーー")
        for (draft in drafts) {
            assertEquals("草稿 [$draft] 必须逐字交出", draft, BlankTapPolicy.textToSubmit(draft))
        }
    }

    @Test
    fun `命中按钮时不补回车`() {
        assertFalse("命中按钮走既有按钮提交路径，本策略不得叠加第二条提交", policy(hitButton = true))
    }

    @Test
    fun `等待数值或字符串输入时不补`() {
        // 原版 `IsWaitingEnterKey`（EmueraConsole.cs:324-343）在 INPUT/INPUTS 的取值等待上为 false；
        // 移植侧同一事实表现为 `InputBarState.canSkip == false`（awaitingAnyKey 只对 WAIT 系为真）。
        assertFalse("整数等待不补：空的整型提交会被 TryParse 丢弃，仍停在等待", policy(canSkip = false))
    }

    @Test
    fun `加载覆盖层可见时不补`() {
        assertFalse("装载期覆盖层盖住控制台，点击不该穿透成一次输入", policy(overlayVisible = true))
    }

    @Test
    fun `失败覆盖层可见时不补`() {
        assertFalse("失败屏上宿主已停转，补回车只会往已关闭的队列里塞一条", policy(overlayVisible = true))
    }

    @Test
    fun `没有宿主时不补`() {
        assertFalse("host 为 null 时没有提交去处（演示屏 / 产物缺失）", policy(hasHost = false))
        assertFalse("宿主停转时 canSkip 亦为 false，两道闸同时挡", policy(hasHost = false, canSkip = false))
    }

    @Test
    fun `三十二种输入组合只放行唯一一种`() {
        val booleans = listOf(true, false)
        var allowed = 0
        var snappedAllowed = 0
        for (hasHost in booleans) {
            for (overlayVisible in booleans) {
                for (canSkip in booleans) {
                    for (hitButton in booleans) {
                        for (snappedToBottom in booleans) {
                            val expected =
                                hasHost && !overlayVisible && canSkip && !hitButton && !snappedToBottom
                            val actual = BlankTapPolicy.shouldSubmitEnter(
                                hasHost, overlayVisible, canSkip, hitButton, snappedToBottom,
                            )
                            val label = "hasHost=$hasHost overlay=$overlayVisible canSkip=$canSkip " +
                                "hit=$hitButton snapped=$snappedToBottom"
                            if (expected) assertTrue(label, actual) else assertFalse(label, actual)
                            if (expected) allowed++
                            if (actual && snappedToBottom) snappedAllowed++
                        }
                    }
                }
            }
        }
        assertEquals("真值表只有一种组合放行", 1, allowed)
        assertEquals("回贴底那一维单独就能封死全部放行组合", 0, snappedAllowed)
    }

    @Test
    fun `双击跳过的 canSkip 闸门不被本策略放宽`() {
        // 双击跳过由装配层 `onDoubleTap` 按 `InputBarState.canSkip` 独立决定（MainActivity），
        // 策略本身没有手势入参、也不产 skip；只要 canSkip 为假，任何其它组合都不得补输入，
        // 否则「等待输入时双击不排队」那条既有口径会被空白补回车破坏。
        val booleans = listOf(true, false)
        for (hasHost in booleans) {
            for (overlayVisible in booleans) {
                for (hitButton in booleans) {
                    for (snappedToBottom in booleans) {
                        assertFalse(
                            "canSkip=false 时不得补回车（hasHost=$hasHost overlay=$overlayVisible " +
                                "hit=$hitButton snapped=$snappedToBottom）",
                            BlankTapPolicy.shouldSubmitEnter(
                                hasHost, overlayVisible, false, hitButton, snappedToBottom,
                            ),
                        )
                    }
                }
            }
        }
    }
}
