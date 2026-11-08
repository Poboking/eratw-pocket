package com.eratw.port.android

import kotlin.reflect.KProperty1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 三语玩家面文案表的**形状闸共享断言**（T-3：把 UpdateNotifyCopyTest / PortraitShellCopyTest /
 * ModImportCopyShapeTest 各自手写的那五条形状判据收成一份工具，各表的形状测试只留自己的
 * 显式键清单与特有对拍）。
 *
 * 判据与抽出来之前逐条同强度（谁抽谁核对，用例读数见各测试文件的交付消息）：
 * 1. [assertKeyCount] —— 三档是同一个 data class 的三个实例，键集合天然一致；这里钉
 *    **构造属性计数**（`toString` 逐个列构造属性，值里不许出现 `=`），谁加键不三档一起加就红；
 * 2. [assertNonEmptyAndDistinct] —— 每条非空、三档两两不同：防「先占坑后补翻」的空串 /
 *    直接复述中文的假翻译。正字法事实共用（如顿号、全角冒号）由 `zhJaMayShare` 点名豁免；
 * 3. [assertSlotsAndNewlinesAligned] —— 带读数的句子三档同形：`{n}` 槽位**序列**与换行数
 *    逐条对齐。EN/JA 语序可以挪读数实参的顺序吗？**不许**——`fill` 按下标灌数，槽位序列
 *    一变，同一组实参灌出来的句子语义就换了；
 * 4. [assertNoSlotResidue] —— 灌满读数后不留槽位：`fill` 单趟替换语义的自检（参数里的
 *    `{n}` 不二次展开，槽位漏灌才会残留）；
 * 5. [assertLanguageSelfEvident] —— EN 档一个汉字不许混进来；ZH 档每条都有汉字（逐字搬运
 *    的中文底稿；纯标点键按「非 ASCII」判）；JA 档每条都含假名或汉字（防漏翻成英文句子）。
 *
 * 逐字性（ZH 档 == 收口前的硬编码）不在这里：那是各表与既有定向用例的对拍职责，见各表
 * 测试文件自己的键清单与说明。
 */
internal object CopyShapeGate {

    /** 汉字范围（EN 档禁入、ZH 档底稿判据），与三份形状闸此前各自的写法同一区间。 */
    private val han = Regex("[一-鿿]")

    private val slot = Regex("[{](\\d+)[}]")

    /**
     * 三档条目数相等且与显式键清单对账。
     *
     * `keys` 允许是**点名子集**（如 UpdateNotifyCopyTest 只点名 U3b 追加的 54 条）——
     * 那时键清单与总数的关系由调用方自己的用例钉（子集大小、总数各归各）。
     *
     * @param expectedPerCopy 每档构造属性总数（含 `lang` 那一枚）：`toString` 里的 `=` 计数。
     *   表里的值不许带 `=` 字符（有就换一种计数法，别硬绕）。
     */
    fun <T : Any> assertKeyCount(
        copyName: String,
        copies: List<T>,
        keys: Map<String, KProperty1<T, String>>,
        expectedPerCopy: Int,
    ) {
        assertEquals(
            "$copyName 的键数变了：三档一起加，并同步测试的键清单与这里的 $expectedPerCopy",
            List(copies.size) { expectedPerCopy },
            copies.map { it.toString().count { ch -> ch == '=' } },
        )
    }

    /** 每条非空且三档两两不同（`zhJaMayShare` 点名的键豁免 ZH/JA 同文那条）。 */
    fun <T : Any> assertNonEmptyAndDistinct(
        copies: List<T>,
        keys: Map<String, KProperty1<T, String>>,
        zhJaMayShare: Set<String> = emptySet(),
    ) {
        for ((name, property) in keys) {
            val (zh, en, ja) = copies.map(property)
            assertTrue(
                "$name：三档都不许空串或纯空白（四份既有闸里最严的那档口径）",
                zh.isNotBlank() && en.isNotBlank() && ja.isNotBlank(),
            )
            assertTrue("$name：ZH 与 EN 不得同文", zh != en)
            if (name !in zhJaMayShare) {
                assertTrue("$name：ZH 与 JA 不得同文", zh != ja)
            }
            assertTrue("$name：EN 与 JA 不得同文", en != ja)
        }
    }

    /** 带读数的句子三档同形：`{n}` 槽位序列与换行数逐条对齐。 */
    fun <T : Any> assertSlotsAndNewlinesAligned(
        copies: List<T>,
        keys: Map<String, KProperty1<T, String>>,
    ) {
        for ((name, property) in keys) {
            val (zh, en, ja) = copies.map(property)
            val zhSlots = slot.findAll(zh).map { it.value }.toList()
            assertEquals("$name：EN 槽位序列与 ZH 不一致", zhSlots, slot.findAll(en).map { it.value }.toList())
            assertEquals("$name：JA 槽位序列与 ZH 不一致", zhSlots, slot.findAll(ja).map { it.value }.toList())
            val zhLines = zh.count { it == '\n' }
            assertEquals("$name：EN 换行数与 ZH 不一致", zhLines, en.count { it == '\n' })
            assertEquals("$name：JA 换行数与 ZH 不一致", zhLines, ja.count { it == '\n' })
        }
    }

    /**
     * 灌满读数后不留任何槽位。
     *
     * @param fill 各表自己的 `fill` 口：`(表实例, 模板, 读数个数) -> 渲染串`。
     */
    fun <T : Any> assertNoSlotResidue(
        copies: List<T>,
        keys: Map<String, KProperty1<T, String>>,
        fill: (T, String, Int) -> String,
    ) {
        for (copy in copies) {
            for ((name, property) in keys) {
                val template = property(copy)
                val n = slot.findAll(template).count()
                val rendered = fill(copy, template, n)
                assertFalse(
                    "$name（${copy.toString().substringBefore('(')}）灌满 $n 个读数后仍有槽位残留：$rendered",
                    slot.containsMatchIn(rendered),
                )
            }
        }
    }

    /**
     * 语言自证：EN 无汉字、ZH 每条有汉字（纯标点键判非 ASCII）、JA 每条有假名或汉字。
     *
     * [hanAllowed] 点名的键豁免「EN 不许有汉字」：盘上真实目录名（`resources/背景图/`、
     * `sound/游戏bgm/`、兜底地点 `自定义`）是物理事实，翻成英文路径反而是假话；
     * 这些字只出现在路径 / 目录名里，句子本身仍须是英文。
     */
    fun <T : Any> assertLanguageSelfEvident(
        copies: List<T>,
        keys: Map<String, KProperty1<T, String>>,
        zhJaMayShare: Set<String> = emptySet(),
        hanAllowed: Set<String> = emptySet(),
    ) {
        for ((name, property) in keys) {
            val (zh, en, ja) = copies.map(property)
            if (name !in hanAllowed) {
                assertFalse("$name：EN 档混进了汉字：$en", han.containsMatchIn(en))
            }
            if (name in zhJaMayShare) {
                // 顿号/全角冒号这类纯标点键没有汉字可言：非 ASCII（不是英文句子）即可。
                assertTrue("$name：ZH 档是纯 ASCII（标点键也该是中文写法）：$zh", zh.any { it.code > 0x7F })
            } else {
                assertTrue("$name：ZH 档一个汉字都没有（不像中文底稿）：$zh", han.containsMatchIn(zh))
            }
            assertTrue(
                "$name：JA 档全是 ASCII（疑似漏翻成英文）：$ja",
                ja.any { it.code > 0x7F },
            )
        }
    }
}
