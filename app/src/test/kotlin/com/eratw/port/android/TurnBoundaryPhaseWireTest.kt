package com.eratw.port.android

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.JobScheduler
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.PhaseDecl
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-WIRE 的 app 侧行为面：回合边界真派发内建相位（Fired / Skipped 可分）+ 回合粒度生命周期。
 *
 * 为什么单独一个文件：[ModRuntimeAssemblyWireTest] 已触 detekt `LargeClass` 阈值；detekt 纪律
 * 又不许加 `@Suppress`、不许改 baseline/阈值，所以按面拆文件，而不是往那个类里继续堆。
 * 语义权威见 `ModRuntimeAssembly.onTurnBoundary` 与 `com.eratw.port.core.mod.TurnPhaseClock`
 * 的 KDoc（相位只从 DAY 派生；官方 C# 无游戏相位概念，属移植侧扩展）。
 */
class TurnBoundaryPhaseWireTest {
    private val logs: MutableList<String> = mutableListOf()

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-phase-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
        enabled: Boolean = true,
    ): ModRuntimeSession? =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = enabled,
            logSink = { line -> logs += line },
            modProviders = providers,
            newHost = { inputs ->
                ModRuntimeHost(
                    snapshot = inputs.snapshot,
                    entityKernel = inputs.entityKernel,
                    nativeMethods = inputs.nativeMethods,
                    logSink = inputs.logSink,
                    runtime = inputs.runtime,
                    shortIdOf = inputs.shortIdOf,
                    intVarView = inputs.intVarView,
                    strVarView = inputs.strVarView,
                    storage = inputs.storage,
                )
            },
        )

    /** onLoad 绑一个按日任务槽 + 若干相位处理器的 mod（本文件的载体）。 */
    private class PhaseJobMod(
        private val jobId: String,
        private val phases: List<String>,
        private val phaseRuns: MutableList<String>,
    ) : EraMod {
        var fired: Int = 0
            private set
        var turns: Int = 0
            private set
        private var enables: Int = 0
        private var disables: Int = 0
        private var unloads: Int = 0

        override fun onLoad(context: ModContext) {
            for (phase in phases) {
                context.phases.subscribe(phase) { phaseRuns += phase }
            }
            context.jobs.bind(jobId, JobHandler { fired++ })
        }

        override fun onEnable() {
            enables++
        }

        override fun onDisable() {
            disables++
        }

        override fun onUnload() {
            unloads++
        }

        override fun onTurnBoundary() {
            turns++
        }
    }

    /** 同时绑定相位与任务，记录跨包生产派发顺序。 */
    private class OrderedMod(
        private val trace: MutableList<String>,
        private val label: String,
        private val jobId: String,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            context.phases.subscribe("DAY_START") { trace += "$label:phase" }
            context.jobs.bind(jobId) { trace += "$label:job" }
        }

        override fun onEnable() { Unit }
        override fun onDisable() { Unit }
        override fun onUnload() { Unit }
    }

    /** onLoad 排一个动态任务，onEnable 失败后其绑定和实例都应回滚。 */
    private class FailingJobMod(private val jobId: String, private val templateKey: String) : EraMod {
        var fired: Int = 0
            private set

        override fun onLoad(context: ModContext) {
            context.jobs.bind(jobId) { fired++ }
            context.jobs.scheduleOnce(templateKey, 0L)
        }

        override fun onEnable() {
            error("enable-boom")
        }

        override fun onDisable() { Unit }
        override fun onUnload() { Unit }
    }

    /** 只声明并绑定动态任务，记录跨包模板 priority 的生产顺序。 */
    private class DynamicOrderedMod(
        private val trace: MutableList<String>,
        private val label: String,
        private val templateKey: String,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            context.jobs.scheduleOnce(templateKey, 0L)
            context.jobs.bindTemplate(templateKey) { trace += label }
        }

        override fun onEnable() { Unit }
        override fun onDisable() { Unit }
        override fun onUnload() { Unit }
    }

    private class EmptyMod : EraMod {
        override fun onLoad(context: ModContext) { Unit }
        override fun onEnable() { Unit }
        override fun onDisable() { Unit }
        override fun onUnload() { Unit }
    }

    // ------------------------------------------------------------------
    // 反编译 C# 全库 phase / GameTime 零命中：游戏相位是 modapi 的移植侧扩展。P0-WIRE 立项裁决 =
    // 「谁决定 phase」由宿主显式回答：TurnPhaseClock 只从 DAY 推导（首边界/回退 = 日始、
    // 日变 = 日终 + 日始、同日 = 动作点）。任务触发口径不变（每边界空相位跑一次），但相位诊断
    // 必须分清 Fired（OK）与 Skipped（NO_HANDLERS / PHASE_NOT_DUE），不再有恒空相位的 NO_PHASE。
    // ------------------------------------------------------------------

    @Test
    fun `回合边界真派发内建相位且Fired与Skipped可分_任务口径不变`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_job.manifest",
            ModManifest(
                packageId = "mod.job",
                archetypes = listOf(ArchetypeDecl("mod.job.trait")),
                jobs = listOf(JobDecl(jobId = "daily", firstDay = 7L)),
            ),
        )
        val phaseRuns = mutableListOf<String>()
        val mod = PhaseJobMod("daily", listOf("DAY_START", "DAY_END", "ACTION"), phaseRuns)
        val store = VariableStore()
        store.writeInt("DAY", longArrayOf(0L), 7L)
        val session = attach(root, store, listOf("mod.job" to mod))!!

        // 首边界：DAY=7 是本会话第一次观察 ⇒ 计划 [DAY_START]；DAY_END 绑了但没到点。
        session.onTurnBoundary()

        assertEquals("任务口径不变：首边界仍按游戏日触发一次", 1, mod.fired)
        assertEquals("回合粒度生命周期必须真触发（onTurnBoundary 从宿主边界回调）", 1, mod.turns)
        assertEquals("到点相位必须真派发（subscribe 处理器真被调用）", listOf("DAY_START"), phaseRuns)
        val phaseRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.PHASE }
        assertTrue(
            "到点且有处理器 ⇒ OK 且 phase 为真实相位名，不再恒空：$phaseRecords",
            phaseRecords.any { it.phase == "DAY_START" && it.outcome == DiagnosticOutcome.OK && it.reasonCode == "OK" },
        )
        assertTrue(
            "绑了但本边界没到点 ⇒ SKIPPED / PHASE_NOT_DUE（Fired 与 Skipped 可区分）：$phaseRecords",
            phaseRecords.any {
                it.phase == "DAY_END" && it.outcome == DiagnosticOutcome.SKIPPED && it.reasonCode == "PHASE_NOT_DUE"
            },
        )

        // 同一游戏日再过一个边界 ⇒ 动作点，且 DAY_START 不再到点。
        session.onTurnBoundary()
        assertEquals("同一游戏日两个边界 = 动作点", listOf("DAY_START", "ACTION"), phaseRuns)
        val secondRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.PHASE }
        assertTrue(
            "第二边界必须再记一条真实相位名 ACTION 的派发读数：$secondRecords",
            secondRecords.any { it.phase == "ACTION" && it.day == 7L },
        )
        assertEquals("任务不因多边界重复触发", 1, mod.fired)
        assertEquals("第二边界仍要回调回合粒度生命周期", 2, mod.turns)
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // ModDX-4 M5P：TIME→自定义相位键单一换算口（core TimePhaseKey）在页边界 tick 段接线。
    // 撤销旧「TIME→无权威映射」搁置：键 = "time." + <TIME 原值>，引擎不引入时段语义
    // （朝/昼/夜命名归 mod 策略）；派发/订阅两侧经大写归一匹配（PhaseApi KDoc 既有纪律）。
    // ------------------------------------------------------------------

    @Test
    fun `自定义time相位按TIME原值在页边界派发_不匹配不触发_固定相位照常`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_time.manifest",
            ModManifest(
                packageId = "mod.time",
                phases = listOf(PhaseDecl("time.3")),
                jobs = listOf(JobDecl(jobId = "daily", firstDay = 7L)),
            ),
        )
        val phaseRuns = mutableListOf<String>()
        val mod = PhaseJobMod("daily", listOf("DAY_START", "DAY_END", "ACTION", "time.3"), phaseRuns)
        val store = VariableStore()
        store.writeInt("DAY", longArrayOf(0L), 7L)
        store.writeInt("TIME", longArrayOf(0L), 3L)
        val session = attach(root, store, listOf("mod.time" to mod))!!

        // 首边界（DAY=7 首见 ⇒ DAY_START；TIME=3 ⇒ time.3 追加在计划尾）：
        session.onTurnBoundary()
        assertEquals(
            "固定相位与 time 相位同拍共存：内建在前、TIME 键追加在后",
            listOf("DAY_START", "time.3"),
            phaseRuns,
        )
        assertEquals("任务口径不变：首边界仍按游戏日触发一次", 1, mod.fired)

        // 同日第二边界、TIME 改 5 ⇒ ACTION 照常、time.3 不匹配不再触发。
        store.writeInt("TIME", longArrayOf(0L), 5L)
        session.onTurnBoundary()
        assertEquals(
            "TIME 不匹配的自定义相位必须不触发（第二拍只多出 ACTION）",
            listOf("DAY_START", "time.3", "ACTION"),
            phaseRuns,
        )
        assertEquals("回合粒度生命周期照常", 2, mod.turns)
        val records = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.PHASE }
        assertTrue(
            "time.3 首边界真实派发留 OK 读数（诊断记大写归一键）：$records",
            records.any { it.phase == "TIME.3" && it.outcome == DiagnosticOutcome.OK && it.reasonCode == "OK" },
        )
        assertTrue(
            "第二边界 TIME=5 时已绑的 time.3 记 PHASE_NOT_DUE（进入既有 Fired/Skipped 机制）：$records",
            records.any {
                it.phase == "TIME.3" && it.outcome == DiagnosticOutcome.SKIPPED && it.reasonCode == "PHASE_NOT_DUE"
            },
        )
        assertTrue(
            "固定相位回归：第二边界 ACTION 照常派发：$records",
            records.any { it.phase == "ACTION" && it.outcome == DiagnosticOutcome.OK && it.reasonCode == "OK" },
        )
        session.shutdown()
    }

    @Test
    fun `TIME为0也是真实读数_time_0照常派发`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_time0.manifest",
            ModManifest(
                packageId = "mod.time0",
                phases = listOf(PhaseDecl("time.0")),
                jobs = listOf(JobDecl(jobId = "daily", firstDay = 7L)),
            ),
        )
        val phaseRuns = mutableListOf<String>()
        val mod = PhaseJobMod("daily", listOf("time.0"), phaseRuns)
        val store = VariableStore()
        store.writeInt("DAY", longArrayOf(0L), 7L)
        // TIME 不显式写：系统布局预建（SystemLayout.int1D 含 TIME），读数 = 0——是真实原值，
        // 不是「读数不可用」；不可用→空串的 fail-open 钉在 core TimePhaseKeyTest。
        val session = attach(root, store, listOf("mod.time0" to mod))!!

        session.onTurnBoundary()
        assertEquals("TIME=0 边界必须真派发 time.0（原值拼缀，不当不可用吞掉）", listOf("time.0"), phaseRuns)
        session.shutdown()
    }

    @Test
    fun `关态零接触_TIME读数与time相位都不发生`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_off.manifest",
            ModManifest(packageId = "mod.off", phases = listOf(PhaseDecl("time.3"))),
        )
        val store = VariableStore()
        store.writeInt("DAY", longArrayOf(0L), 7L)
        store.writeInt("TIME", longArrayOf(0L), 3L)
        val session = attach(
            root,
            store,
            listOf("mod.off" to PhaseJobMod("daily", listOf("time.3"), mutableListOf())),
            enabled = false,
        )

        assertEquals("关态 = 装配链零接触（无会话，TIME 读数与 time 相位都无从发生）", null, session)
        assertTrue("关态不得产生任何日志", logs.isEmpty())
    }

    @Test
    fun `前置包没有任务处理器也不会吞掉后置包到期任务`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(packageId = "mod.a", jobs = listOf(JobDecl("a", firstDay = 0L))),
        )
        writeManifest(
            root,
            "mod_b.manifest",
            ModManifest(packageId = "mod.b", jobs = listOf(JobDecl("b", firstDay = 0L))),
        )
        val b = PhaseJobMod("b", emptyList(), mutableListOf())
        val session = attach(root, VariableStore(), listOf("mod.a" to EmptyMod(), "mod.b" to b))!!
        session.onTurnBoundary()
        assertEquals("后置包任务必须恰好触发一次", 1, b.fired)
        session.shutdown()
    }

    @Test
    fun `全链相位先于任务且跨包任务按priority而非安装序执行`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(
                packageId = "mod.a",
                jobs = listOf(JobDecl("a", firstDay = 0L, priority = 10)),
            ),
        )
        writeManifest(
            root,
            "mod_b.manifest",
            ModManifest(
                packageId = "mod.b",
                jobs = listOf(JobDecl("b", firstDay = 0L, priority = 0)),
            ),
        )
        val trace = mutableListOf<String>()
        val session = attach(
            root,
            VariableStore(),
            listOf("mod.a" to OrderedMod(trace, "a", "a"), "mod.b" to OrderedMod(trace, "b", "b")),
        )!!
        session.onTurnBoundary()
        assertEquals(listOf("a:phase", "b:phase", "b:job", "a:job"), trace)
        session.shutdown()
    }

    @Test
    fun `生产共享任务按模板priority派发动态实例`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(
                packageId = "mod.a",
                jobTemplates = listOf(JobTemplateDecl("late", priority = 10)),
            ),
        )
        writeManifest(
            root,
            "mod_b.manifest",
            ModManifest(
                packageId = "mod.b",
                jobTemplates = listOf(JobTemplateDecl("early", priority = 0)),
            ),
        )
        val trace = mutableListOf<String>()
        val session = attach(
            root,
            VariableStore(),
            listOf(
                "mod.a" to DynamicOrderedMod(trace, "a", "late"),
                "mod.b" to DynamicOrderedMod(trace, "b", "early"),
            ),
        )!!
        session.onTurnBoundary()
        assertEquals(listOf("b", "a"), trace)
        session.shutdown()
    }

    @Test
    fun `失败包任务态不被其它活跃包推进且重装后可触发`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(
                packageId = "mod.a",
                jobs = listOf(JobDecl("a", firstDay = 0L)),
                jobTemplates = listOf(JobTemplateDecl("once")),
            ),
        )
        writeManifest(
            root,
            "mod_b.manifest",
            ModManifest(packageId = "mod.b", jobs = listOf(JobDecl("b", firstDay = 0L))),
        )
        val a = FailingJobMod("a", "once")
        val b = PhaseJobMod("b", emptyList(), mutableListOf())
        val session = attach(root, VariableStore(), listOf("mod.a" to a, "mod.b" to b))!!
        assertNotNull(session.host.failureOf("mod.a"))

        session.onTurnBoundary()

        assertEquals(0, a.fired)
        assertEquals(1, b.fired)
        assertEquals(0L, session.host.jobScheduler.nextFireDay("mod.a", "a"))
        assertTrue(session.host.jobScheduler.instances().isEmpty())
        assertTrue(session.reinstallRolledBack("mod.a", PhaseJobMod("a", emptyList(), mutableListOf())))
        session.onTurnBoundary()
        assertEquals(1, b.fired)
        assertEquals(JobScheduler.DONE, session.host.jobScheduler.nextFireDay("mod.a", "a"))
        session.shutdown()
    }
}
