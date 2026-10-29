package com.eratw.port.android

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.platform.input.InputBarState
import com.eratw.port.render.canvas.CanvasConsoleView
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * 壳层 UI 的全部可观测量（2026-10-23 起壳层用 Jetpack Compose）。
 *
 * 只由 UI 线程写（`pump` / ticker / 点击回调）；Compose 侧读同一份 `mutableStateOf`。
 * **游戏画面不在这里**：`CanvasConsoleView` 经 `AndroidView` 嵌入，仍是原来的
 * 自绘 View（60fps + 脏矩形），本状态只承载输入条/选项栏/宏条这类壳数据。
 */
class ShellState {
    /** 输入框文本（受控）。 */
    var input by mutableStateOf("")

    /** 输入框提示语（按等待类型/宿主状态变化）。 */
    var hint by mutableStateOf("输入后回车提交")

    /** 是否在等整数输入：决定要不要弹数字键盘。 */
    var numberKeyboard by mutableStateOf(false)

    /**
     * 是否停在「按任意键继续」页（WAIT 系：`PRINTW` / `PRINTFORMW` / `WAIT` / `WAITANYKEY`）。
     *
     * 原版这类页面**屏上不印任何 `[N]`**，推进方式是「点一下画面」或敲任意键
     * （`MainWindow.cs:806`：`IsWaitingEnterKey && selectedString == null` ⇒ 左键
     * `PressEnterKey`，`:818-820`；`IsWaitingEnterKey` 只对 `EnterKey` / `AnyKey` 为真，
     * `EmueraConsole.cs:324-343`）。触摸端没有回车键，「点画面」也没有视觉暗示，
     * 于是玩家会以为这一页**没有退出键**、只能自己去手输（2026-10-29 玩家反馈：
     * 钓鱼列表看完最下面没有可点的退出键，也没提示）。
     *
     * 壳层据此在选项栏底部常驻一枚「继续」脚钮（见 [ContinueButton]），把原版那条
     * 隐式的「点任意处」通道显式化；提示语同步换成 [com.eratw.port.platform.input.InputHint.CONTINUE]。
     */
    var awaitingAnyKey by mutableStateOf(false)

    /**
     * 输入条是否展开（2026-10-23 负责人定调：**默认隐藏**，往上滑或点底部小凸边才出现）。
     *
     * 收起时机：提交成功、上拖抓握条、点画面其它处；**失焦**静置 15s 才自动收起，
     * 有焦点（正在打字/输入法组合）时永不自动收 —— 见 [EratwShell] 的 LaunchedEffect。
     */
    var inputVisible by mutableStateOf(false)

    /**
     * 输入框当前是否持有焦点（软键盘/输入法组合态）。
     *
     * 静置自动收起的唯一豁免：**有焦点时不收**。中文输入法的拼音候选还没上屏时
     * `state.input` 不会变化，旧实现只按 `(inputVisible, input)` 重置 5s 计时，
     * 玩家打了 5 秒拼音就被收掉输入条。
     */
    var inputFocused by mutableStateOf(false)

    /** 右侧选项栏是否展开（设置旁螺旋图标控制，状态持久化到 `UiPrefs`）。 */
    var railVisible by mutableStateOf(true)

    /**
     * 右侧选项栏按钮的大小档（设置页「选项按钮大小」，持久化到 `UiPrefs`）。
     *
     * 只是几何：栏宽/按钮高/字号由 [railMetricsOf] 按这一档算。引擎不认识它，
     * 所以改档**不需要重开会话** —— [MainActivity] 在 onCreate/onResume 把最新值镜像进来，
     * 从设置页回到游戏的第一帧就是新大小。
     */
    var railButtonSize by mutableStateOf(UiPrefs.DEFAULT_RAIL_BUTTON_SIZE)

    /** 右侧选项栏候选（来自引擎的 `GameHost.currentOptions()` 快照；空 = 不显示）。 */
    var chips by mutableStateOf<List<ConsoleOption>>(emptyList())

    /**
     * 长按按钮弹出的提示原文（HTML `<button title='…'>`；null = 不显示）。
     *
     * 原版 PC 靠鼠标悬停把 `title` 当 ToolTip 显示（`EmueraConsole.cs:1891-1917`）；
     * 安卓没有 hover，由 [MainActivity] 的长按回调写这里，壳层点任意处关闭。
     */
    var tooltip by mutableStateOf<String?>(null)

    /** 宏条：当前组的宏（label → 内容）。 */
    var macros by mutableStateOf<List<ShellMacro>>(emptyList())

    /** 宏组名（点它切换组）。 */
    var macroGroupLabel by mutableStateOf("")

    /** 首启/重启装载期：true 时显示 Compose 加载覆盖层（进度条）。 */
    var loading by mutableStateOf(false)

    /** 加载覆盖层的标题行（`正在解包…` / `正在装载 erbc.v1…` 等）。 */
    var loadingTitle by mutableStateOf("")

    /** 解包进度（0f..1f）；null = 未知总量，进度条走 indeterminate。 */
    var progress by mutableStateOf<Float?>(null)

    /** 进度条下方的明细行（`已解包 1234/3971 条`）。 */
    var progressText by mutableStateOf("")

    /** 启动失败文案（非 null 时显示失败覆盖层 + 重试按钮）。 */
    var failure by mutableStateOf<String?>(null)

    /**
     * 失败屏上「回到标题」是否可点（GOTOTITLE-CHANNEL）。
     *
     * 与原版菜单的两道拒绝同口径（`MainWindow.cs:1079-1104`）：装载/标题级硬失败没有
     * 可复活的会话，按钮就不点亮，免得点下去只是把同一张失败屏再刷一遍。
     */
    var failureCanBackToTitle by mutableStateOf(false)

    /**
     * 失败屏「兼容模式重试」是否点亮（2026-10-28 低端机 OOM）。
     *
     * 只有「堆不足」族失败才亮（真 OOM 或堆预检不足，`GameHost.canRetryCompat`）：
     * 只有这一族失败换兼容档才有意义 —— 格式坏/文件缺失重装多少遍都一样。
     */
    var failureCanRetryCompat by mutableStateOf(false)

    /**
     * 失败屏「复制报告 / 分享报告」是否点亮（方案① 报错收集）：
     * 本次失败落出了报告文件（`GameHost.lastReportFile`）才亮，免得点了没东西可给。
     */
    var failureReportReady by mutableStateOf(false)
}

/** 一枚宏：`label` 显示在宏条上，`content` 是点下去要预填的文本（原版 F 键宏语义）。 */
data class ShellMacro(val label: String, val content: String)

/**
 * 右侧选项栏的一档几何：栏宽 + 按钮高 + 标题/角标字号。
 *
 * 由设置页的「选项按钮大小」（[UiPrefs.RailButtonSize]）选档，[railMetricsOf] 换算成实际尺寸。
 * **一档只在此处定义一次**：按钮宽、收起位移、角标让位全部从它推导，
 * 免得几个常量各改各的，把「三枚正好铺满一行」这条契约改崩
 * （`ShellUiRailGeometryTest` 钉的就是每一档的这条契约）。
 *
 * @property railWidth 整栏宽度（不含与屏右沿的留白，见 [RAIL_END_PADDING]）
 * @property buttonHeight 一行内按钮的高度（选项按钮与「继续」脚钮同值）
 * @property labelTextSize 标题字号
 * @property badgeTextSize 右上角序号角标的字号
 * @property badgeDigitWidthDp 角标**单个数字**的占宽（dp）：约 0.55×角标字号（8sp 实测约 4dp，
 *   取 4.5dp 留余量），按本档角标字号等比给出，见 [railBadgeReserve]
 */
