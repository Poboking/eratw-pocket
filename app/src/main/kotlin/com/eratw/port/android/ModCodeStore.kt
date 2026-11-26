package com.eratw.port.android

import android.util.Log
import com.eratw.port.core.save.SaveDiagnostics
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * 路 B dex 包在设备上的落地目录名：`mods/rt2/code/`（rt2 根与
 * [ModRuntimeAssembly.manifestDirFor] 同源，本类不自己拼 `mods/rt2`）。
 */
const val MOD_CODE_DIR_NAME: String = "code"

/** 内容寻址包体的扩展名：文件名 = `<sha256 前 16 位>.mod`。 */
const val MOD_CODE_ARTIFACT_EXTENSION: String = ".mod"

/** 内容寻址键长度：sha256 hex 前 16 位（文件名携带的地址）。 */
private const val SHA_PREFIX_LENGTH: Int = 16

/** 完整 sha256 hex 长度（[ModCodeStore.install] 返回的键）。 */
private const val SHA256_HEX_LENGTH: Int = 64

private const val TAG: String = "ModCodeStore"

/** 临时文件前缀/后缀：与最终产物同目录，保证 rename 不跨文件系统（rename 才能原子）。 */
private const val TMP_PREFIX: String = "code-"
private const val TMP_SUFFIX: String = ".tmp"

private const val NIBBLE_BITS: Int = 4
private const val NIBBLE_MASK: Int = 0x0F
private const val HEX_DIGITS_PER_BYTE: Int = 2
private const val SHA_BUFFER_SIZE: Int = 8192
private const val HEX_DIGITS: String = "0123456789abcdef"

/** 文件名里的地址键形状：只认小写 16 位 hex（大写/其它字符一律当坏名跳过）。 */
private val SHA_PREFIX_PATTERN: Regex = Regex("[0-9a-f]{$SHA_PREFIX_LENGTH}")

/** 调用方声明的完整 sha256 形状（大小写不敏感，比较时统一小写）。 */
private val SHA256_HEX_PATTERN: Regex = Regex("[0-9a-f]{$SHA256_HEX_LENGTH}", RegexOption.IGNORE_CASE)

/**
 * 一件已落地的 dex 产物。
 *
 * @property sha256Hex 内容寻址键。`install` 返回**完整 64 位** hex；`list` 只从文件名恢复
 *   前 16 位前缀（列旧版本时不重读包体做全量哈希），因此该字段在 `list` 结果里就是 16 位键。
 * @property file 最终产物文件（只读；调用方只许读，不许原地改写）。
 * @property sizeBytes 包体字节数。
 */
data class CodeArtifact(
    val packageId: String,
    val sha256Hex: String,
    val file: File,
    val sizeBytes: Long,
)

/**
 * 包体完整性失败：空包体、调用方声明的 sha256 与实际不符、或盘上同名产物内容与文件名不符
 * （疑似篡改/损坏）。
 *
 * 继承 [IOException] 是为了让装载侧「读盘失败」与「完整性失败」走同一类恢复路径；但语义上
 * 这是**拒载**而非重试能修好的问题，调用方应上屏并要求玩家重新导入包。
 */
class ModCodeIntegrityException(message: String) : IOException(message)

/**
 * dex 包的**只读内容寻址落地**（ADR-013 路 B 的 S1 片）。
 *
 * ## 为什么是这个形状
 *
 * targetSdk=36 ⇒ Android 14+ 的 `DexClassLoader` 硬约束「动态加载文件必须只读」必然命中
 * （ADR-013 D1），所以一个包体一旦落盘就**只增不改**：文件名 = sha256 前 16 位，升级 =
 * 新文件换引用，旧文件由 [prune] 按台账清。流程固定：
 *
 * 1. 写同目录临时文件（同文件系统，rename 才原子）；
 * 2. 校验 sha256（[install] 的 `expectedSha256` 闸）；
 * 3. `setReadOnly()` 置只读并**复核返回 true**（置不上就拒绝落盘，绝不带病 rename）；
 * 4. `Files.move(ATOMIC_MOVE)` 改名到 `<sha16>.mod`。
 *
 * 已存在同名文件（同 sha）**直接复用**，绝不原地改写；若盘上同名文件内容已与文件名不符，
 * 按 [ModCodeIntegrityException] 拒载（不静默覆盖、也不静默复用坏件）。
 *
 * ## 路径与越界
 *
 * rt2 根由调用方给出（生产侧 = [ModEnablement.rt2Dir]，与本类无关）；`packageId` 走
 * [ModEnablement.isValidPackageId] 的既有形状闸（不另造正则），拼完路径再对 `code/` 做
 * `canonicalPath` 前缀复核，符号链接一律拒绝跟随。
 *
 * ## 总闸
 *
 * 本类只做盘面落地，不看 D5 总闸；总闸关时调用方（S5/S8 接线）**不许调本类**，更不许构造
 * `DexClassLoader`——S1 不碰装载面。
 */
