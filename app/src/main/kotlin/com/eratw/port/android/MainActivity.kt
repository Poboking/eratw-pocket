package com.eratw.port.android

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.host.AndroidKeyCodes
import com.eratw.port.platform.config.SettingsStore
import com.eratw.port.platform.input.BlankTapPolicy
import com.eratw.port.platform.input.ScrollLineAccumulator
import com.eratw.port.platform.input.InputBarState
import com.eratw.port.platform.input.InputHint
import com.eratw.port.platform.input.MacroFile
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameAssetTree
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.render.RenderLine
import com.eratw.port.render.RunKind
import com.eratw.port.render.canvas.CanvasConsoleView
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.view.LeanOptionFilter
import com.eratw.port.render.view.ScreenRows
import java.io.File

/**
 * 真机入口：`<gameRoot>/erbc.v1` 存在时走 [GameHost]（`.erbc` + 侧车 → GameSession →
 * ConsoleFeed），否则保留 M4-SKEL 的演示屏并提示产物路径。
 *
 * ## 首启状态屏（M10-PKG400 随包装机即玩）
 *
 * 正式 APK 把 `erbc.v1` + `CSV` / `resources` / `sound` / `emuera.config` 全带在 assets 里
 * （全 STORED，随包 679.9 MiB），第一次启动由 [GameHost] 内部的 [GameAssetInstaller] 解到
 * 游戏根，量级是「几十秒到几分钟」，所以这里：
 * - 清点、判定与解包都在后台线程（`b076990` 的 ANR 口径不变），UI 只收快照；
 * - 期间每 [TICK_MS] 刷一行「APK 随包 / 已落盘」读数，让人看得出它在动
 *   （进度**只读**文件系统与 assets 清单，不参与解包判定，也不碰解包语义）；
 * - 二次启动命中已解包目录时文案说「已就位」，不谎报「正在解包」；
 * - 失败原因走 [GameHost.failure] → [renderFailureIfNeeded]，adb 那句降级成「开发调试通道」。
 *
 * 兼容口径：
 * - minSdk 21：全部 API 都有 21 级分支；`java.nio.file` / `java.time` 由 app 的
 *   core library desugaring（desugar_jdk_libs_nio）补齐。
 * - targetSdk 35：Android 15 默认 edge-to-edge，系统栏颜色不再生效；
 *   这里用 [View.setOnApplyWindowInsetsListener] 把系统栏 insets 变成根布局 padding。
 *
 * 输入：
 * - 控制台按钮：单击 → [CanvasConsoleView.tap]，可点性按当前等待类型（数值/字符串/任意）；
 * - 点空白（未命中按钮）：正等回车/任意键时补一条空回车（HOST-06，判定见
 *   [BlankTapPolicy]；原版 `MainWindow.cs:806`、`:818-820`）。加载/失败覆盖层、
 *   无宿主、等待数值/字符串输入时不补；
 * - 滚动：上下滑 → [CanvasConsoleView.scrollRows]；
 * - 文本框：**常驻可编辑**（原版文本框恒在，`MainWindow.cs:112`、`:727-728`），不再只在
 *   等待或宏预填时出现；等待 `INPUT` 系时按 INT/STR 切输入法，非等待期按「提交」等于
 *   先入队（抢跑，`QueueInputSink` KDoc 第 1 条），发送后清空（`MainWindow.cs:938-966`）。
 *   回车提交只在用户显式动作里发生，`EditText` 的文本变化不触发提交。
 *
 * ## 生命周期
 * - 旋转/尺寸变化走 manifest 的 `configChanges` 自行处理，不重建 Activity：VM 线程、
 *   `GameSession` 与 `ConsoleFeed` 都挂在当前 `consoleView` 上（`GameHost` 持有它），
 *   重建会先 `onDestroy → host.close()`，等于把正在跑的 VM 杀掉；文本框草稿也会随
 *   View 树丢失。代价是列宽不随旋转重排（`CanvasConsoleView.rebuildScreen` 在挂着 feed
 *   时直接返回，见该类 `:168-171`），与「控制台行仍在」相比是可接受的取舍。
 * - `onPause` 只停 UI 重绘循环（[pump]，`pumping` 翻 false），**不停 VM**：设置页盖在上面时
 *   脚本照常推进；同时把音频焦点让出（[GameHost.onPause]，唯一的焦点入口）。
 *   `onResume` 重读宏配置（不重置控制台）、重新申请焦点，并把重绘循环恢复成**唯一**一条
 *   （`pumping` 翻 true 后 removeCallbacks + post：Choreographer 已排的帧回调摘不掉，
 *   只能靠 `pumping` 让它进来即退，否则 onPause 后循环停不掉、onResume 还会叠出第二条）。
 * - `onDestroy` 对 [GameHost.close] 只调一次（内部另有 `closed` 守卫）；[pump] 先摘回调再关，
 *   避免销毁后回到 UI 线程。
 * - `onDestroy` 还要收口**进程级**的调试通道（CMP-17）：[DevToolsHost.stop] interrupt + join
 *   掉轮询线程并清掉它持有的 `host`/`view`。它是进程级单例，不随 Activity 重建换代的话，
 *   旧代的 View 树会被静态引用钉住，且 `recreate()` 之后 `startOnce` 再也起不来。
 * - 解包未完成时销毁：`eratw-assets` 线程的解包照常跑完（下次启动命中已解包目录），
 *   但它在 `runOnUiThread` 回调入口先查 [destroyed] / `isFinishing`；命中就只关掉
 *   `candidate` 直接返回，绝不挂到 [host] 上、也绝不 `start()` —— 否则 VM 会在没有
 *   宿主 close 的情况下装载整份产物，重进时还可能双份装载 OOM、后台出声。
 * - 装载只由 [started] 放行一次；失败提示由 [failureShown] 去重。
 *
 * 键盘宏：
 * - `macro.txt` 存在且 `USE KEYBOARD MACROS` 为 YES 时，底部出现 F1–F12 快捷条；
 * - 点宏只**预填**输入框，不自动提交（`MainWindow.cs:507-512` 全段没有 `PressEnterKey`）；
 *   提交仍由「提交」按钮或输入法回车完成；
 * - 「组N」按钮循环切组，等价 Ctrl+数字换组（`MainWindow.cs:514-528`），组名 Toast 原样取
 *   `KeyMacroTable.groupName`（`MainWindow.cs:1601-1611`）。
 *
 * 双击跳过：第二次抬起 → [GameHost.submitSkip]（框内文本，`keySkip = true`），对应原版
 * 右键/Esc 的 `PressEnterKey(keySkip = true, ...)`（`MainWindow.cs:814-816`、`:1415-1422`；
 * `EmueraConsole.cs:1371-1373`）。第一击仍即时生效——与 `TouchGestureController` 的即时模型一致。
 */
class MainActivity : ComponentActivity() {

    private lateinit var storage: GameStorage
    private lateinit var consoleView: CanvasConsoleView

    /**
     * 壳层状态（2026-10-23 起壳层是 Jetpack Compose）：输入条 / 右侧选项栏 / 宏条 /
     * 提示语都挂在这份 `mutableStateOf` 上，游戏画面仍是 `CanvasConsoleView`（AndroidView
     * 嵌入）。只在 UI 线程写。
     */
    private val shell = ShellState()

    private var macroTable: KeyMacroTable? = null
    private var macroGroup = 0

    /** 上一帧交给选项栏的候选指纹：没变就不重建（Compose 侧也就不用重组）。 */
    private var optionFingerprint: String = ""

    /** 上次扫描选项的时钟（[OPTION_SCAN_MS] 节流用）。 */
    private var optionScanAt = 0L

    /**
     * 上一次由「等待态」推出的提示文案（[refreshShell] 的写闸）。
     *
     * pump 每帧都跑 [refreshShell]，若它无条件把 [ShellState.hint] 刷回状态文案，
     * [submitInput] 失败时写的「游戏未运行，无法提交」十几毫秒就被冲掉，玩家等于
     * 什么都没看到。这里只在等待态**本身**翻转时才覆写，别处写的提示活到下一次翻转。
     */
    private var lastDerivedHint: String? = null

    /**
     * 本次 Activity 实例建 [consoleView] 时用的字号列数。
     *
     * 字号是**构造期**参数（`CanvasConsoleView.fontColumnsOverride` 派生 metrics/paints），
     * 设置页改档位后仅重开会话不会换字号 —— `onActivityResult` 拿它和最新
     * [UiPrefs.fontColumns] 比，变了就走 `recreate()`（重建 View 并重新装载）。
     */
    private var appliedFontColumns: Int = 0

