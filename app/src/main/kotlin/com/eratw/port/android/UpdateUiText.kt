package com.eratw.port.android

import com.eratw.port.core.update.UpdatePolicy

/**
 * 更新 UI 的**纯判读层**：弹窗文案、蜂窝判定、手动检查报障、已最新版读数，全部是与 Android
 * 无关的字符串/布尔函数（[UpdateUi] 与设置页的更新卡都从这两个判读 object 取文案，
 * 下载进度与结局话术在 [UpdateProgressText]，设置页那条通道现状在 [UpdateChannelReadout]）。
 *
 * 为什么拆出来：壳层 UI 代码没法在纯 JVM 单测里跑（`Looper.getMainLooper()` 直接炸），
 * 而这一层钉的恰是「文案说没说清、该不该二次确认、跳没跳过」这类**判读**——它们才是
 * 玩家会不会被误导的地方。
 *
 * ## EP4 U3a：句子全部来自 [UpdateCopy]
 *
 * 本层的**玩家面句子一条都不许多写在这里**（三语表 [UpdateCopy] 是唯一词源，中文那一组与
 * 搬动前的硬编码逐字相同）。约定是契约 §1 定死的：
 * - 需要玩家面词句的函数**第一个参数收 `copy: UpdateCopy`**，其余参数与判据一律不动；
 * - 分支、行数、顺序、留白这些**结构**留在本层，表里只放词（带读数的句子是 `{0}` 模板）；
 * - 语言由壳层一次取好传进来（`UpdateUi.copyFor` → `UpdateCopy.of(UiPrefs.settingsLanguage(...))`），
 *   本层不判语言、不读系统 Locale；
 * - 不需要文案的判定（[cellularConfirmRequired]、[notificationPermissionNeeded]、[skipCodeForReminder]、
 *   [installBlockedMessage]）没有 `copy` 参数，别为凑签名加一个。
 *
 * 措辞口径不变：玩家面零基础、不出现「镜像 / 清单 / 摘要」这类术语（UpdateManager 给出的
 * problem 原话本就按这条口径写）。本文件的 KDoc 与 logcat 面可以留中文——那是给改代码的人看的。
 *
 * EP4 U0 拆分：本文件归 U2 车道（检查态与通道现状），[UpdateProgressText] 归 U1 车道（下载过程可见性）。
 * EP4 U3a 三语化：本文件与 [UpdateChannelReadout] 的词全部搬进 [UpdateCopy]。
 */
object UpdateUiText {

    /** 跳过的版本在设置页上怎么说；没跳过过（≤0）返回 null 不占行。 */
    const val NO_SKIPPED_VERSION: Long = 0L

    /**
     * 玩家确认后要下的那件东西（超集规则：能装新版就只装新版，否则才谈数据包）。
     *
     * 数据包用 `offer.dataArtifact`（core 已按「差量可用则差量」判好，契约 §1.2）；
     * 只有老形态（没带这个字段的调用方）才回落到 `assets.artifact` 的全量。
     *
     * `installBlockedReason` 非空 = 这台设备的签名章与发布章不同（契约 §1.7）：**整包一票否决**
     * （让玩家白下几百 MiB 再收一个系统英文报错是最坏的失败形状），但数据通道不受安装器互斥
     * 影响，照常可下。下载管线侧还有第二道同源拦截（`UpdateManager.download`），这里是第一道。
     *
     * 单子上那个给玩家看的名字（[DownloadJob.label]）按语言取（EP4 U3a）：它是弹窗、
     * 蜂窝确认与下载通知共用的一张单，标签跟着玩家的语言走。
     */
    fun jobFor(copy: UpdateCopy, offer: UpdateManager.UpdateOffer): DownloadJob? {
        val info = offer.info
        val apk = info.apk?.takeIf { offer.apkNeeded && offer.installBlockedReason == null }
        if (apk != null) return DownloadJob(apk.artifact, UpdateDownloadKind.Apk, copy.apkLabel, 0L)
        val assets = info.assets?.takeIf { offer.assetsNeeded } ?: return null
        val artifact = offer.dataArtifact ?: assets.artifact
        return DownloadJob(artifact, UpdateDownloadKind.Assets, copy.assetsLabel, assets.version)
    }

    /** 蜂窝要不要二次确认：口径在 core 的 `UpdatePolicy.autoDownloadAllowed`，这里只是接线。 */
    fun cellularConfirmRequired(isUnmetered: Boolean): Boolean = !UpdatePolicy.autoDownloadAllowed(isUnmetered)

    /** 蜂窝确认弹窗正文：说清要下多少（sizeLabel 向上取整，不报假精确）。 */
    fun cellularConfirmBody(copy: UpdateCopy, job: DownloadJob): String =
        copy.fill(copy.cellularConfirmBodyFormat, job.label, UpdatePolicy.sizeLabel(job.artifact.sizeBytes))

