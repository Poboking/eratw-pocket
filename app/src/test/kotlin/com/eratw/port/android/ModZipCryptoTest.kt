package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 加密口上 zip 的解密口（[ModZipCrypto]）回归面。
 *
 * ## 夹具为什么是「真工具产物」
 *
 * 四只夹具由 7-Zip 现场生成（命令见下），**不是本仓自己写的加密器**：
 * 「自己加密 → 自己解密」只能证明两处共犯同一个误解，证明不了与 7-Zip / WinZip 的字节口径
 * 一致（AES-CTR 的计数器字节序、PBKDF2 取键长度、ZipCrypto 的三密钥滚动，任一处偏一位都会
 * 在自环里自洽地通过）。夹具落在 `app/src/test/resources/zipcrypto/`，内容物是同一套三文件：
 *
 * ```
 * 7z a -tzip -pgege1234 -mem=AES256  aes256.zip     M_KOJO_K99_CRYPTO.ERB README.txt BIG.txt
 * 7z a -tzip -pgege1234 -mem=AES128  aes128.zip     （同上三件）
 * 7z a -tzip -pgege1234 -mem=ZipCrypto zipcrypto.zip（同上三件）
 * 7z a -tzip -pgege1234 -mem=AES256  bis-bomb.zip   BOMB.bin（9 MiB 全零）
 * ```
 *
 * 三件内容物的分工：ERB 走 STORED（AES 族 realMethod=0）+ 日文 + CRLF，覆盖「解出来的就是
 * 源字节」；BIG.txt 走 deflate（realMethod=8）、4.4 KB（200 行 `\r\n`），覆盖「解密后仍要
 * inflate」；README.txt 是第二个条目，覆盖「多条目逐条解」。AES 夹具的 extra 实测
 * version=2（AE-2），即 HMAC-SHA1 认证码那条路真的被走到（[ModZipCrypto] 对 AE-1 只比密码校验值）。
 */
class ModZipCryptoTest {

    private val erbName = "M_KOJO_K99_CRYPTO.ERB"

    private val erbText = "@KOJO_TEST\r\nPRINTL 暗号化テスト\r\nRETURN\r\n"

    private fun fixture(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/zipcrypto/$name")
        assertNotNull("缺夹具 $name（app/src/test/resources/zipcrypto/）", stream)
        return stream!!.use { it.readBytes() }
    }

    private fun unpackAll(zipBytes: ByteArray): Map<String, ByteArray> =
        ModZip.entriesWhere(zipBytes) { true }.associate { it.name to it.bytes }

    /** 三个正常夹具在解密后都应还原成同一份原文（一族一个用例，判据同一份）。 */
    private fun assertRoundTrip(name: String) {
        val entries = unpackAll(ModZipCrypto.decryptToPlainZip(fixture(name), PASSWORD))
        assertEquals("$name：解出的条目数", 3, entries.size)
        assertEquals("$name：ERB 原文", erbText, String(entries.getValue(erbName), Charsets.UTF_8))
        assertEquals("$name：README 长度", 25, entries.getValue("README.txt").size)
        val big = entries.getValue("BIG.txt")
        assertEquals("$name：BIG 长度", 4_400, big.size)
        assertEquals("$name：BIG 头", "kojo line 0123456789\r\n", String(big.copyOfRange(0, 22), Charsets.UTF_8))
    }

    @Test
    fun `JDK 自己解不开加密包——这正是本对象存在的理由`() {
        // 这条是**前提钉**：哪天 java.util.zip 开始支持解密，接线处的「先判断再救」就该重审。
        for (name in listOf("aes256.zip", "zipcrypto.zip")) {
            assertThrows("$name 应当被 java.util.zip 拒绝", Exception::class.java) { ModZip.unpack(fixture(name)) }
        }
    }

    @Test
    fun `inspect 认出两族加密并点名强度`() {
        val aes = ModZipCrypto.inspect(fixture("aes256.zip"))
        assertEquals(ModZipCrypto.Scheme.AES_256, aes?.scheme)
        assertEquals(3, (aes?.encryptedEntries ?: -1).toLong())
        assertEquals(ModZipCrypto.Scheme.AES_128, ModZipCrypto.inspect(fixture("aes128.zip"))?.scheme)
        val classic = ModZipCrypto.inspect(fixture("zipcrypto.zip"))
        assertEquals(ModZipCrypto.Scheme.ZIP_CRYPTO, classic?.scheme)
        assertEquals(3, (classic?.encryptedEntries ?: -1).toLong())
    }

