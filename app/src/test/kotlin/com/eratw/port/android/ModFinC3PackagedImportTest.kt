package com.eratw.port.android

import com.eratw.port.core.compile.ErbProgramCompiler
import com.eratw.port.core.compile.ExprCallTable
import com.eratw.port.core.compile.FuncSig
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.lexer.LogicalLineReader
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainAssembler
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestSource
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.stmt.FuncLabelStmt
import com.eratw.port.core.stmt.LineParser
import com.eratw.port.core.stmt.SharpStmt
import com.eratw.port.core.stmt.Statement
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ERAFIN-C3 成品包导入合同的定向单测：
 *
 * 1. **正向**：真编译产物两件套（真链产出 `.erbm` + `.manifest`，manifest `artifact-sha256`
 *    钉 `.erbm` 实际摘要——modgen DemoModuleBuild 先例的测试等价形）打成成品包 zip →
 *    合同导入成功 → `mods/rt2` 三件套在场（provenance 空名单 + manifest + 容器）+ 非
 *    `kojo-` packageId 照 manifest 自述身份落盘 + 装配可载（真 ModChainAssembler 出 plan）。
 * 2. **反向三证明**（契约 §3 实现判据）：篡改 `.erbm` 字节拒导（容器自身摘要闸）、
 *    缺 manifest 拒导（不识别成成品包，维持既有空解包失败面）、非法 packageId 拒导；
 *    另加摘要闸两向（manifest 钉错值拒 / 未钉空串拒）与身份自洽闸（manifest ↔ 容器
 *    packageId 不同拒）。
 *
 * 全部纯 JVM：两件套由真 [ErbModuleContainer] / [ManifestCodec] 编码，不伪造格式。
 */
class ModFinC3PackagedImportTest {

    /** modgen 风格的成品包自述身份（非 kojo- 前缀，验「落盘名 = manifest 自述」这一条）。 */
    private val modgenPackageId = "eratw.finc3.demo"

    // ------------------------------------------------------------------
    // 夹具：mini base + 真编译产物两件套
    // ------------------------------------------------------------------

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

    private fun miniBaseCounts(): PatchCompiler.BaseCounts =
        PatchCompiler.BaseCounts(
            instructionCount = 4000,
            functionCount = 4,
            statementCount = 100,
            stringCount = 10,
            longCount = 5,
            exprCount = 20,
        )

    /** 不引用 base 池条目/用户变量的补丁源（与 ModImportControllerTest 同源口径）。 */
    private val patchSource = """
        @B_HELLO
        CALL A_ONE(3)
        PRINTL HELLO_PATCH
        RETURN 0
    """.trimIndent() + "\n"

    /**
     * mini base Program（装配冒烟用；与 ModImportControllerTest.compileMiniBase 同源口径，
     * 测试文件间不共享 private，复制一份保持独立）。
     */
    /** mini base 源（与 ModImportControllerTest.compileMiniBase 同源口径，复制一份保持独立）。 */
    private val miniBaseSource = """
        @A_ONE
        CALL A_TWO(1)
        RETURN 0

        @A_TWO(N)
        RETURN 0

        @A_SUM
        #FUNCTION
        RETURNF 1
    """.trimIndent() + "\n"

    private fun compileMiniBase(): com.eratw.port.core.code.Program {
        val sym = SymbolTable()
            .declareVariable("N", com.eratw.port.core.expr.OperandType.INT, 1)
        val parser = LineParser(sym)
        val reader = LogicalLineReader(
            LogicalLineReader.readAllLines(miniBaseSource.toByteArray(Charsets.UTF_8)),
            "mini.ERB",
        )
        val groups = collectFunctionGroups(reader, parser)
        val compiler = ErbProgramCompiler(callDefaults = emptyMap())
        val known = setOf("A_ONE", "A_TWO", "A_SUM")
        val exprCalls = ExprCallTable(
            intMethods = setOf("A_SUM"),
            nonMethodLabels = setOf("A_ONE", "A_TWO"),
        )
        for ((name, stmts) in groups) {
            val label = stmts.filterIsInstance<FuncLabelStmt>().first()
            compiler.compileFunction(
                FuncSig(
                    name,
                    label.paramNames.size,
                    isMethod = name == "A_SUM",
                    returnsStr = false,
                    paramNames = label.paramNames,
                ),
                stmts,
                known,
                exprCalls,
            )
        }
        return compiler.build(groups.first().first)
    }

