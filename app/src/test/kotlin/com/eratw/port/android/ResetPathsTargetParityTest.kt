package com.eratw.port.android

import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.SettingsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两条重置路径的**逐键对拍**（T6 后半，R1.1–R1.4）。
 *
 * 同一份出厂目标表分别喂给 `ResetPaths.allReset` 与 `ResetPaths.gameItemsOnly`，写后的
 * `GAME` 组快照必须逐键相同；出厂文件缺的键两边都落在 `ConfigSpec.defaultValue`；
 * assets 不可读的等价形状（[FactoryConfigDefaults.resetTargets] 返回空表）两边也都落在
 * 引擎缺省，且目标表本身不得含 `DEBUG` / `REPLACE` 组键。
 *
 * 真实 assets 的读取分支（`resetTargets(context)` 非空）在 JVM 单测里造不出 Context，
 * 由同一条 [FactoryConfigDefaults.parse] 解析链用夹具文本等价覆盖。
 */
class ResetPathsTargetParityTest {

    /** 与随包出厂 emuera.config 同键的夹具；缺 `WINDOW HEIGHT` / `LINE HEIGHT` / `USE MOUSE` 等键。 */
    private val factoryText = """
        WINDOW WIDTH:1800
        MAX HISTORY LOG LINES:900
        ITEMS PER LINE FOR PRINTC:5
        NUMBER OF ITEM CHARACTERS FOR PRINTC:25
        FONT NAME:黑体
        FONT SIZE:18
    """.trimIndent()

    private val gameSpecs = ConfigSpecTable.all.filter { it.group == ConfigGroup.GAME }

    /** 一条路径跑完后的 `GAME` 组快照（与另一条路径对拍的比较面）。 */
    private fun gameSnapshot(model: SettingsModel): Map<ConfigKey, ConfigValue> =
        model.store.snapshot().filterKeys { key -> ConfigSpecTable.specOf(key)?.group == ConfigGroup.GAME }

    @Test
    fun `同一份出厂目标表下两条路径逐键同值`() {
        val targets = FactoryConfigDefaults.parse(factoryText)
        val all = SettingsModel()
        val only = SettingsModel()
        ResetPaths.allReset(all, targets, {}, {})
        ResetPaths.gameItemsOnly(only, targets, { true })

        assertEquals("两条路径的 GAME 组快照必须逐键相等", gameSnapshot(all), gameSnapshot(only))
        for (spec in gameSpecs) {
            val expectedValue = targets[spec.key] ?: spec.defaultValue
            assertEquals("${spec.key} 顶栏取值不是出厂目标值", expectedValue, all.store.itemOf(spec.key).value)
            assertEquals("${spec.key} 底部取值不是出厂目标值", expectedValue, only.store.itemOf(spec.key).value)
        }
        // 出厂值与引擎缺省不同的区分键：两处都必须取出厂值（这正是改前顶栏 760 / 3 的反面）。
        assertEquals(1800, only.store.itemOf(ConfigKey.WindowX).int())
        assertEquals(5, only.store.itemOf(ConfigKey.PrintCPerLine).int())
        assertEquals(1800, all.store.itemOf(ConfigKey.WindowX).int())
        assertEquals(5, all.store.itemOf(ConfigKey.PrintCPerLine).int())
    }

    @Test
    fun `出厂文件缺的键两条路径都取引擎缺省`() {
        val targets = FactoryConfigDefaults.parse(factoryText)

        // 夹具没写这些键 ⇒ 目标表里它们等于 ConfigSpec.defaultValue（ConfigStore 以缺省初始化，
        // 解析链不给「缺席」另一个专有形状；resetTargets 也不额外预填默认值）。
        for (key in listOf(ConfigKey.WindowY, ConfigKey.LineHeight, ConfigKey.UseMouse)) {
            val spec = ConfigSpecTable.specOf(key)!!
            assertEquals("$key 缺键时目标值不是引擎缺省", spec.defaultValue, targets[key])
        }

        val all = SettingsModel()
        val only = SettingsModel()
        ResetPaths.allReset(all, targets, {}, {})
        ResetPaths.gameItemsOnly(only, targets, { true })

        assertEquals(gameSnapshot(all), gameSnapshot(only))
        for (spec in gameSpecs) {
            assertEquals(
                "${spec.key} 顶栏没按「目标值或引擎缺省」取值",
                targets[spec.key] ?: spec.defaultValue,
                all.store.itemOf(spec.key).value,
            )
            assertEquals(
                "${spec.key} 底部没按「目标值或引擎缺省」取值",
                targets[spec.key] ?: spec.defaultValue,
                only.store.itemOf(spec.key).value,
            )
        }
    }

