package com.eratw.port.platform.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「清除已导入的素材」（背景图 / BGM）的存储层定向单测。
 *
 * 用例按契约的冻结语义逐条钉：范围（1）、判基准的两个来源（2）、删除与计数（3）、
 * 被覆盖基准文件按 assets 原字节还原（4）、剪空目录（5）、canonical 边界闸（6）、幂等（7）。
 *
 * 判基准的两个权威来源在这里都当**外部输入**喂进来：
 * - 盘上摘要走真实现 `GameAssetInstaller.encodeDataDigest` 写 `update/data-digest.txt`，
 *   再用 `GameAssetInstaller.readDataDigest` 读回（顺便钉住「摘要形状=本层能吃」）；
 * - APK assets 走本文件里的 [FakeAssetTree]（临时目录视角，三方法都「不存在就返回空/ null」）。
 */
class ResourcePackCleanupTest {

    // ------------------------------------------------------------------
    // 1. 范围 + 判基准（摘要一支）：删非基准、留基准、计数
    // ------------------------------------------------------------------

    @Test
    fun `背景图清理删除非基准文件并保留基准文件与计数`() {
        val root = gameRoot()
        val imported = write(root, "resources/背景图/00/imported.webp", "IMPORTED-BYTES")
        val shipped = write(root, "resources/背景图/00/神社_0_0.webp", "BASE-BYTES")
        writeDigest(root, "resources/背景图/00/神社_0_0.webp" to "BASE-BYTES")

        // 只给摘要（assets 树缺位）：基准文件只保留、不猜也不删。
        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, null)

