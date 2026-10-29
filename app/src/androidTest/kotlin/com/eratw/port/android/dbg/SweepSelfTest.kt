package com.eratw.port.android.dbg

import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FuncMeta
import java.nio.file.Files
import java.nio.file.Path

/**
 * DBG 清扫装置的机制自测（纯 Kotlin，零 android import）：不依赖语料产物，把
 * 口径里钉死的四个机制点逐个跑一遍，任何一点不符都给出可读差异文本。
 *
 * 执行形态 = `selftest=1` instrumentation 参数（androidTest 侧以 [DeviceSweepTest]
 * 为入口、JUnit 断言收口；设备上跑法见该类 KDoc）。
 *
 * 四点与实现的一一对应（改动任意一侧必须同步另一侧）：
 * 1. 实参伪造 → [FunctionSweeper.forgeArgs]
 * 2. RUNNING 占位与覆写 → [SweeperLedger] 的 writeRunning / overwriteTerminal
 * 3. JSONL 字段序 → [SweepJson.row] / [SweepJson.metaLine] / [SweepJson.quote]
 *    （fn,key,status,cls,line,pc,msg,steps,elapsedMs——与桌面 vmdbg 同序）
 * 4. 状态判定 → [FunctionSweeper.classifyThrowable]
 *    （WALL_LIMIT 先于 CodeEE 系；StepLimitExceeded/CallStackOverflow 是其子类、先判）
 */
object SweepSelfTest {

    /**
     * 读整个文本文件（UTF-8）。不用 `Files.readString`：那是 Java 11 API，androidTest
     * 编译面的 android.jar 没有（core-vm 侧 2026-10-22 同款雷，AndroidApiCompatTest 抓过）。
     */
    private fun readText(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)

    /** 单条自测结论。 */
    data class Check(val name: String, val passed: Boolean, val detail: String)

    /** 跑全部四点；全绿时 passed 全为 true。 */
    fun run(): List<Check> = listOf(
        checkForgeArgs(),
        checkRunningPlaceholderOverwrite(),
        checkJsonlFieldOrder(),
        checkStatusClassification(),
    )

    /** 汇总文本（落 `.selftest.txt` / logcat 用）。 */
    fun render(checks: List<Check>): String = buildString {
        appendLine("DBG sweep selftest：${checks.count { it.passed }}/${checks.size} PASS")
        checks.forEach { appendLine("${if (it.passed) "PASS" else "FAIL"}  ${it.name} —— ${it.detail}") }
    }

    // ------------------------------------------------------------------
    // 1. 实参伪造：argIntCount 个 0 + argStrCount 个空串
    // ------------------------------------------------------------------

    private fun checkForgeArgs(): Check {
        val meta = FuncMeta(
            index = 7,
            key = "SELFTEST_FORGE",
            argIntCount = 2,
            argStrCount = 3,
        )
        val (intArgs, strArgs) = FunctionSweeper.forgeArgs(meta)
        val okInt = intArgs.size == 2 && intArgs[0] == 0L && intArgs[1] == 0L
        val okStr = strArgs.size == 3 && strArgs.all { it.isEmpty() }
        val bare = FunctionSweeper.forgeArgs(FuncMeta(index = 8, key = "SELFTEST_FORGE_BARE"))
        val okBare = bare.first.isEmpty() && bare.second.isEmpty()
        val passed = okInt && okStr && okBare
        return Check(
            "实参伪造",
            passed,
            "int=${intArgs.toList()} str=${strArgs.toList()}（期望 [0,0]/[\"\",\"\",\"\"]）；" +
                "零参函数=${bare.first.size}/${bare.second.size}（期望 0/0）",
        )
    }

    // ------------------------------------------------------------------
    // 2. RUNNING 占位与覆写：占位可见 → 终态覆写后恰一行
    // ------------------------------------------------------------------

