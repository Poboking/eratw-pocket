package com.eratw.port.android.market

import java.math.BigDecimal

/** Unicode 转义的十六进制位数（RFC 8259：4 位一组）。 */
private const val UNICODE_ESCAPE_DIGITS = 4

/** 十六进制按位左移拼装的位宽（与上面 4 位一组同源）。 */
private const val HEX_SHIFT_BITS = 4

/** 十六进制字母位与数值的偏移（c - 'a' + 10 的 10）。 */
private const val HEX_ALPHA_OFFSET = 10

/**
 * 市场域的最小 JSON 子集解析器（CONTRACT-MKM-W1 §0.2：全仓零 JSON 库，market 域手写）。
 *
 * 纯语法、零 schema：只按 RFC 8259 语法判合法（对象、数组、字符串含全部短转义与 4 位
 * 十六进制 Unicode 转义、数字含小数与指数、布尔、null、四种空白），不做任何字段校验
 * ——schema 与字段闸全在 [MarketVerifierImpl]。解析失败抛 [MarketJsonException]，消息带
 * 行列定位。判据 = 契约 §2.1：合法样本（嵌套、全部转义、指数数字、空白）与负例（截断、
 * 尾逗号、裸控制字符、非法转义）逐例单测；重复键 = 解析层保留后值（JSON 标准行为，
 * 校验层不管重复）。
 *
 * 自护闸（契约无此判据，防解析栈溢出崩溃）：嵌套深度上限 [MarketJson.MAX_DEPTH]——真实
 * 清单与索引的嵌套深度不超过 5 层，远达不到；等价于 V8 JSON.parse 对深嵌套抛 RangeError
 * 的行为，只是换成一个可捕获的语法错误。
 */
sealed interface JsonNode

/** JSON null 节点。注意它与「键缺席」不同：`{"cover":null}` 里 cover 在册但取值是它。 */
data object JsonNull : JsonNode

/** JSON true / false 节点。 */
data class JsonBoolean(val value: Boolean) : JsonNode

/**
 * JSON 数字节点，保留原字面量（不丢失精度信息）。数值能力按需取：
 * [isWholeNumber] 照 TS `Number.isInteger` 的值口径（`1e2` 与 `1.0` 都是整数，看数学值
 * 不看字面）；[longValueOrNull] 只在整数值能装进 Long 时给出值，其余（分数、溢出）一律
 * null，由字段闸给「不合规」结论。
 */
class JsonNumber(val raw: String) : JsonNode {

    private val parsed: BigDecimal? by lazy { runCatching { BigDecimal(raw) }.getOrNull() }

    /** 数学值是否为整数（照 TS Number.isInteger 的值口径；字面含指数或小数不影响判定）。 */
    val isWholeNumber: Boolean by lazy {
        val v = parsed ?: return@lazy false
        v.signum() == 0 || v.stripTrailingZeros().scale() <= 0
    }

    /** 整数值且能装进 Long 时给出值，否则 null（分数值与超 Long 范围都算装不下）。 */
    fun longValueOrNull(): Long? {
        if (!isWholeNumber) return null
        return runCatching { parsed?.longValueExact() }.getOrNull()
    }

    /** [longValueOrNull] 的 Int 版（fileCount 等计数面用）。 */
    fun intValueOrNull(): Int? {
        val l = longValueOrNull() ?: return null
        return runCatching { Math.toIntExact(l) }.getOrNull()
    }
}

/** JSON 字符串节点（转义已解码完毕；重复的代理对形式与原样字符等价）。 */
data class JsonString(val value: String) : JsonNode

/** JSON 数组节点。 */
data class JsonArray(val items: List<JsonNode>) : JsonNode

/**
 * JSON 对象节点。**重复键后值胜**（契约 §2.1）：构造时同名键后写者覆盖先写者，
 * [opt] 拿到的恒是最后那一份。
 */
data class JsonObject(val members: Map<String, JsonNode>) : JsonNode {

    /** 取键对应的节点；键不在册返回 null（注意与取到 [JsonNull] 区分）。 */
    fun opt(key: String): JsonNode? = members[key]

    /** 键是否在册（取值为 null 也算在册）。 */
    fun has(key: String): Boolean = members.containsKey(key)

    /** 全部键（按出现顺序；重复键只留一次）。 */
    fun keys(): Set<String> = members.keys
}

/** 解析失败：消息带行列定位与失败原因，[position] 是失败点在原文里的字符偏移。 */
class MarketJsonException(message: String, val position: Int) : RuntimeException(message)

object MarketJson {

    /** 嵌套深度上限（自护闸，见 [JsonNode] KDoc；真实清单与索引深度不超过 5 层）。 */
    const val MAX_DEPTH = 512

    /** 解析全文；语法不合法抛 [MarketJsonException]。 */
    fun parse(text: String): JsonNode = Parser(text).parseDocument()

    /** 解析全文；语法不合法返回 null（调用方自己拼「不是合法 JSON」的人话）。 */
    fun parseOrNull(text: String): JsonNode? = runCatching { parse(text) }.getOrNull()
}

private class Parser(private val src: String) {

    var pos = 0

    fun parseDocument(): JsonNode {
        val node = parseValue(0)
        if (peekAfterWhitespace() != null) fail("JSON 文档在值结束后还有多余内容")
        return node
    }

