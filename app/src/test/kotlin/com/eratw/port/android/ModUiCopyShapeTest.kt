package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ModUiCopy` 三语形状闸（AGENTS §6：新增玩家可见文案三档同加、同步形状闸计数）。
 *
 * 复用 `CopyShapeGate` 五条判据（条目数 / 非空且三档不同 / 槽位与换行对齐 / 灌满无残留 /
 * 语言自证），本文件只留自己的显式键清单。
 */
class ModUiCopyShapeTest {

    private val copies = listOf(ModUiCopy.ZH, ModUiCopy.EN, ModUiCopy.JA)

    private val keys: Map<String, KProperty1<ModUiCopy, String>> = mapOf(
        "entryLabel" to ModUiCopy::entryLabel,
        "panelTitle" to ModUiCopy::panelTitle,
        "panelHint" to ModUiCopy::panelHint,
        "emptyLabel" to ModUiCopy::emptyLabel,
        "closeLabel" to ModUiCopy::closeLabel,
        "executedLabelFormat" to ModUiCopy::executedLabelFormat,
        "failedLabelFormat" to ModUiCopy::failedLabelFormat,
        "rejectedLabelFormat" to ModUiCopy::rejectedLabelFormat,
        "logViewerLabel" to ModUiCopy::logViewerLabel,
        "logViewerEmpty" to ModUiCopy::logViewerEmpty,
        "logViewerCopy" to ModUiCopy::logViewerCopy,
        "logViewerCopied" to ModUiCopy::logViewerCopied,
    )

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        assertEquals("键清单要逐条点名，加键时同步这里", 12, keys.size)
        CopyShapeGate.assertKeyCount("ModUiCopy", copies, keys, expectedPerCopy = 13)
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
        CopyShapeGate.assertNoSlotResidue(copies, keys) { copy, template, n ->
            copy.fill(template, *Array(n) { "S$it" })
        }
    }

    @Test
    fun `语言自证（EN 无汉字、ZH 每条有汉字、JA 每条有假名或汉字）`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, keys)
    }
}
