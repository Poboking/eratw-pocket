package com.eratw.port.android

import com.eratw.port.android.ModArchiveFormats.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 非 zip 容器的识别判据（[ModArchiveFormats]）：扩展名尾段逐字符折叠 + 必须有主名。
 *
 * 为什么这批只有识别没有解包：RAR5 没有纯 JVM 实现（junrar 只认 RAR4 且不支持加密），
 * 7z/LZH/ALZ 要往 APK 里塞 JNI 或数 MB 依赖。口上包本体是文本 + zip 容器，玩家要的是
 * 「这个文件怎么办」这句指路，不是多认三种容器。
 *
 * 判据的**反向边**比正向边更重要：`.zip` 必须返回 null —— 一旦被认成压缩包，zip 整包
 * 展开链（加密解密、资源面、成品包）就整条绕过去了，玩家会看到「本机解不开」而不是导入。
 */
class ModArchiveFormatsTest {

    @Test
    fun `每个族的每个扩展名都认得（大小写两种写法）`() {
        for (format in Format.entries) {
            for (extension in format.extensions) {
                assertEquals("$extension 应认出 ${format.label}", format, ModArchiveFormats.of("kojo$extension"))
                assertEquals(
                    "${extension.lowercase()} 应认出 ${format.label}",
                    format,
                    ModArchiveFormats.of("kojo${extension.lowercase()}"),
                )
            }
        }
    }

    @Test
    fun `zip 与口上源不是压缩包——zip 必须继续走整包展开链`() {
        for (name in listOf("kojo.zip", "KOJO.ZIP", "a.Zip", "M_KOJO_K1.ERB", "M_KOJO_K1.erh", "readme.txt", "noext")) {
            assertNull("$name 不该被认成非 zip 容器", ModArchiveFormats.of(name))
        }
    }

    @Test
    fun `必须有主名——隐藏文件名不算`() {
        assertNull(".rar", ModArchiveFormats.of(".rar"))
        assertNull(".7z", ModArchiveFormats.of(".7z"))
    }

    @Test
    fun `只看最后一个扩展名——改过名的包不在这里猜`() {
        assertNull("a.rar.txt 的尾段是 txt", ModArchiveFormats.of("a.rar.txt"))
        assertEquals("尾段是 rar 就认", Format.RAR, ModArchiveFormats.of("a.txt.rar"))
    }

    @Test
    fun `多卷与复合后缀按最后一段归类`() {
        assertEquals(Format.RAR, ModArchiveFormats.of("kojo.part1.rar"))
        assertEquals(Format.RAR, ModArchiveFormats.of("kojo.part03.RAR"))
        assertEquals(Format.GZIP, ModArchiveFormats.of("kojo.tar.gz"))
        assertEquals(Format.GZIP, ModArchiveFormats.of("KOJO.TAR.GZ"))
        assertEquals(Format.GZIP, ModArchiveFormats.of("kojo.tgz"))
        assertEquals(Format.BZIP2, ModArchiveFormats.of("kojo.tar.bz2"))
        assertEquals(Format.XZ, ModArchiveFormats.of("kojo.tar.xz"))
    }

    @Test
    fun `族名直接上屏——与发布页写法一致，不做本地化`() {
        assertEquals("RAR", Format.RAR.label)
        assertEquals("7z", Format.SEVEN_ZIP.label)
    }
}
