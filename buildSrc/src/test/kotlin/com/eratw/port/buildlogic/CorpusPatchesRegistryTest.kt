package com.eratw.port.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 登记面自检：`devtools/corpus-patches` 里 index.yaml 与补丁资产必须一一对应、能互相解释。
 *
 * 这条闸补的是一个机制缺口：套用器只按 sha 三档认资产，所以「index 漏一条」「补丁文件漏提交」
 * 「target 写成另一个文件」都不会让编译报红，而玩家侧就少了那批修复。CB-023 那一波收口时
 * 正是靠人工比对才发现同族文件里有死文件被误列成潜在端点。
 */
class CorpusPatchesRegistryTest {

    /** 从测试工作目录往上找 `devtools/corpus-patches/index.yaml`（buildSrc 测试的 cwd 不固定）。 */
    private fun patchesDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val cand = File(dir, "devtools/corpus-patches")
            if (File(cand, "index.yaml").isFile) return cand
            val nested = File(dir, "eratw-android/devtools/corpus-patches")
            if (File(nested, "index.yaml").isFile) return nested
            dir = dir.parentFile
        }
        throw AssertionError("找不到 devtools/corpus-patches/index.yaml（cwd=${System.getProperty("user.dir")}）")
    }

    private fun readUtf8(f: File): String = f.readText(Charsets.UTF_8)

    /** 补丁正文的逐行类型：头部（`---`/`+++`/`@@`）、新增、删除、保留。 */
    private fun classify(patchText: String): Triple<List<String>, List<String>, List<String>> {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val ctx = mutableListOf<String>()
        for (line in patchText.split("\n")) {
            val s = line.removeSuffix("\r")
            when {
                s.startsWith("--- ") || s.startsWith("+++ ") || s.startsWith("@@") -> Unit
                s.startsWith("+") -> added += s.substring(1)
                s.startsWith("-") -> removed += s.substring(1)
                s.startsWith(" ") -> ctx += s.substring(1)
                s.isEmpty() -> Unit
                else -> throw AssertionError("补丁行不可归类：'$s'")
            }
        }
        return Triple(added, removed, ctx)
    }

    @Test
    fun 登记面_index与补丁资产一一对应() {
        val dir = patchesDir()
        val entries = parseCorpusPatchIndex(readUtf8(File(dir, "index.yaml")))
        assertTrue("index 不能为空", entries.isNotEmpty())

        val declared = entries.associateBy { it.patch }
        assertEquals("同一补丁文件被登记多次", entries.size, declared.size)

        for (e in entries) {
            val f = File(dir, e.patch)
            assertTrue("${e.id}：补丁文件不存在 ${e.patch}", f.isFile)
            val head = readUtf8(f).lineSequence().first()
            assertTrue(
                "${e.id}：补丁首行应声明 target=${e.target}，实际 '$head'",
                head.trim() == "--- a/${e.target}",
            )
            assertEquals("${e.id}：bad == fixed 说明补丁没改东西", false, e.badSha256 == e.fixedSha256)
        }

        val onDisk = dir.listFiles { f -> f.isFile && f.name.startsWith("CB-") && f.name.endsWith(".patch") }
            .orEmpty().map { it.name }.toSet()
        assertEquals(
            "补丁资产与 index 登记不一致：磁盘上未登记 = ${onDisk - declared.keys}，" +
                "登记但缺文件 = ${declared.keys - onDisk}",
            onDisk,
            declared.keys,
        )
    }

    @Test
    fun 关键字表补丁_只追加别名且字面量互不重复() {
        val dir = patchesDir()
        val entries = parseCorpusPatchIndex(readUtf8(File(dir, "index.yaml")))
        val csvEntries = entries.filter { it.target.startsWith("CSV/") && it.target.endsWith(".als") }
        assertTrue("本仓当前应有 CSV 关键字表的别名补丁", csvEntries.isNotEmpty())

        for (e in csvEntries) {
            val text = readUtf8(File(dir, e.patch))
            val (added, removed, ctx) = classify(text)
            assertTrue("${e.id}：别名补丁不该删行（删了就不是纯回补），实删 ${removed.size} 行", removed.isEmpty())
            assertEquals("${e.id}：别名补丁必须保留一行上下文（末行）以防空 hunk", 1, ctx.size)

            val aliases = mutableListOf<Pair<Int, String>>()
            for (line in added) {
                val s = line.trim()
                if (s.isEmpty() || s.startsWith(";")) continue
                val head = s.substringBefore(";").trim().trimEnd(',')
                val cells = head.split(",").map { it.trim() }
                assertEquals("${e.id}：追加行应为 `<下标>,<别名>`，实际 '$line'", 2, cells.size)
                val idx = cells[0].toIntOrNull()
                assertTrue("${e.id}：追加行下标不是数字 '$line'", idx != null)
                aliases += idx!! to cells[1]
            }
            assertTrue("${e.id}：补丁没有追加任何别名行", aliases.isNotEmpty())

            val dupName = aliases.groupBy { it.second }.filterValues { it.size > 1 }.keys
            assertTrue("${e.id}：同一补丁里别名重复 $dupName", dupName.isEmpty())
            val dupIdxName = aliases.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue("${e.id}：同一补丁里 <下标,别名> 重复 $dupIdxName", dupIdxName.isEmpty())
        }
    }

    /**
     * `target` 的大小写必须与语料目录里的实际文件名逐字一致。
     *
     * 2026-11-22 实际踩过：`CSV/ITEM.als` 在 Windows 上是大小写不敏感路径，套用器与编译面都照常命中，
     * 但出包侧 `stageGameAssets` 的随包面校验按目录列举的原始大小写比对，直接判红「补丁目标未随包」，
     * 而 Linux CI 上文件系统区分大小写，会变成补丁目标缺席。这条闸在动手前就点名。
     */
    @Test
    fun 补丁target大小写与语料文件名逐字一致() {
        val gameRoot = sequenceOf(System.getenv("ERATW_GAMEDIR"), System.getenv("ERATW_CORPUS"))
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .map { File(it).absoluteFile }
            .map { if (it.name.equals("ERB", ignoreCase = true)) it.parentFile else it }
            .firstOrNull { File(it, "CSV").isDirectory }
        org.junit.Assume.assumeTrue("跳过：未设置 ERATW_GAMEDIR / ERATW_CORPUS", gameRoot != null)

        val entries = parseCorpusPatchIndex(readUtf8(File(patchesDir(), "index.yaml")))
        val mismatched = mutableListOf<String>()
        for (e in entries) {
            val rel = e.target.replace('\\', '/')
            if (rel.contains('/').not()) continue
            val parent = File(gameRoot!!, rel.substringBeforeLast('/'))
            if (!parent.isDirectory) continue
            val base = rel.substringAfterLast('/')
            val actual = parent.listFiles().orEmpty().map { it.name }
            if (base !in actual) {
                mismatched += "${e.id}: ${e.target} → 语料里是 " +
                    (actual.filter { it.equals(base, ignoreCase = true) }.joinToString().ifEmpty { "（同名文件不存在）" })
            }
        }
        assertTrue(
            "补丁 target 与语料实际文件名大小写不一致（Windows 上蒙混过关，Linux/出包随包面会判红）：\n" +
                mismatched.joinToString("\n"),
            mismatched.isEmpty(),
        )
    }
}
