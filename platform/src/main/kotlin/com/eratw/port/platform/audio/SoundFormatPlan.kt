package com.eratw.port.platform.audio

import java.io.File

/**
 * 音频格式族 —— 只按**文件名后缀**分类，不做内容嗅探（PC 侧也不做：
 * `Sound.play` 只 `EndsWith(".wav")` / `EndsWith(".ogg")`，其余全丢给 MediaFoundation，
 * Sound.cs:83-96）。
 */
enum class SoundFormat {
    /** `.wav` —— PC 走 `WaveFileReader`（Sound.cs:83-86）。 */
    WAV,

    /** `.ogg` —— PC 走 `VorbisWaveReader`（Sound.cs:87-90）。 */
    OGG,

    /** `.mp3` —— PC 落到 else 分支的 `MediaFoundationReader`（Sound.cs:91-96）。 */
    MP3,

    /** `.m4a` / `.aac` / `.flac` / `.mp4` / `.3gp` —— 同样是 MediaFoundation（Sound.cs:91-96）。 */
    MPEG4_AUDIO,

    /**
     * `.mid` / `.midi` / `.rmi` / `.kar` / `.xmf` / `.mxmf` —— PC 也落到 MediaFoundation，
     * 而 MF 没有 SMF（标准 MIDI 文件）解码器 ⇒ 构造期抛异常 ⇒ 裸 catch 转 CodeEE
     * （Sound.cs:91-96 → FunctionIdentifier.cs:2647-2650、:2699-2702）。
     */
    MIDI,

    /** 有扩展名但不在表里（`.wma` 等）。 */
    OTHER,

    /** 无扩展名 —— 语料 `sound/游戏bgm/<区域>/bgm` 就是这种 0 字节占位文件。 */
    NONE,
}

/** PC 侧按后缀选中的 reader（Sound.cs:83-96 的三个分支）。 */
enum class PcSoundReader {
    /** `WaveFileReader`（Sound.cs:85）。 */
    WAVE_FILE,

    /** `VorbisWaveReader`（Sound.cs:89，NAudio.Vorbis / NVorbis）。 */
    VORBIS,

    /** `MediaFoundationReader`（Sound.cs:93-95，`RequestFloatOutput = true`）。 */
    MEDIA_FOUNDATION,
}

/** PC 侧把文件交给 reader 之后的结局 —— 决定移植侧「能不能静默」。 */
enum class PcOpenOutcome {
    /** reader 能打开并解码。 */
    PLAYS,

    /** reader 构造期抛异常 ⇒ 语句层裸 catch ⇒ CodeEE（不是静默）。 */
    FAILS_AT_OPEN,

    /** 取决于内容（MF 按内容嗅探；后缀不给信息）。 */
    UNKNOWN,
}

/** `android.media.MediaPlayer` 对该格式的支持度（按 AOSP 支持的容器/编解码表）。 */
enum class MediaPlayerSupport {
    /** 稳定支持。 */
    YES,

    /** 不支持 —— 绝不交给 MediaPlayer（否则行为随 ROM 的 MIDI 合成器漂移）。 */
    NO,

    /** 不确定，让平台自己判（失败即上抛 ⇒ CodeEE）。 */
    UNKNOWN,
}

/**
 * 打开一个已存在文件时的**唯一**处置策略。
 *
 * 三者覆盖「存在但放不出来」的全部可能，缺一不可 —— 缺了就会退回「EXISTSOUND=1 但没声音」
 * 那种自相矛盾：
 * - [PLAY]：白名单内，直接开；
 * - [ATTEMPT]：让平台试，失败即异常（上层转 CodeEE，与 PC 的裸 catch 同判）；
 * - [REJECT]：**不**交给平台，直接抛 [UnsupportedSoundFormatException]。
 *
 * 没有任何第四种「悄悄不播」。
 */
enum class SoundPlayback {
    PLAY,
    ATTEMPT,
    REJECT,
}

