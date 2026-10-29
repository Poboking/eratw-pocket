package com.eratw.port.platform.input

import com.eratw.port.platform.input.TouchGestureController.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [TouchGestureController] 的判据：双击窗口、滑动符号与行数、单击列号透传。
 *
 * ## 期望值出处
 * - 双击 = 跳过是本波口径，不是 C# 直译：原版双击只触发剪贴板处理器
 *   （`MainWindow.cs:776-782`），跳过消息靠右键 `mesSkip = true`（`:814-816`）、
 *   `Esc`（`:1415-1422`）或 `SKIPLOG`（`Process.cs:1830-1833`）；
 *   `PressEnterKey` 落 `MesSkip` 在 `EmueraConsole.cs:1371-1373`，空转循环在 `:1451-1456`。
 *   板上口径：BOARD 的 M5-TOUCH 行、`docs/eratw-项目实施方案.md:165`。
 * - 行高 19px：`ConfigData.cs:67`（`LineHeight` 默认 19）；滚轮一格 1 行：
 *   `ConfigData.cs:74`（`ScrollHeight` 默认 1）与 `MainWindow.cs:1262`
 *   （`-Math.Sign(e.Delta) * SmallChange * ScrollHeight`）。
 * - 触屏不提交：`MainWindow.cs:507-512` 整段没有 `PressEnterKey`；
 *   `docs/golden-runbook.md:106`、`:125`。
 * - 长按 / 右键：原版右键就是「跳过」（`MainWindow.cs:814-816` 的 `mesSkip: true`），
 *   但触屏没有右键；C# 里**没有长按语义**，长按阈值为 Android `ViewConfiguration` 的
 *   500ms 缺省值，见 [TouchGestureController.Action.LongPress] 与主契约测试
 *   `InputAudioContractTest` 的手势用例。本文件其余用例都是 <500ms 的点击/滑动。
 *
 * 基准 `pc-golden-root.txt` 没有触摸手势行，故本文件只钉上述 C# 行与契约口径。
 */
class TouchGestureControllerTest {

    private fun controller(): TouchGestureController =
        TouchGestureController(
            pixelsPerLine = TouchGestureController.DEFAULT_PIXELS_PER_LINE,
            doubleTapWindowMs = TouchGestureController.DEFAULT_DOUBLE_TAP_WINDOW_MS,
        )

    /** 一次原地点击：按下与抬起的纵坐标相同，横坐标固定列 [col]。 */
    private fun tap(
        controller: TouchGestureController,
        col: Int,
        timeMs: Long,
        y: Float = 100f,
    ): Action {
        controller.onDown(col, y, timeMs)
        return controller.onUp(y, timeMs)
    }

    @Test
    fun `双击窗口内第二击产出跳过`() {
        val controller = controller()
        assertEquals("第一击仍是一次单击", Action.Tap(3), tap(controller, 3, 0L))
        assertEquals("窗口闭区间：恰好 300ms 仍算双击", Action.Skip, tap(controller, 3, 300L))
    }

    @Test
    fun `超窗两次点击各产出一次单击`() {
        val controller = controller()
        assertEquals(Action.Tap(3), tap(controller, 3, 0L))
        assertEquals("301ms 超窗 ⇒ 第二击仍是单击", Action.Tap(4), tap(controller, 4, 301L))
    }

    @Test
    fun `上滑三行给出正行数`() {
        val controller = controller()
        controller.onDown(col = 0, y = 100f, timeMs = 0L)
        val action = controller.onUp(y = 100f - 19f * 3, timeMs = 50L)
        assertEquals("手指上滑 57px / 19px = 3 行；正数 = 看更晚的行", Action.Scroll(3), action)
    }

    @Test
    fun `下滑两行给出负行数`() {
        val controller = controller()
        controller.onDown(col = 0, y = 0f, timeMs = 0L)
        val action = controller.onUp(y = 19f * 2, timeMs = 50L)
        assertEquals("手指下滑 38px / 19px = 2 行；负数 = 回看更早的行", Action.Scroll(-2), action)
    }

    @Test
    fun `单击列号按按下点透传`() {
        val controller = controller()
        assertEquals("列号是半角格，供 ButtonLayer 命中", Action.Tap(42), tap(controller, 42, 0L))
    }

    @Test
    fun `不足半行的位移仍算单击`() {
        val controller = controller()
        controller.onDown(col = 7, y = 100f, timeMs = 0L)
        assertEquals(
            "9px < 19px / 2，四舍五入到 0 行 ⇒ 单击",
            Action.Tap(7),
            controller.onUp(y = 109f, timeMs = 50L),
        )

        controller.onDown(col = 7, y = 0f, timeMs = 100L)
        assertEquals(
            "10px > 19px / 2，四舍五入到 1 行 ⇒ 滑动",
            Action.Scroll(-1),
            controller.onUp(y = 10f, timeMs = 150L),
        )
    }