    /**
     * 当前宿主；**只在 UI 线程**赋值/读取（`eratw-assets` 线程也经 `runOnUiThread` 才碰它）。
     * 因此不需要 `@Volatile`：跨线程交到 UI 线程的那一步由 Handler 的入队/出队建立可见性。
     */
    private var host: GameHost? = null

    /**
     * 正在收尾的旧会话（只在 UI 线程读写，口径同 [host]）。
     *
     * 为什么要单独留一个字段：[restartGame] 等旧 VM 线程退出期间 [host] 已经是 null，
     * 这时失败屏的「重试」若直接再开一局，等于「上一局还压在堆上又起一份」——
     * 那正是 2026-10-27 真机 OOM 的形状。挂在这里，重启路径才认得出它、能继续等它。
     */
    private var retiring: GameHost? = null
    private var started = false
    private var failureShown = false

    /**
     * 兼容装载档开关（2026-10-28 低端机 OOM）：失败屏点过「兼容模式重试」后置 true，
     * 本会话后续 [startGameOrDemo] 构造的宿主全部带 `compatLoad = true`。
     * 只在 UI 线程读写；不持久化 —— 换机/重装后回到默认档，新包发布后也可重新评估。
     */
    private var compatLoad = false

    /** [onDestroy] 已走完：pump 不再自转，[GameHost.close] 不再重复。只在 UI 线程读写。 */
    private var destroyed = false

    /**
     * Activity 是否在前台（[onResume] / [onPause] 维护）。
     *
     * 首启解包要几十秒到几分钟，[host] 往往在 `onPause` **之后**才被挂上来；那时
     * `onPause` 早就走过了（它只转发现有 host，`host` 还是 null），新 host 的
     * `activityPaused` 停在构造值 false，装载完成时就会在后台申请音频焦点、直接在后台
     * 出声。挂载时按本字段补一次 [GameHost.onPause]，真正的 `onResume` 再恢复。
     */
    @Volatile
    private var resumed = false

    /** 首启解包进度条是否在跑：后台线程解包期间由 ticker 每 [TICK_MS] 刷一屏读数。 */
    @Volatile
    private var ticking = false

    /**
     * 重绘循环的唯一开关：装载完成 / onResume 置位，onPause 复位。
     * 只靠 `removeCallbacks` 停不住循环 —— Choreographer 已排的帧回调它摘不掉，
     * 必须让排队中的那条进来即退（见 [pump]），onResume 才不会叠出第二条。
     */
    @Volatile
    private var pumping = false

