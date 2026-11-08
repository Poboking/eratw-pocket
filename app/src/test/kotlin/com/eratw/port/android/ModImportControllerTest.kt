package com.eratw.port.android


import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ModPatchLoader
import com.eratw.port.core.patch.PatchArtifacts
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * R7-C5 导入链的出口判据（CONTRACT-R7-C §2/C5 ①–④，CONTRACT-R7.md §1.6 的 C5 承诺面）：
 *
 * 1. **编译成功落盘 `.patch`**：mini 侧车 + 合成 base 计数注入 [BaseCompileContextSource]，
 *    真 [PatchCompiler] / [ModStore] / [PatchArtifacts] 走全链；产物名与
 *    [ModPatchLoader.PATCH_EXTENSION] 对齐，源 `.ERB` 留档，产物读回（I8）与装载器
 *    [ModPatchLoader] 兼容（C5↔C4 的口径对齐钉子）。
 * 2. **I6 失败文案含文件:行**：全局 `#DIM` 补丁被编译期拒绝（I6-②），错误消息带
 *    源文件名与人读行号（I7 可定位）。
 * 3. **存储失败**：非法文件名 / 空字节被 [ModStore] 拒收，折成 STORAGE 态。
 * 4. **排序与扩展名口径**：多文件 sources 按文件名序（[ModStore.compareNames] 同源）、
 *    产物名规则、`.patch` 扩展名判定与 [ModPatchLoader] 的扫描口径同解。
 *
 * 全部纯 JVM：base 上下文注入（[BaseCompileContextSource] 是接口），不碰 Android 类。
 */
class ModImportControllerTest {

    // ------------------------------------------------------------------
    // 夹具：mini 侧车（A 组函数的元数据）+ 合成 base 计数 + 临时 mods 目录
    // ------------------------------------------------------------------

    /**
     * mini base 的侧车：函数表（A_ONE/A_TWO/A_SUM/A_DEF）+ method-types（A_SUM 是
     * `#FUNCTION` 整型方法）。与 core-vm `PatchCompilerTest.compileBase` 同源口径。
     */
    private fun miniMetadata(): BootMetadata {
        val names = listOf("A_ONE", "A_TWO", "A_SUM", "A_DEF")
        val argCounts = mapOf("A_TWO" to 1, "A_DEF" to 3)
        return BootMetadata(
            functions = names.mapIndexed { i, name ->
                FuncMeta(
                    index = i,
                    key = name,
                    name = name,
                    isMethod = name == "A_SUM",
                    argIntCount = argCounts[name] ?: 0,
                    argStrCount = 0,
                    returnsStr = false,
                    defIndex = i,
                    position = "mini.ERB:${i + 1}",
                )
            },
            methodTypes = mapOf("A_SUM" to BootMetadata.MethodType.INT),
        )
    }

