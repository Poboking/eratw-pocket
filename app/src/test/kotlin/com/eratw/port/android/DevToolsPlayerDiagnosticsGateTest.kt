package com.eratw.port.android

import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import java.io.File
import org.junit.Assert
import org.junit.Test

/**
 * 判词 9（PDIAG1）：玩家可手动授权、缺省关的诊断通道 —— 通道判定面与命令分区面的钉子。
 *
 * ## 形状为什么长这样
 *
 * app 侧单测没有 Robolectric（依赖只有 junit4 与 :tools:erbcc），DevToolsHost 的构造要 GameHost
 * 加 CanvasConsoleView，JVM 里造不出来 ⇒ 判定必须收成**纯函数**（diagnosticsAllowed /
 * playerDiagRefusal / authorizationReportLines），「闸真在装配面上」那一格只能靠**源码形状钉子**
 * （同 BusyIndicatorWiringTest、DevToolsLockWatchCommandTest 的分工口径）。
 *
 * ## 钉什么（逐条对契约 §4 的两问反向证明与组长批复）
 *
 * 1. release 形参位 + 授权 ⇒ 放行；同形参位 + 未授权 ⇒ 拒绝，且**轮询线程根本不起**（不是起了
 *    不读 cmd.txt）—— 用「判定先于 slot.start」+「slot.start 全文件只有一处」钉；
 * 2. debug 侧原行为逐字不变（同一入口、同一句启动日志、不读偏好表）；
 * 3. 35 条命令逐名分区：只读 25 放行 / 写侧 10 必拒 / 未分类 fail-closed 必拒；分区表与 execute
 *    的 when 分支由源码形状闸逐名核对（加命令不归类就判红）；
 * 4. 缺省关（DEFAULT_PLAYER_DIAGNOSTICS = false，新键不进 KEEP_ON_RESET ⇒「恢复默认」退回关）；
 * 5. 取证两行是纯函数灌数可测，且全仓只有 DevToolsHost.kt 一处出题；
 * 6. 关掉当场 DevToolsHost.stop() 真生效（running 复位 + 线程名不存在 + 设置页确实调 stop）；
 * 7. 授权档不得新增 dev 目录之外的落盘导出（只读臂唯一写点 = varsdump.txt，取证行点名路径），
 *    两张名单另有测试侧字面量清单锁表（组长 m00704 / m00717①）。
 */
/**
 * 两张名单的**测试侧字面量清单**（组长 m00704 / m00717①）。
 *
 * 为什么不许只从生产集合推导：反向证明实测暴露过这个弱点 —— 把 `feed` 从 WRITE_COMMANDS 摘掉后，
 * 「写侧命令逐条在授权档被拒」那格反而**仍是绿的**（名单自己缩了，覆盖跟着缩）。改成字面量 +
 * `assertEquals` 锁表之后，摘一名同时红两格：本文件的锁表断言 + 分区对齐闸的差集断言。
 * 逐条行为用例现在遍历这两份字面量清单，不再遍历生产集合。
 */
private val WRITE_NAMES = listOf(
    "feed", "trace", "break", "set", "cheat",
    "snapshot", "restore", "log", "submit", "tap",
)

private val READONLY_NAMES = listOf("state", "gen", "rev", "stats", "view", "img", "bg", "lines", "chips", "hits",
    "frames", "turnlog", "inputtrail", "loadtrace", "lockwatch", "modgate", "sample",
    "var", "vars", "callstack", "where", "dating", "dumpvars", "caps", "events")

class DevToolsPlayerDiagnosticsGateTest {

    // 本文件统一「消息在前」（JUnit4 原生形状），下面三个包装只做转发，不转置参数。

    private fun assertTrue(message: String, condition: Boolean) = Assert.assertTrue(message, condition)

    private fun assertFalse(message: String, condition: Boolean) = Assert.assertFalse(message, condition)

