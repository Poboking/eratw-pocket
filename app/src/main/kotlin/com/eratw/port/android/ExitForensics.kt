package com.eratw.port.android

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 死前留痕（last-breath）与两条自救判式（lane `bug-silent`，2026-11-09）。
 *
 * ## 要补的读数缺口
 *
 * 端点 2026-11-08 / 11-09 两天里 10 份「报告类型：上次异常退出」全部**没有签名**：
 * Java 堆贴顶 5 份、Java 堆与 native 都不高 4 份（映射或信号）、native 偏高 1 份。
 * 这 10 份的共同形状是「报告只能在下一次冷启动落」，而**死的那一刻活读数已经随进程消失**：
 *
 * - `[主线程冻结现场]` 小节覆盖 0/69 —— 哨兵 [StallSentinel] 确实抓到过栈（轨迹里
 *   `stallstack all-stalled uiFrozen=1787ms …` 那行就是它钉的），但现场只活在
 *   `MainActivity` 的进程级槽位里，进程一死就没了；
 * - `errcap` / `trim` 轨迹前缀 0 行 —— 被杀前那几秒根本没有落盘动作，盘上最后一行
 *   停在 `turn 248ms`，既没有「当时堆多少」也没有「当时卡在哪个系统状态」。
 *
 * ## 文件名的来历（避免与包内既有的 ExitForensics 混淆）
 *
 * 本文件的文件名取自波次契约 §2 的点名（app 目录下的 ExitForensics 这一支）。包内**已经**
 * 有一个 `internal object ExitForensics`（在 `ErrorReportCollector.kt` 里，管
 * `ApplicationExitInfo` 的退出行判读），所以本文件里的三个新类型叫 [LastBreath] /
 * [StallWatch] / [HeapRescueRunner]，不叫 ExitForensics —— 同名会直接重复声明。
 *
 * 一个静态退出（`SIGNALED` / `CRASH_NATIVE` / `ANR` 后被系统收）的进程，**唯一活得过
 * 死亡时刻的通道是文件**。本文件给的就是这条通道：一个 ≤5 s 的周期心跳，把「死前那一拍
 * 的堆水位 / 会话状态 / 最近事件 / 图形池」写进 `filesDir/eratw-lastbreath/`，下一次启动
 * 由 `ErrorReportCollector` 读回来，作为报告正文里的 `[上次运行死前现场]` 一节。
 *
 * ## 三块内容与它们各自的口径
 *
 * 1. [LastBreath]：**纯值 + 纯 IO**（无 Android import，app 的 JVM 单测直接钉死）——
 *    心跳记录的渲染/解析、原子替换、体积上限、上一轮文件的认主轮换、报告小节成文。
 * 2. [StallWatch]：**纯判式**的主线程冻结看门狗 —— 只看「两次 UI 帧之间隔了多久」这一枚
 *    单调时钟读数，超 [LastBreath.STALL_THRESHOLD_MS] 抓一次、同一次冻结不重复抓、
 *    恢复后清武装位。不碰时钟、不碰线程，成本全在调用方那一次注入的 [StallWatch.capture]。
 * 3. [HeapRescueRunner]：**纯判式 + 注入动作**的后台堆顶自救 —— Java 堆 ≥
 *    [HeapRescueRunner.RESCUE_AT_PERCENT]% 且应用在后台时，把既有的
 *    「图形逐出 + 存档检查点」通道各调一次；`<90%` 一次都不调。
 *
 * ## 为什么不去另造一套线程/采样器
 *
 * 心跳**不新开常驻线程**（红线：不得引入 wakelock 或后台线程泄漏）：
 * 打点挂在 `GameHost.sampleMemoryIfNeeded` 这条**既有**的 UI 帧回调入口上（`MainActivity.pump`
 * 每帧调一次），写盘沿用 `GameHost` 已有的「同一时刻最多一条一次性 daemon 写线程」形状
 * （与 `mem-sample` 那条同源），所以稳态既没有常驻线程，也没有 UI 线程磁盘 IO。
 *
 * 冻结现场**不另起第二个哨兵**：[StallSentinel]（既有，500 ms 节拍、1 500 ms 下限、独立
 * daemon 线程）负责在冻结**正在发生**时抓栈，本文件只负责把那个现场**落盘**（跨进程），
 * 外加 [StallWatch] 那一层「帧间隔」判式 —— 后者抓不到冻结瞬间的栈（它跑在恢复后的那一帧
 * 上），但它**不需要任何线程**，且能回答「冻了多久、什么时候冻的」。
 *
 * ## 隐私口径
 *
 * 与 `ErrorReport` 类头同源：只写**标量与计数**（堆字节、状态码名、事件类型与计数、
 * 图形池条目数与字节数）。**不写**变量取值、存档内容、玩家输入原文、屏上文本。
 * 事件行来自 `EventJournal` 的既有 `HostEvent`（本身就是按这条口径发射的）。
 *
 * ## 铁律 5 备注
 *
 * 本文件正文不出现块注释定界符字符对；要指通配写法时写「specs 下的 yaml」。
 */
internal object LastBreath {

    /** `filesDir` 下的子目录名（与 `eratw-events` / `eratw-reports` 同级、互不干扰）。 */
    const val DIR_NAME = "eratw-lastbreath"

    /** 本轮心跳文件（进程活着时每 ≤[INTERVAL_MS] 覆盖一次）。 */
    const val FILE_NAME = "lastbreath.txt"

    /** 上一轮心跳的留档（本轮第一次写盘时由旧的 [FILE_NAME] 轮换而来）。 */
    const val PREV_FILE_NAME = "lastbreath.prev.txt"

    /** 原子替换用的临时名（同目录，rename 同卷才原子）。 */
    const val TMP_FILE_NAME = "lastbreath.txt.tmp"

