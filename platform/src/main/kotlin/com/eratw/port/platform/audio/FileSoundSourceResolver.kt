package com.eratw.port.platform.audio

import com.eratw.port.core.audio.ChannelDecoder
import com.eratw.port.core.audio.SoundSourceResolver
import com.eratw.port.core.host.SoundResourcePaths
import java.io.File

/**
 * 文件音源：把 ERB 里的资源名解析成 `<soundDir>/<name>`，再交给注入的打开函数。
 *
 * 原版是 `Path.GetFullPath(".\\sound\\" + name)` 之后 `File.Exists`，存在才建解码器
 * （FunctionIdentifier.cs:2622-2628 的 PLAYSOUND、:2691-2696 的 PLAYBGM；建解码器见
 * Sound.cs:83-96 按扩展名选 WaveFileReader / VorbisWaveReader / MediaFoundationReader）。
 * 三个判定与移植侧的落点：
 *
 * 1. **文件不存在 ⇒ 返回 null（静默不播）**：PLAYSOUND 直接 `return`（:2625-2628），
 *    PLAYBGM 连 play 都不调（:2694-2697）。调用方 [com.eratw.port.core.audio.SoundChannelTable]
 *    把 null 当「文件不存在」处理，不建声道、不改状态。
 * 2. **打开/解码失败 ⇒ 异常上抛**：原版把「查存在 + 建流 + 播放」整段包在裸 catch 里，
 *    任何异常转 CodeEE `非対応のサウンドファイルが指定されました`（:2647-2650、:2699-2702，
 *    文案见 Lang.cs:3994）。转换点在上层的 `TableSoundSink`，本类只负责让异常穿过去。
 * 3. **每次解析都新建解码器**：原版每次 play 都重新打开文件（Sound.cs:83-96），且 play 开头
 *    先 stop 旧流（:82），所以两条播放从不共用流对象 —— 本类不做缓存，调用方负责 release。
 *
 * ## 目录基准
 * 原版 `".\\sound\\"` 相对进程工作目录（游戏根），即 `<gameRoot>/sound/`；
 * 真机接线用 [forGameRoot]。这个名字必须与 `BuiltinHost.soundDir`（EXISTSOUND 的根，
 * FunctionMethodCreator.cs:8214-8218）指向同一个目录，否则 EXISTSOUND 与实际播放会分叉。
 *
 * ## 与 Windows 原版的已知差异（安全边界，按不存在处理）
 * 名字里的 `.` / `..` 先按 Windows 的 `Path.GetFullPath` 口径折叠：只要折叠后的目标仍落在
 * [soundDir] 之内就正常解析（`sub/../a.mp3` → `a.mp3`，不要求 `sub` 存在）；
 * 一旦**逃出**根目录（`../secret.mp3`、根相对路径、盘符路径）一律返回 null，不打开任何文件。
 * 原版在 Windows 上会真的去读根目录之外的文件；Android 侧不放行这条通道（语料不需要它，
 * 且应用私有目录之外的读取在 scoped storage 下也不可靠）。
 * TODO(verify)：是否需要像原版一样放行「逃出根目录」的相对路径。卡点＝没有语料证据
 * （全量 ERB 的 PLAYSOUND / PLAYBGM 实参都落在 `sound/` 下的子目录），且放行会扩大攻击面；
 * 若后续发现真实用例，改 [locate] 的折叠逻辑并在 BOARD 登记。
 *
 * ## 文件名大小写
 * 解析走 [SoundResourcePaths.resolve]（与 core 侧 EXISTSOUND 同一个判定）：原名精确命中优先，
 * 落空才逐段做大小写不敏感回落，等价 Windows NTFS 上 `File.Exists` 的读数。语料两侧不一致是
 * 既成事实（`TITLE.ERB:143` 的 `PLAYSOUND "bad-apple-audio.MP3"` 对磁盘 `sound/bad-apple-audio.mp3`，
 * `054 Patchouli [パチュリー]/小图书馆/M_KOJO_K54_2_事件系.ERB:200-231` 的 `*.MP3` 同理），
 * 不做回落就会在真机静默不播。
 *
 * ## 格式闸（第四条判定，`m11e-audio-format` 追加）
 * 文件存在**不等于**能出声。PC 的格式分流只有三分支（Sound.cs:83-96），`.mid` 会掉进
 * MediaFoundation 并在构造期炸掉，被裸 catch 转成 CodeEE（FunctionIdentifier.cs:2647-2650）。
 * 移植侧对「MediaPlayer 确定不支持」的格式（目前只有 `.mid` 族）在 [resolve] 里**直接抛**
 * [UnsupportedSoundFormatException]，绝不返回 null —— null 的约定是「文件不存在 ⇒ 静默」，
 * 那会造出「EXISTSOUND=1 但没声音」的自相矛盾。判定表见 [SoundFormatTable]。
 *
 * @param soundDir `sound/` 目录（不存在也合法：所有解析都返回 null）。
 * @param open 建解码器；文件存在才会被调用，异常原样上抛（见上）。返回 null 表示后端
 *   明确判定「打不开」，本类按不存在处理（调用方不再区分 null 的两种来源）。
 */
class FileSoundSourceResolver(
    soundDir: File,
    private val open: (File) -> ChannelDecoder?,
) : SoundSourceResolver {

    /** 资源根；转绝对路径保证交给 [open] 的文件路径可直接给平台播放器（纯字符串运算，不触盘）。 */
    private val rootDir: File = soundDir.absoluteFile

    /**
     * 解析并打开。返回 null = 文件不存在（静默）；格式被判定为放不出来时抛
     * [UnsupportedSoundFormatException]；[open] 自身的异常原样上抛。
     *
     * 交给 [open] 的是**折叠过 `.` / `..` 的绝对路径**，不是原始名字拼接的结果。
     */
    override fun resolve(name: String): ChannelDecoder? {
        val file = locate(name) ?: return null
        // 格式按**磁盘上的真实文件名**判定：大小写回落之后它与 ERB 实参可能不同，
        // 而真正交给播放器的是这个文件（Sound.cs:83-96 判的也是最终传进 play 的路径）。
        val plan = SoundFormatTable.planFor(file.name)
        if (plan.playback == SoundPlayback.REJECT) {
            throw UnsupportedSoundFormatException(file, plan)
        }
        return open(file)
    }

    /** 名字 → 存在的文件；不存在 / 越界 / 非法路径一律 null（判定与 EXISTSOUND 同源）。 */
    private fun locate(name: String): File? = SoundResourcePaths.resolve(rootDir, name)

    companion object {
        /** 原版拼接的前缀 `.\sound\`（FunctionIdentifier.cs:2622、:2691）。 */
        const val SOUND_DIR_NAME: String = SoundResourcePaths.SOUND_DIR_NAME

        /** 以游戏根（`Program.ExeDir` 口径）建解析器：根下的 `sound/`。 */
        fun forGameRoot(gameRoot: File, open: (File) -> ChannelDecoder?): FileSoundSourceResolver =
            FileSoundSourceResolver(File(gameRoot, SOUND_DIR_NAME), open)
    }
}
