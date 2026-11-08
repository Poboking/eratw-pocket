package com.eratw.port.android

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.ManifestRead
import com.eratw.port.core.update.ManifestSignature
import com.eratw.port.core.update.UpdateInfo
import com.eratw.port.core.update.UpdateManifest
import com.eratw.port.core.update.UpdatePolicy
import com.eratw.port.platform.storage.BlockDeltaAssetTree
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.platform.storage.ZipAssetTree
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 国内镜像仓**自动更新通道**的管线层：取清单、按镜像回退、流式下载并核对、暂存、拉起安装器。
 *
 * ## 分工
 *
 * **语义判定全在 core（[UpdatePolicy] / [UpdateManifest]）与 [UpdateDownloadPlan]（有定向单测）；
 * 本对象只做「网络 + 文件」的 IO 胶水**，弹窗与线程调度在 [UpdateUi]。与 [ErrorReportUploader]
 * 同一套纪律：**通道未配置 = 每个入口第一行就返回，一个字节都不出网。**
 *
 * ## 通道开关 = 出包时有没有把镜像列表编进包
 *
 * 镜像基址由 `-PeratwUpdateMirrors=https://a/eratw|https://b/eratw` 注入（resValue，缺省空串）。
 * 空 = 整条通道关：日常开发包、CI 包、`--offline` 包都照旧零网络行为。要开就在出包时给列表 ——
 * 与上报端点同一条「参数在出包时定、玩家设备上不改」的口径。
 *
 * ## 两条通道，两种落地时机
 *
 * - **整包（APK）**：下到应用私有目录 → 摘要核对 → 交给系统安装器（要玩家亲手点确认）。
 *   覆盖安装要求**签名一致**：用 debug keystore 签的旧内测包只能卸载重装（出包体检已经在挡
 *   没配发布章的包，见 `devtools/pack_release.py`）；
 * - **数据包**：下到应用私有目录暂存，**不在前台应用**。替换四千条正在被 VM 读的文件必然让
 *   玩家读到新旧混合的语料，所以「下载完成」与「应用完成」分成两拍：应用发生在下一次启动、
 *   装载开始之前（由 `GameHost.ensureAssetsInstalled` 调 [applyStagedBundleIfPresent]）。
 *   这一拍的位置也是刻意的：**必须在 APK 随包解包之后**，否则新包里的旧数据会把刚更新的数据盖回去。
 *   实际下**块级差量 / 文件级差量 / 全量**由 core 的 [UpdatePolicy.dataArtifactFor] 四参形定
 *   （[UpdateOffer.dataArtifact]，装配点 [UpdateDownloadPlan.selectDataArtifact]），app 不重算（契约 §1.2/§3）；
 *   选中的档位同时进 logcat（`数据通道选件`），因为「差量静默失效」在这条通道上不会报错，
 *   只有流量账单知道。落地那一拍按**包内实况**选树（[UpdateDownloadPlan.stagedApplyPlan]）：
 *   带 `update-blocks/` 条目的包只有 [BlockDeltaAssetTree] 读得出来，用普通 zip 树解它
 *   会得到「版本标记写了、内容还是旧的」这种不报错的坏状态。
 *
 * ## 摘要核对为什么放在下载而不是应用
 *
 * 镜像仓是静态目录，任何人都能把链接换掉；清单里的 `sha256` 是唯一能把「玩家拿到的字节」
 * 与「发布侧算过的字节」钉在一起的凭据。核对不过就地删掉 `.part`（内容已不可信，续传只会把
 * 错误缝下去）并换下一条镜像 —— 宁可让玩家白等一趟，也不能把没核对过的包送进系统安装器或解包器。
 *
 * ## 断点续传与退避重试（CONTRACT-UPDATE §1.4，U-B 的存在理由）
 *
 * - `.part` **保留原地不删**：进程被系统回收后，下一次冷启的同一次下载凭「字节数 + 年龄」
 *   认领它（[UpdateDownloadPlan.resumeOffsetFor] → Range 请求）。整条管线没有 Activity 引用、
 *   没有跨进程内存状态，状态只有下载目录与 prefs 两份，所以「死了再来」不需要额外记账；
 * - 服务端**回 200（或 206 但 Content-Range 对不上）时截断从头重写**，绝不在旧 `.part` 后追加
 *   —— 那样得到的是 sha 必错的缝合文件，每次都失败在最末尾（代价与理由见
 *   [UpdateDownloadPlan.mustRewriteFromScratch]）；
 * - 续传完成后照旧**边下边算 SHA-256 + 字节数核对**：续传只是让 digest 从盘上前缀开始喂，
 *   判定链一根都没少；
 * - 同一条镜像按 [UpdatePolicy.retryBackoffMillis]（1s/2s/5s/15s/30s）退避重试，只发生在
 *   「再发一次可能不同」的失败类上（[UpdateDownloadPlan.decideFailure]）；档位用完换下一条镜像，
 *   镜像回退序本身不动（[UpdatePolicy.mirrorOrder]）；
 * - 超期（[UpdatePolicy.PART_MAX_AGE_MILLIS]）或字节数超过声明的 `.part` 一律作废重来；
 *   装完/换版后与当前清单无关的陈旧产物（旧 APK、旧 zip）由 [cleanupStaleDownloads] 回收，
 *   每次 [checkNow] 取到清单就顺手跑一遍 —— 冷启自动检查因此同时是残留回收点。
 *
 * ## 不静默（契约 §1.5，本层不变式）
 *
 * 任何下载都必须由玩家点一次「下载」后经 [tryBeginDownload] 开闸；本对象没有任何自动触发
 * 下载的路径，也没有任何绕过弹窗的安装路径（[launchInstaller] 只是把已核对的文件交给系统
 * 确认界面）。这条形状对 U-E 的接线保持原样。
 *
 * 暂存的数据包应用失败会在偏好里记次数，连续 [MAX_APPLY_ATTEMPTS] 次失败就把包丢弃并把原因
 * 报给屏上 —— 不能让玩家每次启动都撞同一颗雷。
 */
object UpdateManager {

    private const val TAG = "EratwUpdate"

    /** 源上的清单文件名（与 `devtools/emit_update.py` 的产出同名，两边改要一起改）。 */
    const val MANIFEST_FILE_NAME = "manifest.txt"

    /** 镜像列表在 resValue 里的分隔符（URL 里不会出现竖线）。 */
    private const val MIRROR_SEPARATOR = "|"

    /** 更新通道自己的 SharedPreferences（与 [UiPrefs] 分开：那是显示偏好，这是下载状态）。 */
    const val PREFS_NAME = "eratw_update"

    private const val KEY_LAST_CHECK = "last_check_millis"
    private const val KEY_EXTRA_MIRRORS = "extra_mirrors"
    private const val KEY_STAGED_ASSETS_PATH = "staged_assets_path"
    private const val KEY_STAGED_ASSETS_VERSION = "staged_assets_version"
    private const val KEY_APPLY_ATTEMPTS = "apply_attempts"

    /** C-2：登记时那件 artifact 的字节数（应用前复检；<= 0 = 老指针没记过，不拦）。 */
    private const val KEY_STAGED_ASSETS_SIZE = "staged_assets_size"

    /** C-2：登记时那件 artifact 的 sha256（与清单一字不差；留档/未来复检用，应用前不重算全份）。 */
    private const val KEY_STAGED_ASSETS_SHA = "staged_assets_sha256"

    /** C-2：选件时盘上的数据版本 = 差量包的基线（全量包恒 0，不参与判定）。 */
    private const val KEY_STAGED_ASSETS_BASE = "staged_assets_base_version"

    /** 已交给安装器的 APK：路径 + 交出时的本机 versionCode（装成了才允许回收，见 [pruneHandedOffApk]）。 */
    /**
     * 这个基线的差量包**应用失败过**（落不了盘、配对不全、块级补丁拼不回声明的字节）：记下来，
     * 下次同一基线的差量一律不选、直接走全量。不记的话版本号没变、差量照样坏，就会每 12 小时白下一遍。
     *
     * 块级差量（P2-13）与文件级差量**共用这一条键**：core 的 `blockDeltaUsable` 与 `deltaUsable`
     * 吃的都是同一个 `diskTrusted`，记一次两档一起关死（见 [UpdateDownloadPlan.StagedApply.Apply.recordBadBaseOnFailure]）。
     * 键名与语义都不动 —— 老包记过的账对新代码照样有效。
     */
    private const val KEY_DELTA_BAD_BASE = "delta_bad_base_version"
    /** 上次检查拿到的「整包文件名 + 它的签名章指纹」，供下载前做覆盖升级预检。 */
    private const val KEY_EXPECTED_APK_FILE = "expected_apk_file"
    private const val KEY_EXPECTED_APK_CERT = "expected_apk_cert_sha256"
    private const val KEY_HANDOFF_APK_PATH = "handed_off_apk_path"
    private const val KEY_HANDOFF_APK_CODE_BEFORE = "handed_off_apk_code_before"

    // 上一次检查的「结果形状」（EP4 U2-③ 追加的一组只读点，只追加、不动任何判据）。
    // 为什么另开键而不往 KEY_LAST_CHECK 上做文章：那条的既有语义是「上次查成功的时间」，
    // 检查间隔判定读的就是它；把失败也写进去等于改判据（失败会立刻变成「到点再试」，
    // 每次冷启都往连不上的地址打一遍）。失败形状、尝试时间、留下的原话各走一条新键。

    /** 上次检查的结果标记：取值由 UpdateChannelReadout.outcomeOf 判（空串 = 从没记过）。 */
    private const val KEY_LAST_CHECK_OUTCOME = "last_check_outcome"

    /** 上次检查尝试的时间（成功或失败都写，0 = 没记过）。 */
    private const val KEY_LAST_CHECK_ATTEMPT_MILLIS = "last_check_attempt_millis"

    /** 上次检查留下的原话，换行连接；存的时候截过条，真实条数另记（见 KEY_LAST_CHECK_NOTE_COUNT）。 */
    private const val KEY_LAST_CHECK_NOTES = "last_check_notes"

    /** 那些原话的真实条数：屏上报数量用这个数，不许跟着截条一起变少（那是谎报）。 */
    private const val KEY_LAST_CHECK_NOTE_COUNT = "last_check_note_count"

    /** 清单取的超时（毫秒）：几十十字节，取不到就换下一条镜像，不在这儿耗着。 */
    private const val MANIFEST_TIMEOUT_MS = 8_000

    /** 下载的超时（毫秒）：读超时按「一块」计而不是整包计，慢源也能跑完。 */
    private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 30_000

    /** 应用暂存数据包连续失败的上限，超过就丢包。 */
    const val MAX_APPLY_ATTEMPTS = 3

    /** 游戏根下的更新状态目录（[GameAssetInstaller] 不认这个目录，所以随包解包不会碰它）。 */
    private const val UPDATE_DIR = "update"
    private const val DATA_VERSION_FILE = "$UPDATE_DIR/data-version.txt"
    private const val DOWNLOAD_DIR_NAME = "eratw-update"
    private const val APK_MIME = "application/vnd.android.package-archive"

    // ------------------------------------------------------------------
    // 通道配置与盘上读数
    // ------------------------------------------------------------------

    /** 出包时编进包的镜像基址；**空表 = 通道关闭**（见类头）。 */
    fun builtInMirrors(context: Context): List<String> =
        context.getString(R.string.eratw_update_mirrors)
            .split(MIRROR_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** 通道是否启用：出包带了镜像，且玩家在设置页没关掉。 */
    fun channelEnabled(context: Context): Boolean =
        builtInMirrors(context).isNotEmpty() && UiPrefs.autoUpdateEnabled(context)

    /**
     * 当前候选镜像序：内置 + 上次清单里记住的，**并消费镜像健康度**（P1-7）。
     *
     * 清单声明的新镜像会被记下来，于是「加一个国内源」不需要重出 APK；但清单**挤不掉**内置源
     * —— 顺序由 [UpdatePolicy.mirrorOrderWithHealth] 定：近期连续失败的内置源降位但不删除，
     * 失联的源没资格把自己排到第一位。健康度由 [UiPrefs.mirrorHealth] 读，由 [checkNow] 与
     * [download] 在每条源成败后写（[rememberMirrorHealth]）。
     */
    fun mirrorCandidates(context: Context): List<String> = UpdatePolicy.mirrorOrderWithHealth(
        builtIn = builtInMirrors(context),
        persisted = persistedMirrors(context),
        health = UiPrefs.mirrorHealth(context),
        nowMillis = System.currentTimeMillis(),
    )

    /** 本应用当前的 versionCode（`longVersionCode` 要 API 26 才取，minSdk 21 得分档）。 */
    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
    }

