package com.eratw.port.android.portrait

import androidx.compose.ui.graphics.Color
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.OptionOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** §3.6.2 状态点两色的钉值（红 = 危险/受孕 ○，绿 = 常态）。 */
private val DOT_RED = Color(0xFFE86E7A)
private val DOT_GREEN = Color(0xFF7FAF7F)

/**
 * P2 壳接线的**装配方**口径钉（设计稿 §3.5 / §4.3 / §3.6.1 / §3.6.5）。全部纯 JVM：
 * 被测函数不 import 任何 android.*（Color 是 Compose 的纯 Kotlin 值类，P1 同款先例）。
 *
 * 钉的是四类：
 *   1. 盘开合/舞台显隐的形态配方（trayDefaultOpen / stageVisibleFor / trayMaxHeightFraction）；
 *   2. 组标签剥取与纵排分组（trayGroupLabelLines / trayGroupsOf——「永不发明分组」）；
 *   3. peek 取行（trayPeekOptions——第一屏行 ≤4 枚）；
 *   4. HUD 装配（hudBarsFor / hudDrawerChips / hudSubjectLabels / hudSubjectName /
 *      hudDrawerAvailable——附录 A 样例行喂 parseHud，失配即缺席、零编造零 0 兜底）。
 */
class PortraitShellLogicTest {

    // ------------------------------------------------------------------
    // 形态配方：盘开合 / 舞台显隐 / 高度上限档
    // ------------------------------------------------------------------

    @Test
    fun `G0 自动展开不受 command_tray_open 影响`() {
        // §3.5「G0 的自动展开不受它影响」：MENU 恒 true，偏好关也开。
        assertTrue(trayDefaultOpen(TrayForm.MENU, prefOpen = false))
        assertTrue(trayDefaultOpen(TrayForm.MENU, prefOpen = true))
    }

    @Test
    fun `G1-G2-G3 初始开合跟随手动偏好`() {
        // §4.3「G1/G2 的用户手动开合记忆到 UiPrefs.commandTrayOpen」：非 MENU 从偏好起。
        assertFalse(trayDefaultOpen(TrayForm.EXPLORE, prefOpen = false))
        assertTrue(trayDefaultOpen(TrayForm.EXPLORE, prefOpen = true))
        assertFalse(trayDefaultOpen(TrayForm.INTERACT, prefOpen = false))
        assertTrue(trayDefaultOpen(TrayForm.MULTI, prefOpen = true))
    }

    @Test
    fun `立绘舞台只在 G2-G3 出现`() {
        // §3.5 正文/立绘列：G0/G1 无立绘区；G2/G3 有（M1 = 标题条）。
        assertFalse(stageVisibleFor(TrayForm.MENU))
        assertFalse(stageVisibleFor(TrayForm.EXPLORE))
        assertTrue(stageVisibleFor(TrayForm.INTERACT))
        assertTrue(stageVisibleFor(TrayForm.MULTI))
    }

    @Test
    fun `展开高度上限 s 档 40 其余 46`() {
        // §4.3「.ep-tray max-height 46%（s 档 40%）」——s 档收一档保正文（§3.6.4）。
        assertEquals(0.40f, trayMaxHeightFraction(SizeClass.S), 1e-6f)
        assertEquals(0.46f, trayMaxHeightFraction(SizeClass.M), 1e-6f)
        assertEquals(0.46f, trayMaxHeightFraction(SizeClass.L), 1e-6f)
        assertEquals(0.46f, trayMaxHeightFraction(SizeClass.XL), 1e-6f)
    }

    // ------------------------------------------------------------------
    // 组标签与纵排分组（§3.5 组标签段、§4.3）
    // ------------------------------------------------------------------

    private fun option(label: String, serial: Long, value: Any = 0L): ConsoleOption =
        ConsoleOption(value = value, label = label, origin = OptionOrigin.EXPLICIT, lineSerial = serial)

