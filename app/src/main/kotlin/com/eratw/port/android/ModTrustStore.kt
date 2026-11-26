package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/*
 * ADR-013 D3 / S4b：第三方代码包的首次信任确认数据面。
 *
 * 信任标记与 ModEnablement 的 .off 标记同构（rt2 下的 <pkg>.trusted，空文件），
 * 由 ModDexGate.trustMarkerName / isTrusted 读同一份盘面；本文件只负责写/删标记与
 * 列出可信任包，不改变装载判定本身（ModDexLoader 已把未信任映射成 NeedsTrust）。
 * 信任只是「用户同意在下次装载时运行该包」的记录：同一个进程、没有沙箱，
 * 所以设置页必须先给出诚实的二次确认文案再落到这里。
 */

/** 一个在 code 目录下带有合法编译产物的包，以及在盘上的产物个数。 */
data class ModTrustPackage(val packageId: String, val artifactCount: Int)

/**
 * 设置页「第三方代码包」一行：包名、产物数、当前信任态（由标记文件实读得出）。
 *
 * [blockedReason] 非 null = 该包的身份侧车坏了、运行期身份判不了（[ModTrustStore.resolveMarkerId]
 * 返回 Blocked）：此时 [trusted] 恒 false，且信任 / 撤销两个方向都不可写，UI 要显示身份损坏而不是给按钮。
 */
data class ModTrustRow(
    val packageId: String,
    val artifactCount: Int,
    val trusted: Boolean,
    val blockedReason: TrustMarkerBlockReason? = null,
)

/**
 * 信任操作被受控拒绝的原因（[MarkerTarget.Blocked] 的载荷，UI 据此上屏）。
 *
 * 两种都是「盘面一个字节都不碰」的失败：包名形态非法时拼不出合法文件名；身份侧车损坏时落哪个
 * 标记都可能是别的包的，所以宁可失败也不静默退化成磁盘身份。
 */
enum class TrustMarkerBlockReason {
    /** 包名不过 [ModEnablement.isValidPackageId]：拒绝拼路径，盘面不变。 */
    INVALID_PACKAGE_ID,

    /** `.codeid` 侧车损坏：运行期身份不可判，只能受控失败。 */
    IDENTITY_CORRUPT,
}

/**
 * 信任标记的落盘身份解析结论（[ModTrustStore.resolveMarkerId] 的唯一出口）。
 *
 * 第三方代码包有两套身份：磁盘身份（`code/<diskId>/` 目录名）与运行期身份（`.codeid` 侧车里
 * 作者声明的 id）。标记文件与装载闸的读取面统一按**运行期身份**命名，故所有写 / 删 / 显示都要先经
 * [ModDexIdentity.resolve] 桥一次；侧车缺失时运行期身份回落到磁盘身份（既有行为逐字节不变）。
 */
internal sealed interface MarkerTarget {
    /** 可落盘 / 可读：[markerId] 就是标记文件名里的那个身份。 */
    data class Target(val markerId: String) : MarkerTarget

    /** 不可落盘：身份判不了，调用方必须受控失败（返回 false + 上屏原因），不许猜。 */
    data class Blocked(val reason: TrustMarkerBlockReason) : MarkerTarget
}

/**
 * 信任面的一次读数：[Known] = 身份判得出来、[Known.trusted] 是标记实读；
 * [Blocked] = 身份侧车坏档或包名非法，界面该显示「身份损坏」而不是「未信任」。
 *
 * 概览卡 / 信任表 / 导入收尾面都从这里拿读数，避免「解析身份」与「读标记」各走一遍、判据漂移。
 */
internal sealed interface TrustReadout {
    /** 身份判得出来：按解析出的运行期身份实读标记。 */
    data class Known(val trusted: Boolean) : TrustReadout

    /** 身份判不了（侧车坏档 / 包名非法）：调用方必须显式不可判，不许当成未信任。 */
    data class Blocked(val reason: TrustMarkerBlockReason) : TrustReadout
}

/**
 * 读一个磁盘身份包的信任态：唯一身份桥 [ModTrustStore.resolveMarkerId]，标记读取面
 * [ModDexGate.isTrusted]（与装载闸同源）。
 */
internal fun trustReadoutOf(rt2Dir: File, diskPackageId: String): TrustReadout =
    when (val target = ModTrustStore.resolveMarkerId(rt2Dir, diskPackageId)) {
        is MarkerTarget.Target -> TrustReadout.Known(
            runCatching { ModDexGate.isTrusted(rt2Dir, target.markerId) }.getOrDefault(false),
        )
        is MarkerTarget.Blocked -> TrustReadout.Blocked(target.reason)
    }

