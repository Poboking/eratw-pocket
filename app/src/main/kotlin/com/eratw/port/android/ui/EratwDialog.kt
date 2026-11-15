package com.eratw.port.android.ui

import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowInsetsController
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/**
 * 弹窗独立窗口的 edge-to-edge 装配（每次组合执行一次，[SideEffect] 幂等）。
 *
 * ## 为什么需要它（2026-11-12 玩家反馈「教程在某些手机上，右侧会截断」）
 * Compose `Dialog` 是独立窗口，`layoutInDisplayCutoutMode` 此前全仓从未声明，交给平台
 * 缺省（MODE_DEFAULT）随 ROM 分叉：部分机型（沉浸式全屏 + 挖孔屏，横屏挖孔在侧边）会把
 * 窗口 letterbox 到挖孔安全区，而 Compose ui 1.7.x 的 Dialog 用
 * `Configuration.screenWidthDp` 量内容（上游 b/275732345，1.8.0-alpha08 才修；本仓
 * composeBom 2024.12.01 = ui 1.7.x），与被 letterbox 的窗口可视宽不一致 ⇒
 * `fillMaxSize()` / `fillMaxWidth(0.92f)` 的内容按整屏宽测量、溢出可视区被裁 —— 横屏即
 * 「右侧截断」，竖屏即「底部截断」（上游 b/290502769 同症，Samsung One UI 上尤甚）。
 *
 * 修法是把窗口语义**显式声明**：延伸进挖孔区 + 窗口边界钉满 MATCH_PARENT，让「Compose
 * 度量宽 = 窗口可视宽」成为我们声明的性质而不是 ROM 的运气；挖孔/系统栏让出的部分由
 * 内容侧 `safeDrawingPadding()`（教程）或居中封顶卡自身（[EratwDialogCard]）避让。这是
 * 窗口装配层的根修，教程与共用卡的所有弹窗都从这里取装配，不再各写各的。
 *
 * - **挖孔延伸**：R+ 用 ALWAYS（长边挖孔也延伸，弹窗要占满整屏），P..R 用 SHORT_EDGES。
 *   字段本身是 P 才加的，P 以下设备上引用它会 NoSuchFieldError，所以必须与 SDK 判断
 *   同支；P 以下没有官方刘海概念，不设。**治截断的主力是这一条**：窗口不再被 WM
 *   letterbox 到挖孔安全区（反编译 ui-android 1.7.6 证实：`DialogLayout` 在
 *   `usePlatformDefaultWidth=false` 下按 `Configuration.screenWidthDp` 量内容并逐帧回写
 *   `Window.setLayout` —— 量到的宽一旦比可视区宽，溢出侧就被裁；ALWAYS 让可视区=整屏）。
 * - **窗口边界**：MATCH_PARENT 只盖「窗口挂上 → 内容首次布局」的首帧 —— 上述
 *   `DialogLayout` 每次布局都会用 child 实测尺寸回写 `Window.setLayout`；child 是
 *   fillMaxSize，实测 = 整屏可用界，与 MATCH_PARENT 等价。这里钉一下只为首帧不闪。
 * - **decorFitsSystemWindows = false**（`DialogProperties` 层，配 `WindowCompat`）：
 *   insets 全量下发内容，`safeDrawingPadding` 才拿得到挖孔/系统栏 inset。R+ 走窗口级
 *   API；P- 由 compat 层落成 decorView 的 `LAYOUT_*` flags，同样生效（非「低版本无操作」）。
 * - **系统栏**（仅 [immersive]）：语义与 MainActivity.applyImmersiveMode 完全同款
 *   （R+ insetsController 藏状态栏与导航栏 + 滑入即临时显示；P- 走 systemUiVisibility 同款
 *   flags）。窗口延伸进系统栏区之后，系统栏若还可见会浮在内容之上，必须藏。
 */
@Composable
internal fun EratwDialogWindowWire(immersive: Boolean) {
    val view = LocalView.current
    SideEffect {
        // Dialog 内容里的 LocalView 是对话框的 ComposeView，它的 parent 实现
        // DialogWindowProvider —— 这是 Compose 拿自己窗口的官方通道。
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        window.attributes = window.attributes.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        if (immersive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
                    )
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
        }
    }
}

/**
 * 壳层 Compose 弹窗与共文字形的唯一出处。
 *
 * ## 收口前的样子
 * 长按提示卡、脏退出报告卡、补丁导入覆盖层三处各写各的：面板底 `0xFF2A2A2A` 与
 * `MaterialTheme.colorScheme.surface` 两种、描边 `0xFF454545` 与「没有描边」两种、圆角 12dp 与
 * 14dp 两种、警示红直接抄 Material 色板的 `0xFFD32F2F`。同一个应用里三种「卡片」，
 * 玩家读到的是三套语言。
 *
 * ## 收口后的规矩
 * [EratwDialogCard] 只管**外壳**（遮罩、居中、封顶宽高、可滚、面板底 + 发丝描边），卡里写什么
 * 由调用方决定；文字一律走 [EratwText] 的四档语气（[EratwTextTone]），按钮只有三颗
 * （[EratwFilledButton] 主 / [EratwGhostButton] 次 / [EratwQuietButton] 收场）。
 * 颜色一律从 [EratwInk] 取，本文件不出现第二个 `0xFF`。
 *
 * 遮罩与点击穿透沿用长按提示卡那条既有结论：控制台是 `AndroidView`，Compose 兄弟节点的手势
 * 盖不住它，所以浮层必须是独立 `Dialog` 窗口；遮罩层吃点击，卡内的可点组件先消费事件，
 * 不会被父层的手势抢走。
 *
 * [scrollable] 默认开（整卡纵向滚，短弹窗最省事）；**页状弹窗要关掉它** —— 卡内已经分了
 * 各自滚的段落（补丁导入覆盖层的状态区与报告区）时，外层再滚会和内层抢高度约束。
 *
 * [immersive] 缺省 true（窗口跟随宿主藏系统栏）；系统栏可见的宿主（设置页：教程重阅、
 * 补丁导入覆盖层）传 false —— 那边的系统栏是页面语义的一部分，只做几何装配不藏栏。
 * 窗口几何本身（挖孔延伸 + MATCH_PARENT）统一走 [EratwDialogWindowWire]，见它的 KDoc
 * （2026-11-12「教程在某些手机上右侧截断」的根修）。
 */
