package com.eratw.port.android.dbg

import com.eratw.port.core.code.CallStackOverflow
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.ProcessRuntime
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * DBG 全量函数清扫的**核心驱动**（纯 Kotlin：零 android-platform import；Android 胶水在
 * [DeviceSweepTest]，共享装配在 [DbgAssembly]）。
 *
 * ## 口径 = 桌面 vmdbg sweep（DBG3 起逐字对齐，平台差分以此为约）
 *
 * 与 tools/vmdbg 的 CmdSweep 语义一致项（两侧改动必须同步）：
 * - 装配 = [DbgAssembly.assemble]（randSeed 20260226 / frameGateEnabled=false /
 *   存档沙箱读根=游戏根写根=沙箱 / inputSinkOverride=[ImmediateInputSink] / AWAIT 墙钟）；
 * - 清单过滤 = 跳过 `isEvent`、`position == "·event-entry·"`、`position == "synthetic-event-entry"`、
 *   key 含 `#` 的 erbcc 合成入口；[SweepConfig.skipMethods] 时跳过 `isMethod`
 *   （#FUNCTION/#FUNCTIONS 不可直调，原版语义拒之，留在清单里只会产出可预测的 CODEEE 噪声簇）；
 *   被过滤者**不写行**（与桌面同形；事件计数进 Summary）；
 * - `--only` 正则匹配 name 或 key；shard 按**全清单下标**取模（与桌面同序，两侧 shard 可对位）；
 * - 逐函数：先写 status=RUNNING 占位行并即刻落盘 → 换装 [WallBudget] →
 *   `callFunction(key, argIntCount 个 0, argStrCount 个空串)` → 覆写终态；
 * - 终态分类 [classifyThrowable]（顺序敏感）：WallLimitExceeded → WALL_LIMIT；
 *   StepLimitExceeded → STEP_LIMIT；CallStackOverflow → STACK_OVERFLOW；CodeEE → CODEEE；
 *   其余 → ERROR。位置字段直读 CodeEE 的 funcName/line/pc 结构化字段（err/CodeEE.kt:20），
 *   异常类名进 `cls`；
 * - JSONL 字段序钉死：fn, key, status, cls, line, pc, msg, steps, elapsedMs
 *   （与桌面 SweptRecord.toJson 同序；ledger 按字段名解析，多 meta 行无害）。
 *
 * ## 与桌面的已知差异（登记，不改）
 *
 * - 首行多一条 `{"meta":"device-sweep",...}` 对账行（桌面 ledger 解析时因无 key 字段跳过）；
 * - RUNNING 占位行是全 schema + 中性值（桌面是 3 字段短行；两侧 ledger 都按 status=RUNNING 跳过）。
 */
object FunctionSweeper {

    /** 桌面钉死的 RNG 种子（与 DbgAssembly.RAND_SEED / 桌面 Cli.RAND_SEED 同值）。 */
    const val RAND_SEED = DbgAssembly.RAND_SEED

    /** 单次 run 的步数闸缺省（2,000,000；与桌面 Cli.DEFAULT_SWEEP_STEPS 同值）。 */
    const val DEFAULT_MAX_STEPS = 2_000_000L

    /** message 截断上限（与桌面 sweep 报告口径一致：500 字）。 */
    const val MAX_MSG_CHARS = 500

    private const val PROGRESS_EVERY = 2000

    /** 一次清扫的汇总读数（logcat/报告摘要用，不进台账）。 */
    class Summary(
        val swept: Int,
        val skipped: Int,
        val passed: Int,
        val codeee: Int,
        val stepLimit: Int,
        val wallLimit: Int,
        val error: Int,
    )

