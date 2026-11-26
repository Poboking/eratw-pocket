package com.eratw.port.sentinel

import java.io.File
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * 1.824（`reference/emuera-cs`）语义权威引用的**源码哨兵**（2026-11-20，P2 元级）。
 *
 * ## 为什么要有这道闸
 *
 * 复刻目标在 2026-11-14 已换成蛇版 4.981：`specs/engine-profiles.yaml:54-59` 的 `snake`
 * 是唯一 target，树内锚 `reference/emuera-skia-4.981/CHANGELOG.md:11`；而
 * `cs_reference`（:60-64）明写「The tree specs were built from; NOT a target engine.」
 * —— 1.824 **已退役**，它的反编译树还在，历史 KDoc 也大量引用它。
 *
 * 问题不在存量（这批清洗是另一笔活），而在**新增**：新写一段 KDoc 直接抄 1.824 的行为
 * 当语义权威，会让「按 4.981 复刻」这条主口径悄悄漂回旧版，而编译与行为单测都不会说话。
 * 这道哨兵把存量逐文件冻结在 `src/test/resources/ref-authority-baseline.txt`，新增引用
 * 必须在同一注释段里显式写清身份，二者任选其一：
 *
 * * 标「**1.824 已退役**」（或同段出现「已退役」）；
 * * 带 **4.981 对照锚**（同段出现 `4.981`，说明旧树只是对照面）。
 *
 * 两者都没有 ⇒ 红。这不是风格闸：语义权威换了发动机，引用旧树却不标注，读代码的人
 * 会照着 1.824 的行为去改实现 —— 本仓已经因为 1.824/4.981 漂移修过除零、RAND、
 * 溢出三族同类缺陷（见 `ClampWarnings.kt` 的 KDoc）。
 *
 * ## 扫描口径
 *
 * * 只看**注释**（KDoc 块 + 连续 `//` 行组），不看字符串与代码：测试里打开
 *   `reference/emuera-cs/...` 的文件路径不是「语义权威声明」。
 * * 触发词 = `emuera-cs`（树名）。裸 `1.824` 不触发：它常被用来写「不是这个版本」的
 *   反向说明，命中率噪声太大。
 * * 一个注释段里出现多次只计一次（防复制粘贴刷数把基线拉高）。
 * * 基线是**上界**：清理存量把计数降下来不需要改基线；新增文件或新增段会红。
 * * 存量数字（2026-11-20 首次冻结）：**123 文件 / 129 段**；未标注的 `emuera-cs`
 *   出现 176 次（含已带 `4.981` 对照的段落，那些不计入基线）。
 *
 * ## 反向证明
 *
 * 末条用例用临时字符串跑同一判定：未标注 ⇒ 非空；标「1.824 已退役」或带 `4.981` ⇒ 空。
 * 删掉标记即红，故这条判式不是空转。
 */
class ReferenceAuthoritySentinelTest {

    // ------------------------------------------------------------------
    // 判定式（纯函数，反向证明直接打它）
    // ------------------------------------------------------------------

    /** 未标注的 1.824 权威引用段（返回段文本，供报错定位）。 */
    private fun unmarkedAuthoritySegments(text: String): List<String> =
        commentSegments(text)
            .filter { it.contains(AUTHORITY_TREE) }
            .filterNot { it.contains(RETIRED_MARKER) || it.contains(TARGET_VERSION) }

    /**
     * 注释段 = KDoc 块 + 连续 `//` 行组。字符串与普通代码不参与判定。
     *
     * 两条口径细节（都是首次实现踩过的坑）：
     * 1. 先把**字符串/字符字面量**（含三引号原始串）内容抹成空格再提注释，否则测试源码里
     *    作为**字符串值**出现的 KDoc 样板文本（其中含 reference/emuera-cs 与 4.981 之外的
     *    未标注引用）会被当成真注释，哨兵自己把自己判红；
     * 2. 行注释按「行号相邻」合并成段，不要求 `//` 顶格：行尾注释（`val x = 1 // …`）同样
     *    参与判定，而跨行的 `//` 注释组里只要**任一行**写了「已退役」或 `4.981` 就整段放行。
     */
    private fun commentSegments(text: String): List<String> {
        val masked = maskStringLiterals(text)
        val out = ArrayList<String>()
        val blockRanges = ArrayList<IntRange>()
        for (m in KDOC.findAll(masked)) {
            out += m.value
            blockRanges += m.range
        }
        var cursor = 0
        var line = 0
        var prevLine = -2
        val group = StringBuilder()
        for (m in LINE_COMMENT.findAll(masked)) {
            if (blockRanges.any { m.range.first in it }) continue
            while (cursor < m.range.first) {
                if (masked[cursor] == '\n') line++
                cursor++
            }
            if (line == prevLine + 1 && group.isNotEmpty()) {
                group.append('\n').append(m.value)
            } else {
                if (group.isNotEmpty()) out += group.toString()
                group.clear()
                group.append(m.value)
            }
            prevLine = line
            cursor = m.range.last + 1
        }
        if (group.isNotEmpty()) out += group.toString()
        return out
    }

