package com.eratw.port.platform.audio

import com.eratw.port.core.audio.ChannelDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale

/**
 * 语料音频格式覆盖回归（w3-l19）—— 把「共享根 sound 目录的实际格式」钉进判定表。
 *
 * ## 普查读数（2026-10-24，只读共享根，未复制任何文件进仓库）
 * `eratw-画蛇添足版eratw4.980/sound/` 共 **52** 个文件，扩展名分布：
 * - 30 个 `.mp3`（小写）；
 * - 3 个 `.MP3`（大写：`区/vamos!.MP3`、`区/卡了.MP3`、`区/操操操.MP3`）；
 * - 18 个无扩展名的 **0 字节**占位文件 `游戏bgm/<区域>/bgm`；
 * - 1 个 `.txt`（`映姫様素材/K30_素材について.txt`，素材说明，不是音频）。
 *
 * 全量校验魔数：33 个 mp3 全是真 MP3（19 个 `ID3` 标签、14 个 `FF Fx` 帧同步），
 * 没有「后缀写 mp3、内容不是」的错标。
 *
 * ERB 侧实参（全量 `ERB` 子树 grep PLAYSOUND / PLAYBGM / EXISTSOUND）：
 * - 15 处实参以 `.mp3"` 结尾（大小写混用，`TITLE.ERB:132` 的 `bad-apple-audio.MP3` 对磁盘 `.mp3`）；
 * - 1 处被注释掉的 `.m4a`（`ERB/fromEN/OMOGATARI/Omogatari_Set.ERB:3070`，不参与运行）；
 * - 0 处 `.wav` / `.ogg` / `.mid`。
 * 结论：**语料里没有任何文件会走到 [SoundPlayback.REJECT]**（该分支只覆盖 MIDI 族）。
 *
 * ## 判据
 * PC 的格式分流只有三分支且按后缀 `EndsWith(..., OrdinalIgnoreCase)`：
 * `Sound.cs:83`（`.wav` → WaveFileReader）、`Sound.cs:87`（`.ogg` → VorbisWaveReader）、
 * `Sound.cs:95`（其余 → MediaFoundationReader）。文件存在才开流、打开失败转 CodeEE：
 * `FunctionIdentifier.cs:2622/2625/2649`（PLAYSOUND）、`:2691/2694/2701`（PLAYBGM），
 * `FunctionMethodCreator.cs:8214`（EXISTSOUND）。
 *
 * ## 本文件不做的事
 * 不做真机播放断言（CI 无 MediaPlayer）：mp3 在真机上能否出声、0 字节文件被嗅探成什么、
 * `.txt` 交给 MediaPlayer 的失败形态，全部留给装置验收。
 *
 * TODO(verify)：真机上逐类复核 —— ① 33 个 mp3（含大写后缀 3 个）能出声；② 0 字节无扩展名
 * 文件 `open` 抛异常而不是静默成功；③ `.txt` 抛异常；④ 语料外的容器（`.ogg` / `.wav` /
 * `.flac` / `.m4a`）在目标 ROM 上是否真如 [MediaPlayerSupport] 所标。卡点＝本机无装置。
 */
class AudioFormatCoverageTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 每次调用 [FileSoundSourceResolver] 时 `open` 收到的文件（按顺序）。 */
    private val opened = mutableListOf<File>()

    private fun stubDecoder(): ChannelDecoder = object : ChannelDecoder {
        override val totalSeconds: Double = 1.0
        override val positionSeconds: Double = 0.0
        override fun seekTo(seconds: Double) {}
    }

    /** 记录打开文件的解析器；`open` 总返回一个新的假解码器（不加载 MediaPlayer）。 */
    private fun resolver(soundDir: File): FileSoundSourceResolver =
        FileSoundSourceResolver(soundDir) { file ->
            opened += file
            stubDecoder()
        }

    /** 在 [dir] 下造文件（自动建父目录），内容给 3 字节假头；`empty = true` 时造 0 字节。 */
    private fun write(dir: File, name: String, empty: Boolean = false): File {
        val file = File(dir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(if (empty) ByteArray(0) else byteArrayOf(0x49, 0x44, 0x33))
        return file
    }

    /** 判定表的入参口径与 [FileSoundSourceResolver.resolve] 一致：磁盘上的**文件名**，不带目录。 */
    private fun plan(name: String) = SoundFormatTable.planFor(File(name).name)

    // ------------------------------------------------------------------
    // 1. 普查指纹：四种扩展名 + 全量文件名
    // ------------------------------------------------------------------

    @Test
    fun `语料普查读数与判定表一一对齐`() {
        assertEquals("普查总数", 52, CORPUS_NAMES.size)
        assertEquals("小写 .mp3", 30, CORPUS_MP3_LOWER.size)
        assertEquals("大写 .MP3", 3, CORPUS_MP3_UPPER.size)
        assertEquals("无扩展名占位", 18, CORPUS_EXTENSIONLESS.size)
        assertEquals(".txt 说明文件", 1, CORPUS_TXT.size)

        // PC 侧：语料里没有任何名字以 .wav / .ogg 结尾 ⇒ 全部落 MediaFoundation（Sound.cs:83/87/95）
        for (name in CORPUS_NAMES) {
            val p = plan(name)
            assertEquals("$name 在 PC 上落 MF", PcSoundReader.MEDIA_FOUNDATION, p.pcReader)
            // 语料里没有一个格式会被判成「放不出来」：REJECT 只留给 PC 也打不开的 MIDI 族。
            assertNotEquals("$name 不得触发格式闸", SoundPlayback.REJECT, p.playback)
        }
    }

    @Test
    fun `语料的 33 个 mp3 全部判成 PLAY 大小写都对`() {
        for (name in CORPUS_MP3_LOWER + CORPUS_MP3_UPPER) {
            val p = plan(name)
            assertEquals("$name 扩展名大小写不敏感（OrdinalIgnoreCase）", SoundFormat.MP3, p.format)
            assertEquals("$name 交给 MediaPlayer 播", SoundPlayback.PLAY, p.playback)
            assertEquals("$name", MediaPlayerSupport.YES, p.mediaPlayerSupport)
            assertEquals("$name MP3 在 PC 上能播", PcOpenOutcome.PLAYS, p.pcOutcome)
            assertFalse("$name 白名单内不报错", p.errorsWhenUnplayable)
        }
    }

    @Test
    fun `语料的 18 个无扩展名占位文件走 ATTEMPT 而不是 REJECT`() {
        // `游戏bgm/<区域>/bgm` 是 0 字节占位（BGM 被移除，ERB 找的是 `<区域>/<n>.MP3`，命中不了）。
        // 真被播放时：PC 的 MF 与 Android 的 MediaPlayer 都按内容嗅探，空文件两边都失败 ⇒ CodeEE，
        // 所以是 ATTEMPT（不静默），不能是 REJECT（REJECT 要求 pcOutcome 也是 FAILS_AT_OPEN）。
        for (name in CORPUS_EXTENSIONLESS) {
            val p = plan(name)
            assertEquals("$name 无扩展名", SoundFormat.NONE, p.format)
            assertEquals("$name 交给平台嗅探", SoundPlayback.ATTEMPT, p.playback)
            assertEquals("$name", MediaPlayerSupport.UNKNOWN, p.mediaPlayerSupport)
            assertEquals("$name PC 侧结局未知（空文件两边都会失败）", PcOpenOutcome.UNKNOWN, p.pcOutcome)
            assertTrue("$name 放不出来必须报错，不许静默", p.errorsWhenUnplayable)
        }
    }

    @Test
    fun `语料的 txt 按未知扩展名处理 不静默`() {
        // 素材说明文件不是音频；万一被 PLAYSOUND 指到，PC 与 Android 都会在打开时失败 ⇒ CodeEE。
        val p = plan(CORPUS_TXT.single())
        assertEquals(SoundFormat.OTHER, p.format)
        assertEquals(PcSoundReader.MEDIA_FOUNDATION, p.pcReader)
        assertEquals(PcOpenOutcome.UNKNOWN, p.pcOutcome)
        assertEquals(SoundPlayback.ATTEMPT, p.playback)
        assertTrue(p.errorsWhenUnplayable)
    }

    // ------------------------------------------------------------------
    // 2. 大小写变体：每个格式族同判
    // ------------------------------------------------------------------

    @Test
    fun `每个格式族的大小写变体同判`() {
        // Sound.cs:83/87 是 OrdinalIgnoreCase，不区分 ASCII 大小写；表里的每个后缀都必须照抄。
        val families = listOf(
            "a.wav", "a.ogg", "a.mp3", "a.m4a", "a.aac", "a.flac", "a.mp4", "a.3gp",
            "a.mid", "a.midi", "a.rmi", "a.kar", "a.xmf", "a.mxmf", "a.wma",
        )
        for (name in families) {
            val base = plan(name)
            // Locale.ROOT：不让土耳其语等 locale 的 dotless i 影响变体构造（被测代码本身不做 locale 折叠）。
            val variants = listOf(name, name.uppercase(Locale.ROOT), name.lowercase(Locale.ROOT))
            for (variant in variants) {
                val p = plan(variant)
                assertEquals("$variant 格式与 $name 同判", base.format, p.format)
                assertEquals("$variant reader", base.pcReader, p.pcReader)
                assertEquals("$variant PC 结局", base.pcOutcome, p.pcOutcome)
                assertEquals("$variant 支持度", base.mediaPlayerSupport, p.mediaPlayerSupport)
                assertEquals("$variant 处置", base.playback, p.playback)
            }
        }
        // 语料实形再钉一遍：磁盘上的三个大写后缀（对照 SoundFormatPlanTest 的实参侧口径）。
        assertEquals(SoundFormat.MP3, plan("区/vamos!.MP3").format)
        assertEquals(SoundFormat.MP3, plan("区/卡了.MP3").format)
        assertEquals(SoundFormat.MP3, plan("区/操操操.MP3").format)
    }

    // ------------------------------------------------------------------
    // 3. 未知扩展名仍被拦在「不静默」一侧
    // ------------------------------------------------------------------

    @Test
    fun `未知扩展名不白名单 失败即报错`() {
        // 未知后缀既不进白名单（PLAY），也不预先 REJECT：PC 的 MF 与 Android 都按内容嗅探，
        // 「后缀不认识但内容是 mp3」两边都能放。所以口径是 ATTEMPT + errorsWhenUnplayable。
        val unknown = listOf(
            "a.xyz", "a.wave", "a.mp2", "a.oga", "a.opus", "a.amr", "a.rtttl", "a.smf",
            "no-extension-file", "",
        )
        for (name in unknown) {
            val p = plan(name)
            assertNotEquals("$name 不得被静默放行", SoundPlayback.PLAY, p.playback)
            assertEquals("$name 让平台试", SoundPlayback.ATTEMPT, p.playback)
            assertTrue("$name 失败必须报错", p.errorsWhenUnplayable)
            assertEquals("$name", PcSoundReader.MEDIA_FOUNDATION, p.pcReader)
        }
        // 语料外的容器别名（.oga / .opus / .amr / .rtttl 等）目前都归 OTHER。
        // TODO(verify)：MIDI 族别名（.rtttl / .rtx / .ota / .imy / .smf）是否应并入
        // MIDI_SUFFIXES（PC 的 MF 无 SMF 解码器、Android 14 起移除 MIDI 播放路径）。
        // 卡点＝语料 0 命中、且「MediaFoundation 打不开这些后缀」只有平台文档可依，无 C# 行号证据，
        // 擅自 REJECT 会违反 SoundFormatPlan 的结构性不变量（PC 能播却安卓报错）。
        for (name in listOf("a.rtttl", "a.smf", "a.imy", "a.ota", "a.rtx")) {
            assertNotEquals("$name 暂不并入 REJECT", SoundPlayback.REJECT, plan(name).playback)
        }
    }

    // ------------------------------------------------------------------
    // 4. 解析器面：语料四类都不触发格式闸，且大小写实参按磁盘文件判定
    // ------------------------------------------------------------------

    @Test
    fun `语料四类在解析器上都不触发格式闸`() {
        // 语料真实形状的合成目录树（文件名取自普查，不读语料）；open 注入假解码器，不碰 MediaPlayer。
        val sound = temp.newFolder("sound")
        write(sound, "bad-apple-audio.mp3")
        write(sound, "区/卡键.mp3")
        write(sound, "区/vamos!.MP3")
        write(sound, "映姫様素材/K30_素材について.txt")
        write(sound, "游戏bgm/博丽神社/bgm", empty = true)

        val resolver = resolver(sound)
        // ERB 实参口径：TITLE.ERB:132 与 隨時操作存檔.ERB:392 都写大写，磁盘是小写。
        assertNotNull(resolver.resolve("bad-apple-audio.MP3"))
        assertNotNull(resolver.resolve("区/卡键.MP3"))
        assertNotNull(resolver.resolve("区/vamos!.MP3"))
        assertNotNull(resolver.resolve("映姫様素材/K30_素材について.txt"))
        assertNotNull(resolver.resolve("游戏bgm/博丽神社/bgm"))

        assertEquals("语料四类全部都交给 open（没有一类被格式闸拦下）", 5, opened.size)
        // canonicalFile 口径：Windows 上精确命中会保留实参大小写（`bad-apple-audio.MP3`），
        // Linux 上走回落拿的是目录列表项；两边归一后都等于磁盘拼写，双主机同判。
        assertEquals(
            listOf("bad-apple-audio.mp3", "卡键.mp3", "vamos!.MP3", "K30_素材について.txt", "bgm"),
            opened.map { it.canonicalFile.name },
        )
    }

    @Test
    fun `只有 MIDI 族会被格式闸拦下 语料里一个都没有`() {
        // 对照组：同一张表对 .mid 抛异常（FileSoundSourceResolver 的 REJECT 分支）；
        // 语料 52 个文件名没有一个落进这族（见 `语料普查读数与判定表一一对齐`）。
        val sound = temp.newFolder("sound")
        write(sound, "theme.mid")
        val corpusPlans = CORPUS_NAMES.map { plan(it) }

        assertTrue(
            "语料不得命中 MIDI 族",
            corpusPlans.none { it.format == SoundFormat.MIDI || it.playback == SoundPlayback.REJECT },
        )
        val e = assertThrows(UnsupportedSoundFormatException::class.java) {
            resolver(sound).resolve("theme.mid")
        }
        assertEquals(SoundFormat.MIDI, e.plan.format)
        assertTrue("判定为放不出来就不该碰解码器", opened.isEmpty())
    }

    private companion object {

        /** 30 个磁盘小写 `.mp3`（普查名单，顺序与 `find` 输出一致）。 */
        val CORPUS_MP3_LOWER = listOf(
            "bad-apple-audio.mp3",
            "区/卡键.mp3",
            "小图书馆/不要再戳了.mp3",
            "小图书馆/住手.mp3",
            "小图书馆/唔…….mp3",
            "小图书馆/姆Q.mp3",
            "小图书馆/笨蛋.mp3",
            "小图书馆/让我安静一会.mp3",
            "小图书馆/高兴了吗.mp3",
            "映姫様素材/K30_バイブ弱.mp3",
            "映姫様素材/K30_バイブ強.mp3",
            "映姫様素材/K30_バイブ継続.mp3",
            "映姫様素材/K30_フェラ初1.mp3",
            "映姫様素材/K30_フェラ初2.mp3",
            "映姫様素材/K30_フェラ噛1.mp3",
            "映姫様素材/K30_フェラ噛2.mp3",
            "映姫様素材/K30_フェラ始1.mp3",
            "映姫様素材/K30_フェラ始2.mp3",
            "映姫様素材/K30_フェラ早1.mp3",
            "映姫様素材/K30_フェラ早2.mp3",
            "映姫様素材/K30_フェラ普1.mp3",
            "映姫様素材/K30_フェラ普2.mp3",
            "映姫様素材/K30_フェラ朝1.mp3",
            "映姫様素材/K30_フェラ朝2.mp3",
            "映姫様素材/K30_フェラ遅1.mp3",
            "映姫様素材/K30_フェラ遅2.mp3",
            "映姫様素材/K30_フェラ離1.mp3",
            "映姫様素材/K30_フェラ離2.mp3",
            "映姫様素材/K30_フェラ飲1.mp3",
            "映姫様素材/K30_フェラ飲2.mp3",
        )

        /** 3 个磁盘大写 `.MP3`。 */
        val CORPUS_MP3_UPPER = listOf(
            "区/vamos!.MP3",
            "区/卡了.MP3",
            "区/操操操.MP3",
        )

        /** 18 个 0 字节、无扩展名的 BGM 占位文件。 */
        val CORPUS_EXTENSIONLESS = listOf(
            "三途川_冥界", "人间之里", "博丽神社", "参道", "命莲寺", "地灵殿",
            "地狱遗址", "太阳花田", "妖怪之山山顶", "妖怪之山山麓", "旧地狱", "月都",
            "神灵庙", "红魔馆", "迷途竹林", "隙间待客室", "魔法森林", "魔界",
        ).map { "游戏bgm/$it/bgm" }

        /** 1 个 `.txt` 素材说明。 */
        val CORPUS_TXT = listOf("映姫様素材/K30_素材について.txt")

        /** 普查全量 52 个文件名。 */
        val CORPUS_NAMES = CORPUS_MP3_LOWER + CORPUS_MP3_UPPER + CORPUS_EXTENSIONLESS + CORPUS_TXT
    }
}
