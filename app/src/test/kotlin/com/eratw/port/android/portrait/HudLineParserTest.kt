package com.eratw.port.android.portrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HudLineParser 的口径钉：**样例行逐字取自设计稿附录 A**（2026-10-27 真机截图转写），
 * 期望值按附录 A 的行注与三条解析规则 / 角色块切分 5 规则手推，禁止拿实现输出当期望。
 */
class HudLineParserTest {

    private fun parse(vararg lines: String): HudSnapshot = HudLineParser.parseHud(lines.toList())

    // ------------------------------------------------------------------
    // A1 起床菜单页（G0）
    // ------------------------------------------------------------------

    /** 附录 A A1 原文（设计稿 §474-480 行）。 */
    private val a1 = listOf(
        "第47日(五)  所持金: ¥5,177,463,124 （筹码：664CM）",
        "体力 ▁▁▁▁ ( 3249/ 3845)  气力 ▁▁▁▁ ( 4245/ 4545)  法力 ▁▁▁ ( 3052/ 3052)",
        "TSP ▁▁▁▁ ( 1645/ 1645)   精力 ▁ ( 120/ 2420)",
        "[自宅位置]：命莲寺 - 别馆   [起床时间]：9时00分  [日历]",
        "温馨提示:侍丧神的情欲极限上升、…恢复正常生育能力。",
        "【主菜单】",
        "【系统菜单】",
    )

    @Test
    fun `A1 菜单页 日期所持金筹码地点时间逐字钉死`() {
        val snap = parse(*a1.toTypedArray())
        // 行 1：日期段剥掉所持金段；千分位逗号剥掉；筹码取 CM 前整数
        assertEquals("第47日(五)", snap.dateText)
        assertEquals(5_177_463_124L, snap.money)
        assertEquals(664L, snap.chips)
        // 行 4：地点到连续空白截断、时间同理
        assertEquals("命莲寺 - 别馆", snap.place)
        assertEquals("9时00分", snap.time)
    }

    @Test
    fun `A1 菜单页 数值行分母式逐格钉死`() {
        val snap = parse(*a1.toTypedArray())
        // 行 2/3：分母式 a/ b 取双值；一行多组（体力/气力/法力 同行）全扫出
        assertEquals(BarValue(3249, 3845), snap.bars["体力"])
        assertEquals(BarValue(4245, 4545), snap.bars["气力"])
        assertEquals(BarValue(3052, 3052), snap.bars["法力"])
        assertEquals(BarValue(1645, 1645), snap.bars["TSP"])
        assertEquals(BarValue(120, 2420), snap.bars["精力"])
        assertEquals(5, snap.bars.size)
    }

    @Test
    fun `A1 菜单页 组标签与空角色`() {
        val snap = parse(*a1.toTypedArray())
        assertEquals(listOf("主菜单", "系统菜单"), snap.groupLabels)
        // 菜单页无角色块、无经验行、无执行中（提示行不进 HUD）
        assertEquals(0, snap.charCount)
        assertNull(snap.exps)
        assertNull(snap.executing)
        assertNull(snap.lastCommand)
        assertNull(snap.fortune)
        assertNull(snap.protagonist)
        assertTrue(snap.buffChips.isEmpty())
    }

    // ------------------------------------------------------------------
    // A2 探索页（G1）
    // ------------------------------------------------------------------

    /** 附录 A A2 原文（设计稿 §486-492 行）。 */
    private val a2 = listOf(
        "夏之月 17日 (六)9时00分 (已时一点-小昼) — Ｃ敏感 —○ 气温20.0℃ <可吃饭>",
        "法力 ( 3052/ 3052)  TSP ( 1645/ 1645) TSLv: 0",
        "[调和魔药] [时间停止可] [使用魔法] [常识改变变动可]",
        "昂: [今日运势: 171>]   执行中: [带着零感的水]",
        "清醒裾裾 1000   体 3845  气 4545  法 3052  酒 0  精 2420",
        "▼--[Look]---- [显示立绘] [显示设定] [显示自机立绘]",
        "<上回指令:>",
    )

    @Test
    fun `A2 探索页 buff 行整行 chips`() {
        val snap = parse(*a2.toTypedArray())
        assertEquals(listOf("调和魔药", "时间停止可", "使用魔法", "常识改变变动可"), snap.buffChips)
    }

