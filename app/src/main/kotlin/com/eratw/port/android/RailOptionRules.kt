package com.eratw.port.android

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.paletteSwatchMask
import com.eratw.port.render.view.LeanOptionFilter
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 右侧选项栏「自定义过滤 + 常用置顶」的纯规则层：过滤判据、置顶/历史的集合代数、
 * 偏好串的编解码。**零 Android 依赖、零 Compose 依赖**，全部可以 JVM 单测
 * （`RailOptionRulesTest`）——UiPrefs 只做薄存取壳，ShellUi / MainActivity 只做接线。
 *
 * ## 判据从哪来（不重造轮子）
 * 「屏上自带编号」与「调色板」两条识别都是**既有件**：
 * - 引擎快照的 [ConsoleOption.printsOwnNumber]（core-vm）：标签开头的 `[N]` 与提交值一致；
 * - [LeanOptionFilter.isCommonCommand]（render）：末尾 `名字[N]` 形态 + 上面那条；
 * - [paletteSwatchMask]（core-vm）：同一游戏行连续 8 枚以上色块才算调色板。
 *
 * 本文件只回答三件事：哪些候选该从右栏**藏掉**（自定义过滤）、哪些候选**可以置顶**
 * （长按菜单收全部非色块；设置页历史只收带编号指令）、置顶/历史这两份有序小集合怎么
 * 合并与淘汰。可点性判定仍在引擎（世代 + 等待类型），正文画布的按钮命中一概不经这里。
 */
object RailOptionRules {

    // ------------------------------------------------------------------
    // 自定义过滤
    // ------------------------------------------------------------------

    /** 过滤结果：留下的候选（顺序原样）+ 本屏被藏掉的枚数（计数行显示用）。 */
    data class RailFilterResult(
        val options: List<ConsoleOption>,
        val hiddenCount: Int,
    )

    /**
     * 自定义过滤：黑名单（按 [ConsoleOption.label] 精确匹配）与「隐藏无编号按钮」规则
     * **叠加**生效——两条任一命中就藏，黑名单为空且规则关闭时原样返回。
     *
     * 唯一的豁免是**真调色板**色块（连续 8 枚以上）：布娃娃调色板是选色工具，藏掉它
     * 等于把功能藏了；单个彩色字形不算调色板，照走黑名单/规则。
     *
     * 纯显示层：不参与引擎可点性判定，正文里的按钮命中不受影响。
     */
    fun filterOptions(
        options: List<ConsoleOption>,
        hiddenLabels: Set<String>,
        hideUnnumbered: Boolean,
    ): RailFilterResult {
        if (options.isEmpty() || (hiddenLabels.isEmpty() && !hideUnnumbered)) {
            return RailFilterResult(options, 0)
        }
        val palette = paletteSwatchMask(options)
        val kept = ArrayList<ConsoleOption>(options.size)
        var hidden = 0
        for (i in options.indices) {
            val option = options[i]
            if (palette[i]) {
                kept += option
                continue
            }
            val labelHidden = option.label.isNotEmpty() && option.label in hiddenLabels
            val ruleHidden = hideUnnumbered && !LeanOptionFilter.isCommonCommand(option)
            if (labelHidden || ruleHidden) hidden++ else kept += option
        }
        return RailFilterResult(kept, hidden)
    }

    // ------------------------------------------------------------------
    // 可置顶判据
    // ------------------------------------------------------------------

    /**
     * 逐条回答「这枚候选算不算带编号指令」（**历史候选**的收录判据）：不是调色板色块、
     * 且屏上自带编号。与输入列表等长的布尔数组。
     *
     * 调色板不收：色块没有名字，把 `■` 记进历史玩家认不出；裸数字按钮
     * （标签被剥空）同样进不来 —— [LeanOptionFilter.isCommonCommand] 已带这层。
     *
     * 注意：这不是右栏长按菜单「置顶」的判据（那是 [pinMenuMaskOf]）。菜单照这条判据
     * 收窄会让 `[CONFIG]` / `地图切换` 这类无编号按钮只弹出「从右栏隐藏」一项，
     * 看着像置顶入口被过滤项挤没了。
     */
    fun pinnableMaskOf(options: List<ConsoleOption>): BooleanArray {
        val palette = paletteSwatchMask(options)
        return BooleanArray(options.size) { i ->
            !palette[i] && LeanOptionFilter.isCommonCommand(options[i])
        }
    }

