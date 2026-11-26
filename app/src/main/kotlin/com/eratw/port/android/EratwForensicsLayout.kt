package com.eratw.port.android

import com.eratw.port.core.proc.ForensicsLayout
import com.eratw.port.core.proc.ForensicsLabels

/**
 * eraTW 语料（4.980 / 4.981）的现场诊断坐标——**eraTW 业务知识的唯一落点**。
 *
 * ## 为什么在 app 宿主侧而不在 core
 * 下列数值全部来自 eraTW 随包语料的 AutoConst 头（出处逐项见 [ERATW] 字段 KDoc 与本文件下方溯源），
 * 是**语料配置**而非引擎知识。2026-11-19 兼容层解耦前它们硬编码在 core 主源集，导致任何复用
 * 引擎的 era 游戏 jar 都携带 eraTW 语料坐标（业务零耦合铁律的唯一实质破口）。
 * 现外置到 app：别的 era 游戏宿主不提供本文件、不注入本布局 ⇒ core 诊断口整段 isEmpty。
 *
 * 通用读数算法仍留在 core（[com.eratw.port.core.proc.datingForensics]），只认注入的坐标参数。
 *
 * ## 溯源（AutoConst 头，改语料同步这里）
 * - CFLAG_约会中=12（AutoConst_CFLAG.ERH:24）/ 現在位置=300（:90）/ 同行=320（:105）
 * - FLAG_约会的对象=73（AutoConst_FLAG.ERH:63）
 * - TFLAG_约会道中=229（AutoConst_TFLAG.ERH:219-220）/ 一日一回=199（:124）
 * - TALENT_路人=300（AutoConst_Talent.ERH:249）
 * - 基准变量 MAIN_MAP / RANDOM_CHARANUM / GENSOKYO_MAP_FIX / GMF_HOME_MAP 均为 eraTW `#DIM SAVEDATA`
 */
object EratwForensicsLayout {
    /**
     * eraTW 现场诊断布局。注入 core 后行为与解耦前逐格等价（4.980/4.981 双口径、道中、路人闸）。
     */
    @JvmField
    val ERATW = ForensicsLayout(
        mainMapVar = "MAIN_MAP",
        mainMapIndex = 0L,
        randomCharaVar = "RANDOM_CHARANUM",
        gmfFixVar = "GENSOKYO_MAP_FIX",
        gmfHomeMapVar = "GMF_HOME_MAP",
        masterVar = "MASTER",
        cflagVar = "CFLAG",
        talentVar = "TALENT",
        tflagVar = "TFLAG",
        flagVar = "FLAG",
        cflagDating = 12L,
        cflagPosition = 300L,
        cflagTogether = 320L,
        flagAffairTarget = 73L,
        tflagDateTravel = 229L,
        tflagOncePerDay = 199L,
        talentMobgirl = 300L,
        forgetBit = 9,
        gmfMapBit = 0,
        mismatchDetailLimit = 12,
    )

    /**
     * eraTW 现场诊断的中文展示标签——文案与解耦前逐字一致（钉在报告/测试断言上）。
     * 这是 eraTW 中文业务文案的唯一落点；core 侧只保留中性英文 [ForensicsLabels.DEFAULT]。
     */
    @JvmField
    val ERATW_LABELS = ForensicsLabels(
        title = "约会现场：",
        mainMap = "MAIN_MAP",
        gmfBit = "GMF位",
        gmfHomeMap = "GMF家MAP",
        homeMap = "家MAP",
        mismatch = "约会中≠MAIN_MAP",
        homeMismatch = "约会中≠家MAP",
        detailPrefix = "明细",
        homeDetailPrefix = "家明细",
        dateTravel = "约会道中",
        masterDate = "MASTER约会中",
        masterPos = "MASTER现在位置",
        masterTogether = "MASTER同行",
        affairTarget = "约会的对象",
        targetDate = "对象约会中",
        targetPos = "对象现在位置",
        targetTogether = "对象同行",
        randomChara = "路人号",
        mobTalent = "路人天赋",
        forgetBit = "忘记位",
    )
}
