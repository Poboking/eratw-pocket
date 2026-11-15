package com.eratw.port.android

import com.eratw.port.core.patch.LinkOrder
import com.eratw.port.core.patch.LinkOrderException
import com.eratw.port.core.patch.ModuleDescriptor
import com.eratw.port.core.patch.ModuleLinkOrder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RT2 玩家排序配置（L2）的接线钉：源码哨兵 + 行为对拍。
 *
 * 行为面只借壳层纯函数 [UiPrefs.parsePreferredPackageIds] → [LinkOrder] →
 * [ModuleLinkOrder.plan] 复现核心冻结的「Kahn 就绪模块优先级」反例，核心排序语义一行不改
 * （语义权威 = `CONTRACT-RT2-M2-3.md` §3.1 与 `LinkOrder` KDoc）。
 *
 * 源码面钉四件事：公开读点与纯转换函数在、总闸缺省仍关（关态零变化）、
 * `ModChainAssembly.assemble` 的 linkOrder 缺省仍是 `LinkOrder()`（核心零缺口），
 * 设置页输入卡用三语文案且无内联玩家可见字面量。
 *
 * `GameHost` 构造期接线由组长独占（worker 不碰 GameHost）：本文件**不**断言 GameHost 已接，
 * 那一条哨兵由组长收口时补（见交付「需组长接线」）。
 */
class ModPackageOrderWireTest {

    private fun pkg(
        id: String,
        requires: List<String> = emptyList(),
    ): ModuleDescriptor = ModuleDescriptor(packageId = id, requires = requires)