    /**
     * 源按 @标签切段（每条 = 标签名 → 语句表；与编译链出来的分组同形）。
     *
     * 单拆一型两个目的：`compileMiniBase` 压回 detekt `LongMethod` 60 行内；把「读到空行结束」
     * 写成 while 条件而不是 `while (true)` + break/continue（`LoopWithTooManyJumpStatements`）。
     */
    private fun collectFunctionGroups(
        reader: LogicalLineReader,
        parser: LineParser,
    ): List<Pair<String, List<Statement>>> {
        val groups = ArrayList<Pair<String, List<Statement>>>()
        var cur: String? = null
        val body = ArrayList<Statement>()
        var line = reader.readLogicalLine()
        while (line != null) {
            val stmt = parser.parseLogicalLine(line)
            when {
                stmt == null -> Unit
                stmt is FuncLabelStmt -> {
                    if (cur != null) groups.add(cur to body.toList())
                    body.clear()
                    cur = stmt.name.uppercase()
                    body.add(stmt)
                }
                stmt is SharpStmt -> Unit
                cur != null -> body.add(stmt)
            }
            line = reader.readLogicalLine()
        }
        if (cur != null) groups.add(cur to body.toList())
        return groups
    }

    /**
     * 真编译链产出一份 `.erbm`（真链：真 ModImportController 编 mini 源 → rt2 读回容器字节），
     * 再按 [packageId] 重编容器身份——这就是「作者用 modgen 编译产物两件套」的测试等价形
     * （DemoModuleBuild 先例：容器编码 + manifest 钉容器实际摘要）。
     */
    private fun packagedPairBytes(packageId: String): Pair<ByteArray, ByteArray> {
        val tmp = Files.createTempDirectory("eratw-finc3-build").toFile()
        try {
            val controller = ModImportController(
                modStore = ModStore(File(tmp, "mods")),
                baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
                gameRoot = tmp,
            )
            val outcome =
                controller.import(listOf(PickedModFile("KOJO_SRC.ERB", patchSource.toByteArray(Charsets.UTF_8))))
            val detail = (outcome as? ModImportOutcome.Imported)?.detail
                ?: throw AssertionError("真编译链应成功，实为 $outcome")
            val rt2 = File(File(tmp, "mods"), ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)
            val builtId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
            val module = ErbModuleContainer.decode(File(rt2, "$builtId.erbm").readBytes())
            val rebuilt = ErbModuleContainer.encode(
                ErbModuleContainer.ErbModule(
                    packageId,
                    module.allowSuperCalls,
                    module.program,
                    module.functions,
                    module.signatures,
                ),
            )
            // manifest 以真编译产物 descriptor 保真拷贝（externalCalls / override-functions 等
            // 段随行——链接期按它解析导入槽，缺段会 RelocationException），只换身份与摘要：
            // 摘要按 demo 先例钉 rebuilt 的实际值（成品包合同的身份锚）。
            val descriptor = ManifestCodec.decode(File(rt2, "$builtId.manifest").readBytes())
                .copy(packageId = packageId, version = "1.0.0", artifactSha256 = sha256Hex(rebuilt))
            return rebuilt to ManifestCodec.encode(descriptor)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 两件套（+ 可选附加条目）打成 zip：两件套恒在根级（成品包识别判据）。 */
    private fun packagedZip(
        manifestBytes: ByteArray,
        erbmBytes: ByteArray,
        vararg extras: Pair<String, ByteArray>,
    ): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            zip.putNextEntry(ZipEntry("$modgenPackageId.manifest"))
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("$modgenPackageId.erbm"))
            zip.write(erbmBytes)
            zip.closeEntry()
            for ((name, bytes) in extras) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }

    private fun packagedZipFor(packageId: String, vararg extras: Pair<String, ByteArray>): ByteArray {
        val (erbm, manifest) = packagedPairBytes(packageId)
        return packagedZip(manifest, erbm, *extras)
    }

