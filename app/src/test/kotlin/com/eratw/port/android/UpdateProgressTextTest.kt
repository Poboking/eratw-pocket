package com.eratw.port.android

import com.eratw.port.core.update.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateProgressText] 的定向单测（EP4 U1：下载过程与失败处置要看得见）。
 *
 * 四组用例逐条对上契约 §2 U1 的四条病：
 * - 病 ①（速度与「约 X 后下完」）：窗口够宽才报，**报不出就必须与拆分前的正文一字不差**；
 * - 病 ②（等待与换地址不再静默）：判据只吃「多久没有新内容进来 + 整段跑了多久」，
 *   并且必须被反例钉住——慢源真的在吐字节时一句都不许多说；
 * - 病 ③（七类失败各说各话）：[UpdateDownloadPlan.FailureKind] 每个值一句「你现在能做什么」，
 *   `kind == null`（UpdateManager.kt:519 把所有候选地址试完那一支）也给得出话；
 * - 病 ④（不给通知权限也有补救面）：看不见通知时**任何一句都不许承诺「点这条通知」**。
 *
 * 三条「不许谎报」各自钉死：`notifyVisible=false`、数据包还没生效、`totalBytes<=0` 退化。
 * 术语面（契约 §0.1）也有守卫用例：处置句里不许出现「镜像 / 清单 / 摘要 / sha / Range / versionCode / HTTP」。
 * 全程不联网、不碰 Android：时钟一律由用例传进快照，没有一句判定依赖真实时间。
 *
 * EP4 U3b（通知与下载层三语化）：本文件的调用点全部改成**显式传 [UpdateCopy.ZH]** 的主签名
 * —— 中文原文的钉子因此钉在**三语表那一组**上（而不是文件末尾的兼容口）：表里中文动一个字，
 * 这里就红。三语形状、防扩散守卫与兼容口的数量钉在 `UpdateNotifyCopyTest`（同车道新建）。
 */
class UpdateProgressTextTest {

    /** 中文那一组文案：既有断言钉的就是它，逐字与 U3b 搬动前的硬编码相同（契约 §3.2）。 */
    private val zh = UpdateCopy.ZH

    /** 「约 X 后下完」那一格在三语里各长什么样：判「有没有多一句/少一句」就按这个找。 */
    private val etaMarks = mapOf(
        SettingsLanguage.ZH to "后下完",
        SettingsLanguage.EN to "to finish",
        SettingsLanguage.JA to "で完了の見込み",
    )

    /** 「约 X/秒」那一格的三语标记。 */
    private val speedMarks = mapOf(
        SettingsLanguage.ZH to "/秒",
        SettingsLanguage.EN to "per second",
        SettingsLanguage.JA to "/秒",
    )

    private val mib = 1024L * 1024L
    private val t0 = 1_700_000_000_000L

    private fun sample(doneBytes: Long, offsetMillis: Long) =
        UpdateProgressText.ProgressSample(doneBytes, t0 + offsetMillis)

    /** 造一个「正常在下」的快照：10 秒内从 100 MiB 走到 140 MiB（4 MiB/秒），总量 743 MiB。 */
    private fun streamingSnapshot(
        totalBytes: Long = 743L * mib,
        doneBytes: Long = 140L * mib,
        samples: List<UpdateProgressText.ProgressSample>,
        nowMillis: Long = t0 + 10_000L,
        peakDoneBytes: Long = doneBytes,
        silentMillis: Long = 0L,
        elapsedMillis: Long = 10_000L,
    ) = UpdateProgressText.ProgressSnapshot(
        doneBytes,
        totalBytes,
        samples,
        nowMillis,
        peakDoneBytes,
        silentMillis,
        elapsedMillis,
    )

    private val flowing = listOf(sample(100L * mib, 0L), sample(140L * mib, 10_000L))

    // ------------------------------------------------------------------
    // 病 ①：速度与「还要等多久」
    // ------------------------------------------------------------------

