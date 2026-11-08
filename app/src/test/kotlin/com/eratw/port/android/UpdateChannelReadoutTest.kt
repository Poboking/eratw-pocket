package com.eratw.port.android

import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.ApkChannel
import com.eratw.port.core.update.AssetsChannel
import com.eratw.port.core.update.UpdateInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EP4 U2「检查态与通道现状要说得清」的定向单测（CONTRACT-EP4-UPDATE-UI §2 U2 / §3）。
 *
 * 钉的全是判读面（契约 §0.2：壳层碰一下 `Looper.getMainLooper()` 就炸，所以判读必须在纯 JVM 里）：
 * - 病 ①：[UpdateChannelReadout] 的分行现状 —— 地址原文与裸版本号只许出现在展开层，玩家面零术语；
 * - 病 ②：[UpdateUiText.checkButtonLabel] / [UpdateUiText.checkBusyReport] 的进行中态话术，
 *   以及源码面的「单飞闸只有一处放闸、每个结果分支都收回进行中态」；
 * - 病 ③：四种结果形状一次判出（[UpdateChannelReadout.outcomeOf]），失败形状讲得出、
 *   且**不许**把「配了但连不上」说成「包里没配源」（处理人不同）；
 * - 病 ④：清单 warnings 的玩家面消费点（手动上屏 + 自动进摘要），以及原话编解码的截断口径；
 * - 病 ⑤：弹窗正文最多三行、长说明与提醒原话只在展开层，章不合的坏消息必须留在正文第一行；
 * - 病 ⑥：跳过提醒的恢复口话术单源，并有源码面用例钉「不许再出现第三种说法」。
 *
 * 另有三面「既有语义不许回退」的用例：章不合坏消息送达 + 正按钮退化、纯数据更新不给「以后都不」、
 * 手动检查结果一律上屏。
 */
class UpdateChannelReadoutTest {

    /**
     * EP4 U3a：判读层的句子全部来自 [UpdateCopy]。本文件原有的中文断言**一条都没改**
     * （`UpdateCopy.ZH` 的每个值与搬动前的硬编码逐字相同），新增的三语用例见文件末尾。
     */
    private val copy = UpdateCopy.ZH
    private val sha = "a".repeat(64)
    private val mib = 1024L * 1024L

    private fun artifact(name: String, size: Long) = Artifact(name, size, sha)

    private fun apkAndDataOffer(
        note: String = "",
        blocked: String? = null,
    ): UpdateManager.UpdateOffer {
        val info = UpdateInfo(
            format = 1,
            generated = "2026-11-01T00:00:00Z",
            note = note,
            apk = ApkChannel(9, "0.2.34", artifact("eratw-0.2.34.apk", 743L * mib)),
            assets = AssetsChannel(1200, artifact("eratw-data-1200.zip", 680L * mib)),
            mirrors = emptyList(),
            warnings = emptyList(),
        )
        return UpdateManager.UpdateOffer(
            info,
            apkNeeded = true,
            assetsNeeded = true,
            currentVersionCode = 8L,
            installedDataVersion = 1199L,
            dataArtifact = null,
            installBlockedReason = blocked,
        )
    }

    /** 一个「查成功了、什么都没发生」的正常现状；用例只覆盖自己关心的那一项。 */
    private fun snapshot(
        mirrorCount: Int = 2,
        preferredMirror: String = "https://eratw-android.asia/updates/",
        lastSuccessMillis: Long = 0L,
        lastAttemptMillis: Long = 0L,
        outcome: String = UpdateChannelReadout.OUTCOME_UNKNOWN,
        notes: List<String> = emptyList(),
        noteCount: Int = notes.size,
        stagedDataVersion: Long = 0L,
        skippedVersionCode: Long = 0L,
        autoChannelWanted: Boolean = true,
        nowMillis: Long = 0L,
    ) = UpdateChannelReadout.Snapshot(
        mirrorCount = mirrorCount,
        preferredMirror = preferredMirror,
        lastSuccessMillis = lastSuccessMillis,
        lastAttemptMillis = lastAttemptMillis,
        outcome = outcome,
        notes = notes,
        noteCount = noteCount,
        currentVersionCode = 12L,
        installedDataVersion = 12L,
        stagedDataVersion = stagedDataVersion,
        skippedVersionCode = skippedVersionCode,
        autoChannelWanted = autoChannelWanted,
        nowMillis = nowMillis,
    )

    private fun UpdateChannelReadout.Readout.allPlayerText(): String = playerLines.joinToString("\n")

    // ------------------------------------------------------------------
    // 病 ①：摘要分行讲人话，取证数字收进展开层
    // ------------------------------------------------------------------

    @Test
    fun `摘要按三件事分行讲人话`() {
        val base = 1_730_000_000_000L
        val readout = UpdateChannelReadout.of(
            copy,
            snapshot(
                lastSuccessMillis = base,
                lastAttemptMillis = base,
                outcome = UpdateChannelReadout.OUTCOME_OK,
                nowMillis = base + 137L * 60_000L,
            )
        )
        val text = readout.allPlayerText()
        assertTrue("要讲能不能查：$text", text.contains("更新地址有 2 条"))
        assertTrue(
            "要讲上次查到什么：$text",
            text.contains("最近一次检查：2 小时前") && text.contains("检查已完成"),
        )
        assertFalse("官方口吻不许再留口语「连上了」：$text", text.contains("连上了"))
        assertTrue("要讲有没有东西等着应用：$text", text.contains("没有等着装的数据包"))
        assertEquals("三件事就是三行，不该再回成一坨", 3, readout.playerLines.size)
    }

    @Test
    fun `地址原文与裸版本号只许出现在展开层`() {
        val readout = UpdateChannelReadout.of(copy, snapshot())
        val text = readout.allPlayerText()
        assertFalse("玩家面不许甩裸 URL：$text", text.contains("http"))
        assertFalse("玩家面不许甩裸 URL：$text", text.contains("eratw-android.asia"))
        assertFalse("玩家面不许甩裸的本机版本号：$text", text.contains("12"))
        assertTrue("首选地址要留在取证层", readout.techLines.any { it.contains("https://eratw-android.asia/updates/") })
        assertTrue("本机版本读数要留在取证层", readout.techLines.any { it.contains("整包版本 12") })
    }

