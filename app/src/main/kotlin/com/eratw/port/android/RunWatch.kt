package com.eratw.port.android

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * 运行轨迹与脏退出标记（2026-10-30 玩家反馈「死机了」「这次没有报错，直接闪退了」）。
 *
 * ## 为什么现有的诊断抓不到这一类死
 *
 * 整条诊断链（[LoadTrace] 的装载分段采样、堆预检、[ErrorReportCollector] 的报告）全长在
 * **装载那几十秒**上，出口是「抛异常 → catch → 落报告」。而玩家报的是**玩了一会儿**之后：
 * 系统按内存压力杀进程（lmkd / OEM 的后台管控）不给任何 Java 异常，OOM 大到本报告自己也
 * 分配不出来时同样什么都留不下。于是「没有报错」既可能是没抛异常，也可能是抛了但发不出去 ——
 * 这两种我们现在**都区分不了**。
 *
 * ## 本类补的东西
 *
 * 1. **脏退出标记**：状态机三态 + 收尾态（`state.txt` 的首词）：
 *    - `running`：进程在前台跑着。冷启看见它 ⇒ 上一轮**在屏前**死掉（系统杀 / native 崩），
 *      当场补一份「上次异常退出」报告走既有上报链，并交给壳层弹卡；
 *    - `background`：`onStop` 写过（界面已不可见）。冷启看见它 ⇒ 上一轮是**退到后台后被回收**
 *      （lmkd / OEM 清理），属于系统正常管控 —— **不算异常退出**，不落报告、不弹卡，只留一行日志。
 *      这一态是 2026-10-30 装机复看补的：没有它，玩家按 Home 键离开后只要被清一次，
 *      每次冷启都会弹一次「上次异常退出」（vivo 真机退出记录里全是从后台发出的 FORCE STOP）；
 *    - `stopped` / `consumed`：走到了已知收尾点 / 脏退出已认账，整轮干净。
 *    回到前台（`onStart`）会重新写 `running`，否则「回前台后真的死掉」会被误当后台回收。
 * 2. **运行期堆轨迹**：每个回合边界采一发（Java 已用 / 堆上限 / native，口径与
 *    [LoadTrace.Sample] 逐字相同，两处读数可比），滚动只留最后 [RunTrail.MAX_LINES] 行。
 *    系统一给内存压力（[forceSample]）立刻强制钉一发 —— 那一发往往就是死前最后一行。
 *
 * 轨迹**落盘**是有节流的（[RunTrail.WRITE_MIN_INTERVAL_MS]），但**采样**不节流：
 * 内存里的环形表不要钱，被杀时丢的最后几行最多 [RunTrail.MAX_LINES] 行的尾巴，值。
 *
 * ## 铁律
 *
 * 全程 `runCatching`：埋点把游戏搞崩是本末倒置。任何一次写盘失败只 Log。
 */
internal object RunWatch {

    private const val TAG = "EratwRun"
    private const val MIB = 1024L * 1024L

    /** `/proc/meminfo`（系统可用内存的唯一来源，见 [sysAvailMiB]）。 */
    private const val PROC_MEMINFO = "/proc/meminfo"

    /** 系统可用内存的读数缓存时间（毫秒）：回合边界可以每几十毫秒一次，读盘不能跟着走。 */
    private const val SYS_READ_MIN_INTERVAL_MS = 1_000L

    private val lock = Any()

    /** 进程起点（[SystemClock.elapsedRealtime] 基准），用于轨迹行的 `t=`。 */
    private val originMs: Long = SystemClock.elapsedRealtime()

    /** 内存里的滚动轨迹（旧在前）。只在 [lock] 内读写。 */
    private val trail = ArrayDeque<String>()

    /** 上一次真正落盘的毫秒（[originMs] 基准）；-1 = 还没落过。只在 [lock] 内读写。 */
    private var lastWriteMs = -1L

    /**
     * 环形表追加计数（每次 `addLast` +1）：落盘快照的**序号**基准。
     *
     * 为什么要它：`appendAndMaybeWrite` 的「取快照 + 覆盖写盘」发生在 [lock] 之外，UI 线程
     * （onPause / onTrimMemory 的 `forceSample`）与 VM 线程（回合边界）都能同时带着 `due`
     * 走出来 —— 两个快照谁**后**落盘纯看调度，旧快照能把带 `pause` 尾行的新快照盖回去，
     * 脏退出判据（[RunTrail.dirtyReportLines] 读盘上末行）就此失声。序号让写侧能认出旧快照。
     * 只在 [lock] 内自增与读取；快照与它的序号在同一次 [lock] 临界区内一起取，二者永远配对。
     */
    private var appendSeq = 0L

    /**
     * 轨迹落盘的写侧闸门（见 [TrailWriteGate]）：写者之间专用一把 monitor + 序号跳旧快照。
     *
     * 故意**不复用** [lock]：写盘（4 KB 覆盖写）挪进 [lock] 会让 `onPause` 那类 UI 钉行
     * 等 VM 的磁盘 IO —— 观察性欠账不能换成新的 UI 卡顿，这是本仓明令的方向。
     */
    private val trailGate = TrailWriteGate { file, lines -> RunTrail.writeTrailFile(file, lines) }

    /** 系统可用内存的缓存读数（MiB）与它的采样时刻；都只在 [lock] 内读写。 */
    private var sysAvailCacheMiB = -1L
    private var sysAvailCacheAtMs = -1L

    /** 本机低内存线（[ActivityManager.MemoryInfo.threshold]，MiB）；-1 = 查不到。 */
    @Volatile
    private var lmkThresholdMiB = -1L

    /** 低内存线是否已经查过（每进程只查一次，同一进程里它基本不变）。 */
    @Volatile
    private var lmkThresholdQueried = false

    /** 本进程是否已经被认账为「异常退出过一次」，避免同一份死因每启一次报一次。 */
    private var dirtyReported = false

    /**
     * 冷启动取证的同进程一次性闸门（判式本体见 [ColdStartOnceGate]，有定向单测）。
     *
     * 没有它，第二趟 `onCreate`（Activity 被重建而进程还活着）会拿**本进程自己**写下的那行
     * `running` 判出一次「上次异常退出」，在游戏正开着的时候弹给玩家。
     */
    private val coldStartGate = ColdStartOnceGate()

    /**
     * 最近一次该递给玩家的「上一轮死掉了」报告；null = 本进程没报过或落盘失败。
     *
     * 两种来源走同一张卡（自动上报在大陆网络经常送不出去，这份文件是玩家唯一能手动交的
     * 东西 —— [consumeDirtyExitReportFile] 把它交给壳层弹「复制 / 分享报告」）：
     * 1. **脏退出**（没有 throwable、也没有人认领的死）：[beginProcess] 当场补落的那份；
     * 2. **崩溃兜底认领过的那一轮**：崩的当场报告已经落盘、收尾标记也写了，于是下一次冷启
     *    **不判脏**——报告有、玩家那边却等于闪退无声。[beginProcess] 认那条 `stopped crash=`
     *    标记，把**上一轮那份现成的报告**接进同一个出口（本进程不再画任何东西）。
     */
    private var pendingExitNoticeFile: File? = null

    /** 取走 [pendingExitNoticeFile]（一次性：消费后置空，Activity 重建不会重复弹）。 */
    fun consumeDirtyExitReportFile(): File? {
        val file = pendingExitNoticeFile
        pendingExitNoticeFile = null
        return file
    }

