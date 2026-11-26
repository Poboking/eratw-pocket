package com.eratw.port.android

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * 导入包旁挂媒体资源面（ADR-014 S4）：把口上 zip 里 `resources/mod/<dir>/…` 与
 * `sound/mod/<dir>/…` 落到 gameRoot，并在 `mods/rt2/` 写 `<packageId>.media` 清单。
 *
 * 与 [ModImportAssets]（`.assets` 整目录 swap、包独占目录）的关键差别与取舍：
 * - **落点是共享树，不是包独占目录**：`resources/mod/<dir>/` 下可能有玩家自己放的东西，所以
 *   这里**只写清单内文件、只按旧清单删旧文件**，绝不 deleteRecursively 整个 `<dir>`；
 *   「同包重复导入 = 整树替换」由清单差集实现：旧清单 - 新清单 = 待删，新清单条目全部覆盖写。
 * - **清单即真相**：`<pkg>.media` 逐行记录本次写入的 gameRoot 相对路径，重导/卸载/GC 都按这份
 *   清单逐条删；清单不认识的盘上文件一律不碰（那是玩家的东西）。清单后缀 `.media` 不在
 *   [ModPackageInventory] 的成员/孤儿判据里，GC 不会误删它。
 * - **失败零残留（尽力而为，不承诺强事务）**：先全量校验、再写临时目录
 *   （`<pkg>.media.tmp-*`），`commit()` 时才逐文件 ATOMIC_MOVE 就位、最后原子写清单；commit
 *   中途失败会删掉本次已就位的文件并尽力恢复旧清单，但**已被覆盖的旧文件内容无法回滚**
 *   （没有备份整棵共享树的成本预算，KDoc 在此明示不承诺强事务）。
 * - **跨包同名 `<dir>` 拒导**：两个包声明同一个 `<dir>` 会在共享树里互相踩，导入期直接拒
 *   （[MediaDirConflictException] 文案点名两个 packageId 与 `<dir>`），不做「后到覆盖先到」。
 * - **null/空 `<dir>` 完全不动作**：老包（`eratw-pack.json` 无 `resources` 声明）走既有
 *   `.assets` 路径，逐位不变；非法 `<dir>` 只 WARN 不落盘。
 *
 * 清单格式（UTF-8、`\n` 换行、末尾换行）：首行 `RT2MEDIA/1 dir=<dir>`，其后每行一个
 * gameRoot 相对路径（`resources/mod/<dir>/a.png`、`sound/mod/<dir>/se.mp3`）。首行同时承担
 * 版本与目录名两个用途：跨包冲突扫描只读首行，不必打开整份清单。
 *
 * 为什么清单记相对路径而不是文件名：`<dir>` 下允许嵌套子目录（ERB 侧
 * `GCREATEFROMFILE "mod/<dir>/sub/a.png"` 可直接引用），相对路径是唯一无歧义的落点表达。
 */
internal object ModImportMediaAssets {

    /** `<packageId>.media` 清单后缀（rt2 目录下；[ModPackageInventory] 的成员判据不认它，GC 安全）。 */
    const val MEDIA_MANIFEST_SUFFIX: String = ".media"

    /** 清单版本行版本号；格式升级时改这里，读侧按前缀拒绝旧/未来格式（不猜着读）。 */
    const val MANIFEST_VERSION: String = "RT2MEDIA/1"

    /** 版本行里目录名的键名：`RT2MEDIA/1 dir=<dir>`。 */
    const val MANIFEST_DIR_KEY: String = "dir="

    /** 图/文树根（gameRoot 相对，`/` 分隔）。 */
    const val RESOURCE_ROOT: String = "resources/mod/"

    /** 音频树根（gameRoot 相对）。 */
    const val SOUND_ROOT: String = "sound/mod/"

    /** 临时目录/清单后缀（与 [ModImportAssets.TEMP_SUFFIX] 同形但独立，判据不共享）。 */
    internal const val TEMP_SUFFIX: String = ".tmp-"

    /** 图/文单文件上限（ADR-014 D5）。 */
    const val MAX_RESOURCE_FILE_BYTES: Long = 4L * 1024L * 1024L

    /** 图/文单包累计上限。 */
    const val MAX_RESOURCE_TOTAL_BYTES: Long = 16L * 1024L * 1024L

