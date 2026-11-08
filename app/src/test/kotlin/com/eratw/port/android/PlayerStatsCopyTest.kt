package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [PlayerStatsCopy] 的三语形状闸（照 UpdateNotifyCopyTest / CopyShapeGate 同一套判据）。
 *
 * 这张表独立存在的原因见它的类头：大表 [SettingsStrings] 再加构造属性会撞 JVM 方法签名
 * 255 个参数槽的上限，运行期直接 ClassFormatError。这里显式点名每一枚键，
 * 谁加文案不三档一起加就红 —— 与 AGENTS 铁律 6.2 的「改表必改闸」同一口径。
 */
class PlayerStatsCopyTest {

    /** 显式键清单：加一条文案就得在这里点名，并把 assertKeyCount 的期望数一起改。 */
    private val keys: Map<String, KProperty1<PlayerStatsCopy, String>> = mapOf(
        "title" to PlayerStatsCopy::title,
        "hint" to PlayerStatsCopy::hint,
    )

    private val copies = SettingsLanguage.entries.map { PlayerStatsCopy.of(it) }

    @Test
    fun `三档键数一致且与显式清单对账`() {
        // lang + title + hint = 3 枚构造属性；文案里不许出现等号（会污染计数）。
        CopyShapeGate.assertKeyCount("PlayerStatsCopy", copies, keys, 3)
        assertEquals("键清单漏点名", 2, keys.size)
    }

    @Test
    fun `三档每条非空且两两不同文`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, keys)
    }

    @Test
    fun `槽位与换行三档对齐且灌满后无残留`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, keys)
        // 这张表是纯静态文案，一条槽位都没有：fill 即恒等映射。
        CopyShapeGate.assertNoSlotResidue(copies, keys) { _, template, _ -> template }
    }

    @Test
    fun `EN 不混汉字 ZH 有底稿 JA 不是英文`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, keys)
    }

    @Test
    fun `按设置语言单路取到对应档位`() {
        assertEquals(SettingsLanguage.ZH, PlayerStatsCopy.of(SettingsLanguage.ZH).lang)
        assertEquals(SettingsLanguage.EN, PlayerStatsCopy.of(SettingsLanguage.EN).lang)
        assertEquals(SettingsLanguage.JA, PlayerStatsCopy.of(SettingsLanguage.JA).lang)
        assertEquals("匿名玩家数统计", copies[0].title)
        assertEquals("Anonymous player count", copies[1].title)
        assertEquals("匿名プレイヤー数", copies[2].title)
        assertSame("同一档位是同一个实例，不给 Compose 每帧造新对象",
            PlayerStatsCopy.of(SettingsLanguage.ZH), PlayerStatsCopy.of(SettingsLanguage.ZH))
    }
}
