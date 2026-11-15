package com.eratw.port.android

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Debug
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.eratw.port.android.ui.EratwShellDialog
import com.eratw.port.core.audio.AUDIO_CHANNEL_SLOT
import com.eratw.port.core.audio.SoundChannelTable
import com.eratw.port.core.audio.TableSoundSink
import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.code.StepLimitExceeded
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigSpecTable
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.ConfigValue
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.diag.LiveSetCensus
import com.eratw.port.core.diag.PageFunctionTrail
import com.eratw.port.core.diag.ProgramDiagnostics
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.BackgroundAdmission
import com.eratw.port.core.fn.BuiltinEvalContext
import com.eratw.port.core.fn.ConfigMethods
import com.eratw.port.core.fn.EngineSurface
import com.eratw.port.core.fn.IMAGE_LOAD_TRACE_SLOT
import com.eratw.port.core.fn.ImageLoadTrace
import com.eratw.port.core.fn.InputMirrorPort
import com.eratw.port.core.fn.KeyEventInputState
import com.eratw.port.core.fn.TextBoxHost
import com.eratw.port.core.fn.mapPoolDiagnostics
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.fn.prefillSharpLinks
import com.eratw.port.core.host.AndroidKeyCodes
import com.eratw.port.core.host.BeginAfterPreflight
import com.eratw.port.core.host.BusyIndicator
import com.eratw.port.core.host.CBG_SLOT
import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.LineBufferConsoleSink
import com.eratw.port.core.host.MappedPngPayloadStore
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.PNG_PAYLOAD_SLOT
import com.eratw.port.core.host.ParallelTask
import com.eratw.port.core.host.PngPayloadStore
import com.eratw.port.core.host.ResourceContents
import com.eratw.port.core.host.RuntimeAux
import com.eratw.port.core.host.SleepingTimerSink
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.SpriteEvictScratch
import com.eratw.port.core.host.SpritePool
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.lexer.LangVariant
import com.eratw.port.core.loader.BootFailureKind
import com.eratw.port.core.loader.BootFailures
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.policy.ErbPatchPolicy
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.BootViewMetrics
import com.eratw.port.core.proc.EchoInputMode
import com.eratw.port.core.proc.EngineFunctionPolicy
import com.eratw.port.core.proc.EngineStampRead
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.RuntimeCompatibilityProfile
import com.eratw.port.core.proc.StateStep
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.core.proc.datingForensics
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveFolderMigrationLog
import com.eratw.port.core.sql.SqlDriver
import com.eratw.port.core.stmt.StmtInternAudit
import com.eratw.port.core.update.UpdateTelemetry
import com.eratw.port.platform.audio.AndroidAudioFocusRequester
import com.eratw.port.platform.audio.AudioFocusSession
import com.eratw.port.platform.audio.AudioFocusTarget
import com.eratw.port.platform.audio.FileSoundSourceResolver
import com.eratw.port.platform.audio.MediaPlayerChannelDecoder
import com.eratw.port.platform.image.AndroidGraphicsSink
import com.eratw.port.platform.image.BitmapImageDecoder
import com.eratw.port.platform.image.GameImageFiles
import com.eratw.port.platform.image.ResourceCsvHost
import com.eratw.port.platform.image.SpriteBitmapSource
import com.eratw.port.platform.sql.AndroidSqliteHost
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.platform.storage.ModStore
import com.eratw.port.platform.text.AndroidGlyphMaskHost
import com.eratw.port.platform.text.AndroidTextMeasure
import com.eratw.port.platform.update.AndroidUpdateCheckHost
import com.eratw.port.platform.update.UpdateAsk
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.toPointing
import com.eratw.port.render.canvas.CanvasConsoleView
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import java.io.File
import java.lang.reflect.Field
import java.nio.file.NoSuchFileException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** 装载/运行期失败的统一前缀（与 `MainActivity` 失败屏同源）。 */
internal const val FAILURE_PREFIX = "启动失败："

/**
 * 「内存不够」失败族所处阶段 —— 失败屏出口与自动降档的唯一判据来源。
 *
 * - [PREFLIGHT_REJECTED]：静态估算已判定上限小于需求，装载未开始，禁止自动重试以免引发进程级 OOM。
 * - [LOAD_OOM]：预检放行后在装载/装配期实际触发 OOM，允许自动切入兼容省内存档重试一次。
 */
internal enum class HeapFailureStage {
    NONE,
    PREFLIGHT_REJECTED,
    LOAD_OOM,
}

/** 预检拒载（甲族）的玩家面文案格式化（单测契约见 `HeapFailureCopyTest`）。 */
internal fun heapPreflightStoppedText(
    maxMiB: Long,
    requiredMiB: Long,
    usedMiB: Long,
    copy: ShellFailureCopy = ShellFailureCopy.ZH,
): String {
    val gapMiB = (requiredMiB - maxMiB).coerceAtLeast(0L)
    return copy.fill(copy.heapPreflightFormat, maxMiB, requiredMiB, gapMiB, usedMiB)
}

/** 装载期 OOM（乙族）的玩家面文案格式化。 */
internal fun heapOomText(
    peakMiB: Long,
    maxMiB: Long,
    stage: String?,
    oomDetail: String,
    copy: ShellFailureCopy = ShellFailureCopy.ZH,
): String {
    val where = if (stage.isNullOrBlank()) "" else copy.fill(copy.heapOomWhereFormat, stage)
    return copy.fill(copy.heapOomFormat, peakMiB, maxMiB, where, oomDetail)
}

/**
 * LIVE-REFLOW 视口重排三段链（视口新列数 -> 核心重排 -> 渲染换屏）的纯函数判据。
 *
 * 仅在 UI 线程由 [CanvasConsoleView.onViewportChange] 触发；唯有 [resize] 返回 true 时才执行 [swap]，
 * 防止核心未重排而渲染侧强行换屏导致 `WidthMismatch` 崩溃。
 */
internal object ViewportReflowChain {

    enum class Outcome {
        CLOSED,
        NO_SESSION,
        RESIZE_REFUSED,
        SWAP_DONE,
        SWAP_NO_FEED,
    }

    /** 重挂渲染桥时判定是否需要按核心当前折行宽重建空屏，以满足 `ConsoleFeed` 宽度一致前置条件。 */
    fun needsScreenAlignOnReattach(liveReflow: Boolean, screenColumns: Int, sinkColumns: Int): Boolean =
        liveReflow && sinkColumns >= 1 && screenColumns != sinkColumns

    fun <S> run(
        closed: Boolean,
        session: S?,
        resize: (S) -> Boolean,
        swap: () -> Boolean,
    ): Outcome {
        if (closed) return Outcome.CLOSED
        val s = session ?: return Outcome.NO_SESSION
        if (!resize(s)) return Outcome.RESIZE_REFUSED
        return if (swap()) Outcome.SWAP_DONE else Outcome.SWAP_NO_FEED
    }
}

/**
 * eraTW 安卓端引擎宿主：负责资源首启解包、产物映射装载、[GameSession] 装配、VM 状态机驱动、
 * 输入与弹窗桥接、多级内存水位自救以及崩溃/冻结诊断采样。
 *
 * ## 线程模型
 * - **UI 线程**：读取构造期配置快照、分发触摸/按键/提交事件、响应生命周期（[onPause]/[onResume]/[close]）、
 *   执行视口重排回调及后台 PSS/堆水位回收。
 * - **VM 线程（`eratw-vm`）**：承载产物预检、流式映射解码、会话装配与 `BeginFlow` 状态机循环。
 */
