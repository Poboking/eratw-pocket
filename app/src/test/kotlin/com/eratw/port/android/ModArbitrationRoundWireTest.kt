package com.eratw.port.android

import com.eratw.port.core.diag.DiagnosticDomain
import com.eratw.port.core.diag.DiagnosticOutcome
import com.eratw.port.core.mod.DecisionReasonCodes
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.ActionCandidate
import com.eratw.port.modapi.ArchetypeDecl
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modlivingworld.CharacterDeclarations
import com.eratw.port.modlivingworld.CharacterMod
import com.eratw.port.modlivingworld.EventMod
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W2-ARDRV（CONTRACT-RT2-ARDRV）的 app 侧行为面：**生产仲裁驱动**——外轮相位触发 + 
 * decisionDriver 宿主缝。全部用例直打生产构造缝 `ModRuntimeAssembly.attach`（不传 newHost，
 * 决策管线随之真注入），与 [ModRuntimeAssemblyWireTest] 的生产缝用例同族。
 *
 * 判据对照（编号 = 契约 §1）：
 * - 判据 1：开态页边界外轮触发（每边界一次）+ 无候选域完全旁路 + 失败隔离进报告不卡死。
 * - 判据 2：decisionDriver 宿主注入（app 持实例）→ 外轮胜者 handler 执行期间嵌套轮同键
 *   候选吃 RESERVATION_CONFLICT（core DecisionPipelineTest 用例 12 同形状的生产缝 e2e）。
 * - 判据 3：生产真仲裁装配后仲裁参与不再降级（arbitrationDegraded=false、「决策管线未装配」
 *   WARN 不出现——旧口径「生产缺省不注入」自 W1-DSTATE 起翻转，本文件钉的是新形态；
 *   刷新清单见交付报告：旧断言在本仓 0 条，无既有断言需要改写）。
 * - 判据 4：外轮淘汰进 DECISION 诊断环且原因码可解释（REJECTED 上日志面 = 既有消费者缝）；
 *   RAND 独立性负控由 DecisionStateSaveWireTest 与 LivingWorldCombinationTest 既有用例维持。
 * - 判据 5：关态零接触（总闸短路 = 无会话 = 无外轮 = 无仲裁日志）。
 */
class ModArbitrationRoundWireTest {

    private val logs: MutableList<String> = mutableListOf()

