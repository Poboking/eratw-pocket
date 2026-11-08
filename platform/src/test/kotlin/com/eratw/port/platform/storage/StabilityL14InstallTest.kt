package com.eratw.port.platform.storage

import com.eratw.port.platform.config.ConfigFileStore
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L14 稳定性：解包器在**资产树边界**上的两条健壮化。
 *
 * 1. `walkTree` 只接受单段文件名（[GameAssetInstaller] 的 `requireSafeAssetName`）：
 *    `..`、`../x`、`a/b` 这类名字会让 `File(gameRoot, path)` 指到游戏根之外。真机
 *    `AssetManager.list` 只回单段名，这里是 [GameAssetTree] 接口契约的兜底；坏产物必须
 *    **整包拒绝**（可读文案），不能静默跳过，也不能写出游戏根。`v1..2.csv` 里的连续点号
 *    是合法文件名字符，必须有反例证明没被误杀。
 * 2. 盘上 `emuera.config` 超过 [ConfigFileStore.MAX_CONFIG_BYTES] 时，
 *    `mergeConfigUpgrades` 整份跳过（每轮启动都在锁内跑，全量 `readBytes()` 会 OOM），
 *    解包本身照常成功；上限内的配置仍按缺键补齐（正对照，防止闸门写死）。
 *
 * 证伪方式：删掉 `requireSafeAssetName` 的调用，前两条红（变成落盘成功/写出根外）；
 * 把「合法点号」用例里的 `v1..2.csv` 改判成非法，第三条红；删掉 `length()` 闸门，
 * 第四条红（超大配置会被读入并写入 `NEWKEY`）。
 */
class StabilityL14InstallTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    private fun install(tree: GameAssetTree): GameAssetInstaller.Result =
        GameAssetInstaller(tree, gameRoot).install()

    @Test
    fun `条目名为上级目录时整包拒绝`() {
        val tree = MapAssetTree(dirs = mapOf("CSV" to listOf("..")), files = emptyMap())

        val e = assertThrows(AssetInstallException::class.java) { install(tree) }

        assertTrue("要点名条目名非法：${e.message}", e.message!!.contains("条目名非法"))
        assertFalse("拒绝的包不能在根下建目录", File(gameRoot, "CSV").exists())
    }

    @Test
    fun `条目名带路径分隔符时不写出游戏根外`() {
        // 若不拦，`File(gameRoot, "CSV/../../evil-l14.txt")` 归一化后落在 files/ 下（根外）。
        val escaped = File(gameRoot.parentFile, "evil-l14.txt")
        val tree = MapAssetTree(
            dirs = mapOf("CSV" to listOf("../../evil-l14.txt")),
            files = mapOf("CSV/../../evil-l14.txt" to "evil".toByteArray()),
        )

        val e = assertThrows(AssetInstallException::class.java) { install(tree) }

        assertTrue("要点名条目名非法：${e.message}", e.message!!.contains("条目名非法"))
        assertFalse("资产名不得把文件写到游戏根之外", escaped.exists())
    }

    @Test
    fun `文件名中间的连续点号是合法名字`() {
        val payload = "id,name\n1,2\n".toByteArray()
        val tree = MapAssetTree(
            dirs = mapOf("CSV" to listOf("v1..2.csv")),
            files = mapOf("CSV/v1..2.csv" to payload),
        )

        val result = install(tree)

        assertEquals(1, result.copiedFiles)
        assertArrayEquals(payload, File(gameRoot, "CSV/v1..2.csv").readBytes())
    }

    @Test
    fun `盘上配置超过上限时跳过缺键补齐但解包照常成功`() {
        gameRoot.mkdirs()
        val config = File(gameRoot, ConfigFileStore.FILE_NAME)
        config.writeBytes(ByteArray(ConfigFileStore.MAX_CONFIG_BYTES.toInt() + 1) { 'A'.code.toByte() })
        val before = config.length()
        val tree = MapAssetTree(
            dirs = mapOf("CSV" to listOf("a.csv")),
            files = mapOf(
                "CSV/a.csv" to "x".toByteArray(),
                ConfigFileStore.FILE_NAME to "NEWKEY:1\n".toByteArray(),
            ),
        )

        val result = install(tree)

        assertEquals("配置跳过不能影响正常资产", 1, result.copiedFiles)
        assertEquals("超大配置不得被重写", before, config.length())
        assertFalse(
            "超大配置不得被追加缺键",
            config.readBytes().decodeToString().contains("NEWKEY"),
        )
        assertFalse(File(gameRoot, ConfigFileStore.FILE_NAME + ".tmp").exists())
    }

    @Test
    fun `上限内的配置仍按缺键补齐`() {
        gameRoot.mkdirs()
        val config = File(gameRoot, ConfigFileStore.FILE_NAME)
        config.writeBytes("A:1\n".toByteArray())
        val tree = MapAssetTree(
            dirs = emptyMap(),
            files = mapOf(ConfigFileStore.FILE_NAME to "A:1\nB:2\n".toByteArray()),
        )

        install(tree)

        assertEquals("A:1\nB:2\n", config.readText())
    }

    /**
     * 纯内存资产树：`dirs` 是「目录 → 直接子项名」，`files` 是「路径 → 字节」。
     * 故意不经过真实磁盘，才能构造 `AssetManager.list` 在真机上不会返回的畸形条目名。
     */
    private class MapAssetTree(
        private val dirs: Map<String, List<String>>,
        private val files: Map<String, ByteArray>,
    ) : GameAssetTree {

        override fun openFile(path: String): InputStream? =
            files[path]?.let { ByteArrayInputStream(it) }

        override fun listDir(path: String): List<String> = dirs[path].orEmpty()

        override fun fileSize(path: String): Long? = files[path]?.size?.toLong()
    }
}
