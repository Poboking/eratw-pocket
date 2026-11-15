package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「画面左右留白居中」档位的**玩家面接线哨兵**（2026-11-14 圆屏适配）。
 *
 * 闸本体（[UiPrefs] 读写层 + `CanvasConsoleView(contentMarginFraction=)` + 解算点
 * `ScreenLayout.resolve(contentMarginPx=)`）与玩家面出口分属三层，本类钉住
 * 「档位真的从设置页一路接到解算点」这一件事，删掉任一环当场红：
 *
 * 1. 设置页：状态读点 + 写点 + 真文案 + 档位表；
 * 2. 装配层：MainActivity 装配期单读 → 构造参数 → View；
 * 3. 重开判定：`displaySettingChanged` 含留白对（applied 与进页快照两个参照系）；
 * 4. 三语文案：6 条全部入表不内联。
 *
 * 不测行为（`SettingsActivity` / `MainActivity` 挂在 Android 运行时上，JVM 单测实例化不了），
 * 只钉源码形状；缺省值仍须是 **0 = 关**（默认值变动必须先改这里，属于有意红）。
 * 解算算式本身（守护看去留白宽、min 合成、余 1 归左）由 render 模块
 * `ScreenLayoutContentMarginTest` 钉，这里不重复。
 */
class ContentMarginWiringTest {

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
    fun `缺省仍是关`() {
        assertEquals(
            "留白缺省必须为关（关 = 满宽；默认值变动属于有意红）",
            0f,
            UiPrefs.DEFAULT_CONTENT_MARGIN_FRACTION,
        )
    }

    @Test
    fun `设置页档位卡接到读写层`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页必须在 UI 线程读一次档位现状",
            settings.contains(
                "var contentMargin by remember { " +
                    "mutableFloatStateOf(UiPrefs.contentMarginFraction(context)) }",
            ),
        )
        assertTrue(
            "档位必须把新值写回偏好表（下一次会话装配生效）",
            settings.contains("UiPrefs.setContentMarginFraction(context, fraction)"),
        )
        assertTrue(
            "档位卡必须用真文案（不许内联字面量）",
            settings.contains("SectionTitle(text.contentMarginLabel)") &&
                settings.contains("PrefCaption(text.contentMarginHint)"),
        )
        assertTrue(
            "档位必须喂给分段滑选的选项表（四档 = UiPrefs 白名单同一份）",
            settings.contains("options = UiPrefs.CONTENT_MARGIN_OPTIONS,"),
        )
        assertTrue(
            "档位卡必须接到状态与写点",
            settings.contains("onContentMargin = { fraction ->"),
        )
    }

    @Test
    fun `装配期单读并透传给视口构造`() {
        val main = sourceOf("MainActivity.kt")
        assertTrue(
            "装配期读数只在 MainActivity 取一次（与 appliedLiveReflow 同口径）",
            main.contains("appliedContentMargin = UiPrefs.contentMarginFraction(this)"),
        )
        assertTrue(
            "读数必须原样传给 CanvasConsoleView 构造参数",
            main.contains("contentMarginFraction = appliedContentMargin,"),
        )
    }

    @Test
    fun `重开判定含留白对且两个参照系都在`() {
        val main = sourceOf("MainActivity.kt")
        assertTrue(
            "displaySettingChanged 必须把留白对纳入判据",
            main.contains("nowContentMargin != appliedContentMargin"),
        )
        // applied 参照系（变了就得重开）与进页快照参照系（这次访问真的改了）都要传。
        assertEquals(
            "displayChanged 与 displayTouched 两个调用点都必须喂留白读数",
            2,
            Regex("""nowContentMargin = UiPrefs\.contentMarginFraction\(this\)""").findAll(main).count(),
        )
        assertTrue(
            "两个参照系的 applied 实参（applied* 与 settingsEntry*）都必须在",
            main.contains("appliedContentMargin = appliedContentMargin,") &&
                main.contains("appliedContentMargin = settingsEntryContentMargin,"),
        )
        assertTrue(
            "进设置页前必须留快照（否则每轮回返回都弹重开确认）",
            main.contains("settingsEntryContentMargin = UiPrefs.contentMarginFraction(this)"),
        )
    }

    @Test
    fun `三语文案同加且不内联`() {
        val strings = sourceOf("SettingsStrings.kt")
        for (key in listOf(
            "contentMarginLabel",
            "contentMarginHint",
            "contentMarginOff",
            "contentMarginSmall",
            "contentMarginMedium",
            "contentMarginLarge",
        )) {
            assertTrue("缺 $key", strings.contains("val $key: String = tr("))
        }
    }
}
