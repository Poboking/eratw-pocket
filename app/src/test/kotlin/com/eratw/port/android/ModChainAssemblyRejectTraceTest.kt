package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.ErbcWriter
import com.eratw.port.core.code.Instruction
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.OpcodeRelocation
import com.eratw.port.core.code.PoolRef
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.patch.CallSentinels
import com.eratw.port.core.patch.ContainerDescriptor
import com.eratw.port.core.patch.ContainerRole
import com.eratw.port.core.patch.DeclarationConflictException
import com.eratw.port.core.patch.ImportResolutionException
import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.LinkedSegments
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ManifestFormatException
import com.eratw.port.core.patch.ModuleContainer
import com.eratw.port.core.patch.ModuleContainerCodec
import com.eratw.port.core.patch.ModuleContainerFormat
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.RelocationEntry
import com.eratw.port.core.patch.RelocationException
import com.eratw.port.core.patch.RequiredFeatures
import com.eratw.port.core.patch.ZeroBasePools
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.modapi.ModApi
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lane DP（task-114）+ Lane DS（task-117）：`ModChainAssembly` **整链级拒载**的**逐包留痕契约**。
 *
 * 契约：整链拒载发生在 `attach` 之前、整链 fail-closed，会话侧 canonical 行生产者
 * （`ModRuntimeAssembly.attach` 按 install 报告逐包写 `mod.install …`）根本走不到；因此装配层
 * 必须在 `throw` **之前**按包级补一条与会话侧**逐字同形**的
 * `mod.install pkg=<pkg> active=false failure=<实际异常名>: <原因>` 行，写进同一个
 * `logSink`（生产缝 = `GameHost` 装配期与 attach 期共用的同一个 lambda）。
 * `<实际异常名>` 必须与抛出类型一致：minApi 闸写 `LinkOrderException`、坏容器与档位闸写
 * `IllegalArgumentException`（诊断面把 `<异常名>: <原因>` 原样展示给玩家与取证者，
 * 写错即误导）。
 *
 * 本波（DS）新增两类：
 * 1. 档位闸（`container.allowSuperCalls == false`）：容器已解出 packageId ⇒ 归因取容器自述
 *    值（唯一确定），写 canonical 行；
 * 2. 归因不可靠的坏档（文件名词干不合 packageId 形状）：**不伪造包名**，改走
 *    `mod.reject file=<磁盘文件名> reason=<原因> exception=<实际异常类型>` 显式 tag ——
 *    前缀不是 `mod.install pkg=`，诊断面按既有规则静默忽略（不进设置页失败小节），只留
 *    启动失败屏与 logcat；由用例 7 的「日志有 / 诊断面无」两面对锁。
 *
 * 本类的断言面三列：
 * 1. 装配期 logSink 收到的行（`ModChainAssembly.assemble(ChainAssemblyInputs(logSink = …))`）；
 * 2. `ModManageDiagnostics.snapshot()` —— **既有解析器零改动**就必须收入（行形状是唯一契约）；
 * 3. 设置页失败小节的渲染输入表达式（与 `ModFailureVisibilityMatrixE2ETest` 同源）。
 *
 * 留痕只记账不裁决：拒载的异常类型 / 消息 / 时机与本笔之前逐字一致（用例 2 显式钉
 * `IllegalArgumentException` 而非 `LinkOrderException`；用例 1 钉 `LinkOrderException`；
 * 用例 A 钉档位闸 IAE）；正常链零新增行、`.off` 停用包不拒载也不留痕（不是失败不得误报）。
 */
abstract class ModChainTraceTestBase {

    @get:Rule
    val tmp = TemporaryFolder()

    protected val logs: MutableList<String> = mutableListOf()

    protected val zh = SettingsStrings.of(SettingsLanguage.ZH)

    @Before
    fun setUp() {
        logs.clear()
        ModManageDiagnostics.reset()
    }

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    // ------------------------------------------------------------------

    // 夹具与助手
    // ==================================================================

    protected fun recordLine(line: String) {
        logs += line
        ModManageDiagnostics.record(line)
    }

    /** 真装配入口 + 注入留痕汇；期望整链拒载时返回捕获到的异常。 */
    protected fun captureReject(root: File, linkOrder: LinkOrder = LinkOrder()): Throwable {
        try {
            ModChainAssembly.assemble(
                ChainAssemblyInputs(root, true, base(), baseMetas, linkOrder = linkOrder, logSink = ::recordLine),
            )
            fail("夹具必须被整链拒载（否则测不到失败形态）")
        } catch (expected: RuntimeException) {
            return expected
        }
        error("unreachable")
    }

    protected fun installLines(): List<String> = logs.filter { it.startsWith("mod.install pkg=") }

    protected fun singleInstallLine(): String {
        val lines = installLines()
        assertEquals("整链拒载必须留且只留一条包级 canonical 行，实际=$lines", 1, lines.size)
        return lines.single()
    }

    /** 设置页「上次启动时装载失败」小节渲染输入（`SettingsActivity.kt:4976-4996` 同一表达式）。 */
    protected fun settingsFailureSection(scan: ModManageScan, failures: Map<String, String>): List<String> {
        if (failures.isEmpty()) return emptyList()
        val titles = scan.mods.associate { it.packageId to it.title }
        return listOf(zh.modManageFailureHeading) +
            failures.map { (packageId, reason) -> zh.modManageFailureLine(titles[packageId] ?: packageId, reason) }
    }

    protected fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    protected fun writeManifest(root: File, packageId: String, fileName: String, minApi: Int = 0) {
        writeManifestWith(root, fileName, ModuleDescriptor(packageId = packageId, minApi = minApi))
    }

    /** 带 requires / version / componentSchemas 的夹具变体（描述符直给，参数数不触 detekt）。 */
    protected fun writeManifestWith(root: File, fileName: String, descriptor: ModuleDescriptor) {
        File(rt2(root), fileName).writeBytes(ManifestCodec.encode(descriptor))
    }

