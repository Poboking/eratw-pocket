package com.eratw.port.android

import java.util.LinkedHashMap

/**
 * 高频诊断事件的**进程级聚合器**（2026-11-17 上报覆盖补齐波）。
 *
 * ## 为什么需要它（与既有 [ErrorReportThrottle] 的分工）
 *
 * [ErrorReportThrottle] 只管制一类报告（「脚本运行报错」）且绑死在
 * `ErrorReportCollector.collect` 内，只能由有宿主/有会话的失败路径驱动。本类补的是它够不到的
 * 三处黑洞：**UI 主线程异常**（`MainActivity.logUiFault`，主线程、无失败屏）、**启动流程异常**
 * （`MainActivity.onStartupFault`，宿主半装配）、**装载警告**（`GameHost` 装载期、会话刚建、
 * 高频成串）—— 这些路径共同点：可能每帧/每条告警触发一次，逐条落报告会刷爆端点与玩家流量。
 *
 * 本类只回答一个问题：「这一条事件该不该落一份完整报告」。判据 = **每型每局首份上限**：
 * 同一归一签名在一局里最多放行 [PER_KIND_FIRST_MAX] 份代表报告，其余只累加计数。计数不丢 ——
 * [summaryLines] 把本局各型×次数渲染成行，由调用方拼进代表报告与会话结束时的「诊断汇总」报告。
 *
 * ## 故障方向
 *
 * 一切 best-effort：任何一步失败都朝「多报」退（[Decision.firstOfKind] 兜 true），绝不因为
 * 聚合器自身故障而把一条真实现场吞掉。本类不碰文件、不碰网络、不碰 Android API（纯 JVM），
 * `:app` 单测直接锁（`DiagnosticEventLogTest`）。
 *
 * ## 状态与线程
 *
 * 进程级单例对象，状态挂本实例（app 层无 `store.aux`，遵「不新增顶层 var」精神：只有一个
 * `object` 声明，字段全在其内）。UI 线程 / VM 线程 / 装载线程都会调 [record]，全部 `synchronized`。
 * 会话切换由 [reset] 清账（或与既有节流同处接线，见契约 §2.3）。
 *
 * KDoc 注意：正文不出现块注释定界符字符对（AGENTS 铁律 5）。
 */
internal object DiagnosticEventLog {

    /** 三类高频诊断事件 + 会话汇总（对应四种玩家可感但原本不上报的问题）。 */
    enum class DiagnosticKind {
        UI_FAULT,
        STARTUP_FAULT,
        LOAD_WARNING,
        /** 存档失败（`SaveDiagnostics.onFailure` 安装侧喂入；游戏没崩，但现场必须上报）。 */
        SAVE_FAILURE,
        /** 会话结束的聚合汇总（shutdownForExit）与次启并入的上局孤儿账，共用同一类型码。 */
        DIAGNOSTIC_SUMMARY,
        ;

        /** 进报告里的中文类型名（与 `GameHost.writeErrorReport` 的 kind 字面量同风格）。 */
        val reportKind: String
            get() = when (this) {
                UI_FAULT -> "UI线程异常"
                STARTUP_FAULT -> "启动流程异常"
                LOAD_WARNING -> "装载警告"
                SAVE_FAILURE -> "存档失败"
                DIAGNOSTIC_SUMMARY -> "诊断汇总"
            }
    }

    /** 一条诊断事件；[where] = 发生点（UI 阶段 / 装载来源），[message] = 可读现场。 */
    data class DiagnosticEvent(
        val kind: DiagnosticKind,
        val where: String,
        val message: String,
        val occurredAtMs: Long = System.currentTimeMillis(),
    )

    /**
     * 一条事件的处置结论。
     *
     * @param firstOfKind true = 该签名本局还没放行过代表，调用方应落一份完整报告并上报；
     *   false = 只累加了计数，调用方无需再落盘（计数由 [summaryLines] 随代表/汇总带出）。
     * @param occurrences 这个归一签名至今出现过几次
     * @param mayReport **落盘最终判据** = firstOfKind 且本型放行数未到 [PER_KIND_REPORT_MAX]
     *   （签名闸只管重复，类型闸管清单型来源的总量，见其 KDoc）
     */
    data class Decision(val firstOfKind: Boolean, val occurrences: Int, val mayReport: Boolean)

    /** 每个归一签名在一局里最多落几份代表报告（超出的只计数）。 */
    internal const val PER_KIND_FIRST_MAX = 2

    /**
     * 每个**类型**在一局里最多落几份代表报告（DBG-TOOLING 波新闸）。
     *
     * 为什么只有签名上限不够：装载警告这类**清单型**来源（侧车 loadWarnings 可达上百条
     * 互不相同的告警）每条签名都不同，per-signature 放行会让它一局刷上百份报告——防刷屏
     * 的本意就破了。类型闸封顶后，清单型的多出来全部进 [summaryLines] 的明细行，信息不丢。
     */
    internal const val PER_KIND_REPORT_MAX = 8

    private val lock = Any()

    /** 归一签名 -> 一条账（次数 + 未归一的现场原文，给人读）。 */
    private class Entry(var occurrences: Int, val display: String, val kind: DiagnosticKind)

    private val entries = LinkedHashMap<String, Entry>()

    /** 本局已放行落盘的代表报告数（按类型计；[markReported] 由落盘成功的调用方回填）。 */
    private val reportedByKind = mutableMapOf<DiagnosticKind, Int>()

    /** 本局累计记录的事件条数（含被聚合的）。 */
    private var total = 0