    /** 合成 base 基线计数（装载端只要六个读数自洽即可；真值由端到端用例从 baseProgram 取）。 */
    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    /** 不引用 base 池条目/用户变量的补丁源（符号面只要 A_ONE 在函数表里）。 */
    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0
    """.trimIndent() + "\n"

    private fun contextOf(
        metadata: BootMetadata = miniMetadata(),
        counts: PatchCompiler.BaseCounts = miniBaseCounts(),
        sha: String = BASE_SHA,
    ): BaseCompileContext = BaseCompileContext(metadata, counts, sha, csvDir = null)

    private fun controllerIn(tmp: File, context: BaseCompileContext): ModImportController =
        ModImportController(
            modStore = ModStore(File(tmp, "mods")),
            baseSource = { context },
            gameRoot = tmp,
        )

    // ------------------------------------------------------------------
    // 判据 1：编译成功落盘 .patch（含 C5↔C4 装载口径对齐）
    // ------------------------------------------------------------------

    @Test
    fun `编译成功——源留档且产物落盘为 patch 并能被装载器读回`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(listOf(PickedModFile("KOJO_PATCH.ERB", patchSource.toByteArray(Charsets.UTF_8))))

        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")
        assertEquals(ModImportController.UNION_PATCH_NAME, detail.patchFileName)
        assertEquals("补丁只定义 B_HELLO 一个函数", 1, detail.functionCount)
        assertEquals("B_HELLO", detail.entryFunction)
        assertEquals(listOf("KOJO_PATCH.ERB"), detail.sourceFileNames)

        val mods = File(tmp, "mods")
        assertTrue("源 .ERB 应留档在 mods", File(mods, "KOJO_PATCH.ERB").isFile)
        val artifactFile = File(mods, ModImportController.UNION_PATCH_NAME)
        assertTrue("补丁产物应落盘为 .patch", artifactFile.isFile)

        // 读回：结构合法 + 头字段与导入上下文逐项一致（I3 的六计数 + sha + entry）。
        val readBack = PatchArtifacts.read(artifactFile.readBytes())
        assertEquals(miniBaseCounts().instructionCount, readBack.header.baseInstructionCount)
        assertEquals(miniBaseCounts().functionCount, readBack.header.baseFunctionCount)
        assertEquals(BASE_SHA, readBack.header.baseSha256)
        assertEquals("B_HELLO", readBack.header.entryFunction)
        assertTrue("补丁体（内嵌 .erbc）非空", readBack.bodySize > 0)
    }

    @Test
    fun `端到端——导入产物能被 ModPatchLoader 装载（C5 与 C4 口径对齐）`() {
        val tmp = tempDir()
        val baseProgram = compileMiniBase()
        val counts = PatchCompiler.BaseCounts(
            instructionCount = baseProgram.instructionCount,
            functionCount = baseProgram.functions.size,
            statementCount = baseProgram.statementCount,
            stringCount = baseProgram.stringCount,
            longCount = baseProgram.constantCount,
            exprCount = baseProgram.exprCount,
        )
        val controller = controllerIn(tmp, contextOf(counts = counts))
        val outcome = controller.import(listOf(PickedModFile("KOJO_PATCH.ERB", patchSource.toByteArray(Charsets.UTF_8))))
        assertTrue("导入应成功，实为 $outcome", outcome is ModImportOutcome.Imported)

        // 装载器（C4）只认 .patch：目录里的源 .ERB 被扩展名过滤，产物被读回、
        // I3 身份对同一 sha 通过、补丁函数追加在 base 之后（装载序恒为最后的钉子）。
        val loaded = ModPatchLoader.load(File(tmp, "mods").toPath(), baseProgram, BASE_SHA)
        assertFalse("合法补丁不应触发回退（I5）", loaded.rolledBack)
        assertEquals(1, loaded.loadedPatches.size)
        assertEquals(ModImportController.UNION_PATCH_NAME, loaded.loadedPatches[0].name)
        assertEquals(1, loaded.loadedPatches[0].functionCount)
        assertTrue("补丁段指令数 > 0", loaded.loadedPatches[0].patchInstructionCount > 0)
        assertEquals("函数表 = base + 补丁尾段", baseProgram.functions.size + 1, loaded.program.functions.size)
    }

    // ------------------------------------------------------------------
    // 判据 2：I6 失败文案含文件:行
    // ------------------------------------------------------------------

    @Test
    fun `I6 二——全局 DIM 补丁被编译期拒绝且文案含文件与行号`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val badSource = "#DIM GLOBAL_COUNT = 5\n\n@B_GLOBAL\nRETURN 0\n"
        val outcome = controller.import(listOf(PickedModFile("KOJO_BAD.ERB", badSource.toByteArray(Charsets.UTF_8))))

        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("全局 #DIM 必须被拒绝，实为 $outcome")
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertTrue(
            "错误文案必须带文件名（I7 可定位）：${failed.message}",
            failed.message.contains("KOJO_BAD.ERB"),
        )
        assertTrue(
            "错误文案必须带人读行号：${failed.message}",
            failed.message.contains(":1") || failed.message.contains("第 1 行") || failed.message.contains("行:1"),
        )
        assertTrue("全局 #DIM 不落产物（I5）", !File(File(tmp, "mods"), "KOJO_BAD.patch").exists())
    }

    @Test
    fun `I6 三——补丁定义 base 已有同名函数被拒绝并点名`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val badSource = "@A_ONE\nRETURN 0\n"
        val outcome = controller.import(listOf(PickedModFile("KOJO_DUP.ERB", badSource.toByteArray(Charsets.UTF_8))))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("base 同名函数必须被拒绝，实为 $outcome")
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertTrue("要点名撞名函数：${failed.message}", failed.message.contains("A_ONE"))
    }

    @Test
    fun `空补丁——没有函数定义时明确拒绝`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(listOf(PickedModFile("EMPTY.ERB", "; 只有注释\n".toByteArray(Charsets.UTF_8))))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("无函数补丁必须被拒绝，实为 $outcome")
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertFalse(File(File(tmp, "mods"), "EMPTY.patch").exists())
    }

    // ------------------------------------------------------------------
    // 判据 3：存储失败
    // ------------------------------------------------------------------

    @Test
    fun `存储失败——非法文件名折成 STORAGE 态且产物不落盘`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        // SAF 的 DISPLAY_NAME 理论上不含分隔符，但名字面校验是 ModStore 的合同：防呆闸要红。
        val outcome = controller.import(listOf(PickedModFile("bad/name.ERB", patchSource.toByteArray(Charsets.UTF_8))))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("非法文件名必须被拒，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertFalse(File(tmp, "mods").isDirectory && File(File(tmp, "mods"), "bad_name.patch").exists())
    }

    @Test
    fun `存储失败——空字节被 ModStore 拒收`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(listOf(PickedModFile("EMPTY.ERB", ByteArray(0))))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("空文件必须被拒，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
    }

    // ------------------------------------------------------------------
    // 判据 4：排序与扩展名口径
    // ------------------------------------------------------------------

    @Test
    fun `多文件按文件名序排序（ASCII 折叠、locale 无关）`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val sorted = controller.sortSourceFiles(
            listOf(
                PickedModFile("B_WORLD.ERB", "x".toByteArray()),
                PickedModFile("a_hello.ERB", "x".toByteArray()),
                PickedModFile("C.ERB", "x".toByteArray()),
            ),
        )
        assertEquals(listOf("a_hello.ERB", "B_WORLD.ERB", "C.ERB"), sorted.map { it.displayName })
    }

    @Test
    fun `产物名恒为并集产物——与装载序无关，扩展名口径对齐装载器`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        // 补丁窗口只能锚定裸 base 产物：第二张同样锚裸 base 的补丁接不上装载器的窗口对齐闸
        // ⇒ 设备端产物必须只有一张（全部留档源并集重编），产物名因此与「首个源」脱钩。
        assertEquals(ModImportController.UNION_PATCH_NAME, "kojo-imports.patch")
        assertTrue(
            "并集产物名必须走装载器的扩展名权威口径（扫描面才认它）",
            ModPatchLoader.hasPatchExtension(ModImportController.UNION_PATCH_NAME),
        )
        val outcome = controller.import(listOf(PickedModFile("zz.ERB", patchSource.toByteArray(Charsets.UTF_8))))
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")
        assertEquals(ModImportController.UNION_PATCH_NAME, detail.patchFileName)
        assertTrue(File(File(tmp, "mods"), ModImportController.UNION_PATCH_NAME).isFile)
    }

    @Test
    fun `扩展名判定只认装载器权威——app 侧消费点对拍零复制`() {
        // R7-D1 任务③：判定口径的唯一权威在 core-vm 的 [ModPatchLoader.hasPatchExtension]
        // （本波组长授权提为 public）；app 侧 `patchFileNamesIn` 只引用不自持。原 C5 用例
        // 的 6 条断言逐字挪家到权威函数上（零放宽），消费行为另见下一条用例。
        assertTrue(ModPatchLoader.hasPatchExtension("a.patch"))
        assertTrue(ModPatchLoader.hasPatchExtension("a.PATCH"))
        assertTrue(ModPatchLoader.hasPatchExtension("a.Patch"))
        assertFalse(ModPatchLoader.hasPatchExtension("a.patchx"))
        assertFalse(ModPatchLoader.hasPatchExtension("patch"))
        assertFalse("裸扩展名 = 隐藏文件，装载器扫描口径跳过它", ModPatchLoader.hasPatchExtension(".patch"))
    }

    @Test
    fun `patchFileNamesIn 按装载器权威判定过滤补丁候选`() {
        val tmp = tempDir()
        val mods = File(tmp, "mods").apply { mkdirs() }
        for (name in listOf("a.patch", "b.PATCH", "c.Patch", "d.patchx", "patch", ".patch", "e.ERB")) {
            File(mods, name).writeBytes(byteArrayOf(1))
        }
        val controller = controllerIn(tmp, contextOf())
        // 大小写不敏感的补丁候选进清单（文件名序），其余扩展名被权威判定挡掉。
        // 列目录口径（File API、不滤隐藏名）是 app 侧静态清单的既有行为，本用例只钉
        // 「过滤判定来自 ModPatchLoader」这一件事。
        assertEquals(listOf("a.patch", "b.PATCH", "c.Patch"), controller.patchFileNamesIn(mods))
    }

    @Test
    fun `入口函数预扫取首个函数头（与编译器函数名口径同源）`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        assertEquals("B_HELLO", controller.firstFunctionNameOf(patchSource))
        assertEquals("BAR", controller.firstFunctionNameOf("前置行\n@BAR(N)\n"))
        assertNull(controller.firstFunctionNameOf("; 注释\nPRINTL 行首星号不算\n"))
    }

    /**
     * 4.981 浮点面灌进补丁符号表（导入链的解析面）：
     * * 全局 `#DIMF` ⇒ 名字类型必须是 FLOAT（旧实现 `if (isStr) STR else INT` 把浮点声明
     *   折成整型 ⇒ 补丁里读写它落整型列，静默错值；全量侧同判据是 CorpusScan.kt:242）。
     * * base `#FUNCTIONF` ⇒ `method-types` 段只有三态的 UNKNOWN（浮点位不落那张表），
     *   但 `function` 段逐条带 `returnsFloat`；不补声明时表达式位 `F()` 按整型解析，
     *   运行期按整型读浮点返回（恒 0）。
     */
    @Test
    fun `浮点声明与浮点方法灌进补丁符号表`() {
        val tmp = tempDir()
        val meta = BootMetadata(
            functions = listOf(
                FuncMeta(
                    index = 0, key = "A_RATE", name = "A_RATE", isMethod = true,
                    returnsFloat = true, floatLocalSize = 12, defIndex = 0, position = "mini.ERB:1",
                ),
            ),
            methodTypes = mapOf("A_RATE" to BootMetadata.MethodType.UNKNOWN),
            globalDims = listOf(
                BootMetadata.RawDim("#DIMF 倍率 = 0.0", "mini.ERB", 3),
                BootMetadata.RawDim("#DIMS 名字", "mini.ERB", 4),
            ),
        )
        val controller = controllerIn(tmp, contextOf(metadata = meta))
        val sym = controller.buildPatchSymbolTable(meta, null).table
        assertEquals("全局 #DIMF 必须灌成浮点名", OperandType.FLOAT, sym.resolveVariable("倍率", null)?.operandType)
        assertEquals("#DIMS 不受影响", OperandType.STR, sym.resolveVariable("名字", null)?.operandType)
        assertEquals("base #FUNCTIONF 必须灌成浮点方法", OperandType.FLOAT, sym.resolveFunction("A_RATE"))
    }

    // ------------------------------------------------------------------
    // 判据 8（B4）：[SKIPSTART]/[SKIPEND]——预扫与编译器状态机同口径（ErbPreprocessor，
    // spec: ErbLoader.cs:25-181）。实测原型：群友整合包排序第一文件全文件在 SKIPSTART 块内。
    // ------------------------------------------------------------------

    @Test
    fun `预扫——全文件 SKIPSTART 返回 null`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        assertNull(controller.firstFunctionNameOf("[SKIPSTART]\n@GHOST\n[SKIPEND]\n"))
        // 未闭合（SKIPSTART 无 SKIPEND）：禁用持续到文件尾（spec: ErbLoader.cs:169-180
        // 只 Warn 不复位），整文件无可用函数头
        assertNull(controller.firstFunctionNameOf("[SKIPSTART]\n@GHOST\n"))
    }

    @Test
    fun `预扫——SKIPEND 之后的函数头可用且取块外首个`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        assertEquals("BAZ", controller.firstFunctionNameOf("[SKIPSTART]\n@BAR\n[SKIPEND]\n@BAZ\n"))
        assertEquals("块内多函数只跳块内", "BAZ", controller.firstFunctionNameOf("[SKIPSTART]\n@A\n@B\n[SKIPEND]\n@BAZ\n@C\n"))
    }

    @Test
    fun `预扫——重复 SKIPSTART 不嵌套（原版 DuplicateSkipstart 忽略）`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        // 第二个 [SKIPSTART] 被忽略 ⇒ 第一个 [SKIPEND] 即闭块；第二个 [SKIPEND] 无配对
        // （原版 UnexpectedSkipend 忽略）⇒ @B 可用。口径 spec: ErbLoader.cs:43-60/127-141。
        assertEquals("B", controller.firstFunctionNameOf("[SKIPSTART]\n[SKIPSTART]\n@A\n[SKIPEND]\n[SKIPEND]\n@B\n"))
    }

    @Test
    fun `预扫——无配对的 SKIPEND 不改状态`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        assertEquals("A", controller.firstFunctionNameOf("[SKIPEND]\n@A\n"))
    }

    @Test
    fun `预扫——大小写与前导空白容忍、注释行跳过`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        assertEquals("B", controller.firstFunctionNameOf("  [skipstart]\n@A\n\t[SKIPEND]\n@B\n"))
        assertEquals("BAR", controller.firstFunctionNameOf(";@FOO\n@BAR\n"))
    }

    @Test
    fun `entry 回退——首个源整文件被 SKIPSTART 跳过时取下一个源的函数头`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(
            listOf(
                PickedModFile("A_IMAGE.ERB", "[SKIPSTART]\n@GHOST\n[SKIPEND]\n".toByteArray(Charsets.UTF_8)),
                PickedModFile("B_KOJO.ERB", patchSource.toByteArray(Charsets.UTF_8)),
            ),
        )
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("entry 应跨源回退到 B_KOJO，实为 $outcome")
        assertEquals("B_HELLO", detail.entryFunction)
    }

    @Test
    fun `entry 回退——全部源都没有可用函数头时报 COMPILE`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(
            listOf(
                PickedModFile("A_IMAGE.ERB", "[SKIPSTART]\n@GHOST\n".toByteArray(Charsets.UTF_8)),
                PickedModFile("B_EMPTY.ERB", "; 全是注释\n".toByteArray(Charsets.UTF_8)),
            ),
        )
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("无可用函数头必须失败，实为 $outcome")
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertTrue("文案要点名源数：${failed.message}", failed.message.contains("2 个源文件"))
    }

    // ------------------------------------------------------------------
    // 判据 5：源文本解码（严格 UTF-8 → CP932 回落，2026-11 zip/SJIS 导入路）
    // ------------------------------------------------------------------

    /**
     * CP932 特有字符钉子（断言口径照抄 platform 的 MacroFileTest 先例）：
     * 0x8740 ⇒ U+2460 是 NEC 扩展行的 windows-31j 映射，JDK 裸 `Shift_JIS` 解不动它；
     * 0x815C ⇒ U+2015（CP932）而非 U+2014（Shift_JIS）。语义权威 `EncodingHandler.cs:37-40`。
     */
    @Test
    fun `解码口——SJIS 字节回落 CP932 且含 NEC 扩展区`() {
        assertEquals(
            "グループ0:あい",
            ModSourceCodec.decode("グループ0:あい".toByteArray(Charset.forName("windows-31j"))),
        )
        assertEquals(
            "0x8740 ⇒ U+2460（①，NEC 扩展行；JDK Shift_JIS 解不动）",
            "\u2460",
            ModSourceCodec.decode(byteArrayOf(0x87.toByte(), 0x40)),
        )
        assertEquals(
            "0x815C ⇒ U+2015（CP932；JDK Shift_JIS 给 U+2014）",
            "\u2015",
            ModSourceCodec.decode(byteArrayOf(0x81.toByte(), 0x5C.toByte())),
        )
    }

    @Test
    fun `解码口——合法 UTF-8 严格通过不回落`() {
        assertEquals("PRINTFORM 口上\n", ModSourceCodec.decode("PRINTFORM 口上\n".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `解码口——PickedModFile 的 text 走同一口且函数头可预扫`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val sjis = "@B_HELLO\nPRINTL テスト\u2460\nRETURN 0\n".toByteArray(Charset.forName("windows-31j"))
        val picked = PickedModFile("KOJO.ERB", sjis)
        assertTrue("①（0x8740）按 CP932 解出而非 U+FFFD", picked.text.contains("\u2460"))
        assertEquals("B_HELLO", controller.firstFunctionNameOf(picked.text))
    }

    // ------------------------------------------------------------------
    // 判据 6：zip 整包导入（解包 / 排序 / 落盘名 / 失败面）
    // ------------------------------------------------------------------

    /** 构造 zip 字节（条目按入参顺序写入；目录层级直接写在条目名里）。 */
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `zip 导入——解出 ERB 与 ERH 条目、跳过其余、排序落盘`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val zip = zipOf(
            "B_SECOND.ERB" to patchSource.toByteArray(Charsets.UTF_8),
            "A_FIRST.ERB" to "@B_A\nCALL A_ONE(1)\nRETURN 0\n".toByteArray(Charsets.UTF_8),
            "readme.txt" to "说明不是源".toByteArray(Charsets.UTF_8),
            "sub/HEADER.ERH" to "; 头文件当普通源送编译（ERH 语义归 PatchCompiler 车道）".toByteArray(Charsets.UTF_8),
        )
        val outcome = controller.import(listOf(PickedModFile("kojo_v2.ZIP", zip)))
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("zip 导入应成功，实为 $outcome")
        // 条目名如实列出（含 zip 内相对路径），txt 跳过；文件名序 = compareNames 对路径串口径。
        assertEquals(listOf("A_FIRST.ERB", "B_SECOND.ERB", "sub/HEADER.ERH"), detail.sourceFileNames)
        // 落盘留档 = 主名（切掉 zip 内目录层级），产物名取装载序首个源的主名。
        val mods = File(tmp, "mods")
        assertTrue(File(mods, "A_FIRST.ERB").isFile)
        assertTrue(File(mods, "HEADER.ERH").isFile)
        assertFalse("readme.txt 不落盘", File(mods, "readme.txt").exists())
        assertTrue(File(mods, ModImportController.UNION_PATCH_NAME).isFile)
        assertEquals(ModImportController.UNION_PATCH_NAME, detail.patchFileName)
    }

    @Test
    fun `zip 导入——SJIS 条目内容按 CP932 解码可编译`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        // 0x87 0x40 是非法 UTF-8 序列 ⇒ 必须回落 CP932；裸 Shift_JIS 会把它解成 U+FFFD。
        val zip = zipOf(
            "KOJO.ERB" to "@B_HELLO\nPRINTL テスト①\nCALL A_ONE(3)\nRETURN 0\n"
                .toByteArray(Charset.forName("windows-31j")),
        )
        val entries = ModZip.unpack(zip)
        assertEquals(1, entries.size)
        assertTrue("内容含 ①（0x8740 ⇒ U+2460）", entries[0].bytes.let { PickedModFile("x.ERB", it).text.contains("\u2460") })
        val outcome = controller.import(listOf(PickedModFile("sjis.zip", zip)))
        assertTrue("SJIS 源应成功导入，实为 $outcome", outcome is ModImportOutcome.Imported)
    }

    @Test
    fun `zip 导入——坏 zip 折成 STORAGE 明确报错`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(listOf(PickedModFile("broken.zip", "这不是 zip 字节".toByteArray(Charsets.UTF_8))))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("坏 zip 必须失败，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("文案要点名 zip：${failed.message}", failed.message.contains("broken.zip"))
        assertFalse("失败不落产物（I5）", File(tmp, "mods").isDirectory && File(File(tmp, "mods"), "broken.patch").exists())
    }

    @Test
    fun `zip 导入——加密位条目折成 STORAGE（JDK 不解密，读取期抛错）`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        // 手术置位 ZIP APPNOTE §4.3.7 的 general purpose bit 0（加密位）：
        // local file header 的布局是 signature(4) + version(2) + flags(2)，flags 在 offset 6 小端。
        val zip = zipOf("KOJO.ERB" to patchSource.toByteArray(Charsets.UTF_8)).copyOf()
        zip[6] = (zip[6].toInt() or 0x01).toByte()
        val outcome = controller.import(listOf(PickedModFile("locked.zip", zip)))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("加密 zip 必须失败，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("文案要点名 zip：${failed.message}", failed.message.contains("locked.zip"))
    }

    @Test
    fun `zip 导入——没有 ERB 与 ERH 条目的 zip 折成 STORAGE`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val zip = zipOf(
            "readme.txt" to "x".toByteArray(Charsets.UTF_8),
            "data.csv" to "y".toByteArray(Charsets.UTF_8),
        )
        val outcome = controller.import(listOf(PickedModFile("empty_pack.zip", zip)))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("无源条目必须失败，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
    }

    /**
     * 跨卡指路（2026-11）：素材包被选进「补丁（MOD）导入」卡时，报错要指向「素材导入」卡。
     * 判定口径与 [com.eratw.port.platform.storage.ResourcePackStore] 的投放合同同源（resources/sound 段、
     * 根级 BGI.csv / 背景图 / 游戏bgm，允许一层外层包裹），见 [ModZip.looksLikeAssetPack]。
     */
    @Test
    fun `zip 导入——素材包被选进补丁卡时指路到素材导入`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val zip = zipOf("resources/x.webp" to byteArrayOf(1, 2, 3))
        val outcome = controller.import(listOf(PickedModFile("bg_pack.zip", zip)))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("素材包不该走编译成功路径，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue(
            "文案要指路到素材导入卡：${failed.message}",
            failed.message.contains("这看起来是素材包，请到素材导入"),
        )
        assertTrue("文案要点名 zip：${failed.message}", failed.message.contains("bg_pack.zip"))
        assertFalse(
            "指路失败不落产物（I5）",
            File(tmp, "mods").isDirectory &&
                File(File(tmp, "mods"), ModImportController.UNION_PATCH_NAME).exists(),
        )
        // 判定口径本身（素材卡投放合同同源）：resources/sound 段任意深度都算；根级与一层包裹的
        // BGI.csv / 背景图 / 游戏bgm 也算。纯文本包与坏字节不算（坏 zip 不在此分叉改写失败面）。
        assertTrue("resources 段", ModZip.looksLikeAssetPack(zip))
        assertTrue(
            "sound 段",
            ModZip.looksLikeAssetPack(zipOf("sound/游戏bgm/家/1.MP3" to byteArrayOf(9))),
        )
        assertTrue("根级 BGI.csv", ModZip.looksLikeAssetPack(zipOf("BGI.csv" to ByteArray(1))))
        assertTrue(
            "一层包裹的 resources",
            ModZip.looksLikeAssetPack(zipOf("eratw-master/resources/a.png" to ByteArray(1))),
        )
        assertTrue(
            "一层包裹的背景图",
            ModZip.looksLikeAssetPack(zipOf("pack/背景图/a.png" to ByteArray(1))),
        )
        assertTrue("根级游戏bgm", ModZip.looksLikeAssetPack(zipOf("游戏bgm/家/1.MP3" to ByteArray(1))))
    }

    @Test
    fun `zip 导入——不像素材包的非源 zip 维持旧文案`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val zip = zipOf("readme.txt" to "说明不是源".toByteArray(Charsets.UTF_8))
        val outcome = controller.import(listOf(PickedModFile("empty_pack.zip", zip)))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("无源条目必须失败，实为 $outcome")
        assertEquals(ModImportFailureKind.STORAGE, failed.kind)
        assertTrue(
            "非素材包维持旧文案（说清没有 .ERB/.ERH）：${failed.message}",
            failed.message.contains("口上 zip 里没有可导入的 .ERB/.ERH 条目"),
        )
        assertTrue("文案要点名 zip：${failed.message}", failed.message.contains("empty_pack.zip"))
        assertFalse("不得误报成素材包：${failed.message}", failed.message.contains("素材包"))
        assertFalse("纯 readme 不算素材包", ModZip.looksLikeAssetPack(zip))
        assertFalse("空字节不算素材包", ModZip.looksLikeAssetPack(ByteArray(0)))
        assertFalse(
            "坏 zip 不算素材包（不把解包失败改写成另一种失败）",
            ModZip.looksLikeAssetPack("这不是 zip 字节".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `zip 导入——同名条目取先落盘并出告警，后到者不进编译`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val zip = zipOf(
            "a/patch.ERB" to patchSource.toByteArray(Charsets.UTF_8),
            "b/patch.ERB" to "@B_LATER\nRETURN 0\n".toByteArray(Charsets.UTF_8),
        )
        val outcome = controller.import(listOf(PickedModFile("dup.zip", zip)))
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("同名取先应成功，实为 $outcome")
        // 装载序 a/patch.ERB 在前：只保留它，b/patch.ERB 跳过落盘与编译并出告警。
        assertEquals(listOf("a/patch.ERB"), detail.sourceFileNames)
        assertTrue(
            "告警要点名被跳过的条目：${detail.parseWarnings}",
            detail.parseWarnings.any { it.contains("同名源文件取先") && it.contains("b/patch.ERB") },
        )
        assertEquals(
            "落盘的是先到条目的内容",
            patchSource,
            File(File(tmp, "mods"), "patch.ERB").readText(),
        )
        assertEquals(ModImportController.UNION_PATCH_NAME, detail.patchFileName)
    }

    @Test
    fun `zip 与直选文件混合导入——合并后按文件名序`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val zip = zipOf("B_MID.ERB" to "@B_MID\nRETURN 0\n".toByteArray(Charsets.UTF_8))
        val outcome = controller.import(
            listOf(
                PickedModFile("Z_LAST.ERB", "@B_LAST\nRETURN 0\n".toByteArray(Charsets.UTF_8)),
                PickedModFile("pack.zip", zip),
            ),
        )
        val detail = (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("混合导入应成功，实为 $outcome")
        assertEquals(listOf("B_MID.ERB", "Z_LAST.ERB"), detail.sourceFileNames)
        assertEquals("产物名恒为并集产物", ModImportController.UNION_PATCH_NAME, detail.patchFileName)
    }

    @Test
    fun `ERH 直选——过 ModStore 校验进编译面（无函数时报 COMPILE 而非 STORAGE）`() {
        val tmp = tempDir()
        val controller = controllerIn(tmp, contextOf())
        val outcome = controller.import(listOf(PickedModFile("HEADER.ERH", "; 只有注释\n".toByteArray(Charsets.UTF_8))))
        val failed = outcome as? ModImportOutcome.Failed
            ?: throw AssertionError("ERH 应过存储闸、折成编译失败，实为 $outcome")
        assertEquals(ModImportFailureKind.COMPILE, failed.kind)
        assertTrue("源应已留档（save 在 compile 前）", File(File(tmp, "mods"), "HEADER.ERH").isFile)
    }

    @Test
    fun `zip 条目按落盘主名切名，直选文件原样`() {
        val tmp = tempDir()
        controllerIn(tmp, contextOf())
        val zipEntry = PickedModFile("sub/A.ERB", ByteArray(0), fromArchive = true)
        val winEntry = PickedModFile("""bad\B.ERB""", ByteArray(0), fromArchive = true)
        val plainFile = PickedModFile("zz.ERB", ByteArray(0))
        assertEquals("A.ERB", zipEntry.diskName)
        assertEquals("Windows 打包器的反斜杠条目同样切主名", "B.ERB", winEntry.diskName)
        assertEquals("普通文件不切名", "zz.ERB", plainFile.diskName)
        // zip 条目的 displayName 保留相对路径（编译报错定位用），落盘只用主名。
        assertEquals("sub/A.ERB", zipEntry.displayName)
    }

    @Test
    fun `二次导入=并集重编，旧 per-import 产物被清理`() {
        // 留档源是真相：第二次导入把「已留档 + 本次新源」一次编成一张补丁（两张同窗口的
        // 产物在装载侧接不上，结构性不能并存）。
        val tmp = tempDir()
        val first = PickedModFile("K16_A.ERB", patchSource.toByteArray(Charsets.UTF_8))
        val second = PickedModFile("K16_B.ERB", "@B_TWO\nCALL A_ONE(1)\nRETURN 0\n".toByteArray(Charsets.UTF_8))
        val mods = File(tmp, "mods")
        mods.mkdirs()

        val one = controllerIn(tmp, contextOf()).import(listOf(first))
        val d1 = (one as? ModImportOutcome.Imported)?.detail ?: throw AssertionError("第一次导入应成功: $one")
        assertEquals(1, d1.functionCount)

        // 模拟旧版产物名（首个源主名 + .patch）：新实现必须把它清掉，只留并集那一张。
        File(mods, "K16_A.patch").writeBytes(byteArrayOf(0))
        val two = controllerIn(tmp, contextOf()).import(listOf(second))
        val d2 = (two as? ModImportOutcome.Imported)?.detail ?: throw AssertionError("第二次导入应成功: $two")
        assertEquals("并集重编应包含两张源的全部函数", 2, d2.functionCount)
        assertEquals("sourceFileNames 只列本次导入的源", listOf("K16_B.ERB"), d2.sourceFileNames)
        assertFalse("旧形态产物必须清理（否则装载侧撞窗口闸）", File(mods, "K16_A.patch").exists())
        assertTrue(File(mods, ModImportController.UNION_PATCH_NAME).isFile)
        // 留档源仍在（可再次重编的根据）。
        assertTrue(File(mods, "K16_A.ERB").isFile && File(mods, "K16_B.ERB").isFile)
    }

    @Test
    fun `非本链产物不删——玩家手放的 patch 保留给装载侧判定`() {
        val tmp = tempDir()
        val mods = File(tmp, "mods")
        mods.mkdirs()
        val hand = File(mods, "handmade.patch")
        hand.writeBytes(byteArrayOf(1, 2, 3))
        val outcome = controllerIn(tmp, contextOf())
            .import(listOf(PickedModFile("K16_A.ERB", patchSource.toByteArray(Charsets.UTF_8))))
        assertTrue("导入应成功，实为 $outcome", outcome is ModImportOutcome.Imported)
        assertTrue("与留档源不同名的 .patch 不是本链产物，不得删除", hand.isFile)
    }

    // ------------------------------------------------------------------
    // 判据 7：真实口上包语料（群友整合包·蕾米莉亚色情特化 v1.3：54 个 .ERB / 约 12 MB /
    // 全 UTF-8 / 无 zip 无 ERH）。corpus 依赖用例：包缺失时直接 return，不产断言面
    // （与语料装载用例同口径——变量缺失不生成、也不计入 skipped）。
    // ------------------------------------------------------------------

    /** 语料根（只读）：环境变量 ERATW_INTEGRATION_PACK 指向的路径；包缺失时用例体跳过。 */
    private val realPackRoot: File? =
        System.getenv("ERATW_INTEGRATION_PACK")?.let(::File)?.takeIf { it.isDirectory }

    /**
     * 真实包内存 zip 化 → 导入链解包口：条目全解出、displayName = zip 内相对路径、
     * 排序对乱序输入稳定（compareNames 同输入同输出）。全 UTF-8 包走严格 UTF-8 档，
     * 不触发 CP932 回落（回落语义由判据 5 的合成字节钉子负责）。
     */
    @Test
    fun `真实语料——整包 zip 化解出全部 ERB 条目且排序稳定`() {
        val pack = realPackRoot ?: return
        val erbFiles = pack.walkTopDown()
            .filter { it.isFile && ModStore.hasErbExtension(it.name) }
            .toList()
        assertTrue("语料包应收出 .ERB（路径=${pack.absolutePath}）", erbFiles.isNotEmpty())
        val zipBytes = zipOf(
            *erbFiles.map { file ->
                pack.toPath().relativize(file.toPath()).toString().replace('\\', '/') to file.readBytes()
            }.toTypedArray(),
        )
        val entries = ModZip.unpack(zipBytes)
        assertEquals("解出条目数 = 包内 .ERB 数", erbFiles.size, entries.size)
        assertTrue("本包量级锚定（防语料被换成小样后静默失真）", entries.size >= 50)
        assertTrue("解出条目应全为 .ERB（本包无 ERH）", entries.all { ModStore.hasErbExtension(it.name) })
        // 排序稳定：乱序输入重排后逐名一致（compareNames 的确定性口径；zip 打包顺序刻意打乱）
        val canonical = entries.sortedWith { a, b -> ModStore.compareNames(a.name, b.name) }.map { it.name }
        val shuffled = entries.shuffled(java.util.Random(20261102L))
            .sortedWith { a, b -> ModStore.compareNames(a.name, b.name) }.map { it.name }
        assertEquals(canonical, shuffled)
        // PickedModFile 视角（导入链的真实输入形态）：displayName 如实、正文可解码非空。
        // 全 UTF-8 ⇒ 严格档解码成功，不应出现 U+FFFD（出现即说明回落/替换路径被误触发）。
        val picked = canonical.map { name -> entries.first { it.name == name } }
            .map { PickedModFile(it.name, it.bytes, fromArchive = true) }
        assertEquals(canonical, picked.map { it.displayName })
        assertTrue(picked.all { it.text.isNotEmpty() })
        assertTrue(picked.none { it.text.contains('\uFFFD') })
    }

    // ------------------------------------------------------------------
    // 装载端到端的 base Program（mini A 组全量编译，与 core-vm 同款夹具的精简版）
    // ------------------------------------------------------------------

    /** mini base：A_ONE/A_TWO/A_SUM，用 [ErbProgramCompiler] 全量编译（基线 0）。 */
    private fun compileMiniBase(): com.eratw.port.core.code.Program {
        val source = """
            @A_ONE
            CALL A_TWO(1)
            RETURN 0

