package com.eratw.port.android

import com.eratw.port.platform.storage.ModStore
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 导入前的**只读预览**（导入确认面的一屏读数）：在真正调用
 * [ModImportController.import] 之前，把本次选中的文件算成一份玩家可读的摘要。
 *
 * ## 只读纪律（本文件最重要的一条）
 *
 * 本对象**绝不落盘、绝不改导入事务路径**：不建临时目录、不写 `mods/`、不碰 `rt2`（不写也不删）、
 * 不调用 [ModImportAssets.stage] / [ModImportMediaAssets.stage] 这类会写盘的入口。全部读数来自
 * 内存里的 zip 字节与对 `rt2` 的**只读列举/首行读**。预览失败不改变导入可行性：调用方拿到
 * [Preview.Unavailable] 仍可照常导入（见下条分工）。
 *
 * ## 与 S4 强校验的分工（限额只提示、不拦截）
 *
 * 本对象给出的限额预警（单文件 / 单树累计 / 两树合计，见 [PreviewWarning]）是**提前提示**，
 * 语义是「导入时很可能被整面拒掉」，**不是拦截**：真正的判据仍是 S4 的两个 `stage` 入口
 * （[ModImportAssets.stage] 的单文件 4 MiB / 累计 16 MiB，
 * [ModImportMediaAssets.stage] 的单文件 4/8 MiB、单树 16/32 MiB、合计 48 MiB），它们才是
 * 拒导/整面不导入的唯一权威。预览与 S4 的判据**同源**：这里只引用它们的 public 常量
 * （[ModImportAssets.MAX_RESOURCE_FILE_BYTES] 等），不复制魔数；单测按 [Limits] 注入小上限。
 *
 * ## 计数口径（尽量与 S4 的采信面一致，偏差在此明示）
 *
 * - 代码面：按 S4 解包口径算 `.erb` / `.erh` 源文件数（zip 条目取 `diskName` 后按 .erb/.erh 计），
 *   另外数 `.erbm`（已编译模块）条目；预览**不编译**，所以只报源文件与已编译模块的数量，
 *   不报函数数 / 指令数（要报就得先编译，那已经不是只读预览了）。
 * - 资源面（`.assets`）：只数 S4 会采信的条目（合法来源目录名 + 合法相对名 + 同来源先到先得），
 *   字节数按这些条目的解压后大小累加。
 * - 媒体面（`eratw-pack.json` 的 `resources` + 两棵树）：只数扩展名白名单内、名字合法、目录段
 *   与声明 `<dir>` 相符的条目；扩展名/名字不合法的条目在这里**静默跳过**（是否整面拒由 S4 判，
 *   预览不重复那套结构校验）。
 * - 跨包目录冲突：读 `rt2` 下其它 `*.media` 清单首行（`RT2MEDIA/1 dir=<dir>`）点名冲突包，
 *   跳过本次包自己的清单文件（[ModImportMediaAssets.conflictingPackageId] 的同口径只读复刻）。
 *
 * ## packageId 的只读推导
 *
 * 冲突扫描要「跳过自身清单」，所以预览必须自己算出本次包身份，口径与 S4 注释里的两条算法
 * 逐条同形（[ModImportController.packageIdFor] / [ModImportController.assetPackageIdFor]，
 * 身份口径是 M2-4 冻结的哈希，不能在这里另发明一套）：
 * - 源包：`kojo-` + sha256(去重后 diskName 按 [ModStore.compareNames] 排序、`\n` 连成串) 前 4 字节；
 * - 纯资源包：`kojo-` + sha256(元数据自述包名) 前 4 字节。
 * 单测用真控制器实例对拍这两个 id（反向证明见 ModImportPreviewTest）。
 */
internal object ModImportPreview {

    /**
     * 一次预览的限额（默认取 S4 的 public 常量，**不复制魔数**；单测注入小上限覆盖超限分支）。
     *
     * [media] 直接复用 [ModImportMediaAssets.MediaLimits]（S4 的同一枚对象），保证族判据不漂移。
     */
    internal data class Limits(
        val assetFileBytes: Long = ModImportAssets.MAX_RESOURCE_FILE_BYTES,
        val assetTotalBytes: Long = ModImportAssets.MAX_RESOURCE_TOTAL_BYTES,
        val media: ModImportMediaAssets.MediaLimits = ModImportMediaAssets.MediaLimits(),
    )

    /**
     * 算一份预览（纯内存 + 只读文件系统，见类 KDoc 的只读纪律）。
     *
     * 失败面一律收敛成 [Preview.Unavailable]（不抛）：空选择、zip 坏 / 加密 / 过
     * [ModZipGuard] 闸、纯资源包认不出载体元数据，都只让预览这一屏不可用，不影响导入。
     *
     * @param picked 导入页已选的源（与 [ModImportController.import] 同形；zip 的字节已读进内存）。
     * @param rt2Dir `mods/rt2`（只读取其下 `*.media` 清单首行）。
     * @param copy 玩家面语言档（只用于拼 [Preview.Unavailable] 的 reason）。
     */
    fun compute(
        picked: List<PickedModFile>,
        rt2Dir: File,
        copy: ModImportCopy = ModImportCopy.ZH,
        limits: Limits = Limits(),
    ): Preview {
        if (picked.isEmpty()) return Preview.Unavailable(copy.noFilesPicked)
        return when (val gathered = gather(picked, copy)) {
            is Gathered.Failed -> Preview.Unavailable(gathered.reason)
            is Gathered.Ok -> {
                val batch = BatchPreviewScan.of(gathered, limits, rt2Dir)
                if (gathered.hasSources) {
                    sourcePreview(gathered, rt2Dir, limits, batch)
                } else {
                    assetPackPreview(gathered, rt2Dir, limits, copy, batch)
                }
            }
        }
    }

