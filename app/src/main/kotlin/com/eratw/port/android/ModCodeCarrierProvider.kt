package com.eratw.port.android

import android.content.Context
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson
import com.eratw.port.modapi.EraMod
import java.io.File

/**
 * ADR-013 **S6b：已信任第三方代码包接进载体注册表**（[ModCarrierRegistry] 的代码包来源）。
 *
 * ## 它做什么
 *
 * 一次装配期**一次性**把磁盘上的代码包变成载体名单：
 *
 * 1. 枚举 `mods/rt2/code/` 下的包目录（[ModTrustStore.codePackages]——只认有合法 16 位小写 hex
 *    前缀产物文件的目录，目录名即磁盘身份 `kojo-<8hex>`）；
 * 2. 逐包从产物内层 `manifest.json` 取 `entrypoint` 与 ABI 自述，构造运行期 [PackMeta] 投影；
 * 3. 交给既有闸序 [ModDexLoader.load]（ABI → 总闸 → 磁盘 id 形态 → 身份侧车 → 运行期 id 形态 →
 *    按运行期 id 查信任 → 产物唯一性/sha 复算 → 注入工厂建 ClassLoader）；
 * 4. `Loaded` 再交给 [ModDexModuleLoader] 装出 [EraMod] 实例。
 *
 * 它**不复刻任何判据**：信任、ABI、身份、完整性、类加载失败的裁决全在原闸内，本类只做「逐包
 * 编排 + 结局分类 + 内置去重」。这条分工是刻意的——重复实现判据会让两处口径漂移。
 *
 * ## 逐包隔离（S6b 硬语义）
 *
 * 任一包未信任 / ABI 不兼容 / 身份侧车损坏 / 产物缺失或多份 / 内层清单坏 / 入口类装不出，都
 * **只跳过该包**：记一条读数（[CodeCarrierOutcome]），其余包照常出载体，**绝不向上抛**。
 * 失败面的日志沿用既有形态（装载闸自己记 `mod.dex disk=… runtime=… outcome=…`，模块装载器
 * 自己记 `mod.dex module pkg=… entrypoint=… outcome=…`；只有本类前置跳过的才补
 * `mod.dex carrier pkg=… outcome=…` 一行），一次跳过至少一条可 grep 的日志。
 *
 * ## 总闸关 = 零扫描零构造（反证要求）
 *
 * `modDexLoaderEnabled` 为 false 时 [collect] 在**第一行**返回空表：[ModCodeStore] 不构造（
 * [storeFactory] 一次都不调）、目录不枚举、产物不读、工厂不建。这不是「读了再过滤」——关态
 * 的判据是「连读都没发生」，与 ADR-013 S8 的总闸语义（默认 false）一致。
 *
 * ## 运行期身份
 *
 * 返回的 pair 第一元与 [ModDexModuleLoader] 日志里的 packageId 都用
 * [LoadOutcome.Loaded.runtimePackageId]（`.codeid` 侧车声明的作者身份），不是磁盘目录名。
 * 与内置载体（[ModCarrierRegistry] 的编译期载体表）重名 ⇒ 记一条 `builtin-conflict` 并跳过
 * 代码包：内置优先，同一 packageId 绝不做双注册（装配侧 `install` 会按 packageId 逐 mod
 * fail-closed，双注册只会让两边都不可用）。代码包之间声明同一运行期身份时同理保留首个。
 *
 * ## 线程纪律
 *
 * [collect] 只碰文件与类加载，不读偏好表：总闸、工厂与 compat 表（ADR-013 D2 闸 2 的信息面，见
 * [ModCompatTable]）都由调用方在 main thread 上取好后经构造参数单路传入（同 `GameHost.modRuntime`
 * 的纪律）；逐包装载只转发结论，不再读资产面。[forContext] 是给 main thread 用的便利入口，**不要**
 * 在 VM 线程上调用。
 *
 * ## 不在本刀
 *
 * 真机 ART 实装载（DexClassLoader 真读 dex / W^X / dex2oat 耗时）不在本刀：单测用 JVM fake
 * [DexLoaderFactory] 注入，端到端留 TODO(verify)。
 */