    /**
     * 逐条回答「右栏长按菜单里这枚候选能不能置顶」：只要**不是色块**就亮。
     *
     * 与 [pinnableMaskOf] 分开是本条规则的唯一职责：带编号指令只是历史候选的收录面，
     * 玩家想钉 `[CONFIG]`（`USERCOM_コマンド表示処理.ERB:40`）或 `地图切换`
     * （`qol_MAP.ERB:178`）这类无编号按钮时也必须能钉 —— 常用区显示硬规则
     * （[visiblePinnedOf]：屏上有同值按钮才出现）已经保证钉了也不会凭空多出一条。
     *
     * 色块不亮：`■` 没有名字，钉进常用区玩家认不出是哪个部件；真调色板行本身也
     * 不走长按菜单（`PaletteChipRow` 专列）。
     */
    fun pinMenuMaskOf(options: List<ConsoleOption>): BooleanArray =
        BooleanArray(options.size) { i -> !options[i].isColorSwatch }

    // ------------------------------------------------------------------
    // 常用置顶（有序小集合的代数）
    // ------------------------------------------------------------------

    /**
     * 置顶一枚：已存在则挪到最新（LRU 语义），不存在则追加；超过 [cap] 从最旧的一端
     * 淘汰。空值是空操作。
     */
    fun pinTo(current: List<String>, submitValue: String, cap: Int): List<String> {
        if (submitValue.isEmpty() || cap <= 0) return current
        val next = ArrayList<String>(current.size + 1)
        next.addAll(current.filterNot { it == submitValue })
        next.add(submitValue)
        return if (next.size > cap) next.subList(next.size - cap, next.size).toList() else next
    }

    /** 取消置顶一枚；不在表里时原样返回。 */
    fun unpinFrom(current: List<String>, submitValue: String): List<String> =
        if (submitValue in current) current.filterNot { it == submitValue } else current

    /**
     * 常用区的显示规则（硬规则）：**当前屏上必须存在同提交值的按钮**才显示，
     * 标签用当前屏的实时文案（状态随屏变化的开关，常用区跟着换字）。屏上没有的
     * 置顶项整条不出现 —— 不渲染灰条占位，玩家看到的常用区永远每个都能点。
     *
     * 同屏多枚同值按钮取屏序最先的一枚；顺序与置顶列表一致（越靠前越早置顶）。
     */
    fun visiblePinnedOf(pinned: List<String>, chips: List<ConsoleOption>): List<ConsoleOption> {
        if (pinned.isEmpty() || chips.isEmpty()) return emptyList()
        val byValue = HashMap<String, ConsoleOption>()
        chips.forEach { chip -> byValue.putIfAbsent(chip.submitValue, chip) }
        return pinned.mapNotNull { byValue[it] }
    }

    // ------------------------------------------------------------------
    // 置顶历史候选（设置页「从最近见过的指令里新增」的数据面）
    // ------------------------------------------------------------------

    /** 一条历史候选：提交值 + 最近一次见到的名字。 */
    data class RailHistoryEntry(
        val value: String,
        val label: String,
    )

    /**
     * 把这一屏的候选并入历史：只收**可置顶**的（[pinnableMaskOf]），按提交值去重、
     * 名字取最新见到的，超过 [cap] 从最旧一端淘汰。空输入原样返回。
     */
    fun mergeHistory(
        existing: List<RailHistoryEntry>,
        options: List<ConsoleOption>,
        cap: Int,
    ): List<RailHistoryEntry> {
        if (options.isEmpty() || cap <= 0) return existing
        val pinnable = pinnableMaskOf(options)
        var out = existing
        for (i in options.indices) {
            if (!pinnable[i]) continue
            val option = options[i]
            out = mergeHistoryEntry(out, RailHistoryEntry(option.submitValue, option.label), cap)
        }
        return out
    }

