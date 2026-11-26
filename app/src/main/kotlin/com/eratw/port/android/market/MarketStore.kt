package com.eratw.port.android.market

import java.io.File
import java.io.IOException

/**
 * 市场包的落盘与已装记账（CONTRACT-MKM-W1 §2 mkm-pipe 判据 3）。
 *
 * 语义权威 = kojo-editor `packages/plugins/kojo-market/src/service.ts` 的记账/自愈
 * （`verifiedRecords` :287-307：账不存路径、批量 stat、丢条目剔除并写回）与卸载
 * （`uninstall` :1270-1331：删目录 + 清账 + 收空壳父目录 + 按 [MarketContract.packStagingNames]
 * 清全部暂存残壳）、`removeEmptyAncestors` :318-328。
 *
 * 落盘布局（组长裁定冻结）：`<filesDir>/market/packs/<kind>/<id>/<version>/`，
 * 内放 `pack.json` 加 `files/` 之下的全树；记账文件 = `<filesDir>/market/installed.journal`，
 * 一行一条、TAB 分 11 字段（与 InstalledPack 字段同名对齐，账里**不存路径**——落点由
 * [MarketContract] 派生函数重建，路径只信盘上这一份真相）。
 *
 * 行格式字符串字段统一反斜杠转义（`\`、TAB、CR、LF），name/author/license 是自由文本
 * 也不会破行。坏行（字段数不对/形态破坏）整行跳过——照 service.ts `readRecords`
 * 对形坏条目「忽略并出声」的口径；账是本实现自己写的，形坏只可能来自外部改动。
 *
 * 纯 JVM（java.io.File），禁 Android API；线程口径：调用方保证装卸互斥（service 单飞）。
 */
class MarketStore(private val marketRoot: File) {

    /** 已装包落点根：`<market>/packs`。 */
    val packsRoot: File = File(marketRoot, MarketContract.PACKS_DIR)

    /** 暂存区根：`<market>/.staging`（下载、解压、搬运共用地）。 */
    val stagingRoot: File = File(marketRoot, MarketContract.STAGING_DIR)

    /** 记账文件（一行一条）。 */
    private val ledgerFile = File(marketRoot, LedgerCodec.LEDGER_FILE)

    /** 派生落点（唯一真源是 [MarketContract] 的目录段名，这里不拼第二份）。 */
    fun dirOf(kind: String, id: String, version: String): File =
        File(File(File(packsRoot, kind), id), version)

    /** 读全部记账行（形坏行跳过；文件缺席 = 空账）。 */
    fun readRecords(): List<InstalledPack> {
        val text = try {
            if (ledgerFile.isFile) ledgerFile.readText(Charsets.UTF_8) else return emptyList()
        } catch (e: IOException) {
            throw IOException("已装记账读不出来：${ledgerFile.path}（${e.message}）", e)
        }
        return text.lineSequence().filter { it.isNotBlank() }.mapNotNull { LedgerCodec.parseLine(it) }.toList()
    }