/**
 * 「格式 → 处理策略」的判定结果（纯数据，无 Android 依赖，可单测）。
 *
 * 四个字段分别回答四个问题：这是什么格式、PC 用什么 reader、PC 打开后会不会炸、
 * MediaPlayer 支不支持。处置策略由 [mediaPlayerSupport] 唯一派生（见 [playback]），
 * 所以「支持度」和「策略」不可能写岔。
 *
 * 结构性不变量（构造期 [init] 强校验）：**只有在 PC 也打不开时才 REJECT**。
 * 理由：REJECT 会让脚本收到 CodeEE（FunctionIdentifier.cs:2647-2650），这是 PC 口径；
 * 若某格式 PC 能播而移植侧 REJECT，就成了「PC 有声、安卓报错」的真差异，必须显式登记
 * 而不是靠表里的一个枚举悄悄溜过去 —— 这类格式（目前只有 `.wma`）走 [SoundPlayback.ATTEMPT]。
 */
data class SoundFormatPlan(
    val format: SoundFormat,
    val pcReader: PcSoundReader,
    val pcOutcome: PcOpenOutcome,
    val mediaPlayerSupport: MediaPlayerSupport,
) {
    init {
        require(playback != SoundPlayback.REJECT || pcOutcome == PcOpenOutcome.FAILS_AT_OPEN) {
            "REJECT 只允许用在 PC 也打不开的格式上：$format / $pcReader / $pcOutcome"
        }
    }

    /** 处置策略；与 [mediaPlayerSupport] 一一对应，不单独存字段。 */
    val playback: SoundPlayback
        get() = when (mediaPlayerSupport) {
            MediaPlayerSupport.YES -> SoundPlayback.PLAY
            MediaPlayerSupport.NO -> SoundPlayback.REJECT
            MediaPlayerSupport.UNKNOWN -> SoundPlayback.ATTEMPT
        }

    /** 是否会因为「存在但放不出来」而报错（CodeEE），而不是静默。 */
    val errorsWhenUnplayable: Boolean
        get() = playback != SoundPlayback.PLAY
}

/**
 * 「文件名 → [SoundFormatPlan]」的判定表，**移植侧格式语义的唯一真相**。
 *
 * ## PC 口径（Sound.cs:83-96，逐字）
 * ```
 * if (filename.EndsWith(".wav", OrdinalIgnoreCase))  WaveFileReader
 * else if (filename.EndsWith(".ogg", OrdinalIgnoreCase)) VorbisWaveReader
 * else MediaFoundationReader
 * ```
 * 三点必须照抄：
 * 1. 判定是 `EndsWith` 而不是「取最后一个点之后的后缀」：`a.wav.mp3` 落 MP3、`a.mp3.wav` 落 WAV。
 *    本表用 [String.endsWith] 的 `ignoreCase = true` 直接复刻，不做 `substringAfterLast('.')`。
 * 2. 大小写不敏感（`OrdinalIgnoreCase`）；语料里确实有 `.MP3` 实参
 *    （`ERB/TITLE.ERB:143`、`M_KOJO_K54_2_事件系.ERB:200-231`）。
 * 3. 只有 `.wav` / `.ogg` 是特例，**其余（含 `.mp3`、`.mid`、无扩展名）一律 MediaFoundation**。
 *    所以「.mid 在 PC 上是哪种失败」不是猜的：MF 没有 SMF 解码器，构造期抛异常，
 *    `PLAYSOUND` / `PLAYBGM` 的裸 catch 把它转成 CodeEE `非対応のサウンドファイルが指定されました`
 *    （FunctionIdentifier.cs:2647-2650、:2699-2702，文案 Lang.cs:3994）。
 *
 * ## 为什么不给 `.mid` 一个「试试看」的机会
 * MediaPlayer 的 MIDI 能力随 ROM 的 Sonivox 合成器有无而漂移（AOSP 从 14 起移除了
 * 平台 MIDI 播放路径）。交给它试的结果是「同一份存档在 A 机出声、B 机报错」，
 * 而 PC 是**确定报错**。REJECT 是唯一能同时满足「不静默」与「不随 ROM 漂移」的选择。
 *
 * ## `.wma` 是真差异，不是本表的疏漏
 * MF 能解 WMA（PC 有声），MediaPlayer 不能。表里给 [SoundPlayback.ATTEMPT]：能放就放、
 * 放不了就 CodeEE。**不**给 REJECT，因为那会在 PC 有声的格式上主动报错（见
 * [SoundFormatPlan] 的结构性不变量）。
 *
 * ## 无扩展名
 * 语料 `sound/游戏bgm/<区域>/bgm` 是 **0 字节**无扩展名占位文件。PC 的 MF 与 Android 的
 * MediaPlayer 都按内容嗅探，两者都在空文件上失败 ⇒ ATTEMPT 之后走 CodeEE，与 PC 同判；
 * 若将来换成真的无扩展名 mp3，两边也都能靠嗅探播出来。这正是 ATTEMPT 而不是 REJECT 的理由。
 */
