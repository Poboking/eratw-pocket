package com.eratw.port.android

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * **加密口上 zip 的解密口**（纯 JVM：`javax.crypto` + `java.util.zip`，不碰 Android API）。
 *
 * ## 为什么需要它
 *
 * 社区口上包的发行形态大量是「带密码的 zip」（密码写在发布帖里），2026-11-27 实测的
 * 蕾米莉亚色情特化 v1.4.1 就是 **WinZip AES-256**（`flag_bits 0x801`、compression method 99）。
 * JDK 的 `java.util.zip` 不解密：`ZipInputStream` 读到加密条目直接抛
 * `ZipException`，玩家屏上只能看到「解包失败：可能已加密或文件损坏」——包本身是好的，
 * 缺的是解密这一步。原版 PC Emuera 侧玩家是自己用 7-Zip 解压，移植侧没有这层，
 * 于是「导入」对这类包等于不存在。
 *
 * ## 覆盖的两族格式
 *
 * - **WinZip AES（AE-1/AE-2）**：压缩方法 99 + extra field `0x9901`（version / vendor "AE" /
 *   strength / 真实压缩方法）。条目载荷 = salt（8/12/16 B）+ 2 B 密码校验值 + 密文 + 10 B 认证码；
 *   密钥 = `PBKDF2-HMAC-SHA1(密码, salt, 1000)` 取 `2*keyLen+2` 字节（加密键 / 认证键 / 校验值）；
 *   加密 = **AES-CTR，16 字节计数器小端从 1 起**（注意：不是标准 CTR 的大端递增，直接拿
 *   `AES/CTR` 会从第二个块起全错）；AE-2 另有 `HMAC-SHA1(认证键, 密文)` 前 10 字节要逐字节相等。
 * - **传统 ZipCrypto（PKZIP stream cipher）**：通用标志位 bit0，12 字节加密头 + 密文；
 *   三个 32 位密钥按 CRC32 表滚动，头部最后一字节要么等于 crc32 高字节（无 data descriptor），
 *   要么等于 dosTime 高字节（bit3 置位）——不等即密码错。
 *
 * ## 与不可信输入闸的关系（**关键设计**）
 *
 * 本对象**不**自己实现解包策略：它只把「解密 + 解压后的明文条目」重新打成一只**全 STORED
 * 的内存 zip**，再交回既有的 [ModZip.unpack] / [ModZip.entriesWhere] 走原来的
 * [ModZipGuard]（条目数 / 单条目 / 整包 / 压缩比 / 重名）。这样加密包与普通包在闸面前
 * **完全同形**，不存在「加密这条路绕过闸」的缝；解密阶段自己也按
 * [ModZipGuard.MAX_ENTRY_BYTES]/[ModZipGuard.MAX_TOTAL_BYTES] 边解边计数早退
 * （炸弹在解密前就可能炸堆，不能等打完包再判）。
 */
internal object ModZipCrypto {

    /** 加密族。口令校验失败时文案要能点名是哪一族（ZipCrypto 与 AES 的错法不一样）。 */
    enum class Scheme { ZIP_CRYPTO, AES_128, AES_192, AES_256 }

    /** [inspect] 的结论：有加密条目 + 加密族（混合包按最强的一族报）。 */
    class Info(val scheme: Scheme, val encryptedEntries: Int) {
        /**
         * 加密族的**人话名**（失败文案的 {1} 槽位用它，别把内部枚举名甩给玩家）。
         *
         * 放在 [Info] 上而不是 `ModZipCrypto` 的方法：这个 object 的函数数已在 detekt
         * 文件级/对象级阈值上（拆碎反而更难读），属性不占函数预算。
         */
        val schemeLabel: String
            get() = when (scheme) {
                Scheme.ZIP_CRYPTO -> "ZipCrypto"
                Scheme.AES_128 -> "WinZip AES-128"
                Scheme.AES_192 -> "WinZip AES-192"
                Scheme.AES_256 -> "WinZip AES-256"
            }
    }

    /** 密码错（两族都可能：AES 校验值/认证码不符，ZipCrypto 头部校验字节不符）。 */
    class WrongPassword : Exception("zip 解压密码不正确")

    /** 结构不受支持（ZIP64 / 分卷 / 未知加密强度 / 截断）：调用方折成「解包失败」。 */
    class Unsupported(reason: String) : Exception(reason)

    /** AES 条目的固定尾长（认证码）。 */
    private const val AES_AUTH_BYTES = 10

