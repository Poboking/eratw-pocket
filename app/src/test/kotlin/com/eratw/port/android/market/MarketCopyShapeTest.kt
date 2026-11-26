package com.eratw.port.android.market

import com.eratw.port.android.CopyShapeGate
import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [MarketCopy]（市场域三语表）的形状闸——照 `ModImportCopyShapeTest` 的模式（共享断言走
 * [CopyShapeGate]）：
 *
 * 1. **三档同一张键表**：ZH/EN/JA 是同一个 data class 的三个实例；这里钉**条目总数**
 *    （构造属性计数），谁加键不三档一起加就红。
 * 2. **每条非空、三档两两不同**：防「先占坑后补翻」的空串 / 直接复述中文的假翻译。
 * 3. **带读数的句子三档同形**：`{n}` 槽位序列与换行数逐条对齐。
 * 4. **灌满读数后不留槽位**：[MarketCopy.fill] 的单趟替换语义自检。
 * 5. **语言自证**：EN 档一个汉字不许混进来；ZH 档每条都有汉字（中文底稿）；
 *    JA 档每条都含假名或汉字（防漏翻成英文句子）。
 *
 * 另有一条市场域专属对账：拒因人话键数 = [MarketRejectCodes.ALL] 的 18 码（穷尽映射的
 * 词面半边；判定半边在 MarketPhrases.rejectPhrase 的 when 全臂，由 MarketViewModelTest 钉）。
 */
class MarketCopyShapeTest {

    private val copies = listOf(MarketCopy.ZH, MarketCopy.EN, MarketCopy.JA)

