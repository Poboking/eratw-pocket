package com.eratw.port.android

import android.content.Context
import com.eratw.port.android.market.JsonNumber
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModApi
import java.io.File

/**
 * ADR-013 S5：dex 载体的**生产装载面**——包内元数据解析、载体供给核心与装配缝类型。
 *
 * ## 职责切分（对照既有面）
 *
 * - [ModDexLoader]：闸序本体（ABI 闸 → 总闸 → 磁盘包名 → 身份解析 → 信任 → 盘面 → sha 复算 →
 *   工厂），全部盘面与判定不动，工厂经 `DexLoaderFactory` 缝注入；
 * - [AndroidDexLoaderFactory]：缝的生产分支（真 DexClassLoader 构造，ADR-013 D1 参数序）；
 * - 本文件：包内 `manifest.json` 元数据解析（[parseDexPackageMeta]，D1 包形态）、
 *   「扫 code 目录 → 逐包过闸 → 入口实例化」的载体供给核心（[ModDexCarrierSource]）与
 *   装配缝类型（[DexCarrierProvision]，`ModRuntimeAssembly.attach` 的注入位）。
 *   缺口登记（BOARD MODDX4 终态行）：对面 dexrun 波只落了工厂与闸链，本文件是
 *   「ModDexModuleLoader 零生产调用 = 功能休眠」缺口的生产侧清偿。
 *
 * ## 真机边界（JVM 测不了的面）
 *
 * `DexClassLoader` 真行为（只读约束、parent 委派、dex2oat 耗时）JVM 单测不生产生结果：
 * 桌面可验证面（闸序、信任、`.off` 过滤、入口解析、归因行、失败收口）全部落在可注入的
 * 纯 JVM 路径上；真机行为逐条 TODO(verify) 挂账（见 [androidDexCarrierProvision] 与
 * AndroidDexLoaderFactory KDoc）：
 * TODO(verify) 📱 DexClassLoader 真机装载一个最小 dex 包成功且 loadClass 可见入口类；
 * TODO(verify) 📱 Android 14+ 只读产物装载不因 W^X 约束被拒（targetSdk 36）；
 * TODO(verify) 📱 首次装载 dex2oat 耗时可接受（ADR-013 最大风险面，超阈值须回 ModCodeLoadException 口径）；
 * TODO(verify) 📱 大包（数十 MB zip）readBytes 全量进堆的内存峰值可接受（本核心逐包读整包字节）；
 * TODO(verify) 📱 parent=AppClassLoader 委派下包内 modapi 类型与本进程类型身份一致（D4 的真机面）。
 *
 * ## 失败面纪律（S7 归因）
 *
 * 载体供给核心**任何情况下不向上抛**：单包失败收敛成一行 `mod.install pkg=<packageId>
 * active=false failure=<原因>` 归因行（与 Kotlin 载体装载失败同形，设置页失败小节经
 * `ModManageDiagnostics` 渲染），生产装配另经 [androidDexCarrierProvision] 把失败送进
 * `ErrorReportCollector` 既有诊断通道；单包失败不拖垮装配链与其余包（ADR-013 D3 ④ 同口径）。
 * S7 的「运行期最近回调名归因」切片（崩溃归到最近一次 mod 回调）—— **ERAFIN2 判定闭环
 * （不发码，实证在案）**：mod 回调的全部入口都在 core 的异常隔离内，**没有可逃逸进程的
 * 回调路径**——生命周期/相位通知 per-mod 隔离（ModRuntimeHost.kt:37-40 类头口径 +
 * :444/:451/:526 catch Throwable 记录不二次抛）、仲裁处理器 catch Throwable
 * （DecisionPipeline.kt:604）、hook 派发四处 catch Throwable（HookPipeline.kt:251/:263/
 * :293/:318），失败均已带 packageId 进 RuntimeDiagnostics 与日志面。崩溃取证里再挂
 * 「最近回调名」没有可消费的路径（能到取证面的崩溃都不是 mod 回调抛的）⇒ 判定维持
 * 现状，缺口闭环；若未来新增不经隔离的 mod 回调入口，先补隔离再谈归因。
 * 同批 DZ 页边界 dex 取件口 —— **ERAFIN2 已清偿**：[DexCarrierProvision.carrierFor]
 * 单包缝（缺省 null fail-closed）+ [ModDexCarrierSource.carrierForPackage] 供给 +
 * `GameHost.retryRolledBackMod` 的 dex 回退取件（与 Kotlin 载体 `providerFor` 的
 * `newMod()` 新实例语义同源）；玩家入口 = 设置页代码包概览卡失败行的「重试装载」按钮。
 */

