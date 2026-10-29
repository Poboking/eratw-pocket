package com.eratw.port.platform.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

/**
 * [MacroFile] 的判据：文件缺失/吞异常（`KeyMacro.cs:92-95`、`:135-137`）、
 * 编码优先级与 CP932 口径（`EncodingHandler.cs:15-41`、`:67-71`）、「解不动 = 打不开文件」
 * （`EraStreamReader.cs:50-59`）、行拆分（`File.ReadAllLines`）、
 * 以及行内容交给 [com.eratw.port.render.input.KeyMacroTable.loadLines] 后的槽位落点
 * （`KeyMacro.cs:99-133`；行格式见 `docs/golden-runbook.md:106`）。
 */
class MacroFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `缺文件返回 null`() {
        assertNull(MacroFile.load(File(tmp.root, "absent.txt")))
    }

    @Test
    fun `UTF-8 读入宏与组名`() {
        val file = tmp.newFile()
        file.writeBytes(
            (
                "グループ3:第三组\r\n" +
                    "宏快捷键F1:20\r\n" +
                    "G3:宏快捷键F2:yes\r\n"
                ).toByteArray(Charsets.UTF_8),
        )
        val table = MacroFile.load(file)
        assertNotNull(table)
        assertEquals("组 0 的 F1", "20", table!!.get(0, 0))
        assertEquals("组行 Japanese 前缀 + 中文组名", "第三组", table.groupName(3))
        assertEquals("组 3 的 F2（G3:宏快捷键F2:）", "yes", table.get(1, 3))
    }

    @Test
    fun `UTF-8 BOM 被剥离`() {
        val file = tmp.newFile()
        val body = "宏快捷键F5:値".toByteArray(Charsets.UTF_8)
        file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body)
        val table = MacroFile.load(file)
        assertNotNull(table)
        assertEquals("首行没有 U+FEFF 前缀，F5 能命中", "値", table!!.get(4, 0))
    }

    @Test
    fun `非法 UTF-8 回落 CP932`() {
        val bytes = "グループ0:あい".toByteArray(Charset.forName("windows-31j"))
        assertEquals("グループ0:あい", MacroFile.decode(bytes))
    }

    /**
     * 原版 `GetEncoding(932)`（`EncodingHandler.cs:11`、`:67-71`）是 **CP932**，
     * 不是 JDK 的 `Shift_JIS`：两者在 NEC 扩展行（0x87xx）与 0x815C 上分岔。
     * 这一条钉死「用 CP932 解」，Temurin 17 实测 `Shift_JIS` 会给出 U+FFFD / U+2014。
     */
    @Test
    fun `回落用的是 CP932 而不是 JDK 的 Shift_JIS`() {
        assertEquals(
            "0x8740 ⇒ U+2460（①，NEC 扩展行；JDK Shift_JIS 解不动）",
            "\u2460",
            MacroFile.decode(byteArrayOf(0x87.toByte(), 0x40)),
        )
        assertEquals(
            "0x815C ⇒ U+2015（CP932；JDK Shift_JIS 给 U+2014）",
            "\u2015",
            MacroFile.decode(byteArrayOf(0x81.toByte(), 0x5C.toByte())),
        )
    }

    /**
     * CP932 文件的行内容必须按 CP932 解出来（0x87 0x40 的 ① 是 NEC 扩展行，
     * JDK `Shift_JIS` 解不动）。注意宏名/组名默认串是**界面语言**的产物
     * （`KeyMacro.cs:38-57` 的 `ResetNames`）：日文前缀写出的文件在简中界面下
     * 匹配不上宏槽，只有写死的「グループ」组行不受影响 —— 原版也是这个行为。
     */
    @Test
    fun `CP932 的组行与宏行按界面语言各自落位`() {
        val text = "グループ0:テスト\u2460\r\nマクロキーF1:あ\u2460\r\n"
        val file = tmp.newFile()
        file.writeBytes(text.toByteArray(Charset.forName("windows-31j")))

        val decoded = MacroFile.decode(file.readBytes())
        assertNotNull(decoded)
        assertEquals(
            listOf("グループ0:テスト\u2460", "マクロキーF1:あ\u2460"),
            MacroFile.splitLines(decoded!!),
        )

        val table = MacroFile.load(file)
        assertNotNull(table)
        assertEquals("组行前缀写死日文，与界面语言无关（KeyMacro.cs:13、:105）", "テスト\u2460", table!!.groupName(0))
        assertEquals(
            "日文宏名在简中界面下匹配不上（KeyMacro.cs:38-57 按当前语言重建名字）",
            "",
            table.get(0, 0),
        )
    }

    /**
     * BOM 已把编码钉在 `UTF8BOMEncoding`（`EncodingHandler.cs:13`、`:23-26`），
     * 坏字节让 `File.ReadAllLines` 抛、`EraStreamReader.Open` 返回 false
     * （`EraStreamReader.cs:50-59`）⇒ `LoadMacroFile` 直接 return（`KeyMacro.cs:92-95`），
     * **不会**再回落日文编码。
     */
    @Test
    fun `BOM 文件里的坏 UTF-8 视为打不开文件`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x87.toByte(), 0x40)
        assertNull(MacroFile.decode(bytes))
        val file = tmp.newFile()
        file.writeBytes(bytes)
        assertNull("原版 Open 失败 ⇒ 一个槽都不装", MacroFile.load(file))
    }

    @Test
    fun `两种编码都解不动视为打不开文件`() {
        // 孤立的双字节前导字节 0x81：UTF-8 非法，CP932 也不完整（DecoderFallback 抛）。
        assertNull(MacroFile.decode(byteArrayOf(0x81.toByte())))
        val file = tmp.newFile()
        file.writeBytes(byteArrayOf(0x81.toByte()))
        assertNull(MacroFile.load(file))
    }

    /**
     * 原版没有「Tab 分隔」：整行 = 名称前缀 + 余下**全文**（`KeyMacro.cs:123-129` 的 `Substring`），
     * 所以 Tab 只是宏内容的一部分；而用 Tab 顶替冒号的组行会被 `text[5] != ':'` 挡掉
     * （`:111-115`），组名保持缺省。
     */
    @Test
    fun `Tab 不是分隔符 只是宏内容`() {
        val file = tmp.newFile()
        file.writeBytes(
            "宏快捷键F1:\t带制表符\r\nグループ0\t不是组行\r\n".toByteArray(Charsets.UTF_8),
        )
        val table = MacroFile.load(file)
        assertNotNull(table)
        assertEquals("Tab 属于宏内容，原样保留", "\t带制表符", table!!.get(0, 0))
        assertEquals("Tab 顶替冒号 ⇒ 组行整条跳过", "设置为按键宏组0", table.groupName(0))
    }

    @Test
    fun `末尾换行不产生额外空行`() {
        assertEquals(listOf("a", "b"), MacroFile.splitLines("a\r\nb\n"))
        assertEquals(listOf("a", "", "b"), MacroFile.splitLines("a\n\nb"))
        assertEquals(listOf("a"), MacroFile.splitLines("a\r"))
    }
}
