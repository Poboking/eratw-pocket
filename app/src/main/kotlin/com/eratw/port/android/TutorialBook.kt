package com.eratw.port.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eratw.port.android.ui.EratwInk

/**
 * 首启教程的**排版本体**：一块「eraTW 文字游戏」质感的复古像素面板。
 *
 * ## 为什么长这样
 * 教程此前是「羊皮书 + 衬线字 + 火漆印」的旧书隐喻，和本体（Emuera 文字游戏）不是一套语言。
 * 这一版整块改用本体自己的视觉词表，色值与框架都能在语料里查到出处：
 * - 黑底 + 银灰正文 + 黄色高亮，就是 `emuera.config` 的
 *   `BACKGROUND COLOR:0,0,0` / `TEXT COLOR:192,192,192` / `HIGHLIGHT COLOR:255,255,0`；
 * - 2dp 浅灰外框 + 近黑面板底，仿 `ERB/TITLE.ERB` 里那个
 *   `border='2px' bcolor='#a5a5a5' color='#363636'` 的 HTML div；
 * - 章节号做成方块页签（`01`–`07`），照游戏「数字选项」的菜单语法，且可直接点跳；
 * - 分隔线用整条实心粗线（游戏里 `CUSTOMDRAWLINE ━` 的味道），不用花饰；
 * - 正文用 Ark Pixel 12px 点阵字（`EratwPixelFont`），配极淡扫描线，做出 CRT 字屏观感。
 *
 * ## 文案里的行首结构与三种行内标记
 * 正文按行切块，行首前缀决定块的形状（解析在 `TutorialMarkup.kt` 的 `parseTutorialLine`）：
 * `# 小标题` → 黄竖条 + 米白小标题；`- 条目` → 黄点列表；`1. 步骤` → 黄序号列；
 * `---` → 段内细线；其余一律当段落。
 *
 * 行内着色（`parsePixelMarkup`）：`**强调**` → 米白（比正文亮一档，不抢黄色）；
 * `*高亮*` → 游戏高亮黄（关键词、要玩家留意的事实）；`` `路径` `` → 水色（可照抄的目录 /
 * 文件名）。标记都是**成对**的，不配对时按「从这里开始一直着色到行尾」降级，不吞字。
 *
 * ## 版式纪律
 * 按屏幕方向分两套，面板都吃满可用高度，正文区自己滚 —— 短页不缩、长页不撑，
 * 页脚固定，任何时候都够得着「上一页 / 下一页 / 开始游戏」：
 * - **竖屏**：书名条 → `01–07` 横排页签 → 章节大标题 → 正文 → 页脚，宽封顶 620dp；
 * - **横屏**：左侧竖排章节导轨（书名 / 编号 / 当前章高亮）、右侧「章节号 + 标题 + 关闭」
 *   与正文，宽封顶 1100dp。横屏的瓶颈是**竖向空间**（手机横过来只有 ~390dp 高），
 *   所以把页签和大标题挪出正文列，正文可见行数从两三行回到八到十行。
 *
 * 手上没法用触摸屏也能核版式：本文件不引用任何 Android API，预览器（`.tmp/tutorial-preview`）
 * 直接渲染同一份代码出 PNG；底下的 `TutorialDialog` 只负责把它装进对话框窗口。
 * [initialPage] 只给离线预览 / 版式单测从指定页起画，线上调用一律省略（缺省第一页）。
 */
