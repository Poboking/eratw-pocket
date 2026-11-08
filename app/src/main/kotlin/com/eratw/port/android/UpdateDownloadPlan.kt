package com.eratw.port.android

import com.eratw.port.core.update.Artifact
import com.eratw.port.core.update.DataArtifactKind
import com.eratw.port.core.update.UpdateInfo
import com.eratw.port.core.update.UpdatePolicy
import java.security.MessageDigest

/**
 * 更新下载管线的**纯判定层**：续传偏移、何时必须从头重写、失败分类与退避、空间闸、目录清理。
 *
 * ## 为什么单独一层（CONTRACT-UPDATE U-B 的存在理由）
 *
 * [UpdateManager] 是「网络 + 文件」的 Android 壳，单测拿不到它；凡「判错了就烧玩家流量」的
 * 决定全部下沉到本对象 —— 只吃数字与字符串、只吐数字与布尔，**不碰 Context、不碰文件系统**。
 * Android 侧（[UpdateManager]）只做 IO 胶水：量 `.part` 的字节数与年龄、发 Range 请求、把字节
 * 写进文件，然后把读数喂给这里的函数，按返回值行动。判定因此能在纯 JVM 上用
 * `UpdateDownloadPlanTest` 钉死（AGENTS.md 铁律 7 的定向单测口径）。
 *
 * ## 判错了分别贵在哪
 *
 * - **该重写却没重写**（[mustRewriteFromScratch] 判成 false）：在旧 `.part` 后面追加服务端
 *   从头发来的一整份字节流，得到的是一个 **sha 必错**的缝合文件。最恶劣的是失败点在最末尾
 *   —— 玩家每次看到的都是「每次都差一点就成功」，每次重试都白烧几百 MiB；
 * - **该续传却判成重写**：浪费已下到的前缀字节。代价有界、方向安全，所以 [contentRangeStart]
 *   解析不出来这种「拿不准」的场合一律按不信任处理（宁重写，不缝合）；
 * - **失败分类判错**（[decideFailure]）：把「这条镜像没这个文件」判成可重试，就是拿玩家的
 *   网络对着坏源把五个退避档位撞一遍；反过来把「一次抖动」判成换源，一条源抖动就整条通道
 *   今天哑掉；
 * - **空间闸一刀切**（[requiredHeadroomBytes]）：512 MiB 余量是给 743 MiB 整包调出来的刻度
 *   （解包器同目录写临时文件 + 安装器要腾地方），给十几 MiB 的差量包也扣这道税，会让本来
 *   够用的设备判成「空间不够」——差量通道要治的病反而治不了。
 *
 * ## `.part` 的生命周期口径（契约 §1.4）
 *
 * `.part` **保留原地不删**：进程被回收后，下次冷启的同一次下载凭「字节数 + 年龄」认领它。
 * 只有三种情况销毁它：超期（超过 [UpdatePolicy.PART_MAX_AGE_MILLIS]）、字节数超过清单声明、
 * sha 核对不过（内容已经不可信，续传只会把错误缝下去）。下载目录里与当前清单无关的陈旧产物
 * 由 [planCleanup] 点名回收。
 */
object UpdateDownloadPlan {

    /** 半截下载的后缀（与 [UpdateManager] 的写盘口径同源，两边必须同一个串）。 */
    const val PART_SUFFIX = ".part"

    /** 整包产物的后缀（下载完成后要验章、要过安装会话；数据包不走这两条）。 */
    const val APK_SUFFIX = ".apk"

    /** 这件产物是不是整包 APK（判据只有后缀，清单解析侧已保证文件名形状合法）。 */
    fun isApkArtifact(fileName: String): Boolean = fileName.endsWith(APK_SUFFIX)

    /**
     * 小包空间闸的余量下限（字节）：64 MiB。
     *
     * 余量随包缩放（见 [requiredHeadroomBytes]）后不能一路缩到零 —— 下载卷是共享的，
     * 别的应用在写、系统缓存在校准，留一个下限才不会出现「刚好填满盘、解包器写不进临时文件」。
     */
    const val SMALL_HEADROOM_FLOOR_BYTES: Long = 64L * 1024L * 1024L

    /**
     * 一条既有 `.part` 的读数（调用方从文件系统量出来传进来，本类不做 IO）。
     *
     * @param bytes 现有字节数（0 字节空桩传 0）
     * @param ageMillis 距今多久（现在减 lastModified；时钟回拨导致负数按「新鲜」处理，
     *   年龄只用来划界，正确性永远由 sha 兜底）
     */
    class PartState(val bytes: Long, val ageMillis: Long)

    /** 下载目录里一个文件的快照（[name] 是纯文件名，不含路径）。 */
    class DirEntry(val name: String, val bytes: Long, val ageMillis: Long)

