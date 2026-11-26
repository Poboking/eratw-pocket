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
        "assetPackSucceededDetailFormat" to ModImportCopy::assetPackSucceededDetailFormat,
        "packagedSucceededDetailFormat" to ModImportCopy::packagedSucceededDetailFormat,
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
        "zipTooManyEntriesFormat" to ModImportCopy::zipTooManyEntriesFormat,
        "zipEntryTooLargeFormat" to ModImportCopy::zipEntryTooLargeFormat,
        "zipTotalTooLargeFormat" to ModImportCopy::zipTotalTooLargeFormat,
        "zipBombSuspectFormat" to ModImportCopy::zipBombSuspectFormat,
        "zipDuplicateEntryFormat" to ModImportCopy::zipDuplicateEntryFormat,
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
        "codeArtifactStageFailedFormat" to ModImportCopy::codeArtifactStageFailedFormat,
        "codeArtifactCommitFailedFormat" to ModImportCopy::codeArtifactCommitFailedFormat,
        "modRuntimeOffHint" to ModImportCopy::modRuntimeOffHint,
        "modCodeGateHintFormat" to ModImportCopy::modCodeGateHintFormat,
        "modCodeGateReasonGateOff" to ModImportCopy::modCodeGateReasonGateOff,
        "modCodeGateReasonUntrusted" to ModImportCopy::modCodeGateReasonUntrusted,
        "modCodeGateReasonCorrupt" to ModImportCopy::modCodeGateReasonCorrupt,
        "modCodeGateReasonMultipleVersions" to ModImportCopy::modCodeGateReasonMultipleVersions,
        "overrideSummaryFormat" to ModImportCopy::overrideSummaryFormat,
        "reinstallSummaryFormat" to ModImportCopy::reinstallSummaryFormat,
        "reinstallNoVersionSummaryFormat" to ModImportCopy::reinstallNoVersionSummaryFormat,
        "versionChangedSummaryFormat" to ModImportCopy::versionChangedSummaryFormat,
        "packMetaPackageIdMismatchFormat" to ModImportCopy::packMetaPackageIdMismatchFormat,
        "engineApiGateWarningFormat" to ModImportCopy::engineApiGateWarningFormat,
        "engineTooOldWarningFormat" to ModImportCopy::engineTooOldWarningFormat,
        "resourceSummaryFormat" to ModImportCopy::resourceSummaryFormat,
        "resourceEntryInvalidNameFormat" to ModImportCopy::resourceEntryInvalidNameFormat,
        "resourceEntryDuplicateFormat" to ModImportCopy::resourceEntryDuplicateFormat,
        "resourceFileTooLargeFormat" to ModImportCopy::resourceFileTooLargeFormat,
        "resourceTotalTooLargeFormat" to ModImportCopy::resourceTotalTooLargeFormat,
        "resourcePrefixRebasedFormat" to ModImportCopy::resourcePrefixRebasedFormat,
        "resourcePrefixConflictFormat" to ModImportCopy::resourcePrefixConflictFormat,
        "resourceStageFailedFormat" to ModImportCopy::resourceStageFailedFormat,
        "resourceCommitFailedFormat" to ModImportCopy::resourceCommitFailedFormat,
        "mediaDirConflictFormat" to ModImportCopy::mediaDirConflictFormat,
        "mediaDirInvalidFormat" to ModImportCopy::mediaDirInvalidFormat,
        "mediaEntryInvalidNameFormat" to ModImportCopy::mediaEntryInvalidNameFormat,
        "mediaEntryDuplicateFormat" to ModImportCopy::mediaEntryDuplicateFormat,
        "mediaExtensionRejectedFormat" to ModImportCopy::mediaExtensionRejectedFormat,
        "mediaFileTooLargeFormat" to ModImportCopy::mediaFileTooLargeFormat,
        "mediaResourceTotalTooLargeFormat" to ModImportCopy::mediaResourceTotalTooLargeFormat,
        "mediaSoundTotalTooLargeFormat" to ModImportCopy::mediaSoundTotalTooLargeFormat,
        "mediaCombinedTooLargeFormat" to ModImportCopy::mediaCombinedTooLargeFormat,
        "mediaStageFailedFormat" to ModImportCopy::mediaStageFailedFormat,
        "mediaCommitFailedFormat" to ModImportCopy::mediaCommitFailedFormat,
        "previewTitle" to ModImportCopy::previewTitle,
        "previewCodeSourcesFormat" to ModImportCopy::previewCodeSourcesFormat,
        "previewCodeCompiledFormat" to ModImportCopy::previewCodeCompiledFormat,
        "previewAssetsFormat" to ModImportCopy::previewAssetsFormat,
        "previewMediaFormat" to ModImportCopy::previewMediaFormat,
        "previewMediaNoneLine" to ModImportCopy::previewMediaNoneLine,
        "previewExtensionsFormat" to ModImportCopy::previewExtensionsFormat,
        "previewExtensionCountFormat" to ModImportCopy::previewExtensionCountFormat,
        "previewConflictFormat" to ModImportCopy::previewConflictFormat,
        "previewLimitFormat" to ModImportCopy::previewLimitFormat,
        "previewLimitCombinedFormat" to ModImportCopy::previewLimitCombinedFormat,
        "previewUnavailableFormat" to ModImportCopy::previewUnavailableFormat,
        "previewPackageCountFormat" to ModImportCopy::previewPackageCountFormat,
        "previewPackageCountSingleLine" to ModImportCopy::previewPackageCountSingleLine,
        "previewPackageRowFormat" to ModImportCopy::previewPackageRowFormat,
        "previewPackageVersionUnknown" to ModImportCopy::previewPackageVersionUnknown,
        "previewPackageIdUnknown" to ModImportCopy::previewPackageIdUnknown,
        "previewBatchDirConflictFormat" to ModImportCopy::previewBatchDirConflictFormat,
        "batchResultSummaryFormat" to ModImportCopy::batchResultSummaryFormat,
        "batchResultRowSucceededFormat" to ModImportCopy::batchResultRowSucceededFormat,
        "batchResultRowFailedFormat" to ModImportCopy::batchResultRowFailedFormat,
        "batchCardSummaryFormat" to ModImportCopy::batchCardSummaryFormat,
        "batchResultOverflowSucceededFormat" to ModImportCopy::batchResultOverflowSucceededFormat,
        "batchResultOverflowFailedFormat" to ModImportCopy::batchResultOverflowFailedFormat,
        "batchResultCollapse" to ModImportCopy::batchResultCollapse,
        "sourceReadFailedFormat" to ModImportCopy::sourceReadFailedFormat,
        "allReadsFailedFormat" to ModImportCopy::allReadsFailedFormat,
        "unexpectedFormat" to ModImportCopy::unexpectedFormat,
        "unknownStorageError" to ModImportCopy::unknownStorageError,
        // KI-4 报错专页（2026-11-23）：免责导向 + 一键复制 + 报告拼装词。
        "failureGuidanceAuthorFormat" to ModImportCopy::failureGuidanceAuthorFormat,
        "failureGuidanceStorageFormat" to ModImportCopy::failureGuidanceStorageFormat,
        "failureGuidanceUnexpectedFormat" to ModImportCopy::failureGuidanceUnexpectedFormat,
        "copyErrorReportButton" to ModImportCopy::copyErrorReportButton,
        "copyWarningsButton" to ModImportCopy::copyWarningsButton,
        "errorReportTitle" to ModImportCopy::errorReportTitle,
        "errorReportKindFormat" to ModImportCopy::errorReportKindFormat,
        "errorReportSourcesFormat" to ModImportCopy::errorReportSourcesFormat,
        "errorReportTimeFormat" to ModImportCopy::errorReportTimeFormat,
        "copyDoneToast" to ModImportCopy::copyDoneToast,
        // KI-8 月更重复导入：预览替换行。
        "previewReplaceFormat" to ModImportCopy::previewReplaceFormat,
        "previewReplaceNoTimeFormat" to ModImportCopy::previewReplaceNoTimeFormat,
    )

    private val slot = Regex("[{](\\d+)[}]")
    private val han = Regex("[一-鿿]")

    /** 中文与日文共用同一写法是正字法事实、不是漏翻的键（顿号）。 */
    private val zhJaMayShare = setOf("nameListSeparator")

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        // data class 的 toString 逐个列构造属性：1 个 lang + 112 条键 = 113 个 '='。
        // 表里的值不许带 '=' 字符（有就换一种计数法，别硬绕）。
        // 断言本体走共享形状闸（T-3 抽取）；本表就是这份工具的口径来源。
        // 键数算式：112 = 60（基线：55 + 3 BU 版本读数 + 1 pack-mod + 1 CH）
        //           + 5（CM zip 防御闸：条目数 / 单条目体积 / 整包体积 / 压缩比 / 条目重名）
        //           + 1（DC 引擎 API 下限：engineApiGateWarningFormat）
        //           + 1（本 lane 引擎太新：engineTooOldWarningFormat）
        //           + 1（MODDX3-A3 纯资源包成功详情行 assetPackSucceededDetailFormat）
        //           + 1（ERAFIN-C3 成品包成功详情行 packagedSucceededDetailFormat）
        //           + 11（ADR-014 S4 旁挂媒体资源面：冲突 / 目录名非法 / 条目名非法 / 条目重名 /
        //                 扩展名白名单 / 单文件超限 / 图·文树超限 / 音频树超限 / 两树合计超限 /
        //                 临时落盘失败 / 正式落盘失败）
        //           + 12（导入预览面：小标题 / 代码面源文件 / 代码面已编译模块 / 资源面 /
        //                 媒体面 / 媒体面无声明 / 扩展名外壳 / 单扩展名计数 / 目录冲突预警 /
        //                 限额预警 / 两树合计预警 / 预览不可用）
        //           + 6（批导入预览面：包数 / 单包措辞 / 逐包摘要 / 版本未知 / 包名未知 / 同批目录共用）
        //           + 2（ADR-013 代码产物面：暂存校验失败 / 内容寻址目录落盘失败）
        //           + 4（task-178 批导入结果面：汇总计数 / 逐包成功行 / 逐包失败行 / 入口卡摘要）
        //           + 5（task-179/187 代码闸提示面：提示句 + 总闸关 / 未信任 / 身份损坏 /
        //                 多版本残留四个原因）
        //           + 3（task-183 批结果折叠面：成功折叠行 / 失败折叠行 / 收起开关）。
        // G3 静默洞补锁（班次 5）：既有两个计数互相独立——「表加键 + keys.size 与
        // expectedPerCopy 双双跟上、唯独显式清单漏点名」时，新键整键逃过下面四条形状闸
        // （2026-11-20 班次以探针键实测：漂移态五条全绿静默通过）。本断言把三件事锁成
        // 一个算式：显式清单 + lang == 全表构造属性 == toString 的 '=' 计数，漏点名先红。
        val expectedPerCopy = 125
        assertEquals(
            "键数算式：124 = 60（基线）+ 5（CM zip 闸）+ 1（DC 引擎 API）+ 1（引擎太新提示）+ " +
                "1（MODDX3 纯资源包）+ 1（ERAFIN-C3 成品包）+ 11（S4 媒体面）+ 12（导入预览面）+ " +
                "6（批导入预览面）+ 2（代码产物面：暂存校验 / 落盘）+ 4（批导入结果面：汇总 / 成功行 / " +
                "失败行 / 卡片摘要）+ 5（代码闸提示面：提示句 + 四个原因）+ " +
                "3（批结果折叠面：成功折叠行 / 失败折叠行 / 收起开关）+ " +
                "12（KI-4 报错专页 10：导向×3 / 复制按钮×2 / 报告词×4 / Toast + KI-8 预览替换 2）",
            124,
            keys.size,
        )
        assertEquals(
            "显式键清单必须覆盖全表：keys.size + 1（lang）== expectedPerCopy。" +
                "表加键而清单漏点名时，keys.size 字面量与 toString 计数各自都能对上、" +
                "新键整键逃过其余四条形状闸，只有这里能红",
            expectedPerCopy,
            keys.size + 1,
        )
        CopyShapeGate.assertKeyCount("ModImportCopy", copies, keys, expectedPerCopy = expectedPerCopy)
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
