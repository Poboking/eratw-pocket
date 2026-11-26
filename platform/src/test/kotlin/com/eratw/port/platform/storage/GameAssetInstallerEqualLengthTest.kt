package com.eratw.port.platform.storage

import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 换代中「长度不变但内容变了」的文本数据文件必须按内容重拷（首启档）。
 *
 * 根因（玩家反馈 2026-11-18）：261017 → 261111 把 `CSV/Palam.csv` 的
 * `33,眠姦`（繁）改成 `33,眠奸`（简）—— 两字各 3 字节 UTF-8，**整文件同为 228 字节**；
 * 同时 `plugins/tw_csv_chs.xml` 删掉繁体键 `<k>眠姦</k>`（长度也变了，按长度正常更新）。
 * 覆盖安装后若长度判据把等长的 `Palam.csv` 判成「已装好」跳过，`PALAMNAME` 仍是繁体
 * `眠姦`，新翻译表只有简体键 → `PALAM_TR` 查不到 → 上屏「未能找到眠姦翻译」。
 * 这是新旧语料混装，长度判据对等长换代先天失明。
 *
 * 钉住的是 [GameAssetInstaller] 首启档对 [GameAssetInstaller.CONTENT_CHECK_DIRS]
 * 的逐字节对账：等值替换要重拷、内容相同要跳过、非文本目录不叠加内容读、让位档不越界。
 * 与 [GameAssetInstallerTest] 分文件是给 detekt LargeClass 让路。
 */
class GameAssetInstallerEqualLengthTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        assetsRoot = temp.newFolder("assets")
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    /** 模拟 `33,眠姦` → `33,眠奸`：UTF-8 下两者等长，仅内容不同。 */
    private fun palam(name: String): ByteArray = "0,快Ｃ\n33,$name\n".toByteArray(Charsets.UTF_8)

    /** 与真实现象一致：等长换代必须把盘上旧名换成新名，否则新旧语料混装。 */
    @Test
    fun `CSV名字表等长换代按内容重拷_繁体眠姦换成简体眠奸`() {
        val old = palam("眠姦")
        val new = palam("眠奸")
        assertEquals("前提：两代必须等长，否则本用例退化为长度判据就能覆盖", old.size, new.size)
        asset("CSV/Palam.csv", old)
        install()
        assertArrayEquals(old, File(gameRoot, "CSV/Palam.csv").readBytes())

        asset("CSV/Palam.csv", new)
        val result = install()

        assertEquals("等长换代必须识别内容变化并重拷", 1, result.copiedFiles)
        assertArrayEquals("盘上必须换成新一代，否则 PALAM_TR 查繁体键失败", new, File(gameRoot, "CSV/Palam.csv").readBytes())
    }

    /** 翻译表本身换代若也等长，同样不能被长度判据放过。 */
    @Test
    fun `plugins翻译表等长换代按内容重拷`() {
        val old = "<p><k>眠姦</k><v>眠奸</v></p>".toByteArray(Charsets.UTF_8)
        // 值换成另一个同样 2 字的词，保持整行等长。
        val new = "<p><k>眠姦</k><v>睡眠</v></p>".toByteArray(Charsets.UTF_8)
        assertEquals("前提：等长", old.size, new.size)
        asset("plugins/tw_csv_chs.xml", old)
        install()

        asset("plugins/tw_csv_chs.xml", new)
        val result = install()

        assertEquals("翻译表等长换代须重拷", 1, result.copiedFiles)
        assertArrayEquals(new, File(gameRoot, "plugins/tw_csv_chs.xml").readBytes())
    }

    /**
     * 压缩条目（`fileSize()` 拿不到长度）下同样要按内容认出等长换代。
     * `contentStale()` 不依赖长度，不能因为资产是 DEFLATE 就退化成「存在即已装」。
     */
    @Test
    fun `压缩条目等长换代仍按内容重拷`() {
        val old = palam("眠姦")
        val new = palam("眠奸")
        assertEquals("前提：等长", old.size, new.size)
        asset("CSV/Palam.csv", old)
        GameAssetInstaller(DirAssetTree(assetsRoot, hideSizes = true), gameRoot).install()

        asset("CSV/Palam.csv", new)
        val result = GameAssetInstaller(DirAssetTree(assetsRoot, hideSizes = true), gameRoot).install()

        assertEquals("拿不到长度时也必须靠内容对账识别等长换代", 1, result.copiedFiles)
        assertArrayEquals(new, File(gameRoot, "CSV/Palam.csv").readBytes())
    }

    /** 幂等：内容一致时逐字节对账不能把每次冷启都变成全量重写。 */
    @Test
    fun `内容相同则跳过_逐字节对账不破坏幂等`() {
        val data = palam("眠奸")
        asset("CSV/Palam.csv", data)
        install()

        assertEquals("第二轮内容一致必须零重拷", 0, install().copiedFiles)
    }

    /**
     * 反向证明：非文本目录（resources）的等长换代仍按长度判据跳过。
     * 钉住修复范围没有扩大到图片音频（那些目录每次冷启逐字节读不可接受）。
     */
    @Test
    fun `非文本目录等长换代仍按长度跳过_避免全树逐字节读`() {
        val a = ByteArray(256) { 'A'.code.toByte() }
        val b = ByteArray(256) { 'B'.code.toByte() }
        asset("resources/some.img", a)
        install()

        asset("resources/some.img", b)
        val result = install()

        assertEquals("resources 不在内容对账目录内，等长应跳过", 0, result.copiedFiles)
        assertArrayEquals(a, File(gameRoot, "resources/some.img").readBytes())
    }

    /**
     * 让位档：盘上数据版本更高时，内容判据不得把 APK 内旧语料盖回盘上。
     *
     * 这里用翻译表（plugins 目录）而不是名字表（CSV 下的 csv）来钉这条纪律：2026-11-20 起
     * 名字表有**一等例外** —— 名字表按编译期 erbc 的按名查表口径耦合，盘上错一代会让
     * GETNUM 回 -1、CSVCFLAG 抛「下标 -1 越界」直接挡在开局，故名字表**越过让位档**以随包
     * 为准（见 GameAssetInstaller.nameTableStale 与 GameAssetInstallerNameTableHealTest）。
     * 翻译表没有这条耦合（查不到只少一句译名），让位档照旧保护盘上更新的那份。
     */
    @Test
    fun `让位档下内容不同的翻译表不覆盖盘上更高版本数据`() {
        val apkBytes = "<k>眠奸</k>".toByteArray(Charsets.UTF_8)
        val diskBytes = "<k>眠姦</k>".toByteArray(Charsets.UTF_8)
        asset("plugins/tw_csv_chs.xml", apkBytes)
        val target = File(gameRoot, "plugins/tw_csv_chs.xml").apply {
            parentFile?.mkdirs()
            writeBytes(diskBytes)
        }

        GameAssetInstaller(
            DirAssetTree(assetsRoot),
            gameRoot,
            recordDataVersion = 10L,
            diskDataVersion = 20L,
        ).install()

        assertArrayEquals("盘上更高版本数据不该被 APK 覆盖", diskBytes, target.readBytes())
    }

    private fun install(): GameAssetInstaller.Result =
        GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot).install()

    private fun asset(rel: String, bytes: ByteArray) {
        File(assetsRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    /**
     * 临时目录当 assets 根：默认取真实长度（等价 STORED 条目）；
     * [hideSizes] 打开后 `fileSize` 恒为 null（等价 DEFLATE 压缩条目）。
     */
    private class DirAssetTree(private val root: File, private val hideSizes: Boolean = false) :
        GameAssetTree {
        override fun openFile(path: String): InputStream? {
            val file = File(root, path)
            return if (file.isFile) file.inputStream() else null
        }

        override fun listDir(path: String): List<String> {
            val dir = File(root, path)
            return if (dir.isDirectory) dir.list().orEmpty().sorted() else emptyList()
        }

        override fun fileSize(path: String): Long? {
            if (hideSizes) return null
            val file = File(root, path)
            return if (file.isFile) file.length() else null
        }
    }
}
