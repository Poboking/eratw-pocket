package com.eratw.port.platform.update

import java.io.BufferedReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateCheckTransport] 的纯层定向单测（L6 P2 收尾）。
 *
 * 只钉能离开真机/真网卡的那两块：
 * - [usableNetworkInterface]：逐块网卡判据的「读数抛异常也不许把整机判成没网」兜底
 *   （真 `NetworkInterface` 造不出来，所以把两个读数抽成可注入 lambda）；
 * - [readCappedLine]：行尾三形态 / EOF / **预算上限即放弃** —— `maxBytes` 是两行合计口径，
 *   超预算的一行不许被截成前缀混进版本判断。
 *
 * HTTP 管线本身（超时、非 2xx、重定向）不在本类：那要有真连接，归真机/联调面。
 */
class UpdateCheckTransportTest {

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text))

    // ------------------------------------------------------------------
    // 网卡判据：两个读数都在 runCatching 里
    // ------------------------------------------------------------------

    @Test
    fun `网卡判据的取值矩阵`() {
        assertTrue(usableNetworkInterface(isLoopback = { false }, isUp = { true }))
        assertFalse("回环不算可用网卡", usableNetworkInterface(isLoopback = { true }, isUp = { true }))
        assertFalse("down 的网卡不算", usableNetworkInterface(isLoopback = { false }, isUp = { false }))
        // 非回环但状态读不出：按「这块不算数」处理（不是把整机判成没网的那一侧由 any 汇总）
        assertFalse(usableNetworkInterface(isLoopback = { false }, isUp = { throw IllegalStateException("ROM isUp 抛") }))
    }

    @Test
    fun `isLoopback 抛异常只让这块不算_不许冒泡`() {
        // 旧实现把 `!nf.isLoopback` 放在 runCatching 外面：一块网卡读回环属性抛异常，
        // 整条 isNetworkAvailable 的 any 就炸掉/被外层吞成 false ⇒ 有网的机器被说成没网。
        assertFalse(usableNetworkInterface(isLoopback = { throw IllegalArgumentException("ROM isLoopback 抛") }, isUp = { true }))
        // 两个读数都抛也一样：只这一块不算数。
        assertFalse(usableNetworkInterface(isLoopback = { throw Error("x") }, isUp = { throw Error("y") }))
    }

    // ------------------------------------------------------------------
    // readCappedLine：行尾 / EOF / 预算
    // ------------------------------------------------------------------

    @Test
    fun `行尾三形态与EOF`() {
        assertEquals("LF 行尾", "abc", reader("abc\nrest").readCappedLine(10))
        assertEquals("CRLF 行尾", "abc", reader("abc\r\nrest").readCappedLine(10))
        assertEquals("单独 CR 也算行尾", "abc", reader("abc\rrest").readCappedLine(10))
        assertEquals("EOF 收尾仍有内容时返回该行", "abc", reader("abc").readCappedLine(10))
        assertNull("EOF 且一个字符都没有 ⇒ null", reader("").readCappedLine(10))
    }


    @Test
    fun `恰好到预算不算超_超一个字符整行放弃`() {
        // 恰好等于 cap 且随即行尾：合法返回
        assertEquals("abc", reader("abc\nrest").readCappedLine(3))
        assertEquals("abc", reader("abc").readCappedLine(3))
        // 超过 cap 一个字符：整行作废（返回 null ⇒ 调用方按「取回内容不对」落 3），
        // 绝不把 "abc" 截出来当成一条看起来正常的版本行。
        assertNull(reader("abcd\nrest").readCappedLine(3))
        assertNull(reader("abcd").readCappedLine(3))
        // 预算为 0（两行合计已经用完）：直接放弃
        assertNull(reader("x\n").readCappedLine(0))
        assertNull(reader("x\n").readCappedLine(-1))
    }

    @Test
    fun `两行合计预算由调用方扣减_第二行只剩差额`() {
        // 模拟 fetchVersionInfo 的口径：maxBytes=5，第一行 3 字符 ⇒ 第二行只剩 2 个字符预算。
        val r = reader("abc\nde\nf\n")
        val first = r.readCappedLine(5)
        assertEquals("abc", first)
        val second = r.readCappedLine(5 - first!!.length)
        assertEquals("de", second)
        // 若第二行超过剩余预算：放弃，而不是返回截断前缀。
        val r2 = reader("abc\ndefg\n")
        val first2 = r2.readCappedLine(5)
        assertEquals("abc", first2)
        assertNull(r2.readCappedLine(5 - first2!!.length))
    }
}
