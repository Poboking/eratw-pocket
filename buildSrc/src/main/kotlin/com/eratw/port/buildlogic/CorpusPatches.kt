package com.eratw.port.buildlogic

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

/**
 * 语料内容补丁的编译期应用（specs/corpus-bugs.md §3 的机制本体；首个补丁 CB-001 随本文件落地）。
 *
 * 语料根不进 git、两机各持只读副本，随包语料的内容修复必须是「仓库内资产 + 编译期应用」：
 * `:tools:erbcc:compileCorpus` 执行前（doFirst）解析 `devtools/corpus-patches/index.yaml`，
 * 把语料 staging 成硬链接副本（同卷零拷贝；跨卷或链接被拒时逐文件回落流式拷贝，staging 目录
 * 在 erbcc 的 build 下），只对**副本**里的目标文件套 unified diff，再让 erbcc 吃副本。
 * 真语料根一个字节都不许动（AGENTS 铁律）：staging 全程用 `Files.createLink` 建新目录项 +
 * 补丁结果经「临时文件 + 原子替换」落盘，绝不打开硬链接就地写（就地写会穿透到共享 inode、
 * 改坏真语料）；套用后还会回读真语料目标的 sha 自证未变，变了直接判红。
 *
 * 单补丁的判定三档（corpus-bugs 台账口径）：
 * 1. 目标缺席（该语料没有同名文件，如 4.980 无新版 Nitori）→ 跳过 + 留痕，原样直编；
 * 2. 目标在且 sha = bad-sha256 → 套补丁；
 * 3. 目标在且 sha = fixed-sha256 → 已是修后版，跳过 + 留痕；
 *    两个 sha 都不命中 → 未知漂移，判红（防上游语料换版悄悄绕过补丁）。
 *
 * 同一 target 允许多条补丁**串成链**（后条 `bad-sha256` == 前条 `fixed-sha256`，见 [buildPatchChains]）：
 * 判定仍只做一次，但对象是整条链 —— 真语料 == 链尾 fixed（整链已是修后版）⇒ 全跳过；
 * == 链中某一跳的 fixed（部分已生效，如别的分支先合了前一条）⇒ 从下一跳续套；
 * == 链头 bad ⇒ 整链按序套用；套用阶段逐环校验 staging 的当前 sha，任一环不符即判红。
 *
 * 留痕通道：应用成功的补丁在 staging 目录写 marker（每行
 * `corpus-patch <id> <相对路径> <坏版sha> <修后sha>`），compileCorpus 的 doLast 把这些行
 * 追加进产物 manifest.txt（erbcc 的清单解析显式容忍追加字段）；出包期 CMP-66 源 sha 对账
 * （[verifyErbcFreshness]）读同一批行，把「磁盘=坏版、清单=修后版」的目标文件按补丁已应用
 * 放行 —— 否则语料补丁会让下一次出包在新鲜度对账上误判红。
 */
/** 补丁目标的解析根：语料根是 erbcc 吃的那棵树，游戏根是它的父目录（CSV/emuera.config 的所在）。 */
enum class CorpusPatchRoot(val id: String) {
    CORPUS("corpus"),
    GAME("game"),
}

/** index.yaml 的一条补丁（窄 schema，解析见 [parseCorpusPatchIndex]）。 */
class CorpusPatchEntry(
    /** 台账 id（specs/corpus-bugs.md §1 的行，如 CB-001）。 */
    val id: String,
    /** 目标文件相对路径，相对 [root] 指定的根（`/` 分隔）。 */
    val target: String,
    /** 目标根的解析口径；index 未写 `root:` 时默认 [CorpusPatchRoot.CORPUS]（CB-001 原样兼容）。 */
    val root: CorpusPatchRoot,
    /** unified diff 资产文件名（与 index.yaml 同目录）。 */
    val patch: String,
    /** 病灶版目标文件 sha256（小写）。命中 = 应用。 */
    val badSha256: String,
    /** 修后版目标文件 sha256（小写）。命中 = 已修跳过；套用结果也必须等于它（双重判据）。 */
    val fixedSha256: String,
    /** 已知**无需补丁**的其它版本 sha256（如 4.980 根的表已有这些键）：命中 = 跳过 + 留痕。 */
    val skipSha256: List<String>,
    /** 适用语料版本（台账「语料落点」列的根名；仅摘要用，判定走 sha 三档）。 */
    val corpus: String,
    /** 台账状态（摘要用）。 */
    val status: String,
    /** 病灶与修法摘要（摘要用）。 */
    val summary: String,
    /** 证据指针（摘要用）。 */
    val evidence: String,
)

/** 补丁应用后的单条留痕（进 staging marker，再进产物 manifest）。 */
class AppliedPatch(
    val id: String,
    val targetRel: String,
    val badSha256: String,
    val fixedSha256: String,
)

/** compileCorpus 执行前对语料补丁的裁定结果。 */
sealed interface CorpusPatchPlan {
    /** 没有补丁需要应用：原样直编真语料。[reasons] 逐条留痕（日志口径）。 */
    data class None(val reasons: List<String>) : CorpusPatchPlan

    /** 有补丁已套到 staging 副本上：erbcc 的 corpus 根必须换成 [corpusRoot]。 */
    data class Staged(
        val corpusRoot: File,
        val applied: List<AppliedPatch>,
        /** staging 目录（marker 所在；ERB 副本与 CSV/emuera.config 兄弟件都在其下）。 */
        val stagingDir: File,
    ) : CorpusPatchPlan
}

/** staging 目录里的 marker 文件名（compileCorpus 的 doLast 读它往 manifest 追记）。 */
const val CORPUS_PATCHES_MARKER = "corpus-patches-applied.txt"

/** 必填字段（缺一判红）。 */
private val INDEX_REQUIRED_FIELDS =
    listOf("id", "target", "patch", "bad-sha256", "fixed-sha256", "corpus", "status", "summary", "evidence")

/** 索引里 sha 字段的形状（小写 64 hex；解析时统一小写）。 */
private val SHA_RE = Regex("^[0-9a-f]{64}$")

