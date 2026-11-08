package com.eratw.port.android.portrait

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.eratw.port.android.ShellState
import com.eratw.port.android.UiPrefs
import com.eratw.port.render.canvas.CanvasConsoleView
import kotlinx.coroutines.delay

/**
 * 竖屏三区壳的编排层（设计稿 `docs/archive/竖屏布局设计稿.md` §3 三区架构 + §3.5
 * 四形态分流）。横屏路径不进本文件——`EratwShell` 的横屏分支原样保留（§1.3：
 * 竖屏是新增版面路径，不是替换）。
 *
 * 结构（§3 图）：顶部 [PortraitHud]（可展开状态详情抽屉）→ 中部正文槽
 * （`CanvasConsoleView` **原样** `AndroidView` 嵌入，禁外包 Compose 滚动，§4.2）→
 * [SpriteStage]（M1 标题条，让位见 §3.6.1/§4.3）→ [CommandTray]（可展开指令盘）。
 * 输入条/抓手/宏条/覆盖层在 `EratwShell` 的公共段，横竖屏共用（§4.4/§4.5）。
 *
 * 数据源铁律（§0-3）：HUD 解析快照 = 屏行尾窗（[linesProvider]，调用侧从
 * `LineBufferConsoleSink.lines()` 取）；指令盘 = `ShellState.chips`（250ms 扫描现状）。
 * 轮询频率 = 250ms ≤ 选项扫描频率（§0-9），解析器全手写扫描（P1 已禁正则）。
 *
 * 形态分流（§3.5）：`trayFormOf(chips, charCount)` 纯判据，误分级零成本——分级只决定
 * 摆法（盘开合、角色条显隐、抽屉显隐），数据源/提交路径同一份（§0-11）。
 */
internal object PortraitTokens {
    /** HUD/盘面板（§11：#161616、96% 不透明）。 */
    val Panel = Color(0xF5161616)

    /** 分区描边（§11：边线 #2A2A2A）。 */
    val PanelLine = Color(0xFF2A2A2A)

    /** 主文字（§11：正文 #EDEDED）。 */
    val Ink = Color(0xFFEDEDED)

    /** 弱字（§11：#8A8A8A）。 */
    val DimText = Color(0xFF8A8A8A)

    /** 主绿（§11：#7FAF7F）。 */
    val Green = Color(0xFF7FAF7F)
}

/** HUD/指令盘轮询周期（毫秒）。= 选项扫描节奏，≤ 扫描频率即合规（§0-9）。 */
internal const val PORTRAIT_SCAN_MS = 250L

/**
 * HUD 解析用的屏行尾窗行数。屏行缓冲上限数千行（`LineBufferConsoleSink.maxLines`
 * 原版 MaxLog 5000），全量解析浪费；当前屏 + 组标签行在尾窗 160 行内绰绰有余
 * （竖屏 62 列一屏 ≈ 40~70 可视行，含折行）。调用侧据此截尾再取文本。
 */
internal const val PORTRAIT_HUD_TAIL_LINES = 160

/**
 * 指令盘初始开合（纯函数，可测）：G0 自动展开（候选多、正文短，§3.5），且**不受
 * `command_tray_open` 影响**；G1/G2/G3 = 用户手动偏好（§4.3「用户手动开合记忆到
 * UiPrefs.commandTrayOpen」，缺省收起 peek）。
 */
internal fun trayDefaultOpen(form: TrayForm, prefOpen: Boolean): Boolean =
    form == TrayForm.MENU || prefOpen

/** 立绘舞台在该形态是否出现（§3.5 正文/立绘列：G2/G3 有立绘；G0/G1 无）。 */
internal fun stageVisibleFor(form: TrayForm): Boolean =
    form == TrayForm.INTERACT || form == TrayForm.MULTI

/**
 * 竖屏分支的正文区编排（`EratwShell` 里 `BoxWithConstraints` 的竖屏内容）。
 *
 * 高度纪律（§0-14）：所有百分比都从 [contentMaxHeight]（`BoxWithConstraints.maxHeight`，
 * 宿主内容区）派生，**禁** `LocalConfiguration.screenHeightDp` 乘系数；隐藏控件走
 * AnimatedVisibility 真移除（§0-15）。
 *
 * @param linesProvider 屏行尾窗快照（serial to 行文本，显示序；调用侧实现见
 *   `MainActivity.portraitScreenLines`——经 `GameHost.debugSession()` 的只读口取
 *   `LineBufferConsoleSink.lines()` 尾窗，GameHost 本体零改动）
 * @param contentWidth 宿主内容区宽（断点选档输入，§5.1）
 * @param contentMaxHeight 宿主内容区高（盘/抽屉上限派生基准，§4.3/§0-14）
 */
