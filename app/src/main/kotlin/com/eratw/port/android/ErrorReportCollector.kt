package com.eratw.port.android

import android.annotation.TargetApi
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.eratw.port.core.diag.ErrorReport
import com.eratw.port.core.diag.ErrorReportUpload
import com.eratw.port.core.diag.FileReadTrail
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.host.DaemonFailure
import com.eratw.port.core.host.SingleDaemonSlot
import com.eratw.port.core.proc.FunctionTable
import com.eratw.port.core.proc.ProcessRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 错误报告的 Android 侧采集与出口（方案①「本地报告包 + 玩家手动分享」，2026-10-27）。
 *
 * ## 分工
 *
 * 报告的**内容与格式**在 core-vm（[ErrorReport]，纯 JVM、有定向单测）；本类只做
 * 壳侧四件事：
 * 1. **采集**：版本/设备/系统（[headerLines]）、构建标识（`BuildConfig.eratwBuildStamp`，
 *    gradle 注入）与产物指纹（随包 `package.report` 的 `program-sha256`，
 *    [programSha256FromAssets]）、ERB 调用栈与 VM 线程 Java 栈顶
 *    （[vmStackLines]，反射口径与 `DevToolsHost.callstack` 一致）、屏上尾部
 *    （`GameHost.debugConsoleLines`）、系统取证（[exitForensicsLines]：本轮 PSS/RSS 足迹 +
 *    系统记下的上一轮退出原因，判式与格式化在 [ExitForensics]）；
 * 2. **落盘**：`filesDir/eratw-reports/eratw-report-<时间戳>.txt`，滚动保留
 *    [KEEP_REPORTS] 份；
 * 3. **复制**：整份报告进剪贴板（群聊里最省事的交法）；
 * 4. **分享**：经 [ReportFileProvider] 的 content URI 走系统分享面板。
 *
 * ## 铁律
 *
 * - **绝不抛**：报告生成本身若把失败路径炸了，就是用诊断代码制造第二场事故；
 *   任何异常只 Log 并返回 null。
 * - **无网络**：方案①的边界就是「玩家手动分发」，本类不发任何东西。
 * - **无变量取值**：见 [ErrorReport] 类头的隐私口径，采集时不得顺手加。
 * - **不在崩溃线程上解码产物**：本类会被 `UncaughtExceptionHandler` 在**崩溃线程**（多半是
 *   主线程）上调，那一刻 VM 线程还活着；任何取值都不许走「读 `.erbc` 映射页现解」的惰性路
 *   （见 [vmStackLines] 的口径与 `ErrorReportVmStackTest` 的源码哨兵）。
 * - **堆将尽时也要能跑**：[exitForensicsLines] 这类采集不拼大字符串、行数硬封顶。
 * - **同型报错不刷屏**（EP1-L6）：`main@bf0e78a6` 之后每一条语句级脚本错误都走本类落一份
 *   报告，代价是「每页都撞的那几个点」一局能产几十份（端点实证见 [MAX_REPORTS_PER_SESSION]
 *   的取值理由）。节流判式在 [ErrorReportThrottle]，签名归一在 [ReportSignature]，
 *   管制范围只有 [THROTTLED_KIND] 这一类。
 *   **但「失败必须留痕」的纪律优先于省流量**：① 落盘的那一份正文一个字节都不许因节流而变
 *   （含 `[线程心跳]`、`[系统取证]` 两节 —— 去重只决定**要不要再落一份**，不参与拼装，
 *   [collectFull] 里没有节流的名字，哨兵用例钉的就是这条）；② 首份永远即时落盘，
 *   任何冷却都不许把它推后；③ 被抑制的那些条一条都不许静默消失 —— 计数进内存表、
 *   一行进运行轨迹（[RunWatch.forceSample]，随下一次冷启的脏退出报告一起出去）、
 *   一行进 logcat，三份出口都是同一句现场文本。
 */
object ErrorReportCollector {

    private const val TAG = "ErrorReport"

    /** 报告目录名（filesDir 下）。 */
    const val REPORT_DIR_NAME = "eratw-reports"

    /**
     * 受上报节流管制的报告类型（[GameHost.writeErrorReport] 给的那四个字）。
     *
     * 为什么只管制这一类：`bf0e78a6` 把语句级 `CodeEE` 从「启动失败杀会话」改成可恢复终局，
     * 从此「玩家每翻一页都撞一次」的报错点（`QOL_GATHERING_DRAWING` / `SET_MINIMUM_EXP` /
     * `COMF413` 这类）每一页都落一份报告。其余类型 —— 「启动失败」「应用崩溃」「回到标题失败」
     * 「上次异常退出」—— 全都是**终局**：落完那一份会话或进程就没了，一局最多一份，
     * 天然没有刷屏面；把它们一起管起来只会削掉真现场，所以一刀都不碰。
     *
     * 字符串与 `GameHost.kt` 那四处 `writeErrorReport` 的字面量对齐（2026-11-05 复位指针，旧行号
     * `2714`/`2792` 早已漂：现树四处 = `GameHost.kt:2864` 启动失败、`:3429` 脚本运行报错
     * （`scriptErrorTerminal`，语句级 `CodeEE`）、`:3517` 脚本运行报错（`pumpUntilTerminal`
     * 的 BLOCKED/FAILED 终局，无异常对象）、`:3615` 回到标题失败）；
     * 管制集只认「脚本运行报错」这一类，另三类是终局报告、一局最多一份。
     * 那里改名要同步改这里（哨兵见 `ErrorReportThrottleTest`）。
     */
    internal const val THROTTLED_KIND = "脚本运行报错"

    /**
     * 每一局（同一个会话）允许落盘的 [THROTTLED_KIND] 报告份数上限。
     *
     * ## 为什么是 5（不许拍脑袋，读端点报告的是人不是机器）
     *
     * - 端点那一头没有自动告警：`.local/reports/triage.md` 的读数形状是**人工分桶**
     *   ——「去重后 96 份（输入 100 份，重复上传 4 份已合并）」，同一台 vivo V2528A 在
     *   0.2.34 一天 **51 条同型**（`RAND 的参数必须为正数（实际 0） @ QOL_GATHERING_DRAWING 行69`，
     *   台账里相邻两条 03:38:36 → 03:39:26 只差 50 秒）在分桶表里被压成**一行**
     *   `51  …  启动失败  CodeEE`。
     *   ⇒ 第 6 份同型报告对一个坐在屏幕前的人是 0 增量信息：他要的是「哪一类、哪一行」，
     *     那个已经写在第一份里了；「撞了几次」由计数行负责（进轨迹、进 logcat）。
     * - 上限要**容得下一次真实的混合翻车**：一页里能同时冒出的形状实测是 1~3 类
     *   （采集池 RAND、经验越界、`<p>` 标签重复），5 = 三条真实形状 + 两条余量，
     *   再把每局的天花板压到「比改口径前那一整天的 51 份低一个数量级」。
     * - 上限**不许**低到 1~2：中局报错可恢复之后，一局里前几个形状可能是同一场事故的不同面
     *   （先是 `EXPLV 越界`、后是它派生的 `除数为 0`），只留一份就等于让读的人自己猜链。
     * - 终局类报告不受本上限管（见 [THROTTLED_KIND] 的理由），所以「崩了」那一份永远在。
     */
    internal const val MAX_REPORTS_PER_SESSION = 5

    /**
     * 抑制计数的**钉发冷却**（毫秒）：沿用补发侧既有的空报告 10 s 冷却，同一把尺、不另造。
     *
     * 出处 [ReportUploadQueue.EMPTY_GRACE_MS]（就是 BOARD「L17 收口」那条「空报告加 10 s
     * 冷却」），为什么必须同源见那里的 KDoc。
     *
     * **它管的是「一行计数钉进运行轨迹的频率」，不是「报告什么时候落盘」**：
     * 首份报告永远在 [collect] 的当场写（[MAX_REPORTS_PER_SESSION] 那条纪律同理），
     * 这条冷却只决定「10 秒内要不要再惊动一次盘」—— 一次强制采样 = 一条轨迹行 + 一次
     * 4 KB 覆盖写（[RunWatch.forceSample]），没有冷却就是每页一次磁盘写；
     * 10 s 一档 = 每分钟最多 6 发，[RunTrail.MAX_LINES]（64 行滚动）不会被同一页灌满。
     */
    internal const val PIN_COOLDOWN_MS = ReportUploadQueue.EMPTY_GRACE_MS

    /**
     * 本进程的报错节流表（签名 → 首份文件 + 次数、本局已落份数、上次钉发时刻）。
     *
     * **状态全挂这一枚实例**（EP1-L6 的纪律）：本类是进程级唯一采集器，节流表与它同寿；
     * 不新增任何顶层 `var`、不用 `ThreadLocal`、不把计数塞进 core。清账口径见
     * [ErrorReportThrottle] 类头：会话身份变了就整表清零，[resetThrottle] 是给宿主的显式口。
     */
    private val throttle = ErrorReportThrottle()

    /**
     * 守护线程非预期死亡的**进程级留痕环**（本波 L2 结的「后台线程静默死零留痕」的病）。
     *
     * 状态挂本采集器这一枚进程级单例（与 [throttle] 同纪律：不新增顶层 var、不用 ThreadLocal）。
     * 两个写入方：① **直接接线**（契约 §2/L2 第 4 条，不等宿主装配）——[SingleDaemonSlot] 的
     * per-thread handler 在线程死的那一刻把记录攒进 core 侧进程级环，本类采集时
     * [drainDaemonDeathLines] 当场 [SingleDaemonSlot.drainCapturedFailures] 取一次即清、压成
     * 一行入本环；② 宿主手里的显式口 [noteDaemonDeath]（别的 daemon 形状想搭同一节就用它，
     * 例如游戏线程死亡）。采集时 [collectFull] 调 [drainDaemonDeathLines] **取一次即清**，
     * 保证同一条死只在下一份报告里出声一次、不逐份重播。
     *
     * 容量 [MAX_DAEMON_DEATH_LINES] 写死（理由见该常量）：环满丢最旧——一条反复起落、每落一次都
     * 留痕的抖线，不许把这节撑成无界表（§0 第 10 条，findings.jsonl 涨到 42 MB 那起事故的教训）。
     */
    private val daemonDeathLock = Any()
    private val daemonDeaths = ArrayDeque<String>()

    /**
     * 留痕环的容量上限。
     *
     * 取 4 的算术理由：当前用 [SingleDaemonSlot] 的常驻线程实测 1 路（`eratw-devtools`），
     * 波次契约 §2/L2 点名「资产解包 / 图形预取」这两类也会迁到同一形状 ⇒ 预留 1 + 2 = 3 路，
     * 再加 1 格余量给「同一路刚清、第二次死抢在采集前又来一发」的瞬时重叠 = **4**。
     * 超出丢最旧（见 [noteDaemonDeath]），所以这是硬顶、不是估算。
     */
    internal const val MAX_DAEMON_DEATH_LINES = 4

    /** 单条死因留痕里带的栈顶帧数（够定位崩在哪个 loop，又不至于撑爆一行）。 */
    private const val DAEMON_DEATH_FRAMES = 3

    /**
     * 记一笔守护线程非预期死亡。[failure] 为 null（没死 / 正常打断路）直接返回、不占格。
     *
     * 在**入表那一刻就把 Throwable 压成一行文本**，环里只存 String——不把整棵异常对象图长期攥在
     * 进程级单例上（报告可能在堆将尽时生成，留着对象图就是给下一次 OOM 埋雷）。整段 runCatching：
     * 留痕装置自己绝不可能成为新的崩溃源。
     */
    fun noteDaemonDeath(threadName: String, failure: Throwable?) {
        if (failure == null) return
        runCatching {
            val line = daemonDeathText(threadName, failure)
            synchronized(daemonDeathLock) {
                if (daemonDeaths.size >= MAX_DAEMON_DEATH_LINES) daemonDeaths.removeFirst()
                daemonDeaths.addLast(line)
            }
        }.onFailure { Log.w(TAG, "守护线程死因入表失败（不影响报告与游戏）", it) }
    }

    /**
     * 取一次即清：返回死因小节行（无则空表），并把环清空——同一条死不逐份重播。
     *
     * 先收 core 侧的槽死亡环（[SingleDaemonSlot.drainCapturedFailures]，**直接接线**：凡走
     * `SingleDaemonSlot` 的 daemon 非预期死亡，不需要任何宿主装配就能到这里），再收本环。
     * 空表时调用方（[collectFull]）整节不写，「没死过」与「死过并已报出」都留得下干净痕迹。
     * 整段 runCatching：取证出口绝不许把采集面带崩（连 drain 都抛就只缺一节，不缺报告）。
     */
    internal fun drainDaemonDeathLines(): List<String> = runCatching {
        SingleDaemonSlot.drainCapturedFailures().forEach { death ->
            appendDaemonDeathLine(daemonDeathRowText(death))
        }
        synchronized(daemonDeathLock) {
            if (daemonDeaths.isEmpty()) {
                emptyList()
            } else {
                val out = ArrayList<String>(daemonDeaths.size + 1)
                out += "后台守护线程非预期死亡（取一次即清，防重复上报）："
                out += daemonDeaths.toList()
                daemonDeaths.clear()
                out
            }
        }
    }.getOrDefault(emptyList())

    /** 一行死因留痕入本环（满丢最旧，与 [noteDaemonDeath] 同闸）；永不抛。 */
    private fun appendDaemonDeathLine(line: String) {
        runCatching {
            synchronized(daemonDeathLock) {
                if (daemonDeaths.size >= MAX_DAEMON_DEATH_LINES) daemonDeaths.removeFirst()
                daemonDeaths.addLast(line)
            }
        }.onFailure { Log.w(TAG, "守护线程死因入表失败（不影响报告与游戏）", it) }
    }

    /**
     * core 侧定长记录 → 报告一行：`[线程名] 第N代 类名：消息 @ 栈顶帧`。
     *
     * 空 message 不带悬空冒号（与 [daemonDeathText] 同口径：类名后紧跟冒号 = 拼 null 的痕迹，
     * 读的人会以为消息真的为空字符串）。长度已在 core 裁剪过（`SingleDaemonSlot` companion），
     * 这里不再二次截断。
     */
    internal fun daemonDeathRowText(death: DaemonFailure): String {
        val msg = death.message.takeIf { it.isNotBlank() }
        return "[${death.threadName}] 第${death.generation}代 ${death.errorClass}" +
            (if (msg != null) "：$msg" else "") + " @ ${death.topFrame}"
    }

    /**
     * 一条死因 → 一行文本：`[线程名] 异常类：消息  栈顶N帧：a.m:1 <- b.m:2`。绝不抛。
     *
     * 类名取 **simpleName**（`RuntimeException` 而不是 `java.lang.RuntimeException`）：这一行是给
     * 人扫一眼定位用的，包名是噪音；负断言「空消息不带悬空冒号」也只有 simpleName 才判得干净
     * （`ErrorReportDaemonDeathTest` 钉死）。core 侧环（[daemonDeathRowText]）保留 FQCN——那条
     * 通道在死那一刻就裁剪过一次，两侧各有用例钉，不互抄。
     */
    internal fun daemonDeathText(threadName: String, failure: Throwable): String = runCatching {
        val msg = failure.message?.takeIf { it.isNotBlank() }
        val head = "[$threadName] ${failure.javaClass.simpleName}${if (msg != null) "：$msg" else ""}"
        val frames = failure.stackTrace.take(DAEMON_DEATH_FRAMES).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        if (frames.isEmpty()) head else "$head  栈顶${DAEMON_DEATH_FRAMES}帧：$frames"
    }.getOrDefault("[$threadName] 死因不可读")