@Composable
internal fun TutorialBook(
    copy: TutorialCopy,
    fontFamily: FontFamily,
    onDismiss: () -> Unit,
    initialPage: Int = 0,
) {
    val pages = remember(copy) { tutorialPages(copy) }
    var page by remember(copy) { mutableIntStateOf(initialPage.coerceIn(0, pages.lastIndex)) }
    val goTo: (Int) -> Unit = { index -> page = index.coerceIn(0, pages.lastIndex) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(EratwInk.scrim())
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // 横屏换成「左章节导轨 + 右正文」：竖屏那套单栏在 390dp 高的横屏里，
        // 书名/页签/大标题/页脚一挤，正文只剩两三行，等于没法读。
        // 两个判断都在 BoxWithConstraints 这一层算好再往下传：嵌套布局作用域里
        // 拿不到外层的 maxWidth（Compose 的 LayoutScopeMarker 限制隐式接收者）。
        val landscape = maxWidth > maxHeight
        // 宽屏横放时导轨给宽一点：英文章节标题在 232dp 里会被省略号截掉。
        val wideRail = maxWidth >= 760.dp
        Box(
            modifier = Modifier
                .padding(
                    horizontal = if (landscape) 8.dp else 10.dp,
                    vertical = if (landscape) 8.dp else 12.dp,
                )
                .widthIn(max = if (landscape) 1100.dp else 620.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .background(EratwInk.consolePanel())
                .border(2.dp, EratwInk.consoleFrame())
                // 面板吃自己的点击，不透到遮罩上误关（空点击、无涟漪）。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp)
                    .border(1.dp, EratwInk.consoleRule()),
            ) {
                if (landscape) {
                    LandscapeBody(
                        copy = copy,
                        pages = pages,
                        page = page,
                        fontFamily = fontFamily,
                        onSelect = goTo,
                        onPrev = { if (page > 0) page -= 1 },
                        onNext = { if (page < pages.lastIndex) page += 1 },
                        onDismiss = onDismiss,
                        wideRail = wideRail,
                    )
                } else {
                    PortraitBody(
                        copy = copy,
                        pages = pages,
                        page = page,
                        fontFamily = fontFamily,
                        onSelect = goTo,
                        onPrev = { if (page > 0) page -= 1 },
                        onNext = { if (page < pages.lastIndex) page += 1 },
                        onDismiss = onDismiss,
                    )
                }
            }
            // CRT 扫描线：盖在最上层、极淡，只为观感，不吃触摸。
            Scanlines(modifier = Modifier.matchParentSize())
        }
    }
}

/** 竖屏：书名条 → 章节页签 → 章节标题/正文 → 页脚，单栏到底。 */
@Composable
private fun PortraitBody(
    copy: TutorialCopy,
    pages: List<TutorialPage>,
    page: Int,
    fontFamily: FontFamily,
    onSelect: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        BookHeader(copy = copy, fontFamily = fontFamily, onDismiss = onDismiss)
        HeavyRule(modifier = Modifier.padding(top = 10.dp))
        ChapterStrip(
            pages = pages.size,
            current = page,
            fontFamily = fontFamily,
            onSelect = onSelect,
        )
        TutorialPageFlow(
            pages = pages,
            page = page,
            fontFamily = fontFamily,
            showHeading = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        HeavyRule(modifier = Modifier.padding(top = 10.dp))
        PageFooter(
            copy = copy,
            page = page,
            lastIndex = pages.lastIndex,
            fontFamily = fontFamily,
            onPrev = onPrev,
            onNext = onNext,
            onFinish = onDismiss,
        )
    }
}

/**
 * 横屏：左侧竖排章节导轨（书名 / `01–07` / 进度），右侧正文。
 *
 * 横屏的核心收益是**竖向空间**：导轨把「页签 + 大标题」从竖屏那一列挪到侧边，
 * 正文可视高度从两三行回到八到十行，宽屏又让每行多装十几个汉字。
 */
@Composable
private fun LandscapeBody(
    copy: TutorialCopy,
    pages: List<TutorialPage>,
    page: Int,
    fontFamily: FontFamily,
    onSelect: (Int) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    wideRail: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        ChapterRail(
            copy = copy,
            pages = pages,
            current = page,
            fontFamily = fontFamily,
            onSelect = onSelect,
            modifier = Modifier.width(if (wideRail) 300.dp else 232.dp).fillMaxHeight(),
        )
        // 导轨与正文之间的竖隔线：两端留 4dp，别让它顶到外框上。
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(EratwInk.consoleRule()),
        )
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            LandscapeHeader(
                page = page,
                title = pages[page].title,
                copy = copy,
                fontFamily = fontFamily,
                onDismiss = onDismiss,
            )
            HeavyRule(modifier = Modifier.padding(top = 8.dp))
            TutorialPageFlow(
                pages = pages,
                page = page,
                fontFamily = fontFamily,
                showHeading = false,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            HeavyRule(modifier = Modifier.padding(top = 8.dp))
            PageFooter(
                copy = copy,
                page = page,
                lastIndex = pages.lastIndex,
                fontFamily = fontFamily,
                onPrev = onPrev,
                onNext = onNext,
                onFinish = onDismiss,
            )
        }
    }
}