    private val pump = object : Runnable {
        override fun run() {
            // 被 onPause 摘不掉的「已排帧回调」撞上时从这里退出，不再续排。
            if (destroyed || !pumping) return
            // 2026-10-22 真机抖动收口：只 pump，有新快照才 invalidate——
            // 此前每帧无条件重绘（120Hz 屏 ~107fps、legacy janky 87%）＝高频抖动根因。
            consoleView.pumpAndInvalidateIfNeeded()
            refreshShell()
            renderFailureIfNeeded()
            if (pumping && host?.isRunning() == true) consoleView.postOnAnimation(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 崩溃兑底（方案①）：任何线程未捕获异常先落一份报告再交给系统默认处理。
        // 装在 onCreate 最早处：之后任何阶段的崩（解包/装载/脚本线程外逃）都走这里。
        // 报告采集自会 runCatching 兑底，不会拦住真崩。
        installCrashReportHandler()
        // 方案③：冷启动补发上一轮失败/断网的报告（端点未配置时是 no-op）。
        ErrorReportUploader.uploadPendingAsync(applicationContext)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        storage = GameStorage(this)
        // 字号档位由设置页决定（默认 110 列 ≈ 14dp，手机可读；旧版固定 199 列 ≈ 7.7dp 太小）。
        appliedFontColumns = UiPrefs.fontColumns(this)
        consoleView = CanvasConsoleView(
            this,
            fontColumnsOverride = appliedFontColumns,
            screenFitEnabled = UiPrefs.screenFit(this),
        ).apply { setBackgroundColor(Color.BLACK) }
        attachGestures()
        reloadMacros()
        // 右侧选项栏的可见状态跨会话记忆（螺旋图标切换，见 ShellUi.SpiralToggle）。
        shell.railVisible = UiPrefs.optionRailVisible(this)
        // 选项按钮大小档（设置页）：只影响壳层几何，镜像进来供右栏取用。
        shell.railButtonSize = UiPrefs.railButtonSize(this)

        // 壳层 = Jetpack Compose（2026-10-23）；游戏画面仍是 CanvasConsoleView，
        // 经 AndroidView 嵌入，绘制/命中路径零改动。分区布局在 EratwShell 里。
        setContent {
            EratwTheme {
                EratwShell(
                    consoleView = consoleView,
                    state = shell,
                    onSettings = { openSettings() },
                    onSubmit = { text -> submitInput(text) },
                    onOption = { value -> submitInput(value) },
                    onMacroGroup = { switchMacroGroup() },
                    onRetry = { retryLoad() },
                    onRetryCompat = { retryLoadCompat() },
                    onBackToTitle = { backToTitle() },
                    onCopyReport = { copyFailureReport() },
                    onShareReport = { shareFailureReport() },
                    onRailToggle = {
                        shell.railVisible = !shell.railVisible
                        UiPrefs.setOptionRailVisible(this, shell.railVisible)
                    },
                    onInputChanged = { text -> mirrorTextBox(text) },
                )
            }
        }
        refreshShell()

        // 沉浸式必须在 setContent（decorView 建成）之后调用：`window.insetsController`
        // 在 decorView 为 null 时是 NPE（2026-10-23 真机闪退实证：onCreate 里提前调用，
        // PhoneWindow.getInsetsController → DecorView.getWindowInsetsController 空引用）。
        applyImmersiveMode()
        keepScreenOn()

        consoleView.viewTreeObserver.addOnGlobalLayoutListener {
            if (started || consoleView.consoleScreen() == null) return@addOnGlobalLayoutListener
            started = true
            startGameOrDemo(storage)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS &&
            resultCode == RESULT_OK &&
            data?.getBooleanExtra(SettingsActivity.EXTRA_RESTART_GAME, false) == true
        ) {
            // 字号是构造期参数：档位变了必须重建 Activity（重建 CanvasConsoleView 与
            // metrics/paints），否则设置页显示与实际渲染不一致。一屏一帧只影响下一次装配，
            // 走 restartGame() 即可。
            if (UiPrefs.fontColumns(this) != appliedFontColumns) {
                // 字号是构造期参数：档位变了必须重建 Activity（重建 CanvasConsoleView 与
                // metrics/paints），否则设置页显示与实际渲染不一致。但 `recreate()` 之前同样
                // 要等旧会话让出堆：新实例自己会起装载，而旧实例的 `onDestroy` 只 close 不 join
                // （`closed` 只在泵边界被看），两份 Program 同住 512 MiB 堆就是那次 OOM。
                restartGame { recreate() }
            } else {
                restartGame()
            }
        }
    }

    /**
     * 焦点变化时补一次沉浸式：系统栏被边缘上滑临时唤出后，失去/恢复焦点会走这里，
     * 保证回到全屏（Android 11+ 用 IMMERSIVE 行为，旧系统用 IMMERSIVE_STICKY）。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    /** 打开设置页；回来时要不要重启游戏由结果 extra 决定（[SettingsActivity.EXTRA_RESTART_GAME]）。 */
    private fun openSettings() {
        startActivityForResult(Intent(this, SettingsActivity::class.java), REQUEST_SETTINGS)
    }

    /**
     * 「保存并重启游戏」的落点 ＝ 原版 `Reboot()`（spec: `MainWindow.cs:1066-1069` → `:1022-1026`
     * 重启进程）的壳内等价物：拆掉当前 [GameHost]（关输入队列 → VM 线程在下一个输入等待点
     * 以 `InputSinkClosedException` 退出）、等它收尾，然后按盘上**新配置**重开一局 ——
     * [GameSession] 的条目表只在构造时从 `emuera.config` 重放一次（`Program.cs:95` +
     * `ConfigData.cs:494` 同一口径），所以「重启」是设置生效的唯一途径（设置页类 KDoc）。
     *
     * 等待在后台线程做轮询（UI 线程不睡，否则纯计算段的 VM 最坏拖出一个 ANR）：
     * 旧会话**没真的退出就不起新一代**（口径与代价见 [awaitOldSessionThenStart]）。
     * UI 状态先复位再等：失败屏去重、宏组、重绘循环（[pump] 自按 [GameHost.isRunning] 续停）。
     */
    private fun restartGame(afterStop: () -> Unit = { startGameOrDemo(storage) }) {
        // 上一局可能还在收尾（`host` 已空而线程未退）：认它，别当没看见。
        val old = host ?: retiring?.takeIf { it.isRunning() }
        host = null
        old?.close()
        // CMP-17：重启一局换的是新 `GameHost`，而调试通道是进程级单代槽 —— 不收口的话
        // 新一代装载的 `startOnce` 会被「当前代还活着」挡掉，adb 命令继续打到已关闭的旧宿主上。
        // （`old == null` 的首启路径也调一次：幂等，没起过就是 no-op。）
        DevToolsHost.stop()
        failureShown = false
        macroGroup = 0
        ticking = false
        shell.failure = null
        shell.failureCanBackToTitle = false
        shell.failureCanRetryCompat = false
        syncLoading("正在按新配置重启游戏…", null, null)
        consoleView.detachFeed()
        reloadMacros()
        refreshShell()
        consoleView.submitSnapshot(
            listOf(ScreenRows.textLine("正在按新配置重启游戏…")),
        )
        if (old == null) {
            retiring = null
            afterStop()
            return
        }
        retiring = old
        Thread({ awaitOldSessionThenStart(old, afterStop) }, "eratw-restart").start()
    }

    /**
     * 等旧会话**真的**退出，再起新一代。
     *
     * 旧写法是「等 [RESTART_JOIN_TIMEOUT_MS] 后照常重开」，那里的注释说纯计算段「最多再跑
     * 一个步数闸」—— 可步数闸缺省是 2,000 万步（`GameSession` 的 `DEFAULT_MAX_STEPS_PER_RUN`），
     * 真机上一条读档链几十秒都跑不完；而 `GameHost.closed` 只在泵循环边界被看，
     * `interrupt` 叫不醒一条正在跑语句的 VM 线程。超时硬起新一代 ＝ 两份 Program 同住一个堆：
     * 2026-10-27 首个 release 包在 vivo V2199A（largeHeap 上限 512 MiB）上
     * 「导入存档 → 保存并重启」必崩，装载轨迹起点 `used=294MiB` 就是上一局还压着的读数。
     *
     * 所以软窗口过后不是照起，而是把「在等什么」上屏继续等；等满硬上限仍不退出就**停手报失败** ——
     * 宁可让用户手动重开进程，也不拿 OOM 去赌：赌输是被堆打死，连错误报告都发不出去。
     */
    private fun awaitOldSessionThenStart(old: GameHost, afterStop: () -> Unit) {
        if (!waitUntilStopped(old, RESTART_JOIN_TIMEOUT_MS)) {
            consoleView.post {
                if (!destroyed) consoleView.submitSnapshot(
                    listOf(ScreenRows.textLine("上一局还在收尾（脚本正跑一条长链，等它到输入点）…")),
                )
            }
            waitUntilStopped(old, RESTART_HARD_WAIT_MS - RESTART_JOIN_TIMEOUT_MS)
        }
        consoleView.post {
            if (destroyed) return@post
            if (old.isRunning()) {
                renderRestartStuck()
            } else {
                retiring = null
                afterStop()
            }
        }
    }

    /** 轮询等 [old] 的 VM 线程退出，最多 [budgetMs] 毫秒；返回 true = 真的退出了。 */
    private fun waitUntilStopped(old: GameHost, budgetMs: Long): Boolean {
        val deadline = android.os.SystemClock.uptimeMillis() + budgetMs
        while (old.isRunning() && android.os.SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(RESTART_JOIN_POLL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        return !old.isRunning()
    }

    /** 等不到旧会话退出：说清卡在哪。「重试」按钮仍走 [restartGame]，那里会认出在收尾的旧会话。 */
    private fun renderRestartStuck() {
        shell.loading = false
        shell.chips = emptyList()
        shell.failure = "上一局在 ${RESTART_HARD_WAIT_MS / 1000} 秒内没跑到输入点，为避免两份程序" +
            "同时占堆已停止重启。请结束本应用进程后重开（存档已在盘上，不会丢）。"
        shell.failureReportReady = false
        shell.failureCanBackToTitle = false
        refreshShell()
        consoleView.submitSnapshot(
            listOf(
                ScreenRows.line(ScreenRows.run("重启停止", fore = 0xFF6060, styleMask = 1)),
                ScreenRows.textLine(shell.failure ?: ""),
            ),
        )
    }

    /**
     * 键盘事件先喂引擎、再按情况交给视图层。
     *
     * 喂 [GameHost.dispatchKey] 是为了剧本轮询 `GETKEY` / `GETKEYTRIGGERED`（红 E 的打断键位）：
     * 原版是轮询 Win32 `GetKeyState`（spec: WinInput.cs:8 +
     * FunctionMethodCreator.cs:7814-7838），查询动作本身不改消息流，所以这里**先观察**、
     * 不因为观察而丢事件。
     *
     * 唯一例外：输入框没聚焦时吞掉回车的 **KeyUp**（其余键照旧 `super`）。真机 2026-10-26：
     * 输入条提交成功那一下 `clearFocus()` + 输入框移出组合，Compose 把焦点回落到底栏的
     * 可点控件上，同一次回车的 KeyUp 于是被 clickable 当成一次 Enter 点击
     * （右上键盘开关与底边抓手的 onClick 都是 `inputVisible = true`）⇒ 输入条刚收起又弹开。
     * KeyDown 那半截在聚焦时已由输入框的 `onPreviewKeyEvent` 消费，这里只拦残留的 KeyUp；
     * 聚焦时不拦，回车必须走 InputBar 的提交口。按键读数上一行已喂给引擎，吞掉动作不丢读数。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        host?.dispatchKey(event.keyCode, down = event.action != KeyEvent.ACTION_UP)
        val enter = event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (enter && event.action == KeyEvent.ACTION_UP && !shell.inputFocused) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 设置页可能改过 USE KEYBOARD MACROS；macro.txt 也可能被外部替换。
        if (::storage.isInitialized) reloadMacros()
        // 设置页也可能改过「选项按钮大小」：纯壳层几何，不需要重开会话，
        // 回前台读一次镜像到 shell 状态，右栏下一次重组就是新尺寸。
        shell.railButtonSize = UiPrefs.railButtonSize(this)
        // 音频焦点在这里唯一一次重新申请（后台期间已被 onPause 放弃）。
        host?.onResume()
        // onPause 摘掉了重绘循环；回前台恢复唯一一条。这里**无条件**投一次：后台期间装载失败
        // 或脚本结束时 isRunning() 已是 false，若按它跳过，pump 就再也不会跑，
        // renderFailureIfNeeded 不上屏，画面会永远停在最后一张「正在装载…」快照。
        // pump 自己按 isRunning() 决定是否续转：未运行时只跑一帧就停，不会空转。
        // （B3）先翻 pumping 再 removeCallbacks + post：Choreographer 已排的帧回调摘不掉，
        // 靠 pumping 让旧的那条进来即退，保证任何时刻最多只有一条循环在排。
        pumping = true
        consoleView.removeCallbacks(pump)
        consoleView.post(pump)
    }

    override fun onPause() {
        // 音频只在这里让出焦点；Activity 不直接碰声道（避免与 AudioManager 回调双触发）。
        resumed = false
        host?.onPause()
        // 先翻 false 再摘回调：Choreographer 已排的那条帧摘不掉，靠 pumping 让它进来即退。
        pumping = false
        consoleView.removeCallbacks(pump)
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        ticking = false
        pumping = false
        consoleView.removeCallbacks(pump)
        host?.close()
        // 热更通道装的是闭包（捕获本 Activity），销毁时必须摘掉，否则进程级单例攥着
        // 一个已销毁的壳 —— 设置页下一次保存会读到过期的 `host`（已 close 的会话）。
        HotConfigChannel.install(null)
        // CMP-17：调试通道是进程级单代槽，销毁时**必须**真复位（interrupt + join + 清引用）。
        // 顺序放在 host.close() 之后：轮询线程可能还在执行一条命令（它读 host/view 的
        // volatile 快照口），让会话先收口再打断，命令读到的就是「已关闭」而不是半关状态。
        DevToolsHost.stop()
        super.onDestroy()
    }

    /**
     * 失败覆盖层的「重试」：关掉当前宿主后重开一局（等价于设置页的「保存并重启游戏」，
     * 但不清配置）。真机此前失败后只能杀进程重进，这条把重试变成一次点击。
     */
    private fun retryLoad() {
        shell.failure = null
        restartGame()
    }

    /**
     * 失败屏的「兼容模式重试」（2026-10-28 低端机 OOM）：换兼容装载档重开一局。
     *
     * 一旦用户选了兼容档，本次 Activity 会话的后续装载（包括设置页重启）都用兼容档：
     * 堆不够是机型档位问题，用户不会想每次失败都再选一遍；成功后也不自动退回 ——
     * 换新机再装新包才是退出兼容档的途径。
     */
    private fun retryLoadCompat() {
        compatLoad = true
        shell.failure = null
        restartGame()
    }

    /**
     * 失败屏的「回到标题」= 原版菜单项「タイトルへ戻る」（spec: `MainWindow.cs:1090-1104` →
     * `EmueraConsole.GotoTitle()` `EmueraConsole.cs:2569-2581`）：在同一个会话上复位变量池与
     * 系统状态机、重进 `Begin(TITLE)`，不重新装载。
     *
     * 宿主拒绝（装载级硬失败、脚本还在跑，见 `GameHost.canBackToTitle`）时什么都不做，
     * 失败屏原样留着 —— 与原版「按条件禁用菜单项」同一形态。
     */
    private fun backToTitle() {
        val active = host ?: return
        if (!active.backToTitle()) return
        failureShown = false
        shell.failure = null
        shell.failureCanBackToTitle = false
        // 失败后 pump 不再自续（`isRunning()` 当时为假）；新 VM 线程已起，重挂唯一一条重绘循环。
        pumping = true
        consoleView.removeCallbacks(pump)
        consoleView.post(pump)
    }

    /**
     * 把装载进度同步给 Compose 覆盖层（[ShellState.loading]/[ShellState.progress] 等）。
     * console 侧的明细屏保留（便于排障），覆盖层负责「进度条 + 百分比 + 明细」。
     */
    private fun syncLoading(
        title: String,
        progress: GameAssetInstaller.Progress?,
        landed: Inventory?,
    ) {
        shell.loading = true
        shell.loadingTitle = title
        shell.progress = progress?.let { p ->
            if (p.total > 0) (p.copied.toFloat() / p.total.toFloat()).coerceIn(0f, 1f) else null
        }
        shell.progressText = when {
            progress != null -> "已解包 ${progress.copied}/${progress.total} 条（${progress.label}）"
            landed != null -> "已落盘：${landed.summary()}"
            else -> "正在盘点随包数据…"
        }
    }

    private fun startGameOrDemo(storage: GameStorage) {
        val candidate = GameHost(
            storage,
            consoleView,
            // 失败屏选过「兼容模式重试」之后，本会话的后续装载都用兼容档（见 retryLoadCompat）。
            compatLoad = compatLoad,
        )
        val gameRoot = storage.gameRoot()
        // 随包形态（M10-PKG400）：APK 里带 erbc.v1 + CSV/resources/sound/plugins + emuera.config，
        // 首启要解到游戏根 —— 全 STORED 口径下是 679.9 MiB、四千个条目，量级是「几十秒到几分钟」，
        // 所以这块屏要自己报进度：先只读数一遍 assets 清单（不写盘、不碰解包语义），
        // 再每 800 ms 数一遍已落盘的目标文件，把「正在解包 / 已完成 / 失败原因」摊在屏上。
        // 「清点 + 可用性判定 + 解包」全部在后台线程跑，UI 线程只收快照（`b076990` 的 ANR 口径）；
        // 清点出数之前先挂占位行（随包读数「(盘点中…)」），首屏不至于黑着干等。
        consoleView.submitSnapshot(
            startupLines(candidate.artifactDir(), gameRoot, null, null, "正在检查 APK 随包数据…"),
        )
        syncLoading("正在检查游戏数据…", null, null)
        ticking = true
        Thread({
            // 跨 Activity 单飞：上一代解包线程还在跑就先等它（见 [claimUnpackSlot] 的 KDoc）。
            claimUnpackSlot()
            try {
                unpackAndStart(candidate, gameRoot, storage, consoleView)
            } finally {
                releaseUnpackSlot()
            }
        }, "eratw-assets").start()
    }

    /**
     * 解包 + 判定 + 启动装载的整段后台流程（原来内联在 [startGameOrDemo] 的线程体里）。
     *
     * 抽出来只为让「认领/释放解包槽」用 `try/finally` 包住整段，不再多层嵌套 lambda。
     */
    private fun unpackAndStart(
        candidate: GameHost,
        gameRoot: File,
        storage: GameStorage,
        consoleView: CanvasConsoleView,
    ) {
        run {
            // B4：两份清点都在这条后台线程、解包开始**之前**做 —— bundledInventory 要递归整棵
            // assets（数千次 AssetManager JNI），landedInventory 要全盘 walk，放 UI 线程冷启
            // 会卡首帧乃至 ANR。landedBefore 是「本次有没有真解包」的唯一判据，必须是解包**前**
            // 的读数：它和 expected 一起在 artifactsAvailable()（判定 + 解包的入口）之前取好，
            // 同一条线程天然保序，再往下传给最终文案，不破坏 ticker「解包中不数盘」的口径。
            val expected = bundledInventory(storage.assetTree())
            val landedBefore = landedInventory(gameRoot)
            runOnUiThread {
                if (!ticking || destroyed || isFinishing) return@runOnUiThread
                consoleView.submitSnapshot(
                    startupLines(candidate.artifactDir(), gameRoot, expected, null, "正在检查 APK 随包数据…"),
                )
                syncLoading("正在检查游戏数据…", null, null)
            }
            val ticker = Thread({
                while (ticking) {
                    // 解包**正在跑**时不要去数盘：那会给同一个存储再加一路随机小 I/O，还会和
                    // 解包器抢同一批目录项。解包器自己就有 `copied/total`，读它零成本。
                    val progress = candidate.installProgress()
                    val landed = if (progress == null) landedInventory(gameRoot) else null
                    runOnUiThread {
                        if (!ticking || destroyed || isFinishing) return@runOnUiThread
                        consoleView.submitSnapshot(
                            startupLines(
                                candidate.artifactDir(),
                                gameRoot,
                                expected,
                                landed,
                                "正在解包 APK 随包资源（立绘与音效一起）…",
                                progress,
                            ),
                        )
                        syncLoading("正在解包游戏资源…", progress, landed)
                    }
                    try {
                        Thread.sleep(TICK_MS)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }, "eratw-unpack-ticker")
            ticker.start()
            val available = candidate.artifactsAvailable()
            ticking = false
            val landed = landedInventory(gameRoot)
            runOnUiThread {
                // 解包期间 Activity 被销毁（Back / finish）时 onDestroy 早已跑完，[host] 还是
                // null，关不到这个 candidate。这里若照旧 host = candidate 再 start()，VM 会在
                // 无人持有的情况下装载整份产物且永不 close（重进还可能双份装载 OOM、后台出声）。
                // 因此回调入口先判销毁：停 ticker、就地 close（GameHost.close 幂等）、清引用后
                // 直接返回；candidate 只可能在这一个分支或下面的 host 赋值里被接管，不会重复 close。
                // destroyed/isFinishing/host 都只在 UI 线程读写，检查与后续 start 之间不会插入
                // onDestroy，所以入口这一道判断足以覆盖整个回调。
                if (destroyed || isFinishing) {
                    ticking = false
                    ticker.interrupt()
                    runCatching { candidate.close() }
                    host = null
                    return@runOnUiThread
                }
                ticker.interrupt()
                if (!available) {
                    shell.loading = false
                    consoleView.submitSnapshot(demoLines(storage, candidate.artifactDir()))
                    return@runOnUiThread
                }
                // 从回调入队到真正执行之间也可能刚好销毁：赋值前再查一次，压掉这个窗口。
                if (destroyed || isFinishing) {
                    ticking = false
                    ticker.interrupt()
                    runCatching { candidate.close() }
                    return@runOnUiThread
                }
                host = candidate
                // 设置页「热更键」的投递口（M5-CONFIG 的运行时重载口径）：设置页读盘那份
                // `SettingsModel.store` 与会话那份条目表是两份实例，只能靠这条通道推过去。
                // 这里装的是**活引用**（`host?.`），所以重启/关会话后 `host` 变 null 就自动
                // 退化成「只落盘、下次进游戏生效」，不需要在每个收尾分支重复摘除；
                // 唯一的例外是 onDestroy（对象要随 Activity 一起放掉，见那里）。
                HotConfigChannel.install { host?.liveConfigStore() }
                // 解包/判定这几十秒里 Activity 可能已经切到后台：这次 onPause 走过时
                // `host` 还是 null（`onPause` 只转发现有 host），新 host 的 `activityPaused`
                // 于是停在构造值 false。补一次 onPause，装载完成时就不会在后台申请焦点
                // （GameHost 的 `if (!activityPaused) focus.start()`）；回到前台时
                // `onResume` 再正常恢复。
                if (!resumed) candidate.onPause()
                val finalTitle =
                    if (expected.bytes > 0L && !landed.bytesLeveled(expected)) {
                        // 解包器说「成功」但落盘读数追不上 APK 里的随包读数 —— 两种成因：
                        // 随包本身缺件（少 CSV/resources/sound/plugins 之一），或条目被 DEFLATE
                        // （compressed 条目拿不到 openFd 长度，见 AndroidAssetTree.fileSize）。
                        "落盘 ${landed.summary()} 追不上 APK 随包 ${expected.summary()}：随包缺件或条目被压缩"
                    } else if (landedBefore.entries > 0) {
                        "随包数据已就位（本次启动命中已解包目录，${landed.summary()}），正在装载 erbc.v1…"
                    } else {
                        "解包完成 ${landed.summary()}，正在装载 erbc.v1（program.erbc + program.meta）…"
                    }
                consoleView.submitSnapshot(
                    startupLines(
                        candidate.artifactDir(),
                        gameRoot,
                        expected,
                        landed,
                        finalTitle,
                    ),
                )
                // 覆盖层只报玩家面的一句话；排障读数仍在控制台状态屏（[startupLines]，仅 debug）
                // 与 debug 构建的角落行里，不给玩家看条目数/路径（2026-10-23 用户截图反馈）。
                syncLoading("正在装载游戏内容…", null, landed)
                // 覆盖层收在「程序装载完成、脚本开始执行」那一刻（[GameHost.onConsoleReady]）：
                // 那里紧接就是脚本自己画的「少女祈祷中」进度条，玩家看到的是
                // 「加载页 → 彩条 → 标题」，中间不露状态屏、也不黑屏。
                candidate.onConsoleReady = { shell.loading = false }
                if (!candidate.start()) {
                    // 失败后释放会话与焦点：同进程再次进入（Activity 重建 / Robo 反复启动）
                    // 不能带着上一轮的部分装载对象，否则下一次必然 OOM。
                    runCatching { candidate.close() }
                    renderFailureIfNeeded(force = true)
                    return@runOnUiThread
                }
                // 与 onResume 同一套口径：先翻开关再排循环，保证重绘循环永远只有一条。
                pumping = true
                consoleView.post(pump)
            }
        }
    }

    /**
     * 首启状态屏的固定四行：标题 + 游戏根 + 随包/已落盘读数 + 下一步提示。
     *
     * `expected == null` = assets 清点还在后台跑（首帧占位，随包行显示「(盘点中…)」——UI 不自己
     * 数 assets，那是 B4 挪进后台的 ANR 口径）；`landed == null` = 还没开始落盘（只显示 APK 里
     * 有什么）；失败原因不在这里，
     * 它由 [GameHost.failure] 经 [renderFailureIfNeeded] 单独上屏（那句「随包数据没落到盘」
     * 是解包侧的原话，不能被这里的话术盖掉）。
     *
     * @param progress 解包器自己的进度快照（`copied/total` + 当前资产名）。非 null 时代替
     *   「已落盘」那行 —— 解包进行中数盘既慢又抢 I/O，而解包器本来就知道精确进度。
     */
    private fun startupLines(
        artifactDir: File,
        gameRoot: File,
        expected: Inventory?,
        landed: Inventory?,
        title: String,
        progress: GameAssetInstaller.Progress? = null,
    ): List<RenderLine> {
        // 玩家面不给任何排障读数：条目数 / 游戏根 / 产物目录 / adb 提示这些字只在
        // logcat、DevTools（`loadtrace` / `stats`）与失败屏上出现，装载期屏幕上只有
        // Compose 的「正在进入这个世界」加载页（2026-10-23 负责人反馈：首启屏整屏技术字）。
        return emptyList()
    }

    /**
     * 只读数一遍 APK assets 的随包清单（`erbc.v1` + 四个语料目录 + `emuera.config`）。
     *
     * 口径与 [landedInventory] **逐项对齐**（同一批名字、同样的递归），否则屏上那两个数
     * 根本没法比 —— 走过整棵 assets 会把 `fonts/` 这类应用自带件也算进「随包」，
     * 「落盘追不上随包」就永远为真（解包器压根不拷 `package.report`，天然少一条）。
     *
     * 不解包、不写盘、不 import 解包器 —— 只用 [GameAssetTree] 的 `listDir`/`fileSize`。
     * 「文件还是目录」按解包器同一条口径判：`AssetManager.list` 对「不存在的目录」「空目录」
     * 「普通文件」都返回空表，所以**空表先当文件**（拿不到长度就按 0 计），只影响这行读数、
     * 不参与任何判定。压缩条目（`-PeratwErbcCompress` 那档、以及 aapt2 后缀表够不着的
     * 无后缀文件）拿不到 `openFd` 长度 ⇒ 字节数会**偏小**，所以它只当「缺件」的下限用：
     * 落盘比它多不算异常，比它少 1% 以上才在屏上点出来。
     */
    private fun bundledInventory(assets: GameAssetTree): Inventory {
        var entries = 0
        var bytes = 0L
        fun walk(dir: String) {
            for (name in assets.listDir(dir)) {
                val path = if (dir.isEmpty()) name else "$dir/$name"
                val children = assets.listDir(path)
                if (children.isEmpty()) {
                    entries++
                    bytes += assets.fileSize(path) ?: 0L
                } else {
                    walk(path)
                }
            }
        }
        val roots = ArrayList(GameAssetInstaller.DATA_DIRS)
        roots.add(GameHost.ARTIFACT_DIR)
        roots.add(GameAssetInstaller.CONFIG_FILE)
        roots.forEach { walk(it) }
        return Inventory(entries, bytes)
    }

    /**
     * 只读数一遍游戏根已落盘的文件（首启进度用；`sav/` 不在口径内，它不是随包数据）。
     *
     * 一轮是「递归列目录 + 每个文件一次 `length()`」，四千条目在真机是几十毫秒量级；
     * 频率由 [TICK_MS] 压住，且只在后台线程跑，不占 UI。
     */
    private fun landedInventory(gameRoot: File): Inventory {
        var entries = 0
        var bytes = 0L
        val names = ArrayList(GameAssetInstaller.DATA_DIRS)
        names.add(GameHost.ARTIFACT_DIR)
        names.add(GameAssetInstaller.CONFIG_FILE)
        for (name in names) {
            val f = File(gameRoot, name)
            if (f.isFile) {
                entries++
                bytes += f.length()
                continue
            }
            if (!f.isDirectory) continue
            f.walkTopDown().filter { it.isFile }.forEach { file ->
                entries++
                bytes += file.length()
            }
        }
        return Inventory(entries, bytes)
    }

    /** 落盘字节已达到随包字节的 99% ⇒ 判定为「已就位」（第二次启动走这条，文案不谎报「正在解包」）。 */
    private fun Inventory.bytesLeveled(expected: Inventory): Boolean =
        expected.bytes > 0L && bytes >= expected.bytes * 99 / 100

    /** 随包/已落盘读数的一对数字；`summary()` 是屏上那一行原文。 */
    private data class Inventory(val entries: Int, val bytes: Long) {
        fun summary(): String =
            if (entries == 0) "(没有随包条目)" else "$entries 个条目 / ${bytes / (1024 * 1024)} MiB"
    }

    private fun attachGestures() {
        // 拖动行数的零头累加器（判据与出处见 ScrollLineAccumulator）：只在一次按下之内累加。
        val scrollLines = ScrollLineAccumulator()
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                /**
                 * 长按按钮 → 显示它的 `title`（原版 PC 的 hover ToolTip）。
                 *
                 * 原版鼠标指着按钮时控制台取 `ConsoleButtonString.Title` 弹 ToolTip
                 * （`EmueraConsole.cs:1891-1917`，`:1909` 把 `<br>` 换成换行）。安卓没有
                 * hover，改由长按取同一份原文交给壳层卡片；长按后 GestureDetector 不会
                 * 再回调 onSingleTapUp，因此不会误触按钮。
                 */
                override fun onLongPress(e: MotionEvent) {
                    if (shell.inputVisible) return
                    val text = consoleView.tooltipAt(e.x, e.y) ?: return
                    shell.tooltip = text
                }

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    // 输入栏展开时，屏幕第一次点击只负责收起输入栏（对齐原 Compose 遮罩行为），
                    // 不再往下走「点按钮/提交」。
                    if (shell.inputVisible) {
                        shell.inputVisible = false
                        shell.inputFocused = false
                        return true
                    }
                    val active = host ?: return false
                    // 点在任何位置都算一次「按下鼠标左键」：原版 `GetKeyState(VK_LBUTTON)`
                    // 读的是指针按下位（FunctionMethodCreator.cs:7825），语料的打断条件
                    // `TITLE.ERB:158` 把左键与回车/ESC 并列（红 E）。命中按钮时随后还会
                    // 走 [GameHost.submit] 再补一次回车沿变，两者是不同的键、不互相顶掉。
                    active.fireVirtualKeyEdge(AndroidKeyCodes.VK_LBUTTON)
                    val submitted = consoleView.tap(e.x, e.y, active.acceptance())
                    if (submitted != null) {
                        // 世代闸（2026-10-26 静态复查补）：命中表是渲染侧的**可见行**按钮，
                        // 上一页的按钮没滚出屏幕就仍在表里；原版的可点判据是按钮对象的
                        // `Generation == lastButtonGeneration`（EmueraConsole.cs:2439、:2456）。
                        // 判定收在 [GameHost.isCurrentGeneration]（只查世代、借右侧栏的
                        // 取值类型过滤会误杀合法组合），不在当前世代的命中直接吞掉 ——
                        // 真机 2026-10-25 的「跨屏串值把 CFLAG 打越界」就是这类错位的账。
                        if (active.isCurrentGeneration(submitted)) submitButtonValue(submitted)
                        return true
                    }
                    // HOST-06：未命中按钮时，原版左键在非按钮区直接提交一次回车
                    // （`MainWindow.cs:806` 的 `IsWaitingEnterKey && selectedString == null`，
                    // 左键走 `:818-820` 的 `PressEnterKey`）。判定抽在 [BlankTapPolicy] 里：
                    // 只有「宿主就绪 + 无覆盖层 + 正等回车/任意键」才补，数值/字符串等待
                    // 与加载/失败覆盖层都不补（契约 CONTRACT-Q2 §2.8 的保守口径）。
                    // 补的是**空文本**而不是输入框草稿：原版会把草稿一起交出去并清框，
                    // 本移植按契约口径只交空文本、草稿留在框里（差别见交付回执）。
                    // 提交走 [GameHost.submit]（`mesSkip = false`），与按钮命中同一条 API；
                    // 该 API 内部还会补一次 VK_RETURN 沿变，鼠标路径在原版并不产生 Return
                    // 触发，这一笔是既有提交口的统一口径，不另开旁路。
                    if (BlankTapPolicy.shouldSubmitEnter(
                            hasHost = host != null,
                            overlayVisible = shell.loading || shell.failure != null,
                            canSkip = inputBarState().canSkip,
                            hitButton = false,
                        )
                    ) {
                        active.submit("")
                        consoleView.requestRedraw()
                        return true
                    }
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val active = host ?: return false
                    // 原版双击跳过只在「等待回车」态生效（MainWindow.cs:806 IsWaitingEnterKey）；
                    // INPUT/INPUTS 等文本请求时双击不该排一条 skip，否则会比玩家多提交一次。
                    if (!inputBarState().canSkip) return false
                    // 与 submitInput 同口径：提交即清框（canSkip ⇒ VM 正在等，不是抢跑）。
                    mirrorTextBox("")
                    active.submitSkip(shell.input)
                    shell.input = ""
                    consoleView.requestRedraw()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    // `distanceY` 是**相邻两个 MOVE 之间**的增量，不是一共滑了多少：慢速拖动每段都不到
                    // 半行高，逐段四舍五入会一路算成 0 ⇒ 手指推了半屏、画面一格不动
                    // （2026-10-29 真机「无法向上滑」）。零头交给 [ScrollLineAccumulator] 攒着，
                    // 攒够一行付一行。
                    val rows = scrollLines.add(distanceY, consoleView.lineHeightPx())
                    if (rows == 0) return false
                    // 手指上移（distanceY > 0）= 看更后面的行；反之看更早的行。
                    android.util.Log.i("EratwGest", "scrollRows(${-rows}) carryPx=${scrollLines.carry()}")
                    consoleView.scrollRows(-rows)
                    return true
                }
            },
        )
        consoleView.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                // 每次按下都是新的一拖：上一拖没付完的零头不能带过来。
                scrollLines.reset()
                android.util.Log.i("EratwGest", "DOWN 到达控制台 x=${event.x} y=${event.y}")
            }
            // 触摸现场 → MouseState（MOUSEB/MOUSEX/MOUSEY）：只在按下/抬起/取消时记，
            // 不跟 ACTION_MOVE（滚动时每个事件都换算一遍白烧帧）。原版 MouseMove 同理
            // 只在移动/按键时写现场（EmueraConsole.cs:2328-2331）。
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN,
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL,
                -> host?.updatePointer(event.x, event.y)
            }
            detector.onTouchEvent(event)
        }
    }