@Composable
internal fun EratwDialogCard(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    scrim: Color = EratwInk.scrim(),
    dismissOnClickOutside: Boolean = true,
    maxWidth: Dp = 460.dp,
    maxHeight: Dp = 520.dp,
    scrollable: Boolean = true,
    immersive: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
            // insets 全量下发内容：挖孔/系统栏 inset 才能进测量，窗口也才被允许延伸进
            // 挖孔区（见 EratwDialogWindowWire）。R+ 走窗口级 API；P- 由 WindowCompat
            // 落成 decorView 的 LAYOUT_* flags，同样生效。
            decorFitsSystemWindows = false,
        ),
    ) {
        EratwDialogWindowWire(immersive = immersive)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrim)
                .then(
                    if (dismissOnClickOutside) {
                        Modifier.pointerInput(Unit) { detectTapGestures { onDismissRequest() } }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = maxWidth)
                    .heightIn(max = maxHeight)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .clip(RoundedCornerShape(14.dp))
                    .background(EratwInk.raised())
                    .border(1.dp, EratwInk.hairline(), RoundedCornerShape(14.dp))
                    .padding(20.dp),
                content = content,
            )
        }
    }
}

/** 弹窗正文的四档语气：字号、行距、颜色、字重一起定，免得同一段话两处差 1sp。 */
internal enum class EratwTextTone {
    /** 卡内标题，或玩家必须先看到的那一行。 */
    HEADLINE,

    /** 正文。 */
    BODY,

    /** 解释性次要句。 */
    NOTE,

    /** 注脚、文件名、时间戳这类「在但不抢注意力」的行。 */
    MUTED,
    ;

    val fontSize: Int
        get() = when (this) {
            HEADLINE -> 16
            BODY, NOTE -> 13
            MUTED -> 11
        }

    val lineHeight: Int
        get() = when (this) {
            HEADLINE -> 22
            BODY, NOTE -> 20
            MUTED -> 16
        }

    val color: Color
        get() = when (this) {
            HEADLINE, BODY -> EratwInk.text()
            NOTE -> EratwInk.textDim()
            MUTED -> EratwInk.textMuted()
        }

    /** 只有标题档加粗：正文加粗等于满屏都是重点。 */
    val weight: FontWeight
        get() = if (this == HEADLINE) FontWeight.Medium else FontWeight.Normal
}

/** 四档语气之一；[colorOverride] 只给语义色（危险 / 警告）留口子，字号行距仍由 [tone] 定。 */
@Composable
internal fun EratwText(
    text: String,
    tone: EratwTextTone = EratwTextTone.BODY,
    modifier: Modifier = Modifier,
    colorOverride: Color? = null,
) {
    Text(
        text = text,
        color = colorOverride ?: tone.color,
        fontSize = tone.fontSize.sp,
        lineHeight = tone.lineHeight.sp,
        fontWeight = tone.weight,
        modifier = modifier,
    )
}

/** 卡内小标题：比 [EratwTextTone.HEADLINE] 低一档，用于「补丁状态」这类分区块的名字。 */
@Composable
internal fun EratwSubhead(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = EratwInk.textDim(),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
}

/** 一条发丝分隔线：把「说明」与「按钮区」切开，不引入新的底色。 */
@Composable
internal fun EratwHairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.height(1.dp), color = EratwInk.border(), thickness = 1.dp)
}

/** 主按钮：实心，用于「这一步玩家最该点的那颗」。[container] 留给分享一类的特殊语义色。 */
@Composable
internal fun EratwFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = EratwInk.primaryButton(),
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = EratwInk.text(),
        ),
    ) {
        Text(text = text, fontSize = 14.sp)
    }
}

/** 次按钮：描边不填底，与主按钮同宽同高，成对出现时读得出主次。 */
@Composable
internal fun EratwGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(40.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        border = BorderStroke(1.dp, EratwInk.border()),
    ) {
        Text(text = text, color = EratwInk.text(), fontSize = 14.sp)
    }
}

/** 收场按钮：整行灰字（「知道了」这一类），不给它任何视觉重量。 */
@Composable
internal fun EratwQuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(text = text, color = EratwInk.textMuted(), fontSize = 13.sp)
    }
}

/** 一行等宽按钮的排布；主与次的先后由调用方按「哪颗才是出路」决定。 */
@Composable
internal fun EratwButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}
