package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OOM-B（2026-11-08）**厂商 MemoryLimiter 档位覆写校准**的定向钉子。
 *
 * 硬编码档位表（[RunTrail.memoryLimiterBudgetMiB] 的 KDoc 表格）只是设备上读不到配置时的
 * 回落值；本文件钉四件事：
 * 1. [MemoryLimiterConfig.parse] 只认平台口径 —— `<version>1</version>` + 同名子元素写法、
 *    五个数值字段全必需（AOSP android-17.0.0_r1 `MemoryLimiter.getConfiguration` 逐字段
 *    `longValue()`，缺一即整份配置非法）；
 * 2. 任一条件不满足 ⇒ **整表空**（不拿半张表当预算）；
 * 3. [MemoryLimiterConfig.select] 的判据 = 满足 `minimumRequiredMemTotal ≤ RAM` 的最大档，
 *    一档不满足 ⇒ null（MemoryLimiter 不适用，不是「按最小档」）；
 * 4. [MemoryLimiterConfig.readDevice] 的读取顺序 = vendor → system → null，
 *    [RunTrail.memoryLimiterBudgetMiB] 传入实表时以实表为准、传 null 时与旧硬表完全一致。
 *
 * 纯 JVM，断言签名随 app 模块用 JUnit4（消息在前）。
 */
class MemoryLimiterConfigTest {