    /**
     * 沉浸式全屏：隐藏状态栏/导航栏，游戏区占满整屏（横屏 199 列下每多 40px 就是
     * 多一行正文）。系统栏仍可从边缘上滑临时唤出（IMMERSIVE_STICKY 语义），
     * 不会影响输入法与通知。
     */
    /**
     * 自动防息屏（2026-10-24 主人要求）：游戏在前台时保持屏幕常亮。
     *
     * 主人手机 10 分钟无操作自动息屏，而 eratw 有很多「看一会儿再决定」的长页面，
     * 息屏会打断游戏（也打断我的真机验收）。
     * 用 FLAG_KEEP_SCREEN_ON 而不是 PowerManager.WakeLock：前者由 WindowManager 按
     * 窗口可见性自动持有/释放，App 退到后台立刻失效，不需要权限、也不会漏解锁。
     */
    private fun keepScreenOn() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    /** 读 `macro.txt` 与宏开关；结果写进 [shell]（Compose 宏条）；设置页返回时 reassess。 */
    private fun reloadMacros() {
        val root = storage.gameRoot()
        val table = MacroFile.load(File(root, MacroFile.FILE_NAME))
        val useMacro = SettingsStore(File(root, CONFIG_FILE))
            .load()
            .model
            .effectiveConfig()
            .bool(ConfigKey.UseKeyMacro)
        macroTable = if (table != null && useMacro) table else null
        rebuildMacroButtons()
    }

