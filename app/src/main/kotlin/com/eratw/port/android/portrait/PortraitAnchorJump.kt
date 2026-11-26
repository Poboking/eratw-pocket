package com.eratw.port.android.portrait

/**
 * 竖屏抽屉「翻到正文 [Status]/[Palam] 区」的**精确锚点**（BOARD PORTRAIT 余项 ②b，
 * 设计稿 §4.1 M1 形态原来只会「上翻一屏」）。
 *
 * 数据源是 `ConsoleScreen.log` 的**已折行快照行**：每行带 `isLogicalLine`（逻辑行首）标记，
 * 折行出来的续行不算行首。锚点判定复用 [HudLineParser] 的行型函数（同一套规格、同一套
 * 标签集合），不另写一份文本规则 —— 这样「HUD 认得出的状态区」与「抽屉跳过去的区间」
 * 永远是同一处。
 *
 * 判定口径（自上而下扫，取**最后**一处命中）：
 *   1. 逻辑行首、且整行认成日期行或地点时间行的行 —— 状态区头（A1 的
 *     `第47日(五) 所持金:…`、A2~A4 的 `夏之月 17日 (六)9时00分 …`）；
 *   2. 上一步全无命中时退到数值行首（`体力: …` / `[体力] …` 一类），仍无 → null。
 *
 * 返回 null 表示「这一屏没有可锚的状态区」：调用侧必须退回旧行为（上翻一屏），
 * 不许拿 0 或末行冒充锚点（§0-5 口径：不编造）。
 */
object PortraitAnchorJump {

    /**
     * 在 [texts] / [logicalStart]（同长、逐行对应）里找状态区头的行下标。
     *
     * @param texts 每行快照文本（`DisplayRow.runs` 按打印序拼起来，不 trim）
     * @param logicalStart 该行是否为逻辑行首（折行续行 = false，不参与判定）
     * @return 行下标（相对同一个取行窗口）；没有候选 → null
     */
    fun anchorOffset(texts: List<String>, logicalStart: List<Boolean>): Int? {
        if (texts.size != logicalStart.size) return null
        // 先收窄到「逻辑行首且非空」的行，再分两级判定：状态区头优先，数值行首兜底。
        val heads = texts.indices.filter { i -> logicalStart[i] && texts[i].trim().isNotEmpty() }
        val statusHead = heads.lastOrNull { i -> isStatusHead(texts[i].trim()) }
        val barHead = heads.lastOrNull { i -> HudLineParser.parseBarLine(texts[i].trim()) != null }
        return statusHead ?: barHead
    }

    /** 状态区头：日期行或地点时间行（两条都走附录 A 的行型函数，不另立判据）。 */
    private fun isStatusHead(line: String): Boolean =
        HudLineParser.parseDateLine(line) != null || HudLineParser.parsePlaceLine(line) != null
}
