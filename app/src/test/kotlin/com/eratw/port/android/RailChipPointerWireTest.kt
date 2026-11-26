package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右栏芯片鼠标现场的**宿主接线形状闸**（泡茶「点 + 弹窗乱跳」案的右栏半段）。
 *
 * ## 这条闸钉的是什么
 * 画布触摸链每一下都写鼠标现场（MainActivity 的 ACTION_DOWN/UP/CANCEL 三处
 * host.updatePointer(...)），右栏芯片没有触摸事件流——脚本侧弹窗锚在
 * MOUSEX()/MOUSEY() 读数上（QOL_USERCOM.ERB:260-261 的 X_POS=MOUSEX()+20），
 * 不写现场弹窗就落在上一次触摸的陈旧坐标 = 玩家报的「用右栏点 + 弹窗位置乱」。
 * 修复 = 按钮点击语义收敛在 GameHost.submitButton（高内聚低耦合：按钮点击 = 一次
 * 鼠标事件，属提交语义，壳层调用点不再各自记得补写）：提交前把指针指到屏上同值
 * 按钮中心——画布触摸链在 ACTION_DOWN 已写现场，此处重写幂等；右栏芯片等没有
 * 事件流的调用点由 submitButton 统一补写。GameHost 与 updatePointer 共用同一
 * 写入口径（setClientSize→setPointerPosition→windowCreated→指向态）；
 * CanvasConsoleView.buttonCenterClientForValue 负责 div 弹窗按钮优先、正文行按钮
 * 按命中行换算像素。三件缺一不可。
 *
 * ## 为什么用源码形状闸
 * MainActivity/GameHost 是 Android 类，app 模块无 Robolectric（同
 * MouseButtonWireGateTest / RailScanGateWireTest 的分工口径）；像素换算口径与
 * buttonAtClient/setPointer 同源，真机行为由 ComOptionPopupDeviceGeometryTest
 * 的几何复刻与出包实测兜底。
 *
 * ## 反向证明（本笔验收口径）
 * 撤掉 GameHost.submitButton 里的 updatePointerToOption 调用 ⇒ 本文件
 * 「按钮点击语义收敛在 GameHost」红；恢复复绿。撤掉 GameHost 里的
 * setPointerPosition 写入 ⇒ 「必须真的写指针坐标」红。
 */
class RailChipPointerWireTest {

    /** 与 MouseButtonWireGateTest 同款：两种工作目录都能读到大仓源码。 */
    private fun appSource(name: String): String {
        for (base in listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))) {
            val f = File(base, "com/eratw/port/android/" + name)
            if (f.isFile) return f.readText()
        }
        throw AssertionError("读不到 app 源文件 " + name + "（工作目录 " + File(".").absolutePath + "）")
    }

    /** 去掉注释行，避免 KDoc 里的举例串被当成接线证据。 */
    private fun codeOf(name: String): String = appSource(name).lines().filterNot { line ->
        val t = line.trim()
        t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
    }.joinToString("\n")

    @Test
    fun `按钮点击语义收敛在 GameHost 不许散落壳层`() {
        val main = codeOf("MainActivity.kt")
        assertFalse(
            "鼠标现场写入不许散落在壳层调用点（高内聚低耦合：按钮点击=一次鼠标事件，收敛在 GameHost.submitButton）",
            main.contains("updatePointerToOption"),
        )
        val host = codeOf("GameHost.kt")
        val body = host.substringAfter("fun submitButton").substringBefore("fun submitSkip")
        assertTrue(
            "submitButton 没有先写鼠标现场 ⇒ 右栏点 + 弹窗仍落陈旧 MOUSEX/MOUSEY（本案根因）",
            body.contains("updatePointerToOption(text)"),
        )
        assertTrue(
            "必须先写现场再提交（提交后 VM 立刻读 MOUSEX，晚了就是旧值）",
            body.indexOf("updatePointerToOption(text)") < body.indexOf("submitInternal"),
        )
    }

    @Test
    fun `GameHost 写现场与触摸链同口径`() {
        val host = codeOf("GameHost.kt")
        assertTrue(
            "GameHost 缺 updatePointerToOption 入口",
            host.contains("fun updatePointerToOption(value: String): Boolean"),
        )
        val body = host.substringAfter("fun updatePointerToOption").substringBefore("fun pendingRequest")
        assertTrue(
            "必须真的写指针坐标（setPointerPosition）",
            body.contains("mouse.setPointerPosition(center.first, center.second)"),
        )
        assertTrue(
            "必须置 windowCreated，否则 MOUSEX/MOUSEY 读数恒 0（MouseState.kt:158/167 口径）",
            body.contains("mouse.windowCreated = true"),
        )
        assertTrue(
            "屏上无同值按钮必须保持现场不动（返回 false 路径）",
            body.contains("?: return false"),
        )
    }

    @Test
    fun `视图反查口 div 弹窗按钮优先`() {
        // render-canvas 模块源码（与 RailScanGateWireTest 的 platform 探测同款双路径）。
        val rel = "com/eratw/port/render/canvas/CanvasConsoleView.kt"
        val text = sequenceOf(File("../render-canvas/src/main/kotlin"), File("render-canvas/src/main/kotlin"))
            .map { File(it, rel) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: throw AssertionError("读不到 CanvasConsoleView.kt")
        assertTrue(
            "CanvasConsoleView 缺 buttonCenterClientForValue 反查口",
            text.contains("fun buttonCenterClientForValue(value: String): Pair<Int, Int>?"),
        )
        val body = text.substringAfter("fun buttonCenterClientForValue")
        val divAt = body.indexOf("divTargets()")
        val allAt = body.indexOf("buttons.all()")
        assertTrue("缺 div 弹窗按钮支", divAt > 0)
        assertTrue("缺正文行按钮支", allAt > 0)
        assertTrue(
            "div 弹窗按钮必须优先于正文行按钮（弹窗盖在正文上层，命中优先级与 hitOnCurrentView 同判据）",
            divAt < allAt,
        )
    }
}
