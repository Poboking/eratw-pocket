package com.eratw.port.android.market

/**
 * 「市场库卸载 → 装进游戏产物清理」联动的结果密封族（CONTRACT-MKM-W4-UNLINK §0.2 冻结面）。
 *
 * 文件落位（worker 自选，契约 §1 允许新文件或并入 [MarketBridgeOutcome]）：**独立成文件** ——
 * [MarketBridgeOutcome] 是 MKM-W2 的五态导入族（五态不许加），本族是 W4 卸载联动的另一张
 * 冻结面，各表各的契约锚，防两波语义互相渗透。
 *
 * **UI 接线归组长**（本波 API-only）：SettingsActivity / MarketUi 的卸载动作链在
 * `MarketService.uninstall` 成功后调 [MarketGameBridge.syncUninstalled]，按态选文案 ——
 * [Cleaned] / [Failed] 各一句人话，[Kept] 按调用方自己传的 remainingVersions 默认静默即可；
 * ui3 路并行在改，本波禁触 UI 面。
 */
sealed interface BridgeSyncOutcome {

    /**
     * 联动清理完成：三件套已删、台账记录已清，游戏里这个包清干净了。[packageId] 是刚清掉的
     * 包身份（`kojo-` + 8 位 hex，取自台账记录的 packageId）。
     */
    data class Cleaned(val packageId: String) : BridgeSyncOutcome

    /** 本次一个字节都没动：三件套与台账原样，[cause] 交代为什么不动。 */
    data class Kept(val cause: Cause) : BridgeSyncOutcome

    /**
     * 联动失败：[problem] 是人话。**市场卸载本身不受影响**（卸载语义在先且已成功，契约 §0.4）。
     * 删序 = 先产物后台账（契约 §0.3），故失败时产物可能已删一部分而台账记录还在 —— 这是保
     * 重试面的刻意形态：产物删除失败时再调一次 [MarketGameBridge.syncUninstalled] 即可续清；
     * 台账清理失败时产物已不在，重试得 Kept，残留台账行照契约 §0.3 的 Bless 形态等下次同包
     * 装回覆盖自愈（无孤儿闸风险）。
     */
    data class Failed(val problem: String) : BridgeSyncOutcome

    /**
     * [Kept] 的不动手理由。契约 §0.2 冻结 GamesAbsent / LedgerAbsent 两值；契约 §2.2 的
     * 多版本残留分支要求返回 Kept，而冻结两值在该分支没有不撒谎的取值（库还有版本 = 产物
     * 在装、台账也可能记着，既不 Absent 于盘也不 Absent 于账），故实作补第三值
     * [Cause.VersionsRemaining] —— 对冻结面纯增量、两个冻结值语义与产出点不变；差异已
     * 登记交付报告，需组长追认。
     */
    enum class Cause {

        /**
         * 库内还有其它版本在装（remainingVersions != 0）：游戏产物归「最新在装版本」口径
         * （契约 §0.1），卸完再装回 = 台账重建照既有 importInstalled，本次不联动。
         */
        VersionsRemaining,

        /** 台账查无此键（该包从未装进游戏）：零删动作，连台账文件都不建（空台账 no-op）。 */
        LedgerAbsent,

        /**
         * 游戏产物不在盘上（gcOrphans 清过 / 手删），台账有记录但零文件可删。归 Kept 不归
         * Cleaned 是 worker 对契约 §2.3 二选一的裁定（测试钉住）：一个字节不动比「顺手清
         * 台账」更保守，台账残留行是契约 §0.3 已 Bless 的安全态 —— 它只被 importInstalled
         * 消费（换包时照旧查旧版三件套，不在场 = 零删），下次同包装回覆盖自愈。
         */
        GamesAbsent,
    }
}
