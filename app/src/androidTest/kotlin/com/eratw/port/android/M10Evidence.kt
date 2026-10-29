package com.eratw.port.android

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.uiautomator.UiDevice
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 取证文本：把关键 logcat 行、时间线与判据读数落到设备上一份可读文件，同时逐行进 logcat。
 *
 * ## 为什么两份都要写
 *
 * 云真机（Firebase Test Lab）不会把应用私有目录里的文件带回来，能带走的只有 results.zip
 * 里的 logcat 与 instrumentation 输出。所以每一行既写文件（组长在 USB 真机上 `adb pull`），
 * 也打 `Log.i`（云测只有这一条通道）。文件名与绝对路径写在第一行，屏证与读数对得上。
 *
 * 目录选择：外部 files（`Android/data/包名/files/m10-evidence`，adb 直接可拉）优先，
 * 拿不到再回落内部 `filesDir/m10-evidence`。截图 PNG 落在同一个目录下。
 */
class M10Evidence private constructor(
    val dir: File,
    val file: File,
) {

    private val lines = ArrayList<String>(256)

    /** 记一行：进内存缓冲、进 logcat（云测唯一能带回的通道）。 */
    fun record(line: String) {
        lines += line
        Log.i(TAG, line)
    }

    fun recordAll(prefix: String, values: List<String>) {
        if (values.isEmpty()) {
            record("$prefix(无)")
        } else {
            values.forEach { record("$prefix$it") }
        }
    }

    fun recordSection(title: String) {
        record("---- $title ----")
    }

    /** 落盘（UTF-8 逐行）。写失败只记日志不抛：取证装置不能把用例搞崩。 */
    fun flush(): File {
        record("取证文本准备落盘：${lines.size} 行 → ${file.absolutePath}")
        try {
            dir.mkdirs()
            FileOutputStream(file).use { out ->
                lines.forEach {
                    out.write(it.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
                out.flush()
            }
            record("取证文本已落盘：${file.absolutePath}（${file.length()} 字节）")
        } catch (t: Throwable) {
            Log.e(TAG, "取证文本写入失败：${file.absolutePath}", t)
            record("取证文本写入失败：${t.javaClass.simpleName} ${t.message}（logcat 里仍有全部读数）")
        }
        return file
    }

    companion object {
        const val TAG = "M10EVIDENCE"
        private const val EVIDENCE_DIR_NAME = "m10-evidence"

        /** 取证文本与截图的公共目录（外部 files 优先，回落内部 files）。 */
        fun evidenceDir(context: Context): File =
            (context.getExternalFilesDir(EVIDENCE_DIR_NAME) ?: File(context.filesDir, EVIDENCE_DIR_NAME))
                .also { it.mkdirs() }

        fun open(context: Context, stage: String): M10Evidence {
            val dir = evidenceDir(context)
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val name = "m10-$stage-api${Build.VERSION.SDK_INT}-${sanitize(Build.MODEL)}-$stamp.txt"
            return M10Evidence(dir, File(dir, name)).apply {
                record("== eraTW M10-CLOUD 取证 ==")
                record("文件=${file.absolutePath}")
                record("阶段=$stage 本地时间=$stamp")
                record(
                    "设备 model=${Build.MODEL} brand=${Build.BRAND} product=${Build.PRODUCT} " +
                        "release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} " +
                        "abi=${Build.SUPPORTED_ABIS.joinToString(",")}",
                )
                record(
                    "包=${context.packageName} 外部files=${context.getExternalFilesDir(null)?.absolutePath} " +
                        "filesDir=${context.filesDir.absolutePath}",
                )
                record("本进程堆上限=${Runtime.getRuntime().maxMemory() / MIB}MiB（largeHeap 生效与否直接体现在这里）")
            }
        }

        fun sanitize(s: String): String =
            s.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }.joinToString("")

        private const val MIB = 1024L * 1024L
    }
}

/**
 * logcat 通道。
 *
 * 两条路，读数口径不同，取到哪条都要写进取证文本：
 * - shell 通道（[UiDevice.executeShellCommand]，经 UiAutomation 跑在 shell uid 上，API 26 起）：
 *   能读**别的进程**的日志。`Displayed`（ActivityTaskManager）与 `ANR in`（ActivityManager）
 *   只有这条路看得见；
 * - 自身 uid 通道（`Runtime.exec`）：只读得到本进程的日志。`GameHost` 的装载埋点与
 *   `FATAL EXCEPTION` 够用（崩溃日志由本进程写出），但 `Displayed` / `ANR` 必然是空的 ——
 *   取证文本要显式写明这一点，不能把「读不到」当成「没有 ANR」。
 */
class M10Logcat(private val device: UiDevice?) {

    var channel: String = "未取"
        private set

    /** 一次过滤 dump（`logcat -d` 读完即退出，不阻塞）。 */
    fun dump(): LogcatDump {
        val text = if (device != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel = "shell(UiDevice.executeShellCommand)：可读系统进程的 Displayed/ANR"
            try {
                device.executeShellCommand(FILTERED)
            } catch (t: Throwable) {
                channel = "自身 uid(Runtime.exec)：shell 通道抛 ${t.javaClass.simpleName} ${t.message}"
                readOwnUid()
            }
        } else {
            channel = "自身 uid(Runtime.exec)：读不到 Displayed/ANR 这类系统进程的行（API<26 或 UiDevice 不可用）"
            readOwnUid()
        }
        return LogcatDump(text)
    }

    private fun readOwnUid(): String = try {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "threadtime", "-s") + TAGS)
        val text = process.inputStream.use { input ->
            BufferedReader(InputStreamReader(input, "UTF-8")).use { it.readLines() }.joinToString("\n")
        }
        process.waitFor()
        text
    } catch (t: Throwable) {
        "logcat 读取失败：${t.message}"
    }

    companion object {
        /**
         * 只取判据需要的 tag。装载埋点与解包进度都在 `GameHost`（LoadTrace 用同一个 tag），
         * 崩溃在 `AndroidRuntime`，ANR 与 `Displayed` 在 `ActivityManager` / `ActivityTaskManager`，
         * 本装置自己的行在 [M10Evidence.TAG]。
         *
         * 首启解包会刷出成千上万行「解包中 N/M」，缓冲区里旧行会被顶掉，所以每轮都现取、
         * 不做增量缓存；已经拿到的关键行由调用方存进取证文本。
         */
        private val TAGS = arrayOf(
            "GameHost:V",
            "ActivityTaskManager:V",
            "ActivityManager:V",
            "AndroidRuntime:V",
            "M10EVIDENCE:V",
            "System.err:V",
            "libc:F",
            "DEBUG:V",
            "*:S",
        )

        private val FILTERED = "logcat -d -v threadtime -s " + TAGS.joinToString(" ")
    }
}

