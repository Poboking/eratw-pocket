package com.eratw.port.android.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eratw.port.android.ui.EratwInk

/**
 * 分区带（设置页各档内部的**二级分组头**）：分区色竖条 + 标题 + 另起一行的说明句 + 同色细分隔线。
 *
 * 2026-11-26 从 `SettingsActivity` 的 private `GroupHeader` 提级：它是「同类功能进同一档」的
 * 视觉锚点，市场页、以后竖屏抽屉的分组都要用同一副，不能再各写一份。
 *
 * 说明句另起一行是这次的主要修正：旧版把说明挤在标题右侧 11sp 单行，窄屏上必被省略号截掉，
 * 等于没有说明；而分组头的职责恰恰是「一句话交代这一组的写入规则」，读不全就失去意义。
 */
@Composable
fun ShellGroupHeader(
    title: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = ShellSpace.GroupGap, bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(ShellSpace.AccentBar)
                    .height(18.dp)
                    .clip(ShellShape.Bar)
                    .background(accent),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = accent,
                style = ShellType.Title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 组级动作（「清扫」「全部展开」这类）挂在标题行右端：动作属于这一组，不该另起一行
            // 变成页面级按钮；没有动作时这一格不占位。
            if (trailing != null) trailing()
        }
        Text(
            text = subtitle,
            color = EratwInk.textMuted(),
            style = ShellType.Label,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = ShellSpace.CardStart, top = 3.dp, end = 4.dp),
        )
        Spacer(modifier = Modifier.height(6.dp))
        HorizontalDivider(color = accent.copy(alpha = GROUP_RULE_ALPHA))
    }
}

/** 分组头分隔线的实体不透明度（比卡片描边更弱，只交代「这一组到哪结束」）。 */
private const val GROUP_RULE_ALPHA = 0.3f
