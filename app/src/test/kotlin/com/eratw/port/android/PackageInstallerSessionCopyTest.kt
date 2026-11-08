package com.eratw.port.android

import android.content.pm.PackageInstaller
import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U7b「安装回执 17 键入表」的专属闸（CONTRACT-UPD2 §2 U7 的文案收口，2026-11-05）。
 *
 * 分工：共享形状闸（[CopyShapeGate]）与 [UpdateChannelReadoutTest] 的 copyKeys 钉**全表**
 * 形状与本清单计数；这边只钉 installXxx 一族 17 条的**内容对拍**：
 *
 * 1. **中文零漂移**：[UpdateCopy.ZH] 的 17 个值与 U7 收口前 `PackageInstallerSession.kt`
 *    的内联原句逐字相同（AGENTS.md §6.1 搬表不许动一个字的玩家话术）。**唯一偏差**是
 *    U6b 去掉通用失败档的双层前缀（「安装失败：安装失败（错误码 N）」→「安装失败：原因不明
 *    （错误码 N）」），那条用例同时钉错误码读数没丢；
 * 2. **分类判定与语言无关**：`summaryOf` 在三档各取到**本档表里的那句**（同一状态码换档换句，
 *    词源在表、不在判读函数）；未知码走 installGenericFailedFormat 的 {0} 槽；
 * 3. **系统原话套壳**：原话原样附带不翻译，三语槽序同为「摘要、原话」；
 * 4. **兼容口已删**（U6b）：不带 copy 的 `of` 会代进中文档，等于「三语表是装饰」——
 *    [PackageInstallerSessionOutcomeTest] 改成显式喂 copy 之后这一口由本波删除，
 *    这里改钉「三档各取本档那句 + 三档两两不同文 + 失败档带原始状态码」；
 * 5. **术语面**：17 条 × 三档过玩家面禁词表（与 UpdateNotifyCopyTest 同名单）。
 *
 * 形状四件套（非空两两不同 / 槽位换行同形 / 灌满无残留 / 语言自证）直接走共享闸。
 */
class PackageInstallerSessionCopyTest {

    private val copies = listOf(UpdateCopy.ZH, UpdateCopy.EN, UpdateCopy.JA)

    private val installKeys: List<Pair<String, KProperty1<UpdateCopy, String>>> = listOf(
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
    )

    private val installMap: Map<String, KProperty1<UpdateCopy, String>> = installKeys.toMap()

    // ------------------------------------------------------------------
    // 共享形状闸（U7b 的 17 条与 U3b 的 54 条同样过五道判据）
    // ------------------------------------------------------------------

    @Test
    fun `安装回执 17 键三档非空且两两不同`() {
        assertEquals("U7b 入表的键数变了", 17, installKeys.size)
        CopyShapeGate.assertNonEmptyAndDistinct(copies, installMap)
    }

