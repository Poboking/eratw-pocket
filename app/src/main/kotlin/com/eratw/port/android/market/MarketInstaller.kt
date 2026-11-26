package com.eratw.port.android.market

import com.eratw.port.android.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** 一条「id 已被哪个 kind 占用」的已知事实（id-kind 查重的查重面，照 service.ts:533-538）。 */
class MarketPackRef(val kind: String, val id: String)

/** 拒绝点统一出口：本文件所有 InstallReject 只此一处 throw（集中可读）。 */
private fun reject(code: String, detail: String): Nothing = throw MarketInstaller.InstallReject(code, detail)

/** IO 失败统一出口：本文件所有裸 IOException 只此一处 throw。 */
private fun ioFail(detail: String, cause: Throwable? = null): Nothing =
    if (cause == null) throw IOException(detail) else throw IOException(detail, cause)

/**
 * 市场包安装流水线（CONTRACT-MKM-W1 §2 mkm-pipe 判据 2/3）。
 *
 * 语义权威 = kojo-editor `packages/plugins/kojo-market/src/service.ts`：
 * 注册表容器源 `installFromIndex` :797-873（下载 → 容器整包摘要对平 → 解压 → 读清单 →
 * 双向对平 → `commitPack`）、唯一搬运路径 `commitPack` :600-764（journal.begin → 清残壳 →
 * 暂存搬运 → 暂存区读回重算逐文件 sha256 → rename 原子落点（同版本重装旧副本先挪
 * `.__previous__`，失败还原）→ 记账；任一步异常 abort 逆序回滚 + 清暂存，半成品绝不留）、
 * 双向对平 `packContainerProblem`（manifest.ts:184-233）。
 *
 * 与 kojo-editor 的两处 W1 裁定差异（组长裁定冻结，见 CONTRACT §0）：
 * - 落点布局含 `pack.json`（W2 导入游戏要用；kojo-editor 落点只有 `files/` 树）——
 *   `pack.json` 随暂存搬运一并进落点，但不参与逐文件 sha 复核（清单锚只覆盖 `files/` 条目）；
 * - OpJournal 缩成单文件安装日志（`.staging/.install.journal`，BEGIN + MOVED 两行）：
 *   W1 没有 undo 历史面，日志只服务「abort 逆序回滚落点侧」，begin 失败 = 整体取消未动盘。
 *
 * 已知残壳窗口（与源实现同在）：markMoved 落盘与 rename 成功之间进程死亡时，落点已是
 * 新树而日志没有 MOVED——abort 不回滚、旧副本暂存到下次同包安装的残壳清扫（源实现
 * 依赖 OpJournal 巡检，W1 无历史面，TODO(verify)：W2 接线时补巡检口径）。
 *
 * 线程口径：阻塞调用线程；装卸互斥由 service 层单飞保证。
 */