    /**
     * 心跳周期（毫秒）。取 5 s 的理由：判据上限，且比典型「被杀前最后一条轨迹」的
     * 间隔（既有报告实测 `turn` 行 200 ms~2 s、`pause` 之后可能是任意长）密得多，
     * 单份 ≤16 KiB 的覆盖写在真机内部存储上是一次毫秒级的小写。
     */
    const val INTERVAL_MS = 5_000L

    /**
     * 单份心跳的体积上限（字节）。
     *
     * 为什么是 16 KiB：报告正文另有单行 400 字符与整节 80 行的上限（`ErrorReport` 里
     * 那两枚常量），16 KiB 在「事件 6 条 + 冻结帧 12 条 + 十来个标量」的实际形状下
     * 用不到一半，留出的余量给超长事件行，同时保证这一发写盘在内存贴顶时也做得完。
     */
    const val MAX_BYTES = 16 * 1024

    /** 心跳里保留的最近 `HostEvent` 条数（旧→新）。 */
    const val MAX_EVENT_LINES = 6

    /** 心跳里保留的冻结帧文本条数。 */
    const val MAX_STALL_LINES = 12

    /** 单条事件/帧文本的截断长度（字符）。 */
    const val MAX_LINE_CHARS = 160

    /**
     * 主线程冻结判据（毫秒）：两次 UI 帧之间隔了这么久就算「卡过一次」。
     *
     * 取 2 000 的理由：Android 的 ANR 是 5 s（前台输入分发），取证要**早于**它；
     * 而 1 000 ms 档的抖动（真机实测 `longturn 1194ms` 一类）是正常长页，抓栈只会
     * 制造噪声。既有 [StallSentinel] 的下限是 1 500 ms（它有自己的抓栈线程），
     * 本判式跑在恢复后的那一帧上、代价只是一次帧间隔减法，故取更严的 2 000 ms。
     */
    const val STALL_THRESHOLD_MS = 2_000L

    /**
     * 两次冻结留痕之间的最短间隔（毫秒）。
     *
     * 与 `RunTrail.RELEASE_COOLDOWN_MS`（30 000）同档：那是本工程对「同一形状的止血动作
     * 不许连着重做」的既有口径。这里防的是「UI 一帧一卡」把心跳文件刷满冻结行。
     */
    const val STALL_COOLDOWN_MS = 30_000L

    /** 心跳总开关：关掉后 [LastBreath.reportLines] 恒给「无」一行，写盘一次都不发生。 */
    @Volatile
    var enabled: Boolean = true

    /** 记录格式版本（解析侧按它判「这一份是不是本口径写的」；未知版本按读不出处理）。 */
    const val FORMAT_VERSION = 1

    /**
     * 一次心跳的全部读数（纯值）。
     *
     * 字段与报告小节的对应关系写在 [reportLines] 里；这里的顺序就是渲染顺序。
     *
     * @property pid 写入方进程号 —— 「上一轮」的认主位（[readPrevious] 拿它跟当前进程比）。
     * @property writtenWallMs 写盘时刻（墙钟，只用于给玩家/端点看「什么时候死的」）。
     * @property atMs 写盘那一刻的 `elapsedRealtime` 毫秒（与轨迹行、心跳同一个单调钟；不是墙钟）。
     * @property heapUsedMiB / heapMaxMiB / nativeAllocMiB 三个堆读数的 MiB 值（-1 = 没取到）。
     * @property sessionState 系统状态机 + BeginFlow 步（`SYSTEM_*` / 状态码名）。
     * @property sessionNote 会话装配态与等待点的一句话（「已装配 / 输入等待 / 未装配」）。
     * @property gfxEntries / gfxRetainedMiB 图形池条目数与像素驻留（Java 堆）。
     * @property eventLines 最近若干条 `HostEvent`（已编码单行，旧→新）。
     * @property stallGapMs 冻结帧间隔（毫秒；≤0 = 本次心跳没有冻结留痕）。
     * @property stallLines 冻结现场行（主线程/脚本线程帧，最内帧在前）。
     * @property notes 附加说明（取不到读数时如实写「未取到」，不编数）。
     */
    data class Snapshot(
        val pid: Int,
        val writtenWallMs: Long,
        val atMs: Long,
        val heapUsedMiB: Long,
        val heapMaxMiB: Long,
        val nativeAllocMiB: Long,
        val sessionState: String,
        val sessionNote: String,
        val gfxEntries: Int,
        val gfxRetainedMiB: Long,
        val eventLines: List<String> = emptyList(),
        val stallGapMs: Long = 0L,
        val stallLines: List<String> = emptyList(),
        val notes: List<String> = emptyList(),
        val version: Int = FORMAT_VERSION,
    ) {
        /** 堆水位百分比（`max ≤ 0` ⇒ -1，调用方据此不打印百分比）。 */
        fun heapPercent(): Long =
            if (heapMaxMiB <= 0L || heapUsedMiB < 0L) -1L else heapUsedMiB * 100L / heapMaxMiB
    }

    // ---- 渲染 / 解析（纯字符串，单测直接钉）----

    /**
     * 渲染成落盘文本：`key=value` 单行 + 重复键（`ev=` / `st=` / `note=`）。
     *
     * 为什么不用 JSON：这份文件要在**堆将尽**时写、在报告侧读，两个方向都只允许最便宜的
     * 字符串拼接与 split；`key=value` 也让 `adb shell cat` 直接可读（release 包没有
     * run-as，这是唯一的人工通道）。
     */
    fun render(snapshot: Snapshot): String {
        val sb = StringBuilder(1024)
        fun line(key: String, value: Any?) {
            sb.append(key).append('=').append(flat(value?.toString() ?: "")).append('\n')
        }
        line("v", snapshot.version)
        line("pid", snapshot.pid)
        line("wall", snapshot.writtenWallMs)
        line("at", snapshot.atMs)
        line("heapUsed", snapshot.heapUsedMiB)
        line("heapMax", snapshot.heapMaxMiB)
        line("natAlloc", snapshot.nativeAllocMiB)
        line("state", snapshot.sessionState)
        line("sess", snapshot.sessionNote)
        line("gfxEntries", snapshot.gfxEntries)
        line("gfxRetained", snapshot.gfxRetainedMiB)
        line("stallGap", snapshot.stallGapMs)
        snapshot.eventLines.forEach { line("ev", it) }
        snapshot.stallLines.forEach { line("st", it) }
        snapshot.notes.forEach { line("note", it) }
        return cap(sb.toString())
    }