    /**
     * 并入**一条**历史候选：按提交值去重、名字取最新，超过 [cap] 从最旧一端淘汰。
     * 空值/空上限原样返回。
     *
     * 长按菜单允许置顶无编号按钮（[pinMenuMaskOf]），这类值不会经 [mergeHistory] 的
     * 带编号判据进历史；置顶时补记一条，设置页的已置顶列表才能显示按钮名字而不是
     * 兜底的「编号 {0}」。
     */
    fun mergeHistoryEntry(
        existing: List<RailHistoryEntry>,
        entry: RailHistoryEntry,
        cap: Int,
    ): List<RailHistoryEntry> {
        if (entry.value.isEmpty() || cap <= 0) return existing
        val out = existing.filterNot { it.value == entry.value } + entry
        return if (out.size > cap) out.takeLast(cap) else out
    }

    // ------------------------------------------------------------------
    // 快捷宏（玩家自定义：步骤 × 连发次数 + 组合重复 → era 指令集串）
    //
    // 玩家要的是「哪个选项连发几次」「先 a 后 b 这个组合重复几次」这种**可自由组合**的宏；
    // 壳层只存「步骤 + 次数」，提交时编译成 era 通用指令集串（`\n` 分段、`\e` 快进），
    // 分段与剥离一律由引擎侧完成（QueueInputSink.submit，锚 EmueraConsole.cs:146/:1429-1438）。
    // ------------------------------------------------------------------

    /**
     * 宏的一步：提交 [value] 连发 [times] 次。
     *
     * @param value 提交值（游戏选项的 submitValue，或玩家手填的任意输入）
     * @param label 显示用文案（取最近见到的选项名）；空 = 退回 [value] 显示
     * @param times 连发次数（1..[MACRO_TIMES_MAX]）
     */
    data class MacroStep(
        val value: String,
        val label: String = "",
        val times: Int = 1,
    )

    /**
     * 一条玩家自定义宏 = 有序步骤 + 整组重复次数。
     *
     * 提交时编译成 era 通用指令集串（[macroContent]）：每步按 [MacroStep.times] 展开、每段尾
     * 带字面反斜杠+`e`（跳过/快进），整组按 [repeat] 重复。例：
     * - `[会话 ×5]` ⇒ 连发五次会话；
     * - `[会话 → 道具]，组合 ×3` ⇒ 「会话、道具」这一轮跑三遍。
     *
     * 按钮文案不靠玩家起名，直接由 [macroLabel] 从步骤**自动生成**（如「会话×5」「会话→道具 ×3」）。
     */
    data class QuickMacro(
        val steps: List<MacroStep>,
        val repeat: Int = 1,
    )

    /** 快捷宏总条数上限（常用区一屏放得下）。满了 UI 提示先删一条，不静默淘汰。 */
    const val QUICK_MACRO_CAP: Int = 6

    /** 一条宏最多几步（先 a 后 b 后 c…；再长就不像「快捷」了）。 */
    const val MACRO_STEP_CAP: Int = 4

    /** 单步连发次数上限。 */
    const val MACRO_TIMES_MAX: Int = 99

    /** 整组重复次数上限。 */
    const val MACRO_REPEAT_MAX: Int = 99

    /** 一条宏编译出的段数硬上限（护栏：别让一条宏把输入队列灌爆、把游戏卡成幻灯片）。 */
    const val MACRO_SEGMENT_CAP: Int = 600

    /** 段尾的跳过标记与段间分隔符：都是**字面两字符**（反斜杠 + 字母），即指令集串的原形。 */
    private const val MACRO_SEGMENT_SKIP = "\\e"
    private const val MACRO_SEPARATOR = "\\n"

    /**
     * 提交值里会被指令集语法**重新解释**的字符：`(` `)` 是重复分组、`*` 是重复次数。
     * 编译时逐个加反斜杠转义（`\(` = 字面左括号），与引擎侧 `parseInput` 的转义表同源
     * （`\` + 其他字符 = 该字符本身，`EmueraConsole.cs:1546-1623`）。
     */
    private const val MACRO_SYNTAX_CHARS = "()*"