/** 横屏左导轨：书名 + 七章竖排列表 + 当前进度；章节可直接点跳。 */
@Composable
private fun ChapterRail(
    copy: TutorialCopy,
    pages: List<TutorialPage>,
    current: Int,
    fontFamily: FontFamily,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(EratwInk.consoleHighlight()))
            Spacer(Modifier.width(8.dp))
            Text(
                text = copy.bookTitle,
                color = EratwInk.consoleTextDim(),
                fontFamily = fontFamily,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HeavyRule(modifier = Modifier.padding(top = 8.dp))
        // 小屏横屏（360dp 高）也放得下；真挤了就自己滚，不裁掉章节。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            pages.forEachIndexed { index, page ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                ChapterRailItem(
                    index = index,
                    title = page.title,
                    active = index == current,
                    fontFamily = fontFamily,
                    onClick = { onSelect(index) },
                )
            }
        }
        HeavyRule()
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChapterRailItem(
    index: Int,
    title: String,
    active: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (active) {
                    Modifier.background(EratwInk.consoleBox().copy(alpha = 0.45f))
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 22.dp)
                .then(
                    if (active) {
                        Modifier.background(EratwInk.consoleHighlight())
                    } else {
                        Modifier.border(1.dp, EratwInk.consoleRule())
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (index + 1).toString().padStart(2, '0'),
                color = if (active) Color.Black else EratwInk.consoleTextDim(),
                fontFamily = fontFamily,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = if (active) EratwInk.consoleCream() else EratwInk.consoleTextDim(),
            fontFamily = fontFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 横屏右栏头：章节号 + 章节标题 + 关闭钮（横屏没有别处放关闭钮）。 */
@Composable
private fun LandscapeHeader(
    page: Int,
    title: String,
    copy: TutorialCopy,
    fontFamily: FontFamily,
    onDismiss: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = (page + 1).toString().padStart(2, '0'),
            color = EratwInk.consoleHighlight(),
            fontFamily = fontFamily,
            fontSize = 14.sp,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = EratwInk.consoleCream(),
            fontFamily = fontFamily,
            fontSize = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        CloseSquare(copy = copy, fontFamily = fontFamily, onDismiss = onDismiss)
    }
}

/**
 * 翻页动画的共用壳：竖屏带头上的章节大标题，横屏标题已在右栏头里，只画正文。
 *
 * @param showHeading 竖屏 true（标题 + 细线 + 正文），横屏 false（细线 + 正文）。
 */
@Composable
private fun TutorialPageFlow(
    pages: List<TutorialPage>,
    page: Int,
    fontFamily: FontFamily,
    showHeading: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 12 }) togetherWith
                (fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { -it / 12 })
        },
        modifier = modifier,
        label = "tutorial-page",
    ) { idx ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (showHeading) {
                ChapterHeading(
                    title = pages[idx].title,
                    fontFamily = fontFamily,
                    modifier = Modifier.padding(top = 14.dp),
                )
                BookRule(modifier = Modifier.padding(top = 8.dp))
            }
            PageBody(
                body = pages[idx].body,
                fontFamily = fontFamily,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = if (showHeading) 10.dp else 6.dp),
            )
        }
    }
}

// ---- 版式零件 ------------------------------------------------------------------

@Composable
private fun BookHeader(copy: TutorialCopy, fontFamily: FontFamily, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一枚黄色小方块当书签，比图标更像 2000 年代文字游戏的状态灯。
        Box(Modifier.size(6.dp).background(EratwInk.consoleHighlight()))
        Spacer(Modifier.width(8.dp))
        Text(
            text = copy.bookTitle,
            color = EratwInk.consoleTextDim(),
            fontFamily = fontFamily,
            fontSize = 10.sp,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        CloseSquare(copy = copy, fontFamily = fontFamily, onDismiss = onDismiss)
    }
}

/** 关闭钮：视觉上是 28dp 的小方钮，命中区放到 40dp —— 手指点得中，版面又不被撑大。 */
@Composable
private fun CloseSquare(copy: TutorialCopy, fontFamily: FontFamily, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .semantics { contentDescription = copy.closeAction },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(28.dp).border(1.dp, EratwInk.consoleRule()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "×",
                color = EratwInk.consoleText(),
                fontFamily = fontFamily,
                fontSize = 16.sp,
            )
        }
    }
}