    /**
     * 解析落盘文本；**任何一步对不上就返回 null**（宁可报「无」，也不报一份半截现场）。
     *
     * 判据：`v` 必须等于 [FORMAT_VERSION]，`pid` / `wall` / `up` 三个主键都得在。
     * 其余字段缺了按缺省值补（`-1` 或空表）—— 现场不全比整份丢失有价值。
     */
    fun parse(text: String): Snapshot? = runCatching {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (raw in text.lineSequence()) {
            val at = raw.indexOf('=')
            if (at <= 0) continue
            val k = raw.substring(0, at).trim()
            val v = raw.substring(at + 1)
            if (k.isEmpty()) continue
            map.getOrPut(k) { ArrayList(2) }.add(v)
        }
        val version = map["v"]?.firstOrNull()?.trim()?.toIntOrNull() ?: return@runCatching null
        if (version != FORMAT_VERSION) return@runCatching null
        val pid = map["pid"]?.firstOrNull()?.trim()?.toIntOrNull() ?: return@runCatching null
        val wall = map["wall"]?.firstOrNull()?.trim()?.toLongOrNull() ?: return@runCatching null
        val at = map["at"]?.firstOrNull()?.trim()?.toLongOrNull() ?: return@runCatching null
        fun long(key: String, fallback: Long = -1L): Long =
            map[key]?.firstOrNull()?.trim()?.toLongOrNull() ?: fallback
        fun text(key: String): String = map[key]?.firstOrNull()?.trim().orEmpty()
        Snapshot(
            pid = pid,
            writtenWallMs = wall,
            atMs = at,
            heapUsedMiB = long("heapUsed"),
            heapMaxMiB = long("heapMax"),
            nativeAllocMiB = long("natAlloc"),
            sessionState = text("state"),
            sessionNote = text("sess"),
            gfxEntries = long("gfxEntries", -1L).toInt(),
            gfxRetainedMiB = long("gfxRetained"),
            eventLines = map["ev"]?.toList().orEmpty(),
            stallGapMs = long("stallGap", 0L),
            stallLines = map["st"]?.toList().orEmpty(),
            notes = map["note"]?.toList().orEmpty(),
            version = version,
        )
    }.getOrNull()

    /**
     * 把渲染文本截到 [MAX_BYTES] 个 UTF-8 字节以内（**只在行边界上截**）。
     *
     * 行边界不是洁癖：半行会让解析侧读到一个看起来完整的 `ev=` 键值（值被拦腰剪断），
     * 那比少一条事件更误导。所以先按行累加，超了就停，末尾补一行 `note=已截断`。
     * 体积按 UTF-8 字节算（真机上事件行含中文，字符数估字节会低估）。
     */
    fun cap(text: String): String {
        if (text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return text
        val out = StringBuilder(MAX_BYTES)
        var used = 0
        for (raw in text.lineSequence()) {
            val piece = raw + "\n"
            val bytes = piece.toByteArray(Charsets.UTF_8).size
            if (used + bytes > MAX_BYTES - 32) break
            out.append(piece)
            used += bytes
        }
        out.append("note=已截断（超过 ").append(MAX_BYTES).append(" 字节上限）\n")
        return out.toString()
    }

    /** 值整形：换行/回车折成空格、压掉连续空白、截到 [MAX_LINE_CHARS]（含省略号）。 */
    fun flat(value: String): String {
        val one = value.replace('\n', ' ').replace('\r', ' ').trim()
        if (one.length <= MAX_LINE_CHARS) return one
        return one.take(MAX_LINE_CHARS - 1) + "…"
    }

    // ---- 落盘（原子替换、失败静默）----

    /**
     * 覆盖写一份心跳（同目录 `tmp` → `rename` 原子替换）。
     *
     * ## 三条硬约束与它们的落地
     *
     * - **原子**：先写 `lastbreath.txt.tmp` 再 rename。rename 在同一目录内是原子的，
     *   于是读侧要么看到旧的一份完整记录、要么看到新的一份，绝不会读到半截
     *   （这一点由 `LastBreathTest` 的「旧文件不残留半截」用例钉住）。
     * - **有界**：文本先过 [cap]。
     * - **失败静默**：目录建不出、磁盘满、权限拒绝一律只返回 false，**绝不抛**
     *   （红线：所有新写盘失败必须静默且不影响游戏）。调用方也不因此改任何游戏行为。
     *
     * @return true = 真的落盘成功
     */
    fun write(dir: File, snapshot: Snapshot): Boolean = writeText(dir, render(snapshot))

    /** [write] 的文本入口（测试与工具复用）。 */
    fun writeText(dir: File, text: String): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return@runCatching false
        val target = File(dir, FILE_NAME)
        val tmp = File(dir, TMP_FILE_NAME)
        val payload = cap(text).toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(payload)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (tmp.renameTo(target)) return@runCatching true
        // 某些机型上 rename 到已存在的目标会失败：删旧再试一次（窗口内没有目标文件，
        // 下一次写会补上；读侧读到「没有」比读到半截安全）。
        target.delete()
        if (tmp.renameTo(target)) return@runCatching true
        tmp.delete()
        false
    }.getOrDefault(false)