    /**
     * 一次下载尝试的失败类别——决定「同镜像退避重试」还是「直接换下一条镜像」。
     *
     * 只保留管线真的会产生的类别；新增类别必须同时给 [decideFailure] 一个归属，不许 default。
     */
    enum class FailureKind {
        /** 网络层失败：连接/读超时、流被掐断、本地读盘抖动。前缀完好，可退避后原地续传。 */
        Network,

        /** 服务端 5xx / 429 这类临时错误：镜像活着但此刻不行，值得在同一条源上退避重试。 */
        HttpStatus,

        /**
         * 404 / 403 / 410 / **416**：这条源上没有这个文件（或本地断点超出远端对象长度），
         * 重试一万次也不会凭空出现，换镜像。
         *
         * 416（Range Not Satisfiable）落在这一档的原因：请求的 `bytes=<offset>-` 起点已经
         * 越过远端对象的末尾（远端对象被截断/半上传/换了内容），此时原地退避重发的还是同一个
         * 越界 Range —— 只会把 1s/2s/5s/15s/30s 五个档位白撞一遍；正确处置是作废 `.part`
         * （判据见 [mustDiscardPartForHttpStatus]）再换源。
         */
        MissingOnMirror,

        /** 地址被重定向到明文：安全判定，换镜像（且清单镜像本就要求 https，通常意味着配置烂）。 */
        PlainHttp,

        /** sha 不符：这条源上的内容与清单声明的不是同一个文件，重下同一份没有意义，换镜像。 */
        DigestMismatch,

        /** 实收字节数超过清单声明：源或清单坏得离谱，扔掉 `.part` 换镜像。 */
        OversizeStream,

        /** 本地文件系统问题（删不掉旧文件 / rename 不动 / 目录建不出来）：换镜像只是走个过场，但绝不重试轰炸网络。 */
        LocalFileSystem,
    }

    /** 失败之后怎么办：同一条镜像退避重试，还是推进到下一条镜像。 */
    enum class FailureDecision {
        RetrySameMirror,
        NextMirror,
    }

    /**
     * 失败分类 → 处置。**只有网络与临时 HTTP 错误配得上退避重试**，其余一律换镜像。
     *
     * 理由见 [FailureKind] 每一项的注释；共同原则是「重试只有在『同样的请求再发一次可能不同』
     * 时才有价值」——文件不在、内容不对、盘写不进，这三件事不会因为再等 1 秒而改变。
     */
    fun decideFailure(kind: FailureKind): FailureDecision = when (kind) {
        FailureKind.Network, FailureKind.HttpStatus -> FailureDecision.RetrySameMirror
        else -> FailureDecision.NextMirror
    }

    /** 这条失败该在同一条镜像上再试一次吗。 */
    fun retryOnSameMirror(kind: FailureKind): Boolean = decideFailure(kind) == FailureDecision.RetrySameMirror

    /**
     * 非 2xx 响应的**唯一分类出口**（L4）：下载管线的 `downloadOnce` 只调这里，不自己写
     * `if (code == 404 || ...)`。抽出来的理由和续传判据一样：判错了的代价是「拿玩家的流量
     * 对着坏源把五个退避档位撞一遍」，这类判定必须能在纯 JVM 上钉死。
     *
     * - 404 / 403 / 410：对象不在（或无权取）—— 重发同一个请求不会变。
     * - **416**：本地断点超出了远端对象的长度（请求的 Range 起点越界）—— 同样是「这个源上
     *   没有你要的那段字节」，且必须配合 [mustDiscardPartForHttpStatus] 作废断点，
     *   否则换源也只是把同一个越界 Range 带去下一条源。
     * - 其余（5xx / 429 / 408 等）：镜像活着但此刻不行，归可退避重试的 [FailureKind.HttpStatus]。
     */
    fun httpStatusKindFor(code: Int): FailureKind = when (code) {
        404, 403, 410, 416 -> FailureKind.MissingOnMirror
        else -> FailureKind.HttpStatus
    }

    /**
     * 这个 HTTP 状态码回来之后，盘上的 `.part` 还能不能信。
     *
     * 只有 **416** 需要作废：它证明「请求的起始偏移已经越过了远端对象的末尾」，盘上那截前缀
     * 要么属于另一版内容、要么远端对象变短了 —— 留着它，下一条源又会被同一个越界 Range 撞 416
     * （`.part` 在 7 天 [UpdatePolicy.PART_MAX_AGE_MILLIS] 内都算「合法断点」，清理也不会删它，
     * 等于整个下载通道卡死）。其余状态码不在这里作废：网络/5xx 的前缀仍可能是好的，留给
     * 续传口径（[resumeOffsetFor]）判。
     */
    fun mustDiscardPartForHttpStatus(code: Int): Boolean = code == 416

