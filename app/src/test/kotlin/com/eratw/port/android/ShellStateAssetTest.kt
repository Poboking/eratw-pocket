package com.eratw.port.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第十六批（2026-11-26）**动效与等待态资产**的架构哨兵。
 *
 * 与 [ShellDesignSystemTest] 同一口径（JVM 单测实例化不了 Composable，只能读源码形状），
 * 但那支已经长到 `LargeClass` 阈值边缘，新哨兵另起一支、各自带一份最小 helper，
 * 免得每加一条断言就得先给老文件瘦身。
 *
 * 钉两件事：
 * 1. 「怎么出现」只有 [com.eratw.port.android.ui.design.ShellVisibility] 一个入口 ——
 *    第十三批那次文字互压就是漏了它内部那一层列；
 * 2. 「在等」只有一套（细进度带 + 呼吸占位），不再散落 Material 的默认进度条。
 */
class ShellStateAssetTest {

    @Test
    fun `可见性容器只有一个入口`() {
        val kit = sourceOf("ui/design/ShellVisibility.kt")
        assertTrue("可见性容器必须存在", kit.contains("fun ShellVisibility("))
        assertTrue(
            "容器内部必须固定一列（这是把叠放根因做成结构的地方）",
            kit.contains("Column(modifier = Modifier.fillMaxWidth(), content = content)"),
        )
        for (name in listOf("SettingsActivity.kt", "KojoManageCard.kt")) {
            val src = sourceOf(name)
            assertEquals(
                "$name 的展开正文必须走 ShellVisibility，不许再手写可见性容器",
                0,
                Regex("AnimatedVisibility\\(").findAll(src).count(),
            )
            assertTrue("$name 至少要有一处 ShellVisibility", src.contains("ShellVisibility("))
        }
        // 换档也是动效：设置页四档换内容要补整块淡入，时长与规格同样归 ShellMotion。
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue("设置页换档必须挂 rememberShellFade", settings.contains("rememberShellFade(zone)"))
        val motion = sourceOf("ui/design/ShellMotion.kt")
        assertTrue("换档时长要在 ShellMotion 里定义", motion.contains("const val ZONE_MILLIS"))
        assertTrue("ShellMotion 要给出换档规格", motion.contains("fun <T> zone()"))
    }

    @Test
    fun `等待态只有一套`() {
        // 三处「在等」（Mod 首扫 / 口上清单 / 市场安装）原来各用 Material 的进度条：默认高度、
        // 默认圆角、默认主题色，与壳层细线和分区色对不上；首扫那处还只转一条带，卡体空一片。
        // 现在统一走 ShellProgress（细带 + 确定/未知两态）+ ShellSkeletonRow（呼吸占位）。
        assertEquals(
            "壳层不许再用 Material 进度条（高度、圆角、配色都会分叉）",
            0,
            mainSources().sumOf { (_, src) -> materialProgressRefs(src) },
        )
        val kit = sourceOf("ui/design/ShellProgress.kt")
        assertTrue("进度带要有自己的档", kit.contains("fun ShellProgress("))
        assertTrue("占位条要有自己的档", kit.contains("fun ShellSkeletonRow("))
        assertTrue("进度带高度必须取 token 级常量", kit.contains("PROGRESS_HEIGHT"))
        assertTrue("扫描节奏必须借 ShellMotion", kit.contains("ShellMotion.Sweep"))
        assertTrue("呼吸节奏必须借 ShellMotion", kit.contains("ShellMotion.Pulse"))
        for (name in listOf("SettingsActivity.kt", "KojoManageCard.kt", "market/MarketUi.kt")) {
            assertTrue("$name 的等待态要走 ShellProgress", sourceOf(name).contains("ShellProgress("))
        }
        // 首扫与口上清单都有「卡体空一片」的问题，占位条是补这个体感的，别只留一条进度带。
        for (name in listOf("SettingsActivity.kt", "KojoManageCard.kt")) {
            assertTrue("$name 的等待态要给呼吸占位", sourceOf(name).contains("ShellSkeletonRow("))
        }
    }

