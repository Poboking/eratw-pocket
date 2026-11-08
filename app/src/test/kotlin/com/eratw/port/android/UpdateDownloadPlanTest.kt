package com.eratw.port.android

import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateDownloadPlan] 的定向单测（CONTRACT-UPDATE U-B 出口判据）：
 * 续传/重写、失败分类与退避、空间闸缩放、清理点名——全是纯函数，纯 JVM 直接钉。
 *
 * 断言对应的语义账：
 * - §1.4 续传两条信任判据与「回 200 必须从头重写」（缝合文件 sha 必错，代价与理由见
 *   [UpdateDownloadPlan.mustRewriteFromScratch] 的 KDoc）；
 * - §3 接口：偏移判定与退避档位**委托 core**（app 不重算），这里钉的是委托的形状与
 *   装配的边界条件；
 * - U-B 第 4 条：空间闸按清单声明 size + 随 size 缩放的余量，不再一刀切 512 MiB；
 * - U-B 第 3/5 条：陈旧产物与换版后 APK 残留的回收点名。
 *
 * 真源形状引用：产物地址形如 `https://eratw-android.asia/updates/<artifact-file>`（契约 §1.1），
 * 测试里的文件名只是形状样本，不联网。
 */
class UpdateDownloadPlanTest {

    private fun artifact(name: String, size: Long) = Artifact(name, size, "a".repeat(64))

    private val maxAge = UpdatePolicy.PART_MAX_AGE_MILLIS
    private val mib = 1024L * 1024L

    // ------------------------------------------------------------------
    // 续传偏移：委托 core 的四条 + 边界
    // ------------------------------------------------------------------

    @Test
    fun `没有既有 part 时偏移为零`() {
        assertEquals(0L, UpdateDownloadPlan.resumeOffsetFor(artifact("data.zip", 1000L), null))
    }

    @Test
    fun `新鲜且不超过声明的 part 按现有字节续`() {
        val part = UpdateDownloadPlan.PartState(bytes = 400L, ageMillis = 60_000L)
        assertEquals(400L, UpdateDownloadPlan.resumeOffsetFor(artifact("data.zip", 1000L), part))
    }

    @Test
    fun `超期的 part 不续 边界年龄刚好等于上限仍可续`() {
        val artifact = artifact("data.zip", 1000L)
        assertEquals(
            0L,
            UpdateDownloadPlan.resumeOffsetFor(artifact, UpdateDownloadPlan.PartState(400L, maxAge + 1L)),
        )
        assertEquals(
            400L,
            UpdateDownloadPlan.resumeOffsetFor(artifact, UpdateDownloadPlan.PartState(400L, maxAge)),
        )
    }

    @Test
    fun `字节数超过声明的 part 一律不续`() {
        val part = UpdateDownloadPlan.PartState(bytes = 1001L, ageMillis = 0L)
        assertEquals(0L, UpdateDownloadPlan.resumeOffsetFor(artifact("data.zip", 1000L), part))
    }

    @Test
    fun `字节数已齐的 part 判为完成 等 sha 核对而不是再发请求`() {
        val part = UpdateDownloadPlan.PartState(bytes = 1000L, ageMillis = 0L)
        val artifact = artifact("data.zip", 1000L)
        assertTrue(UpdateDownloadPlan.isPartCompleteFor(artifact, part))
        // 偏移落在文件末尾（core 的口径）：核过就能用，核不过调用方整段重来。
        assertEquals(1000L, UpdateDownloadPlan.resumeOffsetFor(artifact, part))
        assertFalse(UpdateDownloadPlan.isPartCompleteFor(artifact, UpdateDownloadPlan.PartState(999L, 0L)))
    }

    // ------------------------------------------------------------------
    // 服务端响应：什么时候必须从头重写
    // ------------------------------------------------------------------

    @Test
    fun `回 200 时必须从头重写`() {
        // 这是本路最硬的一条：在旧 .part 后追加 200 响应的完整字节流 = sha 必错的缝合文件。
        assertTrue(UpdateDownloadPlan.mustRewriteFromScratch(requestedOffset = 500L, responseCode = 200, contentRangeStart = null))
        assertFalse(UpdateDownloadPlan.resumeTrusted(500L, 200, null))
    }

