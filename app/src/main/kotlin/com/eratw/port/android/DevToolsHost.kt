package com.eratw.port.android

import android.util.Log
import android.content.pm.ApplicationInfo
import com.eratw.port.core.host.SingleDaemonSlot
import com.eratw.port.render.canvas.CanvasConsoleView
import java.io.File

/**
 * 真机实时调试通道（**仅 debug 包**）：轮询命令文件 → 宿主侧执行 → 结果回写。
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
 * - `submit <文本>` — 提交输入（驱动选单 / 推进对话）
 * - `tap <x> <y>` — 模拟点击命中（等价手指点屏幕上的按钮行）
 *
 * ## 线程与安全
 *
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
) {

    private val dir: File =
        File(view.context.getExternalFilesDir(null), "eratw/dev").apply { mkdirs() }
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
        return when (name) {
            "state" -> state()
            "gen" -> gen()
            "rev" -> "console.revision=${host.debugRevision()} | ${view.debugStats()}"
            "stats" -> view.debugStats()
            "view" -> view.debugMetrics()
            "img" -> img(arg)
            "bg" -> view.debugBackgrounds()
            "lines" -> lines(arg.toIntOrNull() ?: 10)
            "chips" -> chips()
            "hits" -> view.debugHits()
            "turnlog" -> turnlog()
            "loadtrace" -> loadtrace()
            "sample" -> sample(arg.toIntOrNull() ?: 3000)
            "var" -> varRead(arg)
            "vars" -> varsList(arg)
            "callstack" -> callstack()
            "where" -> where()
            "feed" -> feed(arg)
            "dumpvars" -> dumpVars(arg.toIntOrNull() ?: 8)
            "trace" -> traceCmd(arg)
            "break" -> breakCmd(arg)
            "set" -> DevState.setCmd(host, dir, arg)
            "cheat" -> DevState.cheatCmd(host, dir, arg)
            "snapshot" -> DevState.snapshotCmd(host, dir, snapSandbox(), arg)
            "restore" -> DevState.restoreCmd(host, dir, snapSandbox(), arg)
            "log" -> DevState.logCmd(host, dir, arg, logSlot)
            "submit" -> {
                host.submit(arg)
                "submitted: $arg"
            }
            "tap" -> {
                val parts = arg.split(',', ' ').filter { it.isNotBlank() }.map { it.toFloat() }
                val x = parts.getOrElse(0) { 0f }
                val y = parts.getOrElse(1) { 0f }
                val text = view.tap(x, y, host.acceptance())
                if (text == null) "tap($x,$y) 未命中"
                else {
                    host.submit(text)
                    "tap($x,$y) 命中并提交: $text"
                }
            }
            else -> USAGE
        }
    }

    private fun state(): String =
        "isRunning=${host.isRunning()} pending=${host.pendingRequest()} " +
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

    /** `turnlog`：逐页「提交 → 下一次进入等待点」的墙钟毫秒（最近 64 页）。 */
    private fun turnlog(): String {
        val log = host.debugTurnLog()
        return if (log.isEmpty()) "（无翻页读数：本会话还没提交过输入）" else log.joinToString("\n")
    }

    /** `loadtrace`：本次装载逐段读数（段名 + 已用堆 + 距起点毫秒），口径同 logcat `load.*`。 */
    private fun loadtrace(): String {
        val lines = host.debugLoadTraceLines()
        return if (lines.isEmpty()) "（无装载读数：还没进入装载链）" else lines.joinToString("\n")
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
     * 反射口径与 GameBootSmoke 一致（`ProcessRuntime.vm` → `funcStack`/`depth`，
     * 帧表映射 `program.functions[i].name`）；字段名变更时优雅降级。
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
            val fns = session.program.functions
            (d - 1 downTo 0).mapIndexed { k, i -> "#$k ${fns.getOrNull(fs[i])?.name ?: "?"}" }
        }.getOrElse { return "读数不可用：${it.javaClass.simpleName}（字段名变更？）" }
        val javaTop = host.debugVmThread()?.stackTrace?.take(5)
            ?.joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
            ?: "（VM 线程未启动）"
        return racePrefix() + (frames?.joinToString("\n") ?: "（无帧：VM 未在执行）") + "\n--- java ---\n$javaTop"
    }

    /** `where`：游戏现在在干嘛的浓缩读数（等待面 + revision + 末行 + 调用栈顶 3 帧）。 */
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
        return racePrefix() +
            "running=${host.isRunning()} pending=$pendingText anyKey=${host.awaitingAnyKey()} " +
            "rev=${host.debugRevision()}\n末行: $lastLine\n栈顶: $top3"
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
        val out = File(dir, "varsdump.txt")
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
            "$id value=${c.value} origin=${c.origin} label=${c.label}"
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

        /** 幂等启动：只有当前代线程已经死了才会真的起新一代（Activity 重建 / 反复装载都不叠加）。 */
        fun startOnce(host: GameHost, view: CanvasConsoleView) {
            // buildFeatures.buildConfig 显式关闭（app/build.gradle.kts:227），用 debuggable 标志判定
            if (view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
            val started = slot.start(
                create = { DevToolsHost(host, view) },
                body = { it.loop() },
            )
            if (started) {
                Log.i(
                    TAG,
                    "调试通道已启动（第 ${slot.generation} 代；轮询 files/eratw/dev/cmd.txt，500ms）\n$USAGE",
                )
            }
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

        private const val USAGE = """可用命令:
  state            host 状态
  rev              console.revision + View 计数
  gen              调试通道代次 + 存活 eratw-* 线程（CMP-17 判据）
  stats            重绘计数明细
  view             当前度量（行/列/字号/行高/页行数/日志行数）
  turnlog          逐页翻页耗时（提交 → 下一等待点，最近 64 页）
  loadtrace        本次装载逐段读数（段名/已用堆/毫秒）
  sample <ms>      VM 线程穷采样（聚合叶子帧 top 25，默认 3000）
  img <资源名>     试取图（宽高+命中文件）
  bg               背景层列表
  lines [n]        console 末 n 行
  chips            当前页可点按钮（引擎按钮层枚举，含非数字按钮）
  hits             命中表行/列几何（每行 y/行高/列区间，与截图对拍；含空行）
  where            游戏现场浓缩读数（等待面/revision/末行/栈顶）
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
  submit <文本>    提交输入
  tap <x>,<y>      模拟点击"""
    }
}
