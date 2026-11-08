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
 * **差量数据包**（zip 里只带变更/新增 + 根内 `update-delta.txt` 删除表）与**文件级摘要**
 * （`update/data-digest.txt`）的定向单测 —— 契约 CONTRACT-UPDATE §1.3 与 §4 U-C 那条。
 *
 * 钉的是这条通道会赔大钱的地方，按「赔什么」分组：
 *
 * 1. **删除面只管表内的东西**（第一节）。差量包的全部价值是「只下几 MB」，全部风险是
 *    「多删一个文件」。所以表外的条目必须一个字节都不动，而 `update-delta.txt` 自己
 *    **绝不能落进游戏根**（它不是游戏文件）。
 * 2. **删除面的判据与写入面同源**（第二节）：条目名沿用 `ZipAssetTree.safeEntryName` 那一套
 *    归一与拒绝口径，再加「必须在游戏根之内」与「只在解包器自己落盘的范围之内」两道；
 *    保护名单与发布侧 `devtools/emit_update.py` 的 `NEVER_BUNDLED` 同名单。撞判据的行是
 *    **跳过并计数**，不是抛 —— 一张写歪的表不该把玩家的更新永远卡在「三次失败即丢包」的循环里。
 * 3. **产物对不许只换一半**（第三节）：差量包只带 program 或只带侧车都在**动盘之前**拒掉；
 *    装坏一半是装载期/运行期的错位读，拒了只是这一次没更新。
 * 4. **摘要的格式与并表口径**（第四节）：格式是发布侧 U-D 要按同样形状产出的东西，所以逐行
 *    钉死；并表是「上一份 ⊕ 本轮写过的 − 本轮删掉的」，这样一轮差量才不必重读 680 MiB。
 * 5. **全量档与首启档零回归**（第四节末）：不给版本号就一个摘要字节都不写，
 *    没带删除表的包行为与这一版之前逐字相同。
 *
 * 与 [ZipAssetTreeTest] / [GameAssetInstallerTest] 同口径：纯 JVM、临时目录当游戏根、不建
 * `Context`。下载、清单判定与「选全量还是选差量」不在本文件（那是 core 与 app 壳的闸）。
 */
class DiffAssetApplyTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    // ------------------------------------------------------------------
    // 一、差量档的边界：只动表内的
    // ------------------------------------------------------------------

    @Test
    fun `差量表外的条目一个字节都不动，表内的照删`() {
        val base = newBundle(
            "CSV/keep.txt" to "留着".toByteArray(),
            "CSV/gone.txt" to "该删".toByteArray(),
            "resources/old.webp" to "老立绘".toByteArray(),
        )
        GameAssetInstaller(ZipAssetTree(base), gameRoot).install()
        // 盘上一条「包里没有、也不在删除表里」的残留：全量档与差量档都不许碰它。
        File(gameRoot, "plugins").mkdirs()
        File(gameRoot, "plugins/mine.xml").writeText("玩家自己加的 mod 配置")

        val delta = newBundle(
            "CSV/new.txt" to "新脚本".toByteArray(),
            "update-delta.txt" to "CSV/gone.txt\n".toByteArray(),
        )
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals("差量只落包内那一条", 1, result.copiedFiles)
        assertEquals("表内那条真删了", 1, result.deletedFiles)
        assertEquals("没有撞判据的行", 0, result.blockedDeletions)
        assertFalse("表内条目已从盘上消失", File(gameRoot, "CSV/gone.txt").exists())
        assertEquals("新脚本", File(gameRoot, "CSV/new.txt").readText())
        assertEquals("表外的没动", "留着", File(gameRoot, "CSV/keep.txt").readText())
        assertEquals("包里没有也没进表的残留没动", "老立绘", File(gameRoot, "resources/old.webp").readText())
        assertEquals("mod 自己的文件没动", "玩家自己加的 mod 配置", File(gameRoot, "plugins/mine.xml").readText())
    }

    @Test
    fun `删除表本身绝不落进游戏根，也不进索引`() {
        val delta = newBundle(
            "CSV/x.txt" to "X".toByteArray(),
            "update-delta.txt" to "CSV/old.txt\n".toByteArray(),
        )
        ZipAssetTree(delta).use { tree ->
            assertTrue("有表就是差量档", tree.isDeltaPackage)
            assertEquals(listOf("CSV/old.txt"), tree.deltaDeletions())
            assertEquals("控制条目不是坏名字，不计进拒装闸", 0, tree.rejectedEntryCount)
            assertNull("不进文件索引 ⇒ 落不了盘", tree.fileSize("update-delta.txt"))
            assertNull(tree.openFile("update-delta.txt"))
            assertFalse("根目录清单里也不该看得见", tree.listDir("").contains("update-delta.txt"))
            assertEquals(1L, tree.fileSize("CSV/x.txt"))
        }
        GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()
        assertFalse(File(gameRoot, "update-delta.txt").exists())
    }

    @Test
    fun `空表也算差量档，装完不删任何东西`() {
        val base = newBundle("CSV/a.txt" to "A".toByteArray())
        GameAssetInstaller(ZipAssetTree(base), gameRoot).install()

        val delta = newBundle("CSV/b.txt" to "B".toByteArray(), "update-delta.txt" to ByteArray(0))
        ZipAssetTree(delta).use { tree ->
            assertTrue(tree.isDeltaPackage)
            assertEquals(emptyList<String>(), tree.deltaDeletions())
            assertEquals(0, tree.rejectedEntryCount)
        }
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()
        assertEquals(1, result.copiedFiles)
        assertEquals(0, result.deletedFiles)
        assertEquals("A", File(gameRoot, "CSV/a.txt").readText())
        assertEquals("B", File(gameRoot, "CSV/b.txt").readText())
    }

    // ------------------------------------------------------------------
    // 二、删除表的判据
    // ------------------------------------------------------------------

    @Test
    fun `表里的非法行计入拒装闸，好行照样解析`() {
        val base = newBundle("CSV/ok.txt" to "OK".toByteArray())
        GameAssetInstaller(ZipAssetTree(base), gameRoot).install()

        val delta = newBundle(
            "CSV/x.txt" to "X".toByteArray(),
            "update-delta.txt" to "../evil.txt\nCSV//double.txt\nCSV/ok.txt\n\n".toByteArray(),
        )
        ZipAssetTree(delta).use { tree ->
            assertEquals("两条非法行与坏条目名同一个通道", 2, tree.rejectedEntryCount)
            assertEquals(listOf("CSV/ok.txt"), tree.deltaDeletions())
        }
        // 计数只是**交给调用方的闸**（app 侧 rejected 大于 0 就整包拒装）；解包器自己不猜，
        // 剩下那条合法行照样执行 —— 这样「表里混进一行坏名字」不会变成整轮失败。
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()
        assertFalse("合法行删掉了", File(gameRoot, "CSV/ok.txt").exists())
        assertEquals("X", File(gameRoot, "CSV/x.txt").readText())
        assertEquals(1, result.deletedFiles)
    }

    @Test
    fun `表里指向表自己那一条算非法`() {
        ZipAssetTree(newBundle("update-delta.txt" to "update-delta.txt\nCSV/a.txt\n".toByteArray())).use { tree ->
            assertEquals(1, tree.rejectedEntryCount)
            assertEquals(listOf("CSV/a.txt"), tree.deltaDeletions())
        }
    }

    @Test
    fun `表内路径按条目名口径归一，行尾 CRLF 与井号都收`() {
        val text = "CSV\\back.txt\r\n./CSV/dot.txt\r\n/CSV/abs.txt\r\nCSV/#hash.txt\r\nCSV/back.txt\r\n"
        ZipAssetTree(newBundle("update-delta.txt" to text.toByteArray())).use { tree ->
            assertEquals(0, tree.rejectedEntryCount)
            assertEquals(
                listOf("CSV/back.txt", "CSV/dot.txt", "CSV/abs.txt", "CSV/#hash.txt"),
                tree.deltaDeletions(),
            )
        }
    }

    @Test
    fun `表内不做 trim：路径两头的空格属于文件名本身`() {
        ZipAssetTree(newBundle("update-delta.txt" to "CSV/lead .txt\nCSV/trail .txt\n   \n".toByteArray())).use { tree ->
            assertEquals(0, tree.rejectedEntryCount)
            assertEquals(listOf("CSV/lead .txt", "CSV/trail .txt"), tree.deltaDeletions())
        }
    }

    @Test
    fun `保护名单里的四条一个都不许删`() {
        seedDisk()
        val delta = newBundle(
            "CSV/live.txt" to "L".toByteArray(),
            "update-delta.txt" to (
                "sav/save01.sav\n" +
                    "update/data-version.txt\n" +
                    "update/data-digest.txt\n" +
                    "emuera.config\n" +
                    ".install.lock\n" +
                    "CSV/sav/x.txt\n" +
                    "CSV/dead.txt\n"
                ).toByteArray(),
        )
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals("只有表里最后那条真被删", 1, result.deletedFiles)
        assertEquals("六条撞保护名单：跳过并计数", 6, result.blockedDeletions)
        assertEquals("存档没动", "SAVE", File(gameRoot, "sav/save01.sav").readText())
        assertEquals("更新状态没动", "6", File(gameRoot, "update/data-version.txt").readText())
        assertEquals("摘要没动", "DIGEST", File(gameRoot, "update/data-digest.txt").readText())
        assertEquals("玩家改过的配置没动", "MY CONFIG", File(gameRoot, "emuera.config").readText())
        assertEquals("锁文件没动", "", File(gameRoot, ".install.lock").readText())
        assertEquals("名字撞车的二级 sav 也不删", "NESTED", File(gameRoot, "CSV/sav/x.txt").readText())
        assertFalse("表内且合法的那条删掉了", File(gameRoot, "CSV/dead.txt").exists())
    }

    @Test
    fun `解包器落不了盘的东西它也删不着`() {
        seedDisk()
        val delta = newBundle(
            "update-delta.txt" to "font/my.ttf\nprofile/mine.txt\ndat/whatever.bin\n".toByteArray(),
        )
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals(0, result.deletedFiles)
        assertEquals("三条例外全计受阻", 3, result.blockedDeletions)
        assertEquals("字体还在", "FONT", File(gameRoot, "font/my.ttf").readText())
        assertEquals("玩家自定义还在", "PROFILE", File(gameRoot, "profile/mine.txt").readText())
        assertEquals("原版 dat 还在", "DAT", File(gameRoot, "dat/whatever.bin").readText())
    }

    @Test
    fun `包内也带了同一条就按新内容兑现，删除表不生效`() {
        seedDisk()
        File(gameRoot, "CSV/x.txt").writeText("OLD")
        val delta = newBundle(
            "CSV/x.txt" to "NEW".toByteArray(),
            "update-delta.txt" to "CSV/x.txt\n".toByteArray(),
        )
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals("新内容落盘", 1, result.copiedFiles)
        assertEquals("同一条又被删 ⇒ 按包内兑现，不计删除也不计受阻", 0, result.deletedFiles)
        assertEquals(0, result.blockedDeletions)
        assertEquals("NEW", File(gameRoot, "CSV/x.txt").readText())
    }

    @Test
    fun `产物对不能被删除表清空`() {
        val one = newBundleOf(erbcEntries(bytes(2_000, seed = 5)) + deltaList("erbc.v1/program.erbc\n"))
        GameAssetInstaller(ZipAssetTree(one), gameRoot).install()
        val program = File(gameRoot, "erbc.v1/program.erbc").readBytes()

        // 只带删除表、不带任何产物的差量包：想「这一版把产物删掉」是不成立的形态。
        val delta = newBundle(
            "CSV/a.txt" to "A".toByteArray(),
            "update-delta.txt" to "erbc.v1/program.erbc\nerbc.v1/program.meta\n".toByteArray(),
        )
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals(0, result.deletedFiles)
        assertEquals(2, result.blockedDeletions)
        assertArrayEquals("产物还在盘上（装载侧才有的起来）", program, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertTrue(File(gameRoot, "erbc.v1/program.meta").isFile)
    }

    @Test
    fun `目录名进删除表不递归删，只计受阻`() {
        seedDisk()
        File(gameRoot, "resources/olddir").mkdirs()
        File(gameRoot, "resources/olddir/inner.txt").writeText("INNER")
        val delta = newBundle("update-delta.txt" to "resources/olddir\n".toByteArray())
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals(0, result.deletedFiles)
        assertEquals(1, result.blockedDeletions)
        assertEquals("INNER", File(gameRoot, "resources/olddir/inner.txt").readText())
    }

    @Test
    fun `盘上没有的条目是幂等，既不算删也不算受阻`() {
        seedDisk()
        val delta = newBundle("update-delta.txt" to "CSV/never-existed.txt\n".toByteArray())
        val result = GameAssetInstaller(ZipAssetTree(delta), gameRoot, forceReplace = true).install()

        assertEquals(0, result.deletedFiles)
        assertEquals(0, result.blockedDeletions)
    }

    // ------------------------------------------------------------------
    // 三、差量档的产物对闸
    // ------------------------------------------------------------------

    @Test
    fun `差量档只带 program 没带侧车就动盘之前拒`() {
        val program1 = bytes(4_000, seed = 31)
        val base = newBundleOf(erbcEntries(program1) + deltaList(""))
        GameAssetInstaller(ZipAssetTree(base), gameRoot).install()

        val bad = newBundleOf(
            listOf("erbc.v1/program.erbc" to bytes(4_000, seed = 32)) + deltaList("CSV/old.txt\n"),
        )
        val e = assertThrows(AssetInstallException::class.java) {
            GameAssetInstaller(ZipAssetTree(bad), gameRoot, forceReplace = true).install()
        }
        assertTrue("要看得出是半对产物：${e.message}", e.message!!.contains("没带侧车"))
        assertArrayEquals("动盘之前就被拒 ⇒ 盘上停在上一版", program1, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertFalse("不许留下中间文件", File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse("也不许留下备份", File(gameRoot, "erbc.v1/program.erbc.bak").exists())
    }

    @Test
    fun `差量档只带侧车同样被拒`() {
        val meta = BootMetadata().withHeader(programSha256 = "-").encodeText().toByteArray()
        val bad = newBundleOf(listOf("erbc.v1/program.meta" to meta) + deltaList(""))
        val e = assertThrows(AssetInstallException::class.java) {
            GameAssetInstaller(ZipAssetTree(bad), gameRoot, forceReplace = true).install()
        }
        assertTrue("要看得出是半对产物：${e.message}", e.message!!.contains("没带"))
        assertFalse(File(gameRoot, "erbc.v1/program.meta").exists())
    }

    @Test
    fun `全量档不带产物对不被这道闸拦住（零回归）`() {
        // 只有语料、没有 erbc.v1 的包（改 CSV 不发产物的老发法）：不是差量档，闸门不开火。
        val full = newBundle("CSV/a.txt" to "A".toByteArray())
        val result = GameAssetInstaller(ZipAssetTree(full), gameRoot, forceReplace = true).install()
        assertEquals(1, result.copiedFiles)
    }

    // ------------------------------------------------------------------
    // 四、文件级摘要
    // ------------------------------------------------------------------

    @Test
    fun `全量档落第一次就把摘要按逐行形状写出来`() {
        val base = newBundle(
            "resources/c.webp" to "CCC".toByteArray(),
            "CSV/a.txt" to "A".toByteArray(),
            "emuera.config" to "默认配置".toByteArray(),
        )
        val result = GameAssetInstaller(ZipAssetTree(base), gameRoot, recordDataVersion = 7L).install()
        assertNull("写顺利就不该有原因", result.recordProblem)

        val file = GameAssetInstaller.dataDigestFile(gameRoot)
        assertEquals(File(gameRoot, "update/data-digest.txt"), file)
        assertEquals(
            "eratw-data-digest 1\n" +
                "version 7\n" +
                "entries 2\n" +
                "CSV/a.txt\t1\t${sha("A")}\n" +
                "resources/c.webp\t3\t${sha("CCC")}\n",
            file.readText(Charsets.UTF_8),
        )

        // 体检：一致时空问题；配置被玩家改了也不算问题（它根本不在表里）。
        assertEquals(emptyList<String>(), GameAssetInstaller.checkDataDigest(gameRoot))
        File(gameRoot, "emuera.config").writeText("玩家自己改的配置")
        assertEquals(emptyList<String>(), GameAssetInstaller.checkDataDigest(gameRoot))

        // 盘上被动过 ⇒ 报出来（差量资格该在这一拍被判掉，走全量）。
        File(gameRoot, "CSV/a.txt").delete()
        assertEquals(listOf("CSV/a.txt：摘要说在，盘上没有"), GameAssetInstaller.checkDataDigest(gameRoot))
        File(gameRoot, "CSV/a.txt").writeText("A")
        File(gameRoot, "resources/c.webp").writeText("C")
        assertEquals(
            listOf("resources/c.webp：长度不符，摘要 3 B，盘上 1 B"),
            GameAssetInstaller.checkDataDigest(gameRoot),
        )
    }

    @Test
    fun `同长不同内容只有按内容体检才抓得住`() {
        val base = newBundle("resources/x.webp" to "AAAA".toByteArray())
        GameAssetInstaller(ZipAssetTree(base), gameRoot, recordDataVersion = 1L).install()
        File(gameRoot, "resources/x.webp").writeText("BBBB")

        assertEquals("便宜的 stat 判据抓不住（这是口径，不是漏）", emptyList<String>(), GameAssetInstaller.checkDataDigest(gameRoot))
        assertEquals(
            listOf("resources/x.webp：内容不符（同长不同内容）"),
            GameAssetInstaller.checkDataDigest(gameRoot, verifyContent = true),
        )
    }

    @Test
    fun `差量档并表：写过的更新、删掉的移除、其余原样带过来`() {
        val base = newBundle(
            "CSV/keep.txt" to "KEEP".toByteArray(),
            "CSV/dead.txt" to "DEAD".toByteArray(),
            "CSV/change.txt" to "OLD".toByteArray(),
        )
        GameAssetInstaller(ZipAssetTree(base), gameRoot, recordDataVersion = 6L).install()
        val keepEntry = GameAssetInstaller.readDataDigest(gameRoot)!!.entries.getValue("CSV/keep.txt")

        val delta = newBundle(
            "CSV/change.txt" to "NEW".toByteArray(),
            "update-delta.txt" to "CSV/dead.txt\n".toByteArray(),
        )
        val result = GameAssetInstaller(
            ZipAssetTree(delta),
            gameRoot,
            forceReplace = true,
            recordDataVersion = 7L,
        ).install()
        assertNull(result.recordProblem)

        val digest = GameAssetInstaller.readDataDigest(gameRoot)!!
        assertEquals(7L, digest.version)
        assertEquals(
            "三条变两条：删掉一条、换掉一条、带过一条",
            setOf("CSV/keep.txt", "CSV/change.txt"),
            digest.entries.keys,
        )
        assertEquals("本轮写过的按新内容重算", 3L, digest.entries.getValue("CSV/change.txt").bytes)
        assertEquals("NEW", File(gameRoot, "CSV/change.txt").readText())
        assertEquals("没重写的条目逐字带过来", keepEntry.bytes, digest.entries.getValue("CSV/keep.txt").bytes)
        assertEquals(keepEntry.sha256Hex, digest.entries.getValue("CSV/keep.txt").sha256Hex)
        assertEquals(emptyList<String>(), GameAssetInstaller.checkDataDigest(gameRoot, verifyContent = true))
    }

    @Test
    fun `差量档而盘上没有摘要就不写半张表`() {
        val delta = newBundle(
            "CSV/a.txt" to "A".toByteArray(),
            "update-delta.txt" to "CSV/gone.txt\n".toByteArray(),
        )
        val result = GameAssetInstaller(
            ZipAssetTree(delta),
            gameRoot,
            forceReplace = true,
            recordDataVersion = 9L,
        ).install()
        assertEquals(1, result.copiedFiles)
        assertNull("不算失败，只是下一轮还得走全量", result.recordProblem)
        assertFalse("半张表更坏：宁可不写", GameAssetInstaller.dataDigestFile(gameRoot).exists())
        assertNull(GameAssetInstaller.readDataDigest(gameRoot))
        assertNull("体检：没记过就是 null，不是「零问题」", GameAssetInstaller.checkDataDigest(gameRoot))
    }

    @Test
    fun `不给版本号就一个摘要字节都不写（首启档零回归）`() {
        val base = newBundle("CSV/a.txt" to "A".toByteArray())
        val first = GameAssetInstaller(ZipAssetTree(base), gameRoot).install()
        assertEquals(1, first.copiedFiles)
        assertNull(first.recordProblem)
        assertFalse("首启档不该造 update 目录", File(gameRoot, "update").exists())

        // 更新档但调用方没给版本号（老代码的路径）：照旧只落数据，不写摘要。
        GameAssetInstaller(ZipAssetTree(base), gameRoot, forceReplace = true).install()
        assertFalse(File(gameRoot, "update").exists())
    }

    @Test
    fun `读不懂的摘要一律按没记过处理`() {
        val base = newBundle("CSV/a.txt" to "A".toByteArray())
        GameAssetInstaller(ZipAssetTree(base), gameRoot, recordDataVersion = 3L).install()
        val file = GameAssetInstaller.dataDigestFile(gameRoot)

        // 逐条踩形状：魔数、条数、列数、路径形状、重复行、摘要形状。
        for (broken in listOf(
            "eratw-data-digest 2\nversion 3\nentries 0\n",
            "eratw-data-digest 1\nversion abc\nentries 0\n",
            "eratw-data-digest 1\nversion 3\n",
            "eratw-data-digest 1\nversion 3\nentries 2\nCSV/a.txt\t1\t${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nCSV/a.txt;1;${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nsav/save01.sav\t1\t${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nCSV/../sav/x\t1\t${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nemuera.config\t1\t${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nCSV/a.txt\t1\tzz\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nCSV/a.txt\t-1\t${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 2\nCSV/a.txt\t1\t${sha("A")}\nCSV/a.txt\t1\t${sha("A")}\n",
            "eratw-data-digest 1\nversion 3\nentries 1\nCSV/a.txt\t1\t${sha("A")}\n多出来的一行\n",
        )) {
            file.writeText(broken)
            assertNull("不合形状就整份作废：${broken.take(40)}", GameAssetInstaller.readDataDigest(gameRoot))
            assertNull(GameAssetInstaller.checkDataDigest(gameRoot))
        }

        // 反过来：CRLF 的行尾读得进（发布侧手搓一份是常见事故）。
        file.writeText(
            "eratw-data-digest 1\r\nversion 3\r\nentries 1\r\nCSV/a.txt\t1\t${sha("A")}\r\n",
        )
        val digest = GameAssetInstaller.readDataDigest(gameRoot)
        assertEquals(3L, digest!!.version)
        assertEquals(setOf("CSV/a.txt"), digest.entries.keys)
        assertEquals(emptyList<String>(), GameAssetInstaller.checkDataDigest(gameRoot))
    }

    @Test
    fun `摘要文件的形状与保护名单同源`() {
        // 三个判据彼此一致，否则「发布侧生成」与「设备侧读写」会在边界上分叉。
        assertTrue(GameAssetInstaller.deletionIsProtected("sav/a.sav"))
        assertTrue(GameAssetInstaller.deletionIsProtected("update/data-digest.txt"))
        assertTrue(GameAssetInstaller.deletionIsProtected("CSV/sav/a.txt"))
        assertTrue(GameAssetInstaller.deletionIsProtected("emuera.config"))
        assertTrue(GameAssetInstaller.deletionIsProtected(".install.lock"))
        assertFalse(GameAssetInstaller.deletionIsProtected("CSV/配置.txt"))

        assertTrue(GameAssetInstaller.deletionWithinAssetUniverse("CSV/a.txt"))
        assertTrue(GameAssetInstaller.deletionWithinAssetUniverse("erbc.v1/program.meta"))
        assertFalse(GameAssetInstaller.deletionWithinAssetUniverse("font/a.ttf"))
        assertFalse(GameAssetInstaller.deletionWithinAssetUniverse("profile/x"))
        assertFalse(GameAssetInstaller.deletionWithinAssetUniverse("a.txt"))

        assertFalse(GameAssetInstaller.recordedInDigest("emuera.config"))
        assertFalse(GameAssetInstaller.recordedInDigest("CSV/tab\t.txt"))
        assertTrue(GameAssetInstaller.recordedInDigest("CSV/a.txt"))
    }

    @Test
    fun `摘要里的中间文件不留痕`() {
        val base = newBundle("CSV/a.txt" to "A".toByteArray())
        GameAssetInstaller(ZipAssetTree(base), gameRoot, recordDataVersion = 4L).install()
        GameAssetInstaller(ZipAssetTree(base), gameRoot, forceReplace = true, recordDataVersion = 5L).install()
        val updateDir = File(gameRoot, "update")
        assertEquals(
            "只剩正式名，改名是原子的",
            listOf("data-digest.txt"),
            updateDir.listFiles()!!.map { it.name },
        )
        assertEquals(5L, GameAssetInstaller.readDataDigest(gameRoot)!!.version)
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /**
     * 铺一个「玩家已经用了一阵」的游戏根：存档、更新状态、改过的配置、锁文件、
     * 名字撞车的二级 `sav`、解包器管不着的 `font` / `profile` / `dat`。
     */
    private fun seedDisk() {
        for ((rel, content) in listOf(
            "sav/save01.sav" to "SAVE",
            "update/data-version.txt" to "6",
            "update/data-digest.txt" to "DIGEST",
            "emuera.config" to "MY CONFIG",
            ".install.lock" to "",
            "CSV/sav/x.txt" to "NESTED",
            "CSV/dead.txt" to "DEAD",
            "font/my.ttf" to "FONT",
            "profile/mine.txt" to "PROFILE",
            "dat/whatever.bin" to "DAT",
        )) {
            val file = File(gameRoot, rel)
            file.parentFile?.mkdirs()
            file.writeText(content)
        }
    }

    private fun newBundle(vararg entries: Pair<String, ByteArray>): File =
        zipTo(temp.newFile(), entries.toList())

    /** 差量控制条目（名字固定取设备侧常量，别让测试自己抄一份字面量抄歪）。 */
    private fun deltaList(text: String): Pair<String, ByteArray> =
        ZipAssetTree.DELTA_LIST_NAME to text.toByteArray(Charsets.UTF_8)

    private fun newBundleOf(entries: List<Pair<String, ByteArray>>): File =
        zipTo(temp.newFile(), entries)

    /** 产物两条：program 与自述其 sha 的侧车（配平，配对事务才走得通）。 */
    private fun erbcEntries(program: ByteArray): List<Pair<String, ByteArray>> = listOf(
        "erbc.v1/program.erbc" to program,
        "erbc.v1/program.meta" to BootMetadata()
            .withHeader(programSha256 = sha256OfBytes(program))
            .encodeText()
            .toByteArray(),
    )

    /** STORED 条目：长度与 CRC 由写侧显式给出，读侧才拿得到确定的解压后字节数。 */
    private fun zipTo(target: File, entries: List<Pair<String, ByteArray>>): File {
        ZipOutputStream(target.outputStream().buffered()).use { out ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
                out.putNextEntry(entry)
                out.write(bytes)
                out.closeEntry()
            }
        }
        return target
    }

    private fun sha(text: String): String = sha256OfBytes(text.toByteArray(Charsets.UTF_8))

    private fun sha256OfBytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    /** 可复现的伪随机字节（与 GameAssetInstallerTest 同口径，不用 kotlin.random）。 */
    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }
}
