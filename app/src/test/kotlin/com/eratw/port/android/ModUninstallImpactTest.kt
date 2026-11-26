package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卸载影响预览的纯读数面用例（Lane uninstallpreview）。
 *
 * 只喂值、不碰 Android / 不碰盘：七类读数的先后顺序、0 与「取不到」的两种画法、
 * 数量 / 体积拼装、三语占位符与行尾干净都在这里钉死。只读扫描（`readUninstallImpactInput`）
 * 不在本文件覆盖范围——它没有独立判据，口径靠拼装器这几个用例锁住。
 */
class ModUninstallImpactTest {

    private val langs = SettingsLanguage.entries.map { SettingsStrings.of(it) }
    private val zh = langs[0]
    private val en = langs[1]
    private val ja = langs[2]

    private fun codeOf(versions: Int, bytes: Long): ModCodeRow =
        ModCodeRow("demo.mod", versions, bytes, false, false)

    /** 七类读数都「确实没有」的输入（0 而不是 null）。 */
    private fun zeroInput(): ModUninstallImpactInput = ModUninstallImpactInput(
        storage = ModStorageReadout(bytes = 0L, keys = 0),
        settingsKeys = 0,
        code = codeOf(versions = 0, bytes = 0L),
        mediaEntries = 0,
        assets = ModUninstallImpactDir(entries = 0, bytes = 0L),
        chara = ModUninstallImpactDir(entries = 0, bytes = 0L),
        packageMembers = 0,
    )

    /** 七类读数都有值的输入（体积换算要能逐字点名）。 */
    private fun sampleInput(): ModUninstallImpactInput = ModUninstallImpactInput(
        storage = ModStorageReadout(bytes = 2_048L, keys = 3),
        settingsKeys = 5,
        code = codeOf(versions = 2, bytes = 4_096L),
        mediaEntries = 4,
        assets = ModUninstallImpactDir(entries = 3, bytes = 1_024L),
        chara = ModUninstallImpactDir(entries = 2, bytes = 512L),
        packageMembers = 5,
    )

    private fun lineOf(lines: List<String>, label: String): String =
        lines.first { it.startsWith(label) }

    @Test
    fun `全空包七行齐全且逐项写无`() {
        val lines = impactSummaryLines(zh, zeroInput())
        assertEquals("首行小标题 + 七类读数行", 8, lines.size)
        assertEquals(zh.modUninstallImpactHeading, lines[0])
        assertEquals(
            ModUninstallImpactCategory.entries.toList(),
            buildModUninstallImpact(zeroInput()).map { it.category },
        )
        lines.drop(1).forEach { line ->
            assertTrue("零读数必须写「无」而不是 0：$line", line.endsWith(zh.modUninstallImpactNone))
        }
    }

