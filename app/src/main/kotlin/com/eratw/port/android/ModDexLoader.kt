package com.eratw.port.android

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** 内容寻址文件名里的 sha256 前缀位数（落在 mods/rt2/code 下的产物地址）。 */
private const val SHA_PREFIX_LENGTH: Int = 16

/** 包清单登记引用时使用的完整 sha256 形态（64 位十六进制，大小写不敏感）。 */
private val SHA256_HEX: Regex = Regex("[0-9a-fA-F]{64}")

private const val REASON_MASTER_SWITCH_OFF: String =
    "dex 装载总闸未开启，本次不装载运行包（重开会话后生效）"
private const val REASON_PACKAGE_ID_INVALID: String = "运行包包名不合法，拒绝装载"
private const val REASON_CODE_ID_CORRUPT: String =
    "代码身份侧车损坏，无法确认该运行包的运行期身份，拒绝装载"
private const val REASON_NEEDS_TRUST: String =
    "该运行包尚未获得信任确认，未装载（确认信任后重开会话生效）"
private const val REASON_CODE_DIR_UNREADABLE: String = "读取运行包代码目录失败，未装载"
private const val REASON_ARTIFACT_MISSING: String = "盘上找不到该运行包的代码产物，请重新导入"
private const val REASON_ARTIFACT_VERSION_MISSING: String =
    "盘上找不到包清单引用的那个代码产物版本，请重新导入"
private const val REASON_ARTIFACT_AMBIGUOUS: String =
    "盘上存在多个版本的代码产物而包清单未指定引用，拒绝猜用哪一个"
private const val REASON_SHA_ARGUMENT_MALFORMED: String =
    "包清单登记的 sha256 不是 64 位十六进制，拒绝装载"
private const val REASON_ARTIFACT_EMPTY: String = "代码产物为空，拒绝装载"
private const val REASON_SHA_MISMATCH: String =
    "代码产物的 sha256 与内容寻址文件名不符，疑似损坏或被篡改，拒绝装载"
private const val REASON_ARTIFACT_UNREADABLE: String = "读取代码产物失败，未装载"
private const val REASON_LOADER_FAILED: String = "类加载器构造失败，未装载"
private const val REASON_LOADER_FACTORY_ABSENT: String = "类加载器工厂未装配，未装载"

/** 日志里「运行期身份尚未解析」的占位：结构上只有更早的闸（ABI/总闸/绑定前）会给出 null。 */
private const val RUNTIME_ID_UNRESOLVED: String = "-"

/**
 * 第三方运行包的类加载器工厂（ADR-013 S4a 接口 + S4c 生产实现 [AndroidDexLoaderFactory]）。
 *
 * 生产实现的两条硬约束（ADR-013 D1/D4）：
 * 1. parent 必须是 AppClassLoader；parent 置空会让 ModApi 类型身份分裂，mod 与本进程互不可见。
 * 2. 每包一个 ClassLoader，且只指向内容寻址产物的单文件；DZ 重装必须新开 loader，不要复用旧实例。
 */
internal fun interface DexLoaderFactory {
    fun create(packageId: String, artifactFile: File): ClassLoader
}

/**
 * 装载结果的封闭数据面（ADR-013 D3：失败不向上抛，全部收敛成带可上屏 reason 的数据类）。
 *
 * 调用方只按类型分支消费；[reason] 是给玩家看的中文文案，不掺内部类名与异常名
 * （内部细节只经 logSink 落诊断日志）。
 *
 * ADR-013 S6 起结果面同时携带两个身份，二者结构性不同、不能混用：
 * - [diskPackageId]：内容寻址的磁盘命名空间 id（`code/<磁盘 id>/` 目录名、`.codeid` 侧车名），
 *   只用来**定位盘面产物**，随源集合变化、玩家不可读；
 * - [runtimePackageId]：**运行期身份**（`.codeid` 侧车声明的作者 id；无侧车时回退磁盘 id），
 *   包名形状闸、信任标记、上屏与日志都用它；`null` = 该失败发生在身份解析之前（例如总闸关）。
 *
 * 向后兼容读口 [packageId]（= 运行期身份，解析前回退磁盘 id）保留给既有调用点；新代码请直接读
 * [runtimePackageId] / [diskPackageId]，不要猜 [packageId] 是哪一边。
 */
internal sealed interface LoadOutcome {

    /** 磁盘命名空间身份：只用于定位产物与侧车，不参与信任判定。 */
    val diskPackageId: String