    @Test
    fun `玩家面一个术语都不许出现`() {
        val banned = listOf("镜像", "清单", "摘要", "versionCode", "Range", "sha")
        val shapes = listOf(
            snapshot(),
            snapshot(mirrorCount = 0, preferredMirror = ""),
            snapshot(
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_UNREACHABLE,
                notes = listOf("a.example：连不上，没拿到新版信息"),
                nowMillis = 120_000L,
            ),
            snapshot(
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_CRASHED,
                notes = listOf("IllegalStateException: boom"),
                nowMillis = 120_000L,
            ),
            snapshot(
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_NO_MIRROR,
                nowMillis = 120_000L,
            ),
            snapshot(
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_OK,
                notes = listOf("忽略不认识的键：apk-verzion"),
                nowMillis = 120_000L,
            ),
            snapshot(stagedDataVersion = 1200L, skippedVersionCode = 9L),
        )
        shapes.forEach { s ->
            val text = UpdateChannelReadout.of(copy, s).allPlayerText()
            banned.forEach { term ->
                assertFalse("玩家面不许出现术语「$term」：$text", text.contains(term, ignoreCase = true))
            }
        }
    }

    @Test
    fun `没配地址时把责任指向出包而不是玩家网络`() {
        val readout = UpdateChannelReadout.of(copy, snapshot(mirrorCount = 0, preferredMirror = ""))
        val text = readout.allPlayerText()
        assertTrue("没配地址要指向出包侧：$text", text.contains("没有内置更新源"))
        assertTrue("要明说不是玩家的网络：$text", text.contains("不是"))
        assertFalse("没配地址时不该出现任何地址原文：$text", text.contains("http"))
    }

    @Test
    fun `下好的数据包只能说下次启动自动装上`() {
        val text = UpdateChannelReadout.of(copy, snapshot(stagedDataVersion = 1200L)).allPlayerText()
        assertTrue("要点出「下次启动时才装上」：$text", text.contains("下次启动"))
        assertFalse("没应用之前不许说已生效：$text", text.contains("已生效"))
        assertFalse("没应用之前不许说已经更新好：$text", text.contains("已经更新"))
    }

    // ------------------------------------------------------------------
    // 病 ③：检查结果的形状
    // ------------------------------------------------------------------

    @Test
    fun `四种结果形状按已有读数一次判出`() {
        assertEquals(UpdateChannelReadout.OUTCOME_NO_MIRROR, UpdateChannelReadout.outcomeOf(0, 0, false))
        assertEquals(UpdateChannelReadout.OUTCOME_UNREACHABLE, UpdateChannelReadout.outcomeOf(2, 2, false))
        assertEquals(UpdateChannelReadout.OUTCOME_OK, UpdateChannelReadout.outcomeOf(2, 0, false))
        assertEquals(UpdateChannelReadout.OUTCOME_CRASHED, UpdateChannelReadout.outcomeOf(2, 0, true))
        // 抛错优先于「没配地址」：取证要追的是那次异常，不是地址条数。
        assertEquals(UpdateChannelReadout.OUTCOME_CRASHED, UpdateChannelReadout.outcomeOf(0, 0, true))
    }

    @Test
    fun `连不上的那次不许被写成包里没配源`() {
        val text = UpdateChannelReadout.of(
            copy,
            snapshot(
                mirrorCount = 2,
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_UNREACHABLE,
                notes = listOf("a.example：连不上", "b.example：连不上"),
                nowMillis = 5L * 60_000L,
            )
        ).allPlayerText()
        assertTrue("要讲出是连不上：$text", text.contains("均无法连接"))
        assertTrue("要给下一步：网络恢复后自动重查：$text", text.contains("网络恢复后将自动重新检查"))
        assertFalse("不许退化成「包里没配源」（处理人完全不同）：$text", text.contains("没有内置更新源"))
        assertFalse("连不上不许说成检查已完成：$text", text.contains("检查已完成"))
    }

    @Test
    fun `从没查过时不许谎称查成功`() {
        val text = UpdateChannelReadout.of(copy, snapshot()).allPlayerText()
        assertTrue("要明说还没查过：$text", text.contains("尚未执行检查"))
        assertFalse(text.contains("检查已完成"))
    }

    @Test
    fun `老存档只有成功时间没有结果标记时只报时间不编结论`() {
        val base = 1_730_000_000_000L
        val text = UpdateChannelReadout.of(
            copy,
            snapshot(lastSuccessMillis = base, outcome = UpdateChannelReadout.OUTCOME_UNKNOWN, nowMillis = base + 3L * 60_000L)
        ).allPlayerText()
        assertTrue("时间照报：$text", text.contains("最近一次检查：3 分钟前"))
        assertFalse("结果没记过就不许说检查已完成：$text", text.contains("检查已完成"))
        assertTrue("要承认结果没有记录：$text", text.contains("未记录"))
    }

    @Test
    fun `距今的说法分档且不足一分钟说刚刚`() {
        val now = 1_730_000_000_000L
        val minute = 60_000L
        assertNull(UpdateChannelReadout.sinceText(copy, 0L, now))
        assertEquals("刚刚", UpdateChannelReadout.sinceText(copy, now - 30_000L, now))
        assertEquals("37 分钟前", UpdateChannelReadout.sinceText(copy, now - 37L * minute, now))
        // 137 分钟这种「分钟数大于 60」的读数换算成小时，玩家才数得清是多久以前。
        assertEquals("2 小时前", UpdateChannelReadout.sinceText(copy, now - 137L * minute, now))
        assertEquals("3 小时前", UpdateChannelReadout.sinceText(copy, now - 3L * 60L * minute, now))
        assertEquals("5 天前", UpdateChannelReadout.sinceText(copy, now - 5L * 24L * 60L * minute, now))
        // 玩家把系统时钟改到过去 ⇒ 时间戳「在未来」：报刚刚，不许报「-3 分钟前」这种鬼话。
        assertEquals("刚刚", UpdateChannelReadout.sinceText(copy, now + 10L * minute, now))
    }

