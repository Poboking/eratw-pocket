package com.eratw.port.android

/**
 * 「Mod 装载优先级」卡片的纯逻辑：已装列表 × 玩家偏好串 → 可勾选、可上下移的顺序表。
 *
 * 真源仍是 [UiPrefs.setPreferredPackageIds] 的逗号分隔串（解析口径见
 * [UiPrefs.parsePreferredPackageIds]：去空、去重、保序）。本对象只做不需要 Android 的三件事，
 * 便于纯 JVM 单测：把串归一成「仍在已装列表里的勾选序」、派生整卡展示序、上下移一格。
 *
 * ## 语义口径
 *
 * 1. **勾选的包**按偏好串里的顺序排前——这正是链接期实际读取的顺序；
 * 2. **没勾的包**按扫描序（已启用优先、再按标题）补在后面——它们落回缺省相对序；
 * 3. **偏好串里已不在扫描结果里的残留 ID**（旧串死数据）不再静默吞掉：[leftoverIds] 把它们
 *    原样取出来，卡片按「未知包」行展示并给逐行「移除」——玩家不删就一直保留，其它编辑
 *    （勾选 / 上下移）写回时照原样带回串里，绝不因为动了别的行就悄悄丢数据（ModDX-4
 *    加载顺序 UI 波判据 1：防旧串死数据既看不见又删不掉）；
 * 4. 空勾选 = 空串 = 缺省序（按 packageId 规范序），与偏好 API 的「留空恢复缺省」同义。
 *
 * 未知包残留的写回位置裁定：全部排到串首、相对序保留。链接期 rank 按 ID 查表、只在已知包
 * 之间比相对序（ModuleLinkOrder.preferredRanks），未知 ID 占不占位、占哪个位都不影响已知包
 * 的相对次序，所以「放串首」是零语义代价的确定性选择。
 *
 * 卡片保留「手动编辑」兜底输入框：勾选面只能排**已装**列表，玩家想给还没装的包预留
 * 优先级（或 manifest 读不出来、扫不进列表）时仍需直接编辑原始串。
 */
object ModManageOrder {

    /** 勾选序 → 偏好原始串；空表写空串（缺省序）。 */
    fun toRaw(chosen: List<String>): String = chosen.joinToString(",")

    /**
     * 带未知包残留的写回：未知 ID 排串首（相对序保留，位置无语义，见类 KDoc 裁定），
     * 勾选序紧随其后；两者皆空写空串（缺省序）。
     *
     * 这是卡片勾选 / 上下移写回的唯一入口：玩家没点「移除」的未知包行必须原样带回，
     * 不能因为动了别的行就丢数据。
     */
    fun toRaw(unknownKept: List<String>, chosen: List<String>): String =
        (unknownKept + chosen).joinToString(",")

    /**
     * 偏好串里**不在** [installed] 扫描结果里的残留 ID（旧串死数据），按串里原序去重取出。
     *
     * 与 [seedOrder] 在集合意义下互补：seedOrder 取「在装的勾选序」，本函数取「不在装的
     * 残留」，两者拼起来覆盖归一化串的全部 ID、互不重复（顺序不保证等于原串——残留统一
     * 排前面，位置无语义见类 KDoc 裁定）。卡片据此渲染「未知包」行。
     */
    fun leftoverIds(preferredRaw: String, installed: List<String>): List<String> {
        val available = installed.toSet()
        return UiPrefs.parsePreferredPackageIds(preferredRaw).filter { it !in available }
    }

    /**
     * 偏好原始串 → 勾选序（只留仍在 [installed] 里的包，顺序照串里来、去重）。
     *
     * 不在装的旧 ID 不进勾选序——它们经 [leftoverIds] 以「未知包」行展示（ModDX-4）；
     * 写回只发生在玩家真的操作卡片时，且未移除的残留原样带回（见 [toRaw] 双参版）。
     */
    fun seedOrder(preferredRaw: String, installed: List<String>): List<String> {
        val available = installed.toSet()
        return UiPrefs.parsePreferredPackageIds(preferredRaw).filter { it in available }.distinct()
    }

    /**
     * 整卡展示序 = 勾选序在前（偏好序），其余已装按扫描序补在后面（缺省相对序）。
     *
     * 勾选序里万一带了不在 [installed] 的旧 ID 也一并跳过，保证卡里只列真实存在的包。
     */
    fun displayOrder(chosen: List<String>, installed: List<InstalledMod>): List<String> {
        val installedIds = installed.map { it.packageId }
        val available = installedIds.toSet()
        val ordered = chosen.filter { it in available }.toMutableList()
        for (packageId in installedIds) {
            if (packageId !in ordered) ordered += packageId
        }
        return ordered
    }

    /**
     * 在 [ids] 里把 [packageId] 上移（[delta] = -1）或下移（[delta] = +1）一格。
     *
     * 越界或不在表里原样返回（不抛：按钮禁用与这里同口径，列表短时连点也不会崩）。
     */
    fun move(ids: List<String>, packageId: String, delta: Int): List<String> {
        val index = ids.indexOf(packageId)
        val target = index + delta
        if (index < 0 || target !in ids.indices) return ids
        val next = ids.toMutableList()
        next.removeAt(index)
        next.add(target, packageId)
        return next
    }
}