    @Test
    fun `A2 探索页 运势与执行中`() {
        val snap = parse(*a2.toTypedArray())
        // 行 4：[今日运势: 171>] 的 `>` 不吃进数字；执行中取方括号内容、无主语
        assertEquals(171, snap.fortune)
        assertEquals(ExecutingInfo(subject = null, text = "带着零感的水"), snap.executing)
    }

    @Test
    fun `A2 探索页 清醒裾裾 Status 行与短标签口径`() {
        val snap = parse(*a2.toTypedArray())
        // 行 5：清醒裾裾 在固定标签集合内 → 值 1000；酒/精 是单字标签 → 认；
        // 体/气/法 单字**不在**集合里（集合是 体力/气力/法力）→ 按规格不认，不失配不编造
        assertEquals(BarValue(1000, null), snap.bars["清醒裾裾"])
        assertEquals(BarValue(0, null), snap.bars["酒"])
        assertEquals(BarValue(2420, null), snap.bars["精"])
        assertNull(snap.bars["体力"])
        assertNull(snap.bars["气力"])
        // 行 2：TSLv 不是标签（集合里只有 TSP），其 0 不冒充任何字段
        assertEquals(BarValue(3052, 3052), snap.bars["法力"])
        assertEquals(BarValue(1645, 1645), snap.bars["TSP"])
    }

    @Test
    fun `A2 探索页 上回指令空值也要识别`() {
        val snap = parse(*a2.toTypedArray())
        // 行 7：<上回指令:> → 空串（非 null）；行 1：A2 形日期行 = 整行原文
        assertEquals("", snap.lastCommand)
        assertEquals("夏之月 17日 (六)9时00分 (已时一点-小昼) — Ｃ敏感 —○ 气温20.0℃ <可吃饭>", snap.dateText)
        assertNull(snap.money)
        assertNull(snap.chips)
        assertEquals(0, snap.charCount)
        assertNull(snap.protagonist)
    }

    // ------------------------------------------------------------------
    // A3 角色交互页（G2）
    // ------------------------------------------------------------------

    /** 附录 A A3 原文（设计稿 §496-502 行）。 */
    private val a3 = listOf(
        "夏之日 17日(六) 9时40分（已时二点-小昼）— ○ Ｃ敏感 —○ 气温20.0℃ <可吃饭>",
        "体力 ▁▁▁ 3815 气 ▁▁▁ 4213 法 ▁▁▁ 3052 酒 ▁ 0 精 ▁▁ 2420",
        "好感度: Ex 113791  信赖度: S 3330  欲求不满度: 87%  孩子个数: 0  怀孕: ×",
        "工作情报  工作地点: ——  战斗能力: D 1",
        "(=主人公)清  Erection!!  体 1818 气 1224 法 0 酒 0 精 0",
        "执行中:「带着零感的水」",
        "<上回指令:任凭摆布>",
    )

    @Test
    fun `A3 交互页 经验行逐字段钉死`() {
        val snap = parse(*a3.toTypedArray())
        // 行 3：Ex/S 段位字母跳过取整数；87% 去 %；怀孕取符号
        val exps = snap.exps
        assertEquals(113_791, exps?.favor)
        assertEquals(3330, exps?.trust)
        assertEquals(87, exps?.frustration)
        assertEquals(0, exps?.children)
        assertEquals("×", exps?.pregnancy)
        assertNull(exps?.conception)
    }

    @Test
    fun `A3 交互页 数值行无分母式`() {
        val snap = parse(*a3.toTypedArray())
        // 行 2：单值形态（无 a/ b）→ max=null；气/法 单字不在集合 → 不认
        assertEquals(BarValue(3815, null), snap.bars["体力"])
        assertEquals(BarValue(0, null), snap.bars["酒"])
        assertEquals(BarValue(2420, null), snap.bars["精"])
        assertNull(snap.bars["气力"])
        assertNull(snap.bars["法力"])
    }

    @Test
    fun `A3 交互页 主角行与工作行`() {
        val snap = parse(*a3.toTypedArray())
        // 行 5：(=主人公) 前缀给出主角名「清」；行内 token 开头的 酒/精 是真实数值，并入主角 stats
        val protagonist = snap.protagonist
        assertEquals("清", protagonist?.name)
        assertEquals("清", protagonist?.markerLabel)
        assertEquals(BarValue(0, null), protagonist?.stats?.get("酒"))
        assertEquals(BarValue(0, null), protagonist?.stats?.get("精"))
        // 行 4：工作地点值是文本「——」；战斗能力 = 段位 D + 整数 1
        assertEquals("——", snap.work?.place)
        assertEquals("D", snap.work?.battleRank)
        assertEquals(1, snap.work?.battlePower)
        assertEquals(0, snap.charCount)
    }