    // ------------------------------------------------------------------
    // 病 ④：warnings 有消费点 + 原话的存取口径
    // ------------------------------------------------------------------

    @Test
    fun `自动检查留下的提醒进摘要但不弹`() {
        val text = UpdateChannelReadout.of(
            copy,
            snapshot(
                lastSuccessMillis = 60_000L,
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_OK,
                notes = listOf("忽略不认识的键：a", "第 3 条不合法，已丢弃：b"),
                nowMillis = 2L * 60_000L,
            )
        )
        assertTrue("摘要要报出条数：" + text.allPlayerText(), text.allPlayerText().contains("2 处内容"))
        assertTrue("原话收在展开层：" + text.techLines, text.techLines.any { it.contains("忽略不认识的键") })
    }

    @Test
    fun `零条提醒不占一句话`() {
        assertNull(UpdateUiText.warningsNote(copy, 0))
        val note = UpdateUiText.warningsNote(copy, 2)!!
        assertTrue(note.contains("2 处"))
        assertFalse("不许甩锅给玩家的网络：$note", note.contains("你的网络"))
        assertFalse("不许夸大成没事：$note", note.contains("不影响"))
    }

    @Test
    fun `手动检查两种结论都把提醒条数带上屏`() {
        assertFalse(UpdateUiText.upToDateReport(copy, 8L, 1199L).contains("没看懂"))
        val withNotes = UpdateUiText.upToDateReport(copy, 8L, 1199L, 3)
        assertTrue("已经是最新版也要看得见提醒：$withNotes", withNotes.contains("有 3 处内容"))
        val failed = UpdateUiText.checkFailedReport(copy, 2, listOf("a.example：连不上"), 1)
        assertTrue("连不上的报障也要带提醒数量：$failed", failed.contains("有 1 处内容"))
        assertTrue(failed.contains("已配置 2 条"))
    }

    @Test
    fun `原话存取往返一致并按条数与长度截断`() {
        val notes = listOf("忽略不认识的键：x", "第 3 条不合法，已丢弃：y", "  ", "坏可选组：整组忽略")
        val decoded = UpdateChannelReadout.decodeNotes(UpdateChannelReadout.encodeNotes(notes))
        assertEquals(listOf("忽略不认识的键：x", "第 3 条不合法，已丢弃：y", "坏可选组：整组忽略"), decoded)
        val many = (1..40).map { "第 $it 处提醒" }
        assertEquals(
            UpdateChannelReadout.MAX_STORED_NOTES,
            UpdateChannelReadout.decodeNotes(UpdateChannelReadout.encodeNotes(many)).size,
        )
        val long = UpdateChannelReadout.encodeNotes(listOf("啊".repeat(500)))
        assertTrue("单条要截断：${long.length}", long.length <= UpdateChannelReadout.MAX_NOTE_CHARS + 1)
    }

    @Test
    fun `截断过的原话要在取证层承认少列了几条`() {
        val readout = UpdateChannelReadout.of(
            copy,
            snapshot(
                lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_OK,
                notes = (1..UpdateChannelReadout.MAX_STORED_NOTES).map { "提醒 $it" },
                noteCount = 40,
                nowMillis = 2L * 60_000L,
            )
        )
        assertTrue(
            "数量按真实值报，不许跟着截断变小：" + readout.allPlayerText(),
            readout.allPlayerText().contains("有 40 处内容"),
        )
        assertTrue(readout.techLines.any { it.contains("其余 20 处") })
    }

    // ------------------------------------------------------------------
    // 病 ⑤：弹窗分层
    // ------------------------------------------------------------------

    @Test
    fun `弹窗正文最多三行长说明只在展开层`() {
        val note = "修了一个致命 bug。" + "补充说明".repeat(80)
        val body = UpdateUiText.offerMessageLines(copy, apkAndDataOffer(note = note), onCellular = true)
        assertTrue("正文最多三行，实际 $body", body.size <= 3)
        assertTrue("长说明不许留在正文：$body", body.none { it.contains("补充说明") })
        assertTrue("体积这类必要读数还在：$body", body.any { it.contains("743 MiB") })
        assertTrue("流量提示占最后一行：$body", body.last().contains("移动数据"))
        val detail = UpdateUiText.offerDetailLines(copy, note, emptyList())
        assertTrue("展开层要装着那句说明", detail.any { it.contains("补充说明") })
    }

    @Test
    fun `章不合的坏消息在正文第一行不许折进展开层`() {
        val offer = apkAndDataOffer(note = "很长的说明" + "字".repeat(300), blocked = "这台设备上装的是另一套签名章的包")
        val body = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertTrue("坏消息必须第一行送达：$body", body[0].contains("无法安装"))
        assertTrue(body[0].contains("0.2.34"))
        assertTrue("正文不许把坏消息一起折起来：$body", body.none { it.contains("很长的说明") })
        // 既有裁定：这一支的 job 只剩数据包（正按钮不是「知道了」），整包一票否决。
        assertEquals(UpdateDownloadKind.Assets, UpdateUiText.jobFor(copy, offer)!!.kind)
    }

    @Test
    fun `没有可更的东西时正文一个字都不给`() {
        val offer = UpdateManager.UpdateOffer(
            UpdateInfo(
                format = 1, generated = "", note = "随便一句说明", apk = null, assets = null,
                mirrors = emptyList(), warnings = emptyList(),
            ),
            apkNeeded = false, assetsNeeded = false, currentVersionCode = 9L, installedDataVersion = 0L,
        )
        assertTrue(UpdateUiText.offerMessageLines(copy, offer, onCellular = true).isEmpty())
    }

    @Test
    fun `展开入口只在有内容时给得出并把提醒数量写在标签上`() {
        assertTrue(UpdateUiText.offerDetailLines(copy, "", emptyList()).isEmpty())
        assertEquals("查看说明", UpdateUiText.offerExpandLabel(copy, 0))
        assertTrue(UpdateUiText.offerExpandLabel(copy, 2).contains("2 条提示"))
        assertEquals("看技术细节", UpdateUiText.techDetailLabel(copy, false))
        assertEquals("收起技术细节", UpdateUiText.techDetailLabel(copy, true))
    }

