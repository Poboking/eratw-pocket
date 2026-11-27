package com.eratw.port.android.market

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ModImportCopy
import com.eratw.port.android.modImportFailureTitle
import com.eratw.port.android.ui.EratwButtonRow
import com.eratw.port.android.ui.EratwDialogCard
import com.eratw.port.android.ui.EratwFilledButton
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.EratwText
import com.eratw.port.android.ui.EratwTextTone
import com.eratw.port.android.ui.design.ShellButton
import com.eratw.port.android.ui.design.ShellDisclosureHeader
import com.eratw.port.android.ui.design.ShellEdgeFades
import com.eratw.port.android.ui.design.ShellField
import com.eratw.port.android.ui.design.ShellFilterChip
import com.eratw.port.android.ui.design.ShellGroupHeader
import com.eratw.port.android.ui.design.ShellListBadge
import com.eratw.port.android.ui.design.ShellListRow
import com.eratw.port.android.ui.design.ShellMotion
import com.eratw.port.android.ui.design.ShellProgress
import com.eratw.port.android.ui.design.ShellSkeletonRow
import com.eratw.port.android.ui.design.ShellSideFades
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.android.ui.design.ShellVisibility

/**
 * 市场全屏覆盖层（CONTRACT-MKM-W1 §2 mkm-ui 判据 3）。外壳照 [com.eratw.port.android.ModImportChrome]
 * 的挂载形态（宿主 = SettingsActivity，系统栏可见 ⇒ `immersive = false`、点外不关）。
 *
 * **渲染零判定**：显式态、行动作、话术、进度全部来自 [MarketViewModel.MarketUiState]
 * （[MarketViewModel.reduce] 的产物）与 [MarketCopy]；本文件的 Compose 层只做
 * 「按这个 copy 出词、按这个状态摆位」。两段式刷新、单键互斥、失败主动重读的**驱动**在宿主
 * （market-panel.ts:207-223/:244-307 的壳层口径），状态转移全部走 [MarketViewModel.reduce]。
 */
internal data class MarketChromeActions(
    val onDismiss: () -> Unit,
    val onPull: () -> Unit,
    val onFilterKind: (String?) -> Unit,
    val onInstall: (MarketViewModel.MarketRow) -> Unit,
    /** 已装行的「装进游戏」（CONTRACT-MKM-W3-UI2：桥按钮动作链在宿主，装无确认门）。 */
    val onBridgeImport: (MarketViewModel.MarketInstalledRow) -> Unit,
    val onUninstallRequest: (MarketViewModel.MarketInstalledRow) -> Unit,
    val onUninstallConfirm: () -> Unit,
    val onUninstallCancel: () -> Unit,
    val onEndpointDraftChange: (String) -> Unit,
    val onEndpointSave: () -> Unit,
    val onEndpointReset: () -> Unit,
    /**
     * 已装区的「清理旧版本」手动入口（CONTRACT-MKM-W5-GC 余项 D3）。null = 宿主未接线——
     * 入口不出现（不演死按钮）。接线形态：后台线程调实现类方法 `MarketServiceImpl
     * .sweepSuperseded()`（与 install/uninstall 同一把 mutationLock 单飞），回执发
     * [MarketViewModel.MarketUiEvent.SweepFinished]，随后零网络重读发
     * [MarketViewModel.MarketUiEvent.InstalledRevalidated]（占用读数收敛到清扫后的盘面）。
     */
    val onSweep: (() -> Unit)? = null,
    /**
     * 收藏 toggle（CONTRACT-MKM-STATS-UI §1 D2）：宿主改本地收藏集 + 落盘真源，回执发
     * [MarketViewModel.MarketUiEvent.FavoriteToggled]。收藏是纯本地操作，不占 installingKey
     * 互斥（与装/卸/清扫那把 mutationLock 无交集）。
     */
    val onToggleFavorite: (MarketViewModel.MarketRow) -> Unit,
    /** 收藏过滤档 toggle（纯显示面，零 IO）。 */
    val onFavoritesOnly: (Boolean) -> Unit,
)

