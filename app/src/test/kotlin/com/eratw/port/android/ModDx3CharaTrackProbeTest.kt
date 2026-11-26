package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.compile.PatchCompiler
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.patch.BaseSignatureCatalog
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.GameSession
import com.eratw.port.platform.storage.ModStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MODDX3-A2 探针：ERB 数据轨（.erbm 口上包）加一个可攻略新角色「今天」的行为钉板
 * （CONTRACT-MODDX3-A2 §3；判词 = 部分可行，逐探针判据见
 * `fixtures/moddx-a2-chara/expected-outcomes.tsv`，六章论证见
 * `<internal-workdir>/RECON-MODDX3-A2.md`）。
 *
 * ## 探针矩阵（与夹具一一对应，全部走真导入链 / 真 VM）
 *
 * - A1 无主 KOJO 函数 + ADDCHARA 语句过真导入链：`ModImportController.import`
 *   （mini base + Base64-free 的现场 zip，先例 = [ModImportAuthorPackEndToEndTest] 的
 *   zip 形态）⇒ 导入成功。钉「编译期没有角色模板语义校验」。
 * - A2 eratw-pack.json 三形态：未知字段（displayName/author）忽略不拒、schema=2 整份
 *   不采信回落 version="1"、缺失条目 MISSING 回落。钉 pack.json 字段面
 *   （只认 schema/packageId/version，[ModImportVersion] 的解析面）。
 * - B1 覆盖声明 × 签名段缺席 ⇒ 按覆盖装载放行 + 告警点名「ABI 未校验」（d6da78151 改判后的新语义；
 *   原版锚 LabelDictionary.cs:107-113 后装者胜、ErbLoader.cs:464-474 只 Warn 不拒），且装配面三层验「真激活」：
 *   catalog A_ONE 胜者归因补丁包、撞名告警进 logSink、会话派发命中模块本体 PRINTL 行
 *   （ERB 导入包不走 attach 的 mod.install 行族——该行族只属 Kotlin/dex 载体面，见用例内注释）；
 *   B2 同声明 × 签名段给得出指纹 ⇒ 声明式覆盖装载（overrideCount=1，且没有那条 ABI 未校验告警）。
 *   B1↔B2 互为反向证明（判据 = 签名段非空）。
 * - C1 `ADDCHARA 999` 在无 CSV 的真 VM 里抛 CodeEE（消息逐字）；
 *   C2 同探针 + gameRoot 放一枚 Chara999.csv ⇒ 成功且 NAME 可读。C1↔C2 互为反向证明
 *   （判据 = CSV 模板在场——那是 base 侧资源，mod 导入链送不进 CSV/Chara）。
 * - C3 KOJO 拼名分发（CALLFORM M_KOJO_K999）对模块函数可达（M2 口上路由的可达底座）。
 *
 * ## 环境门（AGENTS §5 动态用例纪律）
 *
 * 本类**不依赖** ERATW_CORPUS / ERATW_GAMEDIR / 真产物：base 是 mini 侧车 + 合成计数
 * （照 [ModImportAuthorPackEndToEndTest]），CSV 夹具自带于 fixtures 目录。因此本类在
 * 缺环境变量的 CI 上也全量实跑，不设 Assume 门；语料依赖的结论（番号 0..167、
 * 人物数量上限 200）以 fixtures/tsv 的证据锚行承载，不在本类断言。
 *
 * 夹具纪律：测试从 `fixtures/moddx-a2-chara/` 现场读取（候选路径三试），不在测试里
 * 内嵌副本；改夹具须同步 expected-outcomes.tsv 与断言。
 */
