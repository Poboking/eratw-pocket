package com.eratw.port.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.ShellCaption
import com.eratw.port.android.ui.design.ShellDisclosureHeader
import com.eratw.port.android.ui.design.ShellListBadge
import com.eratw.port.android.ui.design.ShellRowHairline
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellSurfaceCard
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.android.ui.design.ShellVisibility

/** 读数块内各行之间的行距：密度面留 2dp，靠行距而非空行分组。 */
private val ROW_LINE_GAP = 2.dp

/** 技术明细块的缩进：让「这层是排障读数」与上方的玩家面摘要一眼分层。 */
private val TECH_INDENT = 10.dp

/** 技术明细块左侧竖轨宽度（2dp 是发丝档：标层级，不做装饰）。 */
private val TECH_RAIL_WIDTH = 2.dp

/** 竖轨与上方摘要之间留一口气，免得两条读数贴在一起像同一行折下来的。 */
private val TECH_TOP_GAP = 5.dp

/** 竖轨不透明度：分区色洗一档，够看出归属又抢不走正文。 */
private const val TECH_RAIL_ALPHA = 0.22f

/**
 * 设置页「Mod 运行时概览」卡：每个已装 Mod 一个读数块，回答「谁装载失败了 / 谁慢 / 慢在哪」。
 *
 * ## 两层信息（2026-11-27 改版：默认收起）
 * - **玩家面**（收起也可见）：标题行计数徽标「运行中 n/m」+ 装载失败的包点名与原因。玩家只需要
 *   知道「谁在跑、谁没起来」，失败原因要能直接读到，因为它就是「为什么装了却没用」的答案。
 * - **作者面**（展开才渲染）：逐包累计耗时、慢处理器、最慢作用域、存储足迹、设置已改项数、
 *   注册面字段串。这些是排障读数，不该在默认高度里压满一屏 —— 之前 11 行裸字竖排，三四个
 *   Mod 就把设置页顶穿，玩家还得一路滚过去。
 *
 * 收起态用 [rememberSaveable]，转屏 / 换档不重置；折叠箭头与正文揭示同拍（壳层
 * [ShellMotion] 口径），点击区是整条标题行（≥ [ShellSpace.RowMinHeight]，满足触控下限）。
 *
 * ## 数据来源（全部只读）
 * - 已装清单：与上方「已安装的 Mod」卡同一次 [ModManageScan]（防「列表 N / 概览 M」分叉）；
 * - 装载失败原因：`ModManageDiagnostics.snapshot()`（装配期 `logSink` 旁路解析，含构造期失败）；
 * - 运行时读数：经 [ModRuntimeOverviewEntry] 现读活会话的 timing 账本与慢处理器预算。
 *
 * ## 隐藏与刷新
 * 账本为 null（Mod 运行时总闸关 / 手工装配的宿主）⇒ 整块不渲染，与「关态零读数」同口径；
 * 没有已装 Mod 时保留卡片与一行空态（无折叠，收起一张空卡没有意义）。刷新沿用设置页既有重组
 * 时机（页进入 / 列表或失败表变化），本卡不新增定时器、不轮询会话。
 */
@Composable
internal fun ModRuntimeOverviewCard(
    text: SettingsStrings,
    scan: ModManageScan,
    failures: Map<String, String>,
) {
    val packageIds = remember(scan) { scan.mods.map { it.packageId } }
    // 现读（组合期一次）：账本 null ⇒ read 返回 null ⇒ build 返回 null ⇒ 整块隐藏。
    val overview = remember(scan, failures) {
        buildModRuntimeOverview(packageIds, failures, ModRuntimeOverviewEntry.read(packageIds))
    }
    if (overview == null) return
    val rows = overview.rows
    // 空态不挂折叠：标题 + 一句空态就够，箭头点下去什么都不变会显得卡坏了。
    if (rows.isEmpty()) {
        SettingsCard {
            SectionTitle(text.modRuntimeOverviewTitle)
            ShellCaption(text.modRuntimeOverviewHint)
            Text(
                text = text.modRuntimeOverviewEmpty,
                color = EratwInk.textMuted(),
                style = ShellType.Caption,
                modifier = Modifier.padding(top = ShellSpace.CardGap),
            )
        }
        return
    }
    val activeCount = rows.count { it.active }
    var expanded by rememberSaveable { mutableStateOf(false) }
    ShellSurfaceCard(accent = LocalZoneAccent.current) {
        ShellDisclosureHeader(
            title = text.modRuntimeOverviewTitle,
            expanded = expanded,
            onToggle = { expanded = !expanded },
            accent = LocalZoneAccent.current,
            badge = text.modRuntimeOverviewBadge(activeCount, rows.size),
        )
        ShellCaption(text.modRuntimeOverviewHint)
        // 收起态：只把「没起来的是谁、为什么」提到折叠外。展开后由逐行详情承担，
        // 此处不再重复（否则同一句失败原因在一张卡里出现两遍）。
        if (!expanded) {
            rows.forEach { row ->
                if (row.failure != null) ModRuntimeFailurePreview(text = text, row = row)
            }
        }
        ShellVisibility(visible = expanded) {
            rows.forEachIndexed { index, row ->
                // 只有第二条起才插线：首条紧贴说明句，顶部再来一条线等于给卡内开了个假分节。
                if (index > 0) ShellRowHairline()
                ModRuntimeRowView(text = text, row = row)
            }
        }
    }
}

/**
 * 收起态的失败预览：包名一行 + 「装载失败：<原因>」一行，同走警示色。
 *
 * 不拼「包名 · 原因」的裸分隔符：包名是 ASCII 标识，原因可能带冒号/括号，两行分开读更稳，
 * 也让窄屏不必为一句话做折行判断。
 */