    /** AES 密码校验值长度。 */
    private const val AES_VERIFY_BYTES = 2

    /** PBKDF2 迭代次数（WinZip AES 规范固定 1000）。 */
    private const val AES_ITERATIONS = 1000

    private const val SIG_LOCAL = 0x04034b50
    private const val SIG_CENTRAL = 0x02014b50
    private const val SIG_EOCD = 0x06054b50

    /** extra field 头：AES 参数（version / vendor / strength / 真实压缩方法）。 */
    private const val EXTRA_AES = 0x9901

    /**
     * 扫中央目录判「这只 zip 有没有加密条目、是哪一族」。**不解压任何字节**。
     *
     * 返回 null = 没有加密条目（此时解包失败就是坏包，走原有文案）；结构读不通同样返回
     * null（调用方已有「解包失败」面，不在这里另造一套错误语言）。
     */
    fun inspect(zipBytes: ByteArray): Info? = try {
        val cd = CentralDirectory.parse(zipBytes)
        var encrypted = 0
        var scheme: Scheme? = null
        for (entry in cd.entries) {
            if (!entry.encrypted) continue
            encrypted++
            val s = entry.scheme
            if (s != null && rank(s) > rank(scheme)) scheme = s
        }
        if (encrypted == 0) null else Info(scheme ?: Scheme.ZIP_CRYPTO, encrypted)
    } catch (e: Unsupported) {
        null
    }

    /**
     * 用 [password] 解密整只 zip，返回**全 STORED 的明文 zip 字节**（条目名原样保留原始
     * 名字节与 UTF-8 标志位，供 [ModZip.nameCharsetOf] 的探测逻辑照旧工作）。
     *
     * 抛 [WrongPassword]（密码错）/ [Unsupported]（结构不支持）/ [ModZipGuard.Rejected]
     * （解压量越闸）。三者都发生在任何落盘之前。
     */
    fun decryptToPlainZip(zipBytes: ByteArray, password: String): ByteArray {
        val cd = CentralDirectory.parse(zipBytes)
        val pwd = password.toByteArray(Charsets.UTF_8)
        var total = 0L
        val out = ArrayList<StoredEntry>(cd.entries.size)
        for (entry in cd.entries) {
            // 目录条目（名字以 '/' 收尾）不进包：ModZip 侧本来也一律跳过。
            val name = entry.meta.nameBytes
            if (name.isNotEmpty() && name.last() == '/'.code.toByte()) continue
            val budget = ModZipGuard.MAX_TOTAL_BYTES - total
            val plain = entry.plainBytes(zipBytes, pwd, budget)
            total += plain.size
            if (total > ModZipGuard.MAX_TOTAL_BYTES) {
                throw ModZipGuard.Rejected(ModZipGuard.Reason.TOTAL_SIZE, nameOf(entry), total)
            }
            out += StoredEntry(entry.meta, plain)
            if (out.size > ModZipGuard.MAX_ENTRIES) {
                throw ModZipGuard.Rejected(ModZipGuard.Reason.ENTRY_COUNT, nameOf(entry), out.size.toLong())
            }
        }
        return StoredZipWriter.write(out)
    }

    private fun nameOf(entry: CdEntry): String =
        runCatching { String(entry.meta.nameBytes, Charsets.UTF_8) }.getOrDefault("?")

