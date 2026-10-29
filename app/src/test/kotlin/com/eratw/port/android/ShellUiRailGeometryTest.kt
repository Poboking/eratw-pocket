package com.eratw.port.android

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右侧选项栏的几何契约（纯 JVM 单测，口径承接被撤掉的 `ShellUiRailMetricsTest`）。
 *
 * 1. 一行**恰好三枚**定宽按钮放得下：栏宽 = 3×按钮宽 + 2×行距。横向滑动行与设置页预览
 *    复用 [railButtonWidth]，与 `weight(1f)` 的等分结果必须一致 —— 不一致就会出现
 *    「滑动行的按钮比并排行窄」这种只在选项多的页面才露出来的走形。
 * 2. 收起位移盖住栏的**实际占地**（栏宽 + 右留白，见 `EratwShell` 里 padding 在 width 外层）：
 *    2026-10-26 真机反馈过「收起后还能看到最左边一排」，那时位移写死 168dp 而栏宽 196dp。
 * 3. 序号角标让给标题的宽度随位数增长、并且封顶：存档页有 `10000` 这类五位序号，
 *    预留太短会压住标题尾字，太长会把标题吃光。
 * 4. 大小档（2026-10-29 加的设置项）：四档全部满足以上三条，且**单调变大**；
 *    缺省档必须等于加设置项之前那唯一一档，否则老玩家的界面会莫名变样。
 * 5. 机型自适应两刀（2026-10-29 收口）：窄画布夹栏宽、系统字体放大放开按钮高与角标让位 ——
 *    两刀都只动该动的字段，前三条契约在夹过 / 放过之后必须照样逐档成立。
 */
class ShellUiRailGeometryTest {
    private val tiers = UiPrefs.RailButtonSize.entries.toList()

    @Test
    fun threeButtonsFillRailWidthExactlyOnEveryTier() {
        for (size in tiers) {
            val metrics = railMetricsOf(size)
            val rowWidth = railButtonWidth(metrics.railWidth) * 3 + RAIL_ROW_SPACING * 2
            assertEquals(
                "$size 档：三枚定宽按钮 + 两处行距应当正好铺满栏宽",
                metrics.railWidth.value,
                rowWidth.value,
                0.01f,
            )
        }
    }

    @Test
    fun collapsedRailClearsWholeFootprintOnEveryTier() {
        for (size in tiers) {
            val metrics = railMetricsOf(size)
            assertTrue(
                "$size 档：收起位移要盖住栏宽 + 右留白，否则收起后还剩一列露在屏内",
                railCollapseOffset(metrics.railWidth) > metrics.railWidth + RAIL_END_PADDING,
            )
        }
    }

    @Test
    fun badgeReserveGrowsWithDigitsAndStaysCappedOnEveryTier() {
        for (size in tiers) {
            val metrics = railMetricsOf(size)
            assertTrue(railBadgeReserve(1, metrics) < railBadgeReserve(2, metrics))
            assertTrue(railBadgeReserve(2, metrics) < railBadgeReserve(3, metrics))
            // 封顶在一枚按钮宽的三分之一以内：再让就不是截断标题，而是把标题吃光。
            val cap = railButtonWidth(metrics.railWidth) * 0.32f
            assertEquals(cap.value, railBadgeReserve(9, metrics).value, 0.01f)
            assertEquals(railBadgeReserve(1, metrics), railBadgeReserve(0, metrics))
        }
    }

    @Test
    fun tiersGrowMonotonicallyInEveryDimension() {
        for (i in 1 until tiers.size) {
            val smaller = railMetricsOf(tiers[i - 1])
            val larger = railMetricsOf(tiers[i])
            assertTrue("${tiers[i]} 档栏宽应更大", larger.railWidth > smaller.railWidth)
            assertTrue("${tiers[i]} 档按钮应更高", larger.buttonHeight > smaller.buttonHeight)
            assertTrue("${tiers[i]} 档标题字号应更大", larger.labelTextSize.value > smaller.labelTextSize.value)
            assertTrue("${tiers[i]} 档角标字号应更大", larger.badgeTextSize.value > smaller.badgeTextSize.value)
        }
    }

