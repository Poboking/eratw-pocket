package com.eratw.port.android

import com.eratw.port.core.diag.ErrorReportUpload
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ReportUploadQueue] 的定向单测：补发的单飞闸门与「逐份落索引」。
 *
 * ## 钉的是哪一种线上事故
 *
 * 同一份报告被 POST 两次（2026-10-31 端点台账里成对的同名条目）。成因是两条线程在同一次
 * 冷启动里各扫一遍同一个目录，而 `.uploaded` 索引要等**整轮扫完**才落笔 ⇒ 两边读到的都是
 * 空索引。两种后果都要挡住：
 * - **重复发**：浪费玩家流量，且端点台账被同名副本灌满，同一件事统计成两次；
 * - **漏发**（闸门写坏成"永远拿不到"就是这种）：报错通道整个静默，比双发更坏。
 *
 * 传输用假实现（[ReportUploadQueue.scanAndUpload] 的 `send` 口），所以这里能在桌面 JVM 上
 * 断言真实次序：谁抢到闸门、索引什么时候落笔、超限那份有没有被记进索引。
 */
class ErrorReportUploadQueueTest {

    @get:Rule
    val root = TemporaryFolder()

    private fun report(name: String, bytes: Int = 32): File =
        File(root.root, name).apply { writeText("r".repeat(bytes)) }

    private fun indexFile(): File = File(root.root, ErrorReportUpload.INDEX_FILE_NAME)

    /** 体积闸用生产同一个上限（core 那个常量是 Int，扫描口收 Long）。 */
    private val maxBytes: Long = ErrorReportUpload.MAX_BODY_BYTES.toLong()

    // ---- ① 单飞闸门 ----

    @Test
    fun 闸门一次只放一路且收尾必放开() {
        assertTrue("第一路必须拿到闸门，否则整条上报通道静默", ReportUploadQueue.tryBegin())
        assertFalse("第二路（同一次冷启动里的另一次调用）拿不到", ReportUploadQueue.tryBegin())
        ReportUploadQueue.finish()
        assertTrue("跑完一轮后要能再拿（下一轮冷启动还得补发）", ReportUploadQueue.tryBegin())
        ReportUploadQueue.finish()
    }

    @Test
    fun 被占用时直接返回不排队且body只跑一次() {
        val runs = AtomicInteger(0)
        assertTrue(ReportUploadQueue.runGuarded { runs.incrementAndGet() })
        // 模拟「另一路还在跑」：手工占住闸门再进来。
        assertTrue(ReportUploadQueue.tryBegin())
        assertFalse(ReportUploadQueue.runGuarded { runs.incrementAndGet() })
        assertEquals(1, runs.get())
        ReportUploadQueue.finish()
    }

    @Test
    fun body抛出闸门也必定放开() {
        val threw = runCatching { ReportUploadQueue.runGuarded { error("boom") } }
        assertFalse("runGuarded 不该替调用方吞异常", threw.isSuccess)
        assertTrue("抛出后下一轮还得能补发", ReportUploadQueue.tryBegin())
        ReportUploadQueue.finish()
    }

    @Test
    fun 另一路正在跑时这一路直接跳过不排队() {
        val entered = AtomicInteger(0)
        val inside = CountDownLatch(1)
        val holder = Thread {
            ReportUploadQueue.runGuarded {
                entered.incrementAndGet()
                inside.countDown()
                // 占住闸门一小会儿，让第二路一定撞在「已被占用」上。
                Thread.sleep(300)
            }
        }.apply { isDaemon = true }
        holder.start()
        assertTrue("第一路确实进到块里了", inside.await(3, java.util.concurrent.TimeUnit.SECONDS))
        val secondRan = ReportUploadQueue.runGuarded { entered.incrementAndGet() }
        assertFalse("第二路拿不到闸门：立刻返回，不排队、不重发", secondRan)
        holder.join(5_000)
        assertEquals("同一轮扫描只允许一路执行", 1, entered.get())
        assertTrue("跑完要放开闸门（下一轮冷启动还得补发）", ReportUploadQueue.tryBegin())
        ReportUploadQueue.finish()
    }

    /**
     * 跨进程锁：另一个进程（这里用同一 JVM 的第二把 FileLock 模拟，撞
     * [java.nio.channels.OverlappingFileLockException]）持有 `.upload.lock` 时，
     * 本路必须立刻放弃；锁一放就能重新拿到。
     */
    @Test
    fun 跨进程文件锁被占时这一路跳过() {
        val lockFile = File(root.root, ReportUploadQueue.LOCK_FILE_NAME)
        val runs = AtomicInteger(0)
        RandomAccessFile(lockFile, "rw").use { holder ->
            val held = holder.channel.lock()
            try {
                assertFalse(
                    "另一进程还在发同一份报告：本路不许再扫",
                    ReportUploadQueue.runGuarded(lockFile) { runs.incrementAndGet() },
                )
            } finally {
                held.release()
            }
        }
        assertEquals("拿不到锁时 body 一次都不许跑", 0, runs.get())
        assertTrue(ReportUploadQueue.runGuarded(lockFile) { runs.incrementAndGet() })
        assertEquals(1, runs.get())
    }

    // ---- ② 逐份落索引 ----

