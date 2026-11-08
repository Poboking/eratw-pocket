package com.eratw.port.android

import com.eratw.port.core.config.ConfigFile
import com.eratw.port.core.config.ConfigGroup
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EmueraConfigParser
import com.eratw.port.core.config.SettingsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 两条重置路径的**副作用边界**钉子（T6 前半）。
 *
 *   * 顶栏「全部重置」：写条目 → 清偏好 → 置一次性重启标记（顺序硬约束），并且**不写盘**；
 *   * 底部「恢复默认设置…」：写条目 → 写盘；写盘失败回滚进页基线，偏好 / 标记侧零可达。
 *
 * 目标值用 `FactoryConfigDefaults.parse` 的同一条解析链喂夹具文本（真实 assets 读取需要
 * Context，JVM 单测不造）；两条路径逐键对拍在 [ResetPathsTargetParityTest] 里。
 */
class ResetPathsTest {

    /** 与随包出厂 emuera.config 同键的夹具；刻意缺若干键，留给对拍文件钉「缺键回落」。 */
    private val factoryText = """
        WINDOW WIDTH:1800
        WINDOW HEIGHT:973
        MAX HISTORY LOG LINES:900
        ITEMS PER LINE FOR PRINTC:5
        NUMBER OF ITEM CHARACTERS FOR PRINTC:25
        FONT NAME:黑体
        FONT SIZE:18
    """.trimIndent()

    private val gameCount = ConfigSpecTable.all.count { it.group == ConfigGroup.GAME }

    @Test
    fun `全部重置的相位是写条目 清偏好 置标记且顺序不可换`() {
        val model = SettingsModel()
        // 先摆一个玩家改过的值：两个回调里都要求条目已是出厂值 —— 顺序一旦反了就能抓到。
        model.store.itemOf(ConfigKey.PrintCPerLine).assign(ConfigValue.IntValue(9))
        val events = mutableListOf<String>()

        val outcome = ResetPaths.allReset(
            model = model,
            targets = FactoryConfigDefaults.parse(factoryText),
            resetPrefs = {
                assertEquals("清偏好时条目还没写回：相位顺序反了", 5, model.store.itemOf(ConfigKey.PrintCPerLine).int())
                events += "resetPrefs"
            },
            markRestartPending = {
                assertEquals("置标记时条目还没写回：相位顺序反了", 5, model.store.itemOf(ConfigKey.PrintCPerLine).int())
                events += "markRestartPending"
            },
        )

        assertEquals(listOf("resetPrefs", "markRestartPending"), events)
        assertEquals("出厂值优先于引擎缺省", 1800, model.store.itemOf(ConfigKey.WindowX).int())
        assertEquals(gameCount, outcome.written)
        assertEquals(0, outcome.pinned)
    }

    @Test
    fun `全部重置不写盘 模型保持脏等玩家点保存`() {
        val model = SettingsModel()
        model.store.itemOf(ConfigKey.PrintCPerLine).assign(ConfigValue.IntValue(9))
        ResetPaths.allReset(model, FactoryConfigDefaults.parse(factoryText), {}, {})

        // 没有 store.save / markClean 参与 ⇒ 改动仍可被 changedKeys 认出来，
        // 也就是说「游戏配置尚未写盘」是模型上看得见的事实，不是文案的说法。
        assertTrue("全部重置后模型必须是脏的（未写盘）", model.dirty)
        assertTrue(
            "写了出厂值却没进 changedKeys，保存链路会漏写",
            ConfigKey.PrintCPerLine in model.changedKeys(),
        )
        assertTrue(ConfigKey.WindowX in model.changedKeys())
    }

    @Test
    fun `全部重置返回的计数与 apply 同值且钉住项只计数`() {
        // 同一份 fixed.config 状态喂两份模型，一份直接 apply 作为基准，一份走 allReset。
        fun pinnedModel(): SettingsModel = SettingsModel().also { model ->
            EmueraConfigParser.loadGameFile(
                ConfigFile("fixed.config", listOf("ITEMS PER LINE FOR PRINTC:9")),
                model.store,
                fix = true,
            )
        }

        val targets = FactoryConfigDefaults.parse(factoryText)
        val baselineModel = pinnedModel()
        val expected = FactoryConfigDefaults.apply(baselineModel, targets)
        val pinnedBefore = baselineModel.store.itemOf(ConfigKey.PrintCPerLine).value

        val model = pinnedModel()
        val actual = ResetPaths.allReset(model, targets, {}, {})

        assertEquals("allReset 的 written 必须原样来自 apply", expected.written, actual.written)
        assertEquals("allReset 的 pinned 必须原样来自 apply", expected.pinned, actual.pinned)
        assertEquals(1, actual.pinned)
        assertEquals(gameCount - 1, actual.written)
        assertEquals("fixed.config 钉住的条目一个字节都不改", pinnedBefore, model.store.itemOf(ConfigKey.PrintCPerLine).value)
    }

