package com.eratw.port.android

import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDependencyCycleException
import com.eratw.port.core.mod.ModDependencyMissingException
import com.eratw.port.core.mod.ModDependencyUnavailableException
import com.eratw.port.core.patch.ManifestCodec
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modapi.EraMod
import com.eratw.port.modapi.ModContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EraMod.requires`（mod 间依赖）在**生产装配链**上的端到端证据。
 *
 * 与 core-vm 单测的分工：那里验 `planModInstall` 的纯函数语义；这里从 app 真入口
 * [ModRuntimeAssembly.attach] 起装配（真 [VariableStore] / 真 [NativeMethodRegistry] / 真
 * `mods/rt2` 声明清单 / 真 `mod.install pkg=… active=… failure=…` 上屏行），钉住「规划结果真的
 * 进了装载循环、真的决定生命周期回调顺序与拒载」，而不是只存在于规划函数的返回值里。
 *
 * 断言的语义锚（core-vm 主源码，本 worktree 行号）：
 * - 依赖序 = 稳定拓扑序：`core-vm/.../core/mod/ModRuntimeHost.kt:284` 先算规划、
 *   `:285` 按 `plan.ordered` 进循环；无依赖时 `planModInstall` 的 Kahn 队列按声明下标排序，
 *   因此装载序与载体声明序逐位一致（`core-vm/.../core/mod/ModDependencyPlan.kt:125`）。
 * - 缺依赖 / 成环 / 传递拒在规划期拒载：`ModDependencyPlan.kt:113-118`（缺依赖）、
 *   `:120`（成环）、`:122`（依赖被拒的传递闭包）；拒载包不建 context、不进生命周期，
 *   以 `active=false` + 原因进报告，经 `ModRuntimeAssembly.kt:470-473` 上屏。
 * - 运行时复查：依赖包存在但没成功启用（版本门 / onLoad / onEnable 抛错）时，
 *   `ModRuntimeHost.kt:295` 在依赖者装载前抛 [ModDependencyUnavailableException]，并带上依赖包的
 *   根因（`ModDependencyPlan.kt:72-82`）。
 * - 被规划期拒载的包仍进报告（`ModRuntimeHost.kt:361`），因此 `ModManageDiagnostics` 能看到它。
 *
 * 停用 marker（`.off`）口径：生产链 `ModCarrierRegistry.providers` 会先按
 * `ModEnablement.disabledPackageIds` 过滤，所以「依赖被停用」在生产链上的真实现象是
 * **依赖者看不到该包 ⇒ 按缺依赖拒载**（本文件第 7 例）。宿主另有一层 fail-closed：即使测试绕过
 * 生产过滤、把已停用包硬塞进 providers，该包 manifest 不在声明快照里，构造段会直接拒
 * （`ModContextImpl.kt` 的「声明快照不含 packageId」require），依赖者按「依赖未成功启用」拒载
 * （本文件第 8 例）。
 */
class ModDependencyAssemblyEndToEndTest {

    @Test
    fun `依赖序_B依赖A_真装配先装A再装B`() {
        val root = gameRoot(PKG_A, PKG_B)
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events)
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_A))
        val session = attach(root, listOf(PKG_B to b, PKG_A to a))
        try {
            assertEquals(
                "B requires A 时真装配必须让 A 走完 onLoad/onEnable 再轮到 B",
                listOf("load:$PKG_A", "enable:$PKG_A", "load:$PKG_B", "enable:$PKG_B"),
                events,
            )
            val lines = installLines()
            assertEquals("两个包都应上报一条 mod.install 行：$lines", 2, lines.size)
            assertActive(PKG_A)
            assertActive(PKG_B)
            assertEquals(
                "installedContexts 顺序应与装载顺序一致",
                listOf(PKG_A, PKG_B),
                session.host.installedContexts.map { it.packageId },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `无依赖多包_装载序与载体声明序逐位一致`() {
        val p1 = "mod.dep.p1"
        val p2 = "mod.dep.p2"
        val p3 = "mod.dep.p3"
        val root = gameRoot(p1, p2, p3)
        val events = ArrayList<String>()
        val mods = listOf(
            p3 to RecordingMod(p3, events),
            p1 to RecordingMod(p1, events),
            p2 to RecordingMod(p2, events),
        )
        val session = attach(root, mods)
        try {
            assertEquals(
                "无依赖声明的 mod 之间装载序必须与声明序逐位一致",
                listOf("load:$p3", "enable:$p3", "load:$p1", "enable:$p1", "load:$p2", "enable:$p2"),
                events,
            )
            assertEquals(
                listOf(p3, p1, p2),
                installLines().map { line -> line.substringAfter("pkg=").substringBefore(' ') },
            )
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `缺依赖_依赖包不在本批_依赖者拒载上屏且生命周期零调用`() {
        val root = gameRoot(PKG_A, PKG_B)
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events)
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_GHOST))
        val session = attach(root, listOf(PKG_B to b, PKG_A to a))
        try {
            assertRejected(PKG_B, ModDependencyMissingException::class.java.simpleName)
            assertTrue(
                "缺依赖原因应可读地上屏：${installLineOf(PKG_B)}",
                installLineOf(PKG_B).contains("缺少依赖 $PKG_GHOST"),
            )
            assertEquals("拒载包不得走 onLoad", 0, b.loads)
            assertEquals("拒载包不得走 onEnable", 0, b.enables)
            assertNull("拒载包不得建 context", session.host.contextOf(PKG_B))
            assertFalse("拒载包不得激活 storage", session.storage.isActive(PKG_B))
            assertEquals(listOf(PKG_A), session.host.installedContexts.map { it.packageId })
            assertActive(PKG_A)
            assertEquals("无关包照常走生命周期", 1, a.loads)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `传递拒_C依赖缺依赖的B_两者都拒且独立包照常`() {
        val root = gameRoot(PKG_B, PKG_C, PKG_D)
        val events = ArrayList<String>()
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_GHOST))
        val c = RecordingMod(PKG_C, events, requires = listOf(PKG_B))
        val d = RecordingMod(PKG_D, events)
        val session = attach(root, listOf(PKG_C to c, PKG_B to b, PKG_D to d))
        try {
            assertRejected(PKG_B, ModDependencyMissingException::class.java.simpleName)
            assertRejected(PKG_C, ModDependencyUnavailableException::class.java.simpleName)
            assertTrue(
                "传递拒应带上一跳的根因：${installLineOf(PKG_C)}",
                installLineOf(PKG_C).contains("根因 ModDependencyMissingException"),
            )
            assertEquals("依赖被拒的 C 也不得走生命周期", 0, c.loads)
            assertEquals("链上被拒的 B 不得走生命周期", 0, b.loads)
            assertActive(PKG_D)
            assertEquals("独立包照常装载", 1, d.loads)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `成环_A与B互相依赖_全部拒载并点名环成员`() {
        val root = gameRoot(PKG_A, PKG_B, PKG_C)
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events, requires = listOf(PKG_B))
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_A))
        val c = RecordingMod(PKG_C, events)
        val session = attach(root, listOf(PKG_A to a, PKG_B to b, PKG_C to c))
        try {
            val cycleType = ModDependencyCycleException::class.java.simpleName
            assertRejected(PKG_A, cycleType)
            assertRejected(PKG_B, cycleType)
            val expectedCycle = "$PKG_A -> $PKG_B -> $PKG_A"
            assertTrue(
                "环成员必须被点名（A 的拒载行）：${installLineOf(PKG_A)}",
                installLineOf(PKG_A).contains(expectedCycle),
            )
            assertTrue(
                "环成员必须被点名（B 的拒载行）：${installLineOf(PKG_B)}",
                installLineOf(PKG_B).contains(expectedCycle),
            )
            assertEquals("环上 mod 不得走 onLoad", 0, a.loads + b.loads)
            assertActive(PKG_C)
            assertEquals("环外 mod 照常装载", 1, c.loads)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `自环_A依赖自己_拒载`() {
        val root = gameRoot(PKG_A)
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events, requires = listOf(PKG_A))
        val session = attach(root, listOf(PKG_A to a))
        try {
            assertRejected(PKG_A, ModDependencyCycleException::class.java.simpleName)
            assertTrue(
                "自环应被点名成 A -> A：${installLineOf(PKG_A)}",
                installLineOf(PKG_A).contains("$PKG_A -> $PKG_A"),
            )
            assertEquals("自环 mod 不得走生命周期", 0, a.loads)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `依赖被off停用_生产过滤后依赖者按缺依赖拒载`() {
        val root = gameRoot(PKG_A, PKG_B)
        assertTrue("停用标记必须写成功", ModEnablement.setEnabled(rt2Dir(root), PKG_A, false))
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events)
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_A))
        // 生产同源过滤：ModCarrierRegistry.providers 只交出未被 .off 停用的载体；
        // 测试无法注入静态 ModRegistry，故在 providers 这一层复刻同一过滤。
        val providers = listOf(PKG_B to b, PKG_A to a)
            .filterNot { it.first in ModEnablement.disabledPackageIds(rt2Dir(root)) }
        val session = attach(root, providers)
        try {
            assertEquals("生产过滤后 providers 不应再含被停用包", listOf(PKG_B), providers.map { it.first })
            assertRejected(PKG_B, ModDependencyMissingException::class.java.simpleName)
            assertTrue(
                "被停用包不在批 ⇒ 依赖者按缺依赖拒载：${installLineOf(PKG_B)}",
                installLineOf(PKG_B).contains("缺少依赖 $PKG_A"),
            )
            assertTrue("被停用包不得出现在 mod.install 行", installLines().none { it.contains("pkg=$PKG_A ") })
            assertEquals("被停用包不得创建实例生命周期", 0, a.loads + a.enables)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `依赖被off停用_泄漏provider时宿主仍fail_closed`() {
        val root = gameRoot(PKG_A, PKG_B)
        assertTrue("停用标记必须写成功", ModEnablement.setEnabled(rt2Dir(root), PKG_A, false))
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events)
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_A))
        // 故意绕过生产过滤，把已停用包硬塞进 providers：验证宿主自身的 fail-closed 围栏。
        val session = attach(root, listOf(PKG_A to a, PKG_B to b))
        try {
            assertRejected(PKG_A, IllegalArgumentException::class.java.simpleName)
            assertTrue(
                "被停用包 manifest 不进声明快照 ⇒ 构造段拒载：${installLineOf(PKG_A)}",
                installLineOf(PKG_A).contains("声明快照不含 packageId=$PKG_A"),
            )
            assertRejected(PKG_B, ModDependencyUnavailableException::class.java.simpleName)
            assertTrue(
                "依赖者应按「未成功启用」拒载：${installLineOf(PKG_B)}",
                installLineOf(PKG_B).contains("未成功启用"),
            )
            assertEquals("两者生命周期都必须零调用", 0, a.loads + b.loads)
            assertFalse("被停用包不得激活 storage", session.storage.isActive(PKG_A))
            assertNull("被停用包不得建 context", session.host.contextOf(PKG_A))
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `依赖onLoad失败_运行时复查让依赖者拒载并带根因`() {
        val root = gameRoot(PKG_A, PKG_B)
        val events = ArrayList<String>()
        val a = RecordingMod(PKG_A, events, failOnLoad = true)
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_A))
        val session = attach(root, listOf(PKG_B to b, PKG_A to a))
        try {
            assertRejected(PKG_A, IllegalStateException::class.java.simpleName)
            assertRejected(PKG_B, ModDependencyUnavailableException::class.java.simpleName)
            assertTrue(
                "依赖者拒载行应带依赖包的根因：${installLineOf(PKG_B)}",
                installLineOf(PKG_B).contains("根因 IllegalStateException"),
            )
            assertEquals("A 的 onLoad 确实被调用过一次", 1, a.loads)
            assertEquals("依赖未成功启用时 B 不得走生命周期", 0, b.loads)
        } finally {
            session.shutdown()
        }
    }

    @Test
    fun `被拒包进入ModManageDiagnostics快照`() {
        val root = gameRoot(PKG_A, PKG_B)
        val events = ArrayList<String>()
        val b = RecordingMod(PKG_B, events, requires = listOf(PKG_GHOST))
        val session = attach(root, listOf(PKG_B to b, PKG_A to RecordingMod(PKG_A, events)))
        try {
            ModManageDiagnostics.reset()
            logs.forEach { ModManageDiagnostics.record(it) }
            val snapshot = ModManageDiagnostics.snapshot()
            assertTrue("被拒包应进诊断快照：$snapshot", snapshot.containsKey(PKG_B))
            assertTrue(
                "诊断摘要应可读地带上拒载类型：${snapshot[PKG_B]}",
                snapshot.getValue(PKG_B).contains("ModDependencyMissingException"),
            )
            assertFalse("成功装载的无关包不应被记成失败：$snapshot", snapshot.containsKey(PKG_A))
        } finally {
            session.shutdown()
        }
    }

    private val logs = ArrayList<String>()

    private fun gameRoot(vararg packageIds: String): Path {
        val root = Files.createTempDirectory("mod-dep-e2e")
        Files.createDirectories(rt2Dir(root).toPath())
        for (packageId in packageIds) writeDeclaration(root, packageId)
        return root
    }

    private fun rt2Dir(root: Path): File = ModEnablement.rt2Dir(root.toFile())

    private fun writeDeclaration(root: Path, packageId: String) {
        val descriptor = ModuleDescriptor(packageId = packageId)
        Files.write(rt2Dir(root).toPath().resolve("$packageId.manifest"), ManifestCodec.encode(descriptor))
    }

    private fun attach(root: Path, providers: List<Pair<String, EraMod>>): ModRuntimeSession {
        logs.clear()
        val session = ModRuntimeAssembly.attach(
            gameRoot = root.toFile(),
            store = VariableStore(),
            nativeRegistry = NativeMethodRegistry(),
            onVmThread = { true },
            enabled = true,
            logSink = { logs += it },
            modProviders = providers,
        )
        return checkNotNull(session) { "enabled=true 时真装配入口不应返回 null" }
    }

    private fun installLines(): List<String> = logs.filter { it.startsWith("mod.install pkg=") }

    private fun installLineOf(packageId: String): String =
        checkNotNull(installLines().firstOrNull { it.startsWith("mod.install pkg=$packageId ") }) {
            "缺少 $packageId 的 mod.install 上报行；logs=$logs"
        }

    private fun assertActive(packageId: String) {
        val line = installLineOf(packageId)
        assertTrue("$packageId 应装载成功，实际=$line", line.endsWith(" active=true"))
    }

    private fun assertRejected(packageId: String, failureType: String) {
        val line = installLineOf(packageId)
        assertTrue(
            "$packageId 应按 $failureType 拒载，实际=$line",
            line.startsWith("mod.install pkg=$packageId active=false failure=$failureType:"),
        )
    }

    private class RecordingMod(
        private val label: String,
        private val events: MutableList<String>,
        override val requires: List<String> = emptyList(),
        private val failOnLoad: Boolean = false,
    ) : EraMod {
        var loads = 0
            private set
        var enables = 0
            private set

        override fun onLoad(context: ModContext) {
            loads += 1
            events += "load:$label"
            if (failOnLoad) error("$label onLoad 故意失败")
        }

        override fun onEnable() {
            enables += 1
            events += "enable:$label"
        }

        override fun onDisable() = Unit

        override fun onUnload() = Unit
    }

    private companion object {
        private const val PKG_A: String = "mod.dep.a"
        private const val PKG_B: String = "mod.dep.b"
        private const val PKG_C: String = "mod.dep.c"
        private const val PKG_D: String = "mod.dep.d"
        private const val PKG_GHOST: String = "mod.dep.ghost"
    }
}
