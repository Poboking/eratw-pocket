package com.eratw.port.android.dbg

import com.eratw.port.core.host.FileOps
import com.eratw.port.core.host.InputOutcome
import com.eratw.port.core.host.InputRequest
import com.eratw.port.core.host.InputSink
import com.eratw.port.core.host.NoopTimerSink
import com.eratw.port.core.host.TimerSink
import com.eratw.port.core.loader.BootMetadata
import com.eratw.port.core.loader.ProgramLoader
import com.eratw.port.core.proc.GameSession
import com.eratw.port.core.proc.RootedFileOps
import com.eratw.port.core.save.FileSystemOps
import com.eratw.port.core.save.RootedSaveFileIo
import com.eratw.port.core.save.SaveFileIo
import com.eratw.port.core.save.SaveFolderIo
import com.eratw.port.core.sem.SystemOps
import java.nio.file.Files
import java.nio.file.Path

/**
 * DBG 装置共享的会话装配层（DBG3，2026-10-25）：清扫（[FunctionSweeper]）与剧本推进
 * （PlayChainTest）共用同一条装配链，**口径与桌面 vmdbg 的 Assembler 逐字对齐**
 * （tools/vmdbg/src/main/kotlin/com/eratw/port/vmdbg/Assembler.kt）——平台差分的前提
 * 是两侧除了运行时（ART vs JVM）以外一切相同：
 *
 * - `randSeed = 20260226`（DBG1 钉死，两侧同值）；
 * - `frameGateEnabled = false`（headless 无帧读者，立即发布；只影响时机不改行内容）；
 * - `writeRoot = null`（SAVETEXT / OUTPUTLOG 拒写）；
 * - 存档沙箱 = [SandboxedSaveFileIo]（**读根 = 游戏根、写根 = 沙箱**）+ 惰性
 *   [DeferredSandboxSystemOps]——语料只读铁律的落地点，语义与桌面同款；
 * - AWAIT 墙钟兜底 = [WallTimerSink] 换装 `execContext.timer`（AWAIT 是让出边界又不经
 *   输入 sink，第三类无界点）。
 *
 * 桌面 vmdbg 在 `tools` 模块、androidTest 不可 import，本文件是它在设备侧的等价物；
 * 两侧改动必须同步（判据 = 同一 erbc 产物上 sweep 的平台差分干净）。
 */

/** 装配产物：会话 + 装置自留的侧车（清扫清单/剧本入口都用它）。 */
class DbgAssembled(
    val session: GameSession,
    val metadata: BootMetadata,
    /** 传入的输入 sink（[FunctionSweeper] 传 [ImmediateInputSink]，剧本传 null 走内建队列）。 */
    val overrideSink: ImmediateInputSink?,
    /** 存档沙箱根（已建好；调用方负责跨 run 清理）。 */
    val sandboxDir: Path,
)

object DbgAssembly {

    /** DBG 钉死的 RNG 种子（与桌面 Cli.RAND_SEED 同值）。 */
    const val RAND_SEED = 20260226L

    /** 墙钟预算缺省（毫秒；与桌面 Cli.DEFAULT_WALL_MS 同值）。 */
    const val DEFAULT_WALL_MS = 10_000L