internal class ModCodeCarrierProvider(
    private val gameRoot: File,
    private val masterEnabled: Boolean,
    private val loaderFactory: DexLoaderFactory?,
    private val abi: ModAbiReadout = ModAbiReadout(),
    private val logSink: (String) -> Unit = {},
    private val storeFactory: (File) -> ModCodeStore = ::ModCodeStore,
) {

    /**
     * 装配期取一次：返回代码包载体名单 + 每包结局读数。
     *
     * @param builtinPackageIds 内置载体已占用的 packageId 集合（内置优先判据；空集 = 无内置冲突面）
     */
    fun collect(builtinPackageIds: Set<String> = emptySet()): CodeCarrierCollection {
        val collection = collectOrEmpty(builtinPackageIds)
        ModCodeLoadReports.publish(collection)
        return collection
    }

    /** 实际装配：总闸关 / 工厂缺席都返回规范空表，零扫描零构造（发布点在 [collect]）。 */
    private fun collectOrEmpty(builtinPackageIds: Set<String>): CodeCarrierCollection {
        val factory = loaderFactory
        return when {
            !masterEnabled -> CodeCarrierCollection.EMPTY
            factory == null -> noLoaderFactoryCollection()
            else -> collectWith(factory, builtinPackageIds)
        }
    }

    /** 总闸开但工厂缺席（装配缺陷）：一条日志、零扫描，不猜也不半开。 */
    private fun noLoaderFactoryCollection(): CodeCarrierCollection {
        trace("mod.dex carrier pkg=- outcome=no-loader-factory")
        return CodeCarrierCollection.EMPTY
    }

    private fun collectWith(factory: DexLoaderFactory, builtinPackageIds: Set<String>): CodeCarrierCollection {
        val rt2Dir = ModEnablement.rt2Dir(gameRoot)
        val store = storeFactory(rt2Dir)
        val run = Run(
            loader = ModDexLoader(store, ModDexGate::verdict, factory, logSink, dexLoaderEnabled = true),
            moduleLoader = ModDexModuleLoader(logSink),
            builtinPackageIds = builtinPackageIds,
            taken = builtinPackageIds.toMutableSet(),
        )
        val records = mutableListOf<CodeCarrierRecord>()
        val carriers = mutableListOf<Pair<String, EraMod>>()
        for (packageId in ModTrustStore.codePackages(store.codeDir()).map { it.packageId }) {
            val slot = slotOf(packageId, store, run)
            records += slot.record
            val carrier = slot.carrier
            if (carrier != null) carriers += carrier
        }
        return CodeCarrierCollection(carriers, records)
    }

    /** 单包的「产物 → 内层清单 → 装载闸」三段；清单坏在装载前就断（不建 ClassLoader）。 */
    private fun slotOf(packageId: String, store: ModCodeStore, run: Run): Slot {
        val artifacts = store.list(packageId)
        val artifact = artifacts.singleOrNull()
        return when (artifact) {
            null -> skip(
                packageId,
                null,
                if (artifacts.isEmpty()) CodeCarrierOutcome.MISSING_ARTIFACT else CodeCarrierOutcome.AMBIGUOUS_ARTIFACT,
            )
            else -> {
                val manifest = artifactManifestBytes(artifact.file)?.let(::manifestOf)
                if (manifest == null) {
                    skip(packageId, null, CodeCarrierOutcome.INVALID_MANIFEST)
                } else {
                    loadSlot(packageId, manifest, run)
                }
            }
        }
    }

    /**
     * 单包过装载闸。
     *
     * 刻意**不传** `expectedSha256`：本类已在上一步把「产物恰好一份」判掉，装载闸在「无期望 sha
     * 且唯一产物」分支里自己会在读产物后复算 sha 与文件名前缀比对（[ModDexLoader.verifyAndLoad]），
     * 完整性判据不因此放空；而 `ModCodeStore.list` 的 `sha256Hex` 只有 16 位前缀，当 64 位期望值
     * 传进去反而会被判「sha 参数形态非法」。多版本的面本类已按 [CodeCarrierOutcome.AMBIGUOUS_ARTIFACT]
     * 跳过，装载闸侧的消歧口留给将来有完整 sha 的调用方。
     *
     * `compat` 是本类构造期单路传入的表读数（装配期 main thread 读一次，见 [ModCompatTable]）：
     * 这里只转发给 [ModDexLoader.load]，逐包不再读资产/偏好表。表只影响 [LoadOutcome.Rejected]
     * 的文案后缀，判定仍由 [ModDexGate.verdict] 的 `compiledSurface <= engineApiVersion` 一条决定。
     */
    private fun loadSlot(packageId: String, manifest: ArtifactManifest, run: Run): Slot {
        val outcome = run.loader.load(packageId, manifest.meta, abi.engineApiVersion, abi.compat)
        return when (outcome) {
            is LoadOutcome.Loaded -> adoptSlot(outcome, manifest.entrypoint, run)
            is LoadOutcome.Rejected -> Slot(
                rejectedRecord(packageId, outcome, manifest.meta, abi.engineApiVersion),
                null,
            )
            else -> Slot(CodeCarrierRecord(packageId, outcome.runtimePackageId, outcomeOf(outcome)), null)
        }
    }

    /** 「装载成功 → 去重 → 装模块」：内置与代码包同 id 都在这层挡住，接模块前的最后一道。 */
    private fun adoptSlot(loaded: LoadOutcome.Loaded, entrypoint: String, run: Run): Slot {
        val runtimeId = loaded.runtimePackageId
        return when {
            runtimeId in run.builtinPackageIds ->
                skip(loaded.diskPackageId, runtimeId, CodeCarrierOutcome.BUILTIN_CONFLICT)
            runtimeId in run.taken ->
                skip(loaded.diskPackageId, runtimeId, CodeCarrierOutcome.DUPLICATE_RUNTIME_ID)
            else -> moduleSlot(loaded, runtimeId, entrypoint, run)
        }
    }

    private fun moduleSlot(
        loaded: LoadOutcome.Loaded,
        runtimeId: String,
        entrypoint: String,
        run: Run,
    ): Slot = when (val module = run.moduleLoader.load(loaded, entrypoint)) {
        is ModuleLoadOutcome.Loaded -> {
            run.taken += runtimeId
            Slot(
                CodeCarrierRecord(loaded.diskPackageId, runtimeId, CodeCarrierOutcome.LOADED),
                runtimeId to module.mod,
            )
        }
        // 六种模块失败各自已在 ModDexModuleLoader 里记过一行（判据真源在那），这里只落读数。
        is ModuleLoadOutcome.InvalidEntrypoint,
        is ModuleLoadOutcome.ClassNotFound,
        is ModuleLoadOutcome.NoDefaultConstructor,
        is ModuleLoadOutcome.NotAnEraMod,
        is ModuleLoadOutcome.ConstructorFailed,
        is ModuleLoadOutcome.StaticInitializationFailed ->
            Slot(CodeCarrierRecord(loaded.diskPackageId, runtimeId, CodeCarrierOutcome.MODULE_FAILED), null)
    }

    /** 本类前置跳过（清单坏 / 产物缺失 / 去重）：补一条 canonical 日志，玩家不可见只进 logcat。 */
    private fun skip(diskPackageId: String, runtimePackageId: String?, outcome: CodeCarrierOutcome): Slot {
        trace(
            "mod.dex carrier pkg=" + oneLine(runtimePackageId ?: diskPackageId) +
                " outcome=" + outcome.wire,
        )
        return Slot(CodeCarrierRecord(diskPackageId, runtimePackageId, outcome), null)
    }

    /** logSink 由壳层注入：它自己抛异常不许把逐包隔离（不抛）带坏。 */
    private fun trace(line: String) {
        runCatching { logSink(line) }
    }

    companion object {

        /**
         * main thread 便利入口：一次读总闸，开态才惰性构造生产工厂 [AndroidDexLoaderFactory]，
         * 同址读一次 compat 表（[ModCompatTable.loadFromAssets]；缺失/读失败 ⇒ 空表不抛）。
         *
         * 与 [ModDexLoader.forContext] 同一条纪律：总闸关不构造工厂（关态零构造），总闸、工厂与 compat
         * 表都在构造期定死，进入装配期后不再碰偏好表与资产面。
         */
        fun forContext(context: Context, gameRoot: File, logSink: (String) -> Unit = {}): ModCodeCarrierProvider {
            val enabled = UiPrefs.modDexLoaderEnabled(context)
            return forGate(
                gameRoot = gameRoot,
                enabled = enabled,
                factory = if (enabled) AndroidDexLoaderFactory(context) else null,
                compat = ModCompatTable.loadFromAssets(context.assets),
                logSink = logSink,
            )
        }

        /**
         * 装配期入口（供 `GameHost` 构造参数单路传值）：总闸、工厂与 compat 表已在 main thread 取好，
         * 本入口不读任何偏好表/资产面，可在 VM 线程上调用。
         *
         * @param compat ADR-013 D2 闸 2 的信息面读数（只补拒绝文案，不参与判定）；缺省空表 =
         *   与接线前逐位一致的行为（[ModDexGate.verdict] 不依赖表）
         */
        fun forGate(
            gameRoot: File,
            enabled: Boolean,
            factory: DexLoaderFactory?,
            compat: ModDexGate.SurfaceCompat = ModDexGate.SurfaceCompat.EMPTY,
            logSink: (String) -> Unit = {},
        ): ModCodeCarrierProvider = ModCodeCarrierProvider(
            gameRoot = gameRoot,
            masterEnabled = enabled,
            loaderFactory = factory,
            abi = ModAbiReadout(compat = compat),
            logSink = logSink,
        )
    }
}