    /**
     * 既有 `.part` 的续传起始偏移（0 = 整段重来）；判定本体在 core 的
     * [UpdatePolicy.resumeOffset]（未过期 + 不超过声明 size 才认），这里只做零件装配，
     * app 层不重算语义（契约 §1.2/§3 的分工）。
     */
    fun resumeOffsetFor(artifact: Artifact, part: PartState?): Long {
        if (part == null) return 0L
        return UpdatePolicy.resumeOffset(part.bytes, artifact.sizeBytes, part.ageMillis)
    }

    /** `.part` 的字节数已经等于声明值：该由调用方直接核 sha，而不是再发一次请求。 */
    fun isPartCompleteFor(artifact: Artifact, part: PartState?): Boolean =
        part != null && part.bytes == artifact.sizeBytes

    /** 还差多少字节没下（空间闸用这个数，不是整包 size——续传不是重新下载）。 */
    fun remainingBytes(artifact: Artifact, part: PartState?): Long =
        artifact.sizeBytes - resumeOffsetFor(artifact, part)

    /**
     * 服务端这次响应**能不能信**这次续传：请求了偏移、真的回了 206、且 Content-Range
     * 的起点恰好落在请求的偏移上。三者缺一即不信。
     *
     * Content-Range 缺失或解析不出来也按不信处理（[contentRangeStart] 返回 null）：
     * 「拿不准」在这个问题上必须倒向重写——续传判错的产物是缝合文件（见类头），
     * 重写判错的产物只是多下几字节。R2（真源）与常见对象存储都规范回 206 + Content-Range，
     * 收紧不会误伤主路径，只会挡住野鸡代理。
     */
    fun resumeTrusted(requestedOffset: Long, responseCode: Int, contentRangeStart: Long?): Boolean =
        requestedOffset > 0L && responseCode == 206 && contentRangeStart == requestedOffset

    /**
     * 拿到响应后是否必须**从头重写** `.part`（截断、不追加）。
     *
     * 契约 §1.4 的硬规定：服务端回 200（不支持 Range）时把整份字节流追加到旧 `.part` 后面，
     * 得到的是「旧前缀 + 完整文件」的缝合体，sha 永远核对不过——失败的代价不是报错难看，
     * 而是**每次重试都恰好失败在最末尾**，把玩家的流量和耐心一起磨光。
     * 所以这里宁可作废已下载的几百 MB 前缀，也要保证落盘的每一个字节都属于本次响应。
     */
    fun mustRewriteFromScratch(requestedOffset: Long, responseCode: Int, contentRangeStart: Long?): Boolean =
        requestedOffset > 0L && !resumeTrusted(requestedOffset, responseCode, contentRangeStart)

    /**
     * 解析 Content-Range 响应头的起点：`bytes 1000-1999/2000` 返回 1000。
     *
     * 认不出的形状一律 null（宁可判成「不信续传」）。单位后缀、`bytes * /2000` 这类
     * 「服务端不支持具体区间」的写法都会落到 null。
     */
    fun contentRangeStart(header: String?): Long? {
        val trimmed = header?.trim() ?: return null
        if (!trimmed.startsWith("bytes", ignoreCase = true)) return null
        val range = trimmed.substringAfter(' ', "").substringBefore('/')
            .trim().removePrefix("=").trim()
        val startText = range.substringBefore('-', "")
        if (range.isEmpty() || startText.isEmpty()) return null
        return startText.toLongOrNull()?.takeIf { it >= 0L }
    }

    /**
     * 第 [attempt] 次失败后的退避等待（毫秒）；null = 档位用完，**推进到下一条镜像**。
     *
     * 委托 [UpdatePolicy.retryBackoffMillis]（1s/2s/5s/15s/30s，契约 §3）；单列在此只为让
     * 管线的「等待 → 重试 → 换镜像」循环全部从本类读数，测试钉的是循环依赖的判据本身。
     */
    fun backoffMillisOrFailOver(attempt: Int): Long? = UpdatePolicy.retryBackoffMillis(attempt)

