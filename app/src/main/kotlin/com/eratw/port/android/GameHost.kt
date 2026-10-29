package com.eratw.port.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.eratw.port.core.audio.AUDIO_CHANNEL_SLOT
import com.eratw.port.core.audio.SoundChannelTable
import com.eratw.port.core.audio.TableSoundSink
import com.eratw.port.core.config.ConfigStore
import com.eratw.port.core.diag.ProgramDiagnostics
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.fn.BackgroundAdmission
import com.eratw.port.core.fn.IMAGE_LOAD_TRACE_SLOT
import com.eratw.port.core.fn.ImageLoadTrace
import com.eratw.port.core.fn.KeyEventInputState
import com.eratw.port.core.fn.TextBoxHost
import com.eratw.port.core.host.AndroidKeyCodes
import com.eratw.port.core.host.BeginAfterPreflight
import com.eratw.port.core.host.ConsoleButtonPointing
import com.eratw.port.core.host.ConsoleOption
import com.eratw.port.core.host.IMAGE_DECODER_SLOT
import com.eratw.port.core.host.mouseStateOf
import com.eratw.port.core.host.ImageDecoderHost
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.NoopSoundSink
import com.eratw.port.core.host.ParallelTask
import com.eratw.port.core.host.SoundSink
import com.eratw.port.core.loader.BootFailureKind
import com.eratw.port.core.loader.BootFailures
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.BeginType
import com.eratw.port.core.proc.EngineFunctionPolicy
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.InputSinkClosedException
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.core.proc.StepOutcome
import com.eratw.port.core.save.SaveFolderMigrationLog
import com.eratw.port.core.stmt.StmtInternAudit
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
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.platform.text.AndroidGlyphMaskHost
import com.eratw.port.platform.text.AndroidTextMeasure
import com.eratw.port.render.adapter.ConsoleFeed
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.button.toPointing
import com.eratw.port.render.canvas.CanvasConsoleView
import com.eratw.port.render.view.ConsoleScreen
import com.eratw.port.render.view.GraphicsLayer
import java.io.File
import java.nio.file.NoSuchFileException