/**
 * ABI 拒载的读数分类：原因句原文照抄 [LoadOutcome.Rejected.reason]（不重拼、不改判）；
 * 「引擎太旧」用装载闸同一条判据的版本读数复读——`compiledSurface > engineApiVersion`
 * 成立才判 [CodeCarrierOutcome.ENGINE_TOO_OLD]，否则保留 [CodeCarrierOutcome.REJECTED]
 * （判据真源仍是 [ModDexGate.verdict]，本类只分类不裁决）。
 *
 * 放类外是为了让 provider 的函数数停在 detekt 阈值内（分类是纯投影，不需要实例状态）。
 */
private fun rejectedRecord(
    diskPackageId: String,
    rejected: LoadOutcome.Rejected,
    meta: PackMeta,
    engineApiVersion: Int,
): CodeCarrierRecord {
    val gap = meta.compiledSurface
        ?.takeIf { it > engineApiVersion }
        ?.let { EngineSurfaceGap(required = it, current = engineApiVersion) }
    return CodeCarrierRecord(
        diskPackageId = diskPackageId,
        runtimePackageId = rejected.runtimePackageId,
        outcome = if (gap == null) CodeCarrierOutcome.REJECTED else CodeCarrierOutcome.ENGINE_TOO_OLD,
        rejectReason = rejected.reason,
        engineTooOld = gap,
    )
}

