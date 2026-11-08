package com.eratw.port.platform.storage

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ResourcePackStore] 的纯 JVM 覆盖：条目名归一 / 落盘边界 / 现状读数 / 包级画像 / 结构体检。
 *
 * 判据来源：4.981 语料的读取口径（`resources/` 递归扫 CSV，`sound/` 是 PLAYSOUND
 * 的根，音乐补丁按 `sound/游戏bgm/<地点>/<n>.MP3` 取曲），以及设置页要上屏的
 * 「已导入什么」读数。路径安全用例是硬闸：素材包来自玩家，`..` / 盘符 / 隐藏段
 * 都不许落到游戏根之外。
 */
class ResourcePackStoreTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun zipOf(entries: Map<String, String>): File {
        val file = File(tempDir("pack"), "pack.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun sevenZipOf(entries: Map<String, String>): File {
        val file = File(tempDir("pack7"), "pack.7z")
        SevenZOutputFile(file).use { seven ->
            for ((name, content) in entries) {
                val bytes = content.toByteArray()
                val entry = SevenZArchiveEntry().apply {
                    this.name = name
                    size = bytes.size.toLong()
                }
                seven.putArchiveEntry(entry)
                seven.write(bytes)
                seven.closeArchiveEntry()
            }
        }
        return file
    }

    // ------------------------------------------------------------------
    // 条目名归一
    // ------------------------------------------------------------------

    @Test
    fun `标准目录与包装目录都归一到游戏根`() {
        assertEquals(
            "resources/背景图/通用浴室_0_0.webp",
            ResourcePackStore.normalizeEntry("resources/背景图/通用浴室_0_0.webp"),
        )
        assertEquals(
            "resources/背景图/通用浴室_0_0.webp",
            ResourcePackStore.normalizeEntry("eratw-bgi-master/resources/背景图/通用浴室_0_0.webp"),
        )
        assertEquals(
            "sound/游戏bgm/博丽神社/0.MP3",
            ResourcePackStore.normalizeEntry("sound/游戏bgm/博丽神社/0.MP3"),
        )
        // Windows 打包器用反斜杠
        assertEquals(
            "sound/游戏bgm/博丽神社/1.MP3",
            ResourcePackStore.normalizeEntry("sound\\游戏bgm\\博丽神社\\1.MP3"),
        )
        // 顶层根大小写宽松（Windows 上写的包），子目录原样保留
        assertEquals("resources/Sub/A.PNG", ResourcePackStore.normalizeEntry("Resources/Sub/A.PNG"))
    }

    @Test
    fun `越界与垃圾条目一律拒收`() {
        assertNull(ResourcePackStore.normalizeEntry("../resources/a.png"))
        assertNull(ResourcePackStore.normalizeEntry("resources/../../a.png"))
        assertNull(ResourcePackStore.normalizeEntry("C:/resources/a.png"))
        assertNull(ResourcePackStore.normalizeEntry("__MACOSX/resources/a.png"))
        assertNull(ResourcePackStore.normalizeEntry("resources/.hidden/a.png"))
        assertNull(ResourcePackStore.normalizeEntry("resources/a.png/"))
        assertNull(ResourcePackStore.normalizeEntry("resources"))
        assertNull(ResourcePackStore.normalizeEntry("readme.txt"))
        assertNull(ResourcePackStore.normalizeEntry(""))
        // 编码没解对：U+FFFD 条目不许投进乱码目录
        assertNull(ResourcePackStore.normalizeEntry("resources/\uFFFD\uFFFD/a.webp"))
    }

    // ------------------------------------------------------------------
    // 落盘
    // ------------------------------------------------------------------

