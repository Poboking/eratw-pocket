package com.eratw.port.android

import com.eratw.port.core.code.StmtEnv
import com.eratw.port.core.code.StmtOutcome
import com.eratw.port.core.proc.StatementProbe
import com.eratw.port.core.stmt.FunctionCode
import com.eratw.port.core.stmt.Statement

/**
 * DevTools `trace` / `break` 命令的探针实现（DBG3；**仅 debug 包的调试通道装配**，
 * release 无引用）。
 *
 * 一个实例同时承载**语句轨迹环**与**软断点命中快照**——`ProcessRuntime.statementProbe`
 * 是单槽，两个功能各装一个实例会互相顶掉，故合体。装配/拆卸由 DevToolsHost 的
 * `trace`/`break` 命令管理（探针缺席 = 零开销，见 StatementProbe KDoc 的守卫结构）。
 *
 * ## 软断点的口径（为什么不「停住 VM」）
 *
 * [StatementProbe] 的契约是回调**不得阻塞、不得抛**（异常被吞口折进 suppressed），
 * 在回调里 park VM 线程违反契约也不安全。「break」因此做成**命中点快照 + 不自动应答**：
 * 命中时把（函数、行、pc、语句、结局）当场拷进缓冲，agent 随后用 `where`/`var`/`callstack`
 * 读现场，用 `submit` 控制推进——调试后门模式下「停住」= agent 不投下一条输入，
 * 游戏自然停在下一个输入等待点（等待点的读数才是精确读数，与各命令的竞态口径一致）。
 *
 * ## 回调纪律（逐条落实）
 *
 * - 不抛：整个 [onStatement] 无异常路径（字符串拼接 + 集合操作）；语句实例由注入的
 *   [statementAt] 现取，取不到就退回 `env.line` + FunctionCode 名（T2-64 波次 C 起探针
 *   只拿到 op/index，要树得自己按 index 取）；
 * - 不阻塞：只有一把进程内监视器锁，临界区是纯内存操作；
 * - 不持有 env：`functionKey`/`line`/`pc` 当场读值进局部变量，`stmt` 只取类名与
 *   [Statement.line]（final 字段），不存 env 引用。
 */
class DevProbe(
    /** 语句池下标 → 语句实例（`ProcessRuntime.program.statement`）；null = 不取树，摘要退 op 名。 */
    private val statementAt: ((Int) -> Statement)? = null,
) : StatementProbe {

    /** 一个软断点：大写函数键 + 行号（`line <= 0` = 该函数任意行）。 */
    class BreakPoint(val functionKey: String, val line: Int) {
        val label: String get() = if (line > 0) "$functionKey:$line" else functionKey
    }

    private val lock = Any()

    // ---- trace 面 ----

    /** trace 是否在记；关 = onStatement 里 trace 分支整块跳过。 */
    @Volatile
    private var traceOn = false

    /** 只记这个大写函数键；null = 全函数。 */
    @Volatile
    private var traceKey: String? = null

    private var traceRing = ArrayDeque<String>()
    private var traceCapacity = DEFAULT_CAPACITY
    private var traceDropped = 0L

    // ---- break 面 ----

    /** label（大写 FN 或 FN:line）→ 断点。 */
    private val breaks = LinkedHashMap<String, BreakPoint>()

    /** 命中快照（新→旧；同一 fn:line:pc 只记第一次）。 */
    private val breakHits = ArrayDeque<String>()
    private val seenHits = HashSet<String>()

    /** 探针装上以来的回调总数（诊断用：证明探针活着）。 */
    @Volatile
    var statementCount: Long = 0L
        private set

    /** 开/关 trace；[functionKey] null = 全函数；容量缺省 2000、上限 20000。 */
    fun trace(on: Boolean, functionKey: String?, capacity: Int) {
        synchronized(lock) {
            traceOn = on
            traceKey = functionKey?.uppercase()
            if (on) {
                traceCapacity = capacity.coerceIn(100, 20_000)
                traceRing = ArrayDeque()
                traceDropped = 0L
            }
        }
    }

    val traceActive: Boolean get() = traceOn

    /** 轨迹快照（旧→新）+ 丢弃计数；不消费（可反复读）。 */
    fun traceSnapshot(): List<String> = synchronized(lock) {
        ArrayList(traceRing) + if (traceDropped > 0) listOf("（更早的 ${traceDropped} 条已被环形覆盖）") else emptyList()
    }

    /** 解析并加断点；spec = `FN` 或 `FN:行号`（大小写不敏感，键归一大写）。 */
    fun addBreak(spec: String): String {
        val sp = spec.lastIndexOf(':')
        val (key, line) = if (sp > 0) {
            spec.take(sp) to (spec.substring(sp + 1).toIntOrNull() ?: -1)
        } else {
            spec to 0
        }
        require(key.isNotBlank()) { "断点要给函数名" }
        val bp = BreakPoint(key.uppercase(), line)
        synchronized(lock) { breaks[bp.label] = bp }
        return bp.label
    }

    fun clearBreaks() {
        synchronized(lock) { breaks.clear() }
    }

    val breakCount: Int get() = synchronized(lock) { breaks.size }

    /** 现有断点的 label 列表（`break` 命令无参时展示用）。 */
    fun breakLabels(): List<String> = synchronized(lock) { breaks.keys.toList() }

    /** 命中快照（新→旧），读后清。 */
    fun drainHits(): List<String> = synchronized(lock) {
        val out = ArrayList(breakHits)
        breakHits.clear()
        out
    }

    override fun onStatement(op: Int, index: Int, env: StmtEnv, outcome: StmtOutcome?, error: Throwable?, elapsedNs: Long) {
        statementCount++
        // 当场拷贝 env 四字段（契约：不持有 env）。
        val key = env.functionKey
        val stmt = if (index >= 0) statementAt?.invoke(index) else null
        val line = stmt?.line ?: env.line
        val stmtName = stmt?.javaClass?.simpleName
            ?: FunctionCode.entries.getOrNull(op)?.name
            ?: "op$op"
        val pc = env.pc
        val doTrace = traceOn && (traceKey == null || traceKey == key)
        if (doTrace) {
            val entry = "p$pc L$line $key $stmtName" +
                (if (error != null) " !!${error.javaClass.simpleName}" else "") +
                " ${elapsedNs / 1000}us"
            synchronized(lock) {
                if (traceRing.size >= traceCapacity) {
                    traceRing.removeFirst()
                    traceDropped++
                }
                traceRing.addLast(entry)
            }
        }
        if (breaks.isNotEmpty()) {
            var hit: BreakPoint? = null
            synchronized(lock) {
                for (bp in breaks.values) {
                    if (bp.functionKey == key && (bp.line <= 0 || bp.line == line)) {
                        hit = bp
                        break
                    }
                }
            }
            hit?.let { bp ->
                val dedup = "${bp.label}:$pc"
                synchronized(lock) {
                    if (seenHits.add(dedup)) {
                        if (breakHits.size >= MAX_HITS) breakHits.removeFirst()
                        breakHits.addLast(
                            "HIT ${bp.label} p$pc L$line $stmtName" +
                                (if (error != null) " !!${error.javaClass.simpleName}" else ""),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_CAPACITY = 2000
        const val MAX_HITS = 500
    }
}
