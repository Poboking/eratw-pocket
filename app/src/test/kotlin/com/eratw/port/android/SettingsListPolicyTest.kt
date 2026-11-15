package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 长名单卡折叠口径的定向单测（2026-11-12）。
 *
 * 钉住的是本次 UI 修复的根因：右栏过滤 / 常用置顶 / 快捷宏的名单再长，收起态
 * 也只交给 Compose 前 N 条。历史候选上限 32 条、隐藏名单无上限，旧版逐条竖排
 * 会把一张卡拉出上千 dp；这里用纯函数保证折叠后的条数上界，不依赖真机截图。
 */
class SettingsListPolicyTest {

    @Test
    fun collapsed_hidesEverythingBeyondPreviewLimit() {
        val history = (1..32).map { "cmd-$it" }

        val visible = previewOf(history, expanded = false, limit = SettingsListPreview.HISTORY)

        assertEquals(SettingsListPreview.HISTORY, visible.size)
        assertEquals(history.take(SettingsListPreview.HISTORY), visible)
    }

    @Test
    fun expanded_keepsEveryItem() {
        val history = (1..32).map { "cmd-$it" }

        val visible = previewOf(history, expanded = true, limit = SettingsListPreview.HISTORY)

        assertEquals(32, visible.size)
        assertSame(history, visible)
    }

    @Test
    fun shortList_isNotPaddedOrTruncated() {
        val macros = listOf("a", "b")

        assertEquals(macros, previewOf(macros, expanded = false, limit = SettingsListPreview.MACRO))
    }

    @Test
    fun guardLimit_keepsAtLeastOneRow() {
        val hidden = listOf("立绘开关", "CONFIG")

        assertEquals(listOf("立绘开关"), previewOf(hidden, expanded = false, limit = 0))
    }

    @Test
    fun previewCaps_matchCardChoices() {
        assertEquals(6, SettingsListPreview.CHIP)
        assertEquals(4, SettingsListPreview.PINNED)
        assertEquals(6, SettingsListPreview.HISTORY)
        assertEquals(3, SettingsListPreview.MACRO)
    }
}
