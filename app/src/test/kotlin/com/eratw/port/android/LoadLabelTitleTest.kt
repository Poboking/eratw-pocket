package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CONFIG-WIRE U4：装载等待屏的 LOADLABEL（`Process.cs:277-281`）。
 *
 * 原版语义两条：`!Config.DisplayReport` 才打这一行（另一半是装载报表）；
 * `LoadLabel` 是 REPLACE 组键（`ConfigData.cs:161`，缺省 "Now Loading..."），
 * 只从 `CSV/_Replace.csv` 生效（`Process.cs:240-246` → `ConfigData.cs:594-639`）——
 * 4.981 语料 `_Replace.csv:14` 就把它覆盖成报错指引文案。
 */
class LoadLabelTitleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fallback = "壳层回落文案"

    private fun gameRoot(): File = tmp.newFolder("game")

    private fun writeConfig(root: File, vararg lines: String) {
        File(root, "emuera.config").writeText(lines.joinToString("\n"), Charsets.UTF_8)
    }

    private fun writeReplace(root: File, text: String) {
        File(root, "CSV").mkdirs()
        File(root, "CSV/_Replace.csv").writeText(text, Charsets.UTF_8)
    }

    @Test
    fun `default is Now Loading without any config`() {
        assertEquals("Now Loading...", loadLabelTitle(gameRoot(), fallback))
    }

    @Test
    fun `replace layer overrides load label`() {
        val root = gameRoot()
        writeReplace(root, "起動時簡略表示 , 如果出现了错误，请根据目录下的报错指导文件进行报错\n")
        assertEquals(
            "如果出现了错误，请根据目录下的报错指导文件进行报错",
            loadLabelTitle(root, fallback),
        )
    }

    @Test
    fun `display report on uses shell fallback`() {
        val root = gameRoot()
        writeConfig(root, "Display loading report:YES")
        writeReplace(root, "起動時簡略表示 , 不该显示\n")
        assertEquals(fallback, loadLabelTitle(root, fallback))
    }

    @Test
    fun `use replace file off ignores replace layer`() {
        val root = gameRoot()
        writeConfig(root, "Use _Replace.csv file:NO")
        writeReplace(root, "起動時簡略表示 , 不该显示\n")
        assertEquals("Now Loading...", loadLabelTitle(root, fallback))
    }

    @Test
    fun `missing replace file keeps default`() {
        val root = gameRoot()
        writeConfig(root, "Display loading report:NO")
        assertEquals("Now Loading...", loadLabelTitle(root, fallback))
    }

    @Test
    fun `non directory game root keeps default instead of blocking boot`() {
        // 指向普通文件：三层链读不到任何层（EraConfigChain 对缺失层是 ABSENT 不是异常），
        // 于是读数是缺省 "Now Loading..."；关键在于不抛错、不拦住启动。
        val notADir = tmp.newFile("not-a-dir")
        assertEquals("Now Loading...", loadLabelTitle(notADir, fallback))
    }
}