    /**
     * 跑一个 shard 的全量清扫。阻塞至整个 shard 结束；台账逐行落 [SweepConfig.outPath]
     * （UTF-8 + LF；打开即截断）。
     *
     * @throws IllegalStateException 产物缺失 / 装配期失败（原样冒泡，交 instrumentation 记失败）。
     */
    fun run(config: SweepConfig, log: (String) -> Unit = {}): Summary {
        val assembled = DbgAssembly.assemble(
            erbcDir = config.erbcDir,
            gameRoot = config.gameRoot,
            sandboxDir = config.sandboxSavDir,
            maxStepsPerRun = config.maxStepsPerRun,
            overrideSink = ImmediateInputSink(),
        )
        val runtime = assembled.session.runtime
        val sink = assembled.overrideSink!!
        val metadata = assembled.metadata
        config.outPath.toAbsolutePath().normalize().parent?.let { Files.createDirectories(it) }

        SweeperLedger(config.outPath).use { ledger ->
            ledger.writeMeta(SweepJson.metaLine(config.shard, config.nshard, config.commit))
            var swept = 0
            var skipped = 0
            var passed = 0
            var codeee = 0
            var stepLimit = 0
            var wallLimit = 0
            var errorCount = 0
            val only = config.only
            log(
                "sweep: 清单 ${metadata.functions.size} 本次 shard ${config.shard}/${config.nshard} " +
                    "steps=${config.maxStepsPerRun} wallMs=${config.wallMs}",
            )
            // 迭代序 = 侧车装载序；shard 按全清单下标取模（与桌面同序）。
            metadata.functions.forEachIndexed { idx, meta ->
                if (idx % config.nshard != config.shard) return@forEachIndexed
                if (meta.isEvent || meta.position == EVENT_ENTRY_A || meta.position == EVENT_ENTRY_B) {
                    skipped++
                    return@forEachIndexed
                }
                if ('#' in meta.key) return@forEachIndexed
                if (config.skipMethods && meta.isMethod) return@forEachIndexed
                if (only != null && !only.containsMatchIn(meta.name) && !only.containsMatchIn(meta.key)) {
                    return@forEachIndexed
                }
                val (intArgs, strArgs) = forgeArgs(meta)
                // 占位行先落盘（无缓冲直写）：进程此后任意一刻挂死，台账末行的 RUNNING fn 就是肇事者。
                val placeholderAt = ledger.writeRunning(meta)
                sink.budget = WallBudget(config.wallMs)
                val startNs = System.nanoTime()
                var error: Throwable? = null
                try {
                    runtime.callFunction(meta.key, intArgs, strArgs)
                } catch (t: Throwable) {
                    error = t
                }
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                sink.budget = null
                val steps = executedStepsOf(runtime)
                val v = classifyThrowable(error)
                when (v.status) {
                    SweepStatus.PASS -> passed++
                    SweepStatus.CODEEE -> codeee++
                    SweepStatus.STEP_LIMIT, SweepStatus.STACK_OVERFLOW -> stepLimit++
                    SweepStatus.WALL_LIMIT -> wallLimit++
                    else -> errorCount++
                }
                ledger.overwriteTerminal(
                    placeholderAt,
                    SweepJson.row(
                        meta = meta,
                        status = v.status,
                        cls = error?.javaClass?.simpleName ?: "-",
                        line = v.line,
                        pc = v.pc,
                        msg = (error?.message ?: "").take(MAX_MSG_CHARS),
                        steps = if (v.status == SweepStatus.PASS) steps else v.steps,
                        elapsedMs = elapsedMs,
                    ),
                )
                swept++
                if (swept % PROGRESS_EVERY == 0) log("swept=$swept（进度读数；逐函数明细见台账）")
            }
            log(
                "sweep 完成：swept=$swept skip=$skipped pass=$passed codeee=$codeee " +
                    "stepLimit=$stepLimit wallLimit=$wallLimit error=$errorCount → ${config.outPath}",
            )
            return Summary(swept, skipped, passed, codeee, stepLimit, wallLimit, errorCount)
        }
    }

    // ------------------------------------------------------------------
    // 口径件（自测逐点对齐的对象；改动必须同步 SweepSelfTest）
    // ------------------------------------------------------------------

    /** 实参伪造：`argIntCount` 个 0 + `argStrCount` 个空串（与桌面同款）。 */
    fun forgeArgs(meta: FuncMeta): Pair<LongArray, Array<String>> =
        LongArray(meta.argIntCount) to Array(meta.argStrCount) { "" }

