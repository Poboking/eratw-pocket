package com.eratw.port.android

import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.OptionOrigin
import com.eratw.port.core.host.OptionVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 右栏「自定义过滤 + 常用置顶」纯规则层的契约（判据复用件见 [RailOptionRules] 类文档）。
 *
 * 钉的四块：
 * 1. 过滤 = 标签黑名单（精确匹配）与「隐藏无编号」规则叠加，调色板是唯一豁免；
 * 2. 两条置顶判据分开：长按菜单收全部非色块（无编号按钮也能钉），历史候选只收带编号指令；
 * 3. 置顶/历史的集合代数（LRU、去重保最新名、上限淘汰）；
 * 4. **显示硬规则**：常用项只在当前屏上存在同值按钮时才渲染（玩家明定的口径）。
 * 编解码另有一组：坏段/坏行跳过不抛（偏好读端不许炸）。
 */
class RailOptionRulesTest {

    // ------------------------------------------------------------------
    // 工厂：normal = 普通按钮；numbered = 引擎判定屏上自带编号（[100] - 睁开眼睛 族）；
    // tagged = 编号印在标签末尾（移动[400] 族）；swatch = 调色板色块。
    // ------------------------------------------------------------------

    private fun normal(
        value: Long,
        label: String,
        serial: Long = 10L,
        printsOwnNumber: Boolean = false,
        visual: OptionVisual = OptionVisual.TEXT,
    ): ConsoleOption = ConsoleOption(
        value = value,
        label = label,
        origin = OptionOrigin.EXPLICIT,
        lineSerial = serial,
        startCol = 0,
        endCol = 2,
        printsOwnNumber = printsOwnNumber,
        visual = visual,
    )

    private fun swatchRun(count: Int, serial: Long = 10L, startValue: Long = 80000L): List<ConsoleOption> =
        (0 until count).map { i ->
            ConsoleOption(
                value = startValue + i,
                label = "■",
                origin = OptionOrigin.EXPLICIT,
                lineSerial = serial,
                startCol = i * 2,
                endCol = i * 2 + 2,
                visual = OptionVisual.COLOR_SWATCH,
            )
        }

    // ------------------------------------------------------------------
    // 1. 自定义过滤
    // ------------------------------------------------------------------

    @Test
    fun `黑名单按标签精确命中并计数`() {
        val options = listOf(
            normal(300L, "会话"),
            normal(3105L, "立绘种类切换"),
            normal(400L, "移动[400]"),
        )
        val result = RailOptionRules.filterOptions(options, setOf("立绘种类切换"), hideUnnumbered = false)
        assertEquals(listOf("会话", "移动[400]"), result.options.map { it.label })
        assertEquals(1, result.hiddenCount)
    }

    @Test
    fun `无输入或全关时原样返回`() {
        val options = listOf(normal(1L, "A"), normal(2L, "B"))
        val idle = RailOptionRules.filterOptions(options, emptySet(), hideUnnumbered = false)
        assertEquals(options, idle.options)
        assertEquals(0, idle.hiddenCount)
        val same = RailOptionRules.filterOptions(options, setOf("不存在"), hideUnnumbered = false)
        assertEquals(options, same.options)
    }

    @Test
    fun `隐藏无编号规则只留带编号指令`() {
        val options = listOf(
            normal(809L, "[CONFIG]"),
            normal(3002L, "▽[＋] Ex_COM"),
            normal(3105L, "立绘种类切换"),
            normal(400L, "移动[400]"),
            normal(100L, "睁开眼睛", printsOwnNumber = true),
        )
        val result = RailOptionRules.filterOptions(options, emptySet(), hideUnnumbered = true)
        assertEquals(listOf("移动[400]", "睁开眼睛"), result.options.map { it.label })
        assertEquals(3, result.hiddenCount)
    }

    @Test
    fun `真调色板是唯一豁免 单个色块不豁免`() {
        // 连续 8 枚以上：即使无编号规则开着也全保留。
        val palette = swatchRun(8)
        val paletteResult = RailOptionRules.filterOptions(palette, emptySet(), hideUnnumbered = true)
        assertEquals(palette, paletteResult.options)
        assertEquals(0, paletteResult.hiddenCount)
        // 单个彩色字形只是普通无编号按钮：照规则藏掉。
        val lone = listOf(normal(1L, "■", visual = OptionVisual.COLOR_SWATCH))
        val loneResult = RailOptionRules.filterOptions(lone, emptySet(), hideUnnumbered = true)
        assertEquals(0, loneResult.options.size)
        assertEquals(1, loneResult.hiddenCount)
    }