    /**
     * 读一份心跳文本（缺文件 / 读失败 ⇒ null）。绝不抛。
     *
     * @param maxBytes 只读文件头部这么多字节（默认 [MAX_BYTES] 的两倍，留出别人写坏的超长文件）
     */
    fun readText(file: File, maxBytes: Int = MAX_BYTES * 2): String? = runCatching {
        if (!file.isFile) return@runCatching null
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val n = input.read(buffer, read, maxBytes - read)
                if (n <= 0) break
                read += n
            }
            buffer.copyOf(read)
        }
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    /** 读 [dir] 里的本轮心跳；缺失/损坏 ⇒ null。 */
    fun read(dir: File): Snapshot? = readText(File(dir, FILE_NAME))?.let { parse(it) }

    /**
     * 上一轮（**不是本进程**写的）心跳：先看 [FILE_NAME]、再看 [PREV_FILE_NAME]。
     *
     * 为什么要比较 pid 而不是「谁更新」：本进程可能已经写过一份新的心跳（例如玩家在
     * 本局中途点了「分享报告」），那一份是**本进程**读数，不该冒充「上次运行死前现场」。
     * 认主位因此只能是 pid（同一个进程号只会属于一次进程生命）。
     *
     * @param currentPid 当前进程号（生产侧传 `android.os.Process.myPid()`）
     */
    fun readPrevious(dir: File, currentPid: Int): Snapshot? {
        val direct = read(dir)
        if (direct != null && direct.pid != currentPid) return direct
        val prev = readText(File(dir, PREV_FILE_NAME))?.let { parse(it) }
        if (prev != null && prev.pid != currentPid) return prev
        return null
    }

    /**
     * 本轮第一次写盘前调用：把**别的进程**留下的 [FILE_NAME] 轮换成 [PREV_FILE_NAME]。
     *
     * 不做这一步的后果（真机时序）：冷启补报与心跳起跑是两条独立的路，若心跳先跑起来，
     * 它会把上一轮那份「死前现场」当场覆盖掉，报告侧就只剩一份「本进程刚启动 200 ms」
     * 的无害读数 —— 正好把要取证的那一份抹掉。轮换后两边互不踩：本进程写
     * [FILE_NAME]，上一轮留在 [PREV_FILE_NAME] 直到被下一轮再轮换。
     *
     * @return true = 发生了轮换（调用方据此只做一次）
     */
    fun rotatePreviousRun(dir: File, currentPid: Int): Boolean = runCatching {
        val target = File(dir, FILE_NAME)
        if (!target.isFile) return@runCatching false
        val existing = readText(target)?.let { parse(it) } ?: return@runCatching false
        if (existing.pid == currentPid) return@runCatching false
        val prev = File(dir, PREV_FILE_NAME)
        prev.delete()
        if (target.renameTo(prev)) true else {
            // rename 失败（有的机型不允许覆盖已存在目标）⇒ 退化成读+重写。
            val text = readText(target) ?: return@runCatching false
            val ok = writeText(File(dir, PREV_FILE_NAME), text)
            if (ok) target.delete()
            ok
        }
    }.getOrDefault(false)

    // ---- 报告小节 ----

    /** 报告小节标题（`ErrorReport` 正文里按行插进 `[补充现场]`，与 `[主线程冻结现场]` 同形）。 */
    const val SECTION_HEADER = "[上次运行死前现场]（上一次进程的周期心跳；跨进程落盘，不是本进程读数）"

    /** 读不到心跳时的**唯一**一行说明（报告照样完整渲染，绝不吞小节）。 */
    const val NO_SNAPSHOT = "[上次运行死前现场]（无：上一轮没留下心跳文件 —— 首次运行，或那一轮还没跑到第一个心跳点）"

    /**
     * 报告小节行：有留痕 ⇒ 标题 + 各字段；无 ⇒ [NO_SNAPSHOT] 一行。
     *
     * 逐字段与来源的对应（端点侧对账用）：
     * - `死前心跳`：pid / 墙钟时间 / 该进程已运行毫秒；
     * - `堆水位`：`Runtime.totalMemory-freeMemory` / `maxMemory` + `Debug.getNativeHeapAllocatedSize`；
     * - `会话状态`：[com.eratw.port.core.proc.ProcessRuntime.systemState] + 最近一次
     *   `BeginFlow` 步（`StateStep` 的 from/handler/outcome）+ 装配态与等待点；
     * - `图形池`：[com.eratw.port.core.host.GraphicsPool.stats] 的条目数与 `retainedBytes`；
     * - `最近 HostEvent`：[EventJournal] 环尾（类型与计数，无变量取值）；
     * - `冻结现场`：[StallWatch] 的帧间隔 + 该次冻结的栈文本（来自 `StallSentinel`）。
     *
     * @param dir `filesDir` 下的 [DIR_NAME] 目录
     * @param currentPid 当前进程号
     */
    fun reportLines(dir: File, currentPid: Int): List<String> {
        if (!enabled) return listOf(NO_SNAPSHOT)
        val snapshot = readPrevious(dir, currentPid) ?: return listOf(NO_SNAPSHOT)
        val out = ArrayList<String>(MAX_EVENT_LINES + MAX_STALL_LINES + 10)
        out += SECTION_HEADER
        out += "死前心跳：pid=${snapshot.pid} 时间=${wallText(snapshot.writtenWallMs)} " +
            "心跳时刻 elapsedRealtime=${snapshot.atMs}ms"
        val percent = snapshot.heapPercent()
        out += "堆水位：used=${mibText(snapshot.heapUsedMiB)} / max=${mibText(snapshot.heapMaxMiB)}" +
            (if (percent >= 0L) "（$percent%）" else "（百分比不可算）") +
            " / native 分配=${mibText(snapshot.nativeAllocMiB)}"
        out += "会话状态：${snapshot.sessionState.ifEmpty { "未取到" }}；${snapshot.sessionNote.ifEmpty { "装配态未取到" }}"
        out += "图形池：${if (snapshot.gfxEntries < 0) "未取到" else "${snapshot.gfxEntries} 项"}" +
            " / 像素驻留 ${mibText(snapshot.gfxRetainedMiB)}"
        if (snapshot.eventLines.isNotEmpty()) {
            out += "最近 HostEvent（${snapshot.eventLines.size} 条，旧→新）："
            snapshot.eventLines.forEach { out += "  $it" }
        } else {
            out += "最近 HostEvent：无（心跳写盘时事件环为空）"
        }
        if (snapshot.stallLines.isNotEmpty() || snapshot.stallGapMs > 0L) {
            out += "冻结现场：UI 帧间隔 ${snapshot.stallGapMs}ms（阈值 ${STALL_THRESHOLD_MS}ms；最内帧在前）"
            snapshot.stallLines.forEach { out += "  $it" }
        } else {
            out += "冻结现场：无（该进程没有超过 ${STALL_THRESHOLD_MS}ms 的 UI 帧间隔）"
        }
        snapshot.notes.forEach { out += "备注：$it" }
        return out
    }

    /** MiB 读数的人话（负数 = 「没取到」，不打印成 -1 MiB）。 */
    fun mibText(value: Long): String = if (value < 0L) "未取到" else "${value}MiB"

    /** 墙钟毫秒 → 本地时间文本（解析失败给裸毫秒，绝不抛）。 */
    fun wallText(wallMs: Long): String = runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wallMs))
    }.getOrDefault("$wallMs")
}