/**
 * 解析 corpus-patches 索引。**窄 schema**、手写解析（buildSrc 接不到 YAML 库，也不该为
 * 十几行配置引依赖）：`patches:` 之下每条以 `- ` 起头、后续字段两空格缩进 `key: value`；
 * 整行 `#` 注释与空行忽略；其余任何行判红，宁红不猜。路径/sha 形状当场校验。
 */
fun parseCorpusPatchIndex(text: String): List<CorpusPatchEntry> {
    val entries = mutableListOf<CorpusPatchEntry>()
    val fields = LinkedHashMap<String, String>()
    var seenPatchesHeader = false
    var entryStarted = false

    fun fail(lineNo: Int, why: String): Nothing =
        throw GradleException("corpus-patches index 第 $lineNo 行：$why")

    fun flush(lineNo: Int) {
        if (fields.isEmpty()) return
        val missing = INDEX_REQUIRED_FIELDS.filter { fields[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            fail(lineNo, "条目缺字段 $missing（已有 ${fields.keys}）")
        }
        for (key in listOf("bad-sha256", "fixed-sha256")) {
            val v = fields.getValue(key).trim().lowercase()
            if (!SHA_RE.matches(v)) fail(lineNo, "$key 不是小写 64 hex：'${fields.getValue(key)}'")
            fields[key] = v
        }
        val skipShas = fields["skip-sha256"]?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.onEach { if (!SHA_RE.matches(it)) fail(lineNo, "skip-sha256 含非小写 64 hex：'$it'") }
            ?: emptyList()
        val entry = CorpusPatchEntry(
            id = fields.getValue("id").trim(),
            target = normalizeTargetRel(fields.getValue("target").trim(), lineNo),
            root = when (val raw = fields["root"]?.trim() ?: CorpusPatchRoot.CORPUS.id) {
                CorpusPatchRoot.CORPUS.id -> CorpusPatchRoot.CORPUS
                CorpusPatchRoot.GAME.id -> CorpusPatchRoot.GAME
                else -> fail(lineNo, "root 只认 corpus / game：'$raw'")
            },
            patch = fields.getValue("patch").trim(),
            badSha256 = fields.getValue("bad-sha256"),
            fixedSha256 = fields.getValue("fixed-sha256"),
            skipSha256 = skipShas,
            corpus = fields.getValue("corpus").trim(),
            status = fields.getValue("status").trim(),
            summary = fields.getValue("summary").trim(),
            evidence = fields.getValue("evidence").trim(),
        )
        if (entries.any { it.id == entry.id }) fail(lineNo, "id 重复：${entry.id}")
        entries += entry
        fields.clear()
        entryStarted = false
    }

    var lastLineNo = 0
    text.lineSequence().forEachIndexed { idx, raw ->
        val lineNo = idx + 1
        lastLineNo = lineNo
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        if (line.isBlank() || trimmed.startsWith("#")) return@forEachIndexed
        when {
            line == "patches:" -> {
                flush(lineNo)
                seenPatchesHeader = true
            }
            trimmed.startsWith("- ") -> {
                if (!seenPatchesHeader) fail(lineNo, "条目出现在 patches: 之前")
                flush(lineNo)
                entryStarted = true
                splitIndexField(trimmed.removePrefix("- "), lineNo).let { (k, v) -> fields[k] = v }
            }
            line.startsWith(" ") -> {
                if (!entryStarted) fail(lineNo, "缩进行不在任何条目内：'$line'")
                splitIndexField(trimmed, lineNo).let { (k, v) ->
                    if (fields.containsKey(k)) fail(lineNo, "条目内字段重复：$k")
                    fields[k] = v
                }
            }
            else -> fail(lineNo, "不认识的行：'$line'（窄 schema：只认 patches: / '- ' 条目 / 两空格缩进字段 / # 注释）")
        }
    }
    flush(lastLineNo + 1)
    if (entries.isEmpty()) {
        throw GradleException("corpus-patches index 没有解析到任何补丁条目（$text 行数 ${text.lineSequence().count()}）")
    }
    return entries
}

/** `key: value` 切分（首个冒号加空格处切；value 里允许再有冒号与空格）。 */
private fun splitIndexField(line: String, lineNo: Int): Pair<String, String> {
    val sep = line.indexOf(": ")
    if (sep <= 0) throw GradleException("corpus-patches index 第 $lineNo 行：字段须为 'key: value'：'$line'")
    val key = line.substring(0, sep).trim()
    if (key.isEmpty() || key.contains(' ')) {
        throw GradleException("corpus-patches index 第 $lineNo 行：字段名不合法：'$key'")
    }
    return key to line.substring(sep + 2).trim()
}

/** target 相对路径校验：corpus 根下的 `/` 相对路径，禁止反斜杠、点段与空段。 */
private fun normalizeTargetRel(rel: String, lineNo: Int): String {
    val bad = rel.isEmpty() || rel.startsWith('/') || rel.contains('\\') ||
        rel.split('/').any { it.isEmpty() || it == "." || it == ".." }
    if (bad) {
        throw GradleException(
            "corpus-patches index 第 $lineNo 行：target 须为 corpus 根下的 '/' 相对路径（不许反斜杠/./..）：'$rel'",
        )
    }
    return rel
}

/**
 * staging 目录定位：`<stagingRoot>/<语料根绝对路径的 sha256 前 16 hex>`。
 * 路径哈希做键 ⇒ 不同语料根互不串台、同根两次运行落同处（doLast 凭它找回 marker）。
 * doFirst 的 plan 与 doLast 的 manifest 追记共用本函数，两边必须对上同一个目录。
 */
fun corpusStagingDir(corpusRoot: File, stagingRoot: File): File {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(corpusRoot.absolutePath.toByteArray(Charsets.UTF_8))
    val key = digest.joinToString("") { "%02x".format(it) }.take(16)
    return File(stagingRoot, key)
}

/**
 * 一条 target 上的**顺序补丁链**：`entries[i].badSha256 == entries[i-1].fixedSha256`。
 *
 * 为什么要链：同一份语料文件可能被多笔台账缺陷先后修（CB-006 → CB-008 就是同一处控制流里
 * 相邻的两跳）。每个补丁资产只描述自己那一跳，串起来才是真实语料「原版 → 修后版」的路径；
 * 把两跳压成一条并行补丁会让 fixed-sha256 的语义变成「两条都套完」，前一条一旦回退或重排就静默错位。
 */
private class CorpusPatchChain(
    val root: CorpusPatchRoot,
    val target: String,
    val entries: List<CorpusPatchEntry>,
) {
    val head: CorpusPatchEntry get() = entries.first()
    val tail: CorpusPatchEntry get() = entries.last()

    /** 留痕/判红用的 id 串（单条就是 id 本身，多条是 `CB-006→CB-008`）。 */
    fun idsLabel(): String = entries.joinToString("→") { it.id }
}

/**
 * 一条待套用的补丁：条目 + 真语料目标 + **本次续套的基准 sha**（铁律自证基准）。
 *
 * 基准 = 选择阶段在真语料上实测到的 sha：整链从原版起套时它就是链头 bad-sha256；
 * 从中间态续套（例如语料已套过 CB-006、只差 CB-008）时它是那个中间态的 fixed-sha256。
 */
private class PlannedPatch(
    val entry: CorpusPatchEntry,
    val realTarget: File,
    val expectedRealSha256: String,
)

/**
 * 把 index 条目按 `(root, target)` 归组并各自串成链。
 *
 * 判红口径（宁红不猜）：同 target 的多条必须**恰好**构成一条首尾相接的链 —— 两条 bad 相同、
 * 链头不唯一（断链）、某条 fixed 之后接不上（中间断口）都直接判红并点名涉事 id。
 * 顺序由 `bad == 前条 fixed` 关系**推导**（不信任手写行序：索引里调换两行不该改变语义，
 * 而真断链在这里必然表现为双头/断口，照样判红）。单条 target 原样成链，
 * 行为与链式支持前逐字节一致。
 */
private fun buildPatchChains(entries: List<CorpusPatchEntry>): List<CorpusPatchChain> {
    val groups = LinkedHashMap<Pair<CorpusPatchRoot, String>, MutableList<CorpusPatchEntry>>()
    for (e in entries) groups.getOrPut(e.root to e.target) { mutableListOf() } += e
    return groups.map { (key, list) -> orderChain(key.first, key.second, list) }
}

/** 单个 target 的组内排序；见 [buildPatchChains] 的判红口径。 */
private fun orderChain(
    root: CorpusPatchRoot,
    target: String,
    list: List<CorpusPatchEntry>,
): CorpusPatchChain {
    if (list.size == 1) return CorpusPatchChain(root, target, list)
    val ids = list.joinToString("/") { it.id }
    fun fail(why: String): Nothing = throw GradleException(
        "corpus-patches 索引：同一 target（$target）的多条补丁不成一条链 —— $ids：$why。" +
            "同 target 多条必须按 bad-sha256 == 前条 fixed-sha256 依次相接，断链/双头一律判红",
    )
    val dupBad = list.groupBy { it.badSha256.lowercase() }.entries.firstOrNull { it.value.size > 1 }
    if (dupBad != null) {
        fail("${dupBad.value.joinToString("/") { it.id }} 的 bad-sha256 相同（${dupBad.key}），先后无从判定")
    }
    val dupFixed = list.groupBy { it.fixedSha256.lowercase() }.entries.firstOrNull { it.value.size > 1 }
    if (dupFixed != null) {
        fail("${dupFixed.value.joinToString("/") { it.id }} 的 fixed-sha256 相同（${dupFixed.key}），续套位置无从判定")
    }
    val fixedSet = list.mapTo(HashSet()) { it.fixedSha256.lowercase() }
    val heads = list.filter { it.badSha256.lowercase() !in fixedSet }
    if (heads.size != 1) {
        fail(
            "链头不唯一（${heads.joinToString("/") { it.id }}）——" +
                "一条链只允许一个起点：它的 bad-sha256 不出现在本组任何 fixed-sha256 里",
        )
    }
    val ordered = mutableListOf(heads.single())
    val used = mutableSetOf(heads.single().id)
    while (ordered.size < list.size) {
        val cur = ordered.last()
        val next = list.filter { it.id !in used && it.badSha256.equals(cur.fixedSha256, ignoreCase = true) }
        if (next.size != 1) {
            fail(
                "${cur.id} 的 fixed-sha256（${cur.fixedSha256}）之后应恰好接一条 bad-sha256 相同的补丁，" +
                    "实际候选 ${next.size} 条（未接上：${list.filter { it.id !in used }.joinToString("/") { it.id }}）",
            )
        }
        ordered += next.single()
        used += next.single().id
    }
    return CorpusPatchChain(root, target, ordered)
}

/**
 * 解析补丁索引并对 [corpusRoot] 做三档判定（**按 target 链**判定，见 [buildPatchChains]）：
 * 真语料 sha 命中链尾 fixed ⇒ 整链跳过；命中链头 bad ⇒ 整链按序套用；命中链头 skip-sha256 ⇒ 整链跳过；
 * 都不命中 ⇒ 判红。判「套用」时 staging 副本 + 套补丁 + 留痕 marker 一次做完
 * （返回 [CorpusPatchPlan.Staged]，erbcc 的 corpus 根换成 [CorpusPatchPlan.Staged.corpusRoot]）。
 * 判「跳过」返回 [CorpusPatchPlan.None]，真语料原样直编。
 *
 * @param patchesDir 补丁资产目录（index.yaml 与补丁文件都在其下；缺 index 即判红）。
 * @param stagingRoot staging 的父目录（调用方给 erbcc build 下的固定位；内容键控子目录由本函数建）。
 * @param foreignCorpus 语料根**不是**本补丁表所属的 eraTW 根时的显式声明（pack `--foreign-corpus`
 *   → `-PeratwForeignCorpus`，ENGC-6 构建期通道）。`true` ⇒ 整表不适用：不 staging、不套用、
 *   也不要求 `index.yaml` 存在，直接原样直编并留痕。
 *   **必须显式声明**：从 `-PeratwEngineProfile` 推不出来——用缺省的 snake 档重编 eraTW 是合法组合，
 *   那种包仍然要吃这批补丁（否则 CB-001/005/006 静默回归）。缺省 `false` = 与今天逐字节相同。
 */
fun planCorpusPatches(
    corpusRoot: File,
    patchesDir: File,
    stagingRoot: File,
    logger: Logger,
    foreignCorpus: Boolean = false,
): CorpusPatchPlan {
    if (!corpusRoot.isDirectory) {
        throw GradleException("corpus-patches：语料根不是目录：$corpusRoot")
    }
    if (foreignCorpus) {
        return CorpusPatchPlan.None(
            listOf("整表不适用（显式声明 foreign-corpus：$corpusRoot 不是本补丁表的 eraTW 语料根），原样直编"),
        )
    }
    val indexFile = File(patchesDir, "index.yaml")
    if (!indexFile.isFile) {
        throw GradleException(
            "corpus-patches：补丁索引缺席（$indexFile）——语料内容补丁机制（specs/corpus-bugs.md §3）" +
                "要求资产随仓；若整目录被移除，请连本机制与 compileCorpus 接线一起撤",
        )
    }
    val entries = parseCorpusPatchIndex(indexFile.readText(Charsets.UTF_8))
    val gameRoot = corpusRoot.parentFile
        ?: throw GradleException("corpus-patches：语料根没有父目录，无法解析 root=game 的目标：$corpusRoot")
    val reasons = mutableListOf<String>()
    // 同一 target 上允许「顺序多补丁」（链）：后一条的 bad-sha256 == 前一条的 fixed-sha256。
    // 语料侧判定对**每条链只做一次**（真语料的 sha 与链头 bad / 链上各跳 fixed 比，一次定出从哪一跳续套），
    // 套用阶段按链序逐环校验 staging 的当前 sha。
    val toApply = mutableListOf<PlannedPatch>()
    for (chain in buildPatchChains(entries)) {
        val targetBase = targetBaseFor(chain.root, corpusRoot, gameRoot)
        val target = resolveUnderRoot(targetBase, chain.target, chain.root)
        if (!target.isFile) {
            reasons += "${chain.idsLabel()} 不适用（目标缺席）：$targetBase 下无 ${chain.target}"
            continue
        }
        val sha = sha256Of(target)
        // 已套到第几跳：真语料 sha == 某条的 fixed ⇒ 前 0..k 跳都已生效，从 k+1 续套。
        // 这让「语料只套过前一半补丁」（别的分支先合了 CB-006）也能自然收敛：跳过已生效的、只套剩下的。
        val resumeAt = chain.entries.indexOfLast { sha.equals(it.fixedSha256, ignoreCase = true) }
        when {
            resumeAt == chain.entries.size - 1 ->
                reasons += "${chain.idsLabel()} 不适用（已是修后版）：${chain.target} 的 sha $sha 与链尾 fixed-sha256 一致，整链跳过"
            resumeAt >= 0 -> {
                val remaining = chain.entries.drop(resumeAt + 1)
                reasons += "${chain.idsLabel()} 部分已生效（停在 ${chain.entries[resumeAt].id}）：" +
                    "${chain.target} 的 sha $sha 与它的 fixed-sha256 一致，续套 ${remaining.joinToString("→") { it.id }}"
                remaining.forEach { toApply += PlannedPatch(it, target, sha) }
            }
            sha.equals(chain.head.badSha256, ignoreCase = true) ->
                chain.entries.forEach { toApply += PlannedPatch(it, target, chain.head.badSha256) }
            chain.head.skipSha256.any { it.equals(sha, ignoreCase = true) } ->
                reasons += "${chain.idsLabel()} 不适用（已知无需补丁的版本）：${chain.target} 的 sha $sha 在链头 skip-sha256 内，整链跳过"
            else -> throw GradleException(
                "corpus-patches ${chain.idsLabel()}：目标 ${chain.target} 的 sha256（$sha）既不是链头病灶版" +
                    "（${chain.head.badSha256}），也不是本链任何一跳的 fixed-sha256（续套起点）→ 语料未知漂移，判红。" +
                    "确认语料换版是有意动作后，同步更新 devtools/corpus-patches/index.yaml 的 sha 与台账；禁止静默跳过",
            )
        }
    }
    // 有补丁要套时，跳过的那些（已是修后版 / 部分已生效 / 目标缺席）也必须上屏：
    // 直编档由调用方打印 reasons，套用档此前不打印 —— 「哪几跳已生效、这次续套哪几跳」是排障第一读数。
    if (toApply.isNotEmpty()) {
        reasons.forEach { logger.lifecycle("[corpus-patches] $it") }
    }
    if (toApply.isEmpty()) {
        // 直编档必须撤下上一轮可能残留的补丁 marker：staging 目录按语料路径定键、跨运行复用，
        // 若不清，下一次 compileCorpus 的 doLast 会把旧留痕追到「本次没套补丁」的产物 manifest
        // 上 —— 留痕必须与本次编译的实际行为一致（套了才留，没套不留）。
        File(corpusStagingDir(corpusRoot, stagingRoot), CORPUS_PATCHES_MARKER).delete()
        return CorpusPatchPlan.None(reasons)
    }

    val stagingDir = corpusStagingDir(corpusRoot, stagingRoot)
    stagingRoot.mkdirs()
    if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
        throw GradleException("corpus-patches：清不掉旧 staging 目录：$stagingDir")
    }
    if (!stagingDir.mkdirs()) {
        throw GradleException("corpus-patches：建不出 staging 目录：$stagingDir")
    }
    // 副本形状 = 游戏根的 erbcc 视角：ERB（语料本体）+ 兄弟件 CSV/ 与 emuera.config
    // （erbcc 的侧车与 ERD/关键字表全从 corpus 根的 parent 取：CSV 目录、emuera.config、
    // UserKeywordTables 只扫 gameRoot 下的 ERB 与 CSV —— 三处都盖住，别的文件 erbcc 不读）。
    val stagedCorpus = File(stagingDir, "ERB")
    var linked = 0
    var copied = 0
    stageTree(corpusRoot, stagedCorpus).let { (l, c) -> linked += l; copied += c }
    val csvSrc = File(gameRoot, "CSV")
    if (csvSrc.isDirectory) {
        stageTree(csvSrc, File(stagingDir, "CSV")).let { (l, c) -> linked += l; copied += c }
    } else {
        logger.lifecycle("[corpus-patches] 语料根旁没有 CSV/（$csvSrc）：staging 副本同样不带，行为与直编一致")
    }
    val configSrc = File(gameRoot, "emuera.config")
    if (configSrc.isFile) {
        if (linkOrCopyFile(configSrc, File(stagingDir, "emuera.config"))) linked++ else copied++
    }
    val applied = mutableListOf<AppliedPatch>()
    val markerLines = mutableListOf<String>()
    for (planned in toApply) {
        val entry = planned.entry
        val patchFile = File(patchesDir, entry.patch)
        if (!patchFile.isFile) {
            throw GradleException("corpus-patches ${entry.id}：补丁资产缺席：$patchFile")
        }
        val stagedBase = targetBaseFor(entry.root, stagedCorpus, stagingDir)
        val stagedTarget = resolveUnderRoot(stagedBase, entry.target, entry.root)
        if (!stagedTarget.isFile) {
            throw GradleException("corpus-patches ${entry.id}：staging 后目标缺席（不该发生）：$stagedTarget")
        }
        // 逐环校验（链式支持的核心）：套用前 staging 的当前内容必须正好是**这一条**的病灶版。
        // 只看套完后的 fixed 不够 —— 上一环漏套/套错或链序颠倒时，unified diff 按内容定位仍可能命中，
        // 于是把错的东西当成本环起点，直到 fixed 对不上才炸（报错点离病灶很远）。
        val stagedBytes = stagedTarget.readBytes()
        val stagedSha = sha256Hex(stagedBytes)
        if (!stagedSha.equals(entry.badSha256, ignoreCase = true)) {
            throw GradleException(
                "corpus-patches ${entry.id}：套用前 staging 目标 ${entry.target} 的 sha（$stagedSha）" +
                    "≠ 该条 bad-sha256（${entry.badSha256}）→ 链上一条未生效或链序错（同 target 多条必须按 " +
                    "bad-sha256 == 前条 fixed-sha256 串链），判红",
            )
        }
        val patched = applyUnifiedDiff(
            original = stagedBytes,
            diffText = patchFile.readText(Charsets.UTF_8),
            patchLabel = entry.id,
            expectedTargetRel = entry.target,
        )
        val patchedSha = sha256Hex(patched)
        if (!patchedSha.equals(entry.fixedSha256, ignoreCase = true)) {
            throw GradleException(
                "corpus-patches ${entry.id}：补丁套用后的 sha（$patchedSha）与 index 的 fixed-sha256" +
                    "（${entry.fixedSha256}）不符 → 补丁资产与 index 判据脱节（或套用器有 bug），判红",
            )
        }
        // 原子替换：临时文件 + move，绝不打开硬链接就地写（就地写会穿透共享 inode、改坏真语料）。
        val tmp = File(stagedTarget.parentFile, stagedTarget.name + ".patching")
        Files.write(tmp.toPath(), patched)
        try {
            Files.move(
                tmp.toPath(), stagedTarget.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), stagedTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // 铁律自证：真语料的目标文件必须仍是**本次续套的基准**原样。
        // （链中段的 bad-sha256 是合成中间态、真语料上本就不存在，拿它比会把正常链判成语料被改。）
        val originalSha = sha256Of(planned.realTarget)
        if (!originalSha.equals(planned.expectedRealSha256, ignoreCase = true)) {
            throw GradleException(
                "corpus-patches ${entry.id}：真语料目标 ${entry.target} 的 sha 在 staging 期间变成 $originalSha" +
                    "（应为续套基准 ${planned.expectedRealSha256}）→ 语料根被改动，铁律红线",
            )
        }
        applied += AppliedPatch(entry.id, entry.target, entry.badSha256, entry.fixedSha256)
        markerLines += "corpus-patch ${entry.id} ${entry.target} ${entry.badSha256} ${entry.fixedSha256}"
        logger.lifecycle(
            "[corpus-patches] 已应用 ${entry.id}：${entry.target}" +
                "（sha ${entry.badSha256} → ${entry.fixedSha256}；编译用 staging 副本 $stagedCorpus）",
        )
    }
    // 逐行留痕：marker 会被 doLast 原样追加进 manifest，再被 CMP-66 按行解析 ——
    // 少一个分隔符会让两条补丁粘成一行（单补丁时代看不出来，CB-005 起的第二补丁会炸）。
    File(stagingDir, CORPUS_PATCHES_MARKER)
        .writeText(markerLines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    logger.lifecycle(
        "[corpus-patches] staging 完成：$stagedCorpus（链接 $linked / 拷贝 $copied 个文件；" +
            "跨卷或链接被拒时逐文件回落拷贝）",
    )
    return CorpusPatchPlan.Staged(stagedCorpus, applied, stagingDir)
}

/** 目标根的选择：[CorpusPatchRoot.CORPUS] 吃语料根本身，[CorpusPatchRoot.GAME] 吃它的父目录。 */
private fun targetBaseFor(root: CorpusPatchRoot, corpusRoot: File, gameRoot: File): File =
    when (root) {
        CorpusPatchRoot.CORPUS -> corpusRoot
        CorpusPatchRoot.GAME -> gameRoot
    }

/**
 * 资产 staging 面的游戏根补丁解析：index 里 `root: game` 的条目，按目标文件 sha 三档判定，
 * 返回「相对游戏根路径（'/' 分隔）→ 应落 staging 的修后字节」。
 *
 * 只对**病灶版**返回字节（已修版返回空、由调用方原样硬链接）；目标缺席与 sha 未知漂移
 * 与编译期同口径判红 —— 出包面与编译面必须吃同一套判据，否则会出现「erbc 修了、随包 CSV 没修」
 * 的半截修复。没有 index 时返回空（staging 照旧，行为与引入本机制前一致）。
 *
 * @param foreignCorpus 与 [planCorpusPatches] 同一个显式声明（pack `--foreign-corpus`）：
 *   `true` ⇒ 整表不适用、返回空映射并留痕，**包括不判「游戏根目标缺席」红**。
 *   这里比编译面多一道红（编译面按 index 头部判据「目标缺席=跳过留痕」放行）是有意的：
 *   eraTW 出包根缺 `CSV/_Rename.csv` 意味着语料根指错或补丁表过期，宁红不静默随包
 *   （`CorpusPatchesTest.出包面_游戏根目标缺席判红` 钉着）。第三方游戏根本来就没有这批文件，
 *   所以放行只能靠显式声明，不能靠「目标恰好缺席」猜——那会把上面那道红一起取消。
 */
fun planGameRootPatches(
    gameRoot: File,
    patchesDir: File,
    logger: Logger,
    foreignCorpus: Boolean = false,
): Map<String, ByteArray> {
    if (foreignCorpus) {
        logger.lifecycle(
            "[corpus-patches] 整表不适用（显式声明 foreign-corpus：$gameRoot 不是本补丁表的 eraTW 游戏根）" +
                "——随包数据原样落 staging",
        )
        return emptyMap()
    }
    val indexFile = File(patchesDir, "index.yaml")
    if (!indexFile.isFile) return emptyMap()
    val entries = parseCorpusPatchIndex(indexFile.readText(Charsets.UTF_8))
        .filter { it.root == CorpusPatchRoot.GAME }
    if (entries.isEmpty()) return emptyMap()
    val out = LinkedHashMap<String, ByteArray>()
    for (entry in entries) {
        val target = resolveUnderRoot(gameRoot, entry.target, CorpusPatchRoot.GAME)
        if (!target.isFile) {
            throw GradleException(
                "corpus-patches ${entry.id}：游戏根目标缺席（$target）——出包面必须与编译面同判据，" +
                    "缺席说明语料根不对或补丁表过期，判红",
            )
        }
        val original = target.readBytes()
        val sha = sha256Hex(original)
        when {
            sha.equals(entry.fixedSha256, ignoreCase = true) ->
                logger.lifecycle("[corpus-patches] ${entry.id}：随包 ${entry.target} 已是修后版（sha $sha），原样链接")
            entry.skipSha256.any { it.equals(sha, ignoreCase = true) } ->
                logger.lifecycle("[corpus-patches] ${entry.id}：随包 ${entry.target} 是已知无需补丁的版本（sha $sha），原样链接")
            sha.equals(entry.badSha256, ignoreCase = true) -> {
                val patchFile = File(patchesDir, entry.patch)
                if (!patchFile.isFile) {
                    throw GradleException("corpus-patches ${entry.id}：补丁资产缺席：$patchFile")
                }
                val patched = applyUnifiedDiff(
                    original = original,
                    diffText = patchFile.readText(Charsets.UTF_8),
                    patchLabel = entry.id,
                    expectedTargetRel = entry.target,
                )
                val patchedSha = sha256Hex(patched)
                if (!patchedSha.equals(entry.fixedSha256, ignoreCase = true)) {
                    throw GradleException(
                        "corpus-patches ${entry.id}：随包补丁套用后的 sha（$patchedSha）与 fixed-sha256" +
                            "（${entry.fixedSha256}）不符 → 补丁资产脱节（或套用器有 bug），判红",
                    )
                }
                out[entry.target] = patched
                logger.lifecycle(
                    "[corpus-patches] ${entry.id}：随包 ${entry.target} 按补丁替换" +
                        "（sha ${entry.badSha256} → ${entry.fixedSha256}）",
                )
            }
            else -> throw GradleException(
                "corpus-patches ${entry.id}：随包目标 ${entry.target} 的 sha256（$sha）既不是病灶版" +
                    "（${entry.badSha256}）也不是修后版（${entry.fixedSha256}）→ 语料未知漂移，判红",
            )
        }
    }
    return out
}

/** 解析目标相对路径，并挡掉越出根的解析（canonical 双向核对）。 */
private fun resolveUnderRoot(root: File, rel: String, rootKind: CorpusPatchRoot): File {
    val f = File(root, rel)
    val rootCanonical = root.canonicalFile
    val fCanonical = f.canonicalFile
    if (fCanonical != rootCanonical && !fCanonical.path.startsWith(rootCanonical.path + File.separator)) {
        throw GradleException("corpus-patches：target 解析越出 root=${rootKind.id} 根：$rel → ${fCanonical.path}")
    }
    return f
}

/**
 * 把 [src] 树逐文件硬链接到 [dstRoot]（相对形状不变）；链接被拒的文件回落流式拷贝。
 *
 * @return linked 与 copied 两个计数（留痕日志口径）。
 */
fun stageTree(src: File, dstRoot: File): Pair<Int, Int> {
    var linked = 0
    var copied = 0
    src.walkTopDown().filter { it.isFile }.forEach { f ->
        val rel = f.toRelativeString(src)
        if (linkOrCopyFile(f, File(dstRoot, rel))) linked++ else copied++
    }
    return linked to copied
}

/**
 * 整文件落 staging：先试同卷硬链接（0 拷贝、0 空间），被拒（跨卷/权限/平台不支持）回落
 * 1 MiB 流式拷贝。与 StageGameAssetsTask.linkOrCopy 同形（链接农场先例）。
 *
 * @return true = 建了链接，false = 走了拷贝
 */
fun linkOrCopyFile(src: File, dst: File): Boolean {
    dst.parentFile?.mkdirs()
    if (dst.exists()) dst.delete()
    return try {
        Files.createLink(dst.toPath(), src.toPath())
        true
    } catch (_: Throwable) {
        copyFileFallback(src, dst)
        false
    }
}

/** 流式拷贝（1 MiB 块，不整份进堆）：链接回落路径 + 其定向单测的直测面。 */
fun copyFileFallback(src: File, dst: File) {
    RandomAccessFile(src, "r").use { input ->
        FileOutputStream(dst).use { output ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
    }
}

/** 摘要字节 → 小写十六进制（留痕行的唯一写法）。 */
private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// ---------------------------------------------------------------------------
// unified diff 的窄口径套用器
//
// 资产是我们自己出的补丁文件（单文件头 + 若干 hunk），但目标语料会在两机间漂移：
// git 的 autocrlf 会把补丁工作副本在 LF/CRLF 之间改写，语料文件本身又常是 CRLF 带 BOM
// ——所以匹配按「行内容逐字节、终止符归原文件」的口径：
//   - 上下文与被删行：内容（不含行终止符）必须与语料行**逐字节相等**，无空白宽容；
//     终止符不参与匹配（补丁工作副本的 EOL 被 git 改写不算漂移）。
//   - 保留行：沿用语料自己的终止符与 BOM（补丁改哪行只动哪行，其余字节原样）。
//   - 新增行：取「插入点前一条语料行」的终止符（补丁自己不提供权威 EOL）；diff 里显式
//     带 no-newline 标记的按无终止符落。极端 EOL 边缘（如把 EOF 行换成带终止符的新行）
//     由 planCorpusPatches 的 fixed-sha256 双重判据兜底：不符即判红，不会静默写歪。
// 行号只做定位提示：期望位不中时按内容找全文唯一命中（行号漂移放行并依赖内容逐字门槛）；
// 命中 0 处或多处都判红。
// ---------------------------------------------------------------------------

/** 语料/中间结果的一行：内容不含终止符，终止符取原文（"" 表示文件末行无换行）。 */
private class RawLine(val content: ByteArray, val terminator: String)

/** diff hunk 正文的一行。 */
private class DiffLine(val op: Char, val content: String, val noNewline: Boolean)

/** 一个 hunk：头部行号/计数 + 有序正文。 */
private class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val body: List<DiffLine>,
)

private val HUNK_RE = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

/**
 * diff 文本 → 行内容（剥终止符）。只在**见到终止符**时落一行：串尾的终止符不会多产出
 * 一个幽灵空行（朴素 lineSequence 会）；无终止符的末行照落。CRLF 与 LF 同等对待。
 */
private fun diffTextLines(text: String): List<String> {
    val out = mutableListOf<String>()
    var start = 0
    for (i in text.indices) {
        if (text[i] == '\n') {
            var end = i
            if (end > start && text[end - 1] == '\r') end--
            out += text.substring(start, end)
            start = i + 1
        }
    }
    if (start < text.length) out += text.substring(start)
    return out
}

/**
 * 把 [diffText] 套到 [original] 上，返回修后的完整字节。
 *
 * @param patchLabel 判红文案用的补丁 id。
 * @param expectedTargetRel index 里登记的目标相对路径；diff 的 a/ 与 b/ 头都必须等于它
 *   （防「补丁与 index 指向不同文件」的脱节资产混进来）。
 * @throws GradleException 补丁结构不合法、目标头不符、上下文逐字匹配失败或多处命中。
 */
fun applyUnifiedDiff(original: ByteArray, diffText: String, patchLabel: String, expectedTargetRel: String): ByteArray {
    val hunks = parseUnifiedDiff(diffText, patchLabel, expectedTargetRel)
    var lines = splitLines(original)
    var offset = 0
    for ((idx, hunk) in hunks.withIndex()) {
        val pre = hunk.body.filter { it.op == ' ' || it.op == '-' }
            .map { it.content.toByteArray(Charsets.UTF_8) }
        val postCount = hunk.body.count { it.op == ' ' || it.op == '+' }
        val matchAt = locate(lines, pre, hunk, offset, patchLabel, idx + 1)
        val rebuilt = mutableListOf<RawLine>()
        var cursor = matchAt
        for (e in hunk.body) {
            when (e.op) {
                ' ' -> {
                    rebuilt += lines[cursor]
                    cursor++
                }
                '-' -> cursor++
                '+' -> {
                    val anchor = addedLineTerminator(lines, matchAt, cursor, matchAt + pre.size)
                    rebuilt += RawLine(e.content.toByteArray(Charsets.UTF_8), if (e.noNewline) "" else anchor)
                }
                '\\' -> Unit // no-newline 标记已在解析期折进前一行
            }
        }
        val consumed = cursor - matchAt
        lines = lines.subList(0, matchAt) + rebuilt + lines.subList(matchAt + consumed, lines.size)
        offset += postCount - pre.size
    }
    return joinLines(lines)
}

/** 解析 diff 文本：单文件头（a/b 都必须等于 expected）+ 至少一个 hunk，行数当场对账。 */
private fun parseUnifiedDiff(diffText: String, patchLabel: String, expectedTargetRel: String): List<DiffHunk> {
    // 行序列用带终止符的切分（[diffTextLines]）：lineSequence 之类的朴素切分在「串尾终止符」
    // 上会多产出一个幽灵空行，hunk 正文循环会把它当空上文行吃掉，行数对账必炸。
    val lines = diffTextLines(diffText)
    val hunks = mutableListOf<DiffHunk>()
    var headerSeen = 0
    var oldPath: String? = null
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("--- ") -> {
                headerSeen++
                if (headerSeen > 1) {
                    throw GradleException("corpus-patches $patchLabel：补丁含多个文件头（index 是单文件口径）：$line")
                }
                oldPath = diffHeaderPath(line.removePrefix("--- "), patchLabel, "a")
                i++
                if (i >= lines.size || !lines[i].startsWith("+++ ")) {
                    throw GradleException("corpus-patches $patchLabel：--- 头后必须紧跟 +++ 头")
                }
                val newPath = diffHeaderPath(lines[i].removePrefix("+++ "), patchLabel, "b")
                i++ // +++ 头已消费；不停在这行（否则外层 when 会把它当「不认识的行」误判红）
                if (oldPath != expectedTargetRel || newPath != expectedTargetRel) {
                    throw GradleException(
                        "corpus-patches $patchLabel：补丁头（$oldPath / $newPath）与 index 的 target" +
                            "（$expectedTargetRel）不一致 → 资产脱节，判红",
                    )
                }
            }
            line.startsWith("@@") -> {
                val m = HUNK_RE.find(line)
                    ?: throw GradleException("corpus-patches $patchLabel：hunk 头不认识：'$line'")
                val oldStart = m.groupValues[1].toInt()
                val oldCount = m.groupValues[2].ifEmpty { "1" }.toInt()
                val newStart = m.groupValues[3].toInt()
                val newCount = m.groupValues[4].ifEmpty { "1" }.toInt()
                i++
                val body = mutableListOf<DiffLine>()
                while (i < lines.size && !lines[i].startsWith("@@") && !lines[i].startsWith("--- ")) {
                    val l = lines[i]
                    when {
                        l.isEmpty() -> body += DiffLine(' ', "", false) // 空行 = 内容为空的上文行
                        l[0] == ' ' || l[0] == '-' || l[0] == '+' -> body += DiffLine(l[0], l.substring(1), false)
                        l[0] == '\\' -> {
                            val last = body.lastOrNull()
                                ?: throw GradleException("corpus-patches $patchLabel：no-newline 标记前没有正文行")
                            body[body.size - 1] = DiffLine(last.op, last.content, true)
                        }
                        else -> throw GradleException(
                            "corpus-patches $patchLabel：diff 正文出现不认识的行：'$l'（只认 空格/-/+ 反斜杠）",
                        )
                    }
                    i++
                }
                val ctx = body.count { it.op == ' ' }
                val del = body.count { it.op == '-' }
                val add = body.count { it.op == '+' }
                if (ctx + del != oldCount) {
                    throw GradleException(
                        "corpus-patches $patchLabel：hunk ${hunks.size + 1} 的旧侧行数（@@ 记 $oldCount）" +
                            "与正文（上下文 $ctx + 删除 $del = ${ctx + del}）不符",
                    )
                }
                if (ctx + add != newCount) {
                    throw GradleException(
                        "corpus-patches $patchLabel：hunk ${hunks.size + 1} 的新侧行数（@@ 记 $newCount）" +
                            "与正文（上下文 $ctx + 新增 $add = ${ctx + add}）不符",
                    )
                }
                hunks += DiffHunk(oldStart, oldCount, newStart, newCount, body)
                continue // i 已停在下一个头/正文外
            }
            line.isBlank() || line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("#") -> i++
            else -> throw GradleException(
                "corpus-patches $patchLabel：diff 里不认识的行：'$line'（只认 ---/+++/@@ 与 hunk 正文）",
            )
        }
    }
    if (headerSeen == 0) {
        throw GradleException("corpus-patches $patchLabel：补丁缺 --- / +++ 文件头")
    }
    if (hunks.isEmpty()) {
        throw GradleException("corpus-patches $patchLabel：补丁没有任何 hunk")
    }
    return hunks
}

