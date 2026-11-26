package com.eratw.port.android

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 导入包（口上 zip）**携带的 mod 资源面**的校验与原子落盘（Lane BH）。
 *
 * ## 合同（与运行期读取侧 `core-vm` 的 `ModResourceStore` 同源）
 *
 * 1. **前缀**：认 zip 条目名里 `"<来源目录名>.assets/"` 这一形态（[ASSET_DIR_SUFFIX]）。
 *    `<来源目录名>` 是模块包作者在源码里写的 `PACKAGE_ID`（`modgen pack-mod` 按它放资源），
 *    **不等于**本端算出的 packageId：资源相对路径（`a/b.png`）原样作资源名，落点一律重挂到
 *    本包 `mods/rt2/<packageId>.assets/`。`packageId` 取本次**编译产物**的包标识
 *    （`built.packageId` / manifest 里的值），绝不从文件名或 zip 条目名反推。来源目录名必须
 *    是合法名字段（与 [isValidResourceName] 同一套单段判定：非空、无反斜杠、不含 `/`、
 *    可打印、非 `:`），`..` / 绝对路径 / 控制字符一律跳过 + WARN —— 重挂不放松名字校验。
 *    **同一相对路径来自两个不同来源目录名 ⇒ 抛 [AssetConflictException] 整包拒导**（后写盖
 *    先写会让设备上的图取决于 zip 条目顺序）；同一来源内重名仍只跳过 + WARN。发生重挂时
 *    汇总一条 WARN 点名来源目录名。
 * 2. **名字**：逐条按 [isValidResourceName] 校验（与 `ModResourceStore` 的私有校验逐条同形：
 *    非空、无反斜杠、不以 `/` 开头/结尾、无空段、无 `.`/`..` 段、字符 >= 0x20 且 != 0x7F 且
 *    不是 `:`）。非法名的条目**只跳过该条 + WARN**，不让整包失败——作者塞了一两个奇怪
 *    文件名不该把整份口上废掉。
 * 3. **限额**：单文件 > [MAX_RESOURCE_FILE_BYTES] 或累计 > [MAX_RESOURCE_TOTAL_BYTES]
 *    ⇒ **整面资源不导入**（不落任何半套 + WARN 说明原因）。比运行期（单文件超限只丢那一个
 *    文件）更严：导入期是作者唯一能看见问题的地方，静默丢文件会让 mod 运行期莫名少图；
 *    整面拒绝至少让作者看到告警并把包改小。两处上限常量同值由哨兵测试兜底
 *    （[ModImportAssetsTest] 读 `ModResourceStore.kt` 源码文本比对，先例见
 *    `tools/modgen` 的 `ModCheckResourcesTest`）。
 * 4. **原子**：先写到同目录临时目录 `<packageId>.assets.tmp-<nano>`，全部写成功后才整体
 *    swap 到 `mods/rt2/<packageId>.assets/`；任何失败清掉临时目录，盘上不留半套。
 * 5. **重复导入 = 包级替换**：同集合重导产出同 packageId，新资源面**整目录替换**旧面
 *    （旧面里本次不再提供的文件必须消失——否则作者删掉/改名的图会永远赖在设备上，
 *    而导入报告说「已更新」）。替换用「旧面先改名备份 → 新面就位 → 删备份」，两步
 *    rename 之间失败会尽力恢复旧面。
 *
 * ## 为什么不做「纯资源包」（无 `.ERB`/`.ERH`）
 *
 * 运行期 `ModResourceApi` 按 packageId 隔离：没有 `.erbm` 就没有任何 mod 能读到这份资源，
 * 纯资源包是死物。成品包导入（zip 自带 `<pkg>.manifest` + `.erbm`）是另一份导入合同
 * （是否信任包内 manifest、是否绕过编译期校验），另行立项。本对象因此只服务「源包顺带
 * 资源」这一条链，由 [ModImportController.import] 在编译成功之后调用。
 *
 * ## 告警面
 *
 * 校验类告警（非法名 / 同来源重名 / 超限 / 来源前缀重挂）按调用方传入的 [ModImportCopy] 语言档拼成
 * **正式玩家文案**（键 `resourceXxxFormat`），经 [Plan.warnings] 由
 * [ModImportController.ImportedPatchDetail.resourceWarnings] 结构化带出，并照旧合并进
 * [ModImportController.ImportedPatchDetail.parseWarnings] 供成功页逐行展示。本对象不做语言
 * 判定（同 `ModImportCopy` 硬规矩 1：语言只经 `SettingsLanguage` 由壳层取好传进来）。
 */
