package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 壳层设计系统的**架构哨兵**（2026-11-26 解耦收口）。
 *
 * 本轮把「卡片骨架 / 折叠件 / 分区头 / 动效节奏 / 字阶与间距」从设置页与 Mod 卡里抽到
 * `ui/design`，SETTINGS 与 SHELL 两侧只剩调用。这类抽取最容易在后续改动里悄悄退化：
 * 有人为了赶一处效果就地抄一段 `AnimatedVisibility + tween(220)`，清单看上去没变，
 * 单一源却已经分裂成两套节奏。
 *
 * 所以这里钉的不是像素，而是**形状**：
 * 1. 折叠 / 行级展开的节奏只许在 `ui/design/ShellMotion.kt` 里定义；
 * 2. 卡片骨架只许有一份实现（`ShellSurfaceCard`），设置页的 `SettingsCard` 必须是转接；
 * 3. 分组头只有一份实现（`ShellGroupHeader`），设置页的 private `GroupHeader` 必须是转接；
 * 4. 上述件确实存在且被真实调用（不是只建了文件没人用）。
 *
 * 源码形状类断言的口径与 `ContentMarginWiringTest` 一致：JVM 单测实例化不了 Activity /
 * Composable，只能读源码；断的是「接线还在」，不是「渲染好看」。
 */
class ShellDesignSystemTest {

    @Test
    fun `按钮样式只有一份`() {
        // 设置页二十来处 Button / OutlinedButton 原来各自写 `ButtonDefaults.buttonColors(…)`、
        // 各自给不给描边、定高在 36 / 40 / 42dp 三种之间摆。这里钉住三件事：配色、描边、最小高度
        // 都只能从 `ShellButton` 与 `ShellSpace` 取，否则换墨色又得改十几处。
        val kit = sourceOf("ui/design/ShellButton.kt")
        assertTrue("按钮配色必须由设计件提供", kit.contains("object ShellButton"))
        assertTrue("实心按钮要有自己的档", kit.contains("fun filled("))
        assertTrue("描边按钮要有自己的档", kit.contains("fun outlined("))
        assertTrue("描边宽度必须取 token", kit.contains("ShellSpace.Hairline"))
        val settings = sourceOf("SettingsActivity.kt")
        assertEquals(
            "设置页不许再自建按钮配色",
            0,
            Regex("ButtonDefaults\\.").findAll(settings).count(),
        )
        assertEquals(
            "设置页不许再自建按钮描边",
            0,
            Regex("BorderStroke\\(").findAll(settings).count(),
        )
        assertEquals(
            "每个 OutlinedButton 都要接 ShellButton.outlined()",
            Regex("OutlinedButton\\(").findAll(settings).count(),
            Regex("ShellButton\\.outlined\\(").findAll(settings).count(),
        )
        assertEquals(
            "每个 OutlinedButton 都要接 ShellButton.border()（警示档传 tone）",
            Regex("OutlinedButton\\(").findAll(settings).count(),
            Regex("ShellButton\\.border\\(").findAll(settings).count(),
        )
        assertEquals(
            "每个实心 Button 都要接 ShellButton.filled()",
            Regex("\\bButton\\(").findAll(settings).count(),
            Regex("ShellButton\\.filled\\(\\)").findAll(settings).count(),
        )
        assertEquals(
            "按钮不许再写死 36 / 40 / 42dp（跌破 44dp 触控下限）",
            0,
            Regex("height\\((36|40|42)\\.dp\\)").findAll(settings).count(),
        )
        // 对话框是两个按钮族的落点（`EratwFilledButton` / `EratwGhostButton` / 共享确认框），
        // 它们同样是壳层按钮，不许再各写一份就地配色与描边。
        for (name in listOf("ui/EratwDialog.kt", "ui/design/ShellAlertDialog.kt")) {
            val src = sourceOf(name)
            assertEquals("$name 不许再自建按钮配色", 0, Regex("ButtonDefaults\\.").findAll(src).count())
            assertEquals("$name 不许再自建按钮描边", 0, Regex("BorderStroke\\(").findAll(src).count())
            assertEquals(
                "$name 的按钮不许再写死 36 / 40 / 42dp",
                0,
                Regex("height\\((36|40|42)\\.dp\\)").findAll(src).count(),
            )
            assertTrue("$name 的按钮必须走 ShellButton", src.contains("ShellButton."))
        }
    }

    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    /** 资源侧同口径（动画、主题样式也在设计系统里，别只盯 Kotlin）。 */
    private fun resourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/res/$relative"),
            File("../app/src/main/res/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 res/$relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `折叠节奏只在 ShellMotion 定义`() {
        val shellFiles = listOf(
            "SettingsActivity.kt",
            "SettingsCollapseKit.kt",
            "SettingsCardKit.kt",
            "KojoManageCard.kt",
            "ShellUi.kt",
        )
        for (name in shellFiles) {
            val text = sourceOf(name)
            assertTrue(
                "$name 不许再定义/引用旧的折叠毫秒常量（节奏归 ShellMotion）",
                !text.contains("COLLAPSE_EXPAND_MILLIS") && !text.contains("ROW_EXPAND_MILLIS"),
            )
        }
        val motion = sourceOf("ui/design/ShellMotion.kt")
        assertTrue("ShellMotion 必须给出揭示进场", motion.contains("fun disclosureEnter()"))
        assertTrue("ShellMotion 必须给出揭示退场", motion.contains("fun disclosureExit()"))
        assertTrue("ShellMotion 必须给出行级进场", motion.contains("fun rowEnter()"))
    }