    @Test
    fun `组标签只认贴近候选的屏行`() {
        // §3.5：组头服务当前候选——组头 serial 必然小于其组内首枚候选（组头行不带按钮），
        // 按「到下一候选的距离 ≤ TRAY_GROUP_LABEL_LOOKBACK」落实；远处的组头是上一屏残留。
        val chips = listOf(option("休息[403]", serial = 100), option("外出[405]", serial = 102))
        val tail = listOf(
            90L to "【上一屏组】", // 距首候选 10 行 > 8 → 不算
            99L to "【主菜单】", // 距首候选 1 行 → 认
            100L to "体力 3815", // 非组标签行 → parseGroupLabel null
            101L to "【系统菜单】", // 区间内 → 认
        )
        val labels = trayGroupLabelLines(tail, chips)
        assertEquals(listOf(99L to "主菜单", 101L to "系统菜单"), labels)
    }

    @Test
    fun `无候选时没有组标签`() {
        assertEquals(emptyList<Pair<Long, String>>(), trayGroupLabelLines(listOf(1L to "【主菜单】"), emptyList()))
    }

    @Test
    fun `纵排分组按标签切段且按钮按屏行原序`() {
        // §4.3「全部屏行分组纵排（含组标签行）」：标签变更切段；同标签多行并进同组。
        val chips = listOf(
            option("休息[403]", serial = 100),
            option("日记本[406]", serial = 100),
            option("扫除[410]", serial = 101),
            option("移动[400]", serial = 103),
            option("外出[405]", serial = 103),
        )
        val labels = listOf(99L to "主菜单", 102L to "系统菜单")
        val groups = trayGroupsOf(chips, labels)
        assertEquals(2, groups.size)
        assertEquals("主菜单", groups[0].label)
        // 同一屏行（serial=100）在 railRowsOf 里同组；相邻不同屏行（101）同标签 → 同段。
        assertEquals(listOf(listOf("休息[403]", "日记本[406]"), listOf("扫除[410]")), groups[0].rows.map { r -> r.map { it.label } })
        assertEquals("系统菜单", groups[1].label)
        assertEquals(listOf(listOf("移动[400]", "外出[405]")), groups[1].rows.map { r -> r.map { it.label } })
    }

    @Test
    fun `区间内剥不出标签时按钮平铺为无标签组`() {
        // §3.5 降级路径：组标签缺省 → 单一无标签组，按钮照常平铺（§10-9 不进白名单）。
        val chips = listOf(option("休息[403]", serial = 100))
        val groups = trayGroupsOf(chips, emptyList())
        assertEquals(1, groups.size)
        assertNull(groups[0].label)
        assertEquals(1, groups[0].rows.size)
    }

    @Test
    fun `标签晚于屏行的不回溯套用`() {
        // 组头只服务它**之后**的屏行：serial=100 的行不认 serial=102 的组头。
        val chips = listOf(option("休息[403]", serial = 100))
        val groups = trayGroupsOf(chips, listOf(102L to "系统菜单"))
        assertEquals(1, groups.size)
        assertNull(groups[0].label)
    }

    // ------------------------------------------------------------------
    // peek（§4.3 收起态）
    // ------------------------------------------------------------------

    @Test
    fun `peek 取第一屏行且最多 4 枚`() {
        // 第一屏行 = 候选序里最前的那一行（serial=100 的五枚），peek 取其前 4 枚；
        // serial=200 的另一屏行排在后面，不进 peek。
        val chips = listOf(
            option("一[401]", serial = 100),
            option("二[402]", serial = 100),
            option("三[403]", serial = 100),
            option("四[404]", serial = 100),
            option("五[405]", serial = 100),
            option("第二行[999]", serial = 200),
        )
        val peek = trayPeekOptions(chips)
        assertEquals(listOf("一[401]", "二[402]", "三[403]", "四[404]"), peek.map { it.label })
    }

    @Test
    fun `空候选 peek 为空`() {
        assertTrue(trayPeekOptions(emptyList()).isEmpty())
    }

    // ------------------------------------------------------------------
    // HUD 装配（§3.6.1 ③④⑤ / §3.6.5；样例行取自附录 A3/A4 的关键行）
    // ------------------------------------------------------------------

