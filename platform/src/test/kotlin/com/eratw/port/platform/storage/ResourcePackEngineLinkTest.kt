package com.eratw.port.platform.storage

import com.eratw.port.core.host.ResourceContents
import com.eratw.port.core.host.ResourceFileHost
import com.eratw.port.core.host.SoundResourcePaths
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.image.PngImage
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「导入的素材真能被引擎用起来」的端到端回归：素材包 → [ResourcePackStore.install] 落盘
 * → core-vm 的资源表装载 / 声音路径解析真的认得这些文件。
 *
 * 为什么需要这一层：`ResourcePackStoreTest` 只证明「文件落对了地方」，`ResourceContentsTest`
 * 只证明「给定宿主时 CSV 表能装对」。两段之间的接缝（CSV 第二列的相对路径是否按落盘目录拼、
 * `游戏bgm` 这一层目录是否与 PLAYBGM 的解析口径一致）没有任何用例覆盖——接缝断了的表现是
 * 素材导入显示成功、游戏里却静默不生效，屏上没有可查的报错。
 *
 * 纯 JVM：真实临时目录 + 假 [ResourceFileHost]（`imageSize` 不依赖解码，返回固定尺寸），
 * 不碰 Android API、不依赖环境变量。音视频字节内容不重要，测的是路径链路。
 */
class ResourcePackEngineLinkTest {

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    private fun zipOf(entries: Map<String, String>): File {
        val file = File(tempDir("pack"), "pack.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }

    /**
     * [ResourceFileHost] 的纯 JVM 假宿主（口径对照 platform 的 `ResourceCsvHost`）：
     * 递归扫 `<gameRoot>/resources` 下的 CSV、按相对路径读真实文件；`imageSize` 只要文件在
     * 盘上就返回固定 `100x100`（不依赖任何解码器），文件不在盘上返回 null 让装载侧走缺图告警。
     * [decode] 恒 null：本用例只看登记期与路径，不落像素。
     */
    private class FakeResourceHost(private val gameRoot: File) : ResourceFileHost {

        override fun listCsvFiles(): List<String> {
            val root = File(gameRoot, ResourcePackStore.RESOURCES_DIR)
            if (!root.isDirectory) return emptyList()
            val base = gameRoot.absolutePath
            return root.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".csv", ignoreCase = true) }
                .map { relativeOf(base, it.absolutePath) }
                .toList()
        }

        override fun readBytes(path: String): ByteArray? {
            val file = File(gameRoot, path)
            return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
        }

        override fun imageSize(path: String, bytes: ByteArray?): IntArray? =
            if (File(gameRoot, path).isFile) intArrayOf(100, 100) else null

        override fun decode(path: String, bytes: ByteArray): PngImage? = null

        /** 绝对路径 → 相对游戏根、恒 `/` 分隔（与 `ResourceCsvHost.relativeOf` 同口径）。 */
        private fun relativeOf(baseAbs: String, fullAbs: String): String {
            val base = baseAbs.trimEnd('/', '\\')
            val rest = if (fullAbs.startsWith(base)) fullAbs.substring(base.length) else fullAbs
            return rest.trimStart('/', '\\').replace('\\', '/')
        }
    }

    @Test
    fun `官方背景图包导入后资源表登记 sprite 且第二列拼出的图片真实存在`() {
        val root = tempDir("game")
        // 官方 BGI_Resource 包形态（GitHub 下载还多一层 <repo>-main 包裹目录）：
        // BGI.csv 里第二列是相对 CSV 所在目录的图片路径。
        val pack = zipOf(
            linkedMapOf(
                "BGI_Resource-main/BGI.csv" to
                    "通用浴室_0_0,通用/x.webp\n仓库_0_0,00/1.webp\n",
                "BGI_Resource-main/00/1.webp" to "IMG-00",
                "BGI_Resource-main/通用/x.webp" to "IMG-UTIL",
            ),
        )

        val imported = ResourcePackStore(root).install(pack)
        assertEquals(PackProfile.BACKGROUND_IMAGES, imported.profile)
        assertEquals(3, imported.installedFiles)
        // 落点：根级 BGI.csv 与编号/通用子目录整体改投到 resources/背景图 下
        assertTrue(
            "BGI.csv 必须落在 resources/背景图 根",
            File(root, "resources/背景图/BGI.csv").isFile,
        )
        assertTrue(File(root, "resources/背景图/00/1.webp").isFile)
        assertTrue(File(root, "resources/背景图/通用/x.webp").isFile)

        val pool = SpritePool()
        val loaded = ResourceContents.load(pool, FakeResourceHost(root))

        assertEquals(1, loaded.fileCount)
        assertEquals(2, loaded.spriteCount)
        assertTrue("不该有告警：${loaded.warnings}", loaded.warnings.isEmpty())
        // CSV 名字按原版 Trim().ToUpper() 登记（AppContents.cs:238），SpritePool 查表侧同样归一
        val sprite = pool.find("通用浴室_0_0")
        assertNotNull("CSV 声明的 sprite 必须进资源表", sprite)
        assertEquals("通用浴室_0_0", sprite!!.name)
        assertEquals(100, sprite.width)
        assertEquals(100, sprite.height)
        assertTrue(sprite.isCreated)
        // 第二列「CSV 所在目录 + 相对路径」拼出来必须指向盘上真实文件
        val imagePath = "resources/背景图/通用/x.webp"
        assertTrue("CSV 第二列拼出的路径必须真实存在：$imagePath", File(root, imagePath).isFile)
        assertEquals(
            "资源表父图必须登记 CSV 第二列拼出的真实路径（渲染侧按此路径解位图）",
            imagePath,
            sprite.buffer!!.fileSource,
        )
        assertNotNull(pool.find("仓库_0_0"))
    }

