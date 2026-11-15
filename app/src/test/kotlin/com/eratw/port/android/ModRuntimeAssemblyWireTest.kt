package com.eratw.port.android

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.entity.EntityIdCodec
import com.eratw.port.core.entity.RealCharaSlotProjector
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModContextImpl
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModStorageStore
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.save.SidecarBinding
import com.eratw.port.core.save.SidecarSaveBundle
import com.eratw.port.core.save.SidecarSaveWiring
import com.eratw.port.core.vars.CharacterTemplate
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EntityId
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.GlobalDimDecl
import com.eratw.port.modapi.JobDecl
import com.eratw.port.modapi.JobHandler
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modfamily.FamilyDeclarations
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * RT2 车 B1 的 app 侧接线哨兵 + 行为面（P0-1 宿主装配 / P0-3 单一时间源 / P2-3 诊断消费者）。
 *
 * 两族判据：
 * 1. **源码面**（与 `EventWiringTest` / `GameHostModsWireTest` 同族——`GameHost` 挂在 Android
 *    运行时上，JVM 单测面实例化不了）：总闸缺省关、装配点/节拍点/收口点的接线形状、
 *    装配文件纯 JVM（无 Android import、不摸 SharedPreferences）。
 * 2. **行为面**（[ModRuntimeAssembly] 纯 JVM，可直测）：关态零接触（反向证明①）、
 *    两个 mod 各建实体 pkgShortId 位必不同（反向证明②，`{1}` 假默认 = 跨包撞号的契约陷阱）、
 *    DAY 只读且 mod 侧 `getIntVar("DAY")` 与节拍同源（P0-1/P0-3 收敛点）、诊断读者接通、
 *    转移边界兑现、收口幂等。
 */
class ModRuntimeAssemblyWireTest {

