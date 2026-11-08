package com.eratw.port.android.portrait

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.eratw.port.android.PortraitShellCopy
import com.eratw.port.android.UiPrefs
import com.eratw.port.android.railRowsOf
import com.eratw.port.core.host.ConsoleOption

/**
 * 竖屏三区壳的**底部指令盘**（设计稿 `docs/archive/竖屏布局设计稿.md` §4.3 是唯一规格）。
 *
 * 数据源铁律（§0-4）：按钮唯一来源 = `GameHost.currentOptions()` 快照（壳层侧是
 * `ShellState.chips`，250ms 扫描已是现状），分组复用 `railRowsOf` 按**游戏显示行**分行。
 * **不排序、不跨行合并、不按指令号自行分组**——组标签也只是游戏屏行原文里剥出的
 * `【…】` 行（[HudLineParser.parseGroupLabel]），**永不发明分组**（§4.3、§10-9）。
 *
 * 高度纪律（§0-14/15）：展开态上限由**宿主容器**（`BoxWithConstraints.maxHeight`）的
 * 百分比派生（s 档 40%、其余 46%，§4.3），在调用侧算好传入；命令区在上限内
 * `verticalScroll` 内部滚动，**收起按钮恒在盘底、不进滚动区**（§10-15 的合成症状防线）。
 *
 * 开合只跟用户开关（§10-4）：空 chips 显示占位行「（本屏没有可点选项）」，不隐藏整个盘；
 * G0 自动展开与 `command_tray_open` 的关系在 [PortraitShellContent] 一处处理。
 */

/** 展开态整盘高度上限占宿主内容区的比例（§4.3：46%；s 档收一档到 40%——§3.6.4 实测口径）。 */
internal fun trayMaxHeightFraction(size: SizeClass): Float =
    if (size == SizeClass.S) 0.40f else 0.46f

/** peek（收起态首行）最多显示的按钮数（§4.3「第一屏行（≤4 枚）」）。 */
internal const val TRAY_PEEK_MAX = 4

/**
 * 组头与它服务的下一行候选组的**最大 serial 距离**。组头 `【…】` 印在候选行的前一两行
 * （组头本身不带按钮，serial 必然小于它的组内首枚候选——设计稿 §3.5 的「候选首末区间」
 * 按字面收 [minSerial, maxSerial] 会把第一个组头结构性排除，这里按「组头到下一候选组
 * 的距离 ≤ 本常量」落实同一意图：8 行足够覆盖组头 + 少量信息行，又远小于一屏高度，
 * 上一屏滚出视野的旧组头够不着当前候选）。
 */
internal const val TRAY_GROUP_LABEL_LOOKBACK = 8L

/**
 * 从屏行尾窗里剥出**属于当前候选**的组标签行（纯函数，JVM 可测；§3.5 组标签段）：
 * `DisplayLine.serial` 与 `ConsoleOption.lineSerial` 是同一序号体系，只认「到下一候选行
 * 距离 ≤ [TRAY_GROUP_LABEL_LOOKBACK]」的屏行，整行命中 `【…】` 的经
 * [HudLineParser.parseGroupLabel] 剥取。够不着的组标签（更早屏面的残留）不算——组头只
 * 服务当前屏的候选。剥不出 → 空表（组标签缺省、按钮照常平铺，§3.5 降级路径；标签集合
 * 不进白名单，§10-9）。
 */
internal fun trayGroupLabelLines(
    tailLines: List<Pair<Long, String>>,
    chips: List<ConsoleOption>,
): List<Pair<Long, String>> {
    if (chips.isEmpty() || tailLines.isEmpty()) return emptyList()
    val minSerial = chips.minOf { it.lineSerial }
    val maxSerial = chips.maxOf { it.lineSerial }
    return tailLines.mapNotNull { (serial, text) ->
        val isLabelShape = serial in (minSerial - TRAY_GROUP_LABEL_LOOKBACK)..maxSerial &&
            HudLineParser.parseGroupLabel(text) != null
        if (isLabelShape) {
            serial to HudLineParser.parseGroupLabel(text)!!
        } else {
            null
        }
    }
}

/**
 * 一段组标签 + 它名下的屏行按钮组（展开态纵排的一个单元）。
 *
 * [label] = 该组头上的 `【…】` 行剥出文本；null = 剥不出（组标签缺省、按钮照常平铺，
 * §3.5 降级路径）。[rows] = 按 `railRowsOf` 分好的屏行（每行内候选按引擎排好的序原样）。
 */
