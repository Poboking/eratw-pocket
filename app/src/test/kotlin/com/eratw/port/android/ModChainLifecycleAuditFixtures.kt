package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.EventBucket
import com.eratw.port.modapi.EventResult
import com.eratw.port.modapi.ModContext
import java.io.File

/**
 * Lane CO（task-86）审计夹具：`mod.chain.catalog` 槽与 `ModEventTriggerWiring` 的跨会话生命周期。
 *
 * 夹具只提供「真装配」所需的最小面：base 三函数（ENTRY / TARGET / EVENTFIRST）、一个覆盖
 * TARGET 的 mod 包、两个只注册事件处理的探针包。事件探针把「是否被派发」写成测试侧日志行
 * （`probe-event:<key>`），不依赖 console 文本，关态后可直接区分「派发」与「未派发」。
 *
 * `EVENTFIRST` 函数体打印 [EVENT_RUN_MARKER]：`ModEventTriggerWiring.trigger` 走的
 * `ProcessRuntime.executeEvent` 会真执行事件函数体，因此该 marker 是「引擎真的派发了事件」
 * 的直接证据（不依赖 mod 处理器是否还挂在 registry 上）。
 */
internal const val GOOD_PKG = "mod.probe.good"
internal const val TYPO_PKG = "mod.probe.typo"
internal const val GOOD_EVENT = "EVENTFIRST"
internal const val TYPO_EVENT = "EVENTFIRSTT"
internal const val EVENT_RUN_MARKER = "EVENTFIRST-RAN"
internal const val ORIGINAL_RET = 42L
internal const val JOIN_MILLIS = 30_000L

private const val CANONICAL = "SIG1|I,-,r,0|ret:I"
private val fingerprint by lazy { BaseSignatureCatalog.fingerprint(CANONICAL) }

/** base 的三函数产物：ENTRY / TARGET / EVENTFIRST（后者打印派发 marker）。 */
internal fun baseProgram(): Program {
    val b = CodeBuilder()
    val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
    b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
    b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
    b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
    b.declareFunction("EVENTFIRST", b.here(), arity = 0, slotCount = 1)
    b.emit(Opcode._PRINT_LITERAL, b.string(EVENT_RUN_MARKER), 1, 0, 2)
    b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 3)
    return b.build(entry)
}

internal val baseMetas = listOf(
    FuncMeta(index = 0, key = "ENTRY"),
    FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
    FuncMeta(index = 2, key = GOOD_EVENT, isEvent = true),
)

/** 与父子波既有测试同形的 mod 链夹具（真容器 + 签名段侧车）。 */
internal fun writeChainFixtures(root: File) {
    val rt2 = File(root, "mods/rt2").apply { mkdirs() }
    File(rt2, "a.manifest").writeBytes(
        ManifestCodec.encode(
            ModuleDescriptor(packageId = "mod.a", overrideFunctions = setOf("TARGET")),
        ),
    )
    val modBuilder = CodeBuilder()
    val modEntry = modBuilder.declareFunction("TARGET", modBuilder.here(), arity = 1, slotCount = 1)
    modBuilder.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
    File(rt2, "a.erbm").writeBytes(
        ErbModuleContainer.encode(
            CompiledModule(
                "mod.a",
                modBuilder.build(modEntry),
                listOf(FuncMeta(index = 0, key = "TARGET", argIntCount = 1)),
                mapOf("TARGET" to fingerprint),
            ),
            allowSuperCalls = true,
        ),
    )
    for (pkg in listOf(GOOD_PKG, TYPO_PKG)) {
        File(rt2, "$pkg.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = pkg)),
        )
    }
    File(root, "erbc.v1/program.meta").apply { parentFile?.mkdirs() }
        .writeText("end\nsignatures 1\nsignature TARGET $CANONICAL\n")
}

internal fun inputs(root: File): ChainAssemblyInputs = ChainAssemblyInputs(
    gameRoot = root,
    enabled = true,
    base = baseProgram(),
    baseMetas = baseMetas,
    builtinVariableNames = { emptySet() },
    linkOrder = LinkOrder(),
)

internal fun outcome(root: File): ModChainAssemblyOutcome =
    checkNotNull(ModChainAssembly.assembleWithCatalog(inputs(root))) {
        "有合法容器 + manifest + 签名段必须真装配（旁路才返回 null）"
    }

/** 最小真会话：与生产 GameHost 同款的「base program + boot metadata + modChain」形态。 */
internal fun session(root: File, plan: ModChainRuntimePlan?): GameSession = GameSession(
    gameRoot = root.toPath(),
    program = baseProgram(),
    metadata = BootMetadata(functions = baseMetas),
    modChain = plan,
)

internal fun attach(
    root: File,
    store: VariableStore,
    logs: MutableList<String>,
    providers: List<Pair<String, EraMod>> = emptyList(),
    catalog: SymbolCatalog? = null,
): ModRuntimeSession? = ModRuntimeAssembly.attach(
    gameRoot = root,
    store = store,
    nativeRegistry = NativeMethodRegistry(),
    onVmThread = { true },
    enabled = true,
    logSink = { line -> logs += line },
    modProviders = providers,
    catalog = catalog,
)

/** 事件探针：注册一个事件处理器，命中时写 `probe-event:<key>` 日志行。 */
internal fun probeMod(eventKey: String, log: MutableList<String>): EraMod = object : EraMod {
    override fun onLoad(context: ModContext) {
        context.events.register(eventKey, EventBucket.NORMAL) {
            log += "probe-event:$eventKey"
            EventResult.CONTINUE
        }
    }

    override fun onEnable() = Unit

    override fun onDisable() = Unit

    override fun onUnload() = Unit
}

/**
 * 直接调 `ModEventTriggerWiring.trigger`：该类是 core-vm 的 `internal object`，app 侧唯一
 * 触达方式是反射（这也是「生产是否可达」的判据之一：mod 公共面在 shutdown 后已 fail-closed，
 * 无其它调用点）。方法名在字节码里带 internal 名字改写，故按「trigger 前缀 + 双参」定位。
 */
internal fun rawTrigger(store: VariableStore, eventKey: String): Boolean {
    val wiringClass = Class.forName("com.eratw.port.core.mod.ModEventTriggerWiring")
    val instance = wiringClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
    val method = wiringClass.declaredMethods.first {
        it.name.startsWith("trigger") && it.parameterCount == 2
    }.apply { isAccessible = true }
    return method.invoke(instance, store, eventKey) as Boolean
}

/** 读 `mod.events.trigger` 槽里 Handle 的 `target`（未解绑 ⇒ 非 null）。 */
internal fun triggerTargetOf(store: VariableStore): Any? {
    val handle = store.aux.peek<Any>("mod.events.trigger") ?: return null
    val field = handle.javaClass.declaredFields.first {
        it.name == "target" || it.name.endsWith("target")
    }.apply { isAccessible = true }
    return field.get(handle)
}

/** 读 app main 源码（工作树根或 IDE 下的 app 子目录两处布局都认）。 */
internal fun sourceOf(name: String): String {
    val direct = File("src/main/kotlin/com/eratw/port/android/$name")
    val nested = File("../app/src/main/kotlin/com/eratw/port/android/$name")
    val file = if (direct.isFile) direct else nested
    check(file.isFile) { "找不到源码 $name（工作目录=${File(".").absolutePath}）" }
    return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
}






