package com.eratw.port.android

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玩家面 Mod 文案的内部术语哨兵（task-127，Lane EC copy audit）。
 *
 * 三张玩家面文案表（[SettingsStrings] / [ModImportCopy] / [ModUiCopy]）的值都直接上屏，所以
 * 内部标识符（字段名、产物扩展名、取证面异常类名、内部状态术语）一旦漏进文案，玩家就会看到
 * 看不懂的实现细节。这里用反射把三张表 × 三语的全部字符串值全量扫一遍，命中黑名单即红。
 *
 * 另外两条口径一并钉住：黑名单本身不许被掏空（否则哨兵变成空转）；ZH/JA 的「Mod」写法
 * 不许再漂回「模组」/ 全大写「MOD」（task-127 统一后的现状）。
 */
class ModCopySentinelTest {

    /** 不许出现在玩家面文案里的内部术语；比较时忽略大小写。 */
    private val internalTerms: List<String> = listOf(
        "packageId",
        "package id",
        "パッケージ id",
        ".erbm",
        ".assets",
        ".settings",
        ".sources",
        ".rt2",
        ".manifest",
        "manifest",
        "マニフェスト",
        "metrics/",
        "RT2MANIFEST",
        "Marker",
        "sidecar",
        "サイドカー",
        "侧车",
        "不变量",
        "invariant",
        "I8",
        "LinkOrderException",
        "装配",
    )

    @Test
    fun `玩家面三表三语都不含内部术语`() {
        val tables = listOf(SettingsStrings::class.java, ModImportCopy::class.java, ModUiCopy::class.java)
        val hits = tables.flatMap { table ->
            SettingsLanguage.entries.flatMap { lang -> internalTermHits(table, lang) }
        }
        assertTrue("玩家面文案漏了内部术语：\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `内部术语黑名单不许被掏空`() {
        assertTrue("黑名单至少 $MIN_TERMS 条", internalTerms.size >= MIN_TERMS)
        assertEquals("黑名单不许重复", internalTerms.size, internalTerms.distinct().size)
        assertTrue("黑名单不许有空项", internalTerms.none { it.isBlank() })
        val required = listOf("packageId", ".erbm", ".rt2", "Marker")
        assertTrue(
            "黑名单必须点名 packageId / .erbm / .rt2 / Marker 四类",
            required.all { needle -> internalTerms.contains(needle) },
        )
    }

    @Test
    fun `Mod 写法不漂：ZH 无模组、JA 无全大写 MOD`() {
        val tables = listOf(SettingsStrings::class.java, ModImportCopy::class.java, ModUiCopy::class.java)
        val zhHits = tables.flatMap { valuesOf(it, SettingsLanguage.ZH) }.filter { it.contains("模组") }
        assertTrue("ZH 玩家面统一写「Mod」，不许混用「模组」：$zhHits", zhHits.isEmpty())
        val jaHits = tables.flatMap { valuesOf(it, SettingsLanguage.JA) }.filter { it.contains("MOD") }
        assertTrue("JA 玩家面统一写「Mod」，不许全大写「MOD」：$jaHits", jaHits.isEmpty())
    }

    @Test
    fun `三表三语的占位符集合一致`() {
        val tables = listOf(SettingsStrings::class.java, ModImportCopy::class.java, ModUiCopy::class.java)
        val mismatches = tables.flatMap { table ->
            stringFields(table).mapNotNull { field ->
                val slots = SettingsLanguage.entries.associateWith { lang ->
                    slotsOf(field.get(copyOf(table, lang)) as String)
                }
                if (slots.values.toSet().size <= 1) {
                    null
                } else {
                    "${table.simpleName}.${field.name} 三语占位符不一致：$slots"
                }
            }
        }
        assertTrue("占位符三语不一致，运行期 fill 会少显示或抛异常：\n$mismatches", mismatches.isEmpty())
    }

    @Test
    fun `三表三语文案都非空`() {
        val tables = listOf(SettingsStrings::class.java, ModImportCopy::class.java, ModUiCopy::class.java)
        val blanks = tables.flatMap { table ->
            SettingsLanguage.entries.flatMap { lang ->
                val copy = copyOf(table, lang)
                stringFields(table)
                    .filter { field -> (field.get(copy) as String).isBlank() }
                    .map { field -> "${table.simpleName}.${field.name}[${lang.name}] 是空串" }
            }
        }
        assertTrue("玩家面文案不许空：$blanks", blanks.isEmpty())
    }

    /** 单张表单语命中内部术语的明细（表名.字段名[语言] + 命中的术语 + 原值）。 */
    private fun internalTermHits(table: Class<*>, lang: SettingsLanguage): List<String> {
        val copy = copyOf(table, lang)
        return stringFields(table).flatMap { field ->
            val text = field.get(copy) as String
            internalTerms
                .filter { term -> text.contains(term, ignoreCase = true) }
                .map { term -> "${table.simpleName}.${field.name}[${lang.name}] 含「$term」：$text" }
        }
    }

    private fun valuesOf(table: Class<*>, lang: SettingsLanguage): List<String> {
        val copy = copyOf(table, lang)
        return stringFields(table).map { field -> field.get(copy) as String }
    }

    private fun copyOf(table: Class<*>, lang: SettingsLanguage): Any = when (table) {
        SettingsStrings::class.java -> SettingsStrings.of(lang)
        ModImportCopy::class.java -> ModImportCopy.of(lang)
        ModUiCopy::class.java -> ModUiCopy.of(lang)
        else -> throw IllegalArgumentException("未知的玩家面文案表：$table")
    }

    private fun stringFields(table: Class<*>): List<Field> =
        table.declaredFields
            .filter { field -> !Modifier.isStatic(field.modifiers) && field.type == String::class.java }
            .onEach { field -> field.isAccessible = true }

    private fun slotsOf(text: String): Set<String> = SLOT.findAll(text).map { it.value }.toSet()

    private companion object {
        /** 黑名单下限：少于这个数就说明哨兵被掏空了。 */
        private const val MIN_TERMS = 15

        /** 文案占位符口径，与 `SettingsStringsTest` 的反射闸同形。 */
        private val SLOT = Regex("""\{\d+}""")
    }
}
