package com.eratw.port.android

import com.eratw.port.core.code.VirtualMachine
import com.eratw.port.core.err.CodeEE
import com.eratw.port.core.proc.FuncMeta
import com.eratw.port.core.proc.FunctionExitKind
import com.eratw.port.core.proc.FunctionProbe
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.ProcessRuntime
import com.eratw.port.core.rand.RngProbe
import com.eratw.port.core.rand.Sfmt19937
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.vars.VarWriteProbe
import com.eratw.port.core.vars.VariableStore
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime

/**
 * DevTools 五条**写侧/观测侧**命令（DBG5）的纯逻辑层：`set` / `cheat`（状态注入）、
 * `snapshot` / `restore`（调试快照）、`log`（结构化事件流黑匣子）。语义与桌面 vmdbg 的
 * [CmdSet]/[CmdSnap]/[CmdLog]（tools/vmdbg，DBG4 落地、验收全绿）逐字对齐，但 androidTest
 * 与主源码都不可 import tools 模块，故照抄实现；两侧行为差异见各命令 KDoc 的「与桌面差异」节。
 *
 * ## 两层结构
 *
 * - **cmd 层**（[setCmd] 等，DevToolsHost 的 when 分发调用）：入参 [GameHost] —— 先取
 *   [GameHost.debugSession]（未装载给可读文案），再过**写安全闸**（[gateDenial]），执行完
 *   用 [racePrefix] 给结果补竞态前缀（与 DevToolsHost 读侧命令同款约定：等待点读数精确）。
 * - **pure 层**（[setPure] 等，internal）：入参 [GameSession] + 参数串 → 返回 [String]，
 *   零 android import（文件路径走 java.io/nio）——androidTest 的 [DevStateSelfTest] 直测这一层，
 *   不需要构造 GameHost（那需要完整 Android 装载链）。
 *
 * ## 写安全闸（规格 3.1，五命令共用）
 *
 * VM 线程执行中时 store 不是线程安全的；DevTools daemon 线程执行命令、VM 线程 park 在
 * waitInput——park 期间 store 无人碰，操作安全。判定复用 [GameHost.isRunning] /
 * [GameHost.pendingRequest] / [GameHost.awaitingAnyKey]（都 public）：
 * 仅当「VM 未跑 或 pendingRequest != null 或 awaitingAnyKey」时执行；否则返回
 * [DENY_TEXT]。检查与执行之间仍有 µs 级竞态窗口（玩家恰在此刻点击推进），与原版
 * 事件态翻转窗口同构（GameHost.submitInternal KDoc 同款口径），不加密锁——等待点重试即可。
 *
 * ## 观察口共存（规格 §4）
 *
 * 四类探针分属 [com.eratw.port.core.proc.ProcessRuntime] 的四个独立槽：
 * `statementProbe`（DevProbe：trace/break，**本文件不碰**）、`functionProbe`（log）、
 * `store.writeProbe`（log）、`store.randProvider`（Sfmt19937）的 `drawProbe`（log）。
 * 后三槽由 [logPure] 挂/摘，互不顶替 DevProbe。
 *
 * TODO(verify)：桌面 `restore` 落完即调 `SaveLoadHandoff.reset()` 消费 Unwind 信号
 * （装配期还原、没有脚本帧在飞）；真机 live 会话的 restore **不** reset（规格 3.2 裁定）：
 * 下一次提交输入推进时当前 run 按原版整栈丢弃、引擎走 load-end 链，与玩家「读档」同构。
 * 「换状态续跑当前帧」的 CLI 形态不上真机——live 会话帧局部变量会与全局态分叉。
 */
object DevState {

    /** 写安全闸的拒绝文案（自测锁文案用）。 */
    internal const val DENY_TEXT = "拒绝：VM 运行中（等待输入后再试）"

    /**
     * 快照运输槽：saveData/loadData 的 no 参数（文件名 save99.sav 的 %02d 口径）。
     * 选 99 = 玩家 UI 存档清单（0..9 一族）之外；与桌面 CmdSnap.DEBUG_SLOT 同值同义，
     * 跨平台还原（真机 snapshot → adb pull → 桌面 vmdbg restore）两端指的是同一个槽。
     */
    internal const val DEBUG_SLOT = 99

    /** sidecar 的 console 尾随行数（与桌面 CmdSnap.CONSOLE_TAIL_LINES 同值）。 */
    internal const val CONSOLE_TAIL_LINES = 200

    /** log 可用 tag 集合（规格 §5：event / rng / var；与桌面 CmdLog 同集）。 */
    private val KNOWN_TAGS = setOf("event", "rng", "var")

    /** log 缺省截断条数（与桌面 CmdLog.DEFAULT_MAX_EVENTS 同值）。 */
    internal const val DEFAULT_MAX_EVENTS = 1_000_000L

    // ------------------------------------------------------------------
    // 闸与竞态前缀（GameHost 三读数的纯函数；自测直接测布尔形态）
    // ------------------------------------------------------------------

    /** 写安全闸：null = 放行；非 null = [DENY_TEXT]（直接作为命令结果返回）。 */
    fun gateDenial(host: GameHost): String? =
        gateDenial(host.isRunning(), host.pendingRequest() != null, host.awaitingAnyKey())

    /** [gateDenial] 的布尔形态（自测直测；条件与 [racePrefix] 完全同构）。 */
    internal fun gateDenial(isRunning: Boolean, hasPending: Boolean, awaitingAnyKey: Boolean): String? =
        if (isRunning && !hasPending && !awaitingAnyKey) DENY_TEXT else null

