package com.eratw.port.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.ShellListBadge
import com.eratw.port.android.ui.design.ShellRowHairline
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellType

/** 读数块内各行之间的行距：密度面留 2dp，靠行距而非空行分组。 */
private val ROW_LINE_GAP = 2.dp

/** 作用域缩进：让「合计」与「按作用域拆开」在竖向上分得出层级。 */
private val SCOPE_INDENT = 10.dp

/**
 * 设置页「Mod 运行时概览」卡：每个已装 Mod 一行，回答「谁装载失败了 / 谁慢 / 慢在哪」。
 *
 * ## 数据来源（全部只读）
 * - 已装清单：与上方「已安装的 Mod」卡同一次 [ModManageScan]（防「列表 N / 概览 M」分叉）；
 * - 装载失败原因：`ModManageDiagnostics.snapshot()`（装配期 `logSink` 旁路解析，含构造期失败）；
 * - 运行时读数：经 [ModRuntimeOverviewEntry] 现读活会话的 timing 账本与慢处理器预算。
 *
 * ## 隐藏与刷新
 * 账本为 null（Mod 运行时总闸关 / 手工装配的宿主）⇒ 整块不渲染，与「关态零读数」同口径；
 * 没有已装 Mod 时保留卡片与一行空态。刷新沿用设置页既有重组时机（页进入 / 列表或失败表变化），
 * 本卡不新增定时器、不轮询会话。
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
    SettingsCard {
        SectionTitle(text.modRuntimeOverviewTitle)
        Text(
            text = text.modRuntimeOverviewHint,
            color = EratwInk.textMuted(),
            style = ShellType.Label,
        )
        if (overview.rows.isEmpty()) {
            Text(
                text = text.modRuntimeOverviewEmpty,
                color = EratwInk.textMuted(),
                style = ShellType.Caption,
                modifier = Modifier.padding(top = ShellSpace.CardGap),
            )
        }
        overview.rows.forEachIndexed { index, row ->
            // 只有第二条起才插线：首条紧贴说明句，顶部再来一条线等于给卡内开了个假分节。
            if (index > 0) ShellRowHairline()
            ModRuntimeRowView(text = text, row = row)
        }
    }
}

/**
 * 单个 Mod 的读数块：标题行（包名 + 运行态徽章）+ 失败栏 + 累计耗时 + 最慢 top 3 作用域
 * +（>0 才有的）慢栏 + 注册面。
 *
 * 旧版是十一条 11sp 裸字竖排、只靠 4~6dp 间距分组，屏上一屏能压三四个 Mod 却分不清哪几行属于谁；
 * 现在标题行用正文色 + 静态徽章立住「这是谁」，其余读数降到 Label 灰，出错行由色与文案一起标识
 * （不只靠颜色）。
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
        // 失败栏恒在（空 = 「无」），不让玩家把「没显示」误读成「没失败」。
        ModRuntimeLine(text = failureLine(text, row), tone = if (failed) warn else EratwInk.textMuted())
        ModRuntimeLine(text = text.modRuntimeOverviewTotals(row.totalCount, row.totalMillis))
        for (scope in row.topScopes) {
            ModRuntimeLine(
                text = text.modRuntimeOverviewScope(scope.scope, scope.count, scope.totalMillis),
                indent = true,
            )
        }
        // 慢栏 >0 才出现（0 = 不显示，避免每行都挂一条「无」噪音）；阈值随行给出，读数可自证。
        if (showsSlowLine(row)) {
            ModRuntimeLine(
                text = text.modRuntimeOverviewSlow(row.slowCount, row.slowThresholdMillis),
                tone = warn,
            )
        }
        // 存储段恒在：缺段显式「—」（不是「0 B」——没段与空段是两件事），有段给「字节 · 键数」。
        val storage = row.storage
        ModRuntimeLine(
            text = if (storage == null) {
                text.modRuntimeOverviewStorageMissing
            } else {
                text.modRuntimeOverviewStorage(formatStorageBytes(storage.bytes), storage.keys)
            },
        )
        // 超限标记只在越限时出现（判定在 ModRuntimeRow.showsStorageWarning 纯只读属性里）；Warn 色沿用慢栏语义。
        if (row.showsStorageWarning) {
            ModRuntimeLine(text = text.modRuntimeOverviewStorageOver, tone = warn)
        }
        // 设置项读数恒在：缺会话快照显式「—」，有快照给相对声明默认的已改项数（0 也照渲染）。
        ModRuntimeLine(
            text = row.settingsChanged?.let { text.modRuntimeOverviewSettingsChanged(it) }
                ?: text.modRuntimeOverviewSettingsChangedMissing,
        )
        // 注册面恒在（全零也渲染：0 = 「没注册上」，正是作者最需要看到的信息）。
        ModRuntimeLine(text = text.modRuntimeOverviewFaces(row.faces))
    }
}

/** 一行读数：默认次级灰、可选警示色与作用域缩进，字阶统一取 `ShellType.Label`。 */
@Composable
private fun ModRuntimeLine(
    text: String,
    tone: Color = EratwInk.textMuted(),
    indent: Boolean = false,
) {
    Text(
        text = text,
        color = tone,
        style = ShellType.Label,
        modifier = if (indent) Modifier.padding(start = SCOPE_INDENT) else Modifier,
    )
}