@Composable
internal fun MarketChrome(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
    serviceReady: Boolean,
) {
    EratwDialogCard(
        onDismissRequest = actions.onDismiss,
        dismissOnClickOutside = false,
        maxWidth = MARKET_CARD_MAX_WIDTH,
        maxHeight = MARKET_CARD_MAX_HEIGHT,
        scrollable = false,
        immersive = false,
    ) {
        EratwText(text = copy.cardTitle, tone = EratwTextTone.HEADLINE)
        EratwText(text = copy.subtitle, tone = EratwTextTone.NOTE)
        MarketRowParts.MarketNoteLine(state = state)
        if (!serviceReady) {
            // 服务未装配的诚实态（market-source.ts NO_SERVICE_VIEW :144-149：一句实话，不是空输入框假装能用）
            EratwText(text = copy.serviceMissing, tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
            Spacer(Modifier.height(8.dp))
            EratwButtonRow { EratwGhostButton(text = copy.closeButton, onClick = actions.onDismiss) }
            return@EratwDialogCard
        }
        // 页头收边：标题块与筛选/列表之间原来只是一段空白，三行文字直接压到 chips 带上，
        // 读不出「上面是这张卡的抬头」。一条发丝线把抬头与名单分开，不动排版只定层级。
        Spacer(Modifier.height(10.dp))
        MarketHairline()
        Spacer(Modifier.height(10.dp))
        MarketFilterChips(state = state, copy = copy, actions = actions)
        Spacer(Modifier.height(ShellSpace.CardGap))
        MarketScrollArea(state = state, copy = copy, actions = actions, modifier = Modifier.weight(1f))
        Spacer(Modifier.height(10.dp))
        // 页脚收边：底栏是固定区（拉取 / 关闭），不该和滚动内容糊在同一片黑里。
        MarketHairline()
        Spacer(Modifier.height(10.dp))
        MarketFooter(state = state, copy = copy, actions = actions)
    }
    MarketUninstallDialog(state = state, copy = copy, actions = actions)
}

/** 覆盖层内的横向发丝线（抬头 / 底栏各一条）：比卡片描边亮一档、比正文弱三档。 */
@Composable
private fun MarketHairline() {
    HorizontalDivider(color = EratwInk.hairline(), thickness = ShellSpace.Hairline)
}

/** 过滤 chips（全部 + 收藏档 + 四 kind；gameId 只作徽章展示不过滤，契约 §0 裁定 7）。 */
@Composable
private fun MarketFilterChips(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    // 原来 kind 档与收藏档各占一行：两排 44dp 的方片叠起来是一堵墙，把名单又压低一行。
    // 现在并成一条横向可滚的单排——档位是会随收藏集出现/消失的「一串」，不是两段版面；
    // 收藏档与 kind 档之间加一道竖发丝，交代「这两个维度可以叠加」而不是同一组里的邻居。
    // 窄屏上这排必溢出，右端挂一条侧边渐隐：档位被硬切半枚比「还有档位」更难看。
    val chipScroll = rememberScrollState()
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(chipScroll),
            horizontalArrangement = Arrangement.spacedBy(MARKET_CHIP_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarketRowParts.MarketChip(
                text = copy.filterAll,
                selected = state.filterKind == null,
                onClick = { actions.onFilterKind(null) },
            )
            for (kind in MarketContract.KINDS) {
                MarketRowParts.MarketChip(
                    text = MarketOptimistic.kindLabel(copy, kind),
                    selected = state.filterKind == kind,
                    onClick = { actions.onFilterKind(kind) },
                )
            }
            // 收藏档（CONTRACT-MKM-STATS-UI §1 D2）：与 kind 维度可叠加的独立 toggle，
            // 空收藏集整档不摆（空集下过滤恒空组，摆出来只会让玩家看到一屏空白）。
            if (state.favorites.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(MARKET_CHIP_RULE_HEIGHT)
                        .background(EratwInk.border()),
                )
                MarketRowParts.MarketChip(
                    text = copy.filterFavorites,
                    selected = state.favoritesOnly,
                    onClick = { actions.onFavoritesOnly(!state.favoritesOnly) },
                )
            }
        }
        ShellSideFades(
            start = chipScroll.canScrollBackward,
            end = chipScroll.canScrollForward,
            color = EratwInk.raised(),
        )
    }
}

