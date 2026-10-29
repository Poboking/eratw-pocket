package com.eratw.port.platform.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/**
 * 首启解包失败（文案可读、直接进宿主失败屏，见 `GameHost.ensureAssetsInstalled`）。
 *
 * 与 [IOException] 的关系：本类继承它，是为了让「解包」与「装载」两侧的失败在宿主眼里
 * 仍是同一类 IO 事故（`BootFailures` 的兜底分支会保留 `message` 原话）；
 * 但解包失败**不**走「产物缺失」文案 —— 屏上要看得出是「APK 自带的数据没落到盘」。
 */
class AssetInstallException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * APK assets 的只读视图（解包器唯一的外部依赖）。
 *
 * 抽出来是为了纯 JVM 可测：真机实现是 [AndroidAssetTree]（走 `AssetManager`），
 * 单测用临时目录（见 `GameAssetInstallerTest`）。三个方法的语义都按「不存在就返回空」
 * 设计 —— 调用方不需要区分「路径不存在」与「读失败」。
 */
interface GameAssetTree {

    /** 以流打开 [path]；不存在或不是文件时返回 null（不抛）。 */
    fun openFile(path: String): InputStream?

    /** [path] 目录下的直接子项名（文件与子目录混排）；不是目录或不存在时返回空表。 */
    fun listDir(path: String): List<String>

    /**
     * [path] 作为**未压缩条目**的字节数；压缩条目、目录、不存在一律返回 null。
     *
     * 只用于「目标与资产的字节数是否一致」这一处判断：拿不到（压缩条目）时退化成
     * 「目标存在即视为已装」（写侧经临时文件改名，目标存在即完整）。
     *
     * **0 是有效长度，不是「拿不到」**（语料 `sound/` 下 18 个零字节 `bgm` 标记走的就是这一路）。
     * TODO(verify)：真机上「非空 STORED 条目报 0」没有取证手段（本层没有 Robolectric 装置）；
     * 若某机型真这么报，会被 [GameAssetInstaller] 的落盘后核对判成「与声明不符」而拒绝落盘，
     * 屏上看得到那条 `声明 0 B，实读 N B`。卡点：需要真机 `openFd` 读数。
     */
    fun fileSize(path: String): Long?
}

