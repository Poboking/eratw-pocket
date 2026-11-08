package com.eratw.port.android

/**
 * 「匿名玩家数统计」设置项的三语文案表（ZH / EN / JA）。
 *
 * ## 为什么独立成表，不往 [SettingsStrings] 里加两个字段
 *
 * [SettingsStrings] 是两百多枚字段的 data class，它的构造函数参数数已经贴着
 * JVM 方法描述符「一个方法最多 255 个参数槽」的上限。再塞两枚 String 编译能过，
 * 运行期加载类却会抛 ClassFormatError: Too many arguments in method signature
 * （2026-11-08 实测，连既有用例一起红）。新增文案一律另起小表，语言只经
 * [SettingsLanguage] 单路取，形状照 [UpdateCopy]，不碰那张大表。
 *
 * 隐私口径写进 hint：默认开、可关闭、只发一枚随机编号、不含设备号/账号/位置/存档。
 * 徽章侧的完整说明在公开仓 README，两边措辞同源。
 */
data class PlayerStatsCopy(
    val lang: SettingsLanguage,
    val title: String,
    val hint: String,
) {
    companion object {

        private val ZH = PlayerStatsCopy(
            SettingsLanguage.ZH,
            "匿名玩家数统计",
            "默认开。每天冷启时报一次随机编号，只用于 README 上的累计人数与今日活跃；不含设备号、账号、位置或存档。关掉后既不出网也不生成编号。",
        )

        private val EN = PlayerStatsCopy(
            SettingsLanguage.EN,
            "Anonymous player count",
            "On by default. Sends one random ID per day at launch, used only for the total and today badges in the README. No device ID, account, location or save data. Turn this off to stop reporting and ID generation altogether.",
        )

        private val JA = PlayerStatsCopy(
            SettingsLanguage.JA,
            "匿名プレイヤー数",
            "既定はオン。起動時に一日一回ランダム ID を送り、README の累計・本日のバッジだけに使います。端末 ID・アカウント・位置・セーブデータは含みません。オフにすると送信も ID 生成も行いません。",
        )

        /** 与设置页 `text.lang` 同源的单路取值；不认识的值回落 [SettingsLanguage.DEFAULT]。 */
        fun of(lang: SettingsLanguage): PlayerStatsCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }
    }
}
