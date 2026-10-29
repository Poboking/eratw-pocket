package com.eratw.port.platform.storage

import com.eratw.port.core.loader.BootMetadata
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 首启解包器（M8-ASSETSINSTALL）—— APK assets 树用临时目录顶替，纯 JVM 跑，不建 `Context`。
 *
 * 钉的是 [GameAssetInstaller] 的六条对外契约：
 * 1. assets 里没有 `erbc.v1` 时**一个文件都不碰**（不能凭空造目录，否则 `GameStorage` 的根选择会误判）；
 * 2. 单条 `program.erbc` 与侧车一起落盘，并按侧车声明的 `program-sha256` 核对；
 * 3. 分片按 `program.parts` 的**行序**拼接（不是文件名字典序）；
 * 4. SHA 不配对：清中间文件、抛可读错误，**旧产物保留**（上一版配对可降级）；
 * 5. 配对事务：program/meta 任一步失败，整对回滚到上一版（不留「新 program + 旧 meta」）；
 * 6. 幂等：第二次全跳过，目标被截断则重拷；`emuera.config` 永不覆盖。
 *
 * 第 10 例拿 core 自己的 [BootMetadata] 编码器当 oracle：解包器读的「第 3 行头部」
 * 与侧车格式必须同源，core 换写法时这条会先红。
 */
class GameAssetInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var gameRoot: File

    @org.junit.Before
    fun setUp() {
        assetsRoot = temp.newFolder("assets")
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `assets 里没有产物时一个文件都不碰`() {
        val result = install()

        assertEquals(0, result.copiedFiles)
        assertEquals(0, result.copiedBytes)
        assertFalse("不能凭空造产物目录", File(gameRoot, "erbc.v1").exists())
    }

    @Test
    fun `分片清单为空时报可读错误`() {
        asset("erbc.v1/program.parts", "\n  \n".toByteArray())

        val e = assertThrows(AssetInstallException::class.java) { install() }

        assertTrue("要指名是清单的问题：${e.message}", e.message!!.contains("空清单"))
        assertFalse(File(gameRoot, "erbc.v1/program.erbc").exists())
    }

    @Test
    fun `单条产物与侧车一起落盘并核对 sha`() {
        val program = bytes(300_000, seed = 1)
        asset("erbc.v1/program.erbc", program)
        asset("erbc.v1/program.meta", meta(sha256(program)).toByteArray())

        val result = install()

        assertEquals(2, result.copiedFiles)
        assertTrue("侧车声明了 program-sha256 就必须核对通过", result.programSha256Verified)
        assertArrayEquals(program, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertFalse("中间文件不能留", File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
    }

    @Test
    fun `分片按清单行序拼接而不是文件名字典序`() {
        // 名字字典序是 partA/partB/partZ，清单行序是 Z/A/B —— 目标必须是 Z+A+B。
        val z = bytes(1_000, seed = 7)
        val a = bytes(2_000, seed = 11)
        val b = bytes(500, seed = 13)
        asset("erbc.v1/program.partZ.erbc", z)
        asset("erbc.v1/program.partA.erbc", a)
        asset("erbc.v1/program.partB.erbc", b)
        val manifest = listOf("program.partZ.erbc" to z, "program.partA.erbc" to a, "program.partB.erbc" to b)
            .joinToString(separator = "\n", postfix = "\n") { "${it.first}\t${it.second.size}" }
        asset("erbc.v1/program.parts", manifest.toByteArray())
        asset("erbc.v1/program.meta", meta(sha256(z + a + b)).toByteArray())

        val progress = mutableListOf<GameAssetInstaller.Progress>()
        val result = install(progress = progress)

        assertTrue(result.programSha256Verified)
        assertArrayEquals(z + a + b, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertEquals(
            listOf("erbc.v1/program.partZ.erbc", "erbc.v1/program.partA.erbc", "erbc.v1/program.partB.erbc"),
            progress.take(3).map { it.label },
        )
        assertEquals(4, progress.last().total)
    }

    @Test
    fun `sha 不配对时清中间文件抛可读错误且旧产物保留`() {
        val program = bytes(50_000, seed = 3)
        asset("erbc.v1/program.erbc", program)
        // 声明的是一份完全不同的哈希（配对错，打包期就该挡住，这里兜住「推错一组」）。
        asset("erbc.v1/program.meta", meta("0".repeat(64)).toByteArray())

        val e = assertThrows(AssetInstallException::class.java) { install() }

        assertTrue("要点名不配对：${e.message}", e.message!!.contains("不配对"))
        assertFalse("首次安装本无旧产物，坏文件不能凭 rename 留盘", File(gameRoot, "erbc.v1/program.erbc").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse("回滚后不得残留备份", File(gameRoot, "erbc.v1/program.erbc.bak").exists())
        assertFalse("配对没通过，侧车也不该落地", File(gameRoot, "erbc.v1/program.meta").exists())
    }

    /**
     * 2026-10-22 审查修正的钉子：**旧产物是「上一版配对」，SHA 失败不许删它**。
     * 上一版实现 `target.delete()` 删的正是旧产物（rename 尚未执行），注释还说
     * 「删掉刚落的目标」——把可降级状态破坏掉。
     */
    @Test
    fun `sha 不配对时上一版配对保留`() {
        // 第一版装好（配对成立）。
        val v1 = bytes(30_000, seed = 41)
        asset("erbc.v1/program.erbc", v1)
        asset("erbc.v1/program.meta", meta(sha256(v1)).toByteArray())
        install()

        // 第二版资产自相矛盾（program 与侧车声明不符）→ 触发整对重装 → SHA 失败。
        val v2 = bytes(60_000, seed = 43)
        asset("erbc.v1/program.erbc", v2)
        asset("erbc.v1/program.meta", meta("0".repeat(64)).toByteArray())

        val e = assertThrows(AssetInstallException::class.java) { install() }
        assertTrue("要点名不配对：${e.message}", e.message!!.contains("不配对"))

        // 盘上回到 v1 配对：program 与侧车都是旧版，无任何中间/备份残留。
        assertArrayEquals(v1, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertEquals(sha256(v1), BootMetadata.parse(File(gameRoot, "erbc.v1/program.meta").readText()).programSha256)
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.bak").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.meta.bak").exists())
    }

    /**
     * 配对事务的另一半：中途失败（本例 meta 资产「存在但打不开」）必须整对回滚。
     * 上一版实现没有事务，会留下「新 program + 旧 meta」；若新旧 meta 字节数恰好相同，
     * 下一轮字节相符双双跳过，错配就永久化。
     */
    @Test
    fun `中途失败回滚到上一版配对`() {
        val v1 = bytes(30_000, seed = 51)
        asset("erbc.v1/program.erbc", v1)
        asset("erbc.v1/program.meta", meta(sha256(v1)).toByteArray())
        install()

        // 第二版配对正确，但 meta 资产打不开（fileSize 拿得到、openFile 抛不了）——
        // 模拟「拷贝进行到一半 IO 断掉」的事故形态。
        val v2 = bytes(70_000, seed = 53)
        asset("erbc.v1/program.erbc", v2)
        asset("erbc.v1/program.meta", meta(sha256(v2)).toByteArray())
        val broken = FailOpenTree(DirAssetTree(assetsRoot), failPath = "erbc.v1/program.meta")

        val e = assertThrows(AssetInstallException::class.java) { install(broken) }
        assertTrue("要点名缺资产：${e.message}", e.message!!.contains("program.meta"))

        // 整对回到 v1：不许出现「新 program + 旧 meta」。
        assertArrayEquals(v1, File(gameRoot, "erbc.v1/program.erbc").readBytes())
        assertEquals(sha256(v1), BootMetadata.parse(File(gameRoot, "erbc.v1/program.meta").readText()).programSha256)
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.tmp").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.meta.tmp").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.erbc.bak").exists())
        assertFalse(File(gameRoot, "erbc.v1/program.meta.bak").exists())
    }

    @Test
    fun `第二次全跳过而目标被截断后重拷`() {
        val program = bytes(40_000, seed = 5)
        asset("erbc.v1/program.erbc", program)
        asset("erbc.v1/program.meta", meta(sha256(program)).toByteArray())

        assertEquals(2, install().copiedFiles)

        val second = install()
        assertEquals("已在位就不该再写", 0, second.copiedFiles)
        assertEquals(2, second.skippedFiles)
        assertEquals(0L, second.copiedBytes)
        assertFalse("没有拷贝就没有可核对的产物", second.programSha256Verified)

        // 截断 = 上一轮被打断的残留：字节数对不上就重拷，侧车随 program 一起换。
        File(gameRoot, "erbc.v1/program.erbc").writeBytes(program.copyOf(10))
        val third = install()
        assertEquals(2, third.copiedFiles)
        assertTrue(third.programSha256Verified)
        assertArrayEquals(program, File(gameRoot, "erbc.v1/program.erbc").readBytes())
    }

    @Test
    fun `用户改过的 emuera_config 不被覆盖`() {
        asset("emuera.config", "ConfigVersion=1\nUseKeyMacro=YES\n".toByteArray())
        val userConfig = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("ConfigVersion=1\nUseKeyMacro=NO\n") // 与资产内容不同、长度也不同
        }

        val result = install()

        assertEquals("配置只拷一次", 0, result.copiedFiles)
        assertEquals("ConfigVersion=1\nUseKeyMacro=NO\n", userConfig.readText())
    }

    @Test
    fun `CSV resources sound plugins 四个目录递归落盘`() {
        val chara = bytes(1_200, seed = 17)
        val nested = bytes(300, seed = 19)
        val image = bytes(4_096, seed = 23)
        val sound = bytes(8_192, seed = 29)
        val pluginXml = "<map><p><k>a</k><v>b</v></p></map>".toByteArray()
        asset("CSV/Chara.csv", chara)
        asset("CSV/sub/Nested.csv", nested)
        asset("resources/img/title.png", image)
        asset("sound/bgm.ogg", sound)
        asset("plugins/tw_csv_chs.xml", pluginXml)

        val result = install()

        assertEquals(5, result.copiedFiles)
        assertArrayEquals(chara, File(gameRoot, "CSV/Chara.csv").readBytes())
        assertArrayEquals(nested, File(gameRoot, "CSV/sub/Nested.csv").readBytes())
        assertArrayEquals(image, File(gameRoot, "resources/img/title.png").readBytes())
        assertArrayEquals(sound, File(gameRoot, "sound/bgm.ogg").readBytes())
        assertArrayEquals(pluginXml, File(gameRoot, "plugins/tw_csv_chs.xml").readBytes())
    }

    /**
     * `plugins/` 是**运行期**读的，不是随包装饰：`ERB/SYSTEM.ERB:96-97` 的
     * `MAP_FROMXML CSV_MAP,LOADTEXT("plugins/tw_csv_chs.xml")` 在 `@INIT_NG_OR_LOAD`
     * 里每局跑一遍。少了它 `LOADTEXT` 得空串，`MAP_FROMXML` 在位置 0 抛
     * 「XML 解析错误」，开局直接中止（2026-10-22 真机 4.980 实证）。
     *
     * `bad/` 同理是**运行期**读的：`ERB/TITLE.ERB:123-158` 的 `@BADAPPLE` 每帧
     * `LOADTEXT("bad/bad_apple{count}.txt")`（1..6573）。少了它 DEMO 空帧全速循环，
     * 真机上首页被清空 + 高频抖动（2026-10-22 DevTools 实证空帧行）。
     */
    @Test
    fun `DATA_DIRS 覆盖运行期读的 plugins 目录`() {
        assertTrue(
            "DATA_DIRS 缺 plugins/：首启不会解包 MAP_FROMXML 要读的 XML",
            GameAssetInstaller.DATA_DIRS.contains("plugins"),
        )
        assertTrue(
            "DATA_DIRS 缺 bad/：首启不会解包 BADAPPLE 要读的文本帧",
            GameAssetInstaller.DATA_DIRS.contains("bad"),
        )
        assertEquals(
            "CSV/resources/sound/plugins/bad 五类语料目录不许被顺手删掉",
            listOf("CSV", "resources", "sound", "plugins", "bad"),
            GameAssetInstaller.DATA_DIRS,
        )
    }

    @Test
    fun `压缩条目拿不到大小时目标存在即跳过`() {
        asset("CSV/Chara.csv", bytes(700, seed = 31))
        val tree = DirAssetTree(assetsRoot, hideSizes = true)

        assertEquals(1, install(tree).copiedFiles)
        assertEquals("压缩条目不能为了比大小再解压一遍", 0, install(tree).copiedFiles)
        assertEquals(700L, File(gameRoot, "CSV/Chara.csv").length())
    }

    @Test
    fun `侧车头部解析与 core 编码器同源`() {
        val program = bytes(4_096, seed = 37)
        val sha = sha256(program)
        val metaText = meta(sha)
        // oracle：core 自己的读侧解析出同一个声明；且头部就落在前 4 KiB 内（解包器只读这么多）。
        assertEquals(sha, BootMetadata.parse(metaText).programSha256)
        assertTrue(metaText.indexOf("program-sha256") in 0 until GameAssetInstaller.HEADER_BYTES)
        asset("erbc.v1/program.erbc", program)
        asset("erbc.v1/program.meta", metaText.toByteArray())

        val result = install()

        assertTrue("解包器读到的声明必须与 core 逐字相同", result.programSha256Verified)
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun install(
        tree: GameAssetTree = DirAssetTree(assetsRoot),
        progress: MutableList<GameAssetInstaller.Progress>? = null,
    ): GameAssetInstaller.Result =
        GameAssetInstaller(tree, gameRoot).install { progress?.add(it) }

    private fun asset(rel: String, bytes: ByteArray) {
        File(assetsRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    /** 侧车文本：用 core 的编码器写，头部两行（engine / program-sha256）由它保证。 */
    private fun meta(programSha: String?): String =
        BootMetadata().withHeader(programSha256 = programSha).encodeText()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    /** 可复现的伪随机字节（不用 kotlin.random：core 层禁用同一套 API，测试也保持一致口径）。 */
    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }

    /**
     * 临时目录当 assets 根：路径按 `/` 分，[fileSize] 取真实文件长度（等价 STORED 条目）。
     * [hideSizes] 模拟压缩条目（`AssetManager.openFd` 必抛 → 拿不到长度）。
     */
    private class DirAssetTree(
        private val root: File,
        private val hideSizes: Boolean = false,
    ) : GameAssetTree {

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

    /**
     * 「[fileSize] 拿得到、[openFile] 打不开」——模拟真机上资产条目存在但流打开失败的
     * 事故形态（zip 损坏 / fd 耗尽）。注意 [GameAssetInstaller] 的存在性判定先看
     * [fileSize]，所以这里必须两个方法口径不一致才能触发「拷贝进行中缺资产」。
     */
    private class FailOpenTree(
        private val inner: GameAssetTree,
        private val failPath: String,
    ) : GameAssetTree {

        override fun openFile(path: String): InputStream? =
            if (path == failPath) null else inner.openFile(path)

        override fun listDir(path: String): List<String> = inner.listDir(path)

        override fun fileSize(path: String): Long? = inner.fileSize(path)
    }
}