    @Test
    fun defaultTierMatchesThePreSettingsRailGeometry() {
        // 2026-10-29 加设置项之前的唯一一档：栏宽 232dp（从 200dp 加宽而来，解决「三枚并排看不清」）、
        // 高 28dp、标题 10sp。缺省档若不是这三值，老玩家升级后看到的就是另一个界面。
        val metrics = railMetricsOf(UiPrefs.DEFAULT_RAIL_BUTTON_SIZE)
        assertEquals(232.dp, metrics.railWidth)
        assertEquals(28.dp, metrics.buttonHeight)
        assertEquals(10.sp, metrics.labelTextSize)
        // 三枚并排每枚约 75dp：200dp 栏时只有约 63dp，玩家反馈过标题看不清。
        assertTrue(railButtonWidth(metrics.railWidth).value > 70f)
        assertEquals(UiPrefs.RailButtonSize.NORMAL, UiPrefs.DEFAULT_RAIL_BUTTON_SIZE)
    }

    @Test
    fun storedNamesAreStableAcrossLabelRenames() {
        // 设置页落盘的是 `stored`：改中文档名不能把玩家已存的档位读丢。
        assertEquals(
            tiers.map { it.stored },
            listOf("small", "normal", "large", "xlarge"),
        )
        assertEquals(UiPrefs.RailButtonSize.NORMAL, UiPrefs.RailButtonSize.fromStored("normal"))
        assertEquals(UiPrefs.RailButtonSize.XLARGE, UiPrefs.RailButtonSize.fromStored("xlarge"))
        // 不认识的值（手改 XML、未来改名）一律回落缺省，不抛异常。
        assertEquals(UiPrefs.DEFAULT_RAIL_BUTTON_SIZE, UiPrefs.RailButtonSize.fromStored("huge"))
        assertEquals(UiPrefs.DEFAULT_RAIL_BUTTON_SIZE, UiPrefs.RailButtonSize.fromStored(null))
    }

    @Test
    fun widestTierStillLeavesGameCanvasVisible() {
        // 右栏是叠在画布之上的浮层（画布占满整宽）：栏再宽也不该号称「整屏都是按钮」，
        // 否则等于把游戏画面全盖住 —— 那种需求该用螺旋图标收起，而不是把栏放到最大。
        // 游戏锁横屏（AndroidManifest.xml:44），所以拿横过来那条边做基准。
        val widest = railMetricsOf(tiers.last())
        assertTrue(widest.railWidth <= 300.dp)
        assertTrue("典型横屏手机（约 800dp 宽）不该触到夹宽的刀",
            widest.railWidth < 800.dp * RAIL_MAX_WIDTH_FRACTION)
    }

    @Test
    fun everyTierIsUnchangedOnATypicalLandscapeCanvas() {
        // 自适应这一刀在正常机型上是**空操作**：不能让玩家选的大小莫名其妙缩水。
        val landscape = 800.dp
        for (size in tiers) {
            val metrics = railMetricsOf(size)
            assertEquals("$size 档在 800dp 画布上不该被夹", metrics, railMetricsFitting(metrics, landscape))
        }
    }

    @Test
    fun narrowCanvasCapsRailWidthAndEveryDerivedQuantityFollows() {
        // 窄画布（小窗 / 折叠屏外屏）：栏宽压到可用宽的六成以内，而按钮宽、收起位移、
        // 角标让位都是从**夹后的** railWidth 现推的 ⇒ 「三枚正好铺满一行」这条契约不许破。
        val narrow = 320.dp
        for (size in tiers) {
            val metrics = railMetricsFitting(railMetricsOf(size), narrow)
            assertTrue(
                "$size 档在 ${narrow.value}dp 画布上仍占 ${metrics.railWidth.value}dp",
                metrics.railWidth <= narrow * RAIL_MAX_WIDTH_FRACTION,
            )
            val rowWidth = railButtonWidth(metrics.railWidth) * 3 + RAIL_ROW_SPACING * 2
            assertEquals("$size 档夹完仍要三枚铺满", metrics.railWidth.value, rowWidth.value, 0.01f)
            assertTrue(railCollapseOffset(metrics.railWidth) > metrics.railWidth + RAIL_END_PADDING)
            assertTrue(railBadgeReserve(9, metrics) < railButtonWidth(metrics.railWidth))
        }
    }

