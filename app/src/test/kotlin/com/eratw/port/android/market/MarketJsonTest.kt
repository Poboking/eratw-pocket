package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * [MarketJson] 的语法面定向单测（契约 §2.1 判据）：合法样本（对象/数组/嵌套/全部转义/
 * Unicode 转义/数字含指数/布尔/null/空白）与负例（截断/尾逗号/裸控制字符/非法转义/
 * 数字语法/尾随垃圾/深度上限）逐例断言；重复键钉「后值胜」。解析器只做语法零 schema。
 */
class MarketJsonTest {

    private fun obj(text: String): JsonObject = MarketJson.parse(text) as JsonObject

    private fun assertInvalid(text: String) {
        assertThrows("应为语法错误：$text", MarketJsonException::class.java) { MarketJson.parse(text) }
    }

    @Test
    fun `合法样本_对象数组嵌套`() {
        val o = obj("""{"a":1,"b":[true,false,null],"c":{"d":"x"}}""")
        assertTrue(o.has("a") && o.has("b") && o.has("c"))
        assertEquals("1", (o.opt("a") as JsonNumber).raw)
        val arr = o.opt("b") as JsonArray
        assertEquals(3, arr.items.size)
        assertEquals(JsonBoolean(true), arr.items[0])
        assertEquals(JsonBoolean(false), arr.items[1])
        assertEquals(JsonNull, arr.items[2])
        assertEquals("x", ((o.opt("c") as JsonObject).opt("d") as JsonString).value)
    }

    @Test
    fun `合法样本_全部短转义`() {
        val s = obj("""{"s":"\"\\\/\b\f\n\r\t"}""").opt("s") as JsonString
        assertEquals("\"\\/\b\u000C\n\r\t", s.value)
    }

    @Test
    fun `合法样本_Unicode转义与代理对`() {
        assertEquals("你好", (MarketJson.parse("\"\\u4F60\\u597D\"") as JsonString).value)
        val emoji = MarketJson.parse("\"\\uD83D\\uDE00\"") as JsonString
        assertEquals("😀", emoji.value)
        assertEquals(2, emoji.value.length) // UTF-16 代理对占两个 code unit
    }

    @Test
    fun `合法样本_数字形态_整数小数指数`() {
        fun num(text: String): JsonNumber = MarketJson.parse(text) as JsonNumber
        assertEquals("1e2", num("1e2").raw)
        assertEquals(100L, num("1e2").longValueOrNull())
        assertEquals(100L, num("1E+2").longValueOrNull())
        assertEquals(1500L, num("1.5e3").longValueOrNull())
        assertEquals(999999999L, num("999999999").longValueOrNull())
        assertEquals(123456789012345L, num("123456789012345").longValueOrNull())
        assertEquals(2L, num("2.0").longValueOrNull()) // TS Number.isInteger(2.0) 同真
        assertEquals(0L, num("-0").longValueOrNull())
        assertTrue(num("1e-2").raw == "1e-2" && num("1e-2").longValueOrNull() == null)
        assertTrue(num("-1.5").longValueOrNull() == null)
        assertTrue(num("1e999").isWholeNumber && num("1e999").longValueOrNull() == null)
        assertTrue(num("1.5").intValueOrNull() == null)
    }

    @Test
    fun `合法样本_顶层标量与空容器`() {
        assertEquals("hi", (MarketJson.parse("\"hi\"") as JsonString).value)
        assertEquals("42", (MarketJson.parse("42") as JsonNumber).raw)
        assertEquals(JsonBoolean(true), MarketJson.parse("true"))
        assertEquals(JsonNull, MarketJson.parse("null"))
        assertEquals(0, (MarketJson.parse("[]") as JsonArray).items.size)
        assertEquals(0, (MarketJson.parse("{}") as JsonObject).members.size)
        // 嵌套 4 层：顶层只有一个元素（每层各包一个）
        assertEquals(1, (MarketJson.parse("[[[[1]]]]") as JsonArray).items.size)
    }

    @Test
    fun `合法样本_空白自由`() {
        val o = obj(" \t\r\n{\n  \"a\" :\t[ 1 ,\r\n 2 ]\n}\n")
        assertEquals(2, ((o.opt("a") as JsonArray).items.size))
    }

    @Test
    fun `合法样本_深层嵌套在上限内`() {
        val depth = 200
        val text = "[".repeat(depth) + "1" + "]".repeat(depth)
        val node = MarketJson.parse(text)
        var seen = 0
        var cur: JsonNode = node
        while (cur is JsonArray) {
            seen++
            cur = cur.items[0]
        }
        assertEquals(depth, seen)
    }

    @Test
    fun `重复键后值胜`() {
        val o = obj("""{"a":1,"a":2}""")
        assertEquals("2", (o.opt("a") as JsonNumber).raw)
        assertEquals(1, o.members.size)
        val nested = obj("""{"k":{"x":1},"k":{"y":2}}""").opt("k") as JsonObject
        assertNull(nested.opt("x"))
        assertEquals("2", (nested.opt("y") as JsonNumber).raw)
    }

    @Test
    fun `负例_截断`() {
        listOf(
            "{\"a\":", "{\"a\"", "{\"a\":1", "[1,", "\"abc", "[", "{", "[1,2",
            "{\"a\":1 \"b\":2}", "\"abc\\", "{\"a\":1,",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `负例_尾逗号`() {
        listOf("[1,]", "{\"a\":1,}", "[,]", "{,}").forEach(::assertInvalid)
    }

    @Test
    fun `负例_裸控制字符`() {
        assertInvalid("\"a" + '\u0001' + "b\"")
        assertInvalid("\"x" + "\n" + "y\"")
        assertInvalid("\"x" + "\t" + "y\"")
    }

    @Test
    fun `负例_非法转义`() {
        listOf(
            "\"\\x\"", "\"\\u12g4\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\'", "\"abc\\",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `负例_数字语法`() {
        listOf(
            "01", "-01", "+1", ".5", "1.", "1e", "1e+", "-", "NaN", "Infinity",
            "0x1", "1.2.3", "--1", "1e2.5", "00",
        ).forEach(::assertInvalid)
    }

    @Test
    fun `负例_字面量与键名`() {
        listOf("True", "NULL", "tru", "nul", "{1:2}", "{a:1}", "'a'", "undefined").forEach(::assertInvalid)
    }

    @Test
    fun `负例_尾随垃圾`() {
        listOf("{}{}", "1 2", "null null", "[]x", "true false").forEach(::assertInvalid)
    }

    @Test
    fun `负例_嵌套超过自护上限`() {
        assertInvalid("[".repeat(600) + "]".repeat(600))
    }

    @Test
    fun `异常带行列定位`() {
        val e = assertThrows(MarketJsonException::class.java) { MarketJson.parse("{\n  \"a\": oops}") }
        assertTrue("消息应带行列定位：${e.message}", e.message!!.contains("第 2 行"))
        assertTrue(e.position > 0)
    }

    @Test
    fun `parseOrNull_语法错误给null_合法给值`() {
        assertNull(MarketJson.parseOrNull("{"))
        assertNotNull(MarketJson.parseOrNull("{}"))
    }
}
