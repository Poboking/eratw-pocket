package com.eratw.port.platform.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「抽像素数组用」解码选项的形状闸（玩家报「立绘脸红变脸黑」的回归钉）。
 *
 * 背景见 [BitmapImageDecoder] 类 KDoc 的像素口径段：像素阵列路径的位图从不画上
 * Canvas（即取即收），要的是文件直通 ARGB。`inPremultiplied` 缺省 true 会预乘存底、
 * `getPixels` 再去预乘读出——真机报的「半透明叠层发暗、不透明内容正常」正是预乘值
 * 被当直通值消费的特征签名，且真机 Skia WebP 区域解码已实测过契约外行为
 * （`ResourceCsvHost.decodeRegion` 的 KDoc：要 288x244 得 289x244）。
 * 三处解码点必须同走 [BitmapImageDecoder.pixelArrayDecodeOptions] 单一来源、
 * 且恒为非预乘存底。
 */
class PixelArrayDecodeOptionsTest {

    @Test
    fun `像素阵列解码选项必须非预乘存底`() {
        val options = BitmapImageDecoder.pixelArrayDecodeOptions()
        assertFalse(
            "inPremultiplied 必须为 false：位图只喂 getPixels，非预乘存底才与桌面 ImageIO 逐字节同构",
            options.inPremultiplied,
        )
        assertFalse(
            "inScaled 必须为 false：调用方要的是文件像素，密度缩放会改 GWIDTH/SPRITEWIDTH 读数",
            options.inScaled,
        )
        assertSame("必须 ARGB_8888：其它配置丢 alpha 或改通道序", Bitmap.Config.ARGB_8888, options.inPreferredConfig)
        assertFalse("inDither 必须为 false：抖动会改写像素值", options.inDither)
    }

    /**
     * 绘制句柄口径（0.2.44 回归钉）：**必须预乘存底**。
     *
     * 这条与上面那条看似矛盾，其实是两个消费者：抽像素数组要直通值，画上 Canvas 要预乘值。
     * 0.2.44 把绘制句柄也解成直通再 `setPremultiplied(true)`（只翻标志不翻数据），
     * 真机实测半透明像素 `0x6BF89C95` 读回 `0x6BFFFFFF`（R/G/B 打满），
     * 屏上是「特效层发白 + 脸周白框」。
     */
    @Test
    fun `绘制句柄解码选项必须预乘存底`() {
        val options = BitmapImageDecoder.renderDecodeOptions()
        assertTrue(
            "inPremultiplied 必须为 true：消费者是 Skia，混合公式假定源已预乘；" +
                "解成直通再翻标志会把半透明贡献放大 1/a 倍",
            options.inPremultiplied,
        )
        assertFalse("inScaled 必须为 false：文件像素口径与像素阵列路径同源", options.inScaled)
        assertSame("必须 ARGB_8888：其它配置丢 alpha 或改通道序", Bitmap.Config.ARGB_8888, options.inPreferredConfig)
        assertFalse("inDither 必须为 false：抖动会改写像素值", options.inDither)
    }

    @Test
    fun `绘制句柄与像素阵列是两份独立选项_不得混用`() {
        val array = BitmapImageDecoder.pixelArrayDecodeOptions()
        val render = BitmapImageDecoder.renderDecodeOptions()
        assertTrue("每次调用必须新建 Options（解码器各自持有，不得共享可变实例）", array !== render)
        assertFalse("抽像素口径必须是直通存底", array.inPremultiplied)
        assertTrue("绘制口径必须是预乘存底", render.inPremultiplied)
    }

    @Test
    fun `选项对象是新实例_调用方各自持有`() {
        // 两次调用拿到两个独立对象：ResourceCsvHost 与 BitmapImageDecoder 的实例字段
        // 各持一份，改其中一个不能串味。
        val a = BitmapImageDecoder.pixelArrayDecodeOptions()
        val b = BitmapImageDecoder.pixelArrayDecodeOptions()
        assertTrue("每次调用必须新建 Options（解码器各自持有，不得共享可变实例）", a !== b)
        assertEquals(a.inPremultiplied, b.inPremultiplied)
    }

    @Test
    fun `非预乘选项对象在纯JVM下字段可断言_防桩退化`() {
        // 守住装置前提：android.jar 桩（unitTests.isReturnDefaultValues）下 Options 的
        // 字段读写仍然可用——若桩行为变了（字段读不出所设值），上面那条主闸就是假绿。
        val options = BitmapFactory.Options()
        options.inPremultiplied = false
        assertFalse("Options 字段在桩下必须可写可读，否则本测试类的闸全部失效", options.inPremultiplied)
    }
}
