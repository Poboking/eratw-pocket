package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C-TUTORIAL-CUTOUT@1（2026-11-12 玩家反馈「教程在某些手机上，右侧会截断」）的 app 侧
 * 接线哨兵（与 [BootWindowLayoutPolicyTest] 同族：弹窗窗口挂在 Android 运行时上，app 的
 * JVM 单测面没有 Robolectric，实例化不了，所以用源码面钉装配形状 —— 代码形状骗不了 grep）。
 *
 * 缺陷链（根因层 = UI 壳，弹窗独立窗口的装配）：Compose `Dialog` 是独立窗口，
 * `layoutInDisplayCutoutMode` 此前全仓从未声明 ⇒ 平台缺省 MODE_DEFAULT 随 ROM 分叉；部分
 * 挖孔机型在沉浸式全屏下把窗口 letterbox 到挖孔安全区，而 Compose ui 1.7.x 的 Dialog 用
 * `Configuration.screenWidthDp` 量内容（上游 b/275732345，1.8.0-alpha08 才修；本仓
 * composeBom 2024.12.01 = ui 1.7.x），与被 letterbox 的窗口可视宽不一致 ⇒ `fillMaxSize`
 * 的面板按整屏宽测量、溢出可视区被裁 —— 横屏即「右侧截断」（上游 b/290502769 同症，
 * Samsung One UI 上尤甚）。
 *
 * 修法：窗口语义显式化 —— 共用装配件 [EratwDialogWindowWire]（ui/EratwDialog.kt）钉住
 * 「挖孔延伸 + 窗口边界 MATCH_PARENT + 跟随宿主藏栏」，教程弹窗与共用卡 `EratwDialogCard`
 * 全部经它装配；设置页两个宿主（教程重阅、补丁导入覆盖层）传 `immersive = false`，
 * 游戏宿主保持沉浸缺省。本用例钉住这些装配点，防有人删装配或新弹窗绕开共用壳。
 */
class DialogWindowCutoutWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }

    /** 从 [startMarker] 起切 [length] 个字符：只看调用点实参，别让文件其余部分喂绿断言。 */
    private fun callSlice(src: String, startMarker: String, length: Int = 400): String {
        val start = src.indexOf(startMarker)
        assertTrue("源码里必须有调用点 $startMarker", start >= 0)
        return src.substring(start, (start + length).coerceAtMost(src.length))
    }

    @Test
    fun `教程弹窗窗口声明 edge-to-edge 装配而不是放任 ROM 缺省`() {
        val src = sourceOf("TutorialDialog.kt")
        assertTrue(
            "弹窗窗口必须显式 decorFitsSystemWindows = false：insets 全量下发内容，" +
                "safeDrawingPadding 才拿得到挖孔 inset，窗口也才被允许延伸进挖孔区",
            src.contains("decorFitsSystemWindows = false"),
        )
        assertFalse(
            "decorFitsSystemWindows = true 会把 insets 拦在窗口外，挖孔避让失效，" +
                "正是要根治的 ROM 分叉形态",
            src.contains("decorFitsSystemWindows = true"),
        )
        assertTrue(
            "窗口几何装配必须走共用件 EratwDialogWindowWire（教程与共用卡同源，不许各写各的）",
            src.contains("EratwDialogWindowWire(immersive = immersive)"),
        )
        assertTrue(
            "KDoc 必须保留根因锚（上游 b/275732345）：下一个人改这段前必须看得见为什么装配层要管这事",
            src.contains("b/275732345"),
        )
        assertTrue(
            "KDoc 必须点名 layoutInDisplayCutoutMode：本缺陷的病灶就是这条从未声明、随 ROM 分叉",
            src.contains("layoutInDisplayCutoutMode"),
        )
    }

    @Test
    fun `共用装配件钉住挖孔延伸与窗口边界`() {
        val src = sourceOf("ui/EratwDialog.kt")
        val start = src.indexOf("internal fun EratwDialogWindowWire(")
        val end = src.indexOf("internal fun EratwDialogCard(")
        assertTrue("共用装配件 EratwDialogWindowWire 必须存在", start >= 0 && end > start)
        val wire = src.substring(start, end)
        assertTrue(
            "R+ 必须取 ALWAYS（长边挖孔也延伸，弹窗要占满整屏）",
            wire.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS") &&
                wire.contains("Build.VERSION_CODES.R"),
        )
        assertTrue(
            "P..R 必须取 SHORT_EDGES：字段本身是 P 才加的，与 SDK 判断必须同支（P 以下引用即 NoSuchFieldError）",
            wire.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES") &&
                wire.contains("Build.VERSION_CODES.P"),
        )
        assertFalse(
            "NEVER 是截断的另一种写法：窗口退避挖孔但 Compose 仍按整屏量内容，不许出现",
            wire.contains("LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER"),
        )
        assertTrue(
            "窗口边界必须钉满 MATCH_PARENT（宽与高都要）：窗口一旦小于屏幕，遮罩与面板一起溢出，" +
                "就是玩家看到的截断",
            wire.contains("width = WindowManager.LayoutParams.MATCH_PARENT") &&
                wire.contains("height = WindowManager.LayoutParams.MATCH_PARENT"),
        )
        assertTrue(
            "取自己的窗口必须走 DialogWindowProvider 官方通道（LocalView.parent）",
            wire.contains("as? DialogWindowProvider"),
        )
        assertTrue(
            "沉浸宿主必须藏系统栏（状态栏与导航栏一起）：窗口延伸进系统栏区之后，" +
                "栏若可见会浮在面板与页脚按钮之上",
            wire.contains("WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()"),
        )
    }

    @Test
    fun `共用卡弹窗走同一装配且 insets 下发`() {
        val src = sourceOf("ui/EratwDialog.kt")
        val card = callSlice(src, "internal fun EratwDialogCard(", 1600)
        assertTrue(
            "EratwDialogCard 必须同样 decorFitsSystemWindows = false（长按提示卡、脏退出报告卡、" +
                "补丁导入覆盖层与教程同症同修）",
            card.contains("decorFitsSystemWindows = false"),
        )
        assertTrue(
            "EratwDialogCard 必须走共用装配件，不许绕开",
            card.contains("EratwDialogWindowWire(immersive = immersive)"),
        )
    }

    @Test
    fun `设置页教程重阅不藏栏，游戏首启保持沉浸缺省`() {
        val settings = sourceOf("SettingsActivity.kt")
        val settingsCall = callSlice(settings, "TutorialDialog(")
        assertTrue(
            "设置页系统栏可见是页面语义的一部分：教程重阅入口必须传 immersive = false，" +
                "只做几何装配不藏栏",
            settingsCall.contains("immersive = false"),
        )
        val main = sourceOf("MainActivity.kt")
        val mainCall = callSlice(main, "TutorialDialog(")
        assertFalse(
            "游戏宿主（沉浸式全屏）必须保持 immersive 缺省 true：弹窗窗口要跟着藏系统栏，" +
                "否则栏浮在面板上",
            mainCall.contains("immersive"),
        )
    }

    @Test
    fun `设置页补丁导入覆盖层不藏栏`() {
        val src = sourceOf("ModImportUi.kt")
        val call = callSlice(src, "EratwDialogCard(")
        assertTrue(
            "补丁导入覆盖层宿主是设置页：必须传 immersive = false（导入中不藏玩家的系统栏）",
            call.contains("immersive = false"),
        )
    }
}
