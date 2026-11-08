package com.eratw.port.platform.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpriteBitmapSource] 区域解码兜底那一刀的**纯判式**（lane eng-forced，任务 D5）。
 *
 * ## 为什么要单独钉这条判式
 *
 * 生产里的 `shouldRegionDecode` 只在「整张解不出来」的失败支上被问一次，而那条支在任何
 * 桌面环境都进不去（`Bitmap.createBitmap` / `BitmapRegionDecoder` 是 android.jar 桩）。
 * 于是 96 MiB 这条线过去**只靠人读**：`>` 写成 `>=`（多送一张整表进整张解码）、
 * 丢掉「有没有区域源」那一步（把 G_ID 工作表也送进区域解码）、或者把 Long 乘改回 Int
 * （被改坏的表头绕成小数）—— 三种都编译得过、桌面全绿。
 * 本用例盯的就是这三处。
 *
 * 阈值 96 MiB 与语料读数的出处见 `SpriteBitmapSource.REGION_DECODE_THRESHOLD_BYTES` 的 KDoc
 * （4.981 里唯一越线的 CSV 父图是那张 6964x6191 = 164.4 MiB 的布娃娃拼图）。
 *
 * 全程只做算术：不分配 96 MiB 的 `IntArray`，也不碰任何 Bitmap。
 */
class SpriteBitmapSourceRegionDecodeTest {

    private val mib = 1024L * 1024L

    @Test
    fun `阈值就是 96 MiB`() {
        assertEquals(96L * mib, SpriteBitmapSource.REGION_DECODE_THRESHOLD_BYTES)
        // 96 MiB ÷ 4 B/像素 = 25,165,824 像素，正好是 4096x6144 —— 边界用例的尺寸来源。
        // 边界尺寸必须能被整像素表达，否则「恰好等于阈值」这一格根本没被测到。
        assertEquals(4096L * 6144L, SpriteBitmapSource.REGION_DECODE_THRESHOLD_BYTES / 4L)
    }

    @Test
    fun `没有区域源时一张都不走区域解码`() {
        // 整张 164.4 MiB 的 CSV 父图若没有区域源，也只能按整张走（这条是「有源才兜底」的硬前提）。
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(false, 6_964, 6_191))
        // 立绘工作表 8000x4000 = 122 MiB **越阈值**：它没有区域源（G_ID 不是 CSV 父图），
        // 走区域解码就等于把工作表切成碎片，行为不再是原来那张图 ⇒ 必须 false。
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(false, 8_000, 4_000))
        // 谎报到上限的尺寸也一样。
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(false, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `恰好等于 96 MiB 不越线`() {
        // 判据是「之上」（`>`），不是「不低」（`>=`）。
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(true, 4_096, 6_144))
        // 相邻一格：少一个像素当然也不越线。
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(true, 4_096, 6_143))
    }

    @Test
    fun `多一个像素就越线`() {
        // 4096x6144 + 一行一个像素 = 96 MiB + 4 B。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, 4_097, 6_144))
        // 同一笔字节量的另一条尺寸组合（1 像素宽）：证明判据只看字节数，不看长宽形状。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, 1, 25_165_825))
        // 语料里真正越线的那一张：6964x6191 = 172,456,496 B = 164.5 MiB。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, 6_964, 6_191))
    }

    @Test
    fun `被改坏的尺寸在 Int 上绕成小数也照样越线`() {
        // 65536x65536 = 2^32 像素：Int 乘恰好绕成 0，`0 * 4` 还是 0 ⇒ 「这张图 0 字节」的谎报
        // 会把该走区域解码的那张送回整张解码（32 GiB 级整图 ⇒ 必 OOM）。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, 65_536, 65_536))
        // 32768x32768 = 2^30 像素：Int 上 `2^30 * 4` 绕回 0，同一形。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, 32_768, 32_768))
        // 100000x100000（KDoc 点名的那一形）：Int 乘积落到 1.41e9 附近，乘 4 再绕一次。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, 100_000, 100_000))
        // Int.MAX_VALUE x 2：Int 乘积 = -2（负数），`-2 * 4 = -8` ⇒ 被判「很小」。
        assertTrue(SpriteBitmapSource.regionDecodeNeeded(true, Int.MAX_VALUE, 2))
    }

    @Test
    fun `零尺寸与负尺寸折成 0 字节不越线`() {
        // 未创建 / 已 dispose 的缓冲（尺寸归零）不可能越线 —— 与拆分前的算式同值。
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(true, 0, 0))
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(true, 0, 6_191))
        assertFalse(SpriteBitmapSource.regionDecodeNeeded(true, -1, -1))
    }
}
