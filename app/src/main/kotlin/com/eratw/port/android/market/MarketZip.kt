package com.eratw.port.android.market

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * 市场容器的防穿越解压口（CONTRACT-MKM-W1 §2 mkm-pipe 判据 2）。
 *
 * 语义权威 = kojo-editor `packages/app/src-tauri/src/archive_guard.rs` 的中央目录
 * 预扫（`central_directory` :257 / `container_contract_problem` :498 / `entry_path_problem`
 * :430）与 `market.ts` 的路径闸 `packEntryPathProblem`（market.ts:660-690）。本实现把全部
 * 形态/限额闸放在**任何 inflate 之前**（中央目录预扫），解压期再加一道逐文件在飞闸：
 * 实际膨胀超出声明 = 容器撒谎 = 当场拒（照 archive_guard「拒在动盘之前」+ 逐块累计口径）。
 *
 * 条目名编码：UTF-8 为主、CP932 探测回落，照本仓 `ModImportController.kt` 的 `ModZip`
 * 先例（ModZip.kt:989 的「乱码名少者胜、平手取 UTF-8」；市场包默认 UTF-8，探测只作兼容回落）。
 *
 * 条目名路径闸的码映射（市场域封闭码集，见 [MarketRejectCodes]）：
 * - 穿越/误指向族（`..` 段、绝对路径、盘符、任意 `:`、反斜杠）= [MarketRejectCodes.PATH_TRAVERSAL]；
 * - 形状/用途族（空段、`.` 段、尾点/尾空格、NUL、深度超限、`files/` 外条目、重复条目）
 *   = [MarketRejectCodes.PATH_INVALID]；
 * - 头不可读 / 加密 / 数据描述符 / 契约外压缩法 / 非普通文件 = [MarketRejectCodes.EXTRACT_FAILED]；
 * - 条数超限 = TOO_MANY_ENTRIES；总量（声明或实际）超限 = UNPACKED_TOO_BIG。
 *
 * 纯 JVM（java.util.zip + java.nio），禁 Android API，定向单测可跑。
 */
object MarketZip {

    /** 解压拒绝（带 [MarketRejectCodes] 封闭码）。IOException 子类：调用方按 IO 失败兜底也能接住。 */
    class Reject(val code: String, detail: String) : IOException(detail)

    /** 解出的一个条目事实（中央目录顺序）：容器与清单双向对账用（照 manifest.ts ContainerFacts.entries）。 */
    class EntryFacts(val path: String, val bytes: Long)

    /** 解压结果：全部条目事实 + 实际总字节。 */
    class ExtractResult(val entries: List<EntryFacts>, val totalBytes: Long)

    /** 读缓冲（64 KiB）：流式膨胀不整块驻留，单文件再大也只占这一块。 */
    private const val COPY_BUF = 64 * 1024

    /** 膨胀输出缓冲的保底初始容量（条目声明 0 字节时也不开 0 长缓冲）。 */
    private const val MIN_OUT_HINT = 64L

    /** 报错详情里条目名的截断长度（取证够用即可）。 */
    private const val ERROR_NAME_MAX = 120

    /** DOS 属性高位里的 unix 文件类型段（archive_guard.rs:155 `S_IFMT`）。 */
    private const val UNIX_MODE_FMT = 0xF000

    /** unix 普通文件位（archive_guard.rs:156 `S_IFREG`）。 */
    private const val UNIX_MODE_REG = 0x8000

    /* ---- zip 结构偏移（字节段）；数字面量全部进常量（detekt MagicNumber 口径）。 ---- */

    /** EOCD：条目总数 / 中央目录尺寸 / 中央目录偏移 / 注释长 的字段偏移。 */
    private const val EOCD_ENTRIES_AT = 10
    private const val EOCD_CD_SIZE_AT = 12
    private const val EOCD_CD_OFFSET_AT = 16
    private const val EOCD_COMMENT_LEN_AT = 20

    /** 中央目录头：固定段长与各字段偏移（archive_guard.rs:168 `CENTRAL_HEADER`）。 */
    private const val CEN_HEADER_SIZE = 46
    private const val CEN_FLAGS_AT = 8
    private const val CEN_METHOD_AT = 10
    private const val CEN_COMPRESSED_AT = 20
    private const val CEN_SIZE_AT = 24
    private const val CEN_NAME_LEN_AT = 28
    private const val CEN_EXTRA_LEN_AT = 30
    private const val CEN_COMMENT_LEN_AT = 32
    private const val CEN_ATTRS_AT = 38
    private const val CEN_LOCAL_OFFSET_AT = 42

