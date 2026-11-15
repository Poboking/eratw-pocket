package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QoL 开关与右栏布局档的**闸接线哨兵**（CONTRACT-MODERN-T6 §2 闸矩阵 / T6-3 / T6-4 / RAIL-OPTIONS）。
 *
 * `MainActivity` / `GameHost` 挂在 Android 运行时上，app 的 JVM 单测面实例化不了，
 * 所以照 [EventWiringTest] 的源码面口径钉形状：删掉任一条读点/写点/传值，本类当场红。
 *
 * 各闸钉「消费点 + 写点」：
 *   * `screenFit`   —— 读点 `appliedScreenFit = UiPrefs.screenFit(this)`、消费点
 *     `columnGuardEnabled = appliedScreenFit`（下一次装配生效）；变更判据两个方向都给 `displaySettingChanged`；
 *   * `optionRail`  —— 初值 `shell.railVisible = UiPrefs.optionRailVisible(this)`、
 *     写点 `UiPrefs.setOptionRailVisible(this@MainActivity, shell.railVisible)`（即时 = 壳状态）；
 *   * `chips`       —— 右栏候选必须原样等于 `filtered.options`，显示端不许有第二道裁剪；
 *   * `railLayout`  —— 设置页写点 `UiPrefs.setRailLayout(context, mode)` → 回前台镜像
 *     `shell.railLayout = UiPrefs.railLayout(this)` → 消费点 `railLayoutOf(state.chips, state.railLayout, …)`：
 *     三段缺任何一段，玩家改档位就是「改了没反应」（2026-11-11 玩家问的就是这条接线）；
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
    // 闸 3：右栏候选 = 过滤结果，显示端不许再加第二道裁剪
    // ==================================================================

    @Test
    fun `右栏候选只吃 filterOptions 的结果`() {
        val src = sourceOf("MainActivity.kt")
        // 「更精简的游戏选项」那道三元式已随开关一起摘掉（判据整族收编 = 右栏过滤卡的
        // 「隐藏无编号」规则，两者同一条 `RailOptionRules.filterOptions`）。这里钉的是
        // 摘干净之后的形状：chips 必须原样等于过滤结果，别哪天又冒出第二道裁剪。
        assertTrue(
            "chips 必须直接等于 filtered.options",
            src.contains("val chips = filtered.options"),
        )
    }

    // ==================================================================
    // 闸 3.5：railLayout（右栏三档布局）—— 写点 → 镜像 → 消费点，三段缺一即「改了没反应」
    // ==================================================================

    /**
     * 玩家 2026-11-11 问「三档布局到底接进去没有」。这条闸把整条链一次钉死：
     * 设置页落盘、`MainActivity.onResume` 回前台镜像进壳状态、右栏渲染按壳状态折算摆法。
     * 任何一段被改回「读常量」「读缺省档」「走兼容垫片」，本用例当场红。
     */
    @Test
    fun `右栏布局三档的写点镜像点与消费点齐备`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页必须把选中的档位落盘（随改随存，不必重开会话）",
            settings.contains("UiPrefs.setRailLayout(context, mode)"),
        )
        val activity = sourceOf("MainActivity.kt")
        assertTrue(
            "回前台必须把盘上的档位镜像进壳状态，否则设置页改完右栏照旧",
            activity.contains("shell.railLayout = UiPrefs.railLayout(this)"),
        )
        assertTrue(
            "每行枚数同样要镜像（三档都吃它）",
            activity.contains("shell.railChipsPerRow = UiPrefs.railChipsPerRow(this)"),
        )
        val shell = sourceOf("ShellUi.kt")
        assertTrue(
            "右栏渲染必须按壳状态的档位与枚数折算行形状，不许走硬编码缺省的兼容垫片",
            shell.contains("railLayoutOf(state.chips, state.railLayout, state.railChipsPerRow)"),
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