object SoundFormatTable {

    /** `.wav`：PC `WaveFileReader`（Sound.cs:83-86），MediaPlayer 支持 PCM/WAVE。 */
    private val WAV_PLAN = SoundFormatPlan(
        format = SoundFormat.WAV,
        pcReader = PcSoundReader.WAVE_FILE,
        pcOutcome = PcOpenOutcome.PLAYS,
        mediaPlayerSupport = MediaPlayerSupport.YES,
    )

    /** `.ogg`：PC `VorbisWaveReader`（Sound.cs:87-90），MediaPlayer 支持 Vorbis/Opus 容器。 */
    private val OGG_PLAN = SoundFormatPlan(
        format = SoundFormat.OGG,
        pcReader = PcSoundReader.VORBIS,
        pcOutcome = PcOpenOutcome.PLAYS,
        mediaPlayerSupport = MediaPlayerSupport.YES,
    )

    /** `.mp3`：PC 落 MF（Sound.cs:91-96），MediaPlayer 原生支持。 */
    private val MP3_PLAN = SoundFormatPlan(
        format = SoundFormat.MP3,
        pcReader = PcSoundReader.MEDIA_FOUNDATION,
        pcOutcome = PcOpenOutcome.PLAYS,
        mediaPlayerSupport = MediaPlayerSupport.YES,
    )

    /** `.m4a` 族：PC 落 MF，MediaPlayer 支持 AAC/FLAC 与 mp4 容器。 */
    private val MPEG4_PLAN = SoundFormatPlan(
        format = SoundFormat.MPEG4_AUDIO,
        pcReader = PcSoundReader.MEDIA_FOUNDATION,
        pcOutcome = PcOpenOutcome.PLAYS,
        mediaPlayerSupport = MediaPlayerSupport.YES,
    )

    /** `.mid` 族：PC 的 MF 打不开（无 SMF 解码器），MediaPlayer 不保证。两边都报错。 */
    private val MIDI_PLAN = SoundFormatPlan(
        format = SoundFormat.MIDI,
        pcReader = PcSoundReader.MEDIA_FOUNDATION,
        pcOutcome = PcOpenOutcome.FAILS_AT_OPEN,
        mediaPlayerSupport = MediaPlayerSupport.NO,
    )

    /** `.wma`：PC 能播、MediaPlayer 不能 —— 真差异，交给平台试（见类头）。 */
    private val WMA_PLAN = SoundFormatPlan(
        format = SoundFormat.OTHER,
        pcReader = PcSoundReader.MEDIA_FOUNDATION,
        pcOutcome = PcOpenOutcome.PLAYS,
        mediaPlayerSupport = MediaPlayerSupport.UNKNOWN,
    )

