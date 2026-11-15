package com.eratw.port.android

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.eratw.port.core.host.CBG_SLOT
import com.eratw.port.core.host.CbgPool
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SpriteEvictScratch
import com.eratw.port.core.host.SpritePool
import com.eratw.port.platform.image.SpriteBitmapSource
import com.eratw.port.render.canvas.CanvasConsoleView
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存水位自救协调器（W1 自 GameHost 拆出）：把「堆 / 进程 / 系统三档水位判据 + 像素 /
 * 缓冲 / Native 三层回收 + 自救台账」从宿主里独立出去 —— 宿主只保留装配（实例化与读口注入）、
 * 公共 API 委托（`reclaimForBackgroundFootprint` / `trimGraphicsCaches` / `nativeCacheSnapshot` /
 * `reclaimDiagnosticsLine` / `requestStrongReclaim` 五口）与两个直调入口（回合边界
 * [releaseImagePixelsIfHeapHigh]、页中检查点 [drainReclaimRequests] + [pressureCheckpointReclaim]）。
 *
 * ## 线程口径（三分，调用方自证）
 * * **VM 线程专跑**：[releaseUnusedGraphics]（首行硬闸）及其全部内部路径、
 *   [releaseImagePixelsIfHeapHigh]（回合边界）、[drainReclaimRequests]（页中检查点）——
 *   图形池账本由 VM 语句流独占推进，回收必须与语句边界同步；
 * * **UI 线程专跑**：[reclaimForBackgroundFootprint]（onTrim 切后台入口，首行硬闸）——
 *   View 侧缓存只许主线程收；
 * * **跨线程安全**：池本体（SpritePool / GraphicsPool）自带 `@Synchronized` regime（见
 *   `GraphicsBuffer` 类 KDoc「回收侧会从 UI 线程遍历这张表」）；[releaseResourceImagePixels]
 *   在两种线程都会被调，非 VM 线程时只登记 [gfxSweepRequested]、由 VM 侧下一拍兑现。
 *
 * ## 与宿主的接缝
 * 宿主注入 [currentAux] / [graphicsFrames] / [heartbeat] / [saveCheckpointSignal] /
 * [saveCheckpointLedger] / [pngPayloadStatsLine] 六个读口与会话状态判据 [isBackground]；
 * 回收成果经 [onTurnReclaimSettled] 回流宿主的 HeapCensusPin 取证。判据真源在 `RunTrail`
 * （纯函数，RunWatch.kt），堆水位自救执行在 [HeapRescueRunner]（ExitForensics.kt）。
 */
