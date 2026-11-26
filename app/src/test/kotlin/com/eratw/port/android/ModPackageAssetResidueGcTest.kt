package com.eratw.port.android

import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lane BH 收尾判据：`ModPackageInventory.gcOrphans` 对资源面**过程残留目录**的清理。
 *
 * 判据（与 [ModPackageInventory] 的 KDoc「资源面残留目录」段逐条对应）：
 * 1. 形状精确：`<合法 packageId>.assets.tmp-<数字>` / `.bak-<数字>` 且必须是目录；
 * 2. 同 packageId 的 `<packageId>.assets/` 还在 ⇒ 残留一律保留（在用面永不删）；
 * 3. 删除走 `deleteRecursively` + `MOD_PKG_GC` 留痕，失败不抛、WARN 原地保留；
 * 4. 既有包级孤儿判据不受影响（半成品包整套清，含其残留；只剩资源目录的包保守保留）；
 * 5. 保守时间窗：只清 `lastModified` 早于 24h 的老残留（窗内 = 可能是在途 staging，保留）。
 *
 * 全部纯 JVM：临时目录夹具，不碰 Android。
 */
class ModPackageAssetResidueGcTest {

    private fun tempRoot(): File = Files.createTempDirectory("mod-asset-residue").toFile()

    private fun mods(root: File): File = File(root, "mods").apply { mkdirs() }

    private fun rt2(root: File): File = File(root, "mods/rt2").apply { mkdirs() }

    /** 把夹具时间拨到时间窗外（24h + 1min 之前）：只有「老残留」才允许被清。 */
    private fun age(file: File): File = file.apply {
        setLastModified(System.currentTimeMillis() - ModPackageInventory.RESIDUE_GC_MIN_AGE_MILLIS - 60_000L)
    }

    /** 造一个非空目录（残留目录里通常还有写了一半的文件）；默认按「老残留」造。 */
    private fun residue(dir: File, name: String): File =
        File(dir, name).apply {
            mkdirs()
            File(this, "payload.bin").writeBytes(byteArrayOf(1, 2, 3))
            age(this)
        }

    /** 造一个在用资源面 `.assets/` 目录。 */
    private fun liveAssets(dir: File, packageId: String): File =
        File(dir, packageId + ".assets").apply {
            File(this, "img").mkdirs()
            File(this, "img/a.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50))
        }

    private fun notesFor(name: String): List<String> =
        SaveDiagnostics.snapshot().filter { it.contains("MOD_PKG_GC") && it.contains(name) }

    private fun assertAllPresent(dir: File, names: List<String>) {
        for (name in names) assertTrue("$name 应保留", File(dir, name).exists())
    }

    // ------------------------------------------------------------------
    // 1. 没有在用资源面 ⇒ tmp/bak 残留都被清掉，并逐条留痕
    // ------------------------------------------------------------------

    @Test
    fun `无在用资源面时——tmp与bak残留目录被清并留痕`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        residue(dir, "kojo-aaaa1111.assets.tmp-101")
        residue(dir, "kojo-aaaa1111.assets.bak-202")

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertFalse("tmp 残留应被清", File(dir, "kojo-aaaa1111.assets.tmp-101").exists())
        assertFalse("bak 残留应被清", File(dir, "kojo-aaaa1111.assets.bak-202").exists())
        for (name in listOf("kojo-aaaa1111.assets.tmp-101", "kojo-aaaa1111.assets.bak-202")) {
            val notes = notesFor(name)
            assertTrue("每个被清残留都要留一条 MOD_PKG_GC 痕：$notes", notes.isNotEmpty())
            assertTrue("留痕要说明是资源面残留：$notes", notes.any { it.contains("资源面残留目录已删除") })
        }
    }

    // ------------------------------------------------------------------
    // 2. 在用资源面还在 ⇒ 同名残留保守保留、活资源面一个文件都不许删
    // ------------------------------------------------------------------