    /**
     * 空间闸的余量：随声明 size 缩放，钳在 [SMALL_HEADROOM_FLOOR_BYTES] 与
     * [UpdatePolicy.MIN_FREE_HEADROOM_BYTES] 之间；整包再把**安装会话的副本**算进来。
     *
     * 512 MiB 是发布侧按 743 MiB 整包调的刻度（解包临时文件 + 安装腾地方），不是对所有包的
     * 税：十几 MiB 的差量包要求 512 MiB 余量会把本来够用的设备挡在门外——正是本波差量通道
     * 要治的病。缩放方向：小包 ≈ 包本身大小（解包产物与包同量级），保底 64 MiB；大包维持
     * 既有的 512 MiB 口径（0.2.32 实测能更新的设备行为不变）。
     *
     * L4-9：API 29+ 走 PackageInstaller 会话时，壳层还要把整包**再拷一份**进
     * `/data/app-staging`（[com.eratw.port.android.PackageInstallerSession] 的 `session.openWrite`），
     * 那份副本与下载目录里的产物等量。原闸只留 512 MiB，边界设备会「下完 743 MiB 才在会话
     * 写入阶段失败」。所以整包调用点传 `sessionCopyBytes = declaredSize`；数据包不经过会话，
     * 保持原口径（缺省 0）。
     *
     * @param sessionCopyBytes 落盘之后还要再复制的字节数（整包 = 声明 size；数据包 = 0）
     */
    fun requiredHeadroomBytes(declaredSize: Long, sessionCopyBytes: Long = 0L): Long =
        declaredSize.coerceIn(SMALL_HEADROOM_FLOOR_BYTES, UpdatePolicy.MIN_FREE_HEADROOM_BYTES) + sessionCopyBytes

    /**
     * 空间够不够下**还差的这部分**：可用字节 − 还需下载字节 ≥ [requiredHeadroomBytes]。
     *
     * 走 core 的 [UpdatePolicy.hasEnoughSpace]（usable 必须 > 0 的口径也在它里面），只是把
     * headroom 从默认值换成随 size 缩放的读数。续传时已盘的 `.part` 已经占进 usable 的扣减里
     * （文件系统量出来的可用空间天然不含它），所以用剩余字节判是守恒的。
     *
     * L4-9：`usableBytes` 是**下载卷**的可用空间（`UpdateManager.usableSpace`）。整包的会话
     * 副本落在 `/data`，多数机型两者同卷、这一步成立；外置主存储的机型另说（TODO(verify)，
     * 见 task 回执）。
     */
    fun spaceOk(
        usableBytes: Long,
        artifact: Artifact,
        part: PartState?,
        sessionCopyBytes: Long = 0L,
    ): Boolean = UpdatePolicy.hasEnoughSpace(
        usableBytes,
        remainingBytes(artifact, part),
        requiredHeadroomBytes(artifact.sizeBytes, sessionCopyBytes),
    )

    /**
     * 下载目录清理点名：给目录快照与**当前清单认识的产物**，返回要删除的文件名。
     *
     * 回收三类东西（契约 U-B 第 3/5 条）：
     * 1. 与当前清单无关的陈旧产物（旧版本 APK、旧数据包 zip——装完/换版后不再被 keep 集认识，
     *    几百 MiB 的残留就是这样清掉的，不依赖「谁交给过安装器」的记账）；
     * 2. 无主的 `.part`（对应文件不在清单里；半截下载不该跨版本存活）；
     * 3. 不合法续传的 `.part`（超期 / 字节超过声明——留着只会被下次下载作废，不如现在就回收）
     *    以及正式文件已在、`.part` 还躺着的冗余。
     *
     * 刻意**不删**的：清单认识的正式文件（已下好待安装/待应用的重试资产，删了就是让玩家重下
     * 几百 MiB）与合法可续传的 `.part`（进程被回收后冷启接着来的本钱）。
     * 空 `.part`（0 字节）回收——下次下载反正要重头写。
     *
     * @param extraKeepNames 清单之外必须保名的文件（如已登记待应用的暂存数据包：它活在 prefs
     *   指针里，清单换代前删掉它就是把「下次启动自动应用」变成「重下整包」）
     */
    fun planCleanup(
        entries: List<DirEntry>,
        keepArtifacts: List<Artifact>,
        extraKeepNames: Set<String> = emptySet(),
    ): List<String> {
        val targets = keepArtifacts.map { it.fileName }.toSet() + extraKeepNames
        val artifactByPartName = keepArtifacts.associateBy { it.fileName + PART_SUFFIX }
        val presentNames = entries.map { it.name }.toSet()
        val doomed = ArrayList<String>()
        for (entry in entries) {
            val name = entry.name
            when {
                name in targets -> Unit
                name.endsWith(PART_SUFFIX) -> {
                    val artifact = artifactByPartName[name]
                    if (artifact == null || artifact.fileName in presentNames) {
                        doomed += name
                    } else if (resumeOffsetFor(artifact, PartState(entry.bytes, entry.ageMillis)) <= 0L) {
                        doomed += name
                    }
                }
                else -> doomed += name
            }
        }
        return doomed
    }

    // ------------------------------------------------------------------
    // 选件与落地路由（P2-13 块级差量，U8b）：app 侧只装配读数，判定仍全在 core
    // ------------------------------------------------------------------