    @Test
    fun `黑名单与规则叠加生效`() {
        val options = listOf(
            normal(809L, "[CONFIG]"),
            normal(400L, "移动[400]"),
        )
        val result = RailOptionRules.filterOptions(options, setOf("移动[400]"), hideUnnumbered = true)
        assertEquals(0, result.options.size)
        assertEquals(2, result.hiddenCount)
    }

    // ------------------------------------------------------------------
    // 2. 可置顶判据
    // ------------------------------------------------------------------

    @Test
    fun `可置顶判据与过滤同源`() {
        val chips = listOf(
            normal(100L, "睁开眼睛", printsOwnNumber = true),
            normal(400L, "移动[400]"),
            normal(3105L, "立绘种类切换"),
            normal(31L, "[31]"),
            normal(1L, ""),
        ) + swatchRun(8)
        val mask = RailOptionRules.pinnableMaskOf(chips)
        assertEquals(listOf(true, true, false, false, false) + List(8) { false }, mask.toList())
    }

    @Test
    fun `长按菜单置顶收全部非色块 无编号按钮也能钉`() {
        // 玩家反馈：长按 [CONFIG] / 地图切换 只有「从右栏隐藏」一项，像置顶入口被挤掉了。
        // 菜单判据 = 非色块，和上面历史候选的「带编号」判据不是同一条。
        val chips = listOf(
            normal(100L, "睁开眼睛", printsOwnNumber = true),
            normal(400L, "移动[400]"),
            normal(3105L, "立绘种类切换"),
            normal(809L, "[CONFIG]"),
            normal(3002L, "▽[＋] Ex_COM"),
            normal(1L, ""),
            normal(3L, "■", visual = OptionVisual.COLOR_SWATCH),
        ) + swatchRun(8)
        val mask = RailOptionRules.pinMenuMaskOf(chips)
        assertEquals(listOf(true, true, true, true, true, true, false) + List(8) { false }, mask.toList())
    }

    @Test
    fun `置顶时补记的历史条目按值去重并吃上限`() {
        val cap = 2
        var history = RailOptionRules.mergeHistoryEntry(
            emptyList(),
            RailOptionRules.RailHistoryEntry("809", "[CONFIG]"),
            cap,
        )
        history = RailOptionRules.mergeHistoryEntry(
            history,
            RailOptionRules.RailHistoryEntry("考", "地图切换"),
            cap,
        )
        assertEquals(
            listOf(
                RailOptionRules.RailHistoryEntry("809", "[CONFIG]"),
                RailOptionRules.RailHistoryEntry("考", "地图切换"),
            ),
            history,
        )
        // 同值重记：挪到最新并换名。
        history = RailOptionRules.mergeHistoryEntry(
            history,
            RailOptionRules.RailHistoryEntry("809", "[CONFIG]2"),
            cap,
        )
        assertEquals(
            listOf(
                RailOptionRules.RailHistoryEntry("考", "地图切换"),
                RailOptionRules.RailHistoryEntry("809", "[CONFIG]2"),
            ),
            history,
        )
        // 空值/空上限是空操作。
        assertEquals(
            history,
            RailOptionRules.mergeHistoryEntry(history, RailOptionRules.RailHistoryEntry("", "x"), cap),
        )
        assertEquals(
            history,
            RailOptionRules.mergeHistoryEntry(history, RailOptionRules.RailHistoryEntry("1", "x"), 0),
        )
    }

    // ------------------------------------------------------------------
    // 3. 置顶/历史代数
    // ------------------------------------------------------------------

    @Test
    fun `置顶LRU 已存在挪到最新 超上限淘汰最早`() {
        val cap = 2
        var pinned = RailOptionRules.pinTo(emptyList(), "400", cap)
        pinned = RailOptionRules.pinTo(pinned, "100", cap)
        assertEquals(listOf("400", "100"), pinned)
        // 已存在：挪到最新。
        pinned = RailOptionRules.pinTo(pinned, "400", cap)
        assertEquals(listOf("100", "400"), pinned)
        // 超上限（3 > 2）：最旧的 100 被淘汰。
        pinned = RailOptionRules.pinTo(pinned, "999", cap)
        assertEquals(listOf("400", "999"), pinned)
        // 空值是空操作。
        assertEquals(pinned, RailOptionRules.pinTo(pinned, "", cap))
    }

    @Test
    fun `取消置顶不在表里是空操作`() {
        val pinned = listOf("400", "100")
        assertEquals(listOf("400"), RailOptionRules.unpinFrom(pinned, "100"))
        assertEquals(pinned, RailOptionRules.unpinFrom(pinned, "999"))
    }

