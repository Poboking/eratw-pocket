package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 四个 QoL 开关的**闸接线哨兵**（CONTRACT-MODERN-T6 §2 闸矩阵 / T6-3 / T6-4）。
 *
 * `MainActivity` / `GameHost` 挂在 Android 运行时上，app 的 JVM 单测面实例化不了，
 * 所以照 [EventWiringTest] 的源码面口径钉形状：删掉任一条读点/写点/传值，本类当场红。
 *
 * 四闸各钉「消费点 + 写点」：
 *   * `screenFit`   —— 读点 `appliedScreenFit = UiPrefs.screenFit(this)`、消费点
 *     `columnGuardEnabled = appliedScreenFit`（下一次装配生效）；变更判据两个方向都给 `displaySettingChanged`；
 *   * `optionRail`  —— 初值 `shell.railVisible = UiPrefs.optionRailVisible(this)`、
 *     写点 `UiPrefs.setOptionRailVisible(this@MainActivity, shell.railVisible)`（即时 = 壳状态）；
 *   * `leanOptions` —— 消费点 `chips` 的三元式（关 = `recognized` 原样，开才过过滤）；
 *   * `disableBadApple` —— **负向哨兵**：`GameHost`（VM 线程装载链）源码里不许再出现
 *     `UiPrefs.disableBadApple(`，读数必须在 UI 线程取好、经构造函数命名实参传入。
 *   * `echoInput` —— 同一缺陷类（VM 线程现读 `SharedPreferences`）的顺带收口：`GameHost`
 *     不许出现 `UiPrefs.echoInput(`，读数由 `MainActivity` 在 UI 线程取好传入。
 *
 * 线程纪律这一条是本波的真实缺陷修复（T6-3）：旧实现 `assembleSession` 在 VM 线程上直接
 * 读 `SharedPreferences`，违反 `liveReflow` / `failureCopy` 写死的「本类不许摸 UiPrefs」先例。
 */
class QolGateWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中（2026-11-08 实测）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ==================================================================
    // 闸 1：screenFit（列数守护）—— 记录期读数 → 构造期消费
    // ==================================================================

    @Test
    fun `列数守护的读点与消费点`() {
        val src = sourceOf("MainActivity.kt")
        assertTrue(
            "装配期必须把开关读进 appliedScreenFit（后面构造 CanvasConsoleView 与变更判据都吃它）",
            src.contains("appliedScreenFit = UiPrefs.screenFit(this)"),
        )
        assertTrue(
            "消费点必须是构造期参数 columnGuardEnabled（关 = 字号优先，地图可能折行）",
            src.contains("columnGuardEnabled = appliedScreenFit"),
        )
    }

    @Test
    fun `列数守护的变更判据两个方向都为真`() {
        // 既有用例（LiveReflowWiringTest）只钉了「开 → 关」；这里补反向，避免判据被写成
        // 单边比较（只认 now=true 那一侧）时玩家从关改回开却回到旧渲染。
        assertTrue(
            "关 → 开：玩家从设置页打开守护回来，必须走征求重开那一条路",
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = true, appliedScreenFit = false,
                nowLiveReflow = false, appliedLiveReflow = false,
            ),
        )
        assertTrue(
            "开 → 关：反向同样要问",
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = false, appliedScreenFit = true,
                nowLiveReflow = false, appliedLiveReflow = false,
            ),
        )
        assertFalse(
            "两向都不动时不得误报",
            MainActivity.displaySettingChanged(
                nowFontColumns = 136, appliedFontColumns = 136,
                nowScreenFit = false, appliedScreenFit = false,
                nowLiveReflow = false, appliedLiveReflow = false,
            ),
        )
    }

    // ==================================================================
    // 闸 2：optionRail（右侧选项栏可见）—— 初值 + 螺旋图标写点
    // ==================================================================

    @Test
    fun `选项栏的初值读点与写点`() {
        val src = sourceOf("MainActivity.kt")
        assertTrue(
            "装配期必须把上次退出时的可见状态读进壳（否则每次进游戏都被弹回展开）",
            src.contains("shell.railVisible = UiPrefs.optionRailVisible(this)"),
        )
        assertTrue(
            "螺旋图标切换必须随改随存（写点绑的是 shell.railVisible 的新值）",
            src.contains("UiPrefs.setOptionRailVisible(this@MainActivity, shell.railVisible)"),
        )
    }

    // ==================================================================
    // 闸 3：leanOptions（更精简的游戏选项）—— chips 三元式
    // ==================================================================

    @Test
    fun `精简选项的消费点关态原样`() {
        val src = sourceOf("MainActivity.kt")
        assertTrue(
            "chips 必须是「缺省关 = recognized 原样；开才过 LeanOptionFilter」的三元式",
            src.contains(
                "val chips = if (UiPrefs.leanOptions(this)) LeanOptionFilter.keepCommon(recognized) else recognized",
            ),
        )
    }

    // ==================================================================
    // 闸 4：disableBadApple（标题彩蛋屏蔽）—— UI 线程读、构造参数传
    // ==================================================================

    @Test
    fun `标题彩蛋开关在 UI 线程读好交给宿主`() {
        val src = sourceOf("MainActivity.kt")
        assertTrue(
            "GameHost 构造点必须显式传 disableBadApple（命名实参，值来自 UI 线程这一拍的 UiPrefs 读数）",
            src.contains("disableBadApple = UiPrefs.disableBadApple(this),"),
        )
    }

    @Test
    fun `宿主的消费点吃构造参数的两支`() {
        val src = sourceOf("GameHost.kt")
        assertTrue(
            "assembleSession 必须按构造参数分派（不再是 UiPrefs 现读）",
            src.contains("disabledFunctions = if (disableBadApple)"),
        )
        assertTrue("开 = 出厂口径屏蔽集", src.contains("EngineFunctionPolicy.DEFAULT_DISABLED"))
        assertTrue("关 = 全部放行", src.contains("emptySet()"))
    }

    @Test
    fun `VM 线程装载链不许直接读 SharedPreferences`() {
        val src = sourceOf("GameHost.kt")
        assertFalse(
            "VM 线程装载链不许直接读 SharedPreferences（照 liveReflow/failureCopy 先例）：" +
                "GameHost 里不许出现 UiPrefs.disableBadApple( —— 读数必须由 MainActivity 在 UI 线程取好、" +
                "经构造参数 disableBadApple 传入（SharedPreferences 首读可能落盘，压在装载链最敏感的一段）",
            src.contains("UiPrefs.disableBadApple("),
        )
    }

    // ==================================================================
    // 同类的第五个开关：echoInput（2026-11-06 落地的 ECHO-INPUT）
    // ==================================================================

    @Test
    fun `键入回显开关同样在 UI 线程读好交给宿主`() {
        val activity = sourceOf("MainActivity.kt")
        assertTrue(
            "GameHost 构造点必须显式传 echoInput（值来自 UI 线程这一拍的 UiPrefs 读数）",
            activity.contains("echoInput = UiPrefs.echoInput(this),"),
        )
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "消费点必须吃构造参数（装配期在 VM 线程上，不许现读）",
            host.contains("echoInputMode = if (echoInput)"),
        )
        assertFalse(
            "VM 线程装载链不许直接读 SharedPreferences：GameHost 里不许出现 UiPrefs.echoInput(",
            host.contains("UiPrefs.echoInput("),
        )
    }
}