        assertNull("正常跑过：${report.error}", report.error)
        assertEquals(1, report.deletedFiles)
        assertEquals("按删除前的长度累计", "IMPORTED-BYTES".length.toLong(), report.deletedBytes)
        assertEquals(1, report.keptBaseFiles)
        assertEquals(0, report.restoredBaseFiles)
        assertEquals("00 目录里还有基准文件，不该被剪", 0, report.prunedDirs)
        assertTrue("失败取证应为空：${report.failures}", report.failures.isEmpty())
        assertFalse(imported.exists())
        assertTrue(shipped.isFile)
        assertEquals("BASE-BYTES", shipped.readText())
    }

    // ------------------------------------------------------------------
    // 4. 还原：被导入覆盖的基准文件按 assets 原字节写回
    // ------------------------------------------------------------------

    @Test
    fun `被导入覆盖的基准文件按 assets 原字节还原`() {
        val root = gameRoot()
        val overwritten = write(root, "resources/背景图/00/神社_0_0.webp", "IMPORTED-OVERWRITE")
        writeDigest(root, "resources/背景图/00/神社_0_0.webp" to "SHIPPED-ORIGINAL")
        val base = assets("resources/背景图/00/神社_0_0.webp" to "SHIPPED-ORIGINAL")

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)

        assertNull(report.error)
        assertEquals("基准文件不删", 0, report.deletedFiles)
        assertEquals(1, report.restoredBaseFiles)
        assertEquals(0, report.keptBaseFiles)
        assertEquals("SHIPPED-ORIGINAL", overwritten.readText())
        assertTrue("失败取证应为空：${report.failures}", report.failures.isEmpty())
        val leftovers = overwritten.parentFile!!.listFiles()!!.map { it.name }
        assertTrue("临时文件不许留在盘上：$leftovers", leftovers.none { it.endsWith("clear-tmp") })
    }

    @Test
    fun `基准文件内容本来就一致时不还原也不改写`() {
        val root = gameRoot()
        val same = write(root, "resources/背景图/00/a.webp", "SAME-BYTES")
        writeDigest(root, "resources/背景图/00/a.webp" to "SAME-BYTES")
        val base = assets("resources/背景图/00/a.webp" to "SAME-BYTES")
        val before = same.lastModified()

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)

        assertNull(report.error)
        assertEquals(0, report.restoredBaseFiles)
        assertEquals(1, report.keptBaseFiles)
        assertEquals(0, report.deletedFiles)
        assertEquals("SAME-BYTES", same.readText())
        assertEquals("内容相同 ⇒ 连写都不写（mtime 不动）", before, same.lastModified())
    }

    @Test
    fun `基准在摘要里但 assets 读不到原字节时只点名不删`() {
        val root = gameRoot()
        val kept = write(root, "resources/背景图/00/a.webp", "OVERWRITTEN")
        writeDigest(root, "resources/背景图/00/a.webp" to "ORIGINAL")
        // assets 树活着（CSV 探针非空），但这条路径不在其中 ⇒ 拿不到原字节。
        val base = assets("CSV/配置.txt" to "CSV")

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)

        assertNull(report.error)
        assertEquals(0, report.deletedFiles)
        assertEquals(0, report.restoredBaseFiles)
        assertEquals("拿不准就只计数保留", 1, report.keptBaseFiles)
        assertEquals(1, report.failures.size)
        assertTrue("取证要点名路径：${report.failures}", report.failures[0].contains("resources/背景图/00/a.webp"))
        assertTrue("取证要说清没还原：${report.failures}", report.failures[0].contains("未还原"))
        assertTrue(kept.isFile)
        assertEquals("没拿到原字节就不许改写", "OVERWRITTEN", kept.readText())
    }

    // ------------------------------------------------------------------
    // 1. BGM 的范围是整个 sound 子树
    // ------------------------------------------------------------------

    @Test
    fun `BGM 清理覆盖整个 sound 子树且不碰背景图`() {
        val root = gameRoot()
        val inGameBgm = write(root, "sound/游戏bgm/博丽神社/1.mp3", "A1")
        // 标准形态的包会把音频投到 sound 下的地点目录：这正是「导了也不响」那批文件。
        val misplaced = write(root, "sound/某地/0.mp3", "A2")
        val background = write(root, "resources/背景图/00/keep.webp", "IMG")
        val shippedBgm = write(root, "sound/游戏bgm/博丽神社/0.mp3", "SHIPPED")
        writeDigest(root, "sound/游戏bgm/博丽神社/0.mp3" to "SHIPPED")

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BGM, null)

        assertNull(report.error)
        assertEquals("两个非基准音频都算在范围内", 2, report.deletedFiles)
        assertEquals(1, report.keptBaseFiles)
        assertFalse(inGameBgm.exists())
        assertFalse(misplaced.exists())
        assertTrue("背景图不在 BGM 范围里", background.isFile)
        assertTrue(shippedBgm.isFile)
        assertEquals("被删空的 sound/某地 剪掉", 1, report.prunedDirs)
        assertTrue("范围根 sound 永不删", File(root, "sound").isDirectory)
        assertFalse(File(root, "sound/某地").exists())
    }

    // ------------------------------------------------------------------
    // 1. 背景图的范围只有 resources/背景图
    // ------------------------------------------------------------------

    @Test
    fun `背景图清理不碰 sound 与 resources 下其它基准子树`() {
        val root = gameRoot()
        val imported = write(root, "resources/背景图/00/imported.webp", "IMG")
        val otherTree = write(root, "resources/isaac/cg/a.webp", "CG")
        // 别的资源子树里连「不在基准里」的文件也不归这两张卡管（范围闸的反向证明）：
        // 若把范围根换成整棵 resources，这条会被删，用例即红。
        val strayCg = write(root, "resources/isaac/cg/stray.webp", "STRAY")
        val audio = write(root, "sound/游戏bgm/博丽神社/1.mp3", "AUDIO")
        writeDigest(
            root,
            "resources/isaac/cg/a.webp" to "CG",
            "sound/游戏bgm/博丽神社/1.mp3" to "AUDIO",
        )

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, null)

        assertNull(report.error)
        assertEquals(1, report.deletedFiles)
        assertEquals("范围外的基准文件不参与计数", 0, report.keptBaseFiles)
        assertFalse(imported.exists())
        assertTrue("resources 下的其它基准子树不许动", otherTree.isFile)
        assertTrue("sound 不许动", audio.isFile)
        assertTrue("其它资源子树里非基准的文件同样不许动", strayCg.isFile)
        assertEquals("退回去也可能被删", "CG", otherTree.readText())
    }

    @Test
    fun `前缀相近的目录不受影响`() {
        val root = gameRoot()
        val backup = write(root, "resources/背景图备份/00/old.webp", "BACKUP")
        val backupIndex = write(root, "resources/背景图备份/BGI.csv", "OLD")
        val imported = write(root, "resources/背景图/00/imported.webp", "IMG")
        writeDigest(root, "resources/背景图备份/BGI.csv" to "OLD")

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, null)

        assertNull(report.error)
        assertEquals(1, report.deletedFiles)
        assertFalse(imported.exists())
        assertTrue("前缀相近但不是范围根，一个字节都不许动", backup.isFile)
        assertTrue(backupIndex.isFile)
    }

    // ------------------------------------------------------------------
    // 0.1 / 2. 基准源存活闸
    // ------------------------------------------------------------------

    @Test
    fun `基准源不可用时一个字节都不删并回 error`() {
        val noDigestNoTree = gameRoot()
        val survivor = write(noDigestNoTree, "resources/背景图/00/imported.webp", "IMG")

        val noTree = ResourcePackCleanup.clear(noDigestNoTree, MaterialTarget.BACKGROUND, null)

        assertNotNull("整批没做必须说出来", noTree.error)
        assertTrue("原话要点名基准清单读不到：${noTree.error}", noTree.error!!.contains("基准清单读不到"))
        assertEquals(0, noTree.deletedFiles)
        assertEquals(0L, noTree.deletedBytes)
        assertTrue(noTree.failures.isEmpty())
        assertTrue("一个字节都不许删", survivor.isFile)

        val emptyTree = gameRoot()
        val survivor2 = write(emptyTree, "resources/背景图/00/imported.webp", "IMG")
        // assets 树活着但列不出 CSV（开发包/手工拷根这一类）⇒ 同样按读不到处理。
        val report = ResourcePackCleanup.clear(emptyTree, MaterialTarget.BACKGROUND, assets())

        assertNotNull(report.error)
        assertEquals(0, report.deletedFiles)
        assertTrue(survivor2.isFile)
    }

    @Test
    fun `无摘要但 assets 能列出 CSV 时按 assets 判基准并正常清理`() {
        val root = gameRoot()
        val imported = write(root, "resources/背景图/00/imported.webp", "IMG")
        val shipped = write(root, "resources/背景图/00/神社_0_0.webp", "BASE")
        assertFalse("本用例刻意不给摘要", GameAssetInstaller.dataDigestFile(root).exists())
        val base = assets(
            "CSV/配置.txt" to "CSV",
            "resources/背景图/00/神社_0_0.webp" to "BASE",
        )

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)

        assertNull("存活探针过了就必须正常跑：${report.error}", report.error)
        assertEquals(1, report.deletedFiles)
        assertEquals("仅靠 assets 这一支也判得出基准", 1, report.keptBaseFiles)
        assertFalse(imported.exists())
        assertTrue(shipped.isFile)
    }

    // ------------------------------------------------------------------
    // 1. 范围目录不存在 = 无事可做
    // ------------------------------------------------------------------

    @Test
    fun `范围目录不存在时计数全为 0 且 error 为空`() {
        val root = gameRoot()
        write(root, "CSV/配置.txt", "CSV")
        writeDigest(root, "CSV/配置.txt" to "CSV")

        val background = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, null)
        assertNull("无事可做不是失败", background.error)
        assertEquals(0, background.deletedFiles)
        assertEquals(0L, background.deletedBytes)
        assertEquals(0, background.restoredBaseFiles)
        assertEquals(0, background.keptBaseFiles)
        assertEquals(0, background.prunedDirs)
        assertTrue(background.failures.isEmpty())

        val bgm = ResourcePackCleanup.clear(root, MaterialTarget.BGM, null)
        assertNull(bgm.error)
        assertEquals(0, bgm.deletedFiles)
        assertTrue(bgm.failures.isEmpty())
    }

    // ------------------------------------------------------------------
    // 5. 剪空目录：范围根不删
    // ------------------------------------------------------------------

    @Test
    fun `剪掉被清空的子目录但范围根保留`() {
        val root = gameRoot()
        write(root, "resources/背景图/00/deep/a.webp", "IMG")
        write(root, "resources/背景图/00/deep/b.webp", "IMG")
        writeDigest(root, "resources/背景图/README.txt" to "NOTE")
        write(root, "resources/背景图/README.txt", "NOTE")

        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, null)

        assertNull(report.error)
        assertEquals(2, report.deletedFiles)
        assertEquals("00 与 00/deep 两层都要剪", 2, report.prunedDirs)
        assertFalse(File(root, "resources/背景图/00").exists())
        assertTrue("范围根 resources/背景图 保留", File(root, "resources/背景图").isDirectory)
        assertTrue("范围根之外一层都不许碰", File(root, "resources").isDirectory)
    }

    // ------------------------------------------------------------------
    // 7. 幂等
    // ------------------------------------------------------------------

    @Test
    fun `连跑两次第二次零删除`() {
        val root = gameRoot()
        write(root, "resources/背景图/00/imported-a.webp", "A")
        write(root, "resources/背景图/00/imported-b.webp", "BB")
        write(root, "resources/背景图/00/神社_0_0.webp", "BASE-BYTES")
        writeDigest(root, "resources/背景图/00/神社_0_0.webp" to "BASE-BYTES")
        val base = assets(
            "CSV/配置.txt" to "CSV",
            "resources/背景图/00/神社_0_0.webp" to "BASE-BYTES",
        )

        val first = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)
        assertEquals(2, first.deletedFiles)
        assertEquals(1, first.keptBaseFiles)

        val second = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)

        assertNull(second.error)
        assertEquals("第二次没有可删的东西", 0, second.deletedFiles)
        assertEquals(0L, second.deletedBytes)
        assertEquals(0, second.restoredBaseFiles)
        assertEquals(0, second.prunedDirs)
        assertEquals("基准文件仍然在", 1, second.keptBaseFiles)
        assertTrue(second.failures.isEmpty())
    }

    // ------------------------------------------------------------------
    // 1 / 2. 自动生成的索引属非基准
    // ------------------------------------------------------------------

    @Test
    fun `自动索引属非基准被删而基准里的同路径文件保留`() {
        val loose = gameRoot()
        val autoIndex = write(loose, "resources/背景图/自动索引.csv", "神社_0_0,神社_0_0.webp\n")
        write(loose, "resources/背景图/神社_0_0.webp", "IMG")
        // 随包索引是 BGI.csv，自动索引不在随包清单里 ⇒ 属玩家导入。
        writeDigest(loose, "resources/背景图/BGI.csv" to "BGI")

        val deleted = ResourcePackCleanup.clear(loose, MaterialTarget.BACKGROUND, null)

        assertNull(deleted.error)
        assertEquals(2, deleted.deletedFiles)
        assertFalse(autoIndex.exists())

        val shipped = gameRoot()
        val shippedIndex = write(shipped, "resources/背景图/自动索引.csv", "BASE-INDEX")
        writeDigest(shipped, "resources/背景图/自动索引.csv" to "BASE-INDEX")

        val kept = ResourcePackCleanup.clear(shipped, MaterialTarget.BACKGROUND, null)

        assertNull(kept.error)
        assertEquals("基准里的同路径文件不许删", 0, kept.deletedFiles)
        assertEquals(1, kept.keptBaseFiles)
        assertTrue(shippedIndex.isFile)
    }

    // ------------------------------------------------------------------
    // 6. canonical 边界闸
    // ------------------------------------------------------------------

    @Test
    fun `符号链接指到范围根之外时跳过并记失败取证`() {
        val root = gameRoot()
        val scopeRoot = File(root, "resources/背景图")
        assertTrue(scopeRoot.mkdirs())
        val outside = tempDir("cleanup-outside")
        val secret = File(outside, "secret.webp")
        assertTrue(secret.parentFile!!.isDirectory)
        secret.writeText("OUTSIDE")

        val created = runCatching {
            Files.createSymbolicLink(File(scopeRoot, "escape").toPath(), outside.toPath())
        }.isSuccess
        assumeTrue("本机建不了目录符号链接（Windows 需开发者模式/提权），只用能建的机器跑这条", created)
        // File.canonicalPath 在 POSIX / Android 上穿透符号链接；Windows 上只做 GetFullPathName
        // 那一层归一（不解析重解析点），所以边界闸只在这台宿主机器能穿透时判得出来。
        val resolvesLinks = runCatching {
            File(scopeRoot, "escape").canonicalPath == outside.canonicalPath
        }.getOrDefault(false)
        assumeTrue("本机 canonicalPath 不解析目录符号链接（Windows 语料机），边界闸在这台机器上判不出来", resolvesLinks)

        val base = assets("CSV/配置.txt" to "CSV")
        val report = ResourcePackCleanup.clear(root, MaterialTarget.BACKGROUND, base)

        assertNull(report.error)
        assertEquals("越界文件一个字节都不许删", 0, report.deletedFiles)
        assertTrue("范围根外的真实文件还在", secret.isFile)
        assertEquals(1, report.failures.size)
        assertTrue("取证要点名路径：${report.failures}", report.failures[0].contains("escape"))
        assertTrue("取证要说清是边界闸挡的：${report.failures}", report.failures[0].contains("canonical"))
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * APK assets 树的测试替身：临时目录视角，三方法都按接口语义「不存在就返回空/ null，不抛」。
     */
    private class FakeAssetTree(private val root: File) : GameAssetTree {
        override fun openFile(path: String): InputStream? {
            val file = File(root, path)
            return if (file.isFile) ByteArrayInputStream(file.readBytes()) else null
        }

        override fun listDir(path: String): List<String> {
            val dir = File(root, path)
            return if (dir.isDirectory) dir.list()?.toList() ?: emptyList() else emptyList()
        }

        override fun fileSize(path: String): Long? {
            val file = File(root, path)
            return if (file.isFile) file.length() else null
        }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

    /** 游戏根：每个用例独立临时目录，清完删。 */
    private fun gameRoot(): File = tempDir("cleanup-game")

    private fun write(root: File, rel: String, content: String): File {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    /** 把 entries 写成真摘要（走生产侧编码器，顺便钉住「摘要形状本层吃得下」）。 */
    private fun writeDigest(root: File, vararg entries: Pair<String, String>) {
        val table = LinkedHashMap<String, DataDigestEntry>()
        for ((rel, content) in entries) {
            val bytes = content.toByteArray(Charsets.UTF_8)
            table[rel] = DataDigestEntry(bytes.size.toLong(), sha256Hex(bytes))
        }
        val file = GameAssetInstaller.dataDigestFile(root)
        assertTrue(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        file.writeText(GameAssetInstaller.encodeDataDigest(1L, table), Charsets.UTF_8)
    }

    private fun assets(vararg entries: Pair<String, String>): GameAssetTree {
        val dir = tempDir("cleanup-assets")
        for ((rel, content) in entries) write(dir, rel, content)
        return FakeAssetTree(dir)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
