package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L8 两条接线遗漏的**源码形状闸 + 纯判定等价闸**（收尾批）。
 *
 * `UpdateDownloadService.jobFrom` 与 `UpdateManager.downloadOnce` 都在 Android 壳里
 * （Service 要真机、`HttpURLConnection` 不可注入），本仓的既有口径是
 * `UpdateDownloadServiceForegroundGateTest` / `UpdateDownloadL4WiringTest` 那种
 * 「读主源文件的代码行、钉接线形状」。这里沿用同一套 helper。
 *
 * 钉的两件事：
 * - `jobFrom` 的摘要先过 [UpdatePolicy.normalizeSha]、`kind == Apk` 强制 `.apk` 后缀
 *   （与 `UpdateFileProvider` 的 allowlist 同口径）；
 * - `downloadOnce` 的「从头重写」判定接上 [UpdateDownloadPlan.mustRewriteFromScratch]，
 *   且它与 `resumeTrusted` 的等价关系被 [mustRewriteFromScratch 与 resumeTrusted 互为反面] 钉死
 *   （接线等价性不是靠读代码相信，是靠矩阵穷举）。
 *
 * 破坏性验证：把这两处接线短路（还原成旧写法），本类的形状用例必须红。
 */
class UpdateWireL8Test {

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
    fun `jobFrom 摘要先归一且整包名必须是 apk`() {
        val svc = codeLines("UpdateDownloadService.kt")
        val job = span(svc, "private fun jobFrom(", "private fun ensureChannels(")

        // ① 摘要过 normalizeSha 且坏摘要整单作废（?: return null）。
        val normalize = job.indexOf("UpdatePolicy.normalizeSha(")
        assertTrue("jobFrom 必须对 Intent 里的 sha 做 normalizeSha 归一/校验", normalize >= 0)
        assertTrue(
            "归一结果必须 `?: return null`：坏摘要整单作废，不许带垃圾 sha 下单",
            job.substring(normalize).contains("?: return null"),
        )

        // ② 归一发生在成单之前（Artifact 拿到的必须是归一后的 sha）。
        val build = job.indexOf("DownloadJob(")
        assertTrue("找不到 DownloadJob( 成单点", build > normalize)
        assertTrue("摘要归一必须排在成单之前", normalize < build)
        assertTrue("Artifact 必须吃归一后的 sha", job.contains("Artifact(name, size, sha)"))

        // ③ kind == Apk ⇒ 文件名必须以 .apk 结尾（与 UpdateFileProvider 的 allowlist 同口径）。
        val kindDecl = job.indexOf("val kind =")
        val apkSuffix = job.indexOf("name.endsWith(UpdateDownloadPlan.APK_SUFFIX)")
        assertTrue("jobFrom 必须先解出 kind 再判后缀", kindDecl >= 0 && kindDecl < apkSuffix)
        assertTrue(
            "kind == Apk 必须强制 .apk 后缀，否则几百 MiB 下载完才在 Provider 里失败",
            job.contains("UpdateDownloadKind.Apk") && apkSuffix >= 0,
        )
        assertTrue("后缀判式失败要整单作废", job.substring(apkSuffix).contains("return null"))
    }

    @Test
    fun `downloadOnce 的从头重写接上 mustRewriteFromScratch`() {
        val src = codeLines("UpdateManager.kt")
        val once = span(src, "private fun downloadOnce(", "fun cleanupStaleDownloads(")

        val call = once.indexOf("UpdateDownloadPlan.mustRewriteFromScratch(offset, code, contentRangeStart)")
        assertTrue("downloadOnce 必须接上 UpdateDownloadPlan.mustRewriteFromScratch（纯判定层的「从头发」判式）", call >= 0)
        // resume 由 mustRewrite 驱动：两者是同一份语义的正反面（等价性见下一条矩阵用例）。
        assertTrue("resume 必须由 mustRewrite 派生，不再各自复算一份判式", once.contains("!mustRewrite && UpdateDownloadPlan.resumeTrusted("))
        // 判式必须排在真正决定 truncate 的 FileOutputStream 之前（否则接线只是写在日志里）。
        val stream = once.indexOf("FileOutputStream(part, resume)")
        assertTrue("mustRewrite 必须在 FileOutputStream(part, resume) 之前算出", call < stream)
        assertTrue("找不到 FileOutputStream(part, resume)：落盘 append 语义被改名了", stream > call)
    }

    @Test
    fun `mustRewriteFromScratch 与 resumeTrusted 互为反面`() {
        // 接线等价性的穷举证明：mustRewrite == (offset > 0 && !trusted)，二者永不同真。
        for (offset in listOf(0L, 1L, 256L, 500L)) {
            for (code in listOf(200, 206, 416, 500)) {
                for (start in listOf(null, 0L, 1L, 499L, 500L)) {
                    val trusted = UpdateDownloadPlan.resumeTrusted(offset, code, start)
                    val rewrite = UpdateDownloadPlan.mustRewriteFromScratch(offset, code, start)
                    assertEquals(
                        "offset=$offset code=$code start=$start 时 mustRewrite 必须等于 offset>0 且 !trusted",
                        offset > 0L && !trusted,
                        rewrite,
                    )
                    assertFalse("offset=$offset code=$code start=$start：续传与重写不可同时成立", trusted && rewrite)
                }
            }
        }
    }
}
