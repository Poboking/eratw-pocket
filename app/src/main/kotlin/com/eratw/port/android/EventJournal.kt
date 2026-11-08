package com.eratw.port.android

import android.content.Context
import android.util.Log
import com.eratw.port.core.host.HostEvent
import com.eratw.port.core.host.HostEventCodec
import com.eratw.port.core.host.HostEventRing
import java.io.File

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

    /** 进程单调时钟起点（不读墙钟：避免校时/时区回拨把因果链读倒）。 */
    private val originNanos: Long = System.nanoTime()

    @Volatile
    private var ring: HostEventRing = HostEventRing(RING_CAPACITY)

    /** 落盘目录；null = 未 begin（只记内存环）。 */
    @Volatile
    private var dir: File? = null

    /** 写侧锁（只保护文件追加/轮转，不碰环的锁，也不允许写盘挡住 `emit`）。 */
    private val writeLock = Any()

    /** 绑定落盘目录并立即建目录（失败只记日志）。 */
    fun begin(context: Context) {
        begin(File(context.filesDir, EventFilePolicy.DIR_NAME))
    }

    /** 测试/工具入口：直接给目录。 */
    fun begin(directory: File) {
        runCatching {
            directory.mkdirs()
            dir = directory
        }.onFailure { Log.w(TAG, "事件日志目录不可用（只记内存环）: ${it.message}") }
    }

    /** 解绑落盘目录（进程收尾/测试复位）；内存环保留，序号继续单调。 */
    fun end() {
        dir = null
    }

    /** 记一条事件：先入环，再（若已 begin）追加落盘。绝不抛。 */
    fun record(type: String, payload: Map<String, String> = emptyMap()) {
        try {
            val atMs = (System.nanoTime() - originNanos) / 1_000_000L
            val event = ring.emit(atMs, type, capPayload(payload))
            val d = dir ?: return
            writeLine(d, HostEventCodec.encode(event))
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

    /** 当前落盘文件；未 begin = null。 */
    fun file(): File? = dir?.let { File(it, EventFilePolicy.FILE_NAME) }

    /** 环统计：`emitted/dropped/ringSize`（DevTools `events` 摘要用）。 */
    fun stats(): String = "emitted=${ring.emitted} dropped=${ring.dropped} ring=${ring.snapshot().size}"

    /** 测试复位：换新环、清目录绑定（序号从 1 重新开始）。 */
    internal fun resetForTest() {
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

    /** 追加一行并在超限时轮转（写侧串行；异常由 [record] 兜住）。 */
    private fun writeLine(directory: File, line: String) {
        synchronized(writeLock) {
            val current = File(directory, EventFilePolicy.FILE_NAME)
            if (EventFilePolicy.shouldRotate(current.length())) {
                val rotated = File(directory, EventFilePolicy.ROTATED_NAME)
                rotated.delete()
                current.renameTo(rotated)
            }
            current.appendText(line + "\n", Charsets.UTF_8)
        }
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
