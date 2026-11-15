package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.patch.PatchHeader
import com.eratw.port.core.patch.patchBaseIdentity
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/**
 * RT2 F3 波（wave/rt2-f3）：端到端口上覆写验收的**机制面** —— 磁盘真 `.erbm` 容器经
 * 生产 [ModChainAssembly.assemble] 装配 → `GameSession(modChain = plan)` 三表原子替换 →
 * 真 VM 执行 → 屏上可观察输出（`session.console.loggedLines()`，等价录制模式：PRINT 文本
 * 经生产 consoleBridge 落控制台 sink，不经任何测试桩）。
 *
 * 既有测试三处断链，本文件补位（契约 §0）：
 * - `ModChainAssemblyChainTest` 止步 plan 值对象（无 GameSession / VM / PRINT）；
 * - `ModChainSessionWireTest` 模块是内存 TestModule、无 VM 执行、无 PRINT 断言；
 * - `ModChainRuntimePlanTest` 真 VM 但断返回值（RecordingHost.print = Unit）、模块非磁盘件。
 *
 * 契约 §1 八条编号判据逐条一例（判据名见各方法名前缀）：
 * 1. 覆写生效（Mod 版本内容上屏 + base 返回值不再可达）；
 * 2. CALL SUPER 回 base（报红已修复复绿，见下「报红与修复」节）；
 * 3. 新增函数可调（纯加性 hasOverrides=false ⇒ 会话 dispatch null 直调快路径）；
 * 4. 随包真资产过链（`assets/mods/rt2` 真文件 + artifact-sha256 对账 + 开/关态对照）；
 * 5. 关态/空表旁路（enabled=false ⇒ assemble null ⇒ 会话行为逐位同 base）；
 * 6. modChain × 显式 functionDispatch 构造期互斥（GameSession.kt:826-828）；
 * 7. modChain × 非空 patchLoad 构造期互斥（GameSession.kt:823-825）；
 * 8. 配对 fail-closed（容器在场 manifest 缺失 ⇒ 整包拒，ModChainAssembly.kt:177-185）。
 *
 * ## 报红与修复（2026-11-13 报红，同日 wave/rt2-superfix 修复复绿）
 *
 * 判据 2 曾现态红（报红升级 P0 车道 `wave/rt2-superfix`），根因 =
 * **`.erbm` 容器承载不了 super 哨兵**：生产编译档把 `CALL SUPER` 编成 `_CALL`
 * 哨兵操作数（ErbProgramCompiler.kt:3197-3206，`CALL_SUPER_SENTINEL = Int.MIN_VALUE`，
 * CallSentinels 定义见 ModuleDescriptor.kt:339-364），容器直存 Program 字节
 * （ErbModuleContainer.kt:100 `ErbcWriter.encode` 原样写词），而装载侧
 * `ErbcReader.validate` 对 `_CALL` 的判据是 `a < 0` 即拒 ⇒ 覆写体内带 `CALL SUPER`
 * 的 Mod 包在 `assemble` 装载期即 `ErbcDecodeException` 整包拒，D-3 kojo 覆盖契约的
 * super 链语义无法经 `.erbm` 落地。既有测试当时全部绕开了这一步。
 *
 * **已修复（wave/rt2-superfix）**：根因层 = 装载/编译相位 —— 两个设计哨兵值是
 * 一等公民（编码侧按设计产出、链接期 `CallRelocation` 按设计还原、四池容器闸
 * `ModuleContainer.kt:623` 已显式放行），断点恰在三处装载闸判据漏收。
 * 修复 = 判据收窄为「仅 `CALL_SUPER_SENTINEL` / `TRY_CALL_SUPER_SENTINEL` 精确放行，
 * 其余负值照拒」：`ErbcReader.validate` 与 `ErbcStream` 镜像闸同判据（报错文案
 * 逐字一致不变）、specs/erbc-format.md §5.6 规约行同步；负控与容器语境端到端
 * 钉板见 `ErbcStreamBaseTest` 第 6 节与 core-vm `ErbModuleContainerSuperChainTest`。
 * 判据 2 摘 `@Ignore` 复绿即判据达成；判据 1 / 5 / 6 / 7 / 8 的覆写夹具保持
 * **不含 super** 的覆写体（覆写分派语义不受影响）。
 *
 * 夹具纪律（契约 §1 / §23.5 第 11 条）：容器走真 `ErbModuleContainer.encode`、manifest 走真
 * `ManifestCodec.encode`、base 签名目录走真尾读解析（照 `ModChainAssemblyChainTest` 的
 * writeContainer / writeManifest / writeMeta 既有模式）；R7 补丁照 `ModPatchLoaderTest` 的
 * 落盘字节模式。禁止自造 fake 引擎自证；断言全部走真 VM 的可观察输出。
 */
class ModChainAssemblySessionEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 夹具：base（ENTRY@0 调 X@1；X 打印 BASE-X、置 111 并返回）
    // ------------------------------------------------------------------

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "X"),
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
        return b.build(entry)
    }

    /** 最小真会话：与生产 GameHost 同款三参形态（modChain = plan / 缺省旁路 null）。 */
    private fun session(root: File, plan: ModChainRuntimePlan?): GameSession = GameSession(
        gameRoot = root.toPath(),
        program = baseProgram(),
        metadata = BootMetadata(functions = baseMetas),
        modChain = plan,
    )

    // ------------------------------------------------------------------
    // 夹具：覆写模块（X 覆写；有 / 无 super 两版，见类 KDoc 报红节）
    // ------------------------------------------------------------------

    /** X（无参、int 返回通道）的签名规范串；容器侧与 base 侧车同源这一条串。 */
    private val xCanonical = "SIG1|ret:I"

    private val xFingerprint = BaseSignatureCatalog.fingerprint(xCanonical)

    private fun overrideXProgram(withSuper: Boolean): Program {
        val b = CodeBuilder()
        b.declareFunction("X", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("MOD-X"), 1, 0, 0)
        if (withSuper) {
            b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
            b.emit(Opcode._PRINT_INT, 0, 1, 0, 0)
        } else {
            b.emit(Opcode._SET_INT, 0, b.constant(222L), 0, 0)
        }
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(0)
    }

    private fun overrideModule(withSuper: Boolean): CompiledModule = CompiledModule(
        "mod.ovr",
        overrideXProgram(withSuper),
        listOf(FuncMeta(index = 0, key = "X")),
        mapOf("X" to xFingerprint),
    )

    private fun writeContainer(root: File, fileName: String, cm: CompiledModule) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, fileName).writeBytes(ErbModuleContainer.encode(cm, allowSuperCalls = true))
    }

    private fun writeManifest(root: File, fileName: String, d: ModuleDescriptor) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, fileName).writeBytes(ManifestCodec.encode(d))
    }

    /** base 侧车：`end` 行 + 尾部签名段（有界尾读消费的正是这段）。 */
    private fun writeMeta(root: File, signatureRecords: List<String>) {
        val meta = File(root, "erbc.v1/program.meta").apply { parentFile?.mkdirs() }
        meta.writeText(
            "end\nsignatures ${signatureRecords.size}\n" +
                signatureRecords.joinToString("") { "signature $it\n" },
        )
    }

    /** 覆写套件：同 packageId manifest + 容器 + 带签名段侧车，一次落齐。 */
    private fun writeOverrideFixture(root: File, withSuper: Boolean = false) {
        writeManifest(
            root,
            "mod.ovr.manifest",
            ModuleDescriptor(packageId = "mod.ovr", overrideFunctions = setOf("X")),
        )
        writeContainer(root, "mod.ovr.erbm", overrideModule(withSuper))
        writeMeta(root, listOf("X $xCanonical"))
    }

    // ------------------------------------------------------------------
    // 夹具：加性模块（MOD_NEW 打印 MOD-NEW，base 无同名函数）
    // ------------------------------------------------------------------

    private fun additiveModule(): CompiledModule {
        val b = CodeBuilder()
        b.declareFunction("MOD_NEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("MOD-NEW"), 1, 0, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return CompiledModule(
            "mod.add",
            b.build(0),
            listOf(FuncMeta(index = 0, key = "MOD_NEW")),
        )
    }

    // ------------------------------------------------------------------
    // 夹具：R7 真 patch 落盘（照 ModPatchLoaderTest 模式；判据 7 用）
    // ------------------------------------------------------------------

    private val patchSha = PatchArtifacts.sha256Hex(ByteArray(0))

    private fun writePatch(modsDir: File, base: Program) {
        val b = CodeBuilder(
            codeBase = base.instructionCount * Instruction.WORDS,
            statementBase = base.statementCount,
            stringBase = base.stringCount,
            longBase = base.constantCount,
            exprBase = base.exprCount,
            functionBase = base.functions.size,
        )
        val idx = b.declareFunction("PATCH_0", b.here(), arity = 0, slotCount = 2)
        b.emit(Opcode._PRINT_LITERAL, b.string("PATCH-0"), 1, 0, 101)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 102)
        val patchProgram = b.build(idx)
        val body = ErbcWriter.encode(patchProgram)
        val id = base.patchBaseIdentity(patchSha)
        val header = PatchHeader(
            baseInstructionCount = id.instructionCount,
            baseFunctionCount = id.functionCount,
            baseStatementCount = id.statementCount,
            baseStringCount = id.stringCount,
            baseLongCount = id.longCount,
            baseExprCount = id.exprCount,
            baseSha256 = patchSha,
            compiler = PatchArtifacts.compilerSummary(),
            patchFunctionCount = patchProgram.functions.size,
            patchStatementCount = patchProgram.statementCount - base.statementCount,
            patchStringCount = patchProgram.stringCount - base.stringCount,
            patchLongCount = patchProgram.constantCount - base.constantCount,
            patchExprCount = patchProgram.exprCount - base.exprCount,
            entryFunction = patchProgram.functions.first().name,
        )
        val patchFile = modsDir.resolve("kojo${ModPatchLoader.PATCH_EXTENSION}")
        Files.write(patchFile.toPath(), PatchArtifacts.of(header, body).write())
    }

    // ------------------------------------------------------------------
    // 夹具：随包真资产（契约判据 4：读真文件，不复制不重造字节）
    // ------------------------------------------------------------------

    /** 仓库真实资产目录：候选按 Gradle 测试工作目录的三种落位逐个试（照 ModChainAssetPairTest）。 */
    private fun repoAssetDir(): File {
        val candidates = listOf(
            File("src/main/assets/mods/rt2"),
            File("../app/src/main/assets/mods/rt2"),
            File("../../app/src/main/assets/mods/rt2"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到仓库 rt2 资产目录（试了 ${candidates.joinToString { it.path }}）")
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    // ------------------------------------------------------------------
    // 判据 1：覆写生效 —— 磁盘真容器 → 生产装配 → 会话 → 真 VM → Mod 版本内容上屏
    // ------------------------------------------------------------------

    @Test
    fun `判据1_覆写生效_磁盘容器经生产装配进会话真执行输出Mod版本`() {
        val root = tmp.newFolder("game")
        writeOverrideFixture(root, withSuper = false)
        val base = baseProgram()
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, baseMetas)))
        assertTrue("声明覆盖 + 签名一致 ⇒ hasOverrides", plan.hasOverrides)
        val dispatch = requireNotNull(plan.functionDispatch)
        assertTrue("覆盖后 dispatch 非恒等", dispatch.indices.any { dispatch[it] != it })

        val s = session(root, plan)
        assertSame(
            "进 VM 的 Program 必须是链接产物（三表原子替换的端到端证据）",
            plan.program,
            s.runtime.program,
        )
        val result = s.runtime.execute()

        assertEquals(
            "覆写版本必须是屏上唯一内容（base 的 PRINT 不再可达）",
            listOf("MOD-X"),
            s.console.loggedLines(),
        )
        assertEquals("覆写体的返回值经分派链流回根", 222L, result)
    }

    // ------------------------------------------------------------------
    // 判据 2：CALL SUPER —— base 值经 Mod 体内 SUPER 调用可达（契约原样夹具）
    // ------------------------------------------------------------------

    /**
     * 判据 2（已复绿，2026-11-13 wave/rt2-superfix）：覆写体内的 `CALL SUPER` 哨兵
     * 过 `.erbm` 容器装载（装载闸收窄为「仅 `CallSentinels` 两个设计哨兵值精确放行，
     * 其余负值照拒」，`ErbcReader.validate` 与 `ErbcStream` 镜像闸同判据），装配期
     * `CallRelocation` 把哨兵还原为 base 前实现物理下标并置 DIRECT 位，真 VM 执行
     * 出「Mod 体 → SUPER 回 base → base 返回值」全链。修复前曾现态红
     * （`ErbcDecodeException`：pc 的 `_CALL` 引用函数下标 -2147483648，`assemble`
     * 即整包拒）并挂 `@Ignore` 保 main 闸绿，见类 KDoc「报红与修复」节。
     */
    @Test
    fun `判据2_CALL_SUPER经会话回base_base行为输出与base返回值可达`() {
        val root = tmp.newFolder("game")
        writeOverrideFixture(root, withSuper = true)
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        val s = session(root, plan)

        val result = s.runtime.execute()

        assertEquals(
            "Mod 体先打印，SUPER 回 base 打印 BASE-X，base 返回值 111 再由 Mod 体打印",
            listOf("MOD-X", "BASE-X", "111"),
            s.console.loggedLines(),
        )
        assertEquals("base X 的返回值经 SUPER 链流回根", 111L, result)
    }

    // ------------------------------------------------------------------
    // 判据 3：新增函数可调 —— 纯加性 hasOverrides=false，会话 dispatch null 直调快路径
    // ------------------------------------------------------------------

    @Test
    fun `判据3_新增函数可调_纯加性hasOverrides假_会话走直调快路径`() {
        val root = tmp.newFolder("game")
        writeManifest(root, "mod.add.manifest", ModuleDescriptor(packageId = "mod.add"))
        writeContainer(root, "mod.add.erbm", additiveModule())
        // 刻意不写侧车：加性模块无覆盖，签名目录恒空不进闸（侧车缺失不拦加性）。
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        assertFalse("只加新函数不构成覆盖", plan.hasOverrides)
        assertNull("无覆盖必须走直调快路径（性能闸）", plan.functionDispatch)

        val s = session(root, plan)
        assertNull("HAS_OVERRIDES 消费点：无覆盖 ⇒ 会话不把 dispatch 交给 VM", s.runtime.functionDispatch)
        s.runtime.callFunction("MOD_NEW")
        assertEquals("模块新增函数经会话真执行", listOf("MOD-NEW"), s.console.loggedLines())
        s.runtime.execute()
        assertEquals(
            "base 行为在加性模块在场时零漂移",
            listOf("MOD-NEW", "BASE-X"),
            s.console.loggedLines(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 4：随包真资产过链 —— 仓库真文件 + 摘要对账 + 开态非旁路 / 关态对照 null
    // ------------------------------------------------------------------

    @Test
    fun `判据4_随包真资产过链_开态非旁路_关态对照null_manifest摘要对账`() {
        val root = tmp.newFolder("game")
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        val erbm = File(repoAssetDir(), "eratw.demo.erbm")
        val manifest = File(repoAssetDir(), "eratw.demo.manifest")
        assertTrue("随包两件套必须在仓库 assets 里", erbm.isFile && manifest.isFile)
        erbm.copyTo(File(rt2, erbm.name))
        manifest.copyTo(File(rt2, manifest.name))

        val d = ManifestCodec.decode(manifest.readBytes())
        assertEquals("eratw.demo", d.packageId)
        assertEquals(
            "manifest 钉的 artifact-sha256 必须与容器实际字节摘要一致",
            sha256Hex(erbm.readBytes()),
            d.artifactSha256,
        )

        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        assertFalse("demo 是加性模块", plan.hasOverrides)
        assertTrue(
            "链里必须有 demo 的函数",
            plan.functionTable.all().any { it.key == "ERATW_DEMO_PING" },
        )
        val s = session(root, plan)
        assertEquals("随包真资产经会话真执行（RETURN 1）", 1L, s.runtime.callFunction("ERATW_DEMO_PING"))

        val closedPlan = ModChainAssembly.assemble(ChainAssemblyInputs(root, false, baseProgram(), baseMetas))
        assertNull("关态对照：enabled=false ⇒ assemble null", closedPlan)
        val closed = session(root, null)
        assertNull("关态会话无 modChain ⇒ demo 函数不在表里", closed.runtime.tryCall("ERATW_DEMO_PING"))
    }

    // ------------------------------------------------------------------
    // 判据 5：关态/空表旁路 —— assemble null ⇒ 会话行为逐位同 base（端到端对照）
    // ------------------------------------------------------------------

    @Test
    fun `判据5_关态旁路_assemble为null_会话行为逐位同base`() {
        val root = tmp.newFolder("game")
        writeOverrideFixture(root, withSuper = false) // 盘上覆写资产齐全，但总闸关
        assertNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, false, baseProgram(), baseMetas)))

        val closed = session(root, null)
        val result = closed.runtime.execute()
        assertEquals("关态会话跑的就是 base 行为", 111L, result)
        assertEquals(listOf("BASE-X"), closed.console.loggedLines())

        val plain = session(tmp.newFolder("plain"), null)
        assertEquals(111L, plain.runtime.execute())
        assertEquals(
            "关态（资产在场但闸关）与纯 base 会话输出逐位一致",
            plain.console.loggedLines(),
            closed.console.loggedLines(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 6：modChain × 显式 functionDispatch 构造期互斥（GameSession.kt:826-828）
    // ------------------------------------------------------------------

    @Test
    fun `判据6_modChain与显式functionDispatch同传_构造期互斥拒`() {
        val root = tmp.newFolder("game")
        writeOverrideFixture(root, withSuper = false)
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas)))
        try {
            GameSession(
                gameRoot = root.toPath(),
                program = baseProgram(),
                metadata = BootMetadata(functions = baseMetas),
                functionDispatch = IntArray(plan.program.functions.size) { it },
                modChain = plan,
            )
            fail("modChain 与显式 functionDispatch 共存必须构造期整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名互斥，实得：${e.message}", e.message!!.contains("互斥"))
        }
    }

    // ------------------------------------------------------------------
    // 判据 7：modChain × 非空 patchLoad 构造期互斥（GameSession.kt:823-825，R7 真装载）
    // ------------------------------------------------------------------

    @Test
    fun `判据7_modChain与非空patchLoad同传_构造期整包拒`() {
        val root = tmp.newFolder("game")
        writeOverrideFixture(root, withSuper = false)
        val base = baseProgram()
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, baseMetas)))
        val modsDir = File(root, "mods").apply { mkdirs() }
        writePatch(modsDir, base)

        // 夹具有效性前提：patch 真能装载（loadedPatches 非空 = 「非空 patchLoad」成立）。
        val loaded = ModPatchLoader.load(modsDir.toPath(), base, patchSha)
        assertEquals("夹具 patch 必须装载成功", 1, loaded.loadedPatches.size.toLong())

        try {
            GameSession(
                gameRoot = root.toPath(),
                program = base,
                metadata = BootMetadata(functions = baseMetas),
                modsDirectory = modsDir.toPath(),
                baseProgramSha256 = patchSha,
                modChain = plan,
            )
            fail("modChain 与非空 patchLoad 共存必须构造期整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名两套替换源共存，实得：${e.message}", e.message!!.contains("共存"))
        }
    }

    // ------------------------------------------------------------------
    // 判据 8：配对 fail-closed —— 容器在场 manifest 缺失 ⇒ 端到端语境整包拒
    // ------------------------------------------------------------------

    @Test
    fun `判据8_配对failclosed_容器在场manifest缺失_端到端整包拒`() {
        val root = tmp.newFolder("game")
        writeContainer(root, "mod.ovr.erbm", overrideModule(withSuper = false))
        writeMeta(root, listOf("X $xCanonical")) // 其余语境齐全，唯独缺 manifest
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, baseProgram(), baseMetas))
            fail("容器在场但同 packageId manifest 缺失必须整包拒（单向配对判据）")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名配对不齐，实得：${e.message}", e.message!!.contains("配对不齐"))
        }
    }
}
