package com.eratw.port.android

/**
 * 三张长名单功能卡的预览上限（2026-11-12）。
 *
 * 「右栏过滤 / 常用置顶 / 快捷宏」的条目会随玩家使用持续增长（历史候选上限 32 条、
 * 隐藏名单无硬上限），如果一张卡把每条都竖排出来，整张卡能拉到上千 dp，后面其它
 * 功能卡要滑很久才够得着。默认折叠成这里给的预览条数，玩家点「展开全部」才铺开。
 */
internal object SettingsListPreview {
    /** 通用芯片流（右栏过滤的隐藏名单）。 */
    const val CHIP: Int = 6

    /** 常用置顶的已置顶芯片。 */
    const val PINNED: Int = 4

    /** 常用置顶的历史候选芯片。 */
    const val HISTORY: Int = 6

    /** 快捷宏条目（每条带编辑/删除两个动作，比芯片高，预览更短）。 */
    const val MACRO: Int = 3
}

/**
 * 折叠预览的唯一截断口径：展开 = 全量，收起 = 前 [limit] 条（至少 1 条）。
 *
 * 纯函数，不碰 Compose，供 `SettingsListPolicyTest` 钉住「32 条候选不再撑爆卡片」。
 * 返回的是原列表前缀（保持调用侧下标语义），不会复制/重排。
 */
internal fun <T> previewOf(items: List<T>, expanded: Boolean, limit: Int): List<T> {
    if (expanded || items.size <= limit) return items
    return items.take(limit.coerceAtLeast(1))
}