    @Test
    fun `常用区硬规则 屏上没有同值按钮就整条不出现`() {
        val chips = listOf(
            normal(400L, "移动[400]"),
            normal(100L, "睁开眼睛", printsOwnNumber = true),
        )
        val visible = RailOptionRules.visiblePinnedOf(listOf("400", "999", "100"), chips)
        // 999 屏上没有：不渲染、不占位；顺序跟随置顶列表；标签用当前屏实时文案。
        assertEquals(listOf("移动[400]", "睁开眼睛"), visible.map { it.label })
        assertEquals(emptyList<ConsoleOption>(), RailOptionRules.visiblePinnedOf(listOf("999"), chips))
        assertEquals(emptyList<ConsoleOption>(), RailOptionRules.visiblePinnedOf(listOf("400"), emptyList()))
    }

    @Test
    fun `历史候选按值去重保最新名并吃上限`() {
        val cap = 3
        var history = RailOptionRules.mergeHistory(emptyList(), listOf(normal(400L, "移动[400]")), cap)
        history = RailOptionRules.mergeHistory(history, listOf(normal(400L, "移动(改)[400]")), cap)
        assertEquals(listOf(RailOptionRules.RailHistoryEntry("400", "移动(改)[400]")), history)
        history = RailOptionRules.mergeHistory(
            history,
            listOf(
                normal(100L, "睁开眼睛", printsOwnNumber = true),
                normal(3105L, "立绘种类切换"),
                normal(999L, "踏上归途[999]"),
                normal(998L, "超出上限[998]"),
            ),
            cap,
        )
        // 立绘开关（无编号）不入历史；超上限从最旧一端淘汰（最早并入的 400 出局）。
        assertEquals(
            listOf(
                RailOptionRules.RailHistoryEntry("100", "睁开眼睛"),
                RailOptionRules.RailHistoryEntry("999", "踏上归途[999]"),
                RailOptionRules.RailHistoryEntry("998", "超出上限[998]"),
            ),
            history,
        )
        // 空输入原样返回现有历史（合约：空 options = 空操作）。
        assertEquals(history, RailOptionRules.mergeHistory(history, emptyList(), cap))
    }

    // ------------------------------------------------------------------
    // 4. 编解码
    // ------------------------------------------------------------------

    @Test
    fun `字符串列表编解码往返 特殊字符安全`() {
        val values = listOf("400", "移动[400] 移动", "a|b", "line\nbreak", "100%", "＋＋", "清%(まが)す")
        val round = RailOptionRules.decodeStringList(RailOptionRules.encodeStringList(values))
        assertEquals(values, round)
    }

    @Test
    fun `字符串列表解码坏段跳过不抛`() {
        val round = RailOptionRules.encodeStringList(listOf("400", "100"))
        val tampered = round + "|%ZZ" // 手工改坏的段：解码失败要被跳过
        assertEquals(listOf("400", "100"), RailOptionRules.decodeStringList(tampered))
        assertEquals(emptyList<String>(), RailOptionRules.decodeStringList(""))
        assertEquals(emptyList<String>(), RailOptionRules.decodeStringList("|"))
    }

    @Test
    fun `历史编解码往返 名字可空`() {
        val entries = listOf(
            RailOptionRules.RailHistoryEntry("400", "移动[400]"),
            RailOptionRules.RailHistoryEntry("100", "睁 开 眼睛"),
            RailOptionRules.RailHistoryEntry("999", ""),
        )
        val round = RailOptionRules.decodeHistory(RailOptionRules.encodeHistory(entries))
        assertEquals(entries, round)
    }

