package com.eratw.port.android.market

import java.net.URI
import java.net.URISyntaxException

/**
 * JSON 节点取值助手与 URL/封面形态闸（市场校验层的私有件，零 IO）。
 * KDoc 带真源行号：值助手照 manifest.ts:27-49 的形状面；URL 三条与封面闸照
 * market.ts:748-759（endpointProblem）与 market.ts:787-808（marketCoverProblem）。
 */

/** 顶层值必须是 JSON 对象（真源 manifest.ts:44-49 asRecord：数组与 null 都不算对象）。 */
internal fun asJsonObjectOrNull(node: JsonNode?): JsonObject? = node as? JsonObject

/**
 * 必填非空字符串字段闸（真源 manifest.ts:27-33 reqString）：合法返回 null，否则给
 * 「标签（字段 键）必须是非空字符串」的问题。取值在读到 null 的调用点自行转出。
 */
internal fun requiredStringProblem(obj: JsonObject, key: String, label: String): String? {
    val v = obj.opt(key)
    return if (v is JsonString && v.value.isNotEmpty()) null else "$label（字段 $key）必须是非空字符串"
}

/**
 * 可选字符串字段闸（真源 manifest.ts:35-42 optString）：缺席 = 合法；在册但不是字符串
 * = 问题（注意 JSON null 不算缺席，TS 侧 typeof null 非字符串同样拒）。
 */
internal fun optionalStringProblem(obj: JsonObject, key: String): String? {
    val v = obj.opt(key) ?: return null
    return if (v is JsonString) null else "字段 $key 存在时必须是字符串"
}

/**
 * 非负整数（Long）数字节点：合法返回值，非法返回 null（真源 manifest.ts:118-121 的
 * 类型面 + market.ts:705 packFilesProblem 的 Number.isInteger 与非负判断）。
 */
internal fun nonNegativeLongOrNull(node: JsonNode?): Long? {
    val v = (node as? JsonNumber)?.longValueOrNull() ?: return null
    return if (v >= 0) v else null
}

/**
 * 把一个 JSON 节点渲染成短诊断串（只进人话消息，照 TS String(value) 的口径：字符串
 * 不加引号）。对象与数组只给形态摘要，不整段回显。
 */
internal fun renderNode(node: JsonNode?): String = when (node) {
    null -> "(缺席)"
    is JsonNull -> "null"
    is JsonBoolean -> node.value.toString()
    is JsonNumber -> node.raw
    is JsonString -> node.value
    is JsonArray -> "数组(${node.items.size} 项)"
    is JsonObject -> "对象"
}

/**
 * URL 形态闸：必须是 https、必须有主机名、不许带 userinfo（三条判据 = 真源
 * market.ts:748-759 endpointProblem）。这不是白名单——可信主机在平台侧逐跳核
 * （mkm-pipe 路的取数器）；本闸只拦「一看就打不开」的输入。
 * [what] 是这条 url 在人话里的名字（「zip.url」/「封面 url」）。
 */
internal fun urlShapeProblem(url: String, what: String): String? {
    val uri = try {
        URI(url)
    } catch (e: URISyntaxException) {
        return "$what 不是一个合法 URL：${e.message}"
    }
    val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
    if (scheme != "https") return "$what 协议必须是 https"
    if (uri.host.isNullOrEmpty()) return "$what 没有主机名"
    if (!uri.rawUserInfo.isNullOrEmpty()) return "$what 不许带账号信息"
    return null
}

/**
 * 封面声明闸（真源 market.ts:787-808 marketCoverProblem，逐条顺序一致）：对象、url
 * 非空且不超过 2048 字符、https 直链带主机无账号、bytes 为 1..256KiB 的整数。
 * 不合格 = 条目保留但丢封面（coverIgnored），装饰字段没有让包蒸发的权力。
 */
internal fun coverProblem(cover: JsonNode?): String? {
    val o = cover as? JsonObject ?: return "封面必须是一个对象"
    val url = (o.opt("url") as? JsonString)?.value
    if (url.isNullOrEmpty()) return "封面 url 必须是非空字符串"
    if (url.length > MAX_COVER_URL_CHARS) return "封面 url 超长（${url.length} > $MAX_COVER_URL_CHARS 字符）"
    urlShapeProblem(url, "封面 url")?.let { return it }
    val bytes = (o.opt("bytes") as? JsonNumber)?.longValueOrNull()
    if (bytes == null || bytes <= 0) return "封面 bytes 必须是正整数（注册表声明的图像字节数）"
    if (bytes > MAX_COVER_BYTES) return "封面 bytes 超过封面体积上限（$bytes > $MAX_COVER_BYTES）"
    return null
}