    @Test
    fun `只投放 resources 与 sound 下的条目并点名跳过项`() {
        val root = tempDir("game")
        val pack = zipOf(
            linkedMapOf(
                "wrapper/resources/背景图/通用浴室_0_0.webp" to "IMG",
                "wrapper/resources/背景图/list.csv" to "通用浴室_0_0,通用浴室_0_0.webp\n",
                "wrapper/sound/游戏bgm/博丽神社/0.MP3" to "AUDIO",
                "wrapper/readme.txt" to "not a resource",
                "__MACOSX/resources/背景图/._通用浴室_0_0.webp" to "meta",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(3, report.installedFiles)
        assertEquals(2, report.skippedEntries)
        assertEquals(listOf("resources", "sound"), report.roots)
        assertEquals(2, report.skippedSamples.size)
        assertEquals("IMG", File(root, "resources/背景图/通用浴室_0_0.webp").readText())
        assertEquals("AUDIO", File(root, "sound/游戏bgm/博丽神社/0.MP3").readText())
        assertFalse(File(root, "readme.txt").exists())
        assertFalse(File(root, "__MACOSX").exists())
    }

    @Test
    fun `7z 背景图包与 zip 同口径导入`() {
        val root = tempDir("game")
        val pack = sevenZipOf(
            linkedMapOf(
                "resources/背景图/BGI.csv" to "通用浴室_0_0,00/通用浴室_0_0.webp\n",
                "resources/背景图/00/通用浴室_0_0.webp" to "IMG",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.STANDARD, report.profile)
        assertEquals(2, report.installedFiles)
        assertEquals(listOf("resources"), report.roots)
        assertEquals(
            "通用浴室_0_0,00/通用浴室_0_0.webp\n",
            File(root, "resources/背景图/BGI.csv").readText(),
        )
        assertEquals("IMG", File(root, "resources/背景图/00/通用浴室_0_0.webp").readText())
    }

    @Test
    fun `7z 游戏bgm 包（带外包裹层）落到 sound 下`() {
        val root = tempDir("game")
        val pack = sevenZipOf(
            linkedMapOf(
                "TW背景音乐/游戏bgm/博丽神社/上午/0.mp3" to "AUDIO",
                "TW背景音乐/游戏bgm/博丽神社/上午/bgm" to "",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.BGM, report.profile)
        // 0 字节 `bgm` 是官方包的目录占位，不是曲目：不落盘、也不算「跳过」，
        // 只折成一条非素材读数（否则 126 个占位会把正常导入读成失败）。
        assertEquals(1, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertEquals("AUDIO", File(root, "sound/游戏bgm/博丽神社/上午/0.mp3").readText())
        assertFalse(File(root, "sound/游戏bgm/博丽神社/上午/bgm").exists())
        val ignored = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.MATERIAL_IGNORED_FILES
        }
        assertNotNull(ignored)
        assertEquals("1", ignored!!.detail)
    }

    @Test
    fun `官方 BGM 包形态：说明 txt 与占位折成一条读数不落盘`() {
        val root = tempDir("game")
        val pack = sevenZipOf(
            linkedMapOf(
                "游戏bgm/博丽神社/上午/0.mp3" to "AUDIO",
                "游戏bgm/博丽神社/上午/bgm" to "",
                "游戏bgm/博丽神社/下午/bgm" to "",
                "游戏bgm/使用说明.txt" to "README",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.BGM, report.profile)
        assertEquals(1, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertEquals(listOf("sound"), report.roots)
        assertFalse(File(root, "sound/游戏bgm/使用说明.txt").exists())
        assertFalse(File(root, "sound/游戏bgm/博丽神社/下午").exists())
        // 三个非素材文件合并成一条，detail 是十进制个数
        val ignored = report.structureNotes.filter {
            it.kind == StructureNoteKind.MATERIAL_IGNORED_FILES
        }
        assertEquals(1, ignored.size)
        assertEquals("3", ignored.single().detail)
    }

    @Test
    fun `背景图画像包不做非素材过滤（只有 BGM 画像包折叠）`() {
        val root = tempDir("game")
        val pack = zipOf(
            linkedMapOf(
                "BGI_Resource-main/BGI.csv" to "a,00/a.webp\n",
                "BGI_Resource-main/00/a.webp" to "IMG",
                "BGI_Resource-main/readme.txt" to "hi",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        // BACKGROUND_IMAGES 的逐条投放是既有行为（混装包的路由见
        // ResourcePackStoreShapeTest 的 TODO）；这里钉住「只有 BGM 画像包折叠非素材」，
        // 免得以后把背景图包的 readme 也悄悄改口径。
        assertEquals(PackProfile.BACKGROUND_IMAGES, report.profile)
        assertEquals(3, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertTrue(File(root, "resources/背景图/readme.txt").isFile)
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.MATERIAL_IGNORED_FILES })
    }

    @Test
    fun `既不是 zip 也不是 7z 的包明确报格式不支持`() {
        val bad = File(tempDir("bad"), "pack.zip").apply { writeText("not an archive") }

        val report = ResourcePackStore(tempDir("game")).install(bad)

        assertEquals(PackProfile.UNRECOGNIZED, report.profile)
        assertEquals(0, report.installedFiles)
        assertNotNull(report.error)
        assertTrue(report.error.orEmpty().contains("不支持的压缩包格式"))
    }

    @Test
    fun `重复导入计为覆盖且内容替换完整`() {
        val root = tempDir("game")
        val first = zipOf(mapOf("resources/背景图/a.webp" to "OLD-LONGER"))
        ResourcePackStore(root).install(first)
        val second = zipOf(mapOf("resources/背景图/a.webp" to "NEW"))
        val report = ResourcePackStore(root).install(second)

        assertEquals(1, report.installedFiles)
        assertEquals(1, report.overwrittenFiles)
        assertEquals("NEW", File(root, "resources/背景图/a.webp").readText())
    }

    @Test
    fun `包内同名临时后缀文件不会被落盘临时文件吃掉`() {
        val root = tempDir("game")
        // 先处理 `a.webp.import-tmp`，再处理 `a.webp`：旧实现在落后者时会截断前者再改名，
        // 把玩家包里的真实文件抹掉。临时名带纳秒后缀后两条都保留。
        val pack = zipOf(
            linkedMapOf(
                "resources/背景图/a.webp.import-tmp" to "REAL-FILE",
                "resources/背景图/a.webp" to "IMAGE",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(2, report.installedFiles)
        assertEquals("REAL-FILE", File(root, "resources/背景图/a.webp.import-tmp").readText())
        assertEquals("IMAGE", File(root, "resources/背景图/a.webp").readText())
    }

    @Test
    fun `GBK 条目名的压缩包按原目录投放`() {
        val root = tempDir("game")
        val file = File(tempDir("gbk"), "pack.zip")
        // Windows 打包器常见形态：非 ASCII 条目名按 OEM 代码页写、不置 UTF-8 标志。
        ZipOutputStream(file.outputStream(), Charset.forName("GBK")).use { zip ->
            zip.putNextEntry(ZipEntry("resources/背景图/a.webp"))
            zip.write("IMG".toByteArray())
            zip.closeEntry()
        }

        val report = ResourcePackStore(root).install(file)

        assertNull(report.error)
        assertEquals(1, report.installedFiles)
        assertEquals("IMG", File(root, "resources/背景图/a.webp").readText())
    }

    @Test
    fun `坏压缩包折成报告错误不上抛`() {
        val root = tempDir("game")
        val bad = File(tempDir("bad"), "bad.zip").apply { writeText("not a zip") }
        val report = ResourcePackStore(root).install(bad)

        assertNotNull(report.error)
        assertEquals(0, report.installedFiles)
    }

    // ------------------------------------------------------------------
    // 现状读数
    // ------------------------------------------------------------------

    @Test
    fun `背景图与 BGM 读数按语料口径统计`() {
        val root = tempDir("game")
        File(root, "resources/背景图").mkdirs()
        File(root, "resources/背景图/a.webp").writeText("x")
        File(root, "resources/背景图/b.PNG").writeText("x")
        File(root, "resources/背景图/list.csv").writeText(
            "; comment\n通用浴室_0_0,a.webp\n博丽神社_4_1,b.PNG\n\n",
        )
        File(root, "sound/游戏bgm/博丽神社").mkdirs()
        File(root, "sound/游戏bgm/博丽神社/0.MP3").writeText("x")
        File(root, "sound/游戏bgm/博丽神社/1.mp3").writeText("x")
        File(root, "sound/游戏bgm/红魔馆/上午").mkdirs()
        File(root, "sound/游戏bgm/红魔馆/上午/0.wav").writeText("x")
        File(root, "sound/游戏bgm/红魔馆/下午").mkdirs()
        File(root, "sound/游戏bgm/红魔馆/下午/1.wav").writeText("x")
        File(root, "sound/游戏bgm/根目录曲.mp3").writeText("x")
        File(root, "sound/other").mkdirs()
        File(root, "sound/other/无视.mp3").writeText("x")

        val status = ResourcePackStore.scanStatus(root)

        assertEquals(2, status.backgroundImages)
        assertEquals(2, status.backgroundIndexRows)
        assertTrue(status.backgroundReady)
        assertEquals(5, status.bgmTracks)
        // 上午/下午 是地点下的时间段目录，不能把一个地点数成两个
        assertEquals(2, status.bgmFolders)
        assertTrue(status.bgmReady)
    }

    @Test
    fun `空游戏根读出全零且不抛`() {
        val status = ResourcePackStore.scanStatus(tempDir("empty"))

        assertEquals(0, status.backgroundImages)
        assertEquals(0, status.backgroundIndexRows)
        assertEquals(0, status.bgmTracks)
        assertEquals(0, status.bgmFolders)
        assertFalse(status.backgroundReady)
        assertFalse(status.bgmReady)
    }

    // ------------------------------------------------------------------
    // 包级画像：官方背景图包 / 裸 BGM 目录 / 裸背景图目录 / 认不出
    // ------------------------------------------------------------------

    @Test
    fun `官方背景图包按包级画像投到 resources 背景图`() {
        val root = tempDir("game")
        // 官方 BGI_Resource 包形态：根级 BGI.csv + 编号子目录，GitHub 下载还多一层包裹目录。
        val pack = zipOf(
            linkedMapOf(
                "BGI_Resource-main/BGI.csv" to "通用浴室_0_0,00/通用/通用浴室_0_0.webp\n",
                "BGI_Resource-main/00/通用/通用浴室_0_0.webp" to "IMG",
                "BGI_Resource-main/11/通用/仓库_0_0.webp" to "IMG2",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.BACKGROUND_IMAGES, report.profile)
        assertEquals(3, report.installedFiles)
        assertEquals(listOf("resources"), report.roots)
        assertEquals("IMG", File(root, "resources/背景图/00/通用/通用浴室_0_0.webp").readText())
        assertEquals("IMG2", File(root, "resources/背景图/11/通用/仓库_0_0.webp").readText())
        // BGI.csv 落在索引目录根（scanStatus 靠它数行）
        assertTrue(File(root, "resources/背景图/BGI.csv").isFile)
        // 有 BGI.csv 就是有索引，不许误报「图片无索引」
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX })
    }

    @Test
    fun `裸游戏bgm目录整体投到 sound 下`() {
        val root = tempDir("game")
        val pack = zipOf(mapOf("游戏bgm/博丽神社/0.MP3" to "AUDIO"))

        val report = ResourcePackStore(root).install(pack)

        assertEquals(PackProfile.BGM, report.profile)
        assertEquals(1, report.installedFiles)
        assertEquals(listOf("sound"), report.roots)
        assertEquals("AUDIO", File(root, "sound/游戏bgm/博丽神社/0.MP3").readText())
        // 落点已经在 游戏bgm 下，不该报「BGM 在 游戏bgm 之外」
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.BGM_OUTSIDE_GAME_BGM })
    }

    @Test
    fun `裸背景图目录与根级 BGI csv 都投到 resources 背景图并剥掉重复一层`() {
        val root = tempDir("game")
        val pack = zipOf(
            linkedMapOf(
                "背景图/BGI.csv" to "a,背景图/a.webp\n",
                "背景图/a.webp" to "IMG",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertEquals(PackProfile.BACKGROUND_IMAGES, report.profile)
        assertEquals(2, report.installedFiles)
        assertEquals("IMG", File(root, "resources/背景图/a.webp").readText())
        assertEquals("a,背景图/a.webp\n", File(root, "resources/背景图/BGI.csv").readText())
    }

    @Test
    fun `认不出结构的包全跳过并点名 UNRECOGNIZED_LAYOUT`() {
        val root = tempDir("game")
        val pack = zipOf(linkedMapOf("readme.txt" to "hi", "docs/manual.md" to "x"))

        val report = ResourcePackStore(root).install(pack)

        assertNull(report.error)
        assertEquals(PackProfile.UNRECOGNIZED, report.profile)
        assertEquals(0, report.installedFiles)
        assertEquals(2, report.skippedEntries)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.UNRECOGNIZED_LAYOUT
        }
        assertNotNull(note)
        assertTrue(note!!.detail.contains("readme.txt"))
        assertFalse(File(root, "readme.txt").exists())
    }

    // ------------------------------------------------------------------
    // 结构体检告警
    // ------------------------------------------------------------------

    @Test
    fun `sound 下不在 游戏bgm 的音频落盘成功但报 BGM_OUTSIDE_GAME_BGM`() {
        val root = tempDir("game")
        val pack = zipOf(mapOf("sound/某地/0.mp3" to "AUDIO"))

        val report = ResourcePackStore(root).install(pack)

        assertEquals(PackProfile.STANDARD, report.profile)
        assertEquals(1, report.installedFiles)
        assertEquals("AUDIO", File(root, "sound/某地/0.mp3").readText())
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.BGM_OUTSIDE_GAME_BGM
        }
        assertNotNull(note)
        assertEquals("sound/某地/0.mp3", note!!.detail)
    }

    @Test
    fun `背景图有图无 CSV 索引时报 IMAGES_WITHOUT_CSV_INDEX`() {
        val root = tempDir("game")
        val pack = zipOf(mapOf("resources/背景图/x.webp" to "IMG"))

        val report = ResourcePackStore(root).install(pack)

        assertEquals(1, report.installedFiles)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX
        }
        assertNotNull(note)
        assertEquals("resources/背景图", note!!.detail)
    }

    @Test
    fun `0 字节 CSV 报 EMPTY_CSV_INDEX 且不重复报无索引`() {
        val root = tempDir("game")
        val pack = zipOf(
            linkedMapOf(
                "resources/背景图/list.csv" to "",
                "resources/背景图/a.webp" to "IMG",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        assertEquals(2, report.installedFiles)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.EMPTY_CSV_INDEX
        }
        assertNotNull(note)
        assertEquals("resources/背景图/list.csv", note!!.detail)
        // 有 CSV（哪怕是空表）就不再报「无索引」，两个告警不重复
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX })
    }

    @Test
    fun `新画像下越界绝对路径与隐藏条目仍拒收`() {
        val root = tempDir("game")
        val pack = zipOf(
            linkedMapOf(
                "wrap/BGI.csv" to "a,a.webp\n",
                "wrap/00/../evil.webp" to "EVIL",
                "wrap/.hidden/a.webp" to "HIDDEN",
                "wrap/__MACOSX/背景图/x.webp" to "META",
                "/背景图/abs.webp" to "ABS",
                "wrap/00/正常.webp" to "IMG",
            ),
        )

        val report = ResourcePackStore(root).install(pack)

        // 包裹层照剥、画像照认，但安全闸一条都不放松
        assertEquals(PackProfile.BACKGROUND_IMAGES, report.profile)
        assertEquals(2, report.installedFiles)
        assertEquals(4, report.skippedEntries)
        assertEquals("IMG", File(root, "resources/背景图/00/正常.webp").readText())
        assertFalse(File(root, "resources/背景图/evil.webp").exists())
        assertFalse(File(root, "resources/背景图/.hidden").exists())
        assertFalse(File(root, "resources/背景图/__MACOSX").exists())
        assertFalse(File(root, "resources/背景图/abs.webp").exists())
    }
}
