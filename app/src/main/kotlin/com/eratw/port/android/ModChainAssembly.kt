package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SymbolCatalog
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainAssembler
import com.eratw.port.core.mod.ModChainTrace
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.mod.ModuleContainerAdapter
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.ImportResolutionException
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleContainer
import com.eratw.port.core.patch.ModuleContainerCodec
import com.eratw.port.core.patch.ModuleContainerFormat
import com.eratw.port.core.patch.RelocationException
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.ModCsvCapacityLimitException
import com.eratw.port.core.vars.ModCsvLoadContext
import com.eratw.port.modapi.ModApi
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.platform.storage.ModStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.logging.Level
import java.util.logging.Logger

/**
 * 装配请求（L8 LPL-DEBT 形状收敛）：[ModChainAssembly.assemble] 的全部输入。
 *
 * 字段与收敛前的形参逐个同序同名、缺省值逐字保留 —— 参数对象只把七个形参收成一个
 * 请求体，不新增字段、不改缺省值、不改旁路/配对/链接语义：
 *
 * - [gameRoot]：游戏根目录（容器收集面、manifest 目录、`program.meta` 侧车都相对它解析）。
 * - [enabled]：总闸。false 时一个字节都不读（B1「一个对象都不建、磁盘都不碰」）。
 * - [base] / [baseMetas]：base 产物与运行期元数据（透传给核心装配器）。
 * - [builtinVariableNames]：宿主惰性供给的内置变量名（缺省恒空集 = 核心既有口径；
 *   惰性 lambda 在确认有容器、确需链接之前不求值）。
 * - [linkOrder]：玩家排序配置 → 链接显式排序（缺省 `LinkOrder()` = 核心零缺口）。
 * - [csvContext]：M1-4 阶段二单源（装载相位已冻结的声明快照与真实 CSV 容量）；
 *   缺省 null = 原有自装载口径。
 * - [logSink]：装配期留痕汇（缺省空 lambda = 既有直调点零行为）。**只承载整链级拒载的
 *   canonical 行留痕**，不改变装载/拒绝语义：整链拒发生在 `attach` 之前，而诊断面
 *   （`ModManageDiagnostics`）只消费 `mod.install … active=false failure=…` 行 ——
 *   不把这条线上报出去，玩家重启后设置页失败小节整节不渲染（Lane DP 修的缺口）。
 *   生产缝 = 宿主装配面复用 `ModRuntimeAssembly.attach` 的**同一个** logSink。
 */
data class ChainAssemblyInputs(
    val gameRoot: File,
    val enabled: Boolean,
    val base: Program,
    val baseMetas: List<FuncMeta>,
    val builtinVariableNames: () -> Set<String> = { emptySet() },
    val linkOrder: LinkOrder = LinkOrder(),
    val csvContext: ModCsvLoadContext? = null,
    val logSink: (String) -> Unit = {},
)

/**
 * 生产装配产物：运行期四视图计划 + 链接产物快照（[SymbolCatalog] 同一实例）。
 *
 * 为什么需要这个载体：SUPCAT-W 起 core-vm 的 `ModChainRuntimePlan` 已随链保留 catalog
 * 只读快照（`plan.catalog`，与本载体 [catalog] **同一实例**）；本载体仍存在的理由是
 * 事件名存在性闸的读缝（`ModRuntimeAssembly.attach` 的 `host.knownEventKeys`）走
 * `MOD_LINK_CATALOG_SLOT` 槽，写侧时序必须落在「会话 store 建好之后、`attach` 之前」
 * ——只有宿主（`GameHost` 拿到 `fresh.store` 之后）落得了，故装配点把快照原样交出来，
 * 由宿主经 [installCatalogSlot] 挂进 `store.aux`。entityRuntime 回填的
 * `EntityRuntime.catalog` 自 SUPCAT-W 起改走 attach inputs 供给（`GameHost` 传
 * `plan.catalog`），不再消费该槽。
 *
 * 纪律：快照只被共享引用——不重算、不二次装配、不复制链接语义（W2-SUPCAT 判据 3）。
 */
data class ModChainAssemblyOutcome(
    /** 送进 `GameSession` 的四视图计划（与 [ModChainAssembly.assemble] 返回值同一实例）。 */
    val plan: ModChainRuntimePlan,
    /** `ModChainAssembler` 第五步链接产物的 `LinkedProgram.catalog` 原样引用。 */
    val catalog: SymbolCatalog,
) {
    /**
     * MOD_LINK_CATALOG_SLOT 的**生产写侧**：会话 store 建好之后、`ModRuntimeAssembly.attach`
     * 之前调用（`RuntimeAux.slot` 的 create-if-absent；一个会话只写一次，快照生命周期随 store）。
     */
    fun installCatalogSlot(store: VariableStore) {
        store.aux.slot(MOD_LINK_CATALOG_SLOT) { catalog }
    }
}

