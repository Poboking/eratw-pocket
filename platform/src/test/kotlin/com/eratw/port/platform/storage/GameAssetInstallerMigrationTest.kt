package com.eratw.port.platform.storage

import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 历史出厂值的一次性迁移（安装期）—— [GameAssetInstaller] 第 7 条对外契约。
 *
 * 根因链：4.980 语料 `emuera.config:31` 显式写着 15000，平台覆盖层「只填空、不改写」
 * 永远够不到它；构建期校准只管新装/重装的随包资产，老玩家盘上那份「只拷一次、之后永不
 * 覆盖」的旧配置只能靠这条安装期迁移。钉的是：
 * 1. 只改「值仍是随包旧值」的行，BOM/CRLF/注释/行序逐字保留；
 * 2. 玩家显式改过的值（0、自定义毫秒）一律不动；
 * 3. `update/config-migrations.txt` 按规则行记账，每条规则最多执行一次（事后改回旧值不再被改写），
 *    但将来新增的规则仍能在同一份老盘上执行；
 * 4. 空表 = 完全不碰配置、也不落标记（数据包更新通道）；
 * 5. 非 UTF-8 / 超限配置整份跳过且不写标记（下次冷启动再试）。
 *
 * 与 [GameAssetInstallerTest] 分文件是给 detekt LargeClass 让路：迁移是独立契约，
 * 用例自带最小夹具比挤进 1000 行的主测试类更清楚。
 */
class GameAssetInstallerMigrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        assetsRoot = temp.newFolder("assets")
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    /** 与 `PortFactoryConfig.CONFIG_VALUE_MIGRATIONS` 同形状的一条规则（平台层单测不依赖 app）。 */
    private val loopAlertMigration = ConfigValueMigration(
        key = "MILLISECONDS FOR INFINITE LOOP WARNING",
        from = "15000",
        to = "360000",
    )

    /**
     * 根因链的安装期半边：4.980 语料 `emuera.config:31` 是 15000，
     * 只靠覆盖层的「只填空」永远改不到它；老玩家盘上那份更不会被新资产覆盖。
     */
    @Test
    fun `盘上仍为随包旧值 15000 时迁移到 360000 且其余行逐字保留`() {
        val original = "\uFEFF; 注释\r\n" +
            "ConfigVersion=1\r\n" +
            "MILLISECONDS FOR INFINITE LOOP WARNING:15000\r\n" +
            "UseKeyMacro=YES\r\n"
        asset("emuera.config", "ConfigVersion=1\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeBytes(original.toByteArray())
        }

        install(listOf(loopAlertMigration))

        assertEquals(
            "只改值：BOM、CRLF、注释、行序一个字节都不动",
            original.replace("WARNING:15000", "WARNING:360000"),
            config.readText(),
        )
        assertFalse("迁移后不许残留中间文件", File(gameRoot, "emuera.config.tmp").exists())
        val marker = File(gameRoot, GameAssetInstaller.CONFIG_MIGRATION_MARKER)
        assertTrue("一次性标记必须落下", marker.isFile)
        assertTrue(
            "标记要能读出执行过的规则",
            marker.readText().contains("MILLISECONDS FOR INFINITE LOOP WARNING:15000->360000"),
        )
    }

    /** 玩家自己调的 0（关闭）不是「随包旧值」：一个字节都不许动。 */
    @Test
    fun `玩家自己调过的值不被迁移`() {
        asset("emuera.config", "ConfigVersion=1\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("ConfigVersion=1\nMILLISECONDS FOR INFINITE LOOP WARNING:0\n")
        }

        install(listOf(loopAlertMigration))

        assertEquals(
            "0 = 玩家显式关闭，迁移必须让路",
            "ConfigVersion=1\nMILLISECONDS FOR INFINITE LOOP WARNING:0\n",
            config.readText(),
        )
        assertTrue(
            "即使没改动也落标记：本表只跑一次，之后玩家再改成 15000 属于显式选择",
            File(gameRoot, GameAssetInstaller.CONFIG_MIGRATION_MARKER).isFile,
        )
    }

    /** 标记的存在性就是「已经执行过」：玩家事后刻意改回 15000 不许被下一次冷启动改走。 */
    @Test
    fun `迁移只跑一次 玩家事后改回 15000 不再被改写`() {
        asset("emuera.config", "ConfigVersion=1\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("ConfigVersion=1\nMILLISECONDS FOR INFINITE LOOP WARNING:15000\n")
        }
        install(listOf(loopAlertMigration))
        assertTrue(config.readText().contains("WARNING:360000"))

        // 玩家在设置页把它改回 15000（显式选择），下一轮冷启动：
        config.writeText(config.readText().replace("360000", "15000"))
        install(listOf(loopAlertMigration))

        assertTrue(
            "标记存在 ⇒ 迁移表不再执行，玩家的显式值必须保留",
            config.readText().contains("WARNING:15000"),
        )
    }

    /**
     * 标记按**规则行**记账：上一版执行过的规则不再重复，但将来新增的规则（哪怕同一个键、
     * 只是历史值不同）必须还能在老盘上跑 —— 否则一次改标定就永久锁死了后续所有标定。
     */
    @Test
    fun `标记按规则记账 新增规则在已有标记的老盘上仍执行`() {
        val later = ConfigValueMigration(
            key = "MILLISECONDS FOR INFINITE LOOP WARNING",
            from = "360000",
            to = "420000",
        )
        asset("emuera.config", "ConfigVersion=1\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText("ConfigVersion=1\nMILLISECONDS FOR INFINITE LOOP WARNING:15000\n")
        }
        install(listOf(loopAlertMigration))
        assertTrue(config.readText().contains("WARNING:360000"))

        install(listOf(loopAlertMigration, later))

        assertTrue(
            "新规则必须执行：旧标记只挡住已经跑过的那条",
            config.readText().contains("WARNING:420000"),
        )
        val marker = File(gameRoot, GameAssetInstaller.CONFIG_MIGRATION_MARKER).readText()
        assertTrue(
            "旧规则行必须留在标记里（保住「改回旧值不再被改写」）",
            marker.contains("MILLISECONDS FOR INFINITE LOOP WARNING:15000->360000"),
        )
        assertTrue(
            "新规则执行后也要记账",
            marker.contains("MILLISECONDS FOR INFINITE LOOP WARNING:360000->420000"),
        )
    }

    /** 数据包更新通道不传规则（缺省空表）：盘上配置一个字节都不动，也不落标记。 */
    @Test
    fun `不传迁移表时既有配置一个字节都不动`() {
        asset("emuera.config", "ConfigVersion=1\n".toByteArray())
        val original = "ConfigVersion=1\nMILLISECONDS FOR INFINITE LOOP WARNING:15000\n"
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeText(original)
        }

        install()

        assertEquals(original, config.readText())
        assertFalse(
            "空表不落标记：将来新增迁移规则仍能在老盘上执行",
            File(gameRoot, GameAssetInstaller.CONFIG_MIGRATION_MARKER).exists(),
        )
    }

    /** 迁移纯函数的边界：BOM / CRLF / 键名大小写与空白 / 行尾注释都逐字保留。 */
    @Test
    fun `迁移纯函数保留 BOM CRLF 空白与行尾注释`() {
        val text = "\uFEFF; 头\r\n" +
            "  milliseconds for infinite loop warning : 15000 ; 旧值\r\n" +
            "UseKeyMacro=YES\r\n"
        val out = migrateConfigText(text, listOf(loopAlertMigration))

        assertEquals(1, out.applied)
        assertEquals(
            "\uFEFF; 头\r\n" +
                "  milliseconds for infinite loop warning : 360000 ; 旧值\r\n" +
                "UseKeyMacro=YES\r\n",
            out.text,
        )
    }

    /** 值匹配必须整段相等：`150000` 不能被当成 `15000` 的前缀改写。 */
    @Test
    fun `迁移只匹配完整值不误伤更长或更短的值`() {
        val text = "MILLISECONDS FOR INFINITE LOOP WARNING:150000\n" +
            "MILLISECONDS FOR INFINITE LOOP WARNING:1500\n" +
            "MILLISECONDS FOR INFINITE LOOP WARNING:15000\n"

        val out = migrateConfigText(text, listOf(loopAlertMigration))

        assertEquals(1, out.applied)
        assertEquals(
            "MILLISECONDS FOR INFINITE LOOP WARNING:150000\n" +
                "MILLISECONDS FOR INFINITE LOOP WARNING:1500\n" +
                "MILLISECONDS FOR INFINITE LOOP WARNING:360000\n",
            out.text,
        )
    }

    /**
     * 同一个键登记多条规则（不同历史值各一条）时按值整段匹配：命中哪条用哪条，
     * 不会因为「键相同」而只保留最后一条（那样第一条历史值就静默迁不动了）。
     */
    @Test
    fun `同键多条历史值规则按值各命中一条`() {
        val rules = listOf(
            loopAlertMigration,
            ConfigValueMigration("MILLISECONDS FOR INFINITE LOOP WARNING", "18000", "360000"),
        )
        val text = "MILLISECONDS FOR INFINITE LOOP WARNING:15000\n" +
            "MILLISECONDS FOR INFINITE LOOP WARNING:18000\n"

        val out = migrateConfigText(text, rules)

        assertEquals(2, out.applied)
        assertEquals(
            "MILLISECONDS FOR INFINITE LOOP WARNING:360000\n" +
                "MILLISECONDS FOR INFINITE LOOP WARNING:360000\n",
            out.text,
        )
    }

    /** 玩家关掉英文输出后，设置页会把整份配置重写成日文标签：同一历史值也要能迁。 */
    @Test
    fun `日文标签的历史值同样被迁移`() {
        val jpRule = ConfigValueMigration(
            key = "無限ループ警告までのミリ秒数",
            from = "15000",
            to = "360000",
        )
        val text = "無限ループ警告までのミリ秒数:15000\nUseKeyMacro=YES\n"

        val out = migrateConfigText(text, listOf(loopAlertMigration, jpRule))

        assertEquals("無限ループ警告までのミリ秒数:360000\nUseKeyMacro=YES\n", out.text)
        assertEquals(1, out.applied)
    }

    /** 非 UTF-8 的盘上配置与缺键补齐同策：整份跳过，且**不写标记**（下次冷启动再试）。 */
    @Test
    fun `非 UTF-8 配置迁移整份跳过且不写标记`() {
        val sjis = byteArrayOf(
            0x83.toByte(), 0x74.toByte(),
            0x83.toByte(), 0x87.toByte(),
            0x83.toByte(), 0x93.toByte(),
            0x83.toByte(), 0x67.toByte(),
        )
        asset("emuera.config", "ConfigVersion=1\n".toByteArray())
        val config = File(gameRoot, "emuera.config").apply {
            gameRoot.mkdirs()
            writeBytes(sjis)
        }

        install(listOf(loopAlertMigration))

        assertArrayEquals("非 UTF-8 配置一个字节都不许动", sjis, config.readBytes())
        assertFalse(
            "没跑成不许落标记，否则这盘配置永远没机会被迁移",
            File(gameRoot, GameAssetInstaller.CONFIG_MIGRATION_MARKER).exists(),
        )
    }

    private fun install(migrations: List<ConfigValueMigration> = emptyList()) {
        GameAssetInstaller(
            DirAssetTree(assetsRoot),
            gameRoot,
            configMigrations = migrations,
        ).install()
    }

    private fun asset(rel: String, bytes: ByteArray) {
        File(assetsRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    /** 临时目录当 assets 根：路径按 `/` 分，[fileSize] 取真实文件长度（等价 STORED 条目）。 */
    private class DirAssetTree(private val root: File) : GameAssetTree {

        override fun openFile(path: String): InputStream? {
            val file = File(root, path)
            return if (file.isFile) file.inputStream() else null
        }

        override fun listDir(path: String): List<String> {
            val dir = File(root, path)
            return if (dir.isDirectory) dir.list().orEmpty().sorted() else emptyList()
        }

        override fun fileSize(path: String): Long? {
            val file = File(root, path)
            return if (file.isFile) file.length() else null
        }
    }
}