    /**
     * 本应用当前的 versionName（`0.2.41` 这种玩家认得的口径，与「关于」卡同源：`BuildConfig`）。
     *
     * 只给弹窗正文展示用：更新判定一律按 versionCode 走（[currentVersionCode]），这里不参与任何
     * 比较 —— 两套读数混用会把「同 versionName、不同 versionCode」的构建判成已最新。
     */
    fun currentVersionName(): String = BuildConfig.VERSION_NAME

    /** 上次检查成功的时间（毫秒，0 = 从没查过）。 */
    fun lastCheckMillis(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    /** 上次检查**尝试**（成功或失败都算）的时间（毫秒，0 = 没记过）。EP4 U2-③ 追加的读点。 */
    fun lastCheckAttemptMillis(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK_ATTEMPT_MILLIS, 0L)

    /** 上次检查的结果标记（"" = 从没记过）。取值口径见 UpdateChannelReadout.outcomeOf。 */
    fun lastCheckOutcome(context: Context): String = prefs(context).getString(KEY_LAST_CHECK_OUTCOME, "").orEmpty()

    /** 上次检查留下的原话（最多存 20 条，真实条数读 [lastCheckNoteCount]）。 */
    fun lastCheckNotes(context: Context): List<String> =
        UpdateChannelReadout.decodeNotes(prefs(context).getString(KEY_LAST_CHECK_NOTES, "").orEmpty())

    /** 上次检查留下的原话的**真实**条数（截断只影响屏上列几条，不影响这里报的数量）。 */
    fun lastCheckNoteCount(context: Context): Int = prefs(context).getInt(KEY_LAST_CHECK_NOTE_COUNT, 0)

    /**
     * 记下这一次检查的形状与原话（EP4 U2-③/④ 的落盘口，**只写上面四个新键**）。
     *
     * 不碰 [KEY_LAST_CHECK]：那条由 [checkNow] 在成功路径上自己写，「上次查成功的时间」这个语义
     * 一字不动。本方法不参与任何判定：标记由调用方（UpdateUi）用 UpdateChannelReadout.outcomeOf
     * 判好、原话由它挑好，这里只做持久化。
     */
    fun rememberLastCheckResult(context: Context, outcome: String, atMillis: Long, notes: List<String>) {
        runCatching {
            prefs(context).edit()
                .putString(KEY_LAST_CHECK_OUTCOME, outcome)
                .putLong(KEY_LAST_CHECK_ATTEMPT_MILLIS, atMillis)
                .putString(KEY_LAST_CHECK_NOTES, UpdateChannelReadout.encodeNotes(notes))
                .putInt(KEY_LAST_CHECK_NOTE_COUNT, notes.size)
                .apply()
        }.onFailure { Log.w(TAG, "检查结果记不进偏好（只影响设置页摘要）", it) }
    }

    /** 盘上从没装过数据时的读数（0，与 [UpdatePolicy.NO_ASSETS_INSTALLED] 同口径）。 */
    fun installedDataVersion(gameRoot: File): Long = readVersionMarker(File(gameRoot, DATA_VERSION_FILE))

    /**
     * APK 随包解包前/后，把盘上的数据版本抬到当前整包的 versionCode（**只升不降**）。
     *
     * 为什么需要这一拍：`update/data-version.txt` 以前只由更新通道的数据包写入（[applyStagedBundleIfPresent]），
     * APK 自带的随包数据解包时一个字节都不写 ⇒ 玩家刚装完最新整包，盘上仍是「数据 0 版 / 旧包留下的旧数」。
     * 下一次检查只要清单的 assets-version 大于它（`UpdatePolicy.assetsUpdateNeeded`），就弹
     * 「游戏数据有更新」——而这份数据其实已经随 APK 落在盘上了（2026-11-06 主人反馈的形状：
     * 打出最新安装包装上后，每次启动仍弹更新窗）。
     *
     * 版本口径：`emit_update.py:2514-2516` 缺省把清单的 assets-version 对齐到 APK 的 versionCode，
     * 且随包数据与数据包从同一个语料根产出（AGENTS §8 的出包入口），所以「整包 versionCode」
     * 就是这份随包数据的版本号。发布侧若显式把 assets-version 抬得更高（数据包比整包新），
     * 这里的读数只会偏低 ⇒ 更新通道照常提示，不会漏一次真实更新。
     *
     * 只升不降：已经应用过更高版本的数据包时，装一个更早的 APK 不该把读数往回拨。
     * 调用方必须先确认「这个 APK 确实带了游戏数据」（`--skip-assets` 小包不进这一拍）——
     * 否则会把玩家手装的老数据说成新版，更新通道从此不再提醒。
     *
     * @param bundledVersion 当前安装包的 versionCode；读不出（<=0）时什么都不写
     */
    fun seedBundledDataVersion(gameRoot: File, bundledVersion: Long) {
        if (bundledVersion <= 0L) return
        if (installedDataVersion(gameRoot) >= bundledVersion) return
        writeVersionMarker(File(gameRoot, DATA_VERSION_FILE), bundledVersion)
    }

    /**
     * 把这份盘的数据版本号记进游戏根 `update/data-version.txt`（C-1 首启版本闭环）。
     *
     * 调用点只有 `GameHost.ensureAssetsInstalled` 的随包解包收尾，而且**必须**先确认盘上
     * 真有这一版号的完整摘要（`GameAssetInstaller.readDataDigest` 的 version 同值）才调：
     * `data-version.txt` 与 `data-digest.txt` 同源同值，[dataDigestTrustworthy] 那条闸才敢
     * 把差量资格放出去。没有摘要时不动标记（小包 / 旧版升上来的盘保持原样，下次全量自愈）。
     *
     * 复用 [writeVersionMarker] 的「临时名 + 改名」：半截标记会被 [readVersionMarker] 读成
     * 0 版（= 从没装过），进而把整份数据包重下一遍。
     */
    internal fun rememberInstalledDataVersion(gameRoot: File, version: Long) {
        if (version <= 0L) return
        writeVersionMarker(File(gameRoot, DATA_VERSION_FILE), version)
    }

    /** 本机盘上数据版本的 Context 便利形：下载完成后登记差量基线用（读不到按 0 = 没装过）。 */
    internal fun installedDataVersionOf(context: Context): Long = runCatching {
        installedDataVersion(GameStorage(context.applicationContext).gameRoot())
    }.getOrDefault(0L)

    /**
     * 盘上那份数据是否可信到能吃差量包：有文件级摘要、摘要与盘上相符（只 stat 不比内容）、
     * **且摘要的版本号与 `update/data-version.txt` 同值**（C-3 盘可信闸补全）。
     *
     * 没有摘要（全新装机、或上一轮没开记录）按**可信**处理还是**不可信**处理，是两种取舍：
     * 这里选「不可信 ⇒ 走全量」，因为全量是自愈的（它把包里每一条目都落一遍，落完就有摘要了），
     * 而差量在一份来历不明的盘上只会把不确定性带回家。代价是没摘要的第一次更新必须全量。
     *
     * 版本那一刀是 C-3 补的：摘要与版本标记是两份文件，任何一步只写了其中一份（随包解包
     * 前推了标记、摘要写失败、或反过来）都会留下「版本说第 N 版、摘要还是第 N-1 版」的
     * 中间态 —— 拿这种盘当差量基线，补丁拼到的是另一版语料。entries 为 0 的表同样判不可信
     * （见 [GameAssetInstaller.checkDataDigest]）。
     */
    private fun dataDigestTrustworthy(gameRoot: File): Boolean {
        val installed = installedDataVersion(gameRoot)
        val problems = runCatching {
            GameAssetInstaller.checkDataDigest(
                gameRoot,
                verifyContent = false,
                expectedVersion = installed,
            )
        }.getOrNull()
        return problems != null && problems.isEmpty()
    }

    /**
     * 本机 APK 的签名证书 SHA-256（小写无分隔）；读不出返回空串（= 不拦）。
     *
     * API 28+ 走 `GET_SIGNING_CERTIFICATES`，21–27 走旧的 `GET_SIGNATURES`。两条路都只取
     * **证书 DER** 再算 SHA-256，与发布侧 `keytool -printcert -jarfile` 的读数同口径
     * （清单里那个数就是这么算出来的）。
     *
     * L4-3：返回的是**当前章**（`apkContentsSigners` 的第一枚）——旧实现取
     * `signingCertificateHistory.firstOrNull()`，而该数组的 index 0 是**最旧**的证书、
     * 当前证书在最后一枚（官方 API 文档与 AOSP SigningInfo.java 同口径）。轮换过签名的设备
     * 会因此拿到旧章、把自家更新误判成「装不上，要先卸载（存档会没）」。完整候选集合
     * （多 signer 全部 + 历史最后一枚）见 [localCertSha256Candidates]，预检判式用它。
     */
    fun localCertSha256(context: Context): String = localCertSha256Candidates(context).firstOrNull().orEmpty()

    /**
     * L4-3：本机签名章候选集合（当前 signer 全部 + 旋转历史最后一枚）。
     *
     * 判「能不能覆盖升级」必须按集合判：多 signer 的包任一匹配即不拦；轮换过签名的设备
     * 当前章与历史最后一枚都要算数。纯判定在
     * [UpdateDownloadPlan.signerCertSha256Candidates] / [UpdateDownloadPlan.certBlocksInstallAny]。
     */
    internal fun localCertSha256Candidates(context: Context): List<String> = runCatching {
        val pkg = context.packageName
        val currentDers: List<ByteArray>
        val historyDers: List<ByteArray>
        if (Build.VERSION.SDK_INT >= 28) {
            val info = context.packageManager.getPackageInfo(
                pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val si = info.signingInfo
            currentDers = si?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
            historyDers = si?.signingCertificateHistory?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            val legacy = context.packageManager.getPackageInfo(
                pkg, android.content.pm.PackageManager.GET_SIGNATURES,
            )
            @Suppress("DEPRECATION")
            currentDers = legacy.signatures?.map { it.toByteArray() }.orEmpty()
            historyDers = emptyList()
        }
        UpdateDownloadPlan.signerCertSha256Candidates(currentDers, historyDers)
    }.getOrElse {
        Log.w(TAG, "读不出本机签名证书指纹（按不拦处理）", it)
        emptyList()
    }

    /** 记下「这次要装的整包文件名叫什么、发布方声明的章是哪个」，供 [download] 复判。 */
    private fun rememberExpectedApkCert(context: Context, apkFile: String, certSha: String) {
        val edit = prefs(context).edit()
        if (apkFile.isEmpty() || certSha.isEmpty()) {
            edit.remove(KEY_EXPECTED_APK_FILE).remove(KEY_EXPECTED_APK_CERT)
        } else {
            edit.putString(KEY_EXPECTED_APK_FILE, apkFile).putString(KEY_EXPECTED_APK_CERT, certSha)
        }
        edit.apply()
    }

    /**
     * 记下「这个基线的差量失败过」，下次同一基线直接走全量（见 [KEY_DELTA_BAD_BASE]）。
     *
     * 调用点只有 [applyStagedBundleIfPresent] 的抛错支，且判据来自 [applyBundle] 那一次探包
     * （块级与文件级两档共用同一条记账 —— core 那边 `blockDeltaUsable` 与 `deltaUsable`
     * 吃的也是同一个 `diskTrusted`）。
     */
    private fun rememberDeltaBadBase(context: Context, gameRoot: File, failedDeltaVersion: Long) {
        // 记的是**基线**而不是差量包自己的版本：判据是「盘上版本 == 基线」才会选差量，
        // 而失败后盘上版本没动，所以下次命中的还是同一个基线。
        val base = installedDataVersion(gameRoot)
        prefs(context).edit().putLong(KEY_DELTA_BAD_BASE, base).apply()
        Log.i(TAG, "差量包（版本 $failedDeltaVersion，基线 $base）应用失败已记账：同一基线下次回落全量")
    }

    /** 下载完成、等下一次启动应用的数据包；没有返回 null。 */
    fun stagedBundle(context: Context): StagedBundle? {
        val stored = prefs(context).getString(KEY_STAGED_ASSETS_PATH, "").orEmpty()
        if (stored.isEmpty()) return null
        val version = prefs(context).getLong(KEY_STAGED_ASSETS_VERSION, 0L)
        val file = File(stored)
        if (!file.isFile || version <= 0L) {
            // C-8：只清「读到时这份指针」；读完之后若已被并发登记成新的一份，留着它。
            clearStaged(context, stored)
            return null
        }
        return StagedBundle(file, version, prefs(context).getInt(KEY_APPLY_ATTEMPTS, 0))
    }

    class StagedBundle(val file: File, val version: Long, val attempts: Int)

    // ------------------------------------------------------------------
    // 应用前复检与放弃记账（C-2 / C-4）：判据抽成纯函数，纯 JVM 钉得住
    // ------------------------------------------------------------------

    /**
     * C-2 判据之一：登记时那件 artifact 的字节数与盘上现况不符 ⇒ 暂存包不可信，丢弃不应用。
     *
     * `registeredSize <= 0` = 老版本登记的指针没记过大小（升级到本版后第一次应用它）⇒ 不拦，
     * 保持「旧指针行为不变」的兼容口径。
     */
    internal fun stagedSizeReject(registeredSize: Long, actualSize: Long): Boolean =
        registeredSize > 0L && actualSize != registeredSize

    /**
     * C-2 判据之二：差量包要求盘上数据版本仍等于选件时的基线；全量包不要求（它不相对任何一版拼）。
     */
    internal fun stagedBaseReject(isDelta: Boolean, installedVersion: Long, baseVersion: Long): Boolean =
        isDelta && installedVersion != baseVersion

    /**
     * C-2：一份暂存包是不是差量档（zip 根有 `update-delta.txt`，或带 `update-blocks/` 条目）。
     *
     * 打不开归档返回 null —— 那一类交给 [applyBundle] 的抛错面报 Crashed（记账、重试），
     * 不在这里替它表态。
     */
    internal fun stagedBundleIsDelta(file: File): Boolean? = runCatching {
        ZipAssetTree(file).use { tree ->
            tree.isDeltaPackage || tree.listDir(BlockDeltaAssetTree.PATCH_ROOT_NAME).isNotEmpty()
        }
    }.getOrNull()

    /**
     * C-4：放弃一版数据包时记「目标版本 + 实际档位」，让下一轮选件的挡板读到同键、
     * 不再重下同一件（不记的话同一个坏件每轮选 → 下 → 撞，等于无限重下）。
     *
     * @return 是否记下了；档位没探出来（连归档都打不开，[BundleApply.Crashed.kind] 为 null）
     *        时记不了 —— 返回 false，调用点打一条日志认下这个空白，不写一条假账。
     */
    internal fun recordGiveUpBundle(
        sp: SharedPreferences,
        version: Long,
        kind: DataArtifactKind?,
        nowMillis: Long,
    ): Boolean {
        if (version <= 0L || kind == null || kind == DataArtifactKind.NONE) return false
        rememberRejectedBundle(sp, version, kind, nowMillis)
        return true
    }

    /**
     * 应用暂存包的结论。
     *
     * [Done] 的 `kind` 是这一趟**实际**用的那一档（判据来自包内实况，不是清单声明）——
     * 宿主把它写进启动日志，「玩家到底拿到的是块级包还是全量包」这件事必须在取证面上看得见。
     */
    sealed class ApplyOutcome {
        class Nothing : ApplyOutcome()
        class Done(
            val version: Long,
            val copiedFiles: Int,
            val copiedBytes: Long,
            val kind: DataArtifactKind,
        ) : ApplyOutcome()

        /**
         * 应用失败的三档类别（U10）：漏斗遥测第 7 拍 `failure` 维的**唯一**来源。
         *
         * 按 [applyStagedBundleIfPresent] 里实际存在的出口逐个点名，别造不存在的档：
         * 「版本不需要」（dataUpdateNeeded 不过那支）根本不应用，落的是 [Nothing]——
         * 没有应用就没有这一拍；真正走到 [Failed] 的只有拒装与抛错
         * （抛错按重试记账再分「还会再试 / 已放弃」两档）。
         *
         * [label] 是进漏斗的短标签，字符集必须过 core 的 `isStatusTag`（字母数字加 `._-`）；
         * **[Failed.problem] 的原话一个字都不许进漏斗** —— 它可能带路径与文件名。
         */
        enum class FailureCategory(val label: String) {
            /** 整包不可信被拒装（非法条目 / 补丁不合形状），已丢弃，不重试。 */
            REJECTED("rejected"),

            /** 动盘途中抛错，暂存包还在盘上，下次启动再试。 */
            CRASHED_RETRYING("crashed-retry"),

            /** 抛错且已到重试上限，丢包放弃 —— 这一版数据包彻底没应用上。 */
            CRASHED_GAVE_UP("gave-up"),
        }

        class Failed(
            val problem: String,
            val willRetry: Boolean,
            /** 这一次失败属于哪个真实出口；漏斗只吃它的 [FailureCategory.label]。 */
            val category: FailureCategory,
        ) : ApplyOutcome()
    }

    // ------------------------------------------------------------------
    // 检查
    // ------------------------------------------------------------------

    /** 一次检查的结论：有新版就带 [UpdateOffer]，没有就看 [fetchProblems]。 */
    class CheckResult(
        val offer: UpdateOffer?,
        val fetchProblems: List<String>,
        val warnings: List<String>,
    )

    /**
     * @param dataArtifact 数据通道**实际要下的那件**：core 按「块级可用则块级，否则差量可用则差量，
     *   否则全量」选的（[UpdatePolicy.dataArtifactFor] 四参形，装配点
     *   [UpdateDownloadPlan.selectDataArtifact]，契约 §1.2）。显示端要下这一件，而不是
     *   `info.assets.artifact`（那是旧键组的全量，仅用于版本比较与兼容老清单）。
     *   落在哪一档同时写进 logcat（`数据通道选件`），排查「差量静默失效」只看得到这一处。
     */
    class UpdateOffer(
        val info: UpdateInfo,
        val apkNeeded: Boolean,
        val assetsNeeded: Boolean,
        val currentVersionCode: Long,
        val installedDataVersion: Long,
        val dataArtifact: Artifact? = null,
        /**
         * 非空 = **这台设备装不上这次的整包**，原因是签名章不同（覆盖升级要求同章）。
         *
         * 为什么要在 offer 上带：玩家看到「有新版」然后点了下载，下完几百 MiB 才由系统抛
         * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，是最坏的失败形状 —— 屏上那句是英文系统文案，
         * 玩家不知道该怎么办，而正确处置（先卸载，存档会没）必须在点下载**之前**就说清。
         * 清单没写 `apk-cert-sha256` 或本设备读不出自己的章时为 null（不拦，见
         * [UpdatePolicy.certBlocksInstall]）。
         */
        val installBlockedReason: String? = null,
        /**
         * 强制更新档命中（本机 versionCode 低于清单 `min-supported-version`，
         * 判定在 [UpdatePolicy.isForceUpdateRequired]）：这次的整包提醒不可跳过 ——
         * 「以后都不」抑制不生效（checkNow 里不应用 skippedVersionCode）、弹窗不给
         * 跳过按钮、正文第一行明说必须更新。纯数据更新没有可判的整包版本，
         * 缺省 false（强制档只挂在「这台设备已被宣布停止支持」这一面上）。
         */
        val forceUpdate: Boolean = false,
        /**
         * 本机 versionName（[currentVersionName]，空串 = 读不出 → 展示端回落 versionCode）。
         *
         * 只喂弹窗正文第一行「新版本 X（本机版本 Y）」：玩家认的是 `0.2.41` 这种名字，
         * 不是裸 versionCode；判定仍只看 [currentVersionCode]。
         */
        val currentVersionName: String = "",
    )

    /**
     * 同步检查（**调用方负责放后台线程**：本方法会走网络）。
     *
     * 通道关闭时不发任何请求，直接返回空结果。
     *
     * @param skippedVersionCode 玩家已跳过的版本（`UiPrefs` 的 `update_skipped_version_code`）：
     *   只对**该 versionCode** 抑制提醒（[UpdatePolicy.isVersionSuppressed]，契约 §1.5）。
     *   自动检查传 prefs 读数；设置页「立即检查」传 0 = 无视抑制。缺省 0 保持现状行为，
     *   接线由 U-E 完成。
     */
    fun checkNow(context: Context, gameRoot: File, skippedVersionCode: Long = 0L): CheckResult {
        if (!channelEnabled(context)) return CheckResult(null, emptyList(), emptyList())
        val candidates = mirrorCandidates(context)
        if (candidates.isEmpty()) return CheckResult(null, listOf("没有可用的镜像地址"), emptyList())
        val problems = ArrayList<String>()
        var found: UpdateInfo? = null
        val nowMillis = System.currentTimeMillis()
        // 本机 versionCode 在验签分派之前就要一个读数：签名必须性（P0-2 的兼容切换点）按它判，
        // 同一次检查后面的灰度/强更/选件也用同一个读数（避免一次检查里两次读数在换版瞬间分叉）。
        val currentCode = currentVersionCode(context)
        val signedRequired = currentCode >= UpdatePolicy.SIGNED_MANIFEST_REQUIRED_FROM_APK_CODE
        // 迁移口径（契约 §2 U1）：清单带 manifest-signature ⇒ 必须验签过才可用，验不过按
        // 「该镜像清单不可用」走下一条镜像（归进 problems，带原话）；不带键 ⇒ 旧格式放行，
        // 但落一条 warning 进现有 warnings 面 —— 放行不是豁免，是给已发出去的未签名清单留命。
        // 本机已到「只接受签名清单」的版本档（signedRequired）时，未签名清单按不可用处理。
        var unsignedMirrorHost = ""
        // 只有 VERIFIED 清单才允许把它声明的 mirror 写进持久化表（P1-1 mirror 注入的消费侧闸）。
        var verifiedManifest = false
        for (mirror in candidates) {
            val text = fetchManifest(UpdatePolicy.downloadUrl(mirror, MANIFEST_FILE_NAME))
            if (text == null) {
                // 玩家面措辞：这条会经设置页 Toast 原样上屏，「清单」是我们内部的说法。
                problems += "${hostOf(mirror)}：连不上，没拿到新版信息"
                rememberMirrorHealth(context, mirror, success = false, nowMillis)
                continue
            }
            val verdict = UpdatePolicy.verifyManifestPolicy(text, signedRequired)
            // 一条源这一轮只记一次健康度，判据在 UpdatePolicy.mirrorFetchHealthy：
            // 「先把成功记下去、再在验签分支里补一次失败」等于每轮把连续失败清零，于是验签反复
            // 不过的源永远到不了降位阈值 —— 而 P1-7 要降的正是这种源。
            rememberMirrorHealth(context, mirror, success = UpdatePolicy.mirrorFetchHealthy(verdict), nowMillis)
            when (verdict) {
                ManifestSignature.Verdict.INVALID -> {
                    // 区分两种不可用：本机已到只签名的版本档、而清单压根没签名（发版人该认出来），
                    // 与带了签名但验不过（内容可能被换过）。两者处置相同（换下一条镜像），文案不同。
                    val reason = if (signedRequired && !ManifestSignature.hasSignatureLine(text)) {
                        "本版本起只接受签名清单：该源的清单没有 manifest-signature 行" +
                            "（本机 versionCode=$currentCode），已放弃这条源"
                    } else {
                        "更新信息的签名验证没通过（内容可能被换过），已放弃这条源"
                    }
                    problems += "${hostOf(mirror)}：$reason"
                }
                ManifestSignature.Verdict.UNSIGNED -> {
                    found = when (val read = UpdateManifest.parse(text)) {
                        is ManifestRead.Rejected -> {
                            problems += "${hostOf(mirror)}：${read.problems.joinToString("；")}"
                            null
                        }
                        is ManifestRead.Ready -> {
                            unsignedMirrorHost = hostOf(mirror)
                            read.info
                        }
                    }
                }
                ManifestSignature.Verdict.VERIFIED -> {
                    // 双保险（P0-1）：验签层已经拒了「签名行之后还有非空白字节」的形状，这里再按
                    // 同一条形状判一次 —— VERIFIED 分支只接受「签名行是最后一个非空行」的清单，
                    // 不让「验签管一段、解析读全文」的缺口从这个分支漏进来。
                    if (!ManifestSignature.isSignatureLineLast(text)) {
                        problems += "${hostOf(mirror)}：更新信息的签名行之后还有内容（不在签名覆盖面内），已放弃这条源"
                        null
                    } else {
                        found = when (val read = UpdateManifest.parse(text)) {
                            is ManifestRead.Rejected -> {
                                problems += "${hostOf(mirror)}：${read.problems.joinToString("；")}"
                                null
                            }
                            is ManifestRead.Ready -> {
                                verifiedManifest = true
                                read.info
                            }
                        }
                    }
                }
            }
            if (found != null) break
        }
        val info = found ?: return CheckResult(null, problems, emptyList())
        // 未签名清单的 warning 追加在这里而不是 parse 里：parse 是纯函数，不知道「这条清单
        // 来自哪个镜像」，而提醒要指名道姓（发版人认得出是哪条源没配键）。
        val effectiveWarnings = if (unsignedMirrorHost.isEmpty()) {
            info.warnings
        } else {
            info.warnings + "镜像 $unsignedMirrorHost 的清单未签名（旧格式），无法验证它有没有被换过"
        }
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        // P1-1：清单声明的 mirror 只在 VERIFIED 时进持久化。未签名清单是「还没来得及签」的历史
        // 形态，但它声明的地址没有任何完整性保护 —— 让它落盘等于给「删签名行改 mirror」留一条
        // 长长的注入尾巴（本轮候选序不受影响：candidates 在 fetch 之前就算好了）。
        if (verifiedManifest) rememberMirrors(context, info.mirrors)
        // 取到清单 = 冷启（或手动检查）的顺手回收点：装完/换版后的旧 APK、旧 zip、超期 .part。
        runCatching { cleanupStaleDownloads(context, knownArtifacts(info)) }
            .onFailure { Log.w(TAG, "清理下载目录陈旧产物失败", it) }
        // 灰度闸（P1-5）：取到清单不等于要提醒这台设备。bucket 首次在这里生成并持久化，
        // 之后同设备对同版本恒定；强制档（min-supported-version，U1 提供）命中时无视灰度直接放行。
        val bucket = UiPrefs.rolloutBucket(context)
        val minSupported = declaredMinSupportedVersion(info)
        if (!UpdatePolicy.updateSelected(info.rolloutPercentage, bucket, currentCode, minSupported)) {
            Log.i(TAG, "本设备不在本次灰度范围（percentage=${info.rolloutPercentage}, bucket=$bucket），不提醒")
            // 漏斗遥测拍 3（U6）：清单取到并读懂了，只是这台设备不在灰度里 —— 形状上算「查成功」，
            // 「有没有可更」由紧跟不跟 offer_shown 分出来，这里不发那条。
            UpdateTelemetryWiring.checkOk(context, currentCode)
            return CheckResult(null, emptyList(), info.warnings)
        }
        val installedVersion = installedDataVersion(gameRoot)
        // 强制更新档（契约 §2 U1 病②）：本机版本低于清单 min-supported-version ⇒ 这次的
        // 整包提醒不可跳过 —— 下面的「跳过此版本」抑制直接不应用（抑制是玩家对可跳版本的
        // 表态，强制档是发布方收回的支持承诺，后者压过前者）；频控不受影响（CHECK_INTERVAL_MILLIS
        // 照旧，强制档不是把检查变轮询，只是把这一次的弹窗变成给不了跳过口）。
        val forceUpdate = UpdatePolicy.isForceUpdateRequired(currentCode, info.minSupportedVersion)
        var apkNeeded = UpdatePolicy.apkUpdateNeeded(currentCode, info)
        if (apkNeeded && !forceUpdate &&
            UpdatePolicy.isVersionSuppressed(info.apk?.versionCode ?: -1L, skippedVersionCode)
        ) {
            apkNeeded = false
            Log.i(TAG, "版本 $skippedVersionCode 已被玩家跳过，本次不提醒")
        }
        val assetsNeeded = UpdatePolicy.assetsUpdateNeeded(installedVersion, info)
        // 差量资格 = core 的版本判据 AND 盘上那份数据可信（摘要在且自洽）AND 这个基线没失败过。
        // 体检只 stat 不比内容（verifyContent = false）：定时检查不该为一趟 680 MiB 读盘买单。
        val deltaBase = info.assetsBaseVersion
        val diskTrusted = dataDigestTrustworthy(gameRoot) &&
            deltaBase != prefs(context).getLong(KEY_DELTA_BAD_BASE, 0L)
        // 选件：块级 → 文件级 → 全量 的次序全在 core（四参形），app 只把块级那一件从清单读数里
        // 喂进去并留痕。档位与件一次算完、同源交出去，日志与下载才不可能各说各话。
        // U13 upd-baccount：同键挡板 —— 上一次「版本+档位」完全相同的整包被拒装过的话，
        // 这一趟不再走同一条下载路、单步回落下一档；没账或异键时与 selectDataArtifact 逐字同结论。
        // 判定在 UpdateDownloadPlan.selectDataArtifactAvoidingRejected（回落仍走 core 既有判定，
        // app 只把被拒档从候选摘掉）。
        val rejectedAccount = UiPrefs.rejectedBundleAccount(context)
        val selection = UpdateDownloadPlan.selectDataArtifactAvoidingRejected(info, installedVersion, diskTrusted, rejectedAccount)
        val dataArtifact = selection.artifact
        selection.avoidedKind?.let { avoided ->
            Log.i(
                TAG,
                "拒装挡板生效：版本 ${rejectedAccount?.version} 档位 $avoided 上次被拒，" +
                    "这次改走 ${selection.kind}（件=${dataArtifact?.fileName ?: "暂不取数据包"}）",
            )
        }
        Log.i(
            TAG,
            "数据通道选件：档位=${selection.kind}，件=${dataArtifact?.fileName ?: "（无件）"}，" +
                "盘上版本=$installedVersion，清单基线=$deltaBase，盘可信=$diskTrusted，" +
                "块级件=${info.assetsBlockDelta?.fileName ?: "清单没发"}，" +
                "文件级件=${info.assetsDelta?.fileName ?: "清单没发"}，" +
                "全量件=${UpdatePolicy.fullDataArtifact(info)?.fileName ?: "清单没发"}",
        )
        // U11 P2-13：把选件那一拍的读数落进 UiPrefs（语义 =「上一次检查选出的档位」），差量静默失效
        // 从此不只进 logcat —— 设置页展开层读的就是这份。判据全在 core（selection.kind 由
        // UpdatePolicy 判出），app 只把档位 + 盘可信 + 发布发了哪几件存下并原样读出。检查跑到这里才写，
        // 跑不到不写、不残留；「恢复默认设置」的 clear() 天然清掉，别处不写删除逻辑。
        UiPrefs.setDataArtifactReadout(
            context,
            DataArtifactReadout(
                kind = selection.kind,
                diskTrusted = diskTrusted,
                hasBlockDelta = info.assetsBlockDelta != null,
                hasFileDelta = info.assetsDelta != null,
                hasFull = UpdatePolicy.fullDataArtifact(info) != null,
            ),
        )
        // U13 upd-baccount：挡板生效读数（上屏展开层「上次同版本被拒、这次改走 X / 暂不取数据包」
        // 的依据）。与 U11 档位读数同拍：检查跑到选件才写；挡板没生效就摘掉，不让旧说明残留到
        // 新版本。账上版本即被拒版本（挡板只在同键生效），异键时读数清空。
        if (selection.avoidedKind != null && rejectedAccount != null) {
            rememberRejectedFallback(prefs(context), rejectedAccount, selection.kind)
            Log.i(TAG, "挡板读数已记：被拒版本 ${rejectedAccount.version}，这次改走 ${selection.kind}")
        } else {
            UiPrefs.clearRejectedBundleFallback(context)
        }
        // 覆盖升级预检：只在「这次真要换整包」时判，数据包（zip）不涉及安装器互斥。
        val apkFile = info.apk?.artifact?.fileName.orEmpty()
        val blocked = if (apkFile.isNotEmpty() &&
            UpdatePolicy.certBlocksInstall(localCertSha256(context), info.apkCertSha256)
        ) {
            "这台设备上装的是另一套签名章的包（内测/旧包），新包无法覆盖升级。" +
                "要换过来必须先卸载旧包 —— **存档与已解包的游戏数据会一起没了**。"
        } else {
            null
        }
        rememberExpectedApkCert(context, apkFile, info.apkCertSha256)
        // 漏斗遥测拍 3（U6）：清单形状的两格都在这道闸上判出来 —— 「查成功」发一条 check_ok
        // （维度只有本机版本可读，「找到 / 没找到」由后面跟不跟 offer_shown 分开），
        // 「几条源报障」这一格在 core 的事件模型里没有维度位可放（见交付的需组长接线）。
        UpdateTelemetryWiring.checkOk(context, currentCode)
        if (!apkNeeded && !assetsNeeded) return CheckResult(null, emptyList(), effectiveWarnings)
        return CheckResult(
            UpdateOffer(
                info, apkNeeded, assetsNeeded, currentCode, installedVersion, dataArtifact,
                installBlockedReason = blocked,
                forceUpdate = forceUpdate,
                currentVersionName = currentVersionName(),
            ),
            emptyList(),
            effectiveWarnings,
        )
    }

    /**
     * 取一份清单文本。任何失败都返回 null 交给上层换下一条镜像：非 2xx、重定向掉出 https、
     * 超过 [UpdateManifest.MAX_MANIFEST_BYTES]、IO 异常。
     *
     * 检查最终 URL 是刻意的：`instanceFollowRedirects` 默认跟随重定向，https → http 的跳转
     * 会被跟，跟着就走上一条明文信道 —— 那正是明文降级该被拦住的地方。
     *
     * 判式必须排在 `responseCode` **之后**：`connect()` 只建连、不读响应头，重定向是读响应码
     * 时才会被跟随的，那时 `conn.url` 才变成最终地址（2026-11-06 用本地 302 服务器实测：
     * `connect()` 后仍是原 URL，`responseCode` 后才变）。排在 `connect()` 后等于永远判原始
     * https 地址、这道闸形同虚设。
     */
    private fun fetchManifest(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConnection(url, MANIFEST_TIMEOUT_MS, MANIFEST_TIMEOUT_MS)
            conn.requestMethod = "GET"
            conn.connect()
            // 先读响应码（触发重定向跟随），再判最终 URL 是否仍为 https。
            val code = conn.responseCode
            if (!isHttps(conn.url.toString())) return null
            if (code !in 200..299) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > UpdateManifest.MAX_MANIFEST_BYTES) return null
                    out.write(buffer, 0, n)
                }
                out.toString("UTF-8")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "清单取不到：$url（${t.javaClass.simpleName}: ${t.message}）")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 下载（整包与数据包共用一条管线：续传 + 退避重试 + 逐镜像回退）
    // ------------------------------------------------------------------

