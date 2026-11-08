package com.eratw.port.android

import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.ApkChannel
import com.eratw.port.core.update.AssetsChannel
import com.eratw.port.core.update.UpdateInfo
import com.eratw.port.core.update.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateUiText] 与 [UpdateProgressText] 的定向单测（CONTRACT-UPDATE U-E 出口判据）。
 *
 * 钉的全是「会不会误导玩家」的判读面（纯 JVM，不碰 Android 运行时）：
 * - §1.5 不静默：任何下载要先成单（[UpdateUiText.jobFor]），蜂窝必须再确认
 *   （[UpdateUiText.cellularConfirmRequired] 接线 core 的 `autoDownloadAllowed`）；
 * - §1.5 跳过此版本：只对同一个 versionCode 生效、新版照常提醒、纯数据更新没得跳；
 * - U-E 第 1 条：通知正文给得出 已下/总量/百分比/还剩，失败与取消各说各话；
 * - U-E 第 4 条：手动检查把「包里没配源」与「配了但连不上」分开说（处理人不同）。
 *
 * 真源形状引用：产物地址形如 `https://eratw-android.asia/updates/<artifact-file>`（契约 §1.1），
 * 本测试只造形状样本，不联网。
 */
class UpdateUiTest {

    /**
     * EP4 U3a：这一层的句子全部来自 [UpdateCopy]，本文件钉的**中文原文一个字都没动** ——
     * 用例只是将同一句取自 `UpdateCopy.ZH`（与搬动前的硬编码逐字相同），另由 U3a 新增的
     * 三语形状用例保证 EN/JA 同结构。
     */
    private val copy = UpdateCopy.ZH
    private val sha = "a".repeat(64)
    private val mib = 1024L * 1024L

    private fun artifact(name: String, size: Long) = Artifact(name, size, sha)

    private fun apkInfo(code: Long, name: String = "0.9.9") = UpdateInfo(
        format = 1,
        generated = "2026-11-01T00:00:00Z",
        note = "",
        apk = ApkChannel(code, name, artifact("eratw-$name.apk", 700L * mib)),
        assets = null,
        mirrors = emptyList(),
        warnings = emptyList(),
    )

    private fun assetsInfo(assetsVersion: Long) = UpdateInfo(
        format = 1,
        generated = "2026-11-01T00:00:00Z",
        note = "",
        apk = null,
        assets = AssetsChannel(assetsVersion, artifact("eratw-data-$assetsVersion.zip", 680L * mib)),
        mirrors = emptyList(),
        warnings = emptyList(),
    )

    // ------------------------------------------------------------------
    // 成单：下载哪一件（core 已判好差量优先，app 只搬结果）
    // ------------------------------------------------------------------