    @Test
    fun `窗口够宽时正文补上速度与预计下完`() {
        val text = UpdateProgressText.progressText(zh, "新版本", streamingSnapshot(samples = flowing))
        // 原有那一行一个字都不许动（三参版是拆前形状，用例同时是回归闸）。
        assertTrue(text.startsWith(UpdateProgressText.progressText(zh, "新版本", 140L * mib, 743L * mib)))
        assertTrue(text.contains("约 4 MiB/秒"))
        assertTrue(text.contains("后下完"))
        assertFalse(text.contains("0 秒后下完"))
    }

    @Test
    fun `采样点不足时退回拆前形态绝不编速度`() {
        val one = listOf(sample(100L * mib, 0L))
        val text = UpdateProgressText.progressText(zh, "新版本", streamingSnapshot(doneBytes = 100L * mib, peakDoneBytes = 100L * mib, samples = one))
        assertEquals(UpdateProgressText.progressText(zh, "新版本", 100L * mib, 743L * mib), text)
        assertFalse(text.contains("/秒"))
    }

    @Test
    fun `两点只隔两秒时不报速度`() {
        val narrow = listOf(sample(100L * mib, 0L), sample(140L * mib, 2_000L))
        val snap = streamingSnapshot(samples = narrow, nowMillis = t0 + 2_000L)
        assertNull(UpdateProgressText.speedBytesPerSecond(narrow, t0 + 2_000L))
        assertEquals(UpdateProgressText.progressText(zh, "新版本", 140L * mib, 743L * mib), UpdateProgressText.progressText(zh, "新版本", snap))
    }

    @Test
    fun `总量未声明时不报百分比也不报预计`() {
        // 「不许谎报」第三面：totalBytes<=0 只可能来自拿不到声明值的那条路，此时**还剩多少**与**还要多久**都不许出现。
        val text = UpdateProgressText.progressText(zh, "数据包", streamingSnapshot(totalBytes = 0L, samples = flowing))
        assertFalse(text.contains("%"))
        assertFalse(text.contains("还剩"))
        assertFalse(text.contains("后下完"))
        assertTrue(text.contains("已下 140 MiB"))
        // 速度本身是真读数（窗口里两点都是实测），总量不知道也照样报——这不算编数。
        assertTrue(text.contains("约 4 MiB/秒"))
    }

    @Test
    fun `按当前速度要下超过一天时不报预计`() {
        val slow = listOf(sample(1L * mib, 0L), sample(1L * mib + 1_024L, 3_000L))
        val snap = streamingSnapshot(doneBytes = 1L * mib, samples = slow, peakDoneBytes = 1L * mib, nowMillis = t0 + 3_000L)
        val text = UpdateProgressText.progressText(zh, "新版本", snap)
        assertTrue(text.contains("/秒"))
        assertFalse(text.contains("后下完"))
    }

    @Test
    fun `累计字节倒退时不报速度`() {
        // 前缀作废、从头重写：窗口差值是负的，报速度就成了假数。
        val back = listOf(sample(50L * mib, 0L), sample(10L * mib, 10_000L))
        assertNull(UpdateProgressText.speedBytesPerSecond(back, t0 + 10_000L))
        val text = UpdateProgressText.progressText(
            zh, "新版本",
            streamingSnapshot(doneBytes = 10L * mib, samples = back, peakDoneBytes = 50L * mib),
        )
        assertFalse(text.contains("/秒"))
        assertTrue(text.contains("重新下一遍"))
    }