    /** 单飞闸：两条下载同时跑就是往同一个目录写两份几百 MiB。 */
    private val downloading = AtomicBoolean(false)

    /**
     * L4-8：下载目录的**清理/抢闸互斥锁**。
     *
     * 单飞闸只防两条下载，不防「清理线程正在删目录」与「下载线程刚建 `.part`」这对组合：
     * 旧实现的 `cleanupStaleDownloads` 只在入口看一眼 `downloading`，之后列目录/删文件期间
     * 下载可以抢闸开写 —— 刚建的 0 字节 `.part` 会被 [UpdateDownloadPlan.planCleanup] 当
     * 「空桩」删掉，在飞下载写进一个已 unlink 的 inode，最后 `renameTo` 失败、整包白下。
     *
     * 互斥点选在「抢闸」与「清理全程」：`tryBeginDownload` 在锁内抢闸，清理在锁内先复核
     * `downloading` 再列目录/删文件。于是只有两种时序：清理先拿到锁（此时下载还没抢闸，
     * 目录里不可能有本次的 `.part`）或下载先抢到闸（清理复核即 no-op）。释放闸不必持锁。
     */
    private val downloadDirLock = Any()

    /**
     * 一条下载的结果：[file] 非空即「已落盘且核对通过」。
     *
     * [kind] 只在管线内部（[download] 的重试循环）消费；屏上只读 [problem]。
     */
    class DownloadOutcome(
        val file: File?,
        val problem: String,
        val kind: UpdateDownloadPlan.FailureKind? = null,
    ) {
        val ok: Boolean get() = file != null

        /** 这条失败值不值得在**同一条镜像**上退避再试（分类判据在 [UpdateDownloadPlan.decideFailure]）。 */
        val retrySameMirror: Boolean get() = kind != null && UpdateDownloadPlan.retryOnSameMirror(kind)
    }