    @Test
    fun `A3 交互页 执行中与上回指令`() {
        val snap = parse(*a3.toTypedArray())
        // 行 6：全角冒号紧贴直角引号；行 7：有值
        assertEquals(ExecutingInfo(subject = null, text = "带着零感的水"), snap.executing)
        assertEquals("任凭摆布", snap.lastCommand)
    }

    // ------------------------------------------------------------------
    // A4 多角色交互页（G3）
    // ------------------------------------------------------------------

    /** 附录 A A4 原文（设计稿 §508-523 行）。 */
    private val a4 = listOf(
        "夏之月 17日(六)9时15分 (已时一点-小昼) — Ｃ敏感 —○ 气温20.0℃ <可吃饭>",
        "绀野琳私室  清洁度:中",
        "[白莲] [同行中]  [一轮]",
        "[白莲] 好感度: S 29201  信赖度: A 2377  欲求不满度: 100%  孩子个数: 0  受孕: ○",
        "工作情报  劝诱活动 周一、四 12～16时  女子会 …  战斗能力: A 4",
        "昂  [体力] 3845  气 4495  法 3052  酒 0  精 2420",
        "(主角)清醒裾裾 1020",
        "白莲  [体力] 2666  气 2370  法 754  酒 0",
        "情绪:  理性: ★★★★  危险日前",
        "昂: [今日运势: 712]",
        "执行中 (昂) [带着雾之湖的水]",
        "▼--[Palam]-----  （只列当前对象：白莲）",
        "▼--[Look] ----- [显示立绘][显示设定][显示自机立绘]",
        "【上半身】哥特泰丽塔裙 / 【下半身】哥特洛丽塔裙 / 【★ 789】秩序的破子",
        "[白莲]",
        "[一轮]",
    )

    @Test
    fun `A4 角色块切分 白莲块三行名字一致成块`() {
        val snap = parse(*a4.toTypedArray())
        // 切分规则 2：在场行 [白莲] + 经验行 [白莲] 好感度… + 数值行 白莲 [体力]… → 一个合格块
        assertEquals(1, snap.charCount)
        val block = snap.blocks.single()
        assertEquals("白莲", block.name)
        assertEquals(29_201, block.exps.favor)
        assertEquals(2377, block.exps.trust)
        assertEquals(100, block.exps.frustration)
        assertEquals(0, block.exps.children)
        assertEquals("○", block.exps.conception)
        assertNull(block.exps.pregnancy)
        // 数值行：体力来自 [体力]；酒/精在集合内；气/法单字不在集合
        assertEquals(BarValue(2666, null), block.stats["体力"])
        assertEquals(BarValue(0, null), block.stats["酒"])
        assertEquals(setOf("体力", "酒"), block.stats.keys)
        // 切分规则 5：数值行下一行状态文本含 危险日前 → 状态点
        assertTrue(block.dangerDay)
    }

    @Test
    fun `A4 主角昂不计入角色条`() {
        val snap = parse(*a4.toTypedArray())
        // 切分规则 3：数值行名字（昂）不在在场名单 → 主角块；主角不计入 blocks
        val protagonist = snap.protagonist
        assertEquals("昂", protagonist?.name)
        assertEquals("清醒裾裾", protagonist?.markerLabel)
        assertEquals(BarValue(3845, null), protagonist?.stats?.get("体力"))
        assertEquals(BarValue(0, null), protagonist?.stats?.get("酒"))
        assertEquals(BarValue(2420, null), protagonist?.stats?.get("精"))
        assertTrue(snap.blocks.none { it.name == "昂" })
        // 一轮在场但缺经验行/数值行 → 按规则 2 该块不计入
        assertTrue(snap.blocks.none { it.name == "一轮" })
    }