    @Test
    fun `设置页与 Mod 卡都走统一转场`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页的整卡揭示必须走 ShellMotion.disclosureEnter",
            settings.contains("ShellMotion.disclosureEnter()"),
        )
        assertTrue(
            "设置页的 Mod 行级展开必须走 ShellMotion.rowEnter",
            settings.contains("ShellMotion.rowEnter()"),
        )
        val kojo = sourceOf("KojoManageCard.kt")
        assertTrue(
            "Mod 管理卡的整卡揭示必须用统一分节件（不许再自拼 SettingsCard + 折叠标题）",
            kojo.contains("ShellDisclosureSection("),
        )
        assertTrue(
            "Mod 条目的行级展开必须走 ShellMotion",
            kojo.contains("ShellMotion.rowEnter()"),
        )
        assertTrue(
            "Mod 卡不许再就地拼 expandVertically + tween",
            !kojo.contains("expandVertically(animationSpec = tween("),
        )
        assertTrue(
            "口上包管理行间必须摆共享发丝线（卡内不许再裸行堆叠）",
            kojo.contains("if (index > 0) ShellRowHairline()"),
        )
    }

    @Test
    fun `卡片骨架只有一份实现`() {
        val cardKit = sourceOf("SettingsCardKit.kt")
        assertTrue(
            "设置页的 SettingsCard 必须是 ShellSurfaceCard 的转接",
            cardKit.contains("ShellSurfaceCard("),
        )
        assertTrue(
            "设置页不许再自己造 Card 容器（骨架归 ui/design）",
            !cardKit.contains("CardDefaults.cardColors"),
        )
        val surface = sourceOf("ui/design/ShellSurface.kt")
        assertTrue("圆角半径必须取 token", surface.contains("ShellShape.CardRadius"))
        assertTrue("色带宽度必须取 token", surface.contains("ShellSpace.AccentBar"))
        assertTrue("分节卡必须带分区洗色", surface.contains("ShellTint.wash"))
    }

    @Test
    fun `分组头只有一份实现`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "设置页的 GroupHeader 必须转接 ShellGroupHeader",
            settings.contains(
                "ShellGroupHeader(title = title, subtitle = subtitle, accent = LocalZoneAccent.current)",
            ),
        )
        assertTrue(
            "设置页 private GroupHeader 不许再自己画竖条（实现归 ui/design）",
            !settings.contains("private const val LIST_RULE_ALPHA"),
        )
    }

    @Test
    fun `竖屏功能卡挂实验性小签`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "竖屏字号卡标题旁必须挂「实验性」小签",
            settings.contains("ShellTag(text.experimentalTag)"),
        )
        assertTrue(
            "竖屏模式开关行必须挂「实验性」小签",
            settings.contains("tag = text.experimentalTag,"),
        )
        val strings = sourceOf("SettingsStrings.kt")
        assertTrue("小签文案必须进三语表", strings.contains("val experimentalTag: String = tr("))
        assertTrue("小签三语齐备", strings.contains("zh = \"实验性\"") && strings.contains("en = \"Experimental\""))
    }

    @Test
    fun `市场与导入覆盖层接列表项件`() {
        val listItem = sourceOf("ui/design/ShellListItem.kt")
        assertTrue("列表行必须存在", listItem.contains("fun ShellListRow("))
        assertTrue("列表徽章必须存在", listItem.contains("fun ShellListBadge("))
        assertTrue("覆盖层小节标签必须存在", listItem.contains("fun ShellRuleLabel("))
        assertTrue("筛选片必须存在", listItem.contains("fun ShellFilterChip("))
        assertTrue("列表行内距必须取 token", listItem.contains("ShellSpace.RowPadding"))

        val market = sourceOf("market/MarketUi.kt")
        assertTrue(
            "市场包条目必须走共享列表行（不许再自拼 FloatingCard 容器）",
            market.contains("ShellListRow(onClick = { expanded = !expanded })"),
        )
        assertTrue("市场已装卡必须走共享列表行", market.contains("ShellListRow {"))
        assertTrue("市场分类徽章必须走共享素徽章", market.contains("ShellListBadge("))
        assertTrue("市场筛选片必须走共享件", market.contains("ShellFilterChip("))
        assertTrue("市场分组头必须走共享件", market.contains("ShellGroupHeader("))
        assertTrue(
            "已装区标题必须是共享分组头 + 组级动作槽（不许回到 EratwSubhead + 并排按钮）",
            market.contains("trailing = sweepSlot,"),
        )
        assertTrue("市场覆盖层不许再留 EratwSubhead 语言", !market.contains("EratwSubhead("))

        val modImport = sourceOf("ModImportUi.kt")
        assertTrue("导入块小节标签必须走共享件", modImport.contains("ShellRuleLabel("))
        assertTrue("失败块容器圆角必须取 token", modImport.contains("clip(ShellShape.Inset)"))
    }

    @Test
    fun `View 面弹窗与 Compose 卡片同一套 token`() {
        val dialog = sourceOf("ui/EratwShellDialog.kt")
        assertTrue(
            "View 面字号/内距必须经 ViewShellStyle 从 token 换算",
            dialog.contains("ViewShellStyle.textView("),
        )
        assertTrue(
            "弹窗圆角必须取 SheetRadius token",
            dialog.contains("radius = ShellShape.SheetRadius"),
        )
        assertTrue(
            "弹窗不许再写死 15sp 正文/22dp 内距这类裸值",
            !dialog.contains("15f") && !dialog.contains("22,"),
        )
        val adapter = sourceOf("ui/design/ViewShellStyle.kt")
        assertTrue("View 面适配器必须存在", adapter.contains("object ViewShellStyle"))
        assertTrue("行距换算必须写下口径", adapter.contains("DEFAULT_LINE_HEIGHT_FACTOR"))
        assertTrue("按压反馈必须取系统 selector", adapter.contains("selectableItemBackground"))
        val animIn = resourceOf("anim/shell_dialog_in.xml")
        val animOut = resourceOf("anim/shell_dialog_out.xml")
        assertTrue("进场动画必须存在", animIn.contains("fromXScale=\"0.96\""))
        assertTrue("出场动画必须存在", animOut.contains("toAlpha=\"0\""))
        assertTrue(
            "窗口动画样式必须挂到两段动画",
            resourceOf("values/themes.xml").contains("ShellDialogAnimation"),
        )
    }

    @Test
    fun `右栏宏卡用共享步进器与确认框`() {
        val macro = sourceOf("RailQuickMacroCard.kt")
        assertTrue(
            "宏编辑的步进器必须走共享件（触控热区归它管）",
            macro.contains("ShellStepper("),
        )
        assertTrue(
            "宏卡的两个确认框必须走共享确认框件",
            macro.contains("ShellAlertDialog("),
        )
        assertTrue(
            "宏卡不许再自拼 8dp 浮底行（卡内套卡）",
            !macro.contains("RoundedCornerShape(8.dp)"),
        )
        assertTrue(
            "宏卡字号必须取 token，不许再写 fontSize = <裸数值>",
            !macro.contains("fontSize = "),
        )
        val stepper = sourceOf("ui/design/ShellStepper.kt")
        assertTrue("步进器必须存在", stepper.contains("fun ShellStepper("))
        assertTrue(
            "步进键热区必须撑到平台触控下限（视觉小、热区不许跟着缩）",
            stepper.contains("minimumInteractiveComponentSize()"),
        )
        val dialog = sourceOf("ui/design/ShellAlertDialog.kt")
        assertTrue("确认框件必须存在", dialog.contains("fun ShellAlertDialog("))
        assertTrue("确认框圆角必须取 Sheet token", dialog.contains("shape = ShellShape.Sheet"))
    }

    @Test
    fun `mod 浮层用壳层墨色而不是系统主题`() {
        val surface = sourceOf("ModUiSurface.kt")
        assertTrue(
            "mod 浮层不许再用 MaterialTheme 的系统色/字阶（壳层没有装配 M3 主题）",
            !surface.contains("MaterialTheme."),
        )
        assertTrue("浮层面板必须取壳层面板色", surface.contains("EratwInk.panelAlt()"))
        assertTrue("浮层动作行必须走行内可点件", surface.contains("ModUiActionRow("))
        val image = sourceOf("ModUiImage.kt")
        assertTrue(
            "图片占位框不许再用 MaterialTheme 描边",
            !image.contains("MaterialTheme."),
        )
    }

    @Test
    fun `素材卡按钮走共享按钮件`() {
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue(
            "危险清理按钮必须走次按钮件并同时点色字与描边",
            settings.contains("content = UiColors.Warn,") && settings.contains("border = UiColors.Warn.copy("),
        )
        assertTrue(
            "素材卡的导入/重启按钮必须走主按钮件（不许再各自写 40dp + 8dp 圆角）",
            settings.contains("EratwFilledButton(\n            text = copy.restartButton,"),
        )
    }

    @Test
    fun `壳层主界面不再直写颜色与字号`() {
        val shell = sourceOf("ShellUi.kt")
        assertEquals(
            "ShellUi 不许再出现 Color(0x…) 字面量（墨色只能从 EratwInk 取）",
            0,
            Regex("Color\\(0x").findAll(shell).count(),
        )
        // 字号只允许出现在「从 token 派生」的两处：启动屏品牌字、失败屏标题。
        val rawSizes = shell.lines().filter { line ->
            Regex("fontSize = \\d").containsMatchIn(line) && !line.contains(".copy(fontSize")
        }
        assertEquals("ShellUi 不许再有裸字号（度量档 metrics 除外，它不是裸值）", 0, rawSizes.count {
            !it.contains("metrics")
        })
        assertTrue("输入框字样不许混用系统主题字阶", !shell.contains("MaterialTheme.typography"))
        val ink = sourceOf("ui/EratwInk.kt")
        val newTokens = listOf(
            "bootTitleArgb",
            "modernBootBgArgb",
            "panelOverlayArgb",
            "railButtonArgb",
            "scrimOpaqueArgb",
        )
        for (token in newTokens) {
            assertTrue("EratwInk 必须收敛 $token", ink.contains("fun $token("))
        }
    }

    @Test
    fun `两套启动屏与失败屏共用一份尺码`() {
        val shell = sourceOf("ShellUi.kt")
        assertTrue("启动屏品牌字必须只有一个尺码", shell.contains("private val BOOT_TITLE_TYPE"))
        assertTrue("失败屏标题必须只有一个尺码", shell.contains("private val FAILURE_TITLE_TYPE"))
        assertEquals(
            "不许再有第二个 24/25sp 的品牌字（两套皮肤共用一份）",
            0,
            Regex("text = \"eraThe World\",[\\s\\S]{0,80}?fontSize = 2[45]\\.sp").findAll(shell).count(),
        )
    }

    @Test
    fun `弹窗语气四档全部由字阶 token 派生`() {
        val dialog = sourceOf("ui/EratwDialog.kt")
        assertEquals(
            "EratwDialog 的语气档不许再自带裸字号（尺码只能来自 ShellType）",
            0,
            Regex("\\.sp\\b").findAll(dialog).count(),
        )
        for (tier in listOf("ShellType.Title", "ShellType.Body", "ShellType.Label")) {
            assertTrue("语气档必须至少有一档落在 $tier", dialog.contains(tier))
        }
        assertTrue("小节名只许抬字重，不许另起字号", dialog.contains("ShellType.Caption.copy"))
    }

    @Test
    fun `图标与两份崩溃屏不再散写墨色`() {
        // 图标三枚同源（键盘/螺旋/方向锁），静息与激活只许有两种颜色。
        val icon = sourceOf("ShellIconButton.kt")
        assertEquals("图标配色不许再写 Color(0x…)（已收进 EratwInk 的图标档）", 0, Regex("Color\\(0x").findAll(icon).count())
        assertTrue("图标静息取 EratwInk.iconIdle", icon.contains("EratwInk.iconIdle()"))
        assertTrue("图标激活取 EratwInk.iconActive", icon.contains("EratwInk.iconActive()"))
        // 崩溃屏与补丁回执屏是 View 面，颜色同样只能来自墨色表。
        for (file in listOf("CrashScreenActivity.kt", "CrashForensics.kt", "MainActivity.kt")) {
            val src = sourceOf(file)
            assertEquals(
                "$file 不许再写 Color.WHITE / Color.BLACK 一类平台常量色",
                0,
                Regex("Color\\.(WHITE|BLACK|GRAY|LTGRAY|DKGRAY)").findAll(src).count(),
            )
            assertTrue("$file 必须从 EratwInk 取墨色", src.contains("EratwInk."))
        }
    }

    @Test
    fun `壳层动效时长只有一份`() {
        // 四个调用面都不许再自己挑毫秒数：只允许 `tween(常量名)` 这种形式。
        for (file in listOf("ShellUi.kt", "SettingsActivity.kt", "TutorialBook.kt", "ShellIconButton.kt")) {
            val src = sourceOf(file)
            assertEquals(
                "$file 不许再写 tween(数字)（时长只能从 ShellMotion 取）",
                0,
                Regex("tween\\(\\s*\\d").findAll(src).count(),
            )
            assertTrue("$file 必须真的从 ShellMotion 取动效", src.contains("ShellMotion."))
        }
        val motion = sourceOf("ui/design/ShellMotion.kt")
        for (slot in listOf(
            "TURN_MILLIS",
            "SWEEP_MILLIS",
            "PULSE_MILLIS",
            "PANEL_IN_MILLIS",
            "PAGE_IN_MILLIS",
            "TAP_SHAKE_MILLIS",
        )) {
            assertTrue("ShellMotion 必须定义 $slot", motion.contains(slot))
        }
    }

    @Test
    fun `教程的小控件热区都不小于 44dp`() {
        val book = sourceOf("TutorialBook.kt")
        assertTrue("章节页签与导轨行必须撑到 44dp 热区", Regex("ShellSpace\\.RowMinHeight").findAll(book).count() >= 3)
        assertTrue("关闭钮必须用 48dp 触控下限", book.contains("ShellSpace.TouchMin"))
        assertEquals("关闭钮不许再写 40dp 热区", 0, Regex("size\\(40\\.dp\\)").findAll(book).count())
        val tokens = sourceOf("ui/design/ShellTokens.kt")
        assertTrue("触控下限必须定义在间距 token 里", tokens.contains("val TouchMin = 48.dp"))
    }

    @Test
    fun `阅读面字阶只有一份且黄底黑字来自墨色表`() {
        val book = sourceOf("TutorialBook.kt")
        assertEquals("教程正文不许再写裸字号（尺码只能来自 ReaderType）", 0, Regex("fontSize = ").findAll(book).count())
        assertEquals("教程里的「黄底黑字」不许写 Color.Black", 0, Regex("Color\\.Black").findAll(book).count())
        assertTrue("教程必须真的在吃 ReaderType", Regex("ReaderType\\.").findAll(book).count() >= 8)
        assertTrue("黄底上的字必须只有一个来源", book.contains("EratwInk.consoleOnHighlight()"))
        val reader = sourceOf("ui/design/ReaderType.kt")
        for (role in listOf("val Reading", "val ReadingTight", "val Chapter", "val Meta", "val Overline")) {
            assertTrue("ReaderType 必须定义 $role", reader.contains(role))
        }
    }

    @Test
    fun `设置页字阶与圆角全部取 token`() {
        val settings = sourceOf("SettingsActivity.kt").lines()
        // 裸字号：只许出现在 `ShellType.X.copy(fontSize = …)` 这类派生式里，且行内不得再有 Text(...) 直接吃裸值。
        val rawTextSizes = settings.count { line ->
            line.contains("Text(") && Regex("fontSize = \\d+(\\.\\d+)?\\.sp").containsMatchIn(line)
        }
        assertEquals("设置页不许再有 Text(...) 直吃裸字号", 0, rawTextSizes)
        // 卡内圆角：8/10/9/6dp 一律上 token（发丝线与胶囊指示器不在此列）。
        for (legacy in listOf("8.dp", "10.dp", "9.dp", "6.dp")) {
            assertFalse(
                "设置页不许再写 RoundedCornerShape($legacy)",
                settings.any { it.contains("RoundedCornerShape($legacy)") },
            )
        }
        val tokenUses = settings.count { it.contains("ShellType.") }
        assertTrue("设置页字阶必须大面积取 token（实测 $tokenUses 处）", tokenUses >= 80)
    }

    @Test
    fun `设置页确认框全走共享确认框件`() {
        val lines = sourceOf("SettingsActivity.kt").lines()
        val bare = lines.count { it.trim() == "AlertDialog(" }
        assertEquals("不许再有裸 AlertDialog( 调用（一律走 ShellAlertDialog）", 0, bare)
        assertTrue("共享确认框必须被真实调用", lines.any { it.contains("ShellAlertDialog(") })
        assertTrue("确认框动作必须走 ShellDialogActions", lines.any { it.contains("ShellDialogActions(") })
    }

    @Test
    fun `读数数字有自己的语义档`() {
        val tokens = sourceOf("ui/design/ShellTokens.kt")
        assertTrue("读数数字语义档必须存在（别拿标题档冒充数值）", tokens.contains("val Figure = TextStyle("))
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue("设置页统计格必须用读数档", settings.contains("ShellType.Figure"))
    }

    @Test
    fun `右栏两张卡与运行时概览接同一副行骨架`() {
        for (name in listOf("RailFilterCard.kt", "RailPinnedCard.kt")) {
            val card = sourceOf(name)
            assertTrue("$name 的分隔线必须走共享发丝线", card.contains("EratwHairline("))
            assertTrue(
                "$name 不许再自拼 HorizontalDivider(color = …)",
                !card.contains("HorizontalDivider(color ="),
            )
        }
        val overview = sourceOf("ModRuntimeOverviewCard.kt")
        assertTrue("每个 Mod 的标题行必须带静态徽章（谁、什么状态一眼分清）", overview.contains("ShellListBadge("))
        assertTrue("Mod 之间必须用发丝线分隔", overview.contains("ShellRowHairline("))
        assertTrue(
            "运行时概览不许再写裸字号",
            !overview.contains("fontSize = "),
        )
    }

    @Test
    fun `设计系统件齐备且被调用`() {
        val files = listOf(
            "ui/design/ShellMotion.kt",
            "ui/design/ShellTokens.kt",
            "ui/design/ShellSurface.kt",
            "ui/design/ShellDisclosure.kt",
            "ui/design/ShellGroupHeader.kt",
        )
        for (name in files) {
            assertTrue("$name 必须存在", sourceOf(name).isNotEmpty())
        }
        val tokens = sourceOf("ui/design/ShellTokens.kt")
        assertTrue("形状 token 必须齐备", tokens.contains("object ShellShape"))
        assertTrue("间距 token 必须齐备", tokens.contains("object ShellSpace"))
        assertTrue("字阶 token 必须齐备", tokens.contains("object ShellType"))
        assertTrue("着色 token 必须齐备", tokens.contains("object ShellTint"))
        val disclosure = sourceOf("ui/design/ShellDisclosure.kt")
        assertTrue("揭示式分节必须存在", disclosure.contains("fun ShellDisclosureSection("))
        assertTrue("揭示标题行必须存在", disclosure.contains("fun ShellDisclosureHeader("))
    }

    @Test
    fun `像素面点下去必须有回音`() {
        val book = sourceOf("TutorialBook.kt")
        val press = sourceOf("ui/design/ShellPress.kt")
        assertTrue("按压叠色必须由设计件提供", press.contains("object ShellPress"))
        assertTrue("叠色必须取自 ShellTint", press.contains("ShellTint.press("))
        // 七处关掉涟漪：五处是控件（导轨行/关闭钮/章节页签/次行动/主行动），
        // 一处是面板吞点击（空回调），一处是整屏遮罩「点外面关掉」（本就不该有反馈）。
        assertEquals(
            "关涟漪的位置变了就得同步复核有没有补按压反馈",
            7,
            Regex("indication = null").findAll(book).count(),
        )
        assertEquals(
            "五个控件都必须接 ShellPress.tint(pressSource, …)",
            5,
            Regex("ShellPress\\.tint\\(pressSource").findAll(book).count(),
        )
    }

    @Test
    fun `输入框配色只有一份`() {
        val field = sourceOf("ui/design/ShellField.kt")
        assertTrue("输入框配色必须由设计件提供", field.contains("object ShellField"))
        assertTrue("四挡（聚焦/静息/禁用/错误）必须给全", field.contains("errorBorderColor"))
        // 全仓只剩这四处有输入框；每一处都必须显式接 ShellField，别靠缺省那套系统主题色。
        var fields = 0
        var wired = 0
        for (name in listOf("ShellUi.kt", "RailQuickMacroCard.kt", "SettingsActivity.kt", "market/MarketUi.kt")) {
            val source = sourceOf(name)
            fields += Regex("OutlinedTextField\\(").findAll(source).count()
            wired += Regex("ShellField\\.outlined\\(\\)").findAll(source).count()
        }
        assertEquals("每个输入框调用点都得带 ShellField.outlined()", fields, wired)
        assertTrue(
            "输入框配色不许再有一份就地实现",
            !sourceOf("ShellUi.kt").contains("OutlinedTextFieldDefaults"),
        )
        assertTrue(
            "快捷宏卡不许再吃系统主题（默认紫）",
            !sourceOf("RailQuickMacroCard.kt").contains("EratwFieldColors"),
        )
    }

    @Test
    fun `壳层可点区块不再起涟漪`() {
        // 壳层自己的可点面（行、chip、步进键、折叠头、图标钮、浮层项、候选行）统一走 ShellPress 叠色：
        // 一行式 `clickable(onClick = …)` / `clickable(enabled = …)` 是「还在用系统涟漪」的标志，清零。
        // 例外只有两处：竖屏/预览共用件 `QuickMacroUi.kt`（封存不动）与整屏遮罩（本就不该有反馈）。
        val files = listOf(
            "SettingsCardKit.kt",
            "KojoManageCard.kt",
            "SettingsActivity.kt",
            "ModUiSurface.kt",
            "ShellIconButton.kt",
            "TutorialBook.kt",
            "ui/design/ShellListItem.kt",
            "ui/design/ShellDisclosure.kt",
            "ui/design/ShellStepper.kt",
        )
        for (name in files) {
            val src = sourceOf(name)
            assertEquals(
                "$name 不许再留一行式 clickable(onClick/enabled)（那是系统涟漪）",
                0,
                Regex("clickable\\((onClick|enabled)").findAll(src).count(),
            )
            assertTrue("$name 的可点面必须接 ShellPress.tint", src.contains("ShellPress.tint"))
        }
    }

    @Test
    fun `Material 主题只有一份`() {
        // 壳层任何一个入口自建 colorScheme 都会让「没写样式的 Material 控件」分叉成两套深浅：
        // 主题只许在 ShellUi.kt 的 EratwTheme 里定义，设置页那份必须是转接。
        assertEquals(
            "不许再出现第二份 colorScheme 定义",
            1,
            Regex("darkColorScheme\\(").findAll(sourceOf("ShellUi.kt")).count(),
        )
        for (name in listOf("SettingsActivity.kt", "ModUiSurface.kt", "market/MarketUi.kt", "TutorialBook.kt")) {
            assertEquals("$name 不许自建 Material 主题", 0, Regex("darkColorScheme\\(").findAll(sourceOf(name)).count())
        }
        assertTrue(
            "设置页主题必须是 EratwTheme 的转接",
            sourceOf("SettingsActivity.kt").contains("EratwTheme(content = content)"),
        )
    }

    @Test
    fun `折叠正文必须自带容器`() {
        // `AnimatedVisibility`（foundation 那版可见性容器）的 content 是**叠放**布局：多个子项全部
        // 落脚在同一原点。正文只要不止一个子项，就必须自己套 Column/Row，否则说明句、每一行
        // Mod、尾部提示会互相压住 —— 2026-11-26 设置页 Mod 管理卡就这样整段叠成一行。
        // 单子项的调用点（LazyRow / Column / 单个 block）照旧放行。
        val files = listOf(
            "SettingsActivity.kt",
            "KojoManageCard.kt",
            "ShellUi.kt",
            "ui/design/ShellDisclosure.kt",
            "ui/design/ShellVisibility.kt",
        )
        var sites = 0
        for (name in files) {
            for (children in animatedTopLevelChildren(sourceOf(name))) {
                sites++
                assertTrue(
                    "$name 有一处 AnimatedVisibility 并列了 $children 个子项（会叠放成一坨），请套一层 Column/Row",
                    children <= 1,
                )
            }
        }
        assertTrue("折叠正文至少还剩一处（别让哨兵空转）", sites >= 1)
    }

    /** 逐个 `AnimatedVisibility(...) { … }` 的正文里**顶层**子项个数（注释行与参数续行都不算）。 */
    private fun animatedTopLevelChildren(source: String): List<Int> {
        val lines = source.lines()
        // 每个调用点的行号：按「该匹配之前有多少个换行」数，不需要再做字符级括号扫描。
        return Regex("AnimatedVisibility\\(").findAll(source)
            .map { match -> source.substring(0, match.range.first).count { it == '\n' } }
            .map { header -> topLevelChildCount(lines, header) }
            .toList()
    }

    /**
     * 一个调用点的正文里顶层子项个数。
     *
     * 正文起点 = 参数表收口的那一行（以 `) {` 结尾）；正文终点 = 从正文开头累计花括号、
     * 深度重新归零的那一行 —— **不能**取「第一行独占 `}` 的行」，那是 `for` / `if` 的闭括号
     * （正文里的第一个块往往就是它），会让统计只剩块前面的那一两个子项。
     */
    private fun topLevelChildCount(lines: List<String>, header: Int): Int {
        val bodyStart = (header + 1 until lines.size)
            .firstOrNull { lines[it].trimEnd().endsWith(") {") } ?: return 0
        val tail = lines.drop(bodyStart + 1)
        val depthBefore = listOf(0) + tail.map { braceDelta(it) }.runningFold(0, Int::plus).drop(1).dropLast(1)
        // 正文终点：唯一一条「进来时花括号深度还是 0」的独占 `}` —— 就是 lambda 自己那行。
        val end = tail.indices.firstOrNull { depthBefore[it] <= 0 && tail[it].trim() == "}" }
        val body = if (end == null) tail else tail.take(end)
        return body.fold(0 to 0) { acc, raw -> childStep(acc.first, acc.second, raw) }.second
    }

    /** 正文的一行：顶层出现「大写开头的调用」记为一个子项，随后推进花括号深度。 */
    private fun childStep(depth: Int, children: Int, raw: String): Pair<Int, Int> {
        val line = raw.trim().let(::uncommented)
        val counted = if (depth == 0 && CALL_HEAD.containsMatchIn(line)) 1 else 0
        return (depth + braceDelta(raw)).coerceAtLeast(0) to (children + counted)
    }

    /** 该行的花括号净增量；注释行与字面量里的花括号不算。 */
    private fun braceDelta(raw: String): Int {
        val code = STRING_LITERAL.replace(uncommented(raw.trim()), "\"\"")
        return code.count { it == '{' } - code.count { it == '}' }
    }

    /** 去掉注释行（行首是双斜杠、块注释开头或 KDoc 星号的行）：返回空串表示这行没有可断言的代码。 */
    private fun uncommented(line: String): String =
        if (line.startsWith("/") || line.startsWith("*")) "" else line

    private companion object {
        /** 顶层子项的形态：一行以大写开头的调用（`Column(` / `LazyRow(` / `Text(` …）。 */
        val CALL_HEAD = Regex("^[A-Z][A-Za-z0-9_.]*\\s*\\(")

        /** 字面量里的花括号不该参与深度计算。 */
        val STRING_LITERAL = Regex("\"[^\"]*\"")
    }
}