    /**
     * 提交值能不能被指令集串表示（**协议契约**，UI 与存储都据此拒绝，不做悄悄改写）。
     *
     * 两类不可表示：
     * - 含 CR/LF：一个「值」会被切段器拆成两次提交，语义有歧义；
     * - 含反斜杠：转义成 `\\` 之后仍是「反斜杠 + 下一个字符」，若下一个字符是 `n`/`r`，
     *   展开后的串会再被 `splitSegments` 当成分隔符（切段发生在展开**之后**）⇒ 无法可靠表达。
     *
     * 其余字符（含半角括号与 `*`）都能靠 [escapeMacroValue] 安全送出。
     */
    fun isMacroValueSubmittable(value: String): Boolean =
        value.none { it == '\r' || it == '\n' || it == '\\' }

    /** 按引擎转义表转义提交值里的语法字符（`(` → `\(`、`*` → `\*`），其余原样。 */
    fun escapeMacroValue(value: String): String = buildString(value.length + 4) {
        value.forEach { ch ->
            if (ch in MACRO_SYNTAX_CHARS) append('\\')
            append(ch)
        }
    }

    /**
     * 按钮文案：**直接体现宏的定义**，不需要玩家起名字。
     *
     * 形如 `会话×5`、`会话→道具×2`、`会话→道具 ×3`（末段 `×3` = 整组重复三轮，前面无空格的是
     * 单步连发次数，两者刻意用不同位置区分）。步骤全空 ⇒ 空串（调用侧跳过渲染）。
     */
    fun macroLabel(macro: QuickMacro): String {
        val steps = macro.steps.filter { it.value.isNotBlank() }
        if (steps.isEmpty()) return ""
        val body = steps.joinToString("→") { step ->
            val name = step.label.ifBlank { step.value }
            if (step.times > 1) "$name×${step.times}" else name
        }
        return if (macro.repeat > 1) "$body ×${macro.repeat}" else body
    }

    /**
     * 编译成**era 原生指令集串**交给引擎（`QueueInputSink.submit` → `parseInput` 展开）。
     *
     * 壳层只负责「拼语法」，**不自己展开重复**（那会把引擎的指令集能力绕过去）：
     * - 单步连发 t 次 ⇒ `(值\e\n)*t`；
     * - 多步组合 ⇒ 各步顺序相接（`(a\e\n)(b\e\n)`）；
     * - 整组重复 R 轮 ⇒ 外面再套一层 `( … )*R`。
     *
     * 段尾 `\e` 是「跳过」标记（原版 `\e` = ESC），它让每段提交后画面快进到下个等待点 ——
     * 连发能自动跑下去就靠它（官方手册的 `0\e\n1\e\n` 同义）。
     *
     * 单步、1 次、1 轮这种最简单的情形刻意**不带括号**（原版 `:1425` 只有含 `(` 才解析，
     * 不带括号时 `\e`/`\n` 走字面切段），因此也不会多出末尾的空回车。
     *
     * ## 转义契约（2026-11-11 复查后补）
     *
     * 步骤的提交值是**玩家/语料给的普通文本**，不是协议的一部分：其中的 `(` `)` `*` 会改变
     * 分组结构，一律经 [escapeMacroValue] 转义后再拼进语法。含 CR/LF 或反斜杠的值
     * 见 [isMacroValueSubmittable] —— 那两类**不能**用指令集串表达，调用方（草稿校验）必须先拒绝。
     *
     * @throws IllegalArgumentException 传入的宏含不可表示的提交值。编译前请走
     *   `MacroDraft.build()` 的严格校验，别指望这里兜底。
     */
    fun macroContent(macro: QuickMacro): String {
        val steps = macro.steps.filter { it.value.isNotBlank() }
        if (steps.isEmpty()) return ""
        val bad = steps.firstOrNull { !isMacroValueSubmittable(it.value) }
        require(bad == null) {
            "提交值无法用指令集串表达（含换行或反斜杠）：${bad?.value}"
        }
        val repeat = macro.repeat.coerceIn(1, MACRO_REPEAT_MAX)
        // 最简单形态：一段、不带括号（原版不解析，直接按字面 \n 切段）。
        if (repeat == 1 && steps.size == 1 && steps[0].times <= 1) {
            return escapeMacroValue(steps[0].value.trim()) + MACRO_SEGMENT_SKIP
        }
        val inner = steps.joinToString("") { step ->
            val times = step.times.coerceIn(1, MACRO_TIMES_MAX)
            val value = escapeMacroValue(step.value.trim())
            if (times > 1) {
                "($value$MACRO_SEGMENT_SKIP$MACRO_SEPARATOR)*$times"
            } else {
                "$value$MACRO_SEGMENT_SKIP$MACRO_SEPARATOR"
            }
        }
        // 不含括号时引擎按**字面**分隔符切段（不走 parseInput），可以安全去掉末尾分隔符，
        // 免得每点一次宏都多出一次空回车。含括号时 `\e` 自己会补真换行，去了也白去。
        return if (repeat > 1) "($inner)*$repeat" else inner.removeSuffix(MACRO_SEPARATOR)
    }
    /** 这条宏会提交几段（设置页预览与护栏读数用）。 */
    fun macroSegmentCount(macro: QuickMacro): Int =
        macro.steps.sumOf { step -> if (step.value.isBlank()) 0 else step.times.coerceIn(1, MACRO_TIMES_MAX) } *
            macro.repeat.coerceIn(1, MACRO_REPEAT_MAX)

