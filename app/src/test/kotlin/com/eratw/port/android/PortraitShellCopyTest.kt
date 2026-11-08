package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 竖屏壳内联文案收口（A-8 / T-2b）的三语形状闸，模式照 [UpdateNotifyCopyTest] 的第 1 组
 * 「三语表形状」收窄而来：那边的源码面防扩散、接线纪律两组合由更新车道的用例管，本表只钉：
 *
 * 1. **三档形状**：56 条三档各自非空、两两不同、[PortraitShellCopy.EN] 零汉字（日文档的
 *    汉字正常）、换行数与槽位数一致——谁往某一档手工多塞/漏改一句，这里红；
 * 2. **中文零漂移对拍右轴**：[PortraitShellCopy.ZH] 的每一条都逐字等于收口前的内联字面量
 *    （全角括号、全角空格、省略号字符、`▾`/`▸`/`▲`/`▼`、「执行中:」的半角冒号都在钉面上），
 *    运行时行为零变化的保证就在这一条；
 * 3. **占位符残留**：三档都不许出现 TODO/FIXME/TBD/placeholder 一类未完工标记；
 * 4. **两个非 Composable 兼容口**（照 UpdateNotifyCopyTest「兼容口走的必须是中文那一档」的
 *    口径）：[REPEATED_FAILURE_NOTE]（ShellUiFailureActionsTest 按名引用）与 [ShellState.hint]
 *    的初始值都代 [PortraitShellCopy.ZH] 档，词源在表里、不许另写一句。
 */
class PortraitShellCopyTest {

    private val copies = listOf(PortraitShellCopy.ZH, PortraitShellCopy.EN, PortraitShellCopy.JA)
    private val han = Regex("[\\u4e00-\\u9fff]")
    private val slot = Regex("[{](\\d+)[}]")
    private val placeholder = Regex("(?i)TODO|FIXME|TBD|placeholder|待补|占位")

