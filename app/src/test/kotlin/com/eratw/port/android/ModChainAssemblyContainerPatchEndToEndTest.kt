package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.ContainerDescriptor
import com.eratw.port.core.patch.ContainerRole
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleContainer
import com.eratw.port.core.patch.ModuleContainerCodec
import com.eratw.port.core.patch.ModuleContainerFormat
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.RelocationEntry
import com.eratw.port.core.patch.RequiredFeatures
import com.eratw.port.core.patch.ZeroBasePools
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 四池容器补丁（`mods` 根目录下的 MODULE 态容器件）端到端验收
 * （CONTRACT-RT2-M2CODEC §1 判据 1 / 3 / 4）。
 *
 * 与 `ModChainAssemblySessionEndToEndTest` 的分工：那份钉的是 `mods/rt2` 下 ERTM1
 * 容器的 F3 覆写机制面；本文件钉的是**第二源**（四池容器补丁免 manifest 进同一条
 * Mod 链）以及两源共存。链路一律是「磁盘真字节 → 生产 [ModChainAssembly.assemble]
 * → [GameSession] 三表原子替换 → 真 VM 的可观察输出」，不进测试桩、不手搓假字节。
 *
 * 判据 1 走契约写明的**诚实降级阀**：冻结的 v3 四池容器格式只有五个标量字段的
 * ContainerDescriptor，没有覆盖声明通道，生产免 manifest 路对覆写容器 fail-closed；
 * 因此覆写行为断言降为核心单元级（`ModuleContainerAdapterTest` 的哨兵链路 +
 * 覆盖分派），本文件只交付**加性容器补丁端到端**。降级原因登记在交付报告 TODO(verify)。
 *
 * 容器字节全部经生产 [ModuleContainerCodec.encode] 写出，载荷是 [CodeBuilder] 造的
 * 真实零基线 Program 的 `.erbc` 序列化，四计数 / codeWords / relocations /
 * callTargets 同源派生（与 core-vm `ModuleContainerProgramFixture` 同一口径，
 * 但 app 测试源集不能引用 core-vm 测试夹具，故在本文件内同源重建，不复制链接语义）。
 */
class ModChainAssemblyContainerPatchEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
    )

    /** base：ENTRY@0 调 X@1；X 打印 BASE-X、置 111 返回（与 F3 e2e 同款）。 */
    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._CALL, 1, 0, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 1)
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("BASE-X"), 1, 0, 2)
        b.emit(Opcode._SET_INT, 0, b.constant(111L), 0, 3)
        b.emit(Opcode._RET, 0, 0, 0, 4)
        return b.build(entry)
    }

    /** 最小真会话：与生产 GameHost 同款形态（modChain = plan / 缺省旁路 null）。 */
    private fun session(root: File, plan: ModChainRuntimePlan?): GameSession = GameSession(
        gameRoot = root.toPath(),
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
        modChain = plan,
    )

    /** 加性模块：只有一个新函数，打印自身名字后返回（base 无同名函数）。 */
    private fun additiveProgram(name: String): Program {
        val b = CodeBuilder()
        b.declareFunction(name, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string(name), 1, 0, 0)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(0)
    }

    /**
     * 从真实零基线 Program 造四池 MODULE 容器字节：指令流、重定位表、callTargets、
     * 四计数与 poolBlob 全部同源自 Program，再交生产 codec 写出。
     */
    private fun containerBytes(packageId: String, program: Program): ByteArray {
        val words = program.codeSource.toIntArray()
        val callTargets = ArrayList<Int>()
        val relocations = ArrayList<RelocationEntry>()
        for (pc in 0 until program.instructionCount) {
            val w = pc * Instruction.WORDS
            val op = words[w]
            val reloc = OpcodeRelocation.of(op)
            for (slot in 0..2) {
                val category = reloc.at(slot)
                if (category == PoolRef.NONE) continue
                relocations += RelocationEntry(pc, op, slot, category)
                if (category == PoolRef.FUNCTION) callTargets += words[w + 1 + slot]
            }
        }
        val model = ModuleContainer(
            role = ContainerRole.MODULE,
            requiredFeatures = RequiredFeatures.REQUIRED_MASK,
            isaVersion = ModuleContainerFormat.ISA_VERSION,
            descriptor = ContainerDescriptor(
                packageId = packageId,
                artifactSha256 = "sha256-" + packageId,
            ),
            imports = emptyList(),
            callTargets = callTargets,
            relocations = relocations,
            exports = emptyList(),
            pools = ZeroBasePools(
                longCount = program.constantCount,
                stringCount = program.stringCount,
                exprCount = program.exprCount,
                statementCount = program.statementCount,
                baseMarker = 0,
                codeWords = words.toList(),
                poolBlob = ErbcWriter.encode(program).toList(),
            ),
        )
        return ModuleContainerCodec.encode(model)
    }

    /** 四池容器补丁落 `mods` 根目录，沿用点 patch 扩展名（裁定 1：免 manifest）。 */
    private fun writeContainerPatch(root: File, fileName: String, packageId: String, fnName: String) {
        val mods = File(root, "mods").apply { mkdirs() }
        File(mods, fileName).writeBytes(containerBytes(packageId, additiveProgram(fnName)))
    }

    /** ERTM1 第一源：容器 + 同 packageId manifest（单向配对纪律不变）。 */
    private fun writeErtm1(root: File, packageId: String, fnName: String) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        val cm = CompiledModule(
            packageId,
            additiveProgram(fnName),
            listOf(FuncMeta(index = 0, key = fnName)),
        )
        File(dir, "$packageId.erbm").writeBytes(ErbModuleContainer.encode(cm, allowSuperCalls = true))
        File(dir, "$packageId.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = packageId)),
        )
    }

    // ------------------------------------------------------------------
    // 判据 1（降级位）：加性容器补丁端到端
    // ------------------------------------------------------------------

    @Test
    fun `判据1降级_加性四池容器补丁经生产装配进真会话_新增函数可调且base零漂移`() {
        val root = tmp.newFolder("game")
        writeContainerPatch(root, "add.patch", "mod.patch", "MOD_NEW")

        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        assertFalse("纯加性容器补丁不构成覆盖", plan.hasOverrides)
        assertNull("无覆盖必须走直调快路径", plan.functionDispatch)
        assertTrue(
            "四池容器补丁的新增函数必须进 FunctionTable（免 manifest 进链）",
            plan.functionTable.all().any { it.key == "MOD_NEW" },
        )

        val s = session(root, plan)
        // 夹具的加性函数打印自身函数名（MOD_NEW），与两源同链用例同一口径。
        s.runtime.callFunction("MOD_NEW")
        assertEquals(listOf("MOD_NEW"), s.console.loggedLines())
        assertEquals("base 行为零漂移", 111L, s.runtime.execute())
        assertEquals(
            "新增函数与 base 两条打印都来自真 VM 的屏上输出",
            listOf("MOD_NEW", "BASE-X"),
            s.console.loggedLines(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：两源同链 + 负控
    // ------------------------------------------------------------------

    @Test
    fun `判据3_两源同链_ERTM1与四池容器件同链装配_两包函数均可调`() {
        val root = tmp.newFolder("game")
        writeErtm1(root, "mod.ertm", "ERTM_FN")
        writeContainerPatch(root, "patcher.patch", "mod.patch", "PATCH_FN")

        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        assertTrue("ERTM1 源的函数在链里", plan.functionTable.all().any { it.key == "ERTM_FN" })
        assertTrue("四池容器补丁源的函数在链里", plan.functionTable.all().any { it.key == "PATCH_FN" })

        val s = session(root, plan)
        s.runtime.callFunction("ERTM_FN")
        s.runtime.callFunction("PATCH_FN")
        assertEquals(listOf("ERTM_FN", "PATCH_FN"), s.console.loggedLines())
    }

    @Test
    fun `判据3负控_坏四池容器件整包拒_不半装不静默丢弃`() {
        val root = tmp.newFolder("game")
        val good = containerBytes("mod.bad", additiveProgram("BAD_FN"))
        val mods = File(root, "mods").apply { mkdirs() }
        // 保留可探测前缀（magic + version），截掉后续载荷 ⇒ decode 必失败。
        File(mods, "bad.patch").writeBytes(good.copyOf(ModuleContainerFormat.PREFIX_BYTES + 4))
        assertTrue(
            "截断件仍须被 probe 命中，否则测不到 fail-closed 分支",
            ModuleContainerFormat.probe(File(mods, "bad.patch").readBytes()) != null,
        )

        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas))
            fail("坏四池容器件必须 fail-closed 整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名坏档，实得：${e.message}", e.message!!.contains("坏档"))
        }
    }

    @Test
    fun `判据3负控_容器件packageId与ERTM1撞名_整包拒`() {
        val root = tmp.newFolder("game")
        writeErtm1(root, "mod.same", "ERTM_FN")
        writeContainerPatch(root, "same.patch", "mod.same", "PATCH_FN")

        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas))
            fail("免 manifest 容器件的 packageId 必须自持，撞名整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名撞名，实得：${e.message}", e.message!!.contains("撞名"))
        }
    }

    // ------------------------------------------------------------------
    // 判据 2 / 4：legacy 不被误收、关态旁路
    // ------------------------------------------------------------------

    @Test
    fun `判据2_loader跳过_容器件在场不进legacy装载且不炸整包`() {
        val root = tmp.newFolder("game")
        writeContainerPatch(root, "add.patch", "mod.patch", "MOD_NEW")
        val mods = File(root, "mods")

        val loaded = ModPatchLoader.load(
            mods.toPath(),
            baseProgram(),
            PatchArtifacts.sha256Hex(ByteArray(0)),
        )
        assertEquals(
            "容器件归 Mod 链消费，loader 必须整件跳过（不进 legacy 装载面）",
            0,
            loaded.loadedPatches.size,
        )
        assertTrue(
            "跳过是设计内分流，不该留 legacy 解析失败，实得：${loaded.failures}",
            loaded.failures.isEmpty(),
        )
        assertTrue(
            "跳过是设计内分流，不该留 legacy 警告，实得：${loaded.warnings}",
            loaded.warnings.isEmpty(),
        )

        // 行为面变化登记：改跳过后关态会话照常开（容器件不生效），不再炸整包回退。
        val closed = session(root, null)
        assertEquals(111L, closed.runtime.execute())
        assertEquals(listOf("BASE-X"), closed.console.loggedLines())
    }

    @Test
    fun `判据2_legacy文本头按字节不命中容器探测_装配面不误收`() {
        val legacyHeader = "erbc-patch 1\n".toByteArray()
        assertNull(
            "legacy 文本头首行是小写 erbc-patch，按字节比较永不可能等于大写 ERBM 或 ERBC",
            ModuleContainerFormat.probe(legacyHeader),
        )

        val root = tmp.newFolder("game")
        val mods = File(root, "mods").apply { mkdirs() }
        File(mods, "legacy.patch").writeBytes(legacyHeader)
        writeErtm1(root, "mod.ertm", "ERTM_FN")

        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        assertTrue("legacy 件在场不阻碍第一源正常装配", plan.functionTable.all().any { it.key == "ERTM_FN" })
        assertEquals("legacy 件没被当成第二源收进链", 1, plan.program.functions.count { it.name == "ERTM_FN" })
    }

    @Test
    fun `判据4_关态旁路_容器件在场时assemble空_会话行为逐位同base`() {
        val root = tmp.newFolder("game")
        writeContainerPatch(root, "add.patch", "mod.patch", "MOD_NEW")

        assertNull("关态两源收集面都不调", ModChainAssembly.assemble(ChainAssemblyInputs(root, false, baseProgram(), baseMetas)))
        val closed = session(root, null)
        assertEquals(111L, closed.runtime.execute())
        assertEquals(listOf("BASE-X"), closed.console.loggedLines())

        val plain = session(tmp.newFolder("plain"), null)
        assertEquals(111L, plain.runtime.execute())
        assertEquals(
            "关态（容器件在场但闸关）与纯 base 会话输出逐位一致",
            plain.console.loggedLines(),
            closed.console.loggedLines(),
        )
    }
}
