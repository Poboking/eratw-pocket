package com.eratw.port.android

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销信任 → 装载闸的端到端用例（task-165 / ADR-013 路 B 撤销方向）。
 *
 * 每条用例都走**真实闸序**：[ModDexLoader.decide]（`ModDexLoader.kt:127-143`）→ ABI 闸
 * （`:134`）→ 总闸（`:137`）→ 包名闸（`:138-139`）→ **信任闸（`:140`，回 `NeedsTrust`）** →
 * 盘面 / 完整性 / 工厂（`:141` 起）。数据面全真：`code/` 产物 + `.trusted` 标记 + [ModCodeStore]
 * + [ModTrustStore] / [ModTrustBoard]；唯一替身是 [RevokeRecordingFactory]（不真构造
 * `DexClassLoader`）。断言口径：撤销后必须停在信任闸、**不进**产物读取与类加载器构造。
 */
class ModTrustRevokeE2ETest {
    private val temps = mutableListOf<File>()

    @After
    fun tearDown() {
        temps.forEach { deleteRevokeTree(it) }
    }

    /** 建临时 rt2 根并登记收尾。 */
    private fun tempDir(): File = revokeTempDir().also { temps += it }

    @Test
    fun `信任确认后真实装载成功且类加载器只建一次`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val board = ModTrustBoard(rt2)
        assertEquals("撤销前必须能看到一行产物", 1, board.scan().size)
        assertFalse("初始必须未信任", board.rows.single().trusted)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertEquals("待确认包名必须进状态机", REVOKE_PACKAGE_ID, board.pendingTrustPackageId)
        assertTrue("确认信任必须落盘成功", board.confirmTrust())
        assertTrue("落盘后重扫必须为已信任", board.rows.single().trusted)
        assertTrue(ModDexGate.isTrusted(rt2, REVOKE_PACKAGE_ID))
        val factory = RevokeRecordingFactory()
        val outcome = revokeLoaderOf(rt2, factory)
            .load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("已信任 + 产物完整必须 Loaded，实际：$outcome", outcome is LoadOutcome.Loaded)
        assertEquals(listOf(REVOKE_PACKAGE_ID), factory.createdFor)
    }

    @Test
    fun `撤销信任后同一装载器再装载停在信任闸 NeedsTrust`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val board = ModTrustBoard(rt2)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue(board.confirmTrust())
        val factory = RevokeRecordingFactory()
        val loader = revokeLoaderOf(rt2, factory)
        val before = loader.load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("前置：撤销前必须能装载，实际：$before", before is LoadOutcome.Loaded)
        assertEquals(1, factory.createdFor.size)

        assertTrue("撤销必须返回成功", board.revoke(REVOKE_PACKAGE_ID))
        assertFalse("撤销后标记必须不在", File(rt2, ModDexGate.trustMarkerName(REVOKE_PACKAGE_ID)).exists())
        assertFalse("撤销后重扫必须回未信任", board.rows.single().trusted)

        val after = loader.load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("撤销后必须回 NeedsTrust，实际：$after", after is LoadOutcome.NeedsTrust)
        assertEquals(
            "该运行包尚未获得信任确认，未装载（确认信任后重开会话生效）",
            (after as LoadOutcome.NeedsTrust).reason,
        )
        assertEquals("停在第 140 行信任闸，不得再建类加载器", 1, factory.createdFor.size)
    }

    @Test
    fun `未信任但产物完整也停在信任闸不读产物`() {
        val rt2 = tempDir()
        val artifact = writeRevokeArtifact(rt2)
        assertTrue("前置：产物必须真实存在", artifact.isFile)
        val factory = RevokeRecordingFactory()
        val outcome = revokeLoaderOf(rt2, factory)
            .load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("未信任必须 NeedsTrust，实际：$outcome", outcome is LoadOutcome.NeedsTrust)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `闸序 ABI 闸拒绝先于信任闸 超前编译面 Rejected 而非 NeedsTrust`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val factory = RevokeRecordingFactory()
        val outcome = revokeLoaderOf(rt2, factory)
            .load(REVOKE_PACKAGE_ID, revokeMetaOf(compiledSurface = REVOKE_SURFACE_AHEAD), REVOKE_ENGINE_API_VERSION)
        assertTrue("超前编译面必须先被 ABI 闸拒（ModDexLoader.kt:134-136），实际：$outcome", outcome is LoadOutcome.Rejected)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `闸序 总闸关闭先于信任闸 Disabled 而非 NeedsTrust`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val factory = RevokeRecordingFactory()
        val outcome = revokeLoaderOf(rt2, factory, enabled = false)
            .load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("总闸关必须先被拒（ModDexLoader.kt:137），实际：$outcome", outcome is LoadOutcome.Disabled)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `闸序 包名合法性先于信任闸 InvalidPackage`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val factory = RevokeRecordingFactory()
        val outcome = revokeLoaderOf(rt2, factory)
            .load("../escape", revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("非法包名必须先拒（ModDexLoader.kt:138-139），实际：$outcome", outcome is LoadOutcome.InvalidPackage)
        assertEquals(0, factory.createdFor.size)
    }

    @Test
    fun `只读概览读数随信任与撤销即时翻转`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val board = ModTrustBoard(rt2)
        assertFalse("初始概览必须未信任", buildModCodeOverview(rt2, true).rows.single().trusted)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue(board.confirmTrust())
        assertTrue("信任后概览必须为已信任", buildModCodeOverview(rt2, true).rows.single().trusted)
        assertTrue(board.revoke(REVOKE_PACKAGE_ID))
        assertFalse("撤销后概览必须回未信任", buildModCodeOverview(rt2, true).rows.single().trusted)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue(board.confirmTrust())
        assertTrue("再信任后概览必须再翻转", buildModCodeOverview(rt2, true).rows.single().trusted)
    }

    @Test
    fun `撤销契约 幂等且非法包名拒写拒删而盘面不增`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        assertTrue("本来就没有标记时撤销也算成功（幂等）", boardOf(rt2).revoke(REVOKE_PACKAGE_ID))
        val before = rt2.list().orEmpty().sorted()
        assertTrue("第二次撤销仍须成功", boardOf(rt2).revoke(REVOKE_PACKAGE_ID))
        assertEquals("撤销不得新建/删除盘面文件", before, rt2.list().orEmpty().sorted())
        listOf("", " ", "../escape", "a/b", ".hidden", "-lead").forEach { bad ->
            assertFalse("非法包名不得落信任：[$bad]", ModTrustStore.trust(rt2, bad))
            assertFalse("非法包名不得删标记：[$bad]", ModTrustStore.distrust(rt2, bad))
            assertThrows("非法包名拼标记名必须抛 IAE：[$bad]", IllegalArgumentException::class.java) {
                ModDexGate.trustMarkerName(bad)
            }
        }
        assertEquals(before, rt2.list().orEmpty().sorted())
    }

    /** 每次新建 board 走独立状态机，验证 revoke 不依赖上一次 scan 的缓存。 */
    private fun boardOf(rt2: File): ModTrustBoard = ModTrustBoard(rt2)
}
