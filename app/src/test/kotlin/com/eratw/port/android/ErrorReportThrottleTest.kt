package com.eratw.port.android

import com.eratw.port.core.err.CodeEE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上报节流（EP1-L6）的定向单测：`--tests "*ErrorReport*"` 的收口面。
 *
 * ## 钉的是哪三判式 + 哪两条纪律
 *
 * 1. 同签名（异常类 + 归一消息 + 最深函数/行号）本局只放行第一份，后续只加计数；
 * 2. 每局上限 [ErrorReportCollector.MAX_REPORTS_PER_SESSION] 份（终局类不受管）；
 * 3. 计数行的钉发冷却 = 补发侧那 10 s（[ReportUploadQueue.EMPTY_GRACE_MS]，同一把尺），
 *    而**冷却永远不推迟首份**；
 * 外加两条纪律：会话身份一变整表清零（新一局重新起算）、落盘失败不算报过。
 *
 * 判式层纯 JVM（假时钟 + 假文件句柄：节流从不碰盘，`File` 只是递给调用方的一个名字），
 * 所以这里不需要 Android 运行时。装配面（`collect` 到底有没有按判式走）用**源码哨兵**钉，
 * 与 [ErrorReportVmStackTest] 同族：桌面跑不了 Activity，但代码形状骗不了 grep。
 */
class ErrorReportThrottleTest {

    // ---- 夹具 ----
    //
    // 夹具里的 `line` 一律是**内部 0 基下标**（= `Program.lines` 的存值），签名打的是
    // `CodeEE.displayLine`（1 基，组长裁定 2026-11-04）。端点历史上的 `行69` 那簇
    // 对应下标 69 / 物理第 70 行（`rand/RandZeroArgChainAuditTest.上报行号是零基下标` 钉的
    // 就是同一件事），本波落地后新报告写成 `行70` ⇒ **签名码与指纹跟着变，历史簇与新簇不合并**
    // （端点 2026-11-04 已部署去重版并回填 676 份历史；这是预期代价，不为此加兼容层）。

    private val rand = CodeEE("RAND 的参数必须为正数（实际 0）", funcName = "QOL_GATHERING_DRAWING", line = 69, pc = 12)
    private val randMinus3 = CodeEE("RAND 的参数必须为正数（实际 -3）", funcName = "QOL_GATHERING_DRAWING", line = 69, pc = 12)
    private val randOtherLine = CodeEE("RAND 的参数必须为正数（实际 0）", funcName = "QOL_GATHERING_DRAWING", line = 70, pc = 4)
    private val randOtherFunc = CodeEE("RAND 的参数必须为正数（实际 0）", funcName = "QOL_GATHERING_OTHER", line = 69, pc = 12)
    private val divZero = CodeEE("除数为 0", funcName = "EJAC_CHECK", line = 230, pc = 7)
    private val explv = CodeEE("变量 EXPLV 的第 1 维下标 5002 越界", funcName = "SET_MINIMUM_EXP", line = 88, pc = 3)

    /** 现场时钟：测试自己推进，绝不看真表。 */
    private var nowMs = 1_000_000L
    private fun clock() = nowMs

    private fun newThrottle(max: Int = ErrorReportCollector.MAX_REPORTS_PER_SESSION, cooldownMs: Long = PIN) =
        ErrorReportThrottle(maxReportsPerSession = max, pinCooldownMs = cooldownMs)

    private fun sig(t: Throwable) = ReportSignature.of(ErrorReportCollector.THROTTLED_KIND, t, emptyList())

    /** 走一遍「判式 → 落盘成功」的完整回合，返回判式结论。 */
    private fun writeOnce(
        throttle: ErrorReportThrottle,
        session: String?,
        value: ReportSignature.Value,
        file: File = File("eratw-report-fake-$value.display.txt"),
    ): ThrottleDecision {
        val decision = throttle.decide(session, value, clock())
        if (decision.action == ThrottleAction.WRITE) throttle.noteWritten(session, value, file)
        return decision
    }

    // ---- 判式①：同签名只报第一份 ----