    private fun assertEquals(message: String, expected: Any?, actual: Any?) =
        Assert.assertEquals(message, expected, actual)

    private fun assertNotNull(message: String, value: Any?) = Assert.assertNotNull(message, value)

    // ---- 批复 1：判定入口的真值表（闸在开关上，不在构建变体上）-----------------

    /** release 形参位（flags 不含 DEBUGGABLE）只看授权位；debug 形参位不看授权位。 */
    @Test
    fun `判定入口的四种形参组合`() {
        assertTrue(
            "release + 玩家已授权必须放行（判词 9 要还的就是这一格）",
            DevToolsHost.diagnosticsAllowed(flags = 0, prefsEnabled = true),
        )
        assertFalse(
            "release + 未授权必须拒绝：缺省关是玩家隐私的底线",
            DevToolsHost.diagnosticsAllowed(flags = 0, prefsEnabled = false),
        )
        assertTrue(
            "debug 包不看授权位：判词 9 之前的原行为逐字不变",
            DevToolsHost.diagnosticsAllowed(ApplicationInfo.FLAG_DEBUGGABLE, prefsEnabled = false),
        )
        assertTrue(
            "debug 包 + 玩家也授权：仍是 debug 全功能档，不该因此降级成只读",
            DevToolsHost.diagnosticsAllowed(ApplicationInfo.FLAG_DEBUGGABLE, prefsEnabled = true),
        )
        assertFalse(
            "其它 flag 不许被当成 debuggable（闸只认 FLAG_DEBUGGABLE 那一位）",
            DevToolsHost.diagnosticsAllowed(ApplicationInfo.FLAG_ALLOW_BACKUP, prefsEnabled = false),
        )
    }

    /**
     * 反向证明第一问：闸在开关上、不在构建变体上，且未授权那一格**线程根本不起**。
     *
     * 判据形状：startOnce 里判定必须排在起通道之前；slot.start 全文件只许一处（两处 = 有人新加了
     * 第二条轮询线程，契约 §2.2 明令禁止）；旧的「非 debuggable 直接 return」硬闸不许回来；
     * debug 那句启动日志文本逐字保留（批复 1：debug 侧原行为逐字不变）。
     */
    @Test
    fun `未授权那一格在建线程之前就返回`() {
        val src = appSource("DevToolsHost.kt")
        val startOnce = slice(
            src,
            "fun startOnce(host: GameHost, view: CanvasConsoleView) {",
            "private fun startChannel",
        )
        val verdict = startOnce.indexOf("diagnosticsAllowed(")
        val spawn = startOnce.indexOf("startChannel(")
        assertTrue("startOnce 里找不到判定调用 ⇒ 判定被搬走了：$startOnce", verdict >= 0)
        assertTrue("判定必须排在起通道之前（否则未授权也会先把线程建起来）：$startOnce", verdict < spawn)
        assertFalse("旧的「非 debuggable 直接 return」硬闸回来了", "FLAG_DEBUGGABLE == 0) return" in startOnce)
        assertEquals("轮询线程只许一条：slot.start( 在 DevToolsHost.kt 必须只出现一次", 1, src.split("slot.start(").size - 1)
        assertTrue("档位没进构造位 ⇒ 授权档的只读闸拿不到事实", "create = { DevToolsHost(host, view, authorizedOnly) }," in src)
        assertEquals("debug 路那句启动日志只许一处（逐字保留、不许被档位文案替换掉）", 1, src.split("代；轮询 files/eratw/dev/cmd.txt，500ms）").size - 1)
        assertTrue("非 debuggable 那一格必须 hop 回主线程读授权位（VM 线程不许摸偏好表）", "view.post {" in startOnce)
    }

    // ---- 批复 3：35 条命令逐名分区 + fail-closed --------------------------------

