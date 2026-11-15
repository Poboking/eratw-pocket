package com.eratw.port.platform.storage

import com.eratw.port.core.loader.BootMetadata
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 随包解包「让位档」（CONTRACT-BOOTUNPACK）的**行为级**定向单测。
 *
 * 与 `UnpackSuppressPolicyTest`（core 纯函数矩阵）不同，这里真的在临时目录上跑
 * [GameAssetInstaller.install]，钉的是「盘上数据版本更高时一个字节都不写」这件事本身，
 * 而不是判据函数返回了什么。反向证明撤掉 [stepNeeded] 里的让位分支时，本文件第 1、2、
 * 4 条必须判红。
 *
 * 复刻的循环：盘上是数据包更新过的新语料、APK 里还是旧语料 ⇒ 首启档按长度比对把
 * 每一条都覆盖回旧版（真机上约 919 MiB），且 digest / 版本标记随解包回写回拨，
 * 于是每轮冷启再来一遍。让位档把这条链从装载相位掐断。
 */
class GameAssetInstallerDeferTest {

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
    fun `盘上数据版本更高时长度不同的语料一个字节都不重写`() {
        val oldCsv = bytes(100, seed = 3)
        val newCsv = bytes(200, seed = 4)
        asset("CSV/a.csv", oldCsv)
        onDisk("CSV/a.csv", newCsv)

        val result = install(diskDataVersion = 23L)

        assertEquals("让位档下已在位的资产一律不碰", 0, result.copiedFiles)
        assertEquals(0L, result.copiedBytes)
        assertArrayEquals("盘上新版语料必须原样保住", newCsv, File(gameRoot, "CSV/a.csv").readBytes())
    }

    @Test
    fun `不传盘上版本时逐字回到首启档长度判据`() {
        // 对照面：老调用点（diskDataVersion 缺省 -1）行为与加入让位档之前完全一致。
        // 这一条同时证明上一条不是「本来就不会拷」的假绿。
        val oldCsv = bytes(100, seed = 3)
        val newCsv = bytes(200, seed = 4)
        asset("CSV/a.csv", oldCsv)
        onDisk("CSV/a.csv", newCsv)

        val result = install()

        assertTrue("首启档按长度不符就该重拷", result.copiedFiles >= 1)
        assertArrayEquals(oldCsv, File(gameRoot, "CSV/a.csv").readBytes())
    }

    @Test
    fun `同版或盘上更旧时不让位换 APK 的语料照常落盘`() {
        val oldCsv = bytes(100, seed = 3)
        val newCsv = bytes(200, seed = 4)
        asset("CSV/a.csv", oldCsv)
        onDisk("CSV/a.csv", newCsv)

        val sameVersion = install(diskDataVersion = 22L)
        assertTrue("同版重装按长度判据重拷", sameVersion.copiedFiles >= 1)
        assertArrayEquals(oldCsv, File(gameRoot, "CSV/a.csv").readBytes())

        val olderDisk = install(diskDataVersion = 21L)
        assertEquals("盘上更旧也照常解包；第二轮已在位即跳过", 0, olderDisk.copiedFiles)
    }

    @Test
    fun `让位档下目标缺失仍补拷不让手删变成永久缺文件`() {
        val a = bytes(100, seed = 5)
        val b = bytes(120, seed = 6)
        asset("CSV/a.csv", a)
        asset("CSV/b.csv", b)
        onDisk("CSV/a.csv", bytes(200, seed = 7))

        val result = install(diskDataVersion = 23L)

        assertEquals("只补缺的那一条", 1, result.copiedFiles)
        assertArrayEquals("在位的那条不许被覆盖", bytes(200, seed = 7), File(gameRoot, "CSV/a.csv").readBytes())
        assertArrayEquals(b, File(gameRoot, "CSV/b.csv").readBytes())
    }