/**
 * 主线程冻结看门狗（帧间隔判式）。
 *
 * ## 它判什么、不判什么
 *
 * 只看**同一枚单调时钟上两次 UI 帧的间隔**：间隔 ≥ [thresholdMs] 就是「刚才那一段主线程
 * 没在转」。判式本身不需要线程、不需要锁、不需要 Android API —— 于是「可控时钟 + 注入
 * 抓栈」就能在 JVM 单测里把三种形状钉死：抓到一次、同一次不重复抓、恢复后重新武装。
 *
 * 它**抓不到**冻结正在发生时的栈（那时它自己也没机会跑）：那一半交给既有的
 * [StallSentinel]（独立 daemon 线程、500 ms 节拍），本类只负责把那次现场落盘并给出
 * 「冻了多久」这一枚它没有的读数。
 *
 * ## 开销
 *
 * 每次 UI 帧一次减法 + 两次 long 读写（`@Volatile` 都不需要：只在 UI 线程调）。
 * 真正花钱的 [capture] 只在「一次冻结刚结束」那一帧跑一次，且带
 * [LastBreath.STALL_COOLDOWN_MS] 冷却 —— 稳态零成本。
 *
 * ## 线程
 *
 * 单写者：只在 UI 帧回调线程（`MainActivity.pump` 那条）上调 [onUiFrame]。字段因此
 * 不加 `@Volatile`（同 [Heartbeat] 的单写者口径）。
 *
 * @param thresholdMs 判「卡住」的下限（默认 [LastBreath.STALL_THRESHOLD_MS]）
 * @param cooldownMs 两次留痕之间的最短间隔（默认 [LastBreath.STALL_COOLDOWN_MS]）
 * @param capture 抓栈动作：入参是本次冻结的间隔毫秒，返回帧文本（空表 = 这一发没抓到）
 */
internal class StallWatch(
    private val thresholdMs: Long = LastBreath.STALL_THRESHOLD_MS,
    private val cooldownMs: Long = LastBreath.STALL_COOLDOWN_MS,
    private val capture: (Long) -> List<String> = { emptyList() },
) {

    /** 一次冻结留痕（间隔 + 抓到的帧）。 */
    data class Episode(val gapMs: Long, val frames: List<String>)

    private var lastFrameMs: Long = NEVER

    /** 上一次真正留痕的时刻（冷却基准）。 */
    private var lastCaptureAtMs: Long = NEVER

    /** 本次冻结是否已经留过痕（正常帧到达即清 = 「恢复后清除」）。 */
    private var capturedThisEpisode: Boolean = false

    /** 累计留痕次数（DevTools / 单测读数）。 */
    var captures: Int = 0
        private set

    /**
     * 喂一次 UI 帧时刻（`elapsedRealtime` 毫秒）。
     *
     * @return 这一帧刚结束了一次「够长且还没留过痕」的冻结 ⇒ 留痕本体；否则 null
     */
    fun onUiFrame(nowMs: Long): Episode? {
        val previous = lastFrameMs
        lastFrameMs = nowMs
        // 第一次喂数只是立基准：否则进程启动那一刻就是一次「无限长冻结」。
        if (previous == NEVER) return null
        val gap = nowMs - previous
        if (gap < thresholdMs) {
            // 正常一帧 ⇒ 冻结已经恢复：武装位清掉，下一次冻结重新留痕。
            capturedThisEpisode = false
            return null
        }
        if (capturedThisEpisode) return null
        capturedThisEpisode = true
        if (lastCaptureAtMs != NEVER && nowMs - lastCaptureAtMs < cooldownMs) return null
        lastCaptureAtMs = nowMs
        captures += 1
        return Episode(gap, runCatching { capture(gap) }.getOrDefault(emptyList()))
    }

    /** 复位（换会话 / 回前台重新武装时用）。 */
    fun reset() {
        lastFrameMs = NEVER
        capturedThisEpisode = false
    }

    private companion object {
        /** `NEVER` 哨兵：与 `Heartbeat.NEVER` 同口径（没有读数 ≠ 读数是 0）。 */
        const val NEVER = Long.MIN_VALUE
    }
}

/**
 * 一次「存档检查点」请求的登记 / 兑现结局（lane bug-autosave，2026-11-10）。
 *
 * ## 为什么不是 Boolean
 *
 * 后台自救那一发在 UI 线程上**只能登记、不能写**（实写必须落在 VM 线程的语句安全点，
 * 见 [SaveCheckpointRunner] 类头）。旧签名 `() -> Boolean?` 表达不了这件事：
 * 「已登记、等安全点兑现」只能压成 false，报表里就会把一笔正常排队的检查点读成失败。
 * 所以三态（未接线 / 成功 / 失败）之外再拆两种 deferred —— 「VM 正在跑（≈250 ms 后的
 * 页中检查点兑现）」与「VM 停在输入等待（等玩家下一次提交后的回合边界）」，
 * 报告一眼能看出这一笔是马上兑现还是要等玩家动一下。
 */