    /** 源码形状闸：解析 execute 的 when 分支名与两张分区表**逐名**核对，双向差集都要空。 */
    @Test
    fun `分区表与分发器的命令名逐名对齐`() {
        val src = appSource("DevToolsHost.kt")
        val body = slice(src, "when (name) {", "else -> USAGE")
        val arms = body.lines().mapNotNull { line ->
            val a = line.indexOf('"')
            if (a < 0) return@mapNotNull null
            val b = line.indexOf('"', a + 1)
            if (b < 0 || b == a + 1) return@mapNotNull null
            val rest = line.substring(b + 1)
            if ("->" !in rest) null else line.substring(a + 1, b)
        }.toSet()
        assertEquals("分发器分支数（从源码解析）", 35, arms.size)
        val unclassified = arms - READONLY_COMMANDS - WRITE_COMMANDS
        assertTrue("分发器里有未归类命令 $unclassified（必须先进两张名单之一）", unclassified.isEmpty())
        val extraInTables = (READONLY_COMMANDS + WRITE_COMMANDS) - arms
        assertTrue("分区表里有源码不存在的命令 $extraInTables", extraInTables.isEmpty())
        val overlap = READONLY_COMMANDS intersect WRITE_COMMANDS
        assertTrue("两张名单不许有交集：$overlap", overlap.isEmpty())
        assertEquals("授权档放开面必须是 25 条", 25, READONLY_COMMANDS.size)
        assertEquals("授权档必拒面必须是 10 条", 10, WRITE_COMMANDS.size)
        // 锁表：生产表与测试侧字面量清单必须逐名相同（表被摘/塞一名 ⇒ 这格与上面的差集一起红）
        assertEquals("字面量写侧清单与生产表不一致（表被动过）", WRITE_NAMES.toSet(), WRITE_COMMANDS)
        assertEquals("字面量只读清单与生产表不一致（表被动过）", READONLY_NAMES.toSet(), READONLY_COMMANDS)
        assertEquals("字面量写侧清单条数", 10, WRITE_NAMES.size)
        assertEquals("字面量只读清单条数", 25, READONLY_NAMES.size)
    }

    /**
     * 批复 3①／组长落地批③：写侧十条**逐条**过生产的分发闸 [dispatchGuarded] —— 拒绝时分发臂
     * 一次都没跑（行为判据：when 那一支没执行 ⇒ 游戏状态、存档、输入投喂都不可能被动过），
     * 不是「名单里有这个名字」的自证。debug 档同一条命令必须照旧进臂（判词 9 之前原行为逐字不变）。
     * 分发闸在 execute 里只许一个入口，一并钉住。
     */
    @Test
    fun `写侧命令逐条在授权档被拒`() {
        val src = appSource("DevToolsHost.kt")
        assertEquals("execute 里分发闸只许一处调用", 1, src.split("return dispatchGuarded(").size - 1)
        assertEquals("分发闸只许一个定义出口（不许第二条判定臂）", 1, src.split("internal fun dispatchGuarded(").size - 1)
        assertTrue("when 臂必须挂在 dispatchGuarded 的 proceed 里（否则拒绝证明不了不执行）",
            src.indexOf("return dispatchGuarded(") < src.indexOf("when (name) {"))
        for (name in WRITE_NAMES.sorted()) {
            assertNotNull("$name 必须被授权档拒绝（输入注入 / 改档 / 改执行行为）", playerDiagRefusal(name))
            val g = gate(name, authorizedOnly = true)
            assertFalse("$name 在授权档竟然进了分发臂 ⇒ 玩家授权就能改档或注入输入", g.proceeded)
            assertEquals("$name 被拒必须留一次日志取证", 1, g.refusalLogs)
            assertTrue("$name 的回执要点名命令：${g.out}", name in g.out)
            assertTrue("$name 的理由要写明需要 debug 包装机：${g.out}", "debug 包装机" in g.out)
            val d = gate(name, authorizedOnly = false)
            assertTrue("$name 在 debug 档必须照旧进分发臂", d.proceeded)
            assertEquals("debug 档的 $name 原行为不许变", "ARM:$name", d.out)
            assertEquals("debug 档不许多出拒绝日志", 0, d.refusalLogs)
        }
    }

