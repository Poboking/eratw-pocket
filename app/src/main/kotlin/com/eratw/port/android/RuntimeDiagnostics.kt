package com.eratw.port.android

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.fn.mapPoolDiagnostics
import com.eratw.port.core.proc.datingForensics
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.StateStep
import com.eratw.port.core.host.SpritePool
import com.eratw.port.platform.image.SpriteBitmapSource
import com.eratw.port.render.canvas.CanvasConsoleView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行期诊断子系统（W4 自 GameHost 拆出）：死前心跳（LastBreath）、主线程冻结看门狗（StallWatch）、外置内存采样（mem-sample）与三条取证报告行（图形池 / 图像簿记 / 约会现场）。
 *
 * ## 线程口径
 * * [lastBreathTick] / [sampleMemoryIfNeeded]：UI 线程帧回调（MainActivity 每帧）——
 *   落盘经 [launchSingleFlightIo] 单飞 daemon 线程，不占帧预算；
 * * [flushLastBreath]：onPause（离前台）强制一拍；
 * * 报告行三口：报告线程（ErrorReportCollector）经宿主委托只读。
 *
 * 会话状态一律经注入的读口（[currentSession] / [vmThread] / [pendingRequest] …）取
 * —— 本类不持会话引用，宿主关闭/换局时读口自然失效（各读口对 null 安全）。
 */
