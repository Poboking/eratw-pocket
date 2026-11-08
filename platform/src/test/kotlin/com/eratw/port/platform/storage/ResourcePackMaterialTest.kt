package com.eratw.port.platform.storage

import com.eratw.port.core.host.ResourceContents
import com.eratw.port.core.host.ResourceFileHost
import com.eratw.port.core.host.SoundResourcePaths
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.image.PngImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手机端「直接选文件 / 选文件夹」的投放回归：不经过 zip / 7z。
 *
 * 三条主线：
 * 1. 背景图：目录判定（带 `resources/背景图` 层的树 vs 裸图片目录）、散图自动生成
 *    `自动索引.csv`（含子目录相对路径）、二次导入合并去重；
 * 2. BGM：选中地点目录直接落地、`0-9` 自动编号、`上午/下午/晚上/战斗BGM` 子目录保留、
 *    集合目录（首段是地点）不改名、0-9 满额时点名跳过；
 * 3. 接缝：自动索引真的能被 `ResourceContents` 装进资源表；自动编号后的 `0.mp3`
 *    真能被 `SoundResourcePaths` 按 `0.MP3` 解析到。
 */
class ResourcePackMaterialTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun write(root: File, path: String, content: String = "BYTES"): File {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun memorySource(label: String, files: Map<String, String>): MaterialSource =
        object : MaterialSource {
            override val label: String = label
            override fun entries(): List<MaterialEntry> = files.map { (name, content) ->
                val bytes = content.toByteArray(Charsets.UTF_8)
                MaterialEntry(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
            }
        }

    /** 只判「文件在盘上就返回 100x100」的假宿主，与 `ResourcePackEngineLinkTest` 同口径。 */
    private class FakeHost(private val gameRoot: File) : ResourceFileHost {
        override fun listCsvFiles(): List<String> {
            val root = File(gameRoot, ResourcePackStore.RESOURCES_DIR)
            if (!root.isDirectory) return emptyList()
            val base = gameRoot.absolutePath
            return root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
                .map { it.absolutePath.removePrefix(base).trimStart('/', '\\').replace('\\', '/') }
                .toList()
        }

        override fun readBytes(path: String): ByteArray? =
            File(gameRoot, path).takeIf { it.isFile }?.readBytes()

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? =
            if (File(gameRoot, path).isFile) intArrayOf(100, 100) else null

        override fun decode(path: String, bytes: ByteArray): PngImage? = null
    }

    // ------------------------------------------------------------------
    // 目录判定
    // ------------------------------------------------------------------

    @Test
    fun `目录判定按目标与层级分档`() {
        assertEquals(
            MaterialLayout.BACKGROUND_TREE,
            ResourcePackStore.detectMaterialLayout(
                listOf("resources/背景图/00/a.webp", "resources/背景图/BGI.csv"),
                MaterialTarget.BACKGROUND,
            ),
        )
        assertEquals(
            MaterialLayout.BACKGROUND_FLAT,
            ResourcePackStore.detectMaterialLayout(
                listOf("神社鸟居_0_0.webp", "list.csv"),
                MaterialTarget.BACKGROUND,
            ),
        )
        assertEquals(
            MaterialLayout.UNSUPPORTED_FILES,
            ResourcePackStore.detectMaterialLayout(listOf("readme.txt"), MaterialTarget.BACKGROUND),
        )
        assertEquals(
            MaterialLayout.BGM_TREE,
            ResourcePackStore.detectMaterialLayout(
                listOf("sound/游戏bgm/博丽神社/0.MP3"),
                MaterialTarget.BGM,
            ),
        )
        assertEquals(
            MaterialLayout.BGM_TREE,
            ResourcePackStore.detectMaterialLayout(
                listOf("博丽神社/0.mp3", "守矢神社/1.mp3"),
                MaterialTarget.BGM,
            ),
        )
        assertEquals(
            MaterialLayout.BGM_LOCATION,
            ResourcePackStore.detectMaterialLayout(
                listOf("0.mp3", "上午/1.mp3", "战斗BGM/2.mp3"),
                MaterialTarget.BGM,
            ),
        )
        assertEquals(
            MaterialLayout.UNSUPPORTED_FILES,
            ResourcePackStore.detectMaterialLayout(listOf("a.ogg"), MaterialTarget.BGM),
        )
        assertEquals(
            MaterialLayout.EMPTY,
            ResourcePackStore.detectMaterialLayout(emptyList(), MaterialTarget.BGM),
        )
    }