    @Test
    fun `裸游戏bgm包导入后 SoundResourcePaths 能解析，少一层游戏bgm 解析不到`() {
        val root = tempDir("game")
        val pack = zipOf(mapOf("游戏bgm/博丽神社/0.MP3" to "AUDIO-BYTES"))

        val imported = ResourcePackStore(root).install(pack)
        assertEquals(PackProfile.BGM, imported.profile)
        assertEquals(1, imported.installedFiles)
        val onDisk = File(root, "sound/游戏bgm/博丽神社/0.MP3")
        assertTrue("裸 游戏bgm 包必须投到 sound/游戏bgm 下", onDisk.isFile)

        // 生产同一条解析入口：PLAYSOUND / PLAYBGM 都以 <gameRoot>/sound 为根
        // （FunctionIdentifier.cs:2622、:2691），曲名是「游戏bgm/<地点>/<n>.MP3」。
        val soundDir = File(root, "sound")
        val resolved = SoundResourcePaths.resolve(soundDir, "游戏bgm/博丽神社/0.MP3")
        assertNotNull("导入落点必须能被游戏曲名解析到", resolved)
        assertEquals(onDisk.canonicalPath, resolved!!.canonicalPath)

        // 反面：文件只放在少一层的 sound/博丽神社/0.MP3 时，同一个游戏曲名解析不到
        // —— 证明解析口径里「游戏bgm」这一层不是可省的。
        val shallowRoot = tempDir("game-shallow")
        val shallowDir = File(shallowRoot, "sound/博丽神社")
        assertTrue("反面用例要先造出少一层的目录", shallowDir.mkdirs())
        val shallow = File(shallowDir, "0.MP3")
        shallow.writeText("AUDIO-BYTES")
        assertNull(
            "少了 游戏bgm 这一层，曲名 游戏bgm/博丽神社/0.MP3 必须解析不到",
            SoundResourcePaths.resolve(File(shallowRoot, "sound"), "游戏bgm/博丽神社/0.MP3"),
        )
    }

    @Test
    fun `CSV 引用的图片缺失时装载告警非空而不是静默`() {
        val root = tempDir("game")
        // 只导入 CSV，不给被引用的图片：模拟玩家只搬了索引表、图片没搬全的常见形态。
        val pack = zipOf(mapOf("resources/背景图/list.csv" to "缺失图_0_0,missing.webp\n"))

        val imported = ResourcePackStore(root).install(pack)
        assertEquals(PackProfile.STANDARD, imported.profile)
        assertEquals(1, imported.installedFiles)
        assertTrue(File(root, "resources/背景图/list.csv").isFile)

        val pool = SpritePool()
        val loaded = ResourceContents.load(pool, FakeResourceHost(root))

        assertEquals(
            "引用图不在盘上，不许登记一个画不出来的 sprite",
            0L,
            loaded.spriteCount.toLong(),
        )
        assertTrue("缺图必须告警，不能静默：${loaded.warnings}", loaded.warnings.isNotEmpty())
        assertTrue(
            "告警要点名缺失的图片：${loaded.warnings}",
            loaded.warnings.any { it.contains("missing.webp") },
        )
        assertNull(pool.find("缺失图_0_0"))
    }
}