    /**
     * 抢下载权：返回 false = 已经有一条在跑，调用方（[UpdateUi]）该把按钮保持置灰。
     * 跑完必须 [releaseDownload]。
     */
    fun tryBeginDownload(): Boolean = synchronized(downloadDirLock) {
        downloading.compareAndSet(false, true)
    }

    fun releaseDownload() {
        downloading.set(false)
    }

    /**
     * 按候选镜像逐条下载 [artifact]，核对字节数与 SHA-256 之后才改名成正式名。
     *
     * 每条镜像上的循环：失败 → 分类（[UpdateDownloadPlan.decideFailure]）→ 可重试的按
     * [UpdatePolicy.retryBackoffMillis] 退避后**原地续传**（`.part` 认领，不重头下）；
     * 档位走完或遇到「换镜像类」失败就推进到下一条。**等待发生在调用线程上**——本方法必须
     * 跑在后台线程（[UpdateUi] 的 daemon 线程），最坏一条源 1+2+5+15+30 = 53 秒。
     *
     * 空间闸按**还差的字节**（续传不是重新下载）与随 size 缩放的余量判
     * （[UpdateDownloadPlan.spaceOk]），不再对所有包一刀切 512 MiB。
     *
     * @param onProgress 每写一块回调一次（跑在调用线程上，UI 侧自己节流）；
     *   计数是**累计绝对字节**（含续传认领的盘上前缀），与 [Artifact.sizeBytes] 同基准
     */
    fun download(context: Context, artifact: Artifact, onProgress: (Long, Long) -> Unit): DownloadOutcome {
        val candidates = mirrorCandidates(context)
        if (candidates.isEmpty()) return DownloadOutcome(null, "没有可用的镜像地址")
        // 覆盖升级预检（第二道，给没读 offer 字段的调用方兜底）：正在下的是清单说的那个整包、
        // 而本机章与发布方声明的章不同 ⇒ 直接拒，一行字节都不下。
        val expectedFile = prefs(context).getString(KEY_EXPECTED_APK_FILE, "").orEmpty()
        val expectedCert = prefs(context).getString(KEY_EXPECTED_APK_CERT, "").orEmpty()
        if (expectedFile.isNotEmpty() && artifact.fileName == expectedFile &&
            UpdateDownloadPlan.certBlocksInstallAny(localCertSha256Candidates(context), expectedCert)
        ) {
            return DownloadOutcome(
                null,
                "这台设备的签名章与更新包不同，装不上（要先卸载旧包，存档会没）——已阻止下载",
            )
        }
        val dir = runCatching { downloadDir(context) }.getOrNull()
            ?: return DownloadOutcome(null, "无法创建下载目录", UpdateDownloadPlan.FailureKind.LocalFileSystem)
        val partState = readPartState(File(dir, artifact.fileName + UpdateDownloadPlan.PART_SUFFIX))
        val free = usableSpace(context)
        // L4-9：整包在 29+ 还要被安装会话再拷一份进 /data/app-staging，空间闸要把那份副本算进来。
        val sessionCopyBytes = if (UpdateDownloadPlan.isApkArtifact(artifact.fileName)) artifact.sizeBytes else 0L
        if (!UpdateDownloadPlan.spaceOk(free, artifact, partState, sessionCopyBytes)) {
            val remaining = UpdateDownloadPlan.remainingBytes(artifact, partState)
            val headroom = UpdateDownloadPlan.requiredHeadroomBytes(artifact.sizeBytes, sessionCopyBytes)
            val resumeNote = if (remaining < artifact.sizeBytes) {
                "（已有断点，只需再拉 ${UpdatePolicy.sizeLabel(remaining)}）"
            } else {
                ""
            }
            return DownloadOutcome(
                null,
                "空间不够：${artifact.fileName} 共 ${UpdatePolicy.sizeLabel(artifact.sizeBytes)}" +
                    "$resumeNote，还要留 ${UpdatePolicy.sizeLabel(headroom)} 余量，" +
                    "盘上只剩 ${UpdatePolicy.sizeLabel(free)}",
                UpdateDownloadPlan.FailureKind.LocalFileSystem,
            )
        }
        var lastProblem = "取不到文件"
        for (mirror in candidates) {
            val url = UpdatePolicy.downloadUrl(mirror, artifact.fileName)
            var attempt = 0
            while (true) {
                val outcome = downloadOnce(dir, url, artifact, onProgress)
                if (outcome.ok) {
                    // L4-5：整包在交给安装器之前复核「下载到的包实际用哪把钥匙签的」。
                    // 清单没声明章（expectedCert 空）= 跳过；不符/读不出 ⇒ 删包、换下一条源。
                    val certProblem = verifyDownloadedApkCert(
                        context,
                        outcome.file!!,
                        declaredCertSha256 = if (artifact.fileName == expectedFile) expectedCert else "",
                    )
                    if (certProblem == null) {
                        rememberMirrorHealth(context, mirror, success = true, System.currentTimeMillis())
                        return outcome
                    }
                    lastProblem = "${hostOf(mirror)}：$certProblem"
                    break // 镜像上放的是错包：同一条源再试没有意义，换下一条
                }
                lastProblem = "${hostOf(mirror)}：${outcome.problem}"
                if (!outcome.retrySameMirror) break
                val waitMillis = UpdateDownloadPlan.backoffMillisOrFailOver(attempt)
                    ?: break // 档位用完：这条镜像的这轮到此为止，换下一条
                Log.i(TAG, "第 ${attempt + 1} 次失败（${hostOf(mirror)}），${waitMillis}ms 后续传重试：${outcome.problem}")
                try {
                    Thread.sleep(waitMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return DownloadOutcome(null, "下载被中断（断点已保留，下次可续传）")
                }
                attempt++
            }
            // 这条镜像这轮没成（档位用完或换镜像类失败）：记一次连续失败，下一轮降位。
            // 被中断（InterruptedException 分支直接 return）不算源失联，不记。
            rememberMirrorHealth(context, mirror, success = false, System.currentTimeMillis())
        }
        return DownloadOutcome(null, lastProblem)
    }

    /**
     * 在一条镜像地址上跑一次下载尝试。**不抛异常**：一切失败都编成带 [FailureKind] 的
     * [DownloadOutcome] 交给 [download] 的循环判去留。
     *
     * `.part` 的处理纪律（契约 §1.4）：
     * - 网络类失败**保留** `.part`（那几百 MB 就是续传的本钱，进程死了也在）；
     * - 超期 / 超长 / sha 不符**删除** `.part`（内容不可信，留着只会缝出 sha 必错的产物）；
     * - 服务端不认 Range（回 200 或 Content-Range 对不上）时**截断从头重写**，
     *   代价是作废已下载前缀——不这样换来的是「每次重试都失败在最末尾」的缝合文件，
     *   这条不是洁癖是正确性（见 [UpdateDownloadPlan.mustRewriteFromScratch]）。
     */
    private fun downloadOnce(
        dir: File,
        url: String,
        artifact: Artifact,
        onProgress: (Long, Long) -> Unit,
    ): DownloadOutcome {
        val part = File(dir, artifact.fileName + UpdateDownloadPlan.PART_SUFFIX)
        val target = File(dir, artifact.fileName)
        if (target.isFile) {
            // 已经有一份核对通过的就直接用：数据包应用失败重试时不必重下几百 MiB。
            if (target.length() == artifact.sizeBytes && sha256Of(target) == artifact.sha256Hex) {
                return DownloadOutcome(target, "")
            }
            if (!target.delete()) {
                return failure(UpdateDownloadPlan.FailureKind.LocalFileSystem, "旧文件删不掉：${target.name}")
            }
        }
        // 认领上个进程（或上一次尝试）留下的 .part：这是「进程被回收、冷启接着来」的入口。
        var partState = readPartState(part)
        if (UpdateDownloadPlan.isPartCompleteFor(artifact, partState)) {
            // 字节数已经齐（下完但 rename 前被打断）：直接核 sha，一个字节都不用再出网。
            val actual = sha256Of(part)
            if (actual == artifact.sha256Hex) {
                return if (part.renameTo(target)) {
                    DownloadOutcome(target, "")
                } else {
                    failure(UpdateDownloadPlan.FailureKind.LocalFileSystem, "无法落盘：${target.name}")
                }
            }
            part.delete()
            partState = null
        }
        val offset = UpdateDownloadPlan.resumeOffsetFor(artifact, partState)
        if (partState != null && offset <= 0L) {
            // 超期或字节数超过声明：整段旧前缀作废重来（契约 §1.4 的两条信任判据）。
            part.delete()
        }
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection(url, DOWNLOAD_CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS)
            conn.requestMethod = "GET"
            if (offset > 0L) conn.setRequestProperty("Range", "bytes=$offset-")
            conn.connect()
            // 先读响应码：重定向在读到响应时才被跟随，此刻 conn.url 才是最终地址；
            // 判式排在 connect() 后只会看到原始 https 地址，等于没拦（见 fetchManifest 的注释）。
            val code = conn.responseCode
            if (!isHttps(conn.url.toString())) {
                return failure(UpdateDownloadPlan.FailureKind.PlainHttp, "地址被转成了明文，已放弃")
            }
            if (code !in 200..299) {
                val kind = UpdateDownloadPlan.httpStatusKindFor(code)
                if (UpdateDownloadPlan.mustDiscardPartForHttpStatus(code)) {
                    // 416 = 请求的断点偏移已越过远端对象末尾（远端被截断/换内容）：这份 .part
                    // 不能再当续传本钱，否则换哪条源都是同一个越界 Range、原地退避 53 秒也白费。
                    val removed = part.delete()
                    Log.w(TAG, "HTTP 416，作废断点 .part（删除=$removed）：${artifact.fileName}，换下一条源从头下")
                }
                return failure(kind, "HTTP $code")
            }
            val contentRangeStart = UpdateDownloadPlan.contentRangeStart(conn.getHeaderField("Content-Range"))
            // L8 接线：生产下载的「是否从头重写」由纯判定层的 mustRewriteFromScratch 决定。
            // 它与 resumeTrusted 是同一份语义的正反面（mustRewrite = offset>0 且 !resumeTrusted），
            // 于是 `resume == resumeTrusted` 逐字等价；两个读数都从本行算出的 contentRangeStart 来，
            // 不再各算一份（判式只有一处，单测钉的就是这一处）。
            val mustRewrite = UpdateDownloadPlan.mustRewriteFromScratch(offset, code, contentRangeStart)
            val resume = !mustRewrite && UpdateDownloadPlan.resumeTrusted(offset, code, contentRangeStart)
            if (mustRewrite) {
                Log.w(TAG, "服务端不认续传请求（HTTP $code），从头重写整个文件：${artifact.fileName}（作废 ${offset} 字节前缀）")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            // resume=false 时 FileOutputStream 会截断旧 .part —— 「从头重写」靠的就是这个 truncate。
            FileOutputStream(part, resume).use { out ->
                conn.inputStream.use { input ->
                    if (resume) {
                        // 边下边算的口径不因续传改变：盘上前缀先进 digest，再从网络续着喂。
                        digestPrefix(digest, part, offset)
                        written = offset
                    }
                    val buffer = ByteArray(GameAssetInstaller.BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        written += n
                        if (written > artifact.sizeBytes) {
                            throw DownloadAbort(UpdateDownloadPlan.FailureKind.OversizeStream, "实收字节数超过清单声明", discardPart = true)
                        }
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        onProgress(written, artifact.sizeBytes)
                    }
                }
                // L4-4：先把数据真正刷到盘上，再算摘要/rename。close() 只保证进了内核页缓存，
                // 断电/崩溃时 rename 过的 target 可能只有半截 —— 而安装/解包两条下游都不复验 sha。
                // 与安装会话那条路（PackageInstallerSession 的 session.fsync）对齐。
                try {
                    out.flush()
                    out.fd.sync()
                } catch (io: IOException) {
                    throw DownloadAbort(
                        UpdateDownloadPlan.FailureKind.LocalFileSystem,
                        "落盘 fsync 失败：${io.javaClass.simpleName}",
                        discardPart = false,
                    )
                }
            }
            if (written != artifact.sizeBytes) {
                // 流被掐断但盘上前缀完好：**保留** .part，退避后从这一字节续（不重头下）。
                throw DownloadAbort(
                    UpdateDownloadPlan.FailureKind.Network,
                    "连接中断：已收 $written / 声明 ${artifact.sizeBytes} 字节，断点已保留",
                    discardPart = false,
                )
            }
            val actual = hex(digest.digest())
            if (actual != artifact.sha256Hex) {
                part.delete()
                return failure(
                    UpdateDownloadPlan.FailureKind.DigestMismatch,
                    "摘要不符（源上的文件被换过或下载被截断）：声明 ${artifact.sha256Hex.take(16)}…，" +
                        "实收 ${actual.take(16)}…",
                )
            }
            if (!part.renameTo(target)) {
                part.delete()
                return failure(UpdateDownloadPlan.FailureKind.LocalFileSystem, "无法落盘：${target.name}")
            }
            return DownloadOutcome(target, "")
        } catch (abort: DownloadAbort) {
            if (abort.discardPart) part.delete()
            return failure(abort.kind, abort.message)
        } catch (t: Throwable) {
            Log.w(TAG, "下载异常：$url", t)
            // 网络/IO 异常：.part 里的字节仍是本次响应的前缀，保留它等于保留续传点。
            return failure(UpdateDownloadPlan.FailureKind.Network, "${t.javaClass.simpleName} ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 下载目录的**陈旧产物回收口**（契约 U-B 第 3/5 条）：
     * 1. 已交给安装器、且之后版本真的升上去了的 APK（几百 MiB 的残留主要就指它）；
     * 2. 与当前清单无关的正式文件与 `.part`、超期/超长的 `.part`（点名判据在
     *    [UpdateDownloadPlan.planCleanup]）。
     *
     * 正在下载时（[downloading] 置位）整体 no-op——回收不许去动一条活着写入的目录。
     * L4-8：判断与删除全程持 [downloadDirLock]，与 [tryBeginDownload] 互斥（理由见锁的 KDoc），
     * 不再是「入口看一眼」的窄窗口。
     * 返回删除的文件数。[checkNow] 每次取到清单会自己调用一次；设置页要个「立即清理」也用它。
     */
    fun cleanupStaleDownloads(context: Context, keepArtifacts: List<Artifact>): Int = synchronized(downloadDirLock) {
        // L4-8：锁内复核 —— 与 tryBeginDownload 共用同一把锁，所以走到这里为 false 时，
        // 下载在本段结束前不可能抢到闸、目录里也不可能有本次正在写的 .part。
        if (downloading.get()) return 0
        var removed = runCatching { pruneHandedOffApk(context) }.getOrDefault(0)
        val dir = runCatching { downloadDir(context) }.getOrNull() ?: return removed
        val now = System.currentTimeMillis()
        val entries = runCatching {
            dir.listFiles()?.filter { it.isFile }?.map {
                UpdateDownloadPlan.DirEntry(it.name, it.length(), now - it.lastModified())
            } ?: emptyList()
        }.getOrDefault(emptyList())
        if (entries.isEmpty()) return removed
        // 已登记待应用的暂存数据包必须保名：清单换代时它是 prefs 指针指着的那件，
        // 当残留删掉等于把「下次启动自动应用」变成「重下整包」。
        val extraKeep = HashSet<String>()
        stagedBundle(context)?.file?.takeIf { it.isFile }?.let { extraKeep += it.name }
        for (name in UpdateDownloadPlan.planCleanup(entries, keepArtifacts, extraKeep)) {
            if (runCatching { File(dir, name).delete() }.getOrDefault(false)) {
                removed++
                Log.i(TAG, "回收陈旧下载产物：$name")
            }
        }
        return removed
    }

    /**
     * 把下载好的数据包登记成「下次启动时应用」；只登记指针，不在前台动游戏根。
     *
     * C-2：连同这件 artifact 的 **size/sha** 与**选件时盘上的数据版本（差量基线）**一起落盘，
     * 供 [applyStagedBundleIfPresent] 在动盘之前复检「下的还是当初选的那件吗、盘还停在基线吗」。
     * [artifact] 是清单声明、下载侧已按其 sha 核对过的那件，这里不重算 600 MiB；
     * 全量包不需要基线 —— 登记时按**包内实况**判一次档，非差量一律记 0。
     *
     * @param baseVersion 选件时盘上的 `installedDataVersion`（差量包用）；全量包/判不出档时忽略
     */
    fun stageAssetsBundle(
        context: Context,
        file: File,
        version: Long,
        artifact: Artifact,
        baseVersion: Long = 0L,
    ) {
        // U13 upd-baccount：换版本暂存成功 ⇒ 上一版的拒装账作废（挡板比对的是「版本+档位」，
        // 新版本的键与账上不同，账留着只是脏数据）。同版本暂存不清：那多半是挡板回落档的包，
        // 账还得盯着原键，直到应用成功（清）或再拒（覆盖写）。
        val rejected = UiPrefs.rejectedBundleAccount(context)
        if (rejected != null && rejected.version != version) {
            UiPrefs.clearRejectedBundleState(context)
            Log.i(TAG, "暂存版本 $version 与拒装账 ${rejected.version} 不同键，旧账作废")
        }
        val persistedBase = if (stagedBundleIsDelta(file) == true) baseVersion else 0L
        prefs(context).edit()
            .putString(KEY_STAGED_ASSETS_PATH, file.absolutePath)
            .putLong(KEY_STAGED_ASSETS_VERSION, version)
            .putInt(KEY_APPLY_ATTEMPTS, 0)
            .putLong(KEY_STAGED_ASSETS_SIZE, artifact.sizeBytes)
            .putString(KEY_STAGED_ASSETS_SHA, artifact.sha256Hex)
            .putLong(KEY_STAGED_ASSETS_BASE, persistedBase)
            .apply()
        Log.i(
            TAG,
            "数据包已暂存，待下次启动应用：${file.name}（版本 $version，大小 ${artifact.sizeBytes} B，" +
                "sha=${artifact.sha256Hex.take(12)}…，差量基线=$persistedBase）",
        )
    }

    /**
     * 装载前调用（`GameHost.ensureAssetsInstalled` 的解包之后）：有暂存包就应用到游戏根。
     *
     * 用**更新档**的解包器（`forceReplace = true`）：同长的新立绘必须换掉，理由见
     * [GameAssetInstaller.forceReplace]。成本与首启解包同级，所以只能跑在后台解包线程上。
     *
     * 失败不抛：暂存指针留在盘上等下次启动重试；连 [MAX_APPLY_ATTEMPTS] 次失败就丢包。
     *
     * 本方法只管**记账那一半**（次数、坏基线、丢包、清指针），落地本身交给 [applyBundle]：
     * 后者不吃 Context，于是「这一趟用的是哪棵树、失败该不该记坏基线」这两条路由判据
     * 能在纯 JVM 上钉死（`UpdateBlockDeltaWireTest`），不必装一套 Android 装置。
     *
     * ## 动盘之前的三条复检（C-2 / C-8，判据都是纯函数）
     *
     * 下载完成与这次应用之间隔着换镜像、换版、进程被回收，所以登记时落盘的读数要在这里再核：
     * - 暂存包大小与登记时不符 ⇒ 丢弃（见 [stagedSizeReject]）；
     * - 差量包要求盘上版本仍等于选件时的基线 ⇒ 不等就丢弃（见 [stagedBaseReject]；全量包不要求）；
     * - 应用/丢弃收尾清指针只清**本次处理的那一份**（见 [clearStaged]）：并发登记进来的新版本要留着。
     */
    fun applyStagedBundleIfPresent(context: Context, gameRoot: File): ApplyOutcome {
        val staged = stagedBundle(context) ?: return ApplyOutcome.Nothing()
        val installed = installedDataVersion(gameRoot)
        if (!UpdatePolicy.dataUpdateNeeded(staged.version, installed)) {
            // 新装的 APK 已经带了同等或更新的数据，这份暂存包属于旧版 ⇒ 丢掉，不降级。
            Log.i(TAG, "暂存数据包版本 ${staged.version} 不高于盘上 $installed，丢弃")
            staged.file.delete()
            clearStaged(context, staged.file.absolutePath)
            return ApplyOutcome.Nothing()
        }
        // C-2 应用前复检：下载与这次应用之间隔着换镜像、换版、进程被回收。两条闸都在
        // [applyBundle] **动盘之前**判，依据是登记时落盘的读数（见 [stageAssetsBundle]）：
        // ① 盘上这件不是登记时那件（大小不符）⇒ 丢弃：拿半截/被替换过的 zip 去动盘，最坏是
        //    解包器半路抛错、白占一次重试名额，或把「另一件」当这一版落下去；
        // ② 差量包必须仍停在选件时的基线上：盘上版本已经变了（随包解包前推了标记、或另一份
        //    更新先落了）⇒ 这份补丁拼的是另一版语料，丢弃不应用。全量包不要求基线。
        val sp = prefs(context)
        val registeredSize = sp.getLong(KEY_STAGED_ASSETS_SIZE, 0L)
        val stagedSize = staged.file.length()
        if (stagedSizeReject(registeredSize, stagedSize)) {
            Log.w(TAG, "暂存包大小与登记时不符（登记 $registeredSize B，盘上 $stagedSize B）⇒ 丢弃不应用")
            staged.file.delete()
            clearStaged(context, staged.file.absolutePath)
            return ApplyOutcome.Nothing()
        }
        val stagedIsDelta = stagedBundleIsDelta(staged.file)
        val baseVersion = sp.getLong(KEY_STAGED_ASSETS_BASE, 0L)
        if (stagedBaseReject(stagedIsDelta == true, installed, baseVersion)) {
            Log.w(
                TAG,
                "暂存差量包的基线与盘上不符（选件时 $baseVersion，现在 $installed）⇒ 丢弃不应用" +
                    "（下一轮检查会按盘上现状重选）",
            )
            staged.file.delete()
            clearStaged(context, staged.file.absolutePath)
            return ApplyOutcome.Nothing()
        }
        return when (val applied = applyBundle(staged.file, gameRoot, staged.version)) {
            is BundleApply.RejectedBundle -> {
                // 非法条目名（zip-slip）与不合格补丁：整包不可信 ⇒ 丢弃不重试。
                // **不**记坏基线：盘上一个字节都没动，那份数据仍然自洽 —— 记了会把本来能用两档
                // 差量的盘白白废掉。
                // U13 upd-baccount：但要在账上落一条「这个版本的这个包被拒过」（版本+档位+时刻），
                // 下一轮检查决策读到同「版本+档位」就不再走同一条下载路、单步回落下一档 ——
                // 不记账的话同一个坏件每轮重下重拒（旧注释里的「已知口子」，本路收掉）。
                // 记账点唯一：只在 REJECTED 档写；crashed/gave-up 的暂存还在盘上会重试，不写这条账。
                rememberRejectedBundle(prefs(context), staged.version, applied.kind, System.currentTimeMillis())
                Log.i(
                    TAG,
                    "拒装已记账：版本 ${staged.version} 档位 ${applied.kind} —— " +
                        "下次检查同「版本+档位」不再走同一条下载路",
                )
                staged.file.delete()
                clearStaged(context, staged.file.absolutePath)
                ApplyOutcome.Failed(
                    rejectedProblem(applied.rejectedEntryCount, applied.kind),
                    willRetry = false,
                    category = ApplyOutcome.FailureCategory.REJECTED,
                )
            }

            is BundleApply.Applied -> {
                val result = applied.result
                // blockedDeletions 非 0 = 发布侧那张删除表与设备侧口径不符：数据是好的，
                // 只是该删的旧文件还留在盘上。必须留痕，否则这种偏差永远不会被发现。
                if (result.blockedDeletions > 0) {
                    Log.w(TAG, "差量删除表有 ${result.blockedDeletions} 行没执行（越界/撞保护名单/包内自带）" +
                        "，本轮删掉 ${result.deletedFiles} 个文件 —— 请回传发布侧核对")
                }
                result.recordProblem?.let {
                    // 摘要没写下去只影响「下一轮能不能走差量」，不影响本轮数据正确性。
                    Log.w(TAG, "文件级摘要没写成（下轮将回落全量）：$it")
                }
                // U13 upd-baccount：应用成功 ⇒ 拒装账与挡板读数一并清（这版这个包不再是坏件，
                // 挡板没有再拦的理由）。
                UiPrefs.clearRejectedBundleState(context)
                writeVersionMarker(File(gameRoot, DATA_VERSION_FILE), staged.version)
                staged.file.delete()
                clearStaged(context, staged.file.absolutePath)
                Log.i(
                    TAG,
                    "数据包应用完成：档位 ${applied.kind}，版本 ${staged.version}，写入 ${result.copiedFiles} 个文件 " +
                        "${result.copiedBytes / (1024 * 1024)} MiB，sha 核对=${result.programSha256Verified}",
                )
                ApplyOutcome.Done(staged.version, result.copiedFiles, result.copiedBytes, applied.kind)
            }

            is BundleApply.Crashed -> {
                val attempts = staged.attempts + 1
                prefs(context).edit().putInt(KEY_APPLY_ATTEMPTS, attempts).apply()
                // 只有**差量档**（文件级与块级同一判据）才记坏基线：记一次两档一起关死，
                // 下一拍 diskTrusted = false ⇒ 回落全量，不会留下半份语料、也不再白下一遍。
                // 全量档本来就没有「回落」这回事，记它只会废掉下一次能用的差量。
                if (applied.recordBadBase) {
                    rememberDeltaBadBase(context, gameRoot, staged.version)
                }
                val giveUp = attempts >= MAX_APPLY_ATTEMPTS
                if (giveUp) {
                    staged.file.delete()
                    clearStaged(context, staged.file.absolutePath)
                    // C-4：放弃也要记账 —— 否则下一轮检查还会选「同版本+同档位」的那件，
                    // 把同一场失败重演一遍（无限重下）。挡板读的就是这条账（版本+档位）。
                    // 档位没探出来（连归档都打不开）时记不了：不写假账，只把空白打进日志。
                    if (recordGiveUpBundle(prefs(context), staged.version, applied.kind, System.currentTimeMillis())) {
                        Log.i(
                            TAG,
                            "放弃的数据包已记账：版本 ${staged.version} 档位 ${applied.kind} —— " +
                                "下轮检查同键回落下一档，不再重下同一件",
                        )
                    } else {
                        Log.i(TAG, "放弃的数据包档位没探出来，无法记账：下轮仍可能选同一件（探包失败面）")
                    }
                }
                val head = "数据包应用失败（第 $attempts 次，档位 ${applied.kind ?: "未探出"}）"
                val cause = applied.cause
                if (cause != null) Log.w(TAG, head, cause) else Log.w(TAG, head)
                ApplyOutcome.Failed(
                    "${applied.problem}" +
                        if (giveUp) "，已放弃这个数据包" else "，下次启动再试",
                    willRetry = !giveUp,
                    category = if (giveUp) ApplyOutcome.FailureCategory.CRASHED_GAVE_UP
                    else ApplyOutcome.FailureCategory.CRASHED_RETRYING,
                )
            }
        }
    }

    /**
     * 拒装的原话：**非块级档时与改前逐字同文**（这条会进日志与设置页摘要，老路径一个字都不该动）。
     *
     * 块级档要说的是「补丁形状也不合格」—— [BlockDeltaAssetTree.rejectedEntryCount] 含索引阶段
     * 丢出的补丁条数，只说「非法路径」会把一类不同的坏包说歪。
     */
    internal fun rejectedProblem(rejectedEntryCount: Int, kind: DataArtifactKind?): String =
        if (kind == DataArtifactKind.BLOCK_DELTA) {
            "块级差量包里有 $rejectedEntryCount 条非法路径或不合形状的补丁条目，已拒绝应用并丢弃该包"
        } else {
            "数据包里有 $rejectedEntryCount 条非法路径，已拒绝应用并丢弃该包"
        }

    /**
     * 拒装出口的记账（U13）：被拒的目标数据版本 + 档位 + 时刻一次落盘。
     *
     * 吃 [SharedPreferences] 而不是 [Context]，纯落盘无日志（[UiPrefs] 注入层同形，纯 JVM 可测）；
     * 调用点只有 `applyStagedBundleIfPresent` 的 REJECTED 支（记账点唯一），留痕日志在调用点写。
     * 时刻由调用方传（[System.currentTimeMillis]），本函数不取系统时间，测试钉得住值。
     */
    internal fun rememberRejectedBundle(sp: SharedPreferences, version: Long, kind: DataArtifactKind, nowMillis: Long) {
        UiPrefs.writeRejectedBundleAccount(sp, RejectedBundleAccount(version, kind, nowMillis))
    }

    /**
     * 挡板生效读数的记账（U13）：检查决策挡板生效那一刻写「被拒版本 + 这次改走的档位」，
     * 上屏展开层那行说明读的就是它。与 [rememberRejectedBundle] 同形吃 [SharedPreferences]、
     * 纯落盘无日志；挡板没生效的清侧在 checkNow 直接走 [UiPrefs.clearRejectedBundleFallback]。
     */
    internal fun rememberRejectedFallback(sp: SharedPreferences, account: RejectedBundleAccount, fallbackKind: DataArtifactKind) {
        UiPrefs.writeRejectedBundleFallback(sp, RejectedBundleFallback(account.version, fallbackKind))
    }

    /**
     * [applyBundle] 的结论：路由判完之后的三种去向（不带 prefs、不带 Context，纯 JVM 可测）。
     */
    internal sealed class BundleApply {

        /** 树自己报的非法条目数大于 0 ⇒ 整包拒装（既有那道闸，含块级补丁的形状数）。 */
        class RejectedBundle(val rejectedEntryCount: Int, val kind: DataArtifactKind) : BundleApply()

        /** 已落盘。 */
        class Applied(val kind: DataArtifactKind, val result: GameAssetInstaller.Result) : BundleApply()

        /**
         * 动盘途中抛错。
         *
         * @param kind 已经探出来的档位；包连归档都打不开时为 null
         * @param recordBadBase 这一档失败要不要记坏基线（差量档要，全量档不要，探不出形态不要）
         * @param problem 可直接上屏/进日志的原话（与改前同形：`message ?: simpleName`）
         * @param cause 原始异常，只给日志面留栈，不参与任何判定
         */
        class Crashed(
            val kind: DataArtifactKind?,
            val recordBadBase: Boolean,
            val problem: String,
            val cause: Throwable?,
        ) : BundleApply()
    }

    /**
     * 把一份**已按清单摘要核对过**的暂存包落到游戏根（本文件唯一动游戏根数据的地方）。
     *
     * ## 一次探包 → 路由 → 一道闸
     *
     * 三件读数（非法条目数、有没有 `update-blocks/` 条目、有没有删除表）在**同一次** zip 打开里
     * 探出来，交给 [UpdateDownloadPlan.stagedApplyPlan] 定档；定完档才把树交给解包器。
     * 判据取自**包内实况**而不是清单声明：下载与应用之间隔着换镜像、换版、进程被回收，
     * 而块级包里被补丁覆盖的那些条目在归档里**没有整条字节** —— 拿普通 zip 树去解它，解包器
     * 看不到那些文件、一个字节都不写，[GameAssetInstaller] 照样返回成功，版本标记照写，
     * 盘上停着的还是旧语料。那种形态不报错、看不出来，只有玩家下次读到旧脚本才知道，
     * 是本条通道最坏的失败形状。
     *
     * ## 沿用既有那道闸，不自建第二条
     *
     * [BlockDeltaAssetTree.rejectedEntryCount] 把「内层 zip-slip」与「本类索引阶段丢出的补丁」
     * 汇成同一个数，而**这个数大于 0 就整包拒装** —— 与 [ZipAssetTree] 那条既有闸同一口径
     * 同一个理由（坏包不许「跳过这条继续装」）。zip-slip 本身由内层树判，这里不重复判一遍。
     *
     * ## 失败面
     *
     * 三道闸（形状 / 基线 / 还原）抛的都是 `AssetInstallException`，落进这里的 `catch` →
     * [BundleApply.Crashed]，盘上由 [GameAssetInstaller] 的配对事务与 `.tmp` 纪律保证没有半份
     * 语料；记账（坏基线、重试次数、丢包）留给调用方那半。
     */
    internal fun applyBundle(file: File, gameRoot: File, version: Long): BundleApply {
        var kind: DataArtifactKind? = null
        var recordBadBase = false
        return try {
            ZipAssetTree(file).use { source ->
                val plan = UpdateDownloadPlan.stagedApplyPlan(
                    rejectedEntryCount = source.rejectedEntryCount,
                    hasBlockPatches = source.listDir(BlockDeltaAssetTree.PATCH_ROOT_NAME).isNotEmpty(),
                    isDeltaPackage = source.isDeltaPackage,
                )
                when (plan) {
                    is UpdateDownloadPlan.StagedApply.Rejected ->
                        BundleApply.RejectedBundle(plan.rejectedEntryCount, plan.kind)

                    is UpdateDownloadPlan.StagedApply.Apply -> {
                        kind = plan.kind
                        recordBadBase = plan.recordBadBaseOnFailure
                        if (plan.usesBlockDeltaTree) {
                            val block = BlockDeltaAssetTree(source = source, gameRoot = gameRoot)
                            // 补丁形状的拒绝数只在这棵树上看得到（内层 zip 看不见 update-blocks 的头部）。
                            if (block.rejectedEntryCount > 0) {
                                return BundleApply.RejectedBundle(block.rejectedEntryCount, plan.kind)
                            }
                            BundleApply.Applied(
                                plan.kind,
                                GameAssetInstaller(
                                    block,
                                    gameRoot,
                                    forceReplace = true,
                                    recordDataVersion = version,
                                ).install(),
                            )
                        } else {
                            BundleApply.Applied(
                                plan.kind,
                                GameAssetInstaller(
                                    source,
                                    gameRoot,
                                    forceReplace = true,
                                    recordDataVersion = version,
                                ).install(),
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // 连归档都打不开时 kind 还是 null ⇒ 不记坏基线：与改前「读不出删除表就按不是差量处理、
            // 不记账」逐字同形，不给一类新失败面加新判据。
            BundleApply.Crashed(
                kind = kind,
                recordBadBase = recordBadBase && kind != null,
                problem = t.message ?: t.javaClass.simpleName,
                cause = t,
            )
        }
    }

    /** 安装路由（CONTRACT-UPD2 §2 U7 分档，纯判定可定向单测）：API 29 起走会话，29 以下走 ACTION_VIEW。 */
    internal enum class InstallRoute { SessionApi, LegacyActionView }

    /** 分档的**唯一**判据源：launchInstaller 与定向单测都从这里读，不许两处各写一个 29。 */
    internal fun installRouteFor(sdkInt: Int): InstallRoute =
        if (sdkInt >= Build.VERSION_CODES.Q) InstallRoute.SessionApi else InstallRoute.LegacyActionView

    /**
     * Session API 路的「已提交会话」信号（CONTRACT-UPD2 §2 U7）：[launchInstaller] 在 29+ 把
     * APK 交给 [PackageInstallerSession] 后返回它。**这不是 problem**：会话是异步的，回执
     * （成功 / 用户取消 / 失败+原因摘要）由会话侧经广播送达并自己落结果通知与 Toast；
     * 调用方对这个信号不做任何上屏，只对「旧路返回的 problem 原话」上屏。
     */
    const val SESSION_SUBMITTED_SIGNAL = "session-submitted"

    /**
     * 整包下载完成后拉起系统安装器。
     *
     * 返回值分档（CONTRACT-UPD2 §2 U7）：
     * - **API 29+**：走 [PackageInstallerSession] 会话，返回 [SESSION_SUBMITTED_SIGNAL]
     *   （已提交会话的信号，回执异步送达，没有同步 problem 字符串）；
     * - **API 29 以下**：原 FileProvider + ACTION_VIEW 路原样 —— 返回 null = 已交给系统，
     *   非空 = 该上屏的原因；
     * - **权限未放行**（两档共用的第一道闸）：照旧打开「安装未知应用」设置页并把话术原样返回，
     *   由调用方 Toast —— 本函数不新增上屏点。
     */
    fun launchInstaller(context: Context, apk: File): String? {
        if (!canRequestInstallPackages(context)) {
            return openUnknownSourcesSettings(context)
        }
        when (installRouteFor(Build.VERSION.SDK_INT)) {
            InstallRoute.SessionApi -> {
                // installApk 永不抛（内部全 runCatching）：这里的 runCatching 只兜「起线程都失败」。
                runCatching { PackageInstallerSession.installApk(context, apk) }
                    .onFailure { Log.w(TAG, "安装会话没能起跑", it) }
                // handoff 登记两档共用（29+ 的回执面在会话侧，回收口径不变：版本升上去才回收）。
                rememberHandoff(context, apk)
                return SESSION_SUBMITTED_SIGNAL
            }

            InstallRoute.LegacyActionView -> {
                return try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(UpdateFileProvider.uriFor(apk), APK_MIME)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    rememberHandoff(context, apk)
                    null
                } catch (t: Throwable) {
                    Log.w(TAG, "拉起安装器失败", t)
                    "系统安装器打不开（${t.javaClass.simpleName}），请用文件管理器手动安装 ${apk.name}"
                }
            }
        }
    }

    /** 记下「交给安装器」这件事（路径 + 交出时的本机版本）：只有之后版本真的升上去，[pruneHandedOffApk] 才回收它。 */
    private fun rememberHandoff(context: Context, apk: File) {
        runCatching { currentVersionCode(context) }.getOrNull()?.let { codeBefore ->
            prefs(context).edit()
                .putString(KEY_HANDOFF_APK_PATH, apk.absolutePath)
                .putLong(KEY_HANDOFF_APK_CODE_BEFORE, codeBefore)
                .apply()
        }
    }

    /** 这台机器有没有给本应用放行「安装未知应用」（API 26 起才有这个查询接口）。 */
    fun canRequestInstallPackages(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
    }

    /** 把玩家送到「允许安装未知应用」的设置页，返回一句可以直接上屏的话。 */
    fun openUnknownSourcesSettings(context: Context): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.packageName),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            "已打开系统设置：请允许本应用「安装未知应用」，再回游戏点安装"
        } else {
            "请在系统设置里允许本应用安装未知来源应用"
        }
    } catch (t: Throwable) {
        "系统设置打不开（${t.javaClass.simpleName}），请手动用文件管理器安装"
    }

    /**
     * 只有 Wi-Fi / 以太网这类不计量网络才允许自动开跑（蜂窝一律要玩家点头）。
     *
     * 要 manifest 里的 `ACCESS_NETWORK_STATE`：官方 apidoc 写明 `getActiveNetwork()` 与
     * `getActiveNetworkInfo()` 都 Requires 它，缺它时这里只能交出「判不了」，调用方按蜂窝处理
     * —— 玩家连着 Wi-Fi 也会被提示成蜂窝（2026-11-03 玩家实测）。
     */
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 拿不到默认网络（没连网，或系统把这条网络对调用方挡住）时 activeNetwork 是 null，
            // 而 getNetworkCapabilities(null) 抛的是 NPE 而不是返回 null —— 先接住，交一个干净的 false。
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        }
        @Suppress("DEPRECATION")
        val info: NetworkInfo = manager.activeNetworkInfo ?: return false
        @Suppress("DEPRECATION")
        return info.isConnected && info.type != ConnectivityManager.TYPE_MOBILE
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 流内失败的控制流载体：从嵌套的 use 块里逃出来，带上「该换源还是该重试、.part 留不留」。 */
    private class DownloadAbort(
        val kind: UpdateDownloadPlan.FailureKind,
        override val message: String,
        val discardPart: Boolean,
    ) : IOException(message)

    private fun failure(kind: UpdateDownloadPlan.FailureKind, message: String): DownloadOutcome =
        DownloadOutcome(null, message, kind)

    /**
     * L4-5：下载完成的整包验章。返回 null = 通过/跳过，非空 = 该上屏并换源的原因。
     *
     * 只在下完、rename 之后调一次：读的是**归档里的实际签名**（`getPackageArchiveInfo` 的
     * signer 证书 DER），不是清单自己说的话。清单没声明章时跳过（缺席不拦的既有口径）。
     */
    private fun verifyDownloadedApkCert(context: Context, apk: File, declaredCertSha256: String): String? {
        if (!UpdateDownloadPlan.isApkArtifact(apk.name)) return null
        if (UpdatePolicy.normalizeSha(declaredCertSha256) == null) return null
        val ders = readApkSignerDers(context, apk)
            ?: return "下载包读不出签名（归档损坏或不是 APK），已删除并换源：${apk.name}"
        if (!UpdateDownloadPlan.apkCertSatisfied(declaredCertSha256, ders)) {
            return "下载包的签名与清单声明不符（清单声明 ${declaredCertSha256.take(16)}…），已删除并换源"
        }
        return null
    }

    /** 读一个归档 APK 的 signer 证书 DER；读不出（损坏/不是 APK/API 不给）返回 null。 */
    private fun readApkSignerDers(context: Context, apk: File): List<ByteArray>? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                android.content.pm.PackageManager.GET_SIGNATURES,
            )
        }
        if (info == null) {
            null
        } else if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读下载包签名失败：${apk.name}", t)
        null
    }

    /** 量一个既有 `.part`（没有 / 量不动返回 null = 全新开始）。 */
    private fun readPartState(part: File): UpdateDownloadPlan.PartState? {
        if (!part.isFile) return null
        return runCatching {
            UpdateDownloadPlan.PartState(part.length(), System.currentTimeMillis() - part.lastModified())
        }.getOrNull()
    }

    /**
     * 把 `.part` 的前 [offset] 字节喂进 [digest]：续传之后「边下边算 SHA-256」的起点。
     *
     * 读不满就抛 IOException（文件在单飞闸外被谁动了）—— 外层按网络类失败处理，
     * 下一次尝试重新量、重新判，不赌。
     */
    private fun digestPrefix(digest: MessageDigest, part: File, offset: Long) {
        var remaining = offset
        part.inputStream().use { input ->
            val buffer = ByteArray(GameAssetInstaller.BUFFER_BYTES)
            while (remaining > 0L) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val n = input.read(buffer, 0, want)
                if (n < 0) {
                    throw IOException("盘上断点比请求偏移短：还差 $remaining 字节读不到")
                }
                digest.update(buffer, 0, n)
                remaining -= n
            }
        }
    }

