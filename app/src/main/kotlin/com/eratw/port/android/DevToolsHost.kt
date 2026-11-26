package com.eratw.port.android

import android.util.Log
import android.content.Context
import android.content.pm.ApplicationInfo
import com.eratw.port.core.diag.LockWatch
import com.eratw.port.core.diag.PageFunctionTrail
import com.eratw.port.core.host.SingleDaemonSlot
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.proc.QueueInputSink
import com.eratw.port.render.canvas.CanvasConsoleView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 真机实时调试通道：轮询命令文件 → 宿主侧执行 → 结果回写。
 *
 * 判词 9（PDIAG1）之后这一条通道有**两档**（不是两条通道、不是第二条轮询线程）：
 * - **debug 档**（带 FLAG_DEBUGGABLE）：全功能，写侧/快照/输入注入都能用，与判词 9 之前逐字同行为；
 * - **玩家授权档**（release 包 + 玩家在设置页 Mod 档把「玩家授权诊断」显式打开）：**只读观测**，
 *   写侧命令一律拒（分区表见文件顶层 READONLY_COMMANDS / WRITE_COMMANDS），缺省关 ⇒ 连轮询线程都不建。
 *   判定唯一入口 = Companion.diagnosticsAllowed；以后要加第三个来源（远程开关 / 内测档）只改那一个
 *   函数，不在调用点堆条件表达式。
 *
 * ## 为什么要有它
 *
 * 一轮「构建 → 装 737MB → 等装载 50s → 截屏」的实测回环要 5-8 分钟，而来回猜的
 * 成本大头是「拿不到运行时状态」。本通道把调试变成 adb 一条命令：
 *
 * ```bash
 * # 发命令（外部 files 目录无需 run-as）
 * adb shell "echo img TW_title001 > /sdcard/Android/data/com.eratw.port.android/files/eratw/dev/cmd.txt"
 * # 收结果（立即）
 * adb shell cat /sdcard/Android/data/com.eratw.port.android/files/eratw/dev/out.txt
 * adb logcat -s ERATWDBG
 * ```
 *
 * ## 命令集（一行一命令；未知命令回 usage）
 *
 * - `state` — host 状态（isRunning / pendingRequest / awaitingAnyKey / failure）
 * - `rev` — console sink revision + View 重绘计数（量化「谁在驱动重绘」）
 * - `stats` — CanvasConsoleView 的 onDraw / repaint / requestRedraw / notify 计数与烘焙图尺寸
 * - `img <资源名>` — 试取一帧（sprite 表 → 文件层），回宽高与命中文件路径；
 *   「图为什么不显示」的第一排查口
 * - `bg` — 当前背景层列表（SETBGIMAGE 落了什么）
 * - `lines [n]` — console 末 n 行文本快照
 * - `lockwatch [on|off|readout|reset|status]` — 锁持有/等待采样器的**真机出口**（OBS2-L2）；
 *   **缺省关**，装配期不自动挂载，只有这条命令把它打开（[LockWatchCommand] 逐条口径）
 * - `submit <文本>` — 提交输入（驱动选单 / 推进对话）
 * - `tap <x> <y>` — 模拟点击命中（等价手指点屏幕上的按钮行）
 * - `modgate` — Mod 运行时总闸 / 本局会话 / 安装计数 / 存档侧车一行读数
 *   （[ModGateDiagnostics] 与设置页诊断卡同源）
 *
 * ## 线程与安全
 *
 * - **授权位在哪儿读**：debug 档不读偏好表；release 档的授权位是壳层偏好，只能在主线程读，而
 *   startOnce 由 VM 线程的装载尾段调用（GameHost.kt:1050）⇒ 只有「非 debuggable」那一格用 view.post
 *   hop 回主线程取一次位、再走同一个判定入口（view.post 是这条链既有口径，同 GameHost.kt:1046）。
 *   未授权那一格在**建线程之前**就 return，不是「起了线程不读 cmd.txt」。代价（组长批复认可）：
 *   开启要等下一次装载才生效，关掉当场走 Companion.stop。
 * - 轮询是单代 daemon 线程（500ms），`startOnce` 幂等（Activity 重建不叠加）；代次由
 *   [SingleDaemonSlot] 管（CMP-17）：`MainActivity.onDestroy` 调 [stop] 真复位，
 *   新一代 `startOnce` 换掉旧代的 `host`/`view`，旧的 Activity/View 树不再被进程级静态引用钉住。
 * - 命令执行**不在 UI 线程**（就是这条 daemon 线程）：`state`/`rev`/`lines` 只读
 *   （volatile 字段与 sink 快照口本就跨线程），`submit`/`tap` 走 GameHost/View 的
 *   既有跨线程入口（QueueInputSink 单生产、View 的 tap 命中表有锁口径）。
 * - 任何命令抛异常都折成结果文本回写，轮询线程不死。
 */
