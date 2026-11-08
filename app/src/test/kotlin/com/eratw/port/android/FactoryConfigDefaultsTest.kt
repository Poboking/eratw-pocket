package com.eratw.port.android

import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.SettingsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「恢复默认设置」的目标值钉子：必须是**本移植版随包出厂 emuera.config**，
 * 不是 `ConfigSpec.defaultValue` 那套上游 Emuera 裸缺省。
 *
 * 夹具取随包文件里有代表性的几行（PRINTC 5 / 单元宽 25 / 900 行日志 / 字号 18 / 黑体），
 * 这几项恰好都能把「引擎缺省」与「移植版出厂值」区分开：引擎缺省是
 * PRINTC 3、760 宽窗口、500 行日志、ＭＳ ゴシック。
 */
class FactoryConfigDefaultsTest {

    private val factoryText = """
        IGNORE CASE:YES
        MAX HISTORY LOG LINES:900
        ITEMS PER LINE FOR PRINTC:5
        NUMBER OF ITEM CHARACTERS FOR PRINTC:25
        FONT NAME:黑体
        FONT SIZE:18
    """.trimIndent()

    private val gameCount = ConfigSpecTable.all.count { it.group == ConfigGroup.GAME }

    @Test
    fun `解析出厂文本后应用得到的是移植版出厂值而不是引擎缺省`() {
        val model = SettingsModel()
        val outcome = FactoryConfigDefaults.apply(model, FactoryConfigDefaults.parse(factoryText))

        assertEquals(5, model.store.itemOf(ConfigKey.PrintCPerLine).int())
        assertEquals(25, model.store.itemOf(ConfigKey.PrintCLength).int())
        assertEquals(900, model.store.itemOf(ConfigKey.MaxLog).int())
        assertEquals(18, model.store.itemOf(ConfigKey.FontSize).int())
        assertEquals(0, outcome.pinned)
        assertEquals(gameCount, outcome.written)
    }

    @Test
    fun `出厂文件缺席的键回落引擎缺省而不是留在玩家改过的值上`() {
        val model = SettingsModel()
        // 先模拟玩家把 PRINTC 改到 9，再拿空目标表恢复：必须回到引擎缺省 3。
        model.store.itemOf(ConfigKey.PrintCPerLine).assign(ConfigValue.IntValue(9))
        FactoryConfigDefaults.apply(model, emptyMap())

        for (spec in ConfigSpecTable.all) {
            if (spec.group != ConfigGroup.GAME) continue
            assertEquals(
                "${spec.key} 没回落到引擎缺省",
                spec.defaultValue,
                model.store.itemOf(spec.key).value,
            )
        }
    }

    @Test
    fun `钉住的条目不强解钉但如实计数`() {
        val model = SettingsModel()
        // ConfigItem.fixed 是 core 模块内部口，app 测试看不见；改走生产同一条
        // _fixed.config 装载链把这一项钉住（EmueraConfigParser 的 fix = true）。
        EmueraConfigParser.loadGameFile(
            ConfigFile("fixed.config", listOf("ITEMS PER LINE FOR PRINTC:9")),
            model.store,
            fix = true,
        )
        val pinned = model.store.itemOf(ConfigKey.PrintCPerLine)
        val before = pinned.value

        val outcome = FactoryConfigDefaults.apply(model, FactoryConfigDefaults.parse(factoryText))

        assertEquals(1, outcome.pinned)
        assertEquals(gameCount - 1, outcome.written)
        assertEquals("fixed.config 钉住的条目一个字节都不改", before, pinned.value)
        assertEquals("其余条目照常吃出厂值", 900, model.store.itemOf(ConfigKey.MaxLog).int())
    }

    @Test
    fun `BOM 注释未知键与 CRLF 都不影响解析`() {
        val text = "\uFEFF; 移植版出厂配置\r\nUNKNOWN KEY:7\r\nITEMS PER LINE FOR PRINTC:5\r\n"
        val model = SettingsModel()
        FactoryConfigDefaults.apply(model, FactoryConfigDefaults.parse(text))
        assertEquals(5, model.store.itemOf(ConfigKey.PrintCPerLine).int())
    }

    @Test
    fun `只收 GAME 组且改动能被写盘链路认出来`() {
        val targets = FactoryConfigDefaults.parse(factoryText)
        assertTrue(targets.keys.all { ConfigSpecTable.specOf(it)?.group == ConfigGroup.GAME })

        val model = SettingsModel()
        FactoryConfigDefaults.apply(model, targets)
        assertTrue(
            "直接 assign 也必须进 changedKeys，否则 SettingsStore.save 会写回旧值",
            ConfigKey.PrintCPerLine in model.changedKeys(),
        )
    }
}