    @Test
    fun `窗口只留最近这些点并且有上限`() {
        // 每 5 秒采一点、连采 31 点：窗口只许留下最后 15 秒那几枚，且不超过 SPEED_MAX_SAMPLES。
        val built = (0L..30L).fold(emptyList<UpdateProgressText.ProgressSample>()) { acc, i ->
            UpdateProgressText.withSampleAdded(acc, sample(i * mib, i * 5_000L), t0 + i * 5_000L)
        }
        val lastAt = t0 + 30L * 5_000L
        val windowStart = lastAt - UpdateProgressText.SPEED_WINDOW_MILLIS
        assertTrue(built.all { it.atMillis >= windowStart && it.atMillis <= lastAt })
        assertTrue(built.size <= UpdateProgressText.SPEED_MAX_SAMPLES)
        assertEquals(4, built.size)
        assertEquals(lastAt, built.last().atMillis)
    }

    @Test
    fun `时长读数用秒分时三档且向上取整`() {
        assertEquals("约 1 秒", UpdateProgressText.durationLabel(zh, 1L))
        assertEquals("约 45 秒", UpdateProgressText.durationLabel(zh, 44_500L))
        assertEquals("约 2 分 21 秒", UpdateProgressText.durationLabel(zh, 140_750L))
        assertEquals("约 10 分钟", UpdateProgressText.durationLabel(zh, 10L * 60L * 1_000L + 500L))
        assertEquals("约 1 小时 5 分", UpdateProgressText.durationLabel(zh, 3_900_000L))
    }

    // ------------------------------------------------------------------
    // 病 ②：等待与换地址要看得见（判据 + 反例）
    // ------------------------------------------------------------------

    @Test
    fun `慢源一直在吐字节时一句都不许多说`() {
        // 反例（契约 §2 U1-②要的「过头话」守卫）：整段已经跑了十分钟，但每 5 秒都有新字节 ⇒ 静默不足 ⇒ null。
        val moving = (0L..120L).map { sample(it * 5L * mib, t0 + it * 5_000L) }
        assertNull(UpdateProgressText.stallLine(zh, peakDoneBytes = 600L * mib, doneBytes = 600L * mib, silentMillis = 5_000L, elapsedMillis = 600_000L))
        val text = UpdateProgressText.progressText(
            zh, "新版本",
            streamingSnapshot(doneBytes = 600L * mib, samples = moving.takeLast(16), peakDoneBytes = 600L * mib, silentMillis = 5_000L, elapsedMillis = 600_000L),
        )
        assertFalse(text.contains("等一等"))
        assertFalse(text.contains("换另一条"))
    }

    @Test
    fun `单个慢读块三十秒不算卡住`() {
        assertNull(UpdateProgressText.stallLine(zh, peakDoneBytes = 200L * mib, doneBytes = 200L * mib, silentMillis = 30_000L, elapsedMillis = 400_000L))
    }

    @Test
    fun `静默四十五秒只说可能的几种原因`() {
        val line = UpdateProgressText.stallLine(zh, 200L * mib, 200L * mib, UpdateProgressText.SILENT_WAIT_MILLIS, 100_000L)
        assertTrue(line != null && line!!.contains("多半"))
        assertTrue(line!!.contains("等一等"))
        assertTrue(line!!.contains("不用你重开"))
        // 还没到换地址的量级时不许断言在换地址（只许并列「也可能」）。
        assertFalse(line!!.contains("多半是在换另一条更新地址"))
    }

    @Test
    fun `静默两分钟且整段跑过三分钟才说在换另一条地址`() {
        val line = UpdateProgressText.stallLine(zh, 
            200L * mib,
            200L * mib,
            UpdateProgressText.SILENT_SWITCH_MILLIS,
            UpdateProgressText.SWITCH_MIN_ELAPSED_MILLIS,
        )
        assertTrue(line != null && line!!.contains("换另一条更新地址"))
    }

    @Test
    fun `刚开跑就静默两分钟不许宣称在换地址`() {
        // 第一回合本身就能静默满两分钟（建连 + 读超时 + 满档退避）， elapsed 不足时退回「等一等」那一支。
        val line = UpdateProgressText.stallLine(zh, 200L * mib, 200L * mib, 130_000L, 100_000L)
        assertTrue(line != null && line!!.contains("等一等"))
        assertFalse(line!!.contains("多半是在换另一条更新地址"))
    }

