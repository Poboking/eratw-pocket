package com.eratw.port.android

import android.app.Application
import android.os.Build
import android.os.Looper
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import com.eratw.port.core.host.GraphicsPool
import com.eratw.port.core.host.MemoryTier
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.platform.image.SpriteBitmapSource

/**
 * 进程入口（`AndroidManifest.xml` 的 `android:name`）。本文件做两件事，都是**进程级**而不是
 * 界面级的：①把这台机的 API 级别落成 core/platform 现取的内存档位性质；②按构建档决定装不装
 * StrictMode 哨兵。
 *
 * ## 为什么是 Application 而不是 Activity
 *
 * 两件事的理由同源：
 *
 * - 内存档位（[MemoryTier]）被装载预检与图形池读，而那两处**不以任何界面为前设**；性质晚落
 *   一步的形态是整程静默走保守档（见下面 onCreate 那段的注释）。
 * - 哨兵的价值在于**常驻**：`MainActivity` 只覆盖游戏那一个界面，设置页（`SettingsActivity`）、
 *   更新弹窗、崩溃处理器跑起来的那些 UI 线程代码不在它的生命周期里；而进程一活就装、
 *   一直装到进程死，才回答得了「玩家点屏幕没反应的那一次，主线程到底有没有在等盘」。
 *
 * ## 为什么只在 debug 装
 *
 * 判式与理由都在 [StrictModeGuard.policyFor]；罚则只有 `penaltyLog`（[StrictModeGuard] 类头
 * 写明了为什么不 `penaltyDeath`）。装策略落在主线程上：`Application.onCreate` 由
 * `ActivityThread` 在主线程调，`StrictMode.setThreadPolicy` 影响的正是调用它的那条线程 ——
 * 这条前提别改（挪到子线程初始化就等于给主线程装不上哨）。
 */
class EratwApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // C6（wave/eng-crash）：`:crash` 进程只跑崩溃屏，这一屏的全部价值是「少失败」——
        // 不装内存档位、不打档位读数、不装 StrictMode：三样都与它无关，任何一样在某个 ROM
        // 上出怪（哪怕只是 runCatching 里的 Log 分配失败）都是拿「想显示错误」换「更快的死」。
        // 判据只看进程名后缀，读不到就当主进程（宁可在崩溃屏里多做两件事，也不许主进程静默丢档位）。
        if (isCrashScreenProcess()) {
            runCatching { Log.i(CRASH_PROCESS_TAG, "崩溃屏进程：跳过内存档位与 StrictMode 装配") }
            return
        }
        // R3 结构化事件（常开，release 也开）：绑定落盘目录并记本进程开局。两件事都包在
        // 同一个 runCatching 里 —— 事件装不上是「少一条因果链」，不许升级成「开不了机」。
        runCatching {
            EventJournal.begin(this)
            EventJournal.record(
                "session.start",
                mapOf(
                    "build" to "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
                    "pid" to android.os.Process.myPid().toString(),
                ),
            )
        }.onFailure { Log.w(EVENTS_TAG, "事件日志装配失败（游戏照常起）", it) }
        // 机型内存档位（[MemoryTier]）的唯一落地点：core 层禁读 Android API（铁律 4），
        // API 级别只能由宿主送进去，而四道自家内存闸（像素预算 / 工作表缓存 / 装载期 GC 阈值 /
        // 装载预检系数）都在 core 与 platform 里现取这条性质。
        //
        // 为什么必须在 Application 而不是 Activity：装载预检与图形池第一次读预算的时机
        // 不以任何界面为前设（装置路径、`adb shell am start` 带 extra 直启都能走到）——
        // 性质晚落一步的形态是**静默走保守档**，与没做这一刀逐字相同，所以这一条既不进
        // 失败屏也不许抛：这里抛一次就是「想多要点内存反而开不了机」。
        runCatching {
            System.setProperty(MemoryTier.SDK_INT_PROPERTY, Build.VERSION.SDK_INT.toString())
        }.onFailure { Log.w(MEMORY_TIER_TAG, "内存档位性质落不上（全程按保守档跑）", it) }
        // 一行把档位与档位背后的四个读数一起打出来：放开档到底有没有在真机上生效，
        // 只认这一行的读数，不认「代码里有那个 if」。串本体见 [MemoryTierReadout]（与错误报告
        // 共用同一条，两处不许各写一份）。
        //
        // 这行也包 runCatching：读数要现取五发（属性、堆、四道闸），任何一发在某个 ROM 上
        // 抛出来，都不该变成「想多要点内存反而开不了机」—— 取证失败是损失，开不了机是事故。
        runCatching { Log.i(MEMORY_TIER_TAG, MemoryTierReadout.line()) }
            .onFailure { Log.w(MEMORY_TIER_TAG, "内存档位读数打不出（游戏照常起）", it) }
        // 只记日志、绝不抛：这里抛一次就是「装了哨兵反而开不了机」，本波不许出这种事故。
        runCatching {
            EratwStrictMode.install(StrictModeGuard.policyFor(BuildConfig.DEBUG))
        }.onFailure { Log.w(StrictModeGuard.LOG_TAG, "StrictMode 装载失败（游戏照常起）", it) }
    }

    private companion object {
        /** 内存档位那行的 logcat tag（同 [StrictModeGuard.LOG_TAG] 的命名口径）。 */
        const val MEMORY_TIER_TAG: String = "EratwMemTier"

        /** 崩溃屏进程那行 logcat tag（与 [CrashScreenActivity.LOG_TAG] 同一前缀）。 */
        const val CRASH_PROCESS_TAG: String = "EratwCrashScreen"

        /** 崩溃屏进程名后缀（manifest 里 `CrashScreenActivity` 的 `android:process`）。 */
        const val CRASH_PROCESS_SUFFIX: String = ":crash"

        /** 事件日志那行 logcat tag（与 [EventJournal] 内部 TAG 同一字符串）。 */
        const val EVENTS_TAG: String = "EratwEvents"
    }

    /**
     * 本进程是不是崩溃屏那个独立进程（`<包名>:crash`）。
     *
     * API 28 起有 [Application.getProcessName]；更低版本读 `/proc/self/cmdline`（同一条事实，
     * 只是要自己读）。两种情况都整段 runCatching —— 读不到就按主进程处理：主进程少装配
     * 内存档位与 StrictMode 的代价，比崩溃屏多跑两条 runCatching 大得多。
     */
    private fun isCrashScreenProcess(): Boolean = runCatching {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            java.io.File("/proc/self/cmdline").readText().trim().substringBefore('\u0000')
        }
        name.orEmpty().endsWith(CRASH_PROCESS_SUFFIX)
    }.getOrDefault(false)
}