    /** 源包身份：`kojo-` + sha256(去重后 diskName 排序 `\n` 连串) 前 4 字节（与 S4 同口径）。 */
    fun sourcePackageId(diskNames: List<String>): String {
        val joined = diskNames.distinct()
            .sortedWith(Comparator { first, second -> ModStore.compareNames(first, second) })
            .joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return ModPackageInventory.PACKAGE_ID_PREFIX + digestPrefixHex(digest)
    }

    /**
     * 预览的玩家面行（3–6 行 + 每条预警一行）：语言档只经 [copy] 进来，本函数不判语言。
     * 排版（放哪一屏、什么色调）留在 Compose 侧。
     */
    fun previewLines(preview: Preview, copy: ModImportCopy): List<String> = when (preview) {
        is Preview.Unavailable -> listOf(copy.fill(copy.previewUnavailableFormat, preview.reason))
        is Preview.Ready -> PreviewLineRenderer.readyLines(preview, copy)
    }

    // ------------------------------------------------------------------ 分流：源包 / 纯资源包

    /**
     * 逐文件分流（只读，与 [ModImportController.import] 的 ⓪ 相位同形）：
     * - 非 zip：直接算一个源文件（diskName = displayName）；
     * - zip：一次 `entriesWhere` 只把源/已编译条目字节读进堆，其余条目只过 [ModZipGuard] 闸；
     *   一个源条目都没有的 zip 记为「空包」，留给纯资源包分支认元数据。
     */
    private fun gather(picked: List<PickedModFile>, copy: ModImportCopy): Gathered {
        val sourceNames = ArrayList<String>()
        val archiveZips = ArrayList<ByteArray>()
        val emptyZips = ArrayList<PickedModFile>()
        val items = ArrayList<GatheredItem>()
        var compiledModuleCount = 0
        for (file in picked) {
            if (!ModZip.isZipName(file.displayName)) {
                sourceNames += file.diskName
                items += GatheredItem(file.diskName, listOf(file.diskName), null)
                continue
            }
            archiveZips += file.bytes
            val unpacked = readPreviewEntries(file, copy)
            val entries = (unpacked as? Unpacked.Ok)?.entries
            if (entries == null) return Gathered.Failed((unpacked as Unpacked.Failed).reason)
            val sources = entries.filterNot { isCompiledModuleName(it.name) }
            compiledModuleCount += entries.size - sources.size
            if (sources.isEmpty()) {
                emptyZips += file
                items += GatheredItem(file.displayName, emptyList(), file.bytes)
            } else {
                val names = sources.map { diskNameOf(it.name) }
                sourceNames += names
                items += GatheredItem(file.displayName, names, file.bytes)
            }
        }
        return Gathered.Ok(sourceNames, archiveZips, emptyZips, compiledModuleCount, items)
    }

    /** 只把源 / 已编译条目字节读进堆；坏 zip / 闸门拒绝 ⇒ [Unpacked.Failed]（不抛）。 */
    private fun readPreviewEntries(file: PickedModFile, copy: ModImportCopy): Unpacked {
        val read = runCatching {
            ModZip.entriesWhere(file.bytes) { name ->
                ModStore.hasErbExtension(name) || ModStore.hasErhExtension(name) || isCompiledModuleName(name)
            }
        }
        return read.fold(
            onSuccess = { Unpacked.Ok(it) },
            onFailure = { error -> Unpacked.Failed(unpackReason(file, error, copy)) },
        )
    }

    /** 源包预览：代码面 + 资源面 + 媒体面（媒体目录取 `eratw-pack.json` 的 `resources`）。 */
    private fun sourcePreview(
        gathered: Gathered.Ok,
        rt2Dir: File,
        limits: Limits,
        batch: BatchPreview,
    ): Preview {
        val names = gathered.sourceNames.distinct()
        val faces = FaceContext(sourcePackageId(names), rt2Dir, limits)
        val meta = packMetaOf(gathered.archiveZips)
        val assets = assetsFace(gathered.archiveZips, faces)
        val media = mediaFace(gathered.archiveZips, meta.resources, faces)
        val code = CodeFace(
            sourceFileCount = names.size,
            erbFileCount = names.count { ModStore.hasErbExtension(it) },
            erhFileCount = names.count { ModStore.hasErhExtension(it) },
            compiledModuleCount = gathered.compiledModuleCount,
        )
        return Preview.Ready(code, assets, media, faces.warnings + batch.warnings, batch.packages)
    }