    /** 读数竞态前缀：VM 正在跑时结果可能正被改写，带前缀提示（DevToolsHost 读侧命令同款）。 */
    fun racePrefix(host: GameHost): String =
        racePrefix(host.isRunning(), host.pendingRequest() != null, host.awaitingAnyKey())

    /** [racePrefix] 的布尔形态；与 DevToolsHost.racePrefix 原实现逐字同构。 */
    internal fun racePrefix(isRunning: Boolean, hasPending: Boolean, awaitingAnyKey: Boolean): String =
        if (isRunning && !hasPending && !awaitingAnyKey) "[RUNNING竞态] " else ""

    // ------------------------------------------------------------------
    // cmd 层（DevToolsHost 分发入口：session 解析 + 闸 + 前缀）
    // ------------------------------------------------------------------

    /** `set <NAME>[i,j] = <值>` 的 DevToolsHost 入口。 */
    fun setCmd(host: GameHost, devDir: File, arg: String): String =
        gated(host) { setPure(it, devDir, "set", arg) }

    /** `cheat money|day|raw ...` 的 DevToolsHost 入口。 */
    fun cheatCmd(host: GameHost, devDir: File, arg: String): String =
        gated(host) { cheatPure(it, devDir, arg) }

    /** `snapshot [--out <路径>]` 的 DevToolsHost 入口；[sandboxDir] = 快照工作沙箱（cacheDir 下）。 */
    fun snapshotCmd(host: GameHost, devDir: File, sandboxDir: File, arg: String): String =
        gated(host) { snapshotPure(it, devDir, sandboxDir, arg) }

    /** `restore <路径>` 的 DevToolsHost 入口；[sandboxDir] = 快照工作沙箱（cacheDir 下）。 */
    fun restoreCmd(host: GameHost, devDir: File, sandboxDir: File, arg: String): String =
        gated(host) { restorePure(it, devDir, sandboxDir, arg) }

    /** `log --tags ... [--watch ...] [--max N]` / `log`（落盘）/ `log off` 的 DevToolsHost 入口。 */
    internal fun logCmd(host: GameHost, devDir: File, arg: String, slot: DevLogSlot): String =
        gated(host) { logPure(it, devDir, arg, slot) }

    /**
     * 闸 + 未装载检查的共用壳：先解析会话、过闸，执行后**再**取竞态前缀
     * （执行期间 VM 可能被玩家唤醒，前缀反映的是返回时刻的读数状态）。
     */
    private inline fun gated(host: GameHost, body: (GameSession) -> String): String {
        val session = host.debugSession() ?: return "（未装载）"
        gateDenial(host)?.let { return it }
        val result = body(session)
        return racePrefix(host) + result
    }

    // ------------------------------------------------------------------
    // snapshot / restore（语义照抄桌面 CmdSnap；落点与还原时机按规格 3.2/3.3 有真机特化）
    // ------------------------------------------------------------------

    /**
     * `snapshot` 纯逻辑：当前会话完整状态落盘，独立于游戏存档。
     *
     * - **变量面复用引擎存档序列化，不发明第二种**：照抄桌面 CmdSnap —— 构造
     *   [FileSystemOps]（写侧 = [RootedSaveFileIo] 指向 [sandboxDir] 沙箱，**不**落在
     *   会话自己的存档根上，真机玩家档零污染）走 saveData 全链（SaveCapture.normal +
     *   SaveCodec.write + emData 扩展段），字节 = 游戏自己的 save 格式；槽 = [DEBUG_SLOT]
     *   （99，玩家 UI 档清单 0..9 之外）。随后把沙箱槽字节拷出到 `--out`（缺省
     *   `<devDir>/snapshot.dbg`，外置目录 adb 直接 pull）。
     * - **sidecar**（主文件名 `.dbg` 结尾 → 加 `.json`，否则加 `.dbg.json`）：RNG 625 元素
     *   （[com.eratw.port.core.vars.VariableStore.randProvider] 的 state 导出）、console 尾
     *   200 行、funcStack/depth 反射快照（**只记录不还原**：LOADDATA 语义就是整栈丢弃）、
     *   randSeed（真机 live 会话种子是时钟、无处读取，记 -1；桌面 restore 不消费此字段）、
     *   commit（app 侧 buildConfig 显式关闭、无构建戳可读，记空串——构建戳注入归组长接线）、
     *   墙钟 ISO-8601。
     */
    internal fun snapshotPure(session: GameSession, devDir: File, sandboxDir: File, arg: String): String {
        val toks = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
        var outPath: File? = null
        var i = 0
        while (i < toks.size) {
            when (toks[i]) {
                "--out" -> {
                    val raw = toks.getOrNull(i + 1) ?: return "--out 需要路径实参"
                    outPath = resolveDevPath(devDir, raw)
                    i++
                }
                else -> return "未知 snapshot 参数: ${toks[i]}（用法: snapshot [--out <路径>]）"
            }
            i++
        }
        val main = outPath ?: File(devDir, "snapshot.dbg")
        return doSnapshot(session, sandboxDir, main)
    }

