package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 设置页断点：竖屏单列、常见手机横屏两列 + 顶栏操作、平板/超宽屏继续加列。 */
class SettingsLayoutTest {

    @Test
    fun phonePortrait_usesSingleColumnAndBottomActions() {
        val layout = settingsLayoutFor(widthDp = 360f, heightDp = 800f)
        assertEquals(1, layout.columns)
        assertEquals(680, layout.contentMaxWidthDp)
        assertFalse(layout.actionsInTopBar)
    }

    @Test
    fun phoneLandscape_usesTwoColumnsAndTopActions() {
        val layout = settingsLayoutFor(widthDp = 800f, heightDp = 360f)
        assertEquals(2, layout.columns)
        assertEquals(1180, layout.contentMaxWidthDp)
        assertTrue(layout.actionsInTopBar)
    }

    @Test
    fun narrowLandscape_staysSingleColumnButMovesActionsToTop() {
        val layout = settingsLayoutFor(widthDp = 560f, heightDp = 320f)
        assertEquals(1, layout.columns)
        assertTrue(layout.actionsInTopBar)
    }

    @Test
    fun tabletPortrait_usesTwoColumnsAndKeepsBottomActions() {
        val layout = settingsLayoutFor(widthDp = 800f, heightDp = 1280f)
        assertEquals(2, layout.columns)
        assertFalse(layout.actionsInTopBar)
    }

    @Test
    fun wideTablet_usesThreeColumns() {
        val layout = settingsLayoutFor(widthDp = 1280f, heightDp = 800f)
        assertEquals(3, layout.columns)
        assertEquals(1500, layout.contentMaxWidthDp)
        assertFalse(layout.actionsInTopBar)
    }

    @Test
    fun shortWideWindow_movesActionsToTopWithoutChangingColumns() {
        val layout = settingsLayoutFor(widthDp = 1080f, heightDp = 420f)
        assertEquals(3, layout.columns)
        assertTrue(layout.actionsInTopBar)
    }
}
