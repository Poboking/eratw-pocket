package com.eratw.port.android

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 崩溃取证族（wave/eng-p13-crashmove · A-1 第一刀）：整段从 [MainActivity] **原样搬入** ——
 * 崩溃处理器安装、取证四步、最小现场落盘、建壳失败最后一屏，与那组进程级状态槽。
 * 行为、文案、线程模型逐字未改（FEEL-15 在册挂账）。唯一的结构变化：豁免窗口的**开口**
 * 留在 MainActivity.kt（StrictModeGuardTest 的豁免口扫描按文件点名），经 [install] 的
 * `forensics` 形参注入 —— 执行线程仍是崩溃线程，语义与搬前逐字相同。
 *
 * C6（wave/eng-crash）起多一步：取证四步之后、交回旧处理器之前，把崩溃摘要递给
 * [CrashScreenActivity]（独立进程）并落一份「上次崩溃摘要」标记给下次冷启动兜底
 * （见 [surfaceCrashScreen]）。取证四步的顺序、`reported != null` 判据与补发时序未动。
 */
internal object CrashForensics {

    /** 崩溃「最小现场」文件名（`filesDir/eratw-reports/` 下，追加写、不参与滚动清理）。 */
    private const val CRASH_PREFIX_FILE = "crash-prefix.log"

    /** 最小现场里异常消息的截断长度（字符）：一行只留最关键的头部。 */
    private const val CRASH_MESSAGE_LIMIT = 200

    /** 默认未捕获异常处理器是否已经包过：进程内只包一层（见 [install]）。 */
    private val crashHandlerWrapped = AtomicBoolean(false)

    /** 我们包出去的那一层；`Thread.getDefaultUncaughtExceptionHandler()` 等于它即已装好。 */
    @Volatile
    private var installedCrashHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 崩溃处理器读宿主的口子：每次 onCreate 刷新到最新 Activity 的 [MainActivity.host]。
     *
     * 处理器是进程级单例、只装一次；直接捕获 Activity 会让第一个壳被静态边钉住不放。
     */
    @Volatile
    private var crashHostProvider: (() -> GameHost?)? = null

    /**
     * 当前把 [crashHostProvider] 指向自己的 Activity 实例。
     *
     * 用途只有一处：`onDestroy` 只回收自己装的那份读数口（[uninstallHostProvider]）。
     * 两个实例交接时（旧壳销毁、新壳已 onCreate）旧壳无权把新壳的 provider 置空。
     */
    @Volatile
    private var crashProviderOwner: MainActivity? = null

