package com.eratw.port.android.portrait

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.android.PortraitShellCopy
import com.eratw.port.android.UiPrefs
import java.util.Locale

/**
 * 竖屏顶部状态 HUD（设计稿 `docs/archive/竖屏布局设计稿.md` §4.1 + §3.6.1，后者是
 * G2/G3 统一 HUD 的**唯一规格**）。
 *
 * **单一渲染入口**（§0-16）：四形态都从 [PortraitHud] 进——G0/G1 走徽章级分支，
 * G2/G3 走统一骨架六行（① 身份行 / ② 角色条 / ③ 主体切换 / ④ 数值条 / ⑤ 详情抽屉 /
 * ⑥ 入口），两页**共用同一段代码**，差别只允许「多一条角色条」。
 *
 * 数据源（§0-3/§0-5）：只用 `HudLineParser.parseHud` 的屏行解析快照——任何字段解析不出
 * 就是 null/空，对应控件**整体隐藏**，绝不显示编造默认值、绝不 0 兜底。
 *
 * 交互纪律（§0-12）：主体切换、角色点选、抽屉开合**零指令提交**——壳层只切显示上下文；
 * 游戏内换对象是 `变更对象[802]`（指令盘里按屏行原序可点），HUD 一概不碰。
 * HUD 只显示**当前主体**，不并列堆主角 + 少女（§0-17）。
 */

/** 数值条的一项：标签 + 值（渲染层只管画，组装在 [hudBarsFor] / [hudBadgeBars]）。 */
internal data class HudBarSpec(val label: String, val value: BarValue)

/** HUD 主体（§3.6.1 主体切换）：当前少女 / 主角。缺省 = 少女（交互主视角）。 */
internal enum class HudSubject { CHARACTER, PROTAGONIST }

/**
 * 主体切换 pill 的两枚标签（§3.6.1：标签直接写名字；名字解析不出的一侧为 null，
 * 该 pill 不显示——失配即隐藏，§0-5）。
 */
internal fun hudSubjectLabels(snapshot: HudSnapshot): Pair<String?, String?> =
    snapshot.currentTarget to snapshot.protagonist?.name

/**
 * 当前主体名（① 身份行 / ⑤ 抽屉标题共用）：角色主体 = 选中块的名字（缺省 = 首块 =
 * 当前交互对象），主角主体 = 主角名。都解析不出 → null（调用侧显示「era」占位，
 * §3 数据来源一览「M1 不解析，显示 era 字样占位」）。
 */
internal fun hudSubjectName(
    subject: HudSubject,
    snapshot: HudSnapshot,
    selectedBlockIndex: Int,
): String? {
    if (subject == HudSubject.PROTAGONIST) return snapshot.protagonist?.name
    if (snapshot.blocks.isEmpty()) return snapshot.currentTarget
    val idx = selectedBlockIndex.coerceIn(0, snapshot.blocks.size - 1)
    return snapshot.blocks[idx].name
}

/**
 * ④ 数值条（§3.6.1 ④ 的固定六槽，G2/G3 用）：槽位顺序 = 规格——角色（体力/气力/法力/
 * 酒/精/理性）、主角（体力/气力/法力/TSP/精/运势）。**解析不出的槽位直接缺席**
 * （附录 A 规则 3）：P1 解析器的固定标签集合没有 `气`/`法` 单字短标签（P1 交付
 * 「与基准差异」第一条），所以交互页实测只有 体力/酒/精 等——按失配隐藏口径照常渲染。
 *
 * 取值来源：角色主体 = 选中角色块的数值行（`CharacterBlock.stats`；无块可用时退回
 * 无前缀数值行快照 `HudSnapshot.bars`，即 A3 形的单人数值行）；主角主体 = 主角块
 * 数值 + TSP/运势取全屏快照（主角数值行没有这两项，A2/A4 的法力行/运势行才有）。
 */
internal fun hudBarsFor(
    subject: HudSubject,
    snapshot: HudSnapshot,
    selectedBlockIndex: Int,
): List<HudBarSpec> = when (subject) {
    HudSubject.CHARACTER -> {
        val stats = if (snapshot.blocks.isEmpty()) {
            snapshot.bars
        } else {
            snapshot.blocks[selectedBlockIndex.coerceIn(0, snapshot.blocks.size - 1)].stats
        }
        listOf("体力", "气力", "法力", "酒", "精", "理性").mapNotNull { label ->
            stats[label]?.let { HudBarSpec(label, it) }
        }
    }
    HudSubject.PROTAGONIST -> {
        val protagonist = snapshot.protagonist
        val slots = buildList {
            add("体力" to protagonist?.stats?.get("体力"))
            add("气力" to protagonist?.stats?.get("气力"))
            add("法力" to protagonist?.stats?.get("法力"))
            add("TSP" to snapshot.bars["TSP"])
            add("精" to protagonist?.stats?.get("精"))
            add("运势" to snapshot.fortune?.let { BarValue(it, null) })
        }
        slots.mapNotNull { (label, value) -> value?.let { HudBarSpec(label, it) } }
    }
}