/** 名字首字符下界：可打印 ASCII（与 `ModResourceStore` 的 FIRST_PRINTABLE_CHAR_CODE 同源）。 */
private const val FIRST_PRINTABLE_CHAR_CODE = 0x20

/** DEL 控制字符（与 `ModResourceStore` 的 DELETE_CHAR_CODE 同源）。 */
private const val DELETE_CHAR_CODE = 0x7F

/** 单字符合法：可打印（>= 0x20）、非 DEL、非 `:`（Windows/SAF 上的路径语义字符）。 */
private fun isAllowedNameChar(ch: Char): Boolean =
    ch.code >= FIRST_PRINTABLE_CHAR_CODE && ch.code != DELETE_CHAR_CODE && ch != ':'

/**
 * 单段名字合法：非空、不是 `.`/`..`、字符全部可接受（[isAllowedNameChar]）。
 *
 * 放文件级私有而非对象成员：对象函数数已贴 detekt `TooManyFunctions` 阈值，而
 * [ModImportAssets.isValidResourceName] 与 [ModImportAssets.isLegalSingleSegmentName] 两条
 * 单段/多段判据都要它（单字符闸 [isAllowedNameChar] 同因一并放文件级）。语义未变。
 */
private fun isAllowedSegment(segment: String): Boolean =
    if (segment.isEmpty() || segment == "." || segment == "..") {
        false
    } else {
        segment.all { isAllowedNameChar(it) }
    }

internal object ModImportAssets {

    /** 资源目录后缀：与运行期 `ModResourceStore.ASSET_DIR_SUFFIX` 同值（哨兵测试兜底）。 */
    const val ASSET_DIR_SUFFIX: String = ".assets"

    /** 单文件字节上限：与运行期 `ModResourceStore.MAX_RESOURCE_FILE_BYTES` 同值。 */
    const val MAX_RESOURCE_FILE_BYTES: Long = 4L * 1024L * 1024L

    /** 单包资源字节上限：与运行期 `ModResourceStore.MAX_RESOURCE_TOTAL_BYTES` 同值。 */
    const val MAX_RESOURCE_TOTAL_BYTES: Long = 16L * 1024L * 1024L

    /** 资源名段分隔符（zip 条目名与资源名都只用正斜杠）。 */
    private const val SEGMENT_SEPARATOR = '/'

    /**
     * 临时目录后缀（落盘期使用；正常收尾后不残留）。
     *
     * `internal` 而非 `private`：[ModPackageInventory] 的残留 GC 必须按同一后缀识别
     * `<packageId>.assets.tmp-<nano>`，两年后改一处忘一处就会让 GC 认不出残留（同源常量
     * 比字符串复制到两个文件更可靠）。
     */
    internal const val TEMP_SUFFIX = ".tmp-"

    /**
     * 旧资源面备份目录后缀（替换期使用；正常收尾后不残留）。
     *
     * `internal` 理由同 [TEMP_SUFFIX]。
     */
    internal const val BACKUP_SUFFIX = ".bak-"

    /**
     * WARN 里点名「来源前缀被重挂」的样例条数上限（避免几百条条目刷屏）。
     */
    private const val REBASE_SAMPLE_LIMIT = 3

    /**
     * 一次资源面导入的判定结果。
     *
     * [staged] 为 null 表示「本次没有可落盘的资源面」：没有 `.assets` 条目、全部被跳过、
     * 或限额整面拒绝。此时 [entryCount] 恒 0；跳过/拒绝原因在 [warnings]。
     */
    class Plan(
        /** 已校验并写进临时目录的资源面（尚未 swap 到正式名）；null = 无可落盘内容。 */
        val staged: Staged?,
        /** 已校验通过的资源条目数（提交成功后与盘上文件数一致）。 */
        val entryCount: Int,
        /**
         * 跳过 / 拒绝 / 来源前缀重挂的告警（**玩家可见文案**，已按 [ModImportCopy] 的
         * 调用方语言拼好；取证标记 `[mod.resources]` 不再混进玩家面）。
         */
        val warnings: List<String>,
    )

    /**
     * 重挂到本包后出现**跨来源同名**资源（不同 `<来源目录名>.assets/` 指向同一相对路径）。
     *
     * 抛给调用方 ⇒ 该包整体拒导（[ModImportController] 折成 STORAGE 失败上屏并点名冲突条目）。
     * 与「同来源重名只跳过 + WARN」不同：不同来源指向同一落点是作者把两份东西挂到同一位置，
     * 后写盖先写会让玩家设备上的资源随机取决于 zip 条目顺序，所以拒导而不是静默取舍。
     * 本异常在扫描相位抛，此时尚未建临时目录，盘上无副作用。
     */
    class AssetConflictException internal constructor(message: String) : Exception(message)

