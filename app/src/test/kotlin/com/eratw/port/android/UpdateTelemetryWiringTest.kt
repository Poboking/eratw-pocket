package com.eratw.port.android

import android.content.pm.PackageInstaller
import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.UpdateTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 更新漏斗遥测**接线层**的定向单测（U6，P1-4 收尾）。
 *
 * ## 被测形状为什么是「纯装配 + 真件组合 + 源码面」三半
 *
 * 六个接线点全在挂在 Android 运行时上的对象里（[UpdateUi] 有 `Handler(Looper.getMainLooper())`、
 * [UpdateDownloadService] 是 `Service`、[GameHost] 有 `View`），app 的 JVM 单测面没有
 * Robolectric 就实例化不了 —— 本仓对这个形状的既有先例是 [LiveReflowWiringTest]
 * （判据直测 + 源码面钉子）与 [ErrorReportThrottleTest]（同一套路子）。这里三段都用：
 * - **装配**（哪一拍拼哪些维度、坏读数怎么退化）走 [UpdateTelemetryEvents] 直测，
 *   并拿 core 自己的 `validate` 当第二双眼：接线层产出的每条事件都必须过 core 的闸；
 * - **两条硬闸**（通道关零出网、异常不外逃）拿真 [UpdateTelemetry] + 真 [UpdateTelemetrySink]
 *   + 假 [UpdateTelemetrySink.Transport] 组合跑：假传输口一次没被调就是「一个字节都没出网」，
 *   假 storage 一次没被写就是「关着连盘都不留」；
 * - **接线点在不在**走源码面：漏接一拍在屏上没有任何表现，只有聚合表少一格，
 *   这种「静默少发」只能由源码面钉子挡（见 [六个接线点全在册]）。
 *
 * 事件全程零设备标识：本文件出现的维读数只有版本号、字节数、类别标签与布尔档位。
 *
 * **U6b 追加**：core 开了 `install_result`（安装终态，outcome / status 两维）之后，第 6 拍的
 * 装配、编码、通道关零出网、主线程中继，以及它在 `PackageInstallerSession.kt` 送达面的接线点
 * 一并进本文件名单（见 [编码形状第6拍_安装终态的outcome与status] 与 [六个接线点全在册] 的末段）。
 */
class UpdateTelemetryWiringTest {

    private companion object {
        const val TELEMETRY_ENDPOINT = "https://eratw.example/report"
        const val TELEMETRY_TOKEN = "sekret"
    }


    // ==================================================================
    // 假件与装置
    // ==================================================================

    /** 记录每次 save 的快照；[throwOnSave] 用来钉「落盘抛了也不许外逃」。 */
    private class FakeStorage : UpdateTelemetry.Storage {
        val saved: MutableList<List<String>> = mutableListOf()
        var lines: List<String> = emptyList()
        var throwOnSave = false
        override fun load(): List<String> = lines
        override fun save(lines: List<String>) {
            if (throwOnSave) throw IllegalStateException("盘写不进去")
            saved.add(lines.toList())
            this.lines = lines.toList()
        }
    }

    /** 出站面唯一判据：calls 为空 = 一个字节都没发出去。 */
    private class FakeTransport(var result: Boolean = true) : UpdateTelemetrySink.Transport {
        val calls: MutableList<Triple<String, String, String>> = mutableListOf()
        override fun post(endpoint: String, token: String, body: String): Boolean {
            calls.add(Triple(endpoint, token, body))
            return result
        }
    }

    /** 取证面假件；[throwOnLog] 钉「连记日志都炸了也不许外逃」。 */
    private class FakeLog {
        val lines: MutableList<Pair<String, Throwable?>> = mutableListOf()
        var throwOnLog = false
        fun write(message: String, error: Throwable?) {
            if (throwOnLog) throw IllegalStateException("日志面炸了")
            lines.add(message to error)
        }
    }


    /**
     * 真 core + 真 sink + 假传输口 + 假盘：三件拼起来才是「关态零出网」这条判据的完整链路。
     *
     * [clock] 取的是**早于真实当下**的定值：接线层的事件时间戳是真实时钟，两者之差为负，
     * 于是 core 的 48 小时年龄闸在下面的用例里恒不命中 —— 想钉的只有「通道」「线程」两闸。
     * [mainThreadAtBuild] 是出生档位，运行期由 [Harness.mainThread] 切档：同一只桥先在主线程收、
     * 再换到后台冲刷，钉的正是中继那条路。
     */
    private class Harness(
        channelEnabled: Boolean = true,
        mainThreadAtBuild: Boolean = false,
        val storageField: FakeStorage = FakeStorage(),
        val transportField: FakeTransport = FakeTransport(),
        val logField: FakeLog = FakeLog(),
        clock: () -> Long = { 1_700_000_000_000L },
    ) {
        var enabled = channelEnabled

        /** 可切的档位：**属性名与构造参数必须不同名**，否则 lambda 捕获的是参数（定值），切档不生效。 */
        var mainThread = mainThreadAtBuild
        private val telemetry = UpdateTelemetry(
            storageField,
            clock,
            telemetrySendPort(TELEMETRY_ENDPOINT, TELEMETRY_TOKEN, transportField) { mainThread },
        )
        val bridge = UpdateTelemetryBridge(telemetry, { enabled }, { mainThread }, logField::write)
    }

    /** core 的 validate 是第二双眼：接线层产出的事件必须一条都不被 core 拒。 */
    private fun assertAccepted(event: UpdateTelemetry.Event, why: String) {
        val problem = UpdateTelemetry(FakeStorage(), { 100L }, { true }).validate(event)
        assertNull("$why：core 拒了这条（$problem）：$event", problem)
    }

    // ==================================================================
    // 1. 装配层：每一拍的维度
    // ==================================================================

