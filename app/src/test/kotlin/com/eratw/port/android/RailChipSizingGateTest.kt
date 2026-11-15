package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 右栏按钮**容器尺寸**的源级闸（2026-11-11 复查补）。
 *
 * ## 为什么需要这条闸
 *
 * 右栏曾整栏按钮全灭：`RailChipWithMenu` 用 `Box { OptionChipButton(matchParentSize()) }`
 * 把菜单锚在按钮上，而 `matchParentSize()` 的子件**不参与父尺寸计算**，另一个子件
 * `DropdownMenu` 是 Popup（未展开时零布局节点）⇒ 父 Box 按 `constraints.minHeight` 定尺寸（0）
 * ⇒ 子件被 `Constraints.fixed(h=0)` 压扁，背景/文字被 clip、0 高节点也命中不到触摸。
 *
 * 纯函数闸（几何/文案）**抓不到**这类错误：它不在算数里，在 Compose 的测量契约里。
 * 真渲染闸要等壳层可复用的渲染口（`tools/uipreview` 目前只拷 `RailOptionRules` /
 * `QuickMacroUi`，拿不到私有 `RailChipWithMenu`），所以这里先钉住两条源级不变量：
 *
 * 1. 带菜单的按钮容器必须**自己给高度**（`modifier.height(metrics.buttonHeight)`）；
 * 2. 该容器内**不许**再出现 `matchParentSize()`（它是这次塌陷的成因）。
 *
 * 去掉闸的后果是可复现的：把 (1) 删掉、(2) 加回去，右栏按钮立刻全灭而所有纯函数用例全绿。
 */
class RailChipSizingGateTest {

    private fun shellUiSource(): String {
        val dir = File(System.getProperty("user.dir"))
        val candidates = listOf(
            File(dir, "app/src/main/kotlin/com/eratw/port/android/ShellUi.kt"),
            File(dir, "src/main/kotlin/com/eratw/port/android/ShellUi.kt"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 ShellUi.kt，检查测试工作目录：${dir.absolutePath}")
    }

    /** 截出 `RailChipWithMenu` 函数体（到下一个顶层 `@Composable`/`}` 行为止的粗切）。 */
    private fun railChipWithMenuBody(src: String): String {
        val start = src.indexOf("private fun RailChipWithMenu(")
        assertTrue("ShellUi.kt 里应有 RailChipWithMenu", start >= 0)
        val rest = src.substring(start)
        val end = rest.indexOf("\n/**", 10)
        return if (end > 0) rest.substring(0, end) else rest
    }

    @Test
    fun `带菜单的按钮容器必须自己给高度`() {
        val body = railChipWithMenuBody(shellUiSource())
        assertTrue(
            "RailChipWithMenu 的 Box 必须显式 .height(metrics.buttonHeight)：\n$body",
            body.contains("Box(modifier = modifier.height(metrics.buttonHeight))"),
        )
        assertTrue(
            "按钮子件应 fillMaxSize 铺满定高的父容器",
            body.contains("modifier = Modifier.fillMaxSize()"),
        )
    }

    @Test
    fun `带菜单的按钮容器内不许用 matchParentSize`() {
        // 只看代码行：函数里的 KDoc 会**解释**这个坑（正文里出现该标识符是正常的）。
        val codeLines = railChipWithMenuBody(shellUiSource())
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
        assertFalse(
            "matchParentSize 在「父 Box 只有它一个布局子件」时会把父尺寸塌成 0（2026-11-11 右栏全灭根因）",
            codeLines.contains("Modifier.matchParentSize()"),
        )
    }
}