class DevToolsHost private constructor(
    private val host: GameHost,
    private val view: CanvasConsoleView,
    /**
     * 判词 9：true = **玩家授权档**（release 包 + 玩家显式授权）⇒ execute 先过 playerDiagRefusal，
     * 只读/观测面之外的命令一律不执行；false = debug 档（全功能，判词 9 之前的原行为逐字不变）。
     */
    private val authorizedOnly: Boolean,
) {

    private val dir: File =
        File(view.context.getExternalFilesDir(null), DEV_DIR_REL).apply { mkdirs() }
    private val cmdFile: File get() = File(dir, CMD_NAME)
    private val outFile: File get() = File(dir, OUT_NAME)

    private fun loop() {
        // 退出条件只有「线程被打断」：CMP-17 之后不存在第二个可能分叉的 running 布尔位，
        // 线程存活状态就是唯一事实（stop() 用 interrupt + join 收口）。
        while (!Thread.currentThread().isInterrupted) {
            try {
                val cmd = cmdFile.takeIf { it.isFile }?.readText()?.trim()
                if (!cmd.isNullOrEmpty()) {
                    cmdFile.delete()
                    val out = runCatching { execute(cmd) }
                        .getOrElse { "EXC ${it.javaClass.simpleName}: ${it.message}" }
                    outFile.writeText(out)
                    Log.i(TAG, "[$cmd] → $out")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "轮询异常（不影响下轮）: ${t.message}")
            }
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun execute(cmd: String): String {
        val sp = cmd.indexOf(' ')
        val name = if (sp < 0) cmd else cmd.take(sp)
        val arg = if (sp < 0) "" else cmd.substring(sp + 1).trim()
        // 判词 9：玩家授权档只放开只读/观测命令。**分发闸 = 文件顶层的唯一入口 dispatchGuarded**
        //（同 diagnosticsAllowed 的口径：一条链一个判定出口，将来加第三个来源只改那一处）。
        // 拒绝时下面那个 when 臂（proceed）**一次都不跑** ⇒ 用例钉的是行为，不是「集合里有这个名字」。
        // **未分类命令按缺省拒绝 = fail-closed**：后来人加了 when 分支忘了归类，落在授权档就是拒绝，
        // 不许出现「没登记就等于放开」。debug 档 authorizedOnly = false 压根不进拒绝臂 ⇒ 原行为逐字不变。
        return dispatchGuarded(authorizedOnly, name, onRefuse = { Log.w(TAG, "授权档拒绝命令 " + name) }) {
            when (name) {
                "state" -> state()
                "gen" -> gen()
                "rev" -> "console.revision=${host.debugRevision()} | ${view.debugStats()} | " +
                    host.debugLateInputCounters()
                "stats" -> view.debugStats()
                "view" -> view.debugMetrics()
                "img" -> img(arg)
                "bg" -> view.debugBackgrounds()
                "lines" -> lines(arg.toIntOrNull() ?: 10)
                "chips" -> chips()
                "hits" -> view.debugHits()
                "frames" -> framesCmd(arg)
                "turnlog" -> turnlog()
                "inputtrail" -> inputTrail()
                "loadtrace" -> loadtrace()
                "lockwatch" -> LockWatchCommand.execute(arg)
                "modgate" -> ModGateDiagnostics.report(view.context)
                "sample" -> sample(arg.toIntOrNull() ?: 3000)
                "var" -> varRead(arg)
                "vars" -> varsList(arg)
                "callstack" -> callstack()
                "where" -> where()
                "dating" -> datingForensics()
                "feed" -> feed(arg)
                "dumpvars" -> dumpVars(arg.toIntOrNull() ?: 8)
                "trace" -> traceCmd(arg)
                "break" -> breakCmd(arg)
                "set" -> DevState.setCmd(host, dir, arg)
                "cheat" -> DevState.cheatCmd(host, dir, arg)
                "snapshot" -> DevState.snapshotCmd(host, dir, snapSandbox(), arg)
                "restore" -> DevState.restoreCmd(host, dir, snapSandbox(), arg)
                "log" -> DevState.logCmd(host, dir, arg, logSlot)
                "caps" -> caps()
                "events" -> events(arg.toIntOrNull() ?: 50)
                "submit" -> {
                    host.submit(arg)
                    "submitted: $arg"
                }
                "tap" -> tapCmd(arg)
                else -> USAGE
            }
        }
    }

    /**
     * `tap <x>,<y>`：把一次点击投进**与真机命中同一条**投喂口，并回报取证结果。
     *
     * 独立成函数（RT2-GATE-OPEN 收口）：`execute` 是纯分发器，LongMethod 闸不该被某一条
     * 命令的实现细节吃掉；此处的 `TapOutcome` 口径本就是一段自成体系的语义。
     *
     * 回贴底那一击按原版 `MainWindow.cs:799-820` **只回贴底、不提交**（`:808` 的
     * `if (!flag)` 罩住整段命中分支），否则调试时会把「滚动着的视图上点一下」记成一次
     * 按钮提交，取证就与真机行为对不上。NF 等待与真机同口径：不强制贴底（MainWindow.cs:949）。
     */
    private fun tapCmd(arg: String): String {
        val parts = arg.split(',', ' ').filter { it.isNotBlank() }.map { it.toFloat() }
        val x = parts.getOrElse(0) { 0f }
        val y = parts.getOrElse(1) { 0f }
        val outcome = view.tap(x, y, host.acceptance(), host.pendingRequest()?.noFocus == true)
        val text = outcome.hit
        return when {
            outcome.snappedToBottom ->
                "tap($x,$y) 回贴底，未提交" + if (text == null) "" else "（命中 $text 已丢弃）"
            text == null -> "tap($x,$y) 未命中"
            else -> {
                // 与真机命中同一条投喂口：MainActivity.handleConsoleTap 命中后走
                // submitButtonValue → GameHost.submitButton。带鼠标的 INPUT
                // （QOL 子选单的 `INPUT -1, 1`）要靠这一维写 `RESULT:1` 左键位，
                // 走 host.submit 会把调试通道变成「键盘回车」，取证与真机对不上
                // （原版 MainWindow.cs:824-849 的 changedByMouse 分支）。
                host.submitButton(text)
                "tap($x,$y) 命中并提交: $text"
            }
        }
    }

    /**
     * `frames [on|off|n]`：逐帧诊断包（2026-11-23「地图疯狂跳动」专项的真机兜底面）。
     *
     * 为什么单开一条命令而不是塞进 `stats`：`stats` 是**聚合**读数（帧数/耗时），
     * 回答不了「相邻两帧的版面量有没有变」；而「谁在每拍改变布局」只能靠逐帧比对。
     * 判据写在返回文本的表头里（同 [com.eratw.port.render.canvas.FrameTrace] 类文档）：
     * 版面量跳动 ⇒ 视图层在动；全稳而屏仍动 ⇒ canvas 贴图/硬件加速层。
     *
     * 缺省关（`on` 才记），关时 `onDraw` 零成本。
     */
    private fun framesCmd(arg: String): String {
        val a = arg.trim().lowercase(Locale.ROOT)
        return when (a) {
            "on" -> {
                // 与设置页开关走同一个环（FrameTraceChannel）：调试通道开的就是玩家那条路，
                // 免得「devtools 看得到、报告里没有」两份现场对不上。
                FrameTraceChannel.attach(view)
                "frameTrace: on（环形缓冲 ${com.eratw.port.render.canvas.CanvasConsoleView.FRAME_TRACE_CAPACITY} 帧；" +
                    "复现抖动后 `frames` 读表，报告里也会带上这一段）"
            }
            "off" -> {
                FrameTraceChannel.detach()
                "frameTrace: off（环已清空；报告里会写「未开启逐帧诊断」）"
            }
            "" -> view.debugFrames()
            else -> view.debugFrames(
                a.toIntOrNull()?.takeIf { it > 0 }
                    ?: com.eratw.port.render.canvas.CanvasConsoleView.FRAME_TRACE_CAPACITY,
            )
        }
    }

    private fun state(): String =        "isRunning=${host.isRunning()} pending=${host.pendingRequest()} " +
            "anyKey=${host.awaitingAnyKey()} failure=${host.failure ?: "无"}"

    /**
     * `gen`：调试通道代次 + 当前存活的 `eratw-*` 线程名（CMP-17 的真机判据）。
     *
     * `generation` 由 `SingleDaemonSlot` 递增：Activity 每次 `onDestroy → stop()` 复位、
     * 下一次 `startOnce()` 起新一代。recreate 之后应看到「代次 +1 且 eratw-devtools 仍只有 1 条」，
     * 而不是「代次不变 / 线程叠加」——后者就是旧 Activity/View 被进程级静态引用钉住的形态。
     */
    private fun gen(): String {
        val names = Thread.getAllStackTraces().keys
            .map { it.name }
            .filter { it.startsWith("eratw") }
            .sorted()
        val devtools = names.count { it == "eratw-devtools" }
        return "generation=$generation running=$running devtoolsThreads=$devtools threads=${names.joinToString(",")}"
    }

    /**
     * `turnlog`：逐页「提交 → 下一次进入等待点」的墙钟毫秒（最近 64 页）＋末尾一行分位摘要。
     *
     * 摘要口径 = [TurnPercentiles]（定长窗口最近秩 p50/p95/max，窗口未满 20 条时 p95 即 max），
     * 「这一局整体变慢没有」看末尾这一行，逐页定位仍看上面 64 条。
     */
    private fun turnlog(): String {
        val log = host.debugTurnLog()
        val body = if (log.isEmpty()) "（无翻页读数：本会话还没提交过输入）" else log.joinToString("\n")
        return body + "\n" + host.debugTurnPercentilesSummary()
    }

    /**
     * `inputtrail`：输入四层流水的现场读数（obs-inputseq 波 + 组长接的宿主面）。
     *
     * 一行四层差分 + core 面流水原文。「玩家点了没反应」的四层判据在这里一次读全：
     * `received`（宿主收到几次提交，含被闸丢的）→ `dropped(gate)`（执行期闸 = 原版
     * Running 早退，`EmueraConsole.cs:1374-1377`，属**预期行为**）→ `enq`（`lastSubmittedSeq`）
     * → `con`（`lastConsumedSeq`）；对不上的差额才是要查的层。`awaiting` 给当前等待点标识，
     * `pending` 给队列积压条数。
     */
    private fun inputTrail(): String {
        val (received, gateDropped) = host.debugInputGateCounters()
        val r = host.debugInputTrail()
            ?: return "received=$received dropped(gate)=$gateDropped（会话未装配：core 面无流水）"
        return buildString {
            append(
                "received=$received dropped(gate)=$gateDropped enq=${r.lastSubmittedSeq} " +
                    "con=${r.lastConsumedSeq} drop(void)=${r.dropVoidCount} " +
                    "drop(parse)=${r.dropParseCount} pending=${r.pendingCount} " +
                    "awaiting=${r.awaitingTag ?: "-"} closed=${r.isClosed}",
            )
            append('\n')
            if (r.trail.isEmpty()) {
                append("（流水为空：本会话还没有一次提交走到队列）")
            } else {
                append(r.trail.joinToString("\n"))
            }
        }
    }

    /** `loadtrace`：本次装载逐段读数（段名 + 已用堆 + 距起点毫秒），口径同 logcat `load.*`。 */
    private fun loadtrace(): String {
        val lines = host.debugLoadTraceLines()
        return if (lines.isEmpty()) "（无装载读数：还没进入装载链）" else lines.joinToString("\n")
    }

    /**
     * `caps`（R3）：能力声明。读侧 / 写侧 / 快照 / 事件四类一行点名，供外部脚本按版本协商；
     * 未声明的命令维持 [USAGE] 拒绝路径，不因为这张表而多认任何命令。
     */
    /**
     * 判词 9：授权档改口 schema=1 caps=read —— 能力声明就是「外部脚本别照着写侧清单来撞拒绝」。
     * debug 档那一条字面串逐字不变（EventWiringTest 钉的就是它，不许动）。
     */
    private fun caps(): String =
        if (authorizedOnly) "schema=1 caps=read" else "schema=1 caps=read,write,snapshot,events"

    /**
     * `events [n]`（R3）：常开事件环尾 n 行（缺省 50，与落盘格式逐字相同）+ 环统计与落盘位置。
     *
     * 未 [EventJournal.begin] 时文件为 null —— 此时如实说明「只读内存环」，不造一个假路径。
     */
    private fun events(n: Int): String {
        val tail = if (n <= 0) emptyList() else EventJournal.lines().takeLast(n)
        val file = EventJournal.file()
        val where = if (file == null) {
            "mem-only（未绑定落盘目录）"
        } else {
            "file=${file.path} bytes=${if (file.isFile) file.length() else 0L}"
        }
        val head = "${EventJournal.stats()} $where"
        return if (tail.isEmpty()) head else head + "\n" + tail.joinToString("\n")
    }

    /**
     * `sample <ms>`：对脚本线程（VM 线程）做**穷采样**：叶子帧 top 20 + 栈顶三层 top 15。
     *
     * 为什么放在调试通道里：真机 logcat 被 ROM 吞掉时，「一页为什么慢」只能从线程栈看；
     * 本命令在 DevTools 线程每 10ms 取一次 VM 线程栈（[Thread.getStackTrace] 安全只读），
     * 跑满指定时长后按出现次数排序返回。默认 3000ms，上限 30000ms。
     *
     * 叶子帧只能告诉你「烧在哪一行」，回答不了「谁调的」——栈顶三层（调用方在前）才能
     * 区分「指令页加载」「语句惰性解码」「变量名大写化」这三类同叶子点的不同来源。
     */
    private fun sample(ms: Int): String {
        val vm = host.debugVmThread() ?: return "VM 线程未启动（装载未完成或已退出）"
        val durationMs = ms.coerceIn(200, 30_000)
        val leafCounts = LinkedHashMap<String, Int>()
        val deepCounts = LinkedHashMap<String, Int>()
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        var samples = 0
        while (System.nanoTime() < deadline) {
            val st = vm.stackTrace
            if (st.isNotEmpty()) {
                val leaf = st[0]
                val leafKey = "${leaf.className.substringAfterLast('.')}.${leaf.methodName}"
                leafCounts[leafKey] = (leafCounts[leafKey] ?: 0) + 1
                val depth = minOf(3, st.size)
                val deep = (0 until depth).joinToString(" <- ") {
                    "${st[it].className.substringAfterLast('.')}.${st[it].methodName}"
                }
                deepCounts[deep] = (deepCounts[deep] ?: 0) + 1
            }
            samples++
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                // 采样被 stop() 打断：先把中断位补回来，外层轮询循环才会在下一轮退出
                //（catch 会清掉中断标志，不补的话要等采样跑满 30 s 才停得下来）。
                Thread.currentThread().interrupt()
                break
            }
        }
        val leafTop = leafCounts.entries.sortedByDescending { it.value }.take(20)
        val deepTop = deepCounts.entries.sortedByDescending { it.value }.take(15)
        return "samples=$samples alive=${vm.isAlive}\n" +
            "--- leaf top ---\n" +
            leafTop.joinToString("\n") { "${it.value}x ${it.key}" } +
            "\n--- stack3 top ---\n" +
            deepTop.joinToString("\n") { "${it.value}x ${it.key}" }
    }

    // ------------------------------------------------------------------
    // DBG3 实时读数命令（var/vars/callstack/where/feed/dumpvars/trace/break）
    // ------------------------------------------------------------------

    /**
     * 语句探针（`trace`/`break` 命令共用；首次使用时装到 `session.runtime.statementProbe`，
     * 单实例防两个功能互相顶槽）。探针缺席 = 零开销（StatementProbe KDoc 的守卫结构）。
     */
    private var probe: DevProbe? = null

    private fun ensureProbe(): DevProbe {
        probe?.let { return it }
        val session = host.debugSession() ?: error("（未装载：会话还没起来，先等 loadtrace 出 ready 段）")
        val p = DevProbe { session.runtime.program.statement(it) }
        session.runtime.statementProbe = p
        probe = p
        return p
    }

    /**
     * 读数竞态前缀：VM 正在跑（无 pendingRequest 且非任意键等待）时 store/调用栈读数可能
     * 正被 VM 线程改写——读数仍给（元素级读基本原子），但带前缀提示；等待输入时的读数精确。
     *
     * 判定逻辑与写侧五命令（DevState 的写安全闸）同构，单一实现在 [DevState.racePrefix]，
     * 本方法是其 GameHost 入参形态的委托（DBG5 起提为 internal 供 DevStateSelfTest 直测）。
     */
    internal fun racePrefix(): String = DevState.racePrefix(host)

    /**
     * snapshot/restore 的快照工作沙箱（快照槽字节在此中转，不碰会话自己的存档根）。
     * cacheDir 下：随 app 卸载/清缓存回收，adb 不可见（对外可见的只有 `--out` 指到的
     * 外置 dev 目录）。[DevState] 每次执行前清空重建，目录独占。
     */
    private fun snapSandbox(): File = File(view.context.cacheDir, "dbg5-snap")

    /**
     * `log` 命令的记录器持有槽（实例级，理由见 [DevLogSlot] KDoc——Activity 重建换代后
     * 从空槽开始，旧会话记录器不跨代残留）。
     */
    private val logSlot = DevLogSlot()

    /** `var NAME[:i[:j]]`：单变量读（名归一大写；int/str 两槽都试；未定义给可读文案）。 */
    private fun varRead(spec: String): String {
        val session = host.debugSession() ?: return "（未装载）"
        val parts = spec.split(':')
        val name = parts.firstOrNull()?.trim()?.uppercase().orEmpty()
        if (name.isEmpty()) return "用法: var NAME[:i[:j]]（如 var DAY / var CFLAG:2:3）"
        for (p in parts.drop(1)) {
            if (p.trim().toLongOrNull() == null) return "下标不是数字: $p"
        }
        // 下标省略 = [0]（契约口径；DBG5 真机实测揪出：空 LongArray 对一维变量 readInt
        // 直接维度 CodeEE，「var MONEY」恒报未定义——parseLhs/DevState 侧都缺省补 [0]）。
        val idx = if (parts.size == 1) longArrayOf(0L)
        else LongArray(parts.size - 1) { i -> parts[i + 1].trim().toLong() }
        // 展示用 [i,j] 形（LongArray 默认 toString 是 J@hash，DBG5 真机实测揪出）。
        val idxText = idx.joinToString(",", "[", "]")
        val iv = runCatching { session.store.readInt(name, idx) }.getOrNull()
        val sv = runCatching { session.store.readStr(name, idx) }.getOrNull()
        return when {
            iv != null -> racePrefix() + "$name$idxText = $iv" +
                (sv?.takeIf { it.isNotEmpty() }?.let { " | str=\"$it\"" } ?: "")
            sv != null -> racePrefix() + "$name$idxText = \"$sv\""
            else -> {
                // 帧局部变量（LOCAL/LOCALS/ARG/ARGS）：常规读口只按**当前帧**解析，
                // VM 没在执行（撞闸回卷 / 暂停）时一律拒读 ⇒ 退回逐帧导出
                // （core `VariableStore.frameSnapshotLines`，纯只读取证口）。
                val up = name.uppercase()
                val hits = runCatching { session.store.frameSnapshotLines() }
                    .getOrDefault(emptyList())
                    .filter { it.contains(" $up[") }
                if (hits.isEmpty()) {
                    "$name 未定义或不可读（readInt/readStr 都拒了）"
                } else {
                    racePrefix() + "$name 当前帧不可读，逐帧导出：\n" + hits.joinToString("\n")
                }
            }
        }
    }

    /** `vars <前缀>`：枚举变量名（registry 系统面 + allUserVars 用户面，前缀大写匹配，上限 200）。 */
    private fun varsList(prefix: String): String {
        val session = host.debugSession() ?: return "（未装载）"
        val p = prefix.trim().uppercase()
        val names = sortedSetOf<String>()
        session.store.registry.all().forEach { names.add(it.name) }
        session.store.allUserVars().forEach { names.add(it.spec.name) }
        val hit = names.filter { p.isEmpty() || it.startsWith(p) }.take(200)
        return "命中 ${hit.size}/${names.size}（上限 200）：\n" + hit.joinToString("\n")
    }

    /**
     * `callstack`：ERB 函数调用栈（#0 = 最内帧）+ VM 线程 Java 栈顶 5 帧。
     * 反射口径与 [ErrorReportCollector.vmStackLines] 一致（`ProcessRuntime.vm` → `funcStack`/`depth`）；
     * 帧名走运行期 [com.eratw.port.core.proc.FunctionTable]（[ErrorReportCollector.functionNameResolver]），
     * 不读 `program.functions`：`.erbc` 产物档那张惰性表在调试线程上解码会与 VM 线程抢未加锁的
     * 实例缓冲（报告路径上同样的问题已收口）。帧序/截断统一由 [VmStack.frameLines] 判
     * （`depth` 是栈顶下标而不是帧数，见那里的说明）。字段名变更时优雅降级。
     */
    private fun callstack(): String {
        val session = host.debugSession() ?: return "（未装载）"
        val frames = runCatching {
            val vf = com.eratw.port.core.proc.ProcessRuntime::class.java.getDeclaredField("vm")
            vf.isAccessible = true
            val vm = vf.get(session.runtime) ?: return@runCatching null
            val fsF = vm.javaClass.getDeclaredField("funcStack").apply { isAccessible = true }
            val dF = vm.javaClass.getDeclaredField("depth").apply { isAccessible = true }
            val fs = fsF.get(vm) as IntArray
            val d = dF.getInt(vm)
            VmStack.frameLines(fs, d, Int.MAX_VALUE, ErrorReportCollector.functionNameResolver(session.runtime))
        }.getOrElse { return "读数不可用：${it.javaClass.simpleName}（字段名变更？）" }
        val javaTop = host.debugVmThread()?.stackTrace?.take(5)
            ?.joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "（VM 线程未启动）"
        return racePrefix() + (frames?.joinToString("\n") ?: "（无帧：VM 未在执行）") + "\n--- java ---\n$javaTop"
    }

    /**
     * `where`：游戏现在在干嘛的浓缩读数（等待面 + revision + 末行 + 调用栈顶 3 帧 + 本页函数流水）。
     *
     * ## 本页流水两行的口径（fix-pagetrailwire）
     *
     * - `本页`：`Vm`/`QueueInputSink` 上那个进帧环的**实时**读数（[GameHost.debugPageFlow]）。
     *   它在**等待点之后**恒为空 —— 页边界清零紧跟 `onTurnReady` 交付
     *   （`QueueInputSink` 的 `readAnyKey`/`waitInput` 结算段，core 侧 `PageFunctionTrail`），
     *   而 VM 停在等待点时那一页已经交付过了。所以「等待点之后读到的是**上一页**」
     *   指的是下面那一行：宿主在交付回调里、清零之前拍下的快照。
     * - `上一页`：刚结束那一页的 `最旧→最新` 进帧序列（[GameHost.debugLastPageFlow]）。
     *   「这一页为什么慢」要读它；`longturn` 钉行里的 `page:` 段就是同一份文本。
     *
     * 线程：本命令跑在 DevTools daemon 线程，两行都是**跨线程读** ⇒ 尽力而为（环绕覆盖 /
     * 清零并发时可能个别槽新旧混合、长度 ±1），只作人工定位、**不参与任何判定**。
     * 名字解析走运行期函数表（`FuncMeta`，不可变数组），不碰 `.erbc` 惰性函数表的
     * 未加锁解码缓冲（判词与 `callstack` 同源）。名字解析失败/越界降级成 `#<下标>`，
     * 流水为空才写 `-`。
     */
    private fun where(): String {
        val pending = host.pendingRequest()
        val pendingText = pending?.let {
            val def = if (it.hasDef) {
                if (it.type == com.eratw.port.core.host.InputRequest.InputType.INT) it.defInt.toString() else "\"${it.defStr}\""
            } else {
                "无默认"
            }
            "${it.type}(默认=$def)"
        } ?: "无"
        val lastLine = host.debugConsoleLines(1).firstOrNull()?.take(80) ?: "（console 空）"
        val top3 = callstack().lineSequence().take(3).joinToString(" ; ")
        val pageNow = PageTrailWire.trailText(host.debugPageFlow())
        val pageLast = PageTrailWire.trailText(host.debugLastPageFlow())
        return racePrefix() +
            "running=${host.isRunning()} pending=$pendingText anyKey=${host.awaitingAnyKey()} " +
            "rev=${host.debugRevision()}\n末行: $lastLine\n栈顶: $top3\n" +
            "本页: $pageNow\n上一页: $pageLast\n" +
            // PLAY-INPUT-LATE：执行期滞留入力的四个桶（真机抓「点地图键没反应」现场就靠这一行）
            host.debugLateInputCounters()
    }

    /**
     * `dating`：「约会中／路人子」现场读数（diag-datingsync，与错误报告里那一行同源同口径）。
     *
     * 用途是把三条只能靠玩家描述的反馈变成可读数：①某人被 `CHK_DATENOW` 判成"正在约会"
     * 时到底哪几个角色的 `CFLAG:约会中` 不等于基准（4.980 基准 `MAIN_MAP` 与 4.981 基准
     * `GET_HOME_MAP()` 双口径各出一行，出处见 core 侧 [com.eratw.port.core.proc.DatingForensics]）；
     * ②路人子那两半闸（`TALENT:路人` 与 `TFLAG:一日一回` 的第 9 位）当时各是什么；
     * ③「约去约会后人消失」道中链——`FLAG:约会的对象`、MASTER/对象的
     * `CFLAG:現在位置/同行/约会中`、`TFLAG:约会道中`（97xx = ROAD_TO 道中 MAPID 段）。
     * 只读，不改状态；语料侧的 `#DIM SAVEDATA` 基准读不到时直说不可用（系统变量恒可读，
     * 一片 0 的现场比没有现场更容易误导定性）。
     */
    private fun datingForensics(): String {
        val session = host.debugSession() ?: return "（未装载）"
        val f = runCatching { com.eratw.port.core.proc.datingForensics(session.store, EratwForensicsLayout.ERATW) }
            .getOrElse { return "读数失败：${it.javaClass.simpleName}: ${it.message}" }
        return racePrefix() + if (f.isEmpty) {
            "（不可用：MAIN_MAP / RANDOM_CHARANUM 未声明——语料没装或不是 eraTW 包）"
        } else {
            f.render(EratwForensicsLayout.ERATW_LABELS)
        }
    }

    /** `feed v1,v2,...`：批量入队输入（调试后门口径：允许先入队后取值，与 UI 代理的「不抢跑」不同）。 */
    private fun feed(arg: String): String {
        val items = arg.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) return "用法: feed 0,1,100（逗号/分号分隔，逐条入队）"
        items.forEach { host.submit(it) }
        return "queued ${items.size}: ${items.joinToString(" | ")}"
    }

    /** `dumpvars [每变量下标数]`：全量变量 dump 落 varsdump.txt（行数上限 20000 防撑爆存储）。 */
    private fun dumpVars(maxIdx: Int): String {
        val session = host.debugSession() ?: return "（未装载）"
        val perVar = maxIdx.coerceIn(1, 64)
        val names = sortedSetOf<String>()
        session.store.registry.all().forEach { names.add(it.name) }
        session.store.allUserVars().forEach { names.add(it.spec.name) }
        val out = File(dir, VARSDUMP_NAME)
        var lines = 0
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("# dumpvars perVar=$perVar 变量数=${names.size}（竞态前缀=${racePrefix().isNotEmpty()}）\n")
            outer@ for (name in names) {
                for (i in 0 until perVar) {
                    val idx = longArrayOf(i.toLong())
                    val iv = runCatching { session.store.readInt(name, idx) }.getOrNull()
                    val sv = runCatching { session.store.readStr(name, idx) }.getOrNull()
                    if (iv == null && (sv == null || sv.isEmpty())) continue@outer
                    w.write("$name[$i] = ${iv ?: "?"}${if (!sv.isNullOrEmpty()) " | \"$sv\"" else ""}\n")
                    lines++
                    if (lines >= MAX_DUMP_LINES) break@outer
                }
            }
        }
        return "varsdump.txt 完成：变量 ${names.size} 行 $lines（每变量前 $perVar 个下标）→ ${out.absolutePath}"
    }

    /** `trace <FN|*> [容量]` 开 / `trace off` 关 / `trace` 落盘并回末 20 条。 */
    private fun traceCmd(arg: String): String {
        val toks = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when (toks.firstOrNull()) {
            null -> {
                val p = probe ?: return "trace 未开（用法: trace <FN|*> [cap] / off）"
                val snap = p.traceSnapshot()
                File(dir, "trace.txt").writeText(snap.joinToString("\n"), Charsets.UTF_8)
                "trace 条数=${snap.size}（全文→ ${File(dir, "trace.txt").absolutePath}）\n" +
                    snap.takeLast(20).joinToString("\n")
            }
            "off" -> {
                probe?.trace(false, null, 0)
                "trace off（探针保留，break 不受影响）"
            }
            else -> {
                val fn = toks[0]
                val cap = toks.getOrNull(1)?.toIntOrNull() ?: 2000
                val p = ensureProbe()
                p.trace(true, fn.takeIf { it != "*" }, cap)
                "trace on fn=${fn} cap=$cap（dump: echo trace > cmd.txt 再读 out.txt）"
            }
        }
    }

    /** `break FN[:行]` 加 / `break off` 清 / `break` 看断点与命中。 */
    private fun breakCmd(arg: String): String {
        val toks = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
        return when (toks.firstOrNull()) {
            null -> {
                val p = probe
                val hits = p?.drainHits().orEmpty()
                val labels = p?.breakLabels().orEmpty()
                "断点 ${labels.size}：${labels.joinToString(", ").ifEmpty { "（无）" }}\n" +
                    (if (hits.isEmpty()) "（暂无命中；命中后此处返回快照）"
                    else hits.joinToString("\n"))
            }
            "off" -> {
                probe?.clearBreaks()
                "breaks cleared"
            }
            else -> {
                val label = ensureProbe().addBreak(toks[0])
                "break +$label（现有 ${probe?.breakCount} 个；命中后 `break` 查看，`where` 读现场）"
            }
        }
    }

    private fun img(resource: String): String {
        if (resource.isEmpty()) return "用法: img <资源名>"
        val wiring = host.graphicsWiring
            ?: return "图形通路未装配（尚未装载完成）"
        val size = wiring.frames.let { f ->
            runCatching { f.frame(resource)?.let { "${it.srcW}x${it.srcH}" } }.getOrNull()
        }
        val file = runCatching { wiring.frames.locateFileForDebug(resource)?.absolutePath }.getOrNull()
        return "img[$resource] 帧=${size ?: "取不到"} 文件=${file ?: "无命中"}"
    }

    /**
     * `chips`：右侧「大按钮」栏当前候选，读**引擎输入等待快照**
     * （`ConsoleSink.noteInputWait` → `LineBufferConsoleSink.currentOptions()`）。
     *
     * 与 `lines` 的区别是它回答「这一次等待到底有哪些选项、值是什么」——
     * 数值按钮显示 `#序号`，字符串按钮显示值（如 `exit`），并带按钮上印的字与来源。
     * 非取值等待（WAIT/ENTER）下为空，这是口径而不是故障。
     */
    private fun chips(): String {
        val list = host.currentOptions()
        if (list.isEmpty()) return "（当前等待页没有选项：非取值等待或本页未打印按钮）"
        return list.joinToString("\n") { c ->
            // 读**按钮对象的值**，不读 `ordinal`：ordinal 现在只表示「标签前缀与值一致」
            // （给右侧栏徽标用），地图类按钮印 `[27]` 而值是 227 时它是 null，
            // 拿 null 当「非数值按钮」会把诊断读数码歪。
            val id = (c.value as? Long)?.let { "#$it" } ?: "str"
            val color = c.colorRgb?.let { "#%06X".format(it and RGB_MASK) } ?: "-"
            "$id value=${c.value} origin=${c.origin} visual=${c.visual} color=$color label=${c.label}"
        }
    }

    private fun lines(n: Int): String {
        val ls = host.debugConsoleLines(n.coerceIn(1, 200))
        return if (ls.isEmpty()) "（console 空 / 未装载）" else ls.joinToString("\n")
    }

    companion object {
        private const val TAG = "ERATWDBG"
        private const val CMD_NAME = "cmd.txt"
        private const val OUT_NAME = "out.txt"
        private const val POLL_MS = 500L

        /** `dumpvars` 的行数上限（防撑爆外部存储）。 */
        private const val MAX_DUMP_LINES = 20_000
        private const val RGB_MASK = 0xFFFFFF

        /**
         * 调试通道的**进程级单代槽**（CMP-17）。
         *
         * 旧写法用 `started` CAS + 永不归位的 `running`：`Activity.recreate()`（改字号档位
         * `MainActivity` 那条、fontScale/density/locale、开发者「不保留活动」）之后，新一代
         * `GameHost`/`View` 调 [startOnce] 被 CAS 拒掉，轮询线程继续持有**第一代**对象 ——
         * 每次重建泄漏 1× Activity + View 树，且 adb 命令永远打在已销毁的会话上。
         *
         * 现在代次与线程都由 [SingleDaemonSlot] 管：`onDestroy` → [stop] 真打断并清引用，
         * 新一代 `startOnce` 起第二代（未 stop 时仍然幂等、不叠加）。
         */
        private val slot = SingleDaemonSlot<DevToolsHost>("eratw-devtools")

        /** 调试通道是否在跑（`stop()` 之后必须为 false）。 */
        val running: Boolean get() = slot.isRunning

        /** 已经起过几代（诊断读数：每次 Activity 重建 +1）。 */
        val generation: Int get() = slot.generation

        /**
         * 通道起不起、起哪一档 —— **判定只有一个入口**（判词 9 / PDIAG1）。
         *
         * - debug 包（FLAG_DEBUGGABLE）：全功能档，与玩家开关无关，判词 9 之前的行为逐字不变；
         * - release 包：只有玩家在设置页显式授权（prefsEnabled）才起，且起的是只读档；
         * - 两者都不成立 ⇒ false，调用方在**建线程之前**就 return（缺省关 = 线程根本不存在）。
         *
         * ApplicationInfo.FLAG_DEBUGGABLE 是编译期内联常数、本函数不碰 Context ⇒ 纯 JVM 单测可以
         * 直接点值（app 侧没有 Robolectric，判定面的形状只能是「纯函数 + 源码钉子」）。
         * 以后加第三个来源（远程开关 / 内测档）也只改这里，不在调用点堆条件。
         */
        internal fun diagnosticsAllowed(flags: Int, prefsEnabled: Boolean): Boolean =
            flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 || prefsEnabled

        /** 幂等启动：只有当前代线程已经死了才会真的起新一代（Activity 重建 / 反复装载都不叠加）。 */
        fun startOnce(host: GameHost, view: CanvasConsoleView) {
            val flags = view.context.applicationInfo.flags
            // 第一格：debug 档 —— 同步原路，一个字节都不多读（尤其不摸偏好表：VM 线程装载链不许
            // 直接读 SharedPreferences，同 BusyIndicatorWiringTest:103 钉的那条口径）。
            if (diagnosticsAllowed(flags, prefsEnabled = false)) {
                startChannel(host, view, authorizedOnly = false)
                return
            }
            // 第二格：release 档只剩「玩家授权」这一条来源，而授权位在 SharedPreferences 里 ⇒ 只能在
            // 主线程读 ⇒ 用这条装载链既有的 view.post hop 回主线程取位，再走同一个判定入口。
            view.post {
                val enabled = runCatching { UiPrefs.playerDiagnosticsEnabled(view.context) }.getOrDefault(false)
                if (diagnosticsAllowed(flags, enabled)) startChannel(host, view, authorizedOnly = true)
            }
        }

        /**
         * 起线程 + 记档（判词 9 §2.4：谁在什么时候打开过 —— 日志这一行；诊断报告那两行见
         * authorizationReportLines）。授权档的启动日志把命令文件全路径记一次（既有 dev 目录落盘位置
         * 沿用、不新增任何导出面）；debug 档那条日志文本逐字不变。
         */
        private fun startChannel(host: GameHost, view: CanvasConsoleView, authorizedOnly: Boolean) {
            val started = slot.start(
                create = { DevToolsHost(host, view, authorizedOnly) },
                body = { it.loop() },
            )
            if (!started) return
            if (!authorizedOnly) {
                Log.i(
                    TAG,
                    "调试通道已启动（第 ${slot.generation} 代；轮询 files/eratw/dev/cmd.txt，500ms）\n$USAGE",
                )
                return
            }
            val authoredAt = runCatching { UiPrefs.playerDiagnosticsAuthoredAt(view.context) }.getOrDefault(0L)
            val cmdPath = File(view.context.getExternalFilesDir(null), "$DEV_DIR_REL/$CMD_NAME").absolutePath
            val forensics = authorizationReportLines(
                readOnlySession = true,
                enabled = true,
                authoredAtMillis = authoredAt,
                nowMillis = System.currentTimeMillis(),
            ).joinToString("\n")
            Log.i(
                TAG,
                "调试通道已启动（第 ${slot.generation} 代；玩家授权只读档；轮询 $cmdPath，500ms）\n" +
                    forensics + "\n" + AUTHORIZED_USAGE,
            )
        }

        /**
         * Activity 销毁时**真复位**通道（CMP-17）：interrupt + join([SingleDaemonSlot.DEFAULT_JOIN_MS])
         * 收口轮询线程，并把槽里的 `host`/`view` 清空 —— 旧 Activity 不再被进程级静态引用钉住。
         *
         * 幂等；`stop()` 之后 [running] = false，下一次 [startOnce] 起的是新一代。
         *
         * TODO(verify)：真机上「设置页改字号 → 返回」触发 `recreate()` 之后，
         * `dumpsys activity activities | grep com.eratw.port` 的 Activity 实例数应恒为 1
         * （JVM 单测只证明槽的代次与线程收口；实例数需要装置）。
         *
         * @return true = 线程已经死了（含「本来就没起过」）；false = 1 s 内没死（线程是 daemon，
         *   随进程退出；那时宿主要么在销毁、要么已经换 Activity，不留活引用才是硬要求）
         */
        fun stop(): Boolean = slot.stop()

        /**
         * 判词 9 §2.4 取证行的**唯一出题处**（纯函数：档位、授权位、两个时刻全是入参 ⇒ JVM 灌数可测）。
         * 启动日志（startChannel）与诊断报告共用这两行，不许在别处再抄一份措辞。
         */
        internal fun authorizationReportLines(
            readOnlySession: Boolean,
            enabled: Boolean,
            authoredAtMillis: Long,
            nowMillis: Long,
        ): List<String> {
            val tier = if (readOnlySession) "玩家授权只读档" else "debug 包全功能档"
            val moment = if (authoredAtMillis > 0L) {
                val secs = (nowMillis - authoredAtMillis) / MILLIS_PER_SECOND
                "授权位最后拨动于 " + formatAuthoredAt(authoredAtMillis) + "（距今 " + secs + " 秒）"
            } else {
                "授权位从未拨动过"
            }
            val scope = "只读/观测命令 " + READONLY_COMMANDS.size + " 条；写侧/改档/输入注入 " +
                WRITE_COMMANDS.size + " 条一律拒绝"
            return listOf(
                "诊断通道档位：" + tier + "；玩家授权位=" + enabled + "；" + moment,
                "授权档放开面：" + scope + "；唯一落盘=files/" + VARSDUMP_REL +
                    "（app 私有外部目录，其它 app 读不到，不自动上传）",
            )
        }

        /**
         * 报告接线版（**组长已接线** 2026-11-26：ErrorReportCollector.kt 两条路的 extraSectionLines 末项各拼一次本函数）
         * 。取值全走
         * UiPrefs 单路，与 ErrorReportCollector.kt:660 / :728 在报告线程上调
         * FrameTraceChannel.reportLines(FrameTraceChannel.enabled(context)) 的既有口径同形。
         * 建议调用点：collectLightweight 的 extraSectionLines（:660 那一项之后）与 collectFull 的
         * 「逐帧诊断」段（:728 那一项之后），拼在诊断报告正文「诊断现场」之前。
         */
        fun authorizationReportLines(context: Context): List<String> = authorizationReportLines(
            readOnlySession = true,
            enabled = runCatching { UiPrefs.playerDiagnosticsEnabled(context) }.getOrDefault(false),
            authoredAtMillis = runCatching { UiPrefs.playerDiagnosticsAuthoredAt(context) }.getOrDefault(0L),
            nowMillis = System.currentTimeMillis(),
        )

        private const val USAGE = """可用命令:
  state            host 状态
  rev              console.revision + View 计数
  gen              调试通道代次 + 存活 eratw-* 线程（CMP-17 判据）
  stats            重绘计数明细
  view             当前度量（行/列/字号/行高/页行数/日志行数）
  turnlog          逐页翻页耗时（提交 → 下一等待点，最近 64 页）＋末尾一行 n/p50/p95/max 分位摘要
  inputtrail       输入四层流水差分（received / dropped(gate) / enq / con + 两类丢弃 + 积压 + 当前等待点）
  loadtrace        本次装载逐段读数（段名/已用堆/毫秒）
  lockwatch [on|off|readout|reset|status]
                   锁持有/等待采样器（缺省关；readout 一行一个「挂载点+线程」；release 要玩家在设置页授权，且授权档只放开这一类只读命令）
  modgate          Mod 总闸 / 本局会话 / 安装计数 / 存档侧车（与设置页诊断卡同源）
  sample <ms>      VM 线程穷采样（聚合叶子帧 top 25，默认 3000）
  img <资源名>     试取图（宽高+命中文件）
  bg               背景层列表
  lines [n]        console 末 n 行
  chips            当前页可点按钮（引擎按钮层枚举，含非数字按钮）
  hits             命中表行/列几何（每行 y/行高/列区间，与截图对拍；含空行）
  frames [on|off|n] 逐帧诊断包（缺省关；on 开始记、off 停止、n 读最近 n 帧的版面事实＋判定）
  where            游戏现场浓缩读数（等待面/revision/末行/栈顶/本页与上一页函数流水）
  dating           约会中／路人子现场读数（MAIN_MAP 对照 + 路人子两半闸，只读）
  var NAME[:i[:j]] 读单变量（int|str 双槽；名归一大写）
  vars <前缀>      枚举变量名（系统面+用户 #DIM 面，上限200）
  callstack        ERB 调用栈（#0 最内）+ VM 线程 Java 栈顶
  feed v1,v2,...   批量入队输入（调试后门：允许先入队）
  dumpvars [n]     全量变量 dump → varsdump.txt（每变量前 n 个下标）
  trace <FN|*> [cap] / off / (空=dump)  语句轨迹环（StatementProbe 接线）
  break FN[:行] / off / (空=看命中)     软断点：命中快照+不自动应答
  set <名>[i,j]=<值> 写变量（old→new 回读 + audit.jsonl；等待点才执行）
  cheat money|day|raw <...>          状态注入配方（money N / day N / raw 名[i]=值）
  snapshot [--out 路径]              全状态快照 → snapshot.dbg + sidecar json（slot 99）
  restore <路径>                     还原快照（LOADDATA 语义：下次推进走读档链）
  log --tags event,rng,var [--watch 名单] [--max N]
                                     黑匣子事件流（三观察口；log 落盘 / log off 摘除）
  caps             能力声明（schema=1 caps=read,write,snapshot,events）
  events [n]       常开事件环尾 n 行（缺省 50）+ 环统计与落盘路径/字节
  submit <文本>    提交输入
  tap <x>,<y>      模拟点击"""
    }
}

