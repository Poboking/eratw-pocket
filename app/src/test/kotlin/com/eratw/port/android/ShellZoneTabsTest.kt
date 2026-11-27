package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页分区标签条（移植版 / 原版引擎 / Mod / 关于）的形状哨兵。
 *
 * 从 `ShellDesignSystemTest` 拆出来：那一份已长到触发 detekt `LargeClass`，而标签条自己的
 * 口径（四档等宽铺满、单行居中省略、不许横向滚动）与「卡片 / 按钮 / 动效只有一份实现」
 * 是两件事，拆开各自读更清楚，也让两边都不再逼近类体量阈值。
 *
 * 断的是**接线形状**，不是渲染像素：JVM 单测实例化不了 Activity / Composable，只能读源码。
 */
class ShellZoneTabsTest {

    @Test
    fun `分区标签条是等宽铺满的分段控`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "标签条四档必须装进同一个描边容器",
            settings.contains(
                ".clip(ShellShape.Chip)\n                .border(1.dp, UiColors.Border, ShellShape.Chip)",
            ),
        )
        assertTrue(
            "选中档必须有描边（不只靠底色传意）",
            settings.contains(".border(1.dp, tabBorder, ShellShape.Chip)"),
        )
        assertTrue(
            "档位文字必须取字阶 token（不许再内联 fontSize 13.sp）",
            settings.contains("style = ShellType.Tab"),
        )
        assertTrue(
            "四档必须等宽铺满：weight(1f) 平分整行 + 撑到触控下限（不许再按内容包宽）",
            settings.contains(".weight(1f)\n                        .heightIn(min = ShellSpace.RowMinHeight)"),
        )
        assertTrue(
            "标签条不许再横向滚动（能横向滚 = 一屏铺不满）",
            !settings.contains(".horizontalScroll("),
        )
        assertTrue(
            "档名必须单行居中且超长省略（窄屏不许换行撑破四等分）",
            settings.contains("softWrap = false,") && settings.contains("textAlign = TextAlign.Center,"),
        )
    }

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