internal enum class CheckpointSignal {
    /** 宿主没接存档通道（旧口径：`saveCheckpointHook == null`）。 */
    NOT_WIRED,

    /** 已登记：VM 正在执行，下一个页中语句检查点（[com.eratw.port.android.GameHost] 的 midPageCheckpoint）兑现。 */
    DEFERRED_RUNNING,

    /** 已登记：VM 停在输入等待，下一个安全点（回合边界的 onTurnReady）兑现。 */
    DEFERRED_WAITING,

    /** 已有一笔在挂（单飞），本次不登记。 */
    ALREADY_PENDING,

    /** 记账值：真写成功（纯判式单测可注入；生产兑现走 [SaveCheckpointRunner]）。 */
    SAVED,

    /** 记账值：真写失败（同上）。 */
    FAILED,
    ;

    /** 轨迹行 / 报告里的短记号；`ok` / `fail` / `未接线` 三档与旧行逐字兼容。 */
    fun token(): String = when (this) {
        NOT_WIRED -> "未接线"
        DEFERRED_RUNNING -> "deferred"
        DEFERRED_WAITING -> "deferred(wait)"
        ALREADY_PENDING -> "pending"
        SAVED -> "ok"
        FAILED -> "fail"
    }
}

/**
 * 后台堆顶自救（判式 + 注入动作）。
 *
 * ## 它补的是哪一处缺口
 *
 * 既有的水位自救全部挂在**前台**的回合边界（`GameHost.releaseImagePixelsIfHeapHigh`，
 * 80% / 92% 两档）与页中检查点上 —— 那两条路都要求脚本线程在转。应用退到后台之后
 * 脚本线程按 [com.eratw.port.core.proc.GameSession.setBackgroundPaused] 冻结在安全点，
 * 回合边界再也不来；此时若 Java 堆还贴在上面（真机上「切出去看个群 → 回来应用没了」
 * 就是这个形状），既有通道一次都不会开火。本类补的就是这一条**按水位触发**的接线。
 *
 * ## 动作全部复用既有通道，不新造第二套
 *
 * - [evict]：`GameHost.releaseResourceImagePixels`（既有的「重解必得同一张图」那一档）；
 * - [recycleBuffers]：`GameHost.releaseUnusedGraphics(usePixelBudget = true, …)`（既有的
 *   预算档，只收到像素预算以内）；
 * - [trimNative]：`GameHost.trimGraphicsCaches(deep = true)`（既有 `onTrimMemory` 后台档
 *   走的那一发）；
 * - [checkpoint]：存档检查点。**2026-11-10 起由宿主接真通道**（主人裁定「允许后台自动
 *   写存档」）：登记一笔「写官方自动存档槽 99」的请求，实写由 [SaveCheckpointRunner]
 *   在 VM 线程的语句安全点兑现。缺省 [CheckpointSignal.NOT_WIRED] = 装置没接，如实记
 *   `checkpoint=未接线`、动作一次不调（纯单测 / 假体口径）。
 *
 * ## 判式
 *
 * `used × 100 ≥ max × [RESCUE_AT_PERCENT]` 且 `background`，且距上一次自救 ≥ [cooldownMs]。
 * 90% 的出处：既有的存档前预防档（`GameHost` 的 `SAVE_PRE_RECLAIM_PERCENT`）就是 90%，
 * 比回合边界的 80% / 92% 更贴近「马上就要 OOM」；后台这一档只在玩家看不见画面时动手，
 * 逐出画过的缓冲的代价（回前台重解）已被 `deep=true` 那一档接受（同 `onTrimMemory`）。
 *
 * **冷却复用**：本类这 30 s 一档（[LastBreath.STALL_COOLDOWN_MS]）就是存档检查点的冷却，
 * [SaveCheckpointRunner] 不再另设第二个冷却是为了不让两套计时器互相打架
 * （否则「自救刚动过、检查点却因自己的冷却不发」会出现空窗）。
 *
 * @param evict 可重解像素交还（返回收回 MiB）
 * @param recycleBuffers 预算档逐出（返回 缓冲个数 to 字节数）
 * @param trimNative 本屏 native 位图交还（无返回值）
 * @param checkpoint 存档检查点登记（[CheckpointSignal.NOT_WIRED] = 未接线）
 */