    // ------------------------------------------------------------------
    // 背景图
    // ------------------------------------------------------------------

    @Test
    fun `带 resources 背景图 层的目录整体剥层投放且沿用包内 CSV`() {
        val root = tempDir("material-game-tree")
        val source = tempDir("material-src-tree")
        write(source, "resources/背景图/00/a.webp", "IMG-A")
        write(source, "resources/背景图/BGI.csv", "通用浴室_0_0,00/a.webp\n")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BACKGROUND)

        assertNull(report.error)
        assertEquals(PackProfile.FOLDER, report.profile)
        assertEquals(MaterialLayout.BACKGROUND_TREE, report.layout)
        assertEquals(2, report.installedFiles)
        assertEquals(listOf("resources"), report.roots)
        assertNull(report.generatedIndex)
        assertTrue(File(root, "resources/背景图/00/a.webp").isFile)
        assertTrue(File(root, "resources/背景图/BGI.csv").isFile)
        assertFalse(File(root, "resources/背景图/自动索引.csv").exists())
    }

    @Test
    fun `散图自动建索引并带子目录相对路径，ResourceContents 真能登记`() {
        val root = tempDir("material-game-loose")
        val source = tempDir("material-src-loose")
        write(source, "神社鸟居_0_0.webp", "IMG-1")
        write(source, "通用/仓库_0_0.png", "IMG-2")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BACKGROUND)

        assertNull(report.error)
        assertEquals(MaterialLayout.BACKGROUND_FLAT, report.layout)
        assertEquals("resources/背景图/自动索引.csv", report.generatedIndex)
        // 两张图 + 自动索引一行文件
        assertEquals(3, report.installedFiles)
        val index = File(root, "resources/背景图/自动索引.csv").readText()
        assertTrue(index, index.contains("神社鸟居_0_0,神社鸟居_0_0.webp"))
        assertTrue(index, index.contains("仓库_0_0,通用/仓库_0_0.png"))