    /** 台账状态集（与桌面 Status.classify 的取值面一致 + RUNNING 占位）。 */
    object SweepStatus {
        const val PASS = "PASS"
        const val CODEEE = "CODEEE"
        const val STEP_LIMIT = "STEP_LIMIT"
        const val STACK_OVERFLOW = "STACK_OVERFLOW"
        const val WALL_LIMIT = "WALL_LIMIT"
        const val ERROR = "ERROR"
        const val NOT_FOUND = "NOT_FOUND"
        const val RUNNING = "RUNNING"
    }

    /** [classifyThrowable] 的判定产物：status + CodeEE 结构化位置 + 步数（仅 STEP_LIMIT 带）。 */
    data class Verdict(val status: String, val line: Int, val pc: Int, val steps: Long = -1L)

    /**
     * 终态判定（顺序敏感，与桌面 Status.classify 同序）：WallLimitExceeded 必须先于 CodeEE 系
     * （墙钟不是脚本错误）；StepLimitExceeded/CallStackOverflow 是 CodeEE 子类、必须先判；
     * 位置一律直读 CodeEE 的结构化字段，不做 VM lastPc 反射兜底——桌面同口径。
     */
    fun classifyThrowable(t: Throwable?): Verdict = when (t) {
        null -> Verdict(SweepStatus.PASS, -1, -1)
        is WallLimitExceeded -> Verdict(SweepStatus.WALL_LIMIT, -1, -1)
        is StepLimitExceeded -> Verdict(SweepStatus.STEP_LIMIT, t.line, t.pc, t.steps)
        is CallStackOverflow -> Verdict(SweepStatus.STACK_OVERFLOW, t.line, t.pc)
        is CodeEE -> Verdict(SweepStatus.CODEEE, t.line, t.pc)
        else -> Verdict(SweepStatus.ERROR, -1, -1)
    }

    /**
     * 单次 run 的实际步数（PASS 行的 steps）：反射读 `ProcessRuntime.vm`（private，与桌面
     * StepReader 同口径），`executedSteps` 是 VM 公开属性。取不到时 -1，不影响台账。
     */
    private fun executedStepsOf(runtime: ProcessRuntime): Long = runCatching {
        val field = ProcessRuntime::class.java.getDeclaredField("vm")
        field.isAccessible = true
        (field.get(runtime) as? VirtualMachine)?.executedSteps ?: -1L
    }.getOrDefault(-1L)

    /** 侧车里 erbcc 合成事件入口的 position 标记（与桌面 CmdSweep 的过滤字面量一致）。 */
    private const val EVENT_ENTRY_A = "·event-entry·"
    private const val EVENT_ENTRY_B = "synthetic-event-entry"
}

/** 清扫的输入集合。纯数据，无行为。 */
data class SweepConfig(
    /** erbc 产物目录（含 program.erbc / program.meta）。 */
    val erbcDir: Path,
    /** 游戏根（CSV/resources/sound/emuera.config 所在层；读路径的真根）。 */
    val gameRoot: Path,
    /** 存档沙箱根（写路径唯一的落点；建议放 instrumentation context.cacheDir 下）。 */
    val sandboxSavDir: Path,
    /** 台账输出路径（JSONL，UTF-8 + LF；打开即截断重来）。 */
    val outPath: Path,
    /** 单次 run 步数闸（缺省 2,000,000）。 */
    val maxStepsPerRun: Long = FunctionSweeper.DEFAULT_MAX_STEPS,
    /** 逐函数墙钟预算毫秒（缺省 10,000；`<= 0` = 关闭墙钟检查）。 */
    val wallMs: Long = DbgAssembly.DEFAULT_WALL_MS,
    /** 跳过 #FUNCTION/#FUNCTIONS 方法（不可直调，原版语义拒之）。 */
    val skipMethods: Boolean = false,
    /** shard 序号（本进程只跑全清单下标 % nshard == shard 的函数）。 */
    val shard: Int = 0,
    /** shard 总数（1 = 不分片）。 */
    val nshard: Int = 1,
    /** 可选：按 name 或 key 过滤的正则；null = 全量。被过滤掉的函数不写行。 */
    val only: Regex? = null,
    /** 基线 commit（写进 meta 行便于差分对账；缺省空串）。 */
    val commit: String = "",
)