    /** 本地头：固定段长与各字段偏移（archive_guard.rs:167 `LOCAL_HEADER`；压缩/原尺寸在 18/22）。 */
    private const val LOC_HEADER_SIZE = 30
    private const val LOC_COMPRESSED_AT = 18
    private const val LOC_SIZE_AT = 22
    private const val LOC_NAME_LEN_AT = 26
    private const val LOC_EXTRA_LEN_AT = 28

    /** zip 中央目录条目签名（PK\x01\x02，archive_guard.rs:162）。 */
    private const val SIG_CENTRAL = 0x02014b50L

    /** zip 本地头签名（PK\x03\x04，archive_guard.rs:161）。 */
    private const val SIG_LOCAL = 0x04034b50L

    /** zip EOCD 签名（PK\x05\x06，archive_guard.rs:163）。 */
    private const val SIG_EOCD = 0x06054b50L

    /** EOCD 固定段长（无注释，archive_guard.rs:170）。 */
    private const val EOCD_SIZE = 22

    /** EOCD 注释长度上限（u16，archive_guard.rs:171）。 */
    private const val EOCD_COMMENT_MAX = 0xffff

    /** 压缩法：存储（archive_guard.rs:183）。 */
    private const val METHOD_STORE = 0

    /** 压缩法：deflate（archive_guard.rs:184）。 */
    private const val METHOD_DEFLATE = 8

    /** ZIP64 哨兵：EOCD 条目数段的 u16 满值。 */
    private const val ZIP64_SENTINEL_U16 = 0xffff

    /** ZIP64 哨兵：尺寸/偏移段的 u32 满值。 */
    private const val ZIP64_SENTINEL_U32 = 0xffffffffL

    /** zip 加密位（archive_guard.rs:188）。 */
    private const val FLAG_ENCRYPTED = 0x0007

    /** zip 强加密位（archive_guard.rs:189）。 */
    private const val FLAG_STRONG_ENCRYPTION = 0x0040

    /** zip 数据描述符位（头里两个尺寸为 0 的形态，archive_guard.rs:186）。 */
    private const val FLAG_DATA_DESCRIPTOR = 0x0008

    /** manifest 元数据文件名（root 级唯一合法的 `files/` 外条目）。 */
    private const val MANIFEST_NAME = MarketContract.MANIFEST_FILE

    /** 内容子目录前缀（其余顶层一律拒，CONTRACT §2.2）。 */
    private const val CONTENT_PREFIX = MarketContract.CONTENT_DIR + "/"

    /** 拒绝点统一出口：整个文件只此一处 throw（detekt ThrowsCount 口径，集中可读）。 */
    private fun reject(code: String, detail: String): Nothing = throw Reject(code, detail)

    /** 小端解码的字节掩码、字节位次与位移段（数字面量全部进常量）。 */
    private const val BYTE_MASK = 0xFF
    private const val BYTE_1 = 1
    private const val BYTE_2 = 2
    private const val BYTE_3 = 3
    private const val SHIFT_B1 = 8
    private const val SHIFT_B2 = 16
    private const val SHIFT_B3 = 24