/** dex 包内元数据条目名（ADR-013 D1 包形态；zip 根、大小写敏感）。 */
internal const val DEX_MANIFEST_ENTRY_NAME: String = "manifest.json"

/** dex 包内元数据的编译面键（与 eratw-pack.json 的 compiledSurface 键同名同语义，ADR-013 D2）。 */
private const val DEX_META_COMPILED_SURFACE_KEY: String = "compiledSurface"

/** 元数据条目字节上限：与 eratw-pack.json 同量级的小 JSON，超限按「没有元数据」处理（不读进堆）。 */
private const val DEX_META_MAX_ENTRY_BYTES: Int = PACK_META_MAX_ENTRY_BYTES

private const val REASON_META_INVALID: String = "代码包内的元数据缺失或损坏，无法确认包身份，未装载"
private const val REASON_ARTIFACT_UNREADABLE: String = "读取代码产物失败，未装载"
private const val REASON_PACKAGE_ID_MISMATCH: String = "代码包自述包名与其落地目录不符，拒绝装载"
private const val REASON_MULTI_VERSION: String =
    "盘上存在多个版本的代码产物而缺少版本引用台账，拒绝猜用哪一个"
private const val REASON_ENTRYPOINT_NOT_DECLARED: String = "代码包元数据没有声明入口类，未装载"
private const val REASON_ENTRYPOINT_NOT_FOUND: String = "代码包声明的入口类不存在或无法加载，未装载"
private const val REASON_ENTRYPOINT_NOT_MOD: String = "代码包声明的入口类不是合法的 Mod 入口，未装载"
    private const val REASON_ENTRYPOINT_NOT_INSTANTIABLE: String = "代码包入口类无法创建实例，未装载"
    private const val REASON_ARTIFACT_INNER_BOMB: String =
        "代码产物内层解压量越限（疑似压缩炸弹），未装载"

/**
 * dex 载体装配缝（ADR-013 S5 的 `ModRuntimeAssembly.attach` 注入位）。
 *
 * `ModRuntimeAssembly` 保持纯 JVM：Android 面（总闸偏好读数、codeCacheDir、诊断取证）
 * 全部收在生产实现 [androidDexCarrierProvision] 里，装配段只看本缝的返回值。
 * 缺省 null = 未接线：装配段整段旁路，与未接线前逐位等价（反向证明 1 的判据）。
 * （public：attach 是 public 函数，Kotlin 不允许公开签名暴露 internal 形参类型；本缝只有
 * 公开类型（File / String / EraMod），公开无害。）
 */
fun interface DexCarrierProvision {
    /**
     * 取本次装配的第三方 dex 载体名单（与内置 Kotlin 载体并行的第三批 provider）。
     *
     * 总闸在实现里判（会话快照，构造期读一次）：关 = 不扫 code 目录、不建类加载器、返回空表
     * （ADR-013 D5）。任何失败都收敛进 [logSink] 归因行，不向上抛。
     */
    fun carriers(gameRoot: File, logSink: (String) -> Unit): List<Pair<String, EraMod>>

    /**
     * 单包取件（Lane DZ 页边界重装路径的供给源，ERAFIN2 清偿）：按 [packageId] 过与
     * [carriers] 同一条闸链取**新实例**（每次重装现造，与 Kotlin 载体 `providerFor` 的
     * `newMod()` 语义同源）。缺省实现恒 `null` = 未接线单包缝的供给端 fail-closed
     * （重装请求静默作废，不猜、不整表实例化）；生产实现见 [androidDexCarrierProvision]。
     */
    fun carrierFor(gameRoot: File, packageId: String, logSink: (String) -> Unit): Pair<String, EraMod>? = null
}