    private fun ids(list: List<ModuleDescriptor>): List<String> = list.map { it.packageId }

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$name"),
            File("../app/src/main/kotlin/com/eratw/port/android/$name"),
        )
        return candidates.firstOrNull { it.isFile }?.readText()
            ?: error("找不到 $name（试了 ${candidates.joinToString { it.path }}）")
    }

    // ---- 行为对拍：parse → LinkOrder → ModuleLinkOrder.plan ----

    /**
     * 核心冻结反例（照 `ModuleLinkOrderTest.kahnReadyPriorityIsNotMinimalAdjustmentOfFullOrder`）：
     * 配置 `[a,b,c,d]` 且 `a` 硬依赖 `d` ⇒ 实际 `[b,c,d,a]`，**不是** `[d,a,b,c]`。
     * 壳层接线不许为了「看起来像完整排列」去改核心排序或偷偷做最小调整。
     */
    @Test
    fun `parse 经 LinkOrder 到 plan 的冻结反例仍是 b c d a`() {
        val modules = listOf(
            pkg("a", requires = listOf("d")),
            pkg("b"),
            pkg("c"),
            pkg("d"),
        )
        val order = LinkOrder(UiPrefs.parsePreferredPackageIds("a,b,c,d"))
        val actual = ids(ModuleLinkOrder.plan(modules, order))
        assertEquals(listOf("b", "c", "d", "a"), actual)
        assertFalse("不采用『尽量保留玩家完整顺序』政策", actual == listOf("d", "a", "b", "c"))
    }

    /** 空串 = 缺省 `LinkOrder()`：与不带配置的规范序逐位一致（验收判据 1）。 */
    @Test
    fun `空串配置得到 LinkOrder 缺省规范序`() {
        val modules = listOf(pkg("z"), pkg("a"), pkg("m"))
        val fromEmpty = LinkOrder(UiPrefs.parsePreferredPackageIds(""))
        assertEquals(emptyList<String>(), fromEmpty.preferredPackageIds)
        assertEquals(ids(ModuleLinkOrder.plan(modules)), ids(ModuleLinkOrder.plan(modules, fromEmpty)))
        assertEquals(listOf("a", "m", "z"), ids(ModuleLinkOrder.plan(modules, fromEmpty)))
    }

    /**
     * UI 去重是硬要求：核心 `preferredRanks` 对配置内重复 ID 整组抛 `LinkOrderException`
     * （不静默去重）。`parse` 去重后的配置能正常进 plan；未经去重的同串则整组拒 ——
     * 这正说明去重必须落在壳层输入侧，而不是靠核心兜底。
     */
    @Test
    fun `parse 去重后的配置不会被核心重复 ID 闸整组拒`() {
        val modules = listOf(pkg("a"), pkg("b"), pkg("c"))
        val deduped = LinkOrder(UiPrefs.parsePreferredPackageIds("a,a,b"))
        assertEquals(listOf("a", "b"), deduped.preferredPackageIds)
        assertEquals(listOf("a", "b", "c"), ids(ModuleLinkOrder.plan(modules, deduped)))
        assertThrows(LinkOrderException::class.java) {
            ModuleLinkOrder.plan(modules, LinkOrder(listOf("a", "a", "b")))
        }
    }

    // ---- 源码哨兵 ----

    @Test
    fun `UiPrefs 暴露构造期读点与纯转换函数且总闸缺省仍关`() {
        val prefs = sourceOf("UiPrefs.kt")
        assertTrue(
            "缺公开读点 preferredPackageIds(context)",
            prefs.contains("fun preferredPackageIds(context: Context): List<String>"),
        )
        assertTrue(
            "缺纯转换函数 parsePreferredPackageIds",
            prefs.contains("fun parsePreferredPackageIds(raw: String): List<String>"),
        )
        assertTrue("缺设置页读原始串入口", prefs.contains("fun preferredPackageIdsRaw(context: Context): String"))
        assertTrue("缺设置页写入口", prefs.contains("fun setPreferredPackageIds(context: Context, raw: String)"))
        assertTrue("缺配置键", prefs.contains("KEY_MOD_PACKAGE_ORDER = \"mod_package_order\""))
        // 关态零变化：总闸缺省仍 false（新增键不参与任何关态装配路径）。
        assertTrue("DEFAULT_MOD_RUNTIME 必须仍是 false", prefs.contains("const val DEFAULT_MOD_RUNTIME: Boolean = false"))
    }

    @Test
    fun `ModChainAssembly 的 linkOrder 缺省仍是 LinkOrder 核心零缺口`() {
        val assembly = sourceOf("ModChainAssembly.kt")
        assertTrue(
            "assemble 的 linkOrder 缺省不许改（保留缺省行为）",
            assembly.contains("linkOrder: LinkOrder = LinkOrder()"),
        )
        assertTrue(
            "linkOrder 必须透传给核心 ModChainAssembler.assemble",
            assembly.contains("linkOrder = linkOrder,"),
        )
    }

    /** 组长收口接线钉（契约 §3.6）：GameHost 构造参数 → assemble 的 linkOrder 单路贯通。 */
    @Test
    fun `GameHost 把玩家排序配置接进装配点且 MainActivity 单路传入`() {
        val host = sourceOf("GameHost.kt")
        val activity = sourceOf("MainActivity.kt")
        assertTrue(
            "GameHost 缺 modPackageOrder 构造参数（UI 线程读好传入的承载位）",
            host.contains("private val modPackageOrder: List<String> = emptyList()"),
        )
        assertTrue(
            "GameHost 装配点未把配置接进 linkOrder",
            host.contains("linkOrder = com.eratw.port.core.patch.LinkOrder(modPackageOrder)"),
        )
        assertTrue(
            "MainActivity 未在 UI 线程读偏好传参（线程纪律：装配期不许摸偏好表）",
            activity.contains("modPackageOrder = UiPrefs.preferredPackageIds(this)"),
        )
    }

    /** 设置页无内联玩家可见字面量（AGENTS §6.1 / 契约 §3.3）：三条中文文案只许在文案表里。 */
    @Test
    fun `设置页输入卡用三语文案且无内联玩家可见字面量`() {
        val settings = sourceOf("SettingsActivity.kt")
        val cardStart = settings.indexOf("private fun ModPackageOrderCard(")
        assertTrue("缺输入卡定义", cardStart >= 0)
        assertTrue("缺输入卡调用", settings.contains("ModPackageOrderCard("))
        assertTrue("缺写 UiPrefs 接线", settings.contains("UiPrefs.setPreferredPackageIds(context"))
        assertTrue("缺读 UiPrefs 接线", settings.contains("UiPrefs.preferredPackageIdsRaw(context)"))
        assertTrue("标题没取三语表", settings.contains("text.modPackageOrderTitle"))
        assertTrue("说明没取三语表", settings.contains("text.modPackageOrderHint"))
        assertTrue("空态提示没取三语表", settings.contains("text.modPackageOrderEmpty"))
        // 卡片体内不许出现「双引号包住的中文」玩家面字面量；注释里的中文散文不算。
        // 用 lines() 切片（吃 CRLF），取到行首无缩进的那个 `}` 为止，别用换行字面量匹配。
        val cardBody = settings.substring(cardStart)
            .lines()
            .takeWhile { line -> line != "}" }
            .joinToString("\n")
        val chineseLiteral = Regex("\"[^\"]*[\\u4e00-\\u9fff][^\"]*\"")
        val offenders = chineseLiteral.findAll(cardBody).map { it.value }.toList()
        assertFalse("输入卡内联了玩家可见字面量：$offenders", offenders.isNotEmpty())
        val strings = sourceOf("SettingsStrings.kt")
        assertTrue("三语表缺中文标题", strings.contains("zh = \"Mod 装载优先级\""))
        assertTrue("三语表缺中文说明", strings.contains("用逗号分隔"))
    }
}
