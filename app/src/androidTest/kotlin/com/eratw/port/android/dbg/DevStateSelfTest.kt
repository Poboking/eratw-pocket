package com.eratw.port.android.dbg

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.eratw.port.android.DevLogSlot
import com.eratw.port.android.DevState
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.rand.Sfmt19937
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Path

/**
 * DevTools 五条写侧/观测侧命令（DBG5：set/cheat/snapshot/restore/log）的**真机自测**，
 * 照 [SweepSelfTest] 的「纯逻辑 + 入口类」装配形态：[DevStateSelfTest] 零 android import
 * （入参都是路径），[DevStateSelfTestTest] 是 instrumentation 入口（路径解析 + JUnit 断言
 * 收口 + 摘要落盘）。直测 [DevState] 的 pure 层（入参 GameSession + 参数串 → String），
 * 不经 DevToolsHost（那需要完整 Activity/View 装配）。
 *
 * ## 跑法（需先装 debug 包并完成首启解包：erbc.v1 / CSV 在内部 filesDir/eratw）
 *
 * ```bash
 * adb shell am instrument -w \
 *   -e class com.eratw.port.android.dbg.DevStateSelfTestTest \
 *   com.eratw.port.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 *
 * 独立测试类形态（任务规格允许的二选一）：default 套件不挂它，显式 `-e class` 才跑——
 * 机制自测不该混进每次 connected 检查（与 `-e selftest 1` 参数模式同义，报告已写明）。
 * 摘要落外置 `<externalFilesDir>/eratw/dbg/devstate.selftest.txt`（vivo 吞 logcat，文件为准）。
 *
 * ## 装配口径
 *
 * [DbgAssembly]（同 DBG 装置链，androidTest 侧可直接用）：内部 filesDir/eratw 的
 * erbc.v1 + 游戏根 + cacheDir 沙箱 + randSeed 20260226 + ImmediateInputSink——与桌面
 * vmdbg Assembler 逐字对齐，判定读数与桌面一致的前提。自测的 dev 目录落在 cacheDir
 * （**不**污染外置 dev 通道的 audit/snapshot/dbglog——那是 live 调试的文件面）。
 *
 * ## 四点与实现的一一对应（改动任意一侧必须同步另一侧，SweepSelfTest 同款纪律）
 *
 * 1. set/cheat 注入与 audit trail → [DevState.setPure]/[DevState.cheatPure]
 *    （old→new 回读 + audit.jsonl 字段序 + CodeEE 路径）；
 * 2. snapshot/restore 往返 → [DevState.snapshotPure]/[DevState.restorePure]
 *    （照 CmdSnapTest 口径：变量逐值还原 + RNG 状态逐元素一致 + sidecar 字段 + 缺文件报错；
 *    且锁「LOADDATA 语义」文案——规格 3.2 的真机特化点）；
 * 3. log 三观察口 → [DevState.logPure]（挂口三槽到位 → call 会出号写变量的真语料函数 →
 *    dump 落盘且 event/rng/var 按名单命中 → `log off` 摘净且计数冻结）；
 * 4. 写安全闸 → [DevState.gateDenial]/[DevState.racePrefix] 的布尔形态
 *    （规格 3.1：仅「VM 未跑 或 pendingRequest != null 或 awaitingAnyKey」放行）。
 */
object DevStateSelfTest {

    /** 单条自测结论（SweepSelfTest.Check 同形）。 */
    data class Check(val name: String, val passed: Boolean, val detail: String)

    /**
     * 跑全部四点；全绿时 passed 全为 true。两次装配：会话 A（1+2 点，注入/快照共享状态）
     * 与会话 B（3 点，独立 RNG 保证出号序列干净）。
     */
    fun run(erbcDir: Path, gameRoot: Path, sandboxRoot: Path, devDir: File, maxStepsPerRun: Long): List<Check> {
        val a = DbgAssembly.assemble(
            erbcDir, gameRoot, sandboxRoot.resolve("devstate-a"), maxStepsPerRun, ImmediateInputSink(),
        )
        val check1 = checkSetAndAudit(a.session, devDir)
        val check2 = checkSnapshotRestore(a.session, devDir, sandboxRoot.resolve("devstate-snap"))
        val b = DbgAssembly.assemble(
            erbcDir, gameRoot, sandboxRoot.resolve("devstate-b"), maxStepsPerRun, ImmediateInputSink(),
        )
        val check3 = checkLog(b.session, devDir)
        val check4 = checkGate()
        return listOf(check1, check2, check3, check4)
    }

