package com.eratw.port.android

import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * 口上 zip 的**不可信输入闸**：条目数 / 单条目解压体积 / 整包解压体积 / 压缩比 / 条目重名。
 *
 * 玩家导入的 `.zip` 是群友口上包、二手转发、坏档，**不可信**：没有上界时一个几十 KB 的
 * zip bomb 可以在真机上把 APK 进程打爆（OOM 崩溃屏）。闸做成「边读边计数」的单趟扫描
 * （[Progress] + [readThrough]）：读数越过任一条上限立刻抛 [Rejected] **早退**，绝不
 * 「先 readAllBytes 再检查」——那样检查之前进程已经死了。
 *
 * ## 为什么是这些值
 *
 * - [MAX_ENTRIES] = 512：真口上包一个目录几十个 ERB 文件，512 留十倍余量；再多的包更像
 *   条目洪泛（条目数本身也会拖慢导入前的排序/去重）。
 * - [MAX_ENTRY_BYTES] = 8 MiB：现网口上源单文件都是文本、远小于它；素材资源面的既有上限
 *   是 4 MiB（[ModImportAssets.MAX_RESOURCE_FILE_BYTES]），8 MiB 给「源 + 素材同包」留
 *   一倍余量。
 * - [MAX_TOTAL_BYTES] = 32 MiB：现网真实口上包约 12 MiB（含素材），32 MiB 约三倍余量；
 *   这也是解包后一次性进堆的总上界（源条目会整份进 `List<Entry>`）。
 * - [MAX_COMPRESSION_RATIO] = 120：正常文本/图片 zip 的压缩比是个位数到几十；120 倍以上的
 *   解压放大只可能是构造出来的炸弹（deflate 对全零数据可达约 1000 倍）。解压量不足
 *   [RATIO_MIN_BYTES] 不判压缩比——小包压缩比天然虚高（几百字节的 zip 头占比太大）。
 *
 * ## 计数口径
 *
 * **整包所有条目**（含目录项、以及被 [ModZip.entriesWhere] 的过滤条件跳过的条目）的解压
 * 字节都计数；被跳过的条目仍然流式读一遍但不进堆（`readThrough` 传 `out = null`）。这样
 * 同一份 zip 在 ⓪ 源展开与资源面二扫里读数一致：闸要么在 ⓪ 拦下，要么两处都放行，不存在
 * 「源面放行、资源面偷偷炸堆」的缝。压缩比的分母是 zip 文件本身的字节数（含头开销，
 * 略低估真实压缩比，只会更宽松，不会误伤）。
 */
internal object ModZipGuard {

    /** 条目数上限（含目录项与所有被跳过的条目）。 */
    const val MAX_ENTRIES = 512

    /** 单条目解压后字节上限。 */
    const val MAX_ENTRY_BYTES = 8L * 1024L * 1024L

    /** 整包累计解压字节上限。 */
    const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L

    /** 压缩比上限：解压 / 压缩 > [MAX_COMPRESSION_RATIO] 且解压量 > [RATIO_MIN_BYTES] ⇒ 拒。 */
    const val MAX_COMPRESSION_RATIO = 120L

    /** 压缩比闸的生效下限：解压量不越过它不判压缩比（小包压缩比虚高）。 */
    const val RATIO_MIN_BYTES = 1L * 1024L * 1024L

    /** 流式读取块大小：8 KiB。超限检查按块粒度，早退的读放大最多一块。 */
    const val READ_CHUNK_BYTES = 8 * 1024

    /** 1 MiB 的字节数（文案里的 MiB 读数按它换算，避免魔法数）。 */
    const val BYTES_PER_MIB = 1024L * 1024L

    /** 条目名为空（理论上不该出现）时的文案兜底。 */
    private const val UNKNOWN_ENTRY_NAME = "?"

    /** 拒绝原因：文案按键选 [ModImportCopy] 条目，日志侧只记枚举名。 */
    enum class Reason {
        /** 条目数越过 [MAX_ENTRIES]。 */
        ENTRY_COUNT,

        /** 单条目解压体积越过 [MAX_ENTRY_BYTES]。 */
        ENTRY_SIZE,

        /** 整包解压体积越过 [MAX_TOTAL_BYTES]。 */
        TOTAL_SIZE,

        /** 压缩比越过 [MAX_COMPRESSION_RATIO]（疑似压缩炸弹）。 */
        COMPRESSION_RATIO,

        /** 条目名重复（同名覆盖语义不明确）。 */
        DUPLICATE_NAME,
    }

