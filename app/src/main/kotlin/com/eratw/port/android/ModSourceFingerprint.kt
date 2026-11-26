package com.eratw.port.android

import com.eratw.port.platform.storage.ModStore
import java.io.File
import java.security.MessageDigest

/**
 * 已导入包的内容指纹侧车（`mods/rt2/<packageId>.sources.sha256`，Lane O）。
 *
 * ## 为什么需要它
 *
 * `.sources` 只记留档源**文件名**（线格式冻结，绝不往里塞哈希），所以
 * [ModRecompiler.isUpToDate] 原先只能拿源文件与 `.erbm`/`.manifest` 的 mtime 比大小。
 * mtime 是粗粒度事实：同一时间片内改内容、从 zip/备份解出、跨设备拷贝、恢复系统时间，
 * 都能让「内容已变」与「未变」不可区分 ⇒ 判 upToDate 跳过重编，玩家看到旧口上内容却
 * 拿不到新内容。指纹侧车把判据换成**内容摘要集合逐条相等**，mtime 只留作老包迁移期回退。
 *
 * ## 线格式（UTF-8；一行一条；`\n` 结尾）
 *
 * ```
 * RT2SOURCES/1
 * <64 位小写 hex>  <diskName>
 * ```
 *
 * - 首行头带版本号：换算法（SHA-512、加盐）时整份不认、退回 mtime，不做「一半旧一半新」
 *   的猜测；
 * - 每条摘要 = SHA-256(`diskName` UTF-8 + NUL + 源文件字节)。名字参与摘要 ⇒ 改名换 key；
 *   NUL 分隔保证「名 + 内容」不会与另一组拼出同一串；
 * - 条目按 [ModStore.compareNames] 排序（与 `.sources` 名单、packageId 同序）⇒ 同一源集合
 *   重导/重编写出逐字节可复现的侧车。
 *
 * ## 边界
 *
 * 本对象只做纯字节解析/摘要，不碰写盘：导入侧复用 [ModImportController] 落产物用的原子
 * 写（tmp + fsync + rename），重编侧复用 [ModRecompiler] 替换产物用的 tmp + fsync +
 * `ATOMIC_MOVE`，不另造一套写盘纪律。
 *
 * 侧车缺失 / 读失败 / 头或行不合形 ⇒ 一律当「无指纹」（[readSidecar] 返回 null），
 * 调用方退回 mtime 判据：老包不因为本波新增侧车而被判坏一次。
 */
internal object ModSourceFingerprint {

    /** 线格式头（含版本号）。 */
    const val HEADER: String = "RT2SOURCES/1"

    /** 摘要与文件名之间的列分隔（两个空格：文件名不含空格，可读且无歧义）。 */
    private const val COLUMN_SEPARATOR: String = "  "

    /** 单条记录的列数。 */
    private const val RECORD_COLUMNS: Int = 2

    /** 名字与内容之间的分隔字节（NUL，不是合法文件名/文本字符）。 */
    private const val FIELD_SEPARATOR: Byte = 0

    /** SHA-256 十六进制串长度。 */
    private const val SHA256_HEX_LENGTH: Int = 64

    /** 十六进制小写字符表（与 `ModImportController.packageIdFor` 同源口径）。 */
    private const val HEX_DIGITS: String = "0123456789abcdef"

    /** 每字节两位十六进制字符。 */
    private const val HEX_CHARS_PER_BYTE: Int = 2

    /** 单字节取数值的掩码。 */
    private const val BYTE_MASK: Int = 0xFF

    /** 低半字节掩码。 */
    private const val NIBBLE_MASK: Int = 0x0F

    /** 高半字节右移位数。 */
    private const val NIBBLE_BITS: Int = 4