    @Test
    fun `A4 当前对象判定与执行中主语`() {
        val snap = parse(*a4.toTypedArray())
        // 当前对象 = 首个合格块（游戏把当前对象的经验行列在最前，附录 A A4 行注）
        assertEquals("白莲", snap.currentTarget)
        // 行 11：执行中带主语 (昂)
        assertEquals(ExecutingInfo(subject = "昂", text = "带着雾之湖的水"), snap.executing)
        assertEquals(712, snap.fortune)
        // 行 5：本屏工作行无 工作地点
        assertNull(snap.work?.place)
        assertEquals("A", snap.work?.battleRank)
        assertEquals(4, snap.work?.battlePower)
        assertNull(snap.lastCommand)
        // 行 14：装备行虽以【开头但不是整行【…】 → 不是组标签
        assertTrue(snap.groupLabels.isEmpty())
    }

    // ------------------------------------------------------------------
    // 负样例：任何一条不成立 → 字段 null（附录 A 规则 3，零编造、绝不 0 兜底）
    // ------------------------------------------------------------------

    @Test
    fun `缺标签的叙述行全字段 null`() {
        val snap = parse("什么都有的普通叙述文本一行")
        assertNull(snap.dateText)
        assertNull(snap.money)
        assertNull(snap.place)
        assertTrue(snap.bars.isEmpty())
        assertNull(snap.exps)
        assertNull(snap.executing)
        assertNull(snap.lastCommand)
        assertNull(snap.fortune)
        assertNull(snap.protagonist)
        assertEquals(0, snap.charCount)
        assertTrue(snap.groupLabels.isEmpty())
    }

    @Test
    fun `全角数字不是数值`() {
        // 体力行成立但值是全角 → 无值可取 → 该字段不出现在 bars
        val snap = parse("体力 ▁▁▁ ３８４５")
        assertNull(snap.bars["体力"])
        // 经验行同理
        val snap2 = parse("好感度: １１３７９１")
        assertNull(snap2.exps?.favor)
    }

    @Test
    fun `标签不在行首不锚定`() {
        // 叙述文本里出现「体力」字样：行型锚定失败 → 该行不产出任何字段
        val snap = parse("这里的体力很重要 3845")
        assertNull(snap.bars["体力"])
        assertTrue(snap.bars.isEmpty())
        val snap2 = parse("他说好感度很高 113791")
        assertNull(snap2.exps)
    }

    @Test
    fun `标签后无数字为 null`() {
        val snap = parse("好感度: ——  信赖度:  ")
        // 行型成立（行首好感度）→ 经验行存在，但 favor/trust 均剥不出值 → null（不 0 兜底）
        assertEquals(null, snap.exps?.favor)
        assertEquals(null, snap.exps?.trust)
    }

    @Test
    fun `执行中后无引号内容为 null`() {
        assertNull(parse("执行中:").executing)
        assertNull(parse("执行中: 普通文本收尾").executing)
        assertNull(parse("执行中:「未闭合的直角引号").executing)
    }

    @Test
    fun `多空格变体仍按 token 规则成立`() {
        // 「锚行首标签、不锚列位」：空白量不参与判定 → 值照常剥出
        val snap = parse("体力  ▁▁▁  (  3249/  3845)")
        assertEquals(BarValue(3249, 3845), snap.bars["体力"])
    }

    // ------------------------------------------------------------------
    // 组标签剥取（手写 indexOf + endsWith，禁正则；禁白名单）
    // ------------------------------------------------------------------

    @Test
    fun `组标签整行命中才剥`() {
        assertEquals("主菜单", HudLineParser.parseGroupLabel("【主菜单】"))
        assertEquals("系统菜单", HudLineParser.parseGroupLabel("【系统菜单】"))
        assertEquals(null, HudLineParser.parseGroupLabel("普通行【不剥】"))
        assertEquals(null, HudLineParser.parseGroupLabel("【上半身】哥特泰丽塔裙 / 【下半身】哥特洛丽塔裙"))
        assertEquals(null, HudLineParser.parseGroupLabel("【未闭合"))
        assertEquals(null, HudLineParser.parseGroupLabel("温馨提示:侍丧神的情欲极限上升"))
    }

    // ------------------------------------------------------------------
    // 逐行函数的边界（供 P2 在指令盘分组等处直接复用）
    // ------------------------------------------------------------------

