package com.eratw.port.android

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VaultCore]（[SessionVault] 的槽内核）的认主/活判据语义（纯 JVM，假类型直测）。
 *
 * 生产宿主 [GameHost] 挂着 Android 视图起不来，槽内核因此抽成泛型类——语义在假类型上
 * 钉死，生产装配面（谁在哪个生命周期点调 install/takeover/discard）由
 * [SessionHandoverWireTest] 的源码哨兵钉。
 *
 * 这三个操作对应会话升进程级（2026-11-13）的三条命门：
 * 1. 交接即入槽（install）——槽是权威；
 * 2. 接回只接活的（takeover）——死会话就地收场，失败屏不接回；
 * 3. close 认主出槽（discard）——旧壳迟到的 close 不得撅空新壳刚接回的槽
 *    （2026-10-31 真机双壳交接窗口实形）。
 */
class VaultCoreTest {

    private class FakeHost(val id: Int) {
        var closed = false
    }

    @Test
    fun `install 即入槽 takeover 只接活的`() {
        val core = VaultCore<FakeHost>()
        assertNull("空槽 takeover 应为 null（走冷启装载）", core.takeover({ true }, { it.closed = true }))

        val host = FakeHost(1)
        core.install(host)
        assertSame("活会话应被原样取出", host, core.takeover({ true }, { it.closed = true }))
        assertNull("取出后槽应清空（接回实例是新主人）", core.peek())
    }

    @Test
    fun `死会话就地收场 返回 null`() {
        val core = VaultCore<FakeHost>()
        val dead = FakeHost(2)
        core.install(dead)

        assertNull("非活会话不接回（失败屏不接回，重试归重开）", core.takeover({ false }, { it.closed = true }))
        assertTrue("死会话必须被 kill 收场（无主会话不驻留）", dead.closed)
        assertNull("收场后槽清空", core.peek())
    }

    @Test
    fun `discard 认主 只清自己的槽`() {
        val core = VaultCore<FakeHost>()
        val current = FakeHost(3)
        core.install(current)

        val retired = FakeHost(4)
        core.discard(retired)
        assertSame("旧壳的 close 不得撅空新壳刚接回的槽（认主）", current, core.peek())

        core.discard(current)
        assertNull("槽里正是这只宿主时才清（close 的认主出槽）", core.peek())
    }

    @Test
    fun `install 覆盖旧值并返回旧值`() {
        val core = VaultCore<FakeHost>()
        val first = FakeHost(5)
        val second = FakeHost(6)
        core.install(first)
        assertSame("重开一局覆盖旧槽（旧宿主已由 close 清场，返回只作诊断）", first, core.install(second))
        assertSame(second, core.peek())
    }

    @Test
    fun `takeover 的 alive 判据决定生死归属`() {
        val core = VaultCore<FakeHost>()
        val host = FakeHost(7)
        // 生产判据 = !closed && VM 线程在跑（SessionVault.takeoverIfAlive）。这里用
        // vmAlive 一键代拟 VM 线程存活（closed 键已由上面死会话用例覆盖），验「判据为假
        // 就地收场、为真才接回」的归属关系。
        var vmAlive = false
        core.install(host)
        assertNull("VM 线程已死不接回", core.takeover({ vmAlive }, { it.closed = true }))
        assertTrue("死会话就地收场", host.closed)
        vmAlive = true
        core.install(host)
        assertSame("判据为真才接回", host, core.takeover({ vmAlive }, { it.closed = true }))
    }

    /**
     * 2026-11-17「切后台卡回主菜单 + 当日存档不见」的槽内核回归：takeover 清槽后，
     * 接回壳自己再被系统销毁时必须**重新 install**，否则会话无主，下一实例只能冷启。
     * （冻结/入槽的归属判据在 GameHost.isBoundToView，由 SessionHandoverWireTest 钉。）
     */
    @Test
    fun `接回后再次销毁 重新入槽恢复下一棒的接手能力`() {
        val core = VaultCore<FakeHost>()
        val host = FakeHost(8)
        core.install(host)
        assertSame("第一壳被接回", host, core.takeover({ true }, { it.closed = true }))
        assertNull("takeover 清槽——壳持有期间槽本就为空", core.peek())

        core.install(host) // 接回壳自己系统销毁时的重新入槽
        assertSame("无壳期间会话必须有主", host, core.peek())
        assertSame("下一实例仍能接回同一局，不回标题", host, core.takeover({ true }, { it.closed = true }))
        assertNull("二次接回同样清槽", core.peek())
    }
}