    @Test
    fun `回 206 且起点吻合才允许追加`() {
        assertTrue(UpdateDownloadPlan.resumeTrusted(500L, 206, 500L))
        assertFalse(UpdateDownloadPlan.mustRewriteFromScratch(500L, 206, 500L))
        // 起点不吻合（野鸡代理改写了区间）：同样不许追加。
        assertTrue(UpdateDownloadPlan.mustRewriteFromScratch(500L, 206, 499L))
        // 206 但 Content-Range 读不出来：拿不准倒向重写。
        assertTrue(UpdateDownloadPlan.mustRewriteFromScratch(500L, 206, null))
    }

    @Test
    fun `没要求续传时谈不上重写`() {
        assertFalse(UpdateDownloadPlan.mustRewriteFromScratch(0L, 200, null))
        assertFalse(UpdateDownloadPlan.resumeTrusted(0L, 206, 0L))
    }

    @Test
    fun `content-range 解析 认标准形状 拒一切花活`() {
        assertEquals(1000L, UpdateDownloadPlan.contentRangeStart("bytes 1000-1999/6800"))
        assertEquals(42L, UpdateDownloadPlan.contentRangeStart("Bytes 42-99/100"))
        assertNull(UpdateDownloadPlan.contentRangeStart("bytes */100"))
        assertNull(UpdateDownloadPlan.contentRangeStart(null))
        assertNull(UpdateDownloadPlan.contentRangeStart(""))
        assertNull(UpdateDownloadPlan.contentRangeStart("range: 0-999"))
        // 请求头的形状（bytes=...）不是响应头，拒——续传信任只给规范响应。
        assertNull(UpdateDownloadPlan.contentRangeStart("bytes=1000-1999/2000"))
    }

    // ------------------------------------------------------------------
    // 失败分类与退避
    // ------------------------------------------------------------------

    @Test
    fun `只有网络与临时 HTTP 错误配得上同镜像退避重试`() {
        for (kind in listOf(UpdateDownloadPlan.FailureKind.Network, UpdateDownloadPlan.FailureKind.HttpStatus)) {
            assertEquals(UpdateDownloadPlan.FailureDecision.RetrySameMirror, UpdateDownloadPlan.decideFailure(kind))
            assertTrue(UpdateDownloadPlan.retryOnSameMirror(kind))
        }
        val switchers = listOf(
            UpdateDownloadPlan.FailureKind.MissingOnMirror,
            UpdateDownloadPlan.FailureKind.PlainHttp,
            UpdateDownloadPlan.FailureKind.DigestMismatch,
            UpdateDownloadPlan.FailureKind.OversizeStream,
            UpdateDownloadPlan.FailureKind.LocalFileSystem,
        )
        for (kind in switchers) {
            assertEquals(UpdateDownloadPlan.FailureDecision.NextMirror, UpdateDownloadPlan.decideFailure(kind))
            assertFalse(UpdateDownloadPlan.retryOnSameMirror(kind))
        }
    }

    @Test
    fun `退避档位委托 core 且档位用完即换镜像`() {
        // 1s/2s/5s/15s/30s（契约 §3）；第 6 次失败（attempt=5）返回 null = 推进下一条镜像。
        assertEquals(1_000L, UpdateDownloadPlan.backoffMillisOrFailOver(0))
        assertEquals(2_000L, UpdateDownloadPlan.backoffMillisOrFailOver(1))
        assertEquals(5_000L, UpdateDownloadPlan.backoffMillisOrFailOver(2))
        assertEquals(15_000L, UpdateDownloadPlan.backoffMillisOrFailOver(3))
        assertEquals(30_000L, UpdateDownloadPlan.backoffMillisOrFailOver(4))
        assertNull(UpdateDownloadPlan.backoffMillisOrFailOver(5))
        assertNull(UpdateDownloadPlan.backoffMillisOrFailOver(-1))
        // 委托形状不许跑偏：读数与 core 的档位表逐项一致。
        UpdatePolicy.RETRY_BACKOFF_MILLIS.forEachIndexed { index, millis ->
            assertEquals(millis, UpdateDownloadPlan.backoffMillisOrFailOver(index))
        }
    }