    /** 快照主体（参数已解析；[main] 为落盘主文件）。 */
    private fun doSnapshot(session: GameSession, sandboxDir: File, main: File): String {
        val sb = freshSandbox(sandboxDir) ?: return "快照沙箱不可用: ${sandboxDir.absolutePath}"
        val ops = FileSystemOps(session.store, RootedSaveFileIo(sb), session.saveConfig)
        val text = "dbg5 snapshot " + OffsetDateTime.now()
        // false = 引擎吞掉的失败（saveData catch Throwable → false），多半是盘问题。
        val ok = try {
            ops.saveData(DEBUG_SLOT, text)
        } catch (e: Exception) {
            return "快照抓取异常: ${e.message}"
        }
        if (!ok) return "快照抓取失败（saveData 返回 false，见引擎侧吞掉的异常/盘空间）"
        val bytes = Files.readAllBytes(sb.resolve(session.saveConfig.normalPath(DEBUG_SLOT)))
        main.parentFile?.mkdirs()
        Files.write(main.toPath(), bytes)
        val sidecar = sidecarFile(main)
        Files.write(sidecar.toPath(), buildSidecar(session, text).toByteArray(Charsets.UTF_8))
        return "status=PASS\nmain=${main.absolutePath}\nsidecar=${sidecar.absolutePath}\nslot=$DEBUG_SLOT"
    }

    /**
     * `restore` 纯逻辑：把快照字节灌回**当前 live 会话**。
     *
     * 变量面走引擎已有的 LOADDATA 语义链：快照字节拷进 [sandboxDir] 沙箱的 [DEBUG_SLOT]
     * 槽（槽路径可能带 `sav/` 前缀——USE SAV FOLDER:YES 形态，落盘前必须建父目录，
     * 2026-10-25 桌面真语料冒烟实锤的教训照抄），再 [FileSystemOps.loadData]。
     *
     * **不 reset SaveLoadHandoff（规格 3.2 裁定）**：真机 live 会话有脚本帧在飞（park 在
     * 等待点），保留信号 = 下一次提交输入推进时当前 run 按原版整栈丢弃、引擎走 load-end
     * 链——与玩家「读档」同构、行为可预测。桌面 CLI 形态的 reset（装配期还原、无帧在飞）
     * **不上真机**，见类 KDoc 的 TODO(verify)。返回文案明示这一语义。
     *
     * RNG：sidecar 的 625 元素灌回 [com.eratw.port.core.vars.VariableStore.randProvider]
     * （loadState，对拍过 C# SetRand）；sidecar 缺席 / RNG 段缺席 / 长度不符 → 跳过 RNG
     * 还原并在结果注明。funcStack 永不还原（LOADDATA 正确语义，照抄桌面口径）。
     */
    internal fun restorePure(session: GameSession, devDir: File, sandboxDir: File, arg: String): String {
        val raw = arg.trim()
        if (raw.isEmpty()) return "用法: restore <快照路径>（缺省相对 dev 目录；LOADDATA 语义）"
        val main = resolveDevPath(devDir, raw)
        if (!main.isFile) return "快照主文件不存在: ${main.absolutePath}"
        val bytes = Files.readAllBytes(main.toPath())

        val sb = freshSandbox(sandboxDir) ?: return "还原沙箱不可用: ${sandboxDir.absolutePath}"
        val slotPath = sb.resolve(session.saveConfig.normalPath(DEBUG_SLOT))
        slotPath.parent?.let { Files.createDirectories(it) }
        Files.write(slotPath, bytes)
        val ops = FileSystemOps(session.store, RootedSaveFileIo(sb), session.saveConfig)
        try {
            ops.loadData(DEBUG_SLOT)
        } catch (e: CodeEE) {
            return "快照还原被引擎拒收（LOADDATA 语义）: ${e.message}"
        }

        var rngLoaded = false
        val sidecar = sidecarFile(main)
        if (sidecar.isFile) {
            val line = String(Files.readAllBytes(sidecar.toPath()), Charsets.UTF_8)
                .lineSequence().firstOrNull() ?: ""
            val rngRaw = jsonArrayField(line, "rngState")
            if (!rngRaw.isNullOrEmpty() && rngRaw != "[]") {
                val arr = parseLongArray(rngRaw)
                val provider = session.store.randProvider
                if (provider != null && arr.size == Sfmt19937.RANDDATA_LEN) {
                    provider.loadState(arr)
                    rngLoaded = true
                }
            }
        }
        return "status=PASS\nmain=${main.absolutePath}\nlastLoadNo=${session.store.lastLoadNo}\n" +
            "rngLoaded=$rngLoaded\nfuncStack=not-restored（LOADDATA 正确语义）\n" +
            "LOADDATA 语义：下一次推进时当前 run 结束并走读档链（SaveLoadHandoff 信号保留）"
    }

    /** 沙箱清空重建（照 DbgAssembly 的「全新安装」起手：本命令独占该目录，清了不伤别人）。 */
    private fun freshSandbox(dir: File): java.nio.file.Path? = try {
        dir.deleteRecursively()
        if (!dir.mkdirs() && !dir.isDirectory) null else dir.toPath()
    } catch (_: Exception) {
        null
    }

    /** sidecar 路径：主文件以 .dbg 结尾 → 加 .json；否则加 .dbg.json（与桌面 CmdSnap 同形）。 */
    private fun sidecarFile(main: File): File {
        val s = main.absolutePath
        return File(if (s.endsWith(".dbg")) "$s.json" else "$s.dbg.json")
    }