    /**
     * 滚动保留的**已发件**报告份数：失败反复触发也不挤爆私有存储。
     *
     * 值维持 10 不变（不改既有阈值）；本波改的是它的**作用范围**——滚动删除现在只碰「已经在
     * `.uploaded` 索引里」的已发件，未发件不再从这个预算里被挤掉（见 [prune] 的第一道闸）。
     */
    const val KEEP_REPORTS = 10

    /**
     * **未发件堆积上限**（[prune] 的第二道闸）：一份都没发出去的积压最多留这么多，超出删最旧。
     *
     * ## 为什么必须是第二道闸（结的病）
     *
     * 断网玩家的证据此前会被自己的滚动剪枝删掉：`KEEP_REPORTS` 那 10 格过去是**已发件与未发件
     * 抢同一个预算**，按 mtime 删最旧——而最旧的往往正是还没发出去的那几份（发出去成功的通常
     * 更新）。本波把预算拆成两格独立的：**已发件留 [KEEP_REPORTS]、未发件留本值**，未发件不再
     * 被已发件挤出局（第一道闸跳过未发件）。但「完全跳过未发件」会把上限交给运气——一台长期
     * 离线的机器能无限攒未发件，所以要这第二道闸给未发件一个**写死的天花板**。
     *
     * ## 为什么是 10（算术理由，不许拍脑袋）
     *
     * - 修复的重点**不是把这个数调大**，而是「两桶不再抢一份预算」：同步顺畅的机器仍然只留 ≤10
     *   （全已发件，走第一道闸），彻底离线的机器则「未发件 ≤10 ＋ 已发件 ≤10」——未发件拿到
     *   **一整份独立的 10 格窗口**，不再被已发件挤掉，这正是本波要的保护。
     * - 10 与 [KEEP_REPORTS] 同值刻意取齐：两闸共用一把尺，最坏盘上报告数 = 10 + 10 = **20 份**。
     * - 体积天花板：单份报告受 core 侧各节限行与单行截断约束，实测最坏约 15 万字符（≈ ≤500 KB），
     *   20 份 ≈ ≤10 MB；对照本包随身的 743 MiB 游戏资源可忽略，且这是**硬顶**——第一道闸碰不到
     *   未发件，全靠本闸兜住「断网玩家攒满磁盘」。
     */
    const val MAX_PENDING_REPORTS = 10

    /** 屏上尾部采集行数（截断由 [ErrorReport] 再兜一道）。 */
    private const val CONSOLE_TAIL_LINES = 60

    /**
     * 抛点镜像帧行的口径说明行（实时帧表已回卷、改用 VM 镜像时垫在帧链前面）。
     *
     * 两种读数必须分得开：实时帧表是「采集那一刻还挂在栈上的帧」，镜像是「异常离开
     * `run()` 之前最后一帧表的样子」。混成一节而不标注，读报告的人会拿镜像当现场去核
     * 「现在卡在哪」。
     */
    internal const val THROW_MIRROR_MARK = "（以下帧链取自抛点镜像：实时帧表已随异常离栈回卷）"

    /** 因果链每一层保留的栈帧数（保头部 = 抛出点；见 [stackChainLines]）。 */
    private const val STACK_LEVEL_FRAMES = 25

    /** 随包自述报告的 assets 路径（写侧：`app/build.gradle.kts` 的 `stageGameAssets`）。 */
    private const val ASSET_PACKAGE_REPORT = "erbc.v1/package.report"

