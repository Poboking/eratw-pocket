package com.eratw.port.android.market

import java.security.MessageDigest

/**
 * [MarketVerifier] 的市场域实现（CONTRACT-MKM-W1 §2.2-§2.5 判据）。
 *
 * 纯 JVM、零 IO、零 Android API；校验判据全部来自 kojo-editor
 * `packages/plugin-api/src/market.ts` 与 `packages/plugins/kojo-market/src/manifest.ts`
 * （逐条 KDoc 带真源行号），字段/路径/限额常量取自 [MarketContract]（骨架单源）。
 * 全部闸口 **首个问题即拒**（契约 §2.2：返回 problem 人话，不做「收集全部」）。
 *
 * 闸序总览：
 * - [parsePackManifest]：JSON 语法 → 对象 → 契约外键 → schema → kind → id → version →
 *   必填三串 → redistribute → minAppVersion → files（先逐条提取形状，再逐条过闸，
 *   两段式照 manifest.ts:115-127）→ 可选字段 → overrideFunctions 形状闸（可选收窄，
 *   语义闸在导入期转码器）→ 装配。
 * - [parseIndex]：JSON 语法 → 对象 → schema/generatedAt/packs 整索引闸（manifest.ts:280-284）
 *   → 逐条目闸（不过 = 整条判弃计 dropped）→ zip.url 形态 → 封面闸（不过 = 丢封面保留
 *   条目计 coverIgnored）→ id-kind 冲突判弃（market.ts:831-842）。
 */
class MarketVerifierImpl : MarketVerifier {

    override fun parseIndex(json: String, fetchedAtMs: Long): MarketParseResult<MarketIndex> {
        val root = try {
            MarketJson.parse(json)
        } catch (e: MarketJsonException) {
            return badIndex("index.json 不是合法 JSON：${e.message}")
        }
        val obj = asJsonObjectOrNull(root) ?: return badIndex("索引必须是一个 JSON 对象")
        // 整索引闸（真源 manifest.ts:280-284）：schema / generatedAt / packs 任一不合格 = 整索引拒
        val schema = obj.opt("schema")
        if (!(schema is JsonNumber && schema.longValueOrNull() == MarketContract.SCHEMA_VERSION.toLong())) {
            return badIndex(
                "索引 schema 版本不受支持：本应用只认 ${MarketContract.SCHEMA_VERSION}，" +
                    "收到 ${renderNode(schema)}——整索引拒",
            )
        }
        val generatedAt = (obj.opt("generatedAt") as? JsonString)?.value
            ?: return badIndex("索引 generatedAt 必须是字符串")
        val packsNode = obj.opt("packs") as? JsonArray ?: return badIndex("索引 packs 必须是数组")
        val kept = mutableListOf<MarketIndexEntry>()
        var dropped = 0
        var coverIgnored = 0
        val coverIgnoredIds = mutableListOf<String>()
        for (raw in packsNode.items) {
            val o = raw as? JsonObject
            val zipObj = o?.opt("zip") as? JsonObject
            val zipUrl = (zipObj?.opt("url") as? JsonString)?.value ?: ""
            // 单条不合法 = 丢这一条并计数（manifest.ts:288-294，不让一个坏包打死整站）：
            // 条目闸（真源 manifest.ts:226-256）与 zip.url 形态闸（契约 §2.3；三条判据 =
            // market.ts:748-759 endpointProblem）任一不过都算这一条的
            if (o == null || indexEntryProblem(o) != null || urlShapeProblem(zipUrl, "zip.url") != null) {
                dropped++
                continue
            }
            // 封面闸（market.ts:296-305 消费方式 + marketCoverProblem :787-808）：
            // 缺席 = 合法；在册但不合格 = 丢字段保留条目，id 记入 coverIgnored 出声
            val coverNode = o.opt("cover")
            var coverUrl: String? = null
            if (coverNode != null) {
                val coverWhy = coverProblem(coverNode)
                if (coverWhy != null) {
                    coverIgnored++
                    coverIgnoredIds += (o.opt("id") as JsonString).value
                } else {
                    coverUrl = ((coverNode as? JsonObject)?.opt("url") as? JsonString)?.value
                }
            }
            kept += MarketIndexEntry(
                kind = (o.opt("kind") as JsonString).value,
                id = (o.opt("id") as JsonString).value,
                version = (o.opt("version") as JsonString).value,
                name = (o.opt("name") as JsonString).value,
                author = (o.opt("author") as JsonString).value,
                license = (o.opt("license") as JsonString).value,
                // redistribute：缺席 = true（线上索引无此字段）；false / 非布尔已在条目闸判弃
                redistribute = o.opt("redistribute") !is JsonBoolean ||
                    (o.opt("redistribute") as JsonBoolean).value,
                minAppVersion = (o.opt("minAppVersion") as JsonString).value,
                summary = (o.opt("summary") as? JsonString)?.value,
                sourceUrl = (o.opt("sourceUrl") as? JsonString)?.value,
                gameId = (o.opt("gameId") as? JsonString)?.value,
                zipUrl = zipUrl,
                zipSha256 = (zipObj?.opt("sha256") as? JsonString)?.value ?: "",
                zipBytes = (zipObj?.opt("bytes") as? JsonNumber)?.longValueOrNull() ?: 0L,
                fileCount = (o.opt("fileCount") as? JsonNumber)?.intValueOrNull() ?: 0,
                unpackedBytes = (o.opt("unpackedBytes") as? JsonNumber)?.longValueOrNull() ?: 0L,
                coverUrl = coverUrl,
            )
        }
        // id-kind 冲突（真源 market.ts:831-842 + service.ts:410-432）：冲突 id 的条目全部
        // 判弃计 dropped（不许按 kind 择一）；被拦条目的封面注记一并抹掉（service.ts:428-430）
        val conflictIds = idKindConflictIds(kept)
        val survivors = if (conflictIds.isEmpty()) {
            kept
        } else {
            dropped += kept.count { it.id in conflictIds }
            coverIgnored -= coverIgnoredIds.count { it in conflictIds }
            kept.filter { it.id !in conflictIds }
        }
        return MarketParseResult.Ok(
            MarketIndex(
                schema = MarketContract.SCHEMA_VERSION,
                generatedAt = generatedAt,
                packs = survivors,
                dropped = dropped,
                coverIgnored = coverIgnored,
                fetchedAtMs = fetchedAtMs,
            ),
        )
    }