    /**
     * sidecar JSON（手拼：rngState/consoleTail/funcStack 是数组；字段序与桌面 CmdSnap 逐字
     * 一致，桌面 restore 侧 jsonArrayField 直接可读——跨平台还原链的格式契约）。
     */
    private fun buildSidecar(session: GameSession, text: String): String {
        val store = session.store
        val console = session.console
        val rng: LongArray = try {
            store.randProvider?.state() ?: LongArray(0)
        } catch (_: CodeEE) {
            LongArray(0) // 非 SFMT 提供者：记录为空，还原侧跳过
        }
        val tail = console.snapshot().takeLast(CONSOLE_TAIL_LINES).map { console.textOf(it) }
        val (stack, depth) = reflectFuncStack(session)
        return buildString {
            append("{\"format\":1")
            append(",\"createdAt\":\"").append(jsonEscape(OffsetDateTime.now().toString())).append('"')
            append(",\"snapshotText\":\"").append(jsonEscape(text)).append('"')
            // 真机 live 会话种子是时钟（GameHost 不传 randSeed）、无处读取；桌面 restore 不消费此字段。
            append(",\"commit\":\"\"")
            append(",\"randSeed\":-1")
            append(",\"rngState\":[").append(rng.joinToString(",")).append(']')
            append(",\"consoleTail\":[")
            tail.forEachIndexed { i, l ->
                if (i > 0) append(',')
                append('"').append(jsonEscape(l)).append('"')
            }
            append("]")
            append(",\"funcStack\":[")
            stack.forEachIndexed { i, k ->
                if (i > 0) append(',')
                append('"').append(jsonEscape(k)).append('"')
            }
            append("]")
            append(",\"funcDepth\":").append(depth)
            append("}")
        }
    }

    /**
     * funcStack / depth 反射快照（**只记录**）：[ProcessRuntime] 私有 `vm` → [VirtualMachine]
     * 的私有 `funcStack`（IntArray，函数下标栈）与 `depth`。与 DevToolsHost.callstack 同一套
     * 反射口径；字段改名 / 反射不可得 → 空栈 + (-1)，不阻塞快照主流程。函数下标 → key 映射
     * 用 [GameSession.program] 的函数表。
     */
    private fun reflectFuncStack(session: GameSession): Pair<List<String>, Int> {
        val rt = session.runtime
        val vm = try {
            val f = ProcessRuntime::class.java.getDeclaredField("vm")
            f.isAccessible = true
            f.get(rt) as? VirtualMachine
        } catch (_: Throwable) {
            null
        } ?: return emptyList<String>() to -1
        val stack = try {
            val f = VirtualMachine::class.java.getDeclaredField("funcStack")
            f.isAccessible = true
            f.get(vm) as? IntArray
        } catch (_: Throwable) {
            null
        }
        val depth = try {
            val f = VirtualMachine::class.java.getDeclaredField("depth")
            f.isAccessible = true
            f.get(vm) as? Int
        } catch (_: Throwable) {
            null
        } ?: return emptyList<String>() to -1
        val arr = stack ?: return emptyList<String>() to depth
        if (depth < 0 || depth >= arr.size) return emptyList<String>() to depth
        val fns = session.program.functions
        return (0..depth).map { fns.getOrNull(arr[it])?.name ?: "#" + arr[it] } to depth
    }

    /** "[1,2,3]" → LongArray（restore 的 rngState 解析；坏形状返回空数组）。 */
    private fun parseLongArray(raw: String): LongArray {
        val body = raw.trim().removePrefix("[").removeSuffix("]").trim()
        if (body.isEmpty()) return LongArray(0)
        return body.split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
    }

    /**
     * 单行 JSON 里取数组字段的原文（含括号）：`"key":[...]`。字段缺席 → null。
     * 只服务本文件的 sidecar 自读自写（形状自己产的，不做通用 JSON 解析；与桌面 CmdSnap
     * 同形——跨平台链路两端读同一形状）。
     */
    private fun jsonArrayField(line: String, key: String): String? {
        val needle = "\"" + key + "\":["
        val start = line.indexOf(needle)
        if (start < 0) return null
        val from = start + needle.length
        val end = line.indexOf(']', from)
        if (end < 0) return null
        return line.substring(from - 1, end + 1)
    }

    /** 相对路径解析到 [devDir] 下；绝对路径（/sdcard/... 或盘符）原样。 */
    private fun resolveDevPath(devDir: File, raw: String): File {
        val f = File(raw)
        return if (f.isAbsolute) f else File(devDir, raw)
    }

    // ------------------------------------------------------------------
    // log（黑匣子事件流；三观察口接线 + 环形缓冲 + 落盘/摘除）
    // ------------------------------------------------------------------