/**
 * 「本页函数流水」的 app 侧接线 + 行格式（`obs-pagefuncs` 的消费端，本路唯一的纯逻辑层）。
 *
 * core 侧 [PageFunctionTrail] 只负责**记录**（进帧环、页边界清零、读侧解析名字），
 * 它自己不知道被谁挂上；本对象补齐三件 app 侧的事：
 * 1. [attachSink] / [attachVm]：把同一个实例挂到 `QueueInputSink.pageTrail` 与
 *    `VirtualMachine.pageTrail` 两处（缺省 null = 零行为，关不记由挂载点决定）。
 * 2. [trailText]：读数空串 → `-`。**只把「这一页没有进帧」写成人能看的占位**；
 *    名字解析失败/下标越界仍由 core 的 `readout()` 降级成 `#<下标>`，不许在这里吞成空。
 * 3. [longTurnLine]：`longturn` 钉行的唯一拼装口（页中压力检查点与页尾交付两处共用，
 *    格式只此一份）。
 *
 * ## 为什么 attachVm 只能反射
 *
 * `ProcessRuntime.vm` 是 private（`ProcessRuntime.kt:298`），core 没开公共取用面，而本路
 * 禁改 `core-vm`。反射在这里不是新发明：`callstack` / `ErrorReportCollector.vmStackLines` /
 * `longTurnFrame` 三处既有调用点读的都是同一个 private 字段（判词见那些 KDoc）。
 * 反射失败（字段改名）只让流水**挂不上**（读数退化成 `#<下标>` 与 `-`），不改任何语义；
 * 字段名由 `PageTrailWireTest` 钉住，改名在桌面单测就红。
 *
 * ## 线程口径（读侧两类，core 侧 `PageFunctionTrail` KDoc 同源）
 *
 * - **同线程直读**（逐字确定）：页中压力检查点与页尾 `onTurnReady` 都在 **VM 线程**上跑，
 *   读到的就是此刻/刚结束那一页的流水，无可见性问题。
 * - **跨线程读**（尽力而为）：DevTools `where` 跑在 daemon 线程，可能撞上环绕覆盖或页边界
 *   清零 ⇒ 个别槽新旧混合、长度 ±1。只作人工定位，**绝不参与任何判定**。
 */
