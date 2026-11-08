package com.eratw.port.platform.storage

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** `mods/` 下一条通过校验的 mod 文件（只描述事实，不含任何游戏语义）。 */
data class ModEntry(
    /** 文件名，含扩展名，保留磁盘上的原始大小写。 */
    val name: String,
    /** 字节数。 */
    val sizeBytes: Long,
    /** 最后修改时间（epoch 毫秒，`File.lastModified()` 口径）。 */
    val lastModifiedMillis: Long,
)

/**
 * 一条被拒绝的候选：**文件名 + 可直接上屏的原因**。
 *
 * 拒绝不是异常路径 —— 玩家把坏文件丢进 `mods/` 是常态，UI 要把 [name] 与 [reason]
 * 原样展示，让玩家知道该删哪个、为什么。
 */
data class ModRejection(
    val name: String,
    val reason: String,
)

/** [ModStore.scan] 的结果：合法条目与被拒条目都按文件名确定性排序。 */
data class ModScan(
    val entries: List<ModEntry>,
    val rejections: List<ModRejection>,
)

/**
 * `mods/` 存储层：扫描、校验、原子落盘、删除。
 *
 * 只做文件系统这一层，不编译、不管装载序、不碰游戏语义。上层（装载期）拿到
 * [ModScan.entries] 自己去解析 ERB；本类只保证「交出去的路径与字节是完整且可信的」。
 *
 * ## 目录口径
 *
 * 目录**由调用方给**，本类不自己造路径规则：[modsDirFor] 只把 `mods` 拼在
 * [GameStorage.gameRoot] 返回的游戏根下，与原版 `Program.SetDirPaths` 的「一切相对 ExeDir」
 * 同口径（`Program.cs:78`、`:202-226`）。Android 侧因此只有一行接线：
 * `ModStore(ModStore.modsDirFor(storage.gameRoot()))`。
 *
 * ## 为什么核心逻辑是纯函数
 *
 * 文件名校验、排序、路径安全检查全在 [companion object] 里，只吃 `String` / `File`，
 * 不碰 `Context`、不碰 `AssetManager`，所以 `:platform:test` 的纯 JVM 单测能全量覆盖
 * （本仓无 Robolectric，见 `GameStorageRootChoiceTest` 的同款做法）。
 *
 * ## 原子性
 *
 * [save] 一律「同目录 `.tmp` → `fsync` → `rename`」：任何一步失败都删掉临时文件，
 * 目标文件要么是旧的完整版、要么是新的完整版，不存在半截。Android 的文件系统是
 * POSIX，`rename` 原地覆盖且原子；Windows 上 `renameTo` 目标存在会失败，[replace]
 * 退化为「删目标再改名」（同目录，窗口极小，仍不产生半截文件）。
 */