    /** 一份配置文档；[body] 是 `limitSet` 序列，[version] 传 null 表示故意缺 `<version>`。 */
    private fun config(body: String, version: String? = "1"): String {
        val versionElement = if (version == null) "" else "<version>$version</version>"
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <MemoryLimiterConfig>
              $versionElement
              <configList>
                $body
              </configList>
            </MemoryLimiterConfig>
        """.trimIndent()
    }

    /** 一个完整的 `<limitSet>`；某字段传 null 表示故意缺这一项。 */
    private fun limitSet(
        minimumRequiredMemTotal: String? = "3200",
        memVisible: String? = "2048",
        memNotVisible: String? = "1024",
        swapVisible: String? = "1024",
        swapNotVisible: String? = "512",
    ): String {
        val fields: List<Pair<String, String?>> = listOf(
            "minimumRequiredMemTotal" to minimumRequiredMemTotal,
            "memVisible" to memVisible,
            "memNotVisible" to memNotVisible,
            "swapVisible" to swapVisible,
            "swapNotVisible" to swapNotVisible,
        )
        return fields.joinToString(separator = "", prefix = "<limitSet>", postfix = "</limitSet>") { (name, value) ->
            if (value == null) "" else "<$name>$value</$name>"
        }
    }

    /** 两个档的标准表（4 GB / 6 GB 档）。 */
    private fun twoTiers(): String = limitSet(minimumRequiredMemTotal = "3200") +
        limitSet(
            minimumRequiredMemTotal = "4800",
            memVisible = "4096",
            memNotVisible = "2048",
            swapVisible = "2048",
            swapNotVisible = "1024",
        )

    @Test
    fun `标准元素写法解析成档位表`() {
        val sets = MemoryLimiterConfig.parse(config(twoTiers()))
        assertEquals("两个 limitSet 都应读出", 2, sets.size)
        assertEquals(
            "第一档逐字",
            MemoryLimiterConfig.LimitSet(3200, 2048, 1024, 1024, 512),
            sets[0],
        )
        assertEquals(
            "第二档逐字",
            MemoryLimiterConfig.LimitSet(4800, 4096, 2048, 2048, 1024),
            sets[1],
        )
    }

    @Test
    fun `version 缺失或不为 1 时整表空`() {
        val body = limitSet()
        assertTrue(
            "缺 version ⇒ 平台整份拒绝",
            MemoryLimiterConfig.parse(config(body, version = null)).isEmpty(),
        )
        assertTrue(
            "version=2 ⇒ 平台整份拒绝",
            MemoryLimiterConfig.parse(config(body, version = "2")).isEmpty(),
        )
        assertTrue(
            "version 非数字 ⇒ 整份拒绝",
            MemoryLimiterConfig.parse(config(body, version = "v1")).isEmpty(),
        )
    }

    @Test
    fun `任一字段缺失或非法时整表空`() {
        assertTrue(
            "缺 swapVisible ⇒ 整表空（平台逐字段 longValue）",
            MemoryLimiterConfig.parse(config(limitSet(swapVisible = null))).isEmpty(),
        )
        assertTrue(
            "缺 minimumRequiredMemTotal ⇒ 整表空",
            MemoryLimiterConfig.parse(config(limitSet(minimumRequiredMemTotal = null))).isEmpty(),
        )
        assertTrue(
            "非数值 ⇒ 整表空",
            MemoryLimiterConfig.parse(config(limitSet(memVisible = "2048MiB"))).isEmpty(),
        )
        assertTrue(
            "负值 ⇒ 整表空",
            MemoryLimiterConfig.parse(config(limitSet(memNotVisible = "-1"))).isEmpty(),
        )
        assertTrue(
            "好档与坏档混排也整表空（不拿半张表）",
            MemoryLimiterConfig.parse(config(limitSet() + limitSet(swapNotVisible = null))).isEmpty(),
        )
        assertTrue("没有 limitSet ⇒ 空表", MemoryLimiterConfig.parse(config("")).isEmpty())
        assertTrue("非 XML ⇒ 空表", MemoryLimiterConfig.parse("not xml at all").isEmpty())
        assertTrue("空文本 ⇒ 空表", MemoryLimiterConfig.parse("").isEmpty())
    }

    @Test
    fun `选择规则取满足门槛的最大档`() {
        val sets = MemoryLimiterConfig.parse(config(twoTiers()))
        assertEquals(
            "4000 MiB 落在 [3200,4800) 档",
            3200L,
            MemoryLimiterConfig.select(4000, sets)?.minimumRequiredMemTotalMiB,
        )
        assertEquals(
            "5800 MiB 落在 [4800, ...) 档",
            4800L,
            MemoryLimiterConfig.select(5800, sets)?.minimumRequiredMemTotalMiB,
        )
        assertNull("低于表里最小门槛 ⇒ 功能关闭", MemoryLimiterConfig.select(3000, sets))
        assertNull("空表 ⇒ 功能关闭", MemoryLimiterConfig.select(5800, emptyList()))
    }

    @Test
    fun `设备读取顺序 vendor 优先 system 兜底 两路都无给 null`() {
        val vendorXml = config(twoTiers())
        val systemXml = config(limitSet(minimumRequiredMemTotal = "6144", memVisible = "5120", memNotVisible = "2560"))

        val onlySystem = MemoryLimiterConfig.readDevice { path ->
            if (path == MemoryLimiterConfig.SYSTEM_PATH) systemXml else null
        }
        assertEquals("vendor 读不到时用 system", MemoryLimiterConfig.Origin.SYSTEM, onlySystem?.origin)
        assertEquals("system 表内容", 6144L, onlySystem?.sets?.single()?.minimumRequiredMemTotalMiB)

        val both = MemoryLimiterConfig.readDevice { path ->
            when (path) {
                MemoryLimiterConfig.VENDOR_PATH -> vendorXml
                MemoryLimiterConfig.SYSTEM_PATH -> systemXml
                else -> null
            }
        }
        assertEquals("vendor 优先", MemoryLimiterConfig.Origin.VENDOR, both?.origin)
        assertEquals("vendor 表里两档", 2, both?.sets?.size)

        assertNull("两路都读不到 ⇒ null（调用方回落硬表）", MemoryLimiterConfig.readDevice { null })

        val vendorBroken = MemoryLimiterConfig.readDevice { path ->
            when (path) {
                MemoryLimiterConfig.VENDOR_PATH -> "<not-a-config/>"
                MemoryLimiterConfig.SYSTEM_PATH -> systemXml
                else -> null
            }
        }
        assertEquals(
            "vendor 解析不出条目时也退 system",
            MemoryLimiterConfig.Origin.SYSTEM,
            vendorBroken?.origin,
        )
    }

    @Test
    fun `实表预算以设备表为准 null 时回落旧硬表`() {
        val sets = listOf(
            MemoryLimiterConfig.LimitSet(3200, 2048, 1024, 1024, 512),
            MemoryLimiterConfig.LimitSet(4800, 3584, 1792, 2048, 1024),
        )
        assertEquals(
            "厂商档 memVisible=3584 必须压过硬表的 4096",
            3584L,
            RunTrail.memoryLimiterBudgetMiB(5800, visible = true, config = sets),
        )
        assertEquals(
            "不可见档取 memNotVisible",
            1792L,
            RunTrail.memoryLimiterBudgetMiB(5800, visible = false, config = sets),
        )
        assertNull(
            "RAM 低于设备表最小门槛 ⇒ null（功能关闭）",
            RunTrail.memoryLimiterBudgetMiB(3000, visible = true, config = sets),
        )
        assertEquals(
            "config=null 时与旧硬表完全一致",
            4096L,
            RunTrail.memoryLimiterBudgetMiB(5800, visible = true),
        )
        assertEquals("trace 行的 src= 取 Origin.label（vendor）", "vendor", MemoryLimiterConfig.Origin.VENDOR.label)
        assertEquals("trace 行的 src= 取 Origin.label（system）", "system", MemoryLimiterConfig.Origin.SYSTEM.label)
    }
}
