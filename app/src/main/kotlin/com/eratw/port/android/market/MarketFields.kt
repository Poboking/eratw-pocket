package com.eratw.port.android.market

/**
 * 清单与索引的字面约束、逐条闸与版本比较（纯函数、零 IO）。
 *
 * 语义真源 = kojo-editor `packages/plugin-api/src/market.ts` 与
 * `packages/plugins/kojo-market/src/manifest.ts`（逐条判据的 KDoc 带真源行号）；
 * 契约面 = CONTRACT-MKM-W1 §2.2-§2.4（组长裁定落笔即冻结，market.ts 没有对应判据的点
 * 在 KDoc 注明「契约口径」）。消息一律中文人话，供 [MarketParseResult.Invalid] 直接呈现。
 */

/** 包 id：小写、首字符字母数字、可含点下划线连字符，总长不超过 64（market.ts:161-162）。 */
internal val PACK_ID_RE: Regex = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

/** 包版本：严格三段数字（market.ts:164-165）。前导零合法（TS 同款；比较按数值）。 */
internal val PACK_VERSION_RE: Regex = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$")

/** 版本段数（严格三段，market.ts:164-165 与 :716-732 同源）。 */
internal const val PACK_VERSION_SEGMENTS = 3

/** 版本段：纯数字、1 到 9 位（market.ts:167-168，防数值溢出畸形段）。 */
internal const val VERSION_SEG_MAX_DIGITS = 9

/** sha256 摘要字面形：64 位小写十六进制（market.ts:170-171）。 */
internal val SHA256_HEX_RE: Regex = Regex("^[0-9a-f]{64}$")

/** 单内容路径的 UTF-8 字节上限 512（契约 §2.2 worker 口径；market.ts 无此闸）。 */
internal const val MAX_PATH_UTF8_BYTES = 512

/** 封面 url 的字符上限 2048（market.ts:762）。 */
internal const val MAX_COVER_URL_CHARS = 2048

/** 封面体积上限的 KiB 数（market.ts:772 的 256）。 */
internal const val MAX_COVER_KIB = 256

/** 一个 KiB 的字节数（体积上限以 KiB 计的换算基）。 */
internal const val BYTES_PER_KIB = 1024

/** 封面声明体积上限 256 KiB（market.ts:772，量注册表声明不是真字节）。 */
internal const val MAX_COVER_BYTES = MAX_COVER_KIB * BYTES_PER_KIB

/** 版本段合法：1 到 9 位纯 ASCII 数字（isDigit 会放进全角数字，这里显式限 ASCII）。 */
internal fun isVersionSegment(seg: String): Boolean =
    seg.length in 1..VERSION_SEG_MAX_DIGITS && seg.all { it in '0'..'9' }

/**
 * 包版本合法 = 严格三段数字且每段不超过 9 位（market.ts:164-165 的 PACK_VERSION_RE 加
 * VERSION_SEG_RE 段长）。契约 §2.2 把段长闸冻结在解析层（TS 在 compareVersions 里才核
 * 段长，market.ts:723——口径等价：10 位段在 TS 侧也是比较失败，只是报得晚）。
 */
internal fun isPackVersion(v: String): Boolean =
    PACK_VERSION_RE.matches(v) && v.split('.').all { isVersionSegment(it) }

/**
 * 单个清单条目路径的非法形态；合法返回 null，非法返回人话。
 * 规则集与**判定顺序**照真源 market.ts:672-689（盘符之外的冒号归盘符面：NTFS 备用数据流
 * ADS 注入；段尾点与空格会被 Windows 落盘静默剥掉）；末尾追加契约 §2.2 的 512 字节闸。
 */
internal fun packEntryPathProblem(path: String): String? {
    if (path.isEmpty()) return "文件路径不能为空"
    if (path.contains(':')) return "路径含盘符或冒号（NTFS 备用数据流注入面）：$path"
    if (path.contains('\\')) return "路径不许用反斜杠（只认 posix 相对路径）：$path"
    if (path.startsWith('/')) return "路径不许是绝对路径：$path"
    val segments = path.split('/')
    if (segments.any { it.isEmpty() || it == "." }) return "路径有空段或点段：$path"
    if (segments.any { it == ".." }) return "路径不许上跳（两点段）：$path"
    if (segments.any { it.endsWith('.') || it.endsWith(' ') }) {
        return "段尾点或空格会被 Windows 落盘静默剥掉：$path"
    }
    if (segments[0] != MarketContract.CONTENT_DIR) return "内容路径必须以 files/ 开头：$path"
    if (segments.size < 2) return "裸内容目录不是文件条目：$path"
    if (segments.size > MarketContract.MAX_PATH_DEPTH) return "路径深度超限（${segments.size} 段）：$path"
    if (path.toByteArray(Charsets.UTF_8).size > MAX_PATH_UTF8_BYTES) {
        return "单路径超长（UTF-8 超过 $MAX_PATH_UTF8_BYTES 字节）：$path"
    }
    return null
}

/** 提取期（shape 面）的清单文件条目：bytes 仍是节点，值面闸在 [packFilesGate] 里判。 */
internal class PackRawFile(val path: String, val bytesNode: JsonNode, val sha256: String) {

