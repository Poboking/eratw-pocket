package com.eratw.port.platform.config

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ConfigFileStore] 的判据 —— 只钉**文件层**口径：BOM、行尾、原文件不被动、目录守卫。
 *
 * 期望形状的出处：
 *   * 写出编码恒为 UTF-8 with BOM：`Config.Encode = EncodingHandler.UTF8BOMEncoding`
 *     （spec: `Config.cs:17`）、`new StreamWriter(configPath, append: false, Config.Encode)`
 *     （spec: `ConfigData.cs:429`）—— 原文有没有 BOM 都一样，所以「BOM 保留」= 不丢 BOM；
 *   * 读入按 BOM 认编码：`EncodingHandler.DetectEncoding`（spec: `EncodingHandler.cs:23-25`）；
 *   * 行尾与注释/顺序的逐行保留发生在 `EmueraConfigWriter.mergeInto`，本类只保证**不整平**；
 *     原版 `StreamWriter` 写 `Environment.NewLine`（Windows 上是 CRLF），本移植按契约改为逐行保留。
 *
 * 全部用例自建临时目录（不碰语料、不硬编码机器路径）。
 */
class ConfigFileStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun storeIn(dir: File): ConfigFileStore = ConfigFileStore.inDirectory(dir)

    private fun writeRaw(file: File, bytes: ByteArray) {
        file.writeBytes(bytes)
    }

    @Test
    fun `缺失文件 load 返回 null 且 save 按空原文建文件`() {
        val dir = temp.newFolder()
        val store = storeIn(dir)
        assertNull(store.load())
        assertFalse(File(dir, "emuera.config").exists())

        assertTrue("identity merge 会把空原文原样写回（只有 BOM）", store.save { it })
        val bytes = File(dir, "emuera.config").readBytes()
        assertArrayEquals("空原文 ⇒ 只剩三字节 BOM", UTF8_BOM, bytes)
    }

    @Test
    fun `无 BOM 原文写回补上 BOM 且正文一个字节不变`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeRaw(file, "A:1\n; 注释\n".toByteArray(Charsets.UTF_8))

        val loaded = storeIn(dir).load()
        assertNotNull(loaded)
        assertFalse("原文没有 BOM", loaded!!.hasBom)
        assertEquals("A:1\n; 注释\n", loaded.text)

        assertTrue(storeIn(dir).save { it })
        assertArrayEquals(
            "原版 SaveConfig 恒写 BOM（Config.cs:17）⇒ 原文没 BOM 也会被补上",
            UTF8_BOM + "A:1\n; 注释\n".toByteArray(Charsets.UTF_8),
            file.readBytes(),
        )
    }

    @Test
    fun `有 BOM 原文的正文与 BOM 都原样`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeRaw(file, UTF8_BOM + "FONT SIZE:18\n".toByteArray(Charsets.UTF_8))

        val loaded = storeIn(dir).load()
        assertNotNull(loaded)
        assertTrue(loaded!!.hasBom)
        assertEquals("BOM 不计入首行文本", "FONT SIZE:18\n", loaded.text)

        assertTrue(storeIn(dir).save { it })
        assertArrayEquals(UTF8_BOM + "FONT SIZE:18\n".toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test
    fun `混合行尾逐行保留`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        val body = "A:1\r\nB:2\nC:3\r\n"
        writeRaw(file, UTF8_BOM + body.toByteArray(Charsets.UTF_8))
        assertEquals(
            "出现过 CRLF 就报 CRLF（诊断用，合并层才是逐行口径）",
            "\r\n",
            storeIn(dir).load()!!.lineSeparator,
        )

        assertTrue(storeIn(dir).save { it })
        assertArrayEquals(
            "逐行行尾不许被整平（契约 §2.5）",
            UTF8_BOM + body.toByteArray(Charsets.UTF_8),
            file.readBytes(),
        )
    }

    @Test
    fun `纯 LF 文件的行尾探测`() {
        val dir = temp.newFolder()
        writeRaw(File(dir, "emuera.config"), "A:1\n".toByteArray(Charsets.UTF_8))
        assertEquals("\n", storeIn(dir).load()!!.lineSeparator)
    }

    @Test
    fun `末行没有换行时也原样不补`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeRaw(file, UTF8_BOM + "A:1".toByteArray(Charsets.UTF_8))
        assertTrue(storeIn(dir).save { it })
        assertArrayEquals(UTF8_BOM + "A:1".toByteArray(Charsets.UTF_8), file.readBytes())
    }

    @Test
    fun `BOM 三字节的空文件读成空串`() {
        val dir = temp.newFolder()
        writeRaw(File(dir, "emuera.config"), UTF8_BOM)
        val loaded = storeIn(dir).load()
        assertNotNull(loaded)
        assertEquals("", loaded!!.text)
        assertTrue(loaded.hasBom)
    }

    /**
     * 目标被建成目录时，`renameReplacing` 的兜底会把整个目录改名成 `emuera.config.bak`
     * （Android 的 `rename(2)` 对目录同样成功），等于毁掉目录里的东西。
     * 原版这条路径是 `StreamWriter` 打开失败 ⇒ `SaveConfig` 返回 false（`ConfigData.cs:455-458`），
     * 效果一样：拒绝写、不动现场。
     */
    @Test
    fun `目标是目录时拒绝写且目录原样`() {
        val dir = temp.newFolder()
        val target = File(dir, "emuera.config")
        assertTrue(target.mkdir())
        val inner = File(target, "keep.txt")
        inner.writeText("不可丢")

        val store = ConfigFileStore(target)
        assertNull("load 只认普通文件", store.load())
        assertFalse("目录不是可写目标", store.save { "A:1\n" })

        assertTrue("目录还在", target.isDirectory)
        assertEquals("目录里的东西一个字节不动", "不可丢", inner.readText())
        assertFalse("不许留下 .bak", File(dir, "emuera.config.bak").exists())
    }

    @Test
    fun `merge 抛异常时不上盘且异常原样上抛`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        val original = UTF8_BOM + "A:1\n".toByteArray(Charsets.UTF_8)
        writeRaw(file, original)

        val store = storeIn(dir)
        assertThrows(IllegalStateException::class.java) {
            store.save { throw IllegalStateException("调用方的编程错误") }
        }
        assertArrayEquals("合并失败不许碰原文件", original, file.readBytes())
        val leftovers = dir.listFiles()!!.filter { it.name != "emuera.config" }
        assertTrue("临时文件必须清掉：$leftovers", leftovers.isEmpty())
    }

    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