internal data class TrayGroup(val label: String?, val rows: List<List<ConsoleOption>>)

/**
 * 候选 + 组标签行 → 展开态的纵排单元（纯函数，JVM 可测）。
 *
 * 规则（§3.5 组标签段）：
 *   * [labelLines] 是候选首末 `lineSerial` 区间内的屏行里、整行命中 `【…】` 的
 *     （serial to 标签文本）列表，调用侧已用 [HudLineParser.parseGroupLabel] 剥好；
 *   * 每个屏行组（同一 `lineSerial` 的候选，[railRowsOf] 已分行）的组标签 = serial
 *     **不晚于**该行 `lineSerial` 的最后一个组标签行；
 *   * 相邻屏行组标签相同 → 并进同一 [TrayGroup]（同一组头下的多行按钮）；
 *   * 区间内一个标签都没有 → 全部候选落进单个无标签组（按钮平铺，§3.5 降级）。
 *
 * 顺序完全跟随候选与屏行的原序，无任何排序/白名单（§0-4、§10-9）。
 */
internal fun trayGroupsOf(
    chips: List<ConsoleOption>,
    labelLines: List<Pair<Long, String>>,
): List<TrayGroup> {
    if (chips.isEmpty()) return emptyList()
    val rows = railRowsOf(chips)
    val groups = ArrayList<TrayGroup>()
    var currentLabel: String? = null
    var currentRows = ArrayList<List<ConsoleOption>>()
    fun flush() {
        if (currentRows.isNotEmpty()) groups += TrayGroup(currentLabel, currentRows)
        currentRows = ArrayList()
    }
    for (row in rows) {
        val rowSerial = row.first().lineSerial
        // 不晚于本行 serial 的最后一个组标签行（区间过滤已在调用侧做过，这里只认顺序）。
        var label: String? = null
        for ((serial, text) in labelLines) {
            if (serial <= rowSerial) label = text else break
        }
        if (label != currentLabel) {
            flush()
            currentLabel = label
        }
        currentRows += row
    }
    flush()
    return groups
}

/**
 * 收起态 peek 的按钮（纯函数，JVM 可测）：**第一屏行**的前 [TRAY_PEEK_MAX] 枚（§4.3
 * 「peek = 第一屏行（≤4 枚）」）。第一屏行 = 与首枚候选同 `lineSerial` 的全部候选
 * （引擎按 `(lineSerial, startCol)` 排好序）——不用 `railRowsOf` 的首行，因为那是
 * 右栏的「一行 ≤3 枚」换行口径，会把第四枚挤到下一行；屏行的本义是游戏显示行。
 * 没有候选 → 空表（调用侧显示占位行）。
 */
internal fun trayPeekOptions(chips: List<ConsoleOption>): List<ConsoleOption> {
    val first = chips.firstOrNull() ?: return emptyList()
    return chips.takeWhile { it.lineSerial == first.lineSerial }.take(TRAY_PEEK_MAX)
}

/**
 * 指令盘本体。只管渲染，开合态/持久化/形态判据都在 [PortraitShellContent]。
 *
 * @param groups 展开态纵排单元（[trayGroupsOf] 的产物；空 chips 时为空表）
 * @param peek 收起态首行按钮（[trayPeekOptions] 的产物）
 * @param columns 按钮网格列数（§5.1 断点表 `cmdCols`，调用侧按屏宽选档）
 * @param maxHeight 展开态整盘高度上限（调用侧按 `BoxWithConstraints.maxHeight` ×
 *   [trayMaxHeightFraction] 算好传入——§0-14 禁视口单位，宿主容器派生）
 * @param open 展开态（true = 全部分组纵排；false = peek 收起）
 * @param onToggleOpen 开合开关回调（只翻壳层状态，绝不提交指令，§0-12）
 * @param executingText 执行中行原文（M1 行解析，失配 null → 整行隐藏，§4.3 收起态）
 * @param lastCommandText 上回指令原文（同上；空串也算解析成立，显示空值）
 * @param onOption 点按钮 → 提交该候选的 [ConsoleOption.submitValue]（提交路径与
 *   横屏右栏同一条，见 [com.eratw.port.android.ShellUi] 的 rail 回调口径）
 */
