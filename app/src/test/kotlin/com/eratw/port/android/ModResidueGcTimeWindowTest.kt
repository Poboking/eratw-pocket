package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * task-102 判据（B）：过程残留清理的**保守时间窗**。
 *
 * 两个清理面必须同口径（同常量 [ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS] = 24h）：
 * 1. `ModSettingsStore.gcTempResidues`（`.<pkg>.settings.tmp` 文件）；
 * 2. `ModPackageInventory.gcStagingResidues`（`<pkg>.assets.tmp-<nano>` / `.bak-<nano>` 目录，
 *    以及 M1-002 收编的 `<pkg>.media.tmp-<nano>` 媒体面残留，旁挂媒体另见 ModMediaResidueGcTest）。
 *
 * 时间窗是为了不误删**在途** tmp（写 tmp → 尚未 rename 时启动 GC），不是为了删得更干净：
 * 窗内 / `lastModified` 不可读 / 未来时间戳 ⇒ 一律保留；只有早于 24h 的遗留才允许清。
 *
 * 全部纯 JVM：临时目录夹具 + `setLastModified`（无 `Thread.sleep`）。
 */
class ModResidueGcTimeWindowTest {

    private val window = ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS

    private fun tempRoot(): File = Files.createTempDirectory("mod-residue-window").toFile()

    private fun mods(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    /** 拨到时间窗外（24h + 1min 之前）。 */
    private fun age(file: File): File = file.apply {
        setLastModified(System.currentTimeMillis() - window - 60_000L)
    }

    private fun settingsTmpName(packageId: String): String = ModSettingsStore.tempMemberName(packageId)

    private fun assetResidue(dir: File, name: String): File =
        File(dir, name).apply {
            mkdirs()
            File(this, "payload.bin").writeBytes(byteArrayOf(1, 2, 3))
        }

    private fun notesContaining(name: String): List<String> =
        SaveDiagnostics.snapshot().filter { it.contains("MOD_PKG_GC") && it.contains(name) }

    // ------------------------------------------------------------------
    // 设置面：窗内保留 / 窗外删除
    // ------------------------------------------------------------------

    @Test
    fun `设置残留——窗内新写tmp保守保留且不留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = settingsTmpName("kojo-1a2b3c4d")
        File(dir, name).writeText("half-written")

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("窗内（可能是在途写）的 tmp 不许删：$name", File(dir, name).isFile)
        assertTrue("窗内保留不该留删除痕：${SaveDiagnostics.snapshot()}", notesContaining(name).isEmpty())
    }

    @Test
    fun `设置残留——超过24h的tmp被清并逐条留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = settingsTmpName("kojo-2b3c4d5e")
        age(File(dir, name).apply { writeText("half-written") })

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertFalse("超过时间窗的 tmp 残留应删除：$name", File(dir, name).exists())
        val notes = notesContaining(name)
        assertTrue("每个被清的设置残留都要留一条 MOD_PKG_GC 痕：$notes", notes.isNotEmpty())
        assertTrue("留痕要说明是设置过程残留：$notes", notes.any { it.contains("设置过程残留文件已删除") })
    }

    // ------------------------------------------------------------------
    // 资源面：同一把时间窗，同一口径
    // ------------------------------------------------------------------

