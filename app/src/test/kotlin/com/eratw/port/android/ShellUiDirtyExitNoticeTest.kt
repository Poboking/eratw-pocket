package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [dirtyExitNoticeVisible] 是「上次异常退出」提示卡的契约（2026-10-30）。
 *
 * 背景：脏退出补报的报告文件躺在应用私有目录，自动上报在大陆网络经常送不出去
 * （workers.dev 被 DNS 投毒）—— 没有这块卡，玩家永远不会知道这份可以手动交的东西存在。
 *
 * 三道闸（逐条锁死）：
 * ① 装载在转（`loading`）不弹 —— 不盖进度条；
 * ② 失败屏在场不弹 —— 那张屏有自己的「复制 / 分享报告」，两张报告卡同屏互相抢话；
 * ③ 报告文件真实存在才弹 —— 点了得有东西可给，空引用/已清目录一律不亮。
 */
class ShellUiDirtyExitNoticeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun realReport(): File =
        tmp.root.resolve("eratw-report-20261030-120000.txt").apply { writeText("报告本体") }

    @Test
    fun `装载中与失败屏在场都不弹`() {
        assertTrue(dirtyExitNoticeVisible(loading = false, failure = null, report = realReport()))
        assertFalse("不盖装载进度", dirtyExitNoticeVisible(loading = true, failure = null, report = realReport()))
        assertFalse(
            "失败屏有自己的报告出口，两张卡不许同屏",
            dirtyExitNoticeVisible(loading = false, failure = "OutOfMemoryError …", report = realReport()),
        )
    }

    @Test
    fun `没有真实报告文件不弹`() {
        assertFalse("null = 本进程没报过或落盘失败", dirtyExitNoticeVisible(loading = false, failure = null, report = null))
        assertFalse(
            "文件不存在（已清目录/滚出保留窗）点了没东西可给",
            dirtyExitNoticeVisible(loading = false, failure = null, report = File(tmp.root, "缺席的报告.txt")),
        )
    }

    @Test
    fun `文件在且装载结束且无失败屏才弹`() {
        assertTrue(dirtyExitNoticeVisible(loading = false, failure = null, report = realReport()))
    }
}
