package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1-4 阶段二的**装配单源哨兵**（CONTRACT-RT2-M14B-IMPL §1 裁定 3、§3 判据 4）。
 *
 * 这层只能做源码哨兵：装配链在 Android 运行时上（`GameHost` / `ModRuntimeAssembly` 都碰
 * `android.*`），JVM 单测起不来。哨兵钉三件事：
 * 1. `ModCsvPlanLoader` 里 `ManifestLoader.load(` 恰好**一次**（整趟唯一装载点）；
 * 2. `ModRuntimeAssembly.attach` 消费 `ModCsvLoadContext`（CSV 三端口不再恒空表），
 *    且 `SidecarSaveWiring` 的计划/关键字查询面来自该上下文；
 * 3. `GameSession` 扩容与 `GameHost` 装配都把同一个上下文传下去（不派生第二份）。
 *
 * 行为面（真能存读注入值）由 core 的 `ModCsvInjectionLoadTest` / `ModCsvValueSaveTest` 钉。
 */
class ModCsvPlanWireTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $name（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun coreSourceOf(relative: String): String {
        val candidates = listOf(
            // 公开分仓：core-vm 在同级引擎仓 erak-engine（原单体布局作兼容回落）。
            File("../../erak-engine/core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
            File("core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `装载相位唯一装载点 - ModCsvPlanLoader 只调用一次 ManifestLoader_load`() {
        val source = coreSourceOf("vars/ModCsvLoadContext.kt")
        val count = Regex("ManifestLoader\\.load\\(").findAll(source).count()
        assertEquals("整趟装载只允许一次 ManifestLoader.load（裁定 3），实得 $count", 1, count)
    }

    @Test
    fun `attach 消费上下文 - 声明快照与侧车计划都来自装配上下文`() {
        val source = sourceOf("ModRuntimeAssembly.kt")
        assertTrue(
            "attach 必须现读装载相位上下文（提取到 declarationSnapshotFor 里同样算）",
            source.contains("ModCsvLoadContext.of(store)"),
        )
        assertTrue(
            "声明快照必须来自上下文（不再无条件自装载）",
            source.contains("context?.snapshot ?: ManifestLoader.load("),
        )
        assertTrue(
            "仅上下文缺席时回落自装载，且此时 CSV 容量恒空 = 显式 fail-closed",
            source.contains("csvTableCapacities = emptyMap()"),
        )
        assertTrue(
            "侧车候选计划来自同一上下文",
            source.contains("csvPlan = csvPlan"),
        )
        assertTrue(
            "读档重映射的关键字查询面来自当前注册表",
            source.contains("store.registry.csvTable(table).keywordIndex(keyword)"),
        )
        assertTrue(
            "装载相位的排除/告警必须进宿主日志面（不静默丢声明）",
            source.contains("context?.warnings?.forEach(logSink)"),
        )
    }

    @Test
    fun `会话与装配传同一上下文 - 不派生第二份`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "GameHost 必须只产出一份上下文（装载相位）",
            host.contains("ModRuntimeAssembly.csvLoadContext(gameRoot)"),
        )
        assertTrue("上下文必须传给 Mod 链装配", host.contains("csvContext = csvContext"))
        assertTrue("上下文必须传给会话（扩容）", host.contains("modCsvContext = csvContext"))

        val session = coreSourceOf("proc/GameSession.kt")
        assertTrue("会话构造必须接受注入上下文", session.contains("modCsvContext: ModCsvLoadContext? = null"))
        assertTrue(
            "非空计划必须在建 registry 之前重载一次（扩容前移）",
            session.contains("GameDataLoader.load(csvDir, csvContext.plan)"),
        )
        assertTrue("上下文必须挂进 store.aux", session.contains("ModCsvLoadContext.install(store, csvContext)"))

        val chain = sourceOf("ModChainAssembly.kt")
        assertTrue("链装配必须消费同一份快照", chain.contains("snapshot = csvContext?.snapshot"))
        assertTrue("链装配必须吃同一份容量", chain.contains("csvTableCapacities = csvContext?.csvTableCapacities"))
    }

    @Test
    fun `缺省关态零接触 - 总闸关不产上下文`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "总闸关时必须传 null（不扫盘、不装载 CSV 上下文）",
            host.contains("ModRuntimeAssembly.csvLoadContext(gameRoot)") &&
                Regex("if \\(modRuntime\\) \\{\\s*\\n\\s*// M1-4 阶段二").containsMatchIn(host),
        )
        assertFalse(
            "不得在关态路径上无条件调用 csvLoadContext",
            host.contains("\n        val csvContext = ModRuntimeAssembly.csvLoadContext(gameRoot)"),
        )
        assertFalse("总闸缺省值不许翻", UiPrefs.DEFAULT_MOD_RUNTIME)
    }
}