internal class MemoryGovernor(
    private val context: Context,
    private val viewProvider: () -> CanvasConsoleView,
    private val onVmThread: () -> Boolean,
    private val onMainThread: () -> Boolean,
    private val isBackground: () -> Boolean,
    private val currentAux: () -> RuntimeAux?,
    private val graphicsFrames: () -> SpriteBitmapSource?,
    private val heartbeat: () -> Heartbeat?,
    private val saveCheckpointSignal: () -> CheckpointSignal,
    private val saveCheckpointLedger: () -> String,
    private val pngPayloadStatsLine: () -> String,
    private val onTurnReclaimSettled: (usedMiB: Long, maxMiB: Long) -> Unit,) {

    /** 动态视图读口：`GameHost.reattachView` 换绑后逐次取新值（弹窗/Toast/Context 自动跟随新壳）。 */
    private val view: CanvasConsoleView get() = viewProvider()

    // =========================================================================
    // 1. 回收请求旗标与暂存复用盘（跨线程登记 → VM 侧兑现）
    // =========================================================================

    @Volatile
    private var gfxSweepRequested = false

    @Volatile
    private var strongReclaimRequested = false

    private val csvBudgetEvictScratch = SpriteEvictScratch()
    private val resourceImageEvictScratch = SpriteEvictScratch()
    private val compositedEvictScratch = SpriteEvictScratch()
    private val releaseGuardScratch = SpriteEvictScratch()
    private val cbgGuardScratch = SpriteEvictScratch()

    private var lastImageReleaseAtMs = -1L
    private var lastUnusedReleaseAtMs = -1L

    private val foregroundFootprintHits = AtomicLong(0L)
    private val backgroundFootprintHits = AtomicLong(0L)
    private val backgroundHeapRescueHits = AtomicLong(0L)
    private val footprintFreedMiB = AtomicLong(0L)

    @Volatile
    private var lastBackgroundFootprintLine: String = ""

    @Volatile
    private var lastBackgroundHeapLine: String = ""

    private data class LimiterReadout(
        val config: MemoryLimiterConfig.DeviceConfig?,
        val diagnostics: String,
    )

    private val limiterReadout: LimiterReadout by lazy {
        val (config, diagnostics) = MemoryLimiterConfig.readDeviceWithDiagnostics()
        LimiterReadout(config, diagnostics)
    }

    private val memoryLimiterDevice: MemoryLimiterConfig.DeviceConfig?
        get() = limiterReadout.config

    private val heapRescue = HeapRescueRunner(
        evict = { releaseResourceImagePixels() },
        recycleBuffers = {
            val released = releaseUnusedGraphics(usePixelBudget = true, minPixels = BUDGET_REAP_MIN_PIXELS)
            released.buffers to released.bytes
        },
        trimNative = { trimGraphicsCaches(deep = true) },
        checkpoint = saveCheckpointSignal,
    )

    // =========================================================================
    // 2. 三层回收通道（像素 / 缓冲 / 合成图）与水位自救入口
    // =========================================================================

    fun releaseResourceImagePixels(): Long {
        val aux = currentAux() ?: return 0L
        var pixels = runCatching {
            aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)
                ?.evictDeferredPixels(scratch = resourceImageEvictScratch) ?: 0L
        }.getOrElse {
            Log.w(TAG, "trim.release 回收闸自身抛掷，跳过负号段这一发：${it.javaClass.name}")
            0L
        }
        if (onVmThread()) {
            gfxSweepRequested = false
            pixels += releaseGfxDeferredPixels(aux)
        } else {
            gfxSweepRequested = true
        }
        val mib = bytesToMiB(pixels)
        if (pixels > 0) Log.i(TAG, "trim.release freed=${mib}MiB（像素 $pixels 条，退回未解码态）")
        return mib
    }

    private fun releaseGfxDeferredPixels(aux: RuntimeAux): Long =
        aux.peek<GraphicsPool>(GRAPHICS_SLOT)?.evictDeferredPixels() ?: 0L

    fun requestStrongReclaim() {
        strongReclaimRequested = true
    }

    internal fun releaseUnusedGraphics(
        usePixelBudget: Boolean = false,
        minPixels: Int = MIN_UNUSED_RELEASE_PIXELS,
    ): GraphicsPool.ReleaseResult {
        if (!onVmThread()) return GraphicsPool.ReleaseResult(0, 0L)
        val aux = currentAux() ?: return GraphicsPool.ReleaseResult(0, 0L)
        val gfx = aux.peek<GraphicsPool>(GRAPHICS_SLOT) ?: return GraphicsPool.ReleaseResult(0, 0L)

        val cbgFilled = runCatching { cbgProtectedGuardsInto(aux, gfx, cbgGuardScratch) }.isSuccess
        if (!cbgFilled) return GraphicsPool.ReleaseResult(0, 0L)

        val guardedCount = runCatching {
            releaseGuardScratch.clear()
            releaseGuardScratch.copyGuardsFrom(cbgGuardScratch)
            aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)?.addReferencedGuards(releaseGuardScratch)
            releaseGuardScratch.guardCount()
        }.getOrNull() ?: return GraphicsPool.ReleaseResult(0, 0L)

        val result = gfx.releaseUnused(
            releaseGuardScratch,
            minPixels,
            if (usePixelBudget) gfx.pixelBudgetBytes() else null,
        )
        if (result.buffers > 0) {
            Log.i(
                TAG,
                "trim.unused buffers=${result.buffers} freed=${bytesToMiB(result.bytes)}MiB" +
                    "（保留 $guardedCount 个在引用中的缓冲）",
            )
        }
        val thirdTierBytes = evictCompositedSpritesIfOverBudget(aux, gfx, cbgGuardScratch)
        return if (thirdTierBytes > 0L) {
            GraphicsPool.ReleaseResult(result.buffers, result.bytes + thirdTierBytes)
        } else {
            result
        }
    }

    private fun cbgProtectedGuardsInto(
        aux: RuntimeAux,
        gfx: GraphicsPool,
        target: SpriteEvictScratch,
    ) {
        target.clear()
        val cbg = aux.peek<CbgPool>(CBG_SLOT) ?: return
        for (layer in cbg.layers) {
            val id = layer.gid ?: continue
            gfx.peek(id)?.let { target.addGuard(it) }
        }
    }

    private fun evictCompositedSpritesIfOverBudget(
        aux: RuntimeAux,
        gfx: GraphicsPool,
        keep: SpriteEvictScratch,
    ): Long {
        val sprites = aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE) ?: return 0L
        val budget = gfx.pixelBudgetBytes()
        val resident = gfx.residentBytes()
        val (usedMiB, maxMiB) = heapSnapshotMiB()
        if (!RunTrail.shouldEvictCompositedSprites(usedMiB, maxMiB, resident, budget)) return 0L

        val evicted = runCatching {
            sprites.evictCompositedSpritesWithinBudget(resident, budget, keep, scratch = compositedEvictScratch)
        }.getOrElse {
            Log.w(TAG, "trim.sprites 逐出闸自身抛掷，跳过这一发：${it.javaClass.name}")
            return 0L
        }
        val budgetLabel = if (budget == Long.MAX_VALUE) "不可知" else "${bytesToMiB(budget)}MiB"
        if (evicted.sprites == 0) {
            Log.i(
                TAG,
                "trim.sprites 无可逐出（驻留=${bytesToMiB(resident)}MiB 预算=$budgetLabel" +
                    " 保留 ${keep.guardCount()} 个在引用中的缓冲）",
            )
            return 0L
        }
        val freedBytes = evicted.pixels * BYTES_PER_PIXEL
        val freedMiB = bytesToMiB(freedBytes)
        Log.i(TAG, "trim.sprites names=${evicted.sprites} freed=${freedMiB}MiB used=${usedMiB}MiB/$maxMiB")
        RunWatch.forceSample(
            context,
            "trim.sprites n=${evicted.sprites} freed=${freedMiB}MiB" +
                " used=${usedMiB}MiB/$maxMiB resident=${bytesToMiB(resident)}MiB budget=$budgetLabel",
            heartbeat(),
        )
        return freedBytes
    }

    internal fun drainReclaimRequests(samplePrefix: String, whereLabel: String): Boolean {
        val strong = strongReclaimRequested
        val sweep = gfxSweepRequested
        if (!strong && !sweep) return false
        strongReclaimRequested = false
        if (strong) {
            gfxSweepRequested = false
            val freedPixels = releaseResourceImagePixels()
            val released = releaseUnusedGraphics()
            val now = SystemClock.elapsedRealtime()
            lastImageReleaseAtMs = now
            lastUnusedReleaseAtMs = now
            val (usedMiB, maxMiB) = heapSnapshotMiB()
            val nativeMiB = nativeHeapMiB(fallback = -1L)
            RunWatch.forceSample(
                context,
                "$samplePrefix.strong freed=${freedPixels}MiB" +
                    " n=${released.buffers} freedBuf=${bytesToMiB(released.bytes)}MiB" +
                    " used=${usedMiB}MiB/$maxMiB nativeHeap=${nativeMiB}MiB",
            )
            return true
        }
        val aux = currentAux()
        val freed = if (aux != null) releaseGfxDeferredPixels(aux) else 0L
        if (freed > 0L) {
            val freedMiB = bytesToMiB(freed)
            Log.i(TAG, "trim.release.deferred freed=${freedMiB}MiB（${whereLabel}兑现）")
            RunWatch.forceSample(context, "$samplePrefix.deferred freed=${freedMiB}MiB")
        }
        return false
    }

    fun releaseImagePixelsIfHeapHigh() {
        if (drainReclaimRequests("sys", "回合边界")) return

        currentAux()?.let { budgetAux ->
            val gfxResident = budgetAux.peek<GraphicsPool>(GRAPHICS_SLOT)?.residentBytes() ?: 0L
            val freedPixels = runCatching {
                budgetAux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)
                    ?.evictDeferredPixelsWithinSharedBudget(gfxResident, scratch = csvBudgetEvictScratch)
                    ?: 0L
            }.getOrElse {
                Log.w(TAG, "budget.csv 回收闸自身抛掷，跳过这一发：${it.javaClass.name}")
                0L
            }
            if (freedPixels > 0L) {
                val freedMiB = pixelsToMiB(freedPixels)
                Log.i(TAG, "budget.csv freed=${freedMiB}MiB（像素 $freedPixels 条，退回未解码态，回合边界）")
                RunWatch.forceSample(context, "turn.budgetcsv freed=${freedMiB}MiB")
                journalEvict(freedMiB)
            }
        }

        run {
            val pssMiB = processPssMiB()
            val totalRamMiB = deviceTotalRamMiB()
            val device = memoryLimiterDevice
            val budget = RunTrail.memoryLimiterBudgetMiB(totalRamMiB, visible = true, config = device?.sets)
            if (RunTrail.shouldReclaimForProcessFootprint(pssMiB, budget)) {
                lastImageReleaseAtMs = SystemClock.elapsedRealtime()
                val freed = releaseResourceImagePixels()
                foregroundFootprintHits.incrementAndGet()
                footprintFreedMiB.addAndGet(freed)
                RunWatch.forceSample(
                    context,
                    "turn.pssfootprint pss=${pssMiB}MiB budget=${budget}MiB" +
                        " ram=${totalRamMiB}MiB src=${device?.origin?.label ?: "estimate"}" +
                        " freed=${freed}MiB",
                )
                journalEvict(freed)
            }
        }

        val (used, max) = heapSnapshotMiB()
        val now = SystemClock.elapsedRealtime()
        if (RunTrail.shouldReleaseImagePixels(used, max, now - lastImageReleaseAtMs)) {
            lastImageReleaseAtMs = now
            val freed = releaseResourceImagePixels()
            if (freed > 0L) RunWatch.forceSample(context, "turn.trim used=${used}MiB/$max")
            journalEvict(freed)

            val capped = releaseUnusedGraphics(usePixelBudget = true, minPixels = BUDGET_REAP_MIN_PIXELS)
            if (capped.buffers > 0) {
                val freedMiB = bytesToMiB(capped.bytes)
                RunWatch.forceSample(
                    context,
                    "turn.trim.budget n=${capped.buffers} freed=${freedMiB}MiB used=${used}MiB/$max",
                )
                journalEvict(freedMiB)
            }
        }
        if (RunTrail.shouldReleaseUnusedGraphics(used, max, now - lastUnusedReleaseAtMs)) {
            lastUnusedReleaseAtMs = now
            val r = releaseUnusedGraphics()
            if (r.buffers > 0) {
                val freedMiB = bytesToMiB(r.bytes)
                RunWatch.forceSample(
                    context,
                    "turn.trim.unused n=${r.buffers} freed=${freedMiB}MiB used=${used}MiB/$max",
                )
                journalEvict(freedMiB)
            }
        }

        val sysAvail = RunWatch.systemAvailMiB()
        val lmkLine = RunWatch.lowMemoryLineMiB(context)
        if (RunTrail.shouldReleaseImagePixelsForSystemPressure(sysAvail, lmkLine, now - lastImageReleaseAtMs)) {
            lastImageReleaseAtMs = now
            val freed = releaseResourceImagePixels()
            RunWatch.forceSample(context, "turn.syslow freed=${freed}MiB")
            journalEvict(freed)
        }
        if (RunTrail.shouldReleaseUnusedForSystemPressure(sysAvail, lmkLine, now - lastUnusedReleaseAtMs)) {
            lastUnusedReleaseAtMs = now
            val r = releaseUnusedGraphics()
            val freedMiB = bytesToMiB(r.bytes)
            RunWatch.forceSample(context, "turn.syscrit n=${r.buffers} freed=${freedMiB}MiB")
            journalEvict(freedMiB)
        }

        onTurnReclaimSettled(used, max)
    }

    private fun journalEvict(freedMiB: Long) {
        if (freedMiB <= 0L) return
        EventJournal.record("resource.evict", mapOf("freedMiB" to freedMiB.toString()))
    }

    fun reclaimForBackgroundFootprint(): Long {
        if (!onMainThread()) {
            Log.w(TAG, "bg.pssfootprint 非 UI 线程调用：跳过（View 缓存只许主线程收）")
            return 0L
        }
        val pssFreed = runCatching { reclaimByProcessFootprint() }
            .onFailure { Log.w(TAG, "bg.pssfootprint 进程总量档失败（继续走堆水位档）", it) }
            .getOrDefault(0L)
        val heapFreed = runCatching { reclaimByHeapWatermark() }
            .onFailure { Log.w(TAG, "bg.heaptop 堆水位档失败（不影响游戏）", it) }
            .getOrDefault(0L)
        return pssFreed + heapFreed
    }

    private fun reclaimByHeapWatermark(): Long {
        if (!onMainThread()) return 0L
        val (usedMiB, maxMiB) = heapSnapshotMiB()
        val background = isBackground()
        val outcome = heapRescue.maybeRescue(
            usedMiB = usedMiB,
            maxMiB = maxMiB,
            background = background,
            nowMs = SystemClock.elapsedRealtime(),
        ) ?: return 0L
        backgroundHeapRescueHits.incrementAndGet()
        val freedMiB = outcome.freedImageMiB + outcome.freedBufferMiB
        footprintFreedMiB.addAndGet(freedMiB)
        lastBackgroundHeapLine = outcome.trailLine()
        Log.i(TAG, "bg.heaptop $lastBackgroundHeapLine")
        runCatching { RunWatch.forceSample(context, outcome.trailLine()) }
        journalEvict(freedMiB)
        return freedMiB
    }

    private fun reclaimByProcessFootprint(): Long {
        if (!onMainThread()) {
            Log.w(TAG, "bg.pssfootprint 非 UI 线程调用：跳过（View 缓存只许主线程收）")
            return 0L
        }
        val pssMiB = processPssMiB()
        val totalRamMiB = deviceTotalRamMiB()
        val config = limiterReadout.config?.sets
        if (!RunTrail.shouldReclaimForBackgroundFootprint(pssMiB, totalRamMiB, config)) return 0L
        val freed = runCatching { releaseResourceImagePixels() }.getOrDefault(0L)
        runCatching { trimGraphicsCaches(deep = true) }
            .onFailure { Log.w(TAG, "bg.pssfootprint native 缓存清理失败（继续）", it) }
        backgroundFootprintHits.incrementAndGet()
        footprintFreedMiB.addAndGet(freed)
        val budget = RunTrail.memoryLimiterBudgetMiB(totalRamMiB, visible = false, config = config)
        val src = limiterReadout.config?.origin?.label ?: "estimate"
        lastBackgroundFootprintLine =
            "pss=${pssMiB}MiB budget=${budget}MiB ram=${totalRamMiB}MiB src=$src freed=${freed}MiB"
        Log.i(TAG, "bg.pssfootprint $lastBackgroundFootprintLine")
        journalEvict(freed)
        return freed
    }

    internal fun reclaimDiagnosticsLine(): String {
        val payload = pngPayloadStatsLine()
        val lastBg = lastBackgroundFootprintLine.ifEmpty { "未命中" }
        val heapDump = runCatching { ProfilingTriggers.ledgerLine(context.filesDir) }.getOrNull()
            ?: "自动堆转储台账：暂无投递"
        return "自救台账：前台进程档 ${foregroundFootprintHits.get()} 次 / " +
            "后台堆水位档 ${backgroundHeapRescueHits.get()} 次（末次=${lastBackgroundHeapLine.ifEmpty { "未命中" }}） / " +
            "后台进程档 ${backgroundFootprintHits.get()} 次 / " +
            "累计收回 ${footprintFreedMiB.get()}MiB；" +
            "${saveCheckpointLedger()}；" +
            "MemoryLimiter src=${limiterReadout.config?.origin?.label ?: "estimate"} " +
            "读表=${limiterReadout.diagnostics}；后台末次=$lastBg；" +
            "$payload；$heapDump"
    }

    /** 页中检查点的回收判据（宿主 midPageCheckpoint 用；判据真源 RunTrail，冷却位在本文本内）。 */
    internal fun reclaimDueAt(usedMiB: Long, maxMiB: Long, nowMs: Long): Boolean =
        RunTrail.shouldReleaseImagePixels(usedMiB, maxMiB, nowMs - lastImageReleaseAtMs)

    /** 页中检查点命中后的回收执行（与回合边界末段同口径，采样前缀不同、不记 EventJournal）。 */
    internal fun pressureCheckpointReclaim(usedMiB: Long, maxMiB: Long, nowMs: Long) {
        lastImageReleaseAtMs = nowMs
        val freed = releaseResourceImagePixels()
        if (freed > 0L) {
            RunWatch.forceSample(context, "midpage.trim freed=${freed}MiB used=${usedMiB}MiB/$maxMiB")
        }
        if (RunTrail.shouldReleaseUnusedGraphics(usedMiB, maxMiB, nowMs - lastUnusedReleaseAtMs)) {
            lastUnusedReleaseAtMs = nowMs
            val capped = releaseUnusedGraphics(usePixelBudget = true, minPixels = BUDGET_REAP_MIN_PIXELS)
            if (capped.buffers > 0) {
                RunWatch.forceSample(
                    context,
                    "midpage.trim.budget n=${capped.buffers}" +
                        " freed=${bytesToMiB(capped.bytes)}MiB used=${usedMiB}MiB/$maxMiB",
                )
            }
        }
    }

    internal fun trimGraphicsCaches(deep: Boolean): NativeTrimReadout {
        val before = nativeCacheSnapshot()
        graphicsFrames()?.trimCaches()
        runCatching { view.releaseMemoryCaches(deep) }
            .onFailure { Log.w(TAG, "内存压力：本屏位图交还失败（继续）", it) }
        val readout = NativeTrimReadout(before, nativeCacheSnapshot(), deep)
        Log.i(TAG, "trim.graphics deep=$deep viewFreed=${readout.viewFreedMiB()}MiB${readout.suffix()}")
        return readout
    }

    internal fun nativeCacheSnapshot(): NativeCacheSnapshot =
        NativeCacheSnapshot(
            bitmapCacheMiB = runCatching { bytesToMiB((graphicsFrames()?.cacheBytes() ?: 0).toLong()) }
                .getOrDefault(0L),
            viewMiB = runCatching { bytesToMiB(view.viewBitmapCacheBytes()) }.getOrDefault(0L),
            nativeAllocMiB = nativeHeapMiB(fallback = 0L),
        )

    private fun heapSnapshotMiB(): Pair<Long, Long> {
        val rt = Runtime.getRuntime()
        val usedMiB = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MIB
        val maxMiB = rt.maxMemory() / BYTES_PER_MIB
        return usedMiB to maxMiB
    }

    private fun nativeHeapMiB(fallback: Long = 0L): Long =
        runCatching { Debug.getNativeHeapAllocatedSize() / BYTES_PER_MIB }.getOrDefault(fallback)

    private fun processPssMiB(): Long {
        val pssKib = runCatching { Debug.getPss() }.getOrDefault(-1)
        return if (pssKib > 0L) pssKib / KIB_PER_MIB else -1L
    }

    private fun deviceTotalRamMiB(): Long = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mem)
        mem.totalMem / BYTES_PER_MIB
    }.getOrDefault(-1L)

    private fun bytesToMiB(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MIB

    private fun pixelsToMiB(pixels: Long): Long = (pixels * BYTES_PER_PIXEL) / BYTES_PER_MIB

    companion object {
        private const val TAG = "GameHost"
        private const val BYTES_PER_PIXEL = 4L
        private const val KIB_PER_MIB = 1024L
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val MIN_UNUSED_RELEASE_PIXELS = 256 * 256
        internal const val BUDGET_REAP_MIN_PIXELS = 64 * 64
    }
}

