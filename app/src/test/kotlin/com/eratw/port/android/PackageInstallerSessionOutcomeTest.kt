package com.eratw.port.android

import android.content.pm.PackageInstaller
import com.eratw.port.core.update.UpdateTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * U7 安装会话的定向单测（CONTRACT-UPD2 §2 U7 出口判据：回执分类 + 分档）。
 *
 * 纯 JVM：[InstallSessionOutcome.of] 只吃状态码、系统原话与语言表（U6b 起**没有**不带 copy 的
 * 中文兼容口 —— 那种「默认中文」的口留着，三语表就是装饰）；分档判据
 * [UpdateManager.installRouteFor] 只吃 sdkInt（Build.VERSION_CODES.Q 与 PackageInstaller 的
 * STATUS 常量都是编译期内联常量，可在 JVM 单测里直接引用）。
 * HTTP / 文件 IO / 会话写入本身不测（Android 面，真机口径 TODO(verify)）。
 *
 * 为什么必须钉：回执分类是「系统安装器装完/被拒/被取消之后玩家看到什么」的唯一判定，
 * 分类错一档（把用户取消说成失败、把签名冲突说成网络问题）就是玩家面谎报；分档判据
 * 两处各写一个 29 的风险也一并钉死（launchInstaller 从这一个函数读路由）。
 *
 * ## 状态码取「真实值」而不是脑补字面量
 *
 * 早期本类用 -2/-3 这类**猜的**字面量喂 of，结果与 SDK 里的真实值全对不上（失败码是正数
 * 1..8，ABORTED 是 3 不是 -2）——分类没测到、断言却「看着像对」。现在喂入端一律用
 * [PackageInstaller] 的具名常量（与生产 [InstallSessionOutcome.of] 同一判据源），另用
 * `系统回执状态码与 android-36 SDK 逐字一致` 把每个码的**实际数值**钉成回归闸：一旦换 SDK、
 * 某个码被重新编号，这条会红，逼我们重看回执映射，而不是静默把用户取消说成失败。
 * 数值证据来源：`javap -constants android.content.pm.PackageInstaller`（compileSdk 36）。
 */
class PackageInstallerSessionOutcomeTest {

    /**
     * 回执分类的喂入口（**显式**带语言表）：本类的断言读的是中文档的原句，所以每一处都点名
     * [UpdateCopy.ZH]。生产侧那个不带 copy 的中文兼容口 U6b 已删（它把语言钉死在函数体里，
     * 三语表就成了装饰）；这里留一个测试私有的定值入口，只为本文件的中文对拍服务。
     */
    private fun ofZh(status: Int, statusMessage: String?): InstallSessionOutcome =
        InstallSessionOutcome.of(status, statusMessage, UpdateCopy.ZH)

    /** 造一只「只判不记」的 core 遥测件：[UpdateTelemetry.validate] 是纯判定，盘与网都不碰。 */
    private object EmptyStorage : UpdateTelemetry.Storage {
        override fun load(): List<String> = emptyList()
        override fun save(lines: List<String>) = Unit
    }

    // ------------------------------------------------------------------
    // 分档（CONTRACT-UPD2 §2 U7：29+ Session API / <29 ACTION_VIEW）
    // ------------------------------------------------------------------

    @Test
    fun `API 21 到 28 走旧 ACTION_VIEW 路`() {
        assertEquals(UpdateManager.InstallRoute.LegacyActionView, UpdateManager.installRouteFor(21))
        assertEquals(UpdateManager.InstallRoute.LegacyActionView, UpdateManager.installRouteFor(28))
    }

    @Test
    fun `API 29 起走 PackageInstaller 会话`() {
        assertEquals(UpdateManager.InstallRoute.SessionApi, UpdateManager.installRouteFor(29))
        assertEquals(UpdateManager.InstallRoute.SessionApi, UpdateManager.installRouteFor(36))
    }

