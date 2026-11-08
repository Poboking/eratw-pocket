package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EP4 U3b「更新面三语化 · 通知与下载层」的形状闸与源码面守卫。
 *
 * 分工（不与 [UpdateProgressTextTest] 重复）：那边钉**中文原文**与判读（搬动前后的同一批断言，
 * 只是调用点显式带上 `UpdateCopy.ZH`）；这边钉四件 U3b 才有的事：
 *
 * 1. **三语表形状**：三档条目数相等、每条非空、[UpdateCopy.EN] 零汉字（[UpdateCopy.JA] 的日文汉字正常）、
 *    同一条不许两两写成同一句、换行数与槽位数一致（契约 §3.3）；
 * 2. **三语下的渲染形状**：同一输入的行数一样、读数一格不许多也不欠、算不出的那句就是没有那句、
 *    七类失败 + 两支无类别 = 每档九句（英文不许只给三类，契约 U3b 硬事 3）；
 * 3. **源码面防扩散**：通知与下载层不许再写玩家面中文，但要能分辨 logcat 诊断面、注释与
 *    「拿去匹配上游原话的锚点」（契约 §3.4：把诊断面也翻成三语就毁掉取证），并钉住豁免面的数量；
 * 4. **接线纪律**：取语言只有一条路（壳层入口），兼容口只留所有权外真的在调的那 8 个，
 *    通知里的标签改为渲染期按 `copy` 现取（不再吃成单那一刻的旧标签，硬事 2）。
 *
 * [UpdateDownloadService] 是 Android 壳层（`Looper` 一碰就炸），对它只做源码面判据，不假造渲染断言。
 */
class UpdateNotifyCopyTest {