    /**
     * 弹窗正文（不含按钮）：**最多三行 + 强制档的置顶行**，每行都是玩家做决定前必须知道的
     * 事（契约 EP4 U2-⑤；强制更新档为 CONTRACT-UPD1-3LANE §2 U1 病②追加）。
     *
     * 之前这里还塞着「最长 500 字的一整段更新说明」，再叠三个按钮 ⇒ 小屏上「下载」被顶出可视区，
     * 玩家看到的是「有更新但我点不到」。长说明与提醒原话搬去 [offerDetailLines]，
     * 由弹窗上的「看说明」入口展开。
     *
     * 强制档（[UpdateManager.UpdateOffer.forceUpdate]）命中时正文第一行是
     * 「停止支持，必须更新」：坏消息与既有「章不合」同形状 —— 必须在第一行送达，
     * 且后面三行名额的排法不变（强制行挤进去后数据行/流量行顺延，总行数 +1）。
     *
     * 名额按重要性排：强制档置顶行、章不合的坏消息（这件事绝不能进折叠层）、数据包行、流量提示。
     * 行数与名额的排法与语言无关（EP4 U3a：三种语言都是同样的几行）。
     */
    fun offerMessageLines(copy: UpdateCopy, offer: UpdateManager.UpdateOffer, onCellular: Boolean): List<String> {
        val info = offer.info
        val lines = ArrayList<String>()
        val apk = info.apk?.takeIf { offer.apkNeeded }
        val assets = info.assets?.takeIf { offer.assetsNeeded }
        if (apk == null && assets == null) return emptyList()
        // 玩家认的是 `0.2.41` 这种 versionName，不是裸 versionCode；读不出才回落数字。
        val localVersion = offer.currentVersionName.ifBlank { offer.currentVersionCode.toString() }
        if (offer.forceUpdate) {
            lines.add(copy.offerForceUpdateLine)
        }
        val blocked = offer.installBlockedReason
        // 纯数据更新（整包没有可更或已最新）：先解释「安装包与数据是两件事」，再给数据行 ——
        // 2026-11-03 玩家实测反馈的困惑形状：手动装完最新 APK 后弹「有更新」，玩家以为还要
        // 再装一遍。数据行的体积与「下次启动自动应用」照旧放在它自己那行；解释行只补一句
        // 「这台机器上的安装包是哪个版本」（2026-11-06 主人要求弹窗要显示版本号），不重复体积读数。
        if (assets != null && apk == null) {
            lines.add(copy.fill(copy.offerAssetsOnlyExplainerLine, localVersion))
        }
        if (apk != null) {
            val head = copy.fill(copy.offerApkHeadFormat, apk.versionName, localVersion)
            if (blocked == null) {
                lines.add(copy.fill(copy.offerApkSizeLineFormat, head, UpdatePolicy.sizeLabel(apk.artifact.sizeBytes)))
            } else {
                lines.add(copy.fill(copy.offerApkBlockedLineFormat, head, installBlockedMessage(blocked)))
            }
        }
        // 数据行的出现条件与 jobFor 严格同规则：整包能装就只谈整包（超集规则），装不上/没有才谈数据。
        if (assets != null && (apk == null || blocked != null)) {
            val artifact = offer.dataArtifact ?: assets.artifact
            val delta = if (artifact.fileName != assets.artifact.fileName) copy.offerDeltaMarker else ""
            lines.add(
                copy.fill(
                    copy.offerAssetsLineFormat,
                    assets.version,
                    offer.installedDataVersion,
                    UpdatePolicy.sizeLabel(artifact.sizeBytes),
                    delta,
                )
            )
        }
        if (onCellular && jobFor(copy, offer) != null) {
            lines.add(copy.offerCellularLine)
        }
        return lines
    }

    /**
     * 弹窗「看说明」展开层的内容：长更新说明 + 发布那边留下的提醒原话。
     *
     * 返回空表 = 没东西可展开，壳层据此**不给**展开入口（别在屏上留一个点了没反应的按钮）。
     * 提醒原话是发布侧的产物（core 原话），本层只给它加一句本语言的前缀。
     */
    fun offerDetailLines(copy: UpdateCopy, note: String, warnings: List<String>): List<String> {
        val lines = ArrayList<String>()
        if (note.isNotEmpty()) lines.add(copy.fill(copy.offerNoteLineFormat, note))
        lines.addAll(warnings)
        return lines
    }