    /** A3 形单人交互页的关键行（短标签 气/法 不在固定标签集合 → 实测只出 体力/酒/精）。 */
    private val a3Lines = listOf(
        "夏之日 17日(六) 9时40分（已时二点-小昼）— ○ Ｃ敏感 —○ 气温20.0℃ <可吃饭>",
        "体力 ▁▁▁ 3815 气 ▁▁▁ 4213 法 ▁▁▁ 3052 酒 ▁ 0 精 ▁▁ 2420",
        "好感度: Ex 113791  信赖度: S 3330  欲求不满度: 87%  孩子个数: 0  怀孕: ×",
        "(=主人公)清  Erection!!  体 1818 气 1224 法 0 酒 0 精 0",
        "昂: [今日运势: 171>]",
        "执行中:「带着零感的水」",
    )

    /** A4 形多角色页的关键行（两块：白莲合格、一轮缺经验/数值行被规则 2 挡掉）。 */
    private val a4Lines = listOf(
        "夏之月 17日(六)9时15分 (已时一点-小昼) — Ｃ敏感 —○ 气温20.0℃ <可吃饭>",
        "[白莲] [同行中]  [一轮]",
        "[白莲] 好感度: S 29201  信赖度: A 2377  欲求不满度: 100%  孩子个数: 0  受孕: ○",
        "昂  [体力] 3845  气 4495  法 3052  酒 0  精 2420",
        "(主角)清醒裾裾 1020",
        "白莲  [体力] 2666  气 2370  法 754  酒 0",
        "情绪:  理性: ★★★★  危险日前",
        "昂: [今日运势: 712]",
        "执行中 (昂) [带着雾之湖的水]",
    )

    @Test
    fun `G2 角色主体数值条来自无前缀数值行且失配槽位缺席`() {
        val hud = HudLineParser.parseHud(a3Lines)
        // 无块可用 → 退回 bars 快照；气力/法力/理性解析不出 → 缺席（绝不 0 兜底，§0-5）。
        val bars = hudBarsFor(HudSubject.CHARACTER, hud, selectedBlockIndex = 0)
        assertEquals(listOf("体力", "酒", "精"), bars.map { it.label })
        assertEquals(3815, bars.first { it.label == "体力" }.value.current)
        assertNull(bars.first { it.label == "体力" }.value.max)
    }

    @Test
    fun `G3 角色主体数值条来自选中块且危险日进状态点`() {
        val hud = HudLineParser.parseHud(a4Lines)
        assertEquals(1, hud.charCount) // 一轮缺经验/数值行 → 只剩白莲一块（切分规则 2）
        val bars = hudBarsFor(HudSubject.CHARACTER, hud, selectedBlockIndex = 0)
        // A4 白萧行只印到 酒（无 精）→ 精如实缺席（§0-5）。
        assertEquals(listOf("体力", "酒"), bars.map { it.label })
        assertEquals(2666, bars.first { it.label == "体力" }.value.current)
        // 状态点（§3.6.2）：危险日前 / 受孕 ○ → 红；两态皆无 → 绿。
        val block = hud.blocks.single()
        assertEquals(DOT_RED, characterDotColor(block))
        assertEquals(
            DOT_GREEN,
            characterDotColor(block.copy(dangerDay = false, exps = block.exps.copy(conception = null))),
        )
    }

    @Test
    fun `主角主体数值条混取主角块与全屏快照`() {
        val hud = HudLineParser.parseHud(a4Lines)
        val bars = hudBarsFor(HudSubject.PROTAGONIST, hud, selectedBlockIndex = 0)
        // 主角数值行给 体力/酒/精；TSP/运势全屏快照才有钱（A4 里没印 TSP → 缺席）。
        assertEquals(listOf("体力", "精", "运势"), bars.map { it.label })
        assertEquals(3845, bars.first { it.label == "体力" }.value.current)
        assertEquals(712, bars.first { it.label == "运势" }.value.current)
        assertNull(bars.first { it.label == "运势" }.value.max)
    }

    @Test
    fun `主体切换标签直接写名字且失配侧缺席`() {
        val a4 = HudLineParser.parseHud(a4Lines)
        // 当前对象 = 首个合格块名；主角名 = 数值行名字优先（切分规则 3）。
        assertEquals("白莲" to "昂", hudSubjectLabels(a4))
        val a3 = HudLineParser.parseHud(a3Lines)
        assertNull("A3 无合格块 → 少女侧缺席", hudSubjectLabels(a3).first)
        assertEquals("清", hudSubjectLabels(a3).second)
    }