    @Test
    fun 同签名的第二份被抑制且递回首份那个文件() {
        val throttle = newThrottle()
        val session = "session@1"
        val value = sig(rand)
        val fileOfFirst = File("eratw-report-20261102-033649.txt")
        val first = throttle.decide(session, value, clock())
        assertEquals(ThrottleAction.WRITE, first.action)
        throttle.noteWritten(session, value, fileOfFirst)
        // vivo V2528A 那三条真实间隔里的第一段（12:19:36 → 12:19:54）：18 秒后的同型不再落盘。
        nowMs += 18_000L
        val second = throttle.decide(session, value, clock())
        assertEquals(ThrottleAction.SUPPRESSED_DUPLICATE, second.action)
        assertEquals(fileOfFirst, second.reportFile)
        assertEquals(2, second.occurrences)
        assertEquals(1, second.suppressedTotal)
        assertNotNull("抑制的第一发要钉一行计数进轨迹", second.trailPhase)
    }

    @Test
    fun 同一条语句的不同实参数字算同一型而不同行号不算() {
        // 「实际 0」与「实际 -3」是数据不是身份：数字串归一后同键（这是刷屏的主要来源）。
        assertEquals(sig(rand).key, sig(randMinus3).key)
        // 行号是身份的一部分：下标 69（→ 行70）与下标 70（→ 行71）是两个形状，绝不合并（宁可漏合不可错合）。
        assertNotEquals(sig(rand).key, sig(randOtherLine).key)
        assertNotEquals(sig(rand).key, sig(randOtherFunc).key)
        assertNotEquals(sig(rand).key, sig(divZero).key)
    }

    /**
     * 签名三段带上异常类与最深函数行号（行号 **1 基**，与屏上/报告正文同一口径）。
     *
     * 夹具给的是 0 基下标 69 ⇒ 三段的位置段必须是「行70」：这里同时钉住
     * 「换算只在 [com.eratw.port.core.err.CodeEE.displayLine] 一处」——签名这一路只是
     * 搬运，绝不在打印点再补一个 `+1`（否则会变成 行71）。
     */
    @Test
    fun 签名三段带上异常类与最深函数行号() {
        val value = sig(rand)
        val parts = value.key.split('|')
        assertEquals(3, parts.size)
        assertEquals(CodeEE::class.java.name, parts[0])
        assertEquals("QOL_GATHERING_DRAWING 行70", parts[1])
        assertEquals("RAND 的参数必须为正数（实际 #）", parts[2])
        // display 保未归一的原文：被合并掉的每一条仍然留一句可读的现场。
        assertEquals("QOL_GATHERING_DRAWING 行70｜RAND 的参数必须为正数（实际 0）", value.display)
    }

    @Test
    fun 包装异常取最内层的CodeEE位置() {
        val inner = CodeEE("越界", funcName = "INNER_FUNC", line = 41)
        val wrapped = RuntimeException("外层说明", inner)
        val value = ReportSignature.of(ErrorReportCollector.THROTTLED_KIND, wrapped, emptyList())
        assertTrue("取的是最深那枚 CodeEE 的函数与 1 基行号：${value.key}", value.key.endsWith("|INNER_FUNC 行42|外层说明"))
    }

    @Test
    fun 状态机终局的无异常报告也能签名() {
        val extra = listOf("状态机终局：SYSTEM_MAP", "阻塞：RAND 的参数必须为正数（实际 0）", "证据：Process.cs:595-661")
        val kind = ErrorReportCollector.THROTTLED_KIND
        val a = ReportSignature.of(kind, null, extra)
        val b = ReportSignature.of(
            kind,
            null,
            listOf("状态机终局：SYSTEM_MAP", "阻塞：RAND 的参数必须为正数（实际 -2）", "证据：Process.cs:595-661"),
        )
        val otherTerminalState = ReportSignature.of(
            kind,
            null,
            listOf("状态机终局：SYSTEM_CHAR", "阻塞：RAND 的参数必须为正数（实际 0）", "证据：Process.cs:595-661"),
        )
        assertTrue(a.key.startsWith("kind=$kind|SYSTEM_MAP|"))
        assertEquals(a.key, b.key)
        assertNotEquals(a.key, otherTerminalState.key)
    }

    @Test
    fun 同一型的第三次第四次只加计数不再落盘() {
        val throttle = newThrottle(cooldownMs = 0L)
        val session = "session@2"
        val value = sig(rand)
        writeOnce(throttle, session, value)
        var last: ThrottleDecision? = null
        repeat(4) { last = throttle.decide(session, value, ++nowMs) }
        assertEquals(ThrottleAction.SUPPRESSED_DUPLICATE, last!!.action)
        assertEquals(5, last!!.occurrences)
        assertEquals(4, last!!.suppressedTotal)
    }

