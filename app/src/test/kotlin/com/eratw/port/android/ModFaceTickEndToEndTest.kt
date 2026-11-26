package com.eratw.port.android

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.BuiltinPhase
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GameTime
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.InteractionRunResult
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.PhaseDecl
import com.eratw.port.modapi.TimingSample
import com.eratw.port.modlivingworld.CharacterDeclarations
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane DK（task-109）的 app 侧行为面：**真 `GameTickDriver` 推进真回合**时，五个作者面
 * （jobs / phases / actions / interactions / timing）的**入口行为**必须满足「不 tick 零调用、
 * tick 后恰一次」。
 *
 * 与既有测试的分工（别混）：
 * - `ModContextFaceAuditFixture`（DH 波）证明「24 个作者面在真装配下都能注册/可读」；
 * - 本文件证明「**注册成功 ≠ tick 时真会派发**」：注册只说明面可达，派发要靠真回合边界。
 * - 真 tick 唯一入口 = [ModRuntimeSession.onTurnBoundary]（`ModRuntimeAssembly.kt:1537`），
 *   其内部逐 driver 调 `driver.tickTurn(day, phasePlan)`（`ModRuntimeAssembly.kt:1560` →
 *   `GameTickDriver.kt:47`）。本文件全部用例只经这个入口推进，不直调 `GameTickDriver` /
 *   `DecisionPipeline.run` / `InteractionPipeline.run` —— 否则证明的只是「组件能跑」，
 *   不是「生产节拍真会把五面接上」。
 *
 * 证据面（宿主日志，非玩家可见文案，不进域文案表）：相位 + 到期任务合并读数行
 * `mod.tick day=… phaseHandlers=… jobs=… phases=…`（`ModRuntimeAssembly.kt:1580-1585`）、
 * 外轮仲裁读数行 `mod.arbitration … outcome=COMPLETED …`（同文件 1591 的仲裁缝）、
 * PHASE/DECISION 诊断环、以及作者面 `context.timing.samples()` 的 `tick` scope。
 */
