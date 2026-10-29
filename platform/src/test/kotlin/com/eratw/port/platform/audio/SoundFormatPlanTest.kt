package com.eratw.port.platform.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「格式 → 处理策略」判定表（m11e-audio-format ①）。
 *
 * C# 依据逐条：
 * - PC 的格式分流只有三分支、且判定是 `EndsWith(OrdinalIgnoreCase)`：Sound.cs:83-96
 *   （`.wav` → WaveFileReader、`.ogg` → VorbisWaveReader、其余 → MediaFoundationReader）。
 * - `.mid` 落在 else ⇒ MF 无 SMF 解码器 ⇒ 构造期抛 ⇒ 裸 catch 转 CodeEE：
 *   Sound.cs:91-96 → FunctionIdentifier.cs:2647-2650（PLAYSOUND）、:2699-2702（PLAYBGM），
 *   文案 Lang.cs:3994。
 * - 文件不存在才静默：FunctionIdentifier.cs:2625-2628、:2694-2697。
 *
 * 本文件不碰 Android：判定表是纯数据 + 纯字符串比较。
 */
class SoundFormatPlanTest {

    private fun plan(name: String) = SoundFormatTable.planFor(name)

    @Test
    fun `逐格式判定表`() {
        // .wav / .ogg 是 PC 的两个特例分支（Sound.cs:83-90），MediaPlayer 也都原生支持
        plan("a.wav").let {
            assertEquals(SoundFormat.WAV, it.format)
            assertEquals(PcSoundReader.WAVE_FILE, it.pcReader)
            assertEquals(PcOpenOutcome.PLAYS, it.pcOutcome)
            assertEquals(MediaPlayerSupport.YES, it.mediaPlayerSupport)
            assertEquals(SoundPlayback.PLAY, it.playback)
        }
        plan("a.ogg").let {
            assertEquals(SoundFormat.OGG, it.format)
            assertEquals(PcSoundReader.VORBIS, it.pcReader)
            assertEquals(SoundPlayback.PLAY, it.playback)
        }
        // .mp3 与 .m4a 族：PC 都落 else 分支的 MediaFoundation（Sound.cs:91-96）
        for (name in listOf("a.mp3", "a.m4a", "a.aac", "a.flac", "a.mp4", "a.3gp")) {
            val p = plan(name)
            assertEquals("$name 走 MF", PcSoundReader.MEDIA_FOUNDATION, p.pcReader)
            assertEquals("$name PC 能播", PcOpenOutcome.PLAYS, p.pcOutcome)
            assertEquals("$name MediaPlayer 支持", SoundPlayback.PLAY, p.playback)
        }
        // .mid 族：PC 也打不开（MF 无 SMF）⇒ 两边都报错，不是静默
        for (name in listOf("a.mid", "a.midi", "a.rmi", "a.kar", "a.xmf", "a.mxmf")) {
            val p = plan(name)
            assertEquals("$name 落 MF", PcSoundReader.MEDIA_FOUNDATION, p.pcReader)
            assertEquals("$name 在 PC 上构造期就炸", PcOpenOutcome.FAILS_AT_OPEN, p.pcOutcome)
            assertEquals("$name 不交给 MediaPlayer", MediaPlayerSupport.NO, p.mediaPlayerSupport)
            assertEquals("$name ⇒ CodeEE", SoundPlayback.REJECT, p.playback)
            assertTrue(p.errorsWhenUnplayable)
        }
        // .wma：PC 能播、MediaPlayer 不能 ⇒ 真差异，走 ATTEMPT（能放就放，放不了 CodeEE）
        plan("a.wma").let {
            assertEquals(SoundFormat.OTHER, it.format)
            assertEquals(PcOpenOutcome.PLAYS, it.pcOutcome)
            assertEquals(SoundPlayback.ATTEMPT, it.playback)
            assertTrue(it.errorsWhenUnplayable)
        }
        // 未知扩展名 / 无扩展名：两边都靠内容嗅探
        for (name in listOf("a.xyz", "bgm", "")) {
            assertEquals("$name 交给平台试", SoundPlayback.ATTEMPT, plan(name).playback)
            assertEquals(PcSoundReader.MEDIA_FOUNDATION, plan(name).pcReader)
        }
        assertEquals(SoundFormat.NONE, plan("bgm").format)
        assertEquals(SoundFormat.OTHER, plan("a.xyz").format)
    }

