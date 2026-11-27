package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.PackageShortIdRegistry
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.host.ConsoleSink
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopConsoleSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.FunctionHookWiring
import com.eratw.port.core.mod.JobState
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.putReservedKey
import com.eratw.port.core.mod.registeredFacesOf
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveConfig
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.DurableStory
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.HookDecl
import com.eratw.port.modapi.HookPhase
import com.eratw.port.modapi.HookResult
import com.eratw.port.modapi.InteractionContext
import com.eratw.port.modapi.InteractionHookDecl
import com.eratw.port.modapi.InteractionPhase
import com.eratw.port.modapi.InteractionResult
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobTemplateDecl
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.ModValue
import com.eratw.port.modapi.durableStory
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

/**
 * 多 mod 会话存档 → 读档后的**跨面一致性** e2e（task-110）。
 *
 * 单面各自都有测试（storage：`ModStorageStoreTest`/`ModStorageLifecycleDiskTest`；jobs：
 * `DecisionStateSaveWireTest`；注册面：`ModRegisteredFacesTest`；rng：`ModRandomSaveRoundTripE2ETest`；
 * 诊断：`ModManageDiagnostics` 解析口径），**合起来没人验过**：两个 mod 同时在场、走真
 * `FileSystemOps.saveData/loadData` 落 `sav/saveNN.sav` + 配对 `.sav.rt2`、再在新会话里读回，
 * 五个面是否仍各自一致、且**不互相串**。
 *
 * 硬闸口径（全部走生产缝，不自造引擎/存档实现）：
 * 1. store 取自 `GameSession.store`（生产 `GameHost` 同款构造口径）；
 * 2. 装配走 `ModRuntimeAssembly.attach` 缺省宿主（开态真会话）；
 * 3. 存档走 `FileSystemOps.saveData` / `loadData` + `SaveConfig.ERATW` 真落盘（`sav/save01.sav`
 *    与 `sav/save01.sav.rt2`），不直打 `SidecarSaveWiring` 端口、不手搓 bundle；
 * 4. 注册面计数读 `registeredFacesOf`；真派发三条各自走生产漏斗：
 *    events = `ProcessRuntime.executeEvent`（`executeEvent` 四桶派发，ProcessRuntime.kt:4309-4334）、
 *    hooks = `FunctionHookWiring.dispatchCall`（`ProcessRuntime.doCall` 的唯一漏斗，
 *    FunctionHookWiring.kt:16-19、:80-101）、interactions = 页边界输入证据
 *    （`ModInteractionWire.notePlayerInput` + `ModRuntimeSession.onTurnBoundary`）；
 * 5. 诊断面读 `ModManageDiagnostics.snapshot()`（attach 的 `logSink` 逐行转发）。
 *
 * 往返链路（证据行号）：
 * `ModRuntimeAssembly.attach` → `SidecarSaveWiring.install`（ModRuntimeAssembly.kt:604-616）
 * → `FileSystemOps.saveData` 配对写 `.sav`+`.sav.rt2`（FileSystemOps.kt:279-330）
 * → 槽档 `.rt2` 内含 `SidecarStateSegments(decisionStatePayload, jobStatePayload)`
 * （ModRuntimeAssembly.kt:1755-1777）→ `FileSystemOps.loadData` 末段 `restoreSidecarIfWired`
 * （FileSystemOps.kt:520-552、:707-775）→ `port.restoreRt2`（ModRuntimeAssembly.kt:1779-1833）
 * → `ModStorageStore.load` + `adoptStorage(rebuilt)`（:1789-1796、:1394-1397）
 * → `adoptSeedFromSave`（:1808、:1017-1025）→ `restoreDecisionState`（:1809、:1032-1041）
 * → `restoreJobState`（:1812-1814、:1049-1060）。
 *
 * 计时口径：全部断言写「期望 vs 实际」。
 */
