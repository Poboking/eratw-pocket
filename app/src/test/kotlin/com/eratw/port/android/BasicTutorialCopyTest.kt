package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** SHELL-PROFILE 基础教程的形状闸与去 eraTW 专名证明（与全量 [TutorialCopyTest] 同强度）。 */
class BasicTutorialCopyTest {
    private val keys: Map<String, KProperty1<TutorialCopy, String>> = mapOf(
        "bookTitle" to TutorialCopy::bookTitle,
        "welcomeTitle" to TutorialCopy::welcomeTitle,
        "welcomeBody" to TutorialCopy::welcomeBody,
        "setupTitle" to TutorialCopy::setupTitle,
        "setupBody" to TutorialCopy::setupBody,
        "savesTitle" to TutorialCopy::savesTitle,
        "savesBody" to TutorialCopy::savesBody,
        "mediaTitle" to TutorialCopy::mediaTitle,
        "mediaBody" to TutorialCopy::mediaBody,
        "koujoTitle" to TutorialCopy::koujoTitle,
        "koujoBody" to TutorialCopy::koujoBody,
        "tipsTitle" to TutorialCopy::tipsTitle,
        "tipsBody" to TutorialCopy::tipsBody,
        "resourcesTitle" to TutorialCopy::resourcesTitle,
        "resourcesBody" to TutorialCopy::resourcesBody,
        "prevButton" to TutorialCopy::prevButton,
        "nextButton" to TutorialCopy::nextButton,
        "startButton" to TutorialCopy::startButton,
        "closeAction" to TutorialCopy::closeAction,
        "revisitTitle" to TutorialCopy::revisitTitle,
        "revisitHint" to TutorialCopy::revisitHint,
        "revisitButton" to TutorialCopy::revisitButton,
    )

    private val gameName = "eratohoReverse"
    private val copies = SettingsLanguage.entries.map { BasicTutorialCopy.of(it, gameName) }

    @Test
    fun `三档形状与全量教程同闸`() {
        CopyShapeGate.assertKeyCount("BasicTutorialCopy", copies, keys, 23)
        CopyShapeGate.assertNonEmptyAndDistinct(copies, keys)
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, keys)
        CopyShapeGate.assertNoSlotResidue(copies, keys) { _, template, _ -> template }
        CopyShapeGate.assertLanguageSelfEvident(copies, keys)
    }

    @Test
    fun `七页页序仍可用`() {
        copies.forEach { assertEquals(7, tutorialPages(it).size) }
    }

    @Test
    fun `游戏名从配置进来且不含 eraTW 语料专名`() {
        val banned = listOf("eraTW", "eratw吧", "东方 Project", "幻想乡", "口上")
        copies.forEach { copy ->
            assertTrue("游戏名没注入", keys.values.any { it(copy).contains(gameName) })
            keys.values.forEach { prop ->
                val value = prop(copy)
                banned.forEach { token ->
                    assertFalse("基础教程混入 eraTW 专名 $token：${prop.name}", value.contains(token))
                }
            }
        }
    }

    @Test
    fun `缓存同一档位不每帧造对象`() {
        assertSame(
            BasicTutorialCopy.of(SettingsLanguage.ZH, gameName),
            BasicTutorialCopy.of(SettingsLanguage.ZH, gameName),
        )
    }

    @Test
    fun `full 模式仍取原七页`() {
        val full = TutorialCopy.of(SettingsLanguage.ZH, gameName, basic = false)
        assertSame(TutorialCopy.of(SettingsLanguage.ZH), full)
        assertSame(
            BasicTutorialCopy.of(SettingsLanguage.ZH, gameName),
            TutorialCopy.of(SettingsLanguage.ZH, gameName, basic = true),
        )
    }
}