internal object PageTrailWire {

    /**
     * `VirtualMachine` 上那个挂载点的字段名（`Vm.kt` 的 `var pageTrail: PageFunctionTrail?`）。
     *
     * 单测钉住这个名字还在这两个类上；改名 ⇒ 桌面单测红，而不是真机静默降级。
     */
    const val VM_FIELD = "pageTrail"

    /**
     * 挂到输入队列（页边界清零点在 `QueueInputSink` 的两个等待点结算段）。
     *
     * 必须与 [attachVm] 传**同一个实例**：清零点在 sink 侧、记录点在 VM 侧，
     * 两个实例会让「本页流水」被清零却仍在累加（读数变成跨页混合）。
     */
    fun attachSink(sink: QueueInputSink, trail: PageFunctionTrail) {
        sink.pageTrail = trail
    }

    /**
     * 挂到 VM（进帧记录点：`Vm.run` 入口帧 / `callInto` / `jumpInto` 三处）。
     *
     * @return true = 真的挂上了；false = 反射不通（字段改名 / 未装配），调用方只需照实记日志。
     */
    fun attachVm(runtime: ProcessRuntime, trail: PageFunctionTrail): Boolean = runCatching {
        val vmField = ProcessRuntime::class.java.getDeclaredField("vm").apply { isAccessible = true }
        val vm = vmField.get(runtime) ?: return@runCatching false
        val f = vm.javaClass.getDeclaredField(VM_FIELD).apply { isAccessible = true }
        f.set(vm, trail)
        true
    }.getOrDefault(false)