    /** 汇总文本（落 devstate.selftest.txt / logcat 用；SweepSelfTest.render 同款）。 */
    fun render(checks: List<Check>): String = buildString {
        appendLine("DBG5 DevState selftest：${checks.count { it.passed }}/${checks.size} PASS")
        checks.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.name} —— ${it.detail}") }
    }

    // ------------------------------------------------------------------
    // 1. set/cheat 注入与 audit
    // ------------------------------------------------------------------

    private fun checkSetAndAudit(session: GameSession, devDir: File): Check {
        val dev = File(devDir, "audit-check")
        dev.deleteRecursively()
        val r1 = DevState.setPure(session, dev, "set", "MONEY = 777")
        val okSet = "status=PASS" in r1 && "name=MONEY" in r1 && "new=777" in r1
        val okRead = session.store.readInt("MONEY", longArrayOf(0L)) == 777L
        val r2 = DevState.cheatPure(session, dev, "money 555")
        val okCheat = "new=555" in r2 && session.store.readInt("MONEY", longArrayOf(0L)) == 555L
        // audit.jsonl 落盘与字段序（ts/op/name/idx/old/new，与桌面 CmdSet 同序；old/new 是 JSON 字符串）
        val audit = File(dev, "audit.jsonl")
        val lines = if (audit.isFile) audit.readLines().filter { it.isNotBlank() } else emptyList()
        val okAudit = lines.size == 2 &&
            lines[0].contains("\"op\":\"set\"") &&
            lines[0].contains("\"name\":\"MONEY\"") &&
            lines[0].contains("\"idx\":[0]") &&
            lines[0].contains("\"old\":") &&
            lines[0].contains("\"new\":\"777\"") &&
            lines[1].contains("\"op\":\"cheat\"") &&
            lines[1].contains("\"new\":\"555\"")
        // CodeEE 路径：未定义名给可读文案、不写 audit（重读文件确认仍恰好 2 行）
        val r3 = DevState.setPure(session, dev, "set", "NO_SUCH_VAR_XYZ = 1")
        val okCodeee = "status=CODEEE" in r3 &&
            (if (audit.isFile) audit.readLines().filter { it.isNotBlank() } else emptyList()).size == 2
        val passed = okSet && okRead && okCheat && okAudit && okCodeee
        return Check(
            "set/cheat 注入与 audit",
            passed,
            "set 回读=$okSet/读回777=$okRead/cheat=$okCheat/audit=$okAudit/CODEEE=$okCodeee；audit 行=$lines",
        )
    }

    // ------------------------------------------------------------------
    // 2. snapshot/restore 往返（CmdSnapTest 口径走 DevState 入口）
    // ------------------------------------------------------------------

    private fun checkSnapshotRestore(session: GameSession, devDir: File, snapSandbox: Path): Check {
        val store = session.store
        store.writeInt("MONEY", longArrayOf(0L), 777L)
        store.writeInt("DAY", longArrayOf(0L), 5L)
        repeat(3) { store.randProvider!!.next(100L) } // 推进 RNG，状态非零位
        val stateAtSnap = store.randProvider!!.state()

        val snapDir = File(devDir, "snap-check")
        snapDir.deleteRecursively()
        val main = File(snapDir, "snapshot.dbg")
        val r1 = DevState.snapshotPure(session, snapDir, snapSandbox.toFile(), "--out ${main.absolutePath}")
        val okSnap = "status=PASS" in r1 && main.isFile && main.length() > 0
        // sidecar 字段（主文件 .dbg 结尾 → sidecar 是 .dbg.json）
        val sidecar = File(main.absolutePath + ".json")
        val sideLine = if (sidecar.isFile) sidecar.readLines().firstOrNull() ?: "" else ""
        val okSide = sidecar.isFile &&
            sideLine.contains("\"rngState\":[") &&
            sideLine.contains("\"consoleTail\":[") &&
            sideLine.contains("\"funcStack\":[") &&
            sideLine.contains("\"randSeed\":") &&
            sideLine.contains("\"createdAt\":\"")
        val okRngCount = rngStateCount(sideLine) == Sfmt19937_RANDDATA_LEN

        // 快照后继续污染
        store.writeInt("MONEY", longArrayOf(0L), 1L)
        store.writeInt("DAY", longArrayOf(0L), 99L)
        repeat(7) { store.randProvider!!.next(1000L) }

        val r2 = DevState.restorePure(session, snapDir, snapSandbox.toFile(), main.absolutePath)
        val okRestore = "status=PASS" in r2 && "rngLoaded=true" in r2 &&
            "lastLoadNo=99" in r2 && "LOADDATA 语义" in r2 && "funcStack=not-restored" in r2
        val okVars = store.readInt("MONEY", longArrayOf(0L)) == 777L &&
            store.readInt("DAY", longArrayOf(0L)) == 5L
        val okRng = stateAtSnap.contentEquals(store.randProvider!!.state())
        // 缺文件给可读错误
        val r3 = DevState.restorePure(session, snapDir, snapSandbox.toFile(), File(snapDir, "nope.dbg").absolutePath)
        val okMissing = "快照主文件不存在" in r3

        val passed = okSnap && okSide && okRngCount && okRestore && okVars && okRng && okMissing
        return Check(
            "snapshot/restore 往返",
            passed,
            "snap=$okSnap/sidecar=$okSide/rng625=$okRngCount(实际${rngStateCount(sideLine)})/" +
                "restore=$okRestore/变量还原=$okVars/RNG逐元素=$okRng/缺文件=$okMissing",
        )
    }

    /** sidecar rngState 元素数（独立实现，防与被测代码同源盲区——CmdSnapTestJson 同款纪律）。 */
    private fun rngStateCount(line: String): Int {
        val needle = "\"rngState\":["
        val start = line.indexOf(needle)
        if (start < 0) return -1
        val from = start + needle.length
        val end = line.indexOf(']', from)
        if (end < 0) return -1
        val body = line.substring(from, end).trim()
        if (body.isEmpty()) return 0
        return body.split(',').size
    }

    // ------------------------------------------------------------------
    // 3. log 三观察口
    // ------------------------------------------------------------------

    /**
     * 出号写变量的真语料函数 = `ENEMY_SKILL`（BATTLE.ERB，普通 `@` 函数、非 method 可直调）。
     * ARG=2 分支：写 ENEMY_SKILL_WAIT/NAME/ATK/SPD + `ENEMY_ATK_DIRECTION = RAND:4`——
     * 桌面同款已实证（2026-10-25，vmdbg log --tags event,rng,var --watch ENEMY_SKILL_ATK,
     * ENEMY_ATK_DIRECTION -- call ENEMY_SKILL --args 2）：PASS、2 次 rng draw、
     * ENEMY_SKILL_ATK 0→150 命中 watch。真语料是 DBG 钉死的 4.980，函数不会漂移。
     */
    private fun checkLog(session: GameSession, devDir: File): Check {
        val logDir = File(devDir, "log-check")
        logDir.deleteRecursively()
        val slot = DevLogSlot()
        val store = session.store

        val r1 = DevState.logPure(
            session, logDir,
            "--tags event,rng,var --watch ENEMY_SKILL_ATK,ENEMY_ATK_DIRECTION", slot,
        )
        val okAttach = "log on" in r1
        val rec = slot.recorder
        val okSlots = rec != null &&
            session.runtime.functionProbe === rec &&
            store.writeProbe === rec &&
            (store.randProvider as Sfmt19937).drawProbe === rec

        if (rec != null) {
            session.runtime.callFunction("ENEMY_SKILL", longArrayOf(2L), emptyArray())
        }
        val okGrew = rec != null && rec.count > 0

        val r2 = DevState.logPure(session, logDir, "", slot)
        val dumpFile = File(logDir, "dbglog.txt")
        val dumpLines = if (dumpFile.isFile) dumpFile.readLines().filter { it.isNotBlank() } else emptyList()
        val eventOk = dumpLines.any {
            it.contains("\"tag\":\"event\"") && it.contains("func-enter") && it.contains("ENEMY_SKILL")
        }
        val rngOk = dumpLines.any { it.contains("\"tag\":\"rng\"") && it.contains("\"event\":\"draw\"") }
        val varOk = dumpLines.any {
            it.contains("\"tag\":\"var\"") && it.contains("\"name\":\"ENEMY_SKILL_ATK\"")
        }
        val okDump = dumpFile.isFile && dumpLines.isNotEmpty() && eventOk && rngOk && varOk

        val r3 = DevState.logPure(session, logDir, "off", slot)
        val okOff = "log off" in r3 && slot.recorder == null &&
            session.runtime.functionProbe == null &&
            store.writeProbe == null &&
            (store.randProvider as Sfmt19937).drawProbe == null
        // off 后不再增长（观察口全摘，执行照常）
        val frozen = rec?.count ?: -1L
        session.runtime.callFunction("ENEMY_SKILL", longArrayOf(2L), emptyArray())
        val okFrozen = rec != null && rec.count == frozen

        val passed = okAttach && okSlots && okGrew && okDump && okOff && okFrozen
        return Check(
            "log 三观察口",
            passed,
            "attach=$okAttach/三槽=$okSlots/增长=$okGrew/dump=$okDump(event=$eventOk rng=$rngOk var=$varOk)/" +
                "off=$okOff/冻结=$okFrozen（dump ${dumpLines.size} 行）",
        )
    }

    // ------------------------------------------------------------------
    // 4. 写安全闸
    // ------------------------------------------------------------------

    private fun checkGate(): Check {
        val deny = DevState.gateDenial(true, false, false)
        val okDeny = deny != null && deny.contains("拒绝") && deny.contains("VM 运行中")
        val okIdle = DevState.gateDenial(false, false, false) == null
        val okPending = DevState.gateDenial(true, true, false) == null
        val okAnyKey = DevState.gateDenial(true, false, true) == null
        val okPrefix = DevState.racePrefix(true, false, false) == "[RUNNING竞态] " &&
            DevState.racePrefix(false, false, false) == "" &&
            DevState.racePrefix(true, true, false) == "" &&
            DevState.racePrefix(true, false, true) == ""
        val passed = okDeny && okIdle && okPending && okAnyKey && okPrefix
        return Check(
            "写安全闸",
            passed,
            "运行中拒绝=$okDeny（文案=$deny）/未跑放行=$okIdle/pending放行=$okPending/anyKey放行=$okAnyKey/前缀=$okPrefix",
        )
    }

    /** Sfmt19937 状态字元素数（Sfmt19937.RANDDATA_LEN 的本文件镜像；两处改动必须同步）。 */
    private const val Sfmt19937_RANDDATA_LEN = 625
}