/** diff 头路径：剥 a/ 或 b/ 前缀；引用路径与 /dev/null（新增/删除文件）不支持，判红。 */
private fun diffHeaderPath(raw: String, patchLabel: String, side: String): String {
    val t = raw.trim()
    if (t.startsWith("\"")) {
        throw GradleException("corpus-patches $patchLabel：引用式路径头不支持：$t")
    }
    if (t == "/dev/null") {
        throw GradleException("corpus-patches $patchLabel：/dev/null 头（新增/删除文件）不支持：$t")
    }
    val prefix = "$side/"
    return if (t.startsWith(prefix)) t.removePrefix(prefix) else t
}

/**
 * 定位 hunk：上下文块在全文**逐字唯一**命中才套（期望行号只进判红文案，不参与放宽 ——
 * 语料内容补丁钉的是唯一病灶位，全文多处同形说明语料形状超出补丁判据，宁红不猜）。
 * 0 处或多处都判红；行号漂移（唯一命中但不在 @@ 记位）按内容放行。
 */
private fun locate(
    lines: List<RawLine>,
    pre: List<ByteArray>,
    hunk: DiffHunk,
    offset: Int,
    patchLabel: String,
    hunkNo: Int,
): Int {
    if (pre.isEmpty()) {
        // 纯新增 hunk（旧侧 0 行）：没有内容可逐字匹配，按行号钉位 —— oldCount==0 时
        // 统一 diff 语义是「插在第 oldStart 行之后」，0 基下标即 oldStart。
        val at = (if (hunk.oldCount == 0) hunk.oldStart else hunk.oldStart - 1) + offset
        return at.coerceIn(0, lines.size)
    }
    val expectedLine = hunk.oldStart + offset
    var found = -1
    for (i in 0..lines.size - pre.size) {
        if (matchesAt(lines, pre, i)) {
            if (found >= 0) {
                throw GradleException(
                    "corpus-patches $patchLabel：hunk $hunkNo 的上下文块在语料中出现多处（行 ${found + 1} 与 ${i + 1}），" +
                        "无法唯一定位 → 判红；请收紧补丁上下文",
                )
            }
            found = i
        }
    }
    if (found < 0) {
        throw GradleException(
            "corpus-patches $patchLabel：hunk $hunkNo 的上下文与语料逐字匹配失败" +
                "（@@ 记位行 $expectedLine 附近；上文首行：'${pre.firstOrNull()?.toString(Charsets.UTF_8)}'）" +
                "→ 语料内容与补丁判据不符，判红",
        )
    }
    return found
}

