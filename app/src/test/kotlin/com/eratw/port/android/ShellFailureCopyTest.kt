package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ShellFailureCopy]（失败屏 / 崩溃面 / 启动加载域三语表，T-2a 收口）的形状闸——
 * 断言本体全在共享工具 [CopyShapeGate]（T-3），这里只留本表的显式键清单与**逐字对拍**。
 *
 * 逐字对拍的三条右轴（ZH 档零漂移的锚）：
 * 1. `FAILURE_PREFIX` 文件级常量（HeapFailureCopyTest 在钉它）；
 * 2. `UiFault` 三函数的缺省档输出 == 收口前的硬编码（UiFaultTest 逐字钉那批串）；
 * 3. `heapPreflightStoppedText` / `heapOomText` 缺省档输出 == 收口前的硬编码
 *    （HeapFailureCopyTest 逐字钉那批串）。
 */
class ShellFailureCopyTest {

    private val copies = listOf(ShellFailureCopy.ZH, ShellFailureCopy.EN, ShellFailureCopy.JA)

    /** 全部键的显式清单（改名必须跟着改这里——形状闸只认列出来的键）。 */
    private val keys: Map<String, KProperty1<ShellFailureCopy, String>> = mapOf(
        "failureTitleStartup" to ShellFailureCopy::failureTitleStartup,
        "failureTitleInGame" to ShellFailureCopy::failureTitleInGame,
        "failurePrefix" to ShellFailureCopy::failurePrefix,
        "artifactRootLineFormat" to ShellFailureCopy::artifactRootLineFormat,
        "artifactCauseLine" to ShellFailureCopy::artifactCauseLine,
        "artifactAdbLine" to ShellFailureCopy::artifactAdbLine,
        "noteBackToTitle" to ShellFailureCopy::noteBackToTitle,
        "noteInGame" to ShellFailureCopy::noteInGame,
        "noteLoad" to ShellFailureCopy::noteLoad,
        "heapPreflightFormat" to ShellFailureCopy::heapPreflightFormat,
        "heapOomWhereFormat" to ShellFailureCopy::heapOomWhereFormat,
        "oomDetailPrefixFormat" to ShellFailureCopy::oomDetailPrefixFormat,
        "heapOomFormat" to ShellFailureCopy::heapOomFormat,
        "consoleNotLaidOut" to ShellFailureCopy::consoleNotLaidOut,
        "unpackFailedFormat" to ShellFailureCopy::unpackFailedFormat,
        "scriptAbortFormat" to ShellFailureCopy::scriptAbortFormat,
        "stateMachineHaltFormat" to ShellFailureCopy::stateMachineHaltFormat,
        "stateMachineUnknownBlocker" to ShellFailureCopy::stateMachineUnknownBlocker,
        "stateMachineNoEvidence" to ShellFailureCopy::stateMachineNoEvidence,
        "backToTitleFailedFormat" to ShellFailureCopy::backToTitleFailedFormat,
        "scriptLocationLineFormat" to ShellFailureCopy::scriptLocationLineFormat,
        "scriptLocationWrapFormat" to ShellFailureCopy::scriptLocationWrapFormat,
        "staleSessionHeapFormat" to ShellFailureCopy::staleSessionHeapFormat,
        "missingArtifactsFormat" to ShellFailureCopy::missingArtifactsFormat,
        "artifactDirsSeparator" to ShellFailureCopy::artifactDirsSeparator,
        "restartingLine" to ShellFailureCopy::restartingLine,
        "restartFailedFormat" to ShellFailureCopy::restartFailedFormat,
        "detailSeparator" to ShellFailureCopy::detailSeparator,
        "oldSessionWaiting" to ShellFailureCopy::oldSessionWaiting,
        "restartStuckFormat" to ShellFailureCopy::restartStuckFormat,
        "restartStuckLabel" to ShellFailureCopy::restartStuckLabel,
        "checkingApkLine" to ShellFailureCopy::checkingApkLine,
        "checkingDataLine" to ShellFailureCopy::checkingDataLine,
        "unpackingApkLine" to ShellFailureCopy::unpackingApkLine,
        "unpackingDataLine" to ShellFailureCopy::unpackingDataLine,
        "loadingGameLine" to ShellFailureCopy::loadingGameLine,
        "progressUnpackedFormat" to ShellFailureCopy::progressUnpackedFormat,
        "progressLandedFormat" to ShellFailureCopy::progressLandedFormat,
        "progressCounting" to ShellFailureCopy::progressCounting,
        "inventoryNone" to ShellFailureCopy::inventoryNone,
        "inventorySummaryFormat" to ShellFailureCopy::inventorySummaryFormat,
        "finalTitleLagFormat" to ShellFailureCopy::finalTitleLagFormat,
        "finalTitleCachedFormat" to ShellFailureCopy::finalTitleCachedFormat,
        "finalTitleUnpackedFormat" to ShellFailureCopy::finalTitleUnpackedFormat,
        "spaceShortFormat" to ShellFailureCopy::spaceShortFormat,
        "startupFaultFormat" to ShellFailureCopy::startupFaultFormat,
        "hintNotRunning" to ShellFailureCopy::hintNotRunning,
        "displaySizeDialogTitle" to ShellFailureCopy::displaySizeDialogTitle,
        "displaySizeDialogBody" to ShellFailureCopy::displaySizeDialogBody,
        "orientationDialogTitle" to ShellFailureCopy::orientationDialogTitle,
        "orientationPortrait" to ShellFailureCopy::orientationPortrait,
        "orientationLandscape" to ShellFailureCopy::orientationLandscape,
        "orientationDialogBodyFormat" to ShellFailureCopy::orientationDialogBodyFormat,
        "restoreDefaultsDialogTitle" to ShellFailureCopy::restoreDefaultsDialogTitle,
        "restoreDefaultsDialogBody" to ShellFailureCopy::restoreDefaultsDialogBody,
        "dialogRestartNow" to ShellFailureCopy::dialogRestartNow,
        "dialogApplyNextLaunch" to ShellFailureCopy::dialogApplyNextLaunch,
        "toastNoReportFile" to ShellFailureCopy::toastNoReportFile,
        "toastCopyFailed" to ShellFailureCopy::toastCopyFailed,
        "toastCopyOk" to ShellFailureCopy::toastCopyOk,
        "toastSharePanelFailed" to ShellFailureCopy::toastSharePanelFailed,
        "toastNoShareEntry" to ShellFailureCopy::toastNoShareEntry,
        "shareChooserTitle" to ShellFailureCopy::shareChooserTitle,
        "bootstrapTitleLine" to ShellFailureCopy::bootstrapTitleLine,
        "bootstrapNoMessage" to ShellFailureCopy::bootstrapNoMessage,
        "bootstrapReassure" to ShellFailureCopy::bootstrapReassure,
        "bootstrapInstruction" to ShellFailureCopy::bootstrapInstruction,
        "bootstrapRestartButton" to ShellFailureCopy::bootstrapRestartButton,
    )

