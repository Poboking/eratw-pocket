package com.eratw.port.android

import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.QueryPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-106：`ModContext` 作者面在**真装配**（[ModRuntimeAssembly.attach] 的缺省宿主，
 * 不覆写 `newHost`）下逐面探针。
 *
 * 每个用例一次真 attach，探针在 `EraMod.onLoad` 内执行——此时 attach 已把
 * settings(:423) / resources(:430) / audioSink(:437) / timing(:445) / decisionPipeline(:365)
 * 全部注入完毕，能直接观测「真装配接没接线」。
 *
 * 判据：探针调用抛 `UnsupportedOperationException` = FAIL_CLOSED（该面装配没接线）；
 * 正常返回 = WIRED。真装配零 FAIL_CLOSED 是本审计的结论面；台架口径的反向证明见
 * [ModContextFaceGateTest]。
 */
class ModContextFaceWiringAuditTest {

    @Test
    fun `真装配下 基础面 packageId logger runtime WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            assertEquals(ModContextFaceAuditFixture.PKG, context.packageId)
            context.logger.info("face-audit-logger")
            assertTrue("onVmThread={true} 下 runtime.isOnMainThread 应为真", context.runtime.isOnMainThread)
            context.runtime.submit { }
        }
        assertWired("packageId/logger/runtime", probe)
        assertTrue(
            "logger.info 必须进真装配 logSink：${probe.logs}",
            probe.logs.any { line -> line.contains(ModContextFaceAuditFixture.PKG) },
        )
    }

    @Test
    fun `真装配下 实体 关系 投射 查询面 WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            assertFalse("NULL 实体必须不存在", context.entities.exists(EntityId.NULL))
            assertTrue("空 archetype 查询必须为空", context.entities.query(ModContextFaceAuditFixture.ARCHETYPE).isEmpty())
            assertTrue(
                "manifest relationships 段声明的 kind 必须已在内核",
                context.relationships.hasKind(ModContextFaceAuditFixture.RELATION),
            )
            assertTrue("roster 代数不得为负", context.roster.rosterGeneration >= 0L)
            val plan = QueryPlan(archetypes = setOf(ModContextFaceAuditFixture.ARCHETYPE))
            assertTrue("空库 EntityApi 查询必须为空", context.query.query(plan).isEmpty())
        }
        assertWired("entities/relationships/roster/query", probe)
    }

    @Test
    fun `真装配下 事件 hook 相位 任务面 WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            val event = context.events.register("EVENTFIRST", EventBucket.NORMAL) { EventResult.CONTINUE }
            event.unregister()
            val hook = context.hooks.register(ModContextFaceAuditFixture.HOOK_TARGET, HookPhase.PREFIX) {
                HookResult.Continue
            }
            assertTrue("hooks.register 后 registrations 应有本包记录", context.hooks.registrations().isNotEmpty())
            hook.unregister()
            val phase = context.phases.subscribe(ModContextFaceAuditFixture.DAY_START) { }
            phase.unregister()
            val job = context.jobs.bind(ModContextFaceAuditFixture.JOB_ID) { }
            job.unregister()
        }
        assertWired("events/hooks/phases/jobs", probe)
    }

    @Test
    fun `真装配下 交互 行动 函数 存储面 WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            val interaction = context.interactions.registerHook(InteractionPhase.MAIN) {
                InteractionResult.CONTINUE
            }
            interaction.unregister()
            val action = context.actions.provide("audit.domain") { emptyList() }
            action.unregister()
            assertFalse("未注册函数名必须 has=false", context.functions.has("AUDIT_FN"))
            context.storage.put("audit.k", "v")
            assertEquals("v", context.storage.get("audit.k"))
            assertTrue(context.storage.remove("audit.k"))
        }
        assertWired("interactions/actions/functions/storage", probe)
    }

    @Test
    fun `真装配下 游戏状态 随机 耗时面 WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            assertFalse("空 store 下 hasIntVar 必须 false", context.gameState.hasIntVar("AUDIT_INT"))
            assertFalse("空 store 下 hasStrVar 必须 false", context.gameState.hasStrVar("AUDIT_STR"))
            assertEquals("nextInt(1) 恒为 0", 0, context.rng.nextInt(1))
            assertFalse("chance(0) 恒为假", context.rng.chance(0))
            assertTrue("空账本 samples 应为空", context.timing.samples().isEmpty())
            context.timing.record("audit", 1L)
            assertEquals("record 后应有 1 条样本", 1, context.timing.samples().size)
            context.timing.reset()
            assertTrue("reset 后 samples 应为空", context.timing.samples().isEmpty())
        }
        assertWired("gameState/rng/timing", probe)
    }

    @Test
    fun `真装配下 设置 服务 资源面 WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            assertEquals(
                "设置声明必须来自同一批 mods/rt2 manifest",
                setOf(ModContextFaceAuditFixture.KNOB_KEY),
                context.settings.keys,
            )
            assertEquals(
                ModContextFaceAuditFixture.KNOB_DEFAULT,
                context.settings.int(ModContextFaceAuditFixture.KNOB_KEY),
            )
            assertTrue("未发布服务时 publishedTypes 应为空", context.services.publishedTypes().isEmpty())
            assertEquals(ModContextFaceAuditFixture.PKG, context.resources.packageId)
            assertFalse("缺文件 exists 必须 false", context.resources.exists("missing.txt"))
            assertTrue("空资源包 keys 应为空", context.resources.keys().isEmpty())
        }
        assertWired("settings/services/resources", probe)
    }

    @Test
    fun `真装配下 文案 音频 界面面 WIRED`() {
        val root = ModContextFaceAuditFixture.tempRoot()
        val probe = ModContextFaceAuditFixture.probe(root) { context ->
            context.strings.declare("audit.label", "中文", "en", "ja")
            assertTrue(context.strings.has("audit.label"))
            assertEquals("中文", context.strings.of("audit.label"))
            assertEquals("壳层未注入语言时应为 zh", "zh", context.strings.language())
            assertFalse("缺音频资源 play 必须 false 不抛", context.audio.play("missing-audio", false))
            context.audio.stop()
            val image = context.ui.registerInteraction("audit.label") { }
            image.unregister()
        }
        assertWired("strings/audio/ui", probe)
    }

    private fun assertWired(label: String, probe: FaceProbe) {
        assertNotNull("$label：探针没拿到 ModContext", probe.context)
        assertNull("$label：真装配下不得抛（UOE=未接线，IAE=声明缺失）：${probe.failure}", probe.failure)
        assertTrue(
            "$label：包不得装载失败：${probe.logs.filter { line -> line.contains("active=false") }}",
            probe.loaded,
        )
    }
}
