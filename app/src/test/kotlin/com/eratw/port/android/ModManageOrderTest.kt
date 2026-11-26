package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「Mod 装载优先级」卡片的纯逻辑（task-2 ④）：已装列表 × 偏好串 → 勾选序 / 展示序 / 上下移。
 *
 * 断言口径：勾选序只留在装包且保序；没勾的按扫描序补在后面（缺省相对序）；勾选序写回
 * 原始串时保持顺序、空表即缺省。ModDX-4 起新增：偏好串里不在装的残留 ID 以「未知包」行
 * 取出（原序去重、集合意义下与勾选序互补），编辑写回时未移除的残留原样带回串首。
 */
class ModManageOrderTest {

    private fun mod(packageId: String, title: String = packageId): InstalledMod = InstalledMod(
        packageId = packageId,
        displayName = title,
        version = "0.1",
        author = "",
        description = "",
        source = ModSource.DATA_PACKAGE,
        enabled = true,
        hasModule = true,
    )

    @Test
    fun `勾选序只留仍在已装列表里的包并保序`() {
        val chosen = ModManageOrder.seedOrder("eratw.b,old.gone,eratw.a", listOf("eratw.a", "eratw.b", "eratw.c"))

        assertEquals(listOf("eratw.b", "eratw.a"), chosen)
    }

    @Test
    fun `空偏好串给空勾选序 重复 ID 只留一次`() {
        assertTrue(ModManageOrder.seedOrder("", listOf("eratw.a")).isEmpty())
        assertEquals(listOf("eratw.a"), ModManageOrder.seedOrder("eratw.a,eratw.a", listOf("eratw.a")))
    }

    @Test
    fun `展示序 = 勾选序在前 其余按扫描序补齐`() {
        val installed = listOf(mod("eratw.a"), mod("eratw.b"), mod("eratw.c"))

        val order = ModManageOrder.displayOrder(listOf("eratw.c"), installed)

        assertEquals(listOf("eratw.c", "eratw.a", "eratw.b"), order)
    }

    @Test
    fun `展示序跳过不在已装列表的旧 ID`() {
        val installed = listOf(mod("eratw.a"))

        assertEquals(listOf("eratw.a"), ModManageOrder.displayOrder(listOf("old.gone", "eratw.a"), installed))
    }

    @Test
    fun `上移下移一格 越界与不在表里原样返回`() {
        val ids = listOf("eratw.a", "eratw.b", "eratw.c")

        assertEquals(listOf("eratw.b", "eratw.a", "eratw.c"), ModManageOrder.move(ids, "eratw.b", -1))
        assertEquals(listOf("eratw.a", "eratw.c", "eratw.b"), ModManageOrder.move(ids, "eratw.b", 1))
        assertEquals(ids, ModManageOrder.move(ids, "eratw.a", -1))
        assertEquals(ids, ModManageOrder.move(ids, "eratw.c", 1))
        assertEquals(ids, ModManageOrder.move(ids, "eratw.unknown", 1))
    }

    @Test
    fun `写回原始串保持顺序 空表即缺省`() {
        assertEquals("eratw.b,eratw.a", ModManageOrder.toRaw(listOf("eratw.b", "eratw.a")))
        assertEquals("", ModManageOrder.toRaw(emptyList()))
    }

    // ---- ModDX-4 加载顺序 UI 波：未知包残留（判据 1/4）--------------------------

    /**
     * 偏好串里已不在已装列表的残留 ID 按「未知包」行取出：原序、去重、与 seedOrder 互补
     * （两者拼起来 = 归一化后的整条串）。
     */
    @Test
    fun `未知包残留按原序取出且与勾选序互补`() {
        val raw = "old.gone,eratw.b,old.again,eratw.a,old.gone"
        val installed = listOf("eratw.a", "eratw.b", "eratw.c")

        assertEquals(listOf("old.gone", "old.again"), ModManageOrder.leftoverIds(raw, installed))
        assertEquals(listOf("eratw.b", "eratw.a"), ModManageOrder.seedOrder(raw, installed))
        assertEquals(
            "leftoverIds + seedOrder 覆盖归一化串全部 ID（集合互补，互不重复）",
            UiPrefs.parsePreferredPackageIds(raw).toSet(),
            (ModManageOrder.leftoverIds(raw, installed) + ModManageOrder.seedOrder(raw, installed)).toSet(),
        )
        assertTrue("全部在装时无残留", ModManageOrder.leftoverIds("eratw.a,eratw.b", installed).isEmpty())
        assertTrue("空串无残留", ModManageOrder.leftoverIds("", installed).isEmpty())
    }

    /**
     * pref 串往返（UI 编辑结果 → preferredPackageIds 串）：勾选 / 上下移写回时**未移除**的
     * 未知包残留原样带回串首（相对序保留），移除后只少被移的那一个；全空写空串 = 缺省。
     */
    @Test
    fun `编辑写回保留未知包残留 移除只少一项`() {
        val unknown = listOf("old.gone", "old.again")
        val chosen = listOf("eratw.b", "eratw.a")

        // 玩家上下移 / 勾选后的写回：残留原样带回，勾选序紧随其后。
        assertEquals("old.gone,old.again,eratw.b,eratw.a", ModManageOrder.toRaw(unknown, chosen))
        // 点了「移除」的行：只少被移的那一个。
        assertEquals("old.again,eratw.b,eratw.a", ModManageOrder.toRaw(unknown - "old.gone", chosen))
        // 全部移除 + 全部取消勾选 = 空串 = 缺省序（恢复缺省同口径）。
        assertEquals("", ModManageOrder.toRaw(emptyList(), emptyList()))
    }

    /** 旧串格式直读可用（判据 4）：带空白 / 空段 / 重复的旧串不走迁移、直接进新编辑面。 */
    @Test
    fun `旧串格式直读可用无需迁移`() {
        val legacy = " eratw.b , ,old.gone ,,eratw.b,eratw.a "
        val installed = listOf("eratw.a", "eratw.b", "eratw.c")

        assertEquals(listOf("eratw.b", "eratw.a"), ModManageOrder.seedOrder(legacy, installed))
        assertEquals(listOf("old.gone"), ModManageOrder.leftoverIds(legacy, installed))
        // 旧串在存储层的直读口径不变：parsePreferredPackageIds → ModPackageOrderStore.parse
        //（保序去重按首次出现：eratw.b → old.gone → eratw.a）。
        assertEquals(listOf("eratw.b", "old.gone", "eratw.a"), UiPrefs.parsePreferredPackageIds(legacy))
    }
}