    /**
     * 数据通道这一趟的选件读数：**下哪一件** + **算哪一档**。
     *
     * 两条判定都直接吃 core 的四参形（[UpdatePolicy.dataArtifactFor] /
     * [UpdatePolicy.dataArtifactKind]），块级那一件从 [UpdateInfo.assetsBlockDelta] 取 ——
     * app 不重算回落次序（契约 §1.2/§3 的分工），也不自己比较 `assets-base-version`。
     *
     * 为什么要在这里合一次并**同时**交回 kind 与 artifact：这两条判定各读各的参数就会分叉
     * （日志说「走了块级」、下载的是全量包，排查时第一眼就被带偏，core 的
     * [UpdatePolicy.dataArtifactKind] 类头写的正是这件事）。同一个调用点一次算完，
     * 档位与字节永远同源。
     *
     * 块级新键**缺席**时（线上已发出去的清单全是这一形），[artifact] 与三参形
     * `dataArtifactFor(info, installed, diskTrusted)` **逐字同结论**、[kind] 落在
     * FILE_DELTA / FULL / NONE 三档之一 —— 这条由 `UpdateBlockDeltaWireTest` 用矩阵钉住。
     *
     * @param avoidedKind 拒装挡板这次跳过的档位（U13）；null = 这一趟没踩挡板、照常选件。
     *   与 [kind] 配对才讲得全「上次被拒的是哪一档、这次改走哪一档」。
     */
    class DataSelection(
        val artifact: Artifact?,
        val kind: DataArtifactKind,
        val avoidedKind: DataArtifactKind? = null,
    )

    fun selectDataArtifact(info: UpdateInfo, installedVersion: Long, diskTrusted: Boolean): DataSelection {
        val blockDelta = info.assetsBlockDelta
        return DataSelection(
            artifact = UpdatePolicy.dataArtifactFor(info, blockDelta, installedVersion, diskTrusted),
            kind = UpdatePolicy.dataArtifactKind(info, blockDelta, installedVersion, diskTrusted),
        )
    }

    /**
     * 拒装挡板（U13 upd-baccount）：**同键不再走同一条下载路**。
     *
     * 上一次检查这个「目标版本 + 档位」的整包被拒装过（坏基线 / 不合格补丁 / zip-slip，账在
     * `UiPrefs` 的 `update_rejected_*` 键族），这一趟若还是原样选中同一档，同一个坏件就会
     * 每轮重下重拒。命中挡板时按**单步回落**改路：被拒的那一档按「该档不可用」重新过一遍
     * core 的既有判定 —— app 不自算次序，只是把被拒档从候选里摘掉：
     *
     * - BLOCK_DELTA 被拒：块级件视同缺席（四参形传 null 块级件），core 自己落 FILE_DELTA / FULL / NONE
     * - FILE_DELTA 被拒：盘上判据借 `diskTrusted = false` 关死（块级已由 null 关死），core 落 FULL / NONE
     * - FULL 被拒：没有下一档，[DataSelection.kind] = NONE、[DataSelection.artifact] = null —— 暂不取数据包
     *
     * 「这次要选的」与账**异键**（新版本 / 新档位）或没有账时，[DataSelection] 与
     * [selectDataArtifact] **逐字同结论**、[DataSelection.avoidedKind] 为 null —— 挡板不碰任何正常路径。
     * 回落是单调的：命中挡板后 [DataSelection.kind] 永远不等于 [RejectedBundleAccount.kind]（被拒档
     * 已被摘掉），同一版本反复检查只会停在同一处，不会来回摆。
     *
     * @param rejected 拒装账；null = 没有账（首装 / 已清 / 被系统还原），照常选件
     */
    fun selectDataArtifactAvoidingRejected(
        info: UpdateInfo,
        installedVersion: Long,
        diskTrusted: Boolean,
        rejected: RejectedBundleAccount?,
    ): DataSelection {
        val selection = selectDataArtifact(info, installedVersion, diskTrusted)
        if (rejected == null || selection.kind == DataArtifactKind.NONE) return selection
        if (rejected.version != (info.assets?.version ?: -1L)) return selection
        if (rejected.kind != selection.kind) return selection
        return when (rejected.kind) {
            DataArtifactKind.BLOCK_DELTA -> DataSelection(
                artifact = UpdatePolicy.dataArtifactFor(info, null, installedVersion, diskTrusted),
                kind = UpdatePolicy.dataArtifactKind(info, null, installedVersion, diskTrusted),
                avoidedKind = rejected.kind,
            )
            DataArtifactKind.FILE_DELTA -> DataSelection(
                artifact = UpdatePolicy.dataArtifactFor(info, null, installedVersion, diskTrusted = false),
                kind = UpdatePolicy.dataArtifactKind(info, null, installedVersion, diskTrusted = false),
                avoidedKind = rejected.kind,
            )
            // 全量也被拒过：没有下一档，这一趟暂不取数据包（挡板读数照记，上屏讲「暂不取」）。
            DataArtifactKind.FULL, DataArtifactKind.NONE ->
                DataSelection(artifact = null, kind = DataArtifactKind.NONE, avoidedKind = rejected.kind)
        }
    }

