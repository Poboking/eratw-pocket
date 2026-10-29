package com.eratw.port.platform.input

import com.eratw.port.render.input.KeyMacroTable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * `macro.txt` 的读盘装配（`KeyMacro.cs:31` 的路径、`:89-137` 的 `LoadMacroFile` 正文）。
 *
 * 本对象只把「文件 → 行序列 → [KeyMacroTable]」接起来，宏行/组行的判定规则全部留给
 * [KeyMacroTable.loadLines]（原样照抄 `KeyMacro.cs:99-133`，含长度门槛与怪癖）。
 *
 * ## 解码口径
 * 照 `EncodingHandler.DetectEncoding`（`EncodingHandler.cs:15-41`）与 `GetEncoding(932)`（`:11`、`:67-71`）：
 * 1. 前 3 字节是 UTF-8 BOM（`EF BB BF`）⇒ 剥 BOM 后按严格 UTF-8 解；解不动返回 null。
 *    BOM 已经把编码钉在 `UTF8BOMEncoding`（`EncodingHandler.cs:13`），坏字节让
 *    `EraStreamReader.Open` 的 `File.ReadAllLines` 抛异常、`catch` 里返回 false
 *    （`EraStreamReader.cs:50-59`）⇒ `LoadMacroFile` 直接 return（`KeyMacro.cs:92-95`），
 *    **不会**再回落日文编码。
 * 2. 前 2 字节是 UTF-16 BOM（`FF FE` 小端 / `FE FF` 大端）⇒ 剥 BOM 后按 UTF-16 解。
 *    原版第二步 `new StreamReader(filePath, UTF8Encoding)`（`EncodingHandler.cs:27`）默认
 *    `detectEncodingFromByteOrderMarks: true`，碰到 UTF-16 BOM 会把 `CurrentEncoding`
 *    换成 `Encoding.Unicode` / `BigEndianUnicode`，`:29-31` 随即把它返回；
 *    `File.ReadAllLines(filepath, 该编码)`（`EraStreamReader.cs:52`）再剥一次 BOM。
 *    这条分支的编码对象是 .NET 的默认 Unicode（不可解字节走替换回退，不是
 *    `EncodingHandler.cs:9`/`:13` 那两个 `throwOnInvalidBytes: true` 的自建实例），
 *    所以这里用 `String(bytes, offset, len, charset)` 的非严格解码，
 *    而不是 UTF-8 / CP932 那种严格模式。
 *    TODO(verify)：原版的 `StreamReader` 还会认 UTF-32 BOM（`FF FE 00 00` ⇒ `Encoding.UTF32`，
 *    判序在 UTF-16LE 之前）。本层不建模：JVM 侧 UTF-32 只有部分实现提供（本机 JDK 17 实测
 *    `Charset.isSupported("UTF-32")` 为 true，但 minSdk 21 的 ART 没有取证条件），而原版
 *    `SaveMacro` 只会写 `Config.Encode`（`KeyMacro.cs:68`），UTF-32 的 `macro.txt` 只能来自
 *    第三方编辑器。卡点：需要一台真机上「UTF-32 存盘的 macro.txt 在 PC 与安卓各读出什么」。
 * 3. 否则先按严格 UTF-8 试解，能解就用 UTF-8（`EncodingHandler.cs:27-35`）；
 * 4. 解不动回落 **CP932（`windows-31j`）**，不是 JDK 的 `Shift_JIS`：原版 `GetEncoding(932)`
 *    是 CP932（`EncodingHandler.cs:67-71`），两者在 NEC/IBM 扩展行与 0x815C 上分岔
 *    （JDK `Shift_JIS`：0x8740 ⇒ U+FFFD、0x815C ⇒ U+2014；CP932：U+2460、U+2015）。
 *    仓内同口径见 `core/expr/FormStringTerm.kt:429` 的 `LangCodec.JAPANESE`。
 *    仍解不动同样返回 null —— 原版用 `DecoderFallback.ExceptionFallback`（`:70`），
 *    `Open` 失败后静默不装载。
 *
 * 返回 null 的三种情况（文件不存在 / 读失败 / 解码失败）都对应原版「打不开文件」：
 * `LoadMacroFile` 静默返回，120 个槽保持 `ResetNames` 的清空态（`KeyMacro.cs:46`、`:92-95`）。
 * `LoadMacroFile` 的整段 `catch { }` 也吞掉一切异常（`:135-137`）。ADR-005：不新增诊断。
 *
 * ## 名称前缀语言
 * 宏名与默认组名由 [KeyMacroTable.NameScheme] 生成；本移植的界面语言是简中
 * （语料 `lang/emuera.zhs.xml:776-777`），故固定用 [KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE]。
 * 这同时意味着：由日文界面写出的 `macro.txt` 在简中界面下同样匹配不上 —— 原版也是这个行为
 * （`KeyMacro.ResetNames` 按当前语言重建名字，`KeyMacro.cs:38-57`）。
 */