            @A_TWO(N)
            RETURN 0

            @A_SUM
            #FUNCTION
            RETURNF 1
        """.trimIndent() + "\n"
        val sym = SymbolTable()
            .declareVariable("N", com.eratw.port.core.expr.OperandType.INT, 1)
        val parser = LineParser(sym)
        val reader = LogicalLineReader(LogicalLineReader.readAllLines(source.toByteArray(Charsets.UTF_8)), "mini.ERB")
        val groups = ArrayList<Pair<String, List<Statement>>>()
        var cur: String? = null
        val body = ArrayList<Statement>()
        while (true) {
            val line = reader.readLogicalLine() ?: break
            val stmt = parser.parseLogicalLine(line) ?: continue
            when {
                stmt is FuncLabelStmt -> {
                    if (cur != null) groups.add(cur to body.toList())
                    body.clear()
                    cur = stmt.name.uppercase()
                    body.add(stmt)
                }
                stmt is SharpStmt -> Unit
                cur != null -> body.add(stmt)
            }
        }
        if (cur != null) groups.add(cur to body.toList())
        val compiler = ErbProgramCompiler(callDefaults = emptyMap())
        val known = setOf("A_ONE", "A_TWO", "A_SUM")
        val exprCalls = ExprCallTable(
            intMethods = setOf("A_SUM"),
            nonMethodLabels = setOf("A_ONE", "A_TWO"),
        )
        for ((name, stmts) in groups) {
            val label = stmts.filterIsInstance<FuncLabelStmt>().first()
            compiler.compileFunction(
                FuncSig(name, label.paramNames.size, isMethod = name == "A_SUM", returnsStr = false, paramNames = label.paramNames),
                stmts, known, exprCalls,
            )
        }
        return compiler.build(groups.first().first)
    }

    private companion object {
        /** 合成 base sha（64 位小写十六进制；装载端到端用例里与补丁头逐字一致）。 */
        private val BASE_SHA = "a".repeat(64)
    }
}

/** [tempDir] 的顶层兜底（私有扩展）：JVM 测试专用。 */
private fun tempDir(): File = Files.createTempDirectory("eratw-modimport").toFile()