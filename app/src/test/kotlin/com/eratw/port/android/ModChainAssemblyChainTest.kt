package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * RT2 G2 波（P1-5 余量一）：宿主 `ModChainAssembly.assemble` 的**非旁路真装配**判据。
 *
 * 与 `ModChainAssemblyWireTest`（G 车第一批的旁路哨兵）互补：那边锁「有容器即整包拒」，
 * 这边锁「有合法容器 + manifest + 签名真目录 ⇒ 真的装配出一个三表同源值对象」。
 *
 * 锁五件事：① 关态零接触 / 开态空表完全旁路（连 `builtinVariableNames` 都不求值）；
 * ② manifest × 容器按 packageId 配对，不齐 / 坏档 / 非 Mod 档位整包拒；
 * ③ 签名真目录从 `program.meta` 尾部段送到 `assemble(baseSignatureOf = …)`，
 * 段在场 ⇒ 覆盖放行 ⇒ `hasOverrides=true` / dispatch 非恒等（反向证明 ② 的对照）；
 * ④ 段缺失 ⇒ 核心既有 `OverrideSignatureException` fail-closed，不放宽闸、不拿 arity 猜；
 * ⑤ 有界尾读：侧车超过 8 MiB 时仍能读到尾部签名段，不整读侧车。
 */
class ModChainAssemblyChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val canonical = "SIG1|I,-,r,0|ret:I"
    private val fingerprint by lazy { BaseSignatureCatalog.fingerprint(canonical) }

    /** base：ENTRY@0 / TARGET@1，各一条 _RET。 */
    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
    )

    /** Mod 包：覆盖 base 的 TARGET，签名指纹与 base 侧车目录同源。 */
    private fun moduleProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val moduleMetas = listOf(FuncMeta(index = 0, key = "TARGET", argIntCount = 1))

    private fun writeManifest(root: File, packageId: String = "mod.a", override: Boolean = true) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        val d = ModuleDescriptor(
            packageId = packageId,
            overrideFunctions = if (override) setOf("TARGET") else emptySet(),
        )
        File(dir, "a.manifest").writeBytes(ManifestCodec.encode(d))
    }

    private fun writeContainer(
        root: File,
        packageId: String = "mod.a",
        allowSuperCalls: Boolean = true,
        garbage: Boolean = false,
    ) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        val bytes = if (garbage) {
            byteArrayOf(1, 2, 3, 4, 5)
        } else {
            val cm = CompiledModule(
                packageId,
                moduleProgram(),
                moduleMetas,
                mapOf("TARGET" to fingerprint),
            )
            ErbModuleContainer.encode(cm, allowSuperCalls)
        }
        File(dir, "a.erbm").writeBytes(bytes)
    }

    private fun writeMeta(root: File, withSection: Boolean = true, huge: Boolean = false) {
        val meta = File(root, "erbc.v1/program.meta").apply { parentFile?.mkdirs() }
        // 单个超长行（> 8 MiB 尾读上界）：切口落在该行中间，readTailText 丢掉半行后
        // 尾部 `end` + 签名段完整保留；用单长行而非百万空行，避免 parse 的 split 炸堆。
        val head = if (huge) "x".repeat(9 * 1024 * 1024) + "\n" else ""
        val section = if (withSection) "signatures 1\nsignature TARGET $canonical\n" else ""
        meta.writeText(head + "end\n" + section)
    }

    // ------------------------------------------------------------------
    // ① 关态零接触 / 空表完全旁路
    // ------------------------------------------------------------------

    @Test
    fun `关态零接触_坏容器与坏侧车在场也返回null`() {
        val root = tmp.newFolder("game")
        writeContainer(root, garbage = true)
        writeMeta(root)
        val calls = AtomicInteger(0)
        assertNull(
            ModChainAssembly.assemble(
                ChainAssemblyInputs(
                    root,
                    false,
                    base(),
                    baseMetas,
                    builtinVariableNames = { calls.incrementAndGet(); emptySet() },
                ),
            ),
        )
        assertEquals("关态连惰性 lambda 都不许求值", 0, calls.get())
        // 哨兵兼容面同样关态零接触。
        ModChainAssembly.attach(root, enabled = false)
    }

    @Test
    fun `开态空表完全旁路_不读侧车不求值惰性参数`() {
        val root = tmp.newFolder("game")
        writeManifest(root) // 只有 manifest、没有 .erbm
        writeMeta(root, withSection = false)
        val calls = AtomicInteger(0)
        assertNull(
            ModChainAssembly.assemble(
                ChainAssemblyInputs(
                    root,
                    true,
                    base(),
                    baseMetas,
                    builtinVariableNames = { calls.incrementAndGet(); emptySet() },
                ),
            ),
        )
        assertEquals("空表完全旁路时不建内置名表", 0, calls.get())
    }

    // ------------------------------------------------------------------
    // ② 真装配（签名段在场）
    // ------------------------------------------------------------------

    @Test
    fun `真装配_三表同源且HAS_OVERRIDES驱动非恒等dispatch`() {
        val root = tmp.newFolder("game")
        writeManifest(root)
        writeContainer(root)
        writeMeta(root)
        val calls = AtomicInteger(0)
        val value = ModChainAssembly.assemble(
            ChainAssemblyInputs(
                root,
                true,
                base(),
                baseMetas,
                builtinVariableNames = {
                    calls.incrementAndGet()
                    emptySet()
                },
            ),
        )
        assertNotNull("容器在场必须真装配，不许返回 null 旁路", value)
        assertEquals("有容器时内置名表必须被求值一次", 1, calls.get())
        val v = value!!
        assertTrue(v.hasOverrides)
        // 核心层三视图：无覆盖时 functionDispatch 为 null（v6.2-c 性能闸），有覆盖时为幂等表。
        val dispatch = requireNotNull(v.functionDispatch) { "有覆盖时三视图必须给出非 null dispatch" }
        assertTrue("覆盖后 dispatch 不再恒等", dispatch.indices.any { dispatch[it] != it })
        assertEquals(v.program.functions.size, v.functionTable.all().size)
        assertEquals(v.program.functions.size, dispatch.size)
        assertEquals(
            (0 until v.program.functions.size).toList(),
            (0 until v.program.functions.size).map { v.functionTable.byFunctionIndex(it)!!.index },
        )
        assertTrue("链接产物函数表必须比 base 多出 Mod 函数", v.program.functions.size > base().functions.size)
        assertTrue("base 的 TARGET 必须被分派到覆盖者", dispatch[1] != 1)
        assertEquals("TARGET", v.functionTable.byFunctionIndex(dispatch[1])!!.key)
        for (i in dispatch.indices) {
            assertEquals("dispatch[$i] 幂等", dispatch[i], dispatch[dispatch[i]])
        }
    }

    @Test
    fun `有界尾读_超大侧车尾部签名段仍生效`() {
        val root = tmp.newFolder("game")
        writeManifest(root)
        writeContainer(root)
        writeMeta(root, huge = true)
        val value = ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
        assertNotNull(value)
        assertTrue(value!!.hasOverrides)
    }

    // ------------------------------------------------------------------
    // ③ 签名真目录生产消费点（反向证明 ② 的对照）
    // ------------------------------------------------------------------

    @Test
    fun `KI-2 签名段缺失_MODERN显式覆盖改告警放行装配成功`() {
        // 2026-11-23 KI 改判（BOARD KI-2）：缺签名段时 base 指纹为空，覆盖不再被
        // OverrideSignatureException 拒（原 fail-closed 反转）——装配成功 + 留痕告警。
        val root = tmp.newFolder("game")
        writeManifest(root)
        writeContainer(root)
        writeMeta(root, withSection = false)
        val warnings = mutableListOf<String>()
        val value = ModChainAssembly.assemble(
            ChainAssemblyInputs(root, true, base(), baseMetas, logSink = { warnings += it }),
        )
        assertNotNull(value)
        assertTrue(
            "缺指纹告警进装配留痕: $warnings",
            warnings.any { it.contains("签名指纹缺失") || it.contains("ABI 未校验") },
        )
    }

    @Test
    fun `KI-2 侧车文件缺失_目录恒空串_覆盖同样放行`() {
        val root = tmp.newFolder("game")
        writeManifest(root)
        writeContainer(root)
        val dir = ModChainAssembly.signatureDirectoryOfFile(File(root, "erbc.v1/program.meta"))
        assertEquals("", dir("TARGET"))
        // 2026-11-23 KI 改判：侧车缺失 = 真目录恒空 = 告警放行（原拒反转）。
        val value = ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
        assertNotNull(value)
    }

    // ------------------------------------------------------------------
    // ④ fail-closed 形态
    // ------------------------------------------------------------------

    @Test
    fun `manifest与容器packageId不齐整包拒`() {
        val root = tmp.newFolder("game")
        writeManifest(root, packageId = "mod.a")
        writeContainer(root, packageId = "mod.b")
        writeMeta(root)
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
            fail("配对不齐必须整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("配对不齐"))
        }
    }

    @Test
    fun `坏容器整包拒且不返回半成品`() {
        val root = tmp.newFolder("game")
        writeManifest(root)
        writeContainer(root, garbage = true)
        writeMeta(root)
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
            fail("坏 .erbm 必须整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("fail-closed"))
        }
    }

    @Test
    fun `非Mod编译档位容器整包拒`() {
        val root = tmp.newFolder("game")
        writeManifest(root)
        writeContainer(root, allowSuperCalls = false)
        writeMeta(root)
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
            fail("allowSuperCalls=false 不是 Mod 包编译档，必须整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("档位") || e.message!!.contains("allowSuperCalls"))
        }
    }

    @Test
    fun `无覆盖声明时只加新函数_hasOverrides为假且dispatch走直调快路径`() {
        val root = tmp.newFolder("game")
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, "a.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = "mod.a")),
        )
        val b = CodeBuilder()
        val entry = b.declareFunction("ANEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val cm = CompiledModule(
            "mod.a", b.build(entry), listOf(FuncMeta(index = 0, key = "ANEW")), emptyMap(),
        )
        File(dir, "a.erbm").writeBytes(ErbModuleContainer.encode(cm, allowSuperCalls = true))
        writeMeta(root, withSection = false)
        val value = ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))
        assertNotNull(value)
        assertFalse(value!!.hasOverrides)
        // 核心层 v6.2-c 性能闸：无覆盖时恒等表只是语义基准，不进 VM（null = 物理下标直调）。
        assertNull("无覆盖时 functionDispatch 必须为 null", value.functionDispatch)
        assertEquals(base().functions.size + 1, value.functionTable.all().size)
    }

    // ------------------------------------------------------------------
    // ⑤ RT2-ERBM 裁定①：载体 manifest 与模块容器共存（单向配对）
    // ------------------------------------------------------------------

    /**
     * 载体形态 manifest（无 `.erbm`，如 `eratw.family`）与 demo 容器在场：
     * 配对判据单向化后链只装配 demo、载体跳过（声明事务仍由 `ModRuntimeAssembly.attach`
     * 全量加载，冲突检测不丢）。若配对仍按旧双向严格相等判，这里会在 host 闸整包拒。
     */
    @Test
    fun `载体manifest与模块容器共存_链只装配容器包_载体跳过`() {
        val root = tmp.newFolder("game")
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        // 载体形态：只有 manifest、没有容器。
        File(dir, "eratw.family.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = "eratw.family")),
        )
        // 模块形态：manifest + 容器成对。
        File(dir, "eratw.demo.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = "eratw.demo")),
        )
        val b = CodeBuilder()
        val entry = b.declareFunction("ERATW_DEMO_PING", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val cm = CompiledModule(
            "eratw.demo", b.build(entry), listOf(FuncMeta(index = 0, key = "ERATW_DEMO_PING")), emptyMap(),
        )
        File(dir, "eratw.demo.erbm").writeBytes(ErbModuleContainer.encode(cm, allowSuperCalls = true))

        val value = ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas))

        assertNotNull("载体 + 模块共存必须真装配（旧双向判据会整包拒）", value)
        val v = value!!
        assertFalse("demo 无覆盖声明", v.hasOverrides)
        assertNull("无覆盖时 dispatch 走直调快路径", v.functionDispatch)
        assertEquals("函数表 = base + demo 一条", base().functions.size + 1, v.functionTable.all().size)
        assertTrue(
            "链里装的必须是 demo 的函数",
            v.functionTable.all().any { it.key == "ERATW_DEMO_PING" },
        )
    }

    @Test
    fun `关态带载体与容器双资产仍返回null`() {
        val root = tmp.newFolder("game")
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, "eratw.family.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = "eratw.family")),
        )
        File(dir, "eratw.demo.erbm").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        val calls = AtomicInteger(0)
        assertNull(
            ModChainAssembly.assemble(
                ChainAssemblyInputs(
                    root,
                    false,
                    base(),
                    baseMetas,
                    builtinVariableNames = { calls.incrementAndGet(); emptySet() },
                ),
            ),
        )
        assertEquals("关态连惰性 lambda 都不许求值", 0, calls.get())
    }
}