    @Test
    fun `在用资源面存在时——同名残留保守保留且活资源不删`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val packageId = "kojo-bbbb2222"
        val live = liveAssets(dir, packageId)
        residue(dir, "$packageId.assets.tmp-303")
        residue(dir, "$packageId.assets.bak-404")

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertTrue("在用资源面必须保留", live.isDirectory)
        assertTrue("在用资源面的文件不许删", File(live, "img/a.png").isFile)
        assertAllPresent(dir, listOf("$packageId.assets.tmp-303", "$packageId.assets.bak-404"))
        assertTrue(
            "在用面存在时不该对残留留删除痕：${SaveDiagnostics.snapshot()}",
            notesFor("$packageId.assets.tmp-303").isEmpty() && notesFor("$packageId.assets.bak-404").isEmpty(),
        )
    }

    // ------------------------------------------------------------------
    // 3. 名字形状不匹配 ⇒ 一律不碰（安全线：宁可留垃圾也不误删作者/玩家的东西）
    // ------------------------------------------------------------------

    @Test
    fun `名字形状不匹配——目录与文件一律不误删`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val survivors = listOf(
            "foo.txt", // 普通文件
            "x.assets.", // 后缀不完整
            "eratw..assets.tmp-1", // 非 kojo- 前缀（内置/手放件）
            "handmade.assets.tmp-7", // 同上
            "kojo-cccc3333.assets.tmp-abc", // 序号非数字
            "kojo-cccc3333.assets.tmp-", // 序号为空
            "kojo-cccc3333.assets.tmp-12x", // 序号尾部混字符
            "kojo-dddd4444.assets.tmp-9", // 形状合法但只是文件、不是目录
        )
        for (name in survivors) {
            if (name.endsWith("-9")) File(dir, name).writeBytes(byteArrayOf(9)) else residue(dir, name)
        }

        ModPackageInventory.gcOrphans(dir, mods(root))

        for (name in survivors) {
            assertTrue("不匹配形状的名字不许碰：$name", File(dir, name).exists())
        }
        assertTrue("不该为不匹配名字留删除痕：${SaveDiagnostics.snapshot()}", SaveDiagnostics.snapshot().isEmpty())
    }

    // ------------------------------------------------------------------
    // 4. 与包级孤儿判据互不干扰：半成品包整套清（含其资源残留），只剩资源目录的包保守保留
    // ------------------------------------------------------------------

    @Test
    fun `包级孤儿清理照旧——半成品整套清且只剩资源目录的包保留`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        // 半成品（只有 .erbm、缺 manifest）+ 同名资源残留：包级判据先删成员，残留这一轮跟着走。
        File(dir, "kojo-eeee5555.erbm").writeBytes(byteArrayOf(9, 9, 9))
        residue(dir, "kojo-eeee5555.assets.tmp-505")
        // 只剩资源目录（没有任何包文件）：既有「保守保留」口径不许改。
        val onlyAssets = liveAssets(dir, "kojo-ffff6666")

        ModPackageInventory.gcOrphans(dir, mods(root))

        assertFalse("半成品包成员应被清", File(dir, "kojo-eeee5555.erbm").exists())
        assertFalse("半成品包的资源残留应跟着清", File(dir, "kojo-eeee5555.assets.tmp-505").exists())
        assertTrue("只剩资源目录的包必须保守保留", onlyAssets.isDirectory)
        assertTrue("只剩资源目录包里的文件不许动", File(onlyAssets, "img/a.png").isFile)
        assertTrue(
            "半成品包的资源残留要留痕：${SaveDiagnostics.snapshot()}",
            notesFor("kojo-eeee5555.assets.tmp-505").any { it.contains("资源面残留目录已删除") },
        )
    }

    // ------------------------------------------------------------------
    // 5. 删除失败不抛 + 留 WARN（平台差异容忍：删得掉就算已删除，删不掉必须留 WARN）
    // ------------------------------------------------------------------

    @Test
    fun `删除失败不抛——成功与否都留痕且 GC 不打断`() {
        SaveDiagnostics.clear()
        val root = tempRoot()
        val dir = rt2(root)
        val name = "kojo-gggg7777.assets.tmp-606"
        val residueDir = residue(dir, name)
        // Windows 上只读文件会挡住 deleteRecursively；POSIX 上可能照样删掉 ⇒ 断言取两档都成立的形状。
        File(residueDir, "locked.bin").apply {
            writeBytes(byteArrayOf(7))
            setWritable(false)
        }
        // 写文件会刷新目录 mtime：删前重新拨回时间窗外，才真正走到「删除」这一支。
        age(residueDir)

        val thrown = runCatching { ModPackageInventory.gcOrphans(dir, mods(root)) }

        assertTrue("残留清理失败不许抛：$thrown", thrown.isSuccess)
        val notes = notesFor(name)
        assertTrue("无论删没删都必须留一条 MOD_PKG_GC 痕：$notes", notes.isNotEmpty())
        if (File(dir, name).exists()) {
            assertTrue("删不掉要留 WARN 且原地保留：$notes", notes.any { it.contains("资源面残留目录删除失败") })
        }
    }
}
