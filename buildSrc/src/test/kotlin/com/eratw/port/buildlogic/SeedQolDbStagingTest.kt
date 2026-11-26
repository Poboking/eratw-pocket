package com.eratw.port.buildlogic

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.GradleException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 预置 qol_data.db（`-PeratwSeedQolDb`，「跳过首次建图」包）的 staging 叠加单测。
 *
 * 覆盖的四条风险面：
 * ① 覆盖必须「先 delete 再 copy」—— staging 的 `plugins/` 是语料硬链接农场，
 *   直接写目标 = 改共享根语料（AGENTS 铁律）；这里用真硬链接复现并断言语料字节未变。
 * ② `package.report` 的账要能对上：目标原不存在 vs 0 B 占位 vs 5 B 旧件，三种
 *   替换量（replacedBytes / replacedExisting）各自决定 entries-plugins 要不要 +1。
 * ③ seed 缺失/为空必须在构建期挡（否则「预置了空库」在报告里看起来还是成功）。
 * ④ noCompress 后缀表固定含 `db`：预置库不在游戏根里，靠语料 0 B 文件扫出来是侥幸。
 */
class SeedQolDbStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seedFile(name: String = "qol_data.db", text: String = "seed-db-bytes"): File =
        tmp.newFile(name).apply { writeText(text) }

    private fun stagedTarget(outRoot: File): File = File(outRoot, "plugins/qol_data.db")

    @Test
    fun `无旧目标时整份写入且 sha 与字节数匹配`() {
        val seed = seedFile()
        val outRoot = tmp.newFolder("staging")

        val overlay = applySeedQolDbOverlay(outRoot, seed)

        val target = stagedTarget(outRoot)
        assertTrue("目标应被创建", target.isFile)
        assertEquals(seed.readBytes().toList(), target.readBytes().toList())
        assertEquals(seed.length(), overlay.bytes)
        assertEquals(sha256Of(seed), overlay.sha256)
        assertFalse("原目标不存在", overlay.replacedExisting)
        assertEquals(0L, overlay.replacedBytes)
    }

    @Test
    fun `旧目标 0 B 占位时按替换处理且 replacedBytes 为 0`() {
        val seed = seedFile()
        val outRoot = tmp.newFolder("staging")
        val placeholder = stagedTarget(outRoot).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(0))
        }
        assertEquals(0L, placeholder.length())

        val overlay = applySeedQolDbOverlay(outRoot, seed)

        assertTrue("旧目标存在（0 B 占位）", overlay.replacedExisting)
        assertEquals(0L, overlay.replacedBytes)
        assertEquals(seed.length(), overlay.bytes)
        assertEquals(seed.readText(), stagedTarget(outRoot).readText())
    }

    @Test
    fun `旧目标是语料硬链接时先断链再写 语料字节不被改写`() {
        val seed = seedFile()
        val outRoot = tmp.newFolder("staging")
        val corpusPlugins = tmp.newFolder("corpus-plugins")
        val corpusDb = File(corpusPlugins, "qol_data.db").apply { writeText("12345") }
        val target = stagedTarget(outRoot).apply { parentFile.mkdirs() }
        // 尽量用真硬链接复现 staging 的真实形态；文件系统不支持时退回同内容拷贝（不跳过用例）。
        val linked = try {
            Files.createLink(target.toPath(), corpusDb.toPath())
            true
        } catch (_: Throwable) {
            Files.copy(corpusDb.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            false
        }
        assertEquals(5L, target.length())

        val overlay = applySeedQolDbOverlay(outRoot, seed)

        assertEquals(5L, overlay.replacedBytes)
        assertTrue(overlay.replacedExisting)
        // 关键断言：共享根语料一个字节都不许动（直接写硬链接就会改坏它）。
        assertEquals("12345", corpusDb.readText())
        assertEquals(5L, corpusDb.length())
        assertEquals(seed.readText(), target.readText())
        if (linked) {
            assertFalse(
                "覆盖后目标必须是新 inode，不能仍与语料同链",
                Files.isSameFile(target.toPath(), corpusDb.toPath()),
            )
        }
    }

    @Test
    fun `重复叠加幂等且第二次按替换旧目标计字节`() {
        val seed = seedFile()
        val outRoot = tmp.newFolder("staging")

        val first = applySeedQolDbOverlay(outRoot, seed)
        val second = applySeedQolDbOverlay(outRoot, seed)

        assertEquals(first.sha256, second.sha256)
        assertEquals(seed.length(), second.bytes)
        assertTrue("第二次目标已存在", second.replacedExisting)
        assertEquals(seed.length(), second.replacedBytes)
        assertEquals(seed.readText(), stagedTarget(outRoot).readText())
    }

    @Test
    fun `seed 缺失或为空时判红并提示生成器`() {
        val missing = File(tmp.root, "no-such-qol_data.db")
        val missingError = assertThrows(GradleException::class.java) {
            applySeedQolDbOverlay(tmp.newFolder("missing"), missing)
        }
        assertTrue(
            "错误文案应提示生成器：${missingError.message}",
            missingError.message.orEmpty().contains("devtools/gen_qol_seeddb.py"),
        )

        val empty = tmp.newFile("empty.db")
        assertEquals(0L, empty.length())
        val emptyError = assertThrows(GradleException::class.java) {
            applySeedQolDbOverlay(tmp.newFolder("empty"), empty)
        }
        assertTrue(
            "错误文案应提示生成器：${emptyError.message}",
            emptyError.message.orEmpty().contains("devtools/gen_qol_seeddb.py"),
        )
    }

    @Test
    fun `后缀表固定含 db 即使游戏根里没有任何 db 文件`() {
        val root = tmp.newFolder("game")
        File(root, "CSV").mkdirs()
        File(root, "CSV/a.csv").writeText("x")
        File(root, "emuera.config").writeText("k:v")
        assertTrue("前提：游戏根里没有 .db 文件", root.walkTopDown().none { it.name.endsWith(".db") })

        val suffixes = bundledSuffixes(root, listOf("CSV"), listOf("emuera.config"))

        assertTrue("预置库后缀必须固定进 noCompress：$suffixes", "db" in suffixes)
        assertTrue("parts 固定进表", "parts" in suffixes)
        assertTrue("report 固定进表", "report" in suffixes)
        assertTrue("扫描出的语料后缀仍要在：$suffixes", "csv" in suffixes)
        assertTrue("配置后缀仍要在：$suffixes", "config" in suffixes)
    }

    @Test
    fun `gameRoot 为 null 时后缀表仍为空表`() {
        assertTrue(bundledSuffixes(null, stagedDataDirs(includeBadFrames = true), stagedConfigFiles).isEmpty())
    }
}
