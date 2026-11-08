package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModImportCopy]（mod 导入域三语表）的形状闸——照 `UpdateNotifyCopyTest` 的模式：
 *
 * 1. **三档同一张键表**：ZH/EN/JA 是同一个 data class 的三个实例，键集合天然一致；这里
 *    钉**条目总数**（构造属性计数），谁加键不三档一起加就红。
 * 2. **每条非空、三档两两不同**：防「先占坑后补翻」的空串 / 直接复述中文的假翻译。
 * 3. **带读数的句子三档同形**：`{n}` 槽位序列与换行数逐条对齐——EN/JA 语序可以挪，
 *    读数的个数与先后不许挪（`fill` 按下标灌数）。
 * 4. **灌满读数后不留槽位**：`fill` 的单趟替换语义的自检（参数里的 `{n}` 不二次展开，
 *    槽位漏灌才会残留）。
 * 5. **语言自证**：EN 档一个汉字不许混进来；ZH 档每条都有汉字（逐字搬运的中文底稿）；
 *    JA 档每条都含假名或汉字（防漏翻成英文句子）。
 *
 * 逐字性（ZH 档 == 收口前的硬编码）由既有定向用例对拍：ModImportUiStateTest 钉报错页
 * 四个标题与入口卡摘要，ModImportControllerTest 钉控制器失败消息的关键子串——本表不动
 * 那些用例，中文漂移它们先红。
 */
class ModImportCopyShapeTest {

    private val copies = listOf(ModImportCopy.ZH, ModImportCopy.EN, ModImportCopy.JA)

    /** 全部键的显式清单（改名必须跟着改这里——形状闸只认列出来的键）。 */
    private val keys: Map<String, KProperty1<ModImportCopy, String>> = mapOf(
        "cardTitle" to ModImportCopy::cardTitle,
        "cardOpenButton" to ModImportCopy::cardOpenButton,
        "closeButton" to ModImportCopy::closeButton,
        "importingStageText" to ModImportCopy::importingStageText,
        "importingFilesFormat" to ModImportCopy::importingFilesFormat,
        "cardSucceededFormat" to ModImportCopy::cardSucceededFormat,
        "cardFailedFormat" to ModImportCopy::cardFailedFormat,
        "failureTitleCompile" to ModImportCopy::failureTitleCompile,
        "failureTitleIdentity" to ModImportCopy::failureTitleIdentity,
        "failureTitleStorage" to ModImportCopy::failureTitleStorage,
        "failureTitleUnexpected" to ModImportCopy::failureTitleUnexpected,
        "succeededDetailFormat" to ModImportCopy::succeededDetailFormat,
        "warningLineFormat" to ModImportCopy::warningLineFormat,
        "savedSourcesFormat" to ModImportCopy::savedSourcesFormat,
        "nameListSeparator" to ModImportCopy::nameListSeparator,
        "restartButton" to ModImportCopy::restartButton,
        "reportTitle" to ModImportCopy::reportTitle,
        "reportNotRefreshed" to ModImportCopy::reportNotRefreshed,
        "reportReadFailedFormat" to ModImportCopy::reportReadFailedFormat,
        "reportRolledBackLine" to ModImportCopy::reportRolledBackLine,
        "reportNoneLoaded" to ModImportCopy::reportNoneLoaded,
        "reportLoadedFormat" to ModImportCopy::reportLoadedFormat,
        "reportLoadFailedFormat" to ModImportCopy::reportLoadFailedFormat,
        "reportLoadWarningFormat" to ModImportCopy::reportLoadWarningFormat,
        "reportNoStoredFiles" to ModImportCopy::reportNoStoredFiles,
        "reportStoredFilesFormat" to ModImportCopy::reportStoredFilesFormat,
        "noFilesPicked" to ModImportCopy::noFilesPicked,
        "zipUnpackFailedFormat" to ModImportCopy::zipUnpackFailedFormat,
        "zipNoEntriesFormat" to ModImportCopy::zipNoEntriesFormat,
        "zipLooksLikeAssetPackFormat" to ModImportCopy::zipLooksLikeAssetPackFormat,
        "dedupSkippedWarningFormat" to ModImportCopy::dedupSkippedWarningFormat,
        "saveSourceFailedFormat" to ModImportCopy::saveSourceFailedFormat,
        "readBaseFailedFormat" to ModImportCopy::readBaseFailedFormat,
        "readArchivedFailedFormat" to ModImportCopy::readArchivedFailedFormat,
        "noEntryFunctionFormat" to ModImportCopy::noEntryFunctionFormat,
        "compileFailedFallback" to ModImportCopy::compileFailedFallback,
        "compileUnexpectedFormat" to ModImportCopy::compileUnexpectedFormat,
        "artifactEncodeFailedFormat" to ModImportCopy::artifactEncodeFailedFormat,
        "identityFailedFallback" to ModImportCopy::identityFailedFallback,
        "artifactSaveFailedFormat" to ModImportCopy::artifactSaveFailedFormat,
        "verifyWrittenFailedFormat" to ModImportCopy::verifyWrittenFailedFormat,
        "sourceReadFailedFormat" to ModImportCopy::sourceReadFailedFormat,
        "allReadsFailedFormat" to ModImportCopy::allReadsFailedFormat,
        "unexpectedFormat" to ModImportCopy::unexpectedFormat,
        "unknownStorageError" to ModImportCopy::unknownStorageError,
    )

    private val slot = Regex("[{](\\d+)[}]")
    private val han = Regex("[一-鿿]")

    /** 中文与日文共用同一写法是正字法事实、不是漏翻的键（顿号）。 */
    private val zhJaMayShare = setOf("nameListSeparator")

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        // data class 的 toString 逐个列构造属性：1 个 lang + 45 条键 = 46 个 '='。
        // 表里的值不许带 '=' 字符（有就换一种计数法，别硬绕）。
        // 断言本体走共享形状闸（T-3 抽取）；本表就是这份工具的口径来源。
        assertEquals(45, keys.size)
        CopyShapeGate.assertKeyCount("ModImportCopy", copies, keys, expectedPerCopy = 46)
    }

    @Test
    fun `每条非空且三档两两不同`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, keys, zhJaMayShare)
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
    fun `语言自证（EN 无汉字、ZH 每条有汉字或标点、JA 每条有假名或汉字）`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, keys, zhJaMayShare)
    }
}