internal data class RailMetrics(
    val railWidth: Dp,
    val buttonHeight: Dp,
    val labelTextSize: TextUnit,
    val badgeTextSize: TextUnit,
    val badgeDigitWidthDp: Float,
)

/** 整栏与屏右沿的留白，落在 `railWidth` **之外** ⇒ 栏的实际占地 = 栏宽 + 这份留白。 */
internal val RAIL_END_PADDING = 4.dp

/** 栏内行距，同时也是行内按钮之间的间距。 */
internal val RAIL_ROW_SPACING = 4.dp

/**
 * 四档几何表（2026-10-29 加「选项按钮大小」设置项）。
 *
 * 标准档就是加设置项之前唯一的那一档：栏宽 232dp（玩家反馈「三枚并排过小、标题看不清」后
 * 从 200dp 加宽）、高 28dp、标题 10sp —— 缺省选它，老玩家的界面不变。
 *
 * 上限停在 300dp 的理由是**画布占比**而不是dp 数本身：游戏锁 `sensorLandscape`
 * （AndroidManifest.xml:44），画布是横着的那一边（2400×1080 那台约 800dp 宽），
 * 300dp 占 37%；再宽就快把正文盖光了 —— 那种需求该收栏（螺旋图标），不该靠放大。
 * 窄画布（小窗、折叠屏外屏）由 [railMetricsFitting] 再夹一刀，不在表里夹：
 * 表是玩家选的那一档的**名义尺寸**，设置页预览要照它画，夹过就不准了。
 */
internal fun railMetricsOf(size: UiPrefs.RailButtonSize): RailMetrics = when (size) {
    UiPrefs.RailButtonSize.SMALL -> RailMetrics(176.dp, 24.dp, 9.sp, 7.sp, 4.0f)
    UiPrefs.RailButtonSize.NORMAL -> RailMetrics(232.dp, 28.dp, 10.sp, 8.sp, 4.5f)
    UiPrefs.RailButtonSize.LARGE -> RailMetrics(268.dp, 32.dp, 12.sp, 10.sp, 5.4f)
    UiPrefs.RailButtonSize.XLARGE -> RailMetrics(300.dp, 38.dp, 14.sp, 11.sp, 6.3f)
}

/** 右栏最多允许盖住画布宽的比例（浮层不许吃掉正文）。 */
internal const val RAIL_MAX_WIDTH_FRACTION = 0.6f

/** 夹宽之后的下限：再窄三枚并排就没法读，那种情况该收栏而不是压窄。 */
internal val RAIL_MIN_FIT_WIDTH = 132.dp

/**
 * 按**当前可用宽**把选中的档位夹一遍（机型自适应就落在这里）。
 *
 * 横屏手机上这一刀基本不生效（800dp 画布 × 0.6 = 480dp > 最宽一档 300dp），
 * 生效的是小窗模式 / 折叠屏外屏这类窄画布：不夹的话「特大」会把正文整块盖住，
 * 玩家看到的是满屏按钮、没有游戏画面。
 *
 * 只改 `railWidth` 一个字段：按钮宽、收起位移、角标让位全部是从它派生的
 * （[railButtonWidth] / [railCollapseOffset] / [railBadgeReserve]），
 * 所以夹完之后「三枚正好铺满一行」这条契约仍然逐档成立（`ShellUiRailGeometryTest` 连夹后的值一起钉）。
 */
internal fun railMetricsFitting(metrics: RailMetrics, availableWidth: Dp): RailMetrics {
    val cap = (availableWidth * RAIL_MAX_WIDTH_FRACTION).coerceAtLeast(RAIL_MIN_FIT_WIDTH)
    return if (metrics.railWidth <= cap) metrics else metrics.copy(railWidth = cap)
}

/** 系统字体放大时，按钮高度在行高之外要留的余量（dp）。 */
private const val RAIL_TEXT_HEIGHT_MARGIN_DP = 6f

/** 一行的行高 ≈ 字号 × 1.35（随包像素体的实测比例，留的是 ascender/descender 的差）。 */
private const val RAIL_LINE_HEIGHT_FACTOR = 1.35f

/**
 * 按**系统字体缩放**把档位的竖向尺寸放开（机型自适应的另一半）。
 *
 * 档表里的字号是 `sp`、按钮高是 `dp`：玩家在系统设置里把字体调大（vivo / MIUI 的字号滑杆最高到 2×）
 * 时，字会长而按钮不会 —— `maxLines = 1` 的标题就会被上下裁掉，序号角标也会盖到标题尾字上
 * （角标的让位宽度按 `dp` 算，数字却长宽了）。这里只放开**跟字号同源的两项**：
 * 按钮高取「档位值」与「字号折成 dp 后的行高 + 余量」里较大的那个，角标单个数字的占宽等比放大。
 *
 * 栏宽**不跟着长**：宽度是玩家选的那一档对画面的占比，放大字号不该让右栏多吃画布
 * （横向挤不下自有省略号兜底，且真有需求该换档位）。
 * `fontScale ≤ 1` 原样返回：系统把字体调小不该让按钮跟着变矮（点击目标会掉到不可用）。
 */
internal fun railMetricsForFontScale(metrics: RailMetrics, fontScale: Float): RailMetrics {
    val scale = fontScale.coerceAtLeast(1f)
    if (scale == 1f) return metrics
    val textHeight = (metrics.labelTextSize.value * scale * RAIL_LINE_HEIGHT_FACTOR + RAIL_TEXT_HEIGHT_MARGIN_DP).dp
    return metrics.copy(
        buttonHeight = maxOf(metrics.buttonHeight, textHeight),
        badgeDigitWidthDp = metrics.badgeDigitWidthDp * scale,
    )
}

/**
 * 一行三枚时每枚的宽度（与 `weight(1f)` 的等分结果一致），横向滑动行沿用同一宽度。
 * 行内按钮间距是 [RAIL_ROW_SPACING]，三枚之间共两处 ⇒ 宽度 = (栏宽 - 两处间距) / 3。
 */
internal fun railButtonWidth(railWidth: Dp): Dp = (railWidth - RAIL_ROW_SPACING * 2) / 3f

/**
 * 收起时整栏要平移的距离：必须盖住栏的**实际占地**（栏宽 + 右留白），否则收起后
 * 最左一列仍露在屏内（2026-10-26 真机反馈过的虫，旧值写死 168dp 而栏宽 196dp 时撞的就是这条）。
 */
internal fun railCollapseOffset(railWidth: Dp): Dp = railWidth + RAIL_END_PADDING + 8.dp

/** 角标右内边距（数字之外的固定留白）。 */
private const val RAIL_BADGE_EDGE_DP = 2f

/**
 * 角标要给标题让出的宽度：按序号**位数**算，长序号（存档页有 10000 这类）也不会压到
 * 标题尾字；上界取「一枚按钮宽的三分之一」—— 再让就不是截断标题，而是把标题吃光了。
 */