/**
 * RT2 G 车第一批（P1-5）落下的 ERB Mod 代码载体装配缝，G2 波（余量一）在此接成**真装配**。
 *
 * ## 本波做了什么
 *
 * G 车第一批时 `ModChainAssembler.assemble` 的唯一生产调用者是本对象的**旁路判定**：
 * 开态发现 `.erbm` 即 `require` 整包拒绝。G2 波余量二把 `.erbm` 容器与零基线模块编译档
 * 落地后，[assemble] 才第一次把链接产物交给 `wave/f3` 已落 main 的核心层
 * [ModChainRuntimePlan] 派生出 `program / FunctionTable / dispatch` 运行期三视图，
 * 再由 `GameHost` 送进 `GameSession` 做**三表原子替换**。本波不重造三表值对象。
 *
 * ## 三条口径
 *
 * - **关态零接触**：`enabled=false` 时一个字节都不读（B1「一个对象都不建、磁盘都不碰」）。
 * - **两源全空完全旁路**：`mods/rt2/` 无 `.erbm` 且 `mods/` 根目录无探测命中的四池容器
 *   补丁 ⇒ 返回 null ⇒ `GameHost` 传给 `GameSession` 的 `modChain` 为 null ⇒ 运行期行为
 *   与 G 车第一批逐位一致（dispatch 恒 null、直调快路径）。**有界尾读 `program.meta`
 *   只在容器在场时发生**（RT2-ERBM 起随包有 `eratw.demo.erbm` 一件，关态不受影响；
 *   开态下这条旁路只覆盖「无任何容器」的旧包形状）。
 * - **有容器即真装配、任一坏档 fail-closed**：容器解码 / manifest 配对 / 档位 / 签名真目录
 *   / 容器补丁对账任一不满足均整包抛，不半装、不静默丢弃、不拿 arity 猜。ERTM1 配对是
 *   **单向**判据（RT2-ERBM 裁定①）：每 `.erbm` 必须找到同 packageId 的 manifest，缺失
 *   整包拒；反方向（manifest 无容器）是载体形态，链跳过——见 [assembleChain]。
 *   四池容器补丁**免 manifest**（CONTRACT-RT2-M2CODEC 裁定 1，容器自带
 *   ContainerDescriptor），坏档 / LINKED 态 / packageId 撞名同样整包拒。
 *
 * ## 签名真目录（生产消费点）
 *
 * [signatureDirectoryOfFile] 从 `program.meta` 尾部**有界**读 `signatures` 段（不整读
 * 57 MiB 侧车）→ [BaseSignatureCatalog.parse] → `signatureHashOf` →
 * `ModChainAssembler.assemble(baseSignatureOf = …)`。写侧自 P15S 起随标准产物落盘
 * （erbcc `CorpusCompile.kt:1694` 调 `SignatureExport.export`，`BootMetaWriter.text`
 * 的 `signatures` 实参非空即在 `end` 行之后追加旁路块），261111 产物实测侧车带
 * `signatures 118159` 且记录逐条 118,159 ⇒ 目录给的是**真指纹**，不是恒空桩。
 * 两个生产消费者共用本函数：装配期（这里，覆盖判定左轴）与导入期
 * （`ModImportController.ProductionBaseCompileContextSource`，决定同名补丁能不能声明成
 * 覆盖）。缺该段的旧档仍 fail-closed：目录空 ⇒ 同名补丁既声明不了覆盖、也被核心既有签名
 * 闸整包拒，不拿 arity 猜。
 *
 * ## 兼容面 [attach]
 *
 * 两参重载是 G 车第一批的哨兵口径（当时装配链未落，容器在场一律拒）。生产装配现在走
 * [assemble]；[attach] 只保留给无法提供 base/metas 的哨兵调用与既有定向用例，
 * **不许被读成「生产装配入口」**。
 *
 * TODO(verify)：`.erbm` 与 Mod 链装配是 RT2 自研（原版 Emuera 无 Mod 代码容器可比），
 * 语义权威 = 蓝图 §16.1 / §10 / §12.1 与 `LinkableModule` / `CONTRACT-RT2-P15R.md`。
 */
object ModChainAssembly {

    /** ERB 模块容器扩展名（erbcc `compile-module` 子命令产物）。 */
    private const val ERB_MODULE_EXT = ".erbm"

    /**
     * 坏 `.erbm` 容器留痕时写的异常名：必须等于该分支**实际抛出**的类型
     * （`IllegalArgumentException`）—— 诊断面把 `<异常名>: <原因>` 原样展示，写错即误导。
     */
    private const val CONTAINER_EXCEPTION_NAME = "IllegalArgumentException"

    /** 整链级拒载抛出的异常名（行内 `exception=` / canonical 的 `failure=` 前缀）。 */
    private const val ILLEGAL_ARGUMENT_EXCEPTION_NAME = "IllegalArgumentException"

    /** 整链级拒载没有单一磁盘文件可点名时，`mod.reject file=` 的取证源兜底标签。 */
    private const val CHAIN_SOURCE_UNKNOWN = "chain"

    /** 一条留痕行里的原因折成单行（异常消息按换行分段，logcat/设置页渲染只需摘要）。 */
    private val WHITESPACE_RUN = Regex(" +")

    /**
     * 留痕归因的形状闸：与 `ModManageDiagnostics` 的 `PACKAGE_ID` 正则、`ModEnablement` 的
     * `PACKAGE_ID_PATTERN` 同口径（两者都是私有，故本处复刻；口径三处字面量同源，改须同改）。
     * 只用于「文件名词干能不能当 packageId 写进 canonical 行」的判定，不改任何拒绝判据。
     */
    private val PACKAGE_ID_SHAPE = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")

    /** 四池容器补丁的 FuncMeta.position / 报错文案来源名前缀（`mods/<文件名>`）。 */
    private const val PATCH_SOURCE_PREFIX = "mods/"

    /** fail-closed 报错文案里逐字列出的容器样本数上限（防异常消息膨胀）。 */
    private const val FAIL_SAMPLE_COUNT = 3

    /** boot meta 尾读上界；签名段是尾部旁路块，8 MiB 足够容纳全量函数签名表。 */
    private const val META_TAIL_BYTES = 8L * 1024 * 1024

    /** 标准产物目录 / 侧车文件名（与 `GameHost.META_FILE` 同值）。 */
    private const val META_RELATIVE_PATH = "erbc.v1/program.meta"

    /** 纯 JDK logger：尾读失败要把异常留下证据，不能静默吞（detekt SwallowedException）。 */
    private val LOGGER: Logger = Logger.getLogger(ModChainAssembly::class.java.name)

    /**
     * boot meta 文本 → base 侧签名真查询函数（键大写 → 规范串 SHA-256 指纹）。
     * 无签名段（旧档）⇒ 空表 ⇒ 恒空串（fail-closed，与未接线前的缺省 `{ "" }`
     * 同值，但来源是**解析后的真目录**而非硬编码）。
     */
    fun signatureDirectoryOf(metaText: String): (String) -> String =
        BaseSignatureCatalog.signatureHashOf(BaseSignatureCatalog.parse(metaText))

    /**
     * 有界尾读 `program.meta` 的签名段。文件不存在 / 读失败 ⇒ 恒空串（fail-closed），
     * 不因侧车缺失而让会话装配整包炸；签名闸自己会拒绝覆盖。
     */
    fun signatureDirectoryOfFile(metaFile: File): (String) -> String {
        val tail = readTailOrNull(metaFile) ?: return { "" }
        return signatureDirectoryOf(tail)
    }

    /**
     * 侧车不存在 / IO 失败 ⇒ null ⇒ 目录恒空（fail-closed）。只捕 IO 异常，且把异常交给
     * logger 留下证据：不拿宽泛 `Exception` 盖掉编程错误，也不静默吞掉读失败。
     */
    private fun readTailOrNull(metaFile: File): String? {
        if (!metaFile.isFile) return null
        return try {
            readTailText(metaFile, META_TAIL_BYTES)
        } catch (e: IOException) {
            LOGGER.log(Level.FINE, "program.meta 尾读失败，base 签名目录恒空（fail-closed）", e)
            null
        }
    }

