package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L4 下载/安装接线的**源码形状闸**（与 `UpdateDownloadServiceForegroundGateTest` 同一套口径）。
 *
 * 钉的是「接线顺序」这一类纯 JVM 行为测试抓不到的错：`HttpURLConnection` 不可注入、
 * `FileOutputStream.fd.sync()` 与 `PackageInstallerSession` 要真机、目录清理竞态要并发。
 * 判据取**代码行**（注释与 KDoc 已剔除），措辞改动不影响闸；动接线就得回来对账。
 *
 * 每条都对应一个 L4 缺陷的「修好了」形状，破坏性验证时把对应实现短路，这里必须红：
 * - 明文降级闸排在 `responseCode` 之后（connect 后 conn.url 还是原始地址，挪回去就红）；
 * - 下载落盘 `fd.sync()` 排在 rename 之前；
 * - 下载成功返回前调整包验章；
 * - 抢闸与目录清理共用同一把锁；
 * - 安装入口处理完退场（且不打断在飞下载）；
 * - 安装回执接收器 `RECEIVER_NOT_EXPORTED`（不许再向全设备开放自定义 action）；
 * - 失败单摘要过 `UpdatePolicy.normalizeSha`。
 */
class UpdateDownloadL4WiringTest {

    private fun moduleRoot(): File =
        listOf(File("."), File("../app"), File("app"))
            .firstOrNull { File(it, "src/main/kotlin").isDirectory }
            ?: error("找不到 app/src/main/kotlin")

    /** 主源文件的**代码行**（丢掉注释与 KDoc）：判接线，不判文档措辞。 */
    private fun codeLines(name: String): String =
        File(moduleRoot(), "src/main/kotlin/com/eratw/port/android/$name")
            .readText(Charsets.UTF_8)
            .lines()
            .filterNot { line ->
                val t = line.trim()
                t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")
            }
            .joinToString("\n")

    /** 取 [from] 到其后第一处 [to] 之间的代码片段；找不到即红线（签名/接线被改名）。 */
    private fun span(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("找不到起点：$from", a >= 0)
        val b = text.indexOf(to, a + from.length)
        assertTrue("找不到终点：$to（起点 $from）", b > a)
        return text.substring(a, b)
    }

    @Test
    fun `明文降级闸排在 responseCode 之后`() {
        val src = codeLines("UpdateManager.kt")
        // fetchManifest：先读响应码（触发重定向跟随），再判最终 URL 是否仍 https。
        val fetch = span(src, "private fun fetchManifest(", "private fun downloadOnce(")
        val fetchCode = fetch.indexOf("conn.responseCode")
        val fetchGuard = fetch.indexOf("isHttps(conn.url")
        assertTrue("fetchManifest 找不到 responseCode", fetchCode >= 0)
        assertTrue("fetchManifest 找不到最终 URL 判式", fetchGuard >= 0)
        assertTrue("fetchManifest 必须先读响应码再判最终 URL（否则重定向还没发生）", fetchCode < fetchGuard)
        // downloadOnce 同形态。
        val once = span(src, "private fun downloadOnce(", "fun cleanupStaleDownloads(")
        val onceCode = once.indexOf("conn.responseCode")
        val onceGuard = once.indexOf("isHttps(conn.url")
        assertTrue("downloadOnce 找不到 responseCode", onceCode >= 0)
        assertTrue("downloadOnce 找不到最终 URL 判式", onceGuard >= 0)
        assertTrue("downloadOnce 必须先读响应码再判最终 URL（否则明文降级闸形同虚设）", onceCode < onceGuard)
    }

    @Test
    fun `下载落盘先 fsync 再 rename`() {
        val src = codeLines("UpdateManager.kt")
        val once = span(src, "private fun downloadOnce(", "fun cleanupStaleDownloads(")
        val sync = once.indexOf("out.fd.sync()")
        val rename = once.lastIndexOf("part.renameTo(target)")
        assertTrue("downloadOnce 找不到落盘 fsync", sync >= 0)
        assertTrue("找不到 renameTo", rename > sync)
        assertTrue("fsync 必须排在（校验后的）rename 之前：rename 只保证目录项，不保证数据落盘", sync < rename)
    }

    @Test
    fun `下载成功返回前给整包验章`() {
        val src = codeLines("UpdateManager.kt")
        val download = span(src, "fun download(context: Context", "private fun downloadOnce(")
        assertTrue("download() 成功分支必须调整包验章（防白下 700 MiB 再吃系统安装失败）", download.contains("verifyDownloadedApkCert("))
        val plan = codeLines("UpdateDownloadPlan.kt")
        assertTrue("纯判定层缺 apkCertSatisfied", plan.contains("fun apkCertSatisfied("))
    }

    @Test
    fun `下载抢闸与目录清理互斥`() {
        val src = codeLines("UpdateManager.kt")
        val gate = span(src, "fun tryBeginDownload(", "fun releaseDownload(")
        assertTrue("抢闸必须在 downloadDirLock 内（与清理互斥，否则清理会删掉刚建的 .part）", gate.contains("synchronized(downloadDirLock)"))
        val cleanup = span(src, "fun cleanupStaleDownloads(", "fun stageAssetsBundle(")
        assertTrue("目录清理全程必须持同一把锁", cleanup.contains("synchronized(downloadDirLock)"))
        assertTrue("清理必须在锁内复核 downloading", cleanup.contains("if (downloading.get()) return 0"))
    }

    @Test
    fun `安装入口处理完退场且不打断在飞下载`() {
        val svc = codeLines("UpdateDownloadService.kt")
        val branch = span(svc, "ACTION_INSTALL -> {", "else -> beginDownloadSafe(")
        assertTrue("ACTION_INSTALL 成功路径必须 stopSelf（否则点一次安装留一个常驻空服务）", branch.contains("stopSelf(startId)"))
        assertTrue("只在没持闸时退场：不许把在飞下载连服务一起停掉", branch.contains("if (!gateHeld)"))
    }

    @Test
    fun `安装回执接收器已收窄为不导出`() {
        val src = codeLines("PackageInstallerSession.kt")
        assertTrue("API 33+ 必须用 RECEIVER_NOT_EXPORTED 注册回执接收器", src.contains("Context.RECEIVER_NOT_EXPORTED"))
        assertFalse("不许再向全设备开放自定义 action（任意 app 可伪造安装回执）", src.contains("Context.RECEIVER_EXPORTED"))
    }

    @Test
    fun `失败单摘要必须过 normalizeSha`() {
        val src = codeLines("UiPrefs.kt")
        val read = span(src, "internal fun readFailedDownload(", "internal fun writeFailedDownload(")
        assertTrue("失败单还原必须校验摘要形状并归一（坏摘要会重现「每次失败在最末尾」）", read.contains("UpdatePolicy.normalizeSha("))
    }
}