/**
 * 真机启动器：`<gameRoot>/erbc.v1/{program.erbc,program.meta}` → [GameSession] → [ConsoleFeed]。
 *
 * ## 线程
 *
 * [start] 只做前置检查并拉起 `eratw-vm` 线程；装载（段表映射 + 按段流式解码）、侧车解析、
 * `GameSession` 装配与 BEGIN 状态机全部在该线程。UI 线程只读 [failure] 与
 * `view.invalidate()`，不参与脚本执行。`QueueInputSink` 是单生产（UI）单消费（VM），
 * 提交只走 [submit]。
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
 * 由 `MainActivity` 画一行错误。装载期四类失败按 [BootFailures.classify] 分派到既有文案：
 * 堆不足（OOM / 预检不足）→ [BootFailures.HEAP_SHORTAGE_TEXT]；文件缺失 → [missingArtifacts]；
 * 段损坏 / 映射失败（`FileSystemException`）→「启动失败：装载器原话」（见 [describe]）。
 * 堆不足两条分支都在固定句后追加本次实测读数（上限 / 需求 / 已用 / 峰值，见 [heapShortage]），
 * 真机不接 adb 也能判「机型档位不够」还是「装载前堆已被挤满」。
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
 *   已知大小的按字节数对账，`emuera.config` 只拷一次、`sav/` 不在清单里；
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
) {

    /** 装载或运行期的首错；null = 尚未失败。 */
    @Volatile
    var failure: String? = null
        private set

    /**
     * 最近一次失败是否「堆不足」族（真 OOM 或堆预检不足）。
     *
     * 失败屏的「兼容模式重试」按钮读它（[canRetryCompat]）：只有这一族失败换兼容档才有
     * 意义 —— 格式坏/文件缺失重装多少遍都一样。「上一局没让出堆」（[staleSessionHeap]）
     * **不算**：那是进程内残留问题，兼容档救不了，按钮不亮免得误导。
     */
    @Volatile
    var lastFailureWasHeapShortage: Boolean = false
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

    /** 最近一次装配的图形通路（DevTools 的 img/bg 命令走它试取图；未装载时 null）。 */
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

    /** DevTools `turnlog`：最近若干页的耗时（新→旧按时间顺序存，返回原顺序）。 */
    fun debugTurnLog(): List<String> = synchronized(turnLogLock) { turnLog.toList() }

    /** 最近一次装载的读数（DevTools `loadtrace`；失败路径也保留）。 */
    @Volatile
    private var lastTrace: LoadTrace? = null

    /**
     * 装载覆盖层的收尾回调，只触发一次，已切到 UI 线程。
     *
     * 触发点 = **程序/会话装载完成、脚本即将开始执行**（`loadAndRun` 里 `trace.mark("ready")`
     * 之后、`drive()` 之前）：`start()` 只负责拉起 VM 线程就返回，装载的二十几秒全在后台，
     * 收早了玩家看到的是黑屏（release 已不打控制台状态屏）；收晚了会把脚本自己画的
     * 「少女祈祷中」进度条盖掉。
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
     * 键盘/指针后端（红 E）：原版 `GETKEY` / `GETKEYTRIGGERED` 轮询 Win32 `GetKeyState`
     * （spec: FunctionMethodCreator.cs:7805-7839），Android 只有事件，故由本实例把
     * [dispatchKey] 喂进来的事件折算成原版读数。
     *
     * 「窗口激活」取 Activity 未暂停：原版 `EmueraConsole.IsActive` 是 `window.Created &&
     * ActiveForm != null`（EmueraConsole.cs:258-268），未激活时 GETKEY 族一律返回 0
     * （FunctionMethodCreator.cs:7816-7819）—— 后台不攒按键。
     */
    val keyInput = KeyEventInputState(activeProvider = { !activityPaused })

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
    private var pendingSaveMigrationAnswer: ((Boolean) -> Unit)? = null

    /** 屏上那条迁移询问框（取消作答时要一起收起，不能留着点了没反应）。 */
    @Volatile
    private var pendingSaveMigrationDialog: AlertDialog? = null

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
    fun start(): Boolean {
        // close() 之后不再拉起线程。调用方与 close() 可能不在同一条时间线上
        // （解包收尾的 runnable 在 UI 线程、onDestroy 的 close 在它前后都可能先到），
        // 而 close() 是幂等的、不会再关第二次 —— 放行一次就等于留下一条没人收的 VM 线程
        // 与一份整局 `Program`（600 MiB 档）。会话装配完到挂上 `session` 之间还有第二道闸，
        // 见 [loadAndRun]。
        if (closed) return false
        if (thread != null) return true
        val screen = view.consoleScreen() ?: run {
            failure = "控制台尚未布局，无法确定列宽"
            return false
        }
        // 首启解包在这一步兜底（MainActivity 已经问过 artifactsAvailable，但那可能发生在
        // 别的入口；幂等且进程内只跑一次，这里再问一次不会重复拷）。
        if (!ensureAssetsInstalled()) return false
        if (!artifactsOnDisk()) {
            failure = missingArtifacts()
            return false
        }
        val gameRoot = storage.gameRoot()
        // 脚本屏幕像素（GFX-06）：在 UI 线程一次性取走实测度量，VM 线程不再回头摸 View。
        // 时点与 consoleScreen 相同（布局已发生，否则上面那道闸已经拦下）。
        // 字号**不在这里取**（2026-10-29）：真机 page-fit 字号会随版面行数变（且这份快照是
        // 冻结的），而语料的指令按钮网格拿 GETCONFIG("フォントサイズ") 当尺，量宽必须同尺；
        // 会话自己按生效配置报字号（core 侧 GameSession.scriptFontPixelSize）。
        val metrics = ScriptPixelMetrics(
            clientWidthPx = view.clientWidthPx().toLong(),
            clientHeightPx = view.clientHeightPx().toLong(),
        )
        // FN-04：带样式的分段量宽（字体族 / 粗斜体 / charSpace）。与像素尺度同批在 UI 线程
        // 构造，Paint 由实例锁保护，VM 线程跨线程调用安全。
        val textMeasure = AndroidTextMeasure.fromContext(view.context)
        thread = Thread({ loadAndRun(gameRoot, screen, metrics, textMeasure) }, VM_THREAD_NAME).also { it.start() }
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
        val ok = try {
            var lastLogAt = 0L
            var lastLogged = -1
            val report = GameAssetInstaller(storage.assetTree(), storage.gameRoot())
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
            true
        } catch (t: Throwable) {
            lastInstallProgress = null
            Log.e(TAG, "首启解包失败", t)
            failure = "${FAILURE_PREFIX}首启解包失败：${t.message ?: t.javaClass.simpleName}"
            false
        }
        assetsReady = ok
        return ok
    }

    /** 提交一条「回车文本」；VM 没在等时按原版丢弃（见 [submitInternal] 的执行期闸）。 */
    fun submit(text: String) = submitInternal(text, mesSkip = false)

    /**
     * 提交一条**跳过**（`PressEnterKey(keySkip = true, ...)`，`EmueraConsole.cs:1371-1373`）：
     * 双击/右键/Esc 的语义。跳过态由 sem 层落到 `ExecContext.mesSkip`，在下一个取值输入前结束。
     * VM 没在等时文本同样丢弃，但跳过意图经 [QueueInputSink.noteSkipIntent] 保留
     * （原版 `MesSkip = keySkip` 在 Running 早退**之前**，:1373）。
     */
    fun submitSkip(text: String) = submitInternal(text, mesSkip = true)

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
    private fun submitInternal(text: String, mesSkip: Boolean) {
        // 提交动作 = 玩家敲了一次回车（原版文本框提交走 `PressEnterKey`，MainWindow.cs:938-966）：
        // 补一次 VK_RETURN 沿变，剧本里 `GETKEYTRIGGERED(13)` 形态的打断条件才有机会为真
        // （红 E，见 [fireVirtualKeyEdge]）。
        fireVirtualKeyEdge(AndroidKeyCodes.VK_RETURN)
        synchronized(pendingLock) {
            val sink = session?.inputSink
            if (sink != null) {
                deliver(sink, text, mesSkip)
            } else if (!closed) {
                pendingBeforeSession.addLast(PendingInput(text, mesSkip))
            }
        }
    }

    /**
     * 会话在位时的投递策略（[submitInternal] 与 [attachSession] 补交共用）：
     * 有等待方 ⇒ 原样入队并记翻页计时起点；没有 ⇒ 文本丢弃、只剩跳过意图
     * （[QueueInputSink] 类文档第 1 条的宿主接线，2026-10-25）。
     */
    private fun deliver(sink: QueueInputSink, text: String, mesSkip: Boolean) {
        if (!sink.isAwaiting) {
            if (mesSkip) sink.noteSkipIntent()
            return
        }
        // 翻页计时起点：只在没在计时时置位（队列里的后续提交不覆盖本轮起点）。
        turnStartNs.compareAndSet(0L, System.nanoTime())
        sink.submit(text, mesSkip)
    }

    /**
     * 挂上装配好的会话，并把装载期的抢跑提交按原顺序补交（同一把 [pendingLock]，
     * 保证补交顺序与 UI 的提交顺序一致）。由装载线程在 `drive()` 之前调用。
     *
     * 装配期间 Activity 已销毁（[close] 先到）时不挂会话、也不补交：close() 当时看到的
     * [session] 还是 null，这里把新会话就地清掉，避免装载线程退出后留下半个会话。
     */
    private fun attachSession(fresh: GameSession) {
        val closedBeforeAttach = synchronized(pendingLock) {
            if (closed) {
                pendingBeforeSession.clear()
                true
            } else {
                session = fresh
                installMouse(fresh)
                while (pendingBeforeSession.isNotEmpty()) {
                    val queued = pendingBeforeSession.removeFirst()
                    // 补交时 VM 尚未 drive（必无等待方）⇒ 走 [deliver] 的执行期闸：
                    // 装载期的抢跑文本按原版 Initializing 语义丢弃，跳过意图保留。
                    deliver(fresh.inputSink, queued.text, queued.mesSkip)
                }
                false
            }
        }
        if (closedBeforeAttach) runCatching { fresh.close() }
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
     * `MOUSEX` 直接是客户区 x，`MOUSEY` 由 `MouseState.mouseY()` 换算成「底为 0、向上为负」
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

    /** DevTools lines 命令：console 当前整屏文本快照（最多取 n 行，按显示序）。 */
    fun debugConsoleLines(n: Int): List<String> {
        val snap = session?.console?.snapshot() ?: return emptyList()
        return snap.takeLast(n).map { it.toString() }
    }

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
     * 把迁移询问投到 UI 线程并弹系统对话框。
     *
     * 用框架 `android.app.AlertDialog`（app 无 AppCompat 依赖，主题是系统 Material）；
     * 关框/返回键与「否」同义（原版 `Dialog.cs:21-28`：只有 `DialogResult.Yes` 才是 true）。
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
            val dialog = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("是") { _, _ -> answer(true) }
                .setNegativeButton("否") { _, _ -> answer(false) }
                .setOnCancelListener { answer(false) }
                .create()
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
     * 装载中途被销毁时还有第二条线程要收口：`program.meta` 的并行解析（[inflightMeta]，
     * CMP-18）。interrupt + join([META_JOIN_MS]) 必须在 `shutdownForExit` 之前做完 ——
     * 否则旧线程继续读 49 MB 侧车，与新一代装载抢堆。
     */
    fun close() {
        if (closed) return
        closed = true
        // 先放行装载线程上的迁移询问（若有），否则 [thread] 的 interrupt 只能打断闩锁等待，
        // 装配路径走到会话构造之后仍可能把「脚本错误」误上屏。
        cancelPendingSaveMigrationPrompt()
        // 先摘掉音频出口再清场。下面的 [thread] 打断只能叫醒**等待**（`closed` 只在 [drive]
        // 的循环条件与输入队列上检查，AWAIT 长睡被 interrupt 叫醒也只是回到检查点），
        // VM 线程完全可能正停在一条语句中间；后到的 `PLAYBGM` /
        // `PLAYSOUND` 会经 [TableSoundSink] 重新 play，而 `SoundChannelTable.closeAll()`
        // 只 stop 不置 null（core/audio/SoundChannelTable.kt:213-223「不置 null」），
        // 声道可复用 ⇒ 退出之后新建的 `MediaPlayer` 再没有任何人 release
        // （platform/audio/MediaPlayerChannelDecoder.kt:125-131 的 `release` 只由
        // `SoundChannel.stop()` 下发）。原版 `Program.cs:97-104` 是**进程退出前**的最后一次
        // 清场，之后不可能再有脚本语句，所以那里不需要这道闸；本移植的 Activity 销毁与
        // 脚本线程是两条命，必须先把出口关死。
        sound.shut()
        thread?.interrupt()
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
        // **先摘渲染桥**再放会话：`attachConsoleFeed` 把 `GraphicsLayer`（里面那个
        // `backgroundAdmissible` lambda 强持 `session`）与 `SpriteBitmapSource`（持 `aux` →
        // `SpritePool`，外加最多 48 MiB 位图缓存）交给了**活得比会话久**的 `CanvasConsoleView`。
        // 视图不销毁，这两条边就在：VM 线程退了、`host`/`retiring` 都置空了，整局 Program
        // 仍然回收不掉 —— 2026-10-28 真机「导入存档 → 保存并重启」第二次装载起点
        // `used=293MiB`（干净进程实测 15 MiB）就是这条链，512 MiB 机型当场顶穿。
        // `detachGraphics()` 会 `invalidate()`，只能在 UI 线程直接跑；QUIT 路径是从 VM 线程
        // 走到这里的，所以按线程分派（post 出去的清场晚几毫秒无妨：下一次装载的
        // `attachGraphics` 本来就会把这两个字段覆写掉）。
        val clear = Runnable { view.detachGraphics() }
        if (Looper.myLooper() == Looper.getMainLooper()) clear.run() else view.post(clear)
        focus.release()
        session?.store?.aux?.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT)?.closeAll()
        val released = session?.close()
        if (released == false) {
            // CMP-19 的前提不成立（语句段局部串表 > 128 MiB）：通道保持打开是**有意的**，
            // 宁可多留 1 fd 也不把惰性解码弄坏。真机上的 fd/smaps 读数见 TODO(verify)。
            Log.w(TAG, "产物通道未释放：语句段局部串表超过 pin 上限（128 MiB），惰性解码优先")
        }
        session = null
    }

    private fun loadAndRun(
        gameRoot: File,
        screen: ConsoleScreen,
        metrics: ScriptPixelMetrics,
        textMeasure: AndroidTextMeasure,
    ) {
        val trace = LoadTrace(TAG, logEnabled = isDebuggable())
        lastScreen = screen
        lastTrace = trace
        // 并行装载（2026-10-23 性能）：`program.erbc` 解码（~17s，在 assembleSession 里）与
        // `program.meta` 解析（~8s）读的是**两个不同文件**、两套互不依赖的解析器 —— 串行做
        // 等于把这 8 秒白等。句柄在下面起线程，当参数交进 assembleSession，在那边 join。
        trace.mark("start")
        try {
            val dir = File(gameRoot, ARTIFACT_DIR)
            // UI 线程的 artifactsAvailable 与这里隔了一次线程切换：文件可能已被删/换成目录。
            // 这里再确认一次，缺失统一走 NoSuchFileException → MISSING_ARTIFACT 文案。
            val programFile = File(dir, PROGRAM_FILE).also(::requireArtifact)
            val metaFile = File(dir, META_FILE).also(::requireArtifact)
            // 装载前堆预检（M6-GAMEROOT）：只读 .erbc 头 + 侧车大小，512 MB 堆机型在这里就出文案，
            // 不进入解码把堆打碎。预检不足与真 OOM 共用同一条屏幕文案；M8-APPMEM 在句后追加实测读数。
            // 先收一次垃圾再取读数：预检的「上限」是硬阈值，但「已用」只有收完垃圾才代表真实余量。
            trace.gcHint("preflight")
            val estimate = ProgramLoader.estimateHeap(programFile.toPath(), metaFile.toPath())
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
            // release 包保留硬闸，避免在用户机上白崩。兼容装载档同样放行：它就是从
            // 堆不足失败屏进来的，且串行 + 逐段回收后的实际峰值远低于 1.1 倍静态估算
            // ——硬闸对 compat 档若不放行，「兼容模式重试」会被同一道闸原样再拦一次，
            // 按钮成死键。真 OOM 由下方 catch 收口（失败屏 + 错误报告），不会白崩。
            if (!estimate.sufficient && !forceLoad && !isDebuggable() && !compatLoad) {
                metaGate.reject()
                Log.e(TAG, "堆预检不足：$estimate")
                lastFailureWasHeapShortage = true
                failure = heapShortage(estimate, trace)
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
            // 这里自己催一次 GC：`gcHint` 的阈值是「上限的 GC_TRIGGER_PERCENT%」（512 MiB
            // 机型 ≈281 MiB），150 MiB 的**垃圾**它不收 —— 不先清一遍就把垃圾算成占用，
            // 会把正常开局误杀。装载本身几十秒，多一次 GC 换判据诚实，值。
            if (!forceLoad) {
                Runtime.getRuntime().gc()
                val baseline = trace.usedMiB()
                if (baseline > STALE_SESSION_BASELINE_MIB) {
                    metaGate.reject()
                    Log.e(TAG, "load.baseline.high used=${baseline}MiB（上一局未让出堆，拒绝开局）")
                    failure = staleSessionHeap(baseline)
                    trace.logPeak()
                    return
                }
            }
            val metaTask = if (compatLoad) null else metaGate.begin()
            if (!compatLoad && metaTask == null) {
                // 闸拒绝路径已在上面 return；走到这里只可能是调用点次序被改坏（防御，不留活线程）。
                Log.e(TAG, "meta 闸状态异常：拒绝后仍试图起线程，按堆不足收口")
                lastFailureWasHeapShortage = true
                failure = heapShortage(estimate, trace)
                trace.logPeak()
                return
            }
            inflightMeta = metaTask
            val fresh = try {
                assembleSession(gameRoot, programFile, metaFile, screen, metrics, textMeasure, trace, metaTask)
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
            // 第一处 waitInput 就能取到，见 [attachSession]）。
            attachSession(fresh)
            // 逐页翻页读数（2026-10-23 真机取证）：口径 = 提交 → 下一次进入等待点。
            // 与 drive() 里按 handler 返回计的口径互补 —— 一个 handler 内连等几页时后者永远等不到。
            fresh.inputSink.onTurnReady = { ms ->
                Log.i(TAG, "turn.next ms=$ms")
                synchronized(turnLogLock) {
                    if (turnLog.size >= TURN_LOG_LIMIT) turnLog.removeFirst()
                    turnLog.addLast("$ms ms @${System.currentTimeMillis()}")
                }
            }
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
            // 队列本身就是 GC 根：这条 lambda 在闩锁上攥着 `fresh`/`gfx`，要等 UI 线程有空
            // 才跑。若期间宿主已 `close()`（重启/销毁都走这条路），照原样 attach 等于把
            // **已经死掉的会话**重新挂回活得更久的视图上 —— 整局 Program 就此回收不掉，
            // 所以下面先看清是否已被关掉（`closed` 是 @Volatile，UI 线程读到的一定是最新值）。
            view.post { if (!closed) attachConsoleFeed(fresh, gfx, screen) }
            trace.mark("ready")
            trace.logPeak()
            com.eratw.port.android.DevToolsHost.startOnce(this, view)
            // 装载覆盖层的收尾点：程序/会话装载已完、脚本紧接着开始执行（先画「少女祈祷中」
            // 进度条）。覆盖层一直盖到这里，玩家看到的是「加载页 → 进度条 → 标题」，
            // 中间既不露控制台状态屏，也不留黑屏。
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
            // 失败归因先于文案：堆不足族要点亮失败屏的「兼容模式重试」（describe 内部
            // 还会再 classify 一次，两边判据同源不会分岐）。
            lastFailureWasHeapShortage =
                BootFailures.classify(t).kind == BootFailureKind.HEAP_TOO_SMALL
            failure = describe(t, trace)
            // 方案①：装载/启动失败也落报告（装载轨迹前 24 段进「补充现场」）。
            writeErrorReport("启动失败", t, trace.summaryLines().take(24))
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
            // 失败后立刻清场：同一进程内再次进入（Activity 重建 / Robo 反复启动）不能带着
            // 上一轮的部分装载对象。云真机实测第二次 `load.start used=223MiB`，随后必然 OOM。
            // 用 shutdownForExit 而非裸 session.close()：失败时可能已播上 BGM/音效，音频也要停。
            runCatching { shutdownForExit() }
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
    ): GameSession? {
        // 把宿主的埋点交进解码器（M10-MEM 判据③）：段边界与索引趟每 1/8 条带各回调一次，
        // 否则真机 OOM 落在 load.program 段**内部**时，失败屏的峰值行只能显示上一段的读数。
        // 折叠审计开关（2026-10-22 死循环复盘方案 #2）：`files/fold-audit.on` 存在即开——
        // 真机没有环境变量通道，debug 包走 run-as 开关文件（与 pool-audit.txt 同目录）：
        //   adb shell run-as com.eratw.port.android touch files/fold-audit.on
        // 必须在装载**前**设置：折叠发生在装载期，事后开无读数；读数在
        // ProgramDiagnostics.auditStatementPoolAt（core/diag）里随对账一起落盘（RED = 判等不完整折叠，死循环同型病）。
        StmtInternAudit.enabled = File(view.context.filesDir, "fold-audit.on").exists()
        val program = ProgramLoader.loadMapped(
            programFile.toPath(),
            compat = compatLoad,
        // T2-64 分片惰性扁平池：**设备侧默认关**。2026-10-29 真机实测（vivo V2199A / 512 MiB）：
        // 进游戏后 Dalvik Heap 从 141.9–149.5 MiB（关）涨到 256.2–260.7 MiB（开）⇒ +114 MiB，
        // 而片数与片大小都已证明无关（32/64 片、1024/4096 条/片读数一致）—— 归因未清前不拿玩家设备冒险。
        // 桌面/测试默认开（拿 when(op) 表驱动收益）；归因与优化见 CONTRACT-T2-64 §10.9。
            flatStatements = false,
        ) { stage ->
            trace.mark(stage)
            // 解码期几个「临时胖子」段之后催一次 GC：表达式结点是先全量建出来
            // （`readNodesIndexed`），紧接着 `enableLazyNodes` 就把数组元素置空 ——
            // 那 130-260 MiB 当场变垃圾，可 ART 只在**自己**要分配时才收；512 MiB 机型上等它
            // 下次扩容就已经顶到天花板（真机轨迹：段2 池根表 437 MiB → 段3 局部串池 508 MiB → OOM）。
            // `gcHint` 自带阈值（已用不到上限的 GC_TRIGGER_PERCENT% 就不收），所以这只会在
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
        trace.gcHint("meta")
        // 并行档：meta 是上面并行装的那一份（meta 任务起于 program 解码之前）：program 解码
        // （~17s）远长于 meta（~8s），join 正常零等待。
        // 兼容档（metaTask = null）：在**这里**串行读 —— program 解码完、垃圾收净之后，
        // 46.8 MiB 侧车的短命 String 不再与解码峰值叠加（用装载时间换堆余量的主项之一）。
        val metadata = metaTask?.await() ?: BootMetadata.read(metaFile.toPath())
        trace.mark("meta")
        if (closed) return null
        // 会话装配会建 CSV 总装、变量池与运行期表，是本链最后一次大分配；上一段的解析垃圾先收掉。
        trace.gcHint("session")
        val fresh = GameSession(
            gameRoot = gameRoot.toPath(),
            program = program,
            metadata = metadata,
            consoleWidth = screen.clientWidth,
            // 死循环保护改「时间闸为主、步数闸兜底」（2026-10-29 玩家反馈「读档有时报错」）：
            // 原版 `Process.cs:493-512` 每 10000 行看墙钟、默认 5000 ms 弹框且玩家可选继续，
            // **从不因指令数中止**。移植层原先只用 2000 万步硬闸，读长档恢复演出（合法
            // O(CHARANUM) 扫描）会被判成死循环并弹「启动失败」。两个取值依据见
            // `GameSession.PRODUCTION_MAX_STEPS_PER_RUN` / `DEFAULT_MAX_RUN_MILLIS` 的 KDoc。
            maxStepsPerRun = GameSession.PRODUCTION_MAX_STEPS_PER_RUN,
            maxRunMillis = GameSession.DEFAULT_MAX_RUN_MILLIS,
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
            // 字号这一路**不给**（2026-10-29）：会话按生效配置自报（GameSession.scriptFontPixelSize），
            // 因为语料的指令按钮网格拿 GETCONFIG("フォントサイズ") 当尺排格、又拿 HTML_STRINGLEN
            // 量宽 —— 两把尺必须同一条，喂 page-fit 实测字号会让每个按钮少占一半宽度、
            // 一行攒十几个就整排错位。实测字号还随版面行数变，而这份快照是冻结的。
            clientWidthPx = metrics.clientWidthPx,
            clientHeightPx = metrics.clientHeightPx,
            // FN-04：分段量宽接 Android 真字体（字号由 core 侧按配置尺给出）。
            textMeasureRun = { req ->
                textMeasure.measurePixels(req.text, req.fontName, req.fontSize, req.styleMask, req.charSpace)
            },
            // M11-A-WIRE-2：`USE SAV FOLDER` 由 NO→YES 且有旧档时先问玩家（原版 YesNo）。
            // 缺省 AUTO_ACCEPT 会静默搬档，玩家在设置页改完开关后根本不知道文件动了。
            saveMigrationPrompt = saveMigrationPrompt,
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
            disabledFunctions = if (UiPrefs.disableBadApple(view.context)) {
                EngineFunctionPolicy.DEFAULT_DISABLED
            } else {
                emptySet()
            },
        )
        trace.mark("session")
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
        // CSV 资源表（`AppContents.LoadContents`，AppContents.cs:107-178）必须在脚本开跑前装好：
        // `resources` 下 csv 声明的图片只在这张表里，XRAY 图层的 `RM_RESOURCECHECK_LOAD` 与
        // `GDRAWSPRITE 图层` 全靠它；不装 ⇒ 图层逐层被跳过 ⇒ 合成出一张空图（XRAY 隐形）。
        val spritePool = session.store.aux.slot<SpritePool>(SpriteBitmapSource.SLOT_SPRITE) { SpritePool() }
        val contents = ResourceContents.load(spritePool, ResourceCsvHost(gameRoot, decoder))
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
        view.attachGraphics(gfx.layer, gfx.frames)
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
        view.attachFeed(
            ConsoleFeed(
                fresh.console,
                screen,
                gfx.layer,
                metrics,
                gfx.frames,
                bandAvailWidthPx = maxImageWidth,
                bandMaxHeightPx = view.bandMaxHeightPx(),
                // 脚本 px → 设备 px 的基准字号（立绘空档修复；口径见 ConsoleFeed 的 KDoc）。
                scriptFontPixelSize = fresh.scriptFontPixelSize,
            ),
        )
    }

    private fun drive(fresh: GameSession) {
        fresh.begin(BeginType.TITLE)
        pumpUntilTerminal(fresh)
    }

    /**
     * 泵系统状态机直到「close / QUIT / BLOCKED-FAILED」三者之一。
     *
     * 返回 true = 停在 BLOCKED/FAILED（可恢复的失败终局）。此时**不**关会话：原版报错后
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
            val step = fresh.resumeSystem()
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
                    failure = "系统状态机停在 ${step.from.name}：" +
                        "${step.blocker?.message ?: "未知阻塞"}（${step.blocker?.evidence ?: "无证据"}）"
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
            focus.release()
            fresh.store.aux.peek<SoundChannelTable>(AUDIO_CHANNEL_SLOT)?.closeAll()
        } else if (!closed) {
            shutdownForExit()
        }
        return terminal
    }

    /** 失败屏的「兼容模式重试」是否点亮（判据见 [lastFailureWasHeapShortage]）。 */
    fun canRetryCompat(): Boolean = lastFailureWasHeapShortage

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
        val worker = Thread(
            {
                try {
                    val first = active.gotoTitle()
                    Log.i(TAG, "gotoTitle: outcome=${first.outcome} from=${first.from.name}")
                    // 失败屏摘过渲染桥：重挂一次，否则脚本照跑而屏上永远停在失败快照。
                    view.post { attachConsoleFeed(active, gfx, screen) }
                    pumpUntilTerminal(active)
                } catch (t: Throwable) {
                    failure = "回到标题失败：${t.message ?: t.javaClass.simpleName}${scriptLocation(t)}"
                    Log.e(TAG, "回到标题失败", t)
                    writeErrorReport("回到标题失败", t)
                }
            },
            VM_THREAD_NAME,
        )
        thread = worker
        worker.start()
        return true
    }

    /**
     * 失败现场落一份错误报告（方案① 报错收集），由失败屏的「复制报告 / 分享报告」消费。
     *
     * 三条纪律：① 绝不让报告生成把失败路径再炸一遍（[ErrorReportCollector.collect]
     * 全程 runCatching，失败返回 null）；② 返回 null 时 [lastReportFile] 保持上一份
     * （按钮仍可分享旧报告，聊胜于无）；③ 报告是脱敏口径（无变量取值、无存档数据），
     * 谁要往里加内容先回去读 core 侧的隐私口径。
     */
    private fun writeErrorReport(kind: String, error: Throwable?, extraLines: List<String> = emptyList()) {
        lastReportFile = ErrorReportCollector.collect(view.context, this, kind, error, extraLines)
        // 方案③：端点已配置时后台补发（daemon 线程，VM 线程不等网络）；
        // 未配置时这是第一行就返回的 no-op，日常开发包零网络行为。
        ErrorReportUploader.uploadPendingAsync(view.context)
    }

    private fun describe(t: Throwable, trace: LoadTrace): String {
        val failure = BootFailures.classify(t)
        // 堆不足是唯一固定句子的分支：不把 `Java heap space` 之类的原话落屏。
        if (failure.kind == BootFailureKind.HEAP_TOO_SMALL) {
            return "$FAILURE_PREFIX${BootFailures.HEAP_SHORTAGE_TEXT}（实测：${trace.screenSummary()}）"
        }
        if (failure.kind == BootFailureKind.MISSING_ARTIFACT) return missingArtifacts()
        val msg = failure.detail?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        return "$FAILURE_PREFIX$msg${scriptLocation(t)}"
    }

    /**
     * 脚本错误在屏上补一句现场（`函数名 行N`）——`StepLimitExceeded` 这类保护闸只报
     * 「疑似死循环」，真机上没有 logcat（Origin OS 把本应用的日志整条丢掉，2026-10-21 实测）
     * 时这一句就是唯一的定位信息。[CodeEE] 一路都带 funcName/line，之前被 [describe] 丢掉了。
     */
    private fun scriptLocation(t: Throwable): String {
        var node: Throwable? = t
        var depth = 0
        while (node != null && depth < 16) {
            if (node is CodeEE) {
                val parts = ArrayList<String>(2)
                node.funcName?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (node.line >= 0) parts += "行${node.line}"
                return if (parts.isEmpty()) "" else "（${parts.joinToString(" ")}）"
            }
            node = node.cause
            depth++
        }
        return ""
    }

    /**
     * 预检不足的屏幕文案：固定句 [BootFailures.HEAP_SHORTAGE_TEXT] 之后追加本次实测读数。
     *
     * 三个数各有分工：`上限` 是这台机的档位（`Runtime.maxMemory`），`需` 是装载层按产物
     * 标定的估算（全量语料 ≈ 634 MiB），`已用` 是收完垃圾后的装载前占用 ——
     * 「需 ≤ 上限但已用挤掉了余量」时，屏上能直接看出不是机型档位问题而是常驻已被吃掉。
     */
    private fun heapShortage(estimate: ProgramLoader.HeapEstimate, trace: LoadTrace): String =
        "$FAILURE_PREFIX${BootFailures.HEAP_SHORTAGE_TEXT}" +
            "（实测 上限 ${estimate.maxMemoryMiB} MiB，需 ${estimate.requiredMiB} MiB，" +
            "装载前已用 ${trace.usedMiB()} MiB）"

    /**
     * 装载起点堆被占的文案（阈值判据见 [STALE_SESSION_BASELINE_MIB]）。
     *
     * 与「堆预检不足」分开写两条：那条是机型档位不够（换机才有救），这条是**同一个进程里
     * 上一局没让出堆**，玩家能做的只有一件事 —— 结束进程重开，所以文案只给这一条动作。
     */
    private fun staleSessionHeap(usedMiB: Long): String {
        val maxMiB = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        return "${FAILURE_PREFIX}上一局的内存还没释放（已用 $usedMiB MiB / 上限 $maxMiB MiB）。" +
            "请结束本应用进程后重开 —— 存档在盘上，不会丢。"
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
        val dirs = storage.candidateArtifactDirs(ARTIFACT_DIR).joinToString(" 或 ") { it.absolutePath }
        return "缺少 $dirs 下的 $PROGRAM_FILE / $META_FILE"
    }

    /**
     * 装载前确认产物是普通文件：缺失/被换成目录都按 `NoSuchFileException` 处理，
     * 让 [BootFailures.classify] 落到 [BootFailureKind.MISSING_ARTIFACT]。
     */
    private fun requireArtifact(file: File) {
        if (!file.isFile) throw NoSuchFileException(file.path)
    }

    /** 装载期先存下的一条提交：文本 + 是否跳过（`PressEnterKey(keySkip = …)` 的第一参）。 */
    private class PendingInput(val text: String, val mesSkip: Boolean)

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

        private const val TAG = "GameHost"

        /** 解包进度日志的节流口径：整轮最多打 [LOG_PROGRESS_STEPS] 条，两条间隔不小于 [LOG_PROGRESS_MS]。 */
        private const val LOG_PROGRESS_STEPS = 16
        private const val LOG_PROGRESS_MS = 500L

        /** 装载/运行期失败的既有标题（`MainActivity` 的失败屏同源）。 */
        private const val FAILURE_PREFIX = "启动失败："

        /**
         * 装载起点「堆已经被占」的判据（MiB）：GC 之后仍高于这个值就拒绝开局。
         * 依据：干净进程实测 15 MiB（模拟器 2026-10-28，`dumpsys meminfo` Java Heap Alloc），
         * 出过事的真机是 276 MiB（上一局没让出堆，解码到段3 顶穿 512 MiB 上限）。
         * 120 MiB 高于任何正常开局水位，又留足了「先说清楚再死」的余量。
         */
        private const val STALE_SESSION_BASELINE_MIB = 120L

        /** DevTools `turnlog` 环形缓冲上限：够看最近 64 页即可。 */
        private const val TURN_LOG_LIMIT = 64
    }
}

/**
 * 脚本屏幕像素快照（GFX-06）：[CanvasConsoleView] 布局完成后的一次性取值，
 * UI 线程在 [GameHost.start] 里取走、经 [GameSession] 注入 [com.eratw.port.core.proc.ConsoleBuiltinHost]，
 * 供 `CLIENTWIDTH` / `CLIENTHEIGHT` 使用（消费点见 GameSession KDoc）。
 *
 * 单位是**像素**（与原版 MainPicBox.Width 同量纲），与 `ConsoleScreen.clientWidth` 的半角格列数是两个量。
 *
 * **不含字号**（2026-10-29 撤掉）：`FontSize` 由会话按生效配置自报（core 侧
 * `GameSession.scriptFontPixelSize`）。这里一度喂过 page-fit 实测字号，代价是脚本里
 * 「量宽用的尺」与「排格子用的尺」分成两条（语料排格读 GETCONFIG 的 18，量宽却按实测 42 量），
 * 指令按钮因此每个只占一半应有宽度、一行十几个就整排错位 —— 判据见 GameSession 侧的 KDoc。
 * 何况实测字号还随版面行数变，而这份快照是冻结的。
 */
private data class ScriptPixelMetrics(
    val clientWidthPx: Long,
    val clientHeightPx: Long,
)

/** 从 View 的包装 Context 找回宿主 Activity（对话框必须用 Activity 上下文）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