/**
 * dex 包内元数据的装载面字段（ADR-013 D1 `manifest.json`）。
 *
 * 只解析装载链要消费的四个键（packageId / version / compiledSurface / entrypoint）；
 * `apiVersion` / `minApi` / `requires` / `permissions` 本端不消费（前两者的真闸在 mod 实例
 * 自身——`EraMod.apiVersion` 经 core 版本门、`requires` 经 core 装载规划；permissions 的
 * 「只登记不强制」面 v1 未落，解析了也没有登记 sink，按 D1 的「多余字段忽略」口径跳过，
 * 不拒包）。未知键一律忽略（与 eratw-pack.json 的解析同口径，作者工具加注解不拒收）。
 */
internal data class DexPackageMeta(
    /** 作者自述包名（缺失 / 形态非法 ⇒ null，调用方 fail-closed 拒载）。 */
    val packageId: String?,
    /** 包版本原文（缺失 ⇒ null；只随诊断走，装载链不消费）。 */
    val version: String?,
    /** 编译期 mod-api surface（缺失 / 坏值 ⇒ null = 未声明，老包常态；坏值只丢本字段）。 */
    val compiledSurface: Int?,
    /** 入口类全名（缺失 / 空白 ⇒ null，调用方 fail-closed 拒载——没有入口就无法产出 EraMod）。 */
    val entrypoint: String?,
)

/**
 * 解析 dex 包内 `manifest.json` 条目字节（D1 包形态）。
 *
 * [entry] = null（条目缺失 / 超上限 / 读盘失败）或 JSON 损坏 ⇒ 全 null 元数据，调用方按
 * 「元数据无效」fail-closed。字段口径与 eratw-pack.json 解析同源：packageId 走
 * [ModEnablement.isValidPackageId] 形状闸、compiledSurface 坏值只丢本字段不连坐整份。
 */
internal fun parseDexPackageMeta(entry: ByteArray?): DexPackageMeta {
    val obj = entry
        ?.let { bytes -> runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull() }
        ?.let { text -> MarketJson.parseOrNull(text) as? JsonObject }
        ?: return DexPackageMeta(packageId = null, version = null, compiledSurface = null, entrypoint = null)
    return DexPackageMeta(
        packageId = (obj.opt("packageId") as? JsonString)?.value?.takeIf { ModEnablement.isValidPackageId(it) },
        version = (obj.opt("version") as? JsonString)?.value,
        compiledSurface = (obj.opt(DEX_META_COMPILED_SURFACE_KEY) as? JsonNumber)?.intValueOrNull()
            ?.takeIf { it in 0..PACK_META_MAX_COMPILED_SURFACE },
        entrypoint = (obj.opt("entrypoint") as? JsonString)?.value?.takeIf { it.isNotBlank() },
    )
}

/**
 * dex 载体供给核心（纯 JVM；测试注入 factory 与总闸快照即可全跑）。
 *
 * 逐包流程（每包独立收口，一包失败不影响其余包）：
 * 1. `.off` 停用过滤（ADR-013 D4 玩家面第一条：停用的包**连 ClassLoader 都不建**，
 *    判据与 Kotlin 载体的停用过滤同源）；
 * 2. 产物清点：多于一个产物 = 缺版本引用台账，拒猜（fail-closed）；
 * 3. 读产物 zip 内 `manifest.json`（[parseDexPackageMeta]）：缺失 / 坏档 / 自述包名与目录名
 *    不符 / 未声明入口 ⇒ fail-closed（任何 mod 代码都还没执行——D2 硬约束）；
 * 4. 闸序完整走 [ModDexLoader.load]（ABI 闸 → 总闸 → 磁盘包名 → 身份解析 → 信任 → 盘面 →
 *    sha 复算 → 工厂）；
 * 5. 入口实例化：loadClass → `EraMod` 可指派性判**在构造之前**（防入口串成任意应用类被
 *    实例化）→ 反射构造 → provider 对。
 *
 * 失败一律收敛成归因行：[logSink] 每包一行（`ModManageDiagnostics` 的 canonical 形状，
 * 设置页失败小节渲染），[onAttribution] 非空时再送生产取证面（S7）。本类不向上抛。
 */