internal fun railBadgeReserve(digits: Int, metrics: RailMetrics): Dp =
    ((RAIL_BADGE_EDGE_DP + metrics.badgeDigitWidthDp * digits.coerceAtLeast(1)).dp)
        .coerceAtMost(railButtonWidth(metrics.railWidth) * 0.32f)

/**
 * 右侧栏按钮的统一形状与内边距：选项按钮与「继续」脚钮共用一套，
 * 避免两处各写一份数值后走形（2026-10-29 回滚四档网格后统一）。
 * 尺寸类的数值（高/字号/栏宽）随档位变，见 [railMetricsOf]。
 */
private val RAIL_BUTTON_SHAPE = RoundedCornerShape(7.dp)
private val RAIL_BUTTON_CONTENT_PADDING =
    androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)

/** 深绿半透明底 + 浅字；禁用态压暗（选项栏收起动画期间可见）。 */
@Composable
private fun railButtonColors() = ButtonDefaults.buttonColors(
    containerColor = Color(0xE62A3A2A),
    contentColor = Color(0xFFEDEDED),
    disabledContainerColor = Color(0x662A3A2A),
    disabledContentColor = Color(0xFF6A6A6A),
)

/**
 * 输入条提交的冷却窗（毫秒）：一次按下可能被输入法同时报成
 * `imeAction` + 按键事件（部分第三方输入法确实这么做），原版一次回车只交一条。
 */
private const val SUBMIT_DEBOUNCE_MS = 250L

/**
 * 把引擎选项按**游戏显示行**分组（连续相同的 `lineSerial` 为一行）。
 *
 * 引擎的 [com.eratw.port.core.host.LineBufferConsoleSink.displayOptions] /
 * `currentOptions()` 已按 `(lineSerial, startCol, endCol)` 排好；这里只做相邻分组，
 * 不重新排序、不跨行合并。浮岛按钮 `lineSerial = Long.MAX_VALUE`，自然落在最后。
 */
private fun railRowsOf(chips: List<ConsoleOption>): List<List<ConsoleOption>> {
    if (chips.isEmpty()) return emptyList()
    val rows = ArrayList<MutableList<ConsoleOption>>()
    var lastSerial = Long.MIN_VALUE
    for (chip in chips) {
        if (rows.isEmpty() || chip.lineSerial != lastSerial) {
            rows.add(ArrayList())
            lastSerial = chip.lineSerial
        }
        rows[rows.lastIndex].add(chip)
    }
    return rows
}

/** 选项栏的一行：行内按钮按屏上顺序并排；最多 3 枚，超过 3 枚时横向滚动，保持可点大小。 */
@Composable
private fun OptionRailRow(
    row: List<ConsoleOption>,
    metrics: RailMetrics,
    enabled: Boolean,
    onOption: (String) -> Unit,
) {
    // 用户反馈（2026-10-27）：栏宽 200dp 下 4 枚等分只剩约 47dp，10sp 标题被挤到看不清。
    // 改为「最多 3 枚并排，第 4 枚起一律横向滑动」；滑动行沿用三枚时的固定宽，避免再次压缩。
    val overflow = row.size > 3
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (overflow) Modifier.horizontalScroll(scroll) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(RAIL_ROW_SPACING),
    ) {
        row.forEach { chip ->
            OptionChipButton(
                label = chip.label,
                ordinal = chip.ordinal,
                metrics = metrics,
                enabled = enabled,
                modifier = if (overflow) Modifier.width(railButtonWidth(metrics.railWidth)) else Modifier.weight(1f),
            ) { onOption(chip.submitValue) }
        }
    }
}

/**
 * 设置页「选项按钮大小」用的实物预览：照右栏的同一条绘制通路画一行示例按钮，
 * 几何由 [railMetricsOf] 给出 ⇒ 预览与游戏里的右栏同源，不会各画一份后走形。
 *
 * 三枚示例按「一行三枚」的固定宽排（与 [OptionRailRow] 溢出行的口径一致），
 * 整条可横向滑动：特大档的栏宽会超过设置卡片，裁掉就看不出真实大小了。
 */
@Composable
internal fun RailSizePreview(metrics: RailMetrics, modifier: Modifier = Modifier) {
    val buttonWidth = railButtonWidth(metrics.railWidth)
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(RAIL_ROW_SPACING),
    ) {
        // 序号一位/两位/五位各给一枚：一眼看出角标让位与标题截断在不同档位下的实际效果。
        listOf("会话" to 31, "移动到广场" to 400, "购买[酒]" to 10000).forEach { (label, ordinal) ->
            OptionChipButton(
                label = label,
                ordinal = ordinal,
                metrics = metrics,
                modifier = Modifier.width(buttonWidth),
            ) {}
        }
    }
}

/**
 * 选项栏底部「继续」脚钮是否出现 —— 只在 WAIT 系页面（`PRINTW` / `PRINTFORMW` /
 * `WAIT` / `WAITANYKEY`）出现。
 *
 * 抽成 internal 纯函数便于 JVM 单测覆盖（同 `ShellUiContinueButtonTest` 的先例）：
 * 这条判据是 2026-10-29 玩家反馈
 * 「钓鱼列表最下面没有可点的退出键」的修复点，删掉它就会退回「只能手输」。
 */
internal fun showContinueButton(awaitingAnyKey: Boolean): Boolean = awaitingAnyKey

/**
 * 选项栏底部的「继续」脚钮 —— WAIT 系页面的显式推进键。
 *
 * 原版这类页面（`PRINTW` / `PRINTFORMW` / `WAIT` / `WAITANYKEY`）**不在屏上印任何 `[N]`**，
 * 推进方式是「点画面空白」或敲任意键（`MainWindow.cs:806-823`）。触摸端既没有回车键，
 * 「点空白也能继续」也没有任何视觉暗示，玩家就会以为页面没有退出键
 * （2026-10-29 玩家反馈：钓鱼列表页最下面没有可点的退出键，只能自己手输）。
 *
 * 点它 = 提交一条**空文本**（`mesSkip = false`），与原版左键点空白走的是同一条路
 * （`BlankTapPolicy` 的 `PressEnterKey(mesSkip: false, inputsByMouse: true)`）：
 * WAIT 系只消费一条提交、不解释文本（`EmueraConsole.cs:1443-1448`），所以空串安全。
 * 它**不是** `[N]` 选项，不进 `chips`、也不参与世代判定 —— 那条路径留给真按钮。
 */
@Composable
private fun ContinueButton(metrics: RailMetrics, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        // 与选项按钮同一套样式（形状/配色/内边距共用，高度与字号随档位走），整栏观感统一。
        shape = RAIL_BUTTON_SHAPE,
        colors = railButtonColors(),
        contentPadding = RAIL_BUTTON_CONTENT_PADDING,
        // 底部让出 16dp：`InputKnob`（52×14dp，贴 Box 右下）叠在整栏之上，
        // 不让开的话脚钮右下角会被抓手压住，点下去变成「开输入栏」。
        // 顶部间距与 LazyColumn 的行距（RAIL_ROW_SPACING）对齐，收尾不留断层。
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = RAIL_ROW_SPACING, bottom = 16.dp)
            .height(metrics.buttonHeight)
            .semantics { contentDescription = "继续，翻到下一屏" },
    ) {
        Text("继续 ▶", fontSize = metrics.labelTextSize, maxLines = 1)
    }
}