    /**
     * 纯资源包预览：身份走自述包名哈希（[ModImportController.assetPackageIdFor] 同口径），
     * 认不出可信载体元数据 ⇒ [Preview.Unavailable]（文案与控制器 ⓪ 后置的 `emptyUnpackFailure` 同源）。
     */
    private fun assetPackPreview(
        gathered: Gathered.Ok,
        rt2Dir: File,
        limits: Limits,
        copy: ModImportCopy,
        batch: BatchPreview,
    ): Preview {
        val carrier = AssetPackCarrier.of(gathered.emptyZips)
            ?: return Preview.Unavailable(AssetPackCarrier.missingReason(gathered.emptyZips, copy))
        val packageId = ModImportController.assetPackageIdFor(carrier.declaredPackageId)
        val faces = FaceContext(packageId, rt2Dir, limits)
        val assets = assetsFace(gathered.archiveZips, faces)
        val media = mediaFace(gathered.archiveZips, carrier.resources, faces)
        val code = CodeFace(0, 0, 0, gathered.compiledModuleCount)
        return Preview.Ready(code, assets, media, faces.warnings + batch.warnings, batch.packages)
    }

    // ------------------------------------------------------------------ 资源面 / 媒体面

    /** 三面共用的只读上下文：包身份、rt2 目录、限额与预警收集器。 */
    private class FaceContext(
        val packageId: String,
        val rt2Dir: File,
        val limits: Limits,
        val warnings: MutableList<PreviewWarning> = ArrayList(),
    )

    /** `.assets` 面：只数 S4 会采信的条目（合法来源目录名 + 合法相对名 + 同来源先到先得）。 */
    private fun assetsFace(archiveZips: List<ByteArray>, faces: FaceContext): AssetsFace {
        val scan = AssetPreviewScan(faces.packageId, faces.limits)
        for (zip in archiveZips) {
            val entries = ModZip.entriesWhere(zip) { name ->
                name.contains(ModImportAssets.ASSET_DIR_SUFFIX + "/")
            }
            for (entry in entries) scan.accept(entry)
        }
        faces.warnings += scan.warnings()
        return scan.face()
    }

    /** 媒体面：`null` = 没有声明 `<dir>`（S4 同口径：null/空 = 完全不动作）。 */
    private fun mediaFace(archiveZips: List<ByteArray>, dirName: String?, faces: FaceContext): MediaFace? {
        val dir = dirName?.takeIf { it.isNotEmpty() && ModImportAssets.isLegalSingleSegmentName(it) }
        if (dir == null) return null
        MediaManifestScan.conflictingPackageId(faces.rt2Dir, faces.packageId, dir)?.let { owner ->
            faces.warnings += PreviewWarning.MediaDirConflict(dir, owner)
        }
        val scan = MediaPreviewScan(dir, faces.limits.media)
        for (zip in archiveZips) {
            val entries = ModZip.entriesWhere(zip) { name -> mediaRootOf(name) != null }
            for (entry in entries) scan.accept(entry)
        }
        faces.warnings += scan.warnings()
        return scan.face()
    }
}

/**
 * [Preview.Ready] 的玩家面行（面行 + 批摘要行 + 每条预警一行）：语言档只经 [copy] 进来，
 * 本对象不判语言。排版（放哪一屏、什么色调）留在 Compose 侧。
 *
 * 拆成三个私有段是为了让「整批合并读数」与「逐包读数」互不缠绕：整批面行讲导入后会变成什么，
 * 批摘要行讲选了哪几个包，预警行只讲风险。
 */
private object PreviewLineRenderer {
    fun readyLines(preview: Preview.Ready, copy: ModImportCopy): List<String> =
        faceLines(preview, copy) + batchLines(preview, copy) +
            replacementLines(preview.packages, copy) + warningLines(preview.warnings, copy)

    /**
     * 盘上同包旧产物的替换提示行（KI-8 ①）：有旧 manifest 的包每包一条（单包 / 批量都出），
     * 月更重导前玩家先看到「装了会替换什么、旧版是什么时候导的」。
     */
    private fun replacementLines(packages: List<PackageFace>, copy: ModImportCopy): List<String> =
        packages.mapNotNull { face ->
            when {
                face.installedVersion == null -> null
                face.installedAtMillis > 0L -> copy.fill(
                    copy.previewReplaceFormat,
                    face.installedVersion,
                    formatImportTimestamp(face.installedAtMillis),
                )
                else -> copy.fill(copy.previewReplaceNoTimeFormat, face.installedVersion)
            }
        }