/**
 * [StrictModeGuard] 那套纯判式的 Android 落地层。
 *
 * 判式（该不该装、开哪几项侦测、哪个动作点能豁免、慢到多少毫秒算慢）全部在纯 JVM 的
 * [StrictModeGuard] 里，可单测；这里只负责把它翻成 `android.os.StrictMode` 的真实调用。
 * 之所以拆成两个文件而不是往判式里塞 Android import：本仓 app 层的单测是纯 JVM
 * （mockable android.jar 调未实现方法直接抛），判式必须做到不碰 Android 也能全验。
 */
internal object EratwStrictMode {

    /**
     * 按策略装载。[StrictModePolicy.DISABLED]（release）时**只打一行、不改任何策略**。
     *
     * 侦测项与处罚项都从 [StrictModeGuard] 的表里逐项落地：表里出现一个这里没有的项，
     * 会明确打一行「没有落地实现」而不是静默跳过 —— 否则「加了个侦测项却没生效」这种
     * 事故在日志里长得和「什么都没发生」一模一样。
     *
     * 这一份策略是**覆盖**式的（`setThreadPolicy`/`setVmPolicy` 直接替换）：系统给 debuggable
     * 包预置的那份默认策略（历史上 Android 9 起对「主线程发网络」带过 death 罚则，
     * TODO(verify)：本轮无设备，未真机复核默认策略到底是什么）会被换成「只记日志」。
     * 这是刻意的 —— 本仓口径是 UI 线程故障只记日志、进程继续（`MainActivity.kt:294`），
     * 开发期也不许哨兵自己把正在调的东西打死。
     */
    fun install(policy: StrictModePolicy) {
        if (!policy.installs) {
            Log.i(StrictModeGuard.LOG_TAG, "StrictMode 未装载（构建档 ${policy.name}）")
            return
        }
        val thread = StrictMode.ThreadPolicy.Builder()
        for (item in StrictModeGuard.debugThreadDetections) {
            when (item) {
                StrictModeGuard.DETECT_DISK_READS -> thread.detectDiskReads()
                StrictModeGuard.DETECT_DISK_WRITES -> thread.detectDiskWrites()
                StrictModeGuard.DETECT_NETWORK -> thread.detectNetwork()
                // API 28 起才有自定义慢调用这一族（noteSlowCall 同批）；低版本直接不开。
                StrictModeGuard.DETECT_CUSTOM_SLOW_CALLS -> if (isAtLeastP()) {
                    thread.detectCustomSlowCalls()
                } else {
                    Log.i(StrictModeGuard.LOG_TAG, "自定义慢调用侦测跳过（需 API 28，当前 ${Build.VERSION.SDK_INT}）")
                }
                else -> Log.w(StrictModeGuard.LOG_TAG, "主线程侦测项没有落地实现：$item")
            }
        }
        applyPenalties(thread)
        StrictMode.setThreadPolicy(thread.build())

        val vm = StrictMode.VmPolicy.Builder()
        for (item in StrictModeGuard.debugVmDetections) {
            when (item) {
                StrictModeGuard.DETECT_LEAKED_CLOSEABLES -> vm.detectLeakedClosableObjects()
                else -> Log.w(StrictModeGuard.LOG_TAG, "进程级侦测项没有落地实现：$item")
            }
        }
        vm.penaltyLog()
        StrictMode.setVmPolicy(vm.build())
        Log.i(
            StrictModeGuard.LOG_TAG,
            "StrictMode 已装载：主线程 ${StrictModeGuard.debugThreadDetections} " +
                "｜进程级 ${StrictModeGuard.debugVmDetections} ｜处罚 ${StrictModeGuard.debugPenalties}",
        )
    }