    override fun parsePackManifest(json: String): MarketParseResult<PackManifest> {
        val root = try {
            MarketJson.parse(json)
        } catch (e: MarketJsonException) {
            return badPack("pack.json 不是合法 JSON：${e.message}")
        }
        val obj = asJsonObjectOrNull(root) ?: return badPack("pack.json 必须是一个 JSON 对象")
        // 契约外键拒（契约 §2.2「契约外键拒（pack.json 顶层）」；market.ts 无此闸，
        // 骨架 PackManifest KDoc「契约外键在解析期拒」为组长裁定）
        val unknownKeys = obj.keys().filter { it !in MANIFEST_KEYS }
        if (unknownKeys.isNotEmpty()) {
            return badPack("pack.json 含契约外字段：${unknownKeys.joinToString("、")}")
        }
        // schema（真源 manifest.ts:64-70）
        val schema = obj.opt("schema")
        if (!(schema is JsonNumber && schema.longValueOrNull() == MarketContract.SCHEMA_VERSION.toLong())) {
            return badPack(
                "清单 schema 版本不受支持：本应用只认 ${MarketContract.SCHEMA_VERSION}，" +
                    "包写的是 ${renderNode(schema)}",
            )
        }
        // kind ∈ 三值（真源 manifest.ts:71-74）
        val kindNode = obj.opt("kind") as? JsonString
        if (kindNode == null || kindNode.value !in MarketContract.KINDS) {
            return badPack(
                "包种类不认识：${renderNode(obj.opt("kind"))}" +
                    "（只认 ${MarketContract.KINDS.joinToString(" / ")}）",
            )
        }
        // id（真源 manifest.ts:75-78；正则 market.ts:161-162）
        requiredStringProblem(obj, "id", "包 id")?.let { return badPack(it) }
        val id = (obj.opt("id") as JsonString).value
        if (!PACK_ID_RE.matches(id)) {
            return badPack("包 id 不合规：$id（要匹配 ${PACK_ID_RE.pattern}）")
        }
        // version（真源 manifest.ts:79-82；段长闸契约 §2.2，见 isPackVersion KDoc）
        requiredStringProblem(obj, "version", "包版本")?.let { return badPack(it) }
        val version = (obj.opt("version") as JsonString).value
        if (!isPackVersion(version)) {
            return badPack("包版本不合规：$version（要三段数字 x.y.z，每段至多 9 位）")
        }
        // 必填三串（真源 manifest.ts:83-85）
        requiredStringProblem(obj, "name", "包名")?.let { return badPack(it) }
        requiredStringProblem(obj, "author", "作者")?.let { return badPack(it) }
        requiredStringProblem(obj, "license", "许可")?.let { return badPack(it) }
        val name = (obj.opt("name") as JsonString).value
        val author = (obj.opt("author") as JsonString).value
        val license = (obj.opt("license") as JsonString).value
        // redistribute（真源 manifest.ts:87-97）：判据单源 = [redistributeProblem]（GATE2D 洞①
        // 收口——原先这手写两条分支与索引闸各一份，文案已漂移）。容器侧 absentIsIllegal=true：
        // 缺省即不合法，不许猜作者的意思；false = 作者未许可二次分发，拒（市场收录的前提就是
        // 允许分发）。索引侧同函数取 absentIsIllegal=false——按层不对称是在册裁决（KDoc 在单源
        // 函数头上），不是两份判据。人话文案由单源给出，本层只补包上下文。
        val redistributeNode = obj.opt("redistribute")
        redistributeProblem(redistributeNode, absentIsIllegal = true)?.let { problem ->
            return badPack("$problem：$id v$version（许可 $license）")
        }
        val redistribute = (redistributeNode as JsonBoolean).value
        // minAppVersion（真源 manifest.ts:99-102）；appVersion 满足闸在安装期由管线层经
        // minAppVersionSatisfied 执行（骨架 parsePackManifest 无 appVersion 入参）
        requiredStringProblem(obj, "minAppVersion", "最低应用版本")?.let { return badPack(it) }
        val minAppVersion = (obj.opt("minAppVersion") as JsonString).value
        if (!isPackVersion(minAppVersion)) {
            return badPack("minAppVersion 不合规：$minAppVersion（要三段数字 x.y.z，每段至多 9 位）")
        }
        // files（真源 manifest.ts:111-124）：数组 + 逐条形状提取（两段式照 TS：先全部
        // 提取形状，再统一过 [packFilesGate] 值面闸）
        val filesNode = obj.opt("files")
        if (filesNode !is JsonArray) {
            return badPack("files 必须是数组（逐文件 sha256 是唯一的校验锚，不能没有）")
        }
        val rawFiles = mutableListOf<PackRawFile>()
        for ((i, e) in filesNode.items.withIndex()) {
            val entry = e as? JsonObject ?: return badPack("files[$i] 必须是一个 JSON 对象")
            requiredStringProblem(entry, "path", "files[$i] 的文件路径")?.let { return badPack(it) }
            val bytesNode = entry.opt("bytes")
            if (bytesNode !is JsonNumber) return badPack("files[$i].bytes 必须是数字")
            requiredStringProblem(entry, "sha256", "files[$i] 的逐文件校验锚")?.let { return badPack(it) }
            rawFiles += PackRawFile(
                (entry.opt("path") as JsonString).value,
                bytesNode,
                (entry.opt("sha256") as JsonString).value,
            )
        }
        // 逐文件值面闸（真源 market.ts:692-713 packFilesProblem）：路径、重复、bytes、
        // sha256 形态、条目数、累计字节；首个问题即拒
        val files: List<PackManifestFile> = when (val gate = packFilesGate(rawFiles)) {
            is FilesGateResult.Bad -> return badPack("清单里的文件条目不合法：${gate.problem}")
            is FilesGateResult.Ok -> gate.files
        }
        // 可选字段（真源 manifest.ts:35-42 + 130-132）：在册必须是字符串
        optionalStringProblem(obj, "summary")?.let { return badPack(it) }
        optionalStringProblem(obj, "sourceUrl")?.let { return badPack(it) }
        optionalStringProblem(obj, "gameId")?.let { return badPack(it) }
        // 可选收窄声明（本应用扩展，非市场契约字段）：形状闸在此，语义闸在导入期转码器
        // （声明必须与实际可覆盖集逐一相等，见 PatchImportTranscoder.transcode）。
        val (declaredOverrideFunctions, overrideFunctionsProblem) = parseDeclaredOverrides(obj)
        overrideFunctionsProblem?.let { return badPack(it) }
        return MarketParseResult.Ok(
            PackManifest(
                schema = MarketContract.SCHEMA_VERSION,
                kind = kindNode.value,
                id = id,
                version = version,
                name = name,
                author = author,
                license = license,
                redistribute = redistribute,
                minAppVersion = minAppVersion,
                summary = (obj.opt("summary") as? JsonString)?.value,
                sourceUrl = (obj.opt("sourceUrl") as? JsonString)?.value,
                gameId = (obj.opt("gameId") as? JsonString)?.value,
                files = files,
                overrideFunctions = declaredOverrideFunctions,
            ),
        )
    }

