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

    // ---- 壳层图标（键盘 / 螺旋 / 方向锁三枚同源）----
    // 与文字同值但职责不同：这三个值只在「描线图标」上出现，改字色不该顺带改图标。

    /** 图标静息描线（与 [textMutedArgb] 同值，但语义是「功能没开」）。 */
    fun iconIdleArgb(): Int = 0xFF8A8A8A.toInt()

    /** 图标激活（功能展开/锁定）：与游戏黑底同调的浅绿。 */
    fun iconActiveArgb(): Int = 0xFFB9D3B9.toInt()

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

    // ---- 复古文字游戏面（教程弹窗这类「本体风」浮层）----
    // 不另起设计语言：全部取本体 eraTW 自己的颜色，锚点两处。
    // - `emuera.config`：BACKGROUND COLOR 0,0,0 / TEXT COLOR 192,192,192（= 0xC0C0C0，
    //   游戏里的「白字」其实是银灰）/ HIGHLIGHT COLOR 255,255,0；
    // - 语料 `ERB/**/DIM.ERH:571-608` 的 C_* 常量（C_WHITE 即 0xC0C0C0，C_CREAM 0xFFFFCC，
    //   C_GRAY 0x404040，C_L_GRAY 0x777777，C_AQUA 0x66FFFF）。
    // 面板框仿 `ERB/TITLE.ERB` 的 HTML div：`border='2px' bcolor='#a5a5a5'`,
    // 面板底 `color='#363636'`。改色仍只改这一处。

    /** 屏幕底：纯黑（`emuera.config` 的 BACKGROUND COLOR）。 */
    fun consoleBgArgb(): Int = 0xFF000000.toInt()

    /** 浮层面板底：比屏幕亮一档，读得出「这块是盖在画面上的」。 */
    fun consolePanelArgb(): Int = 0xFF0E0E0E.toInt()

    /** 游戏 HTML div 的面板底：`#363636`（TITLE.ERB 的 `color='#363636'`）。 */
    fun consoleBoxArgb(): Int = 0xFF363636.toInt()

    /** 面板外框：游戏 HTML div 的 `border='2px' bcolor='#a5a5a5'`。 */
    fun consoleFrameArgb(): Int = 0xFFA5A5A5.toInt()

    /** 内框与分隔线：`C_GRAY` #404040。 */
    fun consoleRuleArgb(): Int = 0xFF404040.toInt()

    /** 正文：`C_WHITE` #C0C0C0（游戏默认字色）。 */
    fun consoleTextArgb(): Int = 0xFFC0C0C0.toInt()

    /** 次级字（注脚、未选中的章节号）：`C_L_GRAY` #777777。 */
    fun consoleTextDimArgb(): Int = 0xFF777777.toInt()

    /** 高亮与主行动（关键词、当前章节、末页主按钮）：`C_YELLOW` #FFFF00。 */
    fun consoleHighlightArgb(): Int = 0xFFFFFF00.toInt()

    /** 压在黄底上的字（章节页签反白、末页主按钮）：黄底上只能写黑字，写银灰会发糊。 */
    fun consoleOnHighlightArgb(): Int = 0xFF000000.toInt()

    /** 标题与柔和高亮：`C_CREAM` #FFFFCC。 */
    fun consoleCreamArgb(): Int = 0xFFFFFFCC.toInt()

    /** 可照抄的目录与文件名：`C_AQUA` #66FFFF。 */
    fun consoleLinkArgb(): Int = 0xFF66FFFF.toInt()

    /** CRT 扫描线（盖在像素面板最上层的极淡横线，alpha 约 7%）。 */
    fun consoleScanlineArgb(): Int = 0x12FFFFFF

    // ---- 像素启动屏（boot splash 自有一档暖橄榄色板）----
    // 首启/加载屏不是壳层的一页，而是一块「像素游戏开机画面」：暖灰标题、橄榄进度格、
    // 纸色注脚。它允许有自己的色板（表达力：这是品牌第一眼），但**仍只在这一处定义** ——
    // 以前这些值散在 ShellUi 的十个 `Color(0xFF…)` 里，改一次开机画面要翻半个文件。

    /** 开机标题（暖灰纸色）。 */
    fun bootTitleArgb(): Int = 0xFFD8D4BC.toInt()

    /** 开机副标题（暗橄榄）。 */
    fun bootSubtitleArgb(): Int = 0xFF8C8C6E.toInt()

    /** 进度格外框。 */
    fun bootFrameArgb(): Int = 0xFF3A3A2E.toInt()

    /** 进度格已点亮。 */
    fun bootCellOnArgb(): Int = 0xFFA8C88E.toInt()

    /** 进度格未点亮。 */
    fun bootCellOffArgb(): Int = 0xFF1E1E18.toInt()

    /** 百分比读数。 */
    fun bootPercentArgb(): Int = 0xFFB8C8A0.toInt()

    /** 当前阶段名。 */
    fun bootStageArgb(): Int = 0xFF7A7A62.toInt()

    /** 底部注脚（免费声明这类最弱的一行）。 */
    fun bootNoticeArgb(): Int = 0xFF6A6A55.toInt()

    // ---- 现代风启动屏（与像素屏同职责、不同皮肤）----
    // 两套开机画面并存是玩家可选项（`setting.json` 的加载风格）。这一组只描述「深色渐变 +
    // 细进度条」那一套；值只在这里出现一次。

    /** 现代屏底色（近黑偏绿的深色）。 */
    fun modernBootBgArgb(): Int = 0xFF060807.toInt()

    /** 现代屏标题。 */
    fun modernBootTitleArgb(): Int = 0xFFE7E3D6.toInt()

    /** 现代屏副标题。 */
    fun modernBootSubtitleArgb(): Int = 0xFF7C887C.toInt()

    /** 进度槽底。 */
    fun modernBootTrackArgb(): Int = 0xFF1B201C.toInt()

    /** 进度填充的起止色。 */
    fun modernBootFillStartArgb(): Int = 0xFF6F9E70.toInt()

    fun modernBootFillEndArgb(): Int = 0xFFB7D8A9.toInt()

    /** 未知总量时来回扫的光带。 */
    fun modernBootSweepArgb(): Int = 0xFF9CC79C.toInt()

    /** 百分比读数。 */
    fun modernBootPercentArgb(): Int = 0xFF9DB79D.toInt()

    /** 阶段名与注脚（同一档最弱的暗绿灰）。 */
    fun modernBootNoteArgb(): Int = 0xFF6A736A.toInt()

    // ---- 失败屏（终局）----

    /** 整屏遮罩：失败屏把下面的一切压到几乎看不见（比 [scrimArgb] 更实）。 */
    fun scrimOpaqueArgb(): Int = 0xF2000000.toInt()

    /** 「结束应用」的暖暗底：与危险同类，但**不做成警示红** —— 它是离开，不是又一次失败。 */
    fun exitButtonArgb(): Int = 0xFF4A3535.toInt()

    // ---- 压在游戏画面上的半透明条 ----
    // 顶栏与输入条浮在控制台之上，必须透出下面的画面（否则一拉输入栏就把正文盖死），
    // 所以底色带 ~70% alpha。这类「半透明面板 + 比它亮一档的分隔线」以前是散写值。

    /** 压在画面上的半透明面板底（70% 不透明）。 */
    fun panelOverlayArgb(): Int = 0xB31B1B1B.toInt()

    /** 半透明面板上的分隔线（比 [borderArgb] 亮一档，才透得过下面的画面）。 */
    fun overlayRuleArgb(): Int = 0xFF6E6E6E.toInt()

    // ---- 右栏动作按钮 ----
    // 右栏压在游戏画面上，按钮底必须半透明（下面还有立绘/正文）；底色是一档暗橄榄绿，
    // 与主操作绿同族、但压暗到不会跟画面抢眼。

    /** 右栏按钮底（不透明基准值）。 */
    fun railButtonArgb(): Int = 0xFF2A3A2A.toInt()

    /**
     * 右栏按钮底的不透明度（禁用态）。
     *
     * 常态不透明度由 `ShellUi` 的 `RAIL_BASE_ALPHA = 0xE6` 定（那一档是**按钮底压在画面上**的
     * 统一口径，色块按钮与普通按钮共用同一函数 `railContainerArgb`），这里只补禁用档，
     * 免得「禁用 = 直接对半砍」在壳层再长出第二种写法。
     */
    const val RAIL_BUTTON_DISABLED_ALPHA: Float = 0.40f

    /** 中亮绿：右栏里「已选中 / 主行动」的字色，比 [accentArgb] 略深（压在画面上不刺眼）。 */
    fun accentMidArgb(): Int = 0xFF7FAF7F.toInt()

    /** 计数的半透明底（右栏徽章这类「小片底 + 浅字」）。 */
    fun badgeFillArgb(): Int = 0xB3B9D3B9.toInt()

    /** 像素屏底色（比纯黑亮一档，好让像素网格看得出层次）。 */
    fun bootBgArgb(): Int = 0xFF0A0A0A.toInt()



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

    fun iconIdle(): Color = Color(iconIdleArgb())

    fun iconActive(): Color = Color(iconActiveArgb())

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

    fun consoleBg(): Color = Color(consoleBgArgb())

    fun consolePanel(): Color = Color(consolePanelArgb())

    fun consoleBox(): Color = Color(consoleBoxArgb())

    fun consoleFrame(): Color = Color(consoleFrameArgb())

    fun consoleRule(): Color = Color(consoleRuleArgb())

    fun consoleText(): Color = Color(consoleTextArgb())

    fun consoleTextDim(): Color = Color(consoleTextDimArgb())

    fun consoleHighlight(): Color = Color(consoleHighlightArgb())

    fun consoleOnHighlight(): Color = Color(consoleOnHighlightArgb())

    fun consoleCream(): Color = Color(consoleCreamArgb())

    fun consoleLink(): Color = Color(consoleLinkArgb())

    fun consoleScanline(): Color = Color(consoleScanlineArgb())

    fun bootTitle(): Color = Color(bootTitleArgb())

    fun bootSubtitle(): Color = Color(bootSubtitleArgb())

    fun bootFrame(): Color = Color(bootFrameArgb())

    fun bootCellOn(): Color = Color(bootCellOnArgb())

    fun bootCellOff(): Color = Color(bootCellOffArgb())

    fun bootPercent(): Color = Color(bootPercentArgb())

    fun bootStage(): Color = Color(bootStageArgb())

    fun bootNotice(): Color = Color(bootNoticeArgb())

    fun modernBootBg(): Color = Color(modernBootBgArgb())

    fun modernBootTitle(): Color = Color(modernBootTitleArgb())

    fun modernBootSubtitle(): Color = Color(modernBootSubtitleArgb())

    fun modernBootTrack(): Color = Color(modernBootTrackArgb())

    fun modernBootFillStart(): Color = Color(modernBootFillStartArgb())

    fun modernBootFillEnd(): Color = Color(modernBootFillEndArgb())

    fun modernBootSweep(): Color = Color(modernBootSweepArgb())

    fun modernBootPercent(): Color = Color(modernBootPercentArgb())

    fun modernBootNote(): Color = Color(modernBootNoteArgb())

    fun scrimOpaque(): Color = Color(scrimOpaqueArgb())

    fun exitButton(): Color = Color(exitButtonArgb())

    fun panelOverlay(): Color = Color(panelOverlayArgb())

    fun overlayRule(): Color = Color(overlayRuleArgb())

    fun railButton(): Color = Color(railButtonArgb())

    fun accentMid(): Color = Color(accentMidArgb())

    fun badgeFill(): Color = Color(badgeFillArgb())

    fun bootBg(): Color = Color(bootBgArgb())

    fun scrim(): Color = Color(scrimArgb())

    fun scrimLight(): Color = Color(scrimLightArgb())
}