    /**
     * 装配会话。[overrideSink] 非 null 时进 `inputSinkOverride`（脚本侧输入换源，
     * BEGIN 面仍吃内建 QueueInputSink）；null = 剧本模式（游戏输入走队列、由玩家线程投递）。
     *
     * @throws IllegalStateException 产物缺失 / 装配失败（原样冒泡给 instrumentation 记失败）。
     */
    fun assemble(
        erbcDir: Path,
        gameRoot: Path,
        sandboxDir: Path,
        maxStepsPerRun: Long,
        overrideSink: ImmediateInputSink?,
    ): DbgAssembled {
        val programFile = erbcDir.resolve("program.erbc")
        val metaFile = erbcDir.resolve("program.meta")
        if (!Files.isRegularFile(programFile) || !Files.isRegularFile(metaFile)) {
            error("erbc 产物缺失：$programFile / $metaFile（装置不 push 语料，热更通道归组长）")
        }
        // 沙箱从「全新安装」语义起：先清上一轮存档再建目录（读走真游戏根、写全落沙箱）。
        sandboxDir.toFile().let { if (it.exists()) it.deleteRecursively() }
        Files.createDirectories(sandboxDir)

        val program = ProgramLoader.loadMapped(programFile)
        val metadata = BootMetadata.read(metaFile)

        var session: GameSession? = null
        val systemOps = DeferredSandboxSystemOps(gameRoot, sandboxDir) {
            requireNotNull(session) { "会话尚未装配完成（存档语句不可能在装配期被调用）" }
        }
        val s = GameSession(
            gameRoot = gameRoot,
            program = program,
            metadata = metadata,
            maxStepsPerRun = maxStepsPerRun,
            randSeed = RAND_SEED,
            systemOps = systemOps,
            frameGateEnabled = false,
            inputSinkOverride = overrideSink,
        )
        session = s
        // AWAIT 墙钟兜底（overrideSink == null 的剧本模式不检查：budget 恒 null = 纯透传）。
        s.runtime.execContext.timer = WallTimerSink(overrideSink)
        return DbgAssembled(s, metadata, overrideSink, sandboxDir)
    }
}

/**
 * 存档沙箱的 [SaveFileIo]（语义与桌面 vmdbg 的同名类逐字一致）：**写/删全落沙箱根**，
 * **读与存在性照旧走游戏根**（随包的全局档等只读数据照常可读）。[SaveFolderIo]（旧档
 * 迁移，会 move 文件）整体指向沙箱。与生产构造（GameSession 的 FileSystemOps 缺省分支）
 * 同款口径，仅把「读写同一根」拆成「读根 = 游戏根 / 写根 = 沙箱」。
 */
class SandboxedSaveFileIo(
    gameRoot: Path,
    sandboxRoot: Path,
    readOps: FileOps? = null,
) : SaveFileIo, SaveFolderIo {

    private val readSide = RootedSaveFileIo(gameRoot, readOps)
    private val writeSide = RootedSaveFileIo(sandboxRoot)

    override fun exists(path: String): Boolean = readSide.exists(path)

    override fun read(path: String): ByteArray? = readSide.read(path)

    override fun write(path: String, bytes: ByteArray): Boolean = writeSide.write(path, bytes)

    override fun delete(path: String): Boolean = writeSide.delete(path)

    override fun isReadOnly(path: String): Boolean = readSide.isReadOnly(path)

    override fun saveFolderExists(): Boolean = writeSide.saveFolderExists()

    override fun createSaveFolder(): Boolean = writeSide.createSaveFolder()

    override fun rootSaveFileNames(): List<String> = writeSide.rootSaveFileNames()

    override fun moveToFile(fromRel: String, toRel: String): Boolean = writeSide.moveToFile(fromRel, toRel)
}

/**
 * 存档沙箱的 [SystemOps]：首次调用时才用会话的真实 store / saveConfig 建 [FileSystemOps]
 * （这两样是 GameSession 构造期产物，而 systemOps 又必须在构造期传入——唯一次序解是
 * 「先装配、首次使用再建」）。全部方法逐条转发。
 */