/**
 * 信任标记的读写面（纯 JVM，无 Android API）。
 *
 * 标记文件名统一按**运行期身份**（`<runtimeId>.trusted`，与装载闸 [ModDexGate.isTrusted] 的读取面
 * 同源），而调用方（设置页的行、卸载入口）传的是**磁盘身份**：两套身份的桥只有 [resolveMarkerId]
 * 一处，经 [ModDexIdentity.resolve] 复用 `.codeid` 侧车，本文件不另抄一套身份解析规则。
 *
 * 所有函数都不抛异常：非法包名、没有代码产物、标记路径越界或写盘失败一律返回 false，
 * 且保证盘面不变。写标记走「临时文件 + 原子换名」，避免半截文件被装载侧读成已信任。
 */
object ModTrustStore {
    /**
     * 把磁盘身份解析成信任标记身份：双身份包 ⇒ 运行期身份（`.codeid` 侧车里作者声明的 id），
     * 单身份包 ⇒ 磁盘身份。标记文件名、装载闸读取面与设置页读数都只认这一条桥。
     *
     * 判断只经 [ModDexIdentity.resolve]，不另造身份解析；侧车损坏（[DexCodeIdentity.Corrupt]）
     * 或包名形态非法时返回 [MarkerTarget.Blocked]，调用方必须受控失败而不是退化成磁盘身份。
     */
    internal fun resolveMarkerId(rt2Dir: File, diskPackageId: String): MarkerTarget {
        if (!ModEnablement.isValidPackageId(diskPackageId)) {
            return MarkerTarget.Blocked(TrustMarkerBlockReason.INVALID_PACKAGE_ID)
        }
        return when (val identity = ModDexIdentity.resolve(rt2Dir, diskPackageId)) {
            is DexCodeIdentity.Resolved -> MarkerTarget.Target(identity.runtimePackageId)
            is DexCodeIdentity.Corrupt -> MarkerTarget.Blocked(TrustMarkerBlockReason.IDENTITY_CORRUPT)
        }
    }

    /** [resolveMarkerId] 的失败原因读数；能解析时返回 null。 */
    internal fun markerBlockedReason(rt2Dir: File, diskPackageId: String): TrustMarkerBlockReason? =
        (resolveMarkerId(rt2Dir, diskPackageId) as? MarkerTarget.Blocked)?.reason

    /**
     * 磁盘身份 → 运行期身份；侧车损坏 / 包名非法时返回 null（调用方不猜）。
     *
     * 运行期身份不只是信任标记的落盘键：运行中会话的 storage 段也按它命名
     * （`storage.activate/release` 同键），所以回收投递与信任面共用这一条解析出口。
     */
    internal fun resolveRuntimeId(rt2Dir: File, diskPackageId: String): String? =
        (resolveMarkerId(rt2Dir, diskPackageId) as? MarkerTarget.Target)?.markerId

    /** 该包是否已被玩家显式信任：入参是磁盘身份，先解析出运行期身份再按标记实读。 */
    fun isTrusted(rt2Dir: File, diskPackageId: String): Boolean =
        when (val readout = trustReadoutOf(rt2Dir, diskPackageId)) {
            is TrustReadout.Known -> readout.trusted
            is TrustReadout.Blocked -> false
        }

    /**
     * 首次信任 [diskPackageId]：写空标记（与 .off 同字节形状）。
     *
     * 入参是磁盘身份（设置页的行来自 `code/` 目录），落盘身份经 [resolveMarkerId] 解析成运行期
     * 身份 —— 与装载闸的读取键同源，双身份包才不会「授权后仍 needs-trust」。
     * 已有标记时直接返回 true（幂等）；没有代码产物时拒写，防止给纯目录或空包落信任；
     * 身份不可判（侧车损坏）时受控失败且不写任何标记。
     */
    fun trust(rt2Dir: File, diskPackageId: String): Boolean =
        when (val target = resolveMarkerId(rt2Dir, diskPackageId)) {
            is MarkerTarget.Target -> TrustMarkerFiles.writeTrustMarker(rt2Dir, diskPackageId, target.markerId)
            is MarkerTarget.Blocked -> false
        }

