package com.eratw.port.android.portrait

/**
 * M1 竖屏 HUD 的**行解析纯函数层**（设计稿 `docs/archive/竖屏布局设计稿.md` 附录 A 是唯一规格）。
 *
 * 数据源只有屏行快照（`LineBufferConsoleSink.lines()` 的行文本），解析出 HUD 各控件要显示的
 * 值；**任何一条不成立 → 该字段 null → 壳层隐藏该控件**（附录 A 规则 3）——
 * 绝不显示编造默认值、绝不 0 兜底、不许 try-catch 兜底（§0-5、§10-1）。
 *
 * ## 实现纪律
 *   * **禁正则**（§0-9：轮询路径 Android ICU 正则坑在案）——全部手写 `indexOf`/逐字符扫描；
 *   * **禁 Android API**——纯 JVM 可跑（`HudLineParserTest` 直接钉附录 A 四屏样例）；
 *   * 数字判定只认**半角 0..9**（全角 `３８４５` 必须解析失败，附录 A 负样例；
 *     注意 Kotlin 的 `Char.isDigit()` 对全角也返回 true，所以全仓这里一律 `in '0'..'9'`）;
 *   * 锚**行首标签**、不锚列位（§3.5：逻辑列宽随档位变，同一行会折）——「行首」指该行的
 *     第一个 token 决定行型；行型成立后，行内**token 开头**的其余标签照扫（A1 的数值行
 *     一行里就有体力/气力/法力三组）。标签集合固定（附录 A 规则 1），不新增、不白名单。
 *   * 标签命中必须满足：位于 token 开头（行首或前一字符是空白）且标签后跟空白/冒号/行尾
 *     —— 这挡住 `精神 100` 冒充 `精`、叙述文本里出现「体力」字样的假阳性。
 *   * 一个字段的取值窗口 = 本标签结束到行内下一个标签开始（或行尾），**不跨字段偷值**：
 *     `信赖度:` 后没数字时不会把下一字段的 87 抢过来（附录 A 负样例「标签后无数字」→ null）。
 */

// ======================================================================
// 结果模型（文件顶层：P2 壳层直接消费这些类型）
// ======================================================================

/** 一个数值：[current] 当前值；[max] 上限（分母式 `a/ b` 才有，否则 null）。 */
data class BarValue(val current: Int, val max: Int?)

/** 数值行里扫出的一组：标签（体力/气力/法力/TSP/精力/酒/精/清醒裾裾…）→ 值。 */
data class NamedBar(val label: String, val value: BarValue)

/**
 * 日期行（附录 A A1 形：`第47日(五)  所持金: ¥5,177,463,124 （筹码：664CM）`；
 * A2~A4 形：`夏之月 17日 (六)9时00分 …`）。
 *
 * @property dateText 日期部分原文（A1 形剥掉所持金段；A2~A4 形 = 整行原文）
 * @property money 所持金（千分位逗号剥掉；剥不出 → null → 壳层隐藏该徽章）
 * @property chips 筹码（`664CM` 的数字；无筹码段 → null）
 */
data class DateLineInfo(val dateText: String, val money: Long?, val chips: Long?)

/** `[自宅位置]：命莲寺 - 别馆   [起床时间]：9时00分  [日历]` 一行的地点/时间。 */
data class PlaceTimeInfo(val place: String?, val time: String?)

/**
 * 经验行数值（A3：`好感度: Ex 113791  信赖度: S 3330  欲求不满度: 87%  孩子个数: 0  怀孕: ×`）。
 * `Ex 113791` / `S 3330` 取字母后的整数；`87%` 去 %；`怀孕`/`受孕` 的值是 `○`/`×` 符号。
 * 任一字段在本行剥不出 → 该字段 null（其余字段照常）。
 */
data class ExpValues(
    val favor: Int?,
    val trust: Int?,
    val frustration: Int?,
    val children: Int?,
    val pregnancy: String?,
    val conception: String?,
)

/** 一条经验行：[name] = `[名字]` 前缀（A4 角色块经验行）；null = 无名字前缀（A3 单人经验行）。 */
data class ExpLineInfo(val name: String?, val values: ExpValues)

/** 工作行（A3：`工作情报  工作地点: ——  战斗能力: D 1`）。[battleRank] 是 D/A/Ex 这类段位字母。 */
data class WorkInfo(val place: String?, val battleRank: String?, val battlePower: Int?)