/** 覆盖层的中段（索引区 + 已装区 + 端点行）共用一根滚动轴，超出封顶高度自己滚。 */
@Composable
private fun MarketScrollArea(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            MarketIndexSection(state = state, copy = copy, actions = actions)
            Spacer(Modifier.height(MARKET_SECTION_GAP))
            MarketInstalledSection(state = state, copy = copy, actions = actions)
            Spacer(Modifier.height(MARKET_SECTION_GAP))
            MarketEndpointSection(state = state, copy = copy, actions = actions)
        }
        // 名单滚到上下边界时，行卡会在半高状态被硬裁断（读成「切了一刀」）；两条渐隐把
        // 「还有内容」讲成淡出。底色取卡面色而不是页面黑底，否则会在卡上糊出一条亮带。
        ShellEdgeFades(
            top = scrollState.canScrollBackward,
            bottom = scrollState.canScrollForward,
            color = EratwInk.raised(),
        )
    }
}

/** 索引区：三态显式句（market-view.ts resolveStatus :267-276）+ 出声的两类剔除告示 + 分组列表。 */
@Composable
private fun MarketIndexSection(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    val snap = state.snapshot
    if (snap == null) {
        // 首读本机列表：一句实话 + 三行呼吸占位。原来只有一句 11sp 小字，卡体空一片，
        // 看不出「正在读」而像「什么都没有」；占位与设置页首扫同一档（ShellSkeletonRow）。
        EratwText(text = copy.indexLoading, tone = EratwTextTone.NOTE)
        Spacer(Modifier.height(ShellSpace.CardGap))
        ShellSkeletonRow(lines = MARKET_SKELETON_LINES)
        return
    }
    when (val idx = snap.index) {
        is MarketIndexState.Invalid ->
            EratwText(
                text = copy.fill(copy.statusInvalidFormat, idx.problem),
                tone = EratwTextTone.NOTE,
                colorOverride = EratwInk.amber(),
            )
        MarketIndexState.Absent -> EratwText(text = copy.statusAbsent, tone = EratwTextTone.NOTE)
        is MarketIndexState.Ok -> {
            EratwText(text = MarketPhrases.syncTimePhrase(copy, idx), tone = EratwTextTone.MUTED)
            if (idx.index.packs.isEmpty()) {
                EratwText(text = copy.statusNoPacks, tone = EratwTextTone.NOTE)
                return
            }
            MarketPhrases.droppedNotice(copy, idx.index.dropped).takeIf { it.isNotEmpty() }?.let {
                EratwText(text = it, tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
            }
            MarketPhrases.coverIgnoredNotice(copy, idx.index.coverIgnored).takeIf { it.isNotEmpty() }?.let {
                EratwText(text = it, tone = EratwTextTone.NOTE, colorOverride = EratwInk.amber())
            }
            for (group in state.groups) {
                MarketGroupSection(group = group, state = state, copy = copy, actions = actions)
            }
        }
    }
}

@Composable
private fun MarketGroupSection(
    group: MarketViewModel.MarketGroup,
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    ShellGroupHeader(
        title = group.label,
        subtitle = MarketReadouts.groupCountPhrase(copy, group.rows.size),
        accent = EratwInk.accent(),
    )
    Spacer(Modifier.height(ShellSpace.CardGap))
    for (row in group.rows) {
        MarketRowParts.MarketPackRow(row = row, state = state, copy = copy, actions = actions)
        Spacer(Modifier.height(ShellSpace.CardGap))
    }
}

/** 已装管理区（契约 §2 mkm-ui 判据 3：列表 + 占用读数 + 空态两句 + 卸载确认）。 */
@Composable
private fun MarketInstalledSection(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    val snap = state.snapshot ?: return
    // 手动清扫入口（CONTRACT-MKM-W5-GC 余项 D3）：只在宿主接线后出现（onSweep = null 不演
    // 死按钮），库里没包不摆（空库没得清；孤儿目录的清扫由开设置页的启动触发兜底）。
    // 与装/更新/卸/桥共用 installingKey 一把单飞闸（服务端同一把 mutationLock）。
    val onSweep = actions.onSweep
    val sweepSlot: (@Composable () -> Unit)? = if (onSweep != null && snap.installed.isNotEmpty()) {
        {
            EratwGhostButton(
                text = copy.sweepButton,
                onClick = onSweep,
                enabled = MarketBridgeUi.bridgeImportEnabled(state),
            )
        }
    } else {
        null
    }
    // 标题统一走共享分组头（与上方各 kind 分组同一副骨），清扫动作挂标题行右端 —— 它属于「已装」
    // 这一组。计数与行集同源（CONTRACT-MKM-W4-UI3 裁定 2：按包去重后一行 = 一个包）；占用读数
    // 仍取快照全量字节 —— 被去重隐藏的旧版本目录还在盘上（W5 库内 GC 挂账），是多少就报多少。
    ShellGroupHeader(
        title = copy.installedTitle,
        subtitle = MarketReadouts.installedCountPhrase(copy, state.installedRows.size, snap.installedBytes),
        accent = EratwInk.accent(),
        trailing = sweepSlot,
    )
    Spacer(Modifier.height(ShellSpace.CardGap))
    if (snap.installed.isEmpty()) {
        // 空态两句：主句说为什么空着，副句说这一步怎么做（market-view.ts INSTALLED_EMPTY :546-549）
        EratwText(text = copy.installedEmptyTitle, tone = EratwTextTone.NOTE)
        EratwText(text = copy.installedEmptyHint, tone = EratwTextTone.MUTED)
        return
    }
    for (row in state.installedRows) {
        MarketRowParts.MarketInstalledCard(row = row, state = state, copy = copy, actions = actions)
        Spacer(Modifier.height(ShellSpace.CardGap))
    }
}

/** 端点编辑行（market-source.ts：真相只在 [MarketService.endpoint]，无代理输入框是裁定口径）。 */
@Composable
private fun MarketEndpointSection(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    val endpoint = state.snapshot?.endpoint ?: return
    // 端点编辑是「改一个源地址」的高级操作，不是市场页的主业。原来它常驻在名单末尾：
    // 三句说明 + 一串地址 + 输入框 + 两颗按钮，把整页最低处压成一块设置面板，既抢名单的
    // 视线，也和「浏览 / 安装」的主语气不是一回事。现在默认收成一行：标题 + 当前档位；
    // 展开才出正文。收起态那句话照旧在（玩家不会以为这里还是默认源）。
    var expanded by rememberSaveable { mutableStateOf(false) }
    ShellDisclosureHeader(
        title = copy.endpointTitle,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        accent = EratwInk.accent(),
        badge = MarketEndpointUi.endpointStatus(copy, endpoint),
    )
    ShellVisibility(
        visible = expanded,
        enter = ShellMotion.disclosureEnter(),
        exit = ShellMotion.disclosureExit(),
    ) {
        EratwText(text = copy.endpointIntro, tone = EratwTextTone.NOTE)
        Spacer(Modifier.height(MARKET_FIELD_GAP))
        // 显式读数：当前生效的那串地址（market-source.ts SourceRowUi.address :89）
        EratwText(text = endpoint, tone = EratwTextTone.MUTED)
        Spacer(Modifier.height(MARKET_FIELD_GAP))
        OutlinedTextField(
            value = state.endpointDraft,
            onValueChange = actions.onEndpointDraftChange,
            singleLine = true,
            textStyle = ShellType.Body,
            colors = ShellField.outlined(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(MARKET_FIELD_GAP))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EratwFilledButton(
                text = copy.endpointSave,
                onClick = actions.onEndpointSave,
                enabled = state.installingKey == null,
            )
            // 恢复默认走同一条保存通路（空草稿 = reset，market-source.ts :189-196）；只在覆盖态出现（showReset :129）
            if (MarketEndpointUi.isOverridden(endpoint)) {
                EratwGhostButton(
                    text = copy.endpointReset,
                    onClick = actions.onEndpointReset,
                    enabled = state.installingKey == null,
                )
            }
        }
    }
}

@Composable
private fun MarketFooter(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    EratwButtonRow {
        EratwFilledButton(text = copy.pullButton, onClick = actions.onPull, enabled = !state.busy)
        Spacer(Modifier.weight(1f))
        EratwGhostButton(text = copy.closeButton, onClick = actions.onDismiss)
    }
}

/** 卸载确认弹窗：一次确认门（actions.ts runUninstall :237-254——未确认前一个字节都不动、连服务都不叫）。 */
@Composable
private fun MarketUninstallDialog(
    state: MarketViewModel.MarketUiState,
    copy: MarketCopy,
    actions: MarketChromeActions,
) {
    val pending = state.pendingUninstall ?: return
    EratwDialogCard(
        onDismissRequest = actions.onUninstallCancel,
        maxWidth = MARKET_CONFIRM_MAX_WIDTH,
        maxHeight = MARKET_CONFIRM_MAX_HEIGHT,
        immersive = false,
    ) {
        EratwText(
            text = copy.fill(copy.uninstallConfirmTitleFormat, pending.rec.name),
            tone = EratwTextTone.HEADLINE,
        )
        EratwText(text = copy.uninstallConfirmBody, tone = EratwTextTone.NOTE)
        Spacer(Modifier.height(8.dp))
        EratwButtonRow {
            EratwGhostButton(text = copy.uninstallConfirmCancel, onClick = actions.onUninstallCancel)
            EratwFilledButton(
                text = copy.uninstallConfirmOk,
                onClick = actions.onUninstallConfirm,
                enabled = state.installingKey == null,
                container = EratwInk.danger(),
            )
        }
    }
}

/**
 * 行级小组件（行卡片 / 徽章 / chips / 进度条）：与分区级组件分一组，摆位与判定照旧全在
 * 状态与话术函数里，这里只渲染。
 */
private object MarketRowParts {

    /** 内联状态行那一句（market-view.ts 的 mk-note 口径：一切正常不该有废话，空串不占行）。 */
    @Composable
    fun MarketNoteLine(state: MarketViewModel.MarketUiState) {
        if (state.note.isBlank()) return
        EratwText(
            text = state.note,
            tone = EratwTextTone.NOTE,
            colorOverride = if (state.noteWarn) EratwInk.danger() else null,
        )
    }

    /** 过滤 chip：选中档亮底亮边，未选中档发丝边（骨架在 `ui/design/ShellFilterChip`）。 */
    @Composable
    fun MarketChip(text: String, selected: Boolean, onClick: () -> Unit) {
        ShellFilterChip(text = text, selected = selected, onClick = onClick)
    }

    /**
     * 包功能卡（CONTRACT-MKM-STATS-UI §2 UI 打磨）：名 / kind·gameId 徽章 / 元信息行 /
     * **统计行**（下载量 + 收藏 toggle）/ 行动作与进度。摆位与判定照旧全在状态与话术函数里，
     * 这里只渲染；安装/更新/展开行为零改动（MARKM 既有判据不碰）。
     */
    @Composable
    fun MarketPackRow(
        row: MarketViewModel.MarketRow,
        state: MarketViewModel.MarketUiState,
        copy: MarketCopy,
        actions: MarketChromeActions,
    ) {
        val key = MarketViewModel.indexRowKey(row.entry)
        var expanded by remember(key) { mutableStateOf(false) }
        ShellListRow(onClick = { expanded = !expanded }) {
            MarketRowHeader(row = row, copy = copy)
            Spacer(Modifier.height(3.dp))
            EratwText(
                text = copy.fill(
                    copy.rowMetaFormat,
                    row.entry.author,
                    row.entry.version,
                    MarketReadouts.formatBytes(row.entry.zipBytes),
                ),
                tone = EratwTextTone.NOTE,
            )
            MarketStatsLine(row = row, state = state, copy = copy, actions = actions)
            MarketRowActionArea(row = row, state = state, copy = copy, actions = actions)
            // 详情是行级的二级内容：展开/收起走与设置页行级展开同一档节奏（rowEnter/Exit），
            // 不再整块瞬移；容器仍是 ShellVisibility，正文永远一列，不会像第十三批那样叠放。
            ShellVisibility(
                visible = expanded,
                enter = ShellMotion.rowEnter(),
                exit = ShellMotion.rowExit(),
            ) {
                Spacer(Modifier.height(MARKET_FIELD_GAP))
                MarketRowDetails(row = row, copy = copy)
            }
        }
    }

    /**
     * 统计行（CONTRACT-MKM-STATS-UI §1 D1/D2）：下载量短语（无数据 = 「—」，不编假数）+
     * 收藏 toggle（文字态钮，不占安装互斥——纯本地操作）。统计行在元信息下、动作上，
     * 阅读序 = 是什么 → 多少人在用 → 我要不要收藏 → 装不装。
     */
    @Composable
    private fun MarketStatsLine(
        row: MarketViewModel.MarketRow,
        state: MarketViewModel.MarketUiState,
        copy: MarketCopy,
        actions: MarketChromeActions,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val downloads = MarketStatsUi.downloadsFor(state.stats, row.entry.zipUrl)
            EratwText(
                text = MarketReadouts.downloadCountPhrase(copy, downloads),
                tone = EratwTextTone.MUTED,
            )
            Spacer(Modifier.weight(1f))
            val favorited = MarketContract.favoriteKey(row.entry.kind, row.entry.id) in state.favorites
            // 收藏是纯本地的次行动（不动包、不走网络）：原来它是描边钮，和下方「安装」的实心钮
            // 抢同一层的重量，一行里出现两颗框钮像并排的两个主动作。现在降成文本钮 ——
            // 有框的只留主行动；已收藏靠「字换 + 字色转 accent」两个信号，不只靠颜色。
            TextButton(
                onClick = { actions.onToggleFavorite(row) },
                modifier = Modifier.heightIn(min = ShellSpace.RowMinHeight),
                contentPadding = ShellButton.CompactPadding,
                colors = ShellButton.text(if (favorited) EratwInk.accent() else EratwInk.textDim()),
            ) {
                Text(
                    text = if (favorited) copy.favoriteAdded else copy.favoriteAdd,
                    style = ShellType.Label,
                )
            }
        }
    }

    /** 行头：包名 + kind 徽章 + gameId 徽章（gameId 只作徽章展示，契约 §0 裁定 7）。 */
    @Composable
    private fun MarketRowHeader(row: MarketViewModel.MarketRow, copy: MarketCopy) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EratwText(text = row.entry.name, tone = EratwTextTone.BODY, modifier = Modifier.weight(1f))
            MarketBadge(text = MarketOptimistic.kindLabel(copy, row.entry.kind))
            row.entry.gameId?.let { gid ->
                Spacer(Modifier.width(4.dp))
                MarketBadge(text = gid)
            }
        }
    }

    /** 行的动作区：提示行 / 进度条 / 行动按钮（none 行不放按钮，market-view.ts :471-476）。 */
    @Composable
    private fun MarketRowActionArea(
        row: MarketViewModel.MarketRow,
        state: MarketViewModel.MarketUiState,
        copy: MarketCopy,
        actions: MarketChromeActions,
    ) {
        val key = MarketViewModel.indexRowKey(row.entry)
        val installingThis = state.installingKey == key
        // 提示行：版本闸不过时是那句要求（amber），装过/可更新/本机更新时是更新事实（:451-468）
        val phrase = if (row.appOk) MarketPhrases.updatePhrase(copy, row) else {
            copy.fill(copy.minAppVersionFormat, row.entry.minAppVersion)
        }
        if (phrase.isNotEmpty()) {
            EratwText(
                text = phrase,
                tone = EratwTextTone.NOTE,
                colorOverride = if (row.appOk) null else EratwInk.amber(),
            )
        }
        if (installingThis) {
            Spacer(Modifier.height(MARKET_FIELD_GAP))
            MarketProgressBar(progress = state.progress)
        }
        val actionText = when (row.action) {
            MarketViewModel.MarketRowAction.INSTALL -> copy.actionInstall
            MarketViewModel.MarketRowAction.UPDATE -> copy.actionUpdate
            MarketViewModel.MarketRowAction.NONE -> null
        }
        // 正在装的那一行不再摆按钮：进度带下面跟一颗「安装」灰钮，既点不动又读成「尚未开始」；
        // 忙态本身已由进度带与禁用态交代（别的行的按钮仍禁用，那是另一件事）。
        if (actionText != null && !installingThis) {
            Spacer(Modifier.height(MARKET_FIELD_GAP))
            EratwFilledButton(
                text = actionText,
                onClick = { actions.onInstall(row) },
                enabled = state.installingKey == null,
            )
        }
    }

    /** 安装进度条：总数已知给确定性比例，未知给匀速扫描（不编假百分比，market-panel.ts:253-260）。 */
    @Composable
    fun MarketProgressBar(progress: MarketViewModel.MarketProgress?) {
        val frac = progress?.takeIf { it.totalBytes > 0 }?.let {
            (it.doneBytes.toFloat() / it.totalBytes.toFloat()).coerceIn(0f, 1f)
        }
        // 轨道与带高交给 ShellProgress：与设置页首扫、口上卡读清单是同一档细带。
        ShellProgress(
            accent = EratwInk.accent(),
            progress = frac,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    /** 详情展开区：license / summary / sourceUrl / 文件计数（机器读数只进这一层）。 */
    @Composable
    fun MarketRowDetails(row: MarketViewModel.MarketRow, copy: MarketCopy) {
        Column {
            EratwText(text = copy.detailTitle, tone = EratwTextTone.NOTE)
            EratwText(text = copy.fill(copy.detailLicenseFormat, row.entry.license), tone = EratwTextTone.NOTE)
            row.entry.summary?.let {
                EratwText(text = copy.fill(copy.detailSummaryFormat, it), tone = EratwTextTone.NOTE)
            }
            row.entry.sourceUrl?.let {
                EratwText(text = copy.fill(copy.detailSourceFormat, it), tone = EratwTextTone.MUTED)
            }
            EratwText(
                text = copy.fill(
                    copy.detailFilesFormat,
                    row.entry.fileCount,
                    MarketReadouts.formatBytes(row.entry.unpackedBytes),
                ),
                tone = EratwTextTone.MUTED,
            )
        }
    }

    /** 已装卡：名 / 作者 / 版本 / 占用 / 来源 / 摘要前 8 位 / 卸载按钮。 */
    @Composable
    fun MarketInstalledCard(
        row: MarketViewModel.MarketInstalledRow,
        state: MarketViewModel.MarketUiState,
        copy: MarketCopy,
        actions: MarketChromeActions,
    ) {
        ShellListRow {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EratwText(text = row.rec.name, tone = EratwTextTone.BODY, modifier = Modifier.weight(1f))
                MarketBadge(text = row.kindLabel)
            }
            EratwText(
                text = copy.fill(
                    copy.rowMetaFormat,
                    row.rec.author,
                    row.rec.version,
                    MarketReadouts.formatBytes(row.rec.bytes),
                ),
                tone = EratwTextTone.NOTE,
            )
            // 来源与摘要前缀并成一行：两行 11sp 灰字上下叠会让每张已装卡多出一截，而它们
            // 都是「这条记录是从哪来的」——同一件事拆两行只是变长，不是变清楚。
            Row(verticalAlignment = Alignment.CenterVertically) {
                EratwText(text = row.sourceLabel, tone = EratwTextTone.MUTED, modifier = Modifier.weight(1f))
                row.digestPrefix?.let {
                    EratwText(text = copy.fill(copy.installedDigestFormat, it), tone = EratwTextTone.MUTED)
                }
            }
            Spacer(Modifier.height(MARKET_FIELD_GAP))
            // 装进游戏 + 卸载同行：两颗键共用 installingKey 一把单飞闸（market-panel.ts:279-283——
            // 漏了互斥会把成功演成失败；判据单源在 MarketBridgeUi.bridgeImportEnabled）。
            // 装无确认门（W3-UI2 裁定 4）：桥的旧版清理随导入自动做，不弹框。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EratwFilledButton(
                    text = copy.bridgeInstallButton,
                    onClick = { actions.onBridgeImport(row) },
                    enabled = MarketBridgeUi.bridgeImportEnabled(state),
                )
                Spacer(Modifier.weight(1f))
                EratwGhostButton(
                    text = copy.uninstallConfirmOk,
                    onClick = { actions.onUninstallRequest(row) },
                    enabled = MarketBridgeUi.bridgeImportEnabled(state),
                )
            }
        }
    }

    /** 小徽章（kind / gameId 只作展示不过滤；字符不当图标用，框里只有文字）。骨架在 `ShellListBadge`。 */
    @Composable
    private fun MarketBadge(text: String) {
        ShellListBadge(text = text)
    }
}