    /** 流水读数的人读形态：空串（本页没有进帧）写成 `-`；`#<下标>` 这类降级原样保留。 */
    fun trailText(readout: String): String = readout.ifEmpty { "-" }

    /**
     * `longturn` 钉行（**格式只此一份**）：
     * `longturn <ms>ms @<栈顶> [heap=<used>/<max>] | page: <最旧→最新>`。
     *
     * 页中检查点带 `heap=`（那一路本来就要读堆水位），页尾交付不带（保持既有 `turn`
     * 那一套读数之外**只多一段**流水）。`longturn` 前缀逐字保留：报告里「长页」仍按
     * 这一行认，新增的只是行尾的 `| page:` 段。
     *
     * @param pageMs 这一页已经跑了多少毫秒
     * @param top 最内层 ERB 函数名（取不到调用方给 `-`，见 `GameHost.longTurnFrame`）
     * @param pageReadout `PageFunctionTrail.readout()` 原文（空 = 本页无进帧 ⇒ `-`）
     * @param heap 形如 `480MiB/512MiB`；null = 不写这一段
     */
    fun longTurnLine(pageMs: Long, top: String, pageReadout: String, heap: String? = null): String {
        val heapPart = if (heap == null) "" else " heap=$heap"
        return "longturn ${pageMs}ms @$top$heapPart | page: ${trailText(pageReadout)}"
    }
}