    // ------------------------------------------------------------------
    // 病 ⑥：跳过提醒的话术单源
    // ------------------------------------------------------------------

    @Test
    fun `跳过的两处话术引用同一条恢复口句子`() {
        val toast = UpdateUiText.skippedToast(copy, 9L)
        val note = UpdateUiText.skippedVersionNote(copy, 9L)!!
        assertTrue(toast.contains(copy.reminderRestorePhrase))
        assertTrue(note.contains(copy.reminderRestorePhrase))
        assertFalse("「下方」这种只在一处成立的说法不许留下：$note", note.contains("下方"))
        assertNull(UpdateUiText.skippedVersionNote(copy, 0L))
    }

    @Test
    fun `源码面不许出现第三种恢复提醒的说法`() {
        val canonical = "可在设置页「启动」里恢复提醒"
        val offenders = updatePlayerTextSources().flatMap { file ->
            playerStringLiterals(file).filter { it.contains("可在") && it.contains("恢复") && !it.contains(canonical) }
                .map { "${file.name}: $it" }
        }
        assertTrue("恢复口的说法必须单源（违例：$offenders）", offenders.isEmpty())
        // EP4 U3a 之后这句话的**词源**在三语表的中文那一组（契约 §1 硬规矩 2：逐字搬运），
        // 单源这条纪律不变，只是从「UpdateUiText 的一条常量」变成「UpdateCopy.ZH 的一个键」：
        // 表里只许出现一次，判读层两处引用都从 copy 取。
        val table = File(appMainSourceDir(), "UpdateCopy.kt").readText()
        assertTrue("恢复话术要收进三语表的中文那一组", table.contains("reminderRestorePhrase = \"想改主意$canonical\""))
        assertEquals("词源只有一处（不许在别的语言组里复制中文原句）", 1, countOccurrences(table, "想改主意"))
        val text = File(appMainSourceDir(), "UpdateUiText.kt").readText()
        assertEquals("两处引用（弹窗 Toast 与设置页小字）都从 copy 取", 2, countOccurrences(text, "copy.reminderRestorePhrase"))
    }

    @Test
    fun `重放入口不许再留成一个无人调用的公开口`() {
        // U1 把 `lastFailedDownload` / `retryLastFailed` 开出来时全仓**零调用点**（契约 §2 U1-④ 的同屏
        // 入口当时只落在服务与文案层，落屏由组长接线补齐）。这条守卫钉住「入口真的有调用方」，
        // 也钉住正文取自纯层 —— 同一句话在壳里再写一遍是这套 UI 最早那批病的形状。
        val shell = File(appMainSourceDir(), "SettingsActivity.kt").readText()
        assertTrue("设置页要读那张失败的单子", shell.contains("lastFailedDownload("))
        assertTrue("设置页那颗按钮点了要真能重下", shell.contains("retryLastFailed("))
        assertTrue("正文只许引用 UpdateProgressText.settingsRetryLabel", shell.contains("settingsRetryLabel("))
        // EP4 U3b 之后：设置页往文案层传的是**哪一件**（kind），不是成单那一刻的名字。
        // `DownloadJob.label` 里钉着下单那一刻的语言，玩家在设置页中途换语言就会把旧词带进这一行。
        val shellCode = File(appMainSourceDir(), "SettingsActivity.kt").readLines().rejectCommentLines()
        // L5 P2：入口必须读**盘上那份**失败单（带 Context 的重载）——无参重载只读进程内内存，
        // 进程被杀后按钮永远不出现，落盘等于白存。
        assertTrue(
            "设置页要把失败的**哪一件**交给文案层（且必须读盘上那份）",
            shellCode.any { it.contains("lastFailedDownload(this)?.kind") },
        )
        // 「按当前语言现取名字」是 U3b 指定的正路（新增的下载进度条表头也走它），不是回读单子上的旧标签：
        // 只把这一处点名放行，其余任何 `.label` 读取仍旧一律报红。
        assertTrue("进度表头要按当前语言现取名字", shellCode.any { it.contains("UpdateProgressText.labelFor(") })
        // `MaterialSource.label` 是素材来源显示名（BGM 地点名/文件夹名），不是下载单
        // `DownloadJob.label`（成单那一刻的本地化文案）。这里只点名放行这一种，其余
        // `.label` 读取（尤其 DownloadJob.label）仍旧一律报红。
        val staleLabelReads = shellCode.filter {
            it.contains(".label") &&
                !it.contains("UpdateProgressText.labelFor(") &&
                !it.contains("material.label")
        }
        assertTrue(
            "设置页不许再回头读单子上那个旧语言的标签（label 只在成单那一刻有语言意义）：" +
                staleLabelReads,
            staleLabelReads.isEmpty(),
        )
        // 用同一个取字面量的口径（逐行、剥注释）来判「壳里不许另拼一句」，
        // 整文件正则会在两个不相干的引号之间跨行匹配，把注释与别处的文案也算进来。
        val shellLiterals = playerStringLiterals(File(appMainSourceDir(), "SettingsActivity.kt"))
        assertTrue(
            "设置页不许自己另拼一句「接着下」的话术（应引用纯层）：$shellLiterals",
            shellLiterals.none { it.contains("接着下") },
        )
    }

    // ------------------------------------------------------------------
    // 病 ②：进行中态与单飞闸
    // ------------------------------------------------------------------

    @Test
    fun `按钮进行中态只在原词条后加省略号不另造只有中文的句子`() {
        assertEquals("立即检查更新", UpdateUiText.checkButtonLabel("立即检查更新", false))
        assertEquals("立即检查更新…", UpdateUiText.checkButtonLabel("立即检查更新", true))
        // 三语面：基础词条由调用方传入，英/日玩家看到的也是自己的语言 + 省略号。
        assertEquals("Check for updates now…", UpdateUiText.checkButtonLabel("Check for updates now", true))
    }

    @Test
    fun `重复点击时给的那句话要说清只有一路在跑`() {
        val report = UpdateUiText.checkBusyReport(copy)
        assertTrue(report.contains(copy.checkingLabel))
        assertTrue("要说「连点不会更快」：$report", report.contains("连点不会更快"))
    }