    private fun parseValue(depth: Int): JsonNode {
        if (depth > MarketJson.MAX_DEPTH) fail("JSON 嵌套过深（上限 ${MarketJson.MAX_DEPTH} 层）")
        val c = peekAfterWhitespace() ?: fail("JSON 意外结束（截断）")
        return when (c) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonString(parseStringBody())
            't' -> parseLiteral("true", JsonBoolean(true))
            'f' -> parseLiteral("false", JsonBoolean(false))
            'n' -> parseLiteral("null", JsonNull)
            '-' -> parseNumber()
            in '0'..'9' -> parseNumber()
            else -> fail("非法的值起始字符 '$c'")
        }
    }

    private fun parseObject(depth: Int): JsonNode {
        pos++ // 吃掉 '{'
        val members = LinkedHashMap<String, JsonNode>()
        if (peekAfterWhitespace() == '}') {
            pos++
            return JsonObject(members)
        }
        while (true) {
            val keyStart = peekAfterWhitespace() ?: fail("对象在键位置意外结束（截断）")
            if (keyStart != '"') fail("对象的键必须是字符串（收到 '$keyStart'）")
            val key = parseStringBody()
            if (peekAfterWhitespace() != ':') fail("对象键后缺少冒号")
            pos++
            members[key] = parseValue(depth + 1)
            when (peekAfterWhitespace() ?: fail("对象在值后意外结束（截断）")) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return JsonObject(members)
                }
                else -> fail("对象在值后应有 ',' 或 '}'")
            }
        }
    }

    private fun parseArray(depth: Int): JsonNode {
        pos++ // 吃掉 '['
        val items = mutableListOf<JsonNode>()
        if (peekAfterWhitespace() == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            items += parseValue(depth + 1)
            when (peekAfterWhitespace() ?: fail("数组在值后意外结束（截断）")) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return JsonArray(items)
                }
                else -> fail("数组在值后应有 ',' 或 ']'")
            }
        }
    }

    /** 读一个字符串字面量（pos 必须停在开引号上）；返回已解码的值并把 pos 推过闭引号。 */
    private fun parseStringBody(): String {
        pos++ // 吃掉 '"'
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail("字符串未闭合（截断）")
            val c = src[pos]
            when {
                c == '"' -> {
                    pos++
                    return sb.toString()
                }
                c == '\\' -> {
                    pos++
                    parseEscape(sb)
                }
                c < ' ' -> fail("字符串里有裸控制字符（码位 ${c.code}）——必须转义")
                else -> {
                    sb.append(c)
                    pos++
                }
            }
        }
    }

    /** pos 停在转义字符上（反斜杠已被吃掉）；解码一个转义并推进 pos。 */
    private fun parseEscape(sb: StringBuilder) {
        if (pos >= src.length) fail("转义序列意外结束（截断）")
        when (val e = src[pos]) {
            '"' -> sb.append('"')
            '\\' -> sb.append('\\')
            '/' -> sb.append('/')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000C')
            'n' -> sb.append('\n')
            'r' -> sb.append('\r')
            't' -> sb.append('\t')
            'u' -> {
                pos++
                parseUnicodeEscape(sb)
                return
            }
            else -> fail("非法转义字符 '\\$e'")
        }
        pos++
    }

    /** pos 停在 u 之后；读 4 位十六进制并把它表示的字符追加进缓冲。 */
    private fun parseUnicodeEscape(sb: StringBuilder) {
        if (pos + UNICODE_ESCAPE_DIGITS > src.length) fail("Unicode 转义需要 4 位十六进制（截断）")
        var code = 0
        repeat(UNICODE_ESCAPE_DIGITS) {
            val c = src[pos]
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + HEX_ALPHA_OFFSET
                in 'A'..'F' -> c - 'A' + HEX_ALPHA_OFFSET
                else -> fail("非法 Unicode 转义位 '$c'")
            }
            code = (code shl HEX_SHIFT_BITS) or digit
            pos++
        }
        sb.append(code.toChar())
    }

    /** 读一个数字字面量（RFC 8259 语法：无前导零、小数点与指数后必须有数字）；pos 停在起始字符上。 */
    private fun parseNumber(): JsonNumber {
        val start = pos
        if (peek() == '-') pos++
        when {
            peek() == '0' -> pos++
            peekIsAsciiDigit() -> while (peekIsAsciiDigit()) pos++
            else -> fail("非法数字（缺整数段）")
        }
        if (peek() == '.') {
            pos++
            if (!peekIsAsciiDigit()) fail("小数点后必须有数字")
            while (peekIsAsciiDigit()) pos++
        }
        if (peek() == 'e' || peek() == 'E') {
            pos++
            if (peek() == '+' || peek() == '-') pos++
            if (!peekIsAsciiDigit()) fail("指数后必须有数字")
            while (peekIsAsciiDigit()) pos++
        }
        return JsonNumber(src.substring(start, pos))
    }

    /** 读一个字面量（true / false / null）；pos 停在首字符上，不匹配给语法错误。 */
    private fun parseLiteral(word: String, node: JsonNode): JsonNode {
        if (src.regionMatches(pos, word, 0, word.length)) {
            pos += word.length
            return node
        }
        fail("非法字面量（应为 '$word'）")
    }

    private fun peekAfterWhitespace(): Char? {
        while (pos < src.length && isRfc8259Whitespace(src[pos])) pos++
        return if (pos < src.length) src[pos] else null
    }

    /** RFC 8259 的四种空白（空格、制表、换行、回车）；不用 Char.isWhitespace（它放行全角空格等）。 */
    private fun isRfc8259Whitespace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun peek(): Char? = if (pos < src.length) src[pos] else null

    private fun peekIsAsciiDigit(): Boolean {
        val c = peek() ?: return false
        return c in '0'..'9'
    }

    private fun fail(message: String): Nothing {
        var line = 1
        var col = 1
        for (i in 0 until pos) {
            if (src[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        throw MarketJsonException("$message（第 $line 行第 $col 列）", pos)
    }
}
