package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.EvalContext
import com.eratw.port.core.expr.Value
import com.eratw.port.core.expr.VariableReader
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.entityRuntimeOf
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.ConsoleBuiltinHost
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.VariableStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private const val SUPPLY_PACKAGE_ID: String = "supcat.supply"

/** base 侧 TARGET 的签名规范串（ModLinkCatalogProductionWriteTest 同形）。 */
private const val SUPPLY_CANONICAL: String = "SIG1|I,-,r,0|ret:I"

/** 全链测试的最小求值语境基座（ModLinkCatalogSeamTest 的 SeamBaseCtx 同形）。 */
private class SupplyBaseCtx : EvalContext {
    override lateinit var variables: VariableReader
    override fun callFunction(name: String, args: List<Value?>): Value =
        throw CodeEE("SupplyBaseCtx 不应被直接调用：$name")
    override fun printSystemLine(message: String) = Unit
}

/** 值调用：Long/Int → IntV，String → StrV。 */
private fun supplyCall(ctx: BuiltinEvalContext, name: String, vararg args: Any?): Value =
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

private fun supplyIv(v: Value): Long = (v as Value.IntV).v

/**
 * SUPCAT-W 判据 4：`HAS_SUPER` e2e 三态的**生产装配路**版本（SUPCAT 契约判据 4 补完）。
 * 「生产装配路」= 真链 + 真宿主 + 真供给：磁盘夹具（manifest + `.erbm` 容器 + base 签名段）
 * 经 `ModChainAssembly.assembleWithCatalog` 真跑五步链，链接产物快照从 **plan.catalog**
 * （SUPCAT-W 判据 1：派生保留）出发，GameHost 同形地经 attach inputs 供给
 * （判据 2/3：`ModRuntimeAssembly.attach(catalog = …)`），消费面走生产宿主
 * （ConsoleBuiltinHost + AuxMacroHost 装饰链）的 HAS_SUPER。与
 * ModLinkCatalogSeamTest 的分工：那边锁读缝与消费面本身；本文件锁「生产装配 →
 * plan 保留 → inputs 供给 → HAS_SUPER 真值」的整条生产链路。
 *
 * 三态（SUPCAT 契约判据 4 的口径）：
 * - 带覆写链的包函数（TARGET 胜者有前实现）⇒ 1；
 * - 纯 base 函数（ENTRY 单实现）⇒ 0；
 * - 关态总闸短路 ⇒ 0（catalog 在场也不消费）。
 *
 * 另锁一条退役证明：attach 不供给 catalog 时，即使 [MOD_LINK_CATALOG_SLOT] 槽在场，
 * entityRuntime 也不再从槽读（判据 3 的「槽写路对 entityRuntime 退役」），HAS_SUPER
 * 按未装配旁路 0 回答。
 */