    /**
     * 冷启动第一步：读上一轮的收尾状态，脏的就在返回前把「上次异常退出」报告落出去。
     *
     * 两个例外（都不落报告也不弹卡）：
     * - API 30+ 的系统退出记录明确判为「非缺陷类」（包更新 / 用户主动停止 / 权限变更 /
     *   ROM 管控判词）时只认账 —— 见函数体内那段注释与 [ExitForensics.isBenignExit]；
     * - 本进程**不是第一趟** onCreate（Activity 被重建而进程还活着）：盘上那行 `running`
     *   是这一轮自己的出生证，不是上一轮的死，见 [ColdStartOnceGate]。
     *
     * 必须在任何装载/解包之前调用，且**只调一次**（`MainActivity.onCreate`；重入由
     * [coldStartGate] 挡住，不许靠调用方自觉）。
     * 报告走 [ErrorReportCollector] 的既有出口（含 [ErrorReportUploader] 的补发），
     * 所以这一步顺带量到「玩家手机到底能不能把报告发到端点」——那条一加玩家的
     * 「这次没有报错」有两种解释（真没抛异常 / 抛了但发不出去），这份报告能分开它们。
     *
     * @return true = 检测到上一轮是脏退出（调用方可据此在屏上少说一句「首次启动」）
     */
    fun beginProcess(context: Context): Boolean {
        val dir = dir(context)
        val state = runCatching { stateFile(dir).readText() }.getOrNull()
        val priorTrail = runCatching { trailFile(dir).readLines().filter { it.isNotBlank() } }
            .getOrDefault(emptyList())
        val firstColdStart = coldStartGate.enter()
        val dirty = firstColdStart && RunTrail.isDirtyExit(state)
        if (!firstColdStart) {
            // 判据读的那行 `running` 是本进程第一趟 onCreate 自己写的 ⇒ 这一趟什么都不判。
            // 只钉日志：漏判的成本是「少一张卡」，误判的成本是玩家在**没死过**的一局里
            // 每次切前后台都看见一张「上一次游戏异常结束」——配置变更那一跳 `onStop`
            // 故意不写 `background`（见 MainActivity.onStop 的注释），第二趟读到自己的
            // `running` 就是这么误判的。同进程两发 `boot` 的实形见 [ColdStartOnceGate] 的
            // 出处段（那台三星 SM-G9860 的轨迹证明第二趟 onCreate 确实在跑）。
            Log.i(TAG, "本进程内再进冷启动入口（Activity 重建）：盘上的 running 是本轮自己写的，不判脏退出")
        } else {
            if (!dirty && RunTrail.isBackgroundDeath(state)) {
                // 后台被回收是系统的正常管控：不落报告、不弹卡，但留一行日志 —— 玩家报「闪退」时
                // 这一行能把「真死在屏前」与「退到后台被清掉」分开。
                Log.i(TAG, "上一轮在后台被系统回收（正常管控，不计异常退出）：${state?.trim()}")
            }
            if (dirty && !dirtyReported) {
                dirtyReported = true
                // 先认账再补报告：写 `consumed` 之后哪怕补报告这半截崩了，也不会每次冷启都重报。
                if (!RunTrail.writeStateFile(stateFile(dir), RunTrail.consumedState(state))) {
                    Log.w(TAG, "脏退出认账失败（下一次冷启会再报一份）")
                }
                // 系统能给出判词时先问一句：包更新 / 用户主动停止 / 权限变更 / ROM 的管控判词
                // 这些都写明了「非缺陷类退出」，再落一份「上次异常退出」只会让端点台账自相矛盾，
                // 也会在下次冷启弹一张不该弹的卡（2026-11-04 端点报告 vivo V2199A 的
                // PACKAGE_UPDATED、以及 0.2.34~0.2.35 那批 OTHER(13) 的 o-stop/o-kill）。
                // 低版本（API < 30）读不到判词，维持旧口径照报。
                val priorExit = runCatching { ErrorReportCollector.latestExitRow(context) }.getOrNull()
                if (priorExit != null && ExitForensics.isBenignExit(priorExit)) {
                    Log.i(
                        TAG,
                        "上一轮退出系统判为非缺陷（${ExitForensics.reasonName(priorExit.reasonCode)}" +
                            "），只认账、不落「上次异常退出」报告",
                    )
                } else {
                    runCatching {
                        ErrorReportCollector.collect(
                            context,
                            null,
                            "上次异常退出",
                            null,
                            RunTrail.dirtyReportLines(state, priorTrail),
                            // 显式 null：这一份报的是**上一个进程**的死，本进程刚起、心跳还没跑开，
                            // 拿它去解释上一轮的死是假证 —— 小节会照实写「未采到」。
                            null,
                        )
                    }.onFailure { Log.w(TAG, "脏退出报告落盘失败", it) }
                        .getOrNull()
                        ?.let { pendingExitNoticeFile = it }
                    // 端点未配置时这是第一行就返回的 no-op。
                    runCatching { ErrorReportUploader.uploadPendingAsync(context) }
                }
            }
            if (!dirty) {
                surfaceCrashNotice(context, state)
            }
        }
        if (!RunTrail.writeStateFile(stateFile(dir), RunTrail.runningState(BuildConfig.eratwBuildStamp))) {
            // 首启时这一行是本类唯一会建目录的地方；写不成 = 整套脏退出采集静默失效，
            // 2026-10-30 模拟器实测正是这样哑掉的（目录没建，writeText 抛 ENOENT 被 runCatching 吞）。
            Log.w(TAG, "运行标记写入失败 ⇒ 本轮的死因采不到")
        }
        // 冷启立刻钉一发（`boot`）：轨迹原先只从「第一个回合边界」才有内容，死得更早
        // （装载期 / 开局演出）那一轮在盘上就是 **0 行**，报告只能写「一条都没有」——
        // 2026-10-31 收件箱里那份三星 SM-G9860 正是这种读不出东西的形状。这一行给出
        // 装载前的基线（含系统可用内存），至少能分清「根本没跑起来」与「跑起来才涨上去」。
        forceSample(context, "boot")
        return dirty
    }

    /**
     * 崩的那一轮给玩家补一句：认 `stopped crash=… report=…` 那条收尾标记，把**上一轮已经
     * 落好的那份崩溃报告**接到既有提示卡上（[pendingExitNoticeFile]）。
     *
     * ## 为什么是「下一次冷启补显」而不是「崩的当场弹一句」
     *
     * 兜底处理器跑在崩溃线程上，那一刻能用的只有已经在内存里的东西：本仓的崩溃现场十有八九
     * 是 `OutOfMemoryError`（2026-11-03 端点 3 份退化报告全是），再叠一层 Compose/Dialog 或
     * 任何视图树操作就是拿「报告写不写得出来」去赌；`exit` 之后主线程的消息队列也不再转，
     * 当场画的屏没人保证它上过屏。冷启动那一趟堆是干净的（本轮 Java 堆读数 5~8 MiB），
     * 卡片、复制、分享三条出口都是已经跑通过的路，一行都不必新画。
     *
     * 原版 PC 没有对应物：`reference/emuera-cs` 全仓查不到任何
     * `AppDomain.CurrentDomain.UnhandledException` / `Application.ThreadException` 注册
     * （2026-11-04 grep 零命中），PC 上崩了是 .NET/WinForms 自己的异常对话框，eraTW 只给
     * 用户动作配 `Dialog.Show`（`Dialog.cs:11-27`）。**这一条因此是移植侧新增体验，
     * 不是语义对齐项**（ADR-005 管不到它）。
     *
     * 只认「兜底处理器写了报告并认领了」那一形；`onDestroy` / `exitButton` 这些收尾不沾这张卡，
     * 报告文件不在盘上（被滚动清理 / 从未写成功）也一句不弹。
     */
    private fun surfaceCrashNotice(context: Context, state: String?) {
        val name = RunTrail.crashReportName(state) ?: return
        runCatching {
            val file = File(ErrorReportCollector.reportDir(context), name)
            if (file.isFile) {
                pendingExitNoticeFile = file
                Log.i(TAG, "上一轮是崩溃认领的死（$name）：给玩家补一次报告提示")
            }
        }.onFailure { Log.w(TAG, "崩溃提示补显失败（不影响游戏）", it) }
    }

