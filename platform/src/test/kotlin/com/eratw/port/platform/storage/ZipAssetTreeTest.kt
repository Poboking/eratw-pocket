package com.eratw.port.platform.storage

import com.eratw.port.core.loader.BootMetadata
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 数据更新通道（镜像仓 zip → [ZipAssetTree] → [GameAssetInstaller] 更新档）的定向单测。
 *
 * 钉的是这条通道最容易赔钱的三件事：
 * 1. **zip 条目名是不可信输入**：`..`、空段、控制字符一律不进索引，并且要**计数**留给调用方拒装；
 * 2. **更新档必须真的覆盖**：新内容与旧内容**同长**时首启档会判「已装好」而跳过 ——
 *    这条是本功能的成败点（重编码的 webp 撞长度是常态，不是巧合）；
 * 3. **覆盖不许越界**：玩家的 `emuera.config` 仍然不动，program 与侧车仍然同进同退，
 *    包里没有的文件不删。
 *
 * 与 [GameAssetInstallerTest] 同口径：纯 JVM、临时目录当游戏根、不建 `Context`。
 * 下载与摘要核对在 app 壳，不在本文件（归真机取证）。
 */
class ZipAssetTreeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    // ------------------------------------------------------------------
    // 一、资产树本体：外部归档的读取口径
    // ------------------------------------------------------------------

    @Test
    fun `列目录只出直接子项且不带路径`() {
        bundle("erbc.v1/program.meta" to "m".toByteArray(), "CSV/a.txt" to "A".toByteArray(),
            "CSV/sub/b.txt" to "B".toByteArray(), "resources/c.webp" to "C".toByteArray(),
            "emuera.config" to "cfg".toByteArray()) { tree ->
            // 根这一层混排文件与目录前缀（与 AndroidAssetTree 的 list 同口径）；
            // 解包器实际只问它认识的目录，但索引必须整棵都对。
            assertEquals(listOf("CSV", "emuera.config", "erbc.v1", "resources"), tree.listDir("").sorted())
            assertEquals(listOf("a.txt", "sub"), tree.listDir("CSV").sorted())
            assertEquals(listOf("b.txt"), tree.listDir("CSV/sub"))
            assertEquals(emptyList<String>(), tree.listDir("不存在"))
            assertEquals(1L, tree.fileSize("CSV/a.txt"))
            assertNull("目录不是文件", tree.fileSize("CSV"))
            assertNull(tree.openFile("CSV"))
        }
    }

    @Test
    fun `压缩条目也拿得到解压后长度`() {
        // 这是 zip 通道相对 AndroidAssetTree 的关键优势：DEFLATE 条目在 assets 里只能报 null
        // （解包侧退化成「存在即跳过」），而 zip 中央目录恒记着解压后字节数。
        bundle("resources/big.webp" to bytes(64 * 1024, seed = 3), deflate = true) { tree ->
            assertEquals(65_536L, tree.fileSize("resources/big.webp"))
            assertEquals(65_536L, tree.openFile("resources/big.webp")!!.readBytes().size.toLong())
        }
    }

    @Test
    fun `零长度条目报零而不是null`() {
        // 语料 sound 下有 18 个零字节 bgm 标记：0 是有效长度，报 null 会让解包侧退化。
        bundle("sound/mute" to ByteArray(0)) { tree ->
            assertEquals(0L, tree.fileSize("sound/mute"))
            assertEquals(0, tree.openFile("sound/mute")!!.readBytes().size)
        }
    }

    @Test
    fun `目录条目不进文件索引`() {
        bundle("CSV/" to ByteArray(0), "CSV/a.txt" to "A".toByteArray()) { tree ->
            assertNull(tree.fileSize("CSV"))
            assertEquals(listOf("a.txt"), tree.listDir("CSV"))
            assertEquals(0, tree.rejectedEntryCount)
        }
    }

    @Test
    fun `逃逸路径的条目被丢出并计数`() {
        bundle(
            "../evil.txt" to "X".toByteArray(),
            "CSV/../resources/ok.txt" to "X".toByteArray(),
            "CSV//double.txt" to "X".toByteArray(),
            "CSV/tab\t.txt" to "X".toByteArray(),
            "/abs.txt" to "X".toByteArray(),
            "CSV/good.txt" to "G".toByteArray(),
        ) { tree ->
            assertEquals(4, tree.rejectedEntryCount)
            assertNull(tree.openFile("../evil.txt"))
            assertNull(tree.openFile("CSV/../resources/ok.txt"))
            assertNull(tree.openFile("CSV//double.txt"))
            assertNull(tree.openFile("CSV/tab\t.txt"))
            // 开头的斜杠是「相对根」的写法，剥掉斜杠照装；它不构成逃逸，所以不计进拒绝数。
            assertEquals(1L, tree.fileSize("abs.txt"))
            // 坏名不牵连好条目：整包拒装由调用方按 rejectedEntryCount 决定。
            assertEquals(listOf("good.txt"), tree.listDir("CSV"))
        }
    }

    @Test
    fun `条目名归一认反斜杠与前点`() {
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName("CSV\\a.txt"))
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName("./CSV/a.txt"))
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName("/CSV/a.txt"))
        assertEquals("CSV", ZipAssetTree.safeEntryName("CSV/"))
        assertNull(ZipAssetTree.safeEntryName(""))
        assertNull(ZipAssetTree.safeEntryName("."))
        assertNull(ZipAssetTree.safeEntryName("a/../../b"))
        assertNull(ZipAssetTree.safeEntryName("a//b"))
    }

    /**
     * W5-misc 第 2 条之三：前缀**循环**剥，出口只有「规范相对路径」或「拒收」两种形态。
     *
     * 判据与摘要读侧 `GameAssetInstaller.isRecordablePath` 对称（它判死任何前导斜杠与 `.` 段）：
     * 归一后的名字若还留着 `./` 或 `.` 段，写进数据摘要就整份作废（差量永久回落全量），
     * 当索引键又查不到（表现为「包里没有这条资产」）。这里把三层形态钉死。
     */
    @Test
    fun `多级前缀循环剥净且点段一律拒收`() {
        // 只剥一层时这三条都会留下 `./` 或 `//` 开头的残骸。
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName("././CSV/a.txt"))
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName(".///CSV/a.txt"))
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName("/./CSV/a.txt"))
        assertEquals("CSV/a.txt", ZipAssetTree.safeEntryName("./././CSV\\a.txt"))
        // 纯点与纯斜杠的组合：剥到空 ⇒ 拒收（没有落盘意义）。
        assertNull(ZipAssetTree.safeEntryName("./"))
        assertNull(ZipAssetTree.safeEntryName("/"))
        assertNull(ZipAssetTree.safeEntryName("././."))
        // 中间的 `.` 段不在归一职责内（它会把父链撑出一个名为 `.` 的假目录）⇒ 与摘要读侧同判：拒收。
        assertNull(ZipAssetTree.safeEntryName("CSV/./a.txt"))
        // 越界形态不会因为剥前缀被洗白：`..` 段还在剩余路径里 ⇒ 照旧拒收。
        assertNull(ZipAssetTree.safeEntryName("./../evil.txt"))
        assertNull(ZipAssetTree.safeEntryName("/a/../b.txt"))
        // 以点开头的**真实**文件名不受影响（它不以 `./` 开头）：语料里 `.hidden` 这类名字是合法文件。
        assertEquals(".hidden", ZipAssetTree.safeEntryName(".hidden"))
        assertEquals("CSV/.gitignore", ZipAssetTree.safeEntryName("./CSV/.gitignore"))
    }

    /** 归一收敛后条目按**规范名**进索引：`././CSV/a.txt` 必须能按 `CSV/a.txt` 查到。 */
    @Test
    fun `多级前缀条目按规范名可查且不造出点目录`() {
        newBundle("././CSV/a.txt" to "A".toByteArray(), "CSV/good.txt" to "G".toByteArray()).let { zip ->
            ZipAssetTree(zip).use { tree ->
                assertEquals(0, tree.rejectedEntryCount)
                assertArrayEquals("A".toByteArray(), tree.openFile("CSV/a.txt")?.readBytes())
                assertEquals(1L, tree.fileSize("CSV/a.txt"))
                // 父链里不许出现名为 `.` 的假子项（旧口径下 `././CSV/a.txt` 会撑出它）。
                assertEquals(listOf("CSV"), tree.listDir(""))
                assertEquals(listOf("a.txt", "good.txt"), tree.listDir("CSV"))
            }
        }
    }

    // ------------------------------------------------------------------
    // 二、更新档
    // ------------------------------------------------------------------

    @Test
    fun `同长的新内容只有更新档才落盘`() {
        val v1 = newBundle("CSV/x.txt" to "AAAA".toByteArray(), "emuera.config" to "包内默认配置".toByteArray())
        val first = GameAssetInstaller(ZipAssetTree(v1), gameRoot).install()
        assertEquals("首启要落配置与一条 CSV", 2, first.copiedFiles)

        File(gameRoot, "emuera.config").writeText("玩家自己改的配置")

        // 新包里的立绘与旧文件**同长不同内容** —— 首启档判成「已经装好」，更新档必须换。
        val v2 = newBundle("CSV/x.txt" to "BBBB".toByteArray(), "emuera.config" to "新默认".toByteArray())
        val kept = GameAssetInstaller(ZipAssetTree(v2), gameRoot).install()
        assertEquals("首启档比长度：同长 ⇒ 一个字节都不动（这正是更新会赔的地方）", 0, kept.copiedFiles)
        assertEquals("AAAA", File(gameRoot, "CSV/x.txt").readText())

        val replaced = GameAssetInstaller(ZipAssetTree(v2), gameRoot, forceReplace = true).install()
        assertEquals("更新档只换 CSV：配置不在覆盖范围", 1, replaced.copiedFiles)
        assertEquals("BBBB", File(gameRoot, "CSV/x.txt").readText())
        assertEquals(
            "更新档也不许覆盖 emuera.config（玩家能在游戏内改写它）",
            "玩家自己改的配置",
            File(gameRoot, "emuera.config").readText(),
        )
    }

    @Test
    fun `更新档同长产物照样换版并按侧车核对`() {
        val one = newBundleOf(erbcEntries(bytes(200_000, seed = 11)))
        GameAssetInstaller(ZipAssetTree(one), gameRoot).install()
        assertArrayEquals(bytes(200_000, seed = 11), File(gameRoot, "erbc.v1/program.erbc").readBytes())

        val two = newBundleOf(erbcEntries(bytes(200_000, seed = 12)))
        val result = GameAssetInstaller(ZipAssetTree(two), gameRoot, forceReplace = true).install()

        assertEquals(2, result.copiedFiles)
        assertArrayEquals(bytes(200_000, seed = 12), File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertTrue("新侧车的 sha 必须核对通过", result.programSha256Verified)
    }

    @Test
    fun `更新档sha不配对时整对回滚留旧版`() {
        val program1 = bytes(120_000, seed = 21)
        val good = newBundleOf(erbcEntries(program1))
        GameAssetInstaller(ZipAssetTree(good), gameRoot).install()

        val program2 = bytes(120_000, seed = 22)
        // 新 program 配旧声明 —— 发布侧算错哈希的形态。
        val bad = newBundleOf(erbcEntries(program2, declaredSha = sha256(program1)))

        val e = assertThrows(AssetInstallException::class.java) {
            GameAssetInstaller(ZipAssetTree(bad), gameRoot, forceReplace = true).install()
        }
        assertTrue("要看得出是不配对：${e.message}", e.message!!.contains("不配对"))
        assertArrayEquals("盘上必须停在上一版配对", program1, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertFalse("中间文件不能留下", File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse("备份不能留下", File(gameRoot, "erbc.v1/program.erbc.bak").exists())
    }

    @Test
    fun `包里没有的条目不删盘上残留`() {
        val zip = newBundle("CSV/a.txt" to "A".toByteArray())
        GameAssetInstaller(ZipAssetTree(zip), gameRoot).install()
        File(gameRoot, "resources").mkdirs()
        File(gameRoot, "resources/old.webp").writeText("老立绘")

        GameAssetInstaller(ZipAssetTree(zip), gameRoot, forceReplace = true).install()

        assertEquals("老立绘", File(gameRoot, "resources/old.webp").readText())
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 建包并用树跑一段（树实现 AutoCloseable，跑完即关 ZipFile）。 */
    private fun bundle(
        vararg entries: Pair<String, ByteArray>,
        deflate: Boolean = false,
        block: (ZipAssetTree) -> Unit,
    ) {
        ZipAssetTree(newBundle(*entries, deflate = deflate)).use(block)
    }

    private fun newBundle(
        vararg entries: Pair<String, ByteArray>,
        deflate: Boolean = false,
    ): File = zipTo(temp.newFile(), entries.toList(), deflate)

    /** 列表形态（产物两条由 [erbcEntries] 生成，展开成 vararg 反而绕）。 */
    private fun newBundleOf(entries: List<Pair<String, ByteArray>>): File =
        zipTo(temp.newFile(), entries, false)

    /** 资产包里的产物两条：program 与自述其 sha 的侧车（缺省配平，配对事务才走得通）。 */
    private fun erbcEntries(
        program: ByteArray,
        declaredSha: String? = sha256(program),
    ): List<Pair<String, ByteArray>> = listOf(
        "erbc.v1/program.erbc" to program,
        "erbc.v1/program.meta" to BootMetadata()
            .withHeader(programSha256 = declaredSha)
            .encodeText()
            .toByteArray(),
    )

    private fun zipTo(target: File, entries: List<Pair<String, ByteArray>>, deflate: Boolean): File {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name.endsWith("/")) {
                    out.putNextEntry(entry)
                    out.closeEntry()
                    continue
                }
                if (deflate) {
                    entry.method = ZipEntry.DEFLATED
                } else {
                    // STORED：长度与 CRC 由写侧显式给出，读侧才拿得到确定的解压后字节数。
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return target
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    /** 可复现的伪随机字节（与 GameAssetInstallerTest 同口径，不用 kotlin.random）。 */
    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }
}