/** 深色主题（与游戏黑底同调；Material3 只服务壳层）。 */
@Composable
fun EratwTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7FAF7F),
            background = Color.Black,
            surface = Color(0xFF262626),
            onBackground = Color(0xFFEDEDED),
            onSurface = Color(0xFFEDEDED),
        ),
        content = content,
    )
}

/**
 * 游戏壳：画面（AndroidView）+ 可收起的右侧选项栏 + 自动隐藏输入条 + 设置入口。
 *
 * 分区布局（2026-10-22 真机收口结论）：控制台在上、底栏在下**各占一块**，绝不浮层
 * 覆盖正文；`safeDrawingPadding()` 处理刘海/挖孔（本机左侧有 82px 黑边）。
 *
 * 2026-10-23 二次优化（负责人反馈）：
 * - 输入条默认隐藏，底部留小凸边；上滑/点凸边展开，提交或静置后自动收起（正文高度随之变化）；
 * - 右侧选项栏可收起，设置旁螺旋图标点击旋转 + 滑出/滑入；
 * - 行内 HTML 选项不再画白描边（渲染侧 `CanvasConsoleView` 同步去掉）。
 *
 * [onInputChanged] 在输入框文本每次变化时回调（键入、±、宏预填），把框内文本镜像到
 * 核心层的 `Window.TextBox`：原版这两者是同一个 `RichTextBox`（`MainWindow.cs:210`），
 * 脚本靠 `GETTEXTBOX` 读它（`FunctionMethodCreator.cs:8351-8354`）。
 */
@Composable
fun EratwShell(
    consoleView: CanvasConsoleView,
    state: ShellState,
    onSettings: () -> Unit,
    /** 返回 true = 宿主已接手（入队或轮询屏的文本框+回车事件）；false = 无去处，壳层保持输入条。 */
    onSubmit: (String) -> Boolean,
    onOption: (String) -> Unit,
    onMacroGroup: () -> Unit,
    onRetry: () -> Unit = {},
    onRetryCompat: () -> Unit = {},
    onBackToTitle: () -> Unit = {},
    onCopyReport: () -> Unit = {},
    onShareReport: () -> Unit = {},
    onRailToggle: () -> Unit = {},
    onInputChanged: (String) -> Unit = {},
) {
    // 输入条静置自动收起：任一键入都会重置计时（用户还在打就不收）。
    // **有焦点时永不自动收**：中文输入法组合态（拼音候选未上屏）不会改 state.input，
    // 只看文本变化会把“正在选词”误判成“静置”。失焦后才重新开始计时；
    // 15s 而不是旧 5s —— 真人看屏/选候选的停顿很容易超过 5s。
    LaunchedEffect(state.inputVisible, state.input, state.inputFocused) {
        if (!state.inputVisible || state.inputFocused) return@LaunchedEffect
        delay(15_000)
        if (!state.inputFocused) {
            state.inputVisible = false
            state.inputFocused = false
        }
    }

    // 任何收起路径（点画面、抓手、自动静置、提交成功）都顺手清焦点/收键盘；
    // 只靠 AnimatedVisibility 移除输入框，个别输入法会把软键盘留在屏上。
    val shellKeyboard = LocalSoftwareKeyboardController.current
    val shellFocus = LocalFocusManager.current
    LaunchedEffect(state.inputVisible) {
        if (!state.inputVisible) {
            state.inputFocused = false
            shellFocus.clearFocus()
            shellKeyboard?.hide()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding(),
        ) {
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val areaWidth = maxWidth
                val shellContext = LocalContext.current
                // 每次重组都读一遍偏好（UiPrefs 本来就不缓存、一次 XML 快照微秒级）：
                // 这样在设置页改完「底边抓手位置」返回游戏就立刻生效，不必重启。
                val knobFraction = UiPrefs.inputKnobFraction(shellContext)
                // 右栏几何按当前大小档算（设置页「选项按钮大小」；MainActivity 在
                // onCreate/onResume 把偏好镜像到 state.railButtonSize，所以改完回游戏就是新尺寸）。
                // 再叠两刀自适应：按这块画布的**实际可用宽**夹栏宽（窄画布上「特大」不许盖住正文），
                // 按**系统字体缩放**放开按钮高与角标让位（字号是 sp、尺寸是 dp，不调和就会裁字）。
                val railMetrics = railMetricsForFontScale(
                    railMetricsFitting(railMetricsOf(state.railButtonSize), areaWidth),
                    LocalDensity.current.fontScale,
                )
                // 画布始终占满整宽：右栏是叠在画布之上的浮层（见下方 rail 的 align(TopEnd)）。
                // 2026-10-29 撤回「按栏宽给画布留右内距」：预留会把游戏窗口整块压窄（真机读数
                // 232dp 栏可见时画布只剩 86 列），画面左移、选项一多更容易换行错位——缩画布救不了
                // 栏压内容，只是把排版问题换成「界面变小」。栏要不要挡，交给玩家用螺旋图标收起。
                AndroidView(
                    factory = { consoleView },
                    modifier = Modifier.fillMaxSize(),
                )

                // 「点屏幕别处收起输入栏」不再用 Compose 全屏遮罩：那层会把 down 事件一起吃掉，
                // 控制台的 GestureDetector 收不到滑动 ⇒ 上滑看历史失效（2026-10-24 负责人实测）。
                // 改由控制台 onSingleTapUp 判 shell.inputVisible（见 MainActivity.attachGestures）。

                // 右上角：输入开关（键盘图标）+ 螺旋图标（选项栏开关）+ 设置。
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InputToggle(active = state.inputVisible, onClick = { state.inputVisible = true })
                    SpiralToggle(
                        expanded = state.railVisible,
                        onClick = onRailToggle,
                    )
                    TextButton(onClick = onSettings) {
                        Text("设置", color = Color(0xFFEDEDED), fontSize = 13.sp)
                    }
                }

                // 右侧选项栏：可收起；收起时整栏平移出屏幕右沿（BoxScope 里用 offset 动画，
                // 避免 ColumnScope.AnimatedVisibility 扩展在 Box 里取不到隐式接收者）。
                // 闪烁收口（2026-10-24）：滑入/滑出**只跟用户的 railVisible 开关**。
                // 原来条件里还带了 `chips.isNotEmpty()`，而 chips 每 250ms 由选项扫描刷新，
                // 游戏待机时脚本周期重绘会让扫描结果在「有/无」之间抖动 ⇒ 整排按钮来回滑，
                // 玩家看到的就是「待机也会隔一会儿闪一下」（负责人实测反馈）。
                //
                // 收起位移（2026-10-26）：旧值写死 168dp，而双列宽 196dp ⇒ 左侧一列仍露在
                // 屏内（真机反馈「收起后还能看到最左边一排」）。现在位移由 [railCollapseOffset]
                // 一处算（栏宽 + 右留白 + 余量）；隐藏时按钮 enabled=false，不接收点击/读屏焦点。
                val railOffset by animateDpAsState(
                    targetValue = if (state.railVisible) 0.dp else railCollapseOffset(railMetrics.railWidth),
                    animationSpec = tween(220),
                )

                // 排布（2026-10-26 负责人定调）：**按游戏显示行分行**，不做全局序号排序、
                // 不把不同行的按钮压成两列网格。行内按 startCol 从左到右（引擎已排好），
                // 同一行按钮并排：这样地图的“第 1 行 1 个、第 2 行 2 个、第 3 行 1 个”
                // 在栏里仍是同样的行结构；行内最多并排 3 枚，超过 3 枚时横向滚动，
                // 避免 4 枚等分只剩约 47dp、按钮标题被挤到不可分辨（2026-10-27 用户反馈）。
                val railRows = remember(state.chips) { railRowsOf(state.chips) }
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = railOffset)
                        .padding(top = 44.dp, end = RAIL_END_PADDING)
                        .width(railMetrics.railWidth)
                        .fillMaxHeight(),
                ) {
                    if (state.chips.size > 10) {
                        Text(
                            text = "${state.chips.size} 项可点",
                            color = Color(0xFF9A9A9A),
                            fontSize = railMetrics.labelTextSize,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                        )
                    }
                    LazyColumn(
                        // weight(1f) 而不是 fillMaxSize()：底部要留出「继续」脚钮的位置
                        // （fillMaxSize 会把整栏高度吃光，脚钮被顶到栏外看不见）。
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(RAIL_ROW_SPACING),
                        // 28dp 底距沿用旧值：最后一行按钮要避开底边抓手（52×14dp）。
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 28.dp),
                    ) {
                        items(count = railRows.size) { i ->
                            val row = railRows[i]
                            OptionRailRow(
                                row = row,
                                metrics = railMetrics,
                                enabled = state.railVisible,
                                onOption = onOption,
                            )
                        }
                    }
                    // WAIT 系页面（PRINTW / PRINTFORMW / WAIT）屏上没有任何 [N]：
                    // 常驻一枚「继续」脚钮在栏最底部（不随 LazyColumn 滚走），
                    // 见 [ContinueButton] 与原版「点画面」通道的对应关系。
                    if (showContinueButton(state.awaitingAnyKey)) {
                        ContinueButton(metrics = railMetrics, enabled = state.railVisible) { onSubmit("") }
                    }
                }

                // 底边小抓手：不是大黑条，就一枚圆角小突起（默认贴右、可左右拖）；
                // 点它或向上滑都弹出输入栏。
                if (!state.inputVisible) {
                    InputKnob(
                        fraction = knobFraction,
                        areaWidth = areaWidth,
                        onFractionChange = { },
                        onFractionCommit = {
                            UiPrefs.setInputKnobFraction(shellContext, knobFraction)
                        },
                        onOpen = { state.inputVisible = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 1.dp),
                    )
                }
            }

            // 宏条只在输入条展开时显示（宏的用途就是预填输入框）。
            AnimatedVisibility(
                visible = state.inputVisible && state.macros.isNotEmpty(),
                enter = slideInVertically { it } + fadeIn(tween(120)),
                exit = slideOutVertically { it } + fadeOut(tween(100)),
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1B1B1B))
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item {
                        TextButton(onClick = onMacroGroup) {
                            Text(
                                state.macroGroupLabel.ifEmpty { "宏" },
                                color = Color(0xFFB9D3B9),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    items(state.macros) { macro ->
                        TextButton(onClick = {
                            state.input = macro.content
                            onInputChanged(macro.content)
                        }) {
                            Text(macro.label, color = Color(0xFFDCDCDC), fontSize = 12.sp)
                        }
                    }
                }
            }

            // 输入条只保留展开态（拖动条 + 输入框）；收起后不再占屏幕底边一整条，
            // 改由右上角的键盘图标唤出（2026-10-23 负责人反馈：底部「上滑输入」黑条太大）。
            AnimatedVisibility(
                visible = state.inputVisible,
                enter = slideInVertically { it } + fadeIn(tween(140)),
                exit = slideOutVertically { it } + fadeOut(tween(120)),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    InputGrabBar(onDragDown = {
                        state.inputVisible = false
                        state.inputFocused = false
                    })
                    InputBar(
                        state = state,
                        onSubmit = onSubmit,
                        onInputChanged = onInputChanged,
                    )
                }
            }
        }

        // 装载期覆盖层：进度条 + 明细（Compose 侧，不再只靠 console 文本）。
        if (state.loading) {
            LoadingOverlay(state)
        }
        // 失败覆盖层：原因 + 「回到标题」/「兼容模式重试」（堆不足族才亮）/「重试」按钮。
        state.failure?.let { message ->
            FailureOverlay(
                message = message,
                canBackToTitle = state.failureCanBackToTitle,
                canRetryCompat = state.failureCanRetryCompat,
                reportReady = state.failureReportReady,
                onRetry = onRetry,
                onRetryCompat = onRetryCompat,
                onBackToTitle = onBackToTitle,
                onCopyReport = onCopyReport,
                onShareReport = onShareReport,
            )
        }
        // 长按按钮提示卡（原版 PC hover ToolTip 的安卓替身）。
        // 必须用 Dialog 而不是 Box 叠层：控制台是 AndroidView，Compose 兄弟节点的
        // pointerInput 盖不住它，触摸会直接落到 View 上；Dialog 是独立窗口，能同时挡住
        // 触摸与返回键。
        state.tooltip?.let { raw -> ButtonTooltipOverlay(state, raw) }
    }
}