class ModCodeStore(private val rt2Dir: File) {

    /** `mods/rt2/code/`（不保证存在；只读查询不会现造目录）。 */
    fun codeDir(): File = File(rt2Dir, MOD_CODE_DIR_NAME)

    /**
     * 把一个包的 dex 包体落地为只读内容寻址文件并返回产物。
     *
     * @param expectedSha256 可选：调用方从包清单/下载链拿到的完整 64 位 sha256；给了就必须与
     *   实际包体逐字相符（大小写不敏感）。形状非法抛 `IllegalArgumentException`（含 `..` 之类
     *   的伪 sha 直接挡在拼路径之前）。
     * @throws IllegalArgumentException packageId / expectedSha256 形状非法，或解析后逃出 `code/`
     * @throws ModCodeIntegrityException 包体为空、sha256 不符、盘上同名产物内容被篡改
     * @throws IOException 建目录/写临时文件/置只读/原子改名失败（只读存储、磁盘满）
     */
    fun install(packageId: String, bytes: ByteArray, expectedSha256: String? = null): CodeArtifact {
        val pkg = requirePackageId(packageId)
        requireNonEmptyBody(pkg, bytes)
        val sha256 = sha256Hex(bytes)
        verifyExpectedSha(pkg, sha256, expectedSha256)
        val dir = packageDirFor(pkg, create = true)
        val target = File(dir, sha256.substring(0, SHA_PREFIX_LENGTH) + MOD_CODE_ARTIFACT_EXTENSION)
        return if (target.exists()) {
            reuseExisting(pkg, target, sha256)
        } else {
            writeTempThenMove(bytes, target)
            CodeArtifact(pkg, sha256, target, target.length())
        }
    }

    /**
     * 该包已落地的全部产物（按 sha 地址键字典序）。
     *
     * 只认文件名合法的 `<16 位小写 hex>.mod`：坏名、目录、符号链接一律跳过（不当崩）；
     * 包目录缺失或本身是文件 = 空表。不重读包体（不校验内容，内容审计归装载侧）。
     */
    fun list(packageId: String): List<CodeArtifact> {
        val pkg = requirePackageId(packageId)
        val entries = packageDirFor(pkg, create = false).listFiles().orEmpty()
        return entries.asSequence()
            .mapNotNull { file ->
                val key = addressKeyOf(file) ?: return@mapNotNull null
                CodeArtifact(pkg, key, file, file.length())
            }
            .sortedBy { it.sha256Hex }
            .toList()
    }

