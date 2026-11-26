package com.eratw.port.android

import android.os.Handler
import android.os.HandlerThread
import com.eratw.port.core.audio.ChannelDecoder
import com.eratw.port.core.audio.SoundChannel
import com.eratw.port.core.mod.ModAudioSink
import com.eratw.port.platform.audio.MediaPlayerChannelDecoder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * mod 音频播放汇的宿主实现（Lane AN；RT2 资源注入第二刀）。
 *
 * ## 执行器
 *
 * 复用 platform 既有封装 [MediaPlayerChannelDecoder]（`onStarted`/`onVolume`/`onRepeat`/
 * `release` 的 MediaPlayer 状态机都在里面），本类只负责「字节 → 临时文件 → 后台线程播放」，
 * 不重写 decoder 语义。
 *
 * **VM 线程绝不被阻塞**：`MediaPlayer` 的 `setDataSource`/`prepare`/`start` 全在后台线程
 * （[worker]）。这里默认用 `HandlerThread` 而不是裸线程池：MediaPlayer 需要创建线程有
 * `Looper`（完成回调也走它），在 VM 线程创建会绑到主线程或直接抛异常。
 *
 * ## 降级纪律
 *
 * 缺字节 / 空字节 / 临时文件 IO 失败 / decoder 构造或播放异常 —— 一律 WARN + `false`，
 * 绝不把异常抛回 mod（`PackageAudioApi` 还会再兜一层 `runCatching`）。日志是取证面
 * （`logSink`），不是玩家可见文案。
 *
 * ## 共存取舍
 *
 * 每条声音 = 一条独立 MediaPlayer，**不进**引擎 `SoundChannelTable`：不抢引擎 10 声道与
 * Bgm、不受引擎 STOPSOUND/BGMCONTROL 影响；[stop] 只停本汇自己的声音，不静音引擎。
 *
 * ## 生命周期
 *
 * 每次 `play` 顶掉上一条（mod 音频面只承诺「当前一条」语义，`stop()` 停当前）；会话收口
 * 由 `ModAudioWiring.uninstall` 调 [stop]；惰性临时文件在停止/自然播完后删除。
 *
 * @param logSink 装配日志面（WARN 取证；与宿主其余 logSink 同一条）。
 * @param workDir 临时音频文件目录（缺省放进程临时目录下的独立子目录）。
 * @param createDecoder decoder 工厂（测试注入假实现；缺省 = platform 真封装）。
 * @param worker 后台执行器（测试注入同步执行器；缺省 = 惰性 daemon HandlerThread）。
 */
