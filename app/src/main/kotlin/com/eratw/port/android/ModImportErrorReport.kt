package com.eratw.port.android

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * KI-4 报错专页的纯层（2026-11-23）：导向行判据 + 错误报告拼装。
 *
 * 单独成文件是照 [ModImportBatchResultUi] 的同款理由：`ModImportUi.kt` 的顶层函数数已冻结
 * 在 detekt 文件级上限（加了这三个纯函数就顶过 11），本文件只吃 [ModImportFailureKind] 与
 * [ModImportCopy]，不碰状态机与渲染。上屏点 = `ModImportUi.FailureBlock` / `SuccessBlock`。
 */

/**
 * 报错页的导向行（KI-4 ②）：按失败类别指明该找谁——
 *
 * - COMPILE / IDENTITY：错误来自口上包自身（编译错误 / 身份不符是包内容），移植版修不了，
 *   玩家该把报告转给口上作者；
 * - STORAGE：本机存储问题，先自查空间 / 权限，仍失败才轮到移植版；
 * - UNEXPECTED：内部兜底，一律带报告找移植版。
 *
 * 纯函数（Compose 里判不到，单测逐类断言）。
 */
internal fun modImportFailureGuidance(kind: ModImportFailureKind, copy: ModImportCopy = ModImportCopy.ZH): String =
    when (kind) {
        ModImportFailureKind.COMPILE, ModImportFailureKind.IDENTITY -> copy.failureGuidanceAuthorFormat
        ModImportFailureKind.STORAGE -> copy.failureGuidanceStorageFormat
        ModImportFailureKind.UNEXPECTED -> copy.failureGuidanceUnexpectedFormat
    }

/**
 * 报告/读数面的本地时间戳（ISO 形 `yyyy-MM-dd HH:mm`，固定 [Locale.ROOT]）：
 * 转给口上作者 / 移植版的报告要能对时，不随设备语言漂移。纯函数。
 */
internal fun formatImportTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(millis))

/**
 * 一键复制的错误报告全文（KI-4 ③）：玩家转给口上作者 / 移植版的正文——
 *
 * ```
 * <errorReportTitle>
 * <errorReportKindFormat 填 modImportFailureTitle>
 * <errorReportTimeFormat 填 formatImportTimestamp>
 * <来源行（[sourceNames] 非空才出，逗号连名）>
 * ----
 * <message 全文不截断（编译错误自带 文件:行）>
 * ```
 *
 * 拼装是纯函数（[nowMillis] 由调用方注入，单测钉死时间戳）；message 原文不吞不改
 * （I7 可定位纪律）。[sourceNames] 取预览态的包显示名（Unavailable 时空表 = 省行）。
 */
internal fun modImportErrorReportText(
    kind: ModImportFailureKind,
    message: String,
    copy: ModImportCopy = ModImportCopy.ZH,
    nowMillis: Long,
    sourceNames: List<String> = emptyList(),
): String = buildList {
    add(copy.errorReportTitle)
    add(copy.fill(copy.errorReportKindFormat, modImportFailureTitle(kind, copy)))
    add(copy.fill(copy.errorReportTimeFormat, formatImportTimestamp(nowMillis)))
    if (sourceNames.isNotEmpty()) {
        add(copy.fill(copy.errorReportSourcesFormat, sourceNames.joinToString(", ")))
    }
    add("----")
    add(message)
}.joinToString("\n")
