package com.eratw.port.android

import com.eratw.port.core.entity.ComponentFieldSpec
import com.eratw.port.core.entity.ComponentFieldType
import com.eratw.port.core.entity.ComponentSchema
import com.eratw.port.core.entity.EntityArchetype
import com.eratw.port.core.entity.EntityKernel
import com.eratw.port.core.entity.RelationshipSchema
import com.eratw.port.core.fn.NativeMethodRegistry
import com.eratw.port.core.mod.ModDeclarationAdapter
import com.eratw.port.core.mod.ModRuntimeHost
import com.eratw.port.core.mod.ModTimingLedger
import com.eratw.port.core.patch.DeclarationRegistry
import com.eratw.port.core.patch.DeclarationSnapshot
import com.eratw.port.modapi.ModManifest
import com.eratw.port.modapi.RuntimeScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报告层预算告警判据（ModDX-3 A）：`reportModTiming` 的 `mod.timing.budget [WARN]` 独立行。
 *
 * 钉住四件事：
 * 1. 既有 `mod.timing` 行**逐字不变**（整行精确断言，含字段顺序与微秒换算）；
 * 2. 超预算行独立可筛：`mod.timing.budget` 前缀 + `[WARN]` 标记 + pkg/累计耗时/预算值；
 * 3. budget 为 null / 0 / 负数时不打预算行，既有行不变；
 * 4. 多包隔离 + 关态零输出。
 *
 * 反向证明：摘掉预算 WARN 的 logSink 路径 ⇒ [既有行逐字不变且超预算行独立可筛] 红。
 */
class TimingBudgetReportTest {

    private val logs = mutableListOf<String>()

    @Test
    fun `既有行逐字不变且超预算行独立可筛`() {
        val host = newHost()
        val ledger = checkNotNull(host.timing)
        ledger.record("pkgA", "tick", 40_000)
        ledger.record("pkgA", "hook.F.PREFIX", 30_000)
        ledger.record("pkgB", "tick", 5_000)

        reportModTiming(host, listOf("pkgA", "pkgB"), sink(), budgetNanos = 69_000L)

        // 既有 mod.timing 行逐字不变（含顺序、字段与微秒换算）。
        assertEquals(
            listOf(
                "mod.timing pkg=pkgA scope=tick count=1 totalUs=40 maxUs=40 lastUs=40",
                "mod.timing pkg=pkgA scope=hook.F.PREFIX count=1 totalUs=30 maxUs=30 lastUs=30",
                "mod.timing pkg=pkgB scope=tick count=1 totalUs=5 maxUs=5 lastUs=5",
            ),
            logs.filter { it.startsWith("mod.timing pkg=") },
        )

        // 超预算行独立可筛：前缀不同，既有行筛选器捞不到它。
        val budgetLines = logs.filter { it.startsWith("mod.timing.budget ") }
        assertEquals("只有 pkgA 超预算：$logs", 1, budgetLines.size)
        assertEquals(
            "mod.timing.budget [WARN] pkg=pkgA totalUs=70 budgetUs=69 累计耗时超预算",
            budgetLines.single(),
        )
        assertTrue("预算行必须带 WARN 标记", budgetLines.single().contains("[WARN]"))
        assertFalse(
            "既有行不许掺预算字段",
            logs.filter { it.startsWith("mod.timing pkg=") }.any { it.contains("budgetUs") },
        )
    }

    @Test
    fun `budget为空或非正时不打预算行且既有行不变`() {
        for (budget in listOf(null, 0L, -1L)) {
            val host = newHost()
            val ledger = checkNotNull(host.timing)
            ledger.record("pkgA", "tick", 40_000)
            ledger.record("pkgB", "tick", 5_000)
            logs.clear()

            reportModTiming(host, listOf("pkgA", "pkgB"), sink(), budgetNanos = budget)

            assertTrue("预算=$budget 不该告警：$logs", logs.none { it.startsWith("mod.timing.budget ") })
            assertEquals(
                "预算=$budget 时既有行逐字不变",
                listOf(
                    "mod.timing pkg=pkgA scope=tick count=1 totalUs=40 maxUs=40 lastUs=40",
                    "mod.timing pkg=pkgB scope=tick count=1 totalUs=5 maxUs=5 lastUs=5",
                ),
                logs.filter { it.startsWith("mod.timing pkg=") },
            )
        }
    }

    @Test
    fun `未超预算的包不打预算行多包隔离`() {
        val host = newHost()
        val ledger = checkNotNull(host.timing)
        ledger.record("pkgHeavy", "tick", 100_000)
        ledger.record("pkgLight", "tick", 2_000)

        reportModTiming(host, listOf("pkgHeavy", "pkgLight"), sink(), budgetNanos = 10_000L)

        val budgetLines = logs.filter { it.startsWith("mod.timing.budget ") }
        assertEquals("$logs", 1, budgetLines.size)
        assertTrue("$budgetLines", budgetLines.single().contains("pkg=pkgHeavy "))
        assertFalse("轻包不得被打预算行", budgetLines.single().contains("pkg=pkgLight "))

        logs.clear()
        reportModTiming(host, listOf("pkgHeavy", "pkgLight"), sink(), budgetNanos = 100_000L)
        assertTrue("等于预算不算超预算", logs.none { it.startsWith("mod.timing.budget ") })
    }

    @Test
    fun `关态无账本时零输出`() {
        val host = newHost()
        host.timing = null
        reportModTiming(host, listOf("pkgA"), sink(), budgetNanos = 1L)
        assertTrue("无账本时既有行与预算行都不该出现：$logs", logs.isEmpty())
    }

    // ---------------------------------------------------------------- 夹具

    private fun sink(): (String) -> Unit = { logs += it }

    private fun newHost(): ModRuntimeHost = ModRuntimeHost(
        snapshot = newSnapshot("pkgA", "pkgB", "pkgHeavy", "pkgLight"),
        entityKernel = newKernel(),
        nativeMethods = NativeMethodRegistry(),
        logSink = sink(),
        runtime = FakeRuntime(),
    ).also { it.timing = ModTimingLedger() }

    private fun newKernel(): EntityKernel = EntityKernel(
        schemas = listOf(
            ComponentSchema(
                packageId = "demo",
                name = "Stats",
                fields = listOf(ComponentFieldSpec("hp", ComponentFieldType.INT)),
            ),
        ),
        relationshipSchemas = listOf(RelationshipSchema(name = "PARENT")),
        archetypes = listOf(EntityArchetype("Child", listOf("Stats"))),
    )

    private fun newSnapshot(vararg packages: String): DeclarationSnapshot {
        val registry = DeclarationRegistry()
        for (pkg in packages) {
            registry.addPackage(ModDeclarationAdapter.toDescriptor(ModManifest(packageId = pkg)))
        }
        return registry.freeze()
    }

    private class FakeRuntime : RuntimeScheduler {
        override val isOnMainThread: Boolean get() = true

        override fun submit(action: () -> Unit) = action()
    }
}