internal class ModAudioSinkImpl(
    private val logSink: (String) -> Unit,
    private val workDir: File = File(System.getProperty("java.io.tmpdir") ?: ".", "eratw-mod-audio"),
    private val createDecoder: (File) -> ChannelDecoder = { file -> MediaPlayerChannelDecoder(file) },
    private val worker: Executor = LazyAudioExecutor(),
) : ModAudioSink {

    /** 播放代际：`play`/`stop` 各自递增；后台任务开工前先比对，晚到的旧任务直接丢弃。 */
    private val generation = AtomicLong()

    private val lock = Any()

    /** 当前在播（或正在后台构造）的一条；`null` = 无声。 */
    private var active: Playing? = null

    override fun play(packageId: String, bytes: ByteArray, loop: Boolean, volume: Float): Boolean {
        if (bytes.isEmpty()) {
            safeLog(logSink, "mod.audio 资源为空，忽略 play（$packageId）")
            return false
        }
        // AudioApi KDoc 承诺「越界自动夹取、绝不抛」。Float.NaN 既不大于 1 也不小于 0，
        // coerceIn 对它原样返回，若透传到 MediaPlayer.setVolume 会抛 IllegalArgumentException
        // （违背降级纪律）。在入口把 NaN 归一为静音、±Inf 与越界夹到 0..1，契约在此处闭合。
        val safeVolume = if (volume.isNaN()) 0f else volume.coerceIn(0f, 1f)
        val ticket = generation.incrementAndGet()
        return try {
            worker.execute { startPlayback(ticket, packageId, bytes, loop, safeVolume) }
            true
        } catch (expected: RejectedExecutionException) {
            safeLog(logSink, "mod.audio 播放线程已停，忽略 play（$packageId）：${expected.message}")
            false
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        val current = takeActive() ?: return
        dispatch { release(current) }
    }

    /** 后台开工：顶掉旧声 → 写临时文件 + 建 decoder → 挂钩子 → start。 */
    private fun startPlayback(ticket: Long, packageId: String, bytes: ByteArray, loop: Boolean, volume: Float) {
        if (generation.get() != ticket) return
        takeActive()?.let { release(it) }
        val playing = preparePlaying(packageId, bytes) ?: return
        startDecoding(ticket, playing, loop, volume)
    }

    /** 写临时文件（阻塞 IO 在这）+ 建 decoder（MediaPlayer 的 prepare 阻塞在这）。 */
    private fun preparePlaying(packageId: String, bytes: ByteArray): Playing? {
        val file = writeTemp(bytes) ?: return null
        val decoder = try {
            createDecoder(file)
        } catch (expected: Exception) {
            file.delete()
            safeLog(
                logSink,
                "mod.audio 解码器构造失败（$packageId）：${expected.javaClass.simpleName}: ${expected.message}",
            )
            null
        }
        return decoder?.let { Playing(it, file) }
    }

    /** 代际仍匹配才真正挂载钩子并发布为当前；晚到（已被 stop/新 play 顶掉）则释放。 */
    private fun startDecoding(ticket: Long, playing: Playing, loop: Boolean, volume: Float) {
        if (generation.get() != ticket) {
            release(playing)
            return
        }
        playing.decoder.apply {
            onRepeat(if (loop) SoundChannel.LOOP_FOREVER else LOOP_ONCE)
            onVolume(volume)
            setNaturalEndListener { onNaturalEnd(ticket) }
            onStarted()
        }
        val published = synchronized(lock) {
            if (generation.get() != ticket) {
                false
            } else {
                active = playing
                true
            }
        }
        if (!published) release(playing)
    }

    /** 自然播完（looper 回调）：代际仍匹配才摘当前并释放。 */
    private fun onNaturalEnd(ticket: Long) {
        if (generation.get() != ticket) return
        val current = synchronized(lock) {
            if (generation.get() != ticket) {
                null
            } else {
                active.also { active = null }
            }
        } ?: return
        dispatch { release(current) }
    }

    private fun takeActive(): Playing? = synchronized(lock) { active.also { active = null } }

    /** 停声/自然播完/顶掉旧声共用的释放路径：decoder 释放失败只记 WARN，临时文件尽力删。 */
    private fun release(playing: Playing) {
        runCatching { playing.decoder.release() }.onFailure { expected ->
            safeLog(
                logSink,
                "mod.audio decoder 释放失败（${playing.file.name}）：${expected.javaClass.simpleName}: ${expected.message}",
            )
        }
        playing.file.delete()
    }

    private fun writeTemp(bytes: ByteArray): File? = try {
        workDir.mkdirs()
        Files.createTempFile(workDir.toPath(), "mod-audio-", audioFileSuffix(bytes)).toFile()
            .also { it.writeBytes(bytes) }
    } catch (expected: IOException) {
        safeLog(logSink, "mod.audio 临时文件写入失败：${expected.javaClass.simpleName}: ${expected.message}")
        null
    }

    private fun dispatch(action: Runnable) {
        try {
            worker.execute(action)
        } catch (expected: RejectedExecutionException) {
            // 执行器已停（收口后晚到）：释放不能丢，就地尽力执行，失败只记 WARN。
            try {
                action.run()
            } catch (expected: Exception) {
                safeLog(
                    logSink,
                    "mod.audio 后台任务兜底执行失败：${expected.javaClass.simpleName}: ${expected.message}",
                )
            }
        }
    }

    /** 当前一条声音的持有者：decoder + 它独占的临时文件。 */
    private class Playing(val decoder: ChannelDecoder, val file: File)
}

/**
 * 缺省执行器的惰性壳：真 `HandlerThread` 只在**第一次播放**时才建。
 *
 * 理由：装配期（`attach`）就会构造 sink，而 AGP 单元测试的 `android.jar` 是 mockable 桩，
 * 构造 `HandlerThread` 会抛 "not mocked"；装配不该被播放器的初始化前提连坐。
 */
private class LazyAudioExecutor : Executor {

    private val delegate: Executor by lazy(::singleAudioExecutor)

    override fun execute(command: Runnable) = delegate.execute(command)
}

/** 缺省后台执行器：daemon `HandlerThread`（MediaPlayer 需要创建线程有 Looper）。 */
private fun singleAudioExecutor(): Executor {
    val thread = object : HandlerThread("eratw-mod-audio") {
        init {
            isDaemon = true
        }
    }
    thread.start()
    val handler = Handler(thread.looper)
    return Executor { command -> handler.post(command) }
}

/** 取证面 WARN：日志面自己抛异常不许连累播放路径。 */
private fun safeLog(logSink: (String) -> Unit, message: String) {
    runCatching { logSink(message) }
}

/** 线性音量为 1.0 时的单次播放（`LoopPlan.of(0)` = ONCE）。 */
private const val LOOP_ONCE: Int = 0

/** MP3 帧同步的高 3 位掩码（0xE0）。 */
private const val MP3_SYNC_MASK: Int = 0xE0

/** MP3 帧同步首字节（0xFF）。 */
private const val MP3_SYNC_FIRST: Int = 0xFF

/** MP4/M4A 的 `ftyp` box 偏移。 */
private const val MP4_BRAND_OFFSET: Int = 4

/** MP4/M4A 的 `ftyp` box 长度。 */
private const val MP4_BRAND_LENGTH: Int = 4

/**
 * 按字节魔数给临时文件挑后缀（MediaPlayer 会参考扩展名；猜不准时用 `.bin`，让 decoder
 * 自己决定能不能放 —— 放不了就 WARN + false，不抛）。
 */
internal fun audioFileSuffix(bytes: ByteArray): String = when {
    bytes.startsWithAscii("OggS") -> ".ogg"
    bytes.startsWithAscii("RIFF") -> ".wav"
    bytes.startsWithAscii("fLaC") -> ".flac"
    bytes.startsWithAscii("ID3") -> ".mp3"
    isMp3Frame(bytes) -> ".mp3"
    hasM4aBrand(bytes) -> ".m4a"
    else -> ".bin"
}

/** 前缀是否等于给定 ASCII 串（资源字节可能比前缀短）。 */
private fun ByteArray.startsWithAscii(prefix: String): Boolean =
    size >= prefix.length && prefix.indices.all { index -> this[index] == prefix[index].code.toByte() }

/** MP3 帧同步：首字节 0xFF 且次字节高 3 位全 1。 */
private fun isMp3Frame(bytes: ByteArray): Boolean =
    bytes.size >= 2 &&
        bytes[0].toInt() and MP3_SYNC_FIRST == MP3_SYNC_FIRST &&
        bytes[1].toInt() and MP3_SYNC_MASK == MP3_SYNC_MASK

/** MP4/M4A：第 4 字节起是 `ftyp`。 */
private fun hasM4aBrand(bytes: ByteArray): Boolean =
    bytes.size >= MP4_BRAND_OFFSET + MP4_BRAND_LENGTH &&
        (0 until MP4_BRAND_LENGTH).all { index ->
            bytes[MP4_BRAND_OFFSET + index] == "ftyp"[index].code.toByte()
        }