    /** bytes 节点折成 Long：非整数数学值或超 Long 范围为 null（TS Number.isInteger 口径）。 */
    val bytesLong: Long? by lazy {
        (bytesNode as? JsonNumber)?.longValueOrNull()
    }
}

/** [packFilesGate] 的结果：全部条目合法给出成品，否则给首个问题（首个问题即拒）。 */
internal sealed interface FilesGateResult {
    data class Ok(val files: List<PackManifestFile>) : FilesGateResult
    data class Bad(val problem: String) : FilesGateResult
}

/**
 * 清单逐文件闸（真源 market.ts:692-713 packFilesProblem，逐条顺序一致）：条目数、路径、
 * 重复、bytes 非负整数、sha256 字面形、累计字节。**首个问题即拒**，不做收集全部。
 * 附加：sha256 必须已是小写（契约 §2.2「64 位小写 hex」；TS 在 manifest.ts:123 把大写
 * 归一成小写再过闸——契约冻结为直接拒，见交付「与基准差异」）。
 */
internal fun packFilesGate(files: List<PackRawFile>): FilesGateResult {
    if (files.size > MarketContract.MAX_FILE_ENTRIES) {
        return FilesGateResult.Bad("清单条目数超限：${files.size} > ${MarketContract.MAX_FILE_ENTRIES}")
    }
    var total = 0L
    val seen = HashSet<String>()
    for (f in files) {
        val pathProblem = packEntryPathProblem(f.path)
        if (pathProblem != null) return FilesGateResult.Bad(pathProblem)
        if (!seen.add(f.path)) return FilesGateResult.Bad("清单里同一个路径出现两次：${f.path}")
        val bytes = f.bytesLong
        if (bytes == null || bytes < 0) {
            return FilesGateResult.Bad("文件 ${f.path} 的 bytes 必须是非负整数")
        }
        if (!SHA256_HEX_RE.matches(f.sha256)) {
            return FilesGateResult.Bad("文件 ${f.path} 的 sha256 不合规（要 64 位小写十六进制）")
        }
        total += bytes
        if (total > MarketContract.MAX_UNPACKED_BYTES) {
            return FilesGateResult.Bad("解压后总量超限：$total > ${MarketContract.MAX_UNPACKED_BYTES}")
        }
    }
    val built = files.map { PackManifestFile(it.path, requireNotNull(it.bytesLong), it.sha256) }
    return FilesGateResult.Ok(built)
}

/**
 * 三段数字版本逐段比较（真源 market.ts:716-732 compareVersions）：a 小于 b 给负数，
 * 相等给 0，大于给正数；任一边不是三段或某段不是 1 到 9 位数字返回 null。
 * 段按数值比较（`1.2.3` 小于 `1.10.0`），不按字典序。
 */
internal fun compareVersionsOrNull(a: String, b: String): Int? {
    val parts = listOf(a, b).map { it.split('.') }
    val wellFormed = parts.all { p -> p.size == PACK_VERSION_SEGMENTS && p.all { isVersionSegment(it) } }
    if (!wellFormed) return null
    val indexes = 0 until PACK_VERSION_SEGMENTS
    val cmp = indexes.map { parts[0][it].toLong().compareTo(parts[1][it].toLong()) }
    return cmp.firstOrNull { it != 0 } ?: 0
}

/** 剥预发布段：`0.4.0-beta.1` 按 `0.4.0` 算（真源 market.ts:734-738 coreVersion）。 */
internal fun coreVersion(v: String): String {
    val dash = v.indexOf('-')
    return if (dash == -1) v else v.substring(0, dash)
}

/**
 * minAppVersion 满足闸的**判据单源**（GATE2D 洞②③收口，组长批准）：真源 market.ts:847-859
 * `minAppVersionSatisfied`——minAppVersion 必须是严格三段数字（[isPackVersion] 全口径，含
 * 每段 1-9 位 ASCII 闸）；当前应用版本剥预发布段（[coreVersion]）后逐段数值比较，不小于即
 * 满足。严格 semver 的 prerelease < release 会让 beta 站装不进任何标 `>=` 的包，所以预发布
 * 不算（与 [MarketVerifierImpl.minAppVersionSatisfied] 既有语义逐字一致，零行为改动）。
 *
 * 参数序钉死为 **(minAppVersion, appVersion)**——[MarketVerifierImpl] 接口版与此同序；
 * UI 壳版 MarketVersion.minAppVersionSatisfied 的对外参数序是 (currentAppVersion, minAppVersion)
 * 相反，其委托处必须用具名实参接驳，位置实参在两侧壳互接反是本闸要堵的死口（判词 20 洞③）。
 * 任一边走形（段长越界、非 ASCII、非三段、缺席）一律判不满足——不许猜。
 */
internal fun minAppVersionSatisfiedCore(minAppVersion: String, appVersion: String): Boolean {
    val core = coreVersion(appVersion)
    if (!isPackVersion(minAppVersion) || !isPackVersion(core)) return false
    return compareVersionsOrNull(core, minAppVersion)?.let { it >= 0 } ?: false
}