/**
 * 长按按钮的提示卡（原版 PC hover ToolTip 的安卓替身）。
 *
 * `title` 原文里的 `<br>` 换成换行（`EmueraConsole.cs:1909` 同口径）；点卡外/返回键关闭，
 * 长文案在卡内纵向滚动，不撑出屏幕。
 */
@Composable
private fun ButtonTooltipOverlay(state: ShellState, raw: String) {
    val text = remember(raw) { raw.replace("<br>", "\n").replace("<BR>", "\n") }
    Dialog(
        onDismissRequest = { state.tooltip = null },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .pointerInput(Unit) { detectTapGestures { state.tooltip = null } },
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF454545), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Text(text = text, color = Color(0xFFE6E6E6), fontSize = 13.sp, lineHeight = 20.sp)
                Text(
                    text = "点任意处关闭",
                    color = Color(0xFF8A8A8A),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

    /**
     * 右上角的输入开关（键盘图标）—— 取代原来占屏幕底边一整条的「上滑输入」黑条。
     *
     * 图标与 [SpiralToggle] 同一套线的语言：24dp 画布、2.2px 描边、灰（`0xFF8A8A8A`）。
     * 点一下把输入栏从底部滑出；输入栏自带的拖动条负责收起（5 秒静置也会自动收起）。
     */
    @Composable
    private fun InputToggle(active: Boolean, onClick: () -> Unit) {
        // 点击反馈：图标的「咔哒抖一下」（-10° → +10° → -6° → 0），与螺旋图标的旋转同一套
        // 手感动词（参考它但改成抖动）。用 Animatable + 协程，点击瞬间重放。
        val shake = remember { androidx.compose.animation.core.Animatable(0f) }
        val scope = rememberCoroutineScope()
        val fire: () -> Unit = {
            scope.launch {
                shake.animateTo(-10f, tween(60))
                shake.animateTo(10f, tween(80))
                shake.animateTo(-6f, tween(60))
                shake.animateTo(0f, tween(60))
            }
            onClick()
        }
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (active) Color(0x33B9D3B9) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                )
                .semantics { contentDescription = if (active) "键盘(已展开)" else "键盘(已收起)" }
                .clickable(onClick = fire),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(24.dp).rotate(shake.value)) {

                // 高亮（2026-10-25 主人反馈「点了没高亮」）：输入栏展开时图标转浅绿，
                // 与螺旋图标的「已展开」同色；之前只改了语义标签、颜色那行漏改。
                val gray = if (active) Color(0xFFB9D3B9) else Color(0xFF8A8A8A)
                val stroke = 2.2f * density
                val left = size.width * 0.06f
                val top = size.height * 0.24f
                val right = size.width * 0.94f
                val bottom = size.height * 0.76f
                drawRoundRect(
                    color = gray,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = CornerRadius(3f * density, 3f * density),
                    style = Stroke(width = stroke),
                )
                val dotR = 1.6f * density
                for (row in 0 until 2) {
                    val y = top + (bottom - top) * (0.34f + row * 0.30f)
                    for (col in 0 until 4) {
                        val x = left + (right - left) * (0.18f + col * 0.22f)
                        drawCircle(color = gray, radius = dotR, center = Offset(x, y))
                    }
                }
            }
        }
    }
