package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.CsvKeywordPatch
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.RelocationException
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.ModCsvCapacityLimitException
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lane DY（task-124）：**装载前置段**（`ModRuntimeAssembly.csvLoadContext` 的
 * `ManifestLoader.load` 发生在 `assembleWithCatalog` 之前）拒载的逐包留痕契约。
 *
 * 契约：前置段是装配链第一道 fail-closed 闸，异常在 `assembleWithCatalog` 之前抛出 ⇒
 * 链内 catch 根本走不到。若不在此留痕，声明冲突 / 坏 manifest 只有启动失败屏与 logcat，
 * 设置页「本会话装载失败」小节永远看不到。留痕纪律与链内**同一实现**：
 *
 * - 声明冲突（`DeclarationConflictException.packageIds` 双边可归因）⇒ 逐包 canonical
 *   `mod.install pkg=<pkg> active=false failure=<异常名>: <原因>`，诊断面/设置页可读；
 * - 坏 manifest 字节（解不出 packageId）⇒ `mod.reject file=<磁盘文件名> reason=… exception=…`
 *   取证面，按 `ModManageDiagnostics` 规则 4 刻意不进设置页；
 * - CSV 注入位容量越界（M2-BUG-02 补判据 8；`ModCsvCapacityLimitException.reasons` 是表级
 *   文本、无结构化包归因）⇒ 同 `mod.reject` 取证面，不伪造包级归因；
 * - 三种都**原实例重抛**（类型/消息/时机不变，玩家仍看失败屏）。
 *
 * 判据：同一异常形态不论从前置段还是链装配抛出，写出的行**逐字一致**；且两条路径各自
 * 只留一次痕（双份留痕负控）。
 */
class ModPreloadRejectTraceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val logs: MutableList<String> = mutableListOf()
    private val zh = SettingsStrings.of(SettingsLanguage.ZH)

    @Before
    fun setUp() {
        logs.clear()
        ModManageDiagnostics.reset()
    }

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
        // csvLoadContext 的开点是生产缝（收口在 attach）；单测没有 attach，这里手动收口。
        ManifestCodec.closeDecodeScope()
    }

    // ==================================================================
    // 判据 1：前置段声明冲突 ⇒ 双边 canonical 行 + 诊断面 + 设置页
    // ==================================================================

    @Test
    fun `前置段声明冲突 - 双边逐包canonical行且诊断面与设置页可读`() {
        val root = gameRoot("preload-conflict")
        writeConflictingManifests(root)

        val failure = capturePreloadReject(root)
        assertTrue(
            "前置段仍须抛原类型 DeclarationConflictException，实际=${failure::class}",
            failure is DeclarationConflictException,
        )
        assertTrue("拒载消息不得被改写：${failure.message}", failure.message!!.contains("声明事务冲突"))

        val lines = installLines()
        assertEquals("冲突双边必须各一条 canonical 行，实际=$lines", 2, lines.size)
        for (packageId in listOf(PKG_A, PKG_B)) {
            val mine = lines.filter { it.startsWith("mod.install pkg=$packageId ") }
            assertEquals("每包恰一行（重复即双份留痕）：pkg=$packageId lines=$lines", 1, mine.size)
            val line = mine.single()
            assertTrue(
                "异常名须=实际类型：$line",
                line.startsWith("mod.install pkg=$packageId active=false failure=DeclarationConflictException: "),
            )
            assertTrue("行内原因须带冲突正文：$line", line.contains("声明事务冲突"))
            assertFalse("留痕行必须折单行：$line", line.contains('\n'))
        }

        val snapshot = ModManageDiagnostics.snapshot()
        assertEquals("诊断面必须收入两个包：$snapshot", setOf(PKG_A, PKG_B), snapshot.keys)
        for ((packageId, reason) in snapshot) {
            assertTrue("pkg=$packageId 的摘要须带异常名: $reason", reason.startsWith("DeclarationConflictException: "))
        }

        val section = settingsFailureSection(ModManageScanner.scan(root), snapshot)
        assertEquals("设置页失败小节：标题 + 逐包一条", 3, section.size)
        assertEquals("小节标题", zh.modManageFailureHeading, section[0])
    }

    // ==================================================================
    // 判据 2：前置段坏 manifest 字节 ⇒ mod.reject 取证行，不进诊断面
    // ==================================================================

    @Test
    fun `前置段坏manifest字节 - mod_reject取证行点名磁盘文件且不进诊断面`() {
        val root = gameRoot("preload-bad-manifest")
        writeManifest(root, PKG_A, "a.manifest")
        File(rt2(root), BROKEN_MANIFEST).writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))

        val failure = capturePreloadReject(root)
        assertTrue("坏 manifest 必须仍抛 ManifestFormatException，实际=${failure::class}", failure is ManifestFormatException)

        val canonical = installLines()
        assertTrue("坏字节解不出 packageId ⇒ 不得伪造包级归因：$canonical", canonical.isEmpty())
        val rejects = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("必须恰一条 mod.reject 取证行，实际=$rejects", 1, rejects.size)
        val reject = rejects.single()
        assertTrue(
            "mod.reject 须点名真实坏档文件（不是清单摘要）：$reject",
            reject.startsWith("mod.reject file=$BROKEN_MANIFEST reason=manifest 字节坏档："),
        )
        assertTrue("mod.reject 须带实际异常类型：$reject", reject.contains("exception=ManifestFormatException"))
        assertFalse("取证行必须折单行：$reject", reject.contains('\n'))
        assertTrue(
            "mod.reject 按纪律不得进诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ==================================================================
    // 判据 8（M2-BUG-02）：前置段 CSV 容量越界 ⇒ mod.reject 取证行 + 原样重抛
    // ==================================================================

    @Test
    fun `前置段CSV容量越界 - mod_reject取证行点名违规且异常原样重抛`() {
        val root = gameRoot("preload-capacity")
        // 空目录 Base 容量=0；声明显式下标 5000 ⇒ 推导容量 5001、增量 5001 越硬上界 4096
        // （ModCsvValues.kt MAX_INJECTION_INCREMENT）⇒ ModCsvPlanLoader.load 整包拒。
        writeManifestWith(
            root,
            "cap.manifest",
            ModuleDescriptor(
                packageId = PKG_A,
                csvPatches = listOf(CsvKeywordPatch(tableName = "ABL", index = 5000, keyword = "MODX")),
            ),
        )

        val failure = capturePreloadReject(root)
        assertTrue(
            "容量越界必须仍抛 ModCsvCapacityLimitException，实际=${failure::class}",
            failure is ModCsvCapacityLimitException,
        )
        val reason = requireNotNull(failure.message) { "容量越界异常必须带消息" }
        assertTrue(
            "越界原因（违规原文）不得被改写：$reason",
            reason.contains("表 Abl") && reason.contains("声明要求 5001"),
        )

        val canonical = installLines()
        assertTrue(
            "表级违规无结构化包归因 ⇒ 不得伪造包级 canonical 行：$canonical",
            canonical.isEmpty(),
        )
        val rejects = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("必须恰一条 mod.reject 取证行，实际=$rejects", 1, rejects.size)
        val reject = rejects.single()
        assertTrue(
            "mod.reject 须点名磁盘 manifest：$reject",
            reject.startsWith("mod.reject file=cap.manifest reason=表 Abl"),
        )
        assertTrue("mod.reject 须带违规原文：$reject", reject.contains("声明要求 5001"))
        assertTrue(
            "mod.reject 须带实际异常类型：$reject",
            reject.contains("exception=ModCsvCapacityLimitException"),
        )
        assertFalse("取证行必须折单行：$reject", reject.contains('\n'))
        assertTrue(
            "mod.reject 按纪律不得进诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ==================================================================
    // 判据 3：同一形态两条路径行文本逐字一致（复用而非重造）
    // ==================================================================

    @Test
    fun `同形态从链内与从前置抛出 - 行文本逐字对拍且异常同型同消息`() {
        val root = gameRoot("preload-parity")
        writeConflictingManifests(root)
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        // 前置段：真顺序（csvLoadContextTraced 的 try 只包 csvLoadContext）。
        val preloadLines = mutableListOf<String>()
        val preloadFailure = runCatching {
            ModPreloadTrace.csvLoadContextTraced(root, { preloadLines += it })
        }.exceptionOrNull()
        assertTrue("前置段必须被声明冲突拒载，实际=$preloadFailure", preloadFailure is DeclarationConflictException)
        assertEquals("前置段须写双边两条 canonical 行：$preloadLines", 2, preloadLines.size)
        val preloadError = requireNotNull(preloadFailure) { "前置段必须抛异常，实际没抛" }

        // 链装配：同一夹具、同一异常形态。
        logs.clear()
        ModManageDiagnostics.reset()
        val chainFailure = captureChainReject(root)
        val chainLines = installLines()
        assertEquals("链内须写双边两条 canonical 行：$chainLines", 2, chainLines.size)

        assertEquals(
            "同一形态两条路径的行文本必须逐字一致（复用同一实现，不是第二份）",
            chainLines.sorted(),
            preloadLines.sorted(),
        )
        assertEquals("异常类型不得因路径不同被改写", chainFailure::class, preloadError::class)
        assertEquals("异常消息不得因路径不同被改写", chainFailure.message, preloadError.message)
    }

    // ==================================================================
    // 判据 4：正常路径零留痕
    // ==================================================================

    @Test
    fun `正常前置装载 - 零留痕且上下文照常产出`() {
        val root = gameRoot("preload-ok")
        writeManifest(root, PKG_A, "a.manifest")

        val context = ModPreloadTrace.csvLoadContextTraced(root, ::recordLine)
        assertNotNull("合法 manifest 必须产出上下文（关态/空表不是拒载）", context)
        assertTrue("正常路径不得有任何 mod.install / mod.reject 行：$logs", logs.isEmpty())
        assertTrue("正常路径诊断面必须为空：${ModManageDiagnostics.snapshot()}", ModManageDiagnostics.snapshot().isEmpty())
    }

    // ==================================================================
    // 判据 5：双份留痕负控（catch 不重叠 + 各路径各恰一行）
    // ==================================================================

    @Test
    fun `双份留痕负控 - 两条catch互斥且同一异常每条路径只留一行`() {
        val root = gameRoot("preload-matrix")
        writeConflictingManifests(root)
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        val preloadLines = mutableListOf<String>()
        val preloadFailure = runCatching {
            ModPreloadTrace.csvLoadContextTraced(root, { preloadLines += it })
        }.exceptionOrNull()
        assertTrue("前置段须被拒载", preloadFailure is DeclarationConflictException)
        assertEquals("前置段两只包各一行、无重复：$preloadLines", 2, preloadLines.size)
        assertEquals(
            "前置段不得对同一包写两行（双份留痕本质）：$preloadLines",
            listOf(PKG_A, PKG_B),
            preloadLines.map { it.substringAfter("mod.install pkg=").substringBefore(' ') }.sorted(),
        )

        logs.clear()
        ModManageDiagnostics.reset()
        val chainFailure = captureChainReject(root)
        assertTrue("链装配须被拒载", chainFailure is DeclarationConflictException)
        assertEquals("链内两只包各一行、无重复：$logs", 2, logs.size)

        // 源码哨兵：两处 catch **互斥**（各自只包自己那一段），否则同一异常会被记两次。
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "GameHost 前置段必须走留痕包装（同一 modLogSink 实例）",
            host.contains("ModPreloadTrace.csvLoadContextTraced(gameRoot, modLogSink)"),
        )
        assertFalse(
            "GameHost 不得自己 catch 前置段异常（catch 只在真生产入口内，避免双份）",
            host.contains("catch (e: DeclarationConflictException)") ||
                host.contains("catch (e: ManifestFormatException)"),
        )
        val preloadAt = host.indexOf("ModPreloadTrace.csvLoadContextTraced(gameRoot, modLogSink)")
        val chainAt = host.indexOf("ModChainAssembly.assembleWithCatalog(")
        assertTrue("两段调用都必须存在：$preloadAt/$chainAt", preloadAt >= 0 && chainAt > preloadAt)

        val runtime = sourceOf("ModRuntimeAssembly.kt")
        val tracedAt = runtime.indexOf("fun csvLoadContextTraced(")
        assertTrue("前置段留痕包装必须存在", tracedAt >= 0)
        val bodyEnd = runtime.indexOf("\n    }\n", tracedAt) + "\n    }\n".length
        val tracedBody = runtime.substring(tracedAt, bodyEnd)
        assertTrue(
            "包装体内须 catch DeclarationConflictException",
            tracedBody.contains("catch (e: DeclarationConflictException)"),
        )
        assertTrue(
            "包装体内须 catch ManifestFormatException",
            tracedBody.contains("catch (e: ManifestFormatException)"),
        )
        assertFalse(
            "前置段包装不得把链装配也包进去（内部已有 catch → 双份留痕）",
            tracedBody.contains("assembleWithCatalog"),
        )

        val chain = sourceOf("ModChainAssembly.kt")
        // 钉活 catch 面（3-d 起链侧 SymbolConflict/OverrideSignature 两个死 catch 已删——d6da78151
        // 撞名三态改告警放行后这两个异常在生产不再抛，见 ModChainAssemblyRejectTraceTest 判据7 注释）。
        assertTrue(
            "链接期拒载必须在链内 catch（前置段收不到链接期异常）",
            chain.contains("catch (e: ImportResolutionException)") && chain.contains("catch (e: RelocationException)"),
        )
    }

    // ==================================================================
    // 判据 6：RelocationException（CALL_SUPER 无前实现）—— 无结构化归因 ⇒ 只走 mod.reject
    // ==================================================================

    /**
     * 旧例钉 SymbolConflictException 混入 base 占位 —— d6da78151（KI-2）把撞名三态改判为
     * 告警放行（SymbolCatalog.kt:297-335 reportOverrideVerdict），该异常生产不再抛，
     * 链侧对应 catch 已随 3-d 删死码移除。本例换钉同形状的活体面：RelocationException
     * 无包级归因字段（ModChainAssembly.kt:359-362 对 rethrowTraced 传 emptyList）⇒
     * 零 canonical、恰一条 mod.reject、不进诊断面。夹具与
     * ModChainAssemblyRejectTraceAssemblyGateTest::CALL_SUPER缺前实现整链拒_无包级归因改走mod_reject
     * 同形互指（对拍面 = 两个独立测试类装置下留痕行逐字一致，漂移时两侧同时红）。
     */
    @Test
    fun `链接期重排闸整链拒_调用超体无前实现 - 无包级归因改走mod_reject且不进诊断面`() {
        val root = gameRoot("preload-relocation-super")
        writeManifest(root, PKG_A, "a.manifest")
        val b = CodeBuilder()
        val entry = b.declareFunction("ONLY_HERE", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val module = CompiledModule(PKG_A, b.build(entry), listOf(FuncMeta(index = 0, key = "ONLY_HERE")), emptyMap())
        File(rt2(root), "$PKG_A.erbm").writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = true))

        val failure = captureChainReject(root)
        assertTrue("重排闸必须仍抛 RelocationException，实际=${failure::class}", failure is RelocationException)
        assertTrue("拒载消息须点名 CALL_SUPER：${failure.message}", failure.message!!.contains("CALL_SUPER"))
        val canonical = installLines()
        assertTrue("无结构化归因 ⇒ 不得伪造包级 canonical 行：$canonical", canonical.isEmpty())
        val rejects = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("必须恰一条 mod.reject 取证行，实际=$rejects", 1, rejects.size)
        val reject = rejects.single()
        assertTrue("mod.reject 须点名盘上文件名：$reject", reject.startsWith("mod.reject file=a.manifest reason="))
        assertTrue("mod.reject 须带实际异常类型：$reject", reject.contains("exception=RelocationException"))
        assertTrue(
            "mod.reject 按纪律不得进诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ==================================================================
    // 判据 7（2026-11-26 判支柱改判删除，非失夹具）：SymbolConflictException
    //   providers 纯 packageId ⇒ 逐包 canonical + 诊断面两包。
    // 旧形态依赖链相位撞名硬拒——d6da78151（KI-2）改判为三态告警放行
    //（SymbolCatalog.kt:297-335 reportOverrideVerdict：last-wins，不再 throw），
    // 该异常生产不再抛；链侧 catch 已随 3-d 删死码（ModChainAssembly.kt）。
    // 「多归属包 ⇒ 逐包 canonical + 诊断面逐包」的活体形状已由两侧钉死，不另造桩凑格：
    //   ModChainAssemblyRejectTraceTest.kt 的 AssemblyGateTest 例
    //   多包同时超minApi_逐包各一条且与拒载消息同源同序（链入口，LinkOrderException 两包各一 canonical）；
    //   本文件判据 1（前置入口，DeclarationConflictException 两包 canonical + 诊断面两 key + 设置页小节）。
    // ==================================================================

    // ==================================================================
    // 夹具与助手
    // ==================================================================

    private fun recordLine(line: String) {
        logs += line
        ModManageDiagnostics.record(line)
    }

    /** 生产前置入口（`GameHost.kt` 同一调用形态）：留痕走 [recordLine]。 */
    private fun capturePreloadReject(root: File): Throwable {
        try {
            ModPreloadTrace.csvLoadContextTraced(root, ::recordLine)
            fail("夹具必须在前置装载段被拒载（否则测不到失败形态）")
        } catch (expected: RuntimeException) {
            return expected
        }
        error("unreachable")
    }

    /** 链装配入口（`ModChainAssembly.assemble`）：用于对拍与双份留痕负控。 */
    private fun captureChainReject(root: File): Throwable {
        try {
            ModChainAssembly.assemble(ChainAssemblyInputs(root, true, base(), baseMetas, logSink = ::recordLine))
            fail("夹具必须被整链拒载（否则测不到失败形态）")
        } catch (expected: RuntimeException) {
            return expected
        }
        error("unreachable")
    }

    private fun installLines(): List<String> = logs.filter { it.startsWith("mod.install pkg=") }

    /** 设置页「本会话装载失败」小节渲染输入（`SettingsActivity.kt:4976-4996` 同一表达式）。 */
    private fun settingsFailureSection(scan: ModManageScan, failures: Map<String, String>): List<String> {
        if (failures.isEmpty()) return emptyList()
        val titles = scan.mods.associate { it.packageId to it.title }
        return listOf(zh.modManageFailureHeading) +
            failures.map { (packageId, reason) -> zh.modManageFailureLine(titles[packageId] ?: packageId, reason) }
    }

    /** 游戏根：`CSV` 空目录（`GameDataLoader.loadBase` 空目录也返回非 null）+ `mods/rt2`。 */
    private fun gameRoot(name: String): File = tmp.newFolder(name).apply {
        File(this, "CSV").mkdirs()
        rt2(this)
    }

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    private fun writeManifest(root: File, packageId: String, fileName: String) {
        writeManifestWith(root, fileName, ModuleDescriptor(packageId = packageId))
    }

    private fun writeManifestWith(root: File, fileName: String, descriptor: ModuleDescriptor) {
        File(rt2(root), fileName).writeBytes(ManifestCodec.encode(descriptor))
    }

    private fun writeConflictingManifests(root: File) {
        writeManifestWith(
            root,
            "a.manifest",
            ModuleDescriptor(packageId = PKG_A, componentSchemas = listOf(ComponentSchema(PKG_A, "Shared"))),
        )
        writeManifestWith(
            root,
            "b.manifest",
            ModuleDescriptor(packageId = PKG_B, componentSchemas = listOf(ComponentSchema(PKG_B, "shared"))),
        )
    }

    private fun writeContainer(root: File, packageId: String, fileName: String) {
        writeContainerNamed(root, packageId, fileName, "ANEW_" + packageId.replace('.', '_').uppercase())
    }

    private fun writeContainerNamed(root: File, packageId: String, fileName: String, fnName: String) {
        val b = CodeBuilder()
        val entry = b.declareFunction(fnName, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val module = CompiledModule(packageId, b.build(entry), listOf(FuncMeta(index = 0, key = fnName)), emptyMap())
        File(rt2(root), fileName).writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = true))
    }

    private fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    private val baseMetas: List<FuncMeta> = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
    )

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        // 归一换行：worktree 检出是 CRLF，哨兵里的 \n 片段按 LF 文本断言。
        return candidates.firstOrNull { it.isFile }?.readText()?.replace("\r\n", "\n")
            ?: error("找不到 app/src/main/kotlin/com/eratw/port/android/$name（试了 ${candidates.joinToString { it.path }}）")
    }

    private companion object {
        private const val PKG_A: String = "mod.preload.a"
        private const val PKG_B: String = "mod.preload.b"
        private const val BROKEN_MANIFEST: String = "broken.manifest"
    }
}