    /**
     * 可选 `overrideFunctions` 的形状闸（R7-① 显式收窄，次要能力）。
     *
     * 实现见 [parseDeclaredOverrides]（独立文件：与清单骨架无关的纯形状判据）。
     */

    override fun versionCompare(a: String, b: String): Int {
        // 入参前置条件 = 已过 parse 校验（骨架 KDoc）；违反前置条件按编程错误处理
        val cmp = compareVersionsOrNull(a, b)
        require(cmp != null) { "versionCompare 入参必须已过 parse 校验：a=[$a] b=[$b]" }
        return cmp
    }

    override fun minAppVersionSatisfied(minAppVersion: String, appVersion: String): Boolean =
        // 判据单源 = [minAppVersionSatisfiedCore]（GATE2D 洞③收口）：本实现原先自带一份等价
        // 判定，与 UI 壳 MarketVersion 那份参数序相反、可互接反；两侧现共用同一函数，判定逐字
        // 不变（真源 market.ts:853-859），参数序在本壳钉死为 (minAppVersion, appVersion) 具名接驳。
        minAppVersionSatisfiedCore(minAppVersion = minAppVersion, appVersion = appVersion)

    override fun contentDigest(entries: List<Pair<String, String>>): String {
        // 排序与连接口径钉死（真源 digest.ts:71-77 contentDigestOf）：每行 path 与 sha256
        // 以 NUL 分隔，行按字典序排，行间以单元分隔符连接，整体 UTF-8 编码后 SHA-256，
        // 输出 64 位小写 hex。同法在任何机器上都能从逐文件锚复算。
        val folded = entries
            .map { (path, sha) -> "$path\u0000$sha" }
            .sorted()
            .joinToString("\u0001")
        return sha256Hex(folded.toByteArray(Charsets.UTF_8))
    }