    /**
     * 当前清单认识的全部产物名（清理点名时的 keep 集；缺半组时对应项为 null 不参与）。
     *
     * 块级那一件（P2-13）必须在内：[checkNow] 在**选件之前**就顺手回收一次，漏了它等于把
     * 「已经下好、等着下次启动应用」的块级包当陈旧产物删掉 —— 而那正是清单当前指着的那一件。
     */
    internal fun knownArtifacts(info: UpdateInfo): List<Artifact> =
        listOfNotNull(
            info.apk?.artifact,
            info.assets?.artifact,
            info.assetsFull,
            info.assetsDelta,
            info.assetsBlockDelta,
        )

    /**
     * 回收「已交给安装器」的 APK：本机 versionCode 已经高于交出时的读数 = 升级装成了 ⇒ 删文件、
     * 清登记（[UpdateManager.KEY_HANDOFF_APK_PATH]）。没装成（版本没动）就留着，配合
     * [launchInstaller] 可重开安装器而不重下几百 MiB；永远没装成又换了版本线的，
     * 由 [planCleanup] 的「与清单无关」规则兜底回收。返回删除数（0 或 1）。
     */
    private fun pruneHandedOffApk(context: Context): Int {
        val p = prefs(context)
        val path = p.getString(KEY_HANDOFF_APK_PATH, "").orEmpty()
        val codeBefore = p.getLong(KEY_HANDOFF_APK_CODE_BEFORE, -1L)
        if (path.isEmpty() || codeBefore < 0L) return 0
        val current = runCatching { currentVersionCode(context) }.getOrDefault(codeBefore)
        if (current <= codeBefore) return 0
        val file = File(path)
        val deleted = if (file.isFile && runCatching { file.delete() }.getOrDefault(false)) 1 else 0
        p.edit().remove(KEY_HANDOFF_APK_PATH).remove(KEY_HANDOFF_APK_CODE_BEFORE).apply()
        Log.i(TAG, "升级已装上（$codeBefore → $current），回收交给过安装器的 APK：${file.name}（删除 $deleted）")
        return deleted
    }

