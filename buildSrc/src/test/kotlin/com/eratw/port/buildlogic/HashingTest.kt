package com.eratw.port.buildlogic

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HashingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sha256Of 已知向量 abc`() {
        val f = tmp.newFile("abc.txt")
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Of(f))
    }

    @Test
    fun `sha256Of 空文件`() {
        val f = tmp.newFile("empty.bin")
        f.writeBytes(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Of(f))
    }

    @Test
    fun `sha256Of 跨 1MiB 缓冲边界与单发一致`() {
        // 1 MiB 缓冲 + 尾部不满块：验证流式循环没有丢尾/多读
        val payload = ByteArray((1 shl 20) + 17) { ((it * 31 + 7) and 0xff).toByte() }
        val f = tmp.newFile("multi.bin")
        f.writeBytes(payload)
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).hexLower()
        assertEquals(expected, sha256Of(f))
    }

    @Test
    fun `hexLower 逐字节十六进制`() {
        assertEquals("0fa07f00", byteArrayOf(0x0f, 0xa0.toByte(), 0x7f, 0x00).hexLower())
        assertEquals("", ByteArray(0).hexLower())
    }
}