    @Test
    fun `在场行按切分规则1剥名字`() {
        // ≥2 个非状态词 token → 名单（状态词 同行中 排除）
        assertEquals(listOf("白莲", "一轮"), HudLineParser.parsePresentNames("[白莲] [同行中]  [一轮]"))
        // 状态词占位多时名单不足 2 → 不成立
        assertEquals(null, HudLineParser.parsePresentNames("[白莲] [战斗不能]"))
        // 规则按字面成立：A2 的 buff 行同样满足 ≥2 非状态词 token —— 这些假名字组不出
        // 经验行 + 数值行，切分规则 2 全部挡掉（HudSnapshot.blocks 不受污染，见 A2 用例）
        assertEquals(
            listOf("调和魔药", "时间停止可", "使用魔法", "常识改变变动可"),
            HudLineParser.parsePresentNames("[调和魔药] [时间停止可] [使用魔法] [常识改变变动可]"),
        )
    }

    @Test
    fun `buff 行与标签值行的分界`() {
        assertEquals(
            listOf("调和魔药", "时间停止可", "使用魔法", "常识改变变动可"),
            HudLineParser.parseBuffChips("[调和魔药] [时间停止可] [使用魔法] [常识改变变动可]"),
        )
        // 夹着取值文本（[自宅位置]：…）不是纯 chip 行
        assertEquals(null, HudLineParser.parseBuffChips("[自宅位置]：命莲寺 - 别馆   [起床时间]：9时00分  [日历]"))
        assertEquals(null, HudLineParser.parseBuffChips("昂: [今日运势: 171>]   执行中: [带着零感的水]"))
    }

    @Test
    fun `日期行两形与失配`() {
        // A2~A4 形：日 + N时M分 token
        assertEquals(
            "夏之月 17日 (六)9时00分 (已时一点-小昼) — Ｃ敏感 —○ 气温20.0℃ <可吃饭>",
            HudLineParser.parseDateLine("夏之月 17日 (六)9时00分 (已时一点-小昼) — Ｃ敏感 —○ 气温20.0℃ <可吃饭>")?.dateText,
        )
        // 有时分 token 但没有 日 → 不是日期行（[起床时间] 的值不冒充日期）
        assertEquals(null, HudLineParser.parseDateLine("[起床时间]：9时00分"))
        // A1 地点行：[日历] 的「日」字 + [起床时间] 的时分值凑齐两条件也不行（行内有方括号 token）
        assertEquals(
            null,
            HudLineParser.parseDateLine("[自宅位置]：命莲寺 - 别馆   [起床时间]：9时00分  [日历]"),
        )
        assertEquals(null, HudLineParser.parseDateLine("绀野琳私室  清洁度:中"))
    }

    @Test
    fun `上回指令残形不成立`() {
        assertEquals(null, HudLineParser.parseLastCommand("<上回指令:未闭合"))
        assertEquals(null, HudLineParser.parseLastCommand("上回指令:没有包裹"))
        assertEquals("任凭摆布", HudLineParser.parseLastCommand("<上回指令:任凭摆布>"))
        assertEquals("", HudLineParser.parseLastCommand("<上回指令:>"))
    }

    @Test
    fun `主角标记行两形`() {
        assertEquals("清", HudLineParser.parseProtagonistMarker("(=主人公)清  Erection!!  体 1818"))
        assertEquals("清醒裾裾", HudLineParser.parseProtagonistMarker("(主角)清醒裾裾 1020"))
        assertEquals(null, HudLineParser.parseProtagonistMarker("白莲  [体力] 2666"))
    }

    @Test
    fun `角色数值行的名字取括号前裸 token`() {
        val row = HudLineParser.parseStatRow("白莲  [体力] 2666  气 2370  法 754  酒 0")
        assertEquals("白莲", row?.name)
        assertEquals(BarValue(2666, null), row?.stats?.get("体力"))
        assertEquals(BarValue(0, null), row?.stats?.get("酒"))
        // [体力] 后没有任何整数 → 切分规则 2 的数值行不成立（null，不 0 兜底）
        assertEquals(null, HudLineParser.parseStatRow("白莲  [体力] 气力不足"))
        // 方括号 token 开头的行不是裸名字数值行
        assertEquals(null, HudLineParser.parseStatRow("[白莲] 好感度: S 29201"))
    }

    @Test
    fun `今日运势的截断字符不吃进数字`() {
        assertEquals(171, HudLineParser.parseFortune("昂: [今日运势: 171>]   执行中: [带着零感的水]"))
        assertEquals(712, HudLineParser.parseFortune("昂: [今日运势: 712]"))
        assertEquals(null, HudLineParser.parseFortune("昂: [今日运势: 无数字]"))
        assertEquals(null, HudLineParser.parseFortune("昂: [别的标签: 171]"))
    }
}