    /**
     * 记一条事件，返回是否该落代表报告。绝不抛。
     *
     * 归一 = 类型 + where + 消息（压空白、抹数字）。抹数字的理由同 `ReportSignature`：
     * `第 0 行` / `第 -3 行` 是数据不是身份，同一处同一类告警换个读数就再落一份正是刷屏源。
     * where 参与身份（UI 的「点提交」与「点返回」是两处，不合并）。
     *
     * [Decision.firstOfKind] 是签名级判定（该签名要不要争代表名额）；**落盘最终判据**
     * 是 [Decision.mayReport] = firstOfKind 且本型放行数未到 [PER_KIND_REPORT_MAX]——
     * 清单型来源（上百条不同签名）靠类型闸封顶。
     */
    fun record(event: DiagnosticEvent): Decision = synchronized(lock) {
        runCatching {
            val key = event.kind.name + "|" + normalize(event.where) + "|" + normalize(event.message)
            val entry = entries[key]
            total++
            val firstOfKind: Boolean
            val occurrences: Int
            if (entry == null) {
                entries[key] = Entry(1, displayOf(event), event.kind)
                firstOfKind = true
                occurrences = 1
            } else {
                entry.occurrences++
                // 首份上限内也放行：一局里首 2 次各落一份代表（形态可能不同，给读的人多一眼）。
                firstOfKind = entry.occurrences <= PER_KIND_FIRST_MAX
                occurrences = entry.occurrences
            }
            val mayReport = firstOfKind && (reportedByKind[event.kind] ?: 0) < PER_KIND_REPORT_MAX
            Decision(firstOfKind, occurrences, mayReport)
        }.getOrElse { Decision(true, 1, true) }
    }

    /**
     * 代表报告落盘成功后由调用方回填（[DiagnosticKind] 计满 [PER_KIND_REPORT_MAX] 后
     * 同型后续事件不再放行落盘，只进 [summaryLines]）。失败不回填：宁可多报不吞现场。
     */
    fun markReported(kind: DiagnosticKind) = synchronized(lock) {
        runCatching { reportedByKind[kind] = (reportedByKind[kind] ?: 0) + 1 }
    }

    /**
     * 本局账的可读行（供拼进报告 `extraSectionLines` 与 checkpoint 落盘）。空局返回空表。
     *
     * 第 1 行 = 总账（各型×次数，形状与上波一致）；之后每型至多一行 TOP 明细
     * （该型内按次数降序取前 [TOP_ENTRIES_PER_KIND] 条签名原文，次数同序保进入序）。
     * 行数上界固定（1 + 类型数），不随事件量涨；清单型来源的未上榜签名全在计数里。
     */
    fun summaryLines(): List<String> = synchronized(lock) {
        runCatching {
            if (total == 0) return@synchronized emptyList()
            val byKind = LinkedHashMap<DiagnosticKind, Int>()
            for (e in entries.values) byKind[e.kind] = (byKind[e.kind] ?: 0) + e.occurrences
            val lines = mutableListOf(
                ("诊断聚合（本局 共 $total 条）：" +
                    byKind.entries.joinToString(" / ") { "${it.key.reportKind} ×${it.value}" })
                    .take(SUMMARY_MAX_CHARS),
            )
            for ((kind, kindTotal) in byKind) {
                val top = entries.values
                    .filter { it.kind == kind }
                    .sortedWith(compareByDescending<Entry> { it.occurrences })
                    .take(TOP_ENTRIES_PER_KIND)
                    .joinToString(" ／ ") { "${it.display} ×${it.occurrences}" }
                    .take(SUMMARY_MAX_CHARS)
                lines.add("${kind.reportKind} TOP（共 $kindTotal）：$top")
            }
            lines
        }.getOrDefault(emptyList())
    }

    /** 最近一条事件的现场原文（供代表报告的「补充现场」多带一句未归一文本）。 */
    fun lastDisplay(): String? = synchronized(lock) {
        runCatching { entries.values.lastOrNull()?.display }.getOrNull()
    }

    /** 会话切换 / 宿主释放时清账；清完 summaryLines 返回空表。 */
    fun reset() {
        synchronized(lock) {
            entries.clear()
            reportedByKind.clear()
            total = 0
        }
    }

    /** 消息归一：压空白 -> 抹带符号数字 -> 截断（顺序不可倒，见 ReportSignature 同口径）。 */
    private fun normalize(text: String): String {
        val flat = text.trim().replace(WHITESPACE, " ")
        val masked = DIGITS.replace(flat, "#")
        return if (masked.length <= NORMALIZE_MAX_CHARS) masked else masked.take(NORMALIZE_MAX_CHARS) + "…"
    }

    /** 现场原文（给人读）：`where｜message`，截断到 [DISPLAY_MAX_CHARS]。 */
    private fun displayOf(event: DiagnosticEvent): String {
        val flat = event.message.trim().replace(WHITESPACE, " ")
        val short = if (flat.length > DISPLAY_MAX_CHARS) flat.take(DISPLAY_MAX_CHARS) + "…" else flat
        return (event.where + "｜" + short).take(DISPLAY_MAX_CHARS)
    }

    private val WHITESPACE = Regex("\\s+")
    private val DIGITS = Regex("[+-]?[0-9]+")

    private const val NORMALIZE_MAX_CHARS = 120
    private const val DISPLAY_MAX_CHARS = 96
    private const val SUMMARY_MAX_CHARS = 200

    /** 每型 TOP 明细行里最多列几条签名（[summaryLines] 的行内上界，防清单型把行撑爆）。 */
    private const val TOP_ENTRIES_PER_KIND = 5
}
