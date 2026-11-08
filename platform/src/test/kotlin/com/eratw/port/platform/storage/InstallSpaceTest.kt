package com.eratw.port.platform.storage

import com.eratw.port.platform.storage.GameAssetInstaller.Companion.requiredWithMargin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首启空间预检里**纯函数**那一半的钉子（[GameAssetInstaller.requiredWithMargin]）。
 *
 * 为什么不测 `freeBytes` / `hasSpaceFor`：它们要读真实文件系统的容量，平台层单测跑的是
 * android.jar 桩（`isReturnDefaultValues = true`，文件系统读数不可信），
 * 读数不可信；这两条留给「解包前拒绝安装」的接线验收在真机上取证。这里只钉可判定的算术：
 * 下限分支、5% 分支、切换点、饱和、负数箝位。
 *
 * 数值口径（全部 Integer 除法，与实现逐字同序）：
 * - 余量 = max(64 MiB, required / 20)；
 * - 切换点 = 64 MiB × 20 = 1.25 GiB（此后 5% 才追上下限）。
 */
class InstallSpaceTest {

    private companion object {
        /** 1 MiB 的字节数（不用移位：const 初始化器只认算术运算）。 */
        const val MIB = 1024L * 1024L

        /** 余量下限 64 MiB，与 [GameAssetInstaller.MARGIN_FLOOR_BYTES] 同值（常量本身另有一条断言）。 */
        const val FLOOR = 64L * MIB

        /** 两分支切换点：required / 20 恰好等于下限。 */
        const val SWITCH = FLOOR * 20L
    }

    /** 公开常量的值与「64 MiB」这个契约数字一致（app 侧展示文案若引用它，不能悄悄变）。 */
    @Test
    fun margin_floor_is_64_mib() {
        assertEquals("余量下限必须是 64 MiB", 67_108_864L, GameAssetInstaller.MARGIN_FLOOR_BYTES)
        assertEquals("测试本地口径与实现常量同源", FLOOR, GameAssetInstaller.MARGIN_FLOOR_BYTES)
    }

    /** required = 0：仍要拿满下限余量（结果 = 64 MiB，不是 0 —— 0 会让 hasSpaceFor 恒真）。 */
    @Test
    fun zero_required_yields_floor() {
        assertEquals("需求 0 的结果应为余量本身", FLOOR, requiredWithMargin(0L))
    }

    /** 小值走 64 MiB 下限：1 字节、10 MiB、恰好 64 MiB 都还在下限分支。 */
    @Test
    fun small_value_uses_floor() {
        assertEquals("1 字节也拿满下限", FLOOR + 1L, requiredWithMargin(1L))
        assertEquals("10 MiB 的 5% 远小于下限", FLOOR + 10L * MIB, requiredWithMargin(10L * MIB))
        assertEquals("required = 64 MiB 时余量仍是下限", 2L * FLOOR, requiredWithMargin(FLOOR))
    }

    /** 大值走 5%：余量恰为 required / 20，且已高于下限（必须越过 1.25 GiB 切换点）。 */
    @Test
    fun large_value_uses_five_percent() {
        val twoGiB = 2048L * MIB
        val giBResult = requiredWithMargin(twoGiB)
        assertEquals("2 GiB 的余量应是 5%", twoGiB / 20L, giBResult - twoGiB)
        assertTrue("2 GiB 的 5% 已超过 64 MiB 下限", giBResult - twoGiB > FLOOR)

        val fourGiB = 4096L * MIB
        assertEquals("4 GiB 的余量应是 5%", fourGiB / 20L, requiredWithMargin(fourGiB) - fourGiB)
    }

    /** 切换点附近：两侧各取一点，钉住「下限」与「5%」谁赢。 */
    @Test
    fun switch_point_between_floor_and_percent() {
        val below = SWITCH - 20L
        assertEquals("切换点之下：5% 仍低于下限", below + FLOOR, requiredWithMargin(below))
        assertTrue("前提：below / 20 < 下限", below / 20L < FLOOR)

        assertEquals("切换点：5% 恰等于下限，两分支同值", SWITCH + FLOOR, requiredWithMargin(SWITCH))

        val above = SWITCH + 20L
        assertEquals("切换点之上：余量改按 5%", above + above / 20L, requiredWithMargin(above))
        assertTrue("前提：above / 20 > 下限", above / 20L > FLOOR)
    }

    /** 接近 Long.MAX_VALUE：饱和到 MAX，不回绕成负数（负数会让 hasSpaceFor 静默放行）。 */
    @Test
    fun saturates_near_long_max_value() {
        assertEquals("MAX 本身饱和", Long.MAX_VALUE, requiredWithMargin(Long.MAX_VALUE))
        assertEquals("MAX - 1 也会溢出", Long.MAX_VALUE, requiredWithMargin(Long.MAX_VALUE - 1L))
        assertEquals(
            "MAX - 下限 也溢出（余量取 64 MiB 时相加越界）",
            Long.MAX_VALUE,
            requiredWithMargin(Long.MAX_VALUE - FLOOR),
        )
        assertTrue("饱和后必须是正数", requiredWithMargin(Long.MAX_VALUE - FLOOR) > 0L)

        // 极大但尚未溢出的对照点：仍按 5% 走，说明饱和只发生在真溢出时。
        val half = Long.MAX_VALUE / 2L
        assertEquals("未溢出时不得提前饱和", half + half / 20L, requiredWithMargin(half))
    }

    /** 负数 required 按 0 箝位：返回余量本身，绝不会给出负的预留量。 */
    @Test
    fun negative_required_clamps_to_zero() {
        assertEquals("required = -1 视同 0", FLOOR, requiredWithMargin(-1L))
        assertEquals("Long.MIN_VALUE 视同 0", FLOOR, requiredWithMargin(Long.MIN_VALUE))
        assertTrue("结果恒为正", requiredWithMargin(Long.MIN_VALUE) > 0L)
    }

    /** 单调不减：更大的需求不会算出更小的预留量（饱和段是平台）。点位必须升序。 */
    @Test
    fun required_with_margin_is_monotonic() {
        val points = listOf(
            Long.MIN_VALUE,
            -1L,
            0L,
            1L,
            FLOOR,
            1024L * MIB,
            SWITCH,
            4096L * MIB,
            Long.MAX_VALUE / 2L,
            Long.MAX_VALUE,
        )
        var previous = Long.MIN_VALUE
        for (point in points) {
            val value = requiredWithMargin(point)
            assertTrue("required=$point 的结果 $value 不得小于上一个 $previous", value >= previous)
            previous = value
        }
    }
}