        val pool = SpritePool()
        val loaded = ResourceContents.load(pool, FakeHost(root))
        assertEquals(1, loaded.fileCount)
        assertEquals(2, loaded.spriteCount)
        assertTrue("不该有告警：${loaded.warnings}", loaded.warnings.isEmpty())
        val sprite = pool.find("神社鸟居_0_0")
        assertNotNull(sprite)
        assertEquals("resources/背景图/神社鸟居_0_0.webp", sprite!!.buffer!!.fileSource)
        assertNotNull(pool.find("仓库_0_0"))
    }

    @Test
    fun `二次散图导入合并索引且不重复登记已有名字`() {
        val root = tempDir("material-game-merge")
        val first = tempDir("material-src-merge-a")
        write(first, "神社鸟居_0_0.webp", "IMG-1")
        ResourcePackStore(root).installFolder(first, MaterialTarget.BACKGROUND)

        val second = tempDir("material-src-merge-b")
        write(second, "神社鸟居_0_0.webp", "IMG-1-NEW")
        write(second, "仓库_0_0.png", "IMG-2")
        val report = ResourcePackStore(root).installFolder(second, MaterialTarget.BACKGROUND)

        assertNull(report.error)
        val lines = File(root, "resources/背景图/自动索引.csv").readLines()
            .filter { it.isNotBlank() }
        assertEquals("同名只留一行，新图追加一行", 2, lines.size)
        assertEquals(1, lines.count { it.startsWith("神社鸟居_0_0,") })
        assertTrue(lines.any { it.startsWith("仓库_0_0,") })
        assertEquals("IMG-1-NEW", File(root, "resources/背景图/神社鸟居_0_0.webp").readText())
    }

    @Test
    fun `已有其他 CSV 登记同名图片时不再写自动索引`() {
        val root = tempDir("material-game-other-csv")
        val source = tempDir("material-src-other-csv")
        write(source, "神社鸟居_0_0.webp", "IMG")
        write(source, "手写索引.csv", "神社鸟居_0_0,神社鸟居_0_0.webp\n")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BACKGROUND)

        assertNull(report.error)
        // 源里自带 CSV ⇒ 不生成自动索引
        assertNull(report.generatedIndex)
        assertFalse(File(root, "resources/背景图/自动索引.csv").exists())
        assertFalse(report.structureNotes.any { it.kind == StructureNoteKind.IMAGES_WITHOUT_CSV_INDEX })
    }

    @Test
    fun `背景图卡里选到音频目录时点名没有可用文件`() {
        val root = tempDir("material-game-wrong")
        val source = tempDir("material-src-wrong")
        write(source, "song.mp3", "AUDIO")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BACKGROUND)

        assertEquals(0, report.installedFiles)
        assertEquals(MaterialLayout.UNSUPPORTED_FILES, report.layout)
        assertTrue(File(root, "resources").listFiles().isNullOrEmpty())
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.MATERIAL_NO_USABLE_FILES
        }
        assertNotNull(note)
    }

    // ------------------------------------------------------------------
    // BGM
    // ------------------------------------------------------------------

    @Test
    fun `选中地点目录时保留目录名并把散落音频编号成 0-9`() {
        val root = tempDir("material-game-bgm-loc")
        val source = File(tempDir("material-src-bgm-loc"), "博丽神社")
        write(source, "0.mp3", "A0")
        write(source, "theme.mp3", "A1")
        write(source, "上午/morning.mp3", "AM")
        write(source, "战斗BGM/battle.mp3", "BT")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BGM)

        assertNull(report.error)
        assertEquals(MaterialLayout.BGM_LOCATION, report.layout)
        assertEquals(4, report.installedFiles)
        assertEquals(3, report.autoRenamed)
        assertEquals(listOf("sound"), report.roots)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/0.mp3").isFile)
        assertEquals("A1", File(root, "sound/游戏bgm/博丽神社/1.mp3").readText())
        assertTrue(File(root, "sound/游戏bgm/博丽神社/上午/0.mp3").isFile)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/战斗BGM/0.mp3").isFile)
    }

    @Test
    fun `带 sound 游戏bgm 层的目录按原层级投放`() {
        val root = tempDir("material-game-bgm-tree")
        val source = tempDir("material-src-bgm-tree")
        write(source, "sound/游戏bgm/博丽神社/0.MP3", "A0")
        write(source, "sound/游戏bgm/博丽神社/上午/2.MP3", "A2")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BGM)

        assertNull(report.error)
        assertEquals(MaterialLayout.BGM_TREE, report.layout)
        assertEquals(2, report.installedFiles)
        assertEquals(0, report.autoRenamed)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/0.MP3").isFile)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/上午/2.MP3").isFile)
        assertEquals(
            File(root, "sound/游戏bgm/博丽神社/0.MP3").canonicalPath,
            SoundResourcePaths.resolve(File(root, "sound"), "游戏bgm/博丽神社/0.MP3")!!.canonicalPath,
        )
    }

    @Test
    fun `集合目录首段就是地点名时不再套一层父目录`() {
        val root = tempDir("material-game-bgm-collection")
        val source = tempDir("material-src-bgm-collection")
        write(source, "博丽神社/0.mp3", "A0")
        write(source, "守矢神社/1.mp3", "B1")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BGM)

        assertNull(report.error)
        assertEquals(MaterialLayout.BGM_TREE, report.layout)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/0.mp3").isFile)
        assertTrue(File(root, "sound/游戏bgm/守矢神社/1.mp3").isFile)
    }

    @Test
    fun `单文件导入按卡里填的地点名落地并自动编号`() {
        val root = tempDir("material-game-bgm-file")
        val report = ResourcePackStore(root).installSource(
            memorySource("博丽神社", mapOf("theme.mp3" to "A0")),
            MaterialTarget.BGM,
        )

        assertNull(report.error)
        assertEquals(MaterialLayout.BGM_LOCATION, report.layout)
        assertEquals(1, report.autoRenamed)
        assertEquals("A0", File(root, "sound/游戏bgm/博丽神社/0.mp3").readText())
        assertEquals(
            File(root, "sound/游戏bgm/博丽神社/0.mp3").canonicalPath,
            SoundResourcePaths.resolve(File(root, "sound"), "游戏bgm/博丽神社/0.MP3")!!.canonicalPath,
        )
    }

    @Test
    fun `没有地点名时放进兜底目录并点名`() {
        val root = tempDir("material-game-bgm-fallback")
        val report = ResourcePackStore(root).installSource(
            memorySource("游戏bgm", mapOf("theme.mp3" to "A0")),
            MaterialTarget.BGM,
        )

        assertNull(report.error)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.BGM_FALLBACK_LOCATION
        }
        assertNotNull(note)
        assertEquals("自定义", note!!.detail)
        assertTrue(File(root, "sound/游戏bgm/自定义/0.mp3").isFile)
    }

    @Test
    fun `地点目录已有 0-9 共 10 首时多出的被跳过并点名`() {
        val root = tempDir("material-game-bgm-full")
        for (i in 0..9) write(root, "sound/游戏bgm/博丽神社/$i.mp3", "OLD-$i")
        val source = File(tempDir("material-src-bgm-full"), "博丽神社")
        write(source, "new.mp3", "NEW")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BGM)

        assertNull(report.error)
        assertEquals(0, report.installedFiles)
        assertEquals(1, report.skippedEntries)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.BGM_TRACKS_OVERFLOW
        }
        assertNotNull(note)
        assertEquals("sound/游戏bgm/博丽神社", note!!.detail)
        assertEquals("OLD-0", File(root, "sound/游戏bgm/博丽神社/0.mp3").readText())
    }

    @Test
    fun `BGM 卡里选到非 mp3 时点名没有可用文件`() {
        val root = tempDir("material-game-bgm-wrong")
        val source = tempDir("material-src-bgm-wrong")
        write(source, "a.ogg", "AUDIO")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BGM)

        assertEquals(0, report.installedFiles)
        assertEquals(MaterialLayout.UNSUPPORTED_FILES, report.layout)
        val note = report.structureNotes.singleOrNull {
            it.kind == StructureNoteKind.MATERIAL_NO_USABLE_FILES
        }
        assertNotNull(note)
        // 非 mp3 不计「跳过」（玩家没做错路径），只折成一条非素材读数
        assertEquals(0, report.skippedEntries)
        assertEquals(
            "1",
            report.structureNotes.single { it.kind == StructureNoteKind.MATERIAL_IGNORED_FILES }.detail,
        )
    }

    // ------------------------------------------------------------------
    // 非素材文件：不落盘、不算跳过，折成一条读数
    // ------------------------------------------------------------------

    @Test
    fun `BGM 目录里的占位与说明折成一条读数不算跳过`() {
        val root = tempDir("material-game-bgm-junk")
        val source = File(tempDir("material-src-bgm-junk"), "博丽神社")
        write(source, "0.mp3", "A0")
        // 官方 BGM 包的目录占位（0 字节、无扩展名）与说明 txt
        write(source, "bgm", "")
        write(source, "使用说明.txt", "README")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BGM)

        assertNull(report.error)
        assertEquals(1, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertTrue(File(root, "sound/游戏bgm/博丽神社/0.mp3").isFile)
        assertFalse(File(root, "sound/游戏bgm/博丽神社/bgm").exists())
        assertFalse(File(root, "sound/游戏bgm/博丽神社/使用说明.txt").exists())
        val ignored = report.structureNotes.filter {
            it.kind == StructureNoteKind.MATERIAL_IGNORED_FILES
        }
        assertEquals(1, ignored.size)
        assertEquals("2", ignored.single().detail)
    }

    @Test
    fun `背景图目录里的说明文件折成一条读数不算跳过`() {
        val root = tempDir("material-game-bg-junk")
        val source = tempDir("material-src-bg-junk")
        write(source, "神社鸟居_0_0.webp", "IMG")
        write(source, "readme.txt", "hi")

        val report = ResourcePackStore(root).installFolder(source, MaterialTarget.BACKGROUND)

        assertNull(report.error)
        assertEquals(MaterialLayout.BACKGROUND_FLAT, report.layout)
        // 1 张图 + 自动索引
        assertEquals(2, report.installedFiles)
        assertEquals(0, report.skippedEntries)
        assertFalse(File(root, "resources/背景图/readme.txt").exists())
        val ignored = report.structureNotes.filter {
            it.kind == StructureNoteKind.MATERIAL_IGNORED_FILES
        }
        assertEquals(1, ignored.size)
        assertEquals("1", ignored.single().detail)
    }
}
