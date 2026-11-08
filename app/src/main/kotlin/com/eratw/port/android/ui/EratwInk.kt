package com.eratw.port.android.ui

import androidx.compose.ui.graphics.Color

/**
 * 壳层（设置页 + 游戏外壳 + 各类弹窗）的**唯一墨色源**。
 *
 * ## 为什么一份表要出两种形状
 * 壳层同时存在两套绘制面：设置页与游戏内浮层是 Compose，而存档迁移 / UPDATECHECK / 更新推荐
 * 那几颗弹窗是平台 `android.app.AlertDialog`（View 面，见 [EratwShellDialog]）。View 面吃
 * `@ColorInt` 整数，Compose 面吃 [Color]，所以每个 token 两个视图各一个：[argb] 是权威值，
 * [ink] 只是它的 Compose 包装。**新增颜色只写一次 `argb(...)`**，两边自动同色。
 *
 * ## 为什么用 `fun` 而不是 `val`
 * [Color] 不是 `const` 可表达的类型，而 `val` 字段在 Compose 重组里读没问题、在 `object` 初始化
 * 期却会与本对象的其它字段互相牵出初始化顺序问题。`fun` 让两边都不依赖初始化顺序，编译器也
 * 不会在 Compose 函数里报「不稳定读取」。取值成本是一次内联构造，可以忽略。
 *
 * 命名口径：`Ink` 系列只说**明暗层级**（底 / 面板 / 描边 / 字），语义色（警告、危险、分区）
 * 单独点名，避免「这块灰到底算背景还是算描边」这种二次解释。
 */
object EratwInk {

    // ---- 底与面板 ----

    /** 整页底色：纯黑，与主界面 `EratwTheme` 的 background 同值。 */
    fun backgroundArgb(): Int = 0xFF000000.toInt()

    /** 卡片与弹窗面板底。 */
    fun panelArgb(): Int = 0xFF262626.toInt()

    /** 次级面板（顶栏副信息条、底部保存条、平台弹窗底）。 */
    fun panelAltArgb(): Int = 0xFF1B1B1B.toInt()

    /** 浮层卡片底（长按提示卡一类的「压在画面之上」的层）。 */
    fun raisedArgb(): Int = 0xFF2A2A2A.toInt()

    // ---- 描边 ----

    /** 通用发丝描边（卡片 1dp 边、对话框边线）。 */
    fun borderArgb(): Int = 0xFF3A3A3A.toInt()

    /** 压在画面之上的浮层用的更亮一档描边。 */
    fun hairlineArgb(): Int = 0xFF454545.toInt()

    // ---- 字 ----

    /** 正文。 */
    fun textArgb(): Int = 0xFFEDEDED.toInt()

    /** 次级正文（说明段、正文里的次要句）。 */
    fun textDimArgb(): Int = 0xFFB0B0B0.toInt()

    /** 注脚与标签（11sp 那一档）。 */
    fun textMutedArgb(): Int = 0xFF8A8A8A.toInt()

    // ---- 语义色 ----

    /** 主操作绿（按钮底、关于档标题、开关轨道）。 */
    fun accentArgb(): Int = 0xFF9FC79F.toInt()

    /** 主按钮底。 */
    fun primaryButtonArgb(): Int = 0xFF3F6B3F.toInt()

    /** 「把东西交出去」那一颗按钮的底（分享报告：不依赖本应用网络，是玩家唯一送得出去的路）。 */
    fun shareButtonArgb(): Int = 0xFF2F4B6B.toInt()

    /** 需要玩家知道、但不算坏消息的提醒橙。 */
    fun warnArgb(): Int = 0xFFFFB060.toInt()

    /** 告警黄（口上解析告警一类的「有内容但能玩」）。 */
    fun amberArgb(): Int = 0xFFE0A04F.toInt()

    /** 危险 / 失败 / 已回退。 */
    fun dangerArgb(): Int = 0xFFE86E7A.toInt()

    /** 危险条目的底色（约 13% 不透明度，只用于整块警示背景）。 */
    fun dangerFillArgb(): Int = 0x22E86E7A

    // ---- 分区主题色（设置页四档） ----

    /** 移植版功能：浅青（沿用老设置页的卡片标题色，不动老玩家的既视感）。 */
    fun zonePortArgb(): Int = 0xFF7FD4FF.toInt()

    /** 原版引擎：紫 —— 与警告橙、按钮绿都不撞色。 */
    fun zoneEngineArgb(): Int = 0xFFB9A0F0.toInt()

    /** 素材与补丁：低饱和铜金 —— 与告警橙同暖调但更暗，读起来是「素材」不是「出错」。 */
    fun zoneModArgb(): Int = 0xFFD6A96B.toInt()

    /** 关于与反馈：主绿的亮档，读起来是「信息」而不是「可改的选项」。 */
    fun zoneAboutArgb(): Int = 0xFF9FC79F.toInt()

    // ---- 遮罩 ----

    /** 弹窗遮罩：压在立绘/画面上时用它，正文读得清。 */
    fun scrimArgb(): Int = 0x99000000.toInt()

    /** 轻遮罩：长按提示这类「不该把画面压死」的浮层。 */
    fun scrimLightArgb(): Int = 0x66000000.toInt()

    // ---- Compose 侧包装 ----

    fun background(): Color = Color(backgroundArgb())

    fun panel(): Color = Color(panelArgb())

    fun panelAlt(): Color = Color(panelAltArgb())

    fun raised(): Color = Color(raisedArgb())

    fun border(): Color = Color(borderArgb())

    fun hairline(): Color = Color(hairlineArgb())

    fun text(): Color = Color(textArgb())

    fun textDim(): Color = Color(textDimArgb())

    fun textMuted(): Color = Color(textMutedArgb())

    fun accent(): Color = Color(accentArgb())

    fun primaryButton(): Color = Color(primaryButtonArgb())

    fun shareButton(): Color = Color(shareButtonArgb())

    fun warn(): Color = Color(warnArgb())

    fun amber(): Color = Color(amberArgb())

    fun danger(): Color = Color(dangerArgb())

    fun dangerFill(): Color = Color(dangerFillArgb())

    fun zonePort(): Color = Color(zonePortArgb())

    fun zoneEngine(): Color = Color(zoneEngineArgb())

    fun zoneMod(): Color = Color(zoneModArgb())

    fun zoneAbout(): Color = Color(zoneAboutArgb())

    fun scrim(): Color = Color(scrimArgb())

    fun scrimLight(): Color = Color(scrimLightArgb())
}
