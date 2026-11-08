package com.eratw.port.buildlogic

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import org.gradle.api.GradleException
import org.gradle.api.logging.Logger

/**
 * 随包清单（erbc.v1/manifest.txt）认识的格式版本，与 erbcc 的 ErbcArtifacts.MANIFEST_FORMAT 同值：
 * buildSrc（与 app 的 buildscript classpath）上没有 erbcc 的编译产物（配置期也拿不到那个对象），
 * 所以写真值；改格式要两边一起改（CMP-74）。
 *
 * - 缺 manifest-format 行 = 旧格式 v1（artifacts 1 只登记 program.erbc）；
 * - 大于本值 = 打包脚本还不认识的未来格式，拒绝，而不是猜字段。
 */
const val ERATW_MANIFEST_FORMAT = 2

/**
 * 当前 erbcc 版本的单源 = `core-vm/.../Version.kt` 的 `const val VERSION`。
 *
 * 为什么读源码而不是复用某个常量：buildSrc（与 app 的 buildscript classpath）上没有
 * core-vm 的编译产物（配置期也拿不到 `com.eratw.port.core.Version` 这个对象），
 * 而再手写一遍版本串必然漂移。这个文件是 `Version` 的唯一落点
 * （`Version.NAME` 在下一行，正则只认 `VERSION`）。
 */