    /** 把当前宏组的 12 个键刷进 [ShellState.macros]（空宏置灰由 label 表达）。 */
    private fun rebuildMacroButtons() {
        val table = macroTable
        if (table == null) {
            shell.macros = emptyList()
            shell.macroGroupLabel = ""
            return
        }
        shell.macroGroupLabel = table.groupName(macroGroup)
        shell.macros = (0 until KeyMacroTable.MAX_FKEY).map { fkey ->
            val content = table.get(fkey, macroGroup)
            val label = if (content.isEmpty()) "F${fkey + 1}" else "F${fkey + 1}:${previewOf(content)}"
            ShellMacro(label = label, content = content)
        }
    }

    /** Ctrl+数字换组的触屏等价物：循环 0..9，组名 Toast 文案沿用 `setNewMacroGroup`（`MainWindow.cs:1601-1611`）。 */
    private fun switchMacroGroup() {
        val table = macroTable ?: return
        macroGroup = (macroGroup + 1) % KeyMacroTable.MAX_GROUP
        rebuildMacroButtons()
        Toast.makeText(this, table.groupName(macroGroup), Toast.LENGTH_SHORT).show()
    }

    private fun previewOf(content: String): String {
        val oneLine = content.replace('\n', ' ').replace('\r', ' ').trim()
        return if (oneLine.length > PREVIEW_CHARS) oneLine.take(PREVIEW_CHARS) + "…" else oneLine
    }

