package com.eratw.port.android

import android.content.Context
import android.util.Log
import com.eratw.port.core.host.HostEvent
import com.eratw.port.core.host.HostEventCodec
import com.eratw.port.core.host.HostEventRing
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 进程级结构化事件日志（R3 第一波）：常开、有界、不依赖 logcat。
 *
 * ## 它补的是什么
 *
 * 真机（vivo / OriginOS）会把应用 logcat 整条丢掉，现有的 [RunWatch] 轨迹是纯文本、
 * DevTools 的 `log --tags` 探针只在 debug 包手动打开。玩家报「闪退 / 卡死」时，除了
 * 一份错误报告以外没有一条**因果链**（装载到了哪一段 → 哪一回合 → 什么错 → 是否被杀）。
 *
 * 本类把慢路径上的关键转折压成 [HostEvent] 单行 JSON：内存环永远在记（DevTools 可读），
 * 落盘只追加一行（`filesDir/eratw-events/events.jsonl`，满 256 KiB 轮转一代）。
 *
 * ## 异步写路径（STAB-C3，2026-11-20）
 *
 * 落盘走**单条 daemon 写线程 + 有界队列**（[WRITE_QUEUE_CAPACITY]）：[record] 在调用线程
 * 只做「入内存环 + 队列 offer」，满了丢新行并计进 [stats] 的 `writeDropped`（读数面可观测）；
 * stat（文件长度）+ 轮转 + append 全部移到写线程。主线程高频两处（`input.submit` /
 * `input.tapRefused`）从此不做任何文件 IO、不进写闸。
 *
 * - **写闸**：[writeGate]（ReentrantLock）只盖「轮转 + append」一段，持有者只有写线程与
 *   退出 drain 的调用线程。内存环另有自己的锁（core 侧 [HostEventRing]），两边互不持有 ——
 *   口径同 RunWatch 的 TrailWriteGate：观察性欠账不许换回新的主线程卡顿。
 * - **退出 drain**：[flushForExit] 有界等写线程排空 → 仍有余量就 tryLock 同步刷剩余 →
 *   拿不到锁就放弃。调用点在壳层退出路径（onDestroy / 结束应用），尾行取证语义靠它保住；
 *   daemon 线程 + 有界等待 ⇒ 进程绝不因落盘挂死。
 * - **失败降级**：写线程不可用（起不来 / 已死）时 [record] 同步回落，落盘语义与异步化
 *   之前逐字相同 —— 只在异常态发生，不是常规路径。
 * - **JSONL 形状零变化**：行仍是 `HostEventCodec.encode(event)`；seq 在入环时分配、
 *   队列 FIFO + 单写者 ⇒ 落盘行序与 seq 序一致。
 *
 * ## 铁律
 *
 * 全程 `runCatching`：埋点把游戏搞崩是本末倒置。写盘失败只 `Log.w`；未 [begin] 时
 * 内存环照记、不写盘；绝不在 VM 指令热路径上取时间或拼字符串。
 *
 * ## 开关
 *
 * 没有显式开关：事件只在装载相位、回合边界、错误、OOM、脏退出这些**慢路径**发射，
 * 一页最多一条 `turn.end`。release 包同样开启 —— 这正是「不再只靠 logcat」的前提。
 */
internal object EventJournal {

    private const val TAG = "EratwEvents"

    /** 环容量（512 条 × 一条 ~200 B ≈ 100 KiB 量级）。 */
    const val RING_CAPACITY = 512

    /** 单条事件 payload 的键上限；超出截断并记 `_truncated=1`。 */
    const val MAX_PAYLOAD_KEYS = 8

    /**
     * 落盘队列容量（有界，STAB-C3）：事件只在慢路径发射（每回合一条 turn.end + 玩家输入），
     * 256 条足以吸收任何一次写盘抖动；满则丢新行并计数（见 [fileDropped]），宁可丢诊断行
     * 也不 OOM / 无界增长。
     */
    private const val WRITE_QUEUE_CAPACITY = 256

    /**
     * 退出 drain 的有界等待上限（毫秒）：超过就不再等写线程，改抢锁同步刷剩余；
     * 再抢不到就放弃（宁丢行不挂进程）。壳层退出路径要的就是「必达但不挂死」。
     */
    private const val EXIT_DRAIN_TIMEOUT_MS = 500L

