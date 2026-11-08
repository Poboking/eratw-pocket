package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [SessionHandoff] 的定向单测 —— 跨 Activity 交接闸（2026-10-31 玩家反馈）。
 *
 * 屏幕原话：
 * > 启动失败：上一局的内存还没释放（已用 422 MiB / 上限 512 MiB）。请结束本应用进程后
 * > 重开 —— 存档在盘上，不会丢。
 *
 * 玩家 1 的复现是「切到 QQ 回消息时正在换地图」；玩家 2 是「切换一直都会」。交接闸要
 * 保证的判据只有一条：**同进程里还有上一局的 VM 线程没退时，新的装载线程必须等它**，
 * 而不是直接进堆预检把 422 MiB 读成「上一局没让出堆」。
 *
 * 为什么是 JVM 单测：本类不碰 Android 运行期（纯登记槽 + 轮询），与
 * `ShellUiFailureActionsTest` 同一先例；真正的宿主线程收口由
 * `GameHost.start` / `loadAndRun` 的 finally 接线，`BackgroundPauseTest` 钉 VM 侧的
 * interrupt 响应。
 */
class SessionHandoffTest {

    @Test
    fun `只登记自己时没有别人要等`() {
        val self = Any()
        SessionHandoff.register(self)
        try {
            assertEquals(0, SessionHandoff.othersAlive(self))
            assertTrue(SessionHandoff.awaitOthersGone(self, timeoutMs = 10L))
        } finally {
            SessionHandoff.unregister(self)
        }
    }

    @Test
    fun `别人登记着时不能开始装载 注销后立刻放行`() {
        val oldHost = Any()
        val newHost = Any()
        SessionHandoff.register(oldHost)
        SessionHandoff.register(newHost)
        try {
            assertEquals("新宿主不该把上一局算成自己", 1, SessionHandoff.othersAlive(newHost))
            assertFalse(
                "上一局还在跑时必须等它让出堆",
                SessionHandoff.awaitOthersGone(newHost, timeoutMs = 0L),
            )

            SessionHandoff.unregister(oldHost)
            assertTrue(
                "上一局退出后装载线程应立刻放行，而不是空等到超时",
                SessionHandoff.awaitOthersGone(newHost, timeoutMs = 2_000L),
            )
            assertEquals(0, SessionHandoff.othersAlive(newHost))
        } finally {
            SessionHandoff.unregister(oldHost)
            SessionHandoff.unregister(newHost)
        }
    }

    @Test
    fun `等待期间上一局退出 在超时前返回 true`() {
        val oldHost = Any()
        val newHost = Any()
        SessionHandoff.register(oldHost)
        SessionHandoff.register(newHost)
        val released = CountDownLatch(1)
        val releaser = Thread {
            try {
                Thread.sleep(80L)
            } finally {
                SessionHandoff.unregister(oldHost)
                released.countDown()
            }
        }
        releaser.isDaemon = true
        try {
            releaser.start()
            val began = System.nanoTime()
            val gone = SessionHandoff.awaitOthersGone(newHost, timeoutMs = 3_000L)
            val elapsedMs = (System.nanoTime() - began) / 1_000_000L
            assertTrue("上一局在超时前退出，应返回 true", gone)
            assertTrue("提前返回：实际等了 ${elapsedMs}ms", elapsedMs < 3_000L)
            assertTrue("等待释放线程收口", released.await(2L, TimeUnit.SECONDS))
        } finally {
            releaser.interrupt()
            SessionHandoff.unregister(oldHost)
            SessionHandoff.unregister(newHost)
        }
    }

    @Test
    fun `等不到时如实返回 false 不阻塞到超时之后`() {
        val oldHost = Any()
        val newHost = Any()
        SessionHandoff.register(oldHost)
        SessionHandoff.register(newHost)
        try {
            val began = System.nanoTime()
            val gone = SessionHandoff.awaitOthersGone(newHost, timeoutMs = 60L, pollMs = 10L)
            val elapsedMs = (System.nanoTime() - began) / 1_000_000L
            assertFalse("上一局一直不退时必须如实返回 false（交给既有堆基线硬闸）", gone)
            assertTrue("应在上限附近返回，实际 ${elapsedMs}ms", elapsedMs < 2_000L)
        } finally {
            SessionHandoff.unregister(oldHost)
            SessionHandoff.unregister(newHost)
        }
    }

    @Test
    fun `身份比较 不因两个宿主形状相同而误认`() {
        val oldHost = Any()
        val newHost = Any()
        SessionHandoff.register(oldHost)
        try {
            assertTrue(
                "GameHost 没有值相等语义；两个 Object 不是同一个实例就必须算「别人」",
                SessionHandoff.othersAlive(newHost) >= 1,
            )
        } finally {
            SessionHandoff.unregister(oldHost)
        }
    }

    @Test
    fun `并发登记注销不抛且最终清空`() {
        val owners = (0 until 8).map { Any() }
        val start = CountDownLatch(1)
        val threads = owners.map { owner ->
            Thread {
                start.await(2L, TimeUnit.SECONDS)
                repeat(200) {
                    SessionHandoff.register(owner)
                    SessionHandoff.unregister(owner)
                }
            }.apply { isDaemon = true }
        }
        try {
            threads.forEach { it.start() }
            start.countDown()
            threads.forEach { it.join(3_000L) }
            threads.forEach { assertFalse("并发线程未退出", it.isAlive) }
            val probe = Any()
            assertEquals("所有注销都应落净", 0, SessionHandoff.othersAlive(probe))
        } finally {
            owners.forEach { SessionHandoff.unregister(it) }
        }
    }
}