    /** 已知的收尾点（`onDestroy` / 崩溃兜底 / 「结束应用」）：写下去，下一次冷启就不算脏退出。 */
    fun markStopped(context: Context, reason: String) {
        if (!RunTrail.writeStateFile(stateFile(dir(context)), RunTrail.stoppedState(reason))) {
            Log.w(TAG, "收尾标记写入失败（下一次冷启会多报一份脏退出，可接受）")
        }
    }

    /**
     * 「重启应用」那一族硬退（`Runtime.exit(0)`）之前的认领。
     *
     * 为什么不能像 [markStopped] 一样无条件写：`exit(0)` 在系统那边记的是
     * `EXIT_SELF(1) 状态=code=0 备注=（无）`，端点里 14 份这个形状的「上次异常退出」与
     * **没写这一发**的硬退同形（0.2.34~0.2.39，跨 vivo / 华为 / 小米 / blackshark；
     * 全部 `exit(0)` 点里只剩失败屏那颗「重启应用」没写认账标记，见 `MainActivity` 的调用点）。
     * 但本进程**没走过**冷启动取证时（`beginProcess` 之前一步就抛了）盘上那行可能还是
     * **上一轮**的 `running` —— 那时候写这一行等于把一份没人认领的死因抹掉，是这套采集里
     * 最坏的一种错（漏报比误报难查）。所以闸门没落下就一个字都不写。
     */
    fun markRelaunchClaim(context: Context) {
        if (!coldStartGate.hasEntered()) {
            Log.i(TAG, "本进程没走过冷启动取证：不抹上一轮的状态，重启后照旧由下一次冷启判定")
            return
        }
        markStopped(context, "restartAfterFailure")
    }

    /**
     * 界面切到后台（`onStop`）：改成 `background`。
     *
     * 从这一行落盘起，系统再怎么回收进程（lmkd / OEM 清理 / 用户划掉最近任务）都只是
     * **正常管控**，下一次冷启不再当成「异常退出」—— 那正是「每次打开都弹卡」的来源。
     * 写失败只 Log：退回旧口径（可能多弹一次），绝不拦游戏。
     */
    fun markBackground(context: Context) {
        if (!RunTrail.writeStateFile(stateFile(dir(context)), RunTrail.backgroundState())) {
            Log.w(TAG, "后台标记写入失败（下一次冷启可能多弹一次异常退出，可接受）")
        }
    }

    /** 回到前台（`onStart`）：重新认领成 `running`，否则「回前台后真的死掉」会被误当后台回收。 */
    fun markForeground(context: Context) {
        if (!RunTrail.writeStateFile(stateFile(dir(context)), RunTrail.runningState(BuildConfig.eratwBuildStamp))) {
            Log.w(TAG, "运行标记写入失败 ⇒ 本轮的死因采不到")
        }
    }

    /**
     * 回合边界采样（VM 线程调，成本 = 三次系统调用 + 一次入队）。
     *
     * 落盘由 [RunTrail.shouldWrite] 单点裁决（内存里的环形表不节流）。
     *
     * @param heartbeat 三路心跳（**只读**，见 [HeartbeatJudgment.trailSuffix]）：带上它，
     *   轨迹每一行末尾就多一段 `hb=ui:…/publish:…/vm:…`。这一路是「死在屏前」唯一能把
     *   「谁先停」带到下一次冷启动报告里的东西 —— 那份报告落盘时活读数早没了，只有盘上
     *   的最后一行还在。缺省 null = 与本改动前逐字相同（`GameHost` 那侧的调用点由组长接线）。
     */
    fun sampleTurn(context: Context, phase: String, heartbeat: Heartbeat? = null) {
        appendAndMaybeWrite(context, SystemClock.elapsedRealtime(), phaseWithHeartbeat(phase, heartbeat), force = false)
    }

    /**
     * 强制钉一发并立刻落盘 —— `onTrimMemory` 一到就用。
     *
     * 系统给这个回调的意思就是「再不收就来不及了」，这一发是区分「Java 堆贴顶」与
     * 「被 OEM 后台管控无差别杀掉」的决定性读数。
     *
     * @param heartbeat 同 [sampleTurn]：只读地把三路距今值挤进行尾。
     */
    fun forceSample(context: Context, phase: String, heartbeat: Heartbeat? = null) {
        appendAndMaybeWrite(context, SystemClock.elapsedRealtime(), phaseWithHeartbeat(phase, heartbeat), true)
    }

    /**
     * 把心跳读数接到阶段名后面（纯判式在 [HeartbeatJudgment.trailSuffix]，本函数只做拼接）。
     *
     * 只读、绝不抛：心跳实例内部是三个 volatile 读 + 三次装箱，取值失败也只会写成 `hb=-`。
     */
    private fun phaseWithHeartbeat(phase: String, heartbeat: Heartbeat?): String {
        if (heartbeat == null) return phase
        val nowMs = SystemClock.elapsedRealtime()
        return phase + HeartbeatJudgment.trailSuffix(nowMs, heartbeat.snapshot())
    }

    /** DevTools / 报告用的当前轨迹（旧在前）。 */
    fun trailLines(): List<String> = synchronized(lock) { trail.toList() }

    /**
     * 轨迹末行（崩前最后一发）；表空 = null。
     *
     * 为什么单开一支而不让调用方去切 [trailLines]：唯一的调用点是最小退化报告，
     * 那一刻的堆就是**导致完整报告写不出来**的那块堆，`trail.toList()` 那枚 64 元素的快照
     * 本身就是一次可能失败的分配，而退化报告要的是最后一行。这一支零集合分配 ——
     * `lastOrNull()` 只回一个已经在表里的引用，拿不到就 null，一行都不硬写。
     */
    fun lastTrailLine(): String? = synchronized(lock) { trail.lastOrNull() }

    /**
     * 当前系统可用内存（MiB）；读不到 -1。
     *
     * 与轨迹行里的 `sys=` 同一口径、同一 1 s 缓存（回合边界的水位判据与埋点读到的是同一个数，
     * 报告里对不上账那种事不会发生）。
     */
    fun systemAvailMiB(): Long = sysAvailMiB(SystemClock.elapsedRealtime())

    /** 本机低内存线（MiB）；查不到 -1。每进程一次 binder（与轨迹行里的 `lmk=` 同源）。 */
    fun lowMemoryLineMiB(context: Context): Long = lmkThresholdMiB(context)

    private fun appendAndMaybeWrite(context: Context, nowMs: Long, phase: String, force: Boolean) {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / MIB
        val max = runtime.maxMemory() / MIB
        val native = runCatching { Debug.getNativeHeapAllocatedSize() / MIB }.getOrDefault(0L)
        val elapsed = nowMs - originMs
        val line = RunTrail.formatLine(
            elapsed, used, max, native, phase,
            sysAvailMiB = sysAvailMiB(nowMs),
            lmkThresholdMiB = lmkThresholdMiB(context),
        )
        val due: Boolean
        synchronized(lock) {
            trail.addLast(line)
            appendSeq++
            while (trail.size > RunTrail.MAX_LINES) trail.removeFirst()
            due = RunTrail.shouldWrite(elapsed, lastWriteMs, force)
            if (due) lastWriteMs = elapsed
        }
        if (!due) return
        // 快照与序号在同一次 lock 临界区里取（二者永远配对）；出锁后再进写侧闸门。
        val seq: Long
        val snapshot: List<String>
        synchronized(lock) {
            seq = appendSeq
            snapshot = trail.toList()
        }
        // 落盘只走 trailGate：写者之间专用 monitor + 序号跳旧快照，lock 的临界区里没有任何文件写。
        if (!trailGate.write(seq, snapshot, trailFile(dir(context)))) {
            Log.w(TAG, "运行轨迹落盘失败（不影响游戏）")
        }
    }

