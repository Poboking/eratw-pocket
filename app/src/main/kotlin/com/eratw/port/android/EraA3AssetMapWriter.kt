package com.eratw.port.android

import com.eratw.port.android.market.JsonArray
import com.eratw.port.android.market.JsonNull
import com.eratw.port.android.market.JsonObject
import com.eratw.port.android.market.JsonString
import com.eratw.port.android.market.MarketJson
import com.eratw.port.core.mod.ModDx3AssetMap

/**
 * 作者包 `eratw-pack.json` 里的资源覆盖声明 → [ModDx3AssetMap] 侧车字节（ERA-A3 方案 B / 二期 A）。
 *
 * ## 选位理由
 *
 * 解析依赖 app 域的 [MarketJson]（core 层无 JSON 库），故落 app 包；但**不碰** `ModImportVersion.kt`
 * 的 `PackMeta`（对面契约未列它为独占，且不改既有 pack.json 字段语义）——overrides 是独立段，
 * 坏档只影响覆盖本身，不回退版本 / min-api 既有口径。落盘段归 `ModImportController` 独占的
 * pack.json 派生区，本件只提供纯函数（解析 + 编码），无 IO、无 Android API。
 *
 * ## 声明形状（pack.json 根对象下的可选 `overrides` 数组）
 *
 * ```
 * "overrides": [
 *   { "kind": "sprite", "official": "通用浴室_0_0", "mod": "resources/背景图/custom.png" },
 *   { "kind": "bgm",    "official": "gamebgm/x.mp3", "mod": "sound/游戏bgm/custom.mp3" }
 * ]
 * ```
 *
 * `kind` 大小写不敏感；`official` = 官方资源名（sprite 名字 / sound 相对路径名），
 * `mod` = 本包 `<pkg>.assets/` 下相对路径。两者都过 [ModDx3AssetMap.isValidName]（严格拒绝
 * 路径穿越 / 反斜杠 / 盘符 / 点段 / 控制字符），sprite 的 `official` 统一大写（与消费面
 * `ResourceContents.parseRow` 的 `uppercase()` 同口径，`ResourceContents.kt:215`）。
 *
 * ## fail-closed
 *
 * JSON 非法 / `overrides` 非数组 / 单项字段缺失 / 名字非法 / 重复声明 ⇒ 该项进 [ParseResult.rejected]，
 * 不进 [ParseResult.entries]；有效项与坏项在同一批里互不污染。整个数组坏掉时 entries 为空 ⇒
 * 导入期**不落侧车**，消费面维持官方行为；rejected 走日志取证面（不静默吞错），
 * 不新增玩家可见文案（AGENTS §6：日志与取证面不进文案表）。
 */
internal object EraA3AssetMapWriter {

    /** 解析结果：[entries] 可安全写进侧车；[rejected] 是未采信项的点名诊断（取证面）。 */
    class ParseResult(
        val entries: List<ModDx3AssetMap.Entry>,
        val rejected: List<String>,
    )

    /** 从 `eratw-pack.json` 条目字节解析 `overrides`；[bytes] 为 null = 没有元数据条目。 */
    fun parse(bytes: ByteArray?): ParseResult {
        if (bytes == null) return ParseResult(emptyList(), emptyList())
        val obj = runCatching { bytes.toString(Charsets.UTF_8) }
            .getOrNull()
            ?.let { MarketJson.parseOrNull(it) as? JsonObject }
            ?: return ParseResult(emptyList(), listOf("pack.json 不是合法 JSON 对象"))
        val node = obj.opt(OVERRIDES_KEY) ?: return ParseResult(emptyList(), emptyList())
        if (node is JsonNull) return ParseResult(emptyList(), emptyList())
        val array = node as? JsonArray
            ?: return ParseResult(emptyList(), listOf("overrides 不是数组"))

        val entries = ArrayList<ModDx3AssetMap.Entry>()
        val rejected = ArrayList<String>()
        // 用索引循环而非 withIndex()：后者是 inline lambda，其中的 continue 属实验特性、默认不开。
        for (index in array.items.indices) {
            val item = array.items[index]
            val entryObj = item as? JsonObject
            if (entryObj == null) {
                rejected += "overrides[$index] 不是对象"
                continue
            }
            val kind = when ((entryObj.opt(KIND_KEY) as? JsonString)?.value?.lowercase()) {
                KIND_SPRITE -> ModDx3AssetMap.Kind.SPRITE
                KIND_BGM -> ModDx3AssetMap.Kind.BGM
                else -> null
            }
            val official = (entryObj.opt(OFFICIAL_KEY) as? JsonString)?.value
            val mod = (entryObj.opt(MOD_KEY) as? JsonString)?.value
            if (kind == null || official == null || mod == null) {
                rejected += "overrides[$index] 字段缺失或 kind 不认识"
                continue
            }
            val normalized = if (kind == ModDx3AssetMap.Kind.SPRITE) official.uppercase() else official
            if (!ModDx3AssetMap.isValidName(normalized) || !ModDx3AssetMap.isValidName(mod)) {
                rejected += "overrides[$index] 资源名非法：$official -> $mod"
                continue
            }
            if (entries.any { it.kind == kind && it.officialName == normalized }) {
                rejected += "overrides[$index] 重复声明：$official"
                continue
            }
            entries += ModDx3AssetMap.Entry(kind, normalized, mod)
        }
        return ParseResult(entries, rejected)
    }

    /** 编码成侧车文本（稳定序，委托 [ModDx3AssetMap.encode] 单一来源）。 */
    fun encode(packageId: String, entries: List<ModDx3AssetMap.Entry>): String =
        ModDx3AssetMap.encode(packageId, entries)

    private const val OVERRIDES_KEY = "overrides"
    private const val KIND_KEY = "kind"
    private const val OFFICIAL_KEY = "official"
    private const val MOD_KEY = "mod"
    private const val KIND_SPRITE = "sprite"
    private const val KIND_BGM = "bgm"
}
