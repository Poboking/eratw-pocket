package com.eratw.port.android

import com.eratw.port.core.code.CodeBuilder
import com.eratw.port.core.code.Opcode
import com.eratw.port.core.code.Program
import com.eratw.port.core.code.SlotAddr
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.CompiledModule
import com.eratw.port.core.mod.ErbModuleContainer
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModApi
import com.eratw.port.modapi.ModContext
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Lane DXR 会话口径的行为层证据：诊断面的失败行是「本会话事实」，不跨会话继承。
 *
 * 生产接线（源码序由 `ModDiagnosticsSessionScopeWireTest` 钉死）：
 * `GameHost.assembleSession`（GameHost.kt:1174）**顶部**调 `ModManageDiagnostics.reset()`，
 * 早于同函数内的 `ModChainAssembly.assembleWithCatalog`（:1258）与 `ModRuntimeAssembly.attach`
 * （:1362）。本类用生产缝（真 `ModRuntimeAssembly.attach` / 真 `ModChainAssembly.assemble`）
 * 证明三件事：
 * 1. F1 上一会话的失败行不带进本会话 ⇒ 设置页失败小节整节不渲染（消除「修好 mod 后那行还在」）；
 * 2. F2 归零早于真装配 ⇒ 本会话失败包的 canonical 行在 attach 之后仍在诊断面（防「把行抹掉当修好」）；
 * 3. F3 归零早于链装配 ⇒ 装配期整链拒载行（throw 之前写入同一 logSink）不被随后的归零抹掉。
 *
 * 为什么不放在 `ModFailureVisibilityMatrixE2ETest`：该类已顶到 detekt LargeClass 阈值 600 行。
 * 矩阵类继续负责「失败形态 × 可见面」的静态矩阵；本类只管会话边界这一条时间轴。
 *
 * 反向证明（成对存在，缺一即证明不了时机承重）：
 * - 摘掉 GameHost 顶部 reset ⇒ F1/F2 的跨会话断言绿得没有承重（wire test 的源码序哨兵先红）；
 * - 把 reset 挪到 attach 之后 ⇒ F2 红（本会话行已被抹掉）；
 * - 把 reset 挪到链装配之后 ⇒ F3 红（装配期拒载行被抹掉）。
 */
class ModDiagnosticsSessionScopeE2ETest {

    private val logs: MutableList<String> = mutableListOf()
    private val zh = SettingsStrings.of(SettingsLanguage.ZH)

    @Before
    fun setUp() {
        ModManageDiagnostics.reset()
    }

    @After
    fun tearDown() {
        ModManageDiagnostics.reset()
    }

    @Test
    fun `F1 会话开始归零 - 上一会话的失败行不带进本会话`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        writeModuleManifest(root, PKG_B)

        val first = attach(root, listOf(PKG_A to VersionMod(), PKG_B to FailingMod()))
        try {
            assertEquals(
                "夹具前提：上一会话的失败行确实进了进程级诊断面",
                "IllegalStateException: $ON_LOAD_FAILURE",
                ModManageDiagnostics.snapshot()[PKG_B],
            )
        } finally {
            first.shutdown()
        }

        // 会话边界（生产 = GameHost.assembleSession 顶部；GameHost 在本 JVM 单测面实例化不了，
        // 故按生产时序手工过界，源码序由 ModDiagnosticsSessionScopeWireTest 钉）。
        ModManageDiagnostics.reset()

