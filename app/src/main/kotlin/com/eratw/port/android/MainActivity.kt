package com.eratw.port.android

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eratw.port.android.portrait.PORTRAIT_HUD_TAIL_LINES
import com.eratw.port.core.config.ConfigKey
import com.eratw.port.core.host.AndroidKeyCodes
import com.eratw.port.core.host.InputRequest
import com.eratw.port.platform.config.SettingsStore
import com.eratw.port.platform.input.BlankTapPolicy
import com.eratw.port.platform.input.ScrollLineAccumulator
import com.eratw.port.platform.input.InputBarState
import com.eratw.port.platform.input.InputHint
import com.eratw.port.platform.input.MacroFile
import com.eratw.port.platform.input.TouchGestureController
import com.eratw.port.platform.storage.GameAssetInstaller
import com.eratw.port.platform.storage.GameAssetTree
import com.eratw.port.platform.storage.GameStorage
import com.eratw.port.render.RenderLine
import com.eratw.port.render.button.ButtonAcceptance
import com.eratw.port.render.RunKind
import com.eratw.port.render.canvas.CanvasConsoleView
import com.eratw.port.render.input.KeyMacroTable
import com.eratw.port.render.view.LeanOptionFilter
import com.eratw.port.render.view.ScreenRows
import com.eratw.port.android.ui.EratwShellDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

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
 *   开了 LIVE-REFLOW（[appliedLiveReflow]，缺省关）的那一局例外：宽度变化经 `GameHost`
 *   接的 `onViewportChange` 走「核心先重排、视口再换屏」这条新链；关态这条链一个字节都不动，
 *   本段口径逐字照旧。
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
 * **第二击不是无条件跳过**：窗口内还要过「宿主能跳过 + 第一击真的推进了 + 第一击不是按钮 +
 * 落在第一击的位置邻域内」四道闸（见 [TouchGestureController.onUp]），闸没全开就照常当单击
 * ——2026-11-02 玩家反馈「需要双击才能响应 / 双击快了还卡顿」就是无条件映射的账。
 * 触摸手势（点/滑分界、双击窗口与邻域、长按阈值）全部由 `TouchGestureController` 判定，
 * 本类只做坐标换算与动作执行，不再用 `GestureDetector`（理由见 [attachGestures]）。
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


    /**
     * 上一帧交给选项栏的候选指纹：没变就不重建（Compose 侧也就不用重组）。
     *
     * **类型是 64 位哈希，不是字符串**：指纹的唯一用途是「比较」，而拼一条字符串再比较
     * 要在每 250 ms 的热路径上造几十个短命对象（见 [refreshShell] 的注释与 2026-10-31
     * 真机 OOM 崩溃栈）。判据字段一个没少，只是不再为比较分配。
     */
    private var optionFingerprint: Long = 0L

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
     * 本次 Activity 实例建 [consoleView] 时用的**列数守护下限**（同一个 SharedPreferences
     * 值也派生「显示大小」倍率 [UiPrefs.fontScale]，所以只比这一个数就够了）。
     *
     * 守护下限与倍率都是**构造期**参数（`CanvasConsoleView.minColumnsOverride` /
     * `fontScale` 派生 metrics/paints），设置页改档位后仅重开会话不会换字号 ——
     * `onActivityResult` 拿它和最新 [UiPrefs.fontColumns] 比，变了就走 `recreate()`
     * （重建 View 并重新装载）。注意字号本身（`emuera.config` 的 `FONT SIZE`/`LINE HEIGHT`）
     * 不是构造期参数，由 [GameHost] 在每次会话开始前灌入
     * （见 `CanvasConsoleView.applySourceFontMetrics`）。
     */
    private var appliedFontColumns: Int = 0

    /**
     * 本次 Activity 实例是否走**竖屏三区壳**（竖屏设计稿 §6 方向策略，装配期定死）。
     *
     * 判据在 [onCreateCore]：只看玩家偏好（[UiPrefs.portraitMode]，缺省关 = 横屏），
     * 不读 `Configuration.ORIENTATION_*` —— 那绕开「配置方向在 `onCreate` 里与
     * `requestedOrientation` 谁先生效」的时序问题（设计稿 §6.4 的 TODO(verify)）。
     * 本实例存续期内不再翻转（`configChanges` 吞掉旋转、
     * 逻辑列宽构造期定死，竖屏设计稿 §5.2「旋转时逻辑列宽不变」）——运行中旋转后
     * 壳层分支与档位不跟随，见交付报告 TODO(verify)。
     */
    private var portraitNow: Boolean = false

    /**
     * 本次 Activity 实例建 [consoleView] 时用的**列数守护开关**（[UiPrefs.screenFit]）。
     *
     * 与 [appliedFontColumns] 同一条口径：也是构造期参数，改动只有重建 View 才生效。
     * 设置页随改随存，所以从设置页返回时必须拿它比对 —— 否则玩家把守护关掉再返回，
     * 当前会话仍按旧开关渲染，表现就是「设置无效」。
     */
    private var appliedScreenFit: Boolean = true

    /**
     * 本次 Activity 实例装配 [consoleView] 与 [GameHost] 时用的 **LIVE-REFLOW 开关读数**。
     *
     * 与 [appliedFontColumns] / [appliedScreenFit] 同一条口径：它是 [CanvasConsoleView] 的
     * **构造期**参数（`liveReflow`，决定 `rebuildScreen` 要不要把宽度变化问出去），改动只有重建
     * View 才生效，所以从设置页返回时一并进 [onActivityResult] 的 `displayChanged` 比较。
     *
     * ## 为什么必须「装配期取一次」并原样交给宿主
     *
     * 这颗开关有**两个消费者**：视口（`CanvasConsoleView.liveReflow`，问不问宽度变化）与核心
     * （`GameSession.reflowConsole`，答不答应重排）。两者**必须同源**，否则会出现
     * 「视口一直问、核心一直拒」的空转回调：视口按开态把 `onViewportChange` 打出去，
     * `resizeConsole` 却因关态恒返回 false，于是既不换屏也解释不了「为什么没反应」。
     * 这里在 [onCreateCore] 读一次 [UiPrefs.liveReflow] 定格成字段，[startGameOrDemo] 再把
     * **同一个值**传给 [GameHost]，两处不再各自去摸 SharedPreferences —— 设置页在装配之后
     * 被改掉也只会影响「下一次装配」，与本类的既有构造期参数口径一致。
     */
    private var appliedLiveReflow: Boolean = false

    /**
     * 进设置页**之前**的显示大小档 / 列数守护开关 / LIVE-REFLOW 开关快照（[openSettings] 写）。
     *
     * 用途只有一个：区分「本次设置页访问里真的改了」与「上一轮改了但选择了下次启动生效」——
     * 后者返回时 prefs 与 [appliedFontColumns] 依旧不等，若拿 applied 值判就会每次返回都弹一遍
     * 重开确认。按进页快照判，玩家没动这几项就永不打扰。
     */
    private var settingsEntryFontColumns: Int = 0
    private var settingsEntryScreenFit: Boolean = true
    private var settingsEntryPortraitMode: Boolean = false
    private var settingsEntryLiveReflow: Boolean = false

    /**
     * 当前宿主；UI 线程赋值/读取（`eratw-assets` 线程也经 `runOnUiThread` 才碰它）。
     * 崩溃处理器是例外：它在**崩溃线程**经 CrashForensics 的 crashHostProvider 只读一次，故标 `@Volatile`
     * 保证能看到 UI 线程最后一次写入（否则报告可能拿到 null，少一份装载现场）。
     */
    @Volatile
    private var host: GameHost? = null

    /**
     * 正在收尾的旧会话（只在 UI 线程读写，口径同 [host]）。
     *
     * 为什么要单独留一个字段：[restartGame] 等旧 VM 线程退出期间 [host] 已经是 null，
     * 这时失败屏的「重试」若直接再开一局，等于「上一局还压在堆上又起一份」——
     * 那正是 2026-10-27 真机 OOM 的形状。挂在这里，重启路径才认得出它、能继续等它。
     */
    private var retiring: GameHost? = null

    /**
     * 换局 waiter 的单飞闸（为什么必须有：见 [RestartWaiterGate] 的类头）。
     *
     * 本 Activity 实例私有 —— 名额随实例走，`onDestroy` 后连同 Activity 一起消失，
     * 不留跨 Activity 的全局状态（与 [SessionHandoff] 的进程级槽刻意不同：那条等的是
     * 「别的局的 VM 线程」，这条等的只是「本 Activity 自己起的那条推进线程」）。
     */
    private val restartGate = RestartWaiterGate()

    private var started = false
    private var failureShown = false

    /**
     * 「玩家刚在失败屏上按过重试」（只在 UI 线程读写，口径同 [failureShown]）。
     *
     * 存在的理由：**「重试」走的正是刚刚失败的那同一条路**，所以「重试已经试过一回、还是失败」
     * 这件事必须由屏自己认出来并给出出口（判据见 [failureActions]），不能只靠玩家自己记。
     * 按 [retryLoad] / [retryLoadCompat] 置位，下一张失败屏消费掉它。
     *
     * 刻意**不**按失败文案逐字相同来判：真 OOM 那句里嵌着每次都不一样的字节数
     * （`Failed to allocate a 4665616 byte allocation…`、以及屏上那行实测 MiB 读数），
     * 比字面会把玩家每一次都当成「第一次失败」，出口永远出不来。
     * 装载成功（[GameHost.onConsoleReady]）与「回到标题」两条复活路都要清它 ——
     * 不清就成了「两小时前那次重试」的账。
     */
    private var retriedAfterFailure = false

    /**
     * 省内存装载档开关（2026-10-28 低端机 OOM）：玩家按过失败屏的「少占内存再试」（或乙族自动
     * 降过一次档）后置 true，本会话后续 [startGameOrDemo] 构造的宿主全部带 `compatLoad = true`。
     * 只在 UI 线程读写；不持久化 —— 换机/重装后回到默认档，新包发布后也可重新评估。
     */
    private var compatLoad = false

    /**
     * 分片扁平语句池的设备归因覆盖（null = 不覆盖，走 `files/flat-statements.on` 文件开关）。
     *
     * release 包 `isDebuggable=false`，`run-as` 不可用；改由启动 Intent 的
     * [EXTRA_FLAT_STATEMENTS] 布尔 extra 传入（`adb am start --ez`），否则 +114 MiB 的
     * 真机归因只能在调试包上做、读数与发布包不可比。Activity 重建沿用同一个 Intent。
     */
    private var flatStatementsOverride: Boolean? = null

    /**
     * 本次会话有没有**自动**降过省内存档（判据与理由见 [shouldAutoRetryCompat]）。
     *
     * 与 [compatLoad] 分开记：那颗按钮被玩家按过也叫省内存档在用，但「自动降过一次」这件事
     * 只归本字段 —— 它是失败屏前面的死圈闸（pump 每帧都会再看一次这张屏）。
     * 只有乙族（装载中真 OOM）会走到这里；甲族（预检就判装不下）一次都不自动降（见
     * [HeapFailureStage]）。只在 UI 线程读写，不持久化。
     */
    private var compatAutoRetried = false

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

    /**
     * 重绘循环的连续失败熔断器（判据与取舍见 [UiFaultBreaker]）。
     *
     * 只在 UI 线程读写，与 [pump] 同线程，无需同步。
     */
    private val pumpBreaker = UiFaultBreaker()

    /** UI 线程故障累计次数（日志限流用，判据见 [UiFaultLog]）。只在 UI 线程读写。 */
    private var uiFaultCount = 0L

    /**
     * 三路心跳的记账实例（obs-heartbeat 车道；判式与阈值在 [HeartbeatJudgment]，都纯、可单测）。
     *
     * 为什么是**本 Activity 的成员**而不是 object 单例：AGENTS 铁律 4 禁静态可变全局 ——
     * 心跳的可变状态就该挂在宿主实例上（同 `GameHost` 里的 [RunWatch] 用法：状态在实例里，
     * 调用方只递引用）。Activity 换代得到一份新读数，旧壳不会被这条边钉住内存。
     * 脚本侧那一路（`vm`）的打点在 `GameHost`（本波另一路 obs-turnpct 独占那个文件），
     * 由组长接线时把这个实例交出去。
     */
    private val heartbeat = Heartbeat()

    /**
     * 上一次观察到的 console sink 水位（`GameHost.debugRevision()`）；[Long.MIN_VALUE] = 还没看过。
     *
     * 只在 UI 线程（[pump]）读写，无需同步。渲染发布这一路的判据之一，见 [markHeartbeats]。
     */
    private var lastPublishRevision = Long.MIN_VALUE

    /**
     * 上一次观察到的 `onDraw` 帧计数（`CanvasConsoleView.dbgFrameCount`）。
     *
     * 缺省 **0**（= 一帧都还没画过）而不是 -1：视图刚建好时计数就是 0，若拿 0 与「哨兵 -1」
     * 比不等，会把「一帧都没画」误判成「画了一帧」，于是开局第一发发布读数会假亮。
     */
    private var lastDrawFrameCount = 0L

    /**
     * 上一次**评估**冻结判读的时刻（`elapsedRealtime`）；-1 = 本壳还没评估过。
     *
     * 只在 UI 线程读写。节流挂在评估之前而不是钉发之后：每帧去取一次三路快照会为判读
     * 分配 4 个小对象（60 Hz ⇒ 每秒 240 个），而 30 s 评估一次对「阈值 1 s / 5 s」的
     * 形状判读完全够（代价是最坏晚 30 s 才钉上第一行）。
     */
    private var lastFreezeCheckAtMs = -1L

    /**
     * 主线程冻结哨兵（EP1 波 · L4 stall-stack）：判定 UI 帧循环超期时，由**另一条线程**
     * 把主线程与 `eratw-vm` 的栈抓下来钉进轨迹、留在内存里给报告侧读。
     *
     * 判式、阈值、冷却与体积上限全部在 [StallJudgment]（纯函数，有单测）；为什么是
     * Activity 的**成员**而不是单例：AGENTS 铁律 4 禁静态可变全局，与 [heartbeat] 同一条纪律
     * （见它上面的注释）。构造期零 Android 调用 —— 时钟与线程来源都是 lambda，
     * 只在哨兵线程真正动手时才求值，所以本字段可以在 Activity 构造期就初始化
     * （`applicationContext` 同理：钉发口在 pin 被调时已经跑在 onCreate 之后）。
     *
     * 起停：[onResume] 起、[onPause] 停、[onDestroy] 必达再停一次（幂等）。
     * 「现在该不该指望 UI 在转」由 [pump] 每帧写进 [StallSentinel.setUiLoopExpected]，
     * 装载/解包期间与熔断之外的所有「泵本来就不转」的窗口都不动手 —— 判据见那个方法。
     */
    private val stallSentinel = StallSentinel(
        heartbeat = heartbeat,
        clockMs = stallClockMs,
        mainThread = ::stallMainThread,
        vmThread = ::stallVmThread,
        // 落盘只走 RunWatch 既有的写侧闸（TrailWriteGate，RunWatch.kt:360）：这一发不在 UI 线程，
        // 所以不需要 StrictMode 豁免表条目；行尾的 `hb=` 心跳段由 forceSample 自己拼。
        pin = { phase, _ -> RunWatch.forceSample(applicationContext, phase, heartbeat) },
    )

    /**
     * 跑一段 UI 帧活；`RuntimeException` 就地兜住、只记日志，**不外逃**。
     *
     * 为什么必须兜：Android 上主线程抛出的任何未捕获异常都直接结束进程 —— 没有失败屏、
     * 没有报告、直接回桌面。这里的四段（快照差分、选项刷新、失败屏同步、内存采样）每帧
     * 都要摸一遍 VM 线程刚发布的数据，`ConcurrentModificationException` / 下标越界 /
     * `trying to use a recycled bitmap` 都是可能形态（绘制侧 `onDraw` 2026-10-31 已按同一
     * 口径兜过，这里是同一类代码的另外半边）。
     *
     * 分段兜而不是整段兜：一段坏了不该把另外三段一起拖住 —— 尤其是
     * [renderFailureIfNeeded]，它正是「别的东西坏了」时要上屏的那张失败屏。
     *
     * 刻意不 catch `Error`：理由与 `CanvasConsoleView.onDrawFailed` 逐字相同
     * （OOM 要走崩溃处理器拿真实栈并落报告；逐帧吞 OOM 会变成每帧分配失败的死循环）。
     *
     * @return true = 这一段跑完了；false = 抛过（调用方据此记一次熔断计数）
     */
    private inline fun uiStage(where: String, body: () -> Unit): Boolean = try {
        body()
        true
    } catch (t: RuntimeException) {
        logUiFault(where, t)
        false
    }

    /** UI 线程故障的限流日志：首次全栈，之后每 [UiFaultLog.EVERY] 次一行计数。 */
    private fun logUiFault(where: String, t: RuntimeException) {
        uiFaultCount += 1L
        when (UiFaultLog.mode(uiFaultCount)) {
            UiFaultLogMode.FULL ->
                Log.e(TAG, "UI 线程异常（$where）：本次放弃，进程继续（首次全栈）", t)
            UiFaultLogMode.SHORT ->
                Log.w(TAG, "UI 线程异常（$where）累计 $uiFaultCount 次：" +
                    "${t.javaClass.simpleName}: ${t.message}")
            UiFaultLogMode.SILENT -> Unit
        }
    }

    /**
     * 把壳层上的一颗按钮动作包成「出错只记日志、进程继续」。
     *
     * 这些 lambda 全部由 Compose 在 UI 线程直接调用：`openSettings`（起 Activity）、
     * `submitInput`（摸输入队列与壳层状态）、`switchMacroGroup`（读盘）、`retryLoad` /
     * `retryLoadCompat` / `backToTitle`（拆会话重装）、复制与分享（剪贴板 / FileProvider）
     * —— 任一处抛 `RuntimeException`，玩家看到的就是「点了一下按钮，应用没了」。
     *
     * 只兜 `RuntimeException`：与 [uiStage]、`CanvasConsoleView.onDrawFailed` 同一条口径
     * （OOM 要走崩溃处理器拿真实栈，不许被按钮回调吞掉）。
     */
    private fun guardedAction(where: String, action: () -> Unit): () -> Unit = {
        try {
            action()
        } catch (t: RuntimeException) {
            logUiFault(where, t)
        }
    }

    /** [guardedAction] 的带参版本（`onOption` / `onInputChanged` 收一个字符串）。 */
    private fun guardedTextAction(where: String, action: (String) -> Unit): (String) -> Unit = { text ->
        try {
            action(text)
        } catch (t: RuntimeException) {
            logUiFault(where, t)
        }
    }

    /**
     * `onSubmit: (String) -> Boolean` 的兜底版本。
     *
     * 失败时返回 **false**（「这次没提交出去」）而不是 true：true 会让输入条把玩家刚打的字
     * 清掉，那等于在出错时顺手丢掉他要说的话；false 至少把文本留在框里，可以再按一次。
     */
    private fun guardedSubmit(where: String, action: (String) -> Boolean): (String) -> Boolean = { text ->
        try {
            action(text)
        } catch (t: RuntimeException) {
            logUiFault(where, t)
            false
        }
    }

    private val pump = object : Runnable {
        override fun run() {
            // 被 onPause 摘不掉的「已排帧回调」撞上时从这里退出，不再续排。
            if (destroyed || !pumping) return
            val nowMs = android.os.SystemClock.elapsedRealtime()
            // 熔断冷却期内整段跳过（仍要续排，见下方）：一帧一崩时 60 Hz 重试只会把 CPU
            // 烧光、把玩家看到的东西变成幻灯片；冷却到点自动放行一次探针帧。
            if (pumpBreaker.shouldRun(nowMs)) {
                // 2026-10-22 真机抖动收口：只 pump，有新快照才 invalidate——
                // 此前每帧无条件重绘（120Hz 屏 ~107fps、legacy janky 87%）＝高频抖动根因。
                // 四段**各自**兜住（先全部跑完再合并结果，不能用 && 短路——一段坏了不该
                // 把后面几段一起跳过；理由见 uiStage 的 KDoc）。
                val snapshotOk = uiStage("快照差分") { consoleView.pumpAndInvalidateIfNeeded() }
                val shellOk = uiStage("选项刷新") { refreshShell() }
                val failureOk = uiStage("失败屏同步") { renderFailureIfNeeded() }
                // 内存采样（缺省关，见 GameHost.sampleMemoryIfNeeded）：20 秒一条，没开开关时
                // 这里只是一次 long 比较。release 包没有 run-as/DevTools 通道，这是唯一能在
                // 玩家机上拿到「G 系像素常驻涨不涨」读数的口。
                val sampleOk = uiStage("内存采样") { host?.sampleMemoryIfNeeded(nowMs) }
                if (snapshotOk && shellOk && failureOk && sampleOk) {
                    pumpBreaker.recordSuccess()
                } else if (pumpBreaker.recordFailure(nowMs)) {
                    Log.w(
                        TAG,
                        "重绘连续异常 ${UiFaultBreaker.DEFAULT_THRESHOLD} 次：暂停本循环 " +
                            "${pumpBreaker.cooldownUntilMs - nowMs}ms 后自动重试探针帧",
                    )
                }
                // 心跳两路（UI 帧 + 渲染发布）与冻结钉行；埋点自己出事不许把游戏搞崩（同 uiStage 的纪律）。
                runCatching { markHeartbeats(nowMs, snapshotOk, shellOk, failureOk, sampleOk) }
                    .onFailure { Log.w(TAG, "心跳记账失败（不影响游戏）", it) }
            } else {
                // 熔断冷却帧：四段没跑，但这条回路**确实在转** —— 不记一次就会把「熔断冷却」
                // 误读成「UI 帧循环死了」，而这两种在报告里是完全不同的病。
                runCatching { heartbeat.markUiFrame(nowMs, "熔断冷却中") }
                    .onFailure { Log.w(TAG, "心跳记账失败（不影响游戏）", it) }
            }
            // **无条件**续排（仍按原来的运行态判据）：循环一停，屏就永久冻在最后一帧，
            // 连失败屏都上不来 —— 兜底反而制造「卡死」比崩溃更难解释。
            // 哨兵的「此刻该不该指望 UI 在转」：写的就是这一帧的**续排判据本身**，不是 `pumping`
            // 一个条件 —— 解包期间 `host` 还是 null、脚本线程已退出时 `isRunning()` 为假，
            // 那两种「泵本来就不转」不是冻结，动手抓栈只会钉出一屏 `nativePollOnce` 的假现场。
            // 每帧一次 volatile 写，成本与 [Heartbeat] 自己的打点同级（见 StallSentinel 类头）。
            //
            // `failurePending`：宿主线程已经退出、而失败屏还没画上的那一拍也**必须再转一帧**。
            // 宿主把 `failure` 的发布挪到了释放之后（见 `GameHost.loadAndRun` 的 catch：先关会话
            // 再上屏），于是「发布」与「线程死亡」几乎同时发生 —— 若只认 `isRunning()`，最后一次
            // 续排可能早在发布之前就定了，失败屏就永远画不出来（玩家看到的是停在加载页的黑屏，
            // 正是本刀要修的那种「什么都没看到」）。画上（`failureShown`）或自动降档（宿主要换）
            // 之后这个条件立刻为假，不会空转。
            val failurePending = !failureShown && host?.failure != null
            val loopContinues = pumping && (host?.isRunning() == true || failurePending)
            runCatching { stallSentinel.setUiLoopExpected(loopContinues) }
                .onFailure { Log.w(TAG, "冻结哨兵武装位写入失败（不影响游戏）", it) }
            if (loopContinues) consoleView.postOnAnimation(this)
        }
    }

    /**
     * 心跳打点（obs-heartbeat 车道）：UI 帧 + 渲染发布两路，外加「形状异常就钉一行轨迹」。
     *
     * ## 为什么渲染发布不能用 `pumpAndInvalidateIfNeeded()` 的返回值
     *
     * 实读：那个方法**返回 Unit**（`render-canvas/.../CanvasConsoleView.kt:1013-1019`），
     * 里面只问一次 `feed?.hasUnpublishedContent()` 再 `invalidate()`，既不把「有没有新快照」
     * 交回调用方、也不是发布动作本身 —— 发布在 `onDraw` 的 `attached.pump()`（:1682），
     * 而 `ConsoleFeed.publishedRevision` 是 private（`render/.../ConsoleFeed.kt:156`），app 读不到。
     * ⇒ 用两条同时成立的只读证据：**水位前进**（`GameHost.debugRevision()` :1203，与
     * `ConsoleFeed.hasUnpublishedContent()` 比的是同一个数）+ **`onDraw` 跑了一帧**
     * （`CanvasConsoleView.dbgFrameCount` :1544 / :1691）。只有水位动、没人画帧 ⇒ **不记**，
     * 那正是「UI 在转但屏上不更新」要读出来的形状。计数用**不等**判定而不是**增大**判定：
     * `debugStats()`（:1585）会把帧计数清零，用增大判会被调试通道永久打断这一路。
     *
     * ## 冻结钉行
     *
     * 判式纯在 [HeartbeatJudgment.shouldPinFreeze]（有单测），30 s 评估一次；钉出来的那行
     * 带三路距今值（`freeze <判读> hb=ui:…/publish:…/vm:…`）落进 [RunWatch] 的滚动轨迹 ——
     * 「死在屏前」的报告是下一次冷启动落的，那时活读数早没了，只有盘上最后一行能回答
     * 「谁先停」。
     */
    private fun markHeartbeats(
        nowMs: Long,
        snapshotOk: Boolean,
        shellOk: Boolean,
        failureOk: Boolean,
        sampleOk: Boolean,
    ) {
        val failed = StringBuilder(24)
        if (!snapshotOk) failed.append("快照差分 ")
        if (!shellOk) failed.append("选项刷新 ")
        if (!failureOk) failed.append("失败屏同步 ")
        if (!sampleOk) failed.append("内存采样 ")
        val stage = if (failed.isEmpty()) "四段全成" else "四段失败:${failed.toString().trim()}"
        heartbeat.markUiFrame(nowMs, stage)

        val revision = host?.debugRevision() ?: -1L
        val frames = consoleView.dbgFrameCount
        if (revision > 0L && revision != lastPublishRevision && frames != lastDrawFrameCount) {
            heartbeat.markRenderPublish(nowMs, "rev=$revision frames=$frames")
            lastPublishRevision = revision
        }
        lastDrawFrameCount = frames

        val sinceCheckMs = if (lastFreezeCheckAtMs < 0L) -1L else nowMs - lastFreezeCheckAtMs
        if (sinceCheckMs < 0L || sinceCheckMs >= HeartbeatJudgment.FREEZE_PIN_MIN_INTERVAL_MS) {
            lastFreezeCheckAtMs = nowMs
            val snapshot = heartbeat.snapshot()
            if (HeartbeatJudgment.shouldPinFreeze(nowMs, sinceCheckMs, snapshot)) {
                val verdict = HeartbeatJudgment.verdict(snapshot, nowMs)
                // 这一发是**帧循环里的 UI 线程写盘**（obs-uiio 车道裁：甲）。为什么当场做完比挪线程更对：
                // 脏退出报告是下一次冷启动落的，那时活读数早没了，只有盘上最后一行还认得出「谁先停」；
                // 挪到后台线程要给 trail.txt 添第三个写者（现在只有 UI 与 VM 两条线程共用 RunWatch
                // 那一份内存环形表），取证文件会开始讲不清先后。节流与理由在
                // StrictModeGuard.uiDiskIoExemptions 的「冻结钉行轨迹落盘」那一条；过 2 s 会由
                // EratwStrictMode 自己留一行带标签的慢样本读数（豁免不等于闭嘴）。
                EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_FREEZE_TRAIL) {
                    RunWatch.forceSample(
                        applicationContext,
                        "${HeartbeatJudgment.FREEZE_PHASE} ${verdict.key}" +
                            HeartbeatJudgment.trailSuffix(nowMs, snapshot),
                    )
                }
            }
        }
    }

    /**
     * 最近一次「主线程冻结现场」的正文（internal 读数口，EP1 波 · L4 stall-stack）。
     *
     * 谁该用它：报告侧（`ErrorReportCollector`，L6 独占）在**同一进程内**采集时把这一节并进
     * 补充现场 —— `[线程心跳]` 那一节只说得出「主线程 30 秒没动」，这一节说得出它**停在哪一行**。
     * 空表 = 本进程从没抓到过（没冻结 / 哨兵没起 / 抓栈失败），调用方据此**不写这一节**，
     * 不许写「未取到」冒充读数（那是现场内部对某一条线程的说法，不是对整节的说法）。
     *
     * 接线位置提醒（体积纪律）：core 侧对补充现场是 `takeLast(EXTRA_MAX_LINES)`（保**尾**，
     * `core/diag/ErrorReport.kt:191` = 40 行），而脏退出报告光轨迹就 60+ 行 ⇒
     * 本小节（最多 [StallJudgment.SCENE_MAX_LINES] 行）要放在 `[线程心跳]` 那一节**之后**
     * 才不会被剪掉。
     *
     * 只读、绝不抛：`sceneLines()` 内部是 volatile 读 + 已裁好的表拼接，仍套一层 runCatching
     * —— 采集器可能在崩溃线程上被调，那一路再分配失败就是「报告没了」。
     */
    internal fun stallSceneLines(): List<String> = runCatching { stallSentinel.sceneLines() }
        .getOrDefault(emptyList())

    /**
     * 切后台 30 秒后才冻结脚本（[GameHost.setBackgroundPaused]）。
     *
     * 设置页盖在游戏上、下拉通知栏、切一下别的应用都会走 `onStop`；立即暂停会把
     * 「改个设置再回来」变成「游戏冻了几秒」。真后台（锁屏/切走不回来）才冻结。
     * 只调这一个口：暂停动作本身在 [GameHost] 里转给会话，Activity 不直接碰 VM。
     */
    private val backgroundPauseRunnable = Runnable {
        // 这一发跑在屏幕已经不可见之后：抛出去就是「切后台被杀」，玩家回来发现应用没了，
        // 而且现场一点线索都没有（正是那一类「静默死」的形态）。冻结失败最多是多跑一会儿脚本。
        uiStage("后台冻结") { host?.setBackgroundPaused(true) }
    }

    // ------------------------------------------------------------------
    // C6 启动兜底横幅（wave/eng-crash）
    // ------------------------------------------------------------------

    /**
     * 上一次崩溃处理器写下的「上次崩溃摘要」；null = 没有（正常退出的轮次一定是 null）。
     *
     * 只在 `onCreateCore` 读标记时赋值、`showPendingCrashBannerIfAny` 消费一次；
     * 只在 UI 线程读写，无需同步。
     */
    private var pendingCrashBanner: CrashSummary? = null

    /**
     * 有「上次崩溃摘要」就在既有壳层提示面上补一条可关闭的提示（[EratwShellDialog]：
     * 平台 `AlertDialog` + 统一外观件，与显示大小/方向/恢复默认那三张确认框同一副面板）。
     *
     * 为什么不是新造整页 UI：这一句只是「上次死因」的回执，玩家的下一步仍然是开局；
     * 整页壳会挡住装载、也会与既有的「上次异常退出」报告卡抢话。
     *
     * 「知道了」与点卡外（返回键 / 遮罩）都清标记：玩家已经看到了，下一次启动不该再提；
     * 这次没看到就强杀进程也没关系 —— 标记还在盘上，下次仍会提示。
     */
    private fun showPendingCrashBannerIfAny() {
        val summary = pendingCrashBanner ?: return
        pendingCrashBanner = null
        val copy = runCatching { CrashScreenCopy.of(UiPrefs.settingsLanguage(this)) }
            .getOrDefault(CrashScreenCopy.ZH)
        runCatching {
            EratwShellDialog.show(
                activity = this,
                title = copy.bannerTitle,
                body = CrashSummaryText.bannerBody(copy, summary),
                positiveLabel = copy.bannerAck,
                onPositive = { clearCrashSummaryMarker() },
                onCancel = { clearCrashSummaryMarker() },
            )
        }.onFailure { Log.w(TAG, "上次崩溃提示没能上屏（游戏照常启动）", it) }
    }

    /**
     * 清「上次崩溃摘要」标记（玩家点掉横幅 / 本次已进入主循环）。
     *
     * 删文件放一条短命后台线程：这一发落在按钮回调与首帧回调里，既不在「进程将死」的边上
     * 也不是第一帧之前，按 `StrictModeGuard` 类头那把尺子不该进 UI 线程磁盘 IO 豁免表；
     * 挪走它不改任何语义（删失败的唯一后果是下次启动多提示一次）。
     */
    private fun clearCrashSummaryMarker() {
        val dir = ErrorReportCollector.reportDir(applicationContext)
        runCatching {
            Thread({ runCatching { CrashSummaryMarker.clear(dir) } }, "eratw-crash-marker-clear").start()
        }.onFailure { Log.w(TAG, "崩溃摘要标记清除线程没起来（下次启动会再提示一次）", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 建壳失败 = 玩家看到的是「装上就闪退」，连一句解释都没有（崩溃报告落在私有
        // filesDir 里，大陆网络常常送不出去，玩家手里什么都没有）。这里是**最后一屏**：
        // 把异常折成一段可截图发回来的文字 + 一颗「重启应用」，而不是让进程直接消失。
        // 兜的是异常/错误两类（OOM 也罢）：能走到这一层说明界面根本没建起来，
        // 交给崩溃处理器只会得到「闪退」——那正是要避免的那一种体验。
        try {
            onCreateCore(savedInstanceState)
        } catch (t: Throwable) {
            CrashForensics.renderBootstrapFailure(this, t, ::scheduleRelaunch)
        }
    }

    /**
     * [onCreate] 的正体：建存储、建视图、装壳层、挂首次布局回调。
     *
     * 抽出来只为让 [onCreate] 能用一层 try/catch 把整段包住（见那里的 last-resort 语义）。
     * 这里任何一步抛出都意味着「界面没建起来」，没有可用的壳层可写失败屏 —— 所以
     * 调用方不许在 catch 里碰 `consoleView` / `shell`（它们可能还是 null 或半装配）。
     */
    private fun onCreateCore(savedInstanceState: Bundle?) {
        // 归因开关必须在建 GameHost 之前落下（见 [flatStatementsOverride]）；没带 extra 时为 null。
        flatStatementsOverride = intent?.let { i ->
            if (i.hasExtra(EXTRA_FLAT_STATEMENTS)) i.getBooleanExtra(EXTRA_FLAT_STATEMENTS, false) else null
        }
        // 崩溃兑底（方案①）：任何线程未捕获异常先落一份报告再交给系统默认处理。
        // 装在 onCreate 最早处：之后任何阶段的崩（解包/装载/脚本线程外逃）都走这里。
        // 报告采集自会 runCatching 兑底，不会拦住真崩。
        CrashForensics.install(this, hostProvider = { host })
        // 冻结现场读数口：每次 onCreate 刷新到最新一只壳（口径同 CrashForensics 的 crashHostProvider，
        // 见 CrashForensics.install 里那段 fix-staticpin 的说明：真正不钉住死壳的是
        // [stallSceneOwner] 身份闸 + onDestroy 的认主摘除，不是「用了 provider」这个写法）。
        stallSceneOwner = this
        stallSceneProvider = { stallSentinel.sceneLines() }
        // 静默死亡采集（2026-10-30 玩家反馈「死机了」「这次没有报错，直接闪退了」）：
        // 第一件事读上一轮的收尾标记，脏的就在开工前补一份「上次异常退出」报告走既有上报链，
        // 然后写下本进程的 `running`。放在崩溃兜底之后：万一补报告这半截自己出事，
        // 也有崩溃兜底接着落一份，不会把启动挡死。
        //
        // 这一段是**纯记账**：存储满 / 只读 / 文件被外部删掉都会让它抛，而那时游戏本身
        // 完全能跑。所以整段兜住 —— 「采样挂了」不该升级成「启动失败」。
        //
        // UI 线程磁盘 IO 的裁定（obs-uiio 车道）：**甲**（进豁免表）。跑在第一帧之前 ⇒ 没有在飞的帧可拖；
        // 而它必须在装载开始前读完上一轮的 state.txt / trail.txt 并把脏退出补报落到盘上，
        // 挪到后台线程就是把下一行 consumeDirtyExitReportFile 的成立条件改成竞态（那张卡会随机不弹）。
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_COLD_START) {
                RunWatch.beginProcess(applicationContext)
            }
        }.onSuccess { dirtyExit ->
            // R3：上一轮是否脏退出进事件链（记账失败时不留这条 —— 那时真假未知）。
            EventJournal.record("dirty_exit", mapOf("state" to if (dirtyExit) "dirty" else "clean"))
        }.onFailure { Log.w(TAG, "启动：进程状态记账失败（游戏照常启动）", it) }
        // 脏退出补报落出的报告交给壳层（冷启后弹「复制 / 分享报告」卡）：自动上报在大陆
        // 网络经常送不出去，这份文件是玩家唯一送得出去的东西，不能只躺在私有目录里。
        runCatching { RunWatch.consumeDirtyExitReportFile() }
            .getOrNull()
            ?.let { shell.pendingDirtyExitReport = it }
        // C6 启动兜底横幅（wave/eng-crash）：上一次崩溃处理器写下的「上次崩溃摘要」。
        // 只在**崩溃处理器**里写（`CrashForensics.surfaceCrashScreen`），所以这里读到的一定是
        // 「上一轮真崩过」；正常退出的轮次没有这份文件，横幅不会误报。读盘仍走冷启动同一个
        // 豁免窗口（第一帧之前；豁免表归另一 lane，本波不新增条目），失败的形态是「少一句提示」。
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_COLD_START) {
                CrashSummaryMarker.read(ErrorReportCollector.reportDir(applicationContext))
            }
        }.getOrNull()?.let { pendingCrashBanner = it }
        // 方案③：冷启动补发上一轮失败/断网的报告（端点未配置时是 no-op）。
        runCatching { ErrorReportUploader.uploadPendingAsync(applicationContext) }
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        storage = GameStorage(this)
        // 方向（竖屏设计稿 §6）：manifest 不锁向，方向权在这里，且**竖屏只在玩家
        // 设置页显式打开「竖屏模式」时出现**。关 = 锁横屏（复现原 manifest 的
        // sensorLandscape）；开 = 锁竖屏。没有「跟随机转」——那一档会让手机竖着拿
        // 启动就整个壳切成竖屏版面（2026-11-04 玩家实测的意外竖屏）。
        // 切偏好要重建 Activity 才生效（本实例的壳分支与列宽都是构造期定死）。
        val portraitPick = UiPrefs.portraitMode(this)
        requestedOrientation = if (portraitPick) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // 按方向选档（竖屏设计稿 §6.4 + §5.2）：竖屏档与横屏档互相独立、装配期二选一，
        // 改档走 recreate()（§0-2 与横屏同口径）。判据见 [portraitNow] 的 KDoc。
        portraitNow = portraitPick
        // 「显示大小」档由设置页决定（默认 136 列 = 字号 ×1.00 + 守护 136 列，2400×1080/400dpi
        // 守护后约 33px，128 格地图一行放得下；旧版 110 列会把地图折行）。
        // 倍率与守护下限同出一个档位值；守护开关也是构造期参数，一并在这里定格。
        //
        // 竖屏档（§5.2：50/62/75/90，缺省 62）**不走**横屏那张 136 反推倍率表：
        // 倍率 = 62 ÷ 档位列数（缺省 62 档 = ×1.00，与横屏「缺省档 = ×1.00」同构），
        // 守护下限 = 档位列数本身——90 档靠守护压字号、50 档靠倍率抬字号。§5.2 算例
        // 的两台参考机三档落点 ±1px；S24U 的 62 档约差 2px，真机读数 TODO(verify)。
        appliedFontColumns = if (portraitPick) {
            UiPrefs.fontColumnsPortrait(this)
        } else {
            UiPrefs.fontColumns(this)
        }
        appliedScreenFit = UiPrefs.screenFit(this)
        // LIVE-REFLOW 开关：**装配期只读这一次**，同一个值既喂视口（下面构造 [CanvasConsoleView]）
        // 也喂核心（[startGameOrDemo] 传给 [GameHost]）。两处各读一次会因设置页在中间被改动而
        // 分叉成「视口一直问、核心一直拒」，判据见 [appliedLiveReflow] 的 KDoc。
        appliedLiveReflow = UiPrefs.liveReflow(this)
        consoleView = CanvasConsoleView(
            this,
            minColumnsOverride = appliedFontColumns,
            columnGuardEnabled = appliedScreenFit,
            fontScale = if (portraitPick) {
                UiPrefs.DEFAULT_FONT_COLUMNS_PORTRAIT.toFloat() / appliedFontColumns
            } else {
                UiPrefs.fontScale(this)
            },
            // 关 = 现有横屏/竖屏「会话期宽度冻结、只改行数」路径逐字不变（契约 §0/§5 L4）。
            liveReflow = appliedLiveReflow,
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
                Box {
                    EratwShell(
                        consoleView = consoleView,
                        state = shell,
                        // 全部按钮回调过一层 guardedAction / guardedTextAction：Compose 在 UI
                        // 线程直接调它们，抛一次就是「点了一下按钮，应用没了」（见 KDoc）。
                        // `onExitApp` 例外 —— 它内部已逐步兜住且必然走到 `exit(0)`。
                        onSettings = guardedAction("打开设置") { openSettings() },
                        onSubmit = guardedSubmit("提交输入") { text -> submitInput(text) },
                        // 右侧栏选项按钮与画面命中区同属「画面按钮点击」：执行期落点同样要保活。
                        onOption = guardedTextAction("选项按钮") { value -> submitInput(value, clickIntent = true) },
                        onMacroGroup = guardedAction("切换宏组") { switchMacroGroup() },
                        onRetry = guardedAction("重试装载") { retryLoad() },
                        onRetryCompat = guardedAction("兼容档重试") { retryLoadCompat() },
                        onBackToTitle = guardedAction("回到标题") { backToTitle() },
                        onExitApp = { exitApplication() },
                        onCopyReport = guardedAction("复制报告") { copyFailureReport() },
                        onShareReport = guardedAction("分享报告") { shareFailureReport() },
                        onCopyDirtyReport = guardedAction("复制退出报告") { copyDirtyExitReport() },
                        onShareDirtyReport = guardedAction("分享退出报告") { shareDirtyExitReport() },
                        onRailToggle = guardedAction("选项栏开关") {
                            shell.railVisible = !shell.railVisible
                            UiPrefs.setOptionRailVisible(this@MainActivity, shell.railVisible)
                        },
                        // 方向锁（键盘旁）：锁定 = 冻在当前方向（不随传感器翻转）；解锁 =
                        // 回到**本局方向偏好**的传感器向（横屏局 SENSOR_LANDSCAPE / 竖屏局
                        // SENSOR_PORTRAIT）。会话级，见 ShellState。
                        //
                        // 解锁不能写 UNSPECIFIED（2026-11-04 玩家反馈）：那等于把方向决定权
                        // 交给系统「自动旋转偏好」，手机若开着系统旋转锁（竖屏锁定），
                        // 系统就把游戏钉成竖屏，玩家只能去系统设置里转一下屏幕才能恢复。
                        onOrientationLock = guardedAction("锁定屏幕方向") {
                            shell.orientationLocked = !shell.orientationLocked
                            requestedOrientation =
                                orientationForLock(shell.orientationLocked, portraitNow)
                        },
                        onInputChanged = guardedTextAction("镜像输入框") { text -> mirrorTextBox(text) },
                        // 竖屏三区壳（竖屏设计稿 §6/§8 接线点）：装配期按方向定死分支；
                        // linesProvider 是 HUD 尾窗快照的唯一通路——经 GameHost.debugSession()
                        // 的只读口取 LineBufferConsoleSink.lines() 尾窗（GameHost 零改动），
                        // 250ms 轮询与解析都在竖屏分支里（PortraitShellContent）。
                        isPortrait = portraitNow,
                        portraitLinesProvider = { portraitScreenLines() },
                    )

                }
            }
        }

        refreshShell()

        // 沉浸式必须在 setContent（decorView 建成）之后调用：`window.insetsController`
        // 在 decorView 为 null 时是 NPE（2026-10-23 真机闪退实证：onCreate 里提前调用，
        // PhoneWindow.getInsetsController → DecorView.getWindowInsetsController 空引用）。
        applyImmersiveMode()
        keepScreenOn()
        // C6 启动兜底横幅：窗口已建成（`applyImmersiveMode` 之后），此刻弹既有提示面最稳。
        // 标记来自上一次崩溃处理器，装载还在后面 ⇒ 玩家先读到「上次怎么了」，再等开局。
        runCatching { showPendingCrashBannerIfAny() }
            .onFailure { Log.w(TAG, "上次崩溃提示这一段出事（游戏照常启动）", it) }

        consoleView.viewTreeObserver.addOnGlobalLayoutListener {
            if (started || consoleView.consoleScreen() == null) return@addOnGlobalLayoutListener
            started = true
            // 更新检查挂在「首屏已经画出来」这一拍，而不是 onCreate：装在 onCreate 会和首启解包、
            // 装载抢带宽与 CPU（本仓的启动耗时读数就盯这一段）。本进程只做一次，见 UpdateUi。
            UpdateUi.scheduleStartupCheck(this, storage.gameRoot())
            startGameOrDemo(storage)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 设置页回来这一下是玩家刚点完「保存」的时点：这里抛一次就是一次闪退，
        // 而且现场还停在设置页返回的转场上。读档位 / 换局都按「失败进失败屏」处理。
        try {
            val fromSettings = requestCode == REQUEST_SETTINGS
            val wantsRestart = fromSettings && resultCode == RESULT_OK &&
                data?.getBooleanExtra(SettingsActivity.EXTRA_RESTART_GAME, false) == true
            // 显示大小档 / 列数守护开关 / LIVE-REFLOW 开关都是构造期参数，但设置页是**随改随存**的：
            // 玩家改完档位直接返回（RESULT_CANCELED、无 EXTRA_RESTART_GAME）时，旧实现被
            // `!wantsRestart` 提前挡掉，当前会话继续按旧档位渲染 —— 玩家的体感就是「显示大小设置了无效」。
            // 这里按「本次进设置页前后的快照」判有没有真的动过，再决定要不要问、要不要重开。
            // 判据本体抽在 [displaySettingChanged]（纯函数，app 单测直测：`MainActivity` 起不来）。
            val displayChanged = fromSettings && displaySettingChanged(
                nowFontColumns = UiPrefs.fontColumns(this),
                appliedFontColumns = appliedFontColumns,
                nowScreenFit = UiPrefs.screenFit(this),
                appliedScreenFit = appliedScreenFit,
                nowLiveReflow = UiPrefs.liveReflow(this),
                appliedLiveReflow = appliedLiveReflow,
            )
            val displayTouched = fromSettings && displaySettingChanged(
                nowFontColumns = UiPrefs.fontColumns(this),
                appliedFontColumns = settingsEntryFontColumns,
                nowScreenFit = UiPrefs.screenFit(this),
                appliedScreenFit = settingsEntryScreenFit,
                nowLiveReflow = UiPrefs.liveReflow(this),
                appliedLiveReflow = settingsEntryLiveReflow,
            )
            // 「竖屏模式」也是装配期参数（壳分支、列宽、requestedOrientation 都在
            // onCreate 定死）：改了只有重建 Activity 才生效。
            val orientationTouched = fromSettings &&
                UiPrefs.portraitMode(this) != settingsEntryPortraitMode
            // 「恢复默认设置」一次性把 emuera.config 的重启键与全部应用偏好还原成出厂值，
            // 上面三条判据只看玩家手改过的显示档，覆盖不到这一下。标记由设置页在恢复成功后
            // 写入、这里消费一次：返回游戏时问一句要不要重开，不让改完的设置静悄悄不生效。
            val restorePending = fromSettings && UiPrefs.consumeRestoreRestartPending(this)
            if (!wantsRestart && !displayTouched && !orientationTouched && !restorePending) return
            if (orientationTouched) {
                // 方向变更要重建整只 Activity（连带重开游戏），两种入口在这里合流：
                // 玩家按了「保存并重启游戏」就直接执行，否则先问一句。
                if (wantsRestart) restartGame { recreate() } else confirmOrientationRestart()
                return
            }
            if (displayChanged) {
                // 字号是构造期参数：档位变了必须重建 Activity（重建 CanvasConsoleView 与
                // metrics/paints），否则设置页显示与实际渲染不一致。
                // `recreate()` 之前同样要等旧会话让出堆：新实例自己会起装载，而旧实例的
                // `onDestroy` 只 close 不 join（`closed` 只在泵边界被看），两份 Program 同住
                // 512 MiB 堆就是那次 OOM。
                if (wantsRestart) {
                    // 玩家已按「保存并重启游戏」，不再多问一遍。
                    restartGame { recreate() }
                } else {
                    // 只是从设置页返回：重开才会换字号，但重开会回到标题。先征得同意，
                    // 玩家拒绝就保留当前会话（偏好已落盘，下次启动/重开时生效）。
                    confirmDisplaySizeRestart()
                }
                return
            }
            // 恢复默认之后：配置已写盘，但重启键与构造期偏好只在下一次会话生效。
            // 让玩家自己决定要不要重开（重开会回到标题、丢未存档进度），拒绝就下次启动生效。
            if (restorePending && !wantsRestart) {
                confirmRestoreDefaultsRestart()
                return
            }
            // 档位改回原值或无变化，但玩家按了「保存并重启游戏」：改的多半是
            // `emuera.config` 的 FONT SIZE / LINE HEIGHT / 字体，这些只在会话开始前灌入
            // （见 GameHost.applyBootFontMetrics），重开会话即可，不必重建 Activity。
            if (wantsRestart) restartGame()
        } catch (t: Throwable) {
            onRestartFault(t)
        }
    }

    /**
     * 从设置页返回、显示大小档 / 列数守护开关 / LIVE-REFLOW 开关变了，但玩家没有按
     * 「保存并重启游戏」时问一句。
     *
     * 字号/列宽是会话构造期定死的（`ConsoleFeed` 的 sink 宽与展平 metrics 都按会话开始时的
     * 列数冻结），LIVE-REFLOW 开关同理（它同时是 `CanvasConsoleView.liveReflow` 的构造参数与
     * `GameSession.reflowConsole` 的实参，见 [appliedLiveReflow]），所以三者都只能重开会话才换；
     * 而重开会话会回到标题，未存档的进度拿不回来。
     * 这里只在玩家**显式改过这几项**时才问，拒绝就保留当前会话（偏好已落盘，下次启动生效）。
     * 外观走 [EratwShellDialog]：仍是平台 `AlertDialog`（返回键与按钮回调要交回框架），
     * 但面板、字号、按钮大小写由共用件统一，不再是系统那副长相。
     */
    private fun confirmDisplaySizeRestart() {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        runCatching {
            EratwShellDialog.show(
                activity = this,
                title = copy.displaySizeDialogTitle,
                body = copy.displaySizeDialogBody,
                positiveLabel = copy.dialogRestartNow,
                onPositive = { runCatching { restartGame { recreate() } }.onFailure(::onRestartFault) },
                negativeLabel = copy.dialogApplyNextLaunch,
            )
        }.onFailure(::onRestartFault)
    }

    /**
     * 从设置页返回、「竖屏模式」开关变了，但玩家没按「保存并重启游戏」时问一句。
     *
     * 方向与壳层分支都是装配期定死的（[portraitNow]），只能重建 Activity 才换得过来；
     * 重建会重开一局回到标题，未存档的进度拿不回来，所以必须玩家点头。
     * 拒绝就保留当前会话（偏好已落盘，下次启动生效）。
     */
    private fun confirmOrientationRestart() {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        val target = if (UiPrefs.portraitMode(this)) copy.orientationPortrait else copy.orientationLandscape
        runCatching {
            EratwShellDialog.show(
                activity = this,
                title = copy.orientationDialogTitle,
                body = copy.fill(copy.orientationDialogBodyFormat, target),
                positiveLabel = copy.dialogRestartNow,
                onPositive = { runCatching { restartGame { recreate() } }.onFailure(::onRestartFault) },
                negativeLabel = copy.dialogApplyNextLaunch,
            )
        }.onFailure(::onRestartFault)
    }

    /**
     * 「恢复默认设置」之后从设置页返回时问一句：配置已写盘，但字号、PRINTC 列数、
     * MaxLog 这些重启键和构造期偏好只在下一次会话生效。
     *
     * 与 [confirmDisplaySizeRestart] 同一副面板、同一对按钮，但文案说的是「恢复默认」而不是
     * 「显示大小已更改」；同意才 `restartGame { recreate() }`（方向与字号档都要重建 View 才换），
     * 拒绝就保留当前会话 —— 值已在盘上，下次冷启自然生效。进度不做主，交给玩家。
     */
    private fun confirmRestoreDefaultsRestart() {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        runCatching {
            EratwShellDialog.show(
                activity = this,
                title = copy.restoreDefaultsDialogTitle,
                body = copy.restoreDefaultsDialogBody,
                positiveLabel = copy.dialogRestartNow,
                onPositive = { runCatching { restartGame { recreate() } }.onFailure(::onRestartFault) },
                negativeLabel = copy.dialogApplyNextLaunch,
            )
        }.onFailure(::onRestartFault)
    }

    /**
     * 运行时权限回调（目前唯一使用者：更新下载的 `POST_NOTIFICATIONS`，API 33+）。
     *
     * 转给 [UpdateUi.onPermissionResult] 续跑「玩家已点过下载」的那一单：授权与拒绝**都要续跑**
     * （拒绝只是看不到通知，下载照跑，Toast 里说明白），所以这里不判结果、只转发。
     * 壳层任何权限回调都不许把游戏带崩 —— 整段 `runCatching`，异常只落 logcat。
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        runCatching { UpdateUi.onPermissionResult(requestCode, grantResults) }
            .onFailure { Log.w(TAG, "权限回调异常（不影响游玩）", it) }
    }

    /**
     * 系统给内存压力 = 先释放能无条件收回来的那坨像素，再钉一发堆读数落盘（2026-10-30）。
     *
     * 这是「死机/闪退」与「Java 堆贴顶」之间唯一的桥：玩家报的那类死不给 Java 异常，
     * 但 lmkd / OEM 后台管控在动手之前**会**先发这个回调。三档都收（含 `UI_HIDDEN`，
     * 因为切后台被杀同样是「玩了一会儿就没了」的形状），阶段名带上档位与释放量，报告里直接可读。
     *
     * 顺序刻意为「先释放、后采样」：这一发因此记的是**收完之后**的水位，与上一发对比就能
     * 看出这刀有没有真的把堆拉回来（而不是只记到一个「快死了」的读数）。
     *
     * 另一层（同回调合并）：档位命中 [GraphicsTrimPlan] 的回收集就再清**可重建**的位图缓存
     * （[GameHost.trimGraphicsCaches]）—— 位图在 native 堆、按需重解，最坏掉一帧；与上面的
     * 像素回收互相独立。不碰 VM、变量池与存档 —— 这些清掉就回不来了。收口分深浅两档
     * （判式 [NativeTrimPlan.isDeep]）：前台 `RUNNING` 系只收行缓存/行池（画面不动），
     * 后台系连本屏的整屏保留面与背景烘焙面一起交还（回前台整幅重画一帧）—— 这两块
     * 2026-11-02 之前**不在任何 trim 入口后面**，而它们和平台三张表加起来才是「native 驻留
     * 286~361 MiB」那一桶的完整账面。TRIM_MEMORY 是两套
     * 不成序的刻度（前台 RUNNING 系 5/10/15 全比后台 UI_HIDDEN=20 小，见 [GraphicsTrimPlan]
     * 的 KDoc），拿数值比大小是拿巧合当契约：旧写法 `level >= RUNNING_LOW(10)` 恰好只漏了
     * 最轻的 RUNNING_MODERATE(5)，改成逐档点名后七个系统档全收。`UI_HIDDEN` 留在
     * 回收集里：它是最可靠、时机最好的一发（切后台必发），而 native 工作表没有别的水位判据
     * 兜着 —— 回合边界那套只看 Java 堆。
     *
     * 第三层（同回调合并）：`RUNNING_CRITICAL` 再转 [GameHost.requestStrongReclaim] ——
     * 连**脚本画过的**合成缓冲都在下一回合边界无视水位与冷却地收（方法注释里有
     * 「为什么只认这一档」的完整取舍）。
     *
     * `Activity` 本身就是 `ComponentCallbacks2`，不需要 Application 子类、不需要注册。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // R3：系统内存压力事件（每档一发）。这是「被杀之前系统到底喊过什么」的唯一常开记录 ——
        // 真机 ROM 常常不发最后一次 trim 就直接杀，所以更要每一档都留下。
        EventJournal.record("oom", mapOf("level" to level.toString()))
        val freedMiB = runCatching { host?.releaseResourceImagePixels() ?: 0L }.getOrDefault(0L)
        // native 侧收口的账面读数（` bitmap=…->… view=… native=…MiB`），没有收口动作时是空串。
        var nativeReadout = ""
        // 第一层：可重建位图缓存（帧缓冲/文件位图/native 工作表）。分档口径见
        // GraphicsTrimPlan：RUNNING 系（5/10/15）与后台系（20/40/60/80）是两套不成序的
        // 刻度，旧写法 `level >= RUNNING_LOW` 跨刻度比较、漏了最轻的 RUNNING_MODERATE。
        // 查表逐档点名，未知档不收（宁可不做也不炸）。
        // 本回调的每一步都兜住：它是**系统已经在喊内存紧张**时进来的，这时候抛一次
        // RuntimeException 就是「玩着玩着直接回桌面」，而且现场没有任何 logcat 可给玩家。
        // 三步互相独立（释放 → 强收 → 落读数），一步失败不跳过后面 —— 与 `pump` 同一套纪律。
        if (GraphicsTrimPlan.dropsSheets(level)) {
            // 深浅两档的判式在 NativeTrimPlan（纯函数，app 单测直测）：前台 RUNNING 系只收
            // 行缓存与行池（玩家正看着这一屏，动整屏保留面 = 当场闪一帧空白）；后台系四档
            // 连整屏保留面与背景烘焙面一起交还（这一屏不会立刻再画，留着只是给 lmkd 多一个
            // 杀最重任务的理由）。收完的账面读数直接进下面那条轨迹行。
            val deep = NativeTrimPlan.isDeep(level)
            nativeReadout = runCatching { host?.trimGraphicsCaches(deep)?.suffix() ?: "" }
                .onFailure { Log.w(TAG, "内存压力：可重建位图缓存清理失败（继续）", it) }
                .getOrDefault("")
        }
        // 第二层：系统喊到「再不收就杀你」的那一档：把强档请求转给 VM 线程，**无视堆水位与冷却**
        // 执行（见 GameHost.drainReclaimRequests）。2026-11-02 起这一发有两个兑现点：
        // 页尾（回合边界）与**页中**的语句级检查点（midPageCheckpoint，250 ms 节流）——
        // 报告里 `trim=runningCritical freed=0MiB` 紧跟 ANR 的那份就是押在页尾等不到的形状。
        //
        // 为什么只认 RUNNING_CRITICAL（且不写成 `level >= RUNNING_CRITICAL`）：
        // - UI_HIDDEN(20) / BACKGROUND(40) / MODERATE(60) 的**数值**都在这之上，但它们是
        //   「切后台」而不是「马上要杀」—— 每次按 Home 都丢一遍脚本画过的合成缓冲，
        //   回前台就是整屏缺图；
        // - COMPLETE(80) 也排除：它在后台置位，而这一刀只有 VM 线程能安全执行，
        //   后台没有回合 ⇒ 请求会**一直挂到回前台**才兑现。那时既没救到后台的命（真被
        //   回收了就什么都没发生），又恰好在玩家看得见的时刻把图丢了。
        //
        // 为什么不在这一行直接收：正号段（G_ID 缓冲）只有 VM 线程能安全释放，
        // UI 线程直接 dispose 会让正在 GDRAWG 的脚本撞 AIOOBE（理由见 releaseUnusedGraphics）。
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            runCatching { host?.requestStrongReclaim() }
                .onFailure { Log.w(TAG, "内存压力：强档回收请求投递失败（继续）", it) }
        }
        // 为什么给这一处开 UI 线程磁盘 IO 豁免：这个回调进来的意思就是「再不收就来不及」，
        // 等下一个回合边界（VM 线程）再钉，最坏情况是根本到不了下一个边界。表与理由见
        // StrictModeGuard.uiDiskIoExemptions。
        //
        // `freed=` 只统计 **Java 堆**那一坨 `IntArray` 像素，且 UI 线程这一发只收得了负号段
        // （线程约束见 GameHost.releaseResourceImagePixels）—— 旧行到此为止，于是
        // 「freed=0」既可能是「真的无可收」也可能是「正号段与 native 还没开始收」，两种读起来
        // 一模一样。后面那截 ` bitmap=…->… view=… native=…` 就是为分开这两形而加的。
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_TRIM_TRAIL) {
                RunWatch.forceSample(
                    applicationContext,
                    "trim=${trimMemoryLevelName(level)} freed=${freedMiB}MiB$nativeReadout",
                    heartbeat,
                )
            }
        }.onFailure { Log.w(TAG, "内存压力：采样落盘失败（继续）", it) }
    }

    /** 内存压力档位的可读名（只用于轨迹阶段名，未知档如实写数字）。 */
    private fun trimMemoryLevelName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "uiHidden"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "runningModerate"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "runningLow"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "runningCritical"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "background"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "moderate"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "complete"
        else -> "0x${level.toString(16)}"
    }

    /**
     * 焦点变化时补一次沉浸式：系统栏被边缘上滑临时唤出后，失去/恢复焦点会走这里，
     * 保证回到全屏（Android 11+ 用 IMMERSIVE 行为，旧系统用 IMMERSIVE_STICKY）。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    /**
     * 打开设置页；回来时要不要重启游戏由两处决定：结果 extra
     * （[SettingsActivity.EXTRA_RESTART_GAME]）与显示大小档/守护开关/LIVE-REFLOW 开关
     * 是否被改过（见 [onActivityResult] 与 [displaySettingChanged]，三者同一条口径）。
     */
    private fun openSettings() {
        // 记录进页快照：回来时用它判断「这次访问里真的改了显示大小/守护开关」，
        // 避免玩家选择「下次启动生效」后每次返回都被重开确认拦住。
        settingsEntryFontColumns = UiPrefs.fontColumns(this)
        settingsEntryScreenFit = UiPrefs.screenFit(this)
        settingsEntryPortraitMode = UiPrefs.portraitMode(this)
        settingsEntryLiveReflow = UiPrefs.liveReflow(this)
        // 上一轮「恢复默认」若没走返回流程（玩家切走后被杀进程），一次性标记会残留到
        // 这一轮，下次返回时弹一句过期的重开询问。进页前先消费掉。
        // 恢复默认本身发生在设置页内部（recreate 不回 MainActivity），不会被这里清掉。
        UiPrefs.consumeRestoreRestartPending(this)
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
     *
     * **同一个旧会话只允许一条等待线程**：认领不到 [restartGate] 名额时原样返回（连 UI 状态都
     * 不动），判据与不挡的后果见 [RestartWaiterGate] 类头。代价如实写在这儿：被忽略的那一次若带着
     * 不同的收尾动作（字号变了要 `recreate()`），这一次就不重建视图 —— 由在办那条按**它自己的**
     * 收尾动作收场。宁可少换一次字号，也不能两份 `Program` 同住一个堆。
     *
     * UI 状态先复位再等：失败屏去重、宏组、重绘循环（[pump] 自按 [GameHost.isRunning] 续停）。
     */
    private fun restartGame(afterStop: () -> Unit = { startGameOrDemo(storage) }) {
        // 上一局可能还在收尾（`host` 已空而线程未退）：认它，别当没看见。
        val old = host ?: retiring?.takeIf { it.isRunning() }
        // W5-misc 第 1 条（双 waiter ⇒ 双宿主，完整形状见 [RestartWaiterGate] 类头）：
        // 认领不到名额 = 已经有一条 `eratw-restart` 在等**同一个**旧会话，这一路原样返回。
        // 刻意在动任何状态之前判：`host = null` / `close()` / 复位失败屏这些副作用一旦被第二次
        // 触发执行，在办那条 waiter 的现场就被搅了（比如把它的「正在重启」屏刷成别的样子）。
        if (old != null && !restartGate.begin(old)) {
            Log.w(TAG, "重启请求被忽略：已有 waiter 在等同一个旧会话（再起一条会双 afterStop ⇒ 双宿主）")
            return
        }
        if (old == null && restartGate.hasInFlight()) {
            // 旧会话**已经停了**、但它的交付（`afterStop`）还排在主线程队列里：这一刻 `host` 与
            // `retiring` 都认不出在办的那条，放这一路走「无旧会话 ⇒ 直接起新局」的分支，
            // 随后那条投递会**再**起一局 ⇒ 又是双宿主。投递紧接着就会跑，忽略这一次即可。
            Log.w(TAG, "重启请求被忽略：上一局的收尾交付还在主线程队列里（等它落定，勿起第二份宿主）")
            return
        }
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
        shell.failureCanExitApp = false
        shell.failureNote = null
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        syncLoading(copy.restartingLine, null, null)
        consoleView.detachFeed()
        reloadMacros()
        refreshShell()
        consoleView.submitSnapshot(
            listOf(ScreenRows.textLine(copy.restartingLine)),
        )
        if (old == null) {
            retiring = null
            afterStop()
            return
        }
        retiring = old
        val waiter = Thread({
            try {
                awaitOldSessionThenStart(old, afterStop)
            } catch (t: Throwable) {
                // 这条线程是「按新配置重启」的唯一推进者：它一死，屏幕就永久停在
                // 「正在按新配置重启游戏…」，没有任何按钮（玩家只能杀进程）。
                // 名额当场放开（这条路没能走到投递体，不放开就再也重启不动了）。
                restartGate.release(old)
                onRestartFault(t)
            }
        }, "eratw-restart")
        try {
            waiter.start()
        } catch (t: Throwable) {
            // 线程根本没起来（`OutOfMemoryError: unable to create native thread` 这类）：
            // 没有投递体来放开名额，只能在这里放，否则这一局之后所有重启都被这道闸挡死。
            restartGate.release(old)
            onRestartFault(t)
        }
    }

    /**
     * 换局（设置页改档位 / 导入存档后重启）失败的唯一出口。
     *
     * 与 [onStartupFault] 同一套纪律：上屏由 UI 线程做，`destroyed` 时静默收场。
     * 用 `messageOverride` 而不是 `host?.failure` —— 这条路上宿主可能已经 close，
     * `failure` 是空的，失败屏会直接不出现。
     */
    private fun onRestartFault(t: Throwable) {
        Log.e(TAG, "重启流程异常：改走失败屏（否则屏幕停在没有出口的加载页）", t)
        // 同 [onStartupFault]：走 Activity 的主线程队列，不依赖视图是否还附着。
        runOnUiThread {
            if (destroyed || isFinishing) return@runOnUiThread
            ticking = false
            val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
            renderFailureIfNeeded(
                force = true,
                messageOverride = copy.fill(
                    copy.restartFailedFormat,
                    t.javaClass.simpleName,
                    t.message?.takeIf { it.isNotBlank() }?.let { copy.detailSeparator + it } ?: "",
                ),
            )
        }
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
                    listOf(ScreenRows.textLine(ShellFailureCopy.of(UiPrefs.settingsLanguage(this@MainActivity)).oldSessionWaiting)),
                )
            }
            waitUntilStopped(old, RESTART_HARD_WAIT_MS - RESTART_JOIN_TIMEOUT_MS)
        }
        // 换局的最后一步同样走 Activity 的主线程队列（不用 `consoleView.post`）：
        // 视图此刻若刚好 detach，View 的 RunQueue 要等一次「再附着」才跑，而这条路径可能
        // 再也不附着 —— 表现就是屏幕永远停在「正在按新配置重启游戏…」。
        runOnUiThread {
            // waiter 名额在**这里**放开，而不是在 waiter 线程的 `finally`：投递已经进了主线程
            // 队列但还没跑的那一拍，`host` 与 `retiring` 都认不出「在办的是谁」，提前放开等于给
            // 第二路开了门（双宿主判据见 [RestartWaiterGate] 类头的第二道闸）。
            restartGate.release(old)
            if (destroyed) return@runOnUiThread
            try {
                if (old.isRunning()) {
                    renderRestartStuck()
                } else {
                    retiring = null
                    afterStop()
                }
            } catch (t: Throwable) {
                onRestartFault(t)
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

    /**
     * 等不到旧会话退出：说清卡在哪，并**给出离开这一屏的那颗按钮**。
     *
     * 「重试」按钮仍走 [restartGame]，那里会认出在收尾的旧会话；但这一屏的等不到就是等不到，
     * 所以 [failureActions] 在这里恒点亮「结束应用」（旧版只把「请结束本应用进程后重开」
     * 写在字里，屏上却没有对应按钮 —— 2026-10-30 玩家反馈「点过重试，但是重复报错了」的
     * 其中一圈就是这个形状：120 秒等待 → 同一屏 → 只有重试 → 再等 120 秒）。
     */
    private fun renderRestartStuck() {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        shell.loading = false
        shell.chips = emptyList()
        shell.failure = copy.fill(copy.restartStuckFormat, RESTART_HARD_WAIT_MS / 1000)
        val actions = failureActions(
            repeated = false,
            restartStuck = true,
            canBackToTitle = false,
            canRetryCompat = false,
        )
        shell.failureReportReady = false
        shell.failureCanBackToTitle = actions.canBackToTitle
        shell.failureCanRetryCompat = actions.canRetryCompat
        shell.failureCanExitApp = actions.canExitApp
        shell.failureExitIsRestart = actions.exitIsRestart
        shell.failureNote = actions.note
        refreshShell()
        consoleView.submitSnapshot(
            listOf(
                ScreenRows.line(ScreenRows.run(copy.restartStuckLabel, fore = 0xFF6060, styleMask = 1)),
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
     *
     * `@SuppressLint("RestrictedApi")`：`ComponentActivity` 把 `dispatchKeyEvent` 标成了
     * `@RestrictTo(LIBRARY_GROUP_PREFIX)`（它要为 `OnBackPressedDispatcher` 收口返回键），
     * 所以 lint 把 `super.dispatchKeyEvent(...)` 报成跨库调用。这里必须走 `super` ——
     * 不调就等于把返回键/音量键/媒体键的整条分发链掐断（本方法只做「观察 + 吞残留 KeyUp」，
     * 其余事件原样交回）。这是**宿主覆写系统回调再委托给父类**的常规形态，不是越界调用。
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 按键 → 虚拟键沿变 / 键宏 / 输入条，整条链在主线程上（同触摸回调的纪律）：
        // 一次未捕获异常等于玩家按一个键就闪退。吞掉的是「这次按键的宿主侧处理」，
        // `super` 的返回语义不受影响（返回键/音量键照常交给系统）。
        try {
            host?.dispatchKey(event.keyCode, down = event.action != KeyEvent.ACTION_UP)
        } catch (t: RuntimeException) {
            logUiFault("按键", t)
        }
        val enter = event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (enter && event.action == KeyEvent.ACTION_UP && !shell.inputFocused) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * 回到前台：撤掉待触发的后台暂停，并把已经冻结的会话放行。
     *
     * Activity 重建也走这里：新实例的 `host` 在解包/装载期还是 null，[GameHost.setBackgroundPaused]
     * 会把「不暂停」记成意图，会话装好后应用——旧实例的会话已在 `onDestroy` 里 close，
     * 暂停态不会跨实例残留。
     */
    override fun onStart() {
        super.onStart()
        // 回前台 = 重新认领成 running：否则「切后台 → 回来 → 在屏前死掉」会被下一次冷启
        // 误当成后台回收，那一类死就再也采不到（漏报）。
        // UI 线程磁盘 IO 的裁定（obs-uiio 车道）：**甲**（与 onStop / onDestroy / 结束应用同用
        // 「生命周期状态落盘」这一条）。state.txt 是一行状态机，四种死法全靠这几发落盘的先后；
        // 异步 handoff 要么丢行、要么让 background 与 running 抢同一份文件。
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_LIFECYCLE_STATE) {
                RunWatch.markForeground(applicationContext)
            }
        }
        consoleView.removeCallbacks(backgroundPauseRunnable)
        uiStage("回前台放行会话") { host?.setBackgroundPaused(false) }
    }

    /**
     * 切后台：**30 秒后**才暂停（理由见 [backgroundPauseRunnable]）。
     *
     * 不在这里直接暂停：设置页短暂覆盖、通知栏下拉也会走到这里，立即冻结会让玩家
     * 回来时看到「游戏卡了一下」。回前台由 [onStart] 摘回调，销毁由 [onDestroy] 摘。
     */
    override fun onStop() {
        // 界面已不可见 ⇒ 记成 `background`：从这里往后系统怎么回收（lmkd / OEM 清理 /
        // 玩家划掉最近任务）都是正常管控，下一次冷启不再弹「上次异常退出」。
        // 2026-10-30 装机复看：vivo 真机的退出记录全是后台发出的 FORCE STOP ⇒ 旧口径下
        // 状态永远停在 `running`，玩家每次打开都看见一次那张卡。
        // 配置变更（旋转/字号重建）不写：那一跳紧接着就是同进程的新实例，写 `background`
        // 只会把「新实例已在前台」的状态又改回后台。
        if (!isChangingConfigurations) {
            // 裁定同 onStart（「生命周期状态落盘」，甲）：这一发从落下起，系统再怎么回收都只算正常管控；
            // 晚一步就可能来不及落 —— 玩家每次打开都看见一次「上次异常退出」的那张卡就是这么来的。
            runCatching {
                EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_LIFECYCLE_STATE) {
                    RunWatch.markBackground(applicationContext)
                }
            }
        }
        consoleView.postDelayed(backgroundPauseRunnable, BACKGROUND_PAUSE_DELAY_MS)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // 设置页可能改过 USE KEYBOARD MACROS；macro.txt 也可能被外部替换。
        if (::storage.isInitialized) reloadMacros()
        // 设置页也可能改过「选项按钮大小」：纯壳层几何，不需要重开会话，
        // 回前台读一次镜像到 shell 状态，右栏下一次重组就是新尺寸。
        shell.railButtonSize = UiPrefs.railButtonSize(this)
        // 音频焦点在这里唯一一次重新申请（后台期间已被 onPause 放弃）。这一步要过
        // AudioManager（真机上有 OEM 抛 IllegalStateException 的形态），抛出去就是
        // 「切回前台，应用没了」—— 而重绘循环还没排上，屏也醒不过来。
        uiStage("回前台恢复会话") { host?.onResume() }
        // onPause 摘掉了重绘循环；回前台恢复唯一一条。这里**无条件**投一次：后台期间装载失败
        // 或脚本结束时 isRunning() 已是 false，若按它跳过，pump 就再也不会跑，
        // renderFailureIfNeeded 不上屏，画面会永远停在最后一张「正在装载…」快照。
        // pump 自己按 isRunning() 决定是否续转：未运行时只跑一帧就停，不会空转。
        // （B3）先翻 pumping 再 removeCallbacks + post：Choreographer 已排的帧回调摘不掉，
        // 靠 pumping 让旧的那条进来即退，保证任何时刻最多只有一条循环在排。
        pumping = true
        consoleView.removeCallbacks(pump)
        consoleView.post(pump)
        // 冻结哨兵随重绘循环一起起（成对：onPause 停、onDestroy 必达再停）。
        // 为什么放在泵起之后而不是 onCreate：解包那几十秒里泵本就不转，那时候起线程
        // 只会拿到一屏「主线程在等消息」的假现场（判据见 StallSentinel.setUiLoopExpected）。
        // 起线程失败只意味着这一路取证没有，绝不影响游戏。
        runCatching { stallSentinel.start() }
            .onFailure { Log.w(TAG, "冻结哨兵启动失败（本轮没有栈取证，游戏照常）", it) }
        // U5 P1-6：回前台补一发自动检查（四闸全开才补，判据在 UpdateUi）。冷启首帧不算回前台，
        // 冷启那一发与 startupCheckDone 口径不变；补查不重复弹窗、不重复出网。
        if (::storage.isInitialized) {
            runCatching { UpdateUi.maybeCheckOnResume(this, storage.gameRoot()) }
                .onFailure { Log.w(TAG, "回前台补查没跑起来（游戏照常）", it) }
        }
    }

    override fun onPause() {
        // 音频只在这里让出焦点；Activity 不直接碰声道（避免与 AudioManager 回调双触发）。
        resumed = false
        uiStage("切后台让出焦点") { host?.onPause() }
        // 先翻 false 再摘回调：Choreographer 已排的那条帧摘不掉，靠 pumping 让它进来即退。
        pumping = false
        consoleView.removeCallbacks(pump)
        // 哨兵与泵成对停：切后台后 UI 帧循环**本来就不转**，让它继续跑就是把正常态报成冻结。
        runCatching { stallSentinel.stop() }
            .onFailure { Log.w(TAG, "冻结哨兵停止失败（daemon 线程，不挡任何东西）", it) }
        // 轨迹钉一发：下一次冷启动若判定脏退出，最后一行是不是 `pause` 就把两种死分开 ——
        // 「玩的时候死在屏前」（死机）与「已经退到后台才被系统回收」（正常管控，不是 bug）。
        // 写盘失败（存储满 / 只读）不许变成「切后台就闪退」。
        // 为什么给这一处开 UI 线程磁盘 IO 豁免：**必须**当场写完才算数 —— 进程可能下一刻就被回收，
        // 挪到后台线程等于放弃这个时机。表与理由见 StrictModeGuard.uiDiskIoExemptions。
        uiStage(StrictModeGuard.WHERE_PAUSE_TRAIL) {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_PAUSE_TRAIL) {
                RunWatch.forceSample(applicationContext, "pause", heartbeat)
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        // 走到了已知收尾点 ⇒ 本进程的死因不再是「无人认领」。放在最前面：下面任何一步
        // （close 打断线程、DevToolsHost.stop 的 join）出事，都不该让下一次冷启动误报一次
        // 「上次异常退出」。
        // UI 线程磁盘 IO 的裁定：**甲**（与 onStart / onStop / 结束应用同用「生命周期状态落盘」那一条）：
        // 这一发落在盘上才算「死因有人认领」，异步 handoff 会连这一行一起丢。
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_LIFECYCLE_STATE) {
                RunWatch.markStopped(applicationContext, "onDestroy")
            }
        }
        ticking = false
        pumping = false
        // 冻结哨兵的**必达**收口（onPause 通常已经停过，这里兜「没走 onPause 直接销毁」的形态：
        // 配置变更重建、系统直接 finish）。不停 = 一条 daemon 线程攥着本壳的 heartbeat 与
        // applicationContext 继续每 500 ms 醒一次，而那时 UI 循环已经没了 —— 假现场 + 泄漏边。
        runCatching { stallSentinel.stop() }
            .onFailure { Log.e(TAG, "onDestroy: 冻结哨兵停止抛异常（照常往下收口）", it) }
        // 冻结现场读数口：认主摘除，口径与 CrashForensics 的 crashHostProvider 那一对逐字相同 ——
        // 旧壳无权把新壳刚装的读数口撬空（真机双壳交接窗口实测存在）。
        if (stallSceneOwner === this) {
            stallSceneProvider = null
            stallSceneOwner = null
        }
        // 先摘待触发的后台暂停，再走既有收口：销毁后绝不能再有回调去打已关闭的宿主。
        consoleView.removeCallbacks(backgroundPauseRunnable)
        consoleView.removeCallbacks(pump)
        // 崩溃读数口持有的是本 Activity 的闭包；不在这里摘掉，静态边会把已销毁的壳钉住
        // （下一次 onCreate 才会覆盖，若不再重建就永久钉住）。只回收**自己**那一份：
        // 两个实例交接时旧壳的 onDestroy 不得把新壳刚装好的读数口清掉。
        CrashForensics.uninstallHostProvider(this)
        // 每一步都**不得**把 `super.onDestroy()` 挡在后面（2026-10-31 复查）。
        // 框架是在 `super.onDestroy()` 之后才把这条 ActivityClientRecord 从
        // `ActivityThread.mActivities` 里摘掉的；上面任何一步抛出去，摘除就永远不发生 ——
        // 表现正是真机量到的「旧壳不回收」（`Activities: 2`）与「上一局没让出堆」的启动失败。
        // 所以两条可能抛的收口都单独兜住：异常照打日志，控制流一定走到下面。
        runCatching { host?.close() }
            .onFailure { Log.e(TAG, "onDestroy: host.close 抛异常（照常往下收口）", it) }
        // 热更通道装的是闭包（捕获本 Activity，fix-staticpin 阶段一实测：捕获槽反射可见、
        // 接线期间 WeakReference 收不回），销毁时必须摘掉，否则进程级单例攥着一个已销毁的壳。
        // 认主卸载：真机双壳交接窗口（旧壳的 onDestroy 跑在新壳接线之后）里，旧壳无权把
        // 新壳刚装好的通道撬空（否则热更键静默退化成「只落盘」）。
        HotConfigChannel.uninstall(this)
        // 会话读数口与热更通道同寿命：先卸载再放掉 host 引用，设置页读到的要么是
        // 活会话要么是空，不会是已 close 的旧会话。
        ModImportSessionChannel.install(null)
        // CMP-17：调试通道是进程级单代槽，销毁时**必须**真复位（interrupt + join + 清引用）。
        // 顺序放在 host.close() 之后：轮询线程可能还在执行一条命令（它读 host/view 的
        // volatile 快照口），让会话先收口再打断，命令读到的就是「已关闭」而不是半关状态。
        runCatching { DevToolsHost.stop() }
            .onFailure { Log.e(TAG, "onDestroy: DevToolsHost.stop 抛异常（照常往下收口）", it) }
        // 全部收口做完，再放掉壳上的两个重引用（2026-10-31 真机读数）：
        // 真机「返回键退出 → 再点图标进来」会在同一进程里留下第二个 `MainActivity`
        // （`dumpsys meminfo -a` 读到 `Activities: 2 / Views: 26`，干净进程 `1 / 15`），
        // 旧壳连同那份 215 MiB 会话常驻一起收不回来，下一次装载就被 `GameHost` 的
        // 「上一局没让出堆」兜底闸拦成「启动失败：请结束本应用进程后重开」——**每次重试
        // 都还是 215/216 MiB**，不杀进程永远进不去。
        //
        // 壳为什么没被回收是另一条线（未定性，记在 BOARD）；这里只保证「壳还活着」不再
        // 等于「整局会话还活着」：[GameHost.close] 已放掉会话/图形桥/行缓冲，`host = null`
        // 断掉最后一条到宿主的边，[CanvasConsoleView.releaseSession] 再放掉视图侧那份
        // 行缓冲与离屏表面。三处都是 UI 线程上的纯赋值，不碰任何还在跑的东西
        // （此刻 `destroyed` 已置位、重绘回调已摘、调试通道已 join 收口）。
        host = null
        consoleView.releaseSession()
        super.onDestroy()
    }

    /**
     * 失败覆盖层的「重试」：关掉当前宿主后重开一局（等价于设置页的「保存并重启游戏」，
     * 但不清配置）。真机此前失败后只能杀进程重进，这条把重试变成一次点击。
     *
     * 顺手记一笔「玩家按过重试」（[retriedAfterFailure]）：这一条路再撞回失败屏时，
     * 屏上必须多出「结束应用」，不能只把同一张「只有重试」的屏再刷一遍。
     */
    private fun retryLoad() {
        retriedAfterFailure = true
        shell.failure = null
        restartGame()
    }

    /**
     * 失败屏的「少占内存再试」（2026-10-28 低端机 OOM；2026-11 由玩家按或乙族自动降档共用）：
     * 换省内存装载档重开一局。
     *
     * 一旦进了省内存档，本次 Activity 会话的后续装载（包括设置页重启）都用它：
     * 内存不够是机型档位问题，用户不会想每次失败都再选一遍；成功后也不自动退回 ——
     * 换新机再装新包才是退出这一档的途径。
     *
     * 两条进来路：
     * - **玩家按的**（两族都可能）：甲族（预检就判装不下）只走这条 —— 那一次尝试可能仍然
     *   装不下（屏上明说），风险由知情的玩家承担，不替她自动决定（见 [HeapFailureStage]）；
     * - **乙族自动降一次**（[renderFailureIfNeeded] 里判，只此一条）。
     *
     * 与 [retryLoad] 同样记「按过重试」：换档也是重试，它失败时玩家同样需要出口。
     */
    private fun retryLoadCompat() {
        compatLoad = true
        retriedAfterFailure = true
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
        // 复活成功 = 这一圈到此为止，「按过重试」的账清掉（见 [retriedAfterFailure]）。
        retriedAfterFailure = false
        shell.failure = null
        shell.failureCanBackToTitle = false
        // 失败后 pump 不再自续（`isRunning()` 当时为假）；新 VM 线程已起，重挂唯一一条重绘循环。
        pumping = true
        consoleView.removeCallbacks(pump)
        consoleView.post(pump)
    }

    /**
     * 失败屏的「结束应用」= 把这一屏原来只写在字里的自救路径（结束进程后重开）做成一颗按钮。
     *
     * 为什么必须硬退而不是 `finish()` 就交差：这一屏出现的场合，进程里往往还挂着一条
     * **不肯让出堆的旧 VM 线程**（[renderRestartStuck] 就是为它而设）或一份停在失败终局的
     * 会话；`finish()` 只拆 Activity，Android 对「还有活的非守护线程」的进程不保证回收，
     * 玩家照字面去结束应用、下一次进来仍是同一份堆。硬退才是那句话的字面意思。
     *
     * 上报不会被这一刀切断：报告在失败当场就已落盘，发送是 daemon 线程 —— 没发出去的
     * 那几份由下一次冷启动的 `ErrorReportUploader.uploadPendingAsync` 补（`.uploaded`
     * 索引只记成功，所以补发不会重发已送达的）。
     */
    private fun exitApplication() {
        // 玩家主动离开 = 已认领的死因，别让它下一次冷启被当成「异常退出」再报一份。
        // 裁定：**甲**（「生命周期状态落盘」），而且这一发是全套里最硬的一条 —— 它后面紧跟
        // `Runtime.getRuntime().exit(0)`（见本方法末尾）：挪到后台线程等于这一行**必然**写不上，
        // 于是每次玩家主动「结束应用」都会被下一次冷启动弹一张「上次异常退出」。
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_LIFECYCLE_STATE) {
                RunWatch.markStopped(applicationContext, "exitButton")
            }
        }
        // 「重启应用」那一族（上一局没让出堆）：它要的就是**换一个干净进程**，所以先把
        // 「拉回来」这一步交给系统（AlarmManager 持有 PendingIntent，本进程死掉也照发），
        // 再走既有退出。安排失败只落一条日志 —— 退化成原来的「结束应用」，玩家仍可手动重开。
        if (shell.failureExitIsRestart) scheduleRelaunch()
        runCatching { host?.close() }
        // `finishAndRemoveTask` 也会抛（任务已被系统移除 / RemoteException）。它挡在
        // `exit(0)` 前面：抛出去就等于「玩家点了结束应用，结果停在半死的界面上」。
        runCatching { finishAndRemoveTask() }
            .onFailure { Log.w(TAG, "结束应用：finishAndRemoveTask 失败，仍按退出处理", it) }
        Runtime.getRuntime().exit(0)
    }

    /**
     * 安排「本进程退出后把自己拉回来」（只服务失败屏那颗「重启应用」，2026-10-31）。
     *
     * 为什么不是 `startActivity` 再杀进程：那条路把「启动新 Activity」的请求交给**本进程**的
     * `ActivityThread` 处理，而它马上就要被杀 —— 请求随进程一起消失。交给 `AlarmManager`
     * 才活得比本进程久：`PendingIntent` 由系统持有，到点由 system_server 发起启动。
     *
     * 用非精确 `set`（不是 `setExact`）：精确闹钟在 API 31+ 要 `SCHEDULE_EXACT_ALARM`
     * 用户授权，为一个失败屏去要权限不值得；设备在使用中（玩家刚点完按钮）非精确闹钟
     * 通常就是几秒内到点，而 Doze 里晚一点也无所谓 —— 那时玩家已经离开屏幕了。
     *
     * 全程 `runCatching`：这一步失败**不能**把退出流程挡住（`Runtime.exit` 必须照常执行），
     * 否则就变成「点了重启既没重启也没退出」的死屏。
     */
    private fun scheduleRelaunch() {
        runCatching {
            val ctx = applicationContext
            val intent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            // API 31+ 要求显式可变性；23~30 只有 FLAG_IMMUTABLE，21~22 两者都没有。
            val mutability = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }
            val pi = PendingIntent.getActivity(
                ctx,
                RELAUNCH_REQUEST_CODE,
                intent,
                mutability or PendingIntent.FLAG_CANCEL_CURRENT,
            )
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am == null) {
                Log.w(TAG, "重启应用：拿不到 AlarmManager，按普通退出处理")
                return@runCatching
            }
            am.set(AlarmManager.RTC, System.currentTimeMillis() + RELAUNCH_DELAY_MS, pi)
            Log.i(TAG, "重启应用：已安排 ${RELAUNCH_DELAY_MS}ms 后重新拉起本应用")
        }.onFailure {
            Log.w(TAG, "重启应用：安排失败，按普通退出处理（玩家仍可手动重开）", it)
        }
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
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        shell.progress = progress?.let { p ->
            if (p.total > 0) (p.copied.toFloat() / p.total.toFloat()).coerceIn(0f, 1f) else null
        }
        shell.progressText = when {
            progress != null -> copy.fill(copy.progressUnpackedFormat, progress.copied, progress.total, progress.label)
            landed != null -> copy.fill(copy.progressLandedFormat, landed.summary(copy))
            else -> copy.progressCounting
        }
    }

    private fun startGameOrDemo(storage: GameStorage) {
        // 这里是**每一次开局**的入口（首启 / 失败重试 / 兼容档重试 / 设置页重启全汇到这一处，
        // 由首次布局回调在 UI 线程上触发）。整段兜住：宿主构造、游戏根解析、首屏快照任何
        // 一步抛出，都该落到失败屏而不是把进程带走。
        var candidate: GameHost? = null
        try {
            val host = GameHost(
                storage,
                consoleView,
                // 失败屏选过「兼容模式重试」之后，本会话的后续装载都用兼容档（见 retryLoadCompat）。
                compatLoad = compatLoad,
                // 设备归因：release 包走启动 Intent extra（debug 包仍可用 files 开关），null = 默认关。
                flatStatementsOverride = flatStatementsOverride,
                // LIVE-REFLOW：把 [appliedLiveReflow] **原样**交出去（不是再读一次 UiPrefs）——
                // 它最终落到 `GameSession(reflowConsole = …)`，必须与上面构造 consoleView 时的
                // `liveReflow` 同一个值，否则「视口问、核心拒」空转（见该字段 KDoc）。
                liveReflow = appliedLiveReflow,
                // 标题彩蛋屏蔽：读数在 **UI 线程**这一拍取好、原样交给宿主（装配期在 VM 线程上，
                // 那里不许直接读 SharedPreferences；照 liveReflow / failureCopy 的先例）。
                disableBadApple = UiPrefs.disableBadApple(this),
                // ECHO-INPUT：同一条线程纪律 —— 装配期在 VM 线程上，读数在这里（UI 线程）取好。
                echoInput = UiPrefs.echoInput(this),
                // 失败面文案：构造期（UI 线程）一次取好，VM/装载线程的 failure 写点只吃这份
                // 快照（照 ModImportController「起线程前一次取好进线程」的口径）。
                failureCopy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this)),
            ).also { candidate = it }
            val gameRoot = storage.gameRoot()
            // 随包形态（M10-PKG400）：APK 里带 erbc.v1 + CSV/resources/sound/plugins + emuera.config，
            // 首启要解到游戏根 —— 全 STORED 口径下是 679.9 MiB、四千个条目，量级是「几十秒到几分钟」，
            // 所以这块屏要自己报进度：先只读数一遍 assets 清单（不写盘、不碰解包语义），
            // 再每 800 ms 数一遍已落盘的目标文件，把「正在解包 / 已完成 / 失败原因」摊在屏上。
            // 「清点 + 可用性判定 + 解包」全部在后台线程跑，UI 线程只收快照（`b076990` 的 ANR 口径）；
            // 清点出数之前先挂占位行（随包读数「(盘点中…)」），首屏不至于黑着干等。
            val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
            consoleView.submitSnapshot(
                startupLines(host.artifactDir(), gameRoot, null, null, copy.checkingApkLine),
            )
            syncLoading(copy.checkingDataLine, null, null)
            ticking = true
            val worker = Thread({
                // 随包数据版本（2026-11-06 主人反馈）：APK 自带完整游戏数据时，盘上数据的版本就是
                // 本包的 versionCode（口径与取舍见 UpdateManager.seedBundledDataVersion）。这一拍
                // 必须早于 12 秒后的自动更新检查，否则首启解包还没跑完时，检查会把「盘上数据 0 版」
                // 当成真实的数据更新 —— 玩家装完最新整包仍被弹更新窗。磁盘 IO 不上 UI 线程。
                seedBundledDataVersionOnStartup(storage)
                // 跨 Activity 单飞：上一代解包线程还在跑就先等它（见 [claimUnpackSlot] 的 KDoc）。
                claimUnpackSlot()
                try {
                    unpackAndStart(host, gameRoot, storage, consoleView)
                } catch (t: Throwable) {
                    // 这条线程是玩家**第一次启动**必经的一条。旧写法线程体没有顶层 catch：
                    // 清点阶段（`bundledInventory` 的递归 `AssetManager.listDir`、
                    // `landedInventory` 的整树 walk、空间预检的 `getUsableSpace`）任一步抛出，
                    // 线程就带着异常死掉 —— 玩家看到的是「装上打不开」，连失败屏都来不及画。
                    onStartupFault(host, t)
                } finally {
                    releaseUnpackSlot()
                }
            }, "eratw-assets")
            try {
                worker.start()
            } catch (t: Throwable) {
                // 起不到线程（极端 OOM / 线程数上限）：与线程体里抛是同一张屏。
                onStartupFault(host, t)
            }
        } catch (t: Throwable) {
            onStartupFault(candidate, t)
        }
    }

    /**
     * 随包数据版本（2026-11-06 主人反馈）：把盘上数据版本抬到当前整包的 versionCode。
     *
     * 为什么在启动线程最前段做：`update/data-version.txt` 以前只由更新通道的数据包写入，APK
     * 随包解包时一个字节都不写 ⇒ 装完最新整包，盘上仍读成「数据 0 版 / 旧数」；清单的
     * assets-version 缺省对齐到整包 versionCode（emit_update.py:2514-2516），于是每次检查都
     * 判成有数据更新。这里必须在 12 秒后的自动更新检查之前落读数（见 [startGameOrDemo] 里的
     * `scheduleStartupCheck`），所以放在 `eratw-assets` 线程最前段，顺带避开 UI 线程磁盘 IO。
     *
     * 判据用 CSV：它是随包目录的第一项（`BundledAssets.kt` 的 `stagedDataDirs`）；`--skip-assets`
     * 小包一个条目都没有，不能凭空声明数据版本 —— 那种包的数据确实可能比整包旧，更新提示是真的。
     */
    private fun seedBundledDataVersionOnStartup(storage: GameStorage) {
        runCatching {
            if (storage.assetTree().listDir("CSV").isNotEmpty()) {
                UpdateManager.seedBundledDataVersion(
                    storage.gameRoot(),
                    UpdateManager.currentVersionCode(this),
                )
            }
        }.onFailure { Log.w(TAG, "随包数据版本标记没写成（只影响更新通道的数据判定）", it) }
    }

    /**
     * 开局流程里抛出的任何 Throwable 的唯一出口（[startGameOrDemo] 与 `eratw-assets` 线程共用）。
     *
     * 两件事必须做对：
     * - **只关还没被接管的宿主**。`host === candidate` 说明 `unpackAndStart` 已经把会话交给
     *   界面并 `start()` 成功，这时 close 会把正在跑的一局打死 —— 那种情况下真正要做的是
     *   把重绘循环重新排上，而不是报一张文案对不上的失败屏；
     * - Activity 已销毁时安全跳过（解包几十秒里玩家完全可能按返回），candidate 就地关掉，
     *   否则这条线程持有的半装配宿主会连同它的存储引用一直活着。
     */
    private fun onStartupFault(candidate: GameHost?, t: Throwable) {
        ticking = false
        Log.e(TAG, "启动流程异常：本次放弃，交给失败屏", t)
        // 用 `runOnUiThread` 而不是 `consoleView.post`：后者在视图已 detach 时会把 Runnable
        // 压进 View 自己的 RunQueue，等一次「再附着」才跑 —— 而这条路径上可能再也不附着，
        // 于是 `candidate.close()` 永远不执行，半装配宿主连同存储引用一直挂到进程结束。
        runOnUiThread {
            ticking = false
            val adopted = candidate != null && host === candidate
            when (startupFaultAction(adopted = adopted, destroyed = destroyed || isFinishing)) {
                StartupFaultAction.RESUME_PUMP -> {
                    // 已经交给界面的一局：异常发生在交接之后（例如排重绘循环那一步失败）。
                    // 不打失败屏 —— 那会把「界面已经能画」的一局说成「启动失败」；重排循环即可。
                    Log.w(TAG, "启动流程异常：会话已接管，仅重排重绘循环")
                    if (pumping) consoleView.post(pump)
                }
                StartupFaultAction.CLOSE_SILENTLY -> {
                    runCatching { candidate?.close() }
                }
                StartupFaultAction.CLOSE_AND_FAIL -> {
                    runCatching { candidate?.close() }
                    renderFailureIfNeeded(force = true, messageOverride = startupFaultMessage(t))
                }
            }
        }
    }

    /** 启动期异常的玩家面文案：一句原因 + 一句「存档不会丢」 + 一句下一步。 */
    private fun startupFaultMessage(t: Throwable): String {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        return copy.fill(
            copy.startupFaultFormat,
            t.javaClass.simpleName,
            t.message?.takeIf { it.isNotBlank() }?.let { copy.detailSeparator + it } ?: "",
        )
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
            // B4：两份清点都在这条后台线程、解包开始**之前**做 —— landedInventory 要全盘
            // walk，放 UI 线程冷启会卡首帧乃至 ANR。随包清点先试「随包清单缓存」：assets 在
            // 一次安装内不可变，命中缓存就不必再递归整棵 APK（数千次 AssetManager JNI）；
            // 缓存缺失/损坏/换包时回退整树扫描（见 [bundleInventoryStamp] 的失效语义）。
            // landedBefore 是「本次有没有真解包」的唯一判据，必须是解包**前**的实测读数：
            // 它和 expected 一起在 artifactsAvailable()（判定 + 解包的入口）之前取好，
            // 同一条线程天然保序，再往下传给最终文案，不破坏 ticker「解包中不数盘」的口径。
            val stamp = bundleInventoryStamp()
            val cached = readCachedBundleInventory(stamp)
            val expected = cached ?: bundledInventory(storage.assetTree()).also {
                writeCachedBundleInventory(stamp, it)
            }
            val landedBefore = landedInventory(gameRoot)
            runOnUiThread {
                if (!ticking || destroyed || isFinishing) return@runOnUiThread
                val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
                consoleView.submitSnapshot(
                    startupLines(candidate.artifactDir(), gameRoot, expected, null, copy.checkingApkLine),
                )
                syncLoading(copy.checkingDataLine, null, null)
            }
            // 解包前空间预检：只按「还差多少」算，不能拿全量去比 —— 数据已装好的第二次
            // 启动若盘上剩余空间小于全量（常见于 512 MiB 机型），按全量查会误报、
            // 让本来能玩的机器起不来。expected/landed 都是叶子文件字节数，差值就是本次
            // 最多还要落盘的量；差值 ≤ 0 表示已齐，连 64 MiB 余量都不该再要求。
            // 盘不够就在这里停 —— 进了 artifactsAvailable() 就是逐文件拷贝，写到一半 ENOSPC
            // 只会在盘上留下半份产物 + 一条含糊的解包异常。检查留在本线程（eratw-assets）。
            val missingBytes = (expected.bytes - landedBefore.bytes).coerceAtLeast(0L)
            if (missingBytes > 0L && !GameAssetInstaller.hasSpaceFor(gameRoot, missingBytes)) {
                // 向上取整：需求 0.1 MiB 也不能说成「0 MiB」。
                val requiredMiB = (missingBytes + MIB_BYTES - 1) / MIB_BYTES
                runOnUiThread {
                    ticking = false
                    if (destroyed || isFinishing) {
                        runCatching { candidate.close() }
                        host = null
                        return@runOnUiThread
                    }
                    // 与其它启动失败同一张屏（renderFailureIfNeeded 的出口、同一套按钮语义）；
                    // 本次不建会话，candidate 就地 close。重试会再走一遍这条路，空间没清出来
                    // 就还是这张屏（重复失败由失败屏自己给「结束应用」出口）。
                    runCatching { candidate.close() }
                    val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
                    renderFailureIfNeeded(
                        force = true,
                        messageOverride = copy.fill(copy.spaceShortFormat, requiredMiB),
                    )
                }
                return
            }
            val ticker = Thread({
                while (ticking) {
                    // 解包**正在跑**时不要去数盘：那会给同一个存储再加一路随机小 I/O，还会和
                    // 解包器抢同一批目录项。解包器自己就有 `copied/total`，读它零成本。
                    val progress = candidate.installProgress()
                    // 数盘本身也会抛（`File.walkTopDown` 遇到不可读目录抛 AccessDeniedException、
                    // 底层 IO 错抛 UncheckedIOException）。这条 ticker 线程没有别的守卫，
                    // 抛一次就是进程死 —— 而它跑的正是「玩家第一次启动」那段。数不出来就退成
                    // null（屏上少一行盘上读数），解包照旧往下走。
                    val landed = if (progress == null) {
                        runCatching { landedInventory(gameRoot) }.getOrNull()
                    } else {
                        null
                    }
                    runOnUiThread {
                        if (!ticking || destroyed || isFinishing) return@runOnUiThread
                        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
                        // 首启解包是玩家对新包的第一印象，这里崩一次就是「装上打不开」。
                        // 进度刷新只是读数（拼屏 + 同步加载卡），失败不该影响解包本身，
                        // 所以兜住继续 —— 下一 tick 还会再刷一遍。
                        try {
                            consoleView.submitSnapshot(
                                startupLines(
                                    candidate.artifactDir(),
                                    gameRoot,
                                    expected,
                                    landed,
                                    copy.unpackingApkLine,
                                    progress,
                                ),
                            )
                            syncLoading(copy.unpackingDataLine, progress, landed)
                        } catch (t: RuntimeException) {
                            logUiFault("解包进度", t)
                        }
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
                    // 演示屏走 submitSnapshot（无生产桥），首帧回调不会（也不该）触发。
                    consoleView.onFirstContentFrame = null
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
                candidate.heartbeat = heartbeat
                // 设置页「热更键」的投递口（M5-CONFIG 的运行时重载口径）：设置页读盘那份
                // `SettingsModel.store` 与会话那份条目表是两份实例，只能靠这条通道推过去。
                // 这里装的是**活引用**闭包（`host?.`，捕获本壳；fix-staticpin：接线带宿主身份），
                // 所以重启/关会话后 `host` 变 null 就自动退化成「只落盘、下次进游戏生效」，
                // 不需要在每个收尾分支重复摘除；唯一的卸载点是 onDestroy（认主，见那里）。
                HotConfigChannel.install(this) { host?.liveConfigStore() }
                // 补丁导入面（R7-D1 任务①起宿主在设置页）经这条通道跨 Activity 读会话侧
                // I7 四读数；与上面同一「活引用」口径：host 变 null 后 read 自动为空。
                ModImportSessionChannel.install { host?.debugSession() }
                // 解包/判定这几十秒里 Activity 可能已经切到后台：这次 onPause 走过时
                // `host` 还是 null（`onPause` 只转发现有 host），新 host 的 `activityPaused`
                // 于是停在构造值 false。补一次 onPause，装载完成时就不会在后台申请焦点
                // （GameHost 的 `if (!activityPaused) focus.start()`）；回到前台时
                // `onResume` 再正常恢复。
                if (!resumed) candidate.onPause()
                val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
                val finalTitle =
                    if (expected.bytes > 0L && !landed.bytesLeveled(expected)) {
                        // 解包器说「成功」但落盘读数追不上 APK 里的随包读数 —— 两种成因：
                        // 随包本身缺件（少 CSV/resources/sound/plugins 之一），或条目被 DEFLATE
                        // （compressed 条目拿不到 openFd 长度，见 AndroidAssetTree.fileSize）。
                        copy.fill(copy.finalTitleLagFormat, landed.summary(copy), expected.summary(copy))
                    } else if (landedBefore.entries > 0) {
                        copy.fill(copy.finalTitleCachedFormat, landed.summary(copy))
                    } else {
                        copy.fill(copy.finalTitleUnpackedFormat, landed.summary(copy))
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
                // CONFIG-WIRE U4：标题吃 LOADLABEL（原版 Process.cs:277-281 的装载等待屏
                // 那一行的 Android 壳映射，取值与回落口径见 [loadLabelTitle]）。
                syncLoading(loadLabelTitle(gameRoot, copy.loadingGameLine), null, landed)
                // 装载成功 = 重试这一圈有了结果，「按过重试」的账到此结清（见 [retriedAfterFailure]）。
                // 覆盖层**不在这里收**：4.980 之后脚本会先画「少女祈祷中」进度条，4.981 已删掉
                // 那段脚本进度条 —— 若照旧在「脚本即将开始执行」时收，4.981 会露出一段空屏。
                candidate.onConsoleReady = { retriedAfterFailure = false }
                // 收尾点改到**首帧非空内容真正落笔之后**（[CanvasConsoleView.onFirstContentFrame]）：
                // 4.980 那一刻是脚本进度条首帧（彩条照常可见），4.981 是标题页首帧 ——
                // 玩家看到的始终是「加载页 → 可见画面」，中间不露状态屏、也不黑屏。
                consoleView.onFirstContentFrame = {
                    shell.loading = false
                    // C6：首帧非空内容真的落笔 = 本次启动已经进了主循环。上一轮的崩溃摘要
                    // 到此认账（玩家要么已经看过横幅，要么这一轮根本没崩）—— 标记只在崩溃
                    // 处理器里写，这里清掉不会把「本轮早崩」抹掉（那种情况走不到这一行）。
                    clearCrashSummaryMarker()
                }
                // CBG 按钮（取色图 `CBGSETBMAPG`）命中值 → `InputResult5` 的投喂口：
                // 原版 `MouseDown` 在 `IsWaitingPrimitive` 时先取 `cbgButtonMap` 的颜色
                // （`EmueraConsole.cs:1309-1337`）。与 [pickMappedColorAt] 的行内遮罩取色
                // 是两条独立通道（值域与等待形态都不同），互不挤占。
                consoleView.onCbgButtonValue = { value -> host?.submitCbgButtonValue(value.toLong()) }
                // 取色只在 INPUTMOUSEKEY 等待期发生（原版 `MouseDown` 的 `IsWaitingPrimitive`
                // early-return，`EmueraConsole.cs:1300-1305`）；其余等待形态下 CBG 区域照常
                // 落到文本/div 命中，不能被取色路径吞掉。
                consoleView.cbgButtonArmed = {
                    host?.pendingRequest()?.form == InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY
                }
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
     * 随包清单缓存的失效键：构建标识 + 本次安装的 `PackageInfo.lastUpdateTime`。
     *
     * 失效语义：APK 安装/更新/降级都会改 `lastUpdateTime`，所以「同一次安装内缓存的
     * 随包清点永远可信」；[BuildConfig.eratwBuildStamp] 再兜一层 —— 同一个 versionCode
     * 反复出包时，安装器对「覆盖升级」的 `lastUpdateTime` 处理并不统一，构建标识变了
     * 就不该命中上一版包的读数。
     *
     * 读 `PackageInfo` 失败（`NameNotFoundException` 等）时只用构建标识；整段 runCatching
     * 兜底，本函数**不抛**（它跑在解包线程的第一步，抛出去等于首启直接失败）。
     */
    private fun bundleInventoryStamp(): String {
        val build = BuildConfig.eratwBuildStamp
        return runCatching {
            val updated = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
            "$build:$updated"
        }.getOrElse { build }
    }

    /**
     * 读随包清单缓存；文件不存在、内容损坏、stamp 不匹配都返回 null（＝回退整树扫描）。
     *
     * 缓存只替换 [bundledInventory] 的结果，`landedInventory` 仍逐次走盘，所以缓存脏了
     * 最坏是多扫一遍整棵 assets，绝不会把缺件误判成已装（那由 `artifactsAvailable` 与
     * 解包器的逐文件核对决定）。读写都在解包后台线程（[unpackAndStart]）里发生。
     */
    private fun readCachedBundleInventory(stamp: String): Inventory? = runCatching {
        val file = File(cacheDir, BUNDLE_INVENTORY_CACHE)
        if (!file.isFile) return@runCatching null
        val pair = BundleInventoryStamp.decode(file.readText(), stamp) ?: return@runCatching null
        Inventory(pair.first, pair.second)
    }.getOrNull()

    /**
     * 写随包清单缓存；失败静默。
     *
     * `cacheDir` 本来就可被系统随时清理，写不进去（盘满/被清）只意味着下次冷启再全树扫一遍；
     * 为此弹错误屏会把「缓存没写成」升级成启动失败，显然不划算。半截文件由
     * [BundleInventoryStamp.decode] 的字段数/数字校验挡下，同样退化成扫描。
     */
    private fun writeCachedBundleInventory(stamp: String, inventory: Inventory) {
        runCatching {
            File(cacheDir, BUNDLE_INVENTORY_CACHE)
                .writeText(BundleInventoryStamp.encode(stamp, inventory.entries, inventory.bytes))
        }
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

    /** 随包/已落盘读数的一对数字；`summary(copy)` 是屏上那一行原文。 */
    private data class Inventory(val entries: Int, val bytes: Long) {
        fun summary(copy: ShellFailureCopy): String =
            if (entries == 0) copy.inventoryNone else copy.fill(copy.inventorySummaryFormat, entries, bytes / (1024 * 1024))
    }

    /**
     * 一次点击执行完之后的事实（[TouchGestureController.noteTapExecuted] 的入参）。
     *
     * @param advanced 这一下是否真的交付了一次推进（按钮命中并提交 / 空白处补了空回车）；
     *   false = 什么也没交出去（输入条被这一下收起、页面还在跑、没有可点按钮、世代闸拦下）
     * @param hitButton 这一下是否落在行内按钮上
     */
    private data class TapResult(val advanced: Boolean, val hitButton: Boolean)

    /**
     * 把控制台的触摸接线到 [TouchGestureController]（点/滑分界、双击窗口与邻域、长按阈值）。
     *
     * ## 为什么不再用 GestureDetector
     * 2026-11-02 玩家反馈「如果忽视悬浮按键，需要双击才能响应」「双击快了还会卡顿」
     * 「有的时候一下就行，有的时候连点好几下都不动」。根因有两条，都在 GestureDetector
     * 的口径与本仓规约的差上：
     * 1. **它的「算不算点击」是二维 touchSlop（约 8dp）**：手指稍微横向漂一下就把这一下判成
     *    onScroll，而本仓规约是「横向位移不参与判定、纵向不足半行仍算单击」
     *    （[TouchGestureController] 类文档「行数换算」）。被判成滚动、纵向又不到半行时，
     *    下面只会拿到 `rows == 0`（不滚）—— **点击就这样静默消失**，玩家只能再点一下。
     * 2. **它的双击邻域是 DOUBLE_TAP_SLOP（约 100dp 半径）**，远大于「同一按钮内的落点抖动」：
     *    快速点两个不同按钮会被判成一次双击，第二下不是按钮命中而是「跳过」。
     * 本仓的 [TouchGestureController] 早就把这两条钉成规约（M10-PLAY 钉桩②③），
     * 但生产接线一直绕过它 —— 本方法把接线收回到它身上，手势判定只剩这一处来源。
     *
     * ## 分工
     * - 滚动：按下即清零头，逐段喂 [ScrollLineAccumulator]（慢拖攒够一行付一行，见该类的
     *   「为什么要攒零头」），付过就置 `scrolled`，抬手不再补点击。
     * - 点击/跳过：抬手时问一次 [TouchGestureController.onUp]，按结论执行
     *   （[handleConsoleTap] / [handleSkip]），并把**执行结果**回填给控制器
     *   （[TouchGestureController.noteTapExecuted]）—— 第二击要不要算跳过，取决于第一击
     *   到底有没有推进。
     * - 长按：postDelayed 到 500ms 取按钮提示（原版 PC 的 hover ToolTip）。阈值直接引用
     *   [TouchGestureController.DEFAULT_LONG_PRESS_MS]，与控制器判「长按不算单击」同源，
     *   两处不会漂移。
     */
    private fun attachGestures() {
        // 拖动行数的零头累加器（判据与出处见 ScrollLineAccumulator）：只在一次按下之内累加。
        val scrollLines = ScrollLineAccumulator()
        val touch = TouchGestureController(pixelsPerLine = consoleView.lineHeightPx().positiveOr(19f))

        var tracking = false
        var scrolled = false
        var downX = 0f
        var downY = 0f
        var lastY = 0f
        var lastX = 0f

        /**
         * 长按按钮 → 显示它的 `title`（原版 PC 的 hover ToolTip）。
         *
         * 原版鼠标指着按钮时控制台取 `ConsoleButtonString.Title` 弹 ToolTip
         * （`EmueraConsole.cs:1891-1917`，`:1909` 把 `<br>` 换成换行）。安卓没有 hover，
         * 改由长按取同一份原文交给壳层卡片；长按这一下在抬手时由控制器判成 LongPress，
         * 不再产单击，因此不会误触按钮。
         *
         * 长按**遮罩图**则取色投喂输入通道（INPUT(6/3,color) 消费面的手势半段，
         * 判定与投喂口径见 [pickMappedColorAt]）；与提示卡同闸同拍，两件事互不挤占。
         *
         * 触发条件与控制器判「长按不是点击」的分界同源：**纵向位移不足半行**才算长按
         * （横向不参与滚动判定，见 [TouchGestureController] 类文档）。但横挥要另设闸：
         * 控制器把总横移超过 2 行的手势判成「横挥不是点击」（`DEFAULT_HORIZONTAL_SLOP_LINES`），
         * 这种手势不该弹提示 —— 不设闸的话「横着按住扫出去」会在滑动中途弹出按钮提示卡，
         * 而抬手时同一手势又被控制器判成 None，两端口径打架（2026-11-02 审查修正）。
         */
        val longPressTick = Runnable {
            val linePx = consoleView.lineHeightPx()
            val halfLine = linePx / 2f
            val horizontalSlop = TouchGestureController.DEFAULT_HORIZONTAL_SLOP_LINES * linePx
            if (!shell.inputVisible && !scrolled &&
                abs(lastY - downY) < halfLine && abs(lastX - downX) < horizontalSlop
            ) {
                consoleView.tooltipAt(downX, downY)?.let { shell.tooltip = it }
                pickMappedColorAt(downX, downY)
            }
        }

        consoleView.setOnTouchListener { _, event ->
            // 触摸回调的兜底（与绘制/重绘同一条纪律）：这里往下是手势识别 → 命中区解析 →
            // `GameHost.submit` / `updatePointer` 的整条链，全部跑在主线程上。任何一处抛
            // `RuntimeException`（VM 线程刚换代时的空档、行/列越界、按钮表被清空）都会
            // 直接结束进程 —— 玩家的感受是「点一下就闪退」，且没有任何报告。
            // 吞掉这次事件（返回 true）而不是交回给 View：同一次事件再走一遍默认路径
            // 多半还是同一处数据不自洽，多试一次只会再多崩一次。
            try {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // 每次按下都是新的一拖：上一拖没付完的零头不能带过来。
                        scrollLines.reset()
                        scrolled = false
                        tracking = true
                        downX = event.x
                        downY = event.y
                        lastY = event.y
                        lastX = event.x
                        // 行高在会话期冻结（换字号要重开会话，见 CanvasConsoleView 类头），
                        // 但仍按当前值现取：任何测量缓存陈旧都不许把滑动距离算错。
                        touch.pixelsPerLine = consoleView.lineHeightPx().positiveOr(touch.pixelsPerLine)
                        touch.onDown(columnOf(event.x), event.y, event.eventTime)
                        // 按下态高亮（绘制面，不产输入）：与 tap 同一套行/列换算（`cellHitAt`）。
                        consoleView.setPointer(event.x, event.y, pressed = true, acceptance = host?.acceptance() ?: ButtonAcceptance.ANY)
                        // 触摸现场 → MouseState（MOUSEB/MOUSEX/MOUSEY）：只在按下/抬起/取消时记，
                        // 不跟 ACTION_MOVE（滚动时每个事件都换算一遍白烧帧）。原版 MouseMove 同理
                        // 只在移动/按键时写现场（EmueraConsole.cs:2328-2331）。
                        host?.updatePointer(event.x, event.y)
                        consoleView.postDelayed(
                            longPressTick,
                            TouchGestureController.DEFAULT_LONG_PRESS_MS,
                        )
                        Log.i("EratwGest", "DOWN 到达控制台 x=${event.x} y=${event.y}")
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (tracking) {
                            // 交来的是**相邻两个 MOVE 之间**的增量，不是一共滑了多少：慢速拖动
                            // 每段都不到半行高，逐段四舍五入会一路算成 0 ⇒ 手指推了半屏、画面一格不动
                            // （2026-10-29 真机「无法向上滑」）。零头交给 [ScrollLineAccumulator] 攒着，
                            // 攒够一行付一行。
                            val rows = scrollLines.add(event.y - lastY, consoleView.lineHeightPx())
                            lastY = event.y
                            lastX = event.x
                            if (rows != 0) {
                                // 付过滚动就不再是点击：长按提示取消，抬手也不补点击。
                                scrolled = true
                                consoleView.removeCallbacks(longPressTick)
                                Log.i("EratwGest", "scrollRows($rows) carryPx=${scrollLines.carry()}")
                                // 手指下移（dy > 0）= 内容跟着往下 = 把上面的旧行拉回视野（回看更早），
                                // 手指上移反之 —— 与安卓原生滚动的「跟手」一致，也与
                                // [TouchGestureController.Action.Scroll] 那一路同向。
                                // 这里**不能**取负：累加器吃的就是裸手指位移，读数已与
                                // `scrollRows` 同号（2026-11-02 那版留着的老取负把方向整条翻反了）。
                                consoleView.scrollRows(rows)
                            }
                        }
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                        // 多指不建模：整体取消，避免把两指中任意一指的位置当成单击/滑动终点。
                        if (tracking) {
                            consoleView.removeCallbacks(longPressTick)
                            touch.onCancel()
                            tracking = false
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        consoleView.removeCallbacks(longPressTick)
                        consoleView.clearPointer()
                        host?.updatePointer(event.x, event.y)
                        if (tracking) {
                            tracking = false
                            val action = touch.onUp(
                                y = event.y,
                                timeMs = event.eventTime,
                                // 跳过闸：宿主此刻是否在 WAIT 系等待（原版 `IsWaitingEnterKey`，
                                // `EmueraConsole.cs:324-343`；口径见 InputBarState.canSkip）。
                                canSkip = inputBarState().canSkip,
                                scrolled = scrolled,
                                dx = event.x - downX,
                            )
                            when (action) {
                                is TouchGestureController.Action.Tap -> {
                                    val result = handleConsoleTap(event.x, event.y)
                                    touch.noteTapExecuted(
                                        advanced = result.advanced,
                                        hitButton = result.hitButton,
                                    )
                                }
                                TouchGestureController.Action.Skip -> {
                                    handleSkip()
                                    // 跳过本身就是一次推进，且不落在按钮上。
                                    touch.noteTapExecuted(advanced = true, hitButton = false)
                                }
                                is TouchGestureController.Action.Scroll -> {
                                    // 快扫的兜底（2026-11-02 审查修正）：MOVE 只在攒满整行时付账
                                    //（ScrollLineAccumulator），DOWN→UP 之间没有 MOVE（事件被合并）
                                    // 或零头不足一行的快扫，scrolled=false，但 onUp 会按总位移
                                    // 独立判出 Scroll（TouchGestureController.onUp 的 lines != 0 支）
                                    // —— 这里不接住的话既不滚也不点，一拖就丢。符号换算在
                                    // [TouchGestureController.Action.Scroll.scrollRowsSteps] 里，
                                    // 与上面 MOVE 路同向（手指下移 = 回看更早）。
                                    scrolled = true
                                    consoleView.scrollRows(action.scrollRowsSteps)
                                }
                                // LongPress / None：抬手不再产动作
                                // （长按只弹提示，None 无可产出）。
                                else -> Unit
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        consoleView.removeCallbacks(longPressTick)
                        consoleView.clearPointer()
                        host?.updatePointer(event.x, event.y)
                        if (tracking) {
                            tracking = false
                            touch.onCancel()
                        }
                    }
                    else -> Unit
                }
            } catch (t: RuntimeException) {
                logUiFault("触摸", t)
            }
            true
        }
    }

    /**
     * 长按取色（INPUT(6/3,color) 消费面的手势半段；坐标换算与绘制逐式一致，
     * 见 `MappingColorGeometry` 与 `CanvasConsoleView.mappingColorOrNull`）。
     *
     * 原版在鼠标按下时由窗口侧取色写变量（spec: `MainWindow.cs:795` 的
     * `InputInteger(6L, console.SelectingButton.GetMappedColor(e.X, e.Y))`——INPUTMOUSEKEY
     * 等待；`:828` 的 `InputInteger(3L, ...)`——INPUT 系带鼠标等待），取色与推进是同一个
     * 事件；安卓没有 hover，本移植把取色落在**长按**（单击保留「按下按钮」语义、长按保留
     * 「查看」语义，互不挤占——见 [attachGestures] 长按段的判定说明）。
     *
     * 口径：
     * - **等待态闸**：只在会收色的等待态投喂——INPUTMOUSEKEY（`IsWaitingPrimitive`，
     *   spec: `EmueraConsole.cs:383-392`）或 INPUT 系带鼠标（`IsWaintingInputWithMouse`，
     *   spec: `EmueraConsole.cs:286-293` 的 `inputReq.MouseInput`）。其余等待态原版鼠标
     *   按下不写色（`MainWindow.cs:824`/`:851` 都要求带鼠标的等待），这里同样不动。
     * - **命中闸**：[CanvasConsoleView.mappingColorOrNull] 命中才投喂（null = 无遮罩段 /
     *   点不在图内 / 遮罩缺失），没命中时长按保持纯提示卡——否则「长按看按钮提示」会
     *   顺手往 INPUT 里塞一个 0。命中但像素为黑（色值 0）照原版口径照样交付
     *   （`MainWindow.cs:795`/`:828` 对取色值不做 0 剔除）。
     * - **投喂通道**：`GameHost.submit(色值十进制串)`——现存的提交口（执行期闸、翻页计时、
     *   流水账与文本提交同一条路）；对 INPUT（VALUE/INT）等待，文本解析交付 RESULT:0 =
     *   色值（`StatementExecutor.execInputsFlat` 的 `writeInt(RESULT, [0], o.intValue)`），
     *   这就是「长按 → 输入通道返回选中像素色」的端到端读数。投喂口 =
     *   `GameHost.submitMappedColor` → `QueueInputSink.submitMappedColor`（色条目直付
     *   交付面，不走文本通道）；INPUTMOUSEKEY 等待由此拿到色交付（W6 波收口时已接）。
     * - 空闲期（无等待方）投喂由 `GameHost.deliver` 的执行期闸兜底丢弃（与原版
     *   `PressEnterKey` 的 Running 早退同序，此处等待态闸已先挡一层）。
     *
     * 不镜像输入框（与空白处补回车的 `active.submit("")` 同例）：色是手势取的、不是玩家
     * 敲的，落进文本框镜像会被 GETTEXTBOX 轮询屏误读。
     *
     * TODO(verify)：真机端到端读数（长按 → RESULT 收到色）需要带 srcm 遮罩的语料 +
     * 对应等待形态；4.980 语料普查 srcm 0 处、INPUTMOUSEKEY 仅 1 处且被注释
     * （`ERB/DLC/开锁系统.ERB:184`），本机又无真机，屏上取证未清（见交付报告）。
     */
    private fun pickMappedColorAt(x: Float, y: Float) {
        val active = host ?: return
        val pending = active.pendingRequest() ?: return
        val colorCapable = pending.form == InputRequest.RequestForm.PRIMITIVE_MOUSE_KEY ||
            (pending.form == InputRequest.RequestForm.VALUE && pending.mouseInput)
        if (!colorCapable) return
        val color = consoleView.mappingColorOrNull(x, y) ?: return
        active.submitMappedColor(color)
    }

    /**
     * 屏幕横坐标 → 半角格列号（与 `CanvasConsoleView.tap` 的命中列同一空间）。
     *
     * 只给手势层算「双击位置邻域」用：`TouchGestureController` 的列阈值是半角格，
     * 换算必须与绘制侧同源（`cellWidthPx` = `CellMetrics.cellPixelWidth`），且不减夹取
     * （居中/右对齐行的 `colStart` 可以为负）。
     */
    private fun columnOf(x: Float): Int {
        val width = consoleView.cellWidthPx()
        if (!(width > 0f)) return 0
        return floor((x - consoleView.paddingLeft) / width).toInt()
    }

    /**
     * 行高兜底：`TouchGestureController` 的行高必须为正的有限值（它要拿去做除数），
     * 而 View 在首帧度量之前、或字号算出 0 时可能给出 0 —— 那种情况下退回 [fallback]，
     * 不让一次「还没量出字宽」的触摸把 Activity 抛死。
     */
    private fun Float.positiveOr(fallback: Float): Float =
        if (this > 0f && this.isFinite()) this else fallback

    /**
     * 执行一次「普通点击」（单击；也是双击判定没通过时的落点）：命中按钮 → 交值，
     * 否则按 [BlankTapPolicy] 在 WAIT 系交一次输入框草稿（`mesSkip = false`）；
     * 本击刚把视口弹回底部时只回贴底、什么都不交（`MainWindow.cs:799-820`）。
     *
     * 原实现是 `SimpleOnGestureListener.onSingleTapUp` 的方法体，逐条理由不变：
     * - 输入栏展开时，屏幕第一次点击只负责收起输入栏（对齐原 Compose 遮罩行为），
     *   不再往下走「点按钮/提交」；
     * - 点在任何位置都算一次「按下鼠标左键」：原版 `GetKeyState(VK_LBUTTON)` 读的是指针
     *   按下位（`FunctionMethodCreator.cs:7825`），语料的打断条件 `TITLE.ERB:158` 把左键与
     *   回车/ESC 并列（红 E）。命中按钮时随后还会走 [GameHost.submit] 再补一次回车沿变，
     *   两者是不同的键、不互相顶掉。
     *
     * @return 这一下到底交付了什么，供双击判定用（[TouchGestureController.noteTapExecuted]）
     */
    private fun handleConsoleTap(x: Float, y: Float): TapResult {
        if (shell.inputVisible) {
            shell.inputVisible = false
            shell.inputFocused = false
            return TapResult(advanced = false, hitButton = false)
        }
        val active = host ?: return TapResult(advanced = false, hitButton = false)
        active.fireVirtualKeyEdge(AndroidKeyCodes.VK_LBUTTON)
        val outcome = consoleView.tap(x, y, active.acceptance())
        val submitted = outcome.hit
        if (submitted != null) {
            // 世代闸（2026-10-26 静态复查补）：命中表是渲染侧的**可见行**按钮，
            // 上一页的按钮没滚出屏幕就仍在表里；原版的可点判据是按钮对象的
            // `Generation == lastButtonGeneration`（EmueraConsole.cs:2439、:2456）。
            // 判定收在 [GameHost.isCurrentGeneration]（只查世代、借右侧栏的
            // 取值类型过滤会误杀合法组合），不在当前世代的命中直接吞掉 ——
            // 真机 2026-10-25 的「跨屏串值把 CFLAG 打越界」就是这类错位的账。
            val current = active.isCurrentGeneration(submitted)
            if (current) submitButtonValue(submitted)
            return TapResult(advanced = current, hitButton = true)
        }
        // HOST-06：未命中按钮时，原版左键在非按钮区直接提交一次回车
        // （`MainWindow.cs:806` 的 `IsWaitingEnterKey && selectedString == null`，
        // 左键走 `:818-820` 的 `PressEnterKey`）。判定抽在 [BlankTapPolicy] 里：
        // 只有「宿主就绪 + 无覆盖层 + 正等回车/任意键」才补，数值/字符串等待
        // 与加载/失败覆盖层都不补（契约 CONTRACT-Q2 §2.8 的保守口径）。
        // 另两道原版闸（C4）：
        // - 这一击刚把视口弹回底部（`MainWindow.cs:799` 的 `flag`）⇒ 只回贴底、不回车
        //   （`:808` 的 `if (!flag)` 罩住 `:810-821` 全部分支）；
        // - 交的是**输入框草稿**而不是空串：原版 `PressEnterKey` 取
        //   `richTextBox1.Text`（`:926`）一路交给 `:934`，点空白与点提交键同一条路
        //   ⇒ 走既有提交口 [submitInput]，镜像/清框口径不另造一套。
        // 提交走 [GameHost.submit]（`mesSkip = false`），与按钮命中同一条 API；
        // 该 API 内部还会补一次 VK_RETURN 沿变，鼠标路径在原版并不产生 Return
        // 触发，这一笔是既有提交口的统一口径，不另开旁路。
        if (BlankTapPolicy.shouldSubmitEnter(
                hasHost = host != null,
                overlayVisible = shell.loading || shell.failure != null,
                canSkip = inputBarState().canSkip,
                hitButton = false,
                snappedToBottom = outcome.snappedToBottom,
            )
        ) {
            val draft = BlankTapPolicy.textToSubmit(shell.input)
            // [submitInput] 里还带一次 `snapToBottomForSubmit()`，此刻已在底部 ⇒ 无事可做。
            val delivered = submitInput(draft)
            return TapResult(advanced = delivered, hitButton = false)
        }
        return TapResult(advanced = false, hitButton = false)
    }

    /**
     * 执行一次「跳过」（双击的语义，原版右键/`Esc`）：
     * `PressEnterKey(keySkip = true, inputsByMouse = true)`（`MainWindow.cs:814-816`），
     * 跳过态由 sem 层落到 `ExecContext.mesSkip`（`EmueraConsole.cs:1371-1373`）。
     *
     * 原版双击跳过只在「等待回车」态生效（`MainWindow.cs:806` 的 `IsWaitingEnterKey`）；
     * INPUT/INPUTS 等文本请求时不该排一条 skip，否则会比玩家多提交一次 —— 所以这里
     * 再守一次 [InputBarState.canSkip]（手势层已经用同一个闸判过，这一层是防漏）。
     * 与 [submitInput] 同口径：提交即清框（canSkip ⇒ VM 正在等，不是抢跑）。
     */
    private fun handleSkip() {
        val active = host ?: return
        if (!inputBarState().canSkip) return
        mirrorTextBox("")
        active.submitSkip(shell.input)
        shell.input = ""
        consoleView.requestRedraw()
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
        // 点击走 submitButton：执行期（无等待方）被闸丢弃时，落点会留在单槽点击意图里，
        // 由下一次 VK_LBUTTON/VK_RETURN 读点补写回镜像 —— 地图/外出屏长链期间的点击不丢。
        active.submitButton(value)
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
    private fun submitInput(text: String, clickIntent: Boolean = false): Boolean {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        val active = host ?: run {
            shell.hint = copy.hintNotRunning
            return false
        }
        val state = inputBarState()
        if (!state.canSubmit) {
            shell.hint = copy.hintNotRunning
            return false
        }
        // 提交后框里留下什么由 InputBarState.textBoxAfterSubmit 决定（原版 PressEnterKey 的
        // 两条路：等待期 updateInputs 清框，:933 → :938-967；抢跑在原版提交不了、字留在框里，
        // :922-936）。Android 的选项按钮没有键入过程，抢跑那条路必须把这一条写进去，
        // 否则点「开始游戏」时 TITLE.ERB:168 的 `GETTEXTBOX() != ""` 永远等不到。
        // AWAIT 轮询屏（地图/外出）走的正是抢跑路：文本留在核心文本框，回车沿变由
        // [GameHost.submit] 补；右侧栏的显示兜底见 [GameHost.currentOptions]。
        state.textBoxAfterSubmit(text)?.let { mirrorTextBox(it) }
        // PC 任意键提交前的贴底半边（MainWindow.cs:1402-1406）：键入回车/提交按钮在入队前
        // 把视口送回最新行；选项按钮提交路对应原版点击语义（:799-805）。AWAIT 轮询屏的
        // 抢跑提交同样受此约束（原版 :1402 不看等待态，先弹底、后到 :1410 才闸提交）。
        // 手机软键盘没有 KeyDown 可挂，贴底实现在 CanvasConsoleView.snapToBottomForSubmit。
        consoleView.snapToBottomForSubmit()
        // 输入条/命中区共用这一口：画面按钮（右侧栏选项）带 clickIntent，执行期落点保活；
        // 键入回车不带（见 GameHost.submitButton 的 KDoc）。
        if (clickIntent) active.submitButton(text) else active.submit(text)
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

    /**
     * 竖屏 HUD 的屏行尾窗快照（竖屏设计稿 §3 数据来源一览「屏行快照
     * `LineBufferConsoleSink.lines()`」行）：经 [GameHost.debugSession] 的只读口取
     * `lines()`，截尾到 [PORTRAIT_HUD_TAIL_LINES] 后逐行取纯文本（`textOf`，与渲染层
     * 同一条行文本口径）。serial 随行带出——指令盘组标签靠它与候选的 `lineSerial`
     * 对区间（竖屏设计稿 §3.5 组标签段）。
     *
     * 只在竖屏分支的 250ms 轮询里被调（`PortraitShellContent`，频率 ≤ 选项扫描，
     * §0-9）；横屏不进这条路。锁口径同 `lines()` 本体（快照在锁内完成，跨线程安全）。
     */
    private fun portraitScreenLines(): List<Pair<Long, String>> {
        val sink = host?.debugSession()?.console ?: return emptyList()
        // ECHO-INPUT 抑制档下引擎根本不产生回显显示行，活表就是屏上可见行；
        // 脚本侧的行账本折算只在 deleteLine 发生，不影响这里读到的行表。
        return sink.lines().takeLast(PORTRAIT_HUD_TAIL_LINES).map { it.serial to sink.textOf(it) }
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
     * 用 [LeanOptionFilter] 留下屏上自带编号的常用指令（`移动[400]` 与 `[100] - 睁开眼睛` 两形）；
     * 关闭时 chips 原样不动。引擎快照与正文按钮命中都不受影响。
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
            // 先写壳、后记账（2026-10-31 复查，与 `ConsoleFeed.publish` 同一条纪律）：
            // 反过来写的话，`shell.hint` 这一笔抛一次（Compose 状态写入遇到回收帧的极端时序），
            // 水位已经记上 ⇒ 这条提示**再也不会**被重试，玩家永久停在上一条文案上。
            shell.hint = hint
            lastDerivedHint = hint
        }
        shell.numberKeyboard = state.keyboard == InputBarState.Keyboard.SIGNED_NUMBER
        // WAIT 系页面的「继续」脚钮闸门（见 ShellUi.ContinueButton）：
        // 每帧无条件刷（mutableStateOf 值不变不触发重组），不能放在下面 250ms 的
        // 选项扫描节流之后 —— 那一段每帧早退，会让脚钮的出现/消失慢半拍。
        shell.awaitingAnyKey = state.awaitingAnyKey
        // WIRE-5：当前等待请求的宏预填开关镜像进壳层（单一来源 = InputRequest.macroEnabled；
        // state.macroEnabled 由 InputBarState.of 从 pending.macroEnabled 推导，壳层不另存真源）。
        shell.macroEnabled = state.macroEnabled

        val now = android.os.SystemClock.uptimeMillis()
        if (now - optionScanAt < OPTION_SCAN_MS) return
        optionScanAt = now
        val recognized = host?.currentOptions().orEmpty()
        // 缺省关：`recognized` 原样交给选项栏，识别口径与筛选前完全一致。
        val chips = if (UiPrefs.leanOptions(this)) LeanOptionFilter.keepCommon(recognized) else recognized
        // 指纹 = **64 位哈希**，不再 `joinToString` 拼字符串（2026-10-31 真机 OOM 现场改）。
        //
        // 崩溃栈首帧就是这段的 `StringBuilder.append(Long)`：
        //   `OutOfMemoryError: Failed to allocate a 16 byte allocation with 4008 free bytes`
        //   `at MainActivity.refreshShell$lambda$46(MainActivity.kt:1441)`
        // 也就是说：判断「选项栏要不要刷新」这件事本身，每 250 ms 造 45 枚候选 ×（6 段中间串 +
        // 一条结果串）≈ 每秒几百个短命对象。平时只是白烧 GC，但堆一旦贴顶（彩色地图/角色列表
        // 这类大量图片的场景），**这一次 16 字节分配就是压垮进程的最后一根稻草** ——
        // 玩家看到的就是「打开菜单/切场景时莫名其妙退出来」。
        //
        // 判据没有变（行号/列号/ordinal/label/value 全进，色块颜色仅对真实调色板生效），只是不再为
        // 「比较」造对象：`label.hashCode()` 命中 String 自带的缓存，`value.hashCode()`
        // 对 Long/String 都不分配。64 位哈希的碰撞只会让选项栏停在上一版（概率可忽略），
        // 不会画错内容 —— 内容本身仍然整份交给 `shell.chips`。
        // 颜色对普通按钮和调色板都可见：任一选项换色都要刷新右栏底色。
        // 是否色块由 visual 字段单独参与指纹，不能拿颜色判断调色板身份。
        var h = 17L
        for (i in chips.indices) {
            val c = chips[i]
            h = h * 31 + c.lineSerial
            h = h * 31 + c.startCol
            h = h * 31 + c.endCol
            h = h * 31 + (c.ordinal ?: -1)
            h = h * 31 + c.label.hashCode()
            h = h * 31 + c.value.hashCode()
            h = h * 31 + c.visual.ordinal
            // 同一组调色板换色（部件色 / 重绘）时标签与值都可以不变；null 用 -1 占位，
            // 真实色值 0x000000 不被吞。
            h = h * 31 + (c.colorRgb ?: -1)
        }
        if (h != optionFingerprint) {
            // 同上：先落内容、后记指纹。反了的话一次写入失败就把这一版选项永久判成
            // 「已发布」，右侧栏停在上一次场景的按钮上 —— 点下去还会提交到错的等待方。
            shell.chips = chips
            optionFingerprint = h
        }
    }

    /** 失败屏「复制报告」：整份报告进剪贴板，玩家直接粘进群聊/邮件。 */
    private fun copyFailureReport() = copyReportFile(host?.lastReportFile)

    /** 失败屏「分享报告」：走系统分享面板（经 ReportFileProvider 的临时只读授权）。 */
    private fun shareFailureReport() = shareReportFile(host?.lastReportFile)

    /** 脏退出提示卡「复制报告」（报告 = 冷启补报落出的那份，见 RunWatch）。 */
    private fun copyDirtyExitReport() = copyReportFile(shell.pendingDirtyExitReport)

    /** 脏退出提示卡「分享报告」。 */
    private fun shareDirtyExitReport() = shareReportFile(shell.pendingDirtyExitReport)

    /**
     * 失败屏 / 退出卡的「复制报告」。
     *
     * UI 线程磁盘 IO 的裁定（obs-uiio 车道）：**乙 —— 挪走**。整份报告的读盘（最坏约 15 万字符：
     * 每行截到 `LINE_MAX_CHARS`=400 × 各节行数 160+60+40+80+12，口径在 core 的 `ErrorReport.kt:185-204`，
     * 端点正文配额 256 KiB 在 `ErrorReportUpload.kt:26`）就落在一颗按钮的回调里，
     * 而这一发既不在「进程下一刻可能就没命」的边上，也不是第一帧之前 ——
     * 它正是本仓真机出过两次的那一类（IO 藏在输入路径里 ⇒ 玩家说「点了没反应」）。所以：
     * 读盘交给一条短命的后台线程，剪贴板写入与 Toast 回主线程（Android 10+ 只有前台应用能写
     * 剪贴板，把写入也挪走才会真的改变语义；这里只挪**读**）。
     *
     * 三条 Toast 文案与判据一字未改，只是**到的时机**晚一个后台读的耗时：
     * 「没有可用的报告文件」= 文件不在；「复制失败，请改用「分享报告」」= 读失败或剪贴板失败；
     * 成功那一句不变。连按两次会起两条线程（各读各的、各 Toast 一次）—— 与旧写法在 UI 线程
     * 上串行跑两遍的结果一致，只是不再串着玩家的手。
     */
    private fun copyReportFile(file: File?) {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        if (file == null) {
            Toast.makeText(this, copy.toastNoReportFile, Toast.LENGTH_SHORT).show()
            return
        }
        val startedAt = android.os.SystemClock.uptimeMillis()
        Thread {
            var present = false
            var text: String? = null
            runCatching {
                present = file.isFile
                if (present) text = ErrorReportCollector.readReportText(file)
            }.onFailure { Log.w(TAG, "复制报告：后台读数这一趟出事", it) }
            val tookMs = android.os.SystemClock.uptimeMillis() - startedAt
            val reportText = text
            runOnUiThread {
                // 乙 要求的「UI 侧留一行读数」：这一行**只有后台那趟真跑完回到主线程才会出现**，
                // 于是「挪没挪成功」在 logcat 里直接读得出来；耗时同时是给真机用的标定材料
                // （本轮无设备 ⇒ 真机读数 TODO(verify)）。
                Log.i(
                    TAG,
                    "复制报告：读数已挪出 UI 线程（后台 ${tookMs}ms，present=$present，" +
                        "字数=${reportText?.length ?: -1}）；这一次点击的 UI 侧不做磁盘 IO",
                )
                val message = when {
                    !present -> copy.toastNoReportFile
                    reportText == null -> copy.toastCopyFailed
                    ErrorReportCollector.putClipboardText(this, reportText) -> copy.toastCopyOk
                    else -> copy.toastCopyFailed
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /**
     * 失败屏 / 退出卡的「分享报告」（系统分享面板，经 [ReportFileProvider] 的临时只读授权）。
     *
     * UI 线程磁盘 IO 的裁定（obs-uiio 车道）：**丙 —— 不进表**（StrictMode 盖不住这一段）：
     * - 主线程这一下**不读报告内容**：内容经 `content://` 由**对端进程**拉走，那一趟 open 发生在
     *   我们进程的 Binder 线程池上；线程策略是按线程装的（本仓那份装在 `EratwApplication.onCreate`
     *   = 主线程），Binder 线程上没有侦测位 ⇒ `detectDiskReads` 天然看不到，进表也无的可豁免；
     * - 面板本身是 `startActivity` 的一次 binder IPC，不属于四项侦测里的任何一项（`detectNetwork`
     *   只管 socket）；
     * - UI 侧剩下的只有 `file.isFile` 一次 stat 与 Intent 构造：stat 是否计入磁盘读未取证
     *   ⇒ `TODO(verify)`（侦测的挂钩点在 libcore 的 open 路径，见 `BlockGuardOs.open`）。
     *
     * 替代哨兵就是下面那一行**一次性耗时读数**：整颗按钮的 UI 侧成本（stat + Intent + 拉起面板）
     * 打进 logcat，过线（拿 [StrictModeGuard.SLOW_UI_DISK_IO_MS] 那把 2 s 尺子看）就是玩家说的
     * 「点了没反应」，与豁免表无关。
     */
    private fun shareReportFile(file: File?) {
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(this))
        if (file == null || !file.isFile) {
            Toast.makeText(this, copy.toastNoReportFile, Toast.LENGTH_SHORT).show()
            return
        }
        val startedAt = android.os.SystemClock.uptimeMillis()
        val intent = ErrorReportCollector.shareIntent(this, file)
        if (intent == null) {
            Toast.makeText(this, copy.toastSharePanelFailed, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { startActivity(Intent.createChooser(intent, copy.shareChooserTitle)) }
            .onFailure {
                Toast.makeText(this, copy.toastNoShareEntry, Toast.LENGTH_SHORT).show()
            }
        Log.i(
            TAG,
            "分享报告：UI 侧这一下 ${android.os.SystemClock.uptimeMillis() - startedAt}ms" +
                "（StrictMode 盖不住这一段，理由见本方法注释）",
        )
    }

    /**
     * 把失败原因上屏（失败屏的唯一入口）。
     *
     * @param messageOverride 非 null 时用它代替 [GameHost.failure]：解包前的空间预检
     *   还没有会话（`host == null`），失败原因只能由调用方直接给出；其余按钮语义与
     *   普通启动失败完全一致（都走 [failureActions] 那一份判据）。
     */
    private fun renderFailureIfNeeded(force: Boolean = false, messageOverride: String? = null) {
        val message = messageOverride ?: host?.failure ?: return
        if (failureShown && !force) return
        // 「内存不够」族里**只有乙族**（预检放行后装载中真 OOM）先自动降一档再试，而不是把玩家
        // 留在只有一颗他看不懂的按钮的屏上（判据见 [shouldAutoRetryCompat] 与 [HeapFailureStage]）。
        // 甲族（预检就判装不下）在这里**必须停在失败屏**：自动再试一次是明知装不下还硬试，
        // 2026-11 真机（vivo V2199A，上限 512 MiB）现场就是「自动降档 → 装载 OOM → 进程被杀 →
        // 玩家一张画面都没看到」。
        // 放在 `failureShown` 置位**之前**：这一趟没上屏，去重位自然不该被吃掉，
        // 兼容档再失败时还要走下面的完整出口。
        if (shouldAutoRetryCompat(
                heapFailure = host?.lastHeapFailureStage ?: HeapFailureStage.NONE,
                compatInUse = compatLoad,
                autoRetried = compatAutoRetried,
            )
        ) {
            compatAutoRetried = true
            Log.i(TAG, "装载失败（内存不够·装载中 OOM）：自动降到省内存装载档重试一次，先不弹失败屏")
            // 就地重启，**不 post**：排到下一轮 UI 循环的话，中间那一帧 pump 会再进本函数一次，
            // 而闩锁已经吃掉 ⇒ 它会走完整出口把失败屏闪给玩家看，然后才降档重开。
            // [retryLoadCompat] 与按钮走的是同一条路（`restartGame` 自己会复位 `failureShown`）。
            retryLoadCompat()
            return
        }
        // 失败后宿主停转、pump 不再跑：这里主动把壳层清干净（否则选项栏残留最后一批 chip，
        // 点下去还会往已关闭的输入队列提交）。真机 2026-10-23 实测：失败屏右侧仍挂着 0/1/2。
        shell.chips = emptyList()
        shell.loading = false
        shell.failure = message
        // 这一屏按哪一族讲，由宿主在写 `failure` 的那几处置位（判据见 [FailureScreenKind]）；
        // 只有「还没有会话」的一条路走到这里 —— 那是解包前的空间预检，本就属于产物族。
        val screenKind = host?.lastFailureScreenKind ?: FailureScreenKind.ARTIFACT
        shell.failureKind = screenKind
        // 方案①：本次失败落出了报告才点亮「复制报告 / 分享报告」。
        shell.failureReportReady = host?.lastReportFile?.isFile == true
        // 四枚出口的判据一次算完（含「重试已经试过一回还是失败」要不要给「结束应用」）：
        // 判据在 failureActions，与「重启停止」屏同源，不在这里各写一遍。
        val actions = failureActions(
            repeated = retriedAfterFailure,
            restartStuck = false,
            // 「回到标题」只在真能复活时点亮（同一口径见 GameHost.canBackToTitle）。
            canBackToTitle = host?.canBackToTitle() == true,
            // 「少占内存再试」在「内存不够」两族都点亮（取舍见 GameHost.canRetryCompat 的 KDoc）；
            // 差别只在要不要**自动**按 —— 那只认乙族（判据见 shouldAutoRetryCompat）。
            canRetryCompat = host?.canRetryCompat() == true,
            // 「上一局的内存还没释放」：本进程内重试解不开（那 184 MiB 是活对象），
            // 所以第一次上屏就给「重启应用」，不等玩家先白按一次重试。
            staleSession = host?.lastFailureWasStaleSession == true,
        )
        // 这一次重试已经记账并上屏了：下一张屏要重新等一次「按过重试」才再算一圈。
        retriedAfterFailure = false
        shell.failureCanBackToTitle = actions.canBackToTitle
        shell.failureCanRetryCompat = actions.canRetryCompat
        shell.failureCanExitApp = actions.canExitApp
        shell.failureExitIsRestart = actions.exitIsRestart
        shell.failureNote = actions.note
        // 先释放再画（本刀的第 3 条口径）：失败后堆往往贴边，画屏这几笔分配（Compose 重组 +
        // 行缓冲快照）落在「上一局还占着图形/会话引用」的时候，就是压垮进程的最后一根。
        // 会话与映射已由宿主在发布 `failure` **之前**关掉（见 GameHost.loadAndRun 的 catch 与
        // HeapFailureStage 的「先释放再上屏」一节）；这里再补两件**只在 UI 线程**能做的事：
        // 摘掉渲染桥（那条 lambda 强持 session，还带着位图缓存）与喂屏。
        // 两者都幂等、都只是几个字段赋值 + 一次 invalidate。
        uiStage("失败屏先摘渲染桥") { consoleView.detachGraphics() }
        consoleView.detachFeed()
        consoleView.submitSnapshot(
            listOf(ScreenRows.line(ScreenRows.run(failureScreenTitle(screenKind, ShellFailureCopy.of(UiPrefs.settingsLanguage(this))), fore = 0xFF6060, styleMask = 1))) +
                listOf(ScreenRows.textLine(message)) +
                failureScreenArtifactLines(screenKind, host?.artifactDir()?.absolutePath, ShellFailureCopy.of(UiPrefs.settingsLanguage(this)))
                    .map { ScreenRows.textLine(it) },
        )
        // **先做活、后记账**（与 `ConsoleFeed.publish` / `force` 同一条纪律，2026-10-31 复查）：
        // 上面任何一步抛出去（最可能是 `submitSnapshot`），本帧会被 `pump` 的分段兜底吞掉，
        // 而「已上屏」这一位**不能**提前置上 —— 置了就等于「这张失败屏永远不上屏」，
        // 玩家看到的是一张停住的旧画面，连重试按钮都没有。放在最后：失败就下一帧重来。
        failureShown = true
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

    // `private` → `internal`（EP1 波 L4 的组长接线）：读数口 `currentStallSceneLines()` 本体是
    // `internal`，但外壳是 private companion ⇒ 报告侧（本文件外）够不到。改成 internal 之后
    // 逐个成员仍受自己的修饰符约束（崩溃取证那一族已整段搬 CrashForensics.kt），模块外不可见。
    internal companion object {
        /** logcat 标签（与 [GameHost] 的 `GameHost` 并列；本类自己的读数走这一条）。 */
        const val TAG = "MainActivity"

        const val CONFIG_FILE = "emuera.config"
        const val PREVIEW_CHARS = 8

        /**
         * 方向锁按钮写下的 `requestedOrientation`。
         *
         * 锁定 = [ActivityInfo.SCREEN_ORIENTATION_LOCKED]，冻在点击那一刻的方向；
         * 解锁 = 回到本局偏好对应的传感器向：横屏局 [ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE]，
         * 竖屏局（设置页开了「竖屏模式」）[ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT]。
         * 解锁**不能用** `UNSPECIFIED`：那是把方向决定权交给系统自动旋转偏好，手机开着
         * 系统旋转锁（竖屏）时游戏会被钉死在竖屏（2026-11-04 玩家反馈）。
         */
        internal fun orientationForLock(locked: Boolean, portraitMode: Boolean): Int = when {
            locked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            portraitMode -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        /**
         * 「显示大小档 / 列数守护开关 / LIVE-REFLOW 开关」里有没有任何一项变了（LIVE-REFLOW 契约 §6 L6）。
         *
         * 三颗都是 [CanvasConsoleView] / [GameHost] 的**装配期**参数：改了只有重建 View（进而重开一局）
         * 才生效，所以从设置页回来时必须比对，否则玩家改完返回，当前会话还按旧参数渲染。
         * 口径与 `orientationForLock` 同款：判据抽成 companion 纯函数，
         * 因为 `MainActivity` 是 Android 壳、app 的 JVM 单测面实例化不了它。
         *
         * 两处调用点给的是**不同的参照系**，语义不同（都在 [onActivityResult]）：
         * - 对 `applied*`（本实例实际用的值）比 ⇒ 变了就得重开（`displayChanged`）；
         * - 对 `settingsEntry*`（进设置页那一刻的快照）比 ⇒ 用来区分「这次访问真的改了」与
         *   「上一轮改了但选了下次启动生效」，后者不该每次返回都弹确认。
         *
         * 与「竖屏模式」那条（`orientationTouched`）**分开**：方向变的是壳层分支，走自己那条
         * 重建与文案，本函数不参与。
         */
        internal fun displaySettingChanged(
            nowFontColumns: Int,
            appliedFontColumns: Int,
            nowScreenFit: Boolean,
            appliedScreenFit: Boolean,
            nowLiveReflow: Boolean,
            appliedLiveReflow: Boolean,
        ): Boolean = nowFontColumns != appliedFontColumns ||
            nowScreenFit != appliedScreenFit ||
            nowLiveReflow != appliedLiveReflow

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

        /**
         * 切后台后延迟多久才冻结脚本（毫秒）。设置页短暂覆盖也走 onStop，30 秒是
         * 「短暂覆盖」与「真后台」的分界（见 [backgroundPauseRunnable]）。
         */
        private const val BACKGROUND_PAUSE_DELAY_MS = 30_000L

        /** 分片扁平语句池的设备归因开关（启动 Intent 布尔 extra；见 [flatStatementsOverride]）。 */
        private const val EXTRA_FLAT_STATEMENTS = "eratwFlatStatements"

        /** MiB 换算（向上取整的失败文案用）。 */
        private const val MIB_BYTES = 1024L * 1024L

        /**
         * 冻结现场读数口（EP1 波 · L4）：与 CrashForensics 的 crashHostProvider 同一对身份闸口径。
         *
         * 为什么进程级还要一个口：采集器有两个入口拿不到 Activity 实例 —— 崩溃处理器（进程级
         * 单例，只装一次）与 `RunWatch.beginProcess` 那条冷启路。实例上的
         * `MainActivity.stallSceneLines()` 够不到它们，而直接把哨兵做成单例又违背
         * 「禁静态可变全局」（铁律 4）。这里放的只是一个**函数引用**，可变状态全在
         * Activity 成员 [stallSentinel] 的实例字段上；槽位每次 onCreate 刷新、`onDestroy`
         * 认主摘除（见那两处），所以槽里最多指向当前活的那只壳。
         */
        @Volatile
        private var stallSceneProvider: (() -> List<String>)? = null

        /** 当前把 [stallSceneProvider] 指向自己的 Activity 实例（身份闸，口径同 CrashForensics 的 crashProviderOwner）。 */
        @Volatile
        private var stallSceneOwner: MainActivity? = null

        /**
         * 进程级读「最近一次主线程冻结现场」（给报告侧用；L6 的接线点）。
         *
         * 没装过 / 已摘除 / 本进程没冻结过 = 空表，调用方据此不写这一节。整段 `runCatching`：
         * 这一发跑在崩溃线程上时，读数口自己出事绝不能把取证链再带一次。
         */
        internal fun currentStallSceneLines(): List<String> = runCatching {
            stallSceneProvider?.invoke() ?: emptyList()
        }.getOrDefault(emptyList())

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

        /**
         * 等旧会话退出的硬上限（毫秒）：到点仍不退出就停手报失败，不再拿 OOM 去赌。
         *
         * 口径变更（2026-10-30）：这一窗口原先「刚好盖住生产的 60 秒段落墙钟」；生产判据已改成
         * 10 亿步确定性闸、墙钟关（见 `GameSession.PRODUCTION_MAX_STEPS_PER_RUN`），所以这里
         * **不再有任何闸保证旧会话会自己死掉** —— 本值是纯体验取舍：按当前包速度的合法长段
         * （起床链 ≈2.90 亿条指令，几十秒量级）留余量，同时不把没有按钮的等待屏拖更长。
         * 真撞满 120 秒的出口是这一屏的「结束应用」按钮（[renderRestartStuck]，2026-10-30 补）。
         */
        private const val RESTART_HARD_WAIT_MS = 120_000L

        /** 旧 VM 线程退出轮询的间隔（毫秒）。 */
        private const val RESTART_JOIN_POLL_MS = 50L

        /**
         * 「重启应用」的安排延迟（毫秒）：给出本进程真正退出的窗口，再由系统拉起新进程。
         *
         * 太短（0~100ms）可能与 `Runtime.exit` 抢时序（旧进程还没让出，新实例已经在 onCreate），
         * 太长则玩家要对着桌面等。500 ms 是两者的折中；非精确闹钟的实际到点由系统调度
         * （见 [scheduleRelaunch] 的取舍）。
         */
        private const val RELAUNCH_DELAY_MS = 500L

        /** 「重启应用」的 PendingIntent 请求码：固定值 + `FLAG_CANCEL_CURRENT`，不叠加多份。 */
        private const val RELAUNCH_REQUEST_CODE = 0x0EB7

        /**
         * 首启解包的读数间隔。真机随包是四千条目 / 679.9 MiB，解包要几十秒到几分钟，
         * 这块屏必须动；但每轮都要 `listFiles` 递归一遍（不 stat 长度），所以不追高频率 ——
         * 800 ms 在「看着像在干活」和「别把解包线程的 IO 抢掉」之间取的值。
         */
        const val TICK_MS = 800L

        /**
         * 随包清单缓存文件名（放 `cacheDir`，系统可随时清）。
         *
         * 丢了只会让下一次冷启多扫一遍 assets；内容格式与失效键见 [BundleInventoryStamp]。
         */
        private const val BUNDLE_INVENTORY_CACHE = "bundle-inventory.txt"
    }

}
