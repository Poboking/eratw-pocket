package com.eratw.port.android

import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.MemoryTier
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.platform.image.SpriteBitmapSource
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * 内存档位那一行读数的契约（2026-11-03 分档刀）：这一行是「放开档到底有没有在这台机上生效」
 * 唯一的取证面，所以它的**字段齐全**与**数字同源**必须有闸。
 *
 * 为什么值得单独立一道闸：本刀改的是四个数值闸，而真机 Origin OS 会丢掉应用 logcat
 * （`LoadTrace.summaryLines` 的 KDoc 在册），玩家报告里能不能看见档位全靠这一行。
 * 少一个字段、或者报告里的数与引擎实际用的数不同源（抄了一份），在这台机上就等于
 * 「出问题那台机永远读不到档位」——与本仓最怕的静默失效同型。
 *
 * 纯 JVM：`line()` 只碰属性、`Runtime.maxMemory()` 与四支现取函数，不碰任何需要真机的调用。
 */
class MemoryTierReadoutTest {

    private val mib = 1024L * 1024L

    @After
    fun clearProperty() {
        System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
    }

    @Test
    fun `档位那一行五个字段一个都不许少`() {
        val line = MemoryTierReadout.line()
        for (key in listOf("内存档位=", "像素预算=", "工作表缓存=", "GC阈值=", "预检系数=")) {
            assertTrue("缺了「$key」这一发读数，取证就没法逐项对账：$line", line.contains(key))
        }
    }

    @Test
    fun `没落性质时报告说的就是保守档那四个数`() {
        System.clearProperty(MemoryTier.SDK_INT_PROPERTY)
        val line = MemoryTierReadout.line()
        assertTrue("性质没落必须写保守：$line", line.contains("内存档位=保守"))
        // 三个与测试 JVM 挡位无关的绝对读数：抄错一个当场红。
        assertTrue("工作表缓存=192MiB：$line", line.contains("工作表缓存=192MiB"))
        assertTrue("GC阈值=55%：$line", line.contains("GC阈值=55%"))
        assertTrue("预检系数=80%：$line", line.contains("预检系数=80%"))
        // 像素预算按本 JVM 的堆算（÷4）：报告与引擎不许两张皮。
        assertTrue(
            "像素预算必须等于 GraphicsPool 现取的那个数：$line",
            line.contains("像素预算=${GraphicsPool.defaultPixelBudgetBytes() / mib}MiB"),
        )
    }

    @Test
    fun `档位词与四道闸现取的判定同源`() {
        // 落了性质但本机堆挡位够不够，由判式自己说；这一行只许跟着同一个判式走。
        System.setProperty(MemoryTier.SDK_INT_PROPERTY, "34")
        val generous = MemoryTier.isGenerous()
        val line = MemoryTierReadout.line()
        assertTrue(
            "档位词必须与 MemoryTier 现取判定一致（generous=$generous）：$line",
            line.contains(if (generous) "内存档位=放开" else "内存档位=保守"),
        )
        if (generous) {
            // 够挡位时三个绝对读数必须整体翻档 —— 这条就是「报告说放开而数还是保守档」那种形状。
            assertTrue("工作表缓存应翻到 384MiB：$line", line.contains("工作表缓存=384MiB"))
            assertTrue("GC阈值应翻到 65%：$line", line.contains("GC阈值=65%"))
            assertTrue("预检系数应翻到 70%：$line", line.contains("预检系数=70%"))
        }
    }

    @Test
    fun `读数的每个数都来自生产同一支函数而不是抄来的字面量`() {
        val line = MemoryTierReadout.line()
        assertTrue(line.contains("工作表缓存=${SpriteBitmapSource.defaultSheetCacheBytes() / mib}MiB"))
        assertTrue(line.contains("GC阈值=${LoadTrace.gcTriggerPercent()}%"))
        assertTrue(line.contains("预检系数=${ProgramLoader.artifactResidentPercent()}%"))
    }
}
