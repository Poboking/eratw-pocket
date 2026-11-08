package com.eratw.port.android.portrait

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.eratw.port.android.PortraitShellCopy
import com.eratw.port.android.UiPrefs
import androidx.compose.ui.unit.sp

/**
 * 立绘舞台（M1 形态；设计稿 `docs/archive/竖屏布局设计稿.md` §3.6.3 的**分期边界**）：
 *
 *   * **M1 只做标题条 + 折叠**。立绘本体仍在正文 canvas 流内渲染（render 已实现立绘
 *     图片行几何，BOARD 2026-10-26），壳层**不碰**、不做任何图片裁剪/重排——那需要
 *     render 暴露 sprite 矩形，属 M2（§3.6.3「M1 = canvas 流内；M2 = 独立舞台」）。
 *   * 标题条 = `立绘 N/10`。M1 壳层**拿不到立绘张数**（sink 行文本不带图片计数，
 *     `LineBufferConsoleSink.DisplayLine` 只有文本段与按钮；张数要等 M2 的 render
 *     只读接口），所以 [count] 传 null 时标题退化为「立绘」——不编造数字（§0-5 同族）。
 *   * 折叠状态记 `UiPrefs.sprite_stage_collapsed`（**跨屏记忆**，§3.6.3：玩家既然想看
 *     文字，就别每屏弹回来）；读写由调用侧（PortraitShellContent）接线，本文件只画。
 *   * 「指令盘展开 / 状态详情展开时舞台让位」也是调用侧的事（§3.6.1 高度联动 /
 *     §4.3）：让位用 AnimatedVisibility **真移除**（§0-15），且**不改写**玩家的折叠
 *     偏好——恢复后仍是玩家自己选的那个状态。
 *
 * M1 的「展开」态 = 标题条 + 一行位置提示（立绘在正文流内可滚动查看）；等 M2 舞台
 * 落地后这里换成横滑舞台本体，折叠语义与偏好键不变。
 */
@Composable
internal fun SpriteStage(
    count: Int?,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 文案表：语言由本层一次取好往下传（照 UpdateUi.copyFor 的口径）。
    val copy = PortraitShellCopy.of(UiPrefs.settingsLanguage(LocalContext.current))
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(PortraitTokens.Panel)
            .border(width = 1.dp, color = PortraitTokens.PanelLine)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
        ) {
            Text(
                text = if (count != null) copy.fill(copy.stageTitleFormat, count) else copy.stageTitleNoCount,
                color = PortraitTokens.DimText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = if (collapsed) copy.stageExpand else copy.stageCollapse,
                    color = PortraitTokens.Green,
                    fontSize = 11.sp,
                )
            }
        }
        AnimatedVisibility(visible = !collapsed) {
            Text(
                text = copy.stageHint,
                color = Color(0xFF6A6A6A),
                fontSize = 9.5.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
    }
}
