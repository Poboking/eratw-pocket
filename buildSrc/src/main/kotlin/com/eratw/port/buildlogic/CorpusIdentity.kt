package com.eratw.port.buildlogic

import java.io.File
import org.gradle.api.GradleException

/**
 * 语料身份的构建期窄解析（CONTRACT-ENGCPL §6 L3 / E7）：读**生成物**
 * `specs/engine-profiles.yaml` 的 `corpora:` 段，把「语料根目录名 + 认根标记」的唯一
 * 出处交给 Gradle 侧消费（`:core-vm` 的 gate981 候选链、`:tools:erbcc` 的同名闸）。
 *
 * ## 为什么窄解析而不是引 YAML 依赖
 *
 * `buildSrc` 是 Gradle 的构建逻辑源集，加一门 YAML 解析依赖要改整条构建类路径
 * （离线机构建不保证能拉到包）。生成物的 `corpora:` 段形状由
 * `devtools/gen_engine_profiles.py` 固定（两空格一层的块映射，标量只出现单引号字符串 /
 * `null` / `true|false` 三种），所以这里只按那个形状逐行读：**未知键、缺键、重复段、
 * 坏缩进一律判红并带行号**。宁可构建期就炸，不许悄悄回落到某个内置字面量 —— 那等于
 * 又造出第二份事实。
 *
 * ## 与 python / Kotlin 消费端的分工
 *
 * - 手写权威 = `specs/engine-profiles.input.yaml`；
 * - 机器可读投影 = `specs/engine-profiles.yaml`（本文件读它，**不读** input）；
 * - Kotlin 运行期投影 = `EngineProfileData.CORPORA`（core-vm 测试装置读它，运行期不读 yaml）。
 *
 * 三侧都不许再写语料根名字面量；换根 = 改 input yaml + 重生成两个投影。
 */
data class CorpusIdentity(
    /** 语料版本 id（如 `V4_981`），与 `TargetSpec.corpusVersionId` 同一口径。 */
    val id: String,
    /** 语料根目录名（共享根下的 basename，如 `eraTW4.981-261017-EAGLOV3`）。 */
    val rootDir: String,
    /** 认根标记文件（相对语料根）；`null` = 该语料没有标记文件。 */
    val markerFile: String?,
    /** 已退役：不再随包，只留作旧语料对拍。 */
    val retired: Boolean,
)

private const val SECTION = "corpora:"

/** 条目 id：字母开头，后续字母/数字/下划线（与生成器的 target/corpus 键同一命名面）。 */
private val ID_RE = Regex("[A-Za-z][A-Za-z0-9_]*")

/** 四空格缩进的字段行；值部分原样留给 [scalar] 判形状。 */
private val FIELD_RE = Regex(" {4}([A-Za-z_]+): (.*)")

/** 两空格缩进的条目行。 */
private val ENTRY_RE = Regex(" {2}([^\\s:][^:]*):")

/** corpora 条目允许出现的字段（顺序无关），生成物里只此三个键。 */
private val FIELDS = listOf("root_dir", "marker_file", "retired")

/**
 * 读生成物 yaml 的 `corpora:` 段 → `id` 到 [CorpusIdentity] 的映射（保持书写顺序）。
 *
 * @throws GradleException 表缺失 / 段缺失或重复 / 条目字段缺项 / 未知字段 / 坏缩进 /
 *   值形状不认识。正文一律带 `第 N 行`，可直接跳到生成物对应行核对。
 */
