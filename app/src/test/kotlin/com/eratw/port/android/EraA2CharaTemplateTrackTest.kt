package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.err.CodeEE
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ERA-A2 模板注入通道探针（CONTRACT-ERA-A2 §3）：把 RECON 探针 C1（ADDCHARA 指向不存在
 * 模板 ⇒ CodeEE）翻成 C2 形态——模板**随 mod 包投递**，导入落 `mods/rt2/<pkg>.chara/`，
 * 装配期注入角色表，ADDCHARA 成功。
 *
 * ## 用例矩阵
 *
 * - `C1翻C2`：真导入链 + 真 VM，随包模板在场 ⇒ ADDCHARA 成功（COUNT=5 / NAME 上屏）。
 * - `反向证明`：收录谓词不认的模板名（`Template999.csv`）⇒ 模板不落位 ⇒ 旧 CodeEE。
 * - `failClosed`：`Chara*.csv` 收录，其余 CSV / 路径穿越 / 绝对路径拒收（[ModZip.unpack] 层）。
 * - `超限` / `坏包`：既有 STORAGE 失败口径逐字不变。
 * - `先到先得`：base 已有 NO 不被 mod 覆盖；mod 注入 NO 落位；两个 mod 包同 NO 按
 *   `<packageId>.chara/` 目录名确定序先到先得（VariableStore.kt:2225-2227 putIfAbsent）。
 *
 * 夹具从 `fixtures/era-a2-chara/` 现场读取（三试路径），不在测试里内嵌 ERB 副本。
 * 本类不依赖 ERATW_CORPUS / ERATW_GAMEDIR（mini base + 合成计数），缺环境变量也全量实跑。
 */