/**
     * 底边的小抓手（取代原来的「上滑输入」大黑条）。
     *
     * 形态：52×14dp 的圆角小突起 + 中间一枚 `︿`，半透明深底 —— 不再遮画面。
     * 交互：默认贴右侧（`fraction` 缺省 0.97，位置记在 [UiPrefs.inputKnobFraction]）；
     * **可左右拖动**；点它、或手指在它上面**向上滑**都会把输入栏弹出来。
     * 输入栏展开后它自己隐藏（收起靠：上拖输入条 / 点屏幕其他地方 / 5 秒静置）。
     */
    @Composable
    private fun InputKnob(
        fraction: Float,
        areaWidth: Dp,
        onFractionChange: (Float) -> Unit,
        onFractionCommit: () -> Unit,
        onOpen: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val knobDensity = LocalDensity.current
        val areaPx = with(knobDensity) { areaWidth.toPx() }.coerceAtLeast(1f)
        var dragUp by remember { mutableFloatStateOf(0f) }
        Box(
            modifier = modifier
                .semantics { contentDescription = "底边抓手" }
                .offset(x = areaWidth * (fraction - 1f))
                .size(width = 52.dp, height = 14.dp)
                .background(Color(0xB31B1B1B), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                // 单入口手势（2026-10-24 鹰眼实测修）：原来同时挂 clickable + draggable +
                // detectVerticalDragGestures，三者抢同一个 down —— 垂直拖的 pointerInput 先
                // 消费事件，`clickable` 永远收不到 tap、上滑也被横拖判据抢走，结果是
                // 「点抓手没反应、上滑也没反应」（画面变化 0.00%）。
                // 现在一个 awaitEachGesture 里分流：移动不足 touchSlop = 点击（开输入栏）；
                // 横向为主 = 左右挪位置；纵向为主且上滑 = 开输入栏。
                .pointerInput(areaPx, fraction) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dx = 0f
                        var dy = 0f
                        var dragged = false
                        val slop = viewConfiguration.touchSlop
                        while (true) {
                            val ev = awaitPointerEvent()
                            val change = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            dx += change.positionChange().x
                            dy += change.positionChange().y
                            if (!dragged && (abs(dx) > slop || abs(dy) > slop)) dragged = true
                            if (dragged) {
                                change.consume()
                                if (abs(dx) >= abs(dy)) {
                                    onFractionChange((fraction + dx / areaPx).coerceIn(0.03f, 0.97f))
                                }
                            }
                        }
                        when {
                            !dragged -> onOpen()
                            abs(dy) > abs(dx) && dy < -slop -> onOpen()
                            abs(dx) >= abs(dy) -> onFractionCommit()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(width = 18.dp, height = 6.dp)) {
                val p = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                }
                drawPath(p, color = Color(0xFF9A9A9A), style = Stroke(width = 1.4f * this.density))
            }
        }
    }

    /** 输入条上方的抓握条：向下拖（>18px）收起输入条。 */
    @Composable
    private fun InputGrabBar(onDragDown: () -> Unit) {
    var dragAccum = 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .background(Color(0xFF262626))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragAccum > 18f) onDragDown()
                        dragAccum = 0f
                    },
                    onDragCancel = { dragAccum = 0f },
                ) { _, dragAmount -> dragAccum += dragAmount }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(4.dp)
                .background(Color(0xFF6E6E6E), RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * 展开态输入条：文本域 + 提交。
 *
 * 提交有三条入口，全部走同一个 [doSubmit]：
 * 1. 输入法「完成」/「前往」/「发送」（[KeyboardActions]）；
 * 2. 硬件/外接键盘 Enter、小键盘 Enter（[onPreviewKeyEvent]，部分输入法把「完成」
 *    当普通回车键发，单靠 `onDone` 会漏）；
 * 3. 右侧「提交」按钮。
 *
 * 250ms 冷却防同一次按下被 IME 同时报成 action + key event（原版一次回车只交一条）。
 * **只有宿主接手（返回 true）才收起**；VM 已停/没有去处时保持输入条与文字，提示留在
 * [ShellState.hint] 上，不再“框没了、值也没交”。
 */
@Composable
private fun InputBar(
    state: ShellState,
    onSubmit: (String) -> Boolean,
    onInputChanged: (String) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // 初值取「已经过了冷却」：`0` 在刚开机的 250ms 窗口里会让第一次提交被吃掉。
    var lastSubmitAt by remember { mutableLongStateOf(-SUBMIT_DEBOUNCE_MS) }

    val doSubmit: () -> Unit = submit@{
        // 用 elapsedRealtime 而不是 currentTimeMillis：后者是墙上时钟，NTP 校时/时区跳变
        // 会让 `now - lastSubmitAt` 变成负数，负数同样 < 250 ⇒ 提交被冷却闸吃掉。
        val now = SystemClock.elapsedRealtime()
        if (now - lastSubmitAt < SUBMIT_DEBOUNCE_MS) return@submit
        lastSubmitAt = now
        if (onSubmit(state.input)) {
            state.inputFocused = false
            state.inputVisible = false
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF262626))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 负号开关（DEV-07）：Android 的数字键盘没有负号键，而原版整数取值走
        // `long.TryParse`、收负数（EmueraConsole.cs:1125）。这里补一枚「±」，只在整数等待
        // （`numberKeyboard`）时出现；文本变换是纯函数，见 InputBarState.toggleSign。
        if (state.numberKeyboard) {
            TextButton(
                onClick = {
                    state.input = InputBarState.toggleSign(state.input)
                    onInputChanged(state.input)
                },
                modifier = Modifier.width(44.dp).height(40.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFB9D3B9)),
            ) {
                Text("±", fontSize = 20.sp)
            }
            Spacer(Modifier.width(6.dp))
        }
        OutlinedTextField(
            value = state.input,
            onValueChange = {
                state.input = it
                onInputChanged(it)
            },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { state.inputFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        doSubmit()
                        true
                    } else {
                        false
                    }
                },
            placeholder = { Text(state.hint, color = Color(0xFF8A8A8A), fontSize = 15.sp) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = Color(0xFFE8E8E8),
                fontSize = 15.sp,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (state.numberKeyboard) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            // 有些输入法把「完成」报成 Go/Send；三条都接，配合 250ms 冷却不会重复提交。
            keyboardActions = KeyboardActions(
                onDone = { doSubmit() },
                onGo = { doSubmit() },
                onSend = { doSubmit() },
            ),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1A1A1A),
                unfocusedContainerColor = Color(0xFF1A1A1A),
                focusedBorderColor = Color(0xFF4A6A4A),
                unfocusedBorderColor = Color(0xFF3A3A3A),
                cursorColor = Color(0xFF9FC79F),
            ),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = { doSubmit() },
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF3A3A3A),
                contentColor = Color(0xFFEDEDED),
            ),
        ) {
            Text("提交", fontSize = 15.sp)
        }
    }
}