@Composable
internal fun CommandTray(
    groups: List<TrayGroup>,
    peek: List<ConsoleOption>,
    columns: Int,
    maxHeight: Dp,
    open: Boolean,
    onToggleOpen: () -> Unit,
    executingText: String?,
    lastCommandText: String?,
    onOption: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 文案表：语言由本层一次取好往下传（照 UpdateUi.copyFor 的口径）。
    val copy = PortraitShellCopy.of(UiPrefs.settingsLanguage(LocalContext.current))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .background(PortraitTokens.Panel, RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .border(
                width = 1.dp,
                color = PortraitTokens.PanelLine,
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
            )
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        if (!open) {
            // 收起态（§4.3）：上下文行（执行中 + 上回指令，失配即隐藏）+ peek + 展开按钮。
            if (executingText != null || lastCommandText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (executingText != null) {
                        Text(
                            text = copy.fill(copy.trayExecutingFormat, executingText),
                            color = Color(0xFFFFD9A0),
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    if (lastCommandText != null) {
                        Text(
                            text = copy.fill(copy.trayLastCommandFormat, lastCommandText),
                            color = PortraitTokens.DimText,
                            fontSize = 10.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (peek.isEmpty()) {
                Text(
                    text = copy.trayEmptyLine,
                    color = PortraitTokens.DimText,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            } else {
                TrayButtonGrid(options = peek, columns = columns, onOption = onOption)
            }
        } else {
            // 展开态（§4.3）：全部屏行分组纵排（含组标签行），命令区在上限内内部滚动；
            // 收起按钮在滚动区**外**（盘底），恒在屏内（§10-15）。
            if (groups.isEmpty()) {
                Text(
                    text = copy.trayEmptyLine,
                    color = PortraitTokens.DimText,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    groups.forEach { group ->
                        group.label?.let { label ->
                            Text(
                                text = label,
                                color = PortraitTokens.Green,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        group.rows.forEach { row ->
                            TrayButtonGrid(options = row, columns = columns, onOption = onOption)
                        }
                    }
                }
            }
        }
        TrayToggleButton(copy = copy, open = open, onToggleOpen = onToggleOpen)
    }
}

/**
 * 收起/展开整行按钮（§4.3：收起态「更多指令 ▾」/ 展开态「收起到常用指令 ▴」）。
 * 排在盘 Column 最底、不进滚动区——收起按钮恒在屏内是验收硬项（§10-15）。
 */
@Composable
private fun TrayToggleButton(copy: PortraitShellCopy, open: Boolean, onToggleOpen: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp)
            .heightIn(min = 30.dp)
            .background(Color.Transparent, RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF3A3A3A), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggleOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (open) copy.trayCollapseCommand else copy.trayExpandCommand,
            color = PortraitTokens.DimText,
            fontSize = 11.5.sp,
            maxLines = 1,
        )
    }
}

/**
 * 指令按钮网格（§4.3「2~4 列网格（断点 §5）」+「min-height 34dp」）。
 *
 * 样式 = §11 token 表：有 `[数字]` 编号的候选走**命令钮**（底 #24382A / 文 #D8ECD8 /
 * 边主绿 55%），无编号的**系统钮** ghost（底 #1F1F1F / 文 #C9C9C9）——判定 =
 * [optionNumberOf] 剥不出编号，不做白名单（§4.3）。标签原样显示（含 `[数字]` 尾缀，
 * 不改写游戏原文）。
 */
@Composable
private fun TrayButtonGrid(
    options: List<ConsoleOption>,
    columns: Int,
    onOption: (String) -> Unit,
) {
    val cols = columns.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        options.chunked(cols).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                chunk.forEach { option ->
                    val numbered = optionNumberOf(option.label) != null
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 34.dp)
                            .background(
                                if (numbered) Color(0xFF24382A) else Color(0xFF1F1F1F),
                                RoundedCornerShape(8.dp),
                            )
                            .border(
                                1.dp,
                                if (numbered) Color(0x8C7FAF7F) else Color(0xFF3A3A3A),
                                RoundedCornerShape(8.dp),
                            )
                            .clickable { onOption(option.submitValue) }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            color = if (numbered) Color(0xFFD8ECD8) else Color(0xFFC9C9C9),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.SansSerif,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 末行补位：保持与满行同宽的等分网格（原型 ep-cmdgrid 的 1fr 口径）。
                repeat(cols - chunk.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