    /**
     * 本表全部 56 条：键名、属性引用、以及**收口前内联字面量的中文原话**（对拍右轴）。
     * 新增键必须三档一起加并在这份清单登记——漏登记的键不进形状闸也不进中文钉扎。
     */
    private val table: List<Triple<String, kotlin.reflect.KProperty1<PortraitShellCopy, String>, String>> = listOf(
        // 指令盘（CommandTray）
        Triple("trayEmptyLine", PortraitShellCopy::trayEmptyLine, "（本屏没有可点选项）"),
        Triple("trayExecutingFormat", PortraitShellCopy::trayExecutingFormat, "执行中: {0}"),
        Triple("trayLastCommandFormat", PortraitShellCopy::trayLastCommandFormat, "<上回指令:{0}>"),
        Triple("trayExpandCommand", PortraitShellCopy::trayExpandCommand, "更多指令 ▾"),
        Triple("trayCollapseCommand", PortraitShellCopy::trayCollapseCommand, "收起到常用指令 ▴"),
        // HUD（PortraitHud）
        Triple("hudKeyButton", PortraitShellCopy::hudKeyButton, "键"),
        Triple("hudTrayButton", PortraitShellCopy::hudTrayButton, "盘"),
        Triple("hudSettingsButton", PortraitShellCopy::hudSettingsButton, "设"),
        Triple("hudMoneyFormat", PortraitShellCopy::hudMoneyFormat, "所持金 {0}"),
        Triple("hudDetailOpen", PortraitShellCopy::hudDetailOpen, "▲ 收起状态详情"),
        Triple("hudDetailClosed", PortraitShellCopy::hudDetailClosed, "▼ 状态详情"),
        Triple("drawerProtagonistTitleFormat", PortraitShellCopy::drawerProtagonistTitleFormat, "▼ {0}　主角状态"),
        Triple("drawerCharacterTitleFormat", PortraitShellCopy::drawerCharacterTitleFormat, "▼ {0}　好感度 / Palam"),
        Triple("drawerM2Note", PortraitShellCopy::drawerM2Note, "主角专属详情（素质・经验・装备）属 M2"),
        Triple("drawerScrollToSections", PortraitShellCopy::drawerScrollToSections, "▲ 翻到 [Status]/[Palam] 区"),
        Triple("drawerScrollToLatest", PortraitShellCopy::drawerScrollToLatest, "▼ 回到最新"),
        // 立绘舞台（SpriteStage）
        Triple("stageTitleFormat", PortraitShellCopy::stageTitleFormat, "立绘 {0}/10"),
        Triple("stageTitleNoCount", PortraitShellCopy::stageTitleNoCount, "立绘"),
        Triple("stageExpand", PortraitShellCopy::stageExpand, "展开 ▸"),
        Triple("stageCollapse", PortraitShellCopy::stageCollapse, "收起 ▾"),
        Triple("stageHint", PortraitShellCopy::stageHint, "立绘在正文流内，可在画面里滚动查看"),
        // 输入与右栏（ShellUi 横屏壳）
        Triple("inputHintDefault", PortraitShellCopy::inputHintDefault, "输入后回车提交"),
        Triple("repeatedFailureNote", PortraitShellCopy::repeatedFailureNote, "重试已经试过一回，还是失败：再点一次走的还是同一条路。"),
        Triple("continueButton", PortraitShellCopy::continueButton, "继续 ▶"),
        Triple("continueButtonA11y", PortraitShellCopy::continueButtonA11y, "继续，翻到下一屏"),
        Triple("settingsButton", PortraitShellCopy::settingsButton, "设置"),
        Triple("railCountFormat", PortraitShellCopy::railCountFormat, "{0} 项可点"),
        Triple("macroGroupFallback", PortraitShellCopy::macroGroupFallback, "宏"),
        Triple("tooltipDismissHint", PortraitShellCopy::tooltipDismissHint, "点任意处关闭"),
        Triple("inputToggleExpanded", PortraitShellCopy::inputToggleExpanded, "键盘(已展开)"),
        Triple("inputToggleCollapsed", PortraitShellCopy::inputToggleCollapsed, "键盘(已收起)"),
        Triple("inputKnobA11y", PortraitShellCopy::inputKnobA11y, "底边抓手"),
        Triple("submitButton", PortraitShellCopy::submitButton, "提交"),
        Triple("railToggleExpanded", PortraitShellCopy::railToggleExpanded, "选项栏(已展开)"),
        Triple("railToggleCollapsed", PortraitShellCopy::railToggleCollapsed, "选项栏(已收起)"),
        Triple("orientationLocked", PortraitShellCopy::orientationLocked, "屏幕方向(已锁定)"),
        Triple("orientationUnlocked", PortraitShellCopy::orientationUnlocked, "屏幕方向(未锁定)"),
        // 报告卡（脏退出 + 失败屏共用词）
        Triple("dirtyExitTitle", PortraitShellCopy::dirtyExitTitle, "上次异常退出"),
        Triple("dirtyExitBody1", PortraitShellCopy::dirtyExitBody1, "上一次游戏在运行中异常结束（闪退或被系统强制结束）。"),
        Triple("dirtyExitBody2", PortraitShellCopy::dirtyExitBody2, "已生成一份错误报告存在手机里。自动上报没能送出时，用下面的按钮把它发给开发者。"),
        // 通用对话框按钮（死循环告警）
        Triple("dialogYes", PortraitShellCopy::dialogYes, "是"),
        Triple("dialogNo", PortraitShellCopy::dialogNo, "否"),
        Triple("shareReport", PortraitShellCopy::shareReport, "分享报告"),
        Triple("copyReport", PortraitShellCopy::copyReport, "复制报告"),
        Triple("ackButton", PortraitShellCopy::ackButton, "知道了"),
        // 加载页（两版共用）
        Triple(
            "freeNotice",
            PortraitShellCopy::freeNotice,
            "本游戏完全免费，仅供学习使用；任何收费售卖均与本项目无关，如遇收费请勿付款，保留凭证并向发布渠道举报。",
        ),
        Triple("loadingPixelHeadline", PortraitShellCopy::loadingPixelHeadline, "― 正 在 进 入 这 个 世 界 ―"),
        Triple("loadingModernHeadline", PortraitShellCopy::loadingModernHeadline, "正在进入这个世界"),
        Triple("loadingPreparingData", PortraitShellCopy::loadingPreparingData, "正在准备游戏数据…"),
        // 失败屏（FailureOverlay）
        Triple(
            "retryCompatNote",
            PortraitShellCopy::retryCompatNote,
            "「少占内存再试」装得更慢、占用更少（本次运行会一直沿用），但也可能还是装不下。",
        ),
        Triple(
            "exitIsRestartNote",
            PortraitShellCopy::exitIsRestartNote,
            "「重启应用」会结束本进程再由系统立刻拉回来：上一局占着的内存只有换一个干净进程才还得回去，" +
                "本进程里重试多少次都是同一张屏。存档在盘上，不会丢。",
        ),
        Triple("backToTitle", PortraitShellCopy::backToTitle, "回到标题"),
        Triple("retryCompatButton", PortraitShellCopy::retryCompatButton, "少占内存再试"),
        Triple("retryButton", PortraitShellCopy::retryButton, "重试"),
        Triple("exitAppRestart", PortraitShellCopy::exitAppRestart, "重启应用"),
        Triple("exitAppQuit", PortraitShellCopy::exitAppQuit, "结束应用"),
    )