    /**
     * 在一个**已登记的**豁免窗口里跑 [body]：窗口内放开磁盘读写，出了窗口原样恢复。
     *
     * 四种走向，只有最后那种真的开窗口：
     * 1. `where` 不在 [StrictModeGuard.uiDiskIoExemptions] 里 ⇒ 不开窗口并打一行
     *    [StrictModeGuard.rejectionMessage]，**动作照做**（漏加表的表现是多一行日志，
     *    不是少一次侦测，也不会改变游戏行为）；
     * 2. release（策略 [StrictModePolicy.DISABLED]）⇒ 纯直通，一个 Android API 都不碰；
     * 3. **非主线程调用** ⇒ 不开窗口、打一行说明、动作照做。闸收在本体（fix-uiowiring 车道，
     *    上一路只敢在调用方各钉一道闸就是因为当时 `EratwApplication.kt` 不归它）：
     *    窗口状态机（[windowOpen] 重入位与 [slowExemptionHits] 计数）按「只在主线程读写」
     *    实现，在别的线程上开合会把这条边踩脏 —— 真脏一次，主线程的下一次豁免就变成
     *    永久静默放行；而 StrictMode 的线程策略本就按线程装（AOSP frameworks/base 的
     *    `StrictMode.java`：`setThreadPolicy` 只写当前线程的 mask），本仓那份装在
     *    `EratwApplication.onCreate`（主线程），别的线程上本来就没有侦测位可豁免。
     *    调用方**不需要也不该**再自己分支决定开不开窗口。
     * 4. debug、主线程、且表内 ⇒ `StrictMode.allowThreadDiskWrites()` 拿回旧策略、跑完立刻
     *    `setThreadPolicy` 恢复。注意它只放宽磁盘读写：**网络与自定义慢调用照常报**，
     *    所以豁免点里顺手发请求照样会被抓到。
     *
     * 窗口里还有一条重入保护（见下面行内注释）：豁免点套豁免点时不再叠第二层窗口。
     *
     * 窗口内量到的耗时若过 [StrictModeGuard.SLOW_UI_DISK_IO_MS]，按 [UiFaultLog] 的节奏打一行
     * 带 [StrictModeGuard.violationTag] 的日志，并（API 28+）用 `noteSlowCall` 把同一个标签
     * 注成 StrictMode 自己的自定义违规 —— 豁免不等于闭嘴：这些写盘/读盘真慢到 2 s 以上，
     * 那正是玩家说的「点屏幕没反应」的形状，必须留在日志里。
     */
    fun <R> allowUiDiskIo(where: String, body: () -> R): R {
        if (!StrictModeGuard.allowsUiDiskIo(where)) {
            Log.w(StrictModeGuard.LOG_TAG, StrictModeGuard.rejectionMessage(where))
            return body()
        }
        if (!StrictModeGuard.policyFor(BuildConfig.DEBUG).installs) return body()
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Log.i(
                StrictModeGuard.LOG_TAG,
                "豁免口「$where」被非主线程调用：不开窗口（策略按线程装，那条线程上本无侦测），动作照做",
            )
            return body()
        }
        // 重入：外层窗口已经开着（豁免点里又调了另一个豁免点）就不再叠一层，直接跑。
        // 叠了会怎样：内层 finally 把「已经放宽」的那份当成旧策略恢复回去，外层剩下的一半
        // 从此再也报不出来 —— 豁免口的边界就悄悄变成了一次全进程范围的静默放行。
        if (windowOpen) return body()
        windowOpen = true
        val previous = StrictMode.allowThreadDiskWrites()
        val startedAt = SystemClock.uptimeMillis()
        try {
            return body()
        } finally {
            windowOpen = false
            StrictMode.setThreadPolicy(previous)
            reportIfSlow(where, SystemClock.uptimeMillis() - startedAt)
        }
    }

    /**
     * 慢样本的限流上报。
     *
     * 计数与 [windowOpen] 都挂在**本 object** 上，且只可能来自主线程：[allowUiDiskIo] 本体
     * 的 Looper 闸（走向 3）保证了这一点，`MainActivity.uiStage` 那套生命周期回调也都在主线程
     * 派发 ⇒ 与 `MainActivity.uiFaultCount` 同一条口径，不需要同步。
     */
    private fun reportIfSlow(where: String, tookMs: Long) {
        if (!StrictModeGuard.isSlowUiDiskIo(tookMs)) return
        val hits = (slowExemptionHits[where] ?: 0L) + 1L
        slowExemptionHits[where] = hits
        when (UiFaultLog.mode(hits)) {
            UiFaultLogMode.FULL, UiFaultLogMode.SHORT -> {
                Log.w(StrictModeGuard.LOG_TAG, StrictModeGuard.slowDiskIoMessage(where, tookMs))
                if (isAtLeastP()) {
                    runCatching { StrictMode.noteSlowCall(StrictModeGuard.violationTag(where)) }
                        .onFailure { Log.w(StrictModeGuard.LOG_TAG, "自定义违规标签注入失败（继续）", it) }
                }
            }
            UiFaultLogMode.SILENT -> Unit
        }
    }

    /** 处罚项落地：表里只可能有一项 `penaltyLog`，别的形状一律不认（判式在 [StrictModeGuard.debugPenalties]）。 */
    private fun applyPenalties(thread: StrictMode.ThreadPolicy.Builder) {
        for (item in StrictModeGuard.debugPenalties) {
            when (item) {
                StrictModeGuard.PENALTY_LOG -> thread.penaltyLog()
                else -> Log.w(StrictModeGuard.LOG_TAG, "处罚项没有落地实现（有意不加？改判式并写明理由）：$item")
            }
        }
    }

    private fun isAtLeastP(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /** 每个豁免点的「过阈值慢样本」累计次数（仅主线程读写，理由见 [reportIfSlow]）。 */
    private val slowExemptionHits = HashMap<String, Long>()

    /** 豁免窗口是否已开（仅主线程读写，由 [allowUiDiskIo] 的 Looper 闸保证）：只为挡住嵌套调用。 */
    private var windowOpen = false
}

