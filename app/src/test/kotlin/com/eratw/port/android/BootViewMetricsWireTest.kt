package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `emuera.config` 视图度量**单读**的装配哨兵（`CONTRACT-RT2-CFG16` / SPEC-RULING 序 16）。
 *
 * 这层只能做源码哨兵：`GameHost` 碰 Android View，JVM 起不来。行为面（快照优先 / 缺省回落）
 * 由 core 的 `GameSessionTest`「装载期视图度量快照」钉死；这里钉的是**装配唯一性**：
 * 1. `GameHost` 里字号/行高只读一次（`EffectiveConfig.of` 单点），返回值即快照；
 * 2. 同一份快照既喂 View（`applySourceFontMetrics`），又经 `loadAndRun` → `assembleSession`
 *    → `GameSession(bootViewMetrics = ...)` 传进会话；
 * 3. 会话侧取快照优先、缺省回落 `EffectiveConfig`（不再有第二条读盘路径）。
 */
class BootViewMetricsWireTest {

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    private fun coreSourceOf(relative: String): String {
        val candidates = listOf(
            // 公开分仓：core-vm 在同级引擎仓 Kotera（原单体布局作兼容回落）。
            File("../../Kotera/core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
            File("../core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
            File("core-vm/src/main/kotlin/com/eratw/port/core/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试过 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `单读点 - GameHost 只读一次生效配置且返回值就是快照`() {
        val host = sourceOf("GameHost.kt")
        assertEquals(
            "GameHost 里 EffectiveConfig.of 必须只有一处（装载期单读）",
            1,
            Regex("EffectiveConfig\\.of\\(").findAll(host).count(),
        )
        assertTrue(
            "读取点必须返回 BootViewMetrics（同一份既喂 View 又喂会话）",
            host.contains("private fun applyBootFontMetrics(gameRoot: File): BootViewMetrics?") &&
                host.contains("return BootViewMetrics(effective.fontSize, effective.lineHeight)"),
        )
        assertEquals(
            "applyBootFontMetrics 只允许「定义 + 一次调用」",
            2,
            Regex("applyBootFontMetrics\\(").findAll(host).count(),
        )
        assertTrue("读取点必须在 start() 里只调一次", host.contains("val bootMetrics = applyBootFontMetrics(gameRoot)"))
    }

    @Test
    fun `透传链 - 快照经 loadAndRun 与 assembleSession 进会话`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "loadAndRun 必须收快照",
            host.contains("bootMetrics: BootViewMetrics?,"),
        )
        assertTrue(
            "start() 必须把同一份快照传进 loadAndRun",
            host.contains("loadAndRun(gameRoot, screen, metrics, textMeasure, bootMetrics)"),
        )
        assertTrue(
            "assembleSession 必须收快照",
            host.contains("bootMetrics = bootMetrics,"),
        )
        assertTrue(
            "会话构造必须吃快照",
            host.contains("bootViewMetrics = bootMetrics,"),
        )
    }

    @Test
    fun `会话侧优先级 - 快照在位取快照、缺省回落生效配置`() {
        val session = coreSourceOf("proc/GameSession.kt")
        assertTrue(
            "字号：快照优先、缺省回落生效配置",
            session.contains("this.scriptFontPixelSize = bootViewMetrics?.fontSize ?: effective.fontSize"),
        )
        assertTrue(
            "行高：快照优先、缺省回落生效配置",
            session.contains("lineHeightPx = bootViewMetrics?.lineHeight ?: effective.lineHeight"),
        )
        assertTrue(
            "构造函数必须有缺省 null 的快照参数（headless/装置零变化）",
            session.contains("bootViewMetrics: BootViewMetrics? = null,"),
        )
    }

    // ------------------------------------------------------------------
    // CONTRACT-RT2-CFG16B：序 16 剩余半边（会话侧彻底不自读）
    // ------------------------------------------------------------------

    @Test
    fun `快照形状 - 单读点同时交出链后 store 与原始条目表`() {
        val host = sourceOf("GameHost.kt")
        assertTrue(
            "度量两项仍是同一个两参快照（A/L7 形状保留）",
            host.contains("return BootViewMetrics(effective.fontSize, effective.lineHeight)"),
        )
        assertTrue(
            "同一次读盘必须把链后 store 与原始条目表装进快照",
            host.contains(".copy(configStore = store, rawEntries = host.configOverrides)"),
        )
        assertEquals(
            "读取点只允许构造一个 RootedBuiltinHost（一次读盘）",
            1,
            Regex("RootedBuiltinHost\\(gameRoot\\.toPath\\(\\)\\)").findAll(host).count(),
        )
    }

    @Test
    fun `WindowX 回填单点 - GameHost 用 View 同尺实宽回填快照 store`() {
        val host = sourceOf("GameHost.kt")
        assertEquals(
            "WindowX 回填生产点只允许一处（会话侧禁止二次回填）",
            1,
            Regex("ConfigMethods\\.backfillWindowWidth\\(").findAll(host).count(),
        )
        assertTrue(
            "回填对象 = 快照的链后 store，宽度 = 交给会话的同一份 View 实测脚本 px",
            host.contains("ConfigMethods.backfillWindowWidth(it, metrics.windowWidthScriptPx)"),
        )
        assertTrue(
            "会话侧 windowWidthScriptPx 必须与回填用的 View 实宽同一读数（同源）",
            host.contains("windowWidthScriptPx = metrics.windowWidthScriptPx,"),
        )
        val session = coreSourceOf("proc/GameSession.kt")
        assertTrue(
            "会话只在「既无外部 store 也无快照 store」的缺省回落分支里自行回填",
            session.contains("if (configStore == null && bootConfig == null) {"),
        )
    }

    @Test
    fun `会话零自读 - 快照在位时不取 rooted 原始表也不自建链`() {
        val session = coreSourceOf("proc/GameSession.kt")
        assertTrue(
            "快照分支：原始条目表取自快照（rooted 读盘退居 `?:` 缺省回落）",
            session.contains("val config = bootConfig?.rawEntries ?: rooted.configOverrides"),
        )
        assertTrue(
            "快照分支：链后 store 取自快照（ConfigStore 自建链退居 `?:` 缺省回落）",
            session.contains(
                "configStore ?: bootConfig?.configStore ?: ConfigStore().also { rooted.applyChainTo(it) }",
            ),
        )
        assertEquals(
            "rooted.configOverrides 只允许缺省回落一处",
            1,
            Regex("rooted\\.configOverrides").findAll(session).count(),
        )
        assertEquals(
            "ConfigStore().also 自建链只允许缺省回落一处",
            1,
            Regex("ConfigStore\\(\\)\\.also \\{ rooted\\.applyChainTo\\(").findAll(session).count(),
        )
    }
}