    /** 未知扩展名 / 无扩展名：两边都靠内容嗅探，结果未知。 */
    private fun unknownPlan(format: SoundFormat) = SoundFormatPlan(
        format = format,
        pcReader = PcSoundReader.MEDIA_FOUNDATION,
        pcOutcome = PcOpenOutcome.UNKNOWN,
        mediaPlayerSupport = MediaPlayerSupport.UNKNOWN,
    )

    private val OTHER_PLAN = unknownPlan(SoundFormat.OTHER)
    private val NONE_PLAN = unknownPlan(SoundFormat.NONE)

    private val WAV_SUFFIXES = arrayOf(".wav")
    private val OGG_SUFFIXES = arrayOf(".ogg")
    private val MP3_SUFFIXES = arrayOf(".mp3")
    private val MPEG4_SUFFIXES = arrayOf(".m4a", ".aac", ".flac", ".mp4", ".3gp")
    private val MIDI_SUFFIXES = arrayOf(".mid", ".midi", ".rmi", ".kar", ".xmf", ".mxmf")
    private val WMA_SUFFIXES = arrayOf(".wma")

    /**
     * 文件名 → 判定。入参应当是**磁盘上的真实文件名**（[File.getName]），
     * 不是 ERB 里的实参 —— 大小写回落之后两者可能不同（`x.MP3` 命中 `x.mp3`）。
     */
    fun planFor(name: String): SoundFormatPlan = when {
        hasAnySuffix(name, WAV_SUFFIXES) -> WAV_PLAN
        hasAnySuffix(name, OGG_SUFFIXES) -> OGG_PLAN
        hasAnySuffix(name, MP3_SUFFIXES) -> MP3_PLAN
        hasAnySuffix(name, MPEG4_SUFFIXES) -> MPEG4_PLAN
        hasAnySuffix(name, MIDI_SUFFIXES) -> MIDI_PLAN
        hasAnySuffix(name, WMA_SUFFIXES) -> WMA_PLAN
        name.substringAfterLast('.', "").isEmpty() -> NONE_PLAN
        else -> OTHER_PLAN
    }

    /**
     * `EndsWith(..., OrdinalIgnoreCase)` 的逐字复刻（Sound.cs:83、:87）。
     * 不做后缀解析，所以 `a.wav.mp3` 判成 `.mp3`、`a.mp3.wav` 判成 `.wav`。
     */
    private fun hasAnySuffix(name: String, suffixes: Array<String>): Boolean =
        suffixes.any { name.endsWith(it, ignoreCase = true) }
}

/**
 * 文件存在、但格式在移植侧被确定判定为「放不出来」时抛出的异常（[SoundPlayback.REJECT]）。
 *
 * 它必须**抛**而不是返回 null：返回 null 在 [com.eratw.port.core.audio.SoundSourceResolver]
 * 的约定里是「文件不存在 ⇒ 静默不播」（FunctionIdentifier.cs:2625-2628），那正好就是
 * 「EXISTSOUND=1 却没声音」的自相矛盾。抛出后由 `TableSoundSink` 转成 CodeEE
 * `非対応のサウンドファイルが指定されました`（FunctionIdentifier.cs:2647-2650，Lang.cs:3994），
 * 与 PC 对 `.mid` 的处置逐字一致。
 *
 * TODO(verify)：`EXISTSOUND` 仍按 `File.Exists` 返回 1（FunctionMethodCreator.cs:8214-8218，
 * 本波无权改 HostMethods）。PC 上 `.mid` 同样是 EXISTSOUND=1 + PLAYSOUND 报错，故口径一致；
 * 若产品要「放不出来就当不存在」，需要在 EXISTSOUND 侧接同一张表 —— 那是组长接线项。
 */
class UnsupportedSoundFormatException(
    val file: File,
    val plan: SoundFormatPlan,
) : RuntimeException(
    "非対応のサウンドファイルが指定されました（${file.name}：${plan.format}）",
)