internal data class NativeCacheSnapshot(
    val bitmapCacheMiB: Long,
    val viewMiB: Long,
    val nativeAllocMiB: Long,
)

internal class NativeTrimReadout(
    val before: NativeCacheSnapshot,
    val after: NativeCacheSnapshot,
    val deep: Boolean,
) {
    fun suffix(): String = NativeTrimPlan.readoutLine(before, after, deep)

    fun viewFreedMiB(): Long =
        if (before.viewMiB >= after.viewMiB) before.viewMiB - after.viewMiB else 0L
}

internal object NativeTrimPlan {
    private const val BYTES_PER_MIB = 1024L * 1024L

    private val DEEP_LEVELS = setOf(
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
    )

    fun isDeep(level: Int): Boolean = level in DEEP_LEVELS

    fun readoutLine(before: NativeCacheSnapshot, after: NativeCacheSnapshot, deep: Boolean): String =
        buildString {
            append(" bitmap=").append(before.bitmapCacheMiB).append("->").append(after.bitmapCacheMiB).append("MiB")
            append(" view=").append(before.viewMiB).append("->").append(after.viewMiB).append("MiB")
            append(" native=").append(before.nativeAllocMiB).append("->").append(after.nativeAllocMiB).append("MiB")
            if (deep) append(" deep")
        }

    fun reportSuffix(snapshot: NativeCacheSnapshot): String =
        "本屏位图 ${snapshot.viewMiB} MiB（native：整屏保留面 + 背景烘焙面 + 行缓存 24 MiB + 行池 6 MiB）/ " +
            "进程 native 驻留 ${snapshot.nativeAllocMiB} MiB（Debug.getNativeHeapAllocatedSize）"

    fun miB(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MIB
}
