package com.eratw.port.android.market

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 一条「已装进游戏」的导入台账记录（CONTRACT-MKM-W2 §0.3）。
 *
 * 键 = `(kind, id)`（市场包的种类与 id，与 [InstalledPack] 的键面同名同义）；其余字段是
 * 该键**最新一次**导入的事实：当时的包版本、派生出的 rt2 包身份、导入时刻。同键再次
 * 导入 = 追加新行盖掉旧行（append 序即时间序，取末条）。
 */
data class MarketImportRecord(
    val kind: String,
    val id: String,
    val version: String,
    val packageId: String,
    val importedAtMs: Long,
)

/**
 * 「市场已装包 → 装进游戏」的导入台账（CONTRACT-MKM-W2 §0.3，桥独占文件）。
 *
 * 文件 = `<filesDir>/market/imports.jsonl`（[FILE_NAME]），一行一条 JSON 对象；键 =
 * `(kind, id)`，每键最新一条生效。写路径 = **append 为主、按需整文件 compact**：死行
 * （同键被更新的旧行）积到 [compactAfterDeadLines] 时整文件重写为每键最新一条，走
 * 「同目录临时文件 + nio REPLACE_EXISTING 原子挪」（与 MarketStore 的 installed.journal
 * 同一原子形态；Windows 的 `File.renameTo` 不覆盖已存在目标，故统一走 nio）。失败路径
 * 一律清掉临时文件，台账要么是旧的完整版、要么是新的完整版，不存在半截文件。
 *
 * 读路径 = 整读逐行解：空行与坏行（不是 JSON 对象 / 缺键 / 多键 / 键值形态破坏）整行
 * 跳过、不炸——台账是本类自己写出的，坏行只可能来自外部改动或写侧历史版本，跳过即自愈。
 * 文件不存在 = 空台账（首次导入前没有这个文件是正常态，不是错误）。
 *
 * 纯 JVM（java.io / java.nio.file），禁 Android API；线程口径：桥把全部动作阻塞在
 * 调用线程上（CONTRACT §0.5），本类自身不加锁。
 *
 * @param file 台账文件本体（生产 = `marketRoot/imports.jsonl`；测试随临时目录给）。
 * @param compactAfterDeadLines 死行数达到该值即整文件 compact 重写；只由测试调小，
 *   生产恒用缺省值。
 */
class MarketImportsLedger(
    private val file: File,
    private val compactAfterDeadLines: Int = DEFAULT_COMPACT_AFTER_DEAD_LINES,
) {

    /** 取一个键的最新记录；键从未导入过或台账为空 = null。 */
    fun lookup(kind: String, id: String): MarketImportRecord? =
        readAll().lastOrNull { it.kind == kind && it.id == id }

    /** 全部可解析记录（append 序；同键多条 = 追加历史，消费方一律取末条）。 */
    fun all(): List<MarketImportRecord> = readAll()

    /**
     * 记（或更新）一个键的最新事实：死行未到闸 = 追加一行；到了闸 = 整文件重写为每键
     * 最新一条（本次记录在内）。两种路径都保证「函数正常返回 = 记录已在盘上」。
     *
     * @throws IOException 目录建不出 / 追加失败 / compact 失败（消息点名文件）。
     */
    fun upsert(record: MarketImportRecord) {
        val records = readAll()
        val deadBefore = records.size - records.map { it.kind to it.id }.distinct().size
        val keyExists = records.any { it.kind == record.kind && it.id == record.id }
        val deadAfter = deadBefore + if (keyExists) 1 else 0
        if (deadAfter >= compactAfterDeadLines) {
            writeAtomically(records + record)
        } else {
            appendLine(record)
        }
    }

    /**
     * 删一个键的全部历史行（CONTRACT-MKM-W4-UNLINK §2.4）：把台账原子重写为去掉该键后的
     * 剩余记录（复用 [writeAtomically] 的 tmp + nio 原子挪设施，沿 compact 口径每键留最新
     * 一条，其它键的历史死行顺带收编，读路径跳过的坏行也一并自愈掉）。空台账（文件不在位 /
     * 无可解析记录）或键不存在 = 零动作 no-op：不建文件、不重写、盘上字节零改动。删到空 =
     * 重写为空文件（合法空台账形态，后续 [upsert] 照常追加）。
     *
     * @throws IOException tmp 写入或原子挪失败（消息点名文件；台账要么是旧的完整版、要么是
     *   新的完整版，不存在半截文件 —— 与 compact 同一失败语义）。
     */
    fun remove(kind: String, id: String) {
        val records = readAll()
        if (records.isEmpty()) return
        val remaining = records.filterNot { it.kind == kind && it.id == id }
        if (remaining.size == records.size) return
        writeAtomically(remaining)
    }

    // ------------------------------------------------------------------
    // 读写主体
    // ------------------------------------------------------------------

    /** 整读 + 逐行解；坏行 / 空行整行跳过。文件不在位 = 空表。 */
    private fun readAll(): List<MarketImportRecord> {
        if (!file.isFile) return emptyList()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("导入台账读不出来：${file.path}（${e.message}）", e)
        }
        return text.lineSequence().mapNotNull { LedgerJson.decodeOrNull(it) }.toList()
    }

    /** 追加一行（台账的主写路径；父目录就地建齐）。 */
    private fun appendLine(record: MarketImportRecord) {
        ensureParent()
        try {
            file.appendText(LedgerJson.encode(record) + "\n", Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("导入台账写不进去：${file.path}（${e.message}）", e)
        }
    }

    /** 整文件 compact 重写：每键取最后一条（append 序里最后出现的那条），tmp + 原子挪。 */
    private fun writeAtomically(records: List<MarketImportRecord>) {
        ensureParent()
        val latest = LinkedHashMap<Pair<String, String>, MarketImportRecord>(records.size * 2)
        for (record in records) latest[record.kind to record.id] = record
        val tmp = File(file.parentFile, file.name + "." + System.nanoTime() + TMP_SUFFIX)
        var moved = false
        try {
            tmp.writeText(
                latest.values.joinToString(separator = "") { LedgerJson.encode(it) + "\n" },
                Charsets.UTF_8,
            )
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            moved = true
        } catch (e: IOException) {
            throw IOException("导入台账 compact 重写失败：${file.path}（${e.message}）", e)
        } finally {
            // 失败路径（含非 IO 异常）一律清临时文件；成功后 tmp 已被挪走，delete 是空操作。
            if (!moved) tmp.delete()
        }
    }

    private fun ensureParent() {
        val parent = file.parentFile ?: return
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建台账目录：${parent.absolutePath}")
        }
    }

    companion object {
        /** 台账文件名（挂在 market 根下：`<filesDir>/market/imports.jsonl`）。 */
        const val FILE_NAME = "imports.jsonl"

        /** 生产 compact 闸：64 条死行才整文件重写（单行百字节级，量级余量充足）。 */
        const val DEFAULT_COMPACT_AFTER_DEAD_LINES = 64

        /** 临时文件后缀（带 nanoTime 防并发互踩；compact 结束后不存在残壳）。 */
        private const val TMP_SUFFIX = ".tmp"
    }
}