    /**
     * 崩溃兑底（方案①）：包一层缺省未捕获异常处理器 —— 先尽力落一行「最小现场」与一份
     * 「应用崩溃」报告到 `filesDir/eratw-reports/`，再原样交回旧处理器（保持系统崩溃口径不变）。
     *
     * 顺序是有讲究的：**最小现场先行**（[writeCrashPrefix]）。OOM / native 崩溃时后面的
     * 报告采集与同步补发都可能半途失败，而时间戳 + 线程名 + 异常类 + 截断消息这行是
     * 代价最小、最可能活下来的读数。报告在崩溃线程同步写（私有 filesDir，几十 KB，可接受）；
     * 宿主可能还没建（崩在装载前）或已 close，采集自会降级成纯异常报告。
     *
     * 这四步整段收在 [collectCrashForensics] 里：崩溃线程就是 UI 线程时，把它**整段**套进
     * UI 线程磁盘 IO 豁免窗口（表见 `StrictModeGuard.uiDiskIoExemptions` 的
     * 「崩溃现场取证落盘」，裁定的完整理由在 MainActivity 侧开口那段的行注释里），
     * 不在窗口内外各留半截。窗口开口钉在 MainActivity.kt（豁免口扫描按文件点名），
     * 由调用方经 [forensics] 形参注入。
     *
     * 只包一层：[crashHandlerWrapped] 与 [installedCrashHandler] 双重判定 —— Activity 重建
     * 会再次进 onCreate，若当前默认处理器已经是我们自己包的那一层，直接复用；否则每重建
     * 一次就在崩溃路径上多套一轮采集 + 补发，真崩时进程死得更慢、报告还会重写。
     *
     * C6（wave/eng-crash）在取证之后、交回旧处理器之前**追加**一步 [surfaceCrashScreen]：
     * 玩家当场读得到摘要（[CrashScreenActivity] 在 `:crash` 独立进程里），同时把同一份摘要
     * 落成「上次崩溃摘要」标记给下一次冷启动兜底。取证四步的顺序、`reported != null` 判据、
     * 补发时序一个字都没动 —— 崩溃屏只是取证之后的一步追加，且整段 runCatching。
     *
     * @param hostProvider 崩溃处理器读宿主的口子。调用方传 `{ host }`：闭包读的是实例字段，
     *   **照样捕获本壳** ——「provider」不是一种免捕获写法，真正让死壳不被这条静态边钉住的是
     *   [crashProviderOwner] 身份闸 + onDestroy 的 [uninstallHostProvider] 认主摘除：
     *   槽里最多指向**当前活的那只壳**，换代后旧壳的摘除只清自己。
     */
    internal fun install(
        activity: MainActivity,
        hostProvider: () -> GameHost?,
    ) {
        // 每次进入都刷新宿主读数口：处理器是进程级的（只装一次），宿主却随 Activity 换代。
        crashProviderOwner = activity
        crashHostProvider = hostProvider
        val context = activity.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // 当前默认处理器已经是我们自己包的那一层（上一次 onCreate 装的）⇒ 直接复用。
        if (current != null && current === installedCrashHandler) return
        // 兜住「别人把默认处理器换掉了、但我们的那层还记在字段里」的畸形态：不重复套娃。
        if (!crashHandlerWrapped.compareAndSet(false, true)) return
        val previous = current
        val handler = Thread.UncaughtExceptionHandler { thread, e ->
            // 豁免窗口（obs-uiio 裁定「甲」）随本表自带，调用方不再注入：崩掉的这条线程就是
            // 进程还能自己写字的最后一次机会，任何异步 handoff 都随进程一起消失。只有崩的就是
            // UI 线程时窗口才真的开（线程策略按线程装在 EratwApplication 的主线程；主线程闸
            // 收在 EratwStrictMode.allowUiDiskIo 本体），窗口状态机按「只在主线程读写」实现。
            val reported = EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_CRASH_FORENSICS) {
                collectCrashForensics(context, thread, e)
            }
            // C6（wave/eng-crash）：取证之后**追加**一屏玩家可读摘要 —— 独立进程的
            // [CrashScreenActivity]；起不来的话同一份摘要落成「上次崩溃摘要」标记，由下一次
            // 启动的兜底横幅补显。整段 runCatching：这一屏是新增体验，不许把
            // 「想显示错误」变成「更快地崩」。顺序仍是取证 → 崩溃屏 → 交回 previous。
            runCatching { surfaceCrashScreen(context, thread, e, reported) }
                .onFailure { Log.w(MainActivity.TAG, "崩溃屏没起来（取证已落盘，交回系统处理器）", it) }
            previous?.uncaughtException(thread, e)
                ?: Runtime.getRuntime().exit(2)
        }
        installedCrashHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    /**
     * onDestroy 的认主摘除：只回收**自己**装的那份读数口。两个实例交接时（旧壳销毁、
     * 新壳已 onCreate）旧壳的 onDestroy 不得把新壳刚装好的 provider 清掉
     * （真机双壳交接窗口实测存在）。
     */
    internal fun uninstallHostProvider(activity: MainActivity) {
        if (crashProviderOwner === activity) {
            crashHostProvider = null
            crashProviderOwner = null
        }
    }