/**
 * APK 自带数据 → 游戏根的**首启解包器**（M8-ASSETSINSTALL）。
 *
 * ## 为什么需要
 *
 * 装载侧（`ProgramLoader` / `GameSession`）只吃真实文件系统路径：`.erbc` 要 `Path`/mmap，
 * 语料要按相对路径逐文件打开。APK 里的 assets 是 zip 条目、不是路径，所以首次启动必须
 * 把「随包的游戏数据」落一份到 [gameRoot]（[GameStorage.gameRoot] 选出的那个根），
 * 之后照旧走 `GameHost` 的装载链。这是侧载分发与云真机的唯一可行路径 ——
 * adb 一次只能推单个文件，推不了语料里成百上千个 CSV/图片。
 *
 * ## 资产布局（由 `app/build.gradle.kts` 的 `stageErbcAssets` 产出）
 *
 * - `erbc.v1/program.erbc`：单条产物；或
 * - `erbc.v1/program.parts`：分片清单，每行「分片文件名 TAB 字节数」，分片本体是
 *   `erbc.v1/program.partNNN.erbc`（名字由打包侧决定，本类**只认清单顺序**）；
 * - `erbc.v1/program.meta`：侧车（第 3 行是 `program-sha256 "hex"` 或 `-`）；
 * - `erbc.v1/manifest.txt`：可选，原样随包；
 * - `CSV/`、`resources/`、`sound/`：目录递归；
 * - `emuera.config`：原版配置（`ConfigData.cs:33`），**只拷一次**，之后永不覆盖。
 *
 * ## 幂等与「不覆盖用户数据」
 *
 * 每个目标文件都经「同目录临时文件 + 改名」落盘，所以**目标存在即完整**：
 * - 知道资产字节数（STORED 条目，或分片清单）时，目标存在且字节数相同才跳过，不同就重拷
 *   （覆盖上一版产物 / 截断的残留）；**0 字节也是已知长度**——语料 `sound/` 下有 18 个
 *   零字节 `bgm` 标记（`app/build.gradle.kts` 的体积测算段），目标被写成非空同样要重拷；
 * - 拿不到字节数（DEFLATE 条目）时只比存在性 —— 不会为了「比大小」把 328 MB 解压一遍；
 * - `emuera.config` 是**用户可改**的（游戏内设置直接写回该文件），存在就跳过、不比大小；
 * - `sav/` 不在清单里：存档是用户数据，不随包。
 *
 * 目标路径按资产名**原样大小写**落盘（不改写、不折叠）：原版跑在大小写不敏感的 NTFS 上，
 * 兜底在读取侧（`EmueraConfigParser.resolveSubDir` 认 `csv`/`CSV`、`GameImageFiles` 建小写索引、
 * sprite 名按 `AppContents.cs:84` 的 `ToUpper` 归一），解包侧再折一次只会让两处口径分叉。
 * 这里唯一要防的形态是「两条只差大小写的资产映射到同一个目标 ⇒ 每轮来回重拷、永不收敛」：
 * 真语料四个随包目录实测没有这种条目（`find CSV resources sound plugins -type f` 逐条折小写后
 * `sort | uniq -d` 空输出），所以不加检测、也不加第二条跳过规则。
 *
 * 「目标存在即完整」靠的是**同目录改名**这一步；写侧没有 `fd.sync()`（真机成本：随包 3,971 条
 * 就是 3,971 次 fsync）。所以这里的「完整」是对**进程被杀**成立，对**整机掉电**不成立——
 * 极端情况下会留下一个长度正确的截断文件。TODO(verify)：要不要只给 `erbc.v1/` 两条大文件加
 * sync；卡点：没有可复现的掉电取证手段，且首启耗时读数还没钉过基线。
 *
 * ## 内存（512 MiB 堆机型的硬约束）
 *
 * 全量产物 361.1 MiB（`program.erbc` 328,799,098 B + `program.meta` 49,224,530 B）。
 * 本类**没有任何整份 ByteArray**：所有拷贝走 [BUFFER_BYTES]（1 MiB）固定缓冲，
 * SHA-256 在拷贝过程中用 [MessageDigest.update] 增量喂，所以解包期堆增量是
 * 「1 MiB 缓冲 + 若干小 String（清单/头部）」，与产物大小无关。压缩包（
 * `-PeratwErbcCompress`）时走的是 `AssetInputStream` 的流式 inflate，同样只有 1 MiB 常驻。
 *
 * ## 配对核对与配对事务
 *
 * program 拷完（单条或分片拼完）算 SHA-256，与侧车第 3 行声明的 `program-sha256` 比对；
 * 不一致就**清掉刚落的中间文件并抛** [AssetInstallException] —— 盘上的旧产物（若有）由
 * 下方的事务回滚恢复，本类**不再删旧产物**（旧版 program + 旧版侧车是「上一版配对」，
 * 删掉它只会把可降级的状态破坏掉；2026-10-22 审查修正，此前实现删的是 `target` 而非
 * 刚落的中间文件，注释与行为不符）。
 * 声明是 `-`（写侧没给哈希）时只报 `programSha256Verified = false`，不拦。
 *
 * **事务**：program 与 meta 是一对，必须同版。规划期**双向联动**——任一侧与 APK 不一致
 * 就整对重装（单向「program 变 ⇒ meta 跟」之外，补上「meta 变 ⇒ program 跟」，否则
 * 单独换 meta 也是错配）；执行前把两个目标的旧版备份成 `.bak`，全部成功才删备份，
 * 任一步失败（SHA 不配对、中途 IO 错误……）回滚到「上一版配对」。没有这层事务时，
 * 中途失败会留下「新 program + 旧 meta」，下一轮字节相符双双跳过，错配就永久化。
 * 头部只读前 [HEADER_BYTES] 字节 —— 侧车 49 MB，为 3 行头部做整份解析不划算（见
 * [declaredProgramSha] 的注释）。
 *
 * ## 跨进程互斥（DEV-02）
 *
 * 真机实证：解包中途 Activity 被回收/重建时，上一代的解包线程照常跑完，下一代不认得它，
 * 于是两条解包并发写同一批目标与同一批 `.tmp`。表现是其中之一在 [copyStep] 开头
 * 「无法删除解包中间文件」。进程内的槽（宿主侧）只能挡住同一进程内的两代 Activity，
 * 挡不住「宿主进程被杀后重启」与「多进程宿主」，所以互斥必须落在文件系统上：
 *
 * - 锁文件是数据根下的 [LOCK_FILE]（`gameRoot` 是两侧共用的那个根，见 `GameStorage.gameRoot`）；
 * - 用 [RandomAccessFile] 的 `channel.tryLock()` 拿**整文件排他锁**（OS 级 `fcntl`/`LockFile`，
 *   跨进程真实生效；JVM 的 `synchronized` 与静态锁只覆盖本进程，不满足这条）；
 * - 持锁之后才跑 [plan]：第二实例要么等到锁、要么直接看到「已就位」（`needed` 全空）；
 * - 等待有界（[LOCK_WAIT_TIMEOUT_MS]，可用构造参数缩短，测试用），超时抛**带锁路径**的
 *   [AssetInstallException]，明确写「另一实例可能仍在解包」，不静默成功；
 * - 同 JVM 的第二实例拿锁时 JVM 会抛 [OverlappingFileLockException]（不是返回 null）：
 *   这里捕获并转入同一条等待路径，不让它漏成运行时异常；
 * - 释放靠 `finally` 里 `FileLock.release()` + `channel.close()`：进程被 kill 时由内核回收，
 *   不会留下「锁住了但没人解锁」的死锁盘。
 *
 * 锁文件本身是 0 字节常驻文件，不算解包产物：`GameStorage.looksLikeGameRoot` 只认
 * `emuera.config` 与 `erbc.v1/program.erbc` 两个标志，不受它影响。
 */