    @Test
    fun `手动检查的三类结果都还在上屏并且每个出口都收回进行中态`() {
        // 壳层跑不进纯 JVM，这里钉源码面：三条结果分支（弹窗 / 连不上报障 / 已最新版）与抛错分支都在，
        // 并且结果与抛错两条出口各收回一次进行中态 —— 少一条就是「按钮永远卡在检查中」的新虫。
        val shell = File(appMainSourceDir(), "UpdateUi.kt").readText()
        val manual = shell.substringAfter("fun checkNowInteractive").substringBefore("private fun runCheck")
        assertTrue(manual.contains("showOfferDialog(activity, offer, result.warnings)"))
        assertTrue(manual.contains("checkFailedReport(copy, addressCount"))
        assertTrue(manual.contains("upToDateReport(copy, current, data"))
        assertTrue(manual.contains("checkCrashedReport(copy, error)"))
        assertEquals("结果与抛错两条出口各收回一次进行中态", 2, Regex("onSettled\\(\\)").findAll(manual).count())
        assertTrue("拿不到闸要给一句话而不是静默吞掉点击", manual.contains("checkBusyReport("))
        val run = shell.substringAfter("private fun runCheck").substringBefore("fun channelReadout")
        assertEquals("单飞闸只有一处放闸，且放在结果上屏之前", 1, Regex("checking\\.set\\(false\\)").findAll(run).count())
    }

    // ------------------------------------------------------------------
    // 既有语义不许回退（契约 §3 三面里的另两面）
    // ------------------------------------------------------------------

    @Test
    fun `纯数据更新没有可钉的整包版本不给以后都不`() {
        val info = UpdateInfo(
            format = 1, generated = "", note = "", apk = null,
            assets = AssetsChannel(1200, artifact("eratw-data-1200.zip", 680L * mib)),
            mirrors = emptyList(), warnings = emptyList(),
        )
        val offer = UpdateManager.UpdateOffer(
            info, apkNeeded = false, assetsNeeded = true, currentVersionCode = 8L, installedDataVersion = 1199L,
        )
        assertEquals(-1L, UpdateUiText.skipCodeForReminder(offer))
        assertEquals(UpdateDownloadKind.Assets, UpdateUiText.jobFor(copy, offer)!!.kind)
    }

    @Test
    fun `章不合且没有数据可更时正按钮只能退化成知道了`() {
        val info = UpdateInfo(
            format = 1, generated = "", note = "",
            apk = ApkChannel(9, "0.2.34", artifact("eratw-0.2.34.apk", 743L * mib)),
            assets = null, mirrors = emptyList(), warnings = emptyList(),
        )
        val offer = UpdateManager.UpdateOffer(
            info, apkNeeded = true, assetsNeeded = false, currentVersionCode = 8L, installedDataVersion = 0L,
            installBlockedReason = "另一套签名章",
        )
        assertNull("成不了任何单 ⇒ 弹窗给「知道了」而不是「下载」", UpdateUiText.jobFor(copy, offer))
        assertTrue(
            "但坏消息本身要在正文里：" + UpdateUiText.offerMessageLines(copy, offer, false),
            UpdateUiText.offerMessageLines(copy, offer, false).any { it.contains("无法安装") },
        )
    }

    // ------------------------------------------------------------------
    // EP4 U3a：三语化（同结构 + 防扩散守卫）
    // ------------------------------------------------------------------

