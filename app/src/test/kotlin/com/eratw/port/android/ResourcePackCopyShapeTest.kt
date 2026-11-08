package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResourcePackCopy]（素材导入域三语表）的形状闸——照 `ModImportCopyShapeTest` 的模式、
 * 判据复用 [CopyShapeGate]：
 *
 * 1. **三档同一张键表**：ZH/EN/JA 是同一个 data class 的三个实例，键集合天然一致；这里
 *    钉**条目总数**（构造属性计数），谁加键不三档一起加就红。
 * 2. **每条非空、三档两两不同**：防「先占坑后补翻」的空串 / 直接复述中文的假翻译。
 * 3. **带读数的句子三档同形**：`{n}` 槽位序列与换行数逐条对齐——语序可以挪，读数个数与
 *    先后不许挪（`fill` 按下标灌数）。
 * 4. **灌满读数后不留槽位**：`fill` 单趟替换语义的自检（参数里的 `{n}` 不二次展开）。
 * 5. **语言自证**：EN 档不许混汉字；ZH 档每条有汉字（纯标点键按非 ASCII 判）；JA 档每条含
 *    假名或汉字（防漏翻成英文句子）。
 *
 * 结构体检五个键（含非素材文件折成的 [ResourcePackCopy.materialIgnoredFilesFormat]）在这里
 * 只看形状：`ResourcePackStore` 的 `StructureNote.kind` 到本表键的映射由调用侧
 * （素材导入链）负责，取证原话 / 计数只作为 `{0}` 实参。
 */
class ResourcePackCopyShapeTest {

    private val copies = listOf(ResourcePackCopy.ZH, ResourcePackCopy.EN, ResourcePackCopy.JA)

    /** 全部键的显式清单（改名必须跟着改这里——形状闸只认列出来的键）。 */
    private val keys: Map<String, KProperty1<ResourcePackCopy, String>> = mapOf(
        "backgroundTitle" to ResourcePackCopy::backgroundTitle,
        "backgroundIntro" to ResourcePackCopy::backgroundIntro,
        "backgroundPickFiles" to ResourcePackCopy::backgroundPickFiles,
        "backgroundPickFolder" to ResourcePackCopy::backgroundPickFolder,
        "bgmTitle" to ResourcePackCopy::bgmTitle,
        "bgmIntro" to ResourcePackCopy::bgmIntro,
        "bgmPickFiles" to ResourcePackCopy::bgmPickFiles,
        "bgmPickFolder" to ResourcePackCopy::bgmPickFolder,
        "bgmLocationLabel" to ResourcePackCopy::bgmLocationLabel,
        "bgmLocationHint" to ResourcePackCopy::bgmLocationHint,
        "importingStageText" to ResourcePackCopy::importingStageText,
        "importedSummaryFormat" to ResourcePackCopy::importedSummaryFormat,
        "importedPartialFormat" to ResourcePackCopy::importedPartialFormat,
        "importedNothingFormat" to ResourcePackCopy::importedNothingFormat,
        "failedNotZipFormat" to ResourcePackCopy::failedNotZipFormat,
        "failedReadFormat" to ResourcePackCopy::failedReadFormat,
        "failedUnzipFormat" to ResourcePackCopy::failedUnzipFormat,
        "failedNoUsableEntriesFormat" to ResourcePackCopy::failedNoUsableEntriesFormat,
        "unexpectedFormat" to ResourcePackCopy::unexpectedFormat,
        "skippedLineFormat" to ResourcePackCopy::skippedLineFormat,
        "noteLineFormat" to ResourcePackCopy::noteLineFormat,
        "restartButton" to ResourcePackCopy::restartButton,
        "nameListSeparator" to ResourcePackCopy::nameListSeparator,
        "unknownStorageError" to ResourcePackCopy::unknownStorageError,
        "structureBgmOutsideFormat" to ResourcePackCopy::structureBgmOutsideFormat,
        "structureImagesNoIndexFormat" to ResourcePackCopy::structureImagesNoIndexFormat,
        "structureEmptyCsvFormat" to ResourcePackCopy::structureEmptyCsvFormat,
        "structureUnrecognizedText" to ResourcePackCopy::structureUnrecognizedText,
        "autoIndexGeneratedFormat" to ResourcePackCopy::autoIndexGeneratedFormat,
        "autoRenamedFormat" to ResourcePackCopy::autoRenamedFormat,
        "bgmFallbackLocationFormat" to ResourcePackCopy::bgmFallbackLocationFormat,
        "bgmTracksOverflowFormat" to ResourcePackCopy::bgmTracksOverflowFormat,
        "materialNoUsableFilesFormat" to ResourcePackCopy::materialNoUsableFilesFormat,
        "backgroundIndexRowSkippedFormat" to ResourcePackCopy::backgroundIndexRowSkippedFormat,
        "materialIgnoredFilesFormat" to ResourcePackCopy::materialIgnoredFilesFormat,
        "materialImportFailedFormat" to ResourcePackCopy::materialImportFailedFormat,
        "materialSelectionLabel" to ResourcePackCopy::materialSelectionLabel,
        "hintLooksLikeKojoPack" to ResourcePackCopy::hintLooksLikeKojoPack,
        "backgroundEnabledHint" to ResourcePackCopy::backgroundEnabledHint,
        "bgmEnabledHint" to ResourcePackCopy::bgmEnabledHint,
        "enableStepsTitle" to ResourcePackCopy::enableStepsTitle,
    )