    /** 音频单文件上限。 */
    const val MAX_SOUND_FILE_BYTES: Long = 8L * 1024L * 1024L

    /** 音频单包累计上限。 */
    const val MAX_SOUND_TOTAL_BYTES: Long = 32L * 1024L * 1024L

    /** 两棵树合计上限（= 16 + 32，冗余安全带；单测用 [MediaLimits] 注入小上限才可测）。 */
    const val MAX_MEDIA_TOTAL_BYTES: Long = 48L * 1024L * 1024L

    private const val LINE_SEPARATOR: String = "\n"
    private const val SEGMENT_SEPARATOR: Char = '/'

    /**
     * 一次媒体面落盘的坐标（把四个参数收成一个对象：detekt 默认 `LongParameterList` 的
     * 函数参数上限是 6，而 [stageSafely] 还要带 [copy] 与 [MediaLimits]）。
     */
    internal data class MediaTarget(
        val packageId: String,
        val mediaDirName: String?,
        val gameRoot: File,
        val rt2Dir: File,
    )

    /** 媒体面限额（默认 = ADR-014 D5 常量；单测注入小上限覆盖「合计超限」这类冗余安全带）。 */
    internal data class MediaLimits(
        val maxResourceFileBytes: Long = MAX_RESOURCE_FILE_BYTES,
        val maxResourceTotalBytes: Long = MAX_RESOURCE_TOTAL_BYTES,
        val maxSoundFileBytes: Long = MAX_SOUND_FILE_BYTES,
        val maxSoundTotalBytes: Long = MAX_SOUND_TOTAL_BYTES,
        val maxCombinedBytes: Long = MAX_MEDIA_TOTAL_BYTES,
    )

    /** stage 结果：`staged == null` 表示本次不落盘（无声明 / 校验拒绝 / 全跳过）。 */
    internal data class Plan(
        val staged: Staged?,
        val entryCount: Int,
        val warnings: List<String>,
    )

    /**
     * 跨包 `<dir>` 冲突：已有别的包在 `mods/rt2/<otherPkg>.media` 首行声明了同一个 `<dir>`。
     * 导入端按「整包拒导」处理（与 [AssetConflictException] 同口径：STORAGE 失败面）。
     */
    internal class MediaDirConflictException internal constructor(message: String) : Exception(message)