class ModSaveStateIntegrityE2ETest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // 夹具：最小真会话（生产 GameHost 同款三参形态）/ manifest / 真存档点
    // ------------------------------------------------------------------

    /** index 2 是 `EVT` 事件成员：`FunctionTable.eventCallOrder` 由此派生（FuncMeta.kt:516-520）。 */
    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
        FuncMeta(index = 2, key = EVENT_KEY, isEvent = true, defIndex = 2),
    )

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-X"), 1, 0, 2)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        b.declareFunction(EVENT_KEY, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-EVT"), 1, 0, 5)
        b.emit(Opcode._RET, 0, 0, 0, 6)
        return b.build(entry)
    }

    /** store 只从真会话拿（硬闸）：与 `GameHost` 的 `fresh.store` 同一构造口径。 */
    private fun newSession(root: Path): GameSession = GameSession(
        gameRoot = root,
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
    )

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-save-integrity")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    /**
     * 每包一份形状相同（但包内命名空间独立）的 manifest：hook 槽 + 交互 PRE 钩子声明 +
     * 一个静态任务槽 + 一个动态任务模板。缺任一声明，`register`/`bind` 当场抛
     * （HookPipeline.bind 的「槽未声明」闸、InteractionPipeline 的声明闸），所以这张表就是接线前提。
     */
    private fun manifestOf(packageId: String): ModManifest = ModManifest(
        packageId = packageId,
        archetypes = listOf(ArchetypeDecl("$packageId.trait")),
        hooks = listOf(HookDecl(targetKey = HOOK_TARGET, phase = HookPhase.PREFIX)),
        interactionHooks = listOf(
            InteractionHookDecl(InteractionPhase.PRE),
            InteractionHookDecl(InteractionPhase.MAIN),
        ),
        jobs = listOf(JobDecl(jobId = JOB_ID, everyDays = 1L, firstDay = 0L)),
        jobTemplates = listOf(JobTemplateDecl(templateKey = TEMPLATE_KEY)),
    )

    private fun writeManifest(root: Path, packageId: String) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifestOf(packageId)))
        val fileName = packageId.replace('.', '_') + ".manifest"
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** 装配后的会话 ↔ 真 store/runtime 对照（`ModRuntimeSession.store` 是 private，只能回查源会话）。 */
    private val gamesByMod: MutableMap<ModRuntimeSession, GameSession> = LinkedHashMap()

    /** 生产装配入口（缺省 newHost = 宿主编排；决策熵尾巴按需注入确定字节）。 */
    private fun attach(
        root: Path,
        providers: List<Pair<String, EraMod>>,
        tailOffset: Int = 0,
        consoleSink: ConsoleSink? = null,
        inputSink: InputSink? = null,
    ): ModRuntimeSession {
        val game = newSession(root)
        for ((packageId, _) in providers) writeManifest(root, packageId)
        val attached = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = game.store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line ->
                logs += line
                // 诊断面生产读法：GameHost 的 logSink 也是逐行灌进这个解析器。
                ModManageDiagnostics.record(line)
            },
            modProviders = providers,
            decisionEntropyTail = { tail(tailOffset) },
            consoleSink = consoleSink,
            inputSink = inputSink,
        )
        assertNotNull("开态装配必须返回会话（enabled=true）", attached)
        gamesByMod[attached!!] = game
        return attached
    }

    private fun storeOf(session: ModRuntimeSession): VariableStore = gamesByMod.getValue(session).store

    private fun runtimeOf(session: ModRuntimeSession): ProcessRuntime = gamesByMod.getValue(session).runtime

    /** 熵尾巴：同 offset ⇒ 同字节 ⇒ 同决策种子；异 offset ⇒ 异种子（读档用例靠异种子证明采纳）。 */
    private fun tail(offset: Int): ByteArray = ByteArray(TAIL_BYTES) { (it + offset).toByte() }

    /** eratw 实机二进制槽档口径的生产消费点（真落盘 `.sav` + `.sav.rt2`）。 */
    private fun slotOps(store: VariableStore, root: Path): FileSystemOps =
        FileSystemOps(store, RootedSaveFileIo(root), SaveConfig.ERATW)

    private fun saveSlot(store: VariableStore, root: Path) {
        assertTrue("存档必须成功（真落盘 sav/ + .sav.rt2）", slotOps(store, root).saveData(SLOT_NO, SAVE_TEXT))
        val sav = root.resolve(SaveConfig.ERATW.normalPath(SLOT_NO))
        assertTrue("槽档主文件必须在盘上：$sav", Files.isRegularFile(sav))
        assertTrue("配对侧车必须在盘上：$sav.rt2", Files.isRegularFile(root.resolve("$sav.rt2")))
    }

    private fun loadSlot(store: VariableStore, root: Path) {
        slotOps(store, root).loadData(SLOT_NO)
    }

    private fun conflictingTargetRoot(): Path = tempGameRoot().also { root ->
        val registry = PackageShortIdRegistry().apply { allocate("local.mod") }
        Files.write(root.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME), registry.encode())
    }

    private fun copySlot(source: Path, target: Path) {
        val relative = SaveConfig.ERATW.normalPath(SLOT_NO)
        val destination = target.resolve(relative)
        Files.createDirectories(destination.parent)
        Files.copy(source.resolve(relative), destination)
        Files.copy(source.resolve("$relative.rt2"), destination.resolveSibling("${destination.fileName}.rt2"))
    }

    private fun contextOf(session: ModRuntimeSession, packageId: String): ModContext =
        session.host.contextOf(packageId) ?: error("包 $packageId 必须已装载并激活")

    private fun seedOf(session: ModRuntimeSession): Long =
        session.decisionState!!.pipeline.snapshotState().decisionSeed

    /**
     * 五面同时挂线的载体：一条事件处理器、一条 hook、一条交互 PRE 钩子、一个静态任务槽 +
     * 一个 atDay=[JOB_AT_DAY] 的动态实例。
     */
    private class IntegrityMod : EraMod {
        var eventHits: Int = 0
        var hookHits: Int = 0
        var interactionHits: Int = 0
        var jobFired: Int = 0
        var staticFired: Int = 0

        override fun onLoad(context: ModContext) {
            context.events.register(EVENT_KEY, EventBucket.NORMAL) { _ ->
                eventHits++
                EventResult.CONTINUE
            }
            context.hooks.register(HOOK_TARGET, HookPhase.PREFIX) { _ ->
                hookHits++
                HookResult.Continue
            }
            context.interactions.registerHook(InteractionPhase.PRE) { hook ->
                // 只统计输入驱动的派发（host.input）：ModDX-4 M4 起回合级触发（host.turn）
                // 每拍也派发 PRE，本用例的「证据只消费一次」口径以输入证据为准。
                if (hook.interaction.interactionId == ModInteractionWire.INTERACTION_ID) {
                    interactionHits++
                }
                InteractionResult.CONTINUE
            }
            context.jobs.bind(JOB_ID) { _ -> staticFired++ }
            context.jobs.bindTemplate(TEMPLATE_KEY) { _ -> jobFired++ }
            context.jobs.scheduleOnce(TEMPLATE_KEY, JOB_AT_DAY)
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private class PauseForSave : RuntimeException()

    private class RecordingConsole : ConsoleSink by NoopConsoleSink {
        val lines = mutableListOf<String>()
        val buttons = mutableListOf<Pair<String, Long>>()

        override fun printLine(text: String) { lines += text }
        override fun printButton(text: String, value: Long) { buttons += text to value }
    }

    private class RecordingInput : InputSink {
        private val values = ArrayDeque<Long>()

        fun feed(vararg input: Long) {
            input.forEach(values::addLast)
        }

        override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) = Unit

        override fun waitInput(request: InputRequest): InputOutcome {
            if (values.isEmpty()) throw PauseForSave()
            return InputOutcome(intValue = values.removeFirst())
        }
    }

    private class DurableMod(private val archetype: String? = null) : EraMod {
        val story: DurableStory = durableStory {
            say("opening")
            choose("first.prompt", listOf("left", "right")) { choice ->
                if (choice == 0) say("chosen.left") else say("chosen.right")
                choose("second.prompt", listOf("a", "b")) { nested -> say("nested.$nested") }
            }
            say("after")
        }
        var createdActor: EntityId? = null
            private set
        var resumedContext: InteractionContext? = null
            private set

        override fun onLoad(context: ModContext) {
            listOf(
                "opening", "first.prompt", "left", "right", "chosen.left", "chosen.right",
                "second.prompt", "a", "b", "nested.0", "nested.1", "after",
            ).forEach { context.strings.declare(it, it, it, it) }
            context.interactions.registerHook(InteractionPhase.MAIN) {
                resumedContext = it.interaction
                InteractionResult.CONTINUE
            }
            archetype?.let { createdActor = context.entities.create(it) }
        }

        fun run(context: ModContext) {
            val actor = createdActor ?: EntityId.NULL
            context.interactions.runDurable(
                InteractionContext("durable.e2e", actor, listOf(actor), actor, emptyMap()),
                story,
            )
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    @Test
    fun `真实sav与rt2读档末尾自动恢复两次选择且新局不续旧剧情`() {
        val root = tempGameRoot()
        val modA = DurableMod()
        val consoleA = RecordingConsole()
        val sessionA = attach(
            root,
            listOf(PA to modA),
            consoleSink = consoleA,
            inputSink = RecordingInput(),
        )
        assertThrows(PauseForSave::class.java) { modA.run(contextOf(sessionA, PA)) }
        assertEquals(listOf("opening", "first.prompt"), consoleA.lines)
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val modB = DurableMod()
        val consoleB = RecordingConsole()
        val inputB = RecordingInput().apply { feed(1L, 1L) }
        val sessionB = attach(
            root,
            listOf(PA to modB),
            tailOffset = 1,
            consoleSink = consoleB,
            inputSink = inputB,
        )
        loadSlot(storeOf(sessionB), root)
        sessionB.notifySaveLoaded()
        assertEquals(
            listOf("first.prompt", "chosen.right", "second.prompt", "nested.1", "after"),
            consoleB.lines,
        )
        assertEquals("", sessionB.storage.apiFor(PA).get("__eratw.durable-story.v1"))
        sessionB.shutdown()

        val consoleC = RecordingConsole()
        val sessionC = attach(
            root,
            listOf(PA to DurableMod()),
            tailOffset = 2,
            consoleSink = consoleC,
            inputSink = RecordingInput(),
        )
        loadSlot(storeOf(sessionC), root)
        sessionC.notifyNewGame()
        sessionC.notifySaveLoaded()
        assertTrue("新局不得恢复旧档剧情", consoleC.lines.isEmpty())
        assertEquals("", sessionC.storage.apiFor(PA).get("__eratw.durable-story.v1"))
        sessionC.shutdown()
    }

    @Test
    fun `生产跨设备读档允许已清空剧情续体且不恢复`() {
        val sourceRoot = tempGameRoot()
        val modA = DurableMod()
        val sessionA = attach(
            sourceRoot,
            listOf(PA to modA),
            consoleSink = RecordingConsole(),
            inputSink = RecordingInput().apply { feed(0L, 0L) },
        )
        modA.run(contextOf(sessionA, PA))
        assertEquals("", sessionA.storage.apiFor(PA).get("__eratw.durable-story.v1"))
        saveSlot(storeOf(sessionA), sourceRoot)
        sessionA.shutdown()

        val targetRoot = conflictingTargetRoot()
        copySlot(sourceRoot, targetRoot)
        val consoleB = RecordingConsole()
        val sessionB = attach(
            targetRoot,
            listOf(PA to DurableMod()),
            tailOffset = 1,
            consoleSink = consoleB,
            inputSink = RecordingInput(),
        )
        slotOps(storeOf(sessionB), targetRoot).loadDataCrossDevice(SLOT_NO)
        sessionB.notifySaveLoaded()
        assertTrue(consoleB.lines.isEmpty())
        assertEquals("", sessionB.storage.apiFor(PA).get("__eratw.durable-story.v1"))
        sessionB.shutdown()
    }

    @Test
    fun `生产跨设备读档用实体同一重映射表恢复剧情三类引用`() {
        val sourceRoot = tempGameRoot()
        val modA = DurableMod("$PA.trait")
        val sessionA = attach(
            sourceRoot,
            listOf(PA to modA),
            consoleSink = RecordingConsole(),
            inputSink = RecordingInput(),
        )
        val sourceActor = requireNotNull(modA.createdActor)
        assertEquals(EntityIdCodec.encode(2, 0, 0), sourceActor.raw)
        assertThrows(PauseForSave::class.java) { modA.run(contextOf(sessionA, PA)) }
        saveSlot(storeOf(sessionA), sourceRoot)
        sessionA.shutdown()

        val targetRoot = conflictingTargetRoot()
        copySlot(sourceRoot, targetRoot)
        val modB = DurableMod("$PA.trait")
        val consoleB = RecordingConsole()
        val sessionB = attach(
            targetRoot,
            listOf(PA to modB),
            tailOffset = 1,
            consoleSink = consoleB,
            inputSink = RecordingInput().apply { feed(1L, 1L) },
        )
        val targetActor = requireNotNull(modB.createdActor)
        assertEquals(EntityIdCodec.encode(3, 0, 0), targetActor.raw)
        slotOps(storeOf(sessionB), targetRoot).loadDataCrossDevice(SLOT_NO)
        sessionB.notifySaveLoaded()

        val remappedActor = EntityIdCodec.encode(4, 0, 0)
        val resumed = requireNotNull(modB.resumedContext)
        assertEquals(remappedActor, resumed.actor.raw)
        assertEquals(listOf(remappedActor), resumed.targets.map { it.raw })
        assertEquals(remappedActor, resumed.primaryTarget?.raw)
        assertTrue(contextOf(sessionB, PA).entities.exists(EntityId(remappedActor)))
        assertFalse(contextOf(sessionB, PA).entities.exists(targetActor))
        assertFalse(contextOf(sessionB, PA).entities.exists(sourceActor))
        assertEquals(
            listOf("first.prompt", "chosen.right", "second.prompt", "nested.1", "after"),
            consoleB.lines,
        )
        sessionB.shutdown()
    }

    @Test
    fun `生产跨设备读档遇损坏续体在提交前失败且实体与存储不变`() {
        val sourceRoot = tempGameRoot()
        val modA = DurableMod("$PA.trait")
        val sessionA = attach(
            sourceRoot,
            listOf(PA to modA),
            consoleSink = RecordingConsole(),
            inputSink = RecordingInput(),
        )
        val sourceActor = requireNotNull(modA.createdActor)
        assertThrows(PauseForSave::class.java) { modA.run(contextOf(sessionA, PA)) }
        sessionA.storage.putReservedKey(PA, "__eratw.durable-story.v1", "00")
        saveSlot(storeOf(sessionA), sourceRoot)
        sessionA.shutdown()

        val targetRoot = conflictingTargetRoot()
        copySlot(sourceRoot, targetRoot)
        val modB = DurableMod("$PA.trait")
        val sessionB = attach(
            targetRoot,
            listOf(PA to modB),
            tailOffset = 1,
            consoleSink = RecordingConsole(),
            inputSink = RecordingInput(),
        )
        val targetActor = requireNotNull(modB.createdActor)
        val storageBefore = sessionB.storage.encode()
        val registryPath = targetRoot.resolve(ModRuntimeAssembly.REGISTRY_BIN_NAME)
        val registryBefore = Files.readAllBytes(registryPath)
        val logStart = logs.size

        slotOps(storeOf(sessionB), targetRoot).loadDataCrossDevice(SLOT_NO)

        assertTrue("损坏续体必须留痕", logs.drop(logStart).any { "剧情续体" in it })
        assertTrue("提交前失败不得替换目标实体", contextOf(sessionB, PA).entities.exists(targetActor))
        assertFalse("源实体不得提前写入", contextOf(sessionB, PA).entities.exists(sourceActor))
        assertTrue("提交前失败不得替换存储", storageBefore.contentEquals(sessionB.storage.encode()))
        assertTrue("提交前失败不得改写注册表", registryBefore.contentEquals(Files.readAllBytes(registryPath)))
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 面 1：storage —— A/B 兄弟键逐值、互不串段、墓碑仍不存在
    // ------------------------------------------------------------------

    @Test
    fun `storage往返后A与B分段逐值一致且互不串段`() {
        val root = tempGameRoot()
        val sessionA = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()))
        val ctxA = contextOf(sessionA, PA)
        val ctxB = contextOf(sessionA, PB)
        ctxA.storage.put("alpha", "A-alpha")
        ctxA.storage.put("shared", "A-common")
        ctxA.storage.put("gone", "to-be-removed")
        assertTrue("删键必须成功", ctxA.storage.remove("gone"))
        ctxB.storage.put("beta", "B-beta")
        ctxB.storage.put("shared", "B-common")
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        // 新会话：装配种子与档内种子不同（异尾巴），storage 只能靠 .rt2 恢复。
        val sessionB = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()), 1)
        loadSlot(storeOf(sessionB), root)
        val ctxA2 = contextOf(sessionB, PA)
        val ctxB2 = contextOf(sessionB, PB)
        assertEquals("A 的键值必须随档恢复", "A-alpha", ctxA2.storage.get("alpha"))
        assertEquals("B 的键值必须随档恢复", "B-beta", ctxB2.storage.get("beta"))
        assertEquals("同名键按包分段隔离：A 侧", "A-common", ctxA2.storage.get("shared"))
        assertEquals("同名键按包分段隔离：B 侧", "B-common", ctxB2.storage.get("shared"))
        assertNull("A 不得读到 B 的键（分段隔离）", ctxA2.storage.get("beta"))
        assertNull("B 不得读到 A 的键（分段隔离）", ctxB2.storage.get("alpha"))
        assertNull("删除的键读档后仍不存在（段内容随档一致）", ctxA2.storage.get("gone"))
        assertEquals("A 的键集必须与存档前逐项一致", setOf("alpha", "shared"), ctxA2.storage.keys())
        assertEquals("B 的键集必须与存档前逐项一致", setOf("beta", "shared"), ctxB2.storage.keys())
        assertTrue("A 分段读档后活跃", sessionB.storage.isActive(PA))
        assertTrue("B 分段读档后活跃", sessionB.storage.isActive(PB))
        sessionB.shutdown()
    }

    @Test
    fun `未装载包的孤儿分段读档后不误激活且键读不到`() {
        val root = tempGameRoot()
        val sessionA = attach(
            root,
            listOf(PA to IntegrityMod(), PB to IntegrityMod(), PC to IntegrityMod()),
        )
        contextOf(sessionA, PC).storage.put("gamma", "G-gamma")
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        // 读档会话只装 A/B：C 的分段必须作为孤儿透传、不得被静默启用。
        val sessionB = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()), 1)
        loadSlot(storeOf(sessionB), root)
        assertFalse("未装载包 C 的分段读档后不得被静默启用", sessionB.storage.isActive(PC))
        assertTrue("C 必须在非激活集合里", sessionB.storage.inactivePackages().contains(PC))
        val orphan = sessionB.storage.sectionsSnapshot().singleOrNull { it.packageId == PC }
        assertNotNull("C 的分段必须作为孤儿保留在容器里（逐字节透传）", orphan)
        assertTrue("C 分段必须打 orphan 位", orphan!!.orphan)
        assertEquals(
            "孤儿分段的值原样保留",
            "G-gamma",
            (orphan!!.entries["gamma"] as? ModValue.StrValue)?.value,
        )
        assertNull("未装载包没有 context（不得凭空造面）", sessionB.host.contextOf(PC))
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 面 2：jobs —— 动态实例按原计划日触发；重复读档不复活、不重复触发
    // ------------------------------------------------------------------

    @Test
    fun `jobs往返后动态实例按原计划日触发且DONE不复活`() {
        val root = tempGameRoot()
        val modA = IntegrityMod()
        val sessionA = attach(root, listOf(PA to modA, PB to IntegrityMod()))
        assertEquals("存档前动态实例尚未触发", 0, modA.jobFired)
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val modB = IntegrityMod()
        val sessionB = attach(root, listOf(PA to modB, PB to IntegrityMod()), 1)
        loadSlot(storeOf(sessionB), root)
        val restored = (contextOf(sessionB, PA) as ModContextImpl).jobScheduler.snapshot().instances
            .filter { it.packageId == PA }
        assertEquals("动态实例必须随档恢复（一条）", 1, restored.size)
        assertEquals("实例键必须与计划一致", "$PA::$TEMPLATE_KEY#0", restored.single().instanceKey)
        assertEquals("实例仍在原计划日", JOB_AT_DAY, restored.single().atDay)
        assertEquals("读档后实例状态仍是 PENDING", JobState.PENDING, restored.single().state)
        assertEquals("读档本身不得触发任务", 0, modB.jobFired)

        // 读到计划日：回合边界节拍（GameTickDriver.tickTurn -> JobScheduler.runDue）。
        storeOf(sessionB).writeInt("DAY", longArrayOf(0L), JOB_AT_DAY)
        sessionB.onTurnBoundary()
        assertEquals("动态实例跨存读档后仍按原计划触发一次", 1, modB.jobFired)
        assertEquals("静态任务槽照常触发", 1, modB.staticFired)
        val after = (contextOf(sessionB, PA) as ModContextImpl).jobScheduler.snapshot().instances
            .filter { it.packageId == PA }
        assertEquals("触发后实例转 DONE（不复活）", JobState.DONE, after.single().state)

        // 重复读档：实例不得回到 PENDING、不得再触发一次。
        loadSlot(storeOf(sessionB), root)
        sessionB.onTurnBoundary()
        assertEquals("重复读档不得让已 DONE 的实例复活再触发", 1, modB.jobFired)
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 面 3：注册面 —— registeredFacesOf 计数往返一致
    // ------------------------------------------------------------------

    @Test
    fun `注册面往返后计数与存档前一致`() {
        val root = tempGameRoot()
        val sessionA = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()))
        val facesA = sessionA.host.registeredFacesOf(PA)
        val facesB = sessionA.host.registeredFacesOf(PB)
        assertEquals("A 的 hook 槽必须已绑定 1 条", 1, facesA.hooks)
        assertEquals("A 的事件处理器必须登记在 NORMAL 桶", 1, facesA.events["NORMAL"])
        assertEquals("A 的交互钩子必须已登记 1 条", 1, facesA.interactions)
        assertEquals("B 的 hook 槽必须已绑定 1 条", 1, facesB.hooks)
        assertEquals("B 的事件处理器必须登记在 NORMAL 桶", 1, facesB.events["NORMAL"])
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val sessionB = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()), 1)
        loadSlot(storeOf(sessionB), root)
        assertEquals("读档后 A 的注册面计数必须与存档前逐项一致", facesA, sessionB.host.registeredFacesOf(PA))
        assertEquals("读档后 B 的注册面计数必须与存档前逐项一致", facesB, sessionB.host.registeredFacesOf(PB))
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 面 3a/3b/3c：三个注册面的真派发命中
    // ------------------------------------------------------------------

    @Test
    fun `events读档后经引擎事件链真派发各自命中一次`() {
        val root = tempGameRoot()
        val modA = IntegrityMod()
        val modB = IntegrityMod()
        val sessionA = attach(root, listOf(PA to modA, PB to modB))
        assertTrue("存档前事件必须存在且可派发", runtimeOf(sessionA).executeEvent(EVENT_KEY))
        assertEquals("存档前 A 的事件处理器命中一次", 1, modA.eventHits)
        assertEquals("存档前 B 的事件处理器命中一次", 1, modB.eventHits)
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val modA2 = IntegrityMod()
        val modB2 = IntegrityMod()
        val sessionB = attach(root, listOf(PA to modA2, PB to modB2), 1)
        loadSlot(storeOf(sessionB), root)
        assertTrue("读档后事件仍存在且可派发", runtimeOf(sessionB).executeEvent(EVENT_KEY))
        assertEquals("读档后 A 的事件处理器命中一次", 1, modA2.eventHits)
        assertEquals("读档后 B 的事件处理器命中一次", 1, modB2.eventHits)
        assertTrue("读档后事件处理器必须挂在共享注册表上（不是新桩）", sessionB.host.eventHandlers.hasHandlers(EVENT_KEY))
        sessionB.shutdown()
    }

    @Test
    fun `hooks读档后经调用漏斗真派发命中且返回值折回`() {
        val root = tempGameRoot()
        val modA = IntegrityMod()
        val sessionA = attach(root, listOf(PA to modA, PB to IntegrityMod()))
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val modA2 = IntegrityMod()
        val sessionB = attach(root, listOf(PA to modA2, PB to IntegrityMod()), 1)
        loadSlot(storeOf(sessionB), root)
        // 生产漏斗：ProcessRuntime.doCall 包的就是这一入口（FunctionHookWiring.kt:16-19）。
        // 在册测试债的最小机械对齐（KI-GLOBAL-DIM 波登记）：dispatchCall 已加 strRet 且返回 ModValue。
        val returned = FunctionHookWiring.dispatchCall(
            store = storeOf(sessionB),
            key = HOOK_TARGET,
            intArgs = longArrayOf(),
            strArgs = arrayOf(),
            strRet = false,
        ) { _, _ -> ModValue.IntValue(222L) }
        assertEquals("hook 链必须放行原函数并折回原返回值", ModValue.IntValue(222L), returned)
        assertEquals("读档后 A 的 hook 命中一次", 1, modA2.hookHits)
        assertTrue("读档后 hook 槽必须仍挂在本会话的共享管线上", sessionB.host.hookPipeline.hasHooks(HOOK_TARGET))
        sessionB.shutdown()
    }

    @Test
    fun `interactions读档后经页边界输入证据真派发命中`() {
        val root = tempGameRoot()
        val modA = IntegrityMod()
        val modB = IntegrityMod()
        val sessionA = attach(root, listOf(PA to modA, PB to modB))
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val modA2 = IntegrityMod()
        val modB2 = IntegrityMod()
        val sessionB = attach(root, listOf(PA to modA2, PB to modB2), 1)
        loadSlot(storeOf(sessionB), root)
        val seams = sessionB.hostSeams
        assertNotNull("读档后会话必须仍有交互触发点", seams)
        seams!!.interactionWire.notePlayerInput(ModInteractionWire.SOURCE_COMMAND, "look")
        sessionB.onTurnBoundary()
        assertEquals("读档后 A 的交互 PRE 钩子命中一次", 1, modA2.interactionHits)
        assertEquals("读档后 B 的交互 PRE 钩子命中一次", 1, modB2.interactionHits)
        sessionB.onTurnBoundary()
        assertEquals("无新输入时下一个边界不得重复触发", 1, modA2.interactionHits)
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 面 4：rng —— 两 mod 按档内种子从头重放、互不干扰
    // ------------------------------------------------------------------

    @Test
    fun `rng往返后两mod按档内种子从头重放且互不干扰`() {
        val root = tempGameRoot()
        val sessionA = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()))
        val seedA = seedOf(sessionA)
        val rngA = contextOf(sessionA, PA).rng
        val rngB = contextOf(sessionA, PB).rng
        val preA = List(DRAW_COUNT) { rngA.nextInt(DRAW_BOUND) }
        val preB = List(DRAW_COUNT) { rngB.nextInt(DRAW_BOUND) }
        assertNotEquals("A/B 的随机流必须按包独立派生（同引擎种子也不同流）", preA, preB)
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()

        val sessionB = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()), 1)
        assertNotEquals("读档前装配种子必须与档内种子不同（否则采纳序不可辨）", seedA, seedOf(sessionB))
        loadSlot(storeOf(sessionB), root)
        assertEquals("读档必须采纳档内决策种子", seedA, seedOf(sessionB))
        assertTrue(
            "种子采纳必须留可观测日志（生产文案）",
            logs.any { it.contains("决策种子随档采纳 seed=$seedA") },
        )
        // 流位置不随档走；按档内种子从头重放 ⇒ 读档后前 DRAW_COUNT 次抽样必须与存档前首次逐位相同。
        // 互不干扰的判据：存档前是「A 连抽 4、再 B 连抽 4」，读档后改成 A/B 交替推进；
        // 若两包共用一条流，交替抽样必然打乱各自序列。序列仍逐位相同 ⇒ 两包流互不推进。
        val replayA = mutableListOf<Int>()
        val replayB = mutableListOf<Int>()
        repeat(DRAW_COUNT) {
            val pa = contextOf(sessionB, PA)
            val pb = contextOf(sessionB, PB)
            replayA += pa.rng.nextInt(DRAW_BOUND)
            replayB += pb.rng.nextInt(DRAW_BOUND)
        }
        assertEquals("A 的随机流按档内种子从头重放（交替序下逐位一致）", preA, replayA)
        assertEquals("B 的随机流按档内种子从头重放（交替序下逐位一致）", preB, replayB)
        sessionB.shutdown()
    }

    // ------------------------------------------------------------------
    // 面 5：诊断 —— 往返全程无装载失败/迁移失败记录
    // ------------------------------------------------------------------

    @Test
    fun `诊断面往返全程无装载失败且两包均active`() {
        ModManageDiagnostics.reset()
        val root = tempGameRoot()
        val sessionA = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()))
        saveSlot(storeOf(sessionA), root)
        sessionA.shutdown()
        val sessionB = attach(root, listOf(PA to IntegrityMod(), PB to IntegrityMod()), 1)
        loadSlot(storeOf(sessionB), root)
        sessionB.shutdown()
        assertEquals("往返全程不得留下任何装载失败记录", emptyMap<String, String>(), ModManageDiagnostics.snapshot())
        assertTrue("A 必须有一条开态装载日志", logs.any { it.startsWith("mod.install pkg=$PA active=true") })
        assertTrue("B 必须有一条开态装载日志", logs.any { it.startsWith("mod.install pkg=$PB active=true") })
        assertFalse("往返全程不得出现 active=false 装载失败行", logs.any { it.contains("active=false") })
        assertTrue("读档必须走过生产恢复链并留日志", logs.any { it.contains("mod.rt2 restore ok") })
        assertFalse("读档全程不得有绑定失配", logs.any { it.contains("绑定失配") })
    }

    private companion object {
        const val PA: String = "mod.alpha"
        const val PB: String = "mod.beta"
        const val PC: String = "mod.gamma"
        const val SLOT_NO: Int = 1
        const val SAVE_TEXT: String = "SAVE-INTEGRITY"
        const val TAIL_BYTES: Int = 16
        const val EVENT_KEY: String = "EVT"
        const val HOOK_TARGET: String = "X"
        const val JOB_ID: String = "tick"
        const val TEMPLATE_KEY: String = "once"
        const val JOB_AT_DAY: Long = 5L
        const val DRAW_COUNT: Int = 4
        const val DRAW_BOUND: Int = 1000
    }
}