@Composable
internal fun PortraitShellContent(
    consoleView: CanvasConsoleView,
    state: ShellState,
    linesProvider: () -> List<Pair<Long, String>>,
    contentWidth: Dp,
    contentMaxHeight: Dp,
    onSettings: () -> Unit,
    onOption: (String) -> Unit,
    onInputChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    // HUD 尾窗轮询（§0-9）：频率 = 选项扫描节奏；先读后等，首帧即有数据。
    var tailLines by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    LaunchedEffect(linesProvider) {
        while (true) {
            tailLines = linesProvider()
            delay(PORTRAIT_SCAN_MS)
        }
    }
    val hud = remember(tailLines) { HudLineParser.parseHud(tailLines.map { it.second }) }
    val form = trayFormOf(state.chips, hud.charCount)
    val size = sizeClass(contentWidth.value)

    // 主体与选中块（§3.6.1：壳层状态、每形态独立、不落盘、不提交指令）。
    // rawSubject = null 表示「还没切过」，跟随缺省（当前少女；少女缺席退主角）。
    var rawSubject by remember(form) { mutableStateOf<HudSubject?>(null) }
    var selectedBlock by remember(form) { mutableStateOf(0) }
    val subject = when {
        rawSubject == HudSubject.CHARACTER && hud.currentTarget != null -> HudSubject.CHARACTER
        rawSubject == HudSubject.PROTAGONIST && hud.protagonist != null -> HudSubject.PROTAGONIST
        hud.currentTarget != null -> HudSubject.CHARACTER
        hud.protagonist != null -> HudSubject.PROTAGONIST
        else -> HudSubject.CHARACTER
    }

    // 跨屏复位（§9-2「无残留态」）：形态一变，抽屉收起、盘回各形态缺省
    // （G0 自动展开不受 command_tray_open 影响，G1/G2/G3 从手动偏好起）。
    LaunchedEffect(form) {
        state.hudOpen = false
        state.trayOpen = trayDefaultOpen(form, UiPrefs.commandTrayOpen(context))
    }

    // 立绘舞台折叠（§3.6.3：sprite_stage_collapsed 跨屏记忆；壳层会话内 remember +
    // 每次翻面即落盘，Activity 重建后从偏好读回）。
    var stageCollapsed by remember { mutableStateOf(UiPrefs.spriteStageCollapsed(context)) }

    // 指令盘装配（纯函数）：组标签剥取 → 纵排单元 → peek。
    val groupLabelLines = remember(state.chips, tailLines) {
        trayGroupLabelLines(tailLines, state.chips)
    }
    val groups = remember(state.chips, groupLabelLines) { trayGroupsOf(state.chips, groupLabelLines) }
    val peek = remember(state.chips) { trayPeekOptions(state.chips) }

    Column(modifier = Modifier.fillMaxSize()) {
        PortraitHud(
            form = form,
            snapshot = hud,
            subject = subject,
            onSubjectChange = { rawSubject = it },
            selectedBlockIndex = selectedBlock,
            onBlockSelect = { index ->
                // §3.6.2：点角色条 = 「选中该少女」+「主体切回角色」一次完成；零提交。
                selectedBlock = index
                rawSubject = HudSubject.CHARACTER
            },
            detailOpen = state.hudOpen,
            onDetailToggle = { state.hudOpen = !state.hudOpen },
            trayOpen = state.trayOpen,
            onTrayToggle = {
                val new = !state.trayOpen
                state.trayOpen = new
                // §4.3：只记 G1/G2/G3 的手动偏好；G0 的自动展开不受该键影响。
                if (form != TrayForm.MENU) UiPrefs.setCommandTrayOpen(context, new)
            },
            inputVisible = state.inputVisible,
            onInputToggle = { state.inputVisible = true },
            onSettings = onSettings,
            hudColsPerRow = hudCols(size),
            contentMaxHeight = contentMaxHeight,
            // 锚点跳转（§4.1 M1 形态）：M1 拿不到 render 的行映射（折行后的渲染行号在
            // ConsoleScreen 内部），精确锚到 [Status]/[Palam] 行属 M2/需组长接线；这里用
            // 控制台视图**自己**的公开滚动口（CanvasConsoleView.scrollRows，单入口、
            // 不外包 Compose 滚动）上翻一屏，让玩家把更早的正文拉进视野。
            onScrollToSections = {
                val rows = consoleView.consoleScreen()?.heightLines?.coerceAtLeast(1) ?: 24
                consoleView.scrollRows(rows)
            },
            onScrollToLatest = {
                // scrollRows 负数 = 回底方向；大步长由视口自夹（滚动范围有限）。
                consoleView.scrollRows(-100_000)
            },
        )

        // ── 正文槽：CanvasConsoleView 原样嵌入（§4.2 零改动 + 禁外包滚动） ──
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { consoleView },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ── 立绘舞台（M1 标题条；§3.6.3）：指令盘展开 / 抽屉展开时让位 = 真移除，
        //    不改写玩家自己的折叠偏好（§3.6.1/§4.3/§0-15）。
        AnimatedVisibility(visible = stageVisibleFor(form) && !state.trayOpen && !state.hudOpen) {
            SpriteStage(
                count = null, // M1 拿不到立绘张数（§3.6.3 N/10 需 M2 的 render 只读接口）。
                collapsed = stageCollapsed,
                onToggle = {
                    stageCollapsed = !stageCollapsed
                    UiPrefs.setSpriteStageCollapsed(context, stageCollapsed)
                },
            )
        }

        // ── 指令盘（§4.3）：开合只跟用户开关（§10-4），高度上限宿主派生（§0-14）。
        CommandTray(
            groups = groups,
            peek = peek,
            columns = cmdCols(size),
            maxHeight = contentMaxHeight * trayMaxHeightFraction(size),
            open = state.trayOpen,
            onToggleOpen = {
                val new = !state.trayOpen
                state.trayOpen = new
                if (form != TrayForm.MENU) UiPrefs.setCommandTrayOpen(context, new)
            },
            executingText = hud.executing?.let { e ->
                if (e.subject != null) "(${e.subject}) ${e.text}" else e.text
            },
            lastCommandText = hud.lastCommand,
            onOption = onOption,
        )
    }
}