/** 单包结局码（读数投影；判据真源在 [ModDexLoader] / [ModDexModuleLoader] / 本类去重两道）。 */
internal enum class CodeCarrierOutcome {
    /** 装出 `EraMod` 实例并进载体名单。 */
    LOADED,

    /** ABI 闸拒载，但读不出「引擎太旧」的版本缺口（保留给判据真源变化后的其它拒绝原因）。 */
    REJECTED,

    /** ABI 闸拒载且缺口可读：包内层清单要求的引擎面高于本机（玩家只能更新应用）。 */
    ENGINE_TOO_OLD,

    /** dex 装载总闸未开（单包面，正常路径由 [ModCodeCarrierProvider.collect] 首行整体短路）。 */
    DISABLED,

    /** 未获得信任确认（按运行期身份查 `.trusted` 标记）。 */
    NEEDS_TRUST,

    /** 磁盘包目录名形态非法。 */
    INVALID_PACKAGE,

    /** `.codeid` 运行期身份侧车损坏。 */
    CORRUPT_IDENTITY,

    /** 包目录里没有产物文件。 */
    MISSING_ARTIFACT,

    /** 包目录里有多份产物且无期望 sha 可消歧。 */
    AMBIGUOUS_ARTIFACT,

    /** 产物 sha 复算与文件名前缀不一致（被改写过）。 */
    INTEGRITY_FAILED,