    @Test
    fun `resetTargets 拿不到出厂文件时返回空表且两路径逐键回落引擎缺省`() {
        // assets 读不到 / context 为 null 的等价形状：不得返回部分表、不得抛异常。
        assertTrue(FactoryConfigDefaults.resetTargets(null).isEmpty())

        val all = SettingsModel()
        val only = SettingsModel()
        ResetPaths.allReset(all, FactoryConfigDefaults.resetTargets(null), {}, {})
        ResetPaths.gameItemsOnly(only, FactoryConfigDefaults.resetTargets(null), { true })

        assertEquals(gameSnapshot(all), gameSnapshot(only))
        for (spec in gameSpecs) {
            assertEquals(
                "${spec.key} 顶栏没回落到引擎缺省",
                spec.defaultValue,
                all.store.itemOf(spec.key).value,
            )
            assertEquals(
                "${spec.key} 底部没回落到引擎缺省",
                spec.defaultValue,
                only.store.itemOf(spec.key).value,
            )
        }
    }

    @Test
    fun `顶栏重建回填后的 GAME 子集与底部写盘后逐键相同`() {
        val targets = FactoryConfigDefaults.parse(factoryText)

        // 底部路径：进页模型上恢复并写盘。
        val bottom = SettingsModel()
        ResetPaths.gameItemsOnly(bottom, targets, { true })

        // 顶栏路径：第一实例 apply（不写盘）→ 寄存 → 模拟 recreate 后 load() 读回盘上玩家旧值的
        // 新实例 → 取走一次性载体回填。这正是 SettingsActivity.onCreateCore 的重建口径。
        val first = SettingsModel()
        ResetPaths.allReset(first, targets, {}, {})
        ResetPaths.stageGameReset(targets)

        val reloaded = SettingsModel()
        reloaded.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(900))
        reloaded.store.itemOf(ConfigKey.PrintCPerLine).assign(ConfigValue.IntValue(9))
        reloaded.markClean() // SettingsStore.load 成功后 markClean 的口径（SettingsStore.kt:100）
        ResetPaths.consumeStagedGameReset()?.let { staged -> FactoryConfigDefaults.apply(reloaded, staged) }

        assertEquals("顶栏重建回填后必须与底部写盘后逐键相同", gameSnapshot(bottom), gameSnapshot(reloaded))
        assertTrue("回填只进内存，重建后必须仍是未保存态（不写盘）", reloaded.dirty)
    }

    @Test
    fun `出厂目标表只含 GAME 组键`() {
        val targets = FactoryConfigDefaults.parse(factoryText)
        assertTrue("解析出的目标表为空，夹具和解析链脱节了", targets.isNotEmpty())
        targets.keys.forEach { key ->
            assertEquals("$key 不在 GAME 组，恢复默认不该写只读组", ConfigGroup.GAME, ConfigSpecTable.specOf(key)!!.group)
        }
        // 反向：DEBUG / REPLACE 组的键一个都不许混进来（EXPORTABLE 只读面，`_Replace.csv` 的来源）。
        val readOnlyKeys = ConfigSpecTable.all.filter { it.group != ConfigGroup.GAME }.map { it.key }.toSet()
        assertTrue("目标表混进了只读组键", targets.keys.none { it in readOnlyKeys })
    }
}
