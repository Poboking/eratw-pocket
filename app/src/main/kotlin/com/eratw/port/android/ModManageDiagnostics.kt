package com.eratw.port.android

import java.util.concurrent.atomic.AtomicReference

/**
 * 「一次运行里上过战场的 Mod 装载结果」进程内内存面，供设置页「已安装的 Mod」卡片读。
 *
 * ## 为什么需要它
 *
 * 装载失败的**唯一真源**是运行时装配链打在 `logSink` 上的两行（装配期见
 * `ModRuntimeAssembly`）：
 *
 * - 成功：`mod.install pkg=<packageId> active=true`
 * - 失败：`mod.install pkg=<packageId> active=false failure=<异常简单类名>: <消息>`
 *
 * 失败态不落盘：它是「这次会话怎么样」的读数，不是玩家配置。**会话边界归零**（见下节），
 * 由新一次装载重新上报。
 *
 * ## 会话口径（Lane DXR）
 *
 * 失败行 = **本会话事实**，不跨会话继承：
 *
 * - 每个新会话装配开始时归零（[reset] 的生产唯一调用点是 `GameHost.assembleSession` 顶部，
 *   早于链装配与 `attach`）。所以「玩家按提示修好 / 禁用 / 重导包后再开局」时，上一次会话
 *   留下的失败行不会继续挂在新会话的设置页失败小节里；
 * - 同进程内从后台接回的是**同一个**会话（`GameHost.reattachView` 不新建会话、不经过
 *   `assembleSession`），刻意**不**归零：运行中会话已上报的失败行必须留着，否则接回一次
 *   设置页就白板；
 * - 归零只清「进程内读数」，不参与装载裁决、不改任何 `logSink` 行为。需要跨会话留痕的场合
 *   （崩溃取证、给作者报 bug）走 `core.save.SaveDiagnostics` / 错误报告（`ErrorReportCollector`）
 *   通道，本对象既不落盘也不承担历史账本职责。
 *
 * ## 判定规则
 *
 * 1. `active=false` ⇒ 记下该 packageId 的失败摘要（同类名+消息，截断）；
 * 2. `active=true` ⇒ 清掉该包的旧失败（先失败、玩家修好或重导入后成功，界面不该再报旧账）；
 * 3. 同包新的失败覆盖旧的；只留最近 [MAX_TRACKED_PACKAGES] 个包，单条原因截到
 *    [MAX_REASON_CHARS] 字符，长期运行内存有界；
 * 4. 非装载行 / 形状不对的行 / 非法 packageId ⇒ 静默忽略。
 *
 * ## 归因不可靠的整链拒载：`mod.reject`（Lane DS）
 *
 * - `mod.install pkg=<packageId> active=false failure=<异常简单类名>: <消息>` = **已归因到
 *   具体包名**，本对象收入摘要、设置页失败小节渲染（canonical 形状，唯一被解析的形态）。
 * - `mod.reject file=<磁盘文件名> reason=<原因> exception=<实际异常类型>` = **归因不可靠**
 *   （如 `.erbm` 容器坏档且文件名不合 packageId 形状），**刻意**不进设置页失败小节：前缀
 *   不是 `mod.install pkg=`，按规则 4 静默忽略，只留启动失败屏与 logcat 取证面。理由是
 *   「伪造一个看似合法的包名」比「设置页少一行记录」更坏。
 *
 * ## 线程模型
 *
 * 写方是 VM 线程上的 `logSink` 回调，读方是 Compose 主线程。内部状态是「每次整体
 * publish 的不可变快照」（[AtomicReference] + CAS），读侧无锁、只读到完整快照。
 * [record] 跑在装载路径旁路，**任何情况下都不向调用方抛异常、不做同步 IO**。
 */
object ModManageDiagnostics {
    /** 装载上报行的公共前缀。 */
    private const val INSTALL_PREFIX: String = "mod.install pkg="

    /** `active=` 段之后的失败载荷标记。 */
    private const val FAILURE_MARK: String = " failure="

    /** 失败载荷的固定开头 `active=false`。 */
    private const val INACTIVE_MARK: String = "active=false"

    /** 成功载荷（整段）。 */
    private const val ACTIVE_LINE: String = "active=true"

    /** 保留的失败包上限（超出时丢最早记录的那个包）。 */
    internal const val MAX_TRACKED_PACKAGES: Int = 32

    /** 单条失败摘要的字符上限（异常消息可能很长，界面也读不完）。 */
    internal const val MAX_REASON_CHARS: Int = 160

    /** 原因缺失时的占位（失败但没带 failure 段）。 */
    internal const val UNKNOWN_REASON: String = "?"

    /** packageId 形状：与 `ModEnablement` 的拼路径校验同口径（防脏键进表）。 */
    private val PACKAGE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    private val failures: AtomicReference<Map<String, String>> = AtomicReference(emptyMap())

    /**
     * 一行 `logSink` 输出 → 失败态。VM 线程调用，**绝不抛**。
     *
     * 只认 `mod.install pkg=` 开头的行；其它行（含 `mod.rt2` / `mod.diag`）原样忽略，
     * 保证宿主把整个 `logSink` 转进来时不会误记。
     */
    fun record(line: String) {
        // 装载路径旁路：解析失败一律吞掉，绝不能让诊断面把装配带崩。
        runCatching { applyLine(line) }
    }

    /** 当前失败快照：packageId → 失败摘要，按「最早记录在前」插入序。 */
    fun snapshot(): Map<String, String> = failures.get()

