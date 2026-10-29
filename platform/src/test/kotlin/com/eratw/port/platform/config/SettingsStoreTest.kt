package com.eratw.port.platform.config

import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsModel
import com.eratw.port.core.config.SettingsSetResult
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [SettingsStore] 的判据 —— 只钉装配层：读什么文件、BOM / 行尾 / 原子写回沿用 [ConfigFileStore]、
 * 解析告警原样转达、失败时不动原文件。
 *
 * 期望形状的出处：
 *   * 文件名与位置 —— `Program.ExeDir + "emuera.config"`（spec: `ConfigData.cs:33`）；
 *   * 写出编码 —— `Config.Encode` = UTF-8 with BOM（spec: `Config.cs:17`、`ConfigData.cs:429`）；
 *   * 解析失败只记告警并中止本文件剩余行（spec: `ConfigData.cs:579-586`）；
 *   * 缺失文件的处理 —— 原版先按缺省跑、随后补写一份（spec: `ConfigData.cs:496-508`）；
 *     本层只如实报 `fileExists = false`，补写时机交调用方。
 *
 * 全部用例自建临时目录（不碰语料、不硬编码机器路径）。
 */
class SettingsStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun writeSample(file: File, text: String, bom: Boolean = true) {
        val out = ByteArrayOutputStream()
        if (bom) {
            out.write(UTF8_BOM)
        }
        out.write(text.toByteArray(Charsets.UTF_8))
        file.writeBytes(out.toByteArray())
    }

    private fun readSample(file: File): String {
        val bytes = file.readBytes()
        assertTrue("写回必须带 UTF-8 BOM（ConfigData.cs:429）", bytes.size > 3)
        assertArrayEquals(UTF8_BOM, bytes.copyOfRange(0, 3))
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }

    @Test
    fun `游戏根形态指向 emuera-config`() {
        val dir = temp.newFolder()
        val store = SettingsStore.inDirectory(dir)
        assertEquals(ConfigFileStore.FILE_NAME, store.file.name)
        assertEquals(File(dir, "emuera.config").absoluteFile, store.file.absoluteFile)
    }

    @Test
    fun `无文件时读出缺省模型且 fileExists 为 false`() {
        val store = SettingsStore.inDirectory(temp.newFolder())
        val loaded = store.load()

        assertTrue(loaded.ok)
        assertFalse("文件不存在（ConfigData.cs:514-522 的 File.Exists 守卫）", loaded.fileExists)
        assertTrue(loaded.warnings.isEmpty())
        assertNull(loaded.abortedAtLine)
        assertEquals("缺省表的值（ConfigData.cs:66）", 18, loaded.model.store.itemOf(ConfigKey.FontSize).int())
        assertFalse("刚读进来的状态就是基线", loaded.model.dirty)
    }

    @Test
    fun `首次写回建文件 带 BOM 且整份配置每行带行尾`() {
        val dir = temp.newFolder()
        val store = SettingsStore.inDirectory(dir)
        val model = SettingsModel()
        assertTrue(model.set("FontSize", "22") is SettingsSetResult.Applied)

        assertTrue(store.save(model))
        val file = File(dir, "emuera.config")
        assertTrue(file.isFile)
        val text = readSample(file)
        // english=false（缺省 ConfigData.cs:129）⇒ 日文标签；configArray 第一条
        assertTrue("首行是 configArray 第一条的日文标签", text.startsWith("大文字小文字の違いを無視する:YES\n"))
        assertTrue("写回的是模型当前值", text.contains("フォントサイズ:22\n"))
        assertTrue("SaveConfig 的 WriteLine 让末行也带行尾（ConfigData.cs:451）", text.endsWith("\n"))
        assertFalse("无原文可循时用 LF", text.replace("\n", "").contains('\r'))
        assertFalse("写盘成功即清脏", model.dirty)
    }

    @Test
    fun `BOM 被剥掉 首行键照样命中`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeSample(file, "IGNORE CASE:NO\nFONT SIZE:22\n", bom = true)

        val loaded = SettingsStore.inDirectory(dir).load()
        assertTrue(loaded.ok)
        assertTrue(loaded.fileExists)
        assertFalse(
            "首字符是 U+FEFF 的话这一行会匹配不上（ConfigFile.stripBom 的职责）",
            loaded.model.store.itemOf(ConfigKey.IgnoreCase).bool(),
        )
        assertEquals(22, loaded.model.store.itemOf(ConfigKey.FontSize).int())
        assertTrue(loaded.warnings.isEmpty())
    }

    @Test
    fun `往返 改值写回后再读回同一值 且注释与未知键保留`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeSample(file, "; 顶注释\r\nCUSTOM KEY:keep\r\nFONT SIZE:18\r\n; 尾注释\r\n")
        val store = SettingsStore.inDirectory(dir)

        val first = store.load()
        assertTrue(first.ok)
        assertEquals(18, first.model.store.itemOf(ConfigKey.FontSize).int())
        assertFalse(first.model.dirty)

        assertTrue(first.model.set("FontSize", "22") is SettingsSetResult.Applied)
        assertTrue(first.model.dirty)
        assertTrue(store.save(first.model))
        assertFalse("成功写回后基线前移", first.model.dirty)

        val text = readSample(file)
        val lines = text.split("\r\n")
        assertEquals("注释原样（ConfigData.cs:529-532）", "; 顶注释", lines[0])
        assertEquals("未知键原样", "CUSTOM KEY:keep", lines[1])
        assertEquals("已知键原位替换；english=false ⇒ 日文标签（ConfigItem.cs:86）", "フォントサイズ:22", lines[2])
        assertEquals("顺序不变", "; 尾注释", lines[3])
        assertTrue("原文件以 CRLF 结尾，追加块也以 CRLF 结尾", text.endsWith("\r\n"))
        assertFalse("逐行保留行尾 ⇒ 不出现裸 LF", text.replace("\r\n", "").contains('\n'))

        val second = store.load()
        assertTrue(second.ok)
        assertEquals("往返后读回改过的值", 22, second.model.store.itemOf(ConfigKey.FontSize).int())
        assertFalse(second.model.dirty)
    }

    @Test
    fun `英文标签开关现算 写回换成英文标签`() {
        val dir = temp.newFolder()
        val store = SettingsStore.inDirectory(dir)
        val created = store.load()
        assertFalse(created.fileExists)
        assertTrue(store.save(created.model))

        val loaded = store.load()
        assertTrue(loaded.fileExists)
        assertFalse("缺省 NO（ConfigData.cs:129）", loaded.model.englishConfigOutput())
        assertTrue(loaded.model.set("EnglishConfigOutput", true) is SettingsSetResult.Applied)
        assertTrue(store.save(loaded.model))

        val text = readSample(File(dir, "emuera.config"))
        assertTrue("下一轮写回立刻换英文标签（ConfigItem.cs:86）", text.contains("IGNORE CASE:YES"))
        assertTrue(text.contains("FONT SIZE:18"))
        assertTrue(text.contains("OUTPUT ENGLISH ITEMS IN THE CONFIG FILE:YES"))
    }

    @Test
    fun `坏行只告警并中止本文件剩余行 原版口径`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeSample(file, "FONT SIZE:abc\nWINDOW WIDTH:800\n")
        val store = SettingsStore.inDirectory(dir)

        val loaded = store.load()
        assertTrue("解析失败不算读盘失败", loaded.ok)
        assertEquals("首个抛错行（1 基）", 1, loaded.abortedAtLine)
        assertEquals("该行之后的行全部没读（ConfigData.cs:524-586）", 1, loaded.droppedLines)
        assertEquals(1, loaded.warnings.size)
        assertEquals("数字でない文字が含まれています", loaded.warnings[0].message) // Lang.cs:962
        assertEquals(ConfigKey.FontSize, loaded.warnings[0].configKey)
        assertEquals(1, loaded.warnings[0].lineNo)
        assertEquals("坏行不改值", 18, loaded.model.store.itemOf(ConfigKey.FontSize).int())
        assertEquals("后续行被丢弃", 760, loaded.model.store.itemOf(ConfigKey.WindowX).int())
    }

    @Test
    fun `非法 UTF-8 读失败转达且拒绝写回`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        val garbage = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x41)
        file.writeBytes(garbage)
        val store = SettingsStore(file)

        val loaded = store.load()
        assertFalse(loaded.ok)
        assertNotNull(loaded.error)
        assertTrue("能抛解码错说明文件在读之前是存在的", loaded.fileExists)
        assertEquals("失败时模型保持原样", 18, loaded.model.store.itemOf(ConfigKey.FontSize).int())

        assertFalse("解码失败不写回（ConfigFileStore.save 的守卫）", store.save(loaded.model))
        assertArrayEquals("原文件一个字节都不许动", garbage, file.readBytes())
    }

    @Test
    fun `load 可以叠在装载链已读好的模型上`() {
        val dir = temp.newFolder()
        val file = File(dir, "emuera.config")
        writeSample(file, "WINDOW WIDTH:1024\n")
        val store = SettingsStore.inDirectory(dir)

        // 装配层先把 default.config / fixed.config 读进同一份模型（ConfigData.cs:481-493 的顺序），
        // 再用本类叠 emuera.config 那一层。
        val base = SettingsModel()
        base.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(800))
        assertTrue(base.store.itemOf(ConfigKey.WindowY).assign(ConfigValue.IntValue(700)))

        val loaded = store.load(base)
        assertTrue("解析结果落进调用方给的模型", loaded.model === base)
        assertEquals("emuera.config 覆盖前一层", 1024, base.store.itemOf(ConfigKey.WindowX).int())
        assertEquals("文件里没有的键保留前一层", 700, base.store.itemOf(ConfigKey.WindowY).int())
        assertFalse("load 结束时把当前状态定为基线", base.dirty)
    }

    companion object {
        private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