    /**
     * 撤销信任 [diskPackageId]（入参同为磁盘身份）：解析后删运行期身份标记，立即生效。
     *
     * 同时清掉同名磁盘身份标记：旧版本误把双身份包的信任写在 `<diskId>.trusted` 上，那一份装载闸
     * 从来不读，但留着会让玩家觉得「撤销没生效」。本来就没有标记时返回 true（幂等）；
     * 身份不可判（侧车损坏）时受控失败，不猜该删哪个标记。
     */
    fun distrust(rt2Dir: File, diskPackageId: String): Boolean =
        when (val target = resolveMarkerId(rt2Dir, diskPackageId)) {
            is MarkerTarget.Target -> {
                val legacyCleared = target.markerId == diskPackageId ||
                    distrustMarker(rt2Dir, diskPackageId)
                val resolvedCleared = distrustMarker(rt2Dir, target.markerId)
                legacyCleared && resolvedCleared
            }
            is MarkerTarget.Blocked -> false
        }

    /**
     * 按标记身份删标记（入参就是标记文件名里的 id，即运行期身份）。
     *
     * 卸载 / GC 面已经在按运行期身份算认领关系（[ModManageUninstall] / [gcOrphanTrustMarkers]），
     * 直接走这一层，避免把运行期身份再当磁盘身份解析一遍。幂等：本来没有标记也算成功。
     */
    internal fun distrustMarker(rt2Dir: File, markerId: String): Boolean {
        val marker = TrustMarkerFiles.path(rt2Dir, markerId) ?: return false
        return if (marker.exists()) TrustMarkerFiles.delete(marker) else true
    }

    /** rt2 目录下已信任的包名：只认普通文件标记，目录、符号链接与非规范命名忽略，升序。 */
    fun trustedPackages(rt2Dir: File): List<String> {
        val suffix = ModDexGate.TRUST_MARKER_SUFFIX
        return rt2Dir.listFiles().orEmpty()
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.name.endsWith(suffix) }
            .map { it.name.removeSuffix(suffix) }
            .filter { ModEnablement.isValidPackageId(it) }
            .distinct()
            .sorted()
    }

    /**
     * code 目录下真正能装载的包：只认包目录里出现合法 `<16 位小写十六进制>.mod` 产物的包。
     *
     * 产物判定完全复用 ModCodeStore.list，本函数只按包聚合计数；空目录、坏文件名、
     * 符号链接目录都不进列表，避免设置页给出「能信任但其实没东西可装载」的假行。
     */
    fun codePackages(codeDir: File): List<ModTrustPackage> {
        val rt2Dir = codeDir.parentFile ?: return emptyList()
        val store = ModCodeStore(rt2Dir)
        return codeDir.listFiles().orEmpty()
            .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
            .map { it.name }
            .filter { ModEnablement.isValidPackageId(it) }
            .mapNotNull { packageId ->
                val count = runCatching { store.list(packageId).size }.getOrDefault(0)
                if (count == 0) null else ModTrustPackage(packageId, count)
            }
            .distinctBy { it.packageId }
            .sortedBy { it.packageId }
    }

}

/**
 * 信任标记的盘面机械层：路径规范化、产物存在性、删除与原子写。
 *
 * 从 [ModTrustStore] 拆出来只为守住 detekt 的「单 object 函数数」阈值；语义与判据一字未改，
 * 调用方仍只经 [ModTrustStore] 的公开面（信任 / 撤销 / 读数）。
 */
private object TrustMarkerFiles {
    private const val TMP_PREFIX = "trust-"
    private const val TMP_SUFFIX = ".tmp"

    /** 标记的规范路径；包名非法、路径越出 rt2 目录或标记本身是符号链接时返回 null。 */
    fun path(rt2Dir: File, markerId: String): File? = runCatching {
        val root = rt2Dir.canonicalFile.toPath()
        val marker = File(root.toFile(), ModDexGate.trustMarkerName(markerId))
        val resolved = marker.canonicalFile
        when {
            Files.isSymbolicLink(marker.toPath()) -> null
            !resolved.toPath().startsWith(root) -> null
            else -> resolved
        }
    }.getOrNull()

    fun hasCodeArtifact(rt2Dir: File, packageId: String): Boolean =
        runCatching { ModCodeStore(rt2Dir).list(packageId).isNotEmpty() }.getOrDefault(false)

    fun delete(marker: File): Boolean {
        marker.setWritable(true)
        return runCatching { marker.delete() }.getOrDefault(false) || !marker.exists()
    }

    /** 写信任标记（入参：磁盘身份用于「有没有代码产物」判据，标记身份用于落盘文件名）。 */
    fun writeTrustMarker(rt2Dir: File, diskPackageId: String, markerId: String): Boolean {
        val marker = path(rt2Dir, markerId) ?: return false
        return when {
            marker.isFile -> true
            !hasCodeArtifact(rt2Dir, diskPackageId) -> false
            else -> writeAtomically(marker)
        }
    }

    private fun writeAtomically(marker: File): Boolean =
        marker.parentFile?.let { dir -> writeIn(dir, marker) } ?: false

