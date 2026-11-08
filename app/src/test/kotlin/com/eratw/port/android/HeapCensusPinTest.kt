package com.eratw.port.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HeapCensusPin] 定向单测（L5 heap-census，app 侧纯判式，口径同 [NativeTrimPlanTest]）。
 *
 * 钉的是「Java 堆分项轨迹什么时候该钉一发」的判式：
 * - 水位 < 90% 不钉；≥ 90% 才钉；
 * - 首次（sinceLast 负）必钉；冷却内不重复钉；越冷却再钉；
 * - 堆上限读不到（maxMiB ≤ 0，桌面装置）一律不钉（没有可信分母）。
 *
 * 纯 JVM：只做整数比较，不碰 Android 运行时、不碰会话。
 */
class HeapCensusPinTest {

    private val near = HeapCensusPin.NEAR_TOP_PERCENT // 90

    @Test
    fun `阈值边界_低于九成不钉_达到九成才钉`() {
        // 512 MiB 挡：90% = 460.8 ⇒ 460 不钉、461 钉。
        assertTrue(461L * 100L >= 512L * near)
        assertFalse(HeapCensusPin.shouldPin(usedMiB = 460L, maxMiB = 512L, sinceLastPinMs = -1L))
        assertTrue(HeapCensusPin.shouldPin(usedMiB = 461L, maxMiB = 512L, sinceLastPinMs = -1L))
        assertFalse(HeapCensusPin.shouldPin(usedMiB = 400L, maxMiB = 512L, sinceLastPinMs = -1L))
    }

    @Test
    fun `首发必钉_冷却内不重复_越冷却再钉`() {
        // 已在贴顶：sinceLast 负（没钉过）⇒ 钉。
        assertTrue(HeapCensusPin.shouldPin(500L, 512L, sinceLastPinMs = -1L))
        // 冷却未过（29 999 ms）⇒ 不重复灌窗口。
        assertFalse(HeapCensusPin.shouldPin(500L, 512L, sinceLastPinMs = HeapCensusPin.PIN_COOLDOWN_MS - 1))
        // 恰好越过冷却线 ⇒ 再钉。
        assertTrue(HeapCensusPin.shouldPin(500L, 512L, sinceLastPinMs = HeapCensusPin.PIN_COOLDOWN_MS))
    }

    @Test
    fun `堆上限不可信时一律不钉`() {
        assertFalse(HeapCensusPin.shouldPin(usedMiB = 900L, maxMiB = 0L, sinceLastPinMs = -1L))
        assertFalse(HeapCensusPin.shouldPin(usedMiB = 900L, maxMiB = -1L, sinceLastPinMs = -1L))
    }

    @Test
    fun `阈值数值与 KDoc 声明一致`() {
        assertTrue(
            "贴顶阈值必须是 90（判式建在别处会静默漏钉）",
            near == 90L,
        )
        assertTrue(
            "冷却必须与 30 s 那批钉发同量级（见 RunTrail.RELEASE_COOLDOWN_MS）",
            HeapCensusPin.PIN_COOLDOWN_MS == 30_000L,
        )
    }
}