class DeferredSandboxSystemOps(
    gameRoot: Path,
    sandboxDir: Path,
    sessionRef: () -> GameSession,
) : SystemOps {

    private val io = SandboxedSaveFileIo(gameRoot, sandboxDir, RootedFileOps(gameRoot))

    private val delegate: FileSystemOps by lazy {
        val s = sessionRef()
        FileSystemOps(s.store, io, s.saveConfig)
    }

    override fun saveData(no: Int, text: String): Boolean = delegate.saveData(no, text)

    override fun loadData(no: Int) = delegate.loadData(no)

    override fun delData(no: Int) = delegate.delData(no)

    override fun saveGame() = delegate.saveGame()

    override fun loadGame() = delegate.loadGame()

    override fun saveGlobal() = delegate.saveGlobal()

    override fun loadGlobal(): Boolean = delegate.loadGlobal()

    override fun saveChara(nos: List<Long>, filename: String, text: String) =
        delegate.saveChara(nos, filename, text)

    override fun loadChara(filename: String) = delegate.loadChara(filename)

    override fun updateCheck(): Long = delegate.updateCheck()

    override fun callTrain(no: Long) = delegate.callTrain(no)

    override fun stopCallTrain() = delegate.stopCallTrain()

    override fun doTrain(no: Long) = delegate.doTrain(no)

    override fun upcheck() = delegate.upcheck()

    override fun cupcheck(target: Long) = delegate.cupcheck(target)
}

/**
 * sweep 逐函数挂起检测的墙钟预算（与桌面 vmdbg 同款）：立即应答 sink 下输入永远拿得到，
 * 循环体每圈都带输入等待时步数闸在每个让出点清零而永不触发——墙钟兜底。检查点挂在
 * 输入 sink 的两个等待入口与 [WallTimerSink.delay]（AWAIT 面）；超限抛
 * [WallLimitExceeded]（非 CodeEE，TRYC 家族捕不到），调用方折成 WALL_LIMIT 状态。
 */
class WallBudget(private val wallMs: Long) {

    private val startNs = System.nanoTime()

    /** 期间应答的输入次数（诊断用：WALL_LIMIT 的 message 里带上）。 */
    var inputRequests: Long = 0
        private set

    /** 超预算抛 [WallLimitExceeded]；`wallMs <= 0` = 关闭墙钟检查。 */
    fun check() {
        inputRequests++
        if (wallMs <= 0) return
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        if (elapsedMs > wallMs) throw WallLimitExceeded(wallMs, elapsedMs, inputRequests)
    }
}

/** 墙钟预算超限（非 CodeEE：不能被脚本 TRYC 捕获吞掉）。 */
class WallLimitExceeded(val wallMs: Long, val elapsedMs: Long, val requests: Long) :
    RuntimeException("dbg 函数挂起保护：墙钟 ${elapsedMs}ms 超过预算 ${wallMs}ms（期间应答 $requests 次输入）")

/**
 * 立即应答输入 sink（口径 = GameBootSmoke.kt:98 的 ScriptedInputSink 去掉顺序脚本；
 * 与桌面 vmdbg 的同名类逐字一致）：INT 有默认取默认、无默认取 0；STR 取默认或空串；
 * 任意键立即放行。不用阻塞队列——sweep 的输入永远不能等人。
 */
class ImmediateInputSink : InputSink {

    /** 当前墙钟预算；null = 不检查（call/剧本场景）。 */
    @Volatile
    var budget: WallBudget? = null

    override fun readAnyKey(anyKey: Boolean, stopMesSkip: Boolean) {
        budget?.check()
    }

    override fun waitInput(request: InputRequest): InputOutcome {
        budget?.check()
        return when (request.type) {
            InputRequest.InputType.INT ->
                if (request.hasDef) InputOutcome(intValue = request.defInt) else InputOutcome(intValue = 0L)
            InputRequest.InputType.STR ->
                InputOutcome(strValue = if (request.hasDef) request.defStr else "")
        }
    }
}

/**
 * AWAIT 族的墙钟兜底：`AWAIT` 语句走 [TimerSink.delay]，是让出边界（步数闸在此重算）
 * 又不经输入 sink——只挂输入侧时这里是漏风面。委托 [sink] 现读 budget（sweep 逐函数
 * 换装）；sink 为 null（剧本模式）或 budget 为 null 时与缺省 [NoopTimerSink] 等价。
 */
class WallTimerSink(private val sink: ImmediateInputSink?) : TimerSink {
    override fun delay(millis: Long) {
        sink?.budget?.check()
    }
}