/** 一次 logcat dump 的解析视图（全部按**子串**匹配，不依赖 threadtime 之外的格式假设）。 */
class LogcatDump(val text: String) {

    val lines: List<String> by lazy { text.lines().filter { it.isNotBlank() } }

    /** 装载埋点行（`load.start` / `load.peak` / `load.gc.xxx` / `load.preflight.tight` 等）。 */
    val loadLines: List<String> by lazy { lines.filter { it.contains("load.") } }

    /** 首启解包进度行（`解包中 N/M label`）。 */
    val unpackLines: List<String> by lazy { lines.filter { it.contains("解包中") } }

    /** `首启解包完成：…` 行（有这行说明解包器跑完了；产物齐不齐另说）。 */
    val unpackDoneLines: List<String> by lazy { lines.filter { it.contains("首启解包完成") } }

    /** 装载失败类行（`Log.e(GameHost, "启动失败", t)`、堆预检的 warn 文案）。 */
    val failureLines: List<String> by lazy {
        lines.filter { it.contains("启动失败") || it.contains("堆预检不足") || it.contains("load.preflight.forced") }
    }

    val fatalLines: List<String> by lazy { lines.filter { it.contains("FATAL EXCEPTION") } }

    val anrLines: List<String> by lazy { lines.containsAny("ANR in ", "am_anr") }

    /** `Displayed com.eratw.port.android/.MainActivity: +759ms` 一类的行。 */
    val displayedLines: List<String> by lazy { lines.filter { it.contains("Displayed ") && it.contains(PACKAGE) } }

    /** `load.peak` 里 `series=` 之后的阶段读数（原文整行也留在 [loadLines]）。 */
    val peakLine: String? by lazy { loadLines.firstOrNull { it.contains("load.peak") } }

    fun loadReady(): Boolean = loadLines.any { it.contains("load.ready") }

    fun loadPeaked(): Boolean = loadLines.any { it.contains("load.peak") }

    fun loadStarted(): Boolean = loadLines.isNotEmpty()

    /** 解包器是否跑过（当前随包产物缺失时也会跑，只是写 0 个文件）。 */
    fun installRan(): Boolean = unpackDoneLines.isNotEmpty() || unpackLines.isNotEmpty()

    /**
     * 首启解包耗时（毫秒）：第一条「解包中」到「首启解包完成」的 threadtime 时间戳差。
     *
     * threadtime 时间戳没有年份，按 `MM-dd HH:mm:ss.SSS` 解析并补当前年份；解析不出来
     * （跨年/跨月/格式变化）就返回 null，由取证文本写明「无法从时间戳推得」，不猜。
     */
    fun unpackDurationMs(): Long? {
        val first = timestamps(unpackLines).firstOrNull()
        val done = timestamps(unpackDoneLines).lastOrNull()
        return if (first != null && done != null && done >= first) done - first else null
    }

    private fun List<String>.containsAny(vararg needles: String): List<String> =
        filter { line -> needles.any { line.contains(it) } }

    private fun timestamps(rows: List<String>): List<Long> = rows.mapNotNull { line ->
        TS.find(line)?.groupValues?.get(1)?.let { raw ->
            try {
                TS_FORMAT.parse("$year-$raw")?.time
            } catch (_: Throwable) {
                null
            }
        }
    }

