package com.eratw.port.platform.audio

import com.eratw.port.core.audio.ChannelDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [FileSoundSourceResolver] 的判据：名字 → `<soundDir>/<name>`、缺文件静默、解码异常上抛、
 * 路径穿越拦在根目录内。
 *
 * 全程在 [TemporaryFolder] 里造合成文件，不加载 MediaPlayer（打开函数注入假解码器），
 * 因此是纯 JVM 单测（`platform` 模块的 JUnit4 源集）。C# 依据：
 * - `Path.GetFullPath(".\\sound\\" + name)` + `File.Exists`：FunctionIdentifier.cs:2622-2628（PLAYSOUND）、
 *   :2691-2697（PLAYBGM）。
 * - 打开失败 → 裸 catch → CodeEE：FunctionIdentifier.cs:2647-2650、:2699-2702。
 * - 每次播放重新开流：Sound.cs:82-96。
 *
 * 基准 `pc-golden-root.txt:519-522` 的 SOUND 族是 MISSING 行，本文件不引用基准。
 */
class FileSoundSourceResolverTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 每次调用 [FileSoundSourceResolver] 时 `open` 收到的文件（按顺序）。 */
    private val opened = mutableListOf<File>()

    /** 假解码器：只有时间基，钩子全部走默认实现。 */
    private fun stubDecoder(): ChannelDecoder = object : ChannelDecoder {
        override val totalSeconds: Double = 1.0
        override val positionSeconds: Double = 0.0
        override fun seekTo(seconds: Double) {}
    }

    /** 记录打开文件的解析器；`open` 总返回一个新的假解码器。 */
    private fun resolver(soundDir: File): FileSoundSourceResolver =
        FileSoundSourceResolver(soundDir) { file ->
            opened += file
            stubDecoder()
        }

    /** 在 [dir] 下造一个文件（自动建父目录），内容任意。 */
    private fun write(dir: File, name: String): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x49, 0x44, 0x33))
        return file
    }

    // ------------------------------------------------------------------
    // 命中
    // ------------------------------------------------------------------

    @Test
    fun `存在的文件解析为根目录下的绝对路径`() {
        val sound = temp.newFolder("sound")
        val file = write(sound, "bgm.mp3")

        val decoded = resolver(sound).resolve("bgm.mp3")

        assertNotNull("文件存在 ⇒ 建解码器（:2625-2626）", decoded)
        assertEquals(1, opened.size)
        assertEquals(file.canonicalFile, opened.single().canonicalFile)
        assertEquals(sound.canonicalFile, opened.single().parentFile.canonicalFile)
    }

    @Test
    fun `支持子目录与两种分隔符`() {
        // 语料实参形如 `小图书馆/姆Q.MP3`（PLAYSOUND 直接吃带子目录的名字）；
        // Windows 原版的 `\\` 与 `/` 都能当分隔符（Path.GetFullPath 口径）。
        val sound = temp.newFolder("sound")
        write(sound, "小图书馆/姆Q.mp3")

        assertNotNull(resolver(sound).resolve("小图书馆/姆Q.mp3"))
        assertNotNull(resolver(sound).resolve("小图书馆\\姆Q.mp3"))
        assertEquals(2, opened.size)
    }

    @Test
    fun `语料的大写 MP3 命中小写磁盘文件`() {
        // Windows 的 File.Exists 大小写不敏感，Android 敏感；不回落就会静默不播。
        // 语料实形：TITLE.ERB:143 `PLAYSOUND "bad-apple-audio.MP3"`（磁盘是 .mp3）、
        // 054 Patchouli 小图书馆事件.ERB:200-231 的 `*.MP3` 同理。
        val sound = temp.newFolder("sound")
        val apple = write(sound, "bad-apple-audio.mp3")
        val library = write(sound, "小图书馆/姆Q.mp3")

        resolver(sound).resolve("bad-apple-audio.MP3")
        resolver(sound).resolve("小图书馆/姆Q.MP3")

        assertEquals(
            listOf(apple.canonicalFile, library.canonicalFile),
            opened.map { it.canonicalFile },
        )
    }

    @Test
    fun `根目录内折叠点点仍可解析`() {
        // 原版 Path.GetFullPath 会把 `sub/../a.mp3` 折叠成 `a.mp3`，不要求 `sub` 存在。
        val sound = temp.newFolder("sound")
        val file = write(sound, "a.mp3")

        assertNotNull(resolver(sound).resolve("sub/../a.mp3"))
        assertEquals(file.canonicalFile, opened.single().canonicalFile)
    }

    // ------------------------------------------------------------------
    // 不存在（静默）
    // ------------------------------------------------------------------

    @Test
    fun `缺文件返回 null 且不调用打开函数`() {
        val sound = temp.newFolder("sound")

        assertNull(resolver(sound).resolve("nope.mp3"))
        assertTrue("File.Exists 失败直接 return，不建声道也不开流（:2625-2628、:2694-2697）", opened.isEmpty())
    }

    @Test
    fun `sound 目录不存在时全部返回 null`() {
        val missing = File(temp.root, "no-such-sound-dir")

        assertNull(resolver(missing).resolve("bgm.mp3"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `目录不算文件`() {
        val sound = temp.newFolder("sound")
        File(sound, "folder.mp3").mkdirs()

        assertNull(resolver(sound).resolve("folder.mp3"))
        assertTrue("原版 File.Exists 对目录返回 false", opened.isEmpty())
    }

    @Test
    fun `空名字与根目录本身按不存在处理`() {
        val sound = temp.newFolder("sound")

        assertNull(resolver(sound).resolve(""))
        assertNull(resolver(sound).resolve("."))
        assertNull(resolver(sound).resolve("sub/.."))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `打开函数返回 null 时按不存在处理`() {
        val sound = temp.newFolder("sound")
        write(sound, "a.mp3")
        val resolver = FileSoundSourceResolver(sound) { null }

        assertNull(resolver.resolve("a.mp3"))
    }

    // ------------------------------------------------------------------
    // 解码失败上抛（:2647-2650、:2699-2702）
    // ------------------------------------------------------------------

    @Test
    fun `打开异常原样上抛`() {
        val sound = temp.newFolder("sound")
        write(sound, "broken.mp3")
        val boom = IllegalStateException("非対応のサウンドファイルが指定されました")
        val resolver = FileSoundSourceResolver(sound) { throw boom }

        val actual = assertThrows(IllegalStateException::class.java) { resolver.resolve("broken.mp3") }

        assertSame("异常原样穿到上层（TableSoundSink 再转 CodeEE）", boom, actual)
    }

    @Test
    fun `打开期 IO 异常同样上抛`() {
        val sound = temp.newFolder("sound")
        write(sound, "broken.mp3")
        val resolver = FileSoundSourceResolver(sound) { throw IOException("解码器打不开") }

        assertThrows(IOException::class.java) { resolver.resolve("broken.mp3") }
    }

    // ------------------------------------------------------------------
    // 格式闸（m11e-audio-format ①）：存在 ≠ 能出声
    // ------------------------------------------------------------------

    @Test
    fun `存在的 mid 文件按不支持的格式报错 且不调用打开函数`() {
        // PC：.mid 落 MediaFoundation（Sound.cs:91-96）⇒ 无 SMF 解码器 ⇒ 构造期抛 ⇒ 裸 catch
        // 转 CodeEE（FunctionIdentifier.cs:2647-2650）。移植侧同样报错；**绝不返回 null** ——
        // null 的约定是「文件不存在 ⇒ 静默」（:2625-2628），那才是「EXISTSOUND=1 却没声音」。
        val sound = temp.newFolder("sound")
        write(sound, "theme.mid")
        val resolver = resolver(sound)

        val e = assertThrows(UnsupportedSoundFormatException::class.java) {
            resolver.resolve("theme.mid")
        }

        assertEquals(SoundFormat.MIDI, e.plan.format)
        assertEquals("theme.mid", e.file.name)
        assertTrue("判定为放不出来就不该碰解码器", opened.isEmpty())
    }

    @Test
    fun `无扩展名文件仍交给打开函数`() {
        // 语料 sound/游戏bgm/<区域>/bgm 是无扩展名占位：格式未知 ⇒ ATTEMPT，由平台嗅探决定，
        // 失败也会以异常形式上来（不是静默）。所以这里必须真的调用 open。
        val sound = temp.newFolder("sound")
        write(sound, "bgm")
        val resolver = resolver(sound)

        assertNotNull(resolver.resolve("bgm"))
        assertEquals(1, opened.size)
    }

    @Test
    fun `不存在的 mid 按不存在静默 不抛格式异常`() {
        // 格式闸只对**存在**的文件生效：PC 的 File.Exists 检查在所有事之前
        // （FunctionIdentifier.cs:2625-2628、:2694-2697），不存在的 .mid 与不存在的 .mp3
        // 同判 —— 静默。否则「文件不存在」会被升级成 CodeEE，与 PC 分叉。
        val sound = temp.newFolder("sound")

        assertNull(resolver(sound).resolve("missing.mid"))
        assertNull(resolver(sound).resolve("sub/missing.MIDI"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `格式按磁盘真实文件名判定 大小写回落后的 mid 同样报错`() {
        // ERB 实参 theme.MID 经大小写回落到磁盘 theme.mid；真正交给播放器的是磁盘文件，
        // 格式判定因此按 locate 返回的 file.name 而不是实参（FileSoundSourceResolver.resolve
        // 的注释口径；PC 判的也是最终传进 play 的 fullPath，Sound.cs:83-96）。
        // canonicalFile 口径与「语料的大写 MP3 命中小写磁盘文件」一致：Windows 上精确命中
        // 拿到的是原大小写文件，canonicalFile 归一回磁盘拼写，两种主机同判。
        val sound = temp.newFolder("sound")
        write(sound, "theme.mid")
        val resolver = resolver(sound)

        val e = assertThrows(UnsupportedSoundFormatException::class.java) {
            resolver.resolve("theme.MID")
        }

        assertEquals("theme.mid", e.file.canonicalFile.name)
        assertEquals(SoundFormat.MIDI, e.plan.format)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `wma 交给平台试 不预先拒绝`() {
        // PC 的 MF 能解 WMA（Sound.cs:91-96 的 else 分支），MediaPlayer 不一定 —— 真差异，
        // 走 ATTEMPT：能放就放、放不了由 open 抛异常（上层 TableSoundSink 转 CodeEE）。
        // 绝不能 REJECT，那会在 PC 有声的格式上主动报错（SoundFormatPlan 的结构性不变量）。
        val sound = temp.newFolder("sound")
        write(sound, "a.wma")
        val resolver = resolver(sound)

        assertNotNull(resolver.resolve("a.wma"))
        assertEquals(1, opened.size)
    }

    // ------------------------------------------------------------------
    // 安全边界：穿越与绝对路径
    // ------------------------------------------------------------------

    @Test
    fun `路径穿越逃出根目录按不存在处理`() {
        val sound = temp.newFolder("sound")
        write(temp.root, "secret.mp3") // 根目录的兄弟文件
        val resolver = resolver(sound)

        assertNull(resolver.resolve("../secret.mp3"))
        assertNull(resolver.resolve("sub/../../secret.mp3"))
        assertTrue("逃出根目录的名字一个字节都不读", opened.isEmpty())
    }

    @Test
    fun `绝对路径一律拒绝`() {
        val sound = temp.newFolder("sound")
        val outside = write(temp.root, "secret.mp3")
        val resolver = resolver(sound)

        assertNull(resolver.resolve(outside.absolutePath))
        assertNull(resolver.resolve("/etc/passwd"))
        assertTrue(opened.isEmpty())
    }

    // ------------------------------------------------------------------
    // 每次重新打开 / 工厂 / 大小写口径
    // ------------------------------------------------------------------

    @Test
    fun `每次解析都重新打开`() {
        // 原版每次 play 都新建解码器（Sound.cs:83-96），play 开头先 stop 旧流（:82）。
        val sound = temp.newFolder("sound")
        write(sound, "a.mp3")
        val resolver = resolver(sound)

        val first = resolver.resolve("a.mp3")
        val second = resolver.resolve("a.mp3")

        assertNotSame(first, second)
        assertEquals(2, opened.size)
    }

    @Test
    fun `forGameRoot 以游戏根下的 sound 为基准`() {
        val gameRoot = temp.newFolder("game")
        write(File(gameRoot, FileSoundSourceResolver.SOUND_DIR_NAME), "se/x.mp3")
        val resolver = FileSoundSourceResolver.forGameRoot(gameRoot) { file ->
            opened += file
            stubDecoder()
        }

        assertNotNull(resolver.resolve("se/x.mp3"))
        assertEquals(
            File(gameRoot, "sound/se/x.mp3").canonicalFile,
            opened.single().canonicalFile,
        )
    }

    // 大小写口径由 core 侧 SoundResourcePaths.resolve 统一钉（原名精确命中优先，落空才逐段
    // 大小写不敏感回落，等价 Windows NTFS 上 File.Exists 的读数），播放与 EXISTSOUND 同源
    // 不分叉：语料实证见上面「语料的大写 MP3 命中小写磁盘文件」，越界/穿越的合成用例见
    // 「路径穿越逃出根目录按不存在处理」。断言一律走 canonicalFile 口径，Windows 与 Linux
    // 主机同判（精确命中时 Windows 返回原大小写文件，canonicalFile 归一回磁盘拼写）。
}
