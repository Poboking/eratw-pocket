package com.eratw.port.platform.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 把**块级差量包**（P2-13，U8）当作一棵 [GameAssetTree] 喂给 [GameAssetInstaller]。
 *
 * ## 它治的病
 *
 * 文件级差量（`update-delta` 那条）已经做到「只发变化的文件」，但它的粒度就是**文件**：
 * 一张 4.67 MiB 的立绘改了中部一个字节，玩家还是要重下整张图（实测 4,661,882 B）。
 * 这一层把粒度降到**块**：同一份改动发 9,933 B 的补丁（4 KiB 块长，三档实测读数见交付报告），
 * 补丁里只写「第几块照旧 + 这几字节是新的」。
 *
 * ## 为什么设备侧只需要「按块号取字节」
 *
 * 全套方案里唯一麻烦的部分（找出旧文件哪一块还出现在新文件里）**整个留在发布机的 Python**：
 * 补丁已经逐条写明块号，设备侧不做任何匹配、不算任何滚动哈希、不引任何依赖 ——
 * AGENTS.md 铁律 4（core/platform 纯 JVM、零新增依赖）在这里的代价就是
 * 「生成侧多写一百行，应用侧只剩一台状态机」。
 *
 * 块号按 [PatchEntry.block] 解释：`第 i 块 = 旧文件 [i×block, (i+1)×block)`，末尾不足一块就是
 * 它自己那么长。两侧都只靠这一个算式，不存在第二套口径可以走歪；块长写在补丁里而不是
 * 编译进 APK，调块长时不用重出包。
 *
 * ## 三道闸，任何一道不过都是「抛」而不是「凑合」
 *
 * 1. **形状**（索引阶段）：魔数、`fmt`、`block` 区间、两个摘要的十六进制形状、路径安全、
 *    落在资产范围内。任一条不过 → 计入 [rejectedEntryCount]，由调用方那条
 *    「计数大于 0 就整包拒装」的既有闸处置（与 [ZipAssetTree] 的 zip-slip 共用同一道闸、
 *    同一个理由：坏包不该「跳过这条继续装」）。
 * 2. **基线**（开流时，一个字节都还没落）：盘上那份旧文件的 SHA-256 必须等于补丁声明的
 *    `base-sha256`。补丁引用的是**那一版那份字节**的块号；盘上换了内容还照块号拼，
 *    得到的是一份看着完整的错语料 —— 而装载侧不会因为「立绘差了几个像素」报错。
 * 3. **还原**（读到流末尾时）：拼出来的字节数与 SHA-256 必须等于 `result-size` /
 *    `result-sha256`，且 op 条数等于 `op-count`。这一条把「块级差量拼错了」的可能性压成零：
 *    拼对了才等于全量包里那份字节，也就跟整条重发**完全等价**。
 *
 * 三道闸抛的都是 [AssetInstallException]（继承 IOException），所以走的就是 [GameAssetInstaller]
 * 现成的失败面：前两闸在落盘之前就把本轮判死（盘上一个字节都没动），第三闸在写 `.tmp` 的途中
 * 抛（`.tmp` 被清掉、目标仍是上一版）。调用方据此把盘标记成不可信，下一拍
 * `UpdatePolicy.deltaUsable` / `blockDeltaUsable` 一起关死 ⇒ **整单回落全量**，
 * 不会有半份语料。口径与现行文件级差量一模一样，不自创第二套。
 *
 * ## 删除表照旧
 *
 * 包里的 `update-delta.txt` 由内层 [ZipAssetTree] 解析，[isDeltaPackage] / [deltaDeletions]
 * 直接转交 —— 新通道**不新增第二套删除语义**，发布侧也只写那一份表。
 *
 * ## 内存与临时文件
 *
 * 一条补丁都不整份读进内存：op 流按需从 zip 条目读、旧文件按块 `seek` 读，输出直接流给
 * [GameAssetInstaller] 的 1 MiB 落盘缓冲（它自己边写边算摘要）。本类不建任何文件。
 *
 * @param source 已下载并**通过清单摘要核对**的块级差量包（核对在更新通道做，本类不重复算）
 * @param gameRoot 盘上语料的根：块号引用的旧文件就从这里按相对路径读
 */