/**
 * 「装进游戏」的纯投影半边（CONTRACT-MKM-W3-UI2）：桥五态 → 状态行人话、忙态短语、单飞闸。
 * 与 MarketPhrases 同款纪律——纯 JVM 函数零 Android/Compose 依赖，宿主（SettingsActivity 的
 * marketBridgeImport）与定向单测共用同一条判定；Compose 层只渲染。五态的形状权威 =
 * [MarketBridgeOutcome]（封闭密封族「五态不许加」），本对象不复制那份定义。
 */
internal object MarketBridgeUi {

    /** 进行中那句：{0} 包名（桥无进度回调缝，一句进行中顶到底）。 */
    fun bridgeBusyPhrase(copy: MarketCopy, name: String): String =
        copy.fill(copy.bridgeBusyFormat, name)

    /**
     * 桥五态 → 状态行一句人话（契约 §2.2 的 JVM 级调用链）。[MarketBridgeOutcome] 是封闭
     * 密封族（其 KDoc：「五态不许加」），when 穷尽 = 无未知态的编译级保证；五句互异、全部
     * 不 blank 由 MarketViewModelTest 钉死。ImportFailed 的 failure 原文**不上屏**（契约
     * §2.4，照 W1 拒因 detail 不进玩家文案纪律）：{1} 槽只接失败类别人话——复用既有导入
     * 失败句式 [modImportFailureTitle]（类别词面单源在 ModImportCopy，不复制第二份），
     * 语言随市场文案表的 lang 单路取（AGENTS 文案表约定：取语言只有一条路）。
     */
    fun bridgeNote(copy: MarketCopy, name: String, outcome: MarketBridgeOutcome): String = when (outcome) {
        is MarketBridgeOutcome.Ok -> copy.fill(copy.bridgeOkFormat, outcome.packageId)
        MarketBridgeOutcome.NotFound -> copy.bridgeNotFound
        MarketBridgeOutcome.EmptyPack -> copy.bridgeEmptyPack
        is MarketBridgeOutcome.ImportFailed -> copy.fill(
            copy.bridgeFailedFormat,
            name,
            modImportFailureTitle(outcome.failure.kind, ModImportCopy.of(copy.lang)),
        )
        is MarketBridgeOutcome.LedgerFailed -> copy.bridgeLedgerFailed
    }