/** 执行中：[subject] = `(昂)` 这类主语；[text] = 引号/方括号里的指令原文。 */
data class ExecutingInfo(val subject: String?, val text: String)

/** 角色数值行（A4：`白莲  [体力] 2666  气 2370  法 754  酒 0`）。 */
data class StatRowInfo(val name: String, val stats: Map<String, BarValue>, val dangerDay: Boolean)

/**
 * 主角信息（附录 A 切分规则 3：主角块不计入角色条）。
 *
 * [name] = 主角名：数值行名字优先（A4 的 `昂  [体力] …`，其名字不在在场名单里），
 * 否则取 `(主角)`/`(=主人公)` 前缀行的标签（A3 的 `清`）。
 * [markerLabel] = 前缀行给出的标签原文（A4 是状态名 `清醒裾裾`，不一定是名字）。
 */
data class ProtagonistInfo(
    val name: String?,
    val stats: Map<String, BarValue>,
    val markerLabel: String?,
)

/**
 * 一个合格角色块（附录 A 切分规则 2：`[名字]` 行 + `[名字] 好感度…` 行 + `名字 [体力] …`
 * 数值行，三行名字一致；缺任一行该块不计入）。
 *
 * [dangerDay] = 该块数值行的下一行状态文本里出现 `危险日前`（切分规则 5；
 * 受孕 ○/× 在 [exps] 的 [ExpValues.conception] 里）。
 */
data class CharacterBlock(
    val name: String,
    val exps: ExpValues,
    val stats: Map<String, BarValue>,
    val dangerDay: Boolean,
)

/**
 * 一批屏行的整体解析结果。所有字段「解析不出 → null / 空」，壳层按失配隐藏。
 *
 * 同名字段的重复出现（滚动缓冲里上一屏的行）按**最后一次**为准（屏缓冲底部是当前屏）。
 */
data class HudSnapshot(
    val dateText: String? = null,
    val money: Long? = null,
    val chips: Long? = null,
    val place: String? = null,
    val time: String? = null,
    val bars: Map<String, BarValue> = emptyMap(),
    /** buff 行整行 chips（A2：`[调和魔药] [时间停止可] …` → 四个名字）。 */
    val buffChips: List<String> = emptyList(),
    val fortune: Int? = null,
    val executing: ExecutingInfo? = null,
    val lastCommand: String? = null,
    /** 无名字前缀的经验行（A3 = 当前交互对象的数值；G2 的 HUD 数值来源）。 */
    val exps: ExpValues? = null,
    val work: WorkInfo? = null,
    val protagonist: ProtagonistInfo? = null,
    /** 合格角色块（切分规则 4：块数 ≥ 2 壳层才落 G3，< 2 降级 G2 外观）。 */
    val blocks: List<CharacterBlock> = emptyList(),
    /** 组标签（`【主菜单】` → `主菜单`；整行命中才认）。 */
    val groupLabels: List<String> = emptyList(),
) {
    /** 合格角色块数（切分规则 4 的「角色数」）。 */
    val charCount: Int get() = blocks.size

    /** 当前交互对象 = 首个合格块的名字（游戏把当前对象的经验行列在最前，附录 A A4）。 */
    val currentTarget: String? get() = blocks.firstOrNull()?.name
}

// ======================================================================
// 解析器本体
// ======================================================================

object HudLineParser {

    // ------------------------------------------------------------------
    // 标签集合（附录 A 规则 1：固定，不白名单扩展）
    // ------------------------------------------------------------------

    private const val LABEL_FAVOR = "好感度"
    private const val LABEL_TRUST = "信赖度"
    private const val LABEL_FRUSTRATION = "欲求不满度"
    private const val LABEL_CHILDREN = "孩子个数"
    private const val LABEL_PREGNANCY = "怀孕"
    private const val LABEL_CONCEPTION = "受孕"
    private const val LABEL_EXECUTING = "执行中"
    private const val LABEL_FORTUNE = "今日运势"
    private const val LABEL_DANGER_DAY = "危险日前"

    /** 在场行的状态词最小集（切分规则 1）：方括号内容命中其一的 token 不是名字。 */
    val STATUS_WORDS: Set<String> = setOf("同行中", "战斗不能", "睡眠", "发情")

    private enum class ValueKind { BAR, INT, SYMBOL, TEXT }

    private data class FieldDef(val label: String, val kind: ValueKind)