    /**
     * 侧车字节：头 + 排序后的「摘要  名」行（[files] 按 `diskName` 排序）。
     *
     * 导入（`ModImportController.import` ⑤ 落盘）与重编（[ModRecompiler] 替换产物）
     * 共用这一处生成：同一源集合在任何路径写出的侧车逐字节相同。
     */
    fun bytesFor(files: List<PickedModFile>): ByteArray {
        val sorted = files.sortedWith { a, b -> ModStore.compareNames(a.diskName, b.diskName) }
        val text = StringBuilder(HEADER.length + 1)
        text.append(HEADER).append('\n')
        for (file in sorted) {
            text.append(digestOf(file.diskName, file.bytes))
                .append(COLUMN_SEPARATOR).append(file.diskName).append('\n')
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    /** 单条记录的摘要：`name` 的 UTF-8 字节 + NUL + `bytes`，SHA-256 小写十六进制。 */
    fun digestOf(name: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(name.toByteArray(Charsets.UTF_8))
        digest.update(FIELD_SEPARATOR)
        digest.update(bytes)
        return hexOf(digest.digest())
    }

    /**
     * 解析侧车字节：头不对 / 空表 / 任一行不合形 / 同名重复 ⇒ null（= 无指纹，回退 mtime）。
     *
     * 保守解析是刻意的：这文件唯一的消费者是「要不要重编」的判据，读不懂就当没有，
     * 绝不拿半懂的结果去判「内容一致」。
     */
    fun parse(raw: ByteArray): Map<String, String>? {
        val text = String(raw, Charsets.UTF_8)
        val headerLine = HEADER + "\n"
        val wellFormed = text.startsWith(headerLine) && text.endsWith("\n")
        val body = if (wellFormed) text.removePrefix(headerLine).removeSuffix("\n") else ""
        val records = LinkedHashMap<String, String>()
        return if (wellFormed && body.isNotEmpty() && parseRecords(body, records)) records else null
    }

    /** 读 `<rt2Dir>/<packageId>.sources.sha256`：缺失 / 读失败 / 坏格式 ⇒ null。 */
    fun readSidecar(rt2Dir: File, packageId: String): Map<String, String>? {
        val file = File(rt2Dir, packageId + ModPackageInventory.FINGERPRINT_EXTENSION)
        val raw = if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        return raw?.let { parse(it) }
    }

    /**
     * 盘上现源的摘要表；任一名单源缺失或为空（`.sources` 与 `mods/` 对不上）⇒ null。
     *
     * 口上源是文本小件（`ModStore.MAX_MOD_BYTES` 之内），逐份读进堆算摘要可接受；
     * 与 [ModRecompiler.rebuild] 读源走同一份字节口径。
     */
    fun currentIn(modsDir: File, sourceNames: List<String>): Map<String, String>? {
        val records = LinkedHashMap<String, String>()
        var complete = true
        for (name in sourceNames) {
            val file = File(modsDir, name)
            val bytes = if (file.isFile && file.length() > 0L) {
                runCatching { file.readBytes() }.getOrNull()
            } else {
                null
            }
            if (bytes == null || bytes.isEmpty()) {
                complete = false
            } else {
                records[name] = digestOf(name, bytes)
            }
        }
        return if (complete && records.size == sourceNames.size) records else null
    }

    /** 逐行解析记录体；任一行不合形或同名重复 ⇒ false（[parse] 据此整份不认）。 */
    private fun parseRecords(body: String, records: MutableMap<String, String>): Boolean {
        var clean = true
        for (line in body.split('\n')) {
            val fields = line.split(COLUMN_SEPARATOR, limit = RECORD_COLUMNS)
            if (!isRecord(fields)) {
                clean = false
                continue
            }
            if (records.put(fields[1], fields[0]) != null) clean = false
        }
        return clean
    }

    /** 一行记录的形状：两列、第一列是 64 位小写 hex、名字非空。 */
    private fun isRecord(fields: List<String>): Boolean =
        fields.size == RECORD_COLUMNS && isDigest(fields[0]) && fields[1].isNotEmpty()

    /** 64 位小写十六进制摘要形态。 */
    private fun isDigest(text: String): Boolean =
        text.length == SHA256_HEX_LENGTH && text.all { it in HEX_DIGITS }

    /** 摘要字节 → 小写十六进制（locale 无关，不走 `String.format`）。 */
    private fun hexOf(bytes: ByteArray): String {
        val hex = StringBuilder(bytes.size * HEX_CHARS_PER_BYTE)
        for (b in bytes) {
            val value = b.toInt() and BYTE_MASK
            hex.append(HEX_DIGITS[value ushr NIBBLE_BITS]).append(HEX_DIGITS[value and NIBBLE_MASK])
        }
        return hex.toString()
    }
}
