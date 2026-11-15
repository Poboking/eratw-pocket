package com.eratw.port.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 随包落盘「差量同步 + 并行」的定向单测。
 *
 * 覆盖的是出包链上最容易悄悄出事的两条：① 并行与串行**逐件结果一致**（线程调度不能改字节）；
 * ② 幂等跳过只在「同一 inode / 补丁字节相同」时发生，绝不能因为 mtime 或「文件存在」就跳过。
 * 残留件清理单独测：源删了而 staged 还留着，包里就会混进旧图，这是全删改差量后的新风险面。
 */
class StagingSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun item(
        src: File,
        dst: File,
        rel: String,
        patched: ByteArray? = null,
        group: String = "CSV",
    ) = StageItem(src, dst, rel, group, patched, !src.name.contains('.'))

    @Test
    fun `并行落盘与串行落盘的逐件结果相同且字节不差`() {
        val srcDir = tmp.newFolder("src")
        val dstDir = tmp.newFolder("dst")
        val items = (0 until 24).map { i ->
            val f = File(srcDir, "f$i.csv").apply { writeText("row-$i") }
            item(f, File(dstDir, "f$i.csv"), "f$i.csv")
        }
        val serial = stageFilesParallel(items, 1)
        dstDir.deleteRecursively()
        dstDir.mkdirs()
        val parallel = stageFilesParallel(items, 8)
        assertEquals(serial.size, parallel.size)
        serial.zip(parallel).forEach { (a, b) ->
            assertEquals(a.group, b.group)
            assertEquals(a.bytes, b.bytes)
            assertEquals(a.extensionless, b.extensionless)
        }
        items.forEach { assertEquals(it.src.readText(), it.dst.readText()) }
    }

    @Test
    fun `staged 与源同一 inode 时跳过重建`() {
        val src = tmp.newFile("a.csv").apply { writeText("A") }
        val dst = File(tmp.root, "staged-a.csv")
        try {
            Files.createLink(dst.toPath(), src.toPath())
        } catch (e: Exception) {
            Assume.assumeNoException("本卷不支持硬链接，跳过 inode 判据用例", e)
        }
        val r = stageOne(item(src, dst, "a.csv"))
        assertFalse(r.linked)
        assertFalse(r.copied)
        assertEquals(1L, r.bytes)
    }

    @Test
    fun `补丁件字节相同则跳过 不同则重写`() {
        val src = tmp.newFile("rename.csv").apply { writeText("old") }
        val dst = File(tmp.root, "staged-rename.csv")
        val patched = "new".toByteArray()
        val first = stageOne(item(src, dst, "rename.csv", patched))
        assertTrue(first.copied)
        assertFalse(first.linked)
        assertEquals("new", dst.readText())
        val second = stageOne(item(src, dst, "rename.csv", patched))
        assertFalse(second.copied)
        assertFalse(second.linked)
        val third = stageOne(item(src, dst, "rename.csv", "newer".toByteArray()))
        assertTrue(third.copied)
        assertEquals("newer", dst.readText())
    }

    @Test
    fun `pruneStaged 删掉源已消失的旧件 保留期望件`() {
        val dir = tmp.newFolder("staged")
        File(dir, "keep.csv").writeText("k")
        File(dir, "stale.csv").writeText("s")
        val nested = File(dir, "sub/gone.csv").apply {
            parentFile.mkdirs()
            writeText("g")
        }
        pruneStaged(dir, setOf("keep.csv"))
        assertTrue(File(dir, "keep.csv").isFile)
        assertFalse(File(dir, "stale.csv").exists())
        assertFalse(nested.exists())
    }

    @Test
    fun `空清单不建线程池`() {
        assertTrue(stageFilesParallel(emptyList(), 8).isEmpty())
    }

    @Test
    fun `普通件首次落盘后内容逐字节相同`() {
        val src = tmp.newFile("plain.csv").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        val dst = File(tmp.root, "staged-plain.csv")
        val r = stageFilesParallel(listOf(item(src, dst, "plain.csv")), 4).single()
        assertEquals(4096L, r.bytes)
        assertTrue(r.linked || r.copied)
        assertEquals(src.readBytes().toList(), dst.readBytes().toList())
    }
}