class GameAssetInstaller(
    private val assets: GameAssetTree,
    private val gameRoot: File,
    /**
     * 等另一实例放锁的上限（毫秒），到点抛 [AssetInstallException]。
     *
     * 缺省 [LOCK_WAIT_TIMEOUT_MS]：首启全量解包（361.1 MiB）在慢机上要数分钟，
     * 等待方必须给对面留出整轮的时间；超过这个上限说明不是「正常的一轮首启」。
     */
    private val lockWaitMillis: Long = LOCK_WAIT_TIMEOUT_MS,
    /** 等待期的轮询间隔（毫秒）；只影响抢锁的响应速度，不影响正确性。 */
    private val lockPollMillis: Long = LOCK_POLL_MS,
) {

    /** 单条进度：`copied` / `total` 都按**资产条目**计（分片各算一条），`label` 是正在落盘的资产名。 */
    class Progress(
        val copied: Int,
        val total: Int,
        val label: String,
    )

    /**
     * 一轮解包的结果。
     *
     * @param copiedFiles 本轮真正写入的文件数（0 = 已在位，一个字节都没动）
     * @param skippedFiles 目标已在位而跳过的文件数
     * @param copiedBytes 本轮写入的字节数
     * @param programSha256Verified program 是否按侧车声明核对通过（没有 program 可拷时为 false）
     */
    class Result(
        val copiedFiles: Int,
        val skippedFiles: Int,
        val copiedBytes: Long,
        val programSha256Verified: Boolean,
    )

    /**
     * 把 assets 里存在的游戏数据解包到 [gameRoot]；重复调用幂等。
     *
     * 只做「该拷什么」的规划与落地；文件系统与资产读写的失败都会包成带路径的
     * [AssetInstallException] 文案（屏上直接可读）。
     *
     * **整轮在 [LOCK_FILE] 的跨进程排他锁里跑**（见类头「跨进程互斥」段）：另一实例在解包时
     * 本调用最多等 [LOCK_WAIT_TIMEOUT_MS]，等到之后 `plan()` 看到的目标已在位 ⇒ 直接返回
     * 「跳过 N 个」，不会去碰对方的中间文件；等不到则抛可读的 [AssetInstallException]。
     * 锁与中间文件无关：`.tmp` 的清理语义与失败文案保持原样。
     *
     * @param onProgress 每落完一个资产条目回调一次（真机上就是 logcat 的「解包中 N/M」）
     */
    fun install(onProgress: (Progress) -> Unit = {}): Result = withInstallLock { installLocked(onProgress) }

    /** [install] 的本体（此时已持有跨进程锁；规划与落盘全在锁内）。 */
    private fun installLocked(onProgress: (Progress) -> Unit): Result {
        val steps = plan()
        val pending = steps.filter { it.needed }
        val total = pending.sumOf { it.sources.size }
        var copiedFiles = 0
        var copiedBytes = 0L
        var verified = false
        // 1 MiB 缓冲整轮只分配一次：几百个小文件共用同一块，避免每文件一块的垃圾叠加。
        val buffer = ByteArray(BUFFER_BYTES)
        var done = 0

        fun advance(label: String) {
            done++
            onProgress(Progress(done, total, label))
        }

        // 配对事务：program 与 meta 必须同版（见类头「配对事务」段）。执行前把两个目标
        // 的旧版备份成 .bak；全部成功才删备份；任一步失败回滚 —— 删掉本轮已 rename 的
        // 新 target、恢复备份、清掉全部中间文件，让盘上停在「上一版配对」。
        val pairTargets = pending.filter { it.pairArtifact }.map { it.target }.distinct()
        val backups = LinkedHashMap<File, File>()
        try {
            if (pending.isNotEmpty()) ensureDir(gameRoot)
            for (target in pairTargets) {
                val bak = File(target.parentFile, target.name + BAK_SUFFIX)
                if (bak.isFile) {
                    // 上轮回滚失败的残留：target 缺失时把 bak 取回当旧产物（取回后走下面
                    // 的正常备份），并存时保守清掉 —— 状态本身不该出现。
                    if (!target.isFile) {
                        if (!bak.renameTo(target)) {
                            throw AssetInstallException("无法恢复上一版产物：${bak.absolutePath}")
                        }
                    } else if (!bak.delete()) {
                        throw AssetInstallException("无法清理残留备份：${bak.absolutePath}")
                    }
                }
                if (!target.isFile) continue
                if (!target.renameTo(bak)) {
                    throw AssetInstallException("无法备份旧产物：${target.absolutePath}")
                }
                backups[target] = bak
            }
            for (step in pending) {
                ensureDir(step.target.parentFile)
                val outcome = copyStep(step, buffer, ::advance)
                copiedBytes += outcome.bytes
                copiedFiles++
                if (step.verifyProgram) verified = outcome.verified
            }
            for (bak in backups.values) bak.delete()
        } catch (t: Throwable) {
            for ((target, bak) in backups) {
                target.delete()
                if (bak.isFile && !bak.renameTo(target)) {
                    // 恢复失败只能把事实写进异常（屏上可见）；盘上残留的 .bak 由下一轮
                    // install 开头的恢复逻辑兜底。
                    throw AssetInstallException(
                        "回滚失败：${bak.absolutePath} 无法恢复到 ${target.absolutePath}",
                        t,
                    )
                }
            }
            for (step in pending) {
                File(step.target.parentFile, step.target.name + TMP_SUFFIX).delete()
            }
            throw t
        }
        return Result(
            copiedFiles = copiedFiles,
            skippedFiles = steps.size - copiedFiles,
            copiedBytes = copiedBytes,
            programSha256Verified = verified,
        )
    }

    // ------------------------------------------------------------------
    // 跨进程互斥
    // ------------------------------------------------------------------

    /**
     * 在 [LOCK_FILE] 的排他锁里跑 [block]。
     *
     * 锁的作用域是「规划 + 落盘」整轮（[install] 的全部工作）：只锁 [plan] 不锁落地等于没锁，
     * 只锁落地则是「两边都规划成 needed，先到的拷完、后到的再拷一遍」。
     *
     * 释放顺序是 `FileLock.release()` 再 `close()`，两步都在 `finally` 里：抛异常、被 kill、
     * 线程被中断都只可能留下「channel 已关 ⇒ OS 锁已被内核回收」，不会卡死下一轮。
     */
    private fun <T> withInstallLock(block: () -> T): T {
        ensureDir(gameRoot)
        val lockFile = File(gameRoot, LOCK_FILE)
        val raf = try {
            // 用 RandomAccessFile 而不是 java.nio.file.Files：minSdk 21 上 java.nio.file 不保证
            // 可用，而 RandomAccessFile 的 channel 从 API 1 就有（tryLock 自 API 1 起有）。
            RandomAccessFile(lockFile, "rw")
        } catch (e: IOException) {
            throw AssetInstallException(
                "无法打开解包互斥锁：${lockFile.absolutePath}（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
        raf.use { file ->
            val channel = file.channel
            val lock = try {
                awaitLock(channel, lockFile)
            } catch (t: Throwable) {
                closeQuietly(channel)
                throw t
            }
            try {
                return block()
            } finally {
                try {
                    lock.release()
                } catch (_: IOException) {
                    // 释放失败只可能是 channel 已失效；close 之后 OS 侧锁同样已被回收。
                }
                closeQuietly(channel)
            }
        }
    }

    /**
     * 有界等待排他锁：拿到就返回，超时抛可读错误。
     *
     * 两条「拿不到」的路径合并处理：
     * - `tryLock()` 返回 null = **另一进程**持锁；
     * - 抛 [OverlappingFileLockException] = **同一 JVM** 的另一实例/线程持锁（JVM 在进程内
     *   先做重叠检测，直接抛而不是返回 null）。DEV-02 的两份解包经常正是同 JVM 两代 Activity，
     *   所以这条必须捕获后走同一条等待，不能原地漏成运行时异常。
     *
     * TODO(verify)：外部数据根（`Android/data/...`，走 FUSE）上的 `fcntl` 排他锁支持没有真机
     * 取证；若某机型上 `tryLock` 直接抛 `IOException`（不支持），这里会转成可读的
     * 「无法获取解包互斥锁」。卡点：需要一台外部根可写的真机，`/proc/locks` 读数。
     */
    private fun awaitLock(channel: FileChannel, lockFile: File): FileLock {
        val deadlineNanos = System.nanoTime() + lockWaitMillis * NANOS_PER_MILLI
        while (true) {
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (e: IOException) {
                throw AssetInstallException(
                    "无法获取解包互斥锁：${lockFile.absolutePath}（${e.javaClass.simpleName}: ${e.message}）",
                    e,
                )
            }
            if (lock != null) return lock
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) throw lockTimeout(lockFile)
            // 轮询间隔按剩余时间截断：最后一跳最多睡到 deadline，不越界多睡一轮。
            val sleepMillis = (remainingNanos / NANOS_PER_MILLI)
                .coerceAtMost(lockPollMillis.coerceAtLeast(1L))
                .coerceAtLeast(1L)
            try {
                Thread.sleep(sleepMillis)
            } catch (e: InterruptedException) {
                // 中断 = 宿主不要这一代了：把中断标志还回去（上层可能还在用），
                // 但不静默当成功 —— 数据没保证就位，必须让宿主走失败屏。
                Thread.currentThread().interrupt()
                throw AssetInstallException(
                    "等待另一实例解包时被中断：锁 ${lockFile.absolutePath}；" +
                        "另一实例可能仍在解包，数据可能未就位",
                    e,
                )
            }
        }
    }

    /** 等待超时的可读错误：必须写出锁路径与「对面可能还在解包」，屏上才判得出该不该重启重试。 */
    private fun lockTimeout(lockFile: File): AssetInstallException {
        val waited = if (lockWaitMillis >= 1000L) "${lockWaitMillis / 1000} 秒" else "$lockWaitMillis 毫秒"
        return AssetInstallException(
            "等待另一实例解包超时（$waited）：锁 ${lockFile.absolutePath} 仍被占用；" +
                "另一实例可能仍在解包，数据可能未就位，请稍后重启应用再试",
        )
    }

    private fun closeQuietly(channel: FileChannel) {
        try {
            channel.close()
        } catch (_: IOException) {
            // 关闭失败无处可报：OS 锁随进程退出回收，这里不覆盖正在上抛的解包失败。
        }
    }

    // ------------------------------------------------------------------
    // 规划
    // ------------------------------------------------------------------

    /**
     * 本轮要落盘的文件清单（含「已在位、不用拷」的条目，用来统计跳过数）。
     *
     * 顺序固定：program → 侧车 → manifest → 配置 → CSV → resources → sound。
     * program 的两种形态（单条 / 分片）只取其一 —— 有清单就**只**按清单拼，
     * 免得同一目录里残留的单条与分片互相打架。
     */
    private fun plan(): List<Step> {
        val steps = ArrayList<Step>()
        val artifactDir = File(gameRoot, ARTIFACT_DIR)

        val manifestParts = readPartsManifest()
        val programTarget = File(artifactDir, PROGRAM_FILE)
        val metaTarget = File(artifactDir, META_FILE)

        // meta 是否需要重拷（双向联动的另一半）：APK 侧车与盘上 meta 不一致时，program
        // 也要跟着整对重装 —— 单独换 meta 会留下「旧 program + 新 meta」的错配。
        // 用一次 probe 同时回答存在性与长度（见 probe KDoc），别为同一条资产问两遍。
        val metaSource = probe(META_ASSET)
        val metaStale = metaSource != null && needsCopy(metaTarget, metaSource.expectedBytes)

        // 单条形态只在「没有分片清单」时才探：probe 会 open 一次，别为一条不要的资产白读。
        val programSingle = if (manifestParts == null) probe(PROGRAM_ASSET) else null
        val programStep: Step? = when {
            manifestParts != null -> Step(
                target = programTarget,
                sources = manifestParts,
                needed = metaStale || needsCopy(programTarget, manifestParts.sumOf { it.expectedBytes ?: 0L }),
                verifyProgram = true,
                pairArtifact = true,
            )
            programSingle != null -> Step(
                target = programTarget,
                sources = listOf(programSingle),
                needed = metaStale || needsCopy(programTarget, programSingle.expectedBytes),
                verifyProgram = true,
                pairArtifact = true,
            )
            else -> null
        }
        programStep?.let { steps += it }
        // program 重拷时侧车必须跟着换：两侧只保证「同一版配对」，不能一新一旧。
        val programStale = programStep?.needed == true

        metaSource?.let { source ->
            steps += Step(
                target = metaTarget,
                sources = listOf(source),
                needed = programStale || needsCopy(metaTarget, source.expectedBytes),
                pairArtifact = true,
            )
        }

        // 段 3 语句记录偏移表侧车（2026-10-23 装载提速；**可选**）：
        // APK 里带了就跟 program/meta 同对待（program 重拷 ⇒ 它也重拷，避免一新一旧）；
        // 旧包没有这条资产 ⇒ 装载期自动回落扫描，行为与提速前逐字相同。
        val stmtIdxTarget = File(artifactDir, STMT_IDX_FILE)
        probe(STMT_IDX_ASSET)?.let { source ->
            steps += Step(
                target = stmtIdxTarget,
                sources = listOf(source),
                needed = programStale || needsCopy(stmtIdxTarget, source.expectedBytes),
                pairArtifact = true,
            )
        }

        val manifestTarget = File(artifactDir, MANIFEST_FILE)
        probe(MANIFEST_ASSET)?.let { source ->
            steps += Step(
                target = manifestTarget,
                sources = listOf(source),
                needed = needsCopy(manifestTarget, source.expectedBytes),
            )
        }

        // 配置只拷一次：游戏内设置会写回它（`ConfigData.SaveToFile`），覆盖等于抹掉玩家配置。
        val configTarget = File(gameRoot, CONFIG_FILE)
        probe(CONFIG_FILE)?.let { source ->
            steps += Step(
                target = configTarget,
                sources = listOf(source),
                needed = !configTarget.isFile,
            )
        }

        for (dir in DATA_DIRS) walkTree(dir, steps)
        return steps
    }

    /**
     * 一次探针同时回答「这条资产在不在」与「它是 STORED 的话有多少字节」。
     *
     * 分成 `assetExists` + `fileSize` 两次问，真机上就是每条资产两次 `openFd` —— 压缩条目
     * 每次都吞一个 `FileNotFoundException`（填栈才是成本大头），3,971 条语料在**每次启动**
     * 的规划段都要付这笔白给的开销（`AndroidAssetTree` 的 `fileSize` 段）。
     *
     * @return null = 资产里没有这条路径；否则是只读长度（压缩条目为 null）的 [Step.Source]
     */
    private fun probe(assetPath: String): Step.Source? {
        assets.fileSize(assetPath)?.let { return Step.Source(assetPath, it) }
        val stream = assets.openFile(assetPath) ?: return null
        stream.close()
        return Step.Source(assetPath, null)
    }

    /** 目标存在的唯一充分条件见类头「幂等」段：[expected] 未知时只比存在性。 */
    private fun needsCopy(target: File, expected: Long?): Boolean =
        !target.isFile || (expected != null && target.length() != expected)

    /**
     * 递归收集 [dir] 下的文件（按名字排序，落盘顺序稳定、日志可复现）。
     *
     * 资产里没有「列目录」之外的元信息：文件与空目录在 `AssetManager.list` 下同为空表，
     * 所以判定顺序是「目标已是文件 → 文件」→「有子项 → 目录」→「能打开 → 文件」→「否则空目录，跳过」。
     * 顺序一个都不许换（同名既是文件又是目录前缀的畸形 zip 条目按目录优先），
     * 但长度只问一次 —— 修复前这里问两遍，见 [probe]。
     */
    private fun walkTree(dir: String, steps: MutableList<Step>) {
        for (name in assets.listDir(dir).sorted()) {
            val path = "$dir/$name"
            val target = File(gameRoot, path)
            val stored = assets.fileSize(path)
            if (target.isFile) {
                steps += Step(
                    target = target,
                    sources = listOf(Step.Source(path, stored)),
                    needed = needsCopy(target, stored),
                )
                continue
            }
            val children = assets.listDir(path)
            if (children.isNotEmpty()) {
                walkTree(path, steps)
                continue
            }
            if (stored == null) {
                val stream = assets.openFile(path) ?: continue
                stream.close()
            }
            steps += Step(target, listOf(Step.Source(path, stored)), needed = true)
        }
    }

    /**
     * 读分片清单 `program.parts`（每行「文件名 TAB 字节数」）；没有清单返回 null。
     *
     * 清单是打包侧与解包侧唯一的顺序契约（分片名可能不是字典序），所以顺序**只认行序**。
     * 空清单、缺 TAB、字节数非正、文件名带路径分隔符都直接报错 —— 这些只可能来自坏产物，
     * 静默跳过只会让装载阶段报「产物缺失」，屏上看不出根因。
     */
    private fun readPartsManifest(): List<Step.Source>? {
        // `openFile` 的契约就是「不存在或不是文件返回 null」（见 GameAssetTree），
        // 所以这里不需要先问一遍存在性 —— 那等于白多打一次 openFd。
        val text = assets.openFile(PARTS_ASSET)?.use { input ->
            input.reader(Charsets.UTF_8).use { it.readText() }
        } ?: return null
        val sources = ArrayList<Step.Source>()
        var lineNo = 0
        for (raw in text.lineSequence()) {
            lineNo++
            val line = raw.removeSuffix("\r").trim()
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) {
                throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行不是「文件名 TAB 字节数」：$line")
            }
            val name = line.substring(0, tab).trim()
            val size = line.substring(tab + 1).trim().toLongOrNull()
                ?: throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行字节数不是整数：$line")
            if (size <= 0L) throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行字节数必须为正：$line")
            if (name.contains('/') || name.contains('\\') || name.contains("..")) {
                throw AssetInstallException("$PARTS_ASSET 第 $lineNo 行分片名非法：$name")
            }
            sources += Step.Source("$ARTIFACT_DIR/$name", size)
        }
        if (sources.isEmpty()) throw AssetInstallException("$PARTS_ASSET 是空清单（没有任何分片）")
        return sources
    }

    // ------------------------------------------------------------------
    // 落地
    // ------------------------------------------------------------------

    /** 一条 step 的拷贝结果。 */
    private class CopyOutcome(val bytes: Long, val verified: Boolean)

    /**
     * 按 [Step.sources] 的顺序把字节拼进 [Step.target]。
     *
     * 先写同目录 `<name>.tmp`，全部校验通过后改名 —— 目标名一旦出现就是完整文件
     * （这也是「存在即跳过」成立的前提）。校验失败时把目标和中间文件都删掉。
     */
    private fun copyStep(step: Step, buffer: ByteArray, advance: (String) -> Unit): CopyOutcome {
        val tmp = File(step.target.parentFile, step.target.name + TMP_SUFFIX)
        if (tmp.exists() && !tmp.delete()) {
            throw AssetInstallException("无法删除解包中间文件：${tmp.absolutePath}")
        }
        val digest = if (step.verifyProgram) MessageDigest.getInstance("SHA-256") else null
        var total = 0L
        try {
            FileOutputStream(tmp).use { out ->
                for (source in step.sources) {
                    val input = assets.openFile(source.assetPath)
                        ?: throw AssetInstallException("APK assets 里缺 ${source.assetPath}")
                    val written = input.use { copy(it, out, buffer, digest) }
                    if (source.expectedBytes != null && written != source.expectedBytes) {
                        throw AssetInstallException(
                            "${source.assetPath} 与声明不符：声明 ${source.expectedBytes} B，实读 $written B",
                        )
                    }
                    total += written
                    advance(source.assetPath)
                }
            }
            var verified = false
            if (digest != null) {
                val declared = declaredProgramSha()
                val actual = hex(digest.digest())
                if (declared != null && !declared.equals(actual, ignoreCase = true)) {
                    // 只清刚落的中间文件，**不动 target**：旧产物（若存在）已被 install 的
                    // 事务备份，由回滚统一恢复成「上一版配对」；首次安装时 target 本来
                    // 就不存在。删旧产物只会破坏可降级状态（2026-10-22 审查修正）。
                    tmp.delete()
                    throw AssetInstallException(
                        "program.erbc 与 program.meta 不配对：侧车声明 $declared，实际 $actual",
                    )
                }
                verified = declared != null
            }
            if (!tmp.renameTo(step.target)) {
                step.target.delete()
                if (!tmp.renameTo(step.target)) {
                    throw AssetInstallException("无法落盘 ${step.target.absolutePath}")
                }
            }
            return CopyOutcome(total, verified)
        } catch (e: AssetInstallException) {
            tmp.delete()
            throw e
        } catch (e: IOException) {
            tmp.delete()
            throw AssetInstallException(
                "解包 ${step.target.name} 失败（${e.javaClass.simpleName}: ${e.message}）",
                e,
            )
        }
    }

    /** 1 MiB 块流式拷贝；[digest] 非空时边拷边喂（不缓存整份字节）。 */
    private fun copy(input: InputStream, output: OutputStream, buffer: ByteArray, digest: MessageDigest?): Long {
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) continue
            output.write(buffer, 0, n)
            digest?.update(buffer, 0, n)
            total += n
        }
        return total
    }

    /**
     * 侧车声明的 `program-sha256`（小写十六进制）；`-`、缺行、文件不存在都返回 null。
     *
     * **只读前 [HEADER_BYTES] 字节**：该行固定是第 3 条记录（`BootMetaCodec.encode` 的写出序，
     * `BootMetadata.kt:271-273`；读侧同序 `:621-624`），而侧车整份 49 MB —— 为了 3 行头部
     * 调 `BootMetadata.read` 会把整份文本解析成记录表（那是装载期才该付的成本，
     * 512 MiB 堆机型上不该在解包段再付一次）。
     *
     * 头部解析与 core 的口径一致（引号串或裸 `-`），由单测拿 `BootMetadata` 自己的编码器
     * 当 oracle 钉住；core 目前没有暴露「只读头部」的入口，若将来 `w8-bootmeta` 补上，
     * 这里应改成直接调它（见交付报告的「需组长接线」）。
     */
    internal fun declaredProgramSha(): String? {
        val head = readHead(META_ASSET) ?: return null
        val line = head.lineSequence()
            .map { it.removeSuffix("\r") }
            .firstOrNull { it.startsWith(SHA_PREFIX) }
            ?: return null
        val raw = line.removePrefix(SHA_PREFIX).trim()
        if (raw.isEmpty() || raw == "-") return null
        return raw.trim('"').trim().lowercase().ifEmpty { null }
    }

    /** 读资产开头 [HEADER_BYTES] 字节（UTF-8；截断处的多字节字符不影响 ASCII 头部行）。 */
    private fun readHead(path: String): String? {
        val input = assets.openFile(path) ?: return null
        return input.use { stream ->
            val buf = ByteArray(HEADER_BYTES)
            var len = 0
            while (len < buf.size) {
                val n = stream.read(buf, len, buf.size - len)
                if (n < 0) break
                len += n
            }
            String(buf, 0, len, Charsets.UTF_8)
        }
    }

    /** 目录不存在就建；建不出来直接报可读错误（磁盘满/权限在真机上很常见）。 */
    private fun ensureDir(dir: File?) {
        if (dir == null) return
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw AssetInstallException("无法创建解包目录：${dir.absolutePath}")
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    /**
     * 一条待落盘记录：[sources] 按顺序拼成一条 [target]（单条资产也走这条路径，只是长度为 1）。
     *
     * @param needed false = 目标已在位，本轮跳过（不建目录、不碰文件）
     * @param verifyProgram 是否是 program 产物：拷完要按侧车声明的 `program-sha256` 核对
     * @param pairArtifact 是否参与 program/meta 配对事务：旧版先备份成 `.bak`，
     *   整轮成功才删备份，任一步失败回滚（见类头「配对事务」段）
     */
    private class Step(
        val target: File,
        val sources: List<Source>,
        val needed: Boolean,
        val verifyProgram: Boolean = false,
        val pairArtifact: Boolean = false,
    ) {
        /** @param expectedBytes 已知的资产字节数（STORED 条目/清单声明）；null = 压缩条目，拿不到 */
        class Source(val assetPath: String, val expectedBytes: Long?)
    }

    companion object {
        /** 产物目录名（与 `GameHost.ARTIFACT_DIR` 同源：`erbcc compile` 的缺省输出）。 */
        const val ARTIFACT_DIR = "erbc.v1"

        /** 产物文件名（与 `GameHost.PROGRAM_FILE` 同源）。 */
        const val PROGRAM_FILE = "program.erbc"

        /** 侧车文件名（与 `GameHost.META_FILE` 同源）。 */
        const val META_FILE = "program.meta"

        /** 分片清单文件名（`app/build.gradle.kts` 的 `stageErbcAssets` 写出）。 */
        const val PARTS_FILE = "program.parts"

        /** 编译清单，随包但不参与装载（原样落盘，便于真机上核对版本）。 */
        const val MANIFEST_FILE = "manifest.txt"

        /**
         * 段 3 语句记录偏移表侧车（可选加速件；`StmtOffsetSidecar.FILE_NAME` 同值）。
         *
         * 有它 ⇒ 装载期跳过「逐条反序列化 171 万条语句只为拿偏移」的索引趟；
         * 没有 ⇒ 装载器回落扫描，语义完全一致（只是慢）。
         */
        const val STMT_IDX_FILE = "program.stmt.idx"

        /** 原版配置文件名（`ConfigData.cs:33` 的 `Program.ExeDir + "emuera.config"`）。 */
        const val CONFIG_FILE = "emuera.config"

        /**
         * 首启要递归落盘的语料目录（存档 `sav` 不在内：那是用户数据）。
         *
         * `plugins/` 必须在内：`ERB/SYSTEM.ERB:96-97` 每局开局都跑
         * `MAP_FROMXML …,LOADTEXT("plugins/tw_csv_chs.xml")`，`DT_FROMXML` 还要
         * `plugins/schema.xml` / `plugins/bbas_*.xml`。缺目录时 `LOADTEXT` 得空串，
         * 解析器在位置 0 报「XML 解析错误」，开局直接中止（2026-10-22 真机实证）。
         *
         * `bad/` 必须在内：`ERB/TITLE.ERB:123-158` 的 `@BADAPPLE` 每帧
         * `LOADTEXT("bad/bad_apple{count}.txt")`（1..6573）。缺目录时 LOADTEXT 得空串，
         * DEMO 空帧全速循环——真机上表现为「首页被清空 + 高频抖动」（2026-10-22
         * DevTools 实证：displayLines 出现 `<font face='黑体'></font></p>` 空帧行）。
         *
         * 列表须与 `app/build.gradle.kts` 的 `stagedDataDirs` 保持一致。
         */
        val DATA_DIRS: List<String> = listOf("CSV", "resources", "sound", "plugins", "bad")

        /** 拷贝块大小：1 MiB，整轮复用一块（见类头「内存」段）。 */
        const val BUFFER_BYTES = 1 shl 20

        /** 侧车头部读取上限：第 3 行固定在前几十字节，4 KiB 足够且不建第二个缓冲。 */
        const val HEADER_BYTES = 4 * 1024

        /** 落盘中间文件后缀（与目标同目录，改名是原子的同卷操作）。 */
        const val TMP_SUFFIX = ".tmp"

        /** 配对事务的旧版备份后缀（整轮成功才删；失败回滚时 rename 回目标名）。 */
        const val BAK_SUFFIX = ".bak"

        /**
         * 跨进程互斥锁文件名，落在数据根（[gameRoot]）下。
         *
         * 0 字节常驻文件：内容无意义（锁是 OS 对 inode 的排他状态），删掉也无害 —— 下一轮
         * 会重新 `CREATE` 出来。**不放在 `erbc.v1/` 里**：那目录归产物所有，带 `.lock`
         * 会让「按产物目录内容核对版本」的口径多一条噪音。
         */
        const val LOCK_FILE = ".install.lock"

        /**
         * 等另一实例放锁的上限：5 分钟。
         *
         * 口径：首启全量解包（361.1 MiB / 3,971 条语料）在真机上要数分钟，等待方必须允许对面
         * 跑完整轮；超过 5 分钟仍没放锁，就不是「同一台设备的正常首启」，交给上层报可读失败
         * （宿主会显示「等待另一实例解包超时 … 请稍后重启应用再试」）。
         */
        const val LOCK_WAIT_TIMEOUT_MS: Long = 5L * 60L * 1000L

        /** 抢锁轮询间隔：100 ms（只影响响应速度；解包本身是分钟级，这点开销可忽略）。 */
        const val LOCK_POLL_MS: Long = 100L

        private const val NANOS_PER_MILLI: Long = 1_000_000L

        /** 侧车头部那行的前缀（`BootMetaCodec.encode` 的写出形态）。 */
        private const val SHA_PREFIX = "program-sha256 "

        private const val PROGRAM_ASSET = "$ARTIFACT_DIR/$PROGRAM_FILE"
        private const val META_ASSET = "$ARTIFACT_DIR/$META_FILE"
        private const val PARTS_ASSET = "$ARTIFACT_DIR/$PARTS_FILE"
        private const val MANIFEST_ASSET = "$ARTIFACT_DIR/$MANIFEST_FILE"
        private const val STMT_IDX_ASSET = "$ARTIFACT_DIR/$STMT_IDX_FILE"
    }
}