internal class ModDexCarrierSource(
    private val gameRoot: File,
    private val factory: DexLoaderFactory,
    /** 总闸会话快照（生产 = 构造期从 UiPrefs 单路读入；关态整段旁路，D5）。 */
    private val dexLoaderEnabled: Boolean,
    private val logSink: (String) -> Unit,
    private val engineApiVersion: Int = ModApi.VERSION,
    /** 生产取证面（S7）：每包失败回调一次；测试缺省 null。 */
    private val onAttribution: ((packageId: String, reason: String) -> Unit)? = null,
) {
    private val rt2Dir: File = ModEnablement.rt2Dir(gameRoot)
    private val codeStore = ModCodeStore(rt2Dir)
    private val loader = ModDexLoader(
        codeStore = codeStore,
        gate = ModDexGate::verdict,
        factory = factory,
        logSink = logSink,
        dexLoaderEnabled = dexLoaderEnabled,
    )

    /** 本次装配的 dex 载体名单；关态空表（不扫盘），开态逐包收口后汇总。 */
    fun carriers(): List<Pair<String, EraMod>> {
        if (!dexLoaderEnabled) return emptyList()
        val packages = runCatching {
            ModTrustStore.codePackages(File(rt2Dir, MOD_CODE_DIR_NAME))
        }.getOrDefault(emptyList())
        return packages.asSequence().mapNotNull { carrierForPackage(it.packageId) }.toList()
    }

    /**
     * 单包取件（Lane DZ 页边界重装路径，ERAFIN2）：与 [carriers] 同一条闸链（`.off` 停用
     * 过滤 → 产物清点拒猜 → 元数据自洽 → 闸序 → 入口实例化）只跑点名包；失败收敛归因行
     * 返回 null，不向上抛。总闸关 = 与 [carriers] 同口径整段旁路。
     */
    internal fun carrierForPackage(packageId: String): Pair<String, EraMod>? {
        // 总闸关（D5）= 与 [carriers] 同口径整段旁路（不读盘、不进闸链、零构造）；
        // .off 停用（D4）= 静默跳过，与 Kotlin 载体的停用过滤同口径（不归因、零构造）。
        if (!dexLoaderEnabled || ModEnablement.isDisabled(rt2Dir, packageId)) return null
        val artifacts = runCatching { codeStore.list(packageId) }.getOrDefault(emptyList())
        return when {
            artifacts.size > 1 -> fail(packageId, REASON_MULTI_VERSION)
            else -> loadAndInstantiate(packageId, artifacts.singleOrNull())
        }
    }

    /** 单包装载：元数据自洽闸 → 闸序全链 → 入口实例化；任一步失败都已是归因行（null 返回）。 */
    private fun loadAndInstantiate(packageId: String, artifact: CodeArtifact?): Pair<String, EraMod>? {
        val meta = readMeta(packageId, artifact) ?: return null
        return when (val outcome = loader.load(packageId, packMetaOf(meta), engineApiVersion)) {
            is LoadOutcome.Loaded -> entrypointMod(packageId, requireNotNull(meta.entrypoint), outcome.loader)
            else -> fail(packageId, outcome.reasonForFailure())
        }
    }

    /**
     * 读产物 zip 内的包元数据并做身份自洽闸（自述包名 = 落地目录名、入口已声明）。
     *
     * `ModZip.fixedEntryBytes` 现在内层 zip 也过 [ModZipGuard] 五道闸（产物内层没有 ⓪ 可借，
     * 闸拒绝说明产物内藏压缩炸弹）：[ModZipGuard.Rejected] 折 [REASON_ARTIFACT_INNER_BOMB]、
     * 其余 IO 崩坏折 [REASON_ARTIFACT_UNREADABLE]——两者都只跳过该包。这里必须就地收口：
     * [ModRuntimeAssembly.attach] 对 provision 返回值没有兜底，任何抛出都会把整局装配带崩
     * （本类「任何情况下不向上抛」纪律此前只在这一处没折满）。
     */
    private fun readMeta(packageId: String, artifact: CodeArtifact?): DexPackageMeta? {
        val zipBytes = artifact?.let { art -> runCatching { art.file.readBytes() }.getOrNull() }
            ?: return fail(packageId, REASON_ARTIFACT_UNREADABLE)
        val manifest = runCatching {
            ModZip.fixedEntryBytes(zipBytes, DEX_MANIFEST_ENTRY_NAME, DEX_META_MAX_ENTRY_BYTES)
        }
        return manifest.fold(
            onSuccess = { bytes -> validateDexPackageMeta(packageId, parseDexPackageMeta(bytes)) },
            onFailure = { failure ->
                fail(
                    packageId,
                    if (failure is ModZipGuard.Rejected) REASON_ARTIFACT_INNER_BOMB else REASON_ARTIFACT_UNREADABLE,
                )
            },
        )
    }

    /** 自述面与落地目录名对账（[readMeta] 纪律的一部分：任何不自洽都折归因，不向上抛）。 */
    private fun validateDexPackageMeta(packageId: String, meta: DexPackageMeta): DexPackageMeta? = when {
        meta.packageId == null -> fail(packageId, REASON_META_INVALID)
        meta.packageId != packageId -> fail(packageId, REASON_PACKAGE_ID_MISMATCH)
        meta.entrypoint == null -> fail(packageId, REASON_ENTRYPOINT_NOT_DECLARED)
        else -> meta
    }

    /** 入口类解析：加载 + 可指派性闸都过才算入口（可指派性判在构造之前，防串成任意应用类被实例化）。 */
    private fun entrypointType(packageId: String, entrypoint: String, classLoader: ClassLoader): Class<*>? {
        val type = runCatching { classLoader.loadClass(entrypoint) }.getOrNull()
        return when {
            type == null -> fail(packageId, REASON_ENTRYPOINT_NOT_FOUND)
            !EraMod::class.java.isAssignableFrom(type) -> fail(packageId, REASON_ENTRYPOINT_NOT_MOD)
            else -> type
        }
    }

    /** 入口实例化（入口类型已过可指派性闸）；构造失败收敛成归因行。 */
    private fun entrypointMod(
        packageId: String,
        entrypoint: String,
        classLoader: ClassLoader,
    ): Pair<String, EraMod>? {
        val type = entrypointType(packageId, entrypoint, classLoader) ?: return null
        val mod = runCatching { type.getDeclaredConstructor().newInstance() as EraMod }.getOrNull()
        return mod?.let { packageId to it } ?: fail(packageId, REASON_ENTRYPOINT_NOT_INSTANTIABLE)
    }

    /** ABI 闸入参组装：dex 包元数据 → PackMeta（闸只读 compiledSurface，其余原样随行）。 */
    private fun packMetaOf(meta: DexPackageMeta): PackMeta = PackMeta(
        packageId = meta.packageId,
        declaredVersion = meta.version,
        version = meta.version ?: PACK_META_FALLBACK_VERSION,
        ignored = null,
        evidence = PackMetaEvidence(compiledSurface = meta.compiledSurface),
    )

    /**
     * 单包失败收口：一行 canonical 归因行（设置页失败小节经 ModManageDiagnostics 渲染）+
     * 生产取证回调。两个 sink 都各自 runCatching——诊断面绝不把装配带崩。
     */
    private fun fail(packageId: String, reason: String): Nothing? {
        runCatching {
            logSink(MOD_INSTALL_ROW_PREFIX + packageId + MOD_INSTALL_ROW_FAILED_SUFFIX + reason)
        }
        runCatching { onAttribution?.invoke(packageId, reason) }
        return null
    }
}

