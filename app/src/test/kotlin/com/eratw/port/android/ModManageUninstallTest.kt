package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卸载的盘面语义（task-2 ③）：删本包三件套 + 停用标记，别碰别的包与 mods 根的原始文件。
 *
 * 断言口径：① 成员删干净、幂等；② 别人的包与玩家原始口上文件原样保留；
 * ③ 非法包名在拼路径前就被拒（与 `ModEnablement.markerFile` 同口径）。
 */
class ModManageUninstallTest {

    private fun tempRt2(): File = Files.createTempDirectory("rt2-uninstall").toFile()

    private fun writeMembers(rt2: File, packageId: String) {
        File(rt2, "$packageId.sources").writeText("a.ERB\n")
        File(rt2, "$packageId.manifest").writeText("manifest")
        File(rt2, "$packageId.erbm").writeText("module")
    }

    @Test
    fun `删掉三件套与停用标记 保留别的包`() {
        val rt2 = tempRt2()
        try {
            writeMembers(rt2, "kojo-1a2b3c4d")
            ModEnablement.setEnabled(rt2, "kojo-1a2b3c4d", false)
            writeMembers(rt2, "kojo-ffffffff")
            val keep = File(rt2, "eratw.family.manifest").apply { writeText("builtin") }

            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-1a2b3c4d"))

            val left = rt2.listFiles()!!.map { it.name }.toSet()
            assertFalse(left.contains("kojo-1a2b3c4d.sources"))
            assertFalse(left.contains("kojo-1a2b3c4d.manifest"))
            assertFalse(left.contains("kojo-1a2b3c4d.erbm"))
            assertFalse("停用标记也要删掉", left.contains("kojo-1a2b3c4d.off"))
            assertTrue("别的包的成员不许动", left.contains("kojo-ffffffff.erbm"))
            assertTrue("内置清单不许动", keep.isFile)
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `卸载幂等 文件本来就不在也算成功`() {
        val rt2 = tempRt2()
        try {
            writeMembers(rt2, "kojo-1a2b3c4d")

            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-1a2b3c4d"))
            assertTrue("第二次卸载仍应成功", ModManageUninstall.uninstall(rt2, "kojo-1a2b3c4d"))
            assertTrue("没装过也不该抛", ModManageUninstall.uninstall(rt2, "kojo-99999999"))
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `rt2 目录不存在时直接成功`() {
        val rt2 = tempRt2()
        try {
            rt2.deleteRecursively()

            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-1a2b3c4d"))
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `非法包名拒绝拼路径`() {
        val rt2 = tempRt2()
        try {
            for (bad in listOf("", " ", "../evil", "a/b", "a\\b", ".hidden", "-lead")) {
                val threw = runCatching { ModManageUninstall.uninstall(rt2, bad) }.exceptionOrNull()
                assertTrue("非法包名 $bad 应被拒", threw is IllegalArgumentException)
            }
            assertEquals(0, rt2.listFiles()?.size ?: 0)
        } finally {
            rt2.deleteRecursively()
        }
    }
}
