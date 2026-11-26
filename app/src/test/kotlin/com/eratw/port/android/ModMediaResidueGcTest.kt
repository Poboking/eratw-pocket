package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1-002 判据：`ModPackageInventory.gcOrphans` 对**旁挂媒体过程残留**
 * （`<pkg>.media.tmp-<数字>`；目录 = ④″ 暂存树、文件 = 清单原子写临时件）的清理。
 *
 * 判据（与 [ModPackageInventory] KDoc「旁挂媒体暂存残留」段逐条对应）：
 * 1. 形状精确：`<合法 kojo- packageId>.media.tmp-<全数字序号>`；序号非数字/为空、
 *    非 `kojo-` 前缀、正式 `<pkg>.media` 清单一律不碰；
 * 2. 在用面永不删：同 packageId 的 `<pkg>.media` 清单文件在场 ⇒ 残留保守保留
 *    （清单是媒体面的正式件，在途替换不删；正式清单能留在场上的前提是包成员在场，
 *    否则孤儿媒体回收面会先收走清单）；
 * 3. 保守时间窗：只清 `lastModified` 早于 24h 的老残留（与资源面/设置面同一把窗）；
 * 4. 删除走 `MOD_PKG_GC` 留痕；失败不抛、WARN 原地保留。
 *
 * 全部纯 JVM：临时目录夹具，不碰 Android。
 */
class ModMediaResidueGcTest {

    private fun tempRoot(): File = Files.createTempDirectory("mod-media-residue").toFile()

    private fun mods(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    /** 把夹具时间拨到时间窗外（24h + 1min 之前）：只有「老残留」才允许被清。 */
    private fun age(file: File): File = file.apply {
        setLastModified(System.currentTimeMillis() - ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS - 60_000L)
    }

    /** 造一个超龄媒体暂存**目录**（写了一半的暂存树）。 */
    private fun residueDir(dir: File, name: String): File = File(dir, name).apply {
        mkdirs()
        File(this, "resources/mod/kojo/a.png").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1, 2))
        age(this)
    }

    /** 造一个超龄媒体清单原子写残留**文件**。 */
    private fun residueFile(dir: File, name: String): File = File(dir, name).apply {
        writeText("RT2MEDIA/1 dir=kojo\n")
        age(this)
    }

    /** 造一个完整包（容器 + manifest）：包级孤儿判据与媒体回收面都会保留它。 */
    private fun fullPackage(dir: File, packageId: String) {
        File(dir, "$packageId.erbm").writeBytes(byteArrayOf(1))
        File(dir, "$packageId.manifest").writeBytes(byteArrayOf(2))
    }

    private fun notesFor(name: String): List<String> =
        SaveDiagnostics.snapshot().filter { it.contains("MOD_PKG_GC") && it.contains(name) }

    // ------------------------------------------------------------------
    // 1. 主案：进程杀在 ④″ stage 与 ⑤″ commit 之间（无在用清单）⇒ 超龄暂存树被清并留痕
    // ------------------------------------------------------------------

    @Test
    fun `崩溃残留——无在用清单时超龄媒体tmp目录被清并留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = "kojo-a1b2c3d4.media.tmp-853887813638200"
        residueDir(dir, name)

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertFalse("崩溃残留的媒体暂存树应被清", File(dir, name).exists())
        val notes = notesFor(name)
        assertTrue("被清残留要留一条 MOD_PKG_GC 痕：$notes", notes.isNotEmpty())
        assertTrue("留痕要点名媒体面目录：$notes", notes.any { it.contains("媒体面残留目录已删除") })
    }

    // ------------------------------------------------------------------
    // 2. 在用面：`.media` 清单在场（完整包）⇒ 残留保守保留、正式清单不动
    // ------------------------------------------------------------------

    @Test
    fun `在用清单在场——超龄媒体残留保守保留`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val packageId = "kojo-b2c3d4e5"
        fullPackage(dir, packageId)
        val manifest = File(dir, "$packageId.media")
            .apply { writeText("RT2MEDIA/1 dir=kojo\nresources/mod/kojo/a.png\n") }
        residueDir(dir, "$packageId.media.tmp-101")
        residueFile(dir, "$packageId.media.tmp-202")

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("正式 .media 清单不许动", manifest.isFile)
        assertTrue("在用面在场时目录残留不许删", File(dir, "$packageId.media.tmp-101").isDirectory)
        assertTrue("在用面在场时文件残留不许删", File(dir, "$packageId.media.tmp-202").isFile)
        assertTrue(
            "在用面在场时不得留删除痕：${SaveDiagnostics.snapshot()}",
            notesFor("$packageId.media.tmp-101").isEmpty() && notesFor("$packageId.media.tmp-202").isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 3. 清单原子写残留（同名字形的**文件**）⇒ 超龄且无在用清单时被清并留痕
    // ------------------------------------------------------------------

    @Test
    fun `清单原子写残留——同形状文件超龄且无在用清单时被清并留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = "kojo-a7b8c9d0.media.tmp-998877665544"
        residueFile(dir, name)

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertFalse("清单原子写崩溃残留应被清", File(dir, name).exists())
        val notes = notesFor(name)
        assertTrue("被清残留要留痕：$notes", notes.any { it.contains("媒体面残留文件已删除") })
    }

    // ------------------------------------------------------------------
    // 4. 窗内 = 可能是在途 staging ⇒ 保守保留、不留痕
    // ------------------------------------------------------------------

    @Test
    fun `窗内暂存树保守保留——可能是在途staging`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = "kojo-c3d4e5f6.media.tmp-303"
        File(dir, name).apply {
            mkdirs()
            File(this, "resources/mod/kojo/a.png").apply { parentFile.mkdirs() }.writeBytes(byteArrayOf(1))
        }

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("窗内残留可能是在途 staging，不许删", File(dir, name).isDirectory)
        assertTrue("窗内保留不该留删除痕：${SaveDiagnostics.snapshot()}", notesFor(name).isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. 形状不匹配 ⇒ 一律不碰（宁留垃圾不误删玩家/作者的东西）
    // ------------------------------------------------------------------

    @Test
    fun `形状不匹配——一律不碰`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val survivors = listOf(
            "kojo-d4e5f6a7.media.tmp-abc", // 序号非数字
            "kojo-d4e5f6a7.media.tmp-", // 序号为空
            "kojo-d4e5f6a7.media.tmp", // 没有 tmp- 序号段
            "kojo-d4e5f6a7.media.tmp-12x", // 序号尾部混字符
            "eratw..media.tmp-1", // 非 kojo- 前缀（内置/手放件）
            "handmade.media.tmp-7", // 同上
        )
        for (name in survivors) residueDir(dir, name)
        // 正式 `<pkg>.media` 清单（无 tmp 段）：摆齐包成员让两级回收面都保留它，只看残留判据不误碰。
        // 清单要带至少一行合法路径：零路径清单会被 ModMediaReclaim 判「损坏」留取证行，与本判据无关。
        fullPackage(dir, "kojo-e5f6a7b8")
        val manifest = File(dir, "kojo-e5f6a7b8.media")
            .apply { writeText("RT2MEDIA/1 dir=kojo\nresources/mod/kojo/a.png\n") }

        ModPackageInventory.gcOrphans(dir, mods(root))

        for (name in survivors) {
            assertTrue("不匹配形状的名字不许碰：$name", File(dir, name).exists())
        }
        assertTrue("正式媒体清单不许被残留清理碰", manifest.isFile)
        assertTrue(
            "不该为不匹配名字留删除痕：${SaveDiagnostics.snapshot()}",
            SaveDiagnostics.snapshot().isEmpty(),
        )
    }
}