    /** 整批合并后的三面读数（与批摘要分开：这里描述「导入结果会是什么样」）。 */
    private fun faceLines(preview: Preview.Ready, copy: ModImportCopy): List<String> {
        val lines = ArrayList<String>()
        lines += copy.fill(
            copy.previewCodeSourcesFormat,
            preview.code.sourceFileCount,
            preview.code.erbFileCount,
            preview.code.erhFileCount,
        )
        if (preview.code.compiledModuleCount > 0) {
            lines += copy.fill(copy.previewCodeCompiledFormat, preview.code.compiledModuleCount)
        }
        val assets = preview.assets
        lines += copy.fill(copy.previewAssetsFormat, assets.entryCount, mibText(assets.totalBytes))
        val media = preview.media
        if (media == null) {
            lines += copy.previewMediaNoneLine
        } else {
            lines += copy.fill(
                copy.previewMediaFormat,
                media.dirName,
                media.resourceEntryCount,
                mibText(media.resourceBytes),
                media.soundEntryCount,
                mibText(media.soundBytes),
            )
            if (media.extensions.isNotEmpty()) {
                val summary = media.extensions.joinToString(copy.nameListSeparator) { item ->
                    copy.fill(copy.previewExtensionCountFormat, item.extension, item.count)
                }
                lines += copy.fill(copy.previewExtensionsFormat, summary)
            }
        }
        return lines
    }

    /** 「共 N 个包」与逐包摘要（单包只出一句措辞：整批面行已经把它描述清楚了）。 */
    private fun batchLines(preview: Preview.Ready, copy: ModImportCopy): List<String> {
        val packages = preview.packages
        if (packages.size < 2) {
            return if (packages.size == 1) listOf(copy.previewPackageCountSingleLine) else emptyList()
        }
        return rowLines(packages, copy)
    }

    /** 逐包摘要行：显示名 / 包身份 / 版本 / 源文件数 / 资源条目数（未知项用占位文案）。 */
    private fun rowLines(packages: List<PackageFace>, copy: ModImportCopy): List<String> {
        val lines = ArrayList<String>()
        lines += copy.fill(copy.previewPackageCountFormat, packages.size)
        for (face in packages) {
            lines += copy.fill(
                copy.previewPackageRowFormat,
                face.displayName,
                face.packageId ?: copy.previewPackageIdUnknown,
                face.version ?: copy.previewPackageVersionUnknown,
                face.sourceFileCount,
                face.assetEntryCount,
            )
        }
        return lines
    }

    /** 预警逐条一行（只提示不拦截；批内目录共用与已装包目录冲突分开点名）。 */
    private fun warningLines(warnings: List<PreviewWarning>, copy: ModImportCopy): List<String> =
        warnings.map { warning ->
            when (warning) {
                is PreviewWarning.OverLimit -> copy.fill(
                    copy.previewLimitFormat,
                    warning.subject,
                    mibText(warning.limitBytes),
                    mibText(warning.observedBytes),
                )
                is PreviewWarning.CombinedOverLimit -> copy.fill(
                    copy.previewLimitCombinedFormat,
                    mibText(warning.limitBytes),
                    mibText(warning.observedBytes),
                )
                is PreviewWarning.MediaDirConflict -> copy.fill(
                    copy.previewConflictFormat,
                    warning.mediaDirName,
                    warning.ownerPackageId,
                )
                is PreviewWarning.BatchMediaDirConflict -> copy.fill(
                    copy.previewBatchDirConflictFormat,
                    warning.firstName,
                    warning.secondName,
                    warning.mediaDirName,
                )
            }
        }
}

/** 预览读数里的 MiB 保留一位小数（与 S4 告警里的整数 MiB 不冲突：这里给读数感）。 */
private fun mibText(bytes: Long): String =
    String.format(Locale.ROOT, "%.1f", bytes.toDouble() / ModZipGuard.BYTES_PER_MIB)

/** 预览模型：可用（[Ready]）与不可用（[Unavailable]，预览失败不阻断导入）。 */
sealed interface Preview {
    /**
     * 预览成功的只读读数。
     *
     * @property packages 本批被选中的逐包摘要（空表 = 老调用点没给；单包也有一条，
     *   预览行据此决定出「单包」还是「共 N 个包 + 逐包行」）。
     */
    data class Ready(
        val code: CodeFace,
        val assets: AssetsFace,
        val media: MediaFace?,
        val warnings: List<PreviewWarning>,
        val packages: List<PackageFace> = emptyList(),
    ) : Preview

    /** 预览不可用：只说明这一屏为什么算不出来，调用方仍可继续导入。 */
    data class Unavailable(val reason: String) : Preview
}

/** 代码面：源文件数与已编译模块数（预览不编译，因此没有函数数 / 指令数）。 */
data class CodeFace(
    val sourceFileCount: Int,
    val erbFileCount: Int,
    val erhFileCount: Int,
    val compiledModuleCount: Int,
)

/** 资源面：`.assets` 会采信的条目数与解压后总字节。 */
data class AssetsFace(val entryCount: Int, val totalBytes: Long)

/**
 * 一个被选中的包的摘要（批导入预览的逐条读数）。
 *
 * @property packageId 包身份（与导入侧同口径：源包走源哈希、纯资源包走自述包名哈希）；
 *   非 zip 源文件也有一条自己的源哈希。null = 算不出来（只有认不出可信元数据的纯资源包）。
 * @property version 元数据采信的版本号；没有 zip / 元数据缺失或不可信 ⇒ null（显示为未知）。
 * @property mediaDirName 该包自己声明的旁挂资源目录（`resources`）；未声明 / 不可信 ⇒ null。
 */
