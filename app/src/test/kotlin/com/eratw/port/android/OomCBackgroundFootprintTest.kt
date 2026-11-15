package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OOM-C 波（2026-11-09）：**后台不可见档**判式 + MemoryLimiter 读表诊断。
 *
 * 前半钉 [RunTrail.shouldReclaimForBackgroundFootprint]：预算必须取 notVisible 档，
 * 而不是把前台的 visible 档拿来做后台判断；70% 触发线、负值口径、8 GB 档 notVisible
 * 不许除二、设备实读表优先，逐条与 [RunTrailProcessFootprintTest] 的前台口径对拍。
 *
 * 后半钉 [MemoryLimiterConfig.readDeviceWithDiagnostics]：旧形状把「读不到」一律吞成
 * `src=estimate`，报告分不清「文件不存在 / SELinux 拒绝 / 解析失败」—— 这三笔正是
 * BOARD 51/52 行挂着的真机 TODO(verify)。诊断串只进报告，不改变选表结果。
 *
 * 纯 JVM，断言签名随 app 模块用 JUnit4（消息在前）。
 */
class OomCBackgroundFootprintTest {

    // ---- A：后台 notVisible 判式 ----

    @Test
    fun `4GB 档 notVisible 的 70 百分比边界`() {
        // 4 GB 档 notVisible = 1024 MiB ⇒ 触发线 716.8 MiB
        assertFalse("716 未到线", RunTrail.shouldReclaimForBackgroundFootprint(716L, 4_000L))
        assertTrue("717 到线", RunTrail.shouldReclaimForBackgroundFootprint(717L, 4_000L))
    }

    @Test
    fun `同一 PSS 前台不触发而后台触发`() {
        assertFalse("前台 2048 档 70% = 1433.6", RunTrail.shouldReclaimForProcessFootprint(1_000L, 2_048L))
        assertTrue("后台 1024 档 70% = 716.8", RunTrail.shouldReclaimForBackgroundFootprint(1_000L, 4_000L))
    }

    @Test
    fun `8GB 档 notVisible 是 3072 不许除二`() {
        // 若误把 visible 5120 除二成 2560 ⇒ 70% = 1792，2000 会误触发；正确档 3072 ⇒ 2150.4 不触发。
        assertFalse("3072 档 70% = 2150.4，2000 不该触发", RunTrail.shouldReclaimForBackgroundFootprint(2_000L, 8_000L))
        assertTrue("2160 已过线", RunTrail.shouldReclaimForBackgroundFootprint(2_160L, 8_000L))
    }

    @Test
    fun `读数失败与不适用区间恒不触发`() {
        assertFalse("PSS 读不到", RunTrail.shouldReclaimForBackgroundFootprint(-1L, 4_000L))
        assertFalse("RAM 读不到", RunTrail.shouldReclaimForBackgroundFootprint(1_000L, -1L))
        assertFalse("RAM 低于 3200 不适用", RunTrail.shouldReclaimForBackgroundFootprint(1_000L, 2_000L))
        assertFalse("RAM 不低于 18432 不适用", RunTrail.shouldReclaimForBackgroundFootprint(9_000L, 20_000L))
    }

    @Test
    fun `设备实读表优先于硬表`() {
        val sets = listOf(
            MemoryLimiterConfig.LimitSet(
                minimumRequiredMemTotalMiB = 3_200L,
                memVisibleMiB = 2_048L,
                memNotVisibleMiB = 4_096L,
                swapVisibleMiB = 512L,
                swapNotVisibleMiB = 256L,
            ),
        )
        // 实表 notVisible = 4096 ⇒ 800 远不到线；硬表 1024 档会误触发。
        assertFalse("实表优先", RunTrail.shouldReclaimForBackgroundFootprint(800L, 4_000L, sets))
        assertTrue("实表自己的线", RunTrail.shouldReclaimForBackgroundFootprint(2_900L, 4_000L, sets))
    }

    // ---- C：读表诊断 ----

    private val configXml: String = """
        <MemoryLimiterConfig><configList><version>1</version>
        <limitSet>
          <minimumRequiredMemTotal>3200</minimumRequiredMemTotal>
          <memVisible>2048</memVisible><memNotVisible>1024</memNotVisible>
          <swapVisible>512</swapVisible><swapNotVisible>256</swapNotVisible>
        </limitSet></configList></MemoryLimiterConfig>
    """.trimIndent()

    @Test
    fun `vendor 命中时诊断串记来源与档数`() {
        val (config, diag) = MemoryLimiterConfig.readDeviceWithDiagnostics { path ->
            if (path == MemoryLimiterConfig.VENDOR_PATH) configXml else null
        }
        assertEquals(MemoryLimiterConfig.Origin.VENDOR, config?.origin)
        assertEquals(1, config?.sets?.size)
        assertTrue(diag, diag.contains("vendor=ok:1档"))
        assertTrue("诊断串要跟配置一起进 DeviceConfig", config?.diagnostics == diag)
    }

    @Test
    fun `vendor 不存在时继续试 system 且两路都留痕`() {
        val (config, diag) = MemoryLimiterConfig.readDeviceWithDiagnostics { path ->
            if (path == MemoryLimiterConfig.SYSTEM_PATH) configXml else null
        }
        assertEquals(MemoryLimiterConfig.Origin.SYSTEM, config?.origin)
        assertTrue(diag, diag.startsWith("vendor=notFound|system=ok:1档"))
    }

    @Test
    fun `读取抛掷记 denied 且继续下一路`() {
        val (config, diag) = MemoryLimiterConfig.readDeviceWithDiagnostics { path ->
            if (path == MemoryLimiterConfig.VENDOR_PATH) {
                throw SecurityException("SELinux denied")
            } else {
                configXml
            }
        }
        assertEquals(MemoryLimiterConfig.Origin.SYSTEM, config?.origin)
        assertTrue(diag, diag.contains("vendor=denied:SecurityException"))
        assertTrue(diag, diag.contains("system=ok:1档"))
    }

    @Test
    fun `解析空表与两路全空分别留痕`() {
        val empty = MemoryLimiterConfig.readDeviceWithDiagnostics { "<bad/>" }
        assertNull(empty.first)
        assertEquals("vendor=parseEmpty|system=parseEmpty", empty.second)

        val absent = MemoryLimiterConfig.readDeviceWithDiagnostics { null }
        assertNull(absent.first)
        assertEquals("vendor=notFound|system=notFound", absent.second)
    }

    @Test
    fun `旧 readDevice 委托后结果与诊断版一致`() {
        val reader: (String) -> String? = { path ->
            if (path == MemoryLimiterConfig.SYSTEM_PATH) configXml else null
        }
        val old = MemoryLimiterConfig.readDevice(reader)
        val (config, _) = MemoryLimiterConfig.readDeviceWithDiagnostics(reader)
        assertEquals(config, old)
    }
}
