package com.eratw.port.android.market

import com.eratw.port.android.ImportedPatchDetail
import com.eratw.port.android.ModImportOutcome

/**
 * 「市场已装包 → 装进游戏」的结果密封族（CONTRACT-MKM-W2 §1 冻结面，五态不许加）。
 *
 * UI 层按态选文案：[NotFound] / [EmptyPack] 各自一句指路；[ImportFailed] 原样透出导入链
 * 的失败消息（编译错误带文件:行，I7 可定位）；[LedgerFailed] 的人话必须说明「导入本体已
 * 落盘、只有记账没成」——台账是簿记面，不是导入链的一部分（见 [MarketGameBridge] 的 ⑤）。
 */
sealed interface MarketBridgeOutcome {

    /**
     * 装进游戏成功：三件套已落 `mods/rt2/`、台账已记本次导入。[packageId] 是新产物的
     * 稳定身份（`kojo-` + 8 位 hex，取自导入详情的容器文件名剥掉扩展名）；[detail]
     * 是导入链的成功详情（函数数 / 入口 / 源名单 / 告警 / 总闸提示），UI 成功页直接渲染。
     */
    data class Ok(
        val packageId: String,
        val detail: ImportedPatchDetail,
    ) : MarketBridgeOutcome

    /** 已装包不在位：库目录里翻不到这个包（多半刚被卸载）。没有任何写入发生。 */
    data object NotFound : MarketBridgeOutcome

    /**
     * 包内容里没有可导入条目：`files/` 树过滤 `.ERB`/`.ERH` 后零条目。语义对齐空 zip
     * （ModImportController 的 zipNoEntries 面口径），但判定发生在**调用导入链之前**
     * （先过滤后调用）——导入链未被触达、mods/ 零落盘、台账零写入。指路文案归 UI 波。
     */
    data object EmptyPack : MarketBridgeOutcome

    /**
     * 导入链失败：[failure] 是 [ModImportController.import] 的原始 [ModImportOutcome.Failed]
     * （STORAGE / COMPILE / IDENTITY / UNEXPECTED 四类都从这里走）。台账**不写**（成功才写）；
     * 源留档（mods/ 里的 .ERB/.ERH）可能已落盘——那是导入链自身的「save 在 compile 前」
     * 契约序，与本桥无关。
     */
    data class ImportFailed(val failure: ModImportOutcome.Failed) : MarketBridgeOutcome

    /**
     * 簿记面失败：imports.jsonl 读写或旧版三件套删除没成。**导入本体可能已成功落盘**
     * （簿记在导入成功之后），[problem] 人话必须交代这一点；台账里没记下本次导入，
     * 下一次同包导入会照「无旧版」路径自愈（旧三件套存在才删）。
     */
    data class LedgerFailed(val problem: String) : MarketBridgeOutcome
}