data class PackageFace(
    val displayName: String,
    val packageId: String?,
    val version: String?,
    val sourceFileCount: Int,
    val assetEntryCount: Int,
    val mediaDirName: String?,

    /**
     * 盘上同包旧产物的版本读数（KI-8 ①）：`mods/rt2/<id>.manifest` 在场才非 null，
     * 预览面据此出「将替换已装旧版」一行。只读，不写盘。
     */
    val installedVersion: String? = null,

    /** 旧 manifest 的 mtime（旧版导入时间，ISO 形上屏）；读不到 = 0，替换行走无时间格式。 */
    val installedAtMillis: Long = 0L,
)

/** 媒体面：声明 `<dir>` 下两棵树的条目数 / 字节数与扩展名分组。 */
data class MediaFace(
    val dirName: String,
    val resourceEntryCount: Int,
    val resourceBytes: Long,
    val soundEntryCount: Int,
    val soundBytes: Long,
    val extensions: List<ExtensionCount>,
)

/** 一个扩展名的条目数（按条目数降序、同数按扩展名升序排）。 */
data class ExtensionCount(val extension: String, val count: Int)

/**
 * 预览预警：**只提示、不拦截**（真判据在 S4，见 [ModImportPreview] 类 KDoc 的分工）。
 * 每条都带足够点名信息，玩家能自己决定要不要继续导入。
 */
sealed interface PreviewWarning {
    /** 单个文件或单棵树累计超过 S4 上限：[subject] 是超额物（条目名 / 树路径 / 目录路径）。 */
    data class OverLimit(val subject: String, val limitBytes: Long, val observedBytes: Long) : PreviewWarning

    /** 两树合计超过 S4 的合计上限。 */
    data class CombinedOverLimit(val limitBytes: Long, val observedBytes: Long) : PreviewWarning

    /** 跨包目录冲突：`<dir>` 已被 [ownerPackageId] 声明，导入时 S4 会整包拒导。 */
    data class MediaDirConflict(val mediaDirName: String, val ownerPackageId: String) : PreviewWarning

    /**
     * 同批目录共用：[firstName] 与 [secondName] 都在本批里声明了 [mediaDirName]。
     *
     * 导入侧把整批源文件合并成**一个**包，所以这两个包会共用同一份 `.assets` / 媒体目录；
     * 只提示不拦截（玩家自己决定要不要拆开导）。
     */
    data class BatchMediaDirConflict(
        val mediaDirName: String,
        val firstName: String,
        val secondName: String,
    ) : PreviewWarning
}

/** 分流中间结果（[ModImportPreview.compute] 的中间态）：失败只带 [Preview.Unavailable] 的 reason。 */
private sealed interface Gathered {
    class Ok(
        val sourceNames: List<String>,
        val archiveZips: List<ByteArray>,
        val emptyZips: List<PickedModFile>,
        val compiledModuleCount: Int,
        val items: List<GatheredItem>,
    ) : Gathered {
        val hasSources: Boolean
            get() = sourceNames.isNotEmpty()
    }

    class Failed(val reason: String) : Gathered
}

/**
 * 一个被选中的包（逐包摘要的数据源）：zip 自带字节，非 zip 只有一个源文件。
 *
 * [sourceNames] 是该包自己的源条目主名（非 zip 就是 diskName）；为空 = 纯资源包（走自述元数据）。
 */
private class GatheredItem(
    val displayName: String,
    val sourceNames: List<String>,
    val zipBytes: ByteArray?,
)

    /** 批预览的逐包读数与批内预警（[Gathered.Ok] 的派生面，与整批合并读数分开）。 */
    private class BatchPreview(val packages: List<PackageFace>, val warnings: List<PreviewWarning>)

/**
 * 逐包摘要的只读扫描。
 *
 * 与整批面行的分工：整批面行说「导入后会变成一个什么样的包」，这里说「选中的每个包各是什么」。
 * 身份口径与整批同源（源包走源哈希、纯资源包走自述包名哈希），不另发明一套。
 */
private object BatchPreviewScan {
    fun of(gathered: Gathered.Ok, limits: ModImportPreview.Limits, rt2Dir: File): BatchPreview {
        val packages = gathered.items.map { face(it, limits, rt2Dir) }
        return BatchPreview(packages, dirConflicts(packages))
    }

    /**
     * 一个包的摘要：版本 / 目录只取自它自己 zip 里可信的 `eratw-pack.json`；
     * 盘上同包旧产物读数（KI-8）：packageId 算得出来时读 `mods/rt2/<id>.manifest` 的版本与
     * 文件 mtime（旧版导入时间），月更重导前玩家先看到「装了会替换什么」。
     */
    private fun face(item: GatheredItem, limits: ModImportPreview.Limits, rt2Dir: File): PackageFace {
        val zip = item.zipBytes
        if (zip == null) {
            return installedFace(
                base = PackageFace(
                    displayName = item.displayName,
                    packageId = ModImportPreview.sourcePackageId(listOf(item.displayName)),
                    version = null,
                    sourceFileCount = 1,
                    assetEntryCount = 0,
                    mediaDirName = null,
                ),
                rt2Dir = rt2Dir,
            )
        }
        val meta = packMetaOf(listOf(zip))
        val packageId = if (item.sourceNames.isNotEmpty()) {
            ModImportPreview.sourcePackageId(item.sourceNames)
        } else {
            meta.packageId?.takeIf { meta.trusted }?.let { ModImportController.assetPackageIdFor(it) }
        }
        return installedFace(
            base = PackageFace(
                displayName = item.displayName,
                packageId = packageId,
                version = meta.declaredVersion?.takeIf { meta.trusted },
                sourceFileCount = item.sourceNames.distinct().size,
                assetEntryCount = assetEntryCount(zip, packageId, limits),
                mediaDirName = meta.resources,
            ),
            rt2Dir = rt2Dir,
        )
    }