    private val DEF_FAVOR = FieldDef(LABEL_FAVOR, ValueKind.INT)
    private val DEF_TRUST = FieldDef(LABEL_TRUST, ValueKind.INT)
    private val DEF_FRUSTRATION = FieldDef(LABEL_FRUSTRATION, ValueKind.INT)
    private val DEF_CHILDREN = FieldDef(LABEL_CHILDREN, ValueKind.INT)
    private val DEF_PREGNANCY = FieldDef(LABEL_PREGNANCY, ValueKind.SYMBOL)
    private val DEF_CONCEPTION = FieldDef(LABEL_CONCEPTION, ValueKind.SYMBOL)

    /** 数值行标签（行首判定也用它；长标签在前，保证 `精力` 先于 `精` 命中）。 */
    private val BAR_DEFS: List<FieldDef> = listOf(
        FieldDef("清醒裾裾", ValueKind.BAR),
        FieldDef("TSP", ValueKind.BAR),
        FieldDef("体力", ValueKind.BAR),
        FieldDef("气力", ValueKind.BAR),
        FieldDef("法力", ValueKind.BAR),
        FieldDef("精力", ValueKind.BAR),
        FieldDef("酒", ValueKind.BAR),
        FieldDef("精", ValueKind.BAR),
    ).sortedByDescending { it.label.length }

    private val EXP_DEFS: List<FieldDef> = listOf(
        DEF_FAVOR,
        DEF_TRUST,
        DEF_FRUSTRATION,
        DEF_CHILDREN,
        DEF_PREGNANCY,
        DEF_CONCEPTION,
    ).sortedByDescending { it.label.length }

    private val WORK_DEFS: List<FieldDef> = listOf(
        FieldDef("工作地点", ValueKind.TEXT),
        FieldDef("战斗能力", ValueKind.INT),
    ).sortedByDescending { it.label.length }

    private const val DEF_WORK_HEAD = "工作情报"
    private const val STAT_LABEL = "体力"

    // ------------------------------------------------------------------
    // 基础扫描件（全手写，禁正则）
    // ------------------------------------------------------------------

    private fun isWs(c: Char): Boolean = c == ' ' || c == '　' || c == '\t'

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    /**
     * 标签命中：必须位于 token 开头（[at] = 0 或前一字符是空白），且标签后跟空白/冒号/行尾。
     * 返回标签长度，未命中返回 0。
     */
    private fun labelLengthAt(line: String, at: Int, def: FieldDef): Int {
        if (!line.startsWith(def.label, at)) return 0
        val end = at + def.label.length
        if (end < line.length && !isWs(line[end]) && line[end] != ':' && line[end] != '：') return 0
        return def.label.length
    }

    /** [defs] 里在 [at] 命中的最长标签（调用方保证列表已按长度降序）。 */
    private fun matchDefAt(line: String, at: Int, defs: List<FieldDef>): FieldDef? {
        for (def in defs) {
            if (labelLengthAt(line, at, def) > 0) return def
        }
        return null
    }

    /** 行首（trim 后 0 位）是否以某个 [defs] 标签开头 —— 行型判定。 */
    private fun headDef(line: String, defs: List<FieldDef>): FieldDef? =
        if (line.isEmpty()) null else matchDefAt(line, 0, defs)

    private data class LabelPos(val def: FieldDef, val start: Int, val end: Int)

    private data class FieldHit(
        val def: FieldDef,
        val windowStart: Int,
        val windowEnd: Int,
        val bar: BarValue?,
        val int: Int?,
        val symbol: String?,
        val text: String?,
    )

    /**
     * 从 [from] 起扫行内所有 token 开头的标签命中，并各自在「本标签尾 → 下一标签头」的
     * 窗口里取值。窗口边界保证不跨字段偷值。
     */
    private fun scanFields(line: String, from: Int, defs: List<FieldDef>): List<FieldHit> {
        val hits = ArrayList<LabelPos>()
        var p = maxOf(from, 0)
        while (p < line.length) {
            val atTokenStart = p == 0 || isWs(line[p - 1])
            if (atTokenStart) {
                val def = matchDefAt(line, p, defs)
                if (def != null) {
                    hits += LabelPos(def, p, p + def.label.length)
                    p += def.label.length
                    continue
                }
            }
            p++
        }
        return hits.mapIndexed { idx, h ->
            val windowEnd = hits.getOrNull(idx + 1)?.start ?: line.length
            val ext = extractValue(line, h.end, windowEnd, h.def.kind)
            FieldHit(h.def, h.end, windowEnd, ext.bar, ext.int, ext.symbol, ext.text)
        }
    }

