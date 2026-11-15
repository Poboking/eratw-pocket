package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PluginPolicyCopy]（补丁策略域三语表）的形状闸——判据复用 [CopyShapeGate]，
 * 模式照 `ResourcePackCopyShapeTest`：
 *
 * 1. 三档同一张键表 + 构造属性计数（[assertKeyCount] 在 [CopyShapeGate] 内）；
 * 2. 每条非空、三档两两不同（防空串与直接复述中文的假翻译）；
 * 3. 槽位序列与换行数三档对齐（本表零槽位，闸按零槽位判）；
 * 4. 灌满读数后不留槽位（无模板，直接回原文）；
 * 5. 语言自证：EN 无汉字、ZH 每条有汉字、JA 含假名或汉字。
 *
 * 另钉 [PluginPolicyCopy.of] 只按设置语言取单例、三档 lang 自证——防止复制粘贴三份时
 * 漏改语言标记（这条与文案无关，但会把设置页整卡串成别档）。
 */
class PluginPolicyCopyShapeTest {

    private val copies = listOf(PluginPolicyCopy.ZH, PluginPolicyCopy.EN, PluginPolicyCopy.JA)

    /** 全部键的显式清单（改名必须跟着改这里——形状闸只认列出来的键）。 */
    private val keys: Map<String, KProperty1<PluginPolicyCopy, String>> = mapOf(
        "title" to PluginPolicyCopy::title,
        "intro" to PluginPolicyCopy::intro,
        "disabled" to PluginPolicyCopy::disabled,
        "aiChatName" to PluginPolicyCopy::aiChatName,
    )

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        // data class 的 toString 逐个列构造属性：1 个 lang + 4 条键 = 5 个 '='。
        // 表里的值不许带 '=' 字符（有就换一种计数法，别硬绕）。
        assertEquals(4, keys.size)
        CopyShapeGate.assertKeyCount("PluginPolicyCopy", copies, keys, expectedPerCopy = 5)
    }

    @Test
    fun `每条非空且三档两两不同`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, keys)
    }

    @Test
    fun `带读数的句子三档同形（槽位序列与换行数对齐）`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, keys)
    }

    @Test
    fun `灌满读数后不留任何槽位`() {
        CopyShapeGate.assertNoSlotResidue(copies, keys) { _, template, _ -> template }
    }

    @Test
    fun `语言自证（EN 无汉字、ZH 每条有汉字、JA 每条有假名或汉字）`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, keys)
    }

    @Test
    fun `of 只按设置语言取单例且不串档`() {
        assertEquals(PluginPolicyCopy.ZH, PluginPolicyCopy.of(SettingsLanguage.ZH))
        assertEquals(PluginPolicyCopy.EN, PluginPolicyCopy.of(SettingsLanguage.EN))
        assertEquals(PluginPolicyCopy.JA, PluginPolicyCopy.of(SettingsLanguage.JA))
        assertEquals(SettingsLanguage.ZH, PluginPolicyCopy.ZH.lang)
        assertEquals(SettingsLanguage.EN, PluginPolicyCopy.EN.lang)
        assertEquals(SettingsLanguage.JA, PluginPolicyCopy.JA.lang)
    }
}