/** [LoadOutcome] 失败面 → 归因行载荷（与 S4a 的可上屏 reason 同一份，Loaded 不适用）。 */
private fun LoadOutcome.reasonForFailure(): String = when (this) {
    is LoadOutcome.Loaded -> ""
    is LoadOutcome.Disabled -> reason
    is LoadOutcome.Rejected -> reason
    is LoadOutcome.NeedsTrust -> reason
    is LoadOutcome.InvalidPackage -> reason
    is LoadOutcome.MissingArtifact -> reason
    is LoadOutcome.AmbiguousArtifact -> reason
    is LoadOutcome.IntegrityFailed -> reason
    is LoadOutcome.IoFailed -> reason
    is LoadOutcome.LoaderFailed -> reason
    is LoadOutcome.CorruptIdentity -> reason
}

/** 归因行前缀：与 ModManageDiagnostics 的 canonical 装载行同形（对账由定向用例钉死）。 */
private const val MOD_INSTALL_ROW_PREFIX: String = "mod.install pkg="
/** 归因行失败段：与 ModManageDiagnostics 的 `active=false failure=` 同形。 */
private const val MOD_INSTALL_ROW_FAILED_SUFFIX: String = " active=false failure="

/**
 * dex 载体供给的**生产装配**：总闸偏好读数（构造期读一次 = 会话快照，下一次会话生效，
 * ADR-013 D5）+ 生产工厂 + S7 取证回调一并缝进 [DexCarrierProvision]。
 *
 * 生产工厂 = [AndroidDexLoaderFactory]（main 既有生产构造点，dexrun 波所产：D1 参数序、
 * parent = context.classLoader 即 AppClassLoader、optimizedDirectory = codeCacheDir、
 * 每次 create 新开 loader——D4 重装语义）。
 *
 * 线程纪律：本函数须在**主线程 / 壳层**调用（SharedPreferences 读数照既有单路纪律，
 * `MainActivity` 读 `modRuntime` 构造参数传入是同一条路）；返回的缝在装配线程被
 * `ModRuntimeAssembly.attach` 消费，消费时不再碰偏好表。
 */