/**
 * 台账行的 JSON 编解码（本文件私有；写侧与读侧同一对象，禁两边口径漂移）。
 *
 * 形状冻结为五键扁平对象：kind / id / version / packageId 四键字符串、importedAtMs
 * 整数；decode 拒绝重复键、缺键、多余键与非数字裸 token——宁判坏行跳过，不猜写侧意思。
 * 字符串转义照 JSON 最小集（反斜杠、引号、换行/回车/制表 + 其余控制字符走 `\uXXXX`）：
 * 键面值都是受控字符集，转义几乎不触发，但台账必须对任意输入不腐蚀。
 */
private object LedgerJson {

    private const val KIND_KEY = "kind"
    private const val ID_KEY = "id"
    private const val VERSION_KEY = "version"
    private const val PACKAGE_ID_KEY = "packageId"
    private const val IMPORTED_AT_KEY = "importedAtMs"

    private const val FIELD_COUNT = 5

    /** 编码一行（不含行尾换行）。字段序固定，读侧不限序。 */
    fun encode(record: MarketImportRecord): String = buildString {
        append('{')
        append('"').append(KIND_KEY).append("\":").append(quoted(record.kind)).append(',')
        append('"').append(ID_KEY).append("\":").append(quoted(record.id)).append(',')
        append('"').append(VERSION_KEY).append("\":").append(quoted(record.version)).append(',')
        append('"').append(PACKAGE_ID_KEY).append("\":").append(quoted(record.packageId)).append(',')
        append('"').append(IMPORTED_AT_KEY).append("\":").append(record.importedAtMs)
        append('}')
    }

    /** 解码一行；空行 / 坏行 / 缺键 / 多键 / 形态破坏 = null（调用方整行跳过）。 */
    fun decodeOrNull(line: String): MarketImportRecord? {
        val text = line.trim()
        if (!text.startsWith("{") || !text.endsWith("}")) return null
        val fields = parseFields(text.substring(1, text.length - 1)) ?: return null
        if (fields.size != FIELD_COUNT) return null
        for (key in ALL_KEYS) {
            if (!fields.containsKey(key)) return null
        }
        val importedAtMs = fields[IMPORTED_AT_KEY]?.toLongOrNull() ?: return null
        return MarketImportRecord(
            kind = fields.getValue(KIND_KEY),
            id = fields.getValue(ID_KEY),
            version = fields.getValue(VERSION_KEY),
            packageId = fields.getValue(PACKAGE_ID_KEY),
            importedAtMs = importedAtMs,
        )
    }

