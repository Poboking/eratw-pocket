package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「已安装的 Mod」列表的搜索 + 来源筛选（Lane modsearch，task-168）的纯逻辑与文案闸。
 *
 * 被测面是 [filterInstalledMods]（纯视图过滤，保扫描序、不看启停态）与三语文案表新增的
 * 四条（搜索框标签 / 「全部」档 / 筛选后空结果 / 计数行；形状闸的条目点名清单在
 * `SettingsStringsModTest` 的 Mod 管理面用例里同步计数）。断言口径：
 * - 关键词按 displayName / packageId / author 大小写不敏感包含匹配，只含空白 = 空串 = 全部；
 * - 来源档与关键词取交集，停用项不因搜索消失；
 * - 「筛选后没有匹配」与「一个都没装」是两条不同文案，三语都不得撞；
 * - 计数行 N / M 分别取可见条数与已装总条数。
 *
 * 不起 Compose：过滤与文案都是纯函数，`mods/rt2` 资产与 marker 都不碰。
 */
class ModManageSearchTest {

    private fun mod(
        packageId: String,
        displayName: String = packageId,
        author: String = "",
        source: ModSource = ModSource.DATA_PACKAGE,
        enabled: Boolean = true,
    ): InstalledMod = InstalledMod(
        packageId = packageId,
        displayName = displayName,
        version = "0.1",
        author = author,
        description = "",
        source = source,
        enabled = enabled,
        hasModule = source == ModSource.BUILTIN,
    )

    private fun idsOf(mods: List<InstalledMod>): List<String> = mods.map { it.packageId }

    @Test
    fun `空查询与全部档原样返回扫描序`() {
        val mods = listOf(
            mod("eratw.c", displayName = "Gamma"),
            mod("eratw.a", displayName = "Alpha"),
            mod("eratw.b", displayName = "Beta"),
        )

        val visible = filterInstalledMods(mods, "", ModManageSourceFilter.ALL)

        assertEquals(listOf("eratw.c", "eratw.a", "eratw.b"), idsOf(visible))
        assertEquals(mods, visible)
    }

    @Test
    fun `按显示名匹配且大小写不敏感`() {
        val mods = listOf(
            mod("eratw.a", displayName = "Alpha Pack"),
            mod("eratw.b", displayName = "Beta Pack"),
        )

        assertEquals(listOf("eratw.a"), idsOf(filterInstalledMods(mods, "ALPHA", ModManageSourceFilter.ALL)))
        assertEquals(listOf("eratw.a"), idsOf(filterInstalledMods(mods, "alpha", ModManageSourceFilter.ALL)))
        assertEquals(listOf("eratw.a", "eratw.b"), idsOf(filterInstalledMods(mods, "PACK", ModManageSourceFilter.ALL)))
    }

    @Test
    fun `按包名匹配且大小写不敏感`() {
        val mods = listOf(
            mod("eratw.family", displayName = "家督"),
            mod("eratw.tool", displayName = "工具"),
        )

        assertEquals(listOf("eratw.family"), idsOf(filterInstalledMods(mods, "ERATW.FAM", ModManageSourceFilter.ALL)))
        assertEquals(listOf("eratw.tool"), idsOf(filterInstalledMods(mods, "Tool", ModManageSourceFilter.ALL)))
    }

    @Test
    fun `按作者匹配且大小写不敏感`() {
        val mods = listOf(
            mod("eratw.a", displayName = "A", author = "Alice"),
            mod("eratw.b", displayName = "B", author = "Bob"),
        )

        assertEquals(listOf("eratw.a"), idsOf(filterInstalledMods(mods, "alice", ModManageSourceFilter.ALL)))
        assertEquals(listOf("eratw.b"), idsOf(filterInstalledMods(mods, "BOB", ModManageSourceFilter.ALL)))
    }

    @Test
    fun `查询只含空白等同空串`() {
        val mods = listOf(mod("eratw.a"), mod("eratw.b"))

        assertEquals(idsOf(mods), idsOf(filterInstalledMods(mods, "   ", ModManageSourceFilter.ALL)))
        assertEquals(idsOf(mods), idsOf(filterInstalledMods(mods, "\t\n ", ModManageSourceFilter.ALL)))
    }