    /**
     * **生产装配入口**：开态、且两源（`mods/rt2/` 的 `.erbm` + `mods/` 根目录探测命中的
     * 四池容器补丁）任一在场时做非旁路真装配。
     *
     * 两源（CONTRACT-RT2-M2CODEC 裁定 1，统一走既有 `ModChainAssembler.assemble`，
     * LinkOrder 序）：
     * - **ERTM1 `.erbm`**（`mods/rt2/`）：manifest 单向配对纪律不变——每 `.erbm` 必须找到
     *   同 packageId 的 manifest（缺失整包拒），载体 manifest（无容器，如 `eratw.family`）
     *   链跳过——它的声明事务仍由 `ModRuntimeAssembly.attach` 全量加载，冲突检测不丢，
     *   只是不进五步链接链。
     * - **四池容器补丁**（`mods/` 根目录 `.patch` 且 `ModuleContainerFormat.probe` 命中）：
     *   **免 manifest**——容器自带 ContainerDescriptor，适配器经
     *   [ModuleContainerAdapter] 产出装配器输入（内存内 ManifestSource，不落盘）。
     *   单向配对仅约束 rt2 目录；packageId 与 ERTM1 manifest/容器或彼此撞名 = 身份冲突，
     *   整包拒（fail-closed 同 ERTM1 口径）。
     *
     * 开态入口在收集容器之前先跑 [ModPackageInventory.gcOrphans]（RT2 MODGC 裁定 3）：
     * `kojo-` 半成品（缺容器或 manifest）与「`.sources` 列出的留档源全不在」的失联包就地清掉，
     * 前者正是单向配对整包 fail-closed 的启动砖化来源；关态零接触、不扫不删。
     *
     * `builtinVariableNames` 由宿主惰性供给 `ModRuntimeAssembly.builtinVariableNames()`，
     * 与 Kotlin 载体声明事务同源，不让 Mod 全局维度撞引擎内置名时留下第二语义。
     * **惰性**是 B1 纪律的一部分：关态 / 两源全空完全旁路时连这份静态表都不建，
     * 求值只发生在确认有容器、确需链接之后。
     */
    fun assemble(inputs: ChainAssemblyInputs): ModChainRuntimePlan? = assembleWithCatalog(inputs)?.plan

    /**
     * 生产装配入口（[MOD_LINK_CATALOG_SLOT] 写侧的载体版本）：与 [assemble] 走同一段旁路判定
     * 与同一段真装配，只是把链接产物快照一并交出来。[assemble] 保持既有返回形状（既有调用点、
     * 既有测试零改动），宿主装配面用本函数取快照后经
     * [ModChainAssemblyOutcome.installCatalogSlot] 写槽。
     *
     * 旁路（关态 / 两源全空）返回 null —— 此时没有链接产物，槽也不该被写。
     *
     * 解码复用：开态生产序列里 [ModRuntimeAssembly.csvLoadContext] 已开启 ManifestCodec 的
     * 解码复用作用域（一次装配一份、线程本地），本函数读同一批 manifest 时命中复用，不再
     * 重复解析（见 `ManifestCodec` 类 KDoc「解码复用」节）。直调本函数（测试装置 / 旁路
     * 宿主）时作用域缺席，解码行为与既有口径逐位一致。
     */
    fun assembleWithCatalog(inputs: ChainAssemblyInputs): ModChainAssemblyOutcome? {
        // 别名只为让函数体与收敛前的逐参版本逐字一致（零行为变化：body 无改写）。
        val gameRoot = inputs.gameRoot
        val enabled = inputs.enabled
        val base = inputs.base
        val baseMetas = inputs.baseMetas
        val builtinVariableNames = inputs.builtinVariableNames
        val linkOrder = inputs.linkOrder
        val csvContext = inputs.csvContext
        // RT2 MODGC 裁定 3：开态先把 mods/rt2 的孤儿包（半成品 / 源失联）清掉，再收集容器。
        // 执行点必须在容器被读取之前：半成品 `.erbm` 无 manifest 会让 assembleChain 的
        // 单向配对整包 fail-closed（启动砖化）。关态零接触（B1），不扫不删。
        if (enabled) {
            ModPackageInventory.gcOrphans(
                rt2Dir = ModRuntimeAssembly.manifestDirFor(gameRoot),
                modsDir = ModStore.modsDirFor(gameRoot),
            )
        }
        // 单出口：关态两源收集面都不调（B1 零接触），两源全空完全旁路。
        val erbmFiles = if (enabled) collectErbModules(gameRoot) else emptyList()
        val patchContainerFiles = if (enabled) collectPatchContainers(gameRoot) else emptyList()
        return if (erbmFiles.isEmpty() && patchContainerFiles.isEmpty()) {
            null
        } else {
            assembleChain(inputs, erbmFiles, patchContainerFiles)
        }
    }