    /** 运行期身份：过信任闸、上屏、日志用；`null` 表示更早的闸已拒绝、身份未解析。 */
    val runtimePackageId: String?

    /** 兼容读口（旧调用点只读这个）：运行期身份，未解析时回退磁盘 id。 */
    val packageId: String get() = runtimePackageId ?: diskPackageId

    /** 装载成功：类加载器已就绪，生命周期与卸载由调用方按 ADR-013 D4 管理。 */
    data class Loaded(
        override val diskPackageId: String,
        val sha256Hex: String,
        val loader: ClassLoader,
        override val runtimePackageId: String = diskPackageId,
    ) : LoadOutcome

    /** 总闸关（ADR-013 D5）：版本闸之后立刻返回，链上任何盘面与类加载器都没进去。 */
    data class Disabled(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** ABI 双闸拒绝（ADR-013 D2）：meta 声明面超前或落在 compat 表登记的 breaking 区间。 */
    data class Rejected(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 没有按运行期身份找到信任标记（ADR-013 D3）：未信任不建 ClassLoader。 */
    data class NeedsTrust(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 包名不满足形状闸：在拼信任标记/侧车/产物路径前挡掉，避免路径穿越。 */
    data class InvalidPackage(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 盘上连该包的 code 目录/产物都没有：多半是导入没落盘或已被卸载清理。 */
    data class MissingArtifact(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 盘上多版本且包清单没给 expectedSha256：拒绝猜版本，宁可报错交给导入侧修引用。 */
    data class AmbiguousArtifact(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 内容寻址校验失败（ADR-013 D1）：sha256 与文件名不符、空体、清单 sha 形态非法。 */
    data class IntegrityFailed(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 盘面 IO 失败（枚举 code 目录、读产物）：不向上抛，调用方可按可重试处理。 */
    data class IoFailed(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /** 类加载器工厂构造失败：mod 包体问题或平台拒绝，同样不向上抛。 */
    data class LoaderFailed(
        override val diskPackageId: String,
        val reason: String,
        override val runtimePackageId: String? = null,
    ) : LoadOutcome

    /**
     * 代码身份侧车坏档（ADR-013 S6）：`.codeid` 在场但读不懂 ⇒ fail-closed 拒载。
     *
     * 不猜、也不静默退回磁盘身份：猜错会把 A 包的代码装进 B 包的信任面。运行期身份为 `null`
     * （本结果就是在「身份不可信」时产生的）；[sidecarPath] 与 [reason] 点名坏档路径，
     * 供玩家/诊断日志按路径取证后重新导入。
     */
    data class CorruptIdentity(
        override val diskPackageId: String,
        val sidecarPath: String,
        val reason: String,
    ) : LoadOutcome {
        override val runtimePackageId: String? get() = null
    }
}

/**
 * ADR-013 S4a/S6：第三方运行包的装载骨架（闸序 + 数据面 + 工厂注入）。
 *
 * 固定顺序（每一步都在下一步的 IO 之前；ADR-013 S6 只替换第 3–5 步的主体身份，不重排闸序）：
 * 1. ABI 闸：`gate.verdict` 是纯内存判定（ADR-013 D2），拒绝时零盘面访问、零类加载器构造；
 * 2. 总闸：`dexLoaderEnabled == false` 直接 [LoadOutcome.Disabled]，不扫 code 目录（D5）；
 * 3. **磁盘**包名形状：非法磁盘 id 直接 [LoadOutcome.InvalidPackage]，不拼任何路径
 *    （侧车定位与 `code/<磁盘 id>/` 产物目录都靠它拼，所以这一闸必须先于身份解析；
 *    `../evil` 这类形态到不了盘面）；
 * 4. 身份解析（S6）：读 `rt2/<磁盘 id>.codeid` —— [CodeIdSidecar.Bound] 用作者 id 作运行期身份，
 *    [CodeIdSidecar.Absent] 回退磁盘 id，[CodeIdSidecar.Corrupt] 直接 [LoadOutcome.CorruptIdentity]
 *    拒载（fail-closed，不猜、不静默退回磁盘身份）；
 * 5. 信任确认：按**运行期身份**找 `<运行期 id>.trusted`，无标记直接 [LoadOutcome.NeedsTrust]（D3）；
 * 6. 盘面取产物：仍按**磁盘 id** 在内容寻址目录里按 sha 前缀定位，多版本未指定引用时拒绝猜；
 * 7. sha256 复算：与文件名前缀比对不符即 [LoadOutcome.IntegrityFailed]（D1 的落地校验点）；
 * 8. 建类加载器：只经 [DexLoaderFactory]，包标签用运行期身份，抛异常收敛成 [LoadOutcome.LoaderFailed]。
 *
 * 本类只管到「类加载器就绪」；把包内入口类实例化为 [com.eratw.port.modapi.EraMod]
 * 由 [ModDexModuleLoader] 负责，载体注册表/运行时装配不在本切片。
 *
 * ## 接线（MODDX4-DEX C4 起生产链已通）
 * 导入/装配侧从 [LoadOutcome] 取两个身份：上屏、信任与去重按键用
 * [LoadOutcome.runtimePackageId]；产物回收/卸载/GC 仍按 [LoadOutcome.diskPackageId]。
 * 生产装配供给核心 = [ModDexCarrierSource]（扫 code 目录 → 逐包过闸 → 入口实例化），
 * 装配缝 = `DexCarrierProvision`，`ModRuntimeAssembly.attach` 在 install 前并装；
 * 导入期侧车写入已由 [ModCodeIdentity.write] 落地；本刀不改导入控制器（[ModImportController]）。
 *
 * @param gate ABI 双闸判定入口，默认即 [ModDexGate.verdict]（注入是为了用例可数调用次数并断言闸序）
 * @param factory 类加载器工厂；`null` 表示装配时总闸关、工厂一行都没构造（ADR-013 D5），
 *   此时装载链在闸 2 就返回 [LoadOutcome.Disabled]，factory 分支不可达，判空只是保守防御
 * @param dexLoaderEnabled 总闸的会话快照：必须在构造时从 [UiPrefs] 单路读入，装载期不再读偏好
 */
internal class ModDexLoader(
    private val codeStore: ModCodeStore,
    private val gate: (PackMeta, Int, ModDexGate.SurfaceCompat) -> ModDexGate.Verdict,
    private val factory: DexLoaderFactory?,
    private val logSink: (String) -> Unit,
    private val dexLoaderEnabled: Boolean,
) {
    /**
     * 装载一个运行包并给出封闭结果；任何失败都不抛给调用方。
     *
     * @param diskPackageId 磁盘命名空间身份（`mods/rt2` 下的子目录名与 `.codeid` 侧车名前缀）：
     *   产物与侧车都按它定位，信任闸与上屏不用它
     * @param meta 导入期解析出的包 meta；`compiledSurface == null` 表示老包未声明编译面，直接放行
     * @param engineApiVersion 本会话的 ModApi.VERSION（闸 1 判据）
     * @param compat 闸 2 的 compat 表，默认空表（空表不额外拦任何 surface）
     * @param expectedSha256 包清单登记的完整 sha256（64 位十六进制）；多版本时用它选版本。
     *   省略且盘上唯一产物仍会装载；省略且多版本返回 [LoadOutcome.AmbiguousArtifact]
     */
    fun load(
        diskPackageId: String,
        meta: PackMeta,
        engineApiVersion: Int,
        compat: ModDexGate.SurfaceCompat = ModDexGate.SurfaceCompat.EMPTY,
        expectedSha256: String? = null,
    ): LoadOutcome {
        val outcome = decide(diskPackageId, meta, engineApiVersion, compat, expectedSha256)
        trace(outcome)
        return outcome
    }

    private fun decide(
        diskPackageId: String,
        meta: PackMeta,
        engineApiVersion: Int,
        compat: ModDexGate.SurfaceCompat,
        expectedSha256: String?,
    ): LoadOutcome {
        val verdict = gate(meta, engineApiVersion, compat)
        return when {
            verdict is ModDexGate.Verdict.Rejected -> LoadOutcome.Rejected(diskPackageId, verdict.reason)
            !dexLoaderEnabled -> LoadOutcome.Disabled(diskPackageId, REASON_MASTER_SWITCH_OFF)
            !ModEnablement.isValidPackageId(diskPackageId) ->
                LoadOutcome.InvalidPackage(diskPackageId, REASON_PACKAGE_ID_INVALID)
            else -> afterIdentityGate(diskPackageId, expectedSha256)
        }
    }

    /**
     * 第 4–5 步（ADR-013 S6）：先解析运行期身份，再按它过信任闸；产物仍按磁盘 id 定位。
     *
     * 磁盘 id 在这里一定已过形状闸：侧车路径由它拼出，穿越形态在上一步就挡掉了。
     */
    private fun afterIdentityGate(diskPackageId: String, expectedSha256: String?): LoadOutcome {
        val identity = ModDexIdentity.resolve(codeStore.codeDir().parentFile, diskPackageId)
        return when (identity) {
            is DexCodeIdentity.Corrupt -> LoadOutcome.CorruptIdentity(
                diskPackageId = diskPackageId,
                sidecarPath = identity.sidecarPath,
                reason = REASON_CODE_ID_CORRUPT + "（" + identity.sidecarPath + "）",
            )
            is DexCodeIdentity.Resolved -> when {
                // Bound 的 id 在侧车解析器里已过同一形状闸；这里再判一次，是为了让「信任标记路径
                // 的拼装前置条件」不依赖别的模块（形状闸哪天放宽，拒载也发生在这里而不是 require 抛出）。
                !ModEnablement.isValidPackageId(identity.runtimePackageId) ->
                    LoadOutcome.InvalidPackage(diskPackageId, REASON_PACKAGE_ID_INVALID, identity.runtimePackageId)
                !isTrusted(identity.runtimePackageId) ->
                    LoadOutcome.NeedsTrust(diskPackageId, REASON_NEEDS_TRUST, identity.runtimePackageId)
                else -> loadArtifact(diskPackageId, identity.runtimePackageId, expectedSha256)
            }
        }
    }

    private fun isTrusted(runtimePackageId: String): Boolean {
        val rt2Dir = codeStore.codeDir().parentFile ?: return false
        return runCatching { ModDexGate.isTrusted(rt2Dir, runtimePackageId) }.getOrDefault(false)
    }

    private fun loadArtifact(
        diskPackageId: String,
        runtimePackageId: String,
        expectedSha256: String?,
    ): LoadOutcome {
        val artifacts = runCatching { codeStore.list(diskPackageId) }.getOrNull()
            ?: return LoadOutcome.IoFailed(diskPackageId, REASON_CODE_DIR_UNREADABLE, runtimePackageId)
        val declaredSha256 = expectedSha256?.trim()?.lowercase()
        return when {
            artifacts.isEmpty() ->
                LoadOutcome.MissingArtifact(diskPackageId, REASON_ARTIFACT_MISSING, runtimePackageId)
            declaredSha256 == null && artifacts.size == 1 ->
                verifyAndLoad(diskPackageId, runtimePackageId, artifacts.first())
            declaredSha256 == null -> LoadOutcome.AmbiguousArtifact(
                diskPackageId,
                REASON_ARTIFACT_AMBIGUOUS + "（共 " + artifacts.size + " 个版本）",
                runtimePackageId,
            )
            !SHA256_HEX.matches(declaredSha256) ->
                LoadOutcome.IntegrityFailed(diskPackageId, REASON_SHA_ARGUMENT_MALFORMED, runtimePackageId)
            else -> loadSelected(diskPackageId, runtimePackageId, artifacts, declaredSha256)
        }
    }

    private fun loadSelected(
        diskPackageId: String,
        runtimePackageId: String,
        artifacts: List<CodeArtifact>,
        declaredSha256: String,
    ): LoadOutcome {
        val prefix = declaredSha256.take(SHA_PREFIX_LENGTH)
        val selected = artifacts.firstOrNull { it.sha256Hex.equals(prefix, ignoreCase = true) }
        return if (selected == null) {
            LoadOutcome.MissingArtifact(diskPackageId, REASON_ARTIFACT_VERSION_MISSING, runtimePackageId)
        } else {
            verifyAndLoad(diskPackageId, runtimePackageId, selected)
        }
    }

    private fun verifyAndLoad(
        diskPackageId: String,
        runtimePackageId: String,
        artifact: CodeArtifact,
    ): LoadOutcome {
        val bytes = runCatching { artifact.file.readBytes() }.getOrNull()
            ?: return LoadOutcome.IoFailed(diskPackageId, REASON_ARTIFACT_UNREADABLE, runtimePackageId)
        val actualSha256 = sha256Hex(bytes)
        return when {
            bytes.isEmpty() ->
                LoadOutcome.IntegrityFailed(diskPackageId, REASON_ARTIFACT_EMPTY, runtimePackageId)
            !actualSha256.startsWith(artifact.sha256Hex, ignoreCase = true) ->
                LoadOutcome.IntegrityFailed(diskPackageId, REASON_SHA_MISMATCH, runtimePackageId)
            else -> buildLoader(diskPackageId, runtimePackageId, artifact, actualSha256)
        }
    }

    private fun buildLoader(
        diskPackageId: String,
        runtimePackageId: String,
        artifact: CodeArtifact,
        sha256: String,
    ): LoadOutcome {
        val creator = factory
            ?: return LoadOutcome.LoaderFailed(diskPackageId, REASON_LOADER_FACTORY_ABSENT, runtimePackageId)
        val loader = runCatching { creator.create(runtimePackageId, artifact.file) }.getOrNull()
        return if (loader == null) {
            LoadOutcome.LoaderFailed(diskPackageId, REASON_LOADER_FAILED, runtimePackageId)
        } else {
            LoadOutcome.Loaded(diskPackageId, sha256, loader, runtimePackageId)
        }
    }

    /** 每个包每次装载一行诊断日志：磁盘 id 与运行期身份都打，方便把「盘上是谁」对上「装的是谁」。 */
    private fun trace(outcome: LoadOutcome) {
        val line = "mod.dex disk=" + outcome.diskPackageId +
            " runtime=" + (outcome.runtimePackageId ?: RUNTIME_ID_UNRESOLVED) +
            " " + describeOutcome(outcome)
        runCatching { logSink(line) }
    }

    private fun describeOutcome(outcome: LoadOutcome): String = when (outcome) {
        is LoadOutcome.Loaded -> "outcome=loaded sha=" + outcome.sha256Hex.take(SHA_PREFIX_LENGTH)
        is LoadOutcome.Disabled -> "outcome=disabled reason=" + outcome.reason
        is LoadOutcome.Rejected -> "outcome=rejected reason=" + outcome.reason
        is LoadOutcome.NeedsTrust -> "outcome=needs-trust reason=" + outcome.reason
        is LoadOutcome.InvalidPackage -> "outcome=invalid-package reason=" + outcome.reason
        is LoadOutcome.MissingArtifact -> "outcome=missing-artifact reason=" + outcome.reason
        is LoadOutcome.AmbiguousArtifact -> "outcome=ambiguous-artifact reason=" + outcome.reason
        is LoadOutcome.IntegrityFailed -> "outcome=integrity-failed reason=" + outcome.reason
        is LoadOutcome.IoFailed -> "outcome=io-failed reason=" + outcome.reason
        is LoadOutcome.LoaderFailed -> "outcome=loader-failed reason=" + outcome.reason
        is LoadOutcome.CorruptIdentity -> "outcome=corrupt-identity sidecar=" + outcome.sidecarPath
    }

    companion object {
        /**
         * 壳层装配入口：总闸只在装配线程（既有纪律：UI 线程）从 [UiPrefs] 读一次，
         * 之后以 Boolean 单路注入；装载期不再碰 SharedPreferences，后台线程可安全调用 [load]。
         *
         * 工厂选择（ADR-013 S4c）：总闸关 → 工厂为 `null` 且一行都不构造（D5）；
         * 总闸开 → 注入的 [factory] 优先（JVM 单测用 fake 替换），否则惰性构造 [AndroidDexLoaderFactory]。
         */
        fun forContext(
            context: Context,
            codeStore: ModCodeStore,
            factory: DexLoaderFactory? = null,
            logSink: (String) -> Unit = {},
        ): ModDexLoader {
            val dexLoaderEnabled = UiPrefs.modDexLoaderEnabled(context)
            return ModDexLoader(
                codeStore = codeStore,
                gate = ModDexGate::verdict,
                factory = resolveDexLoaderFactory(dexLoaderEnabled, factory) {
                    AndroidDexLoaderFactory(context)
                },
                logSink = logSink,
                dexLoaderEnabled = dexLoaderEnabled,
            )
        }
    }
}

/**
 * 装配期的工厂选择：总闸关返回 `null`（不构造任何工厂），总闸开时注入优先、否则走生产 lambda。
 *
 * 抽成顶层纯函数，是为了让「总闸关零工厂构造」与「JVM fake 替换」都能在 JVM 单测里数调用次数；
 * 生产 lambda 在总闸关时不会被调用（[ModDexLoader.forContext] 的惰性构造）。
 */
internal fun resolveDexLoaderFactory(
    enabled: Boolean,
    injected: DexLoaderFactory?,
    production: () -> DexLoaderFactory,
): DexLoaderFactory? {
    if (!enabled) return null
    return injected ?: production()
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(separator = "") { "%02x".format(it) }