    /**
     * 交出一枚**被点中的按钮**的值（游戏画面的命中区共用）。
     *
     * 与 [submitInput] 的差别只有一点：不动输入条里的草稿（点画面不算改草稿）。
     * 为什么要先镜像文本框：QoL 的地图/外出屏从不走 `INPUT`，它在 `AWAIT` 轮询里读
     * `GETTEXTBOX()`（`qol_MAP.ERB:1130-1136` 的回车支），而输入队列在无等待方时按原版
     * 「执行期早退」把文本丢弃（[GameHost.submitInternal]）——旧实现在这里只调
     * `active.submit(submitted)`，于是**点画面里的按钮等于没点**（2026-10-26 静态复查发现；
     * 另一条左键支 `MOUSEB()` 在真机没有接线、恒空，见交付报告的遗留项）。
     *
     * 两条分支与 [InputBarState.textBoxAfterSubmit] 同源：
     * - **有等待方**（`INPUT` / `WAIT` 系）⇒ 框清空（原版 `updateInputs` 把 `richTextBox1`
     *   清掉，`MainWindow.cs:933`）+ 值入队；
     * - **无等待方**（轮询屏 / 抢跑）⇒ 值留在框里，随 [GameHost.submit] 补的那一次回车沿变
     *   被 `GETTEXTBOX()` 读到；输入队列那一边按原版丢弃，不跨屏串值。
     */
    private fun submitButtonValue(value: String) {
        val active = host ?: return
        val state = inputBarState()
        if (!state.canSubmit) return
        state.textBoxAfterSubmit(value)?.let { mirrorTextBox(it) }
        active.submit(value)
        consoleView.requestRedraw()
    }