    /**
     * 崩溃现场的取证四步：最小现场 → 完整报告 → 一行 `stopped` 认账 → 同步补发。
     *
     * 从处理器里抽成独立函数只为一件事：让「崩的就是 UI 线程」那一条路能**整段**套进 UI 线程
     * 磁盘 IO 豁免窗口（理由与不挪线程的取舍见 `StrictModeGuard.uiDiskIoExemptions` 的
     * 「崩溃现场取证落盘」那一条），而不是把四步拆在窗口内外两处。四步的**顺序、入参、
     * 判据**与原写法逐字相同：报告真落成才认账（`reported != null`），补发排在交回旧处理器之前。
     *
     * 形参收 application context（调用方 `install` 已取好）而不是再读一次实例属性：旧写法
     * 在这里直接写 `applicationContext`，那是 Activity 的属性 ⇒ 处理器闭包顺手把装它的壳
     * 钉在进程级单例上，与本对象上面那对 provider 字段的口径正好相反。
     * 值没变（同一个 application context），变的只是这条静态边。
     *
     * @return 真落成的报告文件；null = 报告没落成（与内部 `reported != null` 判据同一个值）。
     *   C6 把这一枚交回给调用方喂崩溃屏的「完整记录在哪」那一行 —— 只是**返回值**，
     *   四步的顺序、判据与时序一个字没改。
     */
    internal fun collectCrashForensics(context: Context, thread: Thread, e: Throwable): File? {
        writeCrashPrefix(context, thread, e)
        val reported = runCatching {
            val crashHost = crashHostProvider?.invoke()
            ErrorReportCollector.collect(
                context,
                crashHost,
                "应用崩溃",
                e,
                listOf("崩溃线程：${thread.name}"),
                // 崩溃发生在装载前（或旧宿主已被换掉）时这里就是 null —— 那是真没有，
                // 与「忘了传」不是一回事，所以由签名逼调用点把话说出来。
                crashHost?.heartbeat,
            )
        }.getOrNull()
        // 只有报告真落成才在这里认账（见 RunWatch 的脏退出判据）。
        // `reported == null` 的常见原因就是 OOM 大到报告自己也分配不出来 —— 那种死
        // 正是要留给下次冷启动兜底的那一类，不能让它被一行 `stopped` 抹成「走到了收尾点」。
        //
        // 认账的那一行把**报告文件名**一起写下去（`stopped crash=<异常> report=<名>`）：
        // 下一次冷启动据此给玩家补一句「上一次崩了、报告在机里」（RunWatch.surfaceCrashNotice）。
        // 不带文件名的话崩的这一次就是一份躺在几十份滚动报告里没人认领的文本 ——
        // 旧形态下这条路上**应用侧**一句提示都没有（系统自己的「应用已停止运行」对话框不算：
        // 它既不说报告在哪个文件里、也不给复制/分享的出口，且 `stopped` 首词已经把
        // 脏退出那张卡关掉了，下一次冷启动也不会再提）。
        if (reported != null) {
            runCatching {
                RunWatch.markStopped(
                    context,
                    RunTrail.crashStopReason(e.javaClass.simpleName, reported.name),
                )
            }
        }
        // 方案③：崩溃路上同步补发一次（3s 上限，进程死前给一次机会；未配置 = no-op）。
        // 这一发**不会被豁免掉**：豁免窗口只放开磁盘读写（`allowThreadDiskWrites` 那一档），
        // 网络照旧被 detectNetwork 记一行 —— 故意的：崩溃线程上同步等 3 秒网络本来就值得被看见。
        runCatching { ErrorReportUploader.uploadPendingSyncForCrash(context) }
        return reported
    }

