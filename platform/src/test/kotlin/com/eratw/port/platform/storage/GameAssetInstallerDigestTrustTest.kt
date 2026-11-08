package com.eratw.port.platform.storage

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * C-1 / C-3 的定向单测：**摘要的完整性**与**版本闭环**。
 *
 * 这两条是同一条链的两端：
 * - C-1 让随包解包（首启 / 换 APK）也写文件级摘要，并把同一个版本号写进 `data-version.txt`；
 * - C-3 让「盘可信」这条判据同时看**摘要版本 == 版本标记**与**表非空**。
 *
 * 因此这里钉四件事：
 * 1. `expectedVersion` 与摘要里的 version 不符 ⇒ 不可信（报问题，不是空表）；
 * 2. `entries 0` 的摘要 ⇒ 也不可信（查 0 条就报一致是假可信）；
 * 3. 旧盘（有文件已在位、没有旧摘要可继承）**不写半张表** —— 半张表比没有表更坏；
 * 4. 有旧摘要可继承时，被跳过的条目确实**留在表里**（C-1 的「覆盖到」落点）。
 *
 * 第 4 条是这次改动的关键正面用例：`written` 只装本轮拷过的文件，跳过的条目必须靠
 * 「上一份 ⊕ 本轮」的并表口径带过来；带不过来时宁可整份不写（第 3 条）。
 */
class GameAssetInstallerDigestTrustTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `摘要版本与 expectedVersion 不符按不可信报问题`() {
        val gameRoot = newRoot()
        GameAssetInstaller(ZipAssetTree(bundle("CSV/a.txt" to "A")), gameRoot, recordDataVersion = 7L)
            .install()

        assertEquals(
            "版本对上且表非空 ⇒ 一致",
            emptyList<String>(),
            GameAssetInstaller.checkDataDigest(gameRoot, expectedVersion = 7L),
        )

        val mixed = GameAssetInstaller.checkDataDigest(gameRoot, expectedVersion = 8L)
        assertNotNull("有摘要就必须给问题清单，不是 null", mixed)
        assertTrue("版本不符要报出来：$mixed", mixed!!.isNotEmpty())
        assertTrue(
            "原话要点名两个版本读数：$mixed",
            mixed.any { it.contains("版本不符") && it.contains("7") && it.contains("8") },
        )

        assertNull(
            "没摘要仍然是 null（不是「零问题」），调用方按不能走差量处理",
            GameAssetInstaller.checkDataDigest(newRoot(), expectedVersion = 7L),
        )
    }

    @Test
    fun `entries 为 0 的摘要按不可信报问题`() {
        val gameRoot = newRoot()
        val file = GameAssetInstaller.dataDigestFile(gameRoot)
        file.parentFile!!.mkdirs()
        file.writeText("eratw-data-digest 1\nversion 7\nentries 0\n")

        assertNotNull("形状合法、读得出来（0 条不在形状闸上）", GameAssetInstaller.readDataDigest(gameRoot))
        val problems = GameAssetInstaller.checkDataDigest(gameRoot)
        assertNotNull(problems)
        assertTrue("0 条必须报问题，不许报「一致」：$problems", problems!!.isNotEmpty())
        assertTrue("原话要点名「一条文件都没有」：$problems", problems.any { it.contains("0") })
    }

    @Test
    fun `已在位被跳过且没有旧摘要可继承时不写半张表`() {
        val gameRoot = newRoot()
        // 旧盘：两条资产已在位、没有 data-digest.txt（本特性之前装的盘）。
        disk(gameRoot, "CSV/a.txt", "A")
        disk(gameRoot, "resources/b.webp", "BB")
        // 随包解包：a 同长被跳过、c 是新条目 —— 只拷 1 条，表若照 written 写就只有 1 条。
        val report = GameAssetInstaller(
            ZipAssetTree(bundle("CSV/a.txt" to "A", "CSV/c.txt" to "C")),
            gameRoot,
            recordDataVersion = 5L,
        ).install()

        assertEquals(1, report.copiedFiles)
        assertEquals(1, report.skippedFiles)
        assertNotNull("不写要说清原因（否则宿主只会看到「没摘要」）", report.recordProblem)
        assertFalse("半张表更坏：宁可不写", GameAssetInstaller.dataDigestFile(gameRoot).exists())
        assertNull(GameAssetInstaller.checkDataDigest(gameRoot, expectedVersion = 5L))
    }

    @Test
    fun `已在位被跳过仍有旧摘要继承时表是完整的`() {
        val gameRoot = newRoot()
        GameAssetInstaller(ZipAssetTree(bundle("CSV/a.txt" to "A")), gameRoot, recordDataVersion = 4L)
            .install()
        val report = GameAssetInstaller(
            ZipAssetTree(bundle("CSV/a.txt" to "A", "CSV/c.txt" to "C")),
            gameRoot,
            recordDataVersion = 5L,
        ).install()

        assertNull("有旧摘要可继承 ⇒ 该写完整表：${report.recordProblem}", report.recordProblem)
        assertEquals(1, report.copiedFiles)
        assertEquals(1, report.skippedFiles)
        val digest = GameAssetInstaller.readDataDigest(gameRoot)!!
        assertEquals(5L, digest.version)
        assertEquals("跳过的 a 与刚拷的 c 都在表里", setOf("CSV/a.txt", "CSV/c.txt"), digest.entries.keys)
        assertEquals(
            emptyList<String>(),
            GameAssetInstaller.checkDataDigest(gameRoot, verifyContent = true, expectedVersion = 5L),
        )
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun newRoot(): File = File(temp.newFolder(), "eratw").apply { mkdirs() }

    private fun disk(root: File, rel: String, text: String) {
        val file = File(root, rel)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    private fun bundle(vararg entries: Pair<String, String>): File =
        zipTo(temp.newFile(), entries.map { (name, text) -> name to text.toByteArray(Charsets.UTF_8) })

    /** STORED 条目：长度与 CRC 显式给出，读侧才拿得到确定的解压后字节数。 */
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
}