    /**
     * 已写入临时目录、等待 `commit()` 的媒体面。
     *
     * 原子性边界（明示，不承诺强事务）：`commit()` 逐文件 ATOMIC_MOVE + 最后原子写清单；
     * 失败时删本次已就位文件、尽力恢复旧清单，但旧文件内容无法回滚。
     */
    internal class Staged internal constructor(
        private val tmpDir: File,
        private val request: MediaTarget,
        private val mediaDirName: String,
        private val relativePaths: List<String>,
        val entryCount: Int,
    ) {
        private var committed = false

        /** 逐文件原子就位 + 原子写清单；失败删本次已写文件并尽力恢复旧清单后原样抛。 */
        fun commit() {
            val manifestFile = File(request.rt2Dir, request.packageId + MEDIA_MANIFEST_SUFFIX)
            val oldManifest = manifestFile.takeIf { it.isFile }?.readBytes()
            val oldPaths = oldManifest?.let { manifestPaths(it.toString(Charsets.UTF_8)) }.orEmpty()
            val placed = ArrayList<File>()
            try {
                deleteReplacedFiles(oldPaths)
                for (relativePath in relativePaths) place(relativePath, placed)
                writeManifest(manifestFile, manifestBytes())
                committed = true
            } catch (e: IOException) {
                rollback(placed, manifestFile, oldManifest)
                throw e
            } finally {
                tmpDir.deleteRecursively()
            }
        }

        /** 未提交就整树丢弃临时目录（提交后由 finally 收尾，不重复删正式面）。 */
        fun discard() {
            if (!committed) tmpDir.deleteRecursively()
        }

        private fun deleteReplacedFiles(oldPaths: List<String>) {
            val newPaths = relativePaths.toHashSet()
            for (path in oldPaths) {
                if (path !in newPaths) deleteListedFile(path)
            }
        }

        private fun deleteListedFile(relativePath: String) {
            val target = File(request.gameRoot, relativePath)
            if (isInsideGameRoot(target) && target.isFile) target.delete()
        }

        private fun place(relativePath: String, placed: MutableList<File>) {
            val source = File(tmpDir, relativePath)
            val destination = File(request.gameRoot, relativePath)
            destination.parentFile?.mkdirs()
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            placed += destination
        }

        private fun manifestBytes(): ByteArray {
            val builder = StringBuilder()
            builder.append(MANIFEST_VERSION).append(' ').append(MANIFEST_DIR_KEY)
                .append(mediaDirName).append(LINE_SEPARATOR)
            for (relativePath in relativePaths) builder.append(relativePath).append(LINE_SEPARATOR)
            return builder.toString().toByteArray(Charsets.UTF_8)
        }

        private fun writeManifest(manifestFile: File, bytes: ByteArray) {
            val temp = File(manifestFile.parentFile, manifestFile.name + TEMP_SUFFIX + System.nanoTime())
            try {
                temp.writeBytes(bytes)
                Files.move(
                    temp.toPath(),
                    manifestFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } finally {
                temp.delete()
            }
        }

        private fun rollback(placed: List<File>, manifestFile: File, oldManifest: ByteArray?) {
            for (file in placed) runCatching { file.delete() }
            runCatching {
                if (oldManifest == null) {
                    manifestFile.delete()
                } else {
                    writeManifest(manifestFile, oldManifest)
                }
            }
        }

        private fun isInsideGameRoot(target: File): Boolean =
            target.canonicalPath.startsWith(request.gameRoot.canonicalPath + File.separator)
    }

    /**
     * 媒体面安全入口：IO 异常收敛成一条 WARN（不拦导入，与 [ModImportAssets.stageSafely] 同口径），
     * 跨包 `<dir>` 冲突原样抛出由调用方按拒导处理。
     */
    fun stageSafely(
        archiveZips: List<ByteArray>,
        target: MediaTarget,
        copy: ModImportCopy,
        limits: MediaLimits = MediaLimits(),
    ): Plan = try {
        stage(archiveZips, target, copy, limits)
    } catch (e: IOException) {
        Plan(
            null,
            0,
            listOf(copy.fill(copy.mediaStageFailedFormat, e.message ?: e.javaClass.simpleName ?: "null")),
        )
    }

    /**
     * 校验 + 暂存（纯 JVM，单测直接点名）：null/空 `<dir>` 完全不动作；非法 `<dir>` 只 WARN；
     * 冲突/校验失败/全跳过 ⇒ `staged == null`；通过则写临时目录并返回待 commit 的 [Staged]。
     */
    fun stage(
        archiveZips: List<ByteArray>,
        target: MediaTarget,
        copy: ModImportCopy,
        limits: MediaLimits,
    ): Plan {
        val dirName = target.mediaDirName?.takeIf { it.isNotEmpty() }
        return when {
            dirName == null -> Plan(null, 0, emptyList())
            !ModImportAssets.isLegalSingleSegmentName(dirName) ->
                Plan(null, 0, listOf(copy.fill(copy.mediaDirInvalidFormat, dirName)))
            else -> stageChecked(archiveZips, target, dirName, copy, limits)
        }
    }

    private fun stageChecked(
        archiveZips: List<ByteArray>,
        target: MediaTarget,
        dirName: String,
        copy: ModImportCopy,
        limits: MediaLimits,
    ): Plan {
        val conflict = conflictingPackageId(target.rt2Dir, target.packageId, dirName)
        if (conflict != null) {
            throw MediaDirConflictException(
                copy.fill(copy.mediaDirConflictFormat, dirName, conflict, target.packageId),
            )
        }
        val scan = MediaScan(dirName, copy, limits)
        for (zip in archiveZips) {
            val entries = ModZip.entriesWhere(zip) { name ->
                name.startsWith(RESOURCE_ROOT) || name.startsWith(SOUND_ROOT)
            }
            for (entry in entries) scan.accept(entry)
        }
        if (scan.rejected || scan.accepted.isEmpty()) return Plan(null, 0, scan.warnings)
        val tmpDir = File(
            target.rt2Dir,
            target.packageId + MEDIA_MANIFEST_SUFFIX + TEMP_SUFFIX + System.nanoTime(),
        )
        try {
            for (file in scan.accepted) writeEntry(tmpDir, file)
        } catch (e: IOException) {
            tmpDir.deleteRecursively()
            throw e
        }
        val paths = scan.accepted.map { it.relativePath }
        return Plan(Staged(tmpDir, target, dirName, paths, paths.size), paths.size, scan.warnings)
    }

    /** 写临时目录：落点必须在 root 内（清单/条目名已校验，这里是防篡改的最后一道闸）。 */
    private fun writeEntry(root: File, file: MediaFile) {
        val target = File(root, file.relativePath)
        if (!target.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
            throw IOException("媒体资源路径逃逸：" + file.relativePath)
        }
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { output ->
            output.write(file.bytes)
            output.flush()
            output.fd.sync()
        }
    }

    /** 扫描 rt2 下其它 `<pkg>.media` 的首行；返回第一个声明同 `<dir>` 的 packageId。 */
    private fun conflictingPackageId(rt2Dir: File, packageId: String, dirName: String): String? {
        val ownName = packageId + MEDIA_MANIFEST_SUFFIX
        return rt2Dir.listFiles().orEmpty()
            .filter { it.isFile && it.name != ownName && it.name.endsWith(MEDIA_MANIFEST_SUFFIX) }
            .sortedBy { it.name }
            .firstOrNull { declaredMediaDirOf(it) == dirName }
            ?.name
            ?.removeSuffix(MEDIA_MANIFEST_SUFFIX)
    }

    /** 读清单首行 `RT2MEDIA/1 dir=<dir>` 的 `<dir>`；格式/名字不认识 ⇒ null（不猜）。 */
    private fun declaredMediaDirOf(manifestFile: File): String? {
        val prefix = MANIFEST_VERSION + " " + MANIFEST_DIR_KEY
        val firstLine = runCatching { manifestFile.readLines().firstOrNull() }.getOrNull()
        val dir = firstLine?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
        return dir?.takeIf { ModImportAssets.isLegalSingleSegmentName(it) }
    }

    /** 旧清单 → 可采信的相对路径清单（首行跳过；不合法/不认识的行走 [listedPathOrNull] 丢弃）。 */
    private fun manifestPaths(text: String): List<String> =
        text.split(LINE_SEPARATOR).drop(1).mapNotNull { listedPathOrNull(it) }

    /** 单行相对路径校验：必须落在两棵树之一、`<dir>` 单段合法、相对名合法；否则 null。 */
    private fun listedPathOrNull(line: String): String? {
        val root = when {
            line.startsWith(RESOURCE_ROOT) -> RESOURCE_ROOT
            line.startsWith(SOUND_ROOT) -> SOUND_ROOT
            else -> ""
        }
        val rest = line.substring(root.length)
        val slash = rest.indexOf(SEGMENT_SEPARATOR)
        val dir = if (slash > 0) rest.substring(0, slash) else ""
        val relative = if (slash > 0) rest.substring(slash + 1) else ""
        return line.takeIf {
            root.isNotEmpty() &&
                ModImportAssets.isLegalSingleSegmentName(dir) &&
                ModImportAssets.isValidResourceName(relative)
        }
    }
}

/** MiB 换算分母（对象与 [MediaScan] 共用；放文件级避免把对象私有成员暴露成 API）。 */
private const val BYTES_PER_MIB: Long = 1024L * 1024L

/** 单棵树（图/文 或 音频）的扫描状态：白名单、单文件/累计上限、已收字节。 */
private class MediaTree(
    val root: String,
    val extensions: Set<String>,
    val maxFileBytes: Long,
    val maxTotalBytes: Long,
    val totalTooLargeFormat: String,
) {
    var totalBytes: Long = 0L
}

/** 待落盘条目：gameRoot 相对路径 + 内容。 */
private data class MediaFile(val relativePath: String, val bytes: ByteArray)

/**
 * 一趟扫描的累加器：逐条目分类（树 / 目录名匹配 / 名字 / 重名 / 扩展名 / 限额），
 * 只在全部条目扫完后由 [ModImportMediaAssets.stage] 决定是否写临时目录。
 */
private class MediaScan(
    private val dirName: String,
    private val copy: ModImportCopy,
    limits: ModImportMediaAssets.MediaLimits,
) {
    private val resourceTree = MediaTree(
        ModImportMediaAssets.RESOURCE_ROOT,
        RESOURCE_EXTENSIONS,
        limits.maxResourceFileBytes,
        limits.maxResourceTotalBytes,
        copy.mediaResourceTotalTooLargeFormat,
    )
    private val soundTree = MediaTree(
        ModImportMediaAssets.SOUND_ROOT,
        SOUND_EXTENSIONS,
        limits.maxSoundFileBytes,
        limits.maxSoundTotalBytes,
        copy.mediaSoundTotalTooLargeFormat,
    )
    private val maxCombinedBytes: Long = limits.maxCombinedBytes
    private val seen = HashSet<String>()
    val warnings = ArrayList<String>()
    val accepted = ArrayList<MediaFile>()
    var rejected = false
    private var combinedBytes = 0L

    fun accept(entry: ModZip.Entry) {
        val tree = treeOf(entry.name)
        val relative = if (tree == null) null else relativeNameUnder(entry.name, tree.root)
        when {
            tree == null || relative == null -> Unit
            !ModImportAssets.isValidResourceName(relative) -> warnInvalidName(entry)
            !seen.add(entry.name) -> warnDuplicate(entry)
            !tree.extensions.contains(extensionOf(relative)) -> rejectExtension(tree, entry)
            else -> acceptWithinLimits(tree, entry, relative)
        }
    }

    private fun treeOf(name: String): MediaTree? = when {
        name.startsWith(ModImportMediaAssets.RESOURCE_ROOT) -> resourceTree
        name.startsWith(ModImportMediaAssets.SOUND_ROOT) -> soundTree
        else -> null
    }

    /** `<root><dir>/<relative>` 的 `<relative>`；第二段与声明 `<dir>` 不同 ⇒ null（静默忽略）。 */
    private fun relativeNameUnder(name: String, root: String): String? {
        val rest = name.substring(root.length)
        val slash = rest.indexOf('/')
        val dir = if (slash > 0) rest.substring(0, slash) else ""
        return if (dir == dirName) rest.substring(slash + 1) else null
    }

    private fun warnInvalidName(entry: ModZip.Entry) {
        warnings += copy.fill(copy.mediaEntryInvalidNameFormat, entry.name)
    }

    private fun warnDuplicate(entry: ModZip.Entry) {
        warnings += copy.fill(copy.mediaEntryDuplicateFormat, entry.name)
    }

    private fun rejectExtension(tree: MediaTree, entry: ModZip.Entry) {
        val allowed = tree.extensions.joinToString(copy.nameListSeparator)
        warnings += copy.fill(copy.mediaExtensionRejectedFormat, entry.name, allowed)
        rejected = true
    }

    private fun acceptWithinLimits(tree: MediaTree, entry: ModZip.Entry, relative: String) {
        val size = entry.bytes.size.toLong()
        val treeTotal = tree.totalBytes + size
        val combinedTotal = combinedBytes + size
        when {
            size > tree.maxFileBytes -> reject(
                copy.fill(copy.mediaFileTooLargeFormat, mib(tree.maxFileBytes), entry.name, size),
            )
            treeTotal > tree.maxTotalBytes -> reject(
                copy.fill(tree.totalTooLargeFormat, mib(tree.maxTotalBytes), treeTotal),
            )
            combinedTotal > maxCombinedBytes -> reject(
                copy.fill(copy.mediaCombinedTooLargeFormat, mib(maxCombinedBytes), combinedTotal),
            )
            else -> {
                tree.totalBytes = treeTotal
                combinedBytes = combinedTotal
                // 落点 = 树根 + 声明 <dir> + 目录内相对名：清单与正式树都按 gameRoot 相对路径表达。
                accepted += MediaFile(tree.root + dirName + "/" + relative, entry.bytes)
            }
        }
    }

    private fun reject(warning: String) {
        warnings += warning
        rejected = true
    }

    private fun mib(bytes: Long): Long = bytes / BYTES_PER_MIB
}

/** 图/文白名单（ADR-014 D6：只 `.txt`，禁 `.csv`——CSV 是引擎装载面，不走旁挂资源）。 */
private val RESOURCE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp", "txt")

/** 音频白名单（ADR-014 D6 一期只这三档）。 */
private val SOUND_EXTENSIONS: Set<String> = setOf("mp3", "ogg", "wav")

/** 扩展名小写化（大小写不敏感：玩家/作者机器大小写习惯不同，不因此拒包）。 */
private fun extensionOf(relativeName: String): String =
    relativeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