    /**
     * `log` 纯逻辑，三形态：
     *
     * - `log --tags event,rng[,var] [--watch N1,N2] [--max N]`：挂观察口开记。三个挂点全部
     *   走 core 侧可空观察口（缺省 null = 零行为）：`event` = [ProcessRuntime.functionProbe]
     *   （函数进出）、`rng` = 会话 RNG（Sfmt19937）的 `drawProbe`（每次出号）、`var` =
     *   [VariableStore.writeProbe]（watch 名单命中的写入，入口/校验前回调，old 由记录器
     *   回读、new = 请求值）。与桌面 CmdLog 的差异：桌面只包装一次性 call（VM 线程内
     *   装拆），真机是 live 会话——挂上后持续记录，直到 `log off`；行格式
     *   `{"seq","tag","event","payload"}` 与桌面逐字一致。重复挂 log = 先摘旧的再挂新的
     *   （三个槽都是单槽，旧记录器被顶掉前先摘干净）。
     * - `log`（无参）：环形缓冲 dump 落 `<devDir>/dbglog.txt` 并回摘要（events 总数 / 环内
     *   行数 / 环形覆盖数 / 是否 --max 截断 + 末 10 行）。
     * - `log off`：三观察口全部摘除并停记（**必须提供卸载**——观察口挂着不摘会在 VM 线程
     *   持续付出回调与内存代价）。DevProbe（trace/break）的 `statementProbe` 是另一个槽，
     *   不受影响。
     */
    internal fun logPure(session: GameSession, devDir: File, arg: String, slot: DevLogSlot): String {
        val trimmed = arg.trim()
        if (trimmed.isEmpty()) return logDump(slot, devDir)
        if (trimmed == "off") return logOff(session, slot)

        val toks = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        var tags: Set<String>? = null
        var watch: List<String>? = null
        var max = DEFAULT_MAX_EVENTS
        var i = 0
        while (i < toks.size) {
            when (toks[i]) {
                "--tags" -> {
                    tags = toks.getOrNull(i + 1)?.split(',')
                        ?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
                        ?: return "--tags 需要实参（event,rng,var）"
                    i++
                }
                "--watch" -> {
                    watch = toks.getOrNull(i + 1)?.split(',')
                        ?.map { it.trim() }?.filter { it.isNotEmpty() }
                        ?: return "--watch 需要实参（NAME1,NAME2）"
                    i++
                }
                "--max" -> {
                    max = toks.getOrNull(i + 1)?.toLongOrNull() ?: return "--max 需要正整数实参"
                    if (max <= 0) return "--max 必须为正整数: $max"
                    i++
                }
                else -> return "未知 log 参数: ${toks[i]}（用法: log --tags event,rng[,var] [--watch N1,N2] [--max N] / log 落盘 / log off）"
            }
            i++
        }
        val t = tags ?: setOf("event")
        val unknown = t - KNOWN_TAGS
        if (unknown.isNotEmpty()) return "未知 --tags 值: $unknown（可用 event,rng,var）"
        if ("var" in t && watch.isNullOrEmpty()) return "--tags var 需要 --watch 名单（--watch NAME1,NAME2）"
        if ("rng" in t && session.store.randProvider !is Sfmt19937) {
            return "会话 RNG 不是 Sfmt19937，无法挂出号观察口"
        }
        // 重复挂 log：先摘旧记录器（三槽都是单槽，顶掉前不摘会漏挂半套探针）。
        detachProbes(session, slot)
        val rec = DevLogRecorder(t, watch.orEmpty(), max, session.store)
        if ("event" in t) session.runtime.functionProbe = rec
        if ("rng" in t) (session.store.randProvider as Sfmt19937).drawProbe = rec
        if ("var" in t) session.store.writeProbe = rec
        slot.recorder = rec
        return "log on tags=${t.joinToString(",")} watch=${watch.orEmpty().joinToString(",")} max=$max" +
            "（`log` 落盘 / `log off` 摘除；statementProbe 上的 trace/break 不受影响）"
    }

    /** `log`（无参）：环快照落 dbglog.txt + 摘要。 */
    private fun logDump(slot: DevLogSlot, devDir: File): String {
        val rec = slot.recorder ?: return "log 未开（用法: log --tags event,rng[,var] [--watch N1,N2] [--max N] / log off）"
        val lines = rec.snapshotLines()
        devDir.mkdirs()
        val out = File(devDir, "dbglog.txt")
        out.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n", Charsets.UTF_8)
        return "events=${rec.count}（环内 ${lines.size} 行，更早 ${rec.ringDropped} 行已被环形覆盖；--max 截断=${rec.truncated}）\n" +
            "全文 → ${out.absolutePath}\n" +
            lines.takeLast(10).joinToString("\n")
    }

    /** `log off`：三观察口全部摘除并停记。 */
    private fun logOff(session: GameSession, slot: DevLogSlot): String {
        val rec = slot.recorder ?: return "log 未开（off 无事可做）"
        detachProbes(session, slot)
        return "log off（events=${rec.count}；functionProbe/drawProbe/writeProbe 已摘除；trace/break 的 statementProbe 不受影响）"
    }

    /** 摘掉 [slot] 记录器的三面观察口（逐面只在自己还是挂者时才置 null，不误伤别人）。 */
    private fun detachProbes(session: GameSession, slot: DevLogSlot) {
        val rec = slot.recorder ?: return
        if (session.runtime.functionProbe === rec) session.runtime.functionProbe = null
        val rng = session.store.randProvider as? Sfmt19937
        if (rng?.drawProbe === rec) rng.drawProbe = null
        if (session.store.writeProbe === rec) session.store.writeProbe = null
        slot.recorder = null
    }

    // ------------------------------------------------------------------
    // set / cheat（语义照抄桌面 CmdSet：old→new 回读 + audit trail）
    // ------------------------------------------------------------------