    /**
     * 三语表的全部键（逐条点名，不用反射 —— 键改名或删键时这个文件直接编不过，
     * 这正是想要的效果：表与用例同批改动，不许悄悄留一条没人验的语言）。
     */
    private val copyKeys: List<Pair<String, kotlin.reflect.KProperty1<UpdateCopy, String>>> = listOf(
        "apkLabel" to UpdateCopy::apkLabel,
        "assetsLabel" to UpdateCopy::assetsLabel,
        "justNow" to UpdateCopy::justNow,
        "minutesAgoFormat" to UpdateCopy::minutesAgoFormat,
        "hoursAgoFormat" to UpdateCopy::hoursAgoFormat,
        "daysAgoFormat" to UpdateCopy::daysAgoFormat,
        "problemSeparator" to UpdateCopy::problemSeparator,
        "checkingLabel" to UpdateCopy::checkingLabel,
        "reminderRestorePhrase" to UpdateCopy::reminderRestorePhrase,
        "cellularConfirmBodyFormat" to UpdateCopy::cellularConfirmBodyFormat,
        "offerApkHeadFormat" to UpdateCopy::offerApkHeadFormat,
        "offerApkSizeLineFormat" to UpdateCopy::offerApkSizeLineFormat,
        "offerApkBlockedLineFormat" to UpdateCopy::offerApkBlockedLineFormat,
        "offerDeltaMarker" to UpdateCopy::offerDeltaMarker,
        "offerAssetsLineFormat" to UpdateCopy::offerAssetsLineFormat,
        "offerCellularLine" to UpdateCopy::offerCellularLine,
        "offerNoteLineFormat" to UpdateCopy::offerNoteLineFormat,
        "offerExpandLabel" to UpdateCopy::offerExpandLabel,
        "offerExpandCountFormat" to UpdateCopy::offerExpandCountFormat,
        "warningsNoteFormat" to UpdateCopy::warningsNoteFormat,
        "skippedToastFormat" to UpdateCopy::skippedToastFormat,
        "skippedNoteFormat" to UpdateCopy::skippedNoteFormat,
        "checkBusyFormat" to UpdateCopy::checkBusyFormat,
        "notificationRationaleFormat" to UpdateCopy::notificationRationaleFormat,
        "noBuiltInMirrorLine" to UpdateCopy::noBuiltInMirrorLine,
        "allMirrorsFailedPhrase" to UpdateCopy::allMirrorsFailedPhrase,
        "checkFailedFormat" to UpdateCopy::checkFailedFormat,
        "checkCrashedFormat" to UpdateCopy::checkCrashedFormat,
        "upToDateFormat" to UpdateCopy::upToDateFormat,
        "techExpandLabel" to UpdateCopy::techExpandLabel,
        "techCollapseLabel" to UpdateCopy::techCollapseLabel,
        "canCheckFormat" to UpdateCopy::canCheckFormat,
        "canCheckAutoTail" to UpdateCopy::canCheckAutoTail,
        "canCheckManualTail" to UpdateCopy::canCheckManualTail,
        "neverCheckedLine" to UpdateCopy::neverCheckedLine,
        "lastCheckHeadFormat" to UpdateCopy::lastCheckHeadFormat,
        "checkOkLineFormat" to UpdateCopy::checkOkLineFormat,
        "checkOkNotesLineFormat" to UpdateCopy::checkOkNotesLineFormat,
        "checkUnreachableLineFormat" to UpdateCopy::checkUnreachableLineFormat,
        "checkNoMirrorLineFormat" to UpdateCopy::checkNoMirrorLineFormat,
        "checkCrashedLineFormat" to UpdateCopy::checkCrashedLineFormat,
        "checkUnknownLineFormat" to UpdateCopy::checkUnknownLineFormat,
        "stagedYesLineFormat" to UpdateCopy::stagedYesLineFormat,
        "stagedNoLine" to UpdateCopy::stagedNoLine,
        "techAddressNoneLine" to UpdateCopy::techAddressNoneLine,
        "techAddressLineFormat" to UpdateCopy::techAddressLineFormat,
        "techVersionsLineFormat" to UpdateCopy::techVersionsLineFormat,
        "techLastSuccessLineFormat" to UpdateCopy::techLastSuccessLineFormat,
        "techNeverSucceededLine" to UpdateCopy::techNeverSucceededLine,
        "techAttemptLineFormat" to UpdateCopy::techAttemptLineFormat,
        "techOutcomeLineFormat" to UpdateCopy::techOutcomeLineFormat,
        "techDroppedNotesLineFormat" to UpdateCopy::techDroppedNotesLineFormat,
        "dialogOfferTitle" to UpdateCopy::dialogOfferTitle,
        "dialogDeclineButton" to UpdateCopy::dialogDeclineButton,
        "dialogNeverButton" to UpdateCopy::dialogNeverButton,
        "dialogDownloadButton" to UpdateCopy::dialogDownloadButton,
        "dialogAckButton" to UpdateCopy::dialogAckButton,
        "cellularDialogTitle" to UpdateCopy::cellularDialogTitle,
        "cellularDeclineButton" to UpdateCopy::cellularDeclineButton,
        "cellularProceedButton" to UpdateCopy::cellularProceedButton,
        "notificationsTitle" to UpdateCopy::notificationsTitle,
        "notificationsDeclineButton" to UpdateCopy::notificationsDeclineButton,
        "notificationsGrantButton" to UpdateCopy::notificationsGrantButton,
        "downloadStartFailedFormat" to UpdateCopy::downloadStartFailedFormat,
        // EP4 U3b（通知与下载层）追加的 54 条：三语形状与「英文零汉字」两条闸必须一起覆盖它们，
        // 否则新键就落在闸外面（契约 §3.3）。
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
        // U5（仅 Wi-Fi 下载 + 设置页内嵌进度）追加的 3 条：三语同加，形状闸一起跟平。
        "wifiOnlyCellularTitle" to UpdateCopy::wifiOnlyCellularTitle,
        "wifiOnlyCellularBody" to UpdateCopy::wifiOnlyCellularBody,
        "downloadingLineFormat" to UpdateCopy::downloadingLineFormat,
        // U7b（2026-11-05 安装回执内联文案入表，CONTRACT-UPD2 §2 U7）追加的 17 条。
        "installDoneTitle" to UpdateCopy::installDoneTitle,
        "installDoneLine" to UpdateCopy::installDoneLine,
        "installCancelledTitle" to UpdateCopy::installCancelledTitle,
        "installCancelledLine" to UpdateCopy::installCancelledLine,
        "installFailedTitle" to UpdateCopy::installFailedTitle,
        "installFailedBodyFormat" to UpdateCopy::installFailedBodyFormat,
        "installSessionOpenFailedFormat" to UpdateCopy::installSessionOpenFailedFormat,
        "installConfirmOpenFailedFormat" to UpdateCopy::installConfirmOpenFailedFormat,
        "installPathInvalidLine" to UpdateCopy::installPathInvalidLine,
        "installBlockedLine" to UpdateCopy::installBlockedLine,
        "installConflictLine" to UpdateCopy::installConflictLine,
        "installIncompatibleLine" to UpdateCopy::installIncompatibleLine,
        "installInvalidLine" to UpdateCopy::installInvalidLine,
        "installStorageLine" to UpdateCopy::installStorageLine,
        "installTimeoutLine" to UpdateCopy::installTimeoutLine,
        "installGenericFailedFormat" to UpdateCopy::installGenericFailedFormat,
        "installSystemMessageFormat" to UpdateCopy::installSystemMessageFormat,
        // U11（2026-11-05 数据通道档位读数入表，CONTRACT-UPD3-3LANE §2 U11）追加的 8 条。
        "techDataArtifactLineFormat" to UpdateCopy::techDataArtifactLineFormat,
        "dataKindBlockDelta" to UpdateCopy::dataKindBlockDelta,
        "dataKindFileDelta" to UpdateCopy::dataKindFileDelta,
        "dataKindFull" to UpdateCopy::dataKindFull,
        "dataKindNone" to UpdateCopy::dataKindNone,
        "dataDiskTrusted" to UpdateCopy::dataDiskTrusted,
        "dataDiskUntrusted" to UpdateCopy::dataDiskUntrusted,
        "dataItemsNone" to UpdateCopy::dataItemsNone,
    )

    /** 只有标点、不算「词」的键：三语同形不算抄中文。 */
    private val punctuationOnlyKeys = setOf("problemSeparator")