/**
 * 螺旋图标（右侧选项栏开关）：点击后图标旋转 0↔180°，栏位随之滑出/收起。
 *
 * 用 Canvas 手画阿基米德螺线（Material 图标集里没有螺线；emoji 会带彩色，不适合深色壳层）。
 */
@Composable
private fun SpiralToggle(expanded: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(420),
    )
    Box(
        modifier = Modifier
            .size(40.dp)
            .semantics { contentDescription = if (expanded) "选项栏(已展开)" else "选项栏(已收起)" }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(24.dp).rotate(rotation)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxRadius = size.minDimension / 2f - 1f
            val turns = 2.2f
            val steps = 96
            val path = Path()
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val angle = t * turns * 2f * Math.PI.toFloat()
                val radius = maxRadius * t
                val x = center.x + radius * cos(angle)
                val y = center.y + radius * sin(angle)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = if (expanded) Color(0xFFB9D3B9) else Color(0xFF8A8A8A),
                style = Stroke(width = 2.2f * density),
            )
        }
    }
}

/**
 * 右侧选项栏的一枚**紧凑**按钮（2026-10-24 重做，适配一屏 20+ 选项）。
 *
 * 形态：单行标题（超出省略号）占满按钮，序号退到右上角作浅绿小角标（次要信息）。
 * 高度与字号按当前大小档（[RailMetrics]，设置页「选项按钮大小」）取，与「继续」脚钮同源。
 * 原来是一枚 14sp、上下 12dp 内边距的大按钮 —— 十个以内好看，二十个以上一屏放不下，
 * 玩家要滚动找选项，且按钮过高会让「点不中」变多。
 */
@Composable
private fun OptionChipButton(
    label: String,
    ordinal: Int?,
    metrics: RailMetrics,
    enabled: Boolean = true,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(metrics.buttonHeight),
        shape = RAIL_BUTTON_SHAPE,
        contentPadding = RAIL_BUTTON_CONTENT_PADDING,
        colors = railButtonColors(),
    ) {
        // 序号退到右上角作角标（次要位置），标题从按钮左侧起排、占满横向空间；
        // 数值按钮没有标题时序号仍作主文本显示；标签本身就是同一个数字
        // （地图格子的 `69`）时只画一次，避免「69 69」。
        val cornerBadge = ordinal?.takeIf { label.isNotEmpty() && label != it.toString() }
        Box(modifier = Modifier.fillMaxWidth()) {
            if (cornerBadge != null) {
                Text(
                    text = "$cornerBadge",
                    color = Color(0xB3B9D3B9),
                    fontSize = metrics.badgeTextSize,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 1.dp, end = 1.dp),
                )
            }
            Text(
                text = label.ifEmpty { ordinal?.toString().orEmpty() },
                fontSize = metrics.labelTextSize,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(end = cornerBadge?.let { railBadgeReserve(it.toString().length, metrics) } ?: 0.dp),
            )
        }
    }
}

/**
 * 覆盖层专用：把落在这一层上的指针事件全部标为已消费（DEV-03）。
 *
 * 为什么必须显式消费：覆盖层根原来只有 `Box + background`，而 Compose 的命中路径只收带指针
 * 修饰符的节点 —— 没有这一层修饰符时，触摸直接落下层去：控制台视图（`AndroidView`）收到点击，
 * 右上角「设置」这类 Compose 按钮也照常响应；解包中点进设置再重启，等于把首启解包搅成两份
 * （放大 DEV-02）。
 *
 * 用法：盖在覆盖层根里、**排在内容之前**（内容在上、消费层在下），所以失败屏的「回到标题」
 * 「重试」这类内部按钮仍是最上层的命中节点、照常可点；点空白处只有这一层命中，整条指针流被
 * 吃掉。消费的是**整条**指针流（按下/移动/抬起全标记已消费），不是只识别单击 —— 下层既收不到
 * 点，也收不到滑。
 */
private fun Modifier.consumeAllPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { change -> change.consume() }
        }
    }
}

/**
 * 装载覆盖层：按 [UiPrefs.loadingStyle] 分「像素风（缺省）」/「现代风」两版。
 *
 * 玩家面上只留「正在进入这个世界」这一件事：条目数、游戏根、产物目录、adb 提示这些排障
 * 读数一律不进这里（2026-10-23 负责人反馈：首启屏整屏都是 `随包数据已就位（10256 个条目 /
 * 734 MiB）` —— 技术字直面玩家）。排障读数仍在控制台与失败屏上。
 *
 * 覆盖时机：从启动一直盖到 `GameHost.onConsoleReady`（程序装载完成、脚本即将开始执行）——
 * 那之后紧接着就是脚本自己画的「少女祈祷中」进度条。
 */
@Composable
private fun LoadingOverlay(state: ShellState) {
    when (UiPrefs.loadingStyle(LocalContext.current)) {
        UiPrefs.LoadingStyle.PIXEL -> PixelLoadingOverlay(state)
        UiPrefs.LoadingStyle.MODERN -> ModernLoadingOverlay(state)
    }
}

/**
 * 像素风：方块进度条 + 硬边框 + 粗等宽字 —— 对着游戏本身的 2000 年代文字游戏画风。
 *
 * 为什么不用「真的点阵字体文件」：本机没有可合法随包的中文点阵字体（语料里的 Saitamaar 是
 * 字符画字体、只覆盖日文；OFL 的中文点阵字体今天下载源被限流）。这里用等宽字体 + 大字号
 * 间距 + 方块条 + 扫描线做出同一种观感，等哪天字体到位只换 `fontFamily` 一处。
 */
