package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 教程正文的纯逻辑钉子：行首结构解析（[parseTutorialLine]）、行内标记解析（[parsePixelMarkup]）
 * 与页序表。
 *
 * 渲染层的观感不在这里验（那是 `TutorialBook` 与离线预览器的事）；这里钉的是容易偷偷坏掉的契约：
 * 前缀不上屏、块类型不乱判、着色区间落在**去掉标记之后**的纯文本坐标上、页序与文案字段一一对应。
 */
class TutorialMarkupTest {

    // ---- 行首结构 --------------------------------------------------------------

    @Test
    fun `井号开头是小标题且前缀不上屏`() {
        val line = parseTutorialLine("# 在哪里操作？")
        assertEquals(TutorialLineKind.SUBHEAD, line.kind)
        assertEquals("在哪里操作？", line.text)
    }

    @Test
    fun `短横线开头是列表项`() {
        val line = parseTutorialLine("- 换手机之前先导出一次存档。")
        assertEquals(TutorialLineKind.BULLET, line.kind)
        assertEquals("换手机之前先导出一次存档。", line.text)
    }

    @Test
    fun `数字加点是编号条目且序号单独留下`() {
        val line = parseTutorialLine("2. 在系统文件选择器中找到存档。")
        assertEquals(TutorialLineKind.NUMBERED, line.kind)
        assertEquals("2", line.number)
        assertEquals("在系统文件选择器中找到存档。", line.text)
    }

    @Test
    fun `三连横线是分隔线`() {
        val line = parseTutorialLine("---")
        assertEquals(TutorialLineKind.DIVIDER, line.kind)
        assertTrue("分隔线不该带正文", line.text.isEmpty())
    }

    @Test
    fun `普通文字是段落且前缀不成立时不误判`() {
        assertEquals(TutorialLineKind.PARAGRAPH, parseTutorialLine("打开「设置 → 关于」查看版本。").kind)
        assertEquals(TutorialLineKind.PARAGRAPH, parseTutorialLine("#不是标题").kind)
        assertEquals(TutorialLineKind.PARAGRAPH, parseTutorialLine("-不是列表").kind)
        assertEquals(TutorialLineKind.PARAGRAPH, parseTutorialLine("1、不是编号").kind)
    }

    // ---- 行内标记 --------------------------------------------------------------

    @Test
    fun `无标记时原样输出且没有着色区间`() {
        val line = parsePixelMarkup("设置 → 关于")
        assertEquals("设置 → 关于", line.text)
        assertTrue("没有标记就不该有区间", line.spans.isEmpty())
    }

    @Test
    fun `高亮标记落成黄色区间且标记字符不上屏`() {
        val line = parsePixelMarkup("正文里的 *黄色* 字")
        assertEquals("正文里的 黄色 字", line.text)
        val span = line.spans.single()
        assertEquals(PixelMarkup.HIGHLIGHT, span.markup)
        assertEquals("黄色", line.text.substring(span.start, span.endExclusive))
    }

    @Test
    fun `双星号落成强调区间且不会被单星号吃掉`() {
        val line = parsePixelMarkup("请留意 **存档导入与导出** 这一组功能")
        assertEquals("请留意 存档导入与导出 这一组功能", line.text)
        val span = line.spans.single()
        assertEquals(PixelMarkup.EMPHASIS, span.markup)
        assertEquals("存档导入与导出", line.text.substring(span.start, span.endExclusive))
    }

    @Test
    fun `反引号落成水色区间`() {
        val line = parsePixelMarkup("投放至 `resources/背景图/` 目录")
        assertEquals("投放至 resources/背景图/ 目录", line.text)
        val span = line.spans.single()
        assertEquals(PixelMarkup.LINK, span.markup)
        assertEquals("resources/背景图/", line.text.substring(span.start, span.endExclusive))
    }

    @Test
    fun `同一段里三种标记各归各档`() {
        val line = parsePixelMarkup("**强调** 与 *高亮* 与 `路径`")
        assertEquals("强调 与 高亮 与 路径", line.text)
        assertEquals(3, line.spans.size)
        assertEquals(PixelMarkup.EMPHASIS, line.spans[0].markup)
        assertEquals(PixelMarkup.HIGHLIGHT, line.spans[1].markup)
        assertEquals(PixelMarkup.LINK, line.spans[2].markup)
        assertEquals("强调", line.text.substring(line.spans[0].start, line.spans[0].endExclusive))
        assertEquals("高亮", line.text.substring(line.spans[1].start, line.spans[1].endExclusive))
        assertEquals("路径", line.text.substring(line.spans[2].start, line.spans[2].endExclusive))
    }

    @Test
    fun `落单标记不吞字并着色到行尾`() {
        // 形状闸会先钉成对；这里只是兜底行为：标记字符丢弃、其后一直着色，文字一个不少。
        val line = parsePixelMarkup("前缀 *没闭合")
        assertEquals("前缀 没闭合", line.text)
        val span = line.spans.single()
        assertEquals(PixelMarkup.HIGHLIGHT, span.markup)
        assertEquals("没闭合", line.text.substring(span.start, span.endExclusive))
    }

    // ---- 页序 ------------------------------------------------------------------

    @Test
    fun `页序与文案字段一一对应`() {
        SettingsLanguage.entries.forEach { lang ->
            val copy = TutorialCopy.of(lang)
            val pages = tutorialPages(copy)
            assertEquals("教程固定七页", 7, pages.size)
            assertEquals(copy.welcomeTitle, pages[0].title)
            assertEquals(copy.welcomeBody, pages[0].body)
            assertEquals(copy.setupTitle, pages[1].title)
            assertEquals(copy.setupBody, pages[1].body)
            assertEquals(copy.savesTitle, pages[2].title)
            assertEquals(copy.mediaTitle, pages[3].title)
            assertEquals(copy.koujoTitle, pages[4].title)
            assertEquals(copy.tipsTitle, pages[5].title)
            assertEquals(copy.resourcesTitle, pages[6].title)
            assertEquals(copy.resourcesBody, pages[6].body)
        }
    }
}
