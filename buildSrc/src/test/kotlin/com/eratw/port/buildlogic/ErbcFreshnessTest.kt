package com.eratw.port.buildlogic

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CMP-66 新鲜度对账（[verifyErbcFreshness]）与判据纯函数（[compilerDigestOf] / [expectedErbccVersion]）
 * 的定向单测。夹具是一份「最小自洽」的 erbc 目录：manifest 里的每个校验字段都由真实文件反推，
 * 单个用例只破坏一处，其余保持自洽 —— 任何一条被改坏的断言都会指向对应的 reject 分支。
 */
class ErbcFreshnessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 捕 warn 文案的 Logger 替身：其余级别全数委托给 Gradle 真 logger。 */
    class RecordingLogger : Logger by Logging.getLogger("ErbcFreshnessTest") {
        val warnings = mutableListOf<String>()

        override fun warn(message: String) {
            warnings += message
        }

        override fun warn(message: String, vararg objects: Any?) {
            warnings += message
        }
    }

    private class Fixture(
        val repoRoot: File,
        val corpusRoot: File,
        val erbcDir: File,
        val logger: RecordingLogger,
    )

    private var seq = 0

    /**
     * 造自洽夹具：repoRoot（Version.kt + 另一个 .kt）、corpusRoot（含带空格路径与子目录）、
     * erbcDir（manifest + program.erbc）。manifestOverrides 键值对按 `key value` 覆写对应行，
     * 值为 null 时整行删除（测「缺行」分支）。
     */
    private fun fixture(
        manifestOverrides: Map<String, String?> = emptyMap(),
        corpusExtra: Map<String, ByteArray> = emptyMap(),
        diskRemove: List<String> = emptyList(),
    ): Fixture {
        val repo = tmp.newFolder("repo-${seq++}")
        File(repo, "core-vm/src/main/kotlin/com/eratw/port/core/Version.kt").apply {
            parentFile.mkdirs()
            writeText("package com.eratw.port.core\n\nobject Version {\n    const val VERSION: String = \"9.9.9-test\"\n}\n")
        }
        File(repo, "core-vm/src/main/kotlin/com/eratw/port/core/Extra.kt").apply {
            parentFile.mkdirs()
            writeText("package com.eratw.port.core\n\nobject Extra\n")
        }

        val corpus = tmp.newFolder("corpus-${seq++}")
        val sources = linkedMapOf(
            "a.ERB" to "println A\n".toByteArray(),
            "DLC FUNCTION.ERB" to "println B\n".toByteArray(),
            "sub/b.erh" to "println C\n".toByteArray(),
        )
        sources.forEach { (rel, bytes) ->
            File(corpus, rel).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }
        // 只删磁盘不删清单 → 测「语料文件缺失」分支
        diskRemove.forEach { rel -> File(corpus, rel).delete() }
        corpusExtra.forEach { (rel, bytes) ->
            File(corpus, rel).apply { parentFile.mkdirs() }.writeBytes(bytes)
        }

        // program.erbc：magic "ERBC" + u16 小端容器版本 2 + 任意载荷
        val program = "ERBC".toByteArray(Charsets.US_ASCII) + byteArrayOf(2, 0) + ByteArray(37) { it.toByte() }
        val erbc = tmp.newFolder("erbc-${seq++}")
        val programFile = File(erbc, "program.erbc")
        programFile.writeBytes(program)

        val digest = compilerDigestOf(repo).first
        val lines = mutableListOf(
            "# erbc compile manifest —— 测试夹具",
            "manifest-format 2",
            "format-version 2",
            "container-magic ERBC",
            "container-version 2",
            "erbcc 9.9.9-test",
            "build-mode full",
            "compiler-digest $digest",
            "compiler-sources 2",
            "artifact erbc.v1/program.erbc ${programFile.length()} ${sha256Of(programFile)}",
            "corpus-root ${corpus.absolutePath}",
            "source-files ${sources.size}",
        )
        sources.forEach { (rel, bytes) ->
            lines += "source $rel ${bytes.size} ${MessageDigest.getInstance("SHA-256").digest(bytes).hexLower()}"
        }
        manifestOverrides.forEach { (key, value) ->
            lines.removeAll { it.startsWith("$key ") }
            value?.let { lines += "$key $it" }
        }
        File(erbc, "manifest.txt").writeText(lines.joinToString("\n", postfix = "\n"))
        return Fixture(repo, corpus, erbc, RecordingLogger())
    }

    private fun verify(fixture: Fixture, hard: Boolean, corpusRoot: File? = fixture.corpusRoot): String? =
        verifyErbcFreshness(fixture.erbcDir, corpusRoot, hard, fixture.repoRoot, fixture.logger)

    // ---- 成功路径 -----------------------------------------------------------

    @Test
    fun `自洽夹具走 verified 成功路径`() {
        val fx = fixture()
        val result = verify(fx, hard = true)
        assertNotNull(result)
        assertTrue(result!!.startsWith("verified(erbcc 9.9.9-test, compiler "))
        assertTrue(result.contains("container v2"))
        assertTrue(result.contains("源 3 个文件来自"))
        assertTrue(fx.logger.warnings.isEmpty())
    }

    @Test
    fun `GOLDEN_ 前缀文件不进文件集对账`() {
        val fx = fixture(corpusExtra = mapOf("GOLDEN_baseline.ERB" to "应该被忽略".toByteArray()))
        assertNotNull(verify(fx, hard = true))
    }

    @Test
    fun `manifest 无 manifest-format 行走旧格式告警但仍 verified`() {
        val fx = fixture(manifestOverrides = mapOf("manifest-format" to null))
        assertNotNull(verify(fx, hard = true))
        assertTrue(fx.logger.warnings.any { it.contains("manifest-format") })
    }

    // ---- 拒绝路径（hard 抛 GradleException，soft 返 null 且告警）--------------

    @Test
    fun `manifest_txt 缺失即拒`() {
        val fx = fixture()
        File(fx.erbcDir, "manifest.txt").delete()
        assertHardAndSoft(fx)
    }

    @Test
    fun `manifest-format 比认识的新即拒`() {
        val fx = fixture(manifestOverrides = mapOf("manifest-format" to "3"))
        assertHardAndSoft(fx, "比本打包脚本认识的")
    }

    @Test
    fun `erbcc 版本不符即拒`() {
        val fx = fixture(manifestOverrides = mapOf("erbcc" to "0.0.1-stale"))
        assertHardAndSoft(fx, "产物版本与当前编译器不一致")
    }

    @Test
    fun `erbcc 行缺失即拒`() {
        val fx = fixture(manifestOverrides = mapOf("erbcc" to null))
        assertHardAndSoft(fx, "产物版本与当前编译器不一致")
    }

    @Test
    fun `build-mode 非 full 即拒`() {
        val fx = fixture(manifestOverrides = mapOf("build-mode" to "limited"))
        assertHardAndSoft(fx, "受限产物")
    }

    @Test
    fun `compiler-digest 缺行即拒`() {
        val fx = fixture(manifestOverrides = mapOf("compiler-digest" to null))
        assertHardAndSoft(fx, "没有 compiler-digest 行")
    }

    @Test
    fun `compiler-digest 不符即拒`() {
        val fx = fixture(manifestOverrides = mapOf("compiler-digest" to "0".repeat(64)))
        assertHardAndSoft(fx, "产物由旧编译器产出")
    }

    @Test
    fun `缺 program_erbc 即拒`() {
        val fx = fixture()
        File(fx.erbcDir, "program.erbc").delete()
        assertHardAndSoft(fx, "缺 program.erbc")
    }

    @Test
    fun `container-version 与容器头不符即拒`() {
        val fx = fixture(manifestOverrides = mapOf("container-version" to "3"))
        assertHardAndSoft(fx, "清单与产物不同源")
    }

    @Test
    fun `容器头 magic 不对即拒`() {
        val fx = fixture()
        File(fx.erbcDir, "program.erbc").writeBytes("XRAY".toByteArray() + byteArrayOf(2, 0) + ByteArray(10))
        assertHardAndSoft(fx, "magic 不是 ERBC")
    }

    @Test
    fun `容器头不足 6 字节即拒`() {
        val fx = fixture()
        File(fx.erbcDir, "program.erbc").writeBytes("ER".toByteArray())
        assertHardAndSoft(fx, "读不出 6 字节头")
    }

    @Test
    fun `artifact 条目缺失即拒`() {
        val fx = fixture(manifestOverrides = mapOf("artifact" to null))
        assertHardAndSoft(fx, "artifact ... program.erbc")
    }

    @Test
    fun `artifact sha 不符即拒`() {
        val fx = fixture()
        val program = File(fx.erbcDir, "program.erbc")
        File(fx.erbcDir, "manifest.txt").apply {
            writeText(readText().replace(sha256Of(program), "e".repeat(64)))
        }
        assertHardAndSoft(fx, "sha256 不一致")
    }

    @Test
    fun `artifact 字节数不符即拒`() {
        val fx = fixture()
        val program = File(fx.erbcDir, "program.erbc")
        val sha = sha256Of(program)
        File(fx.erbcDir, "manifest.txt").apply {
            writeText(readText().replace(Regex("artifact erbc\\.v1/program\\.erbc \\d+ $sha"), "artifact erbc.v1/program.erbc 999 $sha"))
        }
        assertHardAndSoft(fx, "大小与 manifest 登记不一致")
    }

    @Test
    fun `source-files 计数与 source 行数不符即拒`() {
        val fx = fixture(manifestOverrides = mapOf("source-files" to "2"))
        assertHardAndSoft(fx, "source 条目数")
    }

    @Test
    fun `磁盘多出语料文件即拒`() {
        val fx = fixture(corpusExtra = mapOf("extra/D.ERB" to "println D\n".toByteArray()))
        assertHardAndSoft(fx, "语料文件集与产物清单不一致")
    }

    @Test
    fun `磁盘缺语料文件即拒`() {
        val fx = fixture(diskRemove = listOf("a.ERB"))
        assertHardAndSoft(fx, "语料文件集与产物清单不一致")
    }

    @Test
    fun `语料文件内容变了即拒`() {
        val fx = fixture()
        // 等长不同内容：跳过大小检查，命中 sha256 分支
        File(fx.corpusRoot, "a.ERB").writeText("println X\n")
        assertHardAndSoft(fx, "sha256 变了")
    }

    @Test
    fun `语料文件大小变了即拒`() {
        val fx = fixture()
        // 先改大小（sha 也会变，但大小检查先命中）
        File(fx.corpusRoot, "sub/b.erh").writeBytes("println C 更长\n".toByteArray())
        assertHardAndSoft(fx, "大小变了")
    }

    @Test
    fun `语料根找不到时报 version-only`() {
        val fx = fixture(manifestOverrides = mapOf("corpus-root" to "Z:/不存在的语料根"))
        val result = verify(fx, hard = true, corpusRoot = null)
        assertEquals("version-only(源的 sha 未对账：找不到语料根)", result)
        assertTrue(fx.logger.warnings.any { it.contains("找不到语料根") })
    }

    // ---- 语料补丁对账（corpus-patch 留痕行；CorpusPatches 编译期应用的出包侧回读）----

    private fun patchFixture(bad: ByteArray, fixed: ByteArray, manifestSource: String? = null): Fixture {
        val badSha = MessageDigest.getInstance("SHA-256").digest(bad).hexLower()
        val fixedSha = MessageDigest.getInstance("SHA-256").digest(fixed).hexLower()
        return fixture(
            corpusExtra = mapOf("K51.ERB" to bad),
            manifestOverrides = buildMap {
                put("source-files", "4")
                put("source K51.ERB", manifestSource ?: "${fixed.size} $fixedSha")
                put("corpus-patch", "CB-001 K51.ERB $badSha $fixedSha")
            },
        )
    }

    @Test
    fun `语料补丁_磁盘坏版清单修后版_按留痕行放行`() {
        val bad = "病灶版内容\n".toByteArray()
        val fixed = "修后版内容更长\n".toByteArray()
        val fx = patchFixture(bad, fixed)
        val result = verify(fx, hard = true)
        assertNotNull(result)
        assertTrue(result!!.contains("补丁 CB-001（对账放行 1 个目标）"))
        assertTrue(fx.logger.warnings.none { it.contains("sha256 变了") })
    }

    @Test
    fun `语料补丁_磁盘与坏版修后版都不符_照旧拒`() {
        val bad = "病灶版内容\n".toByteArray()
        val fixed = "修后版内容更长\n".toByteArray()
        val fx = patchFixture(bad, fixed)
        // 磁盘漂到第三种内容：留痕行救不了它（未知漂移必须红）
        File(fx.corpusRoot, "K51.ERB").writeBytes("第三种内容\n".toByteArray())
        assertHardAndSoft(fx, "语料改过但没重编")
    }

    @Test
    fun `语料补丁_清单源sha与留痕修后sha脱节_照旧拒`() {
        val bad = "病灶版内容\n".toByteArray()
        val fixed = "修后版内容更长\n".toByteArray()
        val otherSha = MessageDigest.getInstance("SHA-256").digest("别的产物\n".toByteArray()).hexLower()
        // manifest 的 source 行声明了与留痕行 fixed 不同的 sha：两侧对不上 ⇒ 不豁免
        val fx = patchFixture(bad, fixed, manifestSource = "${fixed.size} $otherSha")
        assertHardAndSoft(fx, "语料改过但没重编")
    }

    @Test
    fun `语料补丁_缺留痕行_坏版磁盘配修后清单_照旧拒`() {
        val bad = "病灶版内容\n".toByteArray()
        val fixed = "修后版内容更长\n".toByteArray()
        val fixedSha = MessageDigest.getInstance("SHA-256").digest(fixed).hexLower()
        // 没有 corpus-patch 行：磁盘坏版 + 清单修后版 ⇒ 照旧判「语料改过但没重编」，
        // 防止豁免口被静默放宽成「磁盘清单 sha 对不上也放行」。
        val fx = fixture(
            corpusExtra = mapOf("K51.ERB" to bad),
            manifestOverrides = mapOf("source-files" to "4", "source K51.ERB" to "${fixed.size} $fixedSha"),
        )
        assertHardAndSoft(fx, "语料改过但没重编")
    }

    /** 同一处破坏：hard 必须 [GradleException]，soft 必须返 null 且带告警。 */
    private fun assertHardAndSoft(fx: Fixture, msgPart: String? = null) {
        try {
            verify(fx, hard = true)
            fail("hard=true 应当抛 GradleException")
        } catch (e: GradleException) {
            if (msgPart != null) assertTrue("报错应含「$msgPart」：${e.message}", e.message!!.contains(msgPart))
        }
        // verify 只读不改，同一份坏夹具即可复验 soft 分支
        assertNull(verify(fx, hard = false))
        assertTrue(fx.logger.warnings.isNotEmpty())
    }

    // ---- expectedErbccVersion -----------------------------------------------

    @Test
    fun `expectedErbccVersion 正常解析`() {
        val fx = fixture()
        assertEquals("9.9.9-test", expectedErbccVersion(fx.repoRoot))
    }

    @Test
    fun `expectedErbccVersion 源文件缺失即抛`() {
        val fx = fixture()
        File(fx.repoRoot, "core-vm/src/main/kotlin/com/eratw/port/core/Version.kt").delete()
        try {
            expectedErbccVersion(fx.repoRoot)
            fail("应当抛")
        } catch (e: GradleException) {
            assertTrue(e.message!!.contains("找不到版本单源"))
        }
    }

    @Test
    fun `expectedErbccVersion 格式坏即抛`() {
        val fx = fixture()
        File(fx.repoRoot, "core-vm/src/main/kotlin/com/eratw/port/core/Version.kt").writeText("object Version\n")
        try {
            expectedErbccVersion(fx.repoRoot)
            fail("应当抛")
        } catch (e: GradleException) {
            assertTrue(e.message!!.contains("无法从"))
        }
    }

    // ---- compilerDigestOf ---------------------------------------------------

    @Test
    fun `compilerDigestOf 与手工框架一致且与创建顺序无关`() {
        val repo = tmp.newFolder("repo-digest-${seq++}")
        // 故意乱序创建，验证函数内部按相对路径字典序喂哈希
        File(repo, "core-vm/src/main/kotlin/b/B.kt").apply { parentFile.mkdirs() }.writeText("B")
        File(repo, "core-vm/src/main/kotlin/a/A.kt").apply { parentFile.mkdirs() }.writeText("A")
        File(repo, "core-vm/src/main/kotlin/a/A.txt").apply { parentFile.mkdirs() }.writeText("非 kt 不进摘要")

        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            "core-vm/src/main/kotlin/a/A.kt" to "A".toByteArray(),
            "core-vm/src/main/kotlin/b/B.kt" to "B".toByteArray(),
        ).forEach { (rel, bytes) ->
            val relBytes = rel.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(relBytes.size).array())
            digest.update(relBytes)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        val (actual, count) = compilerDigestOf(repo)
        assertEquals(digest.digest().hexLower(), actual)
        assertEquals(2, count)
    }

    @Test
    fun `compilerDigestOf 空树即抛`() {
        val repo = tmp.newFolder("repo-empty-${seq++}")
        File(repo, "core-vm/src/main/kotlin").mkdirs()
        try {
            compilerDigestOf(repo)
            fail("应当抛")
        } catch (e: GradleException) {
            assertTrue(e.message!!.contains("没有 .kt 文件"))
        }
    }

    @Test
    fun `compilerDigestOf 缺源码树即抛`() {
        val repo = tmp.newFolder("repo-missing-${seq++}")
        try {
            compilerDigestOf(repo)
            fail("应当抛")
        } catch (e: GradleException) {
            assertTrue(e.message!!.contains("找不到编译器源码树"))
        }
    }
}