    /**
     * 规整：丢掉空步骤、夹次数/重复、裁到 [MACRO_STEP_CAP] 步、并把总段数压到
     * [MACRO_SEGMENT_CAP] 以内；一步都不剩 ⇒ null。
     *
     * 段数护栏以前只是「声明了没人用」（4 步 × 99 连发 × 99 轮 = 39204 段可以存进去，
     * 一点就把输入队列灌爆）。这里先按「单轮段数」反算整组重复的上限再夹 —— 只要
     * 单轮段数 ≤ 上限（4 × 99 = 396 < 600 恒成立），夹重复就一定收得进。
     *
     * **不可表示的提交值一律拒绝**（返回 null，由 [isMacroValueSubmittable] 判定）：
     * 这条是存储/解码侧的兜底，交互侧该在 `MacroDraft.build()` 里先给出明确提示。
     */
    fun sanitizeMacro(macro: QuickMacro): QuickMacro? {
        val steps = macro.steps
            .filter { it.value.isNotBlank() }
            .take(MACRO_STEP_CAP)
            .map { it.copy(value = it.value.trim(), times = it.times.coerceIn(1, MACRO_TIMES_MAX)) }
        if (steps.isEmpty()) return null
        if (steps.any { !isMacroValueSubmittable(it.value) }) return null
        val timesPerRound = steps.sumOf { it.times }.coerceAtLeast(1)
        val maxRepeat = (MACRO_SEGMENT_CAP / timesPerRound).coerceAtLeast(1)
        val repeat = macro.repeat.coerceIn(1, MACRO_REPEAT_MAX).coerceAtMost(maxRepeat)
        return QuickMacro(steps, repeat)
    }

    /** 新增一条：追加到末尾；已到 [cap] 或宏不成立（无步骤）返回 null（不静默淘汰已有宏）。 */
    fun addQuickMacro(
        current: List<QuickMacro>,
        macro: QuickMacro,
        cap: Int = QUICK_MACRO_CAP,
    ): List<QuickMacro>? {
        val clean = sanitizeMacro(macro) ?: return null
        if (cap <= 0 || current.size >= cap) return null
        return current + clean
    }

    /** 改第 [index] 条（不改顺序）；越界或宏不成立时原样返回。 */
    fun updateQuickMacro(
        current: List<QuickMacro>,
        index: Int,
        macro: QuickMacro,
    ): List<QuickMacro> {
        if (index !in current.indices) return current
        val clean = sanitizeMacro(macro) ?: return current
        val next = ArrayList(current)
        next[index] = clean
        return next
    }

    /** 删第 [index] 条；越界原样返回。 */
    fun removeQuickMacro(current: List<QuickMacro>, index: Int): List<QuickMacro> =
        if (index !in current.indices) current else current.filterIndexed { i, _ -> i != index }