    @Test
    fun `HTTP 416 归换源类并且必须作废断点`() {
        // 416 = 请求的 Range 起点越过远端对象末尾：原地退避只会把同一个越界请求撞五遍，
        // 正确处置是「作废 .part + 换下一条源从 0 来」。
        assertEquals(UpdateDownloadPlan.FailureKind.MissingOnMirror, UpdateDownloadPlan.httpStatusKindFor(416))
        assertEquals(UpdateDownloadPlan.FailureDecision.NextMirror, UpdateDownloadPlan.decideFailure(UpdateDownloadPlan.httpStatusKindFor(416)))
        assertTrue(UpdateDownloadPlan.mustDiscardPartForHttpStatus(416))
        // 其余「源上没有这段」类状态码同档（但不需要作废断点）。
        for (code in listOf(404, 403, 410)) {
            assertEquals("HTTP $code 应归换源类", UpdateDownloadPlan.FailureKind.MissingOnMirror, UpdateDownloadPlan.httpStatusKindFor(code))
            assertFalse("HTTP $code 不该作废断点", UpdateDownloadPlan.mustDiscardPartForHttpStatus(code))
        }
        // 临时错误仍走可退避的 HttpStatus（5xx / 429），且不碰断点。
        for (code in listOf(500, 502, 503, 504, 429)) {
            assertEquals("HTTP $code 应可退避", UpdateDownloadPlan.FailureKind.HttpStatus, UpdateDownloadPlan.httpStatusKindFor(code))
            assertTrue(UpdateDownloadPlan.retryOnSameMirror(UpdateDownloadPlan.httpStatusKindFor(code)))
            assertFalse("HTTP $code 不该作废断点", UpdateDownloadPlan.mustDiscardPartForHttpStatus(code))
        }
    }

    // ------------------------------------------------------------------
    // 空间闸：按清单声明 size 缩放，不再一刀切
    // ------------------------------------------------------------------

    @Test
    fun `余量随包缩放 钳在下限 64 MiB 与既有 512 MiB 之间`() {
        assertEquals(64L * mib, UpdateDownloadPlan.requiredHeadroomBytes(10L * mib))
        assertEquals(64L * mib, UpdateDownloadPlan.requiredHeadroomBytes(1L * mib))
        assertEquals(300L * mib, UpdateDownloadPlan.requiredHeadroomBytes(300L * mib))
        assertEquals(512L * mib, UpdateDownloadPlan.requiredHeadroomBytes(743L * mib))
        // 边界：恰好上下限。
        assertEquals(64L * mib, UpdateDownloadPlan.requiredHeadroomBytes(64L * mib))
        assertEquals(512L * mib, UpdateDownloadPlan.requiredHeadroomBytes(512L * mib))
    }

    @Test
    fun `差量小包不再被一刀切 512 MiB 余量挡在门外`() {
        val delta = artifact("eratw-assets-6to7.zip", 10L * mib)
        // 10 MiB 包 + 64 MiB 缩放余量：80 MiB 可用过线，73 MiB 不过线。
        assertTrue(UpdateDownloadPlan.spaceOk(usableBytes = 80L * mib, artifact = delta, part = null))
        assertFalse(UpdateDownloadPlan.spaceOk(usableBytes = 73L * mib, artifact = delta, part = null))
    }

    @Test
    fun `整包维持既有的 512 MiB 余量口径`() {
        val apk = artifact("eratw-0.2.40+9-full.apk", 680L * mib)
        // 680 + 512：1200 MiB 过线（余 520），1190 MiB 不过线（余 510）。
        assertTrue(UpdateDownloadPlan.spaceOk(1200L * mib, apk, null))
        assertFalse(UpdateDownloadPlan.spaceOk(1190L * mib, apk, null))
    }

    @Test
    fun `续传只按还差的字节判空间`() {
        val data = artifact("eratw-assets-40.zip", 680L * mib)
        // 无断点时 900 MiB 可用判不够（680+512 > 900）；
        // 已续传认领 300 MiB 前缀后只需再拉 380 MiB，同一块盘判够。
        assertFalse(UpdateDownloadPlan.spaceOk(900L * mib, data, null))
        val part = UpdateDownloadPlan.PartState(bytes = 300L * mib, ageMillis = 0L)
        assertEquals(380L * mib, UpdateDownloadPlan.remainingBytes(data, part))
        assertTrue(UpdateDownloadPlan.spaceOk(900L * mib, data, part))
    }