    /**
     * 盘上同包旧产物读数（KI-8）：manifest 在场才填 [PackageFace.installedVersion] /
     * [PackageFace.installedAtMillis]（mtime 读不到 = 0，替换行走无时间格式）。只读，不写盘。
     */
    private fun installedFace(base: PackageFace, rt2Dir: File): PackageFace =
        when (val id = base.packageId) {
            null -> base
            else -> {
                val manifest = File(rt2Dir, id + ModPackageInventory.MANIFEST_EXTENSION)
                val installed = manifest.takeIf { it.isFile }?.let { installedManifestOf(rt2Dir, id) }
                if (installed == null) {
                    base
                } else {
                    base.copy(
                        installedVersion = installed.version,
                        installedAtMillis = manifest.lastModified(),
                    )
                }
            }
        }

    /** 该 zip 里 S4 会采信的资源条目数（判据复用 [AssetPreviewScan] 的 accept，不另写一套）。 */
    private fun assetEntryCount(zip: ByteArray, packageId: String?, limits: ModImportPreview.Limits): Int {
        val scan = AssetPreviewScan(packageId ?: "", limits)
        val entries = ModZip.entriesWhere(zip) { name ->
            name.contains(ModImportAssets.ASSET_DIR_SUFFIX + "/")
        }
        for (entry in entries) scan.accept(entry)
        return scan.face().entryCount
    }

    /**
     * 同批里两个包声明同一 `<dir>`：导入侧会把它们合并成一个包并共用该目录。
     *
     * 同一文件被重复选中时按两次列出摘要（不去重，玩家能看见自己点了两次），但两条摘要是同一个包身份，
     * 导入侧也只是同一份内容，故不参与互相点名。
     */
    private fun dirConflicts(packages: List<PackageFace>): List<PreviewWarning> {
        val out = ArrayList<PreviewWarning>()
        for (first in packages.indices) {
            val dir = packages[first].mediaDirName ?: continue
            val firstId = packages[first].packageId
            for (second in first + 1 until packages.size) {
                val other = packages[second]
                if (other.mediaDirName == dir && !sameIdentity(firstId, other.packageId)) {
                    out += PreviewWarning.BatchMediaDirConflict(dir, packages[first].displayName, other.displayName)
                }
            }
        }
        return out
    }

    /** 同一包身份（同文件重复选中）：同批里不当作「两个包共用目录」。 */
    private fun sameIdentity(firstId: String?, secondId: String?): Boolean =
        firstId != null && secondId == firstId
}

/** 一个 zip 的源条目读取结果（坏 zip / 闸门拒绝都不抛，折成 reason）。 */
private sealed interface Unpacked {
    class Ok(val entries: List<ModZip.Entry>) : Unpacked

    class Failed(val reason: String) : Unpacked
}

/** `.assets` 扫描累加器：只读条目字节大小，不写盘。 */
private class AssetPreviewScan(private val packageId: String, private val limits: ModImportPreview.Limits) {
    private val marker = ModImportAssets.ASSET_DIR_SUFFIX + "/"
    private val prefixes = HashMap<String, String>()
    private var entryCount = 0
    private var totalBytes = 0L
    private var overLimitName: String? = null
    private var overLimitBytes = 0L

    val assetDirPath: String
        get() = PACKAGE_DIR_PREFIX + packageId + ModImportAssets.ASSET_DIR_SUFFIX

    fun accept(entry: ModZip.Entry) {
        val markerAt = entry.name.indexOf(marker)
        val sourcePrefix = if (markerAt > 0) entry.name.substring(0, markerAt) else ""
        val relative = entry.name.substring(markerAt + marker.length)
        val accepted = ModImportAssets.isLegalSingleSegmentName(sourcePrefix) &&
            ModImportAssets.isValidResourceName(relative) &&
            prefixes.putIfAbsent(relative, sourcePrefix) == null
        if (!accepted) return
        val size = entry.bytes.size.toLong()
        entryCount += 1
        totalBytes += size
        if (size > limits.assetFileBytes && overLimitName == null) {
            overLimitName = entry.name
            overLimitBytes = size
        }
    }

    fun warnings(): List<PreviewWarning> {
        val out = ArrayList<PreviewWarning>()
        overLimitName?.let { out += PreviewWarning.OverLimit(it, limits.assetFileBytes, overLimitBytes) }
        if (totalBytes > limits.assetTotalBytes) {
            out += PreviewWarning.OverLimit(assetDirPath, limits.assetTotalBytes, totalBytes)
        }
        return out
    }