internal fun androidDexCarrierProvision(context: Context): DexCarrierProvision {
    val enabled = UiPrefs.modDexLoaderEnabled(context)
    val factory = AndroidDexLoaderFactory(context)
    val appContext = context.applicationContext
    return object : DexCarrierProvision {
        // 供给核无跨调用状态（rt2Dir/codeStore/loader 都随 gameRoot 现造），逐次现建与
        // 原 lambda 语义逐位等价；两个入口共享同一构造，闸序天然同源。
        private fun sourceFor(gameRoot: File, logSink: (String) -> Unit): ModDexCarrierSource =
            ModDexCarrierSource(
                gameRoot = gameRoot,
                factory = factory,
                dexLoaderEnabled = enabled,
                logSink = logSink,
                onAttribution = { packageId, reason ->
                    // S7：装配期 dex 失败进 ErrorReportCollector 既有诊断通道（与 GameHost 的
                    // CALLSHARP / 侧车告警同一通道：每型每局头两次落代表报告，其余聚合计数）。
                    ErrorReportCollector.recordDiagnostic(
                        appContext,
                        DiagnosticEventLog.DiagnosticKind.LOAD_WARNING,
                        null,
                        "dex 载体装载失败 pkg=$packageId reason=$reason",
                    )
                },
            )

        override fun carriers(gameRoot: File, logSink: (String) -> Unit): List<Pair<String, EraMod>> =
            sourceFor(gameRoot, logSink).carriers()

        // Lane DZ（ERAFIN2）：页边界重装路径的单包取件——总闸快照与归因回调与 carriers 同源。
        override fun carrierFor(gameRoot: File, packageId: String, logSink: (String) -> Unit): Pair<String, EraMod>? =
            sourceFor(gameRoot, logSink).carrierForPackage(packageId)
    }
}