    /**
     * 一个已写进临时目录、等待 swap 的资源面。
     *
     * [commit] 与 [discard] 二选一收尾：[commit] 成功后 [discard] 变成空操作；[commit] 抛错
     * 时调用方必须 [discard] 清临时目录（本类不假定调用方一定会清）。
     */
    class Staged internal constructor(
        private val dir: File,
        private val assetDirName: String,
        /** 已校验通过的资源条目数。 */
        val entryCount: Int,
    ) {

        private var committed = false

        /** 把临时目录整体 swap 到 `rt2Dir/<packageId>.assets/`；重复导入即整目录替换。 */
        fun commit(rt2Dir: File) {
            check(!committed) { "资源面已提交，不可重复提交：$assetDirName" }
            val target = File(rt2Dir, assetDirName)
            if (!target.exists()) {
                renameOrThrow(dir, target, "无法落盘资源目录：")
                committed = true
                return
            }
            val backup = File(rt2Dir, assetDirName + BACKUP_SUFFIX + System.nanoTime())
            renameOrThrow(target, backup, "无法移开既有资源目录：")
            if (!dir.renameTo(target)) {
                // 尽力恢复旧面：新面没就位时旧面必须还在，宁可是旧资源也不能是「全无」。
                backup.renameTo(target)
                throw IOException("无法替换资源目录：${target.absolutePath}")
            }
            committed = true
            backup.deleteRecursively()
        }

        /** rename 失败即抛：两个 swap 步骤共用同一条失败口径。 */
        private fun renameOrThrow(from: File, to: File, message: String) {
            if (!from.renameTo(to)) throw IOException(message + to.absolutePath)
        }

        /** 丢弃临时目录（未提交时）；已提交后为空操作。 */
        fun discard() {
            if (committed) return
            dir.deleteRecursively()
        }
    }

    /** 一条通过全部校验、待写入临时目录的资源条目（[relativeName] 已去掉 packageId 前缀）。 */
    private class ResourceEntry(val relativeName: String, val zipName: String, val bytes: ByteArray)

    /** 结算用的可变账本（拆出来避免 [stage] 里嵌套过深）；[copy] = 本次导入的玩家面语言档。 */
    private class Ledger(val copy: ModImportCopy) {
        val warnings = ArrayList<String>()
        val accepted = ArrayList<ResourceEntry>()

        /** 相对资源名 → 来源目录名（判同来源重名 vs 跨来源冲突，也保证后写不覆盖先写）。 */
        val seenSources = HashMap<String, String>()

        /** 被重挂的来源目录名样例（去重后按首次出现顺序，最多 [REBASE_SAMPLE_LIMIT] 个）。 */
        val rebasedSamples = LinkedHashSet<String>()
        var rebasedCount = 0
        var totalBytes = 0L
        var rejected = false
    }

    /**
     * 从导入的 zip 字节里挑出 `"<来源目录名>.assets/"` 条目，校验后重挂到本包并写进临时目录。
     *
     * [archiveZips] 是本次导入选中的所有 zip 的原始字节（多个 zip 的资源面按选择顺序合并，
     * 同来源重名条目后者跳过并 WARN —— 与源文件的「同名取先」口径一致）。[rt2Dir] 是
     * `mods/rt2`（临时目录也建在这里，保证与正式名同卷、rename 原子）。[copy] 是玩家面
     * 语言档：跳过/重挂告警按它拼成正式文案（本对象不做语言判定，同 `ModImportCopy` 硬规矩 1）。
     *
     * 失败面：校验类问题（非法来源目录名 / 非法资源名 / 超限）不抛，进 [Plan.warnings]；
     * 跨来源同名冲突抛 [AssetConflictException]（整包拒导）；IO 失败抛 [IOException]
     * （临时目录已清）。调用方把 IO 失败折成一条 WARN 并放弃本次资源面。
     */
    fun stage(archiveZips: List<ByteArray>, packageId: String, rt2Dir: File, copy: ModImportCopy): Plan {
        val ledger = Ledger(copy)
        for (zipBytes in archiveZips) {
            collectFromZip(zipBytes, packageId, ledger)
            if (ledger.rejected) break
        }
        addRebaseWarning(ledger)
        val accepted = if (ledger.rejected) emptyList() else ledger.accepted
        if (accepted.isEmpty()) {
            return Plan(staged = null, entryCount = 0, warnings = ledger.warnings)
        }
        val assetDirName = packageId + ASSET_DIR_SUFFIX
        val dir = File(rt2Dir, assetDirName + TEMP_SUFFIX + System.nanoTime())
        if (!dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建资源临时目录：${dir.absolutePath}")
        }
        try {
            for (entry in accepted) writeEntry(dir, entry)
        } catch (e: IOException) {
            dir.deleteRecursively()
            throw e
        }
        return Plan(
            staged = Staged(dir, assetDirName, accepted.size),
            entryCount = accepted.size,
            warnings = ledger.warnings,
        )
    }

