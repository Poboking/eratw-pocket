package com.eratw.port.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安卓出厂校准纯函数的契约（随包 `emuera.config` 的移动默认改写）。
 *
 * 钉的都是「校准只在认识的键上生效」这条底线：
 * 1. 命中键只改值，行序与其余行（注释/空行/未知键）逐字保留；
 * 2. 表里有而文件没有的键追加到文末；
 * 3. CRLF / BOM 逐字保留（ConfigFileStore 的 BOM 纪律）；
 * 4. 幂等：同表再跑一遍 = 原文（staging 重跑不会越改越偏）。
 */
class FactoryCalibrationTest {

    /** PC 语料随包那份的关键片段形状（按 4.980 语料根的行风格；含 :31 的 15000）。 */
    private val corpusLike = listOf(
        "　MUTYU Thou",
        "",
        "WINDOW WIDTH:1800",
        "WINDOW HEIGHT:973",
        "PRINTCを並べる数:5",
        "ITEMS PER LINE FOR PRINTC:5",
        "FONT SIZE:18",
        "LINE HEIGHT:18",
        "MILLISECONDS FOR INFINITE LOOP WARNING:15000",
        "; 注释行",
    ).joinToString("\n")

    @Test
    fun `命中键只改值其余行逐字保留`() {
        val (text, applied) = applyFactoryCalibration(
            corpusLike,
            mapOf("ITEMS PER LINE FOR PRINTC" to "4"),
        )
        assertEquals(
            corpusLike.replace("ITEMS PER LINE FOR PRINTC:5", "ITEMS PER LINE FOR PRINTC:4"),
            text,
        )
        assertEquals(listOf("ITEMS PER LINE FOR PRINTC=4"), applied)
    }

    @Test
    fun `幂等 同表再跑一遍原文不变`() {
        val (first, _) = applyFactoryCalibration(
            corpusLike,
            mapOf("ITEMS PER LINE FOR PRINTC" to "4"),
        )
        val (second, applied) = applyFactoryCalibration(
            first,
            mapOf("ITEMS PER LINE FOR PRINTC" to "4"),
        )
        assertEquals(first, second)
        assertEquals(listOf("ITEMS PER LINE FOR PRINTC=4"), applied)
    }

    @Test
    fun `表里有而文件没有的键追加到文末`() {
        val (text, applied) = applyFactoryCalibration(
            "WINDOW WIDTH:1800\n",
            mapOf(
                "WINDOW WIDTH" to "1800",
                "LINE HEIGHT" to "20",
            ),
        )
        assertEquals("WINDOW WIDTH:1800\nLINE HEIGHT:20\n", text)
        assertEquals(
            listOf("WINDOW WIDTH=1800", "LINE HEIGHT=20"),
            applied,
        )
    }

    @Test
    fun `键匹配不分大小写`() {
        val (text, applied) = applyFactoryCalibration(
            "items per line for printc:5\n",
            mapOf("ITEMS PER LINE FOR PRINTC" to "4"),
        )
        assertEquals("items per line for printc:4\n", text)
        assertEquals(listOf("ITEMS PER LINE FOR PRINTC=4"), applied)
    }

    @Test
    fun `CRLF 行尾逐行保留`() {
        val crlf = corpusLike.replace("\n", "\r\n")
        val (text, applied) = applyFactoryCalibration(
            crlf,
            mapOf("ITEMS PER LINE FOR PRINTC" to "4", "LINE HEIGHT" to "20"),
        )
        assertTrue("改写后的行尾仍要是 CRLF", text.contains("ITEMS PER LINE FOR PRINTC:4\r\n"))
        assertTrue("就地改写的行尾也按 CRLF", text.contains("LINE HEIGHT:20\r\n"))
        assertEquals(
            crlf
                .replace("ITEMS PER LINE FOR PRINTC:5", "ITEMS PER LINE FOR PRINTC:4")
                .replace("LINE HEIGHT:18", "LINE HEIGHT:20"),
            text,
        )
        assertEquals(
            listOf("ITEMS PER LINE FOR PRINTC=4", "LINE HEIGHT=20"),
            applied,
        )
    }

    @Test
    fun `BOM 原样保留`() {
        val bom = "\uFEFF" + corpusLike
        val (text, _) = applyFactoryCalibration(
            bom,
            mapOf("ITEMS PER LINE FOR PRINTC" to "4"),
        )
        assertTrue("BOM 必须还在文首", text.startsWith("\uFEFF"))
        assertEquals(
            bom.replace("ITEMS PER LINE FOR PRINTC:5", "ITEMS PER LINE FOR PRINTC:4"),
            text,
        )
    }

    @Test
    fun `空表是原样返回`() {
        val (text, applied) = applyFactoryCalibration(corpusLike, emptyMap())
        assertEquals(corpusLike, text)
        assertTrue(applied.isEmpty())
    }

    @Test
    fun `出厂表确实把 PRINTC 每行项目数校到 4`() {
        // 表就是群友征集结果的落点：这条钉住 PRINTC 条目，回填字高/行高时一并扩。
        assertTrue(ANDROID_FACTORY_CALIBRATION.isNotEmpty())
        assertEquals("4", ANDROID_FACTORY_CALIBRATION["ITEMS PER LINE FOR PRINTC"])
    }

    /**
     * 2026-11-12 根因钉：4.980 语料 `emuera.config:31` 显式写着 15000，平台覆盖层
     * 「只填空、不改写」永远够不到它（线上实际阈值因此是 15 s）。构建期校准必须把随包
     * 资产改成 360000，新装/重装才真正生效。
     */
    @Test
    fun `出厂表把语料显式的 15000 校到 360000`() {
        assertEquals(
            "阈值随包校准 = 6 分钟",
            "360000",
            ANDROID_FACTORY_CALIBRATION["MILLISECONDS FOR INFINITE LOOP WARNING"],
        )
        val (text, applied) = applyFactoryCalibration(corpusLike, ANDROID_FACTORY_CALIBRATION)
        assertEquals(
            corpusLike
                .replace("ITEMS PER LINE FOR PRINTC:5", "ITEMS PER LINE FOR PRINTC:4")
                .replace(
                    "MILLISECONDS FOR INFINITE LOOP WARNING:15000",
                    "MILLISECONDS FOR INFINITE LOOP WARNING:360000",
                ),
            text,
        )
        assertEquals(
            listOf(
                "ITEMS PER LINE FOR PRINTC=4",
                "MILLISECONDS FOR INFINITE LOOP WARNING=360000",
            ),
            applied,
        )
    }
}
