package com.eratw.port.android

import com.eratw.port.core.config.ConfigStore
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.ref.WeakReference

/**
 * fix-staticpin 阶段一判据 —— `HotConfigChannel` 这条进程级静态边的生命周期钉板。
 *
 * 上一路（obs-staticpin）的会话金丝雀证明 core 侧 close 后整局可回收、真机 184 MiB 的
 * 持有面在 app/ART 侧；本文件钉的是它点名交给组长的 app 侧头号嫌疑：
 * `MainActivity` 装载完成点装的 provider 闭包（`install(this) { host?.liveConfigStore() }`，
 * `MainActivity.kt` 的 host 赋值分支里）是否**捕获整只壳**，以及「接线 / 认主卸载」是否成对。
 *
 * 三条事实各自钉一条：
 * 1. **捕获形态是真的**：与真接线点逐字同形的闭包（读宿主实例字段 `host`）编译后必带
 *    一个指向宿主的捕获字段，反射可见。不是「装的是活引用所以不抓壳」——provider 只是
 *    把「读哪一局的表」推迟到 push 时刻，捕获槽照抓。
 * 2. **金丝雀自证**（照 `CanaryPinboard` 的标准）：接线期间把宿主唯一的强引用掐掉，
 *    WeakReference 顶过多轮 GC 仍可达 ⇒ 静态槽真的钉得住（判式不是摆设）；
 *    认主卸载后同一判式立刻转为可达不到 ⇒ 卸载点生效。故意把 `uninstall` 改回
 *    「无条件清空」或「空操作」，本用例与第 3 条会分别变红。
 * 3. **双壳交接**（真机实测 `Activities: 2` 的形状）：旧壳的 onDestroy 跑在新壳接线之后
 *    时，旧壳**无权**把新壳刚装好的通道撬空——上一版的 `install(null)` 是无条件清空，
 *    这正是「摘除点与接线点对不上账」的缺口（内存上不多钉一个字节，功能上热更键静默
 *    退化成只落盘）。
 *
 * 为什么能在 JVM 里钉：本用例只碰 [HotConfigChannel] 这一个纯 Kotlin object 与测试自带的
 * 假壳类，不实例化 `MainActivity`（AGP 桩类不让碰），与 `SessionHandoffTest` 同一先例。
 * 接线点本身的逐字形状靠第 1 条的「同形闭包」代理 + 源码对照（`MainActivity.kt` 的
 * install 调用行），真机换代行为留给 `TODO(verify)`（本机 adb 为空）。
 */
class HotConfigChannelPinningTest {

    /** 假宿主：只做可达性判式的中间对象，[liveConfigStore] 语义照抄 `GameHost.liveConfigStore()`。 */
    private class FakeHost {
        fun liveConfigStore(): ConfigStore? = null
    }

    /**
     * 假壳：[wire] 的方法体与 MainActivity 装载完成点的真接线**逐字同形**
     * （install 传 this，provider 读实例字段 `host`）；[providerShape] 把同一条闭包单独
     * 交出来供反射验捕获。
     */
    private class FakeShell {
        var host: FakeHost? = null

        fun wire() {
            HotConfigChannel.install(this) { host?.liveConfigStore() }
        }

        fun providerShape(): () -> ConfigStore? = { host?.liveConfigStore() }
    }

