package com.eratw.port.buildlogic

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CorpusPatches（语料内容补丁的编译期应用）的定向单测。
 *
 * 夹具是合成语料（CRLF + BOM + 单层 tab 缩进，形状对齐 4.981 的 Nitori 目标文件，但内容是
 * 测试自造的 10 行），不读任何真语料根 —— 本组测试在没导 ERATW_* 的干净环境也要全绿。
 * 覆盖面：index 窄 schema 解析的判红、三档判定（缺席跳过 / 病灶版套用 / 已修跳过 / 漂移判红）、
 * staging 的硬链接与铁律自证、unified diff 的逐字匹配门槛与终止符/BOM 保真。
 */
class CorpusPatchesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 捕 lifecycle 文案的 Logger 替身：其余级别全数委托给 Gradle 真 logger。 */
    private class RecordingLogger : Logger by Logging.getLogger("CorpusPatchesTest") {
        val lifecycles = mutableListOf<String>()

        override fun lifecycle(message: String) {
            lifecycles += message
        }
    }

    // ---- 夹具：形状对齐 Nitori 目标（CRLF、BOM、tab 缩进），内容自造 -----------------------

    private val badLines = listOf(
        "@M_KOJO_EGG_TOP",
        "\tCALL K51_EGG_PROJECT_CHANGE(Counter)",
        "\tRETURN 1",
        "ENDIF",
        "CALL K51_EGG_SPECIAL_EVENT(Counter)",
        "\t;Check if any special events will trigger.",
        "IF RESULT",
        "\tCALLF ONCE(\"SpecialEvent\",0,51)",
        "IF CFLAG:51:K51_EGG_PROJECT_DAYS <= DAY",
        "\tRETURN 2",
    )

    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun crlfBytes(lines: List<String>, withBom: Boolean = true): ByteArray {
        val body = lines.joinToString("") { it + "\r\n" }.toByteArray(Charsets.UTF_8)
        return if (withBom) bom + body else body
    }

    /** 病灶版（合成）：第 5 行一参调用。 */
    private val badBytes: ByteArray
        get() = crlfBytes(badLines)

    /** 修后版（合成）：仅第 5 行去参，其余字节（含各行 CRLF 与 BOM）原样。 */
    private val fixedBytes: ByteArray
        get() = crlfBytes(badLines.mapIndexed { i, line ->
            if (i == 4) "CALL K51_EGG_SPECIAL_EVENT()" else line
        })

    /** LF 行尾的补丁文本（git 工作副本形态；applier 必须对补丁自己的 EOL 不敏感）。 */
    private val patchText: String
        get() = listOf(
            "--- a/TGT.ERB",
            "+++ b/TGT.ERB",
            "@@ -2,7 +2,7 @@",
            " \tCALL K51_EGG_PROJECT_CHANGE(Counter)",
            " \tRETURN 1",
            " ENDIF",
            "-CALL K51_EGG_SPECIAL_EVENT(Counter)",
            "+CALL K51_EGG_SPECIAL_EVENT()",
            " \t;Check if any special events will trigger.",
            " IF RESULT",
            " \tCALLF ONCE(\"SpecialEvent\",0,51)",
        ).joinToString("\n", postfix = "\n")

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun indexText(
        bad: String = sha(badBytes),
        fixed: String = sha(fixedBytes),
        target: String = "TGT.ERB",
        patch: String = "T-001.patch",
        root: String? = null,
        skip: String? = null,
    ): String = buildList {
        add("patches:")
        add("  - id: T-001")
        add("    target: $target")
        root?.let { add("    root: $it") }
        add("    patch: $patch")
        add("    bad-sha256: $bad")
        add("    fixed-sha256: $fixed")
        skip?.let { add("    skip-sha256: $it") }
        add("    corpus: test-corpus")
        add("    status: test")
        add("    summary: test summary")
        add("    evidence: test evidence")
    }.joinToString("\n", postfix = "\n")

    /** 游戏根目标（CSV/_Rename.csv 形态）：把 ERB 夹具的补丁头换到游戏根相对路径。 */
    private val gameTargetRel = "CSV/_Rename.csv"

    private val gamePatchText: String
        get() = patchText.replace("TGT.ERB", gameTargetRel)

    private class Fixture(
        val corpusRoot: File,
        val patchesDir: File,
        val stagingRoot: File,
        val logger: RecordingLogger,
    )

    /** 造夹具：游戏根（ERB + CSV + emuera.config）+ 补丁资产目录 + staging 空目录。 */
    private fun fixture(targetBytes: ByteArray?): Fixture {
        val root = tmp.newFolder("game")
        val corpus = File(root, "ERB").apply { mkdirs() }
        targetBytes?.let { File(corpus, "TGT.ERB").writeBytes(it) }
        File(root, "CSV").apply { mkdirs() }.resolve("x.csv").writeText("a,b\n")
        File(root, "emuera.config").writeText("Option=YES\n")
        val patches = tmp.newFolder("patches")
        return Fixture(corpus, patches, tmp.newFolder("staging"), RecordingLogger())
    }

    private fun Fixture.writeAssets(patch: String = patchText, index: String = indexText()) {
        File(patchesDir, "T-001.patch").writeText(patch)
        File(patchesDir, "index.yaml").writeText(index)
    }

    private fun Fixture.plan(): CorpusPatchPlan =
        planCorpusPatches(corpusRoot, patchesDir, stagingRoot, logger)

    // ---- index 解析 -----------------------------------------------------------------

    @Test
    fun index解析_全字段成条目() {
        val entries = parseCorpusPatchIndex(indexText())
        assertEquals(1, entries.size)
        val e = entries.single()
        assertEquals("T-001", e.id)
        assertEquals("TGT.ERB", e.target)
        assertEquals("T-001.patch", e.patch)
        assertEquals(sha(badBytes), e.badSha256)
        assertEquals(sha(fixedBytes), e.fixedSha256)
        assertEquals("test-corpus", e.corpus)
        assertEquals("test", e.status)
        assertEquals("test summary", e.summary)
        assertEquals("test evidence", e.evidence)
    }

    @Test
    fun index解析_sha字段统一小写() {
        val entries = parseCorpusPatchIndex(indexText(bad = sha(badBytes).uppercase()))
        assertEquals(sha(badBytes), entries.single().badSha256)
    }

    @Test
    fun index解析_缺字段判红() {
        val text = indexText().lineSequence()
            .filter { !it.startsWith("    summary:") }
            .joinToString("\n", postfix = "\n")
        try {
            parseCorpusPatchIndex(text)
            fail("缺 summary 必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("缺字段"))
        }
    }

    @Test
    fun index解析_sha形状坏判红() {
        try {
            parseCorpusPatchIndex(indexText(bad = "01234"))
            fail("非 64 hex 的 sha 必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("64 hex"))
        }
    }

    @Test
    fun index解析_未知行判红() {
        try {
            parseCorpusPatchIndex(indexText() + "surprise: yes\n")
            fail("schema 外的行必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("不认识的行"))
        }
    }

    @Test
    fun index解析_id重复判红() {
        val doubled = indexText() + indexText().removePrefix("patches:\n")
        try {
            parseCorpusPatchIndex(doubled)
            fail("重复 id 必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("id 重复"))
        }
    }

    @Test
    fun index解析_target带点段判红() {
        try {
            parseCorpusPatchIndex(indexText(target = "../escape.ERB"))
            fail("越出语料根的 target 必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("'/' 相对路径"))
        }
    }

    // ---- 三档判定 -------------------------------------------------------------------

    @Test
    fun 三档_目标缺席_跳过留痕原样直编() {
        val fx = fixture(targetBytes = null)
        fx.writeAssets()
        val plan = fx.plan()
        assertTrue(plan is CorpusPatchPlan.None)
        plan as CorpusPatchPlan.None
        assertEquals(1, plan.reasons.size)
        assertTrue(plan.reasons[0], plan.reasons[0].contains("T-001 不适用（目标缺席）"))
        // 跳过档不得建 staging（没有任何东西要套）
        assertEquals(0, fx.stagingRoot.listFiles()!!.size)
    }

    @Test
    fun 三档_已是修后版_跳过留痕() {
        val fx = fixture(targetBytes = fixedBytes)
        fx.writeAssets()
        val plan = fx.plan()
        assertTrue(plan is CorpusPatchPlan.None)
        plan as CorpusPatchPlan.None
        assertTrue(plan.reasons[0], plan.reasons[0].contains("T-001 不适用（已是修后版）"))
        assertEquals(0, fx.stagingRoot.listFiles()!!.size)
    }

    @Test
    fun 直编档_撤下上一轮补丁marker() {
        val fx = fixture(targetBytes = badBytes)
        fx.writeAssets()
        val patched = fx.plan()
        assertTrue(patched is CorpusPatchPlan.Staged)
        val marker = File((patched as CorpusPatchPlan.Staged).stagingDir, CORPUS_PATCHES_MARKER)
        assertTrue(marker.isFile)
        // 同一语料根（同一 staging 键）下一轮目标缺席 → 直编，旧 marker 必须被撤下
        assertTrue(File(fx.corpusRoot, "TGT.ERB").delete())
        val none = fx.plan()
        assertTrue(none is CorpusPatchPlan.None)
        assertFalse(marker.exists())
    }

    @Test
    fun 三档_sha双双不命中_漂移判红() {
        val drifted = crlfBytes(badLines + listOf("EXTRA_TRAILING"))
        val fx = fixture(targetBytes = drifted)
        fx.writeAssets()
        try {
            fx.plan()
            fail("sha 与坏版/修后版都不符必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("未知漂移"))
        }
    }

    @Test
    fun 三档_病灶版_套用且真语料一字不动() {
        val fx = fixture(targetBytes = badBytes)
        fx.writeAssets()
        val plan = fx.plan()
        assertTrue(plan is CorpusPatchPlan.Staged)
        plan as CorpusPatchPlan.Staged
        assertEquals(listOf("T-001"), plan.applied.map { it.id })
        // 编译根换成 staging 副本
        assertEquals(File(fx.stagingRoot, plan.stagingDir.name + "/ERB").canonicalPath, plan.corpusRoot.canonicalPath)
        // 副本里的目标 = 修后版逐字节
        val stagedTarget = File(plan.corpusRoot, "TGT.ERB")
        assertTrue(stagedTarget.isFile)
        assertArrayEquals(fixedBytes, stagedTarget.readBytes())
        // 铁律：真语料目标仍是病灶版原样
        assertArrayEquals(badBytes, File(fx.corpusRoot, "TGT.ERB").readBytes())
        // staging 兄弟件：CSV 与 emuera.config 都在（erbcc 侧车/ERD/关键字表全靠它们）
        assertTrue(File(plan.stagingDir, "CSV/x.csv").isFile)
        assertTrue(File(plan.stagingDir, "emuera.config").isFile)
        // 硬链接取证：非目标件与真语料同 inode（零拷贝），目标件已换成新 inode
        assertTrue(
            Files.isSameFile(
                File(fx.corpusRoot.parentFile, "CSV/x.csv").toPath(),
                File(plan.stagingDir, "CSV/x.csv").toPath(),
            ),
        )
        assertFalse(Files.isSameFile(File(fx.corpusRoot, "TGT.ERB").toPath(), stagedTarget.toPath()))
        // 留痕：marker 行形 + 日志
        val marker = File(plan.stagingDir, CORPUS_PATCHES_MARKER)
        assertEquals(
            "corpus-patch T-001 TGT.ERB ${sha(badBytes)} ${sha(fixedBytes)}\n",
            marker.readText(),
        )
        assertTrue(
            fx.logger.lifecycles.joinToString("\n"),
            fx.logger.lifecycles.any { it.contains("已应用 T-001") && it.contains(sha(badBytes)) },
        )
    }

    @Test
    fun staging键_同根同目录_异根异目录() {
        val fx = fixture(targetBytes = badBytes)
        fx.writeAssets()
        val other = tmp.newFolder("game2").resolve("ERB").apply { mkdirs() }
        File(other, "OTHER.ERB").writeBytes(badBytes)
        assertEquals(corpusStagingDir(fx.corpusRoot, fx.stagingRoot), corpusStagingDir(fx.corpusRoot, fx.stagingRoot))
        assertNotEquals(
            corpusStagingDir(fx.corpusRoot, fx.stagingRoot).name,
            corpusStagingDir(other, fx.stagingRoot).name,
        )
    }

    // ---- root=game（游戏根目标：CSV/_Rename.csv 这类与 ERB 平级的随包数据） -------------

    @Test
    fun index解析_root缺省corpus_显式game() {
        val byDefault = parseCorpusPatchIndex(indexText()).single()
        assertEquals(CorpusPatchRoot.CORPUS, byDefault.root)
        assertTrue(byDefault.skipSha256.isEmpty())
        assertEquals(CorpusPatchRoot.GAME, parseCorpusPatchIndex(indexText(root = "game")).single().root)
    }

    @Test
    fun index解析_root非法值判红() {
        try {
            parseCorpusPatchIndex(indexText(root = "erb"))
            fail("root 只认 corpus / game")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("root 只认"))
        }
    }

    @Test
    fun index解析_skip_sha形状坏判红() {
        try {
            parseCorpusPatchIndex(indexText(skip = "not-a-sha"))
            fail("skip-sha256 的形状必须与 bad/fixed 同口径")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("skip-sha256"))
        }
    }

    @Test
    fun 三档_game根病灶版_套用staging的CSV且真语料不动() {
        val fx = fixture(targetBytes = null)
        val gameRoot = fx.corpusRoot.parentFile
        val csv = File(gameRoot, gameTargetRel).apply { writeBytes(badBytes) }
        fx.writeAssets(patch = gamePatchText, index = indexText(target = gameTargetRel, root = "game"))
        val plan = fx.plan()
        assertTrue(plan is CorpusPatchPlan.Staged)
        plan as CorpusPatchPlan.Staged
        // staging 副本（erbcc 从 corpus 根的 parent 读 CSV/_Rename.csv）里的目标 = 修后版
        assertArrayEquals(fixedBytes, File(plan.stagingDir, gameTargetRel).readBytes())
        // 铁律：真语料（游戏根的 CSV）仍是病灶版原样
        assertArrayEquals(badBytes, csv.readBytes())
        val marker = File(plan.stagingDir, CORPUS_PATCHES_MARKER)
        assertEquals(
            "corpus-patch T-001 $gameTargetRel ${sha(badBytes)} ${sha(fixedBytes)}\n",
            marker.readText(),
        )
    }

    @Test
    fun 三档_game根skip版_跳过留痕不建staging() {
        val fx = fixture(targetBytes = null)
        val other = crlfBytes(badLines + listOf("EXTRA_TRAILING"))
        File(fx.corpusRoot.parentFile, gameTargetRel).writeBytes(other)
        fx.writeAssets(
            patch = gamePatchText,
            index = indexText(target = gameTargetRel, root = "game", skip = sha(other)),
        )
        val plan = fx.plan()
        assertTrue(plan is CorpusPatchPlan.None)
        assertTrue(
            (plan as CorpusPatchPlan.None).reasons.single(),
            plan.reasons.single().contains("已知无需补丁的版本"),
        )
        assertEquals(0, fx.stagingRoot.listFiles()!!.size)
    }

    @Test
    fun 出包面_game根补丁_按sha套用返回修后字节() {
        val fx = fixture(targetBytes = null)
        val gameRoot = fx.corpusRoot.parentFile
        val csv = File(gameRoot, gameTargetRel).apply { writeBytes(badBytes) }
        fx.writeAssets(patch = gamePatchText, index = indexText(target = gameTargetRel, root = "game"))
        val out = planGameRootPatches(gameRoot, fx.patchesDir, fx.logger)
        assertEquals(setOf(gameTargetRel), out.keys)
        assertArrayEquals(fixedBytes, out.getValue(gameTargetRel))
        assertArrayEquals(badBytes, csv.readBytes())
        assertTrue(
            fx.logger.lifecycles.joinToString("\n"),
            fx.logger.lifecycles.any { it.contains("T-001") && it.contains("按补丁替换") },
        )
    }

    @Test
    fun 出包面_游戏根目标缺席判红() {
        val fx = fixture(targetBytes = null)
        fx.writeAssets(patch = gamePatchText, index = indexText(target = gameTargetRel, root = "game"))
        try {
            planGameRootPatches(fx.corpusRoot.parentFile, fx.patchesDir, fx.logger)
            fail("出包面与编译面必须同判据：目标缺席不许静默随包原样")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("游戏根目标缺席"))
        }
    }

    @Test
    fun 多条补丁_marker逐行不粘连() {
        val fx = fixture(targetBytes = badBytes)
        File(fx.corpusRoot.parentFile, gameTargetRel).writeBytes(badBytes)
        val second = listOf(
            "  - id: T-002",
            "    target: $gameTargetRel",
            "    root: game",
            "    patch: T-002.patch",
            "    bad-sha256: ${sha(badBytes)}",
            "    fixed-sha256: ${sha(fixedBytes)}",
            "    corpus: test-corpus",
            "    status: test",
            "    summary: test",
            "    evidence: test",
        ).joinToString("\n", postfix = "\n")
        File(fx.patchesDir, "T-001.patch").writeText(patchText)
        File(fx.patchesDir, "T-002.patch").writeText(gamePatchText)
        File(fx.patchesDir, "index.yaml").writeText(indexText() + second)
        val plan = fx.plan()
        assertTrue(plan is CorpusPatchPlan.Staged)
        val markerLines = File((plan as CorpusPatchPlan.Staged).stagingDir, CORPUS_PATCHES_MARKER)
            .readLines()
            .filter { it.isNotBlank() }
        assertEquals(2, markerLines.size)
        assertTrue(markerLines[0], markerLines[0].startsWith("corpus-patch T-001 TGT.ERB "))
        assertTrue(markerLines[1], markerLines[1].startsWith("corpus-patch T-002 $gameTargetRel "))
    }

    // ---- unified diff 套用 ----------------------------------------------------------

    @Test
    fun 套用_CRLF语料LF补丁_字节级保真() {
        val patched = applyUnifiedDiff(badBytes, patchText, "T-001", "TGT.ERB")
        assertArrayEquals(fixedBytes, patched)
    }

    @Test
    fun 套用_语料上下文漂一字_判红() {
        val drifted = crlfBytes(badLines.mapIndexed { i, line -> if (i == 2) "\tRETURN 2" else line })
        try {
            applyUnifiedDiff(drifted, patchText, "T-001", "TGT.ERB")
            fail("上下文漂一个字必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("逐字匹配失败"))
        }
    }

    @Test
    fun 套用_补丁上下文漂一字_判红() {
        val brokenPatch = patchText.replace(" \tRETURN 1\n", " \tRETURN 9\n")
        try {
            applyUnifiedDiff(badBytes, brokenPatch, "T-001", "TGT.ERB")
            fail("补丁侧上下文漂一个字必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("逐字匹配失败"))
        }
    }

    @Test
    fun 套用_行号漂移_按内容唯一命中放行() {
        val shifted = crlfBytes(listOf(";", ";", ";") + badLines)
        val patched = applyUnifiedDiff(shifted, patchText, "T-001", "TGT.ERB")
        assertArrayEquals(crlfBytes(listOf(";", ";", ";") + badLines.mapIndexed { i, line ->
            if (i == 4) "CALL K51_EGG_SPECIAL_EVENT()" else line
        }), patched)
    }

    @Test
    fun 套用_上下文多处命中_判红() {
        val doubled = crlfBytes(badLines + badLines)
        try {
            applyUnifiedDiff(doubled, patchText, "T-001", "TGT.ERB")
            fail("上下文块出现多处必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("多处"))
        }
    }

    @Test
    fun 套用_hunk计数与正文不符_判红() {
        val badHeader = patchText.replace("@@ -2,7 +2,7 @@", "@@ -2,8 +2,8 @@")
        try {
            applyUnifiedDiff(badBytes, badHeader, "T-001", "TGT.ERB")
            fail("hunk 行数与 @@ 记数不符必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("行数"))
        }
    }

    @Test
    fun 套用_补丁头与目标不符_判红() {
        try {
            applyUnifiedDiff(badBytes, patchText, "T-001", "OTHER.ERB")
            fail("补丁头路径与 index target 不符必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("不一致"))
        }
    }

    @Test
    fun 套用_纯新增hunk_新增行继承锚行终止符() {
        val addText = listOf(
            "--- a/TGT.ERB",
            "+++ b/TGT.ERB",
            "@@ -4,0 +5,1 @@",
            "+CALL K51_EGG_NEW_HOOK()",
        ).joinToString("\n", postfix = "\n")
        val patched = applyUnifiedDiff(badBytes, addText, "T-001", "TGT.ERB")
        // 插在 ENDIF（第 4 行，CRLF）之后 → 新行也 CRLF；其余行原样
        val expected = crlfBytes(
            badLines.take(4) + "CALL K51_EGG_NEW_HOOK()" + badLines.drop(4),
        )
        assertArrayEquals(expected, patched)
    }

    @Test
    fun 套用_补丁缺文件头_判红() {
        // 剥掉 ---/+++ 两行文件头；lineSequence 的串尾幽灵空行一并剔掉（否则重拼会造出
        // 一个真带终止符的空行，被数成多一行上文 —— 那是夹具事故，不是解析器判据）。
        val headless = patchText.lineSequence().toList()
            .drop(2)
            .let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it }
            .joinToString("\n", postfix = "\n")
        try {
            applyUnifiedDiff(badBytes, headless, "T-001", "TGT.ERB")
            fail("缺文件头的补丁必须判红")
        } catch (e: GradleException) {
            assertTrue(e.message!!, e.message!!.contains("文件头"))
        }
    }

    // ---- staging 基件 ---------------------------------------------------------------

    @Test
    fun 拷贝回落_流式拷贝字节一致() {
        val src = tmp.newFile("src.bin")
        val rnd = java.util.Random(42)
        val bytes = ByteArray(3 shl 20) { rnd.nextInt(256).toByte() } // 3 MiB，跨多个 1MiB 块
        src.writeBytes(bytes)
        val dst = tmp.root.resolve("dst.bin")
        copyFileFallback(src, dst)
        assertArrayEquals(bytes, dst.readBytes())
    }

    /** JUnit4 环境的 ByteArray 断言直接用 org.junit.Assert 的同名静态。 */
}