    /**
     * `set` 纯逻辑：`<名字>[i,j,...] = <值>`，LHS/RHS 用第一个 `=` 切分（值里可以再出现
     * `=`）。值解析顺序 = 首尾成对双引号 → 字符串字面量（剥引号，不做转义还原）；
     * 能解析成 Long → 整数；其余 → 原样按字符串写（整数变量会得到可读 CodeEE 文案）。
     * 名字归一大写后走 [GameSession.store] 的 writeInt/writeStr（写入单点，不发明第二条
     * 写路径）；写前读旧值（先整数后字符串）、写后立即回读，输出 old→new 对；
     * 成功写入追加一行 JSONL 审计到 `<devDir>/audit.jsonl`（字段 ts/op/name/idx/old/new，
     * 与桌面 CmdSet 同序）。未定义名 / 维度不符 / 类型不符 / 常量赋值 → `status=CODEEE`
     * + 原文案，不抛裸栈。
     */
    internal fun setPure(session: GameSession, devDir: File, op: String, arg: String): String {
        val expr = arg.trim()
        if (expr.isEmpty()) {
            return "用法: set <名字>[i,j,...] = <整数 或 \"字符串\">"
        }
        val eq = expr.indexOf('=')
        if (eq <= 0) return "set 表达式缺 '=': $expr"
        val (name, indices) = try {
            parseLhs(expr.substring(0, eq).trim())
        } catch (e: CodeEE) {
            return e.message ?: "set 目标解析失败: $expr"
        }
        return applyWrite(session, devDir, op, name, indices, expr.substring(eq + 1).trim())
    }

    /**
     * `cheat` 纯逻辑，零脑补配方（照抄桌面 CmdSet.runCheat）：`cheat money <N>` =
     * `MONEY[0] = N`、`cheat day <N>` = `DAY[0] = N`（两变量都在系统 int1D 注册，可写）；
     * `cheat raw <名字>[i]=<值>` 是 set 的别名（审计 op 仍记 cheat）。eraTW 专用配方
     * （无敌 / 全解锁 / 好感度等）涉及具体变量语义，未按语料核实前**不猜** ——
     * TODO(verify)：留组长按语料 CSV 核后追加。
     */
    internal fun cheatPure(session: GameSession, devDir: File, arg: String): String {
        val toks = arg.split(Regex("\\s+")).filter { it.isNotBlank() }
        val recipe = toks.firstOrNull()?.lowercase()
            ?: return "用法: cheat money <N> | cheat day <N> | cheat raw <名字>[i]=<值>"
        return when (recipe) {
            "money" -> {
                val n = toks.getOrNull(1)?.toLongOrNull()
                    ?: return "cheat money 需要 <N> 整数实参"
                applyWrite(session, devDir, "cheat", "MONEY", longArrayOf(0L), n.toString())
            }
            "day" -> {
                val n = toks.getOrNull(1)?.toLongOrNull()
                    ?: return "cheat day 需要 <N> 整数实参"
                applyWrite(session, devDir, "cheat", "DAY", longArrayOf(0L), n.toString())
            }
            "raw" -> {
                val expr = toks.drop(1).joinToString(" ").trim()
                val eq = expr.indexOf('=')
                if (eq <= 0) return "cheat raw 表达式缺 '=': $expr"
                val (name, indices) = try {
                    parseLhs(expr.substring(0, eq).trim())
                } catch (e: CodeEE) {
                    return e.message ?: "cheat raw 目标解析失败: $expr"
                }
                applyWrite(session, devDir, "cheat", name, indices, expr.substring(eq + 1).trim())
            }
            else -> "未知 cheat 配方: $recipe（可用: money / day / raw；eraTW 专用配方待按语料核实后追加，见 TODO(verify)）"
        }
    }

    /** set / cheat 共用的写入主体（照抄桌面 CmdSet.apply：old→new + audit）。 */
    private fun applyWrite(
        session: GameSession,
        devDir: File,
        op: String,
        name: String,
        indices: LongArray,
        rhs: String,
    ): String {
        val store = session.store
        val uName = name.uppercase()
        val old = readAny(store, uName, indices)
        val parsed = parseValue(rhs)
        if (parsed == null) return "值解析失败: $rhs（整数，或首尾成对的双引号字符串）"
        try {
            when (parsed) {
                is Long -> store.writeInt(uName, indices, parsed)
                is String -> store.writeStr(uName, indices, parsed)
            }
        } catch (e: CodeEE) {
            return "status=CODEEE\nmessage=${e.message}"
        }
        val newStr = readAny(store, uName, indices)
        val auditNote = appendAudit(devDir, op, uName, indices, old, newStr)
        return "status=PASS\nname=$uName\nidx=${indices.joinToString(",", "[", "]")}\n" +
            "old=${old ?: "<unreadable>"}\nnew=${newStr ?: "<unreadable>"}$auditNote"
    }

    /** `名字[下标表]` → (名字, 下标)；与桌面 CmdSet.parseLhs 同形，缺省下标 `[0]`。 */
    private fun parseLhs(lhs: String): Pair<String, LongArray> {
        val open = lhs.indexOf('[')
        val name = if (open >= 0) lhs.substring(0, open).trim() else lhs.trim()
        if (name.isEmpty()) throw CodeEE("set/cheat 目标名为空: $lhs")
        if (open < 0) return name to longArrayOf(0L)
        val close = lhs.indexOf(']', open)
        if (close < 0) throw CodeEE("下标缺闭括号: $lhs")
        val body = lhs.substring(open + 1, close)
        if (body.isBlank()) throw CodeEE("下标为空: $lhs")
        val idx = body.split(',').map { seg ->
            seg.trim().toLongOrNull() ?: throw CodeEE("下标不是整数: ${seg.trim()}（$lhs）")
        }
        return name to idx.toLongArray()
    }

