package com.eratw.port.android

import com.eratw.port.core.update.ApkChannel
import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.UpdateInfo
import com.eratw.port.core.update.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 覆盖升级预检「两处口径不一致」的定向闸（offer 侧 vs 下载侧）。
 *
 * 缺陷形状：同一句「这台设备能不能覆盖升级」判了两次，offer 侧（`UpdateManager.checkNow`，
 * 决定弹窗给不给下载按钮）用 core 的**单值**判式 `UpdatePolicy.certBlocksInstall(localCertSha256)`，
 * 下载侧（`UpdateManager.download`）用**候选集合**判式
 * [UpdateDownloadPlan.certBlocksInstallAny]。多 signer 的包、轮换过签名密钥的设备上，清单声明的章
 * 只要不是候选集合的第一枚（但确实在集合里），offer 侧就判「装不上、必须先卸载（存档会没）」，
 * 弹窗降级成「知道了」、整条更新被挡；下载侧同场景本来放行。
 *
 * 本类钉三件事：
 * - [覆盖升级预检四组：单签与多签都按候选集合判]：判式本体在四种输入上的结论（③ 是本笔真红）；
 * - [轮换过签名密钥：历史最后一枚才算当前章]：候选集合的组成顺序（读代码给的语义，不造夹具）；
 * - [offer 侧预检与下载侧预检必须是同一口径]：**接线形状闸** —— 这条是回归钉，把 offer 侧还原成
 *   单值判式它必红（判式本体已有 `UpdateDownloadPlanTest` 覆盖，本路要钉的是接线）。
 *
 * 形状闸沿用 `UpdateWireL8Test` / `UpdateDownloadL4WiringTest` 的既有口径：读主源文件的**代码行**
 * （丢注释与 KDoc），钉接线不钉文档措辞。
 */
class UpdateCertOfferGateTest {

    private val copy = UpdateCopy.ZH

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