    private fun writeIn(dir: File, marker: File): Boolean {
        if (!dir.isDirectory && !dir.mkdirs()) {
            return false
        }
        return runCatching {
            val tmp = Files.createTempFile(dir.toPath(), TMP_PREFIX, TMP_SUFFIX)
            val moved = runCatching { moveAtomically(tmp, marker.toPath()) }
            if (moved.isFailure) {
                runCatching { Files.deleteIfExists(tmp) }
                moved.getOrThrow()
            }
            true
        }.getOrDefault(false)
    }
}

/**
 * 设置页「第三方代码包」小节的纯状态机：扫盘、待二次确认、确认落盘、撤销落盘。
 *
 * Compose 侧只负责渲染 [rows] 与 pending 弹窗；「取消确认不写盘」「撤销即时生效」
 * 这类语义都在这里，可脱离 UI 单测。
 */
class ModTrustBoard(private val rt2Dir: File) {
    /** 最近一次扫描得到的行读数。 */
    var rows: List<ModTrustRow> = emptyList()
        private set

    /** 当前等待二次确认的包；null = 没有弹窗。只由 requestTrust/cancelTrust/confirmTrust 改。 */
    var pendingTrustPackageId: String? = null
        private set

    /** 最近一次 confirmTrust / revoke 被受控拒绝的原因；null = 上一次没被身份解析挡住。 */
    var lastBlockedReason: TrustMarkerBlockReason? = null
        private set

    /** 重扫盘面：code 下的包 + 标记实读（按解析出的运行期身份对照），返回并记住新读数。 */
    fun scan(): List<ModTrustRow> {
        rows = ModTrustStore.codePackages(File(rt2Dir, MOD_CODE_DIR_NAME)).map { pack ->
            when (val readout = trustReadoutOf(rt2Dir, pack.packageId)) {
                is TrustReadout.Known -> ModTrustRow(pack.packageId, pack.artifactCount, readout.trusted)
                is TrustReadout.Blocked ->
                    ModTrustRow(pack.packageId, pack.artifactCount, false, readout.reason)
            }
        }
        return rows
    }

    /** 点「信任」：只进入待确认态，不写盘。 */
    fun requestTrust(packageId: String) {
        pendingTrustPackageId = packageId
    }

    /** 取消二次确认：不写任何标记，清掉 pending。 */
    fun cancelTrust() {
        pendingTrustPackageId = null
    }

    /** 确认信任：落标记并重扫；false = 没写成（无产物 / 非法包名 / 身份坏档 / 写盘失败）。 */
    fun confirmTrust(): Boolean {
        val packageId = pendingTrustPackageId ?: return false
        pendingTrustPackageId = null
        lastBlockedReason = ModTrustStore.markerBlockedReason(rt2Dir, packageId)
        val written = ModTrustStore.trust(rt2Dir, packageId)
        scan()
        return written
    }

    /** 撤销信任：即时删标记并重扫；false = 没删成，盘面仍按已信任显示。 */
    fun revoke(packageId: String): Boolean {
        lastBlockedReason = ModTrustStore.markerBlockedReason(rt2Dir, packageId)
        val removed = ModTrustStore.distrust(rt2Dir, packageId)
        scan()
        return removed
    }
}

/** 信任态显示文案：已信任 / 未信任，两条都来自三语文案表。 */
internal fun modTrustStateLabel(text: SettingsStrings, trusted: Boolean): String =
    if (trusted) text.modTrustStateTrusted else text.modTrustStateUntrusted

/** 一行状态读数：身份坏档 ⇒ 复用代码态「身份信息损坏」文案（补信任没用，只能重新导入）。 */
internal fun modTrustRowStateLabel(text: SettingsStrings, row: ModTrustRow): String =
    if (row.blockedReason != null) text.modCodeStateCorrupt else modTrustStateLabel(text, row.trusted)

/** 一行读数：「包名」· 代码产物 N 个，参数化句式来自三语文案表。 */
internal fun modTrustRowReadout(text: SettingsStrings, row: ModTrustRow): String =
    text.modTrustRowReadout(row.packageId, row.artifactCount)

/** 临时文件原子换名；部分文件系统不支持 ATOMIC_MOVE 时退回普通 move（与 ModCodeStore 同口径）。 */
private fun moveAtomically(tmp: Path, target: Path) {
    val moved = runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
        .getOrElse { if (it is AtomicMoveNotSupportedException) null else throw it }
    if (moved == null) {
        Files.move(tmp, target)
    }
}