open class MarketInstaller(
    private val store: MarketStore,
    private val verifier: MarketVerifier,
    private val fetcher: MarketFetcher,
    /**
     * 版本闸里「本机应用版本」的串（GATE2D 洞②安装硬闸的入参，组长批准口径）：默认
     * [BuildConfig.VERSION_NAME]——与 SettingsActivity 市场 UI 态（MarketViewModel.initial）
     * 同源同一串，展示的满足性与安装的准入判的是同一个版本，UI 灰了却还能装死的分裂
     * 从此不存在；测试注入固定串做双向钉格。闸放安装器不放服务层：commitOrThrow 是两条
     * 安装路径的唯一汇点，闸必须落在动盘前且不靠调用方自觉。
     */
    private val appVersion: String = BuildConfig.VERSION_NAME,
) {

    /**
     * 容器源要比的三样事实（照 manifest.ts `ContainerFacts`）。sha 单源收口（GATE2D 洞④）：
     * 主构造**私有**——sha256 字段不再接受调用方手填裸串。原先只有 fromContainerBytes 一条
     * 入口：sha 必须由容器原始字节经 [Digests] 现算。此前 installFromIndexOrThrow 把对账层
     * 算出的串接力塞进来、buildRecord 照抄进台账、commitOrThrow 不复核——台账的整包信任锚
     * 可以是任何 64 位字面串（谎报 f×64 就落账 f×64）。「谎报」这个类型从此不存在，
     * 反向证明 = 手填构造在测试里编译不过。
     */
    class ContainerFacts private constructor(
        val sha256: String,
        val entries: List<MarketZip.EntryFacts>,
        val declared: MarketIndexEntry,
    ) {

        companion object {

            /**
             * 唯一构造路：对容器原始字节现算整包 sha256（64 位小写 hex）。字节对账
             * （zipBytes / zipSha256 vs 注册表声明）在下载层已先行把关（SHA_MISMATCH），
             * 本函数产出的就是那份被对过账的字节的事实摘要，不再经任何字符串接力。
             */
            fun fromContainerBytes(
                containerBytes: ByteArray,
                entries: List<MarketZip.EntryFacts>,
                declared: MarketIndexEntry,
            ): ContainerFacts = ContainerFacts(
                sha256 = Digests.sha256Hex(containerBytes),
                entries = entries,
                declared = declared,
            )
        }
    }

    /** 校验拒绝（带 [MarketRejectCodes] 封闭码）。IOException 子类，内部流转用。 */
    class InstallReject(val code: String, detail: String) : IOException(detail)

    /**
     * 注册表容器源全链（service.ts:797-873）：形态闸 → 下载（体积上限 = 声明与解压上限取小，
     * 超时 = [MarketContract.downloadTimeoutMs]）→ 容器字节数与整包 sha256 对账 → 限额解压 →
     * 读清单 + id-kind 查重 → [commitPack]。进度只透传（节流在取数层）。
     * 契约性拒绝 = [InstallOutcome.Rejected]；IO/回滚失败 = [InstallOutcome.Failed]；
     * 任何失败都不在暂存区留残壳（finally 全清）。
     */
    open fun installFromIndex(
        entry: MarketIndexEntry,
        onProgress: (doneBytes: Long, totalBytes: Long) -> Unit,
        knownRefs: List<MarketPackRef>,
    ): InstallOutcome {
        val containerFile = containerFileOf(entry)
        val unpackedDir = unpackedDirOf(entry)
        try {
            return try {
                InstallOutcome.Ok(installFromIndexOrThrow(entry, onProgress, knownRefs, containerFile, unpackedDir))
            } catch (e: InstallReject) {
                InstallOutcome.Rejected(e.code, e.message ?: e.code)
            } catch (e: MarketZip.Reject) {
                InstallOutcome.Rejected(e.code, e.message ?: e.code)
            } catch (e: MarketFetcher.RejectException) {
                InstallOutcome.Rejected(e.code, e.message ?: e.code)
            } catch (e: IOException) {
                InstallOutcome.Failed("容器没能装好：${e.message}")
            }
        } finally {
            // 清掉容器源留在暂存区的两样（service.ts `quietRemoveContainer` :774-785；
            // 这里直接删两样，不走「搬进解压目录再整棵删」的绕法——效果同、少一次 rename）。
            Cleaner.quietRemove(containerFile)
            Cleaner.quietRemove(unpackedDir)
        }
    }

    /** 容器源链的抛错形态（调用方 [installFromIndex] 负责折成三态）。 */
    private fun installFromIndexOrThrow(
        entry: MarketIndexEntry,
        onProgress: (Long, Long) -> Unit,
        knownRefs: List<MarketPackRef>,
        containerFile: File,
        unpackedDir: File,
    ): InstalledPack {
        // 0) 形态+白名单闸（零网络、先给作者一句人话；service.ts:805-810）
        fetcher.urlProblem(entry.zipUrl)?.let { reject(MarketRejectCodes.ENDPOINT_FORBIDDEN, it) }
        // 1) 下载容器（体积上限 = 声明与解压上限取小：撒谎的声明换不来无上限下载；service.ts:813-826）
        val maxBytes = minOf(entry.zipBytes, MarketContract.MAX_UNPACKED_BYTES)
        val downloaded = fetcher.downloadToStaging(
            MarketFetcher.ContainerDownload(
                stagingDir = store.stagingRoot,
                maxBytes = maxBytes,
                timeoutMs = MarketContract.downloadTimeoutMs(maxBytes),
            ),
            entry.zipUrl,
            MarketContract.packContainerName(entry.id, entry.version),
            onProgress,
        )
        // 2) 容器字节数 + 整包 sha256 对账（service.ts:827-848；码归 SHA_MISMATCH）
        val blob = try {
            containerFile.readBytes()
        } catch (e: IOException) {
            ioFail("容器落盘后读不出来：${containerFile.path}（${e.message}）", e)
        }
        if (downloaded.bytes != entry.zipBytes || blob.size.toLong() != entry.zipBytes) {
            reject(
                MarketRejectCodes.SHA_MISMATCH,
                "容器字节数与注册表声明不符：下载回报 ${downloaded.bytes}B、盘上 ${blob.size}B，" +
                    "那一行写的是 ${entry.zipBytes}B",
            )
        }
        val hex = Digests.sha256Hex(blob)
        if (hex != entry.zipSha256) {
            reject(
                MarketRejectCodes.SHA_MISMATCH,
                "容器整包摘要与注册表不符（下载回来的不是那一行说的包）：期望 ${entry.zipSha256}，实算 $hex",
            )
        }
        // 3) 解压到暂存子树（限额三个数全取契约常量；service.ts:850-861）
        val extracted = MarketZip.extract(containerFile, unpackedDir)
        // 4) 读清单 + id-kind 查重（service.ts `readSourceManifest` :547-578）
        val manifest = readManifest(unpackedDir)
        val clash = knownRefs.firstOrNull { it.id == manifest.id && it.kind != manifest.kind }
        if (clash != null) {
            reject(
                MarketRejectCodes.ID_KIND_CONFLICT,
                "包 id ${manifest.id} 已经以「${clash.kind}」这个种类在用，不能再装成「${manifest.kind}」",
            )
        }
        // 5) commitPack（与目录源同一条校验+搬运路径，一个环节都不重写）
        return commitOrThrow(
            unpackedDir,
            manifest,
            // 洞④单源：台账里的整包 sha 由本机容器字节现算，不再传对账层局部串
            ContainerFacts.fromContainerBytes(containerBytes = blob, entries = extracted.entries, declared = entry),
        )
    }

    /**
     * 唯一搬运路径（service.ts `commitPack` :600-764）：容器双向对平 → journal.begin →
     * 清上次残壳 → 暂存搬运 → 暂存区读回重算逐文件 sha256 → rename 原子落点 → 记账 →
     * journal.commit。任一步异常：abort 逆序回滚落点侧 + 清暂存，半成品绝不留。
     */
    open fun commitPack(sourceDir: File, manifest: PackManifest, container: ContainerFacts?): InstallOutcome = try {
        InstallOutcome.Ok(commitOrThrow(sourceDir, manifest, container))
    } catch (e: InstallReject) {
        InstallOutcome.Rejected(e.code, e.message ?: e.code)
    } catch (e: IOException) {
        InstallOutcome.Failed("安装失败：${e.message}")
    }

    /** [commitPack] 的抛错形态。 */
    private fun commitOrThrow(sourceDir: File, manifest: PackManifest, container: ContainerFacts?): InstalledPack {
        // 0) 容器双向对平（service.ts:605-611；manifest.ts:184-233 四方向；首个问题即拒）
        container?.let { facts ->
            ContainerCheck.problem(manifest, facts)?.let { reject(MarketRejectCodes.MANIFEST_MISMATCH, it) }
        }
        // 0b) 安装侧 minAppVersion 硬闸（GATE2D 洞②，组长批准落点）：此前生产链对接口版
        //     minAppVersionSatisfied 零调用——版本不符只被 UI 灰置，安装路径照收 minAppVersion
        //     高于本机的包。落点 = commitOrThrow 头部、journal.begin 之前：容器源与目录源两条路
        //     共用，任何动盘与记账都不发生在闸前。具名实参钉死 (minAppVersion, appVersion) 方向——
        //     判词 20 洞③正是「接口版与 UI 壳版参数序相反、可互接反」，接反的方向从此被类型面
        //     （单源委托）与调用面（具名）双重堵死。码复用 [MarketRejectCodes.APP_VERSION_UNSUPPORTED]，
        //     文案表与三态展示已有此码，零 UI 接线。
        if (!verifier.minAppVersionSatisfied(minAppVersion = manifest.minAppVersion, appVersion = appVersion)) {
            reject(
                MarketRejectCodes.APP_VERSION_UNSUPPORTED,
                "本包要求应用版本 ≥ ${manifest.minAppVersion}，本机是 $appVersion——不装：" +
                    "${manifest.kind}/${manifest.id} v${manifest.version}",
            )
        }
        val staging = File(store.stagingRoot, MarketContract.packStagingName(manifest.id, manifest.version))
        val stale = File(store.stagingRoot, MarketContract.packStaleName(manifest.id, manifest.version))
        val target = store.dirOf(manifest.kind, manifest.id, manifest.version)
        // 1) journal.begin：动盘之前 begin；失败 = 整体取消未动盘（service.ts:616-623）
        val journal = InstallJournal.begin(store.stagingRoot, target, stale)
            ?: ioFail("journal.begin 失败（整体取消，未动盘）：${manifest.id} v${manifest.version}")
        try {
            // 2) 清上次崩在这里的残壳（service.ts:624-625）
            Cleaner.quietRemove(staging)
            Cleaner.quietRemove(stale)
            // 3) 暂存搬运（字节级）+ 4) 暂存区读回重算逐文件 sha256（不信任源）
            StagingMover.moveInto(sourceDir, staging, manifest)
            StagingMover.verifyFromStaging(staging, manifest)
            // 5) 原子搬进落点（同版本重装 = 覆盖；service.ts:685-707）
            target.parentFile?.mkdirs()
            val hadPrev = target.exists()
            if (hadPrev && !target.renameTo(stale)) {
                reject(MarketRejectCodes.STAGING_FAILED, "旧副本挪去 ${stale.name} 失败（同版本重装中止，落点原样）")
            }
            if (!staging.renameTo(target)) {
                if (hadPrev) Cleaner.quietRename(stale, target) // 搬不动就还原旧的，别让落点空着
                reject(MarketRejectCodes.STAGING_FAILED, "暂存区搬进落点失败：${staging.path} → ${target.path}")
            }
            journal.markMoved()
            if (hadPrev) Cleaner.quietRemove(stale)
        } catch (e: IOException) {
            // 半途而废：abort 逆序回滚落点侧 + 清暂存（service.ts:719-738；主错与回滚失败都要见人）
            val rollbackProblem = journal.abort()
            Cleaner.quietRemove(staging)
            if (rollbackProblem != null) {
                reject(e.codeOr(), "${e.message}（且回滚失败：$rollbackProblem）")
            }
            throw e
        }
        // 6) 记账（service.ts:740-757）：账写完包才算装好。记账在 catch 块之外（与源同口径）——
        // 账写失败不回滚已落点的包，错误向上抛 Failed（半趟态由消费侧 revalidate 收敛，CONTRACT §2.6）。
        val record = buildRecord(manifest, container)
        store.upsertRecord(record)
        // 7) journal.commit（service.ts:711-718）：失败不回滚不抛错（包已装好、账已写，抛错=假失败）
        journal.commit()
        return record
    }

    /** 读清单 + 解析校验（service.ts:547-565；缺失/读不出/解析拒都归 MANIFEST_MISMATCH）。 */
    private fun readManifest(unpackedDir: File): PackManifest {
        val manifestFile = File(unpackedDir, MarketContract.MANIFEST_FILE)
        if (!manifestFile.isFile) {
            reject(
                MarketRejectCodes.MANIFEST_MISMATCH,
                "解压目录里没有 ${MarketContract.MANIFEST_FILE}：${unpackedDir.path}",
            )
        }
        val rawText = try {
            manifestFile.readText(Charsets.UTF_8)
        } catch (e: IOException) {
            ioFail("pack.json 读不出来：${manifestFile.path}（${e.message}）", e)
        }
        return when (val parsed = verifier.parsePackManifest(rawText)) {
            is MarketParseResult.Ok -> parsed.value
            is MarketParseResult.Invalid -> reject(MarketRejectCodes.MANIFEST_MISMATCH, parsed.problem)
        }
    }

    /** 记账条目（service.ts:740-754：路径不落账；目录源没有容器 = containerSha256 缺省）。 */
    private fun buildRecord(manifest: PackManifest, container: ContainerFacts?): InstalledPack = InstalledPack(
        kind = manifest.kind,
        id = manifest.id,
        version = manifest.version,
        name = manifest.name,
        author = manifest.author,
        license = manifest.license,
        installedAtMs = System.currentTimeMillis(),
        containerSha256 = container?.sha256,
        contentDigest = verifier.contentDigest(manifest.files.map { it.path to it.sha256 }),
        fileCount = manifest.files.size,
        bytes = manifest.files.sumOf { it.bytes },
    )

    /** 容器与解压两样的派生文件（名字只出自契约派生函数，不拼第二份）。 */
    private fun containerFileOf(entry: MarketIndexEntry): File =
        File(store.stagingRoot, MarketContract.packContainerName(entry.id, entry.version))

    private fun unpackedDirOf(entry: MarketIndexEntry): File =
        File(store.stagingRoot, MarketContract.packUnpackedName(entry.id, entry.version))

    /** 容器双向对平（照 manifest.ts:184-233 `packContainerProblem` 四方向，纯函数）。 */
    private object ContainerCheck {

        /** 首个问题即拒（返回 null = 全平）。码统一归 MANIFEST_MISMATCH，人话进 detail。 */
        fun problem(manifest: PackManifest, facts: ContainerFacts): String? = listOf(
            { identityProblem(manifest, facts) },
            { duplicateEntryProblem(facts) },
            { missingEntryProblem(manifest, facts) },
            { extraEntryProblem(manifest, facts) },
        ).firstNotNullOfOrNull { it() }

        /** 注册表那行说 kind/id/version，容器里的 pack.json 必须逐字一致（落点按清单派生）。 */
        private fun identityProblem(manifest: PackManifest, facts: ContainerFacts): String? {
            val d = facts.declared
            if (manifest.kind == d.kind && manifest.id == d.id && manifest.version == d.version) return null
            return "容器里的 pack.json 写的是 ${manifest.kind}/${manifest.id} v${manifest.version}，" +
                "注册表那一行说的是 ${d.kind}/${d.id} v${d.version}——不是同一个包"
        }

        private fun duplicateEntryProblem(facts: ContainerFacts): String? {
            val seen = HashSet<String>()
            for (entry in facts.entries) {
                if (!seen.add(entry.path)) return "容器里同一个路径出现两次：${entry.path}"
            }
            return null
        }

        /** 清单声明、容器没解出来 = 装完的包少文件；字节数不符 = 内容与清单不是同一份。 */
        private fun missingEntryProblem(manifest: PackManifest, facts: ContainerFacts): String? {
            val byPath = facts.entries.associate { it.path to it.bytes }
            return manifest.files.firstNotNullOfOrNull { f ->
                when (byPath[f.path]) {
                    null -> "清单声明了 ${f.path}，容器里没解出来"
                    else -> bytesProblem(f.path, f.bytes, byPath.getValue(f.path))
                }
            }
        }

        /** 字节数对平：平了给 null，不平给人话。 */
        private fun bytesProblem(path: String, declared: Long, actual: Long): String? =
            if (actual == declared) null else "$path 清单声明 ${declared}B，容器里解出 ${actual}B"

        /** 容器解出来、清单没声明 = 落点里躺着没人核对过的东西（根 pack.json 是元数据，不算）。 */
        private fun extraEntryProblem(manifest: PackManifest, facts: ContainerFacts): String? {
            val declared = manifest.files.map { it.path }.toSet()
            for (entry in facts.entries) {
                val isMetadata = entry.path == MarketContract.MANIFEST_FILE
                if (!isMetadata && !declared.contains(entry.path)) {
                    return "容器里解出了 ${entry.path}，清单却没有声明它"
                }
            }
            return null
        }
    }

    /** 暂存搬运与读回复核（service.ts:631-683 的两段循环，逐文件字节级）。 */
    private object StagingMover {

        private const val COPY_BUF = 64 * 1024

        /**
         * 搬运：`pack.json` 元数据随行（W1 布局裁定）+ 清单逐条字节级复制进暂存区。
         * 清单声明的文件在源里读不到 = 搬运失败（STAGING_FAILED）；字节数不符 = 清单锚破（MANIFEST_MISMATCH）。
         */
        fun moveInto(sourceDir: File, staging: File, manifest: PackManifest) {
            val manifestSrc = File(sourceDir, MarketContract.MANIFEST_FILE)
            if (!manifestSrc.isFile) {
                ioFail("源目录里没有 ${MarketContract.MANIFEST_FILE}：${sourceDir.path}")
            }
            staging.mkdirs()
            copyFile(manifestSrc, File(staging, MarketContract.MANIFEST_FILE))
            for (f in manifest.files) {
                val src = File(sourceDir, f.path)
                if (!src.isFile) {
                    reject(MarketRejectCodes.STAGING_FAILED, "清单声明的文件在源目录里读不到：${f.path}")
                }
                val written = copyFile(src, File(staging, f.path))
                if (written != f.bytes) {
                    reject(
                        MarketRejectCodes.MANIFEST_MISMATCH,
                        "字节数对不上清单：${f.path} 声明 ${f.bytes}B，实读 ${written}B",
                    )
                }
            }
        }

        /** 暂存区读回算锚：哈希必须来自落盘那一份（源侧哈希会让"写的"与"核的"是两个东西）。 */
        fun verifyFromStaging(staging: File, manifest: PackManifest) {
            for (f in manifest.files) {
                val bytes = try {
                    File(staging, f.path).readBytes()
                } catch (e: IOException) {
                    ioFail("暂存区的文件读不回来：${f.path}（${e.message}）", e)
                }
                if (bytes.size.toLong() != f.bytes) {
                    reject(
                        MarketRejectCodes.MANIFEST_MISMATCH,
                        "暂存区里的字节数和清单不符：${f.path} 声明 ${f.bytes}B，实读 ${bytes.size}B",
                    )
                }
                val hex = Digests.sha256Hex(bytes)
                if (hex != f.sha256) {
                    reject(
                        MarketRejectCodes.SHA_MISMATCH,
                        "校验锚不符（包内容与 pack.json 说的不是同一份东西）：${f.path} 期望 ${f.sha256}，实算 $hex",
                    )
                }
            }
        }

        /** 字节级复制并回报字节数（父目录就地建齐——平台写命令 create_dir_all 的同效位）。 */
        private fun copyFile(src: File, dst: File): Long = FileInputStream(src).use { input ->
            dst.parentFile?.mkdirs()
            FileOutputStream(dst).use { out -> pump(input, out) }
        }

        /** 逐块搬运主体。 */
        private fun pump(input: FileInputStream, out: FileOutputStream): Long {
            var written = 0L
            val buf = ByteArray(COPY_BUF)
            while (true) {
                val n = input.read(buf)
                if (n < 0) return written
                out.write(buf, 0, n)
                written += n
            }
        }
    }

    /**
     * 单文件安装日志（BEGIN → MOVED → commit/abort；abort 按标记逆序回滚落点侧）。
     * W1 没有 undo 历史面（OpJournal 是 kojo-editor 的批量撤销层），这份日志只负责
     * 「rename 已过落点、账还没写」这个窗口的回滚依据。
     */
    private class InstallJournal private constructor(
        private val file: File,
        private val target: File,
        private val stale: File,
    ) {

        /** rename staging→target 成功后补 MOVED 行（abort 据此逆序回滚；落盘失败抛 IOException）。 */
        fun markMoved() {
            file.appendText(JOURNAL_MOVED + "\n", Charsets.UTF_8)
        }

        /** commit = 删日志文件（失败只静默——包已装好、账已写，抛错只会呈现假失败）。 */
        fun commit() {
            file.delete()
        }

        /**
         * abort 逆序回滚落点侧（service.ts:720-734）：有 MOVED = 删落点新树 + 还原旧副本；
         * 没有 = 只在落点缺席而旧副本还在时还原（崩在两次 rename 之间的形态）。
         * 返回回滚失败的人话（null = 干净回滚）；主错与回滚失败都要见人。
         */
        fun abort(): String? {
            val problems = ArrayList<String>()
            val movedOnDisk = runCatching { file.readText(Charsets.UTF_8).contains(JOURNAL_MOVED) }
                .getOrDefault(false)
            if (movedOnDisk && target.exists() && !target.deleteRecursively()) {
                problems += "落点新树删除失败：${target.path}"
            }
            if (!target.exists() && stale.exists() && !stale.renameTo(target)) {
                problems += "旧副本还原失败：${stale.path} → ${target.path}"
            }
            file.delete()
            return problems.joinToString(separator = "；").takeIf { it.isNotEmpty() }
        }

        companion object {
            private const val JOURNAL_NAME = ".install.journal"
            private const val JOURNAL_MOVED = "MOVED"

            /**
             * begin：覆盖写 BEGIN 行（暂存根就地建齐）。失败返回 null（调用方整体取消未动盘——
             * 宁可不装，也不给一个装得上却撤不回的批量写）。
             */
            fun begin(stagingRoot: File, target: File, stale: File): InstallJournal? = runCatching {
                stagingRoot.mkdirs()
                val file = File(stagingRoot, JOURNAL_NAME)
                file.writeText("BEGIN\n", Charsets.UTF_8)
                InstallJournal(file, target, stale)
            }.getOrNull()
        }
    }

    /** 清理面（清理永远不该盖住主错误，照 service.ts `quietRemove` :309-316 口径）。 */
    private object Cleaner {

        fun quietRemove(target: File) {
            runCatching { target.deleteRecursively() }
        }

        /** rename 但不在「本就不在/被占用」这类清理副作用上炸（回滚失败由调用方出声）。 */
        fun quietRename(from: File, to: File): Boolean = runCatching { from.renameTo(to) }.getOrDefault(false)
    }

    /** sha256 唯一实现点（64 位小写 hex；locale 无关，不走 String.format）。 */
    private object Digests {

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") {
                    (it.toInt() and HEX_BYTE_MASK).toString(HEX_RADIX).padStart(2, '0')
                }

        private const val HEX_RADIX = 16

        private const val HEX_BYTE_MASK = 0xFF
    }
}

/** IOException 混流进回滚告警时取封闭码（InstallReject 用自己的码，普通 IO 归 STAGING_FAILED）。 */
private fun IOException.codeOr(): String =
    (this as? MarketInstaller.InstallReject)?.code ?: MarketRejectCodes.STAGING_FAILED