    /**
     * 死前系统还剩多少内存（MiB）；读不到给 -1（那一列整段消失，绝不写假读数）。
     *
     * 为什么不用 `ActivityManager.getMemoryInfo()`：那是 binder 往返，回合边界可以每几十毫秒
     * 一次，代价不成比例；`/proc/meminfo` 是一次 open+read+扫一行，再套 [SYS_READ_MIN_INTERVAL_MS]
     * 缓存，成本可忽略，而精度对「死前还剩多少」完全够。
     *
     * 线程：任意线程（UI 的 onTrimMemory 与 VM 的回合边界都会走到），缓存读写在 [lock] 内。
     */
    private fun sysAvailMiB(nowMs: Long): Long {
        synchronized(lock) {
            if (sysAvailCacheAtMs >= 0L && nowMs - sysAvailCacheAtMs < SYS_READ_MIN_INTERVAL_MS) {
                return sysAvailCacheMiB
            }
        }
        val value = runCatching { File(PROC_MEMINFO).readText() }
            .getOrNull()
            ?.let(RunTrail::parseMemAvailableMiB)
            ?: -1L
        synchronized(lock) {
            sysAvailCacheAtMs = nowMs
            sysAvailCacheMiB = value
        }
        return value
    }

    /**
     * 本机「低内存」线（[ActivityManager.MemoryInfo.threshold]，MiB）；查不到给 -1。
     *
     * 这是系统自己给的判据（`lowMemory = availMem < threshold`），拿来和 [sysAvailMiB] 比，
     * 就能说清「死的时候系统是不是已经在低内存状态」—— 而不是我们拍一个绝对 MiB 门槛。
     * 每进程只查一次：同一台机器这一行基本不动，而它只在第一条采样时才需要。
     */
    private fun lmkThresholdMiB(context: Context): Long {
        if (lmkThresholdQueried) return lmkThresholdMiB
        lmkThresholdQueried = true
        lmkThresholdMiB = runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.threshold / MIB
        }.getOrDefault(-1L)
        return lmkThresholdMiB
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun stateFile(dir: File): File = File(dir, RunTrail.STATE_FILE)

    private fun trailFile(dir: File): File = File(dir, RunTrail.TRAIL_FILE)

    private const val DIR_NAME = "eratw-run"
}

/**
 * 轨迹落盘的写侧闸门：**写者之间专用一把 monitor** + **序号跳旧快照**。
 *
 * ## 它修的是什么
 *
 * [RunWatch.appendAndMaybeWrite] 的取快照与覆盖写盘发生在环形表锁之外：UI 线程
 * （`forceSample`：onPause / onTrimMemory / boot）与 VM 线程（`sampleTurn` 回合边界、
 * GameHost 的 turn.* 强制钉发）能各自带着 `due` 走出来并发写同一个 trail.txt。
 * 覆盖写的先后纯看调度 ⇒ **旧快照能盖掉新快照**——撕掉的正是轨迹末行那句
 * `pause` / `stopped` 一类的收尾线索，[RunTrail.dirtyReportLines] 靠它分清「死在屏前」
 * 与「后台被回收」，判据就此失声。
 *
 * ## 口径（组长裁定，勿自创第三条路）
 *
 * 1. [writeLock] 只在写者之间竞争：**不复用** [RunWatch] 的环形表锁，更不许把写盘并进
 *    环形表锁——那会让 UI 钉行等 VM 的 4 KB 磁盘写，把观察性欠账换成新的 UI 卡顿。
 * 2. 每次落盘带一个单调序号（环形表追加计数）；进入 [writeLock] 后先比对「已落盘的最新
 *    序号」，**已有更新的快照落成功过就跳过这一发**（跳过返回 true：不是失败，不打 Log.w）。
 * 3. 落盘失败（sink 返回 false）不推进序号：下一发更新的快照照常写。
 * 4. [writeLock] 内除了 sink 什么都不持有——不碰 core 侧任何状态锁；异常不外抛（sink
 *    自身必须 `runCatching`，本仓口径见 [RunTrail.writeTrailFile]）。
 *
 * 抽成独立类（而非 [RunWatch] 的私有方法）只为可测：[RunWatch] 本体绑死 SystemClock /
 * Context，JVM 单测进不去；判式与闸门留在无 Android 运行期依赖的一侧（同 [RunTrail] 的理由）。
 */
internal class TrailWriteGate(private val sink: (File, List<String>) -> Boolean) {

    /** 写侧专用 monitor：只有落盘者之间竞争。 */
    private val writeLock = Any()

    /** 已经**成功**落盘的快照序号；-1 = 还没落过。只在 [writeLock] 内读写。 */
    private var lastWrittenSeq = -1L

    /**
     * 落一份快照。
     *
     * @param seq 本快照的序号（取快照那一刻的环形表追加计数）
     * @param snapshot 轨迹全文（旧在前），整份覆盖写
     * @return true = 落成功或按序号跳过（旧快照迟到）；false = sink 写失败，调用方只 Log
     */
    fun write(seq: Long, snapshot: List<String>, file: File): Boolean {
        synchronized(writeLock) {
            if (seq <= lastWrittenSeq) return true
            val ok = sink(file, snapshot)
            if (ok) lastWrittenSeq = seq
            return ok
        }
    }
}

/**
 * 冷启动取证（[RunWatch.beginProcess] 的脏退出判定）的**同进程一次性**闸门。
 *
 * ## 它修的是什么
 *
 * 脏退出的判据是「盘上 `state.txt` 的首词是不是 `running`」，而写下那行 `running` 的正是
 * 本进程自己。一趟进程里 `onCreate` 可以走**多**趟：Activity 被销毁重建而进程还活着
 * （配置变更那一跳 [RunTrail.MARK_BACKGROUND] 是故意不写的，见 `MainActivity.onStop` 的
 * 注释「那一跳紧接着就是同进程的新实例，写 `background` 会把状态改回后台」）。
 * 第二趟再走一遍判定，读到的就是**还活着的这一轮**
 * 的出生证，于是给一个没死过的进程补一份「上次异常退出」，玩家在游戏正开着的时候看见一张
 * 「上一次游戏在运行中异常结束（闪退或被系统强制结束）」的卡。
 *
 * 装机侧的确证程度（写清楚，不冒充）：2026-11-04 那台三星 SM-G9860（API 28，读不到系统
 * 退出判词）两分半里 5 份「上次异常退出」，其中两份的运行轨迹里有同进程的**两发**
 * `boot`（`t=78ms boot` → `t=18068ms pause` → `t=18828ms boot`，`t=` 是距**进程**起点的
 * 毫秒、第二发那行 Java 堆已经 39MiB ⇒ 不是新进程）——**第二趟 onCreate 真的在跑**。
 * 但那 5 份正文的「本轮 Java 堆」全是 5MiB（都是新进程写的）⇒ 这一形在端点里**还没有
 * 误报样本**：误报那一支要走配置变更重建才成立（退到后台那一趟 `onStop` 已经把状态写成
 * `background`，第二趟读到它就不判脏）。判式在代码上是确定的洞，真机复现登记在交付。
 *
 * ## 闸门只管判定，不管落盘
 *
 * 第二趟仍然重写 `running`（回到前台本来就要重新认领）并仍然钉一发 `boot`：轨迹里留下
 * 「这一趟重建过」才是取证要的东西，摘掉它反而把这一形读不出来了。
 *
 * 抽成独立类只为可测：[RunWatch] 本体绑死 Context / SystemClock，JVM 单测进不去
 * （口径同 [TrailWriteGate] 与 [RunTrail]）。
 */
internal class ColdStartOnceGate {

    private var entered = false

    /** 第一趟 true（同时落下闸门），之后的趟次一律 false。 */
    @Synchronized
    fun enter(): Boolean = if (entered) false else { entered = true; true }

    /** 当前是否已经走过（只读；第二趟的日志与单测看这一枚）。 */
    @Synchronized
    fun hasEntered(): Boolean = entered
}

/**
 * [RunWatch] 的纯判式与纯格式化 —— 无 Android 运行期依赖，`:app` 的 JVM 单测直接锁死。
 *
 * 抽出来的理由与 [failureActions] 同一口径：这些判断是「上一次到底怎么死的」的唯一裁决点，
 * 写错一次就会要么每次冷启误报、要么永远不报，而这两种都只能靠装机才发现。
 */