    /**
     * 删掉**不在** [referencedShas] 里的旧版本产物，并清掉被清空的包目录。
     *
     * 任务书给的形参名是 `unreferencedShas`，但其正文语义是「删不属于给定集合的旧版本文件」，
     * 故这里按**保留集**实现：集合里的地址键保留，其余删。集合项可给完整 64 位 sha256 或
     * 16 位前缀（统一归一成前缀比较），坏项忽略。
     *
     * 删不掉的文件只记日志不抛（包可能正被载入/被占用），返回**实际删除的文件数**；
     * 目录清理不计数。幂等：再跑一次返回 0。
     */
    fun prune(referencedShas: Set<String>): Int {
        val keep = normalizeAddressKeys(referencedShas)
        val root = codeDir()
        if (!root.isDirectory) return 0
        var removed = 0
        for (dir in root.listFiles().orEmpty()) {
            if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath())) continue
            removed += pruneVersionsIn(dir, keep)
            if (dir.listFiles().orEmpty().isEmpty() && !dir.delete()) {
                Log.w(TAG, "空包目录清理失败 dir=${dir.name}")
            }
        }
        return removed
    }

    /**
     * 卸载一个包的全部 dex 产物（连同包目录），幂等。
     *
     * 返回 true = 盘上已无该包产物（含本来就没有）。只删本包 `code/<pkg>/` 树，不碰别的包；
     * 个别文件删不掉（占用/权限）返回 false，由调用方上屏，不吞成成功。
     *
     * @throws IllegalArgumentException packageId 形状非法（拼路径前就拒）
     */
    fun clear(packageId: String): Boolean {
        val pkg = requirePackageId(packageId)
        val dir = packageDirFor(pkg, create = false)
        if (!dir.exists()) return true
        val leftBehind = dir.listFiles().orEmpty().any { !deleteArtifactTree(it) }
        val dirGone = dir.delete()
        if (leftBehind || !dirGone) {
            Log.w(TAG, "clear 未删净 pkg=$pkg leftBehind=$leftBehind dirGone=$dirGone")
        }
        return !dir.exists()
    }

    // ------------------------------------------------------------------
    // 路径与形状校验
    // ------------------------------------------------------------------

    /** packageId 形状闸：唯一真源 = [ModEnablement.isValidPackageId]（与 `.off` 标记同口径）。 */
    private fun requirePackageId(packageId: String): String {
        require(ModEnablement.isValidPackageId(packageId)) {
            "非法 packageId：$packageId（只允许字母数字与 . _ -，且不得以符号开头）"
        }
        return packageId
    }

    /**
     * `code/<packageId>` 的解析：拼完再 `canonicalPath` 复核前缀，符号链接一律拒绝跟随。
     *
     * @param create true = 顺带建目录（install 用）；false = 只解析（list/clear 用，不现造目录）。
     */
    private fun packageDirFor(packageId: String, create: Boolean): File {
        val root = codeDir()
        if (create) root.mkdirs()
        val rootPath = root.canonicalFile.toPath()
        val raw = File(rootPath.toFile(), packageId)
        require(!Files.isSymbolicLink(raw.toPath())) {
            "拒绝跟随符号链接：$packageId"
        }
        val dir = raw.canonicalFile
        require(dir.toPath().startsWith(rootPath)) {
            "非法 packageId：$packageId 解析后逃出 code/（疑似路径穿越）"
        }
        if (create) {
            dir.mkdirs()
            require(dir.isDirectory) { "包目录建不出来：${dir.absolutePath}" }
        }
        return dir
    }

    // ------------------------------------------------------------------
    // 落地
    // ------------------------------------------------------------------

    /**
     * 复用已存在的同 sha 产物：先重算盘上文件的 sha 复核内容与文件名一致（篡改检测），
     * 一致才复用；不一致 = 拒载，绝不静默覆盖或静默复用坏件。
     */
    private fun reuseExisting(packageId: String, target: File, sha256: String): CodeArtifact {
        val onDisk = if (target.isFile) sha256OfFile(target) else null
        if (onDisk == null || !onDisk.equals(sha256, ignoreCase = true)) {
            throw ModCodeIntegrityException(
                "盘上同名产物内容与文件名不符（疑似篡改或损坏），拒绝复用 pkg=$packageId " +
                    "file=${target.name}",
            )
        }
        return CodeArtifact(packageId, sha256, target, target.length())
    }

    /**
     * 写临时文件 → 置只读 → 原子 rename。最终名已存在时不带 REPLACE（rename 会失败而不是
     * 覆盖），与「只增不改」同一口径；调用方已在 [install] 里先做过存在性复用。
     */
    private fun writeTempThenMove(bytes: ByteArray, target: File) {
        val dir = target.parentFile ?: throw IOException("产物目录缺失：${target.absolutePath}")
        val tmp = Files.createTempFile(dir.toPath(), TMP_PREFIX, TMP_SUFFIX)
        val outcome = runCatching {
            Files.write(tmp, bytes)
            // 只读硬约束：置不上就当场失败，绝不把可写文件 rename 成「产物」。
            if (!tmp.toFile().setReadOnly()) {
                throw IOException("临时包体无法置只读，拒绝落盘：${tmp.fileName}")
            }
            moveAtomically(tmp, target.toPath())
        }
        if (outcome.isFailure) {
            runCatching { Files.deleteIfExists(tmp) }
            outcome.getOrThrow()
        }
    }
}