    // ---- 判式②：每局上限 ----

    @Test
    fun 上限之内的不同形状各落一份() {
        val throttle = newThrottle()
        val session = "session@3"
        listOf(rand, divZero, explv).forEach { e ->
            assertEquals(ThrottleAction.WRITE, writeOnce(throttle, session, sig(e)).action)
        }
    }

    @Test
    fun 超出上限的新形状只计数不落盘且递回最近一份() {
        val throttle = newThrottle(max = 2)
        val session = "session@4"
        val last = File("eratw-report-20261102-074648.txt")
        writeOnce(throttle, session, sig(rand))
        writeOnce(throttle, session, sig(divZero), last)
        nowMs += 60_000L
        val cap = throttle.decide(session, sig(explv), clock())
        assertEquals(ThrottleAction.SUPPRESSED_SESSION_CAP, cap.action)
        assertEquals(last, cap.reportFile)
        assertNotNull(cap.trailPhase)
        assertTrue("上限那一路的轨迹行以 errcap 起头：${cap.trailPhase}", cap.trailPhase!!.startsWith("errcap"))
        // 挤掉的那个形状也进表：它后续的每一次都记成同一型，账面读得出撞了几次。
        val again = throttle.decide(session, sig(explv), clock() + 60_000L)
        assertEquals(ThrottleAction.SUPPRESSED_SESSION_CAP, again.action)
        assertEquals(2, again.occurrences)
    }

    @Test
    fun 上限默认五份而终局类报告不受管制() {
        assertEquals(5, ErrorReportCollector.MAX_REPORTS_PER_SESSION)
        assertTrue(ErrorReportCollector.isThrottledKind("脚本运行报错"))
        // 终局类（落完会话/进程就没了，一局最多一份）一刀都不碰：它们既不受去重也不占额度。
        listOf("启动失败", "应用崩溃", "回到标题失败", "上次异常退出").forEach { kind ->
            assertFalse("$kind 不该被节流管制", ErrorReportCollector.isThrottledKind(kind))
        }
    }

