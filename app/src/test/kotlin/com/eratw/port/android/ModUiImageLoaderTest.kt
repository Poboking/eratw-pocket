package com.eratw.port.android

import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.modUiImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片加载器的**降级纪律**行为面（`ModUiImageLoader`）：所有失效路径（缺字节 / 空 / 超大 /
 * 非图片 / 解码返回 null / 解码抛异常 / 解码期 OOM）都只产出
 * [ModUiImageOutcome.Placeholder] 并记 WARN，**绝不抛回调用侧**。
 *
 * 用注入假解码器 + 泛型产物（`String`）跑：`app` 的单测环境无 Robolectric，
 * `android.graphics.Bitmap` 不可实例化；真 `BitmapFactory` 解码层留真机/仪器测试
 * （`TODO(verify)` 见 `ModUiImage.kt` 的 `BitmapFactoryModUiImageDecoder`）。
 */
class ModUiImageLoaderTest {

    private val logs = mutableListOf<String>()

    private val element = modUiImage("images/bg01.png", altText = "替代文本", maxSidePx = 256)

    private fun loader(
        decoder: ModUiImageDecoder<String>,
        maxBytes: Int = MAX_MOD_UI_IMAGE_BYTES,
    ): ModUiImageLoader<String> = ModUiImageLoader(decoder = decoder, logSink = { logs += it }, maxBytes = maxBytes)

    private fun assertPlaceholder(
        outcome: ModUiImageOutcome<String>,
        reason: ModUiImageFailure,
        altText: String = "替代文本",
    ): ModUiImageOutcome.Placeholder {
        assertTrue("应降级为占位，实际：$outcome", outcome is ModUiImageOutcome.Placeholder)
        val placeholder = outcome as ModUiImageOutcome.Placeholder
        assertEquals(reason, placeholder.reason)
        assertEquals(altText, placeholder.altText)
        return placeholder
    }

    @Test
    fun `PNG 正常解码并透传 maxSidePx`() {
        val decoder = FakeDecoder()
        val outcome = loader(decoder).decode(element, pngBytes())
        assertTrue(outcome is ModUiImageOutcome.Ready)
        assertEquals("bitmap", (outcome as ModUiImageOutcome.Ready).image)
        assertEquals(1, decoder.calls)
        assertEquals(256, decoder.lastMaxSide)
    }

    @Test
    fun `缺字节降级为 MISSING 且带替代文本`() {
        val decoder = FakeDecoder()
        assertPlaceholder(loader(decoder).decode(element, null), ModUiImageFailure.MISSING)
        assertEquals("缺字节时不许进解码器", 0, decoder.calls)
        assertTrue(logs.any { it.contains("MISSING") && it.contains("images/bg01.png") })
    }

    @Test
    fun `空字节降级为 EMPTY`() {
        val decoder = FakeDecoder()
        assertPlaceholder(loader(decoder).decode(element, ByteArray(0)), ModUiImageFailure.EMPTY)
        assertEquals(0, decoder.calls)
    }

    @Test
    fun `超过单文件上限降级为 TOO_LARGE 且不进解码器`() {
        val decoder = FakeDecoder()
        val bytes = pngBytes() + ByteArray(64)
        assertPlaceholder(loader(decoder, maxBytes = 16).decode(element, bytes), ModUiImageFailure.TOO_LARGE)
        assertEquals(0, decoder.calls)
    }

    @Test
    fun `非图片字节被白名单挡在解码器之外`() {
        val decoder = FakeDecoder()
        assertPlaceholder(
            loader(decoder).decode(element, "definitely-not-an-image".toByteArray()),
            ModUiImageFailure.UNSUPPORTED_FORMAT,
        )
        assertEquals(0, decoder.calls)
    }

    @Test
    fun `JPEG 与 WebP 容器被接受`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0x10)
        assertTrue(loader(FakeDecoder()).decode(element, jpeg) is ModUiImageOutcome.Ready)
        val webp = "RIFF".toByteArray() + byteArrayOf(0x20, 0x00, 0x00, 0x00) + "WEBPVP8 ".toByteArray() +
            ByteArray(8)
        assertTrue(loader(FakeDecoder()).decode(element, webp) is ModUiImageOutcome.Ready)
    }

    @Test
    fun `解码器返回 null 降级为 DECODE_FAILED`() {
        val decoder = FakeDecoder(result = null)
        assertPlaceholder(loader(decoder).decode(element, pngBytes()), ModUiImageFailure.DECODE_FAILED)
        assertEquals(1, decoder.calls)
    }

    @Test
    fun `解码期 OOM 不炸且降级为 OUT_OF_MEMORY`() {
        val decoder = FakeDecoder(failure = OutOfMemoryError("模拟解码期内存不足"))
        val outcome = loader(decoder).decode(element, pngBytes())
        assertPlaceholder(outcome, ModUiImageFailure.OUT_OF_MEMORY)
        assertTrue(
            "OOM 必须留 WARN 痕迹",
            logs.any { it.contains("OUT_OF_MEMORY") && it.contains("OutOfMemoryError") },
        )
    }

    @Test
    fun `解码器抛普通异常降级为 DECODE_FAILED 不外泄`() {
        val decoder = FakeDecoder(failure = IllegalStateException("损坏的图片数据"))
        assertPlaceholder(loader(decoder).decode(element, pngBytes()), ModUiImageFailure.DECODE_FAILED)
        assertTrue(logs.any { it.contains("DECODE_FAILED") && it.contains("IllegalStateException") })
    }

    @Test
    fun `采样率决策按最长边取 2 的幂`() {
        assertEquals(1, modUiImageSampleSize(width = 100, height = 80, maxSidePx = 256))
        assertEquals(2, modUiImageSampleSize(width = 512, height = 512, maxSidePx = 256))
        assertEquals(2, modUiImageSampleSize(width = 1000, height = 800, maxSidePx = 256))
        assertEquals(64, modUiImageSampleSize(width = 4096, height = 4096, maxSidePx = 64))
        assertEquals(1, modUiImageSampleSize(width = 4096, height = 4096, maxSidePx = null))
        assertEquals(1, modUiImageSampleSize(width = 0, height = 0, maxSidePx = 256))
    }

    @Test
    fun `容器魔数识别`() {
        assertTrue(looksLikeSupportedImageContainer(pngBytes()))
        assertFalse(looksLikeSupportedImageContainer(byteArrayOf(0x89.toByte(), 0x50)))
        assertFalse(looksLikeSupportedImageContainer("RIFFxxxxNOPE".toByteArray()))
        assertFalse("空表不许当 PNG", looksLikeSupportedImageContainer(ByteArray(0)))
    }

    private fun pngBytes(): ByteArray =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)

    private class FakeDecoder(
        private val result: String? = "bitmap",
        private val failure: Throwable? = null,
    ) : ModUiImageDecoder<String> {

        var calls: Int = 0
            private set

        var lastMaxSide: Int? = null
            private set

        override fun decode(bytes: ByteArray, maxSidePx: Int?): String? {
            calls += 1
            lastMaxSide = maxSidePx
            failure?.let { throw it }
            return result
        }
    }
}