    @Test
    fun `整包空间闸把 29+ 安装会话的副本算进来`() {
        val apk = artifact("eratw-0.2.40+9-full.apk", 680L * mib)
        // 会话要把整包再拷一份进 /data/app-staging：余量 = 512 MiB 缩放刻度 + 680 MiB 副本。
        assertEquals(512L * mib + 680L * mib, UpdateDownloadPlan.requiredHeadroomBytes(680L * mib, 680L * mib))
        // 旧口径下 1200 MiB 可用是过线的（680+512），但会话副本还要 680 ⇒ 实际不够。
        assertFalse(
            "整包必须把会话副本算进空间闸（否则下完才在 openWrite 阶段失败）",
            UpdateDownloadPlan.spaceOk(1200L * mib, apk, null, sessionCopyBytes = 680L * mib),
        )
        // 1200 + 680：扣掉下载后仍够 680+512。
        assertTrue(UpdateDownloadPlan.spaceOk(1880L * mib, apk, null, sessionCopyBytes = 680L * mib))
        // 数据包不走会话，缺省 0 保持原口径。
        val zip = artifact("eratw-assets-40.zip", 680L * mib)
        assertTrue(UpdateDownloadPlan.spaceOk(1200L * mib, zip, null))
    }

    // ------------------------------------------------------------------
    // L4-5：下载完成后的整包验章
    // ------------------------------------------------------------------

    @Test
    fun `下载包实际签名与清单声明一致才通过 未声明跳过`() {
        val signerA = "signer-a-der".toByteArray()
        val signerB = "signer-b-der".toByteArray()
        assertTrue(
            "清单声明的是第二个 signer：多 signer 任一匹配即通过",
            UpdateDownloadPlan.apkCertSatisfied(sha256Hex(signerB), listOf(signerA, signerB)),
        )
        assertFalse(
            "清单声明的章不在包里：必须拦（防白下几百 MiB 再吃系统安装失败）",
            UpdateDownloadPlan.apkCertSatisfied(sha256Hex("other".toByteArray()), listOf(signerA)),
        )
        assertTrue("清单未声明（空串）= 跳过，不拦", UpdateDownloadPlan.apkCertSatisfied("", listOf(signerA)))
        assertTrue("清单声明坏形状 = 跳过，不拦", UpdateDownloadPlan.apkCertSatisfied("not-a-sha", listOf(signerA)))
        assertFalse("读不出包签名（空表）⇒ 不可接受", UpdateDownloadPlan.apkCertSatisfied(sha256Hex(signerA), emptyList()))
    }

    @Test
    fun `只有 apk 后缀的产物才走验章与安装会话口径`() {
        assertTrue(UpdateDownloadPlan.isApkArtifact("eratw-0.2.40+9-full-abcdef.apk"))
        assertFalse(UpdateDownloadPlan.isApkArtifact("eratw-assets-40.zip"))
        assertFalse(UpdateDownloadPlan.isApkArtifact("eratw-0.2.40.apk.part"))
    }

    // ------------------------------------------------------------------
    // 目录清理点名
    // ------------------------------------------------------------------