    /**
     * [stage] 的「折成告警」版本：资源侧 IO 失败不该让整份口上导入失败（源与产物已按前序
     * 相位落定，资源面是附加面）。[stage] 保证抛错时临时目录已清，这里只负责上报警告。
     *
     * 跨来源同名冲突（[AssetConflictException]）**不**折成告警：它必须让整包拒导（见 [stage]），
     * 原样抛给调用方接住。
     */
    fun stageSafely(archiveZips: List<ByteArray>, packageId: String, rt2Dir: File, copy: ModImportCopy): Plan =
        try {
            stage(archiveZips, packageId, rt2Dir, copy)
        } catch (e: IOException) {
            Plan(
                staged = null,
                entryCount = 0,
                warnings = listOf(
                    copy.fill(copy.resourceStageFailedFormat, e.message ?: e::class.simpleName ?: "null"),
                ),
            )
        }

    /**
     * 资源名合法性：与运行期 `ModResourceStore` 的私有 `isValidResourceName` 逐条同形
     * （哨兵测试比对源码文本）。名字是**前缀之后的相对路径**，不是 zip 里全名。
     */
    fun isValidResourceName(name: String): Boolean =
        name.isNotEmpty() &&
            !name.contains('\\') &&
            !name.startsWith(SEGMENT_SEPARATOR) &&
            !name.endsWith(SEGMENT_SEPARATOR) &&
            name.split(SEGMENT_SEPARATOR).all { isAllowedSegment(it) }

    /**
     * 单段目录名合法性：**导入资源面的来源目录名与 `eratw-pack.json` 的 `resources` 字段共用
     * 这一条判据**（ADR-014 D4：目录规则即安全判据；禁止两处各复制一份字符规则）。
     *
     * 口径 = 非空、不是 `.` / `..`、不含 `/` 或 `\`、字符全部为 [isAllowedNameChar]。
     * 只回答「这一个名字能不能当单段目录名」；与具体落点相关的附加规则（如旁挂资源名的
     * 64 字符上限、整名禁 `..`）由调用方按各自语义另加，不改本判据。
     */
    internal fun isLegalSingleSegmentName(name: String): Boolean =
        name.isNotEmpty() &&
            !name.contains('\\') &&
            !name.contains(SEGMENT_SEPARATOR) &&
            isAllowedSegment(name)

    /**
     * 扫一个 zip 的 `.assets` 候选条目（只读条目名含 `.assets/` 的，不读其它条目字节）。
     * 条目名以 `/`/`\\` 结尾（目录项）由 [ModZip.entriesWhere] 统一丢掉。
     */
    private fun collectFromZip(zipBytes: ByteArray, packageId: String, ledger: Ledger) {
        val marker = ASSET_DIR_SUFFIX + SEGMENT_SEPARATOR
        val entries = ModZip.entriesWhere(zipBytes) { it.contains(marker) }
        for (entry in entries) {
            // 条目名必须形如 `<来源目录名>.assets/<相对路径>`：标记出现在开头（来源目录名为空）
            // 或前面带路径分隔（嵌套目录）都不是可重挂的形态，按名字非法跳过 + WARN。
            // 来源目录名按单段名字校验（与 isValidResourceName 同一套字符/段判定，只是限定单段）：
            // `..`、绝对路径、控制字符、带 `:` 的盘符一律不合法 —— 重挂只改落点前缀，绝不放宽
            // 「条目名能不能当路径」的校验。
            val markerAt = entry.name.indexOf(marker)
            val sourcePrefix = if (markerAt > 0) entry.name.substring(0, markerAt) else ""
            // 单段目录名判据与 `eratw-pack.json` 的 resources 字段共用（见 isLegalSingleSegmentName）。
            if (!isLegalSingleSegmentName(sourcePrefix)) {
                ledger.warnings += ledger.copy.fill(ledger.copy.resourceEntryInvalidNameFormat, entry.name)
                continue
            }
            if (sourcePrefix != packageId) {
                ledger.rebasedCount++
                if (ledger.rebasedSamples.size < REBASE_SAMPLE_LIMIT) ledger.rebasedSamples += sourcePrefix
            }
            if (!accept(entry, entry.name.substring(markerAt + marker.length), sourcePrefix, ledger)) return
        }
    }