    @Test
    fun `滚动边界必须有渐隐`() {
        // 长列表被顶栏/底边硬裁断时，卡片上沿会在半高状态直接消失，视线读成「切了一刀」。
        // 边缘渐隐是把这个空间事实讲清楚，不是装饰；它同样只能有一份实现。
        val kit = sourceOf("ui/design/ShellEdgeFade.kt")
        assertTrue("边缘渐隐必须存在", kit.contains("fun BoxScope.ShellEdgeFades("))
        assertTrue("渐隐只能靠墙纸色渐变，不许写死第二种底色", kit.contains("EratwInk.background()"))
        assertEquals(
            "上下两条渐变各一份",
            2,
            Regex("Brush\\.verticalGradient\\(").findAll(kit).count(),
        )
        assertTrue("撤遮罩的节奏要借 ShellMotion", kit.contains("ShellMotion.quick()"))
        val settings = sourceOf("SettingsActivity.kt")
        assertTrue("设置页长列表要挂边缘渐隐", settings.contains("ShellEdgeFades("))
        assertTrue(
            "渐隐要跟着滚动方向收放（能往上滚才压上边）",
            settings.contains("gridState.canScrollBackward") && settings.contains("gridState.canScrollForward"),
        )
    }

    @Test
    fun `文本钮只有一档`() {
        // 设置页十几处 `TextButton` 原来把「有没有颜色」两种写法混着用：有的把色调塞进内部 Text，
        // 有的干脆不写（吃 Material 主题的 primary），同一张卡里会出现两种绿。禁用态更是各写各的
        // `if (enabled) A else B`。现在统一：`colors = ShellButton.text(tone)`，内部 Text 只留字阶。
        val settings = sourceOf("SettingsActivity.kt")
        assertEquals(
            "设置页每处 TextButton 都要接 ShellButton.text(…)",
            Regex("TextButton\\(").findAll(settings).count(),
            Regex("ShellButton\\.text\\(").findAll(settings).count(),
        )
        assertTrue("文本钮至少还有十几处（别让哨兵空转）", Regex("ShellButton\\.text\\(").findAll(settings).count() >= 10)
        val kit = sourceOf("ui/design/ShellButton.kt")
        assertTrue("文本钮档必须在共享按钮件里", kit.contains("fun text(content: Color? = null)"))
        assertTrue("禁用态由件统一压 muted", kit.contains("disabledContentColor = EratwInk.textMuted()"))
    }

    @Test
    fun `关于页必须是玻璃材质`() {
        // 关于页是唯一「不做事、只看一眼」的页面，四张实心卡叠在纯黑底上像四块砖。
        // 这里钉住它整套材质：半透明卡面 + 顶部氛围光 + 同色发丝边/分隔线，
        // 且功能卡不许跟着换（半透明会压低长名单的正文对比度）。
        val kit = sourceOf("ui/design/ShellGlass.kt")
        assertTrue("玻璃卡必须存在", kit.contains("fun ShellGlassCard("))
        assertTrue("氛围光必须存在", kit.contains("fun ShellAmbientGlow("))
        assertTrue("发丝分隔线必须存在", kit.contains("fun glassRule(accent: Color)"))
        assertTrue("圆形外发光必须存在", kit.contains("fun Modifier.shellHalo("))
        assertTrue("边线宽度取 token", kit.contains("ShellSpace.Hairline"))
        assertTrue("卡面必须是半透明（透出背后的光）", kit.contains("GLASS_TOP_ALPHA = 0."))
        assertTrue("氛围光必须是径向渐变并落到透明", kit.contains("Brush.radialGradient"))
        assertTrue("反光边两头必须收", kit.contains("Color.Transparent"))
        val settings = sourceOf("SettingsActivity.kt")
        // 4 = 关于页四张卡（关于 / 匿名统计 / 教程重阅 / 署名）+ 1 = 页尾恢复默认卡的玻璃分支。
        assertEquals(
            "关于档四张卡 + 页尾恢复默认的玻璃分支都必须走玻璃壳",
            5,
            Regex("GlassSettingsCard \\{").findAll(settings).count(),
        )
        assertTrue(
            "关于档页面底必须铺氛围光（玻璃才有东西可透）",
            settings.contains("ShellAmbientGlow(accent = UiColors.ZoneAbout"),
        )
        assertTrue(
            "玻璃面上不许再用实心分隔线",
            Regex("glassRule\\(").findAll(settings).count() >= 3,
        )
        assertTrue(
            "页尾的恢复默认卡在关于档也要跟着变玻璃（否则整页只剩它一块砖）",
            settings.contains("glass = zone == SettingsZone.ABOUT"),
        )
        assertTrue(
            "玻璃卡之间的留白比功能页多一档",
            settings.contains("if (zone == SettingsZone.ABOUT) 16.dp"),
        )
        // 功能页仍是实心卡：SettingsCard 的调用数不许掉下来（防止有人顺手全换玻璃）。
        assertTrue(
            "功能卡必须仍是实心壳",
            Regex("SettingsCard \\{").findAll(settings).count() >= 10,
        )
    }

