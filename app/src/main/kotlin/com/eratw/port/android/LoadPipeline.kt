package com.eratw.port.android

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.eratw.port.core.host.ParallelTask
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.update.UpdatePolicy
import com.eratw.port.core.update.UpdateTelemetry
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.render.canvas.CanvasConsoleView
import java.io.File

/**
 * 资源解包与产物定位（W3 自 GameHost 拆出）：首启解包、镜像数据包应用、数据版本闭环、
 * 产物目录判定与 `eratw-meta` 并行任务的收口 —— 装载域里**有独立状态、独立生命周期**的
 * 一段（[assetsReady] 单次判定 + 解包进度 + meta 线程收口），宿主只留启动编排与产物常量
 * 引用（[ARTIFACT_DIR] 等）。
 *
 * ## 线程口径
 * [ensureAssetsInstalled] 双线程可达（后台预热读口 `artifactsAvailable` / 装载线程
 * `loadAndRun` 前置）—— 内部以 [assetsReady] 单次判定保证解包只跑一次；UI 线程直入时
 * 落一条告警日志（同步解包分钟级）。失败经 [recordFailure] 回写宿主失败屏，不在本类内
 * 持失败状态（失败族字段仍是宿主生命周期的一部分）。
 */
internal class LoadPipeline(
    private val context: Context,
    private val viewProvider: () -> CanvasConsoleView,
    private val onMainThread: () -> Boolean,
    private val storage: GameStorage,
    private val failureCopy: ShellFailureCopy,
    private val recordFailure: (kind: FailureScreenKind, text: String) -> Unit,) {

    /** 动态视图读口：`GameHost.reattachView` 换绑后逐次取新值（弹窗/Toast/Context 自动跟随新壳）。 */
    private val view: CanvasConsoleView get() = viewProvider()
    /** 解包进度读口（MainActivity 轮询面；解包结束后回 null）。 */
    private var lastInstallProgress: GameAssetInstaller.Progress? = null

    /** 首启解包单次判定（null = 未跑过）。 */
    private var assetsReady: Boolean? = null

    @Volatile
    internal var inflightMeta: ParallelTask<BootMetadata>? = null

    fun artifactsAvailable(): Boolean {
        if (!ensureAssetsInstalled()) return true
        return artifactsOnDisk()
    }

    fun artifactsOnDisk(): Boolean = artifactsOnDiskIn(storage.gameRoot())

    fun artifactDir(): File = File(storage.gameRoot(), ARTIFACT_DIR)

    fun installProgress(): GameAssetInstaller.Progress? = lastInstallProgress

    fun ensureAssetsInstalled(): Boolean {
        assetsReady?.let { return it }
        if (onMainThread()) {
            Log.w(TAG, "ensureAssetsInstalled 在 UI 线程上未命中预热缓存：可能整段同步解包（首启分钟级）——调用方应先在后台问 artifactsAvailable")
        }
        val ok = try {
            var lastLogAt = 0L
            var lastLogged = -1
            val dataVersionCode = UpdateManager.currentVersionCode(context)
            val gameRoot = storage.gameRoot()
            // 盘上数据版本只作为「事实」交给装载相位，由 GameAssetInstaller 按让位档逐文件
            // 裁决（CONTRACT-BOOTUNPACK）。这里**不再**用 disk > bundled 把整轮解包短路：
            // 那会把配置缺键补齐、出厂值迁移、内置 RT2 投递、摘要维护一起跳过，而且 marker
            // 被循环回拨到与随包同版时判据恒不成立，冷启全量重写约 919 MiB 不会收敛。
            // 整轮解包照跑，只是「该拷什么」在解包器里让位；让位档零写入时摘要也停在
            // 高版本，下面的标记闭环段自然不会前推。
            // 读数与 companion 的 unpackSuppressed 同源（CONTRACT-FIX-P13-SUPPRESS F2/F4）：
            // marker 与文件级摘要取大、两源各过坏值上界；marker 停旧版而摘要已新版是
            // 数据包应用段的真实可达中间态，单信 marker 会把新版语料打回 APK 内旧版。
            val markerVersion = UpdateManager.installedDataVersion(gameRoot)
            val digestVersion =
                runCatching { GameAssetInstaller.readDataDigest(gameRoot)?.version ?: 0L }.getOrDefault(0L)
            val diskDataVersion = UpdatePolicy.effectiveDiskDataVersion(markerVersion, digestVersion)
            if (UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion, dataVersionCode)) {
                Log.i(
                    TAG,
                    "盘上数据版本 $diskDataVersion 高于随包 $dataVersionCode：" +
                        "装载相位进入让位档（整轮解包照跑，已在位文件不覆盖）",
                )
            }
            val report = GameAssetInstaller(
                storage.assetTree(),
                gameRoot,
                recordDataVersion = dataVersionCode,
                configMigrations = PortFactoryConfig.CONFIG_VALUE_MIGRATIONS,
                diskDataVersion = diskDataVersion,
            ).install { progress ->
                lastInstallProgress = progress
                val now = System.nanoTime()
                val step = (progress.total / LOG_PROGRESS_STEPS).coerceAtLeast(1)
                if (progress.copied != lastLogged &&
                    (progress.copied >= lastLogged + step || now - lastLogAt >= LOG_PROGRESS_MS * NS_PER_MS)
                ) {
                    lastLogged = progress.copied
                    lastLogAt = now
                    Log.i(TAG, "解包中 ${progress.copied}/${progress.total} ${progress.label}")
                }
            }
            lastInstallProgress = null
            Log.i(
                TAG,
                "首启解包完成：写入 ${report.copiedFiles} 个文件 ${bytesToMiB(report.copiedBytes)} MiB，" +
                    "跳过 ${report.skippedFiles} 个已在位，" +
                    "program-sha256 核对=${report.programSha256Verified}，根=${gameRoot.absolutePath}",
            )
            report.recordProblem?.let {
                Log.w(TAG, "随包解包的文件级摘要没写成（下一次更新仍按没摘要走全量）：$it")
            }

            val recordedDigest = runCatching { GameAssetInstaller.readDataDigest(gameRoot) }.getOrNull()
            if (report.recordProblem == null && recordedDigest?.version == dataVersionCode) {
                UpdateManager.rememberInstalledDataVersion(gameRoot, dataVersionCode)
                Log.i(TAG, "数据版本标记已闭环：update/data-version.txt = $dataVersionCode（与文件级摘要同版）")
            } else {
                Log.i(
                    TAG,
                    "数据版本标记不前推：本次要求=$dataVersionCode，摘要版本=${recordedDigest?.version ?: "无"}，" +
                        "摘要问题=${report.recordProblem ?: "无"}（盘上数据按原标记处理）",
                )
            }

            when (val applied = UpdateManager.applyStagedBundleIfPresent(context, gameRoot)) {
                is UpdateManager.ApplyOutcome.Done -> {
                    Log.i(
                        TAG,
                        "镜像数据包已应用：档位 ${applied.kind}，版本 ${applied.version}，写入 ${applied.copiedFiles} 个文件 " +
                            "${bytesToMiB(applied.copiedBytes)} MiB",
                    )
                    UpdateTelemetryWiring.assetsApplied(
                        context,
                        applied.version,
                        UpdateTelemetry.OUTCOME_SUCCESS,
                        applied.kind,
                        null,
                    )
                }
                is UpdateManager.ApplyOutcome.Failed -> {
                    Log.w(TAG, "镜像数据包没应用上：${applied.problem}")
                    view.post {
                        runCatching {
                            Toast.makeText(context, applied.problem, Toast.LENGTH_LONG).show()
                        }.onFailure { Log.w(TAG, "数据包应用失败提示没弹出来（已进日志）", it) }
                    }
                    UpdateTelemetryWiring.assetsApplied(
                        context,
                        null,
                        UpdateTelemetry.OUTCOME_FAILED,
                        null,
                        applied.category.label,
                    )
                }
                is UpdateManager.ApplyOutcome.Nothing -> Unit
            }

            UpdateTelemetryWiring.flushPendingAsync(context)
            PlayerStatsWiring.reportAsync(context)
            true
        } catch (t: Throwable) {
            lastInstallProgress = null
            Log.e(TAG, "首启解包失败", t)
            recordFailure(
                FailureScreenKind.ARTIFACT,
                failureCopy.failurePrefix +
                    failureCopy.fill(failureCopy.unpackFailedFormat, t.message ?: t.javaClass.simpleName),
            )
            false
        }
        assetsReady = ok
        return ok
    }

    /** 宿主 close() 的 meta 线程收口（幂等；false = 未在时限内收口，随进程退出）。 */
    internal fun cancelInflightMeta(): Boolean {
        val meta = inflightMeta ?: return true
        return meta.cancel(META_JOIN_MS)
    }

    companion object {
        /**
         * 盘上产物在位清点的唯一口径：erbc 配对产物（[PROGRAM_FILE] + [META_FILE]）都在游戏根的
         * [ARTIFACT_DIR] 下。实例读口 [artifactsOnDisk]（GameHost.start 的「产物缺失」失败屏判据）
         * 与 [unpackSuppressed] 的让位前置共用这一份清点，不许再造第二份清单口径。
         */
        internal fun artifactsOnDiskIn(gameRoot: File): Boolean {
            val dir = File(gameRoot, ARTIFACT_DIR)
            return File(dir, PROGRAM_FILE).isFile && File(dir, META_FILE).isFile
        }

        /**
         * 随包解包让位的**单一判定入口**：两条 lane 合流后由 MainActivity 的**空间预检**消费；
         * 本类 [ensureAssetsInstalled] 只取同一组读数（marker + digest）交给
         * `GameAssetInstaller` 的逐文件让位档，不再拿本函数的结论短路整轮解包
         * （CONTRACT-BOOTUNPACK：整轮短路会连配置补齐 / 出厂值迁移 / RT2 投递 / 摘要维护
         * 一起跳过）。两处共用同一组 core 纯函数，不许复制第二份判据逻辑。三段：
         *
         * 1. 盘上数据版本 = [UpdatePolicy.effectiveDiskDataVersion]（marker 与文件级摘要**取大**）：
         *    数据包应用段先落摘要、marker 最后改名且改名失败只记日志不抛 —— marker 停旧版而摘要
         *    已新版是真实可达的中间态，单信 marker 会判「不让位」，随包解包把新版语料逐个覆盖回
         *    APK 内旧版，「下载完更新、重启后还是旧版」的循环以同形复发；
         * 2. 判大于走 core 判据 [UpdatePolicy.unpackSuppressedByNewerDiskData]（签名与语义不动）；
         * 3. **让位前置「盘上产物在位」**：产物缺失时返回 false，空间预检照常生效 ——
         *    让位档下 `GameAssetInstaller` 仍会按「目标缺失 ⇒ 重拷」把缺失产物补回来，
         *    本轮并非零写入，缺口读数不能像旧整轮短路那样被无条件跳过（自愈恢复）。
         */
        internal fun unpackSuppressed(gameRoot: File, bundledVersionCode: Long): Boolean {
            val markerVersion = UpdateManager.installedDataVersion(gameRoot)
            val digestVersion =
                runCatching { GameAssetInstaller.readDataDigest(gameRoot)?.version ?: 0L }.getOrDefault(0L)
            val diskDataVersion = UpdatePolicy.effectiveDiskDataVersion(markerVersion, digestVersion)
            val newerThanBundled = UpdatePolicy.unpackSuppressedByNewerDiskData(diskDataVersion, bundledVersionCode)
            val artifactsPresent = artifactsOnDiskIn(gameRoot)
            if (newerThanBundled && !artifactsPresent) {
                Log.i(
                    TAG,
                    "盘上数据版本 $diskDataVersion 高于随包 $bundledVersionCode 但盘上产物缺失：放弃让位，走随包解包自愈",
                )
            }
            return newerThanBundled && artifactsPresent
        }

        private const val BYTES_PER_MIB = 1024L * 1024L
        internal const val TAG = "GameHost"
        const val ARTIFACT_DIR = "erbc.v1"
        const val PROGRAM_FILE = "program.erbc"
        const val META_FILE = "program.meta"
        internal const val META_JOIN_MS = ParallelTask.DEFAULT_JOIN_MS
        private const val NS_PER_MS = 1_000_000L
        private const val LOG_PROGRESS_STEPS = 16
        private const val LOG_PROGRESS_MS = 500L
    }

    private fun bytesToMiB(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MIB
}