    @Test
    fun `来源档只留对应来源`() {
        val mods = listOf(
            mod("eratw.builtin", source = ModSource.BUILTIN),
            mod("eratw.data", source = ModSource.DATA_PACKAGE),
            mod("eratw.imported", source = ModSource.IMPORTED),
        )

        assertEquals(listOf("eratw.builtin"), idsOf(filterInstalledMods(mods, "", ModManageSourceFilter.BUILTIN)))
        assertEquals(listOf("eratw.data"), idsOf(filterInstalledMods(mods, "", ModManageSourceFilter.DATA_PACKAGE)))
        assertEquals(listOf("eratw.imported"), idsOf(filterInstalledMods(mods, "", ModManageSourceFilter.IMPORTED)))
        assertEquals(3, filterInstalledMods(mods, "", ModManageSourceFilter.ALL).size)
    }

    @Test
    fun `搜索与来源筛选取交集`() {
        val mods = listOf(
            mod("eratw.builtin.family", displayName = "家督", source = ModSource.BUILTIN),
            mod("eratw.data.family", displayName = "家督数据包", source = ModSource.DATA_PACKAGE),
        )

        assertEquals(
            listOf("eratw.builtin.family"),
            idsOf(filterInstalledMods(mods, "family", ModManageSourceFilter.BUILTIN)),
        )
        assertEquals(
            listOf("eratw.data.family"),
            idsOf(filterInstalledMods(mods, "FAMILY", ModManageSourceFilter.DATA_PACKAGE)),
        )
        assertTrue(filterInstalledMods(mods, "family", ModManageSourceFilter.IMPORTED).isEmpty())
    }

    @Test
    fun `停用项不被搜索过滤掉`() {
        val mods = listOf(
            mod("eratw.off", displayName = "Off Pack", enabled = false),
            mod("eratw.on", displayName = "On Pack", enabled = true),
        )

        assertEquals(listOf("eratw.off"), idsOf(filterInstalledMods(mods, "off", ModManageSourceFilter.ALL)))
        assertEquals(
            listOf("eratw.off", "eratw.on"),
            idsOf(filterInstalledMods(mods, "pack", ModManageSourceFilter.ALL)),
        )
    }

    @Test
    fun `无匹配返回空表`() {
        val mods = listOf(mod("eratw.a", displayName = "Alpha", author = "Alice"))

        assertTrue(filterInstalledMods(mods, "zzz", ModManageSourceFilter.ALL).isEmpty())
        assertTrue(filterInstalledMods(emptyList(), "a", ModManageSourceFilter.ALL).isEmpty())
    }

    @Test
    fun `过滤后为空与完全没装文案不同`() {
        for (language in SettingsLanguage.entries) {
            val text = SettingsStrings.of(language)

            assertTrue(text.modManageFilterEmpty.isNotBlank())
            assertNotEquals(
                "$language：筛选后空结果不能复用「一个都没装」文案",
                text.modManageEmpty,
                text.modManageFilterEmpty,
            )
        }
        assertEquals(
            "没有匹配的 Mod。换个关键词，或把来源筛选切回「全部」。",
            SettingsStrings.of(SettingsLanguage.ZH).modManageFilterEmpty,
        )
    }

    @Test
    fun `计数行三语口径`() {
        val shown = 2
        val total = shown + 1

        assertEquals("筛选结果 2 / 共 3", SettingsStrings.of(SettingsLanguage.ZH).modManageFilterCount(shown, total))
        assertEquals("Showing 2 of 3", SettingsStrings.of(SettingsLanguage.EN).modManageFilterCount(shown, total))
        assertEquals("表示 2 / 全 3", SettingsStrings.of(SettingsLanguage.JA).modManageFilterCount(shown, total))
        // 0 条也要能说：筛选全灭时计数行是「0 / 共 M」，不是空白。
        assertEquals("筛选结果 0 / 共 3", SettingsStrings.of(SettingsLanguage.ZH).modManageFilterCount(0, total))
    }
}