    /** 中文与日文共用同一写法是正字法事实、不是漏翻的键（全角冒号分隔符、全角括号包装）。 */
    private val zhJaMayShare = setOf("detailSeparator", "scriptLocationWrapFormat")

    @Test
    fun `三档条目数相等且与显式键清单对账`() {
        assertEquals(68, keys.size)
        CopyShapeGate.assertKeyCount("ShellFailureCopy", copies, keys, expectedPerCopy = 69)
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

    @Test
    fun `of 按设置页语言三枚举各归各位`() {
        assertEquals(ShellFailureCopy.ZH, ShellFailureCopy.of(SettingsLanguage.ZH))
        assertEquals(ShellFailureCopy.EN, ShellFailureCopy.of(SettingsLanguage.EN))
        assertEquals(ShellFailureCopy.JA, ShellFailureCopy.of(SettingsLanguage.JA))
    }

    @Test
    fun `FAILURE_PREFIX 文件级常量与 ZH 档 failurePrefix 同文`() {
        assertEquals(FAILURE_PREFIX, ShellFailureCopy.ZH.failurePrefix)
    }

    @Test
    fun `UiFault 三函数缺省档与收口前的硬编码逐字一致`() {
        assertEquals("启动失败", failureScreenTitle(FailureScreenKind.LOAD))
        assertEquals("启动失败", failureScreenTitle(FailureScreenKind.ARTIFACT))
        assertEquals("游戏出错", failureScreenTitle(FailureScreenKind.IN_GAME))
        val dir = "/x/erbc.v1"
        assertEquals(
            listOf("产物根: /x/erbc.v1"),
            failureScreenArtifactLines(FailureScreenKind.LOAD, dir),
        )
        assertEquals(
            listOf(
                "产物根: /x/erbc.v1",
                "随包 APK 正常不该走到这里：这一句就是原因（磁盘满 / assets 坏 / program 与 meta 不配对）。",
                "开发调试通道才需要 adb：tools/apkpack/push_game_data.ps1 -Dev 会把数据直推到上面那个目录。",
            ),
            failureScreenArtifactLines(FailureScreenKind.ARTIFACT, dir),
        )
        assertEquals(emptyList<String>(), failureScreenArtifactLines(FailureScreenKind.IN_GAME, dir))
        assertEquals(
            "「回到标题」在原会话上复位后重进标题（不清存档、不重新装载）；「重试」关闭当前会话并重新装载。",
            failureScreenNote(FailureScreenKind.IN_GAME, canBackToTitle = true),
        )
        assertEquals(
            "重试会关闭当前会话并重新装载；存档在盘上，不会丢。",
            failureScreenNote(FailureScreenKind.IN_GAME, canBackToTitle = false),
        )
        assertEquals(
            "重试会关闭当前会话并重新装载；若反复失败请检查产物目录。",
            failureScreenNote(FailureScreenKind.LOAD, canBackToTitle = false),
        )
    }

    @Test
    fun `heap 两函数缺省档与收口前的硬编码逐字一致`() {
        assertEquals(
            "启动失败：内存不够，装不进这一版" +
                "（这台手机最多给游戏 512 MB，这一版要 600 MB，还差 88 MB；这次装载开始前已用 40 MB）。" +
                "下面的「少占内存再试」会装得更慢、占得少一些，但这一版的需求比上限还大，" +
                "所以也可能还是装不下；最稳的是换一台内存更大的手机。",
            heapPreflightStoppedText(maxMiB = 512, requiredMiB = 600, usedMiB = 40),
        )
        assertEquals(
            "启动失败：内存不够，装到一半就断了" +
                "（最高用到 430 MB / 这台手机最多给游戏 512 MB，停在「解码」这一步；" +
                "OOM 原话：OutOfMemoryError: pthread_create failed）。" +
                "换一台内存更大的手机，或者点下面的「少占内存再试」再试一次 —— 它装得更慢、占得更少。",
            heapOomText(
                peakMiB = 430,
                maxMiB = 512,
                stage = "解码",
                oomDetail = "；OOM 原话：OutOfMemoryError: pthread_create failed",
            ),
        )
        // stage 缺席 = 从句整段不占位（防御分支口径不变）。
        assertEquals(
            "启动失败：内存不够，装到一半就断了" +
                "（最高用到 430 MB / 这台手机最多给游戏 512 MB）。" +
                "换一台内存更大的手机，或者点下面的「少占内存再试」再试一次 —— 它装得更慢、占得更少。",
            heapOomText(peakMiB = 430, maxMiB = 512, stage = null, oomDetail = ""),
        )
    }

    @Test
    fun `fill 的槽位按位置灌数且参数花括号不二次展开`() {
        val copy = ShellFailureCopy.ZH
        assertEquals(
            "重启失败：RuntimeException：坏了 {0}。存档在盘上，不会丢失；可先重试，若仍然失败请结束应用后重开。",
            copy.fill(copy.restartFailedFormat, "RuntimeException", copy.detailSeparator + "坏了 {0}"),
        )
    }
}