    fun face(): AssetsFace = AssetsFace(entryCount, totalBytes)
}

/** 媒体面扫描累加器（两棵树各一份 [MediaUsage]；只读条目字节大小，不写盘）。 */
private class MediaPreviewScan(
    private val dirName: String,
    private val limits: ModImportMediaAssets.MediaLimits,
) {
    private val resource = MediaUsage(limits.maxResourceFileBytes)
    private val sound = MediaUsage(limits.maxSoundFileBytes)
    private val seen = HashSet<String>()
    private val extensionCounts = LinkedHashMap<String, Int>()

    fun accept(entry: ModZip.Entry) {
        val root = mediaRootOf(entry.name)
        val relative = root?.let { relativeNameUnder(entry.name, it) }
        if (root == null || relative == null) return
        if (!ModImportAssets.isValidResourceName(relative) || !seen.add(entry.name)) return
        val extension = extensionOf(relative)
        if (extension in allowedExtensionsOf(root)) {
            extensionCounts[extension] = (extensionCounts[extension] ?: 0) + 1
            val usage = if (root == ModImportMediaAssets.RESOURCE_ROOT) resource else sound
            usage.add(entry.name, entry.bytes.size.toLong())
        }
    }

    /** `<root><dir>/<relative>` 的 `<relative>`；第二段与声明 `<dir>` 不同 ⇒ null（S4 同口径）。 */
    private fun relativeNameUnder(name: String, root: String): String? {
        val rest = name.substring(root.length)
        val slash = rest.indexOf('/')
        val dir = if (slash > 0) rest.substring(0, slash) else ""
        return if (dir == dirName) rest.substring(slash + 1) else null
    }

    fun warnings(): List<PreviewWarning> {
        val out = ArrayList<PreviewWarning>()
        resource.overLimitName?.let {
            out += PreviewWarning.OverLimit(it, limits.maxResourceFileBytes, resource.overLimitBytes)
        }
        if (resource.totalBytes > limits.maxResourceTotalBytes) {
            out += PreviewWarning.OverLimit(
                ModImportMediaAssets.RESOURCE_ROOT + dirName,
                limits.maxResourceTotalBytes,
                resource.totalBytes,
            )
        }
        sound.overLimitName?.let {
            out += PreviewWarning.OverLimit(it, limits.maxSoundFileBytes, sound.overLimitBytes)
        }
        if (sound.totalBytes > limits.maxSoundTotalBytes) {
            out += PreviewWarning.OverLimit(
                ModImportMediaAssets.SOUND_ROOT + dirName,
                limits.maxSoundTotalBytes,
                sound.totalBytes,
            )
        }
        val combined = resource.totalBytes + sound.totalBytes
        if (combined > limits.maxCombinedBytes) {
            out += PreviewWarning.CombinedOverLimit(limits.maxCombinedBytes, combined)
        }
        return out
    }

    fun face(): MediaFace = MediaFace(
        dirName,
        resource.entryCount,
        resource.totalBytes,
        sound.entryCount,
        sound.totalBytes,
        sortedExtensions(),
    )

    private fun sortedExtensions(): List<ExtensionCount> =
        extensionCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { ExtensionCount(it.key, it.value) }
}

/** 单棵树的累加状态：条目数、总字节、首个超单文件上限的条目（只报一个，避免刷屏）。 */
private class MediaUsage(private val maxFileBytes: Long) {
    var entryCount = 0
        private set
    var totalBytes = 0L
        private set
    var overLimitName: String? = null
        private set
    var overLimitBytes = 0L
        private set

    fun add(name: String, size: Long) {
        entryCount += 1
        totalBytes += size
        if (size > maxFileBytes && overLimitName == null) {
            overLimitName = name
            overLimitBytes = size
        }
    }
}

/** 纯资源包的可信载体元数据（自述包名 + 媒体目录）；与控制器 `isTrustedCarrierMeta` 同判据。 */
private data class AssetCarrier(val declaredPackageId: String, val resources: String?)

/** 纯资源包载体认领：按选择顺序取第一个可信元数据（与控制器 `importWithoutSources` 同序）。 */
private object AssetPackCarrier {
    fun of(emptyZips: List<PickedModFile>): AssetCarrier? {
        for (file in emptyZips) {
            val metaEntry = ModZip.fixedEntryBytes(file.bytes, PACK_META_ENTRY_NAME, PACK_META_MAX_ENTRY_BYTES)
            val meta = parsePackMeta(metaEntry)
            val declared = meta.packageId
            if (declared != null && meta.ignored != PackMetaIgnore.PACKAGE_ID_PREFIX) {
                return AssetCarrier(declared, meta.resources)
            }
        }
        return null
    }