    /**
     * 闸门拒绝。**继承 [IOException]**：与 zip 读取失败同类，便于调用方在「读流」这一层
     * 统一兜住；[observed] 是触发时的实测读数（字节 / 条目序号），供日志与测试取证。
     */
    class Rejected(
        val reason: Reason,
        /** 触发条目名；批量类判据（整包总量）在触发时也带上当前条目，便于定位。 */
        val entryName: String?,
        /** 触发时的实测读数：体积类 = 字节数，条目数类 = 第几个条目。 */
        val observed: Long,
    ) : IOException("zip guard rejected: $reason (observed=$observed)")

    /**
     * 一次扫描的累计账本：跨条目累计条目数 / 总字节 / 已见条目名，逐条目重置单条目字节。
     *
     * [archiveBytes] 是 zip 文件字节数（压缩比闸的分母）。账本不持有 zip 字节，扫描结束
     * 即丢，不参与后续导入。
     */
    class Progress(private val archiveBytes: Long) {

        private val seenNames = HashSet<String>()

        /** 已经开始的条目数（含目录项与跳过的条目）。 */
        var entries: Int = 0
            private set

        /** 整包累计解压字节数（含跳过的条目）。 */
        var totalBytes: Long = 0L
            private set

        private var entryBytes = 0L

        /** 开始一条条目：条目数 / 重名两道闸在这里判（不读字节）。 */
        fun beginEntry(name: String) {
            if (entries >= MAX_ENTRIES) throw Rejected(Reason.ENTRY_COUNT, name, (entries + 1).toLong())
            entries++
            if (!seenNames.add(name)) throw Rejected(Reason.DUPLICATE_NAME, name, 1L)
            entryBytes = 0L
        }

        /** 读到一段解压字节：单条目 / 整包 / 压缩比三道闸在这里判（边读边判，越限即抛）。 */
        fun onRead(name: String, count: Int) {
            entryBytes += count
            totalBytes += count
            val rejected = overLimit(name) ?: return
            throw rejected
        }

        /** 三道体积闸的判据集中在这里：返回 null 表示本轮读数仍在上界内。 */
        private fun overLimit(name: String): Rejected? = when {
            entryBytes > MAX_ENTRY_BYTES -> Rejected(Reason.ENTRY_SIZE, name, entryBytes)
            totalBytes > MAX_TOTAL_BYTES -> Rejected(Reason.TOTAL_SIZE, name, totalBytes)
            totalBytes > RATIO_MIN_BYTES && totalBytes > MAX_COMPRESSION_RATIO * archiveBytes ->
                Rejected(Reason.COMPRESSION_RATIO, name, totalBytes)
            else -> null
        }
    }

    /**
     * 把当前条目流式读完并计数：[out] 非空才把字节写进堆（命中过滤条件的条目），`null` 表示
     * 只计数不落堆（跳过条目仍要读——计数口径是整包）。任一道闸命中时 [Progress.onRead]
     * 立刻抛 [Rejected]，不会读完整个条目。
     */
    fun readThrough(zin: ZipInputStream, name: String, progress: Progress, out: OutputStream?) {
        val buffer = ByteArray(READ_CHUNK_BYTES)
        while (true) {
            val read = zin.read(buffer)
            if (read < 0) return
            progress.onRead(name, read)
            out?.write(buffer, 0, read)
        }
    }

    /**
     * 拒绝文案：按 [Rejected.reason] 选 [ModImportCopy] 的三语条目并灌读数。
     *
     * 文案表硬规矩：玩家可见文案禁内联字面量——本函数只做「枚举 → 文案键」的映射，
     * 句子本体全在 [ModImportCopy]（形状闸 [ModImportCopyShapeTest] 对账）。
     */
    fun rejectMessage(copy: ModImportCopy, zipName: String, rejected: Rejected): String =
        when (rejected.reason) {
            Reason.ENTRY_COUNT -> copy.fill(copy.zipTooManyEntriesFormat, zipName, MAX_ENTRIES.toLong())
            Reason.ENTRY_SIZE -> copy.fill(
                copy.zipEntryTooLargeFormat,
                zipName,
                rejected.entryName ?: UNKNOWN_ENTRY_NAME,
                MAX_ENTRY_BYTES / BYTES_PER_MIB,
            )
            Reason.TOTAL_SIZE -> copy.fill(copy.zipTotalTooLargeFormat, zipName, MAX_TOTAL_BYTES / BYTES_PER_MIB)
            Reason.COMPRESSION_RATIO -> copy.fill(copy.zipBombSuspectFormat, zipName)
            Reason.DUPLICATE_NAME -> copy.fill(
                copy.zipDuplicateEntryFormat,
                zipName,
                rejected.entryName ?: UNKNOWN_ENTRY_NAME,
            )
        }
}