    /** u16 小端。 */
    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and BYTE_MASK) or ((b[at + BYTE_1].toInt() and BYTE_MASK) shl SHIFT_B1)

    /** u32 小端（按无符号取值转 Long）。 */
    private fun le32(b: ByteArray, at: Int): Long =
        (b[at].toInt() and BYTE_MASK).toLong() or
            ((b[at + BYTE_1].toInt() and BYTE_MASK).toLong() shl SHIFT_B1) or
            ((b[at + BYTE_2].toInt() and BYTE_MASK).toLong() shl SHIFT_B2) or
            ((b[at + BYTE_3].toInt() and BYTE_MASK).toLong() shl SHIFT_B3)

    /**
     * 解压 [zipFile] 到 [outDir]（`pack.json` 加 `files/` 之下的全树落位）。
     * 全部闸先于第一个字节的写出；任一闸拒 = [Reject]，[outDir] 里可能留下半截
     * （调用方在 finally 里整棵清掉，照 service.ts installFromIndex 的 finally 口径）。
     */
    fun extract(zipFile: File, outDir: File): ExtractResult {
        if (zipFile.length() > MarketContract.MAX_ZIP_BYTES) {
            reject(MarketRejectCodes.ZIP_TOO_BIG, "容器 ${zipFile.length()} 字节超过上限 ${MarketContract.MAX_ZIP_BYTES}")
        }
        val zip = zipFile.readBytes()
        val entries = CentralDirs.parse(zip)
        val charset = NameCharsets.probe(entries.map { it.nameBytes })
        EntryGate.checkAll(entries, charset)
        return Extractor.run(zip, entries, charset, outDir)
    }

    /** 中央目录的一个原始条目（字段从原始字节惰性取——名节未解码，探测在收齐后统一做一次）。 */
    internal class CentralEntry(zip: ByteArray, at: Int) {
        private val at = at

        val nameBytes: ByteArray =
            zip.copyOfRange(at + CEN_HEADER_SIZE, at + CEN_HEADER_SIZE + le16(zip, at + CEN_NAME_LEN_AT))
        val flags: Int = le16(zip, at + CEN_FLAGS_AT)
        val method: Int = le16(zip, at + CEN_METHOD_AT)
        val compressedSize: Long = le32(zip, at + CEN_COMPRESSED_AT)
        val size: Long = le32(zip, at + CEN_SIZE_AT)
        val externalAttrs: Long = le32(zip, at + CEN_ATTRS_AT)
        val localOffset: Long = le32(zip, at + CEN_LOCAL_OFFSET_AT)

        /** 按探测择优的 charset 解出条目名。 */
        fun decoded(charset: Charset): String = String(nameBytes, charset)
    }

    /** EOCD 定位 + 中央目录解析（照 archive_guard.rs `central_directory` 的最小字段面）。 */
    private object CentralDirs {

        fun parse(zip: ByteArray): List<CentralEntry> {
            val eocd = locateEocd(zip)
            if (eocd.declaredEntries == ZIP64_SENTINEL_U16 ||
                eocd.cdOffset == ZIP64_SENTINEL_U32 ||
                eocd.cdSize == ZIP64_SENTINEL_U32
            ) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "容器使用 ZIP64，解包器不接受（照 archive_guard container-zip64）")
            }
            val list = ArrayList<CentralEntry>(eocd.declaredEntries)
            var pos = eocd.cdOffset
            repeat(eocd.declaredEntries) { index ->
                if (pos + CEN_HEADER_SIZE > zip.size || le32(zip, pos.toInt()) != SIG_CENTRAL) {
                    reject(MarketRejectCodes.EXTRACT_FAILED, "中央目录条目 $index 处不可读（截断或签名不符）")
                }
                val at = pos.toInt()
                val nameLen = le16(zip, at + CEN_NAME_LEN_AT)
                val extraLen = le16(zip, at + CEN_EXTRA_LEN_AT)
                val commentLen = le16(zip, at + CEN_COMMENT_LEN_AT)
                if (at + CEN_HEADER_SIZE + nameLen > zip.size) {
                    reject(MarketRejectCodes.EXTRACT_FAILED, "中央目录条目 $index 名字段越界")
                }
                list += CentralEntry(zip, at)
                pos += CEN_HEADER_SIZE + nameLen + extraLen + commentLen
            }
            return list
        }

        /** 从尾部扫描 EOCD：最后一段能自洽（注释长对得上总长）的签名位才算。 */
        private fun locateEocd(zip: ByteArray): Eocd {
            val scanFloor = maxOf(0, zip.size - EOCD_SIZE - EOCD_COMMENT_MAX)
            var at = zip.size - EOCD_SIZE
            while (at >= scanFloor) {
                if (le32(zip, at) == SIG_EOCD) {
                    val commentLen = le16(zip, at + EOCD_COMMENT_LEN_AT)
                    if (at + EOCD_SIZE + commentLen == zip.size) {
                        return Eocd(
                            declaredEntries = le16(zip, at + EOCD_ENTRIES_AT),
                            cdSize = le32(zip, at + EOCD_CD_SIZE_AT),
                            cdOffset = le32(zip, at + EOCD_CD_OFFSET_AT),
                        )
                    }
                }
                at--
            }
            reject(MarketRejectCodes.EXTRACT_FAILED, "EOCD 定位失败：容器不是可读的 zip")
        }

        private class Eocd(val declaredEntries: Int, val cdSize: Long, val cdOffset: Long)
    }

    /** 条目名编码探测（照 ModZip.kt:989 乱码名少者胜，平手取 UTF-8）。 */
    private object NameCharsets {

        /** 日文回落编码 = CP932（`windows-31j`）；缺字符集退 `Shift_JIS` 近似（ModSourceCodec 同款）。 */
        private val CP932: Charset = runCatching { Charset.forName("windows-31j") }
            .getOrElse { Charset.forName("Shift_JIS") }

        fun probe(nameBytesList: List<ByteArray>): Charset =
            if (mojibakeCount(nameBytesList, Charsets.UTF_8) <= mojibakeCount(nameBytesList, CP932)) {
                Charsets.UTF_8
            } else {
                CP932
            }

        /** 名字含 U+FFFD = 该 charset 解错了条目名。 */
        private fun mojibakeCount(nameBytesList: List<ByteArray>, charset: Charset): Int =
            nameBytesList.count { String(it, charset).indexOf('\uFFFD') >= 0 }
    }

    /** 预扫闸（零 inflate）：路径/类型/标志/限额全部在这里拒。 */
    private object EntryGate {

        fun checkAll(entries: List<CentralEntry>, charset: Charset) {
            if (entries.size > MarketContract.MAX_FILE_ENTRIES) {
                reject(
                    MarketRejectCodes.TOO_MANY_ENTRIES,
                    "条目数 ${entries.size} 超过上限 ${MarketContract.MAX_FILE_ENTRIES}",
                )
            }
            var totalDeclared = 0L
            val seen = HashSet<String>()
            for (entry in entries) {
                checkType(entry)
                val name = entry.decoded(charset)
                pathProblem(name)?.let { reject(it.first, "${it.second}（条目 ${name.take(ERROR_NAME_MAX)}）") }
                if (entry.size > MarketContract.MAX_UNPACKED_BYTES) {
                    reject(
                        MarketRejectCodes.UNPACKED_TOO_BIG,
                        "单条目声明 ${entry.size} 字节超过上限 ${MarketContract.MAX_UNPACKED_BYTES}",
                    )
                }
                totalDeclared += entry.size
                if (totalDeclared > MarketContract.MAX_UNPACKED_BYTES) {
                    reject(
                        MarketRejectCodes.UNPACKED_TOO_BIG,
                        "声明解压总量 $totalDeclared 超过上限 ${MarketContract.MAX_UNPACKED_BYTES}",
                    )
                }
                if (!seen.add(name.lowercase())) {
                    reject(MarketRejectCodes.PATH_INVALID, "条目路径重复：$name")
                }
            }
        }

        /** 类型/标志/压缩法闸（archive_guard.rs:676 `entry_type_problem` + :536-562 逐条）。 */
        private fun checkType(entry: CentralEntry) {
            val mode = ((entry.externalAttrs shr SHIFT_B2).toInt() and UNIX_MODE_FMT)
            if (entry.flags and FLAG_ENCRYPTED != 0 || entry.flags and FLAG_STRONG_ENCRYPTION != 0) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目加密，解包器不接受")
            }
            if (entry.flags and FLAG_DATA_DESCRIPTOR != 0) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目用数据描述符携带尺寸（头里两个尺寸为 0），有界膨胀无从谈起")
            }
            if (entry.method != METHOD_STORE && entry.method != METHOD_DEFLATE) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目用了契约外的压缩法 ${entry.method}")
            }
            if (mode != 0 && mode != UNIX_MODE_REG) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目不是普通文件（unix 模式位 $mode），符号链接等一律拒")
            }
        }

        /**
         * 条目名问题（null = 放行）。码序照 archive_guard.rs:430 `entry_path_problem`：
         * NUL 先于形态判定（它不是路径段问题，是根本无法成为文件名）。
         * 返回 (码, 人话)；穿越族给 PATH_TRAVERSAL，形状族给 PATH_INVALID。
         */
        fun pathProblem(name: String): Pair<String, String>? = when {
            name.contains('\u0000') -> MarketRejectCodes.PATH_INVALID to "条目名含 NUL，无法成为文件名"
            else -> traversalProblem(name) ?: shapeProblem(name) ?: topLevelProblem(name)
        }

        /** 穿越/误指向族（CONTRACT §2.2 判据：上跳/绝对/盘符/反斜杠 = PATH_TRAVERSAL）。 */
        private fun traversalProblem(name: String): Pair<String, String>? = when {
            name.split('/').contains("..") -> MarketRejectCodes.PATH_TRAVERSAL to "条目路径含 .. 段（上跳穿越）"
            name.startsWith("/") -> MarketRejectCodes.PATH_TRAVERSAL to "条目路径是绝对路径"
            name.length >= DRIVE_PREFIX_LEN && name[0].isAsciiLetter() && name[1] == ':' ->
                MarketRejectCodes.PATH_TRAVERSAL to "条目路径带盘符"
            name.contains(':') -> MarketRejectCodes.PATH_TRAVERSAL to "条目路径带盘符位之外的冒号（ADS 注入面）"
            name.contains('\\') -> MarketRejectCodes.PATH_TRAVERSAL to "条目路径含反斜杠"
            else -> null
        }

        /** 形状族（照 market.ts:660-690 `packEntryPathProblem` 的空段/点段/尾点/尾空格/深度）。 */
        private fun shapeProblem(name: String): Pair<String, String>? {
            val segments = name.split('/')
            return when {
                name.isEmpty() -> MarketRejectCodes.PATH_INVALID to "条目名为空"
                segments.any { it.isEmpty() || it == "." } -> MarketRejectCodes.PATH_INVALID to "条目路径含空段或 . 段"
                segments.any { it.endsWith(".") || it.endsWith(" ") } ->
                    MarketRejectCodes.PATH_INVALID to "条目路径有段尾随点或空格（Windows 落盘会静默剥掉）"
                segments.size > MarketContract.MAX_PATH_DEPTH ->
                    MarketRejectCodes.PATH_INVALID to "条目路径深度 ${segments.size} 超过上限 ${MarketContract.MAX_PATH_DEPTH}"
                else -> null
            }
        }

        /** 顶层闸：zip 根只允许 `pack.json` 与 `files/` 之下的条目（CONTRACT §2.2）。 */
        private fun topLevelProblem(name: String): Pair<String, String>? = when {
            name == MANIFEST_NAME -> null
            name.startsWith(CONTENT_PREFIX) && name.length > CONTENT_PREFIX.length -> null
            else -> MarketRejectCodes.PATH_INVALID to "条目不在 zip 根合同内（只允许 pack.json 与 files/ 之下）：$name"
        }

        private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
    }

    /** 盘符形态的最短长度（`X:`）。 */
    private const val DRIVE_PREFIX_LEN = 2

    /** 解压执行（预扫全过后才碰数据区；逐文件在飞闸在这里）。 */
    private object Extractor {

        fun run(zip: ByteArray, entries: List<CentralEntry>, charset: Charset, outDir: File): ExtractResult {
            var total = 0L
            val facts = ArrayList<EntryFacts>(entries.size)
            for (entry in entries) {
                val name = entry.decoded(charset)
                val out = File(outDir, name)
                out.parentFile?.mkdirs()
                val written = writeOne(zip, entry, name, out, total)
                total += written
                facts += EntryFacts(name, written)
            }
            return ExtractResult(facts, total)
        }

        /** 单条目落盘：声明量与实际量对不上 = 容器撒谎（UNPACKED_TOO_BIG / EXTRACT_FAILED）。 */
        private fun writeOne(zip: ByteArray, entry: CentralEntry, name: String, out: File, cumulative: Long): Long {
            val header = LocalHeader.at(zip, entry, name)
            if (header.dataEnd > zip.size) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 的数据区越界（容器截断或谎报尺寸）")
            }
            if (entry.size > MarketContract.MAX_UNPACKED_BYTES - cumulative) {
                reject(
                    MarketRejectCodes.UNPACKED_TOO_BIG,
                    "条目 $name 声明 ${entry.size} 字节，累计将超过解压上限 ${MarketContract.MAX_UNPACKED_BYTES}",
                )
            }
            val bytes = if (entry.method == METHOD_STORE) {
                if (entry.compressedSize != entry.size) {
                    reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 声明为存储法但两段尺寸不等")
                }
                zip.copyOfRange(header.dataStart, header.dataStart + entry.size.toInt())
            } else {
                inflate(zip, header.dataStart, entry.compressedSize.toInt(), entry.size, name)
            }
            out.writeBytes(bytes)
            return bytes.size.toLong()
        }

        /**
         * 有界 deflate：只喂声明的那一段压缩字节、只收声明以内的输出；
         * 流提前耗尽（谎报大尺寸）或产出超出声明（炸弹）都当场拒。
         */
        private fun inflate(zip: ByteArray, dataStart: Int, dataLen: Int, declared: Long, name: String): ByteArray {
            val inflater = Inflater(true)
            inflater.setInput(zip, dataStart, dataLen)
            try {
                val bytes = drain(inflater, declared, name)
                if (bytes.size.toLong() != declared) {
                    reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 实解 ${bytes.size} 字节与声明 $declared 不符")
                }
                return bytes
            } catch (e: DataFormatException) {
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 的 deflate 流损坏：${e.message}")
            } finally {
                inflater.end()
            }
        }

        /** 单条目膨胀主循环（产出超声明 = 炸弹；流提前收 = 谎报，当场拒）。 */
        private fun drain(inflater: Inflater, declared: Long, name: String): ByteArray {
            val out = ByteArrayOutputStream(maxOf(declared, MIN_OUT_HINT).toInt())
            val buf = ByteArray(COPY_BUF)
            var done = false
            while (!done) {
                val n = inflater.inflate(buf)
                if (n > 0) absorb(out, buf, n, declared, name) else done = streamSettled(inflater, declared, name)
            }
            return out.toByteArray()
        }

        /** 收一段输出并核在飞上限。 */
        private fun absorb(out: ByteArrayOutputStream, buf: ByteArray, n: Int, declared: Long, name: String) {
            if (out.size() + n > declared) {
                reject(
                    MarketRejectCodes.UNPACKED_TOO_BIG,
                    "条目 $name 实际膨胀超出声明的 $declared 字节（压缩炸弹闸）",
                )
            }
            out.write(buf, 0, n)
        }

        /** inflate 返回 0 时的停流判定：真到流尾 = 收工；提前要输入/字典 = 谎报拒。 */
        private fun streamSettled(inflater: Inflater, declared: Long, name: String): Boolean = when {
            inflater.finished() -> true
            inflater.needsInput() || inflater.needsDictionary() ->
                reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 压缩数据提前耗尽，解不出声明的 $declared 字节")
            else -> reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 停流但流未收尾（解包器语义外形态）")
        }

        /** 本地头互指核（archive_guard.rs `local_matches_central`：名区/法/尺寸与中央目录一致）。 */
        private class LocalHeader private constructor(val dataStart: Int, val dataEnd: Int) {
            companion object {
                fun at(zip: ByteArray, entry: CentralEntry, name: String): LocalHeader {
                    if (entry.localOffset > zip.size) {
                        reject(MarketRejectCodes.EXTRACT_FAILED, "条目 $name 本地头偏移越界")
                    }
                    val at = entry.localOffset.toInt()
                    if (at + LOC_HEADER_SIZE > zip.size || le32(zip, at) != SIG_LOCAL) {
                        reject(MarketRejectCodes.EXTRACT_FAILED, "本地头不可读（偏移 $at）")
                    }
                    val nameLen = le16(zip, at + LOC_NAME_LEN_AT)
                    val extraLen = le16(zip, at + LOC_EXTRA_LEN_AT)
                    if (entry.compressedSize != le32(zip, at + LOC_COMPRESSED_AT) ||
                        entry.size != le32(zip, at + LOC_SIZE_AT)
                    ) {
                        reject(MarketRejectCodes.EXTRACT_FAILED, "本地头与中央目录尺寸不互指")
                    }
                    val dataStart = at + LOC_HEADER_SIZE + nameLen + extraLen
                    return LocalHeader(dataStart, dataStart + entry.compressedSize.toInt())
                }
            }
        }
    }
}