// ---------------------------------------------------------------------------
// 判词 9（PDIAG1）：玩家授权档的判定与命令分区表。
//
// 整块留在文件顶层而不是塞进 DevToolsHost 类体：detekt 只挂根工程、不改阈值、不加
// @Suppress（同 UiPrefs.kt 里 ModPackageOrderStore 那段留档的口径 —— 类体逼近 LargeClass
// 阈值时新逻辑落在外面，不靠放宽闸门过关）。
// ---------------------------------------------------------------------------

/**
 * dev 目录与「授权档唯一落盘面」的路径单源（组长 m00703①）：`dir` 初始化、dumpvars 落点、
 * 启动日志里的 cmd.txt 全路径、取证行四处置物都从这里取，漂一名即判红
 * （钉法见 `DevToolsPlayerDiagnosticsGateTest` 的「授权档落盘不许出 dev 目录」）。
 */
internal const val DEV_DIR_REL = "eratw/dev"
internal const val VARSDUMP_NAME = "varsdump.txt"

/** 取证行要点名的相对路径（真机落点是 app 私有外部目录，其它 app 读不到、不自动上传）。 */
internal const val VARSDUMP_REL = DEV_DIR_REL + "/" + VARSDUMP_NAME

/**
 * 判词 9 命令分区表的一条：**档位与说明只在登记处出现一次**（组长 m00727②）。
 *
 * 形状理由：[readonly] / [write] 两个构造口逼着登记人当场表态这条命令属不属于放开面 ⇒ 新加
 * 命令不可能「先进 when 臂、日后再补名单」，漏归类在 [DIAG_COMMANDS] 这一处就暴露；而
 * `execute` 的 when 臂与这张表逐名对齐由 `DevToolsPlayerDiagnosticsGateTest` 的源码形状闸机器核
 * （双向差集 + 25/10 计数 + 测试侧字面量清单锁表，组长 m00704 / m00727①）。
 *
 * usage 单源：只读条目的 usage 就是授权档清单 [AUTHORIZED_USAGE] 里那一行（派生，不手抄）；
 * 写侧条目的 usage 取 debug 档 USAGE 那一行，只喂「表覆盖了 debug 清单」那条断言。
 * USAGE 本身仍是手写字面量（debug 档清单的可读性优先，不做派生），但它与表的覆盖关系
 * 有断言兜着 —— 表里少一名或多一名都判红。
 *
 * 落盘判据（组长批复 3② / m00703②，交付按这条核）：只读臂里唯一会写文件的是 `dumpvars`，
 * 落点 = [VARSDUMP_REL]（类体 `dir` + [VARSDUMP_NAME]）；`sample` / `state` / `gen` / `rev` 纯内存
 * 零落盘。`snapshot` / `restore` 用的 `snapSandbox()` 在 cacheDir 下，两条都在写侧必拒面 ⇒
 * 授权档碰不到。不新增导出目录，不自动上传任何读数。
 */
