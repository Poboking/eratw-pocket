package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-014 S5 定向判据：`<pkg>.media` 清单 + 两树旁挂资源接入「卸载删净」与「孤儿 GC」。
 *
 * 判的是三件事：只删清单内路径 / 只删空目录（止于两树根之前）；清单损坏 fail-closed 只 WARN；
 * 孤儿 GC 只清「确定无主」——完整包、包级没清成的半成品、同 `<dir>` 仍被别的包声明的，一律保留。
 */

private const val PKG_A = "kojo-aaaa1111"
private const val PKG_B = "kojo-bbbb2222"
private const val MEDIA_DIR = "demo"

private fun tempRoot(label: String): File = Files.createTempDirectory(label).toFile()

private fun rt2Of(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

private fun modsOf(root: File): File = File(root, "mods").apply { mkdirs() }

private fun putFile(root: File, relativePath: String): File = File(root, relativePath).apply {
    parentFile?.mkdirs()
    writeBytes(byteArrayOf(1, 2, 3))
}

private fun manifestText(dir: String, vararg relativePaths: String): String =
    ModImportMediaAssets.MANIFEST_VERSION + " " + ModImportMediaAssets.MANIFEST_DIR_KEY + dir + "\n" +
        relativePaths.joinToString("\n") + "\n"

private fun writeManifest(rt2: File, packageId: String, dir: String, vararg relativePaths: String) {
    File(rt2, packageId + ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX)
        .writeText(manifestText(dir, *relativePaths), Charsets.UTF_8)
}

private fun manifestOf(rt2: File, packageId: String): File =
    File(rt2, packageId + ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX)

/** 完整包成员（四件套 + 设置侧车）：让包级判据判「完整包」，也让显式卸载能返回 true。 */
private fun writePackageMembers(rt2: File, packageId: String) {
    for (name in ModPackageInventory.memberNames(packageId)) File(rt2, name).writeText("member")
    File(rt2, ModSettingsStore.memberName(packageId)).writeText("settings")
}

private fun gc(rt2: File, mods: File) = ModPackageInventory.gcOrphans(rt2, mods)

private fun hasTrace(fragment: String): Boolean =
    SaveDiagnostics.snapshot().any { it.contains("MOD_PKG_GC") && it.contains(fragment) }

class ModMediaReclaimUninstallTest {

    @Test
    fun `卸载按清单删文件与空目录且两树根保留`() {
        val root = tempRoot("mod-media-uninstall")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            putFile(root, "resources/mod/$MEDIA_DIR/img/a.png")
            putFile(root, "sound/mod/$MEDIA_DIR/se.mp3")
            writeManifest(
                rt2,
                PKG_A,
                MEDIA_DIR,
                "resources/mod/$MEDIA_DIR/img/a.png",
                "sound/mod/$MEDIA_DIR/se.mp3",
            )

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            assertFalse(File(root, "resources/mod/$MEDIA_DIR/img/a.png").exists())
            assertFalse(File(root, "sound/mod/$MEDIA_DIR/se.mp3").exists())
            assertFalse("空子目录应自底向上删掉", File(root, "resources/mod/$MEDIA_DIR/img").exists())
            assertFalse(File(root, "resources/mod/$MEDIA_DIR").exists())
            assertFalse(File(root, "sound/mod/$MEDIA_DIR").exists())
            assertTrue("resources/mod 是共享骨架不得删", File(root, "resources/mod").isDirectory)
            assertTrue("sound/mod 是共享骨架不得删", File(root, "sound/mod").isDirectory)
            assertFalse("清单本身应删除", manifestOf(rt2, PKG_A).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `清单外同目录玩家文件不删且非空目录保留`() {
        val root = tempRoot("mod-media-uninstall-keep")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            val listed = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            val player = putFile(root, "resources/mod/$MEDIA_DIR/keep.txt")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")

            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))

            assertFalse("清单内文件应删", listed.exists())
            assertTrue("清单外玩家文件不删", player.isFile)
            assertTrue("目录非空则保留", File(root, "resources/mod/$MEDIA_DIR").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `清单损坏 fail-closed 不删旁挂文件且卸载仍成功`() {
        val root = tempRoot("mod-media-uninstall-corrupt")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            val listed = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            val manifest = manifestOf(rt2, PKG_A)
            manifest.writeText("BROKEN\nresources/mod/$MEDIA_DIR/a.png\n", Charsets.UTF_8)

            assertTrue("媒体清单损坏不改变卸载结局（只 WARN）", ModManageUninstall.uninstall(rt2, PKG_A))

            assertTrue("坏清单不作删除依据：文件保留", listed.isFile)
            assertTrue("坏清单本身也保留（fail-closed）", manifest.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `非标准布局不猜 gameRoot 只 fail-closed 跳过`() {
        val root = tempRoot("mod-media-uninstall-layout")
        try {
            val rt2 = File(root, "rt2").apply { mkdirs() }
            val listed = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")

            assertFalse("非标准布局不能采信相对路径", ModMediaReclaim.uninstall(rt2, PKG_A))
            assertTrue(listed.isFile)
            assertTrue(manifestOf(rt2, PKG_A).isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `无清单与重复卸载都算删净幂等`() {
        val root = tempRoot("mod-media-uninstall-idempotent")
        try {
            val rt2 = rt2Of(root)
            assertTrue("本来就没有清单 = 已删净", ModMediaReclaim.uninstall(rt2, PKG_A))
            assertTrue("没装过也算成功", ModManageUninstall.uninstall(rt2, PKG_A))
            writePackageMembers(rt2, PKG_A)
            putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")
            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))
            assertTrue("第二次卸载仍成功", ModManageUninstall.uninstall(rt2, PKG_A))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `卸载后同 dir 复用不误删新包清单外文件`() {
        val root = tempRoot("mod-media-uninstall-reuse")
        try {
            val rt2 = rt2Of(root)
            writePackageMembers(rt2, PKG_A)
            putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")
            assertTrue(ModManageUninstall.uninstall(rt2, PKG_A))
            assertFalse("A 卸载后 <dir> 清空应删掉", File(root, "resources/mod/$MEDIA_DIR").exists())

            // 别的包复用同一 <dir>：只许删自己清单里的文件。
            writePackageMembers(rt2, PKG_B)
            val reused = putFile(root, "resources/mod/$MEDIA_DIR/b.png")
            val player = putFile(root, "resources/mod/$MEDIA_DIR/player.txt")
            writeManifest(rt2, PKG_B, MEDIA_DIR, "resources/mod/$MEDIA_DIR/b.png")
            assertTrue(ModManageUninstall.uninstall(rt2, PKG_B))

            assertFalse("B 清单内文件应删", reused.exists())
            assertTrue("复用目录里清单外文件不误删", player.isFile)
            assertTrue("resources/mod 骨架保留", File(root, "resources/mod").isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }
}

class ModMediaReclaimGcTest {

    @Test
    fun `孤儿媒体面回收并留取证行`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            val image = putFile(root, "resources/mod/$MEDIA_DIR/img/a.png")
            val sound = putFile(root, "sound/mod/$MEDIA_DIR/se.mp3")
            writeManifest(
                rt2,
                PKG_A,
                MEDIA_DIR,
                "resources/mod/$MEDIA_DIR/img/a.png",
                "sound/mod/$MEDIA_DIR/se.mp3",
            )

            gc(rt2, mods)

            assertFalse(image.exists())
            assertFalse(sound.exists())
            assertFalse(File(root, "resources/mod/$MEDIA_DIR/img").exists())
            assertFalse(File(root, "resources/mod/$MEDIA_DIR").exists())
            assertFalse(File(root, "sound/mod/$MEDIA_DIR").exists())
            assertTrue(File(root, "resources/mod").isDirectory)
            assertTrue(File(root, "sound/mod").isDirectory)
            assertFalse(manifestOf(rt2, PKG_A).exists())
            assertTrue("回收要有取证行", hasTrace("已回收"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `完整包保留媒体面不回收`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-complete")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            writePackageMembers(rt2, PKG_A)
            val image = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")

            gc(rt2, mods)

            assertTrue("完整包的媒体文件必须保留", image.isFile)
            assertTrue("完整包的清单必须保留", manifestOf(rt2, PKG_A).isFile)
            assertFalse("完整包不得留回收取证行", hasTrace("已回收"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `清单在但文件缺的半成品只取证不删`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-partial")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            val image = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(
                rt2,
                PKG_A,
                MEDIA_DIR,
                "resources/mod/$MEDIA_DIR/a.png",
                "resources/mod/$MEDIA_DIR/missing.png",
            )

            gc(rt2, mods)

            assertTrue("残缺台账不作删除依据：在盘文件保留", image.isFile)
            assertTrue("清单保留", manifestOf(rt2, PKG_A).isFile)
            assertTrue(hasTrace("半成品"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `同 dir 仍被别的包声明则保留不误删`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-claim")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            val orphan = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")
            writePackageMembers(rt2, PKG_B)
            val live = putFile(root, "resources/mod/$MEDIA_DIR/b.png")
            writeManifest(rt2, PKG_B, MEDIA_DIR, "resources/mod/$MEDIA_DIR/b.png")

            gc(rt2, mods)

            assertTrue("同 <dir> 仍被 B 声明：A 的孤儿文件保留", orphan.isFile)
            assertTrue("A 的清单保留", manifestOf(rt2, PKG_A).isFile)
            assertTrue("B 的文件不动", live.isFile)
            assertTrue(hasTrace("跨包复用"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `坏清单 GC fail-closed 只取证不删`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-corrupt")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            val image = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            manifestOf(rt2, PKG_A).writeText("garbage\nresources/mod/$MEDIA_DIR/a.png\n", Charsets.UTF_8)

            gc(rt2, mods)

            assertTrue(image.isFile)
            assertTrue(manifestOf(rt2, PKG_A).isFile)
            assertTrue(hasTrace("清单损坏"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `非 kojo 前缀媒体清单永不碰`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-handmade")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            val image = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            File(rt2, "hand.media").writeText(
                manifestText(MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png"),
                Charsets.UTF_8,
            )

            gc(rt2, mods)

            assertTrue(image.isFile)
            assertTrue(File(rt2, "hand.media").isFile)
            assertTrue("手放件零接触（不留任何 GC 取证行）", SaveDiagnostics.snapshot().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `半成品包成员先清媒体随后回收`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-halfpackage")
        try {
            val rt2 = rt2Of(root)
            val mods = modsOf(root)
            // 只有 provenance、缺容器与 manifest = 包级判据要清的半成品。
            val sources = File(rt2, PKG_A + ModPackageInventory.SOURCES_EXTENSION)
            sources.writeText("src.ERB\n", Charsets.UTF_8)
            val image = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")

            gc(rt2, mods)

            assertFalse("包级半成品成员应先清", sources.exists())
            assertFalse("包已确定无主：旁挂文件回收", image.exists())
            assertFalse(manifestOf(rt2, PKG_A).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `非标准布局 GC 不猜 gameRoot`() {
        SaveDiagnostics.clear()
        val root = tempRoot("mod-media-gc-layout")
        try {
            val rt2 = File(root, "rt2").apply { mkdirs() }
            val mods = File(root, "mods").apply { mkdirs() }
            val image = putFile(root, "resources/mod/$MEDIA_DIR/a.png")
            writeManifest(rt2, PKG_A, MEDIA_DIR, "resources/mod/$MEDIA_DIR/a.png")

            gc(rt2, mods)

            assertTrue("非标准布局不删任何东西", image.isFile)
            assertTrue(manifestOf(rt2, PKG_A).isFile)
        } finally {
            root.deleteRecursively()
        }
    }
}
