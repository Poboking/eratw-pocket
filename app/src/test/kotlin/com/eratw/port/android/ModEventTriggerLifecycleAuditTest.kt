package com.eratw.port.android

import com.eratw.port.core.mod.ModAudioWiring
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModEventWiring
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.vars.VariableStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lane CO（task-86）审计 B 面：`ModEventTriggerWiring`（`mod.events.trigger` 槽）的跨会话生命周期。
 *
 * 审计当时的事实（core-vm）：`ProcessRuntime` init 里 `ModEventTriggerWiring.install(store, target)`
 * 挂上 `executeEvent` 匿名目标，而 `ModRuntimeSession.shutdown()` 只拆了 functionhooks / events /
 * audio 三槽 ⇒ 收口后槽内 `Handle.target` 仍非 null。差异只在可达性：
 * - mod 公共面（`context.events.trigger`）经 `ModEventWiring.uninstall` 解绑触发桥 ⇒ fail-closed（UOE）；
 * - raw `ModEventTriggerWiring.trigger(store, key)` 在 ProcessRuntime 记录过的 VM 线程上仍通过线程闸
 *   并真派发（`executeEvent` 跑事件函数体）—— app 侧唯一触达方式是反射，生产无调用点。
 *
 * 组长接线后的现状（本文件用例断言的就是这一版）：`ModRuntimeSession.shutdown()` 调
 * `ModEventTriggerWiring.detach(store)` 无条件摘目标 ⇒ 公共面与 raw 路径**都** fail-closed，
 * 向已收口会话派发在结构上不可能。用例名里的 `_都_fail_closed` 就是钉这条。
 *
 * 另按 CL（Lane CL / task-83）口径复核收口四槽读数。
 */
class ModEventTriggerLifecycleAuditTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `c1_shutdown_后公共面_fail_closed_且_target_已摘`() {
        val fx = shutdownSession("trigger-public")
        try {
            // mod 公共面：触发桥已在 ModEventWiring.uninstall 里置 null ⇒ fail-closed。
            val context = fx.modSession.host.contextOf(GOOD_PKG) as ModContextImpl
            var closed: UnsupportedOperationException? = null
            try {
                context.events.trigger(GOOD_EVENT)
            } catch (e: UnsupportedOperationException) {
                closed = e
            }
            assertNotNull("公共面触发桥已解绑 ⇒ fail-closed（UOE）", closed)
            assertFalse(
                "公共面触发不该派发进已收口会话（探针无日志）",
                fx.probeLog.contains("probe-event:$GOOD_EVENT"),
            )
            assertNull(
                "trigger 槽已解绑：Handle.target 必须是 null（收口走 detachModTriggerTarget）",
                triggerTargetOf(fx.store),
            )
            assertFalse(
                "收口后事件函数体 marker 尚未出现（派发前基线）",
                fx.game.console.loggedLines().any { it.contains(EVENT_RUN_MARKER) },
            )
        } finally {
            fx.game.close()
        }
    }

    @Test
    fun `c2_shutdown_后_raw_wiring_也_fail_closed_且不再派发`() {
        val fx = shutdownSession("trigger-raw")
        try {
            // raw wiring：在 ProcessRuntime 记录过的 VM 线程（跑过一次根级 run 的线程）上调。
            // 注：`rawTrigger` 走反射（槽内 Handle 是 core-vm internal），目标解绑后抛出的
            // `UnsupportedOperationException` 会被包成 `InvocationTargetException` ⇒ 解包再断言。
            val fired = AtomicBoolean(false)
            var rawFailure: Throwable? = null
            var failure: Throwable? = null
            val vmThread = Thread {
                fx.game.runtime.execute()
                try {
                    fired.set(rawTrigger(fx.store, GOOD_EVENT))
                } catch (e: InvocationTargetException) {
                    rawFailure = e.targetException
                } catch (e: UnsupportedOperationException) {
                    rawFailure = e
                }
            }
            vmThread.setUncaughtExceptionHandler { _, thrown -> failure = thrown }
            vmThread.start()
            vmThread.join(JOIN_MILLIS)
            failure?.let { throw it }

            assertTrue(
                "raw ModEventTriggerWiring.trigger 收口后必须是 fail-closed 的 UOE（不再派发）；实际=$rawFailure",
                rawFailure is UnsupportedOperationException,
            )
            assertFalse("raw 路径不该再返回派发成功", fired.get())
            assertFalse(
                "收口后事件函数体 marker 不该出现（没有任何派发进入已收口会话）",
                fx.game.console.loggedLines().any { it.contains(EVENT_RUN_MARKER) },
            )
        } finally {
            fx.game.close()
        }
    }

    /** 开态会话 + 探针 mod，跑一次 shutdown；返回仍可读的夹具（调用方负责 `game.close()`）。 */
    private fun shutdownSession(tag: String): ShutdownFixture {
        val root = tmp.newFolder(tag)
        writeChainFixtures(root)
        val out = outcome(root)
        val game = session(root, out.plan)
        out.installCatalogSlot(game.store)
        val logs = mutableListOf<String>()
        val probeLog = mutableListOf<String>()
        val modSession = checkNotNull(
            attach(root, game.store, logs, listOf(GOOD_PKG to probeMod(GOOD_EVENT, probeLog)), out.catalog),
        ) { "开态 attach 必须产出运行时；logs=$logs" }
        modSession.shutdown()
        return ShutdownFixture(game, game.store, modSession, probeLog)
    }

    private class ShutdownFixture(
        val game: GameSession,
        val store: VariableStore,
        val modSession: ModRuntimeSession,
        val probeLog: MutableList<String>,
    )

    @Test
    fun `d_收口后四槽读数全为空_且_trigger_target_已摘`() {
        val root = tmp.newFolder("teardown")
        writeChainFixtures(root)
        val out = outcome(root)
        val game = session(root, out.plan)
        val store = game.store
        try {
            out.installCatalogSlot(store)
            val logs = mutableListOf<String>()
            val probeLog = mutableListOf<String>()
            val modSession = checkNotNull(
                attach(root, store, logs, listOf(GOOD_PKG to probeMod(GOOD_EVENT, probeLog)), out.catalog),
            ) { "开态 attach 必须产出运行时；logs=$logs" }
            assertTrue(
                "收口前事件表非空（对照）",
                ModEventWiring.hasHandlers(store, GOOD_EVENT),
            )
            assertNotNull(
                "收口前 trigger 槽 target 在场（对照）",
                triggerTargetOf(store),
            )

            modSession.shutdown()

            assertNull(
                "mod.functionhooks：pipeline 已按实例同一性解绑",
                auxField(store, "mod.functionhooks", "pipeline"),
            )
            assertFalse(
                "mod.events.handlers：注册表已解绑",
                ModEventWiring.hasHandlers(store, GOOD_EVENT),
            )
            assertNull(
                "mod.audio.sink：sink 已解绑",
                ModAudioWiring.sinkOf(store),
            )
            assertNull(
                "mod.events.trigger：target 已摘（shutdown 调 detach，四槽收口对称）",
                auxField(store, "mod.events.trigger", "target"),
            )
            assertSame(
                "catalog 槽：收口不清（无删除 API，随会话 store 整体丢弃）",
                out.catalog,
                store.aux.peek<com.eratw.port.core.code.SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
            )
        } finally {
            game.close()
        }
    }

    /** 反射读 aux 槽实例上的字段：`peek` 返回的 Handle 是 core-vm internal 类型。 */
    private fun auxField(store: VariableStore, slot: String, field: String): Any? {
        val handle = store.aux.peek<Any>(slot) ?: return null
        return handle.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(handle)
    }
}