// ----------------------------------------------------------------------
// 摘要 / 文件系统辅助（顶层私有，与类职责解耦）
// ----------------------------------------------------------------------

/** 包体的完整 sha256 hex（小写）。 */
private fun sha256Hex(bytes: ByteArray): String =
    toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

/** 盘上文件的完整 sha256 hex（小写）；读失败按 IO 异常抛出。 */
private fun sha256OfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(SHA_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return toHex(digest.digest())
}

/** 摘要字节 → 小写 hex（不用 `%02x`，避开默认 Locale 与格式化开销）。 */
private fun toHex(digest: ByteArray): String {
    val out = StringBuilder(digest.size * HEX_DIGITS_PER_BYTE)
    for (byte in digest) {
        val value = byte.toInt()
        out.append(HEX_DIGITS[(value shr NIBBLE_BITS) and NIBBLE_MASK])
        out.append(HEX_DIGITS[value and NIBBLE_MASK])
    }
    return out.toString()
}

/** 文件名 → 地址键；只认 `<16 位小写 hex>.mod` 的普通文件，坏名/目录/符号链接 = null。 */
private fun addressKeyOf(file: File): String? {
    val name = file.name
    val key = name.removeSuffix(MOD_CODE_ARTIFACT_EXTENSION)
    val valid = file.isFile &&
        !Files.isSymbolicLink(file.toPath()) &&
        name.endsWith(MOD_CODE_ARTIFACT_EXTENSION) &&
        SHA_PREFIX_PATTERN.matches(key)
    return if (valid) key else null
}

/** 保留集归一：64 位 sha256 → 前 16 位键；16 位键原样；坏项忽略。 */
private fun normalizeAddressKeys(shas: Set<String>): Set<String> =
    shas.mapNotNullTo(mutableSetOf()) { raw ->
        val text = raw.trim().lowercase()
        when {
            SHA_PREFIX_PATTERN.matches(text) -> text
            SHA256_HEX_PATTERN.matches(text) -> text.substring(0, SHA_PREFIX_LENGTH)
            else -> null
        }
    }

/** 空包体是完整性失败（不是形状错误：packageId 已在上游校验过）。 */
private fun requireNonEmptyBody(packageId: String, bytes: ByteArray) {
    if (bytes.isEmpty()) throw ModCodeIntegrityException("包体为空，拒绝落地 pkg=$packageId")
}

/** 期望 sha256 闸：形状非法 = IAE（挡在拼路径前）；内容不符 = 完整性失败。 */
private fun verifyExpectedSha(packageId: String, actual: String, expected: String?) {
    if (expected == null) return
    val wanted = expected.trim()
    require(SHA256_HEX_PATTERN.matches(wanted)) {
        "非法 sha256（须 64 位 hex）：$expected"
    }
    if (!actual.equals(wanted, ignoreCase = true)) {
        throw ModCodeIntegrityException("包体 sha256 不符 pkg=$packageId 期望=$wanted 实得=$actual")
    }
}

/**
 * 删一个产物树；删不掉只返回 false（调用方记日志），不抛。
 *
 * 删前先 `setWritable(true)`：产物按设计是只读的，Windows 的只读属性会直接挡住 `delete()`
 * （Linux 只读文件可删，但统一摘权限后删最省事）。
 */
private fun deleteArtifactTree(file: File): Boolean {
    val childrenGone = if (file.isDirectory) {
        file.listFiles().orEmpty().all { deleteArtifactTree(it) }
    } else {
        true
    }
    if (!childrenGone) return false
    file.setWritable(true)
    return file.delete()
}

/** 优先原子改名；文件系统不支持 ATOMIC_MOVE（部分虚机盘）时退回普通改名。 */
private fun moveAtomically(tmp: Path, target: Path) {
    val moved = runCatching { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE) }
        .getOrElse { failure ->
            if (failure is AtomicMoveNotSupportedException) null else throw failure
        }
    if (moved == null) Files.move(tmp, target)
}

// ----------------------------------------------------------------------
// 卸载删净 + 孤儿 GC（ADR-013 D4 的 S6 片；放本文件是为了复用上面的
// 只读产物删除 / 地址键解析 / `code/` 目录名，别处再抄一套会漂移）
// ----------------------------------------------------------------------

