package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TutorialCopy] 的三语形状闸（照 PlayerStatsCopyTest / CopyShapeGate 同一套判据）。
 *
 * 这张表独立存在的原因见它的类头：大表 [SettingsStrings] 再加构造属性会撞 JVM 方法签名
 * 255 个参数槽的上限。这里显式点名每一枚键，谁加文案不三档一起加就红。
 * 功能落点的事实（分区名、投放目录、索引文件、贴吧）另钉一条对拍：
 * 教程说的路必须和设置页真实的路一致，改设置页结构时这条用例负责提醒同步教程。
 *
 * 教程正文是**带行首结构的纯文本**（`# ` / `- ` / `N. ` / `---`），所以除了「三语换行数一致」，
 * 还钉「三语逐行块类型一致」与「编号条目从 1 连续」——译文只翻字，不许改结构。
 */
class TutorialCopyTest {

    /** 显式键清单：加一条文案就得在这里点名，并把 assertKeyCount 的期望数一起改。 */
    private val keys: Map<String, KProperty1<TutorialCopy, String>> = mapOf(
        "bookTitle" to TutorialCopy::bookTitle,
        "welcomeTitle" to TutorialCopy::welcomeTitle,
        "welcomeBody" to TutorialCopy::welcomeBody,
        "setupTitle" to TutorialCopy::setupTitle,
        "setupBody" to TutorialCopy::setupBody,
        "savesTitle" to TutorialCopy::savesTitle,
        "savesBody" to TutorialCopy::savesBody,
        "mediaTitle" to TutorialCopy::mediaTitle,
        "mediaBody" to TutorialCopy::mediaBody,
        "koujoTitle" to TutorialCopy::koujoTitle,
        "koujoBody" to TutorialCopy::koujoBody,
        "tipsTitle" to TutorialCopy::tipsTitle,
        "tipsBody" to TutorialCopy::tipsBody,
        "resourcesTitle" to TutorialCopy::resourcesTitle,
        "resourcesBody" to TutorialCopy::resourcesBody,
        "prevButton" to TutorialCopy::prevButton,
        "nextButton" to TutorialCopy::nextButton,
        "startButton" to TutorialCopy::startButton,
        "closeAction" to TutorialCopy::closeAction,
        "revisitTitle" to TutorialCopy::revisitTitle,
        "revisitHint" to TutorialCopy::revisitHint,
        "revisitButton" to TutorialCopy::revisitButton,
    )

    /** 正文类键（按行分块的那几枚）。 */
    private val bodyKeys = listOf(
        "welcomeBody", "setupBody", "savesBody", "mediaBody", "koujoBody", "tipsBody", "resourcesBody",
    )

    private val copies = SettingsLanguage.entries.map { TutorialCopy.of(it) }

    /** 盘上真实目录名与贴吧名是物理事实：EN 档里出现汉字只豁免这两条。 */
    private val hanAllowed = setOf("mediaBody", "resourcesBody")

    @Test
    fun `三档键数一致且与显式清单对账`() {
        // lang + 22 枚文案 = 23 枚构造属性；文案里不许出现等号（会污染计数）。
        CopyShapeGate.assertKeyCount("TutorialCopy", copies, keys, 23)
        assertEquals("键清单漏点名", 22, keys.size)
    }

    @Test
    fun `三档每条非空且两两不同文`() {
        CopyShapeGate.assertNonEmptyAndDistinct(copies, keys)
    }

    @Test
    fun `槽位与换行三档对齐且灌满后无残留`() {
        CopyShapeGate.assertSlotsAndNewlinesAligned(copies, keys)
        // 纯静态文案，一条槽位都没有：fill 即恒等映射。
        CopyShapeGate.assertNoSlotResidue(copies, keys) { _, template, _ -> template }
    }

    @Test
    fun `EN 不混汉字 ZH 有底稿 JA 不是英文`() {
        CopyShapeGate.assertLanguageSelfEvident(copies, keys, hanAllowed = hanAllowed)
    }

    @Test
    fun `按设置语言单路取到对应档位`() {
        SettingsLanguage.entries.forEach { lang ->
            assertEquals(lang, TutorialCopy.of(lang).lang)
        }
        assertSame(
            "同一档位是同一个实例，不给 Compose 每帧造新对象",
            TutorialCopy.of(SettingsLanguage.ZH),
            TutorialCopy.of(SettingsLanguage.ZH),
        )
    }