    @Test
    fun `一个字节都还没收到时不许说已经下了多少`() {
        // 「不许谎报」第一面（零字节支）：静默够久但峰值为零，句子只能承认还没收到内容。
        val line = UpdateProgressText.stallLine(zh, 0L, 0L, UpdateProgressText.SILENT_WAIT_MILLIS, 60_000L)
        assertTrue(line != null && line!!.contains("还没收到"))
        assertFalse(line!!.contains("已下"))
    }

    @Test
    fun `累计值小于峰值就是前缀作废这条敢说`() {
        val line = UpdateProgressText.stallLine(zh, peakDoneBytes = 300L * mib, doneBytes = 0L, silentMillis = 0L, elapsedMillis = 60_000L)
        assertTrue(line != null && line!!.contains("重新下一遍"))
    }

    @Test
    fun `状态行另起一行不盖住原有正文`() {
        val snap = streamingSnapshot(
            samples = flowing,
            silentMillis = UpdateProgressText.SILENT_SWITCH_MILLIS,
            elapsedMillis = UpdateProgressText.SWITCH_MIN_ELAPSED_MILLIS,
        )
        val text = UpdateProgressText.progressText(zh, "新版本", snap)
        val head = text.substringBefore('\n')
        assertTrue(head.contains("%") && head.contains("还剩"))
        assertTrue(text.contains('\n'))
        assertTrue(text.substringAfter('\n').contains("换另一条更新地址"))
    }

    // ------------------------------------------------------------------
    // 病 ③：七类失败各说各话
    // ------------------------------------------------------------------

    @Test
    fun `每个失败类别都给得出一句能做的事而且互不相同`() {
        val actions = UpdateDownloadPlan.FailureKind.values().map { UpdateProgressText.failureAction(zh, it, "") }
        assertTrue(actions.all { it.isNotBlank() })
        assertEquals(UpdateDownloadPlan.FailureKind.values().size, actions.distinct().size)
        // kind 缺失（UpdateManager.kt:519 试完所有地址那一支）与空原话各是一支，都不许闭嘴。
        assertTrue(UpdateProgressText.failureAction(zh, null, "一条也没连上").isNotBlank())
        assertTrue(UpdateProgressText.failureAction(zh, null, "").isNotBlank())
        assertEquals(2, listOf(UpdateProgressText.failureAction(zh, null, "一条也没连上"), UpdateProgressText.failureAction(zh, null, "")).distinct().size)
    }

    @Test
    fun `空间不足与别的本机读写问题不是同一句话`() {
        val space = UpdateProgressText.failureAction(zh, 
            UpdateDownloadPlan.FailureKind.LocalFileSystem,
            "空间不够：app.apk 共 743 MiB，还要留 232 MiB 余量，盘上只剩 10 MiB",
        )
        val io = UpdateProgressText.failureAction(zh, UpdateDownloadPlan.FailureKind.LocalFileSystem, "无法落盘：app.apk")
        assertTrue(space.contains("腾出空间"))
        assertFalse(space.contains("重启一次手机"))
        assertTrue(io.contains("重启一次手机"))
        // 同一条判据在整包失败正文里也要用得上。
        assertTrue(UpdateProgressText.failedText(zh, "空间不够：盘上只剩 10 MiB", UpdateDownloadPlan.FailureKind.LocalFileSystem, true).contains("腾出空间"))
    }

    @Test
    fun `失败正文留着原话再补一句你现在能做什么`() {
        val text = UpdateProgressText.failedText(zh, "网络晃了一下：连接中断", UpdateDownloadPlan.FailureKind.Network, true)
        assertTrue(text.contains("网络晃了一下：连接中断"))
        assertTrue(text.contains("你可以这样补救"))
        assertTrue(text.contains("点这条通知"))
        assertEquals(UpdatePolicy.sizeLabel(0L) + "", UpdatePolicy.sizeLabel(0L))
    }