class BlockDeltaAssetTree(
    private val source: ZipAssetTree,
    private val gameRoot: File,
) : GameAssetTree, DeltaDeletionSource {

    /**
     * 一条补丁的头部读数。
     *
     * @param patchEntry 补丁在 zip 里的条目名（[PATCH_DIR_PREFIX] 前缀 + 目标相对路径）
     * @param relPath 目标的游戏根相对路径（剥掉前缀、已过 [ZipAssetTree.safeEntryName]）
     * @param block 块长（字节），取自补丁自己
     * @param baseShaHex 补丁假设的旧文件摘要
     * @param resultShaHex 拼完必须等于的摘要（= 全量包里同一条目的摘要）
     * @param resultSize 拼完必须等于的字节数
     * @param opCount 头部声明的 op 条数（第三闸核对「op 流后面还挂着东西」）
     * @param headerBytes 头部占的字节数（开流时先跳过这么多）
     */
    internal class PatchEntry(
        val patchEntry: String,
        val relPath: String,
        val block: Long,
        val baseShaHex: String,
        val resultShaHex: String,
        val resultSize: Long,
        val opCount: Long,
        val headerBytes: Long,
    )

    /** 目标相对路径 → 补丁（同一目标出现两次：后一条丢弃并计入拒绝数）。 */
    private val patches = LinkedHashMap<String, PatchEntry>()

    /** 虚拟目录索引：前缀 → 该前缀下的补丁条目名（与 [ZipAssetTree] 一样把父链一路撑到根）。 */
    private val virtualChildren = LinkedHashMap<String, LinkedHashSet<String>>()

    /** 索引阶段被丢出的补丁条数（不含内层 zip-slip 的）。 */
    private var ownRejected = 0

    private val rootPath: String = runCatching { gameRoot.canonicalFile.absolutePath }
        .getOrDefault(gameRoot.absolutePath)

    init {
        for (entry in enumeratePatchEntries()) {
            val rel = entry.substring(PATCH_DIR_PREFIX.length)
            val safe = ZipAssetTree.safeEntryName(rel)
            if (safe == null || safe == ZipAssetTree.DELTA_LIST_NAME ||
                !GameAssetInstaller.deletionWithinAssetUniverse(safe)
            ) {
                ownRejected++
                continue
            }
            if (patches.containsKey(safe)) {
                ownRejected++
                continue
            }
            val parsed = readPatchHeader(entry, safe)
            if (parsed == null) {
                ownRejected++
                continue
            }
            patches[safe] = parsed
            var prefix = safe
            while (true) {
                val slash = prefix.lastIndexOf('/')
                val child = if (slash < 0) prefix else prefix.substring(slash + 1)
                val parent = if (slash < 0) "" else prefix.substring(0, slash)
                virtualChildren.getOrPut(parent) { LinkedHashSet() } += child
                if (slash < 0) break
                prefix = parent
            }
        }
    }

    /** 内层包的 zip-slip 计数 + 本类索引阶段的计数；大于 0 调用方就整包拒装。 */
    val rejectedEntryCount: Int get() = source.rejectedEntryCount + ownRejected

    override val isDeltaPackage: Boolean get() = source.isDeltaPackage

    override fun deltaDeletions(): List<String> = source.deltaDeletions()

    /** 这条路径是不是补丁目标（单测与调用方取证用）。 */
    fun isPatched(path: String): Boolean = patches.containsKey(path)

    /** 本类索引阶段丢出的条数（不含内层 zip-slip 的）。 */
    val ownRejectedCount: Int get() = ownRejected

    /** 补丁目标数（正常等于「这一版改过且发得起补丁的文件数」）。 */
    val patchCount: Int get() = patches.size

    override fun openFile(path: String): InputStream? {
        val entry = patches[path] ?: return source.openFile(path)
        return PatchStream(entry)
    }

    /**
     * 补丁目标报**还原后**的字节数（不是补丁自己的大小）。
     *
     * 这一条决定 [GameAssetInstaller] 的 `needsCopy` 对账基准与落盘后的长度核对：报小了会在
     * forceReplace 关掉时把已装好的新版判成「长度不对，重拷」；报错数更糟 —— 解包侧会拿
     * 「声明 N B，实读 M B」当场拒掉一份本来完全正确的语料。
     */
    override fun fileSize(path: String): Long? {
        val entry = patches[path] ?: return source.fileSize(path)
        return entry.resultSize
    }

    /** 真实条目与虚拟条目合并；根一层不列出控制目录 [PATCH_ROOT_NAME]。 */
    override fun listDir(path: String): List<String> {
        val real = if (path.isEmpty()) source.listDir(path).filter { it != PATCH_ROOT_NAME } else source.listDir(path)
        val virtual = virtualChildren[path]
        if (virtual.isNullOrEmpty()) return real
        val merged = LinkedHashSet<String>()
        merged += real
        merged += virtual
        return merged.toList()
    }

    /**
     * 枚举 [PATCH_ROOT_NAME] 目录下的补丁条目（深度不限、广度有上限）。
     *
     * 走的是 [GameAssetTree] 那三个方法而不是直接摸 zip：于是这一层的索引与解包器看到的
     * 是同一份条目视图（zip-slip 的过滤只有一处，不重复实现第二份）。
     */
    private fun enumeratePatchEntries(): List<String> {
        val found = ArrayList<String>()
        val queue = ArrayDeque<String>()
        for (name in source.listDir(PATCH_ROOT_NAME)) queue.addLast("$PATCH_DIR_PREFIX$name")
        var guard = 0
        while (queue.isNotEmpty() && guard < MAX_PATCH_SCAN) {
            guard++
            val path = queue.removeFirst()
            val children = source.listDir(path)
            if (children.isEmpty()) found += path else for (child in children) queue.addLast("$path/$child")
        }
        if (queue.isNotEmpty()) ownRejected++ // 扫不完 = 不像合法发布，整包拒装而不是装一半
        return found
    }

    /**
     * 读并校验补丁头部；任何一条不合格返回 null（调用方计入拒绝数）。
     *
     * 判式与发布侧 `devtools/emit_update.py` 的 `split_block_patch` **逐条同值**：首行魔数、
     * `fmt`、`block` 落在 1 … [MAX_BLOCK_BYTES]、两个摘要 64 位小写十六进制、`result-size`
     * 与 `op-count` 非负、头部在 [HEADER_MAX_BYTES] 内收在空行上。两侧同值才不会出现
     * 「发布侧写得出的包，设备侧读不成」—— 那种差量静默回落全量，谁都不报错。
     */
    private fun readPatchHeader(entryName: String, relPath: String): PatchEntry? {
        val raw = try {
            val input = source.openFile(entryName) ?: return null
            input.use { readCapped(it) }
        } catch (e: IOException) {
            null
        } ?: return null
        if (raw.size < MAGIC_LINE.size + 2) return null
        var sep = -1
        var i = 0
        while (i + 1 < raw.size) {
            if (raw[i] == NEWLINE && raw[i + 1] == NEWLINE) {
                sep = i
                break
            }
            i++
        }
        if (sep < 0) return null
        val headBytes = raw.copyOfRange(0, sep + 2)
        if (!hasMagicPrefix(headBytes)) return null
        val header = String(
            headBytes, MAGIC_LINE.size, headBytes.size - MAGIC_LINE.size, StandardCharsets.UTF_8,
        )
        val fields = LinkedHashMap<String, String>()
        for (line in header.split('\n')) {
            if (line.isEmpty()) continue
            val space = line.indexOf(' ')
            if (space <= 0) return null
            fields[line.substring(0, space)] = line.substring(space + 1)
        }
        if (fields[KEY_FORMAT] != FORMAT) return null
        val block = fields[KEY_BLOCK]?.toLongOrNull() ?: return null
        if (block < 1L || block > MAX_BLOCK_BYTES) return null
        val resultSize = fields[KEY_RESULT_SIZE]?.toLongOrNull() ?: return null
        if (resultSize < 0L) return null
        val opCount = fields[KEY_OP_COUNT]?.toLongOrNull() ?: return null
        if (opCount < 0L) return null
        val baseSha = fields[KEY_BASE_SHA] ?: return null
        val resultSha = fields[KEY_RESULT_SHA] ?: return null
        if (!isLowerHex64(baseSha) || !isLowerHex64(resultSha)) return null
        return PatchEntry(entryName, relPath, block, baseSha, resultSha, resultSize, opCount,
            (sep + 2).toLong())
    }

    /** 读头部那一段；超过 [HEADER_MAX_BYTES] 还没读完判 null（不是本发布侧写的补丁）。 */
    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(512)
        val chunk = ByteArray(1024)
        while (out.size() <= HEADER_MAX_BYTES) {
            val n = try {
                input.read(chunk)
            } catch (e: IOException) {
                return null
            }
            if (n < 0) return out.toByteArray()
            out.write(chunk, 0, n)
        }
        return null
    }

    /**
     * 一条补丁的还原流：先验基线，再按 op 流把「旧文件的第 i 块」与补丁里的字面量拼出来，
     * 读到流末尾时核对字节数、op 条数与 SHA-256。
     *
     * 调用方（[GameAssetInstaller.copyStep]）总是读到 -1 为止，所以第三闸一定会执行到 ——
     * 这也是本类可以「不落盘、不建临时文件」的前提：还原结果只以字节流的形式存在过。
     */
    private inner class PatchStream(private val entry: PatchEntry) : InputStream() {
        private val target = File(gameRoot, entry.relPath)
        private val old = openOldFile(target)
        private val patch = openPatchStream(entry)
        private val digest = MessageDigest.getInstance("SHA-256")
        private val oldLength = old.length()
        private val opTag = ByteArray(1)
        private var total = 0L
        private var opsRead = 0L
        private var literalLeft = 0L
        private var copyLeft = 0L
        private var copyPos = 0L
        private var closed = false
        private var finished = false

        init {
            skipHeader()
            verifyBase()
        }

        /** 基线闸：旧文件的 SHA-256 必须与补丁声明的一致，在写任何一个字节之前把包判死。 */
        private fun verifyBase() {
            val sha = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BASE_READ_BUFFER)
            old.seek(0L)
            var readTotal = 0L
            while (readTotal < oldLength) {
                val want = minOf(buf.size.toLong(), oldLength - readTotal).toInt()
                old.readFully(buf, 0, want)
                sha.update(buf, 0, want)
                readTotal += want.toLong()
            }
            if (shaHex(sha.digest()) != entry.baseShaHex) {
                throw AssetInstallException(
                    "块级差量的基线与盘上内容不符（${entry.relPath}）：盘上那份不是补丁假设的那一版，" +
                        "已拒绝应用",
                )
            }
        }

        private fun skipHeader() {
            var left = entry.headerBytes
            val buf = ByteArray(64)
            while (left > 0L) {
                val n = try {
                    patch.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                } catch (e: IOException) {
                    throw AssetInstallException("块级差量补丁头读不出：${entry.relPath}", e)
                }
                if (n < 0) {
                    throw AssetInstallException("块级差量补丁比头部声明的还短：${entry.relPath}")
                }
                left -= n.toLong()
            }
        }

        /** 读下一条 op 并把状态摆好；返回 false = op 流正常结束（末尾核对由 [finish] 负责）。 */
        private fun nextOp(): Boolean {
            val tag = patch.read(opTag)
            if (tag < 0) {
                if (opsRead != entry.opCount) {
                    throw AssetInstallException(
                        "块级差量的 op 条数与声明不符：读了 $opsRead 条，声明 ${entry.opCount} 条" +
                            "（${entry.relPath}）",
                    )
                }
                return false
            }
            if (opsRead >= entry.opCount) {
                throw AssetInstallException("块级差量的 op 比声明的还多（${entry.relPath}）：补丁被追加过")
            }
            // 标签先落到局部：readInt32 复用同一块单字节缓冲，读完长度之后 opTag 里剩的是
            // 索引的最后一字节（合法补丁里恒为 0）。当场读 opTag 会把每条 COPY 都看成未知标签。
            val tagByte = opTag[0]
            val num = readInt32()
            opsRead++
            when (tagByte.toInt().toChar()) {
                OP_COPY -> {
                    val start = num * entry.block
                    if (start < 0L || start >= oldLength) {
                        throw AssetInstallException(
                            "块级差量引用了不存在的块 $num（${entry.relPath}）：基线比补丁假设的短",
                        )
                    }
                    copyPos = start
                    copyLeft = minOf(entry.block, oldLength - start)
                }

                OP_RUN -> literalLeft = num

                else -> throw AssetInstallException(
                    "块级差量出现未知 op 标签 0x${tagByte.toInt().and(0xFF).toString(16)}（${entry.relPath}）",
                )
            }
            return true
        }

        /** op 的 4 字节大端整数（与发布侧 `to_bytes(4, "big")` 同一个字节序）。 */
        private fun readInt32(): Long {
            var value = 0L
            for (k in 0 until 4) {
                if (patch.read(opTag) < 0) {
                    throw AssetInstallException("块级差量的 op 头被截断（${entry.relPath}）")
                }
                value = (value shl 8) or (opTag[0].toInt().toLong() and 0xFFL)
            }
            return value
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(buf: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (finished) return -1
            var produced = 0
            while (produced < len) {
                if (literalLeft > 0L) {
                    val take = minOf(literalLeft, (len - produced).toLong()).toInt()
                    var filled = 0
                    while (filled < take) {
                        val n = patch.read(buf, off + produced + filled, take - filled)
                        if (n < 0) {
                            throw AssetInstallException("块级差量的字面量段被截断（${entry.relPath}）")
                        }
                        filled += n
                    }
                    literalLeft -= take.toLong()
                    consume(buf, off + produced, take)
                    produced += take
                } else if (copyLeft > 0L) {
                    val take = minOf(copyLeft, (len - produced).toLong()).toInt()
                    try {
                        old.seek(copyPos)
                        old.readFully(buf, off + produced, take)
                    } catch (e: IOException) {
                        throw AssetInstallException(
                            "块级差量读旧文件失败：${target.absolutePath}（${e.message}）", e,
                        )
                    }
                    copyLeft -= take.toLong()
                    copyPos += take.toLong()
                    consume(buf, off + produced, take)
                    produced += take
                } else if (!nextOp()) {
                    finish()
                    return if (produced > 0) produced else -1
                }
            }
            return produced
        }

        private fun consume(buf: ByteArray, off: Int, n: Int) {
            digest.update(buf, off, n)
            total += n.toLong()
            if (total > entry.resultSize) {
                throw AssetInstallException(
                    "块级差量拼出的字节数超过声明的 ${entry.resultSize}（${entry.relPath}）",
                )
            }
        }

        /** 还原闸：字节数与摘要必须与声明一致。这条一过，补丁就与整条重发**完全等价**。 */
        private fun finish() {
            finished = true
            if (total != entry.resultSize) {
                throw AssetInstallException(
                    "块级差量拼出的长度不符：实际 $total，声明 ${entry.resultSize}（${entry.relPath}）",
                )
            }
            if (shaHex(digest.digest()) != entry.resultShaHex) {
                throw AssetInstallException(
                    "块级差量拼出的内容与摘要不符（${entry.relPath}）：已拒绝应用，回落全量",
                )
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { patch.close() }
            runCatching { old.close() }
        }
    }

    private fun openOldFile(target: File): RandomAccessFile {
        if (!target.isFile) {
            throw AssetInstallException(
                "块级差量要引用的旧文件不在盘上：${target.absolutePath}（基线缺文件，回落全量）",
            )
        }
        val canonical = runCatching { target.canonicalFile }
            .getOrElse { throw AssetInstallException("块级差量的目标路径读不出：${target.absolutePath}", it) }
        if (!insideRoot(canonical.absolutePath)) {
            throw AssetInstallException(
                "块级差量指向游戏根之外：${target.absolutePath}（包不可信，已拒绝应用）",
            )
        }
        return try {
            RandomAccessFile(target, "r")
        } catch (e: IOException) {
            throw AssetInstallException("块级差量打不开旧文件：${target.absolutePath}", e)
        }
    }

    private fun openPatchStream(entry: PatchEntry): InputStream = try {
        source.openFile(entry.patchEntry)
            ?: throw AssetInstallException("块级差量条目读不出：${entry.patchEntry}")
    } catch (e: IOException) {
        throw AssetInstallException("块级差量条目损坏：${entry.patchEntry}（${e.message}）", e)
    }

    private fun insideRoot(path: String): Boolean {
        if (path == rootPath) return true
        if (path.startsWith(rootPath + File.separatorChar)) return true
        // Windows 上 canonical 恒用反斜杠，条目名却是正斜杠：两种分隔符都认（与
        // GameAssetInstaller.isInsideRoot 同口径，少认一种会把合法目标误判成越界）。
        return File.separatorChar == '\\' && path.startsWith("$rootPath/")
    }

    companion object {
        /**
         * 补丁条目的固定前缀（相对归档根），与发布侧 `emit_update.py` 的
         * `BLOCK_DELTA_PREFIX` **逐字同值**。它是控制条目：既不进 [listDir] 的根一层，
         * 也不会被解包器当成资产落盘（`update-blocks` 不在 [GameAssetInstaller.DATA_DIRS] 里）。
         */
        const val PATCH_DIR_PREFIX: String = "update-blocks/"

        /** 前缀的第一段名字（根一层要把它藏起来）。 */
        const val PATCH_ROOT_NAME: String = "update-blocks"

        val UTF8: java.nio.charset.Charset = StandardCharsets.UTF_8

        private val MAGIC_LINE = "EBPD1\n".toByteArray(UTF8)

        private const val FORMAT = "1"
        private const val KEY_FORMAT = "fmt"
        private const val KEY_BLOCK = "block"
        private const val KEY_BASE_SHA = "base-sha256"
        private const val KEY_RESULT_SHA = "result-sha256"
        private const val KEY_RESULT_SIZE = "result-size"
        private const val KEY_OP_COUNT = "op-count"

        /** op 标签：`C` = 引用旧文件的第 N 块，`R` = 后面 N 字节是原文。 */
        private const val OP_COPY = 'C'
        private const val OP_RUN = 'R'

        /** 头部读取上限，与发布侧 `BLOCK_PATCH_HEADER_MAX` 同值。 */
        private const val HEADER_MAX_BYTES = 4096

        /** 块长上限：正常 4 KiB；超了只可能是造出来的补丁（越界块号会拿它去 seek 旧文件）。 */
        private const val MAX_BLOCK_BYTES = 1024L * 1024L

        /** 补丁枚举的广度上限：语料总共 3,971 条文件，翻几倍就不是发布侧写的包。 */
        private const val MAX_PATCH_SCAN = 20_000

        private const val BASE_READ_BUFFER = 1 shl 20

        private const val NEWLINE = '\n'.code.toByte()

        /** 与发布侧 `split_block_patch` 同一个判据：64 位小写十六进制。 */
        internal fun isLowerHex64(text: String): Boolean =
            text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

        /** 首行魔数比对（primitive 数组没有 `startsWith`，这里逐字节比，语义同「前缀相等」）。 */
        private fun hasMagicPrefix(data: ByteArray): Boolean {
            if (data.size < MAGIC_LINE.size) return false
            for (i in MAGIC_LINE.indices) if (data[i] != MAGIC_LINE[i]) return false
            return true
        }

        /** 小写十六进制摘要（与 `GameAssetInstaller` 的 `hexString` 同形状，两侧都是给 sha 用的）。 */
        private fun shaHex(bytes: ByteArray): String {
            val out = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                out.append(HEX_CHARS[(b.toInt() shr 4) and 0x0F]).append(HEX_CHARS[b.toInt() and 0x0F])
            }
            return out.toString()
        }

        private val HEX_CHARS = "0123456789abcdef".toCharArray()
    }
}