/**
 * 同 id 挂多个 kind 的 id 集（真源 market.ts:831-842 packIdKindConflicts，注册表不变量
 * A4：一个 id 只能属于一个 kind；冲突条目全部不呈现，不许按 kind 择一）。
 * 本函数只查索引内部冲突；与已装集的冲突由管线层把已装条目并进查重面。
 */
internal fun idKindConflictIds(entries: List<MarketIndexEntry>): Set<String> {
    val kindsById = LinkedHashMap<String, MutableSet<String>>()
    for (e in entries) kindsById.getOrPut(e.id) { LinkedHashSet() }.add(e.kind)
    return kindsById.filterValues { it.size > 1 }.keys
}

/**
 * redistribute 判据**单源**（CONTRACT-MKM-GATE2 洞①，组长改判）：索引条目闸与容器清单闸
 * 共用本函数——「在册但坏了」（非布尔、字面 null）与「在册 false」两层同判同文案，
 * 判据不再各写一份。**缺席分支按层裁定**（这是两层唯一合法的不对称）：
 * - 索引侧（absentIsIllegal=false）：缺席 = 收——线上索引没有这个字段（kojo-market 上游
 *   声明缺口，已登记需组长接线推上游补字段），放行浏览，许可权威由容器侧硬闸兜底；
 * - 容器侧（absentIsIllegal=true）：缺席 = 判死——作者必须主动声明才算许可二次分发，
 *   缺省不许猜意思。装配进 [MarketIndexEntry.redistribute] 的展示默认值与本判据无关。
 * 返回 null = 收下；否则返回玩家日志/取证直接可用人话。
 */
internal fun redistributeProblem(node: JsonNode?, absentIsIllegal: Boolean): String? = when {
    node == null ->
        if (absentIsIllegal) "redistribute 字段必须是布尔（缺省即不合法——不许猜作者的意思）" else null
    node !is JsonBoolean -> "redistribute 必须是布尔"
    !node.value -> "redistribute=false 不许分发（作者未许可二次分发）"
    else -> null
}

/**
 * 索引条目闸（真源 manifest.ts:226-256 indexEntryProblem 判据移植）：返回人话问题，
 * null = 合法。与真源的口径差异逐条注明：
 * - redistribute：判据单源 = [redistributeProblem]（GATE2 洞① 组长改判），本层缺席 = 收
 *   （线上索引没有这个字段：上游声明缺口，权威闸在容器侧），在册 false 判弃、
 *   在册非布尔判弃（骨架 MarketTypes.kt:25「出现即进 dropped」）。
 * - zip.bytes 必须是正整数（契约 §2.3「bytes 正数」；TS 只判非负，manifest.ts:247-248）。
 * - fileCount / unpackedBytes 必须是能装进 Int/Long 的非负整数（Kotlin 字段类型强制）。
 * - summary / sourceUrl / gameId 在册时必须是字符串或 null（Kotlin 字段为 String?，
 *   其他类型无法表达；JsonNull 与缺席等价）。
 */
internal fun indexEntryProblem(o: JsonObject): String? {
    val kindNode = o.opt("kind") as? JsonString
    if (kindNode == null || kindNode.value !in MarketContract.KINDS) return "kind 不认识"
    val idNode = o.opt("id") as? JsonString
    if (idNode == null || !PACK_ID_RE.matches(idNode.value)) return "id 不合规"
    val versionNode = o.opt("version") as? JsonString
    if (versionNode == null || !isPackVersion(versionNode.value)) return "version 不合规"
    val minNode = o.opt("minAppVersion") as? JsonString
    if (minNode == null || !isPackVersion(minNode.value)) return "minAppVersion 不合规"
    for (f in listOf("name", "author", "license")) {
        val v = o.opt(f) as? JsonString
        if (v == null || v.value.isEmpty()) return "$f 缺失"
    }
    redistributeProblem(o.opt("redistribute"), absentIsIllegal = false)?.let { return it }
    val zip = o.opt("zip") as? JsonObject ?: return "zip 缺失"
    val urlNode = zip.opt("url") as? JsonString
    if (urlNode == null || urlNode.value.isEmpty()) return "zip.url 缺失"
    val shaNode = zip.opt("sha256") as? JsonString
    if (shaNode == null || !SHA256_HEX_RE.matches(shaNode.value)) return "zip.sha256 不合规"
    val zipBytes = (zip.opt("bytes") as? JsonNumber)?.longValueOrNull()
    if (zipBytes == null || zipBytes <= 0) return "zip.bytes 不合规"
    val fileCount = (o.opt("fileCount") as? JsonNumber)?.longValueOrNull()
    if (fileCount == null || fileCount < 0 || fileCount > Int.MAX_VALUE) return "fileCount 不合规"
    val unpacked = (o.opt("unpackedBytes") as? JsonNumber)?.longValueOrNull()
    if (unpacked == null || unpacked < 0) return "unpackedBytes 不合规"
    for (f in listOf("summary", "sourceUrl", "gameId")) {
        val v = o.opt(f)
        if (v != null && v !is JsonString && v !is JsonNull) return "$f 在册时必须是字符串"
    }
    return null
}
