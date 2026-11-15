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
 * 2026-11-09 起开关从启动卡迁到「关于」档的独立卡片，正式说明四段
 * （detailTitle / purposeBody / privacyBody / controlBody）随卡片上屏。
 */
data class PlayerStatsCopy(
    val lang: SettingsLanguage,
    val title: String,
    val hint: String,
    /** 关于页说明节的节名。 */
    val detailTitle: String,
    /** 用途：为什么做这项统计、结果给谁看。 */
    val purposeBody: String,
    /** 范围：收什么、不收什么、编号能否关联身份。 */
    val privacyBody: String,
    /** 控制：默认态、关闭后的行为、重新开启。 */
    val controlBody: String,
) {
    companion object {

        private val ZH = PlayerStatsCopy(
            SettingsLanguage.ZH,
            "匿名玩家数统计",
            "默认开。每天冷启时报一次随机编号，只用于 README 上的累计人数与今日活跃；不含设备号、账号、位置或存档。关掉后既不出网也不生成编号。",
            "匿名统计说明",
            "本应用附带一项匿名玩家规模统计，用于帮助开发团队了解移植版的玩家社区规模与每日活跃情况。统计结果仅以聚合计数形式呈现（项目 README 的累计玩家数与今日活跃徽章），不用于任何个体层面的分析或商业目的。",
            "统计仅在本机生成一枚随机匿名编号，每日冷启动时至多上报一次。上报内容不含设备标识、账号信息、地理位置、存档内容或任何游玩记录；编号无法关联至真实身份。未配置上报通道的构建不会发出任何统计请求。",
            "该统计默认开启，您可随时在此关闭。关闭后应用立即停止相关网络上报，并不再为本机生成或保存匿名编号；重新开启后恢复每日至多一次的统计。开关状态随时可改，不影响游戏本体功能。",
        )

        private val EN = PlayerStatsCopy(
            SettingsLanguage.EN,
            "Anonymous player count",
            "On by default. Sends one random ID per day at launch, used only for the total and today badges in the README. No device ID, account, location or save data. Turn this off to stop reporting and ID generation altogether.",
            "About anonymous statistics",
            "This app includes an anonymous player-count statistic that helps the development team understand the size and daily activity of the port's player community. Results are shown only as aggregate counts (the total-players and today-active badges in the project README) and are never used for individual-level analysis or any commercial purpose.",
            "The statistic generates one random anonymous identifier on this device and reports it at most once per day at cold launch. The report contains no device identifier, account information, location, save contents or gameplay records, and the identifier cannot be linked to a real identity. Builds without a reporting channel configured send no statistics requests at all.",
            "The statistic is enabled by default and can be turned off here at any time. Once disabled, the app immediately stops the related network reports and no longer generates or stores an anonymous identifier for this device; re-enabling resumes the at-most-once-daily statistic. The choice can be changed at any time and does not affect gameplay.",
        )

        private val JA = PlayerStatsCopy(
            SettingsLanguage.JA,
            "匿名プレイヤー数",
            "既定はオン。起動時に一日一回ランダム ID を送り、README の累計・本日のバッジだけに使います。端末 ID・アカウント・位置・セーブデータは含みません。オフにすると送信も ID 生成も行いません。",
            "匿名統計に関する説明",
            "本アプリには匿名のプレイヤー数統計が付属しており、移植版のプレイヤーコミュニティの規模と日々のアクティブ状況を把握するために用いられます。結果は集計値としてのみ表示され（プロジェクト README の累計プレイヤー数と本日アクティブのバッジ）、個人単位の分析や商業目的には一切使用されません。",
            "統計は本端末でランダムな匿名識別子を 1 つ生成し、毎日のコールド起動時に最大 1 回だけ送信します。送信内容に端末識別子・アカウント情報・位置情報・セーブ内容・プレイ記録は含まれず、識別子が実際の身元に関連付けられることはありません。送信チャネルが設定されていないビルドは統計リクエストを一切送信しません。",
            "本統計は既定でオンになっており、ここでいつでもオフにできます。オフにするとアプリは関連するネットワーク送信を直ちに停止し、本端末の匿名識別子の生成も保存も行いません。再度オンにすると一日最大 1 回の統計が再開されます。この設定はいつでも変更でき、ゲーム本体の機能には影響しません。",
        )

        /** 与设置页 `text.lang` 同源的单路取值；不认识的值回落 [SettingsLanguage.DEFAULT]。 */
        fun of(lang: SettingsLanguage): PlayerStatsCopy = when (lang) {
            SettingsLanguage.ZH -> ZH
            SettingsLanguage.EN -> EN
            SettingsLanguage.JA -> JA
        }
    }
}