class ModStore(
    /** mods 目录本体；不存在时 [scan] 返回空、[save] 自动创建。 */
    val modsDir: File,
) {

    // ---- 扫描 ----------------------------------------------------------------

    /**
     * 列出 [modsDir] 下的 mod 候选。
     *
     * 跳过（**不报错**，它们本来就不是 mod 候选）：隐藏文件（名字以 `.` 开头）、
     * 非 `.ERB` 扩展名（扩展名大小写不敏感）、子目录。玩家把 `readme.txt` 或 `.nomedia`
     * 丢进来不该在 UI 上报红。
     *
     * 进 [ModScan.rejections] 的是「看起来是 mod（`.ERB`）却过不了校验」的文件：空文件、
     * 超大、文件名非法、不是普通文件。每条都带原因，不静默丢。
     *
     * 目录不存在 ⇒ 空结果（首次启动没有 mods 是正常态）；路径存在但不是目录、或列不出来
     * ⇒ 抛 [IOException]（这是配置/权限事故，静默返回空会让 UI 说「没有 mod」骗人）。
     */
    fun scan(): ModScan {
        val dir = modsDir
        if (!dir.exists()) return ModScan(emptyList(), emptyList())
        if (!dir.isDirectory) throw IOException("mods 路径不是目录：${dir.absolutePath}")
        val files = dir.listFiles() ?: throw IOException("无法列出 mods 目录：${dir.absolutePath}")

        val entries = ArrayList<ModEntry>(files.size)
        val rejections = ArrayList<ModRejection>()
        for (file in files) {
            val name = file.name
            if (name.startsWith(".")) continue
            if (!hasErbExtension(name)) continue
            if (file.isDirectory) continue
            if (!file.isFile) {
                rejections += ModRejection(name, notFileReason(name))
                continue
            }
            val nameReason = validateName(name)
            if (nameReason != null) {
                rejections += ModRejection(name, nameReason)
                continue
            }
            val size = file.length()
            if (size == 0L) {
                rejections += ModRejection(name, REASON_EMPTY)
                continue
            }
            if (size > MAX_MOD_BYTES) {
                rejections += ModRejection(name, tooLargeReason(size))
                continue
            }
            entries += ModEntry(name, size, file.lastModified())
        }
        entries.sortWith(ENTRY_ORDER)
        rejections.sortWith(REJECTION_ORDER)
        return ModScan(entries, rejections)
    }

    // ---- 落盘 ----------------------------------------------------------------

    /**
     * 原子写入一份 mod 字节；同名已存在则**整份替换**（不会产生 `xxx (1).ERB` 这类第二份）。
     *
     * 返回落盘后的实际 [ModEntry]（size / mtime 取 rename 之后的 stat，不是入参推算）。
     *
     * @throws IllegalArgumentException 文件名非法或内容为空（消息即可上屏的原因）
     * @throws IOException 目录建不出来、写到一半失败、rename 失败（临时文件已清理）
     */
    fun save(name: String, bytes: ByteArray): ModEntry {
        requireValidName(name)
        if (bytes.isEmpty()) throw IllegalArgumentException(REASON_EMPTY)
        if (bytes.size.toLong() > MAX_MOD_BYTES) throw IllegalArgumentException(tooLargeReason(bytes.size.toLong()))
        return save(name, ByteArrayInputStream(bytes))
    }

    /**
     * 原子写入一份 mod 流（导入大文件用，不整份进内存）。
     *
     * 大小上限**边写边守**：超限立即中止并清理临时文件，不会先把 300 MB 写完再拒。
     * 流读取失败同样走清理路径，`mods/` 里不会出现半截文件。
     */
    fun save(name: String, source: InputStream): ModEntry {
        requireValidName(name)
        val dir = modsDir
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建 mods 目录：${dir.absolutePath}")
        }
        val target = File(dir, name)
        // 临时文件名带唯一后缀：并发两次 save 不会互踩同一个 .tmp。
        val tmp = File(dir, "$name.${System.nanoTime()}.tmp")
        try {
            var written = 0L
            FileOutputStream(tmp).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    written += read
                    if (written > MAX_MOD_BYTES) throw IOException(tooLargeReason(written))
                    out.write(buffer, 0, read)
                }
                if (written == 0L) throw IOException(REASON_EMPTY)
                out.flush()
                // rename 之前落盘：掉电/进程被杀时宁可丢这次 save，也不要留下长度对但内容空的目标文件。
                out.fd.sync()
            }
            replace(tmp, target)
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } catch (e: RuntimeException) {
            tmp.delete()
            throw e
        }
        return ModEntry(name, target.length(), target.lastModified())
    }

    // ---- 删除 ----------------------------------------------------------------

    /**
     * 删除 [modsDir] 下的一个 `.ERB`。
     *
     * 只删 `mods/` 下、只删 `.ERB`：名字先过 [validateName]，再用 canonical 路径复核
     * 父目录必须**恰好**是 `mods/`（纵深防御，防校验漏掉的形态）。
     *
     * @return true = 真删了；false = 名字合法但不存在（**幂等，不抛**）
     * @throws IllegalArgumentException 名字非法 / 路径穿越 / 不是 `.ERB`（消息即可上屏的原因）
     * @throws IOException 文件存在但删不掉（权限/占用）
     */
    fun remove(name: String): Boolean {
        val target = resolveInside(name)
        if (!target.isFile) return false
        if (!target.delete()) throw IOException("无法删除 mod：$name")
        return true
    }

    /**
     * 把 [name] 解析成 `mods/` 内的绝对文件，越界即拒。
     *
     * 校验两道：先 [validateName]（文案级，能直接上屏），再比对 canonical 父目录。
     */
    fun resolveInside(name: String): File {
        requireValidName(name)
        val dir = modsDir
        val canonicalDir = dir.canonicalFile
        val target = File(canonicalDir, name).canonicalFile
        if (target.parentFile != canonicalDir) throw IllegalArgumentException(escapesReason(name))
        return target
    }

    // ---- 内部 ----------------------------------------------------------------

    private fun requireValidName(name: String) {
        val reason = validateName(name) ?: return
        throw IllegalArgumentException(reason)
    }

    /**
     * 同目录改名；失败时清理留给调用方。
     *
     * POSIX（Android）上 `rename` 目标存在即原地原子替换，第一条就返回。
     * Windows 上目标存在会失败，退化为「删目标 → 改名」：只在 `mods/` 内动，窗口内
     * 最坏是「旧版已删、新版未就位」，也不会出现半截内容。
     */
    private fun replace(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        if (target.exists() && !target.delete()) throw IOException("无法替换既有 mod：${target.name}")
        if (!tmp.renameTo(target)) throw IOException("无法落盘 mod：${target.name}")
    }

    companion object {
        /** `mods` 目录名（拼在游戏根下）。 */
        const val MODS_DIR_NAME = "mods"

        /** mod 扩展名（比较时大小写不敏感）。 */
        const val ERB_EXTENSION = ".ERB"

        /** 口上头文件扩展名（ERH，[validateName] 白名单成员之一；大小写不敏感）。 */
        const val ERH_EXTENSION = ".ERH"

        /**
         * 口上 zip 包扩展名（[validateName] 白名单成员之一；大小写不敏感）。
         *
         * zip 只在**导入链入口**以整包形态出现（app 侧解包成条目后才落 `mods/`），
         * 这里放行它只为 SAF 面的字节校验口径一致；装载与扫描面（[scan]）仍只认 `.ERB`。
         */
        const val ZIP_EXTENSION = ".ZIP"

        /**
         * 单文件上限 64 MiB。
         *
         * 原版整份 ERB 语料也就几 MB 量级，单个口上文件过 64 MiB 必然是塞错了东西
         * （存档、音视频、整包）。定成常量而不是配置项：这是防呆闸，不是可调参数。
         *
         * 2026-11 从 32 MiB 抬到 64 MiB：导入链现在整包收口上 zip（解包前整份字节要过
         * [MAX_MOD_BYTES] 的读取闸），32 MiB 会把合法包挡在读取口。数值依据：实测群友
         * 整合包（蕾米莉亚色情特化 v1.3，纯 ERB 口上）54 个 .ERB 共约 12 MB，zip 化后
         * 单包只小不大——64 MiB 对整包留约 5 倍余量。单文件 `.ERB` 的真实量级（数百 KB
         * 级）仍远低于此，防呆语义不变。导入链**无总量闸**：54 文件逐个 save 各自过
         * 单文件闸即可，zip 解出的条目落盘同样按条目字节过这道闸。
         */
        const val MAX_MOD_BYTES: Long = 64L * 1024 * 1024

        /** 文件名长度上限（字符数）；APK 内 ZIP 名与多数文件系统的单段上限都远大于此。 */
        const val MAX_NAME_CHARS = 128

        private const val BUFFER_BYTES = 8 * 1024

        /** 空文件拒绝原因。 */
        const val REASON_EMPTY = "空文件（0 字节）"

        /** 拒绝原因：超大（带实际字节数与上限，UI 直接展示）。 */
        fun tooLargeReason(size: Long): String = "文件过大：$size 字节，超过上限 $MAX_MOD_BYTES 字节"

        /** 拒绝原因：名字为空 / 全空白。 */
        const val REASON_NAME_BLANK = "文件名为空"

        /** 拒绝原因：不是普通文件。 */
        fun notFileReason(name: String): String = "不是普通文件：$name"

        /** 拒绝原因：canonical 复核发现越出 mods 目录。 */
        fun escapesReason(name: String): String = "解析后的路径不在 mods 目录内：$name"

        /** 拒绝原因：绝对路径。 */
        fun absoluteReason(name: String): String = "文件名是绝对路径：$name"

        /** 拒绝原因：含 `..`。 */
        fun traversalReason(name: String): String = "文件名含路径穿越（..）：$name"

        /** 拒绝原因：含路径分隔符。 */
        fun separatorReason(name: String): String = "文件名含路径分隔符（/ 或 \\）：$name"

        /** 拒绝原因：含控制字符或 Windows 保留字符。 */
        fun illegalCharReason(name: String): String = "文件名含非法字符：$name"

        /** 拒绝原因：过长。 */
        fun tooLongReason(name: String): String =
            "文件名过长：${name.length} 字符，超过上限 $MAX_NAME_CHARS 字符"

        /** 拒绝原因：首尾带空白。 */
        fun blankEdgeReason(name: String): String = "文件名首尾含空白：$name"

        /** 拒绝原因：扩展名不在 `.ERB`/`.ERH`/`.ZIP` 白名单里。 */
        fun extensionReason(name: String): String = "扩展名不是 .ERB/.ERH/.zip：$name"

        /** mods 目录 = 游戏根下的 `mods`（与 [GameStorage] 的根口径一致，不另造路径）。 */
        fun modsDirFor(gameRoot: File): File = File(gameRoot, MODS_DIR_NAME)

        /**
         * 扩展名判定：大小写不敏感，且**必须**有主名（`.ERB` 本身算隐藏文件，不算 mod）。
         *
         * 用逐字符比较而不是 `lowercase()`：后者依赖 locale，土耳其语环境下 `I` 会折成 `ı`，
         * 排序与判定就不再是「同机同输入同输出」。
         */
        fun hasErbExtension(name: String): Boolean = hasNamedExtension(name, ERB_EXTENSION)

        /** 口上头文件（`.ERH`）判定：与 [hasErbExtension] 同口径，只是扩展名不同。 */
        fun hasErhExtension(name: String): Boolean = hasNamedExtension(name, ERH_EXTENSION)

        /**
         * 导入面白名单：`.ERB`/`.ERH`/`.ZIP` 之一（大小写不敏感，同 [hasErbExtension] 口径）。
         *
         * [validateName] 用它放行口上包的三种合法形态；[scan] 的扫描面不受影响
         * （装载链只认 `.patch`，`mods/` 里的源留档清单仍按 `.ERB` 口径列）。
         */
        fun hasModExtension(name: String): Boolean =
            hasNamedExtension(name, ERB_EXTENSION) ||
                hasNamedExtension(name, ERH_EXTENSION) ||
                hasNamedExtension(name, ZIP_EXTENSION)

        /** 扩展名尾段逐字符折叠比较（[foldCase]，主名必须非空）。 */
        private fun hasNamedExtension(name: String, extension: String): Boolean {
            if (name.length <= extension.length) return false
            val start = name.length - extension.length
            for (i in extension.indices) {
                if (foldCase(name[start + i]) != foldCase(extension[i])) return false
            }
            return true
        }

        /**
         * 纯函数文件名校验：合法返回 null，否则返回**可上屏的原因**。
         *
         * 判定顺序按「越危险越先说」：空 → 绝对路径 → `..` → 分隔符 → 非法字符 →
         * 过长 → 首尾空白 → 扩展名。这样 `../evil.ERB` 报的是穿越而不是「含分隔符」，
         * UI 上的诊断更准。
         *
         * 注意：含 `..` 但无分隔符（如 `v1..2.ERB`）其实穿不出去，仍然拒 —— 这种名字
         * 没有正当用途，一律按穿越处理，宁可错杀。
         */
        fun validateName(name: String): String? {
            if (name.isBlank()) return REASON_NAME_BLANK
            if (isAbsoluteName(name)) return absoluteReason(name)
            if (name.contains("..")) return traversalReason(name)
            for (c in name) {
                if (c == '/' || c == '\\') return separatorReason(name)
            }
            for (c in name) {
                if (c < ' ' || c == '\u007f' || c in FORBIDDEN_CHARS) return illegalCharReason(name)
            }
            if (name.length > MAX_NAME_CHARS) return tooLongReason(name)
            if (name.first().isWhitespace() || name.last().isWhitespace()) return blankEdgeReason(name)
            if (!hasModExtension(name)) return extensionReason(name)
            return null
        }

        /** 文件名比较：先按 ASCII 折叠（大小写不敏感）比，再按原文比 —— 全程不碰 locale。 */
        fun compareNames(a: String, b: String): Int {
            val n = minOf(a.length, b.length)
            for (i in 0 until n) {
                val ca = foldCase(a[i])
                val cb = foldCase(b[i])
                if (ca != cb) return ca.compareTo(cb)
            }
            if (a.length != b.length) return a.length - b.length
            return a.compareTo(b)
        }

        private val FORBIDDEN_CHARS = charArrayOf(':', '*', '?', '"', '<', '>', '|')

        private val ENTRY_ORDER = Comparator<ModEntry> { a, b -> compareNames(a.name, b.name) }

        private val REJECTION_ORDER = Comparator<ModRejection> { a, b -> compareNames(a.name, b.name) }

        /** ASCII 大小写折叠（只折 A-Z，其余原样），locale 无关。 */
        private fun foldCase(c: Char): Char = if (c in 'A'..'Z') c + 32 else c

        /**
         * 绝对路径判定：`/x`、`\x`、`C:x`、`C:\x`。
         *
         * 不用 `File.isAbsolute`：它按运行平台判，Windows 上 `/x` 是绝对、Linux 上不是，
         * 单测就会因机器而绿/红。这里按「跨平台都当绝对」的保守口径手判。
         */
        private fun isAbsoluteName(name: String): Boolean {
            if (name.startsWith("/") || name.startsWith("\\")) return true
            return name.length >= 2 && name[1] == ':' && name[0].isLetter()
        }
    }
}