    /** 读产物/侧车时 IO 失败。 */
    IO_FAILED,

    /** 工厂建 ClassLoader 失败（真机上可能是 dex 损坏 / 优化失败）。 */
    LOADER_FAILED,

    /** 产物内层 `manifest.json` 缺失、坏 JSON、缺 `entrypoint`，或内层体积闸拒绝（本类前置跳过）。 */
    INVALID_MANIFEST,

    /** 入口类装不出 `EraMod`（入口名非法 / 类找不到 / 无无参构造 / 类型不符 / 构造或静态块炸）。 */
    MODULE_FAILED,

    /** 与内置载体同 packageId：内置优先，代码包跳过（防双注册）。 */
    BUILTIN_CONFLICT,

    /** 与先前代码包同运行期身份：保留首个。 */
    DUPLICATE_RUNTIME_ID,
    ;

    /** canonical 日志用的稳定小写码（`needs-trust` / `builtin-conflict` …）。 */
    val wire: String get() = name.lowercase().replace('_', '-')
}

/**
 * ABI 闸「引擎太旧」的版本缺口读数：包内层清单要求 [required]（`compiledSurface`），
 * 本机引擎为 [current]（[ModAbiReadout.engineApiVersion]）。
 *
 * 只做读数、不参与判定：判定仍由 [ModDexGate.verdict] 一条决定（`compiledSurface >
 * engineApiVersion` 才拒）；本对象是 [LoadOutcome.Rejected] 的分类依据（见 [ModCodeCarrierProvider]
 * 的拒载分类），设置页只拿它拼玩家文案。
 *
 * 公开可见性由 [ModCodeGateHint.engineGap] 决定（导入成功页的提示结构是公开数据类，
 * 不能持 internal 类型）；构造面仍只在 app 模块内，两个数字都是引擎面版本号、不是内部术语。
 */
data class EngineSurfaceGap(val required: Int, val current: Int)

/** 一个代码包的结局读数（装配诊断/单测用；玩家不可见）。 */
internal data class CodeCarrierRecord(
    /** 磁盘身份（`code/` 下的目录名）。 */
    val diskPackageId: String,
    /** 运行期身份；身份还没解析出来（磁盘 id 形态非法 / 侧车坏 / 产物缺失）时为 null。 */
    val runtimePackageId: String?,
    val outcome: CodeCarrierOutcome,
    /** ABI 拒载原因句原文（[LoadOutcome.Rejected.reason]，来自 [ModDexGate.verdict]，本类不重拼）。 */
    val rejectReason: String? = null,
    /** 仅 [CodeCarrierOutcome.ENGINE_TOO_OLD]：版本缺口读数。 */
    val engineTooOld: EngineSurfaceGap? = null,
)

/** [ModCodeCarrierProvider.collect] 的完整结果：载体名单（安装序）+ 每包读数（枚举序）。 */
internal class CodeCarrierCollection(
    val carriers: List<Pair<String, EraMod>>,
    val records: List<CodeCarrierRecord>,
) {
    companion object {
        /** 总闸关 / 工厂缺席的规范空结果（共享不可变空表，不建任何对象）。 */
        val EMPTY: CodeCarrierCollection = CodeCarrierCollection(emptyList(), emptyList())
    }
}

/**
 * 一次装配期 [ModCodeCarrierProvider.collect] 的逐包结论快照（进程内只读）。
 *
 * 不写盘：装载结论是「本进程这次装配」的事实，落盘会变成第二真源（重启后旧结论可能与新盘面
 * 矛盾）；设置页拿不到快照时照「缺数据显式 —」口径（[ModCodeOverview] 的 null 分支），
 * 不猜上一次的结果。key = 磁盘身份（`code/` 目录名），与 [ModCodeOverview] 的行同键。
 */
internal class ModCodeLoadReport(private val records: List<CodeCarrierRecord>) {

    /** 该磁盘身份本次装载的结论；本次没跑到这个包 ⇒ null（不编造）。 */
    fun recordFor(diskPackageId: String): CodeCarrierRecord? =
        records.firstOrNull { it.diskPackageId == diskPackageId }