    companion object {
        const val PACKAGE = "com.eratw.port.android"
        private val TS = Regex("""^(\d\d-\d\d \d\d:\d\d:\d\d\.\d\d\d)""")
        private val TS_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private val year: String get() = SimpleDateFormat("yyyy", Locale.US).format(Date())
    }
}

/** 装载收敛状态（轮询的出口判据，全部来自可观测量）。 */
enum class TerminalState(val label: String) {
    /** 装载链走完并接上画面（有 `load.ready`，且画面已连续稳定若干轮）。 */
    READY("装载完成"),

    /** 装载链走完但没到 ready（`load.peak` 有而 ready 没有），或出现失败行。 */
    FAILED("装载失败"),

    /** 装载链根本没启动：解包跑过、一条装载埋点没有、画面连续稳定若干轮 = 停在演示屏。 */
    NO_ARTIFACT("产物缺失/装载未启动"),

    /** 预算内没收敛。 */
    TIMEOUT("超时未收敛"),
}

/**
 * 轮询装载进度：每轮取一次 logcat + 一次屏幕抽样哈希。
 *
 * 出口四条（先到先算）：
 * 1. `load.ready` 且画面连续稳定 [STABLE_POLLS] 轮 → [TerminalState.READY]；
 * 2. `load.peak` 而无 ready，或出现失败行 → [TerminalState.FAILED]；
 * 3. 解包跑过、装载埋点一条没有、画面连续稳定 [STABLE_POLLS] 轮 → [TerminalState.NO_ARTIFACT]
 *    （这正是「随包 APK 还没做好」的形态：停在演示屏）；
 * 4. 到 [deadlineElapsedMs] → [TerminalState.TIMEOUT]。
 *
 * [pollIntervalMs] 只是轮询节奏，**不是**「等固定秒数就当好了」—— 判据全部来自上面四条。
 * 画面稳定性用抽样像素哈希（由调用方给出），所以「装载失败屏已经画完」也能被认出来。
 */
class M10LoadWatcher(
    private val logcat: M10Logcat,
    private val pollScreenHash: () -> Long,
    private val pollIntervalMs: Long,
    private val deadlineElapsedMs: Long,
) {

    /** 一轮的读数（进取证文本：组长能看出卡在哪个阶段、卡了多久）。 */
    class Round(
        val index: Int,
        val elapsedMs: Long,
        val hash: Long,
        val stable: Int,
        val loadLines: Int,
        val unpackLines: Int,
        val note: String,
    )

    val rounds = ArrayList<Round>(32)
    var lastDump: LogcatDump = LogcatDump("")
        private set

    fun runUntilTerminal(onRound: (Round, LogcatDump) -> Unit = { _, _ -> }): Pair<TerminalState, LogcatDump> {
        val startElapsed = SystemClock.elapsedRealtime()
        var lastHash = Long.MIN_VALUE
        var stable = 0
        var index = 0
        while (true) {
            val dump = logcat.dump()
            val hash = try {
                pollScreenHash()
            } catch (t: Throwable) {
                // 截图本身失败不能把轮询带崩：本轮哈希记成「按轮次唯一」，等于永远不稳定。
                Log.w(M10Evidence.TAG, "轮询截图失败（第 ${index + 1} 轮）：${t.javaClass.simpleName} ${t.message}")
                -(index.toLong()) - 2L
            }
            val elapsed = SystemClock.elapsedRealtime() - startElapsed
            stable = if (hash == lastHash) stable + 1 else 0
            lastHash = hash
            index++
            val state = classify(dump, stable)
            val round = Round(
                index = index,
                elapsedMs = elapsed,
                hash = hash,
                stable = stable,
                loadLines = dump.loadLines.size,
                unpackLines = dump.unpackLines.size,
                note = state?.label ?: "等待中",
            )
            rounds += round
            onRound(round, dump)
            lastDump = dump
            if (state != null) return state to dump
            if (SystemClock.elapsedRealtime() >= deadlineElapsedMs) return TerminalState.TIMEOUT to dump
            try {
                Thread.sleep(pollIntervalMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return TerminalState.TIMEOUT to dump
            }
        }
    }

    private fun classify(dump: LogcatDump, stable: Int): TerminalState? = when {
        dump.loadReady() && stable >= STABLE_POLLS - 1 -> TerminalState.READY
        dump.fatalLines.isNotEmpty() -> TerminalState.FAILED
        dump.failureLines.isNotEmpty() -> TerminalState.FAILED
        dump.loadPeaked() && !dump.loadReady() -> TerminalState.FAILED
        !dump.loadStarted() && dump.installRan() && stable >= STABLE_POLLS -> TerminalState.NO_ARTIFACT
        else -> null
    }

    companion object {
        /** 连续几轮画面像素哈希一致才算「这一屏画完了」。 */
        const val STABLE_POLLS = 3
    }
}