    /** 顿号这类正字法事实中日共用（不是漏翻），EN 档则用逗号加空格；「地点名」是汉字词。 */
    private val zhJaMayShare = setOf("nameListSeparator", "bgmLocationLabel")

    /** 这几条必须带盘上真实的中文目录名（EN 句子本身仍是英文），见 CopyShapeGate 的豁免说明。 */
    private val hanAllowed = setOf(
        "backgroundIntro",
        "bgmIntro",
        "bgmLocationHint",
        // 官方包索引名是游戏内的汉字键（地点_季节_时间），EN 句里照抄这个标识符
        "autoIndexGeneratedFormat",
    )

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        // data class 的 toString 逐个列构造属性：1 个 lang + 41 条键 = 42 个 '='。
        // 表里的值不许带 '=' 字符（有就换一种计数法，别硬绕）。
        assertEquals(41, keys.size)
        CopyShapeGate.assertKeyCount("ResourcePackCopy", copies, keys, expectedPerCopy = 42)
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
        CopyShapeGate.assertLanguageSelfEvident(copies, keys, zhJaMayShare, hanAllowed)
    }

    @Test
    fun `of 只按设置语言取单例且不串档`() {
        assertEquals(ResourcePackCopy.ZH, ResourcePackCopy.of(SettingsLanguage.ZH))
        assertEquals(ResourcePackCopy.EN, ResourcePackCopy.of(SettingsLanguage.EN))
        assertEquals(ResourcePackCopy.JA, ResourcePackCopy.of(SettingsLanguage.JA))
        // 三档的 lang 字段自证（防止复制粘贴三份时漏改）
        assertEquals(SettingsLanguage.ZH, ResourcePackCopy.ZH.lang)
        assertEquals(SettingsLanguage.EN, ResourcePackCopy.EN.lang)
        assertEquals(SettingsLanguage.JA, ResourcePackCopy.JA.lang)
    }

    @Test
    fun `直投卡三语各自独立且点名投放根与生效时机`() {
        val all = copies

        // 形状闸只钉「非空 + 三档不同」：漏翻或三语互抄照样能过，这里钉玩家照着做的事实。
        assertEquals("背景图卡标题三语重复", 3, all.map { it.backgroundTitle }.distinct().size)
        assertEquals("背景图卡说明三语重复", 3, all.map { it.backgroundIntro }.distinct().size)
        assertEquals("BGM 卡标题三语重复", 3, all.map { it.bgmTitle }.distinct().size)
        assertEquals("BGM 卡说明三语重复", 3, all.map { it.bgmIntro }.distinct().size)
        assertEquals("选文件按钮三语重复", 3, all.map { it.backgroundPickFiles }.distinct().size)
        assertEquals("BGM 选音频按钮三语重复", 3, all.map { it.bgmPickFiles }.distinct().size)
        // 「地点名」中日同文是汉字词事实（见 zhJaMayShare），只要求英文档另写。
        assertEquals("地点名标签 EN 应与中日不同", 2, all.map { it.bgmLocationLabel }.distinct().size)
        assertEquals("地点名说明三语重复", 3, all.map { it.bgmLocationHint }.distinct().size)

        all.forEach {
            // 投放根是三语共用的事实：换语言也照抄这两段路径。
            assertTrue(
                "背景图卡没提 resources/背景图/：${it.backgroundIntro}",
                it.backgroundIntro.contains("resources/背景图/"),
            )
            assertTrue(
                "BGM 卡没提 sound/游戏bgm/：${it.bgmIntro}",
                it.bgmIntro.contains("sound/游戏bgm/"),
            )
        }

        // 中文底稿要点名玩家照着做的事实：自动索引 / 重启生效 / 非 0-9 自动编号 /
        // 单音频才需要地点名。
        val zh = ResourcePackCopy.ZH
        assertTrue("没提自动索引", zh.backgroundIntro.contains("自动索引.csv"))
        assertTrue("没说图片重启后才生效", zh.backgroundIntro.contains("重启"))
        assertTrue("没说 BGM 自动编号", zh.bgmIntro.contains("自动编号"))
        assertTrue("没说地点名何时用", zh.bgmLocationHint.contains("直接选音频"))
        assertTrue("没说留空去哪", zh.bgmLocationHint.contains("自定义"))

        // 只选子文件夹会丢 BGI.csv、自动索引名字对不上游戏要的「地点_季节_时间」：
        // 这条必须在卡上就写清，玩家踩坑后才知道自己漏了什么。
        all.forEach {
            assertTrue(
                "背景图卡没提醒官方包要连 BGI.csv 一起导入：${it.backgroundIntro}",
                it.backgroundIntro.contains("BGI.csv"),
            )
        }
        assertTrue(
            "自动索引的提示没说官方包要整包重导：${zh.autoIndexGeneratedFormat}",
            zh.autoIndexGeneratedFormat.contains("BGI.csv"),
        )
        // 结果区顶出的启用步骤小标题三语独立（不是三份中文照抄）。
        assertEquals("启用步骤小标题三语重复", 3, all.map { it.enableStepsTitle }.distinct().size)
    }
}
