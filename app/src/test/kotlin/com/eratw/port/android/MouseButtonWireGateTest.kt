package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「画面按钮点击」的宿主接线形状闸（玩家敬茶/演奏「+」子选单案的 app 半段）。
 *
 * core 半段由 `QueueInputSinkMouseButtonTest` / `InputStatementsTest` 钉交付与回写；
 * 这里钉的是**宿主有没有把「这是画面按钮点击」这一维送进队列**：原版
 * `MinorShift.Emuera.Forms/MainWindow.cs:824-849` 在 `IsWaintingInputWithMouse` 下命中按钮
 * 时先写 `RESULTS:1`（:834）与 `RESULT:1 = 1`（:847），语料 `QOL_USERCOM.ERB @COM_OPTION`
 * 的 `MOUSE_BTN = RESULT:1` 全靠这一维。少接一条入口 = 那条入口上子选单仍然点不动。
 *
 * 为什么用源码形状闸：`GameHost` / `MainActivity` / `DevToolsHost` 的构造要 Android 运行时，
 * app 模块无 Robolectric（同 `PageTrailWireTest` / `DevToolsLockWatchCommandTest` 的分工口径），
 * 接线面只能读源码钉；行为面由 core-vm 的定向单测承担。
 */
class MouseButtonWireGateTest {

    /** 与 `DevToolsLockWatchCommandTest.appSource` 同款：两种工作目录都能读到大仓源码。 */
    private fun appSource(name: String): String {
        for (base in listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))) {
            val f = File(base, "com/eratw/port/android/$name")
            if (f.isFile) return f.readText()
        }
        throw AssertionError("读不到 app 源文件 $name（工作目录 ${File(".").absolutePath}）")
    }

    /** 去掉注释行，避免 KDoc 里的举例串被当成接线证据。 */
    private fun codeOf(name: String): String = appSource(name).lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }.joinToString("\n")

    /**
     * 真机命中链的入口：控制台 tap 命中 → `submitButtonValue` → `submitButton`；
     * 右侧栏选项按钮走 `submitInput(clickIntent = true)`，同样汇到 `submitButton`。
     */
    @Test
    fun `真机命中与右侧栏选项都汇到 submitButton`() {
        val main = codeOf("MainActivity.kt")
        assertTrue(
            "handleConsoleTap 的命中分支没交 submitButtonValue ⇒ 画面按钮点击丢了",
            main.contains("if (current) submitButtonValue(submitted)"),
        )
        assertTrue(
            "submitButtonValue 必须走 active.submitButton(value)",
            main.contains("active.submitButton(value)"),
        )
        assertTrue(
            "右侧栏选项按钮的 clickIntent 分支要汇到同一条 submitButton",
            main.contains("if (clickIntent) active.submitButton(text) else active.submit(text)"),
        )
    }

    /** DevTools `tap x,y` 自称「等价手指点屏幕上的按钮行」，必须与真机命中同口径。 */
    @Test
    fun `DevTools tap 命中走 submitButton 而 submit 命令仍是键盘语义`() {
        val devtools = codeOf("DevToolsHost.kt")
        assertTrue(
            "DevTools tap 命中改回 host.submit 会让调试通道在带鼠标 INPUT 下丢掉 RESULT:1，取证与真机对不上",
            devtools.contains("host.submitButton(text)"),
        )
        assertFalse(
            "tap 命中分支不许再出现 host.submit(text)",
            devtools.contains("host.submit(text)"),
        )
        assertTrue(
            "submit 命令是键盘回车语义，不许被一并改成按钮点击",
            devtools.contains("host.submit(arg)"),
        )
    }

    /**
     * `GameHost` 把「来源维」透传：Android 触摸只有左键 ⇒ 固定 1；`mesSkip` 一并下传，
     * 两条投喂口除来源标记外逐字相同（缺省 false 对应 `MainWindow.cs:848` 的 keySkip）。
     */
    @Test
    fun `GameHost 把左键位与 mesSkip 一起透传给按钮口`() {
        val host = codeOf("GameHost.kt")
        assertTrue(
            "submitButton 没带 mouseButton = 1 ⇒ RESULT:1 永远写不进去",
            host.contains("clickIntent = true, mouseButton = 1"),
        )
        assertTrue(
            "deliver 要把 mesSkip 一并交给按钮口，不能只交文本与按钮位",
            host.contains("sink.submitButtonClick(text, mouseButton, mesSkip)"),
        )
        assertTrue(
            "装载期抢跑补交也必须带着来源维，不能跨一道 pending 就丢",
            host.contains("mouseButton = queued.mouseButton"),
        )
    }
}
