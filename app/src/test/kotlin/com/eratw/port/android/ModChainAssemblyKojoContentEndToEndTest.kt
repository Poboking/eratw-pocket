package com.eratw.port.android

import com.eratw.port.core.code.Program
import com.eratw.port.core.host.NoopInputSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.mod.ModChainRuntimePlan
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.GameSession
import com.eratw.port.erbcc.compile.ErbModuleCompiler
import com.eratw.port.erbcc.compile.SignatureExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * RT2 F3 波（wave/rt2-f3kojo）：端到端口上覆写验收的**真第三方内容面**。
 *
 * 机制面已由 `ModChainAssemblySessionEndToEndTest` 用合成模块全判据达成；本文件把
 * **群友整合口上包里的真函数**（`$ERATW_ROOT/kojo-community/整合内容` 三包，第三方字节
 * 不入库、源包只读）切出来编成真 `.erbm` 模块，过生产 `ModChainAssembly.assemble`
 * → 真 base 语料链接 → `GameSession(modChain = plan)` → 真 VM 执行，断屏上输出切换。
 *
 * ## 为什么是「覆写 base 侧口上函数」而不是「与基座同名覆盖基座」
 *
 * 阶段一取证：三包 2,451 个函数与基座**零同名碰撞**（编译器单 Program 重复声明硬失败
 * `ErbProgramCompiler` 旁证），口上与基座的集成面是 `KOJO_MESSAGE.ERB` 的 `TRYCALLFORM`
 * 动态按名派发。玩家实况 = 全量语料做 base（口上函数已被烧进 base），RT2 mod 覆写
 * base 侧口上函数、`CALL SUPER` 回烧进原文。本文件按这个形状断。
 *
 * ## 三夹具（契约 CONTRACT-RT2-F3KOJO §1.5；源文本从环境门指向的真源运行期抽取）
 *
 * 1. `M_KOJO_风神少女_MESSAGE_COUNTER_K29_20` 两句 PRINTFORML **行序对调** ⇒ 屏上两行反序；
 * 2. 同函数体前插 `CALL SUPER` ⇒ base 两行（原文序）→ mod 两行（对调序）四行次序钉死；
 * 3. `DIARY_色情特化_K16_1` 全函数 verbatim 加性（base 无此函数）⇒ 七行上屏 + RETURN 0。
 *
 * 断言只取首尾行与行数（口上文本含成人向内容，不把全文常量写进测试代码）；期望文本
 * 由真源行运行期推导，不手抄。
 *
 * ## 环境门（缺变量不生成用例）
 *
 * - `ERATW_ERBC_DIR`：**带签名段**的 fresh 产物目录（`program.erbc` + `program.meta`）；
 * - `ERATW_KOJO_COMMUNITY`：`kojo-community` 根（或直接指到 `整合内容`）；
 * - `ERATW_GAMEDIR` 可选，仅作读侧根提示；本文件一律用临时根承载 `mods/rt2` 与真 meta，
 *   真语料与口上源只读。
 *
 * base 产物再生（compat 档，4.981 语料；产物不入库）：
 * `ERATW_CORPUS=<共享根>/eraTW4.981-261017-EAGLOV3/ERB ./gradlew --configure-on-demand
 * :tools:erbcc:compileCorpus -PcompileMode=compat
 * -PallowParseFailures=devtools/erbc-degraded-allowlist.tsv`
 *
 * ## 签名真闸
 *
 * base 侧签名目录走 `ModChainAssembly.signatureDirectoryOfFile`（真 `program.meta` 尾读）；
 * 模块侧规范串由写侧同一对象 [SignatureExport] 从**真函数头**重算（零参、无 `#FUNCTION`
 * 标注 ⇒ 返回通道 UNKNOWN，与 base 静态推断同口径），断言两侧指纹相等 = 覆写放行。
 * 契约预写「零参非方法 = `SIG1|ret:I`」与真 base 目录不符（实测 `SIG1|ret:U`，这些函数
 * 只经 TRYCALLFORM 动态派发、静态推断不到返回通道）——硬写 I 会被签名闸整包拒，
 * 本文件按真目录口径走 U，已在交付登记。
 */
class ModChainAssemblyKojoContentEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 真源与真语料
    // ------------------------------------------------------------------

    private data class Corpus(val erbc: Path, val kojo: Path)

    private fun corpusOrNull(): Corpus? {
        val erbcDir = env("ERATW_ERBC_DIR")
        val kojoRoot = env("ERATW_KOJO_COMMUNITY")
        if (erbcDir == null || kojoRoot == null) return null
        val erbc = Path.of(erbcDir)
        val kojo = Path.of(kojoRoot)
        val content = if (Files.isDirectory(kojo.resolve("整合内容"))) kojo.resolve("整合内容") else kojo
        val usable = Files.isRegularFile(erbc.resolve("program.erbc")) &&
            Files.isRegularFile(erbc.resolve("program.meta")) &&
            Files.isRegularFile(content.resolve(AYA_ERB)) &&
            Files.isRegularFile(content.resolve(REMILIA_ERB))
        return if (usable) Corpus(erbc, content) else null
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun bodyOf(corpus: Corpus, erbRelative: String, key: String): List<String> {
        val file = corpus.kojo.resolve(erbRelative)
        val lines = Files.readAllLines(file, Charsets.UTF_8)
        val start = lines.indexOfFirst { it.trim().equals("@$key", ignoreCase = true) }
        require(start >= 0) { "真源 [${file.fileName}] 找不到 @$key" }
        return lines.drop(start + 1)
            .takeWhile { !it.trim().startsWith("@") }
            .map { it.trim() }
            // 切片止于下一个 `@` 头；段间 `;` 注释块（下一段的标题）不属于本函数体，
            // 最小模块前端不认注释行，带进去会被 LineParser 当代码行判红。
            .filter { it.isNotEmpty() && !it.startsWith(";") }
    }

    /** PRINT 家族行（含 PRINTFORMDL/DW 变体）；断言用运行期推导，不抄全文。 */
    private fun printLines(body: List<String>): List<String> =
        body.filter { it.startsWith("PRINTFORM", ignoreCase = true) }

    /** PRINT 行的屏上文本（去掉语句名，只留标量参数）。 */
    private fun printedText(line: String): String {
        val op = line.substringBefore(' ')
        require(op.startsWith("PRINTFORM", ignoreCase = true)) { "不是 PRINT 行：$line" }
        return line.substring(op.length).trim()
    }

    /**
     * 真函数头 → 签名规范串（[SignatureExport] 同一渲染器）。
     *
     * 规则与 base 编译档同源：形参取函数头括号内声明序；无 `#FUNCTION` 标注 ⇒ 返回通道
     * UNKNOWN（base 侧 `scan.funcTypes` 对只经 TRYCALLFORM 派发的口上函数同为 null）。
     */
    private fun headerCanonical(header: String): String {
        val open = header.indexOf('(')
        val paramNames = if (open < 0) {
            emptyList()
        } else {
            header.substring(open + 1).substringBefore(')')
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        val params = paramNames.map { SignatureExport.paramOf(it, it, null) }
        return SignatureExport.render(
            SignatureExport.FunctionSignature(params, SignatureExport.ReturnChannel.UNKNOWN),
        )
    }

    // ------------------------------------------------------------------
    // 夹具：真 base 语料 + 临时根（真 meta 只读链接/复制，mods/rt2 落临时根）
    // ------------------------------------------------------------------

    private fun baseProgram(corpus: Corpus) = ProgramLoader.loadMapped(corpus.erbc.resolve("program.erbc"))

    private fun baseMetadata(corpus: Corpus) = BootMetadata.read(corpus.erbc.resolve("program.meta"))

    /** 临时游戏根：`erbc.v1/program.meta` 指真 meta（同卷硬链，跨卷退复制），mods 落临时根。 */
    private fun gameRoot(corpus: Corpus, name: String): File {
        val root = tmp.newFolder(name)
        val dst = File(root, "erbc.v1/program.meta")
        dst.parentFile?.mkdirs()
        val src = corpus.erbc.resolve("program.meta")
        // 同卷硬链（零拷贝）优先；跨卷/无权限用复制兜底。
        if (runCatching { Files.createLink(dst.toPath(), src) }.isFailure) {
            Files.copy(src, dst.toPath())
        }
        return root
    }

    private fun moduleOf(
        packageId: String,
        functions: List<Pair<String, List<String>>>,
        canonicalByKey: Map<String, String>,
    ): CompiledModule {
        val text = buildString {
            for ((name, body) in functions) {
                append('@').append(name).append('\n')
                for (line in body) append(line).append('\n')
            }
        }
        val spec = ErbModuleCompiler.specFromErb(packageId, "$packageId.kojo.erb", text.lines(), canonicalByKey)
        return ErbModuleCompiler.compile(spec)
    }

    private fun writeAssets(root: File, packageId: String, module: CompiledModule, override: Set<String>) {
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, "$packageId.erbm").writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = true))
        File(dir, "$packageId.manifest").writeBytes(
            ManifestCodec.encode(ModuleDescriptor(packageId = packageId, overrideFunctions = override)),
        )
    }

    private fun session(root: File, plan: ModChainRuntimePlan?, base: Program, metadata: BootMetadata): GameSession =
        GameSession(
            gameRoot = root.toPath(),
            program = base,
            metadata = metadata,
            inputSinkOverride = NoopInputSink,
            modChain = plan,
        )

    // ------------------------------------------------------------------
    // 判据 1：真口上覆写 —— mod 版本上屏、base 原文不可达（K29_20 行序对调）
    // ------------------------------------------------------------------

    @Test
    fun `判据1_真口上覆写_模组版本上屏_base原文不可达`() {
        val corpus = corpusOrNull() ?: return
        val base = baseProgram(corpus)
        val metadata = baseMetadata(corpus)
        val root = gameRoot(corpus, "kojo-ovr")

        val real = bodyOf(corpus, AYA_ERB, KOJO_OVERRIDE)
        val prints = printLines(real)
        assertEquals("真源 K29_20 应为两句 PRINTFORML", 2, prints.size)
        val swapped = listOf(prints[1], prints[0], "RETURN 1")

        // 候选 B（借文）：K29_16 逐字保真，只证明第二个同名覆盖被签名闸/链接接受。
        val borrowed = bodyOf(corpus, AYA_ERB, KOJO_BORROW)

        val canonical = mapOf(
            KOJO_OVERRIDE to headerCanonical("@$KOJO_OVERRIDE"),
            KOJO_BORROW to headerCanonical("@$KOJO_BORROW"),
        )
        // 签名真闸：真 base 目录（program.meta 尾段）必须与真函数头重算的规范串一致。
        val baseSig = ModChainAssembly.signatureDirectoryOfFile(File(root, "erbc.v1/program.meta"))
        assertEquals(
            "K29_20 的 base 签名必须与真函数头重算相等",
            BaseSignatureCatalog.fingerprint(canonical.getValue(KOJO_OVERRIDE)),
            baseSig(KOJO_OVERRIDE),
        )
        assertEquals(
            "K29_16 的 base 签名必须与真函数头重算相等",
            BaseSignatureCatalog.fingerprint(canonical.getValue(KOJO_BORROW)),
            baseSig(KOJO_BORROW),
        )
        assertTrue("签名目录必须非空（base 产物带签名段）", baseSig(KOJO_OVERRIDE).isNotEmpty())

        writeAssets(
            root,
            KOJO_PACKAGE,
            moduleOf(KOJO_PACKAGE, listOf(KOJO_OVERRIDE to swapped, KOJO_BORROW to borrowed), canonical),
            setOf(KOJO_OVERRIDE, KOJO_BORROW),
        )
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, metadata.functions)))
        assertTrue("声明覆盖 + 签名一致 ⇒ hasOverrides", plan.hasOverrides)

        val dispatch = requireNotNull(plan.functionDispatch)
        val baseIndex = metadata.functions.first { it.key == KOJO_OVERRIDE }.index
        assertNotEquals("覆写后 dispatch 不再指向 base 原文", baseIndex, dispatch[baseIndex])

        val s = session(root, plan, base, metadata)
        assertSame("进 VM 的 Program 必须是链接产物（三表原子替换）", plan.program, s.runtime.program)

        assertEquals("覆写体经真 VM 执行返回真源 RETURN 1", 1L, s.runtime.callFunction(KOJO_OVERRIDE))
        assertEquals(
            "屏上两行必须是 mod 版本的对调序（base 原文不可达）",
            listOf(printedText(prints[1]), printedText(prints[0])),
            s.console.loggedLines(),
        )
        assertNotEquals(
            "对调后首行不得等于 base 原文首行（否则等于没覆盖）",
            printedText(prints[0]),
            s.console.loggedLines().first(),
        )

        // 候选 B：第二个覆盖同样可调，行数与真源一致（借文逐字）。
        val borrowPrints = printLines(borrowed)
        assertEquals("借文 K29_16 返回 1", 1L, s.runtime.callFunction(KOJO_BORROW))
        assertEquals(
            "借文 K29_16 行数与真源一致",
            borrowPrints.size,
            s.console.loggedLines().size - 2,
        )
    }

    // ------------------------------------------------------------------
    // 判据 2：CALL SUPER —— 四行次序钉死 super 链回烧进原文
    // ------------------------------------------------------------------

    @Test
    fun `判据2_CALL_SUPER_四行次序钉死`() {
        val corpus = corpusOrNull() ?: return
        val base = baseProgram(corpus)
        val metadata = baseMetadata(corpus)
        val root = gameRoot(corpus, "kojo-super")

        val real = bodyOf(corpus, AYA_ERB, KOJO_OVERRIDE)
        val prints = printLines(real)
        assertEquals("真源 K29_20 应为两句 PRINTFORML", 2, prints.size)
        val body = listOf("CALL SUPER") + listOf(prints[1], prints[0]) + listOf("RETURN 1")

        writeAssets(
            root,
            KOJO_PACKAGE,
            moduleOf(
                KOJO_PACKAGE,
                listOf(KOJO_OVERRIDE to body),
                mapOf(KOJO_OVERRIDE to headerCanonical("@$KOJO_OVERRIDE")),
            ),
            setOf(KOJO_OVERRIDE),
        )
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, metadata.functions)))
        val s = session(root, plan, base, metadata)

        assertEquals("SUPER 链返回 1", 1L, s.runtime.callFunction(KOJO_OVERRIDE))
        assertEquals(
            "四行次序 = base 原文两句 → mod 对调两句（CALL SUPER 先回原文）",
            listOf(
                printedText(prints[0]),
                printedText(prints[1]),
                printedText(prints[1]),
                printedText(prints[0]),
            ),
            s.console.loggedLines(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 3：加性 verbatim —— base 无此函数，mod 全函数原样上屏
    // ------------------------------------------------------------------

    @Test
    fun `判据3_加性verbatim日记函数_七行上屏返回0`() {
        val corpus = corpusOrNull() ?: return
        val base = baseProgram(corpus)
        val metadata = baseMetadata(corpus)
        val root = gameRoot(corpus, "kojo-add")

        val diary = bodyOf(corpus, REMILIA_ERB, DIARY_ADDITIVE)
        assertEquals("日记真源应有 7 行 PRINT", 7, printLines(diary).size)
        assertEquals(
            "base 语料不得含该函数（真源整文件在 SKIPSTART 内，加性判据成立）",
            -1,
            metadata.functions.indexOfFirst { it.key == DIARY_ADDITIVE },
        )

        writeAssets(
            root,
            KOJO_DIARY_PACKAGE,
            moduleOf(KOJO_DIARY_PACKAGE, listOf(DIARY_ADDITIVE to diary), emptyMap()),
            emptySet(),
        )
        val plan = requireNotNull(ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, metadata.functions)))
        assertFalse("纯加性不构成覆盖", plan.hasOverrides)
        assertNull("无覆盖走直调快路径", plan.functionDispatch)

        val s = session(root, plan, base, metadata)
        assertEquals("日记函数 RETURN 0", 0L, s.runtime.callFunction(DIARY_ADDITIVE))

        val expectLines = printLines(diary).map { printedText(it) }
        assertEquals("七行日记逐行上屏", expectLines, s.console.loggedLines())
    }

    // ------------------------------------------------------------------
    // 判据 4：关态旁路 —— 资产在场但总闸关 ⇒ 输出逐位同 base 原文
    // ------------------------------------------------------------------

    @Test
    fun `判据4_关态旁路_输出逐位同base原文`() {
        val corpus = corpusOrNull() ?: return
        val base = baseProgram(corpus)
        val metadata = baseMetadata(corpus)
        val root = gameRoot(corpus, "kojo-off")

        val real = bodyOf(corpus, AYA_ERB, KOJO_OVERRIDE)
        val prints = printLines(real)
        writeAssets(
            root,
            KOJO_PACKAGE,
            moduleOf(
                KOJO_PACKAGE,
                listOf(KOJO_OVERRIDE to listOf(prints[1], prints[0], "RETURN 1")),
                mapOf(KOJO_OVERRIDE to headerCanonical("@$KOJO_OVERRIDE")),
            ),
            setOf(KOJO_OVERRIDE),
        )

        assertNull(
            "关态 ⇒ assemble 返回 null（零接触）",
            ModChainAssembly.assemble(ChainAssemblyInputs(root, false, base, metadata.functions)),
        )
        val s = session(root, null, base, metadata)
        assertEquals("关态跑 base 原文返回值", 1L, s.runtime.callFunction(KOJO_OVERRIDE))
        assertEquals(
            "关态屏上必须是 base 原文序",
            listOf(printedText(prints[0]), printedText(prints[1])),
            s.console.loggedLines(),
        )
    }

    // ------------------------------------------------------------------
    // 判据 5：配对 fail-closed —— 容器在场 manifest 缺失 ⇒ 整包拒点名
    // ------------------------------------------------------------------

    @Test
    fun `判据5_配对failclosed_容器在场manifest缺失_整包拒`() {
        val corpus = corpusOrNull() ?: return
        val base = baseProgram(corpus)
        val metadata = baseMetadata(corpus)
        val root = gameRoot(corpus, "kojo-unpaired")

        val real = bodyOf(corpus, AYA_ERB, KOJO_OVERRIDE)
        val prints = printLines(real)
        val module = moduleOf(
            KOJO_PACKAGE,
            listOf(KOJO_OVERRIDE to listOf(prints[1], prints[0], "RETURN 1")),
            mapOf(KOJO_OVERRIDE to headerCanonical("@$KOJO_OVERRIDE")),
        )
        val dir = File(root, "mods/rt2").apply { mkdirs() }
        File(dir, "$KOJO_PACKAGE.erbm").writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = true))
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, metadata.functions))
            fail("容器在场但同 packageId manifest 缺失必须整包拒")
        } catch (e: IllegalArgumentException) {
            assertTrue("异常须点名配对不齐，实得：${e.message}", e.message!!.contains("配对不齐"))
        }
    }

    private companion object {
        /** 真源相对 `整合内容` 的两包入口文件（第三方字节只读，不入库）。 */
        const val AYA_ERB = "风神少女v0.01/M_KOJO_K29_1_COUNTER系.ERB"
        const val REMILIA_ERB = "蕾米莉亚色情特化v1.3/M_KOJO_K16_5_日记（简易版）.ERB"

        /** 覆写主候选 A（真源 341-346 行）与借文候选 B（真源 306-312 行）。 */
        const val KOJO_OVERRIDE = "M_KOJO_风神少女_MESSAGE_COUNTER_K29_20"
        const val KOJO_BORROW = "M_KOJO_风神少女_MESSAGE_COUNTER_K29_16"

        /** 加性候选 C（真源 130-138 行，整文件位于 SKIPSTART 内 ⇒ base 无此函数）。 */
        const val DIARY_ADDITIVE = "DIARY_色情特化_K16_1"

        const val KOJO_PACKAGE = "kojo.aya"
        const val KOJO_DIARY_PACKAGE = "kojo.remilia.diary"
    }
}