    @Test
    fun 管制只认脚本运行报错这一类() {
        assertEquals("脚本运行报错", ErrorReportCollector.THROTTLED_KIND)
        // 宿主侧的字面量必须还是这四个字：改名在桌面上不会炸（判式静默失效、刷屏回来），
        // 所以把两边对齐钉在这里。
        val host = sourceOf("GameHost.kt")
        val kinds = Regex("""writeErrorReport\(\s*"([^"]+)"""").findAll(host).map { it.groupValues[1] }.toSet()
        assertTrue("GameHost 的 writeErrorReport 类型里要有节流管制的这一类：$kinds", kinds.contains(ErrorReportCollector.THROTTLED_KIND))
        kinds.filter { ErrorReportCollector.isThrottledKind(it) }.forEach {
            assertEquals(ErrorReportCollector.THROTTLED_KIND, it)
        }
    }

    // ---- 判式③：10 s 冷却只管钉发，绝不让路给首份 ----

    @Test
    fun 冷却沿用补发侧那十秒而同一把尺() {
        assertEquals(10_000L, ReportUploadQueue.EMPTY_GRACE_MS)
        assertEquals(ReportUploadQueue.EMPTY_GRACE_MS, ErrorReportCollector.PIN_COOLDOWN_MS)
    }

    @Test
    fun 冷却窗口内只加计数不再碰盘() {
        val throttle = newThrottle()
        val session = "session@5"
        val value = sig(rand)
        writeOnce(throttle, session, value)
        nowMs += 1_000L
        val firstPin = throttle.decide(session, value, clock())
        assertNotNull("第一发计数行要钉出去", firstPin.trailPhase)
        nowMs += 5_000L
        val cooled = throttle.decide(session, value, clock())
        assertNull("冷却未到：只加计数，不产钉发", cooled.trailPhase)
        assertEquals(3, cooled.occurrences)
        nowMs += 5_000L
        val due = throttle.decide(session, value, clock())
        assertNotNull(due.trailPhase)
        assertTrue(due.trailPhase!!.startsWith("errdup x4 "))
        assertTrue("轨迹行不许把滚动轨迹撑爆（硬顶 96 字符）", due.trailPhase!!.length <= 96)
    }

    @Test
    fun 冷却不推迟首份且不同形状各写各的() {
        val throttle = newThrottle()
        val session = "session@6"
        val value = sig(rand)
        writeOnce(throttle, session, value)
        // 刚刚因为抑制钉过一发（冷却内），紧接着的**新形状**仍然当场就该落盘。
        nowMs += 1L
        throttle.decide(session, value, clock())
        nowMs += 1L
        val fresh = throttle.decide(session, sig(divZero), clock())
        assertEquals("任何冷却都不许把第一次的真实现场推后", ThrottleAction.WRITE, fresh.action)
        assertNull(fresh.trailPhase)
    }

    @Test
    fun 时钟被拨回时按冷却已过处理而不是永久钉不出去() {
        val throttle = newThrottle()
        val session = "session@7"
        val value = sig(rand)
        writeOnce(throttle, session, value)
        throttle.decide(session, value, nowMs + 20_000L)
        val jumped = throttle.decide(session, value, nowMs) // 墙钟倒退
        assertNotNull(jumped.trailPhase)
    }

    // ---- 清零口径 ----

    @Test
    fun 会话身份一变整表清零() {
        val throttle = newThrottle(max = 2)
        val old = "session@8"
        writeOnce(throttle, old, sig(rand))
        writeOnce(throttle, old, sig(divZero))
        val capped = throttle.decide(old, sig(explv), clock())
        assertEquals(ThrottleAction.SUPPRESSED_SESSION_CAP, capped.action)
        // 回到标题 → 新游戏：换一枚 GameSession ⇒ 身份变 ⇒ 额度与签名表全部重来。
        val fresh = throttle.decide("session@9", sig(rand), clock())
        assertEquals(ThrottleAction.WRITE, fresh.action)
        assertTrue("新局里上一局的抑制账不该留着（空表）：${throttle.summary()}", throttle.summary().isEmpty())
    }

    @Test
    fun 显式清账把所有账面归零() {
        val throttle = newThrottle()
        val session = "session@10"
        writeOnce(throttle, session, sig(rand))
        nowMs += 1_000L
        throttle.decide(session, sig(rand), clock())
        throttle.reset()
        assertEquals(ThrottleAction.WRITE, throttle.decide(session, sig(rand), clock()).action)
        assertTrue("清账后账面回到空表（没抑制过就不出声）", throttle.summary().isEmpty())
        // 清账后第一发计数行不受上一局冷却影响（NEVER 基准）。
        throttle.noteWritten(session, sig(rand), File("a.txt"))
        assertNotNull(throttle.decide(session, sig(rand), clock()).trailPhase)
    }

    @Test
    fun 落盘失败不算报过下一次仍试整份() {
        val throttle = newThrottle()
        val session = "session@11"
        val value = sig(rand)
        val decision = throttle.decide(session, value, clock())
        assertEquals(ThrottleAction.WRITE, decision.action)
        throttle.noteWritten(session, value, null) // 完整与最小退化报告都没写出来
        assertEquals("一份都没落成的型不许伪装成已报过", ThrottleAction.WRITE, throttle.decide(session, value, clock()).action)
    }

    @Test
    fun 上限额度只由落盘成功消耗() {
        val throttle = newThrottle(max = 1)
        val session = "session@12"
        val value = sig(rand)
        throttle.decide(session, value, clock())
        throttle.noteWritten(session, value, null)
        // 失败的那一发没占额度：下一个形状仍然能落一份。
        assertEquals(ThrottleAction.WRITE, throttle.decide(session, sig(divZero), clock()).action)
    }

    // ---- 账面 ----

    @Test
    fun 账面按次数排前三并标明未落盘的那型() {
        val throttle = newThrottle(max = 1)
        val session = "session@13"
        writeOnce(throttle, session, sig(rand))
        repeat(3) { throttle.decide(session, sig(rand), ++nowMs) }
        throttle.decide(session, sig(divZero), ++nowMs) // 被上限挤掉
        val lines = throttle.summary()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("落盘 1/1 份，抑制 4 条"))
        assertTrue(lines[1].startsWith("×4 ${sig(rand).display}"))
        // 一条都没抑制过时是空表：宿主把这组行接进报告的「补充现场」，
        // 首份报告的正文因此逐字不变（有账才出声）。
        val quiet = newThrottle()
        writeOnce(quiet, "session@14", sig(explv))
        assertTrue("没抑制过就不出声：${quiet.summary()}", quiet.summary().isEmpty())
    }