    @Test
    fun `AES-256（AE-2，含 HMAC）用正确密码解出原文`() = assertRoundTrip("aes256.zip")

    @Test
    fun `AES-128（更短的 salt 与密钥）用正确密码解出原文`() = assertRoundTrip("aes128.zip")

    @Test
    fun `ZipCrypto（stored 与 deflate 混排）用正确密码解出原文`() = assertRoundTrip("zipcrypto.zip")

    @Test
    fun `解密后的明文包能直接喂给正式解包口——导入链只多一步解密`() {
        val plain = ModZipCrypto.decryptToPlainZip(fixture("aes256.zip"), PASSWORD)
        // unpack 的筛条目谓词（ERB/ERH + Chara*.csv）与未加密包完全同形：明文 zip 里
        // README/BIG 照旧被跳过，只出 ERB。
        val sources = ModZip.unpack(plain)
        assertEquals("只应留下 ERB 源", listOf(erbName), sources.map { it.name })
        assertEquals(erbText, String(sources.single().bytes, Charsets.UTF_8))
    }

    @Test
    fun `AES 密码错必被拒——2 字节校验与 10 字节 HMAC 双保险`() {
        assertThrows(ModZipCrypto.WrongPassword::class.java) {
            ModZipCrypto.decryptToPlainZip(fixture("aes256.zip"), "wrong-password")
        }
    }

    @Test
    fun `ZipCrypto 密码错绝不解出原文——头部弱校验靠 CRC 兜底`() {
        // 传统 ZipCrypto 的头部只校验**一个字节**（crc32 高字节），错密码有 1/256 概率蒙过
        // 校验位而进入「解出一堆乱码」的路径；[ModZipCrypto] 用中央目录的 CRC 自证把这种
        // 假阳性兜住。但垃圾 deflate 流仍可能先一步被判成「流损坏」（Unsupported），
        // 所以判据写成「拿不到正确原文」，而不是「必抛某一类异常」。
        val decrypted = runCatching {
            ModZipCrypto.decryptToPlainZip(fixture("zipcrypto.zip"), "wrong-password")
        }.getOrElse { error ->
            assertTrue("异常类型应指向密码或包体：$error", error is ModZipCrypto.WrongPassword)
            return
        }
        val erb = runCatching { unpackAll(decrypted).getValue(erbName) }.getOrNull()
        assertTrue(
            "错密码竟然解出了原文——ZipCrypto 校验逻辑有误",
            erb == null || String(erb, Charsets.UTF_8) != erbText,
        )
    }

    @Test
    fun `AES 压缩炸弹在解密阶段就被 ModZipGuard 拦下`() {
        // 9 MiB 全零压成 11 KB：越 MAX_ENTRY_BYTES（8 MiB）。判据必须发生在**边解边计数**时，
        // 不能先攒满再比 —— 攒满那一刻堆已经炸了。
        val rejected = assertThrows(ModZipGuard.Rejected::class.java) {
            ModZipCrypto.decryptToPlainZip(fixture("bis-bomb.zip"), PASSWORD)
        }
        assertEquals(ModZipGuard.Reason.ENTRY_SIZE, rejected.reason)
    }

    @Test
    fun `未加密的 zip 不报加密——inspect 返回 null，调用方走原有失败面`() {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zos ->
            zos.putNextEntry(ZipEntry(erbName))
            zos.write(erbText.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        assertNull("明文 zip 不该被认成加密包", ModZipCrypto.inspect(sink.toByteArray()))
    }

    @Test
    fun `非 zip 字节不冒泡成密码错——结构不支持单独一类`() {
        val junk = "not a zip at all".toByteArray(Charsets.UTF_8)
        assertNull(ModZipCrypto.inspect(junk))
        assertThrows(ModZipCrypto.Unsupported::class.java) {
            ModZipCrypto.decryptToPlainZip(junk, PASSWORD)
        }
    }

    private companion object {
        /** 四只夹具统一口令（7-Zip 的 `-p`）。 */
        const val PASSWORD = "gege1234"
    }
}
