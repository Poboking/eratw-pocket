package com.eratw.port.android

import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撤销信任方向的反证（task-165）：证明撤销是**只动信任闸**、不改任何产物盘面，且反向路径
 * （重新确认 / 显式卸载）的结局与既有语义一致，不是「撤销顺手把产物删了」或「信任闸挡在
 * 产物闸之后」这类假绿。
 *
 * 反向证明口径：同一夹具把唯一变量翻回去（重新信任 → 又能装载；卸载删净标记 → 回到待确认），
 * 每次都应回到 `NeedsTrust` / `Loaded` 的**预期**分支，而不是其它失败分支。
 */
class ModTrustRevokeCounterProofTest {
    private val temps = mutableListOf<File>()

    @After
    fun tearDown() {
        temps.forEach { deleteRevokeTree(it) }
    }

    private fun tempDir(): File = revokeTempDir().also { temps += it }

    @Test
    fun `反证 撤销只删标记不动产物 结局是 NeedsTrust 而非缺产物`() {
        val rt2 = tempDir()
        val artifact = writeRevokeArtifact(rt2)
        val originalBytes = artifact.readBytes()
        val before = ModCodeStore(rt2).list(REVOKE_PACKAGE_ID)
        assertEquals("前置：盘面必须有一件产物", 1, before.size)
        val board = ModTrustBoard(rt2)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue(board.confirmTrust())

        assertTrue(board.revoke(REVOKE_PACKAGE_ID))

        val after = ModCodeStore(rt2).list(REVOKE_PACKAGE_ID)
        assertEquals("撤销不得改变产物条目", before.map { it.sha256Hex }, after.map { it.sha256Hex })
        assertEquals("撤销不得改变产物体积", before.map { it.sizeBytes }, after.map { it.sizeBytes })
        assertTrue("撤销后产物文件必须仍在", artifact.isFile)
        assertArrayEquals("撤销不得改写产物内容", originalBytes, artifact.readBytes())

        val outcome = revokeLoaderOf(rt2).load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("撤销后必须停在信任闸：$outcome", outcome is LoadOutcome.NeedsTrust)
        assertFalse("不得落到缺产物", outcome is LoadOutcome.MissingArtifact)
        assertFalse("不得落到完整性失败", outcome is LoadOutcome.IntegrityFailed)
        assertFalse("不得落到工厂失败", outcome is LoadOutcome.LoaderFailed)
    }

    @Test
    fun `反证 撤销后重新确认信任即恢复装载`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val board = ModTrustBoard(rt2)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue(board.confirmTrust())
        val factory = RevokeRecordingFactory()
        val loader = revokeLoaderOf(rt2, factory)
        assertTrue(loader.load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION) is LoadOutcome.Loaded)

        assertTrue(board.revoke(REVOKE_PACKAGE_ID))
        assertTrue(loader.load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION) is LoadOutcome.NeedsTrust)
        assertEquals("被信任闸挡住时不得建类加载器", 1, factory.createdFor.size)

        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue("重新确认必须能落盘", board.confirmTrust())
        val restored = loader.load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("重新信任后必须恢复装载，实际：$restored", restored is LoadOutcome.Loaded)
        assertEquals(2, factory.createdFor.size)
    }

    @Test
    fun `卸载删净信任标记后装载回 NeedsTrust 而非缺产物`() {
        val rt2 = tempDir()
        writeRevokeArtifact(rt2)
        val board = ModTrustBoard(rt2)
        board.requestTrust(REVOKE_PACKAGE_ID)
        assertTrue(board.confirmTrust())
        assertTrue(ModDexGate.isTrusted(rt2, REVOKE_PACKAGE_ID))

        assertTrue("卸载必须报成功", ModManageUninstall.uninstall(rt2, REVOKE_PACKAGE_ID))
        assertFalse("卸载必须删净 .trusted", File(rt2, ModDexGate.trustMarkerName(REVOKE_PACKAGE_ID)).exists())
        assertFalse("卸载必须删净 code 包目录", File(File(rt2, MOD_CODE_DIR_NAME), REVOKE_PACKAGE_ID).exists())
        assertTrue(board.scan().isEmpty())

        val outcome = revokeLoaderOf(rt2).load(REVOKE_PACKAGE_ID, revokeMetaOf(), REVOKE_ENGINE_API_VERSION)
        assertTrue("重装前必须回 NeedsTrust（信任闸在产物闸之前）：$outcome", outcome is LoadOutcome.NeedsTrust)
        assertFalse("不得因 code 目录已删而落到缺产物", outcome is LoadOutcome.MissingArtifact)
    }
}
