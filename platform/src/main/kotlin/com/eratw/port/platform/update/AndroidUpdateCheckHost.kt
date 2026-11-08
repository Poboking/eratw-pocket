package com.eratw.port.platform.update

import com.eratw.port.core.update.UpdateCheckDecision
import com.eratw.port.core.update.UpdateCheckHost

/**
 * 问玩家那一步的宿主缝（`UPDATECHECK` 专用）。
 *
 * 形状与 `platform/save/SaveMigrationPromptUi` 的区别只有一个，而且是语义要求的区别：
 * **必须能表达「没问到」**。原版 `MessageBox.Show`（`FunctionIdentifier.cs:2802`）只有两种
 * 结局（是 / 否，关框算否），而 `Show` 本身抛异常时走的是那条 catch ⇒ RESULT=3（`:2827-2832`）；
 * 移植侧多一种「超时」（VM 线程不能无限等一个玩家），它与「框弹不出来」同属原版的异常支，
 * 所以本缝用三态交出，而不是让宿主把「没问到」谎报成「否」（那会把 3 变成 1，
 * 玩家看到的结果是「我压根没被问过，脚本却以为我拒绝了」）。
 *
 * 阻塞口不新造：实现方复用既有的 `BlockingSaveMigrationPrompt`（一把 latch + UI 线程作答），
 * 超时由实现方在 UI 投递侧自己收口（见 `app/.../GameHost.kt` 的 `askUpdateCheckOnUi`）。
 */
fun interface UpdateAsk {
    /** 返回 true=是、false=否或关框、null=没问到（超时 / Activity 已销毁 / 框弹不出来）。 */
    fun ask(title: String, message: String): Boolean?
}

/**
 * `UPDATECHECK` 的四件宿主能力装配成一个 [UpdateCheckHost]（core 侧的缝）。
 *
 * 为什么在 platform 而不是 core：core 层纯 JVM（AGENTS.md 铁律 4），这四件事没有一件是
 * core 能自己做的；为什么在 platform 而不是 app：取流与网络判定是**不碰 Android API** 的
 * `java.net`（见 [UpdateCheckTransport]），放这里就能被纯 JVM 装置复用，
 * 而 app 侧只交出「配置读数 / 弹框 / Intent 打开链接」三条设备相关 lambda。
 *
 * 线程：四个方法都在 **VM 线程**被同步调用（`core/save/FileSystemOps.updateCheck`）。
 */
class AndroidUpdateCheckHost(
    /** 生效配置的 `ForbidUpdateCheck` 读数（ConfigKey 163）；逐次现读，不许烘进构造期快照。 */
    private val forbidProvider: () -> Boolean,
    private val transport: UpdateCheckTransport = UpdateCheckTransport(),
    private val ask: UpdateAsk,
    private val linkOpener: (String) -> Unit,
) : UpdateCheckHost {

    /**
     * 配置禁令（原版 `:2767`）。
     *
     * [forbidProvider] 抛异常时按 **true**（= 禁止，RESULT=4）处理而不是 false：
     * 这条判定的两个方向代价不对称 —— 判成禁止最多让玩家这一次检查失效（原版出厂配置
     * 本来就是「允许」，`ConfigData.cs:115` 的 `t: false`，禁掉只是回到「不发请求」），
     * 判成允许则会在配置读不通的情况下真的发一次请求，而调用点在 VM 线程上。
     */
    override fun forbidUpdateCheck(): Boolean = try {
        forbidProvider()
    } catch (_: Throwable) {
        true
    }

    override fun isNetworkAvailable(): Boolean = transport.isNetworkAvailable()

    /** 取前两行：[UpdateCheckTransport.fetchVersionInfo] 已经把「连不上 / 非 2xx / 超时 / 读流异常」压成 null。 */
    override fun fetchVersionInfo(url: String): UpdateCheckDecision.RemoteInfo? = transport.fetchVersionInfo(url)

    /** 玩家表态：null = 没问到，由决策表落 RESULT=3（见 [UpdateAsk]）。 */
    override fun askOpenLink(title: String, message: String): Boolean? = ask.ask(title, message)

    /**
     * 打开链接（原版 `Process.Start` + `UseShellExecute = true`，`:2805-2809`）。
     *
     * 这里**不吞**异常：原版在 `Process.Start` 抛出时由 catch 把 RESULT 从 2 改写成 3
     * （`:2804` 先写、`:2829` 覆盖），而那条改写要由调用方做才成立 —— 吞掉异常就等于
     * 谎报「已经打开了」。
     */
    override fun openDownloadLink(url: String): Unit = linkOpener(url)
}