    /** 原子写回整份账（同目录临时文件 + 原子挪，照 market.rs:364-408 的原子写形态）。 */
    fun writeRecords(list: List<InstalledPack>) {
        marketRoot.mkdirs()
        val tmp = File(marketRoot, LedgerCodec.LEDGER_FILE + LedgerCodec.TMP_SUFFIX)
        val body = list.joinToString(separator = "\n", postfix = if (list.isEmpty()) "" else "\n") {
            LedgerCodec.encodeLine(it)
        }
        try {
            tmp.writeText(body, Charsets.UTF_8)
            MarketAtomicMove.move(tmp, ledgerFile)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 记一条装好的包：同 (kind,id,version) 的旧条目先剔除再追加（照 service.ts:755-757）。 */
    fun upsertRecord(record: InstalledPack) {
        val rest = readRecords().filter { !LedgerCodec.samePack(it, record.kind, record.id, record.version) }
        writeRecords(rest + record)
    }

    /**
     * 已装清单的唯一核验口径（service.ts:287-307）：一趟批量 stat 核派生落点在位 →
     * 剔除丢失条目 → 丢了就写回（自愈）。`installed()` 与 `installedBytes()` 都走这条，
     * 清单与占用两个读数永远同一份在位真相。
     */
    fun verifiedRecords(): List<InstalledPack> {
        val list = readRecords()
        val alive = ArrayList<InstalledPack>(list.size)
        val lost = ArrayList<String>()
        for (record in list) {
            if (dirOf(record.kind, record.id, record.version).exists()) {
                alive += record
            } else {
                lost += "${record.kind}/${record.id} v${record.version}"
            }
        }
        if (lost.isNotEmpty()) writeRecords(alive)
        return alive
    }

    /** 已装总字节数：与 [verifiedRecords] 同一次核验口径，目录已丢的包不计入。 */
    fun installedBytes(): Long = verifiedRecords().sumOf { it.bytes }

    /**
     * 卸载（service.ts:1270-1331 逐条）：目录与账都没有 = [UninstallOutcome.NotFound]；
     * 目录缺席但账还有 = 只清账（照原实现「目录本来就不在位，只清了清单条目」的口径）。
     * 成功路径 = 删目录 → 写回账 → 收空壳父目录 → 按 [MarketContract.packStagingNames]
     * 清这个包在暂存区里的全部残壳（容器 / 解压目录 / 搬运目录 / 回滚副本）。
     */
    fun uninstall(kind: String, id: String, version: String): UninstallOutcome {
        val dir = dirOf(kind, id, version)
        val list = readRecords()
        val rest = list.filter { !LedgerCodec.samePack(it, kind, id, version) }
        val hadDir = dir.exists()
        if (!hadDir && rest.size == list.size) return UninstallOutcome.NotFound
        return when (val problem = removeThenClear(dir, rest, hadDir, id, version)) {
            null -> UninstallOutcome.Ok
            else -> UninstallOutcome.Failed(problem)
        }
    }

    /**
     * 库内被超越版本清扫（CONTRACT-MKM-W5-GC §0.1/§0.3）：逐个列 packs 下的 (kind,id) 组，
     * 同组版本目录多于一版时保留最高版（[MarketVerifier.versionCompare]），其余版本整目录
     * 删除，并照 [MarketContract.packStagingNames] 清各自在暂存区的残壳。返回删除的版本
     * 目录数。
     *
     * 保守面（契约裁定冻结）：版本形态非法（versionCompare 抛错）的目录不参与选主、永不
     * 删除；与保留版比较异常或同值异写比平（如 1.0.0 与 01.0.0）的目录同样保留；单版本
     * 目录 / 组缺席 / 库根缺失 = no-op 返回 0。只动 packs 子树——mods/rt2 与台账零触碰，
     * 被删旧版的孤儿账行由 [verifiedRecords] 的在位自愈在读数面收敛（账不存路径、盘上是
     * 唯一真相，见 CONTRACT-MKM-W1 §2 的记账口径）。
     *
     * 失败语义：单目录删除失败（占用/IO）不影响其它目录，已删计数照实返回；**失败不重试**，
     * 下次触发（install 成功段或显式清扫）自然重试。线程口径：调用方保证与装卸互斥
     * （service 层单飞锁内跑）。
     */
    fun sweepSuperseded(verifier: MarketVerifier): Int =
        Sweeper.sweep(packsRoot, stagingRoot, verifier)

    /** 删目录 → 写回账 → 收空壳 → 清暂存残壳；失败原因只收第一个（删除/记账两处硬错）。 */
    private fun removeThenClear(
        dir: File,
        rest: List<InstalledPack>,
        hadDir: Boolean,
        id: String,
        version: String,
    ): String? {
        val hardProblem = removeTree(dir, rest, hadDir)
        if (hardProblem != null) return hardProblem
        Cleaner.removeEmptyAncestors(dir, packsRoot)
        for (name in MarketContract.packStagingNames(id, version)) {
            Cleaner.quietRemove(File(stagingRoot, name))
        }
        return null
    }

    /** 硬错面：目录删除与账写回（清理副作用不在这里，永远不该盖住主错误）。 */
    private fun removeTree(dir: File, rest: List<InstalledPack>, hadDir: Boolean): String? {
        if (hadDir && !dir.deleteRecursively()) return "目录删除失败：${dir.path}"
        return try {
            writeRecords(rest)
            null
        } catch (e: IOException) {
            "记账写回失败：${e.message}"
        }
    }

    /** 清理面（清理永远不该盖住主错误——照 service.ts `quietRemove` :309-316 的口径）。 */
    private object Cleaner {

        /** 删目录/文件但不在「本就不存在/被占用」这类副作用上炸。 */
        fun quietRemove(target: File) {
            runCatching { target.deleteRecursively() }
        }

        /** 空壳父目录逐级收掉，直到 packs 根（装卸完留下的 `packs/<kind>/<id>` 空层没意义）。 */
        fun removeEmptyAncestors(dir: File, packsRoot: File) {
            var parent = dir.parentFile ?: return
            while (parent != packsRoot && parent != parent.parentFile) {
                if (!parent.exists() || parent.list()?.isNotEmpty() == true) break
                quietRemove(parent)
                parent = parent.parentFile ?: return
            }
        }
    }

    /**
     * 被超越版本清扫的实现体（独立小对象，主类只留一个透传入口）：
     * 逐 (kind,id) 组「自比探测滤非法形态 → 选主（比较异常/比平不换 best，照 updateCheck
     * 的判序）→ 只删与保留版可比且严格更低的目录 + 各自暂存残壳」。单版本组没有候选
     * （candidate == best 跳过）、全非法组选不出主（best 缺席全跳过），两条 no-op 路
     * 都自然落在这两个 continue 上。
     */
    private object Sweeper {

        /** 整库清扫主循环：库根缺失 = 0；单目录删除失败不重试（下次触发自然重试），不影响其它。 */
        fun sweep(packsRoot: File, stagingRoot: File, verifier: MarketVerifier): Int {
            val kindDirs = packsRoot.listFiles(File::isDirectory) ?: return 0
            var deleted = 0
            for (kindDir in kindDirs) {
                val idDirs = kindDir.listFiles(File::isDirectory) ?: continue
                for (idDir in idDirs) {
                    deleted += sweepGroup(idDir, stagingRoot, verifier)
                }
            }
            return deleted
        }

        /** 单个 (kind,id) 组：非法形态/比较异常/同值比平（?: 折 0）都进不了「严格更低」判定。 */
        private fun sweepGroup(idDir: File, stagingRoot: File, verifier: MarketVerifier): Int {
            val versions = idDir.listFiles(File::isDirectory).orEmpty()
            val compare = { a: String, b: String -> runCatching { verifier.versionCompare(a, b) }.getOrNull() }
            val best = versions.asSequence()
                .filter { dir -> compare(dir.name, dir.name) != null }
                .reduceOrNull { acc, next -> if ((compare(next.name, acc.name) ?: 0) > 0) next else acc }
            var deleted = 0
            for (candidate in versions) {
                if (best == null || candidate == best) continue
                if ((compare(best.name, candidate.name) ?: 0) > 0 && candidate.deleteRecursively()) {
                    deleted += 1
                    for (name in MarketContract.packStagingNames(idDir.name, candidate.name)) {
                        Cleaner.quietRemove(File(stagingRoot, name))
                    }
                }
            }
            return deleted
        }
    }

    /** 记账行的编解码（escape/encode/parse/同包判定，store 主类只走这四件事）。 */
    private object LedgerCodec {

        const val LEDGER_FILE = "installed.journal"

        /** 原子写用的临时后缀。 */
        const val TMP_SUFFIX = ".tmp"

        /** 一条记账的 11 字段（与 [InstalledPack] 字段同名对齐，账不存路径）。 */
        private const val FIELD_COUNT = 11

        /* 数字段与可缺省段的位次（字符串段 0..5 前缀紧随其后）。 */
        private const val FIELD_KIND = 0
        private const val FIELD_ID = 1
        private const val FIELD_VERSION = 2
        private const val FIELD_NAME = 3
        private const val FIELD_AUTHOR = 4
        private const val FIELD_LICENSE = 5
        private const val FIELD_INSTALLED_AT = 6
        private const val FIELD_CONTAINER_SHA = 7
        private const val FIELD_CONTENT_DIGEST = 8
        private const val FIELD_FILE_COUNT = 9
        private const val FIELD_BYTES = 10

        /** 同包判定：(kind,id,version) 三元组相等即同一份（照 service.ts `samePack`）。 */
        fun samePack(record: InstalledPack, kind: String, id: String, version: String): Boolean =
            record.kind == kind && record.id == id && record.version == version

        fun encodeLine(record: InstalledPack): String = listOf(
            escape(record.kind),
            escape(record.id),
            escape(record.version),
            escape(record.name),
            escape(record.author),
            escape(record.license),
            record.installedAtMs.toString(),
            escape(record.containerSha256 ?: ""),
            escape(record.contentDigest ?: ""),
            record.fileCount.toString(),
            record.bytes.toString(),
        ).joinToString("\t")

        /** 形坏行（字段数不对 / 数字段不可解析）返回 null，调用方整行跳过。 */
        fun parseLine(line: String): InstalledPack? {
            val fields = splitFields(line)
            return if (fields.size != FIELD_COUNT) null else assemble(fields)
        }

        /** 数字段解析（可空拆出来——非法形态让 [assemble] 折 null 整行跳过）。 */
        private class RowNumbers private constructor(val installedAtMs: Long, val fileCount: Int, val bytes: Long) {
            companion object {
                fun of(fields: List<String>): RowNumbers? {
                    val installedAtMs = fields[FIELD_INSTALLED_AT].toLongOrNull()
                    val fileCount = fields[FIELD_FILE_COUNT].toIntOrNull()
                    val bytes = fields[FIELD_BYTES].toLongOrNull()
                    return if (installedAtMs == null || fileCount == null || bytes == null) {
                        null
                    } else {
                        RowNumbers(installedAtMs, fileCount, bytes)
                    }
                }
            }
        }

        /** 按已过 [RowNumbers] 核验的字段拼一条记账。 */
        private fun assemble(fields: List<String>): InstalledPack? {
            val numbers = RowNumbers.of(fields) ?: return null
            return InstalledPack(
                kind = unescape(fields[FIELD_KIND]),
                id = unescape(fields[FIELD_ID]),
                version = unescape(fields[FIELD_VERSION]),
                name = unescape(fields[FIELD_NAME]),
                author = unescape(fields[FIELD_AUTHOR]),
                license = unescape(fields[FIELD_LICENSE]),
                installedAtMs = numbers.installedAtMs,
                containerSha256 = fields[FIELD_CONTAINER_SHA].takeIf { it.isNotEmpty() }?.let { unescape(it) },
                contentDigest = fields[FIELD_CONTENT_DIGEST].takeIf { it.isNotEmpty() }?.let { unescape(it) },
                fileCount = numbers.fileCount,
                bytes = numbers.bytes,
            )
        }

        /** 字符串字段统一反斜杠转义（`\`、TAB、CR、LF 四种）。 */
        private fun escape(raw: String): String = buildString(raw.length) {
            for (c in raw) when (c) {
                '\\' -> append("\\\\")
                '\t' -> append("\\t")
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                else -> append(c)
            }
        }

        private fun unescape(raw: String): String = if (!raw.contains('\\')) {
            raw
        } else {
            buildString(raw.length) {
                var i = 0
                while (i < raw.length) {
                    val c = raw[i]
                    if (c == '\\' && i + 1 < raw.length) {
                        when (raw[i + 1]) {
                            't' -> append('\t')
                            'r' -> append('\r')
                            'n' -> append('\n')
                            else -> append('\\')
                        }
                        i += 2
                    } else {
                        append(c)
                        i++
                    }
                }
            }
        }

        /** TAB 分段（转义保证字段值里不会有裸 TAB）。 */
        private fun splitFields(line: String): List<String> = line.split('\t')
    }
}

/**
 * 市场域共用的原子挪（同目录临时文件 → 终态文件）：Windows 的 `File.renameTo`
 * 不覆盖已存在目标，跨平台统一走 nio 的 REPLACE_EXISTING（本仓 core-vm 的
 * java.nio.file 依赖由 core library desugaring 补齐，AGENTS/构建脚本同口径）。
 * 同目录替换在 POSIX/NTFS 的 rename 语义下即原子，不再叠加 ATOMIC_MOVE 选项
 * （不支持原子挪的文件系统上那只会把简单的替换变成第二套失败面）。
 */
internal object MarketAtomicMove {

    fun move(from: File, to: File) {
        java.nio.file.Files.move(
            from.toPath(),
            to.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
    }
}