/**
 * 内存档位的一行读数：进程 logcat 与**错误报告头部行**共用同一条串。
 *
 * ## 为什么两处都要打
 *
 * 本仓自己的记录写着：真机 vivo（Origin OS）会把应用 logcat 整条丢掉，`load.*` 那批
 * `Log.i` 在出问题那台机上根本拿不到（`LoadTrace.kt` 的 `summaryLines` KDoc 在册）。
 * 只靠 logcat 的那行档位读数，等于「最需要看档位的那台机永远读不到档位」。
 * 报告头部行是**端点回收得到**的通道（2026-10-31 那批 OOM 归因就是这么来的），
 * 所以这一条同时进两处，串只有一份。
 *
 * ## 读数为什么现算而不抄
 *
 * 四个数各自调**生产同一支函数**（缺省参 = 本进程现取）：像素预算走
 * [GraphicsPool.defaultPixelBudgetBytes]、工作表缓存走
 * [SpriteBitmapSource.defaultSheetCacheBytes]、GC 阈值走 [LoadTrace.gcTriggerPercent]、
 * 预检系数走 [ProgramLoader.artifactResidentPercent]。抄数字就会有一个「报告说的和引擎用的
 * 不是同一个数」的形态 —— 那正是本仓最怕的静默失效。
 */
internal object MemoryTierReadout {

    private const val MIB = 1024L * 1024L

    /** 档位那一行（不带换行；调用方自己决定前缀）。 */
    fun line(): String {
        val maxMiB = Runtime.getRuntime().maxMemory() / MIB
        return "内存档位=" + (if (MemoryTier.isGenerous()) "放开" else "保守") +
            "（API ${Build.VERSION.SDK_INT}、Java 堆上限 $maxMiB MiB）" +
            " 像素预算=${GraphicsPool.defaultPixelBudgetBytes() / MIB}MiB" +
            " 工作表缓存=${SpriteBitmapSource.defaultSheetCacheBytes() / MIB}MiB" +
            " GC阈值=${LoadTrace.gcTriggerPercent()}%" +
            " 预检系数=${ProgramLoader.artifactResidentPercent()}%"
    }
}