    private data class Extraction(
        val bar: BarValue? = null,
        val int: Int? = null,
        val symbol: String? = null,
        val text: String? = null,
    )

    /** 窗口 [from, until) 内取值：BAR = 整数 + 可选 `/ 上限`；INT = 首个整数；SYMBOL = ○/×；TEXT = 冒号后文本。 */
    private fun extractValue(line: String, from: Int, until: Int, kind: ValueKind): Extraction = when (kind) {
        ValueKind.BAR -> {
            val first = firstIntSpan(line, from, until)
            val bar = if (first == null) {
                null
            } else {
                var q = first.second
                while (q < until && isWs(line[q])) q++
                val max = if (q < until && line[q] == '/') {
                    var w = q + 1
                    while (w < until && isWs(line[w])) w++
                    firstInt(line, w, until)
                } else {
                    null
                }
                BarValue(first.first, max)
            }
            Extraction(bar = bar)
        }
        ValueKind.INT -> Extraction(int = firstInt(line, from, until))
        ValueKind.SYMBOL -> {
            var i = from
            var sym: String? = null
            while (i < until) {
                val c = line[i]
                if (c == '○' || c == '×') {
                    sym = c.toString()
                    break
                }
                i++
            }
            Extraction(symbol = sym)
        }
        ValueKind.TEXT -> {
            var i = from
            while (i < until && (isWs(line[i]) || line[i] == ':' || line[i] == '：')) i++
            val sb = StringBuilder()
            var wsRun = 0
            while (i < until) {
                val c = line[i]
                if (isWs(c)) {
                    wsRun++
                    if (wsRun >= 2) break
                    sb.append(' ') // 单空格保留；连续空白才是下一个字段的分隔
                } else {
                    wsRun = 0
                    sb.append(c)
                }
                i++
            }
            Extraction(text = sb.toString().trim().ifEmpty { null })
        }
    }

    /** 窗口内首个半角整数：跳过非数字，收集连续数字。溢出 / 无数字 → null（全角数字不认）。 */
    private fun firstIntSpan(s: String, from: Int, until: Int): Pair<Int, Int>? {
        var i = from
        while (i < until && !isAsciiDigit(s[i])) i++
        if (i >= until) return null
        var j = i
        while (j < until && isAsciiDigit(s[j])) j++
        val v = s.substring(i, j).toIntOrNull() ?: return null
        return v to j
    }

    private fun firstInt(s: String, from: Int, until: Int): Int? = firstIntSpan(s, from, until)?.first

    /** 所持金：千分位逗号随数字一起收下再剥掉（`¥5,177,463,124` → 5177463124）。 */
    private fun firstLongWithGrouping(s: String, from: Int, until: Int): Long? {
        var i = from
        while (i < until && !isAsciiDigit(s[i])) i++
        if (i >= until) return null
        var j = i
        while (j < until && (isAsciiDigit(s[j]) || s[j] == ',')) j++
        return s.substring(i, j).replace(",", "").toLongOrNull()
    }