    /** 退出 drain 同步刷剩余时抢写闸的上限（毫秒）：写线程卡在 IO 上时闸会被它持有。 */
    private const val DRAIN_LOCK_TIMEOUT_MS = 200L

    /** 写线程空轮询间隔（毫秒）：事件是慢路径，50ms 的落盘延迟对取证链无感。 */
    private const val WRITER_IDLE_POLL_MS = 50L

    /** 测试缝 [flushForTest] 的等待上限（毫秒）：断言落盘前先把队列排干净，比退出口径宽。 */
    private const val TEST_FLUSH_TIMEOUT_MS = 10_000L

    /** 进程单调时钟起点（不读墙钟：避免校时/时区回拨把因果链读倒）。 */
    private val originNanos: Long = System.nanoTime()

    @Volatile
    private var ring: HostEventRing = HostEventRing(RING_CAPACITY)

    /** 落盘目录；null = 未 begin（只记内存环）。 */
    @Volatile
    private var dir: File? = null

    /**
     * 落盘写闸（STAB-C3 起 ReentrantLock）：只保护文件轮转/追加这一段，不碰环的锁，
     * 也不允许写盘挡住入队。持有者只有两个 —— 单写线程（逐批）与退出 drain（整段排空）；
     * [record] 的常规路径只入队，永远不进这把锁。
     */
    private val writeGate = ReentrantLock()

    /** 有界落盘队列：调用线程 offer（非阻塞），写线程排空。 */
    private val queue = ArrayBlockingQueue<HostEvent>(WRITE_QUEUE_CAPACITY)

    /**
     * 写线程是否在世（能消费队列）；false 时 [record] 同步回落（降级口径见类 KDoc）。
     * 只在 [ensureWriter] / 写线程 finally / [resetForTest] 三处写，volatile 保证调用侧可见。
     */
    @Volatile
    private var writerAlive = false

    /**
     * 写线程是否正在排空一轮（[drainQueueOnce] 的进出各置一次）。[flushForExit] 靠它区分
     * 「队列已空 = 写完了」与「事件已出队但还没写完」—— 只看队列空会在 poll 之后、append
     * 之前的空窗提前放行，尾行就丢了。
     */
    @Volatile
    private var writerBusy = false

    /**
     * 写线程世代：[resetForTest] 换代 +1，旧线程在下次空轮询看到世代不符自行退出
     * （daemon，不挡 JVM）。生产路径世代恒 0，唯一写者是 [resetForTest]。
     */
    @Volatile
    private var writerGeneration = 0L

    /** 队列满丢掉的落盘行数（丢**新**行；进 [stats] 读数面 `writeDropped`）。 */
    private val fileDropped = AtomicLong()

    /**
     * 只供测试注入慢写（写闸内、每行一发）：E4 的时间上界断言靠它把写线程故意放慢。
     * 生产恒 null；传参 = 目录与待写行，hook 自己负责不抛（抛了按单行落盘失败处理）。
     */
    @Volatile
    internal var slowWriteHookForTest: ((File, String) -> Unit)? = null

    /** 绑定落盘目录并立即建目录（失败只记日志）。 */
    fun begin(context: Context) {
        begin(File(context.filesDir, EventFilePolicy.DIR_NAME))
    }

    /** 测试/工具入口：直接给目录。 */
    fun begin(directory: File) {
        runCatching {
            directory.mkdirs()
            dir = directory
            ensureWriter()
        }.onFailure { Log.w(TAG, "事件日志目录不可用（只记内存环）: ${it.message}") }
    }

    /** 解绑落盘目录（进程收尾/测试复位）；内存环保留，序号继续单调。 */
    fun end() {
        dir = null
    }

    /**
     * 记一条事件：先入环（同步、内存），再（若已 begin）入落盘队列 —— 绝不抛、不做文件 IO、
     * 不进写闸。队列满丢新行并计数（见 [fileDropped]）。
     */
    fun record(type: String, payload: Map<String, String> = emptyMap()) {
        try {
            val atMs = (System.nanoTime() - originNanos) / 1_000_000L
            val event = ring.emit(atMs, type, capPayload(payload))
            if (dir != null) enqueue(event)
        } catch (t: Throwable) {
            runCatching { Log.w(TAG, "事件记录失败（游戏照常）: ${t.message}") }
        }
    }