    /**
     * 夹具（默认 Mod 包编译档）。[allowSuperCalls] = false 用于第三类整链级拒载
     * （`decodeErbmContainers` 的档位闸）。
     */
    protected fun writeContainer(root: File, packageId: String, fileName: String, allowSuperCalls: Boolean = true) {
        // 函数名按包名派生：同链多包各声明各的函数，避免夹自己造出符号重名（那是另一类拒载）。
        val fnName = "ANEW_" + packageId.replace('.', '_').uppercase()
        val b = CodeBuilder()
        val entry = b.declareFunction(fnName, b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val module = CompiledModule(packageId, b.build(entry), listOf(FuncMeta(index = 0, key = fnName)), emptyMap())
        File(rt2(root), fileName).writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = allowSuperCalls))
    }

    /**
     * 自定义容器体写入（Mod 包编译档位）：函数名 / 指令体由用例给定 —— 签名闸（覆盖 base
     * TARGET）、导入闸（externalCalls 点名缺提供者）、重定位闸（加性函数体内 CALL SUPER）
     * 各自需要的模块形状不同。
     */
    protected fun writeContainerOf(root: File, fileName: String, module: CompiledModule) {
        File(rt2(root), fileName).writeBytes(ErbModuleContainer.encode(module, allowSuperCalls = true))
    }

    /**
     * 加性四池 MODULE 容器字节（mods 根目录第二源；与 `ModChainAssemblyContainerPatchEndToEndTest`
     * 同口径自 Program 派生，app 测试源集不能引用 core-vm 测试夹具，故在本文件内同源重建）。
     * [codeWordsOverride] 用于构造「自述面与载荷实体脱节」的容器：decode 的读侧闸全过，
     * 脱节由适配期的对账闸点名。
     */
    protected fun patchModuleBytes(packageId: String, codeWordsOverride: List<Int>? = null): ByteArray {
        val b = CodeBuilder()
        b.declareFunction("PATCHFN", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._PRINT_LITERAL, b.string("PATCHFN"), 1, 0, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val program = b.build(0)
        val words = program.codeSource.toIntArray()
        val callTargets = ArrayList<Int>()
        val relocations = ArrayList<RelocationEntry>()
        for (pc in 0 until program.instructionCount) {
            val w = pc * Instruction.WORDS
            val op = words[w]
            val reloc = OpcodeRelocation.of(op)
            for (slot in 0..2) {
                val category = reloc.at(slot)
                if (category == PoolRef.NONE) continue
                relocations += RelocationEntry(pc, op, slot, category)
                if (category == PoolRef.FUNCTION) callTargets += words[w + 1 + slot]
            }
        }
        return ModuleContainerCodec.encode(
            ModuleContainer(
                role = ContainerRole.MODULE,
                requiredFeatures = RequiredFeatures.REQUIRED_MASK,
                isaVersion = ModuleContainerFormat.ISA_VERSION,
                descriptor = ContainerDescriptor(packageId = packageId, artifactSha256 = "sha256-$packageId"),
                imports = emptyList(),
                callTargets = callTargets,
                relocations = relocations,
                exports = emptyList(),
                pools = ZeroBasePools(
                    longCount = program.constantCount,
                    stringCount = program.stringCount,
                    exprCount = program.exprCount,
                    statementCount = program.statementCount,
                    baseMarker = 0,
                    codeWords = codeWordsOverride ?: words.toList(),
                    poolBlob = ErbcWriter.encode(program).toList(),
                ),
            ),
        )
    }

    /** 自述脱节变体：codeWords 比载荷指令流多一字（对账 3 点名）。 */
    protected fun driftedContainerBytes(packageId: String): ByteArray {
        val b = CodeBuilder()
        b.declareFunction("PATCHFN", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val words = b.build(0).codeSource.toIntArray()
        return patchModuleBytes(packageId, codeWordsOverride = words.toList() + 0)
    }

    /** LINKED_PROGRAM 态容器字节（三段占位；decode 可过、适配期按 role 整包拒）。 */
    protected fun linkedContainerBytes(packageId: String): ByteArray = ModuleContainerCodec.encode(
        ModuleContainer(
            role = ContainerRole.LINKED_PROGRAM,
            requiredFeatures = RequiredFeatures.REQUIRED_MASK,
            isaVersion = ModuleContainerFormat.ISA_VERSION,
            descriptor = ContainerDescriptor(packageId = packageId, artifactSha256 = "sha256-$packageId"),
            imports = emptyList(),
            callTargets = emptyList(),
            relocations = emptyList(),
            exports = emptyList(),
            linkedSegments = LinkedSegments(List(4) { 0 }, List(4) { 1 }, List(4) { 2 }),
        ),
    )

    protected fun base(): Program {
        val b = CodeBuilder()
        val entry = b.declareFunction("ENTRY", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        b.declareFunction("TARGET", b.here(), arity = 1, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        return b.build(entry)
    }

    protected val baseMetas: List<FuncMeta> = listOf(
        FuncMeta(index = 0, key = "ENTRY"),
        FuncMeta(index = 1, key = "TARGET", argIntCount = 1),
    )

    companion object {
        const val PKG_A: String = "mod.trace.a"
        const val PKG_B: String = "mod.trace.b"
        const val PKG_GHOST: String = "mod.trace.ghost"
    }
}

class ModChainAssemblyRejectTraceTest : ModChainTraceTestBase() {

    // 1) minApi 整链拒载：逐包一条 canonical 行 → 诊断面 → 设置页小节
    // ------------------------------------------------------------------

    @Test
    fun `minApi整链拒_抛前逐包写与会话同形canonical行且诊断面与设置页可读`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", ModApi.VERSION + 1)
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val failure = captureReject(root)
        assertTrue("minApi 闸必须仍抛 LinkOrderException（类型不得被留痕改动）", failure is LinkOrderException)
        val message = failure.message!!
        assertTrue("拒载消息不得被改写：$message", message.contains("请更新 app"))
        assertTrue("拒载消息须点名包名：$message", message.contains(PKG_A))

        val line = singleInstallLine()
        assertEquals(
            "canonical 行必须与会话侧同形（前缀逐字）：$line",
            "mod.install pkg=$PKG_A active=false failure=LinkOrderException: ",
            line.substringBefore("包 ["),
        )
        assertTrue("行内原因须带 min-api 读数：$line", line.contains("min-api ${ModApi.VERSION + 1}"))
        assertTrue("行内原因须带当前 VERSION：$line", line.contains("ModApi.VERSION ${ModApi.VERSION}"))

        val snapshot = ModManageDiagnostics.snapshot()
        val recorded = snapshot[PKG_A]
        assertNotNull("诊断面（既有解析器零改动）必须收入该行，实际=$snapshot", recorded)
        assertTrue("摘要须带异常类型：$recorded", recorded!!.startsWith("LinkOrderException: "))
        assertTrue("摘要须带原因：$recorded", recorded.contains("min-api ${ModApi.VERSION + 1}"))

        val scan = ModManageScanner.scan(root)
        val section = settingsFailureSection(scan, snapshot)
        assertEquals("设置页失败小节：标题 + 逐包一条", 2, section.size)
        assertEquals("小节标题", zh.modManageFailureHeading, section[0])
        assertTrue("失败行须带包名与原因：${section[1]}", section[1].contains("min-api ${ModApi.VERSION + 1}"))
    }

    // ------------------------------------------------------------------
    // 2) 坏 .erbm 容器：按包级一条 canonical 行，异常类型/文案不变
    // ------------------------------------------------------------------

    @Test
    fun `坏erbm容器整链拒_抛前按包级写canonical行且诊断面可读`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", minApi = 0)
        Files.write(rt2(root).resolve("$PKG_A.erbm").toPath(), "not-a-container".toByteArray())

        val failure = captureReject(root)
        assertTrue("坏容器拒载类型不得被改动：${failure.javaClass.name}", failure is IllegalArgumentException)
        val message = failure.message!!
        assertTrue("拒载消息须点名坏档文件：$message", message.contains("$PKG_A.erbm"))
        assertTrue("拒载消息须保留 fail-closed 归因：$message", message.contains("fail-closed"))

        val line = singleInstallLine()
        assertTrue(
            "行须带同形前缀与包级归因：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=IllegalArgumentException: "),
        )
        assertTrue("行内原因须点名坏档文件：$line", line.contains("$PKG_A.erbm"))
        assertTrue("行内原因须保留 fail-closed：$line", line.contains("fail-closed"))

        val reason = ModManageDiagnostics.snapshot()[PKG_A]
        assertNotNull("诊断面必须收入坏容器拒载，实际=${ModManageDiagnostics.snapshot()}", reason)
        assertTrue("摘要须带异常类型：$reason", reason!!.startsWith("IllegalArgumentException: "))
        assertTrue("摘要须点名坏档文件：$reason", reason.contains("$PKG_A.erbm"))
        assertTrue("摘要须保留 fail-closed：$reason", reason.contains("fail-closed"))
    }

    // ------------------------------------------------------------------
    // 3) 多包同时不过：逐包各一条，且与拒载消息同源同序
    // ------------------------------------------------------------------

    @Test
    fun `多包同时超minApi_逐包各一条且与拒载消息同源同序`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", ModApi.VERSION + 1)
        writeManifest(root, PKG_B, "b.manifest", ModApi.VERSION + 2)
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        val failure = captureReject(root)
        val lines = installLines()
        assertEquals("多包同时越闸 ⇒ 逐包各一条，实际=$lines", 2, lines.size)
        assertTrue(lines[0].startsWith("mod.install pkg=$PKG_A active=false failure=LinkOrderException: "))
        assertTrue(lines[1].startsWith("mod.install pkg=$PKG_B active=false failure=LinkOrderException: "))
        assertTrue("按 manifest 文件名序，首条带 A 的 min-api：${lines[0]}", lines[0].contains("min-api ${ModApi.VERSION + 1}"))
        assertTrue("次条带 B 的 min-api：${lines[1]}", lines[1].contains("min-api ${ModApi.VERSION + 2}"))

        val message = failure.message!!
        assertTrue("拒载消息与留痕同源（A）：$message", message.contains("min-api ${ModApi.VERSION + 1}"))
        assertTrue("拒载消息与留痕同源（B）：$message", message.contains("min-api ${ModApi.VERSION + 2}"))
        assertTrue("多包仍按既有分隔符聚合：$message", message.contains("；"))

        val snapshot = ModManageDiagnostics.snapshot()
        assertEquals("诊断面须逐包收入：$snapshot", setOf(PKG_A, PKG_B), snapshot.keys)
    }

    // ------------------------------------------------------------------
    // 4) 正常链：零新增行（不是失败不得误报）
    // ------------------------------------------------------------------

    @Test
    fun `正常链_零mod_install行且诊断面为空`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", ModApi.VERSION)
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val plan = ModChainAssembly.assemble(ChainAssemblyInputs(
            gameRoot = root,
            enabled = true,
            base = base(),
            baseMetas = baseMetas,
            logSink = ::recordLine,
        ))
        assertNotNull("达标链必须装配成功", plan)
        assertTrue("正常链不得产生任何 mod.install 行：$logs", installLines().isEmpty())
        assertTrue("正常链诊断面必须为空：${ModManageDiagnostics.snapshot()}", ModManageDiagnostics.snapshot().isEmpty())
    }

    // ------------------------------------------------------------------
    // 5) .off 停用对照：停用包连容器都不进装配面 ⇒ 不拒载、不留痕
    // ------------------------------------------------------------------

    @Test
    fun `off停用对照_停用包的坏容器与超版manifest不拒载也不留痕`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", ModApi.VERSION + 1)
        Files.write(rt2(root).resolve("$PKG_A.erbm").toPath(), "not-a-container".toByteArray())
        assertTrue("停用标记必须写成功", ModEnablement.setEnabled(rt2(root), PKG_A, false))

        val plan = ModChainAssembly.assemble(ChainAssemblyInputs(
            gameRoot = root,
            enabled = true,
            base = base(),
            baseMetas = baseMetas,
            logSink = ::recordLine,
        ))
        assertNull("停用包的两源都被 .off 闸摘掉 ⇒ 整块旁路", plan)
        assertTrue("停用不是失败，不得留痕：$logs", logs.isEmpty())
        assertTrue("停用不是失败，诊断面必须为空", ModManageDiagnostics.snapshot().isEmpty())
    }

    // ------------------------------------------------------------------
    // 6) 归因边界：旧盘面文件名与包名不同名时取文件名词干（生产写侧恒定同名）
    // ------------------------------------------------------------------

    /**
     * 新增边界用例（文档化，不是放宽判据）：坏容器字节解不开时容器里没有可读 packageId
     * （decode 先核尾部摘要），唯一同源线索是文件名词干（生产写侧恒定 `<packageId>.erbm`）。
     * 旧盘面 / 手工文件名与 manifest 包名不同名时，留痕行归因取词干；这行仍被诊断面收入，
     * 只是设置页显示名退化为词干（`titles[pkg] ?: pkg` 兜底）——比「整条丢失」强。
     */
    @Test
    fun `坏容器旧盘面文件名不同名_归因取文件名词干且仍进诊断面`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", minApi = 0)
        Files.write(rt2(root).resolve("legacy_name.erbm").toPath(), "not-a-container".toByteArray())

        val failure = captureReject(root)
        assertTrue("夹具必须是坏容器拒载：${failure.message}", failure.message!!.contains("legacy_name.erbm"))
        val line = singleInstallLine()
        assertTrue("归因取文件名词干：$line", line.startsWith("mod.install pkg=legacy_name active=false failure="))
        assertNotNull("词干是合法 packageId 形状 ⇒ 既有解析器照收，不丢记录", ModManageDiagnostics.snapshot()["legacy_name"])
    }

    // ------------------------------------------------------------------
    // 7) 归因不可靠：改走 mod.reject 显式 tag（刻意不进设置页失败小节）
    // ------------------------------------------------------------------

    /**
     * 归因保真度契约（Lane DS）：文件名不合 packageId 形状时**不伪造包名**，改走
     * `mod.reject file=<磁盘文件名> reason=<原因> exception=<实际异常类型>` 显式 tag ——
     * 前缀不是 `mod.install pkg=`，故诊断面按既有判定规则 4 静默忽略（设置页不会冒出一行
     * 名字是「坏档」的伪包），但该行仍在 logSink（`Log.i` 取证面）与启动失败屏里可查。
     */
    @Test
    fun `坏容器文件名不合包名形状_改走mod_reject显式tag且诊断面按纪律不受理`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", minApi = 0)
        Files.write(rt2(root).resolve("坏档.erbm").toPath(), "not-a-container".toByteArray())

        captureReject(root)
        assertTrue("归因不可靠时不得写 canonical 行（不伪造包名）：$logs", installLines().isEmpty())
        val rejectLines = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("不可归因的整链拒载必须留且只留一条 mod.reject 行，实际=$logs", 1, rejectLines.size)
        val line = rejectLines.single()
        assertTrue("显式带磁盘文件名：$line", line.startsWith("mod.reject file=坏档.erbm reason="))
        assertTrue("行内原因须带 fail-closed 判据：$line", line.contains("fail-closed"))
        assertTrue(
            "显式带实际异常名（须与抛出类型一致）：$line",
            line.contains(" exception=IllegalArgumentException"),
        )
        assertTrue(
            "mod.reject 刻意不进设置页失败小节：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ------------------------------------------------------------------
}