/**
 * G0/G1 徽章级的数值条（§4.1「G0/G1 = 徽章级……数值条保留」）：直接用无前缀数值行
 * 快照（A1 两行 / A2 的法力行、清醒裾裾行），顺序 = 屏行原序（解析器 LinkedHashMap）。
 */
internal fun hudBadgeBars(snapshot: HudSnapshot): List<HudBarSpec> =
    snapshot.bars.map { (label, value) -> HudBarSpec(label, value) }

/**
 * ⑤ 详情抽屉的 chips（M1 数值化范围，纯函数可测；§3.6.5 + §4.1 数据来源一览）：
 *   * 角色主体 = 选中块的经验行（好感度/信赖度/欲求不满度/孩子个数/怀孕/受孕）；
 *     无块可用时退回无前缀经验行快照（A3 形）；选中首个块时附工作行的战斗能力
 *     （游戏把当前对象的工作行列在对象块旁，A4；其余块没有对应行，不硬凑）。
 *   * 主角主体 = 主角数值/状态 token（清醒裾裾、酒、精……来自标记行 + 主角数值行）
 *     + 今日运势；主角专属详情（素质・经验・装备）属 M2（§3.6.5），抽屉里以备注行说明。
 * Palam 网格 M1 不解析（附录 A 明注）→ 不显示，绝不编造（§3.6.5 降级路径）。
 */
internal fun hudDrawerChips(
    subject: HudSubject,
    snapshot: HudSnapshot,
    selectedBlockIndex: Int,
): List<String> = when (subject) {
    HudSubject.CHARACTER -> {
        val exps = if (snapshot.blocks.isEmpty()) {
            snapshot.exps
        } else {
            snapshot.blocks[selectedBlockIndex.coerceIn(0, snapshot.blocks.size - 1)].exps
        }
        buildList {
            if (exps != null) {
                exps.favor?.let { add("好感度 $it") }
                exps.trust?.let { add("信赖度 $it") }
                exps.frustration?.let { add("欲求不满度 $it") }
                exps.children?.let { add("孩子个数 $it") }
                exps.pregnancy?.let { add("怀孕 $it") }
                exps.conception?.let { add("受孕 $it") }
            }
            if (selectedBlockIndex == 0) {
                snapshot.work?.battleRank?.let { rank ->
                    val power = snapshot.work?.battlePower
                    add(if (power != null) "战斗能力 $rank $power" else "战斗能力 $rank")
                }
            }
        }
    }
    HudSubject.PROTAGONIST -> {
        val protagonist = snapshot.protagonist ?: return emptyList()
        buildList {
            protagonist.stats.forEach { (label, value) -> add("$label ${value.current}") }
            snapshot.fortune?.let { add("今日运势 $it") }
        }
    }
}

/** 抽屉入口要不要显示（§3.6.5「连经验也没有 → 抽屉入口隐藏」）：chips 全空 = 隐藏。 */
internal fun hudDrawerAvailable(
    subject: HudSubject,
    snapshot: HudSnapshot,
    selectedBlockIndex: Int,
): Boolean = hudDrawerChips(subject, snapshot, selectedBlockIndex).isNotEmpty()

/** 角色条状态点颜色（§3.6.2：红 = 危险/受孕 ○，绿 = 常态；块总在这两态之一）。 */
internal fun characterDotColor(block: CharacterBlock): Color =
    if (block.dangerDay || block.exps.conception == "○") Color(0xFFE86E7A) else Color(0xFF7FAF7F)

/**
 * HUD 本体。全部显隐判据都来自入参（形态/解析快照/壳层状态），本文件不持状态——
 * 状态归 [PortraitShellContent]，保证四形态同一入口、无残留态（§9 检查单 2）。
 *
 * @param hudColsPerRow 数值条每行条数（§5.1 断点表 hudCols，调用侧按屏宽选档）
 * @param contentMaxHeight 宿主内容区高（抽屉上限的派生基准，§0-14 禁视口单位）
 * @param onScrollToSections 抽屉锚点按钮「翻到正文 Status/Palam 区」（M1 =
 *   控制台视图自身滚动一屏，见 PortraitShellContent 的接线注记）
 * @param onScrollToLatest 抽屉锚点按钮「回到最新」
 */