/**
 * [DevStateSelfTest] 的 instrumentation 入口（JUnit 断言收口）：路径解析照 [DeviceSweepTest]
 * 的口径（erbc/游戏根缺省 = 内部 filesDir/eratw 的首启解包落点；`-e erbc`/`-e game`/`-e steps`
 * 可覆盖），摘要落外置 eratw/dbg/devstate.selftest.txt。跑法见 [DevStateSelfTest] KDoc。
 */
class DevStateSelfTestTest {

    @Test
    fun devStateSelfTest() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val externalRoot = context.getExternalFilesDir(null)
            ?: error("getExternalFilesDir(null) 返回 null：外部存储不可用，摘要无处落")
        val dataRoot = File(context.filesDir, "eratw")
        val erbc = File(args.getString("erbc")?.takeIf { it.isNotBlank() } ?: File(dataRoot, "erbc.v1").absolutePath)
        val game = File(args.getString("game")?.takeIf { it.isNotBlank() } ?: dataRoot.absolutePath)
        val maxSteps = args.getString("steps")?.toLongOrNull() ?: 2_000_000L
        // 进程钉活动：instrumentation 目标进程无前台组件会被 Cached App Freezer 冻结
        // （mmap 装载永久 D 态、0 CPU，2026-10-25 vivo 实锤；SERVICE 级也逃不掉，
        // 可见 Activity = TOP = 唯一强保证）。
        DevKeepAliveActivity.start(context)
        // 自测文件面全在 cacheDir：不污染外置 dev 通道（live 调试的 audit/snapshot/dbglog）
        val work = File(context.cacheDir, "dbg5-selftest")
        val checks = try {
            DevStateSelfTest.run(
                erbcDir = erbc.toPath(),
                gameRoot = game.toPath(),
                sandboxRoot = File(work, "sav").toPath(),
                devDir = File(work, "dev"),
                maxStepsPerRun = maxSteps,
            )
        } finally {
            DevKeepAliveActivity.stop()
        }
        val text = DevStateSelfTest.render(checks)
        val dbgDir = File(File(externalRoot, "eratw"), "dbg")
        dbgDir.mkdirs()
        val summary = File(dbgDir, "devstate.selftest.txt")
        summary.writeText(text, Charsets.UTF_8)
        Log.i(TAG, text)
        val failed = checks.filter { !it.passed }
        assertTrue(
            "DBG5 selftest 未全绿（${failed.size}/${checks.size} FAIL，摘要=$summary）：\n" +
                failed.joinToString("\n") { "${it.name}: ${it.detail}" },
            failed.isEmpty(),
        )
    }

    companion object {
        private const val TAG = "DBG5STATE"
    }
}