    @Test
    fun `主角主体的抽屉标题与 chips 用主角数据`() {
        val hud = HudLineParser.parseHud(a4Lines)
        assertEquals("昂", hudSubjectName(HudSubject.PROTAGONIST, hud, 0))
        val chips = hudDrawerChips(HudSubject.PROTAGONIST, hud, 0)
        // 主角数值行（昂 [体力]…）给 体力/酒/精；全屏运势行给 今日运势（§3.6.5）。
        // 标记行 `(主角)清醒裾裾 1020` 的数值不进 chips——P1 口径：标签不在 token 开头
        // （前面有 (主角) 前缀）不命中，如实缺席、不编造。
        assertTrue(chips.contains("体力 3845"))
        assertTrue(chips.contains("酒 0"))
        assertTrue(chips.contains("今日运势 712"))
        assertFalse(chips.contains("清醒裾裾 1020"))
    }

    @Test
    fun `角色主体的抽屉 chips 来自选中块经验行`() {
        val hud = HudLineParser.parseHud(a4Lines)
        assertEquals("白莲", hudSubjectName(HudSubject.CHARACTER, hud, 0))
        val chips = hudDrawerChips(HudSubject.CHARACTER, hud, 0)
        assertTrue(chips.contains("好感度 29201"))
        assertTrue(chips.contains("信赖度 2377"))
        assertTrue(chips.contains("受孕 ○"))
        // 首块 = 当前对象 → 工作行战斗能力可附（A4 工作行没给出段位数字时按字段缺席）。
    }

    @Test
    fun `A3 单人页抽屉退回无前缀经验行`() {
        val hud = HudLineParser.parseHud(a3Lines)
        val chips = hudDrawerChips(HudSubject.CHARACTER, hud, 0)
        assertTrue(chips.contains("好感度 113791"))
        assertTrue(chips.contains("信赖度 3330"))
        assertTrue(chips.contains("欲求不满度 87"))
        // 怀孕 × 符号照原文显示（符号值不是数字，§0-5 不算编造）。
        assertTrue(chips.contains("怀孕 ×"))
    }

    @Test
    fun `连经验也没有时抽屉入口隐藏`() {
        // 只有日期行的屏：chips 全空 → hudDrawerAvailable false（§3.6.5）。
        val hud = HudLineParser.parseHud(listOf("夏之日 17日(六) 9时40分（已时二点-小昼）"))
        assertFalse(hudDrawerAvailable(HudSubject.CHARACTER, hud, 0))
        assertFalse(hudDrawerAvailable(HudSubject.PROTAGONIST, hud, 0))
        val a3 = HudLineParser.parseHud(a3Lines)
        assertTrue(hudDrawerAvailable(HudSubject.CHARACTER, a3, 0))
    }

    @Test
    fun `选中下标越界按块数收拢`() {
        // 形态切换后块数变少：下标 clamp 到尾块，不崩、不串块（§9-2 无残留态）。
        val hud = HudLineParser.parseHud(a4Lines)
        assertEquals("白莲", hudSubjectName(HudSubject.CHARACTER, hud, selectedBlockIndex = 99))
    }

    // ------------------------------------------------------------------
    // G0/G1 徽章级数值条（hudBadgeBars 直接透传快照）
    // ------------------------------------------------------------------

    @Test
    fun `G0 徽章级数值条按屏行原序透传`() {
        val hud = HudLineParser.parseHud(
            listOf(
                "体力 ▁▁▁▁ ( 3249/ 3845)  气力 ▁▁▁▁ ( 4245/ 4545)  法力 ▁▁▁ ( 3052/ 3052)",
                "TSP ▁▁▁▁ ( 1645/ 1645)   精力 ▁ ( 120/ 2420)",
            ),
        )
        val bars = hudBadgeBars(hud)
        assertEquals(listOf("体力", "气力", "法力", "TSP", "精力"), bars.map { it.label })
        assertEquals(3249, bars[0].value.current)
        assertEquals(3845, bars[0].value.max)
    }
}
