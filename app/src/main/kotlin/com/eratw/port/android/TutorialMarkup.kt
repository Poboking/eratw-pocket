package com.eratw.port.android

/**
 * 教程弹窗的**纯逻辑**面：页序表、正文行的结构解析、行内标记解析。
 *
 * 这里刻意不 import 任何 Compose / Android API —— 版式渲染（`TutorialBook`）依赖它，
 * JVM 单测和离线预览器也能直接调，不必拉 Compose 运行时。
 *
 * 正文是纯文本，靠**行首前缀**表达结构（一行一个块）：
 * - `# 小标题` → 段内小标题（渲染成黄色竖条 + 米白标题）
 * - `- 条目` → 列表项（黄色圆点）
 * - `1. 步骤` → 编号条目（黄色序号列）
 * - `---` → 段内分隔线
 * - 其余 → 普通段落
 *
 * 行内标记（两个 ASCII 记号 + 一个反引号，见 [parsePixelMarkup]）：
 * `**强调**` → 米白强调；`*高亮*` → 游戏高亮黄；`` `路径` `` → 水色（可照抄的目录/文件名）。
 */

/** 一页教程：标题 + 正文（按 `'\n'` 分块，块内可带行内标记与行首结构前缀）。 */
internal data class TutorialPage(val title: String, val body: String)

/** 七页的顺序与 [TutorialCopy] 的字段一一对应；改页序只改这一处。 */
internal fun tutorialPages(copy: TutorialCopy): List<TutorialPage> = listOf(
    TutorialPage(copy.welcomeTitle, copy.welcomeBody),
    TutorialPage(copy.setupTitle, copy.setupBody),
    TutorialPage(copy.savesTitle, copy.savesBody),
    TutorialPage(copy.mediaTitle, copy.mediaBody),
    TutorialPage(copy.koujoTitle, copy.koujoBody),
    TutorialPage(copy.tipsTitle, copy.tipsBody),
    TutorialPage(copy.resourcesTitle, copy.resourcesBody),
)

/** 一个正文块的结构类型。 */
internal enum class TutorialLineKind {
    /** 普通段落。 */
    PARAGRAPH,

    /** `# ` 开头的段内小标题。 */
    SUBHEAD,

    /** `- ` 开头的列表项。 */
    BULLET,

    /** `N. ` 开头的编号条目，[TutorialLine.number] 是序号原文。 */
    NUMBERED,

    /** `---` 分隔线。 */
    DIVIDER,
}

/** 解析后的一个正文块；[text] 不含行首前缀，行内标记留给 [parsePixelMarkup]。 */
internal data class TutorialLine(
    val kind: TutorialLineKind,
    val text: String,
    val number: String = "",
)

private val NUMBERED_LINE = Regex("^(\\d{1,2})\\.\\s+(.*)$")

/**
 * 按行首前缀判定正文块类型。前缀不成立时一律当普通段落 —— 玩家看的是一段话，
 * 排版细节不该因为多打一个空格就换一种块。
 */
internal fun parseTutorialLine(raw: String): TutorialLine {
    val line = raw.trimEnd()
    return when {
        line.trim() == "---" -> TutorialLine(TutorialLineKind.DIVIDER, "")
        line.startsWith("# ") -> TutorialLine(TutorialLineKind.SUBHEAD, line.removePrefix("# ").trimStart())
        line.startsWith("- ") -> TutorialLine(TutorialLineKind.BULLET, line.removePrefix("- ").trimStart())
        else -> {
            val numbered = NUMBERED_LINE.find(line)
            if (numbered != null) {
                TutorialLine(
                    kind = TutorialLineKind.NUMBERED,
                    text = numbered.groupValues[2],
                    number = numbered.groupValues[1],
                )
            } else {
                TutorialLine(TutorialLineKind.PARAGRAPH, line)
            }
        }
    }
}

/** 正文片段的着色档；[NORMAL] 走块级正文色，不单独上色。 */
internal enum class PixelMarkup { NORMAL, HIGHLIGHT, LINK, EMPHASIS }

/** 纯文本坐标下的一段着色区间（`endExclusive` 便于直接喂 `AnnotatedString.addStyle`）。 */
internal data class PixelSpan(val start: Int, val endExclusive: Int, val markup: PixelMarkup)

/** 解析后的一行：去标记的纯文本 + 着色区间。 */
internal data class PixelLine(val text: String, val spans: List<PixelSpan>)

/**
 * 解析行内标记，**不依赖 Compose**（便于单测）。
 *
 * 三种标记各自开关自己的档位：`**` 强调、`*` 高亮、反引号 路径。`**` 优先于 `*` 识别，
 * 所以「`**加粗**`」与「`*高亮*`」不会互相吃掉。切换标记时当前档位直接换档（不支持嵌套）。
 * 落单的标记按「从标记处一直着色到行尾」收尾，标记字符本身丢弃 —— 文案形状闸
 * （`TutorialCopyTest`）会先钉成对，这只是兜底，不靠它保正确。
 */
internal fun parsePixelMarkup(raw: String): PixelLine {
    val plain = StringBuilder(raw.length)
    val spans = ArrayList<PixelSpan>(6)
    var markup = PixelMarkup.NORMAL
    var segmentStart = 0
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        var next: PixelMarkup? = null
        var consumed = 1
        when {
            ch == '*' && i + 1 < raw.length && raw[i + 1] == '*' -> {
                next = if (markup == PixelMarkup.EMPHASIS) PixelMarkup.NORMAL else PixelMarkup.EMPHASIS
                consumed = 2
            }
            ch == '*' -> next = if (markup == PixelMarkup.HIGHLIGHT) PixelMarkup.NORMAL else PixelMarkup.HIGHLIGHT
            ch == '`' -> next = if (markup == PixelMarkup.LINK) PixelMarkup.NORMAL else PixelMarkup.LINK
        }
        if (next == null) {
            plain.append(ch)
            i += 1
            continue
        }
        if (markup != PixelMarkup.NORMAL && plain.length > segmentStart) {
            spans += PixelSpan(segmentStart, plain.length, markup)
        }
        markup = next
        segmentStart = plain.length
        i += consumed
    }
    if (markup != PixelMarkup.NORMAL && plain.length > segmentStart) {
        spans += PixelSpan(segmentStart, plain.length, markup)
    }
    return PixelLine(plain.toString(), spans)
}