internal object RunTrail {

    /** 收尾标记文件（一行状态）。 */
    const val STATE_FILE = "state.txt"

    /** 运行轨迹文件（滚动，只留最后 [MAX_LINES] 行）。 */
    const val TRAIL_FILE = "trail.txt"

    /** 轨迹保留行数。一行约 60 B ⇒ 上限约 4 KB，报告体积不受影响。 */
    const val MAX_LINES = 64

    /** 两次落盘的最小间隔（毫秒）：采样不节流，落盘节流。 */
    const val WRITE_MIN_INTERVAL_MS = 2_000L

    /** 主动回收延迟资源图的堆水位（已用 / 上限，百分比）。 */
    const val RELEASE_AT_PERCENT = 80L

    /** 主动回收的冷却（毫秒）：见 [shouldReleaseImagePixels]。 */
    const val RELEASE_COOLDOWN_MS = 30_000L

    /**
     * 「连画过的缓冲也释放」的堆水位（已用 / 上限，百分比）：见 [shouldReleaseUnusedGraphics]。
     *
     * 比 [RELEASE_AT_PERCENT] 高一大截是刻意的：这一刀丢的是**脚本画上去的内容**
     * （重解不回来），只在「不丢就 OOM」的位置才值。
     */
    const val RELEASE_UNUSED_AT_PERCENT = 92L

    /**
     * 释放「画过的缓冲」的冷却（毫秒）。
     *
     * 比 [RELEASE_COOLDOWN_MS] 长一倍：那一刀随时可以重来（重解必得同一张图），
     * 这一刀每来一次都可能让脚本手里的 gid 变成「未创建」。
     */
    const val RELEASE_UNUSED_COOLDOWN_MS = 60_000L

    /**
     * 「已经站在悬崖边」的堆水位（已用 / 上限，百分比）：到这个水位**两档回收都无视冷却**。
     *
     * ## 为什么需要这一档（2026-10-31 端点报告实证）
     *
     * 收件箱里 11 份「应用崩溃」全是 `OutOfMemoryError`，跨 6 种机型
     * （HONOR PGT-AN00 / ALT-AN00、blackshark SKW-A0、vivo V2313A、HUAWEI ADY-AL00），
     * 崩溃点分散在 `MainActivity.refreshShell`、`CanvasConsoleView.metricOf`、
     * `LineFolder.group` —— 说明不是某个代码点的错，而是**堆已经顶满**，谁先分配谁死。
     * 读数形状是 `Failed to allocate a 16 byte allocation with 4008 free bytes and
     * 3KB until OOM`：连几十字节都拿不出来。
     *
     * 旧判据在 92% 才收，收一次后锁 60 s。而 92%→100% 只有 8%（512 MiB 挡约 40 MiB）
     * 的余量，在「打开角色列表 / 切场景」这种一口气加载十几张图的场景里**几秒就填满**
     * —— 冷却期还没过就已经 OOM。冷却的初衷是「别让回收动作自己变成卡顿源」，
     * 但到了 95% 以上，卡一下远好于进程消失。
     *
     * 取 95 而不是更低：92 那档本身已经很高，再降会把「正常加载峰值」也当成悬崖，
     * 让玩家在每页都看见一次回收抖动。95% 只留给真正无路可退的时刻。
     */
    const val RELEASE_PANIC_PERCENT = 95L

    /**
     * 报告里附带的轨迹尾行数。
     *
     * 2026-10-31 从 24 抬到 [MAX_LINES] 的 64：抬之前的两类死读不出形状 ——
     * 一次「打开角色列表 → OOM」只要 20 来行就走到头，24 行够用；但**慢慢涨上去**的那类
     * （玩几十分钟，每页涨一两 MiB）要几天前的基线才看得出斜率，24 行只剩死前的斜率尾巴。
     * 64 行约 4 KB，报告体积与端点配额（256 KiB）都还差两个数量级。
     */
    private const val REPORT_TAIL_LINES = 64

    /** 状态文件首词 = 本进程正在跑（没有任何收尾点写过它 ⇒ 死在无人认领处）。 */
    const val MARK_RUNNING = "running"

    /** 状态文件首词 = 已认账（脏退出已经报过一次，不再重复报）。 */
    const val MARK_CONSUMED = "consumed"

    /** 状态文件首词 = 走到了已知收尾点。 */
    const val MARK_STOPPED = "stopped"

    /** 状态文件首词 = 界面已不可见（`onStop`）；之后被系统回收是正常管控，不算异常退出。 */
    const val MARK_BACKGROUND = "background"

    /** 脏退出判据：只有首词是 [MARK_RUNNING] 才算。空文件/读不出来一律不算（宁缺勿误报）。 */
    fun isDirtyExit(state: String?): Boolean =
        state?.trim()?.startsWith(MARK_RUNNING) == true

    /**
     * 「上一轮在后台被回收」判据：首词是 [MARK_BACKGROUND]。
     *
     * 与 [isDirtyExit] 互斥：这一态**不是**异常退出（系统正常回收），冷启只记日志，
     * 既不落报告也不弹卡。写错的后果就是玩家每次打开都看见一次「上次异常退出」。
     */
    fun isBackgroundDeath(state: String?): Boolean =
        state?.trim()?.startsWith(MARK_BACKGROUND) == true

    /** 切后台时写的那一行（带上墙钟，报告/日志里能看出在后台待了多久）。 */
    fun backgroundState(nowMs: Long = System.currentTimeMillis()): String =
        "$MARK_BACKGROUND since=$nowMs"

    /** 起进程时写的那一行（带上构建戳，便于把报告对回具体 APK）。 */
    fun runningState(buildStamp: String): String = "$MARK_RUNNING build=$buildStamp"

    /** 认账脏退出时改写的那一行。 */
    fun consumedState(priorState: String?): String =
        "$MARK_CONSUMED prior=${priorState?.trim()?.replace('\n', ' ') ?: "-"}"

    /** 收尾时写的那一行（`reason` = onDestroy / crash / exitButton …）。 */
    fun stoppedState(reason: String): String = "$MARK_STOPPED $reason"

    /** 报告文件名的公共前缀（[ErrorReportCollector] 的两条命名路都带它：常规名与同秒避让名）。 */
    const val REPORT_FILE_PREFIX = "eratw-report-"

    /**
     * 崩溃兜底认领收尾时的那条 reason：`crash=<异常类名> report=<报告文件名>`。
     *
     * 为什么要把文件名带进状态行：崩的当场那份报告是**上一个进程**写的，下一次冷启动想给玩家
     * 补一句提示，除了这条路没有任何办法认出「是哪一份」（报告目录里滚动留着几十份）。
     * 异常类名压平换行：状态文件按首词判状态，多一行就整条判读失声（口径同
     * [consumedState] 对状态原文做的替换）。
     */
    fun crashStopReason(errorName: String, reportFileName: String): String =
        "crash=${errorName.trim().replace('\n', ' ')} report=$reportFileName"

    /**
     * 收尾标记是不是「崩溃兜底写了报告并认领了」的那一形；是 ⇒ 交回那份报告的文件名。
     *
     * 三道闸，缺一律 null（宁可不弹）：
     * 1. 首词必须是 [MARK_STOPPED]——`running` 走脏退出那条路、`background` 是正常回收；
     * 2. reason 以 `crash=` 开头——`onDestroy` / `exitButton` 这些收尾不沾这张卡；
     * 3. `report=` 后面的名字必须是 [REPORT_FILE_PREFIX] 开头且不含路径分隔符与 `..`：
     *    状态文件躺在应用私有目录里、看着是人家自己写的，但拿盘上读来的字符串直接拼 `File`
     *    是老事故点，这一行不省。
     */
    fun crashReportName(state: String?): String? {
        val line = state?.trim()?.takeIf { it.startsWith("$MARK_STOPPED ") } ?: return null
        val reason = line.removePrefix("$MARK_STOPPED ").trim()
        if (!reason.startsWith("crash=")) return null
        val name = reason.substringAfter("report=", "").trim()
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return name.takeIf { it.startsWith(REPORT_FILE_PREFIX) }
    }

