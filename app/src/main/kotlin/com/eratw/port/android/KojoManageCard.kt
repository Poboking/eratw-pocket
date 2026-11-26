package com.eratw.port.android

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwGhostButton
import com.eratw.port.android.ui.EratwGhostTone
import com.eratw.port.android.ui.EratwInk
import com.eratw.port.android.ui.design.DisclosureStyle
import com.eratw.port.android.ui.design.ShellAlertDialog
import com.eratw.port.android.ui.design.ShellDialogActions
import com.eratw.port.android.ui.design.ShellDisclosureSection
import com.eratw.port.android.ui.design.ShellPress
import com.eratw.port.android.ui.design.ShellRowHairline
import com.eratw.port.android.ui.design.ShellMotion
import com.eratw.port.android.ui.design.ShellRuleLabel
import com.eratw.port.android.ui.design.ShellSpace
import com.eratw.port.android.ui.design.ShellType
import com.eratw.port.platform.storage.GameStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 覆盖函数清单的滚动上限：超过就内部滚，不把整张卡顶到一屏。 */
private val KOJO_OVERRIDE_LIST_MAX_HEIGHT = 160.dp

/**
 * 口上包管理卡（KI-6，BOARD 134）：只列玩家导入的口上包（`ModSource.IMPORTED`，`kojo-` 前缀），
 * 与「已安装的 Mod」列表（全来源，Mod 档 [ModManageCard]）分立——主人指令
 * 「和 mod 导入功能区分开来，单独设置一个口上导入功能」。
 *
 * ## 展示面（BOARD KI-6 ②）
 *
 * - 已导入包逐行：显示名（版本 + 停用 tag）、导入时间（manifest mtime，读数见
 *   [InstalledMod.importedAtMillis]）、覆盖计数（[InstalledMod.overrideFunctions]）；
 * - 点行展开：被覆盖的本体函数清单（可滚，无覆盖声明时给一句说明）+ 卸载按钮；
 * - 启停：真源 = `mods/rt2/<id>.off`（[ModEnablement.setEnabled]，与 Mod 列表同一真源）。
 *
 * ## 自装配范式
 *
 * 照 [ModTrustCard] / [ModCodeOverviewCard]：卡内 `remember` 自己扫描（不进 `SettingsScreen`
 * 那 60+ 形参），启停 / 卸载后递增本卡 revision 重扫，同页其它卡（Mod 列表 / 诊断）等既有
 * 重组时机刷新。卸载复用 [uninstallModAndQueueReclaim]（含 storage 段回收投递与运行期信任
 * 标记清账，与 Mod 列表同一卸载链，不另造第二套）。
 *
 * 生效时机文案照 [SettingsStrings.modManageTakeEffectHint] 同口径：启停 / 卸载都是
 * 「下次进入游戏生效，本局不变」——卡顶说明已写明。
 */
@Composable
internal fun KojoManageCard(text: SettingsStrings) {
    val context = LocalContext.current
    // revision：卡内启停 / 卸载落盘成功后递增重扫（照 ModTrustCard 的 modTrustRevision 同款）。
    var revision by remember { mutableIntStateOf(0) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<InstalledMod?>(null) }
    // 折叠态默认展开（与 Mod 列表卡同口径）；收起后标题行仍带已装计数。
    // 启停的本地覆盖：点击即时翻转，盘面写失败才回滚 —— 重扫回来前 Switch 不闪一下。
    var toggleOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    // 扫描挪到 IO：null = 首扫中（只出细进度条），扫完填清单（旧清单在重扫期间保留）。
    val imported by produceState<List<InstalledMod>?>(initialValue = null, revision) {
        value = withContext(Dispatchers.IO) {
            ModManageScanner.scan(GameStorage(context).gameRoot()).mods.filter { it.source == ModSource.IMPORTED }
        }
    }
    val importedList = imported
    val rt2Dir = remember { ModEnablement.rt2Dir(GameStorage(context).gameRoot()) }
    val toggleFailedToast = { Toast.makeText(context, text.modManageToggleFailed, Toast.LENGTH_LONG).show() }

    ShellDisclosureSection(
        title = text.kojoManageTitle,
        style = DisclosureStyle(
            accent = LocalZoneAccent.current,
            badge = importedList?.size?.takeIf { it > 0 }?.toString(),
        ),
    ) {
        KojoManageBody(
            text = text,
            importedList = importedList,
            expandedId = expandedId,
            overrides = toggleOverrides,
            callbacks = KojoListCallbacks(
                onExpanded = { expandedId = it },
                onToggled = { mod, enabled ->
                    toggleOverrides = toggleOverrides + (mod.packageId to enabled)
                    if (ModEnablement.setEnabled(rt2Dir, mod.packageId, enabled)) {
                        revision += 1
                    } else {
                        toggleOverrides = toggleOverrides - mod.packageId
                        toggleFailedToast()
                    }
                },
                onUninstallRequest = { pendingUninstall = it },
            ),
        )
    }

    // 卸载确认（照素材清理确认框同款：先置目标，点「卸载」才真删；删盘与 toast 都在确认框宿主里）。
    KojoUninstallGate(
        target = pendingUninstall,
        text = text,
        rt2Dir = rt2Dir,
        onDismiss = { pendingUninstall = null },
        onRemoved = { revision += 1 },
    )
}