    /** 确认有容器之后的真装配体；与 [assembleWithCatalog] 拆开是为了把旁路判定留在唯一入口。 */
    private fun assembleChain(
        inputs: ChainAssemblyInputs,
        erbmFiles: List<File>,
        patchContainerFiles: List<File>,
    ): ModChainAssemblyOutcome {
        // 别名只为让函数体与收敛前的逐参版本逐字一致（零行为变化：body 无改写）。
        val gameRoot = inputs.gameRoot
        val base = inputs.base
        val baseMetas = inputs.baseMetas
        val builtinVariableNames = inputs.builtinVariableNames
        val linkOrder = inputs.linkOrder
        val csvContext = inputs.csvContext
        val logSink = inputs.logSink
        val compiled = decodeErbmContainers(erbmFiles, logSink)

        // 单向配对（RT2-ERBM 裁定①）：判据只压「每 .erbm 必须找到同 packageId 的 manifest」
        // 这一个方向，缺失整包拒（fail-closed 保留，与旧「packageId 不齐整包拒」同形）；
        // 反方向（manifest 无容器）是**载体形态**（如 eratw.family），链跳过——它的声明
        // 事务仍由 ModRuntimeAssembly.attach 全量加载，冲突检测不丢。传给核心五步链的
        // 两组集合因此等数（ModChainAssembler.pairAndValidate 判据零改动，看到的仍是等数集）。
        val sourceByPkg = readManifestSources(ModRuntimeAssembly.manifestDirFor(gameRoot), logSink)

        val patches = decodePatchContainers(patchContainerFiles, sourceByPkg, compiled, logSink)

        val paired = pairSources(compiled, sourceByPkg, patches, logSink)

        val baseSignatureOf = signatureDirectoryOfFile(File(gameRoot, META_RELATIVE_PATH))
        val manifestFileNames = paired.sources.map { it.name }
        val chain = try {
            ModChainAssembler.assemble(
                sources = paired.sources,
                compiledModules = paired.modules,
                base = base,
                baseMetas = baseMetas,
                builtinVariableNames = builtinVariableNames(),
                csvTableCapacities = csvContext?.csvTableCapacities ?: emptyMap(),
                baseCsvKeywords = csvContext?.baseCsvKeywords ?: emptyMap(),
                baseSignatureOf = baseSignatureOf,
                linkOrder = linkOrder,
                snapshot = csvContext?.snapshot,
                // 2026-11-23 KI 改判（BOARD KI-2）：撞名三态告警汇进装配留痕
                //（trace.overrideDiagnostics → logSink，与拒载/告警同一汇）。
                trace = ModChainTrace().apply { overrideDiagnostics = logSink },
            )
        } catch (e: DeclarationConflictException) {
            // 声明事务冲突：冲突带双边 DeclarationSite.packageId ⇒ 归因唯一，逐包 canonical 行。
            rethrowTraced(logSink, manifestFileNames, e.packageIds, e)
        } catch (e: LinkOrderException) {
            // 链接定序拒载：packageIds 空 = 配置面错误（如显式排序配置重复），走 mod.reject。
            rethrowTraced(logSink, manifestFileNames, e.packageIds, e)
        } catch (e: ImportResolutionException) {
            // 导入无法解析（ExternalImportTable.resolve：缺提供者 / 导入重复 / 事件名）：
            // packageId = 导入方包，包面唯一可信归因。
            rethrowTraced(logSink, manifestFileNames, listOf(e.packageId), e)
        } catch (e: RelocationException) {
            // _CALL.a 决议失败（CALL_SUPER 缺前实现 / 导入哨兵越域 / 净偏移越界）：
            // 异常只有诊断消息、无结构化包归因字段 ⇒ mod.reject 显式 tag 取证（与 IAE 兜底支同口径）。
            rethrowTraced(logSink, manifestFileNames, emptyList(), e)
        } catch (e: IllegalArgumentException) {
            // 其余整链 IAE（核心 pairAndValidate 的配对不齐 / packageId 重复等）：消息内嵌包名
            // 但无可信结构化归因源 ⇒ mod.reject 显式 tag 取证，不进设置页。
            rethrowTraced(logSink, manifestFileNames, emptyList(), e)
        }
        // W2-SUPCAT 判据 3：HAS_SUPER 判据源（specs/builtin-functions.yaml 的 HAS_SUPER 条目）
        // 就是这里的 chain.linked.catalog（ModChainAssembler 第五步链接产物）。写侧时序约束：
        // 事件名存在性闸读缝（host.knownEventKeys）只在 store 建好之后、attach 之前读槽，
        // 而 store 在 GameSession 里才建、本函数先于它执行 —— 装配点写不了槽，只能把同一
        // 实例经 [ModChainAssemblyOutcome.catalog] 交出去，由宿主在 fresh 构造后调
        // [ModChainAssemblyOutcome.installCatalogSlot] 写槽。entityRuntime 回填的
        // EntityRuntime.catalog 自 SUPCAT-W 起改走 attach inputs 供给（Plan.catalog 保留 +
        // GameHost 传 plan.catalog），不再读槽。快照只被共享引用：不重算、
        // 不二次装配、不复制链接语义。
        // 核心层只在「无 Mod / 无模块」旁路时返回 null。走到这里说明两源至少一源在场，
        // 若仍返回 null 就是装配器不变量被破坏：必须整包拒，绝不悄悄退回 null 让
        // GameSession 按 base 三表继续跑（那会变成「容器在盘上但行为没变」的假接通）。
        val plan = requireNotNull(ModChainRuntimePlan.of(chain)) {
            "ERB 模块容器在场但链接产物为旁路态 —— 装配器不变量破坏，fail-closed 整包拒绝"
        }
        // of() 已要求 linked 非空；此处再取一次引用只为拿 catalog 本体（同一实例，零重算）。
        val linked = requireNotNull(chain.linked) {
            "ERB 模块容器在场但链接产物缺 LinkedProgram —— 装配器不变量破坏，fail-closed 整包拒绝"
        }
        return ModChainAssemblyOutcome(plan, linked.catalog)
    }

    /**
     * ERTM1 `.erbm` 容器解码（判据只压「坏档 / 非 Mod 编译档一律整包拒」）：
     * `allowSuperCalls=false` 的字节与坏档都不许穿透到链接链。
     *
     * ## 整链级拒载留痕（Lane DP 起；本函数 `allowSuperCalls=false` 一支 Lane DS 补齐）
     *
     * 本函数的两类拒载（坏档 / `allowSuperCalls=false`）都发生在 `attach` 之前、整链
     * fail-closed，会话侧 canonical 行生产者（[ModRuntimeAssembly.attach] 内按 install 报告
     * 逐包写 `mod.install …`）根本走不到 —— 玩家重启后诊断面与设置页失败小节是空的，只能
     * 靠失败屏一次性看。故两类都在 `throw` **之前**按包级补一条与会话侧逐字同形的
     * canonical 行（[logRejectedInstall]），只记账、不改判据/异常类型/消息/时机。
     *
     * 两类归因口径不同：坏档字节解不开 ⇒ 容器内没有可读 packageId，只能取文件名词干
     * （形状不合时写显式占位，见 [erbmPackageIdOf]）；已解出的容器（`allowSuperCalls=false`）
     * ⇒ 用容器自述 [ErbModuleContainer.ErbModule.packageId]（唯一确定，与会话侧同键）。
     */
    private fun decodeErbmContainers(
        erbmFiles: List<File>,
        logSink: (String) -> Unit,
    ): List<Pair<File, CompiledModule>> =
        erbmFiles.map { file ->
            val container = try {
                ErbModuleContainer.decode(file.readBytes())
            } catch (e: IllegalArgumentException) {
                val reason = "ERB 模块容器 [${file.name}] 坏档或来源不明（G 车余量二 `compile-module` " +
                    "产线之外）—— fail-closed 整包拒绝：${e.message}"
                // 字节解不开 ⇒ packageId 只能尽力归因（文件名词干）：合形状才进 canonical 行；
                // 不合形状走 `mod.reject` 显式 tag，绝不伪造一个看似合法的包名（见 [erbmPackageIdOf]）。
                val packageId = erbmPackageIdOf(file)
                if (packageId != null) {
                    logRejectedInstall(logSink, packageId, reason, CONTAINER_EXCEPTION_NAME)
                } else {
                    logUnattributedReject(logSink, file.name, reason, CONTAINER_EXCEPTION_NAME)
                }
                throw IllegalArgumentException(reason, e)
            }
            if (!container.allowSuperCalls) {
                val reason = "ERB 模块容器 [${file.name}] 不是 Mod 包编译档（allowSuperCalls=false）" +
                    " —— fail-closed 整包拒绝"
                // 容器已解出 packageId：归因取容器自述值，不取文件名词干（唯一确定、与
                // 会话侧 canonical 行同键）。与坏档支同一出口、同形状行；抛的仍是 IAE。
                logRejectedInstall(logSink, container.packageId, reason, CONTAINER_EXCEPTION_NAME)
                throw IllegalArgumentException(reason)
            }
            file to container.toCompiledModule()
        }