    /** 主动忘掉一个包的失败（卸载后旧读数不该继续挂在已不存在的包上）。 */
    fun forget(packageId: String) {
        runCatching { drop(packageId) }
    }

    /**
     * 会话边界归零：清空失败表，让后续 [record] 只反映本会话事实。
     *
     * 生产唯一调用点 = `GameHost.assembleSession` 顶部（新会话装配开始、早于链装配与 attach，
     * 会话口径见类 KDoc 的「会话口径」一节）；接回同一会话的路径刻意不调它。
     * 测试夹具也直接用它清场（既有用例全这么写）。
     */
    internal fun reset() {
        failures.set(emptyMap())
    }

    private fun applyLine(line: String) {
        if (!line.startsWith(INSTALL_PREFIX)) return
        val body = line.substring(INSTALL_PREFIX.length)
        val separator = body.indexOf(' ')
        if (separator <= 0 || !PACKAGE_ID.matches(body.substring(0, separator))) return
        val packageId = body.substring(0, separator)
        val tail = body.substring(separator + 1)
        when {
            tail == ACTIVE_LINE -> drop(packageId)
            tail == INACTIVE_MARK -> put(packageId, UNKNOWN_REASON)
            tail.startsWith(INACTIVE_MARK + FAILURE_MARK) -> {
                val reason = tail.substring(INACTIVE_MARK.length + FAILURE_MARK.length)
                put(packageId, summarize(reason))
            }
            // 形状不认识（未来加了新字段/新状态）：不猜，静默忽略。
            else -> Unit
        }
    }

    private fun summarize(raw: String): String = raw
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .take(MAX_REASON_CHARS)
        .ifEmpty { UNKNOWN_REASON }

    private fun put(packageId: String, reason: String) {
        publish { current ->
            if (current[packageId] == reason) return@publish current
            val next = LinkedHashMap(current)
            if (packageId !in next && next.size >= MAX_TRACKED_PACKAGES) {
                // 有界：丢最早记录的那个包（插入序头部），保持「最近 N 个」。
                next.keys.firstOrNull()?.let { next.remove(it) }
            }
            next[packageId] = reason
            next
        }
    }

    private fun drop(packageId: String) {
        publish { current ->
            if (packageId !in current) return@publish current
            LinkedHashMap(current).apply { remove(packageId) }
        }
    }

    /**
     * CAS publish：用同一个不可变快照做比较，写方并发时后到者重算，读侧永远见到完整表。
     * 内容未变（快照相等）时不发布，避免无谓的对象churn。
     */
    private inline fun publish(transform: (Map<String, String>) -> Map<String, String>) {
        while (true) {
            val current = failures.get()
            val next = transform(current)
            if (next == current) return
            if (failures.compareAndSet(current, java.util.Collections.unmodifiableMap(next))) return
        }
    }
}

/**
 * mod 最近日志回看的读数口（MODDX4-G8：进程级活引用，形态照 `ModRuntimeOverviewEntry`）。
 *
 * ## 为什么需要这条通道
 *
 * 活 `GameHost` 只挂在 `MainActivity` 的实例字段上，设置页是另一个 Activity；每包日志环
 * 挂在会话内各包的 `ModContextImpl.logBuffer` 上（core 侧唯一公共出口 =
 * `ModRuntimeHost.recentLogs(packageId)`），设置页拿不到会话引用。本件供壳在宿主就绪处装一个
 * 「按 packageId 现读宿主聚合缝」的 provider，设置页「已安装的 Mod」卡展开某包时现取现用；
 * 不装、会话已收口（host 引用置空）或读数失败时 read 为空表 ⇒ 界面渲染诚实空态文案，
 * 不造假数据（fail-closed）。
 *
 * ## 线程模型
 *
 * 装/摘在 UI 线程（MainActivity 生命周期两端，照 `ModRuntimeOverviewEntry` 同一认主口径），
 * 读在 UI 线程（设置页展开组合时）⇒ `@Volatile`。认主（双壳交接窗口里旧壳的 onDestroy
 * 不得撅空新壳刚装好的接线）与 `ModUiSettingsEntry` / `ModRuntimeOverviewEntry` 同源。
 * 底层读数本身线程安全：core 环带实例锁、返回只读副本（G2 口径），跨线程持有快照安全。
 */
internal object ModRuntimeLogViewerEntry {

    /** 当前接线：packageId → 该包最近日志快照；null = 未接线（或已卸载）。 */
    @Volatile
    private var liveProvider: ((String) -> List<String>?)? = null

    /** 当前接线的宿主身份（与 [liveProvider] 同生同灭）。 */
    @Volatile
    private var wiredOwner: Any? = null

    /** 壳接线：装上 provider 并以 [owner] 为身份；后装者覆盖旧接线（幂等）。 */
    fun install(owner: Any, provider: ((String) -> List<String>?)?) {
        wiredOwner = owner
        liveProvider = provider
    }

    /** 认主卸载：只有归属正是 [owner] 才摘（旧壳无权撅新壳的接线）。 */
    fun uninstall(owner: Any) {
        if (wiredOwner === owner) {
            wiredOwner = null
            liveProvider = null
        }
    }

    /**
     * 现取现用：未接线 / provider 返回 null（会话已收口）⇒ 空表——调用方渲染诚实空态，
     * 不区分「没接线」与「没日志」（对玩家是同一件事：当前没有可回看的日志）。
     */
    fun read(packageId: String): List<String> = liveProvider?.invoke(packageId).orEmpty()

    /** 当前接线身份；null = 未接线（装/摘是否成对的判据）。 */
    internal fun wiredOwnerSnapshot(): Any? = wiredOwner
}