class ModDx3CharaTrackProbeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 夹具读取（fixtures/moddx-a2-chara/，候选路径照 ModChainAssetPairTest 的三试口径）
    // ------------------------------------------------------------------

    private fun fixtureDir(): File {
        val candidates = listOf(
            File("fixtures/moddx-a2-chara"),
            File("../fixtures/moddx-a2-chara"),
            File("../../fixtures/moddx-a2-chara"),
            // 公开分仓后夹具在同级引擎仓 Kotera（composite build 的 ../Kotera）。
            File("../../Kotera/fixtures/moddx-a2-chara"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到探针夹具目录 fixtures/moddx-a2-chara（试了 ${candidates.joinToString { it.path }}）")
    }

    private fun fixtureText(rel: String): String = String(fixtureBytes(rel), Charsets.UTF_8)

    private fun fixtureBytes(rel: String): ByteArray =
        File(fixtureDir(), rel).readBytes()

    /** 探针 zip：包元数据 + 探针 ERB + 注释-only ERH + readme.txt（非源条目）。 */
    private fun probeZip(packJson: String = fixtureText("pack/pack-min.json")): ByteArray =
        zipOf(
            "eratw-pack.json" to packJson.toByteArray(Charsets.UTF_8),
            "main.ERB" to fixtureText("ERB/moddx_a2_probe.ERB").toByteArray(Charsets.UTF_8),
            "probe_header.ERH" to fixtureText("ERH/moddx_a2_header.ERH").toByteArray(Charsets.UTF_8),
            "readme.txt" to fixtureText("readme.txt").toByteArray(Charsets.UTF_8),
        )

    private fun overrideZip(packJson: String = fixtureText("pack/pack-min.json")): ByteArray =
        zipOf(
            "eratw-pack.json" to packJson.toByteArray(Charsets.UTF_8),
            "override.ERB" to fixtureText("ERB/moddx_a2_override.ERB").toByteArray(Charsets.UTF_8),
        )

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // mini base（照 ModImportAuthorPackEndToEndTest 的装置形态）
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

    /** 签名目录缺省恒空串（= 真旧档无签名段的 fail-closed 形态）。 */
    private fun controllerIn(root: File): ModImportController =
        ModImportController(
            modStore = ModStore(File(root, "mods")),
            baseSource = { BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null) },
            gameRoot = root,
        )

    private fun controllerIn(root: File, signatures: (String) -> String): ModImportController =
        ModImportController(
            modStore = ModStore(File(root, "mods")),
            baseSource = {
                BaseCompileContext(miniMetadata(), miniBaseCounts(), BASE_SHA, csvDir = null, baseSignatures = signatures)
            },
            gameRoot = root,
        )

    private fun imported(outcome: ModImportOutcome): ImportedPatchDetail =
        (outcome as? ModImportOutcome.Imported)?.detail
            ?: throw AssertionError("导入应成功，实为 $outcome")

    private fun rt2Of(root: File): File = File(File(root, "mods"), "rt2")

    // ------------------------------------------------------------------
    // 真会话装置（照 ModChainAssemblySessionEndToEndTest：base ENTRY 直 RET）
    // ------------------------------------------------------------------

    // A_ONE 是 B1/B2 撞名的 base 侧本体：RETURN 0、不打印——覆盖体真被派发（B1 层③）的唯一自证
    // 是覆盖侧 PRINTL 行上屏。加这个函数不影响 C1/C2/C3（三者均不引用 A_ONE）。
    private val baseMetas = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "A_ONE"),
    )

    private fun baseProgram(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        // base 侧 A_ONE 本体（与夹具覆盖段同名）：只 RET 0，不打印——与 baseMetas 的 funcMeta 同形。
        b.declareFunction("A_ONE", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, 0, 0, 0, 0)
        return b.build(entry)
    }

    /** 真导入链落盘 → 生产装配 → 真会话。root 必须是已 import 成功的游戏根。 */
    private fun assembledSession(root: File): GameSession {
        val base = baseProgram()
        val plan = requireNotNull(
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base, baseMetas)),
        ) { "导入产物在场但装配返回旁路 null——配对闸判据破坏" }
        return GameSession(
            gameRoot = root.toPath(),
            program = base,
            metadata = BootMetadata(functions = baseMetas),
            modChain = plan,
        )
    }

    // ------------------------------------------------------------------
    // 探针 A1：无主 KOJO 函数 + ADDCHARA 语句过真导入链
    // ------------------------------------------------------------------

    @Test
    fun `探针A1_无主KOJO函数与ADDCHARA补丁过真导入链`() {
        val root = tmp.newFolder("game")
        val detail = imported(controllerIn(root).import(listOf(PickedModFile("moddx-a2-probe.ZIP", probeZip()))))

        // 三个函数全部进模块（M_KOJO_K999 / PROBE_CHARA_ADD / PROBE_KOJO_DISPATCH）。
        assertEquals("无主 KOJO 与 ADDCHARA 探针都编译进模块", 3, detail.functionCount)
        assertEquals("无撞名 ⇒ 无覆盖", 0, detail.overrideCount)
        assertEquals("entry = 装载序第一个可用函数头（main.ERB 的 M_KOJO_K999）", "M_KOJO_K999", detail.entryFunction)

        // zip 收录面：.ERB/.ERH 进，readme.txt 被静默跳过（不报错、不进留档）。
        assertEquals(
            "源留档名单 = zip 内 .ERB/.ERH 条目（txt 不在其中）",
            listOf("main.ERB", "probe_header.ERH"),
            detail.sourceFileNames,
        )
        assertFalse(
            "readme.txt 必须被跳过",
            detail.parseWarnings.any { it.contains("readme.txt") },
        )

        // pack.json 版本随真链落 manifest；纯源包资源面为 0。
        val manifest = File(rt2Of(root), detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION) + ModPackageInventory.MANIFEST_EXTENSION)
        assertTrue("manifest 落盘：$manifest", manifest.isFile)
        assertEquals("pack.json version 落 manifest", "1.0", ManifestCodec.decode(manifest.readBytes()).version)
        assertEquals("纯源包无资源面", 0, detail.resourceEntryCount)

        // 四件套齐（erbm/manifest/sources/fingerprint）——装配链的配对闸吃的就是这些。
        val pkg = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        assertTrue(File(rt2Of(root), "$pkg${ModPackageInventory.MODULE_EXTENSION}").isFile)
        assertTrue(File(rt2Of(root), "$pkg${ModPackageInventory.SOURCES_EXTENSION}").isFile)
    }

    // ------------------------------------------------------------------
    // 探针 A2：eratw-pack.json 三形态（未知字段 / schema 不符 / 缺失）
    // ------------------------------------------------------------------

    @Test
    fun `探针A2_packJson未知字段忽略_schema不符回落`() {
        // ① 未知字段（displayName/author）不在 schema 里：忽略不拒（采信 version）。
        val extra = parsePackMeta(fixtureText("pack/pack-extra.json").toByteArray(Charsets.UTF_8))
        assertNull(
            "未知字段不构成未采信原因（displayName/author 被 ignore）",
            extra.ignored,
        )
        assertTrue("采信态 = trusted", extra.trusted)
        assertEquals("displayName/author 在场时 version 仍采信", "2.1", extra.version)
        assertEquals("packageId 照常读出", "eratw.moddx3a2", extra.packageId)

        // ② schema=2：整份不采信，version 回落 "1"（未来格式不许被老端猜着读）。
        val bad = parsePackMeta(fixtureText("pack/pack-badschema.json").toByteArray(Charsets.UTF_8))
        assertEquals("schema 不符 ⇒ SCHEMA", PackMetaIgnore.SCHEMA, bad.ignored)
        assertEquals("不采信 ⇒ version 回落", PACK_META_FALLBACK_VERSION, bad.version)

        // ③ 缺失条目：MISSING 回落（真导入端 packMetaOf 的空 zip 形态）。
        val missing = parsePackMeta(null)
        assertEquals(PackMetaIgnore.MISSING, missing.ignored)
        assertEquals(PACK_META_FALLBACK_VERSION, missing.version)

        // ④ 真导入链：displayName/author 形态的 pack.json 不拦导入，version 照采信落 manifest。
        val root = tmp.newFolder("game-extra")
        val detail = imported(controllerIn(root).import(listOf(PickedModFile("moddx-a2-extra.ZIP", probeZip(fixtureText("pack/pack-extra.json"))))))
        assertEquals("未知字段形态导入成功", 3, detail.functionCount)
        val manifest = File(rt2Of(root), detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION) + ModPackageInventory.MANIFEST_EXTENSION)
        assertEquals("2.1", ManifestCodec.decode(manifest.readBytes()).version)
        assertTrue("packageId 前缀无资源自证冲突（本包无 .assets 条目）", detail.packMetaWarnings.isEmpty())
    }

    // ------------------------------------------------------------------
    // 探针 B1/B2：同名覆盖 × 签名段（互为反向证明）
    // ------------------------------------------------------------------

    @Test
    fun `探针B1_覆盖声明但签名段缺席_按覆盖装载放行并告警点名ABI未校验`() {
        // d6da78151（KI-2/KI-3）改判后的新语义钉板：base 侧车给不出被覆盖键的签名指纹 ⇒ 不再整包硬拒，
        // 按覆盖装载 + 告警点名「ABI 未校验」。判据层 = 装载/编译相位，原版锚：
        //   reference/emuera-skia-4.981/Emuera/Runtime/Script/Data/LabelDictionary.cs:107-113
        //     ——非事件同名走 noneventLabelDic[key] = label 直接赋值，后装载者胜出，无任何去重/拒载判断；
        //   同树 Emuera/Runtime/Script/Loader/ErbLoader.cs:464-474
        //     ——撞名先 AddLabel（:464）再 ParserMediator.Warn(FuncIsAlreadyDefined, …, 1)，只告警不拒。
        // 本侧告警现址：app/src/main/kotlin/com/eratw/port/android/ModImportController.kt:2349-2356
        //   degradedOverrideKeys → 「覆盖函数 … 在本体侧车无签名指纹，按覆盖装载但 ABI 未校验——
        //   若该函数形参/返回通道与本体不一致，报错在运行期，请向口上作者反馈」；
        //   链接相位判据汇：core-vm/src/main/kotlin/com/eratw/port/core/code/SymbolCatalog.kt:297-335 reportOverrideVerdict。
        val root = tmp.newFolder("game-b1")
        val detail = imported(
            controllerIn(root).import(
                listOf(PickedModFile("moddx-a2-override.ZIP", overrideZip())),
                declaredOverrides = listOf("A_ONE"),
            ),
        )
        // 后果面①：覆盖照旧按声明记账，既没被静默丢弃，也没降级成「不覆盖」
        //（与 B2 同一判据，差别只在有无指纹 ⇒ 硬拒取消之后覆盖面不塌）。
        assertEquals("覆盖按声明装载：overrideCount = 1", 1, detail.overrideCount)
        // 后果面②：告警必须点名被覆盖的函数 A_ONE，并写清「按覆盖装载」+「ABI 未校验」
        //（判词 21/24 那类「第三方口上装了不报错但行为不对」的唯一取证面，不许漂成泛泛提示）。
        assertTrue(
            "告警须点名 A_ONE 并写明按覆盖装载 / ABI 未校验：${detail.parseWarnings}",
            detail.parseWarnings.any {
                it.contains("A_ONE") && it.contains("按覆盖装载") && it.contains("ABI 未校验")
            },
        )
        // 后果面③：真激活——三层验（归因 / 留痕 / 执行），不许出现静默兜底（不装载 / 半装载 / 整链旁路）。
        // 不钉 mod.install active=true 行、改钉这三层的盘上理由：该行族只由 attach 宿主安装循环打印
        //（ModRuntimeAssembly.kt:528-538），其 modProviders 是内置 Kotlin 载体 + dex 载体
        //（GameHost.kt:1566-1574）；ERB 导入包链装配成功时零行——由 ModChainAssemblyRejectTraceTest
        // 的「正常链零留痕」绿例钉死（ModChainAssembly.kt:316-331 的 try 区没有成功路径 logSink 调用）。
        // 故在装配面钉更硬的三层：
        val chainLines = mutableListOf<String>()
        val patchPackageId = detail.patchFileName.removeSuffix(ModPackageInventory.MODULE_EXTENSION)
        val outcome = checkNotNull(
            ModChainAssembly.assembleWithCatalog(
                ChainAssemblyInputs(root, true, baseProgram(), baseMetas, logSink = { chainLines += it }),
            ),
        ) { "导入产物在场但覆盖包链装配返回旁路 null——整链静默丢不可接受" }
        // 层①归因：catalog A_ONE 胜者必须归因补丁包（last-wins 真联结，不是静默兜底回 base 侧）。
        assertEquals(
            "catalog A_ONE 胜者必须归因补丁包 $patchPackageId",
            patchPackageId,
            outcome.catalog.bindings["A_ONE"]?.ownerPackageId,
        )
        // 层②留痕：链接相位撞名三态告警汇进装配 sink（ModChainAssembly.kt:328-330 接线，
        // 文案出自 SymbolCatalog.kt:305-335 reportOverrideVerdict 的指纹缺失分支）。
        assertTrue(
            "撞名告警必须在装配 sink 留痕（点名 A_ONE 与 ABI 未校验）：$chainLines",
            chainLines.any { it.contains("A_ONE") && it.contains("ABI 未校验") },
        )
        // 层③执行：真会话走生产 plan 必须命中模块本体 PRINTL——base 侧 A_ONE 只 RET 0 不打印，
        // 上屏该行即覆盖体真被派发的唯一自证（先例 KojoTranscodeChainEndToEndTest:373 模块函数可达）。
        val session = GameSession(
            gameRoot = root.toPath(),
            program = baseProgram(),
            metadata = BootMetadata(functions = baseMetas),
            modChain = outcome.plan,
        )
        try {
            assertEquals("覆盖体返回值仍须为 0", 0L, session.runtime.callFunction("A_ONE"))
            assertTrue(
                "会话派发必须命中模块本体覆盖体的 PRINTL 行：${session.console.loggedLines()}",
                session.console.loggedLines().any { it.contains("MODDX3-A2 覆盖探针") },
            )
        } finally {
            session.close()
        }
    }

    @Test
    fun `探针B2_签名段给得出指纹时同名覆盖按声明装载`() {
        // baseSignatures 对 A_ONE 给出非空指纹（真签名段的 mini 等价形态）⇒ 声明式覆盖装载。
        val signatures: (String) -> String = { key ->
            if (key.equals("A_ONE", ignoreCase = true)) {
                BaseSignatureCatalog.fingerprint("SIG1|ret:I")
            } else {
                ""
            }
        }
        val declaredRoot = tmp.newFolder("game-b2-declared")
        val declared = imported(
            controllerIn(declaredRoot, signatures).import(
                listOf(PickedModFile("moddx-a2-override.ZIP", overrideZip())),
                declaredOverrides = listOf("A_ONE"),
            ),
        )
        assertEquals("声明覆盖计数 = 1", 1, declared.overrideCount)

        // SAF 直选形态（declaredOverrides = null）同判据：同名 ∩ base 自动推导，签名在场即可覆盖。
        val autoRoot = tmp.newFolder("game-b2-auto")
        val auto = imported(controllerIn(autoRoot, signatures).import(listOf(PickedModFile("moddx-a2-override.ZIP", overrideZip()))))
        assertEquals("自动推导同判据：overrideCount = 1", 1, auto.overrideCount)
    }

    // ------------------------------------------------------------------
    // 探针 C1/C2：ADDCHARA 指向不存在模板（真 VM；互为反向证明）
    // ------------------------------------------------------------------

    @Test
    fun `探针C1_ADDCHARA指向不存在模板_真VM报未定义角色NO`() {
        val root = tmp.newFolder("game-c1")
        imported(controllerIn(root).import(listOf(PickedModFile("moddx-a2-probe.ZIP", probeZip()))))
        val session = assembledSession(root)
        try {
            session.runtime.callFunction("PROBE_CHARA_ADD")
            fail("ADDCHARA 999 在模板注册表无 999 时必须抛 CodeEE")
        } catch (e: CodeEE) {
            assertEquals(
                "报错逐字 = VariableStore.addCharacter 的未命中文案",
                "ADDCHARA 失败：未定义的角色 NO 999",
                e.message,
            )
        }
    }

    @Test
    fun `探针C2_CSV模板在场时ADDCHARA成功_反向证明C1`() {
        val root = tmp.newFolder("game-c2")
        // 判据载体：gameRoot/CSV/Chara999.csv（base 侧资源——mod 导入链送不进 CSV/Chara，
        // 这个文件只能由玩家/整包手工投放，见 RECON 判词）。
        val csvDir = File(root, "CSV")
        val charaDir = File(csvDir, "Chara")
        assertTrue(charaDir.mkdirs() || charaDir.isDirectory)
        File(charaDir, "Chara999.csv").writeBytes(fixtureBytes("CSV/Chara999.csv"))

        imported(controllerIn(root).import(listOf(PickedModFile("moddx-a2-probe.ZIP", probeZip()))))
        val session = assembledSession(root)

        assertEquals("ADDCHARA 成功返回 0", 0L, session.runtime.callFunction("PROBE_CHARA_ADD"))
        val lines = session.console.loggedLines()
        assertTrue(
            "CHARANUM = 4 裸角色 + 1 新角色：$lines",
            lines.any { it.contains("COUNT=5") },
        )
        assertTrue(
            "新角色 NAME 来自 CSV 模板（名前行）：$lines",
            lines.any { it.contains("NAME=MODDX3探针") },
        )
    }

    // ------------------------------------------------------------------
    // 探针 C3：KOJO 拼名分发对模块函数可达（M2 口上路由的可达底座）
    // ------------------------------------------------------------------

    @Test
    fun `探针C3_CALLFORM拼名分发命中模块KOJO函数`() {
        val root = tmp.newFolder("game-c3")
        imported(controllerIn(root).import(listOf(PickedModFile("moddx-a2-probe.ZIP", probeZip()))))
        val session = assembledSession(root)

        assertEquals("CALLFORM M_KOJO_K999 经动态名命中模块函数", 0L, session.runtime.callFunction("PROBE_KOJO_DISPATCH"))
        val lines = session.console.loggedLines()
        assertTrue(
            "模块侧 M_KOJO_K999 的 PRINTL 上屏：$lines",
            lines.any { it.contains("MODDX3-A2 无主KOJO探针") },
        )
    }

    // ------------------------------------------------------------------
    // 判词钉板：expected-outcomes.tsv 与探针矩阵的对应关系必须逐行成立
    // ------------------------------------------------------------------

    @Test
    fun `探针判词钉板_expectedOutcomes逐行与探针矩阵对齐`() {
        val rows = fixtureText("expected-outcomes.tsv")
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { it.split('\t') }
            .toList()
        val header = rows.first()
        assertEquals("TSV 列形状", listOf("探针ID", "相位", "判据", "期望", "证据锚（本 worktree 文件:行）"), header)
        val byId = rows.drop(1).associate { it[0] to it }
        // 探针矩阵全覆盖（A1/A2/B1/B2/C1/C2/C3 + SUM 判词行）。
        assertEquals(
            "探针行集 = 矩阵全集",
            setOf("A1", "A2", "B1", "B2", "C1", "C2", "C3", "SUM"),
            byId.keys,
        )
        assertTrue(
            "总判词以「部分可行」起头（SUM 行期望列 = 判词 + 逐段理由）",
            byId.getValue("SUM")[3].startsWith("部分可行"),
        )
        // 相位映射与探针归属逐行一致（判词钉进探针的静态面）。
        assertEquals("编译+导入", byId.getValue("A1")[1])
        assertEquals("编译+导入", byId.getValue("A2")[1])
        assertEquals("编译+链接+运行", byId.getValue("B1")[1])
        assertEquals("编译+链接", byId.getValue("B2")[1])
        assertEquals("运行", byId.getValue("C1")[1])
        assertEquals("运行", byId.getValue("C2")[1])
        assertEquals("运行", byId.getValue("C3")[1])
        // C1/C2 是互为反向证明的一对：判据行必须互指。
        assertTrue(
            "C2 判据行须写明与 C1 的反向关系",
            byId.getValue("C2")[2].contains("C1"),
        )
    }

    private companion object {
        /** 合成 base sha（64 位小写十六进制；照 ModImportAuthorPackEndToEndTest）。 */
        private const val BASE_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