/** 章节页签：`01`–`07` 方块，当前页反白（黄底黑字），可直接点跳到任意章。 */
@Composable
private fun ChapterStrip(
    pages: Int,
    current: Int,
    fontFamily: FontFamily,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(pages) { index ->
            val active = index == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(26.dp)
                    .then(
                        if (active) {
                            Modifier.background(EratwInk.consoleHighlight())
                        } else {
                            Modifier.border(1.dp, EratwInk.consoleRule())
                        },
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = (index + 1).toString().padStart(2, '0'),
                    color = when {
                        active -> Color.Black
                        index < current -> EratwInk.consoleText()
                        else -> EratwInk.consoleTextDim()
                    },
                    fontFamily = fontFamily,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ChapterHeading(title: String, fontFamily: FontFamily, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = ">",
            color = EratwInk.consoleHighlight(),
            fontFamily = fontFamily,
            fontSize = 15.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            color = EratwInk.consoleCream(),
            fontFamily = fontFamily,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PageBody(body: String, fontFamily: FontFamily, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // 「还有下文」的判据直接比两侧实高：ScrollState.maxValue 在 AnimatedContent 的嵌套测量里
    // 会停在 Int.MAX_VALUE（滚动列没拿到有界高度），按它判会永远亮着 ▼。
    var contentHeightPx by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        val viewportPx = with(density) { maxHeight.toPx() }
        val overflow = contentHeightPx > viewportPx + 1f
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scroll)
                .onSizeChanged { contentHeightPx = it.height }
                .padding(bottom = 6.dp),
        ) {
            body.split('\n').filter { it.isNotBlank() }.forEachIndexed { index, line ->
                if (index > 0) Spacer(Modifier.height(11.dp))
                Paragraph(line = line, fontFamily = fontFamily)
            }
        }
        if (overflow) {
            // 底部渐隐：视口把最后一行截断时，硬切在页脚线上很难看，用同色渐变化掉。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(18.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, EratwInk.consolePanel()),
                        ),
                    ),
            )
            BlinkingScrollHint(
                fontFamily = fontFamily,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp, bottom = 1.dp),
            )
        }
    }
}

@Composable
private fun Paragraph(line: String, fontFamily: FontFamily) {
    val parsed = remember(line) { parseTutorialLine(line) }
    when (parsed.kind) {
        TutorialLineKind.SUBHEAD -> SubHeading(text = parsed.text, fontFamily = fontFamily)
        TutorialLineKind.BULLET -> BulletItem(text = parsed.text, fontFamily = fontFamily)
        TutorialLineKind.NUMBERED -> NumberedItem(line = parsed, fontFamily = fontFamily)
        TutorialLineKind.DIVIDER -> BodyDivider()
        TutorialLineKind.PARAGRAPH ->
            MarkupText(raw = parsed.text, fontFamily = fontFamily, modifier = Modifier.fillMaxWidth())
    }
}

/** 段内小标题：黄色竖条 + 米白标题，比页标题低一档，用来切分一段里的几个小节。 */
@Composable
private fun SubHeading(text: String, fontFamily: FontFamily) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(17.dp).background(EratwInk.consoleHighlight()))
        Spacer(Modifier.width(8.dp))
        MarkupText(
            raw = text,
            fontFamily = fontFamily,
            color = EratwInk.consoleCream(),
            fontSize = 16,
            lineHeight = 22,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 列表项：黄色圆点起头，正文与段落同字号。 */
@Composable
private fun BulletItem(text: String, fontFamily: FontFamily) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "・",
            color = EratwInk.consoleHighlight(),
            fontFamily = fontFamily,
            fontSize = 16.sp,
            lineHeight = 27.sp,
            modifier = Modifier.width(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        MarkupText(raw = text, fontFamily = fontFamily, modifier = Modifier.weight(1f))
    }
}

/** 编号条目：序号独占一列、染成高亮黄，正文照常排（游玩建议与操作步骤都用它）。 */
@Composable
private fun NumberedItem(line: TutorialLine, fontFamily: FontFamily) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${line.number}.",
            color = EratwInk.consoleHighlight(),
            fontFamily = fontFamily,
            fontSize = 16.sp,
            lineHeight = 27.sp,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(4.dp))
        MarkupText(raw = line.text, fontFamily = fontFamily, modifier = Modifier.weight(1f))
    }
}