    /**
     * 弹窗标题按更新形状分：带整包的照旧「有可用的更新」；纯数据更新（整包没有可更）换
     * [UpdateCopy.dialogOfferAssetsTitle] —— 标题若不区分，玩家会把数据更新误读成
     * 「又要装一遍安装包」（2026-11-03 玩家实测反馈的困惑形状，正是这条优化的动机）。
     * 判读只看 apkNeeded：章不合（installBlockedReason）也属于「整包在谈」的形状，
     * 坏消息的标题不必换。
     */
    fun offerDialogTitle(copy: UpdateCopy, offer: UpdateManager.UpdateOffer): String =
        if (offer.apkNeeded) copy.dialogOfferTitle else copy.dialogOfferAssetsTitle

    /**
     * 弹窗正文里「要下的那件东西」所在行的下标（壳层给它加粗放大），没有可强调的行返回 -1。
     *
     * 判读依据是 [offerMessageLines] 的行顺序定式，**不做字符串匹配**：纯数据更新时首行是
     * 解释行、数据行跟在第 1 位；带整包时整包行就是首行 —— 章不合的坏消息也在这一行，
     * 与既有裁定「坏消息必须正文第一行送达」同形状，强调位让给它。流量行永远在尾部、
     * 永远不是重点，壳层按自己的 onCellular 信息决定灰化，不走这里。
     */
    fun offerHeadlineIndex(offer: UpdateManager.UpdateOffer, lineCount: Int): Int {
        if (lineCount <= 0) return -1
        return if (offer.apkNeeded) 0 else 1.coerceAtMost(lineCount - 1)
    }

    /** 展开入口的标签：有提醒原话时把数量写在标签上，玩家不点开也知道「这里面有东西」。 */
    fun offerExpandLabel(copy: UpdateCopy, warningCount: Int): String =
        if (warningCount > 0) copy.fill(copy.offerExpandCountFormat, copy.offerExpandLabel, warningCount)
        else copy.offerExpandLabel

    /**
     * 发布那边留下的「这个包没看懂的写法」的玩家面一句话（契约 U2-④：warnings 早就在生产，
     * 一直没人在屏上消费）。
     *
     * 措辞守两条：**不夸大成「没事」**（坏掉的可选组会让这次回落全量，代价是玩家多下几个 GiB），
     * **也不甩锅给玩家**（这些记录来自 core 的宽容处理，处理人是发版的人）。
     */
    fun warningsNote(copy: UpdateCopy, warningCount: Int): String? =
        if (warningCount <= 0) null else copy.fill(copy.warningsNoteFormat, warningCount)

    /** core 给的章不合原因带 markdown 强调（`**`），屏上没有 markdown 渲染，剥掉再上屏。 */
    fun installBlockedMessage(reason: String): String = reason.replace("**", "")

    /**
     * 「以后都不」能钉住的版本码；纯数据更新没有整包版本，返回 -1（弹窗不给这个按钮）。
     *
     * 强制更新档（[UpdateManager.UpdateOffer.forceUpdate]）返回 0：这一档是发布方宣布
     * 「低于 min-supported-version 的版本停止支持」，玩家点过一次「跳过」就把一个
     * 必须送达的提醒永远静音，恰恰是这档要治的病（侧载没有商店强制升级兜底）。
     */
    fun skipCodeForReminder(offer: UpdateManager.UpdateOffer): Long =
        if (offer.forceUpdate) 0L else offer.info.apk?.versionCode ?: -1L

    /** 点了「以后都不」之后 Toast 的一句：钉的是这一个版本，不是关掉整条通道。 */
    fun skippedToast(copy: UpdateCopy, versionCode: Long): String =
        copy.fill(copy.skippedToastFormat, versionCode, copy.reminderRestorePhrase)

    /** 设置页「启动」卡里小字：跳过状态（供通道现状与单测钉文案）。 */
    fun skippedVersionNote(copy: UpdateCopy, skippedVersionCode: Long): String? =
        if (skippedVersionCode <= NO_SKIPPED_VERSION) null
        else copy.fill(copy.skippedNoteFormat, skippedVersionCode, copy.reminderRestorePhrase)

    /** 「检查已经在跑了，别再点第三次」：单飞闸挡住重复点击时上屏的一句话。 */
    fun checkBusyReport(copy: UpdateCopy): String = copy.fill(copy.checkBusyFormat, copy.checkingLabel)

    /** 问玩家开通知授权前先说清「为什么问」。 */
    fun notificationRationale(copy: UpdateCopy, job: DownloadJob): String =
        copy.fill(copy.notificationRationaleFormat, UpdatePolicy.sizeLabel(job.artifact.sizeBytes))

    /** API 33 起通知是运行时权限（低于 33 恒不需要）。 */
    fun notificationPermissionNeeded(sdkInt: Int): Boolean = sdkInt >= 33

    /** 「包里没配源」：处理人是出包的人（没带更新地址参数），不是玩家的网络。 */
    fun noBuiltInMirrorReport(copy: UpdateCopy): String = copy.noBuiltInMirrorLine

