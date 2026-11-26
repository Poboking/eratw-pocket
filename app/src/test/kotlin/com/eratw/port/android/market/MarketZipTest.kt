package com.eratw.port.android.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.DeflaterOutputStream

/**
 * 防穿越解压的定向单测（CONTRACT §2.2 判据逐条）。
 *
 * 容器字节全部由 [FakeZipBuilder] 现打（可控制声明尺寸/标志/压缩法/名节编码——
 * 正路 `ZipOutputStream` 撒不了谎，而「容器撒谎」恰是这层闸的存在理由）。
 * 反向证明锚：`dotdot` 用例摘掉主实现的 `..` 段闸即转红；`bomb` 用例摘掉在飞闸即转红。
 */
class MarketZipTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 断言「拒绝且拒因码正确」，返回异常供 detail 取证。 */
    private fun rejects(code: String, block: () -> Unit): MarketZip.Reject = try {
        block()
        fail("期望拒绝 $code，实际放行")
        error("unreachable")
    } catch (e: MarketZip.Reject) {
        assertEquals(code, e.code)
        e
    }

    /** 打 zip 到盘。 */
    private fun writeZip(name: String, zipBytes: ByteArray): File =
        tmp.newFile(name).apply { writeBytes(zipBytes) }

    /** 打 zip 并解压（正常链路的壳）。 */
    private fun extract(zipBytes: ByteArray): Pair<File, MarketZip.ExtractResult> {
        val zip = writeZip("c.zip", zipBytes)
        val out = tmp.newFolder("out")
        return out to MarketZip.extract(zip, out)
    }

    /** 打 zip、解压、断言拒绝码。 */
    private fun extractRejects(zipBytes: ByteArray, code: String): MarketZip.Reject {
        val zip = writeZip("c.zip", zipBytes)
        val out = tmp.newFolder("out")
        return rejects(code) { MarketZip.extract(zip, out) }
    }

    @Test
    fun `happy path extracts pack json plus files tree and reports facts`() {
        val zip = FakeZipBuilder.build(
            listOf(
                FakeZipBuilder.entry("pack.json", "schema=1".toByteArray()),
                FakeZipBuilder.entry("files/a.txt", "alpha".toByteArray()),
                FakeZipBuilder.entry("files/sub/b.txt", "beta!".toByteArray()),
            ),
        )
        val (out, result) = extract(zip)
        assertEquals("schema=1", File(out, "pack.json").readText())
        assertEquals("alpha", File(out, "files/a.txt").readText())
        assertEquals("beta!", File(out, "files/sub/b.txt").readText())
        assertEquals(3, result.entries.size)
        assertEquals(8L + 5L + 5L, result.totalBytes)
        assertEquals("files/a.txt", result.entries[1].path)
        assertEquals(5L, result.entries[1].bytes)
    }

    @Test
    fun `dotdot entry rejected before anything is extracted`() {
        val zip = FakeZipBuilder.build(
            listOf(
                FakeZipBuilder.entry("pack.json", "schema=1".toByteArray()),
                FakeZipBuilder.entry("../evil.txt", "boom".toByteArray()),
            ),
        )
        val zipFile = writeZip("dotdot.zip", zip)
        val out = tmp.newFolder("dotdot-out")
        rejects(MarketRejectCodes.PATH_TRAVERSAL) { MarketZip.extract(zipFile, out) }
        // 预扫在动盘之前：一个文件都不许落
        assertFalse(File(out, "pack.json").exists())
        assertTrue(out.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `absolute path entry rejected as traversal`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("/etc/evil", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_TRAVERSAL)
    }

    @Test
    fun `drive letter entry rejected as traversal`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("C:evil.txt", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_TRAVERSAL)
    }

    @Test
    fun `colon beyond drive position rejected as traversal`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("files/a.txt:stream", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_TRAVERSAL)
    }

    @Test
    fun `backslash entry rejected as traversal`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("files\\a.txt", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_TRAVERSAL)
    }

    @Test
    fun `top level entry outside pack json and files rejected`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("evil.txt", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `bare files directory entry rejected`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("files/", ByteArray(0))))
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `trailing dot segment rejected`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("files/a.txt.", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `dot segment rejected`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("files/./a.txt", "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `duplicate entry names rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(
                FakeZipBuilder.entry("files/a.txt", "one".toByteArray()),
                FakeZipBuilder.entry("files/a.txt", "two".toByteArray()),
            ),
        )
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `duplicate entry names differing only by case rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(
                FakeZipBuilder.entry("files/A.TXT", "one".toByteArray()),
                FakeZipBuilder.entry("files/a.txt", "two".toByteArray()),
            ),
        )
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `depth beyond limit rejected`() {
        // files/ 之下再叠 12 段 = 总深度 13，超上限 12
        val deep = "files/" + (1..12).joinToString("/") { "d$it" } + "/leaf.txt"
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry(deep, "x".toByteArray())))
        extractRejects(zip, MarketRejectCodes.PATH_INVALID)
    }

    @Test
    fun `entry count beyond limit rejected`() {
        val entries = (1..(MarketContract.MAX_FILE_ENTRIES + 1)).map {
            FakeZipBuilder.entry("files/n$it.txt", "x".toByteArray())
        }
        val zip = FakeZipBuilder.build(entries)
        extractRejects(zip, MarketRejectCodes.TOO_MANY_ENTRIES)
    }

    @Test
    fun `declared single entry beyond cap rejected in prescan without inflating`() {
        // 实体只有 3 字节，声明解压上限 + 1：预扫按声明值拒，不给 inflate 机会
        val zip = FakeZipBuilder.build(
            listOf(
                FakeZipBuilder.entry(
                    "files/big.bin",
                    "abc".toByteArray(),
                    EntryTweak(declaredSize = MarketContract.MAX_UNPACKED_BYTES + 1),
                ),
            ),
        )
        val out = tmp.newFolder("prescan-out")
        val zipFile = writeZip("big.zip", zip)
        rejects(MarketRejectCodes.UNPACKED_TOO_BIG) { MarketZip.extract(zipFile, out) }
        assertTrue(out.listFiles()?.isEmpty() == true)
    }

    @Test
    fun `accumulated declared total beyond cap rejected`() {
        val half = MarketContract.MAX_UNPACKED_BYTES / 2 + 1
        val zip = FakeZipBuilder.build(
            listOf(
                FakeZipBuilder.entry("files/a.bin", "abc".toByteArray(), EntryTweak(declaredSize = half)),
                FakeZipBuilder.entry("files/b.bin", "abc".toByteArray(), EntryTweak(declaredSize = half)),
            ),
        )
        extractRejects(zip, MarketRejectCodes.UNPACKED_TOO_BIG)
    }

    @Test
    fun `inflate beyond declared size rejected as bomb`() {
        // 声明 10 字节，实际 deflate 出 50 字节：在飞闸当场拒
        val payload = ByteArray(50) { 'x'.code.toByte() }
        val bomb = FakeZipBuilder.entry("files/bomb.bin", payload, EntryTweak(declaredSize = 10L))
        val zip = FakeZipBuilder.build(listOf(bomb))
        extractRejects(zip, MarketRejectCodes.UNPACKED_TOO_BIG)
    }

    @Test
    fun `stream shorter than declared rejected as unreadable`() {
        // 声明 100 字节，实际只有 5 字节的 deflate 流：提前耗尽
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/short.bin", "tiny!".toByteArray(), EntryTweak(declaredSize = 100L))),
        )
        extractRejects(zip, MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `encrypted flag rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/a.txt", "x".toByteArray(), EntryTweak(flags = 0x0001))),
        )
        extractRejects(zip, MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `data descriptor flag rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/a.txt", "x".toByteArray(), EntryTweak(flags = 0x0008))),
        )
        extractRejects(zip, MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `method outside store and deflate rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/a.txt", "x".toByteArray(), EntryTweak(method = 99))),
        )
        extractRejects(zip, MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `symlink unix mode rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/link", "x".toByteArray(), EntryTweak(externalAttrs = 0xA1FF0000L))),
        )
        extractRejects(zip, MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `unreadable zip header rejected`() {
        extractRejects("this is not a zip at all, not even close".toByteArray(), MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `truncated central directory rejected`() {
        val zip = FakeZipBuilder.build(listOf(FakeZipBuilder.entry("files/a.txt", "x".toByteArray())))
        extractRejects(zip.copyOfRange(0, zip.size - 10), MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `stored method entries extract verbatim`() {
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/raw.bin", "raw-bytes".toByteArray(), EntryTweak(method = 0))),
        )
        val (out, result) = extract(zip)
        assertEquals("raw-bytes", File(out, "files/raw.bin").readText())
        assertEquals(9L, result.totalBytes)
    }

    @Test
    fun `cp932 entry names probe falls back and decodes names`() {
        // 名节按 CP932 编码（无 UTF-8 标志）：UTF-8 解出乱码名、CP932 解干净 → 择优 CP932
        val nameBytes = "files/背景.png".toByteArray(CharsetCp932.CHARSET)
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/背景.png", "png!".toByteArray(), EntryTweak(rawName = nameBytes))),
        )
        val (out, _) = extract(zip)
        assertEquals("png!", File(out, "files/背景.png").readText())
    }

    @Test
    fun `zip64 marker rejected`() {
        val zip = FakeZipBuilder.build(
            listOf(FakeZipBuilder.entry("files/a.txt", "x".toByteArray())),
            zip64Sentinel = true,
        )
        extractRejects(zip, MarketRejectCodes.EXTRACT_FAILED)
    }

    @Test
    fun `oversized container file rejected before parse`() {
        val huge = tmp.newFile("huge.zip")
        java.io.RandomAccessFile(huge, "rw").use { it.setLength(MarketContract.MAX_ZIP_BYTES + 1) }
        val out = tmp.newFolder("huge-out")
        rejects(MarketRejectCodes.ZIP_TOO_BIG) { MarketZip.extract(huge, out) }
    }
}

/** CP932 测试夹具：windows-31j 缺席时退 Shift_JIS（与主实现同一对候选）。 */
private object CharsetCp932 {
    val CHARSET: Charset = runCatching { Charset.forName("windows-31j") }
        .getOrElse { Charset.forName("Shift_JIS") }
}

/** 条目可调项（declaredSize = -1 表示用 payload 实际长度；method = 8 裸 deflate）。 */
internal class EntryTweak(
    val method: Int = 8,
    val declaredSize: Long = -1L,
    val flags: Int = 0,
    val rawName: ByteArray? = null,
    val externalAttrs: Long = 0L,
)

/**
 * 测试专用 zip 现打器（只活在测试源集）：中央目录/本地头按 zip 规范手拼，
 * 声明尺寸、标志、压缩法、名节字节、unix 属性都可注入——专测「容器撒谎」的闸面。
 * CRC 一律写 0：解压层不核 CRC（内容正确性由安装层的逐文件 sha256 复核兜住）。
 */
internal object FakeZipBuilder {

    /** 一条待打进容器的条目规格。 */
    internal class Spec(
        val rawName: ByteArray,
        val method: Int,
        val flags: Int,
        val data: ByteArray,
        val declaredSize: Long,
        val externalAttrs: Long,
    )

    internal fun entry(name: String, payload: ByteArray, tweak: EntryTweak = EntryTweak()): Spec {
        val declared = if (tweak.declaredSize < 0) payload.size.toLong() else tweak.declaredSize
        val data = if (tweak.method == 8) deflate(payload) else payload
        return Spec(
            tweak.rawName ?: name.toByteArray(Charsets.UTF_8),
            tweak.method,
            tweak.flags,
            data,
            declared,
            tweak.externalAttrs,
        )
    }

    /** zip64 哨兵：EOCD 条目数写 0xffff（主实现按 ZIP64 拒）。 */
    internal fun build(entries: List<Spec>, zip64Sentinel: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        var offset = 0L
        for (spec in entries) {
            writeCentral(central, spec, offset)
            offset += writeLocal(out, spec)
        }
        val centralBytes = central.toByteArray()
        out.write(centralBytes)
        val eocd = ByteArrayOutputStream()
        le32(eocd, EOCD_SIG)
        le16(eocd, 0)
        le16(eocd, 0)
        le16(eocd, if (zip64Sentinel) 0xffff else entries.size)
        le16(eocd, if (zip64Sentinel) 0xffff else entries.size)
        le32(eocd, centralBytes.size.toLong())
        le32(eocd, offset)
        le16(eocd, 0)
        out.write(eocd.toByteArray())
        return out.toByteArray()
    }

    /** 落一个本地头 + 数据，返回本条目总长。 */
    private fun writeLocal(out: ByteArrayOutputStream, spec: Spec): Long {
        val start = out.size()
        le32(out, LOCAL_SIG)
        le16(out, 20)
        le16(out, spec.flags)
        le16(out, spec.method)
        le16(out, 0)
        le16(out, 0)
        le32(out, 0)
        le32(out, spec.data.size.toLong())
        le32(out, spec.declaredSize)
        le16(out, spec.rawName.size)
        le16(out, 0)
        out.write(spec.rawName)
        out.write(spec.data)
        return (out.size() - start).toLong()
    }

    private fun writeCentral(out: ByteArrayOutputStream, spec: Spec, localOffset: Long) {
        le32(out, CENTRAL_SIG)
        le16(out, 20)
        le16(out, 20)
        le16(out, spec.flags)
        le16(out, spec.method)
        le16(out, 0)
        le16(out, 0)
        le32(out, 0)
        le32(out, spec.data.size.toLong())
        le32(out, spec.declaredSize)
        le16(out, spec.rawName.size)
        le16(out, 0)
        le16(out, 0)
        le16(out, 0)
        le16(out, 0)
        le32(out, spec.externalAttrs)
        le32(out, localOffset)
        out.write(spec.rawName)
    }

    private fun deflate(raw: ByteArray): ByteArray {
        // nowrap = true：zip 条目里存的是裸 deflate 流（无 zlib 头），与解压侧 Inflater(true) 对齐
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)
        val bos = ByteArrayOutputStream()
        DeflaterOutputStream(bos, deflater).use { it.write(raw) }
        return bos.toByteArray()
    }

    private fun le16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun le32(out: ByteArrayOutputStream, value: Long) {
        out.write((value and 0xFF).toInt())
        out.write(((value shr 8) and 0xFF).toInt())
        out.write(((value shr 16) and 0xFF).toInt())
        out.write(((value shr 24) and 0xFF).toInt())
    }

    private const val EOCD_SIG = 0x06054b50L
    private const val LOCAL_SIG = 0x04034b50L
    private const val CENTRAL_SIG = 0x02014b50L
}
