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
import com.eratw.port.core.err.erbDisplayLine
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
import com.eratw.port.core.mod.EraA3AssetOverlay
import com.eratw.port.core.patch.ModuleLinker
import com.eratw.port.core.policy.ErbPatchPolicy
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.BootViewMetrics
import com.eratw.port.core.proc.EchoInputMode
import com.eratw.port.core.proc.EngineFunctionPolicy
import com.eratw.port.core.proc.EngineStampRead
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.LateInputQueue
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
import com.eratw.port.core.vars.VariableStore
import com.eratw.port.modfamily.FamilyDeclarations
import com.eratw.port.modfamily.FamilyMod
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
 * 回合边界串表回收的触发上界（UTF-16 code unit 数，≈2 B/char ⇒ 12 M ≈ 24 MiB 串载荷）。
 *
 * 为什么会撞上：变量存储的串表是**池下标**模型 —— STR 变量覆盖写只改下标、旧串留在池里
 * （原版槽里是 C# `string` 引用，覆盖写由 GC 归还）。而池只进不出，中后局一直往 STR 里
 * 写长串的会话没有运行期回收点；2026-11-25 真机 OOM 报告的驻留串表就是 63,636 条 /
 * 43,685,878 字符（≈83 MiB，占点名账目的近一半）。同族判例见
 * `RuntimeCodeCacheBudgetTest` 的三件运行期缓存（同一处置口径：越界整表收）。
 *
 * 取 12 M char（≈24 MiB）= 报告实测量的 ≈28%：正常中局远达不到，真灌起来一收就落回地板；
 * 收完带 +25% 迟滞（见 [VariableStore.reclaimStringPoolIfOver]），不会每回合白扫载体。
 */
internal const val STRING_POOL_CHAR_BUDGET = 12_000_000L

/**
 * 回合边界串表回收的条目上界（池里不同串的条数，200 000 ≈ 十几 MiB 的池表开销）。
 *
 * 字符预算管「串有多长」，这条管「串有多少条」：压实要按条数重建反查表，池里条目多、
 * 串短的形态若只按字符预算触发，收池会在堆最紧的一刻再付一份「老表 + 新表」的峰值。
 * 报告实测 63,636 条，正常中局远不到这条线。
 */
internal const val STRING_POOL_ENTRY_BUDGET = 200_000

/**
 * 堆压回收运行期代码缓存的堆水位线（used/max 百分比）。
 *
 * 为什么需要这条自救：代码缓存**有上界 ≠ 上界够小**。2026-11-26 真机报告（512 MiB 堆、
 * 崩溃时 used 461 MiB）里账面点名只有 149 MiB；桌面 512m 浸泡的类直方图把差额指到解码
 * 结点簇（结点对象 + 字面串 + 容器，约 120~150 MiB）—— 那是「丢了重解即可」的缓存，
 * 不是活数据。上界收小会把正常推进拖进逐条重解码，所以走**压力触发**：稳态零成本，
 * 堆吃紧时整块还回去。
 *
 * 取 88%：报告现场 bg.heaptop 在 480/512（93%）才发现，那一档只剩 32 MiB 余量，
 * 一次 40 B 的分配就可能判死；88%（≈450 MiB）留出 ~60 MiB 让 GC 与回收动作自己走路。
 */
internal const val CODE_CACHE_RELEASE_HEAP_PERCENT = 88L

/**
 * 两次堆压回收的最小间隔（毫秒）。
 *
 * 回收的代价是「下一轮逐条重解码」，间隔太密等于把热代码反复驱逐（页中检查点 250 ms
 * 一次，真按水位每拍回收会把长页拖成龟速）。30 s 与图形回收同量级：真 OOM 现场一次
 * 回收就能腾出上百 MiB，足够撑过一整段。
 */
internal const val CODE_CACHE_RELEASE_MIN_GAP_MS = 30_000L

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
 * 脚本线程异常穿出装载与驱动大块后的收口分级（RECON-STAB-A A1 的修复判据；纯函数，JVM 单测
 * 钉在 `RuntimeFaultActionTest`）。
 *
 * 输入只有两个状态位 + 异常类型，三向分流：
 * - [RuntimeFaultAction.SUPPRESS_BY_CLOSE]：宿主已 [GameHost.close] —— 关闭竞态是正常退出
 *   路径，静默收口（口径与 [GameHost.handleLoadOrRuntimeFault] 的 closed 首查、backToTitle
 *   复活线程 catch 的关闭首查同源）。
 * - [RuntimeFaultAction.LOAD_FAULT]：维持既有「启动失败」收口一字不动。三种来路：
 *   ① 脚本泵还没进入（`pumpEntered == false`）—— 故障在装载与装配期，会话从未交给界面，
 *   「启动失败」与事实相符；
 *   ② [CodeEE] —— 可恢复的 CodeEE 在 `drive` / `pumpUntilTerminal` 内部就走
 *   `scriptErrorTerminal` 红字终局，穿不进这里；能穿到这里的是非可恢复 CodeEE
 *   （DeviceBudget / CallStackOverflow），登记形态照旧挂装载失败收口（现场转储
 *   `deadloop-site.txt` 挂在那条路上，见 `isRecoverableScriptError` 的 KDoc）；
 *   ③ [Error] —— OOM 归内存族失败屏（`HeapFailureStage` / `canRetryCompat` 一整族读数），
 *   StackOverflowError 收失败屏优于原版进程即死（RECON-STAB-A §2 的登记判定），都不改。
 * - [RuntimeFaultAction.RUNTIME_FAULT]：会话已建立后的执行期非 CodeEE 异常。原版 DoScript
 *   顶层 `catch (Exception)` 是宽捕获（`Process.cs:462`），意外异常走 `handleException` 的
 *   意外分支（`Process.cs:651-660`）：异常类型 + 消息 + 堆栈红字上屏，**会话保留**，玩家可回
 *   标题。移植侧此前把它们错收进「启动失败」屏 + 会话销毁 + 报告误归档（A1 的三个可见差异），
 *   本判据是分流的唯一落点；红字上屏后脚本线程照常终局——不是吞异常继续跑。
 */
internal enum class RuntimeFaultAction {
    SUPPRESS_BY_CLOSE,
    LOAD_FAULT,
    RUNTIME_FAULT,
}

/**
 * 决定脚本线程异常按哪一条收口走（判据细节见 [RuntimeFaultAction] 的 KDoc）。
 *
 * 接线两处：主泵装载与驱动大块的 catch（`loadAndRun`，`pumpEntered` 用局部变量实传），
 * 以及 backToTitle 复活线程的 catch（C4 接线；`pumpEntered` 恒传 true —— 能点「回到标题」
 * 的会话必然已进过泵，不存在装载期相位）。可恢复 CodeEE 的精分（[isRecoverableScriptError]）
 * 不在本判据内：主泵里它在判据之前就被 drive / pumpUntilTerminal 拦走，复活线程里由
 * catch 的精分支拦 —— 两处都不让可恢复 CodeEE 落进 LOAD_FAULT 支的既有收口。
 *
 * @param closed 宿主是否已 close（生产读 [GameHost] 的 `@Volatile closed`）
 * @param pumpEntered 脚本泵是否已进入（`drive` 被调起；true 之后穿出的异常才可能算执行期）
 * @param t 穿出装载与驱动大块的异常本体
 */
internal fun runtimeFaultAction(closed: Boolean, pumpEntered: Boolean, t: Throwable): RuntimeFaultAction = when {
    closed -> RuntimeFaultAction.SUPPRESS_BY_CLOSE
    !pumpEntered -> RuntimeFaultAction.LOAD_FAULT
    t is CodeEE -> RuntimeFaultAction.LOAD_FAULT
    t is Error -> RuntimeFaultAction.LOAD_FAULT
    else -> RuntimeFaultAction.RUNTIME_FAULT
}

/**
 * 执行期收口红字头的**裸异常位置后缀**（RECON-STAB-A 接线②的形态补齐项；纯函数，
 * JVM 单测钉在 `RuntimeFaultLocationTest`）。
 *
 * 原版意外分支的头串带当前语句位置：`handleException` 对没有自带位置的意外异常取
 * `current.Position`（当前语句）格式化成位置串（蛇版 `Process.cs:645-652`），再把它
 * 拼进 `HasUnexpectedError` 头串（蛇版 `Process.cs:695`；文案 `Lang.cs:2994`
 * `{0}予期しないエラーが発生しました:{1}` / zhs `emuera-zhs.xml:744`
 * `{0}发生了意料之外的错误:{1}`）。移植侧头串是「类型 + 消息」在前、位置后缀随尾
 * （`scriptLocation` 的同一段收尾），本函数只补裸异常缺的那一段：取 [frame]
 * （`ProcessRuntime.erbFrameStack` 抛点镜像的最内帧 —— VM 在 `run()` 的 catch 里、
 * 回卷前镜像的当前语句位置，任意异常离栈都镜像，`Vm.kt:1493-1496`）的
 * 「函数名 + 1 基物理行」，形状与 `scriptLocation` 的 CodeEE 后缀同构
 * （`（函数名 行N）`；行号 0 基 → 1 基走 [erbDisplayLine] 单一换算口，不另加一）。
 *
 * 诚实降级：[frame] 为 null（异常出自 VM 之外 / 镜像已清）或函数名与行号**都**读不出
 * = 返回空串，头串退回「类型 + 消息」原形 —— 不造假位置。CodeEE 家族不走本函数
 * （调用方先试 `scriptLocation`，家族路红字头一字不动）。
 */