    @Test
    fun 两份待发都发成功且索引逐份落笔() {
        val a = report("eratw-report-20261031-000001.txt")
        val b = report("eratw-report-20261031-000002.txt")
        val seenWhenSendingB = ArrayList<String>()
        val sent = ArrayList<String>()
        val ok = ReportUploadQueue.scanAndUpload(
            dir = root.root,
            indexFile = indexFile(),
            maxBodyBytes = maxBytes,
            crashMode = false,
        ) { file ->
            sent += file.name
            if (file.name == b.name) {
                // 关键断言：发第二份**之前**，第一份的名字已经在盘上的索引里了。
                seenWhenSendingB += ReportUploadQueue.readIndex(indexFile())
            }
            true
        }
        assertEquals(2, ok)
        assertEquals(listOf(a.name, b.name), sent)
        assertEquals("发第二份时索引里必须已含第一份", setOf(a.name), seenWhenSendingB.toSet())
        assertEquals(
            listOf(a.name, b.name),
            ReportUploadQueue.readIndex(indexFile()).sorted(),
        )
    }

    @Test
    fun 发失败的不进索引下次还会再发() {
        val a = report("eratw-report-20261031-000011.txt")
        val b = report("eratw-report-20261031-000012.txt")
        val ok = ReportUploadQueue.scanAndUpload(root.root, indexFile(), maxBytes, false) {
            it == a
        }
        assertEquals(1, ok)
        val index = ReportUploadQueue.readIndex(indexFile())
        assertTrue(index.toString(), index.contains(a.name))
        assertFalse("断网那份不能被记成已发", index.contains(b.name))
        // 下一轮补发：只剩 b 待发。
        val second = ReportUploadQueue.scanAndUpload(root.root, indexFile(), maxBytes, false) { true }
        assertEquals(1, second)
    }

    @Test
    fun 已进索引的报告不再扫到() {
        val a = report("eratw-report-20261031-000021.txt")
        indexFile().writeText(a.name)
        var calls = 0
        val ok = ReportUploadQueue.scanAndUpload(root.root, indexFile(), maxBytes, false) {
            calls++
            true
        }
        assertEquals(0, ok)
        assertEquals("重复扫同一目录就是双发的来源，一次都不该发", 0, calls)
    }

    @Test
    fun 超限报告本地拒发但仍记入索引() {
        // 上限压到 64 KiB - 1：第一份超限（拒发）、第二份正常（发出）。
        val big = report("eratw-report-20261031-000031.txt", bytes = 65_536)
        val small = report("eratw-report-20261031-000032.txt")
        val sent = ArrayList<String>()
        val logs = ArrayList<String>()
        val ok = ReportUploadQueue.scanAndUpload(
            dir = root.root,
            indexFile = indexFile(),
            maxBodyBytes = big.length() - 1L,
            crashMode = false,
            log = { message, _ -> logs += message },
        ) { file ->
            sent += file.name
            true
        }
        assertEquals("超限那份不计成功", 1, ok)
        assertEquals(listOf(small.name), sent)
        assertTrue(logs.joinToString("|"), logs.any { it.contains("超限拒发") && it.contains(big.name) })
        val index = ReportUploadQueue.readIndex(indexFile())
        assertTrue("拒发的也要记档，否则每轮补发白跑一趟", index.contains(big.name))
    }

    @Test
    fun 崩溃档第一份发不出去就撒手() {
        report("eratw-report-20261031-000041.txt")
        val second = report("eratw-report-20261031-000042.txt")
        val sent = ArrayList<String>()
        val ok = ReportUploadQueue.scanAndUpload(root.root, indexFile(), maxBytes, crashMode = true) {
            sent += it.name
            false
        }
        assertEquals(0, ok)
        assertEquals("只试第一份", 1, sent.size)
        assertFalse(sent.toString(), ReportUploadQueue.readIndex(indexFile()).contains(second.name))
    }

    @Test
    fun 目录不存在与索引坏掉都不炸() {
        val missing = File(root.root, "nope")
        assertEquals(0, ReportUploadQueue.scanAndUpload(missing, File(missing, ErrorReportUpload.INDEX_FILE_NAME), 1L, false) { true })
        indexFile().writeText("不是索引的行\n\n")
        val a = report("eratw-report-20261031-000051.txt")
        val ok = ReportUploadQueue.scanAndUpload(root.root, indexFile(), maxBytes, false) { true }
        assertEquals(1, ok)
        assertTrue(ReportUploadQueue.readIndex(indexFile()).contains(a.name))
    }

    @Test
    fun 索引的父目录不在时写入器自己把它建起来() {
        // 与 `RunTrail.writeStateFile` 同一坑：`writeText` 不建父目录，建不出来就是静默不写
        // ⇒ 索引永远为空 ⇒ 每轮冷启动把所有报告再发一遍。这里把「自己建目录」钉住。
        val a = report("eratw-report-20261031-000061.txt")
        val nestedIndex = File(root.root, "nested/.uploaded")
        val ok = ReportUploadQueue.scanAndUpload(
            dir = root.root,
            indexFile = nestedIndex,
            maxBodyBytes = maxBytes,
            crashMode = false,
        ) { true }
        assertEquals(1, ok)
        assertTrue("索引写出来了（父目录由 recordUploaded 自己建）", nestedIndex.isFile)
        assertTrue(ReportUploadQueue.readIndex(nestedIndex).contains(a.name))
    }
}