internal class RuntimeDiagnostics(
    private val context: Context,
    private val viewProvider: () -> CanvasConsoleView,
    private val isClosed: () -> Boolean,
    private val currentSession: () -> GameSession?,
    private val vmThread: () -> Thread?,
    private val graphicsFrames: () -> SpriteBitmapSource?,
    private val currentGfxPool: () -> GraphicsPool?,
    private val currentSpritePool: () -> SpritePool?,
    private val pendingRequest: () -> InputRequest?,
    private val awaitingAnyKey: () -> Boolean,
    private val lastBeginFlowStep: () -> StateStep?,
    private val heapSnapshot: () -> Pair<Long, Long>,
    private val flatStatementsActive: () -> Boolean,) {

    /** 动态视图读口：`GameHost.reattachView` 换绑后逐次取新值（弹窗/Toast/Context 自动跟随新壳）。 */
    private val view: CanvasConsoleView get() = viewProvider()
    // =========================================================================
    // 1. 死前心跳与冻结看门狗
    // =========================================================================

    private var memSampleNextMs = 0L
    private val memSampleWriting = AtomicBoolean(false)

    private var lastBreathNextMs = 0L
    private val lastBreathWriting = AtomicBoolean(false)
    private val lastBreathRotated = AtomicBoolean(false)
    private val lastBreathStall = StallWatch(capture = { gapMs -> captureStallFrames(gapMs) })

    @Volatile
    private var lastBreathStallGapMs = 0L

    @Volatile
    private var lastBreathStallFrames: List<String> = emptyList()

    internal fun flushLastBreath() {
        runCatching {
            if (!LastBreath.enabled) return
            val nowMs = SystemClock.elapsedRealtime()
            lastBreathNextMs = nowMs + LastBreath.INTERVAL_MS
            writeLastBreath(buildLastBreath(nowMs))
        }.onFailure { Log.w(TAG, "死前心跳（离前台）失败（游戏照常）", it) }
    }

    private fun lastBreathTick(nowMs: Long) {
        runCatching {
            if (!LastBreath.enabled) return
            val episode = lastBreathStall.onUiFrame(nowMs)
            var due = nowMs >= lastBreathNextMs
            if (episode != null) {
                lastBreathStallGapMs = episode.gapMs
                lastBreathStallFrames = episode.frames
                due = true
            }
            if (!due) return
            lastBreathNextMs = nowMs + LastBreath.INTERVAL_MS
            writeLastBreath(buildLastBreath(nowMs))
        }.onFailure { Log.w(TAG, "死前心跳打点失败（游戏照常）", it) }
    }

    private fun buildLastBreath(nowMs: Long): LastBreath.Snapshot {
        val (usedMiB, maxMiB) = heapSnapshot()
        val nativeMiB = nativeHeapMiB(fallback = -1L)
        val stats = runCatching { currentGfxPool()?.stats() }.getOrNull()
        val stall = ArrayList<String>(LastBreath.MAX_STALL_LINES)
        val sentinel = runCatching { MainActivity.currentStallSceneLines() }.getOrDefault(emptyList())
        if (sentinel.isNotEmpty()) {
            stall += "（下面是冻结哨兵在冻结瞬间抓的栈）"
            stall += sentinel
        } else if (lastBreathStallFrames.isNotEmpty()) {
            stall += lastBreathStallFrames
        }
        val (state, note) = describeSessionForLastBreath()
        return LastBreath.Snapshot(
            pid = Process.myPid(),
            writtenWallMs = System.currentTimeMillis(),
            atMs = nowMs,
            heapUsedMiB = usedMiB,
            heapMaxMiB = maxMiB,
            nativeAllocMiB = nativeMiB,
            sessionState = state,
            sessionNote = note,
            gfxEntries = stats?.entries ?: -1,
            gfxRetainedMiB = if (stats == null) -1L else bytesToMiB(stats.pixelBytes),
            eventLines = runCatching { EventJournal.tailLines(LastBreath.MAX_EVENT_LINES) }.getOrDefault(emptyList()),
            stallGapMs = lastBreathStallGapMs,
            stallLines = stall.take(LastBreath.MAX_STALL_LINES),
        )
    }

    private fun describeSessionForLastBreath(): Pair<String, String> {
        val live = currentSession() ?: return "未装配" to "会话=未装配（装载期或已结束）"
        val state = runCatching { live.runtime.systemState.name }.getOrDefault("未取到")
        val step = lastBeginFlowStep()?.let {
            "${it.from.name}/${it.handler}→${it.to.name} ${it.outcome}"
        } ?: "本会话还没跑过 BeginFlow 步"
        val waiting = runCatching {
            val request = pendingRequest()
            when {
                request != null -> "输入等待 ${request.type}/${request.form}"
                awaitingAnyKey() -> "等待任意键"
                else -> "无输入等待"
            }
        }.getOrDefault("等待点未取到")
        val vm = if (vmThread()?.isAlive == true) "VM线程=alive" else "VM线程=dead"
        return state to "BeginFlow=$step；$waiting；$vm"
    }

    private fun captureStallFrames(gapMs: Long): List<String> {
        val out = ArrayList<String>(LastBreath.MAX_STALL_LINES)
        out += "UI 帧间隔=${gapMs}ms（恢复后的那一帧抓取，不是冻结瞬间的栈）"
        val vm = vmThread()
        if (vm != null && vm.isAlive) {
            val frames = runCatching { vm.stackTrace }.getOrDefault(emptyArray())
            val top = frames.take(6).mapIndexed { index, frame ->
                "vm#$index ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
            }
            if (top.isEmpty()) out += "vm#（栈为空）" else out += top
        } else {
            out += "vm#（脚本线程未启动或已退出）"
        }
        val uiFrames = runCatching { Thread.currentThread().stackTrace }.getOrDefault(emptyArray())
        uiFrames.take(5).forEachIndexed { index, frame ->
            out += "ui#$index ${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
        }
        return out.take(LastBreath.MAX_STALL_LINES)
    }

    private fun writeLastBreath(snapshot: LastBreath.Snapshot) {
        if (!lastBreathWriting.compareAndSet(false, true)) return
        val dir = runCatching { File(context.filesDir, LastBreath.DIR_NAME) }.getOrNull()
        if (dir == null) {
            lastBreathWriting.set(false)
            return
        }
        val rotate = lastBreathRotated.compareAndSet(false, true)
        launchSingleFlightIo(
            gate = lastBreathWriting,
            threadName = "eratw-lastbreath",
            onSpawnFailure = { Log.w(TAG, "死前心跳写线程起不来（下一拍再试）", it) },
        ) {
            try {
                if (rotate) LastBreath.rotatePreviousRun(dir, snapshot.pid)
                LastBreath.write(dir, snapshot)
            } catch (t: Throwable) {
                Log.w(TAG, "死前心跳落盘失败（静默，游戏照常）", t)
            }
        }
    }

    // =========================================================================
    // 2. 外置内存采样
    // =========================================================================


    fun sampleMemoryIfNeeded(nowMs: Long) {
        if (isClosed()) return
        lastBreathTick(nowMs)
        if (nowMs < memSampleNextMs) return
        memSampleNextMs = nowMs + MEM_SAMPLE_INTERVAL_MS
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull() ?: return
        val dir = File(external, "eratw")
        if (!runCatching { File(dir, MEM_SAMPLE_SWITCH).isFile }.getOrDefault(false)) return
        val line = runCatching { memSampleLine(nowMs) }.getOrNull() ?: return
        Log.i(TAG, "mem $line")
        if (!memSampleWriting.compareAndSet(false, true)) return
        val out = File(dir, MEM_SAMPLE_FILE)
        launchSingleFlightIo(gate = memSampleWriting, threadName = "eratw-mem-sample") {
            runCatching {
                if (out.length() < MEM_SAMPLE_MAX_BYTES) out.appendText(line + "\n")
            }
        }
    }

    private fun memSampleLine(nowMs: Long): String {
        val (heapUsed, heapMax) = heapSnapshot()
        val gfx = currentGfxPool()
        val sprites = currentSpritePool()
        return buildString {
            append("t=").append(nowMs)
            append(" wall=").append(System.currentTimeMillis())
            append(" heap=").append(heapUsed).append("/").append(heapMax)
            append(" native=").append(mib(Debug.getNativeHeapAllocatedSize()))
            append(" gfx=").append(gfx?.entryCount() ?: -1)
            append("/").append(mib(gfx?.residentBytes() ?: -1L))
            append(" bitmapCache=").append(mib((graphicsFrames()?.cacheBytes() ?: -1).toLong()))
            append(" sprites=").append(sprites?.size() ?: -1)
            append(" flat=").append(if (flatStatementsActive()) "on" else "off")
            append(" threads=").append(Thread.activeCount())
            append(" view=").append(mib(runCatching { view.viewBitmapCacheBytes() }.getOrDefault(-1L)))
        }
    }

    // =========================================================================
    // 3. 取证报告行（ErrorReportCollector 经宿主委托）
    // =========================================================================


    internal fun imageBookkeepingReportLine(): String? {
        val store = runCatching { currentSession()?.store }.getOrNull() ?: return null
        val bitPopcount = runCatching {
            var pop = 0L
            for (i in 0 until RM_GID_BIT_ELEMENTS) {
                val v = store.readInt(RM_GID_BITS_VAR, longArrayOf(i.toLong()))
                pop += java.lang.Long.bitCount(v)
            }
            pop
        }.getOrNull() ?: return null
        val maps = runCatching { mapPoolDiagnostics(store) }.getOrDefault(emptyList())
        val gmapEntries = maps.firstOrNull { it.first == RM_GMAP_NAME }?.second
        return buildString {
            append("图像簿记：位图位 ").append(bitPopcount)
            gmapEntries?.let { append(" / 图像表 ").append(it).append(" 项") }
            if (maps.isNotEmpty() && gmapEntries == null) {
                append(" / 图像表不在册（现役 map：").append(maps.joinToString(",") { it.first }).append("）")
            }
        }
    }

    internal fun datingForensicsReportLine(): String? {
        val store = runCatching { currentSession()?.store }.getOrNull() ?: return null
        val forensics = runCatching { datingForensics(store, EratwForensicsLayout.ERATW) }.getOrNull() ?: return null
        return if (forensics.isEmpty) null else forensics.render(EratwForensicsLayout.ERATW_LABELS)
    }

    private fun launchSingleFlightIo(
        gate: AtomicBoolean,
        threadName: String,
        onSpawnFailure: ((Throwable) -> Unit)? = null,
        block: () -> Unit,
    ) {
        runCatching {
            Thread(
                {
                    try {
                        block()
                    } finally {
                        gate.set(false)
                    }
                },
                threadName,
            ).apply { isDaemon = true }.start()
        }.onFailure {
            gate.set(false)
            onSpawnFailure?.invoke(it)
        }
    }

    private fun nativeHeapMiB(fallback: Long = 0L): Long =
        runCatching { Debug.getNativeHeapAllocatedSize() / BYTES_PER_MIB }.getOrDefault(fallback)

    private fun mib(bytes: Long): Long = if (bytes < 0L) bytes else bytes / BYTES_PER_MIB

    private fun bytesToMiB(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MIB

    /** 冻结看门狗复位（onResume）。 */
    internal fun resetStallWatch() {
        lastBreathStall.reset()
    }

    companion object {
        private const val TAG = "GameHost"
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val MEM_SAMPLE_INTERVAL_MS = 20_000L
        private const val MEM_SAMPLE_SWITCH = "mem-sample.on"
        private const val MEM_SAMPLE_FILE = "mem-sample.txt"
        private const val MEM_SAMPLE_MAX_BYTES = 8L * 1024 * 1024
        internal const val RM_GID_BITS_VAR = "RM_GID_BITS"
        internal const val RM_GID_BIT_ELEMENTS = 100
        internal const val RM_GMAP_NAME = "map:グラフィックス"

    }
}
