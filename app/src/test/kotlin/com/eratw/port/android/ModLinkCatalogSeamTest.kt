package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.entityRuntimeOf
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainAssembler
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import com.eratw.port.modapi.ModManifest
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SEAM_PACKAGE_ID: String = "supcat.seam"

/** base 侧 TARGET 的签名规范串（ModChainAssemblyChainTest 同形）。 */
private const val SEAM_CANONICAL: String = "SIG1|I,-,r,0|ret:I"

/** 全链测试的最小求值语境基座（与 EntityRuntimeBackfillWireTest 的 WireBaseCtx 同形）。 */
private class SeamBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("SeamBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

/** 值调用：Long/Int → IntV，String → StrV。 */
private fun seamCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
    ctx.callFunction(
        name,
        args.map {
            when (it) {
                is Long -> Value.IntV(it)
                is Int -> Value.IntV(it.toLong())
                is String -> Value.StrV(it)
                is Value -> it
                else -> throw IllegalArgumentException("$it")
            }
        },
    )

private fun seamIv(v: Value): Long = (v as Value.IntV).v

/**
 * W2-SUPCAT：`EntityRuntime.catalog` 读缝与 HAS_SUPER 消费面全链（CONTRACT-RT2-SUPCAT
 * 判据 1/2/3/4 的测试面；生产宿主纵向切片，装置形态照 EntityRuntimeBackfillWireTest 的
 * OpenFixture + ModChainAssemblyChainTest 的真链夹具）。
 *
 * 供给形态（SUPCAT-W 判据 3 起 = **生产供给**）：W2-SUPCAT 时期本测试手工把链接产物
 * 快照写进 [MOD_LINK_CATALOG_SLOT]（当时「写侧」是仿真，类头明写诚实边界）；SUPCAT-W
 * 乙形裁定后 entityRuntime 的读缝源改走 attach inputs 供给（`attach(catalog = …)`，
 * 生产 = GameHost 传 `plan.catalog`），本测试随之改为经同一条生产供给缝注入——
 * 手工写槽形态退役（槽现只服务事件名存在性闸，其生产路径仍由
 * ModLinkCatalogProductionWriteTest 端到端钉住）。
 * 本测试把**真五步链**（ModChainAssembler.assemble：配对 → 校验 → 链接 →
 * LinkedProgram.catalog）的产物快照经 attach inputs 供给，证明读缝与消费面贯通；
 * 会话外直调 attach 时供给缺席的旁路 0 由第二个用例单独钉住。
 *
 * 判据映射：
 * - 判据 1 / 4（开态真值 + e2e 三态）：真链 catalog 注入后，覆写链函数（胜者有前实现）
 *   HAS_SUPER=1、纯 base 函数=0、无帧=0；
 * - 判据 3（catalog 单源）：EntityRuntime.catalog 必须是链接产物**同一实例**（只读引用，
 *   不复制、不二次装配）；
 * - 判据 2 / 5（关态与未装配旁路）：供给缺席 ⇒ catalog null ⇒ HAS_SUPER=0；关态总闸短路
 *   ⇒ catalog 在场也不消费。
 */
class ModLinkCatalogSeamTest {

    // ------------------------------------------------------------------
    // 真链夹具（ModChainAssemblyChainTest 的 base + 覆写包形态，经核心装配器真跑五步链）
    // ------------------------------------------------------------------

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

    /** Mod 包：覆盖 base 的 TARGET，签名指纹与 base 侧签名目录同源。 */
    private fun moduleProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    /**
     * 真五步链的链接产物 catalog（判据 3 单源 = 这一份实例原样进 attach inputs、
     * 原样进 EntityRuntime）。直接调核心装配器而非生产装配入口：本测试锁读缝与消费面，
     * 走最短真链（ModChainAssembler.assemble → AssembledChain.linked.catalog）；
     * 「磁盘夹具 → 生产装配入口 → plan.catalog → attach 供给」的生产路版本见
     * ModLinkCatalogSupplyE2ETest。
     */
    private fun realChainCatalog(): SymbolCatalog {
        val fingerprint = BaseSignatureCatalog.fingerprint(SEAM_CANONICAL)
        val cm = CompiledModule(
            SEAM_PACKAGE_ID,
            moduleProgram(),
            listOf(FuncMeta(index = 0, key = "TARGET", argIntCount = 1)),
            mapOf("TARGET" to fingerprint),
        )
        val descriptor = ModuleDescriptor(
            packageId = SEAM_PACKAGE_ID,
            overrideFunctions = setOf("TARGET"),
        )
        val chain = ModChainAssembler.assemble(
            sources = listOf(ManifestSource("seam.manifest", ManifestCodec.encode(descriptor))),
            compiledModules = listOf(cm),
            base = base(),
            baseMetas = baseMetas,
            baseSignatureOf = { key -> if (key == "TARGET") fingerprint else "" },
        )
        return checkNotNull(chain.linked) { "非旁路装配必须带 LinkedProgram（装配器不变量破坏）" }.catalog
    }

    // ------------------------------------------------------------------
    // 开态 attach 装置（EntityRuntimeBackfillWireTest 的 OpenFixture 同形）
    // ------------------------------------------------------------------

    /** 开态装置：真 manifest → 真 freeze → 真 attach → 真宿主；[linkCatalog] 非空时经 attach inputs 供给。 */
    private class OpenFixture(linkCatalog: SymbolCatalog?) {
        val logs = mutableListOf<String>()
        val root: Path = Files.createTempDirectory("rt2-supcat-seam").also {
            Files.createDirectories(it.resolve("mods").resolve("rt2"))
        }
        val store = VariableStore()
        val probe = SeamProbeMod()

        /** 生产宿主在 attach 之前构造（复刻 GameSession 装配序）。 */
        val consoleHost = ConsoleBuiltinHost(root, audioStore = store)
        val session: ModRuntimeSession

        init {
            // SUPCAT-W 生产供给：链接产物快照经 attach inputs 供给（生产 = GameHost 传
            // plan.catalog；W2-SUPCAT 的手工写槽形态已退役，见类 KDoc「供给形态」节）。
            Files.write(
                root.resolve("mods").resolve("rt2").resolve("supcat_seam.manifest"),
                ManifestCodec.encode(ModuleDescriptor(packageId = SEAM_PACKAGE_ID)),
            )
            session = checkNotNull(
                ModRuntimeAssembly.attach(
                    gameRoot = root.toFile(),
                    store = store,
                    nativeRegistry = NativeMethodRegistry(),
                    onVmThread = { true },
                    enabled = true,
                    logSink = { line -> logs += line },
                    modProviders = listOf(SEAM_PACKAGE_ID to probe),
                    catalog = linkCatalog,
                ),
            ) { "开态生产缝必须产出运行时；logs=$logs" }
            assertNotNull(
                "开态生产宿主的 entityRuntime 必须是真装配",
                consoleHost.entityRuntime,
            )
            assertTrue(
                "宿主读数与槽真源必须同一实例",
                entityRuntimeOf(store.aux) === consoleHost.entityRuntime,
            )
        }

        /** 经生产宿主的求值语境（AuxMacroHost 装饰链同生产形态）。 */
        fun ctx(): BuiltinEvalContext {
            val base = SeamBaseCtx()
            base.variables = store
            return BuiltinEvalContext(base, store, consoleHost)
        }
    }

    /** 探针 mod：只为让载体包走完 install，语义由 HAS_SUPER 消费面自证。 */
    private class SeamProbeMod : EraMod {
        lateinit var context: ModContext
        override fun onLoad(context: ModContext) {
            this.context = context
        }
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    // ------------------------------------------------------------------
    // 判据 1 / 3 / 4：真链 catalog 读缝注入 + HAS_SUPER 真值（e2e 三态的「开态」两态）
    // ------------------------------------------------------------------

    @Test
    fun `开态读缝注入_真链catalog随attach进EntityRuntime_HAS_SUPER真值`() {
        val catalog = realChainCatalog()
        val f = OpenFixture(catalog)
        val rt = checkNotNull(entityRuntimeOf(f.store.aux)) { "开态槽必须已回填" }
        assertTrue(
            "判据 3：EntityRuntime.catalog 必须是链接产物同一实例（只读引用，不复制不二次装配）",
            rt.catalog === catalog,
        )
        assertTrue(
            "取证日志必须如实记 catalog=attached；logs=${f.logs}",
            f.logs.any { it.startsWith("mod.attach entityRuntime") && it.contains("catalog=attached") },
        )
        // 判据 1 / 4：覆写链函数（TARGET 胜者有前实现）⇒ 1。
        f.store.enterFunction("TARGET")
        assertEquals(
            "覆写链函数 HAS_SUPER=1（specs：当前函数有 super 覆盖链返回 1）",
            1L,
            seamIv(seamCall(f.ctx(), "HAS_SUPER")),
        )
        f.store.leaveFunction()
        // 判据 4：纯 base 函数（ENTRY 单实现无 super）⇒ 0。
        f.store.enterFunction("ENTRY")
        assertEquals(
            "纯 base 函数 HAS_SUPER=0（specs：否则 0）",
            0L,
            seamIv(seamCall(f.ctx(), "HAS_SUPER")),
        )
        f.store.leaveFunction()
        // 无帧旁路（EntityMethodsTest `HAS_SUPER - 覆盖链判据与无帧旁路` 同口径）。
        assertEquals(0L, seamIv(seamCall(f.ctx(), "HAS_SUPER")))
    }

    // ------------------------------------------------------------------
    // 判据 2 / 5：会话外直调 attach（供给缺席）旁路 0 —— 零变化锁
    // ------------------------------------------------------------------

    @Test
    fun `开态未供给_catalog保持null_HAS_SUPER旁路0`() {
        val f = OpenFixture(null)
        val rt = checkNotNull(entityRuntimeOf(f.store.aux)) { "开态槽必须已回填" }
        assertNull(
            "会话外直调 attach（未供给）：catalog 必须 null（EntityRuntimeBackfillWireTest 既有锁同口径）",
            rt.catalog,
        )
        assertEquals("槽缺席 ⇒ HAS_SUPER 旁路 0（判据 2）", 0L, seamIv(seamCall(f.ctx(), "HAS_SUPER")))
        assertTrue(
            "取证日志必须如实记 catalog=absent；logs=${f.logs}",
            f.logs.any { it.startsWith("mod.attach entityRuntime") && it.contains("catalog=absent") },
        )
    }

    // ------------------------------------------------------------------
    // 判据 2 / 5：关态总闸短路，catalog 在场也不消费
    // ------------------------------------------------------------------

    @Test
    fun `关态零接触_catalog在场也不消费`() {
        val catalog = realChainCatalog()
        val logs = mutableListOf<String>()
        val root: Path = Files.createTempDirectory("rt2-supcat-off")
        val store = VariableStore()
        store.aux.slot(MOD_LINK_CATALOG_SLOT) { catalog }
        val consoleHost = ConsoleBuiltinHost(root, audioStore = store)
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = false,
            logSink = { line -> logs += line },
            catalog = catalog,
        )
        assertNull("关态总闸短路，会话必须为 null", session)
        assertNull("关态宿主读数必须保持 null", consoleHost.entityRuntime)
        assertNull("关态 entity 槽不回填", entityRuntimeOf(store.aux))
        assertTrue("关态零日志（总闸第一查短路）", logs.none { it.startsWith("mod.attach") })

        val base = SeamBaseCtx()
        base.variables = store
        val ctx = BuiltinEvalContext(base, store, consoleHost)
        assertEquals("判据 2：关态 HAS_SUPER 旁路 0", 0L, seamIv(seamCall(ctx, "HAS_SUPER")))
    }
}