    private fun countOf(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    // ------------------------------------------------------------------
    // 夹具：证书 DER 与「清单声明章」（真机读的是 DER，这里只要字节等价即可）
    // ------------------------------------------------------------------

    private fun der(tag: String): ByteArray = "DER:$tag".toByteArray(Charsets.UTF_8)

    /** 单枚 DER 的 SHA-256 十六进制 —— 直接借被测实现的哈希口径，不另立一份。 */
    private fun certOf(d: ByteArray): String =
        UpdateDownloadPlan.signerCertSha256Candidates(listOf(d), emptyList()).single()

    private fun apkInfo(code: Long, name: String) = UpdateInfo(
        format = 1,
        generated = "2026-11-01T00:00:00Z",
        note = "",
        apk = ApkChannel(code, name, Artifact("eratw-$name.apk", 743L * 1024L * 1024L, "a".repeat(64))),
        assets = null,
        mirrors = emptyList(),
        warnings = emptyList(),
    )

    // ------------------------------------------------------------------
    // 判式本体：四组输入
    // ------------------------------------------------------------------

    @Test
    fun `覆盖升级预检四组：单签与多签都按候选集合判`() {
        val a = der("A")
        val b = der("B")
        val c = der("C")

        // ① 单 signer、清单章相符 ⇒ 放行。
        val single = UpdateDownloadPlan.signerCertSha256Candidates(listOf(a), emptyList())
        assertEquals("单 signer 候选集只有一枚", listOf(certOf(a)), single)
        assertFalse(
            "① 单 signer 章相符必须放行",
            UpdateDownloadPlan.certBlocksInstallAny(single, certOf(a)),
        )

        // ② 单 signer、清单章不符 ⇒ 拦（这条是「真不合」的正例，修复不能把它放过）。
        assertTrue(
            "② 单 signer 章不符必须拦",
            UpdateDownloadPlan.certBlocksInstallAny(single, certOf(b)),
        )

        // ③ 多 signer、清单章 = 第二枚 ⇒ 不拦（本笔修的真红）。
        val multi = UpdateDownloadPlan.signerCertSha256Candidates(listOf(a, b), emptyList())
        assertEquals(
            "当前 signer 必须全量入列且保持 apkContentsSigners 顺序",
            listOf(certOf(a), certOf(b)),
            multi,
        )
        assertFalse(
            "③ 多 signer 清单声明第二枚：任一匹配即不拦",
            UpdateDownloadPlan.certBlocksInstallAny(multi, certOf(b)),
        )
        // 同一份输入上，单值判式（offer 侧原写法）给出相反结论 —— 这就是被修的错判本身，
        // 也是 ③ 的「反向证明」在纯判定层的复现。
        assertTrue(
            "单值判式在 ③ 上必然判「装不上」：两处口径不一致才是缺陷",
            UpdatePolicy.certBlocksInstall(multi.first(), certOf(b)),
        )

        // ④ 多 signer、两枚都不等 ⇒ 拦。
        assertTrue(
            "④ 多 signer 清单章是第三方章必须拦",
            UpdateDownloadPlan.certBlocksInstallAny(multi, certOf(c)),
        )
    }

    @Test
    fun `轮换过签名密钥：历史最后一枚才算当前章`() {
        val old = der("OLD")
        val now = der("NOW")

        // `SigningInfo.getSigningCertificateHistory()` 的顺序：原始证书 index 0、当前证书最后一枚
        // （UpdateDownloadPlan.kt:548-574 的 KDoc 与实现，`historyDers.lastOrNull()`）。
        // 真机场景：contents 已给当前章，历史末枚与它重复 ⇒ 集合去重后仍是当前章一枚。
        val deduped = UpdateDownloadPlan.signerCertSha256Candidates(listOf(now), listOf(old, now))
        assertEquals("历史末枚 = 当前章：与 contents 去重后只剩一枚", listOf(certOf(now)), deduped)
        assertFalse(
            "清单声明当前章：不拦",
            UpdateDownloadPlan.certBlocksInstallAny(deduped, certOf(now)),
        )

        // contents 只给部分 signer 的场合：历史最后一枚兜底入列（UpdateDownloadPlan.kt:570-572）。
        val partial = UpdateDownloadPlan.signerCertSha256Candidates(listOf(old), listOf(old, now))
        assertEquals(
            "contents 只给旧章时，历史末枚必须补进候选集",
            listOf(certOf(old), certOf(now)),
            partial,
        )
        assertFalse(
            "有兜底 ⇒ 清单声明当前章不拦",
            UpdateDownloadPlan.certBlocksInstallAny(partial, certOf(now)),
        )
    }

    // ------------------------------------------------------------------
    // 接线形状闸：offer 侧与下载侧必须是同一口径
    // ------------------------------------------------------------------

    @Test
    fun `offer 侧预检与下载侧预检必须是同一口径`() {
        val src = codeLines("UpdateManager.kt")

        val offer = span(
            src,
            "val apkFile = info.apk?.artifact?.fileName.orEmpty()",
            "rememberExpectedApkCert(",
        )
        assertTrue(
            "offer 侧必须用候选集合判式：certBlocksInstallAny(localCertSha256Candidates(context), …)",
            offer.contains(
                "UpdateDownloadPlan.certBlocksInstallAny(localCertSha256Candidates(context), " +
                    "info.apkCertSha256)",
            ),
        )
        assertFalse(
            "offer 侧不许再用单值判式（多 signer / 轮换设备会误判「装不上、要先卸载」）",
            offer.contains("UpdatePolicy.certBlocksInstall("),
        )
        assertFalse(
            "offer 侧不许再取单值章 localCertSha256(context) 去比（它是候选集的第一枚）",
            offer.contains("localCertSha256(context)"),
        )

        // 两处预检（offer 一次、下载前一次）点名同一判式，且全文件不再出现单值判式。
        assertEquals(
            "覆盖升级预检只许两处：offer 侧 + 下载侧",
            2,
            countOf(src, "certBlocksInstallAny("),
        )
        assertFalse(
            "UpdateManager 不许再出现单值判式 UpdatePolicy.certBlocksInstall(",
            src.contains("UpdatePolicy.certBlocksInstall("),
        )
        assertTrue(
            "下载侧预检必须仍在（同一口径的另一半）",
            src.contains("UpdateDownloadPlan.certBlocksInstallAny(localCertSha256Candidates(context), expectedCert)"),
        )
    }

    // ------------------------------------------------------------------
    // offer 形态：判式放行的后果 = 弹窗照常给整包单
    // ------------------------------------------------------------------

    @Test
    fun `候选集放行时弹窗照常可下整包 章真不合时才降级`() {
        val info = apkInfo(23L, "0.2.47")

        // 判式放行（章在候选集里）⇒ offer 不带 installBlockedReason ⇒ 整包单。
        val open = UpdateManager.UpdateOffer(info, apkNeeded = true, assetsNeeded = false, 8L, 0L)
        val job = UpdateUiText.jobFor(copy, open)
        assertTrue("放行时必须成整包单，否则玩家点不了下载", job != null)
        assertEquals(UpdateDownloadKind.Apk, job!!.kind)
        assertEquals("eratw-0.2.47.apk", job.artifact.fileName)

        // 章真不合 ⇒ 整包一票否决（白下 743 MiB 再吃系统英文报错是最坏形状）。
        val blocked = UpdateManager.UpdateOffer(
            info, apkNeeded = true, assetsNeeded = false, 8L, 0L,
            installBlockedReason = "这台设备上装的是另一套签名章的包（内测/旧包），新包无法覆盖升级。",
        )
        assertNull("章真不合时不许给整包单", UpdateUiText.jobFor(copy, blocked))
    }

    @Test
    fun `强更档与章不合同时成立时正文两行且没有可下载件`() {
        // m00069 的「顺手核」：这两档同时命中时屏上到底几行、按钮长什么样。
        // 行拼接事实（UpdateUiText.kt:80-123）：forceUpdate 置顶行 + 章不合行 = 2 行；
        // 没有 assets ⇒ 数据行与流量行都不出现；jobFor 返 null ⇒ 弹窗只剩 positive「知道了」
        // 与 negative「关闭」（UpdateUi.kt:477-504），没有可点的下载。
        val offer = UpdateManager.UpdateOffer(
            apkInfo(23L, "0.2.47"), apkNeeded = true, assetsNeeded = false, 8L, 0L,
            installBlockedReason = "这台设备上装的是另一套签名章的包（内测/旧包），新包无法覆盖升级。",
            forceUpdate = true,
            currentVersionName = "0.2.20",
        )
        val lines = UpdateUiText.offerMessageLines(copy, offer, onCellular = false)
        assertEquals("强更置顶行 + 章不合坏消息行 = 2 行", 2, lines.size)
        assertEquals("首行必须是强更文案", copy.offerForceUpdateLine, lines[0])
        assertTrue("次行是章不合坏消息（含新版本名）", lines[1].contains("无法安装") && lines[1].contains("0.2.47"))
        assertNull("没有可下载件 ⇒ 弹窗不给下载按钮", UpdateUiText.jobFor(copy, offer))
        assertEquals("强更档不给「以后都不」", 0L, UpdateUiText.skipCodeForReminder(offer))
        // 两个坏消息叠在屏上时，也不许漏出 markdown 记号（屏上没有渲染器）。
        assertTrue("正文不许出现 ** 记号", lines.none { it.contains("**") })
    }
}