/** 段内分隔线：`---` 的落点，两端各留一点气口。 */
@Composable
private fun BodyDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(EratwInk.consoleRule()),
    )
}

@Composable
private fun MarkupText(
    raw: String,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
    color: Color = EratwInk.consoleText(),
    fontSize: Int = 16,
    lineHeight: Int = 27,
) {
    val line = remember(raw) { parsePixelMarkup(raw) }
    val styled = remember(line) {
        buildAnnotatedString {
            append(line.text)
            line.spans.forEach { span ->
                when (span.markup) {
                    PixelMarkup.HIGHLIGHT ->
                        addStyle(SpanStyle(color = EratwInk.consoleHighlight()), span.start, span.endExclusive)
                    PixelMarkup.LINK ->
                        addStyle(SpanStyle(color = EratwInk.consoleLink()), span.start, span.endExclusive)
                    PixelMarkup.EMPHASIS ->
                        addStyle(SpanStyle(color = EratwInk.consoleCream()), span.start, span.endExclusive)
                    PixelMarkup.NORMAL -> Unit
                }
            }
        }
    }
    Text(
        text = styled,
        color = color,
        fontFamily = fontFamily,
        fontSize = fontSize.sp,
        lineHeight = lineHeight.sp,
        modifier = modifier,
    )
}

@Composable
private fun BlinkingScrollHint(fontFamily: FontFamily, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scroll-hint")
    val alpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "hint-alpha",
    )
    // 自带底板：提示会压在正文右下角，没有底会跟文字糊在一起。底板保持不透明，
    // 只让 ▼ 自己闪 —— 整块一起淡入淡出时，底下的半行字会透上来。
    Box(
        modifier = modifier
            .background(EratwInk.consolePanel())
            .border(1.dp, EratwInk.consoleRule())
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = "▼",
            color = EratwInk.consoleText(),
            fontFamily = fontFamily,
            fontSize = 11.sp,
            modifier = Modifier.alpha(alpha),
        )
    }
}

@Composable
private fun PageFooter(
    copy: TutorialCopy,
    page: Int,
    lastIndex: Int,
    fontFamily: FontFamily,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelGhostButton(
            text = copy.prevButton,
            enabled = page > 0,
            fontFamily = fontFamily,
            onClick = onPrev,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${(page + 1).toString().padStart(2, '0')} / ${(lastIndex + 1).toString().padStart(2, '0')}",
            color = EratwInk.consoleTextDim(),
            fontFamily = fontFamily,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.weight(1f))
        if (page < lastIndex) {
            PixelGhostButton(
                text = copy.nextButton,
                enabled = true,
                fontFamily = fontFamily,
                onClick = onNext,
            )
        } else {
            PixelPrimaryButton(text = copy.startButton, fontFamily = fontFamily, onClick = onFinish)
        }
    }
}

/** 次级翻页钮：1dp 灰框 + 银灰字；不可用时整体压暗，但仍占位（版面不跳）。 */
@Composable
private fun PixelGhostButton(
    text: String,
    enabled: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .border(1.dp, EratwInk.consoleRule())
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.3f)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            color = EratwInk.consoleText(),
            fontFamily = fontFamily,
            fontSize = 12.sp,
        )
    }
}

/** 末页主行动：实心高亮黄底 + 黑字，整屏最重的一块，一眼知道收场在哪。 */
@Composable
private fun PixelPrimaryButton(text: String, fontFamily: FontFamily, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(EratwInk.consoleHighlight())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(
            text = text,
            color = Color.Black,
            fontFamily = fontFamily,
            fontSize = 12.sp,
        )
    }
}

/** 整条实心粗线：游戏 `CUSTOMDRAWLINE ━` 的替身，用来切头 / 切尾。 */
@Composable
private fun HeavyRule(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(2.dp)) {
        drawRect(color = EratwInk.consoleRule(), size = size)
    }
}

/** 细一档的分隔线：压在章节标题与正文之间。 */
@Composable
private fun BookRule(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(1.dp)) {
        drawRect(color = EratwInk.consoleRule(), size = size)
    }
}

/** CRT 扫描线：整面板每 3dp 一条 1px 半透明线，只在像素面板上用，不影响点击。 */
@Composable
private fun Scanlines(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val step = 3.dp.toPx()
        val color = EratwInk.consoleScanline()
        var y = 0f
        while (y < size.height) {
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            y += step
        }
    }
}
