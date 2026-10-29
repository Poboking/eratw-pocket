package com.eratw.port.platform.storage

import com.eratw.port.core.loader.BootMetadata
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 首启解包的**跨进程互斥**（DEV-02）钉子。
 *
 * 现场：解包中途 Activity 被回收/重建，上一代线程照常跑完、下一代不认得它，两条解包并发
 * 写同一批目标；其中一条在 `copyStep` 开头撞上对方正在写的 `.tmp`，屏上报
 * 「无法删除解包中间文件」。宿主侧的进程内槽只能挡住同一进程内的两代 Activity。
 *
 * 本类钉的是 `GameAssetInstaller` 新增的五条契约：
 * 1. 锁文件是数据根下的 `.install.lock`，用 `FileChannel.tryLock()` 的 OS 排他锁
 *    （不是 JVM 静态锁：同 JVM 的第二实例会被 JVM 的重叠检测挡下，正好验证这条路径）；
 * 2. 另一实例持锁时**有界等待**，放锁后继续跑，并看到「已就位」（`copiedFiles == 0`）；
 * 3. 等待超时抛可读的 [AssetInstallException]，文案含锁绝对路径 + 「另一实例可能仍在解包」，
 *    且**不许静默成功**（超时路径上一个字节都不落）；
 * 4. 锁在正常返回、解包失败、超时三种出口都已释放（外部 channel 能立刻重新拿到）；
 * 5. `.tmp` 清理语义不变：锁内看到残留中间文件照旧删掉再落盘。
 *
 * 「另一进程」那条分支（`tryLock` 返回 null）与「同 JVM 另一实例」（抛
 * [OverlappingFileLockException]）在实现里走的是同一条等待循环；本类用「测试线程自己持锁 +
 * 解包线程去抢」构造后者 —— 两者都由 JVM/OS 的真实锁机制判决，不是桩。
 * 前者要另起一个 JVM 进程才能构造，单测不做（契约只要求临时目录、自包含）。
 */
class GameAssetInstallerLockTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var assetsRoot: File
    private lateinit var gameRoot: File

    @Before
    fun setUp() {
        assetsRoot = temp.newFolder("assets")
        gameRoot = File(temp.newFolder("files"), "eratw")
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    fun `锁文件落在数据根且第二轮看到已就位`() {
        asset("CSV/Chara.csv", bytes(700, seed = 59))

        assertEquals(1, install().copiedFiles)

        val lockFile = File(gameRoot, GameAssetInstaller.LOCK_FILE)
        assertTrue("锁文件必须在数据根下：${lockFile.absolutePath}", lockFile.isFile)
        assertEquals("锁文件是纯标记，不写内容", 0L, lockFile.length())

        val second = install()
        assertEquals("第二实例必须看到已就位", 0, second.copiedFiles)
        assertEquals("锁文件不参与规划", 1, second.skippedFiles)
        assertEquals(0L, second.copiedBytes)

        // 正常返回后锁已释放：外部 channel 能立刻拿到。
        holdLock().close()
    }

    @Test
    fun `另一实例持锁时等待放锁后继续解包`() {
        val payload = bytes(1_200, seed = 61)
        asset("CSV/Chara.csv", payload)

        val held = holdLock()
        val run = installAsync(lockWaitMillis = 10_000L, pollMillis = 10L)
        try {
            Thread.sleep(300L)
            assertTrue("持锁期间等待方不许抢跑", run.thread.isAlive)
            assertNull("持锁期间不许返回结果", run.result)
        } finally {
            held.close()
        }

        run.await()
        assertNull("等到锁之后应正常解包：${run.failure}", run.failure)
        assertEquals(1, run.result!!.copiedFiles)
        assertArrayEquals(payload, File(gameRoot, "CSV/Chara.csv").readBytes())
    }

    @Test
    fun `另一实例持锁超过等待上限时报可读错误并指名锁路径`() {
        asset("CSV/Chara.csv", bytes(700, seed = 67))

        holdLock().use {
            val e = assertThrows(AssetInstallException::class.java) {
                GameAssetInstaller(
                    DirAssetTree(assetsRoot),
                    gameRoot,
                    lockWaitMillis = 300L,
                    lockPollMillis = 20L,
                ).install()
            }
            val message = e.message.orEmpty()
            val lockPath = File(gameRoot, GameAssetInstaller.LOCK_FILE).absolutePath
            assertTrue("要点名锁路径：$message", message.contains(lockPath))
            assertTrue("要点名是等待超时：$message", message.contains("超时"))
            assertTrue("要提示对面可能还在解包：$message", message.contains("另一实例可能仍在解包"))
            assertFalse("超时不许静默成功：一个字都不该落盘", File(gameRoot, "CSV/Chara.csv").exists())
        }
    }

    @Test
    fun `锁释放后重试成功`() {
        val payload = bytes(900, seed = 71)
        asset("CSV/Chara.csv", payload)

        val held = holdLock()
        val first = try {
            assertThrows(AssetInstallException::class.java) {
                GameAssetInstaller(
                    DirAssetTree(assetsRoot),
                    gameRoot,
                    lockWaitMillis = 200L,
                    lockPollMillis = 20L,
                ).install()
            }
        } finally {
            held.close()
        }
        assertTrue("前置：第一轮必须是因为锁而失败：${first.message}", first.message!!.contains("超时"))
        assertFalse("超时那一轮一个字都不该落盘", File(gameRoot, "CSV/Chara.csv").exists())

        // 锁已放，重试走正常路径。
        val retry = GameAssetInstaller(
            DirAssetTree(assetsRoot),
            gameRoot,
            lockWaitMillis = 5_000L,
            lockPollMillis = 20L,
        ).install()
        assertEquals(1, retry.copiedFiles)
        assertArrayEquals(payload, File(gameRoot, "CSV/Chara.csv").readBytes())
    }

    @Test
    fun `install 幂等 第二次 needed 为空`() {
        val payload = bytes(1_500, seed = 73)
        asset("CSV/Chara.csv", payload)

        assertEquals(1, install().copiedFiles)
        assertArrayEquals(payload, File(gameRoot, "CSV/Chara.csv").readBytes())

        val second = install()
        assertEquals("幂等：第二次一个字节都不写", 0, second.copiedFiles)
        assertEquals(0L, second.copiedBytes)
        assertEquals("规划里只有 CSV 一条", 1, second.skippedFiles)
        assertFalse("没有拷贝就没有 program 可核对", second.programSha256Verified)
    }

    @Test
    fun `同 JVM 两路并发只有一路真写另一路等到已就位`() {
        val payload = bytes(1_800, seed = 79)
        asset("CSV/Chara.csv", payload)

        val a = installAsync(lockWaitMillis = 15_000L, pollMillis = 5L)
        val b = installAsync(lockWaitMillis = 15_000L, pollMillis = 5L)
        a.await()
        b.await()

        assertNull("先到的一路不许抛：${a.failure}", a.failure)
        assertNull("后到的一路不许抛 OverlappingFileLockException：${b.failure}", b.failure)
        assertEquals("只许一路真写", 1, a.result!!.copiedFiles + b.result!!.copiedFiles)
        assertEquals(
            "另一路必须看到已就位",
            1,
            maxOf(a.result!!.skippedFiles, b.result!!.skippedFiles),
        )
        assertArrayEquals(payload, File(gameRoot, "CSV/Chara.csv").readBytes())
        assertFalse("两条并发都不许留下中间文件", File(gameRoot, "CSV/Chara.csv.tmp").exists())
    }

    @Test
    fun `installer 持锁期间第二实例有界等待 放锁后看到已就位`() {
        val payload = bytes(2_500, seed = 97)
        asset("CSV/Chara.csv", payload)
        // 让持锁的那一路**卡在拷贝中**：资产流阻塞到测试放行，锁因此被真实地占住整段时间。
        val gate = GateTree(DirAssetTree(assetsRoot), gatedPath = "CSV/Chara.csv")
        val holder = InstallRun(GameAssetInstaller(gate, gameRoot, 10_000L, 10L), "installer-holder").start()
        try {
            assertTrue("前置：持锁方必须已经进到拷贝段", gate.entered.await(10L, TimeUnit.SECONDS))

            // 第二实例：等待上限很短 ⇒ 走超时出口，且一个字节都不落。
            val e = assertThrows(AssetInstallException::class.java) {
                GameAssetInstaller(
                    DirAssetTree(assetsRoot),
                    gameRoot,
                    lockWaitMillis = 200L,
                    lockPollMillis = 20L,
                ).install()
            }
            assertTrue("要点名对面可能还在解包：${e.message}", e.message!!.contains("另一实例可能仍在解包"))
            assertFalse("等待期不许碰目标", File(gameRoot, "CSV/Chara.csv").exists())
        } finally {
            gate.release()
        }

        holder.await()
        assertNull("持锁方应正常写完：${holder.failure}", holder.failure)
        assertEquals(1, holder.result!!.copiedFiles)

        // 放锁后重试：看到已就位，不再重拷。
        val retry = GameAssetInstaller(
            DirAssetTree(assetsRoot),
            gameRoot,
            lockWaitMillis = 5_000L,
            lockPollMillis = 20L,
        ).install()
        assertEquals("放锁后必须看到已就位", 0, retry.copiedFiles)
        assertArrayEquals(payload, File(gameRoot, "CSV/Chara.csv").readBytes())
    }

    @Test
    fun `解包失败后锁同样释放`() {
        val program = bytes(2_000, seed = 83)
        asset("erbc.v1/program.erbc", program)
        // 声明一份完全不同的哈希：配对失败会抛 AssetInstallException（走 install 的失败出口）。
        asset("erbc.v1/program.meta", meta("0".repeat(64)).toByteArray())

        assertThrows(AssetInstallException::class.java) { install() }

        // 失败出口也必须放锁：外部 channel 能立刻拿到（拿不到时本方法会直接断言失败）。
        holdLock().close()
        // 修好资产后重试必须成功 —— 说明上一轮的失败没有把锁留在「已占用」状态。
        val fixed = bytes(2_000, seed = 83)
        asset("erbc.v1/program.meta", meta(sha256(fixed)).toByteArray())
        val retry = install()
        assertEquals(2, retry.copiedFiles)
        assertTrue(retry.programSha256Verified)
    }

    @Test
    fun `锁内看到残留中间文件照旧删掉再落盘`() {
        val payload = bytes(600, seed = 89)
        asset("CSV/Chara.csv", payload)
        // 上一轮被 kill 的形态：目标不在、中间文件在（锁已随进程退出释放）。
        val stale = File(gameRoot, "CSV/Chara.csv.tmp").apply {
            parentFile?.mkdirs()
            writeText("上一轮残留")
        }
        assertTrue(stale.isFile)

        val result = install()

        assertEquals(1, result.copiedFiles)
        assertFalse("中间文件必须被清掉", stale.exists())
        assertArrayEquals(payload, File(gameRoot, "CSV/Chara.csv").readBytes())
    }

    // ------------------------------------------------------------------
    // 装置
    // ------------------------------------------------------------------

    private fun install(
        lockWaitMillis: Long = 10_000L,
        pollMillis: Long = 10L,
    ): GameAssetInstaller.Result =
        GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot, lockWaitMillis, pollMillis).install()

    /** 后台线程跑一轮 `install()`（用来构造「两路并发」与「持锁期间等待」两种现场）。 */
    private fun installAsync(lockWaitMillis: Long, pollMillis: Long): InstallRun =
        InstallRun(
            GameAssetInstaller(DirAssetTree(assetsRoot), gameRoot, lockWaitMillis, pollMillis),
            "installer-under-test",
        ).start()

    private class InstallRun(
        private val installer: GameAssetInstaller,
        name: String,
    ) {
        @Volatile
        var result: GameAssetInstaller.Result? = null

        @Volatile
        var failure: Throwable? = null

        val thread: Thread = Thread({
            try {
                result = installer.install()
            } catch (t: Throwable) {
                failure = t
            }
        }, name)

        fun start(): InstallRun = apply { thread.start() }

        fun await() {
            thread.join(30_000L)
            assertFalse("解包线程 30 秒内没结束（疑似死等锁）", thread.isAlive)
        }
    }

    /**
     * 扮演「另一实例」：用**另一个 channel** 拿同一把 OS 排他锁（同 JVM 下 JVM 会先做重叠
     * 检测，所以解包器那边拿到的是 [OverlappingFileLockException]；跨进程时则是 `tryLock`
     * 返回 null —— 两种都走实现里的同一条等待循环）。
     *
     * 拿不到即断言失败：这条辅助方法同时充当「锁已释放」的判据（解包器没放锁时这里必然拿不到）。
     */
    private fun holdLock(): HeldLock {
        gameRoot.mkdirs()
        val raf = RandomAccessFile(File(gameRoot, GameAssetInstaller.LOCK_FILE), "rw")
        val lock = try {
            raf.channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            raf.close()
            throw AssertionError("解包器没有释放跨进程锁（同 JVM 重叠检测命中）", e)
        }
        if (lock == null) {
            raf.close()
            throw AssertionError("解包器没有释放跨进程锁（tryLock 返回 null）")
        }
        return HeldLock(raf, lock)
    }

    private class HeldLock(
        private val raf: RandomAccessFile,
        private val lock: FileLock,
    ) : Closeable {

        override fun close() {
            try {
                lock.release()
            } catch (_: Exception) {
                // 测试收尾：释放失败不影响断言（raf.close 也会回收 OS 锁）。
            }
            try {
                raf.close()
            } catch (_: Exception) {
                // 同上。
            }
        }
    }

    private fun asset(rel: String, bytes: ByteArray) {
        File(assetsRoot, rel).apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }
    }

    /** 侧车文本：用 core 的编码器写，头部两行（engine / program-sha256）由它保证。 */
    private fun meta(programSha: String?): String =
        BootMetadata().withHeader(programSha256 = programSha).encodeText()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    /** 可复现的伪随机字节（不用 kotlin.random：core 层禁用同一套 API，测试也保持一致口径）。 */
    private fun bytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * 31 + seed * 7) and 0xff).toByte() }

    /** 临时目录当 assets 根（等价 STORED 条目：长度取真实文件长度）。 */
    private class DirAssetTree(private val root: File) : GameAssetTree {

        override fun openFile(path: String): InputStream? {
            val file = File(root, path)
            return if (file.isFile) file.inputStream() else null
        }

        override fun listDir(path: String): List<String> {
            val dir = File(root, path)
            return if (dir.isDirectory) dir.list().orEmpty().sorted() else emptyList()
        }

        override fun fileSize(path: String): Long? {
            val file = File(root, path)
            return if (file.isFile) file.length() else null
        }
    }

    /**
     * [gatedPath] 的流**阻塞到 [release]**：模拟「大文件正在写」的持锁窗口。
     *
     * 只拦 [openFile]：`walkTree` 走 [fileSize] 拿长度、规划期不打开流，所以 `entered` 一
     * 计数就说明持锁方已经进了 copyStep（锁在手、目标还没 rename）。
     */
    private class GateTree(
        private val inner: GameAssetTree,
        private val gatedPath: String,
    ) : GameAssetTree {

        val entered = CountDownLatch(1)

        @Volatile
        private var open = false

        fun release() {
            open = true
        }

        override fun openFile(path: String): InputStream? {
            if (path != gatedPath) return inner.openFile(path)
            entered.countDown()
            while (!open) Thread.sleep(5L)
            return inner.openFile(path)
        }

        override fun listDir(path: String): List<String> = inner.listDir(path)

        override fun fileSize(path: String): Long? = inner.fileSize(path)
    }
}
