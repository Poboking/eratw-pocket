package com.eratw.port.render.canvas

import java.util.Locale

/**
 * 一帧的**版面事实**（真机现场取证用，不参与绘制决策）。
 *
 * 逐字段的用途与「为什么是这几个量」见 [FrameTrace] 的类文档。
 *
 * @property frameNo 帧序号（本 View 生命周期内单调递增；丢帧不影响它，它就是 `onDraw` 次数）
 * @property revision 本帧绘制用的控制台快照代数（`ConsoleFeed` 那一份的 revision）
 * @property rows 本帧绘制命令覆盖的行数（= 发布行数）
 * @property visibleRowBase 可见窗口的基准环号（`ConsoleScreen.visibleRowBase`）
 * @property hitRowBase 命中层的基准行号（`ConsoleScreen.hitRowBase`）
 * @property offsetFromEnd 离屏尾的上滚行数（0 = 贴底）
 * @property lineHeight 本帧执行行高（像素）
 * @property viewHeight 本帧 View 高度（像素）
 * @property rowsVisible 本帧视口行数（`visibleRows().size`）
 * @property rebaked 本帧是否重建过行位图（`ImageFrameSource.cacheRebuilds` 变化）
 * @property awaitingAnyKey 本帧是否处于「等待任意键」态（宿主注入；未注入恒 false）
 * @property heightLines 视口高度行数（`viewport.heightLines`）—— `heightPx / lineHeight` 的落值
 * @property fontPx 本帧执行字号（像素）
 * @property rowCount 行表的行数（`RowLayout.plan` 的 `windowRows`）
 * @property planHeight 行表总高（各行行高之和；底对齐后内容顶 = 内容底 − 它）
 * @property artAdvance 纯图行的行高（无图行时恒等于 [lineHeight]）
 * @property artRows 参与压缩的纯图行数（0 = 本页没有任何图行）
 */
data class FrameSample(
    val frameNo: Long,
    val revision: Long,
    val rows: Int,
    val visibleRowBase: Int,
    val hitRowBase: Int,
    val offsetFromEnd: Int,
    val lineHeight: Float,
    val viewHeight: Int,
    val rowsVisible: Int,
    val rebaked: Boolean,
    val awaitingAnyKey: Boolean,
    val heightLines: Int,
    val fontPx: Float,
    val rowCount: Int,
    val planHeight: Float,
    val artAdvance: Float,
    val artRows: Int,
)

/**
 * 逐帧环形缓冲（诊断包，2026-11-23「地图疯狂跳动」专项）—— **默认关，关时零开销**。
 *
 * ## 为什么要有它
 * JVM 侧已经把内容面全划掉了：行表、chips、状态位、滚动锚、命中层、帧闸都量过且稳。
 * 剩下的可能性只在**视图层**（Android 的尺寸/测量/贴图），而那一层在 JVM harness 里
 * 根本不存在。真机现场唯一能做的，是让 View 自己把「每帧的版面事实」记下来，
 * 事后读出来对拍 —— 这就是本类。
 *
 * ## 判据（真机现场唯一的区分手段，改动前先读这一段）
 * 拿 [dump] 输出的相邻帧比对：
 * - **`lineHeight` / `heightLines` / `visibleRowBase` / `planHeight` 在相邻帧跳动**
 *   ⇒ 视图层在动（尺寸抖动、度量重解析、行表重排）。这时继续查 `onSizeChanged`、
 *   `applyMetrics`、`resizeFeedScreen` 的触发源；
 * - **这些量全部恒定、而玩家眼里屏仍在动**
 *   ⇒ 问题在 canvas 贴图/硬件加速那一层（保留面 `blitSurface`、行位图重烤、合成器），
 *   不在版面逻辑。`rebaked` 逐帧为真、或 `rowSignature` 变动频繁，就往这一支走；
 * - `visibleRowBase` 稳而 `offsetFromEnd` 变 ⇒ 玩家/驱动在滚，不是「跳」。
 *
 * ## 零开销口径
 * 关闭时只在 `onDraw` 里做一次 `@Volatile` 布尔读 + 分支，不建对象、不碰行表；
 * 打开才做一次 `RowLayout.plan`（它本来就在绘制里算，重复一次只是诊断成本）。
 * 容量固定，满了覆盖最旧一帧 —— 诊断包不参与生产决策，也不许无限增长。
 *
 * @param capacity 保留的帧数（默认由 [DEFAULT_CAPACITY] 给）
 */