    private val copies = listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA)
    private val han = Regex("[\\u4e00-\\u9fff]")
    private val slot = Regex("[{](\\d+)[}]")

    /** U3b 追加进表的 54 条（名字点出来，改名/漏档/只补一种语言都要在这里红）。 */
    private val u3bKeys: List<Pair<String, kotlin.reflect.KProperty1<UpdateCopy, String>>> = listOf(
        "progressNoTotalFormat" to UpdateCopy::progressNoTotalFormat,
        "progressLineFormat" to UpdateCopy::progressLineFormat,
        "speedClauseFormat" to UpdateCopy::speedClauseFormat,
        "etaClauseFormat" to UpdateCopy::etaClauseFormat,
        "durationSecondsFormat" to UpdateCopy::durationSecondsFormat,
        "durationMinutesFormat" to UpdateCopy::durationMinutesFormat,
        "durationMinutesSecondsFormat" to UpdateCopy::durationMinutesSecondsFormat,
        "durationHoursFormat" to UpdateCopy::durationHoursFormat,
        "durationHoursMinutesFormat" to UpdateCopy::durationHoursMinutesFormat,
        "stallRestartLine" to UpdateCopy::stallRestartLine,
        "stallSwitchLine" to UpdateCopy::stallSwitchLine,
        "stallWaitLine" to UpdateCopy::stallWaitLine,
        "stallNoByteLine" to UpdateCopy::stallNoByteLine,
        "startedNotifyLine" to UpdateCopy::startedNotifyLine,
        "startedNoNotifyLine" to UpdateCopy::startedNoNotifyLine,
        "apkDoneLine" to UpdateCopy::apkDoneLine,
        "apkDoneNoNotifyLine" to UpdateCopy::apkDoneNoNotifyLine,
        "assetsDoneLine" to UpdateCopy::assetsDoneLine,
        "cancelledLine" to UpdateCopy::cancelledLine,
        "cancelledNoNotifyLine" to UpdateCopy::cancelledNoNotifyLine,
        "failedUnknownLine" to UpdateCopy::failedUnknownLine,
        "failedLegacyFormat" to UpdateCopy::failedLegacyFormat,
        "causeUnknownPhrase" to UpdateCopy::causeUnknownPhrase,
        "retryInNotifyPhrase" to UpdateCopy::retryInNotifyPhrase,
        "failedBodyFormat" to UpdateCopy::failedBodyFormat,
        "failureToastFormat" to UpdateCopy::failureToastFormat,
        "retryEntryNotifyLine" to UpdateCopy::retryEntryNotifyLine,
        "retryEntryGameLine" to UpdateCopy::retryEntryGameLine,
        "settingsRetryLabelFormat" to UpdateCopy::settingsRetryLabelFormat,
        "actionUnknownLine" to UpdateCopy::actionUnknownLine,
        "actionAllSourcesLine" to UpdateCopy::actionAllSourcesLine,
        "actionSpaceLine" to UpdateCopy::actionSpaceLine,
        "actionNetworkLine" to UpdateCopy::actionNetworkLine,
        "actionHttpStatusLine" to UpdateCopy::actionHttpStatusLine,
        "actionMissingLine" to UpdateCopy::actionMissingLine,
        "actionPlainHttpLine" to UpdateCopy::actionPlainHttpLine,
        "actionDigestLine" to UpdateCopy::actionDigestLine,
        "actionOversizeLine" to UpdateCopy::actionOversizeLine,
        "actionLocalIoLine" to UpdateCopy::actionLocalIoLine,
        "progressNotifyTitle" to UpdateCopy::progressNotifyTitle,
        "cancelNotifyAction" to UpdateCopy::cancelNotifyAction,
        "channelProgressName" to UpdateCopy::channelProgressName,
        "channelResultName" to UpdateCopy::channelResultName,
        "apkReadyTitle" to UpdateCopy::apkReadyTitle,
        "assetsReadyTitle" to UpdateCopy::assetsReadyTitle,
        "assetsRegisterFailedTitle" to UpdateCopy::assetsRegisterFailedTitle,
        "assetsRegisterProblemLine" to UpdateCopy::assetsRegisterProblemLine,
        "cancelledNotifyTitle" to UpdateCopy::cancelledNotifyTitle,
        "failedNotifyTitle" to UpdateCopy::failedNotifyTitle,
        "notStartedNotifyTitle" to UpdateCopy::notStartedNotifyTitle,
        "downloadBusyToast" to UpdateCopy::downloadBusyToast,
        "noNotifyBarStartFailedFormat" to UpdateCopy::noNotifyBarStartFailedFormat,
        "apkMissingToast" to UpdateCopy::apkMissingToast,
        "installerOpenFailedFormat" to UpdateCopy::installerOpenFailedFormat,
    )

    // ------------------------------------------------------------------
    // 1. 三语表形状
    // ------------------------------------------------------------------

    @Test
    fun `三语表三档条目数相等（追加键要三档一起加）`() {
        // data class 的 toString 是「key=value」按声明序，等号数就是条目数：三档必须一样，
        // 并且总数按下面的历史行逐笔钉（lang + 屏上层 + 通知与下载层 + 各波追加，每波抬一次计数）。
        // 少补一档词在编译期就过不去，这一条钉的是**有人手工往某一档里多塞一句**。
        // 2026-11-04 验收复跑发现 119 过期：7db4d19f 按词源契约往三语表各加了 2 条
        // （dialogOfferAssetsTitle / offerAssetsOnlyExplainerLine），三档同加，形状没破，
        // 只是这里没跟。2026-11-05 两路各自往三语表追加、由组长收口合流：
        // U1 强制更新档 +1（offerForceUpdateLine）、U2 系统时限暂停 +2
        // （systemPausedNotifyTitle / systemPausedLine）⇒ 121 → 124；
        // U5 仅 Wi-Fi 与设置页进度 +3（wifiOnlyCellularTitle / wifiOnlyCellularBody /
        // downloadingLineFormat）⇒ 124 → 127。三档同加，形状没破；两边各自的 124 都是单路视角。
        // U7b（2026-11-05）把 PackageInstallerSession 的 17 条内联玩家文案收进表（installDoneTitle 一族，
        // 三档同加，中文档与内联原句逐字相同）⇒ 127 → 144。
        // U11（2026-11-05 数据通道档位读数，CONTRACT-UPD3-3LANE §2 U11）把档位读数的 8 条新键收进表
        // （techDataArtifactLineFormat + dataKind*/dataDisk*/dataItemsNone，三档同加，只进展开层）⇒ 144 → 152。
        // U13（2026-11-05 坏基线拒装记账）把挡板说明的 2 条新键收进表
        // （techRejectedFallbackLineFormat / techRejectedSkipLineFormat，三档同加，只进展开层）⇒ 152 → 154。
        // 对账走共享形状闸（T-3 抽取）；本测试的键清单是 U3b 子集（54 条），与总数的关系在这钉。
        assertEquals("U3b 追加的键数变了（现有 54 条）", 54, u3bKeys.size)
        CopyShapeGate.assertKeyCount("UpdateCopy", copies, u3bKeys.toMap(), expectedPerCopy = 154)
    }

    @Test
    fun `英文那一组一个汉字都不许混进来（日文的汉字不算违规）`() {
        val en = UpdateCopy.EN.toString()
        assertFalse(
            "英文档混进了汉字：${han.findAll(en).map { it.value }.toList()}",
            han.containsMatchIn(en),
        )
        // 反向自证：日文档**确实**有汉字，说明上一条不是「正则没匹配到任何东西」的空转。
        assertTrue("日文档没抓到任何汉字，说明那条闸在空转", han.containsMatchIn(UpdateCopy.JA.toString()))
    }

    @Test
    fun `U3b 的 54 条在三语各自非空并且两两不同`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, u3bKeys.toMap())
    }

    @Test
    fun `同一句式的换行数与槽位数在三语一致（不许英文少一行少一格读数）`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, u3bKeys.toMap())
    }

    // ------------------------------------------------------------------
    // 2. 三语下的渲染形状
    // ------------------------------------------------------------------

    @Test
    fun `通知里的标签渲染期按玩家语言现取（不再吃成单那一刻的旧标签）`() {
        copies.forEach { copy ->
            val apk = UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, 140L * MIB, 743L * MIB)
            val assets = UpdateProgressText.progressText(copy, UpdateDownloadKind.Assets, 12L * MIB, 743L * MIB)
            assertTrue("${copy.lang} 的整包正文开头不是本语言的名字：$apk", apk.startsWith(copy.apkLabel))
            assertTrue("${copy.lang} 的数据正文开头不是本语言的名字：$assets", assets.startsWith(copy.assetsLabel))
            assertEquals("${copy.lang} 的 labelFor 与整包名不符", copy.apkLabel, UpdateProgressText.labelFor(copy, UpdateDownloadKind.Apk))
            assertEquals("${copy.lang} 的 labelFor 与数据名不符", copy.assetsLabel, UpdateProgressText.labelFor(copy, UpdateDownloadKind.Assets))
            if (copy !== UpdateCopy.ZH) {
                assertFalse("${copy.lang} 的正文串进了中文标签：$apk", apk.contains("新版本"))
                assertFalse("${copy.lang} 的正文串进了中文标签：$assets", assets.contains("数据包"))
            }
        }
    }

    @Test
    fun `同一份进度快照在三语下行数一致并且读数一格不欠`() {
        val zhText = UpdateProgressText.progressText(UpdateCopy.ZH, UpdateDownloadKind.Apk, streamingSnapshot())
        val zhStalled = UpdateProgressText.progressText(UpdateCopy.ZH, UpdateDownloadKind.Apk, stalledSnapshot())
        val zhQuiet = UpdateProgressText.progressText(UpdateCopy.ZH, UpdateDownloadKind.Apk, quietSnapshot())
        copies.forEach { copy ->
            val text = UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, streamingSnapshot())
            val stalled = UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, stalledSnapshot())
            val quiet = UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, quietSnapshot())
            assertEquals("${copy.lang} 改了正常在下的行数", zhText.lines().size, text.lines().size)
            assertEquals("${copy.lang} 改了卡住时的行数", zhStalled.lines().size, stalled.lines().size)
            assertEquals("${copy.lang} 改了算不出速度时的行数", zhQuiet.lines().size, quiet.lines().size)
            assertTrue("${copy.lang} 的卡住状态行是空的：$stalled", stalled.lines().last().isNotBlank())
            listOf("18", "140 MiB", "743 MiB", "603 MiB", "4 MiB").forEach { reading ->
                assertTrue("${copy.lang} 丢了读数 $reading：$text", text.contains(reading))
            }
            assertTrue("${copy.lang} 丢了百分比号：$text", text.contains("%"))
            assertEquals("${copy.lang} 的速度句多一句或少一句", 1, occurrences(text, speedMarks.getValue(copy.lang)))
            assertEquals("${copy.lang} 的预报句多一句或少一句", 1, occurrences(text, etaMarks.getValue(copy.lang)))
            // 算不出来的那一句就是**没有那一句**，三语都不许换成另一句假话。
            assertFalse("${copy.lang} 在算不出时编出了速度：$quiet", quiet.contains(speedMarks.getValue(copy.lang)))
            assertFalse("${copy.lang} 在算不出时编出了预报：$quiet", quiet.contains(etaMarks.getValue(copy.lang)))
        }
    }

    @Test
    fun `时长五档在三语下都是同一棵树（不许出现 0 秒那种假精确）`() {
        val probes = listOf(1L, 44_500L, 140_750L, 10L * 60_000L + 500L, 3_900_000L)
        copies.forEach { copy ->
            val labels = probes.map { UpdateProgressText.durationLabel(copy, it) }
            assertEquals("${copy.lang} 的时长档位撞成同一句", probes.size, labels.distinct().size)
            assertTrue("${copy.lang} 的时长为空", labels.all { it.isNotBlank() })
            labels.forEach { assertTrue("${copy.lang} 的时长没有读数：$it", it.any { c -> c.isDigit() }) }
        }
        // 向上取整：1 毫秒那一档在三语里都不许写成 0。
        copies.forEach { copy ->
            assertFalse("${copy.lang} 报了 0 秒", UpdateProgressText.durationLabel(copy, 1L).contains("0 "))
        }
    }

    @Test
    fun `九支处置句在三语各自互不相同（英文不许只给三类）`() {
        val kinds = UpdateDownloadPlan.FailureKind.values().toList()
        copies.forEach { copy ->
            val actions = kinds.map { UpdateProgressText.failureAction(copy, it, "") } +
                UpdateProgressText.failureAction(copy, null, "一条也没连上") +
                UpdateProgressText.failureAction(copy, null, "")
            assertEquals("${copy.lang} 的处置句支数不对", kinds.size + 2, actions.size)
            assertTrue("${copy.lang} 有空的处置句", actions.all { it.isNotBlank() })
            assertEquals(
                "${copy.lang} 的处置句撞了：${actions.groupingBy { it }.eachCount().filterValues { v -> v > 1 }}",
                actions.size,
                actions.distinct().size,
            )
        }
        // 同一条决策对同一句话：按同一个 kind 在三语里取，句子必须两两不同。
        kinds.forEach { kind ->
            val zh = UpdateProgressText.failureAction(UpdateCopy.ZH, kind, "")
            val en = UpdateProgressText.failureAction(UpdateCopy.EN, kind, "")
            val ja = UpdateProgressText.failureAction(UpdateCopy.JA, kind, "")
            assertTrue("$kind 的处置句英中同形", zh != en)
            assertTrue("$kind 的处置句日中同形", zh != ja)
            assertTrue("$kind 的处置句英日同形", en != ja)
        }
        // 磁盘不足按原话再分一支：判据只看上游原话，与玩家语言无关，三语都得**换到另一句**。
        copies.forEach { copy ->
            val space = UpdateProgressText.failureAction(copy, UpdateDownloadPlan.FailureKind.LocalFileSystem, SPACEY)
            val io = UpdateProgressText.failureAction(copy, UpdateDownloadPlan.FailureKind.LocalFileSystem, "无法落盘：app.apk")
            assertTrue("${copy.lang} 的磁盘不足与读写失败写成同一句", space != io)
        }
    }

    @Test
    fun `失败正文在三种语言下都是两行（原因一行、你现在能做什么一行）`() {
        copies.forEach { copy ->
            listOf(true, false).forEach { visible ->
                UpdateDownloadPlan.FailureKind.values().forEach { kind ->
                    val text = UpdateProgressText.failedText(copy, "x", kind, visible)
                    assertEquals("${copy.lang} 的失败正文行数变了：$text", 2, text.lines().size)
                    assertTrue("${copy.lang} 的失败正文有空行：$text", text.lines().all { it.isNotBlank() })
                    assertTrue(
                        "${copy.lang} 的失败正文没带上本类的处置句：$text",
                        text.contains(UpdateProgressText.failureAction(copy, kind, "")),
                    )
                }
            }
        }
    }

    @Test
    fun `看不见通知栏时三语都不许承诺那条通知`() {
        val promises = mapOf(
            SettingsLanguage.ZH to "点这条通知",
            SettingsLanguage.EN to "this notification",
            SettingsLanguage.JA to "この通知",
        )
        copies.forEach { copy ->
            val promise = promises.getValue(copy.lang)
            val texts = listOf(
                UpdateProgressText.apkDoneText(copy, false),
                UpdateProgressText.cancelledText(copy, false),
                UpdateProgressText.failedText(copy, "网络晃了一下", UpdateDownloadPlan.FailureKind.Network, false),
                UpdateProgressText.failureToast(copy, UpdateDownloadPlan.FailureKind.HttpStatus),
                UpdateProgressText.retryEntry(copy, false),
            )
            texts.forEach { assertFalse("${copy.lang} 看不见通知时仍承诺了通知：$it", it.contains(promise)) }
            assertTrue("${copy.lang} 的看不见版本为空", texts.all { it.isNotBlank() })
            // 看得见那一支反过来必须给得出这个入口。
            assertTrue("${copy.lang} 看得见的入口丢了", UpdateProgressText.retryEntry(copy, true).contains(promise))
            assertTrue("${copy.lang} 看得见的失败正文丢了入口", UpdateProgressText.failedText(copy, "", null, true).contains(promise))
            assertFalse("${copy.lang} 的开跑话术在看不见时承诺了通知栏", UpdateProgressText.startedToast(copy, false).contains(promise))
        }
    }

    @Test
    fun `三语玩家面都不许出现术语`() {
        val banned = listOf(
            "镜像", "清单", "摘要", "sha", "SHA", "Range", "range", "versionCode", "HTTP", "http",
            "mirror", "manifest", "digest",
        )
        copies.forEach { copy ->
            playerSentencesOf(copy).forEach { sentence ->
                banned.forEach { term ->
                    assertFalse("${copy.lang} 的玩家面出现术语「$term」：$sentence", sentence.contains(term))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. 源码面：防扩散 / 取语言只有一条路 / 兼容口 / 渲染期标签
    // ------------------------------------------------------------------

    @Test
    fun `通知与下载层不许再写玩家面中文（logcat 与锚点单独点名放行）`() {
        // U7b（2026-11-05）把 PackageInstallerSession.kt 也拉进零容忍名单：它的 17 条内联中文
        // 已收进 UpdateCopy 三语表，此后这条通道再写玩家面字面量就是违例。
        // L5（2026-11-06）把 UpdateUi.kt 也拉进来：它是「文案表 → 玩家面」的唯一翻译层，
        // 之前漏在名单外，P1-2（开跑 Toast 恒中文）正是从这条缝里漏过去的。
        listOf("UpdateProgressText.kt", "UpdateDownloadService.kt", "PackageInstallerSession.kt", "UpdateUi.kt").forEach { name ->
            val offenders = playerFacingLiterals(name)
            assertTrue("$name 里玩家面句子又写了硬编码中文（违例：$offenders）", offenders.isEmpty())
        }
        // 反空转：豁免面必须真的存在且**只有**这些。谁把玩家句子挂到 Log 行上、或多开一处锚点，这里就红。
        // 10 条：9948a5da 的「先亮前台再抢闸」新增了「过闸通知起不来（照常退场）」这条 logcat 取证，
        // 加日志时这行没跟着 bump，main 上一直红；该句只进 logcat，不面向玩家，计数按实际校准。
        // 12 条：2026-11-04 单飞闸复查收口新增两条 logcat 取证 ——「下载服务处理意图异常」
        // （下载支路异常改在 beginDownloadSafe 本地收口）与「在飞通知的前台重申失败（在飞下载照跑）」
        // （抢闸失败路的前台重申）。两句都只进 logcat，不面向玩家，玩家面句子仍由上一条闸零容忍。
        // 17：2026-11-04 U2 路（CONTRACT-UPD1-3LANE）新增 5 条 logcat 取证 —— 失败单落盘/清盘
        // 各一条、系统时限 onTimeout 收尾一条、时限暂停通知与销毁兜底通知发不出去各一条。
        // 都只进 logcat，不面向玩家。
        // 19：L5（2026-11-06）新增两条取证 ——「通知不可见时直接交给安装器」与「停止下载意图没投出去」。
        assertEquals("logcat 面的汉字字面量数量变了（那是取证面，不许翻成三语）", 19, logLiterals("UpdateDownloadService.kt").size)
        assertEquals("进度判读层不该有 logcat 汉字", 0, logLiterals("UpdateProgressText.kt").size)
        assertEquals(
            "只进 logcat 的异常消息只许这一条",
            listOf("玩家取消了下载"),
            exceptionMessageLiterals("UpdateDownloadService.kt"),
        )
        assertEquals(
            "匹配上游原话的锚点只许这一处（它不是玩家面，搬进三语表匹配就失效）",
            listOf("空间不够"),
            classifierAnchorLiterals("UpdateProgressText.kt"),
        )
    }

    @Test
    fun `上屏的槽位里一个中文字面量都不许出现`() {
        // 与上一条互补：这一条**不按 Log 行放行**——凡是把文字交给 Toast / 通知 / 系统设置的调用点，
        // 实参里有中文硬编码就红（同一行既发 Toast 又打日志时，上一条会放过它，这条不会）。
        // PackageInstallerSession.kt 自 U7b 起同在名单内（回执通知与 Toast 的句子只许来自表）。
        // UpdateUi.kt 自 L5 起同在名单内（壳层只搬 copy，不自己拼玩家面句子）。
        listOf("UpdateProgressText.kt", "UpdateDownloadService.kt", "PackageInstallerSession.kt", "UpdateUi.kt").forEach { name ->
            val offenders = File(appMainSourceDir(), name).readLines().rejectCommentLines()
                .filter { line -> SCREEN_SINKS.any { line.contains(it) } }
                .flatMap { quotedLiterals(it) }
                .filter { han.containsMatchIn(it) }
            assertTrue("$name 里上屏调用点写了硬编码中文（违例：$offenders）", offenders.isEmpty())
        }
    }

    @Test
    fun `取语言只有一条路：更新壳层文件不写第二处入口也不写死语言`() {
        // PackageInstallerSession.kt 自 U7b 起同在名单内：它只许经 UpdateUi.copyFor 取语言
        // （入口线程取一次往下传），不写第二处 UpdateCopy.of、不判 Locale、不钉死某一档。
        listOf("UpdateProgressText.kt", "UpdateDownloadService.kt", "PackageInstallerSession.kt").forEach { name ->
            val lines = File(appMainSourceDir(), name).readLines().rejectCommentLines()
            val direct = lines.filter { it.contains("UpdateCopy.of(") }
            assertTrue("$name 不许自己取语言（只许壳层入口 UpdateUi.copyFor）：$direct", direct.isEmpty())
            assertTrue("$name 不许自己判系统语言", lines.none { it.contains("Locale") })
            val hardWired = lines.filter { it.contains("SettingsLanguage.ZH") || it.contains("SettingsLanguage.EN") || it.contains("SettingsLanguage.JA") }
            assertTrue("$name 不许把玩家面写死成某一档语言（违例：$hardWired）", hardWired.isEmpty())
        }
        val shell = File(appMainSourceDir(), "UpdateDownloadService.kt").readLines().rejectCommentLines()
            .filter { it.contains("UpdateUi.copyFor(") }
        // 3：2026-11-05 组长收口把 U2 失败单还原处的取语言移除 —— label 在还原链路上无人消费
        //（retryLastFailed → start → intent 只带 artifact/kind/assetsVersion），静态 companion
        // 拿不到 applicationContext，不该为一句没人读的标签开第三处入口。现余 2 处。
        assertEquals("下载服务取语言只许既有两处入口（开跑时重取 + 类初始化现取）", 2, shell.size)
        shell.forEach { assertTrue("取语言只许走壳层入口这一条路：$it", it.contains("UpdateUi.copyFor(applicationContext)")) }
    }

    @Test
    fun `通知正文只从渲染期的 kind 与文案推标签`() {
        val shell = File(appMainSourceDir(), "UpdateDownloadService.kt").readText()
        val calls = shell.lines().rejectCommentLines().filter { it.contains("UpdateProgressText.progressText(") }
        assertEquals("通知正文只许那两处渲染点（开跑一条 + 心跳刷新一条）", 2, calls.size)
        calls.forEach { assertTrue("两处都要按 kind + copy 现推标签：$it", it.contains("job.kind")) }
        assertFalse("不许再把成单那一刻的旧标签喂进进度正文", shell.contains("progressText(job.label"))
        assertTrue("成单还原也按当前语言推标签", shell.contains("UpdateProgressText.labelFor(copy(), kind)"))
        val layer = File(appMainSourceDir(), "UpdateProgressText.kt").readText()
        assertTrue("labelFor 是通知标签的唯一出处", layer.contains("fun labelFor(copy: UpdateCopy, kind: UpdateDownloadKind)"))
        assertFalse("判读层不许回头读单子上的旧标签", layer.contains("job.label"))
    }

    /**
     * L5 P1-2 的回归钉：开跑话术必须把 `copy` 传下去。
     *
     * 以前 `UpdateUi.startOrReport` 调的是固定中文的兼容口，英文/日文玩家点「下载」会看到中文
     * 的「开始下载了…」。只钉「零字面量」挡不住这种漏接线（兼容口本身没有字面量），
     * 所以这里直接钉调用点的实参形状。
     */
    @Test
    fun `壳层开跑话术必须带 copy 传下去`() {
        val ui = File(appMainSourceDir(), "UpdateUi.kt").readLines().rejectCommentLines()
        val calls = ui.filter { it.contains("UpdateProgressText.startedToast(") }
        assertEquals("开跑话术只许一处上屏点（多了就是两套话术）", 1, calls.size)
        assertTrue("开跑话术必须带 copy（漏传 = 英文/日文玩家看到中文）：${calls}", calls.first().contains("copy"))
        assertFalse(
            "改走 copy 之后不许再出现不带 copy 的兼容口调用：${calls}",
            calls.first().contains("startedToast(notifyVisible"),
        )
    }

    /**
     * L5 P1 的回归钉：通知看不见时整包下完必须走**同屏安装入口**。
     *
     * 旧实现整包完成只有一条路 —— 把安装 action 挂到结果通知上；通知被隐藏时那条路根本
     * 到不了玩家（拒绝通知权限 / 关掉进度渠道），只发一句「再点一次下载就会交给安装器」而
     * 再次下载只会命中「已核对文件直接返回」快路径。这里钉两三件事：入口函数在、Apk 完成支路
     * 在 `!notifyVisible` 时真的调它、以及文案不再承诺「点这条通知」。
     */
    @Test
    fun `整包下完在通知不可见时必须走同屏安装入口`() {
        val service = File(appMainSourceDir(), "UpdateDownloadService.kt").readText()
        assertTrue("同屏安装入口掉了", service.contains("private fun installJustDownloaded(file: File)"))
        val apkBranch = service.substringAfter("UpdateDownloadKind.Apk -> {")
            .substringBefore("UpdateDownloadKind.Assets -> {")
        assertTrue("看不见通知时必须真的把包交给安装器：$apkBranch", apkBranch.contains("installJustDownloaded(file)"))
        assertTrue("看得见通知时仍走通知点击那条路：$apkBranch", apkBranch.contains("if (notifyVisible)"))
        assertTrue("通知看不见时的正文要明说「已经直接交给安装器」", apkBranch.contains("apkDoneText(copy, false)"))
        assertTrue(
            "整套入口必须复用 UpdateManager.launchInstaller（不另造一套安装器调用）",
            service.contains("UpdateManager.launchInstaller(applicationContext, file)"),
        )
        // 文案与实现一致：通知看不见那一档不许再教玩家「去设置开通知 / 再点一次下载」——
        // 现在这一步已经由服务直接做了；「点这条通知」只属于看得见的那一档。
        val invisiblePromises = mapOf(
            SettingsLanguage.ZH to "点这条通知",
            SettingsLanguage.EN to "this notification",
            SettingsLanguage.JA to "この通知",
        )
        listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val promise = invisiblePromises.getValue(copy.lang)
            assertFalse(
                "${copy.lang} 的看不见文案仍承诺了通知点击：${copy.apkDoneNoNotifyLine}",
                copy.apkDoneNoNotifyLine.contains(promise),
            )
        }
    }

    /**
     * L5 P1（Lead 发现）的回归钉：等通知授权的那一单不许被静默吞掉。
     *
     * 三件事一起钉：设置页转发权限回调、超时兜底真的存在、界面没了改用应用上下文起服务。
     */
    @Test
    fun `等待通知授权的那一单不许被静默吞掉`() {
        val settings = File(appMainSourceDir(), "SettingsActivity.kt").readLines().rejectCommentLines()
        assertTrue("设置页要转发 onRequestPermissionsResult（不转发 pendingStart 永远挂着）",
            settings.any { it.contains("override fun onRequestPermissionsResult(") })
        assertTrue("设置页要真的调 UpdateUi.onPermissionResult",
            settings.any { it.contains("UpdateUi.onPermissionResult(requestCode, grantResults)") })
        val ui = File(appMainSourceDir(), "UpdateUi.kt").readLines().rejectCommentLines().joinToString("\n")
        assertTrue("等待授权要有超时上限", ui.contains("PENDING_START_TIMEOUT_MILLIS"))
        assertTrue("超时兜底要真挂上 Handler", ui.contains("mainHandler.postDelayed(pendingStartTimeout"))
        assertTrue("回执丢了也要把这一单跑完（改用应用上下文）", ui.contains("startWithContext(pending.appContext, pending.job)"))
        assertTrue("界面不在时也要起服务（不许只 logcat 一句就丢单）", ui.contains("private fun startWithContext(context: Context, job: DownloadJob)"))
    }

    @Test
    fun `兼容口只留所有权外真的在调的那六个`() {
        val lines = File(appMainSourceDir(), "UpdateProgressText.kt").readLines()
        val marker = lines.indexOfFirst { it.contains("EP4 U3b 兼容口") }
        assertTrue("兼容口区块没了（接线完成后连注释一起删，这里先报红并同步本条计数）", marker >= 0)
        val section = lines.drop(marker)
        val compat = section.withIndex().filter { it.value.trim().startsWith("fun ") }
        // L5（2026-11-06）删掉 startedToast(notifyVisible) 这个中文口：UpdateUi 已改传 copy。
        assertEquals("兼容口的数量变了（现有 6 个：所有权外的调用点逐个接线，接一个删一个）", 6, compat.size)
        assertTrue(
            "「接着下」已按 kind 现取，那个不带 copy 的中文口不许复活",
            compat.none { it.value.contains("settingsRetryLabel") },
        )
        compat.forEach { (offset, signature) ->
            val name = signature.substringAfter("fun ").substringBefore("(").trim()
            val body = (listOf(signature) + section.drop(offset + 1).takeWhile { !it.trim().startsWith("fun ") }).joinToString(" ")
            assertTrue("兼容口 $name 的实体没代 UpdateCopy.ZH：$body", body.contains("UpdateCopy.ZH"))
            val literals = quotedLiterals(body).filter { it.isNotEmpty() }
            assertTrue("兼容口 $name 里出现了字面量（只能代进表，不许另写一句）：$literals", literals.isEmpty())
        }
    }

    @Test
    fun `兼容口取的就是中文那一组（行为对拍，接线前不许漂）`() {
        assertEquals(
            UpdateProgressText.progressText(UpdateCopy.ZH, "新版本", 140L * MIB, 743L * MIB),
            UpdateProgressText.progressText("新版本", 140L * MIB, 743L * MIB),
        )
        assertEquals(
            UpdateProgressText.failureAction(UpdateCopy.ZH, UpdateDownloadPlan.FailureKind.Network, ""),
            UpdateProgressText.failureAction(UpdateDownloadPlan.FailureKind.Network, ""),
        )
        assertEquals(UpdateProgressText.apkDoneText(UpdateCopy.ZH), UpdateProgressText.apkDoneText())
        assertEquals(UpdateProgressText.assetsDoneText(UpdateCopy.ZH), UpdateProgressText.assetsDoneText())
        assertEquals(UpdateProgressText.cancelledText(UpdateCopy.ZH), UpdateProgressText.cancelledText())
        assertEquals(UpdateProgressText.failedText(UpdateCopy.ZH, "镜像连不上"), UpdateProgressText.failedText("镜像连不上"))
        // 开跑话术的中文兼容口已在 L5 删除：它只能由壳层带 copy 的主签名出词
        // （回归钉见 `壳层开跑话术必须带 copy 传下去`）。
        // 「接着下」不再有中文兼容口：设置页传的是 kind，这里钉它出的就是**本语言表里那句**，
        // 而不是成单那一刻的名字（换一档语言它就跟着换词 = 词源在表里）。
        assertEquals(
            "按 kind 现取的「接着下」与按本语言标签拼的不一致",
            UpdateProgressText.settingsRetryLabel(UpdateCopy.ZH, UpdateCopy.ZH.assetsLabel),
            UpdateProgressText.settingsRetryLabel(UpdateCopy.ZH, UpdateDownloadKind.Assets),
        )
        // 兼容口走的必须是**中文那档的表**，不是文件里另写的句子：换一档语言它就跟着变，说明词源在表里。
        assertTrue(UpdateProgressText.apkDoneText().contains(UpdateCopy.ZH.apkDoneLine))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 本车道会上屏的每一句（按语言取一遍），供术语闸遍历。 */
    private fun playerSentencesOf(copy: UpdateCopy): List<String> {
        val kinds = UpdateDownloadPlan.FailureKind.values().toList()
        val out = ArrayList<String>()
        out += UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, 140L * MIB, 743L * MIB)
        out += UpdateProgressText.progressText(copy, UpdateDownloadKind.Assets, 12L * MIB, 0L)
        out += UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, streamingSnapshot())
        out += UpdateProgressText.progressText(copy, UpdateDownloadKind.Assets, stalledSnapshot())
        out += UpdateProgressText.progressText(copy, UpdateDownloadKind.Apk, quietSnapshot())
        out += listOf(true, false).flatMap { visible ->
            listOf(
                UpdateProgressText.startedToast(copy, visible),
                UpdateProgressText.apkDoneText(copy, visible),
                UpdateProgressText.cancelledText(copy, visible),
                UpdateProgressText.retryEntry(copy, visible),
                UpdateProgressText.failedText(copy, "", null, visible),
                UpdateProgressText.failedText(copy, "some problem", kinds.first(), visible),
            )
        }
        out += UpdateProgressText.assetsDoneText(copy)
        out += UpdateProgressText.failureToast(copy, null)
        out += kinds.map { UpdateProgressText.failureAction(copy, it, "") }
        out += kinds.map { UpdateProgressText.failureAction(copy, it, SPACEY) }
        out += UpdateProgressText.failureAction(copy, null, "")
        out += UpdateProgressText.failureAction(copy, null, "一条也没连上")
        out += listOf(
            UpdateProgressText.settingsRetryLabel(copy, copy.apkLabel),
            UpdateProgressText.settingsRetryLabel(copy, UpdateDownloadKind.Assets),
        )
        out += listOf(1L, 44_500L, 140_750L, 10L * 60_000L + 500L, 3_900_000L).map { UpdateProgressText.durationLabel(copy, it) }
        out += UpdateProgressText.speedClause(copy, 4L * MIB)
        out += UpdateProgressText.labelFor(copy, UpdateDownloadKind.Apk)
        out += UpdateProgressText.labelFor(copy, UpdateDownloadKind.Assets)
        // 壳层直接上屏的那几格（通知标题 / 按钮 / 渠道名 / Toast）也走表，术语面一起过。
        out += copy.progressNotifyTitle
        out += copy.cancelNotifyAction
        out += copy.channelProgressName
        out += copy.channelResultName
        out += copy.apkReadyTitle
        out += copy.assetsReadyTitle
        out += copy.assetsRegisterFailedTitle
        out += copy.assetsRegisterProblemLine
        out += copy.cancelledNotifyTitle
        out += copy.failedNotifyTitle
        out += copy.notStartedNotifyTitle
        out += copy.downloadBusyToast
        out += copy.fill(copy.noNotifyBarStartFailedFormat, "IllegalStateException")
        out += copy.fill(copy.installerOpenFailedFormat, "ActivityNotFoundException")
        out += copy.apkMissingToast
        out += copy.fill(copy.settingsRetryLabelFormat, copy.assetsLabel)
        return out
    }

    private fun streamingSnapshot(): UpdateProgressText.ProgressSnapshot = snapshot(
        doneBytes = 140L * MIB,
        offsets = listOf(0L to 0L, 40L * MIB to 10_000L),
        nowMillis = T0 + 10_000L,
    )

    /** 静默到像卡住：字节没动但整段跑过了换地址的量级。 */
    private fun stalledSnapshot(): UpdateProgressText.ProgressSnapshot = snapshot(
        doneBytes = 140L * MIB,
        offsets = listOf(0L to 0L, 40L * MIB to 10_000L),
        nowMillis = T0 + 200_000L,
        silentMillis = UpdateProgressText.SILENT_SWITCH_MILLIS,
        elapsedMillis = UpdateProgressText.SWITCH_MIN_ELAPSED_MILLIS,
    )

    /** 只有一枚采样点：速度算不出、预报也就算不出（那条「宁可不报」的分支）。 */
    private fun quietSnapshot(): UpdateProgressText.ProgressSnapshot = snapshot(
        doneBytes = 100L * MIB,
        offsets = listOf(0L to 0L),
        nowMillis = T0,
        elapsedMillis = 1_000L,
    )

    private fun snapshot(
        doneBytes: Long,
        offsets: List<Pair<Long, Long>>,
        nowMillis: Long,
        silentMillis: Long = 0L,
        elapsedMillis: Long = 10_000L,
    ): UpdateProgressText.ProgressSnapshot = UpdateProgressText.ProgressSnapshot(
        doneBytes = doneBytes,
        totalBytes = 743L * MIB,
        samples = offsets.map { (delta, offset) ->
            UpdateProgressText.ProgressSample(100L * MIB + delta, T0 + offset)
        },
        nowMillis = nowMillis,
        peakDoneBytes = doneBytes,
        silentMillis = silentMillis,
        elapsedMillis = elapsedMillis,
    )

    private fun occurrences(haystack: String, needle: String): Int {
        var hits = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            hits++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return hits
    }

    private fun playerFacingLiterals(name: String): List<String> =
        sourceLines(name)
            .filterNot { isDiagnosticLine(it) }
            .filterNot { isClassifierAnchor(it) }
            .flatMap { quotedLiterals(it) }
            .filter { han.containsMatchIn(it) }

    private fun logLiterals(name: String): List<String> =
        sourceLines(name).filter { it.trim().contains("Log.") }
            .flatMap { quotedLiterals(it) }
            .filter { han.containsMatchIn(it) }

    private fun exceptionMessageLiterals(name: String): List<String> =
        sourceLines(name).filter { it.trim().contains("throw InterruptedException(") }
            .flatMap { quotedLiterals(it) }
            .filter { han.containsMatchIn(it) }

    private fun classifierAnchorLiterals(name: String): List<String> =
        sourceLines(name).filter { isClassifierAnchor(it) }
            .flatMap { quotedLiterals(it) }
            .filter { han.containsMatchIn(it) }

    /**
     * 诊断面：logcat 那一行与只进 logcat 的异常消息。
     *
     * 放行条件不是「这行含 Log.」而是「这行没把文字交给玩家」：同一行既发 Toast 又打日志时，
     * 中文照样算违例（`上屏的槽位` 那条闸兜住这一手）。
     */
    private fun isDiagnosticLine(line: String): Boolean {
        val t = line.trim()
        if (SCREEN_SINKS.any { t.contains(it) }) return false
        return t.contains("Log.") || t.contains("throw InterruptedException(")
    }

    /** 拿去**匹配**上游原话的锚点声明（不是玩家面，也不许搬进三语表）。 */
    private fun isClassifierAnchor(line: String): Boolean =
        line.trim().startsWith("private const val") && line.contains("SPACE_PROBLEM_HINT")

    private fun sourceLines(name: String): List<String> =
        File(appMainSourceDir(), name).readLines().rejectCommentLines()

    private fun appMainSourceDir(): File {
        val fromModule = File("src/main/kotlin/com/eratw/port/android")
        if (fromModule.isDirectory) return fromModule
        val fromRepoRoot = File("app/src/main/kotlin/com/eratw/port/android")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        throw IllegalStateException(
            "找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）——守卫用例无法检查，视为红而不是跳过",
        )
    }

    private fun List<String>.rejectCommentLines(): List<String> = filterNot { raw ->
        val t = raw.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*/") || t.startsWith("*")
    }

    /** 一行里成对双引号包住的片段（允许 `\"` 与 `\n` 这类转义；带转义的路径不算字面量的老口径不适用本表）。 */
    private fun quotedLiterals(line: String): List<String> =
        Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(line).map { it.groupValues[1] }.toList()

    private companion object {
        val MIB = 1024L * 1024L
        const val T0 = 1_700_000_000_000L

        /** UpdateManager 造的磁盘不足原话（UpdateManager.kt:490-496 的形态）：按原话再分一支的入口。 */
        const val SPACEY = "空间不够：app.apk 共 743 MiB，还要留 232 MiB 余量，盘上只剩 10 MiB"

        /** 「约 X 后下完」那一格在三语里各长什么样：判「有没有多一句/少一句」就按这个找。 */
        val etaMarks = mapOf(
            SettingsLanguage.ZH to "后下完",
            SettingsLanguage.EN to " to finish",
            SettingsLanguage.JA to "で完了の見込み",
        )

        /** 「约 X/秒」那一格的三语标记。 */
        val speedMarks = mapOf(
            SettingsLanguage.ZH to "/秒",
            SettingsLanguage.EN to " per second",
            SettingsLanguage.JA to "/秒",
        )

        /** 文字交给玩家的那些调用点：实参里的中文字面量一律算违例（不看它是不是也打了日志）。 */
        val SCREEN_SINKS = listOf(
            "honestToast(", "postResultNotification(", "setContentTitle(", "setContentText(",
            "NotificationChannel(", "Toast.makeText(", "bigText(",
        )
    }
}