    /**
     * 坏 `.erbm` 容器的包级归因：字节解不开时容器里没有可读的 packageId
     * （[ErbModuleContainer.decode] 先核尾部摘要、后读包名），唯一同源线索是文件名词干 ——
     * 生产写侧恒定 `mods/rt2/<packageId>.erbm`（导入落盘名、重编译原位替换名、卸载删除名、
     * 库存成员后缀解析四处同源）。**只用于留痕归因**：不改解码、不放宽拒绝判据。
     *
     * ## 归因保真度契约（Lane DS）
     *
     * 词干只有合 packageId 形状（[PACKAGE_ID_SHAPE]，与 `ModManageDiagnostics` 的解析形状闸、
     * `ModEnablement` 的拼路径校验同口径）时才算**尽力归因**、返回词干并写 canonical 行；
     * 不合形状时返回 null，调用方改走 [logUnattributedReject] 的 `mod.reject` 显式 tag ——
     * **不伪造一个看似合法的包名**（旧实现直接写词干，`pkg=坏档` 会被读成「包名叫坏档」，
     * 与「归因不可靠」的事实相反）。
     *
     * 文件名合形状但非真包名的旧盘面（如 `legacy_name.erbm`）照旧取词干：形状闸放行、
     * 设置页显示名退化为词干（`titles[pkg] ?: pkg` 兜底），比「整条丢失」强 —— 该边界由
     * `ModChainAssemblyRejectTraceTest` 用例 6/7 钉住。
     */
    private fun erbmPackageIdOf(file: File): String? {
        val stem = file.name.removeSuffix(ERB_MODULE_EXT)
        return if (PACKAGE_ID_SHAPE.matches(stem)) stem else null
    }

    /**
     * 归因不可靠的整链拒载留痕（Lane DS）：**刻意不写** `mod.install pkg=…` canonical 形状
     * （那会让诊断面把一个伪造包名收进设置页失败小节），改写成显式 tag
     * `mod.reject file=<磁盘文件名> reason=<原因> exception=<实际异常类型>`：
     * 前缀不是 `mod.install pkg=`，故 `ModManageDiagnostics` 按既有判定规则 4 静默忽略，
     * 而启动失败屏（异常消息原样）与 logcat（`Log.i` 转写）仍给完整归因。契约同步登记在
     * `ModManageDiagnostics` 类 KDoc。
     *
     * 与 [logRejectedInstall] 同一纪律：只在抛之前记账、不参与裁决、任何异常就地兜住。
     */
    private fun logUnattributedReject(
        logSink: (String) -> Unit,
        fileName: String,
        reason: String,
        exceptionName: String,
    ) {
        runCatching {
            logSink("mod.reject file=$fileName reason=$reason exception=$exceptionName")
        }.onFailure {
            LOGGER.log(Level.FINE, "整链拒载（不可归因）留痕写入失败（不影响原拒载行为）：file=$fileName", it)
        }
    }

    /**
     * 整链拒载的**通用归因出口**（Lane DW，task-121）：给带只读 `packageIds` 的核心异常
     * （[DeclarationConflictException] / [LinkOrderException]）用。[packageIds] 里合
     * packageId 形状的包逐个写 canonical 行（与会话侧同形）；一个都不可用时改走
     * [logUnattributedReject] 的 `mod.reject` 显式 tag —— 与 [erbmPackageIdOf] 同一条
     * 归因保真纪律：**不伪造一个看似合法的包名**（配置面错误不指向盘上任何包）。
     *
     * [diskNames] 是本次装配链上已读入的 manifest 磁盘文件名，用于不可归因时的取证
     * 源点名（`mod.reject file=`）；只在真的需要写 mod.reject 时才求值摘要。
     *
     * 与既有两出口同纪律：只在抛之前记账、不参与裁决，[logSink] 异常就地兜住。
     */
    private fun traceChainReject(
        logSink: (String) -> Unit,
        diskNames: List<String>,
        packageIds: List<String>,
        reason: String,
        exceptionName: String,
    ) {
        val singleLine = singleLineReason(reason)
        val attributable = packageIds.filter { PACKAGE_ID_SHAPE.matches(it) }.distinct()
        if (attributable.isEmpty()) {
            logUnattributedReject(logSink, diskNamesSummary(diskNames), singleLine, exceptionName)
        } else {
            for (packageId in attributable) {
                logRejectedInstall(logSink, packageId, singleLine, exceptionName)
            }
        }
    }

    /** 原因折单行：换行/回车 → 空格，连续空格合一（异常消息的排版不进日志行）。 */
    private fun singleLineReason(raw: String): String =
        WHITESPACE_RUN.replace(raw.replace('\n', ' ').replace('\r', ' '), " ").trim()

    /** 不可归因留痕的 `file=` 取值：磁盘文件名逐字，样本超 [FAIL_SAMPLE_COUNT] 截断计数。 */
    internal fun diskNamesSummary(diskNames: List<String>): String =
        when {
            diskNames.isEmpty() -> CHAIN_SOURCE_UNKNOWN
            diskNames.size <= FAIL_SAMPLE_COUNT -> diskNames.joinToString(",")
            else -> diskNames.take(FAIL_SAMPLE_COUNT).joinToString(",") +
                "…(+${diskNames.size - FAIL_SAMPLE_COUNT})"
        }

    /**
     * 整链级拒载的**记账 + 抛出**合并出口（Lane DW）：先按归因纪律留痕，再抛
     * [IllegalArgumentException]（类型/消息由调用方给定，异常名按实际类型写进行内）。
     *
     * 合并的原因：detekt 的 `ThrowsCount` 按函数计，逐点在业务函数里 `throw` 会把
     * `assembleChain` / `readManifestSources` 推过阈值；留痕出口集中到一处后，业务函数
     * 只表达「此处整包拒 + 取证源」，异常构造只有一份。
     */
    private fun rejectChain(
        logSink: (String) -> Unit,
        diskNames: List<String>,
        packageIds: List<String>,
        reason: String,
        cause: Throwable? = null,
    ): Nothing {
        traceChainReject(logSink, diskNames, packageIds, reason, ILLEGAL_ARGUMENT_EXCEPTION_NAME)
        throw if (cause == null) IllegalArgumentException(reason) else IllegalArgumentException(reason, cause)
    }

    /**
     * 留痕后**原样重抛同一异常实例**（Lane DW）：类型/消息/时机不变，调用方只负责给出取证源。
     * 把 `throw` 收到本函数里，业务函数（如 `assembleChain`）才能只表达归因语义。
     */
    private fun rethrowTraced(
        logSink: (String) -> Unit,
        diskNames: List<String>,
        packageIds: List<String>,
        e: RuntimeException,
    ): Nothing {
        traceChainReject(logSink, diskNames, packageIds, e.message.orEmpty(), e.javaClass.simpleName)
        throw e
    }