    /** 批复 3①：只读观测面逐条**真进分发臂**（回执来自 proceed 那一支），不只靠裁决表说它放行。 */
    @Test
    fun `只读观测命令逐条在授权档放行`() {
        for (name in READONLY_NAMES.sorted()) {
            assertTrue("$name 属只读观测面，授权档不该拒它", playerDiagRefusal(name) == null)
            val g = gate(name, authorizedOnly = true)
            assertTrue("只读面 $name 在授权档被拒了 ⇒ 判词 9 要放开的就是这一格", g.proceeded)
            assertEquals("$name 放行时不许留拒绝日志", 0, g.refusalLogs)
            assertEquals("$name 的回执必须来自分发臂", "ARM:$name", g.out)
        }
        assertTrue("判词 9 的正主：lockwatch 必须真进分发臂", gate("lockwatch", true).proceeded)
        assertTrue("与设置页诊断卡同源的 modgate 必须可达", gate("modgate", true).proceeded)
        assertTrue("唯一落盘的 dumpvars 也在放开面（落点仍是既有 dev 目录，批复 3②）", gate("dumpvars", true).proceeded)
    }

    /** 组长要求钉成用例：未分类命令必须拒（「没登记」不等于「放开」），且拒在分发之前。 */
    @Test
    fun `未分类命令按缺省拒绝`() {
        for (name in listOf("wipesave", "upload", "feed2", "lockwatch2", "")) {
            val g = gate(name, authorizedOnly = true)
            assertFalse("未归类命令 <$name> 竟然进了分发臂（fail-closed 破了）", g.proceeded)
            assertTrue("未归类的理由要写明按缺省拒绝：${g.out}", "按缺省拒绝" in g.out)
            assertEquals("未归类被拒也要留日志取证", 1, g.refusalLogs)
            assertTrue("debug 档的 <$name> 仍走原路（落到 USAGE 那一臂）", gate(name, false).proceeded)
        }
    }

    /** 批复 3③：caps 在授权档改口，debug 档那条字面串逐字不动（EventWiringTest 钉它）。 */
    @Test
    fun `caps 分档且 debug 串不动`() {
        val src = appSource("DevToolsHost.kt")
        assertTrue("caps 没按档位分叉", "if (authorizedOnly) \"schema=1 caps=read\"" in src)
        assertTrue("debug 档的 caps 字面串被改动", "\"schema=1 caps=read,write,snapshot,events\"" in src)
    }

    // ---- 批复 2：缺省关 + 撤销当场真生效 ---------------------------------------

    /** 缺省关：DEFAULT 常数、桩直测读侧、「恢复默认设置」退回关。 */
    @Test
    fun `缺省关且恢复默认退回关`() {
        assertFalse("DEFAULT_PLAYER_DIAGNOSTICS 必须是关", UiPrefs.DEFAULT_PLAYER_DIAGNOSTICS)
        val sp = FakePrefs()
        assertFalse("没写过 ⇒ 读出来是关", UiPrefs.readPlayerDiagnostics(sp))
        assertEquals("没写过 ⇒ 授权时刻 0（取证行要说「从未拨动过」）", 0L, UiPrefs.readPlayerDiagnosticsAuthoredAt(sp))
        val keep = slice(appSource("UiPrefs.kt"), "private val KEEP_ON_RESET = setOf(", ")")
        assertFalse("新键进了 KEEP_ON_RESET ⇒「恢复默认」清不掉授权位（隐私红线）", "KEY_PLAYER_DIAGNOSTICS" in keep)
    }

