package com.eratw.port.android

import com.eratw.port.modapi.ModUiElement
import com.eratw.port.modapi.modUiImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浮层元素模型（`mod-api` 的 `ModUiElement` / `modUiImage`）的**装载期校验**行为面。
 *
 * 钉的是「作者错误在构造时暴露」这条口径：资源名沿用资源面名字语义（不归一、直接拒）、
 * 替代文本与 `maxSidePx` 有硬边界。渲染/降级面在 `ModUiImageLoaderTest`。
 */
class ModUiElementModelTest {

    @Test
    fun `构造默认值与数据类语义`() {
        val element = modUiImage("images/bg01.png")
        assertEquals("", element.altText)
        assertNull(element.maxSidePx)
        assertEquals("images/bg01.png", element.resourceName)
        assertEquals(ModUiElement.Image("images/bg01.png"), element)
        assertEquals(element, element.copy())
    }

    @Test
    fun `合法资源名接受单段与多段相对路径`() {
        val names = listOf("a.png", "images/bg01.png", "a/b/c/d.webp", "图片/背景 01.png", "a-b_c.1.jpg")
        for (name in names) {
            assertEquals(name, modUiImage(name).resourceName)
        }
    }

    @Test
    fun `非法资源名当场拒绝`() {
        val illegal = listOf(
            "",
            "/a.png",
            "a/",
            "a//b.png",
            "a/./b.png",
            "a/../b.png",
            "..",
            "a\\b.png",
            "C:/x.png",
            "a:b.png",
            "a\u0001b.png",
            "a\u007fb.png",
        )
        for (name in illegal) {
            assertThrows("应拒绝非法资源名：[$name]", IllegalArgumentException::class.java) {
                ModUiElement.Image(name)
            }
        }
    }

    @Test
    fun `替代文本有字符数上限`() {
        assertEquals(ModUiElement.Image.MAX_ALT_TEXT_CHARS, modUiImage("a.png", "x".repeat(200)).altText.length)
        assertThrows(IllegalArgumentException::class.java) {
            ModUiElement.Image("a.png", altText = "x".repeat(ModUiElement.Image.MAX_ALT_TEXT_CHARS + 1))
        }
    }

    @Test
    fun `maxSidePx 边界与非法值`() {
        assertEquals(1, modUiImage("a.png", maxSidePx = 1).maxSidePx)
        assertEquals(
            ModUiElement.Image.MAX_SIDE_PX,
            modUiImage("a.png", maxSidePx = ModUiElement.Image.MAX_SIDE_PX).maxSidePx,
        )
        for (bad in listOf(0, -1, ModUiElement.Image.MAX_SIDE_PX + 1)) {
            assertThrows("应拒绝非法 maxSidePx：$bad", IllegalArgumentException::class.java) {
                ModUiElement.Image("a.png", maxSidePx = bad)
            }
        }
    }

    @Test
    fun `建议尺寸小于硬上限`() {
        assertTrue(
            "建议尺寸应明显小于渲染硬上限",
            ModUiElement.Image.SUGGESTED_SIDE_PX < ModUiElement.Image.MAX_SIDE_PX,
        )
    }
}
