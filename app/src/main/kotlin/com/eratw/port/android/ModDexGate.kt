package com.eratw.port.android

import java.io.File

/**
 * 路 B（dex 包）的 ABI 双闸判定（ADR-013 D2 闸 2）+ 信任确认标记（D3）。
 *
 * 本对象**只做判定**：不扫 dex、不构 DexClassLoader、不碰磁盘上的 code/（那是 S4 装载片的接线面）。
 * 调用纪律（ADR-013 D2 硬约束）：[verdict] 必须在**任何 mod 代码执行之前**跑完——装载器拿到
 * `PackMeta` 后第一件事就是问它，拒绝即整包不装，绝不允许「先加载再拒」。
 *
 * 判据（surface 单调增，因此只有「太新」一种值得拒的情形）：
 * - `compiledSurface == null`（未声明，老包常态）⇒ [Verdict.AllowedUndeclared]；
 * - `compiledSurface <= engineApiVersion` ⇒ [Verdict.Allowed]（包编译期的 ABI 不比引擎新，引擎向后兼容）；
 * - `compiledSurface > engineApiVersion` ⇒ [Verdict.Rejected]（包需要更新的引擎）。
 *
 * 兼容表（[SurfaceCompat]）**只是加速/降级信息**：拒绝文案可带一句「哪个引擎版本起提供该
 * surface」，但绝不改变上面三条主判据——表为空、表里缺行、表里有坏行，判定结果都不变。
 * 别让表变成第二真源（ADR-013 D2）。
 */
object ModDexGate {

    /** 信任确认标记后缀：`<rt2>/<packageId>.trusted`（与 `.off` 禁用标记、`.media` 同域，ADR-013 D3）。 */
    const val TRUST_MARKER_SUFFIX: String = ".trusted"

    /** compat 表整行注释前缀。 */
    private const val COMPAT_COMMENT_PREFIX = "#"

    /** compat 表列分隔符（简单 TSV：`surface<TAB>minEngineApi`）。 */
    private const val COMPAT_COLUMN_SEPARATOR = '\t'

    /** compat 表每行必须恰好两列。 */
    private const val COMPAT_COLUMN_COUNT = 2

    /** surface 列下标。 */
    private const val SURFACE_COLUMN = 0

    /** minEngineApi 列下标。 */
    private const val MIN_ENGINE_API_COLUMN = 1

    private const val REJECT_REASON_FORMAT =
        "本包编译于 mod-api surface v%d，当前引擎 API v%d，需要更新引擎才能装载"

    private const val COMPAT_HINT_FORMAT = "（compat 表登记该 surface 自引擎 v%d 起提供）"

    /**
     * ABI 闸判定结论。
     *
     * 三态而不是 boolean：未声明（老包）与已声明且兼容在语义上不同——前者「没有证据」，
     * 后者「有证据且通过」；诊断/降级策略要能区分，别在调用点拿 `compiledSurface == null` 再猜一次。
     */
    sealed interface Verdict {
        /** 是否放行（装载器只、且必须只看这个）。 */
        val isAllowed: Boolean

        /** 未声明 `compiledSurface` 的老包：向后兼容放行。 */
        data object AllowedUndeclared : Verdict {
            override val isAllowed: Boolean get() = true
        }

        /** 已声明且不高于当前引擎 API：放行。 */
        data object Allowed : Verdict {
            override val isAllowed: Boolean get() = true
        }

        /** 拒绝装载；[reason] 是给日志/诊断的句子（两边版本号都在其中），不上玩家可见文案表。 */
        data class Rejected(val reason: String) : Verdict {
            override val isAllowed: Boolean get() = false
        }
    }

    /**
     * compat 表读数（surface → 该 surface 起可用的最低引擎 API）。
     *
     * **信息服务、不是判据**：只用于把拒绝文案补全；[verdict] 的主判定完全不依赖它。
     * 由 [loadCompatTable] 构造；坏行已跳过并计进 [malformedLineCount]（只诊断，不影响判定）。
     */
    class SurfaceCompat internal constructor(
        private val minEngineApiBySurface: Map<Int, Int>,
        /** 解析时跳过的坏行数（诊断用；> 0 不影响任何判定）。 */
        val malformedLineCount: Int,
    ) {
        /** 表是否为空（空表 = 无任何降级信息，判定照常）。 */
        val isEmpty: Boolean get() = minEngineApiBySurface.isEmpty()

        /** surface 在表里登记的最低引擎 API；未登记 ⇒ null（不猜）。 */
        fun minEngineApiFor(surface: Int): Int? = minEngineApiBySurface[surface]

        companion object {
            /** 空表（缺省 compat；与传入空表判定完全一致）。 */
            val EMPTY: SurfaceCompat = SurfaceCompat(emptyMap(), 0)
        }
    }