    @Test
    fun `历史解码坏行跳过不抛`() {
        val good = RailOptionRules.encodeHistory(listOf(RailOptionRules.RailHistoryEntry("400", "移动[400]")))
        val tampered = listOf("garbage-line", "", "%ZZ x", good).joinToString("\n")
        assertEquals(listOf(RailOptionRules.RailHistoryEntry("400", "移动[400]")), RailOptionRules.decodeHistory(tampered))
        assertEquals(emptyList<RailOptionRules.RailHistoryEntry>(), RailOptionRules.decodeHistory(""))
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------
    // 快捷宏（2026-11-11 重做）：玩家**拼**出来的连发宏 —— 步骤 × 单步次数 + 整组重复。
    // 关键契约：
    // 1. 按钮文案由步骤自动生成（玩家不起名）；
    // 2. 提交串是 **era 原生语法**（壳层只拼语法，重复由引擎 parseInput 展开）；
    // 3. 满了不静默淘汰、空步骤不收、坏偏好行跳过不炸。
    // ------------------------------------------------------------------

    private fun step(value: String, label: String = "", times: Int = 1) =
        RailOptionRules.MacroStep(value, label, times)

    @Test
    fun `宏文案自动生成 覆盖单步连发 组合 与整组重复`() {
        assertEquals("会话×5", RailOptionRules.macroLabel(RailOptionRules.QuickMacro(listOf(step("31", "会话", 5)))))
        assertEquals(
            "会话→道具",
            RailOptionRules.macroLabel(RailOptionRules.QuickMacro(listOf(step("31", "会话"), step("32", "道具")))),
        )
        assertEquals(
            "整组重复用空格区分单步次数（×t 贴名字、×R 在末尾）",
            "会话→道具 ×3",
            RailOptionRules.macroLabel(
                RailOptionRules.QuickMacro(listOf(step("31", "会话"), step("32", "道具")), repeat = 3),
            ),
        )
        // 没名字时退回提交值。
        assertEquals("31×2", RailOptionRules.macroLabel(RailOptionRules.QuickMacro(listOf(step("31", times = 2)))))
        assertEquals("", RailOptionRules.macroLabel(RailOptionRules.QuickMacro(emptyList())))
    }

    @Test
    fun `提交串拼成 era 原生语法 重复交给引擎解析`() {
        // 最简单形态：不带括号（原版只有含 `(` 才走 parseInput），也不会多出末尾空回车。
        assertEquals("31\\e", RailOptionRules.macroContent(RailOptionRules.QuickMacro(listOf(step("31")))))
        // 单步连发 ⇒ (值\e\n)*t
        assertEquals(
            "(31\\e\\n)*5",
            RailOptionRules.macroContent(RailOptionRules.QuickMacro(listOf(step("31", times = 5)))),
        )
        // 先 a 后 b
        assertEquals(
            "31\\e\\n32\\e",
            RailOptionRules.macroContent(
                RailOptionRules.QuickMacro(listOf(step("31"), step("32"))),
            ),
        )
        // 组合重复 ⇒ 外面再套一层
        assertEquals(
            "(31\\e\\n32\\e\\n)*3",
            RailOptionRules.macroContent(
                RailOptionRules.QuickMacro(listOf(step("31"), step("32")), repeat = 3),
            ),
        )
        assertEquals("", RailOptionRules.macroContent(RailOptionRules.QuickMacro(listOf(step("  ")))))
    }

    @Test
    fun `段数读数等于 次数之和 乘 重复轮数`() {
        val macro = RailOptionRules.QuickMacro(
            listOf(step("31", times = 2), step("32", times = 3)),
            repeat = 4,
        )
        assertEquals((2 + 3) * 4, RailOptionRules.macroSegmentCount(macro))
    }

    @Test
    fun `规整 夹次数与重复 丢空步 裁到步数上限`() {
        val cleaned = RailOptionRules.sanitizeMacro(
            RailOptionRules.QuickMacro(
                steps = listOf(step("31", times = 0), step("  ", times = 9), step("32", times = 1000)),
                repeat = 1000,
            ),
        )!!
        assertEquals(listOf("31", "32"), cleaned.steps.map { it.value })
        assertEquals("0 次夹到 1", 1, cleaned.steps[0].times)
        assertEquals(RailOptionRules.MACRO_TIMES_MAX, cleaned.steps[1].times)
        // 整组重复的上限 = min(MACRO_REPEAT_MAX, MACRO_SEGMENT_CAP / 单轮段数)：
        // 这条宏单轮 = 1 + 99 = 100 段 ⇒ 600 / 100 = 6 轮（段数护栏从 2026-11-11 起真正生效，
        // 见下一条用例；此前 MACRO_SEGMENT_CAP 只是声明、没人用）。
        assertEquals(
            RailOptionRules.MACRO_SEGMENT_CAP / (1 + RailOptionRules.MACRO_TIMES_MAX),
            cleaned.repeat,
        )
        assertTrue(cleaned.repeat <= RailOptionRules.MACRO_REPEAT_MAX)
        assertEquals(null, RailOptionRules.sanitizeMacro(RailOptionRules.QuickMacro(listOf(step("")))))
        // 步数超限裁掉尾巴。
        val many = (1..10).map { step("$it") }
        assertEquals(
            RailOptionRules.MACRO_STEP_CAP,
            RailOptionRules.sanitizeMacro(RailOptionRules.QuickMacro(many))!!.steps.size,
        )
    }

    @Test
    fun `规整把总段数压到硬上限以内`() {
        // 2026-11-11 复查：MACRO_SEGMENT_CAP 以前只是声明、没人用 —— 4 步 × 99 连发 × 99 轮
        // = 39204 段可以存进去，一点就把输入队列灌爆（KDoc 自己写的就是这个后果）。
        val wild = RailOptionRules.QuickMacro(
            steps = (1..4).map { step("3$it", times = RailOptionRules.MACRO_TIMES_MAX) },
            repeat = RailOptionRules.MACRO_REPEAT_MAX,
        )
        val cleaned = RailOptionRules.sanitizeMacro(wild)!!
        assertTrue(
            "规整后段数必须 ≤ MACRO_SEGMENT_CAP（实际 ${RailOptionRules.macroSegmentCount(cleaned)}）",
            RailOptionRules.macroSegmentCount(cleaned) <= RailOptionRules.MACRO_SEGMENT_CAP,
        )
        assertTrue("步数与单步次数不该被改动", cleaned.steps.size == 4 && cleaned.steps.all { it.times == RailOptionRules.MACRO_TIMES_MAX })
        // 正常规模不受影响：2 步 × 2 次 × 3 轮 = 12 段，原样保留。
        val normal = RailOptionRules.QuickMacro(listOf(step("31", times = 2), step("32", times = 2)), repeat = 3)
        assertEquals(normal, RailOptionRules.sanitizeMacro(normal))
    }

    @Test
    fun `宏新增满上限返回 null 不淘汰已有`() {
        val cap = 2
        var list = emptyList<RailOptionRules.QuickMacro>()
        list = RailOptionRules.addQuickMacro(list, RailOptionRules.QuickMacro(listOf(step("31"))), cap)!!
        list = RailOptionRules.addQuickMacro(list, RailOptionRules.QuickMacro(listOf(step("32"))), cap)!!
        assertEquals(2, list.size)
        assertEquals(null, RailOptionRules.addQuickMacro(list, RailOptionRules.QuickMacro(listOf(step("33"))), cap))
        assertEquals(listOf("31", "32"), list.map { it.steps.first().value })
    }

    @Test
    fun `宏改与删 保顺序 越界不动`() {
        val list = listOf(
            RailOptionRules.QuickMacro(listOf(step("31"))),
            RailOptionRules.QuickMacro(listOf(step("32"))),
            RailOptionRules.QuickMacro(listOf(step("33"))),
        )
        val edited = RailOptionRules.updateQuickMacro(list, 1, RailOptionRules.QuickMacro(listOf(step("99"))))
        assertEquals(listOf("31", "99", "33"), edited.map { it.steps.first().value })
        assertEquals(list, RailOptionRules.updateQuickMacro(list, 3, RailOptionRules.QuickMacro(listOf(step("9")))))
        assertEquals(listOf("31", "33"), RailOptionRules.removeQuickMacro(list, 1).map { it.steps.first().value })
        assertEquals(list, RailOptionRules.removeQuickMacro(list, -1))
    }

    @Test
    fun `宏编解码往返 值里的冒号竖线换行都安全`() {
        val macros = listOf(
            RailOptionRules.QuickMacro(listOf(step("31", "会话", 5))),
            RailOptionRules.QuickMacro(
                listOf(step("a:b|c", "冒号竖线"), step("道具 10", "带空格")),
                repeat = 3,
            ),
            RailOptionRules.QuickMacro(listOf(step("%ZZ", ""))),
        )
        assertEquals(macros, RailOptionRules.decodeQuickMacros(RailOptionRules.encodeQuickMacros(macros)))
        assertEquals(emptyList<RailOptionRules.QuickMacro>(), RailOptionRules.decodeQuickMacros(""))
    }

    @Test
    fun `宏解码坏行跳过不抛 老格式自然判坏`() {
        val good = RailOptionRules.encodeQuickMacros(listOf(RailOptionRules.QuickMacro(listOf(step("31", "会话")))))
        // 老格式（名字 + 内容两字段，空格分隔）与垃圾行都该被丢弃，好的照读。
        val tampered = listOf("garbage", "", "%ZZ x", "名字 内容", good).joinToString("\n")
        assertEquals(
            "偏好读端不许炸：坏行跳过、好的照读",
            listOf(RailOptionRules.QuickMacro(listOf(step("31", "会话")))),
            RailOptionRules.decodeQuickMacros(tampered),
        )
    }
}