    /**
     * 提交一条输入（输入条回车/提交键、右侧选项按钮、点击命中区共用）。
     *
     * @return true = 已交给宿主（入队，或执行期轮询屏的「文本框 + 回车沿变」事件）；
     *   false = 宿主不在/VM 已停，本次没有去处。壳层只在 true 时收起输入条并清框 ——
     *   旧实现在 `canSubmit == false` 时静默 return，调用方仍无条件收起，玩家看到的就是
     *   「回车后框没了、值也没交」。
     */
    private fun submitInput(text: String): Boolean {
        val active = host ?: run {
            shell.hint = "游戏未运行，无法提交"
            return false
        }
        val state = inputBarState()
        if (!state.canSubmit) {
            shell.hint = "游戏未运行，无法提交"
            return false
        }
        // 提交后框里留下什么由 InputBarState.textBoxAfterSubmit 决定（原版 PressEnterKey 的
        // 两条路：等待期 updateInputs 清框，:933 → :938-967；抢跑在原版提交不了、字留在框里，
        // :922-936）。Android 的选项按钮没有键入过程，抢跑那条路必须把这一条写进去，
        // 否则点「开始游戏」时 TITLE.ERB:168 的 `GETTEXTBOX() != ""` 永远等不到。
        // AWAIT 轮询屏（地图/外出）走的正是抢跑路：文本留在核心文本框，回车沿变由
        // [GameHost.submit] 补；右侧栏的显示兜底见 [GameHost.currentOptions]。
        state.textBoxAfterSubmit(text)?.let { mirrorTextBox(it) }
        active.submit(text)
        shell.input = ""
        // 交出去了就把提示的写闸放开：下一次 pump 按最新等待态重算文案
        // （这里硬写「输入后回车提交」会在轮询屏上留下与 [refreshShell] 口径不符的一句）。
        lastDerivedHint = null
        consoleView.requestRedraw()
        return true
    }

    /**
     * 把输入框文本镜像到核心层的 `Window.TextBox` —— 原版这两者是同一个 `RichTextBox`
     * （`MainWindow.cs:210` 的 `TextBox => richTextBox1`），`GETTEXTBOX` 读的就是它的
     * `Text`（`FunctionMethodCreator.cs:8351-8354`）。安卓侧输入框是独立 Compose 状态，
     * 不镜像的话 `GETTEXTBOX` 永远是空串：标题画面的「敲字即退出」倒计时
     * （TITLE.ERB:168）、AWAIT_UI 系列的回车取值（qol_MAP.ERB:1132）都会失效。
     */
    private fun mirrorTextBox(text: String) {
        host?.textBoxHost()?.setText(text)
    }

    /** 输入条的纯逻辑状态（可见性恒 true；提交分禁用/正常/抢跑）。 */
    private fun inputBarState(): InputBarState {
        val active = host
        return InputBarState.of(
            hasHost = active != null,
            running = active?.isRunning() == true,
            pending = active?.pendingRequest(),
            awaitingAnyKey = active?.awaitingAnyKey() == true,
        )
    }

    /**
     * pump 每帧把壳层状态刷进 [ShellState]（值不变时 `mutableStateOf` 不触发重组）。
     *
     * 选项栏按 [OPTION_SCAN_MS] 节流 + 指纹去重：读的是 `GameHost.currentOptions()` ——
     * 有等待方时是输入等待入口冻结的快照（`ConsoleSink.noteInputWait`），无等待方时
     * （QoL 地图/外出屏的 `AWAIT` 轮询）退回引擎当前世代的按钮（`displayOptions`），
     * 不再扫可见行。选项只在页面切换/轮询重印时变，60fps 每帧重建指纹仍是白烧电，
     * 所以保留 250ms 节流。
     *
     * 「更精简的游戏选项」（[UiPrefs.leanOptions]，缺省关）只裁剪**显示端**：打开时
     * 用 [LeanOptionFilter] 留下标签末尾带数字标签的常用指令；关闭时 chips 原样不动。
     * 引擎快照与正文按钮命中都不受影响。
     */
    private fun refreshShell() {
        val state = inputBarState()
        // 四档提示语收在 [InputHint]（platform 层纯函数，可 JVM 单测）：
        // 2026-10-29 玩家反馈「没有退出键、也没提示」里那半个「没提示」，
        // 根因就是这里原先没有 WAIT 系（PRINTW / PRINTFORMW）这一档，
        // 玩家停在「按任意键继续」页上看到的仍是「输入后回车提交」。
        // 2026-10-25 执行期闸之后，非等待期的提交不会再落输入队列（防跨屏错位）；
        // 它只作为「文本框 + 回车沿变」交给 AWAIT_UI 轮询（地图/外出）。
        val hint = InputHint.of(state)
        // 只在等待态**本身**翻转时才覆写提示：pump 每帧都跑这里，无条件写会把
        // [submitInput] 刚写的失败提示（「游戏未运行，无法提交」）在十几毫秒内冲掉，
        // 玩家等于什么都没看到；顺带省下每帧一次的字符串拼接与重组判等。
        if (hint != lastDerivedHint) {
            lastDerivedHint = hint
            shell.hint = hint
        }
        shell.numberKeyboard = state.keyboard == InputBarState.Keyboard.SIGNED_NUMBER
        // WAIT 系页面的「继续」脚钮闸门（见 ShellUi.ContinueButton）：
        // 每帧无条件刷（mutableStateOf 值不变不触发重组），不能放在下面 250ms 的
        // 选项扫描节流之后 —— 那一段每帧早退，会让脚钮的出现/消失慢半拍。
        shell.awaitingAnyKey = state.awaitingAnyKey

        val now = android.os.SystemClock.uptimeMillis()
        if (now - optionScanAt < OPTION_SCAN_MS) return
        optionScanAt = now
        val recognized = host?.currentOptions().orEmpty()
        // 缺省关：`recognized` 原样交给选项栏，识别口径与筛选前完全一致。
        val chips = if (UiPrefs.leanOptions(this)) LeanOptionFilter.keepCommon(recognized) else recognized
        // value 也进指纹：两个 >999 的数值按钮或同标签字符串按钮靠 ordinal+label 分不开。
        // 行号/列号也进：同一组选项换行/换列重排（比如地图翻页后按钮位置变了）必须刷新，
        // 否则指纹相同会把旧顺序留在栏里 —— 这正是“排序不跟屏”的一半来源。
        val fingerprint = chips.joinToString("|") {
            "${it.lineSerial}:${it.startCol}:${it.endCol}:${it.ordinal}:${it.label}:${it.value}"
        }
        if (fingerprint != optionFingerprint) {
            optionFingerprint = fingerprint
            shell.chips = chips
        }
    }