    override fun isSha256Hex(value: String): Boolean = SHA256_HEX_RE.matches(value)

    /** 64 位小写 hex 的 SHA-256（摘要真源 = digest.ts:36-38 sha256Hex，字节面口径一致）。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> "%02x".format(b.toInt() and BYTE_HEX_MASK) }

    private fun badPack(problem: String): MarketParseResult<PackManifest> = MarketParseResult.Invalid(problem)

    private fun badIndex(problem: String): MarketParseResult<MarketIndex> = MarketParseResult.Invalid(problem)

    companion object {
        /** 单字节十六进制化的掩码（Byte 转无符号两段 hex 用）。 */
        private const val BYTE_HEX_MASK = 0xFF

        /** pack.json 顶层允许的字段全集（契约 §2.2 契约外键拒；骨架 PackManifest 字段面）。 */
        private val MANIFEST_KEYS: Set<String> = setOf(
            "schema", "kind", "id", "version", "name", "author", "license",
            "redistribute", "minAppVersion", "summary", "sourceUrl", "gameId", "files",
            // 本应用扩展（R7-① 显式收窄，见 [parseOverrideFunctions]）；市场契约无此字段，
            // 但客户端必须认它，否则带声明的包会在契约外键闸被拒。
            "overrideFunctions",
        )
    }
}