@Composable
private fun PixelLoadingOverlay(state: ShellState) {
    val progress = state.progress
    // 真点阵字体（Ark Pixel 12px 等宽 zh_hans，OFL-1.1，见 assets/licenses/）
    val pixelFont = FontFamily(Font(R.font.ark_pixel))
    val blink = rememberInfiniteTransition(label = "pixel-blink")
    val pulse by blink.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 620, easing = LinearEasing)),
        label = "pulse",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        // 指针消费层（DEV-03）：整屏、排在内容之前 ⇒ 内部可点项在它上面，其余点击被吃掉。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeAllPointerEvents(),
        )
        // 扫描线（廉价：整屏每 3dp 一条 1px 半透明黑线）
        Canvas(modifier = Modifier.fillMaxSize()) {
            var y = 0f
            while (y < size.height) {
                drawLine(
                    color = Color(0x22FFFFFF),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += 9f
            }
        }
        Column(
            modifier = Modifier.width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "eraThe World",
                color = Color(0xFFD8D4BC),
                fontSize = 24.sp,
                letterSpacing = 2.sp,
                fontFamily = pixelFont,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "― 正 在 进 入 这 个 世 界 ―",
                color = Color(0xFF8C8C6E),
                fontSize = 12.sp,
                fontFamily = pixelFont,
            )
            Spacer(Modifier.height(26.dp))
            // 方块进度条：24 格，点亮的格数 = 进度；未知总量时留一格闪烁
            val cells = 24
            val lit = when {
                progress != null -> (progress * cells).toInt().coerceIn(0, cells)
                else -> 0
            }
            Row(
                modifier = Modifier
                    .border(1.dp, Color(0xFF3A3A2E))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(cells) { i ->
                    val on = i < lit || (progress == null && i == (pulse * cells).toInt().coerceIn(0, cells - 1))
                    Box(
                        modifier = Modifier
                            .width(10.dp)
                            .height(12.dp)
                            .background(if (on) Color(0xFFA8C88E) else Color(0xFF1E1E18)),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (progress != null) "[ ${(progress * 100).toInt()}% ]" else "[ ... ]",
                color = Color(0xFFB8C8A0),
                fontSize = 12.sp,
                fontFamily = pixelFont,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "▶ " + state.loadingTitle.ifEmpty { "正在准备游戏数据…" },
                color = Color(0xFF7A7A62),
                fontSize = 12.sp,
                fontFamily = pixelFont,
            )
        }
    }
}

/** 现代风：深色渐变底 + 细渐变进度条 + 轻字重（2026-10-23 首版）。 */
@Composable
private fun ModernLoadingOverlay(state: ShellState) {
    val progress = state.progress
    val transition = rememberInfiniteTransition(label = "loading")
    // 总量未知时来回扫的窄光带（已知总量时走填充，不画它）
    val sweep by transition.animateFloat(
        initialValue = -0.30f,
        targetValue = 1.30f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing)),
        label = "sweep",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF060807)),
        contentAlignment = Alignment.Center,
    ) {
        // 指针消费层（DEV-03）：同像素风，整屏压在下层之上、排在内容之前。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeAllPointerEvents(),
        )
        Column(
            modifier = Modifier.width(300.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "eraThe World",
                color = Color(0xFFE7E3D6),
                fontSize = 25.sp,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "正在进入这个世界",
                color = Color(0xFF7C887C),
                fontSize = 12.sp,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(36.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(percent = 50)),
            ) {
                val w = size.width
                val h = size.height
                drawRect(color = Color(0xFF1B201C), size = Size(w, h))
                if (progress != null) {
                    val filled = (w * progress).coerceIn(0f, w)
                    if (filled > 0f) {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(Color(0xFF6F9E70), Color(0xFFB7D8A9)),
                            ),
                            size = Size(filled, h),
                        )
                    }
                } else {
                    val band = w * 0.22f
                    drawRect(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0x00303430), Color(0xFF9CC79C), Color(0x00303430)),
                        ),
                        topLeft = Offset((w + band) * sweep - band, 0f),
                        size = Size(band, h),
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            Text(
                text = if (progress != null) "${(progress * 100).toInt()}%" else "· · ·",
                color = Color(0xFF9DB79D),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.loadingTitle.ifEmpty { "正在准备游戏数据…" },
                color = Color(0xFF6A736A),
                fontSize = 11.sp,
            )
        }
    }
}

/**
 * 失败覆盖层：红标题 + 原文 + 「回到标题」/「兼容模式重试」/「重试」。
 *
 * 两条出口的分工照原版：「回到标题」= `GotoTitle`（`EmueraConsole.cs:2569-2581`，
 * 进程内复位后重进标题）；「重试」= 拆会话重新装载（移植侧没有原版的 `Reboot()`
 * 半程语义可用， closest 是重启一局，见 `MainActivity.retryLoad`）。
 *
 * 「兼容模式重试」（2026-10-28 低端机 OOM）只在堆不足族失败时点亮：串行装载 +
 * 段间强制回收，装载更慢但堆峰值更低 —— 512 MiB 挡本来就贴边，这是玩家能自救的
 * 唯一路径（不用记什么 adb 开关，一次点击）。
 */
@Composable
private fun FailureOverlay(
    message: String,
    canBackToTitle: Boolean,
    canRetryCompat: Boolean,
    reportReady: Boolean,
    onRetry: () -> Unit,
    onRetryCompat: () -> Unit,
    onBackToTitle: () -> Unit,
    onCopyReport: () -> Unit,
    onShareReport: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF2000000)),
        contentAlignment = Alignment.Center,
    ) {
        // 指针消费层（DEV-03）：压在整屏、排在内容之前 ——「回到标题」「重试」在它上面，
        // 点按钮照旧生效；点其它地方全被吃掉，不会穿到下层控制台与右上角「设置」。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .consumeAllPointerEvents(),
        )
        Column(
            modifier = Modifier.width(520.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("启动失败", color = Color(0xFFFF7B7B), fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            Text(message, color = Color(0xFFEDEDED), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (canBackToTitle) {
                    "「回到标题」在原会话上复位后重进标题（不清存档、不重新装载）；" +
                        "「重试」关闭当前会话并重新装载。"
                } else {
                    "重试会关闭当前会话并重新装载；若反复失败请检查产物目录。"
                },
                color = Color(0xFF9A9A9A),
                fontSize = 12.sp,
            )
            if (canRetryCompat) {
                Text(
                    "「兼容模式重试」串行装载并逐段回收垃圾，装载更慢但内存占用更低；" +
                        "本次运行会一直沿用。",
                    color = Color(0xFF9A9A9A),
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(20.dp))
            if (reportReady) {
                // 方案① 报错收集：玩家手动把报告发给开发者（剪贴板直贴群聊 / 系统分享面板）。
                // 次要按钮样式（小一号、暗底），不抢「重试 / 回到标题」的视觉主次。
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onCopyReport,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A2A2A),
                            contentColor = Color(0xFFEDEDED),
                        ),
                    ) {
                        Text("复制报告", fontSize = 14.sp)
                    }
                    Button(
                        onClick = onShareReport,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2A2A2A),
                            contentColor = Color(0xFFEDEDED),
                        ),
                    ) {
                        Text("分享报告", fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
            if (canBackToTitle) {
                Button(
                    onClick = onBackToTitle,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2F4B6B),
                        contentColor = Color(0xFFEDEDED),
                    ),
                ) {
                    Text("回到标题", fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
            if (canRetryCompat) {
                // 兼容模式重试：与「回到标题」同款主按钮色 —— 它是堆不足时真正能救命的那颗，
                // 视觉上要压过普通「重试」（兼容档反复重试还是同样的峰值，Compat 才是出路）。
                Button(
                    onClick = onRetryCompat,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2F4B6B),
                        contentColor = Color(0xFFEDEDED),
                    ),
                ) {
                    Text("兼容模式重试", fontSize = 16.sp)
                }
                Spacer(Modifier.height(10.dp))
            }
            Button(
                onClick = onRetry,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF3A3A3A),
                    contentColor = Color(0xFFEDEDED),
                ),
            ) {
                Text("重试", fontSize = 16.sp)
            }
        }
    }
}