/**
 * 台账写入器：JSONL、UTF-8、LF，逐行即时落盘（[RandomAccessFile] 无用户态缓冲，
 * 每次 write 都是一次 syscall——挂死进程的台账不丢最后一条占位行）。
 *
 * 「覆写终态」的机制：占位行写入前记下文件偏移，终态就位后 [RandomAccessFile.setLength]
 * 截回偏移、再 seek 写终态行——台账里每个函数最终恰好一行（挂死者除外，它停在 RUNNING）。
 * 打开即截断（新一轮清扫不背上一轮的旧行）。
 */
class SweeperLedger(path: Path) : AutoCloseable {

    private val file = RandomAccessFile(path.toAbsolutePath().normalize().toFile(), "rw")

    init {
        file.setLength(0L)
    }

    /** meta 行（对账头），打开后第一行。 */
    fun writeMeta(line: String) {
        append(line)
    }

    /**
     * 写 status=RUNNING 的占位行并返回它的起始偏移（终态覆写要用）。
     * 占位行也是全 schema（ledger 的消费方按同一套解析走，不必为占位行做两套）。
     */
    fun writeRunning(meta: FuncMeta): Long {
        val at = file.length()
        append(SweepJson.runningLine(meta))
        return at
    }

    /** 截回 [at] 并写终态行。 */
    fun overwriteTerminal(at: Long, line: String) {
        file.setLength(at)
        file.seek(at)
        append(line)
    }

    /** 直接追加一行终态（meta 行用；无占位阶段）。 */
    fun appendTerminal(line: String) {
        append(line)
    }

    private fun append(line: String) {
        file.seek(file.length())
        file.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    override fun close() {
        file.channel.force(true)
        file.close()
    }
}

/**
 * 台账行的 JSON 出料（schema 钉死，字段名与顺序一个字母/一位都不许动）：
 *
 * ```
 * {"fn":<书写名>,"key":<大写键>,"status":"...","cls":<异常类或->,"line":<行号或-1>,
 *  "pc":<pc或-1>,"msg":<异常message截500字>,"steps":<步数或-1>,"elapsedMs":<毫秒>}
 * ```
 *
 * 自带极小转义器（quote / 反斜杠 / 控制字符），不引平台 JSON 库——本文件零 android import。
 */
object SweepJson {

    /** 终态行统一走这里；字段序 = fn,key,status,cls,line,pc,msg,steps,elapsedMs。 */
    fun row(
        meta: FuncMeta,
        status: String,
        cls: String,
        line: Int,
        pc: Int,
        msg: String,
        steps: Long,
        elapsedMs: Long,
    ): String = buildString {
        append("{\"fn\":").append(quote(meta.name))
        append(",\"key\":").append(quote(meta.key))
        append(",\"status\":").append(quote(status))
        append(",\"cls\":").append(quote(cls))
        append(",\"line\":").append(line)
        append(",\"pc\":").append(pc)
        append(",\"msg\":").append(quote(msg))
        append(",\"steps\":").append(steps)
        append(",\"elapsedMs\":").append(elapsedMs)
        append("}")
    }

    /** RUNNING 占位行：全 schema + 中性值（cls="-"，数值字段 -1，msg 空）。 */
    fun runningLine(meta: FuncMeta): String =
        row(meta, FunctionSweeper.SweepStatus.RUNNING, "-", -1, -1, "", -1, -1)

    /** meta 对账行（每份台账第一行）：shard/n/commit。 */
    fun metaLine(shard: Int, nshard: Int, commit: String): String =
        "{\"meta\":\"device-sweep\",\"shard\":$shard,\"n\":$nshard,\"commit\":${quote(commit)}}"

    /** JSON 字符串字面量（含两侧引号）；控制字符转 unicode 逃逸，与常见解析器兼容。 */
    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