    /**
     * 校验并收集一条候选资源；返回 false = 已判整面拒绝（调用方停止扫描）。
     *
     * 同名条目分两种：同一来源内重名只跳过 + WARN（既有口径）；**跨来源**同名说明重挂后
     * 会互相覆盖 ⇒ 抛 [AssetConflictException] 整包拒导，绝不后写盖先写。
     * 单文件超限与累计超限都升级为**整面拒绝**（理由见类 KDoc 第 3 条）。
     */
    private fun accept(zipEntry: ModZip.Entry, relativeName: String, sourcePrefix: String, ledger: Ledger): Boolean {
        if (!isValidResourceName(relativeName)) {
            ledger.warnings += ledger.copy.fill(ledger.copy.resourceEntryInvalidNameFormat, zipEntry.name)
            return true
        }
        val existing = ledger.seenSources[relativeName]
        return when {
            existing == null -> {
                ledger.seenSources[relativeName] = sourcePrefix
                acceptWithinLimits(zipEntry, relativeName, ledger)
            }
            existing == sourcePrefix -> {
                ledger.warnings += ledger.copy.fill(ledger.copy.resourceEntryDuplicateFormat, zipEntry.name)
                true
            }
            else -> throw AssetConflictException(
                ledger.copy.fill(
                    ledger.copy.resourcePrefixConflictFormat,
                    relativeName,
                    listOf(existing, sourcePrefix).joinToString(ledger.copy.nameListSeparator),
                ),
            )
        }
    }

    /** 限额判定：超单文件或累计超上限都升级为整面拒绝（理由见类 KDoc 第 3 条）。 */
    private fun acceptWithinLimits(zipEntry: ModZip.Entry, relativeName: String, ledger: Ledger): Boolean {
        val size = zipEntry.bytes.size.toLong()
        ledger.totalBytes += size
        val overFile = size > MAX_RESOURCE_FILE_BYTES
        val overTotal = !overFile && ledger.totalBytes > MAX_RESOURCE_TOTAL_BYTES
        if (overFile || overTotal) {
            ledger.warnings += if (overFile) {
                ledger.copy.fill(
                    ledger.copy.resourceFileTooLargeFormat,
                    MAX_RESOURCE_FILE_BYTES / BYTES_PER_MIB,
                    zipEntry.name,
                    size,
                )
            } else {
                ledger.copy.fill(
                    ledger.copy.resourceTotalTooLargeFormat,
                    MAX_RESOURCE_TOTAL_BYTES / BYTES_PER_MIB,
                    ledger.totalBytes,
                )
            }
            ledger.rejected = true
            return false
        }
        ledger.accepted += ResourceEntry(relativeName, zipEntry.name, zipEntry.bytes)
        return true
    }

    /** 有被重挂的资源条目 ⇒ 汇总一条 WARN（点名来源目录名样例，不逐条刷屏）。 */
    private fun addRebaseWarning(ledger: Ledger) {
        if (ledger.rebasedCount == 0) return
        ledger.warnings += ledger.copy.fill(
            ledger.copy.resourcePrefixRebasedFormat,
            ledger.rebasedCount,
            ledger.rebasedSamples.joinToString(ledger.copy.nameListSeparator),
        )
    }

    /**
     * 写一条资源到临时目录（[root] 内）：建父目录 + 写字节 + fsync。落点必须仍落在
     * [root] 之下（名字校验已挡住 `..`，这里按 canonicalPath 再挡一层，防校验器未来被改松）。
     */
    private fun writeEntry(root: File, entry: ResourceEntry) {
        val target = File(root, entry.relativeName)
        val rootPath = root.canonicalPath + File.separator
        val parent = target.parentFile
        if (parent == null || !target.canonicalPath.startsWith(rootPath)) {
            throw IOException("资源条目路径不可解析或逃出临时目录：${entry.zipName}")
        }
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建资源子目录：${parent.absolutePath}")
        }
        FileOutputStream(target).use { out ->
            out.write(entry.bytes)
            out.flush()
            out.fd.sync()
        }
    }

    /** 1 MiB 的字节数（WARN 文案里把常量换算成人读单位）。 */
    private const val BYTES_PER_MIB = 1024L * 1024L
}