    @Test
    fun capHasAFloorSoButtonsNeverBecomeUntappable() {
        // 画布极窄时不再跟着缩：压到那个份上按钮文字已经没法读，正确做法是收栏而不是压窄。
        val metrics = railMetricsFitting(railMetricsOf(UiPrefs.RailButtonSize.XLARGE), 40.dp)
        assertEquals(RAIL_MIN_FIT_WIDTH, metrics.railWidth)
    }

    @Test
    fun systemFontScaleNeverShrinksTheRail() {
        // 系统字体「调小」是一档真实存在的选择（平板 / 开发者选项），但按钮跟着变矮就点不中了 ⇒
        // fontScale ≤ 1 一律原样返回，一个字段都不动。
        for (size in tiers) {
            val metrics = railMetricsOf(size)
            assertEquals("$size 档：fontScale=1 是恒等", metrics, railMetricsForFontScale(metrics, 1f))
            assertEquals("$size 档：字体调小不跟着缩", metrics, railMetricsForFontScale(metrics, 0.7f))
        }
    }

    @Test
    fun bigSystemFontGrowsHeightAndBadgeRoomButNotWidth() {
        // 字号是 sp、尺寸是 dp：玩家把系统字体拉到 2×（vivo / MIUI 的滑杆顶格）时，
        // 字会长而按钮不会 ⇒ maxLines=1 的标题被上下裁掉、序号角标压到标题尾字。
        // 这一刀只放开「跟字号同源」的竖向尺寸；栏宽不许跟着长（那是玩家选的画面占比）。
        for (size in tiers) {
            val base = railMetricsOf(size)
            val scaled = railMetricsForFontScale(base, 2f)
            assertEquals("$size 档：栏宽不随系统字号变", base.railWidth, scaled.railWidth)
            assertEquals("$size 档：标题字号本来就直接吃 sp", base.labelTextSize, scaled.labelTextSize)
            assertTrue("$size 档：字体放大后按钮要长高", scaled.buttonHeight > base.buttonHeight)
            // 长到装得下行高：2× 的字号折成 dp 后那一行 + 上下余量，必须仍在按钮框里
            assertTrue(
                "$size 档：${scaled.buttonHeight.value}dp 装不下 ${base.labelTextSize.value}sp @2× 的行",
                scaled.buttonHeight.value > scaled.labelTextSize.value * 2f * 1.3f,
            )
            // 角标让位跟着数字实际变宽走（取三位：四位在小档上已经顶到封顶，比不出增量）。
            // 不给够的话「123」这种序号会直接压在标题尾字上。
            assertTrue(
                "$size 档：字体放大后角标让位要给得更多",
                railBadgeReserve(3, scaled) > railBadgeReserve(3, base),
            )
            // 但让位仍封顶在一枚按钮宽之内：栏宽没变，字再大也不能把标题吃光
            assertTrue(railBadgeReserve(9, scaled) < railButtonWidth(scaled.railWidth))
        }
    }

    @Test
    fun fontScaleIsMonotonicAndKeepsTheThreeUpContract() {
        // 缩放系数逐级放大：按钮只会越来越高，不会来回跳；而「三枚正好铺满一行」这条契约
        // 与宽度无关，夹过宽、放高之后都必须照旧成立。
        val base = railMetricsOf(UiPrefs.RailButtonSize.NORMAL)
        var previous = base.buttonHeight
        for (scale in floatArrayOf(1.1f, 1.3f, 1.6f, 2f, 2.4f)) {
            val metrics = railMetricsForFontScale(base, scale)
            assertTrue("fontScale=$scale 不许比上一档矮", metrics.buttonHeight >= previous)
            previous = metrics.buttonHeight
            val rowWidth = railButtonWidth(metrics.railWidth) * 3 + RAIL_ROW_SPACING * 2
            assertEquals("fontScale=$scale 三枚仍铺满栏宽", metrics.railWidth.value, rowWidth.value, 0.01f)
        }
    }
}