    private fun crc32Of(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun rank(s: Scheme?): Int = when (s) {
        null -> 0
        Scheme.ZIP_CRYPTO -> 1
        Scheme.AES_128 -> 2
        Scheme.AES_192 -> 3
        Scheme.AES_256 -> 4
    }

    // ------------------------------------------------------------------
    // 中央目录
    // ------------------------------------------------------------------

    /** 条目「非数据」描述：名字字节 / UTF-8 标志位 / 时间戳 / 外部属性 / 本地头偏移。 */
    private class EntryMeta(
        val nameBytes: ByteArray,
        val utf8Name: Boolean,
        val dosTime: Int,
        val dosDate: Int,
        val externalAttr: Int,
        val localOffset: Int,
    )

    /** 一条中央目录记录（只留解密/改写要用的字段）。 */
    private class CdEntry(
        val meta: EntryMeta,
        val flags: Int,
        val method: Int,
        val crc32: Long,
        val compSize: Int,
        /** AES 参数（仅 method==99 时非 null）。 */
        val aes: AesExtra?,
    ) {
        val encrypted: Boolean get() = flags and 0x1 != 0

        /** 本条目的加密族。 */
        val scheme: Scheme?
            get() = when {
                !encrypted -> null
                aes != null -> when (aes.strength) {
                    1 -> Scheme.AES_128
                    2 -> Scheme.AES_192
                    else -> Scheme.AES_256
                }
                else -> Scheme.ZIP_CRYPTO
            }

        /**
         * 本条目**解压后**的明文字节。加密的先解密再 inflate；未加密的直接 inflate。
         *
         * [budget] = 整包剩余额度，用于边解边判（不先攒满再比）。
         */
        fun plainBytes(zipBytes: ByteArray, password: ByteArray, budget: Long): ByteArray {
            val dataOff = localDataOffset(zipBytes, meta.localOffset)
            if (compSize < 0 || dataOff + compSize > zipBytes.size) {
                throw Unsupported("zip 条目数据越界：${nameOf(this)}")
            }
            val raw: ByteArray
            val compression: Int
            if (!encrypted) {
                raw = zipBytes.copyOfRange(dataOff, dataOff + compSize)
                compression = method
            } else {
                val aesParams = aes
                if (aesParams != null) {
                    val r = aesDecrypt(zipBytes, dataOff, this, aesParams, password)
                    raw = r.first
                    compression = r.second
                } else {
                    raw = zipCryptoDecrypt(zipBytes, dataOff, this, password)
                    compression = method
                }
            }
            val plain = when (compression) {
                0 -> raw
                8 -> inflateRaw(raw, this, budget)
                else -> throw Unsupported("不支持的压缩方法 $compression：${nameOf(this)}")
            }
            // 加密条目的**完整性自证**：口令对 ⇒ 原文 CRC 必须与中央目录里那条相等。
            // 为什么必须有这一步：ZipCrypto 的头部只校验 1 个字节，错密码有 1/256 概率蒙过
            // 校验位后解出一堆乱码——不自己核 CRC，就会把乱码当成功解出来送进编译器，
            // 玩家看到的是一串「未定义的标识符」而不是「密码不对」。crc32==0 的包跳过
            // （个别 AE-1 打包器不写 CRC，此时无从自证，只能信前面的校验）。
            if (encrypted && crc32 != 0L && crc32Of(plain) != crc32) throw WrongPassword()
            return plain
        }
    }

    /** AES extra field（`0x9901`）的内容。 */
    private class AesExtra(val version: Int, val strength: Int, val realMethod: Int)

    private class CentralDirectory(val entries: List<CdEntry>) {
        companion object {
            fun parse(b: ByteArray): CentralDirectory {
                val eocd = findEocd(b) ?: throw Unsupported("找不到 zip 目录尾（EOCD）：可能不是 zip 或被截断")
                val total = u16(b, eocd + 10)
                val cdSize = u32(b, eocd + 12)
                val cdOff = u32(b, eocd + 16)
                if (total == 0xFFFF || cdOff == -1 || cdSize == -1) throw Unsupported("ZIP64 不受支持")
                if (cdOff < 0 || cdSize < 0 || cdOff.toLong() + cdSize > b.size) {
                    throw Unsupported("zip 中央目录越界")
                }
                val entries = ArrayList<CdEntry>(minOf(total, 64))
                var p = cdOff
                repeat(total) {
                    if (p + 46 > b.size || u32(b, p) != SIG_CENTRAL) throw Unsupported("中央目录记录损坏 @$p")
                    val flags = u16(b, p + 8)
                    val method = u16(b, p + 10)
                    val dosTime = u16(b, p + 12)
                    val dosDate = u16(b, p + 14)
                    val crc = u32(b, p + 16).toLong() and 0xFFFFFFFFL
                    val compSize = u32(b, p + 20)
                    val nameLen = u16(b, p + 28)
                    val extraLen = u16(b, p + 30)
                    val commentLen = u16(b, p + 32)
                    val externalAttr = u32(b, p + 38)
                    val localOff = u32(b, p + 42)
                    if (p + 46 + nameLen + extraLen + commentLen > b.size) throw Unsupported("中央目录越界 @$p")
                    val nameBytes = b.copyOfRange(p + 46, p + 46 + nameLen)
                    val extra = b.copyOfRange(p + 46 + nameLen, p + 46 + nameLen + extraLen)
                    val aes = if (method == 99) parseAesExtra(extra) else null
                    entries += CdEntry(
                        meta = EntryMeta(
                            nameBytes = nameBytes,
                            utf8Name = flags and 0x800 != 0,
                            dosTime = dosTime,
                            dosDate = dosDate,
                            externalAttr = externalAttr,
                            localOffset = localOff,
                        ),
                        flags = flags,
                        method = method,
                        crc32 = crc,
                        compSize = compSize,
                        aes = aes,
                    )
                    p += 46 + nameLen + extraLen + commentLen
                }
                return CentralDirectory(entries)
            }

            /** EOCD 在文件尾，注释最长 65535 ⇒ 从尾部往前扫最多 64 KiB + 22。 */
            private fun findEocd(b: ByteArray): Int? {
                if (b.size < 22) return null
                val stop = maxOf(0, b.size - 22 - 0xFFFF)
                var i = b.size - 22
                while (i >= stop) {
                    if (u32(b, i) == SIG_EOCD) return i
                    i--
                }
                return null
            }

            /** `0x9901`：version(2) vendor(2)=`"AE"` strength(1) method(2) —— 共 7 字节。 */
            private fun parseAesExtra(extra: ByteArray): AesExtra? {
                var i = 0
                while (i + 4 <= extra.size) {
                    val id = u16(extra, i)
                    val len = u16(extra, i + 2)
                    if (i + 4 + len > extra.size) return null
                    if (id == EXTRA_AES) {
                        if (len < 7) return null
                        // 偏移必须按规范逐字对：i+4 起 version(2)、i+6 起 vendor(2) 的 `"AE"`，
                        // 强度在 i+8 那**一个**字节上。少算 vendor 那两个字节就会把 'A'(65)
                        // 读成强度 —— 实测 7-Zip 的包会因此报「未知的 AES 强度 65」。
                        val version = u16(extra, i + 4)
                        val strength = extra[i + 8].toInt() and 0xFF
                        val realMethod = u16(extra, i + 9)
                        if (strength !in 1..3) throw Unsupported("未知的 AES 强度 $strength")
                        return AesExtra(version, strength, realMethod)
                    }
                    i += 4 + len
                }
                return null
            }
        }
    }

    /** 本地文件头里的数据起点（名字/extra 长度只有本地头说得准）。 */
    private fun localDataOffset(b: ByteArray, localOff: Int): Int {
        if (localOff < 0 || localOff + 30 > b.size || u32(b, localOff) != SIG_LOCAL) {
            throw Unsupported("本地文件头损坏 @$localOff")
        }
        val nameLen = u16(b, localOff + 26)
        val extraLen = u16(b, localOff + 28)
        return localOff + 30 + nameLen + extraLen
    }

    // ------------------------------------------------------------------
    // AES（AE-1 / AE-2）
    // ------------------------------------------------------------------

    /** 返回（明文字节, 真实压缩方法）。 */
    private fun aesDecrypt(
        b: ByteArray,
        dataOff: Int,
        entry: CdEntry,
        aes: AesExtra,
        password: ByteArray,
    ): Pair<ByteArray, Int> {
        val saltLen = when (aes.strength) {
            1 -> 8
            2 -> 12
            else -> 16
        }
        val keyLen = when (aes.strength) {
            1 -> 16
            2 -> 24
            else -> 32
        }
        if (entry.compSize < saltLen + AES_VERIFY_BYTES + AES_AUTH_BYTES) {
            throw Unsupported("AES 条目长度不足：${nameOf(entry)}")
        }
        val salt = b.copyOfRange(dataOff, dataOff + saltLen)
        val verify = b.copyOfRange(dataOff + saltLen, dataOff + saltLen + AES_VERIFY_BYTES)
        val ctLen = entry.compSize - saltLen - AES_VERIFY_BYTES - AES_AUTH_BYTES
        val ct = b.copyOfRange(dataOff + saltLen + AES_VERIFY_BYTES, dataOff + saltLen + AES_VERIFY_BYTES + ctLen)
        val auth = b.copyOfRange(dataOff + entry.compSize - AES_AUTH_BYTES, dataOff + entry.compSize)

        val derived = pbkdf2(password, salt, keyLen * 2 + AES_VERIFY_BYTES)
        if (derived[keyLen * 2] != verify[0] || derived[keyLen * 2 + 1] != verify[1]) throw WrongPassword()
        val encKey = derived.copyOfRange(0, keyLen)
        val authKey = derived.copyOfRange(keyLen, keyLen * 2)
        // AE-1（version 1）不写认证码，只做密码校验值比对；AE-2 必须验 HMAC。
        if (aes.version >= 2) {
            val mac = hmacSha1(authKey, ct)
            for (i in 0 until AES_AUTH_BYTES) {
                if (mac[i] != auth[i]) throw WrongPassword()
            }
        }
        return aesCtr(encKey, ct) to aes.realMethod
    }

    /**
     * WinZip AES 的 CTR：**16 字节计数器块，前 4 字节是 32 位小端计数，从 1 起**，
     * 其余字节为 0。不能直接用 `AES/CTR/NoPadding`（JCE 是 128 位大端递增，第二块起就错位）。
     */
    private fun aesCtr(key: ByteArray, ct: ByteArray): ByteArray {
        val aes = Cipher.getInstance("AES/ECB/NoPadding")
        aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val out = ByteArray(ct.size)
        val counter = ByteArray(16)
        var c = 1L
        var off = 0
        while (off < ct.size) {
            counter[0] = (c and 0xFF).toByte()
            counter[1] = ((c ushr 8) and 0xFF).toByte()
            counter[2] = ((c ushr 16) and 0xFF).toByte()
            counter[3] = ((c ushr 24) and 0xFF).toByte()
            val ks = aes.update(counter)
            val n = minOf(16, ct.size - off)
            for (i in 0 until n) out[off + i] = (ct[off + i].toInt() xor ks[i].toInt()).toByte()
            off += n
            c = (c + 1) and 0xFFFFFFFFL
        }
        return out
    }

    private fun pbkdf2(password: ByteArray, salt: ByteArray, len: Int): ByteArray {
        // PBEKeySpec 只吃 char[]；密码按 UTF-8 解出的 char 序列再交给它，等价于原字节
        // （规范里密码就是「UTF-8 字节」，非 ASCII 密码此处与 7-Zip/WinRAR 同口径）。
        val chars = String(password, Charsets.UTF_8).toCharArray()
        val spec = PBEKeySpec(chars, salt, AES_ITERATIONS, len * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    // ------------------------------------------------------------------
    // 传统 ZipCrypto
    // ------------------------------------------------------------------

    private fun zipCryptoDecrypt(b: ByteArray, dataOff: Int, entry: CdEntry, password: ByteArray): ByteArray {
        val keys = ZipCryptoKeys()
        for (p in password) keys.update(p.toInt() and 0xFF)
        val len = entry.compSize
        if (len < 12) throw Unsupported("ZipCrypto 条目长度不足：${nameOf(entry)}")
        val dec = ByteArray(len)
        for (i in 0 until len) {
            val c = b[dataOff + i].toInt() and 0xFF
            val p = c xor keys.decryptByte()
            keys.update(p)
            dec[i] = p.toByte()
        }
        // 头部最后一字节的校验：无 data descriptor 用 crc32 高字节，有则用 dosTime 高字节。
        val expect = if (entry.flags and 0x8 != 0) {
            (entry.meta.dosTime ushr 8) and 0xFF
        } else {
            ((entry.crc32 ushr 24) and 0xFF).toInt()
        }
        if ((dec[11].toInt() and 0xFF) != expect) throw WrongPassword()
        return dec.copyOfRange(12, len)
    }

    /** PKZIP 流密码的三个 32 位密钥（crc32 表滚动，spec: APPNOTE.TXT §6.1）。 */
    private class ZipCryptoKeys {
        private var k0 = 0x12345678
        private var k1 = 0x23456789
        private var k2 = 0x34567890

        fun update(plain: Int) {
            k0 = (k0 ushr 8) xor CRC_TABLE[(k0 xor plain) and 0xFF]
            k1 = (k1 + (k0 and 0xFF)) * 134775813 + 1
            k2 = (k2 ushr 8) xor CRC_TABLE[(k2 xor (k1 ushr 24)) and 0xFF]
        }

        fun decryptByte(): Int {
            val t = (k2 or 2) and 0xFFFF
            return ((t * (t xor 1)) ushr 8) and 0xFF
        }
    }

    /** crc32 查表（多项式 0xEDB88320，与 `java.util.zip.CRC32` 同一张表）。 */
    private val CRC_TABLE: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    // ------------------------------------------------------------------
    // 解压（raw deflate）与 STORED zip 写出
    // ------------------------------------------------------------------

    /** raw deflate 解压 + 边解边判额度（[ModZipGuard] 的同一把尺）。 */
    private fun inflateRaw(src: ByteArray, entry: CdEntry, budget: Long): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(src)
            val out = ByteArrayOutputStream(minOf(maxOf(src.size * 4, 1024), 1 shl 20))
            val buf = ByteArray(ModZipGuard.READ_CHUNK_BYTES)
            var total = 0L
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    // 0 字节产出只有三种正当来源：解完 / 缺血 / 缺字典。都不是就说明流停了
                    // ——绝不能用 continue 兜（不可信输入上的死循环 = 卡死 UI）。
                    if (inflater.finished()) break
                    throw Unsupported("deflate 流停滞或截断：${nameOf(entry)}")
                }
                total += n
                if (total > ModZipGuard.MAX_ENTRY_BYTES || total > budget) {
                    throw ModZipGuard.Rejected(ModZipGuard.Reason.ENTRY_SIZE, nameOf(entry), total)
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw Unsupported("deflate 流损坏：${nameOf(entry)} —— ${e.message}")
        } finally {
            inflater.end()
        }
    }

    /** 一支已解出明文的条目（写 STORED zip 用）。 */
    private class StoredEntry(val meta: EntryMeta, val data: ByteArray)

    /**
     * 全 STORED 的最小 zip 写出器（无 extra / 无注释 / 单卷）。
     *
     * 条目名按**原始字节**写回并原样带 UTF-8 标志位：名字编码探测（[ModZip.nameCharsetOf]）
     * 靠它，重新编码只会平白多一次有损转换。
     */
    private object StoredZipWriter {
        fun write(entries: List<StoredEntry>): ByteArray {
            val out = ByteArrayOutputStream(entries.sumOf { it.data.size } + entries.size * 96 + 22)
            val offsets = IntArray(entries.size)
            for ((i, e) in entries.withIndex()) {
                offsets[i] = out.size()
                writeLocal(out, e)
            }
            val cdStart = out.size()
            for ((i, e) in entries.withIndex()) writeCentral(out, e, offsets[i])
            val cdSize = out.size() - cdStart
            writeEocd(out, entries.size, cdStart, cdSize)
            return out.toByteArray()
        }

        private fun crcOf(data: ByteArray): Long = CRC32().apply { update(data) }.value

        private fun writeLocal(out: ByteArrayOutputStream, e: StoredEntry) {
            val crc = crcOf(e.data)
            u32(out, SIG_LOCAL)
            u16(out, 20)
            u16(out, if (e.meta.utf8Name) 0x800 else 0)
            u16(out, 0) // STORED
            u16(out, e.meta.dosTime)
            u16(out, e.meta.dosDate)
            u32(out, crc.toInt())
            u32(out, e.data.size)
            u32(out, e.data.size)
            u16(out, e.meta.nameBytes.size)
            u16(out, 0)
            out.write(e.meta.nameBytes)
            out.write(e.data)
        }

        private fun writeCentral(out: ByteArrayOutputStream, e: StoredEntry, localOffset: Int) {
            val crc = crcOf(e.data)
            u32(out, SIG_CENTRAL)
            u16(out, 20)
            u16(out, 20)
            u16(out, if (e.meta.utf8Name) 0x800 else 0)
            u16(out, 0)
            u16(out, e.meta.dosTime)
            u16(out, e.meta.dosDate)
            u32(out, crc.toInt())
            u32(out, e.data.size)
            u32(out, e.data.size)
            u16(out, e.meta.nameBytes.size)
            u16(out, 0)
            u16(out, 0)
            u16(out, 0)
            u16(out, 0)
            u32(out, e.meta.externalAttr)
            u32(out, localOffset)
            out.write(e.meta.nameBytes)
        }

        private fun writeEocd(out: ByteArrayOutputStream, count: Int, cdStart: Int, cdSize: Int) {
            u32(out, SIG_EOCD)
            u16(out, 0)
            u16(out, 0)
            u16(out, count)
            u16(out, count)
            u32(out, cdSize)
            u32(out, cdStart)
            u16(out, 0)
        }

        private fun u16(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        private fun u32(out: ByteArrayOutputStream, v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
    }

    // ------------------------------------------------------------------
    // 小端读取
    // ------------------------------------------------------------------

    private fun u16(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 2 > b.size) throw Unsupported("zip 读取越界 @$off")
        return (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(b: ByteArray, off: Int): Int {
        if (off < 0 || off + 4 > b.size) throw Unsupported("zip 读取越界 @$off")
        return (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