    /** 全部键的显式清单（改名必须跟着改这里——形状闸只认列出来的键）。 */
    private val keys: Map<String, KProperty1<MarketCopy, String>> = mapOf(
        "cardTitle" to MarketCopy::cardTitle,
        "cardOpenButton" to MarketCopy::cardOpenButton,
        "subtitle" to MarketCopy::subtitle,
        "closeButton" to MarketCopy::closeButton,
        "pullButton" to MarketCopy::pullButton,
        "serviceMissing" to MarketCopy::serviceMissing,
        "kindKojo" to MarketCopy::kindKojo,
        "kindDict" to MarketCopy::kindDict,
        "kindTemplate" to MarketCopy::kindTemplate,
        "kindMod" to MarketCopy::kindMod,
        "filterAll" to MarketCopy::filterAll,
        "rowMetaFormat" to MarketCopy::rowMetaFormat,
        "detailTitle" to MarketCopy::detailTitle,
        "detailLicenseFormat" to MarketCopy::detailLicenseFormat,
        "detailSummaryFormat" to MarketCopy::detailSummaryFormat,
        "detailSourceFormat" to MarketCopy::detailSourceFormat,
        "detailFilesFormat" to MarketCopy::detailFilesFormat,
        "minAppVersionFormat" to MarketCopy::minAppVersionFormat,
        "actionInstall" to MarketCopy::actionInstall,
        "actionUpdate" to MarketCopy::actionUpdate,
        "updateUpdatableFormat" to MarketCopy::updateUpdatableFormat,
        "updateAheadFormat" to MarketCopy::updateAheadFormat,
        "updateLatest" to MarketCopy::updateLatest,
        "updateLatestDiverged" to MarketCopy::updateLatestDiverged,
        "updateFailedFallback" to MarketCopy::updateFailedFallback,
        "installBusyFormat" to MarketCopy::installBusyFormat,
        "updateBusyFormat" to MarketCopy::updateBusyFormat,
        "progressPercentFormat" to MarketCopy::progressPercentFormat,
        "progressBytesFormat" to MarketCopy::progressBytesFormat,
        "progressExtract" to MarketCopy::progressExtract,
        "progressCommit" to MarketCopy::progressCommit,
        "installDoneFormat" to MarketCopy::installDoneFormat,
        "updateDoneFormat" to MarketCopy::updateDoneFormat,
        "updateChainDoneFormat" to MarketCopy::updateChainDoneFormat,
        "rejectSchemaVersion" to MarketCopy::rejectSchemaVersion,
        "rejectKindInvalid" to MarketCopy::rejectKindInvalid,
        "rejectIdInvalid" to MarketCopy::rejectIdInvalid,
        "rejectVersionInvalid" to MarketCopy::rejectVersionInvalid,
        "rejectRedistributeFalse" to MarketCopy::rejectRedistributeFalse,
        "rejectPathTraversal" to MarketCopy::rejectPathTraversal,
        "rejectPathInvalid" to MarketCopy::rejectPathInvalid,
        "rejectTooManyEntries" to MarketCopy::rejectTooManyEntries,
        "rejectUnpackedTooBig" to MarketCopy::rejectUnpackedTooBig,
        "rejectZipTooBig" to MarketCopy::rejectZipTooBig,
        "rejectShaMismatch" to MarketCopy::rejectShaMismatch,
        "rejectManifestMismatch" to MarketCopy::rejectManifestMismatch,
        "rejectIdKindConflict" to MarketCopy::rejectIdKindConflict,
        "rejectAppVersionUnsupported" to MarketCopy::rejectAppVersionUnsupported,
        "rejectEndpointForbidden" to MarketCopy::rejectEndpointForbidden,
        "rejectDownloadFailed" to MarketCopy::rejectDownloadFailed,
        "rejectExtractFailed" to MarketCopy::rejectExtractFailed,
        "rejectStagingFailed" to MarketCopy::rejectStagingFailed,
        "rejectUnknownFormat" to MarketCopy::rejectUnknownFormat,
        "rejectNoteFormat" to MarketCopy::rejectNoteFormat,
        "uninstallConfirmTitleFormat" to MarketCopy::uninstallConfirmTitleFormat,
        "uninstallConfirmBody" to MarketCopy::uninstallConfirmBody,
        "uninstallConfirmOk" to MarketCopy::uninstallConfirmOk,
        "uninstallConfirmCancel" to MarketCopy::uninstallConfirmCancel,
        "uninstallRunningFormat" to MarketCopy::uninstallRunningFormat,
        "uninstalledFormat" to MarketCopy::uninstalledFormat,
        "uninstallNotFound" to MarketCopy::uninstallNotFound,
        "uninstallFailedFormat" to MarketCopy::uninstallFailedFormat,
        "uninstallSyncFailedFormat" to MarketCopy::uninstallSyncFailedFormat,
        "installedTitle" to MarketCopy::installedTitle,
        "installedCountFormat" to MarketCopy::installedCountFormat,
        "installedEmptyTitle" to MarketCopy::installedEmptyTitle,
        "installedEmptyHint" to MarketCopy::installedEmptyHint,
        "installedSourceContainer" to MarketCopy::installedSourceContainer,
        "installedSourceFolder" to MarketCopy::installedSourceFolder,
        "installedDigestFormat" to MarketCopy::installedDigestFormat,
        "indexLoading" to MarketCopy::indexLoading,
        "statusAbsent" to MarketCopy::statusAbsent,
        "statusInvalidFormat" to MarketCopy::statusInvalidFormat,
        "statusNoPacks" to MarketCopy::statusNoPacks,
        "pullOkFormat" to MarketCopy::pullOkFormat,
        "pullCachedNote" to MarketCopy::pullCachedNote,
        "pullInvalidNote" to MarketCopy::pullInvalidNote,
        "droppedNoticeFormat" to MarketCopy::droppedNoticeFormat,
        "coverIgnoredFormat" to MarketCopy::coverIgnoredFormat,
        "syncTimeFormat" to MarketCopy::syncTimeFormat,
        "noDataTime" to MarketCopy::noDataTime,
        "readFailedFormat" to MarketCopy::readFailedFormat,
        "endpointTitle" to MarketCopy::endpointTitle,
        "endpointIntro" to MarketCopy::endpointIntro,
        "endpointSave" to MarketCopy::endpointSave,
        "endpointReset" to MarketCopy::endpointReset,
        "endpointStatusDefault" to MarketCopy::endpointStatusDefault,
        "endpointStatusOverridden" to MarketCopy::endpointStatusOverridden,
        "endpointKept" to MarketCopy::endpointKept,
        "endpointResetDone" to MarketCopy::endpointResetDone,
        "endpointSetDone" to MarketCopy::endpointSetDone,
        "endpointInvalidFormat" to MarketCopy::endpointInvalidFormat,
        "groupHeaderFormat" to MarketCopy::groupHeaderFormat,
        "groupCountFormat" to MarketCopy::groupCountFormat,
        "bridgeInstallButton" to MarketCopy::bridgeInstallButton,
        "bridgeBusyFormat" to MarketCopy::bridgeBusyFormat,
        "bridgeOkFormat" to MarketCopy::bridgeOkFormat,
        "bridgeEmptyPack" to MarketCopy::bridgeEmptyPack,
        "bridgeNotFound" to MarketCopy::bridgeNotFound,
        "bridgeFailedFormat" to MarketCopy::bridgeFailedFormat,
        "bridgeLedgerFailed" to MarketCopy::bridgeLedgerFailed,
        "sweepButton" to MarketCopy::sweepButton,
        "sweepBusy" to MarketCopy::sweepBusy,
        "sweepDoneFormat" to MarketCopy::sweepDoneFormat,
        "sweepNothing" to MarketCopy::sweepNothing,
        "sweepFailedFormat" to MarketCopy::sweepFailedFormat,
        // CONTRACT-MKM-STATS-UI：+filterFavorites/downloadCountFormat/favoriteAdd/
        // favoriteAdded/cardFavoritesFormat/cardNotSynced/cardPacksLabel 七键。
        "filterFavorites" to MarketCopy::filterFavorites,
        "downloadCountFormat" to MarketCopy::downloadCountFormat,
        "favoriteAdd" to MarketCopy::favoriteAdd,
        "favoriteAdded" to MarketCopy::favoriteAdded,
        "cardFavoritesFormat" to MarketCopy::cardFavoritesFormat,
        "cardNotSynced" to MarketCopy::cardNotSynced,
        "cardPacksLabel" to MarketCopy::cardPacksLabel,
    )