    /**
     * 应用暂存包那一拍的**路由**：这一趟用的是哪棵树、失败要不要记坏基线。
     *
     * 三件输入都由壳层从**包自己**探出来（不是从清单探）：清单说的是「该下哪一件」，
     * 而落地吃的是「盘上这个 zip 里到底有什么」—— 下载与冷启应用之间隔着换版、镜像切换、
     * 进程被杀，任何一处让两者各说各话，最坏结果不是报错而是**静默少装一批文件**
     * （块级包里被补丁覆盖的那些条目在归档里根本没有整条字节，用普通树解就等于什么都没装，
     * 而版本标记照写 —— 下次启动读到的还是旧语料，谁都不报错）。所以判据只看包内实况。
     *
     * @param rejectedEntryCount 内层 zip 树报的非法条目数；大于 0 走 [StagedApply.Rejected]，
     *   即既有的「整包拒装」那道闸。块级档还有第二类形状拒绝（补丁头部不合格）只有
     *   [com.eratw.port.platform.storage.BlockDeltaAssetTree] 自己数得到，所以落地前还要拿
     *   **那棵树**的同一个读数再过一次同一条闸（调用点 `UpdateManager.applyBundle`）
     * @param hasBlockPatches 归档里有 `update-blocks/` 条目 = 这是一份块级差量包
     * @param isDeltaPackage 归档根带 `update-delta.txt` = 差量档（文件级与块级共用这一判据）
     */
    fun stagedApplyPlan(
        rejectedEntryCount: Int,
        hasBlockPatches: Boolean,
        isDeltaPackage: Boolean,
    ): StagedApply {
        val kind = stagedKindOf(hasBlockPatches, isDeltaPackage)
        if (rejectedEntryCount > 0) return StagedApply.Rejected(rejectedEntryCount, kind)
        return StagedApply.Apply(kind)
    }

    /** 档位只由包内实况决定；`NONE` 在这一拍不可能出现（包都已经到手了）。 */
    private fun stagedKindOf(hasBlockPatches: Boolean, isDeltaPackage: Boolean): DataArtifactKind = when {
        hasBlockPatches -> DataArtifactKind.BLOCK_DELTA
        isDeltaPackage -> DataArtifactKind.FILE_DELTA
        else -> DataArtifactKind.FULL
    }

    /** [stagedApplyPlan] 的结论。 */
    sealed class StagedApply {

        /**
         * 包不可信：整包拒装、丢弃不重试（与既有那道 `rejectedEntryCount > 0` 闸同一条口径，
         * 不新增第二条判定）。块级档的 [rejectedEntryCount] 含「补丁头部形状不对」的条数 ——
         * 那种包同样没有「跳过这条继续装」的余地：跳过的正是这一版改过的文件。
         */
        class Rejected(val rejectedEntryCount: Int, val kind: DataArtifactKind) : StagedApply()

        /**
         * 可以落地。
         *
         * @param kind 这一趟实际用的那一档；[DataArtifactKind.BLOCK_DELTA] 时壳层必须用
         *   `BlockDeltaAssetTree`（判据见 [usesBlockDeltaTree]），其余两档用普通 zip 树
         */
        class Apply(val kind: DataArtifactKind) : StagedApply() {

            /** 这一档要不要走块级树：唯一的判据出口，壳层不许自己再拼一遍 `kind == ...`。 */
            val usesBlockDeltaTree: Boolean get() = kind == DataArtifactKind.BLOCK_DELTA

            /**
             * 本轮**抛错**时该不该把这份盘记成不可信（坏基线记账）。
             *
             * 差量档（文件级与块级同一判据）才记：记一次，同一基线上的**两档**一起关死
             * （core 的 `blockDeltaUsable` 与 `deltaUsable` 共用 `diskTrusted` 这一条闸）。
             * 全量档不记 —— 全量把包里每一条都落一遍，没有「相对某一版拼错」这回事，
             * 记它只会把下一次本来能用的差量白白废掉。
             *
             * 拒装（[Rejected]）**不**走这条：它一个字节都没落，盘仍然是自洽的上一版。
             */
            val recordBadBaseOnFailure: Boolean get() = kind != DataArtifactKind.FULL
        }
    }

    // ------------------------------------------------------------------
    // 系统时限暂停（U2 病①）：Android 15+ dataSync 型前台服务的时限兜底判定
    // ------------------------------------------------------------------