    @Test
    fun `教程指的路与设置页真实落点一致`() {
        val zh = copies[0]
        // 存档区在「移植版功能」分区顶部（SettingsActivity PORT 档列表最前）。
        assertTrue("存档页没点名分区", zh.savesBody.contains("移植版功能"))
        assertTrue("存档页没提 global.sav", zh.savesBody.contains("global.sav"))
        // 素材投放根与索引文件是物理事实，三语都得原样搬运。
        copies.forEach { copy ->
            assertTrue("素材页没提背景图投放根", copy.mediaBody.contains("resources/背景图/"))
            assertTrue("素材页没提 BGM 投放根", copy.mediaBody.contains("sound/游戏bgm/"))
            assertTrue("素材页没提官方包索引", copy.mediaBody.contains("BGI.csv"))
            assertTrue("口上页没提源文件扩展名", copy.koujoBody.contains(".ERB/.ERH"))
            assertTrue("口上页没提 zip 整合包", copy.koujoBody.contains("zip", ignoreCase = true))
            assertTrue("资源页没点名贴吧", copy.resourcesBody.contains("eratw吧"))
        }
        assertTrue("中文资源页没提百度贴吧", zh.resourcesBody.contains("百度贴吧"))
    }

    /** 正文的行内标记是渲染契约：`**强调**` / `*高亮*` / `` `路径` `` 必须成对，否则渲染端只能降级。 */
    @Test
    fun `行内标记成对且不空`() {
        copies.forEach { copy ->
            keys.forEach { (name, property) ->
                val text = property(copy)
                assertEquals("$name（${copy.lang}）：* 标记必须成对", 0, text.count { it == '*' } % 2)
                assertEquals("$name（${copy.lang}）：** 标记必须成对", 0, text.windowed(2).count { it == "**" } % 2)
                assertEquals("$name（${copy.lang}）：反引号标记必须成对", 0, text.count { it == '`' } % 2)
                assertFalse("$name（${copy.lang}）：不许出现空路径标记", text.contains("``"))
            }
        }
    }

    /** 三语正文逐行块类型必须一致：译文只翻前缀后的正文，不许合并、拆分或改块类型。 */
    @Test
    fun `三语正文逐行块类型一致`() {
        val zh = copies[0]
        for (name in bodyKeys) {
            val zhLines = zh.propertyOf(name).split('\n').filter { it.isNotBlank() }
            copies.drop(1).forEach { copy ->
                val other = copy.propertyOf(name).split('\n').filter { it.isNotBlank() }
                assertEquals("$name（${copy.lang}）块数与 ZH 不一致", zhLines.size, other.size)
                zhLines.indices.forEach { index ->
                    assertEquals(
                        "$name（${copy.lang}）第 ${index + 1} 块类型与 ZH 不一致：${other[index].take(12)}",
                        parseTutorialLine(zhLines[index]).kind,
                        parseTutorialLine(other[index]).kind,
                    )
                }
            }
        }
    }

    /** 结构自洽：编号条目每页都从 1 连续、前缀块不空、游记建议正好七条。 */
    @Test
    fun `正文块结构自洽`() {
        copies.forEach { copy ->
            bodyKeys.forEach { name ->
                val blocks = copy.propertyOf(name).split('\n').filter { it.isNotBlank() }.map { parseTutorialLine(it) }
                assertTrue("$name（${copy.lang}）块数过少，页面会显得空", blocks.size >= 4)
                val numbers = blocks.filter { it.kind == TutorialLineKind.NUMBERED }.map { it.number.toInt() }
                assertEquals("$name（${copy.lang}）编号条目必须从 1 连续", (1..numbers.size).toList(), numbers)
                blocks.forEach { block ->
                    when (block.kind) {
                        TutorialLineKind.SUBHEAD -> assertTrue("$name（${copy.lang}）小标题为空", block.text.isNotBlank())
                        TutorialLineKind.BULLET -> assertTrue("$name（${copy.lang}）列表项为空", block.text.isNotBlank())
                        TutorialLineKind.DIVIDER -> assertTrue("$name（${copy.lang}）分隔线不该带正文", block.text.isEmpty())
                        else -> assertTrue("$name（${copy.lang}）正文块为空", block.text.isNotBlank())
                    }
                }
            }
            val tips = copy.tipsBody.split('\n').filter { it.isNotBlank() }.map { parseTutorialLine(it) }
            assertEquals(
                "${copy.lang} 游玩建议正好七条编号行",
                (1..7).toList(),
                tips.filter { it.kind == TutorialLineKind.NUMBERED }.map { it.number.toInt() },
            )
        }
    }

    private fun TutorialCopy.propertyOf(name: String): String =
        keys.getValue(name).get(this)
}