    @Test
    fun `看不见通知的失败正文不许说点这条通知`() {
        // 「不许谎报」第一面（notifyVisible=false）。
        val text = UpdateProgressText.failedText(zh, "网络晃了一下", UpdateDownloadPlan.FailureKind.Network, false)
        assertFalse(text.contains("点这条通知"))
        assertTrue(text.contains("回游戏里再点一次「下载」"))
        assertTrue(text.contains("腾出空间") || text.contains("再试"))
    }

    @Test
    fun `拆前那一句话失败正文保持原样`() {
        // UpdateUiTest 仍按拆前形状断言；本用例守住「加类别映射不许改动旧签名」。
        assertEquals("更新下载失败：镜像连不上（点这条通知可重试）", UpdateProgressText.failedText(zh, "镜像连不上"))
        assertTrue(UpdateProgressText.failedText(zh, "").contains("原因未知"))
    }

    @Test
    fun `玩家面处置句里一个术语都没有`() {
        val forbidden = listOf("镜像", "清单", "摘要", "sha", "SHA", "Range", "versionCode", "HTTP", "http")
        val sentences = UpdateDownloadPlan.FailureKind.values().map { UpdateProgressText.failureAction(zh, it, "") } +
            listOf(
                UpdateProgressText.failureAction(zh, null, "什么也没连上"),
                UpdateProgressText.failureAction(zh, null, ""),
                UpdateProgressText.failureAction(zh, UpdateDownloadPlan.FailureKind.LocalFileSystem, "空间不够：盘上只剩 1 MiB"),
                UpdateProgressText.failureToast(zh, null),
                UpdateProgressText.retryEntry(zh, true),
                UpdateProgressText.retryEntry(zh, false),
            )
        for (sentence in sentences) {
            for (term in forbidden) assertFalse("文案里出现了术语 $term：$sentence", sentence.contains(term))
        }
    }

    // ------------------------------------------------------------------
    // 病 ④：不给通知权限也要有补救面
    // ------------------------------------------------------------------

    @Test
    fun `开始话术只在看得见通知时承诺通知栏`() {
        assertTrue(UpdateProgressText.startedToast(zh, true).contains("进度在通知栏"))
        val denied = UpdateProgressText.startedToast(zh, false)
        // 「不许谎报」第一面的源头：没权限那一支早已明说看不见，这里钉住不许回退。
        assertFalse(denied.contains("进度在通知栏"))
        assertTrue(denied.contains("看不到进度"))
    }

    @Test
    fun `整包下完的两种说法都不许在看不见通知时提通知栏`() {
        assertTrue(UpdateProgressText.apkDoneText(zh, true).contains("安装"))
        val invisible = UpdateProgressText.apkDoneText(zh, false)
        assertTrue(invisible.contains("安装"))
        assertFalse(invisible.contains("点这条通知"))
        assertTrue(invisible.contains("一个字节都不重下"))
    }

    @Test
    fun `数据包下完只说下次启动应用`() {
        // 「不许谎报」第二面：应用发生在下次启动装载前，此刻绝不许说已生效。
        val text = UpdateProgressText.assetsDoneText(zh, )
        assertTrue(text.contains("下次启动"))
        assertFalse(text.contains("已生效"))
        assertFalse(text.contains("已经应用"))
        assertFalse(UpdateProgressText.cancelledText(zh, false).contains("点这条通知"))
        assertTrue(UpdateProgressText.cancelledText(zh, false).contains("断"))
    }