    // ------------------------------------------------------------------
    // 1. 三语表形状
    // ------------------------------------------------------------------

    @Test
    fun `三档条目数相等（56 条加语言档自身）`() {
        // data class 的 toString 是「key=value」按声明序：等号数 = 54 条文案 + lang 一档。
        // 少补一档词在编译期就过不去，这条钉的是有人往某一档手工多塞一句。
        // 对账走共享形状闸（T-3 抽取）；本表的逐键点名清单（table）由下面几条共用。
        assertEquals("本表的键数变了（现有 56 条，加键要三档一起加并登记进本清单）", 56, table.size)
        CopyShapeGate.assertKeyCount("PortraitShellCopy", copies, table.associate { it.first to it.second }, expectedPerCopy = 57)
    }

    @Test
    fun `英文那一组一个汉字都不许混进来（日文的汉字不算违规）`() {
        val en = PortraitShellCopy.EN.toString()
        assertFalse("英文档混进了汉字：${han.findAll(en).map { it.value }.toList()}", han.containsMatchIn(en))
        // 反空转自证：日文档确实有汉字，说明上一条不是「正则没匹配到任何东西」的空转。
        assertTrue("日文档没抓到任何汉字，说明那条闸在空转", han.containsMatchIn(PortraitShellCopy.JA.toString()))
    }

    @Test
    fun `56 条在三语各自非空并且两两不同`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, table.associate { it.first to it.second })
    }

    @Test
    fun `同一句式的换行数与槽位数在三语一致（不许英文少一行少一格读数）`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, table.associate { it.first to it.second })
    }

    @Test
    fun `三档都不许留占位符残留`() {
        // 两个互补口径：toString 上不许出现占坑词（TODO/待补 这类没补的假文案），
        // 灌满读数后不许残留槽位（fill 漏灌）—— 后者是共享形状闸的判据。
        copies.forEach { copy ->
            val text = copy.toString()
            val hits = placeholder.findAll(text).map { it.value }.toList()
            assertFalse("${copy.lang} 档有占位符残留：$hits", hits.isNotEmpty())
        }
        CopyShapeGate.assertNoSlotResidue(copies, table.associate { it.first to it.second }) { copy, template, n ->
            copy.fill(template, *Array(n) { "S$it" })
        }
    }

    // ------------------------------------------------------------------
    // 2. 中文零漂移对拍右轴
    // ------------------------------------------------------------------

    @Test
    fun `中文档逐字等于收口前的内联字面量（运行时行为零变化的右轴）`() {
        table.forEach { (name, property, inlineLiteral) ->
            assertEquals("中键 $name 与收口前的内联字面量不一致（ZH 档不许动）", inlineLiteral, property(PortraitShellCopy.ZH))
        }
    }

    @Test
    fun `槽位渲染走 fill 一次替换（参数里的花括号不二次展开）`() {
        val copy = PortraitShellCopy.ZH
        assertEquals("执行中: 收起", copy.fill(copy.trayExecutingFormat, "收起"))
        // 参数内容里带花括号：单趟 replace 不会再进模板一轮，原样落屏。
        assertEquals("执行中: {1}号", copy.fill(copy.trayExecutingFormat, "{1}号"))
        // 越界槽位保留原样（与 UpdateCopy.fill 同口径），不许抛也不许吃掉。
        assertEquals("A x B {2}", copy.fill("A {0} B {2}", "x"))
    }

    // ------------------------------------------------------------------
    // 3. 非 Composable 兼容口
    // ------------------------------------------------------------------

    @Test
    fun `失败屏重试说明的兼容口代的就是中文那一档`() {
        // failureActions 是无 Context 的纯函数，ShellUiFailureActionsTest 按名引用：
        // 名字与值都不许漂，词源必须在本表（换一档语言它不变 = 词源在表里）。
        assertEquals(PortraitShellCopy.ZH.repeatedFailureNote, REPEATED_FAILURE_NOTE)
    }

    @Test
    fun `输入框初始提示代的就是中文那一档（运行时随即被 InputHint 覆盖）`() {
        assertEquals(PortraitShellCopy.ZH.inputHintDefault, ShellState().hint)
    }
}