    /** 「配了但连不上」：处理才是玩家的网络/发布那边；一条源没取到就把每条的名字报出来。 */
    fun checkFailedReport(
        copy: UpdateCopy,
        mirrorCount: Int,
        problems: List<String>,
        warningCount: Int = 0,
    ): String {
        val detail = if (problems.isEmpty()) copy.allMirrorsFailedPhrase else problems.joinToString(copy.problemSeparator)
        val head = copy.fill(copy.checkFailedFormat, mirrorCount, detail)
        return withWarnings(copy, head, warningCount)
    }

    /** 检查本身抛了未预期的错：说人话 + 留异常名给取证。 */
    fun checkCrashedReport(copy: UpdateCopy, error: Throwable): String =
        copy.fill(copy.checkCrashedFormat, error.javaClass.simpleName)

    /** 手动检查「已经是最新版」：当前版本两个读数都要报，玩家才信它真的查过了。 */
    fun upToDateReport(
        copy: UpdateCopy,
        currentVersionCode: Long,
        installedDataVersion: Long,
        warningCount: Int = 0,
    ): String {
        val head = copy.fill(copy.upToDateFormat, currentVersionCode, installedDataVersion)
        return withWarnings(copy, head, warningCount)
    }

    /** 检查成功但发布那边留了提醒：手动检查的结果一律上屏，所以这句话直接接在结果后面。 */
    fun withWarnings(copy: UpdateCopy, head: String, warningCount: Int): String {
        val note = warningsNote(copy, warningCount) ?: return head
        return "$head\n$note"
    }

    /** 设置页展开层的两个标签：展开态与收回态各一句（同一个入口的两种形状，不许两处各写各的）。 */
    fun techDetailLabel(copy: UpdateCopy, expanded: Boolean): String =
        if (expanded) copy.techCollapseLabel else copy.techExpandLabel

    /**
     * 「立即检查更新」按钮的标题：进行中在原词条后面加省略号。
     *
     * 基础词条由调用方传进来（那是设置页三语表 [SettingsStrings.checkUpdateButton] 的现成读数，
     * 本层不自己造句子，也不在这里判语言），所以英/日设置页的玩家看到的是自己语言 + 省略号，
     * 不会突然冒出一句中文。省略号是标点不是词，三语同一形态。
     */
    fun checkButtonLabel(baseLabel: String, checking: Boolean): String =
        if (checking) "$baseLabel…" else baseLabel

    // ------------------------------------------------------------------
    // U5：仅 Wi-Fi 下载（P2-10）与回前台补查（P1-6）的纯判定
    // ------------------------------------------------------------------

    /**
     * 「仅 Wi-Fi 下载」打开且当前不是 Wi-Fi（非计量网络）时，蜂窝这一次点下载要直接挡下。
     *
     * 纯判定，不碰 Android：网络类型由壳层用 `UpdateManager.isUnmetered` 算好传进来，
     * 开关读数由壳层从偏好取。两条口径都与 core 的
     * [com.eratw.port.core.update.UpdatePolicy.autoDownloadAllowed] 同源：`isUnmetered` 为真就放行。
     * 关（缺省）时永远返回 false ⇒ 既有蜂窝二次确认路径一个字都不改、照旧再问一次。
     *
     * 挡下之后给的是「已设为仅 Wi-Fi」的说明，**不给「仍要下载」的绕行**：
     * 想用流量必须回设置页把这个开关关掉，那是一次看清再决定的操作。
     */
    fun wifiOnlyBlocksCellular(wifiOnlyEnabled: Boolean, isUnmetered: Boolean): Boolean =
        wifiOnlyEnabled && !isUnmetered

    /**
     * 回前台要不要补一发自动检查（P1-6，纯判定，四闸全开才补）。
     *
     * - [channelEnabled]：出包带了更新地址且玩家没把自动检查关掉；
     * - [checkDue]：距上次查成功已过冷却（core 的 `UpdatePolicy.isCheckDue` 判好）；
     * - [checkInFlight]：单飞闸**空**才补，参数为真表示已有一次在跑；
     * - [processCheckDone]：本进程**不到点**才补。冷启那一发（`scheduleStartupCheck`）若因
     *   「没到冷却」或「通道关」被跳过，`startupCheckDone` 一直是 false，本进程从此没有自动检查 ——
     *   长驻进程就是这个病。补过这一发（或冷启排过）之后它为真，回前台不再重复弹窗、重复出网。
     *
     * 冷启首帧的那次 `onResume` 不算「回前台」，由壳层的首帧闸先挡掉，别抢在首屏画完之前。
     */
    fun shouldCheckOnResume(
        channelEnabled: Boolean,
        checkDue: Boolean,
        checkInFlight: Boolean,
        processCheckDone: Boolean,
    ): Boolean = channelEnabled && checkDue && !checkInFlight && !processCheckDone
}
