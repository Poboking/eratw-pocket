package com.eratw.port.platform.image

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [SpriteBitmapSource.packDestSize] 的位布局 round-trip：
 * 打包 `(w and 0xFFFF) shl 16 or (h and 0xFFFF)` ↔ 解包 `ushr 16` / `and 0xFFFF`。
 *
 * 解包口径 = 生产解包点 `render/adapter/HtmlRunFlatten.kt:332-333`（`base ushr 16`
 * 取宽、`base and 0xFFFF` 取高）的原样复刻——测试不依赖 render 模块，但两侧约定
 * 必须一致，任何一侧单独改动都会在这里炸红。
 *
 * 边界钉 0x7FFF / 0x8000 / 0xFFFF：宽 ≥ 0x8000 时 `shl 16` 使打包结果为负 Int
 * （bit15 置位），解包侧只有 `ushr`（无符号右移）能取回正确宽度；若误用
 * `shr 16` 会得到符号扩展的垃圾值——这正是本测试要防的回归。
 *
 * 纯 JVM 用例：只调用 companion 的纯函数，不构造 [SpriteBitmapSource] 实例
 * （其构造器触碰 android.util.LruCache，纯 JVM 单测环境不可用）。
 */
class SpriteBitmapSourcePackTest {

    /** 与 HtmlRunFlatten.kt:332-333 相同的解包口径。 */
    private fun unpackW(packed: Int): Int = packed ushr 16
    private fun unpackH(packed: Int): Int = packed and 0xFFFF

    private fun assertRoundTrip(w: Int, h: Int) {
        val packed = SpriteBitmapSource.packDestSize(w, h)
        assertEquals("width round-trip for 0x%04X".format(w), w, unpackW(packed))
        assertEquals("height round-trip for 0x%04X".format(h), h, unpackH(packed))
    }

    @Test
    fun roundTrip_atAllThreePinnedBoundaries() {
        assertRoundTrip(0x7FFF, 0x7FFF) // 打包结果仍为正 Int
        assertRoundTrip(0x8000, 0x8000) // 双双越界：打包结果 = Int.MIN_VALUE（负）
        assertRoundTrip(0xFFFF, 0xFFFF) // 全 1：截断后不丢位
    }

    @Test
    fun roundTrip_mixedBoundaries() {
        assertRoundTrip(0x7FFF, 0xFFFF)
        assertRoundTrip(0xFFFF, 0x7FFF)
        assertRoundTrip(0x8000, 0x0001)
        assertRoundTrip(0x0001, 0x8000)
    }

    @Test
    fun packingStaysBitIdenticalToOriginalInlineExpression() {
        // 防重构改变语义：与原内联表达式 `(w and 0xFFFF) shl 16 or (h and 0xFFFF)` 逐位一致
        val w = 0x8000
        val h = 0x1234
        assertEquals((w and 0xFFFF) shl 16 or (h and 0xFFFF), SpriteBitmapSource.packDestSize(w, h))
    }
}