@Composable
private fun ModRuntimeFailurePreview(text: SettingsStrings, row: ModRuntimeRow) {
    val warn = EratwInk.warn()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(ROW_LINE_GAP),
    ) {
        Text(text = row.packageId, color = warn, style = ShellType.Label)
        Text(
            text = failureLine(text, row),
            color = warn,
            style = ShellType.Caption,
            modifier = Modifier.padding(start = TECH_INDENT),
        )
    }
}

/**
 * 单个 Mod 的读数块（展开态）：标题行（包名 + 状态徽章）+ 玩家面摘要 + 技术明细轨。
 *
 * 分层依据是「谁需要读」：状态徽章与累计耗时是玩家能懂的「这个 Mod 重不重」，走 Caption 级
 * 次级灰；作用域 / 存储 / 设置 / 注册面是作者排障面，统一落进左侧竖轨的 [ShellType.Label] 更暗一档 ——
 * 视觉上「一列读数」而不是「十一条并列结论」，出错行仍由色与文案两路一起标识（不只靠颜色）。
 */
@Composable
private fun ModRuntimeRowView(text: SettingsStrings, row: ModRuntimeRow) {
    val failed = row.failure != null
    val warn = EratwInk.warn()
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
        verticalArrangement = Arrangement.spacedBy(ROW_LINE_GAP),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.packageId,
                color = if (failed) warn else EratwInk.text(),
                style = ShellType.Body,
                modifier = Modifier.weight(1f),
            )
            ShellListBadge(
                text = if (row.active) text.modRuntimeOverviewActive else text.modRuntimeOverviewInactive,
                // 装载失败时徽章跟着转警示色：颜色 + 文案两路都在，读屏与色弱都能分辨。
                tone = if (failed) warn else EratwInk.textDim(),
            )
        }
        if (failed) {
            Text(text = failureLine(text, row), color = warn, style = ShellType.Label)
        }
        ModRuntimeLine(text = text.modRuntimeOverviewTotals(row.totalCount, row.totalMillis))
        // 慢栏 >0 才出现（0 = 不显示，避免每行都挂一条「无」噪音）；阈值随行给出，读数可自证。
        if (showsSlowLine(row)) {
            ModRuntimeLine(
                text = text.modRuntimeOverviewSlow(row.slowCount, row.slowThresholdMillis),
                tone = warn,
            )
        }
        ModRuntimeTechBlock {
            for (scope in row.topScopes) {
                ModRuntimeTechLine(text.modRuntimeOverviewScope(scope.scope, scope.count, scope.totalMillis))
            }
            // 存储段恒在：缺段显式「—」（不是「0 B」——没段与空段是两件事），有段给「字节 · 键数」。
            val storage = row.storage
            ModRuntimeTechLine(
                text = if (storage == null) {
                    text.modRuntimeOverviewStorageMissing
                } else {
                    text.modRuntimeOverviewStorage(formatStorageBytes(storage.bytes), storage.keys)
                },
            )
            // 超限标记只在越限时出现（判定在 ModRuntimeRow.showsStorageWarning 纯只读属性里）；
            // 紧跟存储行，Warn 色沿用慢栏语义。
            if (row.showsStorageWarning) {
                ModRuntimeTechLine(text = text.modRuntimeOverviewStorageOver, tone = warn)
            }
            // 设置项读数恒在：缺会话快照显式「—」，有快照给相对声明默认的已改项数（0 也照渲染）。
            ModRuntimeTechLine(
                text = row.settingsChanged?.let { text.modRuntimeOverviewSettingsChanged(it) }
                    ?: text.modRuntimeOverviewSettingsChangedMissing,
            )
            // 注册面恒在（全零也渲染：0 = 「没注册上」，正是作者最需要看到的信息）。
            ModRuntimeTechLine(text = text.modRuntimeOverviewFaces(row.faces))
        }
    }
}

/**
 * 技术明细块：左侧一条分区色发丝竖轨 + 10dp 缩进，内部按行距堆读数。
 *
 * 竖轨用 `drawBehind` 画在自身边界内，不走 `IntrinsicSize` / `fillMaxHeight` —— 后者会多一次
 * 测量遍历，这里只多一笔绘制；整块宽度仍由父列决定，不引入额外布局层级。
 */
@Composable
private fun ModRuntimeTechBlock(
    content: @Composable ColumnScope.() -> Unit,
) {
    val rail = LocalZoneAccent.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = TECH_TOP_GAP)
            .drawBehind {
                drawRect(
                    color = rail.copy(alpha = TECH_RAIL_ALPHA),
                    topLeft = Offset.Zero,
                    size = Size(TECH_RAIL_WIDTH.toPx(), size.height),
                )
            }
            .padding(start = TECH_INDENT),
        verticalArrangement = Arrangement.spacedBy(ROW_LINE_GAP),
        content = content,
    )
}

/** 一行玩家面读数：正文下一档（Caption）的次级灰，[tone] 供警示行覆盖。 */
@Composable
private fun ModRuntimeLine(
    text: String,
    tone: Color = EratwInk.textMuted(),
) {
    Text(text = text, color = tone, style = ShellType.Caption)
}

/** 一行技术明细：再降一档（Label + dim），缩进由 [ModRuntimeTechBlock] 给。 */
@Composable
private fun ModRuntimeTechLine(
    text: String,
    tone: Color = EratwInk.textDim(),
) {
    Text(text = text, color = tone, style = ShellType.Label)
}