    /**
     * 坏 manifest 字节的**记账 + 原样重抛**（Lane DW）：坏字节解不出 packageId、消息只点名
     * 行号 ⇒ 取证源只能钉磁盘文件名（`mod.reject`），异常实例原样透传（类型/消息/时机不变）。
     */
    private fun rejectBadManifest(logSink: (String) -> Unit, fileName: String, e: ManifestFormatException): Nothing {
        logUnattributedReject(
            logSink,
            fileName,
            "manifest 字节坏档：${e.message.orEmpty()}",
            e.javaClass.simpleName,
        )
        throw e
    }

    /**
     * 装载前置段（`csvLoadContext` 的 `ManifestLoader.load`）声明冲突的留痕出口（Lane DY）。
     *
     * 与链内 [rethrowTraced] 走**同一个** [traceChainReject] 实现：同一条异常形态不论从
     * 前置段还是链装配抛出，写出的行文本逐字一致（归因面 = 异常自带的只读 `packageIds`）。
     * 异常实例原样重抛 —— 类型/消息/时机不变，玩家仍看到启动失败屏。
     */
    internal fun tracePreloadReject(
        logSink: (String) -> Unit,
        diskNames: List<String>,
        e: DeclarationConflictException,
    ): Nothing = rethrowTraced(logSink, diskNames, e.packageIds, e)

    /**
     * 装载前置段（`csvLoadContext` 的 `ModCsvPlanLoader.load` 容量推导）CSV 注入位越界的
     * 留痕出口（M2-BUG-02）：与链内 [rethrowTraced] 走**同一个** [traceChainReject] 实现。
     *
     * [ModCsvCapacityLimitException] 的 `reasons` 是**表级**违规文本（「表 X：Base=…，声明要求
     * …」），不带结构化包归因字段——是哪个 manifest 把表推过硬上界，异常本身答不出来。
     * 按归因纪律与 [RelocationException]/IAE 兜底支同形：无结构化归因 ⇒ [rethrowTraced] 的
     * `packageIds` 传空表 ⇒ 走 `mod.reject file=<磁盘清单摘要> reason=<违规原文>
     * exception=ModCsvCapacityLimitException` 显式 tag 取证（按 `ModManageDiagnostics` 规则 4
     * 刻意不进设置页失败小节——「伪造一个看似合法的包名」比「少一行记录」更坏）。
     * 异常实例原样重抛 —— 类型/消息/时机不变，玩家仍看到启动失败屏。
     */
    internal fun tracePreloadCapacityReject(
        logSink: (String) -> Unit,
        diskNames: List<String>,
        e: ModCsvCapacityLimitException,
    ): Nothing = rethrowTraced(logSink, diskNames, emptyList(), e)

    /**
     * 装载前置段坏 manifest 字节的留痕出口（Lane DY）：与链内 [rejectBadManifest] 同一实现，
     * 出口只负责「已定位到的磁盘文件名」；异常实例原样重抛。
     */
    internal fun tracePreloadBadManifest(
        logSink: (String) -> Unit,
        fileName: String,
        e: ManifestFormatException,
    ): Nothing = rejectBadManifest(logSink, fileName, e)

    /**
     * 整链级拒载的逐包 canonical 行留痕：与会话侧 [ModRuntimeAssembly] 的生产格式逐字
     * 同形（`mod.install pkg=<pkg> active=false failure=<异常名>: <原因>`），故
     * `ModManageDiagnostics` 既有解析器无需改动即可收入摘要、设置页失败小节可渲染。
     *
     * 只在抛之前记账、不参与裁决：[logSink] 由宿主接线（测试可注入），实现方任何异常都
     * 不得改变原拒载的类型/消息/时机，故一律就地兜住并留 FINE 证据。
     *
     * [exceptionName] 必须与**实际抛出**的异常类型一致（诊断面把 `<异常名>: <原因>` 原样
     * 展示给玩家/取证者，写错就等于误导：坏容器抛的是 IAE 却被报成 LinkOrderException）。
     */
    private fun logRejectedInstall(
        logSink: (String) -> Unit,
        packageId: String,
        reason: String,
        exceptionName: String = "LinkOrderException",
    ) {
        runCatching {
            logSink("mod.install pkg=$packageId active=false failure=$exceptionName: $reason")
        }.onFailure {
            LOGGER.log(Level.FINE, "整链拒载留痕写入失败（不影响原拒载行为）：pkg=$packageId", it)
        }
    }

