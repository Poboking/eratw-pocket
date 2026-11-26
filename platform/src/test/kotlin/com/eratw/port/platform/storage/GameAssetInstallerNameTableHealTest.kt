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
 * 名字表自愈（[GameAssetInstaller] 的 `nameTableStale`）的**行为级**定向单测。
 *
 * 钉的是 2026-11-20 真机崩溃那一条链：盘上 `CSV/CFLAG.csv` 少了随包里有的一行
 * ⇒ erbc 里 `GETNUM(CFLAG, "居住必要信赖度")` 回 `-1` ⇒ `CSVCFLAG(NO, -1)` 抛
 * 「下标 -1 越界」把回合中止，玩家进不去游戏、换存档也没用。
 *
 * 让位档的旧纪律是「盘上数据版本更高 ⇒ 一个字节都不碰」，名字表要开例外就必须只有
 * 一个方向：**随包认得的下标，盘上对不上号（缺行或改名）就按随包补齐；随包没有的下标
 * （数据包新增行）一律不动**。本文件三条用例分别钉「缺条目要补」「多条目不动」
 * 「改名也要补」。
 */
class GameAssetInstallerNameTableHealTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        assetsRoot = temp.newFolder("assets")
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    @Test
    fun `让位档下盘上 CSV 缺随包有的条目时按随包内容补齐`() {
        // 盘上这份少了 33 号那一行 —— 正是真机上让 GETNUM 回 -1 的形态。
        val apkCsv = csvOf("1,既成事实", "32,大家候補", "33,居住必要信赖度", "34,家賃")
        val diskCsv = csvOf("1,既成事实", "32,大家候補", "34,家賃")
        asset("CSV/CFLAG.csv", apkCsv)
        onDisk("CSV/CFLAG.csv", diskCsv)

        val result = install(diskDataVersion = HIGHER_THAN_BUNDLED)

        assertTrue("缺条目的名字表必须越过让位档补齐", result.copiedFiles >= 1)
        assertArrayEquals(
            "补齐后盘上必须与随包逐字节一致（GETNUM 才能查到 33 号名字）",
            apkCsv,
            File(gameRoot, "CSV/CFLAG.csv").readBytes(),
        )
    }

    @Test
    fun `让位档下盘上 CSV 比随包多条目时一个字节都不动`() {
        // 数据包投递的更新名字表：比随包多一行 —— 让位档的本职，不许被自愈顺手打回旧版。
        val apkCsv = csvOf("1,既成事实", "32,大家候補")
        val diskCsv = csvOf("1,既成事实", "32,大家候補", "33,居住必要信赖度", "35,数据包新增")
        asset("CSV/CFLAG.csv", apkCsv)
        onDisk("CSV/CFLAG.csv", diskCsv)

        val result = install(diskDataVersion = HIGHER_THAN_BUNDLED)

        assertEquals("盘上条目更全时不得重写", 0, result.copiedFiles)
        assertArrayEquals("盘上的新版名字表必须原样保住", diskCsv, File(gameRoot, "CSV/CFLAG.csv").readBytes())
    }

    @Test
    fun `让位档下盘上 CSV 把条目改名时也按随包补齐`() {
        // 改名与缺行在运行期同一个后果：GETNUM 按名字换下标，盘上 33 号换成别的译名，
        // erbc 里问的原名就查不到 ⇒ -1 ⇒ CSVCFLAG 崩。判据必须把两者当同一件事。
        val apkCsv = csvOf("1,既成事实", "33,居住必要信赖度")
        val diskCsv = csvOf("1,既成事实", "33,居住必要信赖度改")
        asset("CSV/CFLAG.csv", apkCsv)
        onDisk("CSV/CFLAG.csv", diskCsv)

        val result = install(diskDataVersion = HIGHER_THAN_BUNDLED)

        assertEquals("改名等价于查不到，必须越过让位档补齐", 1, result.copiedFiles)
        assertArrayEquals(
            "补齐后盘上必须与随包逐字节一致（GETNUM 才能查到 33 号原名）",
            apkCsv,
            File(gameRoot, "CSV/CFLAG.csv").readBytes(),
        )
    }

    @Test
    fun `让位档下盘上 CSV 多出随包没有的下标时不动（只增不改的反面）`() {
        // 随包认得的每个下标在盘上名字都对得上，盘上多出来的 35 号是数据包新增 —— 不许盖掉。
        val apkCsv = csvOf("1,既成事实", "33,居住必要信赖度")
        val diskCsv = csvOf("1,既成事实", "33,居住必要信赖度", "35,数据包新增")
        asset("CSV/CFLAG.csv", apkCsv)
        onDisk("CSV/CFLAG.csv", diskCsv)

        val result = install(diskDataVersion = HIGHER_THAN_BUNDLED)

        assertEquals("随包认得的下标全都对得上时不得重写", 0, result.copiedFiles)
        assertArrayEquals("盘上多出的新增行必须原样保住", diskCsv, File(gameRoot, "CSV/CFLAG.csv").readBytes())
    }

    private fun install(diskDataVersion: Long = -1L): GameAssetInstaller.Result = GameAssetInstaller(
        DirAssetTree(assetsRoot),
        gameRoot,
        recordDataVersion = BUNDLED_VERSION,
        diskDataVersion = diskDataVersion,
    ).install()

    private fun asset(rel: String, bytes: ByteArray) {
        File(assetsRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun onDisk(rel: String, bytes: ByteArray) {
        File(gameRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    private fun csvOf(vararg lines: String): ByteArray =
        (lines.joinToString(separator = "\n") + "\n").toByteArray(Charsets.UTF_8)

    /** 临时目录当 APK assets：缺文件返回 null，长度取真实字节数（等价 STORED 条目）。 */
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

    private companion object {
        const val BUNDLED_VERSION = 22L
        const val HIGHER_THAN_BUNDLED = 24L
    }
}