    @Test
    fun `资源残留——窗内的新staging目录保守保留`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = "kojo-3c4d5e6f.assets.tmp-101"
        assetResidue(dir, name)

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("窗内 staging 目录不许删：$name", File(dir, name).isDirectory)
        assertTrue("窗内保留不该留删除痕：${SaveDiagnostics.snapshot()}", notesContaining(name).isEmpty())
    }

    @Test
    fun `资源残留——超过24h的tmp与bak都被清并留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val tmp = "kojo-4d5e6f70.assets.tmp-202"
        val bak = "kojo-4d5e6f70.assets.bak-303"
        age(assetResidue(dir, tmp))
        age(assetResidue(dir, bak))

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertFalse("超过时间窗的 tmp 残留应删除：$tmp", File(dir, tmp).exists())
        assertFalse("超过时间窗的 bak 残留应删除：$bak", File(dir, bak).exists())
        for (name in listOf(tmp, bak)) {
            val notes = notesContaining(name)
            assertTrue("每个被清资源残留都要留一条 MOD_PKG_GC 痕：$name / $notes", notes.isNotEmpty())
            assertTrue("留痕要说明是资源面残留：$notes", notes.any { it.contains("资源面残留目录已删除") })
        }
    }

    // ------------------------------------------------------------------
    // 时间不可读 / 未来时间戳 / 边界
    // ------------------------------------------------------------------

    @Test
    fun `时间不可读——lastModified为0一律保守保留`() {
        val now = System.currentTimeMillis()
        // 不存在的文件 lastModified() = 0（= 取不到时间）⇒ 不许判可清。
        val ghost = File(tempRoot(), "ghost.settings.tmp")
        assertFalse("不存在/时间读不到的文件不许判可清", ModPackageInventory.residuePastGcWindow(ghost, now))
        // 已被别处删掉的目录（路径不再存在）同样 lastModified()=0。
        val goneDir = File(tempRoot(), "gone-dir").apply { mkdirs() }
        val goneFile = File(goneDir, "x.tmp").apply { writeText("x") }
        goneDir.deleteRecursively()
        assertFalse("父目录已消失 ⇒ 时间读不到，不许判可清", ModPackageInventory.residuePastGcWindow(goneFile, now))
    }

    @Test
    fun `未来时间戳——时钟回拨也按不够旧保留`() {
        val now = System.currentTimeMillis()
        val file = File(tempRoot(), "future.tmp").apply { writeText("x") }
        file.setLastModified(now + 3_600_000L)
        assertFalse("未来时间戳（now - mtime 为负）不许判可清", ModPackageInventory.residuePastGcWindow(file, now))
    }

    @Test
    fun `时间窗边界——窗内1h保留_窗外1h可清`() {
        val now = System.currentTimeMillis()
        val fresh = File(tempRoot(), "fresh.tmp").apply {
            writeText("x")
            setLastModified(now - window + 3_600_000L)
        }
        val old = File(tempRoot(), "old.tmp").apply {
            writeText("x")
            setLastModified(now - window - 3_600_000L)
        }
        assertFalse("窗内 1h 也要保留", ModPackageInventory.residuePastGcWindow(fresh, now))
        assertTrue("窗外 1h 判可清", ModPackageInventory.residuePastGcWindow(old, now))
    }

    // ------------------------------------------------------------------
    // 主判据方向不变：正式件在场 ⇒ 无论多老都不动
    // ------------------------------------------------------------------

    @Test
    fun `正式件存在时——超龄tmp仍按主判据保留`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val packageId = "kojo-5e6f7081"
        val live = settingsTmpName(packageId)
        // 正式设置文件在场：即便 tmp 已超龄，也可能正处替换写窗口，主判据（活配置优先）先挡。
        ModSettingsStore.settingsFile(dir, packageId).writeText("a=1\n")
        age(File(dir, live).apply { writeText("half-written") })

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("有正式设置文件时超龄 tmp 也不许删：$live", File(dir, live).isFile)
        assertTrue("这种情况不得留删除痕：${SaveDiagnostics.snapshot()}", notesContaining(live).isEmpty())
    }

    @Test
    fun `在用资源面存在时——超龄资源残留仍按主判据保留`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val packageId = "kojo-6f708192"
        val name = "$packageId.assets.tmp-404"
        File(dir, "$packageId.assets").apply {
            mkdirs()
            File(this, "img").mkdirs()
            File(this, "img/a.png").writeBytes(byteArrayOf(1))
        }
        age(assetResidue(dir, name))

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("在用资源面在场时超龄残留也不许删：$name", File(dir, name).isDirectory)
        assertTrue("这种情况不得留删除痕：${SaveDiagnostics.snapshot()}", notesContaining(name).isEmpty())
    }
}
