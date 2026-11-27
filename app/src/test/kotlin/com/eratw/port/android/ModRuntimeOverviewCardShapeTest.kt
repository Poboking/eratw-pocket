package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Mod 运行时概览」卡的两层信息哨兵（2026-11-27 默认收起改版）。
 *
 * 玩家面只留「谁在跑 / 谁没起来」，技术读数（耗时作用域 / 存储足迹 / 设置已改 / 注册面）必须
 * 收进折叠体、展开才渲染；失败点名反过来必须**不用展开就能读到**，否则玩家会把「没显示」读成
 * 「没失败」。断的是接线形状（JVM 单测实例化不了 Composable），渲染像素由真机负责。
 */
class ModRuntimeOverviewCardShapeTest {

    @Test
    fun `运行时概览默认收起且技术读数只在展开后渲染`() {
        val overview = sourceOf("ModRuntimeOverviewCard.kt")
        assertTrue(
            "收起态必须是默认（玩家不需要一屏技术读数）",
            overview.contains("mutableStateOf(false)"),
        )
        assertTrue(
            "标题行必须整行可点开（走共享揭示标题行）",
            overview.contains("ShellDisclosureHeader("),
        )
        assertTrue(
            "技术读数必须包在折叠容器里（展开才组合）",
            overview.contains("ShellVisibility(visible = expanded)"),
        )
        assertTrue(
            "失败点名要能不用展开就读到（折叠外预览不许删）",
            overview.contains("ModRuntimeFailurePreview("),
        )
        assertTrue(
            "技术明细不许再退回按内容包宽 / 横向滚动",
            !overview.contains("horizontalScroll"),
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