internal class DiagCommand(
    val name: String,
    val readonly: Boolean,
    val usage: String,
)

/** 登记一条**授权档放开**的只读/观测命令（[AUTHORIZED_USAGE] 里会出现它的 usage 行）。 */
private fun readonly(name: String, usage: String) = DiagCommand(name, true, usage)

/** 登记一条**授权档必拒**的写侧 / 改档 / 输入注入命令。 */
private fun write(name: String, usage: String) = DiagCommand(name, false, usage)

/** 分区表本体：35 条，登记顺序照 debug 档 USAGE 清单。两张名单与授权档清单全从这里派生。 */
internal val DIAG_COMMANDS: List<DiagCommand> = listOf(
    readonly("state", "  state            host 状态"),
    readonly("rev", "  rev              console.revision + View 计数"),
    readonly("gen", "  gen              调试通道代次 + 存活 eratw-* 线程"),
    readonly("stats", "  stats            重绘计数明细"),
    readonly("view", "  view             当前度量（行/列/字号/行高/页行数/日志行数）"),
    readonly("turnlog", "  turnlog          逐页翻页耗时（最近 64 页 + 分位摘要）"),
    readonly("inputtrail", "  inputtrail       输入四层流水差分"),
    readonly("loadtrace", "  loadtrace        本次装载逐段读数"),
    readonly("lockwatch", "  lockwatch [on|off|readout|reset|status]  锁持有/等待采样器（判词 9 给玩家打开的就是这一格）"),
    readonly("modgate", "  modgate          Mod 总闸 / 本局会话 / 安装计数 / 存档侧车"),
    readonly("sample", "  sample <ms>      VM 线程穷采样"),
    readonly("img", "  img <资源名>     试取图（宽高 + 命中文件）"),
    readonly("bg", "  bg               背景层列表"),
    readonly("lines", "  lines [n]        console 末 n 行"),
    readonly("chips", "  chips            当前页可点按钮"),
    readonly("hits", "  hits             命中表行/列几何"),
    readonly("frames", "  frames [on|off|n] 逐帧诊断包（内存环形缓冲）"),
    readonly("where", "  where            游戏现场浓缩读数"),
    readonly("dating", "  dating           约会中／路人子现场读数"),
    readonly("var", "  var NAME[:i[:j]] 读单变量（int|str 双槽；名归一大写）"),
    readonly("vars", "  vars <前缀>      枚举变量名"),
    readonly("callstack", "  callstack        ERB 调用栈（#0 最内）+ VM 线程 Java 栈顶"),
    write("feed", "  feed v1,v2,...   批量入队输入（调试后门：允许先入队）"),
    readonly("dumpvars", "  dumpvars [n]     全量变量 dump 到本机 dev 目录的 varsdump.txt"),
    write("trace", "  trace <FN|*> [cap] / off / (空=dump)  语句轨迹环（StatementProbe 接线）"),
    write("break", "  break FN[:行] / off / (空=看命中)     软断点：命中快照+不自动应答"),
    write("set", "  set <名>[i,j]=<值> 写变量（old→new 回读 + audit.jsonl；等待点才执行）"),
    write("cheat", "  cheat money|day|raw <...>          状态注入配方（money N / day N / raw 名[i]=值）"),
    write("snapshot", "  snapshot [--out 路径]              全状态快照 → snapshot.dbg + sidecar json（slot 99）"),
    write("restore", "  restore <路径>                     还原快照（LOADDATA 语义：下次推进走读档链）"),
    write("log", "  log --tags event,rng,var [--watch 名单] [--max N]"),
    readonly("caps", "  caps             能力声明（schema=1 caps=read）"),
    readonly("events", "  events [n]       常开事件环尾 n 行"),
    write("submit", "  submit <文本>    提交输入"),
    write("tap", "  tap <x>,<y>      模拟点击"),
)

/** 授权档**放开**的名字（只读/观测面）。派生量，不许手抄第二份。 */
internal val READONLY_COMMANDS: Set<String> = DIAG_COMMANDS.filter { it.readonly }.map { it.name }.toSet()

