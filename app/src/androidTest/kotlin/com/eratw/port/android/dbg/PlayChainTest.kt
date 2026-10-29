package com.eratw.port.android.dbg

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * DBG3 真机**剧本推进装置**（androidTest）：真实产物 + `BEGIN TITLE` → 按剧本逐条提交输入
 * → 每步记 JSONL（新增行数/末行/期望比对/步数/耗时）——「agent 写一串输入，设备自动走完
 * 并留痕」，替代真人点屏验收。与清扫（[DeviceSweepTest]）的本质区别：**不直调函数**，
 * 走 `GameSession.begin(TITLE)` 正规入口，游戏输入经内建 [com.eratw.port.core.proc.QueueInputSink]
 * （[DbgAssembly.assemble] 的 `overrideSink = null`），玩家线程按 BootPlayChain 的
 * 「只在等待且队列空时投递」口径驱动（core-vm/src/test/.../boot/BootPlayChain.kt:38-48）。
 *
 * ## 跑法
 *
 * ```bash
 * adb shell am instrument -w -e class com.eratw.port.android.dbg.PlayChainTest \
 *   [-e script <剧本路径>] [-e out <台账路径>] [-e steps 20000000] [-e seconds 600] \
 *   [-e waitms 30000] [-e commit <基线>] [-e selftest 1] \
 *   com.eratw.port.android.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * hawkeye 的 `play` 子命令把 push 剧本 → am instrument → pull → 摘要包成一条命令。
 *
 * ## 参数缺省（路径口径与 [DeviceSweepTest] 一致）
 *
 * | 参数 | 缺省 | 说明 |
 * |---|---|---|
 * | script | `<externalFilesDir>/eratw/dbg/script.txt` | 剧本（格式见 [PlayScript]） |
 * | out | `<externalFilesDir>/eratw/dbg/play.jsonl` | 台账 |
 * | steps | 20000000 | 步数闸；缺省 = `GameSession.DEFAULT_MAX_STEPS_PER_RUN`（生产同值，见红 J/红 K） |
 * | seconds | 600 | 总墙钟预算（到点收口，已有读数照常落账） |
 * | waitms | 30000 | 每步「等游戏进入等待点」的超时（超 = NO_WAIT 并止驱） |
 *
 * ## 每步 JSONL（字段序钉死）
 *
 * `{"step":<序号>,"input":<提交文本>,"status":"SUBMITTED|EXPECT_OK|EXPECT_FAIL|NO_WAIT|ERROR|DONE",
 *   "newLines":<新增行数>,"tail":<新行末行截200字>,"steps":<VM步数或-1>,"elapsedMs":<毫秒>}`
 * 首行 meta：`{"meta":"device-play","script":<剧本文件名>,"commit":<基线>}`。
 *
 * 收口形态：剧本走完 → 关输入队列，VM 线程以 `InputSinkClosedException` 结束本次 run
 * （BootPlayChain 同款收口），台账补一行 status=DONE 的汇总行（newLines 字段借用为往返数）。
 */
class PlayChainTest {

    @Test
    fun play() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val externalRoot = context.getExternalFilesDir(null)
            ?: error("getExternalFilesDir(null) 返回 null：外部存储不可用，装置无法定位产物")
        // 数据面（erbc/game）在**内部** filesDir 下的 eratw（GameStorage 首启解包落点，
        // 与 DeviceSweepTest 同口径）；台账 out / 剧本 script 留外置（adb 好取好推）。
        val dataRoot = File(context.filesDir, "eratw")

        val out = File(args.getString("out")?.takeIf { it.isNotBlank() }
            ?: File(File(File(externalRoot, "eratw"), "dbg"), "play.jsonl").absolutePath)
        if (args.getString("selftest") == "1") {
            runSelfTest(out)
            return
        }

        val scriptFile = File(args.getString("script")?.takeIf { it.isNotBlank() }
            ?: File(File(File(externalRoot, "eratw"), "dbg"), "script.txt").absolutePath)
        assertTrue("剧本不存在：$scriptFile（push 后再跑；格式见 PlayScript KDoc）", scriptFile.isFile)
        val script = PlayScript.parse(String(Files.readAllBytes(scriptFile.toPath()), Charsets.UTF_8))
        assertTrue("剧本没有可执行步骤：$scriptFile", script.steps.isNotEmpty())

        val erbc = File(args.getString("erbc")?.takeIf { it.isNotBlank() } ?: File(dataRoot, "erbc.v1").absolutePath)
        val game = File(args.getString("game")?.takeIf { it.isNotBlank() } ?: dataRoot.absolutePath)
        val steps = args.getString("steps")?.toLongOrNull() ?: GameSession.DEFAULT_MAX_STEPS_PER_RUN
        val budgetMs = (args.getString("seconds")?.toLongOrNull() ?: 600L) * 1_000L
        val waitMs = args.getString("waitms")?.toLongOrNull() ?: 30_000L

        Log.i(TAG, "play 开始：script=$scriptFile 步数=${script.steps.size} out=$out")
        // 进程钉活动：防 Cached App Freezer 冻结 instrumentation（装载 mmap 永久 D 态，2026-10-25 vivo 实锤）
        DevKeepAliveActivity.start(context)
        val summary = try {
            PlayDriver.drive(
                scriptPath = scriptFile.toPath(),
                steps = script.steps,
                erbcDir = erbc.toPath(),
                gameRoot = game.toPath(),
                sandboxDir = File(context.cacheDir, SANDBOX_DIR).toPath(),
                outPath = out.toPath(),
                maxStepsPerRun = steps,
                budgetMs = budgetMs,
                waitTimeoutMs = waitMs,
                commit = args.getString("commit") ?: "",
            ) { line -> Log.i(TAG, line) }
        } finally {
            DevKeepAliveActivity.stop()
        }
        Log.i(TAG, "play 结束：$summary（台账=$out）")
    }

    /** selftest 路径：机制四点（解析/特殊 token/期望切分/JSONL 格式），摘要落 play.selftest.txt。 */
    private fun runSelfTest(out: File) {
        val text = PlaySelfTest.render(PlaySelfTest.run())
        val summary = File(out.absolutePath.removeSuffix(".jsonl") + ".selftest.txt")
        summary.parentFile?.mkdirs()
        summary.writeText(text, Charsets.UTF_8)
        Log.i(TAG, text)
        val failed = text.lineSequence().filter { it.startsWith("FAIL") }.count()
        assertTrue("DBG3 play selftest 有 $failed 条 FAIL（摘要=$summary）", failed == 0)
    }

    companion object {
        private const val TAG = "DBG3PLAY"

        /** 存档沙箱在 instrumentation context.cacheDir 下的子目录（读真游戏根、写全落这里）。 */
        const val SANDBOX_DIR = "dbg3-play/sav"
    }
}

/**
 * 剧本驱动（纯 Kotlin：会话装配经 [DbgAssembly]，Android 面（路径/参数/logcat）全在
 * [PlayChainTest]）。VM 线程跑一次 `begin(TITLE)`（阻塞式等待横跨全部输入，BootPlayChain
 * 同模型）；玩家线程逐条投递并记账。
 */
object PlayDriver {

    class Summary(
        val steps: Int,
        val expectOk: Int,
        val expectFail: Int,
        val noWait: Int,
        val error: Boolean,
        val stoppedBy: String,
    ) {
        override fun toString(): String =
            "steps=$steps expectOk=$expectOk expectFail=$expectFail noWait=$noWait " +
                "error=$error stoppedBy=$stoppedBy"
    }

    /** 每步状态集（KDoc 的口径；DONE 只出现在收口汇总行）。 */
    object StepStatus {
        const val SUBMITTED = "SUBMITTED"
        const val EXPECT_OK = "EXPECT_OK"
        const val EXPECT_FAIL = "EXPECT_FAIL"
        const val NO_WAIT = "NO_WAIT"
        const val ERROR = "ERROR"
        const val DONE = "DONE"
    }

    fun drive(
        scriptPath: Path,
        steps: List<PlayScript.Step>,
        erbcDir: Path,
        gameRoot: Path,
        sandboxDir: Path,
        outPath: Path,
        maxStepsPerRun: Long,
        budgetMs: Long,
        waitTimeoutMs: Long,
        commit: String,
        log: (String) -> Unit,
    ): Summary {
        val assembled = DbgAssembly.assemble(
            erbcDir = erbcDir,
            gameRoot = gameRoot,
            sandboxDir = sandboxDir,
            maxStepsPerRun = maxStepsPerRun,
            overrideSink = null,
        )
        val session = assembled.session
        val sink = session.inputSink
        val console = session.console
        outPath.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }

        // VM 线程：一次 begin(TITLE) 横跨全部输入等待；结束原因兜底记到 vmEnd
        //（局部变量不能 @Volatile，跨线程可见性用 AtomicReference）。
        val vmEnd = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val vmThread = Thread({
            vmEnd.set(
                try {
                    session.begin(BeginType.TITLE)
                    "begin-return"
                } catch (e: com.eratw.port.core.proc.InputSinkClosedException) {
                    "closed"
                } catch (e: CodeEE) {
                    "CodeEE: ${e.funcName ?: "?"}:${e.line} ${e.message?.take(200)}"
                } catch (t: Throwable) {
                    "${t.javaClass.simpleName}: ${t.message?.take(200)}"
                },
            )
        }, "eratw-dbg3-play-vm").apply { isDaemon = true }
        vmThread.start()

        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        var expectOk = 0
        var expectFail = 0
        var noWait = 0
        var error = false
        var stoppedBy = "script-done"

        SweeperLedger(outPath).use { ledger ->
            ledger.writeMeta(
                "{\"meta\":\"device-play\",\"script\":${SweepJson.quote(scriptPath.fileName.toString())}," +
                    "\"commit\":${SweepJson.quote(commit)}}",
            )
            for ((i, step) in steps.withIndex()) {
                // 等游戏进入等待点（INT/STR/任意键任一形态）且队列空，预算 = 每步超时。
                if (!awaitWaitPoint(sink, vmThread, waitTimeoutMs * 1_000_000L)) {
                    if (vmThread.isAlive) {
                        noWait++
                        stoppedBy = "no-wait@${i + 1}"
                        ledger.appendTerminal(rowOf(i, step, StepStatus.NO_WAIT, 0, "", -1, 0))
                        break
                    }
                    error = true
                    stoppedBy = "vm-ended@${i + 1}(${vmEnd.get()})"
                    ledger.appendTerminal(rowOf(i, step, StepStatus.ERROR, 0, vmEnd.get() ?: "?", -1, 0))
                    break
                }
                val before = console.lines().size
                val startNs = System.nanoTime()
                sink.submit(step.input, step.skip)
                // 等推进到下一个等待点（或 VM 结束）；页面计算可能慢，给每步超时的两倍。
                awaitWaitPoint(sink, vmThread, waitTimeoutMs * 2_000_000L)
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                val lines = console.lines()
                val newLines = lines.size - before
                val tail = lines.lastOrNull()?.let { console.textOf(it) }?.take(200) ?: ""
                val vmSteps = executedSteps(session.runtime)
                var status = StepStatus.SUBMITTED
                if (step.expect != null) {
                    val hit = lines.drop(before.coerceAtMost(lines.size)).any { console.textOf(it).contains(step.expect) }
                    status = if (hit) {
                        expectOk++
                        StepStatus.EXPECT_OK
                    } else {
                        expectFail++
                        StepStatus.EXPECT_FAIL
                    }
                }
                ledger.appendTerminal(rowOf(i, step, status, newLines, tail, vmSteps, elapsedMs))
                if (!vmThread.isAlive) {
                    error = true
                    stoppedBy = "vm-ended@${i + 1}(${vmEnd.get()})"
                    break
                }
                if (System.nanoTime() > deadline) {
                    stoppedBy = "budget@${i + 1}"
                    break
                }
            }
            // 收口：关队列让 VM 线程以 closed 结束当前 run（BootPlayChain 同款）。
            sink.close()
            runCatching { vmThread.join(10_000) }
            ledger.appendTerminal(
                "{\"step\":-1,\"input\":\"\",\"status\":\"${StepStatus.DONE}\"," +
                    "\"newLines\":${steps.size - noWait},\"tail\":${SweepJson.quote(stoppedBy)}," +
                    "\"steps\":${executedSteps(session.runtime)},\"elapsedMs\":0}",
            )
        }
        session.close()
        log("play 完成：$stoppedBy expectOk=$expectOk expectFail=$expectFail noWait=$noWait → $outPath")
        return Summary(steps.size, expectOk, expectFail, noWait, error, stoppedBy)
    }

    /** 等到「正在等待且队列空」或 VM 结束；false = 超时（VM 仍活着）。 */
    private fun awaitWaitPoint(
        sink: com.eratw.port.core.proc.QueueInputSink,
        vmThread: Thread,
        timeoutNs: Long,
    ): Boolean {
        val deadline = System.nanoTime() + timeoutNs
        while (System.nanoTime() < deadline) {
            if (!vmThread.isAlive) return true
            if (sink.pendingCount == 0 && (sink.isAwaiting || sink.awaitingAnyKey)) return true
            Thread.sleep(10)
        }
        return !vmThread.isAlive
    }

    /** 单 run 步数（反射读 ProcessRuntime.vm.executedSteps；与清扫同口径）。 */
    private fun executedSteps(runtime: ProcessRuntime): Long = runCatching {
        val field = ProcessRuntime::class.java.getDeclaredField("vm")
        field.isAccessible = true
        (field.get(runtime) as? VirtualMachine)?.executedSteps ?: -1L
    }.getOrDefault(-1L)

    private fun rowOf(
        index: Int,
        step: PlayScript.Step,
        status: String,
        newLines: Int,
        tail: String,
        steps: Long,
        elapsedMs: Long,
    ): String =
        "{\"step\":$index,\"input\":${SweepJson.quote(step.input)},\"status\":\"$status\"," +
            "\"newLines\":$newLines,\"tail\":${SweepJson.quote(tail)}," +
            "\"steps\":$steps,\"elapsedMs\":$elapsedMs}"
}

/** 剧本机制的纯 JVM 自测（解析 / 特殊 token / 期望切分 / JSONL 字段序）。 */
object PlaySelfTest {

    data class Check(val name: String, val passed: Boolean, val detail: String)

    fun run(): List<Check> = listOf(checkParse(), checkTokens(), checkJsonlOrder())

    fun render(checks: List<Check>): String = buildString {
        appendLine("DBG3 play selftest：${checks.count { it.passed }}/${checks.size} PASS")
        checks.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.name} —— ${it.detail}") }
    }

    private fun checkParse(): Check {
        val p = PlayScript.parse(
            """
            # 注释行
            0 ;; 请选择游戏模式
            1

            100;;库存
            """.trimIndent(),
        )
        val ok = p.steps.size == 3 && p.skippedLines == 2 &&
            p.steps[0].input == "0" && p.steps[0].expect == "请选择游戏模式" &&
            p.steps[1].input == "1" && p.steps[1].expect == null &&
            p.steps[2].input == "100" && p.steps[2].expect == "库存"
        return Check("剧本解析", ok, "steps=${p.steps.size} skipped=${p.skippedLines}（期望 3/2）")
    }

    private fun checkTokens(): Check {
        val p = PlayScript.parse("\$any\n\$skip ;; 任意键后应有下一页\n坏行 ;; 期望里可以有 ;; 双分号")
        val s0 = p.steps[0]
        val s1 = p.steps[1]
        val s2 = p.steps[2]
        val ok = !s0.skip && s0.input == "" &&
            s1.skip && s1.input == "" && s1.expect == "任意键后应有下一页" &&
            s2.input == "坏行" && s2.expect == "期望里可以有 ;; 双分号"
        return Check("特殊 token 与 ;; 切分", ok, "any=input空 skip=" + s1.skip + " 双分号期望保留=" + s2.expect)
    }

    private fun checkJsonlOrder(): Check {
        val meta = com.eratw.port.core.proc.FuncMeta(index = 1, key = "K", name = "N")
        val row = SweepJson.row(meta, "PASS", "-", -1, -1, "", 0L, 0L)
        val keys = listOf("fn", "key", "status", "cls", "line", "pc", "msg", "steps", "elapsedMs")
        val positions = keys.map { row.indexOf("\"$it\":") }
        val ok = positions == positions.sorted() && positions.none { it < 0 }
        return Check("JSONL 字段序", ok, "下标=$positions（须严格递增）")
    }
}