internal fun bareFaultLocationSuffix(frame: ProcessRuntime.ErbFrame?, copy: ShellFailureCopy): String {
    frame ?: return ""
    val parts = ArrayList<String>(2)
    frame.function?.takeIf { it.isNotBlank() }?.let { parts += it }
    val displayLine = erbDisplayLine(frame.line)
    if (displayLine >= 0) parts += copy.fill(copy.scriptLocationLineFormat, displayLine)
    return if (parts.isEmpty()) "" else copy.fill(copy.scriptLocationWrapFormat, parts.joinToString(" "))
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
    /**
     * 指令收尾的按键等待（COMEND-WAIT，玩家面缺省开 = 原版）：true = 每条返回非 0 的指令
     * 回菜单前等一次回车（`Process.cs:2417` / `:2449-2455`），打出来的口上留在屏上；
     * false = 不等。与 `echoInput` 同口径：装配期定死，设置页改完重开一局生效。
     */
    private val commandEndWait: Boolean = true,
    private val busyIndicator: Boolean = true,
    private val modRuntime: Boolean = false,
    /**
     * 路 B dex 载体供给缝（ADR-013 S5，MODDX4-DEX 组长接线）：`MainActivity` 在 **UI 线程**经
     * [androidDexCarrierProvision] 构造好单路传入（总闸快照与目录读数在构造期定死，装配线程只
     * 消费现成实例，与 `modRuntime` 同一条线程纪律）。缺省 null = 未接线，装配段整段旁路；
     * 缝实现内判 dex 总闸（关 = 不扫 code 目录、不建类加载器、返回空表），总闸缺省关 = 出厂零行为差。
     */
    private val dexCarriers: DexCarrierProvision? = null,
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

    /**
     * Mod 装配总闸的**会话有效值**（COMPAT-3P-MOD）：玩家偏好总闸 [modRuntime] 与「非第三方档」
     * 的与。第三方档（合法引擎印章 [EngineStampRead.Present]）强制旁路 eraTW 业务 mod 装配 +
     * global/slot 侧车接线 —— 实测第三方语料的 SYSTEM_TITLE `SAVEGLOBAL` 在侧车配对事务下抛异常
     * （`CodeEE(ErrorSavingGlobalData)`），而侧车未装的原生路径可正常进标题、收输入。
     * eraTW 包（[EngineStampRead.Missing] / [EngineStampRead.Malformed]）恒等于 [modRuntime]，
     * 行为逐字不变；构造参数原文与 MainActivity 传参点均未改（守门测试钉原文）。
     */
    private val modActive: Boolean = modRuntime && engineStamp !is EngineStampRead.Present

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

    /**
     * 第三方代码包载体来源（ADR-013 S6b）：已信任的 dex 代码包在装配期并进载体名单。
     *
     * 照 [loadPhaseSink] 的回调惯例由宿主在构造后、start 之前赋值，**不进构造参数表**
     * （进参数表会改掉 baseline 里既有的 LongParameterList 结构性 ID）。壳层在 UI 线程
     * 把总闸与 `DexClassLoader` 工厂定死（`ModCodeCarrierProvider.forGate`）后经
     * [ModCodeCarrierSource] 交进来，装配期（VM 线程）只调一次、不再摸偏好表。
     * 缺省 null = 没有代码包来源，行为与接线前逐位一致（注册表两参重载只返回内置载体）。
     */
    @Volatile
    internal var modCodeCarriers: ModCodeCarrierSource? = null

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

    /**
     * mod 面 logSink 的**唯一定义**（ERAFIN2 从 [assembleSession] 局部提升为类属性）：
     * canonical 行进 [ModManageDiagnostics] + Logcat。装配期（链装配 / attach）与 Lane DZ
     * 页边界重装路径（`retryRolledBackMod` 的 dex 取件归因）走同一份，不建第二份定义
     * 防漂移（Lane DP「格式只在会话侧 canonical 行生产处定义一次」口径的落点）。
     */
    private val modLogSink: (String) -> Unit = { line -> ModManageDiagnostics.record(line); Log.i(TAG, "modrt: $line") }

    /**
     * 本会话的游戏根（Lane DZ 回滚态重装的载体实例来源）。
     *
     * 只在 [assembleSession] 里赋值一次（与 `modRuntimeSession` 同一段装配代码），退出清场
     * 与它一起置空。设置页拿不到这个 File（另一个 Activity），所以重装请求只能经
     * [ModReinstallRequests] 数据队列投递到页边界，由这里定取载体实例。
     */
    @Volatile
    private var modGameRoot: File? = null

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

    /**
     * 执行期入力的滞留队列（PLAY-INPUT-LATE · 2026-11-23）。
     *
     * 落点与语义见 [LateInputQueue] 类头：`deliver` 在 `!sink.isAwaiting`（= VM 正在跑一段
     * 无等待的脚本，动态地图页每 `ANIMATERECOLOREDMAPS` 毫秒一次整屏重印就落在这一段）时，
     * 不再直接丢，而是**有界限时**地留住；下一个帧发布边界（
     * [`LineBufferConsoleSink.onFramePublished`]，= 原版脚本跑到 `WaitInput` 的 `DoEvents`
     * 时机，`EmueraConsole.cs:1040-1082`）由 [replayLateInputs] 按当刻版面裁决后交回输入队列。
     *
     * 只由 `pendingLock` 保护（与 [deliver] 同锁，见 [submitInternal] 的调用形态）。
     */
    private val lateInputs = LateInputQueue()

    /** 执行期被留住（未丢）的笔数，诊断面用。 */
    private val inputGateQueued = AtomicLong(0L)

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

    /** 上一次堆压回收运行期代码缓存的时刻（[CODE_CACHE_RELEASE_MIN_GAP_MS] 的冷却钟）。 */
    private var lastCodeCacheReleaseAtMs = -1L

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

        // A1 分级的状态位：drive 被调起即置位。之后穿出大块的非 CodeEE 异常按执行期收口
        // （runtimeFaultAction → handleRuntimeFault），之前的一律维持装载失败收口。局部变量
        // 即可：本函数只跑在 VM 线程，catch 与置位同线程，无需 volatile。
        var pumpEntered = false
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

            // ERA-W1：音声面解析先过 mod 覆盖侧车（A3 生产消费接线）。modRuntime 关态 =
            // 聚合口都不进（B1 零接触），resolver 拿空表、行为逐字等同改造前；开态空链 /
            // 无声明也回落官方解析（坏档侧车由 collect 整包跳过不采信）。
            val soundOverlay = if (modActive) {
                assetOverlayFor(gameRoot, fresh.modLinkOrderIds) { line -> Log.w(TAG, "assetmap: $line") }
            } else {
                EraA3AssetOverlay.AssetMap.EMPTY
            }
            sound.delegate = TableSoundSink(
                fresh.store.aux,
                FileSoundSourceResolver(File(gameRoot, SOUND_DIR_NAME), gameRoot, soundOverlay) { file ->
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
            pumpEntered = true
            drive(fresh)
        } catch (e: InputSinkClosedException) {
            Log.i(TAG, "输入队列已关闭，脚本线程退出")
        } catch (t: Throwable) {
            // A1 分级：同一 catch 承接装载期与执行期两种相位，判据单一来源（RuntimeFaultActionTest
            // 钉判据函数 + 源码哨兵钉接线）；handleLoadOrRuntimeFault 内的 closed 首查保留兜底。
            when (runtimeFaultAction(closed, pumpEntered, t)) {
                RuntimeFaultAction.SUPPRESS_BY_CLOSE ->
                    Log.i(TAG, "装载期间已关闭，脚本线程退出（${t.javaClass.simpleName}: ${t.message}）")
                RuntimeFaultAction.LOAD_FAULT -> handleLoadOrRuntimeFault(t, trace)
                RuntimeFaultAction.RUNTIME_FAULT -> handleRuntimeFault(t, trace)
            }
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
        // 设置页写入的热通知（DX-A2）：VM 线程页边界 drain 积压并转发会话（会话缺席 = 丢弃）。
        ModSettingsNotifyChannel.drain().forEach { (packageId, keys) ->
            modRuntimeSession?.notifySettingsChanged(packageId, keys)
        }
        // 设置页「重试装载」积压（Lane DZ）：同一拍、同一条 VM 线程页边界执行，
        // 会话缺席 = 请求丢弃（未兑现积压在收口 clear 里一并清）。
        ModReinstallRequests.drain().forEach { packageId -> retryRolledBackMod(packageId) }
        drainModHeirBridge()
        memory.releaseImagePixelsIfHeapHigh()
        // 串表运行期回收（同一拍、同一条 VM 线程页边界）。**只能挂在这种安全点上**：
        // compact 会就地改写全部载体里的池下标，语句/表达式执行中途调会把 Kotlin 局部里
        // 待写回的下标指到别的串上（静默错值，比响更坏）。此处是 WAIT 语句内、VM 线程停在
        // 输入等待 —— ERB 调用帧仍在，但语义层只经 readStr/writeStr 交换 String、不持下标，
        // 下标的全部持有面就是 VariableStore.strIdCarriers 的清单 ⇒ 没有跨输入等待活着的下标。
        // 异常不吞：真抛了交给 A1 分级按运行期故障处置。
        fresh.store.reclaimStringPoolIfOver(STRING_POOL_CHAR_BUDGET, STRING_POOL_ENTRY_BUDGET)?.let { r ->
            Log.i(TAG, "turn.strpool " + r.line)
            EventJournal.record("turn.strpool", mapOf("line" to r.line))
        }
        releaseCodeCachesIfHeapHigh("turn")
    }

    /**
     * 一个「重试装载」请求（Lane DZ）的消费端：忘掉旧失败读数 → 定取载体新实例 → 会话内重装。
     *
     * 三道早退都只让请求**静默作废**（队列语义：不回流、不重投）：
     * - 会话缺席（收口后 / 未装配）：没有可重装的对象；
     * - 游戏根缺席：拿不到载体表（防御性，正常装配段必定已赋值）；
     * - 载体表里没有该 packageId（或玩家已停用）：不是内置载体，不可重装。
     *
     * `forget` 排在取实例之后：取不到实例时旧失败行必须留着（界面继续报真实读数），
     * 取到才清——重装成败由 `host.install` 打出的 canonical `mod.install` 行重新写回。
     * 跑在 VM 线程页边界（`onTurnBoundaryReached`），与诊断面的写线程口径一致。
     */
    private fun retryRolledBackMod(packageId: String) {
        val session = modRuntimeSession
        val root = modGameRoot
        val mod = if (session != null && root != null) {
            // Lane DZ（ERAFIN2）：内置 Kotlin 载体定取口优先；不在注册表（第三方 dex 包）
            // 回退 dex 供给核单包取件（同一条闸链现造新实例，归因行与装配期同形）。
            // 供给缝缺省 carrierFor = null（fail-closed），未接线时请求静默作废。
            ModCarrierRegistry.providerFor(root, packageId)
                ?: dexCarriers?.carrierFor(root, packageId, modLogSink)?.second
        } else {
            null
        }
        if (session != null && mod != null) {
            ModManageDiagnostics.forget(packageId)
            session.reinstallRolledBack(packageId, mod)
        }
    }

    /**
     * 设置页「家督继任」请求的消费端（task-160 / Lane HB）：页边界把玩家意图翻成作者面调用，
     * 再把作者面真值回写成设置页快照。
     *
     * 早退口径与 [retryRolledBackMod] 一致：请求一次性投递、不回流。本局 family 载体不在
     * [ModRuntimeSession.activePackages]（没装 / 停用 / 装载失败 / 总闸关 / 已收口）⇒ 清空
     * 快照并丢弃请求：设置页下次渲染整块不显示，不出现「点了没反应」的按钮。活载体实例走
     * [ModRuntimeSession.carrierModOf]（与装进宿主的是同一实例），继承规则原文走
     * `context.settings.enumName`（会话内视图，无盘 IO）。
     *
     * 兜底 runCatching 只保护「设置页数据面不许把异常抛回页边界」：失败即清快照 + 记日志，
     * 绝不把 VM 主循环带走。跑在 VM 线程页边界，与既有两条 drain 同拍。
     */
    private fun drainModHeirBridge() {
        val requests = ModHeirRequests.drain()
        val session = modRuntimeSession
        val family = if (session != null && FamilyDeclarations.PACKAGE_ID in session.activePackages) {
            session.carrierModOf(FamilyDeclarations.PACKAGE_ID) as? FamilyMod
        } else {
            null
        }
        val mode = if (family != null) {
            session?.host?.contextOf(FamilyDeclarations.PACKAGE_ID)
                ?.settings?.enumName(FamilyDeclarations.SUCCESSION_MODE_KEY)
        } else {
            null
        }
        runCatching { ModHeirBridge.apply(requests, family?.api, mode) }
            .onFailure { t ->
                ModHeirFeed.clear()
                Log.w(TAG, "家督继任桥未生效（快照已清空，请求丢弃）", t)
            }
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
     * 执行期非 CodeEE 异常的收口（VM 线程，RECON-STAB-A A1 修复；与装载收口
     * [handleLoadOrRuntimeFault] 并列，由 [runtimeFaultAction] 分流）。
     *
     * 与原版同构：DoScript 顶层 `catch (Exception)` 宽捕获（`Process.cs:462`）→
     * `handleException` 的意外异常分支（`Process.cs:651-660`）—— 异常类型 + 消息 + 堆栈
     * **红字上屏**，进程与会话都保留（红字后玩家停在原地、可回标题）。移植侧对应三件事：
     *
     * 1. 红字上屏：类型 + 消息一行 + 位置后缀 + 堆栈首段（逐帧 `printError`，与原版逐行
     *    PrintError 同形；截 16 帧防刷屏——报告里有全量堆栈）。位置后缀：CodeEE 家族走
     *    `scriptLocation` 既有口径；裸异常走 `bareFaultLocationSuffix`（RECON-STAB-A 接线②
     *    ——原版意外分支头串带当前语句位置，蛇版 `Process.cs:645-652` / `:695`）。**不吞**：
     *    脚本线程就此终局，屏上是明确报错，玩家可见（原版红字同样是明确报错）；不是静默继续跑。
     * 2. 失败屏按 IN_GAME 族渲染（`scriptErrorTerminal` 同款：会话保留、`canBackToTitle`
     *    通道可达），不再复用「启动失败」屏、不再销毁会话——本函数**绝不调**
     *    [shutdownForExit]，释放权留在玩家后续的回标题 / close 那两条路上。
     * 3. 报告归档为「脚本意外错误」（`phase = IN_GAME`）——原「启动失败」类目会污染收件箱
     *    分诊（与 2026-11-03「关闭竞态一天 6 份」同形的错标噪声）。
     */
    private fun handleRuntimeFault(t: Throwable, trace: LoadTrace) {
        runCatching { trace.mark(LoadTrace.FAIL_STAGE) }
        val text = "${t.javaClass.name}: ${t.message ?: ""}" +
            (scriptLocation(t).ifEmpty { bareFaultLocationSuffix(runtimeThrowFrame(t), failureCopy) })
        runCatching { session?.console?.printError(text) }
            .onFailure { Log.w(TAG, "意外异常红字上屏失败（继续收口）", it) }
        // 堆栈首段逐帧上屏（原版 handleException 意外分支逐行 PrintError 的同形，Process.cs:655-659）。
        runCatching {
            t.stackTrace.take(RUNTIME_FAULT_STACK_LINES).forEach { frame ->
                val at = "  at ${frame.className}.${frame.methodName}" +
                    "(${frame.fileName}:${frame.lineNumber})"
                session?.console?.printError(at)
            }
        }.onFailure { Log.w(TAG, "意外异常堆栈上屏失败（继续收口）", it) }
        recordFailure(
            kind = FailureScreenKind.IN_GAME,
            heapStage = HeapFailureStage.NONE,
            text = failureCopy.fill(failureCopy.scriptAbortFormat, text),
        )
        writeErrorReport(
            "脚本意外错误",
            t,
            listOf(
                "中止点：执行期意外异常（非 CodeEE；原版由 DoScript 顶层宽捕获转红字上屏，会话保留）",
                "证据：Process.cs:462 / Process.cs:651-660",
            ),
        )
        Log.e(TAG, "脚本意外错误终局（会话保留，可回标题）：$text", t)
        session?.let { stopAudioForTerminal(it) }
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
        // Lane DXR：会话装配开始 = 诊断面归零 —— 失败行口径是「本会话事实」，不跨会话继承。
        // 位置承重：必须早于本函数内的链装配（`ModChainAssembly.assembleWithCatalog`）与
        // `ModRuntimeAssembly.attach` —— 二者写的 canonical 行都在归零之后产生，不会被抹掉；
        // 反过来说，归零若挪到链装配之后，装配期拒载行就丢了（`ModDiagnosticsSessionScopeE2ETest`
        // 组 F 与 `ModDiagnosticsSessionScopeWireTest` 的源码序哨兵成对钉这一条）。
        // 换局 / 兼容档重试 / 设置页重启都是新 `GameHost` ⇒ 新会话，全走这里；
        // 从后台接回同一会话（`reattachView`）不经过本函数，故意不归零（运行中会话的失败行得留着）。
        ModManageDiagnostics.reset()
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
        // 导入链性能波：把本会话已算好的产物借给导入链（侧车解析 2.9s + mmap 1.6s 不再重付）。
        // 按文件身份戳借用，换包自动失效；详见 BaseArtifactCache。
        BaseArtifactCache.publish(programFile, metaFile, program, metadata)
        trace.mark("meta")
        if (closed) return null
        // 侧车装载警告捕获（DBG-TOOLING 波）：erbcc 编译期告警随 program.meta 落盘（file/line/
        // message 结构化），此前全仓零生产消费者（BOARD P2 挂账）。GameSession 构造会收走
        // metadata（GameSession.kt:97「装配完成后本类不再持有它」）⇒ 必须在构造**前**把清单
        // 捕获成局部值；上报统一延到下面 sharpWarnings 段的 best-effort 区（装载线程不做网络）。
        val sidecarWarnings = runCatching { metadata.loadWarningsOrEmpty() }.getOrDefault(emptyList())
        for (w in sidecarWarnings) Log.i(TAG, "侧车装载警告：${w.file}:${w.line} ${w.message}")

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
        // Lane DP：装配期（链装配）与会话期（attach）共用同一个诊断/logSink —— 整链级拒载
        // 发生在 attach 之前，只有走这条线留痕，玩家重启后 ModManageDiagnostics 收得到、
        // 设置页失败小节才渲染（格式只在会话侧 canonical 行生产处定义一次，避免漂移）。
        // ERAFIN2：本 sink 提升为类属性（见 [modLogSink]）——Lane DZ 页边界重装路径的
        // dex 取件归因行要与装配期同一通道，不能有第二份定义。
        val csvContext = if (modActive) {
            // M1-4 阶段二：装载相位先产出注入上下文（唯一一次 ManifestLoader.load），
            // 让 Mod 链装配与 GameSession 扩容共用同一份快照/容量/计划（裁定 3）。
            // Lane DY（task-124）：这是装配链**第一道** fail-closed 闸（声明冲突 / 坏 manifest
            // 在 ManifestLoader.load 当场抛），留痕必须走与链装配 / attach 同一条 modLogSink，
            // 否则设置页失败小节看不到、只有启动失败屏。包装函数在原样重抛（类型/消息/时机
            // 不变）前留一行；`assembleWithCatalog` 不包进来（内部已有 catch+重抛，会双份）。
            ModPreloadTrace.csvLoadContextTraced(gameRoot, modLogSink) { line -> Log.w(TAG, "modcsv: $line") }
        } else {
            null
        }
        val modChainOutcome = ModChainAssembly.assembleWithCatalog(
            ChainAssemblyInputs(
                gameRoot = gameRoot,
                enabled = modActive,
                base = program,
                baseMetas = metadata.functions,
                builtinVariableNames = { ModRuntimeAssembly.builtinVariableNames() },
                csvContext = csvContext,
                // L2 组长收口接线：玩家排序配置 → LinkOrder（Kahn 就绪优先级；空表 = 缺省规范序）。
                linkOrder = com.eratw.port.core.patch.LinkOrder(modPackageOrder),
                // Lane DP：装配期整链拒载的包级留痕与 attach 会话面同一条 sink（同一 λ 实例）。
                logSink = modLogSink,
            ),
        )
        // ABI-8 路线 a 的 T1 取数缝：本段 = 上一交界（meta）到 mod 链装配完成的耗时，
        // 只加 mark 不挪语句（上方 :1371-1374 的「位置承重」约束）；关态与空表走旁路，这一段应≈0，
        // 非零即说明旁路没生效（真机读数表 <internal-workdir>/HANDOFF-PERF.md 的 modchain 列）。
        trace.mark("modchain")
        val modChain = modChainOutcome?.plan
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
            // COMEND-WAIT（2026-11-22）：指令收尾按键等待的总闸，玩家侧缺省开 = 原版口径。
            waitAfterCommand = commandEndWait,
            // RT2 G2 波：链接产物三视图（program / FunctionTable / dispatch 同源）非 null 时
            // GameSession 三处取值只从这里来；null = 完全旁路（今天生产恒此态）。
            modChain = modChain,
            // ERA-W1：随包角色模板的链接序真源 = plan.catalog.candidates 的 ownerPackageId
            // 首现序（候选列表序即链接序，SymbolCatalog 类 KDoc）；空表 = 既有目录名序逐位不变。
            modLinkOrderIds = modLinkOrderIdsOf(modChainOutcome),
            functionDispatch = null,
            modCsvContext = csvContext,
            bootViewMetrics = bootMetrics,
            sqlDriver = driver,
        )
        trace.mark("session")

        // MOD_LINK_CATALOG_SLOT 生产写侧（Lane V）：把装配点的链接产物快照挂进会话 store.aux。
        // 时序硬约束（见 ModRuntimeAssembly.kt 的事件名存在性闸读缝）：必须晚于 GameSession
        // 构造（store 此时才存在）、早于本函数尾段的 ModRuntimeAssembly.attach —— 后者的
        // host.knownEventKeys 事件名闸从该槽取快照（entityRuntime 回填的 catalog 自 SUPCAT-W
        // 起改走 attach inputs 供给，见下方 catalog= 行）。旁路会话
        // （modChainOutcome == null，含 modRuntime 关态）不写槽：没有链接产物可写。
        modChainOutcome?.installCatalogSlot(fresh.store)

        wirePageFunctionTrail(fresh)
        wireUpdateCheckHost(fresh)
        wireSaveMemoryReclaim(fresh)
        wireCorpusNativeMethods(fresh)

        // B3（2026-11-26）：mod attach 前移到 prefillSharpLinks **之前** —— mod 的
        // FunctionApi.override（显式覆盖宿主内建）必须在装载趟裁决之前进注册终表，
        // 否则常量名 CALLSHARP 行联结期已绑死宿主本体，覆盖永远不生效（残疾）。
        // 相位模型对齐原版：PluginManager（插件 DLL）先于脚本装载/联结填好。
        // 顺序仍满足既有约束：wireCorpusNativeMethods（宿主终表，mod override 的目标先就位）
        // → mod attach（宿主不抛、mod 回调已隔离，失败语义不变）→ prefillSharpLinks（裁决看到
        // 的是**终表**）→ drive()。attach 的输入全部在此之前就绪（gameRoot/store/providers/
        // catalog/dexCarriers 均不依赖 sharpWarnings/prefill）。
        modGameRoot = gameRoot
        modRuntimeSession = ModRuntimeAssembly.attach(
            gameRoot = gameRoot,
            store = fresh.store,
            nativeRegistry = nativeMethods(fresh.store.aux),
            onVmThread = { onVmThread() },
            enabled = modActive,
            logSink = modLogSink,
            modProviders = if (modActive) {
                // ADR-013 S6b：内置载体先算，已占 id 集交给代码包来源；只有「不重名且未重复
                // 运行期身份」的代码包才被追加（判据在 ModCodeCarrierProvider 内，一次一套）。
                ModCarrierRegistry.providers(gameRoot) { builtinIds ->
                    modCodeCarriers?.carriersFor(builtinIds).orEmpty()
                }
            } else {
                emptyList()
            },
            // SUPCAT-W 判据 2：链接产物快照经 attach inputs 供给 entityRuntime 回填
            // （HAS_SUPER 判据源随链走；旁路 modChain == null 时为 null = 未装配语义）。
            catalog = modChain?.catalog,
            dexCarriers = dexCarriers,
            consoleSink = fresh.console,
            inputSink = fresh.inputSink,
        )
        // 作者面语言码：壳层这里已按唯一单路取好语言（`:244` settingsLanguage），装配层拿不到
        // Android Context、也不许自判 Locale，故由壳层注入给宿主，供 `context.strings.of()` 用。
        modRuntimeSession?.host?.language = settingsLanguage.stored

        val sharpWarnings = prefillSharpLinks(fresh.store.aux, fresh.runtime.program)
        // ERA-W1：随包角色模板装配告警（modLinkOrderIds 声明非法降级等）落宿主日志通道。
        fresh.charaTemplateWarnings.forEach { Log.w(TAG, "chara 模板：$it") }
        for (w in sharpWarnings) Log.w(TAG, "CALLSHARP 装载警告：$w")
        fresh.recordSharpLoadWarnings(sharpWarnings)
        // 2026-11-17 覆盖补齐：装载期警告过去只 Log.w + 回填读数面（侧车 loadWarnings 全仓零
        // 消费者）。这里把每条告警喂进聚合器，每型每局头两次落代表报告并上报，其余计数 ——
        // 语料/第三方口上的译名与常量问题从此能主动进端点，不必等玩家反馈。
        // 走 app 壳的 best-effort 入口；装载线程上绝不因上报抛异常或做网络。
        if (sharpWarnings.isNotEmpty()) {
            runCatching {
                for (w in sharpWarnings) {
                    ErrorReportCollector.recordDiagnostic(
                        view.context, DiagnosticEventLog.DiagnosticKind.LOAD_WARNING,
                        null, "CALLSHARP: $w",
                    )
                }
            }
        }
        // 侧车告警（erbcc 编译期流水）同通道聚合上报（DBG-TOOLING 波）：where 带 文件:行
        // 让签名把「不同文件同类告警」分开、同文件同告警（换读数）被抹数字归一合并。
        // 条数可能上百，刷屏由 DiagnosticEventLog 的每型落盘上限封顶（PER_KIND_REPORT_MAX）。
        if (sidecarWarnings.isNotEmpty()) {
            runCatching {
                for (w in sidecarWarnings) {
                    ErrorReportCollector.recordDiagnostic(
                        view.context, DiagnosticEventLog.DiagnosticKind.LOAD_WARNING,
                        null, "侧车装载警告: ${w.file}:${w.line} ${w.message}",
                    )
                }
            }
        }

        // 新局/读档通知的接线必须排在 attach 之后（attach 已前移到 prefillSharpLinks 之前，B3）：
        // 两个回调都转发到 modRuntimeSession。
        wireModLifecycleHooks(fresh)
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
        // ERA-W1：CSV 资源表装载先过 mod 覆盖侧车（A3 生产消费接线；GameHost.kt:1451 挂账收账）。
        // modRuntime 关态 = 聚合口都不进（B1 零接触），拿空表、装载逐字等同改造前；开态下
        // 映射目标打不开的消费面回落 + WARN 由 core 侧 fail-closed 口径负责（ResourceContents.kt:262-272）。
        val assetOverlay = if (modActive) {
            assetOverlayFor(gameRoot, session.modLinkOrderIds) { line -> Log.w(TAG, "assetmap: $line") }
        } else {
            EraA3AssetOverlay.AssetMap.EMPTY
        }
        val contents = ResourceContents.load(spritePool, ResourceCsvHost(gameRoot, decoder), assetOverlay)
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

    /**
     * 本宿主当前是否挂在 [candidate] 这张视图上（UI 线程的归属判据）。
     *
     * 冻结与「无壳期间重新入槽」都要认这个主，不能用 [SessionVault] 的槽归属：
     * * 槽在 [SessionVault.takeoverIfAlive] 时已清空，接回壳自己后续被系统销毁时
     *   槽归属恒为 false —— 照它跳过冻结/入槽，会话就成了无主孤儿，下个实例冷启回标题，
     *   挂起的后台存档检查点也再没有 VM 安全点可兑现（2026-11-17 玩家反馈的两个现象）；
     * * [reattachView] 会把 view 换成新壳的视图，双壳窗口里旧壳迟到的 `onDestroy`
     *   一看 view 已不是自己，冻结与入槽一并跳过，绝不打扰新壳刚解冻的会话。
     */
    fun isBoundToView(candidate: CanvasConsoleView): Boolean =
        synchronized(pauseLock) { view === candidate }

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
            } catch (e: CodeEE) {
                // C4 分型第一层（RECON-STAB-C）：CodeEE 单独一捕，结构与主泵 pumpUntilTerminal
                // 的 CodeEE 分支同构（closed 首查 → isRecoverableScriptError 精分）。可恢复
                // CodeEE 只可能来自 gotoTitle()（pumpUntilTerminal 自己捕 CodeEE，可恢复的
                // 走不到外层）：主泵里它是 scriptErrorTerminal 红字终局，这里同样红字上屏 +
                // 会话保留、可再点「回到标题」，不再折成「回到标题失败」屏 —— 原版 GotoTitle
                // 与普通脚本同在 DoScript 收口内，handleException 打屏后会话保留
                // （Process.cs:438-478 → :651-660）。
                if (closed) {
                    Log.i(TAG, "回到标题途中宿主关闭，静默收口（${e.message ?: e.javaClass.simpleName}）")
                } else if (!isRecoverableScriptError(e)) {
                    // 非可恢复 CodeEE（装置预算 / 爆栈闸）是真故障：重抛给下面的统一收口，
                    // 经 runtimeFaultAction 落 LOAD_FAULT 支 —— 与主泵 drive 的重抛同一条路。
                    throw e
                } else {
                    scriptErrorTerminal(active, e)
                    stopAudioForTerminal(active)
                }
            } catch (t: Throwable) {
                // C4 分型第二层：非 CodeEE 的意外异常与上面重抛进来的真故障统一收口（正体
                // 见 [handleBackToTitleRuntimeFault]，判据源与主泵同一条）。
                handleBackToTitleRuntimeFault(t)
            }
        }
    }

    /**
     * 「回到标题」复活线程非 CodeEE 异常的统一收口（RECON-STAB-C C4 分型第二层）。
     *
     * 判据源与主泵同一条 [runtimeFaultAction]。`pumpEntered` 恒真 —— 能进 backToTitle 的
     * 会话必然已通过 drive 进过脚本泵并停在脚本错误终局（canBackToTitle 三要件），这里
     * 不存在装载期相位。SUPPRESS 之外的两支都是真故障：维持既有「回到标题失败」屏与报告
     * 一字不动。不吞异常 —— 上屏/落报告后线程照常终局，现场全部留档；重抛进来的非可恢复
     * CodeEE 在判据里落 LOAD_FAULT 支，与主泵 drive 的重抛同一条路。
     */
    private fun handleBackToTitleRuntimeFault(t: Throwable) {
        when (runtimeFaultAction(closed, pumpEntered = true, t)) {
            RuntimeFaultAction.SUPPRESS_BY_CLOSE ->
                Log.i(TAG, "回到标题途中宿主关闭，静默收口（${t.message ?: t.javaClass.simpleName}）")
            RuntimeFaultAction.LOAD_FAULT, RuntimeFaultAction.RUNTIME_FAULT -> {
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

    fun submitButton(text: String) {
        // 按钮点击 = 一次鼠标事件（MainWindow.cs:834/847 口径）：画布触摸链在 ACTION_DOWN
        // 已写鼠标现场（updatePointer），右栏芯片等没有事件流的调用点不写——脚本侧弹窗锚在
        // MOUSEX()/MOUSEY() 上（QOL_USERCOM.ERB:260-261 的 X_POS=MOUSEX()+20），不补写就
        // 落在上一次触摸的陈旧坐标（玩家报「用右栏点 + 弹窗乱跳」）。统一在提交语义里补：
        // 把指针指到屏上同值按钮中心（div 弹窗按钮优先），屏上没有同值按钮时保持现场不动。
        updatePointerToOption(text)
        submitInternal(text, mesSkip = false, clickIntent = true, mouseButton = 1)
    }

    fun submitSkip(text: String) = submitInternal(text, mesSkip = true)

    fun submitMappedColor(color: Long) {
        synchronized(pendingLock) {
            session?.inputSink?.submitMappedColor(color)
        }
        // 鼠标点色字 = 玩家真实点击（不进 deliver 漏斗，单独采集）。
        modRuntimeSession?.hostSeams?.interactionWire?.notePlayerInput(
            ModInteractionWire.SOURCE_CLICK,
            "mapped-color:$color",
        )
    }

    fun submitCbgButtonValue(value: Long) {
        synchronized(pendingLock) {
            session?.inputSink?.submitCbgButtonValue(value)
        }
        // Cbg 按钮点击同属玩家真实点击。
        modRuntimeSession?.hostSeams?.interactionWire?.notePlayerInput(
            ModInteractionWire.SOURCE_CLICK,
            "cbg-button:$value",
        )
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
            // 执行期（VM 正在跑无等待脚本：动态地图页每 tick 的整屏重印、长计算链）的
            // 入力不再当场丢弃，改为有界限时滞留、到下一个等待边界按当刻版面回放一次
            // （见 [lateInputs] 与 [replayLateInputs]）。原版这一段就是 Windows 消息队列：
            // 脚本与消息泵同线程，点击躺在队列里到 `WaitInput` 的 `DoEvents` 才被取出
            // （`EmueraConsole.cs:1040-1082`），既不丢、也按取出那一刻的版面命中。
            if (clickIntent) keyInput.noteClickIntent(text)
            if (mesSkip) sink.noteSkipIntent()
            // 返回值只表示「有没有挤掉更旧的一笔」，**不是**「这笔被丢了」——新的一笔恒被留下，
            // 所以这里不拿它记丢弃（2026-11-23 组长口径：桶不许说谎）。真丢的账全在
            // [LateInputQueue] 的 overflow/expired/layoutMismatch 三个桶里。
            lateInputs.offer(
                nowMs = SystemClock.elapsedRealtime(),
                kind = if (mouseButton != null) LateInputQueue.Kind.BUTTON else LateInputQueue.Kind.TEXT,
                text = text,
                mesSkip = mesSkip,
                mouseButton = mouseButton,
            )
            inputGateQueued.incrementAndGet()
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
        // RT2 P0-LAST：玩家**真实输入**证据（UI/输入线程写，VM 页边界消费一次 → 交互管线）。
        // 只记成功交付的那一笔（上面 !isAwaiting 的丢入力不触发作者交互，口径与引擎一致）。
        modRuntimeSession?.hostSeams?.interactionWire?.notePlayerInput(
            if (mouseButton != null) ModInteractionWire.SOURCE_CLICK else ModInteractionWire.SOURCE_COMMAND,
            text,
        )
    }

    /**
     * 执行期入力的回放（PLAY-INPUT-LATE · 2026-11-23）：在**帧已经发布**的那一刻
     * （[replayLateInputs] 由 `LineBufferConsoleSink.onFramePublished` 在 VM 线程同步调）
     * 把 [lateInputs] 里滞留的笔按提出顺序交回输入队列。
     *
     * 为什么是这一刻：原版没有「执行期丢入力」这回事 —— 脚本与消息泵同线程，点击躺在
     * Windows 消息队列里，到脚本跑进 `WaitInput` 的那轮 `DoEvents`（`EmueraConsole.cs:1040-1082`）
     * 才被取出，且命中用的是**取出那一刻**的版面。移植侧的等价时刻 = 帧发布边界
     * （发布帧与 `publishedOptions` 同刻换新，见 `LineBufferConsoleSink.flushGateOnce`）。
     *
     * 裁决口径（命中不到就丢，不许猜）：
     * - 按钮（[LateInputQueue.Kind.BUTTON]）：当刻发布帧的选项集里必须有同值项，否则记
     *   `layoutMismatch` 丢弃 —— 版面已经换页，按旧值点下去就是误点；
     * - 文本（键盘提交）：原版文本框提交与选项集无关，原样放行（回显时机照旧在交付点）。
     *
     * 线程：只在 VM 线程执行（`onFramePublished` 的调用线程；[onVmThread] 兜底）。
     */
    private fun replayLateInputs(session: GameSession) {
        if (!onVmThread()) return
        val sink = session.inputSink
        val optionValues = session.console.publishedOptions().map { it.submitValue }
        val plan = lateInputs.drainForLayout(SystemClock.elapsedRealtime(), optionValues)
        val entries = plan.entries
        if (entries.isEmpty()) return
        synchronized(pendingLock) {
            for (e in entries) {
                turnStartNs.compareAndSet(0L, System.nanoTime())
                pageStartNs.set(System.nanoTime())
                val button = e.mouseButton
                if (button != null) {
                    sink.submitButtonClick(e.text, button, e.mesSkip)
                } else {
                    sink.submit(e.text, e.mesSkip)
                }
                modRuntimeSession?.hostSeams?.interactionWire?.notePlayerInput(
                    if (button != null) ModInteractionWire.SOURCE_CLICK
                    else ModInteractionWire.SOURCE_COMMAND,
                    e.text,
                )
            }
        }
    }

    /**
     * 执行期入力滞留的桶读数（诊断面，DevTools 的 `rev` / `where` 直接带出来）：
     * `queued` / `expired` / `overflow` / `layoutMismatch` / `replayed` + `ambiguous` + `pending`。
     */
    fun debugLateInputCounters(): String =
        "late queued=${inputGateQueued.get()} expired=${lateInputs.expired} " +
            "overflow=${lateInputs.overflow} layoutMismatch=${lateInputs.layoutMismatch} " +
            "replayed=${lateInputs.replayed} ambiguous=${lateInputs.ambiguousHits} " +
            "pending=${lateInputs.size}"

    /**
     * mod 交互浮层的表面句柄（RT2 P0-LAST UiApi 真接线）：关态/未装配返回 null。
     *
     * 只给 MainActivity 的 Compose 浮层取「当前贡献项快照 + 点击派发口」用；主线程读的是
     * 不可变快照（VM 边界重拍），不直接碰 VM 侧注册表。
     */
    internal fun modUiSurface(): ModUiSurface? = modRuntimeSession?.hostSeams?.uiSurface

    /**
     * mod 运行时概览的只读快照（Lane AW；设置页取数用）：关态 / 未装配 / 账本 null ⇒ null。
     *
     * 只读账本与预算，不写盘、不改 mod 状态；与 [modUiSurface] 同一「活引用」口径——
     * 会话收口后（[modRuntimeSession] 置 null）读到的就是空，不是已关闭的旧会话。
     */
    internal fun modRuntimeDataFor(packageIds: List<String>): ModRuntimeData? =
        modRuntimeSession?.let { runtimeOverviewDataOf(it.host, it.activePackages, packageIds) }

    /**
     * 「最近日志」读数（MODDX4-G8 组长接线）：设置页回看入口经 [ModRuntimeLogViewerEntry]
     * 的活引用通道走这里；会话收口后（[modRuntimeSession] 置 null）读到空表 = 诚实空态，
     * 与 [modRuntimeDataFor] 同一「活引用」口径。按包聚合委托 core 侧
     * [ModRuntimeHost.recentLogs]，本壳不做第二份缓冲。
     */
    internal fun modLogsFor(packageId: String): List<String> =
        modRuntimeSession?.host?.recentLogs(packageId) ?: emptyList()

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
                // 执行期入力的回放点 = 每个「真的产出一帧」的发布边界（VM 线程）：
                // 原版脚本跑到 WaitInput 的 DoEvents 那一刻。`onWaitEntered` 的 KDoc 明写
                // 不得回调 sink 自身（会自锁），所以不用它；帧发布边界在 sink 的锁外，
                // 且此刻 `publishedOptions` 已与 revision 同刻换新 ⇒ 可以按当刻版面裁决。
                fresh.console.onFramePublished = { replayLateInputs(fresh) }
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

    /**
     * 右栏芯片提交前的鼠标现场写入：把指针指到屏上同值按钮的中心
     * （[CanvasConsoleView.buttonCenterClientForValue]）。
     *
     * 画布触摸链有真实事件坐标（[updatePointer]），右栏芯片没有——而脚本侧弹窗普遍锚在
     * MOUSEX()/MOUSEY() 读数上（QOL_USERCOM.ERB:260-261 的 X_POS=MOUSEX()+20），
     * 不写现场弹窗就会落在上一次触摸的陈旧坐标（玩家报「点 + 弹窗乱跳」）。此处与
     * [updatePointer] 共用同一写入口径（setClientSize→setPointerPosition→windowCreated→指向态），
     * 屏上无同值按钮时返回 false、现场原样保留。
     * UI 线程调用（读渲染层命中表）。
     */
    fun updatePointerToOption(value: String): Boolean {
        val live = session ?: return false
        val (clientWidth, clientHeight) = view.clientSizePx()
        if (clientWidth <= 0 || clientHeight <= 0) return false
        val center = view.buttonCenterClientForValue(value) ?: return false
        val mouse = mouseStateOf(live.store.aux)
        mouse.setClientSize(clientWidth, clientHeight)
        mouse.setPointerPosition(center.first, center.second)
        mouse.windowCreated = true
        pointerPointing = view.buttonAtClient(center.first, center.second)?.toPointing()
        return true
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

    /** 当前任意键等待是否禁止跳过（`FORCEWAIT` 的 `stopMesSkip`）；取值等待/无等待 = false。 */
    fun awaitingAnyKeyStopsSkip(): Boolean =
        session?.inputSink?.awaitingAnyKeyStopSkip == true

    /** 等待点序号（单调）：跳过开关用它给「同一等待只补一次 skip」去重。 */
    fun inputWaitGeneration(): Long = session?.inputSink?.waitGeneration ?: 0L

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

    /**
     * 悬停闸：本轮是否正在等按钮/按键输入。与原版 `canSelect` 同形
     * （`EmueraConsole.cs:2574-2577`：Error 态或 `IsWaitInputState && NeedValue` ——
     * 原版 MoveMouse 在 `IsInProcess` 直接返回，脚本执行期悬停一律不亮；
     * Error 分支由移植侧弹窗层承担，不并入本闸）。
     */
    fun awaitingInput(): Boolean {
        val sink = session?.inputSink ?: return false
        return sink.awaitingAnyKey || sink.isAwaiting
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
        // 串表运行期回收的**第二个安全点**（长页专用）：跳日期那种「一页跑很多天」的会话，
        // 页尾要等几十秒到几分钟才来，只有 onTurnReady 那一处收口的话池子在这一整页里一路涨
        // （2026-11-25 真机报告：页中检查点 where=midpage、串池 65,460 条 / 52,588,062 字符）。
        // 此处是 VM 指令循环取指之前（[Vm.pressureCheckpoint] 的挂点，两条指令之间、语句体
        // 不会被截断）：语义层没有攥在 Kotlin 局部里的待写回下标，持有面仍是 strIdCarriers 清单。
        // 检查点 250ms 一次，但本调用先做 O(1) 上界比较，未越界时只两条比较、不扫载体。
        session?.store
            ?.reclaimStringPoolIfOver(STRING_POOL_CHAR_BUDGET, STRING_POOL_ENTRY_BUDGET)
            ?.let { r ->
                Log.i(TAG, "midpage.strpool " + r.line)
                EventJournal.record("midpage.strpool", mapOf("line" to r.line))
            }
        // 同一拍、同一安全点：代码缓存的堆压回收（跳日期那种长页里崩，就是这里救）。
        releaseCodeCachesIfHeapHigh("midpage")

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

    /**
     * 把「新开一局 / 读档走到末尾」两个游戏相位通知接到 mod 运行时
     * （[com.eratw.port.modapi.EraMod.onNewGame] / [com.eratw.port.modapi.EraMod.onSaveLoaded]）。
     *
     * 挂在 [ConsoleBeginHost] 的可变字段上而不是构造参数：本类的构造点在山另一头
     * （`GameSession` 装配），六项位置参已经很长，且 mod 运行时是**装配完之后**才有的，
     * 天然只能后接。回调在 VM 线程上发生，两个转发函数各自带隔离，不外抛。
     */
    private fun wireModLifecycleHooks(session: GameSession) {
        session.beginHost.onNewGameStartedHook = { modRuntimeSession?.notifyNewGame() }
        session.beginHost.onSaveLoadedHook = { modRuntimeSession?.notifySaveLoaded() }
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
     * ## 有界 join（C2 修复，2026-11-18）：为什么「不等 VM 线程退出」与 `VM_EXIT_JOIN_MS`
     * 的几百 ms join 不矛盾
     *
     * 上面第 1 条拒绝的是**无界**等待（合法长链 130s）；`thread?.interrupt()` 之后插的
     * [awaitVmExitForClose] 是**常量上限**的 join：检查点每 65536 步一次（玩家机
     * ≈30ms 一次，见 `Vm.kt` 关闭闸注释），点查语料（SQL 调用 324 条里的 199 条 WHERE
     * 点查）ms 级即可走到检查点收口——join 成功 = UI 走到 `session.close()` 时 VM 线程
     * 已死，SQL 并发面整个消灭；join 超时 = 维持本节的原状语义（不等，交
     * [shutdownForExit] 现状并发收尾），残余窄窗口由 `SqlSession` 的语句边界互斥锁兜成
     * 「close 让到在途语句边界」的确定序（该锁的等待上界 = 当前语句剩余时长，不是 VM 链）。
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
        // C2 修复：interrupt 之后对 VM 线程做有界 join（常量上限，不持 pauseLock——
        // 上面 synchronized 块已出域）。点查场景 VM 在上限内走到 65536 步检查点收口，
        // join 成功即把「UI close 与 VM 语句执行并发」的窗口整个消灭；超时维持既有
        // 「不等 VM 线程退出」语义，残余并发由 SqlSession 语句边界互斥锁兜成确定序。
        if (!awaitVmExitForClose(thread, VM_EXIT_JOIN_MS)) {
            Log.w(TAG, "关闭：VM 线程 ${VM_EXIT_JOIN_MS}ms 内未收口（在途语句/长链），按关闭协议交并发收尾")
        }

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
        modGameRoot = null
        // 未兑现的设置通知积压随会话丢弃（DX-A2）：不许漏进下一局的首边界。
        ModSettingsNotifyChannel.clear()
        // 同上口径（Lane DZ）：未兑现的重装请求也不许漏进下一局首边界。
        ModReinstallRequests.clear()
        // 同上口径（task-134 ③）：未兑现的卸载回收请求同属一次性投递，不跨会话。
        ModStorageReclaimRequests.clear()
        // 同上口径（task-160）：未兑现的家督继任请求与上一局的快照都不许漏进下一局。
        ModHeirRequests.clear()
        ModHeirFeed.clear()

        val released = runCatching { session?.close() }
            .onFailure { Log.w(TAG, "退出清场：会话关闭抛异常（引用照常置空）", it) }
            .getOrNull()
        if (released == false) {
            Log.w(TAG, "产物通道未释放：惰性回读仍有未 pin 住的段（语句段串表超 pin 上限或段 2 换块），fd 保留优先")
        }
        session = null
        flushSummaryThenClearLedger()
        lastScreen = null
    }

    /**
     * 会话结束前把本局聚合的诊断事件（UI异常/启动异常/装载警告各型×次数）汇总成一份报告
     * 上报（2026-11-17 覆盖补齐，「聚合汇总」真正进端点的落点），随后清账。
     *
     * **读数面同步、写面异步**（C2 修复，2026-11-18）：快照在调用线程拍好（close 路 =
     * UI 线程），落盘交 [ErrorReportCollector.flushDiagnosticSummaryAsync] 的一次性非
     * daemon 线程——close 路径不再发生同步写盘。账面即时清零：后台写用的是快照，与
     * reset 无竞态；后台写成功自会清 checkpoint（防次启误判异常死亡重报同一份账），
     * 写被进程退出截断时 checkpoint 留存，次启 `reportOrphanCheckpoint` 并报兜底。
     * best-effort，上报失败不影响清场。
     */
    private fun flushSummaryThenClearLedger() {
        val summarySnapshot = runCatching { DiagnosticEventLog.summaryLines() }.getOrDefault(emptyList())
        if (summarySnapshot.isNotEmpty()) {
            runCatching {
                ErrorReportCollector.flushDiagnosticSummaryAsync(view.context.applicationContext, summarySnapshot)
            }.onFailure { Log.w(TAG, "退出清场：诊断汇总投递后台失败（checkpoint 留给次启并报）", it) }
        }
        DiagnosticEventLog.reset()
        ErrorReportCollector.resetThrottle("shutdownForExit")
    }

    // =========================================================================
    // 15. DevTools 探针、诊断报告取证行与死前心跳 / 内存采样
    // =========================================================================

    fun debugTurnLog(): List<String> = synchronized(turnLogLock) { turnLog.toList() }

    fun debugTurnPercentilesSummary(): String = turnPercentiles.summaryLine()

    fun debugLoadTraceLines(): List<String> = lastTrace?.summaryLines() ?: emptyList()

    fun debugVmThread(): Thread? = thread

    /**
     * 输入闸计数（诊断面）：`received` = 收到过的玩家入力总笔数；`dropped` = 被**直接丢弃**的
     * 笔数。PLAY-INPUT-LATE 起执行期入力不再丢，`dropped` 因此几乎恒 0 —— 真丢的账改由
     * [debugLateInputCounters] 的 `overflow`/`expired`/`layoutMismatch` 三个桶报。
     */
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
            )
                // 报告面走按段断行的成文口：`render()` 那一整行满档 ~430 字，会被报告拼装侧的
                // 400 字单行闸剪掉尾巴「代码缓存」四键（2026-11-17 lane-g-mem 真机三份 OOM
                // 报告实证）。内嵌换行由 `ErrorReport` 摊平成物理行后逐行剪。
                .renderReportLines().joinToString("\n")
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

    /**
     * 裸异常的抛点帧（[bareFaultLocationSuffix] 的取数口，RECON-STAB-A 接线②）。
     *
     * 只在因果链无 CodeEE 时读 [ProcessRuntime.erbFrameStack] —— core 已有的公开只读面
     * （`Vm` 在 `run()` 的 catch 里回卷前镜像的抛点帧栈，任意异常离栈都镜像，绝不抛），
     * 不为展示形态给 core 加新面。本收口跑在 VM 线程上（主泵 catch 处），VM 已回卷、
     * 镜像在场，与错误报告 erb 帧段同一取数源（`ErrorReportCollector.vmStackLines`）；
     * 最内帧（列表首位）= 抛错指令所在语句。CodeEE 家族返回 null：家族路的位置后缀照旧
     * 走 `scriptLocation`，红字头一字不动。镜像缺席（异常出自 VM 之外等）= null →
     * 后缀为空（诚实降级，不造假位置）。
     */
    private fun runtimeThrowFrame(t: Throwable): ProcessRuntime.ErbFrame? {
        if (t.findFirstCauseOfType<CodeEE>() != null) return null
        return session?.runtime?.erbFrameStack()?.firstOrNull()
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

    /**
     * 堆压回收运行期代码缓存（语句条目表 + 两张节点表），见 [CODE_CACHE_RELEASE_HEAP_PERCENT]。
     *
     * 只在**堆吃紧**时动手，两条闸：水位 ≥ 88% 且距上次 ≥ 30 s。动手时刻必须是 VM 指令/
     * 语句边界（两个调用点：[midPageCheckpoint] 与回合边界 `onTurnBoundaryReached`）——
     * 回收只清缓存槽，正在执行的语句/表达式实例照旧有效，之后同一下标重解出逐字段相同的
     * 新实例；钉住表（CALLSHARP 候选 ∪ 数据节）的条目按槽留下，身份契约不变（含钉住的块不再
     * 整块丢掉，只清掉其余槽，清空后连背衬一并弃）。
     *
     * 失败方向：回收自身抛掷只记一笔警告（游戏照常）且**不动冷却钟**（可复现故障，下一次水位
     * 判据再试）；没有可还的块（非惰性池 / 已经还空）时**照样拨钟**、只是不刷日志 —— 页中检查点
     * 250 ms 一次，缓存已空却每拍重扫「块表 × 块宽」在堆最紧的时刻纯属白扫加垃圾。
     */
    private fun releaseCodeCachesIfHeapHigh(where: String) {
        val live = session ?: return
        val now = SystemClock.elapsedRealtime()
        val cooledDown =
            lastCodeCacheReleaseAtMs < 0L || now - lastCodeCacheReleaseAtMs >= CODE_CACHE_RELEASE_MIN_GAP_MS
        val (usedMiB, maxMiB) = heapSnapshotMiB()
        val overWater = maxMiB > 0L && usedMiB * 100L / maxMiB >= CODE_CACHE_RELEASE_HEAP_PERCENT
        if (cooledDown && overWater) {
            val release = runCatching { live.program.releaseRuntimeCodeCaches() }
                .onFailure { t -> Log.w(TAG, "cache.code $where 回收失败（游戏照常）", t) }
                .getOrNull()
            if (release != null) {
                // 冷却钟在**空读数时也拨**（见 KDoc 末段）：否则堆一贴到 88%，页中检查点每 250 ms
                // 都会把块表与块宽重扫一遍，而这时恰恰是最不该制造垃圾的时刻。
                lastCodeCacheReleaseAtMs = now
                if (!release.isEmpty) {
                    Log.i(TAG, "cache.code $where " + release.line + " used=${usedMiB}MiB/$maxMiB")
                    EventJournal.record("cache.code", mapOf("where" to where, "line" to release.line))
                }
            }
        }
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

        /**
         * close() 对 VM 线程的 join 上限（毫秒，C2 修复）：钉死几百 ms 量级，绝不回归
         * 「无界等」——合法长链 2.9 亿指令 ≈130s（见 [close] KDoc）。检查点 ≈30ms 一次
         * （玩家机 2.2M 指令/秒），点查 ms 级；500ms 覆盖慢机的检查点间隔 + 在途短语句
         * + 异常收尾，仍远小于销毁路径可接受的停留。
         */
        internal const val VM_EXIT_JOIN_MS = 500L

        internal const val RM_GID_BITS_VAR = "RM_GID_BITS"
        internal const val RM_GID_BIT_ELEMENTS = 100
        internal const val RM_GMAP_NAME = "map:グラフィックス"

        private const val META_THREAD_NAME = "eratw-meta"
        private const val SESSION_HANDOFF_WAIT_MS = 15_000L
        private const val TAG = "GameHost"
        private const val STAMP_ECHO_LIMIT = 400

        /** 执行期意外异常红字上屏的堆栈帧数上限（全量在报告里，屏上防刷屏）。 */
        private const val RUNTIME_FAULT_STACK_LINES = 16
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

        /**
         * close() 的有界 join 纯函数缝（C2 修复）：app 的 JVM 单测面实例化不了
         * `GameHost`（Android 壳），与 [consoleTailOf] 同款「判据抽成 companion 纯函数」
         * 口径——`GameHostCloseTimingTest` 用假 VM 线程（循环 + 检查点响应中断）钉住
         * 「点查场景上限内退出 / 长链场景不越过上限」两条路径。
         *
         * @return true = 线程已在上限内退出（或本就没有 VM 线程）；false = 超时（或
         *   self-join 不成立——close 只许 UI 线程调，防御未知调用形状）。
         */
        internal fun awaitVmExitForClose(worker: Thread?, boundMs: Long): Boolean {
            // null = 无可等（立即确认）；self = close 误上 VM 线程（不空转一个上限）。
            if (worker == null || worker === Thread.currentThread()) return worker == null
            return try {
                worker.join(boundMs)
                !worker.isAlive
            } catch (ignored: InterruptedException) {
                false
            }
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

// ---------------------------------------------------------------------------
// ERA-W1：A2/A3 生产接线共用面（真链接序提取 + 覆盖侧车聚合）。
// 独立顶层 internal 函数：GameSession 装配段与 installGraphics / 音声装配三处消费同一份，
// 测试（EraW1*）可直接点名，不在 GameHost 类内加方法（detekt 文件阈值不动）。
// ---------------------------------------------------------------------------

/**
 * 随包角色模板 / 素材覆盖面共用的**链接序真源**提取（ERA-W1）。
 *
 * 源 = [ModChainAssemblyOutcome] 携带的链接产物快照（plan.catalog.candidates）：
 * 候选列表序即链接序（SymbolCatalog 类 KDoc），base 候选带合成标记 ModuleLinker.BASE_OWNER_ID
 * （ModuleLinker.kt:115 / ModChainAssembly.kt:331-333）、模块候选带真实 packageId
 * （SymbolCandidate.ownerPackageId）。按首现序去重即得 base 之后的模块链接序。
 *
 * 旁路（modRuntime 关态 / 无容器，outcome = null）= 空表 ⇒ 两个消费面整体回落既有确定性
 * 兜底序（模板目录名序 / 覆盖聚合退化为盘上文件名序），零行为差。
 */
internal fun modLinkOrderIdsOf(outcome: ModChainAssemblyOutcome?): List<String> =
    outcome?.plan?.catalog
        ?.candidates
        ?.map { it.ownerPackageId }
        ?.filter { it != ModuleLinker.BASE_OWNER_ID && it.isNotEmpty() }
        ?.distinct()
        ?: emptyList()

/**
 * 素材覆盖侧车的生产聚合口（sprite / 音声两消费面共用，ERA-W1）。
 *
 * 遍历序 = 「链接序在先、其余在库包在后」：[linkOrderIds]（[modLinkOrderIdsOf] 的真链接序）
 * 先按序聚合；已启用的在库包（rt2 目录下的 manifest 文件，ModRuntimeAssembly.manifestFilesIn
 * 同一实现：停用包不进聚合面）里不在链接序的——纯资源包载体 / manifest-only 无 .erbm 的包，
 * 链跳过但覆盖声明仍应生效——按文件名序追加在后，与 LinkOrder「未指定者排后按序兜底」的
 * 冻结语义同形。侧车读取与跨包 last-wins 裁决全部委托 EraA3AssetOverlay.collect（A3 单一
 * 真源，坏档整包跳过不采信），本函数不自建第二份聚合语义。modRuntime 关态由调用方先闸
 * （本函数不做开关判定）：关态整条 mod 行为缺席，连聚合口都不进（B1 零接触）。
 */
internal fun assetOverlayFor(
    gameRoot: File,
    linkOrderIds: List<String>,
    logSink: (String) -> Unit = {},
): EraA3AssetOverlay.AssetMap {
    val rt2Dir = ModRuntimeAssembly.manifestDirFor(gameRoot)
    val chainSet = linkOrderIds.toHashSet()
    val remaining = ModRuntimeAssembly.manifestFilesIn(rt2Dir)
        .map { it.name.removeSuffix(ModPackageInventory.MANIFEST_EXTENSION) }
        .filter { it !in chainSet }
    return EraA3AssetOverlay.collect(rt2Dir, linkOrderIds + remaining, logSink)
}