internal class HeapRescueRunner(
    private val evict: () -> Long,
    private val recycleBuffers: () -> Pair<Int, Long>,
    private val trimNative: () -> Unit,
    private val checkpoint: () -> CheckpointSignal = { CheckpointSignal.NOT_WIRED },
    private val cooldownMs: Long = LastBreath.STALL_COOLDOWN_MS,
) {

    /** 一次自救的账面（报告/日志/单测读数）。 */
    data class Outcome(
        val usedMiB: Long,
        val maxMiB: Long,
        val freedImageMiB: Long,
        val freedBuffers: Int,
        val freedBufferMiB: Long,
        val checkpoint: CheckpointSignal,
    ) {
        /** 接线与否（旧字段口径：`NOT_WIRED` 之外的都算接上了）。 */
        val checkpointWired: Boolean get() = checkpoint != CheckpointSignal.NOT_WIRED

        /** 轨迹行（与既有 `trim.*` 前缀同族，便于在报告里 grep）。 */
        fun trailLine(): String =
            "bg.heaptop used=${usedMiB}MiB/${maxMiB}MiB freedImage=${freedImageMiB}MiB" +
                " buffers=$freedBuffers/${freedBufferMiB}MiB" +
                " checkpoint=${checkpoint.token()}"
    }

    private var lastAtMs: Long = NEVER

    /**
     * 判一次、必要时做一次。
     *
     * 顺序 = 逐出 → 预算档 → native 交还 → 检查点登记。**逐出先于登记**是有意的：
     * 存档那一笔要一次性分出「对象图 + payload + 压缩副本」（见 `FileSystemOps.saveData`
     * 类头），先收后存才分得出来。
     *
     * @return 真的动手了 ⇒ 账面；没到水位 / 不在后台 / 冷却内 / 参数非法 ⇒ null
     */
    fun maybeRescue(usedMiB: Long, maxMiB: Long, background: Boolean, nowMs: Long): Outcome? {
        if (!shouldRescue(usedMiB, maxMiB, background, if (lastAtMs == NEVER) -1L else nowMs - lastAtMs)) {
            return null
        }
        lastAtMs = nowMs
        val freedImage = runCatching { evict() }.getOrDefault(0L)
        val buffers = runCatching { recycleBuffers() }.getOrDefault(0 to 0L)
        runCatching { trimNative() }
        // 登记自己抛异常也不许把游戏带崩：按「未接线」如实记，逐出已经做过了。
        val checkpointSignal = runCatching { checkpoint() }.getOrDefault(CheckpointSignal.NOT_WIRED)
        return Outcome(
            usedMiB = usedMiB,
            maxMiB = maxMiB,
            freedImageMiB = freedImage,
            freedBuffers = buffers.first,
            freedBufferMiB = buffers.second / (1024L * 1024L),
            checkpoint = checkpointSignal,
        )
    }

    companion object {
        /** 触发线：Java 堆已用 ≥ 上限的这么多百分比（理由见类头）。 */
        const val RESCUE_AT_PERCENT = 90L

        private const val NEVER = Long.MIN_VALUE

        /**
         * 纯判式：该不该做这一次自救。
         *
         * @param sinceLastMs 距上一次自救的毫秒；< 0 = 从没做过
         */
        fun shouldRescue(usedMiB: Long, maxMiB: Long, background: Boolean, sinceLastMs: Long): Boolean {
            if (!background) return false
            if (maxMiB <= 0L || usedMiB < 0L) return false
            if (usedMiB * 100L < maxMiB * RESCUE_AT_PERCENT) return false
            return sinceLastMs < 0L || sinceLastMs >= LastBreath.STALL_COOLDOWN_MS
        }
    }
}

/**
 * 存档检查点的写入器：`(槽位, 标签文本) -> 是否成功`。
 *
 * 生产实现只有一条：`FileSystemOps.saveData`（与语句 `SAVEDATA` 同一条
 * `SaveCapture → SaveCodec → io.write` 通道，`Process.cs:1327-1349` 同源）。
 */
internal typealias SaveWriter = (slot: Int, text: String) -> Boolean

/**
 * 堆顶自救的「存档检查点」——把后台那一发接到**官方自动存档槽 99**的既有写入器上
 * （lane bug-autosave，2026-11-10 主人裁定「允许后台自动写存档」）。
 *
 * ## 缺陷形状（为什么不能直接调 SaveTo）
 *
 * 0e88fb9c9 留下的 `GameHost.saveCheckpointHook` 是 null：后台水位档只逐出像素、不存档，
 * 贴顶的堆在后台一次都没被落盘兜过。而**直接**在 UI 线程调 `saveTo` 会把原版没有的
 * 线程模型塞进存档链：原版唯一的实档动作是 `Process.cs:2583`
 * `vEvaluator.SaveTo(saveTarget, vEvaluator.SAVEDATA_TEXT)`，它只在**脚本线程的语句边界**
 * 被调（`endAutoSaveCallSaveInfo`）；`SaveCapture` 要遍历整个变量池、`SaveCodec` 要一次性
 * 分「payload + 压缩副本」，从别的线程跑既踩 `VariableStore` 的单线程前提，也会在
 * 贴顶堆上再分一份线程栈。
 *
 * ## 因此本类只做三件事
 *
 * 1. **登记**（[request]，UI 线程）：只翻一个 [java.util.concurrent.atomic.AtomicBoolean]，
 *    不碰文件、不碰会话；`vmWaitingInput` 只进台账（区分「马上兑现」与「等玩家提交」）。
 * 2. **兑现**（[drainAtSafePoint]，VM 线程的语句安全点）：恰好消费一笔挂起请求 →
 *    先逐出（[beforeWrite]）→ 再调写入器。写期间新来的请求留在挂起位，等下一个安全点
 *    （单飞 = 同一时刻最多一笔在飞，且**不排队**：堆顶时排队只会把请求攒成一串写）。
 * 3. **台账**（[ledgerLine]）：`save=ok` / `save=fail:<class>` / `save=fail:no-session`，
 *    失败只留痕，绝不抛、绝不杀局（口径同 `VariableEvaluator.cs:2644-2685` 的
 *    `SaveTo`：吞掉一切异常返回 false；也同 `Process.cs:2584-2588`：失败照常收口）。
 *
 * ## 槽位 99 的出处
 *
 * `Process.cs:136` `const AutoSaveIndex = 99`、`:2572-2574` `saveTarget = 99`、
 * `:2583` 只在 `saveTarget == 99` 时实存；落盘名由 `VariableEvaluator.cs:2023-2032`
 * 定成 `save99.sav`。移植侧同一枚常量在 `core/proc/BeginFlow.kt` 的
 * `AUTO_SAVE_SLOT = 99`（internal，本模块另存一份并在单测里对账，防两处漂移）。
 *
 * ## 标签文本
 *
 * 与官方链**同一格变量** `SAVEDATA_TEXT`（`VariableEvaluator.cs:119-129` 的属性就是
 * `varData.DataString[0]`；`Process.cs:2573` 写缺省、`SAVEINFO` 可改写、`:2583` 读当前值）。
 * 读取口由宿主注入（[textProvider]）；取不到或为空时由宿主回落成原版缺省格式。
 *
 * @param writerProvider 取当前写入器；null = 没有会话 / 不是 `FileSystemOps`（不写，留痕）
 * @param textProvider 读 `SAVEDATA_TEXT` 的当前格（可抛；本类兜住）
 * @param beforeWrite 逐出动作（先逐出后存）；自己抛异常也照存（存档优先于画面）
 */
