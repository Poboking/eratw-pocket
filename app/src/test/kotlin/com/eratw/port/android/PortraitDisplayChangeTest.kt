package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏字号档的**方向选键判据 + 玩家面接线哨兵**（L29 PORTRAIT-SETTINGS）。
 *
 * ## 修的是什么
 *
 * `UiPrefs.font_columns_portrait`（50/62/75/90，缺省 62）从竖屏壳第一波起就存在，但设置页
 * 一直没有入口；同时 `onActivityResult` 的 `displayChanged` / `displayTouched` 与进页快照
 * 都固定读横屏键 `UiPrefs.fontColumns`。竖屏实例里于是：
 *   * 改**竖屏档** → 检测不到，返回游戏不提示重开（新 UI 会看起来「设置了无效」）；
 *   * 改**横屏档** → 竖屏实例误报「显示大小变了」。
 *
 * 修法是把「按当前方向取哪个键」抽成 companion 纯函数 [MainActivity.effectiveFontColumns]，
 * 装配期选档、返回判据、进页快照三处共用；本类先直测判据本体，再把每条取键路径钉成源码形状。
 *
 * ## 为什么判据挂在 `MainActivity` companion
 *
 * `MainActivity` 是 Android 壳，app 的 JVM 单测面实例化不了它；既有
 * `displaySettingChanged` / `orientationForLock` 同款口径 —— 判据本体必须是静态纯函数。
 *
 * ## 不测的
 *
 * 真机上的「改档 → 返回 → 弹重开 → 重开后列数变化」全链需要设备（TODO(verify)：
 * 见交付报告）。这里只钉纯判据与接线形状。
 */
class PortraitDisplayChangeTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点必须先归一，否则「明明在」也 0 命中。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ==================================================================
    // 判据本体：按方向选键
    // ==================================================================

    @Test
    fun `竖屏取竖屏键横屏取横屏键`() {
        // 竖屏实例里横屏键是 136、竖屏键是 75：只许取后者。
        assertEquals(
            "竖屏必须读 font_columns_portrait",
            75,
            MainActivity.effectiveFontColumns(
                portrait = true,
                landscapeColumns = 136,
                portraitColumns = 75,
            ),
        )
        // 横屏实例反向：竖屏键怎么改都不参与。
        assertEquals(
            "横屏必须读 fontColumns",
            136,
            MainActivity.effectiveFontColumns(
                portrait = false,
                landscapeColumns = 136,
                portraitColumns = 75,
            ),
        )
        // 缺省竖屏档 62 与横屏缺省 136 各取各的（两个缺省不互相串档）。
        assertEquals(
            62,
            MainActivity.effectiveFontColumns(
                portrait = true,
                landscapeColumns = UiPrefs.FONT_COLUMN_OPTIONS.first().columns,
                portraitColumns = UiPrefs.DEFAULT_FONT_COLUMNS_PORTRAIT,
            ),
        )
    }

    /**
     * 生产判据链：先按方向取键，再喂既有 `displaySettingChanged`（其余三项取「没动」）。
     *
     * 两个参照系（applied / 进页快照）在 [MainActivity.onActivityResult] 里走同一个函数、
     * 只是 `applied*` 实参不同；这里测的是「取键 + 比较」这条链本身。
     */
    private fun displayChanged(
        portrait: Boolean,
        nowLandscape: Int,
        nowPortrait: Int,
        appliedLandscape: Int,
        appliedPortrait: Int,
    ): Boolean = MainActivity.displaySettingChanged(
        nowFontColumns = MainActivity.effectiveFontColumns(
            portrait = portrait,
            landscapeColumns = nowLandscape,
            portraitColumns = nowPortrait,
        ),
        appliedFontColumns = MainActivity.effectiveFontColumns(
            portrait = portrait,
            landscapeColumns = appliedLandscape,
            portraitColumns = appliedPortrait,
        ),
        nowScreenFit = true,
        appliedScreenFit = true,
        nowLiveReflow = false,
        appliedLiveReflow = false,
        nowContentMargin = 0f,
        appliedContentMargin = 0f,
    )

    @Test
    fun `竖屏改竖屏档才提示重开`() {
        assertTrue(
            "竖屏实例改竖屏档（62 → 75）必须走重开提示",
            displayChanged(
                portrait = true,
                nowLandscape = 136,
                nowPortrait = 75,
                appliedLandscape = 136,
                appliedPortrait = 62,
            ),
        )
        assertFalse(
            "竖屏实例改横屏档（136 → 90）不许误报：当前会话根本没用横屏档",
            displayChanged(
                portrait = true,
                nowLandscape = 90,
                nowPortrait = 62,
                appliedLandscape = 136,
                appliedPortrait = 62,
            ),
        )
        assertFalse(
            "竖屏实例两档都没动时不许提示",
            displayChanged(
                portrait = true,
                nowLandscape = 136,
                nowPortrait = 62,
                appliedLandscape = 136,
                appliedPortrait = 62,
            ),
        )
    }

    @Test
    fun `横屏改横屏档才提示重开`() {
        assertTrue(
            "横屏实例改横屏档（136 → 90）必须走重开提示",
            displayChanged(
                portrait = false,
                nowLandscape = 90,
                nowPortrait = 62,
                appliedLandscape = 136,
                appliedPortrait = 62,
            ),
        )
        assertFalse(
            "横屏实例改竖屏档不许误报：竖屏档只在竖屏模式下生效",
            displayChanged(
                portrait = false,
                nowLandscape = 136,
                nowPortrait = 90,
                appliedLandscape = 136,
                appliedPortrait = 62,
            ),
        )
    }

    // ==================================================================
    // 源码形状：三处取键路径共用一条判据
    // ==================================================================

    @Test
    fun `返回判据与进页快照都按方向取键`() {
        val main = sourceOf("MainActivity.kt")
        val directionKey = Regex(
            """val nowFontColumns = effectiveFontColumns\(\s*""" +
                """portrait = portraitNow,\s*""" +
                """landscapeColumns = UiPrefs\.fontColumns\(this\),\s*""" +
                """portraitColumns = UiPrefs\.fontColumnsPortrait\(this\),\s*\)""",
        )
        assertTrue(
            "返回判据必须先按当前方向取键（竖屏读竖屏键、横屏读横屏键）",
            directionKey.containsMatchIn(main),
        )
        assertEquals(
            "displayChanged 与 displayTouched 必须共用同一个按方向取键的读数",
            2,
            Regex("""nowFontColumns = nowFontColumns,""").findAll(main).count(),
        )
        assertTrue(
            "进设置页快照也必须按方向取键（否则竖屏下改竖屏档不算 touched）",
            Regex(
                """settingsEntryFontColumns = effectiveFontColumns\(\s*""" +
                    """portrait = portraitNow,\s*""" +
                    """landscapeColumns = UiPrefs\.fontColumns\(this\),\s*""" +
                    """portraitColumns = UiPrefs\.fontColumnsPortrait\(this\),\s*\)""",
            ).containsMatchIn(main),
        )
        assertTrue(
            "装配期选档与回程比较必须同一判据（各写一份方向分支迟早分叉）",
            Regex(
                """appliedFontColumns = effectiveFontColumns\(\s*""" +
                    """portrait = portraitPick,\s*""" +
                    """landscapeColumns = UiPrefs\.fontColumns\(this\),\s*""" +
                    """portraitColumns = UiPrefs\.fontColumnsPortrait\(this\),\s*\)""",
            ).containsMatchIn(main),
        )
    }

    @Test
    fun `返回判据不再直接读横屏键`() {
        val main = sourceOf("MainActivity.kt")
        // 反证锚：旧实现（本波修掉的 bug）逐字形状，回来就红。
        assertFalse(
            "displayChanged / displayTouched 不许再直接读横屏键",
            main.contains("nowFontColumns = UiPrefs.fontColumns(this)"),
        )
        assertFalse(
            "进页快照不许再直接读横屏键",
            main.contains("settingsEntryFontColumns = UiPrefs.fontColumns(this)"),
        )
    }

    // ==================================================================
    // 设置页：四档 UI 接线 + 既有方向开关回归
    // ==================================================================

    @Test
    fun `设置页竖屏字号卡接到读写层`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页必须读一次竖屏档现状（当前值回显）",
            settings.contains(
                "var portraitFontColumns by remember { " +
                    "mutableIntStateOf(UiPrefs.fontColumnsPortrait(context)) }",
            ),
        )
        assertTrue(
            "选择即写入既有键 font_columns_portrait",
            settings.contains("UiPrefs.setFontColumnsPortrait(context, columns)"),
        )
        assertTrue(
            "档位卡必须用三语文案（不许内联玩家可见字面量）",
            settings.contains("SectionTitle(text.portraitFontTitle)") &&
                settings.contains("PrefCaption(text.portraitFontHint)"),
        )
        assertTrue(
            "四档选项必须取自 UiPrefs 档位表（唯一权威）",
            settings.contains("options = UiPrefs.FONT_COLUMN_OPTIONS_PORTRAIT.map { it.columns },"),
        )
        assertTrue(
            "档名必须走 SettingsStrings 三语映射",
            settings.contains("labelOf = text::portraitFontColumnLabel,"),
        )
        assertTrue(
            "卡片必须挂到 PORT 分区的列表项上",
            settings.contains("item(key = \"ui-portrait-font\")") &&
                settings.contains("PortraitFontCard("),
        )
    }

    @Test
    fun `既有竖屏模式开关原样保留`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "方向开关必须还在 ControlCard 里（本波只回归，不改语义）",
            settings.contains("SwitchRow(\n            title = text.portraitTitle,"),
        )
        assertTrue(
            "方向开关的三语文案与状态/写点不变",
            settings.contains("hint = text.portraitHint,") &&
                settings.contains("checked = portraitMode,") &&
                settings.contains("onCheckedChange = onPortraitMode,"),
        )
        assertTrue(
            "方向仍写 portraitMode 一个键（不复活 follow 三态）",
            settings.contains("UiPrefs.setPortraitMode(context, enabled)"),
        )
    }

    // ==================================================================
    // 零外溢：ControlCard 形参表不漂移，新卡 ≤ 3 形参
    // ==================================================================

    @Test
    fun `ControlCard 形参表零漂移`() {
        // detekt LongMethod 的 baseline ID 用完整签名文本：形参增删一律视为漂移。
        assertEquals(
            "ControlCard 形参表漂移了（baseline 按签名文本记 ID，本波不改它）",
            listOf(
                "text", "railButtonSize", "onRailButtonSize", "railLayout", "onRailLayout",
                "railChipsPerRow", "onRailChipsPerRow", "screenFit", "onScreenFit",
                "portraitMode", "onPortraitMode", "liveReflow", "onLiveReflow",
                "echoInput", "onEchoInput", "commandEndWait", "onCommandEndWait",
                "busyIndicator", "onBusyIndicator",
                "modRuntime", "onModRuntime", "knobAt", "onKnobAt",
            ),
            paramsOf(sourceOf("SettingsActivity.kt"), "ControlCard"),
        )
    }

    @Test
    fun `PortraitFontCard 形参不超过三个`() {
        val params = paramsOf(sourceOf("SettingsActivity.kt"), "PortraitFontCard")
        assertEquals("新卡形参列表（text / columns / onColumns）", listOf("text", "columns", "onColumns"), params)
        assertTrue("契约 §1.5 要求新卡形参 ≤ 3", params.size <= 3)
    }

    /** 读某个 private 函数的形参名（按行拆分；本仓 Composable 的排版是一行一个形参）。 */
    private fun paramsOf(source: String, name: String): List<String> {
        val match = Regex("""private fun $name\(([\s\S]*?)\)\s*\{""").find(source)
            ?: error("找不到 $name 的形参表")
        return match.groupValues[1]
            .lines()
            .map { it.trim().removeSuffix(",").trim() }
            .filter { it.contains(":") }
            .map { it.substringBefore(":").trim() }
    }
}