class FrameTrace(
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    private val lock = Any()
    private val ring = arrayOfNulls<FrameSample>(capacity.coerceAtLeast(1))
    private var next = 0
    private var count = 0

    /** 当前保留的帧数。 */
    val size: Int get() = synchronized(lock) { count }

    /** 记一帧（满了覆盖最旧）。 */
    fun record(sample: FrameSample) {
        synchronized(lock) {
            ring[next] = sample
            next = (next + 1) % ring.size
            if (count < ring.size) count++
        }
    }

    /** 清空（开关切换时调用，避免把上一段现场与这一段混在一张表里）。 */
    fun clear() {
        synchronized(lock) {
            ring.fill(null)
            next = 0
            count = 0
        }
    }

    /** 按时间序取出最多 [limit] 帧（最旧在前）。 */
    fun samples(limit: Int = capacity): List<FrameSample> = synchronized(lock) {
        val n = count.coerceAtMost(limit.coerceAtLeast(0))
        val start = (next - n + ring.size) % ring.size
        (0 until n).mapNotNull { ring[(start + it) % ring.size] }
    }

    /**
     * 完整形态（DevTools 的 `frames [n]`）：表头（判据）+ 逐帧表 + 判定结论。
     *
     * 报告侧请用 [summaryLines] —— 整张表塞不进错误报告的共享尾窗（理由见该函数 KDoc）。
     *
     * @param limit 最多列出多少帧（尾部对齐，取最近的那几帧）
     */
    fun dump(limit: Int = capacity): String {
        val list = samples(limit)
        if (list.isEmpty()) return EMPTY_TEXT
        val sb = StringBuilder()
        sb.append("frameTrace frames=").append(list.size)
            .append(" capacity=").append(capacity)
            .append('\n').append(VERDICT_HINT).append('\n')
        sb.append(headerLine()).append('\n')
        for (s in list) sb.append(rowLine(s)).append('\n')
        sb.append(verdict(list))
        return sb.toString()
    }

    /**
     * **报告用**的紧凑形态（`ErrorReportCollector` 走这条）：全环摘要 + 尾部若干帧 + 判定行。
     *
     * 为什么报告不直接给整张 240 帧的表：错误报告的「补充现场」是**共享尾窗**
     * （`ErrorReport.EXTRA_MAX_LINES` = 80 条物理行，核心层 `takeLast`），一份 240 行的
     * 表格会把同窗口里的内存读数、心跳、冻结现场整段挤出去 —— 那个坑在本仓已经踩过一次
     * （`metricSectionLines` 的 KDoc 记着 80→120 的挪窗账）。所以报告只带
     * 「全环统计（每个版面量的取值集合）+ 最后 [maxRows] 帧明细 + 判定行」，
     * 十几到二十几行就把现场讲完；完整表留给 DevTools 的 `frames` 命令。
     *
     * @param maxRows 明细最多列几帧（尾部对齐；<= 0 = 只给摘要与判定）
     */
    fun summaryLines(maxRows: Int = REPORT_FRAME_ROWS): List<String> {
        val all = samples(capacity)
        if (all.isEmpty()) return listOf(EMPTY_TEXT)
        val tail = if (maxRows > 0) all.takeLast(maxRows) else emptyList()
        val sb = StringBuilder()
        sb.append("frames=").append(all.size).append('/').append(capacity)
            .append(" span=").append(all.last().frameNo - all.first().frameNo + 1)
            .append(" lineH=").append(distinctText(all) { it.lineHeight.toString() })
            .append(" hLines=").append(distinctText(all) { it.heightLines.toString() })
            .append(" vBase=").append(distinctText(all) { it.visibleRowBase.toString() })
            .append(" planH=").append(distinctText(all) { it.planHeight.toString() })
            .append(" font=").append(distinctText(all) { it.fontPx.toString() })
            .append(" rebake=").append(all.count { it.rebaked }).append('/').append(all.size)
            .append(" anyKey=").append(all.count { it.awaitingAnyKey }).append('/').append(all.size)
        val out = ArrayList<String>(tail.size + SUMMARY_FIXED_LINES)
        out += sb.toString()
        out += headerLine()
        tail.forEach { out += rowLine(it) }
        out += verdict(tail.ifEmpty { all }).trimEnd()
        return out
    }

    /** 某个版面量在整环里的取值集合（有序、去重、最多列 [SUMMARY_MAX_VALUES] 个）。 */
    private fun distinctText(list: List<FrameSample>, of: (FrameSample) -> String): String {
        val uniq = list.map(of).distinct()
        if (uniq.size <= SUMMARY_MAX_VALUES) return uniq.joinToString("|")
        return uniq.take(SUMMARY_MAX_VALUES).joinToString("|") + "|…"
    }

    /** 表头一行（[dump] 与 [summaryLines] 共用同一份列口径）。 */
    private fun headerLine(): String =
        "frameNo rev rows vBase hBase offEnd lineH viewH rowsVis hLines font " +
            "rowCount planH artAdv artRows rebake anyKey"

    /** 一行样本（[dump] 与 [summaryLines] 共用同一份列口径）。 */
    private fun rowLine(s: FrameSample): String = String.format(
        Locale.ROOT,
        "%d %d %d %d %d %d %.2f %d %d %d %.1f %d %.1f %.2f %d %s %s",
        s.frameNo, s.revision, s.rows, s.visibleRowBase, s.hitRowBase,
        s.offsetFromEnd, s.lineHeight, s.viewHeight, s.rowsVisible, s.heightLines,
        s.fontPx, s.rowCount, s.planHeight, s.artAdvance, s.artRows,
        if (s.rebaked) "Y" else ".", if (s.awaitingAnyKey) "Y" else ".",
    )

    /**
     * 判定：把「相邻帧是否有版面量跳动」算成一句话，省掉读表的人肉比对。
     *
     * 只看**版面量**（行高/视口行数/可见基准/行表总高）；`revision` 之类内容量本来就会变，
     * 不参与判定。`offsetFromEnd` 单独标注为「滚动」，不混进「跳」。
     */
    fun verdict(list: List<FrameSample>): String {
        if (list.size < 2) return "verdict: 帧数不足（<2），无法判定\n"
        var layoutJitter = false
        var offEndJitter = false
        for (i in 1 until list.size) {
            val a = list[i - 1]
            val b = list[i]
            // 拆两条：四个版面量各成一对，避免单条条件超复杂度阈（判据口径一个字没改）。
            if (a.lineHeight != b.lineHeight || a.heightLines != b.heightLines) layoutJitter = true
            if (a.visibleRowBase != b.visibleRowBase || a.planHeight != b.planHeight) layoutJitter = true
            if (a.offsetFromEnd != b.offsetFromEnd) offEndJitter = true
        }
        val rebakeFrames = list.count { it.rebaked }
        val head = if (layoutJitter) {
            "verdict: 版面量在跳（lineHeight/heightLines/visibleRowBase/planHeight 有变）" +
                " ⇒ 视图层在动"
        } else {
            "verdict: 版面量全稳（lineHeight/heightLines/visibleRowBase/planHeight 相邻帧相同）" +
                " ⇒ 若玩家仍看到动，问题在 canvas 贴图/硬件加速层"
        }
        return "$head；rebakeFrames=$rebakeFrames/${list.size}；" +
            "offsetFromEnd跳动=${if (offEndJitter) "Y（滚动，不是跳）" else "."}\n"
    }

    companion object {

        /** 缺省保留 240 帧 —— 60fps 下约 4 秒现场，够覆盖一次重印突发。 */
        const val DEFAULT_CAPACITY: Int = 240

        /**
         * 报告形态（[summaryLines]）最多列几帧明细。
         *
         * 20 = 60fps 下约 0.33 s 的**原始逐帧**明细，配上全环统计（整环 240 帧的取值集合）
         * 足够判断「是行高在抖还是贴图层在动」；再长就要挤掉错误报告尾窗里的其它现场。
         */
        const val REPORT_FRAME_ROWS: Int = 20

        /** 摘要里每个版面量最多列几个取值（再多就把行撑宽、挤掉别的现场）。 */
        const val SUMMARY_MAX_VALUES: Int = 6

        /** [summaryLines] 里除明细行以外的固定行数（摘要 1 + 表头 1 + 判定 1）。 */
        const val SUMMARY_FIXED_LINES: Int = 3

        /** 环空时的统一说法（`dump` 与报告侧都从这里取，免得两处文案漂移）。 */
        const val EMPTY_TEXT: String = "frameTrace: 空（未开启或还没画过帧）"

        /** 判据摘要（写进报告头，见 [dump]）。 */
        const val VERDICT_HINT: String =
            "# 判据：版面量（lineH/hLines/vBase/planH）相邻帧有变 = 视图层在动；" +
                "全稳而屏仍动 = canvas 贴图/硬件加速层"
    }
}