private fun matchesAt(lines: List<RawLine>, pre: List<ByteArray>, at: Int): Boolean {
    for ((j, want) in pre.withIndex()) {
        if (!lines[at + j].content.contentEquals(want)) return false
    }
    return true
}

/**
 * 新增行的终止符：取插入点前一条**原文行**的终止符（cursor 已推进 = 前一条是被替换/上下文行；
 * hunk 以新增开头则取块前一行；文件开头则取块后一行；空文件兜底 CRLF —— 与本仓语料的
 * 主导 EOL 一致）。
 */
private fun addedLineTerminator(
    lines: List<RawLine>,
    matchAt: Int,
    cursor: Int,
    afterBlock: Int,
): String {
    if (cursor > matchAt && cursor - 1 < lines.size) return lines[cursor - 1].terminator
    if (matchAt > 0) return lines[matchAt - 1].terminator
    if (afterBlock < lines.size) return lines[afterBlock].terminator
    return "\r\n"
}

/** 按行拼回字节：内容原样 + 各行自己的终止符（ASCII）。 */
private fun joinLines(lines: List<RawLine>): ByteArray {
    val out = ByteArrayOutputStream()
    for (l in lines) {
        out.write(l.content)
        out.write(l.terminator.toByteArray(Charsets.US_ASCII))
    }
    return out.toByteArray()
}

/** 字节 → 行（保留终止符）：唯一终止符是 CRLF 与 LF，末行可以无终止符。 */
private fun splitLines(bytes: ByteArray): List<RawLine> {
    val out = mutableListOf<RawLine>()
    var start = 0
    var i = 0
    while (i < bytes.size) {
        if (bytes[i] == '\n'.code.toByte()) {
            var contentEnd = i
            var terminator = "\n"
            if (contentEnd > start && bytes[contentEnd - 1] == '\r'.code.toByte()) {
                contentEnd--
                terminator = "\r\n"
            }
            out += RawLine(bytes.copyOfRange(start, contentEnd), terminator)
            i++
            start = i
        } else {
            i++
        }
    }
    if (start < bytes.size) out += RawLine(bytes.copyOfRange(start, bytes.size), "")
    return out
}