    // ------------------------------------------------------------------
    // 状态码真值闸（javap -constants android.content.pm.PackageInstaller，compileSdk 36）
    // 回执分类喂的是这些具名常量；数值一旦与系统实装漂移，这条先红。
    // ------------------------------------------------------------------

    @Test
    fun `系统回执状态码与 android-36 SDK 逐字一致`() {
        assertEquals(0, PackageInstaller.STATUS_SUCCESS)
        assertEquals(-1, PackageInstaller.STATUS_PENDING_USER_ACTION)
        assertEquals(1, PackageInstaller.STATUS_FAILURE)
        assertEquals(2, PackageInstaller.STATUS_FAILURE_BLOCKED)
        assertEquals(3, PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(4, PackageInstaller.STATUS_FAILURE_INVALID)
        assertEquals(5, PackageInstaller.STATUS_FAILURE_CONFLICT)
        assertEquals(6, PackageInstaller.STATUS_FAILURE_STORAGE)
        assertEquals(7, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
        assertEquals(8, PackageInstaller.STATUS_FAILURE_TIMEOUT)
    }

    // ------------------------------------------------------------------
    // 回执三分类（喂入端用 PackageInstaller 具名常量，与生产同一判据源）
    // ------------------------------------------------------------------

    @Test
    fun `STATUS_SUCCESS 分类为成功`() {
        assertEquals(InstallSessionOutcome.Success, ofZh(PackageInstaller.STATUS_SUCCESS, null))
        // 系统原话在场也照判成功：成功面不因附带句子变调。
        assertEquals(InstallSessionOutcome.Success, ofZh(PackageInstaller.STATUS_SUCCESS, "Done"))
    }

    @Test
    fun `STATUS_FAILURE_ABORTED 分类为用户取消 原话透传`() {
        val withMessage = ofZh(PackageInstaller.STATUS_FAILURE_ABORTED, "user backed out")
        assertTrue(withMessage is InstallSessionOutcome.UserCancelled)
        assertEquals("user backed out", (withMessage as InstallSessionOutcome.UserCancelled).statusMessage)
        val noMessage = ofZh(PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertTrue(noMessage is InstallSessionOutcome.UserCancelled)
        assertEquals(null, (noMessage as InstallSessionOutcome.UserCancelled).statusMessage)
    }

    @Test
    fun `STATUS_FAILURE_BLOCKED 是失败 摘要说被拦`() {
        val outcome = ofZh(PackageInstaller.STATUS_FAILURE_BLOCKED, null)
        assertTrue(outcome is InstallSessionOutcome.Failed)
        assertTrue((outcome as InstallSessionOutcome.Failed).summary.contains("拦下"))
    }

    @Test
    fun `STATUS_FAILURE_CONFLICT 的摘要带卸载与存档警示`() {
        val summary = (ofZh(PackageInstaller.STATUS_FAILURE_CONFLICT, null) as InstallSessionOutcome.Failed).summary
        assertTrue(summary.contains("冲突"))
        assertTrue(summary.contains("存档"))
    }

    @Test
    fun `STATUS_FAILURE_INCOMPATIBLE 的摘要说不兼容与签名`() {
        val summary = (ofZh(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, null) as InstallSessionOutcome.Failed).summary
        assertTrue(summary.contains("不兼容"))
        assertTrue(summary.contains("签名"))
    }

    @Test
    fun `STATUS_FAILURE_INVALID 的摘要指向重新下载`() {
        val summary = (ofZh(PackageInstaller.STATUS_FAILURE_INVALID, null) as InstallSessionOutcome.Failed).summary
        assertTrue(summary.contains("无效"))
        assertTrue(summary.contains("下载"))
    }

    @Test
    fun `STATUS_FAILURE_STORAGE 的摘要说空间不足`() {
        val summary = (ofZh(PackageInstaller.STATUS_FAILURE_STORAGE, null) as InstallSessionOutcome.Failed).summary
        assertTrue(summary.contains("空间不足"))
    }

    @Test
    fun `STATUS_FAILURE_TIMEOUT 的摘要说超时`() {
        val summary = (ofZh(PackageInstaller.STATUS_FAILURE_TIMEOUT, null) as InstallSessionOutcome.Failed).summary
        assertTrue(summary.contains("超时"))
    }

    @Test
    fun `通用 STATUS_FAILURE 落失败类 不当成功也不当取消`() {
        // STATUS_FAILURE(=1) 不在 of 的两条具名分支里 → 必须落 Failed，不许误判成 Success/UserCancelled。
        val outcome = ofZh(PackageInstaller.STATUS_FAILURE, null)
        assertTrue(outcome is InstallSessionOutcome.Failed)
    }

    @Test
    fun `未知状态码落失败并带错误码 不许吞成别的类`() {
        val summary = (ofZh(42, null) as InstallSessionOutcome.Failed).summary
        assertTrue(summary.contains("42"))
        val negative = (ofZh(-99, null) as InstallSessionOutcome.Failed).summary
        assertTrue(negative.contains("-99"))
    }

    @Test
    fun `系统原话非空就附带进失败摘要 空白则不带`() {
        val withMessage = (ofZh(PackageInstaller.STATUS_FAILURE_STORAGE, "not enough space") as InstallSessionOutcome.Failed).summary
        assertTrue(withMessage.contains("空间不足"))
        assertTrue(withMessage.contains("系统原话：not enough space"))
        val blank = (ofZh(PackageInstaller.STATUS_FAILURE_STORAGE, "   ") as InstallSessionOutcome.Failed).summary
        assertFalse(blank.contains("系统原话"))
    }

    @Test
    fun `SESSION_SUBMITTED_SIGNAL 与失败原话永不重形 调用方的信号过滤不会误伤`() {
        // 服务支路用 != 过滤信号、其余原话上屏：信号值必须与所有 problem 串天然不同形。
        assertTrue(UpdateManager.SESSION_SUBMITTED_SIGNAL.isNotEmpty())
        assertFalse(UpdateManager.SESSION_SUBMITTED_SIGNAL.startsWith("系统"))
        assertFalse(UpdateManager.SESSION_SUBMITTED_SIGNAL.startsWith("安装"))
    }

    // ------------------------------------------------------------------
    // 第 6 拍（install_result）的两格读数：终态标签 + 原始状态码（U6b）
    // ------------------------------------------------------------------

    @Test
    fun `失败档把原始状态码透出 非系统失败的长句失败没有码`() {
        // 系统回过话的：状态码原样带出（漏斗要的是这个短读数，不是那句摘要）。
        for (status in listOf(1, 2, 4, 5, 6, 7, 8, 42, -99)) {
            val failed = ofZh(status, "system said something") as InstallSessionOutcome.Failed
            assertEquals("状态码 $status 没透出来", status, failed.status)
        }
        // 玩家取消那一档没有「码」这一格：终态标签就够（core 的 status 只在 failed 档收）。
        assertTrue(ofZh(PackageInstaller.STATUS_FAILURE_ABORTED, null) is InstallSessionOutcome.UserCancelled)
        // 会话自己开不起来 / 路径不合法：系统一句话都没回过，status 必须是 null（不许编一个 0）。
        assertEquals(null, InstallSessionOutcome.Failed("安装会话没跑起来：OutOfMemoryError").status)
    }

    @Test
    fun `三分类映射到 core 的三个终态标签 不多不少不换词`() {
        // 词源在 core：这里再写一套 success/cancelled/failed 的字面量就是第四档脏维的入口。
        assertEquals(UpdateTelemetry.OUTCOME_SUCCESS, PackageInstallerSession.telemetryOutcomeOf(InstallSessionOutcome.Success))
        assertEquals(
            UpdateTelemetry.OUTCOME_CANCELLED,
            PackageInstallerSession.telemetryOutcomeOf(InstallSessionOutcome.UserCancelled("user backed out")),
        )
        assertEquals(
            UpdateTelemetry.OUTCOME_FAILED,
            PackageInstallerSession.telemetryOutcomeOf(InstallSessionOutcome.Failed("一条长句摘要", 6)),
        )
        // 每一档都得过 core 自己的闸（带状态码的只有失败档；success 误带就被拒）。
        val core = UpdateTelemetry(EmptyStorage, { 100L }, { true })
        for (outcome in listOf(InstallSessionOutcome.Success, InstallSessionOutcome.UserCancelled(null), InstallSessionOutcome.Failed("x", 6))) {
            val label = PackageInstallerSession.telemetryOutcomeOf(outcome)
            val event = UpdateTelemetry.Event(
                name = UpdateTelemetry.EVENT_INSTALL_RESULT,
                atMillis = 1_700_000_000_000L,
                outcome = label,
                status = (outcome as? InstallSessionOutcome.Failed)?.status?.toString(),
            )
            assertEquals("core 拒了第 6 拍：$outcome", null, core.validate(event))
        }
        val leaked = UpdateTelemetry.Event(
            name = UpdateTelemetry.EVENT_INSTALL_RESULT,
            atMillis = 1_700_000_000_000L,
            outcome = UpdateTelemetry.OUTCOME_FAILED,
            status = "安装失败：与设备上已装的应用冲突（签名不同）",
        )
        assertEquals(
            "摘要长句混进状态码那一格时必须被 core 拒掉",
            "install_result status 非法：安装失败：与设备上已装的应用冲突（签名不同）",
            core.validate(leaked),
        )
    }

    @Test
    fun `不带 copy 的 of 兼容口确实没了 三语只剩表这一条路`() {
        // 反射闸：兼容口是「编译期过得去、运行时永远说中文」的那种坑，删掉就要钉住它删掉了。
        val overloads = InstallSessionOutcome.Companion::class.java.declaredMethods
            .filter { it.name == "of" && !it.isSynthetic }
        assertEquals("of 的重载数量变了（只剩三参那一条）：$overloads", 1, overloads.size)
        assertEquals(3, overloads.single().parameterTypes.size)
    }

    // ------------------------------------------------------------------
    // 孤儿会话回收判定（纯函数，防会话泄漏；框架枚举与 abandon 面真机 TODO(verify)）
    // ------------------------------------------------------------------

    @Test
    fun `本包且超阈值的遗留会话该回收`() {
        val stale = PackageInstallerSession.STALE_SESSION_MILLIS
        assertTrue(
            PackageInstallerSession.shouldReclaimSelfSession(
                "com.eratw.port.android", "com.eratw.port.android", nowMillis = stale + 1_000, creationTimeMillis = 0L,
            ),
        )
    }

    @Test
    fun `本包但未超阈值的在途会话不许误杀`() {
        val stale = PackageInstallerSession.STALE_SESSION_MILLIS
        // 刚提交、玩家可能还在确认框上犹豫：age 小于阈值 → 保留。
        assertFalse(
            PackageInstallerSession.shouldReclaimSelfSession(
                "com.eratw.port.android", "com.eratw.port.android", nowMillis = stale - 1_000, creationTimeMillis = 0L,
            ),
        )
        // 恰好等于阈值：严格大于才回收，边界保留。
        assertFalse(
            PackageInstallerSession.shouldReclaimSelfSession(
                "com.eratw.port.android", "com.eratw.port.android", nowMillis = stale, creationTimeMillis = 0L,
            ),
        )
    }

    @Test
    fun `非本包或包名缺失的会话一律不动`() {
        val stale = PackageInstallerSession.STALE_SESSION_MILLIS
        assertFalse(
            PackageInstallerSession.shouldReclaimSelfSession(
                "com.other.app", "com.eratw.port.android", nowMillis = stale + 1_000, creationTimeMillis = 0L,
            ),
        )
        assertFalse(
            PackageInstallerSession.shouldReclaimSelfSession(
                null, "com.eratw.port.android", nowMillis = stale + 1_000, creationTimeMillis = 0L,
            ),
        )
    }
}