    /**
     * 长页自救口（指令循环周期检查点）的最小间隔（毫秒）。
     *
     * 检查点本身每 ≈30 ms 到一发（`Vm.SHUTDOWN_CHECK_MASK` 同频），但一发要读三次
     * 运行时内存 + 可能走一遍资源表 ⇒ 再便宜也不该 30 ms 一次。250 ms = 一秒四发，
     * 相对「一页 58 秒」是四次机会，相对 2 ms 的正常页根本不会走到判据。
     */
    const val MID_PAGE_CHECK_INTERVAL_MS = 250L

    /** [sinceLastMs] 为负 = 还没跑过（第一发必走）。 */
    fun shouldMidPageCheck(sinceLastMs: Long): Boolean =
        sinceLastMs < 0L || sinceLastMs >= MID_PAGE_CHECK_INTERVAL_MS

    /**
     * 「一页算了多久才值得钉一发」的门槛（毫秒）。
     *
     * 收件箱里同一类页面平时 `turn 2ms`、堆贴顶时 `turn 58515ms` ⇒ 5 秒以上就是玩家嘴里的
     * 「卡死」；低于它钉发只会把 64 行的滚动轨迹灌满噪声，把真正临死前的那几行挤掉。
     */
    const val LONG_TURN_MS = 5_000L

    /** 同一页内两次钉发的最小间隔（毫秒）：让「还在卡、卡到第几秒」成为一条斜线而不是一个点。 */
    const val LONG_TURN_PIN_COOLDOWN_MS = 5_000L

    /**
     * 长页要不要钉一发轨迹（页还没走完时，报告里本来只有页尾那一条 `turn Nms`）。
     *
     * @param pageElapsedMs 本次提交到现在已经过了多少毫秒（0 / 负 = 当前没有在飞的页）
     * @param sinceLastPinMs 距上一次钉发的毫秒；负数 = 本页还没钉过
     */
    fun shouldPinLongTurn(pageElapsedMs: Long, sinceLastPinMs: Long): Boolean =
        pageElapsedMs >= LONG_TURN_MS && (sinceLastPinMs < 0L || sinceLastPinMs >= LONG_TURN_PIN_COOLDOWN_MS)

    /**
     * 这一行要不要落盘。
     *
     * @param elapsedMs 本次采样距进程起点的毫秒
     * @param lastWriteMs 上一次**真正落盘**那次的同样基准毫秒；负数 = 还没落过（首行必写）
     * @param force 内存压力等关键节点，无条件写
     */
    fun shouldWrite(elapsedMs: Long, lastWriteMs: Long, force: Boolean): Boolean =
        force || lastWriteMs < 0L || elapsedMs - lastWriteMs >= WRITE_MIN_INTERVAL_MS

    /**
     * 要不要**主动**回收延迟资源图（不指望 OEM 发 `onTrimMemory`）。
     *
     * `onTrimMemory` 是标准入口，但 vivo / OPPO 这类 ROM 在真把进程杀掉之前**不一定**发它
     * （玩家「玩了一会儿就闪退、什么报错都没有」正是这个形状）。于是回合边界补一条只看水位
     * 的判据：已用堆摸到上限的 [RELEASE_AT_PERCENT]% 就收一次 —— 收的是「重解必得同一张图」
     * 的那一坨（`GraphicsBuffer.evictDeferred`），宁可早收，也不要晚到 OOM。
     *
     * 冷却 [RELEASE_COOLDOWN_MS]：一旦贴顶，这条判据每个回合都成立，没有冷却就是
     * 每翻一页白走一遍资源表。
     */
    fun shouldReleaseImagePixels(usedMiB: Long, maxMiB: Long, sinceLastAttemptMs: Long): Boolean =
        maxMiB > 0L &&
            usedMiB * 100L >= maxMiB * RELEASE_AT_PERCENT &&
            (sinceLastAttemptMs >= RELEASE_COOLDOWN_MS ||
                usedMiB * 100L >= maxMiB * RELEASE_PANIC_PERCENT)

    /**
     * 要不要**连画过的缓冲一起**释放（`GraphicsPool.releaseUnused`）。
     *
     * 与 [shouldReleaseImagePixels] 是两档，不是同一件事：
     * - 那一档收的是「重解必得同一张图」的（延迟装载的父图与工作表），收了没有任何副作用；
     * - 这一档收的是**脚本画上去的**合成缓冲，收了就是「那个 gid 变成未创建」。
     *
     * 为什么还是要做：本语料的 `@GET_EMPTY_G`（`グラフィック特殊効果.ERB:254-267`）靠
     * `GCREATED==0` 复用 gid，而唯一会 dispose 的 `@RM_全部リリース` 被作者注释掉了
     * （`ResourceManager.ERB:291-302`，理由是不想卡顿）⇒ 中途图只增不减，一路涨到
     * `THROW 图像过多`。语料自己都写了「必要时全部释放」这个函数，这里只是给它一个
     * 真正的触发条件：**堆摸到 [RELEASE_UNUSED_AT_PERCENT]%**。
     *
     * 取舍写清楚：可能一帧空白 vs 进程被系统杀掉。后者是玩家报的那个「什么都没发生就没了」。
     */
    fun shouldReleaseUnusedGraphics(usedMiB: Long, maxMiB: Long, sinceLastAttemptMs: Long): Boolean =
        maxMiB > 0L &&
            usedMiB * 100L >= maxMiB * RELEASE_UNUSED_AT_PERCENT &&
            (sinceLastAttemptMs >= RELEASE_UNUSED_COOLDOWN_MS ||
                usedMiB * 100L >= maxMiB * RELEASE_PANIC_PERCENT)

    /**
     * 要不要把**合成图的具名 Sprite** 也摘掉（`SpritePool.evictCompositedSpritesWithinBudget`，
     * fix-hiresmem 车道的第三档）。两个条件同时成立才动手：
     *
     * 1. **像素驻留还在预算之上**：前两档已经把它们够得着的东西收干净了，还剩 250–309 MiB
     *    挂在语料自己的 `SPRITECREATE OUT_STAND, GID` 上（2026-11-04 收件箱 91 份的读数，
     *    判词见那个方法的 KDoc）；预算内就没有理由动玩家的图。
     * 2. **堆真的贴顶**（≥ [RELEASE_AT_PERCENT]%，即那一档开始回收的水位）：只看驻留不看堆，
     *    正常游玩（立绘刚合成完、堆还空）也会逐出，玩家白担一次重合成 —— 而这一档存在的唯一
     *    理由是「贴顶会被系统静默杀掉」（`LOW_MEMORY` / `iAwareF[LowMem]` / `SIGABRT`，
     *    都不给 Java 异常）。
     *
     * [budgetBytes] 为 `Long.MAX_VALUE`（堆上限不可知 = 桌面装置与全部单测）时条件 1 恒不成立：
     * 装载、E2E、基准与桌面装置的行为逐字不变。这条不是兜底而是**必要的口径** —— 桌面跑不出
     * 512 MiB 挡的分配形状（`PIXEL_BUDGET_DIVISOR` 的 KDoc 在册 TODO(verify)）。
     */
    fun shouldEvictCompositedSprites(
        usedMiB: Long,
        maxMiB: Long,
        residentBytes: Long,
        budgetBytes: Long,
    ): Boolean = maxMiB > 0L &&
        residentBytes > budgetBytes &&
        usedMiB * 100L >= maxMiB * RELEASE_AT_PERCENT

    // ------------------------------------------------------------------
    // 进程口径水位（2026-11-08 OOM-A 波，CONTRACT-OOM-A §3）
    // ------------------------------------------------------------------