    /**
     * 字符串/字符字面量内容抹成空格（保留 `\n` 与长度）；**注释原样保留**。
     *
     * 必须先识别注释再抹串：注释里的 `don't`、`"引用"` 不是字面量，若按普通状态扫会被
     * 当成字符/字符串起头，把后面的注释整段抹掉（2026-11-20 首次实现的实际风险）。
     */
    private fun maskStringLiterals(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            i = when {
                text.startsWith("//", i) -> copyLineComment(text, i, out)
                text.startsWith("/*", i) -> copyBlockComment(text, i, out)
                text.startsWith("\"\"\"", i) -> blankRawString(text, i, out)
                text[i] == '"' || text[i] == '\'' -> blankQuoted(text, i, out)
                else -> {
                    out.append(text[i])
                    i + 1
                }
            }
        }
        return out.toString()
    }

    /** 行注释原样复制到行尾（不含换行；换行留给主循环）。返回新下标。 */
    private fun copyLineComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        while (i < text.length && text[i] != '\n') {
            out.append(text[i])
            i++
        }
        return i
    }

    /** Kotlin 块注释可嵌套：按深度原样复制（含 `/*` `*/` 本体）。返回新下标。 */
    private fun copyBlockComment(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        var depth = 0
        do {
            when {
                text.startsWith("/*", i) -> {
                    depth++
                    out.append("/*")
                    i += 2
                }
                text.startsWith("*/", i) -> {
                    depth--
                    out.append("*/")
                    i += 2
                }
                i < text.length -> {
                    out.append(text[i])
                    i++
                }
            }
        } while (i < text.length && depth > 0)
        return i
    }

    /** 三引号原始串整段抹白（保留换行）。返回新下标。 */
    private fun blankRawString(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append("   ")
        i += 3
        while (i < text.length) {
            if (text.startsWith("\"\"\"", i)) {
                out.append("   ")
                return i + 3
            }
            out.append(if (text[i] == '\n') '\n' else ' ')
            i++
        }
        return i
    }

    /** 普通 `"…"` / `'…'` 字面量抹白（保留换行与转义宽度）。返回新下标。 */
    private fun blankQuoted(text: String, start: Int, out: StringBuilder): Int {
        val quote = text[start]
        var i = start
        out.append(' ')
        i++
        while (i < text.length) {
            when {
                text[i] == '\\' -> {
                    out.append(if (i + 1 < text.length && text[i + 1] == '\n') "\n " else "  ")
                    i += 2
                }
                text[i] == quote -> {
                    out.append(' ')
                    return i + 1
                }
                else -> {
                    out.append(if (text[i] == '\n') '\n' else ' ')
                    i++
                }
            }
        }
        return i
    }

    // ------------------------------------------------------------------
    // 一、存量冻结：新增必须显式标注
    // ------------------------------------------------------------------

    @Test
    fun `未标注的 1_824 权威引用不得超过冻结基线`() {
        val roots = scanRoots()
        val baseline = baseline()
        val measured = LinkedHashMap<String, Int>()
        for (root in roots) {
            for (file in kotlinSources(root)) {
                val rel = file.relativeTo(root).invariantSeparatorsPath
                val n = unmarkedAuthoritySegments(file.readText()).size
                if (n > 0) measured[rel] = n
            }
        }
        val overruns = measured.filter { (path, n) -> n > (baseline[path] ?: 0) }
        assertTrue(
            buildString {
                appendLine("新增了未标注的 1.824 语义权威引用（reference/emuera-cs 已退役）：")
                for ((path, n) in overruns) {
                    appendLine("  · $path：$n 段（基线 ${baseline[path] ?: 0}）")
                    val src = resolveIn(roots, path)
                    for (seg in unmarkedAuthoritySegments(src?.readText() ?: "")) {
                        appendLine("      段：${seg.lineSequence().first().trim()}")
                    }
                }
                appendLine("处置：在同一注释段里标「1.824 已退役」，或补 4.981 对照锚；确属存量再改")
                appendLine("src/test/resources/ref-authority-baseline.txt（只允许上调有据的新增）。")
            },
            overruns.isEmpty(),
        )
    }

    @Test
    fun `基线里的每条路径都必须真实存在`() {
        val roots = scanRoots()
        val missing = baseline().keys.filterNot { resolveIn(roots, it) != null }.sorted()
        assertTrue("基线含已不存在的路径（文件改名/删除时基线要同步）：$missing", missing.isEmpty())
    }

    // ------------------------------------------------------------------
    // 二、闸的前提本身也要钉住（1.824 不是 target engine）
    // ------------------------------------------------------------------

    @Test
    fun `engine-profiles 仍把 1_824 标为非目标引擎且 4_981 是目标档`() {
        val yaml = specFile().readText()
        val csBlock = yaml.substringAfter("cs_reference:", "")
            .substringBefore("\nprofiles:")
        assertTrue("cs_reference 段形状变了：$csBlock", csBlock.contains("reference/emuera-cs"))
        assertTrue("cs_reference 的「NOT a target engine」注记被去掉 ⇒ 本哨兵的前提失效，先重审口径", csBlock.contains("NOT a target engine"))
        val snakeBlock = yaml.substringAfter("  snake:", "").substringBefore("  cs_reference:")
        assertTrue("snake 目标档路径变了：$snakeBlock", snakeBlock.contains("reference/emuera-skia-4.981"))
    }

    // ------------------------------------------------------------------
    // 三、反向证明：判定式对两种合法标注都放行
    // ------------------------------------------------------------------

    @Test
    fun `标注判定式区分未标注与两种合法标注`() {
        val bare = "/** spec: reference/emuera-cs/OperatorMethodManager.cs:24 的 unchecked 回绕 */"
        assertEquals("未标注必须被抓到", 1, unmarkedAuthoritySegments(bare).size)
        assertEquals("「已退役」标记必须放行", 0, unmarkedAuthoritySegments("/** 1.824 已退役：reference/emuera-cs/OperatorMethodManager.cs:24 */")
                .size)
        assertEquals("4.981 对照锚必须放行", 0, unmarkedAuthoritySegments("/** 对照 reference/emuera-cs 与 4.981 的差异；以 4.981 为准 */").size)
        // 连续 // 行组同样按段判定（一行标注、一行引用 ⇒ 放行）。
        val lineGroup = "// 1.824 已退役\n// reference/emuera-cs/OperatorMethodManager.cs:24\n"
        assertEquals(0, unmarkedAuthoritySegments(lineGroup).size)
        val unmarkedGroup = "// 旧版口径\n// reference/emuera-cs/OperatorMethodManager.cs:24\n"
        assertEquals(1, unmarkedAuthoritySegments(unmarkedGroup).size)
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    /** 从 gradle 的 cwd（app 模块目录或其上级）向上找壳层仓根；找不到就 error，不静默跳过。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: "").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile
        }
        error("定位不到壳层仓根（防假绿）：cwd=" + System.getProperty("user.dir"))
    }

    private fun specFile(): File {
        val root = repoRoot()
        // engine-profiles.yaml 是**引擎侧**的规格档（specs/ 在引擎仓 Kotera），不在壳层仓里。
        val candidates = listOf(
            File(root.resolveSibling("Kotera"), "specs/engine-profiles.yaml"),
            File(root, "specs/engine-profiles.yaml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "缺少 specs/engine-profiles.yaml（引擎侧规格档，在同级引擎仓 Kotera）；" +
                    "尝试：${candidates.joinToString { it.absolutePath }}",
            )
    }

    /** 全仓 Kotlin 源（跳过 build / reference / .git 三棵不产语义的树）。 */
    private fun kotlinSources(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && it.extension == "kt" && it.path.contains("${File.separator}src${File.separator}") }
            .toList()

    /**
     * 冻结基线覆盖的是**整个单体仓**的 Kotlin 源；公开分仓后模块分居两仓
     * （引擎侧在同级 Kotera、壳层侧在本仓），故扫描面取两仓之和。
     * 引擎仓不在场时跳过本闸（数据缺失不是回归）。
     */
    private fun scanRoots(): List<File> {
        val shell = repoRoot()
        val engine = shell.resolveSibling("Kotera")
        if (!File(engine, "core-vm/src/main/kotlin").isDirectory) {
            throw org.junit.AssumptionViolatedException(
                "同级引擎仓 Kotera 不在场（cwd=${System.getProperty("user.dir")}）：" +
                    "冻结基线覆盖全单体仓，缺引擎侧扫描面 ⇒ 跳过本哨兵",
            )
        }
        return listOf(engine, shell)
    }

    /** 某个「仓根相对路径」落在哪个仓里。 */
    private fun resolveIn(roots: List<File>, relative: String): File? =
        roots.asSequence().map { File(it, relative) }.firstOrNull { it.isFile }

    private fun baseline(): Map<String, Int> {
        val stream = javaClass.getResourceAsStream(BASELINE_RESOURCE)
            ?: error("缺少基线资源 $BASELINE_RESOURCE（防假绿）")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .associate { line ->
                    val bar = line.lastIndexOf('|')
                    require(bar > 0) { "基线行格式应为 <路径>|<段数>：$line" }
                    line.substring(0, bar) to line.substring(bar + 1).toInt()
                }
        }
    }

    private companion object {
        const val AUTHORITY_TREE = "emuera-cs"
        const val RETIRED_MARKER = "已退役"
        const val TARGET_VERSION = "4.981"
        const val BASELINE_RESOURCE = "/ref-authority-baseline.txt"
        val SKIP_DIRS = setOf("build", "reference", ".git", ".gradle")

        val KDOC = Regex("""/\*\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""(?m)//[^\r\n]*""")
    }
}