class ModChainAssemblyRejectTraceAssemblyGateTest : ModChainTraceTestBase() {

    // 装配闸族（原 8~15 节）：同一留痕纪律，按闸位分族独立成类（detekt LargeClass 闸）。

    // 8) 非 Mod 编译档容器（allowSuperCalls=false）：整链拒载 + 包级 canonical 行
    // ------------------------------------------------------------------

    /**
     * 档位闸（task-117 A）：容器能正常解码、packageId 可读 ⇒ 归因**唯一确定**，写与会话侧
     * 逐字同形的 canonical 行；抛的仍是 `IllegalArgumentException`（类型/消息/时机不得被
     * 留痕改动），消息原样保留档位判据与 fail-closed 措辞。
     */
    @Test
    fun `非Mod编译档容器整链拒_抛前按容器packageId写canonical行且诊断面可读`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", minApi = 0)
        writeContainer(root, PKG_A, "$PKG_A.erbm", allowSuperCalls = false)

        val failure = captureReject(root)
        assertTrue(
            "档位闸拒载类型必须是 IllegalArgumentException（不得被留痕改动）：${failure.javaClass.name}",
            failure is IllegalArgumentException,
        )
        val message = failure.message!!
        assertTrue("拒载消息不得被改写：$message", message.contains("allowSuperCalls=false"))
        assertTrue("拒载消息须保留 fail-closed 判据：$message", message.contains("fail-closed"))

