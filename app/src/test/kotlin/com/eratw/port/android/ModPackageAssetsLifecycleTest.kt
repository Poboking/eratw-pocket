package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资源面**生命周期闭环**（task-29）：`mods/rt2/<packageId>.assets/` 是包的第 5 个成员形状，
 * 卸载与孤儿 GC 都必须把它算进所属包，且绝不单独当成「额外包」或孤儿件。
 *
 * 判据：
 * 1. 卸载 [[ModManageUninstall]] 把同包资源目录整棵树删掉（含嵌套子目录），不碰别的包；
 * 2. 孤儿 GC [[ModPackageInventory.gcOrphans]] 把**半成品**包的资源目录一起清掉并留痕；
 *    源失联包整套保留（「源失联」删除判据已于 2026-11-18 审计裁决撤销，只留取证行）；
 * 3. 健康包的资源目录保留；只剩资源目录（无任何包文件）时保守不删，内置/手放前缀目录不碰。
 */
class ModPackageAssetsLifecycleTest {

    private fun tempRoot(): File = Files.createTempDirectory("mod-assets-lifecycle").toFile()

    private fun rt2Of(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    private fun modsOf(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun writeMembers(dir: File, packageId: String) {
        File(dir, "$packageId.sources").writeText("a.ERB\n", Charsets.UTF_8)
        File(dir, "$packageId.sources.sha256").writeText("fp", Charsets.UTF_8)
        File(dir, "$packageId.manifest").writeText("manifest", Charsets.UTF_8)
        File(dir, "$packageId.erbm").writeText("module", Charsets.UTF_8)
        // assetmap 也是包成员（ModPackageInventory.memberNames 认它），夹具补齐才与真实落盘一致。
        File(dir, "$packageId.assetmap").writeText("{}", Charsets.UTF_8)
        // 代码身份侧车同样是成员名单第 7 件（只有带 code.mod 的包才写）：写真实格式，
        // 卸载/GC 的「整套保留/整套清掉」判据必须把它算进所属包。
        ModCodeIdentity.write(dir, packageId, CODE_RUNTIME_ID)
    }

    private fun assetDir(dir: File, packageId: String): File =
        File(dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX)

    private fun writeAsset(dir: File, packageId: String, name: String, bytes: ByteArray = byteArrayOf(1)) {
        val target = File(assetDir(dir, packageId), name)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
    }

    @Test
    fun `卸载把同包资源目录整树一起删`() {
        val root = tempRoot()
        val rt2 = rt2Of(root)
        try {
            writeMembers(rt2, "kojo-1a2b3c4d")
            ModEnablement.setEnabled(rt2, "kojo-1a2b3c4d", false)
            writeAsset(rt2, "kojo-1a2b3c4d", "README.txt")
            writeAsset(rt2, "kojo-1a2b3c4d", "img/deep/icon.png", byteArrayOf(1, 2, 3))
            assertTrue(assetDir(rt2, "kojo-1a2b3c4d").isDirectory)

            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-1a2b3c4d"))

            assertFalse("资源目录应整树删掉", assetDir(rt2, "kojo-1a2b3c4d").exists())
            assertFalse("停用标记也应删掉", File(rt2, "kojo-1a2b3c4d.off").exists())
            assertTrue(
                "包的全部成员都得清干净",
                rt2.listFiles().orEmpty().none { it.name.startsWith("kojo-1a2b3c4d") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `卸载不碰别的包的资源目录`() {
        val root = tempRoot()
        val rt2 = rt2Of(root)
        try {
            writeMembers(rt2, "kojo-aaaa1111")
            writeAsset(rt2, "kojo-aaaa1111", "a.txt")
            writeMembers(rt2, "kojo-bbbb2222")
            writeAsset(rt2, "kojo-bbbb2222", "keep.txt", byteArrayOf(7, 8))

            assertTrue(ModManageUninstall.uninstall(rt2, "kojo-aaaa1111"))

            assertFalse(assetDir(rt2, "kojo-aaaa1111").exists())
            val kept = File(assetDir(rt2, "kojo-bbbb2222"), "keep.txt")
            assertTrue("别的包资源必须原样保留", kept.isFile)
            assertEquals(2, kept.readBytes().size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 把半成品包的资源目录一起清掉`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val rt2 = rt2Of(root)
        try {
            File(rt2, "kojo-cccc3333.manifest").writeText("manifest", Charsets.UTF_8)
            writeAsset(rt2, "kojo-cccc3333", "half.txt")

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertFalse(File(rt2, "kojo-cccc3333.manifest").exists())
            assertFalse("半成品包的资源目录应跟着整树清掉", assetDir(rt2, "kojo-cccc3333").exists())
            assertTrue(
                "资源目录删除也要留痕",
                SaveDiagnostics.snapshot().any {
                    it.contains("MOD_PKG_GC") && it.contains("kojo-cccc3333.assets")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 不删源失联包及其资源目录`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val rt2 = rt2Of(root)
        try {
            writeMembers(rt2, "kojo-dddd4444")
            File(rt2, "kojo-dddd4444.sources").writeText("gone.ERB\n", Charsets.UTF_8)
            writeAsset(rt2, "kojo-dddd4444", "dead.txt")

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertTrue(
                "完整包一律保留：成员一个不少（源失联不再构成删除依据）",
                ModPackageInventory.memberNames("kojo-dddd4444").all { File(rt2, it).isFile },
            )
            assertTrue(
                "资源目录也应保留",
                File(assetDir(rt2, "kojo-dddd4444"), "dead.txt").isFile,
            )
            assertTrue(
                "只留一条「完整包保留」取证行",
                SaveDiagnostics.snapshot().any {
                    it.contains("MOD_PKG_GC") && it.contains("完整包保留")
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 保留健康包的资源目录且不判成额外包`() {
        val root = tempRoot()
        val rt2 = rt2Of(root)
        try {
            File(rt2, "kojo-eeee5555.erbm").writeText("module", Charsets.UTF_8)
            File(rt2, "kojo-eeee5555.manifest").writeText("manifest", Charsets.UTF_8)
            writeAsset(rt2, "kojo-eeee5555", "keep/readme.txt", byteArrayOf(1, 2, 3))

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertTrue(File(rt2, "kojo-eeee5555.erbm").isFile)
            assertTrue(File(rt2, "kojo-eeee5555.manifest").isFile)
            assertTrue(
                "健康包的资源目录必须保留（不当额外包清掉）",
                File(assetDir(rt2, "kojo-eeee5555"), "keep/readme.txt").isFile,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `GC 不删只剩资源目录的包与内置前缀目录`() {
        val root = tempRoot()
        val rt2 = rt2Of(root)
        try {
            writeAsset(rt2, "kojo-ffff6666", "orphan.txt")
            writeAsset(rt2, "eratw.foo", "builtin.txt")
            val handmade = File(rt2, "handmade.assets").apply { mkdirs() }

            ModPackageInventory.gcOrphans(rt2, modsOf(root))

            assertTrue(
                "只有资源目录、没有包文件时保守保留",
                File(assetDir(rt2, "kojo-ffff6666"), "orphan.txt").isFile,
            )
            assertTrue(
                "内置前缀目录不许动",
                File(assetDir(rt2, "eratw.foo"), "builtin.txt").isFile,
            )
            assertTrue("手放目录不许动", handmade.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    private companion object {
        /** 代码身份侧车的运行期身份（作者声明 id 口径，独立于磁盘 kojo 目录名）。 */
        private const val CODE_RUNTIME_ID = "kojo.codeid.fixture"
    }
}
