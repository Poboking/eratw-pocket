package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.fn.entityRuntimeOf
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.ModContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Lane V：`MOD_LINK_CATALOG_SLOT` 的**生产写侧**哨兵（任务 22）。
 *
 * 与 ModLinkCatalogSeamTest 的分工：那边锁读缝与消费面本身（SUPCAT-W 起经 attach inputs
 * 生产供给）；本测试锁生产写侧函数 —— `ModChainAssembly.assembleWithCatalog`（装配点交出
 * 链接产物快照）+ `ModChainAssemblyOutcome.installCatalogSlot`（宿主在会话 store 建好后
 * 写槽），再进 `ModRuntimeAssembly.attach`，端到端钉住「装配 → 写槽 → 事件名闸 /
 * entityRuntime 回填」。
 *
 * SUPCAT-W 注：entityRuntime 回填的 catalog 自该波起改走 attach inputs 供给
 * （`attach(catalog = plan.catalog)`，GameHost 同形），本测试的 attach 调用随之带供给；
 * 槽仍服务事件名存在性闸，写侧与供给共用装配产物同一实例。
 *
 * 锁四件事：
 * - ① 生产写侧在场：槽里读回的 catalog 与装配产物是**同一实例**，`catalog.events` 非空
 *   ⇒ `host.knownEventKeys` 真取值（U 波闸从「恒空集」变为「真实事件名集合」）；
 * - ② 闸真生效：同一次 attach 里，正确事件名的探针 mod active=true、拼错事件名的探针
 *   mod active=false 且 failure=IllegalArgumentException（attach 日志面同源可查）；
 * - ③ entityRuntime 回填同源：`entityRuntimeOf(store.aux)?.catalog` 也是同一实例
 *   （HAS_SUPER 消费面的来源，经 attach inputs 供给），证明写槽面与供给面共用一份快照；
 * - ④ 反向证明（删写侧且不供给 ⇒ 哨兵红）：同一装配走既有 `assemble`（只拿 plan）而不
 *   写槽、attach 不传 catalog ⇒ 槽缺席 ⇒ 事件名闸空集放行，拼错事件名的 mod 反而
 *   active=true，entityRuntime.catalog=null。即本测试 ② 的绿完全由写侧承担，不是别处兜底。
 *
 * GameHost 侧接线钉单独一条源码守卫（`序列守卫`）：宿主里 `installCatalogSlot(fresh.store)`
 * 必须存在且早于 `ModRuntimeAssembly.attach(`，且 attach 调用带 SUPCAT-W 判据 2 的
 * `catalog = modChain?.catalog` 供给 —— 生产装配顺序是这套时序的合同。
 */
class ModLinkCatalogProductionWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val canonical = "SIG1|I,-,r,0|ret:I"
    private val fingerprint by lazy { BaseSignatureCatalog.fingerprint(canonical) }

    /** base：ENTRY@0 / TARGET@1 / EVENTFIRST@2（事件函数 —— 生产 catalog.events 的非空来源）。 */
    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("EVENTFIRST", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
        // 事件声明面：FuncMeta.isEvent 是 SymbolCatalog.events 的唯一来源（SymbolCatalog.kt:195）。
        FuncMeta(index = 2, key = "EVENTFIRST", isEvent = true),
    )

    /** Mod 包：覆盖 base 的 TARGET（与 ModChainAssemblyChainTest 同形，只为触发真装配链）。 */
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
                ModuleDescriptor(packageId = "mod.a", overrideFunctions = setOf("TARGET")),
            ),
        )
        File(rt2, "a.erbm").writeBytes(
            ErbModuleContainer.encode(
                CompiledModule(
                    "mod.a",
                    moduleProgram(),
                    listOf(FuncMeta(index = 0, key = "TARGET", argIntCount = 1)),
                    mapOf("TARGET" to fingerprint),
                ),
                allowSuperCalls = true,
            ),
        )
        // 两个探针包的载体 manifest（无 .erbm ⇒ 链接链跳过，但声明事务由 attach 全量加载）。
        for (pkg in listOf(GOOD_PKG, TYPO_PKG)) {
            File(rt2, "$pkg.manifest").writeBytes(
                ManifestCodec.encode(ModuleDescriptor(packageId = pkg)),
            )
        }
        File(root, "erbc.v1/program.meta").apply { parentFile?.mkdirs() }
            .writeText("end\nsignatures 1\nsignature TARGET $canonical\n")
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
     * 探针 mod：`onLoad` 里注册一个事件处理器（名字由外部给，便于故意写错）。
     * 事件名闸在 `EventDispatchRegistry.register` 内（EventDispatchRegistry.kt:92-94）。
     */
    private class EventProbeMod(private val eventKey: String) : EraMod {
        override fun onLoad(context: ModContext) {
            context.events.register(eventKey, EventBucket.NORMAL) { EventResult.CONTINUE }
        }

        override fun onEnable() = Unit

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private fun attach(
        root: File,
        store: VariableStore,
        logs: MutableList<String>,
        catalog: SymbolCatalog? = null,
    ): ModRuntimeSession? = ModRuntimeAssembly.attach(
        gameRoot = root,
        store = store,
        nativeRegistry = NativeMethodRegistry(),
        onVmThread = { true },
        enabled = true,
        logSink = { line -> logs += line },
        modProviders = listOf(
            GOOD_PKG to EventProbeMod(GOOD_EVENT),
            TYPO_PKG to EventProbeMod(TYPO_EVENT),
        ),
        // SUPCAT-W 判据 2/3：entityRuntime 回填的 catalog 经 attach inputs 供给（GameHost 同形）。
        catalog = catalog,
    )

    // ------------------------------------------------------------------
    // ① 生产写侧在场：装配 → 写槽 → attach，槽与读缝同一实例 + 闸真生效
    // ------------------------------------------------------------------

    @Test
    fun `生产写侧_装配产物写槽后事件名闸生效且回填catalog同源`() {
        val root = tmp.newFolder("game")
        writeChainFixtures(root)

        val outcome = checkNotNull(
            ModChainAssembly.assembleWithCatalog(inputs(root)),
        ) { "有合法容器 + manifest + 签名段必须真装配（旁路才返回 null）" }
        assertTrue(
            "base 事件函数必须进 catalog.events（否则闸无事件名可校，本测试前提不成立）：" +
                "${outcome.catalog.events.keys}",
            outcome.catalog.events.containsKey(GOOD_EVENT),
        )
        assertTrue(
            "事件名闸的候选集合必须非空（Lane U 的闸只在非空集时校验）",
            outcome.catalog.events.isNotEmpty(),
        )

        val store = VariableStore()
        // 生产写侧：宿主在会话 store 建好后调这一句（GameHost 同形）。
        outcome.installCatalogSlot(store)
        assertSame(
            "写侧必须挂装配产物同一实例（只共享引用，不重算 / 不二次装配）",
            outcome.catalog,
            store.aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
        )

        val logs = mutableListOf<String>()
        // SUPCAT-W：entityRuntime 回填走 attach inputs 供给（GameHost 同形：传 plan.catalog）。
        val session = checkNotNull(attach(root, store, logs, outcome.plan.catalog)) {
            "开态 attach 必须产出运行时；logs=$logs"
        }
        try {
            assertTrue(
                "正确事件名的探针必须照常启用；logs=$logs",
                logs.any { it == "mod.install pkg=$GOOD_PKG active=true" },
            )
            assertTrue(
                "拼错事件名的探针必须被事件名闸拒（active=false + IAE）；logs=$logs",
                logs.any {
                    it.startsWith("mod.install pkg=$TYPO_PKG active=false") &&
                        it.contains("IllegalArgumentException")
                },
            )
            assertSame(
                "entityRuntime 回填必须与写槽同一实例（写槽面与供给面共用一份快照）",
                outcome.catalog,
                entityRuntimeOf(store.aux)?.catalog,
            )
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // ④ 反向证明：删写侧（只走 assemble，不写槽）⇒ 闸空集放行、回填 catalog=null
    // ------------------------------------------------------------------

    @Test
    fun `反向证明_不写槽时事件名闸空集放行且catalog为null`() {
        val root = tmp.newFolder("game")
        writeChainFixtures(root)

        checkNotNull(ModChainAssembly.assemble(inputs(root))) {
            "既有入口 assemble 必须保持返回 plan 的旧形状（向后兼容）"
        }
        assertNull(
            "反向证明前提：不调 installCatalogSlot 时槽必须缺席",
            VariableStore().aux.peek<SymbolCatalog>(MOD_LINK_CATALOG_SLOT),
        )

        val store = VariableStore()
        val logs = mutableListOf<String>()
        val session = checkNotNull(attach(root, store, logs)) { "开态 attach 必须产出运行时；logs=$logs" }
        try {
            assertTrue(
                "槽缺席 ⇒ 闸空集不校验 ⇒ 拼错事件名的 mod 也被放行（写侧就是这道闸的开关）；logs=$logs",
                logs.any { it == "mod.install pkg=$TYPO_PKG active=true" },
            )
            assertNull(
                "不写槽且 attach 不供给 ⇒ entityRuntime 回填 catalog=null（未装配语义；SUPCAT-W 起供给面承担）",
                entityRuntimeOf(store.aux)?.catalog,
            )
        } finally {
            session.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // GameHost 侧接线钉：写槽必须在会话构造后、attach 之前
    // ------------------------------------------------------------------

    @Test
    fun `宿主接线钉_写槽存在且早于attach`() {
        val host = sourceOf("GameHost.kt")
        val assembleCall = host.indexOf("ModChainAssembly.assembleWithCatalog(")
        val writeSlot = host.indexOf("modChainOutcome?.installCatalogSlot(fresh.store)")
        val attachCall = host.indexOf("ModRuntimeAssembly.attach(")
        // 行首锚：注释化的供给行（// catalog = ...）不算接线（反向证明② 的注释化形态抓得住）。
        val supply = Regex("\n\\s*catalog = modChain\\?\\.catalog").find(host)?.range?.first ?: -1
        assertTrue("宿主必须经 assembleWithCatalog 取装配产物（含快照）", assembleCall >= 0)
        assertTrue(
            "宿主必须把快照写进会话 store.aux（MOD_LINK_CATALOG_SLOT 生产写侧唯一落点）",
            writeSlot >= 0,
        )
        assertTrue("宿主必须有 attach 调用点", attachCall >= 0)
        assertTrue(
            "SUPCAT-W 判据 2：宿主必须把 plan.catalog 经 attach inputs 供给（catalog = modChain?.catalog）",
            supply >= 0,
        )
        assertTrue(
            "时序合同：先取装配产物、再写槽、最后 attach（供给行在 attach 调用内）；" +
                "实际下标 assemble=$assembleCall write=$writeSlot attach=$attachCall supply=$supply",
            assembleCall < writeSlot && writeSlot < attachCall && supply > attachCall,
        )
        assertFalse(
            "U 波 TODO(verify) 必须已被生产写侧收账（不许再留「生产写侧目前不存在」）",
            sourceOf("ModRuntimeAssembly.kt").contains("生产写侧目前不存在"),
        )
        assertFalse(
            "SUPCAT-W 判据 3：entityRuntime 回填不得再从槽 peek（读缝源 = attach inputs 供给；" +
                "事件名闸的读缝除外，needle 只指回填函数的旧 peek 行形态）",
            // needle 钉的是**回填那一行**的旧形态（回填曾直接吃槽里 peek 出来的
            // linkCatalog）。事件名闸那一行同名的槽读口是合法的（判据 3 明文除外），
            // 早先按整行文本匹配会把它一并命中；改钉回填调用点的旧形态即可。
            sourceOf("ModRuntimeAssembly.kt").contains(
                "AttachedEntityRuntime(inputs, linkCatalog)",
            ),
        )
        assertTrue(
            "回填必须改吃 attach inputs 供给（catalog 随链走）",
            sourceOf("ModRuntimeAssembly.kt").contains(
                "AttachedEntityRuntime(inputs, inputs.linkCatalog)",
            ),
        )
    }

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private companion object {
        const val GOOD_PKG = "mod.probe.good"
        const val TYPO_PKG = "mod.probe.typo"

        /** catalog.events 的键（FuncMeta.key 原样）；正确探针用它。 */
        const val GOOD_EVENT = "EVENTFIRST"

        /** 故意拼错一个字符（闸开时 register 抛 IAE 并给最近候选）。 */
        const val TYPO_EVENT = "EVENTFIRSTT"
    }
}