/**
 * dex 代码产物面的回收：显式卸载删净（[uninstall]）与孤儿 GC（[gcOrphans]）。
 *
 * ## 为什么 `code/` 要单独回收
 *
 * 产物按内容寻址**只增不改**（[ModCodeStore.install]），旧版本由 [ModCodeStore.prune] 按引用
 * 集合清；但**卸载**以前只删 `mods/rt2` 包成员，`code/<packageId>/` 会永久残留（磁盘只增
 * 不减，且同包名重装会撞上旧产物）。ADR-013 D4 因此把 `code/` 纳入卸载删净名单，S6 接线。
 *
 * ## 判据（与媒体面 [ModMediaReclaim] 同一分支纪律：先保留后删除）
 *
 * 1. `code/` 下条目不是合法 packageId（手放件）、不是普通目录（文件/符号链接）⇒ 保留并取证；
 * 2. 该包在 rt2 仍有包级成员（[hasPackageFootprint]：五件套文件 / `.assets` / `.chara` /
 *    `.settings`）⇒ 保留——完整包自不必说，包级没删成的半成品也归下一轮；
 * 3. 否则判孤儿：逐件删（含只读产物），但**地址键仍被活包引用**的产物保留——内容寻址下同一份
 *    包体可能落在多个包目录，删孤儿目录不能把活包要用的文件带走；
 * 4. `code/` 根永不删（止于根之前）；删不掉的原地保留待下轮，全程不抛、幂等。
 *
 * ## 台账（`released-packages.txt`）只取证、不阻止删除
 *
 * 台账语义是「这个包曾被玩家显式卸载过」，不是所有权凭证：拿它当保留依据会让「卸载失败残留 +
 * 台账条目」永久泄漏磁盘。故台账命中只写进取证行；重导后包级成员回来，走分支 2 自然保留（旧条目
 * 依赖 `ModStorageReclaim.reclaimedPackageIds` 的既有边界惰性失效）。
 */
internal object ModCodeReclaim {

    /** 取证面标签：与包级 / 媒体面同一个 `MOD_PKG_GC` 面，subject 用 `code/` 前缀区分。 */
    private const val DIAG_TAG: String = "MOD_PKG_GC"

    /**
     * 显式卸载：删本包 `code/<packageId>/` 整棵树（含只读产物），幂等。
     *
     * 失败只 WARN、不改卸载结局（任务书口径）：代码产物删不掉不阻塞玩家卸载，残留交给下一轮
     * [gcOrphans] 兜底（那时包级成员已删光 ⇒ 判孤儿）。本函数不抛：`clear` 的形状闸／IO 失败
     * 一律折成 false + 一条告警（生产调用方 [ModManageUninstall] 已先校过 packageId 形状）。
     */
    fun uninstall(rt2Dir: File, packageId: String) {
        val cleared = runCatching { ModCodeStore(rt2Dir).clear(packageId) }.getOrDefault(false)
        if (!cleared) {
            runCatching { Log.w(TAG, "卸载：dex 代码产物未删净（占用/权限），保留待下轮 GC pkg=$packageId") }
        }
    }

    /**
     * `code/` 孤儿回收：由 [ModPackageInventory.gcOrphans] 在包级判据之后调用（生产 =
     * [ModChainAssembly.assemble] 开态入口），`code/` 不存在 = no-op。
     *
     * 全程不抛：任何一步读盘/删盘失败都只留取证行，不影响装配结局。
     */
    fun gcOrphans(rt2Dir: File) {
        val root = File(rt2Dir, MOD_CODE_DIR_NAME)
        if (!root.isDirectory) return
        val entries = root.listFiles().orEmpty().toList()
        val referenced = referencedAddressKeys(rt2Dir, entries)
        val ledger = ModStorageReclaim.releasedPackageIds(rt2Dir)
        for (entry in entries.sortedBy { it.name }) {
            reclaimOne(entry, rt2Dir, referenced, ledger)
        }
    }

    /**
     * 「同 sha 仍被活包引用」的地址键集合：包级成员还在（[hasPackageFootprint]）的包目录里
     * 全部产物的地址键并集。
     *
     * 内容寻址不按包隔离：同一个包体（同 sha256）落到两个包目录是合法的（两个包自带同一份
     * jar/资源），所以删孤儿目录时必须按这个集合留人。
     */
    private fun referencedAddressKeys(rt2Dir: File, entries: List<File>): Set<String> {
        val store = ModCodeStore(rt2Dir)
        return entries.asSequence()
            .filter { isPlainDirectory(it) && ModEnablement.isValidPackageId(it.name) }
            .filter { hasPackageFootprint(rt2Dir, it.name) }
            .flatMap { dir -> store.list(dir.name).asSequence().map { it.sha256Hex } }
            .toSet()
    }

