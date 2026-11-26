package com.eratw.port.android

import com.eratw.port.core.audio.ChannelDecoder
import com.eratw.port.core.audio.SoundChannel
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mod 音频播放汇的宿主实现钉子（Lane AN，task-42）：全走注入的假 decoder + 假执行器，
 * 不起真 MediaPlayer、不碰 Android 运行时。
 *
 * 覆盖：受理语义、loop/volume 透传、空字节降级、构造异常降级、stop 幂等释放、自然播完
 * 释放临时文件、VM 线程不被阻塞（用 latch 卡住 decoder 构造来证明）。
 */
class ModAudioSinkImplTest {

    private fun withTempDir(block: (File) -> Unit) {
        val dir = Files.createTempDirectory("eratw-mod-audio-sink").toFile()
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 记录钩子调用的假 decoder（不碰 MediaPlayer）。 */
    private class FakeDecoder(val file: File) : ChannelDecoder {
        var started: Boolean = false
        var volumeSeen: Float? = null
        var repeatSeen: Int? = null
        var releaseCount: Int = 0
        var naturalEnd: (() -> Unit)? = null

        override val totalSeconds: Double get() = 1.0

        override val positionSeconds: Double get() = 0.0

        override fun seekTo(seconds: Double) = Unit

        override fun release() {
            releaseCount += 1
        }

        override fun onStarted() {
            started = true
        }

        override fun onPaused() = Unit

        override fun onResumed() = Unit

        override fun onStopped() = Unit

        override fun onVolume(linear: Float) {
            volumeSeen = linear
        }

        override fun onSpeed(rate: Float) = Unit

        override fun onPreservePitch(preserve: Boolean) = Unit

        override fun onRepeat(repeat: Int) {
            repeatSeen = repeat
        }

        override fun setNaturalEndListener(listener: (() -> Unit)?) {
            naturalEnd = listener
        }
    }

    /** 同步执行器：后台任务当场跑完，测试好断言。 */
    private val directExecutor = Executor { command -> command.run() }

    /** 建一个记录型 sink：每次构造的 decoder 收进 [created]。 */
    private fun recordingSink(
        dir: File,
        logs: MutableList<String> = mutableListOf(),
        created: MutableList<FakeDecoder> = mutableListOf(),
        worker: Executor = directExecutor,
        createDecoder: ((File) -> ChannelDecoder)? = null,
    ): Pair<ModAudioSinkImpl, MutableList<FakeDecoder>> {
        val decoders = created
        val factory: (File) -> ChannelDecoder = createDecoder ?: { file ->
            FakeDecoder(file).also { decoders += it }
        }
        val sink = ModAudioSinkImpl(
            logSink = { message -> logs += message },
            workDir = dir,
            createDecoder = factory,
            worker = worker,
        )
        return sink to decoders
    }

    @Test
    fun `play受理后透传loop与volume并启动decoder`() {
        withTempDir { dir ->
            val logs = mutableListOf<String>()
            val (sink, created) = recordingSink(dir, logs)
            assertTrue(sink.play("mod.a", "OggS-data".toByteArray(), loop = true, volume = 0.5f))
            val decoder = created.single()
            assertTrue("decoder 必须被启动", decoder.started)
            assertEquals(0.5f, decoder.volumeSeen)
            assertEquals(SoundChannel.LOOP_FOREVER, decoder.repeatSeen)
            assertTrue("成功受理不该留 WARN：$logs", logs.isEmpty())
        }
    }

    @Test
    fun `NaN与越界音量在入口归一且透传给decoder的是安全值`() {
        withTempDir { dir ->
            // NaN：KDoc 承诺「绝不抛、越界自动夹取」；NaN 必须归一为静音，不得穿透到
            // MediaPlayer.setVolume（该处 coerceIn 对 NaN 原样返回会抛 IllegalArgumentException）。
            val (nanSink, nanDecoders) = recordingSink(dir)
            assertTrue(nanSink.play("mod.a", "OggS".toByteArray(), loop = false, volume = Float.NaN))
            assertEquals(0f, nanDecoders.single().volumeSeen)

            // 正/负无穷与越界：夹到 1f / 0f。
            val (highSink, highDecoders) = recordingSink(dir)
            highSink.play("mod.b", "OggS".toByteArray(), loop = false, volume = Float.POSITIVE_INFINITY)
            assertEquals(1f, highDecoders.single().volumeSeen)

            val (lowSink, lowDecoders) = recordingSink(dir)
            lowSink.play("mod.c", "OggS".toByteArray(), loop = false, volume = -2f)
            assertEquals(0f, lowDecoders.single().volumeSeen)
        }
    }

    @Test
    fun `空字节直接拒播并WARN`() {
        withTempDir { dir ->
            val logs = mutableListOf<String>()
            val (sink, created) = recordingSink(dir, logs)
            assertFalse(sink.play("mod.a", ByteArray(0), loop = false, volume = 1f))
            assertTrue("必须留 WARN：$logs", logs.any { it.contains("资源为空") })
            assertTrue("空字节不该构造 decoder", created.isEmpty())
        }
    }

    @Test
    fun `decoder构造异常时只WARN不抛且后续播放仍可`() {
        withTempDir { dir ->
            val logs = mutableListOf<String>()
            val created = mutableListOf<FakeDecoder>()
            var calls = 0
            val (sink, _) = recordingSink(
                dir = dir,
                logs = logs,
                created = created,
                createDecoder = { file ->
                    calls += 1
                    if (calls == 1) {
                        error("decoder-boom")
                    }
                    FakeDecoder(file).also { created += it }
                },
            )
            assertTrue(sink.play("mod.a", "x".toByteArray(), loop = false, volume = 1f))
            assertTrue("必须留 WARN：$logs", logs.any { it.contains("解码器构造失败") })
            assertTrue(sink.play("mod.a", "y".toByteArray(), loop = false, volume = 1f))
            assertEquals("第二次播放必须成功构造", 2, calls)
            assertTrue(created.single().started)
        }
    }

    @Test
    fun `stop幂等且释放decoder与临时文件`() {
        withTempDir { dir ->
            val (sink, created) = recordingSink(dir)
            sink.play("mod.a", "OggS".toByteArray(), loop = false, volume = 1f)
            val decoder = created.single()
            assertTrue("播放期间临时文件应在", decoder.file.exists())
            sink.stop()
            sink.stop()
            assertEquals("stop 幂等：只释放一次", 1, decoder.releaseCount)
            assertFalse("停声后临时文件必须删掉", decoder.file.exists())
        }
    }

    @Test
    fun `自然播完释放decoder与临时文件`() {
        withTempDir { dir ->
            val (sink, created) = recordingSink(dir)
            sink.play("mod.a", "OggS".toByteArray(), loop = false, volume = 1f)
            val decoder = created.single()
            decoder.naturalEnd?.invoke()
            assertEquals(1, decoder.releaseCount)
            assertFalse("自然播完临时文件必须删掉", decoder.file.exists())
        }
    }

    @Test
    fun `VM线程不被decoder构造阻塞`() {
        withTempDir { dir ->
            val gate = CountDownLatch(1)
            val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "test-mod-audio") }
            try {
                val created = mutableListOf<FakeDecoder>()
                val (sink, _) = recordingSink(
                    dir = dir,
                    created = created,
                    worker = worker,
                    createDecoder = { file ->
                        gate.await(5, TimeUnit.SECONDS)
                        FakeDecoder(file).also { created += it }
                    },
                )
                val startedAt = System.nanoTime()
                assertTrue(sink.play("mod.a", "OggS".toByteArray(), loop = false, volume = 1f))
                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                assertTrue("play 必须立即返回，不能被 prepare 卡住（实际 ${elapsedMs}ms）", elapsedMs < 1_000L)
                gate.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var started = false
                while (!started && System.nanoTime() < deadline) {
                    started = created.singleOrNull()?.started == true
                    if (!started) Thread.sleep(10)
                }
                assertTrue("放行后 decoder 应被启动", started)
            } finally {
                worker.shutdownNow()
            }
        }
    }

    @Test
    fun `按字节魔数选择临时文件后缀`() {
        assertEquals(".ogg", audioFileSuffix("OggS".toByteArray()))
        assertEquals(".wav", audioFileSuffix("RIFFxxxx".toByteArray()))
        assertEquals(".mp3", audioFileSuffix(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x00)))
        assertEquals(".m4a", audioFileSuffix("xxxxftyp".toByteArray()))
        assertEquals(".bin", audioFileSuffix("plain".toByteArray()))
    }
}