    @Test
    fun `市场面板是分层的名单面`() {
        // 市场覆盖层在同一个卡里塞了四段：抬头 / 筛选 / 长名单 / 底栏。第二十批之前四段是一条
        // 20dp 内距的平铺列——三行标题直接压到 chips 带上，名单在筛选带下被硬裁断，端点编辑
        // 常驻在最低处像一张其他页面的设置卡。这条钉住那次打磨的结构件，防止改回「一列平铺」。
        val market = sourceOf("market/MarketUi.kt")
        assertTrue(
            "市场抬头与底栏必须各有收边线（固定区与滚动区分得开）",
            Regex("MarketHairline\\(\\)").findAll(market).count() >= 2,
        )
        assertTrue("市场名单必须挂边缘渐隐", market.contains("ShellEdgeFades("))
        assertTrue("渐隐底色必须取卡面色（页面黑底会在卡上糊出亮带）", market.contains("color = EratwInk.raised()"))
        assertTrue(
            "渐隐要跟着滚动方向收放",
            market.contains("scrollState.canScrollBackward") && market.contains("scrollState.canScrollForward"),
        )
        assertTrue("筛选档必须并成一条横向可滚的单排（自己一根轴）", market.contains("horizontalScroll(chipScroll)"))
        assertTrue("筛选带要有自己的滚动状态", market.contains("val chipScroll = rememberScrollState()"))
        assertTrue("收藏档与 kind 档之间要有竖发丝分隔", market.contains("MARKET_CHIP_RULE_HEIGHT"))
        val edgeFade = sourceOf("ui/design/ShellEdgeFade.kt")
        assertTrue("侧边渐隐必须是同一件的横向变体", edgeFade.contains("fun BoxScope.ShellSideFades("))
        assertTrue("横向筛选带也要两端渐隐", market.contains("ShellSideFades("))
        assertTrue("侧边渐隐要跟着横向滚动收放", market.contains("chipScroll.canScrollForward"))
        val listItem = sourceOf("ui/design/ShellListItem.kt")
        assertTrue("筛选片要分视觉层与热区层（片体矮一档、可点区仍 44dp）", listItem.contains("CHIP_VISUAL_HEIGHT"))
        assertTrue("筛选片热区必须还取行级最小高度", listItem.contains("heightIn(min = ShellSpace.RowMinHeight)"))
        assertTrue("首读名单要给呼吸占位，不许只留一句小字", market.contains("ShellSkeletonRow(lines = MARKET_SKELETON_LINES)"))
        assertTrue("端点区必须走共享揭示头（默认收起）", market.contains("ShellDisclosureHeader("))
        assertTrue("端点收起态要交代当前档位", market.contains("badge = MarketEndpointUi.endpointStatus"))
        assertTrue("端点折叠态要存活转屏", market.contains("rememberSaveable { mutableStateOf(false) }"))
        assertTrue(
            "行详情展开要走行级节奏，不许整块瞬移",
            market.contains("ShellMotion.rowEnter()") && market.contains("ShellMotion.rowExit()"),
        )
        assertTrue("正在装的那一行不许再摆一颗禁用按钮", market.contains("actionText != null && !installingThis"))
    }

    /** 一处源码里 Material 进度条的**调用与 import** 条数（KDoc 里提到名字不算）。 */
    private fun materialProgressRefs(src: String): Int {
        val calls = Regex("(Linear|Circular)ProgressIndicator\\(").findAll(src).count()
        val imports = Regex("import androidx\\.compose\\.material3\\.(Linear|Circular)ProgressIndicator")
            .findAll(src)
            .count()
        return calls + imports
    }

    /** 主源码树里所有 Kotlin 文件的（相对路径, 归一换行后的正文）：给「全仓一票否决」类断言用。 */
    private fun mainSources(): List<Pair<String, String>> {
        val root = listOf(
            File("src/main/kotlin/com/eratw/port/android"),
            File("../app/src/main/kotlin/com/eratw/port/android"),
        ).firstOrNull { it.isDirectory }
            ?: error("找不到 app 主源码树（试了 src/main/kotlin 与 ../app/src/main/kotlin）")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it.readText(Charsets.UTF_8).replace("\r\n", "\n") }
            .toList()
    }

    /** 与 [ShellDesignSystemTest] 同口径：兼容 IDE 直跑（工作目录是模块根）与 gradle 跑（工作目录是模块根）。 */
    private fun sourceOf(relative: String): String {
        val candidates = listOf(
            File("src/main/kotlin/com/eratw/port/android/$relative"),
            File("../app/src/main/kotlin/com/eratw/port/android/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（试了 ${candidates.joinToString { it.path }}）")
        return file.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }
}