    /** 中文与日文共用同一写法是正字法事实、不是漏翻的键（同形汉字词 / 全角括号槽模板）。 */
    private val zhJaMayShare = setOf("filterAll", "actionUpdate", "progressPercentFormat", "groupHeaderFormat")

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        // data class 的 toString 逐个列构造属性：1 个 lang + 113 条键 = 114 个 '='
        // （CONTRACT-MKM-W6 缝 1/2：+pullCachedNote、+updateLatestDiverged，updateAhead 改
        // updateAheadFormat 带版本槽；W5-GC 余项：+sweepButton/sweepBusy/sweepDoneFormat/
        // sweepNothing/sweepFailedFormat 五键；MKM-STATS-UI：+filterFavorites/
        // downloadCountFormat/favoriteAdd/favoriteAdded/cardFavoritesFormat/cardNotSynced/
        // cardPacksLabel 七键）。表里的值不许带 '=' 字符（有就换一种计数法，别硬绕）。
        assertEquals(113, keys.size)
        CopyShapeGate.assertKeyCount("MarketCopy", copies, keys, expectedPerCopy = 114)
    }

    @Test
    fun `拒因人话键与 MarketRejectCodes 的封闭集对账`() {
        val rejectKeys = keys.keys.filter {
            it.startsWith("reject") && it != "rejectUnknownFormat" && it != "rejectNoteFormat"
        }
        assertEquals("拒因词面键必须与骨架的 18 码封闭集一一对应", MarketRejectCodes.ALL.size, rejectKeys.size)
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
