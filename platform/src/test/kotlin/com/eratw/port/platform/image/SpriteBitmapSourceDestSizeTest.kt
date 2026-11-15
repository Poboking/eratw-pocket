package com.eratw.port.platform.image

import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SPRITE_SLOT
import com.eratw.port.core.host.SpritePool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * `SpriteBitmapSource.destSize` 的「尺寸源 = 注册表」判据（MODERN-R11-DIVW）。
 *
 * 原版 `<img>` 缺 `width` 时的基图尺寸只来自 `AppContents.GetSprite` 的注册表
 * （`AppContents.cs:39-51` 的 `imageDictionary`；成员 = CSV 声明 `:157-161` +
 * 运行时 `SPRITECREATE` `:84-88`），miss 即按 AltText 文本计宽
 * （`ConsoleImagePart.cs:60-66`、`:104-115`）。此前本端在池 miss 后还读盘量尺寸
 * （`resourceNameSize`），使「池里没有、盘上有」的名字按图宽入表而 core 侧按文本宽计
 * ⇒ 两链列位漂。本类钉两件事：
 * 1. 池命中 ⇒ 打包字与池里的 (width,height) 一致；
 * 2. `destSize` 的函数体**不再读盘**（源码面钉子；桌面 JVM 的 `BitmapFactory` 是桩，
 *    行为面无法区分「读盘 miss」与「不读盘」，只能从源码面钉死）。
 */
class SpriteBitmapSourceDestSizeTest {

    private val sources = ArrayList<SpriteBitmapSource>()
    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        for (s in sources) runCatching { s.close() }
        for (r in roots) runCatching { r.deleteRecursively() }
    }

    private fun newRoot(): File {
        val root = File(System.getProperty("java.io.tmpdir"), "eratw-divw-${System.nanoTime()}")
        root.mkdirs()
        roots.add(root)
        return root
    }

    private fun newSource(aux: RuntimeAux): SpriteBitmapSource =
        SpriteBitmapSource(aux, GameImageFiles(newRoot())).also { sources.add(it) }

    /** 池命中：返回 `(宽 shl 16) or 高` 的打包字（大小写不敏感，池内归一成大写）。 */
    @Test
    fun `池命中返回打包尺寸`() {
        val aux = RuntimeAux()
        aux.slot(SPRITE_SLOT) { SpritePool() }.put(SpritePool.Sprite("TEST_IMG", 30, 40))
        val src = newSource(aux)
        assertEquals((30 shl 16) or 40, src.destSize("test_img"))
        assertEquals((30 shl 16) or 40, src.destSize("TEST_IMG"))
    }

    /** 池 miss：即使游戏目录里真有一个同名文件，也不许再读盘量尺寸。 */
    @Test
    fun `池miss返回null且不读盘`() {
        val aux = RuntimeAux()
        aux.slot(SPRITE_SLOT) { SpritePool() }
        val src = SpriteBitmapSource(aux, GameImageFiles(newRoot().also {
            File(it, "ONLY_ON_DISK.png").writeBytes(ByteArray(0))
        })).also { sources.add(it) }
        assertNull(src.destSize("ONLY_ON_DISK"))
    }

    /** 源码面：`destSize` 函数体只查 SpritePool，不许出现文件尺寸通道。 */
    @Test
    fun `destSize函数体不读盘`() {
        val srcText = Path.of("").toAbsolutePath()
            .resolve("src/main/kotlin/com/eratw/port/platform/image/SpriteBitmapSource.kt")
            .readText()
        val body = bodyOf(srcText, "override fun destSize(resource: String): Int?")
        assertTrue("必须只查 SpritePool：$body", body.contains("SpritePool"))
        assertFalse("不许再走读盘量尺寸（DIVW 红线）：$body", body.contains("resourceNameSize"))
        assertFalse("destSize 不许碰文件层：$body", body.contains("files."))
    }

    private fun bodyOf(text: String, signature: String): String {
        val start = text.indexOf(signature)
        assertTrue("源码里找不到 $signature", start >= 0)
        val open = text.indexOf('{', start)
        assertTrue("$signature 后没有函数体", open >= 0)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
                else -> {}
            }
            i++
        }
        error("$signature 的函数体没有配平")
    }
}