    @Test
    fun `清偏好抛异常时不置标记且异常向上抛`() {
        val model = SettingsModel()
        val boom = IllegalStateException("清偏好失败")
        var marked = 0
        try {
            ResetPaths.allReset(
                model,
                FactoryConfigDefaults.parse(factoryText),
                { throw boom },
                { marked++ },
            )
            fail("异常必须向上传播，不许静默吞")
        } catch (expected: IllegalStateException) {
            assertSame(boom, expected)
        }
        assertEquals("清偏好抛错后不得继续置一次性标记", 0, marked)
    }

    @Test
    fun `恢复默认设置成功时写盘恰好一次且不碰偏好`() {
        val model = SettingsModel()
        model.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(900))
        var writeCalls = 0

        val outcome = ResetPaths.gameItemsOnly(
            model,
            FactoryConfigDefaults.parse(factoryText),
            writeDisk = {
                writeCalls++
                // 生产里这是 SettingsStore.save：成功后自己 markClean（SettingsStore.kt:115-118）
                model.markClean()
                true
            },
        )

        assertEquals(1, writeCalls)
        assertTrue(outcome.diskWritten)
        assertEquals(1800, model.store.itemOf(ConfigKey.WindowX).int())
        assertEquals(emptyList<ConfigKey>(), model.changedKeys())
    }

    @Test
    fun `恢复默认设置写盘失败时回滚进页基线且不动偏好`() {
        val model = SettingsModel()
        // 进页基线 = 玩家现值：SettingsStore.load 之后 markClean 的口径。
        model.store.itemOf(ConfigKey.WindowX).assign(ConfigValue.IntValue(900))
        model.store.itemOf(ConfigKey.PrintCPerLine).assign(ConfigValue.IntValue(9))
        model.markClean()
        val baseline = model.store.snapshot()

        var writeCalls = 0
        val outcome = ResetPaths.gameItemsOnly(
            model,
            FactoryConfigDefaults.parse(factoryText),
            writeDisk = {
                writeCalls++
                false
            },
        )

        assertEquals(1, writeCalls)
        assertFalse(outcome.diskWritten)
        // 逐键回滚：不只是「大概恢复」，每个 GAME 键都必须等于基线值。
        for (spec in ConfigSpecTable.all) {
            if (spec.group != ConfigGroup.GAME) continue
            assertEquals(
                "${spec.key} 没回滚到进页基线",
                baseline[spec.key],
                model.store.itemOf(spec.key).value,
            )
        }
        assertEquals(900, model.store.itemOf(ConfigKey.WindowX).int())
        assertEquals(9, model.store.itemOf(ConfigKey.PrintCPerLine).int())
        assertEquals("回滚后不得留下假脏标记", emptyList<ConfigKey>(), model.changedKeys())
    }

    @Test
    fun `顶栏目标表的一次性载体取走即清空`() {
        val targets = FactoryConfigDefaults.parse(factoryText)
        ResetPaths.stageGameReset(targets)

        assertSame("stage 与 consume 必须交接同一份实体", targets, ResetPaths.consumeStagedGameReset())
        assertNull("第二次取走必须是 null（一次性，不许跨重建重复回填）", ResetPaths.consumeStagedGameReset())
    }

    @Test
    fun `恢复默认设置的入口不接收任何偏好回调`() {
        // R3.1 的签名面钉子：gameItemsOnly 只有写盘这一个函数参数，
        // 偏好 / 重启标记在类型层面不可达（Kotlin 的 internal 函数名带模块后缀，按名截断匹配）。
        val entry = ResetPaths::class.java.declaredMethods
            .first { it.name.substringBefore('$') == "gameItemsOnly" }
        assertEquals("入口参数只有 模型 / 目标表 / 写盘回调", 3, entry.parameterCount)
        assertEquals(
            "除写盘回调外不得再有偏好或标记回调",
            1,
            entry.parameterTypes.count { it.name.startsWith("kotlin.jvm.functions.Function") },
        )
    }
}
