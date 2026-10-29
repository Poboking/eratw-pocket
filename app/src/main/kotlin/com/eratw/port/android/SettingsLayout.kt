package com.eratw.port.android

/**
 * 设置页的自适应布局档位。
 *
 * 只回答三件事：卡片排几列、内容最宽多少、保存按钮放顶栏还是底栏。具体 Composable
 * 不按方向开关分支 —— 横竖屏只是「可用宽高」的两种常见取值，平板竖屏同样会进两列档。
 *
 * @property columns 瀑布网格列数（1 / 2 / 3）
 * @property contentMaxWidthDp 内容最大宽度；超宽屏居中使用，避免卡片拉成一条长条
 * @property actionsInTopBar true = 保存 / 保存并重启收进顶栏（矮屏横屏，省出列表高度）
 */
internal data class SettingsLayoutPolicy(
    val columns: Int,
    val contentMaxWidthDp: Int,
    val actionsInTopBar: Boolean,
)

/**
 * 按当前可用宽高选布局。
 *
 * 断点取 Android 的宽度分级：600dp 以下单列（手机竖屏），600–1040dp 两列
 * （手机横屏、折叠屏、平板竖屏），1040dp 以上三列。高度 < 480dp 视为矮屏
 * （典型手机横屏 360dp 高），底部整条保存栏会吃掉近三分之一列表高度，改收进顶栏。
 */
internal fun settingsLayoutFor(widthDp: Float, heightDp: Float): SettingsLayoutPolicy {
    val columns = when {
        widthDp < 600f -> 1
        widthDp < 1040f -> 2
        else -> 3
    }
    val maxWidth = when (columns) {
        1 -> 680
        2 -> 1180
        else -> 1500
    }
    return SettingsLayoutPolicy(
        columns = columns,
        contentMaxWidthDp = maxWidth,
        actionsInTopBar = heightDp < 480f,
    )
}