class ModFaceTickEndToEndTest {
    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // fixture：临时游戏根 + 真装配（defaultHost = 真决策管线注入）
    // ------------------------------------------------------------------

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-facetick-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve("mod_facetick.manifest"), bytes)
    }

    /**
     * 载体 mod 的 manifest：五面各声明一件，互不重叠地钉住入口前提
     * （相位 = 内建 DAY_START/ACTION + 自声明 [FaceTickMod.SELF_PHASE]；任务 = 一次性静态槽
     * + 一次性动态实例；动作 = 傍晚域候选；互动 = MAIN 阶段钩子；耗时 = 引擎自带 tick 账）。
     */
    private fun faceTickManifest(): ModManifest = ModManifest(
        packageId = FaceTickMod.PKG,
        archetypes = listOf(ArchetypeDecl("mod.facetick.trait")),
        phases = listOf(PhaseDecl(FaceTickMod.SELF_PHASE)),
        jobs = listOf(JobDecl(jobId = FaceTickMod.JOB_ID, firstDay = FaceTickMod.DAY)),
        jobTemplates = listOf(
            JobTemplateDecl(templateKey = FaceTickMod.TEMPLATE_KEY),
            JobTemplateDecl(templateKey = FaceTickMod.TEMPLATE_CANCEL_KEY),
            JobTemplateDecl(templateKey = FaceTickMod.TEMPLATE_MOVE_KEY),
        ),
        interactionHooks = listOf(InteractionHookDecl(InteractionPhase.MAIN)),
    )

    private fun openSession(day: Long = FaceTickMod.DAY): Pair<ModRuntimeSession, FaceTickMod> {
        val root = tempGameRoot()
        writeManifest(root, faceTickManifest())
        val store = VariableStore()
        // 回合时钟只从 DAY 派生（TurnPhaseClock）；测试注入起点日，不伪造 TIME。
        store.writeInt(FaceTickMod.DAY_NAME, longArrayOf(0L), day)
        val mod = FaceTickMod()
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = listOf(FaceTickMod.PKG to mod),
        )!!
        return session to mod
    }

    /**
     * 五面各记一笔「被谁调的」：相位名、任务回调时间、动作 handler 次数、互动钩子阶段 +
     * 输入证据 count、自声明相位计数。`onLoad` 里的注册**只注册不执行**，由真边界派发。
     */
    private class FaceTickMod : EraMod {
        val phaseRuns: MutableList<String> = mutableListOf()
        val jobTimes: MutableList<GameTime> = mutableListOf()
        val instanceJobTimes: MutableList<GameTime> = mutableListOf()
        val movedJobTimes: MutableList<GameTime> = mutableListOf()
        var canceledRuns: Int = 0
            private set
        var actionRuns: Int = 0
            private set
        val interactionPhases: MutableList<InteractionPhase> = mutableListOf()
        val interactionInputCounts: MutableList<String?> = mutableListOf()
        var selfPhaseRuns: Int = 0
            private set
        var enableRuns: Int = 0
            private set
        var disableRuns: Int = 0
            private set
        var unloadRuns: Int = 0
            private set
        lateinit var context: ModContext
            private set

        override fun onLoad(context: ModContext) {
            this.context = context
            // 相位面：内建两相（覆盖同日第二边界 = ACTION）+ 一个自声明相位（不在内建计划里）。
            context.phases.subscribe(BuiltinPhase.DAY_START.name) { phaseRuns += it.phase }
            context.phases.subscribe(BuiltinPhase.ACTION.name) { phaseRuns += it.phase }
            context.phases.subscribe(SELF_PHASE) { selfPhaseRuns++ }
            // 任务面：静态一次性槽 + 动态一次性实例（scheduleOnce 落在首个边界日）。
            context.jobs.bind(JOB_ID, JobHandler { jobTimes += it.time })
            context.jobs.bindTemplate(TEMPLATE_KEY, JobHandler { instanceJobTimes += it.time })
            context.jobs.scheduleOnce(TEMPLATE_KEY, DAY)
            // 任务面（取消/改期）：取消的实例到期也不派发；改期的实例按新日期派发
            // （原定次日，改回当日 ⇒ 当日这一拍就该到）。
            context.jobs.bindTemplate(TEMPLATE_CANCEL_KEY, JobHandler { canceledRuns++ })
            context.jobs.cancel(context.jobs.scheduleOnce(TEMPLATE_CANCEL_KEY, DAY))
            context.jobs.bindTemplate(TEMPLATE_MOVE_KEY, JobHandler { movedJobTimes += it.time })
            context.jobs.reschedule(context.jobs.scheduleOnce(TEMPLATE_MOVE_KEY, DAY + 1L), DAY)
            // 动作面：生产外轮域（productionOuterRoundDomains 唯一元素）的一个候选。
            context.actions.provide(CharacterDeclarations.DOMAIN_EVENING) { _ ->
                listOf(
                    ActionCandidate(
                        providerPackage = PKG,
                        candidateKey = CANDIDATE_KEY,
                        actor = EntityId(1L),
                        weight = 1,
                        handlerKey = HANDLER_KEY,
                    ),
                )
            }
            context.actions.on(HANDLER_KEY) { actionRuns++ }
            // 互动面：MAIN 阶段钩子；输入证据（source/text/count）由 ModInteractionWire 注入。
            // 只统计输入驱动的派发（host.input）：ModDX-4 M4 起回合级触发（host.turn）每拍
            // 也派发 MAIN，回合级面由 ModInteractionWireEndToEndTest 覆盖，这里不混账。
            context.interactions.registerHook(InteractionPhase.MAIN) { hook ->
                if (hook.interaction.interactionId == ModInteractionWire.INTERACTION_ID) {
                    interactionPhases += hook.phase
                    interactionInputCounts += hook.interaction.metadata["count"]
                }
                InteractionResult.CONTINUE
            }
        }

        /** 生命周期账只做对照：装配一次 onEnable，关闭各一次 onDisable/onUnload（非 tick 面）。 */
        override fun onEnable() {
            enableRuns++
        }

        override fun onDisable() {
            disableRuns++
        }

        override fun onUnload() {
            unloadRuns++
        }

        /** 作者面可读的 tick 读数（引擎每边界对本包记一笔 `tick` scope）。 */
        fun tickSamples(): List<TimingSample> = context.timing.samples().filter { it.scope == TICK_SCOPE }

        companion object {
            const val PKG: String = "mod.facetick"
            const val JOB_ID: String = "ft.job"
            const val TEMPLATE_KEY: String = "ft.tmpl"
            const val TEMPLATE_CANCEL_KEY: String = "ft.tmpl.cancel"
            const val TEMPLATE_MOVE_KEY: String = "ft.tmpl.move"
            const val SELF_PHASE: String = "FACETICK_LATE"
            const val CANDIDATE_KEY: String = "ft.candidate"
            const val HANDLER_KEY: String = "ft.handler"
            const val DAY: Long = 7L
            const val DAY_NAME: String = "DAY"
            const val TICK_SCOPE: String = "tick"
        }
    }

    /**
     * 互动面的第二层载体：`context.interactions.run(...)` 在**真 tick 派发内**（任务处理器里）
     * 调用，验五相位序与结果回写走的是真管线。独立 manifest（五相位钩子全声明），
     * 避免污染主 fixture「只注册 MAIN」的零调用口径。
     */
    private class InteractionRunMod : EraMod {
        val order: MutableList<String> = mutableListOf()
        var runResult: InteractionRunResult? = null
            private set
        var lifecycleRuns: Int = 0
            private set
        lateinit var context: ModContext
            private set

        override fun onLoad(context: ModContext) {
            this.context = context
            for (phase in InteractionPhase.values()) {
                context.interactions.registerHook(phase) { hook ->
                    // 只把作者自发起 run（ft.interaction）的相位序记账：ModDX-4 M4 起回合级
                    // 触发（host.turn）每拍也派发五相位，其序由 ModInteractionWireEndToEndTest
                    // 覆盖，不混进本用例的序断言。
                    if (hook.interaction.interactionId == InteractionRunMod.INTERACTION_ID) {
                        order += hook.phase.name
                    }
                    if (hook.phase == InteractionPhase.POST) {
                        // POST 改写结果 ⇒ 证明「结果回写」不是只读旁听。
                        hook.replacedResult = ModValue.of(REPLACED_VALUE)
                        InteractionResult.REPLACE
                    } else {
                        InteractionResult.CONTINUE
                    }
                }
            }
            // 把 run 放进真 tick 的派发链（任务处理器）里，而不是测试线程里直调。
            context.jobs.bind(JOB_ID, JobHandler {
                runResult = context.interactions.run(
                    InteractionContext(
                        interactionId = INTERACTION_ID,
                        actor = EntityId(1L),
                        targets = listOf(EntityId(2L)),
                    ),
                ) {
                    order += BODY_MARK
                    ModValue.of(BODY_VALUE)
                }
            })
        }

        override fun onEnable() {
            lifecycleRuns++
        }

        override fun onDisable() {
            lifecycleRuns++
        }

        override fun onUnload() {
            lifecycleRuns++
        }

        companion object {
            const val PKG: String = "mod.facetick.interact"
            const val JOB_ID: String = "ft.interact.job"
            const val INTERACTION_ID: String = "ft.interaction"
            const val BODY_MARK: String = "BODY"
            const val BODY_VALUE: Long = 42L
            const val REPLACED_VALUE: Long = 99L
        }
    }

    private fun interactionManifest(): ModManifest = ModManifest(
        packageId = InteractionRunMod.PKG,
        archetypes = listOf(ArchetypeDecl("mod.facetick.interact.trait")),
        jobs = listOf(JobDecl(jobId = InteractionRunMod.JOB_ID, firstDay = FaceTickMod.DAY)),
        interactionHooks = InteractionPhase.values().map { InteractionHookDecl(it) },
    )

    private fun openInteractionSession(): Pair<ModRuntimeSession, InteractionRunMod> {
        val root = tempGameRoot()
        writeManifest(root, interactionManifest())
        val store = VariableStore()
        store.writeInt(FaceTickMod.DAY_NAME, longArrayOf(0L), FaceTickMod.DAY)
        val mod = InteractionRunMod()
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = listOf(InteractionRunMod.PKG to mod),
        )!!
        return session to mod
    }

    // ------------------------------------------------------------------
    // ① 基线：真装配完成但未推回合边界 ⇒ 五面零调用
    // ------------------------------------------------------------------

    @Test
    fun `五面在真装配后零调用_未推回合边界前一律不派发`() {
        val (session, mod) = openSession()

        assertTrue("相位面：attach 不派发相位，当前=${mod.phaseRuns}", mod.phaseRuns.isEmpty())
        assertTrue(
            "任务面：静态槽 / 动态实例在 attach 后不得自行到期，当前=${mod.jobTimes}/${mod.instanceJobTimes}",
            mod.jobTimes.isEmpty() && mod.instanceJobTimes.isEmpty(),
        )
        assertEquals("动作面：register provider 不是执行 handler", 0, mod.actionRuns)
        assertTrue("互动面：注册钩子不等于运行", mod.interactionPhases.isEmpty())
        assertTrue(
            "耗时面：attach 后只有生命周期账，无 tick 账：${mod.context.timing.samples()}",
            mod.tickSamples().isEmpty(),
        )
        assertTrue(
            "宿主读数行：边界前不得出现 mod.tick / mod.arbitration：$logs",
            logs.none { it.contains("mod.tick") || it.contains("mod.arbitration") },
        )
        assertEquals("对照：装配完成 ⇒ onEnable 恰一次", 1, mod.enableRuns)
        assertEquals("对照：未关闭 ⇒ onDisable 零次", 0, mod.disableRuns)

        session.shutdown()
        assertEquals("对照：关闭会话 ⇒ onDisable 恰一次", 1, mod.disableRuns)
        assertEquals("对照：关闭会话 ⇒ onUnload 恰一次", 1, mod.unloadRuns)
    }

    // ------------------------------------------------------------------
    // ② 一次真回合边界：五面各恰一次
    // ------------------------------------------------------------------

    @Test
    fun `一次真回合边界五面各恰一次_相位任务动作互动耗时同拍到账`() {
        val (session, mod) = openSession()
        session.hostSeams!!.interactionWire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "1")

        session.onTurnBoundary()

        assertEquals(
            "相位面：首边界 DAY=7 ⇒ TurnPhaseClock 计划 [DAY_START]，恰一次",
            listOf(BuiltinPhase.DAY_START.name),
            mod.phaseRuns,
        )
        assertEquals(
            "任务面：静态槽到点恰一次，回调时间为空相位 + 当日",
            listOf(GameTime(day = FaceTickMod.DAY, phase = "")),
            mod.jobTimes,
        )
        assertEquals(
            "任务面：动态实例到点恰一次",
            listOf(GameTime(day = FaceTickMod.DAY, phase = "")),
            mod.instanceJobTimes,
        )
        assertEquals("动作面：外轮胜者 handler 恰一次", 1, mod.actionRuns)
        assertEquals(
            "互动面：本边界消费一次入队输入 ⇒ MAIN 钩子恰一次",
            listOf(InteractionPhase.MAIN),
            mod.interactionPhases,
        )
        assertEquals("互动证据逐字进上下文（一条输入 ⇒ count=1）", listOf("1"), mod.interactionInputCounts)
        assertEquals("互动证据消费后待处理计数归零", 0, session.hostSeams!!.interactionWire.pendingCount())
        assertEquals("耗时面：一次边界 = 一笔 tick 账", 1L, mod.tickSamples().single().count)

        val tickLine = logs.singleOrNull { it.contains("mod.tick day=${FaceTickMod.DAY}") }
        assertTrue("真 tick 必须留相位 + 任务合并读数行：$logs", tickLine != null)
        assertTrue("读数行证明相位真派发、任务真到期：$tickLine", tickLine!!.contains("phaseHandlers=1"))
        assertTrue("读数行证明任务真到期一次：$tickLine", tickLine.contains("jobs=1"))
        assertTrue("读数行带真正计划的相位：$tickLine", tickLine.contains("phases=DAY_START"))

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ③ 同日第二边界：相位推进到 ACTION，任务与已消费互动不重复
    // ------------------------------------------------------------------

    @Test
    fun `同游戏日第二边界只推ACTION_任务与已消费互动不重复`() {
        val (session, mod) = openSession()
        session.onTurnBoundary()

        session.onTurnBoundary()

        assertEquals(
            "相位面：同日第二边界 = ACTION 恰一次（DAY_START 不重发）",
            listOf(BuiltinPhase.DAY_START.name, BuiltinPhase.ACTION.name),
            mod.phaseRuns,
        )
        assertEquals("任务面：一次性静态槽不因多边界重复触发", 1, mod.jobTimes.size)
        assertEquals("任务面：一次性动态实例也只触发一次", 1, mod.instanceJobTimes.size)
        assertEquals("动作面：每个边界一轮外轮 ⇒ 两边界两次（不是每帧轮询）", 2, mod.actionRuns)
        assertEquals("互动面：两边界都无输入 ⇒ 钩子零调用（不是每拍轮询）", 0, mod.interactionPhases.size)
        assertEquals("耗时面：第二个边界再记一笔 tick 账", 2L, mod.tickSamples().single().count)

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ④ 互动面：按入队输入派发（无输入零调用 / 多输入合拍恰一次）
    // ------------------------------------------------------------------

    @Test
    fun `互动面按入队输入派发_无输入边界零调用多输入合拍恰一次`() {
        val (session, mod) = openSession()
        val wire = session.hostSeams!!.interactionWire

        session.onTurnBoundary()
        assertTrue("无输入边界：互动面零调用（不是每拍轮询）", mod.interactionPhases.isEmpty())

        wire.notePlayerInput(ModInteractionWire.SOURCE_CLICK, "1")
        wire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "2")
        assertEquals("输入已入队但未推边界 ⇒ 仍零调用", 0, mod.interactionPhases.size)
        assertEquals("待消费证据计数 = 2", 2, wire.pendingCount())

        session.onTurnBoundary()
        assertEquals("一个边界只消费一次 ⇒ MAIN 钩子恰一次（两条输入合并成一拍）", 1, mod.interactionPhases.size)
        assertEquals("证据合拍：count=2 逐字进上下文", listOf("2"), mod.interactionInputCounts)
        assertEquals("消费后待处理计数归零", 0, wire.pendingCount())

        session.onTurnBoundary()
        assertEquals("下一拍无新输入 ⇒ 不重放上一拍证据", 1, mod.interactionPhases.size)

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ⑤ 动作面：外轮每边界恰一次 + 读数 / DECISION 诊断可读
    // ------------------------------------------------------------------

    @Test
    fun `动作面外轮每边界恰一次_仲裁读数与DECISION诊断同拍可读`() {
        val (session, mod) = openSession()
        assertTrue("边界前零仲裁读数", logs.none { it.contains("mod.arbitration") })

        session.onTurnBoundary()

        assertEquals("外轮胜者 handler 恰一次", 1, mod.actionRuns)
        val arbitrationLines = logs.filter { it.contains("mod.arbitration") }
        assertEquals("每边界恰一行仲裁读数：$arbitrationLines", 1, arbitrationLines.size)
        val line = arbitrationLines.single()
        assertTrue("读数带真实外轮域：$line", line.contains(CharacterDeclarations.DOMAIN_EVENING))
        assertTrue("读数带终局结果：$line", line.contains("outcome=COMPLETED"))
        assertTrue("读数带胜者候选键：$line", line.contains("chosen=${FaceTickMod.CANDIDATE_KEY}"))
        assertTrue("读数带包归属：$line", line.contains("pkg=${FaceTickMod.PKG}"))

        val decisionRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.DECISION }
        assertTrue(
            "DECISION 诊断环必须留终局 OK 且域键可读：$decisionRecords",
            decisionRecords.any {
                it.outcome == DiagnosticOutcome.OK && it.key == CharacterDeclarations.DOMAIN_EVENING
            },
        )

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ⑥ 耗时面：tick scope 每边界恰一笔，未 tick 前不预记
    // ------------------------------------------------------------------

    @Test
    fun `耗时面tick读数每边界恰一笔_未tick前不得预记`() {
        val (session, mod) = openSession()
        assertTrue("attach 后无 tick 账（生命周期账不算 tick）", mod.tickSamples().isEmpty())

        session.onTurnBoundary()
        val first = mod.tickSamples().single()
        assertEquals("一次边界 = 一笔 tick", 1L, first.count)
        assertTrue("读数时间自洽：总耗时 ≥ 单次最大 ≥ 0", first.totalNanos >= first.maxNanos && first.maxNanos >= 0L)

        session.onTurnBoundary()
        assertEquals("两次边界 = 两笔 tick", 2L, mod.tickSamples().single().count)

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ⑦ 相位面负控：自声明相位不在 TurnPhaseClock 计划里 ⇒ 不派发但必须留取证
    // ------------------------------------------------------------------

    @Test
    fun `自声明相位不进内建计划_真边界记PHASE_NOT_DUE不静默派发`() {
        val (session, mod) = openSession()

        session.onTurnBoundary()

        assertEquals(
            "自声明相位不在 TurnPhaseClock 计划里 ⇒ 真边界零派发",
            0,
            mod.selfPhaseRuns,
        )
        val phaseRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.PHASE }
        assertTrue(
            "绑定但本边界没到点必须留 SKIPPED/PHASE_NOT_DUE 取证，而不是静默：$phaseRecords",
            phaseRecords.any {
                it.phase == FaceTickMod.SELF_PHASE &&
                    it.outcome == DiagnosticOutcome.SKIPPED &&
                    it.reasonCode == "PHASE_NOT_DUE"
            },
        )

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ⑧ 任务面：取消的永不派发 / 改期的按新日期派发
    // ------------------------------------------------------------------

    @Test
    fun `任务面取消与改期_取消的永不派发改期的按新日派发`() {
        val (session, mod) = openSession()

        session.onTurnBoundary()

        assertEquals("任务面：cancel 后到期日也不派发（恰零次）", 0, mod.canceledRuns)
        assertEquals(
            "任务面：reschedule 到当日 ⇒ 按新日期恰一次（原定次日不残留一次）",
            listOf(GameTime(day = FaceTickMod.DAY, phase = "")),
            mod.movedJobTimes,
        )

        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ⑨ 互动面：run(...) 五相位序 + 结果回写，且必须发生在真 tick 派发内
    // ------------------------------------------------------------------

    @Test
    fun `互动面在真tick派发内跑五相位序_结果回写取代主流程返回值`() {
        val (session, mod) = openInteractionSession()

        assertTrue("互动面：未 tick 前 run 零调用（不是装配期就执行）", mod.order.isEmpty())
        assertEquals("对照：装配完成 ⇒ 生命周期 onEnable 一次", 1, mod.lifecycleRuns)

        session.onTurnBoundary()

        assertEquals(
            "五相位序：PRECONDITION → PRE → MAIN 钩子 → 主流程 → POST → SETTLEMENT",
            listOf("PRECONDITION", "PRE", "MAIN", "BODY", "POST", "SETTLEMENT"),
            mod.order,
        )
        val result = mod.runResult
        assertTrue("真 tick 内 run 必须回结果，当前=${mod.runResult}", result != null)
        assertEquals(
            "POST 阶段 REPLACE 改写结果 ⇒ 最终值是改写值而非主流程 42",
            ModValue.of(InteractionRunMod.REPLACED_VALUE),
            result!!.returnValue,
        )
        assertTrue("结果回写标记 replaced=true", result.replaced)
        assertEquals("未触发 INTERRUPT", false, result.interrupted)
        assertTrue("无失败包：${result.failures}", result.failures.isEmpty())

        session.shutdown()
        assertEquals("对照：关闭 ⇒ onEnable + onDisable + onUnload 共三次", 3, mod.lifecycleRuns)
    }
}