    /**
     * 从 APK assets 读随包 `package.report` 的 `program-sha256` 行（产物指纹）。
     *
     * 解包器（platform 的 `GameAssetInstaller`）只按固定名字取 program/meta/parts/manifest，
     * **不把 `package.report` 落盘**，所以这里只能从 `AssetManager` 直读。该文件是几 KB 的
     * 纯文本自述（不是 329 MB 的 program），逐行读到目标行就停。
     *
     * 铁律：**绝不抛**——assets 缺失、IO 失败、行不存在一律返回 null，
     * 由 core 渲染成「产物指纹：未知」；报告缺行总比报告写不出来强。
     */
    private fun programSha256FromAssets(context: Context): String? = runCatching {
        context.assets.open(ASSET_PACKAGE_REPORT).bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("program-sha256 ")) {
                    return trimmed.removePrefix("program-sha256 ")
                        .trim()
                        .trim('"')
                        .takeIf { it.isNotEmpty() && it != "-" }
                }
                line = reader.readLine()
            }
            null
        }
    }.getOrNull()

    /**
     * 采集现场并落一份报告；返回报告文件，失败返回 null（原因见 Log）。
     *
     * 构建标识（`BuildConfig.eratwBuildStamp`）与产物指纹（[programSha256FromAssets]）
     * 在这里进报告：值由宿主给，节式与「未知」兜底口径在 core（[ErrorReport]）。
     *
     * 双层兜底（2026-10-31 玩家报告 003351 实证）：完整报告在**堆将尽**时生成，拼串/渲染
     * 本身可能 OOM —— 那份报告里主错误被二次 OOM 顶掉，等于什么都没留下。所以完整生成
     * 一旦失败，退化为只写「类型 + 根因 + 脚本位置」的最小报告（[collectMinimal]）：
     * 正文仍带 core 的报告标记（端点据此验真），上传扫描按 mtime 取最新，崩溃档能发出去。
     *
     * @param context 任意 Android 上下文（取 filesDir 与包信息）
     * @param host 当前宿主；null（崩溃发生在装载前）时跳过会话侧现场
     * @param kind 报告类型（「脚本运行报错」「启动失败」「应用崩溃」…）
     * @param error 异常对象；null = 无异常对象的失败（状态机终局等）
     * @param extraLines 补充现场行（调用方自定读数；内存现场行由本类统一追加，见 [memoryLines]）
     * @param heartbeat 三路心跳（obs-heartbeat 车道，**只读**、**必传无默认值**）；
     *   null = 本调用点确实拿不到（冷启补报上一轮的死：那时的心跳属于已经没命的进程），
     *   小节照写成「未采到」，不许谎报。
     *   为什么把默认值摘掉：这一枚此前 `= null`，代价是**静默**—— 宿主侧 `writeErrorReport`
     *   漏传，2026-11-01 那份真机中局终局报告的「[线程心跳]」三行就全写成「未采到（没有
     *   心跳实例）」，而那一刻进程活得好好的、UI 与 VM 两路都在打点。没有心跳的报告分不开
     *   「卡住」与「崩了」，正是这类报告最该回答的问题。现在三个调用点各自表态：宿主侧与
     *   崩溃侧传实例，冷启补报显式传 null。
     * (rebase 合并注：本节与 [collect]/[collectFull] 的拆分来自 2026-11-01 报告加固波。)
     *
     * ## 上报节流（EP1-L6：可恢复终局之后，同型报错不再刷屏）
     *
     * 只有 [THROTTLED_KIND] 那一类进节流判式（[ErrorReportThrottle.decide]），判式三件事：
     * 同签名本局只落第一份、每局最多 [MAX_REPORTS_PER_SESSION] 份、计数按 [PIN_COOLDOWN_MS]
     * 的节奏钉一行运行轨迹。**被抑制时返回的是同签名首份那个文件**（不是 null）：
     * 失败屏的「复制报告 / 分享报告」因此仍然递给玩家一份真实场，宿主侧的 `lastReportFile`
     * 也不会被一次「没落盘」抹成上一份不相干的报告。
     *
     * 判式本身失败（取签名/记账抛）一律**退回「就落一份」**：宁可刷屏也不丢现场，
     * 这条方向与 [ErrorReportThrottle] 的类头同源。
     */
    fun collect(
        context: Context,
        host: GameHost?,
        kind: String,
        error: Throwable?,
        extraLines: List<String> = emptyList(),
        heartbeat: Heartbeat?,
    ): File? {
        if (!isThrottledKind(kind)) {
            return collectFull(context, host, kind, error, extraLines, heartbeat)
                ?: collectMinimal(context, kind, error)
        }
        // 会话身份：同一个 GameSession 实例 = 同一局（回到标题再开局会换一枚新 session，
        // 计数表随之清零）。读的是 GameHost 的会话字段（GameHost.kt:1475），一次字段读、
        // 不碰映射页、不加锁，崩溃线程上同样安全；拿不到会话就退到宿主实例身份。
        val sessionKey = sessionKeyOf(host)
        val signature = runCatching { ReportSignature.of(kind, error, extraLines) }.getOrNull()
        val nowMs = System.currentTimeMillis()
        val decision = runCatching {
            if (signature == null) ThrottleDecision.WRITE else throttle.decide(sessionKey, signature, nowMs)
        }.getOrElse {
            Log.w(TAG, "报错节流判式失败（按不去重处理：宁可刷屏也不丢现场）", it)
            ThrottleDecision.WRITE
        }
        // 计数行钉轨迹：冷却在判式里裁决，这一发是抑制路上唯一会碰盘的动作。
        decision.trailPhase?.let { phase ->
            runCatching { RunWatch.forceSample(context, phase) }
                .onFailure { Log.w(TAG, "报错计数钉轨迹失败（不影响报告与游戏）", it) }
        }
        if (decision.action != ThrottleAction.WRITE) {
            Log.i(
                TAG,
                "同型报错已抑制：${signature?.display ?: "（签名不可读）"}" +
                    "（第 ${decision.occurrences} 次，本局累计抑制 ${decision.suppressedTotal} 条）",
            )
            return decision.reportFile
        }
        val file = collectFull(context, host, kind, error, extraLines, heartbeat)
            ?: collectMinimal(context, kind, error)
        // 落盘成功才把签名记进表（file == null ⇒ 从表里摘掉，下一次同型再试整份）：
        // 「报不出来的那份」永远不算报过，这是「失败必须留痕」的下半句。
        runCatching {
            if (signature != null) throttle.noteWritten(sessionKey, signature, file)
        }.onFailure { Log.w(TAG, "报错节流记账失败", it) }
        return file
    }

    /** 这一类报告受不受节流管制（[THROTTLED_KIND] 之外的终局类一律不受管，理由见其 KDoc）。 */
    internal fun isThrottledKind(kind: String): Boolean = kind == THROTTLED_KIND

    /**
     * 会话身份（节流表的清零键）：本局会话实例 → 宿主实例 → null（没有会话）。
     *
     * 为什么用身份而不是名字：`GameHost` 一个 Activity 一个实例，会话在「回到标题 → 新游戏」
     * 时换一枚新的 `GameSession` 而宿主不换 —— 键跟着会话走，「新一局」才真的清零；
     * 只认宿主身份会把上一局的账带进新局（最坏情况：新一局的第一条同型报错被吞掉）。
     */
    private fun sessionKeyOf(host: GameHost?): String? = runCatching {
        host?.debugSession()?.let { "session@" + System.identityHashCode(it) }
            ?: host?.let { "host@" + System.identityHashCode(it) }
    }.getOrNull()

    /**
     * 节流账面（[ErrorReportThrottle.summary] 的只读包装）：本局落了几份、抑制了几条、
     * 前三名是哪三个形状。
     *
     * 为什么要有一张账面而不只是 logcat：被抑制的那些条**不落文件**，端点侧唯一能看到
     * 「这一页其实撞了 21 次」的地方是运行轨迹（[RunWatch.forceSample] 钉的那一行，
     * 随下一次冷启的「上次异常退出」报告一起出去）。宿主若想把它直接写进报告的
     * 「补充现场」，调这一枚即可（`extraLines` 拼接点在 `GameHost.writeErrorReport`，
     * 归组长接线）。**本局一条都没抑制过时它是空表**，接进去也不动首份报告正文一个字
     * （口径见 [ErrorReportThrottle.summary]）。绝不抛：读不到就一行说明。
     */
    internal fun throttleSummary(): List<String> =
        runCatching { throttle.summary() }.getOrElse { listOf("（报错节流读数不可用：${it.javaClass.simpleName}）") }

    /**
     * 显式清账：把本进程的节流表清零（新一局 / 会话释放）。
     *
     * 会话身份（[sessionKeyOf]）已经在「键一变就清零」，这一枚是给宿主的**双保险**：
     * `identityHashCode` 在旧会话被回收后理论上能被新会话复用（JVM 不保证不复用地址），
     * 那一发会把上一局的账带进新局 —— 也就是把新一局的第一条真实现场当成重复。
     * `GameHost.releaseSession` 调它就把这条尾巴彻底剪掉（接线归组长）。
     */
    internal fun resetThrottle(reason: String) {
        runCatching { throttle.reset() }
            .onSuccess { Log.i(TAG, "报错节流清账（$reason）：本局计数与签名表已归零") }
            .onFailure { Log.w(TAG, "报错节流清账失败（下一次会话开头自带清零）", it) }
    }

    /** 完整报告（采集 + 渲染 + 落盘）；任何一步失败返回 null，交给 [collectMinimal] 接手。 */
    private fun collectFull(
        context: Context,
        host: GameHost?,
        kind: String,
        error: Throwable?,
        extraLines: List<String>,
        heartbeat: Heartbeat?,
    ): File? = runCatching {
        val now = System.currentTimeMillis()
        // 心跳小节排在补充现场的**靠尾**位置（它后面还有冻结现场与守护线程死因两节）：core 侧对这一节是
        // `takeLast(EXTRA_MAX_LINES)`（尾部保留，core/diag/ErrorReport.kt:164），而脏退出报告
        // 前面的轨迹就有 68 行 —— 放在最前面会被整节剪掉，那份「死在屏前」的报告正是最需要
        // 读到心跳的一份。时刻口径必须是 `elapsedRealtime`（与打点同一把单调钟），不能用上面
        // 的墙钟 `now`。
        val heartbeatLines = HeartbeatJudgment.freezeLines(
            android.os.SystemClock.elapsedRealtime(),
            heartbeat?.snapshot(),
        )
        val report = ErrorReport(
            kind = kind,
            timestampText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now)),
            headerLines = headerLines(context),
            buildStamp = BuildConfig.eratwBuildStamp,
            channelStamp = BuildConfig.eratwReportChannelStamp,
            programSha256 = programSha256FromAssets(context),
            errorType = error?.javaClass?.name ?: "（无异常对象，见补充现场）",
            errorMessage = error?.message ?: "",
            rootCause = ErrorReport.rootCauseOf(error),
            errorStackLines = error?.let { stackChainLines(it) }.orEmpty(),
            scriptLocation = ErrorReport.scriptLocationOf(error),
            vmStackLines = vmStackLines(host, error),
            exitForensicsLines = exitForensicsLines(context, now),
            consoleTailLines = host?.debugConsoleLines(CONSOLE_TAIL_LINES).orEmpty(),
            // 内存现场统一由采集侧追加：OOM 报告缺这节就定不了根因（2026-10-31 两份 OOM
            // 报告的「补充现场」只有一行「崩溃线程：main」的实际教训）。次序 = 调用方自定行 →
            // 内存读数 → 约会现场 → 存档落盘留痕 → 读文件留痕 → 心跳 → 冻结现场 → 守护线程死因：core 对这一节是
            // takeLast 尾部保留，越靠后越活得下来；守护死因垫在最尾（本波 L2 新增，取一次即清），
            // 空表时整节不写，非空时 ≤ 1(说明)+4(留痕) 行，不动既有几块的相对次序。
            // （**位置**，最多 [StallJudgment.SCENE_MAX_LINES]=31 行）排在它之后当最尾，
            // 于是窗口从 40 提到 80（`ErrorReport.EXTRA_MAX_LINES` 的 KDoc 记了这笔账）——
            // 不提的话最前面的内存读数会被剪掉，而 OOM 归因恰恰靠那几行。
            //
            // 存档留痕（[saveDiagLines]）排在心跳之前而不是最前：这条通道原来只打 `System.err`
            // （= logcat），而玩家读不到 logcat —— 2026-11-01「不能覆盖旧存档」那条反馈因此
            // 一直只有一句屏上文案。挂进报告的尾部保留段，玩家下一次发报告就自带现场。
            extraSectionLines = extraLines + memoryLines(host) + datingLines(host) +
                saveDiagLines() + fileReadTrailLines(host) + heartbeatLines +
                stallSceneLines() + drainDaemonDeathLines(),
        )
        val dir = reportDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        var file = File(dir, ErrorReport.fileNameAt(now, java.util.TimeZone.getDefault().toZoneId()))
        if (file.exists()) {
            // 同一秒内的第二份（罕见）：毫秒后缀避让，不覆盖前一份。
            file = File(dir, "eratw-report-" + now + ".txt")
        }
        file.writeText(report.render(), Charsets.UTF_8)
        prune(dir)
        Log.i(TAG, "错误报告已生成：${file.name}（${file.length()} B）")
        file
    }.getOrElse {
        Log.w(TAG, "错误报告生成失败（转最小退化报告）", it)
        null
    }

    /**
     * 最小退化报告：完整报告写不出时的最后一张网。
     *
     * 保留定位所需的最少信息（类型 / 根因 / 脚本位置 + 版本与构建标识 + 堆水位 + 轨迹末行），
     * 逐项 runCatching——堆将尽时
     * 连「走 cause 链」都可能失败，缺行不缺报告。正文第一行仍是 core 的报告标记
     * （`worker.js` 的 `REPORT_MARKER` 验真口径），所以这份报告照样能走端点发出来。
     * 这一步若也失败（内存真的一丝不剩），只能放弃——上层 [GameHost.writeErrorReport]
     * 的「lastReportFile 保持上一份」口径接管。
     */
    private fun collectMinimal(context: Context, kind: String, error: Throwable?): File? = runCatching {
        val now = System.currentTimeMillis()
        val text = minimalReportText(
            kind = kind,
            // 逐项 runCatching（二审备注①）：SimpleDateFormat 的构造/格式化在堆将尽时同样可能
            // OOM——它炸不该连累整份最小报告；退化为裸毫秒时间戳。
            timestampText = runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
            }.getOrDefault("$now"),
            error = error,
            // 身份与水位五组，**逐组自兜**（取不到只少一行）：每一组都只做「读一个编译期常量 /
            // 一次标量系统调用 / 取环形表最后一个引用」，不读 assets、不查 PackageManager、
            // 不建集合 —— 完整报告正是死在这些采集面上的，退化报告不能再走一遍。
            appVersionText = runCatching {
                "${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})"
            }.getOrNull(),
            buildStamp = runCatching { BuildConfig.eratwBuildStamp }.getOrNull(),
            channelStamp = runCatching { BuildConfig.eratwReportChannelStamp }.getOrNull(),
            memoryTierLine = runCatching { MemoryTierReadout.line() }.getOrNull(),
            heapLine = minimalHeapLine(),
            trailTailLine = runCatching { RunWatch.lastTrailLine() }.getOrNull(),
        )
        val dir = reportDir(context)
        if (!dir.isDirectory) dir.mkdirs()
        var file = File(dir, ErrorReport.fileNameAt(now, java.util.TimeZone.getDefault().toZoneId()))
        if (file.exists()) file = File(dir, "eratw-report-" + now + ".txt")
        file.writeText(text, Charsets.UTF_8)
        runCatching { prune(dir) }
        Log.w(TAG, "已落最小退化报告：${file.name}（${file.length()} B）")
        file
    }.getOrElse {
        Log.w(TAG, "最小退化报告也写不出（内存极紧张）", it)
        null
    }

    /**
     * 退化报告的堆水位行：三个标量、两次系统调用，取不到整行缺省。
     *
     * 为什么这一行值得在「自己也可能分配失败」的时刻去取：三份退化报告的正文
     * （2026-11-03 那两份 `224 byte allocation with 1504 free bytes`）说的是**哪一次分配**输了，
     * 但端点台账要分的是「Java 堆贴顶」还是「native/PSS 把整机吃掉」—— 少了 max 与 native
     * 这两个数就只能猜（口径同 [memoryLines]，只是那里能读 `Debug.MemoryInfo` 的 PSS 分项，
     * 那一枚要 binder 往返 + 分配一个大对象，本时刻不碰）。
     */
    private fun minimalHeapLine(): String? = runCatching {
        val rt = Runtime.getRuntime()
        val used = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)
        val max = rt.maxMemory() / (1024L * 1024L)
        val native = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
        "退化时堆读数：已用 ${used}MiB / 上限 ${max}MiB / native ${native}MiB"
    }.getOrNull()

    /**
     * 最小退化报告的纯文本（internal 供 JVM 单测锁格式与标记行）。
     *
     * 单行超长按 core 的 [ErrorReport.LINE_MAX_CHARS] 截：OOM 的 message 是一整行长话，
     * 原样进报告既淹没关键信息也多分配。
     *
     * 五个可缺省的定位行（版本 / 构建标识 / 内存档位 / 堆读数 / 轨迹末行）由调用方取好再进来，
     * 本函数只做拼接：**传 null 就是那一行不存在**，不写「未知」占位——这张网要尽可能小，
     * 而端点侧的分桶脚本按 `app 版本：` / `构建标识：` 两个标签取数（`devtools/triage_reports.py`
     * 的 `_field` 那两条正则），补上这两行等于把三份退化报告从「ver=?」桶里捞回来。
     */
    internal fun minimalReportText(
        kind: String,
        timestampText: String,
        error: Throwable?,
        appVersionText: String? = null,
        buildStamp: String? = null,
        channelStamp: String? = null,
        memoryTierLine: String? = null,
        heapLine: String? = null,
        trailTailLine: String? = null,
    ): String {
        fun clip(line: String): String =
            if (line.length <= ErrorReport.LINE_MAX_CHARS) line else line.take(ErrorReport.LINE_MAX_CHARS) + "…（超长截断）"
        return buildString {
            appendLine("eraTW 安卓移植版 错误报告")
            appendLine("报告类型：${clip(kind)}")
            appendLine("报告时间：$timestampText")
            appendLine("（完整报告生成失败——多为内存已见底——本文件是最小退化报告，只保留定位所需的最少信息。）")
            appVersionText?.let { appendLine("app 版本：${clip(it)}") }
            buildStamp?.let { appendLine("构建标识：${clip(it)}") }
            channelStamp?.let { appendLine("上报通道：${clip(it)}") }
            memoryTierLine?.let { appendLine(clip(it)) }
            heapLine?.let { appendLine(clip(it)) }
            trailTailLine?.let { appendLine("轨迹末行：${clip(it)}") }
            appendLine("类型：${clip(error?.javaClass?.name ?: "（无异常对象）")}")
            error?.message?.takeIf { it.isNotBlank() }?.let { appendLine("信息：${clip(it)}") }
            runCatching { ErrorReport.rootCauseOf(error) }.getOrNull()?.let { appendLine("根因：${clip(it)}") }
            runCatching { ErrorReport.scriptLocationOf(error) }.getOrNull()?.let { appendLine("脚本位置：${clip(it)}") }
            appendLine("报告结束")
        }
    }

    /**
     * 内存现场行（进「[补充现场]」节头部）。
     *
     * 取的都是标量：Java 堆三列之二（已用/上限）、native 已分配、`Debug.MemoryInfo` 的
     * PSS 分项 —— OOM 归因的四问（Java 堆满没满？native 占多少？PSS 里谁是大头？图形池
     * 驻留多少？）一次答完。任何一项读数失败整节降级成一行说明，绝不抛。
     */
    private fun memoryLines(host: GameHost?): List<String> = runCatching {
        val rt = Runtime.getRuntime()
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        MemoryForensics.lines(
            javaUsedMiB = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L),
            javaMaxMiB = rt.maxMemory() / (1024L * 1024L),
            nativeAllocatedMiB = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L),
            dalvikPssMiB = info.dalvikPss / 1024L,
            nativePssMiB = info.nativePss / 1024L,
            totalPssMiB = info.totalPss / 1024L,
            graphicsLines = listOfNotNull(
                host?.graphicsPoolReportLine(),
                host?.imageBookkeepingReportLine(),
                // Java 堆分项账面（EP1 波 L5 heap-census；组长接线）：`used/max` 那两列只说
                // 「满了」，这一行说「装的是什么」—— 装载侧函数/语句/表达式/串池、运行侧格子与
                // 角色表、显示侧行数、图形 Java 侧正号段像素。读数全是已驻留标量，不踩 .erbc
                // 惰性表（约束见本文件类头与 `LiveSetCensus` 类头），取不到整行缺省不补。
                host?.memoryCensusReportLine(),
                // CSV 资源表读数（挂进报告而非只落 logcat）：4.981 与 4.980 数据混装时
                // 立绘整块不出现，此前报告里一个字都看不出来，只能靠上报端点编号反推。
                // 排在图形侧读数最尾 = 补充现场里较靠后的位置，满档剪行时活得下来。
                host?.resourceCensusReportLine(),
            ),
        )
    }.getOrElse {
        listOf("（内存现场读数不可用：${it.javaClass.simpleName}）")
    }

    /**
     * 主线程冻结现场（EP1 波 L4 stall-stack；组长接线）：哨兵判定 UI 帧循环超期那一刻抓到的
     * **主线程 + `eratw-vm` 两条栈**。本进程没抓到过 ⇒ **空表、不写这一节**（与哨兵自己的
     * 「未取到」是两回事：那是判式在动但栈没拿到，这一节整个不存在意味着压根没冻结过）。
     *
     * 读的是进程级槽位 `MainActivity.currentStallSceneLines()`（身份闸，口径同崩溃处理器那条
     * `CrashForensics` 侧的 `crashHostProvider`）：「上次异常退出」那份是**下一次冷启动**落的，那时旧 Activity 早没了，
     * 只有这种槽位还读得到本进程死前的最后一发。取不到只当没有这一节 —— 报告路径永远不许被
     * 取证拖崩。
     */
    private fun stallSceneLines(): List<String> = runCatching {
        MainActivity.currentStallSceneLines()
    }.getOrDefault(emptyList())

    /**
     * 约会现场行（diag-datingsync）：「一个人出门却被说在约会」与「路人子没问忘不忘」两条
     * 玩家反馈的设备侧自证。读数本体与常量出处见 [GameHost.datingForensicsReportLine] 和
     * core 侧 `DatingForensics` 类头。
     *
     * 与 [memoryLines] 不同的是这里**缺行不补说明**：语料侧的 `#DIM SAVEDATA` 基准读不到时
     * 这一节直接不写（一片 0 的现场比没有现场更容易误导定性）。
     */
    private fun datingLines(host: GameHost?): List<String> =
        listOfNotNull(runCatching { host?.datingForensicsReportLine() }.getOrNull())

    /**
     * 存档落盘留痕（`core/save/SaveDiagnostics` 的环形缓冲）。
     *
     * 为什么必须进报告：`SAVEDATA` 失败在语义上**只是一句屏上文案**（`saveData` 返回 false，
     * 异常被原版口径整个吞掉），既不会抛到宿主、也不会触发任何一次报告采集 —— 于是玩家报
     * 「不能覆盖旧存档」时，开发者手上只有那一句文案，没有阶段（抓变量池 / 序列化 / 落盘）、
     * 没有存档目录现场、没有异常本体。这条通道原本只打 `System.err`（= logcat），而 release
     * 包上玩家读不到 logcat（`run-as` 对 release 不可用，DevTools 只对 debuggable 开）。
     *
     * 所以：失败之后**只要再产生任何一份报告**（下一次崩溃 / 启动失败 / 脚本报错），
     * 这里就把留痕一起带出去。空表 = 本会话没发生过存档失败，此时一行都不写。
     *
     * 口径与 [datingLines] 一致：只出数值与相对路径，不取绝对路径（报告可能被贴到公开场合）。
     */
    private fun saveDiagLines(): List<String> {
        val trail = runCatching { com.eratw.port.core.save.SaveDiagnostics.snapshot() }.getOrNull()
        if (trail.isNullOrEmpty()) return emptyList()
        return listOf("存档落盘留痕（最近 ${trail.size} 条，旧在前）：") + trail
    }

    /**
     * 「找不到文件 X」族的读轴留痕（`core/diag/FileReadTrail`，lane sqlroot 2026-11-05）。
     *
     * 为什么必须进报告：移植侧相对路径**只按宿主根解析、出根即 null**，而屏上文案只带相对路径
     * （真机 `SQL_IMPORT_MAP_XML：找不到文件 plugins/tw_csv_chs.xml` 一族）。2026-11-04 那一红
     * 是把装置根 / 宿主构造点 / 抛点全部串起来反推才定到「它试的是沙盒根」——本行把那一手压成一行。
     *
     * 口径与 [saveDiagLines] 同一族：`fullRoot` 用默认 `true`（出全根）。这条读数的**全部价值就在
     * 根绝对路径**（两根末段可能同名，截段即不可判），而 Android 上宿主根在应用私有数据目录、
     * 不含玩家可识别信息；桌面装置上会带出盘符与用户名——要收口成掩码口径只翻这一枚布尔。
     *
     * `collectMinimal` **不接**这一节：那份是堆将尽时的最后一张网，逐节都要能失败；
     * 而读轴留痕只在「会话还活着」时取得到（`host.debugSession()`），退化报告里必然取不到。
     */
    private fun fileReadTrailLines(host: GameHost?): List<String> =
        runCatching { FileReadTrail.trailLines(host?.debugSession()?.store) }.getOrDefault(emptyList())

    /**
     * 异常自身的栈（含因果链）逐层展平成报告行。
     *
     * 为什么不用现成的 `stackTraceToString()`：它把**顶层**的完整帧表放最前，
     * 而 core 侧截断是尾部保留（根因在最里层）——顶层 ART 栈动辄四五十帧，
     * 直接切尾会把最顶层的抛出点整段丢掉。这里逐层限帧（每层最多 [STACK_LEVEL_FRAMES] 帧、
     * 保头部=抛出点），保证每一层的「类: 消息」与现场帧都在。
     */
    private fun stackChainLines(error: Throwable): List<String> {
        val out = ArrayList<String>()
        var node: Throwable? = error
        var level = 0
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        while (node != null && level < 8 && seen.put(node, true) == null) {
            val label = if (level == 0) "异常" else "第$level 层"
            val msg = node.message?.takeIf { it.isNotBlank() }
            out += "--- $label：${node.javaClass.name}${if (msg != null) "：$msg" else ""} ---"
            val frames = node.stackTrace
            frames.take(STACK_LEVEL_FRAMES).forEach { out += "  at $it" }
            if (frames.size > STACK_LEVEL_FRAMES) out += "  …（共 ${frames.size} 帧，余下省略）"
            node = node.cause
            level++
        }
        return out
    }

    /**
     * 版本与设备小节（错误报告与运行诊断共用同一套口径）。
     *
     * 前三行（app 版本 / 设备 / 系统）**逐字、逐序不动**：收件箱的分桶统计脚本按这三行的标签与
     * 次序取数（波次契约 §2/L2 通则第 5 条「不许动任何既有报告小节的字段顺序」）。
     *
     * 其后追加的是**机型档硬件读数**（[deviceReadoutLines] → [DeviceForensics]）：补上 BRAND /
     * PRODUCT / FINGERPRINT(ROM) / ABI / 总内存 / isLowRamDevice / 可用存储 / fontScale /
     * densityDpi / 无障碍开关 / 电池温度这一层，让收件箱能把「同一台 OPPO PLD110 贡献了 6/11 慢页」
     * 这类集中度**按机型 / ROM / 内存档分桶**（现在只有 3 行根本分不出这一层）。取数写法照
     * `app/src/androidTest/.../M10Evidence.kt:91-93`（brand/product/abi）与提交 48991308 的
     * 档位行纪律——**逐读数 runCatching，取不到只写「未知」，绝不让采集面带死整份报告**。
     *
     * PII 红线：只加机型档硬件读数，**不采**设备序列号 / IMEI / MAC / ANDROID_ID / 账号 /
     * 含玩家名的绝对路径；隐私口径仍以 `core-vm/.../diag/ErrorReport.kt` 类头为准，本节不触碰它。
     * 无障碍只报「开/关」总闸、不列服务包名；电池温度走免权限的粘性广播读数；
     * **任何一项都不为取数申请权限**。
     */
    fun headerLines(context: Context): List<String> {
        val version = runCatching {
            val pm = context.packageManager.getPackageInfo(context.packageName, 0)
            "${pm.versionName} (versionCode ${pm.versionCode})"
        }.getOrDefault("（未知）")
        return listOf(
            "app 版本：$version",
            "设备：${Build.MANUFACTURER} ${Build.MODEL}",
            "系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            // 内存档位那一行（串与进程 logcat 同一支函数，见 MemoryTierReadout）：这台机跑的是
            // 放开档还是保守档，是 OOM 归因的第一个分岔口，而它只能在这一行被看见 ——
            // 真机 Origin OS 会丢掉应用 logcat，报告头部行是端点唯一收得到的通道。
            // 读数失败只少这一行，不许把整份报告带死（口径同上面的版本行）。
            runCatching { MemoryTierReadout.line() }.getOrDefault("内存档位=读数失败"),
        ) + deviceReadoutLines(context)
    }

    /**
     * 机型档硬件读数的**采集面**：逐个读数各自 runCatching，取不到交 null，格式化与「未知」
     * 兜底在纯 JVM 的 [DeviceForensics]（有 `ErrorReportDeviceReadoutTest` 钉死）。整段再兜一道：
     * 连拼装都抛（极端堆将尽）就退化成一行说明，绝不影响报告其余部分——与 [exitForensicsLines]
     * 「读数失败只少一行不带走整份报告」同一口径（提交 48991308）。
     *
     * 不申请任何权限：`getSystemService(ACTIVITY_SERVICE)` / `Build.*` 静态字段 / `StatFs` /
     * `resources` / `Settings.Secure` 的无障碍总闸 / 粘性电池广播，全部是免权限读数。
     */
    private fun deviceReadoutLines(context: Context): List<String> = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val totalRamBytes = runCatching {
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            if (mi.totalMem > 0L) mi.totalMem else null
        }.getOrNull()
        val lowRam = am?.let { runCatching { it.isLowRamDevice }.getOrNull() }
        val dataPath = runCatching { context.filesDir.path }.getOrNull()
        val statFs = dataPath?.let { p -> runCatching { StatFs(p) }.getOrNull() }
        DeviceForensics.lines(
            brand = runCatching { Build.BRAND }.getOrNull()?.takeIf { it.isNotBlank() },
            product = runCatching { Build.PRODUCT }.getOrNull()?.takeIf { it.isNotBlank() },
            fingerprint = runCatching { Build.FINGERPRINT }.getOrNull()?.takeIf { it.isNotBlank() },
            abis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrNull()?.takeIf { it.isNotEmpty() },
            totalRamBytes = totalRamBytes,
            isLowRamDevice = lowRam,
            availableStorageBytes = statFs?.let { runCatching { it.availableBytes }.getOrNull() },
            totalStorageBytes = statFs?.let { runCatching { it.totalBytes }.getOrNull() },
            fontScale = runCatching { context.resources.configuration.fontScale.toDouble() }.getOrNull(),
            densityDpi = runCatching { context.resources.displayMetrics.densityDpi }.getOrNull(),
            accessibilityEnabled = runCatching {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED) >= 1
            }.getOrNull(),
            batteryTempTenthsC = runCatching {
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val raw = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
                if (raw == Int.MIN_VALUE) null else raw
            }.getOrNull(),
        )
    }.getOrElse { listOf("（设备读数不可用：${it.javaClass.simpleName}）") }

    /**
     * ERB 函数调用栈（#0 最内帧）+ VM 线程 Java 栈顶。
     *
     * ## 帧名为什么不读 `program.functions`（2026-10-31 修「报告把 VM 线程一起带死」）
     *
     * 本函数会在 `Thread.setDefaultUncaughtExceptionHandler` 里被调
     * （`CrashForensics.install`），也就是**崩溃线程**上；而脚本异常正是从
     * VM 线程冒出来的 ⇒ 那一刻 VM 线程还活着，两条线程正在抢同一批解码状态。
     * `.erbc` 产物档的 `session.program.functions` 是 `MappedFunctionTable`
     * （core 的 loader 包）：条目**未命中缓存就地解码**，用的段窗口与局部串池的复用缓冲
     * 都是实例字段且**没有加锁**（同一文件里语句池 `LazyStatementRecords` 有
     * `materializeLock`，函数表这一路没有）。于是崩线程读一次函数名可能：
     * ① 把 VM 线程正在用的缓冲踩掉 ⇒ VM 线程接着死第二次、这份报告也写不完；
     * ② 把两条记录的串字节混成同一个名字 ⇒ 报告里是一个不存在的函数名（比没名字更坏）。
     *
     * 现在只读运行期函数表：[FunctionTable.byFunctionIndex] 是一次数组读 + 一次 final 字段读，
     * 那张表由会话装配期从侧车整体建好（`GameSession` 用 `FunctionTable(metadata.functions)`），
     * 不碰映射页、不分配、不加锁。名字口径不变：`FuncMeta.name` 就是「源码书写名（报错用）」，
     * erbcc 把同一个符号名同时写进产物段 4（`FunctionInfo.name`）与侧车（`FuncMeta.name`）。
     *
     * 取栈部分的反射口径与 `DevToolsHost.callstack` 一致（`ProcessRuntime.vm` →
     * `funcStack`/`depth`；那条路是调试后门、VM 停在安全点，本次不动）。字段改名 ⇒ 优雅降级：
     * 帧位留函数下标并写明「名不可读」，**绝不回落到 `program.functions`**（那正是本处要
     * 消掉的那条并发解码路），格式化在 [VmStack.frameLines]（有单测）。
     *
     * ## 实时帧表空了以后回落抛点镜像（2026-11-01）
     *
     * `VirtualMachine.run()` 的 finally 在异常离栈时把 `depth` 收成 -1，等到 [collect] 写报告
     * 早已回卷完毕 ⇒ 端点 2026-11-01 现存 100 份报告里 93 份的这一节只剩采集器自己的 Java 栈。
     * VM 在回卷之前镜像过一次（[VirtualMachine.funcStackAtThrow]），这里在「实时帧空 +
     * 本报告确实带异常」时用它，并垫一行说明把两种读数的口径分开——「上次异常退出」那类
     * 没有 throwable 的报告**不许**回落，否则会把上一局某条链错标成这次的现场。
     */
    private fun vmStackLines(host: GameHost?, error: Throwable?): List<String> {
        if (host == null) return emptyList()
        val session = host.debugSession() ?: return emptyList()
        val runtime = session.runtime
        val frames = runCatching {
            val vf = ProcessRuntime::class.java.getDeclaredField("vm")
            vf.isAccessible = true
            val vm = vf.get(runtime) ?: return@runCatching emptyList()
            val fs = vm.javaClass.getDeclaredField("funcStack").apply { isAccessible = true }
                .get(vm) as IntArray
            val depth = vm.javaClass.getDeclaredField("depth").apply { isAccessible = true }
                .getInt(vm)
            val resolve = functionNameResolver(runtime)
            VmStack.frameLines(fs, depth, ErrorReport.STACK_MAX_LINES, resolve)
                .ifEmpty { if (error == null) emptyList() else throwPointFrames(vm, depth, resolve) }
        }.getOrElse {
            listOf("（ERB 调用栈读数不可用：${it.javaClass.simpleName}）")
        }
        val javaTop = host.debugVmThread()?.stackTrace
            ?.take(8)
            ?.map { "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            .orEmpty()
        return frames + listOf("--- java ---") + javaTop
    }

    /**
     * 抛点镜像 → 报告帧行（带一行口径说明）；读不出就交回空表，与实时帧空表同样降级。
     *
     * 镜像的下标序与实时 `funcStack` 一致（最外层在前），所以直接复用
     * [VmStack.frameLines]，把 `depth` 交回 `size - 1`（= 帧数 - 1）即可。
     */
    private fun throwPointFrames(
        vm: Any,
        liveDepth: Int,
        resolve: (Int) -> String?,
    ): List<String> {
        if (liveDepth >= 0) return emptyList()
        val mirror = vm.javaClass.getMethod("funcStackAtThrow").invoke(vm) as? IntArray
        if (mirror == null || mirror.isEmpty()) return emptyList()
        return listOf(THROW_MIRROR_MARK) +
            VmStack.frameLines(mirror, mirror.size - 1, ErrorReport.STACK_MAX_LINES, resolve)
    }

    /**
     * 函数下标 → 名字：只走 [FunctionTable]（见 [vmStackLines] 的并发口径）。
     *
     * 表拿不到（字段改名、会话没装配完）返回「恒 null」的解析器，帧行退化成带下标的
     * 「名不可读」；单个下标读失败也只当读不到，不抛。
     */
    internal fun functionNameResolver(runtime: ProcessRuntime): (Int) -> String? {
        val table = runCatching {
            val tf = ProcessRuntime::class.java.getDeclaredField("table").apply { isAccessible = true }
            tf.get(runtime) as? FunctionTable
        }.getOrNull() ?: return { null }
        return { index -> runCatching { table.byFunctionIndex(index)?.name }.getOrNull() }
    }

    /**
     * 系统取证（[ExitForensics] 的 Android 侧接线）：本轮 PSS/RSS + 上一轮退出记录。
     *
     * 为什么必须挂在 [collect] 里而不是某一条调用路上：「上次异常退出」那份报告是
     * [RunWatch.beginProcess] 在**下一次冷启动**落的，走的就是同一个 [collect]——于是
     * 静默死（没有 throwable 的那一类）从此带上内核/系统自己的判词，而崩溃当场那一份
     * 也顺手带上本轮足迹。玩家那 14 条「闪退但没报错」第一次有了可分桶的读数。
     *
     * 成本与纪律：`Debug.getPss()` 一次 native 调用、`/proc/<pid>/statm` 一行、
     * 退出记录最多 [ExitForensics.MAX_EXIT_ROWS] 条（API 30 以下不调系统接口）。
     * 铁律照旧：**绝不抛**，任何一项失败只留一行说明。
     */
    private fun exitForensicsLines(context: Context, nowMs: Long): List<String> = runCatching {
        val rows = if (Build.VERSION.SDK_INT >= ExitForensics.API_R) {
            runCatching { previousExitRows(context) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        ExitForensics.lines(
            pssKib = runCatching { Debug.getPss().toLong() }.getOrDefault(ExitForensics.UNKNOWN),
            rssKib = runCatching { statmRssKib() }.getOrDefault(ExitForensics.UNKNOWN),
            rows = rows,
            nowMs = nowMs,
            apiLevel = Build.VERSION.SDK_INT,
            timestampText = { ms -> runCatching { dateFormat.format(Date(ms)) }.getOrDefault("$ms") },
        )
    }.getOrElse {
        listOf("（系统取证读数不可用：${it.javaClass.simpleName}）")
    }

    /** 本轮 RSS（KiB）：`/proc/<pid>/statm` 第 2 列是常驻页数，乘页大小；读不到 = [ExitForensics.UNKNOWN]。 */
    private fun statmRssKib(): Long {
        val line = runCatching {
            File("/proc/" + Process.myPid() + "/statm").bufferedReader().use { it.readLine() }
        }.getOrNull()
        val pageKib = runCatching {
            val bytes = Os.sysconf(OsConstants._SC_PAGESIZE)
            if (bytes >= 1024L) bytes / 1024L else 4L
        }.getOrDefault(4L)
        return ExitForensics.rssKibFromStatm(line, pageKib)
    }

    /**
     * 上一轮（最多 [ExitForensics.MAX_EXIT_ROWS] 条）的系统退出记录（API 30+）。
     *
     * `getHistoricalProcessExitReasons(pkg, 0, n)`：第一个参数给本包包名 = 只要本包的进程，
     * pid 传 **0** = 不限定当前 pid（要看的正是「上一个 pid」怎么死的）。这里不再按包名自筛，
     * 因为 compileSdk 36 的 `ApplicationExitInfo` **没有** `getPackageName()`
     * （2026-10-31 javap 全量成员表里没有这一条），系统接口本身已经把范围卡住了。
     * 次序不依赖文档口径：[ExitForensics.lines] 自己按时间戳降序取最新的几条。
     */
    @TargetApi(Build.VERSION_CODES.R)
    private fun previousExitRows(context: Context): List<ProcessExitRow> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val info: List<ApplicationExitInfo> =
            am.getHistoricalProcessExitReasons(context.packageName, 0, ExitForensics.MAX_EXIT_ROWS)
                ?: emptyList()
        return info.map {
            ProcessExitRow(
                timestampMs = it.timestamp,
                pid = it.pid,
                reasonCode = it.reason,
                status = it.status,
                importance = it.importance,
                pssKb = it.pss,
                rssKb = it.rss,
                description = it.description,
            )
        }.toList()
    }

    /**
     * 最新一条系统退出记录（API 30+）；低版本 / 读不到 = null。
     *
     * 给冷启动的脏退出判定用：若最新那条的死因是系统明确认领的非缺陷类（包更新 / 用户主动
     * 停止 / 权限变更），[RunWatch.beginProcess] 只认账、不再落「上次异常退出」——否则报告
     * 里「判读：非缺陷类退出」与「这是一份异常退出报告」自相矛盾。2026-11-04 端点报告
     * （vivo V2199A，0.2.40）的 `PACKAGE_UPDATED` 就是这一形态。
     */
    internal fun latestExitRow(context: Context): ProcessExitRow? =
        if (Build.VERSION.SDK_INT < ExitForensics.API_R) null
        else previousExitRows(context).maxByOrNull { it.timestampMs }

    /** 报告目录（[ReportFileProvider] 与本类共用同一个根）。 */
    fun reportDir(context: Context): File = File(context.filesDir, REPORT_DIR_NAME)

    /**
     * 有界保留报告目录：两桶各留各的窗口，**未发件不再被已发件挤掉**。
     *
     * ## 两道闸（本波 L2 结的「未发件被自己删掉」的病）
     *
     * 分桶只看一件事——这份的名字在不在 `.uploaded` 索引里（[ReportUploadQueue.readIndex]，
     * 与补发侧同一个账，逐份 `.uploaded` 索引在 `ErrorReportUploader` 维护）：
     * - **第一道闸**（[KEEP_REPORTS]）：滚动删除只碰**已发件**，按 mtime 保最新 [keep] 份、删更旧的。
     *   未发件在这里**一条都不删**——旧写法把「发过没有」完全不看，按 mtime 删最旧，而最旧的往往
     *   正是断网玩家还没发出去的那几份 ⇒ 永久丢证据。这就是被本波摘掉的那条保护。
     * - **第二道闸**（[maxPending]）：未发件积压超过 [MAX_PENDING_REPORTS] 才删最旧的，给完全离线
     *   的机器一个硬顶，免得无限攒（取值理由见 [MAX_PENDING_REPORTS]）。
     *
     * ## 排序口径不变（既有 `prune 只删最旧` 用例继续钉这条）
     *
     * 两桶内部都按 **mtime**（再按名字收口）而不是文件名排序：同秒第二份走
     * `eratw-report-<epochMillis>.txt` 避让名，纯数字名在字典序里排在
     * `eratw-report-yyyyMMdd-HHmmss.txt` 之前——名字排序会把**刚写好的那份**当成最旧删掉。
     *
     * ## 绝不抛
     *
     * `listFiles` 返回 null（目录被并发撤掉）直接 return；索引读不出按空集处理（此时全判成未发件，
     * 走第二道闸收口，仍是安全降级，不会误删已发件之外的东西）。
     */
    fun prune(dir: File, keep: Int = KEEP_REPORTS, maxPending: Int = MAX_PENDING_REPORTS) {
        val files = runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith("eratw-report-") }?.toList()
        }.getOrNull() ?: return
        if (files.isEmpty()) return
        val uploaded = runCatching {
            ReportUploadQueue.readIndex(File(dir, ErrorReportUpload.INDEX_FILE_NAME))
        }.getOrDefault(emptySet())
        val ordered = files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
        // 第一道闸：只对「已发件」（名字在 `.uploaded` 索引里）按 mtime 滚动留最新 [keep] 份；未发件一条不碰。
        deleteOldest(ordered.filter { it.name in uploaded }, keep)
        // 第二道闸：未发件积压超过 [maxPending] 才删最旧，给完全离线的机器一个硬顶（理由见 [MAX_PENDING_REPORTS]）。
        deleteOldest(ordered.filter { it.name !in uploaded }, maxPending)
    }

    /** 一个桶内部按 mtime 序删最旧、留最新 [keep] 份；逐份删除各自 runCatching（不抛）。 */
    private fun deleteOldest(filesByOldestFirst: List<File>, keep: Int) {
        if (filesByOldestFirst.size <= keep) return
        filesByOldestFirst.take(filesByOldestFirst.size - keep).forEach { f ->
            runCatching { f.delete() }
                .onFailure { Log.w(TAG, "旧报告删除失败：${f.name}", it) }
        }
    }

    /**
     * 读整份报告文本；读不到（不存在 / IO 出错）返回 null。
     *
     * 与 [putClipboardText] 拆开只为一件事：调用方可以把这一发**挪出 UI 线程**（obs-uiio 车道的
     * 裁定：整份报告最坏约 15 万字符的读盘落在一次点击里，属于该挪的那一类），
     * 读完再把剪贴板写入交回主线程（见 [putClipboardText]）。
     * 两个既有消费点都是这个拆法：失败屏「复制报告」（`MainActivity.copyReportFile`）与
     * 设置页「复制诊断」（`SettingsActivity.copyDiagnostics`，fix-uiowiring 车道把最后一条
     * UI 线程组合口也拆了）；原来的组合 `copyToClipboard` 因此删除 —— 留着一个「在调用方
     * 线程上读盘」的形状，就是给下一条按钮回调埋雷（点名防回归见 `StrictModeGuardTest`）。
     */
    fun readReportText(file: File): String? = runCatching {
        if (!file.isFile) null else file.readText(Charsets.UTF_8)
    }.getOrElse {
        Log.w(TAG, "报告读数失败：${file.name}", it)
        null
    }

    /** 把已读好的报告文本放进剪贴板；成功返回 true。**必须在主线程调**（剪贴板是 UI 侧的服务）。 */
    fun putClipboardText(context: Context, text: String): Boolean = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("eratw 错误报告", text))
        true
    }.getOrElse {
        Log.w(TAG, "报告复制到剪贴板失败", it)
        false
    }

    /**
     * 系统分享面板的 Intent（text/plain + content URI）。
     *
     * URI 由自研的 [ReportFileProvider] 提供（本工程不引 androidx.core，
     * FileProvider 在编译类路径上不可用）。
     */
    fun shareIntent(context: Context, file: File): Intent? = runCatching {
        val uri = Uri.Builder()
            .scheme(android.content.ContentResolver.SCHEME_CONTENT)
            .authority(ReportFileProvider.AUTHORITY)
            .appendPath(file.name)
            .build()
        Intent(Intent.ACTION_SEND).apply {
            type = ClipDescription.MIMETYPE_TEXT_PLAIN
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(
                Intent.EXTRA_TEXT,
                "eraTW 安卓移植版报错：见附件报告（eratw-reports/${file.name}）",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.getOrElse {
        Log.w(TAG, "分享 Intent 构造失败", it)
        null
    }
}

/**
 * ERB 调用栈的纯格式化层：把 `funcStack`/`depth` 两个原始读数摊成报告行。
 *
 * 抽出来的理由与 [RunTrail] 同口径：这一段是「崩在哪串函数」的唯一产出点，
 * 而它跑在崩溃线程上、没有 Android 依赖，判式必须在 JVM 单测里钉死
 * （见 `ErrorReportVmStackTest`）。
 */
internal object VmStack {

    /** 空帧槽（VM 的「未登记」哨兵，负值）：`depth` 之外的槽位没被清干净时就是这个。 */
    const val SLOT_UNSET_PREFIX = "[未登记帧]"

    /** 函数表读不出名字时留在帧位上的说明（不谎报成 `?`，也不回落到惰性解码面）。 */
    const val NAME_UNREADABLE = "名不可读"

    /**
     * 最内帧在前（`#0` = 当前函数），最多 [maxFrames] 帧。
     *
     * `depth` 是**栈顶帧下标**而不是帧数：Vm 起跑时 `depth = 0; funcStack[0] = 入口`，
     * 每次 `CALL` 先 `depth++` 再写 `funcStack[depth]`（`Vm.kt` 的 `run` 与调用路径），
     * 所以有效帧是 `0..depth`，共 `depth + 1` 帧。按帧数读会丢掉最内一帧，
     * 只在入口函数里崩时（depth = 0）整段 ERB 栈直接消失。
     *
     * @param funcStack VM 的函数下标栈（帧 0 在最外）
     * @param depth 栈顶帧下标；`< 0`（未在执行 / 已被 unwind 收成负值）出空表
     * @param resolve 下标 → 函数名；给 null = 这一帧的名字读不出
     */
    fun frameLines(funcStack: IntArray, depth: Int, maxFrames: Int, resolve: (Int) -> String?): List<String> {
        if (depth < 0 || funcStack.isEmpty() || maxFrames <= 0) return emptyList()
        val top = minOf(depth + 1, funcStack.size)
        val out = ArrayList<String>(minOf(top, maxFrames) + 1)
        var printed = 0
        var slot = top - 1
        while (slot >= 0 && printed < maxFrames) {
            out += "#$printed ${frameText(funcStack[slot], resolve)}"
            printed++
            slot--
        }
        if (top > maxFrames) out += "…（共 $top 帧，余下省略）"
        return out
    }

    /** 单帧文本：有名字给名字；负下标单独说明；读不出名字留下函数下标。 */
    fun frameText(index: Int, resolve: (Int) -> String?): String {
        if (index < 0) return SLOT_UNSET_PREFIX
        val name = runCatching { resolve(index) }.getOrNull()
        return if (name.isNullOrBlank()) "fn#$index（$NAME_UNREADABLE）" else name
    }
}

/**
 * 系统记下的一条进程退出记录（Android 侧从 `ApplicationExitInfo` 原样搬进来）。
 *
 * 只收标量与一段短文本：这条记录会在堆可能已经见底时被格式化，任何一项都不许带大对象。
 */
internal data class ProcessExitRow(
    /** 系统记录的退出墙钟（毫秒）。 */
    val timestampMs: Long,
    /** 死掉的那个进程的 pid。 */
    val pid: Int,
    /** 退出原因码（`ApplicationExitInfo.getReason()`）。 */
    val reasonCode: Int,
    /** 退出状态：被信号杀时是信号号，自行退出时是退出码。 */
    val status: Int,
    /** 退出时的进程重要度（`ActivityManager.RunningAppProcessInfo.IMPORTANCE_*`）。 */
    val importance: Int,
    /** 退出那一刻的 PSS（KiB）。 */
    val pssKb: Long,
    /** 退出那一刻的 RSS（KiB）。 */
    val rssKb: Long,
    /** 系统自己写的一句话（可能为空；渲染时硬截到 [ExitForensics.DESCRIPTION_MAX_CHARS]）。 */
    val description: String?,
)

/**
 * 报告里「内存现场」行的纯格式化（无 Android 依赖，`:app` JVM 单测直接锁）。
 *
 * ## 为什么必须有这一节
 *
 * 2026-10-31 的两份 OOM 玩家报告，异常栈顶分别是 `TextRun.copy` 与报告渲染自身的
 * `StringBuilder.append` —— 都是「堆早已满、谁碰谁死」的稻草，而报告的「补充现场」只有
 * 一行「崩溃线程：main」，四问（Java 堆满没满？native 占多少？PSS 里谁是大头？图形池
 * 驻留多少？）一个都答不了，根因无从定起。本节把这些标量固定进每份报告。
 *
 * 分配纪律与 [ExitForensics] 同源：只收标量、逐行拼接不回收、调用侧任何一项读不到
 * 整节降级成一行说明。
 */
internal object MemoryForensics {

    /**
     * 内存现场行（3 行读数 + 可选图形池一行）。
     *
     * @param javaUsedMiB Java 堆已用（`Runtime.totalMemory - freeMemory`）
     * @param javaMaxMiB Java 堆上限（`Runtime.maxMemory`，largeHeap 档）
     * @param nativeAllocatedMiB native 堆已分配（`Debug.getNativeHeapAllocatedSize`）
     * @param dalvikPssMiB PSS 的 Java/ART 分项（`Debug.MemoryInfo.dalvikPss`，KiB → MiB）
     * @param nativePssMiB PSS 的 native 分项（`Debug.MemoryInfo.nativePss`）
     * @param totalPssMiB PSS 总量（lmkd 杀进程看的就是它）
     * @param graphicsLines 图形侧读数行（宿主 [GameHost.graphicsPoolReportLine] 的引擎侧实况 +
     *   [GameHost.imageBookkeepingReportLine] 的脚本侧簿记；空表 = 会话未装配/读不到，整段省略）
     */
    fun lines(
        javaUsedMiB: Long,
        javaMaxMiB: Long,
        nativeAllocatedMiB: Long,
        dalvikPssMiB: Long,
        nativePssMiB: Long,
        totalPssMiB: Long,
        graphicsLines: List<String>,
    ): List<String> = buildList {
        add("本轮 Java 堆：已用 $javaUsedMiB MiB / 上限 $javaMaxMiB MiB")
        add("本轮 native 堆：已分配 $nativeAllocatedMiB MiB")
        add("本轮 PSS 分项：dalvik $dalvikPssMiB MiB / native $nativePssMiB MiB / 总 $totalPssMiB MiB")
        addAll(graphicsLines)
    }
}

/**
 * 报告「[版本与设备]」小节里**机型档硬件读数**那几行的纯格式化（无 Android 运行期依赖，
 * `:app` JVM 单测直接锁，见 `ErrorReportDeviceReadoutTest`；口径同 [MemoryForensics] / [ExitForensics]）。
 *
 * ## 为什么要单独抽出来
 *
 * 采集侧（[ErrorReportCollector.deviceReadoutLines]）碰 Android（`Build` / `ActivityManager` /
 * `StatFs` / `Settings.Secure` / 电池粘性广播），JVM 里跑不了；而「字节怎么变成 GiB、
 * 十分之几摄氏度怎么变成 29.5℃、布尔怎么变成 是/否/未知」这套**分桶脚本要吃的读数形状**必须在
 * 纯 JVM 钉死——它们一旦漂形（比如单位从 GiB 换成 MiB），收件箱按机型/内存档分桶的统计就错位。
 *
 * ## 铁律：只加机型档硬件读数，不加任何标识符
 *
 * 每一行都是「label：value」，value 为 null 一律渲染 [UNKNOWN_TEXT]（「缺行不缺报告」，取不到
 * 只是这一行写未知，绝不抛）。FINGERPRINT 是 ROM/固件标识（同批机相同、不含序列号），不是玩家
 * 个人标识；其余全是硬件/系统读数。序列号 / IMEI / MAC / ANDROID_ID / 账号 / 绝对路径里的玩家名
 * 一个都不进本节（隐私口径的权威在 `core-vm/.../diag/ErrorReport.kt` 类头，本节不触碰它）。
 *
 * ## 行数写死
 *
 * 固定 11 行（BRAND / PRODUCT / FINGERPRINT / ABI / 总内存 / isLowRam / 可用存储 /
 * fontScale / densityDpi / 无障碍 / 电池温度）。不是会增长的表——每项要么有值要么「未知」，
 * 恒 11 行，进 [ErrorReport] 的头部节时随整份一起受单行截断（LINE_MAX_CHARS）约束。
 */
internal object DeviceForensics {

    /** 读不到的标量显示文本（与 core 的 `ErrorReport.UNKNOWN_MARK` 同值，值内联避免依赖）。 */
    const val UNKNOWN_TEXT = "未知"

    private const val KIB = 1024L
    private const val MIB = KIB * 1024L
    private const val GIB = MIB * 1024L

    /**
     * 机型档硬件读数行（11 行；标签与次序写死，供收件箱按机型/ROM/内存档分桶）。
     *
     * @param brand [android.os.Build.BRAND]（分桶用，比 MANUFACTURER 更贴 OEM 子品牌）
     * @param product [android.os.Build.PRODUCT]
     * @param fingerprint [android.os.Build.FINGERPRINT]（ROM/固件标识；不含序列号）
     * @param abis [android.os.Build.SUPPORTED_ABIS]（主 ABI 在前）
     * @param totalRamBytes [android.app.ActivityManager.MemoryInfo.totalMem]（字节）
     * @param isLowRamDevice [android.app.ActivityManager.isLowRamDevice]（系统给的低内存判定）
     * @param availableStorageBytes 数据分区可用（字节）
     * @param totalStorageBytes 数据分区总量（字节；null 时可用存储那行只报可用）
     * @param fontScale [android.content.res.Configuration.fontScale]（字号缩放，非玩家设置，系统读数）
     * @param densityDpi [android.util.DisplayMetrics.densityDpi]
     * @param accessibilityEnabled 无障碍总闸是否打开（只报开/关，**不**列服务包名）
     * @param batteryTempTenthsC 电池温度（`BatteryManager.EXTRA_TEMPERATURE`，单位 = 0.1℃）
     */
    fun lines(
        brand: String?,
        product: String?,
        fingerprint: String?,
        abis: List<String>?,
        totalRamBytes: Long?,
        isLowRamDevice: Boolean?,
        availableStorageBytes: Long?,
        totalStorageBytes: Long?,
        fontScale: Double?,
        densityDpi: Int?,
        accessibilityEnabled: Boolean?,
        batteryTempTenthsC: Int?,
    ): List<String> = buildList {
        add("厂牌(BRAND)：${brand ?: UNKNOWN_TEXT}")
        add("产品(PRODUCT)：${product ?: UNKNOWN_TEXT}")
        add("ROM 标识(FINGERPRINT)：${textOrUnknown(fingerprint)}")
        add("支持的 ABI：${abisText(abis)}")
        add("总内存：${gibText(totalRamBytes)}")
        add("低内存设备(isLowRamDevice)：${boolText(isLowRamDevice)}")
        add("可用存储：${storageText(availableStorageBytes, totalStorageBytes)}")
        add("字体缩放(fontScale)：${scaleText(fontScale)}")
        add("屏幕密度(densityDpi)：${densityText(densityDpi)}")
        add("无障碍服务开关：${boolText(accessibilityEnabled)}")
        add("电池温度：${tempText(batteryTempTenthsC)}")
    }

    /** 非空文本原样、null 或空白给「未知」（FINGERPRINT 一类可能整串为空的 ROM）。 */
    private fun textOrUnknown(value: String?): String =
        if (value.isNullOrBlank()) UNKNOWN_TEXT else value

    /** ABI 列表：空/null → 未知；否则逗号连接并带一项数（一眼看出 32/64 位混装）。 */
    private fun abisText(abis: List<String>?): String {
        if (abis.isNullOrEmpty()) return UNKNOWN_TEXT
        return abis.joinToString(",") + "（${abis.size} 项）"
    }

    /** 布尔 → 是/否；null → 未知。 */
    private fun boolText(value: Boolean?): String = when (value) {
        null -> UNKNOWN_TEXT
        true -> "是"
        false -> "否"
    }

    /**
     * 字节 → `N.MGiB`（一位小数，避免 8GiB 机报成「8」与 7.3GiB 报成「7」糊成一档）。
     * null 或负 → 未知。用 `((bytes*10) + GIB/2) / GIB` 的**整数四舍五入**得出「十分位 GiB」，
     * 不碰浮点格式化：纯下取整（`bytes*10/GIB`）会把 7.3GiB 的机子（totalMem 常按十进制标称、
     * 换算回来天然差零点几 GiB）报成 7.2，与厂商档位对不上——四舍五入后整倍 GiB 读数不变
     * （8GiB 仍是 8.0），只有卡在半格的值被归到最近一档，分桶更稳。
     */
    private fun gibText(bytes: Long?): String {
        if (bytes == null || bytes < 0L) return UNKNOWN_TEXT
        val tenths = (((bytes * 10L) + GIB / 2L) / GIB).toInt()
        return "${tenths / 10}.${tenths % 10}GiB"
    }

    /** 可用存储那行：两者都缺 → 未知；只缺总量 → 只报可用；齐 → 「可用X GiB / 共Y GiB」。 */
    private fun storageText(available: Long?, total: Long?): String {
        if (available == null && total == null) return UNKNOWN_TEXT
        val avail = gibText(available)
        return if (total == null) avail else "$avail / 共 ${gibText(total)}"
    }

    /** 字号缩放：保留两位小数（Locale.US 固定，避免宿主区域把小数点变成逗号）。 */
    private fun scaleText(scale: Double?): String =
        if (scale == null) UNKNOWN_TEXT else java.lang.String.format(java.util.Locale.US, "%.2f", scale)

    /** 密度：整数 dpi；null → 未知。 */
    private fun densityText(dpi: Int?): String =
        if (dpi == null || dpi <= 0) UNKNOWN_TEXT else "${dpi}dpi"

    /** 电池温度：`EXTRA_TEMPERATURE` 是 0.1℃ 单位的整数，折成一位小数带 ℃（同 Locale.US）。 */
    private fun tempText(tenths: Int?): String =
        if (tenths == null) UNKNOWN_TEXT
        else java.lang.String.format(java.util.Locale.US, "%.1f", tenths / 10.0) + "℃"
}

/**
 * 报告里「系统取证：足迹与上次退出」那一节的纯判式与纯格式化（无 Android 运行期依赖，
 * `:app` JVM 单测直接锁，见 `ErrorReportExitForensicsTest`）。
 *
 * ## 为什么必须有这一节
 *
 * 玩家上报里有一整类「上次异常退出、但报告里没有 throwable」（2026-10 收口的 14 条）。
 * lmkd 收进程、OEM 后台管控划掉、包更新、native 崩（信号）这四种死**都不走**
 * `UncaughtExceptionHandler`，所以 Java 侧的取证天然是空的；[RunTrail] 的轨迹只能说明
 * 「死前水位高不高」，判不出死因归谁。系统自己记的那一份（`ApplicationExitInfo`）是唯一
 * 带判词的读数：它把上面四种与 Java OOM 分成四个不同的原因码。
 *
 * ## 常量出处（不许脑补）
 *
 * 原因码与重要度取自本机 compileSdk 36 的 `android.jar`（2026-10-31 用 `javap` 直读
 * `android/app/ApplicationExitInfo.class` 与 `ActivityManager$RunningAppProcessInfo.class`）：
 * `REASON_UNKNOWN=0 … REASON_PACKAGE_UPDATED=16`、`IMPORTANCE_FOREGROUND=100 … IMPORTANCE_GONE=1000`。
 * 同一张表里 **没有** `getSubreason()`（SDK 36 的成员表里不存在这个方法），所以本节的
 * 「细分」用的是 `getStatus()`（信号号 / 退出码）——它对「SIGKILL（系统收）还是
 * SIGSEGV/SIGABRT（native 崩）」的区分度正是我们要的那一半。
 * 接口下限 [API_R] = 30 是 `ActivityManager.getHistoricalProcessExitReasons` 的最低版本。
 *
 * ## 分配纪律
 *
 * 这段代码跑在「堆可能已经见底」的时刻：行数硬顶 [MAX_EXIT_ROWS]、备注硬顶
 * [DESCRIPTION_MAX_CHARS] 字符、逐行拼接不回收、任何一项读不到就一行「未知」继续。
 * 缺 RSS 之类单个读数不影响其余行，缺整节更不影响报告本身。
 */
internal object ExitForensics {

    /** 读不到的标量哨兵（渲染成 [UNKNOWN_TEXT]）。 */
    const val UNKNOWN = -1L

    /** 读不到的标量的显示文本（与 core 的 `ErrorReport.UNKNOWN_MARK` 同口径，值内联避免依赖）。 */
    const val UNKNOWN_TEXT = "未知"

    /** 能读到上一轮退出记录的最低 API（30 = Android 11）。 */
    const val API_R = 30

    /** 报告里最多列几条退出记录（系统接口本身就只要最新的几条，这里再兜一道）。 */
    const val MAX_EXIT_ROWS = 3

    /** 系统备注的硬截长度：那一栏可能塞了一整段 logcat，绝不能原样进报告。 */
    const val DESCRIPTION_MAX_CHARS = 120

    private const val MIN_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    // ---- ApplicationExitInfo 的原因码（出处见类头）----
    const val REASON_UNKNOWN = 0
    const val REASON_EXIT_SELF = 1
    const val REASON_SIGNALED = 2
    const val REASON_LOW_MEMORY = 3
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6
    const val REASON_INITIALIZATION_FAILURE = 7
    const val REASON_PERMISSION_CHANGE = 8
    const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
    const val REASON_USER_REQUESTED = 10
    const val REASON_USER_STOPPED = 11
    const val REASON_DEPENDENCY_DIED = 12
    const val REASON_OTHER = 13
    const val REASON_FREEZER = 14
    const val REASON_PACKAGE_STATE_CHANGE = 15
    const val REASON_PACKAGE_UPDATED = 16

    // ---- 常见信号号（`status` 在 REASON_SIGNALED 下的取值）----
    private const val SIGABRT = 6
    private const val SIGBUS = 7
    private const val SIGKILL = 9
    private const val SIGSEGV = 11

    // ---- 重要度（ActivityManager.RunningAppProcessInfo，出处见类头）----
    private const val IMPORTANCE_FOREGROUND = 100
    private const val IMPORTANCE_FOREGROUND_SERVICE = 125
    private const val IMPORTANCE_PERCEPTIBLE_PRE_26 = 130
    private const val IMPORTANCE_PERCEPTIBLE = 230
    private const val IMPORTANCE_VISIBLE = 200
    private const val IMPORTANCE_SERVICE = 300
    private const val IMPORTANCE_TOP_SLEEPING = 325
    private const val IMPORTANCE_CANT_SAVE_STATE = 350
    private const val IMPORTANCE_BACKGROUND = 400
    private const val IMPORTANCE_EMPTY = 500
    private const val IMPORTANCE_GONE = 1000

    /**
     * 整节的行：一行足迹 + 至多 [MAX_EXIT_ROWS] 行退出记录 + 一行判读。
     *
     * @param pssKib 本轮 PSS（KiB）；[UNKNOWN] = 读不到
     * @param rssKib 本轮 RSS（KiB）；[UNKNOWN] = 读不到
     * @param rows 系统退出记录（次序不作要求，本函数按时间戳降序自己排）
     * @param nowMs 报告时刻的墙钟（用于「N 分钟前」）
     * @param apiLevel [android.os.Build.VERSION.SDK_INT]；低于 [API_R] 时如实写「接口不可用」
     * @param timestampText 墙钟 → 人眼可读文本（由 Android 侧给，本函数不碰时区与格式化器）
     */
    fun lines(
        pssKib: Long,
        rssKib: Long,
        rows: List<ProcessExitRow>,
        nowMs: Long,
        apiLevel: Int,
        timestampText: (Long) -> String,
    ): List<String> {
        val out = ArrayList<String>(MAX_EXIT_ROWS + 3)
        out += footprintLine(pssKib, rssKib)
        val newest = rows.sortedByDescending { it.timestampMs }.take(MAX_EXIT_ROWS)
        if (newest.isEmpty()) {
            out += if (apiLevel < API_R) {
                "上次退出记录：系统接口不可用（API $apiLevel < $API_R，这一档只能靠足迹行）"
            } else {
                "上次退出记录：系统没给出本包条目（多为 ROM 阉割该接口，或上一轮走到了正常收尾）"
            }
        } else {
            newest.forEachIndexed { i, row ->
                out += exitRowLine(i, timestampText(row.timestampMs), row, nowMs)
            }
            out += "判读：" + verdict(newest.first())
        }
        return out
    }

    /**
     * 足迹行：本轮进程的 PSS/RSS。
     *
     * Java 堆三列（`maxMemory` / `totalMemory` / `freeMemory`）在装载埋点 [LoadTrace] 与
     * 运行轨迹 [RunTrail] 里已有同口径读数，这里**不重复**——这一节补的是「整个进程占多少」
     * （PSS 含 native 与映射，正是 lmkd 用来决定杀谁的那个量）。
     */
    fun footprintLine(pssKib: Long, rssKib: Long): String =
        "本轮足迹：PSS=${mibText(pssKib)} RSS=${mibText(rssKib)}" +
            "（Java 堆三列见装载埋点/运行轨迹，不在此重复）"

    /** 一条退出记录一行：时间（距今多久）、原因、状态、重要度、当时的 PSS/RSS、截断备注。 */
    fun exitRowLine(index: Int, timestampText: String, row: ProcessExitRow, nowMs: Long): String =
        "退出#$index $timestampText（${ageText(nowMs - row.timestampMs)}）" +
            " pid=${row.pid}" +
            " 原因=${reasonName(row.reasonCode)}" +
            " 状态=${statusText(row.reasonCode, row.status)}" +
            " 重要度=${importanceName(row.importance)}" +
            " 当时PSS=${mibText(row.pssKb)}" +
            " 当时RSS=${mibText(row.rssKb)}" +
            " 备注=${clampDescription(row.description)}"

    /**
     * 最新退出记录是否属于「系统/用户明确认领、非缺陷」的一类。
     *
     * 第一类是原因码自己就把话说明白了的：包更新/安装状态变化、运行时权限变更、用户主动停止。
     * 系统内存回收（LOW_MEMORY）、信号、native/Java 崩溃、ANR 都不在此列 —— 那些正是要上报的
     * 现场，不能因为「死因不在 Java 兜底里」就当成正常。
     *
     * 第二类是**原因码只会说「其它 / 自行退出」、但备注原文写着 OEM 管控判词**的那几种
     * （[REASON_OTHER] 与 [REASON_EXIT_SELF]）：判据是 [isOemManagedStop]，只看系统给的
     * 原文、不看我们自己的推测。2026-11-04 端点那 182 份「上次异常退出」里，退出行落在这两个
     * 码上的有 31 份（`OTHER(13)` 13 份 + `EXIT_SELF(1)` 18 份），其中 **14 份**的备注写着
     * 下面的管控判词 ⇒ 旧判据盖不住它们，每次冷启都给玩家弹一张不该弹的卡、给台账落一份
     * 不该落的报告；余下 17 份备注是空的（「谁调的 exit」读不出来），继续照报（逐份读数见
     * [isOemManagedStop] 的出处表）。
     */
    fun isBenignExit(row: ProcessExitRow): Boolean = when (row.reasonCode) {
        REASON_USER_REQUESTED, REASON_USER_STOPPED,
        REASON_PACKAGE_STATE_CHANGE, REASON_PACKAGE_UPDATED,
        REASON_PERMISSION_CHANGE -> true
        // MemoryLimiter 的行显式挡在 benign 之外：它收进程说明进程总量真的超了，
        // 是引擎内存治理的信号，必须照报（OOM-A 波；判据见 isMemoryLimiterKill 的 KDoc）。
        REASON_OTHER -> !isMemoryLimiterKill(row.description) && isOemManagedStop(row.description)
        REASON_EXIT_SELF -> isOemManagedStop(row.description)
        else -> false
    }

    /**
     * 系统备注是不是「ROM 的管控把进程收了」的判词（而不是我们这一侧的死）。
     *
     * ## 出处（全部是收件箱原文，一条一个形状；2026-11-04 全量 617 份里的 500 份切片）
     *
     * 份数口径：这一形出现在 `退出#0` 行的**总次数**（跨报告类型），不是「上次异常退出」
     * 那一类里的份数 —— 后者见 [isBenignExit]（管控判词那 14 份）。
     *
     * - `[UNKNOWN] stop com.eratw.port.android due to o-stop(40)`
     *   —— 荣耀/华为的省电管控 stop，`OTHER(13)` 下 18 次（重要度既有 FOREGROUND(100) 也有
     *   BACKGROUND/EMPTY(400)，屏前一样收，所以重要度不能进判据）；
     * - `[UNKNOWN] o-kill(109)` / `[UNKNOWN] o-kill(32)` —— 同族的 kill，3 次；
     * - `PRESTART WITHOUT START ACTIVITY` —— ROM 预热了进程但一个界面都没起，随后收回，
     *   `OTHER(13)` + BACKGROUND/EMPTY(400)，38 次（这一类里玩家根本没进过游戏）；
     * - `stop com.eratw.port.android due to SPEG` —— 荣耀的功耗治理，1 次（记在
     *   `EXIT_SELF(1)` 名下：系统把「自己退的」和「被治理退了」混在同一码里）；
     * - `stop com.eratw.port.android due to from pid 1454|40|F|J:50`
     *   —— ColorOS/OPPO 的管控串，竖线后面是它的裁决字段（adj 40 / 前后台 F / J:50），2 次。
     *   **必须带竖线**才算：不带竖线的 `due to from pid 322` 只是「某个 pid 让停的」，
     *   说不清是谁的意思，那一形在收件箱里全部落在已经盖住的 `USER_REQUESTED(10)` 名下。
     *
     * 判据只做**包含**、不做整行匹配：备注是系统写的、版本间措辞会变（同一族在同一台机上
     * 就有 `[UNKNOWN]` 前缀与没有前缀两种），但认错一行的代价是「该报的没报」，所以
     * 令牌逐条列死、不写通配，读不到的形状一律继续上报。
     */
    fun isOemManagedStop(description: String?): Boolean {
        val flat = clampDescription(description).lowercase(Locale.ROOT)
        if (flat == "（无）") return false
        if (OEM_STOP_TOKENS.any { flat.contains(it) }) return true
        return OEM_STOP_BY_ADJ.matchesIn(flat)
    }

    /** [isOemManagedStop] 的包含式令牌（小写比较；每条都带出处的原文形态，不要顺手加）。 */
    private val OEM_STOP_TOKENS = listOf(
        "due to o-stop(",
        "o-kill(",
        "prestart without start activity",
        "due to speg",
    )

    /**
     * OEM 管控串的「带裁决字段」那一形：`stop <包名> due to from pid <pid>|<adj>|…`。
     * 收成正则而不是 contains，为的是把「没有竖线的 from pid」挡在门外（理由见上）。
     */
    private object OEM_STOP_BY_ADJ {
        private val PATTERN = Regex("stop \\S+ due to from pid \\d+\\|")
        fun matchesIn(flatLowercase: String): Boolean = PATTERN.containsMatchIn(flatLowercase)
    }

    /**
     * 系统备注是不是「Android 17 进程内存限制器把进程收了」（OOM-A 波，CONTRACT-OOM-A §4）。
     *
     * ## 出处与形状
     *
     * Android 17（API 37）起 MemoryLimiter 按**进程总量**（匿名 RSS + swap，cgroup v2
     * `memory.high` + `memory.swap.max`）节流并最终终止应用，且**不给栈轨迹** —— 官方
     * 行为变更文档（developer.android.com/about/versions/17/behavior-changes-all
     * 「应用内存限制」节）给出的唯一识别口：`ApplicationExitInfo` 的退出原因为
     * `REASON_OTHER`，description 含 `"MemoryLimiter:AnonSwap"`。
     *
     * ## 判据与优先级
     *
     * - 只做小写化**包含**匹配（系统写的串，版本间可能加前后缀）；读不到 / 空串恒 false。
     * - 在 [verdict] 的 `REASON_OTHER` 分支里**优先于** [isOemManagedStop]：两串同现的
     *   形状没见过，但 MemoryLimiter 的语义更具体（厂商管控串说「谁收的」，它说「为什么收」）。
     * - **不进** [isBenignExit]：OEM 管控是「系统治理，我们没病」，MemoryLimiter 收进程
     *   恰恰说明进程总量真的超了 —— 这是引擎内存治理的信号，必须保持「上次异常退出」
     *   让报告弹出来，不能像 OEM 判词那样静默吞掉。
     */
    fun isMemoryLimiterKill(description: String?): Boolean =
        clampDescription(description).lowercase(Locale.ROOT).contains(MEMORY_LIMITER_MARKER)

    /**
     * [isMemoryLimiterKill] 的匹配令牌（小写比较）：官方文档原文 `MemoryLimiter:AnonSwap`
     * 的小写形。逐字列死、不写通配 —— 认错一行的代价与 [isOemManagedStop] 相反：
     * 这里认错是把「我们内存超了」说成「别的死因」。
     */
    private const val MEMORY_LIMITER_MARKER = "memorylimiter:anonswap"

    /**
     * 判读： newest 那条记录说明了什么，以及「为什么这份报告里没有异常对象」。
     *
     * 每一句都要能落到一个修复方向上；认不出的原因码如实说「不认识」，不猜。
     */
    fun verdict(row: ProcessExitRow): String = when (row.reasonCode) {
        REASON_LOW_MEMORY ->
            "系统按内存压力回收（lmkd）——不走 Java 兜底，所以没有 throwable 是正常的；方向是继续降常驻。"
        REASON_SIGNALED ->
            "被信号杀死：${signalText(row.status)}；Java 层拦不住这一类。"
        REASON_CRASH_NATIVE ->
            "native 层崩溃（ART 之外）；本报告的兜底处理器接不到它，方向是查 JNI / 映射访问。"
        REASON_CRASH ->
            "Java 层未捕获异常；若这份报告没有 throwable，说明兜底处理器自身没跑完。"
        REASON_EXIT_SELF ->
            if (isOemManagedStop(row.description)) {
                "系统记成「自行退出」，但备注是 ROM 的管控判词（原文见上一行）——非缺陷类退出。"
            } else {
                "进程自行退出（System.exit / 收尾）——这一类不是死，脏退出判据要回看它。"
            }
        REASON_ANR ->
            "无响应（ANR）后被系统结束：系统的判词只有「主线程迟迟没让出」这一条，" +
                "分不出是卡在锁/磁盘 IO，还是堆贴顶时 GC 一遍遍把全部线程冻住 —— " +
                "分开两者要看补充现场的轨迹行：`used` 逼近 `max`（或 `trim=` 那一发 `freed=0`）" +
                "就是后者，方向仍是降常驻；堆不高而单行 `turn` 拉到秒级才是前者。"
        REASON_USER_REQUESTED, REASON_USER_STOPPED ->
            "用户主动停止（划掉最近任务 / 设置里强行停止）——非缺陷类退出。"
        REASON_PACKAGE_STATE_CHANGE, REASON_PACKAGE_UPDATED ->
            "包被更新或安装状态变化，系统收回旧进程——非缺陷类退出。"
        REASON_PERMISSION_CHANGE ->
            "运行时权限变更触发系统重启进程——非缺陷类退出。"
        REASON_EXCESSIVE_RESOURCE_USAGE ->
            "系统按资源滥用收进程（OEM 管控常见）；结合足迹行看是否真超用。"
        REASON_DEPENDENCY_DIED ->
            "依赖的进程先死（如系统服务/同 UID 进程），本进程被连带结束。"
        REASON_INITIALIZATION_FAILURE ->
            "进程初始化失败（zygote 侧就没起来）。"
        REASON_FREEZER ->
            "应用处于休眠/冻结态时被结束——多为后台管控。"
        REASON_OTHER ->
            if (isMemoryLimiterKill(row.description)) {
                "Android 17 的进程内存限制器（MemoryLimiter）终止：进程总量（native/位图都算，" +
                    "不止 Java 堆）超了设备 RAM 档位的预算，无栈轨迹是它的正常形态；" +
                    "方向 = 降常驻 + 回合边界的进程口径回收（OOM-A 波已接线）。"
            } else if (isOemManagedStop(row.description)) {
                "系统标为「其它」，但备注是 ROM 的管控判词（原文见上一行）——非缺陷类退出。"
            } else {
                "系统标为「其它」，未给细分；只能按原始读数记录。"
            }
        REASON_UNKNOWN ->
            "系统自己也没给出原因；只能按原始读数记录。"
        else ->
            "原因码 ${row.reasonCode} 不在认识的表里（比本包 compileSdk 的认识范围更新）；只按原始读数记录。"
    }

    /** 原因码 → `NAME(code)`；未知码留 `REASON#(n)`，不冒充认识的名字。 */
    fun reasonName(code: Int): String {
        val name = when (code) {
            REASON_UNKNOWN -> "UNKNOWN"
            REASON_EXIT_SELF -> "EXIT_SELF"
            REASON_SIGNALED -> "SIGNALED"
            REASON_LOW_MEMORY -> "LOW_MEMORY"
            REASON_CRASH -> "CRASH"
            REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            REASON_ANR -> "ANR"
            REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
            REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
            REASON_USER_REQUESTED -> "USER_REQUESTED"
            REASON_USER_STOPPED -> "USER_STOPPED"
            REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            REASON_OTHER -> "OTHER"
            REASON_FREEZER -> "FREEZER"
            REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
            REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
            else -> return "REASON#($code)"
        }
        return "$name($code)"
    }

    /** 重要度 → `NAME(code)`：区分「死在屏前（FOREGROUND/VISIBLE）」与「死在后台」。 */
    fun importanceName(code: Int): String {
        val name = when (code) {
            IMPORTANCE_FOREGROUND -> "FOREGROUND"
            IMPORTANCE_FOREGROUND_SERVICE -> "FOREGROUND_SERVICE"
            IMPORTANCE_PERCEPTIBLE_PRE_26 -> "PERCEPTIBLE_PRE_26"
            IMPORTANCE_PERCEPTIBLE -> "PERCEPTIBLE"
            IMPORTANCE_VISIBLE -> "VISIBLE"
            IMPORTANCE_SERVICE -> "SERVICE"
            IMPORTANCE_TOP_SLEEPING -> "TOP_SLEEPING"
            IMPORTANCE_CANT_SAVE_STATE -> "CANT_SAVE_STATE"
            IMPORTANCE_BACKGROUND, IMPORTANCE_EMPTY -> "BACKGROUND/EMPTY"
            IMPORTANCE_GONE -> "GONE"
            else -> return "IMPORTANCE#($code)"
        }
        return "$name($code)"
    }

    /** 状态列：信号型原因给信号名，其余给原始码（`0` = 正常退出码）。 */
    fun statusText(reasonCode: Int, status: Int): String =
        if (reasonCode == REASON_SIGNALED || reasonCode == REASON_CRASH_NATIVE) {
            signalText(status)
        } else {
            "code=$status"
        }

    /** 常见信号名（只列本类用得上的几个，其余如实给号）。 */
    fun signalText(status: Int): String {
        val name = when (status) {
            SIGABRT -> "SIGABRT（多为 ART/native abort）"
            SIGBUS -> "SIGBUS（映射页访问失败）"
            SIGKILL -> "SIGKILL（系统/OEM 直接收，无清理机会）"
            SIGSEGV -> "SIGSEGV（native 段错误）"
            else -> return "signal=$status"
        }
        return "signal=$status $name"
    }

    /**
     * `/proc/<pid>/statm` 的一行 → RSS（KiB）。
     *
     * 该行的七个数都以**页**为单位，第 2 列是常驻页（size resident shared text lib data dt）；
     * 行数不足或列不是数字一律 [UNKNOWN]，不猜。
     */
    fun rssKibFromStatm(statmLine: String?, pageSizeKib: Long): Long {
        if (statmLine.isNullOrBlank() || pageSizeKib < 1L) return UNKNOWN
        val fields = statmLine.trim().split(WHITESPACE)
        if (fields.size < 2) return UNKNOWN
        val residentPages = fields[1].toLongOrNull() ?: return UNKNOWN
        return residentPages * pageSizeKib
    }

    /** 距今多久的中文文本；负数（系统时钟与墙钟不一致）如实说明。 */
    fun ageText(deltaMs: Long): String = when {
        deltaMs < 0L -> "时间不可靠"
        deltaMs < MIN_MS -> "不足1分钟"
        deltaMs < HOUR_MS -> "${deltaMs / MIN_MS}分钟前"
        deltaMs < DAY_MS -> "${deltaMs / HOUR_MS}小时前"
        else -> "${deltaMs / DAY_MS}天前"
    }

    /** KiB → `NMiB`（整除；[UNKNOWN] 及负数给 [UNKNOWN_TEXT]）。 */
    fun mibText(kib: Long): String =
        if (kib < 0L) UNKNOWN_TEXT else "${kib / 1024L}MiB"

    /** 系统备注的截断：压掉换行（一行一条记录），硬顶 [DESCRIPTION_MAX_CHARS] 字符。 */
    fun clampDescription(text: String?, max: Int = DESCRIPTION_MAX_CHARS): String {
        val flat = text?.trim()?.replace('\n', ' ')?.replace('\r', ' ') ?: return "（无）"
        if (flat.isEmpty()) return "（无）"
        return if (flat.length <= max) flat else flat.take(max) + "…（截断）"
    }

    private val WHITESPACE = Regex(" +")
}

/**
 * 上报节流的三种结局（EP1-L6）。
 *
 * `WRITE` = 照常落一份（首份 / 终局类 / 判式退回）；后两种都是**抑制**，差别只在原因：
 * 同签名已经落过（[SUPPRESSED_DUPLICATE]）还是本局额度用完了（[SUPPRESSED_SESSION_CAP]）。
 * 分开给两个值是有读数的：triage 侧要能回答「这台机一天 51 条，是去重挡住了 46 条还是
 * 上限挡住了 46 条」——前者说明只有一个形状，后者说明形状多到 5 个都装不住。
 */
internal enum class ThrottleAction { WRITE, SUPPRESSED_DUPLICATE, SUPPRESSED_SESSION_CAP }

/**
 * 节流判式的产出：做什么、把哪个文件交回调用方、要不要钉一行轨迹。
 *
 * @param reportFile 抑制时递给失败屏的那份（同签名首份，拿不到就退本局最近一份；
 *   两份都没有才给 null）。**这不是「新写的一份」**，所以 [ErrorReportCollector.collect]
 *   直接把它交回宿主，`lastReportFile` 依旧指向一份真实场。
 * @param trailPhase 钉进 [RunWatch] 轨迹的阶段文本；null = 冷却未到，本次只加计数
 * @param occurrences 这个签名（或本局抑制总数，额度用尽那一路）至今出现过几次
 * @param suppressedTotal 本局被抑制的总条数（全部签名相加）
 */
internal data class ThrottleDecision(
    val action: ThrottleAction,
    val reportFile: File? = null,
    val trailPhase: String? = null,
    val occurrences: Int = 0,
    val suppressedTotal: Int = 0,
) {
    companion object {
        /** 判式自己出故障时用的那份「就落一份」：宁可刷屏也不丢现场。 */
        val WRITE = ThrottleDecision(ThrottleAction.WRITE)
    }
}

/**
 * 同局报错的上报节流表（EP1-L6 的判式本体，纯 JVM 无 Android 依赖 ⇒ `:app` 单测直接锁，
 * 见 `ErrorReportThrottleTest`）。
 *
 * ## 三条判式与它们的分工
 *
 * 1. **同签名去重**：[ReportSignature] 归一出来的键在本局内只放行第一次（落一份完整报告），
 *    之后每次只 `occurrences++` 与 `suppressedTotal++`。落盘的那一份正文一个字节都不改
 *    —— 本类只回答「要不要再落一份」，不参与拼装，所以「第一份逐字不变（含
 *    `[线程心跳]`、`[系统取证]`）」是结构性的保证，不是靠约定（哨兵用例钉的就是
 *    「拼装函数里没有节流的名字」这一条）。
 * 2. **每局上限**（[maxReportsPerSession]，取值理由见
 *    [ErrorReportCollector.MAX_REPORTS_PER_SESSION]）：额度用完后**新形状**也只计数不落盘，
 *    并把这个形状记进表（首份文件为空），于是它后续的每一次都记成同一个形状的重复，
 *    `summary()` 里看得见它撞了几次。
 * 3. **钉发冷却**（[pinCooldownMs]，沿用补发侧那 10 s）：只决定「计数行多久钉一次轨迹」，
 *    永远不推迟 `WRITE` 那一路 —— 首份报告在调用当场写，这条判式的存在与否不改变它的时间。
 *
 * ## 清零口径（「本局」到底多大）
 *
 * 键 = 调用方给的会话身份（[ErrorReportCollector.sessionKeyOf]：`GameSession` 实例 →
 * `GameHost` 实例 → null）。**键一变就整表清零**（份数、抑制数、签名表、上次钉发时刻），
 * 所以「回到标题 → 新游戏」换会话 ⇒ 新一局重新起算；进程重启自然是空表。
 * 显式清账走 [reset]（宿主 `releaseSession` 的接线口，见 [ErrorReportCollector.resetThrottle]）。
 *
 * ## 线程与故障方向
 *
 * VM 线程（脚本报错）与主线程（崩溃兜底、冷启补报）都会调 [decide]，全部 `synchronized`。
 * 任何调用方的故障都朝**多报**那一边退：判式自己抛 ⇒ 调用方按 [ThrottleDecision.WRITE] 处理；
 * 落盘失败 ⇒ [noteWritten] 把签名从表里摘掉，同型的下一次仍然试图落整份。
 * 「为了省流量而丢现场」是本类唯一的不可接受项。
 */
internal class ErrorReportThrottle(
    private val maxReportsPerSession: Int = ErrorReportCollector.MAX_REPORTS_PER_SESSION,
    private val pinCooldownMs: Long = ErrorReportCollector.PIN_COOLDOWN_MS,
) {

    private val lock = Any()

    /** 一个被放行过的形状：首份落盘的文件（额度用尽记 `null`）+ 它出现过的次数。 */
    private class Entry(val signature: ReportSignature.Value, var occurrences: Int, var firstFile: File?)

    private val seen = LinkedHashMap<String, Entry>()

    private var sessionKey: String? = null
    private var started = false
    private var written = 0
    private var suppressedTotal = 0
    private var lastFile: File? = null
    private var lastPinAtMs = NEVER

    /**
     * 这一条报错要不要落盘、要不要钉行、递给调用方哪一份文件。
     *
     * 本函数**不写任何文件**（钉发的落盘由调用方执行 [RunWatch.forceSample]），
     * 也不看时钟做任何「延后」：`WRITE` 就是此刻就写。
     */
    fun decide(sessionKey: String?, signature: ReportSignature.Value, nowMs: Long): ThrottleDecision =
        synchronized(lock) {
            alignSession(sessionKey)
            val entry = seen[signature.key]
            if (entry != null) {
                entry.occurrences++
                suppressedTotal++
                ThrottleDecision(
                    // 首份是 null 的那种形状是「被上限挤掉的」，如实分开标（见 ThrottleAction）
                    action = if (entry.firstFile == null) ThrottleAction.SUPPRESSED_SESSION_CAP
                    else ThrottleAction.SUPPRESSED_DUPLICATE,
                    reportFile = entry.firstFile ?: lastFile,
                    trailPhase = pinPhase(nowMs, "errdup", signature, entry.occurrences),
                    occurrences = entry.occurrences,
                    suppressedTotal = suppressedTotal,
                )
            } else if (written >= maxReportsPerSession) {
                suppressedTotal++
                seen[signature.key] = Entry(signature, 1, null)
                ThrottleDecision(
                    action = ThrottleAction.SUPPRESSED_SESSION_CAP,
                    reportFile = lastFile,
                    trailPhase = pinPhase(nowMs, "errcap", signature, suppressedTotal),
                    occurrences = suppressedTotal,
                    suppressedTotal = suppressedTotal,
                )
            } else {
                ThrottleDecision(ThrottleAction.WRITE, suppressedTotal = suppressedTotal)
            }
        }

    /**
     * 记账：整份报告真的落成了（[file] 非 null）才算「这一型报过了」。
     *
     * [file] 为 null（完整与最小退化报告都没写出来）⇒ 把签名**摘掉**：下一次同型仍然走
     * `WRITE`，重试整份。这是「失败必须留痕」的下半句 —— 节流不许把「一次都没落成」
     * 伪装成「已经报过一次」。
     */
    fun noteWritten(sessionKey: String?, signature: ReportSignature.Value, file: File?) {
        synchronized(lock) {
            alignSession(sessionKey)
            if (file == null) {
                seen.remove(signature.key)
            } else {
                written++
                lastFile = file
                seen[signature.key] = Entry(signature, 1, file)
            }
        }
    }

    /**
     * 本局账面（进 logcat / 供宿主拼进报告的「补充现场」）；行数硬顶 [SUMMARY_TOP] + 1。
     *
     * **一条都没抑制过时返回空表**（刻意的，不是遗漏）：宿主若把这组行接进
     * `writeErrorReport` 的 `extraLines`，空表保证「本局第一份报告」的正文逐字不变 ——
     * 「有账才出声」，与 [ErrorReportCollector] 类头那条留痕纪律同一个方向。
     */
    fun summary(): List<String> = synchronized(lock) {
        if (suppressedTotal == 0) return emptyList()
        val top = seen.values.filter { it.occurrences > 1 }.sortedByDescending { it.occurrences }.take(SUMMARY_TOP)
        buildList {
            add("报错节流（本局）：落盘 $written/$maxReportsPerSession 份，抑制 $suppressedTotal 条")
            top.forEach {
                add("×${it.occurrences} ${it.signature.display}" +
                    if (it.firstFile == null) "（本局额度已满，未落盘）" else "")
            }
        }
    }

    /** 整表清零（新一局 / 会话释放）；冷却基准一并归零，所以下一局的第一发计数行立刻能钉。 */
    fun reset() {
        synchronized(lock) {
            seen.clear()
            written = 0
            suppressedTotal = 0
            lastFile = null
            lastPinAtMs = NEVER
            started = false
            sessionKey = null
        }
    }

    /** 会话身份变化 = 新的一局：整表清零。调用方传 null（没宿主）时归到同一个「无会话」桶。 */
    private fun alignSession(sessionKey: String?) {
        val key = sessionKey ?: NO_SESSION
        if (started && key == this.sessionKey) return
        this.sessionKey = key
        started = true
        seen.clear()
        written = 0
        suppressedTotal = 0
        lastFile = null
        lastPinAtMs = NEVER
    }

    /**
     * 冷却到点就产一行阶段文本，否则返回 null（只加计数，不碰盘）。
     *
     * 文本形状：`errdup x21 QOL_GATHERING_DRAWING 行69｜RAND 的参数必须为正数（实际 0）`
     * （`errcap` 那一档是「本局额度已满」）。标签 + 计数 + **未归一的现场原文**：计数说「几次」，
     * 现场文本说「哪一次长什么样」，读轨迹的人不必回头去解签名。
     *
     * 墙钟被拨回（`nowMs < lastPinAtMs`）按「冷却已过」处理：宁可多钉一行，不可因为时钟
     * 跳变把计数永久钉不出去。
     */
    private fun pinPhase(
        nowMs: Long,
        tag: String,
        signature: ReportSignature.Value,
        count: Int,
    ): String? {
        val cooled = lastPinAtMs == NEVER || nowMs < lastPinAtMs || nowMs - lastPinAtMs >= pinCooldownMs
        if (!cooled) return null
        lastPinAtMs = nowMs
        return "$tag x$count ${signature.display}".take(PHASE_MAX_CHARS)
    }

    private companion object {
        /** 还没钉过任何一行的哨兵（不能用 0：开机 0 毫秒的读数是真的）。 */
        const val NEVER = -1L

        /** 「没有会话」那一桶的键（装载前/宿主为空的极少数路）。 */
        const val NO_SESSION = "session:none"

        /** 轨迹行的硬顶字符数：滚动轨迹一行约 60 B，这一行不许把它撑成两行。 */
        const val PHASE_MAX_CHARS = 96

        /** 账面里最多列前几名（其余只进总数，不进文本）。 */
        const val SUMMARY_TOP = 3
    }
}

/**
 * 报错签名的归一化（EP1-L6）：把一条现场压成「值不值得再落一份」的那把键。
 *
 * ## 三段构成（契约 §2/L6：异常类 + 消息 + 最深函数名/行号）
 *
 * - **类型**：异常类的全名；没有 throwable（状态机终局那一类）就用报告类型文本兜上
 *   `kind=` 前缀，保证与真异常的名不会撞。
 * - **消息**：归一 = 压掉换行与连续空白 → **数字串一律换成 `#`** → 硬顶
 *   [MESSAGE_MAX_CHARS] 字符。为什么抹数字：`RAND 的参数必须为正数（实际 0）` 的「实际 0」
 *   「实际 -3」、`EXPLV 的第 1 维下标 5002 越界` 的下标都是**数据不是身份** —— 同一个语句
 *   同一类错，换个数值就再落一份报告正是刷屏的来源。身份在第三段（哪一行的哪条语句）。
 * - **位置**：因果链里**最深**那枚 [CodeEE] 的「函数名 行N」（越往里越深，取最后一次赋值）；
 *   链上没有 `CodeEE`（比如裸 `OutOfMemoryError`、`ArrayIndexOutOfBoundsException`）就退到
 *   最深那层异常自己的**栈顶 Java 帧** `类.方法:行`；再没有（状态机终局，无异常）就退到
 *   宿主给的「状态机终局：X」那一行的 X。
 *
 * 三段里位置段**不抹数字**：行号就是身份的一部分，抹了会把 `行69` 与 `行70` 合成一型。
 *
 * ## 与「留痕」纪律的边界
 *
 * 归一化过度会合并掉真实不同的两次失败（同一条语句的两个不同症状）—— 这里刻意接受的取舍是
 * 「同一条语句的同一类错算一型」，而 [Value.display] 保留**未归一**的现场原文（进计数行与
 * logcat），所以被合并掉的那几条仍然逐条留下一次可读的文本。宁可漏合（多落一份），
 * 不可错合（把两个不同形状吞成一个）：本函数的兜底方向一律是「读不出就当成新的一型」。
 */
internal object ReportSignature {

    /** 归一后消息的硬顶字符数（签名进的是内存表，不是盘，但也不许按消息长度线性涨）。 */
    const val MESSAGE_MAX_CHARS = 120

    /** 现场文本（轨迹行与 logcat 用）的硬顶字符数。 */
    const val DISPLAY_MAX_CHARS = 80

    /** 因果链走多深（与 core 的 `ErrorReport.scriptLocationOf` 同值同理由：防自引用死循环）。 */
    private const val CAUSE_CHAIN_MAX_DEPTH = 16

    /**
     * @param key 三段拼起来的签名（节流表的键）
     * @param display 未归一的现场文本「位置｜消息」，给人读的那一句
     */
    data class Value(val key: String, val display: String)

    /**
     * 归一签名。**任何一步都不许抛**：本函数在失败路径上跑，签名取不出来时调用方按
     * 「不去重」处理（宁可刷屏也不丢现场），所以这里只兜真正读不到的那一段。
     */
    fun of(kind: String, error: Throwable?, extraLines: List<String>): Value {
        val type = error?.javaClass?.name ?: "kind=$kind"
        val rawMessage = error?.message?.takeIf { it.isNotBlank() }
            ?: extraLines.firstOrNull { it.startsWith(BLOCKER_PREFIX) }?.removePrefix(BLOCKER_PREFIX)?.trim()
            ?: kind
        val location = error?.let { runCatching { locationOf(it) }.getOrNull() }
            ?: extraLines.firstOrNull { it.startsWith(TERMINAL_PREFIX) }
                ?.removePrefix(TERMINAL_PREFIX)?.trim()?.takeIf { it.isNotEmpty() }
            ?: UNKNOWN_LOCATION
        val display = displayOf(location, rawMessage)
        return Value("$type|$location|${normalize(rawMessage)}", display)
    }

    /**
     * 消息归一：换行与连续空白压成一个空格 → 数字串换 `#` → 截 [MESSAGE_MAX_CHARS]。
     *
     * 顺序不能倒：先截后归一会让「截断点落在数字中间」把同一条消息切成两个签名。
     */
    fun normalize(message: String): String {
        val flat = message.trim().replace(WHITESPACE, " ")
        val masked = DIGITS.replace(flat, "#")
        return if (masked.length <= MESSAGE_MAX_CHARS) masked else masked.take(MESSAGE_MAX_CHARS) + "…"
    }

    /** 现场文本：`位置｜消息`，两侧各自截断，总长硬顶 [DISPLAY_MAX_CHARS]。 */
    fun displayOf(location: String, message: String): String {
        val flat = message.trim().replace(WHITESPACE, " ")
        val short = if (flat.length > DISPLAY_MAX_CHARS) flat.take(DISPLAY_MAX_CHARS) + "…" else flat
        val head = if (location.length + short.length > DISPLAY_MAX_CHARS) {
            short.take((DISPLAY_MAX_CHARS - location.length).coerceAtLeast(0))
        } else short
        return "$location｜$head"
    }

    /**
     * 因果链里最深的脚本位置：最深 [CodeEE] 的「函数名 行N」优先，其次最深异常的栈顶 Java 帧。
     *
     * 「最深」= 沿 cause 链往里走、**最后一次**能读到的那一个（越往里越是真正的抛出点），
     * 与 core 的 `scriptLocationOf`（取第一个，供报告正文用）刻意不同：那里要给人看
     * 「错在哪个函数」，这里要判「是不是同一条语句」，取内层更准。
     *
     * 行号取 [CodeEE.displayLine]（**1 基**，与报告正文/屏上同一口径；换算只在 [CodeEE.displayLine]
     * 一处，这里不加一）。**副作用如实记**：本串同时是去重签名（[fingerprint]）与 `errdup`
     * 计数行的位置段，所以 1 基落地后签名码/指纹跟着变 —— 历史簇（0 基那批，如
     * `QOL_GATHERING_DRAWING 行69`）与新簇（`… 行70`）**不会合并**，这是本次口径变更的
     * 预期代价，不为此加兼容层或双写（组长裁定 2026-11-04）。
     */
    private fun locationOf(error: Throwable): String? {
        var node: Throwable? = error
        var script: String? = null
        var javaFrame: String? = null
        var depth = 0
        while (node != null && depth < CAUSE_CHAIN_MAX_DEPTH) {
            if (node is CodeEE) {
                val parts = ArrayList<String>(2)
                node.funcName?.takeIf { it.isNotBlank() }?.let { parts += it }
                if (node.line >= 0) parts += "行${node.displayLine}"
                if (parts.isNotEmpty()) script = parts.joinToString(" ")
            }
            if (script == null) {
                val frame = node.stackTrace?.firstOrNull()
                if (frame != null) {
                    javaFrame = "${frame.className.substringAfterLast('.')}.${frame.methodName}:" +
                        frame.lineNumber
                }
            }
            val next = node.cause
            if (next === node) break
            node = next
            depth++
        }
        return script ?: javaFrame
    }

    private val WHITESPACE = Regex("\\s+")

    /** 带符号的整数串一起归一：`实际 0` 与 `实际 -3` 是同一型的两个读数。 */
    private val DIGITS = Regex("[+-]?[0-9]+")

    /** 状态机终局那两条现场行的前缀（与 `GameHost.writeErrorReport` 给的字面量对齐）。 */
    private const val TERMINAL_PREFIX = "状态机终局："
    private const val BLOCKER_PREFIX = "阻塞："

    /** 三段都读不出时的位置哨兵：与任何真实函数名都不相等，因此永远不会被误合成一型。 */
    private const val UNKNOWN_LOCATION = "位置不可读"
}

