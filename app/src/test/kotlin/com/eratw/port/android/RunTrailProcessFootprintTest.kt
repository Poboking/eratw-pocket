package com.eratw.port.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RunTrail] 进程口径水位判式（2026-11-08 OOM-A 波，CONTRACT-OOM-A §3）。
 *
 * 钉三件事：
 * ① [RunTrail.memoryLimiterBudgetMiB] 的档位表**逐字**复刻 AOSP
 *    `/system/etc/memory-limiter-config.xml`（Android 17）标准平台档 —— 这是判据的全部
 *    依据（官方无运行时查询 API，见 KDoc），表抄错一个数，触防线就错一截；
 *    8 GB 档 notVisible = 3072（≠ 5120÷2）这类「不能推算」的数单钉。
 * ② 不适用区间（< 3200 / >= 18432）返回 null：MemoryLimiter 不管这些设备，判式必须恒 false，
 *    不能拿一个拍出来的数当预算。
 * ③ [RunTrail.shouldReclaimForProcessFootprint] 的 70% 边界与负值口径。
 *
 * 纯 JVM（同 `RunTrailTest` / `ErrorReportExitForensicsTest` 的做法）：全部标量进出，
 * 不碰 Android API；断言签名随 app 模块用 JUnit4（消息在前）。
 */
class RunTrailProcessFootprintTest {

    // ---- ① 档位表逐字（visible 列）----

    @Test
    fun `visible 档位表逐字复刻 AOSP 标准档`() {
        assertEquals("4 GB 档", 2048L, RunTrail.memoryLimiterBudgetMiB(4_000L, visible = true))
        assertEquals("6 GB 档", 4096L, RunTrail.memoryLimiterBudgetMiB(6_000L, visible = true))
        assertEquals("8 GB 档", 5120L, RunTrail.memoryLimiterBudgetMiB(8_000L, visible = true))
        assertEquals("12 GB 档", 8192L, RunTrail.memoryLimiterBudgetMiB(12_000L, visible = true))
        assertEquals("16 GB 档", 10240L, RunTrail.memoryLimiterBudgetMiB(16_000L, visible = true))
    }

    @Test
    fun `notVisible 档位表逐字复刻 AOSP 标准档`() {
        assertEquals("4 GB 档不可见", 1024L, RunTrail.memoryLimiterBudgetMiB(4_000L, visible = false))
        assertEquals("6 GB 档不可见", 2048L, RunTrail.memoryLimiterBudgetMiB(6_000L, visible = false))
        // 这一条是「不能推算」的钉：8 GB 档不可见 = 3072，不是 5120÷2 = 2560
        assertEquals("8 GB 档不可见（表原文，不许除二）", 3072L, RunTrail.memoryLimiterBudgetMiB(8_000L, visible = false))
        assertEquals("12 GB 档不可见", 4096L, RunTrail.memoryLimiterBudgetMiB(12_000L, visible = false))
        assertEquals("16 GB 档不可见", 5120L, RunTrail.memoryLimiterBudgetMiB(16_000L, visible = false))
    }

    // ---- ①b 区间边界 [floor, ceiling) ----

    @Test
    fun `档位区间边界按左闭右开分档`() {
        assertEquals(2048L, RunTrail.memoryLimiterBudgetMiB(3_200L, visible = true))
        assertEquals(2048L, RunTrail.memoryLimiterBudgetMiB(4_799L, visible = true))
        assertEquals(4096L, RunTrail.memoryLimiterBudgetMiB(4_800L, visible = true))
        assertEquals(10240L, RunTrail.memoryLimiterBudgetMiB(14_336L, visible = true))
        assertEquals(10240L, RunTrail.memoryLimiterBudgetMiB(18_431L, visible = true))
    }

    // ---- ② 不适用区间：null（判式恒 false）----

    @Test
    fun `MemoryLimiter 不适用区间返回 null`() {
        assertNull("< 3200：平台不施加限制", RunTrail.memoryLimiterBudgetMiB(3_199L, visible = true))
        assertNull(">= 18432：平台不施加限制", RunTrail.memoryLimiterBudgetMiB(18_432L, visible = true))
        assertNull("读数失败（0）不进档", RunTrail.memoryLimiterBudgetMiB(0L, visible = true))
        assertNull("读数失败（负）不进档", RunTrail.memoryLimiterBudgetMiB(-1L, visible = false))
    }

    // ---- ③ 判式 70% 边界 ----

    @Test
    fun `判式在预算的七成触发`() {
        assertEquals("触发线常量本身也是契约", 70L, RunTrail.PROCESS_FOOTPRINT_PERCENT)
        // 2048 × 70% = 1433.6 ⇒ 1433 不触发、1434 触发
        assertTrue("1434 触发", RunTrail.shouldReclaimForProcessFootprint(1_434L, 2_048L))
        assertFalse("1433 不触发", RunTrail.shouldReclaimForProcessFootprint(1_433L, 2_048L))
        // 整数档：4096 × 70% = 2867.2 ⇒ 2868 触发
        assertTrue("2868 触发", RunTrail.shouldReclaimForProcessFootprint(2_868L, 4_096L))
        assertFalse("2867 不触发", RunTrail.shouldReclaimForProcessFootprint(2_867L, 4_096L))
        // 10240 × 70% = 7168 整除，恰在线上触发
        assertTrue("恰在线上触发", RunTrail.shouldReclaimForProcessFootprint(7_168L, 10_240L))
        assertFalse("线下不触发", RunTrail.shouldReclaimForProcessFootprint(7_167L, 10_240L))
    }

    @Test
    fun `无预算或读数失败时判式恒 false`() {
        assertFalse("null = 不适用档，永不触发", RunTrail.shouldReclaimForProcessFootprint(1_016L, null))
        assertFalse("0 预算 = 无这一档", RunTrail.shouldReclaimForProcessFootprint(1_016L, 0L))
        assertFalse("PSS 读不到（负）宁可不收", RunTrail.shouldReclaimForProcessFootprint(-1L, 2_048L))
    }

    // ---- ④ 报告读数形状的最小复演（2026-11-07 报告 5783b90 的 PSS 读数）----

    @Test
    fun `报告读数复演_4GB档切后台PSS已顶格`() {
        // 报告里长会话 PSS ≈ 1016 MiB：4 GB 档前台 2048 的 49%（不触发），
        // 但不可见档 1024 的 99% —— 若在后台跑回合边界（本波不接，留测试证明档位差）即贴线
        assertFalse(
            "前台档不触发",
            RunTrail.shouldReclaimForProcessFootprint(1_016L, RunTrail.memoryLimiterBudgetMiB(4_000L, visible = true)),
        )
        assertTrue(
            "后台档贴线",
            RunTrail.shouldReclaimForProcessFootprint(1_016L, RunTrail.memoryLimiterBudgetMiB(4_000L, visible = false)),
        )
    }
}