    @Test
    fun 采集器实例自带一张空账而读数不碰Android() {
        // throttleSummary 是给宿主接线的读口：它必须能在纯 JVM 上调（不碰 Log/Context），
        // 且「有账才出声」—— 本进程这枚实例没抑制过任何一条时就是空表。
        assertTrue(ErrorReportCollector.throttleSummary().isEmpty())
    }

    // ---- 装配面哨兵：第一份逐字不变的结构性保证 ----
    @Test
    fun 节流不参与正文拼装且抑制在落盘之前返回() {
        val code = collectorSource()
        val full = code.substring(code.indexOf("private fun collectFull(") until code.indexOf("private fun collectMinimal("))
        assertFalse(
            "落盘那份报告的正文不许被节流改一个字（含 [线程心跳] 与 [系统取证]）：判式只决定要不要再落一份",
            full.contains("throttle") || full.contains("Throttle") || full.contains("signature"),
        )
        // 两节仍在原地接线（削掉任何一节就是「为了省流量削留痕」）。
        assertTrue(full.contains("exitForensicsLines = exitForensicsLines(context, now)"))
        // 11-06 对面 7e014323 把 CSV 资源表读数（fileReadTrailLines）插进存档留痕与心跳之间——
        // 哨兵跟到三节链（断的是完整接线序，削掉任何一节仍红）。
        assertTrue(full.contains("saveDiagLines() + fileReadTrailLines(host) + heartbeatLines"))
        // 抑制那一路在真正写文件**之前**就返回（返回的是首份那个文件）。
        val collect = code.substring(code.indexOf("fun collect(") until code.indexOf("private fun collectFull("))
        assertTrue(
            "被抑制时递回首份文件而不是 null",
            Regex("""action != ThrottleAction\.WRITE[\s\S]*?return decision\.reportFile""").containsMatchIn(collect),
        )
        // 先判抑制再谈落盘：进入管制之后的那一段里，`return` 排在 `collectFull` 之前
        // （未受管的终局类在更早的一行直接原路落盘，不参与这段次序）。
        val throttledPart = collect.substring(collect.indexOf("val sessionKey = sessionKeyOf(host)"))
        assertTrue(
            "判式之后先返回，不许先进 collectFull",
            throttledPart.indexOf("return decision.reportFile") < throttledPart.indexOf("collectFull("),
        )
        // 判式自己出问题就退回「就落一份」：宁可刷屏也不丢现场。
        assertTrue(
            Regex("""getOrElse \{[\s\S]{0,200}?ThrottleDecision\.WRITE""").containsMatchIn(collect),
        )
    }

    @Test
    fun 状态全挂采集器实例而没有新增可变全局() {
        val code = collectorSource()
        // 采集器之外不许再出现顶层可变状态（`private var` 只许在 object 内部，见类头纪律）。
        val topLevelVars = code.lines().filter { it.startsWith("private var ") || it.startsWith("internal var ") || it.startsWith("var ") }
        assertTrue("节流状态要挂 ErrorReportCollector 实例：${topLevelVars.joinToString()}", topLevelVars.isEmpty())
        assertTrue("签名表与计数挂这一枚实例", code.contains("private val throttle = ErrorReportThrottle()"))
        // 判式层自己也不许做成第二枚进程级单例之外的形态：整表挂采集器那一枚实例上。
        val throttle = code.substring(code.indexOf("internal class ErrorReportThrottle(") until code.indexOf("internal object ReportSignature"))
        assertFalse("节流表是实例类，不是又一根静态可变全局", throttle.contains("object ErrorReportThrottle"))
        assertTrue("跨线程读改一律进锁（VM 线程与崩溃线程都会调）", throttle.contains("synchronized(lock)"))
    }

    // ---- 工具 ----

    private fun assertNotEquals(unexpected: String, actual: String) {
        assertFalse("两个签名不该相等：$actual", unexpected == actual)
    }

    private fun collectorSource(): String = sourceOf("ErrorReportCollector.kt")

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

    private companion object {
        /** 判式层单测默认用「有冷却」这一档（与线上同一枚常量），个别用例另给 0。 */
        const val PIN = 10_000L
    }
}