    /**
     * 进程总量水位（PSS / 估算预算，百分比）：[shouldReclaimForProcessFootprint] 的触发线。
     *
     * 取 70 而不是更高：[memoryLimiterBudgetMiB] 的预算优先取设备实读的厂商档位表
     * （[MemoryLimiterConfig.readDevice]，vendor → system），读不到才回落 AOSP 标准档；
     * 应用无法在运行期查询自身上限（官方明确的运行期查询缺口），实读表与平台
     * `am memory-limiter status` 的逐值对账仍待 Android 17 真机（TODO(verify)，现有设备
     * 全是 API 35）；估算上限本身带偏差，触发的又是屏上无感的第一档
     * （`releaseResourceImagePixels`，重解必得同一张图），早收的代价只是省下几次重解 ——
     * 而这一档要防的死法（Android 17 起按**进程总量**杀进程、无栈轨迹）比 Java 堆 OOM
     * 更不可诊断。宁可多收一次，不要估算误差吃掉余量。
     */
    const val PROCESS_FOOTPRINT_PERCENT = 70L

    /**
     * Android 17（API 37）MemoryLimiter 按设备 RAM 档位给**单个进程**定的软上限估算值
     * （MiB）；不适用的设备返回 null（调用方按「没有这一档」处理，判式恒 false）。
     *
     * ## 为什么是「估算」而不是真值
     *
     * MemoryLimiter 限制的是 cgroup v2 的 `memory.high`（匿名 RSS + swap），管的是**整个
     * 进程**（native 堆、位图、线程栈全算），与本仓全部自救判据看的 Java 堆（
     * `Runtime.maxMemory()`，largeHeap 档 512 MiB 封顶）是**两道独立的闸**；`largeHeap`
     * 与多进程拆分都绕不过它。官方明确：应用**无法在运行时查询**自己被分配的上限
     * （source.android.com/docs/core/perf/memory-limiter「Absence of runtime query APIs」），
     * 所以只能按 RAM 档位复刻平台标准档。2026-11-07 玩家报告 5783b90 的长会话
     * PSS≈1016 MiB：4 GB 档（估算上限 2048 MiB）前台约 50%，但**不可见档只有 1024 MiB**
     * —— 切后台即顶格，与报告里 `critical_mem_pressure` 的退出记录互证。
     *
     * ## 档位表出处与边界（2026-11-08 逐值核对）
     *
     * 下表的硬编码值复刻 Android 17 官方文档「标准平台限制」表
     * （source.android.com/docs/core/perf/memory-limiter；区间 [floor, ceiling) MiB），
     * 与 AOSP android-17.0.0_r1 `MemoryLimiter.java` 的判式口径一致（平台常量 MB = 1024×1024）：
     *
     * | RAM 档 | visible | notVisible |
     * |---|---|---|
     * | [3200, 4800)  | 2048  | 1024 |
     * | [4800, 6800)  | 4096  | 2048 |
     * | [6800, 9216)  | 5120  | 3072 |
     * | [9216, 14336) | 8192  | 4096 |
     * | [14336, 18432)| 10240 | 5120 |
     *
     * `< 3200` 或 `>= 18432`（MemoryLimiter 不适用）与 8 GB 档 notVisible=3072（≠ 5120÷2）
     * 都是表里的原文，不许「推算」。
     *
     * ## 厂商覆写档（OOM-B，2026-11-08）
     *
     * 上表只是**回落值**：平台允许厂商覆写档位表。AOSP android-17.0.0_r1 的运行期配置路径
     * 是 `CONFIG_PATH = "/vendor/etc/memory-limiter-config.xml"`（`Injector.configFile()` →
     * `getConfiguration(file, memTotal)`）；官方文档把标准配置文件标在
     * `/system/etc/memory-limiter-config.xml`。装配期先经 [MemoryLimiterConfig.readDevice]
     * 读设备实表（vendor → system），读到了就以实表为准（[config] 参数），选择规则 = 满足
     * `minimumRequiredMemTotal ≤ RAM` 的最大档（**不校验 `maximumAllowedMemTotal`**，跟随
     * 平台判式）、一档不满足 ⇒ null；读不到才用上表。避免厂商改了门槛的机型上预算偏高
     * （晚收 ⇒ 被 LMK 杀、无栈轨迹）或偏低（早收 ⇒ 多次重解）。
     *
     * TODO(verify)（卡点：无 Android 17 真机，现有设备全 API 35）：应用 UID 读 vendor 路径
     * 是否被 SELinux 拒绝；实读表与 `adb shell am memory-limiter status` 的
     * visibleMem/notVisibleMem 对账。
     *
     * @param visible 进程是否持有可见界面。回合边界只在游戏前台跑（VM 线程在转），
     *   生产接线恒传 true；notVisible 档供测试与将来的后台触发面用。
     * @param config 设备实读的档位表（[MemoryLimiterConfig.readDevice]）；null = 设备上没有
     *   可读配置，用上面的 AOSP 标准档硬表。
     */
    fun memoryLimiterBudgetMiB(
        totalRamMiB: Long,
        visible: Boolean,
        config: List<MemoryLimiterConfig.LimitSet>? = null,
    ): Long? {
        if (config != null) {
            val set = MemoryLimiterConfig.select(totalRamMiB, config) ?: return null
            return if (visible) set.memVisibleMiB else set.memNotVisibleMiB
        }
        if (totalRamMiB < 3_200L || totalRamMiB >= 18_432L) return null
        val visibleBudget = when {
            totalRamMiB < 4_800L -> 2_048L
            totalRamMiB < 6_800L -> 4_096L
            totalRamMiB < 9_216L -> 5_120L
            totalRamMiB < 14_336L -> 8_192L
            else -> 10_240L
        }
        if (visible) return visibleBudget
        return when {
            totalRamMiB < 4_800L -> 1_024L
            totalRamMiB < 6_800L -> 2_048L
            totalRamMiB < 9_216L -> 3_072L
            totalRamMiB < 14_336L -> 4_096L
            else -> 5_120L
        }
    }

    /**
     * 进程总量（PSS）贴到估算预算的 [PROCESS_FOOTPRINT_PERCENT]% 时，主动收一发
     * 「重解必得同一张图」的延迟图（与堆水位档同一个动作，屏上无感）。
     *
     * 为什么必须有这一档：现有全部自救判据（[shouldReleaseImagePixels] /
     * [shouldReleaseUnusedGraphics] / 系统压力档）看的都是 Java 堆或系统可用内存，
     * **没有任何一路看进程总量** —— 而 Android 17 起直接杀进程的是 MemoryLimiter 那道闸
     * （见 [memoryLimiterBudgetMiB]）。Java 堆还空着、PSS 却已贴线的形状完全可能
     * （native 位图缓存 + 文件映射都算进程量），旧判据对此全盲。
     *
     * @param pssMiB 本进程 PSS（`Debug.getPss()`，KiB→MiB 向下取整）；负数 = 读不到，恒 false
     * @param budgetMiB [memoryLimiterBudgetMiB] 的估算值；null/0 = 无这一档，恒 false
     */
    fun shouldReclaimForProcessFootprint(pssMiB: Long, budgetMiB: Long?): Boolean =
        budgetMiB != null && budgetMiB > 0L && pssMiB >= 0L &&
            pssMiB * 100L >= budgetMiB * PROCESS_FOOTPRINT_PERCENT

    /**
     * 系统压力档的冷却（毫秒）：与 [RELEASE_COOLDOWN_MS] 同值，但语义不同（这一档由**系统**
     * 的读数触发，不是我们的堆），故单独命名 —— 将来要分开调时不必先拆共用常量。
     */
    const val SYS_PRESSURE_COOLDOWN_MS = 30_000L