        val line = singleInstallLine()
        assertTrue(
            "canonical 行前缀须与会话侧同形且异常名=实际类型 IAE：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=IllegalArgumentException: "),
        )
        assertTrue("行内原因须带档位判据：$line", line.contains("allowSuperCalls=false"))

        val snapshot = ModManageDiagnostics.snapshot()
        val recorded = snapshot[PKG_A]
        assertNotNull("诊断面（既有解析器零改动）必须收入该行，实际=$snapshot", recorded)
        assertTrue("摘要须带实际异常类型：$recorded", recorded!!.startsWith("IllegalArgumentException: "))
        assertTrue("摘要须带档位判据：$recorded", recorded.contains("allowSuperCalls=false"))
    }

    /**
     * 反面对照：`allowSuperCalls=true`（Mod 包编译档）不触发档位闸 ⇒ 链照常装配，
     * 零 canonical 行、零 `mod.reject` 行、诊断面为空（放行不是失败，不得误报）。
     */
    @Test
    fun `Mod编译档容器_正常装配且零拒载留痕`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest", minApi = 0)
        writeContainer(root, PKG_A, "$PKG_A.erbm", allowSuperCalls = true)

        val plan = ModChainAssembly.assemble(
            ChainAssemblyInputs(
                gameRoot = root,
                enabled = true,
                base = base(),
                baseMetas = baseMetas,
                logSink = ::recordLine,
            ),
        )
        assertNotNull("Mod 编译档容器必须正常进链", plan)
        assertTrue("放行档不得产生任何 mod.install 行：$logs", installLines().isEmpty())
        assertTrue("放行档不得产生任何 mod.reject 行：$logs", logs.none { it.startsWith("mod.reject ") })
        assertTrue(
            "放行档诊断面必须为空：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ==================================================================
    // Lane DW（task-121）：attach 之前整链拒载的「非 app 内判据」家族
    // ==================================================================

    /**
     * 声明事务冲突（核心 `DeclarationConflictException`，从 `ModChainAssembler` 的
     * freeze 步抛）：冲突恒带双边 `DeclarationSite.packageId` ⇒ 归因唯一，两个包各写一条
     * canonical 行；诊断面与设置页小节两边都能看到。
     */
    @Test
    fun `声明冲突整链拒_双边包各一条canonical行且诊断面与设置页可读`() {
        val root = tmp.newFolder("game")
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
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        val failure = captureReject(root)
        assertTrue(
            "声明冲突必须仍抛 DeclarationConflictException（类型不得被留痕改动），实际=${failure::class}",
            failure is DeclarationConflictException,
        )
        assertTrue("拒载消息不得被改写：${failure.message}", failure.message!!.contains("声明事务冲突"))

        val lines = installLines()
        assertEquals("冲突双边必须各一条 canonical 行，实际=$lines", 2, lines.size)
        assertTrue("两条行须点名两个包：$lines", lines.any { it.startsWith("mod.install pkg=$PKG_A ") })
        assertTrue("两条行须点名两个包：$lines", lines.any { it.startsWith("mod.install pkg=$PKG_B ") })
        for (line in lines) {
            assertTrue(
                "异常名须=实际类型 DeclarationConflictException：$line",
                line.startsWith("mod.install pkg=") &&
                    line.contains(" active=false failure=DeclarationConflictException: "),
            )
            assertTrue("行内原因须带冲突正文：$line", line.contains("声明事务冲突"))
            assertFalse("留痕行必须折单行（换行会把日志行拆碎）：$line", line.contains('\n'))
        }

        val snapshot = ModManageDiagnostics.snapshot()
        assertEquals("诊断面必须收入两个包：$snapshot", setOf(PKG_A, PKG_B), snapshot.keys)
        assertTrue(snapshot[PKG_A]!!.startsWith("DeclarationConflictException: "))
        assertTrue(snapshot[PKG_B]!!.startsWith("DeclarationConflictException: "))

        val section = settingsFailureSection(ModManageScanner.scan(root), snapshot)
        assertEquals("设置页失败小节：标题 + 逐包一条", 3, section.size)
        assertEquals("小节标题", zh.modManageFailureHeading, section[0])
    }

    /**
     * 缺硬依赖（核心 `LinkOrderException`，`ModuleLinkOrder.buildGraph` 抛）：被缺的目标
     * 不在组合里 ⇒ 归因**只给声明方**一条，不替不存在的包报装载失败。
     */
    @Test
    fun `缺硬依赖整链拒_归因只写声明方单条canonical行`() {
        val root = tmp.newFolder("game")
        writeManifestWith(
            root,
            "a.manifest",
            ModuleDescriptor(packageId = PKG_A, requires = listOf(PKG_GHOST)),
        )
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val failure = captureReject(root)
        assertTrue("缺依赖必须仍抛 LinkOrderException，实际=${failure::class}", failure is LinkOrderException)
        val link = failure as LinkOrderException
        assertEquals("归因只含声明方，不含组合外的被缺目标：${link.packageIds}", listOf(PKG_A), link.packageIds)

        val line = singleInstallLine()
        assertTrue(
            "canonical 行须点名声明方且异常名=LinkOrderException：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=LinkOrderException: "),
        )
        assertTrue("行内原因须带缺依赖判据：$line", line.contains("缺少硬依赖 [$PKG_GHOST]"))
        assertFalse("不得给组合外的包伪造留痕行：$logs", logs.any { it.contains("pkg=$PKG_GHOST ") })

        val snapshot = ModManageDiagnostics.snapshot()
        assertEquals("诊断面只收声明方：$snapshot", setOf(PKG_A), snapshot.keys)
    }

    /**
     * 显式排序配置内 packageId 重复（核心 `ModuleLinkOrder.plan` 的 :106）：这是**玩家配置面
     * 错误**，不指向盘上任何已装载包 ⇒ `packageIds` 空，按纪律改走 `mod.reject` 显式 tag，
     * 不进诊断面/设置页（启动失败屏与 logcat 可查）。
     */
    @Test
    fun `显式排序配置重复_配置面错误改走mod_reject且不进诊断面`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest")
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val failure = captureReject(root, linkOrder = LinkOrder(listOf(PKG_A, PKG_A)))
        assertTrue("配置重复必须仍抛 LinkOrderException，实际=${failure::class}", failure is LinkOrderException)
        val link = failure as LinkOrderException
        assertTrue("配置面错误不得伪造包级归因：${link.packageIds}", link.packageIds.isEmpty())
        assertTrue("消息不得被改写：${link.message}", link.message!!.contains("显式排序配置里 packageId 重复"))

        assertTrue("不可归因形态不得写 canonical 行：$logs", installLines().isEmpty())
        val rejects = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("必须恰一条显式 mod.reject 取证行，实际=$rejects", 1, rejects.size)
        val reject = rejects.single()
        assertTrue("mod.reject 须点名磁盘文件名：$reject", reject.startsWith("mod.reject file=a.manifest reason="))
        assertTrue("mod.reject 须带实际异常类型：$reject", reject.contains("exception=LinkOrderException"))
        assertTrue(
            "mod.reject 按纪律不得进诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    /**
     * 坏 manifest 字节（核心 `ManifestFormatException`，`readManifestSources` 解码期抛）：
     * 消息只点名行号、不含文件名/包名（坏字节根本解不出 packageId）⇒ 归因不可靠，改走
     * `mod.reject file=<磁盘文件名>`；异常类型/消息/时机原样透传。
     */
    @Test
    fun `坏manifest字节_归因不可靠改走mod_reject且异常原样透传`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest")
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        File(rt2(root), "broken.manifest").writeBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00))

        val failure = captureReject(root)
        assertTrue(
            "坏 manifest 必须仍抛 ManifestFormatException 原类型，实际=${failure::class}",
            failure is ManifestFormatException,
        )
        val rejects = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("必须恰一条显式 mod.reject 取证行，实际=$rejects", 1, rejects.size)
        val reject = rejects.single()
        assertTrue(
            "mod.reject 须点名坏 manifest 磁盘文件名：$reject",
            reject.startsWith("mod.reject file=broken.manifest reason="),
        )
        assertTrue("mod.reject 须带实际异常类型：$reject", reject.contains("exception=ManifestFormatException"))
        assertTrue("不可归因形态不得写 canonical 行：$logs", installLines().isEmpty())
        assertTrue(
            "mod.reject 按纪律不得进诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    /**
     * 版本区间不匹配（核心 `LinkOrderException`，:193）：dependent 与 target **双方都在
     * 组合里、都装不上** ⇒ 两个包各一条 canonical 行。
     */
    @Test
    fun `版本区间不匹配整链拒_涉事双方各一条canonical行`() {
        val root = tmp.newFolder("game")
        writeManifestWith(
            root,
            "a.manifest",
            ModuleDescriptor(packageId = PKG_A, version = "1.0", requires = listOf("$PKG_B@[1.0,2.0]")),
        )
        writeManifestWith(root, "b.manifest", ModuleDescriptor(packageId = PKG_B, version = "3.0"))
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        val failure = captureReject(root)
        assertTrue("版本越界必须仍抛 LinkOrderException，实际=${failure::class}", failure is LinkOrderException)
        val link = failure as LinkOrderException
        assertEquals("版本不匹配的涉事双方都可归因：${link.packageIds}", listOf(PKG_A, PKG_B), link.packageIds)

        val lines = installLines()
        assertEquals("涉事双方各一条 canonical 行，实际=$lines", 2, lines.size)
        assertTrue("两条行须点名双方：$lines", lines.any { it.startsWith("mod.install pkg=$PKG_A ") })
        assertTrue("两条行须点名双方：$lines", lines.any { it.startsWith("mod.install pkg=$PKG_B ") })
        assertTrue("行内原因须带版本区间判据：$lines", lines.all { it.contains("版本区间") })
        assertEquals("诊断面须收入双方", setOf(PKG_A, PKG_B), ModManageDiagnostics.snapshot().keys)
    }

    /**
     * 容器找不到同包名 manifest（`pairSources` 的整包拒）：容器自述 packageId 是唯一可
     * 归因来源（缺失的 manifest 可能压根不在盘上）⇒ 写容器包名的 canonical 行。
     */
    @Test
    fun `容器缺同包名manifest_归因容器自述packageId写canonical行`() {
        val root = tmp.newFolder("game")
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val failure = captureReject(root)
        assertTrue("配对不齐必须仍抛 IllegalArgumentException，实际=${failure::class}", failure is IllegalArgumentException)
        assertTrue("消息不得被改写：${failure.message}", failure.message!!.contains("找不到同包名 manifest"))
        val line = singleInstallLine()
        assertTrue(
            "canonical 行须点名容器自述 packageId：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=IllegalArgumentException: "),
        )
        assertEquals("诊断面须收入该包", setOf(PKG_A), ModManageDiagnostics.snapshot().keys)
    }

    /**
     * 反面对照（task-121 D5）：正常两包链零 `mod.install` / 零 `mod.reject` / 诊断面空
     * —— 留痕只记账不误报。
     */
    @Test
    fun `正常两包链_零拒载留痕且诊断面为空`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest")
        writeManifest(root, PKG_B, "b.manifest")
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        val plan = ModChainAssembly.assemble(
            ChainAssemblyInputs(
                gameRoot = root,
                enabled = true,
                base = base(),
                baseMetas = baseMetas,
                logSink = ::recordLine,
            ),
        )
        assertNotNull("正常两包链必须装配成功", plan)
        assertTrue("正常装配不得有 canonical 行：$logs", installLines().isEmpty())
        assertTrue("正常装配不得有 mod.reject 行：$logs", logs.none { it.startsWith("mod.reject ") })
        assertTrue("正常装配诊断面必须为空：${ModManageDiagnostics.snapshot()}", ModManageDiagnostics.snapshot().isEmpty())
    }

    /**
     * 依赖环（核心 `LinkOrderException`，:233 `blocked` 全表）：环上每个包都在组合里 ⇒
     * 逐包 canonical 行。
     */
    @Test
    fun `依赖环整链拒_环上包逐条canonical行`() {
        val root = tmp.newFolder("game")
        writeManifestWith(root, "a.manifest", ModuleDescriptor(packageId = PKG_A, requires = listOf(PKG_B)))
        writeManifestWith(root, "b.manifest", ModuleDescriptor(packageId = PKG_B, requires = listOf(PKG_A)))
        writeContainer(root, PKG_A, "$PKG_A.erbm")
        writeContainer(root, PKG_B, "$PKG_B.erbm")

        val failure = captureReject(root)
        assertTrue("依赖环必须仍抛 LinkOrderException，实际=${failure::class}", failure is LinkOrderException)
        assertTrue("消息不得被改写：${failure.message}", failure.message!!.contains("模块依赖环"))
        val lines = installLines()
        assertEquals("环上两个包各一条 canonical 行，实际=$lines", 2, lines.size)
        assertEquals("诊断面须收入环上双方", setOf(PKG_A, PKG_B), ModManageDiagnostics.snapshot().keys)
    }

    /**
     * 自依赖（核心 `LinkOrderException`，:205 `requireNotSelf`）：唯一可归因 = 声明方自身 ⇒
     * 单条 canonical 行。
     */
    @Test
    fun `自依赖整链拒_归因声明方自身单条canonical行`() {
        val root = tmp.newFolder("game")
        writeManifestWith(root, "a.manifest", ModuleDescriptor(packageId = PKG_A, requires = listOf(PKG_A)))
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val failure = captureReject(root)
        assertTrue("自依赖必须仍抛 LinkOrderException，实际=${failure::class}", failure is LinkOrderException)
        val link = failure as LinkOrderException
        assertEquals("自依赖只归因声明方：${link.packageIds}", listOf(PKG_A), link.packageIds)
        assertTrue("消息不得被改写：${link.message}", link.message!!.contains("指向自身"))
        val line = singleInstallLine()
        assertTrue("canonical 行须点名声明方：$line", line.startsWith("mod.install pkg=$PKG_A "))
    }

    // ==================================================================
    // 链接期裸 RuntimeException 的留痕契约（导入解析 / 重定位）
    // ==================================================================

    /**
     * 外部导入重复（核心 `ImportResolutionException`，ExternalImportTable.resolve :149-155）：
     * externalCalls 同名两次（忽略大小写键）⇒ 导入表现存的活体硬拒形态——S9 改判只放行
     * 「缺提供者」（残桩+告警，见下例），空白 / 重复名 / packageId 重复仍抛（:131 / :142 / :150）。
     * 该异常是裸 `RuntimeException`（非 IAE），装配链 catch 面漏接时整链拒载零留痕
     * 直穿失败屏 —— 玩家重启后设置页失败小节整节不渲染。归因 = 导入方包（e.packageId，
     * 唯一可信归属，catch 见 ModChainAssembly.kt:355-358）⇒ 单条 canonical 行 + 诊断面可读。
     *（旧例钉 `OverrideSignatureException`——2026-11-23 d6da78151（KI-2）把签名校验改判为
     * last-wins 告警放行，SymbolCatalog 不再抛该异常 ⇒ 拒载形态物理消失，本 catch 槽位
     * 换钉此可达硬拒形态；旧例判据的正面对偶已由 ModDx3CharaTrackProbeTest 探针 B1/B2
     * 在 catalog 面钉死，链侧死 catch 已随 3-d 移除。）
     */
    @Test
    fun `导入闸重复外部导入整链拒_归因导入方单条canonical行且诊断面可读`() {
        val root = tmp.newFolder("game")
        writeManifestWith(
            root,
            "a.manifest",
            ModuleDescriptor(packageId = PKG_A, externalCalls = listOf("PROBE_DUP", "PROBE_DUP")),
        )
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        val failure = captureReject(root)
        assertTrue(
            "导入闸必须仍抛 ImportResolutionException（类型不得被留痕改动），实际=${failure::class}",
            failure is ImportResolutionException,
        )
        val line = singleInstallLine()
        assertTrue(
            "canonical 行须点名导入方且异常名=实际类型：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=ImportResolutionException: "),
        )
        assertTrue("行内原因须带导入闸判据：$line", line.contains("重复导入"))
        assertEquals("诊断面须收入导入方包", setOf(PKG_A), ModManageDiagnostics.snapshot().keys)
    }

    /**
     * 外部导入缺提供者（核心 `ExternalImportTable.resolve`，S9 改判 CONTRACT-S9-MISSING-CALL.md L1）：
     * `externalCalls` 点名组合里不存在的函数（缺依赖或名字拼错，Mod 作者常见错）——
     * **不再整链拒载**：链接为缺名残桩（死调用执行到才报错，原版 NotDefinedFunc 语义），
     * diagnostics 告警汇 logSink 留痕点名缺失名。
     */
    @Test
    fun `S9改判_外部导入缺提供者不再整链拒_链接残桩并告警留痕`() {
        val root = tmp.newFolder("game")
        writeManifestWith(
            root,
            "a.manifest",
            ModuleDescriptor(packageId = PKG_A, externalCalls = listOf("NO_SUCH_FN")),
        )
        writeContainer(root, PKG_A, "$PKG_A.erbm")

        ModChainAssembly.assemble(
            ChainAssemblyInputs(root, true, base(), baseMetas, logSink = ::recordLine),
        )
        val warnings = logs.filter { it.contains("NO_SUCH_FN") }
        assertTrue(
            "告警须点名缺失名且带归因：$logs",
            warnings.single().contains(PKG_A) && warnings.single().contains("无法解析"),
        )
        assertTrue(
            "缺提供者不再产生包级拒载行（S9 前是 mod.install active=false）：$logs",
            logs.none { it.startsWith("mod.install pkg=$PKG_A active=false") },
        )
        assertTrue(
            "缺提供者不再进拒载诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    /**
     * CALL SUPER 无前实现（核心 `RelocationException`，CallRelocation.resolve）：加性单函数
     * 体内带 `CALL SUPER` 但无同名前实现。异常只有诊断消息、无结构化包归因字段 ⇒ 按纪律
     * 改走 `mod.reject` 显式 tag，不进诊断面（启动失败屏与 logcat 仍可查）。
     */
    @Test
    fun `CALL_SUPER缺前实现整链拒_无包级归因改走mod_reject`() {
        val root = tmp.newFolder("game")
        writeManifest(root, PKG_A, "a.manifest")
        val b = CodeBuilder()
        val entry = b.declareFunction("ONLY_HERE", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._CALL, CallSentinels.CALL_SUPER_SENTINEL, 0, 0, 0)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        writeContainerOf(
            root, "$PKG_A.erbm",
            CompiledModule(
                PKG_A, b.build(entry),
                listOf(FuncMeta(index = 0, key = "ONLY_HERE")), emptyMap(),
            ),
        )

        val failure = captureReject(root)
        assertTrue(
            "重定位闸必须仍抛 RelocationException（类型不得被留痕改动），实际=${failure::class}",
            failure is RelocationException,
        )
        assertTrue("拒载消息不得被改写：${failure.message}", failure.message!!.contains("CALL_SUPER"))
        assertTrue("无包级归因不得写 canonical 行：$logs", installLines().isEmpty())
        val rejects = logs.filter { it.startsWith("mod.reject ") }
        assertEquals("必须恰一条显式 mod.reject 取证行，实际=$logs", 1, rejects.size)
        val reject = rejects.single()
        assertTrue("mod.reject 须点名磁盘文件名：$reject", reject.startsWith("mod.reject file=a.manifest reason="))
        assertTrue("mod.reject 须带实际异常类型：$reject", reject.contains("exception=RelocationException"))
        assertTrue(
            "mod.reject 按纪律不得进诊断面：${ModManageDiagnostics.snapshot()}",
            ModManageDiagnostics.snapshot().isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 四池容器补丁（第二源）的适配期拒载：decode 已过、adapt 拒载同样要留痕
    // ------------------------------------------------------------------

    /**
     * LINKED_PROGRAM 态四池容器件（probe 命中、decode 可过、`ModuleContainerAdapter.adapt`
     * 拒绝）：适配闸整链拒载必须**先写 canonical 行再抛**，归因取容器自述 packageId。
     * 修复前 adapt 的 IAE 裸穿 `decodePatchContainers`（其 catch 只包 decode）与
     * `assembleChain` 的整链 try（只包 `ModChainAssembler.assemble`），GameHost 又刻意
     * 不外包 assembleWithCatalog（双份留痕）⇒ 零留痕，玩家重启后设置页失败小节整节不渲染
     * （M2-BUG-01/02 同症状类）。
     */
    @Test
    fun `四池容器件LINKED态适配拒_抛前写单条canonical行且诊断面可读`() {
        val root = tmp.newFolder("game")
        val mods = File(root, "mods").apply { mkdirs() }
        File(mods, "linked.patch").writeBytes(linkedContainerBytes(PKG_A))

        val failure = captureReject(root)
        assertTrue(
            "适配闸必须仍抛 IllegalArgumentException（类型不得被留痕改动），实际=${failure::class}",
            failure is IllegalArgumentException,
        )
        assertTrue("拒载消息须点名容器件：${failure.message}", failure.message!!.contains("linked.patch"))
        assertTrue("拒载消息须保留 fail-closed 归因：${failure.message}", failure.message!!.contains("fail-closed"))

        val line = singleInstallLine()
        assertTrue(
            "canonical 行须取容器自述包名归因且异常名=实际类型：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=IllegalArgumentException: "),
        )
        assertTrue("行内原因须点名容器件：$line", line.contains("linked.patch"))
        assertTrue("行内原因须保留 fail-closed：$line", line.contains("fail-closed"))

        val reason = ModManageDiagnostics.snapshot()[PKG_A]
        assertNotNull("诊断面必须收入适配期拒载，实际=${ModManageDiagnostics.snapshot()}", reason)
        assertTrue("摘要须带异常类型：$reason", reason!!.startsWith("IllegalArgumentException: "))
    }

    /**
     * MODULE 态四池容器件但自述与载荷脱节（codeWords 比载荷指令流多一字）：容器 decode
     * 的读侧闸全过（四计数/codeWords 是自述面，decode 不对账载荷实体），脱节在
     * `ModuleContainerAdapter.adapt` 的对账 3 被拒 —— 与 LINKED 态同一条留痕契约。
     */
    @Test
    fun `四池容器件自述脱节适配拒_抛前写单条canonical行且诊断面可读`() {
        val root = tmp.newFolder("game")
        val mods = File(root, "mods").apply { mkdirs() }
        File(mods, "drift.patch").writeBytes(driftedContainerBytes(PKG_A))

        val failure = captureReject(root)
        assertTrue(
            "适配闸必须仍抛 IllegalArgumentException（类型不得被留痕改动），实际=${failure::class}",
            failure is IllegalArgumentException,
        )
        assertTrue("拒载消息须点名容器件：${failure.message}", failure.message!!.contains("drift.patch"))

        val line = singleInstallLine()
        assertTrue(
            "canonical 行须取容器自述包名归因且异常名=实际类型：$line",
            line.startsWith("mod.install pkg=$PKG_A active=false failure=IllegalArgumentException: "),
        )
        assertTrue("行内原因须点名容器件：$line", line.contains("drift.patch"))
        assertEquals("诊断面须收入该包", setOf(PKG_A), ModManageDiagnostics.snapshot().keys)
    }

    // ==================================================================
}