    /**
     * 单向配对（RT2-ERBM 裁定①）的 manifest 侧：包名 → 源；重复包名 = 身份冲突整包拒。
     * 反方向（manifest 无容器）是**载体形态**（如 eratw.family），链跳过——它的声明事务
     * 仍由 ModRuntimeAssembly.attach 全量加载，冲突检测不丢。
     *
     * ## 引擎 API 版本闸（ModDX-2 DX2-B）
     *
     * 逐 manifest 判 `minApi > [ModApi.VERSION]`（[com.eratw.port.core.patch.ModuleDescriptor.minApi]，
     * 缺省 0 = 不设闸）⇒ [LinkOrderException] **整链拒载**，消息逐包归因 = 包名 + 需要的
     * min-api + 当前 VERSION（多包越闸按 manifest 文件名序同列）。放本函数的原因：这里是
     * 装配链中唯一同时见到**全部**启用 manifest（含载体形态）的解码点，且早于链接与配对
     * —— 超版包在任何下游失败之前就得到「需要更新 app」的明确归因，而不是莫名装载失败
     * （对标 SMAPI `minApiVersion`）。
     *
     * **异常类型裁定 = 复用 [LinkOrderException] 家族**：本闸与 requires 缺失同处装配裁决
     * 相位、同口径整链 fail-closed（缺依赖路径就是该异常，且已穿透生产装配路径），复用
     * 不为调用方新增异常面；本闸本身不是定序失败，特此记明。
     *
     * **已知边界**（契约把裁决放 ModChainAssembly 的结构后果，需扩闸时由组长接线）：
     * `mods/rt2/` 无任何 `.erbm` 容器（纯载体组合）时 [assembleWithCatalog] 完全旁路，
     * 本函数不被调到，闸不覆盖；四池容器补丁免 manifest（ContainerDescriptor 无 min-api
     * 载体），同样不在闸内。
     *
     * **整链级拒载留痕（Lane DP）**：本闸的拒载发生在 `attach` 之前整链 fail-closed，
     * 会话侧 canonical 行生产者走不到，故在 `throw` **之前**逐包补一条与会话侧逐字同形的
     * `mod.install …` 行（[logRejectedInstall]）；多包同时越闸 ⇒ 逐包各一条。只记账，
     * 不改判据/异常类型/消息/时机。
     */
    private fun readManifestSources(
        manifestDir: File,
        logSink: (String) -> Unit,
    ): LinkedHashMap<String, ManifestSource> {
        val sourceByPkg = LinkedHashMap<String, ManifestSource>()
        val overMinApi = LinkedHashMap<String, Pair<String, Int>>()
        for (file in manifestFilesIn(manifestDir)) {
            val bytes = file.readBytes()
            val descriptor = try {
                ManifestCodec.decode(bytes)
            } catch (e: ManifestFormatException) {
                // 坏字节在解码期就抛，消息只点名行号、**不含文件名也不含 packageId**（这里
                // 根本解不出包名）⇒ 取证源只能钉磁盘文件名：走 mod.reject 显式 tag，不伪造
                // 包名（归因保真纪律同 [erbmPackageIdOf]）。类型/消息/时机原样透传。
                rejectBadManifest(logSink, file.name, e)
            }
            val pkg = descriptor.packageId
            if (descriptor.minApi > ModApi.VERSION) {
                overMinApi[pkg] = file.name to descriptor.minApi
            }
            val previous = sourceByPkg.put(pkg, ManifestSource(file.name, bytes))
            if (previous != null) {
                // 同包名两份 manifest：包名本身唯一可归因（两份都装不上），逐包行 + 原消息。
                rejectChain(
                    logSink,
                    listOf(previous.name, file.name),
                    listOf(pkg),
                    "RT2 Mod 链配对不齐：manifest 包名重复 $pkg（${file.name}）" +
                        " —— fail-closed 整包拒绝",
                )
            }
        }
        if (overMinApi.isNotEmpty()) {
            // 逐包归因只算一次：留痕行与拒载消息共用同一份字句（同源才不会漂）。
            val reasons = overMinApi.entries.map { (pkg, source) ->
                pkg to (
                    "包 [$pkg]（${source.first}）的 manifest 声明 min-api ${source.second}，" +
                        "高于当前引擎 ModApi.VERSION ${ModApi.VERSION}"
                    )
            }
            for ((pkg, reason) in reasons) {
                logRejectedInstall(logSink, pkg, reason)
            }
            throw LinkOrderException(
                reasons.joinToString(
                    separator = "；",
                    prefix = "RT2 Mod 链拒载（引擎 API 版本闸，与缺依赖同口径 fail-closed）：",
                    postfix = " —— 请更新 app 后重试",
                ) { it.second },
            )
        }
        return sourceByPkg
    }

    /**
     * 四池容器补丁（裁定 1 第二源，免 manifest）解码与身份对账：坏字节 / LINKED 态 /
     * 描述符非法一律整包拒（fail-closed 同 ERTM1 口径）；packageId 与 ERTM1 manifest/容器
     * 或彼此撞名 = 身份冲突，整包拒。适配产物 = 内存内 ManifestSource + CompiledModule
     * （ModuleContainerAdapter KDoc 有源名与不落盘说明）。
     */
    private fun decodePatchContainers(
        patchContainerFiles: List<File>,
        sourceByPkg: Map<String, ManifestSource>,
        compiled: List<Pair<File, CompiledModule>>,
        logSink: (String) -> Unit,
    ): ChainSources {
        val patchSources = ArrayList<ManifestSource>(patchContainerFiles.size)
        val patchModules = ArrayList<CompiledModule>(patchContainerFiles.size)
        for (file in patchContainerFiles) {
            val container = decodePatchContainer(file, logSink)
            // 适配期拒载同口径留痕（M2-BUG-03）：容器 decode 已过（头部合法），但载荷适配
            // （非 MODULE 态 / 载荷不是合法零基线 .erbc / 自描述与载荷脱节 / 描述符非法）
            // 同样发生在 attach 之前的整链 fail-closed 相位，会话侧 canonical 行生产者走不到
            // —— 不留痕则玩家重启后设置页失败小节整节不渲染（M2-BUG-01/02 同症状类）。
            // 本函数的 catch 只包 decode；adapt 不包则其 IAE 裸穿 assembleChain 的整链 try
            // （那层只包 ModChainAssembler.assemble），而 GameHost 对 assembleWithCatalog
            // 刻意不外包（内部已有整链 catch，再包会双份留痕）⇒ 零留痕。
            // 归因取容器自述 packageId（decode 已成功、唯一可读；形状闸在 traceChainReject
            // 里滤不可信值，不合形状自动降级 mod.reject 取证行，不伪造包名）。
            val adapted = try {
                ModuleContainerAdapter.adapt(container, PATCH_SOURCE_PREFIX + file.name)
            } catch (e: Exception) {
                rejectChain(
                    logSink,
                    listOf(file.name),
                    listOf(container.descriptor.packageId),
                    "四池容器补丁 [${file.name}] 载荷适配失败（非 MODULE 态 / 载荷不是合法零基线 " +
                        ".erbc / 容器自描述与载荷脱节 / 描述符非法）—— fail-closed 整包拒绝：${e.message}",
                    e,
                )
            }
            val pkg = adapted.compiled.packageId
            val manifestHolder = sourceByPkg[pkg]
            if (manifestHolder != null) {
                rejectChain(
                    logSink,
                    listOf(file.name),
                    listOf(pkg),
                    "四池容器补丁 [${file.name}] 的 packageId $pkg 与 RT2 manifest" +
                        " [${manifestHolder.name}] 撞名（容器补丁免 manifest，包名必须自持）" +
                        " —— fail-closed 整包拒绝",
                )
            }
            if (compiled.any { it.second.packageId == pkg }) {
                rejectChain(
                    logSink,
                    listOf(file.name),
                    listOf(pkg),
                    "四池容器补丁 [${file.name}] 的 packageId $pkg 与 ERTM1 .erbm 容器撞名" +
                        " —— fail-closed 整包拒绝",
                )
            }
            if (patchModules.any { it.packageId == pkg }) {
                rejectChain(
                    logSink,
                    listOf(file.name),
                    listOf(pkg),
                    "四池容器补丁的 packageId 重复 $pkg（${file.name}）—— fail-closed 整包拒绝",
                )
            }
            patchSources += adapted.source
            patchModules += adapted.compiled
        }
        return ChainSources(patchSources, patchModules)
    }