    /**
     * 一条宏的编码：`repeat|值:名字:次数|值:名字:次数`（每个文本字段 URL 编码，
     * 编码结果不含 `|`/`:`/换行，分隔符不会撞）。多条之间用换行分条。
     */
    fun encodeQuickMacros(macros: List<QuickMacro>): String =
        macros.joinToString("\n") { macro ->
            (listOf(macro.repeat.toString()) + macro.steps.map { step ->
                URLEncoder.encode(step.value, "UTF-8") + ":" +
                    URLEncoder.encode(step.label, "UTF-8") + ":" + step.times
            }).joinToString("|")
        }

    /**
     * [encodeQuickMacros] 的逆：坏行/坏段跳过、夹到合法区间、空宏丢弃 —— 偏好读端不许炸。
     * 老版本（名字+内容两字段）的行在这里自然判坏并丢弃（本功能未发布，不做迁移）。
     */
    fun decodeQuickMacros(encoded: String): List<QuickMacro> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split('\n').mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val parts = line.split('|')
            if (parts.size < 2) return@mapNotNull null
            val repeat = parts.first().toIntOrNull()?.coerceIn(1, MACRO_REPEAT_MAX) ?: 1
            val steps = parts.drop(1).mapNotNull { part ->
                val fields = part.split(':')
                if (fields.size != 3) return@mapNotNull null
                val value = runCatching { URLDecoder.decode(fields[0], "UTF-8") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = runCatching { URLDecoder.decode(fields[1], "UTF-8") }.getOrDefault("")
                val times = fields[2].toIntOrNull()?.coerceIn(1, MACRO_TIMES_MAX) ?: 1
                MacroStep(value, label, times)
            }
            sanitizeMacro(QuickMacro(steps, repeat))
        }
    }
    // ------------------------------------------------------------------
    // 偏好串编解码（有序列表在 SharedPreferences 里落一行字符串）
    // ------------------------------------------------------------------

    /**
     * 有序字符串列表的一行编码：逐条 [URLEncoder] 后用 `|` 连接。
     * 编码结果只含 `[A-Za-z0-9._*-]` 与 `+`，分隔符不可能撞上值本身；
     * 置顶值来自 [ConsoleOption.submitValue]，游戏串按钮可能是任意文本，
     * 所以必须转义而不是裸拼。
     */
    fun encodeStringList(values: List<String>): String =
        values.joinToString("|") { URLEncoder.encode(it, "UTF-8") }

    /** [encodeStringList] 的逆：坏段（历史遗留/手工改坏）跳过，不抛 —— 偏好读端不许炸。 */
    fun decodeStringList(encoded: String): List<String> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split('|').mapNotNull { part ->
            if (part.isEmpty()) {
                null
            } else {
                runCatching { URLDecoder.decode(part, "UTF-8") }.getOrNull()
            }
        }
    }

    /** 历史候选的一行编码：每条 = 值 + 空格 + 名字（编码后两者都不含空白），条间换行。 */
    fun encodeHistory(entries: List<RailHistoryEntry>): String =
        entries.joinToString("\n") { entry ->
            URLEncoder.encode(entry.value, "UTF-8") + " " + URLEncoder.encode(entry.label, "UTF-8")
        }

    /** [encodeHistory] 的逆：坏行跳过；名字缺失时留空串（显示端兜底）。 */
    fun decodeHistory(encoded: String): List<RailHistoryEntry> {
        if (encoded.isEmpty()) return emptyList()
        return encoded.split('\n').mapNotNull { line ->
            if (line.isEmpty()) return@mapNotNull null
            val sep = line.indexOf(' ')
            if (sep <= 0) return@mapNotNull null
            val value = runCatching { URLDecoder.decode(line.substring(0, sep), "UTF-8") }.getOrNull()
                ?: return@mapNotNull null
            val label = if (sep + 1 >= line.length) {
                ""
            } else {
                runCatching { URLDecoder.decode(line.substring(sep + 1), "UTF-8") }.getOrDefault("")
            }
            RailHistoryEntry(value, label)
        }
    }
}