    /**
     * 崩溃兑底（方案①）：包一层缺省未捕获异常处理器 —— 先尽力落一份「应用崩溃」报告
     * 到 `filesDir/eratw-reports/`，再原样交回旧处理器（保持系统崩溃口径不变）。
     * 报告在崩溃线程同步写（私有 filesDir，几十 KB，可接受）；[host] 可能还没建
     * （崩在装载前）或已 close，采集自会降级成纯异常报告。
     */
    private fun installCrashReportHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                ErrorReportCollector.collect(
                    applicationContext,
                    host,
                    "应用崩溃",
                    e,
                    listOf("崩溃线程：${thread.name}"),
                )
            }
            // 方案③：崩溃路上同步补发一次（3s 上限，进程死前给一次机会；未配置 = no-op）。
            runCatching { ErrorReportUploader.uploadPendingSyncForCrash(applicationContext) }
            previous?.uncaughtException(thread, e)
                ?: Runtime.getRuntime().exit(2)
        }
    }

    /** 失败屏「复制报告」：整份报告进剪贴板，玩家直接粘进群聊/邮件。 */
    private fun copyFailureReport() {
        val file = host?.lastReportFile
        if (file == null || !file.isFile) {
            Toast.makeText(this, "本次失败没有可用的报告文件", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = ErrorReportCollector.copyToClipboard(this, file)
        Toast.makeText(
            this,
            if (ok) "报错报告已复制，粘贴发给开发者即可" else "复制失败，请改用「分享报告」",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** 失败屏「分享报告」：走系统分享面板（经 ReportFileProvider 的临时只读授权）。 */
    private fun shareFailureReport() {
        val file = host?.lastReportFile
        if (file == null || !file.isFile) {
            Toast.makeText(this, "本次失败没有可用的报告文件", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = ErrorReportCollector.shareIntent(this, file)
        if (intent == null) {
            Toast.makeText(this, "分享面板启动失败，请改用「复制报告」", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent.createChooser(intent, "分享 eratw 错误报告")) }
            .onFailure {
                Toast.makeText(this, "没有可用的分享入口，请改用「复制报告」", Toast.LENGTH_SHORT).show()
            }
    }

    private fun renderFailureIfNeeded(force: Boolean = false) {
        val message = host?.failure ?: return
        if (failureShown && !force) return
        failureShown = true
        // 失败后宿主停转、pump 不再跑：这里主动把壳层清干净（否则选项栏残留最后一批 chip，
        // 点下去还会往已关闭的输入队列提交）。真机 2026-10-23 实测：失败屏右侧仍挂着 0/1/2。
        shell.chips = emptyList()
        shell.loading = false
        shell.failure = message
        // 方案①：本次失败落出了报告才点亮「复制报告 / 分享报告」。
        shell.failureReportReady = host?.lastReportFile?.isFile == true
        // 「回到标题」只在真能复活时点亮（同一口径见 GameHost.canBackToTitle）。
        shell.failureCanBackToTitle = host?.canBackToTitle() == true
        // 「兼容模式重试」只在堆不足族失败时点亮（见 GameHost.canRetryCompat）。
        shell.failureCanRetryCompat = host?.canRetryCompat() == true
        consoleView.detachFeed()
        consoleView.submitSnapshot(
            listOf(
                ScreenRows.line(ScreenRows.run("启动失败", fore = 0xFF6060, styleMask = 1)),
                ScreenRows.textLine(message),
                ScreenRows.textLine("产物根: ${host?.artifactDir()?.absolutePath}"),
                ScreenRows.textLine("随包 APK 正常不该走到这里：这一句就是原因（磁盘满 / assets 坏 / program 与 meta 不配对）。"),
                ScreenRows.textLine("开发调试通道才需要 adb：tools/apkpack/push_game_data.ps1 -Dev 会把数据直推到上面那个目录。"),
            ),
        )
    }

    private fun demoLines(storage: GameStorage, artifactDir: java.io.File): List<RenderLine> = listOf(
        ScreenRows.line(
            ScreenRows.run("eraTW Android — M5-APP-WIRING", fore = 0x7FD4FF, styleMask = 1),
        ),
        ScreenRows.textLine("本屏由 ConsoleScreen → DisplayList → DrawCmd 逐格绘制。"),
        ScreenRows.textLine("半角: ABC abc 0123 !\"#$%&'()"),
        ScreenRows.textLine("全角: 日本語の表示と中文显示测试"),
        ScreenRows.textLine("半角假名: ｱｲｳｴｵ ｶﾀｶﾅ"),
        ScreenRows.textLine("增补平面: \uD840\uDC00 （按 Grid 口径占 4 格）"),
        ScreenRows.line(ScreenRows.run("=".repeat(80), fore = 0x808080)),
        ScreenRows.textLine("数据根: ${storage.root.absolutePath}"),
        ScreenRows.textLine("外部根: ${storage.externalRoot?.absolutePath ?: "(不可用)"}"),
        ScreenRows.textLine("未找到产物: ${artifactDir.absolutePath}"),
        ScreenRows.textLine("这个 APK 没随包数据（构建时给了 -PeratwSkipAssets，或打包机上没有游戏根/erbc 产物）。"),
        ScreenRows.textLine("正式包：设 ERATW_GAMEDIR + -PeratwRequireComplete=1 重新 assembleDebug，装完直接玩。"),
        ScreenRows.textLine("调试：tools/apkpack/push_game_data.ps1 -Dev 把 erbc.v1 与 CSV/resources/sound/plugins 推到游戏根后重启。"),
        ScreenRows.line(
            ScreenRows.run("继续", kind = RunKind.BUTTON, fore = 0x000000, back = 0xC0C0C0),
            ScreenRows.run("  （按钮底色与描边由 RunKind.BUTTON 触发）", fore = 0x909090),
        ),
        ScreenRows.textLine("M5 判据：APK 构建通过；真机冷启动待 M4-SKEL-RT 设备。"),
    )

    private companion object {
        const val CONFIG_FILE = "emuera.config"
        const val PREVIEW_CHARS = 8

        /**
         * 解包线程的**跨 Activity 单飞槽**（2026-10-24 真机实证补的口子）。
         *
         * 既有口径：解包中途销毁时 `eratw-assets` 线程照常跑完（下次启动命中已落盘目录）。
         * 但下一个 Activity 实例不认得上一代那条线程，会**再起一条**并发解包同一批文件 ——
         * 真机线程表实测到 2 条 `eratw-assets` 同时跑（`gen` 命令读数）。两条线程都走
         * `GameAssetInstaller` 的「缺啥拷啥」，交叠时同一个目标文件可能被两个写者同时改写。
         * 这里让后来者在**后台线程里**等前一代收尾再开工：它跑完后资产已在盘上，
         * 本代的清点/判定路径是幂等的，等待成本≈0；UI 线程不受影响（等待不在 UI 线程）。
         */
        private val unpackSlotLock = Any()

        /** 当前在跑的解包线程；null = 没有在跑的。只在 [unpackSlotLock] 内读写。 */
        private var unpackInFlight: Thread? = null

        /**
         * 认领解包槽：若上一代还在跑，**在本线程**等它收尾（本方法只从后台解包线程调用）。
         *
         * `InterruptedException` 不向上抛：被中断说明宿主已经不要这一代了，但解包完整性
         * 仍要保住（前一代的落盘结果才是有用的），所以吞掉中断继续走既有流程。
         */
        private fun claimUnpackSlot() {
            val self = Thread.currentThread()
            val prior = synchronized(unpackSlotLock) {
                val p = unpackInFlight
                unpackInFlight = self
                p
            }
            if (prior != null && prior !== self && prior.isAlive) {
                // 等待期把线程名改成可区分的 `eratw-assets-wa`：真机线程表（`/proc/<pid>/task/*/comm`）
                // 因此能直接看出「两条 eratw-assets」里到底谁在真解包 —— 否则二者同名，
                // 采样只能得到「2 条线程」这种无法判读的读数（2026-10-24 实测踩过）。
                val original = self.name
                self.name = "eratw-assets-wa"
                try {
                    prior.join()
                } catch (_: InterruptedException) {
                    // 打断不改变语义：继续本代的清点/解包（幂等）。
                } finally {
                    self.name = original
                }
            }
        }

        /** 释放解包槽（只认自己，避免误清下一代）。 */
        private fun releaseUnpackSlot() {
            val self = Thread.currentThread()
            synchronized(unpackSlotLock) {
                if (unpackInFlight === self) unpackInFlight = null
            }
        }

        /** 选项栏扫描节流（毫秒）：选项变化的密度远低于帧率，250ms 已能跟上脚本节奏。 */
        private const val OPTION_SCAN_MS = 250L

        /** 设置页请求码：只此一个入口（[openSettings]），回来按结果 extra 分派。 */
        private const val REQUEST_SETTINGS = 4001

        /**
         * 重开一局前等旧 VM 线程退出的**软**窗口（毫秒）：输入队列已关，正常情况下一个
         * 输入等待点就退。过了这个窗口不再「照常重开」，而是把等待上屏继续等 ——
         * 见 [awaitOldSessionThenStart]（2026-10-27 真机 OOM：读档长链几十秒不看关闭标志，
         * 旧写法超时即起新一代，两份 Program 同住 512 MiB 堆直接打穿）。
         */
        private const val RESTART_JOIN_TIMEOUT_MS = 5_000L

        /** 等旧会话退出的硬上限（毫秒）：到点仍不退出就停手报失败，不再拿 OOM 去赌。 */
        private const val RESTART_HARD_WAIT_MS = 120_000L

        /** 旧 VM 线程退出轮询的间隔（毫秒）。 */
        private const val RESTART_JOIN_POLL_MS = 50L

        /**
         * 首启解包的读数间隔。真机随包是四千条目 / 679.9 MiB，解包要几十秒到几分钟，
         * 这块屏必须动；但每轮都要 `listFiles` 递归一遍（不 stat 长度），所以不追高频率 ——
         * 800 ms 在「看着像在干活」和「别把解包线程的 IO 抢掉」之间取的值。
         */
        const val TICK_MS = 800L
    }

}