    // ------------------------------------------------------------------
    // fixture：临时游戏根 + 生产缝装配
    // ------------------------------------------------------------------

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-ardrv-root")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun writeManifest(root: Path, fileName: String, manifest: ModManifest) {
        val bytes = com.eratw.port.core.patch.ManifestCodec.encode(
            com.eratw.port.core.mod.ModDeclarationAdapter.toDescriptor(manifest),
        )
        Files.write(root.resolve("mods").resolve("rt2").resolve(fileName), bytes)
    }

    /** 生产构造缝（不传 newHost = defaultHost 真管线注入；重复形状收敛到一处）。 */
    private fun attach(
        root: Path,
        store: VariableStore,
        providers: List<Pair<String, EraMod>>,
        enabled: Boolean = true,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root.toFile(),
        store = store,
        nativeRegistry = com.eratw.port.core.fn.NativeMethodRegistry(),
        onVmThread = { true },
        enabled = enabled,
        logSink = { line -> logs += line },
        modProviders = providers,
    )

    /**
     * 把生产注册表的内置 RT2 manifest 全量写进临时游戏根（ModCarrierRegistry 载体与磁盘
     * manifest 一一对应；缺一件 = 该包 fail-closed active=false，`生产构造缝`用例同款形状）。
     */
    private fun writeBuiltinManifests(root: Path) {
        for (packageId in ModCarrierRegistry.providers().map { it.first }) {
            val manifest = File("src/main/assets/mods/rt2/$packageId.manifest")
            assertTrue(
                "内置 RT2 manifest 缺席（先跑 generateBuiltinRt2Manifest）：${manifest.path}",
                manifest.isFile,
            )
            Files.write(root.resolve("mods").resolve("rt2").resolve(manifest.name), manifest.readBytes())
        }
    }

    /**
     * 傍晚域 mod（判据 1/4 的载体）：onLoad 注册一个候选 + 处理器；开关位供旁路 / 炸点
     * 形态切换。actor 用裸 EntityId——管线不校验 actor 存在性（handler 解析只看注册表）。
     */
    private class EveningMod : EraMod {
        var executed: Int = 0
        var provideEmpty: Boolean = false
        var throwInHandler: Boolean = false

        override fun onLoad(context: ModContext) {
            context.actions.provide(CharacterDeclarations.DOMAIN_EVENING) { _ ->
                if (provideEmpty) {
                    emptyList()
                } else {
                    listOf(
                        ActionCandidate(
                            providerPackage = PKG,
                            candidateKey = "ev.k1",
                            actor = com.eratw.port.modapi.EntityId(1L),
                            weight = 1,
                            handlerKey = "ev.handler",
                        ),
                    )
                }
            }
            context.actions.on("ev.handler") {
                executed++
                if (throwInHandler) error("傍晚 handler 炸点（失败隔离演示）")
            }
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}

        companion object {
            const val PKG: String = "mod.evening"
        }
    }

    /**
     * 分层傍晚域 mod（判据 4 的载体）：三个候选——高层胜者、低层（吃 PRIORITY_LOST）、
     * 带未注册约束键的高层（吃 CONSTRAINT_FALSE）——淘汰原因码逐条进 DECISION 诊断环。
     */
    private class RankedEveningMod : EraMod {
        var executed: Int = 0

        override fun onLoad(context: ModContext) {
            context.actions.provide(CharacterDeclarations.DOMAIN_EVENING) { _ ->
                listOf(
                    ActionCandidate(
                        providerPackage = PKG,
                        candidateKey = "rank.winner",
                        actor = com.eratw.port.modapi.EntityId(1L),
                        priority = 1,
                        weight = 1,
                        handlerKey = "rank.handler",
                    ),
                    ActionCandidate(
                        providerPackage = PKG,
                        candidateKey = "rank.ghost",
                        actor = com.eratw.port.modapi.EntityId(1L),
                        priority = 1,
                        weight = 1,
                        constraints = listOf("rank.unregistered"),
                        handlerKey = "rank.handler",
                    ),
                    ActionCandidate(
                        providerPackage = PKG,
                        candidateKey = "rank.lowtier",
                        actor = com.eratw.port.modapi.EntityId(1L),
                        priority = 0,
                        weight = 1,
                        handlerKey = "rank.handler",
                    ),
                )
            }
            context.actions.on("rank.handler") { executed++ }
        }

        override fun onEnable() {}
        override fun onDisable() {}
        override fun onUnload() {}

        companion object {
            const val PKG: String = "mod.ranked"
        }
    }

    private fun rankedManifest(root: Path) = writeManifest(
        root,
        "mod_ranked.manifest",
        ModManifest(packageId = RankedEveningMod.PKG, archetypes = listOf(ArchetypeDecl("mod.ranked.trait"))),
    )

    private fun arbitrationLines(): List<String> = logs.filter { it.contains("mod.arbitration") }

    // ------------------------------------------------------------------
    // 判据 1：开态页边界外轮触发 + 无候选域完全旁路
    // ------------------------------------------------------------------

    @Test
    fun `判据1 开态页边界外轮真执行 每边界一次且无候选域完全旁路`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_evening.manifest",
            ModManifest(packageId = EveningMod.PKG, archetypes = listOf(ArchetypeDecl("mod.evening.trait"))),
        )
        val mod = EveningMod()
        val session = attach(root, VariableStore(), listOf(EveningMod.PKG to mod))!!

        session.onTurnBoundary()

        assertEquals("外轮胜者 handler 真执行", 1, mod.executed)
        val completed = arbitrationLines().single {
            it.contains("LW_EVENING") && it.contains("outcome=COMPLETED")
        }
        assertTrue("读数点名胜者归属与候选键：$completed", completed.contains("pkg=${EveningMod.PKG}"))
        assertTrue(completed.contains("chosen=ev.k1"))
        // DECISION 域诊断环：终局 OK 记录带 domain 键（管线既有消费者缝已接，判据 4 的对照面）。
        val decisionRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.DECISION }
        assertTrue(decisionRecords.any { it.outcome == DiagnosticOutcome.OK && it.key == "LW_EVENING" })

        // 无候选旁路：provider 转空表 ⇒ 该拍不执行、不留仲裁读数行（管线 NONE 终局静默）。
        val completedLinesBefore = arbitrationLines().count { it.contains("outcome=COMPLETED") }
        mod.provideEmpty = true
        session.onTurnBoundary()
        assertEquals("无候选 = 处理器零执行", 1, mod.executed)
        assertEquals(
            "无候选拍不留新读数行（完全旁路）",
            completedLinesBefore,
            arbitrationLines().count { it.contains("outcome=COMPLETED") },
        )
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 1 失败隔离：handler 异常进报告上日志面，边界不中断、下一拍照常
    // ------------------------------------------------------------------

    @Test
    fun `判据1失败隔离 handler异常进报告不卡死 下一拍照常`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_evening.manifest",
            ModManifest(packageId = EveningMod.PKG, archetypes = listOf(ArchetypeDecl("mod.evening.trait"))),
        )
        val mod = EveningMod()
        val session = attach(root, VariableStore(), listOf(EveningMod.PKG to mod))!!

        mod.throwInHandler = true
        session.onTurnBoundary()

        assertEquals("handler 抛异常 = 执行尝试已发生（失败也计数，防重试死循环）", 1, mod.executed)
        assertTrue(
            "宿主必须消费报告 failures 上日志面（§21.3 宿主报告义务）：${arbitrationLines()}",
            arbitrationLines().any {
                it.contains("失败已隔离") && it.contains("pkg=${EveningMod.PKG}") && it.contains("IllegalStateException")
            },
        )
        assertFalse(
            "失败隔离必须挡在仲裁缝内，不得漏到页边界兜底（mod.tick 边界异常）",
            logs.any { it.contains("mod.tick 边界异常已隔离") },
        )
        // 不卡死：下一拍关掉炸点，外轮照常出胜者。
        mod.throwInHandler = false
        session.onTurnBoundary()
        assertTrue(
            "下一拍外轮照常（§21.3 失败隔离不卡死）",
            arbitrationLines().any { it.contains("outcome=COMPLETED") },
        )
        // 诊断环：FAILED 终局记录（管线 DECISION 域，HANDLER_FAILED 原因码）。
        val decisionRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.DECISION }
        assertTrue(decisionRecords.any { it.outcome == DiagnosticOutcome.FAILED })
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 2：decisionDriver 宿主注入 → 嵌套轮 RESERVATION_CONFLICT 生产缝 e2e 可达
    // ------------------------------------------------------------------

    @Test
    fun `判据2 decisionDriver宿主注入 外轮胜者handler期间嵌套轮同键吃RESERVATION_CONFLICT`() {
        val root = tempGameRoot()
        writeBuiltinManifests(root)
        val providers = ModCarrierRegistry.providers()
        val character = providers.mapNotNull { it.second as? CharacterMod }.single()
        val session = attach(root, VariableStore(), providers)!!

        assertNotNull("attach 必须给载体实例注入宿主 driver（判据 2 注入点）", character.decisionDriver)
        session.onTurnBoundary()

        val npcs = character.queries.npcs()
        assertEquals("演示阵容两名 NPC（日程槽 JOB_DAY_TICK 已在本拍先跑）", 2, npcs.size)
        val crafters = npcs.filter {
            character.queries.activityOf(it) == CharacterDeclarations.ACT_CRAFT
        }
        val strollers = npcs.filter {
            character.queries.activityOf(it) == CharacterDeclarations.ACT_STROLL
        }
        assertEquals("工坊恰被一人占用（外轮胜者）", 1, crafters.size)
        assertEquals(
            "另一人经嵌套轮回落散步（同键候选吃 RESERVATION_CONFLICT 后无预留候选胜出）",
            1, strollers.size,
        )
        // 嵌套轮读数在宿主日志面（报告由宿主自行消费）：外轮 + 嵌套轮各一行 COMPLETED。
        val completed = arbitrationLines().filter { it.contains("LW_EVENING") && it.contains("outcome=COMPLETED") }
        assertEquals("外轮 + 嵌套轮两行读数", 2, completed.size)
        // 引擎唯一给出 RESERVATION_CONFLICT 的路径：DECISION 诊断环带包归属的淘汰记录。
        val decisionRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.DECISION }
        val conflict = decisionRecords.single { it.reasonCode == DecisionReasonCodes.RESERVATION_CONFLICT }
        assertEquals(CharacterDeclarations.PACKAGE_ID, conflict.packageId)
        assertEquals("LW_EVENING", conflict.key)
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 3：生产真仲裁装配后仲裁参与不再降级（新形态；旧断言 0 条，刷新清单见交付）
    // ------------------------------------------------------------------

    @Test
    fun `判据3 生产真仲裁装配后仲裁参与不再降级且收口清缝`() {
        val root = tempGameRoot()
        writeBuiltinManifests(root)
        val providers = ModCarrierRegistry.providers()
        val character = providers.mapNotNull { it.second as? CharacterMod }.single()
        val event = providers.mapNotNull { it.second as? EventMod }.single()

        val session = attach(root, VariableStore(), providers)!!

        assertFalse("角色包仲裁参与必须已注册（W1-DSTATE 真管线注入 + 本波外轮）", character.arbitrationDegraded)
        assertFalse("事件包仲裁参与必须已注册", event.arbitrationDegraded)
        assertTrue(
            "「决策管线未装配」WARN 不得再出现在生产装载面（降级不是常态）",
            logs.none { it.contains("决策管线未装配") },
        )
        session.shutdown()
        assertNull("收口随 onUnload 清 driver 缝（CharacterMod.onUnload 既有生命周期）", character.decisionDriver)
    }

    // ------------------------------------------------------------------
    // 判据 4：外轮淘汰进 DECISION 诊断环且原因码可解释（REJECTED 上日志面）
    // ------------------------------------------------------------------

    @Test
    fun `判据4 外轮淘汰逐条进DECISION诊断环 原因码带包归属可解释`() {
        val root = tempGameRoot()
        rankedManifest(root)
        val mod = RankedEveningMod()
        val session = attach(root, VariableStore(), listOf(RankedEveningMod.PKG to mod))!!

        session.onTurnBoundary()

        assertEquals("高层胜者执行恰一次", 1, mod.executed)
        val decisionRecords = session.diagnostics.snapshot().filter { it.domain == DiagnosticDomain.DECISION }
        val rejected = decisionRecords.filter { it.outcome == DiagnosticOutcome.REJECTED }
        assertTrue(
            "未注册约束的候选吃 CONSTRAINT_FALSE（fail-closed 不静默）：$rejected",
            rejected.any {
                it.packageId == RankedEveningMod.PKG && it.reasonCode == DecisionReasonCodes.CONSTRAINT_FALSE
            },
        )
        assertTrue(
            "低层候选吃 PRIORITY_LOST（分层裁决可解释）：$rejected",
            rejected.any {
                it.packageId == RankedEveningMod.PKG && it.reasonCode == DecisionReasonCodes.PRIORITY_LOST
            },
        )
        // REJECTED 逐条上日志面 = 既有消费者缝（P2-3 诊断读者）对外轮的真实覆盖。
        assertTrue(
            "诊断读者把外轮 REJECTED 送进日志面",
            logs.any { it.contains("mod.diag") && it.contains("REJECTED") && it.contains("PRIORITY_LOST") },
        )
        session.shutdown()
    }

    // ------------------------------------------------------------------
    // 判据 5：关态零接触——总闸短路 = 无会话 = 无外轮 = 无仲裁日志
    // ------------------------------------------------------------------

    @Test
    fun `判据5 关态外轮缝零接触`() {
        val root = tempGameRoot()
        writeManifest(
            root,
            "mod_evening.manifest",
            ModManifest(packageId = EveningMod.PKG, archetypes = listOf(ArchetypeDecl("mod.evening.trait"))),
        )
        val mod = EveningMod()
        val session = attach(root, VariableStore(), listOf(EveningMod.PKG to mod), enabled = false)

        assertNull("总闸关 = 装配链一次都不进（无会话即无外轮）", session)
        assertTrue("关态不留任何仲裁日志", arbitrationLines().isEmpty())
        assertEquals("关态 mod 一个回调都没跑", 0, mod.executed)
    }
}