object MacroFile {

    /** 原版 `Program.ExeDir + "macro.txt"`（`KeyMacro.cs:31`）。 */
    const val FILE_NAME: String = "macro.txt"

    /** 日文回落编码 = CP932（`windows-31j`）；JVM 缺这个字符集时退回 `Shift_JIS` 等价近似。 */
    private val CP932: Charset =
        runCatching { Charset.forName("windows-31j") }.getOrElse { Charset.forName("Shift_JIS") }
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** UTF-16 小端 BOM（`EncodingHandler.cs:27-31` 的 StreamReader 自动探测；见类文档第 2 条）。 */
    private val UTF16LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())

    /** UTF-16 大端 BOM（同上，`CurrentEncoding` 为 `BigEndianUnicode`）。 */
    private val UTF16BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /**
     * 读 [file] 并装进一张新表。
     *
     * @return 文件不存在、读失败或解码失败（含 BOM 文件里的坏 UTF-8）时为 null（原版静默）；
     *   成功时是已 `loadLines` 的表
     */
    fun load(file: File): KeyMacroTable? {
        if (!file.isFile) return null
        return try {
            val text = decode(file.readBytes()) ?: return null
            val table = KeyMacroTable(KeyMacroTable.NameScheme.SIMPLIFIED_CHINESE)
            table.loadLines(splitLines(text))
            table
        } catch (_: Exception) {
            // 原版 catch 整段吞掉（KeyMacro.cs:135-137）；宏缺失只会让 F1-F12 预填空串。
            null
        }
    }

    /** 按 [EncodingHandler.DetectEncoding] 的优先级解码（见类文档）；null = 原版打不开文件。 */
    internal fun decode(bytes: ByteArray): String? {
        if (startsWith(bytes, UTF8_BOM)) {
            return strictDecode(bytes, UTF8_BOM.size, Charsets.UTF_8)
        }
        // BOM 探测先于「UTF-8 试解 → CP932 回落」：UTF-16 的 0xFF/0xFE 在 CP932 里是合法
        // 单字节，回落会把 UTF-16 文本解成乱码；原版在 StreamReader 构造时就已按 BOM 改道。
        if (startsWith(bytes, UTF16LE_BOM)) {
            return lenientDecode(bytes, UTF16LE_BOM.size, Charsets.UTF_16LE)
        }
        if (startsWith(bytes, UTF16BE_BOM)) {
            return lenientDecode(bytes, UTF16BE_BOM.size, Charsets.UTF_16BE)
        }
        strictDecode(bytes, 0, Charsets.UTF_8)?.let { return it }
        return strictDecode(bytes, 0, CP932)
    }

    private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean {
        if (bytes.size < prefix.size) {
            return false
        }
        for (i in prefix.indices) {
            if (bytes[i] != prefix[i]) {
                return false
            }
        }
        return true
    }

    /**
     * 行拆分对齐 `File.ReadAllLines`：`\r\n` / `\n` / `\r` 都算换行，末尾换行**不**多出空行；
     * 中间连续换行产生的空行保留（随后被 `loadLines` 当空行跳过）。
     */
    internal fun splitLines(text: String): List<String> {
        val lines = text.split("\r\n", "\n", "\r").toMutableList()
        val endsWithNewline = text.endsWith("\n") || text.endsWith("\r")
        if (endsWithNewline && lines.isNotEmpty() && lines.last().isEmpty()) {
            lines.removeAt(lines.lastIndex)
        }
        return lines
    }

    /**
     * 用 [charset] 严格解 [bytes] 从 [offset] 起的全部字节；任何非法/不可映射字节都返回 null
     * （对应原版 `DecoderFallback.ExceptionFallback`，`EncodingHandler.cs:67-71`）。
     */
    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /**
     * 用 [charset] 的**替换**回退解 [bytes] 从 [offset] 起的字节。
     *
     * UTF-16 分支的编码对象来自 .NET 的 `Encoding.Unicode` / `BigEndianUnicode`
     * （`EncodingHandler.cs:27-31` 的 `StreamReader.CurrentEncoding`），它们不是
     * `EncodingHandler` 里那两个 `throwOnInvalidBytes: true` 的自建实例，坏码点会被
     * 替换成 U+FFFD 而不是让 `File.ReadAllLines` 抛异常（`EraStreamReader.cs:52`）。
     */
    private fun lenientDecode(bytes: ByteArray, offset: Int, charset: Charset): String =
        String(bytes, offset, bytes.size - offset, charset)
}
