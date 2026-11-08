package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U5 upd-settings 定向单测（P2-10 仅 Wi-Fi 下载 / P2-12 设置页内嵌进度 / P1-6 回前台补查）。
 *
 * 只钉三样东西：两条纯判定的**矩阵**、进度读数与判读层的**形状**、以及四处**接线**的源码面
 * （壳层碰一下 Looper 就炸，接线只能按源码面判）。玩家面句子一律从 [UpdateCopy] 取，
 * 本文件不自己造中文句子去比原文。
 */
class UpdateSettingsLaneTest {

    private val copies = listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA)
    private val han = Regex("[一-鿿]")
    private val slot = Regex("[{](\\d+)[}]")
    private val mib = 1024L * 1024L

    // ------------------------------------------------------------------
    // P2-10：仅 Wi-Fi 下载的纯判定矩阵
    // ------------------------------------------------------------------

    @Test
    fun `仅 Wi-Fi 开关与网络类型的四格矩阵`() {
        // 开关关（缺省）⇒ 两种网络都 false：既有蜂窝二次确认路径不偷改。
        assertFalse(UpdateUiText.wifiOnlyBlocksCellular(wifiOnlyEnabled = false, isUnmetered = true))
        assertFalse(UpdateUiText.wifiOnlyBlocksCellular(wifiOnlyEnabled = false, isUnmetered = false))
        // 开关开 + Wi-Fi（非计量）⇒ 放行，照旧走正常下载。
        assertFalse(UpdateUiText.wifiOnlyBlocksCellular(wifiOnlyEnabled = true, isUnmetered = true))
        // 开关开 + 蜂窝 ⇒ 只有这一格挡下。
        assertTrue(UpdateUiText.wifiOnlyBlocksCellular(wifiOnlyEnabled = true, isUnmetered = false))
        // 与 core 的口径同源：非计量就放行，本函数只在「开关开」时叠加一层。
        assertEquals(
            com.eratw.port.core.update.UpdatePolicy.autoDownloadAllowed(true),
            !UpdateUiText.wifiOnlyBlocksCellular(wifiOnlyEnabled = true, isUnmetered = true),
        )
    }

    @Test
    fun `已设置为仅 Wi-Fi 的说明里没有仍要下载那颗绕行按钮`() {
        val shell = File(appMainSourceDir(), "UpdateUi.kt").readLines().rejectCommentLines().joinToString("\n")
        val branch = shell.substringAfter("wifiOnlyBlocksCellular(")
            .substringBefore("if (UpdateUiText.cellularConfirmRequired(unmetered))")
        assertTrue("要给出「已设置为仅 Wi-Fi」的说明标题", branch.contains("copy.wifiOnlyCellularTitle"))
        assertTrue("要给出说明正文", branch.contains("copy.wifiOnlyCellularBody"))
        assertTrue("只给一颗知道了", branch.contains("copy.dialogAckButton"))
        assertFalse("这一支不许把仍要下载的绕行按钮接回来：$branch", branch.contains("copy.cellularProceedButton"))
        // 既有二次确认路径仍在它后面，一个字没被吃掉。
        assertTrue(shell.contains("cellularConfirmRequired(unmetered)"))
        assertTrue(shell.contains("copy.cellularProceedButton)"))
    }

    @Test
    fun `设置页开关三语文案都进 SettingsStrings 且缺省关`() {
        copies.forEach { copy ->
            val s = SettingsStrings.of(copy.lang)
            assertTrue("${copy.lang} 的 Wi-Fi 开关标题为空", s.wifiOnlyTitle.isNotBlank())
            assertTrue("${copy.lang} 的 Wi-Fi 开关说明为空", s.wifiOnlyHint.isNotBlank())
        }
        val titles = copies.map { SettingsStrings.of(it.lang).wifiOnlyTitle }
        assertEquals("三语标题要三句不同的话", 3, titles.distinct().size)
        // 缺省关 + 单一 prefs 面：读写只经 UiPrefs（源码面钉住，不靠真机 SharedPreferences）。
        val prefs = File(appMainSourceDir(), "UiPrefs.kt").readLines().rejectCommentLines()
        assertTrue(prefs.any { it.contains("getBoolean(KEY_WIFI_ONLY_DOWNLOAD, false)") })
        assertTrue(prefs.any { it.contains("fun wifiOnlyDownload(context: Context)") })
        assertTrue(prefs.any { it.contains("fun setWifiOnlyDownload(context: Context, enabled: Boolean)") })
        // 壳层不许再留第二处自建 SharedPreferences 的读写（那会让「恢复默认设置」漏掉这个键）。
        val shell = File(appMainSourceDir(), "UpdateUi.kt").readLines().rejectCommentLines()
        assertTrue(
            "UpdateUi 里不该再有 Wi-Fi 偏好的自建读写：$shell",
            shell.none { it.contains("WIFI_ONLY_PREF_KEY") || it.contains("update_wifi_only") },
        )
        assertTrue(shell.any { it.contains("UiPrefs.wifiOnlyDownload(activity)") })
        // 设置页真的把开关接到了 UiPrefs 上。
        val settings = File(appMainSourceDir(), "SettingsActivity.kt").readLines().rejectCommentLines()
        assertTrue(settings.any { it.contains("UiPrefs.wifiOnlyDownload(context)") })
        assertTrue(settings.any { it.contains("UiPrefs.setWifiOnlyDownload(context, enabled)") })
    }

    // ------------------------------------------------------------------
    // P1-6：回前台补查的四闸纯判定矩阵（16 格）
    // ------------------------------------------------------------------

    @Test
    fun `回前台补查只有四闸全开才补其余 15 格全部不补`() {
        var fired = 0
        for (channel in listOf(false, true)) {
            for (due in listOf(false, true)) {
                for (inFlight in listOf(false, true)) {
                    for (done in listOf(false, true)) {
                        val expect = channel && due && !inFlight && !done
                        val actual = UpdateUiText.shouldCheckOnResume(channel, due, inFlight, done)
                        assertEquals(
                            "channel=$channel due=$due inFlight=$inFlight processDone=$done",
                            expect,
                            actual,
                        )
                        if (actual) fired++
                    }
                }
            }
        }
        assertEquals("16 格里只该有一格补一发", 1, fired)
        // 四个「只差一闸」的反例逐个点名：少一个条件都不许出网。
        assertFalse(UpdateUiText.shouldCheckOnResume(false, true, false, false))
        assertFalse(UpdateUiText.shouldCheckOnResume(true, false, false, false))
        assertFalse(UpdateUiText.shouldCheckOnResume(true, true, true, false))
        assertFalse(UpdateUiText.shouldCheckOnResume(true, true, false, true))
    }

    @Test
    fun `回前台补查接在 onResume 且冷启那一发与首帧闸都还在`() {
        val main = File(appMainSourceDir(), "MainActivity.kt").readText()
        val resume = main.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue("onResume 要走 UpdateUi 的新入口", resume.contains("UpdateUi.maybeCheckOnResume("))
        val ui = File(appMainSourceDir(), "UpdateUi.kt").readText()
        assertTrue("冷启那一发原封不动", ui.contains("fun scheduleStartupCheck(activity: Activity, gameRoot: File)"))
        assertTrue("startupCheckDone 仍是那一个开关", ui.contains("private var startupCheckDone = false"))
        assertTrue("首帧 onResume 不算回前台", ui.contains("firstResumeSeen"))
        assertTrue("补查走纯判定", ui.contains("UpdateUiText.shouldCheckOnResume("))
        // 补之前先把 startupCheckDone 置真：回前台不重复弹窗、不重复出网。
        val body = ui.substringAfter("fun maybeCheckOnResume").substringBefore("fun checkNowInteractive")
        assertTrue(body.contains("startupCheckDone = true"))
        assertTrue(body.contains("showOfferDialog(activity, offer, result.warnings)"))
    }

    // ------------------------------------------------------------------
    // P2-12：进度读数形状 + 「正在下载」一行
    // ------------------------------------------------------------------

    @Test
    fun `进度读数是纯数据类且缺省不在跑`() {
        val idle = DownloadProgress.IDLE
        assertEquals(0L, idle.doneBytes)
        assertEquals(0L, idle.totalBytes)
        assertEquals(0L, idle.percent)
        assertFalse(idle.running)
        assertEquals(null, idle.kind)
        val live = DownloadProgress(300L * mib, 743L * mib, 40L, true, UpdateDownloadKind.Apk)
        assertEquals(300L * mib, live.doneBytes)
        assertEquals(743L * mib, live.totalBytes)
        assertEquals(40L, live.percent)
        assertTrue(live.running)
        assertEquals(UpdateDownloadKind.Apk, live.kind)
        // data class 的 copy 不改原值（纯只读语义）。
        assertEquals(false, live.copy(running = false).running)
    }

    @Test
    fun `读数百分比与 UpdateProgressText 的判定同口径`() {
        val probes = listOf(
            0L to 100L, 33L to 100L, 50L to 100L, 99L to 100L, 100L to 100L,
            140L to 100L, -5L to 100L, 300L * mib to 743L * mib, 1L to 3L,
        )
        probes.forEach { (done, total) ->
            val text = UpdateProgressText.progressText(UpdateCopy.ZH, "新版本", done, total)
            val shown = Regex("(\\d+)%").find(text)!!.groupValues[1].toLong()
            assertEquals("done=$done total=$total 的百分比与通知正文不合拍：$text", shown, UpdateChannelReadout.progressPercent(done, total))
        }
        // 总量拿不到：通知正文不带百分号，读数那一格也按 0（除零退化，不编数）。
        assertFalse(UpdateProgressText.progressText(UpdateCopy.ZH, "数据包", 12L * mib, 0L).contains("%"))
        assertEquals(0L, UpdateChannelReadout.progressPercent(12L * mib, 0L))
    }

    @Test
    fun `可视化进度条的填充比例只从读数算且算不出就不给比例`() {
        listOf(
            0L to 0.00f, 33L to 0.33f, 50L to 0.50f, 99L to 0.99f,
            100L to 1.00f, 140L to 1.00f, -5L to 0.00f,
        ).forEach { (done, expected) ->
            assertEquals(
                "done=$done total=100 的比例与通知正文不合拍",
                expected,
                UpdateChannelReadout.progressFraction(done, 100L)!!,
            )
        }
        assertEquals(
            "真实体积档位也要落在 0.40",
            0.40f,
            UpdateChannelReadout.progressFraction(300L * mib, 743L * mib)!!,
        )
        // 总量拿不到 / 是负数 ⇒ 不给比例（屏上改画扫描光带，不许假装 0%）。
        assertEquals("总量为 0 时不给比例", null, UpdateChannelReadout.progressFraction(12L * mib, 0L))
        assertEquals("总量为负时不给比例", null, UpdateChannelReadout.progressFraction(12L * mib, -1L))
    }

    @Test
    fun `可视化进度条贴在立即检查更新下面且词全走三语表`() {
        val settings = File(appMainSourceDir(), "SettingsActivity.kt").readLines()
            .rejectCommentLines().joinToString("\n")
        val card = settings.substringAfter("private fun LaunchCard(")
            .substringBefore("private fun DownloadProgressBar(")
        assertTrue("检查按钮得在进度条之前", card.indexOf("OutlinedButton(") < card.indexOf("DownloadProgressBar("))
        assertTrue("只有真在跑时才占版面", card.contains("if (downloadProgress.running)"))
        val bar = settings.substringAfter("private fun DownloadProgressBar(")
            .substringBefore("private fun DeterminateBar(")
        assertTrue("那一件的名字按当前语言现取", bar.contains("UpdateProgressText.labelFor("))
        assertTrue("说明文字复用通知那一套进度正文", bar.contains("UpdateProgressText.progressText("))
        assertTrue("比例只从判读层取，不在壳里另算", bar.contains("UpdateChannelReadout.progressFraction("))
        assertFalse("进度条不许自己拼中文句子：$bar", han.containsMatchIn(bar))
        // 快慢两条心跳：完整现状 5 秒（读盘），进度条只读内存活字段、在跑 250ms 一跳。
        assertTrue(settings.contains("SETTINGS_PROGRESS_REFRESH_MILLIS"))
        assertTrue(settings.contains("SETTINGS_PROGRESS_IDLE_POLL_MILLIS"))
        assertTrue(settings.contains("SETTINGS_READOUT_REFRESH_MILLIS"))
    }

    @Test
    fun `正在下载那一行只在 running 时出现且三语同形`() {
        fun snapshot(progress: DownloadProgress?) =
            UpdateChannelReadout.Snapshot(nowMillis = 60_000L, progress = progress)

        val idle = UpdateChannelReadout.of(UpdateCopy.ZH, snapshot(DownloadProgress.IDLE))
        assertEquals("没在跑时不许凭空多一行", 3, idle.playerLines.size)
        assertFalse(idle.playerLines.any { it.contains("正在下载") })

        val live = DownloadProgress(300L * mib, 743L * mib, 40L, true, UpdateDownloadKind.Apk)
        val lines = copies.map { copy ->
            val readout = UpdateChannelReadout.of(copy, snapshot(live))
            assertEquals("${copy.lang} 在跑时该补一行", 4, readout.playerLines.size)
            val line = readout.playerLines.last()
            assertTrue("${copy.lang} 丢了百分比：$line", line.contains("40%"))
            assertTrue("${copy.lang} 丢了已下读数：$line", line.contains("300 MiB"))
            assertTrue("${copy.lang} 丢了总量读数：$line", line.contains("743 MiB"))
            line
        }
        assertEquals("三语不该抄成同一句", 3, lines.distinct().size)
        // null 读数与 IDLE 同效果（老调用方不传时形状不变）。
        assertEquals(3, UpdateChannelReadout.of(UpdateCopy.ZH, snapshot(null)).playerLines.size)
    }

    @Test
    fun `进度读数暴露口与设置页刷新都接上了且不开第二条下载线程`() {
        val service = File(appMainSourceDir(), "UpdateDownloadService.kt").readLines().rejectCommentLines().joinToString("\n")
        assertTrue("服务要开只读进度口", service.contains("fun progress(): DownloadProgress"))
        assertTrue("读数取活字段", service.contains("synchronized(s.progressLock)"))
        assertTrue("实例登记要挂上", service.contains("exposedService = this"))
        assertTrue("实例摘除要成对", service.contains("exposedService = null"))
        val ui = File(appMainSourceDir(), "UpdateUi.kt").readLines().rejectCommentLines().joinToString("\n")
        assertTrue("壳层把读数并进通道现状", ui.contains("UpdateDownloadService.progress()"))
        // 判读层复用 UpdateProgressText，不在壳里另拼一套进度句。
        val readout = File(appMainSourceDir(), "UpdateChannelReadout.kt").readText()
        assertTrue(readout.contains("UpdateProgressText.progressText("))
        val settings = File(appMainSourceDir(), "SettingsActivity.kt").readLines().rejectCommentLines().joinToString("\n")
        // L5 P2（主线程 IO）：进页先用**纯判读占位**把页面立起来，真读数（含盘上 data-version.txt）
        // 在 IO 线程取；旧写法在组合期直接读盘。
        assertTrue("进页先用纯判读空快照占位", settings.contains("mutableStateOf(UpdateChannelReadout.of("))
        assertTrue("通道现状的真实读数走 IO 线程", settings.contains("withContext(Dispatchers.IO)"))
        assertTrue("前台低频刷新", settings.contains("SETTINGS_READOUT_REFRESH_MILLIS"))
        assertTrue("刷新受前台闸约束", settings.contains("if (settingsForeground)"))
        // 刷新只重读读数：不许出现另起下载 / 改通知节流的新调用。
        assertFalse("不许开第二条下载", settings.contains("UpdateDownloadService.start("))
        assertFalse("不许碰通知刷新节流", settings.contains("NOTIFY_MIN_INTERVAL_MILLIS"))
        // L5 P2-6：通知看不见时应用内要有「停止下载」入口（走服务的 ACTION_CANCEL）。
        assertTrue("设置页要接停止下载入口", settings.contains("UpdateDownloadService.cancel("))
        // L5 P1（Lead 发现）：设置页也必须转发通知权限回调，否则 pendingStart 永远挂着。
        assertTrue(
            "设置页要转发 onRequestPermissionsResult",
            settings.contains("override fun onRequestPermissionsResult(") &&
                settings.contains("UpdateUi.onPermissionResult(requestCode, grantResults)"),
        )
    }

    // ------------------------------------------------------------------
    // 三语新增键的形状（与 UpdateNotifyCopyTest / UpdateChannelReadoutTest 同口径）
    // ------------------------------------------------------------------

    @Test
    fun `U5 三条新键三语非空互不相同英文零汉字且槽位对齐`() {
        val keys = listOf(
            "wifiOnlyCellularTitle" to UpdateCopy::wifiOnlyCellularTitle,
            "wifiOnlyCellularBody" to UpdateCopy::wifiOnlyCellularBody,
            "downloadingLineFormat" to UpdateCopy::downloadingLineFormat,
        )
        keys.forEach { (name, property) ->
            val values = copies.map(property)
            assertTrue("$name 有空串", values.all { it.isNotBlank() })
            assertEquals("$name 三语撞成同一句", 3, values.distinct().size)
            assertFalse("$name 英文混进汉字：${values[1]}", han.containsMatchIn(values[1]))
            val zhSlots = slot.findAll(values[0]).map { it.value }.toList()
            assertEquals("$name EN 槽位不对齐", zhSlots, slot.findAll(values[1]).map { it.value }.toList())
            assertEquals("$name JA 槽位不对齐", zhSlots, slot.findAll(values[2]).map { it.value }.toList())
            assertEquals("$name EN 换行不对齐", values[0].count { it == '\n' }, values[1].count { it == '\n' })
            assertEquals("$name JA 换行不对齐", values[0].count { it == '\n' }, values[2].count { it == '\n' })
        }
        assertTrue("『正在下载』那一行要留一个读数槽", slot.containsMatchIn(UpdateCopy.ZH.downloadingLineFormat))
        assertTrue("中文说明要写明已设置为仅 Wi-Fi", UpdateCopy.ZH.wifiOnlyCellularTitle.contains("已设置为仅 Wi-Fi"))
    }

    // ------------------------------------------------------------------
    // 工具（与 UpdateChannelReadoutTest 同口径：找不到目录就抛，不静默跳过）
    // ------------------------------------------------------------------

    private fun appMainSourceDir(): File {
        val fromModule = File("src/main/kotlin/com/eratw/port/android")
        if (fromModule.isDirectory) return fromModule
        val fromRepoRoot = File("app/src/main/kotlin/com/eratw/port/android")
        if (fromRepoRoot.isDirectory) return fromRepoRoot
        throw IllegalStateException("找不到 app main 源码目录（工作目录 = ${File(".").absolutePath}）")
    }

    private fun List<String>.rejectCommentLines(): List<String> = filterNot { raw ->
        val t = raw.trim()
        t.startsWith("//") || t.startsWith("/*") || t.startsWith("*/") || t.startsWith("*")
    }
}