        val second = attach(root, listOf(PKG_A to VersionMod()))
        try {
            assertNull("上一会话的失败行不得跨会话继承", ModManageDiagnostics.snapshot()[PKG_B])
            assertTrue("本会话无失败包 ⇒ 诊断面必须为空", ModManageDiagnostics.snapshot().isEmpty())
            val section = settingsFailureSection(ModManageScanner.scan(root.toFile()), ModManageDiagnostics.snapshot())
            assertTrue("设置页失败小节随之整节不渲染（SettingsActivity.kt:4976-4996 的入参为空）", section.isEmpty())
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `F2 归零早于真装配 - 本会话失败行在 attach 之后仍在诊断面`() {
        val root = tempGameRoot()
        writeModuleManifest(root, PKG_A)
        writeModuleManifest(root, PKG_B)

        val first = attach(root, listOf(PKG_A to FailingMod()))
        try {
            assertEquals(
                "夹具前提：上一会话 A 的失败行在诊断面",
                "IllegalStateException: $ON_LOAD_FAILURE",
                ModManageDiagnostics.snapshot()[PKG_A],
            )
        } finally {
            first.shutdown()
        }

        ModManageDiagnostics.reset() // 会话开始（早于真装配）

        // 本会话 A 成功、B 在 onLoad 抛：归零之后、attach 之内产生的失败行必须留下。
        val second = attach(root, listOf(PKG_A to VersionMod(), PKG_B to FailingMod()))
        try {
            assertNull("上一会话 A 的失败行已被会话归零清掉", ModManageDiagnostics.snapshot()[PKG_A])
            assertEquals(
                "本会话失败行在 attach 之后仍须在诊断面（归零早于真装配，不是把 canonical 行一起抹掉）",
                "IllegalStateException: $ON_LOAD_FAILURE",
                ModManageDiagnostics.snapshot()[PKG_B],
            )
            val section = settingsFailureSection(ModManageScanner.scan(root.toFile()), ModManageDiagnostics.snapshot())
            assertEquals("设置页小节 = 标题 + 本会话唯一一条", 2, section.size)
            assertTrue("小节内容只指本会话失败的 B（实际：$section）", section[1].contains(PKG_B))
        } finally {
            second.shutdown()
        }
    }

    @Test
    fun `F3 归零之后链装配期的拒载行仍在诊断面`() {
        val root = tempGameRoot()
        ModManageDiagnostics.record("mod.install pkg=$PKG_GHOST active=false failure=上一会话残留")
        assertEquals("夹具前提：上一会话残留行在诊断面", setOf(PKG_GHOST), ModManageDiagnostics.snapshot().keys)

        ModManageDiagnostics.reset() // 会话开始（早于链装配）

        // min-api 闸：ModChainAssembly 在读 manifest 时逐包写 canonical 行再 throw LinkOrderException。
        writeChainManifest(root, PKG_A, "mod_a.manifest", minApi = ModApi.VERSION + 1)
        writeContainer(root, PKG_A, "mod_a.erbm")
        captureChainFailure(root)

        assertNull("上一会话残留被归零清掉", ModManageDiagnostics.snapshot()[PKG_GHOST])
        val reason = ModManageDiagnostics.snapshot()[PKG_A]
        assertNotNull("装配期拒载行必须留下（throw 之前写同一 logSink，归零在它之前）", reason)
        assertTrue("原因保持 canonical 行形状 LinkOrderException: …（实际：$reason）", reason!!.startsWith("LinkOrderException: "))
        assertTrue("原因含 min-api 闸原文（实际：$reason）", reason.contains("min-api ${ModApi.VERSION + 1}"))
    }

    // =================================================================
    // 夹具：游戏根 / manifest / 容器 / 真装配（照 ModFailureVisibilityMatrixE2ETest 同源裁剪）
    // =================================================================

    private fun tempGameRoot(): Path {
        val root = Files.createTempDirectory("mod-diag-session-scope")
        Files.createDirectories(root.resolve("mods").resolve("rt2"))
        return root
    }

    private fun rt2(root: Path): Path = root.resolve("mods").resolve("rt2")

    private fun writeModuleManifest(root: Path, packageId: String) {
        val descriptor = ModuleDescriptor(packageId = packageId)
        Files.write(rt2(root).resolve(defaultManifestFileName(packageId)), ManifestCodec.encode(descriptor))
    }

    private fun writeChainManifest(root: Path, packageId: String, fileName: String, minApi: Int) {
        val descriptor = ModuleDescriptor(packageId = packageId, minApi = minApi)
        Files.write(rt2(root).resolve(fileName), ManifestCodec.encode(descriptor))
    }

    private fun defaultManifestFileName(packageId: String): String = "${packageId.replace('.', '_')}.manifest"

    /** 合法 `.erbm` 容器（只加新函数 ANEW，无覆盖声明 ⇒ 免签名真目录夹具）。 */
    private fun writeContainer(root: Path, packageId: String, fileName: String) {
        val b = CodeBuilder()
        val entry = b.declareFunction("ANEW", b.here(), arity = 0, slotCount = 1)
        b.emit(Opcode._RET, SlotAddr.NONE, 0, 0, 0)
        val module = CompiledModule(
            packageId,
            b.build(entry),
            listOf(FuncMeta(index = 0, key = "ANEW")),
            emptyMap(),
        )
        Files.write(rt2(root).resolve(fileName), ErbModuleContainer.encode(module, allowSuperCalls = true))
    }

    /** 真装配入口（生产缝）：`logSink` 与 `GameHost.kt:1242` 同形（`record` + 文本日志）。 */
    private fun attach(root: Path, providers: List<Pair<String, EraMod>>): ModRuntimeSession {
        logs.clear()
        return ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = VariableStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { line ->
                logs += line
                ModManageDiagnostics.record(line)
            },
            modProviders = providers,
        ) ?: error("enabled=true 时真装配入口不应返回 null")
    }

    /** 真链装配入口（生产缝）：期望整链拒载时返回捕获到的异常。 */
    private fun captureChainFailure(root: Path): Throwable {
        try {
            ModChainAssembly.assemble(
                ChainAssemblyInputs(
                    root.toFile(), true, base(), baseMetas,
                    logSink = { line ->
                        logs += line
                        ModManageDiagnostics.record(line)
                    },
                ),
            )
            fail("夹具必须被整链拒载（否则测不到装配期留痕形态）")
        } catch (expected: LinkOrderException) {
            return expected
        } catch (expected: IllegalArgumentException) {
            return expected
        }
        error("unreachable")
    }

    /** 整链装配夹具：base = ENTRY@0 / TARGET@1，各一条 `_RET`。 */
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

    private fun settingsFailureSection(scan: ModManageScan, failures: Map<String, String>): List<String> {
        if (failures.isEmpty()) return emptyList()
        val titles = scan.mods.associate { it.packageId to it.title }
        return listOf(zh.modManageFailureHeading) +
            failures.map { (packageId, reason) -> zh.modManageFailureLine(titles[packageId] ?: packageId, reason) }
    }

    private class VersionMod(
        override val apiVersion: Int = ModApi.VERSION,
    ) : EraMod {
        override fun onLoad(context: ModContext) = Unit
        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private class FailingMod : EraMod {
        override fun onLoad(context: ModContext) {
            error(ON_LOAD_FAILURE)
        }

        override fun onEnable() = Unit
        override fun onDisable() = Unit
        override fun onUnload() = Unit
    }

    private companion object {
        private const val PKG_A: String = "mod.scope.a"
        private const val PKG_B: String = "mod.scope.b"
        private const val PKG_GHOST: String = "mod.scope.ghost"
        private const val ON_LOAD_FAILURE: String = "onLoad 故意失败"
    }
}