    /**
     * 单文件容器 decode：坏档 / 来源不明的字节整包拒（fail-closed 同 ERTM1 口径）。
     *
     * 宽兜底是刻意的（detekt TooGenericExceptionCaught 已登记质量债）：来源不明的字节可能从
     * decode 的任一读侧闸抛任意 RuntimeException，这类字节必须一律整包拒、不得穿透到装配器；
     * 与 ModPatchLoader 既有口径同源。
     */
    private fun decodePatchContainer(file: File, logSink: (String) -> Unit): ModuleContainer {
        return try {
            ModuleContainerCodec.decode(file.readBytes())
        } catch (e: Exception) {
            rejectChain(
                logSink,
                listOf(file.name),
                emptyList(),
                "四池容器补丁 [${file.name}] 坏档或来源不明（M2-4 转码产线之外的手工字节）" +
                    " —— fail-closed 整包拒绝：${e.message}",
                e,
            )
        }
    }

    /**
     * 配对等数集：每 `.erbm` 必须找到同包名 manifest（缺失整包拒）。载体 manifest 被链跳过
     * 这一事实打 FINE 留痕，不让「manifest 在场却没进链接」成为无解释的静默路径
     * （AGENTS §6.1 口径）。
     */
    private fun pairSources(
        compiled: List<Pair<File, CompiledModule>>,
        sourceByPkg: Map<String, ManifestSource>,
        patches: ChainSources,
        logSink: (String) -> Unit,
    ): ChainSources {
        val pairedSources = ArrayList<ManifestSource>(compiled.size + patches.sources.size)
        for ((file, module) in compiled) {
            val source = sourceByPkg[module.packageId]
            if (source == null) {
                // 容器里的 packageId 是唯一可归因来源（缺失的 manifest 可能压根不在盘上），
                // 逐包 canonical 行后原样整包拒。
                rejectChain(
                    logSink,
                    listOf(file.name),
                    listOf(module.packageId),
                    "RT2 Mod 链配对不齐：容器 [${file.name}] 的 packageId" +
                        " ${module.packageId} 找不到同包名 manifest —— fail-closed 整包拒绝",
                )
            }
            pairedSources += source
        }
        pairedSources += patches.sources
        val pairedModules = compiled.map { it.second } + patches.modules
        val containerPkgs = compiled.map { it.second.packageId }.toSet()
        for ((pkg, source) in sourceByPkg) {
            if (pkg !in containerPkgs) {
                LOGGER.fine(
                    "RT2 载体 manifest [$pkg]（${source.name}）无同包名 .erbm 容器，" +
                        "五步链接链跳过；声明事务仍由 ModRuntimeAssembly.attach 全量加载",
                )
            }
        }
        return ChainSources(pairedSources, pairedModules)
    }

    /** 容器侧输入两份集合（源 + 编译档）：核心装配器的两组输入必须等数。 */
    private class ChainSources(
        val sources: List<ManifestSource>,
        val modules: List<CompiledModule>,
    )

    /**
     * G 车第一批的两参哨兵（兼容面）：没有 base/metas 就无法真装配，容器在场一律
     * fail-closed 整包拒绝。生产路径已改用 [assemble]。
     */
    fun attach(gameRoot: File, enabled: Boolean) {
        if (!enabled) return
        val modules = collectErbModules(gameRoot)
        for (file in modules) {
            try {
                ErbModuleContainer.decode(file.readBytes())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "ERB 模块容器 [${file.name}] 坏档或来源不明 —— G 车 fail-closed 整包拒绝：" +
                        "${e.message}",
                    e,
                )
            }
        }
        require(modules.isEmpty()) {
            "发现 ${modules.size} 个 ERB 模块容器（$ERB_MODULE_EXT）但本次调用未提供装配上下文" +
                "（base / baseMetas）—— G 车 P1-5 非旁路装配必须走 assemble，" +
                " fail-closed 整包拒绝：${modules.take(FAIL_SAMPLE_COUNT).joinToString()}"
        }
    }

    private fun collectErbModules(gameRoot: File): List<File> {
        val dir = File(gameRoot, "mods/rt2")
        if (!dir.isDirectory) return emptyList()
        val modules = dir.listFiles { f -> f.isFile && f.name.endsWith(ERB_MODULE_EXT) }
            ?.sortedBy { it.name }
            ?: emptyList()
        // 批2-3：停用的包连容器都不进装配面。判据按文件名词干（= packageId）取，
        // 与 ModEnablement 的标记命名同源；manifest 那一侧在 assembleChain 里同步摘掉
        // （单向配对纪律要求两侧一起消失，只摘一边会变成启动砖化）。
        return ModEnablement.enabledOnly(dir, modules)
    }

    /**
     * 收集 mods/ 根目录里的四池容器补丁候选（裁定 1 第二源）：`.patch` 扩展名
     * （与 [ModPatchLoader.hasPatchExtension] 同一口径，含大小写不敏感与隐藏文件排除）
     * 且 [ModuleContainerFormat.probe] 命中。先探后读：只整读前缀
     * [ModuleContainerFormat.PREFIX_BYTES] 字节做探测，未命中（legacy 补丁 / 其它文件）
     * 一个载荷字节都不碰 —— legacy 件归 [ModPatchLoader] 旧路，本收集面不收。
     *
     * 前缀读失败（IO 事故）原样抛：开态装配对「磁盘读不出来」fail-closed（不静默说成
     * 「没有容器」），与 mods 列不出来在装载侧的整体回退口径同层。
     */
    private fun collectPatchContainers(gameRoot: File): List<File> {
        val dir = File(gameRoot, "mods")
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        for (f in dir.listFiles { item -> item.isFile && ModPatchLoader.hasPatchExtension(item.name) }
            ?: emptyArray()
        ) {
            val prefix = readPrefixOrNull(f, ModuleContainerFormat.PREFIX_BYTES) ?: continue
            if (ModuleContainerFormat.probe(prefix) != null) out.add(f)
        }
        return out.sortedBy { it.name }
    }

    /** 读文件最前 [n] 字节；文件不足 [n] 字节返回 null（探测判非容器，不炸）。 */
    private fun readPrefixOrNull(file: File, n: Int): ByteArray? {
        if (file.length() < n) return null
        return RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(n)
            raf.readFully(buf)
            buf
        }
    }

    private fun manifestFilesIn(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val manifests = dir.listFiles { f -> f.isFile && f.name.endsWith(ModRuntimeAssembly.MANIFEST_EXTENSION) }
            ?.sortedBy { it.name }
            ?: emptyList()
        // 批2-3：与 collectErbModules 同一道停用闸（两侧必须一起摘，见那里的注释）。
        return ModEnablement.enabledOnly(dir, manifests)
    }

    /** 只读尾部 [maxBytes]；跨在切口上的半行丢掉（不影响尾部签名段的逐行解析）。 */
    private fun readTailText(file: File, maxBytes: Long): String =
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            val raw = String(bytes, Charsets.UTF_8)
            if (start == 0L) raw else raw.substringAfter('\n', "")
        }
}
