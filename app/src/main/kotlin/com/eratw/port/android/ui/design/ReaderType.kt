package com.eratw.port.android.ui.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/**
 * 首启教程（`TutorialBook`）阅读面的字阶 token（2026-11-26）。
 *
 * ## 为什么另立一档，不并进 [ShellType]
 *
 * 教程不是壳层的一页表单，而是一块**复古像素控制台**：正文用点阵字（`EratwPixelFont`）、
 * 行高按 1.7 倍放宽（16sp 配 27sp）、章节号做成方块页签。壳层字阶是「密集表单的阅读尺度」
 * （14sp 正文、20sp 行高）；把阅读面硬套进去，要么正文缩到 14sp 读得累，要么把壳层正文
 * 抬到 16sp 让设置页变松散 —— 两个面各有各的职责，各有一份 token 才是对的。
 *
 * ## 口径
 *
 * - **阅读档（Reading / ReadingTight）**：正文与小标题同字号，只差行高 —— 小标题靠黄色竖条
 *   和字重区分，不靠缩字；
 * - **标题档（Chapter / RailTitle）**：竖屏页标题比横屏导轨标题大一档（横屏竖向空间紧）；
 * - **元数据档（Meta / Overline / RailIndex）**：页签、页码、书名条、导轨编号 —— 11~14sp
 *   之间，越靠外圈越小；
 * - **字形档（Glyph / Hint）**：单个记号（`>`、`×`、`▼`），只定字号不定行高。
 *
 * 字体族不在这里定：阅读面统一吃调用方传进来的像素字，本文件只管**尺码**。
 */
object ReaderType {

    /** 正文段落、列表项、编号条目：全页最该读的那一档（行高 1.7 倍，两行之间不糊）。 */
    val Reading = TextStyle(fontSize = 16.sp, lineHeight = 27.sp)

    /** 段内小标题：同字号、行高收紧 —— 它要跟下面的段落贴成一组。 */
    val ReadingTight = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)

    /** 竖屏页标题（章节大标题）。 */
    val Chapter = TextStyle(fontSize = 18.sp, lineHeight = 22.sp)

    /** 横屏右栏的章节标题：竖向空间紧，比页标题低一档。 */
    val RailTitle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp)

    /** 横屏右栏的章节号（`01`）：等距数字配 1sp 字距，读起来像仪表盘。 */
    val RailIndex = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 1.sp)

    /** 页签编号、翻页按钮：视觉小，热区由布局撑。 */
    val Meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

    /** 页码（`01 / 07`）：与 [RailIndex] 同一套等距数字，但落在页脚，收一档不抢按钮。 */
    val Counter = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.sp)

    /** 书名条（小号全大写感的位）：全页最弱的一行。 */
    val Overline = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp)

    /** 右下角「还有下文」的 ▼：比元数据再小一点，只为提示存在。 */
    val Hint = TextStyle(fontSize = 11.sp, lineHeight = 14.sp)

    /** 单字记号（`>` 与 `×`）：这两个不是文字，是符号，只按字号取。 */
    val Glyph = TextStyle(fontSize = 16.sp, lineHeight = 20.sp)
}