    @Test
    fun `失败提醒自带一个不靠通知权限的入口`() {
        val toast = UpdateProgressText.failureToast(zh, UpdateDownloadPlan.FailureKind.HttpStatus)
        assertTrue(toast.contains("更新没能下下来"))
        assertTrue(toast.contains("回游戏里再点一次「下载」"))
        assertFalse(toast.contains("点这条通知"))
        // 重试入口的两种说法与权限一致，不许串。
        assertTrue(UpdateProgressText.retryEntry(zh, true).contains("点这条通知"))
        assertFalse(UpdateProgressText.retryEntry(zh, false).contains("点这条通知"))
    }

    @Test
    fun `设置页那颗接着下要点名是哪一份并承诺断点保留`() {
        // 组长接线（U1-④ 落到屏上的那句）：入口只有一句话的位置，所以要同时说完「哪一份」与「不重头下」。
        for ((label, other) in listOf("新版本" to "下次启动", "数据包" to "已生效")) {
            val text = UpdateProgressText.settingsRetryLabel(zh, label)
            assertTrue("$text 没点名是哪一份", text.contains(label))
            assertTrue("$text 没承诺断点", text.contains("断"))
            assertFalse("$text 不许越界承诺「$other」", text.contains(other))
            for (term in listOf("镜像", "清单", "摘要", "sha", "Range", "versionCode", "HTTP")) {
                assertFalse("玩家面句子出现了术语 $term：$text", text.contains(term))
            }
        }
    }

    // ------------------------------------------------------------------
    // EP4 U3b：本层的句子按语言取（形状与中文零漂移的正面钉点）
    // ------------------------------------------------------------------

    @Test
    fun `同一句进度正文在三种语言里都带齐那一组读数`() {
        val snap = streamingSnapshot(samples = flowing)
        val zhText = UpdateProgressText.progressText(UpdateCopy.ZH, "新版本", snap)
        listOf(UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val text = UpdateProgressText.progressText(copy, copy.apkLabel, snap)
            assertEquals("${copy.lang} 改了正文的行数", zhText.count { it == '\n' }, text.count { it == '\n' })
            // 追加的两句（速度、预报）各出现一次：多一句少一句都算形状变了。
            // 分隔符本身不算形状（英文的句式里自带一个并列分隔符，中文那两个在正文模板里）。
            assertEquals(
                "${copy.lang} 的速度句多了或少了：$text",
                1,
                Regex(Regex.escape(speedMarks.getValue(copy.lang))).findAll(text).count(),
            )
            assertEquals(
                "${copy.lang} 的预报句多了或少了：$text",
                1,
                Regex(Regex.escape(etaMarks.getValue(copy.lang))).findAll(text).count(),
            )
            // 读数与语言无关：百分比、已下、总量、还剩、速度，一格都不许多也不欠。
            listOf("18", "140 MiB", "743 MiB", "603 MiB", "4 MiB").forEach { reading ->
                assertTrue("${copy.lang} 的正文丢了读数 $reading：$text", text.contains(reading))
            }
            assertTrue("${copy.lang} 的正文丢了百分比号：$text", text.contains("%"))
            assertTrue("${copy.lang} 的正文丢了预报句：$text", text.contains(etaMarks.getValue(copy.lang)))
        }
        // 中文那一行仍是搬动前的形状（这一条就是「逐字搬运」在正文上的钉子）。
        assertEquals("新版本下载中 18% · 已下 140 MiB / 共 743 MiB · 还剩 603 MiB · 约 4 MiB/秒 · 约 2 分 31 秒后下完", zhText)
    }