    /**
     * 判定一个包能否在 `engineApiVersion` 的引擎上装载 dex。
     *
     * @param meta 已解析的包元数据（未声明 `compiledSurface` ⇒ 放行，老包常态）
     * @param engineApiVersion 当前引擎的 mod-api surface 版本（非负）
     * @param compat 兼容表读数；**只影响拒绝文案**，传空表判定完全一致
     */
    internal fun verdict(
        meta: PackMeta,
        engineApiVersion: Int,
        compat: SurfaceCompat = SurfaceCompat.EMPTY,
    ): Verdict {
        require(engineApiVersion >= 0) {
            "引擎 API 版本必须非负：$engineApiVersion（surface 从 0 起）"
        }
        val compiled = meta.compiledSurface ?: return Verdict.AllowedUndeclared
        return if (compiled <= engineApiVersion) {
            Verdict.Allowed
        } else {
            Verdict.Rejected(rejectReason(compiled, engineApiVersion, compat))
        }
    }

    /**
     * 解析 compat 表文本（简单 TSV，列：`surface<TAB>minEngineApi`）。
     *
     * 空行与 `#` 注释行跳过；列数不对 / 数字解析失败 / 负值 / surface 超上限 ⇒ 跳行并累加
     * [SurfaceCompat.malformedLineCount]，**不抛异常、不影响判定**（表只是信息面）。
     * 同一 surface 重复出现时后值覆盖前值（表由本仓维护，重复是笔误不是语义）。
     */
    fun loadCompatTable(text: String): SurfaceCompat {
        val rows = mutableMapOf<Int, Int>()
        var malformed = 0
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith(COMPAT_COMMENT_PREFIX)) return@forEach
            val columns = line.split(COMPAT_COLUMN_SEPARATOR)
            val surface = columns.getOrNull(SURFACE_COLUMN)?.trim()?.toIntOrNull()
                ?.takeIf { it in 0..PACK_META_MAX_COMPILED_SURFACE }
            val minEngineApi = columns.getOrNull(MIN_ENGINE_API_COLUMN)?.trim()?.toIntOrNull()
                ?.takeIf { it >= 0 }
            if (columns.size != COMPAT_COLUMN_COUNT || surface == null || minEngineApi == null) {
                malformed++
                return@forEach
            }
            rows[surface] = minEngineApi
        }
        return SurfaceCompat(rows, malformed)
    }

    /**
     * 信任确认标记文件名：`<packageId>.trusted`（ADR-013 D3，落在 rt2 目录）。
     *
     * 包名形态复用 [ModEnablement.isValidPackageId]（单源，禁止另抄一套字符规则）：只允许字母数字
     * 与 `.` `_` `-`、不得以符号开头 ⇒ 结果名不含路径分隔符，拼进 rt2 目录不会越界。
     *
     * @throws IllegalArgumentException packageId 形态非法
     */
    fun trustMarkerName(packageId: String): String {
        require(ModEnablement.isValidPackageId(packageId)) {
            "非法 packageId（只允许字母数字与 . _ -，且不得以符号开头）：$packageId"
        }
        return packageId + TRUST_MARKER_SUFFIX
    }

    /**
     * 该包是否已被玩家显式信任：rt2 目录下存在 `<packageId>.trusted`。
     *
     * 只查存在性、不读内容（标记是开关不是数据）；缺失 / 是目录 ⇒ false。
     * 路径越界由 [trustMarkerName] 的包名形态闸兜住（名字里不可能有分隔符）。
     */
    fun isTrusted(rt2Dir: File, packageId: String): Boolean =
        File(rt2Dir, trustMarkerName(packageId)).isFile

    private fun rejectReason(compiledSurface: Int, engineApiVersion: Int, compat: SurfaceCompat): String {
        val base = REJECT_REASON_FORMAT.format(compiledSurface, engineApiVersion)
        val introduced = compat.minEngineApiFor(compiledSurface) ?: return base
        return base + COMPAT_HINT_FORMAT.format(introduced)
    }
}