    /** 环内全部事件（旧→新）。 */
    fun events(): List<HostEvent> = ring.snapshot()

    /** 环内尾 [n] 条。 */
    fun tail(n: Int): List<HostEvent> = ring.tail(n)

    /** 编码后的行（与落盘格式逐字相同）。 */
    fun lines(): List<String> = ring.snapshot().map { HostEventCodec.encode(it) }

    /** 环内尾 [n] 条、编码后的行（只读）；供运行时快照的 events 节注入，写路径不受影响。 */
    fun tailLines(n: Int): List<String> = ring.tail(n).map { HostEventCodec.encode(it) }

    /** 当前落盘文件；未 begin = null。 */
    fun file(): File? = dir?.let { File(it, EventFilePolicy.FILE_NAME) }

    /**
     * 环统计：`emitted/dropped/ringSize`（DevTools `events` 摘要用），STAB-C3 起带
     * `writeDropped`（落盘队列满丢掉的行数）—— 丢行必须可观测，否则因果链断了一段没人知道。
     */
    fun stats(): String =
        "emitted=${ring.emitted} dropped=${ring.dropped} ring=${ring.snapshot().size} " +
            "writeDropped=${fileDropped.get()}"

    /**
     * 退出 drain（STAB-C3）：等写线程把当前队列排空（有界 [timeoutMs]），仍有余量就由调用
     * 线程抢锁同步刷剩余，抢不到就放弃 —— 壳层退出路径（onDestroy / 结束应用）靠它保证
     * 尾行（含 dirty_exit 一族）落盘，同时**绝不**因落盘挂死退出流程。
     *
     * @return true = 队列已空（写线程刷完或本来就没绑目录/没起线程，同步回落已即时落盘）；
     *         false = 超时或抢不到写闸（剩余行可能随进程退出丢失，宁丢不挂）。
     */
    fun flushForExit(timeoutMs: Long = EXIT_DRAIN_TIMEOUT_MS): Boolean {
        if (dir == null || !writerAlive) return true
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline && (!queue.isEmpty || writerBusy)) {
            LockSupport.parkNanos(5_000_000L)
        }
        if (queue.isEmpty && !writerBusy) return true
        if (!writeGate.tryLock(DRAIN_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return false
        try {
            val d = dir ?: return true
            drainQueueInto(d)
        } finally {
            writeGate.unlock()
        }
        return queue.isEmpty
    }

    /** 测试缝：排空队列再断言落盘（口径同 [flushForExit]，等待上限放宽到 [TEST_FLUSH_TIMEOUT_MS]）。 */
    internal fun flushForTest(): Boolean = flushForExit(TEST_FLUSH_TIMEOUT_MS)

    /** 测试复位：换新环、清目录绑定（序号从 1 重新开始）；写线程换代自退，计数清零。 */
    internal fun resetForTest() {
        writerGeneration += 1
        writerAlive = false
        queue.clear()
        fileDropped.set(0)
        slowWriteHookForTest = null
        ring = HostEventRing(RING_CAPACITY)
        dir = null
    }

    /** payload 截断到 [MAX_PAYLOAD_KEYS] 个键；保序。 */
    private fun capPayload(payload: Map<String, String>): Map<String, String> {
        if (payload.isEmpty()) return emptyMap()
        if (payload.size <= MAX_PAYLOAD_KEYS) return payload
        val out = LinkedHashMap<String, String>(MAX_PAYLOAD_KEYS + 1)
        var n = 0
        for ((k, v) in payload) {
            if (n == MAX_PAYLOAD_KEYS) break
            out[k] = v
            n++
        }
        out["_truncated"] = "1"
        return out
    }

    /**
     * 落盘入口：写线程在世只入队（offer 立即返回，满则丢新行并计数）；写线程不可用
     * （起不来 / 已死）才同步回落 —— 降级口径见类 KDoc。目录以写闸内的现读为准
     * （end 之后不再落盘，剩余行留在队列里等下一次 begin）。
     */
    private fun enqueue(event: HostEvent) {
        if (writerAlive) {
            if (queue.offer(event)) return
            fileDropped.incrementAndGet()
            return
        }
        writeGate.withLock {
            val d = dir ?: return
            writeLineThroughHook(d, HostEventCodec.encode(event))
        }
    }

    /** 起（或确认已有）单条 daemon 写线程；进程退出不靠它拖住，尾行由 [flushForExit] 保证。 */
    private fun ensureWriter() {
        if (writerAlive) return
        val gen = writerGeneration
        writerAlive = true
        val thread = Thread({ writerLoop(gen) }, "eratw-event-writer")
        thread.isDaemon = true
        try {
            thread.start()
        } catch (t: Throwable) {
            // 起不来（极端内存紧张）：同步回落，落盘语义不丢。
            writerAlive = false
            Log.w(TAG, "事件写线程启动失败（record 同步回落）: ${t.message}")
        }
    }

    /**
     * 写线程主循环：每轮持闸排空一次队列（批内 FIFO，行序 == seq 序），空轮询 [WRITER_IDLE_POLL_MS]
     * 再试。世代不符即退（见 [writerGeneration]）；任何一轮异常只丢当轮，线程活着继续消费。
     */
    private fun writerLoop(gen: Long) {
        try {
            while (gen == writerGeneration) {
                writerBusy = true
                runCatching { drainQueueOnce() }
                    .onFailure { Log.w(TAG, "事件写线程单轮失败（丢行，游戏照常）: ${it.message}") }
                writerBusy = false
                if (gen != writerGeneration) break
                LockSupport.parkNanos(WRITER_IDLE_POLL_MS * 1_000_000L)
            }
        } finally {
            // 世代已换代（resetForTest）就不动 writerAlive —— 那边已经置 false，别盖回去。
            if (gen == writerGeneration) writerAlive = false
        }
    }

    /**
     * 持闸排空一次队列：目录未绑定（end 之后）就直接返回，剩余行留在队列里等下一次
     * begin；每行一个 runCatching —— 单行落盘失败丢这一行，不清空整批。
     */
    private fun drainQueueOnce() {
        writeGate.withLock {
            val d = dir ?: return
            drainQueueInto(d)
        }
    }

    /**
     * 排空 [queue] 到 [directory]，逐行经 [writeLineThroughHook] 落盘。
     * **调用方必须已持 [writeGate]**（写线程整批 / 退出 drain 整段）。
     */
    private fun drainQueueInto(directory: File) {
        while (true) {
            val event = queue.poll() ?: break
            runCatching { writeLineThroughHook(directory, HostEventCodec.encode(event)) }
                .onFailure { Log.w(TAG, "事件落盘失败（丢行，游戏照常）: ${it.message}") }
        }
    }

    /**
     * 单行落盘：先走测试慢写缝（生产恒 null，零开销），再追加并在超限时轮转。
     * **调用方必须已持 [writeGate]**。
     */
    private fun writeLineThroughHook(directory: File, line: String) {
        slowWriteHookForTest?.invoke(directory, line)
        writeLineLocked(directory, line)
    }

    /** 追加一行并在超限时轮转；**调用方必须已持 [writeGate]**（见 [writeGate] 的口径）。 */
    private fun writeLineLocked(directory: File, line: String) {
        val current = File(directory, EventFilePolicy.FILE_NAME)
        if (EventFilePolicy.shouldRotate(current.length())) {
            val rotated = File(directory, EventFilePolicy.ROTATED_NAME)
            rotated.delete()
            current.renameTo(rotated)
        }
        current.appendText(line + "\n", Charsets.UTF_8)
    }
}

/**
 * 事件文件的有界滚动判式（纯 JVM，无 Android import —— app 单测直测这一层）。
 *
 * 只留一代历史：当前文件超 [MAX_FILE_BYTES] 时整体改名为 `events.1.jsonl`，下一次写入
 * 从空文件开始。两代上限 ≈ 512 KiB，足够覆盖「装载 → 出错 → 被杀」的一条因果链。
 */
internal object EventFilePolicy {

    /** 私有目录下的子目录名。 */
    const val DIR_NAME = "eratw-events"

    /** 当前文件。 */
    const val FILE_NAME = "events.jsonl"

    /** 上一代文件。 */
    const val ROTATED_NAME = "events.1.jsonl"

    /** 单文件上限：256 KiB（约 1300 条 200 B 事件）。 */
    const val MAX_FILE_BYTES = 256L * 1024L

    /** 当前文件达到/超过上限即轮转。 */
    fun shouldRotate(currentBytes: Long): Boolean = currentBytes >= MAX_FILE_BYTES
}