    @Test
    fun `时长与预报在三语下都是同一棵树（不许出现零秒那种假精确）`() {
        val probes = listOf(1L, 44_500L, 140_750L, 10L * 60_000L + 500L, 3_900_000L)
        listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val labels = probes.map { UpdateProgressText.durationLabel(copy, it) }
            assertEquals("每档都只有一句", probes.size, labels.distinct().size)
            labels.forEach { assertTrue("${copy.lang} 出了空串", it.isNotBlank()) }
            assertTrue("${copy.lang} 出现了 0 秒档：$labels", labels.none { it.contains("0 秒") || it.contains("0 sec") })
        }
        assertEquals("约 1 小时 5 分", UpdateProgressText.durationLabel(UpdateCopy.ZH, 3_900_000L))
        // 算不出预报那一支在三语下都是「没有那一句」，不是「换一句」。
        val slow = listOf(sample(1L * mib, 0L), sample(1L * mib + 1_024L, 3_000L))
        val snap = streamingSnapshot(doneBytes = 1L * mib, samples = slow, peakDoneBytes = 1L * mib, nowMillis = t0 + 3_000L)
        val speedMarks = mapOf(
            SettingsLanguage.ZH to "/秒",
            SettingsLanguage.EN to "per second",
            SettingsLanguage.JA to "/秒",
        )
        listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val text = UpdateProgressText.progressText(copy, copy.apkLabel, snap)
            assertTrue("${copy.lang} 把真读数也一起吞了：$text", text.contains(speedMarks.getValue(copy.lang)))
            assertFalse("${copy.lang} 在算不出预报时多塞了一句：$text", text.contains(etaMarks.getValue(copy.lang)))
        }
    }

    @Test
    fun `九支处置句在三语下各自互不相同（英文不许只给三类）`() {
        val kinds = UpdateDownloadPlan.FailureKind.values().toList()
        listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val actions = kinds.map { UpdateProgressText.failureAction(copy, it, "") } +
                UpdateProgressText.failureAction(copy, null, "一条也没连上") +
                UpdateProgressText.failureAction(copy, null, "")
            assertEquals("${copy.lang} 的处置句支数不对", kinds.size + 2, actions.size)
            assertTrue("${copy.lang} 有空的处置句", actions.all { it.isNotBlank() })
            assertEquals("${copy.lang} 的处置句撞了：${actions.groupingBy { it }.eachCount().filterValues { it > 1 }}",
                actions.size, actions.distinct().size)
        }
        // 同一条决策对同一句话：三语各取第 i 句，句子必须互不相同但**位置一致**（七类 + 两支无类别）。
        kinds.forEachIndexed { index, kind ->
            val a = UpdateProgressText.failureAction(UpdateCopy.ZH, kind, "")
            val b = UpdateProgressText.failureAction(UpdateCopy.EN, kind, "")
            val c = UpdateProgressText.failureAction(UpdateCopy.JA, kind, "")
            assertTrue("$index 号决策（$kind）英中同句", a != b)
            assertTrue("$index 号决策（$kind）日中同句", a != c)
            assertTrue("$index 号决策（$kind）英日同句", b != c)
        }
    }

    @Test
    fun `看不见通知栏时三语都不许承诺那条通知`() {
        val zhPromise = "点这条通知"
        val enPromise = "this notification"
        val jaPromise = "この通知"
        listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            val promise = when (copy.lang) {
                SettingsLanguage.ZH -> zhPromise
                SettingsLanguage.EN -> enPromise
                SettingsLanguage.JA -> jaPromise
            }
            val texts = listOf(
                UpdateProgressText.apkDoneText(copy, false),
                UpdateProgressText.cancelledText(copy, false),
                UpdateProgressText.failedText(copy, "网络晃了一下", UpdateDownloadPlan.FailureKind.Network, false),
                UpdateProgressText.failureToast(copy, UpdateDownloadPlan.FailureKind.HttpStatus),
                UpdateProgressText.retryEntry(copy, false),
            )
            texts.forEach { assertFalse("${copy.lang} 看不见通知时仍承诺了通知：$it", it.contains(promise)) }
        }
        // 看得见那一支反过来要**必须**给得出这个入口，三语都不许空。
        listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA).forEach { copy ->
            assertTrue(UpdateProgressText.retryEntry(copy, true).isNotBlank())
            assertTrue(UpdateProgressText.failedText(copy, "", null, true).isNotBlank())
        }
    }
}