    /** 一个可信载体都没有时的 reason（与控制器 `emptyUnpackFailure` 同一文案分流）。 */
    fun missingReason(emptyZips: List<PickedModFile>, copy: ModImportCopy): String {
        val first = emptyZips.firstOrNull() ?: return copy.noFilesPicked
        return if (ModZip.looksLikeAssetPack(first.bytes)) {
            copy.fill(copy.zipLooksLikeAssetPackFormat, first.displayName)
        } else {
            copy.fill(copy.zipNoEntriesFormat, first.displayName)
        }
    }
}

/** `rt2` 下其它 `*.media` 清单的只读扫描（[ModImportMediaAssets] 私有实现的同口径复刻，不写盘）。 */
private object MediaManifestScan {
    fun conflictingPackageId(rt2Dir: File, packageId: String, dirName: String): String? {
        val ownName = packageId + ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX
        return rt2Dir.listFiles().orEmpty()
            .filter {
                it.isFile &&
                    it.name != ownName &&
                    it.name.endsWith(ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX)
            }
            .sortedBy { it.name }
            .firstOrNull { declaredMediaDirOf(it) == dirName }
            ?.name
            ?.removeSuffix(ModImportMediaAssets.MEDIA_MANIFEST_SUFFIX)
    }

    private fun declaredMediaDirOf(manifestFile: File): String? {
        val prefix = ModImportMediaAssets.MANIFEST_VERSION + " " + ModImportMediaAssets.MANIFEST_DIR_KEY
        val firstLine = runCatching { manifestFile.readLines().firstOrNull() }.getOrNull()
        return firstLine?.takeIf { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.takeIf { ModImportAssets.isLegalSingleSegmentName(it) }
    }
}

/** `.assets` 目录在 gameRoot 下的前缀（`mods/rt2/<packageId>.assets`）。 */
private const val PACKAGE_DIR_PREFIX: String = "mods/rt2/"

/** 包身份哈希的前缀字节数与十六进制笔画（与 [ModImportController] 的冻结口径同值）。 */
private const val PACKAGE_ID_BYTES: Int = 4
private const val PACKAGE_ID_HEX: String = "0123456789abcdef"
private const val BYTE_MASK: Int = 0xFF
private const val NIBBLE_MASK: Int = 0x0F
private const val NIBBLE_BITS: Int = 4

/** 图/文白名单（S4 同源：ADR-014 D6 只 `.txt`，CSV 是引擎装载面）。 */
private val RESOURCE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp", "txt")

/** 音频白名单（S4 同源：ADR-014 D6 一期只这三档）。 */
private val SOUND_EXTENSIONS: Set<String> = setOf("mp3", "ogg", "wav")

/** 条目名所属的媒体树根；两棵树都不是 ⇒ null（S4 静默忽略的同一判据）。 */
private fun mediaRootOf(name: String): String? = when {
    name.startsWith(ModImportMediaAssets.RESOURCE_ROOT) -> ModImportMediaAssets.RESOURCE_ROOT
    name.startsWith(ModImportMediaAssets.SOUND_ROOT) -> ModImportMediaAssets.SOUND_ROOT
    else -> null
}

/** 某棵树的扩展名白名单（S4 同源；调用方已保证 [root] 是两棵树之一）。 */
private fun allowedExtensionsOf(root: String): Set<String> =
    if (root == ModImportMediaAssets.RESOURCE_ROOT) RESOURCE_EXTENSIONS else SOUND_EXTENSIONS

/** 扩展名小写化（S4 同口径：大小写不敏感）。 */
private fun extensionOf(relativeName: String): String =
    relativeName.substringAfterLast('.', "").lowercase(Locale.ROOT)

/** zip 条目落盘名（`ModImportController` 的 `PickedModFile.diskName` 对 zip 条目的同口径）。 */
private fun diskNameOf(entryName: String): String = entryName.substringAfterLast('/')

/** 已编译补丁模块（只看名字，不解析内容：预览不装载）。 */
private fun isCompiledModuleName(name: String): Boolean =
    name.endsWith(ModPackageInventory.MODULE_EXTENSION, ignoreCase = true)

/** 预览失败的 reason：闸门拒绝给玩家面定位文案，其余给「解包失败 + 原话」。 */
private fun unpackReason(file: PickedModFile, error: Throwable, copy: ModImportCopy): String =
    when (error) {
        is ModZipGuard.Rejected -> ModZipGuard.rejectMessage(copy, file.displayName, error)
        else -> copy.fill(copy.zipUnpackFailedFormat, file.displayName, detailText(error))
    }

/** 异常原话（与控制器 `detailText` 同口径：没有 message 也不许抛二次异常）。 */
private fun detailText(error: Throwable): String =
    error.message ?: error::class.simpleName ?: "null"

/** sha256 前 [PACKAGE_ID_BYTES] 字节按高位先出转十六进制（与 [ModImportController.packageIdFor] 逐位同形）。 */
private fun digestPrefixHex(digest: ByteArray): String {
    val out = StringBuilder()
    for (index in 0 until PACKAGE_ID_BYTES) {
        val value = digest[index].toInt() and BYTE_MASK
        out.append(PACKAGE_ID_HEX[(value shr NIBBLE_BITS) and NIBBLE_MASK])
        out.append(PACKAGE_ID_HEX[value and NIBBLE_MASK])
    }
    return out.toString()
}