fun loadCorpora(specFile: File): Map<String, CorpusIdentity> {
    if (!specFile.isFile) {
        throw GradleException(
            "语料身份表不存在：${specFile.absolutePath}。它是 devtools/gen_engine_profiles.py 的" +
                "生成物（源 = specs/engine-profiles.input.yaml），先跑生成器再构建；" +
                "构建期不内置回落表",
        )
    }
    val fail: (Int, String) -> Nothing = { lineNo, why ->
        throw GradleException("engine-profiles.yaml 第 $lineNo 行：$why")
    }

    // `File.readLines`（不是 `String.readLines`）：buildSrc 的编译类路径上 gradleApi 带的
    // stdlib 版本比 `String.readLines` 新引入的那一代老，用文件版才能两侧都编得过。
    val lines = specFile.readLines(Charsets.UTF_8)
    var sectionStart = -1
    for (idx in lines.indices) {
        val raw = lines[idx]
        if (raw == SECTION) {
            if (sectionStart >= 0) fail(idx + 1, "重复的 $SECTION 段（首次出现在第 ${sectionStart + 1} 行）")
            sectionStart = idx
            break
        }
        // 段头带尾随空白视同坏缩进，不放过（否则「看着有段、其实没读到」）。
        if (raw.trimEnd() == SECTION) fail(idx + 1, "段头 `corpora:` 带尾随空白")
    }
    if (sectionStart < 0) {
        throw GradleException("engine-profiles.yaml 缺 corpora: 段（${specFile.absolutePath}）")
    }

    val out = LinkedHashMap<String, CorpusIdentity>()
    var curId: String? = null
    var curLine = 0
    val fields = HashMap<String, String>()
    val fieldLines = HashMap<String, Int>()

    fun finish(): Map<String, CorpusIdentity> {
        if (out.isEmpty()) throw GradleException("engine-profiles.yaml 的 corpora: 段为空")
        return out
    }

    fun flush(atLine: Int) {
        val id = curId ?: return
        val missing = FIELDS.filter { it !in fields }
        if (missing.isNotEmpty()) {
            fail(atLine, "条目 $id（第 $curLine 行）缺字段 $missing（已有 ${fields.keys.sorted()}）")
        }
        val rootLine = fieldLines.getValue("root_dir")
        val rootDir = scalar(fields.getValue("root_dir"), rootLine, fail)
        if (rootDir.isBlank()) fail(rootLine, "条目 $id 的 root_dir 为空")
        if (rootDir.contains('/') || rootDir.contains('\\') || rootDir == "." || rootDir == "..") {
            fail(rootLine, "条目 $id 的 root_dir '$rootDir' 是路径而不是目录名（语料根按共享根下的 basename 解析）")
        }
        val markerLine = fieldLines.getValue("marker_file")
        val markerRaw = fields.getValue("marker_file")
        val marker = if (markerRaw == "null") {
            null
        } else {
            scalar(markerRaw, markerLine, fail).also { m ->
                if (m.isBlank()) fail(markerLine, "条目 $id 的 marker_file 为空串（无标记请写 null）")
                if (m.startsWith("/") || m.startsWith("\\") ||
                    m.split('/', '\\').any { it == ".." }
                ) {
                    fail(markerLine, "条目 $id 的 marker_file '$m' 必须是相对语料根的路径（且不含 ..）")
                }
            }
        }
        val retiredLine = fieldLines.getValue("retired")
        val retiredRaw = fields.getValue("retired")
        val retired = when (retiredRaw) {
            "true" -> true
            "false" -> false
            else -> fail(retiredLine, "条目 $id 的 retired 只接受 true/false，收到 '$retiredRaw'")
        }
        out[id] = CorpusIdentity(id = id, rootDir = rootDir, markerFile = marker, retired = retired)
    }

    var j = sectionStart + 1
    while (j < lines.size) {
        val line = lines[j]
        val lineNo = j + 1
        when {
            line.isBlank() || line.trimStart().startsWith("#") -> Unit
            // 顶格的非空行 = 段结束（下一个顶层键）：收尾并交还结果。
            !line.startsWith(" ") -> {
                flush(lineNo)
                return finish()
            }
            line.startsWith("   ") -> {
                val m = FIELD_RE.matchEntire(line)
                    ?: fail(lineNo, "字段行形状不认识：'$line'（期望四个空格 + `key: value`）")
                if (curId == null) fail(lineNo, "字段行出现在任何条目之前：'$line'")
                val key = m.groupValues[1]
                if (key !in FIELDS) {
                    fail(lineNo, "未知字段 '$key'（corpora 条目只允许 $FIELDS）")
                }
                if (key in fields) {
                    fail(lineNo, "条目 $curId 的字段 $key 重复（首次出现在第 ${fieldLines.getValue(key)} 行）")
                }
                fields[key] = m.groupValues[2].trim()
                fieldLines[key] = lineNo
            }
            else -> {
                val m = ENTRY_RE.matchEntire(line)
                    ?: fail(lineNo, "条目行形状不认识：'$line'（期望两个空格 + `<id>:`）")
                val id = m.groupValues[1].trim()
                if (!ID_RE.matches(id)) fail(lineNo, "条目标识 '$id' 不合形状（字母开头的字母/数字/下划线）")
                if (out.containsKey(id)) fail(lineNo, "条目标识 $id 重复（首次出现在第 $curLine 行）")
                flush(lineNo)
                curId = id
                curLine = lineNo
                fields.clear()
                fieldLines.clear()
            }
        }
        j++
    }
    flush(lines.size + 1)
    return finish()
}

/**
 * 现行语料（恰一条 `retired: false`）。0 条或多条一律判红 —— 「哪份语料是现行」必须是
 * 数据里唯一可判的事实，不许由调用方「挑第一个」。
 */
fun activeCorpus(specFile: File): CorpusIdentity {
    val all = loadCorpora(specFile)
    val active = all.values.filterNot { it.retired }
    if (active.size != 1) {
        throw GradleException(
            "engine-profiles.yaml 的 corpora: 段必须恰有一条 retired=false 的语料，" +
                "实际 ${active.size} 条（${active.map { it.id }}；全部条目 ${all.keys}）",
        )
    }
    return active.single()
}

/** 按 id 取一条语料；缺 id 判红（列出表内 id，禁止回落到某条现成语料）。 */
fun corpus(specFile: File, id: String): CorpusIdentity {
    val all = loadCorpora(specFile)
    return all[id] ?: throw GradleException(
        "engine-profiles.yaml 的 corpora: 段没有 '$id'（表内只有 ${all.keys}）；" +
            "换语料先改 specs/engine-profiles.input.yaml 并重生成",
    )
}

/**
 * 剥单引号 / 识别裸标量。只接受生成物会写出的形状：`'字符串'`、裸 token、`null`、
 * `true|false`（后两者由调用方按字段判）。双引号、流式映射/序列、`#` 注释、带冒号的值
 * 一律判红 —— 它们要么是生成器换了写法（该改这里），要么是文件被手改坏了。
 */
private fun scalar(raw: String, lineNo: Int, fail: (Int, String) -> Nothing): String {
    val v = raw.trim()
    if (v.startsWith("'")) {
        if (v.length < 2 || !v.endsWith("'")) fail(lineNo, "单引号标量没有闭合：'$raw'")
        val inner = v.substring(1, v.length - 1)
        if (inner.contains('\'')) fail(lineNo, "单引号标量内含引号（生成物不会写出这种形状）：'$raw'")
        return inner
    }
    if (v.endsWith("'")) fail(lineNo, "单引号标量没有起始引号：'$raw'")
    if (v.isEmpty()) fail(lineNo, "字段值为空")
    if (v.any { it == '"' || it == ':' || it == '{' || it == '[' || it == '#' }) {
        fail(lineNo, "标量形状不认识（生成物只写单引号字符串 / null / true|false）：'$raw'")
    }
    return v
}