    /** 开与关都刷新时刻（取证要有落点），且只新增两把键 = 不引入 SharedPreferences 之外的常驻状态。 */
    @Test
    fun `拨动开关刷新授权时刻并读回校验`() {
        val sp = FakePrefs()
        assertTrue("写入要返回读回一致（UI 线程禁 commit 探盘）", UiPrefs.persistPlayerDiagnostics(sp, true))
        assertTrue("写完必须读回开", UiPrefs.readPlayerDiagnostics(sp))
        val first = UiPrefs.readPlayerDiagnosticsAuthoredAt(sp)
        assertTrue("开启必须记下时刻：$first", first > 0L)
        assertTrue("关掉也要读回一致", UiPrefs.persistPlayerDiagnostics(sp, false))
        assertFalse("关掉后必须读回关", UiPrefs.readPlayerDiagnostics(sp))
        val second = UiPrefs.readPlayerDiagnosticsAuthoredAt(sp)
        assertTrue("关掉同样刷新时刻（取证要能说明撤销点）：$first -> $second", second >= first)
        assertEquals("授权面只许两把键，不许新增常驻状态", 2, sp.map.keys.size)
    }

    /** 关掉当场 stop 真生效：running 复位、槽里没有活线程、设置页那一侧确实调了 stop。 */
    @Test
    fun `撤销当场 stop 真生效`() {
        assertFalse("本用例跑之前槽里不该有 DevTools 线程", DevToolsHost.running)
        assertTrue("没起过也算已停（stop 幂等）", DevToolsHost.stop())
        assertFalse("stop 之后 running 必须为 false", DevToolsHost.running)
        val live = Thread.getAllStackTraces().keys.filter { it.name == "eratw-devtools" && it.isAlive }
        assertTrue("eratw-devtools 线程必须不存在（关态 = 线程不存在，不是起了不读）：$live", live.isEmpty())
        val settings = appSource("SettingsActivity.kt")
        assertTrue(
            "设置页必须写授权位：UiPrefs.setPlayerDiagnostics(context, enabled)",
            "UiPrefs.setPlayerDiagnostics(context, enabled)" in settings,
        )
        assertTrue("设置页关掉那一支必须当场 DevToolsHost.stop()（批复 2①）", "DevToolsHost.stop()" in settings)
        assertTrue("设置页开关行必须绑三语文案表（不许内联字面量）", "text.playerDiagTitle" in settings)
    }

    // ---- 批复 4：取证两行 ------------------------------------------------------

    /** 纯函数灌数：档位、授权位、距今秒数、放开/必拒条数都在读数里，且不许承诺上传之外的东西。 */
    @Test
    fun `取证两行的形状与内容`() {
        val on = DevToolsHost.authorizationReportLines(
            readOnlySession = true,
            enabled = true,
            authoredAtMillis = 1700000000000L,
            nowMillis = 1700000060000L,
        )
        assertEquals("取证面固定两行", 2, on.size)
        assertTrue("第一行要写档位：${on[0]}", "玩家授权只读档" in on[0])
        assertTrue("第一行要写授权位与距今秒数：${on[0]}", "玩家授权位=true" in on[0] && "60 秒" in on[0])
        assertTrue("第二行要写放开面与拒绝面条数：${on[1]}", "25 条" in on[1] && "10 条" in on[1])
        assertTrue("第二行要写明不自动上传：${on[1]}", "不自动上传" in on[1])
        val off = DevToolsHost.authorizationReportLines(
            readOnlySession = false,
            enabled = false,
            authoredAtMillis = 0L,
            nowMillis = 1700000000000L,
        )
        assertTrue("debug 档要写明全功能：${off[0]}", "debug 包全功能档" in off[0])
        assertTrue("从未拨动要显式说：${off[0]}", "从未拨动过" in off[0])
        val hosts = appMainSources().filter { "诊断通道档位" in it.second }.map { it.first }
        assertEquals("取证行只许一处出题（启动日志与诊断报告共用同一函数）", listOf("DevToolsHost.kt"), hosts)
    }

