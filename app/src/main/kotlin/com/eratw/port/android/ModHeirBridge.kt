package com.eratw.port.android

import com.eratw.port.modapi.EntityId
import com.eratw.port.modfamily.FamilyApi
import com.eratw.port.modfamily.FamilyDeclarations

/**
 * 设置页「家督继任」的玩家面数据桥（task-160 / Lane HB；ADR-015 S3 手动继任的玩家入口）。
 *
 * ## 为什么是「请求队列 + 页边界 drain + 快照回写」
 *
 * 作者面 [FamilyApi] 只能在会话的 VM 线程上调：它读 `FamilyMod` 的 `context`，而该字段在
 * `onLoad` 之前是 lateinit（提前调用抛 UninitializedPropertyAccessException），它还读
 * `settings` / `storage` 这类只在本局内有效的视图。设置页是另一个 Activity、另一个线程，
 * 也不可能持有 `ModRuntimeSession`（禁单例、禁 Activity 直摸会话对象）。于是两侧只交换纯数据：
 *
 * 1. 设置页点选 / 清除 → [ModHeirRequests] 入队（按包去重，后到覆盖）；
 * 2. 游戏侧页边界（`GameHost.onTurnBoundaryReached`）drain → 调作者面 → 结果经
 *    [ModHeirFeed] 回写成 [ModHeirSnapshot]；
 * 3. 设置页下次渲染读快照；没有 family 载体（或本局快照为空）时整块不渲染。
 *
 * 队列与快照都不持 host / session / mod 实例引用，收口时两侧 `clear()`，不跨局残留。
 *
 * ## 口径边界
 *
 * 本文件只管「把玩家意图送到作者面、把作者面真值送回来」；继任语义（eldest / youngest /
 * manual 各自的结算时机、手动指定失败条件）全在 mod-family，这里不复制、不改写、不枚举规则。
 */

/** 一条待兑现的继任意图；[packageId] 目前只有 `FamilyDeclarations.PACKAGE_ID`。 */
internal sealed class ModHeirRequest {
    /** 目标包名（按包去重 / 过滤用；将来若第二家实现继任面不需要改队列形态）。 */
    abstract val packageId: String

    /** 手动指定继任者（[heirRaw] = [EntityId.raw]）。 */
    data class Choose(override val packageId: String, val heirRaw: Long) : ModHeirRequest()

    /** 清除手动指定（交回当前继承规则）。 */
    data class Clear(override val packageId: String) : ModHeirRequest()
}

/**
 * 设置页 → 游戏侧的请求队列（一次性投递，不跨会话）。
 *
 * 语义与 [ModReinstallRequests] / [ModSettingsNotifyChannel] 同源：同包后到覆盖（去重）、
 * 首投递序出队、drain 取走即清、收口 clear。非法包名 / 非法 EntityId 入队**静默拒绝并返
 * false**（入队失败不回流 UI，按钮下一帧按快照回到真值）。
 */
internal object ModHeirRequests {
    private val lock = Any()

    /** packageId 形状闸（与 `ModEnablement.PACKAGE_ID_PATTERN` 同口径的本地副本）。 */
    private val PACKAGE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** 首投递序 = 插入序；同包再入队只更新值、不改变位次。 */
    private val pending = LinkedHashMap<String, ModHeirRequest>()

    /** 入队「指定继任者」；[heirRaw] 必须是合法实体 id（0 = [EntityId.NULL]，不接受）。 */
    fun requestChoose(packageId: String, heirRaw: Long): Boolean {
        if (!PACKAGE_ID.matches(packageId) || heirRaw == EntityId.NULL.raw) return false
        synchronized(lock) { pending[packageId] = ModHeirRequest.Choose(packageId, heirRaw) }
        return true
    }

    /** 入队「清除指定」。 */
    fun requestClear(packageId: String): Boolean {
        if (!PACKAGE_ID.matches(packageId)) return false
        synchronized(lock) { pending[packageId] = ModHeirRequest.Clear(packageId) }
        return true
    }

    /** 取走全部请求并清空（取走即清：没被取走的请求不会在下一拍被重投）。 */
    fun drain(): List<ModHeirRequest> {
        synchronized(lock) {
            if (pending.isEmpty()) return emptyList()
            val out = pending.values.toList()
            pending.clear()
            return out
        }
    }

    /** 收口清场（会话结束 / 未兑现请求随会话丢弃）。 */
    fun clear() {
        synchronized(lock) { pending.clear() }
    }

    internal fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** 当前积压的该包请求（设置页用来在快照到来之前回显最后一次点击）。 */
    internal fun pendingFor(packageId: String): ModHeirRequest? =
        synchronized(lock) { pending[packageId] }
}

/** 快照里的一条候选继任者；[raw] = [EntityId.raw]，[displayName] = 既有 `title` 的只读投影
 * （空标题时为 null，UI 按约定只显示 `#raw`）。 */
internal data class ModHeirCandidate(val raw: Long, val displayName: String? = null)

/**
 * 会话 → 设置页的真值快照（纯数据，不持任何会话 / mod 引用）。
 *
 * - [mode]：本局当前继承规则原文（`manual` / `eldest` / `youngest`；标签映射在 UI 侧）；
 * - [candidates]：作者面当前认可的候选（已滤掉悬空 / RETIRED，按 raw 升序）；
 * - [pendingRaw]：本局已指定的手动继任者（null = 未指定）。
 */
internal data class ModHeirSnapshot(
    val packageId: String,
    val mode: String,
    val candidates: List<ModHeirCandidate>,
    val pendingRaw: Long?,
)

/** 最近一次页边界回写的快照槽；单引用读写，@Volatile 足够；不跨局，收口清空。 */
internal object ModHeirFeed {
    @Volatile
    private var current: ModHeirSnapshot? = null

    fun publish(snapshot: ModHeirSnapshot) {
        current = snapshot
    }

    fun read(): ModHeirSnapshot? = current

    fun clear() {
        current = null
    }
}

/**
 * 页边界翻译器：请求 → 作者面调用 → 快照回写（VM 线程调用，唯一兑现点）。
 *
 * [familyApi] == null（本局没装 / 停用 / 装载失败 / 已收口）或 [successionMode] == null ⇒
 * 清空快照并返 false：设置页据此整块不渲染，不出现「点了没反应的按钮」。逐条请求直接调作者面，
 * 作者面的拒绝（非 manual 规则、非法候选）不抛异常、只影响下一帧快照里的 `pendingRaw`。
 */
internal object ModHeirBridge {
    fun apply(
        requests: List<ModHeirRequest>,
        familyApi: FamilyApi?,
        successionMode: String?,
    ): Boolean {
        if (familyApi == null || successionMode == null) {
            ModHeirFeed.clear()
            return false
        }
        for (request in requests) {
            if (request.packageId != FamilyDeclarations.PACKAGE_ID) continue
            when (request) {
                is ModHeirRequest.Choose -> familyApi.chooseSuccessor(EntityId(request.heirRaw))
                is ModHeirRequest.Clear -> familyApi.clearSuccessor()
            }
        }
        // 候选与名字同一次页边界取好：设置页只吃快照，不在 Compose 里读会话数据。
        val candidates = familyApi.candidates()
        val names = familyApi.displayNames(candidates)
        ModHeirFeed.publish(
            ModHeirSnapshot(
                packageId = FamilyDeclarations.PACKAGE_ID,
                mode = successionMode,
                candidates = candidates.map { ModHeirCandidate(it.raw, names[it]) },
                pendingRaw = familyApi.pendingSuccessor()?.raw,
            ),
        )
        return true
    }
}