    @Test
    fun `整包与数据同时可更时只成整包一单（超集规则）`() {
        val info = UpdateInfo(
            format = 1,
            generated = "2026-11-01T00:00:00Z",
            note = "",
            apk = ApkChannel(9, "0.2.34", artifact("eratw-0.2.34.apk", 743L * mib)),
            assets = AssetsChannel(1200, artifact("eratw-data-1200.zip", 680L * mib)),
            mirrors = emptyList(),
            warnings = emptyList(),
        )
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = true, assetsNeeded = true, 8L, 1199L, info.assets?.artifact)
        val job = UpdateUiText.jobFor(copy, offer)
        assertNotNull(job)
        assertEquals(UpdateDownloadKind.Apk, job!!.kind)
        assertEquals("eratw-0.2.34.apk", job.artifact.fileName)
    }

    @Test
    fun `数据通道下的是 dataArtifact（core 判好的差量）而不是 assets 老键的全量`() {
        val info = assetsInfo(1200L)
        val delta = artifact("eratw-data-delta-1199to1200.zip", 32L * mib)
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = false, assetsNeeded = true, 8L, 1199L, delta)
        val job = UpdateUiText.jobFor(copy, offer)!!
        assertEquals(UpdateDownloadKind.Assets, job.kind)
        assertEquals(delta.fileName, job.artifact.fileName)
        assertEquals(32L * mib, job.artifact.sizeBytes)
        // 暂存登记的版本号仍是通道目标版本（应用拍按它判「严格大于才应用」）。
        assertEquals(1200L, job.assetsVersion)
    }

    @Test
    fun `老形态 offer 没带 dataArtifact 时回落全量键`() {
        val info = assetsInfo(1200L)
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = false, assetsNeeded = true, 8L, 1199L)
        val job = UpdateUiText.jobFor(copy, offer)!!
        assertEquals("eratw-data-1200.zip", job.artifact.fileName)
    }

    @Test
    fun `没有任何需要下载的东西时成不了单`() {
        val info = apkInfo(9L)
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = false, assetsNeeded = false, 9L, 0L)
        assertNull(UpdateUiText.jobFor(copy, offer))
    }

    @Test
    fun `章不合时整包一票否决但数据通道照走（契约 1p7 预检的消费点）`() {
        val info = UpdateInfo(
            format = 1,
            generated = "2026-11-01T00:00:00Z",
            note = "",
            apk = ApkChannel(9, "0.2.34", artifact("eratw-0.2.34.apk", 743L * mib)),
            assets = AssetsChannel(1200, artifact("eratw-data-1200.zip", 680L * mib)),
            mirrors = emptyList(),
            warnings = emptyList(),
        )
        val reason = "这台设备上装的是另一套签名章的包（内测/旧包），新包无法覆盖升级。"
        val offer = UpdateManager.UpdateOffer(
            info, apkNeeded = true, assetsNeeded = true, 8L, 1199L,
            dataArtifact = null, installBlockedReason = reason,
        )
        val job = UpdateUiText.jobFor(copy, offer)!!
        // 不给整包单（白下 743 MiB 再收系统英文报错是最坏形状），退化成数据包单。
        assertEquals(UpdateDownloadKind.Assets, job.kind)
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue(lines.any { it.contains("无法安装") && it.contains("0.2.34") })
        // 章不合的说明文案里不许漏出 markdown 强调记号（屏上没有渲染器）。
        assertTrue(lines.none { it.contains("**") })
        // 数据行照常出现（zip 不经安装器，不受覆盖升级互斥影响）。
        assertTrue(lines.any { it.contains("数据包 1200") })
    }

    @Test
    fun `章不合且没有数据可更时成不了任何单`() {
        val info = apkInfo(9L)
        val offer = UpdateManager.UpdateOffer(
            info, apkNeeded = true, assetsNeeded = false, 8L, 0L,
            installBlockedReason = "另一套签名章",
        )
        assertNull(UpdateUiText.jobFor(copy, offer))
        // 但坏消息本身要有台词可讲（弹窗按「知道了」单按钮出）。
        assertTrue(UpdateUiText.offerMessageLines(copy, offer, onCellular = false).any { it.contains("无法安装") })
    }

    // ------------------------------------------------------------------
    // 蜂窝二次确认（把 autoDownloadAllowed 真接上）
    // ------------------------------------------------------------------

    @Test
    fun `非计量网络放行不追问 蜂窝必须追问`() {
        assertFalse(UpdateUiText.cellularConfirmRequired(isUnmetered = true))
        assertTrue(UpdateUiText.cellularConfirmRequired(isUnmetered = false))
        // 与 core 的口径同源：判定本体在 UpdatePolicy.autoDownloadAllowed，app 不许另立。
        assertEquals(UpdatePolicy.autoDownloadAllowed(true), !UpdateUiText.cellularConfirmRequired(true))
    }

    @Test
    fun `蜂窝确认正文用 sizeLabel 说清要下多少`() {
        val job = DownloadJob(artifact("eratw-0.2.34.apk", 743L * mib), UpdateDownloadKind.Apk, "新版本", 0L)
        val body = UpdateUiText.cellularConfirmBody(copy, job)
        assertTrue(body.contains("743 MiB"))
        assertTrue(body.contains("流量"))
        // 续传口径也必须在确认里说明白（玩家怕的是「断了白下」）。
        assertTrue(body.contains("断"))
    }

    // ------------------------------------------------------------------
    // 通知文案：看得见（已下/总量/百分比/剩余）+ 各结局各说各话
    // ------------------------------------------------------------------

    @Test
    fun `进度通知给全百分比与剩余量`() {
        val text = UpdateProgressText.progressText("新版本", 300L * mib, 743L * mib)
        assertTrue(text.contains("40%"))
        assertTrue(text.contains("300 MiB"))
        assertTrue(text.contains("743 MiB"))
        assertTrue(text.contains("还剩 443 MiB"))
    }

    @Test
    fun `进度通知在总量未知时退化为只报已下而不是除零`() {
        val text = UpdateProgressText.progressText("数据包", 12L * mib, 0L)
        assertTrue(text.contains("12 MiB"))
        assertFalse(text.contains("%"))
    }

    @Test
    fun `完成取消失败三种结局的文案互相不串`() {
        assertTrue(UpdateProgressText.apkDoneText().contains("安装"))
        assertTrue(UpdateProgressText.assetsDoneText().contains("下次启动"))
        assertTrue(UpdateProgressText.cancelledText().contains("断"))
        val failed = UpdateProgressText.failedText("镜像连不上")
        assertTrue(failed.contains("镜像连不上"))
        assertTrue(failed.contains("重试"))
    }

    // ------------------------------------------------------------------
    // 跳过此版本：只抑同一个 versionCode，纯数据更新没得跳
    // ------------------------------------------------------------------

    @Test
    fun `跳过按钮只在有整包版本时给得出`() {
        val apkOffer = UpdateManager.UpdateOffer(apkInfo(9L), apkNeeded = true, assetsNeeded = false, 8L, 0L)
        assertEquals(9L, UpdateUiText.skipCodeForReminder(apkOffer))
        val assetsOffer = UpdateManager.UpdateOffer(assetsInfo(1200L), apkNeeded = false, assetsNeeded = true, 8L, 1199L)
        assertEquals(-1L, UpdateUiText.skipCodeForReminder(assetsOffer))
    }

    @Test
    fun `跳过的语义钉在同版抑制且新版不抑制（委托 core 判定）`() {
        assertTrue(UpdatePolicy.isVersionSuppressed(9L, 9L))
        assertFalse(UpdatePolicy.isVersionSuppressed(10L, 9L))
        assertFalse(UpdatePolicy.isVersionSuppressed(9L, 0L))
    }

    @Test
    fun `skippedVersionNote 只在真跳过过的时候占一行`() {
        assertNull(UpdateUiText.skippedVersionNote(copy, 0L))
        val note = UpdateUiText.skippedVersionNote(copy, 9L)
        assertNotNull(note)
        assertTrue(note!!.contains("9"))
        assertTrue(note.contains("恢复"))
    }

    // ------------------------------------------------------------------
    // 手动检查的报障：两类失败分开说（处理人不同）
    // ------------------------------------------------------------------

    @Test
    fun `没配源的报障把责任指向出包而不是玩家网络`() {
        val report = UpdateUiText.noBuiltInMirrorReport(copy)
        assertTrue(report.contains("没有内置更新源"))
        assertTrue(report.contains("不是"))
    }

    @Test
    fun `配了源但连不上时逐条点名`() {
        val problems = listOf("a.example：取不到清单", "b.example：取不到清单")
        val report = UpdateUiText.checkFailedReport(copy, 2, problems)
        assertTrue(report.contains("已配置 2 条"))
        assertTrue(report.contains("a.example"))
        assertTrue(report.contains("b.example"))
        assertTrue(report.contains("连不上"))
    }

    @Test
    fun `检查抛错也要留下异常名给取证`() {
        val report = UpdateUiText.checkCrashedReport(copy, IllegalStateException("boom"))
        assertTrue(report.contains("IllegalStateException"))
        assertTrue(report.contains("稍后再试"))
    }

    @Test
    fun `最新版读数把整包与数据两个版本都报出来`() {
        val report = UpdateUiText.upToDateReport(copy, 8L, 1199L)
        assertTrue(report.contains("整包 8"))
        assertTrue(report.contains("数据 1199"))
    }

    // ------------------------------------------------------------------
    // 弹窗正文与授权话术
    // ------------------------------------------------------------------

    @Test
    fun `弹窗正文：整包一行含版本名与大小 蜂窝提示只在蜂窝时出现`() {
        val info = apkInfo(9L, "0.2.34").copyNote("修了一个致命 bug")
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = true, assetsNeeded = false, 8L, 0L)
        val calm = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue(calm.any { it.contains("0.2.34") && it.contains("700 MiB") })
        // EP4 U2-⑤：长说明从正文搬进「看说明」展开层，正文只留做决定要用的行。
        assertTrue(calm.none { it.contains("修了一个致命 bug") })
        assertTrue(UpdateUiText.offerDetailLines(copy, info.note, emptyList()).any { it.contains("修了一个致命 bug") })
        assertTrue(calm.none { it.contains("流量") })
        // UpdateInfo 没有 copy 助手，note 用直接构造即可；这里只加一条蜂窝场景断言。
        val worried = UpdateUiText.offerMessageLines(copy, offer, onCellular = true)
        assertTrue(worried.any { it.contains("移动数据") })
    }

    @Test
    fun `差量数据在弹窗里注明只补改动的部分`() {
        val info = assetsInfo(1200L)
        val delta = artifact("eratw-data-delta.zip", 32L * mib)
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = false, assetsNeeded = true, 8L, 1199L, delta)
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue(lines.any { it.contains("仅包含变更") })
    }

    @Test
    fun `纯数据更新的弹窗先解释安装包与数据是两件事`() {
        // 玩家困惑形状（2026-11-03 实测反馈）：手动装完最新 APK 后又弹「有更新」，
        // 不知道弹的是分开随包发的数据。解释行必须是第一行，标题也换成数据形状的。
        val info = assetsInfo(15L)
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = false, assetsNeeded = true, 15L, 14L)
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue("第一行要先把安装包与数据分开说：$lines", lines[0].contains("已是最新版本"))
        assertTrue("数据行要保留体积与下次启动应用的读数：$lines",
            lines.any { it.contains("数据包") && it.contains("下次启动") })
        assertEquals(copy.dialogOfferAssetsTitle, UpdateUiText.offerDialogTitle(copy, offer))
    }

    @Test
    fun `带整包的更新弹窗不出现纯数据解释行且标题照旧`() {
        val info = UpdateInfo(
            format = 1,
            generated = "2026-11-01T00:00:00Z",
            note = "",
            apk = ApkChannel(16, "0.2.40", artifact("eratw-0.2.40.apk", 743L * mib)),
            assets = AssetsChannel(15, artifact("eratw-data-15.zip", 680L * mib)),
            mirrors = emptyList(),
            warnings = emptyList(),
        )
        val offer = UpdateManager.UpdateOffer(info, apkNeeded = true, assetsNeeded = true, 15L, 14L)
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertFalse(lines.any { it.contains("已是最新版本") })
        assertEquals(copy.dialogOfferTitle, UpdateUiText.offerDialogTitle(copy, offer))
    }

    @Test
    fun `纯数据解释行与数据标题三语都成句`() {
        for (c in listOf(enCopy, jaCopy)) {
            assertTrue("英文/日文的解释行不能是空串", c.offerAssetsOnlyExplainerLine.isNotEmpty())
            assertTrue("英文/日文的数据标题不能是空串", c.dialogOfferAssetsTitle.isNotEmpty())
        }
    }

    @Test
    fun `整包弹窗把新版本号与本机版本名并排说清`() {
        val info = apkInfo(9L, "0.2.41")
        val offer = UpdateManager.UpdateOffer(
            info,
            apkNeeded = true,
            assetsNeeded = false,
            currentVersionCode = 8L,
            installedDataVersion = 0L,
            currentVersionName = "0.2.40",
        )
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue("新版本与本机版本都要上屏，玩家才看得出从哪升到哪：$lines",
            lines.any { it.contains("0.2.41") && it.contains("0.2.40") })
    }

    @Test
    fun `本机版本名读不出时回落 versionCode`() {
        val offer = UpdateManager.UpdateOffer(apkInfo(9L, "0.2.41"), apkNeeded = true, assetsNeeded = false, 8L, 0L)
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue("versionName 为空串时不能画出一个空括号：$lines", lines.any { it.contains("当前版本 8") })
    }

    @Test
    fun `纯数据解释行也带本机版本号`() {
        val offer = UpdateManager.UpdateOffer(
            assetsInfo(15L),
            apkNeeded = false,
            assetsNeeded = true,
            currentVersionCode = 15L,
            installedDataVersion = 14L,
            currentVersionName = "0.2.41",
        )
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue("解释行要说清这台机器上的安装包是哪个版本：$lines", lines[0].contains("0.2.41"))
    }

    @Test
    fun `强调行下标纯数据钉在数据行 带整包钉在整包行`() {
        // 纯数据：首行是解释行（不强调），数据行在第 1 位。
        val assetsOffer = UpdateManager.UpdateOffer(assetsInfo(15L), apkNeeded = false, assetsNeeded = true, 15L, 14L)
        assertEquals(1, UpdateUiText.offerHeadlineIndex(assetsOffer, 3))
        // 带整包：整包行就是首行（章不合的坏消息也在这一行）。
        val apkOffer = UpdateManager.UpdateOffer(apkInfo(16L), apkNeeded = true, assetsNeeded = false, 15L, 0L)
        assertEquals(0, UpdateUiText.offerHeadlineIndex(apkOffer, 2))
        // 没有行可画（不该发生的形状）：-1 让壳层跳过强调，别把 -1 当真下标用。
        assertEquals(-1, UpdateUiText.offerHeadlineIndex(apkOffer, 0))
    }

    @Test
    fun `通知授权只在 API 33 起需要`() {
        assertFalse(UpdateUiText.notificationPermissionNeeded(32))
        assertTrue(UpdateUiText.notificationPermissionNeeded(33))
    }

    @Test
    fun `开跑话术：授权了说通知栏 拒绝了也说清看不见但仍照下`() {
        // L5：中文兼容口已删，壳层一律带 copy 调主签名（EN/JA 玩家不再看到中文）。
        val granted = UpdateProgressText.startedToast(copy, true)
        assertTrue(granted.contains("通知栏"))
        val denied = UpdateProgressText.startedToast(copy, false)
        assertTrue(denied.contains("看不到进度"))
        assertTrue(denied.contains("开始下载"))
    }

    // ------------------------------------------------------------------
    // EP4 U3a：同一批判读在三语下的形状（中文侧就是上面那些既有用例，逐字未动）
    // ------------------------------------------------------------------

    private val enCopy = UpdateCopy.EN
    private val jaCopy = UpdateCopy.JA

    @Test
    fun `蜂窝确认正文三语都报得出体积并说明断了不白下`() {
        val job = DownloadJob(artifact("eratw-0.2.34.apk", 743L * mib), UpdateDownloadKind.Apk, enCopy.apkLabel, 0L)
        val en = UpdateUiText.cellularConfirmBody(enCopy, job)
        assertTrue("英文正文要带体积：$en", en.contains("743 MiB"))
        assertTrue("英文正文要说得清走的是流量：$en", en.contains("mobile data") && en.contains("Wi-Fi"))
        val ja = UpdateUiText.cellularConfirmBody(jaCopy, job.copy(label = jaCopy.apkLabel))
        assertTrue("日文正文要带体积：$ja", ja.contains("743 MiB"))
        assertTrue("日文正文要说得清走的是流量：$ja", ja.contains("Wi-Fi"))
        // 行数结构：三语都是同一段（换语言不许少一句）。
        assertEquals(
            "三语的蜂窝确认正文行数不一致",
            UpdateUiText.cellularConfirmBody(copy, job.copy(label = copy.apkLabel)).lineSequence().count(),
            en.lineSequence().count(),
        )
    }

    @Test
    fun `检查的三类结果在三语下都把读数报出来`() {
        listOf(enCopy, jaCopy).forEach { c ->
            val failed = UpdateUiText.checkFailedReport(c, 2, listOf("a.example：取不到清单", "b.example：超时"), 1)
            assertTrue("${c.lang} 的报障要报出源条数：$failed", failed.contains("2"))
            assertTrue("${c.lang} 的报障要逐条点名：$failed", failed.contains("a.example") && failed.contains("b.example"))
            assertTrue("${c.lang} 的报障要把提醒条数带上：$failed", failed.contains("1"))
            val latest = UpdateUiText.upToDateReport(c, 8L, 1199L)
            assertTrue("${c.lang} 的最新版读数两个都要报：$latest", latest.contains("8") && latest.contains("1199"))
            val crashed = UpdateUiText.checkCrashedReport(c, IllegalStateException("boom"))
            assertTrue("${c.lang} 的抛错句要留异常名给取证：$crashed", crashed.contains("IllegalStateException"))
            // 「没配源」与「连不上」的处理人不同，三语都不许串（中文侧是既有那条用例）。
            assertFalse("${c.lang} 把连不上说成了没配地址：$failed", failed.contains(c.noBuiltInMirrorLine))
            assertTrue("${c.lang} 的没配源要说清不是玩家的网络", UpdateUiText.noBuiltInMirrorReport(c).isNotBlank())
        }
    }

    @Test
    fun `跳过提醒的话术三语都引用同一条恢复口句子`() {
        listOf(enCopy, jaCopy).forEach { c ->
            assertTrue("${c.lang} 的 Toast 要带上恢复口：${c.skippedToastFormat}", UpdateUiText.skippedToast(c, 9L).contains(c.reminderRestorePhrase))
            val note = UpdateUiText.skippedVersionNote(c, 9L)
            assertNotNull(note)
            assertTrue("${c.lang} 的设置页小字要带上同一个恢复口", note!!.contains(c.reminderRestorePhrase))
            assertNull(UpdateUiText.skippedVersionNote(c, 0L))
            // 版本读数与语言无关，三语都得留着。
            assertTrue(note.contains("9"))
        }
    }

    /** 测试辅助：note 不可变，构造时带上（本文件内部使用）。 */
    private fun UpdateInfo.copyNote(newNote: String): UpdateInfo = UpdateInfo(
        format = format,
        generated = generated,
        note = newNote,
        apk = apk,
        assets = assets,
        mirrors = mirrors,
        warnings = warnings,
        assetsFull = assetsFull,
        assetsDelta = assetsDelta,
        assetsBaseVersion = assetsBaseVersion,
        apkCertSha256 = apkCertSha256,
    )
}