    /**
     * 单飞闸：装进游戏与装/更新/卸载/手动清扫共用 installingKey 一把互斥（market-panel.ts
     * :244-307 的单键互斥口径；清扫占 [MarketViewModel.SWEEP_GATE_KEY] 哨兵，CONTRACT-MKM-W5-GC
     * 余项 D2）。忙态判据只看这把闸、没有第二条口径；行按钮 disabled 与宿主入口
     * （marketBridgeImport 的第二道挡）三处同一函数单源，进行中重复点击进不到第二次 import。
     */
    fun bridgeImportEnabled(state: MarketViewModel.MarketUiState): Boolean = state.installingKey == null
}

/** 覆盖层卡片封顶（照 ModImportChrome 的 680/620 一档）。 */
private val MARKET_CARD_MAX_WIDTH = 680.dp
private val MARKET_CARD_MAX_HEIGHT = 620.dp
private val MARKET_CONFIRM_MAX_WIDTH = 420.dp
private val MARKET_CONFIRM_MAX_HEIGHT = 320.dp

/** 筛选片间距：档位之间只留一口气，合成一条连续的带。 */
private val MARKET_CHIP_GAP = 6.dp

/** 收藏档与 kind 档之间的竖发丝高度：比片矮一半，读成「分隔」而不是「另一枚片」。 */
private val MARKET_CHIP_RULE_HEIGHT = 18.dp

/** 三个分区（索引 / 已装 / 端点）之间的留白：比行间距大一档，隔开「另一件事」。 */
private val MARKET_SECTION_GAP = 14.dp

/** 行内元素间距（元信息 → 统计 → 动作 → 进度）：同一张卡里的节奏值。 */
private val MARKET_FIELD_GAP = 8.dp

/** 首读占位行数：三行足够读出「在加载」，再多就比真名单还长。 */
private const val MARKET_SKELETON_LINES = 3
