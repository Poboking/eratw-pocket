package com.eratw.port.platform.config

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.SettingsSetResult
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 语料那份**真实** `emuera.config` 的逐字节往返对拍 —— 与真机点验同一判据（BOARD M5-CONFIG 行）：
 * 「`USE KEYBOARD MACROS` YES→NO→YES 保存后，设备配置 sha256 `fb23ea9a…` 与语料原文件**逐字节相同**」。
 *
 * 钉住的四件事：
 * 1. 原文 sha256 = [CORPUS_SHA256]（语料是只读基准，变了就该红）；
 * 2. 读 → 不改值 → 写回，字节完全相同（保留未知行 / 注释 / 顺序 / 行尾 / BOM）；这份语料没有注释，
 *    但有一行标签带尾随空格（`CLIPBOARD- ANYKEY WAIT TRIGGER :NO`，spec: `ConfigData.cs:140`）——
 *    写出侧若只按读取侧 `Trim()` 匹配，会把它当未知行保留、同时把该键当缺失再追加一份，字节立刻不等；
 * 3. YES→NO 只改那一行（把期望串造出来逐字节比，而不是只看 `contains`）；
 * 4. NO→YES 回到原文（往返无损）。
 *
 * 语料路径来自环境变量 `ERATW_GAMEDIR`（AGENTS.md §5 的闸门变量）；没导出就跳过，不硬编码机器路径。
 */
class ConfigRoundTripCorpusTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 语料里的 `emuera.config`；缺失/未导变量时整类跳过。 */
    private fun corpusConfig(): File {
        val gameDir = System.getenv("ERATW_GAMEDIR")
        assumeTrue("未导出 ERATW_GAMEDIR，跳过语料对拍", !gameDir.isNullOrBlank())
        val file = File(gameDir, "emuera.config")
        assumeTrue("语料里没有 emuera.config：$file", file.isFile)
        return file
    }

    /** 把语料复制进临时目录，返回 (工作副本, 原文字节)。 */
    private fun copyInto(): Pair<File, ByteArray> {
        val original = corpusConfig().readBytes()
        val dir = temp.newFolder()
        val copy = File(dir, "emuera.config")
        copy.writeBytes(original)
        return copy to original
    }

    @Test
    fun `语料原文的 sha256 就是真机基准`() {
        val (_, original) = copyInto()
        assertEquals("BOARD M5-CONFIG 的真机基准；语料换版就必须重开对拍", CORPUS_SHA256, sha256(original))
        assertEquals(3079, original.size)
        assertArrayEquals("带 UTF-8 BOM", BOM, original.copyOfRange(0, 3))
        assertFalse("纯 LF（97 个，CRLF 0 个）", original.any { it == 0x0D.toByte() })
        assertEquals("末行带换行", 0x0A.toByte(), original.last())
    }

    @Test
    fun `读进来不改值写回 逐字节等于原文`() {
        val (copy, original) = copyInto()
        val store = SettingsStore(copy)

        val loaded = store.load()
        assertTrue(loaded.ok)
        assertTrue(loaded.fileExists)
        assertTrue("97 行全是已知键，不该有告警：${loaded.warnings.map { it.message }}", loaded.warnings.isEmpty())
        assertNull("不该在中途抛错", loaded.abortedAtLine)
        assertEquals(0, loaded.droppedLines)
        assertFalse("读盘结束即基线", loaded.model.dirty)

        val item = loaded.model.store
        assertTrue("OUTPUT ENGLISH ITEMS IN THE CONFIG FILE:YES（行 75）", loaded.model.englishConfigOutput())
        assertEquals(18, item.itemOf(ConfigKey.FontSize).int())
        assertEquals(18, item.itemOf(ConfigKey.LineHeight).int())
        assertEquals(1800, item.itemOf(ConfigKey.WindowX).int())
        assertEquals(900, item.itemOf(ConfigKey.MaxLog).int())
        assertEquals("简体中文", item.itemOf(ConfigKey.EmueraLang).string())
        assertEquals(listOf("xml", "txt", "json"), item.itemOf(ConfigKey.ValidExtension).stringList())
        assertTrue("USE KEYBOARD MACROS:YES（行 9）", item.itemOf(ConfigKey.UseKeyMacro).bool())

        assertTrue(store.save(loaded.model))
        assertFalse("写盘成功即清脏", loaded.model.dirty)
        assertArrayEquals("保留式写回必须逐字节还原", original, copy.readBytes())
        assertEquals(CORPUS_SHA256, sha256(copy.readBytes()))
    }

    @Test
    fun `USE KEYBOARD MACROS 关掉再打开 只动那一行且回到原字节`() {
        val (copy, original) = copyInto()
        val store = SettingsStore(copy)
        val model = store.load().model
        val body = String(original, BOM.size, original.size - BOM.size, Charsets.UTF_8)
        assertTrue("原文里只有一处这个键", body.contains(KEY_LINE + ":YES"))

        assertTrue(model.set("UseKeyMacro", false) is SettingsSetResult.Applied)
        assertTrue(store.save(model))
        val expectedOff = BOM + body.replace(KEY_LINE + ":YES", KEY_LINE + ":NO").toByteArray(Charsets.UTF_8)
        assertArrayEquals("关掉宏只改这一行的值，其余逐字节不变", expectedOff, copy.readBytes())

        assertTrue(model.set("UseKeyMacro", true) is SettingsSetResult.Applied)
        assertTrue(store.save(model))
        assertArrayEquals("再打开回到原文（真机 sha256 基准）", original, copy.readBytes())
        assertEquals(CORPUS_SHA256, sha256(copy.readBytes()))
    }

    companion object {
        /** BOARD「M5-CONFIG」行记的真机读数（语料 `emuera.config`，3,079 B）。 */
        const val CORPUS_SHA256: String = "FB23EA9A4C5D95C7106A930EE449698FF4C138F053DBC164C784390CCD33A55F"

        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        private const val KEY_LINE = "USE KEYBOARD MACROS"

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
                .uppercase()
    }
}