    @Test
    fun 六个接线点产出的事件全部过闸() {
        val events = listOf(
            UpdateTelemetryEvents.checkStarted(17L),
            UpdateTelemetryEvents.checkOk(17L),
            UpdateTelemetryEvents.checkFailed(UpdateChannelReadout.OUTCOME_UNREACHABLE),
            UpdateTelemetryEvents.offerShown(UpdateTelemetry.KIND_APK, 18L, force = false),
            UpdateTelemetryEvents.offerShown(UpdateTelemetry.KIND_ASSETS, 300L, force = true),
            UpdateTelemetryEvents.offerAction(UpdateTelemetry.ACTION_DOWNLOAD, 18L),
            UpdateTelemetryEvents.offerAction(UpdateTelemetry.ACTION_SKIP, 18L),
            UpdateTelemetryEvents.offerAction(UpdateTelemetry.ACTION_DECLINE, 18L),
            UpdateTelemetryEvents.downloadStarted(UpdateDownloadKind.Apk, 0L),
            UpdateTelemetryEvents.downloadStarted(UpdateDownloadKind.Assets, 300L),
            UpdateTelemetryEvents.downloadFinished(UpdateDownloadKind.Assets, 700L, 300L),
            UpdateTelemetryEvents.downloadCancelled(UpdateDownloadKind.Apk, 0L),
            UpdateTelemetryEvents.downloadFailed(
                UpdateDownloadKind.Apk,
                123L,
                UpdateDownloadPlan.FailureKind.DigestMismatch,
                0L,
            ),
            UpdateTelemetryEvents.installRequested(17L),
            UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_SUCCESS, null, 17L),
            UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_CANCELLED, null, 17L),
            UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, PackageInstaller.STATUS_FAILURE_CONFLICT, 17L),
            // 第 7 拍（U10）：Done / Failed 两格的装配形状各一条进过闸清单。
            UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.FULL, null),
            UpdateTelemetryEvents.assetsApplied(
                null,
                UpdateTelemetry.OUTCOME_FAILED,
                null,
                UpdateManager.ApplyOutcome.FailureCategory.CRASHED_RETRYING.label,
            ),
        )
        assertEquals(19, events.size)
        for ((index, event) in events.withIndex()) {
            assertAccepted(event, "接线层第 ${index + 1} 拍")
            assertTrue("时间戳由接线层现取，不许是 0 或负", event.atMillis > 0L)
        }
    }

    @Test
    fun 编码形状逐拍点名() {
        val core = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        // 时间戳那一维由接线层现取时钟，钉形状时统一换成定值再比（其余维一字不动）。
        fun pinned(event: UpdateTelemetry.Event) = core.encode(event.copy(atMillis = 1_700_000_000_000L))
        fun line(name: String, version: Long?, kind: String?, size: Long?, force: Boolean?, failure: String?) =
            core.encode(UpdateTelemetry.Event(name, 1_700_000_000_000L, version, kind, size, force, failure))

        assertEquals(line("check_started", 17L, null, null, null, null), pinned(UpdateTelemetryEvents.checkStarted(17L)))
        assertEquals(line("check_ok", 17L, null, null, null, null), pinned(UpdateTelemetryEvents.checkOk(17L)))
        assertEquals(
            line("check_failed", null, null, null, null, "unreachable"),
            pinned(UpdateTelemetryEvents.checkFailed(UpdateChannelReadout.OUTCOME_UNREACHABLE)),
        )
        assertEquals(
            line("offer_shown", 18L, "apk", null, false, null),
            pinned(UpdateTelemetryEvents.offerShown("apk", 18L, force = false)),
        )
        assertEquals(
            line("offer_action", 18L, "download", null, null, null),
            pinned(UpdateTelemetryEvents.offerAction(UpdateTelemetry.ACTION_DOWNLOAD, 18L)),
        )
        assertEquals(
            line("download_started", 300L, "assets", null, null, null),
            pinned(UpdateTelemetryEvents.downloadStarted(UpdateDownloadKind.Assets, 300L)),
        )
        assertEquals(
            line("download_finished", null, "assets", 700L, null, null),
            pinned(UpdateTelemetryEvents.downloadFinished(UpdateDownloadKind.Assets, 700L, 0L)),
        )
        assertEquals(
            line("download_cancelled", null, "apk", null, null, null),
            pinned(UpdateTelemetryEvents.downloadCancelled(UpdateDownloadKind.Apk, 0L)),
        )
        assertEquals(
            line("download_failed", 300L, "assets", 123L, null, "DigestMismatch"),
            pinned(
                UpdateTelemetryEvents.downloadFailed(
                    UpdateDownloadKind.Assets,
                    123L,
                    UpdateDownloadPlan.FailureKind.DigestMismatch,
                    300L,
                ),
            ),
        )
        assertEquals(
            line("install_requested", 17L, null, null, null, null),
            pinned(UpdateTelemetryEvents.installRequested(17L)),
        )
        // 第 7 拍（U10）带 outcome/kind/failure 三维后走字面串（line() 没有这三维的形参）。
        assertEquals(
            """{"event":"assets_applied","at":1700000000000,"version":300,"kind":"full","outcome":"success"}""",
            pinned(UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.FULL, null)),
        )
    }

    @Test
    fun 编码形状第6拍_安装终态的outcome与status() {
        val core = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        fun pinned(event: UpdateTelemetry.Event) = core.encode(event.copy(atMillis = 1_700_000_000_000L))
        assertEquals(
            """{"event":"install_result","at":1700000000000,"version":17,"outcome":"success"}""",
            pinned(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_SUCCESS, null, 17L)),
        )
        assertEquals(
            """{"event":"install_result","at":1700000000000,"version":17,"outcome":"cancelled"}""",
            pinned(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_CANCELLED, null, 17L)),
        )
        assertEquals(
            """{"event":"install_result","at":1700000000000,"version":17,"outcome":"failed","status":"5"}""",
            pinned(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, PackageInstaller.STATUS_FAILURE_CONFLICT, 17L)),
        )
        // 未知码与负数码都是「一个短读数」，core 收得下；版本号读不出来（-1）时那一整个维不出现。
        assertEquals(
            """{"event":"install_result","at":1700000000000,"outcome":"failed","status":"-99"}""",
            pinned(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, -99, -1L)),
        )
        assertAccepted(
            UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, -99, -1L),
            "读不出版本号的 install_result",
        )
    }

    @Test
    fun 第6拍的非失败档把状态码丢掉而不是把整条事件丢掉() {
        // 接线错位（给成功档也塞了个 0）在装配层就归一掉：outcome 才是这一拍要数的东西。
        val success = UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_SUCCESS, 0, 17L)
        assertNull(success.status)
        assertAccepted(success, "成功档误带状态码的 install_result")
        val cancelled = UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_CANCELLED, PackageInstaller.STATUS_FAILURE_ABORTED, 17L)
        assertNull(cancelled.status)
        assertAccepted(cancelled, "取消档误带状态码的 install_result")
        // 失败档没码（会话自己开不起来那一类）：照常记，只是少一维。
        val noStatus = UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, null, 17L)
        assertNull(noStatus.status)
        assertAccepted(noStatus, "会话开不起来那一类 install_result")
    }

    @Test
    fun 坏读数退化成不带维而不是把整条事件丢掉() {
        // currentVersionCode 读失败是 -1、assetsVersion 缺省是 0：这两种「没读数」都不是版本号。
        assertNull(UpdateTelemetryEvents.versionOrNone(-1L))
        assertNull(UpdateTelemetryEvents.versionOrNone(0L))
        assertNull(UpdateTelemetryEvents.versionOrNone(null))
        assertEquals(17L, UpdateTelemetryEvents.versionOrNone(17L))

        val noVersion = UpdateTelemetryEvents.checkStarted(-1L)
        assertNull(noVersion.version)
        assertAccepted(noVersion, "读不出版本号的 check_started")

        val appliedNoVersion = UpdateTelemetryEvents.assetsApplied(0L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.FILE_DELTA, null)
        assertNull(appliedNoVersion.version)
        assertAccepted(appliedNoVersion, "版本号读不出的 assets_applied")

        // 字节数读不出（负数）同样退化成不带维，而不是记一条「大小 -1」。
        assertNull(UpdateTelemetryEvents.downloadFinished(UpdateDownloadKind.Apk, -1L, 0L).sizeBytes)
    }

    @Test
    fun 失败类别非空白且不超长() {
        assertEquals("unreachable", UpdateTelemetryEvents.failureLabel(UpdateChannelReadout.OUTCOME_UNREACHABLE))
        assertEquals("no-mirror", UpdateTelemetryEvents.failureLabel(UpdateChannelReadout.OUTCOME_NO_MIRROR))
        assertEquals("crashed", UpdateTelemetryEvents.failureLabel(UpdateChannelReadout.OUTCOME_CRASHED))
        assertEquals("unknown", UpdateTelemetryEvents.failureLabel(null))
        assertEquals("unknown", UpdateTelemetryEvents.failureLabel("   "))
        assertEquals("exhausted", UpdateTelemetryEvents.failureLabel(null, UpdateTelemetryEvents.FAILURE_EXHAUSTED))
        // 空白 fallback 也不能把出口做成空白（core 对 check_failed 的 failure 是必填且非空白）。
        assertEquals("unknown", UpdateTelemetryEvents.failureLabel(null, "  "))
        val clipped = UpdateTelemetryEvents.failureLabel("ForegroundServiceDidNotStartInTimeException" + "x".repeat(40))
        assertEquals(UpdateTelemetry.MAX_DIM_CHARS, clipped.length)
        assertAccepted(UpdateTelemetryEvents.checkFailed(clipped), "截断后的失败类别")
    }

    @Test
    fun 弹窗类别跟着单子的超集规则() {
        val sha = "a".repeat(64)
        val apkJob = DownloadJob(Artifact("eratw-0.9.9.apk", 700L, sha), UpdateDownloadKind.Apk, "整包", 0L)
        val assetsJob = DownloadJob(Artifact("eratw-data-300.zip", 680L, sha), UpdateDownloadKind.Assets, "数据包", 300L)
        assertEquals("apk", UpdateTelemetryEvents.offerKind(apkJob, apkNeeded = true, assetsNeeded = true))
        assertEquals("assets", UpdateTelemetryEvents.offerKind(assetsJob, apkNeeded = false, assetsNeeded = true))
        // jobFor 给不出单子（章不合把整包一票否决、又没数据可下）：这一拍仍要有合法 kind。
        assertEquals("apk", UpdateTelemetryEvents.offerKind(null, apkNeeded = true, assetsNeeded = false))
        assertEquals("assets", UpdateTelemetryEvents.offerKind(null, apkNeeded = false, assetsNeeded = true))
        assertEquals("assets", UpdateTelemetryEvents.offerKind(null, apkNeeded = false, assetsNeeded = false))
        // 版本维跟类别走：整包看 versionCode，数据包看 data-version。
        assertEquals(18L, UpdateTelemetryEvents.offerVersion("apk", 18L, 300L))
        assertEquals(300L, UpdateTelemetryEvents.offerVersion("assets", 18L, 300L))
        assertNull(UpdateTelemetryEvents.offerVersion("assets", 18L, 0L))
        assertNull(UpdateTelemetryEvents.offerVersion("apk", null, 300L))
    }

    @Test
    fun 下载失败类别只有现成判据() {
        val withKind = UpdateTelemetryEvents.downloadFailed(
            UpdateDownloadKind.Assets,
            10L,
            UpdateDownloadPlan.FailureKind.Network,
            300L,
        )
        assertEquals("Network", withKind.failure)
        // 「所有候选地址都试完」那一支不带类别（UpdateManager 的 DownloadOutcome.kind 为 null）。
        assertEquals(UpdateTelemetryEvents.FAILURE_EXHAUSTED, UpdateTelemetryEvents.downloadFailed(UpdateDownloadKind.Apk, 10L, null, 0L).failure)
        // 下载线程自己抛出来的那一类：调用点把异常名交给 fallback，不与「地址试完」混成同一条。
        val crashed = UpdateTelemetryEvents.downloadFailed(UpdateDownloadKind.Apk, 0L, null, 0L, "OutOfMemoryError")
        assertEquals("OutOfMemoryError", crashed.failure)
        assertEquals(0L, crashed.sizeBytes)
        assertAccepted(crashed, "线程异常收场的 download_failed")
    }

    @Test
    fun 第7拍装配_成功格带档位_失败格交枚举短标签() {
        // 成功格：kind 经 core 的唯一映射落标签（映射真相在 core，app 侧没有第二份转小写的派）。
        val labels = mapOf(
            DataArtifactKind.BLOCK_DELTA to "block_delta",
            DataArtifactKind.FILE_DELTA to "file_delta",
            DataArtifactKind.FULL to "full",
        )
        for ((kind, label) in labels) {
            val event = UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, kind, null)
            assertEquals(label, event.kind)
            assertNull("成功格不带 failure 维", event.failure)
            assertAccepted(event, "成功格档位 $label")
        }
        // 成功格误传 failure：丢掉那一维而不是丢掉整条事件（第 6 拍同一口径）。
        val successWithFailure = UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.FULL, "rejected")
        assertNull(successWithFailure.failure)
        assertAccepted(successWithFailure, "成功格误传 failure")
        // NONE 档没有标签：core 对成功档缺 kind 整条拒 —— 「NONE 不发这一拍」的挡点在 core，
        // 这条事件不该被发出来，真发出来的表现就是「这一拍没记上」。
        val core = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        val none = UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.NONE, null)
        assertNull(none.kind)
        assertNotNull("NONE 档的成功拍居然被 core 收下", core.validate(none))
        // 失败格：三类真实出口（拒装 / 抛错待重试 / 抛错放弃）逐档过闸；没有「生效的版本」，
        // version 整个不带；kind 也缺（失败时档位没探出来是合法形状）。
        for (category in UpdateManager.ApplyOutcome.FailureCategory.values()) {
            val event = UpdateTelemetryEvents.assetsApplied(null, UpdateTelemetry.OUTCOME_FAILED, null, category.label)
            assertNull(event.version)
            assertNull(event.kind)
            assertEquals(category.label, event.failure)
            assertAccepted(event, "失败格类别 ${category.label}")
        }
    }

    @Test
    fun 第7拍失败类别不泄漏自由文本() {
        val core = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        // problem 原话（可能带路径与文件名）灌进失败格：core 的 isStatusTag 整条拒 ——
        // 表现是「这一拍没记上」，绝不是被清洗后照收；漏斗里只有枚举短标签。
        val problem = UpdateTelemetryEvents.assetsApplied(
            null,
            UpdateTelemetry.OUTCOME_FAILED,
            null,
            "块级差量包里有 3 条非法路径：/data/user/0/eratw/files/a.zip",
        )
        val problemRejected = core.validate(problem)
        assertNotNull("problem 原话居然被 core 收下：$problemRejected", problemRejected)
        // 通用那一档（空白）也不许过：failureLabel 归一成 unknown，拍不丢、原话也不进。
        assertEquals("unknown", UpdateTelemetryEvents.assetsApplied(null, UpdateTelemetry.OUTCOME_FAILED, null, "  ").failure)
        // 枚举三档 label 全在 isStatusTag 字符集内：真实接线（GameHost 交 category.label）恒被收。
        for (category in UpdateManager.ApplyOutcome.FailureCategory.values()) {
            assertNull(
                "类别 ${category.label} 被 core 拒了",
                core.validate(UpdateTelemetryEvents.assetsApplied(null, UpdateTelemetry.OUTCOME_FAILED, null, category.label)),
            )
        }
    }

    @Test
    fun 编码形状第7拍_应用终态的outcome与kind与failure() {
        val core = UpdateTelemetry(FakeStorage(), { 100L }, { true })
        fun pinned(event: UpdateTelemetry.Event) = core.encode(event.copy(atMillis = 1_700_000_000_000L))
        assertEquals(
            """{"event":"assets_applied","at":1700000000000,"version":300,"kind":"block_delta","outcome":"success"}""",
            pinned(UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.BLOCK_DELTA, null)),
        )
        // 失败档没有「生效的版本」，version 维整个不出现；failure 在 outcome 之前落（core 的固定字段序）。
        assertEquals(
            """{"event":"assets_applied","at":1700000000000,"failure":"gave-up","outcome":"failed"}""",
            pinned(
                UpdateTelemetryEvents.assetsApplied(
                    null,
                    UpdateTelemetry.OUTCOME_FAILED,
                    null,
                    UpdateManager.ApplyOutcome.FailureCategory.CRASHED_GAVE_UP.label,
                ),
            ),
        )
    }

    @Test
    fun 第7拍在后台档真的攒进批并出站() {
        val h = Harness()
        assertTrue(
            h.bridge.record(
                UpdateTelemetryEvents.assetsApplied(
                    null,
                    UpdateTelemetry.OUTCOME_FAILED,
                    null,
                    UpdateManager.ApplyOutcome.FailureCategory.REJECTED.label,
                ),
            ),
        )
        assertTrue(h.bridge.flush())
        assertEquals(1, h.transportField.calls.size)
        val body = h.transportField.calls[0].third
        assertTrue(body.contains("\"event\":\"assets_applied\""))
        assertTrue(body.contains("\"outcome\":\"failed\""))
        assertTrue(body.contains("\"failure\":\"rejected\""))
        // 拒装原话属于取证面（带路径与文件名），一个字都不该出现在出站体里。
        assertFalse("出站体里混进了拒装原话：$body", body.contains("非法路径"))
    }

    // ==================================================================
    // 2. 两条硬闸：通道关零出网、异常不外逃
    // ==================================================================

    @Test
    fun 通道关时一个字节都不出网也一个字节都不落盘() {
        val h = Harness(channelEnabled = false)
        assertFalse(h.bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        assertFalse(h.bridge.record(UpdateTelemetryEvents.offerShown("apk", 18L, force = false)))
        assertFalse(h.bridge.record(UpdateTelemetryEvents.assetsApplied(300L, UpdateTelemetry.OUTCOME_SUCCESS, DataArtifactKind.FULL, null)))
        // 第 6 拍同一条闸：安装终态在通道关时也不许多发一个字节、多留一行盘。
        assertFalse(h.bridge.record(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, 6, 17L)))
        assertEquals("关态一次出站都没有", 0, h.transportField.calls.size)
        assertEquals("关态连缓冲都不进", 0, h.bridge.pendingCount())
        assertEquals("关态一个字节都不落盘", 0, h.storageField.saved.size)
        assertEquals("关态盘上也没有待发行", emptyList<String>(), h.storageField.lines)
        assertFalse("关态的手动冲刷也不许出网", h.bridge.flush())
        assertEquals(0, h.transportField.calls.size)
    }

    @Test
    fun 关闸前攒下的事件在开闸的这只才出站() {
        val closed = Harness(channelEnabled = false)
        closed.bridge.record(UpdateTelemetryEvents.checkStarted(17L))
        assertFalse(closed.bridge.flush())
        assertEquals(0, closed.transportField.calls.size)

        val open = Harness(channelEnabled = true)
        assertTrue(open.bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        assertTrue(open.bridge.flush())
        assertEquals(1, open.transportField.calls.size)
        val (gotEndpoint, gotToken, body) = open.transportField.calls[0]
        assertEquals(TELEMETRY_ENDPOINT, gotEndpoint)
        assertEquals(TELEMETRY_TOKEN, gotToken)
        assertTrue(body.startsWith("""{"kind":"update-funnel","events":["""))
        assertTrue(body.contains("\"event\":\"check_started\""))
        assertEquals("发成功后缓冲与盘都清空", 0, open.bridge.pendingCount())
        assertEquals(emptyList<String>(), open.storageField.lines)
    }

    @Test
    fun 通道开时记录进缓冲并落盘() {
        val h = Harness()
        assertTrue(h.bridge.record(UpdateTelemetryEvents.checkOk(17L)))
        assertEquals(1, h.bridge.pendingCount())
        assertEquals(1, h.storageField.saved.size)
        assertTrue(h.storageField.lines[0].contains("\"event\":\"check_ok\""))
        assertEquals("记录这一拍本身不出站（攒批口径归 core）", 0, h.transportField.calls.size)
    }

    @Test
    fun 第6拍在后台档真的攒进批并出站() {
        val h = Harness()
        assertTrue(h.bridge.record(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, 6, 17L)))
        assertTrue(h.bridge.flush())
        assertEquals(1, h.transportField.calls.size)
        val body = h.transportField.calls[0].third
        assertTrue(body.contains("\"event\":\"install_result\""))
        assertTrue(body.contains("\"outcome\":\"failed\""))
        assertTrue(body.contains("\"status\":\"6\""))
        // 出站体里只有短读数：摘要长句与系统原话属于玩家面/取证面，一个字都不该出现在这里。
        assertFalse("出站体里混进了玩家面句子：$body", body.contains("安装"))
        assertFalse("出站体里混进了系统原话：$body", body.contains("INSTALL_FAILED"))
    }

    @Test
    fun 第6拍在主线程回执档走中继_一次磁盘写都不做() {
        // 回执接收器挂在主线程 Handler 上（registerStatusReceiver 的显式 scheduler），
        // 所以这一拍的默认落点就是中继，而不是 core 的缓冲（后者每记一条整批覆盖写一次盘）。
        val h = Harness(mainThreadAtBuild = true)
        assertTrue(h.bridge.record(UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_SUCCESS, null, 17L)))
        assertEquals("主线程回执不许碰盘", 0, h.storageField.saved.size)
        assertEquals("主线程回执不许碰网", 0, h.transportField.calls.size)
        assertEquals(1, h.bridge.pendingCount())
        // 下一个后台拍（服务收尾的冲刷）把它排空。
        h.mainThread = false
        assertTrue(h.bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        assertEquals(2, h.bridge.pendingCount())
        assertTrue(h.bridge.flush())
        val body = h.transportField.calls[0].third
        assertTrue("中继排空后少了第 6 拍：$body", body.contains("\"event\":\"install_result\""))
    }

    @Test
    fun 主线程一次磁盘写都不做_事件先进内存中继() {
        // 弹窗那一拍跑在主线程：core 的记录每记一条都要整批覆盖写一次待发行，那正是
        // StrictModeGuard 类头点名的「落在一次点击里的磁盘写」。中继把它挡在内存里。
        val h = Harness(mainThreadAtBuild = true)
        repeat(UpdateTelemetry.MAX_BUFFER + 4) { assertTrue(h.bridge.record(UpdateTelemetryEvents.checkStarted(17L))) }
        assertEquals("主线程上一个字节都不落盘", 0, h.storageField.saved.size)
        assertEquals("主线程上一个字节都不出网", 0, h.transportField.calls.size)
        assertEquals("中继的容量是有界的（超出丢最旧），不是无限攒", UpdateTelemetryBridge.RELAY_MAX, h.bridge.pendingCount())
    }

    @Test
    fun 主线程攒的中继由下一个后台拍排空_一条不少送出() {
        val h = Harness(mainThreadAtBuild = true)
        // 弹窗的形状：展示 + 玩家点「下载」，两拍都在主线程。
        assertTrue(h.bridge.record(UpdateTelemetryEvents.offerShown("apk", 18L, force = false)))
        assertTrue(h.bridge.record(UpdateTelemetryEvents.offerAction(UpdateTelemetry.ACTION_DOWNLOAD, 18L)))
        assertEquals(0, h.storageField.lines.size)
        // 检查线程回来了（后台）：中继里的两条先排空再记这一条，三条件一起进缓冲与盘。
        h.mainThread = false
        assertTrue(h.bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        assertEquals(3, h.bridge.pendingCount())
        assertEquals(3, h.storageField.lines.size)
        assertTrue(h.bridge.flush())
        assertEquals(1, h.transportField.calls.size)
        val body = h.transportField.calls[0].third
        for (name in listOf("offer_shown", "offer_action", "check_started")) {
            assertTrue("中继排空后少了这一拍：$name", body.contains("\"event\":\"$name\""))
        }
        assertEquals(0, h.bridge.pendingCount())
    }

    @Test
    fun 后台线程档把整批发走() {
        val h = Harness(mainThreadAtBuild = false)
        repeat(UpdateTelemetry.MAX_BUFFER) { h.bridge.record(UpdateTelemetryEvents.checkStarted(17L)) }
        assertEquals("没到上限不刷", 0, h.transportField.calls.size)
        // 第 MAX_BUFFER + 1 条触发 core 的溢出冲刷：这一档在后台，可以出站。
        assertTrue(h.bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        assertEquals(1, h.transportField.calls.size)
        assertEquals(0, h.bridge.pendingCount())
    }

    @Test
    fun 关闸把还没排空的中继一起丢掉() {
        // 「关了还攒」是不行的：主线程收下、玩家随手把自动更新关掉，那几条内存里的计数不该在
        // 下次开闸时被一起带走（那会把关通道之前与之后的行为混成一坨）。
        val h = Harness(mainThreadAtBuild = true)
        assertTrue(h.bridge.record(UpdateTelemetryEvents.offerShown("apk", 18L, force = false)))
        assertEquals(1, h.bridge.pendingCount())
        h.enabled = false
        assertFalse(h.bridge.flush())
        assertEquals("关闸即弃", 0, h.bridge.pendingCount())
        assertEquals(0, h.transportField.calls.size)
        assertEquals(0, h.storageField.saved.size)
    }

    @Test
    fun 主线程档连冲刷都不做() {
        val h = Harness()
        assertTrue(h.bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        h.mainThread = true
        assertFalse("主线程不许动盘与网", h.bridge.flush())
        assertEquals(0, h.transportField.calls.size)
        assertEquals("该发的还在账上", 1, h.bridge.pendingCount())
    }

    @Test
    fun 发送闸的判据就是主线程这一条() {
        assertFalse(UpdateTelemetryWiring.networkingAllowed(isMainThread = true))
        assertTrue(UpdateTelemetryWiring.networkingAllowed(isMainThread = false))
    }

    @Test
    fun 落盘抛异常不外逃_调用点照旧继续() {
        val storage = FakeStorage()
        storage.throwOnSave = true
        val h = Harness(storageField = storage)
        var thrown: Throwable? = null
        var recorded = true
        try {
            recorded = h.bridge.record(UpdateTelemetryEvents.checkStarted(17L))
        } catch (t: Throwable) {
            thrown = t
        }
        assertNull("遥测记录不许把异常抛回更新面", thrown)
        assertFalse(recorded)
        assertEquals("异常挡的是这一条，出站仍是零", 0, h.transportField.calls.size)
        assertTrue("吞掉的异常要留下一行取证", h.logField.lines.isNotEmpty())
    }

    @Test
    fun 连记日志都炸也不外逃() {
        val storage = FakeStorage()
        storage.throwOnSave = true
        val log = FakeLog()
        log.throwOnLog = true
        val bridge = UpdateTelemetryBridge(
            UpdateTelemetry(storage, { 100L }, { true }),
            { true },
            { false },
            log::write,
        )
        var thrown: Throwable? = null
        try {
            assertFalse(bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
            assertFalse(bridge.flush())
        } catch (t: Throwable) {
            thrown = t
        }
        assertNull("存盘抛 + 日志也抛：仍不许把异常交给更新面", thrown)
    }

    @Test
    fun 通道判据自己抛了按关处理() {
        val transport = FakeTransport()
        val bridge = UpdateTelemetryBridge(
            UpdateTelemetry(FakeStorage(), { 100L }, telemetrySendPort(TELEMETRY_ENDPOINT, TELEMETRY_TOKEN, transport) { false }),
            { throw IllegalStateException("偏好读不出来") },
            { false },
            FakeLog()::write,
        )
        assertFalse(bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        assertFalse(bridge.flush())
        assertEquals("判不出来就当关：保守方向是零出网", 0, transport.calls.size)
    }

    @Test
    fun 传输口炸了不外逃_事件留在缓冲与盘上() {
        val boom = UpdateTelemetrySink.Transport { _, _, _ -> throw IllegalStateException("socket 炸了") }
        val storage = FakeStorage()
        val telemetry = UpdateTelemetry(storage, { 100L }, telemetrySendPort(TELEMETRY_ENDPOINT, TELEMETRY_TOKEN, boom) { false })
        val bridge = UpdateTelemetryBridge(telemetry, { true }, { false }, FakeLog()::write)
        assertTrue(bridge.record(UpdateTelemetryEvents.checkStarted(17L)))
        var thrown: Throwable? = null
        var flushed = true
        try {
            flushed = bridge.flush()
        } catch (t: Throwable) {
            thrown = t
        }
        assertNull("发送面的异常不许外逃", thrown)
        assertFalse("这批没消费，core 该留着", flushed)
        assertEquals(1, bridge.pendingCount())
        assertEquals(1, storage.lines.size)
    }

    @Test
    fun 发送失败时事件一条不丢_留给下次() {
        val h = Harness(transportField = FakeTransport(result = false))
        assertTrue(h.bridge.record(UpdateTelemetryEvents.checkOk(17L)))
        assertFalse(h.bridge.flush())
        assertEquals(1, h.transportField.calls.size)
        assertEquals("发不出去就留在缓冲", 1, h.bridge.pendingCount())
        assertEquals("盘上也留着", 1, h.storageField.lines.size)
    }

    @Test
    fun 坏事件由core拒掉_不落盘不出站() {
        val h = Harness()
        // 白名单之外的一律静默无效（U6b 给 core 开了 install_result 这一口，**没有**把校验放宽）：
        // 裸的凭空事件名与近亲拼法都进不来，误接的表现是「这一拍没记上」，不是脏数据。
        for (name in listOf("install_results", "installResult", "install_outcome", "no_such_event")) {
            assertFalse(
                "$name 不在 core 的白名单里，居然被收下",
                h.bridge.record(UpdateTelemetry.Event(name, 1_700_000_000_000L)),
            )
        }
        assertFalse(
            "带 outcome 的凭空事件名同样进不来",
            h.bridge.record(UpdateTelemetry.Event("install_result2", 1_700_000_000_000L, outcome = "success")),
        )
        assertEquals(0, h.bridge.pendingCount())
        assertEquals(0, h.storageField.saved.size)
        assertEquals(0, h.transportField.calls.size)
        assertFalse("null 事件（读数控不下来时）当没这一拍", h.bridge.record(null))
        // 错位维度也一样被 core 拒：check_started 不许带 kind。
        assertFalse(h.bridge.record(UpdateTelemetry.Event("check_started", 1_700_000_000_000L, kind = "apk")))
        // 第 6 拍的维度错位同样拒：非失败档带状态码、失败档把摘要长句当状态码。
        assertFalse(
            h.bridge.record(
                UpdateTelemetry.Event(
                    UpdateTelemetry.EVENT_INSTALL_RESULT,
                    1_700_000_000_000L,
                    outcome = UpdateTelemetry.OUTCOME_SUCCESS,
                    status = "0",
                ),
            ),
        )
        assertFalse(
            h.bridge.record(
                UpdateTelemetryEvents.installResult(UpdateTelemetry.OUTCOME_FAILED, null, 17L).copy(
                    status = "安装失败：与设备上已装的应用冲突（签名不同）",
                ),
            ),
        )
        assertEquals("坏输入一条都没落盘", 0, h.storageField.saved.size)
        assertEquals(0, h.transportField.calls.size)
    }

    // ==================================================================
    // 3. 源码面：六拍接线点与两道闸
    // ==================================================================

    @Test
    fun 六个接线点全在册() {
        val ui = sourceOf("UpdateUi.kt")
        assertTrue("检查起跑那一拍掉了", ui.contains("UpdateTelemetryWiring.checkStarted(app,"))
        assertTrue("检查失败那一拍掉了", ui.contains("UpdateTelemetryWiring.checkFailed(app, mark)"))
        assertTrue("弹窗展示那一拍掉了", ui.contains("UpdateTelemetryWiring.offerShown(activity,"))
        assertEquals("弹窗动作四个点：下载 / 以后都不 / 关闭 / 知道了", 4, occurrences(ui, "UpdateTelemetryWiring.offerAction("))
        assertTrue("检查的冲刷点掉了", ui.contains("UpdateTelemetryWiring.flushPending(app)"))
        // 冲刷必须排在放闸之后：端点慢的时候这一发最长两档超时，而「正在检查更新…」置灰的
        // 时长由那一发 post 决定 —— 排错了就是遥测把按钮按住了十秒。
        assertTrue(
            "冲刷不许挡在放闸前面",
            ui.indexOf("checking.set(false)") < ui.indexOf("UpdateTelemetryWiring.flushPending(app)"),
        )

        val manager = sourceOf("UpdateManager.kt")
        assertEquals("清单形状两处（不在灰度 / 判定尾）", 2, occurrences(manager, "UpdateTelemetryWiring.checkOk(context,"))

        val service = sourceOf("UpdateDownloadService.kt")
        assertTrue("下载起拍掉了", service.contains("UpdateTelemetryWiring.downloadStarted(applicationContext, job.kind, job.assetsVersion)"))
        assertTrue("下载成拍掉了", service.contains("UpdateTelemetryWiring.downloadFinished("))
        assertTrue("下载取消拍掉了", service.contains("UpdateTelemetryWiring.downloadCancelled(applicationContext, job.kind, job.assetsVersion)"))
        assertTrue("下载失败拍掉了", occurrences(service, "UpdateTelemetryWiring.downloadFailed(") == 2)
        assertTrue("安装请求拍掉了", service.contains("UpdateTelemetryWiring.installRequested("))
        // L5 P2（收尾顺序）：下载收尾的冲刷保持接线，但改成**退场之后异步走** —— 旧写法同步等两档
        // 超时，期间那条 ongoing 进度通知还挂着、服务还占着前台预算。
        assertTrue("下载收尾的冲刷点掉了", service.contains("UpdateTelemetryWiring.flushPendingAsync(applicationContext)"))
        assertFalse(
            "下载收尾不许再用同步冲刷（会拖住退场与通知栏）",
            service.contains("UpdateTelemetryWiring.flushPending(applicationContext)"),
        )

        val host = sourceOf("GameHost.kt")
        // 第 7 拍（U10）：Done 与 Failed 两格都发拍 —— U6 时代「Failed 那一格接不了」的注释
        // 在 core 开格后就是假陈述，连同旧的单格调用形状一起不许再出现。
        assertEquals("第 7 拍两格都发（Done + Failed）", 2, occurrences(host, "UpdateTelemetryWiring.assetsApplied("))
        assertFalse(
            "「Failed 那一格接不了」的假陈述必须已死（历史上它只作为已被补上的口子被引用）",
            host.contains("Failed 那一格接不了 ——") || host.contains("不收 failure 维"),
        )
        val doneBody = host.substringAfter("is UpdateManager.ApplyOutcome.Done -> {").substringBefore("is UpdateManager.ApplyOutcome.Failed")
        assertTrue("成功格 outcome 维掉了", doneBody.contains("UpdateTelemetry.OUTCOME_SUCCESS"))
        assertTrue("成功格档位维掉了", doneBody.contains("applied.kind"))
        val failedBody = host.substringAfter("is UpdateManager.ApplyOutcome.Failed -> {").substringBefore("is UpdateManager.ApplyOutcome.Nothing")
        assertTrue("失败格 outcome 维掉了", failedBody.contains("UpdateTelemetry.OUTCOME_FAILED"))
        assertTrue("失败格交的是枚举类别短标签", failedBody.contains("applied.category.label"))
        for (body in listOf(doneBody, failedBody)) {
            assertFalse(
                "problem 原话混进了第 7 拍的维度",
                body.substringAfter("UpdateTelemetryWiring.assetsApplied(").substringBefore(")").contains(".problem"),
            )
        }
        assertTrue(
            "装载前那一拍的冲刷必须走异步（同步一发就是让端点快慢决定首屏时间）",
            host.contains("UpdateTelemetryWiring.flushPendingAsync(view.context)"),
        )
        assertFalse("装载前那一拍不许有同步冲刷", host.contains("UpdateTelemetryWiring.flushPending(view.context)"))

        // 第 6 拍（U6b）：core 开口之后才接上的安装终态，接在回执送达面的第一行。
        val session = sourceOf("PackageInstallerSession.kt")
        assertEquals("安装终态只许一处落笔（deliver 首行）", 1, occurrences(session, "UpdateTelemetryWiring.installResult("))
        val deliverBody = session.substringAfter("private fun deliver(").substringBefore("when (outcome) {")
        assertTrue("第 6 拍掉了：deliver 不再记安装终态", deliverBody.contains("recordInstallResult(appContext, outcome)"))
        // 复用 U6 的接线层：app 侧不许为安装回执另起一套缓冲或发送（那会绕开通道闸与主线程闸）。
        for (forbidden in listOf("UpdateTelemetrySink", "UpdateTelemetry(", "telemetrySendPort", "HttpURLConnection", "FileStorage")) {
            assertFalse("安装回执面自己起了第二套发送/缓冲：$forbidden", session.contains(forbidden))
        }
        // 交给遥测的只有终态标签、失败档的原始状态码与本机版本号；摘要长句（summary）不许进那一格。
        val recordBody = session.substringAfter("private fun recordInstallResult(").substringBefore("\n    }")
        assertEquals("第 6 拍的落笔只许这一处维度装配", 1, occurrences(recordBody, "UpdateTelemetryWiring.installResult("))
        assertTrue("状态码要从失败档透出来", recordBody.contains(".status"))
        assertFalse("摘要长句混进了第 6 拍的维度", recordBody.contains(".summary"))
    }

    @Test
    fun 接线层的两道闸在源面上() {
        val wiring = sourceOf("UpdateTelemetryWiring.kt")
        // 通道闸：记录与冲刷都先问 channelEnabled，且都在触碰 telemetry 之前。
        val recordBody = wiring.substringAfter("fun record(event: UpdateTelemetry.Event?)").substringBefore("fun flush(): Boolean")
        assertTrue("记录第一行问通道闸", recordBody.indexOf("channelEnabled()") in 0 until recordBody.indexOf("telemetry.record("))
        val flushBody = wiring.substringAfter("fun flush(): Boolean").substringBefore("fun pendingCount()")
        assertTrue("冲刷第一行问通道闸", flushBody.indexOf("channelEnabled()") in 0 until flushBody.indexOf("telemetry.flushNow()"))
        // 出站唯一经过 telemetrySendPort，而那里有主线程闸。
        val port = wiring.substringAfter("internal fun telemetrySendPort").substringBefore("/**")
        assertTrue("发送口有主线程闸", port.contains("if (isMainThread()) false"))
        // 六拍的落笔口只有一个，且它兜异常：调用点不需要各自再包一层。
        assertEquals("所有事件都走同一个落笔口", 1, occurrences(wiring, "bridgeOf(context)?.record(event)"))
        assertTrue(
            "首建缓冲避开主线程（还原待发行要读盘，只在后台做）",
            wiring.contains("if (!networkingAllowed(runCatching { mainThreadProbe() }.getOrDefault(true))) return null"),
        )
        assertTrue("落笔口兜异常", wiring.contains("runCatching { bridgeOf(context)?.record(event) }"))
        assertTrue("首建缓冲走锁（两路同时首拍不许各建一只，那会把同一批发两遍）", wiring.contains("return synchronized(this) {"))
        // 第三条闸：主线程不碰盘也不碰网，事件先进内存中继。
        assertTrue("主线程落点走中继", wiring.contains("isMainThread() -> {") && wiring.contains("holdForBackground(event)"))
        assertTrue("后台拍先排空中继", occurrences(wiring, "drainRelay()") >= 2)
        assertTrue("桥面兜住连日志一起炸的形状", occurrences(wiring, "} catch (t: Throwable) {") >= 2)
    }

    @Test
    fun 接线层不碰任何设备标识与路径() {
        val wiring = sourceOf("UpdateTelemetryWiring.kt")
        for (forbidden in listOf("ANDROID_ID", "Settings.Secure", "getSerial", "MacAddress", "deviceId", "mobile network")) {
            assertFalse("遥测面出现了设备标识字样：$forbidden", wiring.contains(forbidden, ignoreCase = true))
        }
        assertFalse("维度装配里不许出现游戏根路径", wiring.contains("gameRoot"))
        assertFalse("维度装配里不许出现绝对路径", wiring.contains("absolutePath"))
        assertFalse("维度装配不许把存档内容交进来", wiring.contains("SaveGame") || wiring.contains("saveSlot"))
    }

    @Test
    fun 零新增玩家可见文案() {
        // 遥测是取证面不是 UI（AGENTS §6.1）：接线层与本波各接线点都不许取文案。
        val wiring = sourceOf("UpdateTelemetryWiring.kt")
        assertFalse("接线层不许取 UpdateCopy", wiring.contains("UpdateCopy"))
        assertFalse("接线层不许取 copyFor", wiring.contains("copyFor("))
        for (name in listOf("UpdateUi.kt", "UpdateManager.kt", "UpdateDownloadService.kt", "GameHost.kt")) {
            for (line in sourceOf(name).lines().filter { it.contains("UpdateTelemetryWiring.") }) {
                assertFalse("$name 的遥测行不许取文案：$line", line.contains("copyFor(") || line.contains("UpdateCopy"))
            }
        }
    }

    // ---- 工具 ----

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    /** 取本模块主源文件的文本（AGP 单测的工作目录按惯例是本模块目录）。 */
    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8)
    }
}