    @Test
    fun `清理只点名陈旧与不合法断点 合法续传点与清单产物不动`() {
        val keep = listOf(
            artifact("eratw-new.apk", 700L * mib),
            artifact("eratw-assets.zip", 500L * mib),
            artifact("eratw-empty", 10L),
            artifact("eratw-dup.zip", 9L),
        )
        val entries = listOf(
            // 当前清单的正式文件：保留（待安装/待应用的重试资产）。
            UpdateDownloadPlan.DirEntry("eratw-new.apk", 700L * mib, ageMillis = 1000L),
            // 合法可续传的 .part：保留（进程被回收后冷启接着来的本钱）。
            UpdateDownloadPlan.DirEntry("eratw-assets.zip.part", 120L * mib, ageMillis = 24L * 3600_000L),
            // 超期 .part：回收（续传不再有信任基础）。
            UpdateDownloadPlan.DirEntry("eratw-new.apk.part", 300L * mib, ageMillis = maxAge + 1L),
            // 空 .part（0 字节桩）：回收（下次反正重头写）。
            UpdateDownloadPlan.DirEntry("eratw-empty.part", 0L, ageMillis = 0L),
            // 与当前清单无关的旧 APK 残留（换版后）与无主 .part：回收。
            UpdateDownloadPlan.DirEntry("eratw-0.2.32.apk", 743L * mib, ageMillis = 1000L),
            UpdateDownloadPlan.DirEntry("eratw-old-assets.zip.part", 5L * mib, ageMillis = 1000L),
            // 正式文件已在、.part 还躺着：.part 冗余，回收；正式文件本身保留。
            UpdateDownloadPlan.DirEntry("eratw-dup.zip", 9L, ageMillis = 0L),
            UpdateDownloadPlan.DirEntry("eratw-dup.zip.part", 4L, ageMillis = 0L),
        )
        val doomed = UpdateDownloadPlan.planCleanup(entries, keep).toSet()
        assertEquals(
            setOf(
                "eratw-new.apk.part", // 超期
                "eratw-empty.part", // 0 字节空桩
                "eratw-0.2.32.apk", // 与当前清单无关的换版残留
                "eratw-old-assets.zip.part", // 无主 .part
                "eratw-dup.zip.part", // 正式文件已在，断点冗余
            ),
            doomed,
        )
        // 保留侧的显式断言：清单正式文件与合法断点不许进回收名单。
        assertFalse("eratw-new.apk" in doomed)
        assertFalse("eratw-assets.zip.part" in doomed)
        assertFalse("eratw-dup.zip" in doomed)
    }

    @Test
    fun `字节数超过声明的 part 被回收`() {
        val keep = listOf(artifact("data.zip", 100L))
        val entries = listOf(UpdateDownloadPlan.DirEntry("data.zip.part", 101L, ageMillis = 0L))
        assertEquals(listOf("data.zip.part"), UpdateDownloadPlan.planCleanup(entries, keep))
    }

    @Test
    fun `已登记待应用的暂存包按 extraKeepNames 保名`() {
        // 清单换代后，暂存指针里那件旧 zip 不在 keepArtifacts 里——不给保名就会被当残留删掉，
        // 「下次启动自动应用」变成「重下整包」。
        val staged = UpdateDownloadPlan.DirEntry("eratw-assets-39.zip", 500L * mib, ageMillis = 3600_000L)
        val keep = listOf(artifact("eratw-assets-40.zip", 500L * mib))
        assertEquals(
            listOf("eratw-assets-39.zip"),
            UpdateDownloadPlan.planCleanup(listOf(staged), keep),
        )
        assertTrue(
            UpdateDownloadPlan.planCleanup(listOf(staged), keep, extraKeepNames = setOf("eratw-assets-39.zip")).isEmpty(),
        )
    }

    @Test
    fun `后缀常量与 UpdateManager 的写盘口径同源`() {
        // .part 是续传认领与清理点名的唯一接头；两边漂移 = 断点永远认不出来。
        assertEquals(".part", UpdateDownloadPlan.PART_SUFFIX)
    }

    // ------------------------------------------------------------------
    // EP4 U1-③：失败分类与「玩家能做什么」的文案必须一一对得上（不许漏类、不许串话）
    // ------------------------------------------------------------------

    @Test
    fun `每个失败类别既有处置决策也有一句玩家面话`() {
        for (kind in UpdateDownloadPlan.FailureKind.values()) {
            // 判据面：每个类别都归得到一条处置路上（decideFailure 与 retryOnSameMirror 必须同答）。
            val retries = UpdateDownloadPlan.retryOnSameMirror(kind) ==
                (UpdateDownloadPlan.decideFailure(kind) == UpdateDownloadPlan.FailureDecision.RetrySameMirror)
            assertTrue("类别 $kind 的决策自相矛盾", retries)
            // 文案面：新增类别若忘了配句子，这里就红（本层不许 default 兜底）。
            assertTrue("类别 $kind 没有玩家面处置句", UpdateProgressText.failureAction(kind, "").isNotBlank())
        }
        assertEquals(
            UpdateDownloadPlan.FailureKind.values().size,
            UpdateDownloadPlan.FailureKind.values().map { UpdateProgressText.failureAction(it, "") }.distinct().size,
        )
    }

