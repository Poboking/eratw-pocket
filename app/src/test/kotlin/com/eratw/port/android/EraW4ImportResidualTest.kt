package com.eratw.port.android

import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

/**
 * ERA-W4 导入链残差清债的定向判据（CONTRACT-ERA-W4 §0/§3）：
 *
 * 1. **宏预扫空表收口**（原 TODO(verify)，原计划锚 :637 → 现位 firstFunctionNameOf）：
 *    预扫宏表与 PatchCompiler 编译期同一张表（`metadata.dimParseConfig.macros.keys`）后，
 *    `[IF 已定义宏]` 启用分支里的 @ 行能被预扫认出；宏未定义时分支仍禁用（反向证明）。
 *    spec: PatchCompiler.kt:971/:979（编译期宏表来源）、ErbLoader.cs:94（`[IF 宏]` 判定口径）。
 * 2. **编码变体收口**（原 TODO(verify)，原计划锚 :1033-1041 → 现位 ModSourceCodec）：
 *    UTF-16/32 BOM 源按 BOM 直接解，判定序与 core LogicalLineReader.readAllLines 逐位同源
 *    （spec: EncodingHandler.cs:20-32）；UTF-8/CP932 既有路径逐字节不变（反向证明）。
 */
class EraW4ImportResidualTest {

    /** [ModImportController.firstFunctionNameOf] 是实例函数：最小构造（base 源不会被预扫读）。 */
    private fun controller(): ModImportController {
        val tmp = Files.createTempDirectory("eratw-eraw4").toFile()
        return ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = {
                BaseCompileContext(
                    BootMetadata(),
                    PatchCompiler.BaseCounts(
                        instructionCount = 4000,
                        functionCount = 4,
                        statementCount = 100,
                        stringCount = 10,
                        longCount = 5,
                        exprCount = 20,
                    ),
                    baseSha256 = "0".repeat(64),
                    csvDir = null,
                )
            },
            gameRoot = tmp,
        )
    }

    // ---- 收口① 宏预扫宏表 ----

    /** 正证：宏已定义 ⇒ `[IF 宏]` 启用分支里的 @ 行被预扫认出（收口前空集口径取不到它）。 */
    @Test
    fun 宏分支_宏已定义_启用分支内的函数头被认出() {
        val src = "[IF K16FLAG]\n@IN_MACRO_BRANCH\n[ENDIF]\n@AFTER\n"
        // 编译期同源宏表（生产调用点传 base.metadata.dimParseConfig.macros.keys）
        assertEquals("IN_MACRO_BRANCH", controller().firstFunctionNameOf(src, setOf("K16flag")))
        // 反向证明（现状口径锚）：宏表空集 ⇒ 同一源取到分支外的 @AFTER（错位 entry）
        assertEquals("AFTER", controller().firstFunctionNameOf(src))
    }

    /** 反证：宏未定义 ⇒ 分支禁用，分支内 @ 行不进 entry（与编译器判定一致）。 */
    @Test
    fun 宏分支_宏未定义_分支内函数头仍禁用() {
        val src = "[IF OTHER_MACRO]\n@GHOST\n[ENDIF]\n@REAL\n"
        assertEquals("REAL", controller().firstFunctionNameOf(src, setOf("K16FLAG")))
    }

    /** 宏名判定大小写不敏感（ErbPreprocessor 按 Locale.ROOT 归一，构造参数 KDoc 合同）。 */
    @Test
    fun 宏名大小写不敏感() {
        val src = "[if k16flag]\n@CASELESS\n[endif]\n@TAIL\n"
        assertEquals("CASELESS", controller().firstFunctionNameOf(src, setOf("K16FLAG")))
    }

    // ---- 收口③ 编码变体 BOM 分支 ----

    /** UTF-16 LE BOM（FF FE）：按 BOM 直接解，不落 UTF-8 失败 → CP932 宽解的乱码路径。 */
    @Test
    fun decode_utf16leBom源按BOM直解() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "PRINTFORM 口上\n".toByteArray(Charsets.UTF_16LE)
        assertEquals("PRINTFORM 口上\n", ModSourceCodec.decode(bytes))
    }

    @Test
    fun decode_utf16beBom源按BOM直解() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "PRINTFORM 口上\n".toByteArray(Charsets.UTF_16BE)
        assertEquals("PRINTFORM 口上\n", ModSourceCodec.decode(bytes))
    }

    /** UTF-32 LE（FF FE 00 00）必须先于 UTF-16 LE 判：前缀 FF FE 与后者重叠。 */
    @Test
    fun decode_utf32leBom先于utf16le判定() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00) +
            "@A\n".toByteArray(Charsets.UTF_32LE)
        assertEquals("@A\n", ModSourceCodec.decode(bytes))
    }

    @Test
    fun decode_utf32beBom源按BOM直解() {
        val bytes = byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()) +
            "@A\n".toByteArray(Charsets.UTF_32BE)
        assertEquals("@A\n", ModSourceCodec.decode(bytes))
    }

    // ---- 既有路径反向证明（收口不触碰面） ----

    /** UTF-8 BOM 不加分支：EF BB BF 走严格 UTF-8 解，首字符 U+FEFF 保留（由 firstFunctionNameOf 剥）。 */
    @Test
    fun decode_utf8Bom保持既有路径首字符U_FEFF不剥() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "PRINTFORM 口上\n".toByteArray(Charsets.UTF_8)
        assertEquals("\uFEFFPRINTFORM 口上\n", ModSourceCodec.decode(bytes))
    }

    /** 无 BOM 合法 UTF-8：直解（既有行为，ModImportControllerTest 同款钉面）。 */
    @Test
    fun decode_无Bom_utf8直解() {
        assertEquals("PRINTFORM 口上\n", ModSourceCodec.decode("PRINTFORM 口上\n".toByteArray(Charsets.UTF_8)))
    }

    /** 无 BOM 非 UTF-8：回落 CP932（windows-31j，NEC/IBM 扩展区 0x8740 ⇒ U+2460）。 */
    @Test
    fun decode_无Bom_非法utf8回落cp932() {
        assertEquals("\u2460", ModSourceCodec.decode(byteArrayOf(0x87.toByte(), 0x40)))
    }

    /** CP932 合法日文：回落档逐字正确（既有行为）。 */
    @Test
    fun decode_无Bom_cp932日文() {
        assertEquals(
            "グループ0:あい",
            ModSourceCodec.decode("グループ0:あい".toByteArray(Charset.forName("windows-31j"))),
        )
    }

    // ---- 维持项锚：宏表灌入不改变无宏分支源的判定 ----

    /** 只用 SKIPSTART 的真实口上包形态（K16 系）：宏表灌入前后判定一致（红线不触面）。 */
    @Test
    fun 宏表灌入对无宏分支源零差异() {
        val src = "[SKIPSTART]\n@IMAGE\n[SKIPEND]\n@ENTRY\n"
        assertEquals("ENTRY", controller().firstFunctionNameOf(src, setOf("K16FLAG")))
        assertEquals("ENTRY", controller().firstFunctionNameOf(src))
    }

    /** 全文件在 SKIPSTART 块内 ⇒ 无 entry（B4 实测形态，宏表灌入不改变）。 */
    @Test
    fun 全skipstart块宏表灌入仍取不到entry() {
        assertNull(controller().firstFunctionNameOf("[SKIPSTART]\n@GHOST\n", setOf("K16FLAG")))
    }
}