class GameHost(
    private val storage: GameStorage,
    @Volatile private var view: CanvasConsoleView,
    private val compatLoad: Boolean = false,
    private val flatStatementsOverride: Boolean? = null,
    private val liveReflow: Boolean = false,
    private val disableBadApple: Boolean = true,
    private val echoInput: Boolean = false,
    private val busyIndicator: Boolean = true,
    private val modRuntime: Boolean = false,
    /**
     * RT2 玩家排序配置（L2 组长收口接线）：`UiPrefs.preferredPackageIds` 在 **UI 线程**读好
     * 经构造参数单路传入（与 `modRuntime` 同一条线程纪律：装配期在 VM 线程上不许摸偏好表）。
     *
     * 空表（缺省）= `LinkOrder()` 缺省 `packageId` 规范序，与接线前逐位一致；
     * 非空 = **Kahn 就绪模块优先级**（`CONTRACT-RT2-M2-3.md` §3.1 与 `LinkOrder` KDoc）：
     * 配置 `[a,b,c,d]` 且 `a` 依赖 `d` ⇒ 实际 `[b,c,d,a]` 是正向回归，不是「对完整排列
     * 做最小调整」——产品裁定已冻结，不做第二种政策。
     */
    private val modPackageOrder: List<String> = emptyList(),
    private val failureCopy: ShellFailureCopy = ShellFailureCopy.ZH,
    private val compatProfile: RuntimeCompatibilityProfile? = null,
) {

    // =========================================================================
    // 1. 引擎印章与目标兼容档案
    // =========================================================================

    private val engineStamp: EngineStampRead =
        if (compatProfile != null) EngineStampRead.Missing else EngineProfileStamp.read(storage)

    private val bootProfile: RuntimeCompatibilityProfile = when {
        compatProfile != null -> compatProfile
        engineStamp is EngineStampRead.Present -> engineStamp.profile
        else -> RuntimeCompatibilityProfile.LEGACY_ERATW_4981
    }

    private val compatExplicit: Boolean =
        compatProfile != null || engineStamp is EngineStampRead.Present

    /** 设置页语言：壳层一次取好，后台线程/渲染回调都用这一份（不各自读 SharedPreferences）。 */
    private val settingsLanguage: SettingsLanguage = UiPrefs.settingsLanguage(view.context)

    private val textVariant: LangVariant = langVariantOf(settingsLanguage)

    // =========================================================================
    // 2. 公开与内部可观测状态（UI / 诊断 / 报告读取）
    // =========================================================================

    /** 装载或运行期的首错文案；非 null 是 UI 侧绘制失败屏的唯一信号（必须在资源释放后置位）。 */
    @Volatile
    var failure: String? = null
        private set

    @Volatile
    internal var lastHeapFailureStage: HeapFailureStage = HeapFailureStage.NONE
        private set

    @Volatile
    var lastFailureWasStaleSession: Boolean = false
        private set

    @Volatile
    internal var lastFailureScreenKind: FailureScreenKind = FailureScreenKind.LOAD
        private set

    @Volatile
    var lastReportFile: File? = null
        private set

    @Volatile
    internal var graphicsWiring: GraphicsWiring? = null
        private set

    @Volatile
    var heartbeat: Heartbeat? = null

    @Volatile
    var onConsoleReady: (() -> Unit)? = null

    /**
     * 首屏装载分段进度上报（CONTRACT-BOOTPHASE）：缺省 null = 装置零行为。
     *
     * 照 [onConsoleReady] 的回调惯例由宿主在构造后、start 之前赋值，不进构造参数表
     * （进参数表会改掉 baseline 里既有的 LongParameterList 结构性 ID）。进度源是
     * [LoadTrace.currentProgress] 对真实阶段名序列的折算，不是时间估算；回调在装载线程
     * 触发，宿主负责切回 UI 线程。装载语义、失败屏与 EventJournal 取证面一条不动。
     */
    @Volatile
    internal var loadPhaseSink: ((LoadProgress?) -> Unit)? = null

    @Volatile
    internal var lastInfiniteLoopAlert: InfiniteLoopAlert? = null

    @Volatile
    internal var saveCheckpointHook: (() -> CheckpointSignal)? = { requestSaveCheckpoint() }

    // =========================================================================
    // 3. 核心会话、工作线程与外设桥接状态
    // =========================================================================

    @Volatile
    private var closed = false

    @Volatile
    private var session: GameSession? = null

    @Volatile
    private var thread: Thread? = null

    /**
     * 本宿主唯一的 SQLite 驱动（`CONTRACT-SQLITE-MIGRATION` §3/§5 W3）。
     *
     * 所有权 = **GameHost 生命周期**，不是单次会话：`SessionVault` 接回新 Activity 时
     * 本宿主继续服役，`backToTitle` 也复用同一会话，两处都不该重新装配驱动。真正占
     * 原生连接/语句的是 `SqlSession`（挂在 `store.aux` 的 `"sql"` 槽），由
     * [GameSession.close] 收口；本字段只在 [close]（宿主生命终点）释放，见 [closeSqlDriver]。
     *
     * 线程：只在 VM 线程创建并交给 `GameSession`（与 [SqlDriver] 的线程口径一致）；
     * 置位/清位都在 [sqlDriverLock] 内，UI 线程的 [close] 与 VM 线程的装配不会各建一份。
     */
    @Volatile
    private var sqlDriver: SqlDriver? = null

    private val sqlDriverLock = Any()

    @Volatile
    private var modRuntimeSession: ModRuntimeSession? = null

    @Volatile
    private var lastScreen: ConsoleScreen? = null

    @Volatile
    private var pointerPointing: ConsoleButtonPointing? = null

    @Volatile
    private var bootTrace: LoadTrace? = null

    @Volatile
    private var lastTrace: LoadTrace? = null

    @Volatile
    private var flatStatementsActive = false

    @Volatile
    private var resourceCensusLine: String? = null

    private var pngPayloadStore: MappedPngPayloadStore? = null

    private val sound = DelegatingSoundSink()

    private val focus = AudioFocusSession(
        AndroidAudioFocusRequester(view.context),
        AudioFocusTarget { event ->
            val table = currentAudioTable() ?: return@AudioFocusTarget 0
            table.mixer.onFocusEvent(event).also { changed ->
                Log.i(TAG, "焦点 $event → 挂起/恢复 $changed 条声道")
            }
        },
    )

    // =========================================================================
    // 4. 输入队列、按键状态与翻页耗时统计
    // =========================================================================

    private val pendingBeforeSession = ArrayDeque<PendingInput>()
    private val pendingLock = Any()
    private val inputReceived = AtomicLong(0L)
    private val inputGateDropped = AtomicLong(0L)

    private val turnStartNs = AtomicLong(0L)

    /**
     * 长页忙碌横带 / longturn 的**页计时起点**（与 [turnStartNs] 不是同一把尺，别合并）。
     *
     * [turnStartNs] 记「玩家提交 → `resumeSystem()` 返回」的宿主 step 延迟，只由
     * `pumpUntilTerminal` 在收口时清零。4.981 的 `MAP_LOOP`（魔改内容/qol/qol_MAP.ERB:32-98）
     * 由 `TINPUTSNF` 超时逐帧全量重绘，整段死循环跑在同一次 `resumeSystem()` 里 ⇒ 它
     * 2.9 分钟都不复位，`midPageCheckpoint` 读出的 `pageMs` 一路涨到 173 s
     * （2026-11-03 真机 longturn 报告：94173 ms → 173380 ms），「少女祈祷中…」横带
     * 因此常驻不消 —— 玩家看到的「切换地图后一直加载」。
     *
     * 本尺按**原版 turn 边界**收口：原版每个等待点都结束一次 turn、把控制权交回消息循环
     * （`Process.cs:438-470`），限时等待超时走 `endTimer()` → `RunEmueraProgram("")`
     * 重进脚本（`EmueraConsole.cs:1043/:1068`）同样是新 turn。移植侧据此在每个等待点
     * 把本尺归零（`onWaitBoundaryReached`）：等待期间是 UI 空闲，不算「正在算一页」；
     * 只有玩家提交（`deliver`）才开启新一页的计算计时。这样连续超时帧既不会累积出
     * 虚假长页，也不会把 2 秒的 HOVER_PAUSE 等待算进下一帧的耗时。
     *
     * `turnStartNs` 保持原样 ⇒ `turn.latency` 读数零回归；VM 指令循环每 65536 条指令的
     * `pressureCheckpoint` 才读本尺（`Vm.kt:1664-1668`），等待期间不读。
     */
    private val pageStartNs = AtomicLong(0L)
    private val turnLog = java.util.ArrayDeque<String>()
    private val turnLogLock = Any()
    private val turnPercentiles = TurnPercentiles()

    @Volatile
    private var pageTrail: PageFunctionTrail? = null

    @Volatile
    private var lastPageTrailText: String = ""

    @Volatile
    private var lastBeginFlowStep: StateStep? = null

    // =========================================================================
    // 5. 前后台生命周期与死循环保护时钟
    // =========================================================================

    @Volatile
    private var activityPaused = false
    private val pauseLock = Any()
    private var backgroundPauseIntent = false

    private val loopClockLock = Any()
    private var loopClockBackgroundSinceMs = -1L
    private var loopClockBackgroundTotalMs = 0L

    val keyInput = KeyEventInputState(activeProvider = { !activityPaused }).apply {
        mirror = object : InputMirrorPort {
            override fun read(): String = textBoxHost()?.text ?: ""
            override fun write(text: String) {
                textBoxHost()?.setText(text)
            }
        }
    }

    // =========================================================================
    // 6. 内存水位自救、暂存复用盘与自动存档检查点
    // =========================================================================
    private val memory = MemoryGovernor(
        context = view.context,
        viewProvider = { view },
        onVmThread = { onVmThread() },
        onMainThread = { isMainThread() },
        isBackground = { activityPaused || backgroundPauseIntent },
        currentAux = { currentAux() },
        graphicsFrames = { graphicsWiring?.frames },
        heartbeat = { heartbeat },
        saveCheckpointSignal = { saveCheckpointHook?.invoke() ?: CheckpointSignal.NOT_WIRED },
        saveCheckpointLedger = { saveCheckpoint.ledgerLine() },
        pngPayloadStatsLine = { pngPayloadStore?.stats()?.line() ?: "堆外载荷：未装（回落堆内闭包）" },
        onTurnReclaimSettled = { usedMiB, maxMiB -> maybePinHeapCensusTrail(usedMiB, maxMiB) },
    )

    private val reportResidentScratch = SpriteEvictScratch()

    private var lastMidPageCheckAtMs = -1L
    private var lastLongTurnPinAtMs = -1L
    private var lastCensusPinAtMs = -1L

    private var progressPublishWarned = false
    private var busyIndicatorWarned = false

    private val saveCheckpoint = SaveCheckpointRunner(
        writerProvider = { saveCheckpointWriter() },
        textProvider = { currentSavedataText() },
        beforeWrite = { evictBeforeSaveCheckpoint() },
    )

    // =========================================================================
    // 7. 死前心跳、主线程冻结看门狗与外置内存采样（迁 [RuntimeDiagnostics]）
    // =========================================================================

    private val diagnostics = RuntimeDiagnostics(
        context = view.context,
        viewProvider = { view },
        isClosed = { closed },
        currentSession = { session },
        vmThread = { thread },
        graphicsFrames = { graphicsWiring?.frames },
        currentGfxPool = { currentGfxPool() },
        currentSpritePool = { currentSpritePool() },
        pendingRequest = { pendingRequest() },
        awaitingAnyKey = { awaitingAnyKey() },
        lastBeginFlowStep = { lastBeginFlowStep },
        heapSnapshot = { heapSnapshotMiB() },
        flatStatementsActive = { flatStatementsActive },
    )

    internal fun imageBookkeepingReportLine(): String? = diagnostics.imageBookkeepingReportLine()

    internal fun datingForensicsReportLine(): String? = diagnostics.datingForensicsReportLine()

    fun sampleMemoryIfNeeded(nowMs: Long) = diagnostics.sampleMemoryIfNeeded(nowMs)

    // =========================================================================
    // 8. 统一 UI 询问弹窗通道（迁 [UiPromptCoordinator]；宿主持有死循环取证钩子）
    // =========================================================================

    private val uiPrompts = UiPromptCoordinator(
        viewProvider = { view },
        isClosed = { closed },
        onInfiniteLoopSeen = { activity, alert ->
            lastInfiniteLoopAlert = alert
            runCatching {
                RunWatch.forceSample(activity, LoopAlertReport.trailLine(alert))
            }
        },
    )

    // =========================================================================
    // 9. 资源解包与启动入口（解包域迁 [LoadPipeline]；宿主只留三口委托与启动编排）
    // =========================================================================

    private val loadPipeline = LoadPipeline(
        context = view.context,
        viewProvider = { view },
        onMainThread = { isMainThread() },
        storage = storage,
        failureCopy = failureCopy,
        recordFailure = { kind, text -> recordFailure(kind = kind, text = text) },
    )

    fun artifactsAvailable(): Boolean = loadPipeline.artifactsAvailable()

    fun artifactDir(): File = loadPipeline.artifactDir()

    fun installProgress(): GameAssetInstaller.Progress? = loadPipeline.installProgress()


/**
     * 会话启动前读一次 `emuera.config` 生效配置：字号/行高灌进 [view]，并把**链后条目表**
     * 一并装进返回的快照。
     *
     * 为什么读点在这里：版面（`consoleWidth` / `ConsoleFeed.metrics`）必须在 UI 线程的
     * `start()` 里先定下来，而 `GameSession` 要到 VM 线程构造时才吃配置。历史上这里是
     * 「View 读一遍、会话再读一遍」；`CONTRACT-RT2-CFG16` / `CFG16B` 起**只读这一次**：
     * 返回的 [BootViewMetrics] 同时携带链后 [ConfigStore] 与原始条目表，[com.eratw.port.core.proc.GameSession]
     * 直接消费，不再自读、不再自建链（原版进程级单实例口径：`Program.cs:95` 一次 `LoadConfig`
     * + `ConfigData.cs:494` `SetConfig`）。读失败（首启/权限）就保持 View 的 18/19 缺省并返回
     * null，不阻断开局 —— 会话此时走自己的缺省回落，与接线前逐位一致。
     * `emuera.config` 的改动本来就只在下一次会话生效（见 `SettingsActivity`）。
     *
     * ## 窗口宽（`WindowX`）的接线口径：**链上显式在位、且值不是引擎裸缺省才灌**（2026-11-10 玩家回归根修）
     *
     * 原版 Windows 上 `Config.WindowX` 是「主窗口外框宽」：系统按配置值开窗（`MainWindow.cs:687-688`），
     * 玩家拖拽窗口后 `MainWindow.cs:726` 再把**实际客户区宽回填**进这个静态字段。Android 没有可
     * 调整大小的主窗口，配置宽就是版面宽的唯一输入——「设置页调窗口宽度」要真正改变屏上列数，
     * 就必须把它当**输入**灌进 View（`ScreenLayout.resolve` 第 5 步 `min(窗口宽, 物理内容宽)`，
     * 窄时水平居中）。
     *
     * 但**裸缺省 760 不是输入**。0.2.43（`0664085e`）曾在这里无条件调
     * `applySourceWindowMetrics(effective.windowX, 0)`：旧玩家盘上的 `emuera.config` 若没有
     * `WINDOW WIDTH` 键，`effective.windowX` 读到的就是 `ConfigSpec` 裸缺省 **760**
     * （`ConfigData.cs:56` 的 PC 值）——把「没人配置过」当成「有人要 760」，闭环如下：
     *
     * 1. `760` 脚本 px 经 `CellMetrics.scriptPixels` 折成设备 px 灌进 View；
     * 2. `ScreenLayout.resolve` 第 5 步 `min(窗口宽, 物理内容宽)` 把版面收窄，
     *    列数从守护后的 136+ 掉到 **80**（列数守护只按物理宽压字号，窄化路径绕过它）；
     * 3. `CanvasConsoleView.windowWidthScriptPx()` 又把窄版面反折回约 `760` 脚本 px，
     *    经 `seedHostState` 播成 `WindowX` ⇒ `drawableWidth = 757`；
     * 4. `DRAWLINESTR` 随之缩到 80 格，`MAXWIDTH()` ≈ 80 ⇒ `DEBUG.ERB:575` 的
     *    `MAXWIDTH()/90` 得 0，`:582` 的 `LOCAL:1 % PRINTNUM` 抛「除数为0的除法运算」。
     *    `:570` 的 `/45` 仍得 1，所以报错固定在 582 —— 与玩家屏文逐字吻合。
     *
     * 0.2.42 完全不读这个键：版面满宽、`WindowX` 播种回落 `host.clientWidth`
     * （自 `009c5e81` 起已是脚本 px，与屏上可绘制宽同源），`MAXWIDTH()` = 139 ⇒ 不除零。
     * 但满宽恒定也把「调窗口宽度」做成了死功能——0.2.44 玩家改设置页的窗口宽，屏上一格不动
     * （2026-11-10 玩家反馈「调窗口宽度的功能好像又失效了」）。
     *
     * 根修口径（不造补丁、回到因果本身）：**裸缺省不是玩家输入，显式写下的非缺省值才是**。
     * 两道闸缺一不可：
     *
     * * **在位闸**（`ConfigChainLoad.touchedKeys`）：`applyChainTo` 逐层记下真正写进条目表的
     *   键。链上没写（缺键盘）⇒ 读数本来就是裸缺省 ⇒ 传 `0` = **未接线**，满宽解算。
     * * **裸缺省闸**（值 ≠ `ConfigSpec` 缺省 760）：盘上「键在位、值 760」有三种不可区分的
     *   来源——玩家显式设 760、写盘侧 `EmueraConfigWriter.mergeInto` 的「补全缺键」把裸缺省
     *   追加进旧盘（自 2026-10-20 起存在）、出包根原文；引擎无法区分，而 760 折算恒
     *   ≈81 列（列数 = 宽 ÷ 9.36，与设备无关），**任何设备**都落在 `DEBUG.ERB /90` 除零区
     *   （出包根自带 1800 正是为了躲开它）。所以 760 一律按「没有窗口请求」处理；
     *   玩家真要窄版面，设 ≥844 的值即刻按配置开窗（原版语义，窄版的脚本后果与 PC 相同）。
     *
     * 两闸皆过 ⇒ 灌 `effective.windowX`，与原版「按配置开窗」同因果；窄于物理宽时居中收窄。
     * 出包根 1800 在常规手机上（列数守护生效，`物理宽 = 136×0.52×守护字号`）折算 ≥ 物理内容宽
     * ⇒ `min()` 落物理宽，观感与 0.2.44 相同；守护不生效的大屏（自然字号更小）则按 1800
     * 收窄居中 —— 这正是「按配置开窗」的原版语义，玩家可自行调宽。
     *
     * `WindowY` **不接线**（纵向观感零变化，恒传 0），见 `windowHeightPxFor` 的管线；
     * `ScreenLayout.resolve` / `CanvasConsoleView` 的窗口宽能力由本宿主显式喂。
     *
     * ## 正向链（同源回填，2026-11-08 补完）
     *
     * 接线（或满宽）之后都要把屏上**实际**版面宽经 `view.windowWidthScriptPx()`
     * 反折成脚本 px（未接线时它返回满宽版面的逆折算，不再返回 0），由 `GameSession` /
     * `ConfigMethods.backfillWindowWidth` 在三层链**之后**回填 `WindowX` —— 原版
     * `MainWindow.cs:726` 的「开窗后回填实际客户区宽」语义。回填不接的话，core 的
     * `WindowX` 停在文件值 ⇒ `drawableWidth` 与屏上可绘制宽分叉成两把尺，
     * `MAXWIDTH()/45` 这类脚本读数与屏上列数对不上。
     */
        private fun applyBootFontMetrics(gameRoot: File): BootViewMetrics? {
        val store = ConfigStore()
        val read = runCatching {
            val host = RootedBuiltinHost(gameRoot.toPath())
            val chain = host.applyChainTo(store)
            Triple(host, chain, EffectiveConfig.of(store))
        }.getOrNull() ?: return null
        val (host, chain, effective) = read

        view.applySourceFontMetrics(effective.fontSize, effective.lineHeight)
        val bareDefaultWidth =
            (ConfigSpecTable.specOf(ConfigKey.WindowX)?.defaultValue as? ConfigValue.IntValue)?.value
        val wiredWidth =
            if (ConfigKey.WindowX in chain.touchedKeys && effective.windowX != bareDefaultWidth) {
                effective.windowX
            } else {
                0
            }
        view.applySourceWindowMetrics(wiredWidth, 0)
        // CONTRACT-RT2-CFG16B：同一次读取把「链后 store + 原始条目表」一并装进快照。
        // `GameSession` 直接消费这两样（不再自读 `configOverrides`、不再 `applyChainTo` 自建链），
        // 于是「View 看到的字号」与「会话生效条目/原始条目」只剩一次读盘。
        // 两参字面量保持 A/L7 形状（单读点哨兵钉的就是它），store 与条目表用 copy 挂上。
        return BootViewMetrics(effective.fontSize, effective.lineHeight)
            .copy(configStore = store, rawEntries = host.configOverrides)
    }

    fun start(): Boolean {
        if (closed) return false
        if (thread != null) return true

        (engineStamp as? EngineStampRead.Malformed)?.let { bad ->
            recordFailure(
                kind = FailureScreenKind.ARTIFACT,
                text = failureCopy.fill(failureCopy.engineStampInvalidFormat, stampFailureDetail(bad)),
            )
            return false
        }
        if (view.consoleScreen() == null) {
            recordFailure(kind = FailureScreenKind.LOAD, text = failureCopy.consoleNotLaidOut)
            return false
        }
        if (!loadPipeline.ensureAssetsInstalled()) return false
        if (!loadPipeline.artifactsOnDisk()) {
            recordFailure(kind = FailureScreenKind.ARTIFACT, text = missingArtifacts())
            return false
        }

        val gameRoot = storage.gameRoot()
        // CONTRACT-RT2-CFG16：**单读** —— 同一份字号/行高既喂 View 排版，又作为构造参数进会话。
        val bootMetrics = applyBootFontMetrics(gameRoot)
        val screen = view.consoleScreen() ?: run {
            recordFailure(kind = FailureScreenKind.LOAD, text = failureCopy.consoleNotLaidOut)
            return false
        }
        val metrics = ScriptPixelMetrics(
            clientWidthPx = view.clientWidthPx().toLong(),
            clientHeightPx = view.clientHeightPx().toLong(),
            deviceFontPixelSize = view.cellMetrics().fontPixelSize,
            windowWidthScriptPx = view.windowWidthScriptPx().toLong(),
        )
        // CONTRACT-RT2-CFG16B：`WindowX` **链后回填的唯一生产点**。原版按配置开窗后把实际
        // 客户区宽回填 `Config.WindowX`（`MainWindow.cs:726`）；这里的对应读数是 View 实测
        // 版面宽的同尺逆折算（`metrics.windowWidthScriptPx`，与交给会话的同名参数完全同源）。
        // 快照 store 就是这一刻交给 `GameSession` 的那一份，会话侧因此不再回填（禁止双重）。
        bootMetrics?.configStore?.let { ConfigMethods.backfillWindowWidth(it, metrics.windowWidthScriptPx) }
        val textMeasure = AndroidTextMeasure.fromContext(view.context)

        launchVmWorker(onStartFailure = { throw it }) {
            loadAndRun(gameRoot, screen, metrics, textMeasure, bootMetrics)
        }
        return true
    }

    // =========================================================================
    // 10. 装载流水线、会话装配与状态机泵
    // =========================================================================

    private fun awaitPreviousHosts(trace: LoadTrace) {
        val waiting = SessionHandoff.othersAlive(this)
        if (waiting <= 0) return
        Log.i(TAG, "load.handoff 上一局还有 $waiting 个会话未退出，先等它让出堆再装载（上限 ${SESSION_HANDOFF_WAIT_MS}ms）")
        val beganNs = System.nanoTime()
        val gone = SessionHandoff.awaitOthersGone(this, SESSION_HANDOFF_WAIT_MS)
        val waitedMs = (System.nanoTime() - beganNs) / 1_000_000L
        if (gone) {
            Log.i(TAG, "load.handoff.done 上一局已退出，等待 ${waitedMs}ms")
        } else {
            Log.w(TAG, "load.handoff.timeout 仍有人没退出（等满 ${waitedMs}ms），继续装载 —— 堆基线硬闸会兜底")
        }
        trace.mark("handoff")
    }

    private fun loadAndRun(
        gameRoot: File,
        screen: ConsoleScreen,
        metrics: ScriptPixelMetrics,
        textMeasure: AndroidTextMeasure,
        bootMetrics: BootViewMetrics?,
    ) {
        val trace = LoadTrace(TAG, logEnabled = isDebuggable())
        trace.onSample = { s ->
            EventJournal.record(
                "load.phase",
                mapOf(
                    "stage" to s.stage,
                    "usedMiB" to s.usedMiB.toString(),
                    "maxMiB" to s.maxMiB.toString(),
                    "t" to s.atMs.toString(),
                ),
            )
            // 首屏覆盖层的进度源：与既有取证面串联而非替换；宿主负责切回 UI 线程。
            loadPhaseSink?.invoke(trace.currentProgress())
        }
        lastScreen = screen
        lastTrace = trace
        lastHeapFailureStage = HeapFailureStage.NONE
        lastFailureWasStaleSession = false
        lastFailureScreenKind = FailureScreenKind.LOAD
        trace.mark("start")

        try {
            awaitPreviousHosts(trace)
            if (closed) return

            val dir = File(gameRoot, LoadPipeline.ARTIFACT_DIR)
            val programFile = File(dir, LoadPipeline.PROGRAM_FILE).also(::requireArtifact)
            val metaFile = File(dir, LoadPipeline.META_FILE).also(::requireArtifact)
            trace.gcHint("preflight")

            val flatStatementsOn = flatStatementsOverride
                ?: File(view.context.filesDir, "flat-statements.on").exists()
            val estimate = ProgramLoader.estimateHeap(
                programFile.toPath(),
                metaFile.toPath(),
                flatStatements = flatStatementsOn,
            )
            trace.mark("preflight")

            val forceLoad = File(gameRoot, FORCE_LOAD_MARKER).exists()
            val metaGate = BeginAfterPreflight {
                ParallelTask(META_THREAD_NAME) { BootMetadata.read(metaFile.toPath()) }.start()
            }

            if (!estimate.sufficient && !forceLoad && !isDebuggable() && !compatLoad) {
                metaGate.reject()
                Log.e(TAG, "堆预检不足：$estimate")
                recordFailure(
                    kind = FailureScreenKind.LOAD,
                    heapStage = HeapFailureStage.PREFLIGHT_REJECTED,
                    text = heapPreflightStoppedText(
                        maxMiB = estimate.maxMemoryMiB,
                        requiredMiB = estimate.requiredMiB,
                        usedMiB = trace.usedMiB(),
                    ),
                )
                trace.logPeak()
                return
            }
            if (!estimate.sufficient) {
                trace.warn(
                    "load.preflight.forced 需=${estimate.requiredMiB}MiB 上限=${estimate.maxMemoryMiB}MiB" +
                        "（force_load 标记存在或兼容装载档，继续装载）",
                )
            }
            if (estimate.requiredBytes > estimate.maxMemoryBytes - trace.usedBytes()) {
                trace.warn(
                    "load.preflight.tight 需=${estimate.requiredMiB}MiB 上限=${estimate.maxMemoryMiB}MiB " +
                        "已用=${trace.usedMiB()}MiB（仍继续装载，真 OOM 由失败屏兜底）",
                )
            }

            if (!forceLoad) {
                Runtime.getRuntime().gc()
                var baseline = trace.usedMiB()
                if (baseline > STALE_SESSION_BASELINE_MIB) {
                    Runtime.getRuntime().runFinalization()
                    Runtime.getRuntime().gc()
                    val afterSecond = trace.usedMiB()
                    Log.w(
                        TAG,
                        "load.baseline.retry used=$baseline MiB → $afterSecond MiB" +
                            "（第二趟回收，闸=$STALE_SESSION_BASELINE_MIB MiB）",
                    )
                    baseline = afterSecond
                }
                if (baseline > STALE_SESSION_BASELINE_MIB) {
                    metaGate.reject()
                    Log.e(TAG, "load.baseline.high used=${baseline}MiB（上一局未让出堆，拒绝开局）")
                    recordFailure(
                        kind = FailureScreenKind.LOAD,
                        staleSession = true,
                        text = staleSessionHeap(baseline),
                    )
                    trace.logPeak()
                    return
                }
            }

            val metaTask = if (compatLoad) null else metaGate.begin()
            if (!compatLoad && metaTask == null) {
                Log.e(TAG, "meta 闸状态异常：拒绝后仍试图起线程，按堆不足收口")
                recordFailure(
                    kind = FailureScreenKind.LOAD,
                    heapStage = HeapFailureStage.LOAD_OOM,
                    text = heapOomText(
                        peakMiB = trace.usedMiB(),
                        maxMiB = estimate.maxMemoryMiB,
                        stage = null,
                        oomDetail = "",
                    ),
                )
                trace.logPeak()
                return
            }

            loadPipeline.inflightMeta = metaTask
            val fresh = try {
                assembleSession(
                    gameRoot = gameRoot,
                    programFile = programFile,
                    metaFile = metaFile,
                    screen = screen,
                    metrics = metrics,
                    textMeasure = textMeasure,
                    trace = trace,
                    metaTask = metaTask,
                    flatStatementsOn = flatStatementsOn,
                    bootMetrics = bootMetrics,
                )
            } finally {
                loadPipeline.inflightMeta = null
                if (metaTask != null && !metaTask.cancel(LoadPipeline.META_JOIN_MS)) {
                    Log.w(TAG, "eratw-meta ${LoadPipeline.META_JOIN_MS}ms 内未收口（daemon，随进程退出）")
                }
            }
            if (fresh == null) return
            if (closed) {
                runCatching { fresh.close() }
                return
            }

            sound.delegate = TableSoundSink(
                fresh.store.aux,
                FileSoundSourceResolver(File(gameRoot, SOUND_DIR_NAME)) { file ->
                    MediaPlayerChannelDecoder(file)
                },
            )
            if (!attachSession(fresh)) return

            fresh.inputSink.onTurnReady = { ms -> onTurnBoundaryReached(fresh, ms) }
            // 轻量 turn 边界：每个等待点一次（含超时自动推进的连续等待），只复位页计时与清横带。
            // 绝不能复用 onTurnReady —— 那条挂了存档兑现/堆采样/turn 日志/图像回收等每页一次的重动作，
            // 33 ms 一帧的动画循环会把它们按帧放大几十倍（见 QueueInputSink.onTurnBoundary KDoc）。
            fresh.inputSink.onTurnBoundary = { onWaitBoundaryReached(fresh) }
            fresh.runtime.onPressureCheckpoint = { midPageCheckpoint() }

            val gfx = installGraphics(fresh, gameRoot)
            graphicsWiring = gfx
            fresh.runtime.execContext.timer = SleepingTimerSink
            if (!activityPaused) focus.start()
            bootTrace = trace

            view.post { if (!closed) attachConsoleFeed(fresh, gfx, screen) }
            view.post { bootTrace?.mark("boot.首帧后") }
            trace.mark("ready")
            trace.logPeak()
            DevToolsHost.startOnce(this, view)
            onConsoleReady?.let { cb ->
                onConsoleReady = null
                view.post { cb() }
            }
            drive(fresh)
        } catch (e: InputSinkClosedException) {
            Log.i(TAG, "输入队列已关闭，脚本线程退出")
        } catch (t: Throwable) {
            handleLoadOrRuntimeFault(t, trace)
        }
    }

    private fun onTurnBoundaryReached(fresh: GameSession, ms: Long) {
        clearBusyIndicator(fresh)
        val pageText = pageFlowReadout()
        lastPageTrailText = pageText
        Log.i(TAG, "turn.next ms=$ms")
        EventJournal.record("turn.end", mapOf("ms" to ms.toString()))
        synchronized(turnLogLock) {
            if (turnLog.size >= TURN_LOG_LIMIT) turnLog.removeFirst()
            turnLog.addLast("$ms ms @${System.currentTimeMillis()}")
        }
        turnPercentiles.record(ms)
        heartbeat?.markVm(SystemClock.elapsedRealtime(), "turn ${ms}ms")
        RunWatch.sampleTurn(view.context, "turn ${ms}ms", heartbeat)
        if (ms >= RunTrail.LONG_TURN_MS) {
            lastLongTurnPinAtMs = SystemClock.elapsedRealtime()
            RunWatch.forceSample(
                view.context,
                PageTrailWire.longTurnLine(ms, longTurnFrame(), pageText),
                heartbeat,
            )
        }
        drainSaveCheckpoint("turn")
        modRuntimeSession?.onTurnBoundary()
        memory.releaseImagePixelsIfHeapHigh()
    }

    /**
     * 忙碌横带的**唯一**页侧清除出口（页交付与轻量 turn 边界共用；`close()` 另有幂等清场一处）。
     *
     * 抽成单一函数是为了让「清除点」始终只有一处真相：以前只挂在 `onTurnReady` 上，而
     * `onTurnReady` 需要玩家提交起点才触发，超时自动推进的连续等待点一次都不触发 —— 这正是
     * 魔法之森横带常驻的成因。两处调用都不做任何 I/O，只是把 sink 上的 `busy` 置空。
     */
    private fun clearBusyIndicator(fresh: GameSession) {
        runCatching { fresh.console.setBusyIndicator(null) }.onFailure { t ->
            if (!busyIndicatorWarned) {
                busyIndicatorWarned = true
                Log.w(TAG, "turn.busy.clear", t)
            }
        }
    }

    /**
     * 轻量 turn 边界（VM 线程、每个等待点一次、输入锁之外）。
     *
     * 归零 [pageStartNs] = 「上一页/上一帧已经上屏，等待期间不算长页」；撤掉横带 = 帧已可见。
     * 这里**刻意不跑** [onTurnBoundaryReached] 的存档兑现、堆采样、turn 日志、Mod 节拍与图像回收：
     * 那些是每页一次的重动作，4.981 动态地图循环 33 ms 一帧，按帧跑会把宿主拖死。
     * 复位成 0 而不是 `nanoTime()` 是口径需要：新一页的计算只从玩家提交（`deliver`）起算，
     * 限时等待自身的 2 秒 HOVER_PAUSE 不该被算进下一帧的 `pageMs`。
     */
    private fun onWaitBoundaryReached(fresh: GameSession) {
        pageStartNs.set(0L)
        clearBusyIndicator(fresh)
    }

    /**
     * 装载/运行的异常收口（VM 线程）。关闭协议的 VM 侧出口：**首查 [closed]** ——
     * 宿主已 [close] 时，中断 / 关输入队带来的异常是正常退出，静默返回、一步资源都不碰
     * （释放权在 UI 线程的 [shutdownForExit]，所有权分界见 [close] 的 KDoc）。
     */
    private fun handleLoadOrRuntimeFault(t: Throwable, trace: LoadTrace) {
        if (closed) {
            Log.i(TAG, "装载期间已关闭，脚本线程退出（${t.javaClass.simpleName}: ${t.message}）")
            return
        }
        runCatching { trace.mark(LoadTrace.FAIL_STAGE) }
        lastHeapFailureStage =
            if (BootFailures.classify(t).kind == BootFailureKind.HEAP_TOO_SMALL) {
                HeapFailureStage.LOAD_OOM
            } else {
                HeapFailureStage.NONE
            }
        val failureText = describe(t, trace)
        try {
            writeErrorReport(
                "启动失败",
                t,
                trace.summaryLines().take(24) + listOfNotNull(graphicsPoolReportLine()),
            )
            val deadLoopProgram = runCatching { session?.runtime?.program }.getOrNull()
            runCatching {
                val lines = ProgramDiagnostics.deadLoopSiteLines(t, deadLoopProgram, session?.store)
                if (lines.isNotEmpty()) {
                    lines.forEach { Log.e(TAG, it) }
                    runCatching {
                        File(view.context.filesDir, "deadloop-site.txt").writeText(lines.joinToString("\n"))
                    }
                }
            }.onFailure { Log.e(TAG, "死循环现场转储失败", it) }
            runCatching { trace.logPeak() }
            Log.e(TAG, "启动失败", t)

            currentAux()?.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)?.let {
                Log.e(TAG, "取图现场 reason=${it.reason} path=${it.path}")
            }
            runCatching {
                val ctx = session?.runtime?.evalContext as? BuiltinEvalContext
                val imgTrace = ctx?.aux?.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)
                Log.e(
                    TAG,
                    "取图现场(VM侧) host=${ctx?.host?.javaClass?.simpleName}" +
                        " reason=${imgTrace?.reason ?: "无记录"} path=${imgTrace?.path}",
                )
            }
            runCatching {
                val e = t as? StepLimitExceeded
                val s = session
                if (e != null && s != null && e.pc >= 0 && e.pc < s.program.instructionCount) {
                    val p = s.program
                    Log.e(
                        TAG,
                        "死循环指令现场 pc=${e.pc} line=${e.line} fn=${e.funcName}" +
                            " words=[${p.op(e.pc)},${p.a(e.pc)},${p.b(e.pc)},${p.c(e.pc)}]" +
                            " expr=" + runCatching { p.expr(p.a(e.pc)).toString() }.getOrDefault("?") +
                            " 窗口=" + ((e.pc - 4)..(e.pc + 4)).joinToString(" ") { q ->
                                if (q < 0 || q >= p.instructionCount) return@joinToString ""
                                "p$q:op${p.op(q)},${p.a(q)},${p.b(q)},${p.c(q)}@L${p.lineOf(q)}"
                            },
                    )
                }
            }
        } finally {
            runCatching { shutdownForExit() }
            failure = failureText
        }
    }

    /**
     * 取本宿主唯一的 SQLite 驱动；首次在 VM 线程装配 fresh [GameSession] 前创建。
     *
     * 两个根的语义（`CONTRACT-SQLITE-MIGRATION` §3，W1 工厂的形参）：
     * - `seedRoot = gameRoot`：只读种子根，随包解出的 `plugins/qol_data.db` 首次打开时复制；
     * - `writeRoot = gameRoot/sql`：**可写沙箱**，所有 `SQL_CONNECT` 目标文件都落在这里，
     *   与种子/产物/存档分树，卸载即随应用私有目录清理。
     *
     * 返回 null 只有一个原因：宿主已 [close]（[closed] 在锁内复核），调用方按「装载期
     * 关闭、本次不建会话」处理。驱动创建失败（native 库缺失、ABI 不支持等）**不吞**：
     * 那会让 `SQL_*` 静默拿到 null 驱动而无故报「宿主未接线」，掩盖真实装载错误；
     * 异常沿 `assembleSession` 上抛，由既有失败屏收口。
     */
    private fun sqlDriverFor(gameRoot: File): SqlDriver? = synchronized(sqlDriverLock) {
        if (closed) return null
        sqlDriver ?: run {
            val writeRoot = gameRoot.resolve(SQLITE_WRITE_DIR_NAME)
            AndroidSqliteHost.create(seedRoot = gameRoot, writeRoot = writeRoot).also { created ->
                sqlDriver = created
                Log.i(
                    TAG,
                    "SQLite 驱动已装配：seed=${gameRoot.absolutePath} write=${writeRoot.absolutePath}",
                )
            }
        }
    }

    private fun assembleSession(
        gameRoot: File,
        programFile: File,
        metaFile: File,
        screen: ConsoleScreen,
        metrics: ScriptPixelMetrics,
        textMeasure: AndroidTextMeasure,
        trace: LoadTrace,
        metaTask: ParallelTask<BootMetadata>?,
        flatStatementsOn: Boolean,
        bootMetrics: BootViewMetrics?,
    ): GameSession? {
        StmtInternAudit.enabled = File(view.context.filesDir, "fold-audit.on").exists()
        flatStatementsActive = flatStatementsOn
        val program = ProgramLoader.loadMapped(
            programFile.toPath(),
            compat = compatLoad,
            flatStatements = flatStatementsOn,
        ) { stage ->
            trace.mark(stage)
            if (compatLoad) {
                trace.forceGc("decode")
            } else if (stage.endsWith("池根表") || stage.endsWith("局部串池") || stage.endsWith("语句记录 8/8")) {
                trace.gcHint("decode")
            }
        }
        trace.mark("program")
        program.internDiagnostics?.let { Log.i(TAG, "load.intern $it") }
        if (closed) return null

        val poolAuditMarker = File(view.context.filesDir, "pool-audit.on")
        if (poolAuditMarker.exists()) {
            Log.w(TAG, "pool-audit.on 存在：本次装载会多装 2 遍产物（预计 +2 倍装载耗时），跑完自动删除")
            runCatching {
                val lines = ProgramDiagnostics.auditStatementPoolAt(program, programFile.toPath(), 140944, 140952, 140948)
                lines.forEach { Log.e(TAG, it) }
                runCatching {
                    File(view.context.filesDir, "pool-audit.txt").writeText(lines.joinToString("\n"))
                }
            }.onFailure { Log.e(TAG, "pool-audit 探针失败", it) }
            runCatching { poolAuditMarker.delete() }
                .onSuccess { Log.w(TAG, "pool-audit.on 已消费并删除（一次性探针）") }
        }

        trace.forceGc("meta")
        val metadata = metaTask?.await() ?: BootMetadata.read(metaFile.toPath())
        trace.mark("meta")
        if (closed) return null

        trace.forceGc("session")
        // CONTRACT-SQLITE-MIGRATION W3：真实 SQLite 驱动在 fresh GameSession 的构造点装配，
        // 同一宿主跨会话复用（不重复建），驱动随宿主关闭（见 closeSqlDriver）。
        // 宿主已关闭时返回 null：本次不再建会话，也不留下一个无人关闭的驱动。
        val driver = sqlDriverFor(gameRoot) ?: return null
        // RT2 G2 波（P1-5 余量一）：ERB Mod 链的非旁路生产装配点。关态零接触；开态空表
        // （mods/rt2/ 只有 manifest）完全旁路返回 null ⇒ program 原样、dispatch null、
        // 逐位不变；有 .erbm 容器即真装配，坏档 / 配对不齐 / 摘要不符整包 fail-closed
        // （不半装、不静默丢弃）。builtinVariableNames 走惰性 lambda：关态与空表连
        // 静态表都不建（B1 纪律）。
        val csvContext = if (modRuntime) {
            // M1-4 阶段二：装载相位先产出注入上下文（唯一一次 ManifestLoader.load），
            // 让 Mod 链装配与 GameSession 扩容共用同一份快照/容量/计划（裁定 3）。
            ModRuntimeAssembly.csvLoadContext(gameRoot) { line -> Log.w(TAG, "modcsv: $line") }
        } else {
            null
        }
        val modChain = ModChainAssembly.assemble(
            ChainAssemblyInputs(
                gameRoot = gameRoot,
                enabled = modRuntime,
                base = program,
                baseMetas = metadata.functions,
                builtinVariableNames = { ModRuntimeAssembly.builtinVariableNames() },
                csvContext = csvContext,
                // L2 组长收口接线：玩家排序配置 → LinkOrder（Kahn 就绪优先级；空表 = 缺省规范序）。
                linkOrder = com.eratw.port.core.patch.LinkOrder(modPackageOrder),
            ),
        )
        val fresh = GameSession(
            gameRoot = gameRoot.toPath(),
            program = program,
            metadata = metadata,
            compatProfile = bootProfile,
            engineSurface = if (compatExplicit) EngineSurface(bootProfile.engineFamily) else null,
            modsDirectory = metadata.programSha256?.let { ModStore.modsDirFor(gameRoot).toPath() },
            // CONFIG-OVERLAY（ADR-005 例外登记）：出厂 InfiniteLoopAlertTime 5000→360000
            //（2026-11-12 主人裁定 360 s）只填空不改写，余量依据见 PortFactoryConfig 类头。
            configOverlay = PortFactoryConfig.overlayFor(gameRoot.toPath()),
            baseProgramSha256 = metadata.programSha256?.lowercase(),
            consoleWidth = screen.clientWidth,
            maxStepsPerRun = GameSession.PRODUCTION_MAX_STEPS_PER_RUN,
            maxRunMillis = GameSession.PRODUCTION_MAX_RUN_MILLIS,
            wallClockMs = { loopGuardClockMs() },
            backgroundMs = { loopClockBackgroundMs(SystemClock.elapsedRealtime()) },
            soundSink = sound,
            inputState = keyInput,
            writeRoot = gameRoot.toPath(),
            stageMark = { s -> trace.mark(s) },
            clientWidthPx = metrics.clientWidthPx,
            clientHeightPx = metrics.clientHeightPx,
            deviceFontPixelSize = metrics.deviceFontPixelSize,
            windowWidthScriptPx = metrics.windowWidthScriptPx,
            textMeasureRun = { req ->
                textMeasure.measurePixels(req.text, req.fontName, req.fontSize, req.styleMask, req.charSpace)
            },
            frameAvailableOf = { name -> graphicsWiring?.frames?.frameAvailable(name) ?: true },
            fontWidth = view.fontDisplayWidth,
            saveMigrationPrompt = uiPrompts.saveMigrationPrompt,
            infiniteLoopPrompt = uiPrompts.infiniteLoopPrompt,
            glyphMaskHost = AndroidGlyphMaskHost(view.context),
            disabledFunctions = if (disableBadApple) EngineFunctionPolicy.DEFAULT_DISABLED else emptySet(),
            reflowConsole = liveReflow,
            echoInputMode = if (echoInput) EchoInputMode.REPLICA else EchoInputMode.SUPPRESSED,
            // RT2 G2 波：链接产物三视图（program / FunctionTable / dispatch 同源）非 null 时
            // GameSession 三处取值只从这里来；null = 完全旁路（今天生产恒此态）。
            modChain = modChain,
            functionDispatch = null,
            modCsvContext = csvContext,
            bootViewMetrics = bootMetrics,
            sqlDriver = driver,
        )
        trace.mark("session")

        wirePageFunctionTrail(fresh)
        wireUpdateCheckHost(fresh)
        wireSaveMemoryReclaim(fresh)
        wireCorpusNativeMethods(fresh)

        val sharpWarnings = prefillSharpLinks(fresh.store.aux, fresh.runtime.program)
        for (w in sharpWarnings) Log.w(TAG, "CALLSHARP 装载警告：$w")
        fresh.recordSharpLoadWarnings(sharpWarnings)

        modRuntimeSession = ModRuntimeAssembly.attach(
            gameRoot = gameRoot,
            store = fresh.store,
            nativeRegistry = nativeMethods(fresh.store.aux),
            onVmThread = { onVmThread() },
            enabled = modRuntime,
            logSink = { line -> Log.i(TAG, "modrt: $line") },
            modProviders = if (modRuntime) ModCarrierRegistry.providers() else emptyList(),
        )
        SaveFolderMigrationLog.of(fresh.store)?.latest?.notice?.let { notice ->
            view.post {
                if (!closed) {
                    Toast.makeText(view.context, "${notice.title}\n${notice.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        if (closed) {
            fresh.close()
            return null
        }
        return fresh
    }

    private fun installGraphics(session: GameSession, gameRoot: File): GraphicsWiring {
        val layer = GraphicsLayer { name ->
            BackgroundAdmission.admissible(session.store.aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)?.find(name))
        }
        val metrics = view.cellMetrics()
        val decoder = session.store.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) { BitmapImageDecoder() }
        runCatching {
            val store = MappedPngPayloadStore(File(view.context.cacheDir, "png-payload").toPath())
            store.sweepStale()
            session.store.aux.slot<PngPayloadStore>(PNG_PAYLOAD_SLOT) { store }
            pngPayloadStore = store
        }.onFailure { Log.w(TAG, "PNG 堆外载荷通道未装（回落堆内闭包）", it) }

        val spritePool = session.store.aux.slot<SpritePool>(SpriteBitmapSource.SLOT_SPRITE) { SpritePool() }
        val contents = ResourceContents.load(spritePool, ResourceCsvHost(gameRoot, decoder))
        resourceCensusLine = ResourceContents.censusLine(contents)
        Log.i(TAG, "CSV 资源表：文件=${contents.fileCount} sprite=${contents.spriteCount} 动画=${contents.animeCount}")
        contents.warnings.forEach { Log.w(TAG, "CSV 资源：$it") }

        val frames = SpriteBitmapSource(session.store.aux, GameImageFiles(gameRoot))
        session.runtime.execContext.graphics = AndroidGraphicsSink(
            layer,
            metrics,
            scriptFontPixelSize = session.scriptFontPixelSize,
            onImage = frames::prefetchAsync,
            sizeSource = frames,
        ) { view.notifyGraphicsChanged() }
        Log.i(
            TAG,
            "图形通路已接：decoder=${decoder.javaClass.simpleName} sink=AndroidGraphicsSink" +
                " 字体=${metrics.fontPixelSize}px 半角=${metrics.cellPixelWidth}px" +
                " 脚本字号=${session.scriptFontPixelSize}px(px 折算分母)" +
                " 资源根=${gameRoot.absolutePath}",
        )
        return GraphicsWiring(layer, frames)
    }

    internal class GraphicsWiring(val layer: GraphicsLayer, val frames: SpriteBitmapSource)

    private fun attachConsoleFeed(fresh: GameSession, gfx: GraphicsWiring, screen: ConsoleScreen) {
        bootTrace?.mark("boot.控制台挂载")
        view.attachGraphics(gfx.layer, gfx.frames)
        runCatching { gfx.frames.setDecodeReadyListener(view::onGraphicResourceReady) }
        view.attachAnimeRepaint(gfx.frames::animeRepaintIntervalMs)

        val metrics = view.cellMetrics()
        val maxImageWidth = (view.windowColumns() * metrics.cellPixelWidth).toInt()
        val sinkColumns = fresh.console.clientWidth
        val feedScreen = if (
            ViewportReflowChain.needsScreenAlignOnReattach(liveReflow, screen.clientWidth, sinkColumns)
        ) {
            Log.i(TAG, "重挂渲染桥：按核心折行宽换屏 ${screen.clientWidth} -> $sinkColumns")
            ConsoleScreen(
                sinkColumns,
                screen.maxLog,
                screen.viewport.heightLines.coerceAtLeast(1),
                fontWidth = view.fontDisplayWidth,
            ).also { lastScreen = it }
        } else {
            screen
        }

        view.attachFeed(
            ConsoleFeed(
                fresh.console,
                feedScreen,
                gfx.layer,
                metrics,
                gfx.frames,
                bandAvailWidthPx = maxImageWidth,
                bandMaxHeightPx = view.bandMaxHeightPx(),
                scriptFontPixelSize = fresh.scriptFontPixelSize,
                prefetch = gfx.frames::prefetchAsync,
            ),
        )

        if (liveReflow) {
            view.onViewportChange = { cols, rows, widthPx, heightPx, fontPx ->
                val outcome = runCatching {
                    ViewportReflowChain.run(
                        closed = closed,
                        session = session,
                        resize = { s -> s.resizeConsole(cols, widthPx.toLong(), heightPx.toLong(), fontPx) },
                        swap = { view.reflowAttachedFeed(cols, rows) },
                    )
                }.getOrElse {
                    Log.e(TAG, "视口重排异常 cols=$cols rows=$rows（本局照常，下一次布局再问）", it)
                    null
                }
                if (outcome != null) {
                    Log.i(TAG, "视口重排 cols=$cols rows=$rows px=${widthPx}x$heightPx font=$fontPx -> $outcome")
                    if (outcome == ViewportReflowChain.Outcome.SWAP_DONE) {
                        lastScreen = view.consoleScreen() ?: lastScreen
                    }
                }
            }
        }
    }

    private fun drive(fresh: GameSession) {
        try {
            fresh.begin(BeginType.TITLE)
        } catch (e: CodeEE) {
            if (closed) {
                Log.i(TAG, "宿主关闭中断，脚本线程退出（${e.message ?: e.javaClass.simpleName}）")
                return
            }
            if (!isRecoverableScriptError(e)) throw e
            scriptErrorTerminal(fresh, e)
            stopAudioForTerminal(fresh)
            return
        }
        pumpUntilTerminal(fresh)
    }

    private fun scriptErrorTerminal(fresh: GameSession, e: CodeEE) {
        val location = scriptLocation(e)
        val message = e.langKey?.render(textVariant, *e.langArgs.toTypedArray())
            ?: (e.message ?: e.javaClass.simpleName)
        val text = message + location
        runCatching { fresh.console.printError(text) }
            .onFailure { Log.w(TAG, "脚本错误上屏失败（继续收口）", it) }
        recordFailure(
            kind = FailureScreenKind.IN_GAME,
            text = failureCopy.fill(failureCopy.scriptAbortFormat, text),
        )
        writeErrorReport(
            "脚本运行报错",
            e,
            listOf(
                "中止点：系统状态机 ${fresh.runtime.systemState.name}（语句级脚本错误，原版由 DoScript 顶层 catch 收口）",
                "证据：Process.cs:438-478 / Process.cs:571-661",
            ),
        )
        Log.e(TAG, "脚本错误终局（会话保留，可回标题）：$text", e)
    }

    private fun stopAudioForTerminal(fresh: GameSession) {
        runCatching { focus.release() }
        runCatching { fresh.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT)?.closeAll() }
            .onFailure { Log.w(TAG, "终局清场：声道关闭失败（会话保留，继续）", it) }
    }

    private fun pumpUntilTerminal(fresh: GameSession): Boolean {
        var terminal = false
        while (!closed && !fresh.runtime.quitRequested) {
            val step: StateStep = try {
                fresh.resumeSystem()
            } catch (e: CodeEE) {
                if (closed) break
                if (!isRecoverableScriptError(e)) throw e
                scriptErrorTerminal(fresh, e)
                terminal = true
                break
            }
            lastBeginFlowStep = step
            if (closed) break

            val startedNs = turnStartNs.getAndSet(0L)
            if (startedNs != 0L) {
                val ms = (System.nanoTime() - startedNs) / 1_000_000L
                Log.i(TAG, "turn.latency ms=$ms outcome=${step.outcome} from=${step.from.name}")
            }

            if (step.outcome == StepOutcome.BLOCKED || step.outcome == StepOutcome.FAILED) {
                recordFailure(
                    kind = FailureScreenKind.IN_GAME,
                    text = failureCopy.fill(
                        failureCopy.stateMachineHaltFormat,
                        step.from.name,
                        step.blocker?.message ?: failureCopy.stateMachineUnknownBlocker,
                        step.blocker?.evidence ?: failureCopy.stateMachineNoEvidence,
                    ),
                )
                writeErrorReport(
                    "脚本运行报错",
                    null,
                    listOf(
                        "状态机终局：${step.from.name}",
                        "阻塞：${step.blocker?.message ?: "未知阻塞"}",
                        "证据：${step.blocker?.evidence ?: "无"}",
                    ),
                )
                terminal = true
                break
            }
        }
        if (terminal) {
            stopAudioForTerminal(fresh)
        } else if (!closed) {
            shutdownForExit()
        }
        return terminal
    }

    fun canRetryCompat(): Boolean = lastHeapFailureStage != HeapFailureStage.NONE

    fun canBackToTitle(): Boolean =
        session != null && !closed && failure != null && thread?.isAlive != true

    /**
     * 活会话三要件 + 未关闭的合并判定（let 链保住智能转换，免去调用点的非空断言）。
     * 任一不成立返回 null，由 [reattachView] 统一按「接回被拒」处理。
     */
    private fun reattachTarget(): Triple<GameSession, GraphicsWiring, ConsoleScreen>? {
        if (synchronized(pauseLock) { closed }) return null
        return session?.let { active ->
            graphicsWiring?.let { gfx ->
                lastScreen?.let { screen -> Triple(active, gfx, screen) }
            }
        }
    }

    /**
     * 会话升进程级（2026-11-13）：新 [MainActivity] 实例把本宿主接回到**自己的**视图上。
     *
     * 只在 UI 线程调用（Activity 装配处）。活会话的三要件——会话本体、图形接线、
     * 最后一屏 [ConsoleScreen]——缺一不可（没有它们就没有可重挂的渲染桥）；全在才换绑：
     * [view] 指向新视图，然后走 [attachConsoleFeed] 原路重挂（该函数自带「重挂渲染桥」
     * 的对齐语义，`backToTitle` 的 `view.post { attachConsoleFeed(...) }` 是同一形状）。
     * 弹窗（`UiPromptCoordinator` 动态 `view.context.findActivity()`）、Toast、RunWatch
     * 的 Context 取用全部随 `view.context` 自动落到新 Activity，无需逐处换绑。
     *
     * 旧视图上未跑的 `view.post` 随旧视图销毁一起丢弃（那是旧壳的渲染帧，接回方不需要）。
     * 重挂后第一帧由新视图的布局/重绘链自然带出，[MainActivity] 侧重排重绘循环即可
     * （`onResume` 的既有路径）。
     *
     * view 声明为 `@Volatile private var` 的理由：换绑只在 UI 线程发生，但 VM 线程经
     * `view.post` 读它——@Volatile 保证换绑的可见性。另注意主构造参数区不写多行注释
     * （detekt 的 LongParameterList 签名会拼参数区注释原文，一行注释改一次 baseline）。
     *
     * @return false = 宿主已关 / 会话或图形接线不在（调用方按无会话处理，走重开路径）
     */
    fun reattachView(newView: CanvasConsoleView): Boolean {
        val target = reattachTarget()
        if (target == null) {
            // 四种拒因（会话未装配 / 图形接线不在 / 最后一屏不在 / 宿主已关）合并一行：
            // 真机取证看 [SessionVault] 轨迹与下文的冷启装载即可，不必逐因分行。
            Log.i(TAG, "接回被拒：会话/图形接线/最后一屏/宿主至少一项不在")
            return false
        }
        synchronized(pauseLock) { view = newView }
        attachConsoleFeed(target.first, target.second, target.third)
        return true
    }

    /** [SessionVault.takeoverIfAlive] 的接回判据口（宿主终态；close 一经置位不再翻转）。 */
    val isClosedForTakeover: Boolean get() = synchronized(pauseLock) { closed }

    fun backToTitle(): Boolean {
        val active = session ?: return false
        val gfx = graphicsWiring ?: return false
        val screen = lastScreen ?: return false
        if (!canBackToTitle()) return false
        failure = null

        return launchVmWorker(
            onStartFailure = { t ->
                recordFailure(
                    kind = FailureScreenKind.IN_GAME,
                    text = failureCopy.fill(failureCopy.backToTitleFailedFormat, t.message ?: t.javaClass.simpleName),
                )
            },
        ) {
            try {
                val first = active.gotoTitle()
                Log.i(TAG, "gotoTitle: outcome=${first.outcome} from=${first.from.name}")
                view.post { if (!closed) attachConsoleFeed(active, gfx, screen) }
                pumpUntilTerminal(active)
            } catch (t: Throwable) {
                if (backToTitleFaultAction(closed) == BackToTitleFaultAction.SUPPRESS_BY_CLOSE) {
                    Log.i(TAG, "回到标题途中宿主关闭，静默收口（${t.message ?: t.javaClass.simpleName}）")
                } else {
                    recordFailure(
                        kind = FailureScreenKind.IN_GAME,
                        text = failureCopy.fill(
                            failureCopy.backToTitleFailedFormat,
                            (t.message ?: t.javaClass.simpleName) + scriptLocation(t),
                        ),
                    )
                    Log.e(TAG, "回到标题失败", t)
                    writeErrorReport("回到标题失败", t)
                }
            }
        }
    }

    // =========================================================================
    // 11. 输入派发、鼠标指向与会话查询
    // =========================================================================

    fun dispatchKey(androidKeyCode: Int, down: Boolean) {
        val vk = AndroidKeyCodes.toWinVirtualKey(androidKeyCode) ?: return
        if (down) keyInput.onKeyDown(vk) else keyInput.onKeyUp(vk)
    }

    fun fireVirtualKeyEdge(virtualKey: Int) {
        keyInput.onKeyDown(virtualKey)
        keyInput.onKeyUp(virtualKey)
    }

    fun submit(text: String) = submitInternal(text, mesSkip = false)

    fun submitButton(text: String) =
        submitInternal(text, mesSkip = false, clickIntent = true, mouseButton = 1)

    fun submitSkip(text: String) = submitInternal(text, mesSkip = true)

    fun submitMappedColor(color: Long) {
        synchronized(pendingLock) {
            session?.inputSink?.submitMappedColor(color)
        }
    }

    fun submitCbgButtonValue(value: Long) {
        synchronized(pendingLock) {
            session?.inputSink?.submitCbgButtonValue(value)
        }
    }

    private fun submitInternal(
        text: String,
        mesSkip: Boolean,
        clickIntent: Boolean = false,
        mouseButton: Int? = null,
    ) {
        EventJournal.record(
            "input.submit",
            mapOf(
                "kind" to when {
                    mouseButton != null -> "button"
                    clickIntent -> "click"
                    else -> "text"
                },
                "len" to text.length.toString(),
            ),
        )
        keyInput.clearClickIntent()
        if (mouseButton == null) fireVirtualKeyEdge(AndroidKeyCodes.VK_RETURN)
        synchronized(pendingLock) {
            val sink = session?.inputSink
            if (sink != null) {
                deliver(sink, text, mesSkip, clickIntent, mouseButton)
            } else if (!closed) {
                pendingBeforeSession.addLast(PendingInput(text, mesSkip, mouseButton))
            }
        }
    }

    private fun deliver(
        sink: QueueInputSink,
        text: String,
        mesSkip: Boolean,
        clickIntent: Boolean = false,
        mouseButton: Int? = null,
    ) {
        inputReceived.incrementAndGet()
        if (!sink.isAwaiting) {
            inputGateDropped.incrementAndGet()
            if (clickIntent) keyInput.noteClickIntent(text)
            if (mesSkip) sink.noteSkipIntent()
            return
        }
        turnStartNs.compareAndSet(0L, System.nanoTime())
        // 新一页开始：长页判据的起点。超时自动推进不经过这里（没有玩家提交），
        // 故 MAP_LOOP 的连续帧不会被误记成一页；见 pageStartNs KDoc。
        pageStartNs.set(System.nanoTime())
        if (mouseButton != null) {
            sink.submitButtonClick(text, mouseButton, mesSkip)
        } else {
            sink.submit(text, mesSkip)
        }
    }

    private fun attachSession(fresh: GameSession): Boolean {
        val closedBeforeAttach = synchronized(pendingLock) {
            if (closed) {
                pendingBeforeSession.clear()
                true
            } else {
                session = fresh
                synchronized(pauseLock) { fresh.setBackgroundPaused(backgroundPauseIntent) }
                keyInput.clearClickIntent()
                installMouse(fresh)
                fresh.inputSink.onWaitEntered = { keyInput.clearClickIntent() }
                while (pendingBeforeSession.isNotEmpty()) {
                    val queued = pendingBeforeSession.removeFirst()
                    deliver(
                        fresh.inputSink,
                        queued.text,
                        queued.mesSkip,
                        mouseButton = queued.mouseButton,
                    )
                }
                false
            }
        }
        if (closedBeforeAttach) runCatching { fresh.close() }
        return !closedBeforeAttach
    }

    private fun installMouse(fresh: GameSession) {
        val mouse = mouseStateOf(fresh.store.aux)
        mouse.windowCreated = true
        mouse.hitTest = { _, _ -> pointerPointing }
        pointerPointing = null
    }

    fun updatePointer(viewX: Float, viewY: Float) {
        val live = session ?: return
        val (clientWidth, clientHeight) = view.clientSizePx()
        if (clientWidth <= 0 || clientHeight <= 0) return
        val mouse = mouseStateOf(live.store.aux)
        val x = view.clientX(viewX)
        val y = view.clientY(viewY)
        mouse.setClientSize(clientWidth, clientHeight)
        mouse.setPointerPosition(x, y)
        mouse.windowCreated = true
        pointerPointing = view.buttonAtClient(x, y)?.toPointing()
    }

    fun pendingRequest(): InputRequest? = session?.inputSink?.awaiting

    fun currentOptions(): List<ConsoleOption> {
        val s = session ?: return emptyList()
        return if (s.inputSink.isAwaiting) s.console.currentOptions() else s.console.publishedOptions()
    }

    /**
     * 屏行尾窗的**窄只读**快照（竖屏 HUD 的数据来源，显示序、最旧在前）。
     *
     * 返回尾 [limit] 行的 `serial to textOf`（行文本口径同 `LineBufferConsoleSink.textOf`），
     * 与改动前 HUD 自己拼的 `lines().takeLast(limit).map { serial to textOf }` 逐字等价。
     * [limit] 的语义：`<= 0` 一律返回空表（不把负数交给 `takeLast` 猜）；大于现存行数时返回
     * 全部行。会话未装配、已关闭或尚未产生显示行时返回空表。
     *
     * **边界**：只交出不可变快照（列表每次新分配，元素是不可变 `Pair`），不暴露
     * [GameSession] / `LineBufferConsoleSink` / `inputSink` / `store`——HUD 只显示、零指令提交
     * （竖屏设计稿 §0-12）；需要完整会话的 DevTools / `ModImportSessionChannel` 仍走
     * [debugSession]。
     *
     * 线程：UI 线程调用（HUD 250ms 轮询与 `GameHost.close()` 都在主线程）；快照本身在
     * `lines()` 的 `displayLines` 锁内完成，元素不可变，不在本口上额外承诺跨线程读。
     */
    fun consoleTail(limit: Int): List<Pair<Long, String>> = consoleTailOf(session?.console, limit)

    /**
     * 画面按钮命中的世代闸：判据归控制台所有（[LineBufferConsoleSink.isCurrentGeneration]），
     * 壳层只转发，不在这里另算一份「可点集合」。
     *
     * 原先壳层自己读 `publishedOptions()` 按值比对，并对空表短路放行；那两处口径与
     * `EmueraConsole.cs:2724`/`:2740` 的逐按钮世代判据不等价，也是 0.2.45 收件箱
     * `ABLNAME:900/1150` 那批「上一屏按钮值串进下一屏 INPUT」的直接放行口。
     * 会话未装配时没有可点的按钮 ⇒ 不放行（原先返回 true 的 fail-open 一并无条件收回）。
     */
    fun isCurrentGeneration(value: String): Boolean =
        session?.console?.isCurrentGeneration(value) ?: false

    fun awaitingAnyKey(): Boolean = session?.inputSink?.awaitingAnyKey == true

    fun textBoxHost(): TextBoxHost? = session?.builtinHost?.textBox

    fun acceptance(): ButtonAcceptance {
        val sink = session?.inputSink ?: return ButtonAcceptance.ANY
        if (sink.awaitingAnyKey) return ButtonAcceptance.ANY
        return when (sink.awaiting?.type) {
            InputRequest.InputType.INT -> ButtonAcceptance.INT
            InputRequest.InputType.STR -> ButtonAcceptance.STR
            null -> ButtonAcceptance.ANY
        }
    }

    fun isRunning(): Boolean = thread?.isAlive == true

    fun liveConfigStore(): ConfigStore? = session?.configStore

    // =========================================================================
    // 12. 内存与像素池分级回收、页中检查点与自动存档安全点
    // =========================================================================

    private fun requestSaveCheckpoint(): CheckpointSignal {
        val waiting = runCatching { session?.inputSink?.isAwaiting == true }.getOrDefault(false)
        val signal = saveCheckpoint.request(vmWaitingInput = waiting)
        Log.i(TAG, "bg.heaptop.save 登记=${signal.token()} vmWaiting=$waiting")
        return signal
    }

    private fun drainSaveCheckpoint(where: String) {
        if (!onVmThread()) return
        val result = runCatching {
            saveCheckpoint.drainAtSafePoint(where, SystemClock.elapsedRealtime())
        }.onFailure {
            Log.w(TAG, "bg.heaptop.save 兑现失败（游戏照常，只少一笔存档）", it)
        }.getOrNull() ?: return
        Log.i(TAG, "bg.heaptop.save ${result.line}")
        runCatching { RunWatch.forceSample(view.context, "save.auto ${result.line}") }
    }

    private fun saveCheckpointWriter(): SaveWriter? {
        val ops = session?.runtime?.execContext?.systemOps as? FileSystemOps ?: return null
        return { slot, text -> ops.saveData(slot, text) }
    }

    private fun currentSavedataText(): String {
        val existing = runCatching { session?.store?.readStr("SAVEDATA_TEXT", longArrayOf()) }.getOrNull()
        if (!existing.isNullOrBlank()) return existing
        return runCatching {
            SAVEDATA_DATE_FORMAT.get()?.format(Date()).orEmpty() + " "
        }.getOrDefault("")
    }

    private fun evictBeforeSaveCheckpoint() {
        if (!onVmThread()) return
        runCatching { memory.releaseResourceImagePixels() }
            .onFailure { Log.w(TAG, "save.evict 可重解像素回收失败（照存）", it) }
        runCatching {
            memory.releaseUnusedGraphics(usePixelBudget = true, minPixels = MemoryGovernor.BUDGET_REAP_MIN_PIXELS)
        }
            .onFailure { Log.w(TAG, "save.evict 预算档回收失败（照存）", it) }
    }

    private fun onVmThread(): Boolean = Thread.currentThread() === thread

    private fun midPageCheckpoint() {
        val now = SystemClock.elapsedRealtime()
        if (!RunTrail.shouldMidPageCheck(now - lastMidPageCheckAtMs)) return
        lastMidPageCheckAtMs = now

        runCatching { session?.console?.publishProgressFrame() }.onFailure { t ->
            if (!progressPublishWarned) {
                progressPublishWarned = true
                Log.w(TAG, "midpage.progress", t)
            }
        }
        heartbeat?.markVm(SystemClock.elapsedRealtime(), "midpage")
        val strongDone = memory.drainReclaimRequests("midpage", "页中检查点")
        drainSaveCheckpoint("midpage")

        // 长页判据只读按 turn 边界收口的页尺；turnStartNs 归 pumpUntilTerminal 的 step 延迟专用。
        val startedNs = pageStartNs.get()
        val pageMs = if (startedNs == 0L) 0L else (System.nanoTime() - startedNs) / 1_000_000L
        if (startedNs != 0L && RunTrail.shouldShowBusyIndicator(pageMs, busyIndicator)) {
            runCatching { session?.console?.setBusyIndicator(BusyIndicator(pageMs)) }.onFailure { t ->
                if (!busyIndicatorWarned) {
                    busyIndicatorWarned = true
                    Log.w(TAG, "midpage.busy", t)
                }
            }
        }

        val pinDue = RunTrail.shouldPinLongTurn(pageMs, now - lastLongTurnPinAtMs)
        val (usedMiB, maxMiB) = heapSnapshotMiB()
        val reclaimDue = !strongDone && memory.reclaimDueAt(usedMiB, maxMiB, now)
        if (!pinDue && !reclaimDue) return

        if (pinDue) {
            lastLongTurnPinAtMs = now
            RunWatch.forceSample(
                view.context,
                PageTrailWire.longTurnLine(pageMs, longTurnFrame(), pageFlowReadout(), "${usedMiB}MiB/$maxMiB"),
                heartbeat,
            )
        }
        if (reclaimDue) {
            memory.pressureCheckpointReclaim(usedMiB, maxMiB, now)
        }
    }

    private fun longTurnFrame(): String {
        val live = session ?: return "-"
        return VmStackInspector.topFrame(live.runtime)
    }

    internal fun reclaimDiagnosticsLine(): String = memory.reclaimDiagnosticsLine()

    internal fun trimGraphicsCaches(deep: Boolean): NativeTrimReadout = memory.trimGraphicsCaches(deep)

    internal fun nativeCacheSnapshot(): NativeCacheSnapshot = memory.nativeCacheSnapshot()

    fun releaseResourceImagePixels(): Long = memory.releaseResourceImagePixels()

    fun reclaimForBackgroundFootprint(): Long = memory.reclaimForBackgroundFootprint()

    fun requestStrongReclaim() = memory.requestStrongReclaim()
    // =========================================================================
    // 13. 宿主接线（流水探针 / 更新检查 / 存档前回收 / 本地方法表）与弹窗交互
    // =========================================================================

    private fun pageFlowReadout(): String = pageTrail?.readout().orEmpty()

    private fun wirePageFunctionTrail(fresh: GameSession) {
        val trail = PageFunctionTrail()
        trail.bindNameResolver(ErrorReportCollector.functionNameResolver(fresh.runtime))
        pageTrail = trail
        lastPageTrailText = ""
        PageTrailWire.attachSink(fresh.inputSink, trail)
        if (!PageTrailWire.attachVm(fresh.runtime, trail)) {
            Log.w(TAG, "pageTrail 未能挂到 VM（ProcessRuntime.vm / VirtualMachine.pageTrail 改名？）")
        }
    }

    private fun wireUpdateCheckHost(session: GameSession) {
        val ops = session.runtime.execContext.systemOps as? FileSystemOps
        if (ops == null) {
            Log.i(TAG, "UPDATECHECK：会话的 systemOps 不是 FileSystemOps，保持原实现口径")
            return
        }
        ops.updateCheckHost = AndroidUpdateCheckHost(
            forbidProvider = { session.configStore.itemOf(ConfigKey.ForbidUpdateCheck).bool() },
            transport = uiPrompts.updateTransport,
            ask = UpdateAsk { title, message -> uiPrompts.askUpdateCheck(title, message) },
            linkOpener = { url -> uiPrompts.openUpdateLink(url) },
        )
    }

    private fun wireSaveMemoryReclaim(session: GameSession) {
        val ops = session.runtime.execContext.systemOps as? FileSystemOps
        if (ops == null) {
            Log.i(TAG, "SAVEDATA：会话的 systemOps 不是 FileSystemOps，跳过堆贴顶求援接线")
            return
        }
        ops.reclaimMemory = reclaim@{ urgent ->
            if (!urgent) {
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                val max = rt.maxMemory()
                if (max <= 0L || used * 100L < max * SAVE_PRE_RECLAIM_PERCENT) return@reclaim 0L
            }
            val freedImageMiB = memory.releaseResourceImagePixels()
            var total = freedImageMiB * BYTES_PER_MIB
            var buffers = 0
            var released = memory.releaseUnusedGraphics(
                usePixelBudget = true,
                minPixels = MemoryGovernor.BUDGET_REAP_MIN_PIXELS,
            )
            buffers += released.buffers
            total += released.bytes
            if (released.buffers == 0) {
                released = memory.releaseUnusedGraphics()
                buffers += released.buffers
                total += released.bytes
            }
            if (total > 0L) {
                Runtime.getRuntime().gc()
                val cause = if (urgent) "堆贴顶求援" else "存档前预防"
                Log.w(
                    TAG,
                    "SAVEDATA $cause：图片 ${freedImageMiB}MiB + 缓冲 $buffers 个 / ${bytesToMiB(total)}MiB",
                )
            } else if (urgent) {
                Log.w(TAG, "SAVEDATA 堆贴顶求援：没有可回收的像素/缓冲")
            }
            total
        }
    }

    private fun wireCorpusNativeMethods(session: GameSession) {
        val registry = nativeMethods(session.store.aux)
        val absent = ErbPatchPolicy.absentNativeMethodNames()
        registry.markPolicyAbsent(absent)
        Log.i(TAG, "CALLSHARP 本地方法表（本版无 .NET 插件）：注册 ${registry.names()} · 策略禁用 $absent")
    }

    // =========================================================================
    // 14. 生命周期管理（onPause / onResume / setBackgroundPaused / close）
    // =========================================================================

    private fun loopClockIsBackground(): Boolean =
        activityPaused || synchronized(pauseLock) { backgroundPauseIntent }

    private fun noteLoopClockState(nowMs: Long) {
        val background = loopClockIsBackground()
        synchronized(loopClockLock) {
            if (background) {
                if (loopClockBackgroundSinceMs < 0L) loopClockBackgroundSinceMs = nowMs
            } else {
                val since = loopClockBackgroundSinceMs
                if (since >= 0L) {
                    loopClockBackgroundTotalMs += nowMs - since
                    loopClockBackgroundSinceMs = -1L
                }
            }
        }
    }

    private fun loopClockBackgroundMs(nowMs: Long): Long = synchronized(loopClockLock) {
        val running = loopClockBackgroundSinceMs
        loopClockBackgroundTotalMs + if (running >= 0L) (nowMs - running).coerceAtLeast(0L) else 0L
    }

    private fun loopGuardClockMs(): Long {
        val now = SystemClock.elapsedRealtime()
        noteLoopClockState(now)
        return now - loopClockBackgroundMs(now)
    }

    fun onPause() {
        activityPaused = true
        noteLoopClockState(SystemClock.elapsedRealtime())
        diagnostics.flushLastBreath()
        uiPrompts.cancelPendingSaveMigrationPrompt()
        focus.onHostPaused()
    }

    fun onResume() {
        activityPaused = false
        noteLoopClockState(SystemClock.elapsedRealtime())
        runCatching { diagnostics.resetStallWatch() }
        focus.onHostResumed()
    }

    fun setBackgroundPaused(paused: Boolean) {
        synchronized(pauseLock) {
            if (closed) return
            backgroundPauseIntent = paused
            session?.setBackgroundPaused(paused)
        }
        noteLoopClockState(SystemClock.elapsedRealtime())
    }

    /**
     * 幂等清场唯一入口（onDestroy / 失败收口 / 换局三条路共用）：**一步都不许抛** —— 任一步裸抛，
     * 后面的 `thread?.interrupt()` 与 [shutdownForExit] 都不会执行：VM 线程继续跑、会话的
     * 映射与 fd 全留着，而下一局装载既等不到旧会话退出（[SessionHandoff] 那头还在等），
     * 也回收不到堆（真机「返回键退出再进 → load.baseline.high used=215MiB」正是这个形状）。
     *
     * ## 与 VM 线程的关闭协议（资源释放所有权，2026-11-13 外部审计后回写）
     *
     * 本函数**不等 VM 线程退出**（销毁路径不许被合法长链拖住 —— 实测最坏合法链 2.9 亿
     * 指令 ≈130s），所有权分界就是 [closed] 标志：
     * 1. `closed = true` 后，VM 侧在三处检查点观察中断 —— 热循环每 65536 步（`Vm.kt`
     *    的 `shutdownInterruptedAt`）、阻塞等待点与帧边界（`ProcessRuntime`）—— 分别抛
     *    `CodeEE「执行被中断（宿主正在关闭）」` 与 `InputSinkClosedException`；
     * 2. 异常上浮进 [handleLoadOrRuntimeFault] / [pumpUntilTerminal] 后**首查 [closed]：
     *    命中即静默返回，一步资源都不碰** —— [shutdownForExit] 的释放权在 UI 线程侧；
     * 3. [GameSession.close] 自身幂等，且 `Program.releaseMmap` 有 pin 前提闸：惰性回读
     *    未完成时通道保持打开（映射保持有效），VM 收尾 drain 期间不存在解映射竞态；
     * 4. 下一局装载经 [SessionHandoff.awaitOthersGone] 等旧 VM 线程死亡（超时由堆基线闸兜底）。
     *
     * 两条顺序铁律：
     * 1. **先放行三把询问闩（迁移 / UPDATECHECK / 死循环）再打断**：interrupt 对
     *    `CountDownLatch.await` 可中断、能收口，但主动放行给的是**确定的**读数
     *    （迁移按否 / RESULT=3 / [InfiniteLoopAnswer.NoUi]），不是超时兜底。
     * 2. **先摘音频出口（[DelegatingSoundSink.shut]）再打断**：打断只能叫醒阻塞点，
     *    纯计算语句要等 VM 主循环的 65536 指令检查点；这期间后到的 PLAYBGM/PLAYSOUND
     *    会经 TableSoundSink 重新 play，出口不先关死，退出后新建的 MediaPlayer 再没有
     *    人 release（原版 Program.cs:97-104 是进程退出前最后一次清场，本移植 Activity
     *    销毁与脚本线程是两条命，必须先把出口关死）。
     */
    fun close() {
        synchronized(pauseLock) {
            if (closed) return
            closed = true
            backgroundPauseIntent = false
        }
        runCatching { uiPrompts.cancelPendingSaveMigrationPrompt() }
            .onFailure { Log.w(TAG, "关闭：迁移询问取消失败（继续收后面的）", it) }
        runCatching { session?.console?.setBusyIndicator(null) }
            .onFailure { Log.w(TAG, "关闭：忙碌指示器清除失败（继续收后面的）", it) }
        runCatching { uiPrompts.cancelPendingUpdatePrompt() }
            .onFailure { Log.w(TAG, "关闭：UPDATECHECK 询问取消失败（继续收后面的）", it) }
        runCatching { uiPrompts.cancelPendingInfiniteLoopPrompt() }
            .onFailure { Log.w(TAG, "关闭：死循环告警取消失败（继续收后面的）", it) }
        runCatching { sound.shut() }
            .onFailure { Log.w(TAG, "关闭：音频出口关闭失败（继续收后面的）", it) }
        runCatching { thread?.interrupt() }
            .onFailure { Log.w(TAG, "关闭：VM 线程打断失败（继续收后面的）", it) }

        if (!loadPipeline.cancelInflightMeta()) {
            Log.w(TAG, "装载中途关闭：eratw-meta ${LoadPipeline.META_JOIN_MS}ms 内未收口（daemon，随进程退出）")
        }
        // 会话升进程级（2026-11-13）：close = 宿主生命的终点，认主出槽。所有 close 路
        // （玩家退出 / restartGame 拆旧 / 启动失败收口）都在 UI 线程走到这里，一处收口
        // 不漏；旧壳迟到的 close 不会把新壳刚接回的槽撅空（认主语义，见 SessionVault）。
        SessionVault.discard(this)
        shutdownForExit()
        closeSqlDriver()
    }

    /**
     * 宿主生命终点释放 SQLite 驱动（[close] 专用）。
     *
     * 为什么不放进 [shutdownForExit]：那里同时是 QUIT 正常退出与失败收口的清场口，宿主
     * 本身还活着（失败屏、生命周期回调、诊断仍要读宿主状态），而驱动是宿主级单例；
     * 在会话清场时丢掉它，下一次装配会重建第二份。连接与语句也不是这里释放的 ——
     * 它们由 `GameSession.close` 的 `SqlSession` 先关（[shutdownForExit] 已调用），
     * 本函数只处理驱动对象本身。
     *
     * [SqlDriver] 端口按契约 §1 只有 `open`、没有 `close`；当前 W1 的
     * `AndroidXSqliteDriver` 除连接外不持有原生资源。因此这里只在实现额外实现了
     * [AutoCloseable] 时释放，不给端口补一个假 close。
     */
    private fun closeSqlDriver() {
        val driver = synchronized(sqlDriverLock) {
            val current = sqlDriver
            sqlDriver = null
            current
        } ?: return
        val closeable = driver as? AutoCloseable ?: return
        runCatching { closeable.close() }
            .onFailure { Log.w(TAG, "退出清场：SQLite 驱动关闭失败（继续收后面的）", it) }
    }

    /**
     * 退出清场：放焦点、停全部声道、关会话。与 [close] 同一套幂等语义
     * （[AudioFocusSession.release] / `closeAll` / `GameSession.close` 各自幂等，重复调用无害），
     * 供 [close] 与 drive() 的 QUIT 正常退出路径共用。
     *
     * ## 谁在什么线程上跑（三条路）
     * * `onDestroy`（UI 线程）经 [close]；
     * * 装载/脚本失败的收口（VM 线程自身）经 [handleLoadOrRuntimeFault] —— 仅在宿主未
     *   [close] 时可达（该入口首查 [closed]，命中即退）；
     * * QUIT 正常退出（VM 线程自身）经 [pumpUntilTerminal] —— 同上。
     *
     * VM 线程走到本函数时它就是**最后一步**：返回后这条线程不再执行任何脚本语句，
     * 不存在「清场之后又出声 / 又取图」的窗口。与 UI 线程 [close] 并发双跑（QUIT 的同一
     * 瞬间用户退出）时，每步 [runCatching] 兜住、逐步幂等：`graphicsWiring` 置 null 后
     * 另一侧跳过位图缓存关闭、[ModRuntimeSession.shutdown] 二次调用 no-op、
     * `GameSession.close` 幂等，末尾重复赋值无害。
     *
     * **先摘渲染桥再放会话**：`attachConsoleFeed` 把 `GraphicsLayer`（内含强持 `session`
     * 的准入 lambda）与 `SpriteBitmapSource`（持 aux 与位图缓存）交给了活得比会话久的
     * [CanvasConsoleView]；视图不销毁这两条边就在，VM 线程退了整局 Program 也回收不掉
     * （真机「保存并重启」第二次装载起点 used=293MiB 就是这条链）。`detachGraphics()`
     * 会 `invalidate()`，只能在 UI 线程直接跑；VM 线程路径按线程分派 post 出去（晚几
     * 毫秒无妨：下一次装载的 attachGraphics 本来就会覆写）。
     *
     * **为什么 QUIT 也要清**：原版 QUIT = 退出进程，BGM/音效随进程自然终结；移植版脚本
     * QUIT 只是 VM 线程正常返回，不显式停的话声道会一直播、焦点不释放。清完置
     * [session] 为 null（与失败路径同口径），让 [submit] / 焦点回调对已退出的会话全部
     * no-op。音频出口闸（`sound.shut()`）只在 [close] 一侧放（见其 KDoc 顺序铁律 2）。
     */
    private fun shutdownForExit() {
        runCatching {
            val clear = Runnable {
                view.onViewportChange = null
                view.detachGraphics()
            }
            if (isMainThread()) clear.run() else view.post(clear)
        }.onFailure { Log.w(TAG, "退出清场：摘渲染桥/视口回调失败（继续收后面的）", it) }

        runCatching { graphicsWiring?.frames?.close() }
            .onFailure { Log.w(TAG, "退出清场：位图缓存关闭失败（继续收后面的）", it) }
        graphicsWiring = null

        runCatching {
            (currentAux()?.peek<PngPayloadStore>(PNG_PAYLOAD_SLOT) as? AutoCloseable)?.close()
        }.onFailure { Log.w(TAG, "退出清场：PNG 载荷通道关闭失败（继续收后面的）", it) }

        runCatching { focus.release() }
            .onFailure { Log.w(TAG, "退出清场：音频焦点释放失败（继续收后面的）", it) }

        val audioFaults = runCatching {
            currentAudioTable()?.closeAll() ?: 0
        }.getOrElse {
            Log.w(TAG, "退出清场：声道关闭失败（继续收后面的）", it)
            -1
        }
        if (audioFaults > 0) {
            Log.w(TAG, "退出清场：$audioFaults 条声道未释放（平台 release 抛异常，已逐个隔离）")
        }

        runCatching { modRuntimeSession?.shutdown() }
            .onFailure { Log.w(TAG, "退出清场：Mod 运行时 shutdown 失败（继续收后面的）", it) }
        modRuntimeSession = null

        val released = runCatching { session?.close() }
            .onFailure { Log.w(TAG, "退出清场：会话关闭抛异常（引用照常置空）", it) }
            .getOrNull()
        if (released == false) {
            Log.w(TAG, "产物通道未释放：惰性回读仍有未 pin 住的段（语句段串表超 pin 上限或段 2 换块），fd 保留优先")
        }
        session = null
        ErrorReportCollector.resetThrottle("shutdownForExit")
        lastScreen = null
    }

    // =========================================================================
    // 15. DevTools 探针、诊断报告取证行与死前心跳 / 内存采样
    // =========================================================================

    fun debugTurnLog(): List<String> = synchronized(turnLogLock) { turnLog.toList() }

    fun debugTurnPercentilesSummary(): String = turnPercentiles.summaryLine()

    fun debugLoadTraceLines(): List<String> = lastTrace?.summaryLines() ?: emptyList()

    fun debugVmThread(): Thread? = thread

    fun debugInputGateCounters(): Pair<Long, Long> = inputReceived.get() to inputGateDropped.get()

    fun debugInputTrail(): QueueInputSink.InputTrailReadout? =
        (session?.inputSink as? QueueInputSink)?.trailReadout()

    fun debugPageFlow(): String = pageFlowReadout()

    fun debugLastPageFlow(): String = lastPageTrailText

    fun debugConsoleLines(n: Int): List<String> = session?.console?.tailText(n).orEmpty()

    fun debugRevision(): Long = session?.console?.revision ?: -1L

    fun debugSession(): GameSession? = session

    internal fun graphicsPoolReportLine(): String? {
        val stats = runCatching { currentGfxPool()?.stats() }.getOrNull() ?: return null
        val bitmapCacheMiB = bytesToMiB((graphicsWiring?.frames?.cacheBytes() ?: 0).toLong())
        val csvResidentMiB = bytesToMiB(
            runCatching {
                currentSpritePool()?.resourceResidentPixelBytes(reportResidentScratch) ?: 0L
            }.getOrDefault(0L),
        )
        val nativeTail = runCatching { NativeTrimPlan.reportSuffix(memory.nativeCacheSnapshot()) }
            .getOrDefault("")
            .let { if (it.isEmpty()) "" else " / $it" }
        val bitmapBudgetMiB = bytesToMiB(SpriteBitmapSource.defaultNativeCacheBudgetBytes())
        val demotedNativeFrames = runCatching { graphicsWiring?.frames?.demotedPngFrames() ?: 0L }.getOrDefault(0L)
        return "图形池：${stats.entries} 项 / ${stats.created} 张已创建 / " +
            "像素驻留 ${bytesToMiB(stats.pixelBytes)} MiB（Java 堆）/ " +
            "压缩驻留 ${stats.demoted} 张（PNG 降级档，内容无损）/ " +
            "降级 native 取图 $demotedNativeFrames 次（不等同像素晋升）/ " +
            "合成源缺失 ${stats.sourceMisses} 笔 / " +
            "负号段 CSV 父图像素驻留 $csvResidentMiB MiB（Java 堆，与图形池记账互不重叠）/ " +
            "位图缓存 $bitmapCacheMiB MiB（native 堆，不计入 Java 堆上限；预算 $bitmapBudgetMiB MiB）" +
            nativeTail
    }

    internal fun memoryCensusReportLine(): String? {
        val live = session ?: return null
        return runCatching {
            val aux = live.store.aux
            LiveSetCensus.census(
                program = live.runtime.program,
                store = live.store,
                console = live.console,
                gfxPool = aux.peek<GraphicsPool>(GRAPHICS_SLOT),
                spritePool = aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE),
                fullScan = true,
                residentFace = reportResidentScratch,
            ).render()
        }.getOrNull()
    }

    internal fun resourceCensusReportLine(): String =
        resourceCensusLine ?: ResourceContents.censusUnloadedLine()

    internal fun maybePinHeapCensusTrail(usedMiB: Long, maxMiB: Long): String? {
        val now = SystemClock.elapsedRealtime()
        if (!HeapCensusPin.shouldPin(usedMiB, maxMiB, now - lastCensusPinAtMs)) return null
        lastCensusPinAtMs = now
        val line = runCatching { censusTrailLine(usedMiB, maxMiB) }.getOrNull() ?: return null
        runCatching { RunWatch.forceSample(view.context, line, heartbeat) }
        return line
    }

    private fun censusTrailLine(usedMiB: Long, maxMiB: Long): String? {
        val live = session ?: return null
        val aux = live.store.aux
        return LiveSetCensus.census(
            program = live.runtime.program,
            store = live.store,
            console = live.console,
            gfxPool = aux.peek<GraphicsPool>(GRAPHICS_SLOT),
            spritePool = aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE),
            fullScan = false,
            residentFace = reportResidentScratch,
        ).let { "heapcensus used=${usedMiB}MiB/$maxMiB " + it.render() }
    }

    // =========================================================================
    // 16. 错误归因、文案格式化与报告收集
    // =========================================================================

    private fun writeErrorReport(kind: String, error: Throwable?, extraLines: List<String> = emptyList()) {
        EventJournal.record("error", mapOf("kind" to kind, "phase" to lastFailureScreenKind.name))
        val suppressed = runCatching { ErrorReportCollector.throttleSummary() }.getOrDefault(emptyList())
        lastReportFile = ErrorReportCollector.collect(
            view.context,
            this,
            kind,
            error,
            suppressed + extraLines,
            heartbeat,
        )
        ErrorReportUploader.uploadPendingAsync(view.context)
    }

    private fun describe(t: Throwable, trace: LoadTrace): String {
        val classified = BootFailures.classify(t)
        lastFailureScreenKind = when (classified.kind) {
            BootFailureKind.MISSING_ARTIFACT,
            BootFailureKind.CORRUPT_ARTIFACT,
            BootFailureKind.MAPPING_FAILED,
            -> FailureScreenKind.ARTIFACT
            else -> FailureScreenKind.LOAD
        }
        if (classified.kind == BootFailureKind.HEAP_TOO_SMALL) return heapShortageDuringLoad(t, trace)
        if (classified.kind == BootFailureKind.MISSING_ARTIFACT) return missingArtifacts()
        val msg = classified.detail?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "$FAILURE_PREFIX$msg${scriptLocation(t)}"
    }

    private fun oomDetail(t: Throwable): String {
        val e = t.findLastCauseOfType<OutOfMemoryError>() ?: return ""
        val firstLine = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
        val body = if (firstLine != null) "${e.javaClass.simpleName}: $firstLine" else e.javaClass.simpleName
        return failureCopy.fill(failureCopy.oomDetailPrefixFormat, body)
    }

    private fun scriptLocation(t: Throwable): String {
        val codeErr = t.findFirstCauseOfType<CodeEE>() ?: return ""
        val parts = ArrayList<String>(2)
        codeErr.funcName?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (codeErr.line >= 0) parts += failureCopy.fill(failureCopy.scriptLocationLineFormat, codeErr.displayLine)
        return if (parts.isEmpty()) "" else failureCopy.fill(failureCopy.scriptLocationWrapFormat, parts.joinToString(" "))
    }

    private fun heapShortageDuringLoad(t: Throwable, trace: LoadTrace): String {
        val peak = trace.peakHeap()
        return heapOomText(
            peakMiB = peak?.usedMiB ?: trace.usedMiB(),
            maxMiB = trace.maxMiB(),
            stage = peak?.stage,
            oomDetail = oomDetail(t),
        )
    }

    private fun staleSessionHeap(usedMiB: Long): String {
        val maxMiB = bytesToMiB(Runtime.getRuntime().maxMemory())
        return failureCopy.fill(failureCopy.staleSessionHeapFormat, usedMiB, maxMiB)
    }

    private fun missingArtifacts(): String {
        val dirs = storage.candidateArtifactDirs(LoadPipeline.ARTIFACT_DIR)
            .joinToString(failureCopy.artifactDirsSeparator) { it.absolutePath }
        return failureCopy.fill(
            failureCopy.missingArtifactsFormat, dirs,
            LoadPipeline.PROGRAM_FILE, LoadPipeline.META_FILE,
        )
    }

    private fun stampFailureDetail(stamp: EngineStampRead.Malformed): String {
        val raw = if (stamp.raw.length <= STAMP_ECHO_LIMIT) {
            stamp.raw
        } else {
            stamp.raw.take(STAMP_ECHO_LIMIT) + "…"
        }
        return if (raw.isEmpty()) stamp.reason else "$raw；${stamp.reason}"
    }

    private fun requireArtifact(file: File) {
        if (!file.isFile) throw NoSuchFileException(file.path)
    }

    // =========================================================================
    // 17. 内部统一工具函数与辅助结构
    // =========================================================================

    private fun recordFailure(
        kind: FailureScreenKind,
        text: String,
        heapStage: HeapFailureStage? = null,
        staleSession: Boolean? = null,
    ) {
        heapStage?.let { lastHeapFailureStage = it }
        staleSession?.let { lastFailureWasStaleSession = it }
        lastFailureScreenKind = kind
        failure = text
    }

    private fun launchVmWorker(
        onStartFailure: (Throwable) -> Unit,
        block: () -> Unit,
    ): Boolean {
        val worker = Thread(
            {
                try {
                    block()
                } finally {
                    SessionHandoff.unregister(this@GameHost)
                }
            },
            VM_THREAD_NAME,
        )
        SessionHandoff.register(this)
        thread = worker
        return try {
            worker.start()
            true
        } catch (t: Throwable) {
            SessionHandoff.unregister(this)
            thread = null
            onStartFailure(t)
            false
        }
    }

    private fun currentAux(): RuntimeAux? = session?.store?.aux

    private fun currentGfxPool(): GraphicsPool? = currentAux()?.peek(GRAPHICS_SLOT)

    private fun currentSpritePool(): SpritePool? = currentAux()?.peek(SpriteBitmapSource.SLOT_SPRITE)

    private fun currentAudioTable(): SoundChannelTable? = currentAux()?.peek(AUDIO_CHANNEL_SLOT)

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private fun isDebuggable(): Boolean =
        (view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun bytesToMiB(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MIB



    private fun heapSnapshotMiB(): Pair<Long, Long> {
        val rt = Runtime.getRuntime()
        val usedMiB = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MIB
        val maxMiB = rt.maxMemory() / BYTES_PER_MIB
        return usedMiB to maxMiB
    }


    private class PendingInput(
        val text: String,
        val mesSkip: Boolean,
        val mouseButton: Int? = null,
    )

    private class DelegatingSoundSink : SoundSink {
        @Volatile
        var delegate: SoundSink = NoopSoundSink

        @Volatile
        private var shut = false

        fun shut() {
            shut = true
            delegate = NoopSoundSink
        }

        private fun live(): SoundSink? = if (shut) null else delegate

        override fun playSound(name: String, count: Int) {
            live()?.playSound(name, count)
        }

        override fun stopSound() {
            live()?.stopSound()
        }

        override fun playBgm(name: String) {
            live()?.playBgm(name)
        }

        override fun stopBgm() {
            live()?.stopBgm()
        }

        override fun setSoundVolume(volume: Int) {
            live()?.setSoundVolume(volume)
        }

        override fun setBgmVolume(volume: Int) {
            live()?.setBgmVolume(volume)
        }
    }

    /** 缓存 `ProcessRuntime.vm` 及 `VirtualMachine.funcStack/depth` 反射句柄，避免长页检查点重复反射查找。 */
    private object VmStackInspector {
        private val runtimeVmField: Field? by lazy {
            runCatching {
                ProcessRuntime::class.java.getDeclaredField("vm").apply { isAccessible = true }
            }.getOrNull()
        }

        @Volatile
        private var cachedVmClass: Class<*>? = null

        @Volatile
        private var funcStackField: Field? = null

        @Volatile
        private var depthField: Field? = null

        fun topFrame(runtime: ProcessRuntime): String {
            val frames = runCatching {
                val vm = runtimeVmField?.get(runtime) ?: return@runCatching emptyList()
                val vmClass = vm.javaClass
                if (cachedVmClass !== vmClass) {
                    funcStackField = vmClass.getDeclaredField("funcStack").apply { isAccessible = true }
                    depthField = vmClass.getDeclaredField("depth").apply { isAccessible = true }
                    cachedVmClass = vmClass
                }
                val fs = funcStackField?.get(vm) as? IntArray ?: return@runCatching emptyList()
                val depth = depthField?.getInt(vm) ?: return@runCatching emptyList()
                VmStack.frameLines(fs, depth, 1, ErrorReportCollector.functionNameResolver(runtime))
            }.getOrNull().orEmpty()
            return frames.firstOrNull()?.trim()?.removePrefix("#0")?.trim() ?: "-"
        }
    }

    companion object {
        const val FORCE_LOAD_MARKER = "force_load"
        private const val SOUND_DIR_NAME = "sound"

        /** SQLite 可写沙箱根（相对 [GameStorage.gameRoot]）；种子/产物/存档与它分树。 */
        private const val SQLITE_WRITE_DIR_NAME = "sql"
        private const val VM_THREAD_NAME = "eratw-vm"

        internal const val RM_GID_BITS_VAR = "RM_GID_BITS"
        internal const val RM_GID_BIT_ELEMENTS = 100
        internal const val RM_GMAP_NAME = "map:グラフィックス"

        private const val META_THREAD_NAME = "eratw-meta"
        private const val SESSION_HANDOFF_WAIT_MS = 15_000L
        private const val TAG = "GameHost"
        private const val STAMP_ECHO_LIMIT = 400
        private const val SAVE_PRE_RECLAIM_PERCENT = 90L
        private const val STALE_SESSION_BASELINE_MIB = 120L
        private const val TURN_LOG_LIMIT = 64
        private const val BYTES_PER_MIB = 1024L * 1024L

        /**
         * [consoleTail] 的纯函数缝：app 的 JVM 单测面实例化不了 `GameHost`（Android 壳），
         * 尾窗口径抽到这里由 `PortraitConsoleTailTest` 用真 `LineBufferConsoleSink` 钉住 ——
         * 与 `MainActivity.effectiveFontColumns` 同款「判据 / 取值抽成 companion 纯函数」口径。
         *
         * 锁口径同 `LineBufferConsoleSink.lines()` 本体：快照在 `displayLines` 锁内拷出，
         * 元素是不可变 `DisplayLine`，锁外映射 `textOf` 安全。
         */
        internal fun consoleTailOf(sink: LineBufferConsoleSink?, limit: Int): List<Pair<Long, String>> {
            if (limit <= 0 || sink == null) return emptyList()
            return sink.lines().takeLast(limit).map { it.serial to sink.textOf(it) }
        }

        private val SAVEDATA_DATE_FORMAT = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue(): SimpleDateFormat =
                SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ROOT)
        }
    }
}