    /**
     * dataSync 型前台服务的系统时限预算（毫秒）：24 小时窗口内 **6 小时**。
     *
     * 官方口径（TODO(verify) 已核，两处出处）：
     * - 行为变更页 developer.android.google.cn/about/versions/15/behavior-changes-15
     *   「数据同步前台服务超时行为」节：以 Android 15（API 35）为目标的应用，系统允许其
     *   dataSync 服务在 24 小时内总共运行 6 小时，到点调用 `Service.onTimeout(int, int)`，
     *   服务有几秒钟调 stopSelf 的窗口，不停止则 RemoteServiceException（"A foreground
     *   service of type dataSync did not stop within its timeout"）；6 小时预算由应用的
     *   **所有** dataSync 服务共享，用户把应用调到前台会重置计时器；
     * - API diff 35（developer.android.google.cn/sdk/api_diff/35/changes/android.app.Service）：
     *   API 34 → 35 只给 [android.app.Service] 新增了 `void onTimeout(int, int)` 这一个方法
     *   （注意是**双参**形态，与单参的旧预览版写法不同）。
     *
     * **时钟归属：系统侧。** 本管线不自设计时器也不读钟 —— 回调没来就当预算还在；
     * 回调来了（[com.eratw.port.android.UpdateDownloadService.onTimeout]）即预算确实用尽。
     * 这个常量只做文档化的刻度，不参与任何运行期判定。
     */
    const val DATA_SYNC_FGS_BUDGET_MILLIS: Long = 6L * 60L * 60L * 1000L

    /** 系统时限暂停时该做的收尾步骤（判定矩阵的一格；壳层照单执行）。 */
    enum class FgsTimeoutStep {
        /** 置取消标志 + interrupt 工作线程：复用取消收尾，`.part` 原样保留（续传口径不变）。 */
        CancelWorker,

        /** 放单飞闸：只有真持有闸（gateHeld）才放 —— 放错会把在飞下载暴露成双开。 */
        ReleaseGate,

        /** stopSelf 退场：系统的时限闸以「服务停了」为准，几秒钟内必须停。 */
        StopSelf,
    }

    /**
     * 时限到点（`onTimeout` 回调）的处置矩阵（纯判定，单测钉死；壳层照单执行）。
     *
     * - **分档**：该口径 Android 15（API 35）才引入，`sdkInt < 35` ⇒ 系统根本不会回调 ⇒
     *   空表（防御：真被调了也不行动）；
     * - worker 还活着 ⇒ [FgsTimeoutStep.CancelWorker]（线程已退则无需打断）；
     * - **闸的归属（时限路）**：返回值**不含** [FgsTimeoutStep.ReleaseGate] —— 闸随 worker
     *   的取消收尾放（runDownload 的 finally），这里提前放闸会把「旧 worker 未收完尾」的窗口
     *   从几条指令放大到「interrupt 生效前的一次读阻塞」（最长 30 秒），让下一单与新单在同一
     *   服务实例上互相踩（worker/activeJob 都是实例级字段）；
     * - 无论前两条如何，都要 [FgsTimeoutStep.StopSelf]：系统只认「服务停了」。
     */
    fun fgsTimeoutSteps(sdkInt: Int, workerAlive: Boolean): List<FgsTimeoutStep> {
        if (sdkInt < 35) return emptyList()
        return if (workerAlive) {
            listOf(FgsTimeoutStep.CancelWorker, FgsTimeoutStep.StopSelf)
        } else {
            listOf(FgsTimeoutStep.StopSelf)
        }
    }

    /**
     * onDestroy 兜底的处置矩阵（纯判定，单测钉死）：系统可能不经 onTimeout 直接销毁服务
     * （内存回收、卸载、时限后的强杀）。正常收尾完成时闸已放、这里是空转；
     * 闸还挂着 = 收尾没走完，必须兜。
     *
     * - 闸的归属：`gateHeld` 为假 ⇒ 空表（没持有就 no-op，防误放别人的闸）；
     * - worker 活着 ⇒ [FgsTimeoutStep.CancelWorker]（置标志 + interrupt，让线程自己的
     *   取消收尾跑完；线程已死则无从打断，只欠放闸）；
     * - **系统时限路（`systemTimedOut`）⇒ 不含 [FgsTimeoutStep.ReleaseGate]**：`onTimeout` 里
     *   `stopSelf` 之后 onDestroy 往往紧跟着来，而 worker 还在 interrupt 生效前的读阻塞里。
     *   这时提前放闸，下一单就能与「旧 worker 未收完尾」同写一个 `.part`（onTimeout 的判定矩阵
     *   已写明闸归 worker 的取消收尾）；worker 已死时例外 —— 它的 finally 已放过闸，
     *   `gateHeld` 还挂着只可能是记账错位，此时放闸是唯一安全动作（死闸绝不能带进下一单）；
     * - 非时限路 `gateHeld` 为真 ⇒ 必含 [FgsTimeoutStep.ReleaseGate]：服务都要销毁了，闸绝不能
     *   带进泥土（进程不死的话下一单会被死闸挡住）。不退 [FgsTimeoutStep.StopSelf] —— 系统已经在销毁了。
     */
    fun onDestroyFallbackSteps(
        gateHeld: Boolean,
        workerAlive: Boolean,
        systemTimedOut: Boolean = false,
    ): List<FgsTimeoutStep> =
        when {
            !gateHeld -> emptyList()
            systemTimedOut -> if (workerAlive) {
                listOf(FgsTimeoutStep.CancelWorker)
            } else {
                // 防御：正常时序下 worker 死掉时闸已由它的 finally 放掉；走到这里说明记账错位，
                // 宁可补放一次（幂等），也不能把死闸留给下一单。
                listOf(FgsTimeoutStep.ReleaseGate)
            }
            workerAlive -> listOf(FgsTimeoutStep.CancelWorker, FgsTimeoutStep.ReleaseGate)
            else -> listOf(FgsTimeoutStep.ReleaseGate)
        }