    /** 导入事务的测试入口（识别失败折 AssertionError，用例内不再占 ThrowsCount 名额）。 */
    private fun importPackaged(tmp: File, zipBytes: ByteArray, displayName: String = "packaged.ZIP"): ModImportOutcome =
        importedOf(pairsOf(zipBytes), tmp, zipBytes, displayName)

    /**
     * 导入成功的 detail，失败折 `AssertionError`（`as?` + throw 的收敛点）。
     *
     * 单拆一份的理由：detekt `ThrowsCount` 限每函数 2 条 throw，正向用例里「导入成功 / 装配可载 /
     * 重导成功」三处断言各带一条会超限；断言原话由 [what] 传入，形态与展开写法逐字一致。
     */
    private fun importedDetailOf(outcome: ModImportOutcome, what: String): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail ?: throw AssertionError("$what，实为 $outcome")

    private fun pairsOf(zipBytes: ByteArray): ModFinC3PackagedImport.PackagedPair =
        requireNotNull(ModFinC3PackagedImport.packagedPairOf(zipBytes)) { "夹具 zip 应被识别成成品包形态" }

    private fun importedOf(
        pair: ModFinC3PackagedImport.PackagedPair,
        tmp: File,
        zipBytes: ByteArray,
        displayName: String,
    ): ModImportOutcome = ModFinC3PackagedImport.importPackagedModule(
        PickedModFile(displayName, zipBytes, fromArchive = true),
        pair,
        rt2Of(tmp),
        ModImportCopy.ZH,
        modRuntimeEnabled = true,
    )