    /** 多轮 collect + finalization，最多 [budgetMs] 毫秒；返回 true = WeakReference 已清空。 */
    private fun collectedWithin(ref: WeakReference<*>, budgetMs: Long = 4_000L): Boolean {
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (ref.get() == null) return true
            System.gc()
            Runtime.getRuntime().runFinalization()
            System.gc()
            try {
                Thread.sleep(25L)
            } catch (t: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return ref.get() == null
    }

    /** 另一条栈帧里接线并掐掉本地强引用，只交回弱引用。 */
    private fun wireDetachedShell(): WeakReference<FakeShell> {
        val shell = FakeShell()
        shell.host = FakeHost()
        shell.wire()
        return WeakReference(shell)
    }

    /**
     * 在**另一条栈帧**里取当前接线身份并卸载。
     *
     * 不能在被测方法的末尾直接 `wiredOwnerSnapshot()`：owner 快照指向宿主本身，而测试帧
     * 还在栈上 —— JDK 17 HotSpot（C2 不覆写死亡槽、解释器按 LVT 保活）会让宿主在 GC 时
     * 仍从本帧可达，判式假阴（fix-staticpin 阶段一实测踩过）。局部变量随帧弹出才是真死。
     */
    private fun uninstallCurrentOwner(): Boolean {
        val owner = HotConfigChannel.wiredOwnerSnapshot() ?: return false
        HotConfigChannel.uninstall(owner)
        return true
    }

    @Test
    fun `与真接线点同形的闭包确实捕获宿主（反射可见捕获字段）`() {
        val shell = FakeShell()
        try {
            val provider = shell.providerShape()
            // Kotlin 对读实例字段的闭包（无论匿名类还是 indy 代理）都会在代理类上留一个
            // 指向宿主实例的捕获字段；没有这种字段 = 本用例的判式没钉住捕获形态。
            val captureField = provider.javaClass.declaredFields.firstOrNull { field ->
                field.type == FakeShell::class.java
            }
            assertTrue(
                "闭包 `install(this) { host?.liveConfigStore() }` 应捕获宿主实例（捕获槽缺失=判式失效）",
                captureField != null,
            )
        } finally {
            HotConfigChannel.uninstall(shell)
        }
    }

    @Test
    fun `金丝雀自证：接线期间钉得住宿主，认主摘除后立刻回收`() {
        val ref = wireDetachedShell()
        try {
            // (a) 静态槽此刻指向活壳 —— 这正是 MainActivity 装载后的正常形态：
            //     多轮 GC 后仍可达，证明 WeakReference 判式对这个槽**看得见**（不是摆设）。
            System.gc()
            Runtime.getRuntime().runFinalization()
            System.gc()
            assertTrue(
                "接线期间宿主应被进程级静态槽钉住（回收了=扫描器失明，后续读数全部作废）",
                ref.get() != null,
            )
        } finally {
            // (b) 认主卸载：槽里剩的就是唯一的钉，摘掉它宿主必须能回收。
            //     卸载整体收进另一条栈帧（见 [uninstallCurrentOwner] 的 KDoc），
            //     本帧不得留任何指向宿主的局部量。
            assertTrue("卸载前槽里应有接线身份", uninstallCurrentOwner())
            assertTrue(
                "认主摘除后宿主必须可回收（uninstall 失效=已销毁的壳被静态边永久钉住）",
                collectedWithin(ref),
            )
        }
    }

    @Test
    fun `双壳交接：旧壳的摘除不得撬掉新壳刚装的接线`() {
        val oldShell = FakeShell()
        val newShell = FakeShell()
        try {
            // 新壳装载完成点先接线（`:1489` 形状），旧壳的 onDestroy 后到（`:1079` 形状）。
            oldShell.wire()
            newShell.wire()
            HotConfigChannel.uninstall(oldShell)
            assertSame(
                "旧壳无权把新壳的通道撬空（上一版无条件清空正是这个缺口）",
                newShell,
                HotConfigChannel.wiredOwnerSnapshot(),
            )
            // 新壳自己收尾时才真正卸载，槽留干净。
            HotConfigChannel.uninstall(newShell)
            assertNull(HotConfigChannel.wiredOwnerSnapshot())
        } finally {
            // 断言失败也不把假壳留在进程级槽里，污染后面的用例。
            HotConfigChannel.wiredOwnerSnapshot()?.let { HotConfigChannel.uninstall(it) }
        }
    }

    @Test
    fun `卸载幂等且重接线以最后一次为准`() {
        val first = FakeShell()
        val second = FakeShell()
        try {
            first.wire()
            HotConfigChannel.uninstall(first)
            assertNull("自己的摘除要生效", HotConfigChannel.wiredOwnerSnapshot())
            // 空槽上再摘别的身份：no-op，不得把状态弄脏。
            HotConfigChannel.uninstall(second)
            assertNull(HotConfigChannel.wiredOwnerSnapshot())
            first.wire()
            second.wire()
            assertSame("重复接线以最后一次为准", second, HotConfigChannel.wiredOwnerSnapshot())
        } finally {
            HotConfigChannel.wiredOwnerSnapshot()?.let { HotConfigChannel.uninstall(it) }
        }
    }
}