    @Test
    fun `安装回执 17 键槽位序列与换行数三档同形`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, installMap)
    }

    @Test
    fun `安装回执 17 键灌满读数后不留槽位`() {
        CopyShapeGate.assertNoSlotResidue(copies, installMap) { copy, template, n ->
            copy.fill(template, *Array(n) { index -> "reading$index" })
        }
    }

    @Test
    fun `安装回执 17 键语言自证（英文无汉字、中文像底稿、日文不是英文句子）`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, installMap)
    }

    @Test
    fun `安装回执 17 键玩家面三档都不许出现术语`() {
        val banned = listOf(
            "镜像", "清单", "摘要", "sha", "SHA", "Range", "range", "versionCode", "HTTP", "http",
            "mirror", "manifest", "digest",
        )
        installKeys.forEach { (name, property) ->
            copies.forEach { copy ->
                val value = property(copy)
                banned.forEach { term ->
                    assertFalse("$name（${copy.lang}）出现术语「$term」：$value", value.contains(term))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 1. 中文零漂移：与 U7 收口前的内联原句逐字对拍（唯一偏差 = 双前缀修正，见下面那条用例）
    // ------------------------------------------------------------------

    @Test
    fun `中文档与收口前的内联原句逐字相同`() {
        val zh = UpdateCopy.ZH
        assertEquals("更新安装完成", zh.installDoneTitle)
        assertEquals("新版本已经装好了", zh.installDoneLine)
        assertEquals("安装已取消", zh.installCancelledTitle)
        assertEquals("你取消了安装。更新包还留着，点这条通知可以再装一次", zh.installCancelledLine)
        assertEquals("更新安装没成功", zh.installFailedTitle)
        assertEquals("安装失败：{0}", zh.installFailedBodyFormat)
        assertEquals("安装会话没跑起来：{0}", zh.installSessionOpenFailedFormat)
        assertEquals("安装会话没跑起来：确认框没拉起来（{0}），点这条通知可以再试", zh.installConfirmOpenFailedFormat)
        assertEquals("安装包不在更新目录里或名字不合法，请回游戏重新下载", zh.installPathInvalidLine)
        assertEquals("安装被系统或设备策略拦下：看看是不是装了设备管理或安全软件在挡", zh.installBlockedLine)
        assertEquals("与设备上已装的应用冲突（签名不同）：要先卸载旧包才能装——卸载会清掉存档，请先备份", zh.installConflictLine)
        assertEquals(
            "与这台设备不兼容，或与已装包的签名不一致：覆盖升级要同一签名，先卸载旧包会清掉存档，请先备份",
            zh.installIncompatibleLine,
        )
        assertEquals("安装包无效或已损坏：回游戏里重新下载一次再装", zh.installInvalidLine)
        assertEquals("存储空间不足：请腾出一些空间后再试", zh.installStorageLine)
        assertEquals("安装超时：请再试一次", zh.installTimeoutLine)
        // U6b 的唯一偏差：这句原先写作「安装失败（错误码 {0}）」，套进外层前缀后屏幕上出的是
        // 「安装失败：安装失败（错误码 1）」。去掉内层重复的那半句，错误码原样留着（排查要用）。
        assertEquals("原因不明（错误码 {0}）", zh.installGenericFailedFormat)
        assertEquals("{0}（系统原话：{1}）", zh.installSystemMessageFormat)
    }

    @Test
    fun `通用失败码的通知正文不再叠两层同一句前缀 但错误码不许丢`() {
        copies.forEach { copy ->
            val summary = InstallSessionOutcome.summaryOf(PackageInstaller.STATUS_FAILURE, null, copy)
            val body = copy.fill(copy.installFailedBodyFormat, summary)
            // 外层前缀只出现一次：把正文里「安装失败」那半句的出现次数数死（三语各数自己的那句）。
            val prefix = copy.fill(copy.installFailedBodyFormat, "").trimEnd { it.isWhitespace() || it == '：' || it == ':' }
            val occurrences = Regex(Regex.escape(prefix)).findAll(body).count()
            assertEquals("${copy.lang} 的失败正文叠了前缀：$body", 1, occurrences)
            // 「错误码 N」这类排查读数一个字都不许弄丢。
            assertTrue("${copy.lang} 的失败正文丢了状态码：$body", body.contains(PackageInstaller.STATUS_FAILURE.toString()))
            assertFalse("${copy.lang} 的正文里摘要句自己又以失败前缀开头：$summary", summary.startsWith(prefix))
        }
    }

    // ------------------------------------------------------------------
    // 2. 分类判定与语言无关：摘要句的词源在表里
    // ------------------------------------------------------------------

    @Test
    fun `失败摘要在三档各取到本语言表里的那句`() {
        val tableByStatus = listOf(
            PackageInstaller.STATUS_FAILURE_BLOCKED to UpdateCopy::installBlockedLine,
            PackageInstaller.STATUS_FAILURE_CONFLICT to UpdateCopy::installConflictLine,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE to UpdateCopy::installIncompatibleLine,
            PackageInstaller.STATUS_FAILURE_INVALID to UpdateCopy::installInvalidLine,
            PackageInstaller.STATUS_FAILURE_STORAGE to UpdateCopy::installStorageLine,
            PackageInstaller.STATUS_FAILURE_TIMEOUT to UpdateCopy::installTimeoutLine,
        )
        tableByStatus.forEach { (status, key) ->
            copies.forEach { copy ->
                assertEquals(
                    "${copy.lang} 的状态码 $status 摘要没取本语言的表句",
                    key(copy),
                    InstallSessionOutcome.summaryOf(status, null, copy),
                )
            }
        }
    }

    @Test
    fun `通用与未知失败码走 installGenericFailedFormat 的槽`() {
        copies.forEach { copy ->
            assertEquals(
                "${copy.lang} 的通用失败摘要与表模板灌码不一致",
                copy.fill(copy.installGenericFailedFormat, PackageInstaller.STATUS_FAILURE),
                InstallSessionOutcome.summaryOf(PackageInstaller.STATUS_FAILURE, null, copy),
            )
            val unknown = InstallSessionOutcome.summaryOf(42, null, copy)
            assertEquals("${copy.lang} 的未知码没带状态码读数", copy.fill(copy.installGenericFailedFormat, 42), unknown)
        }
    }

    @Test
    fun `系统原话原样附带不翻译，空白则整套壳都不出现`() {
        copies.forEach { copy ->
            val base = copy.installStorageLine
            assertEquals(
                "${copy.lang} 的原话套壳与表模板不一致",
                copy.fill(copy.installSystemMessageFormat, base, "not enough space"),
                InstallSessionOutcome.summaryOf(PackageInstaller.STATUS_FAILURE_STORAGE, "not enough space", copy),
            )
            // 原话本体必须逐字在句子里（取证面，不许被本地化改写）。
            val withMessage = InstallSessionOutcome.summaryOf(PackageInstaller.STATUS_FAILURE_STORAGE, "not enough space", copy)
            assertTrue("${copy.lang} 的套壳弄丢了原话：$withMessage", withMessage.contains("not enough space"))
            val blank = InstallSessionOutcome.summaryOf(PackageInstaller.STATUS_FAILURE_STORAGE, "   ", copy)
            assertEquals("${copy.lang} 的空白原话不该套壳", base, blank)
        }
    }

    @Test
    fun `of 的三参口在三档各取到本语言的那句`() {
        // 不带 copy 的中文兼容口（U7b 为旧测试留下的）已删：词源只剩表这一条路，
        // 「默认中文」留着就等于三语表是装饰 —— 这里钉每一档拿到的都是**本档**的那句。
        val statuses = listOf(
            PackageInstaller.STATUS_SUCCESS,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE,
            42,
        )
        statuses.forEach { status ->
            copies.forEach { copy ->
                val outcome = InstallSessionOutcome.of(status, "raw", copy)
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    assertEquals(InstallSessionOutcome.Success, outcome)
                } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    assertTrue("${copy.lang} 的 $status 该判用户取消", outcome is InstallSessionOutcome.UserCancelled)
                } else {
                    val failed = outcome as InstallSessionOutcome.Failed
                    assertEquals(
                        "${copy.lang} 的状态码 $status 取到了别一档语言的句子",
                        InstallSessionOutcome.summaryOf(status, "raw", copy),
                        failed.summary,
                    )
                    assertEquals(
                        "失败档把原始状态码弄丢了（漏斗那一拍要它）：$status",
                        status,
                        failed.status,
                    )
                }
            }
        }
        // 三档同一码出三句：任何两档逐字相同就是漏翻（兼容口时代这种「大家都说中文」的错看不出来）。
        val triple = copies.map { (InstallSessionOutcome.of(42, null, it) as InstallSessionOutcome.Failed).summary }
        assertEquals("未知码的摘要三档出现同文：$triple", 3, triple.distinct().size)
    }

    @Test
    fun `送达面的正文套壳与标题都取自表（失败通知 body 形态对拍）`() {
        copies.forEach { copy ->
            val summary = InstallSessionOutcome.summaryOf(PackageInstaller.STATUS_FAILURE_INVALID, null, copy)
            assertEquals(
                "${copy.lang} 的失败通知正文不是表模板灌摘要",
                copy.fill(copy.installFailedBodyFormat, summary),
                copy.fill(copy.installFailedBodyFormat, copy.installInvalidLine),
            )
        }
    }
}