    /**
     * L4-3：本机签名章候选（SHA-256 小写十六进制）—— 覆盖升级预检的**唯一**读数来源。
     *
     * 为什么要「当前 signer + 旋转历史最后一枚」而不是历史第一枚：
     * `SigningInfo.getSigningCertificateHistory()` 的顺序是「原始证书在 index 0、当前证书在最后
     * 一枚」（官方 API 文档与 AOSP SigningInfo.java 都这么写）。旧实现取 `firstOrNull()`，
     * 在**轮换过签名密钥**的设备上拿到的是最旧的章 ⇒ `certBlocksInstall(旧章, 清单声明的新章)`
     * 判成「装不上」，把整包更新永久拦住，还教玩家「先卸载（存档会没）」。
     *
     * 候选集合的语义：**多 signer 任一匹配即不拦**（`apkContentsSigners` 全部入列），
     * 旋转场景再补历史最后一枚兜底；判「拦不拦」见 [certBlocksInstallAny]。
     *
     * @param currentSignerDers 当前 signer 的证书 DER（API 28+ 的 `apkContentsSigners`，
     *   或 28 以下的 `GET_SIGNATURES` 全量）
     * @param historyDers 旋转历史（仅 API 28+ 有；空表 = 没轮换过）
     */
    fun signerCertSha256Candidates(
        currentSignerDers: List<ByteArray>,
        historyDers: List<ByteArray>,
    ): List<String> {
        val out = LinkedHashSet<String>()
        for (der in currentSignerDers) out += sha256Hex(der)
        // 历史最后一枚 = 当前章；与 contents 重复时被集合去重，不重复的场合兜住「contents 只给了
        // 部分 signer」这类实现差异。
        historyDers.lastOrNull()?.let { out += sha256Hex(it) }
        return out.toList()
    }

    /**
     * 覆盖升级预检：本机章集合里**没有任何一枚**等于清单声明的章 ⇒ 拦。
     *
     * 缺席口径与 core 的 `certBlocksInstall` 逐字一致：本机读不出（空集合）或清单没声明
     * （`normalizeSha` 不过）一律**不拦**——发布者自愿多给的信息不能变成硬条件。
     */
    fun certBlocksInstallAny(localCertSha256: Collection<String>, declaredCertSha256: String): Boolean {
        val declared = UpdatePolicy.normalizeSha(declaredCertSha256) ?: return false
        val locals = localCertSha256.mapNotNull { UpdatePolicy.normalizeSha(it) }.toSet()
        if (locals.isEmpty()) return false
        return declared !in locals
    }

    /**
     * L4-5：下载完成的整包，其**实际签名**是否与清单声明的章一致。
     *
     * 下载前那道预检（[certBlocksInstallAny]）比的是「本机章 vs 清单声明章」，拦的是
     * 「装了也覆盖不上」；这里比的是「清单声明章 vs 下载到的包**真的**用哪把钥匙签的」——
     * 清单写错章、镜像上放了别的 key 重签的包时，只有这一道能在把包交给系统安装器之前拦住，
     * 否则玩家要白下几百 MiB、再吃一句系统英文报错（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
     *
     * @param declaredCertSha256 清单声明的章；**空串/坏形状 = 未声明 ⇒ 跳过**（返回 true，
     *   与 core 的 `certBlocksInstall` 缺席口径一致：发布者自愿多给的信息不变成硬条件）
     * @param actualSignerDers 从下载到的 APK 里读出的 signer 证书 DER（多 signer 全部）；
     *   空表 = 读不出签名 ⇒ 不可接受（返回 false，由调用方删包报失败）
     */
    fun apkCertSatisfied(declaredCertSha256: String, actualSignerDers: List<ByteArray>): Boolean {
        val declared = UpdatePolicy.normalizeSha(declaredCertSha256) ?: return true
        if (actualSignerDers.isEmpty()) return false
        return declared in actualSignerDers.map { sha256Hex(it) }.toSet()
    }

    private fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}