    @Test
    fun `后缀判定是 EndsWith 而不是取最后一段`() {
        // Sound.cs:83、:87 用的是 filename.EndsWith(...)：判的是「整串以它结尾」，
        // 不是「最后一个点之后的扩展名」。两者对多重后缀的文件给出不同答案。
        assertEquals("结尾是 .mp3 ⇒ MF 分支", SoundFormat.MP3, plan("a.wav.mp3").format)
        assertEquals(PcSoundReader.MEDIA_FOUNDATION, plan("a.wav.mp3").pcReader)
        assertEquals("结尾是 .wav ⇒ WaveFileReader", SoundFormat.WAV, plan("a.mp3.wav").format)
        assertEquals(PcSoundReader.WAVE_FILE, plan("a.mp3.wav").pcReader)
        assertEquals(SoundFormat.OGG, plan("a.mp3.ogg").format)
        // 只有「.wav」这个后缀算 wav；「.wave」在 PC 上不匹配 EndsWith ⇒ 落 MF
        assertEquals(PcSoundReader.MEDIA_FOUNDATION, plan("a.wave").pcReader)
        assertEquals(SoundFormat.OTHER, plan("a.wave").format)
    }

    @Test
    fun `大小写不敏感 语料的 MP3 实参照样落 MP3`() {
        // OrdinalIgnoreCase（Sound.cs:83、:87）；语料 `TITLE.ERB:143` 的 bad-apple-audio.MP3
        // 与 M_KOJO_K54_2_事件系.ERB:200-231 的 *.MP3 都靠这条。
        assertEquals(SoundFormat.MP3, plan("bad-apple-audio.MP3").format)
        assertEquals(SoundFormat.WAV, plan("A.WAV").format)
        assertEquals(SoundFormat.OGG, plan("X.Ogg").format)
        assertEquals(SoundFormat.MIDI, plan("X.Mid").format)
        assertEquals(SoundPlayback.REJECT, plan("X.MIDI").playback)
    }

    @Test
    fun `无扩展名走 ATTEMPT 而不是 REJECT`() {
        // 语料 sound/游戏bgm/<区域>/bgm 是 0 字节无扩展名占位文件：PC 的 MF 与 Android 的
        // MediaPlayer 都按内容嗅探，空文件两边都失败 ⇒ ATTEMPT 后走 CodeEE，与 PC 同判。
        // 若将来换成真的无扩展名 mp3，两边也都能嗅探出来 —— 所以不能 REJECT。
        val p = plan("bgm")
        assertEquals(SoundFormat.NONE, p.format)
        assertEquals(SoundPlayback.ATTEMPT, p.playback)
        assertTrue("放不出来必须报错，不许静默", p.errorsWhenUnplayable)
    }

    @Test
    fun `REJECT 只出现在 PC 也打不开的格式上`() {
        // 结构性不变量：REJECT 会让脚本收到 CodeEE。若某个格式 PC 能播而我们 REJECT，
        // 就成了「PC 有声、安卓报错」的真差异，必须显式登记（走 ATTEMPT）而不是混进表里。
        val names = listOf(
            "a.wav", "a.ogg", "a.mp3", "a.m4a", "a.flac", "a.mid", "a.midi",
            "a.wma", "a.xyz", "bgm", "", "A.WAV", "a.wav.mp3",
        )
        for (name in names) {
            val p = plan(name)
            if (p.playback == SoundPlayback.REJECT) {
                assertEquals(
                    "$name 被 REJECT 就必须是 PC 也打不开的格式",
                    PcOpenOutcome.FAILS_AT_OPEN,
                    p.pcOutcome,
                )
            }
        }
    }

    @Test
    fun `处置策略与 MediaPlayer 支持度一一对应 没有第四种静默`() {
        val names = listOf("a.wav", "a.ogg", "a.mp3", "a.mid", "a.wma", "a.xyz", "bgm")
        for (name in names) {
            val p = plan(name)
            val expected = when (p.mediaPlayerSupport) {
                MediaPlayerSupport.YES -> SoundPlayback.PLAY
                MediaPlayerSupport.NO -> SoundPlayback.REJECT
                MediaPlayerSupport.UNKNOWN -> SoundPlayback.ATTEMPT
            }
            assertEquals("$name 的策略必须由支持度唯一派生", expected, p.playback)
            // 除 PLAY 之外的两条都必须「失败即报错」，杜绝 EXISTSOUND=1 但没声音
            assertEquals(p.playback != SoundPlayback.PLAY, p.errorsWhenUnplayable)
        }
        assertFalse("白名单格式不需要报错路径", plan("a.mp3").errorsWhenUnplayable)
    }
}