    // ------------------------------------------------------------------
    // 源码面哨兵
    // ------------------------------------------------------------------

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        // 仓里是 CRLF 工作树：多行锚点先归一（EventWiringTest 2026-11-08 同款口径）。
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `总闸缺省关且装配点挂在闸后`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "总闸参数必须缺省 false（关态行为逐字节不变）",
            host.contains("private val modRuntime: Boolean = false,"),
        )
        assertTrue(
            "装配段必须把总闸原样透传给 attach（enabled = modRuntime）",
            host.contains("enabled = modRuntime,"),
        )
        val assembly = sourceOf("ModRuntimeAssembly.kt")
        val gateAt = assembly.indexOf("if (!enabled) return null")
        val hostCallAt = assembly.indexOf("val host = newHost(inputs)")
        assertTrue("装配文件必须有总闸首查", gateAt >= 0)
        assertTrue("装配文件必须有唯一宿主构造点", hostCallAt >= 0)
        assertTrue(
            "总闸首查必须在宿主构造之前（关态构造次数 == 0）",
            gateAt < hostCallAt,
        )
    }

    @Test
    fun `页边界节拍追加在turn写点之后且不改写既有行`() {
        val host = sourceOf("GameHost.kt")
        // EventWiringTest 锁定的既有行原样还在（追加纪律：不许改写）。
        assertTrue(
            "turn.end 既有写点必须原样保留",
            host.contains("EventJournal.record(\"turn.end\", mapOf(\"ms\" to ms.toString()))"),
        )
        val turnEndAt = host.indexOf("EventJournal.record(\"turn.end\", mapOf(\"ms\" to ms.toString()))")
        val tickAt = host.indexOf("modRuntimeSession?.onTurnBoundary()")
        assertTrue("页边界必须有 Mod 节拍调用", tickAt >= 0)
        assertTrue(
            "节拍调用只能追加在 turn.end 写点之后（P0-3 追加纪律）",
            tickAt > turnEndAt,
        )
        assertTrue(
            "节拍调用必须在 onTurnReady 页边界（存档检查点兑现点之后同段）",
            tickAt > host.indexOf("drainSaveCheckpoint(\"turn\")"),
        )
    }

    @Test
    fun `Mod收口挂在本会话关闭之前`() {
        val host = sourceOf("GameHost.kt")
        val shutdownAt = host.indexOf("modRuntimeSession?.shutdown()")
        val sessionCloseAt = host.indexOf("runCatching { session?.close() }")
        assertTrue("退出清场必须有 Mod 运行时收口", shutdownAt >= 0)
        assertTrue(
            "Mod 收口必须在会话 close 之前（mod 收尾还要读 store）",
            shutdownAt in 0 until sessionCloseAt,
        )
    }

    @Test
    fun `装配文件纯JVM且不摸偏好表`() {
        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(
            "装配文件不得 import Android API（纯 JVM，日志经 logSink 注入）",
            !assembly.contains("import android."),
        )
        assertTrue(
            "装配文件不得读 SharedPreferences（配置只经 GameHost 构造参数单路传入）",
            !assembly.contains("SharedPreferences"),
        )
    }

    // ------------------------------------------------------------------
    // 行为面 fixture
    // ------------------------------------------------------------------

    private val logs: MutableList<String> = mutableListOf()

    /** 临时游戏根；`mods/rt2/` 目录按需建。 */
    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-b1-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun freshStore(): VariableStore = VariableStore()

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = ManifestCodec.encode(ModDeclarationAdapter.toDescriptor(manifest))
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** onLoad 建一个实体的 mod（反向证明②的载体）。 */
    private class EntityMod(
        private val archetype: String,
        private val created: MutableList<EntityId>,
    ) : EraMod {
        override fun onLoad(context: ModContext) {
            created += context.entities.create(archetype)
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}
    }

    /** onLoad 绑一个静态任务槽处理器并暴露 runtime 的 mod（节拍 / 边界用例的载体）。 */
    private class JobMod(
        private val jobId: String,
        private val handler: JobHandler,
    ) : EraMod {
        var fired: Int = 0
            private set
        var runtime: com.eratw.port.modapi.RuntimeScheduler? = null
            private set

        override fun onLoad(context: ModContext) {
            runtime = context.runtime
            context.jobs.bind(jobId, JobHandler { ctx -> handler.run(ctx); fired++ })
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}
    }

    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>> = emptyList(),
        enabled: Boolean = true,
        newHost: ((AssemblyInputs) -> ModRuntimeHost)? = null,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = store,
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = enabled,
        logSink = { line -> logs += line },
        modProviders = providers,
        newHost = newHost ?: { inputs ->
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

    // ------------------------------------------------------------------
    // RT2 D 车：adoptStorage 把分段交还 mod 侧（context storage 视图重绑）
    // ------------------------------------------------------------------

    /** onLoad 写一个 storage 键并记下 context 的 mod（重绑用例的载体）。 */
    private class StorageMod(private val key: String, private val value: String) : EraMod {
        var context: ModContext? = null
            private set

        override fun onLoad(context: ModContext) {
            this.context = context
            context.storage.put(key, value)
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}
    }

    @Test
    fun `adoptStorage后同一context对象storage读写落在新容器`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_a.manifest", ModManifest(packageId = "mod.a"))
        val mod = StorageMod("k", "v1")
        val session = attach(root = root, store = freshStore(), providers = listOf("mod.a" to mod))!!
        assertNotNull(mod.context)
        val context = mod.context!!
        context.storage.put("k2", "v2")
        val rebuilt = ModStorageStore.load(session.storage.encode(), knownPackages = setOf("mod.a"))
        val old = session.storage

        session.adoptStorage(rebuilt)

        assertSame("会话容器必须换成重建实例", rebuilt, session.storage)
        assertSame("context 对象不变（对 mod 透明）", context, session.host.contextOf("mod.a"))
        assertEquals("恢复段原值经同一 context 读回", "v1", context.storage.get("k"))
        assertEquals("v2", context.storage.get("k2"))
        // 重绑后的新写落在新容器：旧容器读不到。
        context.storage.put("k3", "v3")
        assertNull("重绑后不得再写旧容器", old.apiFor("mod.a").get("k3"))
        assertEquals("v3", rebuilt.apiFor("mod.a").get("k3"))
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 反向证明①：关态装配链一次都不进（宿主构造次数 == 0）
    // ------------------------------------------------------------------

    @Test
    fun `关态下宿主构造次数为0且连磁盘都不碰`() {
        val root = tempGameRoot()
        // 故意放一份坏 manifest：关态连读都不读（不抛 = 链一次都没进）；开态同一份当场整包拒绝。
        Files.write(
            root.resolve("mods").resolve("rt2").resolve("broken.manifest"),
            "not a manifest".toByteArray(Charsets.UTF_8),
        )
        var constructed = 0
        val session = attach(
            root = root,
            store = freshStore(),
            enabled = false,
            newHost = { _ ->
                constructed++
                throw IllegalStateException("关态不得构造宿主")
            },
        )
        assertNull("关态必须返回 null（零接触）", session)
        assertEquals("关态宿主构造次数必须为 0", 0, constructed)
        assertTrue("关态不得产生任何日志（什么都没跑）", logs.isEmpty())
        logs.clear()

        // 同一现场的负例：开态读同一份坏档 = 整包拒绝（fail-closed，不是静默跳过）。
        try {
            attach(root = root, store = freshStore(), enabled = true)
            fail("开态坏 manifest 必须整包拒绝")
        } catch (expected: ManifestFormatException) {
            // 预期：装载/编译相位的显式拒绝。
        }
    }

    // ------------------------------------------------------------------
    // 反向证明②：两 mod 各建实体，pkgShortId 位必不同
    // ------------------------------------------------------------------

    @Test
    fun `两个mod各建实体pkgShortId位必不同`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(packageId = "mod.a", archetypes = listOf(ArchetypeDecl("mod.a.trait"))),
        )
        writeManifest(
            root,
            "mod_b.manifest",
            ModManifest(packageId = "mod.b", archetypes = listOf(ArchetypeDecl("mod.b.trait"))),
        )
        val createdA = mutableListOf<EntityId>()
        val createdB = mutableListOf<EntityId>()
        val session = attach(
            root = root,
            store = freshStore(),
            providers = listOf(
                "mod.a" to EntityMod("mod.a.trait", createdA),
                "mod.b" to EntityMod("mod.b.trait", createdB),
            ),
        )
        assertNotNull("开态必须产出运行时", session)
        assertEquals("两个 mod 各建一个实体", 1, createdA.size)
        assertEquals(1, createdB.size)
        val pkgA = EntityIdCodec.pkgShortIdOf(createdA.single().raw)
        val pkgB = EntityIdCodec.pkgShortIdOf(createdB.single().raw)
        assertNotEquals(
            "pkgShortId 位跨包必不同（照 {1} 假默认接 = 所有 mod 都拿 1 号跨包撞号）",
            pkgA,
            pkgB,
        )
        assertNotEquals("内核包固定 1 号（__core__），mod 包不得撞内核号", 1, pkgA)
        assertTrue("短 ID 从 2 起单调分配", pkgA >= 2 && pkgB >= 2)
        session!!.shutdown()
    }

    // ------------------------------------------------------------------
    // P0-3：DAY 只读 + 节拍触发 + intVarView 同源
    // ------------------------------------------------------------------

    @Test
    fun `节拍只读DAY且mod侧getIntVar与节拍同源`() {
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
        val mod = JobMod("daily", JobHandler { })
        val store = freshStore()
        store.writeInt("DAY", longArrayOf(0L), 7L)
        val session = attach(root = root, store = store, providers = listOf("mod.job" to mod))!!
        // DAY=7 的一次边界：任务到期触发一次；DAY 必须原值（写入次数为 0，时钟归 ERB）。
        session.onTurnBoundary()
        assertEquals("任务在 DAY=7 边界触发一次", 1, mod.fired)
        assertEquals("节拍不得写 DAY（时间权威只读铁律）", 7L, store.readInt("DAY", longArrayOf(0L)))
        // P0-1/P0-3 收敛点：mod 侧 gameState 与节拍同一把 DAY 尺（不再是恒 0 假默认）。
        val context = session.host.contextOf("mod.job") as ModContextImpl
        assertEquals(7L, context.gameState.getIntVar("DAY"))
        // 再推一拍（同一游戏日）：一次性任务不重不漏（恰好触发一次）。
        session.onTurnBoundary()
        assertEquals(1, mod.fired)
        // 未到期（DAY=3）不触发。
        store.writeInt("DAY", longArrayOf(0L), 3L)
        session.onTurnBoundary()
        assertEquals("firstDay=7 在 DAY=3 不得触发", 1, mod.fired)
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // P2-3：诊断读者（0 → 1）
    // ------------------------------------------------------------------

    @Test
    fun `任务处理器失败进诊断环并上日志面读者`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_boom.manifest",
            ModManifest(
                packageId = "mod.boom",
                archetypes = listOf(ArchetypeDecl("mod.boom.trait")),
                jobs = listOf(JobDecl(jobId = "boom", firstDay = 0L)),
            ),
        )
        val mod = JobMod("boom", JobHandler { throw IllegalStateException("boom-handler") })
        val session = attach(
            root = root,
            store = freshStore(),
            providers = listOf("mod.boom" to mod),
        )!!
        session.onTurnBoundary()
        // 诊断读者：FAILED 记录上日志面（取证面文案，非玩家可见，不进域文案表）。
        assertTrue(
            "诊断读者必须把 FAILED 记录送进日志面",
            logs.any { it.contains("mod.diag") && it.contains("FAILED") && it.contains("HANDLER_THREW") },
        )
        // 诊断环里有这条记录（域/键/原因码同源）。
        val failed = session.diagnostics.snapshot()
            .filter { it.outcome == DiagnosticOutcome.FAILED }
        assertTrue("诊断环必须有 FAILED 记录", failed.isNotEmpty())
        assertTrue(failed.any { it.domain == DiagnosticDomain.JOB && it.key == "boom" })
        // 宿主报告消费：mod.tick 失败行（P2-1 的「不许静默」面）。
        assertTrue(logs.any { it.contains("mod.tick") && it.contains("失败") })
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 转移边界：submit 入队、页边界兑现
    // ------------------------------------------------------------------

    @Test
    fun `submit在边界前不执行页边界兑现`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_submit.manifest",
            ModManifest(
                packageId = "mod.submit",
                archetypes = listOf(ArchetypeDecl("mod.submit.trait")),
                jobs = listOf(JobDecl(jobId = "noop", firstDay = 0L)),
            ),
        )
        val mod = JobMod("noop", JobHandler { })
        val session = attach(
            root = root,
            store = freshStore(),
            providers = listOf("mod.submit" to mod),
        )!!
        var applied = false
        val scheduler = mod.runtime ?: error("mod 侧 runtime 必须是装配注入的真实现")
        scheduler.submit { applied = true }
        assertTrue("提交必须在边界兑现，不能就地执行", !applied)
        session.onTurnBoundary()
        assertTrue("页边界必须兑现积压提交", applied)
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 收口：shutdown 幂等 + 装载读数
    // ------------------------------------------------------------------

    @Test
    fun `shutdown幂等且日志面留装载与收口读数`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_plain.manifest",
            ModManifest(packageId = "mod.plain", archetypes = listOf(ArchetypeDecl("mod.plain.trait"))),
        )
        val session = attach(
            root = root,
            store = freshStore(),
            providers = listOf("mod.plain" to EntityMod("mod.plain.trait", mutableListOf())),
        )!!
        session.shutdown()
        session.shutdown()
        assertTrue(
            "装载读数必须在（无 provider 的 mod.plain 只是声明面装载）",
            logs.any { it.contains("mod.install pkg=mod.plain active=true") },
        )
        assertTrue(
            "收口读数必须在且只留一行（幂等）",
            logs.count { it.contains("mod.shutdown 完成") } == 1,
        )
    }

    // ------------------------------------------------------------------
    // 生产构造缝 defaultHost：上面各族都经 newHost 注入等价 lambda，生产用的 defaultHost
    // 一次都没被执行过 —— 六注入位在生产缝上漏一个也全绿。这一例不传 newHost，直打生产缝。
    // ------------------------------------------------------------------

    @Test
    fun `生产构造缝六注入位全真值`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_a.manifest",
            ModManifest(packageId = "mod.a", archetypes = listOf(ArchetypeDecl("mod.a.trait"))),
        )
        writeManifest(
            root,
            "mod_b.manifest",
            ModManifest(packageId = "mod.b", archetypes = listOf(ArchetypeDecl("mod.b.trait"))),
        )
        writeManifest(
            root,
            "mod_c.manifest",
            ModManifest(
                packageId = "mod.c",
                archetypes = listOf(ArchetypeDecl("mod.c.trait")),
                jobs = listOf(JobDecl(jobId = "daily", firstDay = 5L)),
            ),
        )
        val store = freshStore()
        store.writeInt("DAY", longArrayOf(0L), 5L)
        store.writeStr("SAVESTR", longArrayOf(0L), "seam")
        val createdA = mutableListOf<EntityId>()
        val createdB = mutableListOf<EntityId>()
        val jobMod = JobMod("daily", JobHandler { })
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = listOf(
                "mod.a" to EntityMod("mod.a.trait", createdA),
                "mod.b" to EntityMod("mod.b.trait", createdB),
                "mod.c" to jobMod,
            ),
        )!!
        // ① shortIdOf：恒 {1} 假默认在这里会红（跨包撞号）。
        val pkgA = EntityIdCodec.pkgShortIdOf(createdA.single().raw)
        val pkgB = EntityIdCodec.pkgShortIdOf(createdB.single().raw)
        assertNotEquals("生产缝的 shortIdOf 必须是会话级分配表", pkgA, pkgB)
        assertTrue("短 ID 从 2 起（1 号是内核 __core__）", pkgA >= 2 && pkgB >= 2)
        // ②③ intVarView / strVarView：恒 0 / 恒空串假默认在这里会红。
        val gameState = (session.host.contextOf("mod.a") as ModContextImpl).gameState
        assertEquals(5L, gameState.getIntVar("DAY"))
        assertEquals("seam", gameState.getStrVar("SAVESTR"))
        // ④ runtime：「调用即执行」假默认在这里会红。
        var applied = false
        (jobMod.runtime ?: error("生产缝必须注入真 scheduler")).submit { applied = true }
        assertTrue("生产缝的 submit 必须入队而非就地执行", !applied)
        // ⑤ storage：会话级唯一一份容器，宿主与会话必须同一个实例。
        assertTrue(
            "生产缝必须把同一份 ModStorageStore 交给宿主与会话",
            session.host.storage === session.storage,
        )
        // ⑥ logSink：装载读数经宿主注入的日志面出去。
        assertTrue(logs.any { it.contains("mod.install pkg=mod.a active=true") })
        session.onTurnBoundary()
        assertTrue("页边界兑现积压提交", applied)
        assertEquals("firstDay=5 在 DAY=5 触发一次", 1, jobMod.fired)
        assertEquals("节拍不得写 DAY", 5L, store.readInt("DAY", longArrayOf(0L)))
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // RT2-B③：生产注册表 + 包内内置 manifest 的端到端真装（B② 三例源码文本钉的覆盖缺口）
    // ------------------------------------------------------------------

    @Test
    fun `内置资产manifest开态在生产构造缝真装FamilyMod`() {
        val root = tempGameRoot()
        // RT2-M91 机械适配：注册表现在登记两只载体（family + memory），ModContextImpl 构造期
        // require packageId 在声明快照内（core ModContextImpl.kt:98）⇒ 生产「全部随包 manifest
        // 都已投递」的形状 = 每个 in-registry 载体的 manifest 都得在游戏根，测试缝照抄这个形状。
        for (packageId in ModCarrierRegistry.providers().map { it.first }) {
            val manifest = File("src/main/assets/mods/rt2/$packageId.manifest")
            assertTrue(
                "内置 RT2 manifest 缺席（先跑 generateBuiltinRt2Manifest）：${manifest.path}",
                manifest.isFile,
            )
            Files.write(root.resolve("mods").resolve("rt2").resolve(manifest.name), manifest.readBytes())
        }

        // 直打生产构造缝 defaultHost（不传 newHost），且 modProviders 直接取生产注册表：
        // 少了资产、包名对不上、声明 freeze 失败、载体 onLoad 抛异常，都会落成 active=false。
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = freshStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            modProviders = ModCarrierRegistry.providers(),
        )!!

        assertTrue(
            "生产注册表 + 内置资产必须让 ${FamilyDeclarations.PACKAGE_ID} 真装成功，" +
                "不能逐 mod fail-closed；实际日志=$logs",
            logs.any { it == "mod.install pkg=${FamilyDeclarations.PACKAGE_ID} active=true" },
        )
        assertNotNull("激活的 mod 必须有 context", session.host.contextOf(FamilyDeclarations.PACKAGE_ID))
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // TIME→phase 裁定：官方引擎没有「游戏相位」概念（反编译 C# 全库 phase / GameTime 零命中），
    // 它是 modapi 的移植侧扩展。没有权威映射可照抄，不发明映射；空相位是设计态。
    // ------------------------------------------------------------------

    @Test
    fun `空相位是裁定而非漏接 任务照跑且诊断记NO_PHASE`() {
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
        val mod = JobMod("daily", JobHandler { })
        val store = freshStore()
        store.writeInt("DAY", longArrayOf(0L), 7L)
        val session = attach(root = root, store = store, providers = listOf("mod.job" to mod))!!

        session.onTurnBoundary()

        assertEquals("空相位不得影响按游戏日的任务", 1, mod.fired)
        val phaseRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.PHASE }
        assertTrue("相位域必须留一条诊断（NO_PHASE 是设计态，不是静默旁路）", phaseRecords.isNotEmpty())
        assertTrue(
            "phase 恒空 ⇒ 全部相位记录为 SKIPPED / NO_PHASE / phase=null；" +
                "谁从 TIME 发明一个映射，这里立刻红：$phaseRecords",
            phaseRecords.all {
                it.phase == null && it.outcome == DiagnosticOutcome.SKIPPED && it.reasonCode == "NO_PHASE"
            },
        )
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 内置变量名总表真的喂进了声明事务：builtinVariableNames() 退化成空表时，
    // mod 就能声明自己的 DAY（第二套时间语义），而上面各族一律绿 —— 这一例是它的失败模式。
    // ------------------------------------------------------------------

    @Test
    fun `mod全局维度撞引擎内置名整包拒绝`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_day.manifest",
            ModManifest(packageId = "mod.day", globalDims = listOf(GlobalDimDecl("DAY"))),
        )
        try {
            attach(root = root, store = freshStore())
            fail("mod 侧全局维度撞内置 DAY 必须整包拒绝（P0-3：不许留第二套时间语义）")
        } catch (expected: DeclarationConflictException) {
            assertTrue(
                "拒绝理由必须点名撞的是 DAY：${expected.message}",
                expected.message?.contains("DAY") == true,
            )
        }
    }

    // ------------------------------------------------------------------
    // RT2 F 车 / MR-2b-2：生产装配序（唯一真桥 + store 双钩子 + 存档面 + 收口解绑）
    // 全部直打生产缝 ModRuntimeAssembly.attach（不传 newHost），不用自定义 host 自证。
    // ------------------------------------------------------------------

    /** 直打生产缝的开态装配（重复形状收敛到一个 helper，避免各例漏传 newHost）。 */
    private fun productionAttach(root: Path, store: VariableStore, enabled: Boolean = true) =
        ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = enabled,
            logSink = { line -> logs += line },
        )

    /** 三行原生角色（M5-2 真桥的 ADDVOIDCHARA 尾增基线）。 */
    private fun seedNativeRows(store: VariableStore) {
        repeat(3) { i ->
            val no = (i + 1).toLong()
            store.registerTemplate(CharacterTemplate(no = no, name = "角色$no", csvNo = no))
            store.addCharacter(no)
        }
    }

    @Test
    fun `生产装配序源码面 真桥唯一且空投射器已删`() {
        val assembly = sourceOf("ModRuntimeAssembly.kt")
        assertEquals(
            "开态只允许构造一份真投射桥（host / store / SaveService 共用同一实例）",
            1,
            assembly.split("RealCharaSlotProjector(").size - 1,
        )
        assertTrue(
            "C 车的空投射器许口实现必须从生产树删除",
            !assembly.contains("EmptyCharaSlotProjector"),
        )
        assertTrue("必须按 M5-2 装配序回填 store 钩子", assembly.contains("store.charaSlotProjector = projector"))
        assertTrue("必须显式后期装配 resolver", assembly.contains("store.bindCharaIndexResolver(charaIndexResolver)"))
        assertTrue("收口必须按实例解绑", assembly.contains("store.unbindCharaIndexResolver(charaIndexResolver)"))
        assertTrue("生产 defaultHost 必须传真桥", assembly.contains("projector = inputs.projector"))
        assertTrue(
            "SaveService 构造块必须传同一份真桥",
            assembly.contains("projector = projector,\n            pkgRegistry = shortIdRegistry,"),
        )
        assertTrue(
            "SaveService 必须拿同一份虚拟 NO 注册表（不再是恒空段）",
            assembly.contains("virtualNoRegistry = virtualNos,"),
        )
    }

    @Test
    fun `生产缝装唯一真投射桥且关态对store零接触`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_roster.manifest", ModManifest(packageId = "mod.roster"))

        // 关态：总闸首查短路，store 侧两个钩子一次都不能被写。
        val closedStore = VariableStore()
        assertNull("关态必须返回 null（零接触）", productionAttach(root, closedStore, enabled = false))
        assertNull("关态不得回填 store 投射钩子", closedStore.charaSlotProjector)
        assertNull("关态不得注入 resolver", closedStore.charaIndexResolverOrNull)
        assertTrue("关态不得产生任何日志", logs.isEmpty())
        logs.clear()

        // 开态：直打生产缝。
        val store = VariableStore()
        val session = checkNotNull(productionAttach(root, store)) { "开态必须产出运行时" }
        assertTrue("生产缝必须构造真投射桥", session.charaSlotProjector is RealCharaSlotProjector)
        assertSame(
            "store 钩子、会话观察面必须是同一份真桥",
            session.charaSlotProjector,
            store.charaSlotProjector,
        )
        assertNotNull("生产缝必须真注入 resolver", store.charaIndexResolverOrNull)
        session.shutdown()
    }

    @Test
    fun `收口解绑store双钩子且二次shutdown幂等`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_plain.manifest", ModManifest(packageId = "mod.plain"))
        val store = VariableStore()
        seedNativeRows(store)
        val session = checkNotNull(productionAttach(root, store)) { "开态必须产出运行时" }
        val projector = session.charaSlotProjector
        val generationBefore = projector.rosterGeneration

        session.shutdown()
        assertNull("收口后 store 不得再挂投射桥", store.charaSlotProjector)
        assertNull("收口后 store 不得再挂 resolver", store.charaIndexResolverOrNull)
        session.shutdown()
        assertEquals("二次收口只留一行读数（幂等）", 1, logs.count { it.contains("mod.shutdown 完成") })

        // 解绑后删行：五个行操作接点零调用 ⇒ 真桥世代不得递增。
        store.deleteCharacter(0L)
        assertEquals(
            "解绑后行删除不得再回调投射桥（不留跨会话悬挂引用）",
            generationBefore,
            projector.rosterGeneration,
        )
    }

    @Test
    fun `存档侧车抓到真投射器的虚拟NO注册表段`() {
        val root = tempGameRoot()
        writeManifest(root, "mod_roster.manifest", ModManifest(packageId = "mod.roster"))
        val store = VariableStore()
        val session = checkNotNull(productionAttach(root, store)) { "开态必须产出运行时" }
        val wiring = checkNotNull(SidecarSaveWiring.of(store)) { "开态必须装 sidecar 接线" }
        val bytes = wiring.port.captureRt2Bytes(
            SidecarBinding(uniqueCode = 1L, scriptVersion = 1L, saveTextHash = 0),
            emptyList(),
            emptyList(),
        )
        val bundle = SidecarSaveBundle.decode(bytes)
        assertTrue(
            "真注册表 encode 后必须是非空段（C 车空投射器许口下恒空）",
            bundle.virtualNoSnapshot.isNotEmpty(),
        )
        assertTrue("实体图载荷必须经真内核导出", bundle.entityGraphPayload.isNotEmpty())
        session.shutdown()
    }
}
