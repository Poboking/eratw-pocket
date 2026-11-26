package com.eratw.port.android

import com.eratw.port.core.compile.ConstantKeywordResolver
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.data.csv.GameData
import com.eratw.port.core.data.csv.GameDataLoader
import com.eratw.port.core.data.csv.UserKeywordTables
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.expr.OperandType
import com.eratw.port.core.frontend.RenameDictionary
import com.eratw.port.core.frontend.sizeConsts
import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.mod.PatchImportTranscoder
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.stmt.SymbolTable
import com.eratw.port.core.vars.DimParser
import com.eratw.port.platform.storage.ModStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * RT2 KOJOTRANS 波（wave/rt2-ktrans）：真实口上包过**导入转码链**全链探针
 * （CONTRACT-RT2-KOJOTRANS 判据 3）。
 *
 * ## 路线（与 f3kojo 直编路不同）
 *
 * 真源 ERB → [PatchCompiler]（窗口补丁产物）→ [PatchImportTranscoder]（零基化 ERTM1
 * `.erbm` + RT2 manifest）→ 生产 [ModChainAssembly.assemble]（真 4.981 base + 真
 * `program.meta` 签名段）→ `GameSession(modChain = plan)` → 真 VM 执行探针。
 * 设备导入的生产包装面（[ModImportController.import]：符号表灌装 + 配置链 + packageId
 * 派生 + 三件套落盘 + 读回校验）由判据 3-2 单独走一遍。
 *
 * ## 真源切片（契约判据 2；选择理由）
 *
 * `蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日记（简易版）.ERB`（41 KB / 631 行 / 28 个 @函数）。
 * 选它的理由：基座 4.981 语料把本文件**整文件包进 `[SKIPSTART]…[SKIPEND]`**（文件头注释
 * 自述：与 日记.ERB 同名冲突，默认不生效）⇒ 里面的函数全部不在 base 侧车里——是三包
 * 唯一能以**加性补丁**合法过 PatchCompiler I6-② 同名闸的真源函数组；去包装动作是源文件
 * 头注释明示的启用法（删除首尾 [SKIPSTART]/[SKIPEND] 两行）。切片再按 base 侧车同名过滤，
 * 把仍在 base 里的同名函数组剔掉（它们在 base 有同名非事件函数，加性形态会被 I6-② 拒）——
 * **剔除个数随基座语料变**：261017 基座收 5 个（留 23），261111 基座一个都不收（留 28），
 * 故本类断言只钉形状不变量（见 [assertSliceShape]），不钉个数。
 * 逐字节内容除去包装/剔除组外零改写；第三方文本不入库、不进测试常量（断言取运行期
 * 推导的首尾行/行数）。
 *
 * ## 判据 3-3 是另一条轴：整包 + 覆盖
 *
 * 3-1/3-2 走的是**加性切片**（不与基座撞名），永远碰不到「口上的本职＝改写基座」那一面。
 * 3-3 因此单独拿整包都与基座撞名的 `风神少女v0.01` 走生产导入路，钉 R7-① 放开后的
 * 覆盖装载（`overrideCount`、manifest 的 override-functions 段、`plan.hasOverrides`）。
 *
 * ## 环境门（三门，缺变量用例 Assume 跳过——不假绿的动态用例纪律）
 *
 * - `ERATW_ERBC_DIR`：**带签名段** fresh 产物目录（`program.erbc` + `program.meta`）；
 *   再生：`ERATW_CORPUS=<共享根>/eraTW4.981-261017-EAGLOV3/ERB ./gradlew
 *   --configure-on-demand :tools:erbcc:compileCorpus -PcompileMode=compat
 *   -PallowParseFailures=devtools/erbc-degraded-allowlist.tsv`（产物不入库）。
 * - `ERATW_KOJO_COMMUNITY`：kojo-community 根（或直接指到 `整合内容`）。
 * - `ERATW_GAMEDIR`：4.981 游戏根（CSV / emuera.config 数据面；只读）。
 */
class KojoTranscodeChainEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 环境门与真语料（lazy：一次装载全类共用）
    // ------------------------------------------------------------------

    /** 真产物装载面（程序 + 侧车 + 符号表 + 折叠闭包），整类共享一次。 */
    private class BaseFace(
        val erbcDir: Path,
        val gameDir: Path,
        val kojoContent: Path,
    ) {
        val metadata: BootMetadata = BootMetadata.read(erbcDir.resolve("program.meta"))
        val base = ProgramLoader.loadMapped(erbcDir.resolve("program.erbc"))
        val counts = PatchCompiler.BaseCounts(
            instructionCount = base.instructionCount,
            functionCount = base.functions.size,
            statementCount = base.statementCount,
            stringCount = base.stringCount,
            longCount = base.constantCount,
            exprCount = base.exprCount,
        )
        val baseKeys: Set<String> = metadata.functions.mapTo(HashSet()) { it.key }

        /** 容忍模式跳过的 global-dims 条目（如实留痕；与本探针切片无交集）。 */
        val skippedGlobalDims = ArrayList<String>()

        val symbolTable: SymbolTable = buildSymbolTable()

        val renameDic: Map<String, String>? = RenameDictionary.load(gameDir.resolve("CSV"))

        private fun gameDataOrNull(): GameData? =
            runCatching { GameDataLoader.load(gameDir.resolve("CSV")) }.getOrNull()

        private fun buildSymbolTable(): SymbolTable {
            val sym = SymbolTable()
            declareSidecarConsts(sym)
            gameDataOrNull()?.let { data ->
                for (def in data.registry.all()) {
                    val info = def.toVarInfo()
                    sym.declareVariable(
                        info.name, info.operandType, info.dimension,
                        info.isCharacterData, info.isConst,
                    )
                }
            }
            declareGlobalDims(sym)
            declareMethodFaces(sym)
            return sym
        }

        /** ERH `#DIM CONST` 的名字（侧车 const / str-const 两段）先登记为标量常量。 */
        private fun declareSidecarConsts(sym: SymbolTable) {
            val dimCfg = metadata.dimParseConfig
            for (name in dimCfg.intConstants.keys) {
                sym.declareVariable(name, OperandType.INT, 0, isCharacterData = false, isConst = true)
            }
            for (name in dimCfg.strConstants.keys) {
                sym.declareVariable(name, OperandType.STR, 0, isCharacterData = false, isConst = true)
            }
        }

        /** base 全局 `#DIM`（侧车 global-dims 原文重解析）；非严格形态容忍跳过并留痕。 */
        private fun declareGlobalDims(sym: SymbolTable) {
            val dimCfg = metadata.dimParseConfig
            val consts = sizeConsts(dimCfg.macros, dimCfg.intConstants)
            val cfg = dimCfg.copy(intConstants = consts)
            for (raw in metadata.globalDims) {
                try {
                    val keyword = raw.raw.substringBefore(' ', raw.raw).uppercase()
                    val spec = DimParser.parse(
                        keyword, raw.raw.substringAfter(' ', "").trim(),
                        isPrivate = false, config = cfg,
                    )
                    sym.declareVariable(
                        spec.name,
                        if (spec.isStr) OperandType.STR else OperandType.INT,
                        spec.dimension,
                        isCharacterData = spec.isCharaData,
                        isConst = spec.isConst,
                    )
                } catch (e: Exception) {
                    // 容忍模式（生产 ModImportController 同款）：非严格形态逐条跳过，
                    // 不整链死；跳过条目带原文留痕（不静默），与本探针切片无交集。
                    skippedGlobalDims += "${raw.file}:${raw.line} ${raw.raw.take(50)}" +
                        " || ${(e.message ?: e::class.simpleName)?.take(120)}"
                }
            }
        }

        /** 方法三分 + FUNCTIONF 浮点 + base 非事件函数存在性（生产同口径）。 */
        private fun declareMethodFaces(sym: SymbolTable) {
            val methodTypes = metadata.knownMethodTypes()
            for ((name, type) in methodTypes) {
                when (type) {
                    OperandType.INT -> sym.declareFunction(name, OperandType.INT)
                    OperandType.STR -> sym.declareFunction(name, OperandType.STR)
                    else -> Unit
                }
            }
            for (m in metadata.functions) {
                if (m.isMethod && m.returnsFloat) sym.declareFunction(m.key, OperandType.FLOAT)
            }
            val methodKeys = methodTypes.keys
            for (m in metadata.functions) {
                if (m.isEvent || m.isMethod || m.key in methodKeys) continue
                sym.declareFunction(m.key, OperandType.INT)
            }
        }

        /** 解析期常量关键字折叠闭包（ModImportController keywordLookup 的同源数据面）。 */
        fun keywordLookup(): ((String, Int, String) -> Long?)? {
            val gameData = gameDataOrNull() ?: return null
            val systemNames = gameData.registry.all().mapTo(HashSet()) { it.name.uppercase() }
            val erdTables = runCatching { UserKeywordTables.foldSnapshot(gameDir, systemNames) }
                .getOrDefault(emptyMap())
            val tables = erdTables + gameData.registry.systemKeywordTables()
            return ConstantKeywordResolver(gameData.registry, tables)::resolve
        }
    }

    private val face: BaseFace? by lazy {
        val erbcDir = envPath("ERATW_ERBC_DIR")?.takeIf {
            Files.isRegularFile(it.resolve("program.erbc")) && Files.isRegularFile(it.resolve("program.meta"))
        }
        val kojoRoot = envPath("ERATW_KOJO_COMMUNITY")
        val kojoContent = kojoRoot?.let { if (Files.isDirectory(it.resolve("整合内容"))) it.resolve("整合内容") else it }
        val gameDir = envPath("ERATW_GAMEDIR")?.takeIf { Files.isDirectory(it.resolve("CSV")) }
        val diaryOk = kojoContent != null && Files.isRegularFile(kojoContent.resolve(DIARY_ERB))
        if (erbcDir == null || !diaryOk || gameDir == null) null else BaseFace(erbcDir, gameDir, kojoContent!!)
    }

    private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun envPath(name: String): Path? = env(name)?.let { Path.of(it) }

    /** 三门齐备才实跑（JUnit 动态用例纪律：缺变量 = Assume 跳过、不生成实跑用例，不假绿）。 */
    private fun faceOrSkip(): BaseFace {
        val f = face
        assumeTrue(
            "三门齐备（ERATW_ERBC_DIR 带签名段产物 + ERATW_KOJO_COMMUNITY 真源 + " +
                "ERATW_GAMEDIR CSV）才实跑；实得 erbc=${env("ERATW_ERBC_DIR")?.let { "set" } ?: "unset"} " +
                "kojo=${env("ERATW_KOJO_COMMUNITY")?.let { "set" } ?: "unset"} " +
                "gamedir=${env("ERATW_GAMEDIR")?.let { "set" } ?: "unset"}",
            f != null,
        )
        return f!!
    }

    private fun fileSha256Hex(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readSourceLines(face: BaseFace): List<String> =
        Files.readAllLines(face.kojoContent.resolve(DIARY_ERB), Charsets.UTF_8)
            .joinToString("\n")
            .removePrefix("\uFEFF")
            .lines()

    // ------------------------------------------------------------------
    // 真源切片（去 SKIPSTART 包装 + base 同名组过滤）
    // ------------------------------------------------------------------

    private data class Slice(
        val relPath: String,
        val text: String,
        val kept: List<String>,
        val dropped: List<String>,
    )

    /**
     * 去包装 + 加性过滤后的真源补丁文本。除「删 [SKIPSTART]/[SKIPEND] 两行标记」与
     * 「剔除 base 同名函数组」外逐字节保真（见类 KDoc 切片理由）。
     */
    private fun diarySlice(face: BaseFace): Slice {
        val out = ArrayList<String>()
        val kept = ArrayList<String>()
        val dropped = ArrayList<String>()
        var curKept = false
        for (line in readSourceLines(face)) {
            val t = line.trim()
            if (t == "[SKIPSTART]" || t == "[SKIPEND]") continue // 源文件头注释明示的启用法
            if (t.startsWith("@")) {
                val name = t.substring(1).substringBefore('(').substringBefore(' ').trim().uppercase()
                curKept = name !in face.baseKeys
                if (curKept) kept += name else dropped += name
            }
            if (curKept) out += line
        }
        return Slice(DIARY_ERB.substringAfter('/'), out.joinToString("\n") + "\n", kept, dropped)
    }

    /** 真源某函数组的 PRINT 家族行文本（运行期推导期望屏上行，不抄全文进常量）。 */
    private fun printedLinesOf(face: BaseFace, key: String): List<String> {
        val lines = readSourceLines(face)
        val start = lines.indexOfFirst { it.trim().equals("@$key", ignoreCase = true) }
        require(start >= 0) { "真源找不到 @$key" }
        return lines.drop(start + 1)
            .takeWhile { !it.trim().startsWith("@") && it.trim() != "[SKIPEND]" }
            .filter { it.trim().startsWith("PRINTFORM", ignoreCase = true) }
            .map { it.trim().substringAfter(' ').trim() }
    }

    /** 临时游戏根：`erbc.v1/program.meta` 指真 meta（同卷硬链，跨卷退复制）。 */
    private fun gameRoot(face: BaseFace, name: String): File {
        val root = tmp.newFolder(name)
        val dst = File(root, "erbc.v1/program.meta")
        dst.parentFile?.mkdirs()
        val src = face.erbcDir.resolve("program.meta")
        if (runCatching { Files.createLink(dst.toPath(), src) }.isFailure) {
            Files.copy(src, dst.toPath())
        }
        return root
    }

    private fun session(root: File, plan: ModChainRuntimePlan?, face: BaseFace): GameSession =
        GameSession(
            gameRoot = root.toPath(),
            program = face.base,
            metadata = face.metadata,
            inputSinkOverride = NoopInputSink,
            modChain = plan,
        )

    /** 切片 → PatchCompiler（生产同源数据面）；返回补丁产物。 */
    private fun compileSlice(face: BaseFace, slice: Slice): PatchCompiler.PatchResult =
        PatchCompiler(
            face.metadata,
            face.counts,
            face.symbolTable,
            renameDic = face.renameDic,
            constantKeywordLookup = face.keywordLookup(),
        ).compile(listOf(PatchCompiler.SourceFile(slice.relPath, slice.text)), slice.kept.first())

    /** 补丁产物 → 转码（本波修过的转码器；包名由调用方定）。 */
    private fun transcodeSlice(
        face: BaseFace,
        compiled: PatchCompiler.PatchResult,
        packageId: String,
    ): PatchImportTranscoder.Transcoded = PatchImportTranscoder.transcode(
        compiled,
        face.counts,
        face.metadata.functions.associate { it.index to it.key },
        packageId,
    )

    /** 转码产物 → 生产装配（真 base + 真 program.meta 签名段）。 */
    private fun assembleChain(root: File, face: BaseFace): ModChainRuntimePlan =
        requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, face.base, face.metadata.functions)))

    // ------------------------------------------------------------------
    // 判据 3-1：真源 → PatchCompiler → 转码 → 生产装配 → 真 VM（手工链，逐段断言）
    // ------------------------------------------------------------------

    @Test
    fun `判据3-1_真源日记页函数全链_编译转码零基化生产装配_真VM执行`() {
        val face = faceOrSkip()
        val slice = diarySlice(face)
        assertSliceShape(face, slice)

        val compiled = compileSlice(face, slice)
        assertTrue(
            "真源切片必须零函数级作废：${compiled.parseWarnings}",
            compiled.parseWarnings.none { it.contains("已跳过") },
        )
        assertEquals(
            "补丁函数表 = 切片全集（一条不许静默丢）", slice.kept.size, compiled.funcMetas.size,
        )

        val transcoded = transcodeSlice(face, compiled, PACKAGE)
        assertTranscodedShape(face, slice, transcoded)

        val root = gameRoot(face, "ktrans-chain")
        writeAssets(root, PACKAGE, transcoded)
        assertSignatureSection(root)
        val plan = assembleChain(root, face)
        assertFalse("纯加性切片不构成覆盖", plan.hasOverrides)
        assertNull("无覆盖走直调快路径", plan.functionDispatch)

        val s = session(root, plan, face)
        assertTrue(
            "manifest 私有 #DIM 必须经 plan 注册成功：${s.privateDimFailures}",
            s.privateDimFailures.isEmpty(),
        )
        assertEquals("K16_1 真源 RETURN 0", 0L, s.runtime.callFunction("DIARY_色情特化_K16_1"))
        assertEquals(
            "七行日记逐行上屏（运行期从真源推导，不贴全文常量）",
            printedLinesOf(face, "DIARY_色情特化_K16_1"),
            s.console.loggedLines(),
        )
        assertEquals(
            "K16_1_HAPPEN 真源 RETURN 0（CHARA=16 不命中 SIF）",
            0L, s.runtime.callFunction("DIARY_色情特化_K16_1_HAPPEN"),
        )
    }

    /** 切片形状钉死（真源事实；变了说明基座语料或源包变了，先人工核再动断言）。 */
    private fun assertSliceShape(face: BaseFace, slice: Slice) {
        // 换代漂移纪律（2026-11-17 重锚）：被剔/保留的**个数**随基座语料变 —— 261017 基座
        // 收了那 5 个 DIARY 键（剔 5 留 23），261111 基座不再含它们（剔 0 留 28）。个数写死
        // 就等于把探针冻在某一版语料上，换根本判红还静默 skip 到没人看见。这里只钉与语料
        // 无关的两条形状不变量：分组判据 = 「键在不在基座函数表」，且切片必须真带上 K16_1 那一对。
        assertTrue(
            "被剔组必须个个都在基座函数表里，实得：${slice.dropped}",
            slice.dropped.all { it in face.baseKeys },
        )
        assertTrue(
            "保留组必须个个都不在基座函数表里（否则补丁同名撞 I6），实得：${slice.kept.take(3)}",
            slice.kept.none { it in face.baseKeys },
        )
        assertTrue("切片必须非空（真源文件的 @ 头全被剔掉 = 探针失效）", slice.kept.isNotEmpty())
        assertTrue(
            "K16_1 与 K16_1_HAPPEN 必须在切片内",
            "DIARY_色情特化_K16_1" in slice.kept && "DIARY_色情特化_K16_1_HAPPEN" in slice.kept,
        )
    }

    /** 转码产物形状：零基线、函数集同序、无截断入口/导入表、私有 DIM 进 descriptor。 */
    private fun assertTranscodedShape(
        face: BaseFace,
        slice: Slice,
        transcoded: PatchImportTranscoder.Transcoded,
    ) {
        assertEquals("模块 functionBase 归零", 0, transcoded.module.program.functionBase)
        assertEquals("模块 codeBase 归零", 0, transcoded.module.program.codeBase)
        assertEquals(
            "模块函数表 = 切片全集同序（本切片无事件成员 ⇒ 无合成入口截断面）",
            slice.kept,
            transcoded.module.functions.map { it.key },
        )
        // 换代漂移纪律（同 [assertSliceShape]）：保留组个数随基座变，切片里是否存在静态
        // CALL 也随之变（261111 基座不再收那 5 个 DIARY 键 ⇒ 它们回到切片并带来对基座函数的
        // 静态 CALL ⇒ 导入表非空）。因此只钉两条与语料无关的判据。
        assertTrue(
            "切片全是真源裸函数名 ⇒ 不含 9 个事件名 ⇒ 无合成入口截断面",
            transcoded.droppedEventEntries.isEmpty(),
        )
        assertTrue(
            "导入表只能是基座函数名（转码按 base 目录取名，查不到就该 fail-closed 抛）：${transcoded.imports}",
            transcoded.imports.all { it in face.baseKeys },
        )
        assertTrue(
            "HAPPEN 组的 #DIM DYNAMIC 必须进 descriptor",
            transcoded.descriptor.privateDims.isNotEmpty(),
        )
    }

    /** 容器 + manifest 落 `mods/rt2`（生产单向配对形状）。 */
    private fun writeAssets(root: File, packageId: String, transcoded: PatchImportTranscoder.Transcoded) {
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        File(rt2, "$packageId.erbm").writeBytes(ErbModuleContainer.encode(transcoded.module))
        File(rt2, "$packageId.manifest").writeBytes(ManifestCodec.encode(transcoded.descriptor))
    }

    /** fresh 产物签名段在场（判据 3 要求真 4.981 base + 真签名段）。 */
    private fun assertSignatureSection(root: File) {
        val baseSig = ModChainAssembly.signatureDirectoryOfFile(File(root, "erbc.v1/program.meta"))
        assertTrue(
            "fresh 产物签名段必须在场（判据 3 要求真 4.981 base + 真签名段）",
            baseSig("CHARA_DIARY_PAGESETTING").isNotEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 3-2：生产导入路（ModImportController.import 的编译→转码→三件套落盘）
    // ------------------------------------------------------------------

    @Test
    fun `判据3-2_生产导入路_同切片经ModImportController_三件套落盘过同一条装配`() {
        val face = faceOrSkip()
        val slice = diarySlice(face)
        val root = gameRoot(face, "ktrans-import")

        val controller = ModImportController(
            modStore = ModStore(File(root, "mods")),
            baseSource = { _ ->
                BaseCompileContext(
                    face.metadata,
                    face.counts,
                    fileSha256Hex(face.erbcDir.resolve("program.erbc")),
                    File(face.gameDir.toFile(), "CSV"),
                )
            },
            gameRoot = root,
        )
        val outcome = controller.import(
            listOf(PickedModFile(slice.relPath, slice.text.toByteArray(Charsets.UTF_8))),
        )
        val detail = requireNotNull((outcome as? ModImportOutcome.Imported)?.detail) {
            "生产导入必须成功：$outcome"
        }
        assertEquals(
            "生产导入的补丁函数数 = 切片全集", slice.kept.size, detail.functionCount,
        )
        assertEquals("生产导入必须零覆盖（切片是加性过滤后的真源）", 0, detail.overrideCount)
        assertEquals("entry = 切片首函数（生产 firstFunctionNameOf 同口径）", slice.kept.first(), detail.entryFunction)
        assertTrue(
            "生产导入零函数级作废：${detail.parseWarnings}",
            detail.parseWarnings.none { it.contains("已跳过") },
        )

        val packageId = controller.packageIdFor(listOf(slice.relPath))
        val rt2 = File(root, "mods/rt2")
        assertTrue("ERTM1 容器已落盘", File(rt2, "$packageId.erbm").isFile)
        assertTrue("RT2 manifest 已落盘", File(rt2, "$packageId.manifest").isFile)
        assertTrue("provenance 已落盘", File(rt2, "$packageId.sources").isFile)

        val plan = assembleChain(root, face)
        assertFalse("纯加性不构成覆盖", plan.hasOverrides)
        val s = session(root, plan, face)
        assertTrue("生产包的私有 #DIM 注册零失败：${s.privateDimFailures}", s.privateDimFailures.isEmpty())
        assertEquals("生产包 K16_1 真源 RETURN 0", 0L, s.runtime.callFunction("DIARY_色情特化_K16_1"))
        assertEquals(
            "生产导入路产出的包在同一装配与会话面上逐位同内容",
            printedLinesOf(face, "DIARY_色情特化_K16_1"),
            s.console.loggedLines(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 1（关态半边）：资产在场但总闸关 ⇒ 零接触旁路，模块函数不可达
    // ------------------------------------------------------------------

    @Test
    fun `关态零接触_assemble返回null_模块函数在会话不可达`() {
        val face = faceOrSkip()
        val slice = diarySlice(face)
        val compiled = compileSlice(face, slice)
        val transcoded = transcodeSlice(face, compiled, PACKAGE)
        val root = gameRoot(face, "ktrans-off")
        writeAssets(root, PACKAGE, transcoded)

        assertNull(
            "关态 ⇒ assemble 返回 null（一个字节都不读）",
            ModChainAssembly.assemble(ChainAssemblyInputs(root, false, face.base, face.metadata.functions)),
        )
        val s = session(root, null, face)
        try {
            s.runtime.callFunction("DIARY_色情特化_K16_1")
            fail("关态下模块函数必须不可达（base 无此函数）")
        } catch (e: CodeEE) {
            assertTrue(
                "报错须点名缺失函数，实得：${e.message}",
                e.message!!.contains("DIARY_色情特化_K16_1"),
            )
        }
    }

    // ------------------------------------------------------------------
    // 判据 5：配对 fail-closed —— 容器在场 manifest 缺失 ⇒ 整包拒点名
    // ------------------------------------------------------------------

    @Test
    fun `判据5_配对failclosed_孤儿容器缺manifest_整包拒点名`() {
        val face = faceOrSkip()
        val slice = diarySlice(face)
        val compiled = compileSlice(face, slice)
        val transcoded = transcodeSlice(face, compiled, PAIR_PROBE_PACKAGE)
        val root = gameRoot(face, "ktrans-pair")
        // 包名不带 kojo- 前缀：GC 的半成品清理只作用于导入产物形状（ModPackageInventory
        // packageIdOf 的前缀闸），让孤儿容器活到配对闸（与 ModChainAssetPairTest 同手法）。
        val rt2 = File(root, "mods/rt2").apply { mkdirs() }
        File(rt2, "$PAIR_PROBE_PACKAGE.erbm").writeBytes(ErbModuleContainer.encode(transcoded.module))
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, face.base, face.metadata.functions))
            fail("容器在场但同 packageId manifest 缺失必须整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名配对不齐，实得：${e.message}", e.message!!.contains("配对不齐"))
        }
    }

    // ------------------------------------------------------------------
    // 判据 3-3（R7-①）：真口上包**整包**过生产导入路 —— 撞名片段按覆盖装载，不再整包拒
    // ------------------------------------------------------------------

    /**
     * 验收对象 = `整合内容` 下的**全部**真口上包，逐个走一遍生产导入路。
     *
     * 三包在闸面上是两种形态，必须同时钉住（缺一侧就是没验的墙）：
     * - `风神少女v0.01` / `摩卡橙ver0.05` = **整包都与基座撞名**（基座 261111 已在
     *   `口上・メッセージ関連/個人口上/…` 收了同一批函数；实测 506/506、761/761 唯一函数名
     *   撞基座）。放开 I6-② 之前它们在 `PatchCompiler` 步骤 5 的第一个撞名函数上就整包判死，
     *   是「PC 能用、本项目导不进」的最干净样本；现在必须整包 Imported 且把撞名片段
     *   全量声明成覆盖（`overrideCount > 0` + manifest 的 override-functions 段 + `hasOverrides`）。
     * - `蕾米莉亚色情特化v1.3` = **与基座零撞名**的加性整包（58 ERB / 13 MB，另由
     *   `KojoPackageAcceptanceSmokeTest` 在 core-vm 侧编成 966 函数）。它必须照常导入成功，
     *   用来钉「放开覆盖没有把加性路撞坏」。
     *
     * 第三方字节只读、不入库、不抄进测试常量；读数一律运行期推导。
     */
    /**
     * 一个真包的整包导入 + 产物自证 + 装配非旁路：返回（包名, 导入读数, 装配 plan）。
     * 抽出来是为了让判据 3-3 的循环体只剩「逐包跑一遍」这一件事。
     */
    private fun importWholePack(
        face: BaseFace,
        packDir: Path,
        tag: String,
    ): Triple<String, ImportedPatchDetail, ModChainRuntimePlan> {
        val name = packDir.fileName.toString()
        val picked = Files.list(packDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter {
                    val n = it.fileName.toString().uppercase()
                    n.endsWith(".ERB") || n.endsWith(".ERH")
                }
                .map { it.fileName.toString() to Files.readAllBytes(it) }
                .toList()
        }.map { PickedModFile(it.first, it.second) }
        assertTrue("包 $name 内没有 .ERB/.ERH：$packDir", picked.isNotEmpty())

        val root = gameRoot(face, "ktrans-33-$tag")
        val controller = ModImportController(
            modStore = ModStore(File(root, "mods")),
            baseSource = { _ ->
                BaseCompileContext(
                    face.metadata,
                    face.counts,
                    fileSha256Hex(face.erbcDir.resolve("program.erbc")),
                    File(face.gameDir.toFile(), "CSV"),
                    // 生产同源：覆盖声明的指纹取 production 读的那份 program.meta 签名段。
                    ModChainAssembly.signatureDirectoryOfFile(File(root, "erbc.v1/program.meta")),
                )
            },
            gameRoot = root,
        )
        val outcome = controller.import(picked)
        val detail = requireNotNull((outcome as? ModImportOutcome.Imported)?.detail) {
            "真包整包必须导入成功（$name），实得：$outcome"
        }

        // 落盘产物自证：manifest 的 override-functions 段与导入读数逐项一致（不是内存里说说）。
        val manifest = File(root, "mods/rt2/${detail.patchFileName.removeSuffix(".erbm")}.manifest")
        assertTrue("manifest 必须随容器落盘：${manifest.absolutePath}", manifest.isFile)
        val decoded = ManifestCodec.decode(Files.readAllBytes(manifest.toPath()))
        assertEquals(
            "descriptor 的覆盖声明数 = 导入读数（$name）",
            detail.overrideCount,
            decoded.overrideFunctions.size,
        )
        return Triple(name, detail, assembleChain(root, face))
    }

    @Test
    fun `判据3-3_真包逐个整包导入_撞名片段按覆盖装载`() {
        val f = faceOrSkip()
        val packs = Files.list(f.kojoContent).use { stream ->
            stream.filter { Files.isDirectory(it) }.map { it }.toList().sortedBy { it.fileName.toString() }
        }
        assertTrue("整合内容下必须有真包：${f.kojoContent}", packs.isNotEmpty())

        var declaredAny = 0
        for ((i, packDir) in packs.withIndex()) {
            val (name, detail, plan) = importWholePack(f, packDir, "p$i")
            assertEquals(
                "hasOverrides 必须与覆盖声明数同真假（$name）",
                detail.overrideCount > 0,
                plan.hasOverrides,
            )
            declaredAny += detail.overrideCount
            println(
                "[kojo-override] $name：源 ${detail.sourceFileNames.size} / 函数 ${detail.functionCount} / " +
                    "覆盖 ${detail.overrideCount} / 告警 ${detail.parseWarnings.size}",
            )
        }
        assertTrue("撞名片段里至少有一个被声明成覆盖（否则本闸没吃到覆盖路）", declaredAny > 0)
    }

    private companion object {
        /** 真源相对 `整合内容` 的入口文件（第三方字节只读，不入库）。 */
        const val DIARY_ERB = "蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日记（简易版）.ERB"


        /** 判据 3-1/关态用的手工包名（kojo- 前缀 = 导入产物形状；容器+manifest 齐 ⇒ GC 不吃）。 */
        const val PACKAGE = "kojo-ktransdiary"

        /** 判据 5 的孤儿容器包名（故意不带 kojo- 前缀，躲开 GC 半成品清理、活到配对闸）。 */
        const val PAIR_PROBE_PACKAGE = "ktrans-pair-probe"
    }
}