    /**
     * 授权档 usage 不许把写侧命令当成可用面列出去。
     *
     * 判据按**派生形状**写（组长 m00717②：AUTHORIZED_USAGE 由分区表派生，不再手抄）：取每行首 token
     * 做集合比较，不比整串 —— 派生出来的名字顺序是登记顺序，手抄顺序变了不该算红。
     */
    @Test
    fun `授权档 usage 只列放开的面`() {
        assertTrue("AUTHORIZED_USAGE 要点名 lockwatch", "lockwatch" in AUTHORIZED_USAGE)
        val listed = AUTHORIZED_USAGE.lines()
            .filter { it.startsWith("  ") }
            .map { it.trim().substringBefore(" ").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        assertEquals("授权档清单列出的命令名必须正好是 25 条放开面", READONLY_NAMES.toSet(), listed)
        val refusalLine = AUTHORIZED_USAGE.lines().firstOrNull { "不放开" in it } ?: ""
        assertTrue("usage 必须有「不放开」那一排：$AUTHORIZED_USAGE", refusalLine.isNotEmpty())
        val refused = refusalLine.substringAfter(":").trim().split(" ").filter { it.isNotBlank() }.toSet()
        assertEquals("「不放开」那一排必须正好是 10 条写侧命令", WRITE_NAMES.toSet(), refused)
        assertFalse("写侧命令的 usage 行不许出现在放开面里（等于把它当可用面宣传）", "  feed v1,v2,...   批量入队输入" in AUTHORIZED_USAGE)
    }

    /**
     * 组长 m00703②：只读面在授权档**不得新增 dev 目录之外的落盘导出**。判据（源码级 + 取证级）：
     * 1. 类体里每一处 `getExternalFilesDir` 都必须指向路径单源 `DEV_DIR_REL`（不许新增导出路径）；
     * 2. 写进 dev 目录的文件名只有 cmd.txt / out.txt / varsdump.txt / trace.txt 四个既有面；
     * 3. dumpvars 没有自定义输出路径那类口，落点写死 `File(dir, VARSDUMP_NAME)`；
     * 4. dev 目录之外唯一的 File 构造 = snapshot 沙箱（cacheDir），而 snapshot / restore 都在必拒面；
     * 5. 全文件不许出现公共外部存储 / 媒体库 / 外部缓存 API；
     * 6. 取证行必须显式点名 files/eratw/dev/varsdump.txt（m00703①）。
     */
    @Test
    fun `授权档落盘不许出 dev 目录`() {
        val src = appSource("DevToolsHost.kt")
        val code = src.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
        assertEquals("路径单源必须就是 eratw/dev/varsdump.txt", "eratw/dev/varsdump.txt", VARSDUMP_REL)
        val extLines = code.filter { "getExternalFilesDir" in it }
        assertTrue("源码里必须有 dev 目录构造（读数不许是空集）", extLines.isNotEmpty())
        for (line in extLines) {
            assertTrue("外部存储目录只许指 DEV_DIR_REL，不许新增导出路径：$line", "DEV_DIR_REL" in line)
        }
        val written = code
            .flatMap { line -> Regex("File\\(dir,\\s*([^)]+)\\)").findAll(line)
                .map { it.groupValues[1].trim() }.toList() }
            .toSet()
        // 比的是源码里写的那个实参（标识符或字面量），四个既有面之外一律判红
        val allowed = setOf("CMD_NAME", "OUT_NAME", "VARSDUMP_NAME", "\"trace.txt\"")
        for (name in written) {
            assertTrue("写进 dev 目录的文件名不在既有四个面里：$name", name in allowed)
        }
        assertEquals("既有落盘面 = cmd.txt + out.txt + varsdump.txt + trace.txt", 4, written.size)
        val dumpBody = slice(src, "private fun dumpVars", "private fun traceCmd")
        assertTrue("dumpvars 的落点必须写死在 dev 目录里：$dumpBody", "File(dir, VARSDUMP_NAME)" in dumpBody)
        assertFalse("dumpvars 不许接受自定义输出路径（那等于开一个新的导出面）", "--out" in dumpBody)
        assertFalse("dumpvars 不许用外部路径参数：$dumpBody", "outPath" in dumpBody)
        val sandbox = code.filter { "snapSandbox()" in it }
        assertEquals("snapshot 沙箱只许一个定义 + snapshot / restore 两个调用点", 3, sandbox.size)
        assertTrue("沙箱必须在 cacheDir（卸载即回收、adb 不可见）", code.any { "File(view.context.cacheDir," in it })
        for (name in listOf("snapshot", "restore")) {
            assertNotNull("$name 落在必拒面（dev 之外唯一写点，授权档必须碰不到）", playerDiagRefusal(name))
            assertFalse("$name 在授权档竟然进了分发臂", gate(name, authorizedOnly = true).proceeded)
        }
        val forbiddenApis = listOf(
            "getExternalStorageDirectory", "Environment.DIRECTORY", "MediaStore", "getExternalCacheDir",
        )
        for (api in forbiddenApis) {
            assertFalse("诊断通道不许碰公共存储 / 媒体库 / 外部缓存 API：$api", code.any { api in it })
        }
        val on = DevToolsHost.authorizationReportLines(
            readOnlySession = true,
            enabled = true,
            authoredAtMillis = 1700000000000L,
            nowMillis = 1700000060000L,
        )
        assertTrue("取证行必须显式点名唯一落盘路径：${on[1]}", "files/eratw/dev/varsdump.txt" in on[1])
        assertTrue("取证行必须写明别的 app 读不到：${on[1]}", "app 私有外部目录" in on[1])
    }

    /**
     * 驱动生产的那一个分发闸：[proceed] 就是 `DevToolsHost.execute` 里的 when 臂。
     * 返回的 proceeded / refusalLogs 就是「行为」读数 —— 臂跑没跑、日志记没记，不靠集合自证。
     */
    private fun gate(name: String, authorizedOnly: Boolean): GateRun {
        var ran = false
        var logs = 0
        val out = dispatchGuarded(
            authorizedOnly,
            name,
            onRefuse = { logs += 1 },
        ) {
            ran = true
            "ARM:$name"
        }
        return GateRun(out, ran, logs)
    }

    private data class GateRun(val out: String, val proceeded: Boolean, val refusalLogs: Int)
    private fun slice(text: String, from: String, to: String): String {
        val a = text.indexOf(from)
        assertTrue("源码里找不到起点「$from」", a >= 0)
        val b = text.indexOf(to, a + from.length)
        assertTrue("源码里找不到终点「$to」", b > a)
        return text.substring(a, b)
    }

    private fun appSource(name: String): String {
        for (base in listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))) {
            val f = File(base, "com/eratw/port/android/$name")
            if (f.isFile) return f.readText()
        }
        throw AssertionError("读不到 app 源文件 $name（工作目录 ${File(".").absolutePath}）")
    }

    private fun appMainSources(): List<Pair<String, String>> {
        val root = listOf(File("src/main/kotlin"), File("../app/src/main/kotlin"))
            .firstOrNull { it.isDirectory } ?: throw AssertionError("找不到 app/src/main/kotlin")
        return root.walk().filter { it.isFile && it.extension == "kt" }.map { it.name to it.readText() }.toList()
    }

    /** 注入内存版 SharedPreferences 桩直测 internal 层（照 BusyIndicatorPrefTest 的口径）。 */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return map[key] as? MutableSet<String> ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            private val pending = HashMap<String?, Any?>()

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                pending[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                pending.clear()
                map.clear()
                return this
            }

            override fun commit(): Boolean {
                flush()
                return true
            }

            override fun apply() = flush()

            private fun flush() {
                for ((k, v) in pending) if (k != null) map[k] = v
                pending.clear()
            }
        }
    }
}