    /** 单个 `code/` 子条目的裁定：先保留（非包名 / 非普通目录 / 包级成员仍在），再判孤儿。 */
    private fun reclaimOne(dir: File, rt2Dir: File, referenced: Set<String>, ledger: Set<String>) {
        when {
            !ModEnablement.isValidPackageId(dir.name) -> trace(dir.name, "非包名目录，保留（不拿非包目录当删除依据）")
            !isPlainDirectory(dir) -> trace(dir.name, "非普通包目录（文件/符号链接），保留")
            hasPackageFootprint(rt2Dir, dir.name) -> trace(dir.name, "包级成员仍在（完整包/未删成的半成品），代码产物保留")
            else -> reclaimArtifacts(dir, referenced, ledger)
        }
    }

    /** 孤儿目录清理：被引用地址键的产物留下，其余（含坏名/临时件）删；删完空目录连目录一起删。 */
    private fun reclaimArtifacts(dir: File, referenced: Set<String>, ledger: Set<String>) {
        var kept = 0
        var failed = 0
        for (child in dir.listFiles().orEmpty()) {
            val key = addressKeyOf(child)
            when {
                key != null && key in referenced -> kept++
                !deleteArtifactTree(child) -> failed++
            }
        }
        val dirGone = dir.listFiles().orEmpty().isEmpty() && dir.delete()
        trace(dir.name, describeReclaim(kept, failed, dirGone, dir.name in ledger))
    }

    private fun describeReclaim(kept: Int, failed: Int, dirGone: Boolean, released: Boolean): String = when {
        failed > 0 -> "孤儿代码产物 $failed 件删不掉（占用/权限），原地保留待下轮 GC"
        kept > 0 -> "孤儿代码目录仅剩被引用地址键产物 $kept 件，保留（同 sha 仍被活包引用）"
        dirGone && released -> "孤儿代码目录已回收（台账记载为显式卸载：只取证、不阻止回收）"
        dirGone -> "孤儿代码目录已回收（packageId 在 rt2 已无包成员）"
        else -> "孤儿代码目录已清空但目录删除失败，原地保留待下轮 GC"
    }

    /**
     * 包级成员面是否仍在：五件套任一文件、`<pkg>.assets/`、`<pkg>.chara/`、`<pkg>.settings`。
     *
     * 口径与 [ModManageUninstall] 的删净名单、[ModMediaReclaim] 的 footprint 同源（宁保守：
     * 只要还有任何一件包级产物，就当包还活着，代码产物不回收）。
     */
    private fun hasPackageFootprint(rt2Dir: File, packageId: String): Boolean {
        val members = ModPackageInventory.memberNames(packageId)
        return members.any { File(rt2Dir, it).isFile } ||
            File(rt2Dir, packageId + ModPackageInventory.ASSET_DIR_SUFFIX).isDirectory ||
            File(rt2Dir, packageId + ModPackageInventory.CHARA_DIR_SUFFIX).isDirectory ||
            File(rt2Dir, ModSettingsStore.memberName(packageId)).isFile
    }

    private fun isPlainDirectory(file: File): Boolean =
        file.isDirectory && !Files.isSymbolicLink(file.toPath())

    private fun trace(name: String, detail: String) {
        runCatching { SaveDiagnostics.note(DIAG_TAG, MOD_CODE_DIR_NAME + "/" + name, detail) }
    }
}

/** prune 的单包版本清理：只删地址键不在保留集里的产物，返回删除数。 */
private fun pruneVersionsIn(dir: File, keep: Set<String>): Int {
    val victims = dir.listFiles().orEmpty().filter { file ->
        val key = addressKeyOf(file)
        key != null && key !in keep
    }
    var removed = 0
    for (file in victims) {
        if (deleteArtifactTree(file)) {
            removed++
        } else {
            Log.w(TAG, "旧版本产物删除失败（保留待下次）file=${file.name}")
        }
    }
    return removed
}