/**
 * 卸载确认宿主：确认时真删并上 toast，成功后回调卡体刷 revision（从 [KojoManageCard] 拆出，压长函数）。
 */
@Composable
private fun KojoUninstallGate(
    target: InstalledMod?,
    text: SettingsStrings,
    rt2Dir: File,
    onDismiss: () -> Unit,
    onRemoved: (InstalledMod) -> Unit,
) {
    val mod = target ?: return
    val context = LocalContext.current
    KojoUninstallConfirmDialog(
        mod = mod,
        text = text,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            if (uninstallModAndQueueReclaim(rt2Dir, mod.packageId)) {
                Toast.makeText(context, text.modManageUninstallDone(mod.title), Toast.LENGTH_SHORT).show()
                onRemoved(mod)
            } else {
                Toast.makeText(context, text.modManageUninstallFailed(mod.title), Toast.LENGTH_LONG).show()
            }
        },
    )
}

/**
 * 卡体：说明行 + 首扫进度 / 空态 / 口上包列表（从 [KojoManageCard] 拆出，压长函数）。
 *
 * [importedList] 为 null = 首扫中；重扫期间保留旧清单，不闪空态。
 */
@Composable
private fun KojoManageBody(
    text: SettingsStrings,
    importedList: List<InstalledMod>?,
    expandedId: String?,
    overrides: Map<String, Boolean>,
    callbacks: KojoListCallbacks,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text.kojoManageHint,
            color = EratwInk.textMuted(),
            style = ShellType.Label,
        )
        Spacer(Modifier.height(ShellSpace.CardGap))
        when {
            importedList == null -> LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = ShellSpace.CardGap),
                color = LocalZoneAccent.current,
                trackColor = UiColors.CardAlt,
            )
            importedList.isEmpty() -> Text(
                text = text.kojoManageEmpty,
                color = EratwInk.textMuted(),
                style = ShellType.Caption,
            )
            else -> KojoPackList(
                imported = importedList,
                text = text,
                expandedId = expandedId,
                overrides = overrides,
                callbacks = callbacks,
            )
        }
    }
}

/** 行动作组（形参归组：行组件参数已达上限，三个回调照 [ModImportChromeActions] 先例成组进）。 */
private data class KojoRowActions(
    val onToggle: (Boolean) -> Unit,
    val onExpandToggle: () -> Unit,
    val onUninstallRequest: () -> Unit,
)

/** 列表段回调组（照 [KojoRowActions] 同款归组：列表组件参数不平铺加参）。 */
private data class KojoListCallbacks(
    val onExpanded: (String?) -> Unit,
    val onToggled: (InstalledMod, Boolean) -> Unit,
    val onUninstallRequest: (InstalledMod) -> Unit,
)

/** 口上包列表段：计数行 + 逐包行（展开态与启停回调由卡体持有，这里只转发）。 */
@Composable
private fun KojoPackList(
    imported: List<InstalledMod>,
    text: SettingsStrings,
    expandedId: String?,
    overrides: Map<String, Boolean>,
    callbacks: KojoListCallbacks,
) {
    Text(
        text = text.kojoManageCount(imported.size),
        color = EratwInk.textDim(),
        style = ShellType.Caption,
        modifier = Modifier.padding(bottom = ShellSpace.CardGap),
    )
    imported.forEachIndexed { index, mod ->
        // 行与行之间只摆一条发丝线：同一张卡里十几包，靠分隔线给出扫读节奏，而不是每条再套一层描边框
        // （卡内再叠行框会变成嵌套容器，密度反而更差）。
        if (index > 0) ShellRowHairline()
        // 本地覆盖优先：点开关的下一帧就按新值画，不等重扫回来。
        val enabled = overrides[mod.packageId] ?: mod.enabled
        KojoPackRow(
            mod = mod,
            enabled = enabled,
            expanded = expandedId == mod.packageId,
            text = text,
            actions = KojoRowActions(
                onToggle = { next -> callbacks.onToggled(mod, next) },
                onExpandToggle = { callbacks.onExpanded(if (expandedId == mod.packageId) null else mod.packageId) },
                onUninstallRequest = { callbacks.onUninstallRequest(mod) },
            ),
        )
    }
}