@Composable
internal fun PortraitHud(
    form: TrayForm,
    snapshot: HudSnapshot,
    subject: HudSubject,
    onSubjectChange: (HudSubject) -> Unit,
    selectedBlockIndex: Int,
    onBlockSelect: (Int) -> Unit,
    detailOpen: Boolean,
    onDetailToggle: () -> Unit,
    trayOpen: Boolean,
    onTrayToggle: () -> Unit,
    inputVisible: Boolean,
    onInputToggle: () -> Unit,
    onSettings: () -> Unit,
    hudColsPerRow: Int,
    contentMaxHeight: Dp,
    onScrollToSections: () -> Unit,
    onScrollToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unified = form == TrayForm.INTERACT || form == TrayForm.MULTI
    // 文案表：语言由本层一次取好往下传（照 UpdateUi.copyFor 的口径，子件不再各判语言）。
    val copy = PortraitShellCopy.of(UiPrefs.settingsLanguage(LocalContext.current))
    // G2/G3 的抽屉 chips 在 ④/⑤/⑥ 三处共用同一份判据：抽屉可用时才让位数值条（§3.6.1），
    // chips 全空时抽屉与入口都隐藏、数值条保持显示（§3.6.5「连经验也没有 → 入口隐藏」）。
    val drawerChips = if (unified) hudDrawerChips(subject, snapshot, selectedBlockIndex) else emptyList()
    val drawerUsable = drawerChips.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PortraitTokens.Panel, RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            .border(
                width = 1.dp,
                color = PortraitTokens.PanelLine,
                shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // ── ① 身份行（四形态同一行；§4.1 ①）：头像圈 + 名字 + 徽章 + 键/盘/设 ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            val name = if (unified) hudSubjectName(subject, snapshot, selectedBlockIndex) else null
            AvatarCircle(name = name, highlighted = unified && subject == HudSubject.PROTAGONIST)
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name ?: "era",
                        color = PortraitTokens.Ink,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!unified) {
                        // G0/G1 徽章（§4.1：日期/所持金入名字行徽章；失配即隐藏）。
                        snapshot.dateText?.let { HudBadge(it, Modifier.padding(start = 6.dp)) }
                        snapshot.money?.let {
                            HudBadge(copy.fill(copy.hudMoneyFormat, "%,d".format(Locale.ROOT, it)), Modifier.padding(start = 4.dp))
                        }
                    } else {
                        snapshot.dateText?.let { HudBadge(it, Modifier.padding(start = 6.dp)) }
                    }
                }
                if (!unified) {
                    // G0/G1 的地点/时间徽章行（§3.5「地点/时间入徽章」；失配整行隐藏）。
                    val placeTime = listOfNotNull(snapshot.place, snapshot.time)
                    if (placeTime.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            placeTime.forEach { HudBadge(it) }
                        }
                    }
                }
            }
            HudIconButton(label = copy.hudKeyButton, active = inputVisible, onClick = onInputToggle)
            HudIconButton(label = copy.hudTrayButton, active = trayOpen, onClick = onTrayToggle)
            HudIconButton(label = copy.hudSettingsButton, active = false, onClick = onSettings)
        }

        // ── ② 角色条（仅 G3 且合格块 ≥2；§3.6.2：解析不出 → 整体隐藏退回 G2 外观） ──
        if (form == TrayForm.MULTI && snapshot.blocks.size >= 2) {
            CharacterRail(
                blocks = snapshot.blocks,
                selectedBlockIndex = selectedBlockIndex,
                onBlockSelect = onBlockSelect,
            )
        }

        // ── G0/G1 的 buff 行整行 chips（§4.1「buff 行整行变 chips」；失配整行隐藏） ──
        if (!unified && snapshot.buffChips.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                snapshot.buffChips.take(6).forEach { HudBadge(it) }
            }
        }

        if (unified) {
            // ── ③ 主体切换（G2/G3 同一行；§3.6.1：两枚等宽 pill，标签直接写名字） ──
            val labels = hudSubjectLabels(snapshot)
            if (labels.first != null || labels.second != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    labels.first?.let { name ->
                        SubjectPill(
                            label = name,
                            selected = subject == HudSubject.CHARACTER,
                            onClick = { onSubjectChange(HudSubject.CHARACTER) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    labels.second?.let { name ->
                        SubjectPill(
                            label = name,
                            selected = subject == HudSubject.PROTAGONIST,
                            onClick = { onSubjectChange(HudSubject.PROTAGONIST) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        // ── ④ 数值条：抽屉展开且可用时整段真移除让位（§3.6.1 高度联动；§0-15 禁位移隐藏） ──
        AnimatedVisibility(visible = !(detailOpen && drawerUsable)) {
            val bars = if (unified) hudBarsFor(subject, snapshot, selectedBlockIndex) else hudBadgeBars(snapshot)
            if (bars.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    bars.chunked(hudColsPerRow.coerceAtLeast(1)).forEach { rowBars ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowBars.forEach { spec -> HudBar(spec, modifier = Modifier.weight(1f)) }
                            repeat(hudColsPerRow - rowBars.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        if (unified) {
            // ── ⑤ 详情抽屉（M1：经验 chips + 锚点跳转按钮；Palam 属 M2 不解析不显示） ──
            AnimatedVisibility(visible = detailOpen && drawerUsable) {
                DetailDrawer(
                    title = hudSubjectName(subject, snapshot, selectedBlockIndex),
                    protagonistSide = subject == HudSubject.PROTAGONIST,
                    chips = drawerChips,
                    copy = copy,
                    maxHeight = contentMaxHeight * 0.22f,
                    onScrollToSections = onScrollToSections,
                    onScrollToLatest = onScrollToLatest,
                )
            }
            // ── ⑥ 入口（§3.6.5：连经验也没有 → 抽屉入口隐藏；G0/G1 无抽屉无入口） ──
            if (drawerUsable) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clickable(onClick = onDetailToggle)
                        .padding(vertical = 3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (detailOpen) copy.hudDetailOpen else copy.hudDetailClosed,
                        color = PortraitTokens.DimText,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/** 身份行右端的三枚 34dp 图标钮（§4.1 ①：键/盘/设；§10-7 竖屏自绘，不与横屏共用状态）。 */
@Composable
private fun HudIconButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                if (active) Color(0xFF24382A) else Color(0xFF101010),
                RoundedCornerShape(8.dp),
            )
            .border(1.dp, if (active) PortraitTokens.Green else Color(0xFF303030), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = if (active) PortraitTokens.Green else PortraitTokens.DimText, fontSize = 12.sp)
    }
}

/** 头像圈（§4.1 ①：36dp，名字首字；主体 = 主角时转黄；名字解析不出显示「era」占位）。 */
@Composable
private fun AvatarCircle(name: String?, highlighted: Boolean) {
    val bg = if (highlighted) Color(0xFFEFD27C) else PortraitTokens.Green
    Box(
        modifier = Modifier.size(36.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name?.take(1) ?: "era",
            color = Color(0xFF161616),
            fontSize = if (name == null) 9.sp else 15.sp,
            maxLines = 1,
        )
    }
}

/** 名字行徽章 chip（G0/G1 日期/所持金/地点/时间；G2/G3 日期）。 */
@Composable
private fun HudBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color(0xFFCFCFCF),
        fontSize = 9.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(Color(0xFF101010), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF303030), RoundedCornerShape(6.dp))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

/** 主体切换 pill（§3.6.1：两枚等宽，当前主体高亮；点选零提交，§0-12）。 */
@Composable
private fun SubjectPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(if (selected) Color(0xFF24382A) else Color(0xFF101010), RoundedCornerShape(999.dp))
            .border(
                1.dp,
                if (selected) PortraitTokens.Green else Color(0xFF303030),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) PortraitTokens.Ink else PortraitTokens.DimText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 角色条（§3.6.2）：横滑 pill 行，上限 10（§0-13 原版事实；超出显示 +N——理论上不会）。
 * 点选 = 「选中该少女」+「主体切回角色」一次完成（壳层回调里做），绝不提交指令。
 */
@Composable
private fun CharacterRail(
    blocks: List<CharacterBlock>,
    selectedBlockIndex: Int,
    onBlockSelect: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
    ) {
        itemsIndexed(blocks.take(10)) { index, block ->
            val selected = index == selectedBlockIndex
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(if (selected) Color(0xFF24382A) else Color(0xFF101010), RoundedCornerShape(999.dp))
                    .border(
                        1.dp,
                        if (selected) PortraitTokens.Green else Color(0xFF303030),
                        RoundedCornerShape(999.dp),
                    )
                    .clickable { onBlockSelect(index) }
                    .padding(start = 4.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
            ) {
                Box(
                    modifier = Modifier.size(20.dp).background(PortraitTokens.Green, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = block.name.take(1), color = Color(0xFF161616), fontSize = 10.sp, maxLines = 1)
                }
                Text(
                    text = block.name,
                    color = if (selected) PortraitTokens.Ink else Color(0xFFCFCFCF),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(6.dp)
                        .background(characterDotColor(block), CircleShape),
                )
            }
        }
        if (blocks.size > 10) {
            item {
                Text(
                    text = "+${blocks.size - 10}",
                    color = PortraitTokens.DimText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 2.dp, top = 6.dp),
                )
            }
        }
    }
}

/**
 * 单条数值（§4.1 ④：标签 + 等宽数值 + 4px 圆角条）。上限未知（解析不分母式）时
 * **不画条**、只出标签与数值——画一条空轨或满轨都是编造读数（§0-5 同族纪律）。
 * 条色 = §11 token 表；表外标签（酒/清醒裾裾等）用弱字灰如实占位（§11 没给的色不硬编）。
 */
@Composable
private fun HudBar(spec: HudBarSpec, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = spec.label,
                color = Color(0xFFB5B5B5),
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = spec.value.max?.let { "${spec.value.current}/${spec.value.max}" }
                    ?: "${spec.value.current}",
                color = PortraitTokens.Ink,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        val max = spec.value.max
        if (max != null && max > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp)
                    .height(4.dp)
                    .background(Color(0xFF262626), RoundedCornerShape(4.dp)),
            ) {
                val fraction = (spec.value.current.toFloat() / max.toFloat()).coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(4.dp)
                        .background(barColor(spec.label), RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/** 数值条配色（§11 条色行：体力/精/法力/TSP/气力/理性；表外标签弱字灰，报告登记）。 */
private fun barColor(label: String): Color = when (label) {
    "体力" -> Color(0xFF7FAF7F)
    "精", "精力" -> Color(0xFF6FC7E8)
    "法力" -> Color(0xFFE0A04F)
    "TSP" -> Color(0xFF5A8FD8)
    "气力" -> Color(0xFF8F86E0)
    "理性" -> Color(0xFFE86E8A)
    else -> Color(0xFF8A8A8A)
}

/**
 * ⑤ 详情抽屉（M1 形态；§3.6.5 + §4.1）：标题 = 当前主体名；内容 = 经验 chips +
 * 锚点跳转按钮（M1 数值化范围外的 Palam 属 M2，不显示）。上限由宿主内容区派生
 * （调用侧传 `contentMaxHeight * 0.22f`），内部可滚动——不是固定高框（§0-15）。
 */
@Composable
private fun DetailDrawer(
    title: String?,
    protagonistSide: Boolean,
    chips: List<String>,
    copy: PortraitShellCopy,
    maxHeight: Dp,
    onScrollToSections: () -> Unit,
    onScrollToLatest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .heightIn(max = maxHeight)
            .background(Color(0xFF101010), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF303030), RoundedCornerShape(8.dp))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = copy.fill(
                if (protagonistSide) copy.drawerProtagonistTitleFormat else copy.drawerCharacterTitleFormat,
                title ?: "era",
            ),
            color = PortraitTokens.Green,
            fontSize = 10.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        chips.chunked(2).forEach { rowChips ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowChips.forEach { chip ->
                    Text(
                        text = chip,
                        color = Color(0xFFCFCFCF),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .background(Color(0xFF161616), RoundedCornerShape(6.dp))
                            .border(1.dp, Color(0xFF303030), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        if (protagonistSide) {
            // §3.6.5：主角专属详情（素质・经验・装备）属 M2，抽屉里如实备注。
            Text(text = copy.drawerM2Note, color = PortraitTokens.DimText, fontSize = 9.5.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF161616), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF303030), RoundedCornerShape(6.dp))
                    .clickable(onClick = onScrollToSections)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = copy.drawerScrollToSections,
                    color = PortraitTokens.DimText,
                    fontSize = 9.5.sp,
                    maxLines = 1,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF161616), RoundedCornerShape(6.dp))
                    .border(1.dp, Color(0xFF303030), RoundedCornerShape(6.dp))
                    .clickable(onClick = onScrollToLatest)
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = copy.drawerScrollToLatest, color = PortraitTokens.DimText, fontSize = 9.5.sp, maxLines = 1)
            }
        }
    }
}
