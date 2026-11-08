package com.eratw.port.platform.save

import com.eratw.port.core.save.SaveConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SaveExport] 的判据 —— 只钉「收集哪些文件、包内怎么排、写 zip 的往返」这几件纯 JVM 的事。
 *
 * 期望形状的出处与 [SaveImportTest] 同源：
 *   * 槽名 `save{index:00}.sav` / 全局档 `global.sav`（spec: `save-format.md` §2.1，
 *     `VariableEvaluator.cs:2018-2032`）；
 *   * 目录由 `UseSaveFolder` 决定 `sav/` 还是游戏根（spec: `Config.cs:470-481`）；
 *   * 单文件体积闸 = 导入侧 [SaveImport.MAX_SAVE_BYTES]。
 */
class SaveExportTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 本作实机口径：二进制 + 压缩 + 存档进 `sav/`（`emuera.config:51/60/74`）。 */
    private val eraConfig = SaveConfig.ERATW

    private fun root(): File = temp.newFolder("game")

    private fun write(file: File, text: String): File {
        file.parentFile?.mkdirs()
        file.writeBytes(text.toByteArray())
        return file
    }

    // -----------------------------------------------------------------
    // 收集
    // -----------------------------------------------------------------

    @Test
    fun `收集 sav 下的全部槽与全局档并统一加 sav 前缀`() {
        val root = root()
        write(File(root, "sav/save11.sav"), "十一")
        write(File(root, "sav/save02.sav"), "二")
        write(File(root, "sav/global.sav"), "全局")
        write(File(root, "sav/notes.txt"), "不是存档")
        File(root, "sav/subdir").mkdirs()

        val bundle = SaveExport.collect(root, eraConfig)

        assertEquals(
            listOf("sav/global.sav", "sav/save02.sav", "sav/save11.sav"),
            bundle.entries.map { it.name },
        )
        assertEquals("全局", bundle.entries.first().file.readText())
        assertTrue(bundle.hasGlobal)
        assertTrue(bundle.skipped.isEmpty())
    }

    @Test
    fun `不开 sav 文件夹时根目录的档也能导出`() {
        val root = root()
        write(File(root, "save05.sav"), "五")
        write(File(root, "global.sav"), "全局")

        val bundle = SaveExport.collect(root, SaveConfig(systemSaveInBinary = true))

        assertEquals(listOf("sav/global.sav", "sav/save05.sav"), bundle.entries.map { it.name })
        assertEquals("五", bundle.entries.last().file.readText())
    }

    @Test
    fun `两处目录并存时以生效目录为准且不重复`() {
        val root = root()
        write(File(root, "sav/save00.sav"), "sav 版")
        write(File(root, "save00.sav"), "根版")

        val folder = SaveExport.collect(root, eraConfig)
        assertEquals(1, folder.entries.size)
        assertEquals("sav 版", folder.entries.single().file.readText())

        val flat = SaveExport.collect(root, SaveConfig(systemSaveInBinary = true))
        assertEquals(1, flat.entries.size)
        assertEquals("根版", flat.entries.single().file.readText())
    }

    @Test
    fun `空文件与超体积的文件跳过并报出来`() {
        val root = root()
        write(File(root, "sav/save00.sav"), "")
        write(File(root, "sav/save01.sav"), "0123456789")
        write(File(root, "sav/save02.sav"), "ok")

        val bundle = SaveExport.collect(root, eraConfig, maxEntryBytes = 8)

        assertEquals(listOf("sav/save02.sav"), bundle.entries.map { it.name })
        assertEquals(listOf("save00.sav" to "是空文件", "save01.sav" to "超过体积上限"), bundle.skipped.map { it.name to it.reason })
    }

    @Test
    fun `目录不存在时交出空包不抛`() {
        val bundle = SaveExport.collect(root(), eraConfig)
        assertTrue(bundle.isEmpty)
        assertFalse(bundle.hasGlobal)
        assertTrue(bundle.skipped.isEmpty())
    }

    // -----------------------------------------------------------------
    // zip
    // -----------------------------------------------------------------

    @Test
    fun `zip 条目名与内容逐字节保持原样`() {
        val root = root()
        val save = write(File(root, "sav/save03.sav"), "第 3 日")
        val global = write(File(root, "sav/global.sav"), "全局")
        val bundle = SaveExport.collect(root, eraConfig)

        val out = ByteArrayOutputStream()
        val result = SaveExport.writeZip(out, bundle.entries)

        assertTrue(result.ok)
        assertEquals(2, result.written)
        assertEquals(null, result.failed)

        val read = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                read[entry.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("sav/global.sav", "sav/save03.sav"), read.keys.toList())
        assertTrue(read.getValue("sav/global.sav").contentEquals(global.readBytes()))
        assertTrue(read.getValue("sav/save03.sav").contentEquals(save.readBytes()))
    }

    @Test
    fun `写到一半读不动时如实报失败与卡住的条目`() {
        val root = root()
        write(File(root, "sav/save00.sav"), "零")
        val ghost = File(root, "sav/save01.sav")
        val bundle = SaveExport.Bundle(
            entries = listOf(
                SaveExport.Entry("sav/save00.sav", File(root, "sav/save00.sav")),
                SaveExport.Entry("sav/save01.sav", ghost),
            ),
            skipped = emptyList(),
        )

        val result = SaveExport.writeZip(ByteArrayOutputStream(), bundle.entries)

        assertFalse(result.ok)
        assertEquals(1, result.written)
        assertEquals("sav/save01.sav", result.failed)
    }

    @Test
    fun `输出流写不进去时返回失败`() {
        val root = root()
        write(File(root, "sav/save00.sav"), "零")
        val bundle = SaveExport.collect(root, eraConfig)
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("disk full")
            override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("disk full")
        }

        val result = SaveExport.writeZip(broken, bundle.entries)

        assertFalse(result.ok)
        assertEquals(0, result.written)
    }

    @Test
    fun `建议文件名带时间戳且是 ASCII`() {
        assertEquals(
            "eratw-saves-20261030-153012.zip",
            SaveExport.suggestName(LocalDateTime.of(2026, 10, 30, 15, 30, 12)),
        )
    }
}