    /**
     * 系统已经处在「低内存」状态？判据照 Android 自己的定义（`MemoryInfo.lowMemory` =
     * `availMem < threshold`），两列都来自轨迹同款读数（见 [parseMemAvailableMiB] 与
     * `lmk=` 列）。
     *
     * 缺任何一半（读不到 `/proc/meminfo` / 查不到低内存线）一律 false：宁可不收，
     * 也不拿一个拍出来的门槛把「我们的映射崩了」当成系统压力。
     */
    fun isSystemLow(sysAvailMiB: Long, lmkThresholdMiB: Long): Boolean =
        sysAvailMiB >= 0L && lmkThresholdMiB > 0L && sysAvailMiB < lmkThresholdMiB

    /**
     * 系统已经**远远**跌破低内存线（可用不足那条线的一半）——「画过的缓冲」那一档的触发条件。
     *
     * 之所以比 [isSystemLow] 严格一倍：那一刀每来一次都可能让脚本手里的 gid 变成「未创建」。
     * 刚跌破线时还愿意相信系统自己能回收（页缓存、lmkd 先收后台），只有到了「一半都不剩」
     * 才承认：再不丢内容就是整进程消失。
     */
    fun isSystemCritical(sysAvailMiB: Long, lmkThresholdMiB: Long): Boolean =
        sysAvailMiB >= 0L && lmkThresholdMiB > 0L && sysAvailMiB * 2L < lmkThresholdMiB

    /**
     * 回合边界：系统跌破低内存线时，收一次「重解无损」的延迟图。
     *
     * 为什么要有这一条（`onTrimMemory` 之外的第二道闸）：vivo / OPPO 这类 ROM 在真杀进程
     * 之前**不一定**发 `onTrimMemory`（玩家报的「玩一会儿就没了」正是这个形状）；而系统
     * 可用内存是我们每个回合都能自己读到的。这一档只动「重解必得同一张图」的那部分，
     * 屏上无感，代价只是资源表一遍，冷却 [SYS_PRESSURE_COOLDOWN_MS]。
     */
    fun shouldReleaseImagePixelsForSystemPressure(
        sysAvailMiB: Long,
        lmkThresholdMiB: Long,
        sinceLastAttemptMs: Long,
    ): Boolean = isSystemLow(sysAvailMiB, lmkThresholdMiB) && sinceLastAttemptMs >= SYS_PRESSURE_COOLDOWN_MS

    /**
     * 回合边界：系统远远跌破低内存线时，连「画过的」合成缓冲一起收（`GraphicsPool.releaseUnused`）。
     *
     * 这是 [shouldReleaseUnusedGraphics]（堆水位 92%）的**系统口径孪生**：那一条在 14 份
     * 系统压力死里一次都没触发过（Java 堆只有 10%~79%），因为它们根本不是堆不够。
     * 触发面收得比 [shouldReleaseImagePixelsForSystemPressure] 更严（[isSystemCritical]），
     * 冷却也更长（[RELEASE_UNUSED_COOLDOWN_MS]）—— 丢的是脚本画上去、重解不回来的内容。
     */
    fun shouldReleaseUnusedForSystemPressure(
        sysAvailMiB: Long,
        lmkThresholdMiB: Long,
        sinceLastAttemptMs: Long,
    ): Boolean = isSystemCritical(sysAvailMiB, lmkThresholdMiB) && sinceLastAttemptMs >= RELEASE_UNUSED_COOLDOWN_MS

    /**
     * 从 `/proc/meminfo` 全文取 `MemAvailable:` 的 MiB 值（向下取整）；读不到返回 null。
     *
     * ## 为什么轨迹非要有这一列（2026-10-31 上报收件箱分桶）
     *
     * 当前口径 18 份「上次异常退出」里有 11 份 **Java 堆与 native 都不高**（峰值 10%~79% /
     * native 18~146 MiB）。只看 used/native 永远分不出两种死法：文件映射访问（SIGBUS）与
     * **整机内存被别的东西吃光后 lmkd 无差别收进程**。前者是我们自己的 bug，后者装多少内存
     * 优化都只是把死亡时间推后 —— 必须记下死前**系统还剩多少**才能分开。
     *
     * 判据可对照：`MemAvailable` 与 `ActivityManager.MemoryInfo.availMem` 同源
     * （内核估算的「还能借出来」的页数，含可回收页缓存），因此可以直接和设备自己报的
     * 低内存线（`MemoryInfo.threshold`，见 [formatLine] 的 `lmk=` 列）比大小。
     */
    fun parseMemAvailableMiB(memInfo: String): Long? {
        for (line in memInfo.lineSequence()) {
            if (!line.startsWith(MEM_AVAILABLE_KEY)) continue
            val kb = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: return null
            return kb / 1024L
        }
        return null
    }

    /** `/proc/meminfo` 里那一行的键名。 */
    private const val MEM_AVAILABLE_KEY = "MemAvailable:"

    /**
     * 一行轨迹：`t=<距进程起点毫秒> used=<Java 已用>MiB max=<堆上限>MiB native=<MiB> [阶段] [sys=<系统可用>MiB] [lmk=<本机低内存线>MiB]`。
     *
     * 三列口径与 [LoadTrace.Sample] 逐字相同（`totalMemory-freeMemory` / `maxMemory` /
     * `Debug.getNativeHeapAllocatedSize`），装载期与运行期的读数因此可以直接并排看。
     */
    fun formatLine(
        elapsedMs: Long,
        usedMiB: Long,
        maxMiB: Long,
        nativeMiB: Long,
        phase: String,
        sysAvailMiB: Long = -1L,
        lmkThresholdMiB: Long = -1L,
    ): String {
        val tail = if (phase.isBlank()) "" else " $phase"
        val sys = if (sysAvailMiB >= 0L) " sys=${sysAvailMiB}MiB" else ""
        val lmk = if (lmkThresholdMiB >= 0L) " lmk=${lmkThresholdMiB}MiB" else ""
        return "t=${elapsedMs}ms used=${usedMiB}MiB max=${maxMiB}MiB native=${nativeMiB}MiB$tail$sys$lmk"
    }

    /** 脏退出报告的补充现场行（状态原文 + 轨迹尾 + 一句怎么读）。 */
    fun dirtyReportLines(state: String?, trail: List<String>): List<String> {
        val lines = ArrayList<String>(REPORT_TAIL_LINES + 6)
        lines += "上一轮的收尾标记没被写下 ⇒ 进程死在没有兜底的点上（系统杀 / native 崩 / 兜底自身分配失败）。"
        lines += "状态文件原文：${state?.trim()?.replace('\n', ' ') ?: "（读不到）"}"
        lines += "运行轨迹（旧在前，最多 $REPORT_TAIL_LINES 行；前三列口径同装载埋点，sys/lmk 是系统读数）："
        if (trail.isEmpty()) {
            lines += "（一条都没有 ⇒ 上一轮死在第一次回合边界之前，即装载期或开局演出期）"
        } else {
            lines += trail.takeLast(REPORT_TAIL_LINES)
        }
        return lines
    }

    /**
     * 落一行状态文件；**目录不存在就建**。
     *
     * 2026-10-30 API 36 模拟器实测踩到的洞：`File.writeText` 不会创建父目录，而 `eratw-run/`
     * 是本类专用的新目录（首启必然不存在）⇒ 每一次写都抛 ENOENT，全部被调用方的
     * `runCatching` 吞掉，整套脏退出采集静默失效、屏上与日志里一点痕迹都没有。
     * 建目录因此收进写入器本身：调用点再没有「忘了 mkdirs」这个选项。
     *
     * @return false = 写不成（目录建不出来 / 磁盘满 / 权限）；调用方只 Log，绝不影响游戏。
     */
    fun writeStateFile(stateFile: File, text: String): Boolean = writeCreatingDir(stateFile, text)

    /** 落整份运行轨迹（覆盖写，只留最后 [MAX_LINES] 行）。口径同 [writeStateFile]。 */
    fun writeTrailFile(trailFile: File, lines: List<String>): Boolean =
        writeCreatingDir(trailFile, lines.joinToString("\n"))

    private fun writeCreatingDir(file: File, text: String): Boolean = runCatching {
        val parent = file.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
        file.writeText(text)
        true
    }.getOrDefault(false)
}