class EraA2CharaTemplateTrackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 夹具读取
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

    /** 模板 CSV 内容（可换名，用于先到先得判据）。 */
    private fun templateCsv(name: String): String = "番号,999,\n名前,$name,\n呼び名,探针,\n"

    // ------------------------------------------------------------------
    // 探针 zip / mini base / 会话装置（照 ModDx3CharaTrackProbeTest）
    // ------------------------------------------------------------------

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

    private val packJson: ByteArray
        get() = fixtureBytes("pack/pack-min.json")

    private val probeErb: ByteArray
        get() = fixtureBytes("ERB/era_a2_probe.ERB")

    /** 带随包模板的探针 zip；[templateName] 传 null = 不放模板（反向证明形态）。 */
    private fun probeZip(
        diskErbName: String = "main.ERB",
        templateName: String? = "ERA-A2探针",
        templateEntryName: String = "CSV/Chara/Chara999.csv",
    ): ByteArray {
        val entries = ArrayList<Pair<String, ByteArray>>()
        entries += "eratw-pack.json" to packJson
        entries += diskErbName to probeErb
        if (templateName != null) {
            entries += templateEntryName to templateCsv(templateName).toByteArray(Charsets.UTF_8)
        }
        entries += "readme.txt" to "说明不是源".toByteArray(Charsets.UTF_8)
        return zipOf(*entries.toTypedArray())
    }

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

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun failed(outcome: ModImportOutcome): ModImportOutcome.Failed =
        (outcome as? ModImportOutcome.Failed)
            ?: throw AssertionError("导入应失败，实为 $outcome")

    private fun rt2Of(root: File): File = File(File(root, "mods"), ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)

    private val baseMetas = listOf(FuncMeta(index = 0, key = "ENTRY"))

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(entry)
    }

    private fun assembledSession(root: File): GameSession {
        val base = baseProgram()
        val plan = requireNotNull(
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, baseMetas)),
        ) { "导入产物在场但装配返回旁路 null" }
        return GameSession(
            gameRoot = root.toPath(),
            program = base,
            metadata = BootMetadata(functions = baseMetas),
            modChain = plan,
        )
    }

    // ------------------------------------------------------------------
    // C1 → C2：随包模板在场 ⇒ ADDCHARA 成功
    // ------------------------------------------------------------------

    @Test
    fun `EraA2_C1翻C2_随包模板在场使ADDCHARA成功`() {
        val root = tmp.newFolder("game-c2")
        assertEquals("rt2 目录名与 core 装配侧字面量对齐", "rt2", ModRuntimeAssembly.RT2_MANIFEST_DIR_NAME)

        val detail = imported(controllerIn(root).import(listOf(PickedModFile("era-a2-probe.ZIP", probeZip()))))
        assertEquals("随包模板条目数 = 1", 1, detail.charaTemplateEntryCount)
        assertEquals("模板不进编译源面（CSV 不是 ERB）", listOf("main.ERB"), detail.sourceFileNames)

        val pkg = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        val templateFile = File(rt2Of(root), "$pkg.chara/Chara999.csv")
        assertTrue("随包模板落位：$templateFile", templateFile.isFile)
        val expectedTemplate = templateCsv("ERA-A2探针").toByteArray(Charsets.UTF_8)
        assertTrue("模板字节与包内一致", templateFile.readBytes().contentEquals(expectedTemplate))

        val session = assembledSession(root)
        assertEquals("ADDCHARA 999 成功返回 0", 0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        val lines = session.console.loggedLines()
        assertTrue("CHARANUM = 4 裸角色 + 1 新角色：$lines", lines.any { it.contains("COUNT=5") })
        assertTrue("新角色 NAME 来自随包模板：$lines", lines.any { it.contains("NAME=ERA-A2探针") })
    }

    // ------------------------------------------------------------------
    // 反向证明：收录谓词不认 ⇒ 旧 CodeEE 红回来
    // ------------------------------------------------------------------

    @Test
    fun `EraA2_反向证明_收录谓词关掉时旧CodeEE断言红`() {
        val root = tmp.newFolder("game-reverse")
        // 模板名换成 fail-closed 名单外的 Template999.csv = 「收录谓词关掉」形态。
        val zip = probeZip(templateName = "ERA-A2探针", templateEntryName = "CSV/Chara/Template999.csv")
        assertTrue(
            "Template999.csv 不在 unpack 收录面",
            ModZip.unpack(zip).none { it.name.endsWith("Template999.csv") },
        )

        val detail = imported(controllerIn(root).import(listOf(PickedModFile("era-a2-reverse.ZIP", zip))))
        assertEquals("模板未被收录", 0, detail.charaTemplateEntryCount)
        assertEquals("没有 .chara 落盘目录", 0, rt2Of(root).listFiles { f -> f.name.endsWith(".chara") }?.size ?: 0)

        val session = assembledSession(root)
        try {
            session.runtime.callFunction("PROBE_CHARA_ADD")
            fail("谓词关掉时 ADDCHARA 999 必须抛 CodeEE")
        } catch (e: CodeEE) {
            assertEquals("报错逐字 = VariableStore.addCharacter 的未命中文案", "ADDCHARA 失败：未定义的角色 NO 999", e.message)
        }
    }

    // ------------------------------------------------------------------
    // fail-closed：只收 Chara*.csv，拒其余 CSV / 路径穿越 / 绝对路径
    // ------------------------------------------------------------------

    @Test
    fun `EraA2_failClosed_只收CharaCsv拒其余Csv与路径穿越`() {
        val zip = zipOf(
            "CSV/Chara/Chara999.csv" to "番号,999,\n".toByteArray(Charsets.UTF_8),
            "CSV/Chara/Chara888.CSV" to "番号,888,\n".toByteArray(Charsets.UTF_8),
            "data.csv" to "x".toByteArray(Charsets.UTF_8),
            "CSV/Chara/Other777.csv" to "x".toByteArray(Charsets.UTF_8),
            "../escape/Chara111.csv" to "x".toByteArray(Charsets.UTF_8),
            "/abs/Chara222.csv" to "x".toByteArray(Charsets.UTF_8),
            "main.ERB" to "RETURN 0".toByteArray(Charsets.UTF_8),
            "readme.txt" to "说明不是源".toByteArray(Charsets.UTF_8),
        )
        val accepted = ModZip.unpack(zip).map { it.name }.toSet()
        assertEquals(
            "收录面 = .ERB + Chara*.csv（大小写不敏感），其余一律不收",
            setOf("CSV/Chara/Chara999.csv", "CSV/Chara/Chara888.CSV", "main.ERB"),
            accepted,
        )
        assertFalse("路径穿越条目不进堆", accepted.any { it.contains("..") })
        assertFalse("绝对路径条目不进堆", accepted.any { it.startsWith("/") })
    }

    @Test
    fun `EraA2_超限条目维持既有STORAGE失败口径`() {
        val root = tmp.newFolder("game-limit")
        val entries = ArrayList<Pair<String, ByteArray>>()
        repeat(ModZipGuard.MAX_ENTRIES + 1) { i ->
            entries += "f$i.ERB" to "RETURN 0".toByteArray(Charsets.UTF_8)
        }
        val outcome = controllerIn(root).import(listOf(PickedModFile("flood.zip", zipOf(*entries.toTypedArray()))))
        val failed = failed(outcome)
        assertEquals("越限维持既有 STORAGE 失败口径", ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("文案点名 zip：${failed.message}", failed.message.contains("flood.zip"))
        assertEquals("拒在落盘之前，盘上零 .chara 目录", 0, rt2Of(root).listFiles { f -> f.name.endsWith(".chara") }?.size ?: 0)
    }

    @Test
    fun `EraA2_坏zip维持既有STORAGE失败口径`() {
        val root = tmp.newFolder("game-bad")
        val outcome = controllerIn(root).import(
            listOf(PickedModFile("broken.zip", "不是 zip".toByteArray(Charsets.UTF_8))),
        )
        val failed = failed(outcome)
        assertEquals("坏包维持既有 STORAGE 失败口径", ModImportFailureKind.STORAGE, failed.kind)
        assertTrue("文案点名 zip：${failed.message}", failed.message.contains("broken.zip"))
    }

    // ------------------------------------------------------------------
    // 同 NO 先到先得
    // ------------------------------------------------------------------

    @Test
    fun `EraA2_先到先得_base已有NO不被mod覆盖`() {
        val root = tmp.newFolder("game-base-wins")
        // base 侧 CSV 先放一枚同 NO 模板（装配第 3 步先注册）。
        val baseChara = File(File(File(root, "CSV"), "Chara"), "Chara999.csv")
        val baseCharaDir = requireNotNull(baseChara.parentFile)
        assertTrue(baseCharaDir.mkdirs() || baseCharaDir.isDirectory)
        baseChara.writeText("番号,999,\n名前,BASE探针,\n")

        imported(controllerIn(root).import(listOf(PickedModFile("era-a2-base-wins.ZIP", probeZip()))))
        val session = assembledSession(root)
        assertEquals("ADDCHARA 999 成功", 0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        val lines = session.console.loggedLines()
        assertTrue("base 已有 NO 保住自己的 NAME：$lines", lines.any { it.contains("NAME=BASE探针") })
        assertFalse("mod 同名 NO 未覆盖 base：$lines", lines.any { it.contains("NAME=ERA-A2探针") })
    }

    @Test
    fun `EraA2_先到先得_两个mod包同NO按目录名序先到者胜`() {
        val root = tmp.newFolder("game-two-mods")
        val controller = controllerIn(root)
        imported(
            controller.import(
                listOf(PickedModFile("a.ZIP", probeZip(diskErbName = "a_probe.ERB", templateName = "AAA探针"))),
            ),
        )
        val secondZip = zipOf(
            "eratw-pack.json" to packJson,
            "b_second.ERB" to fixtureBytes("ERB/era_a2_second.ERB"),
            "CSV/Chara/Chara999.csv" to templateCsv("BBB探针").toByteArray(Charsets.UTF_8),
        )
        val secondDetail = imported(controller.import(listOf(PickedModFile("b.ZIP", secondZip))))
        assertEquals("第二包模板也随包落位", 1, secondDetail.charaTemplateEntryCount)

        val p1 = controller.packageIdFor(listOf("a_probe.ERB"))
        val p2 = controller.packageIdFor(listOf("b_second.ERB"))
        val winner = if (p1 < p2) "AAA探针" else "BBB探针"
        assertTrue("两包 packageId 不同（两个 .chara 目录）", p1 != p2)

        val session = assembledSession(root)
        assertEquals("ADDCHARA 999 成功", 0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        val lines = session.console.loggedLines()
        assertTrue("先到者（目录名序较小）胜出：$winner / $lines", lines.any { it.contains("NAME=$winner") })
    }

    private companion object {
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