internal class SaveCheckpointRunner(
    private val writerProvider: () -> SaveWriter?,
    private val textProvider: () -> String = { "" },
    private val beforeWrite: () -> Unit = {},
) {

    /** 一次兑现的账面：[line] 就是台账原文（含 `save=ok` / `save=fail:<class>`）。 */
    data class DrainResult(val line: String, val ok: Boolean)

    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private val requests = java.util.concurrent.atomic.AtomicLong(0L)
    private val deferredWaiting = java.util.concurrent.atomic.AtomicLong(0L)
    private val alreadyPending = java.util.concurrent.atomic.AtomicLong(0L)
    private val writes = java.util.concurrent.atomic.AtomicLong(0L)
    private val okCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val failCount = java.util.concurrent.atomic.AtomicLong(0L)

    /** 末次台账原文（空 = 这一局一次都没兑现过）。写侧只有 VM 线程，读侧是报告线程。 */
    @Volatile
    private var lastLine: String = ""

    /** 是否有一笔挂起请求（只读口径，DevTools / 单测用）。 */
    val hasPendingRequest: Boolean get() = pending.get()

    /**
     * 登记一笔请求（**UI 线程**；零 IO、零会话访问）。
     *
     * @param vmWaitingInput 登记这一刻 VM 是否停在输入等待（`QueueInputSink.isAwaiting`）——
     *                       只决定台账记号与「什么时候能兑现」的提示，不改变动作
     * @return 登记结局；[CheckpointSignal.ALREADY_PENDING] = 已有一笔在挂，本次丢弃
     */
    fun request(vmWaitingInput: Boolean): CheckpointSignal {
        requests.incrementAndGet()
        if (vmWaitingInput) deferredWaiting.incrementAndGet()
        if (!pending.compareAndSet(false, true)) {
            alreadyPending.incrementAndGet()
            return CheckpointSignal.ALREADY_PENDING
        }
        return if (vmWaitingInput) CheckpointSignal.DEFERRED_WAITING else CheckpointSignal.DEFERRED_RUNNING
    }

    /**
     * 在**语句安全点**兑现一笔挂起请求（VM 线程；一次最多一笔）。
     *
     * 单飞用 [inFlight] 与 [pending] 两枚原子量表达，**不在写期间持锁**：写一次存档可能
     * 几百毫秒（`SaveCapture` 遍历变量池 + 压缩），若用 `synchronized` 包住写入，UI 线程
     * 下一次 [request] 就会被挡在这把锁上 —— 贴顶堆上的 ANR 正是这么来的。
     *
     * @param where 兑现点记号（`midpage` / `turn`），进台账与轨迹行
     * @param nowMs 单调毫秒（`elapsedRealtime` 口径），只作读数
     * @return null = 没有挂起请求（或已有一笔在飞）；否则本次账面
     */
    fun drainAtSafePoint(where: String, nowMs: Long): DrainResult? {
        if (!pending.get()) return null
        if (!inFlight.compareAndSet(false, true)) return null
        try {
            // 消费请求：写期间新来的请求留在 pending 里，等下一个安全点（单飞、不排队）。
            if (!pending.compareAndSet(true, false)) return null
            val writer = runCatching { writerProvider() }.getOrNull()
            if (writer == null) {
                val line = "save=fail:no-session slot=$AUTO_SAVE_SLOT where=$where at=$nowMs"
                failCount.incrementAndGet()
                lastLine = line
                return DrainResult(line, false)
            }
            // 先逐出后存：逐出自己炸了也照存（要救的是存档，不是画面）。
            runCatching { beforeWrite() }
            val text = runCatching { textProvider() }.getOrDefault("")
            writes.incrementAndGet()
            val outcome = try {
                if (writer(AUTO_SAVE_SLOT, text)) {
                    true to "save=ok"
                } else {
                    // 原版 `SaveTo` 的失败返回（`VariableEvaluator.cs:2666-2669`）：
                    // 没有异常类可记，如实写 false，不编一个。
                    false to "save=fail:false"
                }
            } catch (t: Throwable) {
                // 含 OutOfMemoryError：存档失败绝不许把这一局打死（`Process.cs:2584-2588`）。
                false to "save=fail:${t.javaClass.name}"
            }
            val ok = outcome.first
            val line = "${outcome.second} slot=$AUTO_SAVE_SLOT where=$where at=$nowMs"
            if (ok) okCount.incrementAndGet() else failCount.incrementAndGet()
            lastLine = line
            return DrainResult(line, ok)
        } finally {
            inFlight.set(false)
        }
    }

    /**
     * 台账一行（报告「补充现场」节直接拼进去）。
     *
     * 纯读：只读原子量与一枚 volatile 字符串，不重算、不碰会话、绝不抛 —— 报告本身可能
     * 就是在内存见底时生成的。
     */
    fun ledgerLine(): String {
        val last = if (lastLine.isEmpty()) NEVER else lastLine
        return "存档检查点：请求 ${requests.get()} 次（输入等待登记 ${deferredWaiting.get()} 次）" +
            " / 单飞丢弃 ${alreadyPending.get()} 次 / 写 ${writes.get()} 次" +
            " / ok ${okCount.get()} fail ${failCount.get()}；末次=$last"
    }

    companion object {
        /**
         * 官方自动存档槽（`Process.cs:136` `const AutoSaveIndex = 99`）。
         *
         * 与 `core/proc/BeginFlow.kt` 的 `AUTO_SAVE_SLOT` 必须同值；那边的常量是
         * `internal`（跨模块不可见），所以本模块另存一份，并由单测读两侧源码对账。
         */
        const val AUTO_SAVE_SLOT = 99

        /** 台账「没有兑现过」的记号（与 `LastBreath.NEVER` 同口径：没有读数 ≠ 读数是 0）。 */
        const val NEVER = "未命中"
    }
}