private data class ScriptPixelMetrics(
    val clientWidthPx: Long,
    val clientHeightPx: Long,
    val deviceFontPixelSize: Int,
    val windowWidthScriptPx: Long,
)


private inline fun <reified T : Throwable> Throwable.findFirstCauseOfType(maxDepth: Int = 16): T? {
    var node: Throwable? = this
    var depth = 0
    while (node != null && depth < maxDepth) {
        if (node is T) return node
        node = node.cause
        depth++
    }
    return null
}

private inline fun <reified T : Throwable> Throwable.findLastCauseOfType(maxDepth: Int = 16): T? {
    var node: Throwable? = this
    var match: T? = null
    var depth = 0
    while (node != null && depth < maxDepth) {
        if (node is T) match = node
        node = node.cause
        depth++
    }
    return match
}

internal object HeapCensusPin {
    const val NEAR_TOP_PERCENT: Long = 90L
    const val PIN_COOLDOWN_MS: Long = 30_000L

    fun shouldPin(usedMiB: Long, maxMiB: Long, sinceLastPinMs: Long): Boolean =
        maxMiB > 0L &&
            usedMiB * 100L >= maxMiB * NEAR_TOP_PERCENT &&
            (sinceLastPinMs < 0L || sinceLastPinMs >= PIN_COOLDOWN_MS)
}