    private fun checkRunningPlaceholderOverwrite(): Check {
        val dir: Path = Files.createTempDirectory("dbg3-selftest")
        val ledgerPath = dir.resolve("ledger.jsonl")
        val meta = FuncMeta(index = 9, key = "SELFTEST_LEDGER", name = "自测台账函数")
        try {
            SweeperLedger(ledgerPath).use { ledger ->
                ledger.writeMeta(SweepJson.metaLine(0, 1, "deadbeef"))
                // 占位态：文件必须恰好 meta + 一行 RUNNING（进程此刻挂死 → 肇事者可定位）。
                val at = ledger.writeRunning(meta)
                val midText = readText(ledgerPath)
                val midLines = midText.split('\n').filter { it.isNotBlank() }
                val midOk = midLines.size == 2 &&
                    midLines[0].contains("\"meta\":\"device-sweep\"") &&
                    midLines[1].contains("\"status\":\"RUNNING\"") &&
                    midLines[1].contains("\"fn\":\"自测台账函数\"")
                // 终态覆写：截回占位偏移再写终态 —— 全文件不得再剩 RUNNING。
                val terminal = SweepJson.row(
                    meta, FunctionSweeper.SweepStatus.PASS, "-", -1, -1, "", 1234L, 5L,
                )
                ledger.overwriteTerminal(at, terminal)
                val endText = readText(ledgerPath)
                val endLines = endText.split('\n').filter { it.isNotBlank() }
                val endOk = endLines.size == 2 &&
                    endLines[1] == terminal &&
                    !endText.contains(FunctionSweeper.SweepStatus.RUNNING)
                // 再扫一个函数：占位/覆写可以连续多轮（文件只增行不残留）。
                val at2 = ledger.writeRunning(meta)
                ledger.overwriteTerminal(
                    at2,
                    SweepJson.row(meta, FunctionSweeper.SweepStatus.CODEEE, "CodeEE", 42, 7, "boom", -1L, 9L),
                )
                val finalText = readText(ledgerPath)
                val finalLines = finalText.split('\n').filter { it.isNotBlank() }
                val finalOk = finalLines.size == 3 &&
                    finalLines[2].contains("\"status\":\"CODEEE\"") &&
                    finalLines[2].contains("\"line\":42") &&
                    finalLines[2].contains("\"pc\":7")
                val passed = midOk && endOk && finalOk
                return Check(
                    "RUNNING 占位与覆写",
                    passed,
                    "占位态 2 行含 RUNNING=$midOk；覆写后恰 2 行无 RUNNING=$endOk；" +
                        "第二轮覆写后 3 行且位置字段在位=$finalOk",
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 3. JSONL 字段序：fn,key,status,cls,line,pc,msg,steps,elapsedMs
    // ------------------------------------------------------------------

    private fun checkJsonlFieldOrder(): Check {
        val meta = FuncMeta(index = 10, key = "SELFTEST_JSON", name = "书 写「名」")
        val row = SweepJson.row(
            meta, FunctionSweeper.SweepStatus.STEP_LIMIT, "StepLimitExceeded", 3, 4,
            "he said \"hi\"\nline2\\", 99L, 11L,
        )
        // 字段序按「字段名" 的出现下标」逐个钉：fn < key < status < cls < ... < elapsedMs。
        val keys = listOf("fn", "key", "status", "cls", "line", "pc", "msg", "steps", "elapsedMs")
        val positions = keys.map { key -> row.indexOf("\"$key\":") }
        val ordered = positions == positions.sorted() && positions.none { it < 0 }
        val prefix = row.startsWith("{\"fn\":")
        val escaped = row.contains("he said \\\"hi\\\"") &&
            row.contains("\\nline2\\\\") &&
            !row.contains("\"hi\"")
        val metaLine = SweepJson.metaLine(2, 4, "abc")
        val metaOk = metaLine ==
            "{\"meta\":\"device-sweep\",\"shard\":2,\"n\":4,\"commit\":\"abc\"}"
        val running = SweepJson.runningLine(meta)
        val runningOk = running.contains("\"status\":\"RUNNING\"") &&
            running.contains("\"cls\":\"-\"") &&
            running.contains("\"line\":-1") &&
            running.contains("\"pc\":-1") &&
            running.contains("\"steps\":-1") &&
            running.contains("\"elapsedMs\":-1") &&
            running.contains("\"msg\":\"\"")
        val passed = ordered && prefix && escaped && metaOk && runningOk
        return Check(
            "JSONL 字段序",
            passed,
            "字段下标=$positions（须严格递增）；前缀=$prefix；转义=$escaped；" +
                "meta 行=$metaOk；占位行中性值=$runningOk",
        )
    }

    // ------------------------------------------------------------------
    // 4. 状态判定：WallLimitExceeded 先于 CodeEE 系；StepLimit 系是 CodeEE 子类、先判
    // ------------------------------------------------------------------

    private fun checkStatusClassification(): Check {
        val pass = FunctionSweeper.classifyThrowable(null)
        val passOk = pass.status == FunctionSweeper.SweepStatus.PASS
        val wall = FunctionSweeper.classifyThrowable(WallLimitExceeded(10_000, 12_345, 7))
        val wallOk = wall.status == FunctionSweeper.SweepStatus.WALL_LIMIT &&
            wall.line == -1 && wall.pc == -1
        val step = FunctionSweeper.classifyThrowable(
            StepLimitExceeded(steps = 2_000_001L, line = 7, pc = 42, funcName = "F", callChain = null),
        )
        val stepOk = step.status == FunctionSweeper.SweepStatus.STEP_LIMIT &&
            step.line == 7 && step.pc == 42 && step.steps == 2_000_001L
        val code = FunctionSweeper.classifyThrowable(CodeEE("脚本错了", "F", 3, 9))
        val codeOk = code.status == FunctionSweeper.SweepStatus.CODEEE &&
            code.line == 3 && code.pc == 9
        // 无位置的 CodeEE（表达式层抛出不经 stampPosition 的形态）：位置字段按 -1 落行。
        val codeBare = FunctionSweeper.classifyThrowable(CodeEE("无位置"))
        val codeBareOk = codeBare.status == FunctionSweeper.SweepStatus.CODEEE &&
            codeBare.line == -1 && codeBare.pc == -1
        val other = FunctionSweeper.classifyThrowable(IllegalStateException("宿主炸了"))
        val otherOk = other.status == FunctionSweeper.SweepStatus.ERROR &&
            other.line == -1 && other.pc == -1
        val passed = passOk && wallOk && stepOk && codeOk && codeBareOk && otherOk
        return Check(
            "状态判定",
            passed,
            "PASS=($passOk)；WALL_LIMIT=($wallOk) 位置-1；STEP_LIMIT=($stepOk) line=7 pc=42 steps=2000001；" +
                "CODEEE=($codeOk) line=3 pc=9；无位置 CODEEE=($codeBareOk)；ERROR=($otherOk)",
        )
    }
}