// ----------------------------------------------------------------------
// 双身份信任标记 GC（ADR-013 S6 收口；调用点是 ModPackageInventory.gcOrphans）
// ----------------------------------------------------------------------

/**
 * 孤儿信任标记 GC：清掉 `rt2/` 下**确定无主**的 `<运行期身份>.trusted`。
 *
 * 为什么需要：信任标记按**运行期身份**命名（[ModDexGate.trustMarkerName] 与装载闸读取面同源），
 * 磁盘包名是编译派生的磁盘身份，两套身份靠 `.codeid` 侧车（[ModCodeIdentity]）桥接
 * （[ModDexIdentity] 是唯一解析出口）。包被显式卸载时 [ModManageUninstall] 会补删这份标记；
 * 但「导入后立刻卸载 / 包级 GC 删掉半成品 / 侧车损坏导致身份不可判」等路径可能留下
 * `<runtime>.trusted` 没人认领。留着的旧标记会让**同一个代码包重装后静默继承旧信任**——
 * 安全面不能只靠卸载单点兜住，所以装配前的 GC 必须按盘面重新算一遍认领关系。
 *
 * 认领集 = 仍在 `code/` 下有合法产物的包（[ModTrustStore.codePackages]）经 `.codeid` 解析出的
 * 运行期身份（侧车缺失时回退磁盘身份，与装载闸同口径）。只删不在认领集里的标记：
 *
 * - 任一存活包的侧车**损坏**（[DexCodeIdentity.Corrupt]）⇒ 该包的运行期身份不可判、可能认领
 *   任意标记 ⇒ 整轮保守跳过（宁留垃圾、不误删信任），只留一条 [SaveDiagnostics] 取证；
 * - `code/` 在场但读不动（权限 / 被普通文件占位）⇒ 认领集不可信，同样整轮跳过。
 *
 * 全程不抛（`runCatching` 兜住枚举与删除）；成功/失败都写 `MOD_PKG_GC` 取证面，与
 * [ModPackageInventory.gcOrphans] 其余清理面同口径。调用点必须在 [ModCodeReclaim.gcOrphans]
 * **之后**（那时残留包已定，见 [ModPackageInventory.gcOrphans]）。
 *
 * @return 本轮删掉的标记个数（0 = 没有孤儿 / 保守跳过；仅供单测与诊断读，不改装配结局）
 */
internal fun gcOrphanTrustMarkers(rt2Dir: File): Int {
    val markers = ModTrustStore.trustedPackages(rt2Dir)
    val owned = ownedRuntimeIds(rt2Dir, File(rt2Dir, MOD_CODE_DIR_NAME))
    if (owned == null) {
        SaveDiagnostics.note(
            "MOD_PKG_GC",
            ModDexGate.TRUST_MARKER_SUFFIX,
            "身份侧车损坏或 code/ 不可读，孤儿信任标记本轮保守跳过（残留下轮再判）",
        )
        return 0
    }
    var removed = 0
    for (packageId in markers) {
        if (packageId in owned) continue
        // 这里的 packageId 来自 trustedPackages，已经是标记身份（运行期身份），直接删，不再当磁盘身份解析。
        val cleared = ModTrustStore.distrustMarker(rt2Dir, packageId)
        if (cleared) removed++
        SaveDiagnostics.note(
            "MOD_PKG_GC",
            ModDexGate.trustMarkerName(packageId),
            if (cleared) {
                "孤儿信任标记已删除（无存活代码包按运行期身份认领）"
            } else {
                "孤儿信任标记删除失败，原地保留"
            },
        )
    }
    return removed
}

/**
 * 盘面当前能认领信任标记的运行期身份集合；`null` = 判不了（有存活包侧车损坏 / `code/` 不可读）。
 *
 * 枚举口径与 [ModTrustStore.codePackages] 同源（只认 `code/` 下有合法产物的包目录），身份解析
 * 与装载闸同源（[ModDexIdentity.resolve]），本函数不另造映射、不猜坏档。
 */
private fun ownedRuntimeIds(rt2Dir: File, codeDir: File): Set<String>? {
    // code/ 不在场 = 没有代码包可认领（正常空态）；在场但列不出 = 读不动，判不了。
    if (codeDir.exists() && codeDir.listFiles() == null) return null
    val owned = mutableSetOf<String>()
    var corrupt = false
    for (pkg in ModTrustStore.codePackages(codeDir)) {
        when (val identity = ModDexIdentity.resolve(rt2Dir, pkg.packageId)) {
            is DexCodeIdentity.Resolved -> owned += identity.runtimePackageId
            is DexCodeIdentity.Corrupt -> corrupt = true
        }
    }
    return if (corrupt) null else owned
}