fun expectedErbccVersion(repoRoot: File): String {
    val src = File(repoRoot, "core-vm/src/main/kotlin/com/eratw/port/core/Version.kt")
    if (!src.isFile) {
        throw GradleException("找不到版本单源 ${src}：CMP-66 的 version 对账没有判据")
    }
    val m = Regex("""\bVERSION\s*:\s*String\s*=\s*"([^"]+)"""").find(src.readText())
    return m?.groupValues?.get(1)
        ?: throw GradleException("无法从 ${src} 解析 Version.VERSION（CMP-66 的 version 对账没有判据）")
}

/** 文件 SHA-256 小写十六进制（流式，328 MB 的 program.erbc 不整份进堆）。 */
fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    RandomAccessFile(file, "r").use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** 摘要字节 → 小写十六进制（staging 清单 / package.report 的唯一写法）。 */
fun ByteArray.hexLower(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

/**
 * 编译器指纹（CMP-66 的另一半）：与 erbcc 的 `ErbcCompilerDigest` **同口径** ——
 * 取 `core-vm/src/main/kotlin` 整树 .kt（编译链跨 compile/code/expr/lexer/stmt/vars 等包，
 * 逐包枚举会漏），按相对**仓库根**的路径（`\`→`/`）字典序，逐文件
 * `u32be(路径字节数) ‖ 路径 UTF-8 ‖ u32be(文件字节数) ‖ 文件字节` 喂 SHA-256。
 *
 * 为什么在构建逻辑里重写一遍而**不**依赖 erbcc 的类：app 的 buildscript classpath 上没有
 * erbcc/core-vm 的编译产物（同 [expectedErbccVersion] 的处境），而给 `stageGameAssets` 挂
 * 跨项目 `dependsOn` 会撞 classloader scope 竞态（见 [verifyErbcFreshness] 的说明）。
 * **换文件集或换编码口径必须两处一起改**（erbc 的 `ErbcCompilerDigest.kt` 与本函数），
 * 否则清单与打包期会互相判红。
 *
 * @param repoRoot 仓库根（核心源码树 `core-vm/src/main/kotlin` 挂在其下）。
 * @return digest（小写十六进制）与参与计算的文件数
 */
fun compilerDigestOf(repoRoot: File): Pair<String, Int> {
    val srcRoot = File(repoRoot, "core-vm/src/main/kotlin")
    if (!srcRoot.isDirectory) {
        throw GradleException("找不到编译器源码树 $srcRoot：compiler-digest 没有判据")
    }
    val files = srcRoot.walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .map { it.toRelativeString(repoRoot).replace('\\', '/') to it }
        .sortedBy { it.first }
        .toList()
    if (files.isEmpty()) {
        throw GradleException("编译器源码树 $srcRoot 里没有 .kt 文件：compiler-digest 没有判据")
    }
    val digest = MessageDigest.getInstance("SHA-256")
    for ((rel, file) in files) {
        val relBytes = rel.toByteArray(Charsets.UTF_8)
        val bytes = file.readBytes()
        digest.update(ByteBuffer.allocate(4).putInt(relBytes.size).array())
        digest.update(relBytes)
        digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } to files.size
}

/**
 * CMP-66：显式 erbc 目录的**新鲜度对账**（`-PeratwErbcDir` / `ERATW_ERBC_DIR`，以及兜底目录）。
 *
 * 背景：`stageGameAssets` 刻意不挂 `dependsOn(:tools:erbcc:compileCorpus)`
 * （configure-on-demand 下跨项目依赖会撞 classloader scope，2026-10-22 实测），所以它只能
 * 「读现成目录」。兜底目录已有 `-PeratwRequireComplete` 拒绝，但**显式 pin 的目录连 version
 * 与源 sha 都不对账** ⇒ 改过编译器代码或语料之后照样把旧产物打进包（BOARD CMP-66）。
 *
 * 判据全部来自 erbcc 落盘时写的确定性 `manifest.txt`（[ErbcArtifacts.manifestText]）：
 * 1. **version**：`erbcc <v>` 必须等于当前源码的 `Version.VERSION`；`build-mode` 必须是
 *    `full`（`--limit` 出来的受限产物不得随包）；`container-version` 必须与
 *    `program.erbc` 头里的真实版本一致（magic `ERBC` + u16 小端，防截断/串包）。
 * 2. **产物自证**：`artifact erbc.v1/program.erbc <bytes> <sha256>` 与磁盘字节逐位一致。
 * 3. **源 sha**：`source <rel> <bytes> <sha256>` 与语料根的**当前**文件逐个对账，文件集
 *    （增/删）也必须一致 —— 语料改过而没重编 ⇒ 拒绝。
 * 4. **编译器指纹**：`compiler-digest` 必须等于当前 `core-vm/src/main/kotlin` 整树重算值
 *    （[compilerDigestOf]）—— 编译器改过而没重编 ⇒ 拒绝。缺这一行（旧格式/旧编译器产物）
 *    同样拒绝：语料摘要与产物摘要在「只有编译器换了」时全都对得上，这一行是唯一判据。
 * 5. **语料补丁对账**：manifest 末尾的 `corpus-patch <id> <相对路径> <坏版sha> <修后sha>`
 *    追加行（compileCorpus 套补丁后写入）是唯一的「编译期改写」豁免口 —— 某源文件
 *    磁盘=坏版 sha 且清单=修后版 sha 且两侧都与留痕行一致时按已应用放行（真语料只读，
 *    补丁只作用于编译期 staging 副本，磁盘慢一步是预期形状）；此外的任何漂移照旧拒绝。
 *
 * @param corpusRoot 优先用调用方给的「本次随包语料根」（`<gameRoot>/ERB`）；给不出时退回
 *   manifest 里登记的 `corpus-root`（编译机路径，同一台机器上可用）。
 * @param hard true = 显式 pin（用户意图就是「用这一份」），任何不符直接 [GradleException]；
 *   false = 兜底目录（保持既有政策：警告 + `package.report` 标记，正式包由
 *   `-PeratwRequireComplete` 在别处挡）。
 * @param repoRoot 仓库根（[expectedErbccVersion] 与 [compilerDigestOf] 的源码树挂在其下）。
 * @param logger 告警出口（任务动作里传任务自己的 logger；文案带 stageGameAssets 前缀）。
 * @return 写进 `package.report` 的 `erbc-freshness` 值；null = 未能验证（已按 hard 处理）。
 */
fun verifyErbcFreshness(
    dir: File,
    corpusRoot: File?,
    hard: Boolean,
    repoRoot: File,
    logger: Logger,
): String? {
    fun reject(msg: String): String? {
        if (hard) throw GradleException("CMP-66 新鲜度对账失败：$msg")
        logger.warn("stageGameAssets: $msg")
        return null
    }

    val manifest = File(dir, "manifest.txt")
    if (!manifest.isFile) {
        return reject(
            "erbc 目录缺 manifest.txt（$dir）→ 无法证明产物出自当前编译器/当前语料；" +
                "先跑 ./gradlew :tools:erbcc:compileCorpus，或用 -PeratwErbcDir 指向最新产物目录",
        )
    }
    val lines = manifest.readLines()
    fun field(name: String): String? =
        lines.firstOrNull { it.startsWith("$name ") }?.removePrefix("$name ")?.trim()

    // ---- 0. 清单格式（CMP-74）-----------------------------------------------
    // 缺 `manifest-format` = 旧格式 v1（只有 `artifacts 1`，侧车不在表里）：宽松放行，
    // 随包清单由本任务补 package 段；比打包脚本新的格式则拒绝 —— 未来字段不可猜。
    val manifestFormat = field("manifest-format")?.toIntOrNull()
    if (manifestFormat != null && manifestFormat > ERATW_MANIFEST_FORMAT) {
        return reject(
            "manifest 格式 ${manifestFormat} 比本打包脚本认识的 ${ERATW_MANIFEST_FORMAT} 新：" +
                "升级打包脚本后再出包（旧脚本猜新字段会把清单对账跳过）",
        )
    }
    if (manifestFormat == null) {
        logger.warn(
            "stageGameAssets: manifest 无 manifest-format 行（旧格式 v1，artifacts 只登记 program.erbc）：" +
                "随包清单会按实际文件补 package 段；建议重跑 ./gradlew :tools:erbcc:compileCorpus 拿新格式",
        )
    }

    // ---- 1. version ---------------------------------------------------------
    val expected = expectedErbccVersion(repoRoot)
    val declared = field("erbcc")
    if (declared != expected) {
        return reject(
            "产物版本与当前编译器不一致：manifest 记 erbcc ${declared ?: "(缺)"}，源码是 $expected → " +
                "改过编译器就必须重编产物（./gradlew :tools:erbcc:compileCorpus）",
        )
    }
    val buildMode = field("build-mode")
    if (buildMode != "full") {
        return reject(
            "产物不是全量档（build-mode ${buildMode ?: "(缺；旧 manifest)"}）：受限产物（--limit N）不得随包，" +
                "请用 :tools:erbcc:compileCorpus 的全量产物",
        )
    }

    // ---- 1b. 编译器指纹 -----------------------------------------------------
    // 语料没改、产物自洽，唯独编译器源码换了（红 J 的 NEXT/STEP 死循环就是这样混进包的）：
    // 上面的 version 行只认 erbcc 的版本串，改编译器实现不动 VERSION 时它照样放行。
    val declaredCompilerDigest = field("compiler-digest")
    if (declaredCompilerDigest == null) {
        return reject(
            "manifest 没有 compiler-digest 行（旧编译器/旧格式产物）→ 无法证明产物出自当前编译器；" +
                "先跑 ./gradlew :tools:erbcc:compileCorpus 再出包",
        )
    }
    val (currentCompilerDigest, compilerFileCount) = compilerDigestOf(repoRoot)
    if (!declaredCompilerDigest.equals(currentCompilerDigest, ignoreCase = true)) {
        return reject(
            "产物由旧编译器产出：manifest compiler-digest $declaredCompilerDigest" +
                "（compiler-sources ${field("compiler-sources") ?: "(缺)"} 个），当前编译器源码树 " +
                "$currentCompilerDigest（$compilerFileCount 个）→ 先跑 ./gradlew :tools:erbcc:compileCorpus 再出包",
        )
    }
    val program = File(dir, "program.erbc")
    if (!program.isFile) return reject("erbc 目录缺 program.erbc：$dir")
    val declaredContainerVersion = field("container-version")?.toIntOrNull()
    val head = ByteArray(6)
    try {
        RandomAccessFile(program, "r").use { it.readFully(head) }
    } catch (e: Exception) {
        return reject("program.erbc 读不出 6 字节头（${e::class.simpleName}: ${e.message}）：$program")
    }
    val headMagic = String(head, 0, 4, Charsets.US_ASCII)
    if (headMagic != "ERBC") {
        return reject("program.erbc 头 magic 不是 ERBC（实际 '${headMagic.escape()}')：$program")
    }
    val headVersion = (head[4].toInt() and 0xff) or ((head[5].toInt() and 0xff) shl 8)
    if (declaredContainerVersion == null || declaredContainerVersion != headVersion) {
        return reject(
            "manifest 的 container-version（${declaredContainerVersion ?: "(缺)"}）与 program.erbc 头里的版本" +
                "（$headVersion）不一致：清单与产物不同源",
        )
    }

    // ---- 2. 产物自证 --------------------------------------------------------
    val artifact = lines.firstOrNull { it.startsWith("artifact ") }?.split(' ')
    if (artifact == null || artifact.size != 4 || !artifact[1].endsWith("program.erbc")) {
        return reject("manifest 缺 `artifact ... program.erbc` 条目：$manifest")
    }
    val programSha = sha256Of(program)
    val declaredArtifactSha = artifact[3]
    if (!declaredArtifactSha.equals(programSha, ignoreCase = true)) {
        return reject("program.erbc 与 manifest 登记的 sha256 不一致：清单 $declaredArtifactSha，实际 $programSha")
    }
    val declaredArtifactBytes = artifact[2].toLongOrNull()
    if (declaredArtifactBytes != program.length()) {
        return reject("program.erbc 大小与 manifest 登记不一致：清单 ${declaredArtifactBytes ?: "?"}，实际 ${program.length()}")
    }

    // ---- 3. 源 sha（语料文件集 + 逐文件摘要）--------------------------------
    val sources = lines.filter { it.startsWith("source ") }.mapNotNull { line ->
        // 行形 `source <相对路径> <字节数> <sha256>`，路径里可能有空格（eratw 语料里
        // `DLC/DLC FUNCTION.ERB`、`MOVEMENTS/自由行動/FREE_ACT_19 抱卵.ERB` 都是），
        // 所以必须**从右往左**切：末段是摘要、倒数第二段是字节数，其余整段是路径。
        //
        // 旧实现 `split(' ', limit = 4)` 是从**左**切，`p[1]` 只拿到路径的第一个词
        // （`DLC/DLC`），`p[2]` 拿到 `FUNCTION.ERB` 而不是字节数 ⇒ 该条被当成坏行。
        // 更糟的是这种「截断后的路径」会大量重复，`declaredSet` 去重后从 3971 塌成 1738，
        // 报出来的错是「清单 1738 / 磁盘 3971 → 语料改过但没重编」，**把真因指向语料**，
        // 实际语料一个字都没动（2026-10-26 出 release 包时实测）。
        // 判据与 erbcc 侧 `ArtifactFreshness.entry` 逐字同口径（那边一开始就是右切的）。
        val rest = line.removePrefix("source ")
        val shaSep = rest.lastIndexOf(' ')
        if (shaSep <= 0) return@mapNotNull null
        val sizeSep = rest.lastIndexOf(' ', shaSep - 1)
        if (sizeSep <= 0) return@mapNotNull null
        val bytes = rest.substring(sizeSep + 1, shaSep).toLongOrNull() ?: return@mapNotNull null
        Triple(rest.substring(0, sizeSep), bytes, rest.substring(shaSep + 1))
    }
    val declaredCount = field("source-files")?.toIntOrNull()
    if (declaredCount == null || declaredCount != sources.size) {
        return reject("manifest 的 source 条目数（${sources.size}）与 source-files（${declaredCount ?: "(缺)"}）不一致")
    }
    val corpus = corpusRoot?.takeIf { it.isDirectory }
        ?: field("corpus-root")?.let { File(it) }?.takeIf { it.isDirectory }
    if (corpus == null) {
        logger.warn(
            "stageGameAssets: 找不到语料根（既没给 -PeratwGameRoot/ERATW_GAMEDIR 的有效 ERB/，" +
                "manifest 的 corpus-root 也不在本机）→ CMP-66 只验到 version/容器摘要，**源 sha 未对账**",
        )
        return "version-only(源的 sha 未对账：找不到语料根)"
    }
    val actual = corpus.walkTopDown()
        .filter { it.isFile }
        .filter { it.name.endsWith(".erb", ignoreCase = true) || it.name.endsWith(".erh", ignoreCase = true) }
        .filter { !it.name.startsWith("GOLDEN_", ignoreCase = true) }
        .map { it.toRelativeString(corpus).replace('\\', '/') }
        .sorted()
        .toList()
    val declaredSet = sources.map { it.first }.toSortedSet()
    if (actual.toSet() != declaredSet) {
        val missing = declaredSet - actual.toSet()
        val extra = actual.toSet() - declaredSet
        return reject(
            "语料文件集与产物清单不一致（清单 ${declaredSet.size} / 磁盘 ${actual.size}）：" +
                "磁盘缺 ${missing.take(3)}${if (missing.size > 3) "…" else ""}；" +
                "清单缺 ${extra.take(3)}${if (extra.size > 3) "…" else ""} → 语料改过但没重编（语料根 $corpus）",
        )
    }
    // 编译期语料补丁的留痕行（CorpusPatches 经 compileCorpus doLast 追加）：
    // `corpus-patch <id> <相对路径> <坏版sha> <修后sha>`。路径可能含空格 → 与 source 行
    // 同一个右切法：末段=修后sha、倒数第二段=坏版sha、首段=id，其余整段是路径。
    val corpusPatches = lines.filter { it.startsWith("corpus-patch ") }.mapNotNull { line ->
        val rest = line.removePrefix("corpus-patch ")
        val fixedSep = rest.lastIndexOf(' ')
        if (fixedSep <= 0) return@mapNotNull null
        val badSep = rest.lastIndexOf(' ', fixedSep - 1)
        if (badSep <= 0) return@mapNotNull null
        val idSep = rest.indexOf(' ')
        if (idSep <= 0 || idSep >= badSep) return@mapNotNull null
        CorpusPatchTrace(
            id = rest.substring(0, idSep),
            rel = rest.substring(idSep + 1, badSep),
            badSha = rest.substring(badSep + 1, fixedSep),
            fixedSha = rest.substring(fixedSep + 1),
        )
    }
    var checked = 0
    var reconciled = 0
    for ((rel, bytes, sha) in sources) {
        val f = File(corpus, rel)
        if (!f.isFile || bytes == null) {
            return reject("语料文件缺失或 manifest 行结构不对：source $rel")
        }
        val trace = corpusPatches.firstOrNull { it.rel == rel }
        val actualSha = sha256Of(f)
        val patchReconciled = trace != null &&
            actualSha.equals(trace.badSha, ignoreCase = true) &&
            sha.equals(trace.fixedSha, ignoreCase = true)
        if (patchReconciled) {
            // 编译期补丁的预期形状：磁盘仍是病灶版（语料根只读，补丁只在 staging 副本上生效）、
            // 清单登记修后版，两侧 sha 都与留痕行逐字一致才豁免；豁免口只此一处。
            logger.lifecycle(
                "stageGameAssets: CMP-66 语料补丁对账：$rel 磁盘=病灶版 sha（${trace.id} 已在编译期" +
                    "staging 副本应用，manifest 登记修后版）→ 按 corpus-patch 留痕放行",
            )
            reconciled++
        } else {
            if (f.length() != bytes) {
                return reject("语料文件大小变了：$rel（清单 $bytes，实际 ${f.length()}）→ 语料改过但没重编")
            }
            if (!actualSha.equals(sha, ignoreCase = true)) {
                return reject("语料文件 sha256 变了：$rel（清单 $sha，实际 $actualSha）→ 语料改过但没重编")
            }
        }
        checked++
    }
    val patchNote = if (corpusPatches.isEmpty()) "" else {
        "，补丁 " + corpusPatches.map { it.id }.distinct().joinToString(",") +
            "（对账放行 " + reconciled + " 个目标）"
    }
    return "verified(erbcc $expected, compiler ${currentCompilerDigest.take(12)}, " +
        "container v$headVersion, program $programSha, 源 $checked 个文件来自 $corpus" + patchNote + ")"
}

/** manifest 里一条 `corpus-patch` 留痕行（编译期语料补丁的应用记录）。 */
private class CorpusPatchTrace(
    val id: String,
    val rel: String,
    val badSha: String,
    val fixedSha: String,
)

/** manifest 里的版本串可能带控制字符；报错文案里把不可见字符转义，免得日志里看不见差异。 */
private fun String.escape(): String = buildString {
    for (c in this@escape) {
        if (c.code in 32..126) append(c) else append("\\u%04x".format(c.code))
    }
}