    private val threeCopies = listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA)

    @Test
    fun `取语言只有一条路：表按设置页那三档各取到自己那组`() {
        assertSame(UpdateCopy.ZH, UpdateCopy.of(SettingsLanguage.ZH))
        assertSame(UpdateCopy.EN, UpdateCopy.of(SettingsLanguage.EN))
        assertSame(UpdateCopy.JA, UpdateCopy.of(SettingsLanguage.JA))
        // 枚举只有三档；出现第四档时上面三个分支会编不过，这条钉住「更新侧不许新增语言判定」。
        assertEquals(3, SettingsLanguage.entries.size)
    }

    @Test
    fun `三语表每条都非空且三语互不相同（英文不许抄中文）`() {
        copyKeys.forEach { (name, property) ->
            val zh = property(UpdateCopy.ZH)
            val en = property(UpdateCopy.EN)
            val ja = property(UpdateCopy.JA)
            assertTrue("$name 中文为空", zh.isNotBlank())
            assertTrue("$name 英文为空", en.isNotBlank())
            assertTrue("$name 日文为空", ja.isNotBlank())
            if (name in punctuationOnlyKeys) return@forEach
            assertTrue("$name 英文与中文逐字相同（没本地化）：$en", en != zh)
            assertTrue("$name 日文与中文逐字相同（没本地化）：$ja", ja != zh)
            assertTrue("$name 英日写成同一句：$en", en != ja)
        }
        // 121 → 138：U7b（2026-11-05）把安装回执的 17 条内联中文收进表并逐条点名进本清单。
        // 138 → 146：U11（2026-11-05）把数据通道档位读数的 8 条新键收进表并逐条点名进本清单。
        // 在册漂移（**不是本清单漏抄，也不是 U11 的活**）：表里另有 5 条键此前收口时没进本清单——
        // dialogOfferAssetsTitle / offerAssetsOnlyExplainerLine（7db4d19f）、offerForceUpdateLine（U1）、
        // systemPausedNotifyTitle / systemPausedLine（U2）。表实有 String 键 151 条（lang 不计），
        // 本清单钉 146 = 151 - 5；补不补那 5 条由组长拍板（补了只抬本计数，别处断言不消费它们）。
        assertEquals("三语表的键数（新增键要进 copyKeys 清单）", 146, copyKeys.size)
    }

    @Test
    fun `英文那一组一个汉字都不许混进来`() {
        val han = Regex("[\\u4e00-\\u9fff]")
        copyKeys.forEach { (name, property) ->
            val en = property(UpdateCopy.EN)
            assertFalse("英文键 $name 里混进了中文字符：$en", han.containsMatchIn(en))
        }
    }

    @Test
    fun `三语形状一致：同一张单在三种语言下弹窗正文的行数一样`() {
        val offers = listOf(apkAndDataOffer(note = "修了一个致命 bug"), apkAndDataOffer(blocked = "另一套签名章的包"))
        offers.forEach { offer ->
            listOf(false, true).forEach { cellular ->
                val expected = UpdateUiText.offerMessageLines(UpdateCopy.ZH, offer, cellular)
                threeCopies.forEach { c ->
                    val lines = UpdateUiText.offerMessageLines(c, offer, cellular)
                    assertEquals("${c.lang} 在正文里改了行数", expected.size, lines.size)
                    assertTrue("${c.lang} 的正文有空行：$lines", lines.all { it.isNotBlank() })
                    // 体积这类读数与语言无关，三语都得带着（不许因为换词把数字弄丢）。
                    expected.indices.forEach { i ->
                        assertTrue(
                            "${c.lang} 第 $i 行丢了体积读数",
                            !expected[i].contains("743 MiB") || lines[i].contains("743 MiB"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `三语形状一致：通道现状三行与展开层在三种语言下同形`() {
        val shapes = listOf(
            snapshot(),
            snapshot(mirrorCount = 0, preferredMirror = ""),
            snapshot(
                lastAttemptMillis = 60_000L, outcome = UpdateChannelReadout.OUTCOME_UNREACHABLE,
                notes = listOf("a.example：连不上"), nowMillis = 120_000L,
            ),
            snapshot(stagedDataVersion = 1200L, skippedVersionCode = 9L),
            snapshot(
                lastSuccessMillis = 60_000L, lastAttemptMillis = 60_000L,
                outcome = UpdateChannelReadout.OUTCOME_OK, notes = listOf("忽略不认识的键：a"), nowMillis = 2L * 60_000L,
            ),
        )
        shapes.forEachIndexed { index, s ->
            val zh = UpdateChannelReadout.of(UpdateCopy.ZH, s)
            threeCopies.forEach { c ->
                val readout = UpdateChannelReadout.of(c, s)
                assertEquals("$index 号形状在 ${c.lang} 下玩家面行数变了", zh.playerLines.size, readout.playerLines.size)
                assertEquals("$index 号形状在 ${c.lang} 下展开层行数变了", zh.techLines.size, readout.techLines.size)
                assertTrue("$index 号形状在 ${c.lang} 下有空行", readout.playerLines.all { it.isNotBlank() })
                assertEquals("$index 号形状在 ${c.lang} 下展开入口变了", zh.hasTechDetail, readout.hasTechDetail)
            }
        }
    }

    @Test
    fun `三语玩家面都不许出现术语`() {
        // 中文侧沿既有口径（镜像 / 清单 / 摘要 / versionCode / Range / sha）；英日同口径另加英文原词。
        val banned = listOf("镜像", "清单", "摘要", "versionCode", "Range", "sha", "mirror", "manifest", "digest")
        val shapes = listOf(
            snapshot(),
            snapshot(mirrorCount = 0, preferredMirror = ""),
            snapshot(mirrorCount = 2, autoChannelWanted = false),
            snapshot(
                lastAttemptMillis = 60_000L, outcome = UpdateChannelReadout.OUTCOME_UNREACHABLE,
                notes = listOf("a.example：连不上"), nowMillis = 120_000L,
            ),
            snapshot(stagedDataVersion = 1200L, skippedVersionCode = 9L),
        )
        threeCopies.forEach { c ->
            shapes.forEach { s ->
                val text = UpdateChannelReadout.of(c, s).allPlayerText()
                banned.forEach { term ->
                    assertFalse("${c.lang} 的玩家面出现术语「$term」：$text", text.contains(term, ignoreCase = true))
                }
            }
            val body = UpdateUiText.offerMessageLines(
                c, apkAndDataOffer(blocked = "另一套签名章的包"), onCellular = true,
            ).joinToString("\n")
            banned.forEach { term ->
                assertFalse("${c.lang} 的弹窗正文出现术语「$term」：$body", body.contains(term, ignoreCase = true))
            }
        }
    }

    @Test
    fun `手动检查那颗按钮的名字在三语里与设置页同词`() {
        // 通道现状第一行的尾巴要点出「只有手动点某颗按钮才会查」。那颗按钮的名字归设置页三语表
        // （SettingsStrings，本波不扩），这里钉两处用词一致：按钮改名时这边不许留着旧名。
        threeCopies.forEach { c ->
            val line = UpdateChannelReadout.of(c, snapshot(mirrorCount = 2, autoChannelWanted = false)).playerLines.first()
            val button = SettingsStrings.of(c.lang).checkUpdateButton
            assertTrue("${c.lang} 的现状行没点出按钮名（应为「$button」）：$line", line.contains(button))
        }
    }

    @Test
    fun `判读层不许再返回硬编码中文（玩家面句子的唯一词源是三语表）`() {
        // 只扫玩家面判读层：注释与 KDoc 里的中文是给改代码的人看的（按行剥掉再判），
        // 剩下的上屏字面量里一个汉字都不该有。壳层单独按「是不是 logcat 那一行」分开判：
        // Log 的中文是内部诊断面，弹窗标题与按钮才是玩家面。
        val han = Regex("[\\u4e00-\\u9fff]")
        listOf("UpdateUiText.kt", "UpdateChannelReadout.kt").forEach { name ->
            val offenders = playerStringLiterals(File(appMainSourceDir(), name)).filter { han.containsMatchIn(it) }
            assertTrue("$name 里玩家面函数又写了硬编码中文（违例：$offenders）", offenders.isEmpty())
        }
        val shell = File(appMainSourceDir(), "UpdateUi.kt").readLines().rejectCommentLines()
            .filterNot { it.contains("Log.") }
            .flatMap { quotedLiterals(it) }
            .filter { han.containsMatchIn(it) }
        assertTrue("更新壳层的弹窗与按钮话术必须来自三语表（违例：$shell）", shell.isEmpty())
        // EP4 U3b 追加的两份（下载过程判读层 + 前台服务壳层）：同样只搬玩家面。
        // 这两份里有**两类不是玩家面**的汉字，必须逐条点名放行（一刀切会把取证面钉死）：
        // 1) logcat 与只进日志的异常消息；2) 拿去匹配上游中文原话的锚点（搬进表里匹配就失效）。
        // 豁免面本身的数量由 UpdateNotifyCopyTest 钉住，不许在这里悄悄变宽。
        listOf("UpdateProgressText.kt", "UpdateDownloadService.kt").forEach { name ->
            val offenders = File(appMainSourceDir(), name).readLines().rejectCommentLines()
                .filterNot { it.contains("Log.") || it.contains("throw InterruptedException(") || it.contains("SPACE_PROBLEM_HINT") }
                .flatMap { quotedLiterals(it) }
                .filter { han.containsMatchIn(it) }
            assertTrue("$name 里玩家面句子又写了硬编码中文（违例：$offenders）", offenders.isEmpty())
        }
    }

    @Test
    fun `更新侧取语言只有一条路`() {
        val calls = File(appMainSourceDir(), ".").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .flatMap { file ->
                file.readLines().rejectCommentLines().filter { it.contains("UpdateCopy.of(") }
                    .map { "${file.name}: ${it.trim()}" }
            }
            .toList()
        assertTrue("更新面一处都没取语言（三语表根本没接上屏）", calls.isNotEmpty())
        calls.forEach { call ->
            assertTrue("取语言只许从 UiPrefs.settingsLanguage 走（违例：$call）", call.contains("UiPrefs.settingsLanguage("))
        }
        assertEquals("UpdateCopy.of 的调用点只许壳层入口与设置页各一处（要加第三处先报红）", 2, calls.size)
    }

    // ------------------------------------------------------------------
    // 源码面工具（与 PatchExtensionDuplicationGuardTest 同口径：找不到目录就抛，不静默跳过）
    // ------------------------------------------------------------------

    /** 玩家面更新文案可能出现的文件：判读层两份 + 三语表 + 壳层两份 + 设置页与设置页三语表。 */
    private fun updatePlayerTextSources(): List<File> =
        listOf(
            "UpdateUiText.kt", "UpdateChannelReadout.kt", "UpdateCopy.kt", "UpdateUi.kt",
            "UpdateProgressText.kt", "UpdateDownloadService.kt", "SettingsActivity.kt", "SettingsStrings.kt",
        ).map { File(appMainSourceDir(), it) }
            .filter { it.isFile }
            .also { assertTrue("更新面源码一个都没找到（工作目录 = ${File(".").absolutePath}）", it.isNotEmpty()) }

    private fun appMainSourceDir(): File {
        val fromModule = File("src/main/kotlin/com/eratw/port/android")
        if (fromModule.isDirectory) return fromModule
        val fromRepoRoot = File("app/src/main/kotlin/com/eratw/port/android")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        throw IllegalStateException(
            "找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）——守卫用例无法检查，视为红而不是跳过",
        )
    }

    /**
     * 只取**上屏字符串**：先丢掉注释行（以斜杠斜杠、斜杠星、星号开头的行），再取行内成对双引号片段。
     * KDoc 里描述旧 bug 的原话不该被当成新话术，所以必须先剥注释再匹配。
     */
    private fun playerStringLiterals(file: File): List<String> =
        file.readLines().rejectCommentLines().flatMap { quotedLiterals(it) }

    /** 一行里成对双引号包住的片段（带转义的路径不算字面量，与既有口径一致）。 */
    private fun quotedLiterals(line: String): List<String> =
        Regex("\"([^\"\\\\]*)\"").findAll(line).map { it.groupValues[1] }.toList()

    private fun List<String>.rejectCommentLines(): List<String> = filterNot { raw ->
        val t = raw.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*/") || t.startsWith("*")
    }
}

    /** 数一个字面串出现几次：不借正则，转义曾在文件里被吃掉过一次，这个用例本身就是活证据。 */
    private fun countOccurrences(haystack: String, needle: String): Int {
        var hits = 0
        var at = haystack.indexOf(needle)
        while (at >= 0) {
            hits++
            at = haystack.indexOf(needle, at + needle.length)
        }
        return hits
    }
