package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mod 生命周期通知（`onNewGame` / `onSaveLoaded`）的**装配哨兵**。
 *
 * 这层只能做源码哨兵：`GameHost` 碰 Android View，JVM 起不来。行为面由 core 侧
 * `BeginFlowTest`（新局在 `EVENTFIRST` 之后恰好一次）、`LoadDataHandoffTest`
 * （读档在 `endEventLoad` 恰好一次）与 `ModRuntimeHostTest`（只达已启用 mod、异常隔离）
 * 钉死；这里钉的是「**这条链在 app 侧真的接上了**」：
 * 1. `GameSession` 的 `beginHost` 两个后端都被赋值到 mod 运行时的转发方法；
 * 2. 赋值发生在 `ModRuntimeAssembly.attach` **之后**（否则 `modRuntimeSession` 还是旧会话/null）；
 * 3. `ModRuntimeSession` 确实有这两个转发方法（不是只接了一半）。
 */
class ModLifecycleWireSentinelTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `GameHost 把两个相位通知接到 mod 运行时`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "必须定义 wireModLifecycleHooks",
            host.contains("private fun wireModLifecycleHooks(session: GameSession)"),
        )
        assertTrue(
            "新局通知必须转发到 modRuntimeSession.notifyNewGame",
            host.contains("session.beginHost.onNewGameStartedHook = { modRuntimeSession?.notifyNewGame() }"),
        )
        assertTrue(
            "读档通知必须转发到 modRuntimeSession.notifySaveLoaded",
            host.contains("session.beginHost.onSaveLoadedHook = { modRuntimeSession?.notifySaveLoaded() }"),
        )
        // 接线必须排在 attach 之后：两个 lambda 读的是 modRuntimeSession 字段的**当前值**
        // （赋值后立即执行也不会早，但装配顺序错了会把 null 缓进去）。
        val attach = host.indexOf("modRuntimeSession = ModRuntimeAssembly.attach(")
        val wire = host.indexOf("wireModLifecycleHooks(fresh)")
        assertTrue("GameHost 必须有 attach 调用点", attach >= 0)
        assertTrue("GameHost 必须有 wireModLifecycleHooks 调用点", wire >= 0)
        assertTrue("接线点必须排在 attach 之后", wire > attach)
    }

    @Test
    fun `ModRuntimeSession 提供两个转发方法`() {
        val session = sourceOf("ModRuntimeAssembly.kt")
        assertTrue("缺少 notifyNewGame", session.contains("fun notifyNewGame()"))
        assertTrue("缺少 notifySaveLoaded", session.contains("fun notifySaveLoaded()"))
        assertTrue("notifyNewGame 必须转发给 host", session.contains("host.notifyNewGame()"))
        assertTrue("notifySaveLoaded 必须转发给 host", session.contains("host.notifySaveLoaded()"))
        assertEquals(
            "关态早退必须成对出现（shutDown 后不再发通知）",
            4,
            Regex("if \\(shutDown\\) return").findAll(session).count(),
        )
    }
}