    @Test
    fun `滑动中断双击配对`() {
        val controller = controller()
        assertEquals(Action.Tap(5), tap(controller, 5, 0L))
        controller.onDown(col = 5, y = 100f, timeMs = 100L)
        assertEquals("滑动不是点击", Action.Scroll(3), controller.onUp(y = 43f, timeMs = 150L))
        assertEquals(
            "150ms 后再点，但中间的滑动已清掉双击候选 ⇒ 仍是单击",
            Action.Tap(5),
            tap(controller, 5, 200L),
        )
    }

    @Test
    fun `取消的按下不产出动作`() {
        val controller = controller()
        controller.onDown(col = 1, y = 100f, timeMs = 0L)
        controller.onCancel()
        assertEquals(Action.None, controller.onUp(y = 100f, timeMs = 10L))
    }

    @Test
    fun `时间戳倒退不与上一击配对`() {
        val controller = controller()
        assertEquals(Action.Tap(1), tap(controller, 1, 500L))
        assertEquals(
            "时间戳倒退按窗口外处理，不能凭负数间隔判成双击",
            Action.Tap(2),
            tap(controller, 2, 400L),
        )
    }

    /**
     * 四舍五入的临界点必须方向对称：原实现直接 `-(dy / px).roundToInt()`，
     * `roundToInt` 在 .5 上向 +∞ 收（`0.5 → 1`、`-0.5 → 0`），于是「恰好半个行高」
     * 只在**下滑**时算一行、上滑时算单击。这里钉住「先取绝对值再定方向」。
     */
    @Test
    fun `恰好半个行高上下两个方向都算一行`() {
        val controller = controller()
        controller.onDown(col = 0, y = 100f, timeMs = 0L)
        assertEquals("下滑 9.5px = 半行 ⇒ -1 行", Action.Scroll(-1), controller.onUp(y = 109.5f, timeMs = 10L))

        controller.onDown(col = 0, y = 100f, timeMs = 20L)
        assertEquals("上滑 9.5px = 半行 ⇒ +1 行", Action.Scroll(1), controller.onUp(y = 90.5f, timeMs = 30L))
    }

    @Test
    fun `没按下就抬起给出 None`() {
        assertEquals("原版没有对应概念，只是绑定层的分支穷尽", Action.None, controller().onUp(y = 100f, timeMs = 0L))
    }

    @Test
    fun `三击的第二击是跳过 第三击重新算单击`() {
        val controller = controller()
        assertEquals(Action.Tap(2), tap(controller, 2, 0L))
        assertEquals("窗口内第二击", Action.Skip, tap(controller, 2, 100L))
        assertEquals("Skip 消费掉候选，第三击重新起算", Action.Tap(2), tap(controller, 2, 200L))
    }

    /**
     * 位置邻域闸（M10-PLAY 收口 M7 钉桩②③）：原版无双击跳过实现（`MainWindow.cs:776-782`
     * 双击只喂剪贴板；跳过靠右键 `:814-816` / `EmueraConsole.cs:1371-1373`），邻域阈值是
     * 纯产品判据 ——「真机快速点两个不同按钮不得被误判成跳过」。列距超过
     * [TouchGestureController.doubleTapColumnSlop] 的第二击按**新单击**起算。
     */
    @Test
    fun `窗口内但邻域外的第二击按新单击起算`() {
        val controller = controller()
        assertEquals(Action.Tap(10), tap(controller, 10, 0L))
        assertEquals(
            "列距 20 > 默认 6 格邻域 ⇒ 不误判成跳过，第二击照常是按钮命中用的单击",
            Action.Tap(30),
            tap(controller, 30, 100L),
        )
        assertEquals("邻域外那击重新起算候选：再来一次同列击才是双击", Action.Tap(45), tap(controller, 45, 200L))
        assertEquals(Action.Skip, tap(controller, 45, 250L))
    }

    @Test
    fun `邻域阈值与行高同源可注入`() {
        val strict = TouchGestureController(doubleTapColumnSlop = 0)
        assertEquals(Action.Tap(3), tap(strict, 3, 0L))
        assertEquals("阈值 0：换列的第二击不再配对", Action.Tap(4), tap(strict, 4, 100L))
        assertEquals("同列（且纵向不超一行）仍配对成跳过", Action.Skip, tap(strict, 4, 200L))

        val tall = TouchGestureController(pixelsPerLine = 40f)
        assertEquals(Action.Tap(3), tap(tall, 3, 0L))
        assertEquals(
            "行高 40px 时纵向 20px 仍在一行邻域内（阈值随 pixelsPerLine 走）",
            Action.Skip,
            tap(tall, 3, 100L, y = 120f),
        )
    }
}
