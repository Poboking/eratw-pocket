package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ERA-W1：随包角色模板注册序从「目录名临时排序」升格为 **ModuleLinkOrder 声明序**的正反证
 * （A2 交付留下的真 LinkOrder 接线挂账收账；CONTRACT-ERA-W1 §0.2）。
 *
 * - 正向：声明序改变同 NO 先到先得的胜者——声明把目录名序靠后的包排前，它的模板先注册、
 *   putIfAbsent（VariableStore.kt:2225-2227）先到先得 ⇒ 胜者随声明翻转。
 * - 反向（fail-closed 零行为差）：空声明（关态 / 旁路 / 未接线形态）⇒ 既有目录名序逐位不变。
 * - 声明非法（重复 ID）⇒ 整体降级目录名序 + WARN 进 [GameSession.charaTemplateWarnings]，不抛。
 * - 声明引用不存在的包：该包无 .chara 目录自然无候选，合法目录照声明序落位（不降级、不炸）。
 *
 * 夹具复用 `fixtures/era-a2-chara/`（真导入链 + 真 VM，照 EraA2CharaTemplateTrackTest）；
 * 不依赖 ERATW_CORPUS / ERATW_GAMEDIR。
 */
class EraW1CharaLinkOrderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 装置（照 EraA2CharaTemplateTrackTest）
    // ------------------------------------------------------------------

    private fun fixtureDir(): File {
        val candidates = listOf(
            File("fixtures/era-a2-chara"),
            File("../fixtures/era-a2-chara"),
            File("../../fixtures/era-a2-chara"),
            // 公开分仓后夹具在同级引擎仓 Kotera（composite build 的 ../Kotera）。
            File("../../Kotera/fixtures/era-a2-chara"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到夹具目录 fixtures/era-a2-chara（试了 ${candidates.joinToString { it.path }}）")
    }

    private fun fixtureBytes(rel: String): ByteArray = File(fixtureDir(), rel).readBytes()

    private fun templateCsv(name: String): String = "番号,999,\n名前,$name,\n呼び名,探针,\n"

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val packJson: ByteArray get() = fixtureBytes("pack/pack-min.json")

    private fun templateZip(diskErbName: String, templateName: String): ByteArray {
        val erb = if (diskErbName.startsWith("a_")) "era_a2_probe.ERB" else "era_a2_second.ERB"
        return zipOf(
            "eratw-pack.json" to packJson,
            diskErbName to fixtureBytes("ERB/$erb"),
            "CSV/Chara/Chara999.csv" to templateCsv(templateName).toByteArray(Charsets.UTF_8),
        )
    }

    private fun controllerIn(root: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(root, "mods")),
            baseSource = {
                BaseCompileContext(
                    miniMetadata(),
                    PatchCompiler.BaseCounts(
                        instructionCount = 4000,
                        functionCount = 4,
                        statementCount = 100,
                        stringCount = 10,
                        longCount = 5,
                        exprCount = 20,
                    ),
                    BASE_SHA,
                    csvDir = null,
                    baseSignatures = { BaseSignatureCatalog.fingerprint("SIG|$it") },
                )
            },
            gameRoot = root,
        )

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

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"))

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(entry)
    }

    /** 导入两个带同 NO(999) 模板的包，返回按目录名（= packageId）升序的 (p1, p2)。 */
    private fun importTwoTemplatePackages(root: File): Pair<String, String> {
        val controller = controllerIn(root)
        val a = controller.import(listOf(PickedModFile("a.ZIP", templateZip("a_probe.ERB", "AAA探针"))))
        val b = controller.import(listOf(PickedModFile("b.ZIP", templateZip("b_second.ERB", "BBB探针"))))
        assertTrue("两包都应导入成功：$a / $b", a is ModImportOutcome.Imported && b is ModImportOutcome.Imported)
        val p1 = controller.packageIdFor(listOf("a_probe.ERB"))
        val p2 = controller.packageIdFor(listOf("b_second.ERB"))
        assertTrue("两包 packageId 必须不同（两个 .chara 目录）", p1 != p2)
        return if (p1 < p2) p1 to p2 else p2 to p1
    }

    /** [EraA2CharaTemplateTrackTest.assembledSession] 的声明序扩展形态。 */
    private fun assembledSession(root: File, linkOrderIds: List<String>): GameSession {
        val base = baseProgram()
        val plan = requireNotNull(
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, baseMetas)),
        ) { "导入产物在场但装配返回旁路 null" }
        return GameSession(
            gameRoot = root.toPath(),
            program = base,
            metadata = BootMetadata(functions = baseMetas),
            modChain = plan,
            modLinkOrderIds = linkOrderIds,
        )
    }

    private fun winnerName(session: GameSession): String? =
        session.console.loggedLines().firstOrNull { it.contains("NAME=") }?.substringAfter("NAME=")?.trim()

    // ------------------------------------------------------------------
    // 正向：声明序改变先到先得胜者
    // ------------------------------------------------------------------

    @Test
    fun `EraW1_声明序改变模板注册序_目录名靠后者按声明先注册胜出`() {
        val root = tmp.newFolder("game-declared")
        val (lo, hi) = importTwoTemplatePackages(root)
        // 目录名序胜者 = lo（A2 既有行为）；声明把 hi 排前 ⇒ hi 的模板先注册 ⇒ 胜者翻转。
        val session = assembledSession(root, listOf(hi, lo))
        assertEquals(0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        assertEquals("胜者随声明翻转（目录名序胜者本应是 lo 包）", controllerNameOf(root, hi), winnerName(session))
    }

    // ------------------------------------------------------------------
    // 反向：空声明零行为差（目录名序逐位不变）
    // ------------------------------------------------------------------

    @Test
    fun `EraW1_空声明零行为差_目录名序胜者与A2基线一致`() {
        val root = tmp.newFolder("game-empty-decl")
        val (lo, _) = importTwoTemplatePackages(root)
        val session = assembledSession(root, emptyList())
        assertEquals(0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        assertEquals("空声明 = 目录名序（lo 包先注册先到先得）", controllerNameOf(root, lo), winnerName(session))
        assertTrue("空声明不得留 WARN", session.charaTemplateWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // fail-closed：重复 ID 声明降级 + WARN；引用不存在的包不炸
    // ------------------------------------------------------------------

    @Test
    fun `EraW1_重复ID声明降级目录名序并留WARN`() {
        val root = tmp.newFolder("game-dup-decl")
        val (lo, hi) = importTwoTemplatePackages(root)
        val session = assembledSession(root, listOf(hi, hi))
        assertEquals(0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        assertEquals("非法声明整体降级 = 目录名序胜者（lo 包）", controllerNameOf(root, lo), winnerName(session))
        assertTrue("降级必须留 WARN（不静默）", session.charaTemplateWarnings.isNotEmpty())
    }

    @Test
    fun `EraW1_声明引用不存在的包不炸且合法目录照声明落位`() {
        val root = tmp.newFolder("game-ghost-decl")
        val (lo, hi) = importTwoTemplatePackages(root)
        // 声明把 hi 排前并夹一个不存在的包：合法目录照声明序（hi 先注册胜出），幽灵包无候选被忽略。
        val session = assembledSession(root, listOf(hi, "ghost-pkg", lo))
        assertEquals(0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        assertEquals("幽灵包忽略、hi 先注册胜出", controllerNameOf(root, hi), winnerName(session))
        assertTrue("合法声明不得降级留 WARN", session.charaTemplateWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // packageId ↔ 模板名对应（断言助手）
    // ------------------------------------------------------------------

    private fun controllerNameOf(root: File, packageId: String): String =
        if (packageId == controllerIn(root).packageIdFor(listOf("a_probe.ERB"))) "AAA探针" else "BBB探针"

    private companion object {
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