    @Test
    fun `读数缺失逐项写破折号`() {
        val lines = impactSummaryLines(zh, ModUninstallImpactInput())
        assertEquals(8, lines.size)
        lines.drop(1).forEach { line ->
            assertTrue("取不到必须写「—」：$line", line.endsWith(zh.modUninstallImpactMissing))
        }
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactCode, zh.modUninstallImpactMissing),
            lines[1],
        )
    }

    @Test
    fun `零读数与取不到是两种读数`() {
        val zero = impactSummaryLines(zh, zeroInput())
        val missing = impactSummaryLines(zh, ModUninstallImpactInput())
        assertNotEquals("0 与「取不到」不许画成同一行", zero, missing)
        assertTrue(zero[1].endsWith(zh.modUninstallImpactNone))
        assertTrue(missing[1].endsWith(zh.modUninstallImpactMissing))
    }

    @Test
    fun `存档读数的字节与条目数`() {
        val lines = impactSummaryLines(
            zh,
            ModUninstallImpactInput(storage = ModStorageReadout(bytes = 2_048L, keys = 3)),
        )
        val save = lineOf(lines, zh.modUninstallImpactSave)
        assertTrue("体积口径要带上换算后的字节：$save", save.contains("2.0 KB"))
        assertTrue("条目数要出现：$save", save.contains("3"))
    }

    @Test
    fun `包设置键数`() {
        val lines = impactSummaryLines(zh, ModUninstallImpactInput(settingsKeys = 5))
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactSettings, zh.modUninstallImpactCountLine(5)),
            lineOf(lines, zh.modUninstallImpactSettings),
        )
    }

    @Test
    fun `代码版本数与体积`() {
        val lines = impactSummaryLines(zh, ModUninstallImpactInput(code = codeOf(versions = 2, bytes = 4_096L)))
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactCode, zh.modUninstallImpactCodeLine(2, "4.0 KB")),
            lineOf(lines, zh.modUninstallImpactCode),
        )
    }

    @Test
    fun `媒体清单条目数`() {
        val lines = impactSummaryLines(zh, ModUninstallImpactInput(mediaEntries = 4))
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactMedia, zh.modUninstallImpactCountLine(4)),
            lineOf(lines, zh.modUninstallImpactMedia),
        )
    }

    @Test
    fun `资源目录条目数与体积`() {
        val lines = impactSummaryLines(
            zh,
            ModUninstallImpactInput(assets = ModUninstallImpactDir(entries = 3, bytes = 1_024L)),
        )
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactAssets, zh.modUninstallImpactAssetsLine(3, "1.0 KB")),
            lineOf(lines, zh.modUninstallImpactAssets),
        )
    }

    @Test
    fun `角色模板条目数`() {
        val lines = impactSummaryLines(
            zh,
            ModUninstallImpactInput(chara = ModUninstallImpactDir(entries = 2, bytes = 512L)),
        )
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactChara, zh.modUninstallImpactCountLine(2)),
            lineOf(lines, zh.modUninstallImpactChara),
        )
    }

    @Test
    fun `包成员文件数`() {
        val lines = impactSummaryLines(zh, ModUninstallImpactInput(packageMembers = 5))
        assertEquals(
            zh.modManageDetailLine(zh.modUninstallImpactMembers, zh.modUninstallImpactCountLine(5)),
            lineOf(lines, zh.modUninstallImpactMembers),
        )
    }

    @Test
    fun `多行顺序稳定`() {
        val expected = listOf(
            ModUninstallImpactCategory.CODE,
            ModUninstallImpactCategory.SETTINGS,
            ModUninstallImpactCategory.STORAGE,
            ModUninstallImpactCategory.MEDIA,
            ModUninstallImpactCategory.ASSETS,
            ModUninstallImpactCategory.CHARA,
            ModUninstallImpactCategory.MEMBERS,
        )
        repeat(2) {
            assertEquals(expected, buildModUninstallImpact(sampleInput()).map { it.category })
        }
    }

    @Test
    fun `三语行数与占位符一致`() {
        langs.forEach { text -> assertEquals("三语都要同样八行", 8, impactSummaryLines(text, sampleInput()).size) }
        assertEquals(
            "代码：2 个版本 / 4.0 KB",
            lineOf(impactSummaryLines(zh, sampleInput()), zh.modUninstallImpactCode),
        )
        assertEquals(
            "Code: 2 version(s) / 4.0 KB",
            lineOf(impactSummaryLines(en, sampleInput()), en.modUninstallImpactCode),
        )
        assertEquals(
            "コード：2 バージョン / 4.0 KB",
            lineOf(impactSummaryLines(ja, sampleInput()), ja.modUninstallImpactCode),
        )
        langs.forEach { text ->
            val save = lineOf(impactSummaryLines(text, sampleInput()), text.modUninstallImpactSave)
            assertTrue("存档行三语都要带上体积与条目数：$save", save.contains("2.0 KB"))
            assertTrue("存档行三语都要带上条目数：$save", save.contains("3"))
        }
    }

    @Test
    fun `行尾无空白且没有空行`() {
        langs.forEach { text ->
            impactSummaryLines(text, sampleInput()).forEach { line ->
                assertTrue("行不许为空：$text", line.isNotBlank())
                assertEquals("行尾不许有空白：$line", line, line.trimEnd())
            }
        }
    }

    @Test
    fun `停用包照样能算影响`() {
        val fields = ModUninstallImpactInput::class.java.declaredFields.map { it.name }
        assertFalse(
            "输入面不许带启用态：停用包卸载删的是同一批文件",
            fields.any { name -> name.contains("enabled", ignoreCase = true) },
        )
        assertTrue(impactSummaryLines(zh, ModUninstallImpactInput(settingsKeys = 3)).size > 1)
    }
}
