package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入法慢弹回归锁（2026-11-11 真机反馈：数值输入时键盘「极其卡顿、缓慢」）。
 *
 * 病灶不在输入框本身，而在**键盘 inset 驱动的整屏重排**：
 * - 旧壳层根 Column 用 `safeDrawingPadding()`，而 `WindowInsets.safeDrawing` 包含 IME；
 * - 键盘每帧动画都会改变 Column 可用高 ⇒ `CanvasConsoleView.onSizeChanged` →
 *   `resizeFeedScreen()` + `syncFeedLayout()`；
 * - `ConsoleFeed.updateLayout()` 发现 `bandMaxHeightPx` 变了就 `force()` 整屏重展平/重绘
 *   （一屏几十张内联图时是 UI 线程的毫米级到十毫秒级长杆），键盘动画被拖慢。
 *
 * 修法 = 游戏列排除 IME inset，输入条改底部覆盖层、只在自己的子树上吃
 * `safeDrawing.only(Bottom)`（IME 与导航栏取并集）。`ShellUi` 是 Android/Compose 运行时
 * 组件，app 的纯 JVM 单测无法实例化，因此照 `QolGateWireTest` 的源码面口径钉接线：
 * 任何一笔把根改回 `safeDrawingPadding()`、或把输入条塞回 Column 的改动都应让本类变红。
 */
class InputBarImeInsetTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `游戏列不得吃 IME inset`() {
        val src = sourceOf("ShellUi.kt")
        assertFalse(
            "根 Column 不能再使用 safeDrawingPadding()：safeDrawing 含 IME，键盘动画会逐帧重排整块 console",
            src.contains(".safeDrawingPadding()"),
        )
        assertTrue(
            "游戏列必须显式排除 IME，只保留状态栏/挖孔/导航栏 safe drawing",
            src.contains("WindowInsets.safeDrawing.exclude(WindowInsets.ime)"),
        )
    }

    @Test
    fun `输入条是底部覆盖层且单独吃 IME inset`() {
        val src = sourceOf("ShellUi.kt")
        assertTrue(
            "输入条必须是 Box 底部覆盖层，不能回到游戏 Column 的测量流里",
            src.contains(".align(Alignment.BottomCenter)") &&
                src.contains("WindowInsets.safeDrawing.only(") &&
                src.contains("WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom"),
        )
    }
}