    /** 「引擎太旧」缺口；该包本次不是 ABI 拒载 ⇒ null。 */
    fun engineGapFor(diskPackageId: String): EngineSurfaceGap? = recordFor(diskPackageId)
        ?.takeIf { it.outcome == CodeCarrierOutcome.ENGINE_TOO_OLD }
        ?.engineTooOld

    /**
     * 按运行期身份取结论：磁盘身份会随重导换代，装载端认包用的是运行期身份，
     * 诊断面 join 需要一个只读查询口（[recordFor] 只认磁盘身份），不重跑装载、不改判。
     *
     * 该包本次没解析出运行期身份（侧车坏 / 产物缺）⇒ null。
     */
    fun recordForRuntimeId(runtimePackageId: String): CodeCarrierRecord? =
        records.firstOrNull { it.runtimePackageId == runtimePackageId }

    /** 快照是否为空（总闸关 / 工厂缺席 / 没有代码包）。 */
    val isEmpty: Boolean get() = records.isEmpty()

    companion object {
        /** 空快照（装配没跑或零包）。 */
        val EMPTY: ModCodeLoadReport = ModCodeLoadReport(emptyList())
    }
}

/**
 * 最近一次装配期装载结论的进程内发布点（装配期写一次、设置页只读）。
 *
 * 为什么不是 `UiPrefs`：装载结论只在本次进程有效，且总闸关时必须是「零结论」而不是上一局的
 * 旧结论（[publish] 连空表也发布，正好清掉旧快照）。`@Volatile` 只保证引用可见性，快照本身
 * 不可变，所以不需要锁。
 */
internal object ModCodeLoadReports {

    /** 最近一次 [ModCodeCarrierProvider.collect] 发布的快照；本进程还没装配过 ⇒ null。 */
    @Volatile
    var last: ModCodeLoadReport? = null
        private set

    /** 装配期收尾发布（总闸关 / 工厂缺席也发布空快照，防设置页把旧结论当本次）。 */
    fun publish(collection: CodeCarrierCollection) {
        last = ModCodeLoadReport(collection.records)
    }
}

/** 单次 collect 的装配上下文：把「两个装载器 + 内置身份集 + 已占用身份集」收成一组。 */
private class Run(
    val loader: ModDexLoader,
    val moduleLoader: ModDexModuleLoader,
    val builtinPackageIds: Set<String>,
    val taken: MutableSet<String>,
)

/** 单包编排中间态：结局读数 +（成功时）待装进宿主的载体 pair。 */
private class Slot(val record: CodeCarrierRecord, val carrier: Pair<String, EraMod>?)

/** 内层 `manifest.json` 的运行期投影：入口类名 + 装载闸认的 ABI 自述。 */
private class ArtifactManifest(val entrypoint: String, val meta: PackMeta)

/** 产物内层清单条目名（ADR-013 D1，与导入链同名）。 */
private const val MANIFEST_ENTRY_NAME: String = "manifest.json"

/** 内层清单的入口类名键（ADR-013 D1）。 */
private const val MANIFEST_ENTRYPOINT_KEY: String = "entrypoint"

/**
 * 内层清单字节上限：清单只有几个标量键，64 KiB 已是一个数量级的余量；超限视为坏档（
 * 不让异常清单把一键装配拖住，也不给 zip 解压放大留口子）。
 */
private const val MAX_MANIFEST_BYTES: Int = 64 * 1024

/**
 * 从产物（`.mod` zip）里读内层清单字节；任何读失败都收敛成 null（坏档不抛）。
 *
 * 产物整份读进堆再取内层清单（[ModZip.fixedEntryBytes] 单趟过 [ModZipGuard] 五道闸）：
 * 产物由导入链落盘（≤ 4 MiB），整份进堆与装载闸 [ModDexLoader.verifyAndLoad] 的既有读法
 * 同量级。内层 zip 没有 ⓪ 整包闸可借（外层闸只管 `code.mod` 条目解压后的文件字节，内层
 * 再压缩是新的压缩域），非目标条目若靠 `getNextEntry` 的 closeEntry 静默排水，炸弹可藏在
 * 清单条目之前的任意条目里、每局装配重放一次——闸拒绝折成 null ⇒
 * [CodeCarrierOutcome.INVALID_MANIFEST] 跳过该包，绝不带病进类加载。条目数上限同口径收紧
 * 到 [ModZipGuard.MAX_ENTRIES]（正常产物 = classes.dex + manifest.json + 可选多 dex，
 * 十位数量级；512 留十倍余量）。
 */