    @Test
    fun `更新档优先于让位档包里有就一律重写`() {
        val oldCsv = bytes(100, seed = 3)
        val newCsv = bytes(200, seed = 4)
        asset("CSV/a.csv", oldCsv)
        onDisk("CSV/a.csv", newCsv)

        val result = install(diskDataVersion = 23L, forceReplace = true)

        assertTrue("forceReplace 不被让位档拦住", result.copiedFiles >= 1)
        assertArrayEquals(oldCsv, File(gameRoot, "CSV/a.csv").readBytes())
    }

    @Test
    fun `配对产物在让位档下整对不碰不留下旧程序配新侧车`() {
        val oldProgram = bytes(1_000, seed = 11)
        val newProgram = bytes(3_000, seed = 12)
        asset(PROGRAM_ASSET, oldProgram)
        asset(META_ASSET, meta(sha256(oldProgram)).toByteArray())
        onDisk(PROGRAM_ASSET, newProgram)
        onDisk(META_ASSET, meta(sha256(newProgram)).toByteArray())

        val result = install(diskDataVersion = 23L)

        assertEquals("program 与侧车都不许被 APK 旧版覆盖", 0, result.copiedFiles)
        assertArrayEquals(newProgram, File(gameRoot, PROGRAM_ASSET).readBytes())
        assertEquals(meta(sha256(newProgram)), File(gameRoot, META_ASSET).readText())
    }

    @Test
    fun `配对产物缺一条时整对让位不拿 APK 旧版侧车去补`() {
        // 反向证明的第二个判红点：撤掉 stepNeeded 的 pairArtifact 整对让位分支，
        // 让位档就会把缺的 meta 用 APK 旧版补上，留下「新 program + 旧 meta」错配。
        val oldProgram = bytes(1_000, seed = 11)
        val newProgram = bytes(3_000, seed = 12)
        asset(PROGRAM_ASSET, oldProgram)
        asset(META_ASSET, meta(sha256(oldProgram)).toByteArray())
        onDisk(PROGRAM_ASSET, newProgram)

        val result = install(diskDataVersion = 23L)

        assertEquals("配对产物缺一条也不补：宁可让更新通道全量自愈", 0, result.copiedFiles)
        assertEquals(false, File(gameRoot, META_ASSET).exists())
        assertArrayEquals(newProgram, File(gameRoot, PROGRAM_ASSET).readBytes())
    }

    @Test
    fun `配对产物按首启档长度判据确实会重拷对照面`() {
        // 同上一条，但不传盘上版本 ⇒ 长度不同必须整对重装（配对事务的另一半）。
        val oldProgram = bytes(1_000, seed = 11)
        val newProgram = bytes(3_000, seed = 12)
        asset(PROGRAM_ASSET, oldProgram)
        asset(META_ASSET, meta(sha256(oldProgram)).toByteArray())
        onDisk(PROGRAM_ASSET, newProgram)
        onDisk(META_ASSET, meta(sha256(newProgram)).toByteArray())

        val result = install()

        assertTrue("首启档会整对重拷，让位档才保得住", result.copiedFiles >= 2)
        assertArrayEquals(oldProgram, File(gameRoot, PROGRAM_ASSET).readBytes())
    }

    @Test
    fun `缺省盘上版本时同长文件仍然跳过老调用点零行为`() {
        val csv = bytes(100, seed = 13)
        asset("CSV/a.csv", csv)
        onDisk("CSV/a.csv", csv)

        val result = install()

        assertEquals(0, result.copiedFiles)
    }

    private fun install(
        diskDataVersion: Long = -1L,
        forceReplace: Boolean = false,
        recordDataVersion: Long = BUNDLED_VERSION,
    ): GameAssetInstaller.Result = GameAssetInstaller(
        DirAssetTree(assetsRoot),
        gameRoot,
        forceReplace = forceReplace,
        recordDataVersion = recordDataVersion,
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

    private fun meta(programSha: String): String =
        BootMetadata().withHeader(programSha256 = programSha).encodeText()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }

    /** 临时目录当 APK assets：三个方法都按「不存在就返回空」，长度取真实字节数（等价 STORED）。 */
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
        const val PROGRAM_ASSET = "erbc.v1/program.erbc"
        const val META_ASSET = "erbc.v1/program.meta"
    }
}