    /** 纯方括号 token 内容（`[白莲]` → `白莲`）；嵌套/未闭合的残形跳过。 */
    private fun bracketTokens(t: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < t.length) {
            if (t[i] == '[') {
                val close = t.indexOf(']', i + 1)
                if (close < 0) break
                val content = t.substring(i + 1, close)
                if (content.isNotEmpty() && content.none { it == '[' || it == ']' }) out += content
                i = close + 1
            } else {
                i++
            }
        }
        return out
    }

    /** 时分 token：`数字 时 数字+ 分`（`9时00分`；`12～16时` 无分不命中）。 */
    private fun hasTimeToken(t: String): Boolean {
        var i = 0
        while (i < t.length - 2) {
            if (isAsciiDigit(t[i]) && t[i + 1] == '时') {
                var j = i + 2
                while (j < t.length && isAsciiDigit(t[j])) j++
                if (j > i + 2 && j < t.length && t[j] == '分') return true
            }
            i++
        }
        return false
    }

    // ------------------------------------------------------------------
    // 逐行解析（各型独立，可单测；parseHud 负责行型分派与组装）
    // ------------------------------------------------------------------

    /**
     * 组标签（§3.5）：行文本**整行**命中 `【…】` 才认（`indexOf('【')` 开头 + `endsWith('】')`，
     * 首个 `】` 必须就是行尾字符，挡住 `【上半身】哥特泰丽塔裙 / …` 这类装备行）。
     * 剥不出 → null（组标签缺省、按钮照常平铺；标签集合不进白名单，§10-9）。
     */
    fun parseGroupLabel(line: String): String? {
        val t = line.trim()
        if (t.indexOf('【') != 0) return null
        if (!t.endsWith('】')) return null
        val close = t.indexOf('】')
        if (close != t.length - 1) return null
        return t.substring(1, close).ifEmpty { null }
    }

    /**
     * 日期行（A1 形含所持金/筹码；A2~A4 形按 `日` + `N时M分` token 认）。
     * 时分形还要求行内**没有方括号 token**——A1 的地点行 `[自宅位置]：… [起床时间]：9时00分 [日历]`
     * 靠 `[日历]` 的「日」字与 `[起床时间]` 的时分值恰好凑齐两个条件，不能让它冒充日期行。
     */
    fun parseDateLine(line: String): DateLineInfo? {
        val t = line.trim()
        val moneyAt = t.indexOf("所持金")
        if (moneyAt >= 0) {
            val dateText = t.substring(0, moneyAt).trim().ifEmpty { t }
            val money = firstLongWithGrouping(t, moneyAt + 3, t.length)
            val chipAt = t.indexOf("筹码")
            val chips = if (chipAt >= 0) firstInt(t, chipAt + 2, t.length)?.toLong() else null
            return DateLineInfo(dateText, money, chips)
        }
        if (t.contains('日') && hasTimeToken(t) && bracketTokens(t).isEmpty()) {
            return DateLineInfo(t, null, null)
        }
        return null
    }

    /** 地点/时间行：`[自宅位置]：…` / `[起床时间]：…`（A1）。值到连续 2 个空白或下一个 `[` 为止。 */
    fun parsePlaceLine(line: String): PlaceTimeInfo? {
        val t = line.trim()
        val hasPlace = t.contains("[自宅位置]")
        val hasTime = t.contains("[起床时间]")
        if (!hasPlace && !hasTime) return null
        return PlaceTimeInfo(
            place = if (hasPlace) bracketLabeledValue(t, "[自宅位置]") else null,
            time = if (hasTime) bracketLabeledValue(t, "[起床时间]") else null,
        )
    }

    private fun bracketLabeledValue(t: String, tag: String): String? {
        val at = t.indexOf(tag)
        if (at < 0) return null
        var q = at + tag.length
        while (q < t.length && (isWs(t[q]) || t[q] == ':' || t[q] == '：')) q++
        val sb = StringBuilder()
        var wsRun = 0
        while (q < t.length) {
            val c = t[q]
            if (c == '[') break
            if (isWs(c)) {
                wsRun++
                if (wsRun >= 2) break
                sb.append(' ') // 单空格是值的一部分（「命莲寺 - 别馆」）；连续空白才是分隔
            } else {
                wsRun = 0
                sb.append(c)
            }
            q++
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /**
     * 数值行：行首是数值标签才认（A1 两行 / A2 的法力行与清醒裾裾行 / A3 的体力行）。
     * 行内其余 token 开头的标签照扫（A1 一行三组）。行型不成立 → null；
     * 行型成立但全行剥不出值（如全角数字）→ 空表。
     */
    fun parseBarLine(line: String): List<NamedBar>? {
        val t = line.trim()
        if (headDef(t, BAR_DEFS) == null) return null
        return scanFields(t, 0, BAR_DEFS).mapNotNull { h -> h.bar?.let { NamedBar(h.def.label, it) } }
    }

    /**
     * 经验行：行首 `好感度…`（A3）或 `[名字] 好感度…`（A4 角色块经验行，剥出 name）。
     * 行型不成立 → null；成立但某字段无值 → 该字段 null。
     */
    fun parseExpLine(line: String): ExpLineInfo? {
        val t = line.trim()
        if (t.isEmpty()) return null
        var name: String? = null
        if (t.startsWith("[")) {
            val close = t.indexOf(']')
            if (close > 1) {
                val candidate = t.substring(1, close)
                if (candidate.none { it == '[' || it == ']' } &&
                    close + 1 < t.length && isWs(t[close + 1])
                ) {
                    val rest = t.substring(close + 1).trimStart()
                    if (headDef(rest, listOf(DEF_FAVOR)) != null) name = candidate
                }
            }
        }
        if (name == null && headDef(t, listOf(DEF_FAVOR)) == null) return null
        val hits = scanFields(t, 0, EXP_DEFS)
        fun firstOf(label: String): FieldHit? = hits.firstOrNull { it.def.label == label }
        val values = ExpValues(
            favor = firstOf(LABEL_FAVOR)?.int,
            trust = firstOf(LABEL_TRUST)?.int,
            frustration = firstOf(LABEL_FRUSTRATION)?.int,
            children = firstOf(LABEL_CHILDREN)?.int,
            pregnancy = firstOf(LABEL_PREGNANCY)?.symbol,
            conception = firstOf(LABEL_CONCEPTION)?.symbol,
        )
        return ExpLineInfo(name, values)
    }

    /** 工作行：行首 `工作情报…`（A3/A4）。工作地点是文本值；战斗能力 = 段位字母 + 整数。 */
    fun parseWorkLine(line: String): WorkInfo? {
        val t = line.trim()
        if (headDef(t, listOf(FieldDef(DEF_WORK_HEAD, ValueKind.INT))) == null) return null
        val hits = scanFields(t, 0, WORK_DEFS)
        val place = hits.firstOrNull { it.def.label == "工作地点" }?.text
        val battle = hits.firstOrNull { it.def.label == "战斗能力" }
        val rank = battle?.let { h ->
            var i = h.windowStart
            val sb = StringBuilder()
            while (i < h.windowEnd && !isAsciiDigit(line[i])) {
                if (!isWs(line[i]) && line[i] != ':' && line[i] != '：') sb.append(line[i])
                i++
            }
            sb.toString().trim().ifEmpty { null }
        }
        return WorkInfo(place, rank, battle?.int)
    }

    /**
     * 主角标记行：`(主角)…`（A4）/ `(=主人公)…`（A3）开头（半/全角括号都认）。
     * 返回前缀后的标签（token，到首个空白为止）——A4 是状态名 `清醒裾裾`，A3 是名字 `清`。
     */
    fun parseProtagonistMarker(line: String): String? {
        val t = line.trim()
        val rest = when {
            t.startsWith("(主角)") -> t.substring("(主角)".length)
            t.startsWith("（主角）") -> t.substring("（主角）".length)
            t.startsWith("(=主人公)") -> t.substring("(=主人公)".length)
            t.startsWith("（=主人公）") -> t.substring("（=主人公）".length)
            t.startsWith("（＝主人公）") -> t.substring("（＝主人公）".length)
            else -> return null
        }
        val label = rest.trimStart().takeWhile { !isWs(it) }
        return label.ifEmpty { null }
    }

    /**
     * 角色数值行（切分规则 2 的第三行）：裸名字 token + `[体力]` + 数值（A4 的
     * `昂  [体力] 3845 …` 与 `白莲  [体力] 2666 …`）。`[体力]` 之后其余数值标签照扫
     * （酒/精；`气`/`法` 单字不在固定标签集合里，按规格不认）。不成形 → null。
     */
    fun parseStatRow(line: String): StatRowInfo? {
        val t = line.trim()
        val bracket = t.indexOf("[$STAT_LABEL]")
        if (bracket <= 0) return null
        val prefix = t.substring(0, bracket).trimEnd()
        val lastWs = prefix.lastIndexOfAny(charArrayOf(' ', '　', '\t'))
        val name = if (lastWs < 0) prefix else prefix.substring(lastWs + 1)
        if (name.isEmpty()) return null
        if (name.any { it == '[' || it == ']' || it == '(' || it == ')' || it == '（' || it == '）' }) return null
        val after = bracket + STAT_LABEL.length + 2
        val hits = scanFields(t, after, BAR_DEFS)
        val windowEnd = hits.minOfOrNull { it.windowStart } ?: t.length
        val main = extractValue(t, after, windowEnd, ValueKind.BAR).bar ?: return null
        val stats = LinkedHashMap<String, BarValue>()
        stats[STAT_LABEL] = main
        for (h in hits) h.bar?.let { stats[h.def.label] = it }
        return StatRowInfo(name, stats, dangerDay = false)
    }

    /**
     * 在场角色行（切分规则 1）：一行里 ≥2 个 `[名字]` 方括号 token（内容命中状态词最小集
     * 的不算名字）。注意规则按字面成立：A2 的 buff 行 `[调和魔药] [时间停止可] …` 也满足
     * 「≥2 个非状态词 token」——这些假名字组不出「经验行 + 数值行」，切分规则 2 会把它们
     * 全部挡掉，角色块数不受污染；壳层的角色条只吃 [HudSnapshot.blocks]。
     */
    fun parsePresentNames(line: String): List<String>? {
        val names = bracketTokens(line.trim()).filter { it !in STATUS_WORDS }
        return if (names.size >= 2) names else null
    }

    /**
     * buff 行（§4.1「buff 行整行变 chips」）：整行只由方括号 token 与空白构成才认
     * （A2 的 buff 行）。带冒号取值的标签行（A1 的 `[自宅位置]：…`）因夹着文本天然不命中。
     */
    fun parseBuffChips(line: String): List<String>? {
        val t = line.trim()
        val tokens = bracketTokens(t)
        if (tokens.isEmpty()) return null
        var i = 0
        while (i < t.length) {
            if (isWs(t[i])) {
                i++
                continue
            }
            if (t[i] != '[') return null
            val close = t.indexOf(']', i + 1)
            if (close < 0) return null
            i = close + 1
        }
        return tokens
    }

    /**
     * 执行中（A2/A3/A4）：`执行中:` 后必须跟 `「…」` 或 `[…]`（负样例「后无引号内容」→ null）；
     * 允许夹 `(主语)`（A4 的 `执行中 (昂) [带着雾之湖的水]`）。
     */
    fun parseExecuting(line: String): ExecutingInfo? {
        var from = 0
        while (true) {
            val at = findLabelToken(line, LABEL_EXECUTING, from) ?: return null
            val info = extractExecuting(line, at + LABEL_EXECUTING.length)
            if (info != null) return info
            from = at + 1
        }
    }

    private fun findLabelToken(line: String, label: String, from: Int): Int? {
        var p = from
        while (true) {
            val at = line.indexOf(label, p)
            if (at < 0) return null
            val end = at + label.length
            val atTokenStart = at == 0 || isWs(line[at - 1])
            val okTail = end >= line.length || isWs(line[end]) || line[end] == ':' || line[end] == '：'
            if (atTokenStart && okTail) return at
            p = at + 1
        }
    }

    private fun extractExecuting(line: String, from: Int): ExecutingInfo? {
        var q = from
        while (q < line.length && (isWs(line[q]) || line[q] == ':' || line[q] == '：')) q++
        var subject: String? = null
        if (q < line.length && (line[q] == '(' || line[q] == '（')) {
            val closeAscii = line.indexOf(')', q)
            val closeFull = line.indexOf('）', q)
            val close = when {
                closeAscii < 0 -> closeFull
                closeFull < 0 -> closeAscii
                else -> minOf(closeAscii, closeFull)
            }
            if (close < 0) return null
            subject = line.substring(q + 1, close).trim().ifEmpty { null }
            q = close + 1
            while (q < line.length && isWs(line[q])) q++
        }
        if (q >= line.length) return null
        val text = when (line[q]) {
            '「' -> {
                val e = line.indexOf('」', q + 1)
                if (e < 0) return null
                line.substring(q + 1, e)
            }
            '[' -> {
                val e = line.indexOf(']', q + 1)
                if (e < 0) return null
                line.substring(q + 1, e)
            }
            else -> return null
        }
        if (text.isEmpty()) return null
        return ExecutingInfo(subject, text)
    }

    /**
     * 上回指令（A2/A3）：`<上回指令:…>` 包裹形式；**空值也认**（A2 的 `<上回指令:>` → 空串，
     * 设计稿明注「空值也要识别」）。没有 `>` 收口 → null（残形不算成立）。
     */
    fun parseLastCommand(line: String): String? {
        val open = line.indexOf("<上回指令")
        if (open < 0) return null
        var q = open + "<上回指令".length
        if (q < line.length && (line[q] == ':' || line[q] == '：')) q++
        val close = line.indexOf('>', q)
        if (close < 0) return null
        return line.substring(q, close)
    }

    /** 今日运势（A2/A4）：`昂: [今日运势: 171>]` → 171（取标签后首个整数；`>`/`]` 自然止住）。 */
    fun parseFortune(line: String): Int? {
        val at = line.indexOf(LABEL_FORTUNE)
        if (at < 0) return null
        return firstInt(line, at + LABEL_FORTUNE.length, line.length)
    }

    // ------------------------------------------------------------------
    // 整体组装
    // ------------------------------------------------------------------

    /**
     * 一批屏行 → [HudSnapshot]。行型分派顺序：组标签 / 上回指令 / 日期 / 地点 / 数值行 /
     * 经验行 / 工作行 / 主角标记 / 角色数值行 → 都不认的行走非独占扫描（buff chips、
     * 在场行、执行中、今日运势、危险日前挂接）。重复出现按最后一次为准（在场名单除外：
     * 多行并列按出现序并集，切分规则 2 的三行一致判据会自然挡掉行文里的括号噪声）。
     */
    fun parseHud(lines: List<String>): HudSnapshot {
        var dateText: String? = null
        var money: Long? = null
        var chips: Long? = null
        var place: String? = null
        var time: String? = null
        val bars = LinkedHashMap<String, BarValue>()
        val buffChips = ArrayList<String>()
        var fortune: Int? = null
        var executing: ExecutingInfo? = null
        var lastCommand: String? = null
        var exps: ExpValues? = null
        var work: WorkInfo? = null
        var markerLabel: String? = null
        val markerBars = LinkedHashMap<String, BarValue>()
        val expLines = ArrayList<ExpLineInfo>()
        val statRows = ArrayList<StatRowInfo>()
        val presentNames = LinkedHashSet<String>()
        val groupLabels = ArrayList<String>()
        var pendingStatIdx: Int? = null

        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            // —— 独占行型：依序试认，谁先认下谁独占（顺序 = 附录 A 的行型优先级）
            var claimed = false
            fun claimedExclusive() {
                claimed = true
                pendingStatIdx = null
            }

            parseGroupLabel(line)?.let {
                groupLabels += it
                claimedExclusive()
            }
            if (!claimed) {
                parseLastCommand(line)?.let {
                    lastCommand = it
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parseDateLine(line)?.let {
                    dateText = it.dateText
                    it.money?.let { m -> money = m }
                    it.chips?.let { c -> chips = c }
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parsePlaceLine(line)?.let {
                    it.place?.let { p -> place = p }
                    it.time?.let { t -> time = t }
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parseBarLine(line)?.let { named ->
                    for (bar in named) bars[bar.label] = bar.value
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parseExpLine(line)?.let {
                    expLines += it
                    if (it.name == null) exps = it.values
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parseWorkLine(line)?.let {
                    work = it
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parseProtagonistMarker(line)?.let {
                    markerLabel = it
                    // 标记行里 token 开头的数值标签值并入主角 stats（A3 主角行的酒/精）
                    for (h in scanFields(line, 0, BAR_DEFS)) {
                        h.bar?.let { b -> markerBars[h.def.label] = b }
                    }
                    claimedExclusive()
                }
            }
            if (!claimed) {
                parseStatRow(line)?.let {
                    statRows += it
                    pendingStatIdx = statRows.size - 1
                    claimed = true
                }
            }
            if (!claimed) {
                // —— 非独占区：一条行可同时喂多个通道（A2 的运势行同时有执行中）
                parseBuffChips(line)?.let { buffChips += it }
                parsePresentNames(line)?.let { presentNames.addAll(it) }
                parseExecuting(line)?.let { executing = it }
                parseFortune(line)?.let { fortune = it }
                if (line.contains(LABEL_DANGER_DAY) && pendingStatIdx != null) {
                    val idx = pendingStatIdx!!
                    statRows[idx] = statRows[idx].copy(dangerDay = true)
                }
                pendingStatIdx = null
            }
        }

        // 角色块组装（切分规则 2/4）：在场名单里的名字，经验行 + 数值行齐备才成块
        val blocks = presentNames.mapNotNull { name ->
            val exp = expLines.lastOrNull { it.name == name } ?: return@mapNotNull null
            val stat = statRows.lastOrNull { it.name == name } ?: return@mapNotNull null
            CharacterBlock(name, exp.values, stat.stats, stat.dangerDay)
        }

        // 主角（切分规则 3）：数值行名字不在在场名单里的那一行；否则退回前缀行标签
        val protagonistRow = statRows.lastOrNull { it.name !in presentNames }
        val protagonist = if (protagonistRow != null || markerLabel != null) {
            val stats = LinkedHashMap<String, BarValue>()
            stats.putAll(markerBars)
            if (protagonistRow != null) stats.putAll(protagonistRow.stats)
            ProtagonistInfo(
                name = protagonistRow?.name ?: markerLabel,
                stats = stats,
                markerLabel = markerLabel,
            )
        } else {
            null
        }

        return HudSnapshot(
            dateText = dateText,
            money = money,
            chips = chips,
            place = place,
            time = time,
            bars = bars,
            buffChips = buffChips,
            fortune = fortune,
            executing = executing,
            lastCommand = lastCommand,
            exps = exps,
            work = work,
            protagonist = protagonist,
            blocks = blocks,
            groupLabels = groupLabels,
        )
    }
}