    /**
     * 值字面量 → Long / String：首尾成对双引号 = 字符串（剥引号，不做转义还原）；
     * 其余先试整数，失败按原样字符串（整数变量会得到可读 CodeEE）。空串按原样返回空串。
     */
    private fun parseValue(rhs: String): Any? = when {
        rhs.length >= 2 && rhs.first() == '"' && rhs.last() == '"' -> rhs.substring(1, rhs.length - 1)
        else -> rhs.toLongOrNull() ?: rhs
    }

    /** 先整数后字符串读旧/新值（与桌面 CmdSet.readAny 同一套回退）；两路都 CodeEE → null。 */
    private fun readAny(store: com.eratw.port.core.vars.VariableStore, name: String, indices: LongArray): String? {
        val i = try {
            store.readInt(name, indices).toString()
        } catch (_: CodeEE) {
            null
        }
        if (i != null) return i
        return try {
            store.readStr(name, indices)
        } catch (_: CodeEE) {
            null
        }
    }

    /**
     * 审计 trail：`<devDir>/audit.jsonl` 追加一行（字段序与桌面 CmdSet 一致：
     * ts/op/name/idx/old/new）。成功返回空串；写不进去**不回滚注入本体**（审计是旁路），
     * 但把失败原因补进结果文本——真机 logcat 可能被 ROM 吞，无声失败等于没记。
     */
    private fun appendAudit(
        devDir: File,
        op: String,
        name: String,
        indices: LongArray,
        old: String?,
        new: String?,
    ): String {
        val line = buildString {
            append("{\"ts\":").append(System.currentTimeMillis())
            append(",\"op\":\"").append(jsonEscape(op)).append('"')
            append(",\"name\":\"").append(jsonEscape(name)).append('"')
            append(",\"idx\":[").append(indices.joinToString(",")).append(']')
            append(",\"old\":").append(jsonValue(old))
            append(",\"new\":").append(jsonValue(new))
            append("}")
        }
        return try {
            devDir.mkdirs()
            File(devDir, "audit.jsonl").appendText(line + "\n", Charsets.UTF_8)
            ""
        } catch (e: Exception) {
            "\n（audit.jsonl 追加失败: ${e.message}）"
        }
    }

    /** old/new 统一字符串形式（null = 写前/写后不可读，JSON null）。 */
    private fun jsonValue(v: String?): String =
        if (v == null) "null" else "\"" + jsonEscape(v) + "\""

    /**
     * 字符串 JSON 转义（与桌面 vmdbg 的 Jsonl.escape 逐字同规则：反斜杠、引号、
     * 控制字符；tools 模块不可 import，照抄）。internal：[DevLogRecorder] 的 JSONL
     * 行发射复用同一实现。KDoc 里的转义示例有意省略反斜杠本体，规则以桌面实现为准。
     */
    internal fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                else ->
                    if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else sb.append(c)
            }
        }
        return sb.toString()
    }
}

/**
 * `log` 记录器的持有槽（DevToolsHost 一个实例一个槽）：挂上的 [DevLogRecorder] 在此，
 * `log`（落盘）/ `log off`（摘除）都从槽里找当前记录器。挂成 @Volatile：DevTools daemon
 * 线程读写、VM 线程只经观察口回调接触记录器本体（不碰槽）。
 *
 * 槽在 DevToolsHost 实例上（而非进程级单例）的理由与 DevProbe 同款：SingleDaemonSlot 的
 * 代次收口保证 Activity 重建后新实例从空槽开始，旧会话的记录器不会跨代残留——旧会话的
 * 观察口随旧会话一起失效，槽里即使有残留引用也无人可挂。
 */
internal class DevLogSlot {

    /** 当前记录器；null = log 未开。 */
    @Volatile
    var recorder: DevLogRecorder? = null
}

/**
 * `log` 的事件记录器：[FunctionProbe] / [RngProbe] / [VarWriteProbe] 三面合体（未选 tag
 * 不挂对应面，见 [DevState.logPure]）。行格式与桌面 CmdLog 的 LogRecorder 逐字一致：
 * `{"seq":n,"tag":"...","event":"...","payload":{...}}`（JSON 手拼——payload 带嵌套对象与
 * 数字数组，转义复用 [DevState.jsonEscape]）。
 *
 * ## 线程与回调纪律（DevProbe 同款 + VarWriteProbe 契约）
 *
 * - **环形缓冲**：定长 [ArrayDeque]（照抄 DevProbe 的 trace 环机制——容量定长、超限丢最旧
 *   并计数）；与桌面 LogRecorder 的「直接顺序写文件」不同，真机形态是持续记录 + 按需落盘，
 *   环内存上限换「黑匣子不撑爆手机存储」。
 * - **线程安全**：回调来自 VM 线程（functionProbe/drawProbe/writeProbe 都是 VM 线程同步
 *   回调，ADR-003），dump/off 来自 DevTools daemon 线程，set/cheat 的写入还会从 DevTools
 *   线程走一遍 writeProbe——所有 ring/count/seq 状态共用一把监视器锁，临界区纯内存操作。
 * - **不阻塞、不抛**：回调路径只有字符串拼接与集合操作；store 回读的 CodeEE 全部吞成
 *   null（与桌面 readAnyOld 同款回退）。
 * - **watch 名单 O(1) miss 零分配**：[VarWriteProbe.watches] 是写路径热点的守卫（写路径
 *   百万级/秒），每个监视名的原形与大写形同时入 [watchSet]，判定只做一次 Set.contains
 *   （KDoc 硬要求：miss 路径不做 uppercase 分配）。
 */
