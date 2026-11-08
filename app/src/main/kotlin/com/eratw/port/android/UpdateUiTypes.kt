package com.eratw.port.android

import com.eratw.port.core.update.Artifact

// 更新 UI 的两个**纯数据类型**（EP4 U0 从 UpdateUi.kt 的段 C 拆出）。
//
// 装什么：下载落地走哪条通道（整包交给安装器 / 数据包登记暂存），以及「玩家已经点过下载」的成单。
// 为什么单独成文件：弹窗那一路与前台下载那一路都要造它、读它，而两边谁都不许改它 —— 拆出来之后
// 文件所有权才切得开（契约 §1 所有权表：本文件归组长保留，两 lane 只读，要加字段报红转组长）。

/** 下载下来之后怎么落地：整包交给安装器，数据包登记暂存。 */
enum class UpdateDownloadKind {
    Apk,
    Assets,
}

/**
 * 一次「玩家已经点过下载」的成单：要下哪件产物、属于哪条通道、给玩家看叫什么。
 *
 * 纯数据 —— 它能穿过弹窗、蜂窝确认、通知授权，最后原样交给 [UpdateDownloadService]。
 */
data class DownloadJob(
    val artifact: Artifact,
    val kind: UpdateDownloadKind,
    val label: String,
    val assetsVersion: Long,
)