    /**
     * C6 追加步：把崩溃摘要递给玩家 —— 先落「上次崩溃摘要」标记，再拉独立进程的崩溃屏。
     *
     * ## 顺序为什么是「标记先行」
     *
     * 拉起崩溃屏这一步在真机上至少有三种失败面：ROM 的后台启动限制、`BadToken` 类的窗口
     * 权限、以及 `:crash` 进程自己没起来。任何一种失败都不该让玩家**又**回到「闪退无声」，
     * 所以标记先写：本次能上屏时它留着给「玩家强杀 / 下次启动」兜底（清标记点在
     * `MainActivity` 进入主循环与玩家点掉横幅两处），起不来时它就是唯一的出口。
     *
     * ## 为什么标记写盘仍走崩溃取证那一条豁免
     *
     * 处理器跑在崩溃线程上（可能就是 UI 线程），这一发同属「进程最后一次能自己落盘的时机」，
     * 进表理由与 [collectCrashForensics] 那三件套逐字同源（`StrictModeGuard` 的
     * 「崩溃现场取证落盘」条目）；豁免表归另一 lane，本波不新增条目，只是同一理由下的
     * 第二处调用点。整个函数由调用方整段 runCatching，这里再各套一层是为了让
     * 「标记写失败但屏还能起来」不被前一步拖死。
     */
    private fun surfaceCrashScreen(context: Context, thread: Thread, e: Throwable, reported: File?) {
        val summary = buildCrashSummary(thread, e, reported)
        runCatching {
            EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_CRASH_FORENSICS) {
                CrashSummaryMarker.write(ErrorReportCollector.reportDir(context), summary)
            }
        }.onFailure { Log.w(MainActivity.TAG, "上次崩溃摘要标记没写成（下次启动少一句提示）", it) }
        runCatching {
            context.startActivity(
                Intent(context, CrashScreenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION,
                )
                    .putExtra(CrashScreenActivity.EXTRA_EXCEPTION_TYPE, summary.exceptionType)
                    .putExtra(CrashScreenActivity.EXTRA_AT_MILLIS, summary.atMillis)
                    .putExtra(CrashScreenActivity.EXTRA_THREAD_NAME, summary.threadName)
                    .putExtra(CrashScreenActivity.EXTRA_CENSUS_LINE, summary.censusLine)
                    .putExtra(CrashScreenActivity.EXTRA_REPORT_NAME, summary.reportName),
            )
        }.onFailure { Log.w(MainActivity.TAG, "崩溃屏没拉起来（标记已落盘，下次启动兜底）", it) }
    }

    /**
     * 崩溃摘要：异常类型 + 时刻 + 线程名 + 资源取证行 + 报告文件名。
     *
     * 资源/引擎取证**复用既有数据面**（[GameHost.resourceCensusReportLine]：装载时由
     * [com.eratw.port.core.host.ResourceContents.censusLine] 折好的一行，读侧只读一个
     * `@Volatile` 标量）：这里不新造任何采集面，宿主没建好或已关时取到 null ⇒ 屏上写
     * 「取证不可用」。整个取值 runCatching：崩溃线程上再分配失败就是「摘要也没了」。
     */
    private fun buildCrashSummary(thread: Thread, e: Throwable, reported: File?): CrashSummary {
        val census = runCatching { crashHostProvider?.invoke()?.resourceCensusReportLine() }.getOrNull()
        val type = e.javaClass.simpleName.ifBlank { e.javaClass.name }
        return CrashSummary(
            exceptionType = type.ifBlank { "Throwable" },
            atMillis = System.currentTimeMillis(),
            threadName = thread.name,
            censusLine = census?.takeIf { it.isNotBlank() },
            reportName = reported?.name,
        )
    }

    /**
     * 崩溃「最小现场」：往 `filesDir/eratw-reports/crash-prefix.log` 追加一行。
     *
     * OOM 现场的第一纪律是**少分配、不读盘**：不采集设备信息、不格式化时间、不拼大字符串。
     * 消息本身已是一条现成 String，只截前 [CRASH_MESSAGE_LIMIT] 个字符（substring 至多再
     * 分配几百字节）；时间戳用 epoch 毫秒，交给后处理换算。整段 runCatching ——
     * 写现场失败绝不能改变崩溃出口。
     *
     * 放在本对象：崩溃处理器是进程级的，写成实例方法会让 handler 闭包捕获装它的
     * 那一个 Activity，静态边把旧壳钉在内存里。
     */
    private fun writeCrashPrefix(context: Context, thread: Thread, e: Throwable) {
        runCatching {
            val dir = ErrorReportCollector.reportDir(context)
            if (!dir.isDirectory) dir.mkdirs()
            val raw = e.message
            val message = if (raw != null && raw.length > CRASH_MESSAGE_LIMIT) {
                raw.substring(0, CRASH_MESSAGE_LIMIT)
            } else {
                raw.orEmpty()
            }
            val line = StringBuilder(CRASH_MESSAGE_LIMIT + 96)
                .append(System.currentTimeMillis())
                .append(" thread=").append(thread.name)
                .append(" ex=").append(e.javaClass.name)
                .append(" msg=").append(message)
                .append('\n')
                .toString()
            FileOutputStream(File(dir, CRASH_PREFIX_FILE), true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * 建壳失败时的最后一屏（[MainActivity.onCreate] 的 catch 专用）。
     *
     * 不依赖 `consoleView` / `shell` / Compose —— 那些对象可能压根没建起来。只用最原始的
     * 系统控件摆一屏可读文字 + 一颗「重启应用」：玩家至少能把故障说出来，而不是对着桌面
     * 反复点图标（崩溃报告在私有目录里，大陆网络常常送不出去）。
     *
     * 连这一步都失败（例如控件工厂本身也抛）才把原异常交回系统：那时确实没有别的选择，
     * 崩溃处理器会照常落报告。
     *
     * @param activity 兜底屏摆在其上的壳（原写法的 `this`）。
     * @param scheduleRelaunch 「重启应用」按下后的重拉安排（原 [MainActivity.scheduleRelaunch]，
     *   仍归 MainActivity 所有，经形参注入）。
     */
    internal fun renderBootstrapFailure(
        activity: MainActivity,
        t: Throwable,
        scheduleRelaunch: () -> Unit,
    ) {
        Log.e(MainActivity.TAG, "界面初始化失败：降级到最后一屏", t)
        // 文案接线②入 ShellFailureCopy（值与原硬编码逐字同形）；本函数只跑在
        // onCreate 的建壳失败现场（UI 线程），语言现取不跨线程。
        val copy = ShellFailureCopy.of(UiPrefs.settingsLanguage(activity))
        val detail = buildString {
            append(copy.bootstrapTitleLine)
            append(t.javaClass.simpleName)
                .append(copy.detailSeparator)
                .append(t.message ?: copy.bootstrapNoMessage)
                .append("\n\n")
            append(copy.bootstrapReassure)
            append(copy.bootstrapInstruction)
        }
        val restored = runCatching {
            val message = TextView(activity).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(56, 140, 56, 24)
                // 允许长按复制：玩家发反馈时不必依赖截图。
                setTextIsSelectable(true)
                text = detail
            }
            val restart = Button(activity).apply {
                text = copy.bootstrapRestartButton
                setOnClickListener {
                    // 收尾标记必须先落：这一发 `exit(0)` 之后系统记的是 EXIT_SELF（code=0、
                    // 备注空），而盘上还停在本次冷启动自己写的那行 `running` ⇒ 下一次开游戏
                    // 会给**玩家自己点的这颗重启**补一份「上次异常退出」+ 弹一张卡。
                    // 端点里 14 份 `原因=EXIT_SELF(1) 状态=code=0 重要度=FOREGROUND(100)
                    // 备注=（无）` 的「上次异常退出」（0.2.34~0.2.39，跨 vivo V2528A /
                    // HUAWEI NAM-AL00 / 小米 / blackshark）就是这个形状；全部 `exit(0)` 点里
                    // 「结束应用」那一路（MainActivity.exitApplication）从 7c04bb3c 起就先写
                    // `stopped exitButton`，所以这一发是唯一还漏着的一个（**这 14 份能不能全
                    // 归它没证**：那一屏没留 logcat 以外的痕迹，见交付的 TODO(verify)）。
                    // 这一跳的死因本来就由「启动失败」那份报告认领过了，不该再算一次无人认领。
                    runCatching {
                        // 裁定同 onStart / onStop / exitApplication：这一发用的就是豁免表里
                        // 「生命周期状态落盘」那一条写明的理由（它的下一句紧跟 `Runtime.exit(0)`），
                        // 不另开一条。写在窗口外就是拿 debug 包的 StrictMode 侦测换一次静默丢行。
                        EratwStrictMode.allowUiDiskIo(StrictModeGuard.WHERE_LIFECYCLE_STATE) {
                            RunWatch.markRelaunchClaim(activity.applicationContext)
                        }
                    }
                    // 与失败屏那颗同一套机制：AlarmManager 的 PendingIntent 活在
                    // system_server 里，本进程死了也照样把新实例拉起来。
                    scheduleRelaunch()
                    Runtime.getRuntime().exit(0)
                }
            }
            val column = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
                addView(
                    message,
                    LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f,
                    ),
                )
                addView(
                    restart,
                    LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            activity.setContentView(column)
        }
        if (restored.isFailure) throw t
    }
}
