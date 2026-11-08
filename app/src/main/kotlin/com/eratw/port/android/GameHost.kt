package com.eratw.port.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.eratw.port.core.audio.AUDIO_CHANNEL_SLOT
import com.eratw.port.core.audio.SoundChannelTable
import com.eratw.port.core.audio.TableSoundSink
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.config.EffectiveConfig
import com.eratw.port.core.diag.PageFunctionTrail
import com.eratw.port.core.diag.ProgramDiagnostics
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.BackgroundAdmission
import com.eratw.port.core.fn.IMAGE_LOAD_TRACE_SLOT
import com.eratw.port.core.fn.ImageLoadTrace
import com.eratw.port.core.fn.InputMirrorPort
import com.eratw.port.core.fn.KeyEventInputState
import com.eratw.port.core.fn.NativeMethod
import com.eratw.port.core.fn.TextBoxHost
import com.eratw.port.core.fn.nativeMethods
import com.eratw.port.core.fn.prefillSharpLinks
import com.eratw.port.core.host.AndroidKeyCodes
import com.eratw.port.core.host.BeginAfterPreflight
import com.eratw.port.core.host.CBG_SLOT
import com.eratw.port.core.host.CbgPool
import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.GRAPHICS_SLOT
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.PNG_PAYLOAD_SLOT
import com.eratw.port.core.host.MappedPngPayloadStore
import com.eratw.port.core.host.PngPayloadStore
import com.eratw.port.core.host.ParallelTask
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.host.SpriteEvictScratch
import com.eratw.port.core.loader.BootFailureKind
import com.eratw.port.core.loader.BootFailures
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.RootedBuiltinHost
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.EchoInputMode
import com.eratw.port.core.proc.EngineFunctionPolicy
import com.eratw.port.core.code.InfiniteLoopAlert
import com.eratw.port.core.code.InfiniteLoopAnswer
import com.eratw.port.core.lexer.LangVariant
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.StateStep
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.SaveFolderMigrationLog
import com.eratw.port.core.stmt.StmtInternAudit
import com.eratw.port.core.update.UpdateTelemetry
import com.eratw.port.platform.audio.AndroidAudioFocusRequester
import com.eratw.port.platform.audio.AudioFocusSession
import com.eratw.port.platform.audio.AudioFocusTarget
import com.eratw.port.platform.audio.FileSoundSourceResolver
import com.eratw.port.platform.audio.MediaPlayerChannelDecoder
import com.eratw.port.platform.diag.HostDiagnostics
import com.eratw.port.platform.image.AndroidGraphicsSink
import com.eratw.port.platform.image.BitmapImageDecoder
import com.eratw.port.platform.image.GameImageFiles
import com.eratw.port.core.host.ResourceContents
import com.eratw.port.platform.image.ResourceCsvHost
import com.eratw.port.core.host.SpritePool
import com.eratw.port.platform.image.SpriteBitmapSource
import com.eratw.port.platform.save.BlockingSaveMigrationPrompt
import com.eratw.port.platform.save.SaveMigrationPromptUi
import com.eratw.port.platform.vm.BlockingInfiniteLoopPrompt
import com.eratw.port.platform.vm.InfiniteLoopPromptUi
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.platform.storage.ModStore
import com.eratw.port.platform.text.AndroidGlyphMaskHost
import com.eratw.port.platform.text.AndroidTextMeasure
import com.eratw.port.platform.update.AndroidUpdateCheckHost
import com.eratw.port.platform.update.UpdateAsk
import com.eratw.port.platform.update.UpdateCheckTransport
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.toPointing
import com.eratw.port.render.canvas.CanvasConsoleView
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import com.eratw.port.android.ui.EratwShellDialog
import java.io.File
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicBoolean

/** 装载/运行期失败的既有标题（`MainActivity` 的失败屏同源）。 */
internal const val FAILURE_PREFIX = "启动失败："

/**
 * 「内存不够」这一族失败到底卡在哪一步 —— 失败屏出口与**自动降档**的唯一判据来源
 * （2026-11 真机 vivo V2199A 那次的修复点）。
 *
 * ## 为什么要分两族
 *
 * 同一个玩家面说法「内存不够」，在装载链上是两个完全不同的处境：
 *
 * - **[PREFLIGHT_REJECTED]（甲：预检拒载）** —— `ProgramLoader.estimateHeap` 的静态估算
 *   就判「上限 < 需求」（真机现场：需 540.2 MiB > 上限 512 MiB），装载**一个字节都没开始**：
 *   没有会话、没有映射、没有并行线程。旧版对它的处置是「先自动换省内存档再试一次」，而省内存档
 *   靠 `!compatLoad` 绕开同一道闸 —— 明知装不下还硬试，代价是 OOM 之后进程被系统随机一刀，
 *   **玩家什么都没看到**（失败屏还没上屏，致命 OOM 落在别的线程上）。
 * - **[LOAD_OOM]（乙：装载中真 OOM）** —— 预检放行了（上限本来够），是装载/装配/首轮脚本
 *   真的撞了 OOM（实际峰值高于估算）。这一族换档确实有救，自动降一档是对的。
 *
 * ## 处置（两族的差别只有一条：要不要**自动**降档）
 *
 * | 族 | 自动降档 | 屏上那颗「少占内存再试」 | 屏上读数 |
 * |---|---|---|---|
 * | 甲 | **不**（自动再试一次 = 拿进程去赌） | 亮，文案明说「也可能还是装不下」 | 最多给多少 / 要多少 / **还差多少** / 已用 |
 * | 乙 | 一次（另有 `compatInUse` / `autoRetried` 两道死圈闸） | 亮 | 最高用到 / 最多给多少 / 停在哪一步 / OOM 原话 |
 *
 * ## 先释放再上屏（两族的共同纪律）
 *
 * `failure` 非 null 是 UI 侧「可以画失败屏了」的**唯一**信号。所以装载失败那一支把 `failure`
 * 的赋值放在 `shutdownForExit()` **之后**（会话 / 整局 Program / `.erbc` 映射先关掉，见
 * `loadAndRun` 的 catch）。甲族本来就没有可释放的大对象（装载没开始），天然满足这条。
 * 贴边 OOM 的机器上，画屏那几笔分配（Compose 重组、行缓冲快照）若落在释放之前，就是压垮
 * 进程的最后一根 —— 屏还没画出来，进程已经死了。
 */
internal enum class HeapFailureStage {
    /** 本轮没有撞「内存不够」（还没失败 / 别的失败族）。 */
    NONE,

    /** 甲：预检就判装不下，装载根本没开始。**不许自动降档。** */
    PREFLIGHT_REJECTED,

    /** 乙：预检放行之后装载中真 OOM。允许自动降省内存档一次。 */
    LOAD_OOM,
}

/**
 * 甲族（预检拒载）的失败屏文案：玩家话 + 本轮实测读数 + 说清「换档也可能不管用」。
 *
 * 读数四个数各有分工：`最多给游戏` = `Runtime.maxMemory()`（这台机给单个应用的硬上限），
 * `要` = 装载层按产物标定的需求，`还差` = 两者之差（玩家唯一要做的算术已经替他做了），
 * `已用` = 收完垃圾后的装载前占用（用来区分「机型档位不够」与「常驻已被吃掉」）。
 *
 * 措辞一律零基础中文：不上「堆」「预检」「兼容档」这类术语（2026-11 玩家面口径）。
 * 单独抽成顶层函数是为了让「差多少 MB 必须出现在屏上」「术语不许上屏」这两条能用 JVM
 * 单测钉住（判据见 `HeapFailureCopyTest`）。
 */
internal fun heapPreflightStoppedText(
    maxMiB: Long,
    requiredMiB: Long,
    usedMiB: Long,
    copy: ShellFailureCopy = ShellFailureCopy.ZH,
): String {
    val gapMiB = (requiredMiB - maxMiB).coerceAtLeast(0L)
    return copy.fill(copy.heapPreflightFormat, maxMiB, requiredMiB, gapMiB, usedMiB)
}

/**
 * 乙族（装载中真 OOM）的失败屏文案：玩家话 + 装载轨迹读数 + OOM 原话。
 *
 * `最多给游戏` 是 `Runtime.maxMemory()`，`最高用到` 是装载轨迹里最高的一段，
 * `停在` 是峰值所在段 —— 三者对读才分得清「机型档位不够」与「装载前已被挤满」；
 * [oomDetail] 补 OOM 原话（`pthread_create failed` 这类非 Java 堆分配失败同被归并到本族，
 * 靠它区分）。`stage` 为 null 表示没有可信的阶段读数（防御分支），此时不占那个从句。
 */
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
 * LIVE-REFLOW（契约 CONTRACT-LIVE-REFLOW §6 L6）：「视口把新列数问出去 → 核心重排 → 渲染换屏」
 * 这条三段链的**判据本体**。
 *
 * ## 为什么抽成纯函数
 *
 * 生产调用点是 [GameHost.attachConsoleFeed] 里装给 [CanvasConsoleView.onViewportChange] 的那枚闭包，
 * 三个参与者（View / GameSession / ConsoleFeed）都要真机运行时：app 的 JVM 单测面没有 Robolectric，
 * `CanvasConsoleView` 实例化不了。所以把「该不该动 / 动哪一步 / 什么情况下绝不许动」抽成只吃
 * 布尔与 lambda 的纯函数直测，同 L4 的 `LiveReflowPlan`、本模块 `MainActivity.orientationForLock`
 * 一个路子（判式与生产接线逐字同形，测试里用假步骤复现）。
 *
 * ## 四条不变量
 *
 * 1. **[Outcome.CLOSED] 关**：宿主已收口（[GameHost.close] 先**同步**置 `closed = true`）⇒ 一步都不做。
 *    这是「`stop()`/关闭路径之后回调不做事」那条判据的本体；摘除回调本身在收口链里做
 *    （见 [GameHost.shutdownForExit]），两者独立成立才挡得住「post 排队的摘除还没跑」那一拍。
 * 2. **[Outcome.NO_SESSION] 会话不在**：未装配 / 已随收口置 null ⇒ 一步都不做（既有路径不受影响）。
 * 3. **[Outcome.RESIZE_REFUSED] 只有 `resizeConsole` 返回 true 才许换屏**：契约 §1 对 false 的定义是
 *    **开关关闭 / 宽度未变 / 无需重建**，且调用前后无任何状态变化。此时**绝不**调
 *    [CanvasConsoleView.reflowAttachedFeed] —— 核心没动，`sink.clientWidth` 还是旧宽，
 *    而视口按新列数去换屏会命中 `LiveReflowPlan.Step.WidthMismatch` **抛 IllegalStateException**
 *    （见 `CanvasConsoleView.reflowAttachedFeed` 的 KDoc），表现就是「转一下屏就闪退」。
 * 4. **[Outcome.SWAP_DONE] / [Outcome.SWAP_NO_FEED]**：换屏只在这里被调，列数/行数与交给核心的
 *    列数**同源**（同一个 `cols`/`rows` 读数，来自触发这一次的那回 `rebuildScreen`）；
 *    `reflowAttachedFeed` 返回 false 是「没挂 feed」的合法早退（契约 §1），零状态变化。
 *
 * ## 线程（写死）
 *
 * 本函数只由 [CanvasConsoleView.onViewportChange] 的调用方驱动，而那条回调只在 **UI 线程**的
 * `rebuildScreen` 里发出；两步动作（`resizeConsole` / `reflowAttachedFeed`）各自 KDoc 都写明
 * 「调用方是 UI 线程」「只许 UI/渲染线程」。**不要从 VM 步进线程调**：`resizeConsole` 会与正在
 * 执行的语句抢同一份显示行（`GameSession.resizeConsole` 的线程节）。
 */
internal object ViewportReflowChain {

    /** 这一发的落点：只进日志与单测断言，不参与任何游戏语义。 */
    enum class Outcome {
        /** 宿主已收口：什么都没做。 */
        CLOSED,

        /** 会话不在（未装配 / 已置 null）：什么都没做。 */
        NO_SESSION,

        /** `resizeConsole` 说「不重排」（关态 / 同宽 / 无需重建）：**没有**换屏。 */
        RESIZE_REFUSED,

        /** 核心已按新列数重排，视口也换了屏。 */
        SWAP_DONE,

        /** 核心已重排，但视口没挂 feed（演示路径 / 已被摘桥）：换屏早退，零状态变化。 */
        SWAP_NO_FEED,
    }

    /**
     * 重挂渲染桥时该不该按**核心当前折行宽**换一只空屏（[attachConsoleFeed] 的兜底闸）。
     *
     * `ConsoleFeed` 的构造前置是 `sink.clientWidth == screen.clientWidth`
     * （`render/.../ConsoleFeed.kt:131` 的 `require`，非等即抛）。开态下这两者能分叉的窗口有两个：
     * - 换屏成功之后，[GameHost.lastScreen] 仍停在装载时那份旧列宽的屏（旧写法根本没人改它），
     *   而「回到标题」的恢复路径正是拿它重挂桥（`backToTitle` → [attachConsoleFeed]）；
     * - 失败屏摘过 feed（`MainActivity.renderFailureIfNeeded` → `detachFeed()`）时视口仍在变宽：
     *   链把 `resizeConsole` 走成了（核心已换新宽），`reflowAttachedFeed` 却因没挂 feed 走 Skip
     *   早退（`CanvasConsoleView.kt:685` 的 `sinkWidth == null` 支）。
     * 两种都会在「失败屏 → 回到标题」那一跳当场抛 `IllegalArgumentException`（UI 线程 ⇒ 进程没）。
     *
     * 关态（[liveReflow] = false）核心折行宽在会话期冻结 ⇒ 永不分叉，这个分支根本不进 ——
     * 既有路径逐字不变。
     *
     * `sinkColumns < 1`（sink 处于「不折行」形态，`LineBufferConsoleSink.clientWidth` 的口径）
     * 时**不换**：`ConsoleScreen` 自己的 `require(clientWidth >= 1)` 会把兜底变成新崩溃点。
     */
    fun needsScreenAlignOnReattach(liveReflow: Boolean, screenColumns: Int, sinkColumns: Int): Boolean =
        liveReflow && sinkColumns >= 1 && screenColumns != sinkColumns

    /**
     * 走一遍三段链。
     *
     * @param closed 宿主是否已收口（生产读 `GameHost.closed`，`@Volatile`）
     * @param session 当前会话；null ⇒ [Outcome.NO_SESSION]。类型收宽成 `S?` 只为了让本对象
     *   不依赖 `GameSession`，测试递自己的假会话即可（不必为了测而改生产签名）。
     * @param resize 核心侧重排（`GameSession::resizeConsole` 的闭包）；返回 false ⇒ 不换屏
     * @param swap 渲染侧换屏（`CanvasConsoleView::reflowAttachedFeed` 的闭包）；
     *   **只在** [resize] 返回 true 时求值（这条惰性就是本函数存在的理由）
     */
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
 * 真机启动器：`<gameRoot>/erbc.v1/{program.erbc,program.meta}` → [GameSession] → [ConsoleFeed]。
 *
 * ## 线程
 *
 * [start] 只做前置检查并拉起 `eratw-vm` 线程；装载（段表映射 + 按段流式解码）、侧车解析、
 * `GameSession` 装配与 BEGIN 状态机全部在该线程。UI 线程只读 [failure] 与
 * `view.invalidate()`，不参与脚本执行。`QueueInputSink` 是单生产（UI）单消费（VM），
 * 提交只走 [submit]。
 * 唯一的例外是 LIVE-REFLOW（开关开的那一局）：UI 线程经 [CanvasConsoleView.onViewportChange]
 * 调 `session.resizeConsole(...)` 改控制台折行宽 —— 那是**改排版**，不是执行脚本，
 * 并发由 sink 自己在锁内处置（判据与线程约束见 [ViewportReflowChain]）。
 *
 * ## 状态机驱动
 *
 * 原版 DoScript 的内层泵形状是「只要会话活着就反复跑 handler，状态是 `Normal`
 * 也照跑」（`Process.cs:445-460`）：`Normal` 且脚本函数栈空、又没有挂起 BEGIN
 * 时，原版是 handler 里的 endNormal 抛错（`Process.cs:2039` 注册 + `:2959-2962`
 * 方法体），移植侧等价读数是 [StepOutcome.FAILED]。因此本类不按
 * 「状态 != Normal」泵——那会让 endNormal 的 FAILED 永远不可达——而是：
 * [StepOutcome.BLOCKED]/[StepOutcome.FAILED] 当场落 [failure] 收口，
 * `ProcessRuntime.quitRequested`（脚本 QUIT）停转，其余读数继续泵。
 * 等待输入发生在 handler 内部（`ConsoleBeginHost` 阻塞在输入队列上），
 * 所以本类不再额外轮询。
 *
 * ## 音频
 *
 * `TableSoundSink` 需要 `store.aux`，而 store 由 [GameSession] 内部创建，故先用
 * [DelegatingSoundSink] 占位、装配完成后把委托换成同表 sink。声道表按 aux 现读，
 * 与内建宿主四项同源（见 `AudioAdapters.kt` 的类头）。
 *
 * ## 装载路径
 *
 * `program.erbc` 走 [ProgramLoader.loadMapped]：段表与大段（CODE / LINE_TABLE / 字符串池）
 * 全程按段映射，装载期堆上不再有「整份产物字节」（M6-ERBC-STREAM 的读数：装载峰值
 * 1644.4 → 1286.7 MiB）。真机只有这一份产物、且 `GameSession` 构造立即需要 `Program`，
 * 所以不接 `deferredMapped` / `deferredDirectory`：延迟句柄在这里只是多一次 `close`
 * 生命周期，省不下任何常驻；将来若改成多分片清单再按需接。
 *
 * ## 装载内存（M8-APPMEM）
 *
 * 装载链按 `start → preflight → program → meta → session → ready` 六段埋点（[LoadTrace]），
 * 每段一行 `load.<阶段> used=XMiB max=YMiB native=NMiB`，收尾一行 `load.peak`；
 * 只有 Debug 版打日志（`ApplicationInfo.FLAG_DEBUGGABLE`），release 静默但照常采样。
 *
 * 三处**削减峰值**的动作都不改语义：
 * - 段交界处 [LoadTrace.gcHint]：把上一段留下的垃圾在下一段大分配之前收掉，
 *   削掉「垃圾 + 新常驻」的叠加峰（三段交界处是 512 MB 堆机型最薄的余量）；
 * - `Program` / `BootMetadata` 的局部引用收进 [assembleSession] 的一帧里，装配返回后
 *   只剩 `GameSession` 持有它们，装载函数那边不再有一份同名局部陪着整局跑；
 * - 预检的 [ProgramLoader.HeapEstimate] 只在预检段存活，不跨段持有。
 *
 * 宿主侧**没有**可释放的产物字节：`loadMapped` 逐段读、映射窗口用完即丢，所以第二项
 * 是「顺序与生命周期」上的余量，不是「省一份 323.7 MB 副本」。真正的常驻大头
 * （`Program.code/lines`、表达式池）在 core 层，见 `M6-CODE-VIEW` / `M6-PERF-INTERN`。
 *
 * ## 音频焦点
 *
 * 音频焦点（[focus]）是**平台强制项，原版没有**：`MainWindow.cs:2118-2119` 只挂
 * `FormClosing` / `Shown`，全文无 `Sound` / `Bgm` 引用。因此焦点只走「不改脚本可见状态」
 * 的旁路（`AudioMixer.onFocusEvent`），不动 `Playing` / `paused`，避免 ISPLAYINGSOUND /
 * ISPLAYINGBGM 与 PLAYSOUND 选道在切后台后与 PC 分叉。生命周期入口只有 [onPause] /
 * [onResume] / [close] 三个，Activity 不直接碰声道。
 *
 * ## 失败口径
 *
 * 产物缺失 / 装载失败 / 脚本抛错都写进 [failure]（不崩溃 Activity）；界面保留最后快照，
 * 由 `MainActivity` 画一行错误。装载期四类失败按 [BootFailures.classify] 分派到既有文案；
 * 「内存不够」这一族按**卡在哪一步**再分两族（判据与处置见 [HeapFailureStage]）：
 * 甲（预检拒载）→ [heapPreflightStoppedText]；乙（装载中真 OOM）→ [heapOomText]。
 * 两族的屏上都带本次实测读数（最多给多少 / 要多少 / 还差多少 / 已用 / 停在哪一步），
 * 真机不接 adb 也能判「机型档位不够」还是「装载前已被挤满」；玩家面用词一律零基础中文，
 * core 固定句 [BootFailures.HEAP_SHORTAGE_TEXT] 只留在核心层的判据与测试里。
 * 文件缺失 → [missingArtifacts]；段损坏 / 映射失败（`FileSystemException`）→
 * 「启动失败：装载器原话」（见 [describe]）。
 * 产物缺失文案**并列两条候选产物目录**（外部根优先，见 [GameStorage.candidateArtifactDirs]），
 * 真机 adb 调试时屏上直接能看出该往哪推。真机冷启动判据仍受 M4-SKEL-RT
 * （模拟器不可用）阻塞。
 *
 * ## 首启解包（M8-ASSETSINSTALL）
 *
 * 分发形态是「APK 自带游戏数据」：`erbc.v1` 与 `CSV` / `resources` / `sound` / `emuera.config`
 * 都在 assets 里，首次启动由 [ensureAssetsInstalled] 落到 `GameStorage.gameRoot()`
 * （外部根优先，内部 `filesDir/eratw` 兜底），之后照旧走上面的装载链。
 *
 * 三条口径：
 * - **只跑一次**：进程内缓存结果（[assetsReady]），`artifactsAvailable()` 与 [start] 共用；
 * - **幂等 + 不覆盖用户数据**：目标已在位就跳过（写侧经临时文件改名，存在即完整），
 *   已知大小的按字节数对账，`emuera.config` 只拷一次（升级键由解包器的缺键补齐单独
 *   追加，玩家已有行不碰）、`sav/` 不在清单里；
 * - **流式**：解包器按 1 MiB 块写、SHA-256 边拷边算，不持整份产物字节
 *   （361.1 MiB 产物在 512 MiB 堆机型上的前提，见 [GameAssetInstaller] 类头）；
 *   进度以「解包中 N/M」进 logcat（Debug/Release 都打，装不上时这是唯一的现场）。
 *
 * 解包失败不白崩：文案落 [failure]（「启动失败：首启解包失败：…」），并让
 * [artifactsAvailable] 返回 true 把控制权交给 [start] 的失败分支 —— 原因见该方法的 KDoc。
 */
class GameHost(
    private val storage: GameStorage,
    private val view: CanvasConsoleView,
    /**
     * 兼容装载档（2026-10-28 低端机 OOM）：true = 用更长的装载时间换更低的堆峰值 ——
     * ① 解码期去重与校验串行（不起并行线程，`ProgramLoader.loadMapped` 的 `compat`）；
     * ② `program.meta` 不再与 program 解码并行，串行在 program 之后读；
     * ③ 解码每逢段边界无条件收一次垃圾（[LoadTrace.forceGc]，砍掉 GC 时机噪声 ——
     *   同一份产物的高水位读数在桌面实测能从 342 摆到 725 MiB）；
     * ④ 堆预检硬闸放行（同 force_load，见下方判据注释）。
     * 失败屏的「兼容模式重试」置 true 后重建宿主；一旦启用，本实例的后续装载都用兼容档。
     */
    private val compatLoad: Boolean = false,
    /**
     * 分片扁平语句池的**设备归因覆盖**（null = 按文件开关，见 [assembleSession]）。
     *
     * 归因 +114 MiB 时 release 包没有 `run-as`（`isDebuggable=false`），文件开关碰不到；
     * 壳层从启动 Intent 的 `eratwFlatStatements` 布尔 extra 取值传进来，让 release 包也能
     * 用 `adb shell am start ... --ez eratwFlatStatements true` 复现。null/缺省 = 与旧行为
     * 逐字等价（仍读 `files/flat-statements.on`，默认不存在 ⇒ 关）。
     */
    private val flatStatementsOverride: Boolean? = null,
    /**
     * LIVE-REFLOW 开关（契约 CONTRACT-LIVE-REFLOW §6 L6）：落到 `GameSession(reflowConsole = …)`。
     *
     * **必须由调用方把装配期那次读数原样传进来**，本类不许自己去摸 `UiPrefs`：同一个开关还有
     * 第二个消费者 `CanvasConsoleView(liveReflow = …)`（视口侧，决定宽度变化要不要问出去）。
     * 两处各读一次就可能分叉 ⇒ 「视口一直问、核心一直拒」的空转回调：
     * `resizeConsole` 关态恒 false，于是既不重排也不换屏，而屏上看不出任何解释。
     * 现树唯一生产调用点把 `MainActivity.appliedLiveReflow` 同时喂给两者（`MainActivity.kt`
     * 的 `onCreateCore` 读数与 [GameHost] 构造点）。
     *
     * 关（缺省）= 宽度在会话期冻结、只改可见行数这条既有路径逐字不变，且本类**不注册**
     * [CanvasConsoleView.onViewportChange]（关态零新增行为，连闭包都不分配）。
     */
    private val liveReflow: Boolean = false,
    /**
     * 标题彩蛋屏蔽开关（缺省 = 屏蔽，与 [UiPrefs.DEFAULT_DISABLE_BAD_APPLE] 同值）。
     *
     * 与 [liveReflow] 同一条纪律：**必须由调用方在 UI 线程读好原样传入**，本类不许自己去摸
     * `UiPrefs` —— 本值唯一的读点是 [assembleSession]，而它跑在 VM 线程上（线程起点
     * [loadAndRun] 的 `Thread { … }`）；`SharedPreferences` 首读可能落盘，压在装载链
     * （预检/解码）最敏感的一段。现树唯一生产调用点（`MainActivity` 的 [GameHost] 构造处，
     * UI 线程）把壳层偏好的读数经命名实参交给本参数。
     *
     * 开 = `GameSession(disabledFunctions = EngineFunctionPolicy.DEFAULT_DISABLED)`（出厂口径）；
     * 关 = `emptySet()`（全部放行）。两支语义与开关取值逐字不变，只换「读的线程」。
     */
    private val disableBadApple: Boolean = true,
    /**
     * 「键入回显复刻」（ECHO-INPUT）开关，缺省 = 关（与 [UiPrefs.DEFAULT_ECHO_INPUT] 同值）。
     *
     * 与 [disableBadApple] 同一条纪律：**必须由调用方在 UI 线程读好原样传入**，本类不许自己去摸
     * `UiPrefs` —— 读点同样在 [assembleSession] 的 VM 线程上（线程起点 [loadAndRun] 的
     * `Thread { … }`）。生产调用点（`MainActivity.startGameOrDemo`，UI 线程）每次开局新建宿主
     * ⇒ 「设置页改完、重开一局即生效」的语义与旧的现读口径逐字一致。
     *
     * 开 = `EchoInputMode.REPLICA`（原版 `EmueraConsole.cs:1280-1282` 的编号可见）；
     * 关 = `SUPPRESSED`（回显行仍进逻辑行表，只从上屏帧剔除）。装置/夹具不经过本类
     * （直接构造 `GameSession`），那边仍吃 core 缺省的 `REPLICA`。
     */
    private val echoInput: Boolean = false,
    /**
     * 失败面文案（三语表 [ShellFailureCopy]）的唯一来源：构造期（UI 线程）取好一次，
     * 之后 [failure] 的全部写点（VM 线程 / 装载线程 / UI 线程）都只用这份快照拼串 ——
     * 照 ModImportController「起线程前一次取好进线程」的先例，纯层与 VM 线程不吃 context、
     * 不判 Locale。缺省 [ShellFailureCopy.ZH] 只为所有权外的装配点（DevTools/测试）保留；
     * 生产调用点（MainActivity 构造处）必须显式传。
     */
    private val failureCopy: ShellFailureCopy = ShellFailureCopy.ZH,
) {

    /** 装载或运行期的首错；null = 尚未失败。 */
    @Volatile
    var failure: String? = null
        private set

    /**
     * 最近一次失败落在「内存不够」这一族的**哪一段**（判据与两族的处置见 [HeapFailureStage]）。
     *
     * 两个消费者，判据都从这里读，不各自从文案反推：
     * - 失败屏那颗「少占内存再试」按钮 → [canRetryCompat]（两族都亮）；
     * - `MainActivity` 的**自动**降档 → `ShellUi.shouldAutoRetryCompat`，**只认 [HeapFailureStage.LOAD_OOM]**。
     *
     * 「上一局没让出堆」（[staleSessionHeap]）**不算**这一族（落 [HeapFailureStage.NONE]）：
     * 那是进程内残留问题，换档救不了，按钮不亮免得误导。
     */
    @Volatile
    internal var lastHeapFailureStage: HeapFailureStage = HeapFailureStage.NONE
        private set

    /**
     * 最近一次失败是不是「上一局的内存还没释放」（[staleSessionHeap] 那张屏，2026-10-31）。
     *
     * 失败屏据此决定那颗出口叫「重启应用」还是「结束应用」，**并且第一次上屏就点亮**：
     * 这一族的成因是上一局留下的 184~215 MiB **活对象**（真机实测：失败屏静置 30 s、
     * `Heap Alloc` 恒定），本进程内再重试多少次都是同一张屏 —— 只有换一个干净进程才解得开。
     * 与 [lastHeapFailureStage] 分开：那一族换省内存档就够，这一族换档没用。
     */
    @Volatile
    var lastFailureWasStaleSession: Boolean = false
        private set

    /**
     * 最近一次失败该按哪一族讲（失败屏的标题、产物那几行与说明句都由它选，
     * 判据见 [FailureScreenKind]）。
     *
     * 与 [lastHeapFailureStage] / [lastFailureWasStaleSession] 同一套写法：**由写
     * [failure] 的那几处就地置位**，不在界面侧从文案反推 —— 从「有没有『启动失败：』前缀」
     * 猜族会漏掉「缺少 …program.erbc」这条无前缀的产物故障，而它恰恰最该提产物。
     */
    @Volatile
    internal var lastFailureScreenKind: FailureScreenKind = FailureScreenKind.LOAD
        private set

    /**
     * 最近一次失败对应的错误报告（方案① 报错收集）；null = 本进程还没落出报告。
     *
     * 失败屏的「复制报告 / 分享报告」按钮读它；每次新的失败生成新文件并覆盖本引用，
     * 旧文件按滚动保留在 `filesDir/eratw-reports/`（见 [ErrorReportCollector.prune]）。
     * 报告内容与隐私口径见 core 的 [com.eratw.port.core.diag.ErrorReport] 类头。
     */
    @Volatile
    var lastReportFile: File? = null
        private set

    @Volatile
    private var closed = false

    @Volatile
    private var session: GameSession? = null

    /**
     * 最近一次触摸下的按钮指向态；null = 没指着按钮（空白/文本/越界）。
     *
     * UI 线程在 [updatePointer] 里写，VM 线程的 `MOUSEB()` 经 `MouseState.hitTest`
     * 只读这份缓存 —— `DisplayList` / `ButtonLayer` 是单线程（UI）结构，不能让 VM
     * 线程直接进去查。
     */
    @Volatile
    private var pointerPointing: ConsoleButtonPointing? = null

    /**
     * 最近一次 CSV 资源表装载的端点取证行（[ResourceContents.censusLine]）；null = 还没装配。
     *
     * 写侧是 VM 线程的 [installGraphics]，读侧是崩溃报告线程（[ErrorReportCollector.memoryLines]
     * 经 [resourceCensusReportLine]），故 `@Volatile`。存的是**已格式化的单行字符串**而不是
     * [ResourceContents.Report]：报告终点路径只读一个标量，不碰报告对象里的告警表，也不在
     * 崩溃当下做任何格式化分配。
     */
    @Volatile
    private var resourceCensusLine: String? = null

    /**
     * 最近一次装配的图形通路（DevTools 的 img/bg 命令走它试取图；未装载时 null）。
     *
     * 装载线程写、UI 线程读（低内存回调 [trimGraphicsCaches]）：跨线程可见性靠 `@Volatile`。
     */
    @Volatile
    internal var graphicsWiring: GraphicsWiring? = null
        private set

    /** 最近一次装配用的屏幕形状（`ConsoleScreen`）：[backToTitle] 重挂渲染桥要同一份。 */
    @Volatile
    private var lastScreen: ConsoleScreen? = null

    /**
     * 会话装配完成前收到的抢跑提交（[submit] / [submitSkip]），由 [pendingLock] 保护。
     *
     * 装载期 `MainActivity` 已经按「VM 线程活着」点亮提交按钮（`InputBarState.PRERUN`），
     * 但 [session] 要到装载末段才存在；此前这里直接对 null 丢弃，用户敲的字静默消失。
     * 先存下、[attachSession] 时按原顺序补交，等价于 `QueueInputSink` 的「无请求方也不丢」。
     */
    private val pendingBeforeSession = ArrayDeque<PendingInput>()
    private val pendingLock = Any()

    /**
     * 上一次「输入被接受」的时刻（纳秒，0 = 当前没有待测翻页）。
     *
     * 负责人实测「点开始游戏后像卡住，44 秒才出下一页」——那个延迟既可能是脚本计算
     * （世界初始化/角色生成），也可能是图像解码或渲染。这里只测**输入提交 → 下一步
     * `step()` 返回（页面就绪）** 的墙钟时间，按 `turn.latency ms=` 落 logcat；
     * 与 LoadTrace 的装载分段互补，是「44 秒落在哪」的第一手读数。
     *
     * 用 CAS 只在「当前没有待测翻页」时记起点：连点/排队提交不会互相覆盖，
     * 一次翻页只出一条读数（VM 线程读取后复位）。
     */
    private val turnStartNs = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 翻页读数环形缓冲（DevTools `turnlog` 读；每页一条，最多 64 条）。
     *
     * 口径 = 提交 → 下一次进入等待点的墙钟毫秒（[com.eratw.port.core.proc.QueueInputSink.onTurnReady]）。
     * 真机 vivo 把应用 logcat 整条丢掉的环境里，这是唯一能拿到的逐页读数。
     */
    private val turnLog = java.util.ArrayDeque<String>()
    private val turnLogLock = Any()

    /**
     * 翻页耗时的定长窗口分位累加器（obs-turnpct 波，口径与线程约束见 [TurnPercentiles]）。
     *
     * turnLog 只答「最近 64 页各自多少毫秒」，答不了「这一局整体变慢没有」——尾部劣化
     * （取证里 `2ms → 2342ms → 15192ms → 58515ms` 那种）在逐条读数里要人工拉日志才能看出来。
     * 这里在同一条 `onTurnReady` 交付上并喂一路分位统计，DevTools `turnlog` 末尾一行给
     * `n/p50/p95/max` 摘要。**逐条 turnLog 与两条日志口径原样保留**，别的调查还靠它们。
     */
    private val turnPercentiles = TurnPercentiles()

    /**
     * 三路心跳（obs-heartbeat 车道交付，组长接线）。由 `MainActivity` 在建宿主后注入，
     * 宿主在**脚本侧两个动作点**记一次 VM 心跳；UI 帧与渲染发布两路 `MainActivity` 自己记。
     * null = 未注入（裸装置/单测）⇒ 脚本路判读恒 UNREAD，不影响另两路出数。
     */
    @Volatile
    var heartbeat: Heartbeat? = null

    /**
     * 内存采样的下一条允许时刻（`elapsedRealtime` 口径，0 = 首条即采）。
     *
     * 只在 UI 线程（`MainActivity.pump` 重绘循环）读写，无需同步；
     * 节流判断先于一切 IO，为的是「开关没开时每帧只多一次 long 比较」。
     */
    private var memSampleNextMs = 0L

    /**
     * 采样落盘线程是否在飞（同一时刻最多一条，见 [sampleMemoryIfNeeded]）。
     *
     * UI 线程 CAS 置位、写线程 finally 复位。
     */
    private val memSampleWriting = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 本轮装载**实际**用的扁平池开关值（采样行的 `flat=` 列）。
     *
     * 归因 2026-10-29 那条 +114 MiB 读数时要能把「这台设备这一轮到底开没开」和读数对上，
     * 而开关有两个入口（release 走启动 Intent extra、debug 走开关文件）—— 事后从文件
     * 存在性反推会在「带 extra 启动后删掉文件」这类操作后失真，所以在这里把当轮取值钉住。
     * 装载线程写、UI 线程读，故 `@Volatile`。
     */
    @Volatile
    private var flatStatementsActive = false

    /** DevTools `turnlog`：最近若干页的耗时（新→旧按时间顺序存，返回原顺序）。 */
    fun debugTurnLog(): List<String> = synchronized(turnLogLock) { turnLog.toList() }

    /** DevTools `turnlog` 末尾的分位摘要一行（定长窗口口径，见 [TurnPercentiles.summaryLine]）。 */
    fun debugTurnPercentilesSummary(): String = turnPercentiles.summaryLine()

    /** 最近一次装载的读数（DevTools `loadtrace`；失败路径也保留）。 */
    @Volatile
    private var lastTrace: LoadTrace? = null

    /**
     * 装载完成回调，只触发一次，已切到 UI 线程。
     *
     * 触发点 = **程序/会话装载完成、脚本即将开始执行**（`loadAndRun` 里 `trace.mark("ready")`
     * 之后、`drive()` 之前），用于结算「这一轮装载成功」这类记账。**不要**在它里面收
     * 装载覆盖层：4.980 之后脚本会先画「少女祈祷中」进度条，4.981 已删掉那段脚本进度条，
     * 在这个点收会让 4.981 露出一段空屏。覆盖层的收尾点是画面第一帧非空内容落笔之后
     * （`CanvasConsoleView.onFirstContentFrame`，接线在 `MainActivity`）。
     */
    @Volatile
    var onConsoleReady: (() -> Unit)? = null

    /** DevTools `loadtrace`：本次装载逐段读数（段名 + 已用堆 + 距起点毫秒）。 */
    fun debugLoadTraceLines(): List<String> = lastTrace?.summaryLines() ?: emptyList()

    /** DevTools `sample`：脚本线程（VM 线程）引用；未启动 = null。 */
    fun debugVmThread(): Thread? = thread

    @Volatile
    private var thread: Thread? = null

    /** Activity 是否处于后台：启动装载与 `onPause` 的先后不确定，用它兜住这个窗口。 */
    @Volatile
    private var activityPaused = false

    /**
     * 后台暂停意图（[setBackgroundPaused]）与它的锁。
     *
     * 为什么要存**意图**而不是只转给当前会话：首启装载/重启期间 [session] 还是 null，
     * 壳层 30 秒到点的那一次暂停会直接丢掉，游戏在后台照常推进。这里先记下，
     * [attachSession] 挂上会话的一刻补应用。
     *
     * 锁保护「写意图 + 转交会话」与「读意图 + 补应用」两步互斥：否则两条线程交错时，
     * 已回到前台的会话可能被一条迟到的后台意图冻住。锁序固定 `pendingLock → pauseLock`
     * （只有 [attachSession] 同时持有两把），[setBackgroundPaused] 与 [close] 只拿这一把。
     *
     * 全仓唯一的锁序声明处在 core-vm 的 `core/diag/LockWatch.kt` 类 KDoc「锁序表」：
     * 本处是那张表的边 1（`pendingLock → pauseLock`）与边 2（`pendingLock → QueueInputSink`
     * 的输入锁，经 [deliver] 调 submit）。**改任何取锁顺序先去那张表**，回来同步这里的行号。
     */
    private val pauseLock = Any()
    private var backgroundPauseIntent = false

    /**
     * 键盘/指针后端（红 E）：原版 `GETKEY` / `GETKEYTRIGGERED` 轮询 Win32 `GetKeyState`
     * （spec: FunctionMethodCreator.cs:7805-7839），Android 只有事件，故由本实例把
     * [dispatchKey] 喂进来的事件折算成原版读数。
     *
     * 「窗口激活」取 Activity 未暂停：原版 `EmueraConsole.IsActive` 是 `window.Created &&
     * ActiveForm != null`（EmueraConsole.cs:258-268），未激活时 GETKEY 族一律返回 0
     * （FunctionMethodCreator.cs:7816-7819）—— 后台不攒按键。
     */
    val keyInput = KeyEventInputState(activeProvider = { !activityPaused })

    init {
        // 执行期点击意图的补写面：与脚本的 GETTEXTBOX/SETTEXTBOX 摸同一份会话文本框。
        // 会话未装配时两端退化为空，意图会在下一次读点被收走（不会凭空冒出文本）。
        keyInput.mirror = object : InputMirrorPort {
            override fun read(): String = textBoxHost()?.text ?: ""

            override fun write(text: String) {
                textBoxHost()?.setText(text)
            }
        }
    }

    /**
     * 一条 Android 按键事件。[androidKeyCode] 走 [AndroidKeyCodes] 换算成 Win32 VK；
     * 表外键（音量、系统导航的返回键等）直接丢，不伪造读数。
     */
    fun dispatchKey(androidKeyCode: Int, down: Boolean) {
        val vk = AndroidKeyCodes.toWinVirtualKey(androidKeyCode) ?: return
        if (down) keyInput.onKeyDown(vk) else keyInput.onKeyUp(vk)
    }

    /**
     * 补一次「按下并立即抬起」的沿变（[virtualKey] = Win32 VK）。
     *
     * 触屏没有可持续的指针按下态，软键盘的回车也到不了 Win32 的 `GetKeyState`；
     * 而语料的打断条件读的正是一次按下的沿变（`TITLE.ERB:158`
     * `GETKEYTRIGGERED(1) || GETKEYTRIGGERED(13) || GETKEYTRIGGERED(27)`：左键、回车、ESC）。
     * 所以宿主把「玩家真的做了一次动作」折算成对应键的一次沿变，由
     * [KeyEventInputState] 的存记保证下一次轮询仍能看到（短按不丢）。
     */
    fun fireVirtualKeyEdge(virtualKey: Int) {
        keyInput.onKeyDown(virtualKey)
        keyInput.onKeyUp(virtualKey)
    }

    /**
     * 首启解包的结果缓存：`null` = 还没试过，`true`/`false` = 本进程内的结论。
     *
     * 只在 UI 线程（`MainActivity` 的启动路径）写，另加 `@Volatile` 兜住从 VM 线程的读。
     */
    @Volatile
    private var assetsReady: Boolean? = null

    private val sound = DelegatingSoundSink()

    /**
     * 存档目录迁移询问桥（M11-A-WIRE-2）。
     *
     * core 在 `FileSystemOps` 构造期（VM 线程）同步问一次；这里把 UI 作答投到 UI 线程并用
     * 闩锁把 VM 线程停在询问上。**缺它时 core 缺省 `AUTO_ACCEPT`** ⇒ 玩家把 `USE SAV FOLDER`
     * 改成 YES 后旧档会被静默搬走（原版 `Config.cs:564-567` 必须先问）。
     */
    private val saveMigrationPrompt = BlockingSaveMigrationPrompt(
        SaveMigrationPromptUi { title, message, answer -> askSaveMigrationOnUi(title, message, answer) },
    )

    /** 正在等玩家作答的询问（UI 线程置/清；VM 线程只在装配期读一次）。 */
    private val promptLock = Any()

    /**
     * 会话期文案语言档（i18n，2026-11-07）：主线程构造时取一次 `SettingsLanguage` 单路映射。
     *
     * 游戏内改语言不跟随（与字号/行高「重启生效」同口径，见 `SettingsActivity.changeLanguage`
     * 的既有注释）；VM 线程（如 [scriptErrorTerminal]）只读本缓存，**不**直读
     * SharedPreferences（AGENTS §6 的非主线程约束）。
     */
    private val textVariant: LangVariant = langVariantOf(UiPrefs.settingsLanguage(view.context))
    private var pendingSaveMigrationAnswer: ((Boolean) -> Unit)? = null

    /** 屏上那条迁移询问框（取消作答时要一起收起，不能留着点了没反应）。 */
    @Volatile
    private var pendingSaveMigrationDialog: AlertDialog? = null

    /**
     * `UPDATECHECK` 的取流面（判网络 + 取回远端前两行，带超时；实现见
     * `platform/update/UpdateCheckTransport.kt`）。
     *
     * 无状态、可复用：一个宿主实例一份，会话重建也不换（构造只是存三个超时常数）。
     */
    private val updateTransport = UpdateCheckTransport()

    /**
     * UPDATECHECK 询问的阻塞口 —— **复用** [BlockingSaveMigrationPrompt]（同一把闩锁、同一个
     * 「VM 线程同步问一次、UI 线程作答」机制），只是投递到 [askUpdateCheckOnUi]。
     *
     * 为什么可以直接复用：这个类的实现就是 `(title, message) -> Boolean`，与「问的是什么」无关；
     * 它的作答闸是一次性的（重复作答/关框/取消只认第一次），UI 投递失败按「否」收敛，
     * 绝不把等待线程永久挂住。UPDATECHECK 不需要第二把 latch。
     *
     * 「超时」与「没问到」这两件它给不了的，由 [UpdateAskTicket] 在投递侧补：
     * 原版 `MessageBox.Show` 抛异常时走 catch 落 RESULT=3（`FunctionIdentifier.cs:2802` + `:2827-2832`），
     * 而选「否」是 RESULT=1 —— 两者必须能区分，否则「玩家没被问过」会被谎报成「玩家拒绝了」。
     */
    private val updatePrompt = BlockingSaveMigrationPrompt(
        SaveMigrationPromptUi { title, message, answer -> askUpdateCheckOnUi(title, message, answer) },
    )

    /** 正在等玩家作答的更新检查询问（与迁移询问共用 [promptLock]，各自一个槽位）。 */
    private var pendingUpdateAnswer: ((Boolean) -> Unit)? = null

    @Volatile
    private var pendingUpdateDialog: AlertDialog? = null

    /**
     * 死循环告警的阻塞口（ENGFIX-4）。
     *
     * 原版在语句边界同步弹框（skia `Process.cs:478-488`：Yes = 强制结束，No = 继续，
     * 没有可用 UI 时什么都不做）；阈值来自会话生效配置的 `InfiniteLoopAlertTime`
     * （缺省 5000ms，置 0 关）。本桥用 `CountDownLatch` 把 **VM 线程**钉在
     * [askInfiniteLoopOnUi] 的作答上；照 [BlockingSaveMigrationPrompt] 同一条纪律：
     * 投递失败/关闭中断一律回落 [InfiniteLoopAnswer.NoUi]，绝不永久挂住。
     */
    private val infiniteLoopPrompt = BlockingInfiniteLoopPrompt(
        InfiniteLoopPromptUi { alert, answer -> askInfiniteLoopOnUi(alert, answer) },
    )

    /** 正在等玩家作答的死循环告警（与其它询问共用 [promptLock]，各自一个槽位）。 */
    private var pendingInfiniteLoopAnswer: ((InfiniteLoopAnswer) -> Unit)? = null

    @Volatile
    private var pendingInfiniteLoopDialog: AlertDialog? = null

    /**
     * 本次询问的结局票据：由 [askUpdateCheckOnUi]（VM 线程同步进入）建好并写在这里，
     * 由 [updatePrompt] 放行后在同一条线程读回。
     *
     * 只在 VM 线程读写（`updatePrompt.invoke` 阻塞期间 UI 线程只写票据内部字段），
     * 票据内容经闩锁的 release/acquire 建立 happens-before，读侧不需要额外同步。
     */
    private var currentUpdateAskTicket: UpdateAskTicket? = null

    /** 更新检查询问的 UI 线程投递器（超时兜底用 `postDelayed` / `removeCallbacks`）。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 音频焦点会话（**原版没有这一层**，见 `core/audio/AudioFocus.kt` 的 [com.eratw.port.core.audio.AudioFocusEvent]）。
     *
     * 目标端**按需**取表：`store.aux` 在 [loadAndRun] 里才建出来，而焦点事件可能在启动过程中
     * 就到达（Activity 先 `onResume`），所以用 `peek` 只查不建 —— 表还没建时事件直接丢弃。
     * 表建好之后，`PLAYBGM` / `PLAYSOUND` 才会往混音器里挂声道，那时的失焦才有对象可挂起。
     *
     * 恢复语义（DEV-06）：本宿主只在装载（[loadAndRun]）与 `onResume` 两个点申请焦点；
     * 前台被其他播放器**永久**抢占后，会话停在可重申请状态，若系统补投迟到的 `GAIN`
     * 会由会话自动重申请恢复 —— 本类无需第三个申请点（取舍与后台丢弃规则见
     * [AudioFocusSession] 类 KDoc 的平台最优说明）。
     */
    private val focus = AudioFocusSession(
        AndroidAudioFocusRequester(view.context),
        AudioFocusTarget { event ->
            val table = session?.store?.aux?.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT)
            if (table == null) {
                0
            } else {
                table.mixer.onFocusEvent(event).also { changed ->
                    Log.i(TAG, "焦点 $event → 挂起/恢复 $changed 条声道")
                }
            }
        },
    )

    /**
     * 产物是否齐（缺一份都不启动，避免把「没推语料」误报成脚本错）。
     *
     * 首启解包（[ensureAssetsInstalled]）必须在这里**之前**跑：`MainActivity` 用本方法决定
     * 「走真机装载」还是「演示屏」，解包若只放进 [start] 就永远轮不到（`MainActivity` 先问本方法）。
     *
     * 解包失败时本方法返回 **true**（而不是 false）：`MainActivity` 先问一次拿到 true 才会把
     * host 挂上去、再调 [start]；由 [start] 的第一道闸把 [failure] 里的
     * 「启动失败：首启解包失败：…」原样上屏。返回 false 只会落到演示屏的「未找到产物」，
     * 屏上就丢了真正的原因（盘满、assets 坏、SHA 不配对）。
     */
    fun artifactsAvailable(): Boolean {
        if (!ensureAssetsInstalled()) return true
        return artifactsOnDisk()
    }

    /** 盘上的产物是否齐（解包之后再问；缺一份都算缺）。 */
    private fun artifactsOnDisk(): Boolean {
        val dir = File(storage.gameRoot(), ARTIFACT_DIR)
        return File(dir, PROGRAM_FILE).isFile && File(dir, META_FILE).isFile
    }

    /** 产物目录（错误提示里原样显示，便于 adb push 对位）。 */
    fun artifactDir(): File = File(storage.gameRoot(), ARTIFACT_DIR)

    /**
     * 拉起 VM 线程。返回 false 时 [failure] 已写好（未布局 / 首启解包失败 / 产物缺失）。
     * 只在 UI 线程调用（读 [CanvasConsoleView.consoleScreen]）。
     */
    /** PERF-BOOT（2026-10-26）：供跨线程埋点的句柄（`trace` 是 loadAndRun 局部量，UI 线程拿不到）。 */
    @Volatile
    private var bootTrace: LoadTrace? = null

    /**
     * 会话启动前把 `emuera.config` 的**生效**字号/行高灌进 [view]。
     *
     * 为什么要在这里重读一遍配置：`GameSession` 在 VM 线程构造时才读配置，而版面
     * （`consoleWidth` / `ConsoleFeed.metrics`）必须在 UI 线程的 `start()` 里先定下来。
     * 配置链只有几 KB，重复读一次是便宜的；读失败（首启/权限）就保持 View 的 18/19 缺省，
     * 不阻断开局。`emuera.config` 的改动本来就只在下一次会话生效（见 `SettingsActivity`）。
     *
     * ## 窗口宽（`WindowX`）**不是**版面输入（0.2.43 玩家回归根修）
     *
     * 原版 Windows 上 `Config.WindowX` 是「主窗口外框宽」：玩家拖拽窗口后由
     * `MainWindow.cs:726` 把**实际客户区宽回填**进这个静态字段，所以它是版面解算的**输出**。
     * Android 没有可调整大小的主窗口，客户区宽由屏幕决定，同样是输出；让
     * `emuera.config` 里的 PC 遗留值反向裁剪手机版面，等于把因果倒过来。
     *
     * 0.2.43（`0664085e`）曾在这里调 `applySourceWindowMetrics(effective.windowX, 0)`，
     * 而 `effective` 走的是 `applyChainTo` 的**无宿主播种**条目表：旧玩家盘上的
     * `emuera.config` 若没有 `WINDOW WIDTH` 键（升级只补缺键、不覆盖已有值），读到的就是
     * `ConfigSpec` 裸缺省 **760**（`ConfigData.cs:56` 的 PC 值）。闭环如下：
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
     *
     * 因此这里显式传 `(0, 0)` = **未接线**：`ScreenLayout` 按物理内容宽满宽解算，
     * 与 0.2.42 逐像素相同。`ScreenLayout.resolve` / `CanvasConsoleView` 的窗口宽能力
     * 保留（将来要真窗口/分屏时由宿主显式喂），只是不再由配置文件自动触发。
     * `WindowY` 同理**不接线**（纵向观感零变化），见 `windowHeightPxFor` 的管线。
     *
     * ## 正向链（同源回填，2026-11-08 补完）
     *
     * 断开反向输入不等于断掉正向回填：屏上**实际**版面宽经 `view.windowWidthScriptPx()`
     * 反折成脚本 px（未接线时它返回满宽版面的逆折算，不再返回 0），由 `GameSession` /
     * `ConfigMethods.backfillWindowWidth` 在三层链**之后**回填 `WindowX` —— 原版
     * `MainWindow.cs:726` 的「开窗后回填实际客户区宽」语义。回填不接的话，core 的
     * `WindowX` 停在文件值/裸缺省 760 ⇒ `drawableWidth` ≈ 42 格而屏上 139 列，
     * `MAXWIDTH()/45` 也掉进除零区，HTML_PRINT 折行尺与屏上分叉成两把尺。
     */
    private fun applyBootFontMetrics(gameRoot: File) {
        val effective = runCatching {
            val store = ConfigStore()
            RootedBuiltinHost(gameRoot.toPath()).applyChainTo(store)
            EffectiveConfig.of(store)
        }.getOrNull() ?: return
        view.applySourceFontMetrics(effective.fontSize, effective.lineHeight)
        // 有意不灌 effective.windowX：见上方 KDoc 的 0.2.43 回归链。
        // 0 = 未接线 ⇒ View 按物理内容宽满宽解算；applySourceWindowMetrics 同值时早退，
        // 不会触发无谓的 rebuildScreen。两实参都保持「未接线」语义，缺一不可。
        view.applySourceWindowMetrics(0, 0)
    }

    fun start(): Boolean {
        // close() 之后不再拉起线程。调用方与 close() 可能不在同一条时间线上
        // （解包收尾的 runnable 在 UI 线程、onDestroy 的 close 在它前后都可能先到），
        // 而 close() 是幂等的、不会再关第二次 —— 放行一次就等于留下一条没人收的 VM 线程
        // 与一份整局 `Program`（600 MiB 档）。会话装配完到挂上 `session` 之间还有第二道闸，
        // 见 [loadAndRun]。
        if (closed) return false
        if (thread != null) return true
        // 布局闸：控制台必须先布局，否则既解不出列宽，也灌不进字号。
        if (view.consoleScreen() == null) {
            lastFailureScreenKind = FailureScreenKind.LOAD
            failure = failureCopy.consoleNotLaidOut
            return false
        }
        // 首启解包在这一步兜底（MainActivity 已经问过 artifactsAvailable，但那可能发生在
        // 别的入口；幂等且进程内只跑一次，这里再问一次不会重复拷）。
        if (!ensureAssetsInstalled()) return false
        if (!artifactsOnDisk()) {
            lastFailureScreenKind = FailureScreenKind.ARTIFACT
            failure = missingArtifacts()
            return false
        }
        val gameRoot = storage.gameRoot()
        // 字号是**版面输入**：必须在取 screen/metrics 之前，把本会话将要读到的生效配置灌进
        // View 并按可见区重解一次。否则 `GameSession.consoleWidth`（取 screen.clientWidth）
        // 与 `ConsoleFeed` 的 metrics 会停在旧字号，控制台折行宽与画面列数错位。
        applyBootFontMetrics(gameRoot)
        val screen = view.consoleScreen() ?: run {
            lastFailureScreenKind = FailureScreenKind.LOAD
            failure = failureCopy.consoleNotLaidOut
            return false
        }
        // 脚本屏幕像素（GFX-06）：在 UI 线程一次性取走实测度量，VM 线程不再回头摸 View。
        // 时点与 consoleScreen 相同（布局已发生，否则上面那道闸已经拦下）。
        // 两个像素是**设备像素**；随行的设备字号只用来把内容区折回脚本 px（core 侧 ScriptPixels）。
        // 配置字号**不在这里取**：语料的指令按钮网格拿 GETCONFIG("フォントサイズ") 当尺，
        // 量宽必须同尺；会话自己按生效配置报字号（core 侧 GameSession.scriptFontPixelSize）。
        // 设备字号必须在 applyBootFontMetrics 之后读 —— 那一句才会把本会话字号灌进 View 并重解版面。
        val metrics = ScriptPixelMetrics(
            clientWidthPx = view.clientWidthPx().toLong(),
            clientHeightPx = view.clientHeightPx().toLong(),
            deviceFontPixelSize = view.cellMetrics().fontPixelSize,
            // R5.1 同源回填：View 实际排版用的版面宽（设备 px）按同一折算比反折成脚本 px，
            // GameSession 在三层链之后把它回填成 `WindowX`（原版 MainWindow.cs:726 的
            // 「开窗后回填实际客户区宽」）—— core 折行宽 `WindowX - max(2, FontSize/6)`
            // 折算到设备像素后与屏上可绘制宽同源（差不到一格）。
            // 0 = 未布局（View 还没解算过版面）⇒ 回填层不写，`WindowX` 保持链读数。
            windowWidthScriptPx = view.windowWidthScriptPx().toLong(),
        )
        // FN-04：带样式的分段量宽（字体族 / 粗斜体 / charSpace）。与像素尺度同批在 UI 线程
        // 构造，Paint 由实例锁保护，VM 线程跨线程调用安全。
        val textMeasure = AndroidTextMeasure.fromContext(view.context)
        // 交接登记（[SessionHandoff]）：**先登记、再起线程**。登记与注销必须把「整份 Program
        // 压在这条 VM 线程栈上」的窗口完整包住 —— 下一个 Activity 实例的装载线程据此判断
        // 「上一局还没让出堆」，在堆预检之前等它退出（2026-10-31 玩家 422 MiB 那条）。
        // 线程对象先构造再登记：构造本身若抛（极罕见），不会留下一条没人注销的登记。
        val worker = Thread(
            {
                try {
                    loadAndRun(gameRoot, screen, metrics, textMeasure)
                } finally {
                    SessionHandoff.unregister(this@GameHost)
                }
            },
            VM_THREAD_NAME,
        )
        SessionHandoff.register(this)
        thread = worker
        try {
            worker.start()
        } catch (t: Throwable) {
            SessionHandoff.unregister(this)
            thread = null
            throw t
        }
        return true
    }

    /**
     * 首启解包：把 APK assets 里的 `erbc.v1` + `CSV` / `resources` / `sound` / `emuera.config`
     * 落到 `GameStorage.gameRoot()`，进程内只跑一次。
     *
     * 返回 false 时 [failure] 已写好可读文案；**不抛**异常（解包失败不该把 Activity 带走）。
     * 解包器按 1 MiB 块流式写、SHA-256 边拷边算，因此这一步的堆增量与产物大小无关
     * （361.1 MiB 产物在 512 MiB 堆机型上的前提，见 [GameAssetInstaller] 类头）。
     *
     * 进度以「解包中 N/M <资产名>」进 logcat：真机上首启要拷 361 MiB，装不上时没有 adb
     * 也能从这一行看出卡在哪一条。
     */
    /**
     * 解包进度快照（UI 的启动屏读它，`null` = 本轮没在解包）。
     *
     * 为什么不让 UI 自己数盘上文件：解包时磁盘正满负荷写，再每 800 ms 递归 walk 一遍
     * 三万多条目录项（`landedInventory`）等于给同一个存储再加一路随机小 I/O，
     * 而且**读数还追不上**真实进度。解包器本来就知道 `copied/total`，直接借出来。
     */
    @Volatile
    private var lastInstallProgress: GameAssetInstaller.Progress? = null

    /** 最近一次解包进度；[ensureAssetsInstalled] 结束后清成 null。 */
    fun installProgress(): GameAssetInstaller.Progress? = lastInstallProgress

    private fun ensureAssetsInstalled(): Boolean {
        assetsReady?.let { return it }
        // #3（2026-11-01 挖虫波）：走到这里 = MainActivity 的 `artifactsAvailable()` 预热**没发生过**
        // ——而 [start] 声明只在 UI 线程调，这条兜底会在 UI 线程同步解 361 MiB/3,971 条
        // （首启形态 = 分钟级 ANR，解包锁竞争可再叠 5 分钟）。生产入口都有预热，触发者只会是
        // 新入口（DevTools/测试装配/第二个 Activity）。本轮先钉一条响亮告警把代价点名；
        // 「未预热 ⇒ 拒开」与「解包挪进 VM 线程首段带进度」属产品口径，登记 BOARD 待裁。
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Log.w(TAG, "ensureAssetsInstalled 在 UI 线程上未命中预热缓存：可能整段同步解包（首启分钟级）——调用方应先在后台问 artifactsAvailable")
        }
        val ok = try {
            var lastLogAt = 0L
            var lastLogged = -1
            // C-1 首启版本闭环：把本机 versionCode 交给解包器当摘要版本号。新装一盘时它会把
            // APK 里的资产全落一遍、顺手写出一份完整 `update/data-digest.txt`；收尾再把同一个
            // 数写进 `data-version.txt`，两份文件同源同值 ⇒ 差量基线当场可用，不必先花
            // ~600 MiB 走一次全量更新。换 APK 时盘上已有旧数据，解包器自己会拒绝写半张表
            // （见 GameAssetInstaller.writeDataDigest），这里也就不会前推标记。
            val dataVersionCode = UpdateManager.currentVersionCode(view.context)
            val report = GameAssetInstaller(
                storage.assetTree(),
                storage.gameRoot(),
                recordDataVersion = dataVersionCode,
            )
                .install { progress ->
                    lastInstallProgress = progress
                    // 逐条打日志是 3600+ 行 logcat，在真机上这条 I/O 本身就要几百毫秒：
                    // 按「每 1/16 或每 500 ms」两种口径节流，末条由下面的完成行补齐。
                    val now = System.nanoTime()
                    val step = (progress.total / LOG_PROGRESS_STEPS).coerceAtLeast(1)
                    if (progress.copied != lastLogged &&
                        (progress.copied >= lastLogged + step ||
                            now - lastLogAt >= LOG_PROGRESS_MS * 1_000_000L)
                    ) {
                        lastLogged = progress.copied
                        lastLogAt = now
                        Log.i(
                            TAG,
                            "解包中 ${progress.copied}/${progress.total} ${progress.label}",
                        )
                    }
                }
            lastInstallProgress = null
            Log.i(
                TAG,
                "首启解包完成：写入 ${report.copiedFiles} 个文件 ${report.copiedBytes / (1024 * 1024)} MiB，" +
                    "跳过 ${report.skippedFiles} 个已在位，" +
                    "program-sha256 核对=${report.programSha256Verified}，根=${storage.gameRoot().absolutePath}",
            )
            report.recordProblem?.let {
                // 摘要没写下去只影响「下一轮能不能走差量」，不影响本轮数据正确性。
                Log.w(TAG, "随包解包的文件级摘要没写成（下一次更新仍按没摘要走全量）：$it")
            }
            // C-1：只有盘上**确实有这一版号的完整摘要**才前推 `update/data-version.txt` ——
            // data-version.txt 与 data-digest.txt 同源同值，UpdateManager 的盘可信闸（C-3）
            // 才敢把差量资格放出去。没有摘要（--skip-assets 小包 / 旧版升上来的盘 / 摘要写失败）
            // 就保持原标记不动：宁可这一次更新走全量自愈，也不能让标记谎报盘上的内容。
            val recordedDigest = runCatching { GameAssetInstaller.readDataDigest(storage.gameRoot()) }.getOrNull()
            if (report.recordProblem == null && recordedDigest?.version == dataVersionCode) {
                UpdateManager.rememberInstalledDataVersion(storage.gameRoot(), dataVersionCode)
                Log.i(TAG, "数据版本标记已闭环：update/data-version.txt = $dataVersionCode（与文件级摘要同版）")
            } else {
                Log.i(
                    TAG,
                    "数据版本标记不前推：本次要求=$dataVersionCode，摘要版本=${recordedDigest?.version ?: "无"}，" +
                        "摘要问题=${report.recordProblem ?: "无"}（盘上数据按原标记处理）",
                )
            }
            // **装载前的一拍**：把上次下载好的镜像数据包应用到游戏根。
            // 位置是刻意的，必须在随包解包**之后** —— 反过来的话新包里的旧数据会把刚更新的数据
            // 盖回去。失败不影响本次启动（暂存指针还在盘上，下次重试，见 UpdateManager）。
            when (val applied = UpdateManager.applyStagedBundleIfPresent(view.context, storage.gameRoot())) {
                is UpdateManager.ApplyOutcome.Done -> {
                    Log.i(
                        TAG,
                        "镜像数据包已应用：档位 ${applied.kind}，版本 ${applied.version}，写入 ${applied.copiedFiles} 个文件 " +
                            "${applied.copiedBytes / (1024 * 1024)} MiB",
                    )
                    // 漏斗遥测第 7 拍（U10）：应用成功这一拍。档位经 core 的唯一映射落标签
                    //（Done.kind 恒非 NONE —— stagedApplyPlan 的包内实况四档里 NONE 只属于
                    // 「没包可下」，能应用就有档位），成功档缺 kind 会被 core 整条拒。
                    UpdateTelemetryWiring.assetsApplied(
                        view.context,
                        applied.version,
                        UpdateTelemetry.OUTCOME_SUCCESS,
                        applied.kind,
                        null,
                    )
                }

                is UpdateManager.ApplyOutcome.Failed -> {
                    Log.w(TAG, "镜像数据包没应用上：${applied.problem}")
                    // C-5：失败不能只留 logcat。一次性启动提示走 Toast（不挡启动、不需要玩家点掉），
                    // ensureAssetsInstalled 在后台解包线程上，show 前先 post 回 UI 线程。
                    // 每进程只报一次：ensureAssetsInstalled 由 assetsReady 缓存，重试发生在下次冷启。
                    view.post {
                        runCatching {
                            Toast.makeText(view.context, applied.problem, Toast.LENGTH_LONG).show()
                        }.onFailure { Log.w(TAG, "数据包应用失败提示没弹出来（已进日志）", it) }
                    }
                    // 漏斗遥测第 7 拍（U10）：应用失败这一拍不再静默 —— core 已开 outcome/kind/failure
                    // 三格（U6 落拍 5 时「Failed 那一格接不了」的口子已补上）。failure 交失败类别
                    // 枚举的短标签；problem 原话可能带路径与文件名，一个字都不进漏斗。
                    UpdateTelemetryWiring.assetsApplied(
                        view.context,
                        null,
                        UpdateTelemetry.OUTCOME_FAILED,
                        null,
                        applied.category.label,
                    )
                }

                is UpdateManager.ApplyOutcome.Nothing -> Unit
            }
            // 冲刷点之三（U6）：这一拍每次冷启必到、且跑在后台解包线程上 —— 上一轮攒在盘上
            // 没发出去的待发行（含主线程中继里那几条）在这里兜底走一趟。**走异步**是硬要求：
            // 本函数的返回值就是「游戏能不能开始装载」，同步等一趟 HTTP 等于让端点快慢
            // 决定玩家看到首屏的时间（形状照 ErrorReportUploader.uploadPendingAsync）。
            UpdateTelemetryWiring.flushPendingAsync(view.context)
            // 匿名玩家数：同一拍里挂上，但自己另起 daemon 线程（PlayerStatsWiring.reportAsync），
            // 与漏斗冲刷互不等待。关闸 / 没配端点时这一发只是白跑一趟判断，出站字节为零；
            // 每天至多一次，失败不外逃、不碰 assetsReady 这个返回值。
            PlayerStatsWiring.reportAsync(view.context)
            true
        } catch (t: Throwable) {
            lastInstallProgress = null
            Log.e(TAG, "首启解包失败", t)
            lastFailureScreenKind = FailureScreenKind.ARTIFACT
            failure = failureCopy.failurePrefix +
                failureCopy.fill(failureCopy.unpackFailedFormat, t.message ?: t.javaClass.simpleName)
            false
        }
        assetsReady = ok
        return ok
    }

    /** 提交一条「回车文本」；VM 没在等时按原版丢弃（见 [submitInternal] 的执行期闸）。 */
    fun submit(text: String) = submitInternal(text, mesSkip = false)

    /**
     * 提交一笔**画面按钮点击**（[submit] 的同一条路，多带一个「点击」维）。
     *
     * 与 [submit] 的唯一差别：若此刻没有等待方（执行期），这次落点会被执行期闸丢弃，
     * 但会记进 [KeyEventInputState] 的**单槽点击意图**，由下一次 VK_LBUTTON/VK_RETURN 读点
     * 补写回文本框镜像 —— QoL 地图/外出屏在 `CHARA_MOVEMENT` 长链（≈5.4s）执行期间点的
     * 按钮因此不再丢（`AWAIT` + `GETTEXTBOX` 轮询能读到；详见 [KeyEventInputState] 类文档
     * 「执行期点击意图」节）。
     *
     * 只有画面按钮走这一口；输入条键入仍走 [submit]（键入的文本不补写：它的语义是「这次
     * 回车提交什么」，跨屏补交会撞 2026-10-25 的取值串屏红）。
     *
     * **第二维：鼠标按钮位（玩家子选单案修复）**。原版
     * `MinorShift.Emuera.Forms/MainWindow.cs:824-849` 在带鼠标的 INPUT 等待下命中按钮时，
     * 除了把 `selectedString` 交给 `PressEnterKey`（→ `RESULT:0`），还先写了
     * `RESULTS:1`（`:834`）与 `RESULT:1 = 1`（`:847`，左键）。`QOL_USERCOM.ERB @COM_OPTION`
     * 的 `INPUT -1, 1` 就读 `RESULT:1` 判左键，缺了它子选单「点选项没反应」。
     * 因此本口在有等待方时改投 [QueueInputSink.submitButtonClick]，把「这条文本来自按钮」
     * 带进队列；形态闸在 sink 内部，键盘提交与不带鼠标的等待不受影响。
     * Android 触摸只有左键语义 ⇒ 固定 1。
     */
    fun submitButton(text: String) =
        submitInternal(text, mesSkip = false, clickIntent = true, mouseButton = 1)

    /**
     * 提交一条**跳过**（`PressEnterKey(keySkip = true, ...)`，`EmueraConsole.cs:1371-1373`）：
     * 双击/右键/Esc 的语义。跳过态由 sem 层落到 `ExecContext.mesSkip`，在下一个取值输入前结束。
     * VM 没在等时文本同样丢弃，但跳过意图经 [QueueInputSink.noteSkipIntent] 保留
     * （原版 `MesSkip = keySkip` 在 Running 早退**之前**，:1373）。
     */
    fun submitSkip(text: String) = submitInternal(text, mesSkip = true)

    /**
     * 提交一笔**遮罩取色**（`INPUTMOUSEKEY` / 带 `mouseInput` 的 INPUT 等待下长按命中
     * 遮罩段，`MainWindow.cs:790-796` / `:824-830` 的 `InputInteger(6/3, GetMappedColor)`）。
     *
     * 与 [submitInternal] 的两点口径差（都是原版语义，不是省事）：
     * - **不触发 VK_RETURN 沿变**——色是鼠标按下事件携带的值，与回车键无关；
     * - **不进 [pendingBeforeSession]**——装载期直接丢弃：取色的前提是 canvas 正在渲染
     *   遮罩图，会话必然在位；给色扩 [PendingInput] 维会把文本对账式
     *   （`received - dropped(gate) = lastSubmittedSeq`）搅进非文本量。
     *
     * 等待形态闸在 `QueueInputSink.submitMappedColor` 内部（不匹配的等待拒收并
     * `drop(color)` 记账，core 面流水），宿主侧不重复预筛、不打文本流水计数。
     * 返回值 = 是否真正入队（Boolean，透传自 sink）；宿主侧不消费它。
     */
    fun submitMappedColor(color: Long) {
        synchronized(pendingLock) {
            session?.inputSink?.submitMappedColor(color)
        }
    }

    /**
     * CBG 按钮（取色图 `CBGSETBMAPG`）命中值的提交口。
     *
     * 原版：鼠标按下时先用 `cbgButtonMap` 在点击点取色，`A == 255` 才算命中，
     * 命中值 `= pixel.ToArgb() & 0xFFFFFF` 进 `InputResult5` 整组
     * （`EmueraConsole.cs:1309-1320`、`:1327-1337`）。与 [submitMappedColor] **不是**
     * 一个值域：那条是行内 `PRINT_IMG` 的 `namem` 遮罩取色，本条是整屏 CBG 按钮图的
     * 取色图；命中坐标、判据与生效等待形态都不同。
     *
     * 等待形态闸在 `QueueInputSink.submitCbgButtonValue` 内部（只有 INPUTMOUSEKEY 消费，
     * 其余形态丢弃并记 `drop(cbgbtn)`，core 面流水可查），宿主侧不重复预筛。
     */
    fun submitCbgButtonValue(value: Long) {
        synchronized(pendingLock) {
            session?.inputSink?.submitCbgButtonValue(value)
        }
    }

    /**
     * 两条提交的唯一入口：会话已装配就直接进 `QueueInputSink`；装载期（[session] 仍为 null）
     * 先留在 [pendingBeforeSession]，等 [attachSession] 补交。
     *
     * **执行期闸（原版 `PressEnterKey` 的 Running/Initializing 早退，`EmueraConsole.cs:1374-1377`）**：
     * 当前没有等待方（[QueueInputSink.isAwaiting] 为假）时，文本**丢弃**、不落队列 —— 原版
     * 「脚本跑着的时候按的回车」不会落到下一次等待。不闸的话：世界初始化页一跑十几秒，
     * 期间玩家的点击/输入全部入队，被**下一个界面**的 INPUT 吃掉，值跨屏错位
     * （2026-10-25 真机红：口上选择屏 `qol_update.ERB:358` 的 `CFLAG:(V-2000)/10` 收到
     * 上一屏残留的 4000 系值 ⇒ `CFLAG:200` 越界启动失败）。跳过意图仍保留（见
     * [submitSkip]）。isAwaiting 读与 submit 之间有 µs 级竞态窗口，与原版事件态翻转窗口同构。
     *
     * 与 [attachSession] 共用 [pendingLock]：一条提交要么整条发生在装配前（进队列、随后
     * 先于任何后来的提交被补交），要么整条发生在装配后（直接进 sink），不会插队。
     * 宿主已 [close] 时不再收新提交（`QueueInputSink` 自己也拒绝已关闭的队列）。
     */
    private fun submitInternal(
        text: String,
        mesSkip: Boolean,
        clickIntent: Boolean = false,
        /** 非 null = 这次提交来自画面按钮点击，值 = 鼠标按钮位（左 1 / 右 2 / 中 3）。 */
        mouseButton: Int? = null,
    ) {
        // R3：每次提交一条结构化事件（含被闸丢掉的那些 —— 因果链要能看出「玩家按了但没进」）。
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
        // 任何一次新提交都是玩家最新的动作：上一笔尚未被读点收走的点击意图就此作废
        // （否则键入的文本会被旧点击值在补写时盖掉）。必须放在补沿变**之前**：先补沿变再
        // 作废，VM 线程可能拿这条新沿变去解析旧意图，把上一笔旧落点补写进镜像。
        keyInput.clearClickIntent()
        // 提交动作 = 玩家敲了一次回车（原版文本框提交走 `PressEnterKey`，MainWindow.cs:938-966）：
        // 补一次 VK_RETURN 沿变，剧本里 `GETKEYTRIGGERED(13)` 形态的打断条件才有机会为真
        // （红 E，见 [fireVirtualKeyEdge]）。
        fireVirtualKeyEdge(AndroidKeyCodes.VK_RETURN)
        synchronized(pendingLock) {
            val sink = session?.inputSink
            if (sink != null) {
                deliver(sink, text, mesSkip, clickIntent, mouseButton)
            } else if (!closed) {
                // 装载期的抢跑提交也带上来源维：补交时若恰有等待方（见 attachSession），
                // 不能因为跨了一道 pending 队列就把「这是一次按钮点击」丢掉。
                pendingBeforeSession.addLast(PendingInput(text, mesSkip, mouseButton))
            }
        }
    }

    /**
     * 会话在位时的投递策略（[submitInternal] 与 [attachSession] 补交共用）：
     * 有等待方 ⇒ 原样入队并记翻页计时起点；没有 ⇒ 文本丢弃、只剩跳过意图
     * （[QueueInputSink] 类文档第 1 条的宿主接线，2026-10-25）。画面按钮点击额外把落点记进
     * 单槽点击意图（[KeyEventInputState.noteClickIntent]），由下一次按键读点补写回文本框。
     *
     * [mouseButton] 非 null 时改投 [QueueInputSink.submitButtonClick]（文本 + 来源维），
     * 让带鼠标的 INPUT 等待能拿到原版的 `RESULT:1` / `RESULTS:1`；执行期闸在**两类投喂之前**
     * 统一生效，所以「无等待方」分支不因这一维产生新行为。
     */
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
        // 翻页计时起点：只在没在计时时置位（队列里的后续提交不覆盖本轮起点）。
        turnStartNs.compareAndSet(0L, System.nanoTime())
        if (mouseButton != null) sink.submitButtonClick(text, mouseButton, mesSkip) else sink.submit(text, mesSkip)
    }

    /**
     * 输入四层流水的最外两层读数（obs-inputseq 波的组长接线半边）。
     *
     * 分工要说清：`received` 与 `dropped(gate)` 是**宿主面**——执行期闸在 `QueueInputSink`
     * 之前就把文本丢了，压根没走到 `submit`，所以 sink 的流水里没有它们（core 面那条账
     * 只记 `enq` / `con` / `drop(void)` / `drop(parse)` / `tmo`）。两边合起来才是
     * 「Android 事件 → 宿主 → 入队 → 消费」四层的完整差分：
     * `received - dropped(gate)` 应当等于 core 面的 `lastSubmittedSeq`（同一会话内），
     * 对不上就说明中间又丢了一层，那才是真虫。
     *
     * 计数挂实例、`AtomicLong`（写侧 UI 线程、读侧 DevTools daemon 线程），不挂静态全局。
     */
    fun debugInputGateCounters(): Pair<Long, Long> =
        inputReceived.get() to inputGateDropped.get()

    /** core 面的输入流水快照（会话未装配 ⇒ null）；口径见 [QueueInputSink.trailReadout]。 */
    fun debugInputTrail(): QueueInputSink.InputTrailReadout? =
        (session?.inputSink as? QueueInputSink)?.trailReadout()

    /** 收到的提交条数（宿主面，含被执行期闸丢的那些）。 */
    private val inputReceived = java.util.concurrent.atomic.AtomicLong(0L)

    /** 被执行期闸（原版 Running 早退）丢弃的条数。 */
    private val inputGateDropped = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * 挂上装配好的会话，并把装载期的抢跑提交按原顺序补交（同一把 [pendingLock]，
     * 保证补交顺序与 UI 的提交顺序一致）。由装载线程在 `drive()` 之前调用。
     *
     * 装配期间 Activity 已销毁（[close] 先到）时不挂会话、也不补交：close() 当时看到的
     * [session] 还是 null，这里把新会话就地清掉，避免装载线程退出后留下半个会话。
     */
    private fun attachSession(fresh: GameSession): Boolean {
        val closedBeforeAttach = synchronized(pendingLock) {
            if (closed) {
                pendingBeforeSession.clear()
                true
            } else {
                session = fresh
                // 装载期收到的后台暂停意图在会话挂上的一刻补应用（见 [setBackgroundPaused]）。
                // 与 setBackgroundPaused 同锁，读到的意图一定是最新值：晚到的「前台恢复」
                // 不会让这里再把会话冻回去。锁序固定 pendingLock → pauseLock。
                synchronized(pauseLock) { fresh.setBackgroundPaused(backgroundPauseIntent) }
                // 换局/重挂会话：上一局残留的点击意图不得跨局补写（BEGIN 复位的语义）。
                keyInput.clearClickIntent()
                installMouse(fresh)
                // 执行期点击意图只服务轮询屏：脚本一落到 sink 的输入等待就作废
                // （原版 PressEnterKey 的 Running 早退 + 取值等待不许串屏，见 QueueInputSink.onWaitEntered）。
                fresh.inputSink.onWaitEntered = { keyInput.clearClickIntent() }
                while (pendingBeforeSession.isNotEmpty()) {
                    val queued = pendingBeforeSession.removeFirst()
                    // 补交时 VM 尚未 drive（必无等待方）⇒ 走 [deliver] 的执行期闸：
                    // 装载期的抢跑文本按原版 Initializing 语义丢弃，跳过意图保留。
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
        // 返回值把「已被关闭」透回调用方：旧形态返回 Unit，loadAndRun 察觉不到，
        // 会继续给**已关会话**挂 onTurnReady、installGraphics（CSV 总装白读一遍、
        // graphicsWiring 挂上后再无人 close —— shutdownForExit 已经跑过了）。
        return !closedBeforeAttach
    }

    /**
     * 把触摸现场接进 `MouseState`（`windowCreated` + `hitTest`）。
     *
     * 原版在鼠标事件里调 `MoveMouse` 写同一份现场，`MOUSEB/MOUSEX/MOUSEY` 从它读
     * （spec: `EmueraConsole.cs:2328-2331`、`:2358-2361`）。本移植的命中结构
     * （`DisplayList` / `ButtonLayer`）是单生产单消费的 UI 线程结构，VM 线程不能直接查，
     * 所以 `MouseState.hitTest` 只返回 [updatePointer] 在 UI 线程算好的 [pointerPointing]。
     */
    private fun installMouse(fresh: GameSession) {
        val mouse = mouseStateOf(fresh.store.aux)
        mouse.windowCreated = true
        mouse.hitTest = { _, _ -> pointerPointing }
        pointerPointing = null
    }

    /**
     * 触摸坐标 → `MouseState` 现场（客户区坐标、客户区尺寸、指向态）；由 UI 线程调用。
     *
     * 客户区 = Canvas 内容区（padding 以内），对应原版 `MainPicBox.ClientSize`；
     * 写进 `MouseState` 的是**设备像素**（命中测试 `view.buttonAtClient` 与越界判定都在这个
     * 坐标系里做），`MOUSEX` / `MOUSEY` 的读数由 `MouseState` 折回脚本 px —— 与
     * `CLIENTWIDTH()` 同一把尺（会话装配期注入折算，见 `GameSession` 的 3b-2 段）。
     * `MOUSEY` 的「底为 0、向上为负」位移在 `MouseState.mouseY()` 里做
     * （spec: `EmueraConsole.cs:2328-2331`、`:2358-2361`）。指向态在这里同步算好，
     * VM 线程的 `MOUSEB()` 只读缓存，不碰渲染结构。
     */
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

    /** 当前等待的输入请求（数值/字符串）；null = 没在等 `waitInput`。 */
    fun pendingRequest(): InputRequest? = session?.inputSink?.awaiting

    /**
     * 右侧栏的选项（引擎数据，UI 唯一来源）。
     *
     * 两条口径：
     * - **有等待方**（`QueueInputSink.isAwaiting`，含 WAIT/ENTER）：用
     *   `LineBufferConsoleSink.currentOptions()` 的等待快照 —— 非取值等待恒空，
     *   数值/字符串等待按类型过滤。这是原版 `SelectedString` 的可点口径。
     * - **无等待方但 VM 在跑**：QoL 地图/外出屏用 `AWAIT` + `GETKEYTRIGGERED` + `MOUSEB`
     *   轮询（`qol_MAP.ERB:1033` 的 `AWAIT_UI_INPUT`），从不走 `INPUT`，等待快照会停在
     *   上一个真正的 INPUT 页 —— 旧按钮一直挂着、本屏按钮反而没有。这里改用
     *   `publishedOptions()`（当前世代、**与画布同一帧**的全部按钮/HTML 按钮，行优先），
     *   让右侧栏跟屏走。点击这类按钮时 [submitInternal] 仍按原版「执行期早退」不落输入队列，
     *   只保留文本框 + 回车沿变（`GETTEXTBOX` / `GETKEYTRIGGERED(13)` 轮询可读）。
     *
     * 为什么是 `publishedOptions()` 而不是 `displayOptions()`（2026-10-27 真机收口）：
     * 后者扫的是**活表**，撞上脚本「`CLEARLINE` + 逐行重印」的中间态就读到残缺集合 ——
     * 动态地图调试屏每 200 ms 整屏重印一次、本栏每 250 ms 扫一次，两个周期拍频 ⇒ 玩家看到
     * 「按钮不断抖动、数量在 9/11/29/32 之间跳」。前者与 `revision` 同一发布点，只认整帧。
     *
     * 不读渲染层的可见行，也不做正则/扫尾窗口：两份数据都来自引擎按钮对象。
     */
    fun currentOptions(): List<ConsoleOption> {
        val s = session ?: return emptyList()
        return if (s.inputSink.isAwaiting) {
            s.console.currentOptions()
        } else {
            s.console.publishedOptions()
        }
    }

    /**
     * 画面命中这个值，**现在还算不算可点**（世代回查）。
     *
     * 两条链的口径本来就不同：`CanvasConsoleView.tap` 的命中表是**可见行**上的按钮，
     * 上一页的按钮只要没滚出屏幕就一直能命中；而原版的可点判据是
     * `Generation == lastButtonGeneration`（`EmueraConsole.cs:2439`、`:2456`）——
     * 旧页的按钮点了不算。真机 2026-10-25 的「跨屏串值把 CFLAG 打越界」就是这类错位的账。
     *
     * 这里只查**世代**（[LineBufferConsoleSink.publishedOptions]，不按等待类型过滤），
     * 不借用右侧栏那份「取值类型过滤」的口径：那一条是给候选列表用的，
     * 拿来当闸门会误杀「INT 等待页上的字符串按钮」这类本来能点的组合。
     *
     * 用帧口径而不是活表：玩家点的必然是**已经上屏**的那一帧上的按钮，拿重印中途的
     * 残缺集合去比对会把眼前真实存在的按钮判成旧世代（2026-10-27 与右侧栏同源收口）。
     *
     * 两处**放行**（宁可漏拦不误杀）：会话不在（提交本来就会被丢弃，不改变结果）、
     * 当前世代一个按钮都没有（没有可比对的事实源）。
     */
    fun isCurrentGeneration(value: String): Boolean {
        val s = session ?: return true
        val options = s.console.publishedOptions()
        if (options.isEmpty()) return true
        return options.any { it.submitValue == value }
    }

    /** 当前是否在等任意键（WAIT 系）。 */
    fun awaitingAnyKey(): Boolean = session?.inputSink?.awaitingAnyKey == true

    /**
     * 运行中会话的文本框（原版 `Window.TextBox`，`MainWindow.cs:210`）—— 输入框镜像的写入口。
     *
     * `GETTEXTBOX` 读的就是它（`FunctionMethodCreator.cs:8351-8354` 返回
     * `exm.Console.Window.TextBox.Text`），而原版那个 `RichTextBox` 同时就是输入框：
     * 玩家敲的字先落控件，脚本再在 `AWAIT` 轮询里读出来（`TITLE.ERB:168` 的
     * `GETTEXTBOX() != ""` 退出倒计时、`qol_MAP.ERB:1132` 的 AWAIT_UI 回车取值）。
     * 会话未装配 ⇒ null（此时没有脚本会读，UI 写入落空即可）。
     */
    fun textBoxHost(): TextBoxHost? = session?.builtinHost?.textBox

    /** 按钮可点性：与 `ButtonAcceptance` 的三态同源（见该类 KDoc）。 */
    fun acceptance(): ButtonAcceptance {
        val sink = session?.inputSink ?: return ButtonAcceptance.ANY
        if (sink.awaitingAnyKey) return ButtonAcceptance.ANY
        return when (sink.awaiting?.type) {
            InputRequest.InputType.INT -> ButtonAcceptance.INT
            InputRequest.InputType.STR -> ButtonAcceptance.STR
            null -> ButtonAcceptance.ANY
        }
    }

    /** VM 线程是否还活着（UI 的重绘循环据此决定何时停）。 */
    fun isRunning(): Boolean = thread?.isAlive == true

    /**
     * 内存压力下释放「重解必得同一张图」的像素，返回释放的 MiB（0 = 无可释放 / 会话未装配）。
     *
     * 调用点是 `MainActivity.onTrimMemory` 与回合边界的水位判据 —— 系统发这个回调的意思
     * 就是「再不收就来不及」，而这一批像素是**唯一能无条件收回来的一坨**：内容恒等于某份
     * 文件的解码结果，下次访问透明重解（判式与不变量见 `GraphicsBuffer.evictDeferred`）。
     *
     * **两处都要扫，缺一边就漏**：
     * - `SpritePool.evictDeferredPixels`：CSV 资源表的父图（负号段）；
     * - `GraphicsPool.evictDeferredPixels`：`GCREATEFROMFILE` 建的 G_ID 工作表（正号段）。
     *   后者 2026-10-30 之前根本不在回收面上，于是立绘工作表（单张 8000x4000 = 122 MiB）
     *   只要被 `GGETCOLOR` / `GSAVE` / `GDRAWG` 读过一次就永久常驻 Dalvik 堆。
     *
     * 线程：**两半的线程约束不同，别当成一件事**。
     * - 负号段（CSV 父图）VM 的画图语句只能按 gid 寻缓冲、够不到它，故「只被读」是结构性的，
     *   UI 线程（`onTrimMemory`）直接收是安全的。
     * - 正号段（G_ID 工作表）恰恰是脚本画写的**目标**：UI 线程把 `pixelData` 抽走的瞬间，
     *   VM 线程可能正在 `GDRAWG` 里 `pixels[y * width + x] = …` ⇒ 抽到空数组 / 尺寸被清零
     *   就是 `ArrayIndexOutOfBoundsException`，那正是本板要修的那类闪退。所以这一半只在
     *   VM 线程做；UI 线程来的请求只记一笔 [gfxSweepRequested]，由下一个回合边界真正执行
     *   （见 [releaseImagePixelsIfHeapHigh]）。代价是「晚一页」，换来不炸。
     *
     * 回收后位图缓存（`SpriteBitmapSource.sheetCache` / `bufferCache`）不动 —— 已建好的位图
     * 照常显示，下一次重建才走重解，所以这一刀不会在屏上闪出空帧。
     */
    fun releaseResourceImagePixels(): Long {
        val aux = session?.store?.aux ?: return 0L
        // 第一发（负号段 CSV 父图）：传**复用面** + 闸外套壳（oomsrc2 波，接 oomsrc 遗留的
        // 调用点之一）。判式本身是「堆 ≥ 80% / 系统压力 / 存档求援 / UI 的 onTrimMemory」，
        // 全是贴顶相位，而旧形状每次在闸内现搭一块去重面（候选表要长到资源表体量）——
        // 真机报告 6c08e8bb-0 那笔「回收闸自己 `HashMap.resize` 把 <1% free 的堆打爆」在同族位置。
        // 抛掷只 logcat、不穿调用线程：回收没做成只是「这一发没收到」，读数少一行。
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
            // 不是「不做」，是「换个线程做」：回合边界那一发会把它连同水位判据一起执行。
            gfxSweepRequested = true
        }
        val mib = pixels / (1024L * 1024L)
        if (pixels > 0) Log.i(TAG, "trim.release freed=${mib}MiB（像素 $pixels 条，退回未解码态）")
        return mib
    }

    /** 正号段（G_ID 工作表）的整表回收；**只许 VM 线程调**（理由见 [releaseResourceImagePixels]）。 */
    private fun releaseGfxDeferredPixels(aux: com.eratw.port.core.host.RuntimeAux): Long =
        aux.peek<GraphicsPool>(GRAPHICS_SLOT)?.evictDeferredPixels() ?: 0L

    /** 当前线程就是跑 VM 的那条线程（`eratw-vm`）。 */
    private fun onVmThread(): Boolean = Thread.currentThread() === thread

    /**
     * UI 线程（`onTrimMemory`）转来的「收正号段」请求；`@Volatile` 因为写侧在 UI、读侧在 VM。
     * 置位后由下一个回合边界执行并清掉 —— 丢一次请求最多是多等一页，不会丢正确性。
     */
    @Volatile
    private var gfxSweepRequested = false

    /**
     * 负号段预算闸的**可复用记账面**（oomsrc 波，2026-11-05）：回合边界那一发
     * [SpritePool.evictDeferredPixelsWithinSharedBudget] 在「去重和 > 预算」的形状下要
     * 现算驻留并收集候选（判据见 SpritePool 的 KDoc），旧闸每发都现搭三件容器，真机
     * 0.2.39+15 报告 `6c08e8bb` 簇里「回收闸自己 `HashMap.resize` 把贴顶堆打爆」就是
     * 这笔（2026-11-04 快路径治了『负号段本就空』一支；本字段治『非空且超预算』一支，
     * 报告 `417844ea-0` 的 vivo 现场：负号段驻留 129 MiB > 共用档剩余）。
     *
     * 线程：本面只被 [releaseImagePixelsIfHeapHigh] 用，而该函数只在 **VM 线程**的回合
     * 边界被调（页尾 onTurnReady 一路），面本身的遍历又收在 `SpritePool` 池监视器里 ⇒
     * 单线程持有 + 池锁可见性，不需要 `@Volatile`。生命周期：随 GameHost 实例回收，
     * 表容量爬到历史水位后不再增长（稳态零分配），不新增静态可变全局（铁律 4）。
     */
    private val csvBudgetEvictScratch = SpriteEvictScratch()

    /**
     * 负号段**全表档**的可复用记账面（oomsrc2 波，2026-11-05；oomsrc 交付原文点名的两处遗留
     * 调用点之一）：[releaseResourceImagePixels] 里那一发 `SpritePool.evictDeferredPixels`
     * 走的是「不看预算、一次退干净」的全表遍历，闸内按**对象身份**去重（一张 CSV 父图常被切成
     * 几十个 Sprite 复用），旧形状每发现搭一块面 ⇒ 贴顶堆上这笔就是压垮点的同族形状。
     *
     * ## 为什么是一块**独立**的面，不并入 [csvBudgetEvictScratch]
     * 相位与线程不同：[csvBudgetEvictScratch] 的 KDoc 把它钉在「只在 VM 线程的回合边界」，
     * 而本面所在的 [releaseResourceImagePixels] 还有一条 **UI 线程**入口
     * （`MainActivity.onTrimMemory` 那一发，以及本文件 [releaseUnusedGraphics] 开头那条
     * 「正号段只许 VM 线程」的约束同样只管正号段，负号段这一半本来就允许 UI 线程直接收）。
     * 共用一块在**当前形状下**并不会踩坏 —— 两发的遍历本体都整段收在
     * `SpritePool` 的监视器里（core/host/GraphicsBuffer.kt:2019 与 :2109 的 `synchronized(this)`），
     * 监视器同时给出互斥与可见性，且监视器内的 `evictDeferred()`（:458）/ `dispose()`（:590）
     * 不回宿主的任何回收口 ⇒ 不存在「同一条线程嵌套两发互相 clear」的重入。分开是因为**没必要**
     * 把两个相位的表水位绑在一起：[SpriteEvictScratch] 的候选表只增不缩（clear 只填 null），
     * 让 UI trim 那发把 VM 预算档的表一起顶大，换来的是常驻引用数组，不划算。
     *
     * 生命周期：随 GameHost 实例回收，不新增静态可变全局（铁律 4）。稳态零分配 —— 容量爬到
     * 历史水位后不再增长（面本体的分配口径见 `SpriteEvictScratch` 的 KDoc）。
     */
    private val resourceImageEvictScratch = SpriteEvictScratch()

    /**
     * **合成图档**的可复用记账面（oomsrc2 波；oomsrc 交付原文点名的另一处遗留调用点）：
     * [evictCompositedSpritesIfOverBudget] 那一发 `SpritePool.evictCompositedSpritesWithinBudget`
     * 在闸内要装两张表 —— 护栏面（`exclude` ∪ 资源表全部缓冲，逐条 addGuard）与候选面
     * （按登记序选出的要 dispose 的缓冲），旧形状每发现搭，而**这一档的触发判式本身就写着堆贴顶**
     * （[RunTrail.shouldEvictCompositedSprites]：驻留超预算且 Java 堆 ≥ 80%）⇒ 与报告
     * `6c08e8bb` 簇的病灶同族，属 oomsrc 回执里点名的「同族风险点」。
     *
     * ## 并发与重入判据（一块还是几块，按这条判）
     * 本面**只可能**在 VM 线程被摸：唯一的调用链是 [releaseUnusedGraphics]（开头一道
     * `onVmThread()` 硬闸，UI 线程来的请求只置位 [strongReclaimRequested]、由页尾/页中在 VM
     * 线程兑现）⇒ [evictCompositedSpritesIfOverBudget]。与 [resourceImageEvictScratch]
     * （UI/VM 两条线程都会走）分家，于是「两块面各自只服务一个出入口」，判据不必依赖调用序；
     * 页尾那一发里两档确实是**先后**关系（`releaseImagePixelsIfHeapHigh` 先走
     * [releaseResourceImagePixels]、再走 [releaseUnusedGraphics]），不是嵌套。
     * 遍历本体同样整段收在 `SpritePool` 监视器内（core/host/GraphicsBuffer.kt:2282），
     * 监视器给出互斥与可见性。形状：一块长生命周期面，各处只搬运不分配。
     */
    private val compositedEvictScratch = SpriteEvictScratch()

    /**
     * **贴顶名单**的复用护栏面（refbuffers 波，2026-11-05；收 oomsrc2 交来的最后一发）：
     * [releaseUnusedGraphics] 每一发都要一张「不许动」的名单（正在被 Sprite / CBG 指着缓冲），
     * 旧形状在**堆 ≥80% / ≥92% 的贴顶相位**现搭两张表 ——
     * [SpritePool.referencedBuffers] 里那个按 `sprites.size*2 + resourceSprites.size*2 + 1`
     * 现开的 `HashSet`（三万量级 Sprite ⇒ 一张 MB 级引用数组 + 几万个 32–40 B 的
     * `HashMap.Node`）加上 [cbgProtectedGuardsInto] 那一个。自救路径自己现搭哈希表，
     * 与真机报告 `6c08e8bb` 簇的病灶同族，只是这一发的量级更大。
     * 本面装的是**并集**（CBG ∪ Sprite 引用），只喂一个出入口：[GraphicsPool.releaseUnused]
     * 的护栏版（`core/host/GraphicsBuffer.kt` 的 `releaseUnused(guards, minPixels, budgetBytes)`）。
     *
     * ## 为什么是**两块**新面，不是一块（线程 / 重入 / 档位判据）
     * 同一发里有两个消费点，要的**不是同一个集合**：
     * - `releaseUnused` 要 CBG ∪ Sprite（它没有摘名字的能力，摘了就是屏上缺一块）；
     * - [evictCompositedSpritesIfOverBudget] 只要 CBG —— Sprite 那一半正是它的候选集，
     *   传并集等于让它当场看不见任何候选（`n` 恒 0，这一档等于没修；原文见本函数下方注释
     *   与 `SpritePool.evictCompositedSpritesWithinBudget` 的 KDoc）。
     * 护栏面只有「当前内容」这一个状态，共用一块就得在同一发里 `clear` 两次、重灌两次，
     * 而两次重灌之间任何一处抛掷都会让下一档看到**半张名单**（比旧形状更危险：旧形状
     * 拿不到完整名单就整发不做）。分成两块 ⇒ 每块「装一次、被读一次」，判据不依赖调用序。
     *
     * ## 为什么与 [compositedEvictScratch] 不共用
     * 那一块是合成图档的**记账面**（护栏 + 候选两用的 scratch），而本波传进去的名单是它的
     * **输入**：`evictCompositedSpritesWithinBudget` 进闸第一件事是 `scratch.clear()`，
     * 同一块面对象等于先把宿主名单清空再抄自己 ⇒ core 侧直接钉了别名闸（`require`），
     * 名单必须住在另一块面上。
     *
     * ## 线程与生命周期
     * 只可能在 **VM 线程**被摸：唯一链路是 [releaseUnusedGraphics]（开头一道 `onVmThread()`
     * 硬闸；UI 线程的请求只置位 [strongReclaimRequested]，由页尾/页中在 VM 线程兑现）。
     * 装载名单那一半收在 `SpritePool` 监视器内（`addReferencedGuards`），读那一半收在
     * `GraphicsPool` 监视器内，但**同步面不靠这两把锁** —— 面是本线程独占的搬运对象，
     * 锁只保证被扫的表结构一致。随 GameHost 实例回收，不新增静态可变全局（铁律 4）；
     * 容量爬到历史水位后不再增长（稳态零分配，见 `SpriteEvictScratch` 的 KDoc）。
     */
    private val releaseGuardScratch = SpriteEvictScratch()

    /**
     * **CBG 图层名单**的复用护栏面（refbuffers 波）：[cbgProtectedGuardsInto] 的装载目标，
     * 两个消费点共用这一份（图层表是 VM 线程边写的普通 ArrayList，取一次就够，口径同
     * oomsrc2 留下的那句「CBG 图层名单只取一次」）：
     * 1. 经 [SpriteEvictScratch.copyGuardsFrom] 并进 [releaseGuardScratch]，喂 `releaseUnused`；
     * 2. 原样作为 `protectedGuards` 喂 [evictCompositedSpritesIfOverBudget]（那一档只要 CBG）。
     *
     * 规模：CBG 图层数（控制台背景 + 按钮层，几十到几百），不是 Sprite 那种三万量级 ——
     * 单独成块的价值不在省字节，而在让**整发**一处都不新建（判据见 [releaseGuardScratch]）。
     * 线程与生命周期同 [releaseGuardScratch]：VM 线程独占、随实例回收、稳态零分配。
     */
    private val cbgGuardScratch = SpriteEvictScratch()

    /**
     * **报告/取证读数**的可复用身份去重面（reportres 波，2026-11-05；收 lane refbuffers
     * 点名未动的那一处同族点）：[graphicsPoolReportLine] 里那一发
     * [SpritePool.resourceResidentPixelBytes] 的旧形状按 `resourceSprites.size * 2 + 1`
     * **每次调用现搭**一枚 `HashSet` —— 表数组按登记条数长、节点按去重后的父图张数长。
     * 桌面装置实测单发字节：134 条登记 = 6,448 B、2,795 条 = 122,320 B、38,861 条 = 610,672 B
     * （`ResourceResidentSnapScratchTest` 的 `量·资源表规模曲线`；4.981 EAGLOV3 真语料生产路
     * 的登记条数见同文件 `量·真语料装载`）。这一发所在的两条链都是贴顶相位：
     * ① 崩溃报告正文（`ErrorReportCollector.memoryLines` 里那一处 `host?.graphicsPoolReportLine()`，
     *    以及本文件启动失败报告那一发 `trace.summaryLines().take(24) + listOfNotNull(…)`），
     * ② [censusTrailLine] → [com.eratw.port.core.diag.LiveSetCensus.census] 的本挡 Java 堆 ≥
     *    [HeapCensusPin.NEAR_TOP_PERCENT]% 分项钉发（VM 线程、[HeapCensusPin.PIN_COOLDOWN_MS] ms
     *    冷却）。自救/取证相位自己现搭
     * 0.1–0.6 MB 容器，与真机报告 `6c08e8bb` 簇的抛点同族，只是这一发在**报告侧**而不是回收侧。
     *
     * ## 为什么是**第六块**面，不与既有五块并
     * 「能复用就别新开面」在这一发上不成立，三条理由各自独立成立：
     * 1. **`clear` 语义会蒸发别人的名单**。[releaseGuardScratch] / [cbgGuardScratch] 的内容是
     *    **回收那一发的「不许动」名单**，要一直活到 [GraphicsPool.releaseUnused] 把名单读完；
     *    [compositedEvictScratch] / [csvBudgetEvictScratch] / [resourceImageEvictScratch] 的内容
     *    是回收那一发的**候选集**。而本读数的闸进门第一件事是 `face.clear()`（口径同其余几把闸
     *    ⇒ 「这一次的全部或 nothing」）。并块的结果是：回收事务进行到一半时被报告发当场清空
     *    ⇒ 名单蒸发、CBG 图层变成可逐出、屏上直接缺一块 —— 比 refbuffers 波用 `require` 钉住的
     *    那个别名形状更坏（那条是同一发里两面互抄，这条是**跨发**互相清）。
     * 2. **线程归属不同**。既有五块的 KDoc 都把「哪条线程能摸」钉死（VM 回合边界独占，或
     *    VM + UI trim 两条明确的回收入口）；本块服务的报告链跑在**不固定的线程**上（崩溃线程、
     *    UI 侧启动失败屏、将来的 VM 分项钉发），它的安全不是靠线程独占，而是靠
     *    [SpritePool.resourceResidentPixelBytes] 外面那层 `synchronized(this)`（池监视器给出
     *    互斥与 happens-before，原文见 core 侧 [SpritePool] 面臂的「线程口径」一节）。把这种
     *    线程归属并进任何一块「线程独占」的面，都会把那一块 KDoc 的判据顶掉。
     * 3. **水位不同形**。回收路的面要长到 sprite 全量（三万级 ⇒ 候选表 + 序数组两根引用数组），
     *    本块只需要长到**去重后的父图张数**（真语料 132–2,777 量级）；并块等于让报告发把回收
     *    路的候选表水位一起顶大，而 [SpriteEvictScratch] 的表只增不缩。
     *
     * 消费点三处： [graphicsPoolReportLine] 直传，以及 [censusTrailLine] / [memoryCensusReportLine]
     * 两处经 [com.eratw.port.core.diag.LiveSetCensus.census] 的 `residentFace` 参数透传
     * （census3 波接线）。三处都在同一把 `SpritePool` 池锁内读，本块 KDoc 第 2 条的线程口径对它们
     * 同时成立；两处 census 恰好都跑在「堆贴顶/崩溃报告」这种最不能分配的相位，所以它们才是这块面
     * 的主要受益点（缺省 null 时那一支在锁内现搭 `HashSet`）。
     *
     * 生命周期：随 GameHost 实例回收，不新增静态可变全局（铁律 4）；容量爬到历史水位后不再增长。
     */
    private val reportResidentScratch = SpriteEvictScratch()

    /**
     * UI 线程（`onTrimMemory` 的 `RUNNING_CRITICAL` 档）转来的**强档回收**请求。
     *
     * 与 [gfxSweepRequested] 的区别是「收多少」：那一条只收「重解无损」的延迟图，这一条
     * 连**画过的**合成缓冲一起收，而且**跳过水位与冷却**。为什么需要：水位判据（92% / 95%）
     * 只认 Java 堆，而 2026-10-31 收件箱里有 11 份死法是 Java 堆 10%~79%、系统可用内存
     * 已被别的应用榨干 —— 堆根本不贴顶，水位判据一次都不会触发，可 lmkd 照样收了进程。
     * 这一条把「系统亲口说不收就杀」当作比我们自己那个百分比更强的信号。
     *
     * 只在前台可见的那一档置位（见 `MainActivity.onTrimMemory`）：后台档位置位没有意义 ——
     * 后台没有回合边界可以兑现，请求会挂到回前台才执行，正好在玩家看得见的时刻丢图。
     * 误置的代价是脚本画过的 gid 提前变成「未创建」，回前台整屏缺图。
     */
    @Volatile
    private var strongReclaimRequested = false

    /**
     * 请求一次强档回收（任意线程调，无副作用；真正执行在下一个回合边界、VM 线程上）。
     *
     * 为什么必须转线程：要收的正号段缓冲只有 VM 线程能安全 dispose（见 [releaseUnusedGraphics]）。
     * 代价是「晚一页」—— 若玩家此刻停在输入等待上，要等他下一次操作；但这一档本来就是在
     * 系统已经发过警告之后的兜底，早一页晚一页都不改变语义。
     */
    fun requestStrongReclaim() {
        strongReclaimRequested = true
    }

    /**
     * 内存压力下**释放掉**没人引用的 G_ID 缓冲（本板欠账「G_ID 池只增不减」的收口）。
     *
     * 语料侧的分配口径见 `GraphicsPool.releaseUnused` 的 KDoc：`@GET_EMPTY_G` 靠
     * `GCREATED==0` 复用 gid，而唯一会 dispose 的 `@RM_全部リリース` 被作者注释掉了。
     * 这一刀就是那个函数的**内存压力版**，并额外过滤掉「正被 Sprite / CBG 引用」的缓冲。
     *
     * 只在 [RunTrail.shouldReleaseUnusedGraphics] 那档水位（92%）触发：丢的是脚本画过的
     * 内容，取舍写在该判式的 KDoc 里。
     *
     * @param usePixelBudget true = 只收到 `GraphicsPool.pixelBudgetBytes()` 以内就停手
     *   （LRU 出队、跳过 [releaseGuardScratch] 里那张护栏名单），用于 80%（回合边界）与 92%（页中/页尾）水位档；
     *   false = 92% 水位那一档的原口径（够大的一律 dispose，不看预算）
     * @param minPixels 入队门槛；预算档用更小的门槛（见 [BUDGET_REAP_MIN_PIXELS]）
     * @return 释放的读数快照；`bytes` 已并入第三档合成图逐出的释放量（[evictCompositedSpritesIfOverBudget]），
     *   `buffers` 仍只计前两档 dispose 的个数；会话未装配 ⇒ (0, 0)
     */
    private fun releaseUnusedGraphics(
        usePixelBudget: Boolean = false,
        minPixels: Int = MIN_UNUSED_RELEASE_PIXELS,
    ): GraphicsPool.ReleaseResult {
        // `dispose()` 会把尺寸清零，而 VM 线程正在 GDRAWG 里按旧尺寸索引同一张缓冲 ⇒
        // AIOOBE。这个函数只在回合边界（VM 线程）被调，这里再钉一道：将来若有人从
        // onTrimMemory 顺手调它，宁可不做也不炸。
        if (!onVmThread()) return GraphicsPool.ReleaseResult(0, 0L)
        val aux = session?.store?.aux ?: return GraphicsPool.ReleaseResult(0, 0L)
        val gfx = aux.peek<GraphicsPool>(GRAPHICS_SLOT) ?: return GraphicsPool.ReleaseResult(0, 0L)
        // 白名单必须**完整**：漏掉正在画的那张就是屏上缺一块。拿不到完整名单时宁可不做 ——
        // CBG 图层表是普通 ArrayList（VM 线程边写），并发遍历会抛，故整体 try 住。
        // CBG 图层名单只取一次，两个消费点共用（图层表是 VM 线程边写的普通 ArrayList，
        // 并发遍历会抛 ⇒ 取不到就整发不做，口径同下面那道「宁可不做也不炸」的闸）。
        // refbuffers 波（2026-11-05）：名单不再物化成 `Set`，改灌进两块长生命周期护栏面
        // （判据与「为什么是两块」见 [releaseGuardScratch] 的 KDoc）。分两步灌是有原因的：
        // CBG 那一半被两个消费点共用（`releaseUnused` 要并集、合成图档只要 CBG），先单独灌一次、
        // 再整体并进并集面 ⇒ 名单成员与旧形状里「`referencedBuffers()` ∪ cbgProtected」逐字同集。
        // 任何一步抛掷 ⇒ 半张名单**绝不进闸**（当场返回 0，口径同上面那句「宁可不做也不炸」）。
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
                "trim.unused buffers=${result.buffers} freed=${result.bytes / (1024L * 1024L)}MiB" +
                    "（保留 $guardedCount 个在引用中的缓冲）",
            )
        }
        // 第三档（fix-hiresmem）：上一刀只动「没有具名 Sprite 指着」的缓冲，而 2026-11-04 收件箱
        // 里贴顶那几台的驻留（正号段 250–309MiB，预算 128–170MiB）恰恰全被语料自己的
        // `SPRITECREATE OUT_STAND, GID` 钉住 ⇒ `turn.trim.budget` 一行都不肯出现。
        // 这一发只在**收完仍超预算**时动手，判据、候选集与逐出本体都在
        // [SpritePool.evictCompositedSpritesWithinBudget]（自愈链与风险面的原文在那里）。
        // 挂在 dispose 那一刀的同一发之后 = 四个触发点（页尾预算档 / 页尾 92% 强档 / 页中检查点 /
        // onTrimMemory 转来的请求）自动全覆盖，不新增水位常量、不新增冷却计时段。
        //
        // 名单**只传 CBG 那一半**：上面那张并集名单的全部意义就是「被 Sprite 指着别动」，
        // 原样传进来会让这一档当场看不见任何候选（n 恒 0，等于没修）。CBG 图层是控制台背景，
        // 语料不会自己重算，必须继续护住 —— 名单在上面的闸里已经取到了才走到这里。
        // 走护栏面（refbuffers 波）：传 [cbgGuardScratch] 而不是 [releaseGuardScratch]，
        // 判据就是上面那句「只传 CBG 那一半」；闸内的别名 `require` 保证它不会与
        // [compositedEvictScratch] 是同一块面（两面各司其职，KDoc 有账）。
        //
        // 第三档的释放量必须并进返回值（2026-11-07 报告 `5783b90` 的存档求援修复）。
        // 旧形状只把前两档的字节数交回去，可这两档对「被具名 Sprite 钉住的降级缓冲」一律
        // 看不见；于是第三档真腾出了空间、`FileSystemOps` 却读到 0 ⇒ 打出
        // 「已回收=0KiB，不重试」并放弃那次存档，连 GC 提示都不发。
        // 报告里 178 张降级缓冲是否全被具名 Sprite 钉住，报告本身判定不了（TODO(verify)）；
        // 但「只剩第三档能收」这个形态确实存在，交 0 就等于把唯一的自救路径判死。
        val thirdTierBytes = evictCompositedSpritesIfOverBudget(aux, gfx, cbgGuardScratch)
        return if (thirdTierBytes > 0L) {
            GraphicsPool.ReleaseResult(result.buffers, result.bytes + thirdTierBytes)
        } else {
            result
        }
    }

    /**
     * CBG（控制台背景图）图层正在引用的缓冲 —— 绘制层唯一「摘了不会自己长回来」的持有者。
     *
     * 从 [releaseUnusedGraphics] 的名单里抽出来单独成口，是因为现在有两个消费点要的**不是
     * 同一份**：`releaseUnused` 要 Sprite ∪ CBG（它没有摘名字的能力），而合成图逐出档只要
     * CBG（Sprite 那一半正是它的候选集）。图层表是普通 ArrayList（VM 线程边写），并发遍历
     * 会抛，所以调用方各自 `runCatching`。
     *
     * refbuffers 波（2026-11-05）的出入口改动：目标从「现搭一张 `HashSet`」换成**调用方的
     * 复用护栏面**（[cbgGuardScratch]），成员判定逐字不变 —— 每个图层取 `gid`、`gid` 非空才
     * `gfx.peek`、peek 到才算成员（旧的 `?.let` 链就是这个意思，这里改成 `?: continue` 同判；
     * 少一层嵌套顺带把 detekt 的 `NestedBlockDepth` 存量项留在旧签名上，不新增违例）。
     * 本口**先 `target.clear()`** 再灌 ⇒ 调用方拿到的永远是「这一次的全部或 nothing」：
     * 中途抛掷时调用方直接不做这一发，半张名单不会进闸（口径同上段那句「并发遍历会抛」）。
     */
    private fun cbgProtectedGuardsInto(
        aux: com.eratw.port.core.host.RuntimeAux,
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

    /**
     * [releaseUnusedGraphics] 的最后一发：驻留**仍**在像素预算之上时，按登记序摘掉合成图
     * 的具名 Sprite 并 dispose 其缓冲。判据与「为什么这样做不坏」全部在
     * [SpritePool.evictCompositedSpritesWithinBudget] 的 KDoc，这里只负责接线与钉行。
     *
     * 钉行是这一档唯一的取证面：`trim.sprites n=… freed=…MiB used=…/…`，端点侧按前缀分桶
     * （口径同 `turn.trim.budget`）。`n=0` 时**不钉行**、只留一条 logcat，免得把 64 行滚动
     * 窗口灌满空转读数（同 `capped.buffers > 0` 那一条既有纪律）。
     *
     * [keep] 是宿主的**复用护栏面**（refbuffers 波，实参恒为 [cbgGuardScratch]：只要 CBG
     * 那一半，理由在上面那句「名单只传 CBG 那一半」）；闸内把它抄进 [compositedEvictScratch]
     * 的护栏表，两臂读数与旧 `Set` 形状逐字一致（等价性证据在 core 侧
     * `SpritePool.evictCompositedSpritesWithinBudget` 的护栏版 KDoc）。
     *
     * @return 本发真正释放的 Java 堆字节数（= `pixels × 4`，降级态按 PNG 载荷入账的
     *   `retainedBytes` 计）；没动手或一张都没逐出时为 0。调用方
     *   [releaseUnusedGraphics] 必须把它并进返回值 —— 这是存档求援唯一的自救口径。
     */
    private fun evictCompositedSpritesIfOverBudget(
        aux: com.eratw.port.core.host.RuntimeAux,
        gfx: GraphicsPool,
        keep: SpriteEvictScratch,
    ): Long {
        val sprites = aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE) ?: return 0L
        val budget = gfx.pixelBudgetBytes()
        val resident = gfx.residentBytes()
        val runtime = Runtime.getRuntime()
        val usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMiB = runtime.maxMemory() / (1024L * 1024L)
        // 判式本体在 RunTrail（与上面两档同一个家），这里只把读数喂进去：
        // 驻留已在预算内、或堆没贴顶 ⇒ 一张都不许动。
        if (!RunTrail.shouldEvictCompositedSprites(usedMiB, maxMiB, resident, budget)) return 0L
        // 第二发（oomsrc2 波，接 oomsrc 遗留的调用点之二）：传**复用面** + 闸外套壳。
        // 这一档的触发判式本身就是「堆 ≥ 80% 且驻留超预算」，旧形状在闸内现搭护栏面
        // （exclude ∪ 整张资源表）与候选面两件事 ⇒ 与报告 6c08e8bb 簇的「回收自救路径
        // 自己把堆打爆」同族。抛掷只 logcat、不钉行、不穿 VM 线程：逐出没做成只是这一发没收到。
        val evicted = runCatching {
            sprites.evictCompositedSpritesWithinBudget(resident, budget, keep, scratch = compositedEvictScratch)
        }.getOrElse {
            Log.w(TAG, "trim.sprites 逐出闸自身抛掷，跳过这一发：${it.javaClass.name}")
            return 0L
        }
        val budgetLabel = if (budget == Long.MAX_VALUE) "不可知" else "${budget / (1024L * 1024L)}MiB"
        if (evicted.sprites == 0) {
            Log.i(
                TAG,
                "trim.sprites 无可逐出（驻留=${resident / (1024L * 1024L)}MiB 预算=$budgetLabel" +
                    " 保留 ${keep.guardCount()} 个在引用中的缓冲）",
            )
            return 0L
        }
        val freedBytes = evicted.pixels * 4L
        val freedMiB = freedBytes / (1024L * 1024L)
        Log.i(TAG, "trim.sprites names=${evicted.sprites} freed=${freedMiB}MiB used=${usedMiB}MiB/$maxMiB")
        RunWatch.forceSample(
            view.context,
            "trim.sprites n=${evicted.sprites} freed=${freedMiB}MiB" +
                " used=${usedMiB}MiB/$maxMiB resident=${resident / (1024L * 1024L)}MiB budget=$budgetLabel",
            heartbeat,
        )
        return freedBytes
    }

    /**
     * 兑现 UI 线程（`onTrimMemory`）转来的两发请求：`gfxSweepRequested`（只收「重解无损」的
     * 正号段工作表）与 `strongReclaimRequested`（连**画过的**合成缓冲一起收、不看水位不看冷却）。
     *
     * ## 为什么要有这个函数（2026-11-02 端点报告，heap-budget 车道）
     *
     * 旧形状里这两发**只在回合边界兑现**（页尾 = 进入等待点）。而报告里那一发
     * `trim=runningCritical freed=0MiB` + 随后 ANR 的现场是 `used=511/512`、
     * `turn 1164/1210/2930ms`：**单页一秒以上、GC 抖动把主线程冻住**的形态下，
     * 页尾虽终归会来，可强档那一发（唯一动得了「画过的」167 MiB 的那一刀）要多等整整一页；
     * 而 `freed=0` 之所以是 0，是因为 UI 线程那一发只收负号段（线程约束见
     * [releaseResourceImagePixels]），正号段与强档全押在页尾 —— 盘上于是只看得见那个 0。
     * 本函数把同一个兑现动作多挂一个触发点：**页中的语句级检查点**（[midPageCheckpoint]，
     * 本来就在 VM 线程、两条语句之间），最坏 250 ms 内兑现，而不是等一页。
     *
     * ## 线程
     *
     * **只许 VM 线程调**：两个兑现体（[releaseGfxDeferredPixels] / [releaseUnusedGraphics]）
     * 抽的是 VM 线程正在 `GDRAWG` 写的 `pixelData`，UI 线程动它就是 `ArrayIndexOutOfBoundsException`
     * （约束原文见 [releaseResourceImagePixels] 与 [releaseUnusedGraphics] 的 KDoc）。
     * [releaseUnusedGraphics] 内部另有一道 `onVmThread()` 硬闸，双保险。
     *
     * @param samplePrefix 钉进行轨迹的前缀：页尾 `sys.strong`（逐字保留旧文案，取证脚本按它筛）、
     *                     页中 `midpage.strong` / `midpage.trim.deferred`
     * @param whereLabel 只进 logcat 的位置说明
     * @return true = 兑现过**强档**那一发（两档已一次做全，调用方据此跳过本轮的水位判据，
     *         免得在同一时刻白做一遍）；false = 只兑现过轻的那一发或本来就没有请求
     */
    private fun drainReclaimRequests(samplePrefix: String, whereLabel: String): Boolean {
        val strong = strongReclaimRequested
        val sweep = gfxSweepRequested
        if (!strong && !sweep) return false
        strongReclaimRequested = false
        if (strong) {
            // 强档自己就会把正号段一起收掉（releaseResourceImagePixels 在 VM 线程上两半都做），
            // 所以这里不再单独走轻的那一发 —— 少一遍 O(n) 扫描。
            gfxSweepRequested = false
            val freedPixels = releaseResourceImagePixels()
            val released = releaseUnusedGraphics()
            val now = SystemClock.elapsedRealtime()
            lastImageReleaseAtMs = now
            lastUnusedReleaseAtMs = now
            // 读数收紧（本车道的另一半交付）：旧行只有 freed/n/freedBuf，判不出「收完到底离
            // 贴顶还差多少」，也读不出 native 侧有没有跟着动。补 Java 水位 + native 账面。
            val runtime = Runtime.getRuntime()
            val usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / BYTES_PER_MIB
            val maxMiB = runtime.maxMemory() / BYTES_PER_MIB
            val nativeMiB = runCatching { android.os.Debug.getNativeHeapAllocatedSize() / BYTES_PER_MIB }
                .getOrDefault(-1L)
            RunWatch.forceSample(
                view.context,
                "$samplePrefix.strong freed=${freedPixels}MiB" +
                    " n=${released.buffers} freedBuf=${released.bytes / BYTES_PER_MIB}MiB" +
                    " used=${usedMiB}MiB/$maxMiB nativeHeap=${nativeMiB}MiB",
            )
            return true
        }
        val aux = session?.store?.aux
        val freed = if (aux != null) releaseGfxDeferredPixels(aux) else 0L
        if (freed > 0L) {
            Log.i(TAG, "trim.release.deferred freed=${freed / BYTES_PER_MIB}MiB（${whereLabel}兑现）")
            RunWatch.forceSample(view.context, "$samplePrefix.deferred freed=${freed / BYTES_PER_MIB}MiB")
        }
        return false
    }

    /**
     * 回合边界的水位判据：堆贴顶就自己收一次（VM 线程调）。
     *
     * 为什么不能只等 [android.content.ComponentCallbacks2] 的 `onTrimMemory`：vivo / OPPO
     * 这类 ROM 在真把进程杀掉之前不一定发这个回调，而玩家报的正是「玩了一会儿就没了、
     * 什么报错都没有」。判式与冷却见 [RunTrail.shouldReleaseImagePixels] /
     * [RunTrail.shouldReleaseUnusedGraphics]（两档：先收「重解无损」的，还贴顶才收「画过的」）。
     */
    private fun releaseImagePixelsIfHeapHigh() {
        // 先兑现 UI 线程（onTrimMemory）转来的请求：正号段只能在 VM 线程收（见
        // releaseResourceImagePixels 的线程约束）。这一发**不看水位** —— 请求本身就代表
        // 「系统刚发过内存压力」，再拿水位筛一遍等于把玩家的救命信号丢了。
        // 同一个兑现动作还有第二个触发点：页中的语句级检查点（[midPageCheckpoint]）。
        if (drainReclaimRequests("sys", "回合边界")) return
        // 负号段预算档（fix-spritebudget 车道）：每回合边界一发，**不看水位** —— 水位档要
        // used 顶到堆的 80%（512 MiB 挡 = 410 MiB）才动手，而 CSV 父图的像素一旦被 GDRAWSPRITE
        // 合成读进 Java 堆，此前**没有自己的上限**（上一路回执缺口①：负号段不进 GraphicsPool
        // 驻留记账，正号段的预算闸结构上看不见它们）。预算是两段**共用一档**：总预算
        // （maxMemory/4）先给正号段驻留，剩多少负号段持多少；算术与不挤爆的论据见
        // `SpritePool.evictDeferredPixelsWithinSharedBudget` 的 KDoc。
        // 成本 = 锁内一次 O(n) 快照（n = CSV 父图 sprite 数，真语料读数见 SpritePoolBudgetProbeTest），
        // 一发/回合，不在绘制与语句热路径上。
        run {
            val budgetAux = session?.store?.aux ?: return@run
            val gfxResident = budgetAux.peek<GraphicsPool>(GRAPHICS_SLOT)?.residentBytes() ?: 0L
            // 自救闸不许把自己炸掉（oomsrc 波）：判式与算术都在池内，闸外这一层薄壳兜住
            // 一切逃逸（含 OutOfMemoryError —— 旧形状里贴顶堆上的闸内分配会一路穿到
            // waitInput，正是 6c08e8bb 簇的抛点形状）。回收没做成只是「这一页没收到」，
            // 进程照常往下跑；读数只少一行。
            val freedPixels = runCatching {
                budgetAux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)
                    ?.evictDeferredPixelsWithinSharedBudget(gfxResident, scratch = csvBudgetEvictScratch)
                    ?: 0L
            }.getOrElse {
                Log.w(TAG, "budget.csv 回收闸自身抛掷，跳过这一发：${it.javaClass.name}")
                0L
            }
            if (freedPixels > 0L) {
                val freedMiB = freedPixels * 4L / (1024L * 1024L)
                Log.i(TAG, "budget.csv freed=${freedMiB}MiB（像素 $freedPixels 条，退回未解码态，回合边界）")
                RunWatch.forceSample(view.context, "turn.budgetcsv freed=${freedMiB}MiB")
                journalEvict(freedMiB)
            }
        }
        // 进程口径水位（OOM-A 波，CONTRACT-OOM-A §3）：Android 17 (API 37) 起 MemoryLimiter
        // 按**进程总量**（匿名 RSS + swap，cgroup v2 memory.high）节流/杀进程，与本函数原有
        // 判据看的 Java 堆（used/max）是两道独立闸 —— largeHeap 绕不过，且被杀无栈轨迹。
        // 官方无运行时查询自己上限的 API ⇒ 预算只能按 RAM 档位复刻 AOSP 标准档估算
        // （档位表与 TODO(verify) 都在 RunTrail.memoryLimiterBudgetMiB 的 KDoc）。
        // 触发线 70%：估算必有偏差，触发的又是屏上无感的第一档（重解必得同一张图），
        // 早收只多花几次重解，晚收就是进程消失。与堆水位档共用 [lastImageReleaseAtMs] 冷却。
        // PSS/RAM 读数失败（负数）恒不触发：宁可不收，不拿拍出来的数当系统压力（口径同
        // RunTrail.isSystemLow）。后台不可见档不接：回合边界只在前台跑，后台靠 onTrimMemory。
        run {
            val pssKib = runCatching { android.os.Debug.getPss() }.getOrDefault(-1)
            val pssMiB = if (pssKib > 0L) pssKib / 1024L else -1L
            val totalRamMiB = runCatching {
                val am = view.context.getSystemService(Context.ACTIVITY_SERVICE)
                    as? android.app.ActivityManager
                val mem = android.app.ActivityManager.MemoryInfo()
                am?.getMemoryInfo(mem)
                mem.totalMem / (1024L * 1024L)
            }.getOrDefault(-1L)
            // 厂商覆写档（OOM-B，2026-11-08）：先读设备自己的 memory-limiter-config.xml，
            // 读不到才回落 RunTrail 里的 AOSP 标准档硬表（[memoryLimiterDevice] 装配期读一次）。
            val device = memoryLimiterDevice
            val budget = RunTrail.memoryLimiterBudgetMiB(totalRamMiB, visible = true, config = device?.sets)
            if (RunTrail.shouldReclaimForProcessFootprint(pssMiB, budget)) {
                lastImageReleaseAtMs = SystemClock.elapsedRealtime()
                val freed = releaseResourceImagePixels()
                // 钉一发：pss/budget/ram 三个数并排，报告里能直接看出「进程总量贴的是哪一档」；
                // src=vendor/system 说明预算取自设备实读表，estimate = 设备上没有可读配置、用的硬表。
                RunWatch.forceSample(
                    view.context,
                    "turn.pssfootprint pss=${pssMiB}MiB budget=${budget}MiB" +
                        " ram=${totalRamMiB}MiB src=${device?.origin?.label ?: "estimate"}" +
                        " freed=${freed}MiB",
                )
                journalEvict(freed)
            }
        }
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val max = runtime.maxMemory() / (1024L * 1024L)
        val now = SystemClock.elapsedRealtime()
        if (RunTrail.shouldReleaseImagePixels(used, max, now - lastImageReleaseAtMs)) {
            lastImageReleaseAtMs = now
            val freed = releaseResourceImagePixels()
            // 收完立刻钉一发：这一行既是「这刀有没有真把堆拉回来」的读数，也是被杀时
            // 报告里最后能看到的那一行。
            if (freed > 0L) RunWatch.forceSample(view.context, "turn.trim used=${used}MiB/$max")
            journalEvict(freed)
            // 预算档（2026-11-01 设备实证）：可重解那一档在真机上收回 0 —— 常驻的是**画过的**
            // G_ID 合成缓冲（657 张 / 167 MiB）。这一刀把它们按 LRU 收到像素预算以内就停手，
            // 而 92% 那一档（下方）仍是「够大的一律 dispose」；两档共用同一个冷却时点。
            val capped = releaseUnusedGraphics(usePixelBudget = true, minPixels = BUDGET_REAP_MIN_PIXELS)
            if (capped.buffers > 0) {
                RunWatch.forceSample(
                    view.context,
                    "turn.trim.budget n=${capped.buffers}" +
                        " freed=${capped.bytes / (1024L * 1024L)}MiB used=${used}MiB/$max",
                )
                journalEvict(capped.bytes / (1024L * 1024L))
            }
        }
        if (RunTrail.shouldReleaseUnusedGraphics(used, max, now - lastUnusedReleaseAtMs)) {
            lastUnusedReleaseAtMs = now
            val r = releaseUnusedGraphics()
            if (r.buffers > 0) {
                RunWatch.forceSample(
                    view.context,
                    "turn.trim.unused n=${r.buffers} freed=${r.bytes / (1024L * 1024L)}MiB used=${used}MiB/$max",
                )
                journalEvict(r.bytes / (1024L * 1024L))
            }
        }
        // 系统口径的第二道闸（2026-10-31 收件箱：14 份静默退出 Java 堆只有 10%~79%，
        // 上面的水位判据一次都没触发，死因是整机内存被别的东西吃光）。这一道看的是
        // **系统**可用内存与本机低内存线，与上面的堆水位互补、冷却共享（上面刚收过就跳过）。
        val sysAvail = RunWatch.systemAvailMiB()
        val lmkLine = RunWatch.lowMemoryLineMiB(view.context)
        if (RunTrail.shouldReleaseImagePixelsForSystemPressure(sysAvail, lmkLine, now - lastImageReleaseAtMs)) {
            lastImageReleaseAtMs = now
            val freed = releaseResourceImagePixels()
            // 这一行是「系统压过来时我们收了多少」的唯一读数（sys/lmk 由 formatLine 自动附在后面）。
            RunWatch.forceSample(view.context, "turn.syslow freed=${freed}MiB")
            journalEvict(freed)
        }
        if (RunTrail.shouldReleaseUnusedForSystemPressure(sysAvail, lmkLine, now - lastUnusedReleaseAtMs)) {
            lastUnusedReleaseAtMs = now
            val r = releaseUnusedGraphics()
            RunWatch.forceSample(
                view.context,
                "turn.syscrit n=${r.buffers} freed=${r.bytes / (1024L * 1024L)}MiB",
            )
            journalEvict(r.bytes / (1024L * 1024L))
        }
        // 堆贴顶（≥ 90%）时补钉一发 **Java 堆分项账面**（L5 heap-census，判式见 [HeapCensusPin]）：
        // 这一发与上面的回收动作**语义无关**，只在回收判据跑完后多钉一行读数（fullScan=false，
        // 不扫字符串池/行段，VM 线程热路径零额外分配），报告与轨迹从此读得出「贴顶的到底是什么」。
        // 冷却自带（[lastCensusPinAtMs]）、整段 runCatching（[maybePinHeapCensusTrail]）—— 取不到就当没钉。
        maybePinHeapCensusTrail(used, max)
    }

    /**
     * R3：回合边界有**实际回收量**时记一条 `resource.evict`（0 = 没收到，不记）。
     *
     * 只做判空转发 —— 事件体异常由 [EventJournal.record] 自己兜住；这里再加一层是因为
     * 本函数跑在 VM 线程的回合边界上，宁愿少一条事件也不许多一条异常路径。
     */
    private fun journalEvict(freedMiB: Long) {
        if (freedMiB <= 0L) return
        EventJournal.record("resource.evict", mapOf("freedMiB" to freedMiB.toString()))
    }

    /** 上一次按水位尝试回收的毫秒（[SystemClock.elapsedRealtime]）；负数 = 还没试过。 */
    private var lastImageReleaseAtMs = -1L

    /**
     * 设备自己的 MemoryLimiter 档位表（OOM-B，2026-11-08）：首次进进程口径判据时读一次
     * （[MemoryLimiterConfig.readDevice]：vendor → system），之后复用。
     *
     * null = 两路都没读到可解析的配置（老系统 / SELinux 拒绝 / 文件不存在）⇒
     * [RunTrail.memoryLimiterBudgetMiB] 回落 AOSP 标准档硬表，行为与 OOM-A 波完全一致。
     */
    private val memoryLimiterDevice: MemoryLimiterConfig.DeviceConfig? by lazy {
        MemoryLimiterConfig.readDevice()
    }

    /** 上一次「释放画过的缓冲」的毫秒；与 [lastImageReleaseAtMs] 分开计冷却。 */
    private var lastUnusedReleaseAtMs = -1L

    /** 上一次走长页自救口的毫秒（[RunTrail.shouldMidPageCheck] 的节流基准）。 */
    private var lastMidPageCheckAtMs = -1L

    /** 上一次钉「长页」轨迹行的毫秒（[RunTrail.shouldPinLongTurn] 的冷却基准）。 */
    private var lastLongTurnPinAtMs = -1L

    /** 页中保活转发失败只记一次日志（避免 250 ms 一发刷屏）。 */
    private var progressPublishWarned = false

    /**
     * 本页函数流水（`obs-pagefuncs` 的消费端，fix-pagetrailwire）：装配期挂到
     * `QueueInputSink.pageTrail` 与 `VirtualMachine.pageTrail` 两处，缺省 null = 零行为。
     *
     * 常驻 < 0.5 KiB（定长 `IntArray(64)`），热路径只在进帧三处各多一次数组存；关不记由
     * 挂载点决定（core 侧 `PageFunctionTrail` KDoc 的容量节）。
     */
    @Volatile
    private var pageTrail: PageFunctionTrail? = null

    /**
     * 刚交付那一页的流水文本（`最旧→最新`，空 = 那一页没有进帧）。
     *
     * 为什么要有这份快照：页边界清零**紧跟**在 `onTurnReady` 交付之后
     * （`QueueInputSink` 两个等待点的 `try/finally`），所以 VM 停在等待点时
     * [pageTrail] 已被清零 —— 「上一页」只能靠交付回调里拍下的这一份。
     * 写者 = VM 线程（交付回调内），读者 = DevTools daemon 线程，故 `@Volatile`。
     */
    @Volatile
    private var lastPageTrailText: String = ""

    /** 当前页（在飞）的流水读数原文；VM 未跑 / 已交付 ⇒ 空串（口径见 [PageTrailWire] 的 KDoc）。 */
    private fun pageFlowReadout(): String = pageTrail?.readout().orEmpty()

    /**
     * 装配期把「本页函数流水」挂上（接线点：`assembleSession` 返回 `GameSession` 之后、
     * `drive()` 之前 —— 第一页脚本跑起来时就必须已在记）。
     *
     * ## 三件必须一起做的事
     *
     * 1. **同一个实例**挂两处：记录点在 VM（`Vm.run` 入口帧 / `callInto` / `jumpInto`），
     *    清零点在 `QueueInputSink` 的两个等待点。两个不同实例会得到「不清零、跨页累加」
     *    的混合读数（`PageTrailWire.attachSink` KDoc）。
     * 2. **名字解析器只在读侧被叫**（core 侧 `readout()` 里），热路径只存下标 —— 这就是
     *    为什么这里绑一次、而不是在进帧钩子里拼串。
     * 3. 解析源必须是**运行期函数表**（`FuncMeta`，不可变数组，`index` 就是
     *    `Program.functions` 下标，见 `FuncMeta.kt:33`）。**不能**用
     *    `program.functions[i].name`：`.erbc` 产物档那张表是「未命中就地解码映射页」的
     *    惰性表，解码缓冲（`SectionWindow` 游标 / `StreamStringTable` 缓冲）**没有锁**
     *    （`ErbcStream.kt:1146-1160`），而 DevTools `where` 在 daemon 线程读流水 ——
     *    并发踩它的后果与 `ErrorReportVmStackTest` 钉的那条一模一样（把 VM 线程的解码
     *    踩坏 / 两条记录的串混成一个假名字），且还会把这摊 `FunctionInfo` 永久物化
     *    （整池 15.6 MiB 常驻，512 MiB 机型上正压在「堆贴顶」这条病根上）。
     *    `ErrorReportCollector.functionNameResolver` 已是全仓对这条铁律的既有实现
     *    （`callstack` / 崩溃报告同源），这里直接复用。
     *
     * 反射失败（`ProcessRuntime.vm` 或 `VirtualMachine.pageTrail` 改名）⇒ 流水挂不上：
     * 钉行里的 `page:` 段退化成 `-`，其余读数逐字不变。字段名由 `PageTrailWireTest` 钉住。
     */
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

    /** DevTools `where`：在飞本页的流水（跨线程尽力而为；等待点之后恒为空，见 [lastPageTrailText]）。 */
    fun debugPageFlow(): String = pageFlowReadout()

    /** DevTools `where`：刚交付那一页的流水（交付回调里、清零之前拍下的快照）。 */
    fun debugLastPageFlow(): String = lastPageTrailText

    /**
     * 长页自救口：一页还没算完时的内存压力出口（挂在 VM 指令循环的周期检查点上）。
     *
     * ## 为什么会走到这里
     *
     * 2026-11-01 收件箱（0.2.34 / OPPO PLD110）那一轮轨迹是
     * `turn 2ms` → `turn 2342ms` → `turn 15192ms` → `turn 58515ms` 交替：同一类页面平时
     * 2 毫秒，堆贴顶（`used=510~511MiB max=512MiB`）时一分钟才出下一页。玩家的说法是
     * 「玩着玩着卡死、点屏幕没反应」—— 那 58 秒里脚本没到等待点，按原版
     * `PressEnterKey` 的 Running 早退（`EmueraConsole.cs:1374-1377`）点击本来就落不进输入队列，
     * 于是画面静止 + 点不动 + 没有崩溃也没有上报。
     *
     * 旧形状里所有自救动作（[releaseImagePixelsIfHeapHigh] 的按水位回收）都只挂在**页尾**
     * （`onTurnReady` = 进入等待点）：一页一分钟，那一分钟一次都跑不到 ⇒ 越慢越回收不到、
     * 越回收不到越慢。本方法把同一个判式多挂一个触发点。
     *
     * ## 第一件事（2026-11-02，heap-budget）：兑现 UI 线程转来的请求
     *
     * [gfxSweepRequested] / [strongReclaimRequested] 此前**只有页尾一个兑现点**，而
     * `onTrimMemory` 那一发在 UI 线程只收得了负号段（线程约束见 [releaseResourceImagePixels]）
     * ⇒ 报告里 `trim=runningCritical freed=0MiB` 之后紧跟 ANR 的那份，「能救命的强档那一刀」
     * 还押在页尾等一页算完。本方法已经在 VM 线程、两条语句之间 ⇒ 就地兑现（[drainReclaimRequests]），
     * 最迟 250 ms（[RunTrail.MID_PAGE_CHECK_INTERVAL_MS]）。
     *
     * ## 三件事（前两件语义零风险，第三件只在 92% 水位以上且带预算/exclude 双保险）
     *
     * 1. **钉一发轨迹**（带卡住秒数与最内层 ERB 函数名）：报告里此前只有页尾那一条
     *    `turn Nms`，中间那 58 秒在盘上是空白，「卡死」永远读不出形状。
     * 2. **收「重解必得同一张图」的那一档**（[releaseResourceImagePixels]，负号段 CSV 父图 +
     *    正号段 G_ID 工作表）：判式、水位、冷却全部复用 [RunTrail] 那套常量。
     *
     * 3. **堆摸到 92%（或 95% 悬崖）时再收「画过的」那一档，且只收到像素预算以内**
     *    （[releaseUnusedGraphics] 的 `usePixelBudget`）：2026-11-01 的长页证据是第 2 条在真机上
     *    收回 0 MiB（可重解项早就退完，常驻的 167 MiB 全是画过的 G_ID），于是 95 秒的页里
     *    有再多次检查点也救不回堆。水位与冷却复用 [RunTrail.shouldReleaseUnusedGraphics]，
     *    与页尾那一档共享同一个计时段（[lastUnusedReleaseAtMs]）。
     *
     * 线程：只在 VM 线程、两条语句之间被调（检查点在取指之前，语句体不会被截断），
     * 所以正号段那一半的「只许 VM 线程」约束天然满足。异常由 [com.eratw.port.core.proc.ProcessRuntime]
     * 那侧 `runCatching` 兜住，自救失败不该把这一局打死。
     */
    private fun midPageCheckpoint() {
        val now = SystemClock.elapsedRealtime()
        if (!RunTrail.shouldMidPageCheck(now - lastMidPageCheckAtMs)) return
        lastMidPageCheckAtMs = now
        // 页中保活（PERF-YIELD）：只上屏、不算段落边界 —— 长链中途把冻结段的最新完整行表
        // 交给渲染层，避免「睡醒开始新一天」这类 100+ 秒段落在页尾前整屏静止。sink 内部还有
        // 静默期 / 节流两道判据；异常不得打死本局，与 onPressureCheckpoint 外层兜底同一纪律。
        runCatching { session?.console?.publishProgressFrame() }.onFailure { t ->
            if (!progressPublishWarned) {
                progressPublishWarned = true
                Log.w(TAG, "midpage.progress", t)
            }
        }
        heartbeat?.markVm(SystemClock.elapsedRealtime(), "midpage")
        // 页中兑现 UI 线程转来的回收请求（2026-11-02，heap-budget）：这一发本来就跑在 VM 线程、
        // 两条语句之间，正号段的线程约束天然满足 ⇒ 请求不必再等页尾。旧形状下 `RUNNING_CRITICAL`
        // 的强档要等**整页算完**才兑现，而报告的形状正是「一页一秒到一分钟」——
        // `trim=runningCritical freed=0MiB` 那个 0 读的是 UI 线程只能收负号段这一半，
        // 真正动得了「画过的」缓冲的那一刀还押在页尾。判式与取舍见 [drainReclaimRequests]。
        val strongDone = drainReclaimRequests("midpage", "页中检查点")
        // 没有在飞的翻页（玩家没提交 / 页已交付）⇒ 长页判据无从谈起，只走水位那一半。
        val startedNs = turnStartNs.get()
        val pageMs = if (startedNs == 0L) 0L else (System.nanoTime() - startedNs) / 1_000_000L
        val pinDue = RunTrail.shouldPinLongTurn(pageMs, now - lastLongTurnPinAtMs)
        val runtime = Runtime.getRuntime()
        val usedMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMiB = runtime.maxMemory() / (1024L * 1024L)
        // 与页尾同一套水位 + 冷却判式（判式本体在 RunTrail，两边共用一份数）。
        // 刚做过强档 ⇒ 这一轮不再走水位判据：两档都已一次做全，95% 那一档无视冷却会立刻白做一遍。
        val reclaimDue = !strongDone &&
            RunTrail.shouldReleaseImagePixels(usedMiB, maxMiB, now - lastImageReleaseAtMs)
        if (!pinDue && !reclaimDue) return
        if (pinDue) {
            lastLongTurnPinAtMs = now
            // 本页流水**同线程直读**（本方法只在 VM 线程上被调）：读到的就是此刻这一页
            // 已经进过的帧，逐字确定 —— 与 DevTools 那种跨线程读不是一类读数。
            RunWatch.forceSample(
                view.context,
                PageTrailWire.longTurnLine(pageMs, longTurnFrame(), pageFlowReadout(), "${usedMiB}MiB/$maxMiB"),
                    heartbeat,
            )
        }
        if (reclaimDue) {
            lastImageReleaseAtMs = now
            val freed = releaseResourceImagePixels()
            if (freed > 0L) {
                RunWatch.forceSample(
                    view.context,
                    "midpage.trim freed=${freed}MiB used=${usedMiB}MiB/$maxMiB",
                )
            }
            // 页中动「画过的」缓冲：与页尾同一个 92% 水位 + 60 s 冷却（[RunTrail.shouldReleaseUnusedGraphics]），
            // 只收到像素预算以内就停手。为什么打破「页中只做重解无损那一档」的旧口径：2026-11-01
            // 那次 95 秒长页（@APPLYCOLOR_F）里 midpage.trim 每一发都收回 0 MiB —— 可重解的项早退完，
            // 堆在 511/512 上就是一路抖到 OOM；同样形状的启动失败（BeginFlow 里 657 张画过的 G_ID、
            // 167 MiB）根本没有页尾可等。exclude 护住 Sprite / CBG 正在显示的那批，
            // 代价顶多是脚本手里留着的中间图变空白。
            if (RunTrail.shouldReleaseUnusedGraphics(usedMiB, maxMiB, now - lastUnusedReleaseAtMs)) {
                lastUnusedReleaseAtMs = now
                val capped = releaseUnusedGraphics(usePixelBudget = true, minPixels = BUDGET_REAP_MIN_PIXELS)
                if (capped.buffers > 0) {
                    RunWatch.forceSample(
                        view.context,
                        "midpage.trim.budget n=${capped.buffers}" +
                            " freed=${capped.bytes / (1024L * 1024L)}MiB used=${usedMiB}MiB/$maxMiB",
                    )
                }
            }
        }
    }

    /**
     * 卡住时最内层 ERB 函数名（长页轨迹行用）。
     *
     * 反射口径与 [DevToolsHost] 的 `callstack` / [ErrorReportCollector] 的报告完全一致
     * （`ProcessRuntime.vm` → `funcStack` / `depth`）；本方法在 VM 线程的检查点上跑，
     * 读的就是自己那条栈，不存在与别的线程抢未加锁缓冲的问题。取不到一律给 `-`，
     * 钉轨迹这件事不值得为读数把自救动作变成异常。
     */
    private fun longTurnFrame(): String {
        val live = session ?: return "-"
        val frames = runCatching {
            val vf = com.eratw.port.core.proc.ProcessRuntime::class.java
                .getDeclaredField("vm").apply { isAccessible = true }
            val vm = vf.get(live.runtime) ?: return@runCatching emptyList()
            val fs = vm.javaClass.getDeclaredField("funcStack")
                .apply { isAccessible = true }.get(vm) as IntArray
            val depth = vm.javaClass.getDeclaredField("depth")
                .apply { isAccessible = true }.getInt(vm)
            VmStack.frameLines(fs, depth, 1, ErrorReportCollector.functionNameResolver(live.runtime))
        }.getOrNull().orEmpty()
        return frames.firstOrNull()?.trim()?.removePrefix("#0")?.trim() ?: "-"
    }

    /**
     * 运行中会话的配置条目表 —— 设置页热更通道（[HotConfigChannel]）的唯一取用点。
     *
     * 会话未装配 / 已关闭 / 装载失败 ⇒ null。读的是 `@Volatile` 的 [session]，UI 线程
     * 只会看到「完整装配好的实例」或 null（装配完成前根本不赋值），不会读到半个会话。
     *
     * 存在的理由：设置页读盘用的 `SettingsModel.store` 与运行中会话的条目表是**两份实例**
     * （后者在装配期定死，`GameSession.kt:301`）。原版靠进程级静态单例 `ConfigData.Instance`
     * 天然共享（`ConfigDialog.cs:1107-1109` 改完直接推给 `CBProc`）；移植侧按铁律 4
     * 不许静态可变全局，只能由壳把这条引用交出去。
     */
    fun liveConfigStore(): ConfigStore? = session?.configStore

    /** DevTools lines 命令 / 错误报告尾部：console 当前整屏**行文本**快照（最多 n 行，按显示序）。 */
    fun debugConsoleLines(n: Int): List<String> = session?.console?.tailText(n).orEmpty()

    /** DevTools rev 命令：console sink 的 revision 水位。 */
    fun debugRevision(): Long = session?.console?.revision ?: -1L

    /**
     * DevTools var/vars/callstack/trace/break 命令的会话只读口（DBG3）：返回当前会话，
     * 未装载 = null。调用方（DevTools daemon 线程）自行承担读数竞态——VM 正在跑时
     * store/调用栈读数带「RUNNING（竞态）」前缀，等待输入时才是精确值（各命令 KDoc 同口径）。
     */
    fun debugSession(): GameSession? = session

    /**
     * Activity `onPause`：放弃音频焦点并按瞬态失焦挂起全部声道。
     *
     * **只在这里响应**：`MainActivity.onPause` 只调本方法，不直接碰声道；`AudioManager` 的
     * 回调也汇到同一个 [focus] 会话，重复触发由 `AudioFocusSession.lastEvent` 与
     * `AudioMixer.focusScope` 两道闸挡掉（见两者的 KDoc）。
     *
     * 刻意**不**停 VM 线程：设置页盖在上面时脚本照常推进（`MainActivity` 类头的既有口径）。
     */
    fun onPause() {
        activityPaused = true
        // 离开前台时还没作答的迁移询问按原版「关框 = 否」收敛：不搬文件，VM 线程继续装载。
        // 不做这一步的话，Activity 被回收后闩锁永远没人放行，装载线程会把整局拖死。
        cancelPendingSaveMigrationPrompt()
        focus.onHostPaused()
    }

    /**
     * Activity `onResume`：重新申请焦点；拿到后按 `GAIN` 恢复（循环 BGM 续播、一次性音效不重播）。
     *
     * 这是永久抢占后的**手动**恢复点：会话对所有非持有阶段都会重新 acquire（DEV-06）；
     * 留在前台时的**自动**恢复由迟到的 `GAIN` 触发（见 [focus] 的 KDoc）。
     */
    fun onResume() {
        activityPaused = false
        focus.onHostResumed()
    }

    /**
     * 后台冻结/前台恢复（壳层 `onStop` 30 秒后调 true，`onStart` 调 false）。
     *
     * 语义在 core：`GameSession.setBackgroundPaused` 只冻脚本推进（VM 在下一个安全点
     * `awaitResume`），不关输入队列、不停音频、不碰存档；恢复后从同一点继续。
     *
     * 线程：可从任意线程调（壳层的 `postDelayed` 回调走这里）。会话尚未装配时只记
     * [backgroundPauseIntent]，[attachSession] 挂上会话后立刻补应用 —— 首启装载期间
     * 切后台的那一次因此不会丢。已 [close] 时丢弃，且 [close] 会把意图清掉，
     * 不把暂停态泄漏给下一次会话。
     */
    fun setBackgroundPaused(paused: Boolean) {
        synchronized(pauseLock) {
            if (closed) return
            backgroundPauseIntent = paused
            session?.setBackgroundPaused(paused)
        }
    }

    /**
     * 低内存回调（壳层 `onTrimMemory`，档位分流见 [GraphicsTrimPlan]）：清掉**可重建**的位图缓存。
     *
     * 两层，分别对应两份**不同的堆**：
     * 1. [SpriteBitmapSource] 的三级 `LruCache`（帧缓冲、文件位图与 native 工作表）：
     *    解码结果随取随建，清掉最多让下一帧重新解码；工作表条目走「宽限期 + 主线程」的
     *    延迟回收（见 [SpriteBitmapSource.trimCaches]），绝不回收正被画着的位图。
     * 2. 本屏自己的 native 位图（`CanvasConsoleView.viewBitmapCacheBytes`：整屏保留面、
     *    背景烘焙面、行栅格缓存、行位图池）—— 2026-11-02 之前这一层**没有任何 trim 入口**，
     *    `onTrimMemory` 三层里最重的 `RUNNING_*`/`UI_HIDDEN` 都够不到它，而端点报告
     *    「native 驻留偏高」那一桶（286~361 MiB）的账面只有平台那三张表在册。
     *
     * @param deep true = 后台系档位（`UI_HIDDEN` / `BACKGROUND` / `MODERATE` / `COMPLETE`）：
     *   连整屏保留面与背景烘焙面一起交还，回前台走一帧整幅重画；false = 前台 `RUNNING` 系：
     *   只收行缓存与行池（都是重烤必得同一张图的性能面），画面一个字节不动，避免玩家
     *   正看着这一屏时闪一帧空白。判式与分档见 [NativeTrimPlan]。
     * @return 本次收口的账面读数（供轨迹行与报告行）；图形通路尚未装配时给「全 0」的读数。
     */
    internal fun trimGraphicsCaches(deep: Boolean): NativeTrimReadout {
        val before = nativeCacheSnapshot()
        graphicsWiring?.frames?.trimCaches()
        runCatching { view.releaseMemoryCaches(deep) }
            .onFailure { Log.w(TAG, "内存压力：本屏位图交还失败（继续）", it) }
        val readout = NativeTrimReadout(before, nativeCacheSnapshot(), deep)
        // logcat 同一份读数：真机取证时不一定拿得到落盘的轨迹，而 `adb logcat` 总是可得。
        Log.i(TAG, "trim.graphics deep=$deep viewFreed=${readout.viewFreedMiB()}MiB${readout.suffix()}")
        return readout
    }

    /**
     * native 位图两块的账面快照（诊断读数，单位 MiB）。
     *
     * 两份都不在 512 MiB 的 Java 堆挡位里：`bitmapCacheMiB` 是平台侧三张 `LruCache`
     * （预算 32 + 16 + 192 MiB，见 `SpriteBitmapSource`），`viewMiB` 是本屏自有的四块位图
     * （预算 24 + 6 MiB 再加两张整屏面）。任一步取不到（通路未装配 / 位图已回收）只给 0，
     * 绝不让诊断把内存压力回调这条**救命路径**拖崩。
     *
     * 线程：`onTrimMemory`（UI 线程）与报告路径（VM / 装载线程）都会读。本屏那几枚字段
     * 只由 UI 线程写，跨线程读到的是中间态 ⇒ 只作趋势读数，不参与任何判定。
     */
    internal fun nativeCacheSnapshot(): NativeCacheSnapshot =
        NativeCacheSnapshot(
            bitmapCacheMiB = runCatching { (graphicsWiring?.frames?.cacheBytes() ?: 0).toLong() / BYTES_PER_MIB }
                .getOrDefault(0L),
            viewMiB = runCatching { view.viewBitmapCacheBytes() / BYTES_PER_MIB }.getOrDefault(0L),
            nativeAllocMiB = runCatching { android.os.Debug.getNativeHeapAllocatedSize() / BYTES_PER_MIB }
                .getOrDefault(0L),
        )

    /**
     * 把迁移询问投到 UI 线程并弹询问框。
     *
     * 仍是框架 `android.app.AlertDialog`（app 无 AppCompat 依赖，主题是系统 Material）——
     * 返回键与「否」同义这条要靠框架的取消回调，不能换成 Compose 浮层；但面板与字号走
     * [EratwShellDialog]，与更新弹窗同一副长相。
     * （原版 `Dialog.cs:21-28`：只有 `DialogResult.Yes` 才是 true。）
     * Activity 已销毁/正在销毁时直接按「否」作答，不把 VM 装载线程挂住。
     */
    private fun askSaveMigrationOnUi(title: String, message: String, answer: (Boolean) -> Unit) {
        synchronized(promptLock) { pendingSaveMigrationAnswer = answer }
        view.post {
            val activity = view.context.findActivity()
            if (closed || activity == null || activity.isFinishing || activity.isDestroyed) {
                synchronized(promptLock) {
                    if (pendingSaveMigrationAnswer === answer) pendingSaveMigrationAnswer = null
                }
                answer(false)
                return@post
            }
            val dialog = EratwShellDialog.builder(activity)
                .setView(EratwShellDialog.content(activity, title, message))
                .setPositiveButton("是") { _, _ -> answer(true) }
                .setNegativeButton("否") { _, _ -> answer(false) }
                .setOnCancelListener { answer(false) }
                .create()
            EratwShellDialog.dress(dialog)
            dialog.setOnDismissListener {
                synchronized(promptLock) {
                    if (pendingSaveMigrationAnswer === answer) pendingSaveMigrationAnswer = null
                }
                if (pendingSaveMigrationDialog === dialog) pendingSaveMigrationDialog = null
            }
            pendingSaveMigrationDialog = dialog
            // 弹不出来（窗口令牌失效等）按「否」收敛，别让装载线程等一个永远不会来的作答。
            if (runCatching { dialog.show() }.isFailure) {
                pendingSaveMigrationDialog = null
                answer(false)
            } else {
                Log.i(TAG, "存档迁移：已弹询问框（$title）")
            }
        }
    }

    /** 放行（按「否」）正在等待的迁移询问；没有等待者时 no-op。 */
    private fun cancelPendingSaveMigrationPrompt() {
        val pending = synchronized(promptLock) {
            pendingSaveMigrationAnswer.also { pendingSaveMigrationAnswer = null }
        }
        val dialog = pendingSaveMigrationDialog
        pendingSaveMigrationDialog = null
        if (dialog != null) view.post { runCatching { dialog.dismiss() } }
        if (pending == null) return
        pending(false)
        Log.i(TAG, "存档迁移询问被取消（离开前台/关闭）→ 按否处理")
    }

    /**
     * 死循环告警的宿主面（ENGFIX-4）：VM 线程 → `view.post` → UI 线程画框 → 阻塞的 VM 线程被放行。
     *
     * 文案：标题/正文/退出错误全在 core 的 [InfiniteLoopAlert]（官方 ja/zhs/en 三语逐字照抄），
     * 语言档只经壳层单路取 `UiPrefs.settingsLanguage` → [langVariantOf]（三语一一映射，
     * core 不读 Locale、不持全局语言状态，AGENTS 铁律 4）。
     * 按钮「是/否」与既有迁移询问同样的原版 YesNo 语义（`Dialog.cs:21-28`：只有 Yes 才中止），
     * 词源在 [PortraitShellCopy] 的三语表。Activity 已销毁/正在销毁时直接按
     * [InfiniteLoopAnswer.NoUi] 作答（= 原版「没有可用 UI」），不把 VM 线程挂在框上。
     */
    private fun askInfiniteLoopOnUi(alert: InfiniteLoopAlert, answer: (InfiniteLoopAnswer) -> Unit) {
        synchronized(promptLock) { pendingInfiniteLoopAnswer = answer }
        view.post {
            val activity = view.context.findActivity()
            if (closed || activity == null || activity.isFinishing || activity.isDestroyed) {
                synchronized(promptLock) {
                    if (pendingInfiniteLoopAnswer === answer) pendingInfiniteLoopAnswer = null
                }
                answer(InfiniteLoopAnswer.NoUi)
                return@post
            }
            val lang = UiPrefs.settingsLanguage(activity)
            val variant = langVariantOf(lang)
            val copy = PortraitShellCopy.of(lang)
            val dialog = EratwShellDialog.builder(activity)
                .setView(
                    EratwShellDialog.content(
                        activity,
                        alert.title(variant),
                        alert.message(variant),
                    ),
                )
                .setPositiveButton(copy.dialogYes) { _, _ ->
                    answer(InfiniteLoopAnswer.Exit(variant))
                }
                .setNegativeButton(copy.dialogNo) { _, _ -> answer(InfiniteLoopAnswer.Continue) }
                .setOnCancelListener { answer(InfiniteLoopAnswer.Continue) }
                .create()
            EratwShellDialog.dress(dialog)
            dialog.setOnDismissListener {
                synchronized(promptLock) {
                    if (pendingInfiniteLoopAnswer === answer) pendingInfiniteLoopAnswer = null
                }
                if (pendingInfiniteLoopDialog === dialog) pendingInfiniteLoopDialog = null
            }
            pendingInfiniteLoopDialog = dialog
            // 弹不出来（窗口令牌失效等）按「无 UI」收敛，别让 VM 线程等一个永远不会来的作答。
            if (runCatching { dialog.show() }.isFailure) {
                pendingInfiniteLoopDialog = null
                answer(InfiniteLoopAnswer.NoUi)
            } else {
                Log.i(TAG, "死循环：已弹告警框（${alert.displaySource}:${alert.lineNo}）")
            }
        }
    }

    /** 放行（按「无 UI」）正在等待的死循环告警；没有等待者时 no-op。 */
    private fun cancelPendingInfiniteLoopPrompt() {
        val pending = synchronized(promptLock) {
            pendingInfiniteLoopAnswer.also { pendingInfiniteLoopAnswer = null }
        }
        val dialog = pendingInfiniteLoopDialog
        pendingInfiniteLoopDialog = null
        if (dialog != null) view.post { runCatching { dialog.dismiss() } }
        if (pending == null) return
        pending(InfiniteLoopAnswer.NoUi)
        Log.i(TAG, "死循环告警被取消（关闭）→ 按无 UI 处理")
    }

    // ------------------------------------------------------------------
    // UPDATECHECK 的宿主面（波次 CONTRACT-UPDATE §2 U-F）
    // ------------------------------------------------------------------

    /**
     * 把 `UPDATECHECK` 的四件宿主能力装进会话的 [FileSystemOps]。
     *
     * 时机：会话装配完成后、`begin()` 之前（[assembleSession] 的返回路径）。
     * 为什么只能事后装：`FileSystemOps` 的**构造点在 `GameSession` 第 8 步**
     * （`core/proc/GameSession.kt:721`），而 `GameSession` 与 `ExecContext` 的构造参数面
     * 都不在本路的所有权内（CONTRACT-UPDATE §2 的 U-F 行只给本文件与 `core/save/FileSystemOps.kt`）
     * ⇒ 用 `FileSystemOps.updateCheckHost` 这个 `var` 属性补上（写入方 = 装配线程 = VM 线程，
     * 与 `ExecContext.graphics` / `timer` 的「var + 单线程写」同一条纪律）。
     * 组长若把这条缝挪进 `GameSession` 的构造参数，删掉本函数即可（交付报告已登记）。
     *
     * 落空场景（只记日志、不抛）：装置显式传了别的 `systemOps`（非 [FileSystemOps]）时
     * `UPDATECHECK` 保持该实现自己的口径 —— 那是装置的选择，不在生产面上。
     */
    private fun wireUpdateCheckHost(session: GameSession) {
        val ops = session.runtime.execContext.systemOps as? FileSystemOps
        if (ops == null) {
            Log.i(TAG, "UPDATECHECK：会话的 systemOps 不是 FileSystemOps，保持原实现口径")
            return
        }
        ops.updateCheckHost = AndroidUpdateCheckHost(
            // 逐次现读会话条目表：原版读的是静态字段 `Config.ForbidUpdateCheck`（:2767），
            // 出厂 NO（ConfigData.cs:115 t: false），玩家在设置页关掉后下一次检查就该生效。
            forbidProvider = { session.configStore.itemOf(ConfigKey.ForbidUpdateCheck).bool() },
            transport = updateTransport,
            ask = UpdateAsk { title, message -> askUpdateCheck(title, message) },
            linkOpener = { url -> openUpdateLink(url) },
        )
    }

    /**
     * 把「存档失败时的求援」接进会话的 [FileSystemOps]（`FileSystemOps.reclaimMemory`）。
     *
     * ## 这条缝解决什么
     *
     * 2026-11-02 玩家反馈（eratw 4.980 / **0.2.35** / 没开「即时存档」插件）：
     * 「游玩时间太久了，可能导致存档失败，重启游戏后又正常了」。
     * 机制是**堆随时间累积**，不是存档本身：
     * - 0.2.35 的设备实证（`aa5a956c`）：启动 BeginFlow 建 657 张 G_ID、167 MiB 像素驻留，
     *   95 秒长页一路顶到 512 MiB 堆 OOM ⇒ 越玩越满；
     * - 存档要一次性分配「对象图 + 完整 payload（实测 560 KB）+ 压缩副本」，堆在 90%+ 时
     *   这一笔分不出来，`OutOfMemoryError` 被 `catch (Throwable)` 吞成 `false`，屏上只剩
     *   `SAVEDATA命令在存档时发生了意料之外的错误`；重启清堆 ⇒ 立刻恢复。
     *
     * ## 为什么是「存档失败之后」而不是「存档之前」
     *
     * 存档之前**每次**都收一遍，等于把 0.2.36 的两档水位（80% / 92% + 60 s 冷却）废掉：
     * 玩家每存一次档，画面里脚本留着的中间图就可能变空白 —— 那是正常游玩看得见的副作用。
     * 挂到失败之后，同样的副作用只在「本来就要存不上」的时刻兑现，代价从「画面抽一下」
     * 变成「救回一次存档」，划算。
     *
     * ## 收哪一档（阶梯式，能少破坏画面就少破坏）
     *
     * 1. **可重解的图片像素**（[releaseResourceImagePixels]，无损）：位图缓存不动，
     *    下一次重建才走重解，屏上不闪空帧；
     * 2. **画过的 G_ID 合成缓冲**（`usePixelBudget = true` + [BUDGET_REAP_MIN_PIXELS]）：
     *    与 0.2.36 的 92% 档同口径，LRU 出队，驻留降到 `pixelBudgetBytes()`（保守档堆上限 ÷4，
     *    放开档 ÷3；档位判式见 core/host/MemoryTier.kt）
     *    以内就停手，`exclude` 仍护住 Sprite / CBG 正在显示的那批；
     * 3. 预算档**一个都没收到**（驻留本来就低于预算，或全被 `exclude` 护住）⇒ 退
     *    「够大的一律 dispose」的旧口径 —— 这时不再为画面保留，存档成功优先。
     *
     * 三步的收益累加成总字节数交回 core；总量为 0 时 core 不重试（见 `reclaimMemory` 契约）。
     *
     * ## 线程
     *
     * 跑在 VM 线程（`FileSystemOps.saveData` 的调用线程 = 脚本语句线程），满足
     * [releaseUnusedGraphics] 的 `onVmThread()` 前置；不在 VM 线程时它自己返回 0，
     * 本函数随之交出 0 ⇒ core 侧不重试（宁可不救，也不跨线程 dispose 炸 AIOOBE）。
     *
     * 落空场景（装置传了别的 `systemOps`）：与 [wireUpdateCheckHost] 同口径，只记日志。
     */
    private fun wireSaveMemoryReclaim(session: GameSession) {
        val ops = session.runtime.execContext.systemOps as? FileSystemOps
        if (ops == null) {
            Log.i(TAG, "SAVEDATA：会话的 systemOps 不是 FileSystemOps，跳过堆贴顶求援接线")
            return
        }
        ops.reclaimMemory = reclaim@{ urgent ->
            // 预防档（urgent=false，每次存档前）：堆不到 90% 就**一动不动**。
            // 这是本钩子能挂在存档热路径上的前提 —— 否则每存一次档都可能让画面抽一下。
            // 90% 比 0.2.36 那两档水位（80% / 92%）都高：那两档已在回合边界/页中兜着，
            // 这一档只补「存档瞬间正好没人兜」的窗口，不该抢它们的活。
            if (!urgent) {
                val rt = Runtime.getRuntime()
                val used = rt.totalMemory() - rt.freeMemory()
                val max = rt.maxMemory()
                if (max <= 0L || used * 100L < max * SAVE_PRE_RECLAIM_PERCENT) return@reclaim 0L
            }
            // 阶梯式，能少破坏画面就少破坏；每一步的收益都累加，最后把总字节数交回 core。
            // ① 可重解的图片像素（无损：位图缓存不动，下一次重建才走重解，屏上不闪空帧）。
            val freedImageMiB = releaseResourceImagePixels()
            var total = freedImageMiB * 1024L * 1024L
            var buffers = 0
            // ② 画过的 G_ID：先按预算档（LRU 出队，驻留降到 maxMemory/4 以内就停手）。
            var released = releaseUnusedGraphics(
                usePixelBudget = true,
                minPixels = BUDGET_REAP_MIN_PIXELS,
            )
            buffers += released.buffers
            total += released.bytes
            // ③ 预算档一个都没收到（驻留本来就低于预算，或全被 exclude 护住）⇒
            //    退「够大的一律 dispose」的旧口径。存档失败比画面抽一下严重，这时不再保留。
            if (released.buffers == 0) {
                released = releaseUnusedGraphics()
                buffers += released.buffers
                total += released.bytes
            }
            if (total > 0L) {
                // 收掉引用之后补一发 GC 提示：求援档能走到这里说明刚才已经 OOM 过一次
                // （系统 GC 过仍不够），刚清掉的缓冲必须真正回收才能腾出空间给重试。
                // 预防档同理 —— 堆已过 90%，先回收再让存档分配，比让它撞一次 OOM 划算。
                // 正常游玩（堆不高）一次都不会执行到。
                Runtime.getRuntime().gc()
                val cause = if (urgent) "堆贴顶求援" else "存档前预防"
                Log.w(
                    TAG,
                    "SAVEDATA $cause：图片 ${freedImageMiB}MiB + 缓冲 $buffers 个 / " +
                        "${total / (1024L * 1024L)}MiB",
                )
            } else if (urgent) {
                Log.w(TAG, "SAVEDATA 堆贴顶求援：没有可回收的像素/缓冲")
            }
            total
        }
    }

    /**
     * CALLSHARP 本地方法表的装配位（CSHARP-WIRE C1 三段序的**第一段**）。
     *
     * 本版**不内置 .NET 插件 DLL**：`plugins/ChatCallSharp.dll` 是原版进程级插件，移植层只保留
     * core/fn 的通道设计（core 定语义、宿主往 [com.eratw.port.core.fn.NativeMethodRegistry]
     * 注册本机方法）。本函数因此不注册任何名字 —— 历史上的 `ModelSet` / `Chat` 两个降级桩已拆；
     * 保留函数与调用点是为了钉住「注册终表 → 装载趟裁决 → drive()」的严格三段序。
     *
     * ## 4.981 的两处可达 CALLSHARP 走装载趟裁决
     *
     * `ERB/DLC/AI对话补丁/对话调用.erb:35-36` 的
     * `CALLSHARP ModelSet(模型API, 模型Key, 模型名称)` 与
     * `CALLSHARP Chat(AI对话返回信息, 返回对话)` 是全语料仅有的**可达** CALLSHARP 调用点
     * （`:54` 的 9 参 `Chat` 变体整段被 SKIPSTART 包住，预处理期不编译，ErbLoader.cs:163-166）。
     * 注册表为空 ⇒ [prefillSharpLinks]（调用点紧随本函数之后）扫常量名查表未命中，按原版
     * 联结期相位逐行记错误并收警告（两句：`No native method ModelSet found` /
     * `No native method Chat found`）
     * （`FunctionIdentifier.cs:1201-1204` Warn(isError:true) → `ParserMediator.cs:125-136` 记
     * line.IsError / ErrMes、`:143-158` 装载完统一上屏）。该行**不执行也见警告**；真走到那行
     * 才抛记录值（`Process.cs:1017-1019`）。同文件其余函数照常挂载（原版对被标错的行 continue，
     * `ErbLoader.cs:1598-1600`），故 `@GET_CURRENT_ACTION_INFO` 等被口上引用的函数不受牵连。
     *
     * ## 时机（CSHARP-WIRE C1，2026-11-06 收口）
     *
     * 与 [wireUpdateCheckHost] 同型：会话建成后、任何脚本语句之前事后装配
     * （`GameSession` 构造参数面不归本路）；注册表挂在 `store.aux`（每 VM 一份，
     * `NATIVE_METHODS_SLOT`），VM 线程注册、CALLSHARP 同线程读，无跨线程问题。
     *
     * 原版的插件表是进程级单例、先于脚本装载填好（联结期查表，FunctionIdentifier.cs:1201-1204）；
     * 这里注册表挂 `store.aux`、store 由 GameSession 构造创建 ⇒ 「注册先于容器解码」做不到，
     * 「注册先于装载趟扫描裁决」由三段序保证 —— 装载趟扫描时本地方法表已是终表。
     */
    private fun wireCorpusNativeMethods(session: GameSession) {
        val registry = nativeMethods(session.store.aux)
        // 本版不往注册表放任何名字：4.981 的两处可达 CALLSHARP 由紧随其后的装载趟裁决拒载并
        // 统一上屏；不设执行期桩（桩只把装载期相位退化成「执行到才报」，且提示文案是本版私加的）。
        Log.i(TAG, "CALLSHARP 本地方法表（本版无 .NET 插件）：${registry.names()}")
    }

    /**
     * 问玩家「要不要打开链接」—— 三态交出（是 / 否 / 没问到）。
     *
     * 阻塞复用 [updatePrompt]（同一把 latch），结局由 [UpdateAskTicket] 定性：
     * - 玩家按了「是」/「否」或关框 ⇒ 直接交出布尔值（关框 = 否，原版 `MessageBox.Show`
     *   的 `MessageBoxDefaultButton.Button2` 也是把「否」当默认项，`:2802`）；
     * - [UPDATE_ASK_TIMEOUT_MS] 内没作答、或框根本没弹出去 ⇒ 交出 **null**，
     *   决策表落 RESULT=3（原版的 `MessageBox.Show` 抛异常时是同一条出口，`:2827-2832`）。
     *
     * 本函数跑在 VM 线程（`FileSystemOps.updateCheck` 的调用线程），**不许在 UI 线程调用**
     * —— 那样会把闩锁等成自锁。
     */
    private fun askUpdateCheck(title: String, message: String): Boolean? {
        currentUpdateAskTicket = null
        val yes = updatePrompt(title, message)
        val ticket = currentUpdateAskTicket
        currentUpdateAskTicket = null
        return if (ticket == null || ticket.timedOut || ticket.notDelivered) null else yes
    }

    /**
     * 把更新检查的询问投到 UI 线程并弹系统对话框（形状照 [askSaveMigrationOnUi]，
     * 差异只有「超时兜底」与「没弹出去 ≠ 否」两条）。
     *
     * 本函数由 [updatePrompt] 在 VM 线程**同步**调用：先建好 [UpdateAskTicket] 并投出，
     * 再让调用方阻塞在闩锁上 —— 于是超时定时器与对话框生命周期都归 UI 线程，
     * VM 线程只负责等一个一定来得到的答案（定时器到点也会放行）。
     */
    private fun askUpdateCheckOnUi(title: String, message: String, answer: (Boolean) -> Unit) {
        val ticket = UpdateAskTicket()
        currentUpdateAskTicket = ticket
        synchronized(promptLock) { pendingUpdateAnswer = answer }
        val timeout = Runnable {
            if (!ticket.claim(timeout = true)) return@Runnable
            val dialog = pendingUpdateDialog
            if (dialog != null) view.post { runCatching { dialog.dismiss() } }
            Log.i(TAG, "UPDATECHECK 询问 ${UPDATE_ASK_TIMEOUT_MS}ms 未作答 → 按原版异常支（RESULT=3）")
            answer(false)
        }
        mainHandler.postDelayed(timeout, UPDATE_ASK_TIMEOUT_MS)
        view.post {
            val activity = view.context.findActivity()
            if (closed || activity == null || activity.isFinishing || activity.isDestroyed) {
                // 「弹不出去」不是「玩家说不」：定性成没问到，让决策表落 3（见 askUpdateCheck）。
                ticket.claim(notDelivered = true)
                mainHandler.removeCallbacks(timeout)
                synchronized(promptLock) { if (pendingUpdateAnswer === answer) pendingUpdateAnswer = null }
                answer(false)
                return@post
            }
            val dialog = EratwShellDialog.builder(activity)
                .setView(EratwShellDialog.content(activity, title, message))
                .setPositiveButton("是") { _, _ ->
                    if (ticket.claim()) {
                        mainHandler.removeCallbacks(timeout)
                        answer(true)
                    }
                }
                .setNegativeButton("否") { _, _ ->
                    if (ticket.claim()) {
                        mainHandler.removeCallbacks(timeout)
                        answer(false)
                    }
                }
                // 返回键关框 = 否（原版只有 DialogResult.Yes 算 true，Dialog.cs:21-28）。
                .setOnCancelListener {
                    if (ticket.claim()) {
                        mainHandler.removeCallbacks(timeout)
                        answer(false)
                    }
                }
                .create()
            EratwShellDialog.dress(dialog)
            dialog.setOnDismissListener {
                mainHandler.removeCallbacks(timeout)
                synchronized(promptLock) { if (pendingUpdateAnswer === answer) pendingUpdateAnswer = null }
                if (pendingUpdateDialog === dialog) pendingUpdateDialog = null
            }
            pendingUpdateDialog = dialog
            if (runCatching { dialog.show() }.isFailure) {
                pendingUpdateDialog = null
                ticket.claim(notDelivered = true)
                mainHandler.removeCallbacks(timeout)
                answer(false)
            } else {
                Log.i(TAG, "UPDATECHECK：已弹询问框（$title），${UPDATE_ASK_TIMEOUT_MS}ms 内未作答按超时处理")
            }
        }
    }

    /** 放行正在等待的更新检查询问（按「没问到」定性）；没有等待者时 no-op。 */
    private fun cancelPendingUpdatePrompt() {
        val pending = synchronized(promptLock) {
            pendingUpdateAnswer.also { pendingUpdateAnswer = null }
        }
        val dialog = pendingUpdateDialog
        pendingUpdateDialog = null
        if (dialog != null) view.post { runCatching { dialog.dismiss() } }
        if (pending == null) return
        // Activity 没了 = 玩家没被问到（不是他拒绝），票据先落「没弹出去」再放行闩锁。
        currentUpdateAskTicket?.claim(notDelivered = true)
        pending(false)
        Log.i(TAG, "UPDATECHECK 询问被取消（宿主关闭）→ 按没问到处理（RESULT=3）")
    }

    /**
     * 把远端第二行的下载地址交给系统打开（原版 `Process.Start` + `UseShellExecute = true`，
     * `FunctionIdentifier.cs:2805-2809`）。
     *
     * **抛异常是接口的一部分**：调用方（`FileSystemOps.updateCheck`）据此把 RESULT 从 2 改写成 3，
     * 与原版 `Process.Start` 抛出后被 catch 覆盖那一条同解（`:2804` 先写 2、`:2829` 改成 3）。
     * 所以这里不 `runCatching`，也不给 Toast 兜底 —— 静默成功是最坏的读数。
     *
     * 线程：VM 线程。`ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK` 由 AMS 在主线程派发，
     * 后台线程发起是允许的（与 `UpdateManager.launchInstaller` 同一形状：非 Activity 上下文
     * 才补 NEW_TASK；这里没有 Activity 可依赖玩家点按，一律按新任务开）。
     */
    private fun openUpdateLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        view.context.applicationContext.startActivity(intent)
        Log.i(TAG, "UPDATECHECK：已把链接交给系统打开")
    }

    /**
     * 一次更新检查询问的结局票据。
     *
     * 为什么要有它：复用 [BlockingSaveMigrationPrompt] 只能交出布尔值，而原版语义要求把
     * 「玩家选否」（RESULT=1）与「没问到 / 框弹不出来 / 超时」（RESULT=3，原版的异常支
     * `:2827-2832`）分开。票据在**第一个**到达的终局事件（按钮 / 关框 / 超时 / 弹不出去）
     * 手上定性，之后的事件一律忽略；布尔答案本身仍由那把闩锁的一次性闸保证只生效一次。
     */
    private class UpdateAskTicket {

        private val claimed = AtomicBoolean(false)

        /** 超时到点（UI 线程写，VM 线程经闩锁读）。 */
        var timedOut: Boolean = false
            private set

        /** 框没能弹出去（Activity 已销毁 / show 失败）。 */
        var notDelivered: Boolean = false
            private set

        /** 抢到定性权返回 true；后续事件返回 false。 */
        fun claim(timeout: Boolean = false, notDelivered: Boolean = false): Boolean {
            if (!claimed.compareAndSet(false, true)) return false
            timedOut = timeout
            this.notDelivered = notDelivered
            return true
        }
    }

    /**
     * Activity 销毁：作废焦点会话、关闭输入队列、按原版退出口径清场音频，并打断 VM 线程。
     *
     * 幂等（`onDestroy` 与失败路径都可能走到）：[closed] 已置位就直接返回。
     * 清场对应 `Program.cs:97-104`（Bgm 与所有已建声道逐个 `close()`），用 `peek` 只查不建 ——
     * 没播过音频的会话不该因为退出凭空造出一张表。
     *
     * 必须打断 VM 线程：脚本可能正卡在 AWAIT 长睡（`SleepingTimerSink` 的 `Thread.sleep`，
     * 设计上支持 interrupt），只关输入队列的话它会把整段动画睡完（@BADAPPLE 6573 帧 ≈ 2 分钟）
     * 才察觉 [closed]。打断后 `waitInput` 的 await 会检查 [closed] 并转成
     * [InputSinkClosedException]，不会把「脚本错误」误上失败屏。VM 线程自然退出后
     * [thread] 仍非 null（Thread 对象不置 null），对已死线程 interrupt 是安全 no-op，无需特判。
     *
     * 2026-10-31 补充：打断现在也**能停纯计算长链**了 —— `VirtualMachine` 主循环每 65536
     * 条指令看一次中断位（`Vm.SHUTDOWN_CHECK_MASK`），抛出「执行被中断（宿主正在关闭）」
     * 由下面的 `closed` 分支按正常退出吞掉。此前只有暂停分支看中断位，换地图那种
     * 1.2 亿~2.9 亿指令的链会把整份 Program 带进下一次 Activity 的装载窗口（玩家屏：
     * 「上一局的内存还没释放（已用 422 MiB / 上限 512 MiB）」）。
     *
     * 装载中途被销毁时还有第二条线程要收口：`program.meta` 的并行解析（[inflightMeta]，
     * CMP-18）。interrupt + join([META_JOIN_MS]) 必须在 `shutdownForExit` 之前做完 ——
     * 否则旧线程继续读 49 MB 侧车，与新一代装载抢堆。
     */
    fun close() {
        synchronized(pauseLock) {
            if (closed) return
            closed = true
            // 暂停意图不留给下一条会话：本宿主关闭后再来的 setBackgroundPaused 会被 [closed]
            // 挡掉，这里清掉，避免「已死会话 + 暂停态」出现在任何读数里。
            backgroundPauseIntent = false
        }
        // 先放行装载线程上的迁移询问（若有），否则 [thread] 的 interrupt 只能打断闩锁等待，
        // 装配路径走到会话构造之后仍可能把「脚本错误」误上屏。
        // 本函数是**幂等清场**的唯一入口，跑在 onDestroy、失败收口、换局三条路上，
        // 契约与 [shutdownForExit] 同一条：**一步都不许抛**（2026-10-31 复查）。
        // 旧写法里前三步是裸调用 —— `cancelPendingSaveMigrationPrompt()` 要 dismiss 一个
        // 可能已随 Activity 消失的对话框，`sound.shut()` 会把 delegate 换成 Noop；任一步
        // 抛出去，下面的 `thread?.interrupt()` 与 [shutdownForExit] 就都不执行：VM 线程
        // 继续跑纯计算长链、session 的映射与 fd 全留着，而宿主已经被标记 `closed` ⇒
        // 下一次装载既等不到旧会话退出（[SessionHandoff] 那头还在等），也回收不到堆。
        runCatching { cancelPendingSaveMigrationPrompt() }
            .onFailure { Log.w(TAG, "关闭：迁移询问取消失败（继续收后面的）", it) }
        // UPDATECHECK 的询问复用同一把闩锁：Activity 没了而 VM 线程还等在框上时，
        // 不先放行就只能等 UPDATE_ASK_TIMEOUT_MS 的超时兜底，而 interrupt 对
        // CountDownLatch.await 是可中断的 —— 两条都能收口，但主动放行给的是**确定的**读数。
        runCatching { cancelPendingUpdatePrompt() }
            .onFailure { Log.w(TAG, "关闭：UPDATECHECK 询问取消失败（继续收后面的）", it) }
        // 死循环告警也挂同一把闩锁：不先放行就只能等下一次 10000 行边界才看到中断。
        runCatching { cancelPendingInfiniteLoopPrompt() }
            .onFailure { Log.w(TAG, "关闭：死循环告警取消失败（继续收后面的）", it) }
        // **先摘音频出口再打断**（顺序不能调）：interrupt 只能叫醒阻塞点（`queue` 的 await、
        // AWAIT 的 sleep、暂停位），纯计算语句要等 VM 主循环的中断检查点（每 65536 条指令）；
        // 这期间后到的 `PLAYBGM` / `PLAYSOUND` 会经 [TableSoundSink] 重新 play，而
        // `SoundChannelTable.closeAll()` 只 stop 不置 null，声道可复用 ⇒ 退出之后新建的
        // `MediaPlayer` 再没有任何人 release。原版 `Program.cs:97-104` 是进程退出前的最后
        // 一次清场，之后不可能再有脚本语句；本移植的 Activity 销毁与脚本线程是两条命，
        // 必须先把出口关死。
        runCatching { sound.shut() }
            .onFailure { Log.w(TAG, "关闭：音频出口关闭失败（继续收后面的）", it) }
        runCatching { thread?.interrupt() }
            .onFailure { Log.w(TAG, "关闭：VM 线程打断失败（继续收后面的）", it) }
        // CMP-18：装载中途销毁重建时，被遗弃的 `eratw-meta` 线程必须一起收口 ——
        // 否则旧线程还在解 49 MB 侧车，新一代装载又起一条，两条同时读进堆
        //（宿主注释自警的「第二次必然 OOM」）。interrupt + join(1s)：join 无上限会把一次
        // 侧车解析原样加到 onDestroy 上，够 ANR。
        val meta = inflightMeta
        if (meta != null && !meta.cancel(META_JOIN_MS)) {
            Log.w(TAG, "装载中途关闭：eratw-meta ${META_JOIN_MS}ms 内未收口（daemon，随进程退出）")
        }
        shutdownForExit()
    }

    /**
     * 装载期在跑的 `program.meta` 并行任务（CMP-18）。
     *
     * 为什么要在宿主上留一个引用：`assembleSession` 的局部变量在装载线程的栈上，
     * `close()`（UI 线程）够不到 —— 旧写法因此只能 interrupt VM 线程，meta 线程没人收。
     * 装配返回/抛错后由 `loadAndRun` 的 `finally` 清空。
     */
    @Volatile
    private var inflightMeta: ParallelTask<BootMetadata>? = null

    /**
     * 退出清场：放焦点、停全部声道、关会话。与 [close] 同一套幂等语义
     * （[AudioFocusSession.release] / `closeAll` / `GameSession.close` 各自幂等，重复调用无害），
     * 供 [close] 与 drive() 的 QUIT 正常退出路径共用。
     *
     * 为什么 QUIT 也要清：原版 QUIT = 退出进程，BGM/音效随进程自然终结；移植版脚本 QUIT
     * 只是 VM 线程正常返回，不显式停的话声道会一直播、焦点不释放。清完置 [session] 为 null
     * （与失败路径同口径），让 [submit] / 焦点回调对已退出的会话全部 no-op。
     *
     * 音频出口闸（`sound.shut()`）只在 [close] 一侧放：本函数由 QUIT / 失败路径在 **VM 线程
     * 自身**调用，返回后这条线程不再执行任何脚本语句，不存在「清场之后又出声」的窗口；
     * 而重启一局走的是新 [GameHost] 实例（新的 [DelegatingSoundSink]），这里不置闸也不会
     * 把下一局的音频一起闸死。
     */
    private fun shutdownForExit() {
        // **本函数一步都不许抛**（2026-10-31 复查）：它跑在三条路上 —— `onDestroy`（UI 线程）、
        // 装载/脚本失败的收口（VM 线程）、`QUIT` 的正常退出。任一步抛出去，后面的
        // `session.close()`（整局 Program 与 `.erbc` 映射通道的唯一持有者）与 `session = null`
        // 都不会执行：前者漏掉几十 MB 映射与一个 fd，后者让宿主**替一个已死会话续命** ——
        // 真机「返回键退出再进 → `load.baseline.high used=215MiB`」正是这个形状。
        // 所以逐步兜住，控制流必达末两行。
        //
        // **先摘渲染桥**再放会话：`attachConsoleFeed` 把 `GraphicsLayer`（里面那个
        // `backgroundAdmissible` lambda 强持 `session`）与 `SpriteBitmapSource`（持 `aux` →
        // `SpritePool`，外加最多 48 MiB 位图缓存）交给了**活得比会话久**的 `CanvasConsoleView`。
        // 视图不销毁，这两条边就在：VM 线程退了、`host`/`retiring` 都置空了，整局 Program
        // 仍然回收不掉 —— 2026-10-28 真机「导入存档 → 保存并重启」第二次装载起点
        // `used=293MiB`（干净进程实测 15 MiB）就是这条链，512 MiB 机型当场顶穿。
        // `detachGraphics()` 会 `invalidate()`，只能在 UI 线程直接跑；QUIT 路径是从 VM 线程
        // 走到这里的，所以按线程分派（post 出去的清场晚几毫秒无妨：下一次装载的
        // `attachGraphics` 本来就会把这两个字段覆写掉）。
        runCatching {
            val clear = Runnable {
                // LIVE-REFLOW（契约 §6 L6）：视口回调与渲染桥同寿命 —— 摘在**换屏动作之前**、
                // 且与 `detachGraphics()` 同一发 UI 线程清场里做（本函数可能从 VM 线程走到，
                // 所以按线程分派；跨线程裸写 View 字段既没有可见性保证，也没有道理）。
                // 摘完之后视口再变宽也不会打这条链；关态这一行写的是本来就为 null 的字段，
                // 不产生任何新行为。
                view.onViewportChange = null
                view.detachGraphics()
            }
            if (Looper.myLooper() == Looper.getMainLooper()) clear.run() else view.post(clear)
        }.onFailure { Log.w(TAG, "退出清场：摘渲染桥/视口回调失败（继续收后面的）", it) }
        // 解码槽（线程 + 队列 + 在途台账）随会话一起收（`SpriteBitmapSource.close` 幂等：
        // 置收口标志 → 清队列与台账并打断在跑的那一次解码 → 摘解码就绪监听）：
        // 视图侧可能还持着 `frames`（detach 是 post 出去的、还没跑），但解码槽只往后写缓存，
        // 收口之后 `prefetchAsync` 与 `frameOf` 的未命中支都被队列拒收并静默丢弃 ——
        // 不会把已退出的会话留在后台解码，也不会让残留的就绪监听把视图树钉在主线程队列上。
        // 置空 [graphicsWiring] 同时放掉「最近一次装配」那条引用（含两级位图缓存）。
        runCatching { graphicsWiring?.frames?.close() }
            .onFailure { Log.w(TAG, "退出清场：位图缓存关闭失败（继续收后面的）", it) }
        graphicsWiring = null
        // 堆外 PNG 载荷通道（OOM-B）：收掉本会话还活着的映射与临时文件。
        // 各缓冲换代时已逐份 close 过，这里收的是「降级后没再被碰过」的残留。
        runCatching {
            (session?.store?.aux?.peek<PngPayloadStore>(PNG_PAYLOAD_SLOT) as? AutoCloseable)?.close()
        }.onFailure { Log.w(TAG, "退出清场：PNG 载荷通道关闭失败（继续收后面的）", it) }
        runCatching { focus.release() }
            .onFailure { Log.w(TAG, "退出清场：音频焦点释放失败（继续收后面的）", it) }
        val audioFaults = runCatching {
            session?.store?.aux?.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT)?.closeAll() ?: 0
        }.getOrElse {
            Log.w(TAG, "退出清场：声道关闭失败（继续收后面的）", it)
            -1
        }
        if (audioFaults > 0) {
            // 逐声道隔离之后这里不再是「一条坏了全没关」，而是**确实有几条**没放掉：
            // OEM 的 MediaPlayer 在非法状态下 release 会抛，声道对象本身已经不可达，
            // 只是平台侧句柄要等进程退出。落一行读数，别让它变成无声的 fd 泄漏。
            Log.w(TAG, "退出清场：$audioFaults 条声道未释放（平台 release 抛异常，已逐个隔离）")
        }
        // 会话最后关，且**单独**兜：`close()` 要释放映射通道，失败时返回值给 false（设计内），
        // 抛异常则是意外 —— 两种都只记日志，引用照常置空。
        val released = runCatching { session?.close() }
            .onFailure { Log.w(TAG, "退出清场：会话关闭抛异常（引用照常置空）", it) }
            .getOrNull()
        if (released == false) {
            // CMP-19 的前提不成立（两条惰性回读路里至少一条没 pin 住：语句段局部串表超 pin
            // 上限，或段 2 记录窗/串表在装载期退成了换块形态）：通道保持打开是**有意的**，
            // 宁可多留 1 fd 也不把惰性解码弄坏 —— 关错的后果是玩几十分钟后凭空一条
            // 「映射已关闭」。真机上的 fd/smaps 读数见 TODO(verify)。
            Log.w(TAG, "产物通道未释放：惰性回读仍有未 pin 住的段（语句段串表超 pin 上限或段 2 换块），fd 保留优先")
        }
        session = null
        // 报错节流清账（EP1 波 L6；组长接线）：键是会话身份，引用置空后旧会话的 hash 理论上
        // 会被新会话复用（JVM 不保证不复用地址）—— 那一发会把上一局的账带进新局，最坏形状是
        // **把新一局的第一条真实现场当成重复吞掉**。这一句就是把那条理论尾巴剪干净；本体自带
        // runCatching，且 `shutdownForExit` 一步都不许抛的纪律不受影响。
        ErrorReportCollector.resetThrottle("shutdownForExit")
        // `lastScreen` 是整块行缓冲（含 HTML 浮岛）的唯一强引用，装载时由 loadAndRun 钉上。
        // 会话都关了还留着它，等于让「已死会话」跟着宿主对象一直活着 —— 真机上宿主被
        // 旧 Activity 壳钉住时，这几十 MB 就再也收不回来（2026-10-31：退出重进后
        // `load.baseline.high used=215MiB`，干净进程 15 MiB）。视图自己另有一份 `screen`，
        // 失败屏/退出提示照常画，不受这里影响。
        lastScreen = null
    }

    /**
     * 装载线程等「上一局宿主」全部退出，最多 [SESSION_HANDOFF_WAIT_MS] 毫秒。
     *
     * 只在 [loadAndRun] 开头（本线程）调用，UI 线程不睡。判据与取舍见 [SessionHandoff]：
     * - 等到了 ⇒ 堆基线读到的是这一局自己的占用，硬闸不再被旧会话误判；
     * - 等不到 ⇒ 只落一条日志继续走既有硬闸。**不**把等待上限拉长成第二条「重启停止」屏：
     *   玩家切一次 QQ 回来已经等了装载，再把无出口的等待叠上去没有收益；真异常由
     *   「上一局的内存还没释放」那张屏说真话（它自带「结束应用」出口）。
     *
     * 等待期对玩家不可见是有意的：装载覆盖层本来就是 indeterminate 的「正在装载游戏内容…」，
     * 正常情形（旧链已被 interrupt 叫停）只多几毫秒，不值得为它再开一条 UI 状态。
     */
    private fun awaitPreviousHosts(trace: LoadTrace) {
        val waiting = SessionHandoff.othersAlive(this)
        if (waiting <= 0) return
        Log.i(
            TAG,
            "load.handoff 上一局还有 $waiting 个会话未退出，先等它让出堆再装载" +
                "（上限 ${SESSION_HANDOFF_WAIT_MS}ms）",
        )
        val beganNs = System.nanoTime()
        val gone = SessionHandoff.awaitOthersGone(this, SESSION_HANDOFF_WAIT_MS)
        val waitedMs = (System.nanoTime() - beganNs) / 1_000_000L
        if (gone) {
            Log.i(TAG, "load.handoff.done 上一局已退出，等待 ${waitedMs}ms")
        } else {
            Log.w(
                TAG,
                "load.handoff.timeout 仍有人没退出（等满 ${waitedMs}ms），继续装载 —— " +
                    "堆基线硬闸会兜底",
            )
        }
        trace.mark("handoff")
    }

    private fun loadAndRun(
        gameRoot: File,
        screen: ConsoleScreen,
        metrics: ScriptPixelMetrics,
        textMeasure: AndroidTextMeasure,
    ) {
        val trace = LoadTrace(TAG, logEnabled = isDebuggable())
        // R3 结构化事件：装载相位同时进常开事件环/滚动文件（release 也记）。监听体由
        // LoadTrace 兜住异常，回环失败只丢这一条事件，不拖装载。
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
        }
        lastScreen = screen
        lastTrace = trace
        // 本轮从头开始：上一轮失败留下的族标记不能带进这一轮 —— 失败屏据它们决定按钮文案
        // （「重启应用」vs「结束应用」）与**要不要自动降档**，带旧值会把一次预检拒载写成
        // 「装载中真 OOM」而自动降档（正是本刀要修的那条：明知装不下还硬试一次）。
        lastHeapFailureStage = HeapFailureStage.NONE
        lastFailureWasStaleSession = false
        lastFailureScreenKind = FailureScreenKind.LOAD
        // 并行装载（2026-10-23 性能）：`program.erbc` 解码（~17s，在 assembleSession 里）与
        // `program.meta` 解析（~8s）读的是**两个不同文件**、两套互不依赖的解析器 —— 串行做
        // 等于把这 8 秒白等。句柄在下面起线程，当参数交进 assembleSession，在那边 join。
        trace.mark("start")
        try {
            // 跨 Activity 交接（[SessionHandoff]）：系统回收旧 Activity、同进程重建新的时，
            // 旧一局的 VM 线程可能还在跑（换地图那条 1.2 亿~2.9 亿指令的长链就是玩家报告
            // 的形状）。旧口径下新装载立刻进堆预检，GC 后基线被旧会话抬到 422 MiB，直接撞
            // 「上一局的内存还没释放」硬闸。两件事分开做：
            // - 旧链自己在 `close()` 的 interrupt 后毫秒级退出（Vm 的 SHUTDOWN_CHECK_MASK）；
            // - 本线程在**堆预检之前**等它退净，把「谁先谁后」钉成确定性，而不是靠时间差赌。
            awaitPreviousHosts(trace)
            if (closed) return
            val dir = File(gameRoot, ARTIFACT_DIR)
            // UI 线程的 artifactsAvailable 与这里隔了一次线程切换：文件可能已被删/换成目录。
            // 这里再确认一次，缺失统一走 NoSuchFileException → MISSING_ARTIFACT 文案。
            val programFile = File(dir, PROGRAM_FILE).also(::requireArtifact)
            val metaFile = File(dir, META_FILE).also(::requireArtifact)
            // 装载前堆预检（M6-GAMEROOT）：只读 .erbc 头 + 侧车大小，512 MB 堆机型在这里就出文案，
            // 不进入解码把堆打碎。预检不足与真 OOM 共用同一条屏幕文案；M8-APPMEM 在句后追加实测读数。
            // 先收一次垃圾再取读数：预检的「上限」是硬阈值，但「已用」只有收完垃圾才代表真实余量。
            trace.gcHint("preflight")
            // 分片扁平池的归因开关必须在**预检之前**定下来：预检要按「本局是否开池」把
            // FlatPoolBudget 的实测增量预留记进需求（预检不许少报），装载链再按同一个值建池。
            // 只读这一次、两处共用 —— 两次 exists() 之间被人 touch 出「预检按关记账、装载按开建池」
            // 正是这个预算要堵的两张皮（见 FlatPoolBudget 类头）。
            val flatStatementsOn = flatStatementsOverride
                ?: File(view.context.filesDir, "flat-statements.on").exists()
            val estimate = ProgramLoader.estimateHeap(
                programFile.toPath(),
                metaFile.toPath(),
                flatStatements = flatStatementsOn,
            )
            trace.mark("preflight")
            // 真机联调开关：游戏根放一个空文件 force_load 时越过堆预检（默认不存在，不影响产品行为）。
            // 预检产物项是 1.1 倍估算（M10-MEM 按「催过 GC 的解码 live 高水位 0.726 倍 + 1.5 倍涨阶余量」
            // 重标；旧口径 2.0 倍在全量产物上恒判不足，把已经省下来的装载又挡回失败屏）。
            val forceLoad = File(gameRoot, FORCE_LOAD_MARKER).exists()
            // CMP-18：**闸先于线程**。meta 解析的并行任务只允许在闸放行之后起（[BeginAfterPreflight]
            // 把顺序钉成状态机：reject 之后 begin() 恒 null、一次都不调 create）。旧写法把
            // `ParallelMeta(metaFile)` 放在闸之前（构造即起线程），弱机走失败屏时后台还在解
            // 49 MB 侧车 —— 既违背「预检不足就不进入解码把堆打碎」，又和下一次装载抢堆。
            val metaGate = BeginAfterPreflight {
                ParallelTask(META_THREAD_NAME) { BootMetadata.read(metaFile.toPath()) }.start()
            }
            // 预检产物项 1.1 倍；debug 包一律尝试装载（真实峰值由失败屏/日志给出），
            // release 包保留硬闸，避免在用户机上白崩。兼容装载档同样放行：它是玩家在失败屏上
            // **自己按**「少占内存再试」进来的，且串行 + 逐段回收后的实际峰值远低于 1.1 倍静态
            // 估算 —— 硬闸对 compat 档若不放行，那颗按钮会被同一道闸原样再拦一次，成了死键。
            // 真 OOM 由下方 catch 收口（失败屏 + 错误报告），不会白崩。
            //
            // **甲族（预检拒载）**：走到这里是「装载根本没开始」—— 产物一个字节都没解、没有
            // 会话、没有映射、meta 闸也刚被 reject 掉（线程从未起）。所以：
            // ① 失败屏就地停住，**不自动降档**（判据见 [HeapFailureStage.PREFLIGHT_REJECTED]）：
            //    静态估算已经说「上限 < 需求」，再试一次只是拿进程去赌一次被杀；
            // ② 没有任何大对象要释放（[failure] 发布前无须先 close，见 [HeapFailureStage] 的
            //    「先释放再上屏」一节）；③ 屏上给真实读数与「还差多少」，出口由失败屏自己给。
            if (!estimate.sufficient && !forceLoad && !isDebuggable() && !compatLoad) {
                metaGate.reject()
                Log.e(TAG, "堆预检不足：$estimate")
                lastHeapFailureStage = HeapFailureStage.PREFLIGHT_REJECTED
                lastFailureScreenKind = FailureScreenKind.LOAD
                failure = heapPreflightStoppedText(
                    maxMiB = estimate.maxMemoryMiB,
                    requiredMiB = estimate.requiredMiB,
                    usedMiB = trace.usedMiB(),
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
            // 预检通过只说明「上限够」：把「已用之后的余量」也测出来，真机 logcat 里能直接看出
            // 是机型档位不够还是上一轮垃圾把余量吃掉了。只留读数，不改判据（估算产物项 1.1 倍）。
            if (estimate.requiredBytes > estimate.maxMemoryBytes - trace.usedBytes()) {
                trace.warn(
                    "load.preflight.tight 需=${estimate.requiredMiB}MiB 上限=${estimate.maxMemoryMiB}MiB " +
                        "已用=${trace.usedMiB()}MiB（仍继续装载，真 OOM 由失败屏兜底）",
                )
            }
            // 闸后起线程：meta 解析与 program 解码（在 [assembleSession] 里）并行，两文件、
            // 两套互不依赖的解析器；句柄记在 [inflightMeta] 上，`close()` 才收得到。
            // 兼容装载档不起并行任务：meta 串行到 program 解码完、垃圾收净之后读
            // （[assembleSession] 的 `metaTask?.await() ?: BootMetadata.read` 兜底正好承接），
            // 闸对象就此丢弃 —— [BeginAfterPreflight] 未 begin 也未 reject，没有活线程可漏。
            // 兜底闸：GC 之后装载起点仍占着 >[STALE_SESSION_BASELINE_MIB] 才开局，说明
            // **上一局还没让出堆**（干净进程实测 15 MiB；2026-10-28 真机那次是 276 MiB，
            // 解码到段3 就顶穿 512 MiB）。与其闯进解码把进程打死（被堆杀死时连错误报告都
            // 发不出去），不如按设计好的失败屏把原因和自救路径说清楚。
            // 这里自己催一次 GC：`gcHint` 的阈值是「上限的本档百分比」（512 MiB 机型保守档
            // 55% ≈281 MiB、放开档 65% ≈332 MiB，见 LoadTrace.gcTriggerPercent），150 MiB 的**垃圾**它不收 —— 不先清一遍就把垃圾算成占用，
            // 会把正常开局误杀。装载本身几十秒，多一次 GC 换判据诚实，值。
            if (!forceLoad) {
                Runtime.getRuntime().gc()
                var baseline = trace.usedMiB()
                if (baseline > STALE_SESSION_BASELINE_MIB) {
                    // 第二趟（2026-10-31）：一次 `gc()` 只把**当场不可达**的对象收掉。上一局
                    // 留下的那一坨若有任何一段要先走 finalizer / Cleaner 才真正释放
                    // （直接缓冲与映射缓冲的解分配器、被 WeakReference 引住的那类），
                    // 这一趟就会先把它们**入队**、还没执行 —— 读数因此停在 184 MiB 这种
                    // 「看起来是活对象」的数上。runFinalization + 再 collect 一次才把它们清完。
                    //
                    // 只在**已经要失败**的路径上付这一次（约 0.1~0.6 s），正常装载零成本；
                    // 收得回来就让玩家直接进去，而不是把「请结束本应用进程」推给他。
                    Runtime.getRuntime().runFinalization()
                    Runtime.getRuntime().gc()
                    val afterSecond = trace.usedMiB()
                    Log.w(
                        TAG,
                        "load.baseline.retry used=$baseline MiB → $afterSecond MiB（第二趟回收，" +
                            "闸=$STALE_SESSION_BASELINE_MIB MiB）",
                    )
                    baseline = afterSecond
                }
                if (baseline > STALE_SESSION_BASELINE_MIB) {
                    metaGate.reject()
                    Log.e(TAG, "load.baseline.high used=${baseline}MiB（上一局未让出堆，拒绝开局）")
                    // 族标记：失败屏据此把出口写成「重启应用」并在**第一次上屏**就点亮
                    // （本进程内重试解不开，见 [lastFailureWasStaleSession]）。
                    lastFailureWasStaleSession = true
                    lastFailureScreenKind = FailureScreenKind.LOAD
                    failure = staleSessionHeap(baseline)
                    trace.logPeak()
                    return
                }
            }
            val metaTask = if (compatLoad) null else metaGate.begin()
            if (!compatLoad && metaTask == null) {
                // 闸拒绝路径已在上面 return；走到这里只可能是调用点次序被改坏（防御，不留活线程）。
                // 族别按**乙**记（任务口径）：预检闸已放行才走到这里，装配随时可能开始 —— 与甲族
                // 「装载根本没开始」不同，这一支允许自动降一次档（判据全在 [HeapFailureStage]）。
                Log.e(TAG, "meta 闸状态异常：拒绝后仍试图起线程，按堆不足收口")
                lastHeapFailureStage = HeapFailureStage.LOAD_OOM
                lastFailureScreenKind = FailureScreenKind.LOAD
                failure = heapOomText(
                    peakMiB = trace.usedMiB(),
                    maxMiB = estimate.maxMemoryMiB,
                    stage = null,
                    oomDetail = "",
                )
                trace.logPeak()
                return
            }
            inflightMeta = metaTask
            val fresh = try {
                assembleSession(
                    gameRoot, programFile, metaFile, screen, metrics, textMeasure, trace, metaTask,
                    flatStatementsOn = flatStatementsOn,
                )
            } finally {
                // 元任务的生命周期只到装配返回：正常路径已在 await 里 join（cancel 变成一次
                // isAlive 读），异常/提前返回路径在这里收口（CMP-18）。兼容档没有并行任务，
                // null 直接跳过。
                inflightMeta = null
                if (metaTask != null && !metaTask.cancel(META_JOIN_MS)) {
                    Log.w(TAG, "eratw-meta ${META_JOIN_MS}ms 内未收口（daemon，随进程退出）")
                }
            }
            if (fresh == null) return
            // 第二道关闭闸（第一道在 [start]）。`assembleSession` 只在**段交接处**看 `closed`：
            // 最后一次检查之后还有 `GameSession` 构造（CSV 总装 + 变量池，几百毫秒）与返回，
            // Activity 恰在这段里销毁时，close() 已经带着 `session == null` 跑完 ——
            // 再往下走就是「关完之后挂上一个活的会话」，VM 线程、音频表与整份 `Program`
            // 活到进程结束，`close()` 的幂等守卫也不会再收一次。
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
            // 会话一挂上就把装载期攒下的提交补进 QueueInputSink（在 drive() 之前，
            // 第一处 waitInput 就能取到，见 [attachSession]）。返回 false ⇒ 挂接当口已被关闭：
            // fresh 已就地 close，后续装配（回调/图形/焦点/drive）一律不再做。
            if (!attachSession(fresh)) return
            // 逐页翻页读数（2026-10-23 真机取证）：口径 = 提交 → 下一次进入等待点。
            // 与 drive() 里按 handler 返回计的口径互补 —— 一个 handler 内连等几页时后者永远等不到。
            // 回调由 sink 在输入锁外交付（2026-10-30 修复：此前锁内同步调本回调，里面的落盘/
            // 堆采样把 UI 线程的下一次 submit 挡出 ANR）；仍在 VM 线程上同步跑，保住回合边界的采样点位。
            fresh.inputSink.onTurnReady = { ms ->
                // 本页函数流水（fix-pagetrailwire）：清零紧跟在本回调**返回之后**
                // （`QueueInputSink` 两个等待点的页边界 `try/finally`），所以这里读到的正是
                // 刚结束那一页；回调由 sink 在输入锁外、在 VM 线程上同步调 ⇒ **同线程直读**，
                // 逐字确定。快照另存一份给 DevTools `where` 的「上一页」行（等待点之后
                // 环已被清零，那时只有这份快照还留着上一页）。
                val pageText = pageFlowReadout()
                lastPageTrailText = pageText
                Log.i(TAG, "turn.next ms=$ms")
                // R3：回合边界一条结构化事件（每页至多一条，慢路径）。
                EventJournal.record("turn.end", mapOf("ms" to ms.toString()))
                synchronized(turnLogLock) {
                    if (turnLog.size >= TURN_LOG_LIMIT) turnLog.removeFirst()
                    turnLog.addLast("$ms ms @${System.currentTimeMillis()}")
                }
                // 分位并喂（obs-turnpct 波）：同一发读数进定长窗口算 n/p50/p95/max，
                // DevTools `turnlog` 末尾一行上屏；逐条读数上面两条一条没动。成本 = 一次数组写。
                turnPercentiles.record(ms)
                heartbeat?.markVm(SystemClock.elapsedRealtime(), "turn ${ms}ms")
                // 运行期堆轨迹（2026-10-30 玩家反馈「玩了一会儿就闪退」）：回合边界是天然的
                // 采样点 —— 玩家每翻一页一次，成本三次数系统调用。玩家报的那类死不给 Java
                // 异常，marker 文件加上这一次采样，是唯一能在下一次冷启动把它变成读数的东西
                // （见 [RunWatch]）。
                RunWatch.sampleTurn(view.context, "turn ${ms}ms", heartbeat)
                // 长页钉行（本页流水）：页尾这一发与页中压力检查点那一发共用
                // [PageTrailWire.longTurnLine] 的格式，只有 `longturn` 行尾多出的 `| page:`
                // 段是新的。门槛与页中那一路同一个 [RunTrail.LONG_TURN_MS]：低于它的页不钉，
                // 免得把 64 行滚动窗口灌满噪声、把真正临死前那几行挤掉。
                // 判据只看 `ms` 与阈值（不参与等待点判定、不改 `turn` 那一条既有读数）。
                if (ms >= RunTrail.LONG_TURN_MS) {
                    lastLongTurnPinAtMs = SystemClock.elapsedRealtime()
                    RunWatch.forceSample(
                        view.context,
                        PageTrailWire.longTurnLine(ms, longTurnFrame(), pageText),
                        heartbeat,
                    )
                }
                // 堆水位判据：ROM 不一定发 onTrimMemory，贴顶时自己收一次（判式见 RunTrail）。
                releaseImagePixelsIfHeapHigh()
            }
            // 长页自救口（2026-11-01 收件箱 `turn 58515ms`）：同一个回收判式此前**只挂在页尾**，
            // 而玩家报的卡死正是页尾迟迟不来的那一分钟 —— 越慢越回收不到、越回收不到越慢。
            // 这里把它同时挂进指令循环的周期检查点（VM 线程、两条语句之间，≈30 ms 一发，
            // 内部再按 [RunTrail.MID_PAGE_CHECK_INTERVAL_MS] 节流）。判式、冷却、水位
            // 与页尾那一发**共用同一套常量**，只多一个触发点，不改任何语义。
            fresh.runtime.onPressureCheckpoint = { midPageCheckpoint() }
            // 图形通路（M10-GFX）：位图解码器 + 语句层 sink + 绘制侧取图口。
            // 必须在 drive() 之前装好，否则开场那几页 HTML_PRINT 的 <img> 会先被当成纯文本交上去。
            val gfx = installGraphics(fresh, gameRoot)
            graphicsWiring = gfx
            // AWAIT 要真的让出。原版 `EmueraConsole.Await`（`EmueraConsole.cs:840-856`）一边
            // 强制重绘 + `Thread.Sleep(time)`，一边是死循环保护的**唯一**复位点
            // （`Process.cs:487-491` 只被那里调用一次）；缺省 NoopTimerSink 下 `@BADAPPLE`
            // 这种 6573 帧每帧 `AWAIT 16` 的动画会全速跑完、和配乐彻底脱节。
            // 本函数在 VM 线程跑，sleep 只挡脚本线程，渲染与输入各有自己的线程。
            fresh.runtime.execContext.timer = com.eratw.port.core.host.SleepingTimerSink
            // 音频焦点在表建好之后再申请；后台启动时不申请（等 onResume）。会话的 target
            // 按需 peek 表，所以先申请也不会「丢」事件 —— 丢了也只影响启动瞬间的空表。
            if (!activityPaused) focus.start()
            // ConsoleFeed 的首次 pump 会补交整屏快照；先挂桥再跑脚本不会丢开场行。
            // 埋点句柄上提到字段：`attachConsoleFeed` 的 post 里要打「boot.控制台挂载」，
            // 首帧后那一帧也要打 —— 两处都在 UI 线程、都晚于本函数的 `trace` 局部量作用域。
            bootTrace = trace
            // 队列本身就是 GC 根：这条 lambda 在闩锁上攥着 `fresh`/`gfx`，要等 UI 线程有空
            // 才跑。若期间宿主已 `close()`（重启/销毁都走这条路），照原样 attach 等于把
            // **已经死掉的会话**重新挂回活得更久的视图上 —— 整局 Program 就此回收不掉，
            // 所以下面先看清是否已被关掉（`closed` 是 @Volatile，UI 线程读到的一定是最新值）。
            view.post { if (!closed) attachConsoleFeed(fresh, gfx, screen) }
            // 首帧后一帧（UI 线程）：分辨 ready 之后那 ~13.2 s 是「渲染/布局」还是「脚本首轮」。
            view.post { bootTrace?.mark("boot.首帧后") }
            trace.mark("ready")
            trace.logPeak()
            com.eratw.port.android.DevToolsHost.startOnce(this, view)
            // 装载完成记账点（界面侧用它清「按过重试」的账）。覆盖层**不**在这里收：
            // 4.980 之后脚本先画「少女祈祷中」进度条，4.981 已删掉那段脚本进度条，
            // 在这里收会让 4.981 露空屏。收尾点在第一帧非空内容落笔之后
            // （CanvasConsoleView.onFirstContentFrame，接线在 MainActivity）。
            onConsoleReady?.let { cb ->
                onConsoleReady = null
                view.post { cb() }
            }
            drive(fresh)
        } catch (e: InputSinkClosedException) {
            Log.i(TAG, "输入队列已关闭，脚本线程退出")
        } catch (t: Throwable) {
            // 关闭竞态（CMP-18）：close() 会 interrupt `eratw-meta`，装配线程的 await 因此抛
            // InterruptedException —— 这是「Activity 已销毁」的正常退出路径，不该上失败屏
            // （屏也已经被销毁了）。真失败仍然按原口径落 [failure]。
            if (closed) {
                Log.i(TAG, "装载期间已关闭，脚本线程退出（${t.javaClass.simpleName}: ${t.message}）")
                return
            }
            // 失败点采样（2026-10-30）：OOM 发生在最后一次段采样**之后**的盲区时（真机实测：
            // 峰值行停在「program 引用范围校验 2/8 / 167 MiB」却报堆不足），失败屏需要一枚
            // 当口读数来区分「Java 堆容量不足」与「native/线程分配失败被归并成堆不足」。
            // 必须是 catch 里的第一件事：后面的分类/文案/报告路径都会做分配，先钉住现场。
            runCatching { trace.mark(LoadTrace.FAIL_STAGE) }
            // 失败归因先于文案：堆不足族要点亮失败屏的「少占内存再试」（describe 内部
            // 还会再 classify 一次，两边判据同源不会分岐）。
            //
            // **乙族（装载中真 OOM）**：预检闸放行了，是装载/装配（含首轮 `drive`）真的撞了
            // OOM。只有这一族允许「自动降一次档再试」（判据见 [HeapFailureStage.LOAD_OOM]）
            // —— 上限本来够，是实际峰值比估算高，换档确实有救。
            lastHeapFailureStage =
                if (BootFailures.classify(t).kind == BootFailureKind.HEAP_TOO_SMALL) {
                    HeapFailureStage.LOAD_OOM
                } else {
                    HeapFailureStage.NONE
                }
            // 文案先算好但**不发布**：`failure` 非 null = UI 侧「可以画失败屏了」的唯一信号，
            // 而这一刻整局 Program / `.erbc` 映射 / 会话都还活着（下一段诊断还要读它们）。
            // 贴边 OOM 之后先上屏再释放，画屏那几笔分配就是压垮进程的最后一根（2026-11 真机
            // 现场：失败屏没露面，致命 OOM 落在别处线程上）。→ 发布点挪到下面释放之后。
            val failureText = describe(t, trace)
            // 诊断与报告都在**会话还活着**时做（下面几段要读 `session.program` / `store.aux`），
            // 只有它们全部走完才允许释放。`finally` 兜住「诊断自己抛出去」的那一拍：那种情况下
            // 既不能把会话留着（那一局已经死了），也不能把失败屏吞掉（玩家又是什么都看不到）。
            try {
                // 方案①：装载/启动失败也落报告（装载轨迹前 24 段进「补充现场」）。
                // 图形池读数一并落盘：真机 OOM 需要它区分「G 系图像常驻」与装载期临时峰值。
                writeErrorReport(
                    "启动失败",
                    t,
                    trace.summaryLines().take(24) + listOfNotNull(graphicsPoolReportLine()),
                )
                // 死循环闸现场的语句级转储（ARCH1-P2a 下沉 core）：行产出归 core，
                // logcat 与 files/deadloop-site.txt 落盘归宿主（旧实现同分工）。
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
                // 失败路径也要留下峰值：OOM 就发生在某一段的当口，这一行是唯一的现场读数。
                runCatching { trace.logPeak() }
                Log.e(TAG, "启动失败", t)
                // 取图现场：`GCREATEFROMFILE` 按原版口径把一切失败折成返回 0、不抛不打印，
                // 脚本因此可以每帧重试到撞死循环闸而没有现场（2026-10-21 真机 RM_タイトル画像用意）。
                session?.store?.aux?.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)?.let {
                    Log.e(TAG, "取图现场 reason=${it.reason} path=${it.path}")
                }
                runCatching {
                    val ctx = session?.runtime?.evalContext as? com.eratw.port.core.fn.BuiltinEvalContext
                    val t = ctx?.aux?.peek<ImageLoadTrace>(IMAGE_LOAD_TRACE_SLOT)
                    Log.e(TAG, "取图现场(VM侧) host=${ctx?.host?.javaClass?.simpleName}" +
                        " reason=${t?.reason ?: "无记录"} path=${t?.path}")
                }
                // 死循环指令现场：`GCREATEFROMFILE` 一次都没被调用（取图现场=无记录）却报在它的行号上，
                // 说明是**同一条指令反复派发、pc 不前进**，所以要把 pc 上的四个词打出来定位操作码。
                runCatching {
                    val e = t as? com.eratw.port.core.code.StepLimitExceeded
                    val s = session
                    if (e != null && s != null && e.pc >= 0 && e.pc < s.program.instructionCount) {
                        // 走 program.op/a/b/c（按页取指）而不是 `program.code`：映射装载下
                        // `code` 会把 78.2 MiB 指令流整体物化回堆内 —— 失败现场往往已经贴着上限，
                        // 这条诊断本身就成了压垮进程的最后一笔（M5-BOOT）。
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
                // 失败后立刻清场：同一进程内再次进入（Activity 重建 / Robo 反复启动）不能带着
                // 上一轮的部分装载对象。云真机实测第二次 `load.start used=223MiB`，随后必然 OOM。
                // 用 shutdownForExit 而非裸 session.close()：失败时可能已播上 BGM/音效，音频也要停。
                runCatching { shutdownForExit() }
                // **先释放再上屏**：`failure` 是 UI 侧「可以画失败屏」的唯一信号，所以它必须是
                // 这里**最后**一笔 —— 贴边 OOM 时，失败屏那几笔分配（Compose 重组、行缓冲快照）
                // 若发生在 Program/映射/会话还活着的时候，就会把进程就地打死（2026-11 真机：
                // 玩家什么都没看到，致命 OOM 落在别处线程上）。释放之后堆回到几十 MiB，画屏安全。
                failure = failureText
            }
        }
    }

    /**
     * `program → meta → session` 三段装配，返回后本帧只留会话。
     *
     * `Program` 与 `BootMetadata` 都只在**本函数的一帧**里以局部引用存在：装配一返回，
     * 两个大对象的引用就只剩 `GameSession` 里那份，装载函数（要陪整局跑）不再有同名局部
     * 陪着它们。装载层本身不持整份产物字节（`loadMapped` 按段流式解码，窗口用完即丢），
     * 所以宿主侧不存在「ByteArray 与 Program 同时持有」的第二份 —— 能省的只有阶段交界的
     * 垃圾（[LoadTrace.gcHint]）与这层引用生命周期。
     *
     * @return null = 装配期间 Activity 已销毁（`closed`），调用方直接退出。
     */
    private fun assembleSession(
        gameRoot: File,
        programFile: File,
        metaFile: File,
        screen: ConsoleScreen,
        metrics: ScriptPixelMetrics,
        textMeasure: AndroidTextMeasure,
        trace: LoadTrace,
        /**
         * 并行 meta 任务；**兼容装载档传 null** —— meta 在下面串行读（program 解码完、
         * 垃圾收净之后），同一条 `?: BootMetadata.read` 兑底承接，读数口径不变。
         */
        metaTask: ParallelTask<BootMetadata>?,
        /**
         * 本局是否开分片扁平池（T2-64）。**由 [loadAndRun] 在堆预检之前读一次**、原样传进来：
         * 预检要按同一个值把 114 MiB 预留记进需求（[ProgramLoader.estimateHeap] 的
         * `flatStatements`），装载链再按它建池。本函数不许再读一次开关 —— 两次读数之间被人
         * `touch files/flat-statements.on` 就会出现「预检按关、装载按开」的记账缺口。
         */
        flatStatementsOn: Boolean,
    ): GameSession? {
        // 把宿主的埋点交进解码器（M10-MEM 判据③）：段边界与索引趟每 1/8 条带各回调一次，
        // 否则真机 OOM 落在 load.program 段**内部**时，失败屏的峰值行只能显示上一段的读数。
        // 折叠审计开关（2026-10-22 死循环复盘方案 #2）：`files/fold-audit.on` 存在即开——
        // 真机没有环境变量通道，debug 包走 run-as 开关文件（与 pool-audit.txt 同目录）：
        //   adb shell run-as com.eratw.port.android touch files/fold-audit.on
        // 必须在装载**前**设置：折叠发生在装载期，事后开无读数；读数在
        // ProgramDiagnostics.auditStatementPoolAt（core/diag）里随对账一起落盘（RED = 判等不完整折叠，死循环同型病）。
        StmtInternAudit.enabled = File(view.context.filesDir, "fold-audit.on").exists()
        // T2-64 分片惰性扁平池：**设备侧默认关**。2026-10-29 真机实测（vivo V2199A / 512 MiB）：
        // 进游戏后 Dalvik Heap 从 141.9–149.5 MiB（关）涨到 256.2–260.7 MiB（开）⇒ +114 MiB，
        // 而片数与片大小都已证明无关（32/64 片、1024/4096 条/片读数一致）—— 归因未清前不拿玩家设备冒险。
        // 桌面/测试默认开（拿 when(op) 表驱动收益）；归因与优化见 CONTRACT-T2-64 §10.9。
        //
        // 归因开关（2026-10-30）：两种入口，缺省都**不启用** ⇒ 与上面的 `flatStatements = false`
        // 逐字等价（默认关，产品行为不变）。
        //   ① debug 包：`adb shell run-as com.eratw.port.android touch files/flat-statements.on`
        //   ② release 包（isDebuggable=false，run-as 不可用）：启动 Intent extra
        //      `adb shell am force-stop com.eratw.port.android`
        //      `adb shell am start -n com.eratw.port.android/.MainActivity --ez eratwFlatStatements true`
        // 装载发生在冷启/重启里，开关必须在装载**前**落下；读数见 LoadTrace、
        // `adb shell dumpsys meminfo` 与 [sampleMemoryIfNeeded] 的 `flat=` 列。用完删文件 / 不带 extra 重启即恢复默认。
        flatStatementsActive = flatStatementsOn
        val program = ProgramLoader.loadMapped(
            programFile.toPath(),
            compat = compatLoad,
            flatStatements = flatStatementsOn,
        ) { stage ->
            trace.mark(stage)
            // 解码期几个「临时胖子」段之后催一次 GC：表达式结点是先全量建出来
            // （`readNodesIndexed`），紧接着 `enableLazyNodes` 就把数组元素置空 ——
            // 那 130-260 MiB 当场变垃圾，可 ART 只在**自己**要分配时才收；512 MiB 机型上等它
            // 下次扩容就已经顶到天花板（真机轨迹：段2 池根表 437 MiB → 段3 局部串池 508 MiB → OOM）。
            // `gcHint` 自带阈值（已用不到上限的本档那条线就不收），所以这只会在
            // 真贴边时付一次 GC，不会把装载拖成十几次全量回收。
            // 兼容装载档（2026-10-28）：逢段必收（[LoadTrace.forceGc]）—— 兼容档的任务就是
            // 用装载时间换堆余量，GC 时机噪声（桌面实测同一产物 342→725 MiB）在 512 MiB 挡
            // 本来就贴边的前提下必须砍掉。
            if (compatLoad) {
                trace.forceGc("decode")
            } else if (
                stage.endsWith("池根表") || stage.endsWith("局部串池") || stage.endsWith("语句记录 8/8")
            ) {
                trace.gcHint("decode")
            }
        }
        trace.mark("program")
        // 诊断：表达式池的「不同形态数 / 遍历结点数」比值（决定边解析边规范化能省多少 GC）。
        program.internDiagnostics?.let { Log.i(TAG, "load.intern $it") }
        if (closed) return null
        // 2026-10-22 死循环侦破探针：装载完成后（**执行开始前**）对 FOR 区语句池下标
        // 140944..140952 做多实例直读对账。桌面 stmt-at 口径：#140946 = RESULT[0]=IntLiteral(0)@L-1、
        // #140948 = I[0]=IntLiteral(0)@L7、#140950 = I[0]+=1@L7；真机撞闸现场 statement(140948)
        // 渲染成 RESULT[0]=GLOBAL[0]@L7 —— line 字段对、节点引用错。本探针一锤定音错发生在哪层：
        //   双实例一致且形态同错 ⇒ ART 装载解码确定性错（与执行无关，转查解码层）；
        //   实例2（新装载）形态=桌面基准 ⇒ 执行用的实例1在运行期被污染（转查执行期写坏）。
        //
        // **必须显式打开，缺省关**（2026-10-22 首启 2 分钟复盘）：本探针为了「同一份产物在
        // 不同物化序下的形态」要新开**两次** `loadMapped`，而惰性语句池的「省」只省了结果对象，
        // 段 0/2/3/4/6/7 的索引趟一步都不能少 ⇒ 一次探针 ≈ 一次完整装载的耗时。
        // 默认开着等于每次启动装四遍产物：JVM 实测单次 6.5 s，真机上就是「每次都要两分钟」。
        // 开关与 fold-audit.on 同目录、同形状：
        //   adb shell run-as com.eratw.port.android touch files/pool-audit.on
        // **一次性**（2026-10-23 性能收口）：本探针的代价是**多装两遍整份产物**
        // （注释见下，JVM 单次 6.5 s ⇒ 真机「每次两分钟」）。开关文件留在用户机上就会
        // 让**每一次**冷启/重启都变成多装 N 遍 —— 所以跑完立刻删掉，只留一份读数。
        // 需要再取证时重新 touch 一次即可。
        val poolAuditMarker = File(view.context.filesDir, "pool-audit.on")
        if (poolAuditMarker.exists()) {
            Log.w(TAG, "pool-audit.on 存在：本次装载会多装 2 遍产物（预计 +2 倍装载耗时），跑完自动删除")
            runCatching {
                // 多实例对账下沉 core（ARCH1-P2a）：行窗口与第三实例下标 = CMP-15 排查口径
                // （FOR 区 140944..140952、第三实例先物化 #140948），输出与旧实现逐字节一致。
                val lines = ProgramDiagnostics.auditStatementPoolAt(program, programFile.toPath(), 140944, 140952, 140948)
                lines.forEach { Log.e(TAG, it) }
                runCatching {
                    File(view.context.filesDir, "pool-audit.txt").writeText(lines.joinToString("\n"))
                }
            }.onFailure { Log.e(TAG, "pool-audit 探针失败", it) }
            runCatching { poolAuditMarker.delete() }
                .onSuccess { Log.w(TAG, "pool-audit.on 已消费并删除（一次性探针）") }
        }
        // 侧车按行流式解析，但 49 MB 文本仍会在堆上留下大量短命 String；先收再进会话装配。
        // **交界恒收**（不走 gcHint 的 55% 阈值）：真机两张「启动失败：堆不足」的实测读数
        // 是 267 / 265 MiB，恰好压在 512 MiB 挡的阈值 281 MiB 下面 14 MiB —— 阈值把贴边的
        // 机型全放过去了，然后让它们在本链最大的一笔分配（侧车解析 ≈170 MiB）里顶穿。
        // 判据与桌面同挡位实测见 `LoadGcPolicy.collectAtSeam`（装置 `:core-vm:deviceTierProbe`）。
        trace.forceGc("meta")
        // 并行档：meta 是上面并行装的那一份（meta 任务起于 program 解码之前）：program 解码
        // （~17s）远长于 meta（~8s），join 正常零等待。
        // 兼容档（metaTask = null）：在**这里**串行读 —— program 解码完、垃圾收净之后，
        // 46.8 MiB 侧车的短命 String 不再与解码峰值叠加（用装载时间换堆余量的主项之一）。
        val metadata = metaTask?.await() ?: BootMetadata.read(metaFile.toPath())
        trace.mark("meta")
        if (closed) return null
        // 会话装配会建 CSV 总装、变量池与运行期表，是本链最后一次大分配；上一段的解析垃圾先收掉
        // （同 `meta` 交界：恒收，判据见 `LoadGcPolicy.collectAtSeam`）。
        trace.forceGc("session")
        val fresh = GameSession(
            gameRoot = gameRoot.toPath(),
            program = program,
            metadata = metadata,
            // RT2-M0 / R7-C4：mods 补丁装载接线。此前生产 GameHost 不传这两个参数，只有测试在传
            // ⇒ 正式包的「导入 Mod → 重启生效」链是断的。mods 目录与导入端同源
            // （ModStore.modsDirFor）；base SHA 取 .meta 侧车声明的 program-sha256
            // （GameAssetInstaller 解包时已按同值核对过 program.erbc，重启不再哈希 100+ MiB）。
            // 侧车无声明（"-"/旧产物）⇒ 两参皆 null = fail-closed：无身份校验的补丁不加载，
            // 会话行为与接线前逐位一致（GameSessionStageMarkTest 的无补丁轨迹不变）。
            modsDirectory = metadata.programSha256?.let { ModStore.modsDirFor(gameRoot).toPath() },
            baseProgramSha256 = metadata.programSha256?.lowercase(),
            consoleWidth = screen.clientWidth,
            // 死循环保护（ENGFIX-3/4，2026-11-05）：官方「墙钟 + 弹框问玩家、从不自动中止」语义
            // 在 core 落地（VmConfig.infiniteLoopAlertTimeMs + VmHost.askInfiniteLoop）；
            // 阈值由 GameSession 从会话生效配置的 InfiniteLoopAlertTime 读（缺省 5000ms，置 0 关），
            // 弹框经 [infiniteLoopPrompt] 接 UI；步数闸保留 10 亿极端兜底
            // （LoopGuardCause.DeviceBudget，ADR-005 登记项）。取值依据见
            // `GameSession.PRODUCTION_MAX_STEPS_PER_RUN` / `PRODUCTION_MAX_RUN_MILLIS` 的 KDoc。
            maxStepsPerRun = GameSession.PRODUCTION_MAX_STEPS_PER_RUN,
            maxRunMillis = GameSession.PRODUCTION_MAX_RUN_MILLIS,
            soundSink = sound,
            inputState = keyInput,
            // HOST-05：写放行根 = 游戏根。原版 SAVETEXT / OUTPUTLOG 的路径都相对
            // `Program.ExeDir`（FunctionMethodCreator.cs:7936-8017、EmueraConsole.cs:3444-3484），
            // 而本仓的游戏根 = `GameStorage.gameRoot()`（外部私有目录优先、内部 filesDir 兜底），
            // 首次启动 assets 就解包到它里面，故本身可写；越界仍由 RootedFileOps 拒写。
            writeRoot = gameRoot.toPath(),
            stageMark = { s -> trace.mark(s) },
            // 脚本屏幕像素（GFX-06）：CLIENTWIDTH/CLIENTHEIGHT 喂真机实测值，
            // 状态页/体型图/列表底色的像素换算不再缩在出厂 760。
            // 两个像素是设备像素：会话按 deviceFontPixelSize 把它们折回脚本 px（语料拿
            // CLIENTWIDTH() 开 CG 画布，渲染层再按同一比例折设备像素 —— 不折就放大整倍）。
            // 配置字号这一路**不给**（2026-10-29）：会话按生效配置自报（GameSession.scriptFontPixelSize），
            // 因为语料的指令按钮网格拿 GETCONFIG("フォントサイズ") 当尺排格、又拿 HTML_STRINGLEN
            // 量宽 —— 两把尺必须同一条，喂 page-fit 实测字号会让每个按钮少占一半宽度、
            // 一行攒十几个就整排错位。实测字号还随版面行数变，而这份快照是冻结的。
            // 这里的 deviceFontPixelSize 只当折算比例，不是喂给脚本的配置字号。
            clientWidthPx = metrics.clientWidthPx,
            clientHeightPx = metrics.clientHeightPx,
            deviceFontPixelSize = metrics.deviceFontPixelSize,
            // R5.1 同源回填：`WindowX` 吃 View 反折出的**实际版面宽**脚本 px，
            // GameSession 在三层链之后回填（0 = 未布局 ⇒ 回填层不写）。
            windowWidthScriptPx = metrics.windowWidthScriptPx,
            // FN-04：分段量宽接 Android 真字体（字号由 core 侧按配置尺给出）。
            textMeasureRun = { req ->
                textMeasure.measurePixels(req.text, req.fontName, req.fontSize, req.styleMask, req.charSpace)
            },
            // AA 画/老虎机字符画错位修复（2026-11）：控制台折行与屏上推进同一把尺
            // （assets sidecar 的真实 advance）。`SETFONT "Saitamaar"` 的比例微调空格与
            // `SETFONT "MS Gothic"` 的块元素宽度只有尺读得出；缺尺（尺侧缺表）时尺自己
            // 回落静态网格，装配侧不再有第二条退化路径。
            fontWidth = view.fontDisplayWidth,
            // M11-A-WIRE-2：`USE SAV FOLDER` 由 NO→YES 且有旧档时先问玩家（原版 YesNo）。
            // 缺省 AUTO_ACCEPT 会静默搬档，玩家在设置页改完开关后根本不知道文件动了。
            saveMigrationPrompt = saveMigrationPrompt,
            // ENGFIX-4：死循环告警（原版 YesNo 弹框）接 UI；缺省 NoUi 只留在装置面。
            infiniteLoopPrompt = infiniteLoopPrompt,
            // M11-E hostwire 应用层接线（其余 lane 明文要求由 app 侧喂）：真字形掩码通道。
            // 会话内链 RootedBuiltinHost -> ConsoleBuiltinHost（by delegate）-> 消费点
            // GraphicsMethods 的 GlyphMaskHostProvider 探测已由 GlyphHostWireTest 钉死；
            // 此前本层从未喂实例、AndroidGlyphMaskHost 全仓零构造点，该探测恒 null，
            // GDRAWTEXT 永久回落推进盒形态，与 GraphicsImage.DrawString 真字形绘制
            //（GraphicsImage.cs:154-188）不符。返回 null 时消费点仍回落推进盒，
            // 故本接线不改变字体栅格失败时的既有行为。
            // TODO(verify)：随包 ttf 仅单字面、真机字面对账未做（与 AndroidTextMeasure
            // 同源卡点，属组长真机对账面）；app 模块无 JVM 测试设施（仅 androidTest），未附单测。
            glyphMaskHost = AndroidGlyphMaskHost(view.context),
            // 引擎级函数屏蔽（`EngineFunctionPolicy`）：出厂口径屏蔽标题彩蛋 `@BADAPPLE`
            // （6573 帧 `AWAIT 16` ≈ 105 秒的纯装饰动画）。语料不进仓，上游那份的
            // `CALL BADAPPLE` 可能是活的 —— 屏蔽必须由引擎做，不能靠玩家手工注释语料。
            // 设置页的「标题彩蛋」开关关掉时传 emptySet()（全部放行）。
            // 读数由调用方在 UI 线程取好经构造参数 [disableBadApple] 传入（装配期在 VM 线程上，
            // 这里不许直接摸偏好表；照 [liveReflow] / failureCopy 的先例）。
            disabledFunctions = if (disableBadApple) {
                EngineFunctionPolicy.DEFAULT_DISABLED
            } else {
                emptySet()
            },
            // LIVE-REFLOW（契约 §6 L6）：核心侧开关，与视口侧的 `CanvasConsoleView(liveReflow = …)`
            // **同源**（都由 `MainActivity.appliedLiveReflow` 传入，见本类构造参数 [liveReflow] 的 KDoc）。
            // 关 = `resizeConsole` 恒 false、sink 不维护 journal，宽度冻结语义逐字不变。
            // 这里**不**再读一次 UiPrefs：本值必须是「本局装配那一刻视口用的同一个布尔」。
            reflowConsole = liveReflow,
            // ECHO-INPUT（2026-11-06 引擎侧收口）：玩家侧缺省关 —— 引擎在输入交付点
            // 就不产生回显显示行，屏上看不到编号、冻结段前后也没有帧差；少掉的那行由
            // 引擎行账本记进 LINECOUNT 并在 deleteLine 折算，脚本 CLEARLINE 不偏离。
            // 读数照 [disableBadApple]/[liveReflow] 先例由调用方在 UI 线程取好传入（构造参数
            // [echoInput]）—— 本行跑在 VM 线程上，不许在这里摸 SharedPreferences。
            // 装置/夹具不经过这里 ⇒ 仍是 GameSession 的 core 缺省 REPLICA（对 E2E 基准）。
            echoInputMode = if (echoInput) {
                EchoInputMode.REPLICA
            } else {
                EchoInputMode.SUPPRESSED
            },
        )
        trace.mark("session")
        // 本页函数流水（观察面接线）：`PageFunctionTrail` 只记录、不自挂；三处进帧钩子与两个
        // 页边界清零点靠这里把**同一个实例**挂上去（缺省 null = 零行为，详见 [wirePageFunctionTrail]）。
        // 必须在 `drive()` 之前 —— 第一页脚本跑起来时就得已在记。
        wirePageFunctionTrail(fresh)
        // UPDATECHECK 的宿主面（网络/取流/询问/开链接）：会话建成后立刻补上，早于任何脚本语句
        // （构造点在 GameSession 第 8 步，本路不许改 GameSession ⇒ 事后装配，理由见函数 KDoc）。
        wireUpdateCheckHost(fresh)
        // 存档失败的堆贴顶求援：同一时机（会话建成后、任何脚本语句之前）。
        // 与上一条同因 —— FileSystemOps 的构造点在 GameSession 里，本路不许改，故事后装配。
        wireSaveMemoryReclaim(fresh)
        // 4.981 语料的 CALLSHARP 本地方法表（本版为空，不内置 .NET 插件）：同样只能事后装配
        // （注册表挂 store.aux，store 由 GameSession 内部创建）。必须在 drive() 之前 —— 紧随其后的
        // 装载趟裁决要吃终表；EVENTCOMEND 随第一次行动就会到。
        wireCorpusNativeMethods(fresh)
        // CSHARP-WIRE C2/C3（2026-11-06）：CALLSHARP 的**装载趟裁决** + 警告统一上屏。
        // 会话装配段内的三段严格序：wireCorpusNativeMethods（注册终表）→ prefillSharpLinks
        // （扫 CALLSHARP 常量名、绑 SharpLinkTable、未命中收警告）→ drive()（任何脚本语句
        // 之前）。「本地方法表必须在装载趟扫描时已可用」（CONTRACT-CSHARP-WIRE C1）由
        // 本序保证；扫描点为何落在装配段而不是容器解码趟，证据链见
        // prefillSharpLinks（core/fn/PluginMethods.kt）的 KDoc。警告呈现走既有日志通道
        // （原版 ParserMediator.cs:143-158 装载完统一显示的相位；会话读数面 =
        // GameSession.sharpLoadWarnings）。
        val sharpWarnings = prefillSharpLinks(fresh.store.aux, fresh.runtime.program)
        for (w in sharpWarnings) Log.w(TAG, "CALLSHARP 装载警告：$w")
        fresh.recordSharpLoadWarnings(sharpWarnings)
        // 迁移失败/文件夹丢失的结局原版是 MessageBox（Config.cs:553-561/:568-572/:586-589），
        // Android 用 Toast 透出；成功搬迁无 notice（原版也不弹）。
        SaveFolderMigrationLog.of(fresh.store)?.latest?.notice?.let { notice ->
            view.post {
                if (!closed) {
                    Toast.makeText(view.context, "${notice.title}\n${notice.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        // 竞态窗口（A4）：`GameSession` 构造完成后、VM 线程走到 drive() 之前，UI 线程可能
        // 已把 [closed] 置位 —— 此时没人会再调 close()，构造出来的会话（CSV 总装、变量池、
        // 声道表）就地泄漏。与上面 program / meta 段的两道闸同型，补上最后这一道。
        if (closed) {
            fresh.close()
            return null
        }
        return fresh
    }

    /**
     * 图形通路装配（M10-GFX）：把「像素从哪来」和「语句画到哪去」两条链一次接齐。
     *
     * ## 三个注入点，为什么都不动 `GameSession`
     *
     * 会话构造链（`GameSession → ProcessRuntime`）的参数面归别的 lane（m10-play 正在改
     * `GameSession` 的输入队列那一段），这里一个参数都不加，改用两个既有注入点：
     * 1. `runtime.execContext.graphics` —— [com.eratw.port.core.sem.ExecContext.graphics] 是
     *    `var`，且语句层每次执行都现读它（spec: `StatementExecutor.kt:385-422` 六条图形语句
     *    全部走 `ctx.graphics.*`），所以「会话建好后换上真 sink」与「构造时传 `graphicsSink`」
     *    逐次执行等价（同一注入点的两条路，见 `ProcessRuntime.graphicsSink` 的 KDoc）；
     * 2. `store.aux` 的 [IMAGE_DECODER_SLOT] —— `GCREATEFROMFILE` / `GLOAD` 在
     *    `GraphicsMethods.readImageFile` 里 `c.aux.peek` 现读（`BuiltinContext.aux` 就是
     *    `store.aux`，见 `Builtins.kt:512`），装与不装只改「像素从哪个解码器来」，
     *    返回码分支一字未动；没装时（纯 JVM 闸 / 语料对拍）走 core 自带 `PngDecoder` /
     *    `WebpDecoder`，与今天逐项相同。
     * 3. 绘制侧的取图口 [SpriteBitmapSource] 读的是**同一个** `store.aux` 的 `sprite` 槽
     *    （`SPRITECREATE` 写的就是那张表，spec: `AppContents.cs:39-51` 的 `GetSprite` 同构）。
     *
     * 时机：`drive()` 之前、`view.post { … }` 之外。`drive()` 起的脚本会立刻跑 `HTML_PRINT`
     * 与 `SETBGIMAGE`，晚一步开场那几页就先被当纯文本交上去（展平发生在 `ConsoleFeed` 提交时）。
     *
     * 线程：本函数在 VM 线程跑，[AndroidGraphicsSink] 因此也在 VM 线程被调用；
     * 重绘通知走 `postInvalidateOnAnimation`（跨线程入口）。
     */
    private fun installGraphics(session: GameSession, gameRoot: File): GraphicsWiring {
        // 背景准入语义与 C# 证据：见 core/fn/BackgroundAdmission.kt 的 KDoc（原 lambda 注释原样下沉）。
        val layer = GraphicsLayer { name ->
            BackgroundAdmission.admissible(session.store.aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)?.find(name))
        }
        val metrics = view.cellMetrics()
        val decoder = session.store.aux.slot<ImageDecoderHost>(IMAGE_DECODER_SLOT) { BitmapImageDecoder() }
        // 堆外 PNG 载荷通道（OOM-B）：降级态的压缩载荷写 cacheDir 子目录再 mmap，
        // 搬出 Java 堆大对象空间。装配失败（目录不可写等）不装 ⇒ core 回落堆内闭包老路。
        // 残留清扫：上一进程崩溃 / Windows 映射存活期删不掉的文件，本次装配顺手收。
        runCatching {
            val store = MappedPngPayloadStore(File(view.context.cacheDir, "png-payload").toPath())
            store.sweepStale()
            session.store.aux.slot<PngPayloadStore>(PNG_PAYLOAD_SLOT) { store }
        }.onFailure { Log.w(TAG, "PNG 堆外载荷通道未装（回落堆内闭包）", it) }
        // CSV 资源表（`AppContents.LoadContents`，AppContents.cs:107-178）必须在脚本开跑前装好：
        // `resources` 下 csv 声明的图片只在这张表里，XRAY 图层的 `RM_RESOURCECHECK_LOAD` 与
        // `GDRAWSPRITE 图层` 全靠它；不装 ⇒ 图层逐层被跳过 ⇒ 合成出一张空图（XRAY 隐形）。
        val spritePool = session.store.aux.slot<SpritePool>(SpriteBitmapSource.SLOT_SPRITE) { SpritePool() }
        val contents = ResourceContents.load(spritePool, ResourceCsvHost(gameRoot, decoder))
        // 端点取证面只认这一行：logcat 玩家和收件箱都看不到，报告里的「补充现场」才带得出去。
        resourceCensusLine = ResourceContents.censusLine(contents)
        Log.i(
            TAG,
            "CSV 资源表：文件=${contents.fileCount} sprite=${contents.spriteCount} 动画=${contents.animeCount}",
        )
        contents.warnings.forEach { Log.w(TAG, "CSV 资源：$it") }
        val frames = SpriteBitmapSource(session.store.aux, GameImageFiles(gameRoot))
        session.runtime.execContext.graphics = AndroidGraphicsSink(
            layer,
            metrics,
            // 脚本 px 的基准字号：与下面 ConsoleFeed 喂的是同一个值，否则「语句打的图」与
            // 「HTML 打的图」差一个基准倍，同一行的图层会错位。
            scriptFontPixelSize = session.scriptFontPixelSize,
            // 后台预取（立绘/背景首帧卡顿）：`PRINT_IMG` / `SETBGIMAGE` 一入队就把按名解码
            // 挪到 `frames` 的解码槽（后台单线程）。产品口径是「宁可掉一帧，绝不空白」——
            // 预取没赶上时 `frameOf` 也不再在调用线程解码，而是把同一个名字排进同一个槽、
            // 本帧按既有缺图形状降级，解好后经 [attachConsoleFeed] 里接上的就绪口补一次重画。
            onImage = frames::prefetchAsync,
            // 尺寸源与 ConsoleFeed 的展平链同一份 `frames`：`PRINT_IMG` 缺宽按基图纵横比推宽、
            // 图元 miss 按 AltText 落文本（`ConsoleImagePart.cs:60-71`）。不传就退见方占位。
            sizeSource = frames,
        ) { view.notifyGraphicsChanged() }
        Log.i(
            TAG,
            "图形通路已接：decoder=${decoder.javaClass.simpleName} sink=AndroidGraphicsSink" +
                " 字体=${metrics.fontPixelSize}px 半角=${metrics.cellPixelWidth}px" +
                // 脚本 px → 设备 px 的折算基准（静默换算必须留可查痕迹：图上尺寸不对时先看这一项）
                " 脚本字号=${session.scriptFontPixelSize}px(px 折算分母)" +
                " 资源根=${gameRoot.absolutePath}",
        )
        return GraphicsWiring(layer, frames)
    }

    /** 一次装配交给绘制侧的两件东西（必须是同一份实例，否则内联段与背景层分叉）。 */
    internal class GraphicsWiring(val layer: GraphicsLayer, val frames: SpriteBitmapSource)

    /**
     * 把渲染桥挂回视图（装载末段与 [backToTitle] 的恢复路径共用这一处）。
     *
     * 失败屏会 `detachFeed()`（`MainActivity.renderFailureIfNeeded`），恢复通道重新起泵前
     * 必须再挂一次，否则脚本照常推进而屏上永远停在失败快照。
     */
    private fun attachConsoleFeed(fresh: GameSession, gfx: GraphicsWiring, screen: ConsoleScreen) {
        bootTrace?.mark("boot.控制台挂载")
        view.attachGraphics(gfx.layer, gfx.frames)
        // 解码就绪 → 上屏（FEEL1-L1 组长接线）：`frameOf` 未命中时不再在 UI 线程解码，
        // 那一帧按缺图形状降级；解码槽解好后经 `SpriteBitmapSource` 的主线程 Handler 投回
        // 这一个口，由 View 侧的「在场面闸」决定要不要重画（离屏名字零失效，在屏恰好一次
        // 普通 invalidate）。摘口不必另接：`SpriteBitmapSource.close()` 自己把监听置 null，
        // 而会话收口链本来就会调它。整段 `runCatching`：装配期任何失败只退化成「图晚几帧出现」，
        // 不该把已经跑起来的会话带死。
        runCatching { gfx.frames.setDecodeReadyListener(view::onGraphicResourceReady) }
        // 动画周期重绘（GFX-07 宿主侧）：`SETANIMETIMER` 的池级读数交给视图自己排表，
        // 0 = 关表、退回「内容变了才重画」。取图口与帧号计算都在 `frames` 里，
        // 视图只负责「按时把屏再画一遍」（原版定时器只做 window.Refresh）。
        view.attachAnimeRepaint(gfx.frames::animeRepaintIntervalMs)
        // 内联图形横向夹到屏宽：eratw 标题图 1041×16 横带按 `FontSize` 缺省高度
        // 放大后宽约 2733px（110 列档）> 2400 屏宽，不夹会右侧被裁；夹宽时只压宽
        // 不压高，各带仍逐行相接（不出条纹缝），超高由控制台滚动/裁切处理。
        //
        // 竖带合并（2026-10-25 黑块修复）：标题图 35 条带逐行打印，一屏 28~34 行装不下，
        // 贴底显示只剩下半截、上翻才看得到全图。`ImageBandMerge` 把连续纯图片行合成
        // 一张等比缩小的图并删掉多余行；可用宽取窗口宽、块高取内容高的 60%（给页脚文字
        // 留位置），整页因此收进一屏。参数为 0 时会话/预览/测试路径保持原行为。
        val metrics = view.cellMetrics()
        val maxImageWidth = (view.windowColumns() * metrics.cellPixelWidth).toInt()
        // LIVE-REFLOW 的兜底闸（判据 [ViewportReflowChain.needsScreenAlignOnReattach]）：
        // `ConsoleFeed` 的构造要求「核心折行宽 == 屏宽」，非等即抛。开态下这两者会在
        // 「换屏成功但 `lastScreen` 还是旧宽那份」/「失败屏摘过 feed 时核心重排了、屏没换成」
        // 两条窗口里分叉，而恢复路径（`backToTitle` → 本方法）正是拿旧屏重挂桥 ⇒ 当场崩。
        // 这里按核心当前宽换一只**空**屏（与 `reflowAttachedFeed` 同一形状：空屏 + 首帧 pump
        // 补交整屏快照），行数沿用传进来那份的**实时**视口行数（`resizeFeedScreen` 改的是
        // `viewport.heightLines`，不是构造参数）。关态永不进这个分支，既有路径逐字不变。
        val sinkColumns = fresh.console.clientWidth
        val feedScreen = if (
            ViewportReflowChain.needsScreenAlignOnReattach(liveReflow, screen.clientWidth, sinkColumns)
        ) {
            Log.i(TAG, "重挂渲染桥：按核心折行宽换屏 ${screen.clientWidth} -> $sinkColumns")
            ConsoleScreen(
                sinkColumns,
                screen.maxLog,
                screen.viewport.heightLines.coerceAtLeast(1),
                // 与会话/绘制同一只尺（`CanvasConsoleView.fontDisplayWidth`）：换屏不能换宽度口径。
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
                // 脚本 px → 设备 px 的基准字号（立绘空档修复；口径见 ConsoleFeed 的 KDoc）。
                scriptFontPixelSize = fresh.scriptFontPixelSize,
                // HTML 图（立绘主路径）预取：`ConsoleFeed` 在发布线程只查表入队，
                // 解码落在 `frames` 的解码槽（后台单线程）；没赶上时绘制侧也不在调用线程解码，
                // 同样排队补解 —— 这一帧按缺图形状降级，就绪后经上面的监听补一次重画。
                prefetch = gfx.frames::prefetchAsync,
            ),
        )
        // LIVE-REFLOW（契约 §6 L6）：渲染桥已挂、会话已可用 ⇒ 在这里把视口的「宽度变了」接进重排链。
        //
        // 关态（[liveReflow] = false）**连闭包都不注册**，也不碰 `onViewportChange` 这个字段：
        // 缺省它就是 null，而 `liveReflow=false` 的视口外层 if 根本不会去读它
        // （`CanvasConsoleView.rebuildScreen`，现树 :1715-1727）。所以关态既没有新增调用、
        // 也没有新增对象，既有横屏/竖屏路径逐字不变 —— 这一条由 `LiveReflowWiringTest` 钉住。
        //
        // 为什么挂在这一处、而不是 `attachSession`（会话挂上的一刻）：
        // 1. `attachSession` 跑在 **VM 线程**，而这条链的两步（`resizeConsole` /
        //    `reflowAttachedFeed`）都只许 UI/渲染线程（见 [ViewportReflowChain] 的线程节）——
        //    注册动作本身也该在 UI 线程做，别留一枚「跨线程写 View 字段」的先例；
        //    本方法两个调用点都是 `view.post { if (!closed) attachConsoleFeed(...) }`，天然在 UI 线程；
        // 2. `reflowAttachedFeed` 的前提是 feed 已在位，上面那一行刚把它挂好；
        // 3. `backToTitle` 的恢复路径重新走一遍本方法 ⇒ 重挂同一形状的闭包，幂等。
        //
        // 摘除的对偶在 [shutdownForExit]（与摘渲染桥同一发 UI 线程清场）。换局时新宿主注册闭包
        // 的 `post` 排在旧宿主那条摘除之后很久（主线程队列 FIFO + 新局要先解包/装载），
        // 不存在「摘除晚到把新回调撬空」的窗口。
        if (liveReflow) {
            view.onViewportChange = { cols, rows, widthPx, heightPx, fontPx ->
                // 整段兜住（本仓 UI 回调的既定纪律：一次未捕获异常就是玩家转一下屏/分一次屏
                // 之后应用没了）。兜住不等于静默：`rebuildScreen` 的判据读的是**当前屏宽**，
                // 换屏没做成时下一次布局仍会按「屏宽 != 解算列数」再问一遍 ⇒ 这一发失败会自愈，
                // 不是「永久不重排」。
                val outcome = runCatching {
                    ViewportReflowChain.run(
                        closed = closed,
                        session = session,
                        // 像素口径：视口给的是**内容区** Int 宽高，核心侧收 Long（GFX-06 同一把尺）；
                        // 高度变化也记进 `screenState`（`CLIENTHEIGHT()` 跟着走），但只有宽度
                        // 真的变了才重排历史 —— 由 `resizeConsole` 自己判，本处不另判一次。
                        // fontPx = 本次版面解算出的设备字号：转屏换字号档后 CLIENTWIDTH 的折算比例
                        // 也要跟着换（否则旧分母配新宽度，脚本读到的脚本 px 偏一截）。
                        resize = { s ->
                            s.resizeConsole(cols, widthPx.toLong(), heightPx.toLong(), fontPx)
                        },
                        // **只在**上一行返回 true 时才求值（[ViewportReflowChain] 不变量 3）：
                        // 核心没重排就换屏，视口会按新列数去比旧 sink 宽 ⇒ WidthMismatch 当场抛。
                        swap = { view.reflowAttachedFeed(cols, rows) },
                    )
                }.getOrElse {
                    Log.e(TAG, "视口重排异常 cols=$cols rows=$rows（本局照常，下一次布局再问）", it)
                    null
                }
                if (outcome != null) {
                    Log.i(
                        TAG,
                        "视口重排 cols=$cols rows=$rows px=${widthPx}x$heightPx font=$fontPx -> $outcome",
                    )
                    if (outcome == ViewportReflowChain.Outcome.SWAP_DONE) {
                        // 「最近一次装配的屏幕形状」必须跟到新屏上：`backToTitle` 的恢复路径拿它
                        // 重挂渲染桥，而 `ConsoleFeed` 的构造非等即抛（见
                        // [ViewportReflowChain.needsScreenAlignOnReattach] 的那两条分叉窗口）。
                        // 读 `view.consoleScreen()` 而不是自己 new —— `reflowAttachedFeed` 刚把
                        // 换好的那只写进视图（`CanvasConsoleView.kt:700`），两者必须是同一份对象。
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
            // 关闭竞态（收件箱 2026-11-03 一天 6 份）：close() 先置 [closed] 再 interrupt VM
            // 线程，VM 主循环中断检查点抛出的「执行被中断（宿主正在关闭）」是「玩家/系统正在
            // 拆这一局」的正常退出路径 —— Vm.kt 里 shutdownInterruptedAt 的 KDoc 本就写着
            // GameHost 的 catch 按 [closed] 把它只当日志，此处补上同一道闸，别把它折成
            // 「脚本运行报错」上报（Vm.kt:732-746 的既定口径）。
            if (closed) {
                Log.i(TAG, "宿主关闭中断，脚本线程退出（${e.message ?: e.javaClass.simpleName}）")
                return
            }
            // 标题入口那一发同样在原版 DoScript 的 catch 覆盖范围内（每一次 runSystemProc
            // 派发都算，Process.cs:449-452）。收件箱 0.2.32 那 8 份「XML_GET函数:""解析错误:
            // 缺少根元素（位置 0）@ RM_リソース情報初期化 行49」正死在这一步 —— 判据与收口的
            // 形状见 [scriptErrorTerminal]。
            if (!isRecoverableScriptError(e)) throw e
            scriptErrorTerminal(fresh, e)
            stopAudioForTerminal(fresh)
            return
        }
        pumpUntilTerminal(fresh)
    }

    /**
     * 语句级脚本错误（[CodeEE]）从中局状态机逃出来时的收口 —— 原版是 DoScript 的顶层 catch。
     *
     * 原版的形状（reference，逐句可查）：
     * - `Process.cs:438-478`：整条 `while (state.ScriptEnd && console.IsRunning) runSystemProc()`
     *   外面套一个 `try`，`catch (Exception exc)` 按 `flag` 分派给
     *   `handleExceptionInSystemProc`（`:571-593`）或 `handleException`（`:595-661`）；
     * - 两条分支都**先**调 `console.ThrowError(playSound)`（`EmueraConsole.cs:757-768`，它把
     *   控制台置成 `ConsoleState.Error`），再一串 `PrintError` 把错误文案与位置打在屏上，
     *   然后**正常返回** —— 不重抛、不关进程、不重启；
     * - 错误之后**连回显都没有**：`PressEnterKey` 在 `state == ConsoleState.Error` 那一支里，
     *   除 `__openFileWithDebug__` 这条开发通道，其余输入一律 `window.Close()` 后直接 `return`
     *   （`EmueraConsole.cs:1390-1401`）—— 根本走不到会回显的那一段。`RunEmueraProgram` 里
     *   `state != ConsoleState.Error` 的门控（`:1092-1110`）只是第二道闸，不是「只回显」的依据。
     *   鼠标点击同一条路：Error 态下 `IsWaitingEnterKey` 仍为真（`:324-332` 的 `state == Error`
     *   一支），`MainWindow.cs:810-813` 把点击交给 `PressEnterKey`（`:922-934`）→ 同一句
     *   `window.Close()` ⇒ 点屏 = 关窗。
     *   于是唯一的复活通道是菜单「タイトルへ戻る」= `GotoTitle`（`EmueraConsole.cs:2569-2581`），
     *   而它能复活的**技术理由**是那九条语句里的 `ReadAnyKey()`（`:2578`，方法体 `:876-895`，
     *   写点 `:893` 的 `state = ConsoleState.WaitInput`）先把状态从 Error 写回 WaitInput，
     *   末尾那发 `RunEmueraProgram("")`（`:2579`）才过得了上面那道门控、把 DoScript 重跑起来。
     *
     * 移植侧此前缺这一层：一条语句级的 CodeEE 直接穿破 [pumpUntilTerminal]，落到
     * [loadAndRun] 的大 catch 里按装载失败收口 —— 于是「玩家点了采集、脚本自己报错」这种
     * 中局错误上屏写「启动失败」、报告类型也写「启动失败」，而且 `shutdownForExit()` 把会话
     * 关掉，连「回到标题」那颗复活按钮都不点亮（[canBackToTitle] 要求会话还在）。
     * 端点收件箱里最大的一簇正是它：2026-11-02 读数下 0.2.34 一台 vivo 连报 28 份
     * 「RAND不能指定0以下的值(0) @ QOL_GATHERING_DRAWING 行69」，同形的还有「除数为 0」
     * 「变量 EXPLV 的第 1 维下标 5002 越界」「`<p>`标签重复使用」（语料侧的判式缺陷，
     * 官方同样抛 —— 见 ADR-005，本方法不改抛错，只改**怎么收口**）。
     *
     * 收成的形状与既有的 [StepOutcome.FAILED] 终局**逐条一致**（终局语义与「为什么留会话」
     * 写在 `BeginFlow.terminalEndNormalStep` 的 KDoc）：失败屏按 [FailureScreenKind.IN_GAME]
     * 讲、会话留着、「回到标题」原地复活、报告按「脚本运行报错」落。差别只有一条：这一条
     * 拿得到异常对象，所以报告里带类型/文案/栈，而不是「（无异常对象）」。
     *
     * 刻意**不做**的：
     * - 不重演原版的整段错误文案块（`Lang.Error.HasError` / `ErrorInFunc` / 函数调用栈，
     *   `Process.cs:612-644`）。那些键不在 specs 的 error-texts.yaml 口径表里，逐字接线要连
     *   `LangText.kt` 与它的对账闸一起动，交组长排期；这里只把 core 侧已按官方文案逐字对齐的
     *   `message` 与脚本位置打在屏上。
     * - 不播错误音（原版 `ThrowError(playSound)` 的 `SystemSounds.Hand.Play()`）：失败屏已经
     *   把话说完，音频半边属另案。
     */
    private fun scriptErrorTerminal(fresh: GameSession, e: CodeEE) {
        val location = scriptLocation(e)
        // i18n（2026-11-07）：带结构化键的 CodeEE 按会话语言档重渲染；无键的沿用定型 message。
        // 报告的取证面仍读 e.message（zhs 定型），只有这里上屏的这行随语言变。
        val message = e.langKey?.render(textVariant, *e.langArgs.toTypedArray())
            ?: (e.message ?: e.javaClass.simpleName)
        val text = message + location
        // 屏上留痕（原版 PrintError 的对应面）：`LineBufferConsoleSink.printError` 自带
        // 解冻发布（见其 KDoc）。但**别指望玩家回标题后还能在这一屏上看见错在哪一行**：
        // `gotoTitle()` 的第一句就是清屏（`core-vm/.../proc/ProcessRuntime.kt:4088-4090` 调
        // `clearDisplayForGotoTitle()`，其 KDoc 逐条对到原版 `ClearDisplay()` 的方法体
        // `EmueraConsole.cs:2801-2813` 的 `displayLineList.Clear()` 与 `logicalLineCount = 0`），
        // 复活成功之后屏上是标题那一页，这一行已经不在快照里了。
        // 它真正活得久的地方是**报告**：本函数下面那发 `writeErrorReport` 读
        // `debugConsoleLines`（:1847 → `session.console.tailText`）当「屏上最后的内容（原样）」
        // 那一节（拼装点 `ErrorReportCollector.collectFull` 的 `consoleTailLines`，小节渲染与
        // 80 行尾部保留窗口在 core 的 `ErrorReport.kt:156-159` + `:185`）—— 写报告在读到这一行
        // 之后、清屏之前，所以「错在哪一行」取证靠的是它，不是屏。
        // 整段兜住：收口这一步再抛就没有第二条路了。
        runCatching { fresh.console.printError(text) }
            .onFailure { Log.w(TAG, "脚本错误上屏失败（继续收口）", it) }
        lastFailureScreenKind = FailureScreenKind.IN_GAME
        failure = failureCopy.fill(failureCopy.scriptAbortFormat, text)
        writeErrorReport(
            "脚本运行报错",
            e,
            // 这两行是「现场行」，靠 writeErrorReport 的拼接次序垫到本块**末尾**（尾部保留窗口
            // 从头部剪，见那里的 2026-11-05 修正）：中止点与证据没有第二个出口，被剪就永久没了。
            listOf(
                "中止点：系统状态机 ${fresh.runtime.systemState.name}（语句级脚本错误，原版由 DoScript 顶层 catch 收口）",
                "证据：Process.cs:438-478 / Process.cs:571-661",
            ),
        )
        Log.e(TAG, "脚本错误终局（会话保留，可回标题）：$text", e)
    }

    /**
     * 终局失败清场（音频半边）。
     *
     * 终局失败要留会话（理由见 [pumpUntilTerminal] 的 KDoc），但音频照停。这条路上没有
     * [shutdownForExit] 的逐段兜底，一旦 closeAll 抛出（旧写法会，因为它在循环里裸调平台
     * release）异常会直接进调用方的失败收口，把「终局」变成「又一层异常」。
     */
    private fun stopAudioForTerminal(fresh: GameSession) {
        runCatching { focus.release() }
        runCatching { fresh.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT)?.closeAll() }
            .onFailure { Log.w(TAG, "终局清场：声道关闭失败（会话保留，继续）", it) }
    }

    /**
     * 泵系统状态机直到「close / QUIT / BLOCKED-FAILED / 语句级脚本错误」四者之一。
     *
     * 返回 true = 停在可恢复的失败终局（BLOCKED/FAILED 那两态，或 [CodeEE] 穿破本步派发被
     * [scriptErrorTerminal] 收口）。此时**不**关会话：原版报错后
     * 进程与 `Process` 都还在，`GotoTitle`（`EmueraConsole.cs:2569-2581`）是唯一复活通道，
     * 关掉输入队列就没法复活了（`GameSession.close` 之后任何等待直接抛
     * `InputSinkClosedException`）；只按 [shutdownForExit] 的音频半边停声、放焦点。
     */
    private fun pumpUntilTerminal(fresh: GameSession): Boolean {
        var terminal = false
        // 原版 DoScript 内层循环的形状（Process.cs:445-460）：只要会话活着就反复跑
        // handler，状态是 Normal 也照跑 —— Normal+栈空在原版是 handler 里的 endNormal
        // 抛错（Process.cs:2039 注册 + :2959-2962 方法体），移植侧等价读数是
        // StepOutcome.FAILED。所以不能按「状态 != Normal」当循环条件：那会把
        // endNormal 的 FAILED 永远挡在门外（ Normal 时直接静默退出，崩一次连失败屏
        // 都不出）。停转条件只有三个：close()、脚本 QUIT（ProcessRuntime.quitRequested，
        // 对应原版 ConsoleState.Quit 的 IsRunning=false，EmueraConsole.cs:731-734 +
        // :270-284）、以及 BLOCKED/FAILED 收口。
        while (!closed && !fresh.runtime.quitRequested) {
            val step: StateStep
            try {
                step = fresh.resumeSystem()
            } catch (e: CodeEE) {
                // 关闭竞态：与 [drive] 的 catch 同一道闸 —— 本步执行期间宿主已 close() 时，
                // VM 中断检查点抛出的 CodeEE（「宿主正在关闭」）是正常退出，不是脚本错误；
                // 终局处理（失败屏 / 「脚本运行报错」报告）一律不跑。
                if (closed) break
                // 语句级脚本错误从中局状态机里逃出来：原版由 DoScript 的顶层 catch 收口
                // （官方 Process.cs:438-478），移植侧的同一形状见 [scriptErrorTerminal]。
                // 判据按成因分流（[isRecoverableScriptError]）：同为 StepLimitExceeded，
                // 只有玩家在墙钟告警里选「是」那一支（LoopGuardCause.PlayerChoseExit）
                // 是普通脚本错误、走本路；装置预算与爆栈闸仍原样交回上层，
                // 现场转储挂在 [loadAndRun] 的收口里（deadloop-site.txt）。
                if (!isRecoverableScriptError(e)) throw e
                scriptErrorTerminal(fresh, e)
                terminal = true
                break
            }
            // 关闭竞态：本步跑完返回时宿主已被 close()（onDestroy/换局），后面的终局处理
            //（含 BLOCKED/FAILED 那一发「脚本运行报错」报告）都不该再跑 —— 静默收，等
            // 循环条件自然退出。
            if (closed) break
            // 翻页延迟读数（负责人实测「点开始游戏后像卡住、44 秒才出下一页」）：
            // 起点 = 输入提交（submitInternal），终点 = 本步返回（页面就绪）。
            // 只读一次就复位；没有待测翻页时是一次 CAS 读，热路径成本可忽略。
            val startedNs = turnStartNs.getAndSet(0L)
            if (startedNs != 0L) {
                val ms = (System.nanoTime() - startedNs) / 1_000_000L
                Log.i(TAG, "turn.latency ms=$ms outcome=${step.outcome} from=${step.from.name}")
            }
            // BLOCKED 且状态不变时下一轮会退化为每轮立即返回的 busy loop（BeginFlow
            // 某些分支如 CTrain 未接、状态未接管）——烧一核空转。FAILED 是状态机的
            // 确定性死路且已粘滞（BeginFlow.terminalEndNormalStep：重复 step 幂等返回
            // 同一终局，不再消费挂起 BEGIN、不再进输入等待），继续转也不会有下一步。
            // 两者都当场落 [failure] 上失败屏，不再转。AWAIT_INPUT / CONTINUE /
            // CALLED / REENTERED 照旧循环。
            when (step.outcome) {
                StepOutcome.BLOCKED, StepOutcome.FAILED -> {
                    lastFailureScreenKind = FailureScreenKind.IN_GAME
                    failure = failureCopy.fill(
                        failureCopy.stateMachineHaltFormat,
                        step.from.name,
                        step.blocker?.message ?: failureCopy.stateMachineUnknownBlocker,
                        step.blocker?.evidence ?: failureCopy.stateMachineNoEvidence,
                    )
                    // 方案①：脚本报错终局也落报告。这里拿不到异常对象（状态机终局），
                    // 错误小节写「（无异常对象）」，现场靠屏上原文与 ERB 调用栈。
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
                else -> {}
            }
        }
        // 音频清场两条路都要做（原版 QUIT/报错终局 = 控制权回宿主，BGM 自然终结；
        // 移植版不显式停的话声道一直播、焦点不释放）。差别只在**会话要不要留**：
        // - 终局失败留会话：原版报错后 `Process` 还活着，`GotoTitle`
        //   （`EmueraConsole.cs:2569-2581`）是唯一复活通道，而 `GameSession.close()` 会关掉
        //   输入队列（此后任何等待抛 `InputSinkClosedException`）⇒ 关了就只能重开进程，
        //   恢复通道随之失效。
        // - QUIT / closed：进程级退出语义，按 [shutdownForExit] whole 清场。
        // shutdownForExit 幂等，重复调用无害。
        if (terminal) {
            stopAudioForTerminal(fresh)
        } else if (!closed) {
            shutdownForExit()
        }
        return terminal
    }

    /**
     * 失败屏那颗「少占内存再试」是否点亮（判据见 [lastHeapFailureStage]）。
     *
     * **两族都点亮**（这是本刀的一处刻意取舍，理由写在这里）：甲族（预检拒载）的静态估算说
     * 「上限 < 需求」，可那份估算按 1.1 倍保守标定，而兼容档的实测峰值远低于它 —— 2026-10-28
     * 加这颗按钮的初衷（512 MiB 挡机器的唯一自救路）在甲族依然成立。把它收走等于对玩家说
     * 「你这台机器没救了」，而真正的病只是「别**自动**替他再赌一次」。
     * 差别因此落在**要不要自动按**上（`ShellUi.shouldAutoRetryCompat` 只认
     * [HeapFailureStage.LOAD_OOM]），而不是这颗按钮亮不亮；甲族的屏上文案会明说
     * 「也可能还是装不下」（见 [heapPreflightStoppedText]），让这一次点击是玩家的知情选择。
     */
    fun canRetryCompat(): Boolean = lastHeapFailureStage != HeapFailureStage.NONE

    /**
     * 「回到标题」按钮的可用性（原版菜单项的两道拒绝在同一处判）。
     *
     * 对应 `MainWindow.cs:1079-1104`：脚本正在跑（`console.IsInProcess`）时不给点；
     * 装载/标题级硬失败（`notToTitle`/`byError`，`EmueraConsole.cs:750-755` 只由
     * `ThrowTitleError` 置位）时移植侧根本没有可复活的会话，同样不给点。
     * 普通游戏中错误（`ThrowError`，`:757-768`）不置 `byError` ⇒ 照常可复活。
     */
    fun canBackToTitle(): Boolean =
        session != null && !closed && failure != null && thread?.isAlive != true

    /**
     * 失败屏「回到标题」= 原版 `GotoTitle`（`EmueraConsole.cs:2569-2581`）的宿主半边：
     * 在**同一会话**上复位变量池与系统状态机、重进 `Begin(TITLE)`，然后照旧泵。
     *
     * 与 [close] + 重开一局的差别正是原版口径：原版报错后不重启进程，
     * `Process.BeginTitle()`（`Process.cs:480-485`）原地复位；装载一次的代价
     * （真机 40 s 级）不该压在唯一的复活通道上。
     *
     * @return false = 按 [canBackToTitle] 的口径拒绝（调用方保留失败屏）。
     */
    fun backToTitle(): Boolean {
        val active = session ?: return false
        val gfx = graphicsWiring ?: return false
        val screen = lastScreen ?: return false
        if (!canBackToTitle()) return false
        failure = null
        // 复活也会起一条持有整份 Program 的 VM 线程：与 [start] 同一交接口径登记，
        // 否则「回到标题」之后旧 Activity 被回收、新 Activity 装载时看不到这条线程。
        SessionHandoff.register(this)
        val worker = Thread(
            {
                try {
                    val first = active.gotoTitle()
                    Log.i(TAG, "gotoTitle: outcome=${first.outcome} from=${first.from.name}")
                    // 失败屏摘过渲染桥：重挂一次，否则脚本照跑而屏上永远停在失败快照。
                    // 与 [start] 装载路径（:1808）同闸：post 排队期间 Activity 可能已销毁、
                    // close() 已摘过桥 —— 无条件 attach 会把**已死会话**重挂回长寿视图
                    // （整局 Program 回收不掉，正是那条 KDoc 写明的泄漏形态）。`closed` 是 @Volatile。
                    view.post { if (!closed) attachConsoleFeed(active, gfx, screen) }
                    pumpUntilTerminal(active)
                } catch (t: Throwable) {
                    // 关闭竞态：与 [drive] 的 catch（本文件 :3350-3353）、[pumpUntilTerminal] 的
                    // 两道闸（:3482、:3495）同一形状，判据在 UiFault.kt 的 [backToTitleFaultAction]。
                    // 复活途中宿主被 close() 时，`gotoTitle()` 撞上 VM 中断检查点抛「执行被中断
                    // （宿主正在关闭）」、会话已关时等待抛 `InputSinkClosedException`，两者都是玩家
                    // 正常退出的路径：不换屏、不落报告（否则每次退出都可能折成一份「回到标题失败」）。
                    // finally 里的 SessionHandoff.unregister 照旧 —— 登记与注销必须成对收。
                    if (backToTitleFaultAction(closed) == BackToTitleFaultAction.SUPPRESS_BY_CLOSE) {
                        Log.i(TAG, "回到标题途中宿主关闭，静默收口（${t.message ?: t.javaClass.simpleName}）")
                    } else {
                        lastFailureScreenKind = FailureScreenKind.IN_GAME
                        failure = failureCopy.fill(
                            failureCopy.backToTitleFailedFormat,
                            (t.message ?: t.javaClass.simpleName) + scriptLocation(t),
                        )
                        Log.e(TAG, "回到标题失败", t)
                        writeErrorReport("回到标题失败", t)
                    }
                } finally {
                    SessionHandoff.unregister(this@GameHost)
                }
            },
            VM_THREAD_NAME,
        )
        thread = worker
        try {
            worker.start()
        } catch (t: Throwable) {
            SessionHandoff.unregister(this)
            thread = null
            lastFailureScreenKind = FailureScreenKind.IN_GAME
            failure = failureCopy.fill(failureCopy.backToTitleFailedFormat, t.message ?: t.javaClass.simpleName)
            return false
        }
        return true
    }

    /**
     * 报告里的图形池读数（`gfx` 槽，见 `GraphicsMethods.gfx()`）。
     *
     * 只读诊断、失败路径专用（启动失败报告直连 + 崩溃报告经 [ErrorReportCollector.memoryLines]
     * 间接消费）：取样本身失败（会话未建/槽未登记）就当没有这一行，绝不让诊断把报告路径
     * 再拖崩一次。OOM 机型上这枚读数是「像素常驻 vs 装载峰值」的区分依据。
     */
    internal fun graphicsPoolReportLine(): String? {
        val stats = runCatching {
            session?.store?.aux?.peek<com.eratw.port.core.host.GraphicsPool>("gfx")?.stats()
        }.getOrNull() ?: return null
        // 位图缓存与核心像素池是两份内存，且**不在同一个堆上**：`stats.pixelBytes` 是 core 的
        // IntArray 像素（4 B/像素），计入 512 MiB Java 堆挡位；`cacheBytes()` 是平台侧 Bitmap 像素，
        // Android 8+ 常驻 **native 堆**，根本不占那 512 MiB。2026-11-06 端点报告里两段同排不带
        // 口径，把人往「204 MiB 位图缓存也要挤 Java 堆」的错方向带过一次 —— 每段自带堆归属。
        // 仍保持单行：调用方把它当一行塞进报告行表（listOfNotNull / take(24)），不引入换行符。
        val bitmapCacheMiB = (graphicsWiring?.frames?.cacheBytes() ?: 0) / (1024 * 1024)
        // 负号段（CSV 父图）驻留（fix-spritebudget）：这批缓冲**不在** stats.pixelBytes 的池
        // 记账里（正号段预算闸结构上看不见它们，见 `SpritePool.resourceResidentPixelBytes`），
        // 报告里不并排读数就没法定性「像素常驻到底在哪一段」。O(n) 锁内快照在崩溃路径可接受
        //（口径同本函数对 stats() 的遍历：只读不改，任一步失败缺这一行不缺报告）。
        // reportres 波（2026-11-05）：去重不再每次现搭 `HashSet`，传本宿主长生命周期的
        // [reportResidentScratch]（面只在 `SpritePool` 池监视器内被摸，判据与「为什么新开第六块」
        // 全在那个字段的 KDoc）。**读数与成文一个字没动** —— 两臂同值由
        // `ResourceResidentSnapScratchTest` 的等价臂钉住，本行的 MiB 折法与文本原样。
        val csvResidentMiB = runCatching {
            session?.store?.aux?.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE)
                ?.resourceResidentPixelBytes(reportResidentScratch) ?: 0L
        }.getOrDefault(0L) / (1024L * 1024L)
        // native 侧的第二块账面（heap-budget 车道 2026-11-02）：本屏那四块位图从来不在
        // `位图缓存` 那一列里 —— 端点报告「native 驻留偏高」286~361 MiB 的形状，光看
        // 平台三张表（32 + 16 + 工作表缓存，档位现取）是**算不满**的，缺的正是这块。
        // 读数失败（通路未装配 / 位图已回收）只当没有这半句，不拖垮整行。
        val nativeTail = runCatching { NativeTrimPlan.reportSuffix(nativeCacheSnapshot()) }
            .getOrDefault("")
            .let { if (it.isEmpty()) "" else " / $it" }
        // 预算不许写死：放开档工作表缓存是 384 而非 192，写死 240 会把 309 MiB 误报成超预算
        // （2026-11-04 报告 004311 的形状），与头部「内存档位=放开」自相矛盾。
        val bitmapBudgetMiB = SpriteBitmapSource.defaultNativeCacheBudgetBytes() / (1024L * 1024L)
        // 2026-11-08「查看其他人的位置」OOM 的取证读数：降级缓冲被渲染侧直接解成 native
        // 位图的次数。非 0 = 那条「不借 pixels 读口」的路真的在跑；若报告仍贴顶而这里是 0，
        // 就说明还有别处把降级载荷透明晋升回 Java 堆。只读计数，崩报路径 runCatching 兜住。
        val demotedNativeFrames = runCatching { graphicsWiring?.frames?.demotedPngFrames() ?: 0L }
            .getOrDefault(0L)
        return "图形池：${stats.entries} 项 / ${stats.created} 张已创建 / " +
            "像素驻留 ${stats.pixelBytes / (1024L * 1024L)} MiB（Java 堆）/ " +
            "压缩驻留 ${stats.demoted} 张（PNG 降级档，内容无损）/ " +
            "降级 native 取图 $demotedNativeFrames 次（不等同像素晋升）/ " +
            // 非 0 = 有合成层「该画却画不出来」。原版没有这个状态（CSV 父图整张解码，
            // 失败即 sprite 作废：AppContents.cs:273-278）；2026-11-06 布娃娃预览整页碎片
            // 当时这条读数还不存在，只能靠逐层打诊断挖，所以并进这一行。
            "合成源缺失 ${stats.sourceMisses} 笔 / " +
            "负号段 CSV 父图像素驻留 $csvResidentMiB MiB（Java 堆，与图形池记账互不重叠）/ " +
            "位图缓存 $bitmapCacheMiB MiB（native 堆，不计入 Java 堆上限；预算 $bitmapBudgetMiB MiB）" +
            nativeTail
    }

    /**
     * 报告里的图像**簿记**读数（脚本侧账本，与 [graphicsPoolReportLine] 的引擎侧实况并排）。
     *
     * 语料出处（只读事实，禁止改语料）：`ResourceManager.ERH:23` 的
     * `#DIM RM_GID_BITS, 100`（位图簿记位，每元素一个 64 位 INT，分配画像时按位占坑）
     * 与 `:18` 的 `RM_GMAP = "map:グラフィックス"`（已分配画像路径 → G_ID 的表）。
     * 「图像过多」（`ResourceManager.ERB:257` 的 THROW）在语料里的触发判式是
     * `SIF G_ID < RM_GID_BASE`，即**位打满**；而位图释放钩子整块被语料 [SKIPSTART]
     * 禁用（:304-365，只增不放是官方行为，ADR-005 不修）。2026-10-31 有 5 份「启动
     * 失败 = 图像过多」的玩家报告全是旧版，TODO(verify) 要设备端对拍才能定性
     * （真耗尽还是过早耗尽）——本行让未来的报告**自带**这两个聚合数，不用再等设备。
     *
     * 隐私口径：只报**计数**（popcount / map 元素数），不取任何键值与画像路径，
     * 与 [ErrorReport] 类头「无变量取值」的口径不冲突。
     *
     * 竞态与失败口径同 [graphicsPoolReportLine]：崩溃线程上读活 store 允许近似，
     * 逐元素 runCatching，任一步失败整行放弃（缺行不缺报告）。
     */
    internal fun imageBookkeepingReportLine(): String? {
        val store = runCatching { session?.store }.getOrNull() ?: return null
        val bitPopcount = runCatching {
            var pop = 0L
            for (i in 0 until RM_GID_BIT_ELEMENTS) {
                val v = store.readInt(RM_GID_BITS_VAR, longArrayOf(i.toLong()))
                pop += java.lang.Long.bitCount(v)
            }
            pop
        }.getOrNull() ?: return null
        val maps = runCatching { com.eratw.port.core.fn.mapPoolDiagnostics(store) }.getOrDefault(emptyList())
        val gmapEntries = maps.firstOrNull { it.first == RM_GMAP_NAME }?.second
        return buildString {
            append("图像簿记：位图位 ").append(bitPopcount)
            gmapEntries?.let { append(" / 图像表 ").append(it).append(" 项") }
            if (maps.isNotEmpty() && gmapEntries == null) {
                append(" / 图像表不在册（现役 map：").append(maps.joinToString(",") { it.first }).append("）")
            }
        }
    }

    /**
     * 报告里的「约会中／路人子」现场读数（2026-11-01 两条玩家反馈的观察面，diag-datingsync）。
     *
     * 读数本体在 core 只读口 [com.eratw.port.core.proc.datingForensics]——判式出处、常量
     * 出处（AutoConst 头）与「为什么这两条反馈必须靠设备侧读数才能定性」都写在那儿的类头。
     * 一句话版：①「独自出门却被 NPC 说'约会中吗？感情真好呢'」的判式是
     * `CFLAG:no:约会中 != MAIN_MAP`（4.980 语料判式，4.981 已在语料侧改成 `GET_HOME_MAP()`，
     * 引擎侧按 ADR-005 不补）；②「遇到路人子却没问要不要忘记」的闸是
     * `TALENT:RANDOM_CHARANUM:路人` 与 `GETBIT(TFLAG:一日一回, 9)` 两半。
     * 玩家存档拿不到，所以让**下一份报告自带这两组数**。
     *
     * 语义零改动：本行只读，不改任何变量、不碰状态机。竞态与失败口径同
     * [imageBookkeepingReportLine]：崩溃线程上允许近似，任一步失败整行放弃（缺行不缺报告）。
     * 隐私口径：只出数值与角色下标，不取 NAME／CSTR 等字符串。
     */
    internal fun datingForensicsReportLine(): String? {
        val store = runCatching { session?.store }.getOrNull() ?: return null
        val forensics = runCatching { com.eratw.port.core.proc.datingForensics(store) }.getOrNull()
            ?: return null
        return if (forensics.isEmpty) null else forensics.render()
    }

    /**
     * 报告里的 **Java 堆分项账面**（L5 heap-census：`used/max` 之外的一行分项）。
     *
     * ## 为什么单独一行（取证）
     * OOM 簇那份「<1% of heap free after GC」说明大头是**活对象**，而 native 两块账面已由
     * [graphicsPoolReportLine]（上一路 heap-budget）补上，Java 堆侧此前只有一个总数。本行从
     * 我们自己持有的结构（装载侧 / 运行侧 / 图形 Java 侧）逐项给估算字节，让下一份报告能直接
     * 看出「贴顶的 500 MiB 里谁占多少」。读数本体与**诚实口径**（估算、非 dominator tree、
     * 逐项误差方向、禁物化惰性表）全在 core 的 [com.eratw.port.core.diag.LiveSetCensus] 类头。
     *
     * ## fullScan = true 的理由
     * 本方法只在**报告终点路径**被调（崩溃/失败报告经 [ErrorReportCollector.memoryLines] 消费），
     * VM 线程早已停在抛点或异常栈上，此时扫一遍**已堆内物化**的字符串池取字符数不踩正在解码的
     * 惰性表（[com.eratw.port.core.diag.LiveSetCensus] 类头「只读、禁物化」第 2 条）。页中钉发走
     * [maybePinHeapCensusTrail]（fullScan = false），两条口径分开。
     *
     * 竞态与失败口径同 [graphicsPoolReportLine]：崩溃线程读活结构允许近似，整段 [runCatching]，
     * 取不到就缺这一行（缺行不缺报告）。
     */
    internal fun memoryCensusReportLine(): String? {
        val live = session ?: return null
        return runCatching {
            val aux = live.store.aux
            com.eratw.port.core.diag.LiveSetCensus.census(
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

    /**
     * CSV 资源表读数行（报告终点路径用；装配前给「未装载」，绝不返回 null 省掉这一行）。
     *
     * 端点侧按 [ResourceContents.CENSUS_LINE_PREFIX] 分桶：完整 4.981 语料应读到文件数
     * 266 附近、sprite 数 2795 附近；读出个位数 ⇒ 随包数据与 erbc 不同源，脚本侧
     * `SPRITECREATED` 恒 0、立绘整块不出现。这一行是那个缺陷唯一进报告的判据：
     * 不进玩家可见 UI、不改显示语义、不碰资源表本身，只读一个已格式化好的标量。
     */
    internal fun resourceCensusReportLine(): String =
        resourceCensusLine ?: ResourceContents.censusUnloadedLine()

    /** 上一次钉「Java 堆分项」轨迹行的毫秒（[HeapCensusPin.shouldPin] 的冷却基准）。 */
    private var lastCensusPinAtMs = -1L

    /**
     * 堆贴顶（≥ [HeapCensusPin.NEAR_TOP_PERCENT]%）时钉一发 Java 堆分项轨迹（**只 VM 线程调**）。
     *
     * ## 触发点与为什么挂这里
     * 挂在**既有**的回合边界水位判据 [releaseImagePixelsIfHeapHigh] 末尾（它本来就在 VM 线程算
     * used/max）：页贴顶时回收动作会在那里发生，同处补一行「贴顶的到底是什么」，报告与轨迹从此
     * 都能读出分项形状。冷却走 [HeapCensusPin] 的纯判式（[lastCensusPinAtMs] 自带计时段），
     * **不改** [releaseImagePixelsIfHeapHigh] 的任何回收语义，只在末尾多一发 forceSample。
     *
     * ## fullScan = false（与报告行分开）
     * 页中/回合边界这一发在 VM 线程热路径上，**绝不**扫字符串池或行段（那是十几万条 / 一次
     * toList），只取原子/锁内的标量读数（[com.eratw.port.core.diag.LiveSetCensus.census] 的
     * fullScan 参数）。真要看串池/段数，去读崩溃报告里的 [memoryCensusReportLine]（fullScan=true）。
     *
     * ## 只读、绝不抛
     * 整段 [runCatching]：读数、拼串、钉行任一失败都只当这一发没发生，诊断不许把自救或游戏拖崩。
     *
     * @param usedMiB / maxMiB 调用方（[releaseImagePixelsIfHeapHigh]）已算好的本挡 Java 堆水位，
     *        不重复取系统调用
     * @return 钉了返回那行原文（单测据此断言形状），没到水位/在冷却内/失败返回 null
     */
    internal fun maybePinHeapCensusTrail(usedMiB: Long, maxMiB: Long): String? {
        val now = SystemClock.elapsedRealtime()
        if (!HeapCensusPin.shouldPin(usedMiB, maxMiB, now - lastCensusPinAtMs)) return null
        lastCensusPinAtMs = now
        val line = runCatching { censusTrailLine(usedMiB, maxMiB) }.getOrNull() ?: return null
        runCatching { RunWatch.forceSample(view.context, line, heartbeat) }
        return line
    }

    /** 分项轨迹行本体（fullScan = false）：`heapcensus used=…MiB/max …` + [com.eratw.port.core.diag.LiveSetCensus.render]。 */
    private fun censusTrailLine(usedMiB: Long, maxMiB: Long): String? {
        val live = session ?: return null
        val aux = live.store.aux
        return com.eratw.port.core.diag.LiveSetCensus.census(
            program = live.runtime.program,
            store = live.store,
            console = live.console,
            gfxPool = aux.peek<GraphicsPool>(GRAPHICS_SLOT),
            spritePool = aux.peek<SpritePool>(SpriteBitmapSource.SLOT_SPRITE),
            fullScan = false,
            residentFace = reportResidentScratch,
        ).let {
            "heapcensus used=${usedMiB}MiB/$maxMiB " + it.render()
        }
    }

    /**
     * release 包可读的低频内存采样（20 秒一条，缺省关）。
     *
     * ## 为什么另开一条通道
     * 板上那个 0.2.3 是 release（`isDebuggable=false`，实测 `run-as: package not debuggable`），
     * 于是 `run-as` 与 DevTools 文件通道都用不了 —— 而 BOARD 顶部那条未结欠账（G 系像素
     * 常驻 Java 堆）要的正是设备侧堆归因。本通道把开关与读数都放在**外置**应用目录
     * （adb 直接可读写，不需要 run-as、也不需要重新出包）：
     * ```
     * adb shell touch /sdcard/Android/data/com.eratw.port.android/files/eratw/mem-sample.on
     * adb shell cat    /sdcard/Android/data/com.eratw.port.android/files/eratw/mem-sample.txt
     * ```
     *
     * ## 代价口径
     * 开关文件不存在时 = 一次 `long` 比较 + 一次 `stat`，20 秒一回，UI 线程无感。
     * 开关存在时统计在调用线程取完（全部是原子/`@Volatile`/池内锁口径，见下），
     * **只有追加写文件**丢给一条 daemon 线程：重绘循环里绝不碰磁盘写。
     * 任何一步抛（读计数、拼串、建目录）都只落日志后放弃这一条，绝不让诊断把
     * 重绘循环拖崩 —— 采样死在第 3 条读数上，比没有第 3 条读数更糟。
     *
     * ## 线程
     * 只在 UI 线程的重绘循环里调。读的几枚数分别是：
     * [com.eratw.port.core.host.GraphicsPool.residentBytes] / `entryCount`（原子计数 /
     * HashMap 引用快照，**不迭代** VM 线程正在写的容器）、[SpritePool.size]（池内锁）、
     * [SpriteBitmapSource.cacheBytes]（`LruCache.size()` 自带同步）。
     */
    fun sampleMemoryIfNeeded(nowMs: Long) {
        if (closed || nowMs < memSampleNextMs) return
        memSampleNextMs = nowMs + MEM_SAMPLE_INTERVAL_MS
        val external = runCatching { view.context.getExternalFilesDir(null) }.getOrNull() ?: return
        val dir = File(external, "eratw")
        if (!runCatching { File(dir, MEM_SAMPLE_SWITCH).isFile }.getOrDefault(false)) return
        val line = runCatching { memSampleLine(nowMs) }.getOrNull() ?: return
        Log.i(TAG, "mem $line")
        // 同一时刻最多一条写线程：真机（vivo）见过设备 IO 被压死、一次小写卡住几十秒的现场，
        // 那时若不管闸门就会每 20 秒叠出一条线程。丢读数比叠线程好——采样本身是旁路。
        if (!memSampleWriting.compareAndSet(false, true)) return
        val out = File(dir, MEM_SAMPLE_FILE)
        Thread {
            try {
                runCatching {
                    // 上限护栏：开关是 adb 手动点的，忘了关就一直涨；8 MiB 之后停写而不是删表，
                    // 已落的读数一条不丢（取证时「前半局怎么涨的」正是要看的那半）。
                    if (out.length() < MEM_SAMPLE_MAX_BYTES) out.appendText(line + "\n")
                }
            } finally {
                memSampleWriting.set(false)
            }
        }.also { it.isDaemon = true; it.name = "eratw-mem-sample" }.start()
    }

    /**
     * 一条采样读数（`key=value` 单行，列序固定，方便 `grep`/`awk` 直接对齐两次采样）。
     *
     * `heap` 是 ART 的 Java 堆（已用/上限，上限即 `largeHeapMemory` 的 512 MiB 挡），
     * `native` 是 Bitmap 像素这类原生分配 —— 两侧分开看才能判断「贴顶的是 IntArray 常驻」
     * 还是「解码没释放」。缺读数的列给 `-1`（会话未装配 / 槽未登记），不省列，
     * 免得对齐脚本被变长行绊倒。
     */
    private fun memSampleLine(nowMs: Long): String {
        val rt = Runtime.getRuntime()
        val aux = session?.store?.aux
        val gfx = aux?.peek<com.eratw.port.core.host.GraphicsPool>("gfx")
        val sprites = aux?.peek<SpritePool>("sprite")
        return buildString {
            append("t=").append(nowMs)
            append(" wall=").append(System.currentTimeMillis())
            append(" heap=").append(mib(rt.totalMemory() - rt.freeMemory()))
            append("/").append(mib(rt.maxMemory()))
            append(" native=").append(mib(android.os.Debug.getNativeHeapAllocatedSize()))
            append(" gfx=").append(gfx?.entryCount() ?: -1)
            append("/").append(mib(gfx?.residentBytes() ?: -1L))
            append(" bitmapCache=").append(mib((graphicsWiring?.frames?.cacheBytes() ?: -1).toLong()))
            append(" sprites=").append(sprites?.size() ?: -1)
            append(" flat=").append(if (flatStatementsActive) "on" else "off")
            append(" threads=").append(Thread.activeCount())
            // 本屏位图账面（native：整屏保留面 + 背景烘焙面 + 行缓存 + 行池）。
            // 加在**行尾**：前面的列序是既有取证脚本按位读的，不动它。
            append(" view=").append(mib(runCatching { view.viewBitmapCacheBytes() }.getOrDefault(-1L)))
        }
    }

    /** 字节 → MiB（整除；负数原样返回，用作「没有这一项读数」的哨兵）。 */
    private fun mib(bytes: Long): Long = if (bytes < 0L) bytes else bytes / BYTES_PER_MIB

    /**
     * 失败现场落一份错误报告（方案① 报错收集），由失败屏的「复制报告 / 分享报告」消费。
     *
     * 三条纪律：① 绝不让报告生成把失败路径再炸一遍（[ErrorReportCollector.collect]
     * 全程 runCatching，失败返回 null）；② 返回 null 时 [lastReportFile] 保持上一份
     * （按钮仍可分享旧报告，聊胜于无）；③ 报告是脱敏口径（无变量取值、无存档数据），
     * 谁要往里加内容先回去读 core 侧的隐私口径。
     */
    private fun writeErrorReport(kind: String, error: Throwable?, extraLines: List<String> = emptyList()) {
        // R3：错误也是因果链的一环（类型 + 失败族/相位），与报告落盘同一处发射。
        EventJournal.record(
            "error",
            mapOf("kind" to kind, "phase" to lastFailureScreenKind.name),
        )
        // 心跳必须带上：本宿主就是心跳的持有者，漏传等于把「三路线程当时活不活」这条
        // 唯一的判据从报告里抹掉（判据与为何必传见 ErrorReportCollector.collect 的参数 KDoc）。
        //
        // 拼接次序（2026-11-05 修正，先前写反过一次）：补充现场那一节在 core 侧是
        // `takeLast(EXTRA_MAX_LINES)` 的**尾部保留**（`core-vm/.../diag/ErrorReport.kt:161-164`
        // + `:201` 的窗口 = 80），而采集侧在传进来的这块之后还要再垫内存读数 / 约会现场 /
        // 存档留痕 / 心跳 / 冻结现场 / 守护死因（`ErrorReportCollector.collectFull` 的
        // `extraSectionLines`）⇒ **本函数交出去的这块整体排在最前，满档时从它的头部开始剪**。
        // 所以头部让给节流账面（`throttleSummary()` 最多 SUMMARY_TOP+1 = 4 行，被抑制的条数
        // 另有运行轨迹与 logcat 两个出口），把调用方的现场行（「中止点：…」「证据：…」、
        // 「状态机终局：…」「阻塞：…」）垫到块尾 —— 那些行没有第二个出口，剪掉就永久消失。
        // 没抑制过 ⇒ `throttleSummary()` 给空表，本表达式等于 `extraLines` 本身，
        // **首份报告正文逐字不变**（L6 用单测钉死这条口径，故这里不写「无账也补一行」的默认值）。
        val suppressed = runCatching { ErrorReportCollector.throttleSummary() }.getOrDefault(emptyList())
        lastReportFile = ErrorReportCollector.collect(
            view.context,
            this,
            kind,
            error,
            suppressed + extraLines,
            heartbeat,
        )
        // 方案③：端点已配置时后台补发（daemon 线程，VM 线程不等网络）；
        // 未配置时这是第一行就返回的 no-op，日常开发包零网络行为。
        ErrorReportUploader.uploadPendingAsync(view.context)
    }

    private fun describe(t: Throwable, trace: LoadTrace): String {
        val failure = BootFailures.classify(t)
        // 族别就地判（本函数已经跑过 classify），且排在各 return 之前 —— 一个会话里
        // describe 可能被叫第二遍（按过重试），后置会让上一轮的族别残在这张屏上。
        // 产物缺失/容器坏/映射失败才归 ARTIFACT；堆不足与其余装载期抛错归 LOAD
        // —— 前者有专门的兼容档按钮，后者提产物是误导。
        lastFailureScreenKind = when (failure.kind) {
            BootFailureKind.MISSING_ARTIFACT,
            BootFailureKind.CORRUPT_ARTIFACT,
            BootFailureKind.MAPPING_FAILED,
            -> FailureScreenKind.ARTIFACT
            else -> FailureScreenKind.LOAD
        }
        // 堆不足是唯一固定句子的分支：core 层不落 JVM 原话（BootFailures 的口径），
        // 但 2026-10-30 真机实证「峰值 167 / 上限 512 仍 OOM」—— 单看固定句无法区分
        // 「Java 堆容量不足」与「native/线程分配失败」同被 classify 归并成堆不足，
        // 排障必须拿到 OOM 的类名与首行原话。文案由 [heapShortageDuringLoad] 按玩家话给，
        // 错误报告里另有完整堆栈兜底。
        if (failure.kind == BootFailureKind.HEAP_TOO_SMALL) {
            return heapShortageDuringLoad(t, trace)
        }
        if (failure.kind == BootFailureKind.MISSING_ARTIFACT) return missingArtifacts()
        val msg = failure.detail?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "$FAILURE_PREFIX$msg${scriptLocation(t)}"
    }

    /**
     * `cause` 链上最深的那个 [OutOfMemoryError] 的短标签（`类型: 首行`），无 OOM 时空串。
     *
     * 只取首行：ART 的 OOM 消息里「Failed to allocate a N byte allocation with M free bytes」
     * 这半句已经够定性；后面的「till a recent GC it was…」长尾不上屏。
     * `pthread_create failed` / `Could not allocate JNI Env` 这两类非 Java 堆 OOM 是
     * 2026-10-30 失败屏读数矛盾（峰值远低于上限仍 OOM）的头号嫌疑，靠它区分。
     */
    private fun oomDetail(t: Throwable): String {
        var node: Throwable? = t
        var oom: OutOfMemoryError? = null
        var depth = 0
        while (node != null && depth < 16) {
            if (node is OutOfMemoryError) oom = node
            node = node.cause
            depth++
        }
        val e = oom ?: return ""
        val firstLine = e.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() }
        val body = if (firstLine != null) "${e.javaClass.simpleName}: $firstLine" else e.javaClass.simpleName
        return failureCopy.fill(failureCopy.oomDetailPrefixFormat, body)
    }

    /**
     * 脚本错误在屏上补一句现场（`函数名 行N`）——`StepLimitExceeded` 这类保护闸只报
     * 「疑似死循环」，真机上没有 logcat（Origin OS 把本应用的日志整条丢掉，2026-10-21 实测）
     * 时这一句就是唯一的定位信息。[CodeEE] 一路都带 funcName/line，之前被 [describe] 丢掉了。
     *
     * 行号取 [CodeEE.displayLine]（**1 基，与原版对齐**）：[CodeEE.line] 是内部 0 基下标
     * （产物行号表与 pc↔行号对照用的就是它，不许动），照它直打会比原版屏上少一行。
     * 换算只在 [CodeEE.displayLine] 一处，这里不加一（spec: ScriptPosition.cs:15-17 ——
     * 原版在位置对象自己身上归一，`Process.cs:504` 的死循环弹框与 `:610`/`:628`/`:636`
     * 的报错三行打的都是归一后的 1 基值）。
     */
    private fun scriptLocation(t: Throwable): String {
        var node: Throwable? = t
        var depth = 0
        while (node != null && depth < 16) {
            if (node is CodeEE) {
                val parts = ArrayList<String>(2)
                node.funcName?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (node.line >= 0) parts += failureCopy.fill(failureCopy.scriptLocationLineFormat, node.displayLine)
                return if (parts.isEmpty()) "" else failureCopy.fill(failureCopy.scriptLocationWrapFormat, parts.joinToString(" "))
            }
            node = node.cause
            depth++
        }
        return ""
    }

    /**
     * 乙族（装载中真 OOM）的屏幕文案 —— core 的固定句按**玩家话**重写，实测读数照旧保留。
     *
     * 为什么不再直接用 [BootFailures.HEAP_SHORTAGE_TEXT]：那句是排障口径（「堆不足 / largeHeap /
     * 挡位 / ≥6 GB RAM 机型」），玩家读不懂「堆」，也不知道自己能做什么。本移植侧给出的动作只有
     * 两个 —— 换一台内存更大的手机，或者点屏上那颗「少占内存再试」。
     * core 那句一字不动（`BootFailureTest` 逐字锁定它），改写只发生在宿主这一层。
     *
     * 读数分工：`最高用到` 是装载轨迹里最高的一段（[LoadTrace.peakHeap]），`最多给游戏` 是
     * `Runtime.maxMemory()`，`停在` 是峰值所在段 —— 三者一致才能分清「机型档位不够」与
     * 「装载前堆已被挤满」；[oomDetail] 补 OOM 原话（`pthread_create failed` 这类非 Java 堆
     * 分配失败同被归并到本族，靠它区分）。
     */
    private fun heapShortageDuringLoad(t: Throwable, trace: LoadTrace): String {
        val peak = trace.peakHeap()
        return heapOomText(
            peakMiB = peak?.usedMiB ?: trace.usedMiB(),
            maxMiB = trace.maxMiB(),
            stage = peak?.stage,
            oomDetail = oomDetail(t),
        )
    }

    /**
     * 装载起点堆被占的文案（阈值判据见 [STALE_SESSION_BASELINE_MIB]）。
     *
     * 与「堆预检不足」分开写两条：那条是机型档位不够（换机才有救），这条是**同一个进程里
     * 上一局没让出堆**，玩家能做的只有一件事 —— 结束进程重开，所以文案只给这一条动作。
     */
    private fun staleSessionHeap(usedMiB: Long): String {
        val maxMiB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        // 指引与出口必须说同一件事（2026-10-31）：这句话原先让玩家「结束本应用进程后重开」，
        // 而屏上那颗按钮写的是「结束应用」、按下去也只是退出 —— 玩家得自己回桌面找图标。
        // 现在那颗按钮就叫「重启应用」并且真的把进程拉回来，文案跟着改成它的名字。
        return failureCopy.fill(failureCopy.staleSessionHeapFormat, usedMiB, maxMiB)
    }

    /** Debug 开关：`applicationInfo.flags` 带 `FLAG_DEBUGGABLE` 才打装载埋点（release 静默）。 */
    private fun isDebuggable(): Boolean =
        (view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * 产物缺失的唯一文案（[start] 的前置检查与装载期二次确认共用）。
     *
     * 两条候选产物目录**并列**：外部根（adb 可推）在前、内部根在后；屏上一眼能看出该推哪里。
     * 句式沿用既有的「缺少 <目录> 下的 program.erbc / program.meta」，只把单个目录换成并列。
     */
    private fun missingArtifacts(): String {
        val dirs = storage.candidateArtifactDirs(ARTIFACT_DIR).joinToString(failureCopy.artifactDirsSeparator) { it.absolutePath }
        return failureCopy.fill(failureCopy.missingArtifactsFormat, dirs, PROGRAM_FILE, META_FILE)
    }

    /**
     * 装载前确认产物是普通文件：缺失/被换成目录都按 `NoSuchFileException` 处理，
     * 让 [BootFailures.classify] 落到 [BootFailureKind.MISSING_ARTIFACT]。
     */
    private fun requireArtifact(file: File) {
        if (!file.isFile) throw NoSuchFileException(file.path)
    }

    /**
     * 装载期先存下的一条提交：文本 + 是否跳过（`PressEnterKey(keySkip = …)` 的第一参）
     * + 鼠标按钮位（非 null = 画面按钮点击，[submitButton] 那条路；补交时不得丢这一维）。
     */
    private class PendingInput(
        val text: String,
        val mesSkip: Boolean,
        val mouseButton: Int? = null,
    )

    /**
     * 占位 sink：装配期先接，拿到 `store.aux` 后换成 [TableSoundSink]。
     *
     * [shut] 是 [GameHost.close] 的出口闸：关闭之后 VM 线程可能还在跑一条语句，
     * 任何一个 `delegate` 调用都可能重新建出 `MediaPlayer`。闸放在本类而不是只换
     * delegate，是因为「换 delegate」与「VM 线程读 delegate」之间还留着一条指令级的窗口
     * （装配收尾也可能在 close 之后才把真 sink 挂上来）。
     */
    private class DelegatingSoundSink : SoundSink {
        @Volatile
        var delegate: SoundSink = NoopSoundSink

        @Volatile
        private var shut = false

        /** 关死出口并换上空实现；幂等，见 [GameHost.close]。 */
        fun shut() {
            shut = true
            delegate = NoopSoundSink
        }

        /** 开着的 delegate；null = 已关闭，调用方直接丢弃这次音频动作。 */
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

    companion object {
        /** `erbcc compile` 的缺省输出目录（见 `ErbcArtifacts`）。 */
        const val ARTIFACT_DIR = "erbc.v1"
        /** 真机联调标记：游戏根存在该空文件时越过堆预检，用来实测真实装载峰值（产品默认不存在）。 */
        const val FORCE_LOAD_MARKER = "force_load"
        const val PROGRAM_FILE = "program.erbc"
        const val META_FILE = "program.meta"
        private const val SOUND_DIR_NAME = "sound"
        private const val VM_THREAD_NAME = "eratw-vm"

        // ---- 图像簿记诊断（随包语料事实，见 [imageBookkeepingReportLine]；改语料必须同步这里）----

        /** 语料位图簿记变量名（`ResourceManager.ERH:23` 的 `#DIM RM_GID_BITS, 100`）。 */
        internal const val RM_GID_BITS_VAR = "RM_GID_BITS"
        /** 位图簿记的元素个数（同上 ERH 行；每元素一个 64 位 INT 的位掩码）。 */
        internal const val RM_GID_BIT_ELEMENTS = 100
        /**
         * 语料图像表 map 名。**含 `map:` 前缀**：`ResourceManager.ERH:18` 的
         * `#DIMS CONST RM_GMAP = "map:グラフィックス"` 是个字符串常量，其**值**才是
         * 传给 MAP_CREATE / MAP_GET 一族的表名 —— 拿裸「グラフィックス」去对
         * [com.eratw.port.core.fn.mapPoolDiagnostics] 的键永远 miss（三审必修项）。
         */
        internal const val RM_GMAP_NAME = "map:グラフィックス"

        /** `program.meta` 并行解析的线程名（与 core-vm 单测断言的是同一个字面量）。 */
        private const val META_THREAD_NAME = "eratw-meta"

        /**
         * `eratw-meta` 的收口上限（毫秒）：CMP-18 的 `close()` 与装配收尾都用它。
         *
         * 取值与 [com.eratw.port.core.host.ParallelTask.DEFAULT_JOIN_MS] 同口径（1 s）：
         * 侧车解析正常 ≤8 s、但 interrupt 会打断文件读，实测能在这条线上收口；
         * 超时就落日志（线程是 daemon，随进程退出），不用无上限 join 换 ANR。
         */
        private const val META_JOIN_MS = ParallelTask.DEFAULT_JOIN_MS

        /**
         * `UPDATECHECK` 询问框的作答上限（毫秒）：**45 秒**。
         *
         * 原版这一句是 `MessageBox.Show`（`FunctionIdentifier.cs:2802`），桌面版可以无限等玩家；
         * 移植侧的等待发生在 VM 线程上，等不到就是整局停在一条语句里 —— 必须给一条线。
         * 取值判据：这条链前面已经有一次「连不上就 3 秒」的取流（`UpdateCheckTransport`），
         * 玩家此刻正盯着屏，45 秒足够读完两行文案并伸手点一次；再长只是把「脚本没反应」
         * 拖成「游戏没了」。到点按原版的异常支收敛（RESULT=3，`:2827-2832`），
         * **不**当成「玩家拒绝」（那是 RESULT=1，`:2815`）—— 定性见 [UpdateAskTicket]。
         */
        private const val UPDATE_ASK_TIMEOUT_MS: Long = 45_000L

        /**
         * 跨 Activity 交接的等待上限（毫秒）：新一局的装载线程在堆预检之前等上一局 VM 线程
         * 退出，最多这么久（判据与两种结局见 [awaitPreviousHosts]）。
         *
         * 取 15 s 的依据：正常情形旧链在 `close()` 的 interrupt 后 ≈30 ms 内就退出（VM 每
         * 65536 条指令看一次中断位），15 s 是给「装载中途关闭 / 段交界收口 / 慢机型」的余量；
         * 再长也只是把玩家按在 indeterminate 装载页上，不能提高失败后的自救能力。
         * **不要**把它当成新的「重启停止」等待：等不到就交给既有堆基线硬闸说真话。
         */
        private const val SESSION_HANDOFF_WAIT_MS = 15_000L

        private const val TAG = "GameHost"

        /**
         * `releaseUnusedGraphics` 的「够大才值得动」门槛（像素数）。
         *
         * 取 256×256：低于这个量的缓冲一个才 256 KiB，收一堆也就几 MiB，却要陪上一次
         * 「脚本手里的 gid 变未创建」的风险 —— 不值。整屏合成图与立绘工作表都在门槛之上。
         */
        private const val MIN_UNUSED_RELEASE_PIXELS = 256 * 256

        /**
         * 预算档（[releaseUnusedGraphics] 的 `usePixelBudget`）的入队门槛（像素数）。
         *
         * 与 [MIN_UNUSED_RELEASE_PIXELS] 分开：那一档是「一次扫干净，只挑大的」，这一档
         * 是「收到预算内，能收的都算」。2026-11-01 的设备读数里图形池 657 张均摊 ≈266 KiB
         * （约 258×258），一刀 256×256 门槛会把其中相当一部分直接划出回收面 —— 于是 167 MiB
         * 驻留里有一大块谁也动不了。取 64×64（16 KiB）：更小的多是描边掩膜那类一过性小图，
         * 攒一堆也不够一个 MiB，不值得冒「脚本手里的 gid 变未创建」的风险。
         */
        private const val BUDGET_REAP_MIN_PIXELS = 64 * 64

        /**
         * 存档前预防档的水位线（百分比）：Java 堆用到这个比例才允许 `reclaimMemory(urgent=false)`
         * 动手，低于它一律返回 0。
         *
         * 为什么是 90 而不是 0.2.36 那两档的 80/92：那两档跑在**回合边界与页中**，是常规兜底；
         * 这一档跑在**每次存档前**，只补「存档那一秒正好没人兜」的窗口。定低了会把常规兜底
         * 该做的事提前做掉（画面白抽一下），定太高又赶不上存档要的 2~4 MB。
         */
        private const val SAVE_PRE_RECLAIM_PERCENT = 90L

        /** 解包进度日志的节流口径：整轮最多打 [LOG_PROGRESS_STEPS] 条，两条间隔不小于 [LOG_PROGRESS_MS]。 */
        private const val LOG_PROGRESS_STEPS = 16
        private const val LOG_PROGRESS_MS = 500L

        // 失败屏标题 `FAILURE_PREFIX` 已上提为文件级 internal 常量（两族文案函数共用一处）。

        /**
         * 装载起点「堆已经被占」的判据（MiB）：GC 之后仍高于这个值就拒绝开局。
         * 依据：干净进程实测 15 MiB（模拟器 2026-10-28，`dumpsys meminfo` Java Heap Alloc），
         * 出过事的真机是 276 MiB（上一局没让出堆，解码到段3 顶穿 512 MiB 上限）。
         * 120 MiB 高于任何正常开局水位，又留足了「先说清楚再死」的余量。
         */
        private const val STALE_SESSION_BASELINE_MIB = 120L

        /** DevTools `turnlog` 环形缓冲上限：够看最近 64 页即可。 */
        private const val TURN_LOG_LIMIT = 64

        /** 内存采样节流（毫秒）：20 秒一条 —— 够看清「一局里 G 系像素涨不涨」，又不至于把日志刷满。 */
        private const val MEM_SAMPLE_INTERVAL_MS = 20_000L

        /** 采样开关文件名（外置 `eratw/` 目录，adb 可写，不需要 run-as）。 */
        private const val MEM_SAMPLE_SWITCH = "mem-sample.on"

        /** 采样读数落地文件名（同目录，追加写）。 */
        private const val MEM_SAMPLE_FILE = "mem-sample.txt"

        /** 采样文件的大小上限：到顶后**停写**（不删已有读数），防「开关忘了关」把外置目录写满。 */
        private const val MEM_SAMPLE_MAX_BYTES = 8L * 1024 * 1024

        private const val BYTES_PER_MIB = 1024L * 1024L
    }
}

/**
 * native 位图账面的一次快照（全部 MiB）——「收了没有」的唯一可核对证据。
 *
 * 三枚读数分属三种口径，混不得：
 * - [bitmapCacheMiB]：平台侧三张 `LruCache` 的**账面**（`LruCache.size()` 的字节和），
 *   `evictAll` 之后立刻归零 —— 它证明「条目交出去了」，**不**证明像素已经回到 native 堆
 *   （工作表条目还要等 `SpriteBitmapSource` 的宽限期回收队列被主线程排干）。
 * - [viewMiB]：本屏四块位图（整屏保留面 + 背景烘焙面 + 行栅格缓存 + 行位图池）的账面，
 *   同样是「引用交出去」的口径，像素由 `NativeAllocationRegistry` 随下一次 GC 归还。
 * - [nativeAllocMiB]：`Debug.getNativeHeapAllocatedSize()` —— **进程 native 堆的真实驻留**，
 *   是上面两条「说了有没有做到」的裁判。trim 前后各取一发，收了不降的那一类
 *   （延迟回收队列没人排干）就在这两列的差里露形。
 */
internal data class NativeCacheSnapshot(
    val bitmapCacheMiB: Long,
    val viewMiB: Long,
    val nativeAllocMiB: Long,
)

/**
 * 一次 native 位图收口的前后账面（[GameHost.trimGraphicsCaches] 的返回值）。
 *
 * 存在的理由：`onTrimMemory` 那条轨迹行原来只写 `freed=<Java 像素>MiB`（收的是 Dalvik 堆上
 * 的 `IntArray`），native 侧收了几 MiB、收完之后进程 native 到底降没降，**盘上全是空白** ——
 * 「trim 了但 native 纹丝不动」这类结论只能靠猜。本类把那三枚读数钉成一行文本，
 * 判式与格式化都是纯函数（[NativeTrimPlan]），单测直接测。
 */
internal class NativeTrimReadout(
    val before: NativeCacheSnapshot,
    val after: NativeCacheSnapshot,
    val deep: Boolean,
) {
    /** 轨迹/报告用的一行读数（不带前缀，前缀由调用方给）。 */
    fun suffix(): String = NativeTrimPlan.readoutLine(before, after, deep)

    /** 本屏账面这次交出去的 MiB（负数按 0：并发取图可能刚登记回一条，不是漏收）。 */
    fun viewFreedMiB(): Long =
        if (before.viewMiB >= after.viewMiB) before.viewMiB - after.viewMiB else 0L
}

/**
 * native 位图收口的**分档判式 + 读数成文**（纯函数，app 单测直测）。
 *
 * ## 为什么要再分一张档表，[GraphicsTrimPlan] 不是已经管这件事了吗
 *
 * `GraphicsTrimPlan.dropsSheets` 答的是「这一档要不要收位图缓存」，七个系统档全收；
 * 它管不到「收多深」。深与浅的分界是**这一屏接下来还会不会被画**：
 * `RUNNING` 系（5/10/15）玩家正看着，动整屏保留面就等于当场闪一帧空白；
 * 后台系（20/40/60/80）界面已经不可见，留着那两张整屏面只是让 lmkd 多一个杀你的理由。
 * 与 `dropsSheets` 一样，这里是**逐档点名**，不拿数值比大小（两套不成序刻度的教训见
 * [GraphicsTrimPlan] 的类 KDoc）；未知档（0/负数/OEM 自造）一律不深收。
 */
internal object NativeTrimPlan {

    private const val BYTES_PER_MIB = 1024L * 1024L

    /** 深档 = 后台系四档（界面已不可见）；`RUNNING` 系三档与未知档只收行缓存。 */
    private val DEEP_LEVELS = setOf(
        android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN, // 20
        android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, // 40
        android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE, // 60
        android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE, // 80
    )

    /** 这一档收多深：true = 连整屏保留面与背景烘焙面一起交还。 */
    fun isDeep(level: Int): Boolean = level in DEEP_LEVELS

    /**
     * 读数成文：` bitmap=120->0MiB view=41->8MiB native=300->244MiB deep`（逐字与
     * [NativeTrimPlanTest] 钉住的输出同形）。
     *
     * `native=` 那一列是 `Debug.getNativeHeapAllocatedSize()`（进程真实 native 驻留），
     * 前面两列只是账面 —— 两列都放是有意义的：账面降了而 native 没降 = 像素还压在
     * 没人排干的延迟回收队列上（本车道登记的 `SpriteBitmapSource` 欠账正是这一形状）。
     * 深档才在尾部挂 ` deep`，浅档不挂 —— 让轨迹里一眼看得出那一发收没收到整屏面。
     */
    fun readoutLine(before: NativeCacheSnapshot, after: NativeCacheSnapshot, deep: Boolean): String =
        buildString {
            append(" bitmap=").append(before.bitmapCacheMiB).append("->").append(after.bitmapCacheMiB).append("MiB")
            append(" view=").append(before.viewMiB).append("->").append(after.viewMiB).append("MiB")
            append(" native=").append(before.nativeAllocMiB).append("->").append(after.nativeAllocMiB).append("MiB")
            if (deep) append(" deep")
        }

    /** 报告行用的单点读数（不是差分）：`本屏位图 41 MiB / native 驻留 286 MiB`。 */
    fun reportSuffix(snapshot: NativeCacheSnapshot): String =
        "本屏位图 ${snapshot.viewMiB} MiB（native：整屏保留面 + 背景烘焙面 + 行缓存 24 MiB + 行池 6 MiB）/ " +
            "进程 native 驻留 ${snapshot.nativeAllocMiB} MiB（Debug.getNativeHeapAllocatedSize）"

    /** 字节 → MiB 的共用换算（本对象与宿主各自内部使用，避免两处各写一份 1024*1024）。 */
    fun miB(bytes: Long): Long = if (bytes <= 0L) 0L else bytes / BYTES_PER_MIB
}

/**
 * 脚本屏幕像素快照（GFX-06）：[CanvasConsoleView] 布局完成后的一次性取值，
 * UI 线程在 [GameHost.start] 里取走、经 [GameSession] 注入 [com.eratw.port.core.proc.ConsoleBuiltinHost]，
 * 供 `CLIENTWIDTH` / `CLIENTHEIGHT` 使用（消费点见 GameSession KDoc）。
 *
 * 两个像素字段是**设备像素**（与原版 MainPicBox.Width 同量纲），与
 * `ConsoleScreen.clientWidth` 的半角格列数是两个量。会话按 [deviceFontPixelSize] 把它们
 * 折回脚本 px 后才交给脚本（`ScriptPixels`：脚本拿读数开 CG 画布，渲染层再按同一比例折设备像素）。
 *
 * [deviceFontPixelSize] **不是**配置字号（2026-10-29 的收口仍成立）：`FontSize` 由会话按生效
 * 配置自报（core 侧 `GameSession.scriptFontPixelSize`）。这里一度喂过 page-fit 实测字号当配置字号，
 * 代价是脚本里「量宽用的尺」与「排格子用的尺」分成两条（语料排格读 GETCONFIG 的 18，量宽却按
 * 实测 42 量），指令按钮因此每个只占一半应有宽度、一行十几个就整排错位。现在这一格只做
 * 设备像素 → 脚本 px 的比例分母，从不进 `GETCONFIG("フォントサイズ")` / `builtinHost.fontSize`。
 */
private data class ScriptPixelMetrics(
    val clientWidthPx: Long,
    val clientHeightPx: Long,
    val deviceFontPixelSize: Int,
    /** View 同尺逆折算的脚本 px 版面宽（0 = 未接线；播种点见 ConfigMethods.seedHostState）。 */
    val windowWidthScriptPx: Long,
)

/** 从 View 的包装 Context 找回宿主 Activity（对话框必须用 Activity 上下文）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Java 堆分项轨迹的**钉发判式**（L5 heap-census；纯函数，app 单测直测，口径同 [NativeTrimPlan]）。
 *
 * 为什么单独一档而不是复用 [RunTrail.shouldReleaseImagePixels]（80%）/
 * [RunTrail.shouldReleaseUnusedGraphics]（92%）：那两档是**回收动作**的门槛，钉账本是**取证**，
 * 二者取值理由不同 —— 分项行只在玩家已经贴顶（`<1% free after GC` 那簇）时才值得占一行滚动窗口。
 * 取 90 而非更低：低于 80 会把「正常装载峰值」也钉进来灌满 64 行窗口（教训见
 * [RunTrail.LONG_TURN_MS] 的取舍）；取 90 而不是 92：这一发是**读数不是动作**，比回收档早一点
 * 触发能在真正 OOM 前一页就把形状落到盘上（回收那一刀可能收回 0，见 heap-budget 回执）。
 *
 * 冷却取 30 s（同 [RunTrail.RELEASE_COOLDOWN_MS] 量级、与 [HeartbeatJudgment] 的冻结钉发同段）：
 * 贴顶往往连续好几页，冷却保证「涨/没涨」读得出斜率而不把窗口灌成同一页的分重复件。
 */
internal object HeapCensusPin {

    /** 堆贴顶判定的百分比阈值（已用 / 上限）。 */
    const val NEAR_TOP_PERCENT: Long = 90L

    /** 两次钉发的最小间隔（毫秒）：负数距 = 还没钉过（首发自钉）。 */
    const val PIN_COOLDOWN_MS: Long = 30_000L

    /**
     * 这一发要不要钉：水位 ≥ [NEAR_TOP_PERCENT]% 且距上次钉已越过 [PIN_COOLDOWN_MS]。
     * `maxMiB ≤ 0`（堆上限读不到，桌面装置）一律 false —— 没有可信分母就不钉，宁缺勿误。
     * @param sinceLastPinMs 距上一次钉发的毫秒；负数 = 本页/本会话还没钉过（首发必走）
     */
    fun shouldPin(usedMiB: Long, maxMiB: Long, sinceLastPinMs: Long): Boolean =
        maxMiB > 0L &&
            usedMiB * 100L >= maxMiB * NEAR_TOP_PERCENT &&
            (sinceLastPinMs < 0L || sinceLastPinMs >= PIN_COOLDOWN_MS)
}