class ModLinkCatalogSupplyE2ETest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 夹具（ModLinkCatalogProductionWriteTest 的磁盘形状：真链经生产装配入口）
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

    private fun writeChainFixtures(root: File) {
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        File(rt2, "a.manifest").writeBytes(
            ManifestCodec.encode(
                ModuleDescriptor(packageId = SUPPLY_PACKAGE_ID, overrideFunctions = setOf("TARGET")),
            ),
        )
        File(rt2, "a.erbm").writeBytes(
            ErbModuleContainer.encode(
                CompiledModule(
                    SUPPLY_PACKAGE_ID,
                    moduleProgram(),
                    listOf(FuncMeta(index = 0, key = "TARGET", argIntCount = 1)),
                    mapOf("TARGET" to BaseSignatureCatalog.fingerprint(SUPPLY_CANONICAL)),
                ),
                allowSuperCalls = true,
            ),
        )
        File(root, "erbc.v1/program.meta").apply { parentFile?.mkdirs() }
            .writeText("end\nsignatures 1\nsignature TARGET $SUPPLY_CANONICAL\n")
    }

    private fun inputs(root: File): ChainAssemblyInputs = ChainAssemblyInputs(
        gameRoot = root,
        enabled = true,
        base = base(),
        baseMetas = baseMetas,
        builtinVariableNames = { emptySet() },
        linkOrder = LinkOrder(),
    )

    /**
     * 开态装置：生产写侧写槽（事件名闸，GameHost 同形）→ 真宿主在 attach 之前构造
     * （复刻 GameSession 装配序）→ attach inputs 供给（supply=true 传 plan.catalog，
     * supply=false 不传 = 会话装配面之外直调的旁路形态）。
     */
    private class OpenFixture(
        root: File,
        outcome: ModChainAssemblyOutcome,
        supply: Boolean,
    ) {
        val logs = mutableListOf<String>()
        val store = VariableStore()

        init {
            // 生产写侧（事件名闸）：GameHost 在 store 建好后、attach 之前写槽，本装置同形。
            outcome.installCatalogSlot(store)
        }

        val consoleHost = ConsoleBuiltinHost(root.toPath(), audioStore = store)
        val session: ModRuntimeSession? = ModRuntimeAssembly.attach(
            gameRoot = root,
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line -> logs += line },
            catalog = if (supply) outcome.plan.catalog else null,
        )
    }

    /** 经生产宿主的求值语境（AuxMacroHost 装饰链同生产形态）。 */
    private fun ctxOf(store: VariableStore, consoleHost: ConsoleBuiltinHost): BuiltinEvalContext {
        val base = SupplyBaseCtx()
        base.variables = store
        return BuiltinEvalContext(base, store, consoleHost)
    }

    // ------------------------------------------------------------------
    // 三态之开态两态：覆写链包函数 = 1 / 纯 base 函数 = 0（生产装配路）
    // ------------------------------------------------------------------

    @Test
    fun `生产装配路_catalog经attachinputs供给_覆写链1纯base0`() {
        val root = tmp.newFolder("game")
        writeChainFixtures(root)
        val outcome = checkNotNull(
            ModChainAssembly.assembleWithCatalog(inputs(root)),
        ) { "有合法容器 + manifest + 签名段必须真装配（旁路才返回 null）" }
        // 判据 1 的生产路出口判据：plan.catalog 保留链接产物同一实例（派生不复制）。
        assertSame(
            "SUPCAT-W 判据 1：ModChainRuntimePlan.catalog 必须保留链接产物同一实例",
            outcome.catalog,
            outcome.plan.catalog,
        )

        val f = OpenFixture(root, outcome, supply = true)
        val session = checkNotNull(f.session) { "开态生产缝必须产出运行时；logs=${f.logs}" }
        try {
            val rt = checkNotNull(entityRuntimeOf(f.store.aux)) { "开态必须已回填 entityRuntime" }
            assertSame(
                "entityRuntime.catalog 必须与装配产物同一实例（inputs 供给只共享引用）",
                outcome.plan.catalog,
                rt.catalog,
            )
            assertTrue(
                "取证日志必须如实记 catalog=attached；logs=${f.logs}",
                f.logs.any { it.startsWith("mod.attach entityRuntime") && it.contains("catalog=attached") },
            )
            // 三态之①：带覆写链的包函数（TARGET 胜者有前实现）⇒ 1。
            f.store.enterFunction("TARGET")
            assertEquals(
                "覆写链函数 HAS_SUPER=1（specs：当前函数有 super 覆盖链返回 1）",
                1L,
                supplyIv(supplyCall(ctxOf(f.store, f.consoleHost), "HAS_SUPER")),
            )
            f.store.leaveFunction()
            // 三态之②：纯 base 函数（ENTRY 单实现无 super）⇒ 0。
            f.store.enterFunction("ENTRY")
            assertEquals(
                "纯 base 函数 HAS_SUPER=0（specs：否则 0）",
                0L,
                supplyIv(supplyCall(ctxOf(f.store, f.consoleHost), "HAS_SUPER")),
            )
            f.store.leaveFunction()
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 退役证明：attach 不供给时槽在场 entityRuntime 也不再读（判据 3 的槽写路退役）
    // ------------------------------------------------------------------

    @Test
    fun `attach未供给时槽在场entityRuntime也不再读_HAS_SUPER旁路0`() {
        val root = tmp.newFolder("game-bypass")
        writeChainFixtures(root)
        val outcome = checkNotNull(ModChainAssembly.assembleWithCatalog(inputs(root)))
        val f = OpenFixture(root, outcome, supply = false)
        val session = checkNotNull(f.session) { "开态 attach 必须产出运行时；logs=${f.logs}" }
        try {
            assertNull(
                "判据 3 退役证明：entityRuntime 的 catalog 只认 inputs 供给，槽在场也不读",
                entityRuntimeOf(f.store.aux)?.catalog,
            )
            assertTrue(
                "取证日志必须如实记 catalog=absent；logs=${f.logs}",
                f.logs.any { it.startsWith("mod.attach entityRuntime") && it.contains("catalog=absent") },
            )
            assertEquals(
                "供给缺席 ⇒ HAS_SUPER 旁路 0（未装配语义）",
                0L,
                supplyIv(supplyCall(ctxOf(f.store, f.consoleHost), "HAS_SUPER")),
            )
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // 三态之③：关态总闸短路，catalog 在场也不消费
    // ------------------------------------------------------------------

    @Test
    fun `关态零接触_catalog在场也不消费`() {
        val root = tmp.newFolder("game-off")
        writeChainFixtures(root)
        val outcome = checkNotNull(ModChainAssembly.assembleWithCatalog(inputs(root)))
        val logs = mutableListOf<String>()
        val store = VariableStore()
        val consoleHost = ConsoleBuiltinHost(root.toPath(), audioStore = store)
        outcome.installCatalogSlot(store)
        val session = ModRuntimeAssembly.attach(
            gameRoot = root,
            store = store,
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = false,
            logSink = { line -> logs += line },
            catalog = outcome.plan.catalog,
        )
        assertNull("关态总闸短路，会话必须为 null", session)
        assertNull("关态 entity 槽不回填，宿主读数必须保持 null", consoleHost.entityRuntime)
        assertNull("关态 entity 槽不回填", entityRuntimeOf(store.aux))
        assertTrue("关态零日志（总闸第一查短路）", logs.none { it.startsWith("mod.attach") })
        val base = SupplyBaseCtx()
        base.variables = store
        assertEquals(
            "三态之③：关态 HAS_SUPER 旁路 0（catalog 在场也不消费）",
            0L,
            supplyIv(supplyCall(BuiltinEvalContext(base, store, consoleHost), "HAS_SUPER")),
        )
    }
}