/** 口上管理卡的一行：读数 + 开关；点行展开覆盖清单与卸载（渲染不判定，判定在卡体回调）。 */
@Composable
private fun KojoPackRow(
    mod: InstalledMod,
    enabled: Boolean,
    expanded: Boolean,
    text: SettingsStrings,
    actions: KojoRowActions,
) {
    val pressSource = remember { MutableInteractionSource() }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ShellSpace.RowMinHeight)
                .background(ShellPress.tint(pressSource, EratwInk.text()))
                .clickable(
                    interactionSource = pressSource,
                    indication = null,
                    onClick = actions.onExpandToggle,
                )
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (enabled) {
                        mod.title
                    } else {
                        "${mod.title}（${text.modManageDisabledTag}）"
                    },
                    color = if (enabled) EratwInk.text() else EratwInk.textDim(),
                    style = ShellType.Body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "v${mod.version}",
                    color = EratwInk.textDim(),
                    style = ShellType.Label,
                )
                Text(
                    text = listOfNotNull(
                        mod.importedAtMillis.takeIf { it > 0L }?.let { text.kojoManageImportedAt(it) },
                        text.kojoManageOverrideCount(mod.overrideFunctions.size),
                    ).joinToString("　"),
                    color = EratwInk.textMuted(),
                    style = ShellType.Label,
                )
            }
            ExpandChevron(expanded = expanded, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = actions.onToggle,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = ShellMotion.rowEnter(),
            exit = ShellMotion.rowExit(),
        ) {
            KojoOverrideBlock(mod = mod, text = text, onUninstallRequest = actions.onUninstallRequest)
        }
    }
}

/** 展开块：被覆盖的本体函数清单（可滚）+ 卸载按钮。无覆盖声明时整块给一句说明。 */
@Composable
private fun KojoOverrideBlock(
    mod: InstalledMod,
    text: SettingsStrings,
    onUninstallRequest: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ShellSpace.CardStart, bottom = ShellSpace.CardGap),
    ) {
        if (mod.overrideFunctions.isEmpty()) {
            Text(
                text = text.kojoManageOverrideNone,
                color = EratwInk.textMuted(),
                style = ShellType.Label,
            )
        } else {
            // 「被覆盖的本体函数」是一段小节：用共享小节标签（小字 + 细分隔线）而不是
            // 又一颗彩色 12sp 字，免得零散函数清单看起来像另一个分层。
            ShellRuleLabel(text = text.kojoManageOverrideListTitle, tone = LocalZoneAccent.current)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = KOJO_OVERRIDE_LIST_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (fn in mod.overrideFunctions) {
                    Text(
                        text = fn,
                        color = EratwInk.textDim(),
                        style = ShellType.Label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Spacer(Modifier.height(ShellSpace.CardGap))
        // 卸载是不可逆动作：字与描边一起点红（[EratwGhostTone]），文案本身也是动词「卸载」。
        EratwGhostButton(
            text = text.kojoManageUninstallConfirmOk,
            onClick = onUninstallRequest,
            tone = EratwGhostTone(UiColors.Warn, UiColors.Warn.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 卸载确认框：与素材清理确认框同形态（共享 [ShellAlertDialog]），危险键点警示色。 */
@Composable
private fun KojoUninstallConfirmDialog(
    mod: InstalledMod,
    text: SettingsStrings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ShellAlertDialog(
        title = text.kojoManageUninstallConfirmTitle,
        onDismissRequest = onDismiss,
        actions = ShellDialogActions(
            confirmLabel = text.kojoManageUninstallConfirmOk,
            onConfirm = onConfirm,
            confirmTone = EratwInk.warn(),
            dismissLabel = text.kojoManageUninstallConfirmCancel,
            onDismiss = onDismiss,
        ),
    ) {
        Text(
            text = text.kojoManageUninstallConfirm(mod.title),
            color = EratwInk.text(),
            style = ShellType.Body,
        )
    }
}
