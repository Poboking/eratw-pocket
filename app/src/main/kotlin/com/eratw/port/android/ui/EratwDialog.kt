package com.eratw.port.android.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

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
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = dismissOnClickOutside,
        ),
    ) {
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