private fun artifactManifestBytes(artifactFile: File): ByteArray? =
    runCatching { readManifestBytes(artifactFile) }.getOrNull()

private fun readManifestBytes(artifactFile: File): ByteArray? =
    ModZip.fixedEntryBytes(artifactFile.readBytes(), MANIFEST_ENTRY_NAME, MAX_MANIFEST_BYTES)

/** 清单字节 → 运行期投影；缺 JSON 对象 / 缺入口类名 ⇒ null（该包在装载前跳过）。 */
private fun manifestOf(bytes: ByteArray): ArtifactManifest? {
    val node = MarketJson.parseOrNull(String(bytes, Charsets.UTF_8)) as? JsonObject
    val fields = innerManifestFieldsOf(bytes)
    val entrypoint = (node?.opt(MANIFEST_ENTRYPOINT_KEY) as? JsonString)?.value?.trim().orEmpty()
    return if (node == null || fields == null || entrypoint.isEmpty()) {
        null
    } else {
        ArtifactManifest(entrypoint, metaOf(fields))
    }
}

/**
 * 内层清单 → [PackMeta] 运行期投影。
 *
 * 只填装载闸真正读的两项（`compiledSurface` = ABI 自述、`minApi`）与读数用版本号；
 * `packageId` 留 null（内层身份一致性已在导入期判过，运行期不重复判），`ignored` 留 null
 * （「有没有采信」是导入期 schema 闸的结论，这里没有第二套 schema 判据）。字段归一化沿用
 * [innerManifestFieldsOf] 的既有口径（空串 / 非正数按未声明），不在这里另立一套。
 */
private fun metaOf(fields: InnerManifestFields): PackMeta = PackMeta(
    packageId = null,
    declaredVersion = fields.version,
    version = fields.version ?: PACK_META_FALLBACK_VERSION,
    ignored = null,
    evidence = PackMetaEvidence(compiledSurface = fields.compiledSurface),
    minApi = fields.minApi ?: 0,
)

/** [LoadOutcome] → 结局码的穷举投影（新增子类型时编译器逼着这里补一行）。 */
private fun outcomeOf(outcome: LoadOutcome): CodeCarrierOutcome = when (outcome) {
    is LoadOutcome.Loaded -> CodeCarrierOutcome.LOADED
    is LoadOutcome.Disabled -> CodeCarrierOutcome.DISABLED
    is LoadOutcome.Rejected -> CodeCarrierOutcome.REJECTED
    is LoadOutcome.NeedsTrust -> CodeCarrierOutcome.NEEDS_TRUST
    is LoadOutcome.InvalidPackage -> CodeCarrierOutcome.INVALID_PACKAGE
    is LoadOutcome.MissingArtifact -> CodeCarrierOutcome.MISSING_ARTIFACT
    is LoadOutcome.AmbiguousArtifact -> CodeCarrierOutcome.AMBIGUOUS_ARTIFACT
    is LoadOutcome.IntegrityFailed -> CodeCarrierOutcome.INTEGRITY_FAILED
    is LoadOutcome.IoFailed -> CodeCarrierOutcome.IO_FAILED
    is LoadOutcome.LoaderFailed -> CodeCarrierOutcome.LOADER_FAILED
    is LoadOutcome.CorruptIdentity -> CodeCarrierOutcome.CORRUPT_IDENTITY
}

/** 日志值压成单行（控制字符换 `?`；清单里的包名不许把 logcat 行结构撑坏）。 */
private fun oneLine(value: String): String =
    value.map { ch -> if (ch.code < FIRST_PRINTABLE_CODE) '?' else ch }.joinToString("")

/** 可打印字符下界。 */
private const val FIRST_PRINTABLE_CODE: Int = 0x20