    private fun rt2Of(tmp: File): File = File(File(tmp, "mods"), ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    // ------------------------------------------------------------------
    // 正向：真编译产物成品包导入 + 装配可载
    // ------------------------------------------------------------------

    @Test
    fun `成品包导入成功——三件套按manifest自述身份落盘且装配可载`() {
        val zip = packagedZipFor(modgenPackageId)
        val tmp = Files.createTempDirectory("eratw-finc3-ok").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val detail = importedDetailOf(outcome, "成品包应导入成功")
            assertEquals(modgenPackageId + ModPackageInventory.MODULE_EXTENSION, detail.patchFileName)
            assertTrue("成品包成功页标记应置位", detail.packagedModuleOnly)
            assertEquals("成品包无留档源，源名单恒空", emptyList<String>(), detail.sourceFileNames)
            assertTrue("函数读数应取自容器", detail.functionCount > 0)
            assertTrue("指令读数应取自容器", detail.instructionCount > 0)

            // 三件套在场：provenance 空名单（无留档源）+ manifest + 容器，按 manifest 自述身份命名。
            val rt2 = rt2Of(tmp)
            val sources = File(rt2, modgenPackageId + ModPackageInventory.SOURCES_EXTENSION)
            assertTrue("provenance 应落盘：$sources", sources.isFile)
            assertEquals("成品包 provenance 必须是空名单（单换行）", "\n", sources.readText(Charsets.UTF_8))
            val manifestFile = File(rt2, modgenPackageId + ModPackageInventory.MANIFEST_EXTENSION)
            assertTrue("manifest 应落盘", manifestFile.isFile)
            assertEquals(modgenPackageId, ManifestCodec.decode(manifestFile.readBytes()).packageId)
            val erbmFile = File(rt2, modgenPackageId + ModPackageInventory.MODULE_EXTENSION)
            assertTrue("容器应落盘", erbmFile.isFile)
            assertEquals(modgenPackageId, ErbModuleContainer.decode(erbmFile.readBytes()).packageId)

            // 装配可载（复用既有装配冒烟口径）：真 ModChainAssembler 出 plan，模块函数进链接产物。
            val module = ErbModuleContainer.decode(erbmFile.readBytes())
            val baseProgram = compileMiniBase()
            val chain = ModChainAssembler.assemble(
                listOf(ManifestSource("$modgenPackageId.manifest", manifestFile.readBytes())),
                listOf(module.toCompiledModule()),
                baseProgram,
                miniMetadata().functions.take(baseProgram.functions.size),
            )
            val plan = ModChainRuntimePlan.of(chain)
                ?: throw AssertionError("成品包在场必须走非旁路装配")
            assertTrue(
                "成品包模块函数必须进入链接产物",
                plan.program.functions.any { it.name == "B_HELLO" },
            )

            // 重导同包 = 原地替换（版本语义与既有导入链同口径）。
            val again = importPackaged(tmp, zip)
            val againDetail = importedDetailOf(again, "重导应成功")
            assertTrue(
                "同版本重导应报重装：${againDetail.versionChange}",
                againDetail.versionChange is ImportVersionChange.Reinstall,
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `成品包带资源与代码产物——两附加面照源链同口径收下`() {
        val codeMod = zipOf(
            "classes.dex" to ByteArray(64) { it.toByte() },
            "manifest.json" to """{"packageId":"$modgenPackageId"}""".toByteArray(Charsets.UTF_8),
        )
        val zip = packagedZipFor(
            modgenPackageId,
            "$modgenPackageId.assets/README.txt" to "finc3".toByteArray(Charsets.UTF_8),
            CODE_MOD_ENTRY to codeMod,
        )
        val tmp = Files.createTempDirectory("eratw-finc3-extras").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val detail = importedDetailOf(outcome, "成品包应导入成功")
            assertEquals("资源面应重挂收下一条", 1, detail.resourceEntryCount)
            val assets = File(rt2Of(tmp), modgenPackageId + ModPackageInventory.ASSET_DIR_SUFFIX)
            assertTrue("资源目录应落盘", assets.isDirectory)
            assertTrue(
                "资源字节应原样",
                File(assets, "README.txt").readBytes().contentEquals("finc3".toByteArray(Charsets.UTF_8)),
            )
            // 代码产物面：内容寻址只读件落 `code/<pkg>/<sha16>.mod`（与源链 ⑤‴ 同形状）。
            val store = ModCodeStore(rt2Of(tmp))
            val artifacts = store.list(modgenPackageId)
            assertEquals("代码产物应恰好落一件", 1, artifacts.size)
            assertFalse("代码产物必须只读", artifacts.single().file.canWrite())
            // 无 code.mod 的重导：反向清 `<pkg>.codeid` 身份侧车（与源链 finalizeCodeArtifact
            // 同口径）；代码产物本体按 ModCodeStore「只增不改、旧版本归 GC」纪律保留。
            val again = importPackaged(tmp, packagedZipFor(modgenPackageId))
            (again as? ModImportOutcome.Imported)?.detail
                ?: throw AssertionError("重导应成功，实为 $again")
            assertTrue(
                "重导去掉 code.mod 后身份侧车必须被清掉",
                !File(rt2Of(tmp), modgenPackageId + ModPackageInventory.CODE_ID_EXTENSION).exists(),
            )
            assertEquals(
                "代码产物本体按只增纪律保留",
                1,
                ModCodeStore(rt2Of(tmp)).list(modgenPackageId).size,
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 反向三证明 + 摘要/身份闸两向
    // ------------------------------------------------------------------

    @Test
    fun `反向——篡改erbm字节整包拒导且无落盘`() {
        val zip = packagedZipFor(modgenPackageId)
        // 定位 .erbm 条目并翻转一个字节（zip 内局部篡改 = 作者分发后被替换过的形态）。
        val mutated = zipOf(
            "$modgenPackageId.manifest" to zipEntryBytes(zip, "$modgenPackageId.manifest"),
            "$modgenPackageId.erbm" to zipEntryBytes(zip, "$modgenPackageId.erbm").also {
                it[10] = (it[10].toInt() xor 0x01).toByte()
            },
        )
        val tmp = Files.createTempDirectory("eratw-finc3-badbytes").toFile()
        try {
            val outcome = importPackaged(tmp, mutated)
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("篡改 .erbm 必须拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.IDENTITY, failed.kind)
            assertTrue("失败文案应点名容器摘要：${failed.message}", failed.message.contains(".erbm"))
            // 零落盘：三件套一件都不许在（fail-closed 在任何落盘之前）。
            assertFalse(rt2Of(tmp).isDirectory)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `反向——manifest钉错摘要值拒导`() {
        // 容器字节合法（解码能过），manifest 钉的是别的 64 hex = 说的与是的不同一份。
        val (erbm, manifest) = packagedPairBytes(modgenPackageId)
        val descriptor = ManifestCodec.decode(manifest)
        val wrongPin = ManifestCodec.encode(descriptor.copy(artifactSha256 = "0".repeat(64)))
        val zip = packagedZip(wrongPin, erbm)
        val tmp = Files.createTempDirectory("eratw-finc3-badpin").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("钉错摘要必须拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.IDENTITY, failed.kind)
            assertTrue("失败文案应点名摘要对账：${failed.message}", failed.message.contains("摘要"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `反向——manifest未钉摘要（空串）拒导`() {
        val (erbm, _) = packagedPairBytes(modgenPackageId)
        val unpinned = ManifestCodec.encode(ModuleDescriptor(packageId = modgenPackageId, version = "1.0.0"))
        val zip = packagedZip(unpinned, erbm)
        val tmp = Files.createTempDirectory("eratw-finc3-nopin").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("未钉摘要必须拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.IDENTITY, failed.kind)
            assertTrue("失败文案应点名未钉：${failed.message}", failed.message.contains("artifact-sha256"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `反向——manifest与容器packageId不一致拒导`() {
        val (erbm, manifest) = packagedPairBytes(modgenPackageId)
        val otherDescriptor = ManifestCodec.decode(manifest).copy(packageId = "eratw.finc3.other")
        val zip = packagedZip(ManifestCodec.encode(otherDescriptor), erbm)
        val tmp = Files.createTempDirectory("eratw-finc3-idclash").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("身份不一致必须拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.IDENTITY, failed.kind)
            assertTrue("失败文案应点名自洽：${failed.message}", failed.message.contains("自洽"))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `反向——非法packageId形状拒导`() {
        // 容器身份跟着换成非法形状（以符号开头，ModEnablement 形状闸拒）。
        val (erbm, manifest) = packagedPairBytes(modgenPackageId)
        val module = ErbModuleContainer.decode(erbm)
        val badId = "-badpkg"
        val rebuilt = ErbModuleContainer.encode(
            ErbModuleContainer.ErbModule(
                badId,
                module.allowSuperCalls,
                module.program,
                module.functions,
                module.signatures,
            ),
        )
        val descriptor = ManifestCodec.decode(manifest).copy(packageId = badId, artifactSha256 = sha256Hex(rebuilt))
        val zip = packagedZip(ManifestCodec.encode(descriptor), rebuilt)
        val tmp = Files.createTempDirectory("eratw-finc3-badid").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("非法 packageId 必须拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.IDENTITY, failed.kind)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `反向——只有erbm缺manifest的zip不识别维持既有失败面`() {
        val (erbm, _) = packagedPairBytes(modgenPackageId)
        val zip = zipOf("$modgenPackageId.erbm" to erbm)
        // 探针走真控制器（缝在 ModImportController.import 的空解包分支）：
        // 不识别 = 成品包合同不接手，维持既有 zipNoEntries 失败面（fail-closed 反向证明）。
        val tmp = Files.createTempDirectory("eratw-finc3-nomanifest").toFile()
        try {
            val controller = ModImportController(
                modStore = ModStore(File(tmp, "mods")),
                baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
                gameRoot = tmp,
            )
            val outcome = controller.import(listOf(PickedModFile("half.ZIP", zip)))
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("缺 manifest 必须拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.STORAGE, failed.kind)
            assertTrue(
                "维持既有空解包失败面：${failed.message}",
                failed.message.contains("没有可导入的 .ERB/.ERH 条目"),
            )
            assertFalse(rt2Of(tmp).isDirectory)
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `识别谓词——带源态ERB的zip不进成品包合同`() {
        // author pack（源态）有 ERB 无两件套：packagedPairOf 返回 null，由编译链接手。
        val zip = zipOf("main.ERB" to patchSource.toByteArray(Charsets.UTF_8))
        assertNull("源态 zip 不得被识别成成品包", ModFinC3PackagedImport.packagedPairOf(zip))
    }

    // ------------------------------------------------------------------
    // 反向——失败路径暂存面回收（G2 焦点②：失败回滚不留半装目录）
    // 源态链每个失败点都带交叉回收（ModImportController.kt:746-753 代码暂存失败、
    // :788-793 三件套落盘失败）；成品包合同在同样的失败点上不得泄漏暂存面。
    // ------------------------------------------------------------------

    @Test
    fun `反向——代码产物暂存失败时资源暂存目录不残留`() {
        // 资源面先暂存成功（staged != null 建出临时目录），随后代码产物暂存被运行期身份闸
        // 拒收（内层 manifest.json 的 packageId 与成品包身份不一致 = 配错包，IDENTITY）：
        // 资源暂存目录必须跟着 discard，不得等 24h 残留 GC 兜底
        //（ModPackageInventory.kt:198-205 的保守时间窗）。
        val badCodeMod = zipOf(
            "classes.dex" to ByteArray(64) { it.toByte() },
            "manifest.json" to """{"packageId":"eratw.finc3.other"}""".toByteArray(Charsets.UTF_8),
        )
        val zip = packagedZipFor(
            modgenPackageId,
            "$modgenPackageId.assets/README.txt" to "finc3".toByteArray(Charsets.UTF_8),
            CODE_MOD_ENTRY to badCodeMod,
        )
        val tmp = Files.createTempDirectory("eratw-finc3-codefail").toFile()
        try {
            val outcome = importPackaged(tmp, zip)
            val failed = outcome as? ModImportOutcome.Failed
                ?: throw AssertionError("坏代码产物必须整包拒导，实为 $outcome")
            assertEquals(ModImportFailureKind.IDENTITY, failed.kind)
            assertEquals(
                "资源暂存目录必须跟着回收：${packagedTmpDirsOf(rt2Of(tmp))}",
                emptyList<String>(),
                packagedTmpDirsOf(rt2Of(tmp)),
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun `反向——三件套落盘失败时暂存面不残留`() {
        // 资源 + 合法代码产物先暂存成功，随后三件套落盘在容器一步失败（rt2 里佯置一个非空
        // 目录挡住 .erbm 的换名：saveArtifact 的 replaceArtifactFile 对目录 rename 必败、
        // delete 非空目录也败，ModImportController.kt:1667-1673）：两个已建暂存面都必须
        // 跟着回收（源态链落盘失败同口径，ModImportController.kt:788-793）。
        val codeMod = zipOf(
            "classes.dex" to ByteArray(64) { it.toByte() },
            "manifest.json" to """{"packageId":"$modgenPackageId"}""".toByteArray(Charsets.UTF_8),
        )
        val zip = packagedZipFor(
            modgenPackageId,
            "$modgenPackageId.assets/README.txt" to "finc3".toByteArray(Charsets.UTF_8),
            CODE_MOD_ENTRY to codeMod,
        )
        val tmp = Files.createTempDirectory("eratw-finc3-savefail").toFile()
        try {
            val blocker = File(rt2Of(tmp), modgenPackageId + ModPackageInventory.MODULE_EXTENSION)
            assertTrue("佯置挡路目录应建成", blocker.mkdirs())
            assertTrue("佯置内容文件应建成", File(blocker, "occupied.txt").createNewFile())
            val outcome = importPackaged(tmp, zip)
            assertTrue("三件套落盘失败必须整包拒导，实为 $outcome", outcome is ModImportOutcome.Failed)
            assertEquals(
                "暂存面必须跟着回收：${packagedTmpDirsOf(rt2Of(tmp))}",
                emptyList<String>(),
                packagedTmpDirsOf(rt2Of(tmp)),
            )
        } finally {
            tmp.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val sink = ByteArrayOutputStream()
        ZipOutputStream(sink).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return sink.toByteArray()
    }

    private fun zipEntryBytes(zip: ByteArray, name: String): ByteArray {
        ZipInputStream(zip.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: throw AssertionError("zip 缺条目 $name")
                if (entry.name == name) return zin.readBytes()
            }
        }
        throw AssertionError("unreachable")
    }

    /** rt2 里的资源暂存残留目录名（断言用；`.tmp-` 子串即暂存面，正式件名不带该子串）。 */
    private fun packagedTmpDirsOf(rt2: File): List<String> =
        (rt2.listFiles { f -> f.isDirectory && ModImportAssets.TEMP_SUFFIX in f.name } ?: emptyArray<File>())
            .map { it.name }

    private companion object {
        /** 合成 base sha（64 位小写十六进制）。 */
        private val BASE_SHA = "a".repeat(64)
    }
}