/**
 * 授权档**必拒**的名字 = 输入注入（feed / tap / submit）、改运行时状态或执行行为
 * （set / cheat / trace / break / log）、写或还原存档（snapshot / restore）。派生量。
 *
 * 口径：玩家「授权打开诊断」只意味着允许壳层采集**只读读数**，不意味着允许远程改档。
 * `snapshot` / `restore` / `set` / `cheat` 走 DevState 的写档面（`DevState.kt:167` snapshot.dbg、
 * `:439` dbglog.txt、`:621` audit.jsonl），`feed` / `submit` / `tap` 把输入直接投进
 * QueueInputSink 的真投喂口（等价玩家手指），`trace` / `break` 挂语句探针改变应答行为 ——
 * 全都不进授权档。
 */
internal val WRITE_COMMANDS: Set<String> = DIAG_COMMANDS.filter { !it.readonly }.map { it.name }.toSet()
/**
 * 授权档对一条命令名的裁决：null = 放行，非 null = 拒绝理由。
 *
 * **fail-closed**：既不在只读名单也不在写侧名单的名字（含后来人新加却忘了归类的 when 分支）
 * 一律拒绝 ⇒ 「没登记」永远不等于「放开」。三条臂各有行为用例逐名点它。
 */
internal fun playerDiagRefusal(name: String): String? = when {
    name in READONLY_COMMANDS -> null
    name in WRITE_COMMANDS -> "写侧/改档/输入注入命令，授权档不放开（需要 debug 包装机）"
    else -> "未分类命令：既不在只读名单也不在写侧名单，按缺省拒绝；新加命令必须先归进两张名单之一"
}

/**
 * 授权档的**分发闸**（判词 9）：`DevToolsHost.execute` 里唯一的判定出口。
 *
 * 为什么把「判定」与「是否分发」绑进同一个函数，而不是让 execute 自己写两个 if：
 * 于是 JVM 单测能证明**拒绝时分发臂一次都没跑**（proceed 未被调用 ⇒ 游戏状态、存档、输入
 * 通道都不可能被动过），而不是只证明「名单里有这个名字」。debug 档（authorizedOnly=false）
 * 直接走 proceed，一条指令都不多问。[onRefuse] 只给宿主写日志（取证面按 AGENTS §6.1 不进三语文案表）。
 */
internal fun dispatchGuarded(
    authorizedOnly: Boolean,
    name: String,
    onRefuse: (String) -> Unit,
    proceed: () -> String,
): String {
    val why = if (authorizedOnly) playerDiagRefusal(name) else null
    if (why == null) return proceed()
    val refusal = refusalText(name, why)
    onRefuse(refusal)
    return refusal
}

/** 拒绝回执正文（进 out.txt，玩家与作者都能直接看懂该换哪条命令）。 */
internal fun refusalText(name: String, why: String): String =
    "授权档（玩家授权只读诊断）不执行这条命令：$name —— $why\n" +
        "授权档放开的观测面命令：${READONLY_COMMANDS.joinToString("/")}"

/**
 * 授权档用法清单：**由分区表派生**（组长 m00727②），不再有手抄的第二份。
 * 启动日志与拒绝回执共用；日志/取证面按 AGENTS §6.1 不进三语 UI 文案表。
 * 末行「不放开」的名字也是派生量 ⇒ 表里摘一名，这里跟着少一名，取证面不会虚报覆盖面。
 */
internal val AUTHORIZED_USAGE: String = buildString {
    appendLine("可用命令（玩家授权档 = 只读观测）:")
    for (c in DIAG_COMMANDS) if (c.readonly) appendLine(c.usage)
    append("不放开（需要 debug 包装机）: ")
    append(WRITE_COMMANDS.joinToString(" "))
}

/** 取证行「距今 N 秒」的换算因子（裸 1000 会被 detekt MagicNumber 判红）。 */
private const val MILLIS_PER_SECOND = 1_000L

/** 取证时刻的格式（与 ErrorReportCollector.kt:650 的报告时间戳口径同形）。 */
internal fun formatAuthoredAt(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
/**
 * DevTools 的 `lockwatch` 子命令（OBS2-L2，2026-11-05；契约 §2 L2 第 4 项）——
 * [com.eratw.port.core.diag.LockWatch] 的**真机出口**。
 *
 * ## 为什么这一格要单独成对象
 *
 * `LockWatch` 的开关只吃 JVM 性质 `eratw.lockwatch`（`core/diag/LockWatch.kt` 的
 * `PROPERTY_ENABLED`），桌面有 `-Plockwatch=true`（`core-vm/build.gradle.kts:131` 透传），
 * **真机上没有人能设性质** ⇒ 采样器在设备上永远关着、永远读不到。本对象把四条出口接在
 * 既有命令通道上：`on` → [LockWatch.attach]、`off` → [LockWatch.detach]、
 * `readout` → [LockWatch.readout]、`reset` → [LockWatch.reset]。
 *
 * 三条硬口径（逐条有单测：`app/src/test/kotlin/com/eratw/port/android/DevToolsLockWatchCommandTest.kt`）：
 * 1. **缺省关**：装配期不自动挂载（本对象没有任何 init / 其他调用点会 attach），
 *    打开只由这条命令发生；
 * 2. **纯文本返回、绝不抛**：`readout` 本体已禁抛（`LockWatch` 类 KDoc「读侧禁抛降级」），
 *    这里再兜一层 `try/catch` —— 命令面抛出会让轮询线程那一轮只剩 `EXC ...`，
 *    而观测通道自己炸掉是最不该发生的事；
 * 3. **零语义位移**：只读写 `LockWatch` 自己的定长表，不碰任何锁、不碰 VM 状态。
 *
 * ## 能不能在 release 包里打到（判词 9 / PDIAG1 改口；契约 §2 L2 第 5 项那一格的账在这里还掉）
 *
 * **能，但要玩家显式授权**：本命令挂在 [DevToolsHost] 的轮询通道上，而通道起不起、起哪一档只有一个
 * 判定入口 `DevToolsHost.diagnosticsAllowed(flags, prefsEnabled)` —— debug 包照旧自动起（全功能档）；
 * release 包只有玩家在设置页 Mod 档把「玩家授权诊断」打开后才起，且起的是**只读档**：`lockwatch` 在
 * 放开的观测面里（[READONLY_COMMANDS]），而 `feed`/`tap`/`submit`/`set`/`cheat`/`snapshot`/`restore`/
 * `log`/`trace`/`break` 这些会改游戏状态、写档或注入输入的（[WRITE_COMMANDS]）由 `execute` 直接拒，
 * 未分类的名字按 fail-closed 一并拒。缺省仍关 ⇒ `startOnce` 在建线程之前就 return，`cmd.txt` 没人读。
 * 开启要等下一次装载生效（判定挂在装载尾段 `GameHost.kt:1050`），关掉当场 `DevToolsHost.stop()`。
 *
 * 判词 9 之前这一格的答案是「**打不到**」：通道启动点第一件事就是非 debuggable 直接 return，采样器
 * 对玩家 / 正式作者不可达，那一格当时记在 OBS2-L2 交付的「需组长接线」里。
 */
internal object LockWatchCommand {

    /** 子命令名（[DevToolsHost.execute] 的 `when` 分支与单测都引这一个常数）。 */
    const val NAME = "lockwatch"

    /** 用法行：不带参、参不认识、异常兜底都要把这一行带出去。 */
    const val HELP = "用法: lockwatch on|off|readout|reset|status"

    /**
     * 执行一条 `lockwatch` 子命令，返回**纯文本**（多行以换行分隔，直接进 `out.txt`）。
     *
     * @param arg [DevToolsHost.execute] 切掉命令名之后的余部（可为空串）。
     */
    fun execute(arg: String): String = try {
        when (arg.trim().lowercase(Locale.ROOT)) {
            "on" -> {
                LockWatch.attach()
                "lockwatch on：挂载点已打开（全进程一档，UI/VM/装载线程同时开始记；" +
                    "off 摘除、readout 取数、reset 清表）\n" +
                    LockWatch.readout()
            }
            "off" -> {
                LockWatch.detach()
                "lockwatch off：挂载点回到零行为（一次 volatile 布尔读，" +
                    "不取时钟、不进监视器、零分配）\n" +
                    "表内读数保留到下一次 reset\n" +
                    LockWatch.readout()
            }
            "readout" -> LockWatch.readout()
            "reset" -> {
                LockWatch.reset()
                "lockwatch reset：读数已清（挂载态=" +
                    (if (LockWatch.enabled) "on" else "off") + "）"
            }
            "", "status", "help", "?" -> HELP + "\n" + LockWatch.readout()
            else -> "$HELP（收到: $arg）\n" + LockWatch.readout()
        }
    } catch (t: Throwable) {
        // 命令面绝不抛（口径照 LockWatch.readout 的读侧降级）。
        "lockwatch 不可用：${t.javaClass.simpleName}: ${t.message}\n$HELP"
    }
}