    private fun openConnection(url: String, connectMillis: Int, readMillis: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectMillis
        connection.readTimeout = readMillis
        connection.useCaches = false
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun isHttps(url: String): Boolean = url.startsWith("https://")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistedMirrors(context: Context): List<String> =
        prefs(context).getString(KEY_EXTRA_MIRRORS, "").orEmpty()
            .lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** 清单声明的镜像只**追加**进持久化表（顺序与上限仍由 [UpdatePolicy.mirrorOrder] 定）。 */
    private fun rememberMirrors(context: Context, declared: List<String>) {
        if (declared.isEmpty()) return
        val merged = UpdatePolicy.mirrorOrder(
            builtIn = builtInMirrors(context),
            persisted = persistedMirrors(context),
            declared = declared,
        )
        prefs(context).edit().putString(KEY_EXTRA_MIRRORS, merged.joinToString("\n")).apply()
    }

    /**
     * 镜像健康度记账（P1-7）：只记**内置表**里的源。
     *
     * 清单补充的源（persisted / declared）不参与 core 的降位（[UpdatePolicy.mirrorOrderWithHealth]
     * 只重排内置组），记了也用不上；而内置源最多 [UpdatePolicy.MAX_MIRRORS] 条，于是条目数天然封顶。
     * 成败口径按网络层：连上拿到字节 = 成功，连不上 / 这轮没下成 = 失败。
     */
    private fun rememberMirrorHealth(context: Context, mirror: String, success: Boolean, nowMillis: Long) {
        val base = UpdatePolicy.normalizeMirror(mirror) ?: return
        val builtIn = builtInMirrors(context).mapNotNull { UpdatePolicy.normalizeMirror(it) }
        if (base !in builtIn) return
        UiPrefs.noteMirrorResult(context, base, success, nowMillis)
    }

    /**
     * 强制档（min-supported-version）读数 —— 组长收口接线（U1 已合入 main）：
     * 字段就是 [UpdateInfo.minSupportedVersion]（未声明 = [UpdateManifest.NO_MIN_SUPPORTED_VERSION]
     * = -1）。[UpdatePolicy.isForceUpdateRequired] 对 ≤0 一律 false，「读不到 = 不拦」的
     * 宁可不拦也不误拦口径由 core 兜住，这里只做透传，不重算。
     */
    private fun declaredMinSupportedVersion(info: UpdateInfo): Long = info.minSupportedVersion

    /**
     * 清掉「下次启动应用」的指针。C-8：**只清自己处理的那一份**。
     *
     * 应用收尾与下载完成不在同一条时间线上（应用在冷启装载前、下载在前台服务里）：
     * 后一次登记可能正好落在应用收尾的几条语句之间，无条件清会把「刚下好的新包」抹成
     * 「没有待应用包」，玩家得重新下一遍。`handledPath` = 本次真正处理/丢弃的那个文件
     * 的绝对路径；与 prefs 里现存的指针不同 ⇒ 保留新指针，一个键都不动。
     *
     * @return 是否真的清了指针（false = 发现指针已被并发换成别的一份，保留之）
     */
    internal fun clearStaged(sp: SharedPreferences, handledPath: String): Boolean {
        val stored = sp.getString(KEY_STAGED_ASSETS_PATH, "").orEmpty()
        if (stored.isNotEmpty() && stored != handledPath) return false
        sp.edit()
            .remove(KEY_STAGED_ASSETS_PATH)
            .remove(KEY_STAGED_ASSETS_VERSION)
            .remove(KEY_APPLY_ATTEMPTS)
            .remove(KEY_STAGED_ASSETS_SIZE)
            .remove(KEY_STAGED_ASSETS_SHA)
            .remove(KEY_STAGED_ASSETS_BASE)
            .apply()
        return true
    }

    private fun clearStaged(context: Context, handledPath: String) {
        if (!clearStaged(prefs(context), handledPath)) {
            Log.i(TAG, "暂存指针已被并发登记成别的一份，保留它（本次处理的是 $handledPath）")
        }
    }

    /**
     * 下载目录：优先应用专属外部目录（`Android/data/<包名>/files/eratw-update`，与随包数据同卷，
     * `/data` 分区通常装不下 743 MiB），拿不到才回落内部 `filesDir`。两处都不需要存储权限。
     *
     * 对 [UpdateFileProvider] 可见（它按同一个口径解析请求路径，两处必须同源）。
     */
    internal fun downloadDir(context: Context): File {
        val external = context.getExternalFilesDir(null)?.let { File(it, DOWNLOAD_DIR_NAME) }
        val dir = external ?: File(context.filesDir, DOWNLOAD_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("无法创建下载目录：${dir.absolutePath}")
        }
        return dir
    }

    private fun usableSpace(context: Context): Long =
        runCatching { downloadDir(context).usableSpace }.getOrDefault(0L)

    private fun readVersionMarker(file: File): Long =
        runCatching { file.readText(Charsets.UTF_8).trim().toLongOrNull() ?: 0L }.getOrDefault(0L)

    private fun writeVersionMarker(file: File, version: Long) {
        runCatching {
            file.parentFile?.mkdirs()
            // 先写临时名再改名：标记写成半截会被读成「没装过」，白重落一整份数据。
            val tmp = File(file.parentFile, file.name + UpdateDownloadPlan.PART_SUFFIX)
            FileOutputStream(tmp).use { it.write(version.toString().toByteArray(Charsets.UTF_8)) }
            // 改名失败**不抛异常**，只回 false（磁盘满 / 权限 / 别人正占着这个名）。早先这里
            // 静默吞掉：屏上照样弹「更新完成」，而盘上的标记停在旧版甚至 0 版。
            //
            // 代价按「盘上 0 版」那一档算：读侧 [readVersionMarker] 拿不到就是 0，
            // 下一轮选包据此判成「数据从没装过」⇒ 回落**全量包**重下一遍
            // （真机口径几百 MiB：见 `CONTRACT-UPDATE` 的随包体积读数），玩家侧的表现是
            // 「每次联网都催我更新、下完还说没更新」。数据本身是全的，所以这里**不**改成抛
            // —— 把已经成功的整轮判成失败更坏（会白丢一次已落盘的数据 + 再来一轮重试），
            // 只把事实钉进 logcat：这一发是有名有姓的降级，不是「没事」。
            if (!tmp.renameTo(file)) {
                tmp.delete()
                Log.e(
                    TAG,
                    "版本标记没能改名落位：${file.absolutePath}（盘上仍是旧版/0 版 ⇒ " +
                        "下次启动会把整份数据包重下一遍；数据本身已落盘，不必手工修）",
                )
            }
        }.onFailure { Log.w(TAG, "版本标记写不下去：${file.absolutePath}", it) }
    }

    private fun sha256Of(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(GameAssetInstaller.BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        hex(digest.digest())
    }.getOrNull()

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun hostOf(mirror: String): String = mirror.substringAfter("://").substringBefore('/')
}