    @Test
    fun `值得同一条地址重试的类别才叫玩家等一会儿再来`() {
        for (kind in UpdateDownloadPlan.FailureKind.values()) {
            val action = UpdateProgressText.failureAction(kind, "")
            if (UpdateDownloadPlan.retryOnSameMirror(kind)) {
                // 只有「再发一次可能不同」的那几类（Network / HttpStatus，UpdateDownloadPlan.kt:103-106）
                // 才许叫玩家稍后再来一遍。
                assertTrue("类别 $kind 的退避语义与话术脱节：$action", action.contains("再试一次"))
            } else {
                // 换镜像类：要么换地址再试、要么等下次发版，不许写成「等十几分钟再来」这种空承诺。
                assertTrue("类别 $kind 的处置没落地：$action", action.contains("再试") || action.contains("发版"))
            }
        }
    }

    @Test
    fun `磁盘不足与本机读写故障同类别但不同句话`() {
        // 空间闸与读写失败都归 LocalFileSystem（UpdateManager.kt:482-496 与 :546/559/639），
        // 但处置人完全不同：清空间 vs 重启/清目录。分类不动、文案分开。
        val space = UpdateProgressText.failureAction(
            UpdateDownloadPlan.FailureKind.LocalFileSystem,
            "空间不够：app.apk 共 743 MiB，还要留 232 MiB 余量，盘上只剩 10 MiB",
        )
        val io = UpdateProgressText.failureAction(UpdateDownloadPlan.FailureKind.LocalFileSystem, "无法落盘：app.apk")
        assertEquals(UpdateDownloadPlan.FailureDecision.NextMirror, UpdateDownloadPlan.decideFailure(UpdateDownloadPlan.FailureKind.LocalFileSystem))
        assertFalse(space == io)
    }

    // ------------------------------------------------------------------
    // L4-3：覆盖升级预检的本机签名章候选
    // ------------------------------------------------------------------

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `本机章取当前 signer 与旋转历史最后一枚 历史第一枚不参与`() {
        val old = "old-cert-der".toByteArray()
        val current = "current-cert-der".toByteArray()
        // 旋转历史顺序：原始证书 index 0，当前证书最后一枚。
        val candidates = UpdateDownloadPlan.signerCertSha256Candidates(
            currentSignerDers = listOf(current),
            historyDers = listOf(old, current),
        )
        assertEquals("当前章必须排第一（localCertSha256 取 first）", sha256Hex(current), candidates.first())
        assertTrue("当前章必须在候选里", sha256Hex(current) in candidates)
        assertFalse("最旧的章不该进候选（旧实现取 first 的病根）", sha256Hex(old) in candidates)
        assertEquals("当前章重复出现要去重", 1, candidates.size)
    }

    @Test
    fun `多 signer 任一匹配即不拦 全不匹配才拦`() {
        val signerA = "signer-a".toByteArray()
        val signerB = "signer-b".toByteArray()
        val declared = sha256Hex(signerB)
        val candidates = UpdateDownloadPlan.signerCertSha256Candidates(listOf(signerA, signerB), emptyList())
        assertEquals(2, candidates.size)
        assertFalse("清单声明的是第二个 signer：任一匹配即不拦", UpdateDownloadPlan.certBlocksInstallAny(candidates, declared))
        assertTrue("清单声明的是第三方章：拦", UpdateDownloadPlan.certBlocksInstallAny(candidates, sha256Hex("other".toByteArray())))
    }

    @Test
    fun `本机读不出或清单没声明章 一律不拦`() {
        val declared = sha256Hex("release".toByteArray())
        assertFalse("本机读不出（空集合）= 不拦", UpdateDownloadPlan.certBlocksInstallAny(emptyList(), declared))
        assertFalse(
            "清单没声明（空串/坏形状）= 不拦",
            UpdateDownloadPlan.certBlocksInstallAny(listOf(declared), ""),
        )
        assertFalse(
            "清单声明坏形状 = 不拦",
            UpdateDownloadPlan.certBlocksInstallAny(listOf(declared), "not-a-sha"),
        )
        assertFalse("本机空串也要被归一掉 = 不拦", UpdateDownloadPlan.certBlocksInstallAny(listOf(""), declared))
    }
}