    /**
     * 解析对象体里的 `key:value` 对（值 = 字符串或裸数字 token）；重复键 / 键值形态
     * 破坏 = null。裸 token 只认 JSON 数字形状（写侧只在 importedAtMs 上出裸 token）。
     */
    private fun parseFields(body: String): Map<String, String>? {
        val fields = HashMap<String, String>(FIELD_COUNT * 2)
        var i = 0
        while (i < body.length) {
            if (body[i] != '"') return null
            val key = scanString(body, i) ?: return null
            i = key.second
            if (i >= body.length || body[i] != ':') return null
            i++
            val value = if (i < body.length && body[i] == '"') {
                scanString(body, i) ?: return null
            } else {
                scanBareNumber(body, i) ?: return null
            }
            if (fields.put(key.first, value.first) != null) return null
            i = value.second
            if (i >= body.length) break
            if (body[i] != ',') return null
            i++
        }
        return fields
    }

    /** 从 start 起扫一个 JSON 字符串字面量；返回（值，下一位置）。未闭合 / 非法转义 = null。 */
    private fun scanString(text: String, start: Int): Pair<String, Int>? {
        var i = start + 1
        val out = StringBuilder()
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return out.toString() to (i + 1)
                '\\' -> {
                    val consumed = appendEscape(text, i, out) ?: return null
                    i += consumed
                }
                else -> {
                    if (c.code < CONTROL_CHAR_LIMIT) return null
                    out.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** 处理 start 处的反斜杠转义，把解码字符写入 out；返回消费长度，非法转义 = null。 */
    private fun appendEscape(text: String, start: Int, out: StringBuilder): Int? {
        val esc = text.getOrNull(start + 1) ?: return null
        return when (esc) {
            '"' -> {
                out.append('"'); 2
            }
            '\\' -> {
                out.append('\\'); 2
            }
            '/' -> {
                out.append('/'); 2
            }
            'b' -> {
                out.append('\b'); 2
            }
            'f' -> {
                out.append('\u000C'); 2
            }
            'n' -> {
                out.append('\n'); 2
            }
            'r' -> {
                out.append('\r'); 2
            }
            't' -> {
                out.append('\t'); 2
            }
            'u' -> {
                if (start + UNICODE_ESCAPE_LENGTH > text.length) return null
                val code = text
                    .substring(start + 2, start + UNICODE_ESCAPE_LENGTH)
                    .toIntOrNull(HEX_RADIX) ?: return null
                out.append(code.toChar())
                UNICODE_ESCAPE_LENGTH
            }
            else -> null
        }
    }

    /**
     * 从 start 起扫一个裸 token（到逗号或对象尾）；返回（原文，下一位置）。只认 JSON
     * 数字形状（其余裸 token 一律 null——写侧只在 importedAtMs 上出裸 token）。
     */
    private fun scanBareNumber(text: String, start: Int): Pair<String, Int>? {
        var i = start
        while (i < text.length && text[i] != ',') i++
        val raw = text.substring(start, i).trim()
        if (raw.isEmpty() || !raw.isNumberShape()) return null
        return raw to i
    }

    /** JSON 数字形状闸：只含数字与小数点/指数/符号，且至少一位数字、不以点起。 */
    private fun String.isNumberShape(): Boolean =
        isNotEmpty() &&
            all { it in NUMBER_SHAPE_CHARS } &&
            first().isDigit() &&
            any { it.isDigit() }

    /** JSON 数字 token 允许的字符全集（写侧只出十进制整数，闸宽于此以容历史形态）。 */
    private const val NUMBER_SHAPE_CHARS = "0123456789.eE+-"

    /** 控制字符上限（JSON 正文禁裸控制字符，一律走转义）。 */
    private const val CONTROL_CHAR_LIMIT = 0x20

    /** `\uXXXX` 转义的全文消费长度（反斜杠 + u + 四位十六进制）。 */
    private const val UNICODE_ESCAPE_LENGTH = 6

    /** `\uXXXX` 的四位十六进制位数。 */
    private const val HEX_PAD_WIDTH = 4

    /** 十六进制基数（`\uXXXX` 的位段解析与编码共用）。 */
    private const val HEX_RADIX = 16

    /** JSON 字符串字面量编码（最小转义集；控制字符一律走四位十六进制转义）。 */
    private fun quoted(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (c in raw) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else ->
                if (c.code < CONTROL_CHAR_LIMIT) {
                    append("\\u").append(c.code.toString(HEX_RADIX).padStart(HEX_PAD_WIDTH, '0'))
                } else {
                    append(c)
                }
        }
        append('"')
    }

    private val ALL_KEYS = listOf(KIND_KEY, ID_KEY, VERSION_KEY, PACKAGE_ID_KEY, IMPORTED_AT_KEY)
}
