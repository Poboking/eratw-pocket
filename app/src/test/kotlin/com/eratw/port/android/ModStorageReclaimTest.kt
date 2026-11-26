package com.eratw.port.android

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显式卸载的存档段回收（task-134 ①/②）的**盘面与队列面**判据：
 * `mods/rt2/released-packages.txt` 的读写、防穿越、fail-open、重导惰性失效，
 * 以及「设置页 → VM 页边界」的会话内回收请求队列。
 *
 * 与 `ModStorageUninstallRetentionE2ETest` 的分工：那边用真装配 + 真存档文件量「段真的
 * 减在下次存档」；本类只驱动可独立读写/入队的面，不跑装配。
 */
class ModStorageReclaimTest {

    private fun tempRt2(): File = Files.createTempDirectory("mod-storage-reclaim").toFile()

    // ------------------------------------------------------------------
    // 清单读面
    // ------------------------------------------------------------------

    @Test
    fun `清单缺失时按空集处理 不误跳任何段`() {
        val rt2 = tempRt2()
        try {
            assertEquals(emptySet<String>(), ModStorageReclaim.releasedPackageIds(rt2))
            assertEquals(emptySet<String>(), ModStorageReclaim.reclaimedPackageIds(rt2))
            assertFalse("缺失目录也不许现造清单", ModStorageReclaim.listFile(rt2).exists())
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `record 落盘可读且幂等 不残留临时文件`() {
        val rt2 = tempRt2()
        try {
            assertTrue(ModStorageReclaim.record(rt2, "kojo-aaaa1111"))
            assertEquals("kojo-aaaa1111\n", ModStorageReclaim.listFile(rt2).readText())
            assertTrue("重复 record 必须幂等成功", ModStorageReclaim.record(rt2, "kojo-aaaa1111"))
            assertEquals("kojo-aaaa1111\n", ModStorageReclaim.listFile(rt2).readText())
            assertTrue(
                "原子写不许留 tmp 残骸",
                rt2.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
            )
            assertTrue(ModStorageReclaim.record(rt2, "kojo-bbbb2222"))
            assertEquals(
                "清单按 packageId 排序、末行换行",
                "kojo-aaaa1111\nkojo-bbbb2222\n",
                ModStorageReclaim.listFile(rt2).readText(),
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `record 非法包名拒绝 清单不落盘`() {
        val rt2 = tempRt2()
        try {
            for (bad in listOf("", " ", "../evil", "a/b", "a\\b", ".hidden", "-lead")) {
                val threw = runCatching { ModStorageReclaim.record(rt2, bad) }.exceptionOrNull()
                assertTrue("非法包名 $bad 应被拒", threw is IllegalArgumentException)
            }
            assertFalse("非法输入不许留下清单文件", ModStorageReclaim.listFile(rt2).exists())
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `清单坏行与穿越行忽略 只认合法行`() {
        val rt2 = tempRt2()
        try {
            ModStorageReclaim.listFile(rt2).writeText(
                """
                # 注释行
                ../evil

                  /abs/path
                kojo-bbbb2222
                ok.pkg
                """.trimIndent() + "\n",
            )
            assertEquals(
                "只有形状合法的 packageId 行算数（穿越行拿去拼路径会逃出 rt2）",
                setOf("kojo-bbbb2222", "ok.pkg"),
                ModStorageReclaim.releasedPackageIds(rt2),
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 跳过集语义：重导 / 资源回装后旧条目惰性失效
    // ------------------------------------------------------------------

    @Test
    fun `重导后同名包不跳过 旧清单条目惰性失效`() {
        val rt2 = tempRt2()
        try {
            assertTrue(ModStorageReclaim.record(rt2, "kojo-aaaa1111"))
            assertEquals(
                "盘上无产物 ⇒ 进跳过集",
                setOf("kojo-aaaa1111"),
                ModStorageReclaim.reclaimedPackageIds(rt2),
            )
            // 重导 / 换版本后同名包的 manifest 又在了 ⇒ 不许跳过（新数据要照常落档）。
            File(rt2, "kojo-aaaa1111.manifest").writeText("manifest")
            assertEquals(
                "产物在场 ⇒ 惰性失效，不跳过",
                emptySet<String>(),
                ModStorageReclaim.reclaimedPackageIds(rt2),
            )
            // 资源目录同样是「包在场」的形状。
            Files.deleteIfExists(File(rt2, "kojo-aaaa1111.manifest").toPath())
            Files.createDirectories(File(rt2, "kojo-aaaa1111.assets").toPath())
            assertEquals(emptySet<String>(), ModStorageReclaim.reclaimedPackageIds(rt2))
        } finally {
            rt2.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 卸载接点 / 停用边界
    // ------------------------------------------------------------------

    @Test
    fun `显式卸载写清单 停用不写清单`() {
        val rt2 = tempRt2()
        try {
            File(rt2, "kojo-aaaa1111.manifest").writeText("manifest")
            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-aaaa1111"))
            assertEquals(
                "显式卸载必须落回收台账（抓取侧据此减在下次存档）",
                setOf("kojo-aaaa1111"),
                ModStorageReclaim.releasedPackageIds(rt2),
            )

            assertTrue(ModEnablement.setEnabled(rt2, "kojo-bbbb2222", false))
            assertFalse(
                "停用 = 保留数据，不是卸载，不许写回收台账",
                "kojo-bbbb2222" in ModStorageReclaim.releasedPackageIds(rt2),
            )
        } finally {
            rt2.deleteRecursively()
        }
    }

    @Test
    fun `回收清单不会被当成一个可管理的 Mod`() {
        val gameRoot = Files.createTempDirectory("mod-reclaim-scan").toFile()
        try {
            val rt2 = ModEnablement.rt2Dir(gameRoot)
            assertTrue(rt2.mkdirs())
            assertTrue(ModStorageReclaim.record(rt2, "kojo-aaaa1111"))
            val scan = ModManageScanner.scan(gameRoot)
            assertEquals("清单名不是 *.manifest，扫描不得多出一个 Mod", 0, scan.mods.size)
        } finally {
            gameRoot.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // 会话内回收队列（③ 的投递面）
    // ------------------------------------------------------------------

    @Test
    fun `回收请求队列去重 取走即清 非法忽略 收口清空`() {
        ModStorageReclaimRequests.clear()
        try {
            ModStorageReclaimRequests.request("kojo-aaaa1111")
            ModStorageReclaimRequests.request("kojo-aaaa1111")
            ModStorageReclaimRequests.request("../evil")
            ModStorageReclaimRequests.request("")
            assertEquals("去重 + 非法忽略", 1, ModStorageReclaimRequests.pendingCount())
            assertEquals(listOf("kojo-aaaa1111"), ModStorageReclaimRequests.drain())
            assertEquals("取走即清", 0, ModStorageReclaimRequests.pendingCount())
            assertEquals(emptyList<String>(), ModStorageReclaimRequests.drain())

            ModStorageReclaimRequests.request("kojo-bbbb2222")
            ModStorageReclaimRequests.clear()
            assertEquals("收口清场", 0, ModStorageReclaimRequests.pendingCount())
        } finally {
            ModStorageReclaimRequests.clear()
        }
    }
}