internal class DevLogRecorder(
    private val tags: Set<String>,
    watch: List<String>,
    private val max: Long,
    private val store: VariableStore,
) : FunctionProbe, RngProbe, VarWriteProbe {

    /** 监视名单：原形 + 大写形同入集（见类 KDoc 的零分配 miss 形态）。 */
    private val watchSet = HashSet<String>()

    private val lock = Any()

    /** 环形缓冲（旧→新；定长 [RING_CAPACITY]，超限丢最旧）。 */
    private val ring = ArrayDeque<String>()

    /** 已发出（未被 --max 截断）的事件总数；含已被环形覆盖的行。 */
    var count: Long = 0L
        private set

    /** 被环形覆盖掉的行数（dump 摘要展示「更早 N 行已被覆盖」）。 */
    var ringDropped: Long = 0L
        private set

    /** 是否因 [max] 截断（true = 有事件被丢弃，且此后不再记录）。 */
    var truncated: Boolean = false
        private set

    /** 行序号（JSONL 的 seq 字段；与本记录器生命周期一致，重挂 log 从 1 重新计）。 */
    private var seq = 0L

    init {
        for (n in watch) {
            watchSet.add(n)
            watchSet.add(n.uppercase())
        }
    }

    // ------------------------------------------------------------------
    // event
    // ------------------------------------------------------------------

    override fun onFunctionEnter(meta: FuncMeta?) {
        if ("event" in tags) emit("event", "func-enter", funcPayload(meta))
    }

    override fun onFunctionExit(meta: FuncMeta?, outcome: FunctionExitKind) {
        if ("event" in tags) {
            emit("event", "func-exit", funcPayload(meta) + ",\"outcome\":\"" + outcome.name + "\"")
        }
    }

    private fun funcPayload(meta: FuncMeta?): String =
        if (meta == null) {
            "\"fn\":null,\"key\":null,\"index\":null"
        } else {
            "\"fn\":\"" + DevState.jsonEscape(meta.name) + "\",\"key\":\"" + DevState.jsonEscape(meta.key) +
                "\",\"index\":" + meta.index
        }

    // ------------------------------------------------------------------
    // rng
    // ------------------------------------------------------------------

    override fun onDraw(value: Long, drawSeq: Long) {
        if ("rng" in tags) emit("rng", "draw", "\"value\":$value,\"seq\":$drawSeq")
    }

    // ------------------------------------------------------------------
    // var
    // ------------------------------------------------------------------

    override fun watches(name: String): Boolean = name in watchSet

    override fun onWriteInt(name: String, indices: LongArray, value: Long) {
        if ("var" !in tags) return
        emit("var", "write-int", varPayload(name, indices, readAnyOld(name, indices), value.toString()))
    }

    override fun onWriteStr(name: String, indices: LongArray, value: String) {
        if ("var" !in tags) return
        emit("var", "write-str", varPayload(name, indices, readAnyOld(name, indices), value))
    }

    /** old 值回读（先整数后字符串，与桌面 LogRecorder.readAnyOld 同一套回退）；不可读 = null。 */
    private fun readAnyOld(name: String, indices: LongArray): String? =
        try {
            store.readInt(name, indices).toString()
        } catch (_: CodeEE) {
            try {
                store.readStr(name, indices)
            } catch (_: CodeEE) {
                null
            }
        }

    private fun varPayload(name: String, indices: LongArray, oldStr: String?, new: String): String {
        val fn: String = try {
            store.currentFunction ?: ""
        } catch (_: Throwable) {
            ""
        }
        return "\"name\":\"" + DevState.jsonEscape(name) + "\",\"idx\":[" + indices.joinToString(",") + "]" +
            ",\"old\":" + (if (oldStr == null) "null" else "\"" + DevState.jsonEscape(oldStr) + "\"") +
            ",\"new\":\"" + DevState.jsonEscape(new) + "\"" +
            ",\"fn\":" + (if (fn.isEmpty()) "null" else "\"" + DevState.jsonEscape(fn) + "\"")
    }

    // ------------------------------------------------------------------
    // 行发射与读取
    // ------------------------------------------------------------------

    /**
     * 发一行；[max] 截断（置 truncated 并停止记录）。临界区 = 计数 + 行拼装 + 环维护，
     * 纯内存、微秒级（与 DevProbe 的锁纪律同款）。
     */
    private fun emit(tag: String, event: String, payload: String) {
        synchronized(lock) {
            if (count >= max) {
                truncated = true
                return
            }
            count++
            val line = "{\"seq\":" + (++seq) + ",\"tag\":\"" + tag + "\",\"event\":\"" + event +
                "\",\"payload\":{" + payload + "}}"
            if (ring.size >= RING_CAPACITY) {
                ring.removeFirst()
                ringDropped++
            }
            ring.addLast(line)
        }
    }

    /** 环快照（旧→新）；不消费（可反复 dump）。 */
    fun snapshotLines(): List<String> = synchronized(lock) { ArrayList(ring) }

    private companion object {
        /** 环容量（DevProbe trace 环的上限口径：20,000 行 ≈ 数 MB 文本，手机内存无压力）。 */
        const val RING_CAPACITY = 20_000
    }
}
