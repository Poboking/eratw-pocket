package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainAssembler
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.mod.ModuleContainerAdapter
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleContainerCodec
import com.eratw.port.core.patch.ModuleContainerFormat
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.ModCsvLoadContext
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
 */
data class ChainAssemblyInputs(
    val gameRoot: File,
    val enabled: Boolean,
    val base: Program,
    val baseMetas: List<FuncMeta>,
    val builtinVariableNames: () -> Set<String> = { emptySet() },
    val linkOrder: LinkOrder = LinkOrder(),
    val csvContext: ModCsvLoadContext? = null,
)

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
 * 46.8 MiB 侧车）→ [BaseSignatureCatalog.parse] → `signatureHashOf` →
 * `ModChainAssembler.assemble(baseSignatureOf = …)`。生产 boot meta 目前**不带**该段
 * （`ErbcArtifacts.kt:189` 未传 `signatures`，`BootMetaWriter.kt:63-66` KDoc 明示
 * 「生产串联写进需组长接线」）⇒ 目录恒空 ⇒ MODERN 显式覆盖被核心既有签名闸拒绝。
 * 这是**故意的 fail-closed，不是假接通**：打开写侧会改标准 `compileCorpus` 产物字节、
 * 还需装载侧 `stripSignatureSection` 串联与出包体检基线重标定，单列余量三。
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
    fun assemble(inputs: ChainAssemblyInputs): ModChainRuntimePlan? {
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

    /** 确认有容器之后的真装配体；与 [assemble] 拆开是为了把旁路判定留在唯一入口。 */
    private fun assembleChain(
        inputs: ChainAssemblyInputs,
        erbmFiles: List<File>,
        patchContainerFiles: List<File>,
    ): ModChainRuntimePlan {
        // 别名只为让函数体与收敛前的逐参版本逐字一致（零行为变化：body 无改写）。
        val gameRoot = inputs.gameRoot
        val base = inputs.base
        val baseMetas = inputs.baseMetas
        val builtinVariableNames = inputs.builtinVariableNames
        val linkOrder = inputs.linkOrder
        val csvContext = inputs.csvContext
        val compiled = erbmFiles.map { file ->
            val container = try {
                ErbModuleContainer.decode(file.readBytes())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "ERB 模块容器 [${file.name}] 坏档或来源不明（G 车余量二 `compile-module` " +
                        "产线之外）—— fail-closed 整包拒绝：${e.message}",
                    e,
                )
            }
            require(container.allowSuperCalls) {
                "ERB 模块容器 [${file.name}] 不是 Mod 包编译档（allowSuperCalls=false）" +
                    " —— fail-closed 整包拒绝"
            }
            file to container.toCompiledModule()
        }

        // 单向配对（RT2-ERBM 裁定①）：判据只压「每 .erbm 必须找到同 packageId 的 manifest」
        // 这一个方向，缺失整包拒（fail-closed 保留，与旧「packageId 不齐整包拒」同形）；
        // 反方向（manifest 无容器）是**载体形态**（如 eratw.family），链跳过——它的声明
        // 事务仍由 ModRuntimeAssembly.attach 全量加载，冲突检测不丢。传给核心五步链的
        // 两组集合因此等数（ModChainAssembler.pairAndValidate 判据零改动，看到的仍是等数集）。
        val manifestDir = ModRuntimeAssembly.manifestDirFor(gameRoot)
        val sourceByPkg = LinkedHashMap<String, ManifestSource>()
        for (file in manifestFilesIn(manifestDir)) {
            val bytes = file.readBytes()
            val pkg = ManifestCodec.decode(bytes).packageId
            require(sourceByPkg.put(pkg, ManifestSource(file.name, bytes)) == null) {
                "RT2 Mod 链配对不齐：manifest 包名重复 $pkg（${file.name}）—— fail-closed 整包拒绝"
            }
        }

        // 四池容器补丁（裁定 1 第二源，免 manifest）：坏字节 / LINKED 态 / 描述符非法
        // 一律整包拒（fail-closed 同 ERTM1 口径）；packageId 与 ERTM1 manifest/容器或
        // 彼此撞名 = 身份冲突，整包拒。适配产物 = 内存内 ManifestSource + CompiledModule
        // （ModuleContainerAdapter KDoc 有源名与不落盘说明）。
        val patchSources = ArrayList<ManifestSource>(patchContainerFiles.size)
        val patchModules = ArrayList<CompiledModule>(patchContainerFiles.size)
        for (file in patchContainerFiles) {
            // 宽兜底是刻意的（detekt TooGenericExceptionCaught 已登记质量债）：
            // 来源不明的字节可能从 decode 的任一读侧闸抛任意 RuntimeException，
            // 这类字节必须一律整包拒、不得穿透到装配器；与 ModPatchLoader 既有口径同源。
            val container = try {
                ModuleContainerCodec.decode(file.readBytes())
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "四池容器补丁 [${file.name}] 坏档或来源不明（M2-4 转码产线之外的手工字节）" +
                        " —— fail-closed 整包拒绝：${e.message}",
                    e,
                )
            }
            val adapted = ModuleContainerAdapter.adapt(container, PATCH_SOURCE_PREFIX + file.name)
            val pkg = adapted.compiled.packageId
            val manifestHolder = sourceByPkg[pkg]
            require(manifestHolder == null) {
                "四池容器补丁 [${file.name}] 的 packageId $pkg 与 RT2 manifest " +
                    "[${manifestHolder?.name}] 撞名（容器补丁免 manifest，包名必须自持）" +
                    " —— fail-closed 整包拒绝"
            }
            require(compiled.none { it.second.packageId == pkg }) {
                "四池容器补丁 [${file.name}] 的 packageId $pkg 与 ERTM1 .erbm 容器撞名" +
                    " —— fail-closed 整包拒绝"
            }
            require(patchModules.none { it.packageId == pkg }) {
                "四池容器补丁的 packageId 重复 $pkg（${file.name}）—— fail-closed 整包拒绝"
            }
            patchSources += adapted.source
            patchModules += adapted.compiled
        }

        val pairedSources = ArrayList<ManifestSource>(compiled.size + patchSources.size)
        for ((file, module) in compiled) {
            val source = sourceByPkg[module.packageId]
            requireNotNull(source) {
                "RT2 Mod 链配对不齐：容器 [${file.name}] 的 packageId ${module.packageId}" +
                    " 找不到同包名 manifest —— fail-closed 整包拒绝"
            }
            pairedSources += source
        }
        pairedSources += patchSources
        val pairedModules = compiled.map { it.second } + patchModules
        val containerPkgs = compiled.map { it.second.packageId }.toSet()
        for ((pkg, source) in sourceByPkg) {
            if (pkg !in containerPkgs) {
                // 装载面取证日志：载体 manifest 被链跳过这一事实要留痕（FINE 不进常规输出），
                // 不让「manifest 在场却没进链接」成为无解释的静默路径（AGENTS §6.1 口径）。
                LOGGER.fine(
                    "RT2 载体 manifest [$pkg]（${source.name}）无同包名 .erbm 容器，" +
                        "五步链接链跳过；声明事务仍由 ModRuntimeAssembly.attach 全量加载",
                )
            }
        }

        val baseSignatureOf = signatureDirectoryOfFile(File(gameRoot, META_RELATIVE_PATH))
        val chain = ModChainAssembler.assemble(
            sources = pairedSources,
            compiledModules = pairedModules,
            base = base,
            baseMetas = baseMetas,
            builtinVariableNames = builtinVariableNames(),
            csvTableCapacities = csvContext?.csvTableCapacities ?: emptyMap(),
            baseCsvKeywords = csvContext?.baseCsvKeywords ?: emptyMap(),
            baseSignatureOf = baseSignatureOf,
            linkOrder = linkOrder,
            snapshot = csvContext?.snapshot,
        )
        // W2-SUPCAT 卡点取证：HAS_SUPER 判据源（specs/builtin-functions.yaml 的 HAS_SUPER
        // 条目）就是这里的 chain.linked.catalog（ModChainAssembler 第五步链接产物）。本装配点
        // 挂不了 store.aux——[ChainAssemblyInputs] 无 store，且生产时序上本函数先于
        // GameSession 构造执行（store 在 GameSession 里才建出来），ModChainRuntimePlan 又不
        // 保留 catalog，快照只活在本函数作用域。读侧（ModRuntimeAssembly.attach 尾段读缝
        // MOD_LINK_CATALOG_SLOT）已就绪；生产写侧 = 组长在 GameHost/GameSession 面接线
        // （两文件本波禁触，裁定见 CONTRACT-RT2-SUPCAT 交付报告「需组长接线」）。
        // 核心层只在「无 Mod / 无模块」旁路时返回 null。走到这里说明两源至少一源在场，
        // 若仍返回 null 就是装配器不变量被破坏：必须整包拒，绝不悄悄退回 null 让
        // GameSession 按 base 三表继续跑（那会变成「容器在盘上但行为没变」的假接通）。
        return requireNotNull(ModChainRuntimePlan.of(chain)) {
            "ERB 模块容器在场但链接产物为旁路态 —— 装配器不变量破坏，fail-closed 整包拒绝"
        }
    }

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
        return dir.listFiles { f -> f.isFile && f.name.endsWith(ERB_MODULE_EXT) }
            ?.sortedBy { it.name }
            ?: emptyList()
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
        return dir.listFiles { f -> f.isFile && f.name.endsWith(ModRuntimeAssembly.MANIFEST_EXTENSION) }
            ?.sortedBy { it.name }
            ?: emptyList()
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
